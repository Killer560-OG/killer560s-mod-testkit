package dev.testkit.gametest;

import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.Latency;
import dev.testkit.harness.PacketTrace;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 1025-blink-* (killer560, 2026-10-25: "fully build blink and add it to the mod"; then "work on getting as many packets as
 * possible for the blink"). Blink holds every packet the client sends and releases them together. Every case proves the
 * hold happened before it says anything about GrimAC: the mod's own counters (ticks and packets held and released) AND
 * the SERVER's view of the player (`data get entity`), which must stand still while the client walks and catch up after.
 *
 * <p>The sweep cases (1025-blink-sweep-*) run with {@link Scenario#runExpectingFlags}: they try longer and longer holds
 * and print, per length, what Grim said, so the longest clean hold is a measured number. Cheat build only; every case
 * fails on a jar without {@code blink.BlinkConfig}.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class BlinkTests implements FabricClientGameTest {

    private static final String CFG = "blink.BlinkConfig";
    private static final String MODE = "blink.BlinkConfig$Mode";
    private static final String LIMIT = "blink.BlinkConfig$LimitAction";
    private static final String FEATURE = "blink.BlinkFeature";
    private static final int SURFACE_Y = 151;
    private static final Pattern POS = Pattern.compile("has the following entity data: \\[(-?[0-9.E-]+)d, (-?[0-9.E-]+)d, (-?[0-9.E-]+)d\\]");

    /** The sweep lengths, from the system property testkit.blinkSweep (comma list) or this default. */
    private static int[] sweepLengths() {
        String p = System.getProperty("testkit.blinkSweep");
        String s = p == null || p.isBlank() ? "20,21,22,23,24,30" : p;
        String[] parts = s.split(",");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Integer.parseInt(parts[i].trim());
        }
        return out;
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        AssertionError first = null;
        for (Consumer<ClientGameTestContext> c : List.<Consumer<ClientGameTestContext>>of(
                BlinkTests::setting, BlinkTests::still, BlinkTests::walk, BlinkTests::sprintJump, BlinkTests::pulse,
                BlinkTests::latency, BlinkTests::pulseMax, BlinkTests::attack, BlinkTests::dig, BlinkTests::place,
                BlinkTests::teleport, BlinkTests::refused, BlinkTests::leap, BlinkTests::knockback, BlinkTests::etherwarp,
                BlinkTests::charge, BlinkTests::chargeActions, BlinkTests::chargeWarp, BlinkTests::chargeRun,
                BlinkTests::chargeSweep, BlinkTests::chargeDouble, BlinkTests::clicks, BlinkTests::chargeFar, BlinkTests::chargeLong,
                BlinkTests::worldChange, BlinkTests::bow,
                BlinkTests::sweepWalk, BlinkTests::sweepStill,
                BlinkTests::sweepLatency)) {
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

    private static void arena(TestServer server, int x, int z) {
        TestMap.on(server)
                .platform(x, SURFACE_Y - 1, z, 40)
                .walls(x, SURFACE_Y, z, 40, 3)
                .catchFloor(120)
                .survival()
                .clearInventory()
                .spawn(x + 0.5, z + 0.5, -90f)
                .build();
    }

    /** Blink ON, Toggle, the given limit and action; Skyblock Only off for the run. Returns what to restore. */
    private static boolean setUp(ClientGameTestContext ctx, int maxTicks, String limitAction) {
        return ctx.computeOnClient(mc -> {
            Mod.staticCall(FEATURE, "end", "test setup");
            boolean gate = (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled");
            Mod.staticCall("util.SkyblockGate", "setEnabled", false);
            Mod.setField(FEATURE, "keyHeldForTest", Boolean.FALSE);
            Mod.set(CFG, "setEnabled", true);
            Mod.set(CFG, "setMode", Mod.enumValue(MODE, "TOGGLE"));
            Mod.set(CFG, "setLimitAction", Mod.enumValue(LIMIT, limitAction));
            Mod.set(CFG, "setMaxTicks", maxTicks);
            Mod.set(CFG, "setChatMessages", false);
            Mod.set(CFG, "setReleaseOnHit", !Boolean.getBoolean("testkit.blinkHoldHits"));
            Mod.set(CFG, "setReleaseOnDig", !Boolean.getBoolean("testkit.blinkHoldDigs"));
            Mod.set(CFG, "setReleaseOnCharge", !Boolean.getBoolean("testkit.blinkHoldUses"));
            return gate;
        });
    }

    private static void tearDown(ClientGameTestContext ctx, boolean gate) {
        ctx.runOnClient(mc -> {
            Mod.staticCall(FEATURE, "end", "test teardown");
            Mod.setField(FEATURE, "keyHeldForTest", null);
            Mod.set(CFG, "setEnabled", false);
            Mod.staticCall("util.SkyblockGate", "setEnabled", gate);
        });
        releaseKeys(ctx);
        ctx.waitTicks(5);
    }

    private static void releaseKeys(ClientGameTestContext ctx) {
        ctx.getInput().releaseKey(o -> o.keyUp);
        ctx.getInput().releaseKey(o -> o.keyDown);
        ctx.getInput().releaseKey(o -> o.keySprint);
        ctx.getInput().releaseKey(o -> o.keyJump);
    }

    /** One press of the Blink key (Toggle: start or stop a hold), through the test hook the tick reads. */
    private static void pressBlink(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> Mod.setField(FEATURE, "keyHeldForTest", Boolean.TRUE));
        ctx.waitTicks(1);
        ctx.runOnClient(mc -> Mod.setField(FEATURE, "keyHeldForTest", Boolean.FALSE));
    }

    private static boolean holding(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> (Boolean) Mod.staticCall(FEATURE, "isHolding"));
    }

    private static long num(ClientGameTestContext ctx, String getter) {
        return ctx.computeOnClient(mc -> ((Number) Mod.staticCall(FEATURE, getter)).longValue());
    }

    private static String playerName(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> mc.player.getName().getString());
    }

    /** The server's own idea of where the player is, from {@code data get entity}. */
    private static double[] serverPos(TestServer server, ClientGameTestContext ctx, String name) {
        int before = countPosLines(server);
        server.command("data get entity " + name + " Pos");
        for (int i = 0; i < 40; i++) {
            ctx.waitTicks(1);
            String tail = server.tail(60);
            if (countPosLines(server) > before) {
                Matcher m = POS.matcher(tail);
                double[] out = null;
                while (m.find()) {
                    out = new double[]{Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2)),
                            Double.parseDouble(m.group(3))};
                }
                if (out != null) {
                    return out;
                }
            }
        }
        throw new AssertionError("the server never answered data get entity " + name + " Pos");
    }

    private static int countPosLines(TestServer server) {
        Matcher m = POS.matcher(server.tail(400));
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    private static double flat(double[] a, double[] b) {
        return Math.hypot(a[0] - b[0], a[2] - b[2]);
    }

    private static void check(boolean ok, String why) {
        if (!ok) {
            throw new AssertionError(why);
        }
    }

    private static String fmt(double[] p) {
        return String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)", p[0], p[1], p[2]);
    }

    // ---------------------------------------------------------------------------------------------------------------

    /** 1025-blink-setting: OFF by default, every setting saved and loaded from disk, and with it OFF the key holds nothing. */
    private static void setting(ClientGameTestContext ctx) {
        Scenario.run(ctx, "1025-blink-setting", (server, scenario) -> arena(server, -3000, -3000), (server, scenario) -> {
            String[] got = ctx.computeOnClient(mc -> {
                Mod.staticCall(CFG, "load");
                Object fresh = Mod.cfg(CFG);
                String defaults = Mod.call(fresh, "isEnabled") + " " + Mod.call(fresh, "getMode") + " "
                        + Mod.call(fresh, "getMaxTicks") + " " + Mod.call(fresh, "getKeyCode") + " "
                        + Mod.call(fresh, "isReleaseOnHit") + " " + Mod.call(fresh, "isReleaseOnDig") + " "
                        + Mod.call(fresh, "isReleaseOnCharge");
                Mod.set(CFG, "setEnabled", true);
                Mod.set(CFG, "setMode", Mod.enumValue(MODE, "HOLD"));
                Mod.set(CFG, "setLimitAction", Mod.enumValue(LIMIT, "PULSE"));
                Mod.set(CFG, "setMaxTicks", 33);
                Mod.set(CFG, "setKeyCode", 66);
                Mod.set(CFG, "setShowHud", false);
                Mod.set(CFG, "setShowServerBox", false);
                Mod.set(CFG, "setChatMessages", true);
                Mod.set(CFG, "setReleaseOnHit", false);
                Mod.set(CFG, "setReleaseOnDig", false);
                Mod.set(CFG, "setReleaseOnCharge", false);
                Mod.call(Mod.cfg(CFG), "save");
                Mod.set(CFG, "setEnabled", false);
                Mod.set(CFG, "setMaxTicks", 5);
                Mod.staticCall(CFG, "load");
                Object l = Mod.cfg(CFG);
                String loaded = Mod.call(l, "isEnabled") + " " + Mod.call(l, "getMode") + " " + Mod.call(l, "getLimitAction")
                        + " " + Mod.call(l, "getMaxTicks") + " " + Mod.call(l, "getKeyCode") + " " + Mod.call(l, "isShowHud")
                        + " " + Mod.call(l, "isShowServerBox") + " " + Mod.call(l, "isChatMessages") + " "
                        + Mod.call(l, "isReleaseOnHit") + " " + Mod.call(l, "isReleaseOnDig") + " "
                        + Mod.call(l, "isReleaseOnCharge");
                // Back to the defaults on disk for the cases behind this one.
                Mod.set(CFG, "setEnabled", false);
                Mod.set(CFG, "setMode", Mod.enumValue(MODE, "TOGGLE"));
                Mod.set(CFG, "setLimitAction", Mod.enumValue(LIMIT, "RELEASE"));
                Mod.set(CFG, "setMaxTicks", 22);
                Mod.set(CFG, "setKeyCode", -1);
                Mod.set(CFG, "setShowHud", true);
                Mod.set(CFG, "setShowServerBox", true);
                Mod.set(CFG, "setChatMessages", false);
                Mod.set(CFG, "setReleaseOnHit", true);
                Mod.set(CFG, "setReleaseOnDig", true);
                Mod.set(CFG, "setReleaseOnCharge", true);
                Mod.call(Mod.cfg(CFG), "save");
                return new String[]{defaults, loaded};
            });
            // OFF: the key does nothing.
            ctx.runOnClient(mc -> Mod.setField(FEATURE, "keyHeldForTest", Boolean.TRUE));
            ctx.waitTicks(4);
            boolean heldWhileOff = holding(ctx);
            ctx.runOnClient(mc -> Mod.setField(FEATURE, "keyHeldForTest", null));
            scenario.log("defaults " + got[0] + "; loaded " + got[1] + "; held while off " + heldWhileOff);
            check(got[0].equals("false TOGGLE 22 -1 true true true"), "defaults wrong: " + got[0]);
            check(got[1].equals("true HOLD PULSE 33 66 false false true false false false"),
                    "settings did not come back from disk: " + got[1]);
            check(!heldWhileOff, "Blink held packets while switched off");
        });
    }

    /** 1025-blink-still: standing still, one 17-tick hold by the key; the limit releases it; Grim silent. */
    private static void still(ClientGameTestContext ctx) {
        Scenario.run(ctx, "1025-blink-still", (server, scenario) -> arena(server, -3100, -3000), (server, scenario) -> {
            boolean gate = setUp(ctx, 17, "RELEASE");
            try {
                long holdsBefore = num(ctx, "releasedHolds");
                pressBlink(ctx);
                ctx.waitTicks(8);
                boolean mid = holding(ctx);
                long midPackets = num(ctx, "heldPackets");
                ctx.waitTicks(20);
                boolean after = holding(ctx);
                long holds = num(ctx, "releasedHolds") - holdsBefore;
                long ticks = num(ctx, "lastHoldTicks");
                long packets = num(ctx, "lastHoldPackets");
                scenario.log("mid-hold " + mid + " (" + midPackets + " held), after " + after + "; " + holds
                        + " release(s), last " + ticks + " tick(s) / " + packets + " packet(s)");
                check(mid && midPackets >= 8, "no hold was running 8 ticks after the press");
                check(!after && holds == 1, "the tick limit did not release the hold exactly once");
                check(ticks == 17, "the hold was not 17 ticks: " + ticks);
                check(packets >= 17, "fewer packets than ticks were released: " + packets);
            } finally {
                tearDown(ctx, gate);
            }
            ctx.waitTicks(40);
        });
    }

    /**
     * One walking hold of {@code ticks}, returning {server moved during hold, client moved during hold, gap after
     * release, setbacks}. Walks +x from where he stands.
     */
    private static double[] walkHold(TestServer server, Scenario scenario, ClientGameTestContext ctx, int ticks,
                                     boolean jump) {
        String name = playerName(ctx);
        ctx.getInput().holdKey(o -> o.keyUp);
        ctx.getInput().holdKey(o -> o.keySprint);
        if (jump) {
            ctx.getInput().holdKey(o -> o.keyJump);
        }
        ctx.waitTicks(10);
        ctx.runOnClient(mc -> PacketTrace.start());
        pressBlink(ctx);
        double[] clientStart = scenario.playerPosition();
        // Read after the press: from here the server must hear nothing until the release.
        double[] serverStart = serverPos(server, ctx, name);
        // Read the server mid-hold (it must still be where the hold began).
        int midWait = Math.max(2, ticks / 2 - 4);
        ctx.waitTicks(midWait);
        double[] serverMid = holding(ctx) ? serverPos(server, ctx, name) : null;
        double[] clientMid = scenario.playerPosition();
        // Out to the limit and a little past it.
        for (int i = 0; i < ticks + 10 && holding(ctx); i++) {
            ctx.waitTicks(1);
        }
        boolean stillHolding = holding(ctx);
        // How far the client went over the whole hold (the hold has just been released at its limit).
        double[] clientAtRelease = scenario.playerPosition();
        releaseKeys(ctx);
        ctx.waitTicks(20);
        double[] serverEnd = serverPos(server, ctx, name);
        double[] clientEnd = scenario.playerPosition();
        ctx.runOnClient(mc -> PacketTrace.stop());
        int setbacks = PacketTrace.received("player_position");
        double serverMoved = serverMid == null ? -1 : flat(serverStart, serverMid);
        double clientMoved = flat(clientStart, clientAtRelease);
        double gap = flat(serverEnd, clientEnd);
        scenario.log(String.format(Locale.ROOT, "hold %d: server %s -> mid %s (moved %.2f) while the client moved %.2f over the hold; "
                        + "after release server %s client %s (gap %.2f); last hold %d tick(s) / %d packet(s); "
                        + "%d setback(s)%s", ticks, fmt(serverStart), serverMid == null ? "-" : fmt(serverMid), serverMoved,
                clientMoved, fmt(serverEnd), fmt(clientEnd), gap, num(ctx, "lastHoldTicks"), num(ctx, "lastHoldPackets"),
                setbacks, stillHolding ? " STILL HOLDING" : ""));
        return new double[]{serverMoved, clientMoved, gap, setbacks, stillHolding ? 1 : 0, num(ctx, "lastHoldTicks")};
    }

    /** 1025-blink-walk: sprinting through a 17-tick hold - the server stands still, then catches up; Grim silent. */
    private static void walk(ClientGameTestContext ctx) {
        Scenario.run(ctx, "1025-blink-walk", (server, scenario) -> arena(server, -3200, -3000), (server, scenario) -> {
            boolean gate = setUp(ctx, 17, "RELEASE");
            try {
                double[] r = walkHold(server, scenario, ctx, 17, false);
                check(r[1] >= 3.0, "the client did not walk during the hold (" + r[1] + " blocks): nothing was tested");
                check(r[0] >= 0 && r[0] < 0.2, "the server saw him move during the hold (" + r[0] + "): nothing was held");
                check(r[2] < 0.5, "the server did not catch up after the release (gap " + r[2] + ")");
                check(r[3] == 0, "the server set him back " + (int) r[3] + " time(s)");
                check(r[5] == 17, "the hold was not 17 ticks: " + (int) r[5]);
            } finally {
                tearDown(ctx, gate);
            }
            ctx.waitTicks(40);
        });
    }

    /** 1025-blink-jump: sprint-jumping through a 17-tick hold. */
    private static void sprintJump(ClientGameTestContext ctx) {
        Scenario.run(ctx, "1025-blink-jump", (server, scenario) -> arena(server, -3300, -3000), (server, scenario) -> {
            boolean gate = setUp(ctx, 17, "RELEASE");
            try {
                double[] r = walkHold(server, scenario, ctx, 17, true);
                check(r[1] >= 3.0, "the client did not move during the hold (" + r[1] + " blocks): nothing was tested");
                check(r[0] >= 0 && r[0] < 0.2, "the server saw him move during the hold (" + r[0] + ")");
                check(r[2] < 0.5, "the server did not catch up after the release (gap " + r[2] + ")");
                check(r[3] == 0, "the server set him back " + (int) r[3] + " time(s)");
            } finally {
                tearDown(ctx, gate);
            }
            ctx.waitTicks(40);
        });
    }

    /** 1025-blink-pulse: Release and keep going at 10 ticks while sprinting 160 ticks - a burst every half second. */
    private static void pulse(ClientGameTestContext ctx) {
        Scenario.run(ctx, "1025-blink-pulse", (server, scenario) -> arena(server, -3400, -3000), (server, scenario) -> {
            boolean gate = setUp(ctx, 10, "PULSE");
            try {
                long before = num(ctx, "releasedHolds");
                double[] start = scenario.playerPosition();
                ctx.getInput().holdKey(o -> o.keyUp);
                ctx.getInput().holdKey(o -> o.keySprint);
                ctx.runOnClient(mc -> PacketTrace.start());
                pressBlink(ctx);
                ctx.waitTicks(160);
                pressBlink(ctx);
                releaseKeys(ctx);
                ctx.waitTicks(20);
                ctx.runOnClient(mc -> PacketTrace.stop());
                long holds = num(ctx, "releasedHolds") - before;
                double moved = flat(start, scenario.playerPosition());
                int setbacks = PacketTrace.received("player_position");
                scenario.log(String.format(Locale.ROOT, "pulse: %d release(s) over 160 ticks, moved %.1f blocks, %d setback(s)",
                        holds, moved, setbacks));
                check(moved > 20, "he did not sprint (" + moved + " blocks)");
                check(holds >= 14, "Pulse did not keep releasing and holding again: " + holds + " release(s)");
                check(setbacks == 0, "the server set him back " + setbacks + " time(s)");
            } finally {
                tearDown(ctx, gate);
            }
            ctx.waitTicks(40);
        });
    }

    /** 1025-blink-latency: the 17-tick walking hold with a 100 ms round trip. */
    private static void latency(ClientGameTestContext ctx) {
        Scenario.run(ctx, "1025-blink-latency", (server, scenario) -> arena(server, -3500, -3000), (server, scenario) -> {
            boolean gate = setUp(ctx, 17, "RELEASE");
            try {
                ctx.runOnClient(mc -> Latency.install(100));
                ctx.waitTicks(40);
                double[] r = walkHold(server, scenario, ctx, 17, false);
                check(r[1] >= 3.0, "the client did not walk during the hold (" + r[1] + " blocks): nothing was tested");
                // 100 ms each way: the moves sent just before the press are still arriving when the server is first read.
                check(r[0] >= 0 && r[0] < 0.8, "the server saw him move during the hold (" + r[0] + ")");
                check(r[3] == 0, "the server set him back " + (int) r[3] + " time(s)");
            } finally {
                ctx.runOnClient(mc -> Latency.remove());
                tearDown(ctx, gate);
            }
            ctx.waitTicks(40);
        });
    }

    /** 1025-blink-sweep-walk: longer and longer sprinting holds; per length, what Grim said. The longest clean is printed. */
    private static void sweepWalk(ClientGameTestContext ctx) {
        sweep(ctx, "1025-blink-sweep-walk", -3600, true, 0);
    }

    /** 1025-blink-sweep-still: the same lengths standing still. */
    private static void sweepStill(ClientGameTestContext ctx) {
        sweep(ctx, "1025-blink-sweep-still", -3700, false, 0);
    }

    /** 1025-blink-sweep-latency: the walking lengths with a round trip (testkit.blinkLatency ms, default 100). */
    private static void sweepLatency(ClientGameTestContext ctx) {
        sweep(ctx, "1025-blink-sweep-latency", -3800, true, Integer.getInteger("testkit.blinkLatency", 100));
    }

    /**
     * 1025-blink-pulse-max: Pulse at the longest hold the sweeps found clean (testkit.blinkPulse, default the mod's own
     * default Max Ticks) while sprinting for about 400 ticks - a burst every hold, back to back. Grim must stay silent.
     */
    private static void pulseMax(ClientGameTestContext ctx) {
        Scenario.run(ctx, "1025-blink-pulse-max", (server, scenario) -> arena(server, -3900, -3000), (server, scenario) -> {
            int len = Integer.getInteger("testkit.blinkPulse",
                    ctx.computeOnClient(mc -> ((Number) Mod.field(CFG, "DEFAULT_TICKS")).intValue()));
            boolean gate = setUp(ctx, len, "PULSE");
            try {
                long before = num(ctx, "releasedHolds");
                String name = playerName(ctx);
                double[] start = scenario.playerPosition();
                ctx.getInput().holdKey(o -> o.keyUp);
                ctx.getInput().holdKey(o -> o.keySprint);
                ctx.runOnClient(mc -> PacketTrace.start());
                pressBlink(ctx);
                double maxGap = 0;
                // Sprint forwards, then walk backwards, twice over, so the run stays on the platform.
                for (int i = 0; i < 4; i++) {
                    if (i % 2 == 1) {
                        ctx.getInput().releaseKey(o -> o.keyUp);
                        ctx.getInput().releaseKey(o -> o.keySprint);
                        ctx.getInput().holdKey(o -> o.keyDown);
                    } else if (i > 0) {
                        ctx.getInput().releaseKey(o -> o.keyDown);
                        ctx.getInput().holdKey(o -> o.keyUp);
                        ctx.getInput().holdKey(o -> o.keySprint);
                    }
                    ctx.waitTicks(100 - len / 2);
                    double[] sp = serverPos(server, ctx, name);
                    maxGap = Math.max(maxGap, flat(sp, scenario.playerPosition()));
                }
                pressBlink(ctx);
                releaseKeys(ctx);
                ctx.waitTicks(20);
                ctx.runOnClient(mc -> PacketTrace.stop());
                long holds = num(ctx, "releasedHolds") - before;
                double moved = flat(start, scenario.playerPosition());
                int setbacks = PacketTrace.received("player_position");
                scenario.log(String.format(Locale.ROOT, "pulse-max at %d: %d release(s) over ~400 ticks, largest server/client gap "
                        + "seen %.2f blocks, ended %.1f from the start, %d setback(s)", len, holds, maxGap, moved, setbacks));
                check(holds >= 400 / (len + 1) - 1, "Pulse did not keep holding and releasing: " + holds + " release(s)");
                check(maxGap > 1.0, "the server never lagged behind the client: nothing was held");
                check(setbacks == 0, "the server set him back " + setbacks + " time(s)");
            } finally {
                tearDown(ctx, gate);
            }
            ctx.waitTicks(40);
        });
    }

    /** Pulse at the default (Grim's max) Max Ticks, for a case that acts while blinking. */
    private static int defaultTicks(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> ((Number) Mod.field(CFG, "DEFAULT_TICKS")).intValue());
    }

    /** 1025-blink-attack: hitting a frozen dummy for 200 ticks while Pulse holds every swing in bursts; and -control. */
    private static void attack(ClientGameTestContext ctx) {
        int repeat = Integer.getInteger("testkit.blinkAttackRepeat", 1);
        AssertionError first = null;
        for (int i = 0; i < repeat; i++) {
            String sfx = repeat > 1 ? "-" + (i + 1) : "";
            for (int k = 0; k < 2; k++) {
                try {
                    if (k == 0) {
                        attackCase(ctx, "1025-blink-attack-control" + sfx, false, -4300 - 40 * i);
                    } else {
                        attackCase(ctx, "1025-blink-attack" + sfx, true, -4000 - 40 * i);
                    }
                } catch (AssertionError e) {
                    first = first == null ? e : first;
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }

    private static void attackCase(ClientGameTestContext ctx, String caseName, boolean blink, int ax) {
        Scenario.run(ctx, caseName, (server, scenario) -> {
            arena(server, ax, -3000);
            server.command("give @p minecraft:diamond_sword");
        }, (server, scenario) -> {
            boolean gate = setUp(ctx, defaultTicks(ctx), "PULSE");
            TestEnemy dummy = null;
            try {
                dummy = TestEnemy.named("Blink", "Dummy")
                        .at(ax + 3.0, SURFACE_Y, -3000 + 0.5)
                        .health(20)
                        .frozen(true)
                        .spawn(ctx, server);
                check(dummy.visibleToClient(), "the dummy never appeared to the client");
                long before = num(ctx, "releasedHolds");
                if (blink) {
                    pressBlink(ctx);
                }
                int swings = scenario.attackFor(200);
                if (blink) {
                    pressBlink(ctx);
                }
                ctx.waitTicks(20);
                long holds = num(ctx, "releasedHolds") - before;
                scenario.log("attack (" + (blink ? "Blink Pulse" : "no Blink") + "): " + swings + " swing(s), dummy died "
                        + dummy.deaths() + " time(s), " + holds + " release(s)");
                check(!blink || holds >= 5, "Pulse did not hold and release while attacking: " + holds);
                check(blink || holds == 0, "Blink held packets in the control: " + holds);
                check(swings >= 10 && dummy.deaths() > 0, "the swings never landed (" + swings + " swings, "
                        + dummy.deaths() + " deaths): nothing about combat was tested");
            } finally {
                if (dummy != null) {
                    dummy.remove();
                }
                tearDown(ctx, gate);
            }
            ctx.waitTicks(40);
        });
    }

    private static int stoneLeft(ClientGameTestContext ctx, int x, int z) {
        return ctx.computeOnClient(mc -> {
            int n = 0;
            for (int dx = 2; dx <= 4; dx++) {
                for (int y = SURFACE_Y; y <= SURFACE_Y + 2; y++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        if (!mc.level.getBlockState(new net.minecraft.core.BlockPos(x + dx, y, z + dz)).isAir()) {
                            n++;
                        }
                    }
                }
            }
            return n;
        });
    }

    /** 1025-blink-dig: mining a stone wall with a pickaxe for 160 ticks while Pulse holds every dig in bursts. */
    private static void dig(ClientGameTestContext ctx) {
        int repeat = Integer.getInteger("testkit.blinkDigRepeat", 1);
        AssertionError first = null;
        for (int i = 0; i < repeat; i++) {
            try {
                digCase(ctx, "1025-blink-dig" + (repeat > 1 ? "-" + (i + 1) : ""), -4100 - 30 * i);
            } catch (AssertionError e) {
                first = first == null ? e : first;
            }
        }
        if (first != null) {
            throw first;
        }
    }

    private static void digCase(ClientGameTestContext ctx, String caseName, int x) {
        int z = -3000;
        Scenario.run(ctx, caseName, (server, scenario) -> {
            TestMap.on(server)
                    .platform(x, SURFACE_Y - 1, z, 12)
                    .catchFloor(120)
                    .survival()
                    .clearInventory()
                    .give("diamond_pickaxe", 1)
                    .fill(x + 2, SURFACE_Y, z - 2, x + 4, SURFACE_Y + 2, z + 2, "stone")
                    .spawn(x + 0.5, z + 0.5, -90f)
                    .build();
        }, (server, scenario) -> {
            boolean gate = setUp(ctx, defaultTicks(ctx), "PULSE");
            try {
                int before = stoneLeft(ctx, x, z);
                long holds0 = num(ctx, "releasedHolds");
                pressBlink(ctx);
                ctx.getInput().holdKey(o -> o.keyAttack);
                ctx.waitTicks(160);
                ctx.getInput().releaseKey(o -> o.keyAttack);
                pressBlink(ctx);
                ctx.waitTicks(40);
                int after = stoneLeft(ctx, x, z);
                long holds = num(ctx, "releasedHolds") - holds0;
                scenario.log("dig: wall " + before + " -> " + after + " block(s) (as the client sees it 40 ticks after the "
                        + "last release), " + holds + " release(s)");
                check(before == 45, "the wall was not built: " + before);
                check(after <= before - 2, "fewer than two blocks were mined (" + (before - after) + "): digging was hardly tested");
                check(holds >= 5, "Pulse did not hold and release while digging: " + holds);
            } finally {
                ctx.getInput().releaseKey(o -> o.keyAttack);
                tearDown(ctx, gate);
            }
            ctx.waitTicks(40);
        });
    }

    /** 1025-blink-place: placing cobblestone on the floor ahead for 120 ticks while Pulse holds every placement in bursts. */
    private static void place(ClientGameTestContext ctx) {
        int x = -4200;
        int z = -3000;
        Scenario.run(ctx, "1025-blink-place", (server, scenario) -> {
            TestMap.on(server)
                    .platform(x, SURFACE_Y - 1, z, 12)
                    .catchFloor(120)
                    .survival()
                    .clearInventory()
                    .give("cobblestone", 64)
                    .spawn(x + 0.5, z + 0.5, -90f, 90f)
                    .build();
        }, (server, scenario) -> {
            boolean gate = setUp(ctx, defaultTicks(ctx), "PULSE");
            try {
                int woolBefore = ctx.computeOnClient(mc -> mc.player.getInventory().countItem(net.minecraft.world.item.Items.COBBLESTONE));
                long holds0 = num(ctx, "releasedHolds");
                double y0 = scenario.playerPosition()[1];
                pressBlink(ctx);
                ctx.getInput().holdKey(o -> o.keyUse);
                ctx.getInput().holdKey(o -> o.keyJump);
                ctx.waitTicks(120);
                ctx.getInput().releaseKey(o -> o.keyJump);
                ctx.getInput().releaseKey(o -> o.keyUse);
                pressBlink(ctx);
                ctx.waitTicks(40);
                int woolAfter = ctx.computeOnClient(mc -> mc.player.getInventory().countItem(net.minecraft.world.item.Items.COBBLESTONE));
                long holds = num(ctx, "releasedHolds") - holds0;
                double rose = scenario.playerPosition()[1] - y0;
                scenario.log(String.format(Locale.ROOT, "place: cobblestone %d -> %d (40 ticks after the last release), towered "
                        + "%.1f blocks, %d release(s)", woolBefore, woolAfter, rose, holds));
                check(woolBefore > 0 && woolAfter <= woolBefore - 3, "fewer than three blocks were placed: placing was hardly tested");
                check(rose >= 2.5, "he did not tower up (" + rose + " blocks): the placements were not under him");
                check(holds >= 3, "Pulse did not hold and release while placing: " + holds);
            } finally {
                ctx.getInput().releaseKey(o -> o.keyUse);
                ctx.getInput().releaseKey(o -> o.keyJump);
                tearDown(ctx, gate);
            }
            ctx.waitTicks(40);
        });
    }

    /**
     * 1025-blink-teleport (killer560, 2026-10-25: "make the blink survive taking knock back and etherwarping and leaping"):
     * a server teleport in the middle of a sprinting Toggle hold. Blink keeps holding (the accept goes into the queue in
     * order), the client is where the server put him, and once the hold ends client and server agree. Under 20 ticks in
     * all, so the hold stays inside GrimAC's 22 and the server does not resend the teleport.
     */
    private static void teleport(ClientGameTestContext ctx) {
        int x = -4400;
        int z = -3000;
        Scenario.run(ctx, "1025-blink-teleport", (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = setUp(ctx, 22, "RELEASE");
            try {
                String name = playerName(ctx);
                ctx.getInput().holdKey(o -> o.keyUp);
                ctx.getInput().holdKey(o -> o.keySprint);
                ctx.waitTicks(5);
                long holds0 = num(ctx, "releasedHolds");
                long tp0 = num(ctx, "teleportsHeld");
                pressBlink(ctx);
                ctx.waitTicks(6);
                boolean heldBefore = holding(ctx);
                releaseKeys(ctx);
                server.command("tp " + name + " " + (x + 0.5) + " " + SURFACE_Y + " " + (z + 20.5));
                boolean kept = false;
                for (int i = 0; i < 12 && !kept; i++) {
                    ctx.waitTicks(1);
                    kept = num(ctx, "teleportsHeld") > tp0;
                }
                boolean stillHolding = holding(ctx);
                boolean releasedByTp = num(ctx, "releasedHolds") > holds0;
                double[] clientHeld = scenario.playerPosition();
                pressBlink(ctx);
                ctx.waitTicks(10);
                double[] client = scenario.playerPosition();
                double[] srv = serverPos(server, ctx, name);
                scenario.log(String.format(Locale.ROOT, "teleport: holding before %s, held through %s, still holding %s, released "
                        + "by it %s; client while held %s, after %s, server %s", heldBefore, kept, stillHolding, releasedByTp,
                        fmt(clientHeld), fmt(client), fmt(srv)));
                check(heldBefore, "no hold was running when the teleport came");
                check(kept && stillHolding && !releasedByTp, "Blink did not keep holding through the teleport");
                check(Math.abs(clientHeld[2] - (z + 20.5)) < 1.0 && Math.abs(clientHeld[0] - (x + 0.5)) < 1.0,
                        "the client is not where the server put him: " + fmt(clientHeld));
                check(flat(client, srv) < 0.5, "client and server disagree after the hold: " + fmt(client) + " vs " + fmt(srv));
            } finally {
                tearDown(ctx, gate);
            }
            ctx.waitTicks(40);
        });
    }

    /**
     * 1025-blink-refused: the server puts him back where a sprinting hold started (what a refused blink looks like). Blink
     * drops the blink - the held moves never go - and he carries on from the start with client and server agreeing.
     */
    private static void refused(ClientGameTestContext ctx) {
        int x = -4400;
        int z = -3200;
        Scenario.run(ctx, "1025-blink-refused", (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = setUp(ctx, 22, "RELEASE");
            try {
                String name = playerName(ctx);
                ctx.waitTicks(10);
                double[] start = serverPos(server, ctx, name);
                long ref0 = num(ctx, "refused");
                long holds0 = num(ctx, "releasedHolds");
                pressBlink(ctx);
                ctx.getInput().holdKey(o -> o.keyUp);
                ctx.getInput().holdKey(o -> o.keySprint);
                ctx.waitTicks(10);
                boolean heldBefore = holding(ctx);
                double[] walked = scenario.playerPosition();
                releaseKeys(ctx);
                server.command(String.format(Locale.ROOT, "tp %s %.4f %.4f %.4f", name, start[0], start[1], start[2]));
                boolean dropped = false;
                for (int i = 0; i < 12 && !dropped; i++) {
                    ctx.waitTicks(1);
                    dropped = num(ctx, "refused") > ref0;
                }
                pressBlink(ctx);
                ctx.waitTicks(10);
                boolean released = num(ctx, "releasedHolds") > holds0;
                double[] client = scenario.playerPosition();
                double[] srv = serverPos(server, ctx, name);
                scenario.log(String.format(Locale.ROOT, "refused: started %s, walked to %s while held, dropped %s, released %s; "
                        + "client %s, server %s", fmt(start), fmt(walked), dropped, released, fmt(client), fmt(srv)));
                check(heldBefore && flat(start, walked) > 2, "no sprinting hold was running when the server set him back");
                check(dropped && released, "Blink did not drop the refused blink");
                check(flat(client, start) < 0.5, "he is not back at the start: " + fmt(client));
                check(flat(client, srv) < 0.5, "client and server disagree after the drop: " + fmt(client) + " vs " + fmt(srv));
            } finally {
                tearDown(ctx, gate);
            }
            ctx.waitTicks(40);
        });
    }

    /**
     * 1025-blink-leap: Pulse at 22 while sprinting, the server moves him three times (a leap is a server teleport). Each
     * one is held through, the pulses carry on, no setback beyond the three teleports, client and server agree at the end.
     */
    private static void leap(ClientGameTestContext ctx) {
        int x = -4400;
        int z = -3400;
        Scenario.run(ctx, "1025-blink-leap", (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = setUp(ctx, 22, "PULSE");
            try {
                String name = playerName(ctx);
                long tp0 = num(ctx, "teleportsHeld");
                long holds0 = num(ctx, "releasedHolds");
                long ref0 = num(ctx, "refused");
                ctx.runOnClient(mc -> PacketTrace.start());
                pressBlink(ctx);
                int[][] spots = {{-15, -15}, {15, -15}, {0, 15}};
                for (int[] spot : spots) {
                    ctx.getInput().holdKey(o -> o.keyUp);
                    ctx.getInput().holdKey(o -> o.keySprint);
                    ctx.waitTicks(17);
                    server.command("tp " + name + " " + (x + spot[0] + 0.5) + " " + SURFACE_Y + " " + (z + spot[1] + 0.5));
                    ctx.waitTicks(13);
                }
                releaseKeys(ctx);
                ctx.waitTicks(10);
                pressBlink(ctx);
                ctx.waitTicks(20);
                ctx.runOnClient(mc -> PacketTrace.stop());
                long held = num(ctx, "teleportsHeld") - tp0;
                long holds = num(ctx, "releasedHolds") - holds0;
                long refusedN = num(ctx, "refused") - ref0;
                int positions = PacketTrace.received("player_position");
                double[] client = scenario.playerPosition();
                double[] srv = serverPos(server, ctx, name);
                scenario.log(String.format(Locale.ROOT, "leap: %d teleport(s) held through, %d release(s), %d refused, %d position "
                        + "packet(s); client %s, server %s", held, holds, refusedN, positions, fmt(client), fmt(srv)));
                check(held >= 2, "Blink held through fewer than two of the three teleports: " + held);
                check(holds >= 4, "Pulse did not keep going: " + holds + " release(s)");
                check(refusedN == 0, "a teleport was taken for a refused blink");
                check(positions == 3, "the server sent " + positions + " position packet(s), not the three teleports");
                check(flat(client, srv) < 0.5, "client and server disagree at the end: " + fmt(client) + " vs " + fmt(srv));
            } finally {
                tearDown(ctx, gate);
            }
            ctx.waitTicks(40);
        });
    }

    /**
     * 1025-blink-knockback: Pulse at 22 standing in front of a bot that hits him once a second. The knockback must reach
     * him (he is moved), Pulse keeps holding and releasing, and nothing sets him back.
     */
    private static void knockback(ClientGameTestContext ctx) {
        int x = -4400;
        int z = -3600;
        Scenario.run(ctx, "1025-blink-knockback", (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = setUp(ctx, 22, "PULSE");
            TestEnemy attacker = null;
            try {
                double[] start = scenario.playerPosition();
                long holds0 = num(ctx, "releasedHolds");
                ctx.runOnClient(mc -> PacketTrace.start());
                pressBlink(ctx);
                attacker = TestEnemy.named("Knockback", "Blink")
                        .at(x + 3.5, SURFACE_Y, z + 0.5)
                        .health(20)
                        .heldItem("minecraft:stick")
                        .attacking(4.0f, 20, 1.0f)
                        .chasing(true)
                        .spawn(ctx, server);
                attacker.drive(200);
                pressBlink(ctx);
                ctx.waitTicks(10);
                ctx.runOnClient(mc -> PacketTrace.stop());
                long holds = num(ctx, "releasedHolds") - holds0;
                int setbacks = PacketTrace.received("player_position");
                double[] end = scenario.playerPosition();
                double moved = flat(start, end);
                String name = playerName(ctx);
                double[] srv = serverPos(server, ctx, name);
                scenario.log(String.format(Locale.ROOT, "knockback: %d hit(s), moved %.2f blocks, %d release(s), %d setback(s); "
                        + "client %s, server %s", attacker.hits(), moved, holds, setbacks, fmt(end), fmt(srv)));
                check(attacker.hits() >= 5, "only " + attacker.hits() + " hit(s): knockback was hardly tested");
                check(moved >= 0.5, "he was not knocked anywhere (" + moved + " blocks): no knockback was delivered");
                check(holds >= 6, "Pulse did not keep going under the hits: " + holds + " release(s)");
                check(setbacks == 0, "the server set him back " + setbacks + " time(s)");
                check(flat(end, srv) < 0.5, "client and server disagree at the end: " + fmt(end) + " vs " + fmt(srv));
            } finally {
                if (attacker != null) {
                    attacker.remove();
                }
                tearDown(ctx, gate);
            }
            ctx.waitTicks(40);
        });
    }

    private static final String AOTV = "minecraft:diamond_shovel[custom_data={id:\"ASPECT_OF_THE_VOID\",ethermerge:1,"
            + "tuned_transmission:4},unbreakable={}]";

    /**
     * 1025-blink-etherwarp: Pulse at 22, four real etherwarps (hx/dungeon/HxAbilities) 15 ticks apart. Each warp's use is
     * held and released with its pulse, and the landing arrives while the next hold is running: Blink must keep holding,
     * the server must warp all four times, and client and server agree at the end.
     */
    private static void etherwarp(ClientGameTestContext ctx) {
        int x = -4400;
        int z = -3800;
        Scenario.run(ctx, "1025-blink-etherwarp", (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = setUp(ctx, 22, "PULSE");
            dev.testkit.gametest.hx.Hx hx = null;
            try {
                hx = dev.testkit.gametest.hx.Hx.connect();
                hx.call("dungeon.abilities", "enabled", true);
                hx.give(0, AOTV);
                ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
                ctx.waitTicks(10);
                com.google.gson.JsonObject before = hx.call("dungeon.stats").getAsJsonObject();
                long tp0 = num(ctx, "teleportsHeld");
                long ref0 = num(ctx, "refused");
                long holds0 = num(ctx, "releasedHolds");
                ctx.getInput().holdKey(o -> o.keyShift);
                ctx.waitTicks(5);
                pressBlink(ctx);
                for (int i = 1; i <= 4; i++) {
                    int bx = x + 6 * i;
                    ctx.runOnClient(mc -> {
                        net.minecraft.world.phys.Vec3 f = mc.player.position();
                        double dx = bx + 0.5 - f.x;
                        double dy = SURFACE_Y - (f.y + 1.27);
                        double dz = z + 0.5 - f.z;
                        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
                        mc.player.setYRot(mc.player.getYRot() + net.minecraft.util.Mth.wrapDegrees(yaw - mc.player.getYRot()));
                        mc.player.setXRot(net.minecraft.util.Mth.clamp(pitch, -90f, 90f));
                    });
                    ctx.getInput().pressKey(o -> o.keyUse);
                    ctx.waitTicks(30);
                }
                pressBlink(ctx);
                ctx.waitTicks(20);
                ctx.getInput().releaseKey(o -> o.keyShift);
                com.google.gson.JsonObject after = hx.call("dungeon.stats").getAsJsonObject();
                int warps = after.get("etherwarps").getAsInt() - before.get("etherwarps").getAsInt();
                long held = num(ctx, "teleportsHeld") - tp0;
                long refusedN = num(ctx, "refused") - ref0;
                long holds = num(ctx, "releasedHolds") - holds0;
                double[] client = scenario.playerPosition();
                String name = playerName(ctx);
                double[] srv = serverPos(server, ctx, name);
                scenario.log(String.format(Locale.ROOT, "etherwarp: %d server warp(s), %d landing(s) held through, %d refused, %d "
                        + "release(s); client %s, server %s", warps, held, refusedN, holds, fmt(client), fmt(srv)));
                check(warps == 4, "the server did not etherwarp four times: " + warps);
                check(held >= 3, "Blink held through fewer than three of the landings: " + held);
                check(refusedN == 0, "a landing was taken for a refused blink");
                check(Math.abs(client[0] - (x + 24.5)) < 1.0 && Math.abs(client[2] - (z + 0.5)) < 1.0,
                        "he is not on the last warp's block: " + fmt(client));
                check(flat(client, srv) < 0.5, "client and server disagree at the end: " + fmt(client) + " vs " + fmt(srv));
            } finally {
                ctx.getInput().releaseKey(o -> o.keyShift);
                if (hx != null) {
                    hx.call("dungeon.abilities", "enabled", false);
                    hx.close();
                }
                tearDown(ctx, gate);
            }
            ctx.waitTicks(40);
        });
    }

    // ---- Charge (killer560, 2026-10-25: "stand still and do a terminal then once it is done I start walking and release
    // it so I shoot 22 packets forward"; "flick levers mine blocks"; "infinite"; "hold through leaps and etherwarps") ----

    private static final String STYLE = "blink.BlinkConfig$Style";
    private static final String CHARGE = "blink.BlinkCharge";

    private static void chargeOn(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            Mod.set(CFG, "setStyle", Mod.enumValue(STYLE, "CHARGE"));
            Mod.set(CFG, "setShootOnMove", true);
            Mod.set(CFG, "setHoldAfterShot", false);
        });
        pressBlink(ctx);
    }

    private static void chargeOff(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            Mod.setField(FEATURE, "keyHeldForTest", Boolean.FALSE);
            Mod.set(CFG, "setStyle", Mod.enumValue(STYLE, "HOLD_PACKETS"));
        });
        ctx.waitTicks(2);
    }

    private static long cnum(ClientGameTestContext ctx, String getter) {
        return ctx.computeOnClient(mc -> ((Number) Mod.staticCall(CHARGE, getter)).longValue());
    }

    private static double lastShotBlocks(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> ((Number) Mod.staticCall(CHARGE, "lastShotBlocks")).doubleValue());
    }

    private static void look(ClientGameTestContext ctx, double tx, double ty, double tz) {
        ctx.runOnClient(mc -> {
            double ex = mc.player.getX();
            double ey = mc.player.getEyeY();
            double ez = mc.player.getZ();
            double dx = tx - ex;
            double dy = ty - ey;
            double dz = tz - ez;
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
            mc.player.setYRot(mc.player.getYRot() + net.minecraft.util.Mth.wrapDegrees(yaw - mc.player.getYRot()));
            mc.player.setXRot(net.minecraft.util.Mth.clamp(pitch, -90f, 90f));
        });
    }

    /**
     * Walk forward one tick after standing charged and return how far the client went that tick, plus the shot's own
     * numbers. The shot runs at the start of the tick the key is first down.
     */
    private static double[] shootByWalking(ClientGameTestContext ctx) {
        long shots0 = cnum(ctx, "shots");
        double[] before = ctx.computeOnClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
        ctx.getInput().holdKey(o -> o.keyUp);
        ctx.waitTicks(1);
        double[] after = ctx.computeOnClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
        ctx.getInput().releaseKey(o -> o.keyUp);
        long shots = cnum(ctx, "shots") - shots0;
        return new double[]{flat(before, after), shots, cnum(ctx, "lastShotTicks")};
    }

    /**
     * 1025-blink-charge: Charge on, stand still 40 ticks (the bank fills to 22 and stops), then walk: the 22 ticks run at
     * once and he is about five blocks on in one tick, client and server agreeing. Three times over - it never runs out.
     */
    private static void charge(ClientGameTestContext ctx) {
        int x = -4600;
        int z = -3400;
        Scenario.run(ctx, "1025-blink-charge", (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = setUp(ctx, 22, "RELEASE");
            try {
                String name = playerName(ctx);
                ctx.runOnClient(mc -> PacketTrace.start());
                chargeOn(ctx);
                StringBuilder rounds = new StringBuilder();
                int good = 0;
                for (int round = 0; round < 3; round++) {
                    ctx.waitTicks(40);
                    long bank = cnum(ctx, "banked");
                    double[] shot = shootByWalking(ctx);
                    ctx.waitTicks(10);
                    double[] client = scenario.playerPosition();
                    double[] srv = serverPos(server, ctx, name);
                    rounds.append(String.format(Locale.ROOT, " [round %d: bank %d, shot %d tick(s) %.2f blocks in one tick; "
                            + "client %s server %s]", round + 1, bank, (long) shot[2], shot[0], fmt(client), fmt(srv)));
                    if (bank == 22 && shot[1] == 1 && shot[2] == 22 && shot[0] > 3.5 && flat(client, srv) < 0.5) {
                        good++;
                    }
                }
                ctx.runOnClient(mc -> PacketTrace.stop());
                int setbacks = PacketTrace.received("player_position");
                scenario.log("charge:" + rounds + "; " + setbacks + " setback(s)");
                check(good == 3, good + " of 3 charge shots banked 22 ticks and shot him forward with the server agreeing");
                check(setbacks == 0, "the server set him back " + setbacks + " time(s)");
            } finally {
                chargeOff(ctx);
                tearDown(ctx, gate);
            }
            ctx.waitTicks(40);
        });
    }

    /**
     * 1025-blink-charge-actions: while charged, flick a lever and dig two dirt blocks by hand, then shoot. The lever and the
     * digs must reach the server (it shows them), the bank must still be there for the shot, and Grim says nothing.
     */
    private static void chargeActions(ClientGameTestContext ctx) {
        int x = -4600;
        int z = -3600;
        Scenario.run(ctx, "1025-blink-charge-actions", (server, scenario) -> {
            arena(server, x, z);
            server.command("setblock " + (x + 2) + " " + SURFACE_Y + " " + (z + 2) + " minecraft:stone");
            server.command("setblock " + (x + 2) + " " + (SURFACE_Y + 1) + " " + (z + 2) + " minecraft:lever[face=floor,facing=east]");
            server.command("setblock " + (x + 2) + " " + SURFACE_Y + " " + (z - 2) + " minecraft:dirt");
            server.command("setblock " + (x + 2) + " " + (SURFACE_Y + 1) + " " + (z - 2) + " minecraft:dirt");
        }, (server, scenario) -> {
            boolean gate = setUp(ctx, 22, "RELEASE");
            try {
                String name = playerName(ctx);
                ctx.runOnClient(mc -> PacketTrace.start());
                chargeOn(ctx);
                ctx.waitTicks(30);
                long bankBefore = cnum(ctx, "banked");
                // The lever.
                look(ctx, x + 2.5, SURFACE_Y + 1.1, z + 2.5);
                ctx.waitTicks(2);
                ctx.getInput().pressKey(o -> o.keyUse);
                ctx.waitTicks(10);
                // The two dirt blocks, top first, by hand.
                look(ctx, x + 2.5, SURFACE_Y + 1.5, z - 1.5);
                ctx.waitTicks(2);
                ctx.getInput().holdKey(o -> o.keyAttack);
                ctx.waitTicks(30);
                look(ctx, x + 2.5, SURFACE_Y + 0.5, z - 1.5);
                ctx.waitTicks(30);
                ctx.getInput().releaseKey(o -> o.keyAttack);
                // Face along +x again and stand a moment, still charged.
                ctx.runOnClient(mc -> {
                    mc.player.setYRot(mc.player.getYRot() + net.minecraft.util.Mth.wrapDegrees(-90f - mc.player.getYRot()));
                    mc.player.setXRot(0f);
                });
                ctx.waitTicks(30);
                long bank = cnum(ctx, "banked");
                double[] shot = shootByWalking(ctx);
                ctx.waitTicks(20);
                ctx.runOnClient(mc -> PacketTrace.stop());
                server.command("execute if block " + (x + 2) + " " + (SURFACE_Y + 1) + " " + (z + 2)
                        + " minecraft:lever[powered=true] run say LEVER_ON");
                server.command("execute if block " + (x + 2) + " " + (SURFACE_Y + 1) + " " + (z - 2)
                        + " minecraft:air if block " + (x + 2) + " " + SURFACE_Y + " " + (z - 2) + " minecraft:air run say DIRT_GONE");
                ctx.waitTicks(10);
                String tail = server.tail(80);
                boolean lever = tail.contains("LEVER_ON");
                boolean dirt = tail.contains("DIRT_GONE");
                double[] client = scenario.playerPosition();
                double[] srv = serverPos(server, ctx, name);
                int setbacks = PacketTrace.received("player_position");
                scenario.log(String.format(Locale.ROOT, "charge-actions: bank %d before, %d at the shot; lever on (server) %s, both "
                                + "dirt gone (server) %s; shot %d tick(s) %.2f blocks in one tick; client %s server %s; %d setback(s)",
                        bankBefore, bank, lever, dirt, (long) shot[2], shot[0], fmt(client), fmt(srv), setbacks));
                check(bankBefore >= 20, "the bank did not fill before the actions: " + bankBefore);
                check(lever, "the lever flick did not reach the server");
                check(dirt, "the digging did not reach the server");
                check(shot[1] == 1 && shot[2] >= 20 && shot[0] > 3.0, "no full shot after the actions: " + (long) shot[2]
                        + " tick(s), " + shot[0] + " blocks");
                check(flat(client, srv) < 0.5, "client and server disagree after the shot: " + fmt(client) + " vs " + fmt(srv));
                check(setbacks == 0, "the server set him back " + setbacks + " time(s)");
            } finally {
                ctx.getInput().releaseKey(o -> o.keyAttack);
                chargeOff(ctx);
                tearDown(ctx, gate);
            }
            ctx.waitTicks(40);
        });
    }

    /**
     * 1025-blink-charge-warp: charged, then a real etherwarp (Hx abilities) and a server teleport (a leap) in the middle of
     * the charge; the bank refills and the shot still goes, with the server agreeing and Grim silent.
     */
    private static void chargeWarp(ClientGameTestContext ctx) {
        int x = -4600;
        int z = -3800;
        Scenario.run(ctx, "1025-blink-charge-warp", (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = setUp(ctx, 22, "RELEASE");
            dev.testkit.gametest.hx.Hx hx = null;
            try {
                String name = playerName(ctx);
                hx = dev.testkit.gametest.hx.Hx.connect();
                hx.call("dungeon.abilities", "enabled", true);
                hx.give(0, AOTV);
                ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
                ctx.waitTicks(10);
                com.google.gson.JsonObject before = hx.call("dungeon.stats").getAsJsonObject();
                ctx.runOnClient(mc -> PacketTrace.start());
                chargeOn(ctx);
                ctx.waitTicks(30);
                long bank1 = cnum(ctx, "banked");
                // Etherwarp 12 blocks on, sneaking.
                ctx.getInput().holdKey(o -> o.keyShift);
                ctx.waitTicks(3);
                look(ctx, x + 12.5, SURFACE_Y, z + 0.5);
                ctx.waitTicks(1);
                ctx.getInput().pressKey(o -> o.keyUse);
                ctx.waitTicks(10);
                ctx.getInput().releaseKey(o -> o.keyShift);
                ctx.waitTicks(30);
                // A leap: the server moves him 10 blocks to the side.
                server.command("tp " + name + " " + (x + 12.5) + " " + SURFACE_Y + " " + (z + 10.5));
                ctx.waitTicks(30);
                ctx.runOnClient(mc -> {
                    mc.player.setYRot(mc.player.getYRot() + net.minecraft.util.Mth.wrapDegrees(-90f - mc.player.getYRot()));
                    mc.player.setXRot(0f);
                });
                ctx.waitTicks(30);
                long bank2 = cnum(ctx, "banked");
                double[] shot = shootByWalking(ctx);
                ctx.waitTicks(20);
                ctx.runOnClient(mc -> PacketTrace.stop());
                com.google.gson.JsonObject after = hx.call("dungeon.stats").getAsJsonObject();
                int warps = after.get("etherwarps").getAsInt() - before.get("etherwarps").getAsInt();
                double[] client = scenario.playerPosition();
                double[] srv = serverPos(server, ctx, name);
                int positions = PacketTrace.received("player_position");
                scenario.log(String.format(Locale.ROOT, "charge-warp: bank %d before the warp, %d at the shot; %d server warp(s), "
                                + "%d position packet(s); shot %d tick(s) %.2f blocks in one tick; client %s server %s", bank1,
                        bank2, warps, positions, (long) shot[2], shot[0], fmt(client), fmt(srv)));
                check(bank1 >= 20, "the bank did not fill before the warp: " + bank1);
                check(warps == 1, "the server did not etherwarp: " + warps);
                check(positions == 2, positions + " position packet(s), not the warp and the leap");
                check(shot[1] == 1 && shot[2] >= 20 && shot[0] > 3.0, "no full shot after the warp and leap: " + (long) shot[2]
                        + " tick(s), " + shot[0] + " blocks");
                check(Math.abs(client[2] - (z + 10.5)) < 1.0, "he is not on the leap's row: " + fmt(client));
                check(flat(client, srv) < 0.5, "client and server disagree after the shot: " + fmt(client) + " vs " + fmt(srv));
            } finally {
                ctx.getInput().releaseKey(o -> o.keyShift);
                if (hx != null) {
                    hx.call("dungeon.abilities", "enabled", false);
                    hx.close();
                }
                chargeOff(ctx);
                tearDown(ctx, gate);
            }
            ctx.waitTicks(40);
        });
    }

    /**
     * 1025-blink-charge-run (killer560, 2026-10-25: "make it charge while blinking and running"): Shoot When You Move off.
     * Charge standing still, then sprint about for 40 ticks - the bank is kept - and press the Blink key mid-sprint: the
     * bank fires at once, charging goes on, and after standing again it is full and fires again. Grim silent.
     */
    private static void chargeRun(ClientGameTestContext ctx) {
        int x = -4800;
        int z = -3400;
        Scenario.run(ctx, "1025-blink-charge-run", (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = setUp(ctx, 22, "RELEASE");
            try {
                String name = playerName(ctx);
                ctx.runOnClient(mc -> PacketTrace.start());
                chargeOn(ctx);
                ctx.runOnClient(mc -> Mod.set(CFG, "setShootOnMove", false));
                StringBuilder rounds = new StringBuilder();
                int good = 0;
                for (int round = 0; round < 2; round++) {
                    ctx.waitTicks(35);
                    long bankStill = cnum(ctx, "banked");
                    ctx.getInput().holdKey(o -> o.keyUp);
                    ctx.getInput().holdKey(o -> o.keySprint);
                    ctx.waitTicks(20);
                    long bankRunning = cnum(ctx, "banked");
                    long shots0 = cnum(ctx, "shots");
                    double[] before = scenario.playerPosition();
                    pressBlink(ctx);
                    double[] after = scenario.playerPosition();
                    long shots = cnum(ctx, "shots") - shots0;
                    long ticks = cnum(ctx, "lastShotTicks");
                    boolean stillCharging = ctx.computeOnClient(mc -> (Boolean) Mod.staticCall(CHARGE, "isActive"));
                    ctx.waitTicks(5);
                    ctx.getInput().releaseKey(o -> o.keySprint);
                    ctx.getInput().releaseKey(o -> o.keyUp);
                    // Turn round so the next sprint stays on the platform.
                    ctx.runOnClient(mc -> mc.player.setYRot(mc.player.getYRot() + 180f));
                    ctx.waitTicks(10);
                    double[] client = scenario.playerPosition();
                    double[] srv = serverPos(server, ctx, name);
                    double went = flat(before, after);
                    rounds.append(String.format(Locale.ROOT, " [round %d: bank %d standing, %d after 20 sprinting ticks; key press "
                                    + "shot %d tick(s), %.2f blocks over the press's 2 ticks, still charging %s; client %s server %s]",
                            round + 1, bankStill, bankRunning, ticks, went, stillCharging, fmt(client), fmt(srv)));
                    if (bankStill == 22 && bankRunning == 22 && shots == 1 && ticks == 22 && went > 6.0 && stillCharging
                            && flat(client, srv) < 0.5) {
                        good++;
                    }
                }
                ctx.runOnClient(mc -> PacketTrace.stop());
                int setbacks = PacketTrace.received("player_position");
                scenario.log("charge-run:" + rounds + "; " + setbacks + " setback(s)");
                check(good == 2, good + " of 2 rounds kept the bank while sprinting and fired it on the key");
                check(setbacks == 0, "the server set him back " + setbacks + " time(s)");
            } finally {
                ctx.getInput().releaseKey(o -> o.keySprint);
                ctx.getInput().releaseKey(o -> o.keyUp);
                chargeOff(ctx);
                tearDown(ctx, gate);
            }
            ctx.waitTicks(40);
        });
    }

    /**
     * 1025-blink-charge-sweep: how big a bank GrimAC takes. For each length (testkit.chargeSweep, default 22,23,24,26,30):
     * back to the start, charge exactly that many ticks standing, shoot by walking, and record what Grim said.
     */
    private static void chargeSweep(ClientGameTestContext ctx) {
        int x = -4800;
        int z = -3600;
        Scenario.runExpectingFlags(ctx, "1025-blink-charge-sweep", (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = setUp(ctx, 22, "RELEASE");
            String p = System.getProperty("testkit.chargeSweep");
            String[] parts = (p == null || p.isBlank() ? "22,23,24,26,30" : p).split(",");
            List<String> rows = new ArrayList<>();
            int longestClean = 0;
            boolean brokeClean = false;
            String name = playerName(ctx);
            try {
                for (String part : parts) {
                    int len = Integer.parseInt(part.trim());
                    server.command("tp " + name + " " + (x - 20.5) + " " + SURFACE_Y + " " + (z + 0.5) + " -90 0");
                    ctx.waitTicks(30);
                    ctx.runOnClient(mc -> Mod.set(CFG, "setMaxTicks", len));
                    server.mark();
                    chargeOn(ctx);
                    ctx.waitTicks(len + 15);
                    long bank = cnum(ctx, "banked");
                    double[] shot = shootByWalking(ctx);
                    ctx.getInput().holdKey(o -> o.keyUp);
                    ctx.waitTicks(10);
                    ctx.getInput().releaseKey(o -> o.keyUp);
                    chargeOff(ctx);
                    ctx.waitTicks(40);
                    List<String> flags = scenario.flags();
                    boolean ok = flags.isEmpty() && bank == len && (long) shot[2] == len;
                    String row = String.format(Locale.ROOT, "bank %3d: banked %d, shot %d tick(s) %.2f blocks, %s", len, bank,
                            (long) shot[2], shot[0], flags.isEmpty() ? "clean" : flags.size() + " flag(s): " + firstChecks(flags));
                    rows.add(row);
                    scenario.log(row);
                    if (ok && !brokeClean) {
                        longestClean = len;
                    } else if (!ok) {
                        brokeClean = true;
                    }
                    if (!flags.isEmpty()) {
                        ctx.waitTicks(60);
                    }
                }
            } finally {
                chargeOff(ctx);
                tearDown(ctx, gate);
            }
            scenario.log("SWEEP 1025-blink-charge-sweep: biggest bank clean (and every smaller one) " + longestClean + " tick(s)");
            for (String r : rows) {
                scenario.log("  " + r);
            }
            check(!rows.isEmpty(), "the sweep ran no lengths");
            server.mark();
        });
    }

    /**
     * 1025-blink-charge-double (killer560, 2026-10-25: "effectively get 2 blinks"): charge 22 standing, then sprint - the
     * bank fires at once and a normal 22-tick hold starts straight after (Blink Again After Shot), released at its limit.
     * Grim must stay silent and client and server agree at the end.
     */
    private static void chargeDouble(ClientGameTestContext ctx) {
        int x = -4800;
        int z = -3800;
        Scenario.run(ctx, "1025-blink-charge-double", (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = setUp(ctx, 22, "RELEASE");
            try {
                String name = playerName(ctx);
                chargeOn(ctx);
                ctx.runOnClient(mc -> Mod.set(CFG, "setHoldAfterShot", true));
                ctx.waitTicks(35);
                long bank = cnum(ctx, "banked");
                long holds0 = num(ctx, "releasedHolds");
                ctx.runOnClient(mc -> PacketTrace.start());
                double[] start = scenario.playerPosition();
                ctx.getInput().holdKey(o -> o.keyUp);
                ctx.getInput().holdKey(o -> o.keySprint);
                ctx.waitTicks(1);
                double[] afterShot = scenario.playerPosition();
                long shotTicks = cnum(ctx, "lastShotTicks");
                boolean followUp = holding(ctx) && "charge follow-up".equals(
                        ctx.computeOnClient(mc -> (String) Mod.staticCall(FEATURE, "holdOwner")));
                ctx.waitTicks(30);
                ctx.getInput().releaseKey(o -> o.keySprint);
                ctx.getInput().releaseKey(o -> o.keyUp);
                ctx.waitTicks(10);
                ctx.runOnClient(mc -> PacketTrace.stop());
                long released = num(ctx, "releasedHolds") - holds0;
                long lastHold = num(ctx, "lastHoldTicks");
                int setbacks = PacketTrace.received("player_position");
                double[] client = scenario.playerPosition();
                double[] srv = serverPos(server, ctx, name);
                scenario.log(String.format(Locale.ROOT, "charge-double: bank %d; shot %d tick(s) %.2f blocks in one tick; follow-up "
                                + "hold started %s, %d release(s), last hold %d tick(s); %.2f blocks in all; client %s server %s; "
                                + "%d setback(s)", bank, shotTicks, flat(start, afterShot), followUp, released, lastHold,
                        flat(start, client), fmt(client), fmt(srv), setbacks));
                check(bank == 22 && shotTicks == 22 && flat(start, afterShot) > 5.0, "the charge did not shoot 22 ticks");
                check(followUp, "no follow-up hold started after the shot");
                check(released >= 1 && lastHold == 22, "the follow-up hold did not run to 22 ticks and release");
                check(flat(client, srv) < 0.5, "client and server disagree at the end: " + fmt(client) + " vs " + fmt(srv));
                check(setbacks == 0, "the server set him back " + setbacks + " time(s)");
            } finally {
                ctx.getInput().releaseKey(o -> o.keySprint);
                ctx.getInput().releaseKey(o -> o.keyUp);
                chargeOff(ctx);
                tearDown(ctx, gate);
            }
            ctx.waitTicks(40);
        });
    }

    /**
     * 1025-blink-clicks (killer560, 2026-10-25: a blink "while being in a terminal menu and clicking"): a server menu open,
     * a 22-tick Hold Packets blink, four slot clicks during it. The clicks go out live (Menu Clicks Go Through), the hold
     * runs to its limit and releases, the menu stays open, Grim silent.
     */
    private static void clicks(ClientGameTestContext ctx) {
        int x = -5000;
        int z = -3400;
        Scenario.run(ctx, "1025-blink-clicks", (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = setUp(ctx, 22, "RELEASE");
            try (dev.testkit.gametest.hx.Hx hx = dev.testkit.gametest.hx.Hx.connect()) {
                ctx.runOnClient(mc -> Mod.set(CFG, "setClicksLive", true));
                hx.menu("Blink Terminal", 3, java.util.Map.of(11, "minecraft:stone", 13, "minecraft:dirt", 15,
                        "minecraft:oak_log"), null);
                ctx.waitTicks(10);
                long live0 = num(ctx, "clicksLive");
                long holds0 = num(ctx, "releasedHolds");
                pressBlink(ctx);
                for (int t = 0; t < 20; t++) {
                    ctx.waitTicks(1);
                    if (t % 5 == 2) {
                        int slot = 11 + (t / 5 % 3) * 2;
                        ctx.runOnClient(mc -> {
                            var menu = mc.player.containerMenu;
                            mc.gameMode.handleContainerInput(menu.containerId, slot, 0,
                                    net.minecraft.world.inventory.ContainerInput.PICKUP, mc.player);
                        });
                    }
                }
                ctx.waitTicks(15);
                boolean open = ctx.computeOnClient(mc -> mc.screen instanceof
                        net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>);
                long live = num(ctx, "clicksLive") - live0;
                long released = num(ctx, "releasedHolds") - holds0;
                long lastHold = num(ctx, "lastHoldTicks");
                ctx.runOnClient(mc -> mc.player.closeContainer());
                scenario.log(String.format(Locale.ROOT, "clicks: %d click(s) went out live during the hold, %d release(s), last "
                        + "hold %d tick(s), menu still open %s", live, released, lastHold, open));
                check(live >= 4, "fewer than four clicks went out live during the hold: " + live);
                check(released >= 1 && lastHold == 22, "the hold did not run to 22 ticks and release");
                check(open, "the menu closed during the blink");
            } finally {
                tearDown(ctx, gate);
            }
            ctx.waitTicks(40);
        });
    }

    /**
     * 1025-blink-charge-far (killer560, 2026-10-25: "blink as far as possible"): the shot runs his real movement, so its
     * length is his speed times 22 ticks. A plain walking shot, then one sprint-jumping with Speed IV: both must be clean
     * and agree with the server; the log gives the distances.
     */
    private static void chargeFar(ClientGameTestContext ctx) {
        int x = -5000;
        int z = -3800;
        Scenario.run(ctx, "1025-blink-charge-far", (server, scenario) -> arena(server, x - 25, z), (server, scenario) -> {
            boolean gate = setUp(ctx, 22, "RELEASE");
            String name = playerName(ctx);
            try {
                ctx.runOnClient(mc -> PacketTrace.start());
                chargeOn(ctx);
                ctx.waitTicks(35);
                double[] walk = shootByWalking(ctx);
                StringBuilder rows = new StringBuilder();
                int good = 0;
                // jump only, Speed IV only (sprinting), both
                boolean[][] kinds = {{true, false}, {false, true}, {true, true}};
                double[] dist = new double[3];
                for (int k = 0; k < 3; k++) {
                    boolean jump = kinds[k][0];
                    boolean speed = kinds[k][1];
                    server.command("effect clear " + name);
                    server.command("tp " + name + " " + (x - 55.5) + " " + SURFACE_Y + " " + (z + 0.5) + " -90 0");
                    if (speed) {
                        server.command("effect give " + name + " minecraft:speed 60 3 true");
                    }
                    ctx.waitTicks(60);
                    long bank = cnum(ctx, "banked");
                    int sb0 = PacketTrace.received("player_position");
                    double[] before = scenario.playerPosition();
                    ctx.getInput().holdKey(o -> o.keySprint);
                    if (jump) {
                        ctx.getInput().holdKey(o -> o.keyJump);
                    }
                    ctx.getInput().holdKey(o -> o.keyUp);
                    ctx.waitTicks(1);
                    double[] after = scenario.playerPosition();
                    ctx.getInput().releaseKey(o -> o.keyUp);
                    ctx.getInput().releaseKey(o -> o.keyJump);
                    ctx.getInput().releaseKey(o -> o.keySprint);
                    long ticks = cnum(ctx, "lastShotTicks");
                    ctx.waitTicks(30);
                    double[] client = scenario.playerPosition();
                    double[] srv = serverPos(server, ctx, name);
                    int sb = PacketTrace.received("player_position") - sb0;
                    dist[k] = flat(before, after);
                    String label = jump && speed ? "sprint-jump + Speed IV" : jump ? "sprint-jump" : "sprint + Speed IV";
                    rows.append(String.format(Locale.ROOT, " [%s: bank %d, shot %d tick(s) %.2f blocks in one tick; client %s "
                            + "server %s; %d position packet(s) after]", label, bank, ticks, dist[k], fmt(client), fmt(srv), sb));
                    if (bank == 22 && ticks == 22 && dist[k] > walk[0] && flat(client, srv) < 0.5 && sb == 0) {
                        good++;
                    }
                }
                ctx.runOnClient(mc -> PacketTrace.stop());
                server.command("effect clear " + name);
                scenario.log(String.format(Locale.ROOT, "charge-far: walking shot %d tick(s) %.2f blocks;", (long) walk[2], walk[0])
                        + rows);
                check(walk[2] == 22, "the walking shot was not 22 ticks");
                check(good == 3, good + " of 3 fast shots went further than walking with the server agreeing");
            } finally {
                server.command("effect clear " + name);
                chargeOff(ctx);
                tearDown(ctx, gate);
            }
            ctx.waitTicks(40);
        });
    }

    /**
     * 1025-blink-charge-long (killer560, 2026-10-25: "I don't want the charge to be dropped due to time"): charge, then
     * wait 40 s - standing, then a lever flick, then sprinting about - and shoot. The bank must still be full and the shot
     * clean; he is never kicked (Grim's max-transaction-time is 60 s).
     */
    private static void chargeLong(ClientGameTestContext ctx) {
        int x = -5200;
        int z = -3400;
        Scenario.run(ctx, "1025-blink-charge-long", (server, scenario) -> {
            arena(server, x, z);
            server.command("setblock " + (x + 2) + " " + SURFACE_Y + " " + (z + 2) + " minecraft:stone");
            server.command("setblock " + (x + 2) + " " + (SURFACE_Y + 1) + " " + (z + 2) + " minecraft:lever[face=floor,facing=east]");
        }, (server, scenario) -> {
            boolean gate = setUp(ctx, 22, "RELEASE");
            try {
                String name = playerName(ctx);
                ctx.runOnClient(mc -> PacketTrace.start());
                chargeOn(ctx);
                ctx.runOnClient(mc -> Mod.set(CFG, "setShootOnMove", false));
                ctx.waitTicks(400);
                look(ctx, x + 2.5, SURFACE_Y + 1.1, z + 2.5);
                ctx.waitTicks(2);
                ctx.getInput().pressKey(o -> o.keyUse);
                ctx.waitTicks(200);
                ctx.runOnClient(mc -> {
                    mc.player.setYRot(mc.player.getYRot() + net.minecraft.util.Mth.wrapDegrees(90f - mc.player.getYRot()));
                    mc.player.setXRot(0f);
                });
                ctx.getInput().holdKey(o -> o.keyUp);
                ctx.getInput().holdKey(o -> o.keySprint);
                ctx.waitTicks(60);
                ctx.runOnClient(mc -> mc.player.setYRot(mc.player.getYRot() + 180f));
                ctx.waitTicks(60);
                ctx.getInput().releaseKey(o -> o.keySprint);
                ctx.getInput().releaseKey(o -> o.keyUp);
                ctx.waitTicks(80);
                ctx.runOnClient(mc -> {
                    mc.player.setYRot(mc.player.getYRot() + net.minecraft.util.Mth.wrapDegrees(-90f - mc.player.getYRot()));
                    mc.player.setXRot(0f);
                });
                ctx.waitTicks(5);
                long bank = cnum(ctx, "banked");
                boolean connected = ctx.computeOnClient(mc -> mc.getConnection() != null && mc.player != null);
                long shots0 = cnum(ctx, "shots");
                double[] before = scenario.playerPosition();
                ctx.getInput().holdKey(o -> o.keyUp);
                pressBlink(ctx);
                double[] after = scenario.playerPosition();
                ctx.getInput().releaseKey(o -> o.keyUp);
                long shots = cnum(ctx, "shots") - shots0;
                long ticks = cnum(ctx, "lastShotTicks");
                ctx.waitTicks(20);
                ctx.runOnClient(mc -> PacketTrace.stop());
                int setbacks = PacketTrace.received("player_position");
                double[] client = scenario.playerPosition();
                double[] srv = serverPos(server, ctx, name);
                scenario.log(String.format(Locale.ROOT, "charge-long: after ~40 s (standing, a lever, sprinting) bank %d, connected %s; "
                                + "shot %d tick(s), %.2f blocks over the press's 2 ticks; client %s server %s; %d setback(s)", bank,
                        connected, ticks, flat(before, after), fmt(client), fmt(srv), setbacks));
                check(connected, "he was disconnected while charging");
                check(bank == 22, "the bank was not full after the wait: " + bank);
                check(shots == 1 && ticks == 22 && flat(before, after) > 4.0, "no full shot after the wait");
                check(flat(client, srv) < 0.5, "client and server disagree after the shot: " + fmt(client) + " vs " + fmt(srv));
                check(setbacks == 0, "the server set him back " + setbacks + " time(s)");
            } finally {
                ctx.getInput().releaseKey(o -> o.keySprint);
                ctx.getInput().releaseKey(o -> o.keyUp);
                chargeOff(ctx);
                tearDown(ctx, gate);
            }
            ctx.waitTicks(40);
        });
    }

    /**
     * 1025-blink-world: the server moves him to another dimension in the middle of a sprinting hold. The held moves belong
     * to the world he left and are dropped; everything else goes; the blink ends; he is not kicked and Grim says nothing.
     */
    private static void worldChange(ClientGameTestContext ctx) {
        int x = -4500;
        int z = -3000;
        Scenario.run(ctx, "1025-blink-world", (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = setUp(ctx, 200, "RELEASE");
            try {
                String name = playerName(ctx);
                String dimBefore = ctx.computeOnClient(mc -> mc.level.dimension().toString());
                ctx.getInput().holdKey(o -> o.keyUp);
                ctx.getInput().holdKey(o -> o.keySprint);
                ctx.waitTicks(5);
                pressBlink(ctx);
                ctx.waitTicks(10);
                boolean heldBefore = holding(ctx);
                long heldMoves = num(ctx, "heldMoves");
                releaseKeys(ctx);
                server.command("execute in minecraft:the_nether run forceload add 0 0");
                server.command("execute in minecraft:the_nether run fill -8 100 -8 8 100 8 minecraft:stone");
                server.command("execute in minecraft:the_nether run fill -8 101 -8 8 105 8 minecraft:air");
                server.command("execute in minecraft:the_nether run tp " + name + " 0.5 101 0.5");
                String dimAfter = dimBefore;
                for (int i = 0; i < 200 && dimAfter.equals(dimBefore); i++) {
                    ctx.waitTicks(1);
                    dimAfter = ctx.computeOnClient(mc -> mc.level == null ? "" : mc.level.dimension().toString());
                }
                ctx.waitTicks(60);
                boolean holdingAfter = holding(ctx);
                boolean connected = ctx.computeOnClient(mc -> mc.getConnection() != null && mc.player != null);
                // Walk a little in the new world, so a desync would show up as a flag or a setback.
                ctx.runOnClient(mc -> PacketTrace.start());
                ctx.getInput().holdKey(o -> o.keyUp);
                ctx.waitTicks(20);
                ctx.getInput().releaseKey(o -> o.keyUp);
                ctx.waitTicks(20);
                ctx.runOnClient(mc -> PacketTrace.stop());
                int setbacks = PacketTrace.received("player_position");
                scenario.log("world change: " + dimBefore + " -> " + dimAfter + "; holding before " + heldBefore + " (" + heldMoves
                        + " move(s) held), holding after " + holdingAfter + ", connected " + connected + ", " + setbacks
                        + " setback(s) walking in the new world");
                check(heldBefore && heldMoves > 0, "no moves were held when the world changed: nothing was tested");
                check(!dimAfter.equals(dimBefore), "the dimension never changed: " + dimAfter);
                check(connected, "he was disconnected");
                check(!holdingAfter, "Blink kept holding into the new world");
                check(setbacks == 0, "the server set him back " + setbacks + " time(s) in the new world");
            } finally {
                tearDown(ctx, gate);
                server.command("execute in minecraft:overworld run tp " + playerName(ctx) + " " + (x + 0.5) + " " + SURFACE_Y
                        + " " + (z + 0.5));
                ctx.waitTicks(60);
            }
        });
    }

    /** 1025-blink-bow: drawing and loosing a bow five times (25-tick draws) while Pulse holds them in bursts. */
    private static void bow(ClientGameTestContext ctx) {
        int x = -4600;
        int z = -3000;
        Scenario.run(ctx, "1025-blink-bow", (server, scenario) -> {
            TestMap.on(server)
                    .platform(x, SURFACE_Y - 1, z, 12)
                    .catchFloor(120)
                    .survival()
                    .clearInventory()
                    .give("bow", 1)
                    .give("arrow", 64)
                    .spawn(x + 0.5, z + 0.5, -90f, -10f)
                    .build();
        }, (server, scenario) -> {
            boolean gate = setUp(ctx, defaultTicks(ctx), "PULSE");
            try {
                int before = ctx.computeOnClient(mc -> mc.player.getInventory().countItem(net.minecraft.world.item.Items.ARROW));
                long holds0 = num(ctx, "releasedHolds");
                pressBlink(ctx);
                for (int i = 0; i < 5; i++) {
                    ctx.getInput().holdKey(o -> o.keyUse);
                    ctx.waitTicks(25);
                    ctx.getInput().releaseKey(o -> o.keyUse);
                    ctx.waitTicks(10);
                }
                pressBlink(ctx);
                ctx.waitTicks(40);
                int after = ctx.computeOnClient(mc -> mc.player.getInventory().countItem(net.minecraft.world.item.Items.ARROW));
                long holds = num(ctx, "releasedHolds") - holds0;
                scenario.log("bow: arrows " + before + " -> " + after + " (40 ticks after the last release), " + holds + " release(s)");
                check(before == 64 && after <= before - 4, "fewer than four arrows were loosed: the bow was hardly tested");
                check(holds >= 4, "Pulse did not hold and release while shooting: " + holds);
            } finally {
                ctx.getInput().releaseKey(o -> o.keyUse);
                tearDown(ctx, gate);
            }
            ctx.waitTicks(40);
        });
    }

    private static void sweep(ClientGameTestContext ctx, String name, int x, boolean walking, int latencyMs) {
        Scenario.runExpectingFlags(ctx, name, (server, scenario) -> arena(server, x, -3000), (server, scenario) -> {
            boolean gate = setUp(ctx, 17, "RELEASE");
            if (latencyMs > 0) {
                ctx.runOnClient(mc -> Latency.install(latencyMs));
                ctx.waitTicks(40);
            }
            List<String> rows = new ArrayList<>();
            java.util.TreeSet<Integer> cleanLens = new java.util.TreeSet<>();
            java.util.TreeSet<Integer> failedLens = new java.util.TreeSet<>();
            int tested = 0;
            String player = playerName(ctx);
            try {
                for (int len : sweepLengths()) {
                    // Back to the start, then let the teleport settle before the hold.
                    server.command("tp " + player + " " + (x - 34.5) + " " + SURFACE_Y + " " + (-3000 + 0.5) + " -90 0");
                    ctx.waitTicks(30);
                    ctx.runOnClient(mc -> Mod.set(CFG, "setMaxTicks", len));
                    server.mark();
                    double[] r;
                    if (walking) {
                        // A sprint covers ~5.6 blocks/s; walk back and forth so a long hold stays on the platform.
                        r = walkHold(server, scenario, ctx, len, false);
                    } else {
                        pressBlink(ctx);
                        for (int i = 0; i < len + 10 && holding(ctx); i++) {
                            ctx.waitTicks(1);
                        }
                        ctx.waitTicks(20);
                        r = new double[]{0, 0, 0, 0, holding(ctx) ? 1 : 0, num(ctx, "lastHoldTicks")};
                    }
                    ctx.waitTicks(40);
                    List<String> flags = scenario.flags();
                    tested++;
                    boolean ok = flags.isEmpty() && r[3] == 0 && r[5] == len && (!walking || (r[0] >= 0 && r[0] < 0.2));
                    String row = String.format(Locale.ROOT, "len %3d: held %d tick(s), %s, %d setback(s)%s", len,
                            (int) r[5], flags.isEmpty() ? "clean" : flags.size() + " flag(s): " + firstChecks(flags),
                            (int) r[3], walking ? String.format(Locale.ROOT, ", server moved %.2f / client %.2f / gap %.2f",
                                    r[0], r[1], r[2]) : "");
                    rows.add(row);
                    scenario.log(row);
                    if (!ok) {
                        failedLens.add(len);
                    } else {
                        cleanLens.add(len);
                    }
                    if (!flags.isEmpty()) {
                        // Grim marks him after a flag; let it settle so the next length starts clean.
                        ctx.waitTicks(60);
                    }
                }
            } finally {
                if (latencyMs > 0) {
                    ctx.runOnClient(mc -> Latency.remove());
                }
                tearDown(ctx, gate);
            }
            int longestClean = 0;
            for (int l : cleanLens) {
                if (!failedLens.contains(l) && failedLens.headSet(l).isEmpty()) {
                    longestClean = l;
                }
            }
            scenario.log("SWEEP " + name + ": longest hold clean every time (and every shorter one too) " + longestClean
                    + " tick(s); " + tested + " hold(s) tried; clean lengths " + cleanLens + ", flagged lengths " + failedLens
                    + (latencyMs > 0 ? "; latency " + latencyMs + " ms" : ""));
            for (String r : rows) {
                scenario.log("  " + r);
            }
            check(tested > 0, "the sweep ran no lengths");
            server.mark();
        });
    }

    private static String firstChecks(List<String> flags) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(3, flags.size()); i++) {
            String f = flags.get(i);
            int cut = f.indexOf("failed");
            b.append(i == 0 ? "" : " | ").append(cut >= 0 ? f.substring(Math.max(0, cut - 40), Math.min(f.length(), cut + 60))
                    : f.substring(Math.max(0, f.length() - 100)));
        }
        return b.toString();
    }
}
