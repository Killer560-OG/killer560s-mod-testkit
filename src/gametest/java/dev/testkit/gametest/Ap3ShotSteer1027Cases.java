package dev.testkit.gametest;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import static dev.testkit.gametest.Ap3RuntimeTests.FLOOR;
import static dev.testkit.gametest.Ap3RuntimeTests.LANE_X0;
import static dev.testkit.gametest.Ap3RuntimeTests.node;
import static dev.testkit.gametest.Ap3RuntimeTests.onClient;
import static dev.testkit.gametest.Ap3RuntimeTests.set;

/**
 * mod ap3-shotsteer-1027 (63-ap3 session lanes, cheat jar, GrimAC on; Session.test fails any flag).
 *
 * <p>killer560, 2026-10-27, his S2 in the sim (Mod Only Test log 00:15:34): "It blinks right but then it falls down when
 * normally it doesn't fall in S2." blinkrelease #39 fired 20 ticks on keys frozen at the release, 18.75 blocks straight
 * off the platform. And: "freeze momentum while it is charging so once the blink is done charging it still has all its
 * momentum even if it freezes during the charge."
 *
 * <p>Each case runs the same route twice in two lanes - once plain (no Blink nodes) and once with them - and records
 * every movement tick's position: the real ticks (END) and every shot tick (Blink's shot-tick END hook), leaving out the
 * ticks a momentum-keeping charge paused. The Blink run's path must lie on the plain run's path.
 * <ul>
 *   <li>1027-ap3-shot-turn: charge 20 (momentum kept), release all, a turning Run 3 blocks on (inside the shot) and a drop
 *       straight ahead past it: he must turn inside the shot and never fall; path within 0.02 of the plain run.</li>
 *   <li>1027-ap3-momentum-ground: sprinting into charge 10, release 10 right after: no movement while banking, 10 ticks of
 *       physics paused, the velocity after the charge equal to the velocity before it, path within 0.02 of the plain run,
 *       the per-tick positions equal to it once the paused ticks are left out.</li>
 *   <li>1027-ap3-momentum-air: the same with a Jump just before the charge, so it banks in mid-jump.</li>
 *   <li>1027-ap3-shot-stop: a Stop 2 blocks into a 20-tick shot: the shot ends when he has stopped, the rest stays banked.</li>
 *   <li>1027-ap3-shot-abort-keep (blinkmerge-1027): AP3 stopped from inside the 5th tick of a 20-tick shot: the shot ends
 *       at its next tick, the other 15 stay banked, and the charge is handed back to the Charge key with AP3's cap
 *       (blink-keep-1027: never drop a blink he did not release).</li>
 * </ul>
 */
final class Ap3ShotSteer1027Cases {

    private static final String CHARGE = "blink.BlinkCharge";
    private static final String CFG = "blink.BlinkConfig";
    private static final String EXEC_SHORT = "ap3.Ap3Executor";

    private Ap3ShotSteer1027Cases() {
    }

    static void register(Session s) {
        s.test("1027-ap3-shot-turn", Ap3ShotSteer1027Cases::shotTurn);
        s.test("1027-ap3-momentum-ground", c -> momentum(c, false));
        s.test("1027-ap3-momentum-air", c -> momentum(c, true));
        s.test("1027-ap3-shot-stop", Ap3ShotSteer1027Cases::shotStop);
        s.test("1027-ap3-shot-abort-keep", Ap3ShotSteer1027Cases::shotAbortKeep);
    }

    // ---------------------------------------------------------------------------------------------------- recorder

    /** One movement tick: where he ended it, and whether it was a shot tick or a tick whose physics was paused. */
    record Pt(double x, double y, double z, double vx, double vy, double vz, boolean shot, boolean frozen) {
    }

    private static final List<Pt> PTS = new ArrayList<>();
    private static volatile boolean rec;
    private static boolean installed;
    private static int lastFrozen;

