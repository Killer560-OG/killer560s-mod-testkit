package dev.testkit.gametest.boss;

import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.SameTick;
import dev.testkit.gametest.Scenario;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.PacketTrace;
import dev.testkit.harness.WireCapture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 1109-boss-ee3route-grim (ee3route-1109): the HEE2 -> EE3 route file {@code fastest.json} on GrimAC. The stretch's map
 * (corridor, S2 pit, low-lever platform, Breaker tunnel, walkway to EE3) is rebuilt from his P3 capture as /fill commands
 * ({@code killer560s-mod-logs/ee3route-1109/fills-env.txt}, written by fills.py there; full cubes as stone). Lever Aura
 * flicks both S2 levers (with their "Not Activated" stands) while AP3 holds the walk (leverap3-1109: the off-walk look),
 * the BOOM node sends its Superboom tap and the server then clears gate 2's arch cells (SimBossTests DOOR_BOX 2), as Hypixel would; GrimAC must stay
 * silent (Session.test) on the default and experimental configs.
 * <ul>
 *   <li>1109-boss-ee3route-grim: the route's Breaker picks stay in the world and are dug on the way (ee3route-align-1117).</li>
 *   <li>1109-boss-ee3route-same-&lt;use|swap|dig&gt; (SAME-TICK GRIM CASES): his own right click, hotbar swap or dig goes
 *       into the route's ticks ({@link SameTick}); use and swap must share a server tick with a lever click, a queued dig
 *       must hold the lever click to another tick.</li>
 * </ul>
 */
final class Ee3RouteGrimCases {

    private static final Path DIR = Path.of(System.getProperty("user.home"), "killer560s-mod-logs", "ee3route-1109");
    private static final String KM_DIR = System.getenv("KM_EE3_DIR");
    /** Where the EE3 route finder puts the route it proved in the sim (ROUTE.json + ROUTE.picks). */
    private static final Path VERIFIED = Path.of(System.getProperty("user.home"), "killer560s-mod-logs", "ap3finder-1112", "verified");
    private static final String ROUTE = System.getProperty("testkit.ee3.route", KM_DIR == null ? "ee3" : "fastest");
    private static final BlockPos LOW = new BlockPos(27, 124, 127);
    private static final BlockPos HIGH = new BlockPos(23, 132, 138);
    private static final String CU = "cheatutils.CheatUtilsConfig";
    private static final String LA = "leveraura.LeverAuraConfig";
    private static final Predicate<Packet<?>> LEVER_CLICK = p -> p instanceof ServerboundUseItemOnPacket u
            && (hitPos(u).equals(LOW) || hitPos(u).equals(HIGH));
    /** The clicked block (26.3's record accessor is hitResult(), 26.2's getHitResult()). */
    private static final java.lang.reflect.Method HIT = hitMethod();

    private static java.lang.reflect.Method hitMethod() {
        for (String n : new String[]{"hitResult", "getHitResult"}) {
            try {
                return ServerboundUseItemOnPacket.class.getMethod(n);
            } catch (NoSuchMethodException e) {
                // the other name
            }
        }
        throw new AssertionError("no hit result accessor on ServerboundUseItemOnPacket");
    }

