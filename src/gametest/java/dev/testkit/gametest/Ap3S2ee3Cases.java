package dev.testkit.gametest;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static dev.testkit.gametest.Ap3RuntimeTests.EXEC;
import static dev.testkit.gametest.Ap3RuntimeTests.contains;
import static dev.testkit.gametest.Ap3RuntimeTests.onClient;
import static dev.testkit.gametest.Ap3RuntimeTests.set;
import static dev.testkit.gametest.Ap3RuntimeTests.setChain;

/**
 * mod ap3-s2ee3-1027 - killer560, 2026-10-27 on main 3011406a: "my s2 to ee3 ap3 is working now but for some reason the
 * 34 align does not work. it needs to pull me back into it and it is positioned halfway on a stair. if i walk into it
 * then it works" and "sometimes the ap3 will walk off still ... I think it happens when I walk into the align from half
 * a block low."
 * <p>
 * His route's RUN #33 (7, 109, 113, yaw 140) and Fast Align #34 (2.5, 109, 105.5, a 1x1 box) at his heights, shifted
 * +80 in x clear of the lanes. The map around #34 is rebuilt from his own traces (no capture of p3sim exists): a floor at
 * y 109; a stair whose high half (x 2.0-2.5, top 109) carries the point and whose low half (x 2.5-3.0, top 108.5) is the
 * last thing before a pit 3 blocks down (floor y 106, the heights his falls landed on); the floor ends at z 103. The
 * straight line of RUN #33 passes about 1.4 blocks beside #34's box (it never enters it) and runs on to the floor's edge.
 * <ul>
 *   <li>1027-ap3-s2ee3-run-&lt;speed&gt;-&lt;offset&gt;: hand-walked into RUN #33 from 7 lateral offsets at speeds 0.4 and 0.5:
 *       align #34 must run and land within 0.001, and he never goes below 108.5.</li>
 *   <li>1027-ap3-s2ee3-lowstep: #34 started standing on the stair's low half (his 09:25:55 entry): it steps up and
 *       lands.</li>
 *   <li>1027-ap3-s2ee3-stall: #34 (a 3x3 box so he is inside) started at rest where his 09:25:55 align stalled, (2.64,
 *       109, 104.73): it lands.</li>
 *   <li>1027-ap3-s2ee3-blink: Blink Charge on the way, released on RUN #33's line so the shot carries him past #34.</li>
 * </ul>
 */
final class Ap3S2ee3Cases {

    static final double DX = 80;
    static final String CFG = "blink.BlinkConfig";
    static final String CHARGE = "blink.BlinkCharge";

    private Ap3S2ee3Cases() {
    }

    static void register(Session s) {
        for (double speed : new double[]{0.4, 0.5}) {
            for (double off : new double[]{-0.45, -0.3, -0.15, 0.0, 0.15, 0.3, 0.45}) {
                String name = String.format(Locale.ROOT, "1027-ap3-s2ee3-run-%.1f-%+.2f", speed, off);
                s.test(name, c -> run(c, speed, off, false));
            }
        }
        s.test("1027-ap3-s2ee3-lowstep", c -> standing(c, 2.85, 108.5, 106.0, 1.0));
        s.test("1027-ap3-s2ee3-stall", c -> standing(c, 2.64, 109.0, 104.73, 3.0));
        // Leap-in arrivals (killer560: his Blink trouble comes after a leap up there): a teleport of 30+ blocks lands him in
        // #34's box - on its point's stair, and on the low half - with his route armed.
        // The half-low standing start again and again (2026-10-27: intermittent Simulation flags on the first press).
        // Registered only when the -Scenario names them ("1027-ap3-lowrep"), so the whole session stays its usual length.
        if (System.getProperty("testkit.scenario", "").contains("1027-ap3-lowrep")) {
            for (int r = 1; r <= 24; r++) {
                s.test(String.format(Locale.ROOT, "1027-ap3-lowrep-r%02d", r), c -> standing(c, 2.85, 108.5, 106.0, 1.0));
            }
        }
        s.test("1027-ap3-s2ee3-leapin-high", c -> leapIn(c, 2.35, 109.0, 105.3));
        s.test("1027-ap3-s2ee3-leapin-low", c -> leapIn(c, 2.85, 108.5, 105.9));
        s.test("1027-ap3-s2ee3-blink-0.4", c -> run(c, 0.4, 0.0, true));
        s.test("1027-ap3-s2ee3-blink-0.5", c -> run(c, 0.5, 0.15, true));
        // His live route at 15:11 (Mod Only Test): #34 is a 2x2 FAST_ALIGN, a Blink Charge (#49) sits on its point and a
        // leapcounter:3 RUN (#40) after it; boom #39 waits for its gate while he runs on to #34.
        for (String mode : new String[]{"boom", "charge-first", "beside-leap"}) {
            for (double sp : new double[]{0.4, 0.5}) {
                s.test(String.format(Locale.ROOT, "1027-ap3-live34-%s-%.1f", mode, sp), c -> live(c, mode, sp));
            }
        }
        // The two Blink entries again and again (ship2-1027: 26.2 flagged / missed a few of 9), only when named.
        if (System.getProperty("testkit.scenario", "").contains("1027-ap3-blinkrep")) {
            for (int r = 1; r <= 12; r++) {
                s.test(String.format(Locale.ROOT, "1027-ap3-blinkrep-0.4-r%02d", r), c -> run(c, 0.4, 0.0, true));
                s.test(String.format(Locale.ROOT, "1027-ap3-blinkrep-0.5-r%02d", r), c -> run(c, 0.5, 0.15, true));
            }
        }
    }

