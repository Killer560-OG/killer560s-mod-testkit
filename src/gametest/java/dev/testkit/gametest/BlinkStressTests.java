package dev.testkit.gametest;

import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.LagSpike;
import dev.testkit.harness.Latency;
import dev.testkit.harness.PacketTrace;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.function.Consumer;

import static dev.testkit.gametest.BlinkTests.AOTV;
import static dev.testkit.gametest.BlinkTests.CFG;
import static dev.testkit.gametest.BlinkTests.CHARGE;
import static dev.testkit.gametest.BlinkTests.FEATURE;
import static dev.testkit.gametest.BlinkTests.MODE;
import static dev.testkit.gametest.BlinkTests.SURFACE_Y;
import static dev.testkit.gametest.BlinkTests.arena;
import static dev.testkit.gametest.BlinkTests.armed;
import static dev.testkit.gametest.BlinkTests.chargeOff;
import static dev.testkit.gametest.BlinkTests.chargeOn;
import static dev.testkit.gametest.BlinkTests.charging;
import static dev.testkit.gametest.BlinkTests.check;
import static dev.testkit.gametest.BlinkTests.cnum;
import static dev.testkit.gametest.BlinkTests.firstChecks;
import static dev.testkit.gametest.BlinkTests.flat;
import static dev.testkit.gametest.BlinkTests.fmt;
import static dev.testkit.gametest.BlinkTests.holdDown;
import static dev.testkit.gametest.BlinkTests.holdUp;
import static dev.testkit.gametest.BlinkTests.holding;
import static dev.testkit.gametest.BlinkTests.look;
import static dev.testkit.gametest.BlinkTests.num;
import static dev.testkit.gametest.BlinkTests.playerName;
import static dev.testkit.gametest.BlinkTests.pressCharge;
import static dev.testkit.gametest.BlinkTests.releaseKeys;
import static dev.testkit.gametest.BlinkTests.serverPos;
import static dev.testkit.gametest.BlinkTests.setUp;
import static dev.testkit.gametest.BlinkTests.shootByPress;
import static dev.testkit.gametest.BlinkTests.tearDown;

