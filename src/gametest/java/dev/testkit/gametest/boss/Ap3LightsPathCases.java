package dev.testkit.gametest.boss;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 1027-boss-ap3-lights-path (mod ap3-levers-1027, cheat jar). killer560, 2026-10-27: "when it looks at stuff like levers
 * and things it can mess with where my ap3 is going. is there a way to make it get levers without actually looking at
 * them?" His align #13 and RUN 14-17 beside the Lights levers (the 1026-boss-ap3-lights-run layout), run TWICE from the
 * same start: once with the Lights Aura off (the path), once with it on and two levers unlit along the run. Judged:
 * <ul>
 *   <li>the levers get flicked during the second run (otherwise nothing was tested);</li>
 *   <li>every tick of the aura run stays within 0.05 blocks of the no-aura path's line (a turn that used W instead of
 *       W+A is 2% slower, so the runs are compared in space; the tick-aligned direction differences are noted);</li>
 *   <li>GrimAC silent (Session.test).</li>
 * </ul>
 */
final class Ap3LightsPathCases {

    private static final String EXEC = "ap3.Ap3Executor";

    private Ap3LightsPathCases() {
    }

    static void register(Session s) {
        if (!Mod.isCheat()) {
            return;
        }
        s.test("1027-boss-ap3-lights-path", Ap3LightsPathCases::run);
    }

