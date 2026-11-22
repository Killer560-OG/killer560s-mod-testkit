package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 1122-sim-trapstart (acrev-trap-1122): New Trap's bundled Just Cleared route, entered three times in one sim floor.
 *
 * <p>killer560 (2026-11-22, his live clears): "look at how slow trap is to start". Two causes in his log:
 * <ol>
 *   <li>The walk-wait guard Dungeons Autopilot uses (AutoSecretFeature.walkWaiting: RouteExecutor.awaitingWalk, on the
 *       ground, standing still, 10 ticks) gave the route up 10 ticks into its #3 pearl's flight, before breaker #4 it
 *       reaches by the pearl. Each run here plays with that guard's own condition watched every tick: it must never hold
 *       10 ticks in a row while the route runs (FAILS on a jar whose awaitingWalk ignores a pearl in flight), and the run
 *       must reach its last node with the pearl wait over 10 ticks (else this case proves nothing).</li>
 *   <li>A fresh trap-walk plan from a spot 0.62 off the start replaced the bundled saved walk, and every later start from
 *       the real spot planned 4.1-4.2 s standing still. Entry 1 is from the bundled start spot, entry 2 from 0.64 off it
 *       (a fresh plan, saved on a jar that overwrites), entry 3 from the start spot again: entries 1 and 3 must start
 *       walking on the saved walk within {@link #START_BOUND} ticks of node #1, and node #1's saved walk must be the
 *       bundled one after all three (entry 3 FAILS on a jar that overwrites it).</li>
 * </ol>
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimTrapStart1122Tests implements FabricClientGameTest {

    private static final String NAME = "1122-sim-trapstart";
    private static final String ROUTE_EXEC = "com.killer560.hub.autoroutes.RouteExecutor";
    private static final String ROUTE_COORDS = "com.killer560.hub.autoroutes.RouteCoords";
    private static final String AUTO_TRAP = "com.killer560.hub.autotrap.AutoTrap";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String TRAP = "New Trap";
    /** AutoSecretFeature.WALK_WAIT_TICKS. */
    private static final int GUARD_TICKS = 10;
    /** Ticks from node #1's start to the first walk step on a saved walk. */
    private static final int START_BOUND = 3;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        if (Scenario.legitSkip(NAME)) {
            return;
        }
        Integer renderBefore = SimTrapRoutes1121Tests.setUp(ctx, NAME);
        if (renderBefore == null) {
            return;
        }
        List<String> failures = new ArrayList<>();
        try {
            SimTrapRoutes1121Tests.oneFloor(ctx, failures, TRAP, 0, SimTrapStart1122Tests::body);
        } catch (Throwable t) {
            failures.add("scenario threw: " + t);
            t.printStackTrace(System.out);
        } finally {
            SimTrapRoutes1121Tests.tearDown(ctx, renderBefore);
        }
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " problem(s): " + String.join("; ", failures));
        }
        System.out.println("[" + NAME + "] PASS");
    }

    private static void body(ClientGameTestContext ctx, List<String> failures, String trap, int rot, String tag,
                             String north) {
        SimTrapRoutes1121Tests.standOnTile(ctx, SimTrapRoutes1121Tests.NX, SimTrapRoutes1121Tests.NZ, 0, 0);
        for (int i = 0; i < 300 && ctx.computeOnClient(mc -> SimTrapRoutes1121Tests.roomNamed(
                ModUnderTest.staticCall(LAYOUT, "capture"), trap)) < 0; i++) {
            ctx.waitTicks(1);
        }
        Object[] info = ctx.computeOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            int room = SimTrapRoutes1121Tests.roomNamed(layout, trap);
            Object route = ModUnderTest.staticCall(AUTO_TRAP, "routeFor", new Class<?>[]{String.class}, new Object[]{trap});
            int[] cr = room < 0 ? null
                    : (int[]) ModUnderTest.call(layout, "clayRotation", new Class<?>[]{int.class}, new Object[]{room});
            if (route == null || cr == null) {
                return null;
            }
            Object frame = SimTrapRoutes1121Tests.newFrame(trap, cr);
            List<?> nodes = (List<?>) ModUnderTest.call(route, "nodes", new Class<?>[]{}, new Object[]{});
            Vec3 rel = (Vec3) ModUnderTest.call(nodes.get(0), "relativePos", new Class<?>[]{}, new Object[]{});
            Vec3 real = (Vec3) ModUnderTest.staticCall(ROUTE_COORDS, "toReal",
                    new Class<?>[]{frame.getClass(), Vec3.class}, new Object[]{frame, rel});
            return new Object[]{real, nodes.size(), nodes.get(0)};
        });
        if (info == null) {
            failures.add(tag + ": no trap room, clay or bundled route");
            return;
        }
        Vec3 start = (Vec3) info[0];
        int nodeCount = (Integer) info[1];
        Object first = info[2];
        String walkBefore = ctx.computeOnClient(mc -> savedWalk(first));
        println("node #1 saved walk before: " + walkBefore);
        if (walkBefore.startsWith("0 steps")) {
            failures.add(tag + ": the bundled route's node #1 has no saved walk");
            return;
        }
        Run a = enter(ctx, tag + " entry 1 (start spot)", start.add(0, 0.05, 0), nodeCount, true);
        Run b = enter(ctx, tag + " entry 2 (0.64 off)", start.add(0.45, 0.05, 0.45), nodeCount, false);
        Run c = enter(ctx, tag + " entry 3 (start spot)", start.add(0, 0.05, 0), nodeCount, false);
        String walkAfter = ctx.computeOnClient(mc -> savedWalk(first));
        println("node #1 saved walk after: " + walkAfter);
        for (Run r : new Run[]{a, c}) {
            if (r.beganAt < 0) {
                failures.add(r.tag + ": node #1 never began");
            } else if (!r.savedUsed || r.walkAt < 0 || r.walkAt - r.beganAt > START_BOUND) {
                failures.add(r.tag + ": the walk did not start on the saved walk within " + START_BOUND + " tick(s) (saved used "
                        + r.savedUsed + ", walk line " + (r.walkAt < 0 ? "never" : (r.walkAt - r.beganAt) + " tick(s) after node #1")
                        + ": " + r.walkLine + ")");
            }
        }
        if (b.beganAt < 0 || b.walkAt < 0) {
            failures.add(b.tag + ": the off-start entry never began its walk (" + b.walkLine + ")");
        }
        if (!walkAfter.equals(walkBefore)) {
            failures.add(tag + ": node #1's bundled saved walk was replaced (" + walkBefore + " -> " + walkAfter + ")");
        }
        if (!a.lastBegan) {
            failures.add(a.tag + ": the route never reached its last node #" + nodeCount + " (" + a + ")");
        }
        if (a.pearlTicks <= GUARD_TICKS) {
            failures.add(a.tag + ": the #3 pearl took only " + a.pearlTicks + " tick(s) to node #4 - the case did not exercise a"
                    + " pearl in flight past " + GUARD_TICKS + " ticks");
        }
        for (Run r : new Run[]{a, b, c}) {
            if (r.guardMax >= GUARD_TICKS) {
                failures.add(r.tag + ": Dungeons Autopilot's walk-wait guard would have given the route up (" + r.guardMax
                        + " still tick(s) in a row awaiting a walk, at " + r.guardAt + ")");
            }
        }
    }

    /** One entry's findings, in test ticks from the teleport. */
    private static final class Run {
        String tag;
        int beganAt = -1;
        int walkAt = -1;
        boolean savedUsed;
        String walkLine = "none";
        int pearlTicks = -1;
        boolean lastBegan;
        int guardMax;
        String guardAt = "-";

        @Override
        public String toString() {
            return String.format("began %d, walk %d saved %s, pearl %d t, last node %s, guard max %d", beganAt, walkAt,
                    savedUsed, pearlTicks, lastBegan, guardMax);
        }
    }

    /**
     * Teleports into New Trap at {@code at} (server side, standing still) after standing clear on the north tile, and
     * watches the route: the full run when {@code full}, else until 30 ticks after its walk began.
     */
    private static Run enter(ClientGameTestContext ctx, String tag, Vec3 at, int nodeCount, boolean full) {
        Run r = new Run();
        r.tag = tag;
        SimTrapRoutes1121Tests.standOnTile(ctx, SimTrapRoutes1121Tests.NX, SimTrapRoutes1121Tests.NZ, 0, 0);
        ctx.waitTicks(20);
        int completionsBefore = ctx.computeOnClient(mc -> ((Number) ModUnderTest.staticCall(ROUTE_EXEC, "completions")).intValue());
        long mark = LogTap.mark();
        AtomicReference<Boolean> done = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(uuid);
                if (sp != null) {
                    sp.teleportTo(at.x, at.y, at.z);
                    sp.setDeltaMovement(Vec3.ZERO);
                }
                done.set(sp != null);
            });
        });
        ctx.waitFor(mc -> done.get() != null, 200);
        String lastTag = "Node #" + nodeCount + " ";
        int node3 = -1;
        int streak = 0;
        for (int t = 0; t < (full ? 1200 : 300); t++) {
            ctx.waitTicks(1);
            boolean[] g = ctx.computeOnClient(mc -> new boolean[]{
                    (Boolean) ModUnderTest.staticCall(ROUTE_EXEC, "awaitingWalk"),
                    mc.player.onGround(), mc.player.getDeltaMovement().horizontalDistanceSqr() < 1.0E-4,
                    (Boolean) ModUnderTest.staticCall(ROUTE_EXEC, "isRunning")});
            streak = g[0] && g[1] && g[2] ? streak + 1 : 0;
            if (streak > r.guardMax) {
                r.guardMax = streak;
                r.guardAt = "tick " + t;
            }
            for (String l : LogTap.since(mark)) {
                if (r.beganAt < 0 && l.contains("Node #1 ") && l.contains(" begins: player (")) {
                    r.beganAt = t;
                }
                if (r.walkAt < 0 && l.contains("[TrapWalk]") && (l.contains("using the saved") || l.contains(": planned "))) {
                    r.walkAt = t;
                    r.savedUsed = l.contains("using the saved");
                    r.walkLine = l.substring(Math.max(0, l.indexOf("[TrapWalk]")));
                }
                if (node3 < 0 && l.contains("Node #3 ") && l.contains(" begins")) {
                    node3 = t;
                }
                if (node3 >= 0 && r.pearlTicks < 0 && l.contains("Node #4 ") && l.contains(" begins")) {
                    r.pearlTicks = t - node3;
                }
                r.lastBegan |= l.contains(lastTag) && l.contains(" begins");
            }
            int comp = ctx.computeOnClient(mc -> ((Number) ModUnderTest.staticCall(ROUTE_EXEC, "completions")).intValue());
            if (!full && r.walkAt >= 0 && t > r.walkAt + 30) {
                break;
            }
            if (r.beganAt >= 0 && (comp > completionsBefore || (!g[3] && t > r.beganAt + 5))) {
                break;
            }
        }
        if (r.walkAt >= 0 && !r.savedUsed) {
            // a fresh plan: let it be saved (or not) before he leaves, as in his run
            ctx.waitTicks(5);
        }
        String stops = String.join(" | ", SimTrapRoutes1121Tests.grep(mark, "stop called", "Route stopped"));
        println(tag + ": " + r + (stops.isEmpty() ? "" : "; stops: " + stops));
        return r;
    }

    /** Node #1's saved walk, for comparing: steps, start, speed and a hash of the steps. */
    private static String savedWalk(Object node) {
        try {
            List<?> steps = (List<?>) node.getClass().getField("walkSteps").get(node);
            double x = node.getClass().getField("walkFromX").getDouble(node);
            double y = node.getClass().getField("walkFromY").getDouble(node);
            double z = node.getClass().getField("walkFromZ").getDouble(node);
            double vx = node.getClass().getField("walkFromVX").getDouble(node);
            double vz = node.getClass().getField("walkFromVZ").getDouble(node);
            return String.format(java.util.Locale.ROOT, "%d steps from %.3f %.3f %.3f speed %.3f %.3f hash %08x",
                    steps.size(), x, y, z, vx, vz, steps.hashCode());
        } catch (ReflectiveOperationException e) {
            return "unreadable (" + e + ")";
        }
    }

    private static void println(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}
