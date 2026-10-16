package dev.testkit.gametest;

import dev.testkit.compat.McCompat;

import com.google.gson.JsonObject;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 147-sim-map-precompute (mod map-precompute-1015). killer560, 2026-10-15: "Every time a new map is loaded ... have it
 * figure ways to get to other rooms that it can see automatically - it just needs to get something ready within the first
 * 5s so my first warp goes out near instantly. Then as it loads in all rooms it should calculate from the very last node
 * of every auto routes to the start node of every other room's auto routes and every other room".
 *
 * <p>On {@link #FLOORS} generated sim F7s, each with four one-node start routes seeded (as 102 does), the run begun (the
 * entrance gate opening is the first block change of the floor):
 * <ul>
 *   <li><b>phase 1</b>: the mod's own TripCache status - every trip from where he stands to every room planned (or known
 *       to have no graph plan) - within 5 s of the gate opening;</li>
 *   <li><b>press to first warp</b>: the same map press ({@code AutoClearUtils.pathToRoom}) on rooms across the floor from
 *       the same spot, Precompute Trips ON and OFF back to back: client ticks from the press to the first warp's use
 *       packet ({@code ClearExecutor.hopSendTicks}), the warps flown, and that the ON press ran a precomputed trip with no
 *       search; and before each press the planner's own fresh plan from that spot - a precomputed trip must never take
 *       more warps than it;</li>
 *   <li><b>phase 2</b>: every trip from each route's end to every other room's start node (or main tile), planned;</li>
 *   <li><b>re-validation</b>: a block put across a cached trip's first warp with the planner's drop and new searches held
 *       off - the press must drop the cached trip and plan fresh at once, and still arrive; then a doorway the cached trip
 *       goes through is walled up (the trip must be dropped by the block change and planned again round it) and opened
 *       again (planned again through it, never more warps than fresh), and a press then runs it.</li>
 * </ul>
 * On a jar without TripCache it measures the presses (the "before" numbers) and fails for want of the cache. Only when
 * named.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimMapPrecomputeTests implements FabricClientGameTest {

    private static final String NAME = "147-sim-map-precompute";
    private static final int FLOORS = Integer.getInteger("testkit.precomputeFloors", 3);
    private static final int PRESSES = 4;
    /** A precomputed press: the tick after the press puts the sneak down, the next sends the warp. */
    private static final int MAX_CACHED_PRESS_TICKS = 2;
    private static final long PHASE1_MAX_MS = 5000;

    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String LIVE_MAP_CONFIG = "com.killer560.hub.livemap.LiveMapConfig";
    private static final String EXECUTOR = "com.killer560.hub.livemap.autoclear.ClearExecutor";
    private static final String CLEAR_UTILS = "com.killer560.hub.livemap.autoclear.AutoClearUtils";
    private static final String PATHFINDER = "com.killer560.hub.livemap.autoclear.EtherwarpPathfinder";
    private static final String TELEPORT_UTILS = "com.killer560.hub.livemap.autoclear.TeleportUtils";
    private static final String CACHE = "com.killer560.hub.livemap.autoclear.TripCache";
    private static final String AR_CONFIG = "com.killer560.hub.autoroutes.AutoRoutesConfig";
    private static final String AR = "com.killer560.hub.autoroutes.AutoRoutesFeature";

    private static final Pattern RUNNING = Pattern.compile("\\[Path\\] running (\\d+) warp\\(s\\)");

    /** Per press: {ticks ON, ticks OFF, warps ON, warps OFF, fresh warps}. Across floors. */
    private static final List<int[]> PRESS_ROWS = new ArrayList<>();
    private static final List<Long> PHASE1_MS = new ArrayList<>();
    private static final List<Long> PHASE2_MS = new ArrayList<>();
    /** Per floor: {client us a tick average, client us at most, planner ms a second}. */
    private static final List<double[]> COST_ROWS = new ArrayList<>();

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank() || Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (SimMapTests.copyRoomsForOthers() < 20) {
            Scenario.skipped(NAME, "needs real room captures and room database");
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);
        boolean hasCache = ctx.computeOnClient(mc -> {
            try {
                Class.forName(CACHE);
                return true;
            } catch (ClassNotFoundException e) {
                return false;
            }
        });
        println("TripCache in this jar: " + hasCache);
        configure(ctx);
        int renderBefore = ctx.computeOnClient(mc -> mc.options.renderDistance().get());
        ctx.runOnClient(mc -> mc.options.renderDistance().set(16));
        List<String> failures = new ArrayList<>();
        for (int f = 1; f <= FLOORS; f++) {
            List<String> floorFailures = new ArrayList<>();
            try {
                floor(ctx, f, hasCache, floorFailures);
            } catch (Throwable t) {
                floorFailures.add("floor " + f + " threw: " + t);
                t.printStackTrace(System.out);
            } finally {
                setPaused(ctx, hasCache, false, false);
                SimAutoSecretTests.writeRoutes(ctx, Map.of());
                leaveSim(ctx);
            }
            for (String s : floorFailures) {
                println("FAIL floor " + f + ": " + s);
            }
            failures.addAll(floorFailures);
        }
        ctx.runOnClient(mc -> mc.options.renderDistance().set(renderBefore));
        summary(hasCache);
        if (!hasCache) {
            failures.add("this jar has no TripCache: nothing is precomputed (the press numbers above are the before)");
        }
        if (!failures.isEmpty()) {
            throw new AssertionError(NAME + ": " + failures.size() + " problem(s): " + String.join("; ", failures));
        }
        println("PASS");
    }

    // ================================================================================================ one floor

    private static void floor(ClientGameTestContext ctx, int f, boolean hasCache, List<String> failures) {
        enterFloor(ctx);
        // Four one-node start routes, so phase 2 has route ends and start nodes to plan between.
        List<SimAutoSecretTests.Room> rooms = ctx.computeOnClient(SimAutoSecretTests::readRooms);
        Map<String, JsonObject> routes = new LinkedHashMap<>();
        List<SimAutoSecretTests.Room> routed = new ArrayList<>();
        for (SimAutoSecretTests.Room r : rooms) {
            if (routes.size() >= 4) {
                break;
            }
            if (r.name().equals("Unknown") || r.type().equals("PUZZLE") || r.type().equals("TRAP")
                    || r.type().equals("BLOOD") || r.type().equals("ENTRANCE") || !SimAutoSecretTests.pathsOut(r)) {
                continue;
            }
            JsonObject route = SimAutoSecretTests.routeFor(ctx, r);
            if (route != null) {
                routes.put(r.name(), route);
                routed.add(r);
            }
        }
        SimAutoSecretTests.writeRoutes(ctx, routes);
        println("floor " + f + ": routed " + routes.keySet());

        // ---- GO: the gate opens ------------------------------------------------------------------------------------
        if (hasCache) {
            try {
                ctx.runOnClient(mc -> ModUnderTest.staticCall(CACHE, "resetCost"));
            } catch (Throwable ignored) {
                // an older branch jar without the cost counters
            }
        }
        long go = System.currentTimeMillis();
        long goTick = tick(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimRun", "begin",
                new Class<?>[]{Minecraft.class, BlockPos.class},
                new Object[]{mc, ModUnderTest.staticCall("com.killer560.hub.roomsim.SimBuilder", "entranceDoor")}));
        for (int i = 0; i < 400 && !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(
                "com.killer560.hub.roomsim.SimRun", "isRunning")); i++) {
            ctx.waitTicks(1);
        }
        long opened = System.currentTimeMillis();
        println(String.format(Locale.US, "floor %d: the gate opened %d ms (%d ticks) after GO", f, opened - go,
                tick(ctx) - goTick));

        // ---- phase 1 readiness -------------------------------------------------------------------------------------
        if (hasCache) {
            long readyMs = -1;
            int[] st = null;
            for (int i = 0; i < 400; i++) {
                ctx.waitTicks(1);
                st = status(ctx, 1);
                Vec3 o = ctx.computeOnClient(mc -> (Vec3) ModUnderTest.staticCall(CACHE, "origin"));
                if (o != null && st[0] > 0 && st[3] == 0 && st[1] * 2 >= st[0]) {
                    readyMs = System.currentTimeMillis() - opened;
                    break;
                }
            }
            ctx.waitTicks(5);   // the mod notes its own readiness at the end of a tick
            long[] p1 = ctx.computeOnClient(mc -> (long[]) ModUnderTest.staticCall(CACHE, "phase1"));
            println(String.format(Locale.US, "floor %d: phase 1 %s - %d trip(s) wanted, %d planned (%d on the quick graph),"
                            + " %d with no graph plan; the mod's own line: floor known -> ready %d ms", f,
                    readyMs < 0 ? "NOT ready in 20 s" : "ready " + readyMs + " ms after the gate opened", st[0], st[1],
                    st[4], st[2], p1[1] == 0 ? -1 : p1[1] - p1[0]));
            if (readyMs < 0 || readyMs > PHASE1_MAX_MS) {
                failures.add("phase 1 not ready within " + PHASE1_MAX_MS + " ms of the gate opening (" + readyMs + ")");
            } else {
                PHASE1_MS.add(readyMs);
            }
        }

        // ---- presses from the entrance spot: ON and OFF back to back -------------------------------------------------
        for (int i = 0; i < 100 && !ctx.computeOnClient(mc -> mc.player.onGround()); i++) {
            ctx.waitTicks(1);
        }
        Vec3 spot = ctx.computeOnClient(mc -> mc.player.position());
        List<int[]> targets = targets(ctx);
        println("floor " + f + ": " + targets.size() + " room(s) to press from " + fmt(spot));
        int pressed = 0;
        for (int[] t : targets) {
            if (pressed >= PRESSES) {
                break;
            }
            Press on = null;
            if (hasCache) {
                backTo(ctx, spot);
                awaitPhase1(ctx);
                on = press(ctx, t[0], t[1], "ON", failures);
            }
            backTo(ctx, spot);
            if (hasCache) {
                setPrecompute(ctx, false);
            }
            Press off = press(ctx, t[0], t[1], "OFF", failures);
            if (hasCache) {
                setPrecompute(ctx, true);
            }
            if (off == null && on == null) {
                continue;
            }
            pressed++;
            int fresh = on != null ? on.fresh : off.fresh;
            PRESS_ROWS.add(new int[]{on == null ? -1 : on.ticks, off == null ? -1 : off.ticks, on == null ? -1 : on.warps,
                    off == null ? -1 : off.warps, fresh});
            if (on != null) {
                if (!on.cached) {
                    failures.add("tile " + t[0] + ": the ON press searched instead of running a precomputed trip");
                }
                if (on.ticks < 0 || on.ticks > MAX_CACHED_PRESS_TICKS) {
                    failures.add("tile " + t[0] + ": a precomputed press sent its first warp " + on.ticks
                            + " tick(s) after the press (want at most " + MAX_CACHED_PRESS_TICKS + ")");
                }
                if (fresh > 0 && on.warps > fresh) {
                    failures.add("tile " + t[0] + ": the precomputed trip took " + on.warps + " warps where fresh planning"
                            + " takes " + fresh);
                }
                if (!on.arrived) {
                    failures.add("tile " + t[0] + ": the precomputed trip did not get him into the room");
                }
            }
        }
        if (pressed < 2) {
            failures.add("fewer than two rooms could be pressed (" + pressed + ")");
        }
        if (!hasCache) {
            return;
        }

        // ---- phase 2: from every route's end to every other room -----------------------------------------------------
        backTo(ctx, spot);
        long p2From = System.currentTimeMillis();
        int[] s2 = null;
        long p2Ms = -1;
        for (int i = 0; i < 1200; i++) {
            s2 = status(ctx, 2);
            if (s2[0] > 0 && s2[3] == 0) {
                p2Ms = System.currentTimeMillis() - p2From;
                break;
            }
            ctx.waitTicks(1);
        }
        println(String.format(Locale.US, "floor %d: phase 2 %s - %d trip(s) from %d route end(s), %d planned (%d on the quick"
                        + " graph), %d with no graph plan", f, p2Ms < 0 ? "NOT done in 60 s" : "done (" + p2Ms
                        + " ms after the presses)", s2[0], routed.size(), s2[1], s2[4], s2[2]));
        if (p2Ms < 0) {
            failures.add("phase 2 not finished in 60 s");
        } else {
            PHASE2_MS.add(p2Ms);
        }
        if (s2[0] > 0 && s2[1] * 2 < s2[0]) {
            failures.add("phase 2 planned only " + s2[1] + " of " + s2[0] + " trips");
        }
        // Each routed room's end to each other routed room's start node: the lookup Auto Secret's room order makes.
        int pairs = 0;
        int found = 0;
        for (SimAutoSecretTests.Room a : routed) {
            for (SimAutoSecretTests.Room b : routed) {
                if (a == b) {
                    continue;
                }
                pairs++;
                found += ctx.computeOnClient(mc -> routePairCached(a.id(), b.id())) ? 1 : 0;
            }
        }
        println("floor " + f + ": route end -> start node trips cached: " + found + " of " + pairs);
        if (pairs > 0 && found * 2 < pairs) {
            failures.add("only " + found + " of " + pairs + " route-end -> start-node trips are cached");
        }

        // ---- re-validation at use: a block across the first warp, nothing allowed to fix the cache first --------------
        revalidate(ctx, spot, targets, failures);
        // ---- a doorway walled up, then opened: dropped by the change, planned again ------------------------------------
        doorway(ctx, spot, targets, failures);

        try {
            long[] cost = ctx.computeOnClient(mc -> (long[]) ModUnderTest.staticCall(CACHE, "cost"));
            double wall = (System.currentTimeMillis() - go) / 1000.0;
            double avgUs = cost[0] == 0 ? 0 : cost[1] / 1000.0 / cost[0];
            println(String.format(Locale.US, "floor %d: cost - client tick %.1f us average over %d tick(s), %.0f us at most;"
                            + " planner %.0f ms in %.0f s (%.1f ms a second, off the render thread)", f, avgUs, cost[0],
                    cost[2] / 1000.0, cost[3] / 1e6, wall, cost[3] / 1e6 / Math.max(1, wall)));
            COST_ROWS.add(new double[]{avgUs, cost[2] / 1000.0, cost[3] / 1e6 / Math.max(1, wall)});
            if (avgUs > 1000) {
                failures.add(String.format(Locale.US, "the precompute's client tick averaged %.0f us (budget 1000)", avgUs));
            }
        } catch (Throwable e) {
            println("floor " + f + ": cost counters not in this jar");
        }
        int[] st = ctx.computeOnClient(mc -> (int[]) ModUnderTest.staticCall(CACHE, "stats"));
        println(String.format(Locale.US, "floor %d: cache stats - %d hit(s), %d miss(es), %d rejected at use, %d dropped by"
                        + " block changes, %d search(es), %d stored, %d with no plan, %d held, %d room-order cost(s) read",
                f, st[0], st[1], st[2], st[3], st[4], st[5], st[6], st[7], st[8]));
    }

    // ================================================================================================ presses

    private record Press(int ticks, int warps, int fresh, boolean cached, boolean arrived) {
    }

    /** One map press on {@code tile}; ticks from the press to its first warp, warps flown, the fresh plan's warps. */
    private static Press press(ClientGameTestContext ctx, int tile, int room, String label, List<String> failures) {
        for (int i = 0; i < 100 && !ctx.computeOnClient(mc -> mc.player.onGround()); i++) {
            ctx.waitTicks(1);
        }
        Vec3 from = ctx.computeOnClient(mc -> mc.player.position());
        int fresh = freshWarps(ctx, from, tile, room);
        long mark = LogTap.mark();
        long[] pressTick = {-1};
        Boolean ok = ctx.computeOnClient(mc -> {
            pressTick[0] = (Long) ModUnderTest.staticCall(EXECUTOR, "clientTick");
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            return (Boolean) ModUnderTest.staticCall(CLEAR_UTILS, "pathToRoom",
                    new Class<?>[]{layout.getClass(), int.class, int.class, int.class},
                    new Object[]{layout, room, tile, 0});
        });
        if (!Boolean.TRUE.equals(ok)) {
            println("  " + label + " tile " + tile + ": the press was refused");
            return null;
        }
        boolean busySeen = false;
        for (int t = 0; t < 400; t++) {
            ctx.waitTicks(1);
            boolean busy = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(EXECUTOR, "isBusy"));
            busySeen |= busy;
            if (busySeen && !busy) {
                break;
            }
        }
        ctx.waitTicks(5);
        @SuppressWarnings("unchecked")
        List<Long> hops = ctx.computeOnClient(mc -> (List<Long>) ModUnderTest.staticCall(EXECUTOR, "hopSendTicks"));
        long first = -1;
        for (long h : hops) {
            if (h > pressTick[0]) {
                first = h;
                break;
            }
        }
        Vec3 to = ctx.computeOnClient(mc -> mc.player.position());
        int endRoom = ctx.computeOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            return (Integer) ModUnderTest.call(layout, "roomAtWorld", new Class<?>[]{double.class, double.class},
                    new Object[]{to.x, to.z});
        });
        int warps = -1;
        boolean cached = false;
        boolean offPlan = false;
        for (String l : LogTap.since(mark)) {
            Matcher m = RUNNING.matcher(l);
            if (m.find() && warps < 0) {
                warps = Integer.parseInt(m.group(1));
                cached = l.contains("precomputed") && l.contains("no search");
            }
            offPlan |= l.contains("[Path] off the plan");
            if (l.contains("precomputed trip to") || l.contains("[Path] running") || l.contains("off the plan")) {
                println("    " + l.replaceAll("^.*?\\[Path\\]", "[Path]"));
            }
        }
        boolean arrived = endRoom == room && from.distanceTo(to) > 1.0;
        int ticks = first < 0 ? -1 : (int) (first - pressTick[0]);
        println(String.format(Locale.US, "  %s tile %d: first warp %d tick(s) after the press, %d warp(s)%s (fresh plan %d),"
                        + " moved %.1f, %s the room%s", label, tile, ticks, warps, cached ? " precomputed" : " searched",
                fresh, from.distanceTo(to), arrived ? "IN" : "NOT IN", offPlan ? ", went off the plan" : ""));
        if (offPlan) {
            failures.add(label + " tile " + tile + ": the trip went off the plan");
        }
        return new Press(ticks, warps, fresh, cached, arrived);
    }

    /**
     * The planner's own plan for a press on {@code tile} from {@code from}, as a press makes it (999: none): the room's
     * recorded spot when it has one (AutoClearUtils.overrideGoal, a block trip), else the whole tile.
     */
    private static int freshWarps(ClientGameTestContext ctx, Vec3 from, int tile, int room) {
        AtomicReference<Integer> out = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            Object cfg = ModUnderTest.staticCall(EXECUTOR, "pathConfig");
            double range = (Double) ModUnderTest.staticCall(EXECUTOR, "hopRange");
            BlockPos override = null;
            try {
                override = (BlockPos) ModUnderTest.staticCall(CLEAR_UTILS, "overrideGoal",
                        new Class<?>[]{layout.getClass(), int.class, int.class, boolean.class},
                        new Object[]{layout, room, tile, true});
            } catch (Throwable e) {
                override = null;   // a jar without it (main): every press here is a tile press then
            }
            BlockPos centre = (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class},
                    new Object[]{tile});
            BlockPos to = override != null ? override : (BlockPos) ModUnderTest.staticCall(TELEPORT_UTILS,
                    "etherwarpableInTile", new Class<?>[]{BlockPos.class, Vec3.class}, new Object[]{centre, from});
            if (to == null) {
                out.set(999);
                return;
            }
            boolean block = override != null;
            Runnable job = () -> {
                try {
                    Object path = block ? ModUnderTest.staticCall(PATHFINDER, "findDungeonPath",
                            new Class<?>[]{Vec3.class, BlockPos.class, cfg.getClass(), double.class, layout.getClass(),
                                    boolean.class}, new Object[]{from, to, cfg, range, layout, false})
                            : ModUnderTest.staticCall(PATHFINDER, "findDungeonPathToTile",
                            new Class<?>[]{Vec3.class, BlockPos.class, int.class, cfg.getClass(), double.class,
                                    layout.getClass()}, new Object[]{from, to, tile, cfg, range, layout});
                    out.set(path == null ? 999 : ((List<?>) path).size());
                } catch (RuntimeException e) {
                    out.set(999);
                }
            };
            ModUnderTest.staticCall(EXECUTOR, "onPlanner", new Class<?>[]{Runnable.class}, new Object[]{job});
        });
        ctx.waitFor(mc -> out.get() != null, 600);
        return out.get();
    }

    /** Room tiles across the floor to press, spread out: {tile, room}. Not his room, no trap, maze or Boulder. */
    private static List<int[]> targets(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            int here = (Integer) ModUnderTest.call(layout, "currentRoom", new Class<?>[]{}, new Object[]{});
            List<int[]> all = new ArrayList<>();
            java.util.Set<Integer> seen = new java.util.HashSet<>();
            for (int gz = 0; gz < 11; gz += 2) {
                for (int gx = 0; gx < 11; gx += 2) {
                    int idx = gz * 11 + gx;
                    int room = (Integer) ModUnderTest.call(layout, "roomOfCell", new Class<?>[]{int.class},
                            new Object[]{idx});
                    if (room < 0 || room == here || !seen.add(room)) {
                        continue;
                    }
                    Object nm = ModUnderTest.call(layout, "name", new Class<?>[]{int.class}, new Object[]{room});
                    String n = nm == null ? "" : nm.toString();
                    if (n.contains("Trap") || n.contains("Maze") || n.contains("Boulder")) {
                        continue;
                    }
                    // Not behind a locked door: the map's path from here exists.
                    if (ModUnderTest.staticCall("com.killer560.hub.livemap.autoclear.DungeonMapPathfinder", "findPath",
                            new Class<?>[]{layout.getClass(), int.class, int.class, boolean.class},
                            new Object[]{layout, here, room, false}) == null) {
                        continue;
                    }
                    all.add(new int[]{idx, room});
                }
            }
            List<int[]> out = new ArrayList<>();
            int step = Math.max(1, all.size() / (PRESSES + 2));
            for (int i = 0; i < all.size(); i += step) {
                out.add(all.get(i));
            }
            return out;
        });
    }

    // ================================================================================================ re-validation

    /**
     * A cached trip from {@code spot}; a 3x3x3 stone block put on its first warp's ray halfway, with the cache's block-change
     * drop and its new searches held off (TripCache.testNoCorridorDrop / testPaused); the press must drop the trip at use
     * and plan fresh, and get there.
     */
    private static void revalidate(ClientGameTestContext ctx, Vec3 spot, List<int[]> targets, List<String> failures) {
        backTo(ctx, spot);
        awaitPhase1(ctx);
        for (int[] t : targets) {
            double[] corridor = corridorOf(ctx, spot, t[0]);
            if (corridor == null) {
                continue;
            }
            // Halfway along the first hop's ray, where it is air (that is why the ray passes).
            BlockPos mid = BlockPos.containing((corridor[0] + corridor[3]) / 2, (corridor[1] + corridor[4]) / 2,
                    (corridor[2] + corridor[5]) / 2);
            setPaused(ctx, true, true, true);
            Map<BlockPos, BlockState> was = fill(ctx, mid.offset(-1, -1, -1), mid.offset(1, 1, 1),
                    net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            int[] before = ctx.computeOnClient(mc -> (int[]) ModUnderTest.staticCall(CACHE, "stats"));
            long mark = LogTap.mark();
            Press p = press(ctx, t[0], t[1], "BLOCKED", failures);
            int[] after = ctx.computeOnClient(mc -> (int[]) ModUnderTest.staticCall(CACHE, "stats"));
            restore(ctx, was);
            setPaused(ctx, true, false, false);
            boolean dropped = LogTap.since(mark).stream().anyMatch(l -> l.contains("precomputed trip to")
                    && l.contains("dropped"));
            println(String.format(Locale.US, "  re-validation: stone across the first warp at %s; rejected at use %d -> %d;"
                            + " the press %s; %s", mid.toShortString(), before[2], after[2],
                    p == null ? "was refused" : p.cached ? "RAN THE STALE TRIP" : "planned fresh (" + p.warps + " warps)",
                    p != null && p.arrived ? "he got there" : "he did NOT get there"));
            if (p == null || p.cached || after[2] <= before[2] || !dropped) {
                failures.add("re-validation: the cached trip across the new block was not dropped at use");
            } else if (!p.arrived) {
                failures.add("re-validation: after dropping the cached trip the fresh plan did not get him there");
            }
            return;
        }
        failures.add("re-validation: no cached trip from the entrance spot to test with");
    }

    /**
     * A cached trip from {@code spot} that passes a doorway: the doorway walled up (the trip must be dropped by the change
     * and planned again round it, or found to have no way), then opened (planned again, never more warps than fresh), then
     * pressed (runs the new trip).
     */
    private static void doorway(ClientGameTestContext ctx, Vec3 spot, List<int[]> targets, List<String> failures) {
        backTo(ctx, spot);
        awaitPhase1(ctx);
        for (int[] t : targets) {
            double[] corridor = corridorOf(ctx, spot, t[0]);
            if (corridor == null) {
                continue;
            }
            BlockPos door = doorOn(ctx, corridor);
            if (door == null) {
                continue;
            }
            int warps0 = cachedWarps(ctx, spot, t[0]);
            int[] s0 = ctx.computeOnClient(mc -> (int[]) ModUnderTest.staticCall(CACHE, "stats"));
            Map<BlockPos, BlockState> was = fill(ctx, door.offset(-2, 0, -2), door.offset(2, 4, 2),
                    net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            // The change reaches the planner with its next search or warm-up slice; the trip must go.
            boolean gone = false;
            for (int i = 0; i < 100 && !gone; i++) {
                ctx.waitTicks(1);
                int[] s = ctx.computeOnClient(mc -> (int[]) ModUnderTest.staticCall(CACHE, "stats"));
                gone = s[3] > s0[3];
            }
            awaitPhase1(ctx);
            int closed = cachedWarps(ctx, spot, t[0]);
            int freshClosed = freshWarps(ctx, spot, t[0], t[1]);
            restore(ctx, was);
            boolean replanned = false;
            int open = -1;
            for (int i = 0; i < 200 && !replanned; i++) {
                ctx.waitTicks(1);
                open = cachedWarps(ctx, spot, t[0]);
                Boolean stale = ctx.computeOnClient(mc -> staleOf(spot, t[0]));
                replanned = open > 0 && Boolean.FALSE.equals(stale);
            }
            int freshOpen = freshWarps(ctx, spot, t[0], t[1]);
            println(String.format(Locale.US, "  doorway at %s walled up: dropped by the change %s; cached %d -> %s warp(s)"
                            + " (fresh %s); opened: cached %d warp(s), fresh %d", door.toShortString(), gone,
                    warps0, closed < 0 ? "none" : String.valueOf(closed), freshClosed == 999 ? "none"
                            : String.valueOf(freshClosed), open, freshOpen));
            if (!gone) {
                failures.add("doorway: walling it up did not drop the cached trip through it");
            }
            if (closed > 0 && freshClosed < 999 && closed > freshClosed) {
                failures.add("doorway: walled up, the cached trip takes " + closed + " warps, fresh " + freshClosed);
            }
            if (!replanned) {
                failures.add("doorway: after it opened the trip was not planned again");
            } else if (open > freshOpen) {
                failures.add("doorway: opened, the cached trip takes " + open + " warps, fresh " + freshOpen);
            }
            Press p = press(ctx, t[0], t[1], "REOPENED", failures);
            if (p == null || !p.cached || !p.arrived) {
                failures.add("doorway: the press after it opened did not run the trip planned again into the room");
            }
            return;
        }
        println("  doorway: no cached trip from the entrance spot passes a doorway on this floor - not tested");
    }

    /** The door cell centre (its lock block) within 2.5 blocks of any hop of the corridor, or null. */
    private static BlockPos doorOn(ClientGameTestContext ctx, double[] c) {
        return ctx.computeOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            for (int idx = 0; idx < 121; idx++) {
                boolean isDoor = (Boolean) ModUnderTest.call(layout, "isDoor", new Class<?>[]{int.class},
                        new Object[]{idx});
                if (!isDoor) {
                    continue;
                }
                Vec3 d = (Vec3) ModUnderTest.staticCall(LAYOUT, "doorCentre", new Class<?>[]{int.class}, new Object[]{idx});
                for (int i = 0; i + 5 < c.length; i += 6) {
                    Vec3 a = new Vec3(c[i], c[i + 1], c[i + 2]);
                    Vec3 b = new Vec3(c[i + 3], c[i + 4], c[i + 5]);
                    if (pointToSegment(d, a, b) <= 2.5) {
                        return (BlockPos) ModUnderTest.staticCall(LAYOUT, "doorBlock", new Class<?>[]{int.class},
                                new Object[]{idx});
                    }
                }
            }
            return null;
        });
    }

    private static double pointToSegment(Vec3 p, Vec3 a, Vec3 b) {
        Vec3 ab = b.subtract(a);
        double len = ab.lengthSqr();
        double t = len < 1e-9 ? 0 : Math.max(0, Math.min(1, p.subtract(a).dot(ab) / len));
        return p.distanceTo(a.add(ab.scale(t)));
    }

    // ================================================================================================ cache reads

    private static int[] status(ClientGameTestContext ctx, int phase) {
        return ctx.computeOnClient(mc -> (int[]) ModUnderTest.staticCall(CACHE, "phaseStatus", new Class<?>[]{int.class},
                new Object[]{phase}));
    }

    /** Waits (at most 5 s) for phase 1 from where he stands to have nothing missing. */
    private static void awaitPhase1(ClientGameTestContext ctx) {
        Vec3 at = ctx.computeOnClient(mc -> mc.player.position());
        for (int i = 0; i < 100; i++) {
            Vec3 o = ctx.computeOnClient(mc -> (Vec3) ModUnderTest.staticCall(CACHE, "origin"));
            int[] s = status(ctx, 1);
            if (o != null && o.distanceTo(at) < 0.3 && s[0] > 0 && s[3] == 0) {
                return;
            }
            ctx.waitTicks(1);
        }
        println("  (phase 1 from " + fmt(at) + " still had trips missing after 5 s)");
    }

    private static Object entryOf(Vec3 at, int tile) {
        BlockPos centre = (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class},
                new Object[]{tile});
        return ModUnderTest.staticCall(CACHE, "find", new Class<?>[]{Vec3.class, BlockPos.class, int.class, boolean.class},
                new Object[]{at, centre, tile, false});
    }

    private static double[] corridorOf(ClientGameTestContext ctx, Vec3 at, int tile) {
        return ctx.computeOnClient(mc -> {
            Object e = entryOf(at, tile);
            return e == null ? null : (double[]) ModUnderTest.call(e, "corridor", new Class<?>[]{}, new Object[]{});
        });
    }

    private static int cachedWarps(ClientGameTestContext ctx, Vec3 at, int tile) {
        return ctx.computeOnClient(mc -> {
            Object e = entryOf(at, tile);
            return e == null ? -1 : (Integer) ModUnderTest.call(e, "warps", new Class<?>[]{}, new Object[]{});
        });
    }

    private static Boolean staleOf(Vec3 at, int tile) {
        Object e = entryOf(at, tile);
        return e == null ? null : (Boolean) ModUnderTest.call(e, "stale", new Class<?>[]{}, new Object[]{});
    }

    /** Whether the trip from room a's route end to room b's start node is cached. Client thread. */
    private static boolean routePairCached(int a, int b) {
        Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
        Object ra = ModUnderTest.staticCall(AR, "roomRoute", new Class<?>[]{layout.getClass(), int.class},
                new Object[]{layout, a});
        Object rb = ModUnderTest.staticCall(AR, "roomRoute", new Class<?>[]{layout.getClass(), int.class},
                new Object[]{layout, b});
        if (ra == null || rb == null) {
            return false;
        }
        Vec3 end = (Vec3) ModUnderTest.call(ra, "predictedEnd", new Class<?>[]{}, new Object[]{});
        BlockPos goal = (BlockPos) ModUnderTest.call(rb, "goal", new Class<?>[]{}, new Object[]{});
        if (end == null || goal == null) {
            return false;
        }
        return ModUnderTest.staticCall(CACHE, "find", new Class<?>[]{Vec3.class, BlockPos.class, int.class, boolean.class},
                new Object[]{end, goal, -1, true}) != null;
    }

    private static void setPaused(ClientGameTestContext ctx, boolean hasCache, boolean paused, boolean noDrop) {
        if (!hasCache) {
            return;
        }
        ctx.runOnClient(mc -> {
            try {
                Class<?> c = Class.forName(CACHE);
                c.getField("testPaused").setBoolean(null, paused);
                c.getField("testNoCorridorDrop").setBoolean(null, noDrop);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static void setPrecompute(ClientGameTestContext ctx, boolean on) {
        ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(LIVE_MAP_CONFIG), "setPrecomputeTrips", on));
        ctx.waitTicks(1);
    }

    // ================================================================================================ world

    /** Fills a box on the server; returns what was there. Waits until the client has the last block. */
    private static Map<BlockPos, BlockState> fill(ClientGameTestContext ctx, BlockPos a, BlockPos b, BlockState state) {
        AtomicReference<Map<BlockPos, BlockState>> out = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(uuid);
                Map<BlockPos, BlockState> was = new HashMap<>();
                if (sp != null) {
                    var level = (net.minecraft.server.level.ServerLevel) sp.level();
                    for (BlockPos p : BlockPos.betweenClosed(a, b)) {
                        BlockPos q = p.immutable();
                        was.put(q, level.getBlockState(q));
                        level.setBlockAndUpdate(q, state);
                    }
                }
                out.set(was);
            });
        });
        ctx.waitFor(mc -> out.get() != null, 200);
        for (int i = 0; i < 100 && !ctx.computeOnClient(mc -> mc.level.getBlockState(b).is(state.getBlock())); i++) {
            ctx.waitTicks(1);
        }
        ctx.waitTicks(2);
        return out.get();
    }

    private static void restore(ClientGameTestContext ctx, Map<BlockPos, BlockState> was) {
        AtomicReference<Boolean> done = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(uuid);
                if (sp != null) {
                    var level = (net.minecraft.server.level.ServerLevel) sp.level();
                    was.forEach(level::setBlockAndUpdate);
                }
                done.set(true);
            });
        });
        ctx.waitFor(mc -> done.get() != null, 200);
        ctx.waitTicks(4);
    }

    /** Server teleport back to {@code to}, on the ground and still. */
    private static void backTo(ClientGameTestContext ctx, Vec3 to) {
        AtomicReference<Boolean> done = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(uuid);
                if (sp != null) {
                    sp.teleportTo(to.x, to.y, to.z);
                }
                done.set(true);
            });
        });
        ctx.waitFor(mc -> done.get() != null, 200);
        for (int i = 0; i < 100 && ctx.computeOnClient(mc -> mc.player.position().distanceTo(to) > 0.2
                || !mc.player.onGround()); i++) {
            ctx.waitTicks(1);
        }
        ctx.waitTicks(10);
    }

    private static void configure(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            Object auto = ModUnderTest.config("com.killer560.hub.autopuzzles.AutoPuzzlesConfig");
            ModUnderTest.set(auto, "setAutoPuzzlesMasterEnabled", false);
            Object map = ModUnderTest.config(LIVE_MAP_CONFIG);
            ModUnderTest.set(map, "setEnabled", true);
            ModUnderTest.set(map, "setInteractiveMapEnabled", true);
            Object ar = ModUnderTest.config(AR_CONFIG);
            ModUnderTest.set(ar, "setEnabled", true);
            ModUnderTest.set(ar, "setLegitMode", false);
            ModUnderTest.set(ar, "setStartFromStartNodeOnly", true);
        });
    }

    /** A generated sim F7 (as 102), its chunks on the client, an AOTV in hand - the run NOT begun. */
    private static void enterFloor(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter", new Class<?>[]{String.class},
                new Object[]{"gametest"}));
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> {
            Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
            ModUnderTest.staticCall(FLOOR_GEN, "generate",
                    new Class<?>[]{Minecraft.class, floor.getClass(), int.class, int.class},
                    new Object[]{mc, floor, 3, 4});
        }));
        ctx.waitFor(mc -> mc.level != null);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitTicks(80);
        SimAutoSecretTests.awaitFloorChunks(ctx);
        SimAutoSecretTests.giveAotv(ctx);
        for (int i = 0; i < 100 && !ctx.computeOnClient(mc -> mc.player.onGround()); i++) {
            ctx.waitTicks(1);
        }
    }

    private static void leaveSim(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "leave"));
        ctx.runOnClient(mc -> mc.execute(() -> {
            if (mc.level != null) {
                mc.level.disconnect(net.minecraft.network.chat.Component.literal("scenario over"));
                mc.disconnectWithSavingScreen();
            }
        }));
        ctx.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null);
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> mc.execute(() ->
                McCompat.setScreen(mc, new net.minecraft.client.gui.screens.TitleScreen())));
        ctx.waitFor(mc -> McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.TitleScreen);
    }

    private static long tick(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> (Long) ModUnderTest.staticCall(EXECUTOR, "clientTick"));
    }

    // ================================================================================================ report

    private static void summary(boolean hasCache) {
        double onT = 0;
        double offT = 0;
        int nOn = 0;
        int nOff = 0;
        int costlier = 0;
        StringBuilder rows = new StringBuilder();
        for (int[] r : PRESS_ROWS) {
            if (r[0] >= 0) {
                onT += r[0];
                nOn++;
            }
            if (r[1] >= 0) {
                offT += r[1];
                nOff++;
            }
            if (r[2] > 0 && r[4] > 0 && r[4] < 999 && r[2] > r[4]) {
                costlier++;
            }
            rows.append(String.format(Locale.US, " [on %d t / %d w, off %d t / %d w, fresh %d w]", r[0], r[2], r[1], r[3],
                    r[4]));
        }
        println(String.format(Locale.US, "SUMMARY presses %d: press -> first warp mean %s ticks precomputed, %s ticks"
                        + " searched; precomputed trips costlier than fresh: %d;%s", PRESS_ROWS.size(),
                nOn == 0 ? "-" : String.format(Locale.US, "%.2f", onT / nOn),
                nOff == 0 ? "-" : String.format(Locale.US, "%.2f", offT / nOff), costlier, rows));
        StringBuilder cost = new StringBuilder();
        for (double[] c : COST_ROWS) {
            cost.append(String.format(Locale.US, " [%.1f us avg, %.0f us max, %.1f planner ms/s]", c[0], c[1], c[2]));
        }
        println("SUMMARY cost per floor:" + cost);
        println("SUMMARY phase 1 ready (ms after the gate opened): " + PHASE1_MS + "; phase 2 done (ms): " + PHASE2_MS
                + (hasCache ? "" : " (no TripCache)"));
    }

    private static String fmt(Vec3 v) {
        return String.format(Locale.US, "(%.2f, %.2f, %.2f)", v.x, v.y, v.z);
    }

    private static void println(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}
