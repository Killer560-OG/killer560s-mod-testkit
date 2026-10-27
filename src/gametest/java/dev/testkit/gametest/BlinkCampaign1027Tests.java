package dev.testkit.gametest;

import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.Latency;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.function.Consumer;

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
import static dev.testkit.gametest.BlinkTests.holdDown;
import static dev.testkit.gametest.BlinkTests.holdUp;
import static dev.testkit.gametest.BlinkTests.holding;
import static dev.testkit.gametest.BlinkTests.num;
import static dev.testkit.gametest.BlinkTests.playerName;
import static dev.testkit.gametest.BlinkTests.pressCharge;
import static dev.testkit.gametest.BlinkTests.releaseKeys;
import static dev.testkit.gametest.BlinkTests.serverPos;
import static dev.testkit.gametest.BlinkTests.setUp;
import static dev.testkit.gametest.BlinkTests.tearDown;

/**
 * 1027-blink-c-* (the GrimAC campaign, 2026-10-27; owner: "Do lots of tests on inventory walk, zpew, and blink. Make sure
 * nothing flags; test them under every circumstance imaginable."). Combinations and long runs on top of 1025/1026:
 * Charge and Hold interleaved at every tick count, shots into every terrain at every speed, shots in the air, shots that
 * split over two ticks, a shot at every delay after a kept teleport, menus in every state, and a 30-minute mixed session
 * that watches the ping-answer queue and memory. Latency from -PblinkCampaignLatency (ms, the whole case).
 *
 * <p>Every row first proves Blink acted (bank, ticks shot, the client's and the server's own position). A row is bad if it
 * did not act or GrimAC said anything, except rows marked expected: the shot after a kept teleport (killer560 2026-10-27,
 * accepted) and a shot fired within the same second as a hold while charging (both spend GrimAC's one lag allowance, see
 * {@link #interleaveNow}); those record what GrimAC said.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class BlinkCampaign1027Tests implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext ctx) {
        AssertionError first = null;
        for (Consumer<ClientGameTestContext> c : List.<Consumer<ClientGameTestContext>>of(
                BlinkCampaign1027Tests::interleave, BlinkCampaign1027Tests::interleaveNow, BlinkCampaign1027Tests::order,
                BlinkCampaign1027Tests::terrain, BlinkCampaign1027Tests::air, BlinkCampaign1027Tests::split,
                BlinkCampaign1027Tests::menus, BlinkCampaign1027Tests::keepDelay, BlinkCampaign1027Tests::mixed)) {
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

    /** One row per sub-case: flags since the last mark, then a new mark. Expected rows record, never fail on flags. */
    static final class Rows {
        final List<String> rows = new ArrayList<>();
        final List<String> bad = new ArrayList<>();
        int flagged;
        int expectedFlagged;

        void add(TestServer server, Scenario scenario, String label, boolean ok, String detail, boolean expected) {
            List<String> flags = scenario.flags();
            if (!flags.isEmpty()) {
                if (expected) {
                    expectedFlagged++;
                } else {
                    flagged++;
                }
            }
            String row = String.format(Locale.ROOT, "%-34s %s %s; %s", label, ok ? "acted" : "DID NOT ACT", detail,
                    flags.isEmpty() ? "clean" : flags.size() + " flag(s)" + (expected ? " (expected)" : "") + ": "
                            + firstChecks(flags));
            rows.add(row);
            scenario.log(row);
            if (!ok || (!flags.isEmpty() && !expected)) {
                bad.add(label + (ok ? "" : " (did not act)") + (!flags.isEmpty() && !expected ? " (flagged)" : ""));
            }
            server.mark();
        }

        void add(TestServer server, Scenario scenario, String label, boolean ok, String detail) {
            add(server, scenario, label, ok, detail, false);
        }

        void finish(Scenario scenario, String name) {
            scenario.log("SUMMARY " + name + ": " + rows.size() + " row(s), " + flagged + " flagged, " + expectedFlagged
                    + " expected-flagged, bad " + bad);
            for (String r : rows) {
                scenario.log("  " + r);
            }
            check(!rows.isEmpty(), name + " ran no rows");
            check(bad.isEmpty(), name + ": " + bad);
        }
    }

    private static int latency() {
        return Integer.getInteger("testkit.blinkCampaignLatency", 0);
    }

    /** Installs the case's latency (if any) and says so in the log. */
    private static void installLatency(ClientGameTestContext ctx, Scenario scenario) {
        int ms = latency();
        ctx.runOnClient(mc -> {
            Latency.remove();
            if (ms > 0) {
                Latency.install(ms);
            }
        });
        scenario.log("latency " + ms + " ms");
        ctx.waitTicks(20);
    }

    private static void removeLatency(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> Latency.remove());
    }

    private static void tp(TestServer server, ClientGameTestContext ctx, double x, double y, double z, float yaw) {
        server.command(String.format(Locale.ROOT, "tp %s %.2f %.2f %.2f %.1f 0", playerName(ctx), x, y, z, yaw));
        ctx.waitTicks(20 + latency() / 25);
    }

    private static double[] here(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
    }

    private static double dist3(double[] a, double[] b) {
        return Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]));
    }

    private static double gap(TestServer server, ClientGameTestContext ctx) {
        ctx.waitTicks(10 + latency() / 25);
        return dist3(here(ctx), serverPos(server, ctx, playerName(ctx)));
    }

    private static void face(ClientGameTestContext ctx, float yaw) {
        ctx.runOnClient(mc -> {
            mc.player.setYRot(mc.player.getYRot() + net.minecraft.util.Mth.wrapDegrees(yaw - mc.player.getYRot()));
            mc.player.setXRot(0f);
        });
    }

    private static void ticks(ClientGameTestContext ctx, String charge, String hold) {
        ctx.runOnClient(mc -> {
            if (charge != null) {
                Mod.set(CFG, "setChargeTicks", Integer.parseInt(charge));
            }
            if (hold != null) {
                Mod.set(CFG, "setHoldTicks", Integer.parseInt(hold));
            }
        });
    }

    /** Waits until the bank holds {@code n} (or {@code max} ticks pass). */
    private static long waitBank(ClientGameTestContext ctx, long n, int max) {
        for (int i = 0; i < max && cnum(ctx, "banked") < n; i++) {
            ctx.waitTicks(1);
        }
        return cnum(ctx, "banked");
    }

    /**
     * A shot fired the way he fires it: movement keys down and the Charge key pressed in the same tick. {@code jump} holds
     * jump too. Returns {client moved over the firing tick and the next (a split's second half), shots made, ticks shot}.
     */
    private static double[] shoot(ClientGameTestContext ctx, boolean sprint, boolean jump) {
        long shots0 = cnum(ctx, "shots");
        long ticks0 = cnum(ctx, "shotTicks");
        double[] a = here(ctx);
        ctx.getInput().holdKey(o -> o.keyUp);
        if (sprint) {
            ctx.getInput().holdKey(o -> o.keySprint);
        }
        if (jump) {
            ctx.getInput().holdKey(o -> o.keyJump);
        }
        ctx.runOnClient(mc -> Mod.setField(CHARGE, "keyHeldForTest", Boolean.TRUE));
        ctx.waitTicks(1);
        ctx.runOnClient(mc -> Mod.setField(CHARGE, "keyHeldForTest", Boolean.FALSE));
        ctx.waitTicks(1);
        double[] b = here(ctx);
        releaseKeys(ctx);
        return new double[]{dist3(a, b), cnum(ctx, "shots") - shots0, cnum(ctx, "shotTicks") - ticks0};
    }

    /** A sprinting hold with the Hold key down until Hold Ticks releases it. Returns {moved, held ticks, released}. */
    private static double[] holdRun(ClientGameTestContext ctx, boolean sprint, int limit) {
        ctx.getInput().holdKey(o -> o.keyUp);
        if (sprint) {
            ctx.getInput().holdKey(o -> o.keySprint);
        }
        ctx.waitTicks(3);
        long holds0 = num(ctx, "releasedHolds");
        double[] a = here(ctx);
        holdDown(ctx);
        ctx.waitTicks(2);
        for (int i = 0; i < limit + 8 && holding(ctx); i++) {
            ctx.waitTicks(1);
        }
        holdUp(ctx);
        double[] b = here(ctx);
        releaseKeys(ctx);
        ctx.waitTicks(2);
        boolean released = num(ctx, "releasedHolds") > holds0;
        return new double[]{dist3(a, b), num(ctx, "lastHoldTicks"), released ? 1 : 0};
    }

    private static int[] intList(String prop, String def) {
        String s = System.getProperty(prop, def);
        String[] parts = s.split(",");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Integer.parseInt(parts[i].trim());
        }
        return out;
    }

    private static boolean full() {
        return Boolean.getBoolean("testkit.blinkCampaignFull");
    }

    private static int[] chargeCounts() {
        return full() ? range(1, 20) : intList("testkit.blinkCampaignCharges", "1,2,3,5,8,10,12,15,18,20");
    }

    private static int[] holdCounts() {
        return full() ? range(1, 20) : intList("testkit.blinkCampaignHolds", "1,3,5,10,15,20");
    }

    private static int[] range(int a, int b) {
        int[] r = new int[b - a + 1];
        for (int i = 0; i < r.length; i++) {
            r[i] = a + i;
        }
        return r;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 1. Charge and Hold interleaved

    /**
     * 1027-blink-c-interleave: Charge Ticks C x Hold Ticks H. Charge to C, a sprinting hold of H while charging (the bank is
     * kept), then he stands C + 8 ticks and fires sprinting. The shot must run C ticks, the server must follow it and GrimAC
     * must say nothing. On mod 3011406a this shot drew Timer/TimerLimit and was half set back (1026-blink-both (a)): the
     * hold sent the waiting answers early, so GrimAC no longer covered the kept bank.
     */
    private static void interleave(ClientGameTestContext ctx) {
        int x = -11000;
        int z = -6000;
        String name = "1027-blink-c-interleave";
        Scenario.runExpectingFlags(ctx, name, (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            installLatency(ctx, scenario);
            boolean gate = setUp(ctx, 20);
            Rows rows = new Rows();
            try {
                int lane = 0;
                for (int c : chargeCounts()) {
                    for (int h : holdCounts()) {
                        double lz = z - 30.5 + 6 * (lane++ % 11);
                        ticks(ctx, String.valueOf(c), String.valueOf(h));
                        tp(server, ctx, x - 20.5, SURFACE_Y, lz, -90f);
                        server.mark();
                        chargeOn(ctx);
                        long bank = waitBank(ctx, c, c + 30);
                        double[] hr = holdRun(ctx, true, h);
                        long bankAfterHold = cnum(ctx, "banked");
                        ctx.waitTicks(c + 8);
                        long unbacked = Mod.has("blink.BlinkCharge") ? unbacked(ctx) : -1;
                        long setBack0 = cnum(ctx, "shotsSetBack");
                        double[] srv0 = serverPos(server, ctx, playerName(ctx));
                        double[] s = shoot(ctx, true, false);
                        ctx.waitTicks(20);
                        double[] srv1 = serverPos(server, ctx, playerName(ctx));
                        double g = gap(server, ctx);
                        chargeOff(ctx);
                        long setBack = cnum(ctx, "shotsSetBack") - setBack0;
                        rows.add(server, scenario, "C" + c + " H" + h, bank == c && hr[2] == 1 && hr[1] == h && bankAfterHold == c
                                        && s[2] == c && g < 0.5 && setBack == 0 && flat(srv0, srv1) > 0.2 * c,
                                String.format(Locale.ROOT, "bank %d, hold %d tick(s) %.2f blocks, bank after %d, not backed before "
                                                + "the shot %d, shot %d tick(s) %.2f blocks, server moved %.2f, gap %.2f, %d set back",
                                        bank, (int) hr[1], hr[0], bankAfterHold, unbacked, (long) s[2], s[0], flat(srv0, srv1), g,
                                        setBack));
                    }
                }
            } finally {
                removeLatency(ctx);
                chargeOff(ctx);
                tearDown(ctx, gate);
            }
            rows.finish(scenario, name);
        });
    }

    private static long unbacked(ClientGameTestContext ctx) {
        try {
            return cnum(ctx, "unbacked");
        } catch (RuntimeException | AssertionError e) {
            return -1;
        }
    }

    /**
     * 1027-blink-c-interleave-now: the same, but the shot goes the tick after the hold's release. A hold and a bank share
     * GrimAC's one lag allowance (the client may be at most ~1.1 s behind real time; the hold spends H ticks of it and the
     * waiting answers it sends bring the allowance to the present), so this shot is not covered: recorded, not failed.
     */
    private static void interleaveNow(ClientGameTestContext ctx) {
        int x = -11100;
        int z = -6000;
        String name = "1027-blink-c-interleave-now";
        Scenario.runExpectingFlags(ctx, name, (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            installLatency(ctx, scenario);
            boolean gate = setUp(ctx, 20);
            Rows rows = new Rows();
            try {
                int lane = 0;
                for (int c : new int[]{2, 5, 10, 20}) {
                    for (int h : new int[]{2, 5, 10, 20}) {
                        double lz = z - 30.5 + 6 * (lane++ % 11);
                        ticks(ctx, String.valueOf(c), String.valueOf(h));
                        tp(server, ctx, x - 20.5, SURFACE_Y, lz, -90f);
                        server.mark();
                        chargeOn(ctx);
                        long bank = waitBank(ctx, c, c + 30);
                        double[] hr = holdRun(ctx, true, h);
                        double[] srv0 = serverPos(server, ctx, playerName(ctx));
                        double[] s = shoot(ctx, true, false);
                        ctx.waitTicks(40);
                        double[] srv1 = serverPos(server, ctx, playerName(ctx));
                        double g = gap(server, ctx);
                        chargeOff(ctx);
                        ctx.waitTicks(20);
                        rows.add(server, scenario, "now C" + c + " H" + h, bank == c && hr[2] == 1 && s[2] == c,
                                String.format(Locale.ROOT, "bank %d, hold %d tick(s), shot %d tick(s) %.2f blocks, server moved "
                                        + "%.2f, gap %.2f", bank, (int) hr[1], (long) s[2], s[0], flat(srv0, srv1), g), true);
                    }
                }
            } finally {
                removeLatency(ctx);
                chargeOff(ctx);
                tearDown(ctx, gate);
            }
            rows.finish(scenario, name);
        });
    }

    /**
     * 1027-blink-c-order: the other orders, Charge Ticks 5/10/20 - (a) a hold first, then a fresh charge and its shot; (b) a
     * shot, then a hold the very next tick; (c) the Charge key fired and the Hold key down in the same tick (the shot runs,
     * then the hold holds what follows); (d) Charge switched on in the middle of a hold, then the shot after it; (e) Hold-mode
     * Charge key and Hold key down together, the Charge key let go first (it fires inside the hold), the Hold key after.
     * Rows after a hold wait for the bank to be backed again (C + 8 still ticks) before shooting.
     */
    private static void order(ClientGameTestContext ctx) {
        int x = -11200;
        int z = -6000;
        String name = "1027-blink-c-order";
        Scenario.runExpectingFlags(ctx, name, (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            installLatency(ctx, scenario);
            boolean gate = setUp(ctx, 20);
            Rows rows = new Rows();
            try {
                int lane = 0;
                for (int c : new int[]{5, 10, 20}) {
                    ticks(ctx, String.valueOf(c), "10");
                    // (a)
                    tp(server, ctx, x - 20.5, SURFACE_Y, z - 30.5 + 6 * (lane++ % 11), -90f);
                    server.mark();
                    double[] hr = holdRun(ctx, true, 10);
                    ctx.waitTicks(5);
                    chargeOn(ctx);
                    long bank = waitBank(ctx, c, c + 30);
                    double[] s = shoot(ctx, true, false);
                    ctx.waitTicks(10);
                    chargeOff(ctx);
                    double g = gap(server, ctx);
                    rows.add(server, scenario, "(a) hold, then charge C" + c, hr[2] == 1 && bank == c && s[2] == c && g < 0.5,
                            String.format(Locale.ROOT, "hold %d tick(s), bank %d, shot %d tick(s) %.2f, gap %.2f", (int) hr[1],
                                    bank, (long) s[2], s[0], g));
                    // (b)
                    tp(server, ctx, x - 20.5, SURFACE_Y, z - 30.5 + 6 * (lane++ % 11), -90f);
                    server.mark();
                    chargeOn(ctx);
                    bank = waitBank(ctx, c, c + 30);
                    long holds0 = num(ctx, "releasedHolds");
                    ctx.getInput().holdKey(o -> o.keyUp);
                    ctx.getInput().holdKey(o -> o.keySprint);
                    ctx.runOnClient(mc -> Mod.setField(CHARGE, "keyHeldForTest", Boolean.TRUE));
                    ctx.waitTicks(1);
                    ctx.runOnClient(mc -> Mod.setField(CHARGE, "keyHeldForTest", Boolean.FALSE));
                    long shotTicks = cnum(ctx, "lastShotTicks");
                    holdDown(ctx);
                    for (int i = 0; i < 20 && (holding(ctx) || num(ctx, "releasedHolds") == holds0); i++) {
                        ctx.waitTicks(1);
                    }
                    holdUp(ctx);
                    releaseKeys(ctx);
                    ctx.waitTicks(5);
                    chargeOff(ctx);
                    g = gap(server, ctx);
                    rows.add(server, scenario, "(b) shot, hold next tick C" + c, bank == c && shotTicks == c
                                    && num(ctx, "releasedHolds") > holds0 && g < 0.5,
                            String.format(Locale.ROOT, "bank %d, shot %d tick(s), hold %d tick(s), gap %.2f", bank, shotTicks,
                                    num(ctx, "lastHoldTicks"), g));
                    // (c)
                    tp(server, ctx, x - 20.5, SURFACE_Y, z - 30.5 + 6 * (lane++ % 11), -90f);
                    server.mark();
                    chargeOn(ctx);
                    bank = waitBank(ctx, c, c + 30);
                    holds0 = num(ctx, "releasedHolds");
                    ctx.getInput().holdKey(o -> o.keyUp);
                    ctx.getInput().holdKey(o -> o.keySprint);
                    ctx.runOnClient(mc -> {
                        Mod.setField(CHARGE, "keyHeldForTest", Boolean.TRUE);
                        Mod.setField(FEATURE, "keyHeldForTest", Boolean.TRUE);
                    });
                    ctx.waitTicks(1);
                    ctx.runOnClient(mc -> Mod.setField(CHARGE, "keyHeldForTest", Boolean.FALSE));
                    shotTicks = cnum(ctx, "lastShotTicks");
                    for (int i = 0; i < 20 && holding(ctx); i++) {
                        ctx.waitTicks(1);
                    }
                    holdUp(ctx);
                    releaseKeys(ctx);
                    ctx.waitTicks(5);
                    chargeOff(ctx);
                    g = gap(server, ctx);
                    rows.add(server, scenario, "(c) fire + hold same tick C" + c, bank == c && shotTicks == c
                                    && num(ctx, "releasedHolds") > holds0 && g < 0.5,
                            String.format(Locale.ROOT, "bank %d, shot %d tick(s), hold %d tick(s), gap %.2f", bank, shotTicks,
                                    num(ctx, "lastHoldTicks"), g));
                    // (d)
                    tp(server, ctx, x - 20.5, SURFACE_Y, z - 30.5 + 6 * (lane++ % 11), -90f);
                    server.mark();
                    holds0 = num(ctx, "releasedHolds");
                    holdDown(ctx);
                    ctx.waitTicks(4);
                    pressCharge(ctx);
                    boolean chargingInHold = charging(ctx) && holding(ctx);
                    for (int i = 0; i < 20 && holding(ctx); i++) {
                        ctx.waitTicks(1);
                    }
                    holdUp(ctx);
                    bank = waitBank(ctx, c, c + 40);
                    ctx.waitTicks(c + 8);
                    s = shoot(ctx, true, false);
                    ctx.waitTicks(10);
                    chargeOff(ctx);
                    g = gap(server, ctx);
                    rows.add(server, scenario, "(d) charge on inside a hold C" + c, chargingInHold
                                    && num(ctx, "releasedHolds") > holds0 && bank == c && s[2] == c && g < 0.5,
                            String.format(Locale.ROOT, "charging inside the hold %s, bank %d, shot %d tick(s) %.2f, gap %.2f",
                                    chargingInHold, bank, (long) s[2], s[0], g));
                    // (e)
                    tp(server, ctx, x - 20.5, SURFACE_Y, z - 30.5 + 6 * (lane++ % 11), -90f);
                    server.mark();
                    ctx.runOnClient(mc -> Mod.set(CFG, "setChargeMode", Mod.enumValue(MODE, "HOLD")));
                    ctx.runOnClient(mc -> Mod.setField(CHARGE, "keyHeldForTest", Boolean.TRUE));
                    bank = waitBank(ctx, c, c + 30);
                    holds0 = num(ctx, "releasedHolds");
                    long shots0 = cnum(ctx, "shots");
                    ctx.getInput().holdKey(o -> o.keyUp);
                    ctx.getInput().holdKey(o -> o.keySprint);
                    holdDown(ctx);
                    ctx.waitTicks(3);
                    ctx.runOnClient(mc -> Mod.setField(CHARGE, "keyHeldForTest", Boolean.FALSE));
                    ctx.waitTicks(4);
                    long shotsInHold = cnum(ctx, "shots") - shots0;
                    boolean wasHolding = holding(ctx);
                    holdUp(ctx);
                    releaseKeys(ctx);
                    ctx.waitTicks(5);
                    ctx.runOnClient(mc -> Mod.set(CFG, "setChargeMode", Mod.enumValue(MODE, "TOGGLE")));
                    chargeOff(ctx);
                    g = gap(server, ctx);
                    rows.add(server, scenario, "(e) Hold-mode fire inside a hold C" + c, bank == c && shotsInHold == 1
                                    && wasHolding && num(ctx, "releasedHolds") > holds0 && g < 0.5,
                            String.format(Locale.ROOT, "bank %d, %d shot(s) inside the hold (%d tick(s)), gap %.2f", bank,
                                    shotsInHold, cnum(ctx, "lastShotTicks"), g));
                }
            } finally {
                removeLatency(ctx);
                ctx.runOnClient(mc -> Mod.set(CFG, "setChargeMode", Mod.enumValue(MODE, "TOGGLE")));
                chargeOff(ctx);
                tearDown(ctx, gate);
            }
            rows.finish(scenario, name);
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 2. Terrain x speed, the air, splits

    private record Terrain(String name, String kind, double minMove) {
    }

    private static final List<Terrain> TERRAINS = List.of(
            new Terrain("flat", "flat", 1.0),
            new Terrain("wall", "wall", 0.2),
            new Terrain("edge", "edge", 1.0),
            new Terrain("stairs", "stairs", 0.8),
            new Terrain("slabs", "slabs", 0.8),
            new Terrain("water", "water", 0.2),
            new Terrain("ladder", "ladder", 0.1),
            new Terrain("cobweb", "cobweb", 0.0),
            new Terrain("ice", "ice", 1.0),
            new Terrain("soul sand", "soulsand", 0.3));

    private record Speed(String name, boolean sprint, String effect, int amp) {
    }

    private static final List<Speed> SPEEDS = List.of(
            new Speed("walk", false, null, 0),
            new Speed("sprint", true, null, 0),
            new Speed("speed I", true, "minecraft:speed", 0),
            new Speed("speed II", true, "minecraft:speed", 1),
            new Speed("speed III", true, "minecraft:speed", 2),
            new Speed("speed IV", true, "minecraft:speed", 3),
            new Speed("slowness II", true, "minecraft:slowness", 1));

    /** Builds terrain {@code kind} from x0 + 3 (ahead of where he stands) along the lane z (3 wide). */
    private static void buildTerrain(TestServer s, String kind, int x0, int z) {
        int y = SURFACE_Y;
        int fy = y - 1;
        int a = x0 + 3;
        int b = x0 + 30;
        String box = "%d %d %d %d %d %d";
        switch (kind) {
            case "wall" -> s.command("fill " + String.format(Locale.ROOT, box, a + 1, y, z - 1, a + 1, y + 3, z + 1) + " minecraft:stone");
            case "edge" -> {
                s.command("fill " + String.format(Locale.ROOT, box, a, fy - 6, z - 1, b, fy, z + 1) + " minecraft:air");
                s.command("fill " + String.format(Locale.ROOT, box, a, fy - 7, z - 1, b, fy - 7, z + 1) + " minecraft:stone");
            }
            case "stairs" -> {
                for (int i = 0; i < 8; i++) {
                    s.command("fill " + String.format(Locale.ROOT, box, a + i, y + i, z - 1, a + i, y + i, z + 1)
                            + " minecraft:stone_brick_stairs[facing=east]");
                    if (i > 0) {
                        s.command("fill " + String.format(Locale.ROOT, box, a + i, y, z - 1, a + i, y + i - 1, z + 1) + " minecraft:stone");
                    }
                }
                s.command("fill " + String.format(Locale.ROOT, box, a + 8, y, z - 1, b, y + 7, z + 1) + " minecraft:stone");
            }
            case "slabs" -> {
                for (int i = 0; i < 27; i += 2) {
                    s.command("fill " + String.format(Locale.ROOT, box, a + i, y, z - 1, a + i, y, z + 1) + " minecraft:stone_slab");
                }
            }
            case "water" -> s.command("fill " + String.format(Locale.ROOT, box, a, fy - 2, z - 1, b, fy, z + 1) + " minecraft:water");
            case "ladder" -> {
                s.command("fill " + String.format(Locale.ROOT, box, a + 1, y, z - 1, a + 1, y + 14, z + 1) + " minecraft:stone");
                s.command("fill " + String.format(Locale.ROOT, box, a, y, z, a, y + 14, z) + " minecraft:ladder[facing=west]");
            }
            case "cobweb" -> s.command("fill " + String.format(Locale.ROOT, box, a, y, z - 1, b, y, z + 1) + " minecraft:cobweb");
            case "ice" -> s.command("fill " + String.format(Locale.ROOT, box, x0 - 2, fy, z - 1, b, fy, z + 1) + " minecraft:ice");
            case "soulsand" -> s.command("fill " + String.format(Locale.ROOT, box, a, fy, z - 1, b, fy, z + 1) + " minecraft:soul_sand");
            default -> { }
        }
    }

    /**
     * 1027-blink-c-terrain: a 20-tick shot from flat ground into each terrain (a wall, an edge with a 6-block drop, stairs
     * up, slabs, water, a ladder, cobwebs, ice, soul sand, and flat as the control) at every speed (walk, sprint, Speed
     * I-IV, Slowness II). Speed III/IV shots run more than 9.5 blocks and split over two ticks.
     */
    private static void terrain(ClientGameTestContext ctx) {
        int x = -11300;
        int z = -6000;
        String name = "1027-blink-c-terrain";
        Scenario.runExpectingFlags(ctx, name, (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            int x0 = x - 30;
            for (int i = 0; i < TERRAINS.size(); i++) {
                buildTerrain(server, TERRAINS.get(i).kind(), x0, z - 33 + 7 * i);
            }
            ctx.waitTicks(20);
            installLatency(ctx, scenario);
            boolean gate = setUp(ctx, 20);
            Rows rows = new Rows();
            String player = playerName(ctx);
            try {
                for (Speed sp : SPEEDS) {
                    if (sp.effect() != null) {
                        server.command("effect give " + player + " " + sp.effect() + " 600 " + sp.amp() + " true");
                    }
                    for (int i = 0; i < TERRAINS.size(); i++) {
                        Terrain t = TERRAINS.get(i);
                        tp(server, ctx, x0 + 0.5, SURFACE_Y, z - 33 + 7 * i + 0.5, -90f);
                        server.mark();
                        chargeOn(ctx);
                        long bank = waitBank(ctx, 20, 50);
                        long split0 = cnum(ctx, "shots");
                        double[] a = here(ctx);
                        double[] s = shoot(ctx, sp.sprint(), false);
                        ctx.waitTicks(3);
                        long shotsMade = cnum(ctx, "shots") - split0;
                        double moved = dist3(a, here(ctx));
                        ctx.waitTicks(20);
                        chargeOff(ctx);
                        double g = gap(server, ctx);
                        rows.add(server, scenario, t.name() + " / " + sp.name(), bank == 20 && s[2] == 20 && moved >= t.minMove()
                                        && g < 0.6, String.format(Locale.ROOT, "bank %d, %d tick(s) in %d shot(s), moved %.2f, gap %.2f",
                                bank, (long) s[2], shotsMade, moved, g));
                    }
                    if (sp.effect() != null) {
                        server.command("effect clear " + player);
                        ctx.waitTicks(5);
                    }
                }
            } finally {
                server.command("effect clear " + player);
                removeLatency(ctx);
                releaseKeys(ctx);
                chargeOff(ctx);
                tearDown(ctx, gate);
            }
            rows.finish(scenario, name);
        });
    }

    /**
     * 1027-blink-c-air: shots in the air - (a) fired with jump held (a sprint-jump shot), (b) fired at the top of a jump,
     * (c) fired 3 ticks into a fall off a 7-block edge, (d) a shot that runs off that edge, (e) Jump Boost II shot with jump
     * held; each sprinting and at Speed II.
     */
    private static void air(ClientGameTestContext ctx) {
        int x = -11400;
        int z = -6000;
        String name = "1027-blink-c-air";
        Scenario.runExpectingFlags(ctx, name, (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            int x0 = x - 30;
            buildTerrain(server, "edge", x0, z + 10);
            ctx.waitTicks(20);
            installLatency(ctx, scenario);
            boolean gate = setUp(ctx, 20);
            Rows rows = new Rows();
            String player = playerName(ctx);
            try {
                for (int sp = 0; sp < 2; sp++) {
                    String spName = sp == 0 ? "sprint" : "speed II";
                    if (sp == 1) {
                        server.command("effect give " + player + " minecraft:speed 600 1 true");
                    }
                    for (String kind : new String[]{"jump held", "jump apex", "falling", "run off edge", "jump boost"}) {
                        boolean edge = kind.equals("falling") || kind.equals("run off edge");
                        double lz = edge ? z + 10.5 : z - 10.5;
                        tp(server, ctx, edge ? x0 + 0.5 : x0 + 0.5, SURFACE_Y, lz, -90f);
                        if (kind.equals("jump boost")) {
                            server.command("effect give " + player + " minecraft:jump_boost 600 1 true");
                        }
                        server.mark();
                        chargeOn(ctx);
                        long bank = waitBank(ctx, 20, 50);
                        double[] a = here(ctx);
                        double[] s;
                        if (kind.equals("jump apex")) {
                            ctx.getInput().holdKey(o -> o.keyJump);
                            ctx.waitTicks(1);
                            ctx.getInput().releaseKey(o -> o.keyJump);
                            ctx.waitTicks(5);
                            s = shoot(ctx, true, false);
                        } else if (kind.equals("falling")) {
                            // Walk off the edge (2.5 blocks ahead) without firing, then fire 3 ticks into the fall.
                            ctx.getInput().holdKey(o -> o.keyUp);
                            boolean fell = false;
                            for (int i = 0; i < 40 && !fell; i++) {
                                ctx.waitTicks(1);
                                fell = ctx.computeOnClient(mc -> !mc.player.onGround() && mc.player.getDeltaMovement().y < -0.1);
                            }
                            ctx.getInput().releaseKey(o -> o.keyUp);
                            ctx.waitTicks(2);
                            s = shoot(ctx, true, false);
                        } else {
                            s = shoot(ctx, true, kind.equals("jump held") || kind.equals("jump boost"));
                        }
                        ctx.waitTicks(40);
                        chargeOff(ctx);
                        double g = gap(server, ctx);
                        double moved = dist3(a, here(ctx));
                        rows.add(server, scenario, kind + " / " + spName, bank == 20 && s[2] == 20 && moved > 2 && g < 0.6,
                                String.format(Locale.ROOT, "bank %d, %d tick(s) in %d shot(s), %.2f in the firing tick(s), %.2f in "
                                        + "all, gap %.2f", bank, (long) s[2], (long) s[1], s[0], moved, g));
                        if (kind.equals("jump boost")) {
                            server.command("effect clear " + player + " minecraft:jump_boost");
                        }
                    }
                }
            } finally {
                server.command("effect clear " + player);
                removeLatency(ctx);
                releaseKeys(ctx);
                chargeOff(ctx);
                tearDown(ctx, gate);
            }
            rows.finish(scenario, name);
        });
    }

    /**
     * 1027-blink-c-split: shots longer than 9.5 blocks run over two ticks (the vanilla server sets back more than 10 blocks
     * in one of its ticks). Speed III and IV, sprinting and sprint-jumping, at 0/50/150/300 ms: the shot must split (two
     * shot lines, 20 ticks in all), the server must follow it all the way, and GrimAC must say nothing.
     */
    private static void split(ClientGameTestContext ctx) {
        int x = -11500;
        int z = -6000;
        String name = "1027-blink-c-split";
        Scenario.runExpectingFlags(ctx, name, (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = setUp(ctx, 20);
            Rows rows = new Rows();
            String player = playerName(ctx);
            try {
                int lane = 0;
                for (int ms : new int[]{0, 50, 150, 300}) {
                    ctx.runOnClient(mc -> {
                        Latency.remove();
                        if (ms > 0) {
                            Latency.install(ms);
                        }
                    });
                    ctx.waitTicks(20);
                    for (int amp : new int[]{2, 3}) {
                        server.command("effect give " + player + " minecraft:speed 600 " + amp + " true");
                        for (boolean jump : new boolean[]{false, true}) {
                            tp(server, ctx, x - 30.5, SURFACE_Y, z - 30.5 + 6 * (lane++ % 11), -90f);
                            ctx.waitTicks(ms / 25);
                            server.mark();
                            chargeOn(ctx);
                            long bank = waitBank(ctx, 20, 50);
                            double[] srv0 = serverPos(server, ctx, player);
                            double[] a = here(ctx);
                            double[] s = shoot(ctx, true, jump);
                            ctx.waitTicks(30 + ms / 25);
                            double[] srv1 = serverPos(server, ctx, player);
                            chargeOff(ctx);
                            double g = gap(server, ctx);
                            double moved = flat(a, srv1);
                            rows.add(server, scenario, ms + " ms speed " + (amp + 1) + (jump ? " jump" : ""), bank == 20 && s[2] == 20
                                            && s[1] == 2 && flat(srv0, srv1) > 9.5 && g < 0.6,
                                    String.format(Locale.ROOT, "bank %d, %d tick(s) in %d shot(s), %.2f over the two ticks, server "
                                            + "moved %.2f, gap %.2f", bank, (long) s[2], (long) s[1], s[0], flat(srv0, srv1), g));
                        }
                        server.command("effect clear " + player);
                        ctx.waitTicks(5);
                    }
                }
            } finally {
                server.command("effect clear " + player);
                removeLatency(ctx);
                releaseKeys(ctx);
                chargeOff(ctx);
                tearDown(ctx, gate);
            }
            rows.finish(scenario, name);
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 3. Menus in every state

    private static void openInventory(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> dev.testkit.compat.McCompat.setScreen(mc,
                new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player)));
    }

    private static void closeScreen(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) {
                mc.player.closeContainer();
            }
            dev.testkit.compat.McCompat.setScreen(mc, null);
        });
    }

    /**
     * 1027-blink-c-menus: a screen in each state - (a) the bank half full: inventory 20 ticks (it keeps filling), (b) armed:
     * inventory open with W down does not fire, closing it does, (c) the second half of a split shot runs with the
     * inventory opened between its two ticks, (d) a hold while charging with a chest opened and clicked inside it, then the
     * shot once backed, (e) pause screen with a full bank for 2 s, then a shot, (f) chat open while holding.
     */
    private static void menus(ClientGameTestContext ctx) {
        int x = -11600;
        int z = -6000;
        String name = "1027-blink-c-menus";
        Scenario.runExpectingFlags(ctx, name, (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            installLatency(ctx, scenario);
            boolean gate = setUp(ctx, 20);
            Rows rows = new Rows();
            dev.testkit.gametest.hx.Hx hx = null;
            String player = playerName(ctx);
            try {
                hx = dev.testkit.gametest.hx.Hx.connect();
                // (a)
                tp(server, ctx, x - 20.5, SURFACE_Y, z - 20.5, -90f);
                server.mark();
                chargeOn(ctx);
                ctx.waitTicks(8);
                long bankA0 = cnum(ctx, "banked");
                openInventory(ctx);
                ctx.waitTicks(20);
                long bankA1 = cnum(ctx, "banked");
                closeScreen(ctx);
                ctx.waitTicks(5);
                double[] s = shoot(ctx, true, false);
                ctx.waitTicks(10);
                chargeOff(ctx);
                double g = gap(server, ctx);
                rows.add(server, scenario, "(a) filling, inventory", bankA0 < 20 && bankA1 == 20 && s[2] == 20 && g < 0.5,
                        String.format(Locale.ROOT, "bank %d before, %d in the inventory, shot %d, gap %.2f", bankA0, bankA1,
                                (long) s[2], g));
                // (b)
                tp(server, ctx, x - 20.5, SURFACE_Y, z - 14.5, -90f);
                server.mark();
                chargeOn(ctx);
                waitBank(ctx, 20, 50);
                pressCharge(ctx);
                boolean arm = armed(ctx);
                openInventory(ctx);
                ctx.getInput().holdKey(o -> o.keyUp);
                ctx.getInput().holdKey(o -> o.keySprint);
                long shots0 = cnum(ctx, "shots");
                ctx.waitTicks(10);
                boolean firedUnder = cnum(ctx, "shots") > shots0;
                closeScreen(ctx);
                ctx.waitTicks(3);
                boolean firedAfter = cnum(ctx, "shots") > shots0;
                releaseKeys(ctx);
                ctx.waitTicks(10);
                chargeOff(ctx);
                g = gap(server, ctx);
                rows.add(server, scenario, "(b) armed, inventory", arm && !firedUnder && firedAfter && g < 0.5,
                        String.format(Locale.ROOT, "armed %s, fired under the screen %s, after it %s (%d tick(s)), gap %.2f", arm,
                                firedUnder, firedAfter, cnum(ctx, "lastShotTicks"), g));
                // (c)
                server.command("effect give " + player + " minecraft:speed 600 3 true");
                tp(server, ctx, x - 30.5, SURFACE_Y, z - 8.5, -90f);
                server.mark();
                chargeOn(ctx);
                waitBank(ctx, 20, 50);
                shots0 = cnum(ctx, "shots");
                ctx.getInput().holdKey(o -> o.keyUp);
                ctx.getInput().holdKey(o -> o.keySprint);
                ctx.runOnClient(mc -> Mod.setField(CHARGE, "keyHeldForTest", Boolean.TRUE));
                ctx.waitTicks(1);
                ctx.runOnClient(mc -> Mod.setField(CHARGE, "keyHeldForTest", Boolean.FALSE));
                openInventory(ctx);
                ctx.waitTicks(3);
                long shotsC = cnum(ctx, "shots") - shots0;
                closeScreen(ctx);
                releaseKeys(ctx);
                ctx.waitTicks(20);
                chargeOff(ctx);
                server.command("effect clear " + player);
                g = gap(server, ctx);
                rows.add(server, scenario, "(c) split shot, inventory between", shotsC == 2 && g < 0.6,
                        String.format(Locale.ROOT, "%d shot line(s), gap %.2f", shotsC, g));
                // (d)
                tp(server, ctx, x - 20.5, SURFACE_Y, z - 2.5, -90f);
                server.mark();
                chargeOn(ctx);
                long bankD = waitBank(ctx, 20, 50);
                long ev0 = hx.head();
                holdDown(ctx);
                ctx.waitTicks(2);
                hx.menu("Blink Campaign Chest", 3, java.util.Map.of(11, "minecraft:stone"), null);
                boolean opened = false;
                for (int t = 0; t < 10 && !opened; t++) {
                    ctx.waitTicks(1);
                    opened = BlinkTests.menuOpen(ctx);
                }
                BlinkTests.clickSlot(ctx, 11);
                ctx.waitTicks(2);
                closeScreen(ctx);
                for (int t = 0; t < 20 && holding(ctx); t++) {
                    ctx.waitTicks(1);
                }
                holdUp(ctx);
                long clicks = hx.events(ev0, "menu.click").size();
                ctx.waitTicks(28);
                s = shoot(ctx, true, false);
                ctx.waitTicks(10);
                chargeOff(ctx);
                g = gap(server, ctx);
                rows.add(server, scenario, "(d) hold + chest while charging", bankD == 20 && opened && clicks >= 1 && s[2] == 20
                        && g < 0.5, String.format(Locale.ROOT, "bank %d, chest %s, %d click(s), shot %d tick(s), gap %.2f", bankD,
                        opened, clicks, (long) s[2], g));
                // (e)
                tp(server, ctx, x - 20.5, SURFACE_Y, z + 3.5, -90f);
                server.mark();
                chargeOn(ctx);
                waitBank(ctx, 20, 50);
                ctx.runOnClient(mc -> dev.testkit.compat.McCompat.setScreen(mc, new net.minecraft.client.gui.screens.PauseScreen(true)));
                ctx.waitTicks(40);
                closeScreen(ctx);
                long bankE = cnum(ctx, "banked");
                s = shoot(ctx, true, false);
                ctx.waitTicks(10);
                chargeOff(ctx);
                g = gap(server, ctx);
                rows.add(server, scenario, "(e) pause with a full bank", bankE == 20 && s[2] == 20 && g < 0.5,
                        String.format(Locale.ROOT, "bank %d after, shot %d, gap %.2f", bankE, (long) s[2], g));
                // (f)
                tp(server, ctx, x - 20.5, SURFACE_Y, z + 9.5, -90f);
                server.mark();
                long holds0 = num(ctx, "releasedHolds");
                ctx.getInput().holdKey(o -> o.keyUp);
                holdDown(ctx);
                ctx.waitTicks(3);
                ctx.runOnClient(mc -> dev.testkit.compat.McCompat.setScreen(mc, new net.minecraft.client.gui.screens.ChatScreen("", false)));
                ctx.waitTicks(5);
                boolean heldUnderChat = holding(ctx);
                closeScreen(ctx);
                for (int t = 0; t < 20 && holding(ctx); t++) {
                    ctx.waitTicks(1);
                }
                holdUp(ctx);
                releaseKeys(ctx);
                ctx.waitTicks(5);
                g = gap(server, ctx);
                rows.add(server, scenario, "(f) chat while holding", heldUnderChat && num(ctx, "releasedHolds") > holds0 && g < 0.5,
                        String.format(Locale.ROOT, "held under chat %s, gap %.2f", heldUnderChat, g));
            } finally {
                closeScreen(ctx);
                server.command("effect clear " + player);
                if (hx != null) {
                    hx.close();
                }
                removeLatency(ctx);
                releaseKeys(ctx);
                chargeOff(ctx);
                tearDown(ctx, gate);
            }
            rows.finish(scenario, name);
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 4. The kept teleport, by delay

    /**
     * 1027-blink-c-keep-delay (measurement; killer560 2026-10-27 keeps the bank through every teleport and accepts that
     * GrimAC flags the next shot): bank 20, a server teleport of 4 blocks (and one in place), then a 20-tick shot D ticks
     * later, D = 2..200. Records what GrimAC says at the teleport and at the shot, and whether the server followed the
     * shot. Charge off (the control): the same teleport and a walk are clean.
     */
    private static void keepDelay(ClientGameTestContext ctx) {
        int x = -11700;
        int z = -6000;
        String name = "1027-blink-c-keep-delay";
        Scenario.runExpectingFlags(ctx, name, (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            installLatency(ctx, scenario);
            boolean gate = setUp(ctx, 20);
            Rows rows = new Rows();
            String player = playerName(ctx);
            try {
                int lane = 0;
                // Control: Charge off, the same teleport, a sprint.
                tp(server, ctx, x - 20.5, SURFACE_Y, z - 30.5, -90f);
                server.mark();
                server.command(String.format(Locale.ROOT, "tp %s %.2f %.2f %.2f", player, x - 16.5, (double) SURFACE_Y, z - 30.5));
                ctx.waitTicks(20);
                ctx.getInput().holdKey(o -> o.keyUp);
                ctx.getInput().holdKey(o -> o.keySprint);
                ctx.waitTicks(20);
                releaseKeys(ctx);
                double g = gap(server, ctx);
                rows.add(server, scenario, "control: no charge, tp, sprint", g < 0.5, String.format(Locale.ROOT, "gap %.2f", g));
                lane++;
                for (boolean inPlace : new boolean[]{false, true}) {
                    for (int d : inPlace ? new int[]{2, 20, 60, 200} : new int[]{2, 10, 20, 30, 40, 60, 100, 200}) {
                        double lz = z - 30.5 + 6 * (lane++ % 11);
                        tp(server, ctx, x - 20.5, SURFACE_Y, lz, -90f);
                        server.mark();
                        chargeOn(ctx);
                        long bank = waitBank(ctx, 20, 50);
                        double tx = inPlace ? x - 20.5 : x - 16.5;
                        long tps0 = cnum(ctx, inPlace ? "inPlaceTeleports" : "movingTeleports");
                        server.command(String.format(Locale.ROOT, "tp %s %.2f %.2f %.2f", player, tx, (double) SURFACE_Y, lz));
                        for (int i = 0; i < 40 && cnum(ctx, inPlace ? "inPlaceTeleports" : "movingTeleports") == tps0; i++) {
                            ctx.waitTicks(1);
                        }
                        long bankAfterTp = cnum(ctx, "banked");
                        ctx.waitTicks(Math.max(0, d - 1));
                        List<String> atTp = new ArrayList<>(scenario.flags());
                        server.mark();
                        double[] srv0 = serverPos(server, ctx, player);
                        long setBack0 = cnum(ctx, "shotsSetBack");
                        double[] s = shoot(ctx, true, false);
                        ctx.waitTicks(40);
                        double[] srv1 = serverPos(server, ctx, player);
                        chargeOff(ctx);
                        g = gap(server, ctx);
                        long setBack = cnum(ctx, "shotsSetBack") - setBack0;
                        double serverMoved = flat(srv0, srv1);
                        boolean landed = serverMoved > 4.5;
                        rows.add(server, scenario, (inPlace ? "in place" : "moving 4") + ", shot after " + d + " tick(s)",
                                bank == 20 && bankAfterTp == 20 && s[2] == 20,
                                String.format(Locale.ROOT, "bank %d, kept %d; at the tp %s; shot %d tick(s) %.2f, server moved %.2f "
                                                + "(%s), %d SHOT SET BACK, gap %.2f", bank, bankAfterTp,
                                        atTp.isEmpty() ? "clean" : atTp.size() + " flag(s): " + firstChecks(atTp), (long) s[2], s[0],
                                        serverMoved, landed ? "LANDED" : "set back", setBack, g), true);
                        ctx.waitTicks(40);
                        // A kept teleport's BadPacketsN lands on the next teleport: take it here (final-1027).
                        server.command("tp " + player + " ~ ~ ~");
                        ctx.waitTicks(30);
                        server.mark();
                    }
                }
            } finally {
                removeLatency(ctx);
                releaseKeys(ctx);
                chargeOff(ctx);
                tearDown(ctx, gate);
            }
            rows.finish(scenario, name);
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 5. A long mixed session

    /**
     * 1027-blink-c-mixed: testkit.blinkMixedMinutes (default 30) minutes of mixed use with no teleports while charging -
     * charge shots of random size (Toggle and Hold mode, sprinting, walking, jumping), holds of random length, a hold while
     * charging and the shot once backed, the inventory opened while charging and while holding, jumps and turns. Every minute:
     * the waiting answers, the oldest's age, the held queue, memory, client vs server. GrimAC must stay silent all the way.
     */
    private static void mixed(ClientGameTestContext ctx) {
        int x = -11800;
        int z = -6000;
        String name = "1027-blink-c-mixed";
        Scenario.runExpectingFlags(ctx, name, (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            installLatency(ctx, scenario);
            boolean gate = setUp(ctx, 20);
            Rows rows = new Rows();
            String player = playerName(ctx);
            int minutes = Integer.getInteger("testkit.blinkMixedMinutes", 30);
            Random rnd = new Random(Long.getLong("testkit.blinkMixedSeed", 1027L));
            long end = System.currentTimeMillis() + minutes * 60_000L;
            long nextSample = System.currentTimeMillis() + 60_000L;
            int[] count = new int[7];
            List<String> samples = new ArrayList<>();
            long mem0 = memoryMb(ctx);
            long maxOldest = 0;
            long maxWaiting = 0;
            int minute = 0;
            try {
                tp(server, ctx, x + 0.5, SURFACE_Y, z + 0.5, -90f);
                server.command("effect give " + player + " minecraft:saturation 100000 5 true");
                server.mark();
                while (System.currentTimeMillis() < end) {
                    double[] p = here(ctx);
                    if (Math.hypot(p[0] - (x + 0.5), p[2] - (z + 0.5)) > 22) {
                        chargeOff(ctx);
                        tp(server, ctx, x + 0.5, SURFACE_Y, z + 0.5, -90f);
                    }
                    p = here(ctx);
                    float toCenter = (float) Math.toDegrees(Math.atan2(-((x + 0.5) - p[0]), (z + 0.5) - p[2]));
                    face(ctx, toCenter + (rnd.nextFloat() - 0.5f) * 90f);
                    int act = rnd.nextInt(7);
                    count[act]++;
                    String label;
                    switch (act) {
                        case 0 -> { // Toggle charge shot
                            int c = 1 + rnd.nextInt(20);
                            ticks(ctx, String.valueOf(c), null);
                            if (!charging(ctx)) {
                                chargeOn(ctx);
                            }
                            waitBank(ctx, c, c + 30);
                            shoot(ctx, rnd.nextBoolean(), rnd.nextInt(4) == 0);
                            ctx.waitTicks(5 + rnd.nextInt(10));
                            label = "charge shot C" + c;
                        }
                        case 1 -> { // hold (a hold while charging is case 2: its shot must wait to be backed)
                            chargeOff(ctx);
                            int h = 1 + rnd.nextInt(20);
                            ticks(ctx, null, String.valueOf(h));
                            holdRun(ctx, rnd.nextBoolean(), h);
                            ctx.waitTicks(3);
                            label = "hold H" + h + (charging(ctx) ? " (charging)" : "");
                        }
                        case 2 -> { // hold while charging, then the shot once backed
                            int c = 1 + rnd.nextInt(20);
                            int h = 1 + rnd.nextInt(20);
                            ticks(ctx, String.valueOf(c), String.valueOf(h));
                            if (!charging(ctx)) {
                                chargeOn(ctx);
                            }
                            waitBank(ctx, c, c + 30);
                            holdRun(ctx, true, h);
                            ctx.waitTicks(c + 8);
                            shoot(ctx, true, false);
                            ctx.waitTicks(5);
                            label = "charge C" + c + ", hold H" + h + ", shot";
                        }
                        case 3 -> { // inventory while charging or holding
                            if (rnd.nextBoolean()) {
                                if (!charging(ctx)) {
                                    chargeOn(ctx);
                                }
                                openInventory(ctx);
                                ctx.waitTicks(10 + rnd.nextInt(30));
                                closeScreen(ctx);
                                label = "inventory while charging";
                            } else {
                                chargeOff(ctx);
                                holdDown(ctx);
                                ctx.waitTicks(3);
                                openInventory(ctx);
                                ctx.waitTicks(5);
                                closeScreen(ctx);
                                for (int t = 0; t < 25 && holding(ctx); t++) {
                                    ctx.waitTicks(1);
                                }
                                holdUp(ctx);
                                ctx.waitTicks(3);
                                label = "inventory while holding";
                            }
                        }
                        case 4 -> { // Hold-mode charge
                            int c = 1 + rnd.nextInt(20);
                            ticks(ctx, String.valueOf(c), null);
                            chargeOff(ctx);
                            ctx.runOnClient(mc -> Mod.set(CFG, "setChargeMode", Mod.enumValue(MODE, "HOLD")));
                            ctx.runOnClient(mc -> Mod.setField(CHARGE, "keyHeldForTest", Boolean.TRUE));
                            waitBank(ctx, c, c + 30);
                            ctx.getInput().holdKey(o -> o.keyUp);
                            ctx.runOnClient(mc -> Mod.setField(CHARGE, "keyHeldForTest", Boolean.FALSE));
                            ctx.waitTicks(2);
                            releaseKeys(ctx);
                            ctx.runOnClient(mc -> Mod.set(CFG, "setChargeMode", Mod.enumValue(MODE, "TOGGLE")));
                            ctx.waitTicks(5);
                            label = "Hold-mode charge C" + c;
                        }
                        case 5 -> { // walk/jump/turn around, charging or not
                            ctx.getInput().holdKey(o -> o.keyUp);
                            if (rnd.nextBoolean()) {
                                ctx.getInput().holdKey(o -> o.keySprint);
                            }
                            if (rnd.nextBoolean()) {
                                ctx.getInput().holdKey(o -> o.keyJump);
                            }
                            ctx.waitTicks(10 + rnd.nextInt(20));
                            releaseKeys(ctx);
                            float turn = (rnd.nextFloat() - 0.5f) * 200f;
                            ctx.runOnClient(mc -> mc.player.setYRot(mc.player.getYRot() + turn));
                            ctx.waitTicks(5);
                            label = "walk/jump";
                        }
                        default -> { // stand, charging toggled
                            if (rnd.nextBoolean()) {
                                chargeOff(ctx);
                            } else if (!charging(ctx)) {
                                chargeOn(ctx);
                            }
                            ctx.waitTicks(10 + rnd.nextInt(40));
                            label = "stand";
                        }
                    }
                    List<String> flags = scenario.flags();
                    if (!flags.isEmpty()) {
                        rows.add(server, scenario, "after " + label, true, "at minute " + minute);
                    } else {
                        server.mark();
                    }
                    if (System.currentTimeMillis() >= nextSample) {
                        nextSample += 60_000L;
                        minute++;
                        long waiting = cnum(ctx, "answersHeld");
                        long oldest = cnum(ctx, "oldestAnswerAgeMs");
                        maxOldest = Math.max(maxOldest, oldest);
                        maxWaiting = Math.max(maxWaiting, waiting);
                        long mem = memoryMb(ctx);
                        long heldNow = num(ctx, "heldPackets");
                        double g = gap(server, ctx);
                        samples.add(String.format(Locale.ROOT, "min %d: %s, bank %d, %d answer(s) waiting (oldest %d ms), held %d, "
                                        + "peak queue %d, memory %d MB, shots %d, set back %d, gap %.2f", minute,
                                charging(ctx) ? "charging" : "not charging", cnum(ctx, "banked"), waiting, oldest, heldNow,
                                num(ctx, "peakQueue"), mem, cnum(ctx, "shots"), cnum(ctx, "shotsSetBack"), g));
                        scenario.log(samples.get(samples.size() - 1));
                    }
                }
                chargeOff(ctx);
                long mem1 = memoryMb(ctx);
                long longest = cnum(ctx, "maxAnswerWaitMs");
                double g = gap(server, ctx);
                rows.add(server, scenario, "end of " + minutes + " min", num(ctx, "heldPackets") == 0 && maxOldest < 2000
                                && maxWaiting < 60 && mem1 - mem0 < 300 && g < 0.6,
                        String.format(Locale.ROOT, "actions %s, max answers waiting %d, oldest seen %d ms, longest wait %d ms, "
                                + "memory %d -> %d MB, gap %.2f", java.util.Arrays.toString(count), maxWaiting, maxOldest, longest,
                                mem0, mem1, g));
            } finally {
                closeScreen(ctx);
                removeLatency(ctx);
                releaseKeys(ctx);
                ctx.runOnClient(mc -> Mod.set(CFG, "setChargeMode", Mod.enumValue(MODE, "TOGGLE")));
                chargeOff(ctx);
                tearDown(ctx, gate);
            }
            rows.finish(scenario, name);
        });
    }

    private static long memoryMb(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            Runtime rt = Runtime.getRuntime();
            System.gc();
            return (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        });
    }
}
