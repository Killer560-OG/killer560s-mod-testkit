package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.harness.WireCapture;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 65-join-fingerprint: can a server tell this client has killer560smod loaded?
 *
 * <p>Two halves, both read off the wire (the encoder's output, {@link WireCapture}), from the handshake to the end of
 * the body:
 * <ol>
 *   <li><b>What the client volunteers.</b> Every serverbound packet in all four phases. Custom payloads are decoded
 *   (channel, brand, the {@code minecraft:register} / {@code c:register} channel lists), client information and known
 *   packs are printed, movement is counted. Fails if any byte of any packet (except the probe's own echoes) contains
 *   "killer560" in any case. Writes the fingerprint to {@code build/join-fingerprint/}; a run WITHOUT the mod
 *   ({@code run-scenario.ps1 -NoMod}) writes the baseline, and a run WITH it fails unless its channels, register entries,
 *   brand, client information and known packs are identical to that baseline.</li>
 *   <li><b>What it answers when asked.</b> The translation/keybind probe anticheats use: the server places signs whose
 *   lines are {@code {translate:K, fallback:"ABSENT"}} and {@code {keybind:K}}, the client opens each one's editor and
 *   sends the lines back as {@code Component.getString()} resolved them (26.1.2 and 26.2 bytecode: the editor maps
 *   {@code SignText.getMessage} through {@code Component::getString}). Vanilla {@code key.jump} is the POSITIVE control
 *   and must come back resolved ("Jump", and a key name for the keybind); a made-up key is the negative control and must
 *   come back as its fallback / raw name. Every killer560smod key must come back unresolved too.</li>
 * </ol>
 * The server is labelled {@code mc.hypixel.net} so the mod behaves as it would there. The test server is a Fabric
 * server (Fabric API on it), so the configuration-phase {@code c:version}/{@code c:register} exchange happens here and
 * would not on a non-Fabric server; the play-phase {@code minecraft:register} is sent regardless.
 */
public class JoinFingerprintTest implements FabricClientGameTest {

    private static final String NAME = "65-join-fingerprint";
    private static final String NEEDLE = "killer560";
    private static final String FALLBACK = "ABSENT";

    /** Signs: a row at z = -663 facing south, the player 2.5 blocks south of them. Own coordinates. */
    private static final int CX = -660;
    private static final int CZ = -660;
    private static final int Y = 150;

    /** Packet types counted, not listed: movement, ticking and acknowledgements. */
    private static final Set<String> NOISE = Set.of("minecraft:move_player_pos", "minecraft:move_player_pos_rot",
            "minecraft:move_player_rot", "minecraft:move_player_status_only", "minecraft:client_tick_end",
            "minecraft:player_input", "minecraft:keep_alive", "minecraft:chunk_batch_received",
            "minecraft:accept_teleportation", "minecraft:swing", "minecraft:pong");

    /** One probe line: what the sign holds (SNBT component), and what a client WITHOUT the key must send back. */
    private record Probe(String label, String snbt, String key, Kind kind) {
    }

    private enum Kind { CONTROL_TRANSLATE, CONTROL_KEYBIND, NEGATIVE, MOD }

    private static List<Probe> probes() {
        List<Probe> p = new ArrayList<>();
        p.add(new Probe("translate key.jump (positive control)", translate("key.jump"), "key.jump", Kind.CONTROL_TRANSLATE));
        p.add(new Probe("keybind key.jump (positive control)", keybind("key.jump"), "key.jump", Kind.CONTROL_KEYBIND));
        p.add(new Probe("translate testkit.no.such.key (negative control)", translate("testkit.no.such.key"),
                "testkit.no.such.key", Kind.NEGATIVE));
        p.add(new Probe("keybind key.testkit.nosuch (negative control)", keybind("key.testkit.nosuch"),
                "key.testkit.nosuch", Kind.NEGATIVE));
        for (String k : List.of("killer560smod", "killer560smod.name", "killer560smod.title", "killer560smod.config.title",
                "killer560smod.correction_alarm", "subtitles.killer560smod.correction_alarm", "category.killer560smod",
                "key.categories.killer560smod", "key.category.killer560smod", "key.category.killer560smod.main",
                "key.killer560smod.open", "key.killer560smod.settings", "key.killer560smod.gui",
                "itemGroup.killer560smod", "modmenu.nameTranslation.killer560smod",
                "modmenu.descriptionTranslation.killer560smod", "modmenu.summaryTranslation.killer560smod")) {
            p.add(new Probe("translate " + k, translate(k), k, Kind.MOD));
        }
        for (String k : List.of("key.killer560smod.open", "key.killer560smod.settings", "key.killer560smod.gui",
                "key.killer560smod.menu")) {
            p.add(new Probe("keybind " + k, keybind(k), k, Kind.MOD));
        }
        while (p.size() % 4 != 0) {
            p.add(new Probe("(padding)", "{text:\"\"}", "", Kind.NEGATIVE));
        }
        return p;
    }

    private static String translate(String key) {
        return "{translate:\"" + key + "\",fallback:\"" + FALLBACK + "\"}";
    }

    private static String keybind(String key) {
        return "{keybind:\"" + key + "\"}";
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        List<Probe> probes = probes();
        int signs = probes.size() / 4;
        // Armed BEFORE Scenario.run connects, so the handshake, login and configuration phases are on the record.
        WireCapture.start();
        Scenario.labelServerAs("mc.hypixel.net");
        try {
            Scenario.runExpectingFlags(ctx, NAME,
                    (server, scenario) -> {
                        TestMap map = TestMap.on(server).platform(CX, Y, CZ, 6).survival().clearInventory();
                        for (int i = 0; i < signs; i++) {
                            StringBuilder lines = new StringBuilder();
                            for (int l = 0; l < 4; l++) {
                                lines.append(l == 0 ? "" : ",").append(probes.get(i * 4 + l).snbt());
                            }
                            map.command(String.format(Locale.ROOT,
                                    "setblock %d %d %d minecraft:oak_sign[rotation=0]{front_text:{messages:[%s]}}",
                                    signX(i), Y + 1, CZ - 3, lines));
                        }
                        map.spawn(CX + 0.5, CZ + 0.5, 180f, 20f).build();
                    },
                    (server, scenario) -> body(ctx, server, scenario, probes, signs));
        } finally {
            WireCapture.stop();
        }
    }

    private static int signX(int i) {
        return CX - 2 + i;
    }

    private static void body(ClientGameTestContext ctx, TestServer server, Scenario scenario, List<Probe> probes,
                             int signs) {
        boolean modLoaded = FabricLoader.getInstance().isModLoaded("killer560smod");
        String mc = FabricLoader.getInstance().getModContainer("minecraft").orElseThrow().getMetadata().getVersion()
                .getFriendlyString();
        String addMods = System.getProperty("fabric.addMods", "");
        String jar = addMods.isBlank() ? "none" : Path.of(addMods.split(java.io.File.pathSeparator)[0]).getFileName().toString();
        scenario.log("Minecraft " + mc + ", killer560smod loaded: " + modLoaded + " (fabric.addMods jar: " + jar + ")");
        scenario.log("player name: " + ctx.computeOnClient(m -> m.player.getGameProfile().name()));

        // ~15 s after the first play-phase packet, so anything the mod sends shortly after a join is on the record.
        long firstPlay = WireCapture.snapshot().stream().filter(s -> s.phase().equals("play")).mapToLong(WireCapture.Sent::nanos)
                .min().orElseThrow(() -> new AssertionError("no play-phase packet was captured - the capture never ran"));
        while ((System.nanoTime() - firstPlay) / 1_000_000L < 15_000L) {
            ctx.waitTicks(10);
        }
        List<WireCapture.Sent> joinWindow = WireCapture.snapshot();
        int joinCount = joinWindow.size();

        // ---- the translation / keybind probe ----
        // First the vanilla way in: right-click the first sign. Vanilla's server only opens the editor when every line
        // is plain text (SignBlock.hasEditableText), so this is expected NOT to open; it is recorded, not asserted.
        BlockPos first = new BlockPos(signX(0), Y + 1, CZ - 3);
        ctx.waitFor(m -> m.level.getBlockEntity(first) instanceof net.minecraft.world.level.block.entity.SignBlockEntity, 200);
        click(ctx, first);
        ctx.waitTicks(20);
        boolean vanillaOpened = ctx.computeOnClient(m -> McCompat.screen(m) instanceof AbstractSignEditScreen);
        scenario.log("vanilla right-click on a sign holding translate/keybind lines opened the editor: " + vanillaOpened
                + " (a probing server sends the open-editor packet itself, which is what follows)");
        if (vanillaOpened) {
            ctx.runOnClient(m -> McCompat.setScreen(m, null));
            ctx.waitTicks(5);
        }
        List<String[]> answered = new ArrayList<>();
        for (int i = 0; i < signs; i++) {
            BlockPos pos = new BlockPos(signX(i), Y + 1, CZ - 3);
            int before = countType(WireCapture.snapshot(), "minecraft:sign_update");
            boolean isSign = ctx.computeOnClient(m -> m.level.getBlockEntity(pos)
                    instanceof net.minecraft.world.level.block.entity.SignBlockEntity);
            if (!isSign) {
                ctx.waitFor(m -> m.level.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.SignBlockEntity, 200);
            }
            server.command(String.format(Locale.ROOT, "testkit opensign %d %d %d", pos.getX(), pos.getY(), pos.getZ()));
            try {
                ctx.waitFor(m -> McCompat.screen(m) instanceof AbstractSignEditScreen, 100);
            } catch (AssertionError e) {
                throw new AssertionError("sign " + i + " at " + pos.toShortString() + " never opened its editor: "
                        + ctx.computeOnClient(m -> String.valueOf(McCompat.screen(m))), e);
            }
            ctx.waitTicks(2);
            ctx.runOnClient(m -> McCompat.setScreen(m, null));
            ctx.waitFor(m -> countType(WireCapture.snapshot(), "minecraft:sign_update") > before, 100);
            List<WireCapture.Sent> updates = WireCapture.snapshot().stream()
                    .filter(s -> s.type().equals("minecraft:sign_update")).toList();
            answered.add(decodeSignUpdate(updates.get(updates.size() - 1).bytes()));
        }
        List<WireCapture.Sent> all = WireCapture.snapshot();

        // ---- report: every packet, by phase ----
        Map<String, Integer> perPhase = new LinkedHashMap<>();
        Map<String, Integer> noise = new TreeMap<>();
        Set<String> fingerprint = new TreeSet<>();
        Set<String> typesSent = new TreeSet<>();
        List<String> needleHits = new ArrayList<>();
        // The scanner's own positive control: the probe's keybind lines come back as the raw "key.killer560smod.*"
        // name, so the same byte scan must find the needle in those sign_update packets.
        int scannerControl = 0;
        String brand = null;
        long t0 = all.isEmpty() ? 0 : all.get(0).nanos();
        scenario.log("==== every serverbound packet from the handshake to the end of the probe (" + all.size()
                + "; the first " + joinCount + " are the ~15 s join window) ====");
        for (int idx = 0; idx < all.size(); idx++) {
            WireCapture.Sent s = all.get(idx);
            perPhase.merge(s.phase(), 1, Integer::sum);
            typesSent.add("type " + s.phase() + " " + s.type());
            String lower = new String(s.bytes(), StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
            if (lower.contains(NEEDLE)) {
                if (s.type().equals("minecraft:sign_update")) {
                    scannerControl++;
                } else {
                    needleHits.add(s.phase() + " " + s.type() + " " + printable(s.bytes()));
                }
            }
            if (NOISE.contains(s.type())) {
                noise.merge(s.phase() + " " + s.type(), 1, Integer::sum);
                continue;
            }
            String at = String.format(Locale.ROOT, "+%6d ms %-13s %-40s %5d B", (s.nanos() - t0) / 1_000_000L,
                    s.phase(), s.type(), s.bytes().length);
            if (s.type().equals("minecraft:custom_payload")) {
                int[] p = {0};
                readVarInt(s.bytes(), p);
                String channel = readString(s.bytes(), p);
                byte[] data = java.util.Arrays.copyOfRange(s.bytes(), p[0], s.bytes().length);
                fingerprint.add("channel " + s.phase() + " " + channel);
                scenario.log(at + "  channel " + channel + " (" + data.length + " B data)");
                if (channel.equals("minecraft:brand")) {
                    int[] q = {0};
                    brand = readString(data, q);
                    fingerprint.add("brand " + s.phase() + " " + brand);
                    scenario.log("        brand = \"" + brand + "\"");
                } else if (channel.equals("minecraft:register") || channel.equals("minecraft:unregister")) {
                    for (String entry : new String(data, StandardCharsets.UTF_8).split("\0")) {
                        if (!entry.isEmpty()) {
                            fingerprint.add("register " + s.phase() + " " + channel + " " + entry);
                            scenario.log("        " + channel + " entry: " + entry);
                        }
                    }
                } else if (channel.equals("c:register")) {
                    for (String entry : decodeCommonRegister(data)) {
                        fingerprint.add("register " + s.phase() + " " + channel + " " + entry);
                        scenario.log("        c:register " + entry);
                    }
                } else {
                    String text = printable(data);
                    fingerprint.add("payload " + s.phase() + " " + channel + " " + hex(data, 64));
                    scenario.log("        data hex " + hex(data, 64) + "  text \"" + text + "\"");
                }
            } else {
                scenario.log(at + "  " + clip(s.text(), 400));
                if (s.type().equals("minecraft:client_information") || s.type().equals("minecraft:select_known_packs")) {
                    fingerprint.add("info " + s.phase() + " " + s.type() + " " + s.text());
                }
                if (!s.type().equals("minecraft:sign_update")) {
                    String text = printable(java.util.Arrays.copyOfRange(s.bytes(), 1, s.bytes().length));
                    if (!text.isBlank()) {
                        scenario.log("        strings on the wire: \"" + clip(text, 300) + "\"");
                    }
                }
            }
        }
        scenario.log("==== counted, not listed ====");
        noise.forEach((k, v) -> scenario.log(String.format(Locale.ROOT, "  %5d x %s", v, k)));
        scenario.log("==== per phase: " + perPhase + " ====");
        scenario.log("==== packet types sent ====");
        typesSent.forEach(t -> scenario.log("  " + t));

        // ---- the probe's answers ----
        scenario.log("==== translation / keybind probe: what the client sent back in sign_update ====");
        List<String> probeFailures = new ArrayList<>();
        int modProbes = 0;
        for (int i = 0; i < probes.size(); i++) {
            Probe probe = probes.get(i);
            String got = answered.get(i / 4)[i % 4];
            String verdict;
            switch (probe.kind()) {
                case CONTROL_TRANSLATE -> {
                    verdict = "Jump".equals(got) ? "RESOLVED (control OK)" : "control FAILED";
                    if (!"Jump".equals(got)) {
                        probeFailures.add("positive control " + probe.label() + " came back \"" + got + "\", not \"Jump\"");
                    }
                }
                case CONTROL_KEYBIND -> {
                    boolean ok = !got.isEmpty() && !got.equals(probe.key());
                    verdict = ok ? "RESOLVED (control OK)" : "control FAILED";
                    if (!ok) {
                        probeFailures.add("positive control " + probe.label() + " came back \"" + got + "\" (unresolved)");
                    }
                }
                case NEGATIVE -> {
                    String expect = probe.key().isEmpty() ? "" : probe.snbt().startsWith("{translate") ? FALLBACK
                            : probe.key();
                    verdict = got.equals(expect) ? "unresolved (as expected)" : "UNEXPECTED";
                    if (!got.equals(expect)) {
                        probeFailures.add("negative control " + probe.label() + " came back \"" + got + "\", expected \""
                                + expect + "\"");
                    }
                }
                default -> {
                    modProbes++;
                    String expect = probe.snbt().startsWith("{translate") ? FALLBACK : probe.key();
                    verdict = got.equals(expect) ? "unresolved - not detectable" : "RESOLVED - LEAK";
                    if (!got.equals(expect)) {
                        probeFailures.add("mod key " + probe.label() + " RESOLVED to \"" + got + "\"");
                    }
                }
            }
            if (!probe.label().equals("(padding)")) {
                scenario.log(String.format(Locale.ROOT, "  %-58s -> \"%s\"  %s", probe.label(), got, verdict));
            }
        }

        // ---- fingerprint file and the baseline comparison ----
        Path dir = Path.of(System.getProperty("testkit.checkout", "."), "build", "join-fingerprint");
        String label = modLoaded ? "mod-" + jar.replace(".jar", "") : "nomod-" + mc;
        try {
            Files.createDirectories(dir);
            Files.write(dir.resolve(label + ".txt"), new ArrayList<>(fingerprint), StandardCharsets.UTF_8);
            Files.write(dir.resolve(label + "-types.txt"), new ArrayList<>(typesSent), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("could not write the fingerprint to " + dir, e);
        }
        scenario.log("==== fingerprint (" + fingerprint.size() + " lines, written to " + dir.resolve(label + ".txt") + ") ====");
        fingerprint.forEach(f -> scenario.log("  " + clip(f, 400)));

        List<String> failures = new ArrayList<>();
        for (String phase : List.of("handshake", "login", "configuration", "play")) {
            if (perPhase.getOrDefault(phase, 0) == 0) {
                failures.add("no " + phase + "-phase packet captured - the capture did not see the whole join");
            }
        }
        if (brand == null) {
            failures.add("no minecraft:brand payload captured - the capture missed the configuration phase");
        }
        if (answered.size() != signs) {
            failures.add("only " + answered.size() + " of " + signs + " signs answered");
        }
        scenario.log("byte scanner control: \"" + NEEDLE + "\" found in " + scannerControl
                + " sign_update packet(s) (the probe's own echoes; must be > 0)");
        if (scannerControl == 0) {
            failures.add("the byte scanner did not find \"" + NEEDLE + "\" even in the probe's echoes - it is blind");
        }
        if (modLoaded && modProbes == 0) {
            failures.add("no mod probe was judged");
        }
        failures.addAll(probeFailures);
        if (!needleHits.isEmpty()) {
            for (String hit : needleHits) {
                failures.add("\"" + NEEDLE + "\" on the wire: " + clip(hit, 300));
            }
        }
        if (modLoaded) {
            // The committed baseline first (baselines/join-fingerprint/, copied from a -NoMod run), so a shard
            // worktree or a fresh checkout compares without a -NoMod run of its own; this checkout's build output
            // only when nothing is committed for this version.
            Path committed = Path.of(System.getProperty("testkit.checkout", "."), "baselines", "join-fingerprint");
            Path baseDir = Files.exists(committed.resolve("nomod-" + mc + ".txt")) ? committed : dir;
            Path base = baseDir.resolve("nomod-" + mc + ".txt");
            if (!Files.exists(base)) {
                failures.add("no baseline at " + base + " - run this scenario once with run-scenario.ps1 -NoMod and copy build/join-fingerprint/nomod-*.txt into baselines/join-fingerprint/");
            } else {
                try {
                    Set<String> baseline = new TreeSet<>(Files.readAllLines(base, StandardCharsets.UTF_8));
                    Set<String> added = new TreeSet<>(fingerprint);
                    added.removeAll(baseline);
                    Set<String> missing = new TreeSet<>(baseline);
                    missing.removeAll(fingerprint);
                    scenario.log("==== against the no-mod baseline " + base.getFileName() + " (" + baseline.size()
                            + " lines): " + added.size() + " added, " + missing.size() + " missing ====");
                    added.forEach(a -> failures.add("only WITH the mod: " + clip(a, 300)));
                    missing.forEach(m -> failures.add("only WITHOUT the mod: " + clip(m, 300)));
                    Path baseTypes = baseDir.resolve("nomod-" + mc + "-types.txt");
                    if (Files.exists(baseTypes)) {
                        Set<String> bt = new TreeSet<>(Files.readAllLines(baseTypes, StandardCharsets.UTF_8));
                        Set<String> extra = new TreeSet<>(typesSent);
                        extra.removeAll(bt);
                        Set<String> gone = new TreeSet<>(bt);
                        gone.removeAll(typesSent);
                        scenario.log("packet types only with the mod: " + extra + "; only without: " + gone
                                + " (informational: what the mod DOES, not who it is)");
                    }
                } catch (IOException e) {
                    failures.add("could not read the baseline " + base + ": " + e);
                }
            }
        }
        if (!failures.isEmpty()) {
            StringBuilder m = new StringBuilder("[" + NAME + "] " + failures.size() + " problem(s):");
            failures.forEach(f -> m.append(System.lineSeparator()).append("    ").append(f));
            System.out.println(m);
            throw new AssertionError(m.toString());
        }
        scenario.log("PASS: " + all.size() + " packets captured across " + perPhase.keySet() + ", brand \"" + brand
                + "\", controls resolved, " + modProbes + " mod probes unresolved, no \"" + NEEDLE + "\" on the wire"
                + (modLoaded ? ", fingerprint identical to the no-mod baseline" : " (baseline written)"));
    }

    // ---- wire decoding -------------------------------------------------------------------------------------

    private static int countType(List<WireCapture.Sent> list, String type) {
        int n = 0;
        for (WireCapture.Sent s : list) {
            if (s.type().equals(type)) {
                n++;
            }
        }
        return n;
    }

    /** sign_update: id VarInt, BlockPos long, boolean front, four strings. */
    private static String[] decodeSignUpdate(byte[] b) {
        int[] p = {0};
        readVarInt(b, p);
        p[0] += 8 + 1;
        String[] lines = new String[4];
        for (int i = 0; i < 4; i++) {
            lines[i] = readString(b, p);
        }
        return lines;
    }

    /** Fabric's c:register: VarInt version, String phase, then a VarInt-counted list of identifiers. */
    private static List<String> decodeCommonRegister(byte[] data) {
        List<String> out = new ArrayList<>();
        try {
            int[] p = {0};
            int version = readVarInt(data, p);
            String phase = readString(data, p);
            int n = readVarInt(data, p);
            for (int i = 0; i < n; i++) {
                out.add("[v" + version + " " + phase + "] " + readString(data, p));
            }
            if (p[0] != data.length) {
                out.add("(" + (data.length - p[0]) + " trailing bytes: " + hex(java.util.Arrays.copyOfRange(data, p[0],
                        data.length), 64) + ")");
            }
        } catch (RuntimeException e) {
            out.add("(undecoded: " + hex(data, 200) + " \"" + printable(data) + "\")");
        }
        return out;
    }

    private static int readVarInt(byte[] b, int[] p) {
        int value = 0;
        int shift = 0;
        while (true) {
            byte x = b[p[0]++];
            value |= (x & 0x7F) << shift;
            if ((x & 0x80) == 0) {
                return value;
            }
            shift += 7;
            if (shift > 35) {
                throw new IllegalStateException("VarInt too big");
            }
        }
    }

    private static String readString(byte[] b, int[] p) {
        int len = readVarInt(b, p);
        String s = new String(b, p[0], len, StandardCharsets.UTF_8);
        p[0] += len;
        return s;
    }

    /** Runs of 3+ printable ASCII characters, joined by " | ". */
    private static String printable(byte[] b) {
        StringBuilder out = new StringBuilder();
        StringBuilder run = new StringBuilder();
        for (byte x : b) {
            if (x >= 0x20 && x < 0x7F) {
                run.append((char) x);
            } else {
                if (run.length() >= 3) {
                    out.append(out.length() == 0 ? "" : " | ").append(run);
                }
                run.setLength(0);
            }
        }
        if (run.length() >= 3) {
            out.append(out.length() == 0 ? "" : " | ").append(run);
        }
        return out.toString();
    }

    private static String hex(byte[] b, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(b.length, max); i++) {
            sb.append(String.format(Locale.ROOT, "%02x", b[i]));
        }
        return b.length > max ? sb + "..." : sb.toString();
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...(" + s.length() + " chars)";
    }

    /** Turn to the sign and use it, the way {@code boss.SimonSaysCases.click} does. */
    private static void click(ClientGameTestContext ctx, BlockPos pos) {
        ctx.runOnClient(m -> {
            Vec3 eye = m.player.getEyePosition();
            Vec3 d = Vec3.atCenterOf(pos).subtract(eye);
            m.player.setYRot((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
            m.player.setXRot((float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z))));
        });
        ctx.waitTicks(2);
        ctx.runOnClient(m -> {
            Vec3 eye = m.player.getEyePosition();
            Vec3 target = Vec3.atCenterOf(pos);
            var shape = m.level.getBlockState(pos).getShape(m.level, pos);
            BlockHitResult hit = shape.isEmpty() ? null
                    : shape.clip(eye, eye.add(target.subtract(eye).normalize().scale(eye.distanceTo(target) + 1.5)), pos);
            if (hit == null) {
                hit = new BlockHitResult(target, Direction.SOUTH, pos, false);
            }
            m.gameMode.useItemOn(m.player, InteractionHand.MAIN_HAND, hit);
        });
    }
}
