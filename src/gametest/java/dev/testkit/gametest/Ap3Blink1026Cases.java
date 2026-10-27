package dev.testkit.gametest;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static dev.testkit.gametest.Ap3RuntimeTests.LANE_X0;
import static dev.testkit.gametest.Ap3RuntimeTests.node;
import static dev.testkit.gametest.Ap3RuntimeTests.onClient;
import static dev.testkit.gametest.Ap3RuntimeTests.set;

/**
 * mod ap3-blink-1026 - AP3's Blink Charge and Blink Release nodes, in the 63-ap3 session's lanes (cheat jar, GrimAC on;
 * Session.test fails any flag). killer560, 2026-10-26: "add a blink node to ap3. there should be a /ap3 add blinkcharge and
 * /ap3 add blinkrelease. for both if i do /ap3 add blinkcharge 3 it should charge 3 packets while frozen then
 * /blinkrelease 3 would release 3. if no number have it go to the max. it should freeze on that node though for the
 * charge".
 *
 * <p>Every case: a RUN carries him (+x) into a Blink Charge box that also holds a second RUN (it takes over once the
 * charge is done), then on into Blink Release boxes, then a Stop. His real keys are read (no key hooks): the Charge key is
 * unbound in some cases and bound (never pressed) in others, so the nodes are shown to work either way.
 * <ul>
 *   <li>1026-ap3-blink-3: charge 3, release 3. Exactly 3 banked (never more), he stood still on the node while it banked,
 *       one shot of exactly 3 ticks, and the shot moved him 3 times the step he was making each tick just before it
 *       (within 3%), along +x; the server agrees where he ends up; no shot set back.</li>
 *   <li>1026-ap3-blink-max: charge with no number banks the Charge Ticks setting (20); release with no number fires all 20.</li>
 *   <li>1026-ap3-blink-split: charge 10, release 4, release 6: two shots, 4 then 6 ticks, the bank 6 between them and the
 *       charge over after; a third release with nothing banked fires nothing and says why.</li>
 *   <li>1026-ap3-blink-off: Blink switched off: the charge node waits on the node (it does not skip), says so in chat once,
 *       and carries on to a full charge and release when Blink is switched on.</li>
 * </ul>
 */
final class Ap3Blink1026Cases {

    private static final String CHARGE = "blink.BlinkCharge";
    private static final String CFG = "blink.BlinkConfig";
    private static final String EXEC_SHORT = "ap3.Ap3Executor";
    private static final int KEY_V = 86;

    private Ap3Blink1026Cases() {
    }

    static void register(Session s) {
        s.test("1026-ap3-blink-3", c -> chargeRelease(c, 3, new int[]{3}, false, false));
        s.test("1026-ap3-blink-max", c -> chargeRelease(c, 0, new int[]{0}, true, false));
        s.test("1026-ap3-blink-split", c -> chargeRelease(c, 10, new int[]{4, 6}, true, false));
        s.test("1026-ap3-blink-off", c -> chargeRelease(c, 3, new int[]{3}, false, true));
        // killer560, 2026-10-27: "the blink should always be saved through leaps and teleports and stuff like that" - he
        // accepts that GrimAC flags the next shot, so this case records the flags instead of failing on them.
        s.testExpectingFlags("1026-ap3-blink-leap", Ap3Blink1026Cases::chargeLeapRelease);
        // killer560, 2026-10-27 (field report): "if I teleport to someone up there (a leap) that's when it tends to fail".
        // Charge 20, leap, and the release right away (his S2: the release node is where the leap lands) or a little later:
        // the shot must fire all 20, the server must follow the whole shot, and GrimAC must say nothing.
        s.test("1027-ap3-blink-leap-now", c -> leapThenRelease(c, 0));
        s.test("1027-ap3-blink-leap-10", c -> leapThenRelease(c, 10));
        s.test("1027-ap3-blink-leap-40", c -> leapThenRelease(c, 40));
    }