    private static void cmd(Session c, String f, Object... a) {
        c.server().command(String.format(Locale.ROOT, f, a));
    }

    /** The map around #34 (his coordinates + DX in x). Rebuilt for every case. */
    static void build(Session c) {
        int x0 = (int) DX;
        cmd(c, "forceload add %d %d %d %d", x0 - 8, 96, x0 + 14, 120);
        c.server().sync(20);
        cmd(c, "fill %d 104 98 %d 116 120 minecraft:air", x0 - 8, x0 + 14);
        // Floor at 109 (blocks at y 108): x -4..9, z 103..116.
        cmd(c, "fill %d 108 103 %d 108 116 minecraft:stone", x0 - 4, x0 + 9);
        // The pit east of the stair: x 3..6, z 103..107, floor 3 down (blocks at y 105, top 106).
        cmd(c, "fill %d 106 103 %d 108 107 minecraft:air", x0 + 3, x0 + 6);
        cmd(c, "fill %d 105 103 %d 105 107 minecraft:stone", x0 + 3, x0 + 6);
        // The stair: block x 2, z 103..107, high half west (top 109), low half east (top 108.5). A stair's tall side is
        // toward its facing: facing=west puts it on the west.
        cmd(c, "fill %d 108 103 %d 108 107 minecraft:stone_brick_stairs[facing=west,half=bottom]", x0 + 2, x0 + 2);
        // Under the floor's edge at z 103: nothing for 10 blocks (the drop he must never take).
        c.server().sync(20);
    }

    static void clear(Session c) {
        int x0 = (int) DX;
        cmd(c, "fill %d 104 98 %d 116 120 minecraft:air", x0 - 8, x0 + 14);
        cmd(c, "forceload remove %d %d %d %d", x0 - 8, 96, x0 + 14, 120);
    }

    static Object node(String type, double x, double y, double z, float yaw, double size) {
        Object n = Ap3RuntimeTests.node(type, x + DX, z, yaw, 0f, size, size);
        set(n, "y", y);
        return n;
    }

    private static String activeType() {
        Object n = Mod.field("ap3.Ap3Executor", "activeNode");
        return n == null ? "none" : String.valueOf(Ap3RuntimeTests.field(n, "type"));
    }

    private static boolean running() {
        return (Boolean) ModUnderTest.staticCall(EXEC, "isRunning");
    }

    private record Watch(boolean alignSeen, double minY, double[] end, double closest, int ticks) {
    }

    private static Watch watch(ClientGameTestContext ctx, double px, double pz, int max) {
        boolean seen = false;
        double minY = Double.MAX_VALUE;
        double closest = Double.MAX_VALUE;
        double[] last = null;
        int after = -1;
        int t = 0;
        for (; t < max; t++) {
            ctx.waitTick();
            Object[] r = onClient(ctx, mc -> new Object[]{mc.player.getX(), mc.player.getY(), mc.player.getZ(), activeType(),
                    running()});
            seen |= String.valueOf(r[3]).contains("ALIGN");
            minY = Math.min(minY, (Double) r[1]);
            last = new double[]{(Double) r[0], (Double) r[1], (Double) r[2]};
            closest = Math.min(closest, Math.hypot(last[0] - px, last[2] - pz));
            if (!(Boolean) r[4] && after < 0 && t > 3) {
                after = t;
            }
            if (after >= 0 && t - after >= 10) {
                break;
            }
        }
        return new Watch(seen, minY, last, closest, after < 0 ? -1 : t);
    }