/**
 * 1026-blink-* (killer560, 2026-10-26: "test [Blink] in every way imaginable to be sure that it wont break or flag or
 * anything"). The stress set on top of 1025-blink-*: every tick setting of both parts, key mashing, both keys at once,
 * switching off mid-hold and mid-charge, screens, terrain and effects, items and abilities, death, disconnects, kicks,
 * dimension changes, latency, lag spikes and long frames, setbacks, long sessions with queue/memory checks, the commands,
 * the log, and Blink's absence from a legit jar.
 *
 * <p>Every case proves Blink acted (holds released, ticks banked and shot, distance moved, the server's own position)
 * before anything about GrimAC is said. Cases that walk several sub-cases record the flags per sub-case
 * ({@link Scenario#runExpectingFlags}) and then fail on any, naming which.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class BlinkStressTests implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext ctx) {
        AssertionError first = null;
        for (Consumer<ClientGameTestContext> c : List.<Consumer<ClientGameTestContext>>of(
                BlinkStressTests::legitAbsent,
                BlinkStressTests::holdSweepAll, BlinkStressTests::chargeSweepAll, BlinkStressTests::chargeSweepHoldMode,
                BlinkStressTests::mash, BlinkStressTests::chargeMash, BlinkStressTests::bothKeys,
                BlinkStressTests::offMid, BlinkStressTests::screens, BlinkStressTests::terrainA, BlinkStressTests::terrainB,
                BlinkStressTests::itemsA, BlinkStressTests::itemsB, BlinkStressTests::death, BlinkStressTests::disconnect,
                BlinkStressTests::dimensionCharge, BlinkStressTests::latencyMatrix, BlinkStressTests::lag,
                BlinkStressTests::setbackShot, BlinkStressTests::lcew, BlinkStressTests::ui,
                BlinkStressTests::longHolds, BlinkStressTests::longShots, BlinkStressTests::longCharge,
                BlinkStressTests::releaseLog)) {
            try {
                c.accept(ctx);
            } catch (AssertionError e) {
                first = first == null ? e : first;
            }
        }
        if (first != null) {
            throw first;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Helpers

    /** One sub-case's verdict in a multi-part case: flags since the last mark, then a fresh mark. */
    private static final class Rows {
        final List<String> rows = new ArrayList<>();
        final List<String> bad = new ArrayList<>();

        void add(TestServer server, Scenario scenario, String label, boolean ok, String detail) {
            List<String> flags = scenario.flags();
            boolean clean = flags.isEmpty();
            String row = String.format(Locale.ROOT, "%-28s %s %s; %s", label, ok ? "acted" : "DID NOT ACT", detail,
                    clean ? "clean" : flags.size() + " flag(s): " + firstChecks(flags));
            rows.add(row);
            scenario.log(row);
            if (!ok || !clean) {
                bad.add(label + (clean ? "" : " (flagged)") + (ok ? "" : " (did not act)"));
            }
            server.mark();
        }

        void finish(Scenario scenario, String name) {
            scenario.log("SUMMARY " + name + ": " + rows.size() + " part(s), bad " + bad);
            for (String r : rows) {
                scenario.log("  " + r);
            }
            check(!rows.isEmpty(), name + " ran no parts");
            check(bad.isEmpty(), name + ": " + bad);
        }
    }

    private static void tp(TestServer server, ClientGameTestContext ctx, double x, double y, double z, float yaw) {
        server.command(String.format(Locale.ROOT, "tp %s %.2f %.2f %.2f %.1f 0", playerName(ctx), x, y, z, yaw));
        ctx.waitTicks(20);
    }

    private static double[] here(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
    }

    private static double dist3(double[] a, double[] b) {
        return Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]));
    }

    /** Client and server agree on where he is (after things settle). */
    private static double gap(TestServer server, ClientGameTestContext ctx) {
        ctx.waitTicks(10);
        double[] srv = serverPos(server, ctx, playerName(ctx));
        return dist3(here(ctx), srv);
    }

    /** A sprinting hold until Hold Ticks lets go (the key then comes up). Returns {client moved, ticks, packets}. */
    private static double[] sprintHold(ClientGameTestContext ctx, boolean sprint, int maxTicks) {
        ctx.getInput().holdKey(o -> o.keyUp);
        if (sprint) {
            ctx.getInput().holdKey(o -> o.keySprint);
        }
        ctx.waitTicks(4);
        long holds0 = num(ctx, "releasedHolds");
        double[] a = here(ctx);
        holdDown(ctx);
        ctx.waitTicks(2);
        for (int i = 0; i < maxTicks + 6 && holding(ctx); i++) {
            ctx.waitTicks(1);
        }
        holdUp(ctx);
        double[] b = here(ctx);
        releaseKeys(ctx);
        ctx.waitTicks(3);
        boolean released = num(ctx, "releasedHolds") > holds0;
        return new double[]{released ? dist3(a, b) : -1, num(ctx, "lastHoldTicks"), num(ctx, "lastHoldPackets")};
    }

    /** Charge on, stand {@code wait} ticks, fire sprinting. Returns {bank before, shot ticks, blocks in the firing tick}. */
    private static double[] chargeShot(ClientGameTestContext ctx, int wait, boolean sprint) {
        chargeOn(ctx);
        ctx.waitTicks(wait);
        long bank = cnum(ctx, "banked");
        double[] s = shootByPress(ctx, sprint);
        ctx.waitTicks(5);
        chargeOff(ctx);
        return new double[]{bank, s[1] == 1 ? s[2] : 0, s[0]};
    }

    private static int setbacksDuring(ClientGameTestContext ctx, Runnable r) {
        ctx.runOnClient(mc -> PacketTrace.start());
        try {
            r.run();
        } finally {
            ctx.runOnClient(mc -> PacketTrace.stop());
        }
        return PacketTrace.received("player_position");
    }

    /** How many {@code id} packets the server sent while {@code r} ran. */
    private static int setbacksDuringReceived(ClientGameTestContext ctx, String id, Runnable r) {
        ctx.runOnClient(mc -> PacketTrace.start());
        try {
            r.run();
        } finally {
            ctx.runOnClient(mc -> PacketTrace.stop());
        }
        return PacketTrace.received(id);
    }

    private static void sendCommand(ClientGameTestContext ctx, String command) {
        ctx.runOnClient(mc -> mc.player.connection.sendCommand(command));
    }

    private static long usedMemoryMb(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            Runtime rt = Runtime.getRuntime();
            System.gc();
            return (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        });
    }

    private static boolean hasLog(long mark, String needle) {
        for (String l : LogTap.since(mark)) {
            if (l.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static int countLog(long mark, String needle) {
        int n = 0;
        for (String l : LogTap.since(mark)) {
            if (l.contains(needle)) {
                n++;
            }
        }
        return n;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 0. Legit jar: Blink is not there.

    /**
     * 1026-blink-legit-absent: on a legit jar (only - SKIP on cheat) there is no /blink command, and even with Blink's
     * config forced on and both key hooks forced down nothing is held or charged and the send hook passes everything.
     */
    private static void legitAbsent(ClientGameTestContext ctx) {
        String name = "1026-blink-legit-absent";
        if (Mod.isCheat()) {
            Scenario.skipped(name, "cheat jar (this case is for the legit jar)");
            return;
        }
        Scenario.run(ctx, name, (server, scenario) -> arena(server, -7000, -6000), (server, scenario) -> {
            boolean command = ctx.computeOnClient(mc -> {
                var d = net.fabricmc.fabric.impl.command.client.ClientCommandInternals.getActiveDispatcher();
                return d != null && d.getRoot().getChild("blink") != null;
            });
            boolean classes = Mod.has("blink.BlinkFeature");
            boolean held = false;
            boolean charged = false;
            if (classes) {
                ctx.runOnClient(mc -> {
                    Mod.set(CFG, "setEnabled", true);
                    Mod.setField(FEATURE, "keyHeldForTest", Boolean.TRUE);
                    Mod.setField(CHARGE, "keyHeldForTest", Boolean.TRUE);
                });
                ctx.getInput().holdKey(o -> o.keyUp);
                ctx.waitTicks(30);
                ctx.getInput().releaseKey(o -> o.keyUp);
                held = holding(ctx) || num(ctx, "releasedHolds") > 0;
                charged = charging(ctx) || cnum(ctx, "bankedTotal") > 0;
                ctx.runOnClient(mc -> {
                    Mod.setField(FEATURE, "keyHeldForTest", null);
                    Mod.setField(CHARGE, "keyHeldForTest", null);
                    Mod.set(CFG, "setEnabled", false);
                });
            }
            boolean menuTab = ctx.computeOnClient(mc -> {
                try {
                    Object tab = Mod.cls("gui.tab.BlinkTab").getConstructor().newInstance();
                    return !((java.util.List<?>) Mod.call(tab, "buildWidgets", 0, 0, 200, (Runnable) () -> { })).isEmpty();
                } catch (ReflectiveOperationException | RuntimeException e) {
                    return false;
                }
            });
            scenario.log("legit: /blink registered " + command + ", Blink classes present " + classes + ", held " + held
                    + ", charged " + charged + ", Blink tab builds widgets " + menuTab);
            check(!command, "/blink is registered on a legit jar");
            check(!held && !charged, "Blink held or charged on a legit jar");
            check(!menuTab, "the Blink tab builds settings on a legit jar");
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 1. Every tick setting

    /** 1026-blink-hold-sweep-all: Hold Ticks 1..20 one by one, sprinting: each hold is exactly the setting, Grim clean. */
    private static void holdSweepAll(ClientGameTestContext ctx) {
        int x = -7100;
        int z = -6000;
        Scenario.runExpectingFlags(ctx, "1026-blink-hold-sweep-all", (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = setUp(ctx, 20);
            Rows rows = new Rows();
            try {
                for (int len = 1; len <= 20; len++) {
                    int l = len;
                    tp(server, ctx, x - 15.5, SURFACE_Y, z + 0.5, -90f);
                    ctx.runOnClient(mc -> Mod.set(CFG, "setHoldTicks", l));
                    server.mark();
                    double[] h = sprintHold(ctx, true, len);
                    double g = gap(server, ctx);
                    rows.add(server, scenario, "Hold Ticks " + len, h[0] >= 0 && h[1] == len && g < 0.5,
                            String.format(Locale.ROOT, "hold %d tick(s) / %d packet(s), client moved %.2f, gap %.2f",
                                    (int) h[1], (int) h[2], h[0], g));
                }
            } finally {
                tearDown(ctx, gate);
            }
            rows.finish(scenario, "1026-blink-hold-sweep-all");
        });
    }

    /** 1026-blink-charge-sweep-all: Charge Ticks 1..20 (Toggle), sprinting shot: bank == setting == shot, Grim clean. */
    private static void chargeSweepAll(ClientGameTestContext ctx) {
        chargeSweepCase(ctx, "1026-blink-charge-sweep-all", -7200, "TOGGLE");
    }

    /** 1026-blink-charge-sweep-holdmode: the same in Hold mode (key held to charge, let go sprinting to fire). */
    private static void chargeSweepHoldMode(ClientGameTestContext ctx) {
        chargeSweepCase(ctx, "1026-blink-charge-sweep-holdmode", -7300, "HOLD");
    }

    private static void chargeSweepCase(ClientGameTestContext ctx, String name, int x, String mode) {
        int z = -6000;
        Scenario.runExpectingFlags(ctx, name, (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = setUp(ctx, 20);
            ctx.runOnClient(mc -> Mod.set(CFG, "setChargeMode", Mod.enumValue(MODE, mode)));
            Rows rows = new Rows();
            try {
                for (int len = 1; len <= 20; len++) {
                    int l = len;
                    tp(server, ctx, x - 15.5, SURFACE_Y, z + 0.5, -90f);
                    ctx.runOnClient(mc -> Mod.set(CFG, "setChargeTicks", l));
                    server.mark();
                    long bank;
                    long shotTicks;
                    double blocks;
                    if ("TOGGLE".equals(mode)) {
                        double[] s = chargeShot(ctx, len + 12, true);
                        bank = (long) s[0];
                        shotTicks = (long) s[1];
                        blocks = s[2];
                    } else {
                        long shots0 = cnum(ctx, "shots");
                        ctx.runOnClient(mc -> Mod.setField(CHARGE, "keyHeldForTest", Boolean.TRUE));
                        ctx.waitTicks(len + 12);
                        bank = cnum(ctx, "banked");
                        ctx.getInput().holdKey(o -> o.keyUp);
                        ctx.getInput().holdKey(o -> o.keySprint);
                        double[] a = here(ctx);
                        ctx.runOnClient(mc -> Mod.setField(CHARGE, "keyHeldForTest", Boolean.FALSE));
                        ctx.waitTicks(1);
                        blocks = flat(a, here(ctx));
                        releaseKeys(ctx);
                        shotTicks = cnum(ctx, "shots") > shots0 ? cnum(ctx, "lastShotTicks") : 0;
                        ctx.waitTicks(3);
                        check(!charging(ctx), "Hold mode: Charge still on after the key came up and the shot went");
                    }
                    double g = gap(server, ctx);
                    rows.add(server, scenario, "Charge Ticks " + len, bank == len && shotTicks == len && g < 0.5,
                            String.format(Locale.ROOT, "banked %d, shot %d tick(s), %.2f blocks in the firing tick, gap %.2f",
                                    bank, shotTicks, blocks, g));
                }
            } finally {
                chargeOff(ctx);
                tearDown(ctx, gate);
            }
            rows.finish(scenario, name);
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 2. Keys

    /**
     * 1026-blink-mash: the Hold key mashed at random (up or down each tick, seeded) for 400 ticks while sprinting back and
     * forth. Many short holds; none longer than Hold Ticks; nothing left held; server and client agree; Grim clean.
     */
    private static void mash(ClientGameTestContext ctx) {
        int x = -7400;
        int z = -6000;
        Scenario.run(ctx, "1026-blink-mash", (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = setUp(ctx, 20);
            try {
                Random rnd = new Random(1026);
                long holds0 = num(ctx, "releasedHolds");
                double[] start = here(ctx);
                double travelled = 0;
                double[] last = start;
                int setbacks = setbacksDuring(ctx, () -> {
                    for (int t = 0; t < 400; t++) {
                        if (t % 60 == 0) {
                            boolean fwd = (t / 60) % 2 == 0;
                            releaseKeys(ctx);
                            ctx.getInput().holdKey(o -> fwd ? o.keyUp : o.keyDown);
                            ctx.getInput().holdKey(o -> o.keySprint);
                        }
                        boolean down = rnd.nextInt(3) != 0;
                        ctx.runOnClient(mc -> Mod.setField(FEATURE, "keyHeldForTest", down));
                        ctx.waitTicks(1);
                    }
                    holdUp(ctx);
                    releaseKeys(ctx);
                    ctx.waitTicks(10);
                });
                long holds = num(ctx, "releasedHolds") - holds0;
                long longest = num(ctx, "longestHoldTicks");
                long left = num(ctx, "heldPackets");
                double g = gap(server, ctx);
                scenario.log(String.format(Locale.ROOT, "mash: %d release(s) over 400 ticks, longest hold ever %d tick(s), "
                        + "%d packet(s) left held, holding %s, gap %.2f, %d setback(s), moved %.1f", holds, longest, left,
                        holding(ctx), g, setbacks, flat(start, here(ctx))));
                check(holds >= 40, "the mashing made only " + holds + " hold(s): nothing was tested");
                check(longest <= 20, "a hold ran past Hold Ticks: " + longest);
                check(left == 0 && !holding(ctx), "packets were left held after the mashing");
                check(setbacks == 0, "the server set him back " + setbacks + " time(s)");
                check(g < 0.5, "client and server disagree: " + g);
            } finally {
                tearDown(ctx, gate);
            }
        });
    }

    /**
     * 1026-blink-charge-mash: the Charge key (Toggle) pressed at random gaps of 1-8 ticks for 500 ticks while he stands
     * (40 ticks) and walks (15 ticks) in turn: charging, firing, arming, switching off and on again, over and over. Some
     * shots must fire; the bank never passes Charge Ticks; client and server agree; no setbacks; Grim clean.
     */
    private static void chargeMash(ClientGameTestContext ctx) {
        int x = -7500;
        int z = -6000;
        Scenario.run(ctx, "1026-blink-charge-mash", (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = setUp(ctx, 20);
            try {
                Random rnd = new Random(2610);
                long shots0 = cnum(ctx, "shots");
                int[] maxBank = {0};
                int setbacks = setbacksDuring(ctx, () -> {
                    int t = 0;
                    int phase = 0;
                    while (t < 500) {
                        boolean walking = (t % 55) >= 40;
                        if (walking && phase != 1) {
                            boolean fwd = (t / 55) % 2 == 0;
                            ctx.getInput().holdKey(o -> fwd ? o.keyUp : o.keyDown);
                            phase = 1;
                        } else if (!walking && phase != 0) {
                            releaseKeys(ctx);
                            phase = 0;
                        }
                        int gapTicks = 1 + rnd.nextInt(8);
                        ctx.waitTicks(gapTicks);
                        maxBank[0] = Math.max(maxBank[0], (int) cnum(ctx, "banked"));
                        pressCharge(ctx);
                        t += gapTicks + 2;
                    }
                    releaseKeys(ctx);
                    ctx.waitTicks(10);
                });
                long shots = cnum(ctx, "shots") - shots0;
                double g = gap(server, ctx);
                scenario.log(String.format(Locale.ROOT, "charge-mash: %d shot(s), largest bank seen %d, %d setback(s), gap %.2f, "
                        + "%d answer(s) waiting at the end", shots, maxBank[0], setbacks, g, cnum(ctx, "answersHeld")));
                check(shots >= 3, "only " + shots + " shot(s) fired: nothing was tested");
                check(maxBank[0] <= 20, "the bank passed Charge Ticks: " + maxBank[0]);
                check(setbacks == 0, "the server set him back " + setbacks + " time(s)");
                check(g < 0.5, "client and server disagree: " + g);
            } finally {
                chargeOff(ctx);
                tearDown(ctx, gate);
            }
        });
    }

    /**
     * 1026-blink-both: (a) a full bank, a 180-degree turn standing (a banked turn: the look is owed), then a sprinting
     * 20-tick hold - the bank is dropped when the hold starts, the owed look rides the first held move, and the released
     * burst is sent as held (not through Charge); the bank then refills and a shot is clean. (b) both keys pressed in the
     * same tick. (c) Charge in Hold mode and the Hold key down together while sprinting, both let go together.
     */
    private static void bothKeys(ClientGameTestContext ctx) {
        int x = -7600;
        int z = -6000;
        Scenario.runExpectingFlags(ctx, "1026-blink-both", (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = setUp(ctx, 20);
            Rows rows = new Rows();
            try {
                // (a)
                tp(server, ctx, x - 10.5, SURFACE_Y, z + 0.5, 90f);
                chargeOn(ctx);
                // Turn while the bank is still filling: only a BANKED tick owes its look (a full bank sends the turn).
                ctx.waitTicks(8);
                long bank = cnum(ctx, "banked");
                long carried0 = cnum(ctx, "rotationsCarried");
                ctx.runOnClient(mc -> mc.player.setYRot(mc.player.getYRot() - 180f));
                ctx.waitTicks(3);
                long bankAfterTurn = cnum(ctx, "banked");
                double[] h = sprintHold(ctx, true, 20);
                long bankAfterHold = cnum(ctx, "banked");
                long carried = cnum(ctx, "rotationsCarried") - carried0;
                double g = gap(server, ctx);
                rows.add(server, scenario, "(a) bank, turn, hold", bank >= 3 && bankAfterTurn > bank && h[0] > 3 && h[1] == 20 && g < 0.5
                                && carried >= 1, String.format(Locale.ROOT, "bank %d, after the turn %d, hold %d tick(s) moved %.2f, "
                                + "bank right after the hold %d, %d look(s) carried, gap %.2f", bank, bankAfterTurn, (int) h[1], h[0],
                        bankAfterHold, carried, g));
                tp(server, ctx, x - 10.5, SURFACE_Y, z + 0.5, -90f);
                ctx.waitTicks(30);
                long bank2 = cnum(ctx, "banked");
                double[] s = shootByPress(ctx, true);
                ctx.waitTicks(5);
                g = gap(server, ctx);
                rows.add(server, scenario, "(a) shot after the hold", bank2 == 20 && s[2] == 20 && s[0] > 4.5 && g < 0.5,
                        String.format(Locale.ROOT, "bank %d, shot %d tick(s) %.2f blocks, gap %.2f", bank2, (long) s[2], s[0], g));
                chargeOff(ctx);
                // (b) both keys in one tick (Toggle Charge on, Hold on), sprinting.
                tp(server, ctx, x - 10.5, SURFACE_Y, z + 5.5, -90f);
                ctx.getInput().holdKey(o -> o.keyUp);
                ctx.getInput().holdKey(o -> o.keySprint);
                ctx.waitTicks(4);
                long holds0 = num(ctx, "releasedHolds");
                ctx.runOnClient(mc -> {
                    Mod.setField(FEATURE, "keyHeldForTest", Boolean.TRUE);
                    Mod.setField(CHARGE, "keyHeldForTest", Boolean.TRUE);
                });
                ctx.waitTicks(1);
                ctx.runOnClient(mc -> Mod.setField(CHARGE, "keyHeldForTest", Boolean.FALSE));
                ctx.waitTicks(10);
                boolean both = holding(ctx) && charging(ctx);
                holdUp(ctx);
                releaseKeys(ctx);
                ctx.waitTicks(30);
                long bankB = cnum(ctx, "banked");
                double[] sb = shootByPress(ctx, true);
                ctx.waitTicks(5);
                chargeOff(ctx);
                g = gap(server, ctx);
                rows.add(server, scenario, "(b) both keys one tick", both && num(ctx, "releasedHolds") > holds0 && sb[2] >= 19
                        && g < 0.5, String.format(Locale.ROOT, "holding and charging together %s, then bank %d, shot %d tick(s) "
                        + "%.2f blocks, gap %.2f", both, bankB, (long) sb[2], sb[0], g));
                // (c) Charge Hold mode + Hold key, both down while sprinting, let go together.
                ctx.runOnClient(mc -> Mod.set(CFG, "setChargeMode", Mod.enumValue(MODE, "HOLD")));
                tp(server, ctx, x - 10.5, SURFACE_Y, z - 5.5, -90f);
                ctx.runOnClient(mc -> Mod.setField(CHARGE, "keyHeldForTest", Boolean.TRUE));
                ctx.waitTicks(25);
                long bankC = cnum(ctx, "banked");
                ctx.getInput().holdKey(o -> o.keyUp);
                ctx.getInput().holdKey(o -> o.keySprint);
                holdDown(ctx);
                ctx.waitTicks(15);
                boolean bothC = holding(ctx) && charging(ctx);
                ctx.runOnClient(mc -> {
                    Mod.setField(FEATURE, "keyHeldForTest", Boolean.FALSE);
                    Mod.setField(CHARGE, "keyHeldForTest", Boolean.FALSE);
                });
                ctx.waitTicks(5);
                releaseKeys(ctx);
                boolean offC = !charging(ctx) && !holding(ctx);
                g = gap(server, ctx);
                rows.add(server, scenario, "(c) Hold-mode Charge + Hold", bankC >= 20 && bothC && offC && g < 0.5,
                        String.format(Locale.ROOT, "bank %d before, both on %s, both off after %s, gap %.2f", bankC, bothC, offC, g));
                // (d) Charging (Toggle), a hold that walks 10 ticks and then stands 6 before the key comes up: the release
                // happens standing still. Before mod b95c8146 the burst went through Charge's hook, which banked its
                // tick-ends (dropping the walk's moves) because he stood still at that moment.
                ctx.runOnClient(mc -> Mod.set(CFG, "setChargeMode", Mod.enumValue(MODE, "TOGGLE")));
                tp(server, ctx, x - 10.5, SURFACE_Y, z + 10.5, -90f);
                chargeOn(ctx);
                ctx.waitTicks(10);
                double[] d0 = here(ctx);
                long holdsD = num(ctx, "releasedHolds");
                String nameD = playerName(ctx);
                int sbD = setbacksDuring(ctx, () -> {
                    holdDown(ctx);
                    ctx.getInput().holdKey(o -> o.keyUp);
                    ctx.getInput().holdKey(o -> o.keySprint);
                    ctx.waitTicks(10);
                    releaseKeys(ctx);
                    ctx.waitTicks(6);
                    holdUp(ctx);
                    ctx.waitTicks(20);
                });
                double walked = flat(d0, here(ctx));
                double[] srvD = serverPos(server, ctx, nameD);
                double serverWalked = flat(d0, srvD);
                g = gap(server, ctx);
                chargeOff(ctx);
                rows.add(server, scenario, "(d) release standing, charging", num(ctx, "releasedHolds") > holdsD && walked > 2
                        && sbD == 0 && g < 0.5, String.format(Locale.ROOT, "client walked %.2f, server has him %.2f on, %d setback(s), "
                        + "gap %.2f", walked, serverWalked, sbD, g));
            } finally {
                chargeOff(ctx);
                tearDown(ctx, gate);
            }
            rows.finish(scenario, "1026-blink-both");
        });
    }

    /**
     * 1026-blink-off-mid: Blink switched off (a) in the middle of a sprinting hold, (b) with a full bank, (c) with a shot
     * armed - and straight back on each time. Everything held goes, nothing fires, client and server agree, Grim clean.
     */
    private static void offMid(ClientGameTestContext ctx) {
        int x = -7700;
        int z = -6000;
        Scenario.runExpectingFlags(ctx, "1026-blink-off-mid", (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = setUp(ctx, 20);
            Rows rows = new Rows();
            try {
                tp(server, ctx, x - 10.5, SURFACE_Y, z + 0.5, -90f);
                ctx.getInput().holdKey(o -> o.keyUp);
                ctx.getInput().holdKey(o -> o.keySprint);
                ctx.waitTicks(4);
                long holds0 = num(ctx, "releasedHolds");
                holdDown(ctx);
                ctx.waitTicks(8);
                boolean was = holding(ctx);
                ctx.runOnClient(mc -> Mod.set(CFG, "setEnabled", false));
                ctx.waitTicks(2);
                boolean released = !holding(ctx) && num(ctx, "releasedHolds") > holds0 && num(ctx, "heldPackets") == 0;
                ctx.runOnClient(mc -> Mod.set(CFG, "setEnabled", true));
                ctx.waitTicks(4);
                boolean restarted = holding(ctx);
                holdUp(ctx);
                releaseKeys(ctx);
                double g = gap(server, ctx);
                rows.add(server, scenario, "(a) off mid-hold", was && released && g < 0.5, "holding " + was + ", released at once "
                        + released + ", back on with the key still down: holding " + restarted + String.format(Locale.ROOT,
                        ", gap %.2f", g));
                // (b) full bank
                chargeOn(ctx);
                ctx.waitTicks(30);
                long bank = cnum(ctx, "banked");
                long shots0 = cnum(ctx, "shots");
                ctx.runOnClient(mc -> Mod.set(CFG, "setEnabled", false));
                ctx.waitTicks(2);
                boolean off = !charging(ctx) && cnum(ctx, "answersHeld") == 0;
                ctx.runOnClient(mc -> Mod.set(CFG, "setEnabled", true));
                ctx.getInput().holdKey(o -> o.keyUp);
                ctx.waitTicks(15);
                releaseKeys(ctx);
                g = gap(server, ctx);
                rows.add(server, scenario, "(b) off with a full bank", bank == 20 && off && cnum(ctx, "shots") == shots0
                        && g < 0.5, "bank " + bank + ", off with no answers waiting " + off + String.format(Locale.ROOT,
                        ", gap %.2f", g));
                // (c) armed
                chargeOn(ctx);
                ctx.waitTicks(30);
                pressCharge(ctx);
                boolean arm = armed(ctx);
                long shots1 = cnum(ctx, "shots");
                ctx.runOnClient(mc -> Mod.set(CFG, "setEnabled", false));
                ctx.waitTicks(2);
                ctx.runOnClient(mc -> Mod.set(CFG, "setEnabled", true));
                double[] a = here(ctx);
                ctx.getInput().holdKey(o -> o.keyUp);
                ctx.waitTicks(1);
                double step = flat(a, here(ctx));
                ctx.waitTicks(10);
                releaseKeys(ctx);
                g = gap(server, ctx);
                rows.add(server, scenario, "(c) off with a shot armed", arm && cnum(ctx, "shots") == shots1 && step < 0.5
                        && !armed(ctx) && g < 0.5, String.format(Locale.ROOT, "armed %s, fired after %s, first step %.2f, gap %.2f",
                        arm, cnum(ctx, "shots") != shots1, step, g));
            } finally {
                chargeOff(ctx);
                tearDown(ctx, gate);
            }
            rows.finish(scenario, "1026-blink-off-mid");
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 3. Screens

    /**
     * 1026-blink-screens: during a hold - a chest menu opened, two clicks, closed (clicks live, the close held); a chat line
     * and a command typed (held: they reach the server only after the release); the inventory and the pause screen opened
     * and closed. While charging - the same, the bank must hold through them, and a shot after is clean. (In play the Hold
     * key reads as up under chat and the pause screen, which releases the hold; the test hook forces the key, so here the
     * hold carries on and what is tested is the traffic.)
     */
    private static void screens(ClientGameTestContext ctx) {
        int x = -7800;
        int z = -6000;
        Scenario.runExpectingFlags(ctx, "1026-blink-screens", (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = setUp(ctx, 20);
            Rows rows = new Rows();
            dev.testkit.gametest.hx.Hx hx = null;
            try {
                hx = dev.testkit.gametest.hx.Hx.connect();
                String name = playerName(ctx);
                // Hold + chest + chat.
                long ev0 = hx.head();
                long live0 = num(ctx, "clicksLive");
                holdDown(ctx);
                ctx.waitTicks(2);
                hx.menu("Blink Stress Chest", 3, java.util.Map.of(11, "minecraft:stone", 13, "minecraft:dirt"), null);
                boolean opened = false;
                for (int t = 0; t < 10 && !opened; t++) {
                    ctx.waitTicks(1);
                    opened = BlinkTests.menuOpen(ctx);
                }
                BlinkTests.clickSlot(ctx, 11);
                ctx.waitTicks(1);
                BlinkTests.clickSlot(ctx, 13);
                ctx.waitTicks(2);
                ctx.runOnClient(mc -> mc.player.closeContainer());
                ctx.runOnClient(mc -> mc.player.connection.sendChat("blinkstress-held-chat"));
                sendCommand(ctx, "me blinkstress-held-cmd");
                ctx.waitTicks(3);
                boolean chatEarly = server.tail(60).contains("blinkstress-held-chat") || server.tail(60).contains("blinkstress-held-cmd");
                long clicksDuring = hx.events(ev0, "menu.click").size();
                boolean stillHolding = holding(ctx);
                for (int t = 0; t < 20 && holding(ctx); t++) {
                    ctx.waitTicks(1);
                }
                holdUp(ctx);
                ctx.waitTicks(10);
                String tail = server.tail(80);
                boolean chatAfter = tail.contains("blinkstress-held-chat") && tail.contains("blinkstress-held-cmd");
                long live = num(ctx, "clicksLive") - live0;
                double g = gap(server, ctx);
                rows.add(server, scenario, "hold: chest + chat", opened && live >= 2 && clicksDuring >= 2 && stillHolding
                                && !chatEarly && chatAfter && g < 0.5, String.format(Locale.ROOT, "menu opened %s, %d click(s) live, "
                                + "%d reached the server during the hold, still holding after %s, chat/cmd before release %s, after %s, gap %.2f",
                        opened, live, clicksDuring, stillHolding, chatEarly, chatAfter, g));
                // Hold + inventory + pause.
                holdDown(ctx);
                ctx.waitTicks(2);
                ctx.runOnClient(mc -> dev.testkit.compat.McCompat.setScreen(mc, new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player)));
                ctx.waitTicks(5);
                ctx.runOnClient(mc -> mc.player.closeContainer());
                ctx.waitTicks(2);
                ctx.runOnClient(mc -> dev.testkit.compat.McCompat.setScreen(mc, new net.minecraft.client.gui.screens.PauseScreen(true)));
                ctx.waitTicks(5);
                ctx.runOnClient(mc -> dev.testkit.compat.McCompat.setScreen(mc, null));
                boolean held2 = holding(ctx);
                for (int t = 0; t < 20 && holding(ctx); t++) {
                    ctx.waitTicks(1);
                }
                holdUp(ctx);
                g = gap(server, ctx);
                rows.add(server, scenario, "hold: inventory + pause", held2 && g < 0.5, "held through both " + held2
                        + String.format(Locale.ROOT, ", gap %.2f", g));
                // Charging through the same.
                chargeOn(ctx);
                ctx.waitTicks(30);
                long bank0 = cnum(ctx, "banked");
                long ev1 = hx.head();
                hx.menu("Blink Stress Chest 2", 3, java.util.Map.of(11, "minecraft:stone"), null);
                opened = false;
                for (int t = 0; t < 10 && !opened; t++) {
                    ctx.waitTicks(1);
                    opened = BlinkTests.menuOpen(ctx);
                }
                BlinkTests.clickSlot(ctx, 11);
                ctx.waitTicks(3);
                ctx.runOnClient(mc -> mc.player.closeContainer());
                ctx.runOnClient(mc -> dev.testkit.compat.McCompat.setScreen(mc, new net.minecraft.client.gui.screens.ChatScreen("", false)));
                ctx.waitTicks(3);
                ctx.runOnClient(mc -> mc.player.connection.sendChat("blinkstress-charge-chat"));
                ctx.runOnClient(mc -> dev.testkit.compat.McCompat.setScreen(mc, null));
                ctx.runOnClient(mc -> dev.testkit.compat.McCompat.setScreen(mc, new net.minecraft.client.gui.screens.PauseScreen(true)));
                ctx.waitTicks(40);
                ctx.runOnClient(mc -> dev.testkit.compat.McCompat.setScreen(mc, null));
                ctx.runOnClient(mc -> dev.testkit.compat.McCompat.setScreen(mc, new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player)));
                ctx.waitTicks(10);
                ctx.runOnClient(mc -> mc.player.closeContainer());
                ctx.waitTicks(25);
                long bank1 = cnum(ctx, "banked");
                long clicks = hx.events(ev1, "menu.click").size();
                boolean chatC = server.tail(80).contains("blinkstress-charge-chat");
                tp(server, ctx, x - 10.5, SURFACE_Y, z + 0.5, -90f);
                ctx.waitTicks(25);
                double[] s = shootByPress(ctx, true);
                ctx.waitTicks(5);
                chargeOff(ctx);
                g = gap(server, ctx);
                rows.add(server, scenario, "charge: chest, chat, pause, inv", bank0 == 20 && bank1 == 20 && clicks >= 1 && chatC
                                && s[2] == 20 && g < 0.5, String.format(Locale.ROOT, "bank %d before, %d after the screens, %d click(s) "
                                + "reached the server, chat arrived %s, shot %d tick(s) %.2f blocks, gap %.2f", bank0, bank1, clicks,
                        chatC, (long) s[2], s[0], g));
            } finally {
                ctx.runOnClient(mc -> {
                    if (dev.testkit.compat.McCompat.screen(mc) != null) {
                        dev.testkit.compat.McCompat.setScreen(mc, null);
                    }
                });
                if (hx != null) {
                    hx.close();
                }
                chargeOff(ctx);
                tearDown(ctx, gate);
            }
            rows.finish(scenario, "1026-blink-screens");
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 4. Terrain and movement

    /** One lane: its name, how to build it at (x, z) (lane runs +x from x-15 to x+15), and how far a run must get. */
    private record Lane(String name, java.util.function.BiConsumer<TestServer, int[]> build, double minMove, boolean sprint,
                        String keyExtra, String effect) {
    }

    private static void lanes(ClientGameTestContext ctx, String caseName, int x, List<Lane> lanes) {
        int z0 = -6000;
        Scenario.runExpectingFlags(ctx, caseName, (server, scenario) -> arena(server, x, z0), (server, scenario) -> {
            // Built now, with him standing in the arena: a vanilla fill/setblock in a chunk nobody has loaded fails
            // ("That position is not loaded"), and in the build step the lanes' chunks were not loaded yet.
            for (int i = 0; i < lanes.size(); i++) {
                int lz = z0 - 30 + 6 * i;
                lanes.get(i).build().accept(server, new int[]{x, lz});
            }
            ctx.waitTicks(20);
            boolean gate = setUp(ctx, 20);
            Rows rows = new Rows();
            String name = playerName(ctx);
            try {
                for (int i = 0; i < lanes.size(); i++) {
                    Lane lane = lanes.get(i);
                    int lz = z0 - 30 + 6 * i;
                    if (lane.effect() != null) {
                        server.command("effect give " + name + " " + lane.effect() + " 120 1 true");
                    }
                    // Hold along the lane.
                    tp(server, ctx, x - 14.5, SURFACE_Y, lz + 0.5, -90f);
                    server.mark();
                    if (lane.keyExtra() != null) {
                        holdExtra(ctx, lane.keyExtra(), true);
                    }
                    double[] h = sprintHold(ctx, lane.sprint(), 20);
                    holdExtra(ctx, lane.keyExtra(), false);
                    ctx.waitTicks(20);
                    double g = gap(server, ctx);
                    rows.add(server, scenario, lane.name() + " hold", h[0] >= lane.minMove() && h[1] == 20 && g < 0.6,
                            String.format(Locale.ROOT, "hold %d tick(s), moved %.2f, gap %.2f", (int) h[1], h[0], g));
                    // A charge shot along the lane.
                    tp(server, ctx, x - 14.5, SURFACE_Y, lz + 0.5, -90f);
                    chargeOn(ctx);
                    ctx.waitTicks(30);
                    long bank = cnum(ctx, "banked");
                    if (lane.keyExtra() != null) {
                        holdExtra(ctx, lane.keyExtra(), true);
                    }
                    double[] a = here(ctx);
                    double[] s = shootByPress(ctx, lane.sprint());
                    double moved = dist3(a, here(ctx));
                    holdExtra(ctx, lane.keyExtra(), false);
                    ctx.waitTicks(5);
                    chargeOff(ctx);
                    ctx.waitTicks(20);
                    g = gap(server, ctx);
                    boolean banks = !lane.name().startsWith("water") && !lane.name().startsWith("cobweb");
                    rows.add(server, scenario, lane.name() + " shot", (!banks || bank >= 20) && (bank == 0 || s[2] == bank)
                                    && (bank == 0 || moved >= lane.minMove() / 2) && g < 0.6,
                            String.format(Locale.ROOT, "bank %d, shot %d tick(s), moved %.2f in the firing tick, gap %.2f", bank,
                                    (long) s[2], moved, g));
                    if (lane.effect() != null) {
                        server.command("effect clear " + name);
                        ctx.waitTicks(5);
                    }
                }
            } finally {
                releaseKeys(ctx);
                ctx.getInput().releaseKey(o -> o.keyShift);
                chargeOff(ctx);
                tearDown(ctx, gate);
            }
            rows.finish(scenario, caseName);
        });
    }

    private static void holdExtra(ClientGameTestContext ctx, String key, boolean down) {
        if (key == null) {
            return;
        }
        if (down) {
            ctx.getInput().holdKey(o -> "jump".equals(key) ? o.keyJump : o.keyShift);
        } else {
            ctx.getInput().releaseKey(o -> "jump".equals(key) ? o.keyJump : o.keyShift);
        }
    }

    private static void fillLane(TestServer server, int[] p, int y1, int y2, String block) {
        server.command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s", p[0] - 15, y1, p[1] - 1, p[0] + 15, y2, p[1] + 1, block));
    }

    /** 1026-blink-terrain-a: ice, packed/blue ice, soul sand, slabs, stairs up, water (swim), ladder, cobweb. */
    private static void terrainA(ClientGameTestContext ctx) {
        int fy = SURFACE_Y - 1;
        lanes(ctx, "1026-blink-terrain-a", -7900, List.of(
                new Lane("ice", (s, p) -> fillLane(s, p, fy, fy, "minecraft:ice"), 3, true, null, null),
                new Lane("blue ice", (s, p) -> fillLane(s, p, fy, fy, "minecraft:blue_ice"), 3, true, null, null),
                new Lane("soul sand", (s, p) -> fillLane(s, p, fy, fy, "minecraft:soul_sand"), 1, true, null, null),
                new Lane("slabs", (s, p) -> {
                    for (int i = -12; i <= 15; i += 2) {
                        s.command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone_slab", p[0] + i, SURFACE_Y,
                                p[1] - 1, p[0] + i, SURFACE_Y, p[1] + 1));
                    }
                }, 2.5, true, "jump", null),
                new Lane("stairs up", (s, p) -> {
                    for (int i = 0; i < 6; i++) {
                        s.command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone_brick_stairs[facing=east]",
                                p[0] - 11 + i, SURFACE_Y + i, p[1] - 1, p[0] - 11 + i, SURFACE_Y + i, p[1] + 1));
                        if (i > 0) {
                            s.command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", p[0] - 11 + i,
                                    SURFACE_Y, p[1] - 1, p[0] - 11 + i, SURFACE_Y + i - 1, p[1] + 1));
                        }
                    }
                    s.command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", p[0] - 5, SURFACE_Y, p[1] - 1,
                            p[0] + 15, SURFACE_Y + 5, p[1] + 1));
                }, 2, true, null, null),
                new Lane("water (swim)", (s, p) -> fillLane(s, p, fy - 2, fy, "minecraft:water"), 0.5, true, null, null),
                new Lane("ladder (climb)", (s, p) -> {
                    s.command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", p[0] - 13, SURFACE_Y, p[1] - 1,
                            p[0] - 13, SURFACE_Y + 12, p[1] + 1));
                    s.command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:ladder[facing=west]", p[0] - 14,
                            SURFACE_Y, p[1], p[0] - 14, SURFACE_Y + 12, p[1]));
                }, 0.5, false, null, null),
                new Lane("cobweb", (s, p) -> fillLane(s, p, SURFACE_Y, SURFACE_Y, "minecraft:cobweb"), 0.05, true, null, null)));
    }

    /** 1026-blink-terrain-b: sneaking, sprint-jumping, off an edge (a 5-block fall), into a wall, Speed II, Slowness II, Jump Boost II. */
    private static void terrainB(ClientGameTestContext ctx) {
        lanes(ctx, "1026-blink-terrain-b", -8000, List.of(
                new Lane("sneak", (s, p) -> { }, 0.5, false, "sneak", null),
                new Lane("sprint-jump", (s, p) -> { }, 3, true, "jump", null),
                new Lane("off an edge", (s, p) -> {
                    s.command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", p[0] - 12, SURFACE_Y - 6,
                            p[1] - 1, p[0] + 15, SURFACE_Y - 1, p[1] + 1));
                    s.command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", p[0] - 12, SURFACE_Y - 7,
                            p[1] - 1, p[0] + 15, SURFACE_Y - 7, p[1] + 1));
                }, 2, true, null, null),
                new Lane("into a wall", (s, p) -> s.command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone",
                        p[0] - 11, SURFACE_Y, p[1] - 1, p[0] - 11, SURFACE_Y + 3, p[1] + 1)), 0.5, true, null, null),
                new Lane("speed II", (s, p) -> { }, 4, true, null, "minecraft:speed"),
                new Lane("slowness II", (s, p) -> { }, 0.5, true, null, "minecraft:slowness"),
                new Lane("jump boost II", (s, p) -> { }, 3, true, "jump", "minecraft:jump_boost")));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 5. Items and abilities

    private static final String HYPERION = "minecraft:iron_sword[custom_data={id:\"HYPERION\"}]";

    /**
     * 1026-blink-items-a: ender pearls (thrown inside a hold; thrown just before a hold and landing during it; thrown while
     * charging), Hyperion's Wither Impact and AOTV's Instant Transmission inside a hold and while charging, and five
     * etherwarps in a row while charging. Each must take effect on the server; Grim clean.
     */
    private static void itemsA(ClientGameTestContext ctx) {
        int x = -8100;
        int z = -6000;
        Scenario.runExpectingFlags(ctx, "1026-blink-items-a", (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = setUp(ctx, 20);
            Rows rows = new Rows();
            dev.testkit.gametest.hx.Hx hx = null;
            String name = playerName(ctx);
            try {
                hx = dev.testkit.gametest.hx.Hx.connect();
                hx.call("dungeon.abilities", "enabled", true);
                // Pearl thrown inside a hold.
                server.command("give " + name + " minecraft:ender_pearl 16");
                ctx.waitTicks(5);
                int pearlSlot = ctx.computeOnClient(mc -> {
                    for (int i = 0; i < 9; i++) {
                        if (mc.player.getInventory().getItem(i).getItem() == net.minecraft.world.item.Items.ENDER_PEARL) {
                            return i;
                        }
                    }
                    return -1;
                });
                check(pearlSlot >= 0, "no ender pearl in the hotbar");
                int[] pearlTps = new int[3];
                for (int k = 0; k < 3; k++) {
                    int kk = k;
                    tp(server, ctx, x - 10.5, SURFACE_Y, z + 0.5, -90f);
                    ctx.runOnClient(mc -> {
                        mc.player.getInventory().setSelectedSlot(pearlSlot);
                        mc.player.setXRot(-20f);
                    });
                    ctx.waitTicks(5);
                    double[] a = here(ctx);
                    long tp0 = num(ctx, "teleportsHeld");
                    int sb = setbacksDuring(ctx, () -> {
                        if (kk == 0) {
                            holdDown(ctx);
                            ctx.waitTicks(3);
                            ctx.getInput().pressKey(o -> o.keyUse);
                            for (int t = 0; t < 25 && holding(ctx); t++) {
                                ctx.waitTicks(1);
                            }
                            holdUp(ctx);
                            ctx.waitTicks(60);
                        } else if (kk == 1) {
                            ctx.getInput().pressKey(o -> o.keyUse);
                            ctx.waitTicks(6);
                            holdDown(ctx);
                            for (int t = 0; t < 25 && holding(ctx); t++) {
                                ctx.waitTicks(1);
                            }
                            holdUp(ctx);
                            BlinkTests.Pulser.start(ctx);
                            ctx.waitTicks(50);
                            BlinkTests.Pulser.stop(ctx);
                            ctx.waitTicks(20);
                        } else {
                            chargeOn(ctx);
                            ctx.waitTicks(30);
                            ctx.getInput().pressKey(o -> o.keyUse);
                            ctx.waitTicks(60);
                        }
                    });
                    pearlTps[k] = sb;
                    double moved = flat(a, here(ctx));
                    long heldTp = num(ctx, "teleportsHeld") - tp0;
                    long bankAfter = kk == 2 ? cnum(ctx, "banked") : -1;
                    double[] s = kk == 2 ? shootByPress(ctx, true) : null;
                    if (kk == 2) {
                        ctx.waitTicks(5);
                        chargeOff(ctx);
                    }
                    double g = gap(server, ctx);
                    String label = kk == 0 ? "pearl inside a hold" : kk == 1 ? "pearl landing in holds" : "pearl while charging";
                    rows.add(server, scenario, label, sb >= 1 && moved > 3 && g < 0.5 && (kk != 2 || s[2] >= 19),
                            String.format(Locale.ROOT, "%d position packet(s), %d held through, moved %.2f, %sgap %.2f", sb, heldTp,
                                    moved, kk == 2 ? "bank after landing " + bankAfter + ", shot " + (long) s[2] + " tick(s), " : "", g));
                }
                // Hyperion and AOTV transmission, inside a hold and while charging.
                hx.give(0, AOTV);
                hx.give(1, HYPERION);
                for (int item = 0; item < 2; item++) {
                    for (int mode = 0; mode < 2; mode++) {
                        int it = item;
                        tp(server, ctx, x - 15.5, SURFACE_Y, z + 6.5 + 6 * item, -90f);
                        ctx.runOnClient(mc -> {
                            mc.player.getInventory().setSelectedSlot(it == 0 ? 0 : 1);
                            mc.player.setXRot(0f);
                        });
                        ctx.waitTicks(5);
                        double[] a = here(ctx);
                        int sb;
                        if (mode == 0) {
                            sb = setbacksDuring(ctx, () -> {
                                holdDown(ctx);
                                ctx.waitTicks(4);
                                ctx.getInput().pressKey(o -> o.keyUse);
                                for (int t = 0; t < 25 && holding(ctx); t++) {
                                    ctx.waitTicks(1);
                                }
                                holdUp(ctx);
                                ctx.waitTicks(20);
                            });
                        } else {
                            chargeOn(ctx);
                            ctx.waitTicks(30);
                            sb = setbacksDuring(ctx, () -> {
                                ctx.getInput().pressKey(o -> o.keyUse);
                                ctx.waitTicks(30);
                            });
                        }
                        double moved = flat(a, here(ctx));
                        double[] s = null;
                        if (mode == 1) {
                            ctx.runOnClient(mc -> mc.player.setYRot(mc.player.getYRot() + 180f));
                            ctx.waitTicks(25);
                            s = shootByPress(ctx, true);
                            ctx.waitTicks(5);
                            chargeOff(ctx);
                        }
                        double g = gap(server, ctx);
                        String label = (item == 0 ? "AOTV transmission" : "Hyperion") + (mode == 0 ? " in a hold" : " charging");
                        rows.add(server, scenario, label, sb >= 1 && moved > 4 && g < 0.5 && (s == null || s[2] >= 19),
                                String.format(Locale.ROOT, "%d position packet(s), moved %.2f%s, gap %.2f", sb, moved,
                                        s == null ? "" : ", shot " + (long) s[2] + " tick(s)", g));
                    }
                }
                // Five etherwarps in a row while charging (ZPEW on), then a refill and a shot.
                BlinkTests.warpSetUp(ctx);
                ctx.runOnClient(mc -> Latency.install(100));
                tp(server, ctx, x - 25.5, SURFACE_Y, z - 12.5, -90f);
                chargeOn(ctx);
                ctx.waitTicks(30);
                com.google.gson.JsonObject before = hx.call("dungeon.stats").getAsJsonObject();
                ctx.getInput().holdKey(o -> o.keyShift);
                ctx.waitTicks(3);
                double[] feet = here(ctx);
                for (int w = 1; w <= 5; w++) {
                    int bx = x - 26 + 6 * w;
                    BlinkTests.warpAt(ctx, feet, bx, z - 13);
                    ctx.waitTicks(3);
                    feet = new double[]{bx + 0.5, SURFACE_Y + 0.05, z - 12.5};
                }
                ctx.waitTicks(20);
                ctx.getInput().releaseKey(o -> o.keyShift);
                int warps = hx.call("dungeon.stats").getAsJsonObject().get("etherwarps").getAsInt()
                        - before.get("etherwarps").getAsInt();
                ctx.waitTicks(30);
                double[] s = shootByPress(ctx, true);
                ctx.waitTicks(5);
                chargeOff(ctx);
                ctx.runOnClient(mc -> Latency.remove());
                double g = gap(server, ctx);
                rows.add(server, scenario, "5 etherwarps charging", warps == 5 && s[2] >= 19 && g < 0.5,
                        String.format(Locale.ROOT, "%d server warp(s), then shot %d tick(s) %.2f blocks, gap %.2f", warps,
                                (long) s[2], s[0], g));
            } finally {
                ctx.getInput().releaseKey(o -> o.keyShift);
                BlinkTests.warpTearDown(ctx, hx);
                chargeOff(ctx);
                tearDown(ctx, gate);
            }
            rows.finish(scenario, "1026-blink-items-a");
        });
    }

    /**
     * 1026-blink-items-b: eating (made hungry first) inside back-to-back holds and while charging; a bow drawn and loosed
     * while charging; a block placed, broken, a lever flicked, a chest opened and a dummy hit while charging - each reaching
     * the server, then a full shot. Grim clean.
     */
    private static void itemsB(ClientGameTestContext ctx) {
        int x = -8200;
        int z = -6000;
        Scenario.runExpectingFlags(ctx, "1026-blink-items-b", (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            server.command("setblock " + (x + 2) + " " + SURFACE_Y + " " + (z + 2) + " minecraft:stone");
            server.command("setblock " + (x + 2) + " " + (SURFACE_Y + 1) + " " + (z + 2) + " minecraft:lever[face=floor,facing=east]");
            server.command("setblock " + (x + 2) + " " + SURFACE_Y + " " + (z - 2) + " minecraft:chest");
            server.command("setblock " + (x - 2) + " " + SURFACE_Y + " " + (z + 2) + " minecraft:dirt");
            ctx.waitTicks(10);
            boolean gate = setUp(ctx, 20);
            Rows rows = new Rows();
            String name = playerName(ctx);
            try {
                // Food inside holds.
                for (int mode = 0; mode < 2; mode++) {
                    server.command("clear " + name);
                    server.command("give " + name + " minecraft:cooked_beef 8");
                    server.command("effect give " + name + " minecraft:hunger 4 255 true");
                    ctx.waitTicks(90);
                    server.command("effect clear " + name);
                    ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
                    int food0 = ctx.computeOnClient(mc -> mc.player.getFoodData().getFoodLevel());
                    if (mode == 0) {
                        BlinkTests.Pulser.start(ctx);
                    } else {
                        chargeOn(ctx);
                        ctx.waitTicks(25);
                    }
                    ctx.getInput().holdKey(o -> o.keyUse);
                    ctx.waitTicks(mode == 0 ? 80 : 45);
                    ctx.getInput().releaseKey(o -> o.keyUse);
                    if (mode == 0) {
                        BlinkTests.Pulser.stop(ctx);
                    }
                    ctx.waitTicks(20);
                    int food1 = ctx.computeOnClient(mc -> mc.player.getFoodData().getFoodLevel());
                    double[] s = null;
                    if (mode == 1) {
                        ctx.waitTicks(25);
                        s = shootByPress(ctx, false);
                        ctx.waitTicks(5);
                        chargeOff(ctx);
                    }
                    double g = gap(server, ctx);
                    rows.add(server, scenario, mode == 0 ? "eat in holds" : "eat charging", food1 > food0 && g < 0.5
                                    && (s == null || s[2] >= 19), String.format(Locale.ROOT, "food %d -> %d%s, gap %.2f", food0, food1,
                                    s == null ? "" : ", shot " + (long) s[2] + " tick(s)", g));
                    tp(server, ctx, x + 0.5, SURFACE_Y, z + 0.5, -90f);
                }
                // Bow while charging.
                server.command("clear " + name);
                server.command("give " + name + " minecraft:bow");
                server.command("give " + name + " minecraft:arrow 16");
                ctx.waitTicks(5);
                ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
                chargeOn(ctx);
                ctx.waitTicks(25);
                int arrows0 = ctx.computeOnClient(mc -> mc.player.getInventory().countItem(net.minecraft.world.item.Items.ARROW));
                for (int i = 0; i < 3; i++) {
                    ctx.getInput().holdKey(o -> o.keyUse);
                    ctx.waitTicks(22);
                    ctx.getInput().releaseKey(o -> o.keyUse);
                    ctx.waitTicks(10);
                }
                int arrows1 = ctx.computeOnClient(mc -> mc.player.getInventory().countItem(net.minecraft.world.item.Items.ARROW));
                ctx.waitTicks(25);
                double[] s = shootByPress(ctx, false);
                ctx.waitTicks(5);
                chargeOff(ctx);
                double g = gap(server, ctx);
                rows.add(server, scenario, "bow charging", arrows1 < arrows0 && s[2] >= 19 && g < 0.5,
                        String.format(Locale.ROOT, "arrows %d -> %d, shot %d tick(s), gap %.2f", arrows0, arrows1, (long) s[2], g));
                // Blocks, lever, chest, a hit while charging.
                tp(server, ctx, x + 0.5, SURFACE_Y, z + 0.5, -90f);
                server.command("clear " + name);
                server.command("give " + name + " minecraft:cobblestone 16");
                server.command("give " + name + " minecraft:diamond_pickaxe");
                ctx.waitTicks(5);
                TestEnemy dummy = null;
                try {
                    chargeOn(ctx);
                    ctx.waitTicks(30);
                    long bank0 = cnum(ctx, "banked");
                    ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
                    look(ctx, x - 1.5, SURFACE_Y - 0.5, z - 0.5);
                    ctx.waitTicks(2);
                    ctx.getInput().pressKey(o -> o.keyUse);
                    ctx.waitTicks(10);
                    look(ctx, x + 2.5, SURFACE_Y + 1.1, z + 2.5);
                    ctx.waitTicks(2);
                    ctx.getInput().pressKey(o -> o.keyUse);
                    ctx.waitTicks(10);
                    ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(1));
                    look(ctx, x - 1.5, SURFACE_Y + 0.5, z + 2.5);
                    ctx.waitTicks(2);
                    ctx.getInput().holdKey(o -> o.keyAttack);
                    ctx.waitTicks(20);
                    ctx.getInput().releaseKey(o -> o.keyAttack);
                    look(ctx, x + 2.5, SURFACE_Y + 0.5, z - 1.5);
                    ctx.waitTicks(2);
                    int opens = setbacksDuringReceived(ctx, "open_screen", () -> {
                        ctx.getInput().pressKey(o -> o.keyUse);
                        ctx.waitTicks(10);
                    });
                    boolean chestOpen = opens >= 1;
                    ctx.runOnClient(mc -> mc.player.closeContainer());
                    ctx.waitTicks(5);
                    dummy = TestEnemy.named("BlinkDummy", "Stress").at(x - 2.5, SURFACE_Y, z - 1.5).health(20).spawn(ctx, server);
                    look(ctx, x - 2.5, SURFACE_Y + 1.0, z - 1.5);
                    ctx.waitTicks(3);
                    ctx.getInput().pressKey(o -> o.keyAttack);
                    ctx.waitTicks(15);
                    int hits = dummy.hits();
                    ctx.runOnClient(mc -> {
                        mc.player.setYRot(mc.player.getYRot() + net.minecraft.util.Mth.wrapDegrees(90f - mc.player.getYRot()));
                        mc.player.setXRot(0f);
                    });
                    ctx.waitTicks(30);
                    long bank1 = cnum(ctx, "banked");
                    double[] s2 = shootByPress(ctx, false);
                    ctx.waitTicks(10);
                    server.command("execute if block " + (x - 2) + " " + SURFACE_Y + " " + (z - 1)
                            + " minecraft:cobblestone run say PLACED_OK");
                    server.command("execute if block " + (x + 2) + " " + (SURFACE_Y + 1) + " " + (z + 2)
                            + " minecraft:lever[powered=true] run say LEVER_OK");
                    server.command("execute if block " + (x - 2) + " " + SURFACE_Y + " " + (z + 2) + " minecraft:air run say DUG_OK");
                    ctx.waitTicks(10);
                    String tail = server.tail(80);
                    chargeOff(ctx);
                    g = gap(server, ctx);
                    rows.add(server, scenario, "place/lever/dig/chest/hit", tail.contains("LEVER_OK") && tail.contains("DUG_OK")
                                    && tail.contains("PLACED_OK") && chestOpen && s2[2] >= 19 && g < 0.5, String.format(Locale.ROOT, "bank %d -> %d; placed %s, lever %s, "
                                    + "dug %s, chest opened %s, %d hit(s) on the dummy, shot %d tick(s) %.2f blocks, gap %.2f", bank0, bank1,
                            tail.contains("PLACED_OK"), tail.contains("LEVER_OK"), tail.contains("DUG_OK"), chestOpen, hits,
                            (long) s2[2], s2[0], g));
                } finally {
                    if (dummy != null) {
                        dummy.remove();
                    }
                }
            } finally {
                ctx.getInput().releaseKey(o -> o.keyUse);
                ctx.getInput().releaseKey(o -> o.keyAttack);
                BlinkTests.Pulser.stop(ctx);
                chargeOff(ctx);
                tearDown(ctx, gate);
            }
            rows.finish(scenario, "1026-blink-items-b");
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 6. World, connection

    /**
     * 1026-blink-death: killed (a) in the middle of a sprinting hold, (b) while charging with a full bank, (c) with Charge
     * in Hold mode and its key down. Respawned: nothing held, Charge off and not restarted by a key still down, then a hold
     * and a shot work again. Grim clean.
     */
    private static void death(ClientGameTestContext ctx) {
        int x = -8300;
        int z = -6000;
        Scenario.runExpectingFlags(ctx, "1026-blink-death", (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = setUp(ctx, 20);
            Rows rows = new Rows();
            String name = playerName(ctx);
            try {
                server.command("gamerule doImmediateRespawn true");
                server.command("spawnpoint " + name + " " + x + " " + SURFACE_Y + " " + z);
                for (int k = 0; k < 3; k++) {
                    int kk = k;
                    tp(server, ctx, x + 0.5, SURFACE_Y, z + 0.5, -90f);
                    if (kk == 0) {
                        ctx.getInput().holdKey(o -> o.keyUp);
                        ctx.getInput().holdKey(o -> o.keySprint);
                        holdDown(ctx);
                        ctx.waitTicks(8);
                    } else if (kk == 1) {
                        chargeOn(ctx);
                        ctx.waitTicks(30);
                    } else {
                        ctx.runOnClient(mc -> Mod.set(CFG, "setChargeMode", Mod.enumValue(MODE, "HOLD")));
                        ctx.runOnClient(mc -> Mod.setField(CHARGE, "keyHeldForTest", Boolean.TRUE));
                        ctx.waitTicks(30);
                    }
                    boolean before = kk == 0 ? holding(ctx) : charging(ctx);
                    server.command("kill " + name);
                    ctx.waitTicks(10);
                    ctx.runOnClient(mc -> {
                        if (mc.player != null && mc.player.isDeadOrDying()) {
                            mc.player.respawn();
                        }
                    });
                    ctx.waitTicks(40);
                    releaseKeys(ctx);
                    boolean holdingAfter = holding(ctx);
                    boolean chargingAfter = charging(ctx);
                    long left = num(ctx, "heldPackets");
                    holdUp(ctx);
                    ctx.runOnClient(mc -> {
                        Mod.setField(CHARGE, "keyHeldForTest", Boolean.FALSE);
                        Mod.set(CFG, "setChargeMode", Mod.enumValue(MODE, "TOGGLE"));
                    });
                    ctx.waitTicks(5);
                    double[] h = sprintHold(ctx, true, 20);
                    tp(server, ctx, x + 0.5, SURFACE_Y, z + 0.5, -90f);
                    double[] s = chargeShot(ctx, 30, true);
                    double g = gap(server, ctx);
                    String label = kk == 0 ? "(a) die mid-hold" : kk == 1 ? "(b) die charging" : "(c) die, Hold-mode key down";
                    rows.add(server, scenario, label, before && !holdingAfter && !chargingAfter && left == 0 && h[1] == 20
                                    && s[1] == 20 && g < 0.5, String.format(Locale.ROOT, "on before %s; after respawn holding %s, "
                                    + "charging %s, %d left held; then hold %d tick(s), shot %d tick(s), gap %.2f", before, holdingAfter,
                            chargingAfter, left, (int) h[1], (long) s[1], g));
                }
            } finally {
                chargeOff(ctx);
                tearDown(ctx, gate);
                server.command("gamerule doImmediateRespawn false");
            }
            rows.finish(scenario, "1026-blink-death");
        });
    }

    /**
     * 1026-blink-disconnect: (a) the client leaves in the middle of a hold and joins again, (b) the server kicks him while
     * charging with a full bank and he joins again. Nothing held survives into the new session, Charge is off, the
     * answers queue is empty, and a hold and a shot then work. Grim clean.
     */
    private static void disconnect(ClientGameTestContext ctx) {
        int x = -8400;
        int z = -6000;
        Scenario.runExpectingFlags(ctx, "1026-blink-disconnect", (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = setUp(ctx, 20);
            Rows rows = new Rows();
            try {
                for (int k = 0; k < 2; k++) {
                    long mark = LogTap.mark();
                    boolean before;
                    if (k == 0) {
                        ctx.getInput().holdKey(o -> o.keyUp);
                        holdDown(ctx);
                        ctx.waitTicks(8);
                        before = holding(ctx);
                        releaseKeys(ctx);
                        scenario.reconnect();
                    } else {
                        chargeOn(ctx);
                        ctx.waitTicks(30);
                        before = cnum(ctx, "banked") == 20;
                        server.command("kick " + playerName(ctx) + " blink stress kick");
                        ctx.waitTicks(40);
                        scenario.reconnect();
                    }
                    ctx.waitTicks(40);
                    boolean holdingAfter = holding(ctx);
                    boolean chargingAfter = charging(ctx);
                    long left = num(ctx, "heldPackets");
                    long answers = cnum(ctx, "answersHeld");
                    holdUp(ctx);
                    ctx.runOnClient(mc -> Mod.setField(CHARGE, "keyHeldForTest", Boolean.FALSE));
                    ctx.waitTicks(5);
                    server.mark();
                    tp(server, ctx, x + 0.5, SURFACE_Y, z + 0.5, -90f);
                    double[] h = sprintHold(ctx, true, 20);
                    tp(server, ctx, x + 0.5, SURFACE_Y, z + 0.5, -90f);
                    double[] s = chargeShot(ctx, 30, true);
                    double g = gap(server, ctx);
                    boolean logged = k == 0 ? hasLog(mark, "disconnected while holding") : hasLog(mark, "charge off (disconnect)");
                    rows.add(server, scenario, k == 0 ? "(a) leave mid-hold" : "(b) kicked charging", before && !holdingAfter
                                    && !chargingAfter && left == 0 && answers == 0 && h[1] == 20 && s[1] == 20 && g < 0.5 && logged,
                            String.format(Locale.ROOT, "on before %s; after rejoin holding %s, charging %s, %d held, %d answers; "
                                    + "logged %s; then hold %d tick(s), shot %d tick(s), gap %.2f", before, holdingAfter, chargingAfter,
                                    left, answers, logged, (int) h[1], (long) s[1], g));
                }
            } finally {
                chargeOff(ctx);
                tearDown(ctx, gate);
            }
            rows.finish(scenario, "1026-blink-disconnect");
        });
    }

    /**
     * 1026-blink-dimension-charge: Charge in Hold mode with its key down when the server moves him to the Nether and back.
     * Charge ends with the world and does not start again on the new world while the key is still down (it waits for the
     * key to come up, as a hold does). Then a shot in the new world is clean.
     */
    private static void dimensionCharge(ClientGameTestContext ctx) {
        int x = -8500;
        int z = -6000;
        Scenario.run(ctx, "1026-blink-dimension-charge", (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = setUp(ctx, 20);
            String name = playerName(ctx);
            try {
                server.command("execute in minecraft:the_nether run forceload add 0 0");
                server.command("execute in minecraft:the_nether run fill -8 100 -8 8 100 8 minecraft:stone");
                server.command("execute in minecraft:the_nether run fill -8 101 -8 8 105 8 minecraft:air");
                ctx.runOnClient(mc -> {
                    Mod.set(CFG, "setChargeMode", Mod.enumValue(MODE, "HOLD"));
                    Mod.setField(CHARGE, "keyHeldForTest", Boolean.TRUE);
                });
                ctx.waitTicks(30);
                long bank = cnum(ctx, "banked");
                String dim0 = ctx.computeOnClient(mc -> mc.level.dimension().toString());
                server.command("execute in minecraft:the_nether run tp " + name + " 0.5 101 0.5");
                String dim1 = dim0;
                boolean chargedOnNew = false;
                for (int i = 0; i < 200 && dim1.equals(dim0); i++) {
                    ctx.waitTicks(1);
                    dim1 = ctx.computeOnClient(mc -> mc.level == null ? "" : mc.level.dimension().toString());
                }
                for (int i = 0; i < 60; i++) {
                    ctx.waitTicks(1);
                    chargedOnNew |= charging(ctx);
                }
                ctx.runOnClient(mc -> Mod.setField(CHARGE, "keyHeldForTest", Boolean.FALSE));
                ctx.waitTicks(2);
                ctx.runOnClient(mc -> Mod.setField(CHARGE, "keyHeldForTest", Boolean.TRUE));
                ctx.waitTicks(30);
                long bank2 = cnum(ctx, "banked");
                ctx.getInput().holdKey(o -> o.keyUp);
                double[] a = here(ctx);
                ctx.runOnClient(mc -> Mod.setField(CHARGE, "keyHeldForTest", Boolean.FALSE));
                ctx.waitTicks(1);
                double moved = flat(a, here(ctx));
                releaseKeys(ctx);
                ctx.waitTicks(10);
                double g = gap(server, ctx);
                scenario.log(String.format(Locale.ROOT, "dimension-charge: bank %d in %s; moved to %s; charging on the new world "
                        + "with the key still down %s; after key up+down bank %d, shot moved %.2f, gap %.2f", bank, dim0, dim1,
                        chargedOnNew, bank2, moved, g));
                check(bank == 20, "the bank never filled before the move: " + bank);
                check(!dim1.equals(dim0), "the dimension never changed");
                check(!chargedOnNew, "Charge started again on the new world with the key still down from the old one");
                check(bank2 == 20 && moved > 3, "after key up and down Charge did not bank and fire: bank " + bank2);
                check(g < 0.5, "client and server disagree: " + g);
            } finally {
                ctx.runOnClient(mc -> Mod.setField(CHARGE, "keyHeldForTest", Boolean.FALSE));
                chargeOff(ctx);
                tearDown(ctx, gate);
                server.command("execute in minecraft:overworld run tp " + name + " " + (x + 0.5) + " " + SURFACE_Y + " " + (z + 0.5));
                ctx.waitTicks(60);
            }
        });
    }

    /** 1026-blink-latency: 0, 50, 150 and 300 ms round trips: a sprinting 20-tick hold and a 20-tick sprinting shot each. */
    private static void latencyMatrix(ClientGameTestContext ctx) {
        int x = -8600;
        int z = -6000;
        Scenario.runExpectingFlags(ctx, "1026-blink-latency", (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = setUp(ctx, 20);
            Rows rows = new Rows();
            try {
                for (int ms : new int[]{0, 50, 150, 300}) {
                    ctx.runOnClient(mc -> {
                        Latency.remove();
                        if (ms > 0) {
                            Latency.install(ms);
                        }
                    });
                    ctx.waitTicks(40);
                    tp(server, ctx, x - 15.5, SURFACE_Y, z + 0.5, -90f);
                    server.mark();
                    double[] h = sprintHold(ctx, true, 20);
                    ctx.waitTicks(20);
                    double g = gap(server, ctx);
                    rows.add(server, scenario, ms + " ms hold", h[0] > 3 && h[1] == 20 && g < 0.5,
                            String.format(Locale.ROOT, "hold %d tick(s) moved %.2f, gap %.2f", (int) h[1], h[0], g));
                    tp(server, ctx, x - 15.5, SURFACE_Y, z + 0.5, -90f);
                    double[] s = chargeShot(ctx, 30, true);
                    ctx.waitTicks(20);
                    g = gap(server, ctx);
                    rows.add(server, scenario, ms + " ms shot", s[0] == 20 && s[1] == 20 && s[2] > 4.5 && g < 0.5,
                            String.format(Locale.ROOT, "bank %d, shot %d tick(s) %.2f blocks, gap %.2f", (long) s[0], (long) s[1], s[2], g));
                }
            } finally {
                ctx.runOnClient(mc -> Latency.remove());
                chargeOff(ctx);
                tearDown(ctx, gate);
            }
            rows.finish(scenario, "1026-blink-latency");
        });
    }

    /**
     * 1026-blink-lag: (a) a 1.5 s two-way lag spike in the middle of a sprinting hold, (b) one while charging with a full
     * bank, then a shot; (c) a 2.5 s frozen client (the render thread asleep) in the middle of a hold, (d) one while
     * charging, then a shot. Grim clean, client and server agree.
     */
    private static void lag(ClientGameTestContext ctx) {
        int x = -8700;
        int z = -6000;
        Scenario.runExpectingFlags(ctx, "1026-blink-lag", (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = setUp(ctx, 20);
            Rows rows = new Rows();
            try {
                for (int k = 0; k < 4; k++) {
                    int kk = k;
                    tp(server, ctx, x - 15.5, SURFACE_Y, z + 0.5, -90f);
                    server.mark();
                    Runnable freeze = () -> {
                        if (kk < 2) {
                            ctx.runOnClient(mc -> LagSpike.start(1500));
                            ctx.waitTicks(40);
                        } else {
                            ctx.runOnClient(mc -> {
                                try {
                                    Thread.sleep(2500);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            });
                            ctx.waitTicks(5);
                        }
                    };
                    String label = (kk < 2 ? "lag spike" : "frozen client") + (kk % 2 == 0 ? " mid-hold" : " charging");
                    if (kk % 2 == 0) {
                        long holds0 = num(ctx, "releasedHolds");
                        ctx.getInput().holdKey(o -> o.keyUp);
                        ctx.getInput().holdKey(o -> o.keySprint);
                        holdDown(ctx);
                        ctx.waitTicks(6);
                        boolean was = holding(ctx);
                        freeze.run();
                        for (int t = 0; t < 25 && holding(ctx); t++) {
                            ctx.waitTicks(1);
                        }
                        holdUp(ctx);
                        releaseKeys(ctx);
                        ctx.waitTicks(40);
                        double g = gap(server, ctx);
                        rows.add(server, scenario, label, was && num(ctx, "releasedHolds") > holds0 && num(ctx, "heldPackets") == 0
                                && g < 0.5, String.format(Locale.ROOT, "held %s, released, longest %d, gap %.2f", was,
                                num(ctx, "longestHoldTicks"), g));
                    } else {
                        chargeOn(ctx);
                        ctx.waitTicks(30);
                        long bank = cnum(ctx, "banked");
                        freeze.run();
                        ctx.waitTicks(40);
                        long bank2 = cnum(ctx, "banked");
                        double[] s = shootByPress(ctx, true);
                        ctx.waitTicks(10);
                        chargeOff(ctx);
                        ctx.waitTicks(20);
                        double g = gap(server, ctx);
                        rows.add(server, scenario, label, bank == 20 && s[2] >= 1 && g < 0.5, String.format(Locale.ROOT,
                                "bank %d before, %d after, shot %d tick(s) %.2f blocks, gap %.2f", bank, bank2, (long) s[2], s[0], g));
                    }
                }
            } finally {
                chargeOff(ctx);
                tearDown(ctx, gate);
            }
            rows.finish(scenario, "1026-blink-lag");
        });
    }

    /**
     * 1026-blink-setback-shot: the server puts him back where a shot started, 3 ticks after it (a refused shot). Blink logs
     * it as a shot setback and reports a correction; Charge stays on and the next shot is clean.
     */
    private static void setbackShot(ClientGameTestContext ctx) {
        int x = -8800;
        int z = -6000;
        Scenario.run(ctx, "1026-blink-setback-shot", (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = setUp(ctx, 20);
            try {
                String name = playerName(ctx);
                tp(server, ctx, x - 15.5, SURFACE_Y, z + 0.5, -90f);
                chargeOn(ctx);
                ctx.waitTicks(30);
                double[] start = serverPos(server, ctx, name);
                long sb0 = cnum(ctx, "shotsSetBack");
                long mark = LogTap.mark();
                double[] s = shootByPress(ctx, true);
                server.command(String.format(Locale.ROOT, "tp %s %.4f %.4f %.4f", name, start[0], start[1], start[2]));
                ctx.waitTicks(10);
                long sb = cnum(ctx, "shotsSetBack") - sb0;
                boolean still = charging(ctx);
                boolean logged = hasLog(mark, "SHOT SET BACK");
                ctx.waitTicks(30);
                double[] s2 = shootByPress(ctx, true);
                ctx.waitTicks(10);
                double g = gap(server, ctx);
                scenario.log(String.format(Locale.ROOT, "setback-shot: shot %d tick(s) %.2f blocks, set back -> counted %d, logged %s, "
                        + "still charging %s; next shot %d tick(s) %.2f blocks, gap %.2f", (long) s[2], s[0], sb, logged, still,
                        (long) s2[2], s2[0], g));
                check(s[2] == 20 && s[0] > 4.5, "the first shot did not go");
                check(sb == 1 && logged, "the setback after the shot was not noticed");
                check(still, "Charge stopped on a server correction");
                check(s2[2] == 20 && g < 0.5, "the next shot was not clean");
            } finally {
                chargeOff(ctx);
                tearDown(ctx, gate);
            }
        });
    }

    /**
     * 1026-blink-lcew: Left Click Etherwarp and Zero Ping Etherwarp both on (AOTV, 150 ms): three left-click warps while
     * charging (then a refill and a shot), and three inside back-to-back holds. Every warp reaches the server; Grim clean.
     */
    private static void lcew(ClientGameTestContext ctx) {
        int x = -8900;
        int z = -6000;
        Scenario.runExpectingFlags(ctx, "1026-blink-lcew", (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = setUp(ctx, 20);
            Rows rows = new Rows();
            dev.testkit.gametest.hx.Hx hx = null;
            try {
                hx = BlinkTests.warpSetUp(ctx);
                ctx.runOnClient(mc -> {
                    Mod.set("leftclickew.LeftClickEtherwarpConfig", "setEnabled", true);
                    Latency.install(150);
                });
                for (int mode = 0; mode < 2; mode++) {
                    int row = z - 10 + 12 * mode;
                    tp(server, ctx, x - 25.5, SURFACE_Y, row + 0.5, -90f);
                    com.google.gson.JsonObject before = hx.call("dungeon.stats").getAsJsonObject();
                    if (mode == 0) {
                        chargeOn(ctx);
                        ctx.waitTicks(30);
                    } else {
                        BlinkTests.Pulser.start(ctx);
                    }
                    double[] feet = here(ctx);
                    for (int w = 1; w <= 3; w++) {
                        int bx = x - 26 + 7 * w;
                        double dx = bx + 0.5 - feet[0];
                        double dy = SURFACE_Y - (feet[1] + 1.27);
                        double dz = row + 0.5 - feet[2];
                        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
                        ctx.runOnClient(mc -> {
                            mc.player.setYRot(mc.player.getYRot() + net.minecraft.util.Mth.wrapDegrees(yaw - mc.player.getYRot()));
                            mc.player.setXRot(pitch);
                        });
                        ctx.getInput().pressKey(o -> o.keyAttack);
                        ctx.waitTicks(8);
                        feet = new double[]{bx + 0.5, SURFACE_Y + 0.05, row + 0.5};
                    }
                    ctx.waitTicks(30);
                    if (mode == 1) {
                        BlinkTests.Pulser.stop(ctx);
                        ctx.waitTicks(20);
                    }
                    int warps = hx.call("dungeon.stats").getAsJsonObject().get("etherwarps").getAsInt()
                            - before.get("etherwarps").getAsInt();
                    double[] s = null;
                    if (mode == 0) {
                        ctx.runOnClient(mc -> mc.player.setXRot(0f));
                        ctx.waitTicks(25);
                        s = shootByPress(ctx, true);
                        ctx.waitTicks(5);
                        chargeOff(ctx);
                    }
                    double g = gap(server, ctx);
                    rows.add(server, scenario, mode == 0 ? "LCEW charging" : "LCEW in holds", warps == 3 && g < 0.5
                                    && (s == null || s[2] >= 19), String.format(Locale.ROOT, "%d server warp(s)%s, gap %.2f", warps,
                                    s == null ? "" : ", shot " + (long) s[2] + " tick(s)", g));
                }
            } finally {
                ctx.runOnClient(mc -> Mod.set("leftclickew.LeftClickEtherwarpConfig", "setEnabled", false));
                BlinkTests.Pulser.stop(ctx);
                BlinkTests.warpTearDown(ctx, hx);
                chargeOff(ctx);
                tearDown(ctx, gate);
            }
            rows.finish(scenario, "1026-blink-lcew");
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 7. Settings, commands, log

    /**
     * 1026-blink-ui: Debug Log saved and loaded; a profile reload (configs re-read from disk) in the middle of a hold and
     * of a charge; the Hold key rebound and unbound mid-hold with no test hook (the real key read); both keys unbound do
     * nothing; /blink starts ONE hold that ends at Hold Ticks; /blink release; /blink debug prints the state and the
     * events; and the log carries every kind of Blink line the owner needs.
     */
    private static void ui(ClientGameTestContext ctx) {
        int x = -9000;
        int z = -6000;
        Scenario.run(ctx, "1026-blink-ui", (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = setUp(ctx, 20);
            long mark = LogTap.mark();
            try {
                // Debug Log persists.
                boolean dbg = ctx.computeOnClient(mc -> {
                    Mod.set(CFG, "setDebugLog", true);
                    Mod.call(Mod.cfg(CFG), "save");
                    Mod.set(CFG, "setDebugLog", false);
                    Mod.staticCall(CFG, "load");
                    boolean back = (Boolean) Mod.get(CFG, "isDebugLog");
                    Mod.set(CFG, "setDebugLog", false);
                    Mod.call(Mod.cfg(CFG), "save");
                    // load() made a new instance: put the test's settings back.
                    Mod.set(CFG, "setEnabled", true);
                    Mod.set(CFG, "setHoldTicks", 20);
                    Mod.set(CFG, "setChargeTicks", 20);
                    Mod.set(CFG, "setChatMessages", false);
                    return back;
                });
                // Profile reload mid-hold and mid-charge: Blink.json on disk says OFF (saved above with enabled=true? no:
                // the save happened with the test's enabled=true) - so write it OFF first, then reload.
                ctx.runOnClient(mc -> {
                    Mod.set(CFG, "setEnabled", false);
                    Mod.call(Mod.cfg(CFG), "save");
                    Mod.set(CFG, "setEnabled", true);
                });
                ctx.getInput().holdKey(o -> o.keyUp);
                holdDown(ctx);
                ctx.waitTicks(6);
                boolean held = holding(ctx);
                ctx.runOnClient(mc -> {
                    Mod.staticCall("profiles.ProfileManager", "reloadAllConfigs");
                    Mod.staticCall("util.SkyblockGate", "setEnabled", false);
                });
                ctx.waitTicks(3);
                boolean releasedByReload = !holding(ctx) && num(ctx, "heldPackets") == 0;
                holdUp(ctx);
                releaseKeys(ctx);
                ctx.runOnClient(mc -> {
                    Mod.set(CFG, "setEnabled", true);
                    Mod.set(CFG, "setChatMessages", false);
                });
                chargeOn(ctx);
                ctx.waitTicks(30);
                boolean charged = cnum(ctx, "banked") == 20;
                ctx.runOnClient(mc -> {
                    Mod.staticCall("profiles.ProfileManager", "reloadAllConfigs");
                    Mod.staticCall("util.SkyblockGate", "setEnabled", false);
                });
                ctx.waitTicks(3);
                boolean chargeOffByReload = !charging(ctx) && cnum(ctx, "answersHeld") == 0;
                ctx.runOnClient(mc -> {
                    Mod.set(CFG, "setEnabled", true);
                    Mod.set(CFG, "setChatMessages", false);
                });
                // The real key read (no hook): bound to a key nobody presses, unbound: nothing happens.
                ctx.runOnClient(mc -> {
                    Mod.setField(FEATURE, "keyHeldForTest", null);
                    Mod.setField(CHARGE, "keyHeldForTest", null);
                    Mod.set(CFG, "setHoldKeyCode", 301);
                    Mod.set(CFG, "setChargeKeyCode", 302);
                });
                ctx.waitTicks(20);
                boolean boundIdle = !holding(ctx) && !charging(ctx);
                ctx.runOnClient(mc -> {
                    Mod.set(CFG, "setHoldKeyCode", -1);
                    Mod.set(CFG, "setChargeKeyCode", -1);
                });
                ctx.waitTicks(20);
                boolean unboundIdle = !holding(ctx) && !charging(ctx);
                // A hold through the hook, then the key rebound mid-hold (the hook still rules): no throw, released at key up.
                ctx.runOnClient(mc -> Mod.setField(FEATURE, "keyHeldForTest", Boolean.TRUE));
                ctx.waitTicks(4);
                boolean heldRebind = holding(ctx);
                ctx.runOnClient(mc -> Mod.set(CFG, "setHoldKeyCode", 71));
                ctx.waitTicks(3);
                ctx.runOnClient(mc -> Mod.setField(FEATURE, "keyHeldForTest", Boolean.FALSE));
                ctx.waitTicks(2);
                boolean releasedRebind = !holding(ctx);
                ctx.runOnClient(mc -> {
                    Mod.set(CFG, "setHoldKeyCode", -1);
                    Mod.setField(CHARGE, "keyHeldForTest", Boolean.FALSE);
                });
                // /blink: one hold, ends at Hold Ticks and does not start again.
                long holds0 = num(ctx, "releasedHolds");
                sendCommand(ctx, "blink");
                ctx.waitTicks(3);
                boolean cmdHolding = holding(ctx);
                ctx.waitTicks(60);
                long cmdHolds = num(ctx, "releasedHolds") - holds0;
                boolean cmdStill = holding(ctx);
                // /blink 5 then /blink release after 2 ticks.
                long holds1 = num(ctx, "releasedHolds");
                sendCommand(ctx, "blink 5");
                ctx.waitTicks(2);
                boolean cmd5 = holding(ctx);
                sendCommand(ctx, "blink release");
                ctx.waitTicks(2);
                boolean cmdReleased = !holding(ctx) && num(ctx, "releasedHolds") == holds1 + 1;
                ctx.runOnClient(mc -> Mod.set(CFG, "setHoldKeyCode", -1));
                // Charge events for the log: on, full, armed, shot, status (200 ticks), off.
                ctx.runOnClient(mc -> Mod.setField(CHARGE, "keyHeldForTest", Boolean.FALSE));
                chargeOn(ctx);
                ctx.waitTicks(210);
                pressCharge(ctx);
                ctx.getInput().holdKey(o -> o.keyUp);
                ctx.waitTicks(2);
                releaseKeys(ctx);
                ctx.waitTicks(25);
                pressCharge(ctx);
                ctx.waitTicks(5);
                sendCommand(ctx, "blink debug 20");
                ctx.waitTicks(5);
                chargeOff(ctx);
                List<String> dbgLines = ctx.computeOnClient(mc -> {
                    @SuppressWarnings("unchecked")
                    List<String> l = (List<String>) Mod.staticCall("blink.BlinkCommands", "debugLines", 20);
                    return l;
                });
                String[] needles = {"hold started by key", "released (key up)", "hold started by command", "released (tick limit)", "released (command)",
                        "released (switched off)", "charge on:", "bank full", "Charge key pressed (Toggle): fire",
                        "charge: ARMED", "charge SHOT", "charge status:", "charge off (", "/blink debug: Hold:",
                        "/blink debug: Charge:", "Charge Ticks 20 (Toggle"};
                List<String> missing = new ArrayList<>();
                for (String n : needles) {
                    if (!hasLog(mark, n)) {
                        missing.add(n);
                    }
                }
                int blinkLines = countLog(mark, "[Blink]");
                scenario.log("ui: debugLog persisted " + dbg + "; held " + held + ", profile reload released " + releasedByReload
                        + "; charged " + charged + ", reload ended charge " + chargeOffByReload + "; bound-unpressed idle "
                        + boundIdle + ", unbound idle " + unboundIdle + "; rebind mid-hold held " + heldRebind + " released "
                        + releasedRebind + "; /blink holding " + cmdHolding + ", " + cmdHolds + " release(s) in 60 ticks, still "
                        + "holding " + cmdStill + "; /blink 5 " + cmd5 + ", /blink release " + cmdReleased + "; /blink debug "
                        + dbgLines.size() + " line(s); " + blinkLines + " [Blink] log line(s); missing log kinds " + missing);
                for (String l : dbgLines) {
                    scenario.log("  debug> " + l);
                }
                check(dbg, "Debug Log did not survive save/load");
                check(held && releasedByReload, "a profile reload switching Blink off did not release the hold");
                check(charged && chargeOffByReload, "a profile reload switching Blink off did not end Charge cleanly");
                check(boundIdle && unboundIdle, "Blink acted with no key pressed");
                check(heldRebind && releasedRebind, "rebinding mid-hold broke the hold");
                check(cmdHolding && cmdHolds == 1 && !cmdStill, "/blink did not make exactly one hold ending at Hold Ticks ("
                        + cmdHolds + " release(s), still holding " + cmdStill + ")");
                check(cmd5 && cmdReleased, "/blink 5 or /blink release did not work");
                check(dbgLines.size() >= 5, "/blink debug printed too little");
                check(missing.isEmpty(), "log lines missing: " + missing);
            } finally {
                ctx.runOnClient(mc -> {
                    Mod.set(CFG, "setHoldKeyCode", -1);
                    Mod.set(CFG, "setChargeKeyCode", -1);
                    Mod.set(CFG, "setHoldTicks", 20);
                });
                releaseKeys(ctx);
                chargeOff(ctx);
                tearDown(ctx, gate);
            }
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 8. Long sessions

    /**
     * 1026-blink-long-holds: Hold Ticks 10, back-to-back holds by the key for 3000 ticks (2.5 min), walking back and forth.
     * At least 150 holds; the held queue never grows past what one hold makes and is empty at the end; memory does not
     * climb; no setbacks; Grim clean.
     */
    private static void longHolds(ClientGameTestContext ctx) {
        int x = -9100;
        int z = -6000;
        Scenario.run(ctx, "1026-blink-long-holds", (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = setUp(ctx, 10);
            try {
                long holds0 = num(ctx, "releasedHolds");
                long mem0 = usedMemoryMb(ctx);
                int setbacks = setbacksDuring(ctx, () -> {
                    BlinkTests.Pulser.start(ctx);
                    for (int t = 0; t < 3000; t += 50) {
                        boolean fwd = (t / 50) % 2 == 0;
                        releaseKeys(ctx);
                        ctx.getInput().holdKey(o -> fwd ? o.keyUp : o.keyDown);
                        ctx.waitTicks(50);
                    }
                    BlinkTests.Pulser.stop(ctx);
                    releaseKeys(ctx);
                    ctx.waitTicks(20);
                });
                long holds = num(ctx, "releasedHolds") - holds0;
                long peak = num(ctx, "peakQueue");
                long left = num(ctx, "heldPackets");
                long mem1 = usedMemoryMb(ctx);
                double g = gap(server, ctx);
                scenario.log(String.format(Locale.ROOT, "long-holds: %d hold(s), largest queue %d, %d left, memory %d -> %d MB, "
                        + "%d setback(s), gap %.2f", holds, peak, left, mem0, mem1, setbacks, g));
                check(holds >= 150, "only " + holds + " hold(s)");
                check(peak < 400, "the held queue grew to " + peak);
                check(left == 0, left + " packet(s) left held");
                check(setbacks == 0, setbacks + " setback(s)");
                check(g < 0.5, "client and server disagree: " + g);
            } finally {
                tearDown(ctx, gate);
            }
        });
    }

    /**
     * 1026-blink-long-shots: Charge Ticks 5, 120 shots in a row (walk forward, then back), each fired the moment the bank
     * is full. Every shot 5 ticks; the answers queue stays small; no setbacks; Grim clean.
     */
    private static void longShots(ClientGameTestContext ctx) {
        int x = -9200;
        int z = -6000;
        Scenario.run(ctx, "1026-blink-long-shots", (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = setUp(ctx, 20);
            try {
                ctx.runOnClient(mc -> Mod.set(CFG, "setChargeTicks", 5));
                tp(server, ctx, x - 10.5, SURFACE_Y, z + 0.5, -90f);
                chargeOn(ctx);
                long shots0 = cnum(ctx, "shots");
                int[] bad = {0};
                int[] maxAnswers = {0};
                long mem0 = usedMemoryMb(ctx);
                int setbacks = setbacksDuring(ctx, () -> {
                    for (int i = 0; i < 120; i++) {
                        // He slides for a few ticks after each shot (not still, not banked): 16 ticks fill a 5-tick bank.
                        ctx.waitTicks(16);
                        maxAnswers[0] = Math.max(maxAnswers[0], (int) cnum(ctx, "answersHeld"));
                        boolean fwd = (i / 6) % 2 == 0;
                        long before = cnum(ctx, "shots");
                        ctx.getInput().holdKey(o -> fwd ? o.keyUp : o.keyDown);
                        ctx.runOnClient(mc -> Mod.setField(CHARGE, "keyHeldForTest", Boolean.TRUE));
                        ctx.waitTicks(1);
                        ctx.runOnClient(mc -> Mod.setField(CHARGE, "keyHeldForTest", Boolean.FALSE));
                        ctx.getInput().releaseKey(o -> o.keyUp);
                        ctx.getInput().releaseKey(o -> o.keyDown);
                        if (cnum(ctx, "shots") != before + 1 || cnum(ctx, "lastShotTicks") != 5) {
                            bad[0]++;
                            scenario.log("shot " + i + ": shots +" + (cnum(ctx, "shots") - before) + ", last "
                                    + cnum(ctx, "lastShotTicks") + " tick(s)");
                        }
                    }
                    ctx.waitTicks(10);
                });
                long shots = cnum(ctx, "shots") - shots0;
                long mem1 = usedMemoryMb(ctx);
                chargeOff(ctx);
                double g = gap(server, ctx);
                scenario.log(String.format(Locale.ROOT, "long-shots: %d shot(s), %d not a 5-tick shot, most answers waiting %d, "
                        + "memory %d -> %d MB, %d setback(s), gap %.2f", shots, bad[0], maxAnswers[0], mem0, mem1, setbacks, g));
                check(shots >= 115, "only " + shots + " shot(s)");
                check(bad[0] <= 3, bad[0] + " press(es) did not fire a 5-tick shot");
                check(maxAnswers[0] < 60, "the answers queue grew to " + maxAnswers[0]);
                check(setbacks == 0, setbacks + " setback(s)");
                check(g < 0.5, "client and server disagree: " + g);
            } finally {
                chargeOff(ctx);
                tearDown(ctx, gate);
            }
        });
    }

    /**
     * 1026-blink-long-charge: ten minutes charging (Toggle), standing, with the camera turning now and then. Every minute:
     * the bank is full, the answers waiting stay under 40 and the oldest under 1.2 s. Then a sprinting shot of 20 ticks is
     * clean. Length from testkit.blinkLongMinutes (default 10).
     */
    private static void longCharge(ClientGameTestContext ctx) {
        int x = -9300;
        int z = -6000;
        Scenario.run(ctx, "1026-blink-long-charge", (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = setUp(ctx, 20);
            int minutes = Integer.getInteger("testkit.blinkLongMinutes", 10);
            try {
                tp(server, ctx, x - 10.5, SURFACE_Y, z + 0.5, -90f);
                chargeOn(ctx);
                long mem0 = usedMemoryMb(ctx);
                StringBuilder samples = new StringBuilder();
                int bad = 0;
                long maxWait = 0;
                for (int m = 0; m < minutes; m++) {
                    for (int s = 0; s < 6; s++) {
                        ctx.waitTicks(200);
                        float turn = (s % 2 == 0 ? 30f : -30f);
                        ctx.runOnClient(mc -> mc.player.setYRot(mc.player.getYRot() + turn));
                    }
                    long bank = cnum(ctx, "banked");
                    long waiting = cnum(ctx, "answersHeld");
                    long oldest = cnum(ctx, "oldestAnswerAgeMs");
                    maxWait = Math.max(maxWait, cnum(ctx, "maxAnswerWaitMs"));
                    samples.append(String.format(Locale.ROOT, " [min %d: bank %d, %d waiting, oldest %d ms]", m + 1, bank, waiting,
                            oldest));
                    if (bank != 20 || waiting >= 40 || oldest >= 1200 || !charging(ctx)) {
                        bad++;
                    }
                }
                long mem1 = usedMemoryMb(ctx);
                ctx.runOnClient(mc -> mc.player.setYRot(-90f + 360f * Math.round(mc.player.getYRot() / 360f)));
                ctx.waitTicks(5);
                double[] shot = shootByPress(ctx, true);
                ctx.waitTicks(10);
                chargeOff(ctx);
                double g = gap(server, ctx);
                scenario.log(String.format(Locale.ROOT, "long-charge %d min:%s; longest answer wait %d ms; memory %d -> %d MB; "
                        + "shot %d tick(s) %.2f blocks, gap %.2f", minutes, samples, maxWait, mem0, mem1, (long) shot[2], shot[0], g));
                check(bad == 0, bad + " minute sample(s) out of bounds");
                // Answers go only at a tick start, so a frame stall on a loaded machine adds to the 990 ms (1458 ms once, 2026-10-26).
                check(maxWait < 2000, "an answer waited " + maxWait + " ms");
                check(shot[2] == 20 && shot[0] > 4.5 && g < 0.5, "the shot after the long charge was not clean");
            } finally {
                chargeOff(ctx);
                tearDown(ctx, gate);
            }
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 9. A release jar's log

    /**
     * 1026-blink-release-log: on a RELEASE cheat jar (-Prelease=true, what the GitHub builds are; SKIP on a dev jar) a hold
     * and a charge shot with Debug Log OFF write no [Blink] line to the game log, and with it ON write them all; /blink
     * debug answers either way.
     */
    private static void releaseLog(ClientGameTestContext ctx) {
        String name = "1026-blink-release-log";
        if (!Mod.isCheat() || Mod.isDevTools()) {
            Scenario.skipped(name, "needs a release cheat jar");
            return;
        }
        int x = -9400;
        int z = -6000;
        Scenario.run(ctx, name, (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = setUp(ctx, 20);
            try {
                int[] lines = new int[2];
                int[] dbg = new int[2];
                for (int on = 0; on < 2; on++) {
                    boolean d = on == 1;
                    ctx.runOnClient(mc -> Mod.set(CFG, "setDebugLog", d));
                    long mark = LogTap.mark();
                    tp(server, ctx, x - 10.5, SURFACE_Y, z + 0.5, -90f);
                    double[] h = sprintHold(ctx, true, 20);
                    tp(server, ctx, x - 10.5, SURFACE_Y, z + 0.5, -90f);
                    double[] s = chargeShot(ctx, 30, true);
                    check(h[1] == 20 && s[1] == 20, "nothing was tested: hold " + h[1] + ", shot " + s[1]);
                    lines[on] = countLog(mark, "[Blink]");
                    dbg[on] = ctx.computeOnClient(mc -> ((List<?>) Mod.staticCall("blink.BlinkCommands", "debugLines", 10)).size());
                }
                scenario.log("release-log: Debug Log off " + lines[0] + " [Blink] line(s), on " + lines[1] + "; /blink debug "
                        + dbg[0] + " / " + dbg[1] + " line(s)");
                check(lines[0] == 0, "a release jar logged Blink with Debug Log off: " + lines[0]);
                check(lines[1] >= 6, "a release jar with Debug Log on logged only " + lines[1] + " Blink line(s)");
                check(dbg[0] >= 5 && dbg[1] >= 5, "/blink debug printed too little");
            } finally {
                ctx.runOnClient(mc -> Mod.set(CFG, "setDebugLog", false));
                chargeOff(ctx);
                tearDown(ctx, gate);
            }
        });
    }
}
