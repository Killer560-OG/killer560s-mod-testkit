package dev.testkit.gametest;

import dev.testkit.gametest.hx.Session;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import static dev.testkit.gametest.Ap3RuntimeTests.FLOOR;
import static dev.testkit.gametest.Ap3RuntimeTests.LANE_X0;
import static dev.testkit.gametest.Ap3RuntimeTests.node;
import static dev.testkit.gametest.Ap3RuntimeTests.onClient;
import static dev.testkit.gametest.Ap3RuntimeTests.set;
import static dev.testkit.gametest.Ap3RuntimeTests.setChain;

/**
 * mod align12-1110 - Fast Align, tick-exact (killer560 2026-11-10: "its only goal should be 2 tick align - 1 tick align",
 * 0.0002 on each axis, the box the exact block, counted from the tick that enters it, which does not count). In the 63-ap3
 * session (cheat jar, GrimAC on; Session.test fails any flag). Each case runs seeded approaches into a 1x1 Fast Align on a
 * block center at one Hypixel speed (movement_speed base 0.4 / 0.5 / 0.6), from a random heading and side offset, with W or
 * W+A, sprinting or walking, from far (steady speed) or near (still accelerating). The client's own END-of-tick states
 * decide: the entering tick is the first he is inside the block, done is the first tick on the point within 0.0002 with the
 * velocity the game zeroes next tick, and he must still be there 5 ticks later. Every approach must end on the point; the
 * mod's "tick-exact plan" line must be there (the feature acted), and the case notes how many ticks each took.
 * <ul>
 *   <li>1110-align12-sprint-400 / -500 / -600: sprinting from 6-9 blocks.</li>
 *   <li>1110-align12-walk-400 / -600: walking (no sprint key).</li>
 *   <li>1110-align12-accel-500: sprinting from 1.2-3 blocks, still speeding up.</li>
 * </ul>
 */
final class Align12Cases {

    private Align12Cases() {
    }

    static final int TRIALS = Integer.getInteger("align12.trials", 10);

    static void register(Session s) {
        s.test("1110-align12-sprint-400", c -> run(c, 0.4, true, false, 1));
        s.test("1110-align12-sprint-500", c -> run(c, 0.5, true, false, 2));
        s.test("1110-align12-sprint-600", c -> run(c, 0.6, true, false, 3));
        s.test("1110-align12-walk-400", c -> run(c, 0.4, false, false, 4));
        s.test("1110-align12-walk-600", c -> run(c, 0.6, false, false, 5));
        s.test("1110-align12-accel-500", c -> run(c, 0.5, true, true, 6));
    }

