package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Session;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.testkit.gametest.Ap3RuntimeTests.EXEC;
import static dev.testkit.gametest.Ap3RuntimeTests.LANE_X0;
import static dev.testkit.gametest.Ap3RuntimeTests.node;
import static dev.testkit.gametest.Ap3RuntimeTests.onClient;
import static dev.testkit.gametest.Ap3RuntimeTests.set;

/**
 * mod ap3-s2ee3-1027: the {@code sectiondone:<n>} suffix and the left click on every wait.
 * <p>
 * killer560, 2026-10-27: "add a suffix for leap on section end. So for instance, in S1, this would be after device all
 * four terminals and two levers are done" and "it should be any section not just the one I am actively standing in ...
 * left clicking should override this and ... the term close also for the leap counter suffix, sometimes left clicking
 * didn't make it skip and I would have to walk off the node and back onto it for it to work again."
 * <p>
 * The P3 progress is real server chat (tellraw), read by the mod's own P3SectionTracker; the lanes are not in any S1..S4
 * box, so nothing here can lean on his position.
 * <ul>
 *   <li>1027-ap3-section-s1..s4: sections before N are completed (all objectives, then the gate line); he stands on a
 *       Walk with sectiondone:N. It holds through N's 0/0 (the finished count of N-1 must not answer), through every
 *       count short of n/n and through a forged "[VIP] Bob: a activated a terminal! (n/n)"; it goes the tick the real
 *       n/n line is read.</li>
 *   <li>1027-ap3-section-already: section 1 is complete before he steps on a sectiondone:1 Walk: it goes at once.</li>
 *   <li>1027-ap3-section-click: sectiondone:2 with S1 unfinished; one left click releases it, no swing or dig sent.</li>
 *   <li>1027-ap3-gate-click-repeat: four gated nodes in one box (leapcounter:1, close, sectiondone:3, then a Walk with
 *       leapcounter:2); an inventory opened and closed during the first must not drop it; one click each releases them
 *       in turn; the last is dropped by a stop while he still stands in its box, and one click still fires it. No
 *       swing or dig packet for any of the clicks.</li>
 * </ul>
 */
final class Ap3Gates1027Cases {

    private static final String TRACKER = "com.killer560.hub.leveraura.P3SectionTracker";
    private static final int[] TOTAL = {0, 7, 8, 7, 7};

    private Ap3Gates1027Cases() {
    }

    static void register(Session s) {
        for (int n = 1; n <= 4; n++) {
            int sec = n;
            s.test("1027-ap3-section-s" + n, c -> sectionCase(c, sec));
        }
        s.test("1027-ap3-section-already", Ap3Gates1027Cases::already);
        s.test("1027-ap3-section-click", Ap3Gates1027Cases::sectionClick);
        s.test("1027-ap3-gate-click-repeat", Ap3Gates1027Cases::clickRepeat);
    }

    // ------------------------------------------------------------------------------------------------- helpers

    private static void line(Session c, String text) {
        c.server().command("tellraw @a \"" + text + "\"");
    }

    private static void f7(ClientGameTestContext ctx, boolean on) {
        onClient(ctx, mc -> {
            ModUnderTest.staticCall("com.killer560.hub.secrets.DungeonState", "setRoomSim", new Class<?>[]{boolean.class},
                    new Object[]{on}); // isF7OrM7, which the P3 tracker asks before it reads a line
            return null;
        });
    }

    private static int trackerSection(ClientGameTestContext ctx) {
        return onClient(ctx, mc -> ((Number) ModUnderTest.staticCall(TRACKER, "section")).intValue());
    }

    private static boolean done(ClientGameTestContext ctx, int n) {
        return onClient(ctx, mc -> (Boolean) ModUnderTest.staticCall(TRACKER, "sectionDone", new Class<?>[]{int.class},
                new Object[]{n}));
    }

    /** Goldor's line (a new run), then sections 1..upTo complete: every objective line and the gate line. */
    private static void completeThrough(Session c, int upTo) {
        line(c, "[BOSS] Goldor: Who dares trespass into my domain?");
        c.server().sync(20);
        c.ctx().waitTicks(3);
        for (int k = 1; k <= upTo; k++) {
            int t = TOTAL[k];
            for (int i = 1; i <= t; i++) {
                line(c, "Tester activated a terminal! (" + i + "/" + t + ")");
            }
            if (k <= 3) {
                line(c, "The gate has been destroyed!");
            }
            c.server().sync(20);
            // ChatObserver drops a line identical to one seen in the last 250 ms (a mod re-adding Hypixel's line): two
            // gate lines sent back to back lost the second (first run of s3). Real sections are minutes apart.
            c.ctx().waitTicks(12);
        }
    }

