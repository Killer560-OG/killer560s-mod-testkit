package dev.testkit.gametest;

import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.Latency;
import dev.testkit.harness.PacketTrace;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 1025-blink-* (killer560, 2026-10-25: "fully build blink and add it to the mod"; reworked the same day after testing on
 * Hypixel: Charge and Hold are two sections with their own keys and 1-20 tick sliders). Blink's Hold holds every packet
 * the client sends while the Hold key is down and releases them together when it comes up (or at Hold Ticks, after which
 * it waits for the key to come up). Charge banks still ticks (up to Charge Ticks) and fires them on the Charge key.
 *
 * <p>Every case proves the feature acted before it says anything about GrimAC: the mod's own counters (ticks and packets
 * held and released, ticks banked and shot) AND the SERVER's view of the player ({@code data get entity}).
 *
 * <p>Since the rework nothing auto-releases a hold (the owner's choice): a hit, a dig or a bow/eat use inside a hold is
 * held with everything else. The 1025-blink-hold-hit-1 / -hit-2 / -dig / -place / -bow cases therefore run with
 * {@link Scenario#runExpectingFlags} and record exactly what GrimAC says. Cheat build only.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class BlinkTests implements FabricClientGameTest {

    private static final String CFG = "blink.BlinkConfig";
    private static final String MODE = "blink.BlinkConfig$Mode";
    private static final String FEATURE = "blink.BlinkFeature";
    private static final String CHARGE = "blink.BlinkCharge";
    private static final int SURFACE_Y = 151;
    private static final Pattern POS = Pattern.compile("has the following entity data: \\[(-?[0-9.E-]+)d, (-?[0-9.E-]+)d, (-?[0-9.E-]+)d\\]");

    /** The hold sweep lengths, from testkit.blinkSweep (comma list) or this default; Hold Ticks stops at 20. */
    private static int[] sweepLengths() {
        String p = System.getProperty("testkit.blinkSweep");
        String s = p == null || p.isBlank() ? "5,10,15,18,19,20" : p;
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
                BlinkTests::latency, BlinkTests::pulseMax, BlinkTests::holdHits, BlinkTests::holdDig, BlinkTests::place,
                BlinkTests::teleport, BlinkTests::refused, BlinkTests::leap, BlinkTests::knockback, BlinkTests::etherwarp,
                BlinkTests::charge, BlinkTests::chargeActions, BlinkTests::chargeWarp, BlinkTests::chargeRun,
                BlinkTests::chargeSweep, BlinkTests::chargeArm, BlinkTests::chargeHoldMode, BlinkTests::chargeWait,
                BlinkTests::chargeInPlace, BlinkTests::chargeLanding, BlinkTests::hud,
                BlinkTests::holdRapid, BlinkTests::chargeRapid, BlinkTests::chargeArmedMenu,
                BlinkTests::clicks, BlinkTests::chargeFar, BlinkTests::chargeLong, BlinkTests::shotLever,
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

    /** Blink ON, Charge in Toggle at 20, the given Hold Ticks; Skyblock Only off for the run. Returns what to restore. */
    private static boolean setUp(ClientGameTestContext ctx, int holdTicks) {
        return ctx.computeOnClient(mc -> {
            Mod.staticCall(FEATURE, "end", "test setup");
            Mod.staticCall(CHARGE, "reset", "test setup");
            boolean gate = (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled");
            Mod.staticCall("util.SkyblockGate", "setEnabled", false);
            Mod.setField(FEATURE, "keyHeldForTest", Boolean.FALSE);
            Mod.setField(CHARGE, "keyHeldForTest", Boolean.FALSE);
            Mod.set(CFG, "setEnabled", true);
            Mod.set(CFG, "setChargeMode", Mod.enumValue(MODE, "TOGGLE"));
            Mod.set(CFG, "setChargeTicks", 20);
            Mod.set(CFG, "setHoldTicks", holdTicks);
            Mod.set(CFG, "setChatMessages", false);
            Mod.set(CFG, "setShowHud", true);
            Mod.set(CFG, "setShowServerBox", true);
            return gate;
        });
    }

    private static void tearDown(ClientGameTestContext ctx, boolean gate) {
        Pulser.on = false;
        ctx.runOnClient(mc -> {
            Mod.staticCall(FEATURE, "end", "test teardown");
            Mod.staticCall(CHARGE, "reset", "test teardown");
            Mod.setField(FEATURE, "keyHeldForTest", null);
            Mod.setField(CHARGE, "keyHeldForTest", null);
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

    /** The Hold key down (a hold starts at the next tick and lasts while it is down, up to Hold Ticks). */
    private static void holdDown(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> Mod.setField(FEATURE, "keyHeldForTest", Boolean.TRUE));
    }

    /** The Hold key up: the hold is released at the next tick. */
    private static void holdUp(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> Mod.setField(FEATURE, "keyHeldForTest", Boolean.FALSE));
    }

    /** The Hold key down, kept down until the hold's own limit releases it (or {@code maxTicks}), then up. */
    private static void holdToLimit(ClientGameTestContext ctx, int maxTicks) {
        holdDown(ctx);
        ctx.waitTicks(2);
        for (int i = 0; i < maxTicks && holding(ctx); i++) {
            ctx.waitTicks(1);
        }
        holdUp(ctx);
    }

    /**
     * One press of the Charge key (Toggle: on, fire, or off), and one tick with it up, so a press straight after it is a
     * new press (without that tick the Blink tick never saw the key come up and two presses read as one).
     */
    private static void pressCharge(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> Mod.setField(CHARGE, "keyHeldForTest", Boolean.TRUE));
        ctx.waitTicks(1);
        ctx.runOnClient(mc -> Mod.setField(CHARGE, "keyHeldForTest", Boolean.FALSE));
        ctx.waitTicks(1);
    }

    /**
     * Stands in for a player who keeps the Hold key down and lets it up whenever the Hold Ticks limit released it: back to
     * back holds, the old Pulse setting done by the hand on the key. Runs from the client's own START tick (after the mod's).
     */
    static final class Pulser {
        static volatile boolean on;
        private static boolean registered;

        static synchronized void start(ClientGameTestContext ctx) {
            if (!registered) {
                registered = true;
                ClientTickEvents.START_CLIENT_TICK.register(mc -> {
                    if (!on) {
                        return;
                    }
                    try {
                        Object k = Mod.field(FEATURE, "keyHeldForTest");
                        boolean down = Boolean.TRUE.equals(k);
                        boolean held = (Boolean) Mod.staticCall(FEATURE, "isHolding");
                        if (down && !held) {
                            Mod.setField(FEATURE, "keyHeldForTest", Boolean.FALSE);
                        } else if (!down) {
                            Mod.setField(FEATURE, "keyHeldForTest", Boolean.TRUE);
                        }
                    } catch (RuntimeException | AssertionError e) {
                        on = false;
                    }
                });
            }
            ctx.runOnClient(mc -> Mod.setField(FEATURE, "keyHeldForTest", Boolean.TRUE));
            on = true;
        }

        static void stop(ClientGameTestContext ctx) {
            on = false;
            ctx.runOnClient(mc -> Mod.setField(FEATURE, "keyHeldForTest", Boolean.FALSE));
        }
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

    /**
     * 1025-blink-setting: OFF and unbound by default, Charge Toggle 20 / Hold 20; every setting saved and loaded from disk;
     * both sliders clamp to 1-20; an old one-key file migrates its key to the section its Style was; with Blink OFF neither
     * key does anything.
     */
    private static void setting(ClientGameTestContext ctx) {
        Scenario.run(ctx, "1025-blink-setting", (server, scenario) -> arena(server, -3000, -3000), (server, scenario) -> {
            String[] got = ctx.computeOnClient(mc -> {
                java.nio.file.Path path = (java.nio.file.Path) Mod.field(CFG, "CONFIG_PATH");
                try {
                    java.nio.file.Files.deleteIfExists(path);
                } catch (java.io.IOException e) {
                    throw new AssertionError(e);
                }
                Mod.staticCall(CFG, "load");
                String defaults = describe(Mod.cfg(CFG));
                Mod.set(CFG, "setEnabled", true);
                Mod.set(CFG, "setChargeMode", Mod.enumValue(MODE, "HOLD"));
                Mod.set(CFG, "setChargeTicks", 33);
                Mod.set(CFG, "setChargeKeyCode", 66);
                Mod.set(CFG, "setHoldKeyCode", 67);
                Mod.set(CFG, "setHoldTicks", 0);
                Mod.set(CFG, "setShowHud", false);
                Mod.set(CFG, "setShowServerBox", false);
                Mod.set(CFG, "setChatMessages", true);
                Mod.call(Mod.cfg(CFG), "save");
                Mod.set(CFG, "setEnabled", false);
                Mod.set(CFG, "setHoldTicks", 5);
                Mod.staticCall(CFG, "load");
                String loaded = describe(Mod.cfg(CFG));
                // The one-key file from before the rework, both Styles.
                String migratedCharge;
                String migratedHold;
                try {
                    java.nio.file.Files.writeString(path, "{\"enabled\":true,\"keyCode\":70,\"style\":\"CHARGE\","
                            + "\"mode\":\"HOLD\",\"maxTicks\":22,\"limitAction\":\"PULSE\",\"showHud\":true}");
                    Mod.staticCall(CFG, "load");
                    migratedCharge = describe(Mod.cfg(CFG));
                    java.nio.file.Files.writeString(path, "{\"enabled\":false,\"keyCode\":71,\"style\":\"HOLD_PACKETS\","
                            + "\"mode\":\"TOGGLE\",\"maxTicks\":12}");
                    Mod.staticCall(CFG, "load");
                    migratedHold = describe(Mod.cfg(CFG));
                    java.nio.file.Files.deleteIfExists(path);
                } catch (java.io.IOException e) {
                    throw new AssertionError(e);
                }
                Mod.staticCall(CFG, "load");
                return new String[]{defaults, loaded, migratedCharge, migratedHold};
            });
            // OFF: neither key does anything.
            ctx.runOnClient(mc -> {
                Mod.setField(FEATURE, "keyHeldForTest", Boolean.TRUE);
                Mod.setField(CHARGE, "keyHeldForTest", Boolean.TRUE);
            });
            ctx.waitTicks(4);
            boolean heldWhileOff = holding(ctx);
            boolean chargingWhileOff = ctx.computeOnClient(mc -> (Boolean) Mod.staticCall(CHARGE, "isActive"));
            ctx.runOnClient(mc -> {
                Mod.setField(FEATURE, "keyHeldForTest", null);
                Mod.setField(CHARGE, "keyHeldForTest", null);
            });
            scenario.log("defaults [" + got[0] + "]; loaded [" + got[1] + "]; old Charge file [" + got[2] + "]; old Hold file ["
                    + got[3] + "]; held while off " + heldWhileOff + ", charging while off " + chargingWhileOff);
            check(got[0].equals("false -1 TOGGLE 20 -1 20 true true false"), "defaults wrong: " + got[0]);
            check(got[1].equals("true 66 HOLD 20 67 1 false false true"), "settings did not come back from disk (or the "
                    + "sliders did not clamp to 1-20): " + got[1]);
            check(got[2].equals("true 70 HOLD 20 -1 20 true true false"), "an old Charge file did not migrate: " + got[2]);
            check(got[3].equals("false -1 TOGGLE 12 71 12 true true false"), "an old Hold file did not migrate: " + got[3]);
            check(!heldWhileOff && !chargingWhileOff, "Blink acted while switched off");
        });
    }

    private static String describe(Object c) {
        return Mod.call(c, "isEnabled") + " " + Mod.call(c, "getChargeKeyCode") + " " + Mod.call(c, "getChargeMode") + " "
                + Mod.call(c, "getChargeTicks") + " " + Mod.call(c, "getHoldKeyCode") + " " + Mod.call(c, "getHoldTicks") + " "
                + Mod.call(c, "isShowHud") + " " + Mod.call(c, "isShowServerBox") + " " + Mod.call(c, "isChatMessages");
    }

    /**
     * 1025-blink-still (the Hold key, standing still): key down = a hold; at Hold Ticks (17) it is released and does NOT
     * start again while the key stays down; key up then down starts a new one; key up releases it at once. Grim silent.
     */
    private static void still(ClientGameTestContext ctx) {
        Scenario.run(ctx, "1025-blink-still", (server, scenario) -> arena(server, -3100, -3000), (server, scenario) -> {
            boolean gate = setUp(ctx, 17);
            try {
                long holdsBefore = num(ctx, "releasedHolds");
                holdDown(ctx);
                ctx.waitTicks(8);
                boolean mid = holding(ctx);
                long midPackets = num(ctx, "heldPackets");
                ctx.waitTicks(20);
                boolean afterLimit = holding(ctx);
                long holds1 = num(ctx, "releasedHolds") - holdsBefore;
                long ticks1 = num(ctx, "lastHoldTicks");
                long packets1 = num(ctx, "lastHoldPackets");
                // Key still down for another 15 ticks: nothing starts.
                boolean restarted = false;
                for (int i = 0; i < 15; i++) {
                    ctx.waitTicks(1);
                    restarted |= holding(ctx);
                }
                long holds2 = num(ctx, "releasedHolds") - holdsBefore;
                // Key up, then down: a new hold; up after 6 ticks releases it then.
                holdUp(ctx);
                ctx.waitTicks(2);
                holdDown(ctx);
                ctx.waitTicks(3);
                boolean second = holding(ctx);
                ctx.waitTicks(3);
                holdUp(ctx);
                ctx.waitTicks(2);
                boolean afterUp = holding(ctx);
                long holds3 = num(ctx, "releasedHolds") - holdsBefore;
                long ticks3 = num(ctx, "lastHoldTicks");
                scenario.log("mid-hold " + mid + " (" + midPackets + " held); at the limit: holding " + afterLimit + ", " + holds1
                        + " release(s), " + ticks1 + " tick(s) / " + packets1 + " packet(s); key kept down 15 more ticks: "
                        + "restarted " + restarted + "; key up+down: holding " + second + "; key up: holding " + afterUp
                        + ", releases " + holds3 + ", last " + ticks3 + " tick(s)");
                check(mid && midPackets >= 8, "no hold was running 8 ticks after the key went down");
                check(!afterLimit && holds1 == 1 && ticks1 == 17, "the Hold Ticks limit did not release a 17-tick hold once");
                check(packets1 >= 17, "fewer packets than ticks were released: " + packets1);
                check(!restarted && holds2 == 1, "a hold started again while the key stayed down after the limit");
                check(second, "key up then down did not start a new hold");
                check(!afterUp && holds3 == 2 && ticks3 >= 3 && ticks3 <= 8, "key up did not release the second hold at "
                        + "once (" + ticks3 + " tick(s))");
            } finally {
                tearDown(ctx, gate);
            }
            ctx.waitTicks(40);
        });
    }

    /**
     * One walking hold of {@code ticks} (the key held until Hold Ticks releases it), returning {server moved during
     * hold, client moved during hold, gap after release, setbacks, still holding, last hold ticks}. Walks +x.
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
        holdDown(ctx);
        ctx.waitTicks(1);
        double[] clientStart = scenario.playerPosition();
        // Read after the hold began: from here the server must hear nothing until the release.
        double[] serverStart = serverPos(server, ctx, name);
        int midWait = Math.max(2, ticks / 2 - 4);
        ctx.waitTicks(midWait);
        double[] serverMid = holding(ctx) ? serverPos(server, ctx, name) : null;
        for (int i = 0; i < ticks + 10 && holding(ctx); i++) {
            ctx.waitTicks(1);
        }
        boolean stillHolding = holding(ctx);
        double[] clientAtRelease = scenario.playerPosition();
        holdUp(ctx);
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

    /** 1025-blink-walk: sprinting through a 20-tick hold (the most Hold Ticks allows) - the server stands still, then catches up. */
    private static void walk(ClientGameTestContext ctx) {
        Scenario.run(ctx, "1025-blink-walk", (server, scenario) -> arena(server, -3200, -3000), (server, scenario) -> {
            boolean gate = setUp(ctx, 20);
            try {
                double[] r = walkHold(server, scenario, ctx, 20, false);
                check(r[1] >= 3.0, "the client did not walk during the hold (" + r[1] + " blocks): nothing was tested");
                check(r[0] >= 0 && r[0] < 0.2, "the server saw him move during the hold (" + r[0] + "): nothing was held");
                check(r[2] < 0.5, "the server did not catch up after the release (gap " + r[2] + ")");
                check(r[3] == 0, "the server set him back " + (int) r[3] + " time(s)");
                check(r[5] == 20, "the hold was not 20 ticks: " + (int) r[5]);
            } finally {
                tearDown(ctx, gate);
            }
            ctx.waitTicks(40);
        });
    }

    /** 1025-blink-jump: sprint-jumping through a 20-tick hold. */
    private static void sprintJump(ClientGameTestContext ctx) {
        Scenario.run(ctx, "1025-blink-jump", (server, scenario) -> arena(server, -3300, -3000), (server, scenario) -> {
            boolean gate = setUp(ctx, 20);
            try {
                double[] r = walkHold(server, scenario, ctx, 20, true);
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

    /** 1025-blink-pulse: Hold Ticks 10, the key let up and pressed again at each limit, while sprinting 160 ticks. */
    private static void pulse(ClientGameTestContext ctx) {
        Scenario.run(ctx, "1025-blink-pulse", (server, scenario) -> arena(server, -3400, -3000), (server, scenario) -> {
            boolean gate = setUp(ctx, 10);
            try {
                long before = num(ctx, "releasedHolds");
                double[] start = scenario.playerPosition();
                ctx.getInput().holdKey(o -> o.keyUp);
                ctx.getInput().holdKey(o -> o.keySprint);
                ctx.runOnClient(mc -> PacketTrace.start());
                Pulser.start(ctx);
                ctx.waitTicks(160);
                Pulser.stop(ctx);
                releaseKeys(ctx);
                ctx.waitTicks(20);
                ctx.runOnClient(mc -> PacketTrace.stop());
                long holds = num(ctx, "releasedHolds") - before;
                double moved = flat(start, scenario.playerPosition());
                int setbacks = PacketTrace.received("player_position");
                scenario.log(String.format(Locale.ROOT, "pulse: %d release(s) over 160 ticks, moved %.1f blocks, %d setback(s)",
                        holds, moved, setbacks));
                check(moved > 20, "he did not sprint (" + moved + " blocks)");
                check(holds >= 11, "the holds did not keep coming: " + holds + " release(s)");
                check(setbacks == 0, "the server set him back " + setbacks + " time(s)");
            } finally {
                tearDown(ctx, gate);
            }
            ctx.waitTicks(40);
        });
    }

    /** 1025-blink-latency: the 20-tick walking hold with a 100 ms round trip. */
    private static void latency(ClientGameTestContext ctx) {
        Scenario.run(ctx, "1025-blink-latency", (server, scenario) -> arena(server, -3500, -3000), (server, scenario) -> {
            boolean gate = setUp(ctx, 20);
            try {
                ctx.runOnClient(mc -> Latency.install(100));
                ctx.waitTicks(40);
                double[] r = walkHold(server, scenario, ctx, 20, false);
                check(r[1] >= 3.0, "the client did not walk during the hold (" + r[1] + " blocks): nothing was tested");
                // 100 ms each way: the moves sent just before the hold are still arriving when the server is first read.
                check(r[0] >= 0 && r[0] < 0.8, "the server saw him move during the hold (" + r[0] + ")");
                check(r[3] == 0, "the server set him back " + (int) r[3] + " time(s)");
            } finally {
                ctx.runOnClient(mc -> Latency.remove());
                tearDown(ctx, gate);
            }
            ctx.waitTicks(40);
        });
    }

    /** 1025-blink-sweep-walk: sprinting holds of each length up to 20; per length, what Grim said. */
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
     * 1025-blink-pulse-max: back-to-back holds at the default Hold Ticks (20) while sprinting about 400 ticks - the key let
     * up and down again at each limit. Grim must stay silent.
     */
    private static void pulseMax(ClientGameTestContext ctx) {
        Scenario.run(ctx, "1025-blink-pulse-max", (server, scenario) -> arena(server, -3900, -3000), (server, scenario) -> {
            int len = Integer.getInteger("testkit.blinkPulse",
                    ctx.computeOnClient(mc -> ((Number) Mod.field(CFG, "DEFAULT_TICKS")).intValue()));
            boolean gate = setUp(ctx, len);
            try {
                long before = num(ctx, "releasedHolds");
                String name = playerName(ctx);
                double[] start = scenario.playerPosition();
                ctx.getInput().holdKey(o -> o.keyUp);
                ctx.getInput().holdKey(o -> o.keySprint);
                ctx.runOnClient(mc -> PacketTrace.start());
                Pulser.start(ctx);
                double maxGap = 0;
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
                Pulser.stop(ctx);
                releaseKeys(ctx);
                ctx.waitTicks(20);
                ctx.runOnClient(mc -> PacketTrace.stop());
                long holds = num(ctx, "releasedHolds") - before;
                double moved = flat(start, scenario.playerPosition());
                int setbacks = PacketTrace.received("player_position");
                scenario.log(String.format(Locale.ROOT, "pulse-max at %d: %d release(s) over ~400 ticks, largest server/client gap "
                        + "seen %.2f blocks, ended %.1f from the start, %d setback(s)", len, holds, maxGap, moved, setbacks));
                check(len == 20, "the default Hold Ticks is not 20: " + len);
                check(holds >= 400 / (len + 3) - 1, "the holds did not keep coming: " + holds + " release(s)");
                check(maxGap > 1.0, "the server never lagged behind the client: nothing was held");
                check(setbacks == 0, "the server set him back " + setbacks + " time(s)");
            } finally {
                tearDown(ctx, gate);
            }
            ctx.waitTicks(40);
        });
    }

    // ---- what a hold does to hits and digs (the owner's choice since 2026-10-25: nothing releases a hold early) ----

    /** 1025-blink-hold-hit-1 and -hit-2: one and two full-strength sword hits inside one 20-tick hold; what Grim says. */
    private static void holdHits(ClientGameTestContext ctx) {
        AssertionError first = null;
        for (int hits = 1; hits <= 2; hits++) {
            try {
                holdHitCase(ctx, "1025-blink-hold-hit-" + hits, hits, -4000 - 40 * hits);
            } catch (AssertionError e) {
                first = first == null ? e : first;
            }
        }
        if (first != null) {
            throw first;
        }
    }

    private static void holdHitCase(ClientGameTestContext ctx, String caseName, int wantHits, int ax) {
        Scenario.runExpectingFlags(ctx, caseName, (server, scenario) -> {
            arena(server, ax, -3000);
            server.command("give @p minecraft:diamond_sword");
        }, (server, scenario) -> {
            boolean gate = setUp(ctx, 20);
            TestEnemy dummy = null;
            try {
                dummy = TestEnemy.named("Blink", "Dummy" + wantHits)
                        .at(ax + 2.5, SURFACE_Y, -3000 + 0.5)
                        .health(20)
                        .frozen(true)
                        .spawn(ctx, server);
                check(dummy.visibleToClient(), "the dummy never appeared to the client");
                ctx.waitTicks(30);
                float hp0 = dummy.health();
                server.mark();
                long holds0 = num(ctx, "releasedHolds");
                holdDown(ctx);
                ctx.waitTicks(2);
                int swings = 0;
                for (int t = 0; t < 17 && swings < wantHits; t++) {
                    if (ctx.computeOnClient(mc -> mc.player.getAttackStrengthScale(0f) >= 1.0f
                            && mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult)) {
                        ctx.getInput().pressKey(o -> o.keyAttack);
                        swings++;
                    }
                    ctx.waitTick();
                }
                boolean heldThrough = holding(ctx);
                for (int i = 0; i < 25 && holding(ctx); i++) {
                    ctx.waitTicks(1);
                }
                holdUp(ctx);
                ctx.waitTicks(40);
                float hp1 = dummy.health();
                long holds = num(ctx, "releasedHolds") - holds0;
                long ticks = num(ctx, "lastHoldTicks");
                List<String> flags = scenario.flags();
                scenario.log(String.format(Locale.ROOT, "%s: %d swing(s) inside one hold (still holding after them %s), %d "
                                + "release(s), last hold %d tick(s); dummy %.1f -> %.1f HP; GrimAC %s", caseName, swings,
                        heldThrough, holds, ticks, hp0, hp1, flags.isEmpty() ? "clean" : flags.size() + " flag(s): "
                                + firstChecks(flags)));
                for (String f : flags) {
                    scenario.log("  FLAG " + f);
                }
                check(swings == wantHits, "only " + swings + " of " + wantHits + " swing(s) happened inside the hold");
                check(heldThrough, "a hit released the hold (nothing may auto-release since the rework)");
                check(holds == 1 && ticks == 20, "the hits were not inside one 20-tick hold");
                check(hp1 < hp0, "no hit reached the server (dummy " + hp0 + " -> " + hp1 + "): nothing was tested");
            } finally {
                if (dummy != null) {
                    dummy.remove();
                }
                tearDown(ctx, gate);
            }
            ctx.waitTicks(60);
            server.mark();
        });
    }

    /** 1025-blink-hold-dig: one stone block mined by hand (start to finish) inside one 20-tick hold; what Grim says. */
    private static void holdDig(ClientGameTestContext ctx) {
        int x = -4100;
        int z = -3000;
        Scenario.runExpectingFlags(ctx, "1025-blink-hold-dig", (server, scenario) -> {
            TestMap.on(server)
                    .platform(x, SURFACE_Y - 1, z, 12)
                    .catchFloor(120)
                    .survival()
                    .clearInventory()
                    .give("diamond_pickaxe", 1)
                    .command("setblock " + (x + 2) + " " + (SURFACE_Y + 1) + " " + z + " minecraft:stone")
                    .spawn(x + 0.5, z + 0.5, -90f, 10f)
                    .build();
        }, (server, scenario) -> {
            boolean gate = setUp(ctx, 20);
            net.minecraft.core.BlockPos block = new net.minecraft.core.BlockPos(x + 2, SURFACE_Y + 1, z);
            try {
                ctx.waitTicks(20);
                boolean aimed = ctx.computeOnClient(mc -> mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult b
                        && b.getBlockPos().equals(block));
                server.mark();
                long holds0 = num(ctx, "releasedHolds");
                holdDown(ctx);
                ctx.waitTicks(2);
                ctx.getInput().holdKey(o -> o.keyAttack);
                int mined = -1;
                for (int t = 0; t < 16; t++) {
                    ctx.waitTicks(1);
                    if (mined < 0 && ctx.computeOnClient(mc -> mc.level.getBlockState(block).isAir())) {
                        mined = t + 1;
                        ctx.getInput().releaseKey(o -> o.keyAttack);
                    }
                }
                ctx.getInput().releaseKey(o -> o.keyAttack);
                boolean heldThrough = holding(ctx);
                for (int i = 0; i < 25 && holding(ctx); i++) {
                    ctx.waitTicks(1);
                }
                holdUp(ctx);
                ctx.waitTicks(40);
                server.command("execute if block " + (x + 2) + " " + (SURFACE_Y + 1) + " " + z + " minecraft:air run say DUG_OK");
                ctx.waitTicks(10);
                boolean dug = server.tail(60).contains("DUG_OK");
                long holds = num(ctx, "releasedHolds") - holds0;
                long ticks = num(ctx, "lastHoldTicks");
                List<String> flags = new ArrayList<>();
                for (String f : scenario.flags()) {
                    flags.add(f);
                }
                scenario.log(String.format(Locale.ROOT, "hold-dig: aimed %s; mined on the client after %d tick(s) of digging, "
                                + "inside the hold %s; %d release(s), last hold %d tick(s); gone on the server %s; GrimAC %s",
                        aimed, mined, heldThrough, holds, ticks, dug, flags.isEmpty() ? "clean" : flags.size() + " flag(s): "
                                + firstChecks(flags)));
                for (String f : flags) {
                    scenario.log("  FLAG " + f);
                }
                check(aimed, "he was not looking at the block: nothing was tested");
                check(mined > 0, "the block was never mined on the client");
                check(heldThrough && holds == 1 && ticks == 20, "the dig was not inside one 20-tick hold");
            } finally {
                ctx.getInput().releaseKey(o -> o.keyAttack);
                tearDown(ctx, gate);
            }
            ctx.waitTicks(60);
            server.mark();
        });
    }

    /** 1025-blink-place: placing cobblestone under him (towering) for 120 ticks with back-to-back holds; what Grim says. */
    private static void place(ClientGameTestContext ctx) {
        int x = -4200;
        int z = -3000;
        Scenario.runExpectingFlags(ctx, "1025-blink-place", (server, scenario) -> {
            TestMap.on(server)
                    .platform(x, SURFACE_Y - 1, z, 12)
                    .catchFloor(120)
                    .survival()
                    .clearInventory()
                    .give("cobblestone", 64)
                    .spawn(x + 0.5, z + 0.5, -90f, 90f)
                    .build();
        }, (server, scenario) -> {
            boolean gate = setUp(ctx, 20);
            try {
                server.mark();
                int before = ctx.computeOnClient(mc -> mc.player.getInventory().countItem(net.minecraft.world.item.Items.COBBLESTONE));
                long holds0 = num(ctx, "releasedHolds");
                double y0 = scenario.playerPosition()[1];
                Pulser.start(ctx);
                ctx.getInput().holdKey(o -> o.keyUse);
                ctx.getInput().holdKey(o -> o.keyJump);
                ctx.waitTicks(120);
                ctx.getInput().releaseKey(o -> o.keyJump);
                ctx.getInput().releaseKey(o -> o.keyUse);
                Pulser.stop(ctx);
                ctx.waitTicks(40);
                int after = ctx.computeOnClient(mc -> mc.player.getInventory().countItem(net.minecraft.world.item.Items.COBBLESTONE));
                long holds = num(ctx, "releasedHolds") - holds0;
                double rose = scenario.playerPosition()[1] - y0;
                List<String> flags = scenario.flags();
                scenario.log(String.format(Locale.ROOT, "place: cobblestone %d -> %d, towered %.1f blocks, %d release(s); GrimAC %s",
                        before, after, rose, holds, flags.isEmpty() ? "clean" : flags.size() + " flag(s): " + firstChecks(flags)));
                for (String f : flags) {
                    scenario.log("  FLAG " + f);
                }
                check(before > 0 && after <= before - 3, "fewer than three blocks were placed: placing was hardly tested");
                check(holds >= 3, "the holds did not keep coming while placing: " + holds);
            } finally {
                ctx.getInput().releaseKey(o -> o.keyUse);
                ctx.getInput().releaseKey(o -> o.keyJump);
                tearDown(ctx, gate);
            }
            ctx.waitTicks(60);
            server.mark();
        });
    }

    /**
     * 1025-blink-teleport: a server teleport in the middle of a sprinting hold. Blink keeps holding (the accept goes into
     * the queue in order), the client is where the server put him, and once the key comes up client and server agree.
     */
    private static void teleport(ClientGameTestContext ctx) {
        int x = -4400;
        int z = -3000;
        Scenario.run(ctx, "1025-blink-teleport", (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = setUp(ctx, 20);
            try {
                String name = playerName(ctx);
                ctx.getInput().holdKey(o -> o.keyUp);
                ctx.getInput().holdKey(o -> o.keySprint);
                ctx.waitTicks(5);
                long holds0 = num(ctx, "releasedHolds");
                long tp0 = num(ctx, "teleportsHeld");
                holdDown(ctx);
                ctx.waitTicks(6);
                boolean heldBefore = holding(ctx);
                releaseKeys(ctx);
                server.command("tp " + name + " " + (x + 0.5) + " " + SURFACE_Y + " " + (z + 20.5));
                boolean kept = false;
                for (int i = 0; i < 10 && !kept; i++) {
                    ctx.waitTicks(1);
                    kept = num(ctx, "teleportsHeld") > tp0;
                }
                boolean stillHolding = holding(ctx);
                boolean releasedByTp = num(ctx, "releasedHolds") > holds0;
                double[] clientHeld = scenario.playerPosition();
                holdUp(ctx);
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
            boolean gate = setUp(ctx, 20);
            try {
                String name = playerName(ctx);
                ctx.waitTicks(10);
                double[] start = serverPos(server, ctx, name);
                long ref0 = num(ctx, "refused");
                long holds0 = num(ctx, "releasedHolds");
                holdDown(ctx);
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
                holdUp(ctx);
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
     * 1025-blink-leap: back-to-back 20-tick holds while sprinting, the server moves him three times (a leap is a server
     * teleport). Each one is held through, the holds carry on, client and server agree at the end.
     */
    private static void leap(ClientGameTestContext ctx) {
        int x = -4400;
        int z = -3400;
        Scenario.run(ctx, "1025-blink-leap", (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = setUp(ctx, 20);
            try {
                String name = playerName(ctx);
                long tp0 = num(ctx, "teleportsHeld");
                long holds0 = num(ctx, "releasedHolds");
                long ref0 = num(ctx, "refused");
                ctx.runOnClient(mc -> PacketTrace.start());
                Pulser.start(ctx);
                int[][] spots = {{-15, -15}, {15, -15}, {0, 15}};
                for (int[] spot : spots) {
                    ctx.getInput().holdKey(o -> o.keyUp);
                    ctx.getInput().holdKey(o -> o.keySprint);
                    ctx.waitTicks(10);
                    server.command("tp " + name + " " + (x + spot[0] + 0.5) + " " + SURFACE_Y + " " + (z + spot[1] + 0.5));
                    ctx.waitTicks(20);
                }
                releaseKeys(ctx);
                ctx.waitTicks(10);
                Pulser.stop(ctx);
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
                check(holds >= 4, "the holds did not keep coming: " + holds + " release(s)");
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
     * 1025-blink-knockback: back-to-back 20-tick holds standing in front of a bot that hits him once a second. The
     * knockback must reach him (he is moved), the holds keep coming, and nothing sets him back.
     */
    private static void knockback(ClientGameTestContext ctx) {
        int x = -4400;
        int z = -3600;
        Scenario.run(ctx, "1025-blink-knockback", (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = setUp(ctx, 20);
            TestEnemy attacker = null;
            try {
                double[] start = scenario.playerPosition();
                long holds0 = num(ctx, "releasedHolds");
                ctx.runOnClient(mc -> PacketTrace.start());
                Pulser.start(ctx);
                attacker = TestEnemy.named("Knockback", "Blink")
                        .at(x + 3.5, SURFACE_Y, z + 0.5)
                        .health(20)
                        .heldItem("minecraft:stick")
                        .attacking(4.0f, 20, 1.0f)
                        .chasing(true)
                        .spawn(ctx, server);
                attacker.drive(200);
                Pulser.stop(ctx);
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
                check(holds >= 6, "the holds did not keep coming under the hits: " + holds + " release(s)");
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
     * 1025-blink-etherwarp: back-to-back 20-tick holds, four real etherwarps (hx/dungeon/HxAbilities) 30 ticks apart. Each
     * warp's use is held and released with its hold, and the landing arrives while the next hold is running: Blink must
     * keep holding, the server must warp all four times, and client and server agree at the end.
     */
    private static void etherwarp(ClientGameTestContext ctx) {
        int x = -4400;
        int z = -3800;
        Scenario.run(ctx, "1025-blink-etherwarp", (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = setUp(ctx, 20);
            dev.testkit.gametest.hx.Hx hx = null;
            try {
                hx = dev.testkit.gametest.hx.Hx.connect();
                hx.call("dungeon.abilities", "enabled", true);
                hx.give(0, AOTV);
                ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
                // Hypixel-like latency: the landing of a warp released with one hold comes back during the next one (the
                // key needs a tick up and a tick down between holds, so with none it lands in that gap).
                ctx.runOnClient(mc -> Latency.install(150));
                ctx.waitTicks(30);
                com.google.gson.JsonObject before = hx.call("dungeon.stats").getAsJsonObject();
                long tp0 = num(ctx, "teleportsHeld");
                long ref0 = num(ctx, "refused");
                long holds0 = num(ctx, "releasedHolds");
                ctx.getInput().holdKey(o -> o.keyShift);
                ctx.waitTicks(5);
                Pulser.start(ctx);
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
                Pulser.stop(ctx);
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
                check(held >= 2, "Blink held through fewer than two of the landings: " + held);
                check(refusedN == 0, "a landing was taken for a refused blink");
                check(Math.abs(client[0] - (x + 24.5)) < 1.0 && Math.abs(client[2] - (z + 0.5)) < 1.0,
                        "he is not on the last warp's block: " + fmt(client));
                check(flat(client, srv) < 0.5, "client and server disagree at the end: " + fmt(client) + " vs " + fmt(srv));
            } finally {
                ctx.getInput().releaseKey(o -> o.keyShift);
                ctx.runOnClient(mc -> Latency.remove());
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
    // it"; "flick levers mine blocks"; "infinite"; the rework: banks Charge Ticks, fires only on the key, arms standing) ----

    /** Charge on (a Toggle press of the Charge key). */
    private static void chargeOn(ClientGameTestContext ctx) {
        pressCharge(ctx);
    }

    private static void chargeOff(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            Mod.setField(CHARGE, "keyHeldForTest", Boolean.FALSE);
            Mod.staticCall(CHARGE, "reset", "test");
        });
        ctx.waitTicks(2);
    }

    private static long cnum(ClientGameTestContext ctx, String getter) {
        return ctx.computeOnClient(mc -> ((Number) Mod.staticCall(CHARGE, getter)).longValue());
    }

    private static boolean charging(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> (Boolean) Mod.staticCall(CHARGE, "isActive"));
    }

    private static boolean armed(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> (Boolean) Mod.staticCall(CHARGE, "isArmed"));
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
     * Fires the bank the way he does: a movement key down and the Charge key pressed in the same tick (Toggle). Returns
     * {blocks moved over that one tick, shots made, ticks in the last shot}. The shot runs at the start of the tick.
     */
    private static double[] shootByPress(ClientGameTestContext ctx, boolean sprint) {
        long shots0 = cnum(ctx, "shots");
        double[] before = ctx.computeOnClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
        ctx.getInput().holdKey(o -> o.keyUp);
        if (sprint) {
            ctx.getInput().holdKey(o -> o.keySprint);
        }
        ctx.runOnClient(mc -> Mod.setField(CHARGE, "keyHeldForTest", Boolean.TRUE));
        ctx.waitTicks(1);
        double[] after = ctx.computeOnClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
        ctx.runOnClient(mc -> Mod.setField(CHARGE, "keyHeldForTest", Boolean.FALSE));
        ctx.getInput().releaseKey(o -> o.keyUp);
        ctx.getInput().releaseKey(o -> o.keySprint);
        long shots = cnum(ctx, "shots") - shots0;
        return new double[]{flat(before, after), shots, cnum(ctx, "lastShotTicks")};
    }

    /**
     * 1025-blink-charge: Charge Ticks 20. Stand still 40 ticks (the bank fills to exactly 20 and stops), then sprint and
     * press the Charge key: the 20 ticks run at once and he is about six blocks on in one tick, client and server agreeing.
     * Three times over - Toggle keeps charging after a shot, so it never runs out.
     */
    private static void charge(ClientGameTestContext ctx) {
        int x = -4600;
        int z = -3400;
        Scenario.run(ctx, "1025-blink-charge", (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = setUp(ctx, 20);
            try {
                String name = playerName(ctx);
                ctx.runOnClient(mc -> PacketTrace.start());
                chargeOn(ctx);
                StringBuilder rounds = new StringBuilder();
                int good = 0;
                for (int round = 0; round < 3; round++) {
                    ctx.waitTicks(40);
                    long bank = cnum(ctx, "banked");
                    double[] shot = shootByPress(ctx, true);
                    boolean still = charging(ctx);
                    ctx.waitTicks(10);
                    // Turn round so the next round stays on the platform.
                    ctx.runOnClient(mc -> mc.player.setYRot(mc.player.getYRot() + 180f));
                    double[] client = scenario.playerPosition();
                    double[] srv = serverPos(server, ctx, name);
                    rounds.append(String.format(Locale.ROOT, " [round %d: bank %d, shot %d tick(s) %.2f blocks in one tick, still "
                            + "charging %s; client %s server %s]", round + 1, bank, (long) shot[2], shot[0], still, fmt(client),
                            fmt(srv)));
                    if (bank == 20 && shot[1] == 1 && shot[2] == 20 && shot[0] > 4.5 && still && flat(client, srv) < 0.5) {
                        good++;
                    }
                }
                ctx.runOnClient(mc -> PacketTrace.stop());
                int setbacks = PacketTrace.received("player_position");
                scenario.log("charge:" + rounds + "; " + setbacks + " setback(s)");
                check(good == 3, good + " of 3 sprinting charge shots banked exactly 20 ticks and shot him forward with the "
                        + "server agreeing");
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
            boolean gate = setUp(ctx, 20);
            try {
                String name = playerName(ctx);
                ctx.runOnClient(mc -> PacketTrace.start());
                chargeOn(ctx);
                ctx.waitTicks(30);
                long bankBefore = cnum(ctx, "banked");
                look(ctx, x + 2.5, SURFACE_Y + 1.1, z + 2.5);
                ctx.waitTicks(2);
                ctx.getInput().pressKey(o -> o.keyUse);
                ctx.waitTicks(10);
                look(ctx, x + 2.5, SURFACE_Y + 1.5, z - 1.5);
                ctx.waitTicks(2);
                ctx.getInput().holdKey(o -> o.keyAttack);
                ctx.waitTicks(30);
                look(ctx, x + 2.5, SURFACE_Y + 0.5, z - 1.5);
                ctx.waitTicks(30);
                ctx.getInput().releaseKey(o -> o.keyAttack);
                ctx.runOnClient(mc -> {
                    mc.player.setYRot(mc.player.getYRot() + net.minecraft.util.Mth.wrapDegrees(-90f - mc.player.getYRot()));
                    mc.player.setXRot(0f);
                });
                ctx.waitTicks(30);
                long bank = cnum(ctx, "banked");
                double[] shot = shootByPress(ctx, false);
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
                check(shot[1] == 1 && shot[2] >= 19 && shot[0] > 3.0, "no full shot after the actions: " + (long) shot[2]
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
            boolean gate = setUp(ctx, 20);
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
                long moving0 = cnum(ctx, "movingTeleports");
                ctx.getInput().holdKey(o -> o.keyShift);
                ctx.waitTicks(3);
                look(ctx, x + 12.5, SURFACE_Y, z + 0.5);
                ctx.waitTicks(1);
                ctx.getInput().pressKey(o -> o.keyUse);
                ctx.waitTicks(10);
                ctx.getInput().releaseKey(o -> o.keyShift);
                ctx.waitTicks(30);
                server.command("tp " + name + " " + (x + 12.5) + " " + SURFACE_Y + " " + (z + 10.5));
                ctx.waitTicks(30);
                ctx.runOnClient(mc -> {
                    mc.player.setYRot(mc.player.getYRot() + net.minecraft.util.Mth.wrapDegrees(-90f - mc.player.getYRot()));
                    mc.player.setXRot(0f);
                });
                ctx.waitTicks(30);
                long bank2 = cnum(ctx, "banked");
                long moving = cnum(ctx, "movingTeleports") - moving0;
                double[] shot = shootByPress(ctx, false);
                ctx.waitTicks(20);
                ctx.runOnClient(mc -> PacketTrace.stop());
                com.google.gson.JsonObject after = hx.call("dungeon.stats").getAsJsonObject();
                int warps = after.get("etherwarps").getAsInt() - before.get("etherwarps").getAsInt();
                double[] client = scenario.playerPosition();
                double[] srv = serverPos(server, ctx, name);
                int positions = PacketTrace.received("player_position");
                scenario.log(String.format(Locale.ROOT, "charge-warp: bank %d before the warp, %d at the shot; %d server warp(s), "
                                + "%d position packet(s), %d moving teleport(s) counted; shot %d tick(s) %.2f blocks in one tick; "
                                + "client %s server %s", bank1, bank2, warps, positions, moving, (long) shot[2], shot[0], fmt(client),
                        fmt(srv)));
                check(bank1 >= 20, "the bank did not fill before the warp: " + bank1);
                check(warps == 1, "the server did not etherwarp: " + warps);
                check(positions == 2 && moving == 2, positions + " position packet(s), " + moving
                        + " moving teleport(s): not the warp and the leap");
                check(shot[1] == 1 && shot[2] >= 19 && shot[0] > 3.0, "no full shot after the warp and leap: " + (long) shot[2]
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
     * 1025-blink-charge-run (killer560, 2026-10-25: "make it charge while blinking and running"): charge standing still,
     * then sprint about for 20 ticks - the bank is kept - and press the Charge key mid-sprint: the bank fires at once,
     * charging goes on, and after standing again it is full and fires again. Grim silent.
     */
    private static void chargeRun(ClientGameTestContext ctx) {
        int x = -4800;
        int z = -3400;
        Scenario.run(ctx, "1025-blink-charge-run", (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = setUp(ctx, 20);
            try {
                String name = playerName(ctx);
                ctx.runOnClient(mc -> PacketTrace.start());
                chargeOn(ctx);
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
                    pressCharge(ctx);
                    double[] after = scenario.playerPosition();
                    long shots = cnum(ctx, "shots") - shots0;
                    long ticks = cnum(ctx, "lastShotTicks");
                    boolean stillCharging = charging(ctx);
                    ctx.waitTicks(5);
                    ctx.getInput().releaseKey(o -> o.keySprint);
                    ctx.getInput().releaseKey(o -> o.keyUp);
                    ctx.runOnClient(mc -> mc.player.setYRot(mc.player.getYRot() + 180f));
                    ctx.waitTicks(10);
                    double[] client = scenario.playerPosition();
                    double[] srv = serverPos(server, ctx, name);
                    double went = flat(before, after);
                    rounds.append(String.format(Locale.ROOT, " [round %d: bank %d standing, %d after 20 sprinting ticks; key press "
                                    + "shot %d tick(s), %.2f blocks over the press's tick, still charging %s; client %s server %s]",
                            round + 1, bankStill, bankRunning, ticks, went, stillCharging, fmt(client), fmt(srv)));
                    if (bankStill == 20 && bankRunning == 20 && shots == 1 && ticks == 20 && went > 5.5 && stillCharging
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
     * 1025-blink-charge-sweep: each Charge Ticks setting (testkit.chargeSweep, default 1,5,10,15,18,19,20): back to the
     * start, charge, sprint and fire, and record what Grim said. The bank must be exactly the setting (since the rework,
     * 20 banks 20) and every length up to 20 must be clean.
     */
    private static void chargeSweep(ClientGameTestContext ctx) {
        int x = -4800;
        int z = -3600;
        Scenario.runExpectingFlags(ctx, "1025-blink-charge-sweep", (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = setUp(ctx, 20);
            String p = System.getProperty("testkit.chargeSweep");
            String[] parts = (p == null || p.isBlank() ? "1,5,10,15,18,19,20" : p).split(",");
            List<String> rows = new ArrayList<>();
            List<Integer> bad = new ArrayList<>();
            String name = playerName(ctx);
            try {
                for (String part : parts) {
                    int len = Integer.parseInt(part.trim());
                    server.command("tp " + name + " " + (x - 20.5) + " " + SURFACE_Y + " " + (z + 0.5) + " -90 0");
                    ctx.waitTicks(30);
                    ctx.runOnClient(mc -> Mod.set(CFG, "setChargeTicks", len));
                    server.mark();
                    chargeOn(ctx);
                    ctx.waitTicks(len + 15);
                    long bank = cnum(ctx, "banked");
                    double[] shot = shootByPress(ctx, true);
                    ctx.getInput().holdKey(o -> o.keyUp);
                    ctx.waitTicks(10);
                    ctx.getInput().releaseKey(o -> o.keyUp);
                    chargeOff(ctx);
                    ctx.waitTicks(40);
                    List<String> flags = scenario.flags();
                    boolean ok = flags.isEmpty() && bank == len && (long) shot[2] == len;
                    String row = String.format(Locale.ROOT, "Charge Ticks %2d: banked %d, shot %d tick(s) %.2f blocks, %s", len,
                            bank, (long) shot[2], shot[0], flags.isEmpty() ? "clean" : flags.size() + " flag(s): " + firstChecks(flags));
                    rows.add(row);
                    scenario.log(row);
                    if (!ok) {
                        bad.add(len);
                        ctx.waitTicks(60);
                    }
                }
            } finally {
                chargeOff(ctx);
                tearDown(ctx, gate);
            }
            scenario.log("SWEEP 1025-blink-charge-sweep: lengths that were not clean or banked the wrong amount " + bad);
            for (String r : rows) {
                scenario.log("  " + r);
            }
            check(!rows.isEmpty(), "the sweep ran no lengths");
            check(bad.isEmpty(), "Charge Ticks " + bad + " flagged or banked the wrong amount");
            server.mark();
        });
    }

    /**
     * 1025-blink-charge-arm (killer560, 2026-10-25): a Toggle fire while standing still ARMS the shot - nothing moves, the
     * bank stays - and it fires the first tick a movement key is down, moving him the whole bank in that tick. A second
     * press while armed switches Charge off (nothing is fired). Grim silent.
     */
    private static void chargeArm(ClientGameTestContext ctx) {
        int x = -5000;
        int z = -3000;
        Scenario.run(ctx, "1025-blink-charge-arm", (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = setUp(ctx, 20);
            try {
                String name = playerName(ctx);
                ctx.runOnClient(mc -> PacketTrace.start());
                chargeOn(ctx);
                ctx.waitTicks(35);
                long bank = cnum(ctx, "banked");
                long shots0 = cnum(ctx, "shots");
                double[] p0 = scenario.playerPosition();
                pressCharge(ctx);
                boolean armedNow = armed(ctx);
                ctx.waitTicks(15);
                double[] p1 = scenario.playerPosition();
                boolean armedLater = armed(ctx);
                long bankArmed = cnum(ctx, "banked");
                long shotsArmed = cnum(ctx, "shots") - shots0;
                // A movement key: it fires that very tick.
                ctx.getInput().holdKey(o -> o.keyUp);
                ctx.getInput().holdKey(o -> o.keySprint);
                ctx.waitTicks(1);
                double[] p2 = scenario.playerPosition();
                ctx.getInput().releaseKey(o -> o.keyUp);
                ctx.getInput().releaseKey(o -> o.keySprint);
                long shots = cnum(ctx, "shots") - shots0;
                long ticks = cnum(ctx, "lastShotTicks");
                boolean armedAfter = armed(ctx);
                boolean stillCharging = charging(ctx);
                ctx.waitTicks(20);
                double[] client = scenario.playerPosition();
                double[] srv = serverPos(server, ctx, name);
                // Armed again, then a second press: Charge off, nothing fired.
                ctx.waitTicks(30);
                long shotsB = cnum(ctx, "shots");
                pressCharge(ctx);
                boolean armed2 = armed(ctx);
                pressCharge(ctx);
                boolean offAfterSecond = !charging(ctx);
                boolean firedB = cnum(ctx, "shots") != shotsB;
                ctx.runOnClient(mc -> PacketTrace.stop());
                int setbacks = PacketTrace.received("player_position");
                scenario.log(String.format(Locale.ROOT, "charge-arm: bank %d; press standing: armed %s, 15 ticks later armed %s, "
                                + "moved %.3f, bank %d, %d shot(s); forward: %d shot(s) of %d tick(s), %.2f blocks that tick, armed "
                                + "after %s, still charging %s; client %s server %s; armed again %s then a press: off %s, fired %s; "
                                + "%d setback(s)", bank, armedNow, armedLater, flat(p0, p1), bankArmed, shotsArmed, shots, ticks,
                        flat(p1, p2), armedAfter, stillCharging, fmt(client), fmt(srv), armed2, offAfterSecond, firedB, setbacks));
                check(bank == 20, "the bank did not fill to 20: " + bank);
                check(armedNow && armedLater && shotsArmed == 0 && flat(p0, p1) < 0.01 && bankArmed == 20,
                        "a standing press did not arm the shot and wait (nothing may move)");
                check(shots == 1 && ticks == 20 && flat(p1, p2) > 4.5, "the armed shot did not fire the tick he moved");
                check(!armedAfter && stillCharging, "after the armed shot it is still armed or Charge went off");
                check(flat(client, srv) < 0.5, "client and server disagree after the shot: " + fmt(client) + " vs " + fmt(srv));
                check(armed2 && offAfterSecond && !firedB, "a press while armed did not switch Charge off without firing");
                check(setbacks == 0, "the server set him back " + setbacks + " time(s)");
            } finally {
                ctx.getInput().releaseKey(o -> o.keyUp);
                ctx.getInput().releaseKey(o -> o.keySprint);
                chargeOff(ctx);
                tearDown(ctx, gate);
            }
            ctx.waitTicks(40);
        });
    }

    /**
     * 1025-blink-charge-holdmode: Charge in Hold mode. Key down = charging; letting go while sprinting fires the bank and
     * turns Charge off; letting go standing still arms it, the first movement fires it and turns Charge off. Grim silent.
     */
    private static void chargeHoldMode(ClientGameTestContext ctx) {
        int x = -5000;
        int z = -3200;
        Scenario.run(ctx, "1025-blink-charge-holdmode", (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = setUp(ctx, 20);
            try {
                String name = playerName(ctx);
                ctx.runOnClient(mc -> {
                    Mod.set(CFG, "setChargeMode", Mod.enumValue(MODE, "HOLD"));
                    PacketTrace.start();
                });
                // Round 1: let go while sprinting.
                ctx.runOnClient(mc -> Mod.setField(CHARGE, "keyHeldForTest", Boolean.TRUE));
                ctx.waitTicks(2);
                boolean on1 = charging(ctx);
                ctx.waitTicks(35);
                long bank1 = cnum(ctx, "banked");
                long shots0 = cnum(ctx, "shots");
                double[] a0 = scenario.playerPosition();
                ctx.getInput().holdKey(o -> o.keyUp);
                ctx.getInput().holdKey(o -> o.keySprint);
                ctx.runOnClient(mc -> Mod.setField(CHARGE, "keyHeldForTest", Boolean.FALSE));
                ctx.waitTicks(1);
                double[] a1 = scenario.playerPosition();
                ctx.getInput().releaseKey(o -> o.keyUp);
                ctx.getInput().releaseKey(o -> o.keySprint);
                long shots1 = cnum(ctx, "shots") - shots0;
                long ticks1 = cnum(ctx, "lastShotTicks");
                boolean off1 = !charging(ctx);
                ctx.waitTicks(15);
                ctx.runOnClient(mc -> mc.player.setYRot(mc.player.getYRot() + 180f));
                double[] c1 = scenario.playerPosition();
                double[] s1 = serverPos(server, ctx, name);
                // Round 2: let go standing still - armed, fires on the first move, then off.
                ctx.runOnClient(mc -> Mod.setField(CHARGE, "keyHeldForTest", Boolean.TRUE));
                ctx.waitTicks(35);
                long bank2 = cnum(ctx, "banked");
                ctx.runOnClient(mc -> Mod.setField(CHARGE, "keyHeldForTest", Boolean.FALSE));
                ctx.waitTicks(10);
                boolean armed2 = armed(ctx);
                boolean on2 = charging(ctx);
                long shots2a = cnum(ctx, "shots");
                double[] b0 = scenario.playerPosition();
                ctx.getInput().holdKey(o -> o.keyUp);
                ctx.getInput().holdKey(o -> o.keySprint);
                ctx.waitTicks(1);
                double[] b1 = scenario.playerPosition();
                ctx.getInput().releaseKey(o -> o.keyUp);
                ctx.getInput().releaseKey(o -> o.keySprint);
                long shots2 = cnum(ctx, "shots") - shots2a;
                long ticks2 = cnum(ctx, "lastShotTicks");
                boolean off2 = !charging(ctx);
                ctx.waitTicks(15);
                double[] c2 = scenario.playerPosition();
                double[] s2 = serverPos(server, ctx, name);
                ctx.runOnClient(mc -> PacketTrace.stop());
                int setbacks = PacketTrace.received("player_position");
                scenario.log(String.format(Locale.ROOT, "charge-holdmode: key down -> charging %s, bank %d; let go sprinting: %d "
                                + "shot(s) of %d tick(s), %.2f blocks that tick, Charge off %s; client %s server %s. Again: bank %d, "
                                + "let go standing: armed %s, charging %s; forward: %d shot(s) of %d tick(s), %.2f blocks, off %s; "
                                + "client %s server %s; %d setback(s)", on1, bank1, shots1, ticks1, flat(a0, a1), off1, fmt(c1),
                        fmt(s1), bank2, armed2, on2, shots2, ticks2, flat(b0, b1), off2, fmt(c2), fmt(s2), setbacks));
                check(on1 && bank1 == 20, "holding the key did not charge to 20");
                check(shots1 == 1 && ticks1 == 20 && flat(a0, a1) > 4.5 && off1, "letting go while sprinting did not fire "
                        + "the bank and switch Charge off");
                check(flat(c1, s1) < 0.5, "client and server disagree after the first shot");
                check(bank2 == 20 && armed2 && on2, "letting go standing still did not arm the shot");
                check(shots2 == 1 && ticks2 == 20 && flat(b0, b1) > 4.5 && off2, "the armed shot did not fire on the first "
                        + "move and switch Charge off");
                check(flat(c2, s2) < 0.5, "client and server disagree after the second shot");
                check(setbacks == 0, "the server set him back " + setbacks + " time(s)");
            } finally {
                ctx.getInput().releaseKey(o -> o.keyUp);
                ctx.getInput().releaseKey(o -> o.keySprint);
                chargeOff(ctx);
                tearDown(ctx, gate);
            }
            ctx.waitTicks(40);
        });
    }

    /**
     * 1025-blink-charge-wait: a full bank (20) stays full for 7 seconds standing, with no shot and no flag (read every
     * second), then the shot is still all 20 ticks and clean.
     */
    private static void chargeWait(ClientGameTestContext ctx) {
        int x = -5000;
        int z = -3400;
        Scenario.run(ctx, "1025-blink-charge-wait", (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = setUp(ctx, 20);
            try {
                String name = playerName(ctx);
                ctx.runOnClient(mc -> PacketTrace.start());
                chargeOn(ctx);
                ctx.waitTicks(30);
                long shots0 = cnum(ctx, "shots");
                StringBuilder banks = new StringBuilder();
                boolean full = true;
                for (int s = 0; s < 7; s++) {
                    ctx.waitTicks(20);
                    long b = cnum(ctx, "banked");
                    banks.append(b).append(s < 6 ? "," : "");
                    full &= b == 20;
                }
                long shotsWaiting = cnum(ctx, "shots") - shots0;
                List<String> flagsWaiting = scenario.flags();
                double[] shot = shootByPress(ctx, true);
                ctx.waitTicks(20);
                ctx.runOnClient(mc -> PacketTrace.stop());
                double[] client = scenario.playerPosition();
                double[] srv = serverPos(server, ctx, name);
                int setbacks = PacketTrace.received("player_position");
                scenario.log(String.format(Locale.ROOT, "charge-wait: bank each second for 7 s [%s], %d shot(s) and %d flag(s) "
                                + "meanwhile; then shot %d tick(s) %.2f blocks; client %s server %s; %d setback(s)", banks,
                        shotsWaiting, flagsWaiting.size(), (long) shot[2], shot[0], fmt(client), fmt(srv), setbacks));
                check(full, "the bank did not stay at 20 for 7 s: " + banks);
                check(shotsWaiting == 0, "something fired while he waited");
                check(flagsWaiting.isEmpty(), "Grim flagged while the full bank waited: " + flagsWaiting);
                check(shot[1] == 1 && shot[2] == 20 && shot[0] > 4.5, "no full shot after the wait");
                check(flat(client, srv) < 0.5, "client and server disagree after the shot: " + fmt(client) + " vs " + fmt(srv));
                check(setbacks == 0, "the server set him back " + setbacks + " time(s)");
            } finally {
                chargeOff(ctx);
                tearDown(ctx, gate);
            }
            ctx.waitTicks(40);
        });
    }

    /**
     * 1025-blink-charge-inplace (killer560, 2026-10-25: Hypixel re-syncs a charging player about once a second and each
     * re-sync emptied the bank): while charged the server teleports him to exactly where he stands - once by relative
     * {@code tp @s ~ ~ ~}, once to his absolute coordinates - and the bank must be kept; a shot 5 ticks after the second
     * one must be all 20 ticks and clean. Then a teleport that moves him 3 blocks must restart the bank (and the next full
     * shot is clean too). Grim silent throughout.
     */
    private static void chargeInPlace(ClientGameTestContext ctx) {
        int x = -5000;
        int z = -3600;
        Scenario.runExpectingFlags(ctx, "1025-blink-charge-inplace", (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = setUp(ctx, 20);
            try {
                String name = playerName(ctx);
                ctx.runOnClient(mc -> PacketTrace.start());
                chargeOn(ctx);
                ctx.waitTicks(35);
                server.mark();
                long bank0 = cnum(ctx, "banked");
                long in0 = cnum(ctx, "inPlaceTeleports");
                long mv0 = cnum(ctx, "movingTeleports");
                // Relative, in place.
                server.command("execute as " + name + " at @s run tp @s ~ ~ ~");
                ctx.waitTicks(10);
                long bankRel = cnum(ctx, "banked");
                long inRel = cnum(ctx, "inPlaceTeleports") - in0;
                // Absolute, to where the server has him.
                ctx.waitTicks(20);
                double[] sp = serverPos(server, ctx, name);
                server.command(String.format(Locale.ROOT, "tp %s %s %s %s", name, Double.toString(sp[0]), Double.toString(sp[1]),
                        Double.toString(sp[2])));
                ctx.waitTicks(5);
                long bankAbs = cnum(ctx, "banked");
                long inAbs = cnum(ctx, "inPlaceTeleports") - in0;
                long answersWaiting = cnum(ctx, "answersHeld");
                double[] shot = shootByPress(ctx, true);
                ctx.waitTicks(20);
                double[] c1 = scenario.playerPosition();
                double[] s1 = serverPos(server, ctx, name);
                List<String> flags1 = scenario.flags();
                server.mark();
                // A teleport that moves him: the bank starts again.
                ctx.runOnClient(mc -> mc.player.setYRot(mc.player.getYRot() + 180f));
                ctx.waitTicks(35);
                long bankBeforeMove = cnum(ctx, "banked");
                server.command("execute as " + name + " at @s run tp @s ~ ~ ~3");
                ctx.waitTicks(3);
                long bankAfterMove = cnum(ctx, "banked");
                long moved = cnum(ctx, "movingTeleports") - mv0;
                ctx.waitTicks(35);
                long bankRefilled = cnum(ctx, "banked");
                double[] shot2 = shootByPress(ctx, true);
                ctx.waitTicks(20);
                ctx.runOnClient(mc -> PacketTrace.stop());
                double[] c2 = scenario.playerPosition();
                double[] s2 = serverPos(server, ctx, name);
                int positions = PacketTrace.received("player_position");
                List<String> flags2 = scenario.flags();
                for (String f : flags1) {
                    scenario.log("  FLAG (in place, then the shot) " + f);
                }
                for (String f : flags2) {
                    scenario.log("  FLAG (moving teleport, then the shot) " + f);
                }
                scenario.log(String.format(Locale.ROOT, "charge-inplace: bank %d; relative in-place tp: bank %d, %d in-place "
                                + "counted; absolute in-place tp: bank %d, %d in-place in all, %d answer(s) waiting; shot 5 ticks "
                                + "later %d tick(s) %.2f blocks, client %s server %s, %d flag(s) so far. Moving tp (3 blocks): bank "
                                + "%d -> %d, %d moving counted, refilled to %d; shot %d tick(s) %.2f blocks; client %s server %s; "
                                + "%d position packet(s)", bank0, bankRel, inRel, bankAbs, inAbs, answersWaiting, (long) shot[2],
                        shot[0], fmt(c1), fmt(s1), flags1.size(), bankBeforeMove, bankAfterMove, moved, bankRefilled,
                        (long) shot2[2], shot2[0], fmt(c2), fmt(s2), positions));
                check(bank0 == 20, "the bank did not fill: " + bank0);
                check(inRel == 1 && bankRel == 20, "the relative in-place teleport did not keep the bank");
                check(inAbs == 2 && bankAbs == 20, "the absolute in-place teleport did not keep the bank");
                check(shot[1] == 1 && shot[2] == 20 && shot[0] > 4.5, "no full shot after the in-place teleports");
                // The bank kept through an in-place teleport is the owner's design (2026-10-25). On GrimAC the teleport's
                // confirm then arrives before the answer to the ping GrimAC sent with it (the answers wait 990 ms), which
                // GrimAC reads as a confirm and a move with no teleport pending; recorded here, see the report.
                scenario.log(String.format(Locale.ROOT, "IN-PLACE VERDICT: shot after the in-place teleports %s with %d flag(s); "
                        + "shot after the moving teleport %s with %d flag(s)", flat(c1, s1) < 0.5 ? "landed" : "was REFUSED",
                        flags1.size(), flat(c2, s2) < 0.5 ? "landed" : "was REFUSED", flags2.size()));
                // The moving-teleport half runs after GrimAC has been upset by the in-place half (it set him back once
                // more, counted as a second moving teleport), so it is only recorded here; 1025-blink-charge-warp and
                // -rapid prove a moving teleport restarts the bank cleanly.
                check(bankRefilled == 20 && shot2[1] == 1 && shot2[2] == 20, "the bank did not refill and fire after the move");
            } finally {
                chargeOff(ctx);
                tearDown(ctx, gate);
            }
            ctx.waitTicks(60);
            server.mark();
        });
    }

    /**
     * 1025-blink-charge-landing: charged, then sprinting - the green landing box must draw (landingFrames rises), and the
     * estimate read the tick before the shot must be within 1.5 blocks of where the shot (fired that next tick) left him,
     * on flat ground.
     */
    private static void chargeLanding(ClientGameTestContext ctx) {
        int x = -5000;
        int z = -3800;
        Scenario.run(ctx, "1025-blink-charge-landing", (server, scenario) -> arena(server, x - 15, z), (server, scenario) -> {
            boolean gate = setUp(ctx, 20);
            try {
                String name = playerName(ctx);
                chargeOn(ctx);
                ctx.waitTicks(35);
                long bank = cnum(ctx, "banked");
                long frames0 = num(ctx, "landingFrames");
                long framesStill = 0;
                ctx.waitTicks(10);
                framesStill = num(ctx, "landingFrames") - frames0;
                ctx.getInput().holdKey(o -> o.keyUp);
                ctx.getInput().holdKey(o -> o.keySprint);
                long f1 = num(ctx, "landingFrames");
                ctx.waitTicks(12);
                long framesMoving = num(ctx, "landingFrames") - f1;
                long bankMoving = cnum(ctx, "banked");
                double[] est = ctx.computeOnClient(mc -> {
                    net.minecraft.world.phys.Vec3 v = (net.minecraft.world.phys.Vec3) Mod.staticCall(CHARGE, "landingEstimate");
                    Mod.setField(CHARGE, "keyHeldForTest", Boolean.TRUE);
                    return v == null ? null : new double[]{v.x, v.y, v.z};
                });
                double[] from = scenario.playerPosition();
                ctx.waitTicks(1);
                ctx.runOnClient(mc -> Mod.setField(CHARGE, "keyHeldForTest", Boolean.FALSE));
                double[] landed = scenario.playerPosition();
                ctx.getInput().releaseKey(o -> o.keyUp);
                ctx.getInput().releaseKey(o -> o.keySprint);
                long ticks = cnum(ctx, "lastShotTicks");
                ctx.waitTicks(20);
                double[] client = scenario.playerPosition();
                double[] srv = serverPos(server, ctx, name);
                double err = est == null ? -1 : flat(est, landed);
                scenario.log(String.format(Locale.ROOT, "charge-landing: bank %d; landing frames standing %d, sprinting %d (bank "
                                + "%d); estimate %s from %s; the shot (%d tick(s)) left him at %s (%.2f blocks on), %.2f from the "
                                + "estimate; client %s server %s", bank, framesStill, framesMoving, bankMoving,
                        est == null ? "-" : fmt(est), fmt(from), ticks, fmt(landed), flat(from, landed), err, fmt(client), fmt(srv)));
                check(bank == 20 && bankMoving == 20, "the bank was not full while sprinting");
                check(framesStill == 0, "the landing box drew while he stood still");
                check(framesMoving > 0, "the landing box never drew while charging and moving");
                check(est != null, "no landing estimate while charging and moving");
                check(ticks == 20 && flat(from, landed) > 4.5, "the shot did not fire");
                check(err >= 0 && err < 1.5, String.format(Locale.ROOT, "the estimate was %.2f blocks off", err));
                check(flat(client, srv) < 0.5, "client and server disagree after the shot");
            } finally {
                ctx.getInput().releaseKey(o -> o.keyUp);
                ctx.getInput().releaseKey(o -> o.keySprint);
                chargeOff(ctx);
                tearDown(ctx, gate);
            }
            ctx.waitTicks(40);
        });
    }

    // ---- rapid etherwarps, menus and leaps (killer560, 2026-10-25: "double check that the blink will hold through
    // multiple very fast succession etherwarps and menu opens and things like that") ----

    private static final String ZP_CFG = "zpew.ZeroPingEtherwarpConfig";

    /** Abilities on, an AOTV in slot 0, Zero Ping Etherwarp on (so warps one tick apart aim from the predicted landing). */
    private static dev.testkit.gametest.hx.Hx warpSetUp(ClientGameTestContext ctx) {
        dev.testkit.gametest.hx.Hx hx = dev.testkit.gametest.hx.Hx.connect();
        hx.call("dungeon.abilities", "enabled", true);
        hx.give(0, AOTV);
        ctx.runOnClient(mc -> {
            mc.player.getInventory().setSelectedSlot(0);
            Mod.set(ZP_CFG, "setEnabled", true);
            Mod.set(ZP_CFG, "setChain", true);
            Mod.set(ZP_CFG, "setTimeoutMs", 1500);
        });
        ctx.waitTicks(10);
        return hx;
    }

    private static void warpTearDown(ClientGameTestContext ctx, dev.testkit.gametest.hx.Hx hx) {
        ctx.getInput().releaseKey(o -> o.keyShift);
        ctx.runOnClient(mc -> {
            Latency.remove();
            Mod.set(ZP_CFG, "setEnabled", false);
            if (dev.testkit.compat.McCompat.screen(mc) != null) {
                mc.player.closeContainer();
            }
        });
        if (hx != null) {
            hx.call("dungeon.abilities", "enabled", false);
            hx.close();
        }
        ctx.waitTicks(20);
    }

    /** One etherwarp now from {@code feet} at the floor block (bx, bz): a client turn and a right click. */
    private static void warpAt(ClientGameTestContext ctx, double[] feet, int bx, int bz) {
        double dx = bx + 0.5 - feet[0];
        double dy = SURFACE_Y - (feet[1] + 1.27);
        double dz = bz + 0.5 - feet[2];
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        ctx.runOnClient(mc -> {
            mc.player.setYRot(mc.player.getYRot() + net.minecraft.util.Mth.wrapDegrees(yaw - mc.player.getYRot()));
            mc.player.setXRot(net.minecraft.util.Mth.clamp(pitch, -90f, 90f));
        });
        ctx.getInput().pressKey(o -> o.keyUse);
    }

    private static boolean menuOpen(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> dev.testkit.compat.McCompat.screen(mc) instanceof
                net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>);
    }

    private static void clickSlot(ClientGameTestContext ctx, int slot) {
        ctx.runOnClient(mc -> {
            var menu = mc.player.containerMenu;
            mc.gameMode.handleContainerInput(menu.containerId, slot, 0,
                    net.minecraft.world.inventory.ContainerInput.PICKUP, mc.player);
        });
    }

    /** 1025-blink-hold-rapid-warps / -menu / -mixed: see {@link #holdRapidCase}. */
    private static void holdRapid(ClientGameTestContext ctx) {
        AssertionError first = null;
        String[][] kinds = {{"warps", "1", "0", "0"}, {"menu", "0", "1", "0"}, {"mixed", "1", "1", "1"}};
        int i = 0;
        for (String[] k : kinds) {
            try {
                holdRapidCase(ctx, "1025-blink-hold-rapid-" + k[0], "1".equals(k[1]), "1".equals(k[2]), "1".equals(k[3]),
                        -5600 - 100 * i);
            } catch (AssertionError e) {
                first = first == null ? e : first;
            }
            i++;
        }
        if (first != null) {
            throw first;
        }
    }

    /**
     * One hold with the Hold key down (Hold Ticks 20, 150 ms latency like Hypixel): with {@code warps}, two etherwarps sent
     * one tick apart just before the key goes down (their landings come back during the hold and are held through) and
     * three more one tick apart inside it (Zero Ping Etherwarp aims each from the one before); with {@code menu}, a server
     * chest menu opened during the hold, two slots clicked (they reach the server during the hold) and the menu closed;
     * with {@code leap}, a server teleport 10 blocks to the side first. The hold must stay on until the key comes up, every
     * teleport that arrives during it (counted by PacketTrace) is held through, and he ends where the last warp put him on
     * client and server. Grim silent. He stands up before the hold when there are warps: a menu click while the server
     * still has him sneaking is GrimAC MultiActionsC, and GrimAC drops the click (2026-10-25 first run).
     */
    private static void holdRapidCase(ClientGameTestContext ctx, String caseName, boolean warps, boolean menu, boolean leap,
                                      int x) {
        int z = -4400;
        Scenario.run(ctx, caseName, (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = setUp(ctx, 20);
            dev.testkit.gametest.hx.Hx hx = null;
            try {
                hx = warpSetUp(ctx);
                String name = playerName(ctx);
                server.command("tp " + name + " " + (x - 30.5) + " " + SURFACE_Y + " " + (z + 0.5) + " -90 0");
                ctx.runOnClient(mc -> Latency.install(150));
                ctx.waitTicks(40);
                com.google.gson.JsonObject before = hx.call("dungeon.stats").getAsJsonObject();
                long tp0 = num(ctx, "teleportsHeld");
                long ref0 = num(ctx, "refused");
                long holds0 = num(ctx, "releasedHolds");
                long live0 = num(ctx, "clicksLive");
                long ev0 = hx.head();
                int bx = x - 31;
                int[] lastWarp = null;
                double[] from = scenario.playerPosition();
                int sentWarps = 0;
                if (warps) {
                    // Two warps one tick apart just before the key goes down: their landings come back during the hold.
                    ctx.getInput().holdKey(o -> o.keyShift);
                    ctx.waitTicks(5);
                    for (int w = 0; w < 2; w++) {
                        int tx = bx + 6 * (w + 1);
                        warpAt(ctx, from, tx, z);
                        ctx.waitTicks(1);
                        from = new double[]{tx + 0.5, SURFACE_Y + 0.05, z + 0.5};
                        lastWarp = new int[]{tx, z};
                        sentWarps++;
                    }
                    // Stand up before the hold, so the sneak's end reaches the server live: a menu click while the server
                    // still has him sneaking is GrimAC MultiActionsC, and GrimAC then drops the click.
                    ctx.getInput().releaseKey(o -> o.keyShift);
                    ctx.waitTicks(1);
                }
                ctx.runOnClient(mc -> PacketTrace.start());
                holdDown(ctx);
                ctx.waitTicks(1);
                int heldTicks = 0;
                boolean droppedEarly = false;
                long clicksDuringHold = 0;
                // Three ticks in: the two warps sent before the key are with the server. Then the leap and the menu.
                for (int t = 0; t < 3; t++) {
                    ctx.waitTicks(1);
                    droppedEarly |= !holding(ctx);
                }
                int row = z;
                if (leap) {
                    int lx = lastWarp == null ? bx : lastWarp[0];
                    row = z + 10;
                    server.command("tp " + name + " " + (lx + 0.5) + " " + SURFACE_Y + " " + (row + 0.5));
                    from = new double[]{lx + 0.5, SURFACE_Y, row + 0.5};
                    lastWarp = new int[]{lx, row};
                }
                boolean opened = false;
                int clickTick = -1;
                if (menu) {
                    hx.menu("Blink Rapid Menu", 3, java.util.Map.of(11, "minecraft:stone", 13, "minecraft:dirt"), null);
                    for (int t = 0; t < 10 && !opened; t++) {
                        ctx.waitTicks(1);
                        droppedEarly |= !holding(ctx);
                        opened = menuOpen(ctx);
                    }
                    clickTick = (int) num(ctx, "heldTicks");
                    clickSlot(ctx, 11);
                    ctx.waitTicks(1);
                    clickSlot(ctx, 13);
                    ctx.waitTicks(1);
                    droppedEarly |= !holding(ctx);
                    ctx.runOnClient(mc -> mc.player.closeContainer());
                    ctx.waitTicks(1);
                    droppedEarly |= !holding(ctx);
                } else {
                    for (int t = 0; t < 4; t++) {
                        ctx.waitTicks(1);
                        droppedEarly |= !holding(ctx);
                    }
                }
                if (warps) {
                    // Three more one tick apart inside the hold (the sneak goes into the hold with them, in order).
                    ctx.getInput().holdKey(o -> o.keyShift);
                    ctx.waitTicks(1);
                    int startX = lastWarp[0];
                    for (int w = 0; w < 3; w++) {
                        int tx = startX + 6 * (w + 1);
                        warpAt(ctx, from, tx, row);
                        ctx.waitTicks(1);
                        droppedEarly |= !holding(ctx);
                        from = new double[]{tx + 0.5, SURFACE_Y + 0.05, row + 0.5};
                        lastWarp = new int[]{tx, row};
                        sentWarps++;
                    }
                }
                if (menu) {
                    // The clicks went out live: they must have reached the server while the hold is still on.
                    for (int t = 0; t < 6 && hx.events(ev0, "menu.click").size() < 2 && holding(ctx); t++) {
                        ctx.waitTicks(1);
                    }
                    boolean stillHolding = holding(ctx);
                    clicksDuringHold = stillHolding ? hx.events(ev0, "menu.click").size() : -1;
                    droppedEarly |= !stillHolding;
                }
                heldTicks = (int) num(ctx, "heldTicks");
                boolean holdingAtKeyUp = holding(ctx);
                long holdsBeforeUp = num(ctx, "releasedHolds") - holds0;
                long heldDuring = num(ctx, "teleportsHeld") - tp0;
                holdUp(ctx);
                ctx.runOnClient(mc -> PacketTrace.stop());
                int positionsDuring = PacketTrace.received("player_position");
                ctx.waitTicks(40);
                ctx.getInput().releaseKey(o -> o.keyShift);
                ctx.waitTicks(10);
                long clicksAfter = hx.events(ev0, "menu.click").size();
                com.google.gson.JsonObject after = hx.call("dungeon.stats").getAsJsonObject();
                int serverWarps = after.get("etherwarps").getAsInt() - before.get("etherwarps").getAsInt();
                long refusedN = num(ctx, "refused") - ref0;
                long holds = num(ctx, "releasedHolds") - holds0;
                long live = num(ctx, "clicksLive") - live0;
                double[] client = scenario.playerPosition();
                double[] srv = serverPos(server, ctx, name);
                int expectAtLeast = (warps ? 1 : 0) + (leap ? 1 : 0);
                scenario.log(String.format(Locale.ROOT, "%s: %d warp(s) sent (server made %d); %d position packet(s) arrived "
                                + "during the hold, %d held through, %d refused; menu opened %s (clicked at hold tick %d), %d "
                                + "click(s) live, %d reached the server during the hold (%d by the end); hold %d tick(s) at key "
                                + "up, holding then %s, %d release(s) before key up, dropped early %s, %d release(s) in all; ended "
                                + "client %s server %s, last warp %s", caseName, sentWarps, serverWarps, positionsDuring,
                        heldDuring, refusedN, opened, clickTick, live, clicksDuringHold, clicksAfter, heldTicks, holdingAtKeyUp,
                        holdsBeforeUp, droppedEarly, holds, fmt(client), fmt(srv),
                        lastWarp == null ? "-" : lastWarp[0] + "," + lastWarp[1]));
                check(!droppedEarly && holdingAtKeyUp && holdsBeforeUp == 0 && holds == 1,
                        "the hold did not stay on until the key came up");
                check(positionsDuring >= expectAtLeast, "only " + positionsDuring + " teleport(s) arrived during the hold, "
                        + "wanted at least " + expectAtLeast + ": nothing to hold through");
                check(heldDuring == positionsDuring && refusedN == 0, "not every teleport during the hold was held through ("
                        + heldDuring + " of " + positionsDuring + ", " + refusedN + " refused)");
                if (warps) {
                    check(serverWarps == sentWarps, "the server made " + serverWarps + " of " + sentWarps + " etherwarps");
                    check(Math.abs(client[0] - (lastWarp[0] + 0.5)) < 1.0 && Math.abs(client[2] - (lastWarp[1] + 0.5)) < 1.0,
                            "the client is not where the last warp put him: " + fmt(client));
                }
                if (menu) {
                    check(opened && live >= 2, "the menu did not open or its clicks did not go out live");
                    check(clicksDuringHold >= 2, "the clicks did not reach the server during the hold: " + clicksDuringHold);
                }
                check(flat(client, srv) < 0.5, "client and server disagree at the end: " + fmt(client) + " vs " + fmt(srv));
            } finally {
                warpTearDown(ctx, hx);
                tearDown(ctx, gate);
            }
            ctx.waitTicks(40);
        });
    }

    /**
     * 1025-blink-charge-rapid: while charging (Toggle, a full bank), three etherwarps one tick apart (Zero Ping Etherwarp,
     * 150 ms latency), then a server menu opened, three slots clicked and closed. The warps restart the bank as designed
     * (moving teleports), every click reaches the server within 6 ticks, Charge stays on every tick, and once the bank
     * has refilled a sprinting shot of 20 ticks is clean with client and server agreeing.
     */
    private static void chargeRapid(ClientGameTestContext ctx) {
        int x = -5600;
        int z = -4800;
        Scenario.run(ctx, "1025-blink-charge-rapid", (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = setUp(ctx, 20);
            dev.testkit.gametest.hx.Hx hx = null;
            try {
                hx = warpSetUp(ctx);
                String name = playerName(ctx);
                server.command("tp " + name + " " + (x - 30.5) + " " + SURFACE_Y + " " + (z + 0.5) + " -90 0");
                ctx.runOnClient(mc -> Latency.install(150));
                ctx.waitTicks(30);
                ctx.runOnClient(mc -> PacketTrace.start());
                chargeOn(ctx);
                ctx.waitTicks(35);
                long bank0 = cnum(ctx, "banked");
                long mv0 = cnum(ctx, "movingTeleports");
                com.google.gson.JsonObject before = hx.call("dungeon.stats").getAsJsonObject();
                boolean alwaysOn = true;
                ctx.getInput().holdKey(o -> o.keyShift);
                ctx.waitTicks(3);
                double[] from = scenario.playerPosition();
                int[] last = null;
                for (int w = 0; w < 3; w++) {
                    int tx = x - 31 + 6 * (w + 1);
                    warpAt(ctx, from, tx, z);
                    ctx.waitTicks(1);
                    alwaysOn &= charging(ctx);
                    from = new double[]{tx + 0.5, SURFACE_Y + 0.05, z + 0.5};
                    last = new int[]{tx, z};
                }
                for (int t = 0; t < 15; t++) {
                    ctx.waitTicks(1);
                    alwaysOn &= charging(ctx);
                }
                ctx.getInput().releaseKey(o -> o.keyShift);
                long moved = cnum(ctx, "movingTeleports") - mv0;
                long bankAfterWarps = cnum(ctx, "banked");
                double[] cw = scenario.playerPosition();
                // A menu: three clicks, each must reach the server on time.
                hx.menu("Blink Charge Menu", 3, java.util.Map.of(11, "minecraft:stone", 13, "minecraft:dirt", 15,
                        "minecraft:oak_log"), null);
                boolean opened = false;
                for (int t = 0; t < 10 && !opened; t++) {
                    ctx.waitTicks(1);
                    alwaysOn &= charging(ctx);
                    opened = menuOpen(ctx);
                }
                int onTime = 0;
                StringBuilder lag = new StringBuilder();
                for (int slot : new int[]{11, 13, 15}) {
                    long ev = hx.head();
                    clickSlot(ctx, slot);
                    int seenAfter = -1;
                    for (int t = 0; t < 12 && seenAfter < 0; t++) {
                        ctx.waitTicks(1);
                        alwaysOn &= charging(ctx);
                        if (!hx.events(ev, "menu.click").isEmpty()) {
                            seenAfter = t + 1;
                        }
                    }
                    lag.append(seenAfter).append(' ');
                    if (seenAfter > 0 && seenAfter <= 6) {
                        onTime++;
                    }
                }
                ctx.runOnClient(mc -> mc.player.closeContainer());
                ctx.waitTicks(40);
                alwaysOn &= charging(ctx);
                long bankRefilled = cnum(ctx, "banked");
                com.google.gson.JsonObject after = hx.call("dungeon.stats").getAsJsonObject();
                int serverWarps = after.get("etherwarps").getAsInt() - before.get("etherwarps").getAsInt();
                double[] shot = shootByPress(ctx, true);
                ctx.waitTicks(20);
                ctx.runOnClient(mc -> PacketTrace.stop());
                double[] client = scenario.playerPosition();
                double[] srv = serverPos(server, ctx, name);
                scenario.log(String.format(Locale.ROOT, "charge-rapid: bank %d; 3 warps one tick apart: server made %d, %d moving "
                                + "teleport(s) counted, bank %d after, at %s (last warp %d,%d); menu opened %s, clicks reached the "
                                + "server after [%s] tick(s) (%d on time); charging every tick %s; bank refilled to %d; shot %d "
                                + "tick(s) %.2f blocks; client %s server %s", bank0, serverWarps, moved, bankAfterWarps, fmt(cw),
                        last[0], last[1], opened, lag.toString().trim(), onTime, alwaysOn, bankRefilled, (long) shot[2], shot[0],
                        fmt(client), fmt(srv)));
                check(bank0 == 20, "the bank did not fill before the warps: " + bank0);
                check(serverWarps == 3 && moved == 3, "the warps did not all land as moving teleports (" + serverWarps
                        + " made, " + moved + " counted)");
                check(Math.abs(cw[0] - (last[0] + 0.5)) < 1.0, "he is not where the last warp put him: " + fmt(cw));
                check(opened && onTime == 3, "the menu clicks did not reach the server on time: " + lag);
                check(alwaysOn, "Charge went off during the warps or the menu");
                check(bankRefilled == 20 && shot[1] == 1 && shot[2] == 20 && shot[0] > 4.5, "no full shot afterwards");
                check(flat(client, srv) < 0.5, "client and server disagree after the shot: " + fmt(client) + " vs " + fmt(srv));
            } finally {
                warpTearDown(ctx, hx);
                chargeOff(ctx);
                tearDown(ctx, gate);
            }
            ctx.waitTicks(40);
        });
    }

    /**
     * 1025-blink-charge-armed-menu: a shot armed (a Toggle fire standing still), then a server menu opens. With the menu up
     * the shot must not fire, not even with a movement key down; the menu closes, he stands 3 ticks (still armed), and the
     * first movement after it fires the whole bank. Grim silent.
     */
    private static void chargeArmedMenu(ClientGameTestContext ctx) {
        int x = -5600;
        int z = -5200;
        Scenario.run(ctx, "1025-blink-charge-armed-menu", (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = setUp(ctx, 20);
            try (dev.testkit.gametest.hx.Hx hx = dev.testkit.gametest.hx.Hx.connect()) {
                String name = playerName(ctx);
                ctx.runOnClient(mc -> PacketTrace.start());
                chargeOn(ctx);
                ctx.waitTicks(35);
                long bank = cnum(ctx, "banked");
                long shots0 = cnum(ctx, "shots");
                pressCharge(ctx);
                boolean armed0 = armed(ctx);
                hx.menu("Blink Armed Menu", 3, java.util.Map.of(13, "minecraft:stone"), null);
                boolean opened = false;
                for (int t = 0; t < 10 && !opened; t++) {
                    ctx.waitTicks(1);
                    opened = menuOpen(ctx);
                }
                clickSlot(ctx, 13);
                ctx.getInput().holdKey(o -> o.keyUp);
                ctx.waitTicks(6);
                ctx.getInput().releaseKey(o -> o.keyUp);
                long shotsInMenu = cnum(ctx, "shots") - shots0;
                boolean armedInMenu = armed(ctx);
                ctx.runOnClient(mc -> mc.player.closeContainer());
                ctx.waitTicks(3);
                long shotsClosed = cnum(ctx, "shots") - shots0;
                boolean armedClosed = armed(ctx);
                double[] p0 = scenario.playerPosition();
                ctx.getInput().holdKey(o -> o.keyUp);
                ctx.getInput().holdKey(o -> o.keySprint);
                ctx.waitTicks(1);
                double[] p1 = scenario.playerPosition();
                ctx.getInput().releaseKey(o -> o.keyUp);
                ctx.getInput().releaseKey(o -> o.keySprint);
                long shots = cnum(ctx, "shots") - shots0;
                long ticks = cnum(ctx, "lastShotTicks");
                ctx.waitTicks(20);
                ctx.runOnClient(mc -> PacketTrace.stop());
                double[] client = scenario.playerPosition();
                double[] srv = serverPos(server, ctx, name);
                int setbacks = PacketTrace.received("player_position");
                scenario.log(String.format(Locale.ROOT, "charge-armed-menu: bank %d, armed %s; menu opened %s; with the menu up "
                                + "and forward held 6 ticks: %d shot(s), armed %s; closed, 3 ticks standing: %d shot(s), armed %s; "
                                + "first move: %d shot(s) of %d tick(s), %.2f blocks that tick; client %s server %s; %d setback(s)",
                        bank, armed0, opened, shotsInMenu, armedInMenu, shotsClosed, armedClosed, shots, ticks, flat(p0, p1),
                        fmt(client), fmt(srv), setbacks));
                check(bank == 20 && armed0, "the shot was not armed with a full bank");
                check(opened, "the menu never opened: nothing was tested");
                check(shotsInMenu == 0 && armedInMenu, "the armed shot fired (or was dropped) with the menu up");
                check(shotsClosed == 0 && armedClosed, "the armed shot fired (or was dropped) before he moved");
                check(shots == 1 && ticks == 20 && flat(p0, p1) > 4.5, "the first move after the menu did not fire the bank");
                check(flat(client, srv) < 0.5, "client and server disagree after the shot: " + fmt(client) + " vs " + fmt(srv));
                check(setbacks == 0, "the server set him back " + setbacks + " time(s)");
            } finally {
                ctx.getInput().releaseKey(o -> o.keyUp);
                ctx.getInput().releaseKey(o -> o.keySprint);
                ctx.runOnClient(mc -> {
                    if (dev.testkit.compat.McCompat.screen(mc) != null) {
                        mc.player.closeContainer();
                    }
                });
                chargeOff(ctx);
                tearDown(ctx, gate);
            }
            ctx.waitTicks(40);
        });
    }

    private static String statusLine(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            Object c = Mod.staticCall(FEATURE, "statusLine");
            return c == null ? "" : ((net.minecraft.network.chat.Component) c).getString().replaceAll("§.", "");
        });
    }

    /**
     * 1025-blink-hud: while charging the HUD reads "Charge n/20" and while holding "Hold n/20"; it really draws in game
     * (hudFrames, HudSeen), and the HUD editor then lists the "blink" element with Show Unseen OFF.
     */
    @SuppressWarnings("unchecked")
    private static void hud(ClientGameTestContext ctx) {
        int x = -5200;
        int z = -3000;
        Scenario.run(ctx, "1025-blink-hud", (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = setUp(ctx, 20);
            boolean showAll = ctx.computeOnClient(mc -> (Boolean) Mod.get("hud.HudConfig", "isEditorShowAll"));
            try {
                ctx.runOnClient(mc -> Mod.staticCall("hud.HudSeen", "forget", "blink"));
                long frames0 = num(ctx, "hudFrames");
                chargeOn(ctx);
                ctx.waitTicks(30);
                String chargeLine = statusLine(ctx);
                long framesCharge = num(ctx, "hudFrames") - frames0;
                boolean seen = ctx.computeOnClient(mc -> (Boolean) Mod.staticCall("hud.HudSeen", "drawnRecently", "blink"));
                chargeOff(ctx);
                holdDown(ctx);
                ctx.waitTicks(6);
                String holdLine = statusLine(ctx);
                ctx.waitTicks(20);
                holdUp(ctx);
                ctx.waitTicks(3);
                String idleLine = statusLine(ctx);
                List<String> listed = ctx.computeOnClient(mc -> {
                    Mod.set("hud.HudConfig", "setEditorShowAll", false);
                    try {
                        net.minecraft.client.gui.screens.Screen editor = (net.minecraft.client.gui.screens.Screen)
                                Mod.cls("hud.HudEditorScreen").getConstructor(net.minecraft.client.gui.screens.Screen.class)
                                        .newInstance((Object) null);
                        dev.testkit.compat.McCompat.setScreen(mc, editor);
                        return null;
                    } catch (ReflectiveOperationException e) {
                        throw new AssertionError(e);
                    }
                });
                ctx.waitTicks(3);
                listed = ctx.computeOnClient(mc -> {
                    List<String> ids = new ArrayList<>();
                    Object s = dev.testkit.compat.McCompat.screen(mc);
                    for (Object e : (List<Object>) Mod.field(s, "shown")) {
                        ids.add((String) Mod.call(e, "id"));
                    }
                    dev.testkit.compat.McCompat.setScreen(mc, null);
                    return ids;
                });
                scenario.log("hud: charging line \"" + chargeLine + "\" (" + framesCharge + " frame(s) drawn, HudSeen " + seen
                        + "); holding line \"" + holdLine + "\"; idle \"" + idleLine + "\"; HUD editor (Show Unseen off) lists "
                        + listed);
                check(chargeLine.startsWith("Charge 20/20"), "the charging line is not \"Charge 20/20\": " + chargeLine);
                check(holdLine.matches("Hold \\d+/20"), "the holding line is not \"Hold n/20\": " + holdLine);
                check(idleLine.isEmpty(), "the HUD still shows a line with nothing running: " + idleLine);
                check(framesCharge > 0 && seen, "the Blink HUD never drew in game");
                check(listed.contains("blink"), "the HUD editor does not list the blink element without Show Unseen");
            } finally {
                ctx.runOnClient(mc -> {
                    Mod.set("hud.HudConfig", "setEditorShowAll", showAll);
                    dev.testkit.compat.McCompat.setScreen(mc, null);
                });
                chargeOff(ctx);
                tearDown(ctx, gate);
            }
            ctx.waitTicks(40);
        });
    }

    /**
     * 1025-blink-clicks (killer560, 2026-10-25: a blink "while being in a terminal menu and clicking"): a server menu open,
     * the Hold key down for a 20-tick hold, four slot clicks during it. The clicks go out live, the hold runs to its limit
     * and releases, the menu stays open, Grim silent.
     */
    private static void clicks(ClientGameTestContext ctx) {
        int x = -5000;
        int z = -4000;
        Scenario.run(ctx, "1025-blink-clicks", (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = setUp(ctx, 20);
            try (dev.testkit.gametest.hx.Hx hx = dev.testkit.gametest.hx.Hx.connect()) {
                hx.menu("Blink Terminal", 3, java.util.Map.of(11, "minecraft:stone", 13, "minecraft:dirt", 15,
                        "minecraft:oak_log"), null);
                ctx.waitTicks(10);
                long live0 = num(ctx, "clicksLive");
                long holds0 = num(ctx, "releasedHolds");
                holdDown(ctx);
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
                holdUp(ctx);
                boolean open = ctx.computeOnClient(mc -> dev.testkit.compat.McCompat.screen(mc) instanceof
                        net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>);
                long live = num(ctx, "clicksLive") - live0;
                long released = num(ctx, "releasedHolds") - holds0;
                long lastHold = num(ctx, "lastHoldTicks");
                ctx.runOnClient(mc -> mc.player.closeContainer());
                scenario.log(String.format(Locale.ROOT, "clicks: %d click(s) went out live during the hold, %d release(s), last "
                        + "hold %d tick(s), menu still open %s", live, released, lastHold, open));
                check(live >= 4, "fewer than four clicks went out live during the hold: " + live);
                check(released == 1 && lastHold == 20, "the hold did not run to 20 ticks and release");
                check(open, "the menu closed during the blink");
            } finally {
                tearDown(ctx, gate);
            }
            ctx.waitTicks(40);
        });
    }

    /**
     * 1025-blink-charge-far (killer560, 2026-10-25: "blink as far as possible"): the shot runs his real movement, so its
     * length is his speed times 20 ticks. A plain walking shot, then sprint-jump, Speed IV sprint, and both: all clean and
     * agreeing with the server; the log gives the distances.
     */
    private static void chargeFar(ClientGameTestContext ctx) {
        int x = -5000;
        int z = -4200;
        Scenario.run(ctx, "1025-blink-charge-far", (server, scenario) -> arena(server, x - 25, z), (server, scenario) -> {
            boolean gate = setUp(ctx, 20);
            String name = playerName(ctx);
            try {
                ctx.runOnClient(mc -> PacketTrace.start());
                chargeOn(ctx);
                ctx.waitTicks(35);
                double[] walk = shootByPress(ctx, false);
                StringBuilder rows = new StringBuilder();
                int good = 0;
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
                    long st0 = cnum(ctx, "shotTicks");
                    double[] before = scenario.playerPosition();
                    ctx.getInput().holdKey(o -> o.keySprint);
                    if (jump) {
                        ctx.getInput().holdKey(o -> o.keyJump);
                    }
                    ctx.getInput().holdKey(o -> o.keyUp);
                    ctx.runOnClient(mc -> Mod.setField(CHARGE, "keyHeldForTest", Boolean.TRUE));
                    ctx.waitTicks(2);
                    ctx.runOnClient(mc -> Mod.setField(CHARGE, "keyHeldForTest", Boolean.FALSE));
                    double[] after = scenario.playerPosition();
                    ctx.getInput().releaseKey(o -> o.keyUp);
                    ctx.getInput().releaseKey(o -> o.keyJump);
                    ctx.getInput().releaseKey(o -> o.keySprint);
                    long ticks = cnum(ctx, "shotTicks") - st0;
                    ctx.waitTicks(30);
                    double[] client = scenario.playerPosition();
                    double[] srv = serverPos(server, ctx, name);
                    int sb = PacketTrace.received("player_position") - sb0;
                    dist[k] = flat(before, after);
                    String label = jump && speed ? "sprint-jump + Speed IV" : jump ? "sprint-jump" : "sprint + Speed IV";
                    rows.append(String.format(Locale.ROOT, " [%s: bank %d, shot %d tick(s) %.2f blocks over 2 ticks; client %s "
                            + "server %s; %d position packet(s) after]", label, bank, ticks, dist[k], fmt(client), fmt(srv), sb));
                    if (bank == 20 && ticks == 20 && dist[k] > walk[0] && flat(client, srv) < 0.5 && sb == 0) {
                        good++;
                    }
                }
                ctx.runOnClient(mc -> PacketTrace.stop());
                server.command("effect clear " + name);
                scenario.log(String.format(Locale.ROOT, "charge-far: walking shot %d tick(s) %.2f blocks;", (long) walk[2], walk[0])
                        + rows);
                check(walk[2] == 20, "the walking shot was not 20 banked ticks");
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
            boolean gate = setUp(ctx, 20);
            try {
                String name = playerName(ctx);
                ctx.runOnClient(mc -> PacketTrace.start());
                chargeOn(ctx);
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
                double[] shot = shootByPress(ctx, false);
                ctx.waitTicks(20);
                ctx.runOnClient(mc -> PacketTrace.stop());
                int setbacks = PacketTrace.received("player_position");
                double[] client = scenario.playerPosition();
                double[] srv = serverPos(server, ctx, name);
                scenario.log(String.format(Locale.ROOT, "charge-long: after ~40 s (standing, a lever, sprinting) bank %d, connected %s; "
                                + "shot %d tick(s), %.2f blocks over the press's tick; client %s server %s; %d setback(s)", bank,
                        connected, (long) shot[2], shot[0], fmt(client), fmt(srv), setbacks));
                check(connected, "he was disconnected while charging");
                check(bank == 20, "the bank was not full after the wait: " + bank);
                check(shot[1] == 1 && shot[2] == 20 && shot[0] > 3.5, "no full shot after the wait");
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
     * 1025-blink-shot-lever (killer560, 2026-10-25: "if I blink past a lever it'll still flick it right"). A lever 7 blocks
     * ahead and to the side, out of Secret Aura's reach while he charges; a sprinting Charge shot takes him past it, and
     * the aura flicks it inside the shot. The server must show it on, client and server agree, GrimAC silent.
     */
    private static void shotLever(ClientGameTestContext ctx) {
        int x = -5400;
        int z = -3800;
        String name = "1025-blink-shot-lever";
        if (Scenario.skip(name)) {
            return;
        }
        Scenario.labelServerAs("localhost.p3sim.net:25565");
        Scenario.run(ctx, name, (server, scenario) -> TestMap.on(server)
                .platform(x + 5, SURFACE_Y - 1, z, 12)
                .catchFloor(120)
                .survival()
                .clearInventory()
                .skyblockSidebar("F7")
                .command("setblock " + (x + 7) + " " + (SURFACE_Y + 1) + " " + (z + 3) + " minecraft:stone")
                .command("setblock " + (x + 7) + " " + (SURFACE_Y + 1) + " " + (z + 2) + " minecraft:lever[face=wall,facing=north]")
                .spawn(x + 0.5, z + 0.5, -90f)
                .build(), (server, scenario) -> {
            boolean gate = setUp(ctx, 20);
            String cheatCfg = "com.killer560.hub.cheatutils.CheatUtilsConfig";
            try {
                ctx.runOnClient(mc -> {
                    Object cfg = dev.testkit.gametest.ModUnderTest.config(cheatCfg);
                    dev.testkit.gametest.ModUnderTest.set(cfg, "setAuraLevers", true);
                    dev.testkit.gametest.ModUnderTest.set(cfg, "setAuraRange", 4.5);
                    dev.testkit.gametest.ModUnderTest.set(cfg, "setSecretAuraEnabled", true);
                    if (!dev.testkit.gametest.ModUnderTest.getBoolean(cfg, "isSecretAuraEnabled")) {
                        throw new AssertionError("Secret Aura is not live - needs the cheat jar and a dungeon-looking server");
                    }
                });
                String player = playerName(ctx);
                chargeOn(ctx);
                ctx.waitTicks(40);
                net.minecraft.core.BlockPos lever = new net.minecraft.core.BlockPos(x + 7, SURFACE_Y + 1, z + 2);
                boolean before = ctx.computeOnClient(mc -> mc.level.getBlockState(lever)
                        .getValue(net.minecraft.world.level.block.LeverBlock.POWERED));
                long bank = cnum(ctx, "banked");
                long acts0 = cnum(ctx, "shotTickActions");
                double x0 = ctx.computeOnClient(mc -> mc.player.getX());
                shootByPress(ctx, true);
                double x1 = ctx.computeOnClient(mc -> mc.player.getX());
                long acts = cnum(ctx, "shotTickActions") - acts0;
                ctx.waitTicks(20);
                server.command("execute if block " + (x + 7) + " " + (SURFACE_Y + 1) + " " + (z + 2)
                        + " minecraft:lever[powered=true] run say LEVER_ON");
                ctx.waitTicks(10);
                boolean on = server.tail(60).contains("LEVER_ON");
                double[] client = scenario.playerPosition();
                double[] srv = serverPos(server, ctx, player);
                scenario.log(String.format(Locale.ROOT, "blink-shot-lever: lever on before %s, bank %d; shot %.2f blocks (x %.2f -> %.2f, "
                                + "lever at x %d); %d shot tick(s) sent an action; lever on (server) %s; client %s server %s", before,
                        bank, x1 - x0, x0, x1, x + 7, acts, on, fmt(client), fmt(srv)));
                check(!before && bank >= 20, "nothing was tested: the lever was already on or the bank never filled");
                check(on && acts >= 1, "the shot went past the lever without flicking it");
                check(flat(client, srv) < 0.5, "client and server disagree after the shot: " + fmt(client) + " vs " + fmt(srv));
            } finally {
                ctx.runOnClient(mc -> dev.testkit.gametest.ModUnderTest.turnOff(cheatCfg, "setSecretAuraEnabled"));
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
            boolean gate = setUp(ctx, 20);
            try {
                String name = playerName(ctx);
                String dimBefore = ctx.computeOnClient(mc -> mc.level.dimension().toString());
                server.command("execute in minecraft:the_nether run forceload add 0 0");
                server.command("execute in minecraft:the_nether run fill -8 100 -8 8 100 8 minecraft:stone");
                server.command("execute in minecraft:the_nether run fill -8 101 -8 8 105 8 minecraft:air");
                ctx.getInput().holdKey(o -> o.keyUp);
                ctx.getInput().holdKey(o -> o.keySprint);
                ctx.waitTicks(5);
                holdDown(ctx);
                ctx.waitTicks(6);
                boolean heldBefore = holding(ctx);
                long heldMoves = num(ctx, "heldMoves");
                releaseKeys(ctx);
                server.command("execute in minecraft:the_nether run tp " + name + " 0.5 101 0.5");
                String dimAfter = dimBefore;
                for (int i = 0; i < 200 && dimAfter.equals(dimBefore); i++) {
                    ctx.waitTicks(1);
                    dimAfter = ctx.computeOnClient(mc -> mc.level == null ? "" : mc.level.dimension().toString());
                }
                ctx.waitTicks(60);
                boolean holdingAfter = holding(ctx);
                holdUp(ctx);
                boolean connected = ctx.computeOnClient(mc -> mc.getConnection() != null && mc.player != null);
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

    /** 1025-blink-bow: drawing and loosing a bow five times (25-tick draws) with back-to-back holds; what Grim says. */
    private static void bow(ClientGameTestContext ctx) {
        int x = -4600;
        int z = -3000;
        Scenario.runExpectingFlags(ctx, "1025-blink-bow", (server, scenario) -> {
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
            boolean gate = setUp(ctx, 20);
            try {
                server.mark();
                int before = ctx.computeOnClient(mc -> mc.player.getInventory().countItem(net.minecraft.world.item.Items.ARROW));
                long holds0 = num(ctx, "releasedHolds");
                Pulser.start(ctx);
                for (int i = 0; i < 5; i++) {
                    ctx.getInput().holdKey(o -> o.keyUse);
                    ctx.waitTicks(25);
                    ctx.getInput().releaseKey(o -> o.keyUse);
                    ctx.waitTicks(10);
                }
                Pulser.stop(ctx);
                ctx.waitTicks(40);
                int after = ctx.computeOnClient(mc -> mc.player.getInventory().countItem(net.minecraft.world.item.Items.ARROW));
                long holds = num(ctx, "releasedHolds") - holds0;
                List<String> flags = scenario.flags();
                scenario.log("bow: arrows " + before + " -> " + after + " (40 ticks after the last release), " + holds
                        + " release(s); GrimAC " + (flags.isEmpty() ? "clean" : flags.size() + " flag(s): " + firstChecks(flags)));
                for (String f : flags) {
                    scenario.log("  FLAG " + f);
                }
                check(holds >= 4, "the holds did not keep coming while shooting: " + holds);
            } finally {
                ctx.getInput().releaseKey(o -> o.keyUse);
                tearDown(ctx, gate);
            }
            ctx.waitTicks(60);
            server.mark();
        });
    }

    private static void sweep(ClientGameTestContext ctx, String name, int x, boolean walking, int latencyMs) {
        Scenario.runExpectingFlags(ctx, name, (server, scenario) -> arena(server, x, -3000), (server, scenario) -> {
            boolean gate = setUp(ctx, 17);
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
                    server.command("tp " + player + " " + (x - 34.5) + " " + SURFACE_Y + " " + (-3000 + 0.5) + " -90 0");
                    ctx.waitTicks(30);
                    ctx.runOnClient(mc -> Mod.set(CFG, "setHoldTicks", len));
                    server.mark();
                    double[] r;
                    if (walking) {
                        r = walkHold(server, scenario, ctx, len, false);
                    } else {
                        holdToLimit(ctx, len + 10);
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
                        ctx.waitTicks(60);
                    }
                }
            } finally {
                if (latencyMs > 0) {
                    ctx.runOnClient(mc -> Latency.remove());
                }
                tearDown(ctx, gate);
            }
            scenario.log("SWEEP " + name + ": clean lengths " + cleanLens + ", flagged or wrong lengths " + failedLens
                    + (latencyMs > 0 ? "; latency " + latencyMs + " ms" : "") + "; " + tested + " hold(s) tried");
            for (String r : rows) {
                scenario.log("  " + r);
            }
            check(tested > 0, "the sweep ran no lengths");
            check(failedLens.isEmpty(), "hold lengths " + failedLens + " (all within Hold Ticks' 1-20) flagged or were wrong");
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
