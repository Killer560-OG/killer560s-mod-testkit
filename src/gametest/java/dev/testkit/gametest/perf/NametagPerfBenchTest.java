package dev.testkit.gametest.perf;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.ModUnderTest;
import dev.testkit.gametest.TestEnemy;
import dev.testkit.gametest.TestMap;
import dev.testkit.gametest.hx.HxKit;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.FrameClock;
import dev.testkit.harness.NameProbe;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.CameraType;
import net.minecraft.world.entity.player.Player;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 975-perf-nametag (killer560, 2026-10-13: "look at the third person name and see if that is eating a bunch of fps").
 * Times the render thread's CPU per frame ({@link FrameClock}) with the mod's NAME features in four settings - off,
 * own custom name with a fade (and My Name in F5), the same plus Wave, each in first person and in F5 - first in a quiet
 * scene and then in a busy one: 50 named armour stands, six player bots whose supporter names are faded and waving, an
 * 80-row tab list held open and a chat line every tick (every other one naming the player). Cells are interleaved over
 * rounds, so a drift lands on every cell alike; read DELTAS against "off" in the same scene and camera.
 * <p>
 * One JFR per scene with Wave on in F5 ({@code nametag-<scene>-wave.jfr}, for {@code tools/fps-jfr.py}). Then a
 * screenshot of every cell with the animation clock frozen, the chat refilled with the same lines and the clouds off
 * ({@code -PnametagShots=<dir>} copies them there by name), so two jars' frames can be compared pixel for pixel.
 * Only when named ({@code -Pscenario=975}); it measures, it does not test, beyond the scene existing and every cell
 * having frames.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class NametagPerfBenchTest implements FabricClientGameTest {

    static final String NAME = "975-perf-nametag";

    private static final String NC = "namechanger.NameChangerConfig";
    private static final String NA = "namechanger.NameAnimation";
    private static final String SC = "supporters.SupportersConfig";
    private static final String SF = "supporters.SupportersFeature";
    private static final String OWN = "Gradientname";
    private static final int FROM = 0xFF00FF00;
    private static final int TO = 0xFF0000FF;
    private static final int X = -1460;
    private static final int Z = -1460;
    private static final String[] CFGS = {"off", "fade", "wave"};
    private static final CameraType[] CAMS = {CameraType.FIRST_PERSON, CameraType.THIRD_PERSON_BACK};
    private static final String SHOTS = System.getProperty("testkit.nametagShots", "");

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank()) {
            return; // only when named
        }
        Session.run(ctx, NAME,
                (server, s) -> TestMap.on(server)
                        .platform(X, 150, Z, 24)
                        .catchFloor(140)
                        .survival()
                        .clearInventory()
                        .spawn(X + 0.5, Z + 0.5, 0f)
                        .build(),
                s -> {
                    ModUnderTest.require("killer560smod");
                    s.skipDetectorProof();
                    s.test(NAME + "-bench", NametagPerfBenchTest::bench);
                });
    }

    private static Object mode(String name) {
        for (Object o : Mod.cls(NA + "$Mode").getEnumConstants()) {
            if (((Enum<?>) o).name().equals(name)) {
                return o;
            }
        }
        throw new AssertionError("no NameAnimation.Mode." + name);
    }

    /** Sets one of {@link #CFGS} on the client (Name Changer own name + fade + F5 tag + supporter names). */
    private static void apply(Session c, String cfg) {
        boolean on = !cfg.equals("off");
        Object anim = mode(cfg.equals("wave") ? "WAVE" : "OFF");
        c.onClient(mc -> {
            Object nc = Mod.cfg(NC);
            Mod.call(nc, "clearOwnCharColors");
            Mod.call(nc, "setRandomizeOthers", false);
            Mod.call(nc, "setOwnDisplayName", OWN);
            Mod.call(nc, "setOwnColor", FROM);
            Mod.call(nc, "setOwnColorFadeTo", TO);
            Mod.call(nc, "setOwnColorFadeEnabled", true);
            Mod.call(nc, "setOwnAnimation", anim);
            Mod.call(nc, "setOwnAnimationSpeed", 1.0);
            Mod.call(nc, "setOwnNameEnabled", on);
            Mod.call(nc, "setEnabled", on);
            Object sc = Mod.cfg(SC);
            Mod.call(sc, "setOwnNameInF5", on);
            Mod.call(sc, "setShowSupporterNames", on);
            return null;
        });
    }

    private static void camera(Session c, CameraType t) {
        c.onClient(mc -> {
            mc.options.setCameraType(t);
            mc.player.setYRot(0f);
            mc.player.setXRot(0f);
            mc.player.yRotO = 0f;
            mc.player.xRotO = 0f;
            return null;
        });
    }

    private static int chatSeq;

    private static void chatLine(Session c, String self) {
        int i = chatSeq++;
        String line = switch (i % 4) {
            case 0 -> "§7[§8" + (100 + i % 300) + "§7] §b[MVP§c+§b] " + self + "§f: selling hyperion cheap visit me " + i;
            case 1 -> "§9Party §8> §a[VIP] HubPlayer" + (i % 79) + "§f: ready? " + i;
            case 2 -> "§9Party §8> §6[MVP§c++§6] " + self + "§f: are you coming to f7 " + i;
            default -> "§7[§8" + (100 + i % 300) + "§7] §7HubPlayer" + (i % 79) + "§7: lf carry m7 " + i;
        };
        c.hx().chat(line);
    }

    private static long[][] measure(Session c, int frames, boolean busy, String self) {
        ClientGameTestContext ctx = c.ctx();
        ctx.runOnClient(mc -> {
            FrameClock.start(frames * 20);
            NameProbe.start();
        });
        for (int i = 0; i < frames; i++) {
            if (busy) {
                chatLine(c, self);
            }
            ctx.waitTicks(1);
        }
        return ctx.computeOnClient(mc -> {
            FrameClock.stop();
            NameProbe.stop();
            if (FrameClock.dropped() > 0) {
                System.out.println("[" + NAME + "] WARNING: " + FrameClock.dropped() + " frame(s) past capacity dropped");
            }
            return new long[][]{FrameClock.frameSamples(), FrameClock.tickSamples(), NameProbe.calls(), NameProbe.nanos()};
        });
    }

    private static void jfr(Session c, int frames, boolean busy, String self, Path file) {
        try {
            Files.createDirectories(file.getParent());
            jdk.jfr.Recording rec = new jdk.jfr.Recording(jdk.jfr.Configuration.getConfiguration("profile"));
            rec.enable("jdk.ExecutionSample").withPeriod(java.time.Duration.ofMillis(1));
            rec.start();
            for (int i = 0; i < frames; i++) {
                if (busy) {
                    chatLine(c, self);
                }
                c.ctx().waitTicks(1);
            }
            rec.stop();
            rec.dump(file);
            rec.close();
        } catch (Exception e) {
            System.out.println("[" + NAME + "] JFR failed: " + e);
        }
    }

    private static void bench(Session c) throws Exception {
        ClientGameTestContext ctx = c.ctx();
        int frames = Integer.getInteger("testkit.bench.frames", 200);
        int rounds = Integer.getInteger("testkit.bench.rounds", 3);
        Path out = Path.of(System.getProperty("testkit.reportDir", "build/testkit-report"));
        List<String> report = new ArrayList<>();
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        String self = c.onClient(mc -> mc.player.getGameProfile().name());
        Map<String, Object> before = c.onClient(mc -> {
            Map<String, Object> m = new LinkedHashMap<>();
            Object nc = Mod.cfg(NC);
            for (String g : new String[]{"isEnabled", "isOwnNameEnabled", "getOwnDisplayName", "getOwnColor",
                    "getOwnColorFadeTo", "isOwnColorFadeEnabled", "getOwnAnimation", "getOwnAnimationSpeed",
                    "isRandomizeOthers"}) {
                m.put("nc#" + g, Mod.call(nc, g));
            }
            Object sc = Mod.cfg(SC);
            m.put("sc#isOwnNameInF5", Mod.call(sc, "isOwnNameInF5"));
            m.put("sc#isShowSupporterNames", Mod.call(sc, "isShowSupporterNames"));
            mc.options.enableVsync().set(false);
            mc.options.framerateLimit().set(260);
            mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
            return m;
        });
        HxKit.sidebar(c, "template", "hub");
        c.server().command("time set 6000");
        c.server().command("weather clear");
        double overhead = c.onClient(mc -> {
            NameProbe.overheadNs();
            return NameProbe.overheadNs();
        });
        boolean sodium = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium");
        report.add(String.format(Locale.ROOT, "timer overhead %.1f ns/call; sodium %s; player %s", overhead, sodium, self));
        List<TestEnemy> bots = new ArrayList<>();
        Map<String, long[][][]> cells = new LinkedHashMap<>();
        try {
            for (String scene : new String[]{"quiet", "busy"}) {
                boolean busy = scene.equals("busy");
                if (busy) {
                    buildBusy(c, self, bots);
                }
                c.onClient(mc -> {
                    McCompat.clearChatAndToasts(mc);
                    mc.options.keyPlayerList.setDown(busy);
                    return null;
                });
                int seen = c.onClient(mc -> {
                    int n = 0;
                    for (var e : mc.level.entitiesForRendering()) {
                        n++;
                    }
                    return n;
                });
                report.add(scene + ": client renders " + seen + " entit(ies), tab entries "
                        + c.onClient(mc -> mc.getConnection().getListedOnlinePlayers().size()));
                if (busy) {
                    c.check(seen >= 55, "busy scene: the client sees only " + seen + " entities");
                }
                boolean shotsOnly = Boolean.getBoolean("testkit.nametagShotsOnly");
                // warm-up: every config once
                for (String cfg : shotsOnly ? new String[0] : CFGS) {
                    apply(c, cfg);
                    for (CameraType cam : CAMS) {
                        camera(c, cam);
                        measure(c, 60, busy, self);
                    }
                }
                for (int r = 0; r < (shotsOnly ? 0 : rounds); r++) {
                    for (String cfg : CFGS) {
                        apply(c, cfg);
                        for (CameraType cam : CAMS) {
                            camera(c, cam);
                            ctx.waitTicks(20);
                            long[][] m = measure(c, frames, busy, self);
                            String key = scene + "/" + cfg + "/" + (cam == CameraType.FIRST_PERSON ? "1st" : "F5");
                            long[][][] acc = cells.computeIfAbsent(key, k -> new long[4][rounds][]);
                            acc[0][r] = m[0];
                            acc[1][r] = m[1];
                            acc[2][r] = m[2];
                            acc[3][r] = m[3];
                        }
                    }
                }
                FrameClock.Stats[] base = new FrameClock.Stats[CAMS.length];
                for (String cfg : shotsOnly ? new String[0] : CFGS) {
                    for (int k = 0; k < CAMS.length; k++) {
                        String key = scene + "/" + cfg + "/" + (k == 0 ? "1st" : "F5");
                        long[][][] acc = cells.get(key);
                        FrameClock.Stats f = stats(acc[0]);
                        FrameClock.Stats t = stats(acc[1]);
                        if (cfg.equals("off")) {
                            base[k] = f;
                        }
                        String line = String.format(Locale.ROOT, "%-16s frame %s | tick %s | vs off: mean %+.1f us p95 %+.1f us",
                                key, f.line(), t.line(), (f.mean() - base[k].mean()) * 1000, (f.p95() - base[k].p95()) * 1000);
                        report.add(line);
                        System.out.println("[" + NAME + "] " + line);
                        // the name hooks' own time, per frame (timer overhead subtracted)
                        long calls = 0;
                        long ns = 0;
                        StringBuilder per = new StringBuilder();
                        for (int h = 0; h < NameProbe.HOOKS.length; h++) {
                            long hc = 0;
                            long hn = 0;
                            for (int r = 0; r < rounds; r++) {
                                hc += acc[2][r][h];
                                hn += acc[3][r][h];
                            }
                            calls += hc;
                            ns += hn;
                            double us = (hn - hc * overhead) / 1000.0 / f.n();
                            if (hc > 0) {
                                per.append(String.format(Locale.ROOT, " %s %.1f/f %.1fus", NameProbe.HOOKS[h],
                                        hc / (double) f.n(), us));
                            }
                        }
                        double hookUs = (ns - calls * overhead) / 1000.0 / f.n();
                        String hl = String.format(Locale.ROOT, "%-16s NAME HOOKS %.1f us/frame (%.1f%% of the %.3f ms mean frame),"
                                + " %.1f calls/frame:%s", key, hookUs, 100.0 * hookUs / (f.mean() * 1000), f.mean(),
                                calls / (double) f.n(), per);
                        report.add(hl);
                        System.out.println("[" + NAME + "] " + hl);
                        c.check(f.n() >= frames * rounds / 2, key + ": only " + f.n() + " frames recorded");
                    }
                }
                if (!shotsOnly && !Boolean.getBoolean("testkit.bench.noJfr")) {
                    apply(c, "wave");
                    camera(c, CameraType.THIRD_PERSON_BACK);
                    ctx.waitTicks(20);
                    jfr(c, frames, busy, self, out.resolve("nametag-" + scene + "-wave.jfr"));
                    apply(c, "off");
                    ctx.waitTicks(20);
                    jfr(c, frames, busy, self, out.resolve("nametag-" + scene + "-off.jfr"));
                }
                if (sodium) {
                    report.add(scene + ": no screenshots (Sodium refuses the gametest screenshot: \"Global terrain"
                            + " uniforms have not been updated\")");
                } else {
                    shots(c, scene, busy, self, report);
                }
            }
        } finally {
            try {
                c.onClient(mc -> {
                    Mod.staticCall(NA, "freezeClockForTest", -1L);
                    mc.options.keyPlayerList.setDown(false);
                    mc.options.setCameraType(CameraType.FIRST_PERSON);
                    Object nc = Mod.cfg(NC);
                    Object sc = Mod.cfg(SC);
                    before.forEach((k, v) -> {
                        String[] p = k.split("#");
                        String setter = p[1].replaceFirst("^(is|get)", "set");
                        Mod.call(p[0].equals("nc") ? nc : sc, setter, v);
                    });
                    Mod.staticCall(SF, "apply", 0, List.of(), false);
                    return null;
                });
            } catch (Throwable ignored) {
                // restoring must not mask the real failure
            }
            for (TestEnemy b : bots) {
                try {
                    b.remove();
                } catch (Throwable ignored) {
                    // the session tears the server down anyway
                }
            }
            try {
                c.hx().removeStands();
            } catch (Throwable ignored) {
                // same
            }
            try {
                Files.createDirectories(out);
                Files.writeString(out.resolve("nametag-bench.txt"), String.join(System.lineSeparator(), report)
                        + System.lineSeparator());
            } catch (Exception e) {
                System.out.println("[" + NAME + "] could not write nametag-bench.txt: " + e);
            }
            for (String line : report) {
                System.out.println("[" + NAME + "] " + line);
            }
        }
    }

    /** 50 named stands in front of the player, six frozen bots with faded waving supporter names, an 80-row tab list. */
    private static void buildBusy(Session c, String self, List<TestEnemy> bots) throws Exception {
        for (int i = 0; i < 50; i++) {
            double a = -Math.PI / 2 + (i - 25) * 0.05;
            double r = 4 + (i % 10);
            // z+ is in front at yaw 0
            c.hx().stand(X + 0.5 + Math.sin(a) * r * 0.6, 151, Z + 0.5 + r,
                    (i % 3 == 0 ? "§c" : "§a") + "[Lv" + (i * 7 % 300) + "] §fHub Mob §a" + (i * 1234) + "§f/§a50000§c❤");
        }
        List<String> extra = new ArrayList<>();
        extra.add("§r§7[§8312§7] §r§b[MVP§c+§b] " + self + " §r§6⚔");
        for (int k = 0; k < 6; k++) {
            TestEnemy bot = TestEnemy.named("Nt", String.valueOf(k)).at(X + 0.5 - 5 + 2 * k, 151, Z + 4.5).frozen(true)
                    .spawn(c.ctx(), c.server());
            bots.add(bot);
            extra.add("§r§7[§8" + (200 + k) + "§7] §r§a[VIP] " + bot.name() + " §r§6♲");
        }
        String[] ranks = {"§7", "§a[VIP] ", "§a[VIP§6+§a] ", "§b[MVP] ", "§b[MVP§c+§b] ", "§6[MVP§c++§6] "};
        for (int i = extra.size(); i < 79; i++) {
            extra.add("§r§7[§8" + (100 + i * 3) + "§7] §r" + ranks[i % ranks.length] + "HubPlayer" + i + " §r§6"
                    + (i % 4 == 0 ? "⚔" : "♲"));
        }
        HxKit.tab(c, "template", "area", "area", "Hub", "extra", extra);
        c.ctx().waitTicks(40);
        StringBuilder body = new StringBuilder("{\"version\":11,\"supporters\":[");
        List<String> names = new ArrayList<>();
        for (TestEnemy b : bots) {
            names.add(b.name());
        }
        Map<String, UUID> ids = c.onClient(mc -> {
            Map<String, UUID> m = new LinkedHashMap<>();
            for (Player p : mc.level.players()) {
                if (names.contains(p.getGameProfile().name())) {
                    m.put(p.getGameProfile().name(), p.getUUID());
                }
            }
            return m;
        });
        c.check(ids.size() == bots.size(), "only " + ids.size() + " of " + bots.size() + " bots are in the client's world");
        int k = 0;
        for (Map.Entry<String, UUID> e : ids.entrySet()) {
            String shown = "Wavebot" + (char) ('A' + k);
            StringBuilder colors = new StringBuilder();
            for (int i = 0; i < shown.length(); i++) {
                int g = Math.round(255 - 255f * i / (shown.length() - 1));
                colors.append(i == 0 ? "" : ",").append((g << 16) | (255 - g));
            }
            body.append(k == 0 ? "" : ",").append("{\"uuid\":\"").append(e.getValue()).append("\",\"name\":\"&c")
                    .append(shown).append("\",\"scale\":1,\"colors\":[").append(colors)
                    .append("],\"animation\":{\"mode\":\"wave\",\"speed\":1}}");
            k++;
        }
        body.append("]}");
        String json = body.toString();
        c.onClient(mc -> {
            for (Map.Entry<String, UUID> e : ids.entrySet()) {
                Mod.staticCall("players.PlayerNames", "noteKnown", e.getValue(), e.getKey());
            }
            List<?> list = (List<?>) Mod.staticCall("supporters.SupportersFetcher", "parseSupporters",
                    com.google.gson.JsonParser.parseString(json).getAsJsonObject().get("supporters"));
            Mod.staticCall(SF, "apply", 11, list, false);
            return null;
        });
    }

    /** Every cell at a frozen animation clock (0 ms; Wave also at 250 ms), with the same chat lines on screen. */
    private static void shots(Session c, String scene, boolean busy, String self, List<String> report) throws Exception {
        for (String cfg : CFGS) {
            apply(c, cfg);
            // Supporter tab text re-reads its index at most once a second: let it.
            c.ctx().waitTicks(30);
            for (long t : new long[]{0L, 250L}) {
                String text = glyphs(c, t);
                report.add("glyphs " + scene + "-" + cfg + "-" + t + "ms: " + text.lines().count() + " line(s)");
                if (!SHOTS.isEmpty()) {
                    Path dir = Path.of(SHOTS);
                    Files.createDirectories(dir);
                    Files.writeString(dir.resolve("glyphs-" + scene + "-" + cfg + "-" + t + "ms.txt"), text);
                }
            }
            for (CameraType cam : CAMS) {
                camera(c, cam);
                long[] times = cfg.equals("wave") ? new long[]{0L, 250L} : new long[]{0L};
                for (long t : times) {
                    c.onClient(mc -> {
                        Mod.staticCall(NA, "freezeClockForTest", t);
                        McCompat.clearChatAndToasts(mc);
                        return null;
                    });
                    if (busy) {
                        chatSeq = 0;
                        for (int i = 0; i < 6; i++) {
                            chatLine(c, self);
                        }
                    }
                    for (boolean tab : busy ? new boolean[]{true, false} : new boolean[]{false}) {
                        c.onClient(mc -> {
                            mc.options.keyPlayerList.setDown(tab);
                            return null;
                        });
                        c.ctx().waitTicks(8);
                        String tag = scene + "-" + cfg + "-" + (cam == CameraType.FIRST_PERSON ? "1st" : "F5") + "-" + t + "ms"
                                + (busy && !tab ? "-notab" : "");
                        Path p = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(NAME + "-" + tag));
                        report.add("shot " + tag + ": " + p.getFileName());
                        if (!SHOTS.isEmpty()) {
                            Path dir = Path.of(SHOTS);
                            Files.createDirectories(dir);
                            Files.copy(p, dir.resolve(tag + ".png"), StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
            }
        }
        c.onClient(mc -> {
            Mod.staticCall(NA, "freezeClockForTest", -1L);
            return null;
        });
    }

    /**
     * Every glyph the Font is handed for the texts on screen at the frozen time {@code ms}, as the mod's prepareText hook
     * hands them on ({@code NameAnimation.animate(NameReplacer.replace(seq))}, the exact chain of
     * {@code NameChangerFontMixin}), plus each text's {@code Font.width} (through the mod's width hooks): the tab list's
     * names, the chat's lines, and every entity's nametag Component (the renderer's own {@code getNameTag}). Two jars
     * writing the same file paint the same text.
     */
    private static String glyphs(Session c, long ms) {
        return c.onClient(mc -> {
            Mod.staticCall(NA, "freezeClockForTest", ms);
            StringBuilder sb = new StringBuilder();
            List<net.minecraft.client.multiplayer.PlayerInfo> infos = new ArrayList<>(mc.getConnection().getListedOnlinePlayers());
            infos.sort(java.util.Comparator.comparing(i -> i.getProfile().name()));
            for (var info : infos) {
                net.minecraft.network.chat.Component comp = McCompat.tabList(mc).getNameForDisplay(info);
                dump(mc, sb, "tab " + info.getProfile().name(), comp);
            }
            try {
                java.lang.reflect.Field f = net.minecraft.client.gui.components.ChatComponent.class.getDeclaredField("trimmedMessages");
                f.setAccessible(true);
                int i = 0;
                for (Object line : (List<?>) f.get(McCompat.chat(mc))) {
                    java.lang.reflect.Method m = line.getClass().getMethod("content");
                    m.setAccessible(true);
                    dumpSeq(mc, sb, "chat " + (i++), (net.minecraft.util.FormattedCharSequence) m.invoke(line));
                }
            } catch (ReflectiveOperationException e) {
                sb.append("chat unreadable: ").append(e).append('\n');
            }
            List<net.minecraft.world.entity.Entity> ents = new ArrayList<>();
            for (var e : mc.level.entitiesForRendering()) {
                ents.add(e);
            }
            ents.sort(java.util.Comparator.comparing((net.minecraft.world.entity.Entity e) -> e.getType().toString())
                    .thenComparing(e -> e.getName().getString()).thenComparingDouble(e -> e.getX()).thenComparingDouble(e -> e.getZ()));
            for (var e : ents) {
                try {
                    Object r = mc.getEntityRenderDispatcher().getRenderer(e);
                    java.lang.reflect.Method m = net.minecraft.client.renderer.entity.EntityRenderer.class
                            .getDeclaredMethod("getNameTag", net.minecraft.world.entity.Entity.class);
                    m.setAccessible(true);
                    net.minecraft.network.chat.Component tag = (net.minecraft.network.chat.Component) m.invoke(r, e);
                    // Only what draws a tag here: players and named stands (falling blocks from the platform build
                    // and other unnamed entities vary between runs and are never drawn with a tag).
                    boolean shown = e instanceof Player || (e.hasCustomName() && e.isCustomNameVisible());
                    if (tag != null && shown) {
                        dump(mc, sb, "tag " + e.getType() + " " + e.getName().getString(), tag);
                    }
                } catch (ReflectiveOperationException ex) {
                    sb.append("tag unreadable: ").append(ex).append('\n');
                }
            }
            for (String str : new String[]{mc.player.getGameProfile().name(), "§a" + mc.player.getGameProfile().name() + " §7hi"}) {
                Object replaced = Mod.staticCall("namechanger.NameReplacer", "replace", str);
                StringBuilder g = new StringBuilder();
                Mod.staticCall(NA, "iterate", replaced, net.minecraft.network.chat.Style.EMPTY,
                        (net.minecraft.util.FormattedCharSink) (pos, st, cp) -> {
                            g.append(Character.toString(cp)).append('[').append(look(st)).append(']');
                            return true;
                        });
                sb.append("string ").append(str).append(" width ").append(mc.font.width(str)).append(" -> ").append(g).append('\n');
            }
            return sb.toString();
        });
    }

    /** What of a Style reaches the pixels: colour, the five formats, font, shadow colour (not click/hover/insertion). */
    private static String look(net.minecraft.network.chat.Style st) {
        net.minecraft.network.chat.TextColor col = st.getColor();
        return (col == null ? "-" : String.format("%06x", col.getValue() & 0xFFFFFF)) + (st.isBold() ? "b" : "")
                + (st.isItalic() ? "i" : "") + (st.isUnderlined() ? "u" : "") + (st.isStrikethrough() ? "s" : "")
                + (st.isObfuscated() ? "o" : "") + " " + st.getFont() + " " + st.getShadowColor();
    }

    private static void dump(net.minecraft.client.Minecraft mc, StringBuilder sb, String what, net.minecraft.network.chat.Component comp) {
        sb.append(what).append(" width ").append(mc.font.width(comp)).append(' ');
        dumpSeq(mc, sb, "", comp.getVisualOrderText());
    }

    private static void dumpSeq(net.minecraft.client.Minecraft mc, StringBuilder sb, String what, net.minecraft.util.FormattedCharSequence seq) {
        net.minecraft.util.FormattedCharSequence out = (net.minecraft.util.FormattedCharSequence) Mod.staticCall(NA, "animate",
                Mod.staticCall("namechanger.NameReplacer", "replace", seq));
        sb.append(what).append(" seqwidth ").append(mc.font.width(seq)).append(" -> ");
        out.accept((pos, st, cp) -> {
            sb.append(Character.toString(cp)).append('[').append(look(st)).append(']');
            return true;
        });
        sb.append('\n');
    }

    private static FrameClock.Stats stats(long[][] parts) {
        long[] all = FpsBenchTest.concat(parts);
        return FrameClock.stats(all, all.length);
    }
}