    private static BlockPos hitPos(ServerboundUseItemOnPacket u) {
        try {
            return ((net.minecraft.world.phys.BlockHitResult) HIT.invoke(u)).getBlockPos();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private Ee3RouteGrimCases() {
    }

    static void register(Session s) {
        if (!Mod.isCheat()) {
            return;
        }
        // Only a route proven in the sim is judged here: the one the EE3 route finder installs (ap3finder-1112/verified),
        // or a candidate folder named with KM_EE3_DIR. Until one exists every case is a SKIP row, not a failure: the old
        // candidates/fastest.json falls into the lava, which is the finder's question, not Grim's (train t1112-1409).
        String none = KM_DIR == null && !Files.isRegularFile(VERIFIED.resolve(ROUTE + ".json"))
                ? "no verified EE3 route yet (" + VERIFIED.resolve(ROUTE + ".json") + " is written by the route finder)" : null;
        add(s, "1109-boss-ee3route-grim", none, c -> run(c, null, true));
        add(s, "1109-boss-ee3route-nolook-off", none, Ee3RouteGrimCases::runOff);
        add(s, "1109-boss-ee3route-same-use", none, c -> run(c, SameTick.Act.USE, true));
        add(s, "1109-boss-ee3route-same-swap", none, c -> run(c, SameTick.Act.SWAP, true));
        add(s, "1109-boss-ee3route-same-dig", none, c -> run(c, SameTick.Act.DIG, true));
        add(s, "1123-boss-ee3route-swap-walk", none, Ee3RouteGrimCases::swapWalk);
        // ee3race-1123: the plain run again and again, over one or more candidate folders, in one client
        // (KM_EE3_REPEAT=r23a:5,m26a:5 with any KM_EE3_DIR, so the file names are fastest.*); a SKIP row without them.
        add(s, "1123-boss-ee3route-repeat", REPEAT == null || KM_DIR == null ? "KM_EE3_REPEAT / KM_EE3_DIR not set (e.g. r23a:5,m26a:5)"
                : null, Ee3RouteGrimCases::repeat);
    }

    private static void add(Session s, String name, String skip, Session.Case body) {
        if (skip == null) {
            s.test(name, body);
        } else {
            s.shelved(name, skip, body);
        }
    }

    private static boolean powered(net.minecraft.client.Minecraft mc, BlockPos p) {
        BlockState st = mc.level.getBlockState(p);
        return st.getBlock() instanceof LeverBlock && st.getValue(LeverBlock.POWERED);
    }

    /** GrimAC's ticks on the wire (a movement packet, or client_tick_end with none before it): lever clicks and digs. */
    private static final class Ticks implements Consumer<Packet<?>> {
        final Consumer<Packet<?>> prev = PacketTrace.tap;
        int lever;
        int dig;
        int twoLevers;
        int leverAndDig;
        int curLever;
        int curDig;
        boolean moved;

        @Override
        public synchronized void accept(Packet<?> p) {
            if (prev != null) {
                prev.accept(p);
            }
            if (LEVER_CLICK.test(p)) {
                lever++;
                curLever++;
            } else if (p instanceof ServerboundPlayerActionPacket a
                    && a.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK) {
                dig++;
                curDig++;
            }
            boolean end = p instanceof ServerboundMovePlayerPacket || (p instanceof ServerboundClientTickEndPacket && !moved);
            if (p instanceof ServerboundMovePlayerPacket) {
                moved = true;
            } else if (p instanceof ServerboundClientTickEndPacket) {
                moved = false;
            }
            if (end) {
                twoLevers += curLever > 1 ? 1 : 0;
                leverAndDig += curLever > 0 && curDig > 0 ? 1 : 0;
                curLever = 0;
                curDig = 0;
            }
        }

        synchronized String report() {
            return "lever clicks " + lever + ", digs " + dig + ", ticks with two lever clicks " + twoLevers
                    + ", ticks with a lever click and a dig " + leverAndDig;
        }
    }

    private static String describe(Object p) {
        if (p instanceof ServerboundPlayerActionPacket a) {
            return "dig " + a.getAction();
        }
        if (p instanceof ServerboundMovePlayerPacket m) {
            return (m.hasRotation() ? "move rot " : "move ") + String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f", m.getX(0), m.getY(0), m.getZ(0));
        }
        if (p instanceof net.minecraft.network.protocol.game.ServerboundPlayerInputPacket in) {
            net.minecraft.world.entity.player.Input i = in.input();
            return "input " + (i.forward() ? "W" : "") + (i.left() ? "A" : "") + (i.backward() ? "S" : "") + (i.right() ? "D" : "")
                    + (i.sprint() ? " sprint" : "");
        }
        return p.getClass().getSimpleName();
    }

    /** From the wire (encoder order): {ticks with a START dig, of those a rotating move after the dig, a move before it}. */
    private static int[] digTicks() {
        int[] out = new int[3];
        boolean dig = false;
        boolean before = false;
        boolean after = false;
        boolean moved = false;
        for (WireCapture.Sent s : WireCapture.snapshot()) {
            if (!s.phase().equals("play")) {
                continue;
            }
            String t = s.text();
            if (t.equals("dig START_DESTROY_BLOCK")) {
                dig = true;
                before |= moved;
            } else if (t.startsWith("move")) {
                moved = true;
                after |= dig && t.startsWith("move rot");
            } else if (t.equals("ServerboundClientTickEndPacket")) {
                if (dig) {
                    out[0]++;
                    out[1] += after ? 1 : 0;
                    out[2] += before ? 1 : 0;
                }
                dig = before = after = moved = false;
            }
        }
        return out;
    }

    private static double boxDistSq(double ex, double ey, double ez, BlockPos p) {
        double dx = ex - Math.max(p.getX(), Math.min(ex, p.getX() + 1));
        double dy = ey - Math.max(p.getY(), Math.min(ey, p.getY() + 1));
        double dz = ez - Math.max(p.getZ(), Math.min(ez, p.getZ() + 1));
        return dx * dx + dy * dy + dz * dz;
    }

    private static final String REPEAT = System.getenv("KM_EE3_REPEAT");
    /** The candidate folder {@link #repeat} is running now (null = KM_EE3_DIR / verified). */
    private static String dirNow;

    private static Path routeDir() {
        return dirNow != null ? DIR.resolve(dirNow) : KM_DIR == null ? VERIFIED : DIR.resolve(KM_DIR);
    }

    /** 1123-boss-ee3route-repeat: each folder's plain run (the grim case) N times; every run must arrive. */
    private static void repeat(Session c) throws Exception {
        List<String> bad = new ArrayList<>();
        int n = 0;
        try {
            for (String part : REPEAT.split(",")) {
                String[] kv = part.strip().split(":");
                dirNow = kv[0];
                for (int i = 1; i <= Integer.parseInt(kv[1]); i++) {
                    n++;
                    try {
                        run(c, null, true);
                        // Each run() moves the server mark, so the case's own Grim judgment sees only the LAST run: judge each.
                        List<String> flags = c.scenario().flags().stream().filter(l -> l.contains("failed")).toList();
                        c.check(flags.isEmpty(), "Grim flagged the run " + flags.size() + " time(s): " + flags);
                        c.note("repeat " + kv[0] + " #" + i + ": arrived, no Grim flag");
                    } catch (AssertionError e) {
                        c.note("repeat " + kv[0] + " #" + i + ": FAILED " + e.getMessage());
                        bad.add(kv[0] + " #" + i);
                    }
                }
            }
        } finally {
            dirNow = null;
        }
        c.check(n > 0 && bad.isEmpty(), bad.size() + " of " + n + " plain runs failed: " + bad);
    }

    /** Lifts GrimAC's RotationPlace cancelvl for one case (punishments.yml + /grim reload); the returned closer puts it back. */
    private static AutoCloseable raiseCancelVl(Session c) throws Exception {
        java.util.Properties props = new java.util.Properties();
        try (var in = Files.newInputStream(Path.of(System.getProperty("testkit.testserver.launch")))) {
            props.load(in);
        }
        Path f = Path.of(props.getProperty("dir"), "config", "GrimAC", "punishments.yml");
        String old = Files.readString(f);
        String lifted = old.replaceAll("(RotationPlace:\\s+cancelvl: )\\d+", "$1100000");
        if (lifted.equals(old)) {
            lifted = old + (old.endsWith("\n") ? "" : "\n") + "RotationPlace:\n    cancelvl: 100000\n"; // no entry: Grim's own default cancels
        }
        Files.writeString(f, lifted);
        c.server().command("grim reload");
        c.ctx().waitTicks(10);
        return () -> {
            Files.writeString(f, old);
            c.server().command("grim reload");
        };
    }

    /** The no-look click switch OFF (its label says it flags Grim): the lever waits for a one-packet look and Grim shows ZERO lines,
     *  on default and experimental Grim. Whether the levers go is not judged here (they may be missed), only that nothing flags. */
    private static void runOff(Session c) throws Exception {
        long m0 = LogTap.mark();
        try {
            run(c, null, false);
        } catch (AssertionError e) {
            c.note("switch OFF, route result not judged: " + e.getMessage());
        }
        c.check(c.scenario().flags().isEmpty() && c.server().since().stream().noneMatch(l -> l.contains("failed RotationPlace")),
                "Grim lines with No-Look Lever Click OFF: " + c.server().since().stream().filter(l -> l.contains("Grim »")).toList());
        c.check(LogTap.since(m0).stream().noneMatch(l -> l.contains("no packet can carry a look")), "a no-look click went out with the switch OFF");
    }

    /** ee3r23a-1123: set by {@link #swapWalk}; run() then clears every pick before the walk (no fake-Breaker race with the
     *  server's /setblock) and records each client tick's keys, look, sprint, held slot and position here. */
    private static volatile List<String> walkTrace;
    private static volatile boolean walkRec;
    private static boolean walkHooked;

    /** One client tick of the walk (END_CLIENT_TICK, so no tick is skipped or read twice as a test-thread poll can). */
    private static void walkTick(net.minecraft.client.Minecraft mc) {
        List<String> tr = walkTrace;
        var p = mc.player;
        if (!walkRec || tr == null || p == null) {
            return;
        }
        var k = p.input.keyPresses;
        tr.add(String.format(Locale.ROOT, "%s|%s%s%s%s%s%s%s yaw %.3f pitch %.3f|slot %d|%.4f,%.4f,%.4f",
                Math.hypot(p.getX() - 60.5, p.getZ() - 139) > 0.01 ? "move" : "still", k.forward() ? "W" : "", k.left() ? "A" : "",
                k.backward() ? "S" : "", k.right() ? "D" : "", k.jump() ? "J" : "", k.shift() ? "N" : "", p.isSprinting() ? "R" : "",
                p.getYRot(), p.getXRot(), p.getInventory().getSelectedSlot(), p.getX(), p.getY(), p.getZ()));
    }

    /** ee3r23a-1123: his hotbar swap must not change AP3's walk (auras and swaps never touch keys, yaw or sprint). The same
     *  route with the picks cleared, once without his swap and once with it; keys, look, sprint and position must match tick for
     *  tick from the first moving tick, and the swap must have changed the held slot during the compared ticks. */
    private static void swapWalk(Session c) throws Exception {
        c.onClient(mc -> {
            if (!walkHooked) {
                walkHooked = true;
                net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(Ee3RouteGrimCases::walkTick);
            }
            return null;
        });
        List<List<String>> runs = new ArrayList<>();
        for (SameTick.Act act : new SameTick.Act[]{null, SameTick.Act.SWAP}) {
            walkTrace = java.util.Collections.synchronizedList(new ArrayList<>());
            try {
                run(c, act, true);
            } catch (AssertionError e) {
                c.note((act == null ? "plain" : "swap") + " run, route result not judged here: " + e.getMessage());
            } finally {
                // ee3race-1123: the second run() moves the server mark, so the case's Grim judgment saw only the swap run;
                // the plain run's flags (11-23: a Simulation streak hid here twice) are judged here.
                walkRec = false;
                runs.add(new ArrayList<>(walkTrace));
                walkTrace = null;
            }
            if (act == null) {
                List<String> flags = c.scenario().flags().stream().filter(l -> l.contains("failed")).toList();
                c.check(flags.isEmpty(), "Grim flagged the plain run " + flags.size() + " time(s): " + flags);
            }
        }
        List<List<String>> moving = new ArrayList<>();
        for (List<String> r : runs) {
            int i = 0;
            while (i < r.size() && !r.get(i).startsWith("still")) {
                i++;
            }
            while (i < r.size() && r.get(i).startsWith("still")) {
                i++;
            }
            moving.add(r.subList(i, r.size()));
        }
        int n = Math.min(40, Math.min(moving.get(0).size(), moving.get(1).size()));
        int swaps = 0;
        int diff = -1;
        for (int i = 0; i < n; i++) {
            String[] a = moving.get(0).get(i).split("\\|");
            String[] b = moving.get(1).get(i).split("\\|");
            swaps += i > 0 && !b[2].equals(moving.get(1).get(i - 1).split("\\|")[2]) ? 1 : 0;
            if (diff < 0 && !(a[1] + a[3]).equals(b[1] + b[3])) {
                diff = i;
            }
        }
        for (int i = 0; i < n; i++) {
            System.out.println("[ee3route] walk t" + i + " plain " + moving.get(0).get(i) + " | swap " + moving.get(1).get(i));
        }
        c.note("compared " + n + " moving ticks, held slot changed by his swap " + swaps + " time(s), first difference at "
                + (diff < 0 ? "none" : "t" + diff));
        c.check(n >= 30, "only " + n + " moving ticks to compare");
        c.check(swaps >= 10, "his swap changed the held slot only " + swaps + " time(s) in the compared ticks - nothing tested");
        c.check(diff < 0, "his swap changed AP3's walk at moving tick " + diff + ": plain " + (diff < 0 ? "" : moving.get(0).get(diff))
                + " vs swap " + (diff < 0 ? "" : moving.get(1).get(diff)));
    }

    /** ee3race-1123: picks the fake Breaker has set to air on the server and the client has not yet RECEIVED as air. */
    private static boolean grimDebug;
    /** simstreak-1123: the wait spot beside HEE2, out of its box and of every fill. It was 62.5,132,139, in the column the
     *  fills clear (x 62, y 127-139), so he fell after every tp and, at a session's first run (empty world), drew Simulation
     *  .284 and, once the fill sync let the client tick, .357 while the fills changed the blocks he fell past. Now he stands
     *  on a barrier above the highest fill (y 142). */
    private static void park(Session c) {
        c.server().command("setblock 62 143 139 minecraft:barrier");
        TerminalAuraCases.tp(c, 62.5, 144, 139, 72f);
    }

    private static final java.util.Set<BlockPos> airWait =java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Network thread: crosses off each pick as its air update arrives (applied on the client's next tick, before its move). */
    private static final Consumer<Packet<?>> airIn = p -> {
        if (p instanceof net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket u && u.getBlockState().isAir()) {
            airWait.remove(u.getPos());
        } else if (p instanceof net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket u) {
            u.runUpdates((q, b) -> {
                if (b.isAir()) {
                    airWait.remove(q.immutable());
                }
            });
        }
    };

    static void run(Session c, SameTick.Act act, boolean noLook) throws Exception {
        List<AutoCloseable> undo = new ArrayList<>();
        Path route = routeDir().resolve(ROUTE + ".json");
        Path fills = DIR.resolve("fills-env.txt");
        c.check(Files.isRegularFile(route) && Files.isRegularFile(fills), "missing " + route + " or " + fills);
        Ticks ticks = new Ticks();
        SameTick st = null;
        try {
            c.server().command("forceload add -2 100 63 146");
            c.server().sync(30);
            // Off the map first: the fills below must not land on him where the last case left him (Simulation flags
            // charged to this case, experimental Grim, x4/x5).
            park(c);
            c.ctx().waitTicks(5);
            for (String l : Files.readAllLines(fills)) {
                if (!l.isBlank()) {
                    c.server().command(l);
                }
            }
            c.server().sync(120, () -> c.ctx().waitTick()); // the client keeps ticking (simstreak-1123: NegativeTimer)
            // ee3route-align-1117: the picks stay in the world, as in a real run; the run digs them on the way (below).
            List<BlockPos> live = new ArrayList<>();
            for (String l : Files.readAllLines(routeDir().resolve(ROUTE + ".picks"))) {
                l = l.strip();
                if (!l.isEmpty() && !l.startsWith("#")) {
                    String[] x = l.split(",");
                    live.add(new BlockPos(Integer.parseInt(x[0].strip()), Integer.parseInt(x[1].strip()), Integer.parseInt(x[2].strip())));
                }
            }
            for (BlockPos p : new BlockPos[]{LOW, HIGH}) {
                c.server().command(String.format(Locale.ROOT, "summon minecraft:armor_stand %.1f %d %.1f {NoGravity:1b,Invisible:1b,"
                        + "CustomNameVisible:1b,CustomName:\"Not Activated\",Tags:[\"ee3route\"]}", p.getX() + 0.5, p.getY() + 1, p.getZ() + 0.5));
            }
            c.server().command("item replace entity @p hotbar.6 with minecraft:tnt[minecraft:custom_data={id:\"SUPERBOOM_TNT\"}] 64");
            c.server().command("attribute @p minecraft:movement_speed base set 0.6");
            c.server().command("effect give @p minecraft:fire_resistance 600 0 true"); // no lava knockback if he misses
            c.server().sync(30);
            park(c); // beside HEE2: AP3 must not start before the run (a tp under its walk is a Simulation flag)
            c.ctx().waitTicks(10);
            if (act != null || walkTrace != null) {
                SameTickCases.sticks(c); // swapWalk: both runs hold the same stick
            }
            c.hx().sidebar("SKYBLOCK", "The Catac§combs §7(F7)");
            c.waitUntil("DungeonState F7", mc -> Boolean.TRUE.equals(Mod.staticCall("secrets.DungeonState", "isF7OrM7")), 100);
            LightsDeviceCases.newRun(c);
            LightsDeviceCases.openS2(c);
            undo.add(c.onClient(mc -> Mod.with(CU, "AuraBossLevers", true)));
            undo.add(c.onClient(mc -> Mod.with(LA, "SectionLevers", true)));
            undo.add(c.onClient(mc -> Mod.with(LA, "SectionLeversEarly", true)));
            undo.add(c.onClient(mc -> Mod.with(LA, "Range", 4.5)));
            undo.add(c.onClient(mc -> Mod.with(LA, "NoLookClick", noLook)));
            undo.add(c.onClient(mc -> Mod.with(LA, "CooldownMs", Integer.parseInt(System.getenv().getOrDefault("KM_LEVER_CD", "50")))));
            undo.add(c.onClient(mc -> Mod.with("ap3.Ap3Config", "Enabled", true)));
            // ee3route-align-1117: 45 Degree Strafe ON as in his 26.3 (routes are planned with it); KM_EE3_STRAFE45=0 turns it off.
            undo.add(c.onClient(mc -> Mod.with("ap3.Ap3Config", "Strafe45", !"0".equals(System.getenv("KM_EE3_STRAFE45")))));
            c.onClient(mc -> {
                try {
                    Mod.staticCall("ap3.Ap3Executor", "stop", "test lane");
                    Mod.staticCall("ap3.Ap3Feature", "setForceDungeon", true);
                    Path dir = (Path) Mod.staticCall("ap3.Ap3Store", "directory");
                    Files.createDirectories(dir);
                    Files.copy(route, dir.resolve("ee3-grim.json"), StandardCopyOption.REPLACE_EXISTING);
                    Mod.staticCall("ap3.Ap3Store", "select", "ee3-grim.json");
                    Mod.staticCall("ap3.Ap3Store", "reload");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return null;
            });
            c.ctx().waitTicks(5);
            c.check(c.onClient(mc -> !powered(mc, LOW) && !powered(mc, HIGH)), "an S2 lever was already on before the run");
            // Judge Grim from the run on: the session join and the setup fills/tps (AP3 and Lever Aura idle) drew a lone
            // Simulation .284 in x6's first case. Print them, never hide them silently.
            List<String> setupFlags = c.scenario().flags();
            if (!setupFlags.isEmpty()) {
                System.out.println("[ee3route] setup flags before the run (not judged): " + setupFlags);
            }
            // Owner exception (killer560, 2026-11-18, "whatever you think is best"): the same for the low lever (27,124,127).
            // One RotationPlace per no-look lever click (counted below against the log), nothing else.
            java.util.Set<String> acceptedPlace = new java.util.LinkedHashSet<>();
            // the "grim reload" lines raiseCancelVl causes are Grim's own chat, not a flag
            Scenario.acceptedFlag = l -> l.contains("Reloading config") || l.contains("Config has been reloaded") || l.contains("grim-datastore")
                    || l.contains("v2-bootstrap") || noLook && l.contains("failed RotationPlace") && (acceptedPlace.add(l) | acceptedPlace.contains(l));
            // GrimAC cancels a placement once RotationPlace violations reach punishments.yml cancelvl 5, and they add up over a
            // session (11-18: cases 3 and 4 ran at x5+ and every lever click was canceled, levers false/false). The accepted
            // flags must not decide whether the lever goes, so the cancel level is lifted for this case and restored after.
            undo.add(raiseCancelVl(c));
            if (System.getenv("KM_GRIM_DEBUG") != null && !grimDebug) {
                grimDebug = true; // a toggle: once per session, Grim's P/A/O line per movement goes to the server log
                c.server().command("grim consoledebug Player0");
            }
            // simstreak-1123: the server holds Grim's trig guess at vanilla (MixinGrimTrig); KM_GRIM_TRIG=auto lets it switch, to
            // show the Simulation streak it causes at this case's 0.6 speed.
            c.server().command("testkit grimtrig " + ("auto".equals(System.getenv("KM_GRIM_TRIG")) ? "auto" : "pin"));
            c.server().mark();
            long mark = LogTap.mark();
            c.onClient(mc -> {
                PacketTrace.tap = ticks;
                PacketTrace.inTap = airIn;
                return null;
            });
            if (act != null) {
                // ee3swap-1123 (killer560 11-23: Breaker Aura Auto Swap waits for a free tick): his scroll goes in two ticks of
                // three, a free tick after each pair, as a real player's scrolling leaves ticks, and still lands in a lever
                // click's tick on some ticks. Every tick would leave the aura no tick to dig in (decided behavior, covered by
                // 1123-fhb-breaker-autoswap-swap-every).
                // The scroll is never freed within 7 blocks of a lever (the aura's lever click goes in its tick, which is the
                // point of the case); the Breaker's walk stretches keep their free ticks.
                st = SameTickCases.start(c, act, LEVER_CLICK, act == SameTick.Act.SWAP ? 3 : 0, () -> {
                    var p = net.minecraft.client.Minecraft.getInstance().player; // on the client thread
                    return p != null && (p.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(LOW)) < 49
                            || p.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(HIGH)) < 49);
                });
            }
            int holds0 = ((Number) Mod.staticCall("ap3.Ap3Executor", "handHolds")).intValue();
            int turns0 = act == SameTick.Act.DIG ? ((Number) Mod.staticCall("util.HandDigAim", "turns")).intValue() : 0;
            WireCapture.describe = Ee3RouteGrimCases::describe;
            WireCapture.start();
            if (walkTrace != null) {
                for (BlockPos q : live) {
                    c.server().command("setblock " + q.getX() + " " + q.getY() + " " + q.getZ() + " minecraft:air");
                    c.onClient(mc -> mc.level.setBlock(q, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 11));
                }
                c.server().sync(30);
            }
            // Leave and re-enter HEE2's box: a tp lands him in it, as a leap would.
            park(c);
            c.ctx().waitTicks(5);
            // The start of a boss run: standing at the HEE2 node, yaw 117 pitch 46.3 (the route finder's sim start).
            walkRec = walkTrace != null;
            c.hx().call("dungeon.tp", "x", 60.5, "y", 132.0, "z", 139.0, "yaw", 117f, "pitch", 46.3f);
            int t0 = -1;
            int charges = 20;
            int recharge = 0;
            java.util.Set<BlockPos> broken = new java.util.HashSet<>();
            if (walkTrace != null) {
                broken.addAll(live); // cleared before the tp
            }
            int fakeDug = 0;
            int lateAir = 0;
            airWait.clear();
            int arrive = -1;
            int boomAt = -1;
            double minY = 999;
            double[] fell = null;
            long arriveNs = Long.MAX_VALUE;
            StringBuilder path = new StringBuilder();
            StringBuilder tickMs = new StringBuilder(); // simstreak-1123: wall ms per lockstep tick (server stalls)
            long tPrev = System.nanoTime();
            for (int t = 0; t < 400 && arrive < 0; t++) {
                c.ctx().waitTick();
                double[] s = c.onClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                        mc.player.onGround() ? 1 : 0, mc.player.input.keyPresses.jump() ? 1 : 0});
                minY = Math.min(minY, s[1]);
                // The Breaker's rule as the route finder models it: a pick whose box is within 4.5 of the eye goes (one per
                // charge, 20 charges, one back every 10 ticks), set to air on the server; the client learns it from the server's
                // block update. ee3r23a-1123: also writing air into the client world at once put the client a tick or two ahead
                // of GrimAC's world (no dig packet tells Grim), so a walk through a fresh hole drew Simulation + GroundSpoof and a
                // setback (r23a at t5-6 with his swap, 08:21:32 on 11-23); 1123-boss-ee3route-swap-walk proved AP3's walk unchanged.
                // ee3race-1123: the setblock used to reach the client one to three ticks after the tick it was sent in, so a
                // zero-margin pick was still solid for his next move and he stopped at its face (x 56.30 at t6, then the lava
                // at t32; r23a and m26a, nodelay-1123). The real Breaker's block is gone the tick its dig goes out. Now, as the
                // finder's Exec.breaker: recharge, then every pick in reach nearest first, at the END of tick t from the
                // post-move position; and the lockstep client is held (the test thread waits) until the server's air update
                // for each has REACHED the client, so it is applied, with GrimAC's transaction around it, before tick t+1's
                // move. Client and Grim stay in step (no client-ahead air, the ee3r23a-1123 lesson).
                if (charges < 20 && ++recharge >= 10) {
                    recharge = 0;
                    charges++;
                }
                List<BlockPos> inReach = new ArrayList<>();
                for (BlockPos q : live) {
                    if (!broken.contains(q) && boxDistSq(s[0], s[1] + 1.62, s[2], q) <= 4.5 * 4.5) {
                        inReach.add(q);
                    }
                }
                inReach.sort(java.util.Comparator.comparingDouble(q -> boxDistSq(s[0], s[1] + 1.62, s[2], q)));
                List<BlockPos> dug = new ArrayList<>();
                for (BlockPos q : inReach) {
                    if (charges > 0) {
                        charges--;
                        broken.add(q);
                        dug.add(q);
                    }
                }
                if (!dug.isEmpty()) {
                    airWait.addAll(c.onClient(mc -> dug.stream().filter(q -> !mc.level.getBlockState(q).isAir()).toList()));
                    for (BlockPos q : dug) {
                        c.server().command("setblock " + q.getX() + " " + q.getY() + " " + q.getZ() + " minecraft:air");
                    }
                    fakeDug += dug.size();
                    long deadline = System.nanoTime() + 3_000_000_000L;
                    while (!airWait.isEmpty() && System.nanoTime() < deadline) {
                        java.util.concurrent.locks.LockSupport.parkNanos(500_000);
                    }
                    if (!airWait.isEmpty()) {
                        lateAir += airWait.size();
                        airWait.clear();
                    }
                }
                long tNow = System.nanoTime();
                tickMs.append(' ').append((tNow - tPrev) / 1_000_000);
                tPrev = tNow;
                path.append(String.format(Locale.ROOT, " %d:%.2f,%.2f,%.2f%s%s/c%d", t, s[0], s[1], s[2], s[3] > 0 ? "g" : "", s[4] > 0 ? "J" : "", charges));
                if (t0 < 0 && Math.hypot(s[0] - 60.5, s[2] - 139) > 0.2) {
                    t0 = t;
                }
                if (boomAt < 0 && LogTap.since(mark).stream().anyMatch(l -> l.contains("[AP3] boom #") && l.contains("use sent"))) {
                    boomAt = t;
                    // Hypixel blows gate 2 a tick or two after the Superboom lands; nothing breaks it here on its own.
                    c.server().command("fill 16 115 127 19 136 137 minecraft:air replace minecraft:stone"); // the capture's arch is stone here
                }
                if (Math.abs(s[0] - 2.5) <= 1 && Math.abs(s[2] - 105.5) <= 1 && Math.abs(s[1] - 109) <= 1.5 && s[3] > 0) {
                    arrive = t;
                    arriveNs = System.nanoTime();
                }
                if (s[1] < 107.5) {
                    // Off the route into the lava (y 106): out at once, or the burning fall draws Simulation flags that the
                    // next case is blamed for. Where this route ends in the hx world is the route's question, not this case's.
                    c.note(String.format(Locale.ROOT, "fell into the lava at t%d (rel %d) at %.2f %.2f %.2f", t, t - t0, s[0], s[1], s[2]));
                    fell = s;
                    park(c);
                    break;
                }
            }
            System.out.println("[ee3route] path " + (act == null ? "none" : act) + ":" + path);
            System.out.println("[ee3route] tick ms:" + tickMs);
            c.note("fake Breaker: " + fakeDug + " pick(s) dug, " + lateAir + " air update(s) not at the client before the next tick");
            List<String> trig = c.server().since().stream().filter(l -> l.contains("[testkit-trig]")).toList();
            if (!trig.isEmpty()) {
                c.note("anticheat trig guess switched in this run: " + trig);
            }
            // A green run must have dug through the route's picks with every air in place in time (the case's own fixture).
            c.check(walkTrace != null || fakeDug > 0, "the fake Breaker never dug a pick");
            c.check(lateAir == 0, "fake Breaker: " + lateAir + " air update(s) reached the client late");
            c.ctx().waitTicks(20);
            // The wire around every lever click (packet order against the swap / dig / move of its tick), to read a refused click.
            java.util.List<WireCapture.Sent> wireAll = WireCapture.snapshot();
            for (int i = 0; i < wireAll.size(); i++) {
                if (wireAll.get(i).text().contains("UseItemOn")) {
                    StringBuilder sb = new StringBuilder();
                    for (int k = Math.max(0, i - 6); k < Math.min(wireAll.size(), i + 5); k++) {
                        sb.append(k == i ? " >>" : " ").append(wireAll.get(k).text(), 0, Math.min(60, wireAll.get(k).text().length()));
                    }
                    System.out.println("[ee3route] lever wire" + sb);
                }
            }
            boolean[] lev = c.onClient(mc -> new boolean[]{powered(mc, LOW), powered(mc, HIGH)});
            String wire = ticks.report();
            c.note(String.format(Locale.ROOT, "route %s: left HEE2 t%d, at EE3 t%d (%d ticks), levers low/high %s/%s, boom t%d,"
                            + " lowest y %.2f; wire: %s", ROUTE, t0, arrive, arrive < 0 || t0 < 0 ? -1 : arrive - t0, lev[0], lev[1],
                    boomAt < 0 || t0 < 0 ? -1 : boomAt - t0, minY, wire));
            // grimreach-1110: Lever Aura clicks only a lever whose cube is within Grim's 4.5 (TurnFirst.grimReach); the low S2
            // lever is never that close on this route (a closer route is the ap3brute job), and while his own dig holds the
            // body (DIG) Lever Aura yields, so no lever may go out. Use/swap: the high lever must be flicked, in a shared tick.
            boolean dig = act == SameTick.Act.DIG;
            if (st != null && (!dig || ticks.lever > 0)) {
                SameTick s2 = st;
                st = null;
                SameTickCases.judge(c, s2, "ee3 route levers");
            }
            long noLookClicks = LogTap.since(mark).stream().filter(l -> l.contains("no packet can carry a look to lever")).count();
            c.check(acceptedPlace.size() <= noLookClicks, "RotationPlace flags " + acceptedPlace.size() + " exceed the no-look lever clicks " + noLookClicks + ": " + acceptedPlace);
            c.check(dig || lev[1], "the high S2 lever was not powered (low/high " + lev[0] + "/" + lev[1] + ")");
            c.check(dig || ticks.lever >= 1, "no lever click on the wire: " + wire);
            c.check(ticks.twoLevers == 0, "two lever clicks in one server tick: " + wire);
            // ap3holddig-1110: his right click or dig in every tick must never stop AP3. grimreach-1110: the route waits at the
            // low S2 lever while it is out of Grim's 4.5 (or his dig keeps Lever Aura off), so the BOOM and EE3 are only
            // asked once that lever went; then his act is held one tick and goes. A swap every tick takes the TNT away.
            // nodelay-1123: after a fall this case's own rescue tp reads to AP3 as a leap ("Stopped: you leapt"); that stop is
            // the harness's, not the route's (the fall itself is judged above/below).
            boolean rescued = fell != null;
            // ee3race-1123 (m26a-samedig-1123, killer560 2026-11-23: "I will never be holding a click or scrolling my hotbar or
            // anything while auto routes is working."): AP3 giving up a BOOM because his own held dig keeps its look off the
            // block ("boom #N couldn't go out") is a route outcome under his input, a note like the arrival; a Grim flag still fails.
            List<String> boomGaveUp = LogTap.since(mark).stream().filter(l -> act == SameTick.Act.DIG && l.contains("[AP3] Stopped: boom #")).toList();
            if (!boomGaveUp.isEmpty()) {
                c.note("route outcome under his DIG (not required since 11-23): " + boomGaveUp);
            }
            List<String> stops = LogTap.since(mark).stream().filter(l -> l.contains("[AP3] Stopped:") && !l.contains("test over")
                    && !(rescued && l.contains("you leapt")) && !boomGaveUp.contains(l)).toList();
            c.check(stops.isEmpty(), "AP3 stopped on the route: " + stops);
            // nodelay-1123 (killer560 2026-11-23: "I will never be holding a click or scrolling my hotbar or anything while auto
            // routes is working."): the route need not FINISH under his own use / swap / dig, so in the same-* cases the
            // BOOM, the arrival and the lava are notes; GrimAC must still stay silent (Session.test) and every wire check stands.
            // The plain grim and nolook-off cases (act == null) still require the route to arrive.
            java.util.function.BiConsumer<Boolean, String> need = (ok, msg) -> {
                if (act == null) {
                    c.check(ok, msg);
                } else if (!ok) {
                    c.note("route outcome under his " + act + " (not required since 11-23): " + msg);
                }
            };
            need.accept(fell == null, "fell into the lava (path above)");
            if (!lev[0]) {
                c.note("route waits at the low S2 lever; BOOM tap at t" + boomAt);
            } else {
                need.accept(boomAt >= 0, "the BOOM node never sent its tap");
                need.accept(arrive >= 0, "the route never reached EE3 (lowest y " + minY + ")");
                if (act != null && act != SameTick.Act.SWAP) {
                    int holds = ((Number) Mod.staticCall("ap3.Ap3Executor", "handHolds")).intValue() - holds0;
                    c.check(holds > 0, "AP3 never held his hand for its act (" + act + ")");
                    c.note("hand holds: " + holds);
                }
            }
            // leverkey-1110: Lever Aura's pre-turn re-picked the keys as A alone for a tick (W and sprint dropped mid-route).
            // On the wire: no player_input of A alone, and the turn itself must have run (lines "turning body" / "keys re-picked").
            int aAlone = 0;
            for (WireCapture.Sent w : WireCapture.snapshot()) {
                // Up to the arrival: the align at EE3 afterwards may press A alone, it is not the route's walk.
                aAlone += w.nanos() < arriveNs && w.phase().equals("play") && w.text().startsWith("input A") && !w.text().startsWith("input AS")
                        && !w.text().startsWith("input AD") ? 1 : 0;
            }
            List<String> logs = LogTap.since(mark);
            long turns = logs.stream().filter(l -> l.contains("[LeverAura] turning body")).count();
            // A lever turn is a re-pick (route yaw A -> aura yaw B) that the hand-back (B -> A) follows; its keys must hold W.
            // The hand-back's own keys are the route's, and another feature's one-way turn is not Lever Aura's.
            java.util.regex.Pattern rp = java.util.regex.Pattern.compile("turned from (\\S+) to (\\S+) before the move.*yaw: (\\w*)");
            List<String[]> reps = new ArrayList<>();
            for (String l : logs) {
                java.util.regex.Matcher m = rp.matcher(l);
                if (m.find()) {
                    reps.add(new String[]{m.group(1), m.group(2), m.group(3), l.contains("(in the air)") ? "air" : ""});
                }
            }
            long noW = 0;
            long pairs = 0;
            for (int i = 0; i + 1 < reps.size(); i++) {
                if (reps.get(i)[0].equals(reps.get(i + 1)[1]) && reps.get(i)[1].equals(reps.get(i + 1)[0])) {
                    pairs++;
                    // leverjump-1111: in the air a W-less re-pick is allowed (the S2 high lever goes right after node 23's jump).
                    noW += reps.get(i)[2].contains("W") || !reps.get(i)[3].isEmpty() ? 0 : 1;
                }
            }
            c.note("lever pre-turns " + turns + ", lever re-pick pairs " + pairs + ", without W " + noW + ", A-alone inputs on the wire " + aAlone);
            // ap3auralook-1116 (killer560 2026-11-16, "My auras can never affect my movement"): Lever Aura never turns the
            // body while AP3 runs (the pre-turn is gone; it clicks pitch-only or with no look). This used to require a turn.
            c.check(turns == 0, "Lever Aura turned the body under the AP3 walk " + turns + " time(s) - an aura must never move him");
            // A route's own plan may press A alone (r23a does, 4 stretches: the finder's xbeam-plan keys "A"); the route folder's
            // fastest.aalone then holds how many such inputs its plain walk puts on the wire, and only more than that is a drop.
            Path aa = routeDir().resolve(ROUTE + ".aalone");
            int plannedA = Files.isRegularFile(aa) ? Integer.parseInt(Files.readString(aa).strip()) : 0;
            c.check(noW == 0 && aAlone <= plannedA, "the lever turn dropped W/sprint: re-picks without W " + noW + ", A-alone inputs " + aAlone
                    + " (the route's own: " + plannedA + ")");
            c.check(ticks.leverAndDig == 0, "a lever click and a dig in one server tick: " + wire);
            if (act == SameTick.Act.DIG) {
                // ap3digrot-1109: his dig under AP3's held camera turns the body onto his block, and the movement packet
                // carrying that look follows the dig in the same tick (GrimAC RotationBreak lends a dig that packet's look).
                WireCapture.stop();
                int turned = ((Number) Mod.staticCall("util.HandDigAim", "turns")).intValue() - turns0;
                int[] w = digTicks();
                c.note("hand-dig turns " + turned + "; dig ticks " + w[0] + ", with a turned move after the dig " + w[1]
                        + ", with a move before it " + w[2]);
                c.check(turned > 0, "his digs under the AP3 walk were never turned for (HandDigAim did not act)");
                c.check(w[0] > 0 && w[2] == 0, "a dig went after its tick's movement packet: " + w[2] + " of " + w[0]);
            }
        } finally {
            if (st != null) {
                SameTick s2 = st;
                c.onClient(mc -> {
                    s2.close();
                    return null;
                });
            }
            WireCapture.stop();
            WireCapture.describe = null;
            c.onClient(mc -> {
                if (PacketTrace.tap == ticks) {
                    PacketTrace.tap = ticks.prev;
                }
                if (PacketTrace.inTap == airIn) {
                    PacketTrace.inTap = null;
                }
                Mod.staticCall("ap3.Ap3Executor", "stop", "test over");
                return null;
            });
            for (int i = undo.size() - 1; i >= 0; i--) {
                undo.get(i).close();
            }
            c.server().command("kill @e[tag=ee3route]");
            c.server().command("attribute @p minecraft:movement_speed base set 0.1");
            c.server().command("effect clear @p minecraft:fire_resistance");
            c.server().command("forceload remove -2 100 63 146");
            c.hx().sidebarClear();
        }
    }
}
