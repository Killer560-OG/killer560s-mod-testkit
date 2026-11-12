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
        // The approach-tick steer (killer560 2026-11-10: only while AP3 drives, else only on a tick with no input from him):
        // carried in by a RUN node (must steer), and walking in himself while his look moves every tick (must not steer).
        s.test("1110-align12-ap3run", c -> run(c, 0.5, true, false, 30, null, false, Mode.DRIVEN));
        s.test("1110-align12-mouse-500", c -> run(c, 0.5, true, false, 31, null, false, Mode.MOUSE));
        // ssnap-1112: his S1->SS route end (c34g: last RUN at yaw -1, 2.2 blocks before the 2x2 align, 1 block to its side,
        // sprinting in): his crosshair must not show the align's steering turns nor the turn back after it.
        s.test("1110-align12-ss", c -> run(c, 0.5, true, false, 32, null, false, Mode.SS));
    }

    /** How he comes in: walking himself, carried by AP3's RUN node, or walking himself with the mouse moving every tick. */
    enum Mode {
        HAND, DRIVEN, MOUSE, SS;

        boolean driven() { return this == DRIVEN || this == SS; }
    }

    private static final int ROD_A = 7;
    private static final int ROD_B = 8;

    private static void run(Session c, double speed, boolean sprint, boolean near, long seed) {
        run(c, speed, sprint, near, seed, null, false);
    }

    /** {@code act}: his action in every tick (null: none); {@code again}: the align is armed a second time while it runs. */
    private static void run(Session c, double speed, boolean sprint, boolean near, long seed, SameTick.Act act, boolean again) {
        run(c, speed, sprint, near, seed, act, again, Mode.HAND);
    }

    private static void run(Session c, double speed, boolean sprint, boolean near, long seed, SameTick.Act act, boolean again,
            Mode mode) {
        ClientGameTestContext ctx = c.ctx();
        c.server().command(String.format(Locale.ROOT, "attribute @p minecraft:movement_speed base set %.2f", speed));
        c.server().sync(20);
        double z = Ap3RuntimeTests.lane();
        double tx = LANE_X0 + 20.5, tz = Math.floor(z) + 0.5;
        double y = FLOOR + 1;
        Random rnd = new Random(1110L * 31 + seed);
        int[] hist = new int[12];
        int planned = 0, fellBack = 0, steered = 0, late = 0;
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
                double head = mode.driven() ? 0 : (rnd.nextDouble() - 0.5) * 70; // degrees off +x
                boolean diag = !mode.driven() && rnd.nextBoolean();
                double side = mode == Mode.SS ? -0.76 + (rnd.nextDouble() - 0.5) * 0.1 : (rnd.nextDouble() - 0.5) * 0.9;
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
                Object align = node("FAST_ALIGN", tx, tz, 0f, 0f, mode == Mode.SS ? 2.0 : 1.0, mode == Mode.SS ? 2.0 : 1.0);
                set(align, "y", y);
                if (mode == Mode.DRIVEN) {
                    Ap3RuntimeTests.walkIntoRun(c, tz + side, Ap3RuntimeTests.runNode(tz + side), align);
                } else if (mode == Mode.SS) {
                    // his last leg turned into the lane (+z there is +x here): a RUN at yaw -91 drifting away from the point
                    Object last = node("RUN", tx - 2.2225, tz - 0.995, -91f, 0f, 0.8, 0.8);
                    Ap3RuntimeTests.walkIntoRun(c, tz + side, Ap3RuntimeTests.runNode(tz + side), last, align);
                }
                // DIG: his crosshair on the floor ahead (pitch 60), so his left click digs a block every other tick
                if (!mode.driven()) {
                    c.server().command(String.format(Locale.ROOT, "tp @p %.4f %.2f %.4f %.3f %d", sx, y, sz, yaw,
                            act == SameTick.Act.DIG ? 60 : 0));
                    c.server().sync(20);
                    ctx.waitTicks(10);
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
                }
                List<double[]> tr = new ArrayList<>();
                boolean held = !mode.driven();
                Object runNodeType = mode.driven() ? "RUN" : null;
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
                    if (held && mode == Mode.MOUSE) {
                        onClient(ctx, mc -> {
                            mc.player.setYRot(mc.player.getYRot() + ((tr.size() & 1) == 0 ? 0.01f : -0.01f)); // his mouse
                            return null;
                        });
                    }
                    ctx.waitTick();
                    double[] r = sample(ctx, runNodeType);
                    tr.add(r);
                    if (held && r[4] == 1) {
                        held = false;
                        armed = i;
                        release(ctx);
                    }
                    if (!held && r[4] == 0 && r[6] == 0 && tr.size() > 8 && same(tr, 5) && (!mode.driven() || entered(tr, tx, tz))) {
                        break;
                    }
                }
                release(ctx);
                double[] an = analyze(tr, tx, tz, 0);
                int entry = (int) an[0], done = (int) an[1];
                double viewJump = an[2];
                double[] last = tr.get(tr.size() - 1);
                boolean stays = done >= 0 && Math.abs(last[0] - tx) < 2e-4 && Math.abs(last[1] - tz) < 2e-4;
                List<String> plan = lines(mark, "tick-exact plan");
                List<String> lost = lines(mark, "tick-exact align handed");
                List<String> steer = lines(mark, "approach tick steered");
                if (!steer.isEmpty()) {
                    steered++;
                }
                if (!lines(mark, "enters a tick later").isEmpty()) {
                    late++; // the would-be entering tick steered too: he enters a tick later, stopped on the same tick as a 2-tick
                }
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
                                + " point and stopped %s; end off (%+.6f, %+.6f); view jump %.2f deg%s%s", t, head, diag ? "W+A" : "W", side,
                        dist, entry, n >= 0 ? n + " tick(s) after entering" : "NEVER", last[0] - tx, last[1] - tz, viewJump,
                        plan.isEmpty() ? "; no tick-exact plan" : "; " + tail(plan.get(plan.size() - 1)),
                        lost.isEmpty() ? "" : "; " + tail(lost.get(0))));
                if (viewJump > 0.5) {
                    fails.add(String.format(Locale.ROOT, "trial %d: his crosshair jumped %.1f degrees in one tick", t, viewJump));
                }
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
        c.note(String.format(Locale.ROOT, "align12 speed %.1f %s%s %s: tick-exact plans %d, approach tick steered %d (entering a tick"
                        + " later %d), handed to the regular align %d;%s", speed, sprint ? "sprint" : "walk", near ? " near" : "", mode,
                planned, steered, late, fellBack, h));
        if (mode == Mode.DRIVEN) {
            c.check(steered > 0, "AP3 carried him in and the approach tick was never steered");
        }
        if (mode == Mode.MOUSE) {
            c.check(steered == 0, "the approach tick was steered on a tick his mouse moved (" + steered + " trial(s))");
        }
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

    /**
     * One END-of-tick sample: x, z, vx, vz, 1 while the align (any node but {@code skipType}) is active, the yaw his camera
     * shows (getViewYRot: the frozen view while AP3 turns the body), 1 while AP3 holds a frozen view (the real yaw glides back
     * under it after the align), and the yaw the first frame after this tick draws (partial tick 0: lerped from yRotO).
     */
    static double[] sample(ClientGameTestContext ctx, Object skipType) {
        return onClient(ctx, mc -> {
            Vec3 v = mc.player.getDeltaMovement();
            Object n = dev.testkit.gametest.mod.Mod.field("ap3.Ap3Executor", "activeNode");
            boolean on = n != null && (skipType == null || !String.valueOf(dev.testkit.gametest.mod.Mod.field(n, "type")).equals(skipType));
            Object fv = dev.testkit.gametest.mod.Mod.staticCall("ap3.Ap3Executor", "frozenViewYaw");
            boolean frozen = fv instanceof Float f && !f.isNaN();
            return new double[]{mc.player.getX(), mc.player.getZ(), v.x, v.z, on ? 1 : 0, mc.player.getViewYRot(1f),
                    frozen ? 1 : 0, mc.player.getViewYRot(0f)};
        });
    }

    /**
     * {entry, done, viewJump} of a trace: the first sample inside the 1x1 block, the first on the point (0.0002) with the velocity
     * the game zeroes next tick, and the largest change of the shown yaw from {@code skip} samples after the align's first
     * tick on (the frames lerp from [7] to [5], so a stale yRotO swings the crosshair); wrapped, the view lets go onto the real
     * yaw, which is the same look a whole turn on (the running yaw is never wrapped).
     */
    static double[] analyze(List<double[]> tr, double tx, double tz, int skip) {
        int entry = -1, done = -1, from = -1;
        for (int i = 0; i < tr.size(); i++) {
            double[] r = tr.get(i);
            if (entry < 0 && Math.abs(r[0] - tx) <= 0.5 && Math.abs(r[1] - tz) <= 0.5) {
                entry = i;
            }
            if (entry >= 0 && done < 0 && Math.abs(r[0] - tx) < 2e-4 && Math.abs(r[1] - tz) < 2e-4
                    && r[2] * r[2] + r[3] * r[3] < 9e-6) {
                done = i;
            }
            if (from < 0 && r[4] == 1) {
                from = i;
            }
        }
        double viewJump = 0;
        for (int i = Math.max(1, from + skip); i < tr.size() && from >= 0; i++) {
            double[] a = tr.get(i - 1), b = tr.get(i);
            viewJump = Math.max(viewJump, Math.max(wrapped(b[5] - a[5]), Math.max(wrapped(b[7] - a[5]), wrapped(b[5] - b[7]))));
        }
        return new double[]{entry, done, viewJump};
    }

    static void release(ClientGameTestContext ctx) {
        ctx.getInput().releaseKey(o -> o.keyUp);
        ctx.getInput().releaseKey(o -> o.keyLeft);
        ctx.getInput().releaseKey(o -> o.keySprint);
    }

    /** He has been in the 1x1 block around (tx, tz) at some sample. */
    private static boolean entered(List<double[]> tr, double tx, double tz) {
        for (double[] r : tr) {
            if (Math.abs(r[0] - tx) <= 0.5 && Math.abs(r[1] - tz) <= 0.5) {
                return true;
            }
        }
        return false;
    }

    static double wrapped(double d) {
        return Math.abs(d - 360 * Math.rint(d / 360));
    }

    /** The last k samples all at the same spot. */
    static boolean same(List<double[]> tr, int k) {
        double[] a = tr.get(tr.size() - 1);
        for (int i = tr.size() - k; i < tr.size() - 1; i++) {
            double[] b = tr.get(i);
            if (b[0] != a[0] || b[1] != a[1]) {
                return false;
            }
        }
        return true;
    }

    static List<String> lines(long mark, String needle) {
        List<String> out = new ArrayList<>();
        for (String l : LogTap.since(mark)) {
            if (l.contains(needle)) {
                out.add(l);
            }
        }
        return out;
    }

    static String tail(String l) {
        int i = l.indexOf("Fast Align #");
        return i < 0 ? l : l.substring(i);
    }
}
