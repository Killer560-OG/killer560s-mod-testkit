package dev.testkit.gametest;

import dev.testkit.gametest.hx.Session;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static dev.testkit.gametest.Ap3RuntimeTests.EXEC;
import static dev.testkit.gametest.Ap3RuntimeTests.LANE_X0;
import static dev.testkit.gametest.Ap3RuntimeTests.node;
import static dev.testkit.gametest.Ap3RuntimeTests.onClient;

/**
 * mod ap3-stop-1101. killer560, 2026-11-01: "for ap3 make it so a termaura node will make you stop as well just like a stop
 * node. also if i hit a stop node and stay in it dont have it keep overriding other nodes it should fire once then not work
 * again till i reenter it."
 * <p>
 * One lane per case, the same shape for a STOP and a TERM_AURA (no terminal in range on the lanes, so the Term Aura's
 * click part ends at once and only its stop is under test; the boss case 1101-boss-ap3-termaura-run has a real terminal):
 * <ol>
 *   <li>A RUN carries him east into a big box (x 8..16) of the type under test. A WALK east sits in its first block, so it
 *       is entered on the same tick; a RUN east sits inside it at x 12..13; a WALK WEST waits outside at x 22..23.</li>
 *   <li>Expected: he comes to a standstill inside the big box (the stop), then the WALK takes him on and the inner RUN
 *       carries him out of the far side - the stop must not fire again and stand him still a second time while he is
 *       still inside it.</li>
 *   <li>The WALK west brings him back into the box from the east: a re-entry, so it must stop him again (re-armed).</li>
 * </ol>
 * Every judgment comes from his position, sampled every tick; the mod's {@code isStopSpent} hook is noted when the jar has
 * it (a jar from before 1101 does not, and must still be judged on the positions).
 */
final class Ap3Stop1101Cases {

    private static final double BOX_X0 = LANE_X0 + 8.0;
    private static final double BOX_X1 = LANE_X0 + 16.0;
    /** A tick in which he moved less than this is a standstill. A STOP ends at 0.003 per tick; walking is ~0.2. */
    private static final double STILL = 0.01;

    private Ap3Stop1101Cases() {
    }

    static void register(Session s) {
        s.test("1101-ap3-stop-once-reenter", c -> once(c, "STOP"));
        s.test("1101-ap3-termaura-once-reenter", c -> once(c, "TERM_AURA"));
    }

    private record Sample(int tick, double x, double dx, Boolean spent) {
    }