    private static List<String> logSince(long mark, String needle) {
        List<String> out = new ArrayList<>();
        for (String l : LogTap.since(mark)) {
            if (l.contains(needle)) {
                out.add(l);
            }
        }
        return out;
    }

    private static void judge(Session c, String what, Watch w, long mark) {
        double px = 2.5 + DX;
        double pz = 105.5;
        List<String> lines = new ArrayList<>();
        for (String needle : new String[]{"passed beside", "stopped at the edge", "Align #", "blinkrelease", "planning ran out"}) {
            lines.addAll(logSince(mark, needle));
        }
        c.note(String.format(Locale.ROOT, "%s: align seen %s, lowest y %.3f, ended (%.4f, %.3f, %.4f), %.4f from the point, closest"
                + " %.4f", what, w.alignSeen(), w.minY(), w.end()[0], w.end()[1], w.end()[2],
                Math.hypot(w.end()[0] - px, w.end()[2] - pz), w.closest()));
        for (String l : lines) {
            c.note("  " + l);
        }
        // A one-tick align inside a blink shot can fall between two samples: its own "Fast Align #" line counts too.
        boolean alignRan = w.alignSeen() || !logSince(mark, "Fast Align #").isEmpty();
        // The hand-made lead-in (a RUN placed 0.45 to the side of #33's 1x1 box at speed 0.5) can miss #33 itself: then the
        // run is the lead-in's, #34 is not its next node, and the rule is only that he stays on the floor, stopped at the
        // edge. That is the test's own lead-in, not his route (his log shows #33 firing every run).
        boolean missed33 = !alignRan && !logSince(mark, "held walk from Run #1").isEmpty()
                && !logSince(mark, "stopped at the edge").isEmpty();
        if (missed33) {
            c.note("the lead-in missed #33's box; the walk guard stopped him at the edge");
        }
        if (!alignRan && !missed33) {
            throw new AssertionError("align #34 never ran (closest " + String.format(Locale.ROOT, "%.3f", w.closest()) + ")");
        }
        if (w.minY() < 108.5 - 1e-6) {
            throw new AssertionError(String.format(Locale.ROOT, "he went down to y %.3f (lowest allowed 108.5)", w.minY()));
        }
        if (!missed33 && (Math.abs(w.end()[0] - px) > 1e-3 || Math.abs(w.end()[2] - pz) > 1e-3 || Math.abs(w.end()[1] - 109.0) > 1e-6)) {
            throw new AssertionError(String.format(Locale.ROOT, "align #34 did not land: ended (%.4f, %.3f, %.4f)", w.end()[0],
                    w.end()[1], w.end()[2]));
        }
    }

