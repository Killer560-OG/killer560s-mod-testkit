package dev.testkit.gametest.boss;

import dev.testkit.gametest.BlinkTests;
import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * 1027-boss-ap3-blink-combo (mod ap3-s2ee3-1027, cheat jar), the F7 boss arena on the Hypixel-shaped test server (GrimAC
 * on). killer560, 2026-10-27: "if I have a blink charge blink release, and a run node all in the same block the second I
 * step onto it I should be frozen till blink is charged then the tick it is charged it should release while holding the
 * run." The 63-ap3 lanes check all six orders (1027-ap3-blink-combo-*); this is the same stack in the boss world, off the
 * west side of the 846 arena (the floor Ap3LightsRunCases lays), with the boss world's own dungeon state: a lead-in RUN
 * west, then Blink Charge 20 + Blink Release + RUN west in one 2x2 box, then a Stop.
 * Judged as in the lanes: paused the tick he is in the box (or the next), 20 paused ticks without drift, a shot tick
 * straight after, every shot tick at least 0.2 west, the release line "along the run", and the server's position.
 */
final class Ap3BlinkComboBossCases {

    private static final String EXEC = "ap3.Ap3Executor";
    private static final String CHARGE = "blink.BlinkCharge";
    private static final String CFG = "blink.BlinkConfig";
    private static final double Y = Ap3LightsRunCases.Y;

    private Ap3BlinkComboBossCases() {
    }

    static void register(Session s) {
        if (!Mod.isCheat()) {
            return;
        }
        s.test("1027-boss-ap3-blink-combo", Ap3BlinkComboBossCases::run);
    }

    /** x, z, shot, frozen per movement tick. */
    private static final List<double[]> PTS = new ArrayList<>();
    private static volatile boolean rec;
    private static boolean installed;
    private static int lastFrozen;

    private static List<String> since(long mark, String needle) {
        List<String> out = new ArrayList<>();
        for (String l : LogTap.since(mark)) {
            if (l.contains(needle)) {
                out.add(l);
            }
        }
        return out;
    }

    private static void add(Minecraft m, boolean shot, boolean frozen) {
        Vec3 p = m.player.position();
        synchronized (PTS) {
            PTS.add(new double[]{p.x, p.z, shot ? 1 : 0, frozen ? 1 : 0});
        }
    }

