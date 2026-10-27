package dev.testkit.gametest.boss;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 1026-boss-ap3-lights-run (mod ap3-backwards-1026, cheat jar). killer560, 2026-10-26: "sometimes i hit node 16/17 in the
 * ap3 and it makes me run backwards". His live log traced it to the Lights Device's pre-flick: its turn-first turned his
 * BODY to an unlit lever at START, AP3's held walk had picked its keys for the walk's yaw at the END before, so the move
 * went along the lever's yaw instead - and AP3 snapped the body back at END, so the aura turned it again every tick and
 * never got its flick. The same turns mid-align walked him off the ledge at his align #13.
 * <p>
 * His route around the Lights levers (the 846 arena: levers at their real coordinates, z 142): Fast Align #13 at
 * (60.5, 139), then RUN 14-17 at his own points and angles, a STOP further west; three of the six levers unlit, the Lights
 * Aura on with pre-flick. Judged:
 * <ul>
 *   <li>the Fast Align lands within 0.001 and no flick goes out while it runs (the aura waits for AP3's align);</li>
 *   <li>every tick of the held run he moves ALONG the run's direction, never against it (the bug's signature: a tick
 *       end whose step points backwards along the held direction);</li>
 *   <li>the aura did turn him during the run at least once (otherwise nothing was tested), and its flicks went out;</li>
 *   <li>GrimAC silent (Session.test).</li>
 * </ul>
 */
final class Ap3LightsRunCases {

    private static final String EXEC = "ap3.Ap3Executor";
    private static final String NODE = "com.killer560.hub.ap3.Ap3Node";
    private static final String TYPE = "com.killer560.hub.ap3.Ap3Node$Type";
    private static final double Y = 133;

    private Ap3LightsRunCases() {
    }

    static void register(Session s) {
        if (!Mod.isCheat()) {
            return;
        }
        s.test("1026-boss-ap3-lights-run", Ap3LightsRunCases::run);
    }

    static Object node(String type, double x, double z, float yaw, double size) {
        try {
            Class<?> cls = Mod.cls(NODE);
            Object n = cls.getConstructor(Mod.cls(TYPE), double.class, double.class, double.class, float.class, float.class)
                    .newInstance(Mod.enumValue(TYPE, type), x, Y, z, yaw, 0f);
            cls.getField("length").set(n, size);
            cls.getField("width").set(n, size);
            return n;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot make an AP3 " + type + " node", e);
        }
    }

    private static String activeType() {
        Object n = Mod.staticCall(EXEC, "activeNode");
        return n == null ? "none" : String.valueOf(Mod.field(n, "type"));
    }

