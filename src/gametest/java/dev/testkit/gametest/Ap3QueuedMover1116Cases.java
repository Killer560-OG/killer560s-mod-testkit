package dev.testkit.gametest;

import dev.testkit.gametest.hx.Session;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static dev.testkit.gametest.Ap3RuntimeTests.LANE_X0;
import static dev.testkit.gametest.Ap3RuntimeTests.node;
import static dev.testkit.gametest.Ap3RuntimeTests.onClient;

/**
 * mod ap3setback-1116 - killer560's 2026-11-16 "it put me backwards" AP3 run at 11:03:07: a Fast Align crossed the WALK
 * on its point and a RUN beside it; both were queued while the align owned movement, and when the
 * align ended both fired in queue order, so the RUN he was no longer in set the walk direction and drove him off the
 * route. 1116-ap3-mover-queued-in-align rebuilds that: the align must end with the WALK he stands in driving, the RUN
 * dropped with its log line, and him moving along the WALK's +x heading, not the RUN's +z. Fails on 03e527b54.
 */
final class Ap3QueuedMover1116Cases {

    private Ap3QueuedMover1116Cases() {
    }

    static void register(Session s) {
        s.test("1116-ap3-mover-queued-in-align", Ap3QueuedMover1116Cases::queuedInAlign);
    }

    private static Object execField(String name) {
        return dev.testkit.gametest.mod.Mod.field("ap3.Ap3Executor", name);
    }

    private static void speed(Session c, double v) {
        c.server().command(String.format(Locale.ROOT, "attribute @p minecraft:movement_speed base set %.2f", v));
        c.server().sync(20);
    }

    private static void queuedInAlign(Session c) {
        ClientGameTestContext ctx = c.ctx();
        double z = Ap3RuntimeTests.lane();
        double tx = LANE_X0 + 10.5;
        speed(c, 0.2);
        long mark = LogTap.mark();
        try {
            Object run = node("RUN", LANE_X0 + 3.0, z, -90f, 0f, 2.0, 2.0);
            Object align = node("FAST_ALIGN", tx, z, 0f, 0f, 3.0, 3.0);
            // WALK (heading +x) covers the align's approach and point, so it is queued first and he ends in it;
            // run2 (heading +z) sits on the approach (x 9.2..10.4), so it is queued after the WALK and left before the
            // align ends: on the old jar it fires last and wins. It is wide enough that he is more centered in it than
            // in the WALK on both align paths: the regular align passes 9.84, 10.15, 10.50, the 1-tick align (align12)
            // 9.28, 9.59, 10.01, 10.50; the old 0.5 box at 10.15 caught only the first.
            Object walk = node("WALK", tx, z, -90f, 0f, 2.8, 2.8);
            Object run2 = node("RUN", tx - 0.7, z, 0f, 0f, 3.0, 1.2); // long in z so he is more centered in it than in the WALK
            Ap3RuntimeTests.walkIntoRun(c, z, run, align, walk, run2);
            boolean alignSeen = false;
            boolean bothQueued = false;
            StringBuilder trace = new StringBuilder();
            int alignEnd = -1;
            double[] at = null;
            for (int t = 0; t < 200; t++) {
                ctx.waitTick();
                Object[] r = onClient(ctx, mc -> {
                    Object active = execField("activeNode");
                    List<?> q = (List<?>) execField("queue");
                    boolean w = q.stream().anyMatch(n -> n == walk);
                    boolean r2 = q.stream().anyMatch(n -> n == run2);
                    return new Object[]{active == align, w && r2, mc.player.getX(), mc.player.getZ(), w, r2};
                });
                if ((Boolean) r[0]) {
                    trace.append(String.format(Locale.ROOT, " (%.3f,%.3f walk=%s run=%s)", r[2], r[3], r[4], r[5]));
                    alignSeen = true;
                    bothQueued |= (Boolean) r[1];
                } else if (alignSeen) {
                    alignEnd = t;
                    at = new double[]{(Double) r[2], (Double) r[3]};
                    break;
                }
            }
            if (!alignSeen) {
                throw new AssertionError("the Fast Align never ran - nothing was tested");
            }
            if (!bothQueued) {
                throw new AssertionError("the align never queued both the WALK and the RUN on its approach - the case"
                        + " did not rebuild the 11-16 run; nothing was tested; align ticks:" + trace);
            }
            if (alignEnd < 0) {
                throw new AssertionError("the align never ended in 200 ticks");
            }
            ctx.waitTicks(2);
            Object hold = onClient(ctx, mc -> execField("holdNode"));
            ctx.waitTicks(10);
            double[] end = onClient(ctx, mc -> new double[]{mc.player.getX(), mc.player.getZ()});
            double dx = end[0] - at[0];
            double dz = end[1] - at[1];
            List<String> dropped = new ArrayList<>();
            for (String l : LogTap.since(mark)) {
                if (l.contains("dropped: it waited while you left its box")) {
                    dropped.add(l);
                }
            }
            c.note(String.format(Locale.ROOT, "align ended at (%.3f, %.3f); held node %s; moved dx %.2f dz %.2f in 10"
                    + " ticks; drop lines %d", at[0], at[1], hold == walk ? "WALK" : hold == run2 ? "the RUN past it"
                    : String.valueOf(hold), dx, dz, dropped.size()));
            for (String l : dropped) {
                c.note("  " + l);
            }
            if (hold == run2) {
                throw new AssertionError("the RUN he had left drove after the align (the 11-16 setback), not the WALK he"
                        + " stands in");
            }
            if (hold != walk) {
                throw new AssertionError("the WALK on the align's point did not drive after the align: held " + hold);
            }
            if (dropped.isEmpty()) {
                throw new AssertionError("no log line says the RUN he had left was dropped");
            }
            if (dx < 1.0 || Math.abs(dz) > 0.5) {
                throw new AssertionError(String.format(Locale.ROOT, "he did not walk the WALK's +x heading: dx %.2f dz %.2f",
                        dx, dz));
            }
        } finally {
            onClient(ctx, mc -> {
                Ap3RuntimeTests.staticCallStop();
                return null;
            });
            speed(c, 0.1);
        }
    }
}
