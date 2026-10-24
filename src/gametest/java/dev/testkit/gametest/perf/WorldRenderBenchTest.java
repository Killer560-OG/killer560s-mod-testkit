package dev.testkit.gametest.perf;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.ModUnderTest;
import dev.testkit.gametest.Scenario;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.gametest.mod.Quiet;
import dev.testkit.gametest.perf.PerfBench.Key;
import dev.testkit.gametest.perf.PerfBench.Phase;
import dev.testkit.gametest.perf.PerfBench.Switch;
import dev.testkit.harness.DrawCalls;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 95-perf-world (2026-10-15, fps-world-1015): what the mod's IN-WORLD drawing costs when it has something to draw (~5 min,
 * only when named). The 95-perf benches stand in a sim F7 start room where almost no world renderer has anything to
 * draw, so every LevelRenderEvents listener sat at the probe's floor there. This one builds the sim's "Chambers" room
 * (secrets and crypts), stands in it with 20 starred zombies around him and a Terminator in his hand, everything else
 * switched off, and measures four switch sets against a BASE with none of them, two rounds each:
 * <ul>
 *   <li>ESP: Dungeon ESP, starred mobs, Filled Box, through walls (a fill and an outline per mob);</li>
 *   <li>WAYPOINTS: Secret Waypoints, Fill + Outline, through walls, names on, crypts shown;</li>
 *   <li>TRAJ: Trajectories, shortbows on (the Terminator's three arcs, every frame);</li>
 *   <li>ALL: the three together.</li>
 * </ul>
 * Per phase: the mod's PerfProbe time and render-thread allocation of the world render callbacks per frame, and the
 * DRAW CALLS per frame out of the immediate buffer sources ({@code harness/DrawCalls}, 26.1.2). The verdict is judged on
 * the draw calls (deterministic) and on each feature having drawn (asserted, so a phase that drew nothing cannot pass):
 * ALL may add at most {@link #MAX_ADDED_DRAWS} draw calls per frame over BASE, and ESP may allocate at most
 * {@link #MAX_ESP_BYTES} per frame. Main (69505e45) adds one or more per box
 * and type switch, about 50 here; the branch draws each render type once per event.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class WorldRenderBenchTest implements FabricClientGameTest {

    private static final String NAME = "95-perf-world";
    private static final String ROOM = "Chambers";
    private static final int MOBS = 20;
    private static final int TICKS = Integer.getInteger("testkit.perfWorldTicks", 400);
    private static final int ROUNDS = Integer.getInteger("testkit.perfWorldRounds", 2);
    /** Draw calls per frame ALL may add over BASE: ESP fill + lines, waypoint fill + lines, the names' text, the arcs'
     *  lines, plus slack for the text's glyph pages and an event with nothing in it. */
    static final double MAX_ADDED_DRAWS = 12.0;
    /** Render-thread bytes per frame the world callbacks may allocate with ESP on (20 filled boxes): main 69505e45
     *  allocated ~112 KB (a color array, a lambda, an AABB and two Vec3 per box and draw, plus a buffer per draw call);
     *  the branch ~6.6 KB, nearly all of it vanilla's own batch end. */
    static final int MAX_ESP_BYTES = 16384;
    /** The world render packages this bench is about, plus the batch flush in the mod's compat layer. */
    private static final List<String> WORLD = List.of("mobesp", "secretwaypoints", "trajectories", "compat");

    @Override
    public void runTest(ClientGameTestContext ctx) {
        String filter = System.getProperty("testkit.scenario", "");
        if (filter.isBlank() || Scenario.skip(NAME)) {
            return;
        }
        List<String> report = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        if (!open(ctx, report)) {
            return;
        }
        Map<Switch, Boolean> defaults = new LinkedHashMap<>();
        try {
            if (!PerfBench.probeOn(ctx)) {
                throw new AssertionError("[" + NAME + "] the mod's PerfProbe is off: run with -PperfProbe and a dev jar");
            }
            double[] cal = PerfBench.calibrate(ctx);
            List<Switch> all = ctx.computeOnClient(mc -> PerfBench.switches());
            defaults.putAll(ctx.computeOnClient(mc -> PerfBench.states(all)));
            Map<Switch, Boolean> off = PerfBench.allOff(all);
            ctx.runOnClient(mc -> PerfBench.apply(off));
            ctx.runOnClient(WorldRenderBenchTest::configure);
            spawnMobs(ctx, report);
            giveTerminator(ctx, report);
            ctx.waitTicks(60);

            String[] sets = {"BASE", "ESP", "WAYPOINTS", "TRAJ", "ALL"};
            Map<String, List<Row>> rows = new LinkedHashMap<>();
            for (int round = 1; round <= ROUNDS; round++) {
                for (String set : sets) {
                    ctx.runOnClient(mc -> select(set));
                    ctx.waitTicks(40); // the ESP and waypoint caches refresh on the tick
                    Row r = measure(ctx, set + " r" + round, cal);
                    rows.computeIfAbsent(set, k -> new ArrayList<>()).add(r);
                    report.add(r.line());
                    for (String k : r.topKeys) {
                        report.add("    " + k);
                    }
                    check(set, r, failures);
                }
            }
            ctx.runOnClient(mc -> select("BASE"));

            // ---- verdict: draw calls ALL adds over BASE (min over rounds; other work only ever adds) ----
            double base = min(rows.get("BASE"), true);
            double allOn = min(rows.get("ALL"), true);
            boolean counted = ctx.computeOnClient(mc -> DrawCalls.available());
            if (!counted) {
                report.add("draw calls: not counted (no MultiBufferSource on this Minecraft version) - no draw-call verdict");
            } else {
                String line = String.format(Locale.ROOT, "draw calls per frame: BASE %.1f, ALL %.1f, ALL adds %.1f (budget %.0f)",
                        base, allOn, allOn - base, MAX_ADDED_DRAWS);
                report.add(line);
                for (String set : new String[]{"ESP", "WAYPOINTS", "TRAJ"}) {
                    report.add(String.format(Locale.ROOT, "  %s adds %.1f draw call(s) per frame", set,
                            min(rows.get(set), true) - base));
                }
                if (allOn - base > MAX_ADDED_DRAWS) {
                    failures.add(line);
                }
            }
            double espBytes = minBytes(rows.get("ESP"));
            String espLine = String.format(Locale.ROOT, "Dungeon ESP, 20 filled boxes: %.0f B/frame allocated by the world"
                    + " callbacks (budget %d)", espBytes, MAX_ESP_BYTES);
            report.add(espLine);
            if (espBytes > MAX_ESP_BYTES) {
                failures.add(espLine);
            }
            report.add(String.format(Locale.ROOT, "world callbacks (min of rounds): BASE %.1f us %.0f B, ESP %.1f us %.0f B,"
                            + " WAYPOINTS %.1f us %.0f B, TRAJ %.1f us %.0f B, ALL %.1f us %.0f B per frame",
                    min(rows.get("BASE"), false), minBytes(rows.get("BASE")), min(rows.get("ESP"), false), minBytes(rows.get("ESP")),
                    min(rows.get("WAYPOINTS"), false), minBytes(rows.get("WAYPOINTS")), min(rows.get("TRAJ"), false),
                    minBytes(rows.get("TRAJ")), min(rows.get("ALL"), false), minBytes(rows.get("ALL"))));
            write(report);
        } finally {
            try {
                if (!defaults.isEmpty()) {
                    ctx.runOnClient(mc -> PerfBench.apply(defaults));
                }
            } catch (Throwable ignored) {
                // restoring config must not mask the real failure
            }
            for (String line : report) {
                System.out.println("[" + NAME + "] " + line);
            }
            PerfBench.close(ctx);
        }
        if (!failures.isEmpty()) {
            throw new AssertionError("[" + NAME + "] " + failures.size() + " failure(s):\n  " + String.join("\n  ", failures));
        }
        System.out.println("[" + NAME + "] PASS");
    }

    // ---- scene ------------------------------------------------------------------------------------------------

    private static boolean open(ClientGameTestContext ctx, List<String> report) {
        ModUnderTest.require("killer560smod");
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> {
            if (Quiet.enabled()) {
                Quiet.apply();
            }
            ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled");
        });
        if (PerfBench.copyRealRooms() < 20) {
            Scenario.skipped(NAME, "needs room captures (Machine.roomsConfigDir) to build a room");
            return false;
        }
        ctx.runOnClient(mc -> ModUnderTest.staticCall(PerfBench.ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(PerfBench.ROOM_LIBRARY, "isReady"), 6000);
        if (Scenario.ensureRoomDatabase(ctx) == 0) {
            Scenario.skipped(NAME, "needs the room database (killer560smod-roomdata) to build a room");
            return false;
        }
        ctx.getInput().resizeWindow(854, 480);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(PerfBench.SIM_STATE, "enter",
                new Class<?>[]{String.class}, new Object[]{"gametest"}));
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimBuilder",
                "buildSingleRoom", new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, ROOM})));
        ctx.waitFor(mc -> mc.level != null && mc.player != null, 6000);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitFor(mc -> McCompat.screen(mc) == null, 1200);
        ctx.waitFor(mc -> mc.player != null && mc.player.onGround()
                && !mc.level.getBlockState(mc.player.blockPosition().below()).isAir(), 2400);
        ctx.waitTicks(60);
        boolean inDungeon = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(
                "com.killer560.hub.secrets.DungeonState", "isInDungeon"));
        if (!inDungeon) {
            throw new AssertionError("[" + NAME + "] the mod does not think the sim room is a dungeon");
        }
        String scene = ctx.computeOnClient(mc -> {
            mc.options.enableVsync().set(false);
            mc.options.framerateLimit().set(260);
            mc.options.renderDistance().set(8);
            mc.options.guiScale().set(2);
            mc.options.fov().set(70);
            return String.format(Locale.ROOT, "window %dx%d gui %d, render distance %d, at %.1f %.1f %.1f",
                    mc.getWindow().getWidth(), mc.getWindow().getHeight(), mc.options.guiScale().get(),
                    mc.options.renderDistance().get(), mc.player.getX(), mc.player.getY(), mc.player.getZ());
        });
        report.add("scene: sim room " + ROOM + ", " + scene + ", camera 360 deg / " + PerfBench.PERIOD + " ticks at pitch 10, "
                + ROUNDS + " round(s) x " + TICKS + " ticks per set");
        return true;
    }

    /** Client thread: each feature's look for this bench (switches stay off until {@link #select}). */
    private static void configure(Minecraft mc) {
        Object esp = Mod.cfg("mobesp.MobEspConfig");
        Mod.call(esp, "setRoomScoped", false);
        Mod.call(esp, "setRange", 40.0);
        Mod.call(esp, "setThroughWalls", true);
        for (int i = 0; i < 3 && !"FILLED".equals(((Enum<?>) Mod.call(esp, "getStyle")).name()); i++) {
            Mod.call(esp, "cycleStyle");
        }
        Object sw = Mod.cfg("secretwaypoints.SecretWaypointsConfig");
        Mod.call(sw, "setStyle", Mod.enumValue("secretwaypoints.SecretWaypointsConfig$Style", "FILL_OUTLINE"));
        Mod.call(sw, "setThroughWalls", true);
        Mod.call(sw, "setShowNames", true);
        Mod.call(sw, "setShowCrypts", true);
        Mod.call(Mod.cfg("trajectories.TrajectoriesConfig"), "setShowShortbows", true);
    }

    /** Client thread. */
    private static void select(String set) {
        boolean esp = set.equals("ESP") || set.equals("ALL");
        boolean wp = set.equals("WAYPOINTS") || set.equals("ALL");
        boolean traj = set.equals("TRAJ") || set.equals("ALL");
        Mod.call(Mod.cfg("mobesp.MobEspConfig"), "setStarredMobs", esp);
        Mod.call(Mod.cfg("secretwaypoints.SecretWaypointsConfig"), "setEnabled", wp);
        Mod.call(Mod.cfg("trajectories.TrajectoriesConfig"), "setEnabled", traj);
        try {
            Mod.staticCall("secretwaypoints.SecretWaypointsFeature", "invalidateCache");
        } catch (Throwable ignored) {
            // older jars rebuild on their own TTL
        }
    }

    private static void spawnMobs(ClientGameTestContext ctx, List<String> report) {
        ctx.runOnClient(mc -> {
            BlockPos feet = mc.player.blockPosition();
            Object zombie = ModUnderTest.enumValue("com.killer560.hub.roomsim.SimMobs$Kind", "ZOMBIE");
            for (int i = 0; i < MOBS; i++) {
                double a = Math.PI * 2 * i / MOBS;
                double r = 3.0 + (i % 3) * 1.5;
                BlockPos at = feet.offset((int) Math.round(Math.cos(a) * r), 0, (int) Math.round(Math.sin(a) * r));
                ModUnderTest.staticCall("com.killer560.hub.roomsim.SimMobs", "spawnStarred",
                        new Class<?>[]{Minecraft.class, BlockPos.class, zombie.getClass()}, new Object[]{mc, at, zombie});
            }
        });
        ctx.waitTicks(40);
        int starred = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall("com.killer560.hub.roomsim.SimMobs",
                "starredCount"));
        report.add("spawned " + MOBS + " starred zombies; the sim counts " + starred + " starred");
    }

    private static void giveTerminator(ClientGameTestContext ctx, List<String> report) {
        AtomicReference<Boolean> given = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            UUID me = mc.player.getUUID();
            int slot = mc.player.getInventory().getSelectedSlot();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(me);
                if (sp != null) {
                    sp.getInventory().setItem(slot, (ItemStack) ModUnderTest.staticCall(
                            "com.killer560.hub.roomsim.SimItems", "build", new Class<?>[]{String.class},
                            new Object[]{"TERMINATOR"}));
                }
                given.set(sp != null);
            });
        });
        ctx.waitFor(mc -> given.get() != null, 200);
        ctx.waitTicks(20);
        String held = ctx.computeOnClient(mc -> mc.player.getMainHandItem().getHoverName().getString());
        report.add("holding: " + held);
    }

    // ---- measuring --------------------------------------------------------------------------------------------

    /** One phase: probe keys of the world packages, draw calls, and what each feature drew. */
    private record Row(String label, Phase phase, double worldUs, double worldBytes, double drawsPerFrame,
                       double batchedPerFrame, double flushesPerFrame, int espTargets, int waypoints, int arcs,
                       long arcFrames, List<String> topKeys) {
        String line() {
            return String.format(Locale.ROOT, "%-14s frames %5d, frame %.3f ms | world callbacks %.2f us/frame, %.0f B/frame"
                            + " | draw calls %.1f/frame | batched %.1f/frame in %.2f flush(es) | esp %d, waypoints %d, arcs %d"
                            + " (%d frames drawn) | all mod %.1f us/frame, alloc %.1f KB/frame",
                    label, phase.frames(), phase.frame().mean(), worldUs, worldBytes, drawsPerFrame, batchedPerFrame,
                    flushesPerFrame, espTargets, waypoints, arcs, arcFrames, phase.frameUs(),
                    phase.allocPerFrame() / 1024.0);
        }
    }

    private static Row measure(ClientGameTestContext ctx, String label, double[] cal) {
        long[] before = ctx.computeOnClient(mc -> counters());
        Phase p = PerfBench.measure(ctx, label, TICKS, cal);
        long[] after = ctx.computeOnClient(mc -> counters());
        int frames = Math.max(1, p.frames());
        double us = 0;
        double bytes = 0;
        Map<Double, String> top = new TreeMap<>(java.util.Comparator.reverseOrder());
        for (Key k : p.keys().values()) {
            if (!WORLD.contains(k.feature()) || !k.kind().equals("frame")) {
                continue;
            }
            us += k.ns() / 1e3 / frames;
            bytes += k.bytes() / frames;
            top.put(k.ns() / 1e3 / frames + top.size() * 1e-9, String.format(Locale.ROOT, "%-70s %7.2f us/frame %7.0f B/frame %6.2f calls/frame",
                    k.key(), k.ns() / 1e3 / frames, k.bytes() / frames, k.calls() / (double) frames));
        }
        int esp = ctx.computeOnClient(mc -> ((Map<?, ?>) Mod.staticCall("mobesp.MobEspFeature", "drawnColors")).size());
        int wp = ctx.computeOnClient(mc -> {
            int n = 0;
            for (String kind : new String[]{"CHEST", "ITEM", "BAT", "WITHER", "LEVER", "CRYPT", "PRINCE"}) {
                n += ((List<?>) Mod.staticCall("secretwaypoints.SecretWaypointsFeature", "cachedPositions", kind)).size();
            }
            return n;
        });
        int arcs = ctx.computeOnClient(mc -> (Integer) Mod.staticCall("trajectories.TrajectoriesFeature", "lastArcCount"));
        List<String> keys = new ArrayList<>(top.values());
        return new Row(label, p, us, bytes, (after[0] - before[0]) / (double) frames,
                (after[1] - before[1]) / (double) frames, (after[2] - before[2]) / (double) frames, esp, wp, arcs,
                after[3] - before[3], keys.subList(0, Math.min(8, keys.size())));
    }

    /** Client thread: {draw calls, batched geometry callbacks, batch flushes, trajectory frames drawn}. */
    private static long[] counters() {
        long[] batch = {0, 0};
        try {
            batch = (long[]) Mod.staticCall("compat.McRender", "batchStats");
        } catch (Throwable ignored) {
            // a jar from before the batch (main 69505e45) has no such hook
        }
        long arcFrames = (Long) Mod.staticCall("trajectories.TrajectoriesFeature", "framesDrawn");
        return new long[]{DrawCalls.count(), batch[0], batch[1], arcFrames};
    }

    /** A phase whose feature drew nothing measured nothing: fail it rather than quote it. */
    private static void check(String set, Row r, List<String> failures) {
        boolean esp = set.equals("ESP") || set.equals("ALL");
        boolean wp = set.equals("WAYPOINTS") || set.equals("ALL");
        boolean traj = set.equals("TRAJ") || set.equals("ALL");
        if (esp && r.espTargets() < MOBS * 3 / 4) {
            failures.add(r.label() + ": Dungeon ESP drew " + r.espTargets() + " starred mob(s) of " + MOBS);
        }
        if (!esp && r.espTargets() > 0) {
            failures.add(r.label() + ": Dungeon ESP is off but drew " + r.espTargets());
        }
        if (wp && r.waypoints() < 3) {
            failures.add(r.label() + ": Secret Waypoints had " + r.waypoints() + " waypoint(s) in " + ROOM);
        }
        if (traj && (r.arcs() != 3 || r.arcFrames() < r.phase().frames() / 2)) {
            failures.add(r.label() + ": Trajectories drew " + r.arcs() + " arc(s) (want the Terminator's 3) in "
                    + r.arcFrames() + " of " + r.phase().frames() + " frames");
        }
    }

    private static double min(List<Row> rows, boolean draws) {
        double m = Double.MAX_VALUE;
        for (Row r : rows) {
            m = Math.min(m, draws ? r.drawsPerFrame() : r.worldUs());
        }
        return m;
    }

    private static double minBytes(List<Row> rows) {
        double m = Double.MAX_VALUE;
        for (Row r : rows) {
            m = Math.min(m, r.worldBytes());
        }
        return m;
    }

    private static void write(List<String> report) {
        try {
            Files.createDirectories(PerfBench.outDir());
            Files.write(PerfBench.outDir().resolve("perf-world.txt"), report);
        } catch (Exception e) {
            report.add("could not write perf-world.txt: " + e);
        }
    }
}
