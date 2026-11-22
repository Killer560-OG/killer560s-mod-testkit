package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 1122-sim-tour-replan (mod acwaits4-1122): his 11-21 log had every server-correction replan of a map trip queued 300-900
 * ms on the planner thread behind Autopilot's room-order search (RoomTour), whose single searches went on to the slow
 * fallbacks (the fine room search, 200 ms; room by room, up to 670 ms). Here a room order is asked for from his spot
 * the moment a map trip's first warp goes, with a site sealed in a stone box first (no landing reaches it, so a full
 * search runs the 200 ms fine room search), and the server puts him back (a correction). The replan must be queued at
 * most {@link #MAX_QUEUED_MS} ms, in each of {@link #TRIALS} trials. Fails on acwaits3-1122 645a32c6a (queued ~50-200 ms
 * behind the sealed site's fine search).
 */
public class SimTourReplan1122Tests implements FabricClientGameTest {

    private static final String NAME = "1122-sim-tour-replan";
    private static final String EXECUTOR = "com.killer560.hub.livemap.autoclear.ClearExecutor";
    private static final String PATHFINDER = "com.killer560.hub.livemap.autoclear.EtherwarpPathfinder";
    private static final String CACHE = "com.killer560.hub.livemap.autoclear.TripCache";
    private static final String TOUR = "com.killer560.hub.autosecret.RoomTour";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String CLEAR_UTILS = "com.killer560.hub.livemap.autoclear.AutoClearUtils";
    private static final String TELEPORT_UTILS = "com.killer560.hub.livemap.autoclear.TeleportUtils";
    private static final int TRIALS = 3;
    private static final double MAX_QUEUED_MS = 40;
    private static final Pattern QUEUED = Pattern.compile("\\[Path\\] running (\\d+) warp\\(s\\).*?([0-9.]+) ms from the press"
            + " to the first warp's turn \\(queued ([0-9.]+) ms, searched (\\d+) ms\\)");

    private static final Pattern TOUR_DONE = Pattern.compile("room order: searched (\\d+) trip\\(s\\).*\\(" + NAME + "\\)");

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank() || Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(40);
        if (SimMapTests.copyRoomsForOthers() < 20) {
            Scenario.skipped(NAME, "needs real room captures and room database");
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.RoomLibrary", "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.roomsim.RoomLibrary", "isReady"), 2400);
        SimMapPrecomputeTests.configure(ctx);
        List<String> failures = new ArrayList<>();
        Map<BlockPos, BlockState> was = new HashMap<>();
        try {
            SimMapPrecomputeTests.enterFloor(ctx);
            ctx.runOnClient(mc -> dev.testkit.gametest.mod.Mod.setField(CACHE, "testDisabled", true));
            ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimRun", "begin",
                    new Class<?>[]{Minecraft.class, BlockPos.class},
                    new Object[]{mc, ModUnderTest.staticCall("com.killer560.hub.roomsim.SimBuilder", "entranceDoor")}));
            for (int i = 0; i < 400 && !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(
                    "com.killer560.hub.roomsim.SimRun", "isRunning")); i++) {
                ctx.waitTicks(1);
            }
            for (int i = 0; i < 100 && !ctx.computeOnClient(mc -> mc.player.onGround()); i++) {
                ctx.waitTicks(1);
            }
            Vec3 spot = ctx.computeOnClient(mc -> mc.player.position());
            List<int[]> targets = SimMapPrecomputeTests.targets(ctx);
            if (targets.size() < 3) {
                failures.add("fewer than three rooms to use (" + targets.size() + ")");
                return;
            }
            int[] press = targets.get(0);
            int[] sealed = targets.get(targets.size() - 1);
            BlockPos goal = ctx.computeOnClient(mc -> (BlockPos) ModUnderTest.staticCall(TELEPORT_UTILS,
                    "etherwarpableInTile", new Class<?>[]{BlockPos.class, Vec3.class}, new Object[]{
                            ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class},
                                    new Object[]{sealed[0]}), spot}));
            if (goal == null) {
                failures.add("no floor block in tile " + sealed[0]);
                return;
            }
            // A stone box around the sealed site's block: its roof 8 up, its walls 6 out, so no landing is within the
            // planner's "near" five blocks of it and none can aim inside.
            was.putAll(SimMapPrecomputeTests.fill(ctx, goal.offset(-6, 0, -6), goal.offset(6, 8, 6),
                    Blocks.STONE.defaultBlockState()));
            SimMapPrecomputeTests.fill(ctx, goal.offset(-5, 1, -5), goal.offset(5, 7, 5), Blocks.AIR.defaultBlockState());
            for (int i = 0; i < 1200 && !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(PATHFINDER,
                    "graphWarm")); i++) {
                ctx.waitTicks(1);
            }
            println("press tile " + press[0] + ", sealed site " + goal.toShortString() + " (tile " + sealed[0] + "), graph warm "
                    + ctx.computeOnClient(mc -> ModUnderTest.staticCall(PATHFINDER, "graphWarm")));
            int measured = 0;
            for (int trial = 1; trial <= TRIALS; trial++) {
                Double q = trial(ctx, trial, spot, press, goal, targets, failures);
                if (q != null) {
                    measured++;
                    if (q > MAX_QUEUED_MS) {
                        failures.add(String.format(Locale.US, "trial %d: the correction's replan was queued %.1f ms behind"
                                + " the room order (want at most %.0f)", trial, q, MAX_QUEUED_MS));
                    }
                }
            }
            if (measured < TRIALS) {
                failures.add("only " + measured + " of " + TRIALS + " trials had a correction and a replan");
            }
        } catch (Throwable t) {
            failures.add("threw: " + t);
            t.printStackTrace(System.out);
        } finally {
            try {
                ctx.runOnClient(mc -> ModUnderTest.staticCall(EXECUTOR, "cancel"));
                if (!was.isEmpty()) {
                    SimMapPrecomputeTests.restore(ctx, was);
                }
                ctx.runOnClient(mc -> dev.testkit.gametest.mod.Mod.setField(CACHE, "testDisabled", false));
            } catch (Throwable ignored) {
                // leaving the floor follows
            }
            SimMapPrecomputeTests.leaveSim(ctx);
        }
        for (String f : failures) {
            println("FAIL " + f);
        }
        if (!failures.isEmpty()) {
            throw new AssertionError(NAME + ": " + failures.size() + " problem(s): " + String.join("; ", failures));
        }
        println("PASS");
    }

    /** One press, a room order and a server correction at its first warp; the replan's queued ms, or null. */
    private static Double trial(ClientGameTestContext ctx, int trial, Vec3 spot, int[] press, BlockPos sealed,
                                List<int[]> targets, List<String> failures) {
        SimMapPrecomputeTests.backTo(ctx, spot);
        for (int i = 0; i < 200 && ctx.computeOnClient(mc -> (Boolean) dev.testkit.gametest.mod.Mod.field(TOUR, "busy")); i++) {
            ctx.waitTicks(1);
        }
        ctx.runOnClient(mc -> ((Map<?, ?>) dev.testkit.gametest.mod.Mod.field(TOUR, "COST")).clear());
        long mark = LogTap.mark();
        long pressTick = ctx.computeOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            ModUnderTest.staticCall(CLEAR_UTILS, "pathToRoom", new Class<?>[]{layout.getClass(), int.class, int.class,
                    int.class}, new Object[]{layout, press[1], press[0], 0});
            return (Long) ModUnderTest.staticCall(EXECUTOR, "clientTick");
        });
        boolean sent = false;
        for (int i = 0; i < 60 && !sent; i++) {
            ctx.waitTicks(1);
            @SuppressWarnings("unchecked")
            List<Long> hops = ctx.computeOnClient(mc -> (List<Long>) ModUnderTest.staticCall(EXECUTOR, "hopSendTicks"));
            sent = hops.stream().anyMatch(h -> h > pressTick);
        }
        if (!sent) {
            failures.add("trial " + trial + ": the press sent no warp");
            return null;
        }
        // The room order from here, the sealed site first, and the server's teleport back - in one client step.
        AtomicReference<Boolean> tp = new AtomicReference<>();
        long tpTick = ctx.computeOnClient(mc -> {
            ModUnderTest.staticCall(TOUR, "request", new Class<?>[]{Vec3.class, List.class, boolean.class, boolean.class,
                    String.class}, new Object[]{mc.player.position(), sites(sealed, targets), false, true, NAME});
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(uuid);
                if (sp != null) {
                    sp.teleportTo(spot.x, spot.y, spot.z);
                }
                tp.set(true);
            });
            return (Long) ModUnderTest.staticCall(EXECUTOR, "clientTick");
        });
        Double queued = null;
        boolean corrected = false;
        for (int i = 0; i < 200 && queued == null; i++) {
            ctx.waitTicks(1);
            for (String l : LogTap.since(mark)) {
                corrected |= l.contains("[Correction] Interactive Map") || l.contains("server moved you");
                Matcher m = QUEUED.matcher(l);
                if (corrected && m.find()) {
                    queued = Double.parseDouble(m.group(3));
                    long now = ctx.computeOnClient(mc -> (Long) ModUnderTest.staticCall(EXECUTOR, "clientTick"));
                    println(String.format(Locale.US, "trial %d: replan %s warp(s), queued %s ms, searched %s ms, %s ms from"
                                    + " the replan's start to its first warp's turn, %d tick(s) after the teleport", trial,
                            m.group(1), m.group(3), m.group(4), m.group(2), now - tpTick));
                    break;
                }
            }
        }
        // The room order must have searched (the planner work the replan competes with), not only estimated.
        int searched = -1;
        for (int i = 0; i < 100 && searched < 0; i++) {
            for (String l : LogTap.since(mark)) {
                Matcher m = TOUR_DONE.matcher(l);
                if (m.find()) {
                    searched = Integer.parseInt(m.group(1));
                    println("trial " + trial + ": " + l.replaceAll("^.*?\\[AutoSecret\\]", "[AutoSecret]"));
                    break;
                }
            }
            if (searched < 0) {
                ctx.waitTicks(1);
            }
        }
        if (searched < 1) {
            failures.add("trial " + trial + ": the room order searched no trip (" + searched + ")");
        }
        if (!corrected) {
            failures.add("trial " + trial + ": the teleport back was not taken as a correction");
        } else if (queued == null) {
            failures.add("trial " + trial + ": no replan after the correction");
        }
        ctx.runOnClient(mc -> ModUnderTest.staticCall(EXECUTOR, "cancel"));
        ctx.waitTicks(10);
        return queued;
    }

    /** RoomTour.Site rows: the sealed block first, then a few room tiles (each leaving at its own block). */
    private static List<Object> sites(BlockPos sealed, List<int[]> targets) {
        try {
            Class<?> site = Class.forName(TOUR + "$Site");
            Constructor<?> c = site.getDeclaredConstructors()[0];
            c.setAccessible(true);
            List<Object> out = new ArrayList<>();
            out.add(c.newInstance("Sealed", sealed, -1, false, Vec3.atBottomCenterOf(sealed.above()), 5.0, 100.0));
            for (int i = 1; i < Math.min(targets.size() - 1, 5); i++) {
                int[] t = targets.get(i);
                BlockPos b = (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class},
                        new Object[]{t[0]});
                out.add(c.newInstance("Room" + t[1], b, t[0], false, Vec3.atBottomCenterOf(b.above()), 3.0, 100.0));
            }
            return out;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("RoomTour.Site: " + e, e);
        }
    }

    private static void println(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}
