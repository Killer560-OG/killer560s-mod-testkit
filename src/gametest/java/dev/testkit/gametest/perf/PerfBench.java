package dev.testkit.gametest.perf;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.Machine;
import dev.testkit.gametest.ModUnderTest;
import dev.testkit.gametest.Scenario;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.gametest.mod.Quiet;
import dev.testkit.gametest.ui.JarIndex;
import dev.testkit.harness.BenchCamera;
import dev.testkit.harness.FrameClock;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The fixed scene and the measuring for the 95-perf-* benches (2026-10-15). See docs/fps-bench.md.
 *
 * <p>Scene: a generated sim F7 floor with the sim's RNGs seeded (the same floor every run), the player standing where
 * the floor puts him, window 854x480 (the gametest default, so it still fits a screen quadrant) at GUI scale 2, render distance 8, FOV 70, vsync off, frame rate unlimited, no
 * screen open, and {@link BenchCamera} turning the view one full circle every {@link #PERIOD} ticks at 10 degrees
 * down, restarted from the same yaw at the start of every phase.
 *
 * <p>A phase records {@link FrameClock} (CPU time of extract+render per frame and of {@code Minecraft.tick} per tick),
 * the render thread's allocated bytes, and the mod's {@code PerfProbe} counters (per listener / HUD element self time
 * on the render thread, minus the probe's own calibrated per-call cost). The probe needs {@code -PperfProbe}, which
 * the build turns on by itself for any {@code -Pscenario} naming 95-perf.
 */
final class PerfBench {

    static final String PROBE = "com.killer560.hub.perfprobe.PerfProbe";
    static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    static final long SEED = 95_2026_1005L;
    /** Ticks per camera circle. Every phase length is a multiple of it. */
    static final int PERIOD = 200;
    /** The real game's ratio, used to put frame and tick costs on one scale: cost per second at 144 fps, 20 tps. */
    static final double REF_FPS = 144.0;

    /**
     * Packages whose features act on the world or the player, or reach the OS or network: never switched ON by a bench
     * (they are still switched off for OFF). Automation would change the scene between phases and is not what a
     * player's frame rate is about; the rest have no business running in a test client.
     */
    static final List<String> NEVER_ON = List.of(
            "autoroutes.", "autosecret.", "autoclear.", "autopuzzles.", "leveraura.", "terminalaura.", "secrettrigger.",
            "terminaltrigger.", "goldor.", "ap3.", "pathfinding.", "autokick.", "autosell.", "autoanvil.",
            "autojoinskyblock.", "automeow.", "autodebuff.", "autotrap.", "autoclosechest.", "invsort.", "fastleap.",
            "cheatutils.", "dungeonbreaker.", "simonsays.", "voicetotext.", "spotify.", "discordrpc.", "proxy.",
            "accounts.", "window.", "windowlayout.", "reboot.", "updatecheck.", "autoupdate.", "relay.", "modchat.",
            "bridge.", "bugreport.", "bazaarflip.", "auction.", "translate.", "clicktranslate.", "shorts.",
            "partycommands.", "interop.", "dungeonqueue.", "packdisabler.", "supporters.", "autocorrect.");

    /** Switch names that act on the world or the player or reach out, in any package (ArrowAlign's TriggerBot...). */
    static final java.util.regex.Pattern NEVER_ON_NAME = java.util.regex.Pattern.compile(
            "Auto|Aura|Trigger|Breaker|Dialogue|Share|Logger|Protection|DropKey|MelodyKeys|Borderless|Restore|Notify"
                    + "|Cosmetics|PreventMisclicks");

    /** Switches that are not a plain isEnabled/setEnabled config but are features of their own (95's ON set). */
    static final String[][] EXTRA = {
            {"dungeoninfo.DungeonInfoConfig", "setSecretsHudEnabled", "isSecretsHudEnabled"},
            {"mobesp.MobEspConfig", "setStarredMobs", "isStarredMobsEnabled"},
            {"mobesp.MobEspConfig", "setBats", "isBatsEnabled"},
            {"mobesp.MobEspConfig", "setWithers", "isWithersEnabled"},
            // Etherwarp Waypoints' master toggle is its highlight (its setEnabled is the old, removed HUD list's).
            {"etherwarp.EtherwarpWaypointsConfig", "setHighlightBlocks", "isHighlightBlocks"},
    };

    private PerfBench() {
    }

    // ---- switches -----------------------------------------------------------------------------------------------

    /** One on/off switch of the mod. */
    record Switch(String label, Object cfg, Method is, Method set) {
        boolean get() {
            try {
                return (Boolean) is.invoke(cfg);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }

        void put(boolean v) {
            try {
                set.invoke(cfg, v);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(label + ": " + e, e);
            }
        }

        boolean neverOn() {
            // A switch whose own name says it acts or reaches out, whatever package it lives in.
            if (label.contains("#") && NEVER_ON_NAME.matcher(label.substring(label.indexOf('#'))).find()) {
                return true;
            }
            for (String p : NEVER_ON) {
                if (label.startsWith(p)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Every config in the jar with getInstance + isEnabled + setEnabled(boolean), plus {@link #EXTRA}. Client thread. */
    static List<Switch> switches() {
        Map<String, Switch> out = new TreeMap<>();
        for (String fq : JarIndex.classNames(s -> s.endsWith("Config"))) {
            if (fq.contains(".mixin.")) {
                continue;
            }
            Class<?> c = JarIndex.peek(fq);
            if (c == null) {
                continue;
            }
            Object cfg;
            try {
                Method gi = c.getMethod("getInstance");
                if (!Modifier.isStatic(gi.getModifiers())) {
                    continue;
                }
                cfg = gi.invoke(null);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                continue; // not a singleton config
            }
            String base = fq.substring(Mod.ROOT.length());
            try {
                Method is = c.getMethod("isEnabled");
                Method set = c.getMethod("setEnabled", boolean.class);
                if (!Modifier.isStatic(is.getModifiers())) {
                    out.put(base, new Switch(base, cfg, is, set));
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                // no plain isEnabled/setEnabled
            }
            // A feature's master switch is often named for it (setSecretsHudEnabled, setBearHud, setDvdEnabled):
            // every set...Enabled / set...Hud(boolean) with a boolean getter counts, or OFF would leave it running
            // (2026-10-15: Thorn's bear HUD, Blessings, Dungeon Extras and a dozen more were never switched off).
            for (Method set : c.getMethods()) {
                String n = set.getName();
                if (Modifier.isStatic(set.getModifiers()) || set.getParameterCount() != 1
                        || set.getParameterTypes()[0] != boolean.class || n.equals("setEnabled")
                        || !(n.endsWith("Enabled") || n.endsWith("Hud"))) {
                    continue;
                }
                String prop = n.substring(3);
                Method is = getter(c, prop);
                if (is != null) {
                    String label = base + "#" + prop;
                    out.putIfAbsent(label, new Switch(label, cfg, is, set));
                }
            }
        }
        for (String[] e : EXTRA) {
            try {
                Object cfg = Mod.cfg(e[0]);
                Method is = cfg.getClass().getMethod(e[2]);
                Method set = cfg.getClass().getMethod(e[1], boolean.class);
                String label = e[0] + "#" + e[1].replaceFirst("^set", "");
                out.put(label, new Switch(label, cfg, is, set));
            } catch (Throwable ignored) {
                // reported by the caller as missing
            }
        }
        return new ArrayList<>(out.values());
    }

    private static Method getter(Class<?> c, String prop) {
        for (String g : new String[]{"is" + prop, "get" + prop, "is" + prop + "Raw", "get" + prop + "Raw"}) {
            try {
                Method m = c.getMethod(g);
                if (!Modifier.isStatic(m.getModifiers()) && (m.getReturnType() == boolean.class)) {
                    return m;
                }
            } catch (NoSuchMethodException ignored) {
                // try the next name
            }
        }
        return null;
    }

    static Map<Switch, Boolean> states(List<Switch> all) {
        Map<Switch, Boolean> m = new LinkedHashMap<>();
        for (Switch s : all) {
            m.put(s, s.get());
        }
        return m;
    }

    /** Put every switch to {@code state}; switches not in it are left alone. Client thread. */
    static void apply(Map<Switch, Boolean> state) {
        // Off first, then on: a feature that needs another one sees it on when it turns on.
        state.forEach((s, v) -> {
            if (!v) {
                s.put(false);
            }
        });
        state.forEach((s, v) -> {
            if (v) {
                s.put(true);
            }
        });
    }

    static Map<Switch, Boolean> allOff(List<Switch> all) {
        Map<Switch, Boolean> m = new LinkedHashMap<>();
        for (Switch s : all) {
            m.put(s, false);
        }
        return m;
    }

    /** Defaults with every bench-safe switch on. */
    static Map<Switch, Boolean> allOn(Map<Switch, Boolean> defaults) {
        Map<Switch, Boolean> m = new LinkedHashMap<>();
        defaults.forEach((s, v) -> m.put(s, s.neverOn() ? v : Boolean.TRUE));
        return m;
    }

    static Map<Switch, Boolean> alone(List<Switch> all, Switch one) {
        Map<Switch, Boolean> m = allOff(all);
        m.put(one, true);
        return m;
    }

    // ---- scene --------------------------------------------------------------------------------------------------

    /** Build the fixed scene. Returns false (after a SKIP row) when this machine has no room data to build it with. */
    static boolean open(ClientGameTestContext ctx, String name, List<String> report) {
        ModUnderTest.require("killer560smod");
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> {
            if (Quiet.enabled()) {
                Quiet.apply();
            }
            ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled");
        });
        if (copyRealRooms() < 20) {
            Scenario.skipped(name, "needs room captures (Machine.roomsConfigDir) to build a floor");
            return false;
        }
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 6000);
        if (Scenario.ensureRoomDatabase(ctx) == 0) {
            Scenario.skipped(name, "needs the room database (killer560smod-roomdata) to build a floor");
            return false;
        }
        ctx.getInput().resizeWindow(854, 480);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter",
                new Class<?>[]{String.class}, new Object[]{"gametest"}));
        report.add("sim RNGs seeded with " + SEED + ": " + ctx.computeOnClient(mc -> seedSimRandoms(SEED)));
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> {
            Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
            ModUnderTest.staticCall(FLOOR_GEN, "generate",
                    new Class<?>[]{Minecraft.class, floor.getClass(), int.class, int.class},
                    new Object[]{mc, floor, 3, 3});
        }));
        ctx.waitFor(mc -> mc.level != null, 6000);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitFor(mc -> mc.player != null && mc.player.onGround(), 2400);
        ctx.waitTicks(100);
        boolean inDungeon = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(
                "com.killer560.hub.secrets.DungeonState", "isInDungeon"));
        if (!inDungeon) {
            throw new AssertionError("[" + name + "] the mod does not think the sim floor is a dungeon");
        }
        String scene = ctx.computeOnClient(mc -> {
            mc.options.enableVsync().set(false);
            mc.options.framerateLimit().set(260);
            mc.options.renderDistance().set(8);
            mc.options.guiScale().set(2);
            mc.options.fov().set(70);
            McCompat.setScreen(mc, null);
            return String.format(Locale.ROOT, "window %dx%d gui %d, render distance %d, at %.1f %.1f %.1f",
                    mc.getWindow().getWidth(), mc.getWindow().getHeight(), mc.options.guiScale().get(),
                    mc.options.renderDistance().get(), mc.player.getX(), mc.player.getY(), mc.player.getZ());
        });
        report.add("scene: sim F7, " + scene + ", camera 360 deg / " + PERIOD + " ticks at pitch 10");
        return true;
    }

    static void close(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> BenchCamera.stop());
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "leave"));
        ctx.waitTicks(20);
        ctx.runOnClient(mc -> mc.execute(() -> {
            if (mc.level != null) {
                mc.level.disconnect(net.minecraft.network.chat.Component.literal("bench over"));
                mc.disconnectWithSavingScreen();
            }
        }));
        ctx.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null, 2400);
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> mc.execute(() ->
                McCompat.setScreen(mc, new net.minecraft.client.gui.screens.TitleScreen())));
        ctx.waitFor(mc -> McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.TitleScreen, 600);
    }

    private static String seedSimRandoms(long seed) {
        List<String> done = new ArrayList<>();
        for (String cls : new String[]{"roomsim.SimFloorGen", "roomsim.SimGenerator", "roomsim.SimMimic"}) {
            try {
                ((java.util.Random) Mod.field(cls, "RNG")).setSeed(seed);
                done.add(cls);
            } catch (Throwable t) {
                done.add(cls + " (MISSING: " + t.getMessage() + ")");
            }
        }
        return String.join(", ", done);
    }

    private static int copyRealRooms() {
        try {
            Path source = Path.of(ModUnderTest.instanceConfig(Machine.roomsConfigDir(), "killer560smod-rooms"));
            if (!Files.isDirectory(source)) {
                return 0;
            }
            Path target = ModUnderTest.modConfig("killer560smod-rooms");
            Files.createDirectories(target);
            int n = 0;
            try (var s = Files.list(source)) {
                for (Path f : (Iterable<Path>) s.filter(p -> p.toString().endsWith(".json"))::iterator) {
                    Files.copy(f, target.resolve(f.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    n++;
                }
            }
            return n;
        } catch (Exception e) {
            return 0;
        }
    }

    // ---- probe --------------------------------------------------------------------------------------------------

    static boolean probeOn(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            try {
                return (Boolean) Mod.staticCall("perfprobe.PerfProbe", "enabled")
                        && (Integer) Mod.staticCall("perfprobe.PerfProbe", "keyCount") > 0;
            } catch (Throwable t) {
                return false;
            }
        });
    }

    /** {outer ns/call, recorded ns/call, recorded bytes/call}. */
    static double[] calibrate(ClientGameTestContext ctx) {
        String s = ctx.computeOnClient(mc -> (String) Mod.staticCall("perfprobe.PerfProbe", "calibrate", 200_000));
        String[] p = s.split("\t");
        return new double[]{Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2])};
    }

    /** One probe key's totals over a phase. */
    record Key(String key, String kind, long calls, double ns, double bytes, long maxNs) {
        /** The feature a key belongs to: the first package of its owner ("secretwaypoints.SecretsHud HUD[..]" -> "secretwaypoints"). */
        String feature() {
            int dot = key.indexOf('.');
            int sp = key.indexOf(' ');
            return dot > 0 && (sp < 0 || dot < sp) ? key.substring(0, dot) : key.split(" ")[0];
        }
    }

    // ---- phases -------------------------------------------------------------------------------------------------

    /** What one phase measured. */
    record Phase(String label, FrameClock.Stats frame, FrameClock.Stats tick, long allocBytes, long wallNs,
                 Map<String, Key> keys) {
        int frames() {
            return frame.n();
        }

        int ticks() {
            return tick.n();
        }

        /** Probe self time per frame (frame-kind keys), per tick (tick + event keys) and server, in microseconds. */
        double frameUs() {
            return sum("frame") / 1e3 / Math.max(1, frames());
        }

        double tickUs() {
            return (sum("tick") + sum("event")) / 1e3 / Math.max(1, ticks());
        }

        double serverUs() {
            return sum("server") / 1e3 / Math.max(1, ticks());
        }

        /** The client-thread cost per frame of a player at 144 fps / 20 tps, in microseconds. */
        double at144Us() {
            return frameUs() + tickUs() * 20.0 / REF_FPS;
        }

        double allocPerFrame() {
            return allocBytes / (double) Math.max(1, frames());
        }

        /** Render-thread allocation in MB per second of this phase. */
        double allocMBs() {
            return allocBytes / 1048576.0 / Math.max(1e-9, wallNs / 1e9);
        }

        double sum(String kind) {
            double s = 0;
            for (Key k : keys.values()) {
                if (k.kind().equals(kind)) {
                    s += k.ns();
                }
            }
            return s;
        }

        /** Per feature (first package), at144 microseconds. */
        Map<String, Double> byFeature() {
            Map<String, Double> m = new TreeMap<>();
            for (Key k : keys.values()) {
                if (k.kind().equals("server")) {
                    continue;
                }
                m.merge(k.feature(), keyAt144(k), Double::sum);
            }
            return m;
        }

        double keyAt144(Key k) {
            return k.kind().equals("frame") ? k.ns() / 1e3 / Math.max(1, frames())
                    : k.ns() / 1e3 / Math.max(1, ticks()) * 20.0 / REF_FPS;
        }

        String line() {
            return String.format(Locale.ROOT, "%-34s frame %s | tick %s | mod %.1f us/frame + %.1f us/tick = %.1f us/frame@144"
                            + " | alloc %.1f KB/frame %.1f MB/s",
                    label, frame.line(), tick.line(), frameUs(), tickUs(), at144Us(), allocPerFrame() / 1024.0,
                    allocMBs());
        }
    }

    /** Run {@code ticks} ticks with the camera path and every counter fresh. */
    static Phase measure(ClientGameTestContext ctx, String label, int ticks, double[] cal) {
        long[] alloc = new long[2];
        ctx.runOnClient(mc -> {
            BenchCamera.start(0f, 10f, PERIOD);
            Mod.staticCall("perfprobe.PerfProbe", "reset");
            alloc[0] = threadAllocated();
            alloc[1] = System.nanoTime();
            FrameClock.start(ticks * 100); // an idle gametest loop can render 40+ frames a tick
        });
        ctx.waitTicks(ticks);
        return ctx.computeOnClient(mc -> {
            FrameClock.stop();
            long bytes = threadAllocated() - alloc[0];
            long wall = System.nanoTime() - alloc[1];
            String snap = (String) Mod.staticCall("perfprobe.PerfProbe", "snapshot");
            Map<String, Key> keys = new LinkedHashMap<>();
            for (String line : snap.split("\n")) {
                if (line.isBlank()) {
                    continue;
                }
                String[] p = line.split("\t");
                long calls = Long.parseLong(p[2]);
                boolean render = !p[1].equals("server");
                double ns = Long.parseLong(p[3]) - (render ? calls * cal[1] : 0);
                double b = Long.parseLong(p[4]) - (render ? calls * cal[2] : 0);
                keys.put(p[0], new Key(p[0], p[1], calls, Math.max(0, ns), Math.max(0, b), Long.parseLong(p[5])));
            }
            long[] f = FrameClock.frameSamples();
            long[] t = FrameClock.tickSamples();
            return new Phase(label, FrameClock.stats(f, f.length), FrameClock.stats(t, t.length), bytes, wall, keys);
        });
    }

    static long threadAllocated() {
        return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean()).getCurrentThreadAllocatedBytes();
    }

    static Path outDir() {
        return Path.of(System.getProperty("testkit.reportDir", "build/testkit-report"));
    }
}