    static void run(Session c) throws Exception {
        List<AutoCloseable> undo = new ArrayList<>();
        try {
            LightsDeviceCases.baseline(c, undo);
            LightsDeviceCases.arena(c);
            // His route runs west off the 846 arena: more floor, and air over it.
            c.server().command("forceload add 30 134 53 144");
            c.server().sync(20);
            c.server().command("fill 30 132 134 53 132 144 minecraft:stone");
            c.server().command("fill 30 133 134 53 139 144 minecraft:air");
            // Unlit: only the two at x 58 (58,136 and 58,133). With a 3.0 range they are out of reach from the align's point
            // (about 3.4 to their boxes) and in reach along RUN 15-17 (under 2), so the aura's turns land on the RUN -
            // where his backwards steps were - and not all during the align.
            LightsDeviceCases.setLevers(c, 0, 2, 3, 4);
            LightsDeviceCases.waitLevers(c, "four lit, two unlit", 0, 2, 3, 4);
            undo.add(c.onClient(mc -> Mod.with(LightsDeviceCases.CFG, "Range", 3.0)));
            LightsDeviceCases.newRun(c);
            undo.add(c.onClient(mc -> Mod.with("ap3.Ap3Config", "Enabled", true)));
            undo.add(c.onClient(mc -> Mod.with("ap3.Ap3Config", "Strafe45", true)));
            c.onClient(mc -> {
                Mod.staticCall(EXEC, "stop", "test lane");
                Mod.staticCall("ap3.Ap3Feature", "setForceDungeon", true);
                Ap3TermAuraCases.setChain();
                return null;
            });
            TerminalAuraCases.tp(c, 61.2, Y, 139.6, 117f);
            c.ctx().waitTicks(10);
            Object align = node("FAST_ALIGN", 60.5, 139.0, 0f, 2.0);
            Object r14 = node("RUN", 60.5, 139.0, 72f, 1.0);
            Object r15 = node("RUN", 58.5, 139.5, 50f, 1.0);
            Object r16 = node("RUN", 56.0, 141.0, 110f, 1.0);
            Object r17 = node("RUN", 55.5, 141.5, 96.780396f, 1.0);
            Object stop = node("STOP", 42.0, 140.5, 90f, 4.0);
            int flicks0 = LightsDeviceCases.flickCount();
            c.onClient(mc -> {
                Mod.set(LightsDeviceCases.CFG, "setAuraEnabled", true);
                Ap3TermAuraCases.setChain(align, r14, r15, r16, r17, stop);
                return null;
            });
            List<double[]> ticks = new ArrayList<>(); // x, z, holdX, holdZ, onGround, turnOut, flicks, alignActive
            boolean alignSeen = false;
            boolean alignDone = false;
            double[] alignEnd = null;
            int flicksInAlign = -1;
            for (int t = 0; t < 160; t++) {
                c.ctx().waitTick();
                Object[] r = c.onClient(mc -> {
                    Vec3 hold = (Vec3) Mod.field(EXEC, "holdDir");
                    return new Object[]{mc.player.getX(), mc.player.getZ(), hold == null ? 0.0 : hold.x,
                            hold == null ? 0.0 : hold.z, mc.player.onGround(),
                            Mod.staticCall("util.TurnFirst", "anyTurnOut"), LightsDeviceCases.flickCount(), activeType(),
                            Mod.staticCall(EXEC, "isRunning")};
                });
                boolean inAlign = "FAST_ALIGN".equals(r[7]);
                if (inAlign && !alignSeen) {
                    alignSeen = true;
                }
                if (alignSeen && !inAlign && !alignDone) {
                    alignDone = true;
                    alignEnd = new double[]{(Double) r[0], (Double) r[1]};
                    flicksInAlign = (Integer) r[6] - flicks0;
                }
                ticks.add(new double[]{(Double) r[0], (Double) r[1], (Double) r[2], (Double) r[3],
                        (Boolean) r[4] ? 1 : 0, (Boolean) r[5] ? 1 : 0, (Integer) r[6], inAlign ? 1 : 0});
                if (alignDone && !(Boolean) r[8]) {
                    break;
                }
            }
            if (!alignSeen || alignEnd == null) {
                throw new AssertionError("the Fast Align never ran to its end - nothing was tested");
            }
            c.note(String.format(Locale.ROOT, "the align ended at (%.4f, %.4f), %d flick(s) during it", alignEnd[0], alignEnd[1],
                    flicksInAlign));
            int backwards = 0;
            int turnsInRun = 0;
            int holdTicks = 0;
            StringBuilder worst = new StringBuilder();
            for (int i = 1; i < ticks.size(); i++) {
                double[] p = ticks.get(i - 1);
                double[] q = ticks.get(i);
                double hx = p[2];
                double hz = p[3];
                if (hx == 0 && hz == 0) {
                    continue; // no held walk
                }
                holdTicks++;
                if (q[5] > 0 || p[5] > 0) {
                    turnsInRun++;
                }
                double dx = q[0] - p[0];
                double dz = q[1] - p[1];
                double len = Math.hypot(hx, hz);
                double along = (dx * hx + dz * hz) / len;
                if (along < -0.02) {
                    backwards++;
                    if (worst.length() < 600) {
                        worst.append(String.format(Locale.ROOT, " [tick %d: step (%.3f, %.3f) against the run (%.3f, %.3f)]", i,
                                dx, dz, hx / len, hz / len));
                    }
                }
            }
            int flicks = LightsDeviceCases.flickCount() - flicks0;
            c.note(String.format(Locale.ROOT, "held-run ticks %d, ticks with an aura's turn out %d, backwards ticks %d, flicks %d%s",
                    holdTicks, turnsInRun, backwards, flicks, worst));
            c.check(Math.abs(alignEnd[0] - 60.5) <= 1e-3 && Math.abs(alignEnd[1] - 139.0) <= 1e-3,
                    String.format(Locale.ROOT, "the Fast Align ended %.4f / %.4f off its point", alignEnd[0] - 60.5, alignEnd[1] - 139.0));
            c.check(flicksInAlign == 0, "the Lights Aura flicked " + flicksInAlign + " time(s) during the align (it turns him)");
            c.check(holdTicks >= 10, "the held run lasted only " + holdTicks + " ticks - nothing was tested");
            c.check(turnsInRun > 0, "the Lights Aura never turned him during the run - the case did not exercise the bug");
            c.check(backwards == 0, backwards + " tick(s) moved him backwards along the held run:" + worst);
            c.check(flicks >= 1, "the Lights Aura never got a flick out during the route");
        } finally {
            c.onClient(mc -> {
                try {
                    Mod.staticCall(EXEC, "stop", "test end");
                    Ap3TermAuraCases.setChain();
                    Mod.staticCall("ap3.Ap3Feature", "setForceDungeon", false);
                } catch (Throwable t) {
                    System.out.println("[ap3-lights-run] AP3 reset failed: " + t);
                }
                return null;
            });
            c.server().command("fill 30 132 134 53 139 144 minecraft:air");
            c.server().command("forceload remove 30 134 53 144");
            LightsDeviceCases.cleanup(c, undo);
        }
    }
}