    static void run(Session c) throws Exception {
        List<AutoCloseable> undo = new ArrayList<>();
        boolean gate = false;
        try {
            LightsDeviceCases.baseline(c, undo);
            LightsDeviceCases.arena(c);
            c.server().command("forceload add 30 134 53 144");
            c.server().sync(20);
            c.server().command("fill 30 132 134 53 132 144 minecraft:stone");
            c.server().command("fill 30 133 134 53 139 144 minecraft:air");
            c.server().sync(20);
            LightsDeviceCases.newRun(c);
            undo.add(c.onClient(mc -> Mod.with("ap3.Ap3Config", "Enabled", true)));
            gate = c.onClient(mc -> {
                if (!installed) {
                    installed = true;
                    ClientTickEvents.END_CLIENT_TICK.register(m -> {
                        if (rec && m.player != null) {
                            int f = ((Number) Mod.staticCall(EXEC, "blinkFrozenTicks")).intValue();
                            boolean frozen = f > lastFrozen;
                            lastFrozen = f;
                            add(m, false, frozen);
                        }
                    });
                    Consumer<Minecraft> hook = m -> {
                        if (rec && m.player != null) {
                            add(m, true, false);
                        }
                    };
                    Mod.staticCall(CHARGE, "addShotTickEndHook", hook);
                }
                Mod.staticCall("blink.BlinkFeature", "end", "test setup");
                Mod.staticCall(CHARGE, "reset", "test setup");
                boolean g = (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled");
                Mod.staticCall("util.SkyblockGate", "setEnabled", false);
                Mod.setField("blink.BlinkFeature", "keyHeldForTest", null);
                Mod.setField(CHARGE, "keyHeldForTest", null);
                Mod.set(CFG, "setEnabled", true);
                Mod.set(CFG, "setChargeMode", Mod.enumValue(CFG + "$Mode", "TOGGLE"));
                Mod.set(CFG, "setChargeTicks", 20);
                Mod.set(CFG, "setChargeKeyCode", -1);
                Mod.set(CFG, "setChatMessages", false);
                Mod.staticCall(EXEC, "stop", "test lane");
                Mod.staticCall("ap3.Ap3Feature", "setForceDungeon", true);
                Ap3TermAuraCases.setChain();
                return g;
            });
            c.server().command("effect give @p minecraft:saturation 30 10 true");
            TerminalAuraCases.tp(c, 52.5, Y, 139.5, 90f);
            c.ctx().waitTicks(10);
            Object lead = Ap3LightsRunCases.node("RUN", 51.0, 139.5, 90f, 2.0);
            Object charge = Ap3LightsRunCases.node("BLINK_CHARGE", 46.0, 139.5, 0f, 2.0);
            charge.getClass().getField("blinkTicks").setInt(charge, 20);
            Object release = Ap3LightsRunCases.node("BLINK_RELEASE", 46.0, 139.5, 0f, 2.0);
            Object run = Ap3LightsRunCases.node("RUN", 46.0, 139.5, 90f, 2.0);
            Object stop = Ap3LightsRunCases.node("STOP", 33.0, 139.5, 90f, 2.0);
            c.onClient(mc -> {
                Ap3TermAuraCases.setChain(lead, release, run, charge, stop);
                return null;
            });
            c.ctx().waitTicks(2);
            c.ctx().getInput().holdKey(o -> o.keyUp);
            boolean in = false;
            for (int i = 0; i < 60 && !in; i++) {
                c.ctx().waitTick();
                in = c.onClient(mc -> mc.player.getX() <= 52.0);
            }
            c.ctx().getInput().releaseKey(o -> o.keyUp);
            c.check(in, "the hand walk never reached the lead-in RUN");
            String name = BlinkTests.playerName(c.ctx());
            long mark = LogTap.mark();
            c.onClient(mc -> {
                synchronized (PTS) {
                    PTS.clear();
                }
                lastFrozen = ((Number) Mod.staticCall(EXEC, "blinkFrozenTicks")).intValue();
                rec = true;
                return null;
            });
            for (int t = 0; t < 200; t++) {
                c.ctx().waitTick();
                boolean idle = c.onClient(mc -> !(Boolean) Mod.staticCall(EXEC, "isRunning"));
                if (idle && t > 10) {
                    break;
                }
            }
            c.ctx().waitTicks(5);
            List<double[]> pts = c.onClient(mc -> {
                rec = false;
                synchronized (PTS) {
                    return new ArrayList<>(PTS);
                }
            });
            StringBuilder sb = new StringBuilder("ticks (F paused, S shot): ");
            for (double[] p : pts) {
                sb.append(String.format(Locale.ROOT, "%s(%.3f,%.3f) ", p[3] > 0 ? "F" : p[2] > 0 ? "S" : "", p[0], p[1]));
            }
            c.note(sb.toString());
            for (String l : since(mark, "blinkrelease #")) {
                c.note("  " + l);
            }
            int enter = -1;
            int firstF = -1;
            int lastF = -1;
            int frozen = 0;
            for (int i = 0; i < pts.size(); i++) {
                double[] p = pts.get(i);
                if (enter < 0 && p[2] == 0 && p[0] <= 47.0 && Math.abs(p[1] - 139.5) <= 1.0) {
                    enter = i;
                }
                if (p[3] > 0) {
                    frozen++;
                    if (firstF < 0) {
                        firstF = i;
                    }
                    lastF = i;
                }
            }
            c.note(String.format(Locale.ROOT, "first tick inside the box %d, paused ticks %d (%d..%d)", enter, frozen, firstF, lastF));
            c.check(enter >= 0 && firstF >= 0, "he never entered the box, or never paused");
            c.check(firstF - enter <= 1, "the charge paused him " + (firstF - enter) + " tick(s) after he was in the box");
            c.check(frozen == 20 && lastF - firstF == 19, "expected 20 paused ticks in a row, got " + frozen);
            double drift = 0;
            for (int i = firstF; i <= lastF; i++) {
                drift = Math.max(drift, Math.hypot(pts.get(i)[0] - pts.get(firstF)[0], pts.get(i)[1] - pts.get(firstF)[1]));
            }
            c.check(drift < 1e-6, String.format(Locale.ROOT, "he drifted %.4f while paused", drift));
            c.check(lastF + 1 < pts.size() && pts.get(lastF + 1)[2] > 0, "the tick after the bank was full was not a shot tick");
            int shots = 0;
            double minStep = Double.MAX_VALUE;
            double[] prev = pts.get(lastF);
            for (int i = lastF + 1; i < pts.size() && pts.get(i)[2] > 0; i++) {
                minStep = Math.min(minStep, prev[0] - pts.get(i)[0]);
                prev = pts.get(i);
                shots++;
            }
            c.note(String.format(Locale.ROOT, "shot ticks right after the charge %d, smallest west step %.4f", shots, minStep));
            c.check(shots >= 10 && minStep >= 0.2, "the shot did not carry the run west (" + shots + " ticks, min step " + minStep + ")");
            List<String> rel = since(mark, "blinkrelease #");
            c.check(!rel.isEmpty() && rel.get(0).contains("along the run at yaw 90.0"), "the release did not fire along the Run");
            c.ctx().waitTicks(10);
            double[] sp = BlinkTests.serverPos(c.server(), c.ctx(), name);
            double[] cp = c.onClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
            double gap = Math.sqrt(Math.pow(sp[0] - cp[0], 2) + Math.pow(sp[1] - cp[1], 2) + Math.pow(sp[2] - cp[2], 2));
            c.note(String.format(Locale.ROOT, "server (%.3f, %.3f, %.3f), client (%.3f, %.3f, %.3f), gap %.4f; server moved %.3f west"
                    + " of the box", sp[0], sp[1], sp[2], cp[0], cp[1], cp[2], gap, pts.get(firstF)[0] - sp[0]));
            c.check(gap < 0.05, "the server has him " + gap + " from the client");
            c.check(pts.get(firstF)[0] - sp[0] > 4.0, "the server did not see him carried on by the shot");
        } finally {
            boolean g = gate;
            c.onClient(mc -> {
                try {
                    rec = false;
                    Mod.staticCall(EXEC, "stop", "test end");
                    Ap3TermAuraCases.setChain();
                    Mod.staticCall("ap3.Ap3Feature", "setForceDungeon", false);
                    Mod.staticCall(CHARGE, "reset", "test teardown");
                    Mod.set(CFG, "setEnabled", false);
                    Mod.staticCall("util.SkyblockGate", "setEnabled", g);
                } catch (Throwable t) {
                    System.out.println("[ap3-blink-combo] reset failed: " + t);
                }
                return null;
            });
            c.server().command("fill 30 132 134 53 139 144 minecraft:air");
            c.server().command("forceload remove 30 134 53 144");
            LightsDeviceCases.cleanup(c, undo);
        }
    }
}
