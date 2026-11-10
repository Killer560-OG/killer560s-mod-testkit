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
        // SAME-TICK GRIM CASES: his own action in every tick of the approach and the align (a container click cannot be
        // one: AP3 stops when a screen opens), and the align armed again while it runs.
        for (SameTick.Act a : new SameTick.Act[]{SameTick.Act.USE, SameTick.Act.SWAP, SameTick.Act.TP, SameTick.Act.DIG}) {
            s.test("1110-align12-same-" + a.name().toLowerCase(Locale.ROOT), c -> run(c, 0.5, true, false, 10 + a.ordinal(), a, false));
        }
        s.test("1110-align12-same-again", c -> run(c, 0.5, true, false, 20, null, true));
    }

    private static final int ROD_A = 7;
    private static final int ROD_B = 8;

    private static void run(Session c, double speed, boolean sprint, boolean near, long seed) {
        run(c, speed, sprint, near, seed, null, false);
    }

    /** {@code act}: his action in every tick (null: none); {@code again}: the align is armed a second time while it runs. */
    private static void run(Session c, double speed, boolean sprint, boolean near, long seed, SameTick.Act act, boolean again) {
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
        boolean same = act != null || again;
        int trials = same ? Math.max(4, TRIALS / 2) : TRIALS;
        SameTick st = null;
        int rearmed = 0;
        if (act == SameTick.Act.USE || act == SameTick.Act.SWAP) {
            c.server().command("item replace entity @p hotbar." + ROD_A + " with minecraft:blaze_rod");
            c.server().command("item replace entity @p hotbar." + ROD_B + " with minecraft:blaze_rod");
            ctx.waitTicks(3);
            onClient(ctx, mc -> {
                mc.player.getInventory().setSelectedSlot(ROD_A);
                return null;
            });
        }
        try {
            if (act != null) {
                // the align's packets: every movement packet while AP3 runs
                st = onClient(ctx, mc -> SameTick.start(act, act == SameTick.Act.DIG ? 2 : 1, p -> p instanceof
                        net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
                        && Boolean.TRUE.equals(dev.testkit.gametest.mod.Mod.staticCall("ap3.Ap3Executor", "isRunning")),
                        ROD_A, ROD_B));
            }
            for (int t = 0; t < trials; t++) {
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
                // DIG: his crosshair on the floor ahead (pitch 60), so his left click digs a block every other tick
                c.server().command(String.format(Locale.ROOT, "tp @p %.4f %.2f %.4f %.3f %d", sx, y, sz, yaw,
                        act == SameTick.Act.DIG ? 60 : 0));
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
                int armed = -1;
                for (int i = 0; i < 120; i++) {
                    if (act == SameTick.Act.TP && !held && i % 2 == 0) {
                        c.server().command("execute as @a at @s run tp @s ~ ~ ~");
                    }
                    if (again && armed >= 0 && i == armed + 1) {
                        onClient(ctx, mc -> {
                            setChain(align); // a second run of the align, one tick into the first
                            return null;
                        });
                        rearmed++;
                    }
                    ctx.waitTick();
                    double[] r = onClient(ctx, mc -> {
                        Vec3 v = mc.player.getDeltaMovement();
                        Object n = dev.testkit.gametest.mod.Mod.field("ap3.Ap3Executor", "activeNode");
                        return new double[]{mc.player.getX(), mc.player.getZ(), v.x, v.z, n != null ? 1 : 0};
                    });
                    tr.add(r);
                    if (held && r[4] == 1) {
                        held = false;
                        armed = i;
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
            if (st != null) {
                SameTick done = st;
                onClient(ctx, mc -> {
                    done.close();
                    return null;
                });
            }
            release(ctx);
            onClient(ctx, mc -> {
                Ap3RuntimeTests.staticCallStop();
                return null;
            });
            c.server().command("attribute @p minecraft:movement_speed base set 0.10");
            c.server().command("clear @p minecraft:blaze_rod");
            c.server().sync(20);
        }
        StringBuilder h = new StringBuilder();
        for (int i = 0; i < hist.length; i++) {
            if (hist[i] > 0) {
                h.append(String.format(Locale.ROOT, " %d-tick %d of %d;", i, hist[i], trials));
            }
        }
        c.note(String.format(Locale.ROOT, "align12 speed %.1f %s%s: tick-exact plans %d, handed to the regular align %d;%s",
                speed, sprint ? "sprint" : "walk", near ? " near" : "", planned, fellBack, h));
        if (st != null) {
            c.note(st.report());
            String why = st.verdict("align12 " + act, false);
            c.check(why == null, why);
        }
        if (again) {
            c.note("armed again mid-align " + rearmed + " time(s)");
            c.check(rearmed > 0, "the align was never armed a second time");
        }
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