    private static void once(Session c, String type) {
        ClientGameTestContext ctx = c.ctx();
        ctx.getInput().pressKey(options -> options.keyHotbarSlots[0]);
        ctx.waitTicks(2);
        double z = Ap3RuntimeTests.lane();
        Object run = Ap3RuntimeTests.runNode(z);
        Object big = node(type, LANE_X0 + 12.0, z, -90f, 0f, 8.0, 3.0);
        Object walk = node("WALK", LANE_X0 + 8.5, z, -90f, 0f, 1.0, 3.0);
        Object run2 = node("RUN", LANE_X0 + 12.5, z, -90f, 0f, 1.0, 3.0);
        Object back = node("WALK", LANE_X0 + 22.5, z, 90f, 0f, 1.0, 3.0);
        long mark = LogTap.mark();
        try {
            Ap3RuntimeTests.walkIntoRun(c, z, run, big, walk, run2, back);
            List<Sample> samples = new ArrayList<>();
            double lastX = onClient(ctx, mc -> mc.player.getX());
            for (int t = 0; t < 160; t++) {
                ctx.waitTick();
                Object[] r = onClient(ctx, mc -> new Object[]{mc.player.getX(), spent(big)});
                double x = (Double) r[0];
                samples.add(new Sample(t, x, x - lastX, (Boolean) r[1]));
                lastX = x;
            }
            // The phases, from the positions.
            int firstIn = -1;
            int stop1 = -1;
            int out = -1;
            int farthest = -1;
            int reIn = -1;
            int stop2 = -1;
            int stillEpisodesFirstStay = 0;
            boolean prevStill = false;
            double maxX = Double.NEGATIVE_INFINITY;
            for (Sample s : samples) {
                boolean in = s.x() >= BOX_X0 && s.x() <= BOX_X1;
                boolean still = Math.abs(s.dx()) < STILL;
                if (firstIn < 0) {
                    if (in) {
                        firstIn = s.tick();
                    }
                } else if (out < 0) {
                    if (in && still && !prevStill) {
                        stillEpisodesFirstStay++;
                        if (stop1 < 0) {
                            stop1 = s.tick();
                        }
                    }
                    if (s.x() > BOX_X1) {
                        out = s.tick();
                    }
                } else if (reIn < 0) {
                    if (s.x() > maxX) {
                        maxX = s.x();
                        farthest = s.tick();
                    }
                    if (in) {
                        reIn = s.tick();
                    }
                } else if (stop2 < 0 && in && still) {
                    stop2 = s.tick();
                }
                prevStill = still;
            }
            for (Sample s : samples) {
                if (s.tick() % 4 == 0 || s.tick() == firstIn || s.tick() == stop1 || s.tick() == out || s.tick() == reIn
                        || s.tick() == stop2) {
                    c.note(String.format(Locale.ROOT, "t%d x %.3f dx %+.4f%s", s.tick(), s.x(), s.dx(),
                            s.spent() == null ? "" : " spent " + s.spent()));
                }
            }
            for (String l : LogTap.since(mark)) {
                if (l.contains("[AP3]") || l.contains("Term Aura") || l.contains("(chat)")) {
                    c.note("  " + l.trim());
                }
            }
            Sample last = samples.get(samples.size() - 1);
            c.note(String.format(Locale.ROOT, "%s: entered t%d, standstill t%d, standstills during the first stay %d, out t%d,"
                    + " farthest x %.2f at t%d, back in t%d, stopped again t%d, ended at x %.2f", type, firstIn, stop1,
                    stillEpisodesFirstStay, out, maxX, farthest, reIn, stop2, last.x()));
            c.check(firstIn >= 0, "the run never reached the " + type + " box - nothing below means anything");
            c.check(stop1 >= 0, "the " + type + " never stood him still (it did not stop him)");
            c.check(out >= 0, "he never left the " + type + " box on the far side: the following WALK / RUN were overridden");
            c.check(stillEpisodesFirstStay == 1, "he was stood still " + stillEpisodesFirstStay
                    + " times during one stay in the " + type + " box: it fired more than once per entry");
            c.check(maxX > LANE_X0 + 21.0, String.format(Locale.ROOT, "the inner RUN did not carry him on past the box (%.2f)", maxX));
            c.check(reIn >= 0, "the WALK west never brought him back into the box");
            c.check(stop2 >= 0, "re-entering the " + type + " box did not stop him again (it did not re-arm)");
            c.check(last.x() >= BOX_X0 && last.x() <= BOX_X1 && Math.abs(last.dx()) < STILL,
                    String.format(Locale.ROOT, "he did not end standing in the box (x %.2f, dx %.4f)", last.x(), last.dx()));
            // The hook, when the jar has it: spent while he stood in the box after the stop, clear once he was out.
            Sample atStop = samples.get(stop1);
            Sample afterOut = samples.get(Math.min(samples.size() - 1, out + 1));
            if (atStop.spent() != null) {
                c.check(atStop.spent(), "isStopSpent false right after the stop");
                c.check(!afterOut.spent(), "isStopSpent still true after he left the box");
                c.check(last.spent(), "isStopSpent false after the re-entry stop");
            }
        } finally {
            onClient(ctx, mc -> {
                Ap3RuntimeTests.staticCallStop();
                return null;
            });
        }
    }

    /** The mod's 1101 hook, or null on a jar without it. */
    private static Boolean spent(Object node) {
        try {
            Class<?> exec = Class.forName(EXEC);
            return (Boolean) exec.getMethod("isStopSpent", node.getClass()).invoke(null, node);
        } catch (NoSuchMethodException e) {
            return null;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Ap3Executor.isStopSpent", e);
        }
    }

}