    private static Object gatedNode(ClientGameTestContext ctx) {
        return onClient(ctx, mc -> ModUnderTest.staticCall(EXEC, "leapGateNode"));
    }

    /** Milliseconds of the day from a LogTap line's leading "HH:MM:SS.mmm". */
    private static long ms(String l) {
        String t = l.trim();
        return ((Long.parseLong(t.substring(0, 2)) * 60 + Long.parseLong(t.substring(3, 5))) * 60
                + Long.parseLong(t.substring(6, 8))) * 1000 + Long.parseLong(t.substring(9, 12));
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

    // ------------------------------------------------------------------------------------------------- sections

    private static void sectionCase(Session c, int n) {
        ClientGameTestContext ctx = c.ctx();
        double z = Ap3RuntimeTests.lane();
        double ax = LANE_X0 + 10.5;
        f7(ctx, true);
        long mark = LogTap.mark();
        try {
            completeThrough(c, n - 1);
            int sec = trackerSection(ctx);
            c.note("tracker after completing sections 1.." + (n - 1) + ": section " + sec);
            c.check(sec == n, "the tracker is not in section " + n + " (" + sec + ")");
            Object run = Ap3RuntimeTests.runNode(z);
            Object stop = node("STOP", ax, z, -90f, 0f, 3.0, 3.0);
            Object walk = node("WALK", ax, z, 0f, 0f, 3.0, 3.0);
            set(walk, "sectionDone", n);
            Ap3RuntimeTests.walkIntoRun(c, z, run, stop, walk);
            c.waitUntil("the section gate to hold on the Walk", mc -> ModUnderTest.staticCall(EXEC, "leapGateNode") == walk, 60);
            ctx.waitTicks(10);
            double z0 = onClient(ctx, mc -> mc.player.getZ());
            int t = TOTAL[n];
            // Section n's 0/0: section n-1 finished, and its n/n count must not answer for n.
            c.check(gatedNode(ctx) == walk && !done(ctx, n), "the gate let go at section " + n + "'s 0/0");
            for (int i = 1; i < t; i++) {
                line(c, "Tester activated a terminal! (" + i + "/" + t + ")");
            }
            line(c, "[VIP] Bob: a activated a terminal! (" + t + "/" + t + ")");
            c.server().sync(20);
            ctx.waitTicks(10);
            double zMid = onClient(ctx, mc -> mc.player.getZ());
            c.note(String.format(Locale.ROOT, "after %d/%d and a forged %d/%d: gate on the Walk %s, section %d done %s, moved %.3f",
                    t - 1, t, t, t, gatedNode(ctx) == walk, n, done(ctx, n), zMid - z0));
            c.check(gatedNode(ctx) == walk && Math.abs(zMid - z0) < 1e-3, "the Walk went before section " + n + " was done");
            line(c, "Tester activated a terminal! (" + t + "/" + t + ")");
            int doneAt = -1;
            int releasedAt = -1;
            double zEnd = z0;
            for (int k = 0; k < 60; k++) {
                ctx.waitTick();
                Object[] r = onClient(ctx, mc -> new Object[]{ModUnderTest.staticCall(TRACKER, "sectionDone",
                        new Class<?>[]{int.class}, new Object[]{n}), ModUnderTest.staticCall(EXEC, "leapGateNode") == null,
                        mc.player.getZ()});
                if ((Boolean) r[0] && doneAt < 0) {
                    doneAt = k;
                }
                if ((Boolean) r[1] && releasedAt < 0) {
                    releasedAt = k;
                }
                zEnd = (Double) r[2];
            }
            for (String l : logSince(mark, "section gate")) {
                c.note("  " + l);
            }
            c.note(String.format(Locale.ROOT, "real %d/%d: tracker done at tick %d, gate released at tick %d, walked %+.2f",
                    t, t, doneAt, releasedAt, zEnd - z0));
            c.check(doneAt >= 0, "the tracker never read section " + n + " done");
            c.check(releasedAt >= doneAt && releasedAt <= doneAt + 1, "the gate released at " + releasedAt
                    + ", the section was done at " + doneAt);
            c.check(zEnd - z0 > 2.0, String.format(Locale.ROOT, "the Walk did not go (%.2f)", zEnd - z0));
        } finally {
            onClient(ctx, mc -> {
                Ap3RuntimeTests.staticCallStop();
                return null;
            });
            f7(ctx, false);
        }
    }

    private static void already(Session c) {
        ClientGameTestContext ctx = c.ctx();
        double z = Ap3RuntimeTests.lane();
        double ax = LANE_X0 + 10.5;
        f7(ctx, true);
        try {
            completeThrough(c, 1);
            c.check(done(ctx, 1), "section 1 does not read done after its lines");
            Object run = Ap3RuntimeTests.runNode(z);
            Object stop = node("STOP", ax, z, -90f, 0f, 3.0, 3.0);
            Object walk = node("WALK", ax, z, 0f, 0f, 3.0, 3.0);
            set(walk, "sectionDone", 1);
            Ap3RuntimeTests.walkIntoRun(c, z, run, stop, walk);
            double z0 = z;
            long mark = LogTap.mark();
            double zEnd = z0;
            for (int k = 0; k < 160 && zEnd - z0 <= 2.0; k++) {
                ctx.waitTick();
                zEnd = onClient(ctx, mc -> mc.player.getZ());
            }
            List<String> held = logSince(mark, "holds for section 1");
            List<String> rel = logSince(mark, "section gate: released");
            for (String l : held) {
                c.note("  " + l);
            }
            for (String l : rel) {
                c.note("  " + l);
            }
            c.note(String.format(Locale.ROOT, "section 1 already done: walked %+.2f", zEnd - z0));
            c.check(!held.isEmpty() && !rel.isEmpty() && Math.abs(ms(rel.get(0)) - ms(held.get(0))) < 50,
                    "the gate did not release on the tick it engaged");
            c.check(zEnd - z0 > 2.0, "a sectiondone:1 Walk did not go with section 1 already done");
        } finally {
            onClient(ctx, mc -> {
                Ap3RuntimeTests.staticCallStop();
                return null;
            });
            f7(ctx, false);
        }
    }

    private static AutoCloseable swingTap(AtomicInteger swings, AtomicBoolean watching) {
        var old = dev.testkit.harness.PacketTrace.tap;
        dev.testkit.harness.PacketTrace.tap = p -> {
            if (old != null) {
                old.accept(p);
            }
            if (watching.get() && (p instanceof net.minecraft.network.protocol.game.ServerboundSwingPacket
                    || p instanceof net.minecraft.network.protocol.game.ServerboundPlayerActionPacket)) {
                swings.incrementAndGet();
            }
        };
        return () -> dev.testkit.harness.PacketTrace.tap = old;
    }

    /** One left click (a real attack-key press); the ticks until {@code node} is no longer the gate, or -1. */
    private static int clickRelease(ClientGameTestContext ctx, Object node, int within) {
        ctx.getInput().pressKey(o -> o.keyAttack);
        for (int k = 0; k < within; k++) {
            ctx.waitTick();
            if (gatedNode(ctx) != node) {
                return k;
            }
        }
        return -1;
    }

    private static void sectionClick(Session c) {
        ClientGameTestContext ctx = c.ctx();
        double z = Ap3RuntimeTests.lane();
        double ax = LANE_X0 + 10.5;
        f7(ctx, true);
        AtomicInteger swings = new AtomicInteger();
        AtomicBoolean watching = new AtomicBoolean();
        long mark = LogTap.mark();
        try (AutoCloseable tap = swingTap(swings, watching)) {
            completeThrough(c, 0);
            Object run = Ap3RuntimeTests.runNode(z);
            Object stop = node("STOP", ax, z, -90f, 0f, 3.0, 3.0);
            Object walk = node("WALK", ax, z, 0f, 0f, 3.0, 3.0);
            set(walk, "sectionDone", 2);
            Ap3RuntimeTests.walkIntoRun(c, z, run, stop, walk);
            c.waitUntil("the section gate to hold on the Walk", mc -> ModUnderTest.staticCall(EXEC, "leapGateNode") == walk, 60);
            ctx.waitTicks(10);
            double z0 = onClient(ctx, mc -> mc.player.getZ());
            watching.set(true);
            int rel = clickRelease(ctx, walk, 4);
            ctx.waitTicks(30);
            watching.set(false);
            double zEnd = onClient(ctx, mc -> mc.player.getZ());
            c.note(String.format(Locale.ROOT, "left click: released after %d tick(s), walked %+.2f, swing/dig packets %d", rel,
                    zEnd - z0, swings.get()));
            c.check(rel >= 0 && rel <= 2, "the click did not release the section gate within 2 ticks (" + rel + ")");
            c.check(zEnd - z0 > 2.0, "the Walk did not go after the click");
            c.check(swings.get() == 0, "the skip click reached the server: " + swings.get() + " packet(s)");
            c.check(!logSince(mark, "sectiondone:2 skipped by left click").isEmpty(), "no 'skipped by left click' line");
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            onClient(ctx, mc -> {
                Ap3RuntimeTests.staticCallStop();
                return null;
            });
            f7(ctx, false);
        }
    }

    private static void clickRepeat(Session c) {
        ClientGameTestContext ctx = c.ctx();
        double z = Ap3RuntimeTests.lane();
        double ax = LANE_X0 + 10.5;
        AtomicInteger swings = new AtomicInteger();
        AtomicBoolean watching = new AtomicBoolean();
        long mark = LogTap.mark();
        try (AutoCloseable tap = swingTap(swings, watching)) {
            Object run = Ap3RuntimeTests.runNode(z);
            Object stop = node("STOP", ax, z, -90f, 0f, 3.0, 3.0);
            Object l1 = node("LOOK", ax, z, -90f, 10f, 3.0, 3.0);
            set(l1, "leapCounter", 1);
            Object l2 = node("LOOK", ax, z, -90f, 20f, 3.0, 3.0);
            set(l2, "closeGate", true);
            Object l3 = node("LOOK", ax, z, -90f, 30f, 3.0, 3.0);
            set(l3, "sectionDone", 3);
            Object walk = node("WALK", ax, z, 0f, 0f, 3.0, 3.0);
            set(walk, "leapCounter", 2);
            Ap3RuntimeTests.walkIntoRun(c, z, run, stop, l1, l2, l3, walk);
            Object[] gates = {l1, l2, l3};
            String[] names = {"leapcounter:1 Look", "close Look", "sectiondone:3 Look"};
            for (int i = 0; i < gates.length; i++) {
                Object g = gates[i];
                c.waitUntil("the gate on the " + names[i], mc -> ModUnderTest.staticCall(EXEC, "leapGateNode") == g, 80);
                ctx.waitTicks(5);
                if (i == 0) {
                    // A screen (his chat, the leap menu, a terminal) must not drop a waiting gate: 09:22:39 in his log
                    // dropped leapcounter #37 on "a screen opened", and no click could reach it after.
                    onClient(ctx, mc -> {
                        mc.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player));
                        return null;
                    });
                    ctx.waitTicks(10);
                    onClient(ctx, mc -> {
                        mc.setScreen(null);
                        return null;
                    });
                    ctx.waitTicks(3);
                    boolean kept = gatedNode(ctx) == g;
                    c.note("inventory opened and closed during the leapcounter wait: gate kept " + kept);
                    c.check(kept, "opening a screen dropped the waiting gate");
                }
                watching.set(true);
                int rel = clickRelease(ctx, g, 4);
                watching.set(false);
                c.note(names[i] + ": one click released it after " + rel + " tick(s)");
                c.check(rel >= 0 && rel <= 2, "one click did not release the " + names[i] + " (" + rel + ")");
                ctx.waitTicks(2);
            }
            c.waitUntil("the gate on the leapcounter:2 Walk", mc -> ModUnderTest.staticCall(EXEC, "leapGateNode") == walk, 80);
            ctx.waitTicks(5);
            float pitch = onClient(ctx, mc -> mc.player.getXRot());
            c.note(String.format(Locale.ROOT, "after the three Looks: pitch %.2f", pitch));
            c.check(Math.abs(pitch - 30f) < 0.5f, "the Looks did not all run (pitch " + pitch + ")");
            // A stop while he waits (here forced) drops the gate; he still stands in its box, where nothing re-fires on
            // its own - one click must still fire it.
            onClient(ctx, mc -> {
                ModUnderTest.staticCall(EXEC, "stop", new Class<?>[]{String.class}, new Object[]{"test: dropped while waiting"});
                return null;
            });
            ctx.waitTicks(5);
            boolean idle = onClient(ctx, mc -> !(Boolean) ModUnderTest.staticCall(EXEC, "isRunning"));
            double z0 = onClient(ctx, mc -> mc.player.getZ());
            watching.set(true);
            ctx.getInput().pressKey(o -> o.keyAttack);
            ctx.waitTicks(30);
            watching.set(false);
            double zEnd = onClient(ctx, mc -> mc.player.getZ());
            for (String l : logSince(mark, "left click")) {
                c.note("  " + l);
            }
            c.note(String.format(Locale.ROOT, "dropped gate (AP3 idle %s): one click, walked %+.2f; swing/dig packets %d", idle,
                    zEnd - z0, swings.get()));
            c.check(idle, "the stop did not drop the gate");
            c.check(zEnd - z0 > 2.0, "a click in the dropped gate's box did not fire it");
            c.check(swings.get() == 0, "a skip click reached the server: " + swings.get() + " packet(s)");
            c.check(onClient(ctx, mc -> McCompat.screen(mc) == null), "a screen was left open");
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            onClient(ctx, mc -> {
                if (McCompat.screen(mc) != null) {
                    mc.setScreen(null);
                }
                Ap3RuntimeTests.staticCallStop();
                return null;
            });
        }
    }
}