    private static void install(ClientGameTestContext ctx) {
        onClient(ctx, mc -> {
            if (!installed) {
                installed = true;
                ClientTickEvents.END_CLIENT_TICK.register(m -> {
                    if (rec && m.player != null) {
                        int f = ((Number) Mod.staticCall(EXEC_SHORT, "blinkFrozenTicks")).intValue();
                        boolean frozen = f > lastFrozen; // the mod's count restarts at 0 for each charge
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
            return null;
        });
    }

    private static void add(Minecraft m, boolean shot, boolean frozen) {
        Vec3 p = m.player.position();
        Vec3 v = m.player.getDeltaMovement();
        synchronized (PTS) {
            PTS.add(new Pt(p.x, p.y, p.z, v.x, v.y, v.z, shot, frozen));
        }
    }

    private static void startRec(ClientGameTestContext ctx) {
        onClient(ctx, mc -> {
            synchronized (PTS) {
                PTS.clear();
            }
            lastFrozen = ((Number) Mod.staticCall(EXEC_SHORT, "blinkFrozenTicks")).intValue();
            rec = true;
            return null;
        });
    }

    private static List<Pt> stopRec(ClientGameTestContext ctx) {
        return onClient(ctx, mc -> {
            rec = false;
            synchronized (PTS) {
                return new ArrayList<>(PTS);
            }
        });
    }

    // ---------------------------------------------------------------------------------------------------- shared

    static boolean blinkOn(ClientGameTestContext ctx) {
        return onClient(ctx, mc -> {
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
            return g;
        });
    }

    static void blinkOff(ClientGameTestContext ctx, boolean gate) {
        onClient(ctx, mc -> {
            Ap3RuntimeTests.staticCallStop();
            Mod.staticCall(CHARGE, "reset", "test teardown");
            Mod.set(CFG, "setEnabled", false);
            Mod.staticCall("util.SkyblockGate", "setEnabled", gate);
            return null;
        });
    }

    /** Runs the chain in lane z until AP3 is idle or a Stop is active; returns the recorded movement ticks. */
    static List<Pt> runLane(Session c, double z, Object run, List<Object> rest, int maxTicks) {
        ClientGameTestContext ctx = c.ctx();
        install(ctx);
        // Late in the session he can be too hungry to sprint (one lane sprinted, the next walked - 2026-10-27 full run):
        // both lanes of a case must run on a full food bar.
        c.server().command("effect give @p minecraft:saturation 30 10 true");
        c.server().sync(20);
        Ap3RuntimeTests.walkIntoRun(c, z, run, rest.toArray());
        startRec(ctx);
        // walkIntoRun returns once the run is carrying him; the recording starts there, in both runs alike.
        for (int t = 0; t < maxTicks; t++) {
            ctx.waitTick();
            boolean idle = onClient(ctx, mc -> !(Boolean) dev.testkit.gametest.ModUnderTest.staticCall(Ap3RuntimeTests.EXEC,
                    "isRunning"));
            if (idle && t > 10) {
                break;
            }
        }
        ctx.waitTicks(5);
        return stopRec(ctx);
    }

    /** Points relative to the lane (z - laneZ), frozen ticks left out. */
    private static List<double[]> path(List<Pt> pts, double laneZ) {
        List<double[]> out = new ArrayList<>();
        for (Pt p : pts) {
            if (!p.frozen()) {
                out.add(new double[]{p.x(), p.y(), p.z() - laneZ});
            }
        }
        return out;
    }

    /** The largest distance from any point of {@code a} to the polyline {@code b} (3D). */
    private static double maxOffPath(List<double[]> a, List<double[]> b) {
        double worst = 0;
        for (double[] p : a) {
            double best = Double.MAX_VALUE;
            for (int i = 0; i + 1 < b.size(); i++) {
                best = Math.min(best, segDist(p, b.get(i), b.get(i + 1)));
            }
            worst = Math.max(worst, best);
        }
        return worst;
    }

    private static double segDist(double[] p, double[] a, double[] b) {
        double[] ab = {b[0] - a[0], b[1] - a[1], b[2] - a[2]};
        double[] ap = {p[0] - a[0], p[1] - a[1], p[2] - a[2]};
        double l2 = ab[0] * ab[0] + ab[1] * ab[1] + ab[2] * ab[2];
        double t = l2 < 1e-12 ? 0 : Math.max(0, Math.min(1, (ap[0] * ab[0] + ap[1] * ab[1] + ap[2] * ab[2]) / l2));
        double dx = ap[0] - t * ab[0];
        double dy = ap[1] - t * ab[1];
        double dz = ap[2] - t * ab[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Per-tick difference of two paths, aligned from their first points (frozen ticks already left out). */
    private static double maxTickDiff(List<double[]> a, List<double[]> b, int n) {
        double worst = 0;
        for (int i = 0; i < Math.min(n, Math.min(a.size(), b.size())); i++) {
            double[] p = a.get(i);
            double[] q = b.get(i);
            worst = Math.max(worst, Math.sqrt((p[0] - q[0]) * (p[0] - q[0]) + (p[1] - q[1]) * (p[1] - q[1])
                    + (p[2] - q[2]) * (p[2] - q[2])));
        }
        return worst;
    }

    static List<String> logSince(long mark, String needle) {
        List<String> out = new ArrayList<>();
        for (String l : LogTap.since(mark)) {
            if (l.contains(needle)) {
                out.add(l);
            }
        }
        return out;
    }

    static void serverAgrees(Session c) {
        ClientGameTestContext ctx = c.ctx();
        ctx.waitTicks(10);
        String name = BlinkTests.playerName(ctx);
        double[] sp = BlinkTests.serverPos(c.server(), ctx, name);
        double[] cp = onClient(ctx, mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
        double gap = Math.sqrt(Math.pow(sp[0] - cp[0], 2) + Math.pow(sp[1] - cp[1], 2) + Math.pow(sp[2] - cp[2], 2));
        c.note(String.format(Locale.ROOT, "end: client (%.3f, %.3f, %.3f), server (%.3f, %.3f, %.3f), gap %.4f", cp[0], cp[1],
                cp[2], sp[0], sp[1], sp[2], gap));
        c.check(gap < 0.05, String.format(Locale.ROOT, "the server has him %.3f from the client", gap));
    }

    static void note(Session c, String label, List<Pt> pts, double laneZ) {
        StringBuilder sb = new StringBuilder(label).append(": ");
        int i = 0;
        for (Pt p : pts) {
            sb.append(String.format(Locale.ROOT, "%s(%.3f,%.3f,%.3f)", p.frozen() ? "F" : p.shot() ? "S" : "", p.x(), p.y(),
                    p.z() - laneZ)).append(' ');
            if (++i % 12 == 0) {
                c.note(sb.toString());
                sb.setLength(0);
                sb.append("   ");
            }
        }
        c.note(sb.toString());
    }

    // ---------------------------------------------------------------------------------------------------- cases

    /** A drop for x >= 18 across the lane; the turn at x 15.5 sends him +z, along solid floor. */
    private static void carve(Session c, double z) {
        int zc = (int) Math.floor(z);
        c.server().command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", LANE_X0 + 18, FLOOR - 6, zc - 3,
                LANE_X0 + 44, FLOOR, zc + 3));
        c.server().sync(20);
    }

    private static List<Object> turnRoute(double z, boolean blink) {
        List<Object> nodes = new ArrayList<>();
        if (blink) {
            Object charge = node("BLINK_CHARGE", LANE_X0 + 8.5, z, 0f, 0f, 2.0, 2.0);
            set(charge, "blinkTicks", 20);
            nodes.add(charge);
            nodes.add(node("BLINK_RELEASE", LANE_X0 + 12.5, z, 0f, 0f, 2.0, 2.0));
        }
        nodes.add(node("RUN", LANE_X0 + 15.5, z, 0f, 0f, 2.0, 2.0)); // yaw 0 = +z
        nodes.add(node("STOP", LANE_X0 + 15.5, z + 6.5, 0f, 0f, 2.0, 2.0));
        return nodes;
    }

    private static void shotTurn(Session c) {
        ClientGameTestContext ctx = c.ctx();
        c.server().command("attribute @p minecraft:movement_speed base set 0.10");
        double zPlain = Ap3RuntimeTests.lane();
        double zBlink = Ap3RuntimeTests.lane();
        carve(c, zPlain);
        carve(c, zBlink);
        boolean gate = blinkOn(ctx);
        long mark = LogTap.mark();
        try {
            List<Pt> plain = runLane(c, zPlain, Ap3RuntimeTests.runNode(zPlain), turnRoute(zPlain, false), 200);
            List<Pt> blink = runLane(c, zBlink, Ap3RuntimeTests.runNode(zBlink), turnRoute(zBlink, true), 260);
            note(c, "plain", plain, zPlain);
            note(c, "blink", blink, zBlink);
            for (String l : logSince(mark, "blinkrelease #")) {
                c.note("  " + l);
            }
            for (String l : logSince(mark, "charge SHOT")) {
                c.note("  " + l);
            }
            long shotTicks = blink.stream().filter(Pt::shot).count();
            double minY = blink.stream().mapToDouble(Pt::y).min().orElse(0);
            List<double[]> pp = path(plain, zPlain);
            List<double[]> bp = path(blink, zBlink);
            double off = maxOffPath(bp, pp);
            double endDz = bp.get(bp.size() - 1)[2];
            c.note(String.format(Locale.ROOT, "blink run: %d shot ticks, lowest y %.3f, ended at x %.3f z %+.3f; plain ended at "
                    + "x %.3f z %+.3f; largest distance off the plain path %.4f", shotTicks, minY, bp.get(bp.size() - 1)[0],
                    endDz, pp.get(pp.size() - 1)[0], pp.get(pp.size() - 1)[2], off));
            c.check(shotTicks == 20, "expected 20 shot ticks, saw " + shotTicks);
            c.check(minY > FLOOR + 1 - 0.01, String.format(Locale.ROOT, "he fell to y %.3f", minY));
            c.check(endDz > 4.0, String.format(Locale.ROOT, "he did not take the turn (+z %.3f)", endDz));
            c.check(off < 0.02, String.format(Locale.ROOT, "the blink run left the plain path by %.4f", off));
            serverAgrees(c);
        } finally {
            blinkOff(ctx, gate);
        }
    }

    private static void momentum(Session c, boolean air) {
        ClientGameTestContext ctx = c.ctx();
        c.server().command("attribute @p minecraft:movement_speed base set 0.10");
        double zPlain = Ap3RuntimeTests.lane();
        double zBlink = Ap3RuntimeTests.lane();
        boolean gate = blinkOn(ctx);
        long mark = LogTap.mark();
        long sent0 = onClient(ctx, mc -> ((Number) Mod.staticCall(CHARGE, "frozenTicksSent")).longValue());
        try {
            double cx = LANE_X0 + (air ? 11.5 : 10.5);
            List<Pt> plain = runLane(c, zPlain, Ap3RuntimeTests.runNode(zPlain), route(zPlain, cx, air, false), 200);
            List<Pt> blink = runLane(c, zBlink, Ap3RuntimeTests.runNode(zBlink), route(zBlink, cx, air, true), 260);
            note(c, "plain", plain, zPlain);
            note(c, "blink", blink, zBlink);
            for (String l : logSince(mark, "blinkcharge #")) {
                c.note("  " + l);
            }
            for (String l : logSince(mark, "blinkrelease #")) {
                c.note("  " + l);
            }
            int firstFrozen = -1;
            int lastFrozenIdx = -1;
            int frozen = 0;
            for (int i = 0; i < blink.size(); i++) {
                if (blink.get(i).frozen()) {
                    frozen++;
                    if (firstFrozen < 0) {
                        firstFrozen = i;
                    }
                    lastFrozenIdx = i;
                }
            }
            c.check(firstFrozen > 0, "no tick of his physics was paused");
            Pt before = blink.get(firstFrozen - 1);
            Pt after = blink.get(lastFrozenIdx + 1);
            double still = 0;
            for (int i = firstFrozen; i <= lastFrozenIdx; i++) {
                Pt p = blink.get(i);
                still = Math.max(still, Math.sqrt(Math.pow(p.x() - before.x(), 2) + Math.pow(p.y() - before.y(), 2)
                        + Math.pow(p.z() - before.z(), 2)));
            }
            Pt frozenV = blink.get(lastFrozenIdx);
            double dv = Math.sqrt(Math.pow(frozenV.vx() - before.vx(), 2) + Math.pow(frozenV.vy() - before.vy(), 2)
                    + Math.pow(frozenV.vz() - before.vz(), 2));
            long sent = onClient(ctx, mc -> ((Number) Mod.staticCall(CHARGE, "frozenTicksSent")).longValue()) - sent0;
            long shotTicks = blink.stream().filter(Pt::shot).count();
            c.note(String.format(Locale.ROOT, "paused %d tick(s) (sample %d..%d), moved %.6f meanwhile; velocity before (%.5f, "
                            + "%.5f, %.5f) on the ground %s, at the end of the pause (%.5f, %.5f, %.5f), change %.8f; first tick after "
                            + "moved (%.5f, %.5f, %.5f); %d shot tick(s); %d paused tick(s) went out unbanked", frozen, firstFrozen,
                    lastFrozenIdx, still, before.vx(), before.vy(), before.vz(), !air, frozenV.vx(), frozenV.vy(), frozenV.vz(), dv,
                    after.x() - before.x(), after.y() - before.y(), after.z() - before.z(), shotTicks, sent));
            c.check(frozen == 10, "expected 10 paused ticks, saw " + frozen);
            c.check(still < 1e-9, "he moved while paused");
            c.check(dv < 1e-9, "his velocity changed while paused");
            c.check(sent == 0, sent + " paused tick(s) went out unbanked");
            c.check(shotTicks == 10, "expected a 10-tick shot, saw " + shotTicks);
            if (air) {
                c.check(Math.abs(before.vy()) > 0.01 || before.y() > FLOOR + 1.05, "the charge did not start in the air");
            }
            List<double[]> pp = path(plain, zPlain);
            List<double[]> bp = path(blink, zBlink);
            double off = maxOffPath(bp, pp);
            double tick = maxTickDiff(bp, pp, Math.min(bp.size(), pp.size()) - 3);
            c.note(String.format(Locale.ROOT, "largest distance off the plain path %.6f; largest per-tick difference with the "
                    + "paused ticks left out %.6f (%d vs %d ticks)", off, tick, bp.size(), pp.size()));
            c.check(off < 0.02, String.format(Locale.ROOT, "the blink run left the plain path by %.4f", off));
            serverAgrees(c);
        } finally {
            blinkOff(ctx, gate);
        }
    }

    private static List<Object> route(double z, double cx, boolean air, boolean blink) {
        List<Object> nodes = new ArrayList<>();
        if (air) {
            nodes.add(node("JUMP", LANE_X0 + 8.5, z, 0f, 0f, 1.0, 2.0));
        }
        if (blink) {
            Object charge = node("BLINK_CHARGE", cx, z, 0f, 0f, 2.0, 1.0);
            set(charge, "blinkTicks", 10);
            nodes.add(charge);
            Object rel = node("BLINK_RELEASE", cx + 3, z, 0f, 0f, 2.0, 1.0);
            set(rel, "blinkTicks", 10);
            nodes.add(rel);
        }
        nodes.add(node("STOP", LANE_X0 + 30.5, z, 0f, 0f, 2.0, 2.0));
        return nodes;
    }

    private static void shotStop(Session c) {
        ClientGameTestContext ctx = c.ctx();
        c.server().command("attribute @p minecraft:movement_speed base set 0.10");
        double z = Ap3RuntimeTests.lane();
        boolean gate = blinkOn(ctx);
        long mark = LogTap.mark();
        try {
            List<Object> nodes = new ArrayList<>();
            Object charge = node("BLINK_CHARGE", LANE_X0 + 6.5, z, 0f, 0f, 2.0, 2.0);
            set(charge, "blinkTicks", 20);
            nodes.add(charge);
            nodes.add(node("BLINK_RELEASE", LANE_X0 + 9.5, z, 0f, 0f, 2.0, 2.0));
            nodes.add(node("STOP", LANE_X0 + 11.0, z, 0f, 0f, 2.0, 2.0));
            List<Pt> pts = runLane(c, z, Ap3RuntimeTests.runNode(z), nodes, 200);
            note(c, "run", pts, z);
            for (String l : logSince(mark, "charge SHOT")) {
                c.note("  " + l);
            }
            for (String l : logSince(mark, "blinkrelease #")) {
                c.note("  " + l);
            }
            long shotTicks = pts.stream().filter(Pt::shot).count();
            List<String> early = logSince(mark, "charge SHOT ended early");
            c.note("shot ticks " + shotTicks + ", early end: " + (early.isEmpty() ? "none" : early.get(0)));
            c.check(shotTicks > 0 && shotTicks < 20, "expected the shot to end early, it ran " + shotTicks + " tick(s)");
            c.check(!early.isEmpty() && early.get(0).contains("he has stopped"), "the shot did not end because he stopped");
            c.check(!early.isEmpty() && early.get(0).contains("after " + shotTicks + " of 20 tick(s)")
                    && early.get(0).contains((20 - shotTicks) + " tick(s) kept banked"), "the rest was not kept banked");
            serverAgrees(c);
        } finally {
            blinkOff(ctx, gate);
        }
    }

    /** One-shot: stop AP3 from inside shot tick N (set before the release, cleared when it fires). */
    private static volatile int abortAtShotTick;
    private static volatile int abortShotTicks;
    private static boolean abortHookInstalled;

    private static void shotAbortKeep(Session c) {
        ClientGameTestContext ctx = c.ctx();
        c.server().command("attribute @p minecraft:movement_speed base set 0.10");
        double z = Ap3RuntimeTests.lane();
        boolean gate = blinkOn(ctx);
        long mark = LogTap.mark();
        onClient(ctx, mc -> {
            if (!abortHookInstalled) {
                abortHookInstalled = true;
                Consumer<Minecraft> hook = m -> {
                    if (abortAtShotTick > 0 && ++abortShotTicks == abortAtShotTick) {
                        abortAtShotTick = 0;
                        dev.testkit.gametest.ModUnderTest.staticCall(Ap3RuntimeTests.EXEC, "stop", new Class<?>[]{String.class},
                                new Object[]{"test: stopped inside a shot tick"});
                    }
                };
                Mod.staticCall(CHARGE, "addShotTickEndHook", hook);
            }
            abortShotTicks = 0;
            abortAtShotTick = 5;
            return null;
        });
        try {
            List<Object> nodes = new ArrayList<>();
            Object charge = node("BLINK_CHARGE", LANE_X0 + 6.5, z, 0f, 0f, 2.0, 2.0);
            set(charge, "blinkTicks", 20);
            nodes.add(charge);
            nodes.add(node("BLINK_RELEASE", LANE_X0 + 9.5, z, 0f, 0f, 2.0, 2.0));
            nodes.add(node("STOP", LANE_X0 + 30.5, z, 0f, 0f, 2.0, 2.0));
            List<Pt> pts = runLane(c, z, Ap3RuntimeTests.runNode(z), nodes, 200);
            note(c, "run", pts, z);
            for (String l : logSince(mark, "charge SHOT")) {
                c.note("  " + l);
            }
            List<String> handed = logSince(mark, "charge handed back to the Charge key by AP3");
            long shotTicks = pts.stream().filter(Pt::shot).count();
            List<String> early = logSince(mark, "charge SHOT ended early");
            Object[] st = onClient(ctx, mc -> new Object[]{Mod.staticCall(CHARGE, "isActive"), Mod.staticCall(CHARGE, "banked"),
                    Mod.staticCall(CHARGE, "owner"), Mod.staticCall(CHARGE, "cap"), abortAtShotTick});
            c.note("shot ticks " + shotTicks + ", early end: " + (early.isEmpty() ? "none" : early.get(0)));
            c.note("after: charging " + st[0] + ", bank " + st[1] + ", owner " + st[2] + ", cap " + st[3] + "; hand-back: "
                    + (handed.isEmpty() ? "none" : handed.get(0)));
            c.check((Integer) st[4] == 0, "the test's stop never fired inside the shot");
            c.check(shotTicks == 5, "expected the shot to end after 5 tick(s), it ran " + shotTicks);
            c.check(!early.isEmpty() && early.get(0).contains("after 5 of 20 tick(s): AP3 stopped")
                    && early.get(0).contains("15 tick(s) kept banked"), "the shot did not end at AP3's stop with 15 kept");
            c.check(Boolean.TRUE.equals(st[0]) && ((Number) st[1]).intValue() == 15, "the bank was not kept: charging "
                    + st[0] + ", bank " + st[1]);
            c.check(st[2] == null && ((Number) st[3]).intValue() == 20 && !handed.isEmpty(),
                    "the charge was not handed back to the Charge key with AP3's cap");
            serverAgrees(c);
        } finally {
            abortAtShotTick = 0;
            blinkOff(ctx, gate);
        }
    }
}