    private static void leapThenRelease(Session c, int delay) {
        ClientGameTestContext ctx = c.ctx();
        double z = Ap3RuntimeTests.lane();
        c.server().command("attribute @p minecraft:movement_speed base set 0.10");
        c.server().sync(20);
        boolean gate = onClient(ctx, mc -> {
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
            double cx = LANE_X0 + 10.5;
            Object run = Ap3RuntimeTests.runNode(z);
            Object charge = node("BLINK_CHARGE", cx, z, 0f, 0f, 2.0, 2.0);
            set(charge, "blinkTicks", 20);
            Object hold = node("BLINK_CHARGE", cx + 30, z, 0f, 0f, 2.0, 2.0);
            Ap3RuntimeTests.walkIntoRun(c, z, run, charge, node("RUN", cx, z, -90f, 0f, 2.0, 2.0), hold);
            Sample s = null;
            for (int t = 0; t < 200; t++) {
                ctx.waitTick();
                s = sample(ctx, t);
                if (s.banked() >= 20 && !"BLINK_CHARGE".equals(s.active())) {
                    break;
                }
            }
            c.check(s != null && s.banked() == 20, "the charge node never banked 20: " + (s == null ? "-" : s.banked()));
            double z2 = z + 12;
            long shots0 = s.shots();
            c.server().command(String.format(Locale.ROOT, "tp @p %.2f %d %.2f -90 0", LANE_X0 + 0.5, Ap3RuntimeTests.FLOOR + 1, z2));
            for (int t = 0; t < 20; t++) {
                ctx.waitTick();
                if (onClient(ctx, mc -> Math.abs(mc.player.getZ() - z2) < 0.6)) {
                    break;
                }
            }
            onClient(ctx, mc -> {
                ModUnderTest.staticCall(Ap3RuntimeTests.EXEC, "stop", new Class<?>[]{String.class}, new Object[]{"you leapt"});
                return null;
            });
            if (delay > 0) {
                ctx.waitTicks(delay);
            }
            double[] srv0 = new double[]{LANE_X0 + 0.5, 0, z2};
            double[] from = onClient(ctx, mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
            Object run2 = Ap3RuntimeTests.runNode(z2);
            Object rel = node("BLINK_RELEASE", LANE_X0 + 0.5, z2, 0f, 0f, 3.0, 3.0);
            set(rel, "blinkTicks", 20);
            Ap3RuntimeTests.walkIntoRun(c, z2, run2, rel, node("STOP", LANE_X0 + 30.5, z2, 0f, 0f, 2.0, 2.0));
            Sample fired = null;
            for (int t = 0; t < 120; t++) {
                ctx.waitTick();
                Sample x = sample(ctx, t);
                if (x.shots() > shots0) {
                    fired = x;
                    break;
                }
            }
            ctx.waitTicks(60);
            int ticks = onClient(ctx, mc -> ((Number) Mod.staticCall(CHARGE, "lastReleaseTicks")).intValue());
            double[] client = onClient(ctx, mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
            double[] srv1 = BlinkTests.serverPos(c.server(), ctx, BlinkTests.playerName(ctx));
            double gap = Math.hypot(srv1[0] - client[0], srv1[2] - client[2]);
            long setBack = onClient(ctx, mc -> ((Number) Mod.staticCall(CHARGE, "shotsSetBack")).longValue());
            c.note(String.format(Locale.ROOT, "leap, release %d tick(s) after: fired %s, %d tick(s); client went %.2f along x, "
                    + "server %.2f; client/server gap %.2f; shots set back so far %d", delay, fired != null, ticks,
                    client[0] - from[0], srv1[0] - srv0[0], gap, setBack));
            for (String l : logSince(mark, "[Blink]")) {
                c.note("  " + l);
            }
            c.check(fired != null && ticks == 20, "the release did not fire 20 after the leap: " + ticks);
            c.check(gap < 0.5 && srv1[0] - srv0[0] > 5.0, String.format(Locale.ROOT, "the server did not follow the shot: "
                    + "server moved %.2f, gap %.2f", srv1[0] - srv0[0], gap));
        } finally {
            onClient(ctx, mc -> {
                Ap3RuntimeTests.staticCallStop();
                Mod.staticCall(CHARGE, "reset", "test teardown");
                Mod.set(CFG, "setEnabled", false);
                Mod.staticCall("util.SkyblockGate", "setEnabled", gate);
                return null;
            });
        }
    }

    /** One tick's reading, taken between client ticks. */
    private record Sample(int t, double x, double y, double z, String active, int banked, long shots, long shotTicks,
                          boolean charging, String owner, long oldestAnswerMs) {
    }

    private static Sample sample(ClientGameTestContext ctx, int t) {
        return onClient(ctx, mc -> {
            Object n = Mod.field(EXEC_SHORT, "activeNode");
            return new Sample(t, mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                    n == null ? "none" : String.valueOf(Ap3RuntimeTests.field(n, "type")),
                    ((Number) Mod.staticCall(CHARGE, "banked")).intValue(),
                    ((Number) Mod.staticCall(CHARGE, "shots")).longValue(),
                    ((Number) Mod.staticCall(CHARGE, "shotTicks")).longValue(),
                    (Boolean) Mod.staticCall(CHARGE, "isActive"),
                    (String) Mod.staticCall(CHARGE, "owner"),
                    ((Number) Mod.staticCall(CHARGE, "oldestAnswerAgeMs")).longValue());
        });
    }

    /**
     * @param chargeTicks the charge node's number, 0 = none (the max)
     * @param releases    each release node's number in order, 0 = none (all)
     * @param keyBound    his Charge key bound (to V, never pressed) instead of unbound
     * @param startOff    Blink starts switched off: the charge must wait, then go once it is on
     */
    private static void chargeRelease(Session c, int chargeTicks, int[] releases, boolean keyBound, boolean startOff) {
        ClientGameTestContext ctx = c.ctx();
        double z = Ap3RuntimeTests.lane();
        c.server().command("attribute @p minecraft:movement_speed base set 0.10");
        c.server().sync(20);
        boolean gate = onClient(ctx, mc -> {
            Mod.staticCall("blink.BlinkFeature", "end", "test setup");
            Mod.staticCall(CHARGE, "reset", "test setup");
            boolean g = (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled");
            Mod.staticCall("util.SkyblockGate", "setEnabled", false);
            // His real keys are read: no key hooks.
            Mod.setField("blink.BlinkFeature", "keyHeldForTest", null);
            Mod.setField(CHARGE, "keyHeldForTest", null);
            Mod.set(CFG, "setEnabled", !startOff);
            Mod.set(CFG, "setChargeMode", Mod.enumValue(CFG + "$Mode", "TOGGLE"));
            Mod.set(CFG, "setChargeTicks", 20);
            Mod.set(CFG, "setChargeKeyCode", keyBound ? KEY_V : -1);
            Mod.set(CFG, "setChatMessages", false);
            return g;
        });
        double cx = LANE_X0 + 10.5;
        Object run = Ap3RuntimeTests.runNode(z);
        Object charge = node("BLINK_CHARGE", cx, z, 0f, 0f, 2.0, 2.0);
        set(charge, "blinkTicks", chargeTicks);
        Object run2 = node("RUN", cx, z, -90f, 0f, 2.0, 2.0);
        List<Object> chain = new ArrayList<>(List.of(charge, run2));
        double[] relX = new double[releases.length + 1];
        for (int i = 0; i < releases.length; i++) {
            relX[i] = cx + 9 + i * 8;
            Object r = node("BLINK_RELEASE", relX[i], z, 0f, 0f, 2.0, 2.0);
            set(r, "blinkTicks", releases[i]);
            chain.add(r);
        }
        boolean emptyRelease = releases.length > 1;
        double lastX = cx + 9 + releases.length * 8;
        if (emptyRelease) {
            // One more after the bank is spent: it must fire nothing and say why.
            chain.add(node("BLINK_RELEASE", lastX, z, 0f, 0f, 2.0, 2.0));
            lastX += 6;
        }
        chain.add(node("STOP", lastX, z, 0f, 0f, 2.0, 2.0));
        long mark = LogTap.mark();
        long shots0 = onClient(ctx, mc -> ((Number) Mod.staticCall(CHARGE, "shots")).longValue());
        long setback0 = onClient(ctx, mc -> ((Number) Mod.staticCall(CHARGE, "shotsSetBack")).longValue());
        List<Sample> samples = new ArrayList<>();
        try {
            Ap3RuntimeTests.walkIntoRun(c, z, run, chain.toArray());
            int t = 0;
            if (startOff) {
                // Blink off: the charge must hold him on the node and wait, not skip.
                c.waitUntil("the Blink Charge node to begin", mc -> "BLINK_CHARGE".equals(activeTypeOn()), 80);
                ctx.waitTicks(60);
                Sample s = sample(ctx, -1);
                c.note(String.format(Locale.ROOT, "60 ticks on the node with Blink off: active %s, charging %s, at x %.3f",
                        s.active(), s.charging(), s.x()));
                c.check("BLINK_CHARGE".equals(s.active()), "with Blink off the route did not wait on the charge node (active "
                        + s.active() + ")");
                c.check(!s.charging(), "Blink is off but Charge is on");
                List<String> said = logSince(mark, "waiting - Blink is off");
                c.note("wait lines: " + said.size() + (said.isEmpty() ? "" : " - " + said.get(0)));
                c.check(said.size() == 1, "the node should say once that it waits for Blink, said " + said.size() + " times");
                ctx.waitTicks(5);
                Sample s2 = sample(ctx, -1);
                c.check(Math.abs(s2.x() - s.x()) < 1e-6, "he moved while the node waited for Blink");
                onClient(ctx, mc -> {
                    Mod.set(CFG, "setEnabled", true);
                    return null;
                });
                c.note("Blink switched on");
            }
            int chargeTarget = chargeTicks > 0 ? chargeTicks : 20;
            boolean done = false;
            for (; t < 400 && !done; t++) {
                ctx.waitTick();
                Sample s = sample(ctx, t);
                samples.add(s);
                done = "STOP".equals(s.active()) || (!(Boolean) onClient(ctx, mc -> (Boolean) dev.testkit.gametest.ModUnderTest
                        .staticCall(Ap3RuntimeTests.EXEC, "isRunning")) && t > 20);
            }
            ctx.waitTicks(20);
            for (String l : logSince(mark, "[AP3] blink")) {
                c.note("  " + l);
            }
            for (String l : logSince(mark, "release asked")) {
                c.note("  " + l);
            }
            for (String l : logSince(mark, "charge SHOT")) {
                c.note("  " + l);
            }

            // ---- the charge: frozen on the node, exactly the target banked, never more
            Sample firstBank = null;
            Sample chargeEnd = null;
            int maxBank = 0;
            for (Sample s : samples) {
                if ("AP3".equals(s.owner())) {
                    maxBank = Math.max(maxBank, s.banked());
                }
                if ("BLINK_CHARGE".equals(s.active()) && s.banked() > 0 && firstBank == null) {
                    firstBank = s;
                }
                if (firstBank != null && chargeEnd == null && !"BLINK_CHARGE".equals(s.active())) {
                    chargeEnd = s;
                }
            }
            c.check(firstBank != null, "the charge node never banked a tick");
            c.check(chargeEnd != null, "the charge node never finished");
            double still = 0;
            int stillTicks = 0;
            for (Sample s : samples) {
                if (s.t() >= firstBank.t() && s.t() < chargeEnd.t()) {
                    still = Math.max(still, Math.hypot(s.x() - firstBank.x(), s.z() - firstBank.z()));
                    stillTicks++;
                }
            }
            int bankAtEnd = chargeEnd.banked();
            c.note(String.format(Locale.ROOT, "charge: banking from tick %d to %d (%d ticks), moved at most %.6f meanwhile; bank "
                    + "when it finished %d, most banked %d", firstBank.t(), chargeEnd.t(), stillTicks, still, bankAtEnd, maxBank));
            c.check(still < 1e-6, String.format(Locale.ROOT, "he moved %.6f blocks while the charge banked", still));
            c.check(bankAtEnd == chargeTarget, "the charge finished with " + bankAtEnd + " banked, expected " + chargeTarget);
            c.check(maxBank == chargeTarget, "the bank reached " + maxBank + ", expected at most " + chargeTarget);
            c.check(logSince(mark, ": charged - " + chargeTarget + " tick(s) banked").size() == 1,
                    "no \"charged - " + chargeTarget + " tick(s) banked\" line");

            // ---- the ping answers keep their delay while he runs charged (a 2026-10-26 bug held them all while moving)
            long delay = Math.min(1050, 50L * chargeTarget - 10);
            long oldest = 0;
            for (Sample s : samples) {
                if (s.t() >= chargeEnd.t() && s.charging()) {
                    oldest = Math.max(oldest, s.oldestAnswerMs());
                }
            }
            c.note("oldest waiting ping answer while running charged: " + oldest + " ms (delay " + delay + " ms)");
            c.check(oldest <= delay + 300, "a ping answer waited " + oldest + " ms while he ran charged (delay " + delay + ")");

            // ---- the releases: one shot each, of exactly the asked ticks, 1 tick's step times that
            List<Integer> shotIdx = new ArrayList<>();
            for (int i = 1; i < samples.size(); i++) {
                if (samples.get(i).shots() > samples.get(i - 1).shots()) {
                    shotIdx.add(i);
                }
            }
            long shots = samples.get(samples.size() - 1).shots() - shots0;
            c.note("shots: " + shots + " at sample(s) " + shotIdx);
            c.check(shots == releases.length, "expected " + releases.length + " shot(s), saw " + shots);
            int bankLeft = chargeTarget;
            for (int r = 0; r < releases.length && r < shotIdx.size(); r++) {
                int i = shotIdx.get(r);
                Sample a = samples.get(i - 2);
                Sample b = samples.get(i - 1);
                Sample s = samples.get(i);
                int want = releases[r] > 0 ? releases[r] : bankLeft;
                long ran = s.shotTicks() - b.shotTicks();
                double step = Math.hypot(b.x() - a.x(), b.z() - a.z());
                double moved = s.x() - b.x();
                // The shot's ticks plus the tick's own move: want + 1 steps at a steady run.
                double expect = (want + 1) * step;
                c.note(String.format(Locale.ROOT, "release %d (asked %s): %d tick(s) ran, bank %d -> %d; step before %.5f, the "
                                + "shot tick moved %.5f along x (expected %.5f = %d x step), z drift %.5f", r + 1,
                        releases[r] > 0 ? String.valueOf(releases[r]) : "all", ran, b.banked(), s.banked(), step, moved, expect,
                        want + 1, s.z() - b.z()));
                c.check(ran == want, "release " + (r + 1) + " ran " + ran + " tick(s), expected " + want);
                c.check(b.banked() == bankLeft && s.banked() == bankLeft - want, "release " + (r + 1) + ": bank " + b.banked()
                        + " -> " + s.banked() + ", expected " + bankLeft + " -> " + (bankLeft - want));
                c.check(step > 0.15, String.format(Locale.ROOT, "he was not running when release %d fired (step %.4f)", r + 1,
                        step));
                c.check(Math.abs(moved - expect) <= 0.03 * expect, String.format(Locale.ROOT, "release %d moved %.4f in its tick, "
                        + "expected %.4f (%d ticks of %.4f)", r + 1, moved, expect, want + 1, step));
                bankLeft -= want;
            }
            Sample last = samples.get(samples.size() - 1);
            c.check(!last.charging(), "Charge is still on after the bank was spent");
            List<String> asked = logSince(mark, "blinkrelease #");
            c.check(!asked.isEmpty() && logSince(mark, "blinkrelease #").stream().anyMatch(l -> l.contains("fired ")),
                    "no blinkrelease \"fired\" line");
            if (emptyRelease) {
                List<String> nothing = logSince(mark, "nothing fired");
                c.note("empty release: " + (nothing.isEmpty() ? "(no line)" : nothing.get(nothing.size() - 1)));
                c.check(nothing.stream().anyMatch(l -> l.contains("[AP3] blinkrelease")), "the release with nothing banked "
                        + "did not say why nothing fired");
            }

            // ---- the server agrees, nothing set back
            ctx.waitTicks(10);
            String name = BlinkTests.playerName(ctx);
            double[] sp = BlinkTests.serverPos(c.server(), ctx, name);
            double[] cp = onClient(ctx, mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
            double gap = Math.sqrt(Math.pow(sp[0] - cp[0], 2) + Math.pow(sp[1] - cp[1], 2) + Math.pow(sp[2] - cp[2], 2));
            long setbacks = onClient(ctx, mc -> ((Number) Mod.staticCall(CHARGE, "shotsSetBack")).longValue()) - setback0;
            c.note(String.format(Locale.ROOT, "end: client (%.3f, %.3f, %.3f), server (%.3f, %.3f, %.3f), gap %.4f; setbacks %d",
                    cp[0], cp[1], cp[2], sp[0], sp[1], sp[2], gap, setbacks));
            c.check(gap < 0.05, String.format(Locale.ROOT, "the server has him %.3f from the client", gap));
            c.check(setbacks == 0, setbacks + " shot(s) set back");
        } finally {
            onClient(ctx, mc -> {
                Ap3RuntimeTests.staticCallStop();
                Mod.staticCall(CHARGE, "reset", "test teardown");
                Mod.set(CFG, "setEnabled", false);
                Mod.set(CFG, "setChargeKeyCode", -1);
                Mod.staticCall("util.SkyblockGate", "setEnabled", gate);
                return null;
            });
        }
    }

    /**
     * 1026-ap3-blink-leap: Blink Charge 10 banks 10, then he leaps (a server teleport 12 blocks away and AP3 stopping
     * "you leapt"). The charge survives with its bank (handed back to his key, cap 10); a later chain's Blink Release 10
     * fires 10 after the leap and the server moves him. GrimAC's flags on that shot are recorded (his decision).
     */
    private static void chargeLeapRelease(Session c) {
        ClientGameTestContext ctx = c.ctx();
        double z = Ap3RuntimeTests.lane();
        c.server().command("attribute @p minecraft:movement_speed base set 0.10");
        c.server().sync(20);
        boolean gate = onClient(ctx, mc -> {
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
            double cx = LANE_X0 + 10.5;
            Object run = Ap3RuntimeTests.runNode(z);
            Object charge = node("BLINK_CHARGE", cx, z, 0f, 0f, 2.0, 2.0);
            set(charge, "blinkTicks", 10);
            Object hold = node("BLINK_CHARGE", cx + 30, z, 0f, 0f, 2.0, 2.0);
            Ap3RuntimeTests.walkIntoRun(c, z, run, charge, node("RUN", cx, z, -90f, 0f, 2.0, 2.0), hold);
            Sample s = null;
            for (int t = 0; t < 200; t++) {
                ctx.waitTick();
                s = sample(ctx, t);
                if (s.banked() >= 10 && !"BLINK_CHARGE".equals(s.active())) {
                    break;
                }
            }
            c.check(s != null && s.banked() == 10, "the charge node never banked 10: " + (s == null ? "-" : s.banked()));
            c.note("charged: bank " + s.banked() + ", owner " + s.owner() + ", active " + s.active());
            // The leap: a server teleport 12 blocks across, and AP3 stopping as it does on "you leapt".
            double z2 = z + 12;
            c.server().command(String.format(Locale.ROOT, "tp @p %.2f %d %.2f -90 0", LANE_X0 + 0.5, Ap3RuntimeTests.FLOOR + 1, z2));
            ctx.waitTicks(3);
            onClient(ctx, mc -> {
                ModUnderTest.staticCall(Ap3RuntimeTests.EXEC, "stop", new Class<?>[]{String.class}, new Object[]{"you leapt"});
                return null;
            });
            ctx.waitTicks(10);
            Sample after = sample(ctx, -1);
            c.note("after the leap: charging " + after.charging() + ", bank " + after.banked() + ", owner " + after.owner());
            c.check(after.charging() && after.banked() == 10, "the leap dropped the charge: charging " + after.charging()
                    + ", bank " + after.banked());
            c.check(after.owner() == null, "the charge was not handed back to his key: owner " + after.owner());
            c.check(!logSince(mark, "handed back to the Charge key by AP3").isEmpty(), "no hand-back line");
            // A later chain's Blink Release 10.
            long shots0 = after.shots();
            Object run2 = Ap3RuntimeTests.runNode(z2);
            Object rel = node("BLINK_RELEASE", cx, z2, 0f, 0f, 2.0, 2.0);
            set(rel, "blinkTicks", 10);
            double[] from = onClient(ctx, mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
            Ap3RuntimeTests.walkIntoRun(c, z2, run2, rel, node("STOP", cx + 12, z2, 0f, 0f, 2.0, 2.0));
            double[] srv0 = BlinkTests.serverPos(c.server(), ctx, BlinkTests.playerName(ctx));
            Sample fired = null;
            for (int t = 0; t < 200; t++) {
                ctx.waitTick();
                Sample x = sample(ctx, t);
                if (x.shots() > shots0) {
                    fired = x;
                    break;
                }
            }
            ctx.waitTicks(20);
            int ticks = onClient(ctx, mc -> ((Number) Mod.staticCall(CHARGE, "lastReleaseTicks")).intValue());
            double[] srv1 = BlinkTests.serverPos(c.server(), ctx, BlinkTests.playerName(ctx));
            double serverMoved = Math.hypot(srv1[0] - srv0[0], srv1[2] - srv0[2]);
            c.note(String.format(Locale.ROOT, "release after the leap: fired %s, %d tick(s); server moved %.2f blocks from the "
                    + "lane start", fired != null, ticks, serverMoved));
            for (String l : logSince(mark, "[Blink]")) {
                c.note("  " + l);
            }
            c.check(fired != null && ticks == 10, "Blink Release 10 did not fire 10 after the leap: " + ticks);
            // GrimAC sets the kept bank's shot back after the leap (owner 2026-10-27 accepts it): recorded, not demanded.
            c.note(String.format(Locale.ROOT, "server moved %.2f after the release (0 = GrimAC set the shot back)", serverMoved));
        } finally {
            onClient(ctx, mc -> {
                Ap3RuntimeTests.staticCallStop();
                Mod.staticCall(CHARGE, "reset", "test teardown");
                Mod.set(CFG, "setEnabled", false);
                Mod.staticCall("util.SkyblockGate", "setEnabled", gate);
                return null;
            });
        }
    }

    private static String activeTypeOn() {
        Object n = Mod.field(EXEC_SHORT, "activeNode");
        return n == null ? "none" : String.valueOf(Ap3RuntimeTests.field(n, "type"));
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
}
