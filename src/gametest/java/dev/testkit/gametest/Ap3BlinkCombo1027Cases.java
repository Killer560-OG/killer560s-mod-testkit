package dev.testkit.gametest;

import dev.testkit.gametest.hx.Session;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static dev.testkit.gametest.Ap3RuntimeTests.LANE_X0;
import static dev.testkit.gametest.Ap3RuntimeTests.node;
import static dev.testkit.gametest.Ap3RuntimeTests.onClient;
import static dev.testkit.gametest.Ap3RuntimeTests.set;

/**
 * mod ap3-s2ee3-1027. killer560, 2026-10-27: "if I have a blink charge blink release, and a run node all in the same block
 * the second I step onto it I should be frozen till blink is charged then the tick it is charged it should release while
 * holding the run."
 * <p>
 * A Blink Charge (20, momentum kept), a Blink Release (all) and a Run along the lane share one 2x2 box 5 blocks into the
 * lane; a Stop 22 blocks on ends the route. The lead-in is the lane's run (sprinting). Every movement tick is recorded
 * (Ap3ShotSteer1027Cases' recorder: real ticks, shot ticks, physics-paused ticks). Each case adds the three nodes in a
 * different order (all six), since the order he placed them in must not matter.
 * <ul>
 *   <li>Frozen at once: the first paused tick is the tick he is first inside the box, or the one after (the box is
 *       seen at END; the charge pauses from the next tick); no position change across the paused ticks.</li>
 *   <li>20 paused ticks, then the very next recorded tick is a shot tick: no idle tick between full and release.</li>
 *   <li>The release fires along the Run: its log line says "along the run" at the Run's yaw, and every shot tick moves
 *       him at least 0.2 blocks along +x (the run's keys, not a standing release).</li>
 *   <li>GrimAC clean (Session.test), and the server has him where the client does, with the server's own distance from
 *       the box to the end over 4 blocks.</li>
 * </ul>
 */
final class Ap3BlinkCombo1027Cases {

    private Ap3BlinkCombo1027Cases() {
    }

    private static final String[][] ORDERS = {
            {"C", "R", "N"}, {"C", "N", "R"}, {"R", "C", "N"}, {"R", "N", "C"}, {"N", "C", "R"}, {"N", "R", "C"}};

    static void register(Session s) {
        for (String[] o : ORDERS) {
            String name = "1027-ap3-blink-combo-" + String.join("", o).toLowerCase(Locale.ROOT);
            s.test(name, c -> combo(c, o));
        }
    }

    private static void combo(Session c, String[] order) {
        ClientGameTestContext ctx = c.ctx();
        c.server().command("attribute @p minecraft:movement_speed base set 0.10");
        double z = Ap3RuntimeTests.lane();
        boolean gate = Ap3ShotSteer1027Cases.blinkOn(ctx);
        long mark = LogTap.mark();
        double bx = LANE_X0 + 8.0; // the box: x 7..9
        try {
            List<Object> nodes = new ArrayList<>();
            for (String k : order) {
                switch (k) {
                    case "C" -> {
                        Object charge = node("BLINK_CHARGE", bx, z, 0f, 0f, 2.0, 2.0);
                        set(charge, "blinkTicks", 20);
                        nodes.add(charge);
                    }
                    case "R" -> nodes.add(node("BLINK_RELEASE", bx, z, 0f, 0f, 2.0, 2.0));
                    default -> nodes.add(node("RUN", bx, z, -90f, 0f, 2.0, 2.0));
                }
            }
            nodes.add(node("STOP", LANE_X0 + 30.5, z, 0f, 0f, 2.0, 2.0));
            String name = BlinkTests.playerName(ctx);
            List<Ap3ShotSteer1027Cases.Pt> pts = Ap3ShotSteer1027Cases.runLane(c, z, Ap3RuntimeTests.runNode(z), nodes, 260);
            Ap3ShotSteer1027Cases.note(c, "order " + String.join(",", order) + " (F paused, S shot)", pts, z);
            for (String l : Ap3ShotSteer1027Cases.logSince(mark, "blinkrelease #")) {
                c.note("  " + l);
            }
            for (String l : Ap3ShotSteer1027Cases.logSince(mark, "blinkcharge #")) {
                c.note("  " + l);
            }
            int enter = -1;
            int firstF = -1;
            int lastF = -1;
            int frozen = 0;
            for (int i = 0; i < pts.size(); i++) {
                Ap3ShotSteer1027Cases.Pt p = pts.get(i);
                if (enter < 0 && !p.shot() && p.x() >= bx - 1.0 && Math.abs(p.z() - z) <= 1.0) {
                    enter = i;
                }
                if (p.frozen()) {
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
            c.check(frozen == 20 && lastF - firstF == 19, "expected 20 paused ticks in a row, got " + frozen + " over "
                    + (lastF - firstF + 1));
            double drift = 0;
            for (int i = firstF; i <= lastF; i++) {
                Ap3ShotSteer1027Cases.Pt p = pts.get(i);
                Ap3ShotSteer1027Cases.Pt a = pts.get(firstF);
                drift = Math.max(drift, Math.hypot(p.x() - a.x(), p.z() - a.z()));
            }
            c.check(drift < 1e-6, String.format(Locale.ROOT, "he drifted %.4f while paused", drift));
            c.check(lastF + 1 < pts.size() && pts.get(lastF + 1).shot(), "the tick after the bank was full was not a shot tick");
            int shots = 0;
            double minStep = Double.MAX_VALUE;
            Ap3ShotSteer1027Cases.Pt prev = pts.get(lastF);
            for (int i = lastF + 1; i < pts.size() && pts.get(i).shot(); i++) {
                Ap3ShotSteer1027Cases.Pt p = pts.get(i);
                minStep = Math.min(minStep, p.x() - prev.x());
                prev = p;
                shots++;
            }
            c.note(String.format(Locale.ROOT, "shot ticks right after the charge %d, smallest +x step %.4f", shots, minStep));
            c.check(shots >= 10, "the shot after the charge ran only " + shots + " tick(s)");
            c.check(minStep >= 0.2, String.format(Locale.ROOT, "a shot tick moved only %.4f along the run", minStep));
            List<String> rel = Ap3ShotSteer1027Cases.logSince(mark, "blinkrelease #");
            c.check(!rel.isEmpty() && rel.get(0).contains("along the run at yaw -90.0"), "the release did not fire along the Run");
            Ap3ShotSteer1027Cases.serverAgrees(c);
            double[] sp = BlinkTests.serverPos(c.server(), ctx, name);
            c.note(String.format(Locale.ROOT, "server: from the box (x %.2f) to x %.3f", pts.get(firstF).x(), sp[0]));
            c.check(sp[0] - pts.get(firstF).x() > 4.0, "the server did not see him carried on by the shot");
        } finally {
            Ap3ShotSteer1027Cases.blinkOff(ctx, gate);
        }
    }
}