    private static void run(Session c, double speed, boolean sprint, boolean near, long seed) {
        ClientGameTestContext ctx = c.ctx();
        c.server().command(String.format(Locale.ROOT, "attribute @p minecraft:movement_speed base set %.2f", speed));
        c.server().sync(20);
        double z = Ap3RuntimeTests.lane();
        double tx = LANE_X0 + 20.5, tz = Math.floor(z) + 0.5;
        double y = FLOOR + 1;
        Random rnd = new Random(1110L * 31 + seed);
        int[] hist = new int[12];
        int planned = 0, fellBack = 0;
        List<String> fails = new ArrayList<>();
        try {
            for (int t = 0; t < TRIALS; t++) {
                double head = (rnd.nextDouble() - 0.5) * 70; // degrees off +x
                boolean diag = rnd.nextBoolean();
                double side = (rnd.nextDouble() - 0.5) * 0.9;
                double dist = near ? 1.2 + rnd.nextDouble() * 1.8 : 6 + rnd.nextDouble() * 3;
                double hr = Math.toRadians(head);
                // start dist back along the heading, side offset across it, so the line crosses the block
                double sx = tx - Math.cos(hr) * dist - Math.sin(hr) * side;
                double sz = tz - Math.sin(hr) * dist + Math.cos(hr) * side;
                float moveYaw = (float) (head - 90); // MC yaw: -90 faces +x
                float yaw = diag ? moveYaw + 45f : moveYaw; // W+A moves 45 degrees left of the yaw
                long mark = LogTap.mark();
                onClient(ctx, mc -> {
                    Ap3RuntimeTests.staticCallStop();
                    return null;
                });
                c.server().command(String.format(Locale.ROOT, "tp @p %.4f %.2f %.4f %.3f 0", sx, y, sz, yaw));
                c.server().sync(20);
                ctx.waitTicks(10);
                Object align = node("FAST_ALIGN", tx, tz, 0f, 0f, 1.0, 1.0);
                set(align, "y", y);
                onClient(ctx, mc -> {
                    setChain(align);
                    return null;
                });
                ctx.waitTicks(2);
                ctx.getInput().holdKey(o -> o.keyUp);
                if (diag) {
                    ctx.getInput().holdKey(o -> o.keyLeft);
                }
                if (sprint) {
                    ctx.getInput().holdKey(o -> o.keySprint);
                }
                List<double[]> tr = new ArrayList<>();
                boolean held = true;
                for (int i = 0; i < 120; i++) {
                    ctx.waitTick();
                    double[] r = onClient(ctx, mc -> {
                        Vec3 v = mc.player.getDeltaMovement();
                        Object n = dev.testkit.gametest.mod.Mod.field("ap3.Ap3Executor", "activeNode");
                        return new double[]{mc.player.getX(), mc.player.getZ(), v.x, v.z, n != null ? 1 : 0};
                    });
                    tr.add(r);
                    if (held && r[4] == 1) {
                        held = false;
                        release(ctx);
                    }
                    if (!held && r[4] == 0 && tr.size() > 8 && same(tr, 5)) {
                        break;
                    }
                }
                release(ctx);
                int entry = -1, done = -1;
                for (int i = 0; i < tr.size(); i++) {
                    double[] r = tr.get(i);
                    if (entry < 0 && Math.abs(r[0] - tx) <= 0.5 && Math.abs(r[1] - tz) <= 0.5) {
                        entry = i;
                    }
                    if (entry >= 0 && done < 0 && Math.abs(r[0] - tx) < 2e-4 && Math.abs(r[1] - tz) < 2e-4
                            && r[2] * r[2] + r[3] * r[3] < 9e-6) {
                        done = i;
                    }
                }
                double[] last = tr.get(tr.size() - 1);
                boolean stays = done >= 0 && Math.abs(last[0] - tx) < 2e-4 && Math.abs(last[1] - tz) < 2e-4;
                List<String> plan = lines(mark, "tick-exact plan");
                List<String> lost = lines(mark, "tick-exact align handed");
                if (!plan.isEmpty()) {
                    planned++;
                }
                if (!lost.isEmpty()) {
                    fellBack++;
                }
                int n = done >= 0 ? done - entry : -1;
                if (n >= 0) {
                    hist[Math.min(n, hist.length - 1)]++;
                }
                c.note(String.format(Locale.ROOT, "trial %d: heading %+.1f %s side %+.2f from %.1f: entered at sample %d, on the"
                                + " point and stopped %s; end off (%+.6f, %+.6f)%s%s", t, head, diag ? "W+A" : "W", side, dist,
                        entry, n >= 0 ? n + " tick(s) after entering" : "NEVER", last[0] - tx, last[1] - tz,
                        plan.isEmpty() ? "; no tick-exact plan" : "; " + tail(plan.get(0)),
                        lost.isEmpty() ? "" : "; " + tail(lost.get(0))));
                if (entry < 0) {
                    fails.add("trial " + t + " never entered the block");
                } else if (!stays) {
                    fails.add(String.format(Locale.ROOT, "trial %d ended (%+.6f, %+.6f) off the point", t, last[0] - tx, last[1] - tz));
                }
            }
        } finally {
            release(ctx);
            onClient(ctx, mc -> {
                Ap3RuntimeTests.staticCallStop();
                return null;
            });
            c.server().command("attribute @p minecraft:movement_speed base set 0.10");
            c.server().sync(20);
        }
        StringBuilder h = new StringBuilder();
        for (int i = 0; i < hist.length; i++) {
            if (hist[i] > 0) {
                h.append(String.format(Locale.ROOT, " %d-tick %d of %d;", i, hist[i], TRIALS));
            }
        }
        c.note(String.format(Locale.ROOT, "align12 speed %.1f %s%s: tick-exact plans %d, handed to the regular align %d;%s",
                speed, sprint ? "sprint" : "walk", near ? " near" : "", planned, fellBack, h));
        if (planned == 0) {
            throw new AssertionError("no tick-exact plan in any trial - the feature never acted");
        }
        if (!fails.isEmpty()) {
            throw new AssertionError(String.join("; ", fails));
        }
    }

    private static void release(ClientGameTestContext ctx) {
        ctx.getInput().releaseKey(o -> o.keyUp);
        ctx.getInput().releaseKey(o -> o.keyLeft);
        ctx.getInput().releaseKey(o -> o.keySprint);
    }

    /** The last k samples all at the same spot. */
    private static boolean same(List<double[]> tr, int k) {
        double[] a = tr.get(tr.size() - 1);
        for (int i = tr.size() - k; i < tr.size() - 1; i++) {
            double[] b = tr.get(i);
            if (b[0] != a[0] || b[1] != a[1]) {
                return false;
            }
        }
        return true;
    }

    private static List<String> lines(long mark, String needle) {
        List<String> out = new ArrayList<>();
        for (String l : LogTap.since(mark)) {
            if (l.contains(needle)) {
                out.add(l);
            }
        }
        return out;
    }

    private static String tail(String l) {
        int i = l.indexOf("Fast Align #");
        return i < 0 ? l : l.substring(i);
    }
}