    /**
     * killer560 2026-10-27 15:11 ("that 34 fast align still isnt pulling me in"; "for blink make sure the align takes
     * priority over it charging"). His nodes: #33 RUN, #34 FAST_ALIGN 2x2, #49 BLINK_CHARGE on #34's point, #40 RUN
     * leapcounter:3 on the point.
     * <ul>
     *   <li>boom: a BOOM on the run line before #33 aims at the floor (nothing breaks): its 40-tick gate wait must not
     *       hold #34 (his 15:11:04 and 15:11:40: #34 queued behind the boom, then "superboom didn't break anything"
     *       stopped AP3 and #34 with it).</li>
     *   <li>charge-first: #34's box is 1x1 and the charge's 3x3, so he is in the charge's box before the align's: the
     *       align must land before the charge banks or freezes him.</li>
     *   <li>beside-leap: a teleport puts him 2.1 blocks from #34's point, outside its 2x2 box: it pulls him in.</li>
     * </ul>
     * Judged: #34 lands within 0.001 at y 109, never below 108.5, the charge starts only after the align finished and at
     * its point, AP3 never stopped on the boom, GrimAC clean.
     */
    private static void live(Session c, String mode, double speed) {
        ClientGameTestContext ctx = c.ctx();
        build(c);
        cmd(c, "attribute @p minecraft:movement_speed base set %.2f", speed);
        c.server().sync(20);
        double dx = -Math.sin(Math.toRadians(140)), dz = Math.cos(Math.toRadians(140));
        double sx = 7 + DX - dx * 4.0, sz = 113 - dz * 4.0;
        Object hand = Ap3RuntimeTests.node("RUN", sx + dx * 1.2, sz + dz * 1.2, 140f, 0f, 1.5, 1.5);
        set(hand, "y", 109.0);
        Object boom = Ap3RuntimeTests.node("BOOM", sx + dx * 2.6, sz + dz * 2.6, 140f, 60f, 1.0, 1.0);
        set(boom, "y", 109.0);
        Object n33 = node("RUN", 7, 109, 113, 140f, 1.0);
        Object n34 = node("FAST_ALIGN", 2.5, 109, 105.5, 0f, "charge-first".equals(mode) ? 1.0 : 2.0);
        Object n49 = node("BLINK_CHARGE", 2.5, 109, 105.5, 0f, "charge-first".equals(mode) ? 3.0 : 1.0);
        Object n40 = node("RUN", 2.5, 109, 105.5, 164.5f, 1.0);
        set(n40, "leapCounter", 3);
        boolean gateWas = onClient(ctx, mc -> {
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
        long mark = LogTap.mark();
        try {
            onClient(ctx, mc -> {
                Ap3RuntimeTests.staticCallStop();
                return null;
            });
            List<Object> chain = new ArrayList<>();
            if ("beside-leap".equals(mode)) {
                cmd(c, "forceload add %d %d %d %d", (int) DX, 130, (int) DX + 10, 140);
                cmd(c, "fill %d 108 %d %d 108 %d minecraft:stone", (int) (7 + DX) - 1, 134, (int) (7 + DX) + 1, 136);
                c.server().sync(20);
                cmd(c, "tp @p %.3f 109 %.3f 140 0", 7 + DX, 135.5);
                c.server().sync(20);
                ctx.waitTicks(20);
                chain.addAll(List.of(n33, n34, n49, n40));
                onClient(ctx, mc -> {
                    setChain(chain.toArray());
                    return null;
                });
                ctx.waitTicks(5);
                // 2.1 blocks west of the point, outside the 2x2 box (x 1.5..3.5), on the floor at y 109.
                cmd(c, "tp @p %.3f 109 %.3f 140 0", 0.4 + DX, 105.5);
                c.server().sync(20);
            } else {
                chain.add(hand);
                if ("boom".equals(mode)) {
                    chain.add(boom);
                }
                chain.addAll(List.of(n33, n34, n49, n40));
                cmd(c, "tp @p %.3f 109 %.3f 140 0", sx, sz);
                c.server().sync(20);
                ctx.waitTicks(20);
                onClient(ctx, mc -> {
                    setChain(chain.toArray());
                    return null;
                });
                ctx.waitTicks(2);
                ctx.getInput().holdKey(o -> o.keyUp);
                boolean in = false;
                for (int i = 0; i < 40 && !in; i++) {
                    ctx.waitTick();
                    in = onClient(ctx, mc -> contains(hand, mc.player.position()));
                }
                ctx.getInput().releaseKey(o -> o.keyUp);
                if (!in) {
                    throw new AssertionError("the hand walk never reached the start node");
                }
            }
            Watch w = watch(ctx, 2.5 + DX, 105.5, 120);
            List<String> all = LogTap.since(mark);
            List<String> alignDone = logSince(mark, "Fast Align #");
            List<String> chargeStart = new ArrayList<>(logSince(mark, "charge started"));
            chargeStart.removeIf(l -> !l.contains("blinkcharge #"));
            List<String> boomStop = logSince(mark, "Stopped: superboom");
            for (String needle : new String[]{"boom #", "goes first", "landed you", "passed beside", "charge started"}) {
                for (String l : logSince(mark, needle)) {
                    c.note("  " + l);
                }
            }
            judge(c, String.format(Locale.ROOT, "live %s speed %.1f", mode, speed), w, mark);
            c.check(boomStop.isEmpty(), "AP3 stopped on the boom: " + boomStop);
            c.check(!chargeStart.isEmpty(), "the Blink Charge never started");
            c.check(!alignDone.isEmpty() && all.indexOf(alignDone.get(0)) < all.indexOf(chargeStart.get(0)),
                    "the charge started before Fast Align #34 finished");
            c.check(chargeStart.get(0).contains(String.format(Locale.ROOT, "(%.3f, 109.000, 105.500)", 2.5 + DX)),
                    "the charge did not start on #34's point: " + chargeStart.get(0));
            if ("boom".equals(mode)) {
                c.check(!logSince(mark, "checked in the background").isEmpty() || !logSince(mark, "result: the gate broke").isEmpty(),
                        "the boom's gate check never reported");
            }
        } finally {
            boolean g = gateWas;
            onClient(ctx, mc -> {
                Ap3RuntimeTests.staticCallStop();
                Mod.staticCall("blink.BlinkFeature", "end", "test end");
                Mod.staticCall(CHARGE, "reset", "test end");
                Mod.set(CFG, "setEnabled", false);
                Mod.staticCall("util.SkyblockGate", "setEnabled", g);
                return null;
            });
            cmd(c, "attribute @p minecraft:movement_speed base set 0.10");
            cmd(c, "tp @p 0.50 121 0.50 -90 0");
            c.server().sync(20);
            if ("beside-leap".equals(mode)) {
                cmd(c, "fill %d 108 %d %d 108 %d minecraft:air", (int) (7 + DX) - 1, 134, (int) (7 + DX) + 1, 136);
                cmd(c, "forceload remove %d %d %d %d", (int) DX, 130, (int) DX + 10, 140);
            }
            clear(c);
        }
    }

    private static void run(Session c, double speed, double off, boolean blink) {
        ClientGameTestContext ctx = c.ctx();
        build(c);
        cmd(c, "attribute @p minecraft:movement_speed base set %.2f", speed);
        c.server().sync(20);
        // RUN #33's line: from (7, 113) at yaw 140, dir (-0.643, -0.766); its lateral (+90) is (0.766, -0.643).
        double dx = -Math.sin(Math.toRadians(140)), dz = Math.cos(Math.toRadians(140));
        double lx = -dz, lz = dx;
        double sx = 7 + DX - dx * 3.0 + lx * off, sz = 113 - dz * 3.0 + lz * off;
        Object hand = Ap3RuntimeTests.node("RUN", sx + dx * 1.2, sz + dz * 1.2, 140f, 0f, 1.5, 1.5);
        set(hand, "y", 109.0);
        Object n33 = node("RUN", 7, 109, 113, 140f, 1.0);
        Object n34 = node("FAST_ALIGN", 2.5, 109, 105.5, 0f, 1.0);
        List<Object> chain = new ArrayList<>(List.of(n33, n34));
        boolean gateWas = false;
        if (blink) {
            gateWas = onClient(ctx, mc -> {
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
            // Charge on the hand-walk node's spot, release one block past #33 on its line: the shot runs on past #34.
            Object charge = Ap3RuntimeTests.node("BLINK_CHARGE", sx + dx * 1.2, sz + dz * 1.2, 0f, 0f, 1.5, 1.5);
            set(charge, "y", 109.0);
            Object run2 = Ap3RuntimeTests.node("RUN", sx + dx * 1.2, sz + dz * 1.2, 140f, 0f, 1.5, 1.5);
            set(run2, "y", 109.0);
            Object release = node("BLINK_RELEASE", 7 + dx * 1.0, 109, 113 + dz * 1.0, 0f, 1.0);
            chain = new ArrayList<>(List.of(charge, run2, n33, release, n34));
        }
        long mark = LogTap.mark();
        try {
            onClient(ctx, mc -> {
                Ap3RuntimeTests.staticCallStop();
                return null;
            });
            cmd(c, "tp @p %.3f 109 %.3f 140 0", sx, sz);
            c.server().sync(20);
            ctx.waitTicks(20);
            Object[] all = new Object[chain.size() + 1];
            all[0] = hand;
            for (int i = 0; i < chain.size(); i++) {
                all[i + 1] = chain.get(i);
            }
            onClient(ctx, mc -> {
                setChain(all);
                return null;
            });
            ctx.waitTicks(2);
            ctx.getInput().holdKey(o -> o.keyUp);
            boolean in = false;
            for (int i = 0; i < 40 && !in; i++) {
                ctx.waitTick();
                in = onClient(ctx, mc -> contains(hand, mc.player.position()));
            }
            ctx.getInput().releaseKey(o -> o.keyUp);
            if (!in) {
                throw new AssertionError("the hand walk never reached the start node");
            }
            String who = BlinkTests.playerName(ctx);
            if (blink) {
                cmd(c, "grim consoledebug %s", who); // GrimAC's own P/A/O per tick into the server log
            }
            Watch w;
            try {
                w = watch(ctx, 2.5 + DX, 105.5, 260);
            } finally {
                if (blink) {
                    cmd(c, "grim consoledebug %s", who);
                }
            }
            judge(c, String.format(Locale.ROOT, "%s speed %.1f offset %+.2f", blink ? "blink" : "run", speed, off), w, mark);
        } finally {
            boolean g = gateWas;
            onClient(ctx, mc -> {
                Ap3RuntimeTests.staticCallStop();
                if (blink) {
                    Mod.staticCall("blink.BlinkFeature", "end", "test end");
                    Mod.staticCall(CHARGE, "reset", "test end");
                    Mod.set(CFG, "setEnabled", false);
                    Mod.staticCall("util.SkyblockGate", "setEnabled", g);
                }
                return null;
            });
            cmd(c, "attribute @p minecraft:movement_speed base set 0.10");
            cmd(c, "tp @p 0.50 121 0.50 -90 0");
            c.server().sync(20);
            clear(c);
        }
    }

    private static void leapIn(Session c, double x, double y, double z) {
        ClientGameTestContext ctx = c.ctx();
        build(c);
        cmd(c, "attribute @p minecraft:movement_speed base set 0.45");
        long mark = LogTap.mark();
        try {
            onClient(ctx, mc -> {
                Ap3RuntimeTests.staticCallStop();
                return null;
            });
            // Start 30 blocks away with his route armed, then the teleport (a leap moves him 8+ blocks in one tick). He
            // stands on a pad there (the first version dropped him into unloaded air at z 135 - Grim Simulation flags).
            cmd(c, "forceload add %d %d %d %d", (int) DX, 130, (int) DX + 10, 140);
            cmd(c, "fill %d 108 %d %d 108 %d minecraft:stone", (int) (7 + DX) - 1, 134, (int) (7 + DX) + 1, 136);
            c.server().sync(20);
            cmd(c, "tp @p %.3f 109 %.3f 140 0", 7 + DX, 135.5);
            c.server().sync(20);
            ctx.waitTicks(20);
            Object n33 = node("RUN", 7, 109, 113, 140f, 1.0);
            Object n34 = node("FAST_ALIGN", 2.5, 109, 105.5, 0f, 1.0);
            onClient(ctx, mc -> {
                setChain(n33, n34);
                return null;
            });
            ctx.waitTicks(5);
            cmd(c, "tp @p %.3f %.3f %.3f 140 0", x + DX, y, z);
            c.server().sync(20);
            Watch w = watch(ctx, 2.5 + DX, 105.5, 160);
            judge(c, String.format(Locale.ROOT, "leap-in to (%.2f, %.2f, %.2f)", x, y, z), w, mark);
        } finally {
            onClient(ctx, mc -> {
                Ap3RuntimeTests.staticCallStop();
                return null;
            });
            cmd(c, "attribute @p minecraft:movement_speed base set 0.10");
            cmd(c, "tp @p 0.50 121 0.50 -90 0");
            c.server().sync(20);
            cmd(c, "fill %d 108 %d %d 108 %d minecraft:air", (int) (7 + DX) - 1, 134, (int) (7 + DX) + 1, 136);
            cmd(c, "forceload remove %d %d %d %d", (int) DX, 130, (int) DX + 10, 140);
            clear(c);
        }
    }

    private static void standing(Session c, double x, double y, double z, double box) {
        ClientGameTestContext ctx = c.ctx();
        build(c);
        cmd(c, "attribute @p minecraft:movement_speed base set 0.45");
        long mark = LogTap.mark();
        try {
            onClient(ctx, mc -> {
                Ap3RuntimeTests.staticCallStop();
                return null;
            });
            cmd(c, "tp @p %.3f %.3f %.3f 140 0", x + DX, y, z);
            c.server().sync(20);
            ctx.waitTicks(25);
            Object n34 = node("FAST_ALIGN", 2.5, 109, 105.5, 0f, box);
            onClient(ctx, mc -> {
                setChain(n34);
                return null;
            });
            // GrimAC's own per-tick prediction (P predicted, A actual, O offset) into the server log for this align.
            String who = BlinkTests.playerName(ctx);
            cmd(c, "grim consoledebug %s", who);
            Watch w;
            try {
                w = watch(ctx, 2.5 + DX, 105.5, 160);
            } finally {
                cmd(c, "grim consoledebug %s", who);
            }
            judge(c, String.format(Locale.ROOT, "standing start (%.2f, %.2f, %.2f), box %.0f", x, y, z, box), w, mark);
        } finally {
            onClient(ctx, mc -> {
                Ap3RuntimeTests.staticCallStop();
                return null;
            });
            cmd(c, "attribute @p minecraft:movement_speed base set 0.10");
            cmd(c, "tp @p 0.50 121 0.50 -90 0");
            c.server().sync(20);
            clear(c);
        }
    }
}