    /** Distance from p to the segment a-b, in x/z. */
    private static double segDist(double[] p, double[] a, double[] b) {
        double dx = b[0] - a[0];
        double dz = b[1] - a[1];
        double len2 = dx * dx + dz * dz;
        double t = len2 < 1e-12 ? 0 : Math.max(0, Math.min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dz) / len2));
        return Math.hypot(p[0] - (a[0] + t * dx), p[1] - (a[1] + t * dz));
    }

    private static String activeType() {
        Object n = Mod.staticCall(EXEC, "activeNode");
        return n == null ? "none" : String.valueOf(Mod.field(n, "type"));
    }

    /** One run of the route from the same start; returns the per-tick positions from the tick the align ended. */
    private static List<double[]> runOnce(Session c, boolean aura, int[] flicksOut) {
        c.onClient(mc -> {
            Mod.staticCall(EXEC, "stop", "test lane");
            Ap3TermAuraCases.setChain();
            Mod.set(LightsDeviceCases.CFG, "setAuraEnabled", false);
            return null;
        });
        if (aura) {
            LightsDeviceCases.setLevers(c, 0, 2, 3, 4);
            LightsDeviceCases.waitLevers(c, "four lit, two unlit", 0, 2, 3, 4);
            LightsDeviceCases.newRun(c);
        }
        TerminalAuraCases.tp(c, 61.2, Ap3LightsRunCases.Y, 139.6, 117f);
        c.ctx().waitTicks(20);
        Object align = Ap3LightsRunCases.node("FAST_ALIGN", 60.5, 139.0, 0f, 2.0);
        Object r14 = Ap3LightsRunCases.node("RUN", 60.5, 139.0, 72f, 1.0);
        Object r15 = Ap3LightsRunCases.node("RUN", 58.5, 139.5, 50f, 1.0);
        Object r16 = Ap3LightsRunCases.node("RUN", 56.0, 141.0, 110f, 1.0);
        Object r17 = Ap3LightsRunCases.node("RUN", 55.5, 141.5, 96.780396f, 1.0);
        Object stop = Ap3LightsRunCases.node("STOP", 42.0, 140.5, 90f, 4.0);
        int f0 = LightsDeviceCases.flickCount();
        c.onClient(mc -> {
            Mod.set(LightsDeviceCases.CFG, "setAuraEnabled", aura);
            Ap3TermAuraCases.setChain(align, r14, r15, r16, r17, stop);
            return null;
        });
        List<double[]> path = new ArrayList<>();
        boolean alignSeen = false;
        boolean alignDone = false;
        for (int t = 0; t < 160; t++) {
            c.ctx().waitTick();
            Object[] r = c.onClient(mc -> new Object[]{mc.player.getX(), mc.player.getZ(), activeType(),
                    Mod.staticCall(EXEC, "isRunning")});
            boolean inAlign = "FAST_ALIGN".equals(r[2]);
            alignSeen |= inAlign;
            if (alignSeen && !inAlign) {
                alignDone = true;
            }
            if (alignDone) {
                path.add(new double[]{(Double) r[0], (Double) r[1]});
                if (!(Boolean) r[3]) {
                    break;
                }
            }
        }
        flicksOut[0] = LightsDeviceCases.flickCount() - f0;
        c.onClient(mc -> {
            Mod.set(LightsDeviceCases.CFG, "setAuraEnabled", false);
            return null;
        });
        return path;
    }

    static void run(Session c) throws Exception {
        List<AutoCloseable> undo = new ArrayList<>();
        try {
            LightsDeviceCases.baseline(c, undo);
            LightsDeviceCases.arena(c);
            c.server().command("forceload add 30 134 53 144");
            c.server().sync(20);
            c.server().command("fill 30 132 134 53 132 144 minecraft:stone");
            c.server().command("fill 30 133 134 53 139 144 minecraft:air");
            undo.add(c.onClient(mc -> Mod.with(LightsDeviceCases.CFG, "Range", 3.0)));
            LightsDeviceCases.newRun(c);
            undo.add(c.onClient(mc -> Mod.with("ap3.Ap3Config", "Enabled", true)));
            undo.add(c.onClient(mc -> Mod.with("ap3.Ap3Config", "Strafe45", true)));
            c.onClient(mc -> {
                Mod.staticCall("ap3.Ap3Feature", "setForceDungeon", true);
                return null;
            });
            LightsDeviceCases.setLevers(c, 0, 1, 2, 3, 4, 5); // all lit: nothing for the aura in the first run
            LightsDeviceCases.waitLevers(c, "all lit", 0, 1, 2, 3, 4, 5);
            int[] f = new int[1];
            List<double[]> base = runOnce(c, false, f);
            List<double[]> withAura = runOnce(c, true, f);
            int flicks = f[0];
            int n = Math.min(base.size(), withAura.size());
            int differ = 0;
            double worstDeg = 0;
            double worstSpeed = 0;
            StringBuilder where = new StringBuilder();
            for (int i = 1; i < n; i++) {
                double bx = base.get(i)[0] - base.get(i - 1)[0];
                double bz = base.get(i)[1] - base.get(i - 1)[1];
                double ax = withAura.get(i)[0] - withAura.get(i - 1)[0];
                double az = withAura.get(i)[1] - withAura.get(i - 1)[1];
                double bl = Math.hypot(bx, bz);
                double al = Math.hypot(ax, az);
                if (bl < 0.02 && al < 0.02) {
                    continue;
                }
                double deg = (bl < 1e-6 || al < 1e-6) ? 180.0
                        : Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, (bx * ax + bz * az) / (bl * al)))));
                worstDeg = Math.max(worstDeg, deg);
                worstSpeed = Math.max(worstSpeed, Math.abs(bl - al));
                if (deg > 2.0) {
                    differ++;
                    if (where.length() < 500) {
                        where.append(String.format(Locale.ROOT, " [tick %d: %.1f deg, path (%.3f, %.3f) aura (%.3f, %.3f)]", i, deg,
                                bx, bz, ax, az));
                    }
                }
            }
            // The judgment is spatial: how far each tick of the aura run is from the no-aura path's line (a turn that used W
            // instead of W+A is 2% slower, which shifts ticks along the line but not off it).
            double worstOff = 0;
            int worstOffTick = -1;
            for (int i = 0; i < withAura.size(); i++) {
                double[] p = withAura.get(i);
                double best = Double.MAX_VALUE;
                for (int j = 1; j < base.size(); j++) {
                    best = Math.min(best, segDist(p, base.get(j - 1), base.get(j)));
                }
                if (base.size() == 1) {
                    best = Math.hypot(p[0] - base.get(0)[0], p[1] - base.get(0)[1]);
                }
                if (best > worstOff) {
                    worstOff = best;
                    worstOffTick = i;
                }
            }
            double endGap = n == 0 ? Double.NaN : Math.hypot(base.get(n - 1)[0] - withAura.get(n - 1)[0],
                    base.get(n - 1)[1] - withAura.get(n - 1)[1]);
            c.note(String.format(Locale.ROOT, "path ticks %d / %d; flicks in the aura run %d; farthest the aura run got from the"
                    + " no-aura path: %.4f blocks (tick %d); tick-aligned: %d tick(s) whose direction differs by more than 2"
                    + " degrees (worst %.2f deg), worst per-tick speed difference %.4f, gap at tick %d %.3f%s",
                    base.size(), withAura.size(), flicks, worstOff, worstOffTick, differ, worstDeg, worstSpeed, n - 1, endGap, where));
            c.check(base.size() >= 10, "the no-aura run lasted only " + base.size() + " ticks after the align");
            c.check(flicks >= 1, "the Lights Aura flicked nothing during the AP3 run - nothing was tested");
            c.check(worstOff <= 0.05, String.format(Locale.ROOT, "the aura run left the no-aura path by %.3f blocks at tick %d",
                    worstOff, worstOffTick));
        } finally {
            c.onClient(mc -> {
                try {
                    Mod.staticCall(EXEC, "stop", "test end");
                    Ap3TermAuraCases.setChain();
                    Mod.staticCall("ap3.Ap3Feature", "setForceDungeon", false);
                } catch (Throwable t) {
                    System.out.println("[ap3-lights-path] AP3 reset failed: " + t);
                }
                return null;
            });
            c.server().command("fill 30 132 134 53 139 144 minecraft:air");
            c.server().command("forceload remove 30 134 53 144");
            LightsDeviceCases.cleanup(c, undo);
        }
    }
}
