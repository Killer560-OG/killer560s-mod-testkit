package dev.testkit.gametest;

import dev.testkit.gametest.hx.Hx;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.PacketTrace;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.KeyMapping;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 1027-zpewmt-* - several etherwarps in ONE client tick (killer560, 2026-10-27: "did you find a way to get multiple ether
 * warps done in a single tick?"). Zero Ping Etherwarp's cheat-only Warps Per Tick turns one right click into N use packets
 * in the same tick at the same look; the testkit's server (hx/dungeon/HxAbilities, a MODEL of Hypixel's etherwarp, not
 * Hypixel) casts each from where the one before put him, so a flat lane with the look on the floor 8 ahead moves him 8 N.
 *
 * <p>Every shot proves the feature acted before anything is quoted: the use packets are recorded as they leave with the
 * client tick they left in (all N must be in one tick, the same yaw/pitch, rising sequence numbers), the server's own
 * etherwarp counter must go up by N, the client must receive N position packets at 8, 16, ... 8N, and he must end 8N
 * ahead. GrimAC must say nothing (Scenario.run). Controls: two vanilla clicks queued in one tick with Warps Per Tick 1 (a
 * vanilla client's own way of sending two uses in a tick: {@code while (keyUse.consumeClick()) startUseItem()}), a burst
 * that runs off the end of its floor (the warp that would land nowhere is not sent), and the legit jar (nothing extra).
 * Coordinates of their own (X -7600), so no other scenario shares the lanes.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class ZpewMultiTickTests implements FabricClientGameTest {

    static final String ZP = ZpewCampaignTests.ZP;
    static final String ZP_CFG = ZpewCampaignTests.ZP_CFG;
    static final int Y = 151;
    static final int X0 = -7600;
    static final int Z0 = -7600;
    static final int STEP = 8;
    static final int[] LATENCIES = {0, 50, 150, 300};
    static final int[] COUNTS = {2, 3, 5, 10};
    /** The lane whose floor ends 16 blocks in. */
    static final int SHORT_LANE = 3;

    /** Client ticks, counted at START_CLIENT_TICK (a use sent in a tick carries that tick's number). */
    static final AtomicLong TICK = new AtomicLong();
    static boolean tickHooked;
    /** use_item packets as they leave: tick, sequence, yaw, pitch. */
    record Use(long tick, int seq, float yaw, float pitch) {
    }
    static final List<Use> USES = Collections.synchronizedList(new ArrayList<>());
    /** Movement packets carrying a rotation, as they leave: tick, yaw, pitch. */
    static final List<float[]> ROTS = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!cheatJar(ctx)) {
            Scenario.skipped("1027-zpewmt", "Zero Ping Etherwarp is cheat-only (mod menufix-1102)");
            return;
        }
        AssertionError first = null;
        for (int lat : LATENCIES) {
            try {
                bursts(ctx, lat);
            } catch (AssertionError e) {
                first = first == null ? e : first;
            }
        }
        try {
            diffLook(ctx);
        } catch (AssertionError e) {
            first = first == null ? e : first;
        }
        for (dev.testkit.gametest.SameTick.Act act : List.of(dev.testkit.gametest.SameTick.Act.SWAP)) {
            try {
                wpt20Same(ctx, act);
            } catch (AssertionError e) {
                first = first == null ? e : first;
            }
        }
        for (String v : List.of("plain", "swap", "tp", "double", "bank")) {
            try {
                mwtick(ctx, v);
            } catch (AssertionError e) {
                first = first == null ? e : first;
            }
        }
        if (first != null) {
            throw first;
        }
    }

    /**
     * 1111-mwtick-wpt-* (mod mwtick-1111; killer560 2026-11-11): Multi Warp Mode
     * Tick Split, which must never flag (killer560 2026-11-11: "why would you push it if it flags?"). Warps Per Tick at
     * its top, 20, 20 server warps landing 8 apart, and GrimAC silent on every check. With no Blink bank Tick Split has no
     * tick to spend, so each burst falls back to Burst (the mod counts it, splitShort). Variants (the SAME-TICK rule): {@code
     * swap} (his scroll every tick), {@code tp} (the server teleports him just before each burst, so his accept rides with
     * it), {@code double} (two clicks of his in one tick, Warps Per Tick 10: 20 uses). A container click cannot share a tick
     * with a manual warp (the use key does nothing under a screen); the map chain's same-click case covers it. {@code bank}:
     * Blink Charge on and full (20, covered) before each burst: every warp sits in a banked GrimAC tick of its own on the
     * WIRE (a {@code client_tick_end} between each two uses), all 20 inside ONE real client tick, and nothing falls back.
     */
    static void mwtick(ClientGameTestContext ctx, String variant) {
        String name = "1111-mwtick-wpt-" + variant;
        boolean dbl = variant.equals("double");
        boolean bank = variant.equals("bank");
        int n = dbl ? 10 : 20;
        dev.testkit.gametest.SameTick.Act act = variant.equals("swap") ? dev.testkit.gametest.SameTick.Act.SWAP
                : variant.equals("tp") ? dev.testkit.gametest.SameTick.Act.TP : null;
        Scenario.runExpectingFlags(ctx, name, (server, scenario) -> build(server), (server, scenario) -> {
            Hx hx = null;
            dev.testkit.gametest.SameTick[] st = {null};
            List<String> problems = new ArrayList<>();
            GapMeter meter = null;
            try {
                hx = ZpewCampaignTests.setUp(ctx, 4);
                hx.give(1, ZpewCampaignTests.aotv(4));
                hookTicks(ctx);
                if (bank) {
                    meter = new GapMeter(server);
                }
                String who = ZpewCampaignTests.name(ctx);
                ctx.getInput().holdKey(o -> o.keyShift);
                setWpt(ctx, n);
                ctx.runOnClient(mc -> Mod.set(ZP_CFG, "setTickSplit", true));
                if (bank) {
                    ctx.runOnClient(mc -> {
                        Mod.set("blink.BlinkConfig", "setChargeMode", Mod.enumValue("blink.BlinkConfig$Mode", "TOGGLE"));
                        Mod.set("blink.BlinkConfig", "setChargeTicks", 20);
                        Mod.set("blink.BlinkConfig", "setChatMessages", false);
                        Mod.set("blink.BlinkConfig", "setEnabled", true);
                        Mod.setField("blink.BlinkCharge", "keyHeldForTest", Boolean.TRUE);
                    });
                    ctx.waitTicks(1);
                    ctx.runOnClient(mc -> Mod.setField("blink.BlinkCharge", "keyHeldForTest", Boolean.FALSE));
                }
                if (act != null) {
                    st[0] = ctx.computeOnClient(mc -> dev.testkit.gametest.SameTick.start(act, 1,
                            p -> p instanceof ServerboundUseItemPacket, 0, 1));
                }
                int retries = 0;
                for (int rep = 0; rep < 3; rep++) {
                    int problems0 = problems.size();
                    long banked0 = bank ? fillBank(ctx) : 0;
                    long short0 = splitShort(ctx);
                    Runnable press = dbl ? clicks(2) : () -> ctx.getInput().pressKey(o -> o.keyUse);
                    Runnable fire = () -> {
                        // shot()'s own teleport back to the lane leaves the bank uncovered until it is answered, and an
                        // uncovered bank goes as Burst by design: fire once it has stayed covered for 3 ticks in a row (a
                        // server in-place re-sync, seen on 26.2, can uncover it between the check and the press; max 5 s).
                        for (int i = 0, ok = 0; bank && i < 100 && ok < 3; i++) {
                            ok = Boolean.TRUE.equals(ctx.computeOnClient(mc ->
                                    Mod.staticCall("blink.BlinkCharge", "shotCovered"))) ? ok + 1 : 0;
                            if (ok < 3) {
                                ctx.waitTicks(1);
                            }
                        }
                        ctx.runOnClient(mc -> dev.testkit.harness.WireCapture.start());
                        if (act == dev.testkit.gametest.SameTick.Act.TP) {
                            server.command("execute as " + who + " at @s run tp @s ~ ~ ~");
                            ctx.waitTicks(1);
                        }
                        press.run();
                    };
                    String label = name + " rep " + rep;
                    ownTpOk = act == dev.testkit.gametest.SameTick.Act.TP;
                    try {
                        problems.addAll(shot(ctx, server, hx, scenario, who, 0, label, 1, 4, fire, 20, 20, 20 - (dbl ? 2 : 1),
                                dbl ? 2 : 1)); // his 2nd click waits a tick (PacketOrderN)
                    } finally {
                        ownTpOk = false;
                    }
                    ctx.runOnClient(mc -> dev.testkit.harness.WireCapture.stop());
                    long fellBack = splitShort(ctx) - short0;
                    // bank: every use in a banked tick of its own; else Burst (only the burst's last use is closed alone).
                    List<String> wp = new ArrayList<>();
                    String wire = splitWire(dev.testkit.harness.WireCapture.snapshot(), 20, bank ? 20 : -1, wp, label);
                    problems.addAll(wp);
                    scenario.log(label + ": " + fellBack + " burst(s) fell back to Burst" + (bank ? " (bank " + banked0
                            + " before)" : "") + "; " + countLooks(dev.testkit.harness.WireCapture.snapshot())
                            + " look move(s); wire " + wire);
                    // A bank that a server ping gap uncovered between the check and the press goes as Burst by design (the
                    // mod counts it, no flag): that rep is retried (3 retries in all), a flag or a count never is.
                    if (bank && fellBack != 0 && banked0 >= 19 && retries < 3
                            && problems.subList(problems0, problems.size()).stream().noneMatch(s -> s.contains("GrimAC"))) {
                        retries++;
                        while (problems.size() > problems0) {
                            problems.remove(problems.size() - 1);
                        }
                        scenario.log(label + ": the bank fell back to Burst (a ping gap uncovered it); retry " + retries);
                        rep--;
                        continue;
                    }
                    if (bank ? banked0 >= 19 && fellBack != 0 : fellBack < 1) {
                        problems.add(label + ": " + fellBack + " burst(s) fell back to Burst (bank " + banked0 + ")");
                    }
                }
                if (st[0] != null) {
                    dev.testkit.gametest.SameTick done = st[0];
                    ctx.runOnClient(mc -> done.close());
                    st[0] = null;
                    scenario.log(name + ": " + done.report());
                    if (done.done() == 0 && act != dev.testkit.gametest.SameTick.Act.TP || done.playerWindows() == 0
                            || done.featureWindows() == 0) {
                        problems.add(name + ": his action or the warps never reached the wire (" + done.report() + ")");
                    }
                }
            } finally {
                if (meter != null) {
                    meter.stop();
                }
                if (st[0] != null) {
                    dev.testkit.gametest.SameTick open = st[0];
                    ctx.runOnClient(mc -> open.close());
                }
                ctx.runOnClient(mc -> {
                    dev.testkit.harness.WireCapture.stop();
                    Mod.set(ZP_CFG, "setTickSplit", false);
                    if (bank) {
                        Mod.setField("blink.BlinkCharge", "keyHeldForTest", null);
                        Mod.staticCall("blink.BlinkCharge", "reset", "test teardown");
                        Mod.set("blink.BlinkConfig", "setEnabled", false);
                    }
                });
                setWpt(ctx, 1);
                ZpewCampaignTests.tearDown(ctx, hx);
            }
            ctx.waitTicks(30); // a late GrimAC line lands before the flags are read
            long gap = meter == null ? -1 : meter.stop();
            problems.addAll(noFlags(scenario, name, gap));
            if (!problems.isEmpty()) {
                throw new AssertionError(name + ": " + problems);
            }
        });
    }

    /** Waits (max 6 s) for Blink Charge's bank to refill to 20 and be covered; returns the bank. */
    static long fillBank(ClientGameTestContext ctx) {
        for (int i = 0; i < 240; i++) {
            boolean ok = ctx.computeOnClient(mc -> ((Number) Mod.staticCall("blink.BlinkCharge", "banked")).longValue() >= 20
                    && Boolean.TRUE.equals(Mod.staticCall("blink.BlinkCharge", "shotCovered")));
            if (ok) {
                break;
            }
            ctx.waitTicks(1);
        }
        // A bank GrimAC has not covered yet is not spent (BlinkCharge.splitTick): count it as none.
        return ctx.computeOnClient(mc -> Boolean.TRUE.equals(Mod.staticCall("blink.BlinkCharge", "shotCovered"))
                ? ((Number) Mod.staticCall("blink.BlinkCharge", "banked")).longValue() : 0L);
    }

    /** Moves on the wire that carry a look (rot / pos_rot). */
    static int countLooks(List<dev.testkit.harness.WireCapture.Sent> wire) {
        int n = 0;
        for (dev.testkit.harness.WireCapture.Sent x : wire) {
            n += x.type().startsWith("minecraft:move_player") && x.type().contains("rot") ? 1 : 0;
        }
        return n;
    }

    static long splitShort(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> ((Number) dev.testkit.gametest.Ap3RuntimeTests.staticField(
                "com.killer560.hub.zpew.ZeroPingEtherwarp", "splitShort")).longValue());
    }

    /**
     * Tick Split on the wire: each use must be closed by a {@code client_tick_end} before the next use (its own GrimAC
     * tick). Adds a problem when {@code want} uses were not each in a tick of their own.
     * @return a short form, "U r T | U r T | ..." (U use, r move with a look, p move without, T tick end, A accept)
     */
    static String splitWire(List<dev.testkit.harness.WireCapture.Sent> wire, int want, int wantAlone, List<String> problems,
                            String label) {
        StringBuilder b = new StringBuilder();
        int uses = 0;
        int alone = 0;
        boolean open = false;
        for (dev.testkit.harness.WireCapture.Sent x : wire) {
            String t = x.type();
            if (t.equals("minecraft:use_item")) {
                uses++;
                open = true;
                b.append(uses == 1 ? "U" : " | U");
            } else if (t.equals("minecraft:client_tick_end")) {
                if (open) {
                    alone++;
                }
                open = false;
                b.append(" T");
            } else if (t.startsWith("minecraft:move_player")) {
                b.append(t.contains("rot") ? " r" : " p");
            } else if (t.equals("minecraft:accept_teleportation")) {
                b.append(" A");
            } else if (t.equals("minecraft:set_carried_item")) {
                b.append(" S");
            }
        }
        if (uses != want || wantAlone >= 0 && alone != wantAlone) {
            problems.add(label + ": " + uses + " use(s) on the wire, " + alone + " closed by a tick end before the next use, "
                    + "expected " + want + " and " + wantAlone + " | " + b);
        }
        String s = b.toString();
        return s.length() > 400 ? s.substring(0, 400) + " ..." : s;
    }

    /** Tick Split must never flag: logs GrimAC's lines by check and returns a problem per line. */
    static List<String> noFlags(Scenario scenario, String name, long gapMs) {
        // gapMs >= 0 (the Blink Charge variant): the owner accepts a TimerLimit ONLY from a lag spike of 900 ms or more in the
        // middle of a Blink Hold (killer560 2026-11-04, "Keep holding"). The case measured the longest stretch the server or
        // the client stood still (GapMeter); a TimerLimit counts as that exception only if that gap was >= 900 ms, and the
        // measured gap is logged. Every other line, and a TimerLimit without the proof, fails the case.
        boolean proven = gapMs >= 900;
        List<String> other = new ArrayList<>();
        java.util.Map<String, Integer> byCheck = new java.util.TreeMap<>();
        for (String f : scenario.flags()) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("failed ([A-Za-z]+)").matcher(f);
            String check = m.find() ? m.group(1) : "?";
            byCheck.merge(check, 1, Integer::sum);
            if (proven && check.equals("TimerLimit")) {
                scenario.log(name + ": TimerLimit accepted, measured stall " + gapMs + " ms (>= 900, the owner's lag-spike exception)");
                continue;
            }
            other.add(name + ": GrimAC " + f);
        }
        System.out.println("[mwtick] " + name + ": GrimAC lines by check " + byCheck);
        scenario.log(name + ": GrimAC lines by check " + byCheck + " (Tick Split may not flag)"
                + (gapMs >= 0 ? "; longest server/client stall " + gapMs + " ms" : ""));
        return other;
    }

    /** Longest time the server tick or the client tick stood still while a bank case ran: the proof of a lag spike. */
    static final class GapMeter {
        private volatile boolean run = true;
        private volatile long max;
        private final Thread t;

        GapMeter(net.minecraft.server.MinecraftServer server) {
            t = new Thread(() -> {
                int sTick = -1;
                int cTick = -1;
                long sAt = System.currentTimeMillis();
                long cAt = sAt;
                while (run) {
                    long now = System.currentTimeMillis();
                    int s = server.getTickCount();
                    var pl = net.minecraft.client.Minecraft.getInstance().player;
                    int c = pl == null ? -1 : pl.tickCount;
                    if (s != sTick) {
                        sTick = s;
                        sAt = now;
                    }
                    if (c != cTick) {
                        cTick = c;
                        cAt = now;
                    }
                    max = Math.max(max, Math.max(now - sAt, now - cAt));
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }, "mwtick-gap-meter");
            t.setDaemon(true);
            t.start();
        }

        long stop() {
            run = false;
            return max;
        }
    }

    /**
     * 1111-zpew-wpt20-same-swap (zpewunify-1111; the SAME-TICK GRIM CASES rule): Warps Per Tick at its top,
     * 20, three times, while HIS action goes into every client tick ({@link dev.testkit.gametest.SameTick}): a scroll between
     * two AOTVs (slots 0 and 1); a dig cannot share a tick with a use (vanilla's startUseItem returns while destroying, so
     * DIG sent 0 uses), and his own use is 1105-zpew-aimed-same-use. Every burst must send all 20 uses and land 20 warps, a
     * GrimAC tick must carry the burst with his packet, and GrimAC says nothing (Scenario.run). Cheat jar only.
     */
    static void wpt20Same(ClientGameTestContext ctx, dev.testkit.gametest.SameTick.Act act) {
        String name = "1111-zpew-wpt20-same-" + act.name().toLowerCase(Locale.ROOT);
        Scenario.run(ctx, name, (server, scenario) -> build(server), (server, scenario) -> {
            Hx hx = null;
            dev.testkit.gametest.SameTick[] st = {null};
            List<String> problems = new ArrayList<>();
            try {
                hx = ZpewCampaignTests.setUp(ctx, 4);
                hx.give(1, ZpewCampaignTests.aotv(4));
                hookTicks(ctx);
                String who = ZpewCampaignTests.name(ctx);
                ctx.getInput().holdKey(o -> o.keyShift);
                setWpt(ctx, 20);
                st[0] = ctx.computeOnClient(mc -> dev.testkit.gametest.SameTick.start(act, 1,
                        p -> p instanceof ServerboundUseItemPacket, 0, 1));
                for (int rep = 0; rep < 3; rep++) {
                    problems.addAll(shot(ctx, server, hx, scenario, who, 0, "wpt-20 same-" + act + " rep " + rep, 1, 4,
                            () -> ctx.getInput().pressKey(o -> o.keyUse), 20, 20, 19));
                }
                dev.testkit.gametest.SameTick done = st[0];
                ctx.runOnClient(mc -> done.close());
                st[0] = null;
                scenario.log(name + ": " + done.report());
                String why = done.verdict("warps per tick");
                if (why != null) {
                    problems.add(why);
                }
            } finally {
                if (st[0] != null) {
                    dev.testkit.gametest.SameTick open = st[0];
                    ctx.runOnClient(mc -> open.close());
                }
                setWpt(ctx, 1);
                ZpewCampaignTests.tearDown(ctx, hx);
            }
            if (!problems.isEmpty()) {
                throw new AssertionError(name + ": " + problems);
            }
        });
    }

    static void build(TestServer server) {
        TestMap.on(server).platform(X0, Y - 1, Z0, 4).catchFloor(120).survival().clearInventory()
                .spawn(X0 + 0.5, Z0 + 0.5, -90f).build();
        server.command(String.format(Locale.ROOT, "forceload add %d %d %d %d", X0 - 4, Z0 - 3, X0 + 100, Z0 + 74));
        for (int lane = 0; lane <= SHORT_LANE; lane++) {
            int z = Z0 + 10 * lane;
            int end = lane == SHORT_LANE ? X0 + 16 : X0 + 96;
            server.command(String.format(Locale.ROOT, "testkit fill %d %d %d %d %d %d minecraft:air", X0 - 3, Y, z - 3,
                    X0 + 100, Y + 6, z + 3));
            server.command(String.format(Locale.ROOT, "testkit fill %d %d %d %d %d %d minecraft:stone", X0 - 3, Y - 1,
                    z - 2, end, Y - 1, z + 2));
        }
        // The floor for different-look bursts (ZpewLooksTests): 16 x 24 at Z0 + 50.
        server.command(String.format(Locale.ROOT, "testkit fill %d %d %d %d %d %d minecraft:air", X0 - 4, Y, SQ_Z - 4,
                X0 + 14, Y + 6, SQ_Z + 22));
        server.command(String.format(Locale.ROOT, "testkit fill %d %d %d %d %d %d minecraft:stone", X0 - 3, Y - 1,
                SQ_Z - 3, X0 + 12, Y - 1, SQ_Z + 20));
        server.command("effect give @a minecraft:resistance infinite 255 true");
        server.command("effect give @a minecraft:fire_resistance infinite 0 true");
        server.sync(120);
    }

    /** The different-look square's start row (feet at X0 + 0.5, SQ_Z + 0.5). */
    static final int SQ_Z = Z0 + 50;

    static void hookTicks(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            if (!tickHooked) {
                tickHooked = true;
                // counted at the tick END: the mod replays a held-back click at the next tick START, ahead of a START hook
                ClientTickEvents.END_CLIENT_TICK.register(client -> TICK.incrementAndGet());
            }
            PacketTrace.tap = p -> {
                if (p instanceof ServerboundUseItemPacket u) {
                    USES.add(new Use(TICK.get(), dev.testkit.compat.McPackets.useItemSequence(u), dev.testkit.compat.McPackets.useItemYRot(u),
                            dev.testkit.compat.McPackets.useItemXRot(u)));
                } else if (p instanceof ServerboundMovePlayerPacket m && m.hasRotation()) {
                    ROTS.add(new float[]{TICK.get(), m.getYRot(0), m.getXRot(0)});
                }
            };
        });
    }

    static void setWpt(ClientGameTestContext ctx, int n) {
        ctx.runOnClient(mc -> {
            multiWarps(true);
            Mod.set(ZP_CFG, "setWarpsPerTick", n);
        });
    }

    /** The Multi Warps switch over Warps Per Tick and Aimed Warps (mod doc-menu-1101); a jar from before it has none. */
    static void multiWarps(boolean on) {
        Object c = Mod.cfg(ZP_CFG);
        boolean has = false;
        for (java.lang.reflect.Method m : c.getClass().getMethods()) {
            has |= m.getName().equals("setMultiWarps");
        }
        if (has) {
            Mod.call(c, "setMultiWarps", on);
        }
    }

    /** At START of the next tick, {@code n} vanilla clicks of the use key: handleKeybinds turns each into a use, this tick. */
    static Runnable clicks(int n) {
        return () -> ZpewCampaignTests.atTickStart = () -> {
            var mc = net.minecraft.client.Minecraft.getInstance();
            for (int i = 0; i < n; i++) {
                KeyMapping.click(mc.options.keyUse.getDefaultKey()); // fresh test options: the bound key is the default
            }
        };
    }

    static boolean cheatJar(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            try {
                return Mod.cls("BuildVariant").getField("CHEAT_FEATURES_ENABLED").getBoolean(null);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("BuildVariant not readable: " + e);
            }
        });
    }

    /**
     * One shot on {@code lane}: aim at the floor 8 ahead, fire with {@code fire}, wait for every landing, then judge.
     * {@code expectWarps}/{@code expectUses} are what must happen; returns the problems found.
     */
    static List<String> shot(ClientGameTestContext ctx, TestServer server, Hx hx, Scenario scenario, String who, int lat,
                             String label, int lane, Runnable fire, int expectUses, int expectWarps, int expectExtra) {
        return shot(ctx, server, hx, scenario, who, lat, label, lane, STEP, fire, expectUses, expectWarps, expectExtra,
                expectUses > 1 ? 1 : expectUses);
    }

    static List<String> shot(ClientGameTestContext ctx, TestServer server, Hx hx, Scenario scenario, String who, int lat,
                             String label, int lane, int step, Runnable fire, int expectUses, int expectWarps,
                             int expectExtra) {
        return shot(ctx, server, hx, scenario, who, lat, label, lane, step, fire, expectUses, expectWarps, expectExtra,
                expectUses > 1 ? 1 : expectUses);
    }

    /** Set by 1111-mwtick-wpt-tp: the server's own teleport before each burst is not a warp. */
    static boolean ownTpOk;

    static List<String> shot(ClientGameTestContext ctx, TestServer server, Hx hx, Scenario scenario, String who, int lat,
                             String label, int lane, int step, Runnable fire, int expectUses, int expectWarps,
                             int expectExtra, int expectTicks) {
        List<String> problems = new ArrayList<>();
        int z = Z0 + 10 * lane;
        ZpewCampaignTests.tpTo(ctx, server, who, X0 + 0.5, Y, z + 0.5, true);
        float[] a = ZpewCampaignTests.aim(new Vec3(X0 + 0.5, Y + 1.27, z + 0.5), new Vec3(X0 + step + 0.5, Y, z + 0.5));
        ZpewCampaignTests.look(ctx, a[0], a[1]);
        ctx.waitTicks(3);
        ZpewCampaignTests.Counters c0 = ZpewCampaignTests.counters(ctx, hx);
        long extra0 = ZpewCampaignTests.num(ctx, "extraWarpsSent");
        ctx.runOnClient(mc -> {
            USES.clear();
            ROTS.clear();
        });
        ZpewCampaignTests.captureStart(ctx);
        fire.run();
        ctx.waitTicks(30 + lat / 25 + 2 * expectWarps);
        ZpewCampaignTests.captureStop(ctx);
        ZpewCampaignTests.Counters c1 = ZpewCampaignTests.counters(ctx, hx);
        long extra = ZpewCampaignTests.num(ctx, "extraWarpsSent") - extra0;
        Vec3 end = ctx.computeOnClient(mc -> mc.player.position());
        double endCam = ctx.computeOnClient(mc -> ZpewCampaignTests.camera(mc).distanceTo(ZpewCampaignTests.playerEye(mc)));
        List<Use> uses;
        synchronized (USES) {
            uses = new ArrayList<>(USES);
        }
        List<float[]> rots;
        synchronized (ROTS) {
            rots = new ArrayList<>(ROTS);
        }
        List<Vec3> tps;
        synchronized (ZpewCampaignTests.TELEPORTS) {
            tps = new ArrayList<>(ZpewCampaignTests.TELEPORTS);
        }
        // The tp variant's own teleport (in place, or off the lane) is not a warp: drop it and allow a ZPEW miss for it.
        int setbacks = 0;
        if (ownTpOk) {
            for (int k = tps.size() - 1; k >= 0; k--) {
                double prevX = k == 0 ? X0 + 0.5 : tps.get(k - 1).x;
                double dx = tps.get(k).x - X0;
                if (Math.abs(tps.get(k).x - prevX) < 0.01 || dx < 0 || dx > step * expectWarps + 0.51) {
                    tps.remove(k);
                    setbacks++;
                }
            }
        }
        // 26.2 only: after a burst the client settles 0.05 onto the floor (y 151.05 -> 151.00) and the server answers with
        // ONE in-place teleport (same x/z, |dy| <= 0.1) AFTER the last landing. It is the server's re-sync, not a warp (the
        // mod sent every use, every warp hit, GrimAC silent): dropped only when it is exactly the one extra last packet.
        int np = tps.size();
        if (np == expectWarps + 1 && np > 1) {
            Vec3 a1 = tps.get(np - 2);
            Vec3 b1 = tps.get(np - 1);
            if (Math.abs(a1.x - b1.x) < 0.01 && Math.abs(a1.z - b1.z) < 0.01 && Math.abs(a1.y - b1.y) <= 0.1) {
                tps.remove(np - 1);
                scenario.log(label + ": dropped one in-place re-sync teleport after the last landing (dy "
                        + String.format(Locale.ROOT, "%.2f", b1.y - a1.y) + ")");
            }
        }
        TreeSet<Long> ticks = new TreeSet<>();
        boolean sameLook = true;
        boolean seqRising = true;
        for (int i = 0; i < uses.size(); i++) {
            ticks.add(uses.get(i).tick());
            if (i > 0) {
                sameLook &= uses.get(i).yaw() == uses.get(0).yaw() && uses.get(i).pitch() == uses.get(0).pitch();
                seqRising &= uses.get(i).seq() > uses.get(i - 1).seq();
            }
        }
        // The movement packet of the uses' tick (if it carried a rotation) must report the uses' look: BadPacketsJ's rule.
        String rotLine = "no rotation packet in the uses' tick";
        if (!uses.isEmpty()) {
            for (float[] r : rots) {
                if ((long) r[0] == uses.get(0).tick()) {
                    rotLine = String.format(Locale.ROOT, "rotation in that tick %.4f/%.4f (%s)", r[1], r[2],
                            r[1] == uses.get(0).yaw() && r[2] == uses.get(0).pitch() ? "same" : "DIFFERENT");
                    if (!(r[1] == uses.get(0).yaw() && r[2] == uses.get(0).pitch())) {
                        problems.add(label + ": the tick's rotation differs from the uses'");
                    }
                }
            }
        }
        StringBuilder landed = new StringBuilder();
        for (Vec3 t : tps) {
            landed.append(String.format(Locale.ROOT, " %.1f", t.x - X0));
        }
        int warps = c1.warps() - c0.warps();
        String line = String.format(Locale.ROOT, "%s: %d use(s) in %d tick(s) %s, same look %s, seq rising %s, %s; extra sent %d;"
                        + " server warps %d (emu misses %d); position packets at x+[%s ]; zpew p%d h%d m%d t%d nl%d;"
                        + " end x+%.2f, end cam-vs-eye %.3f; max uses in one tick ever %d", label, uses.size(), ticks.size(),
                ticks, sameLook, seqRising, rotLine, extra, warps, c1.emuMisses() - c0.emuMisses(), landed,
                c1.preds() - c0.preds(), c1.hits() - c0.hits(), c1.misses() - c0.misses(), c1.timeouts() - c0.timeouts(),
                c1.noLanding() - c0.noLanding(), end.x - X0, endCam, ZpewCampaignTests.num(ctx, "maxUsesInOneTick"));
        scenario.log(line);
        // Asserted in this order: it acted (uses, server warps, distance) before anything about cleanliness.
        if (uses.size() != expectUses) {
            problems.add(label + ": expected " + expectUses + " use(s), sent " + uses.size() + " | " + line);
        }
        if (ticks.size() != expectTicks) {
            problems.add(label + ": the uses went in " + ticks.size() + " tick(s), expected " + expectTicks + " | " + line);
        }
        if (!sameLook || !seqRising) {
            problems.add(label + ": look changed or sequence not rising | " + line);
        }
        if (extra != expectExtra) {
            problems.add(label + ": expected " + expectExtra + " extra warp(s), the mod sent " + extra + " | " + line);
        }
        if (warps != expectWarps || (setbacks == 0 && c1.hits() - c0.hits() != expectWarps) || c1.misses() - c0.misses() > setbacks
                || c1.timeouts() != c0.timeouts()) {
            problems.add(label + ": expected " + expectWarps + " server warp(s) all predicted | " + line);
        }
        if (tps.size() != expectWarps) {
            problems.add(label + ": " + tps.size() + " position packet(s), expected " + expectWarps + " | " + line);
        } else {
            for (int k = 0; k < tps.size(); k++) {
                if (Math.abs(tps.get(k).x - (X0 + step * (k + 1) + 0.5)) > 0.01) {
                    problems.add(label + ": warp " + (k + 1) + " landed at x+" + (tps.get(k).x - X0) + ", expected x+"
                            + (step * (k + 1) + 0.5) + " (not cast from the previous landing) | " + line);
                }
            }
        }
        if (Math.abs(end.x - (X0 + step * expectWarps + 0.5)) > 0.01 || endCam > 0.05) {
            problems.add(label + ": ended at x+" + (end.x - X0) + " cam-vs-eye " + endCam + " | " + line);
        }
        return problems;
    }

    static void bursts(ClientGameTestContext ctx, int lat) {
        Scenario.run(ctx, "1027-zpewmt-" + lat, (server, scenario) -> build(server), (server, scenario) -> {
            scenario.assertDetectorWorks();
            Hx hx = null;
            List<String> problems = new ArrayList<>();
            try {
                hx = ZpewCampaignTests.setUp(ctx, 4);
                hookTicks(ctx);
                String who = ZpewCampaignTests.name(ctx);
                boolean cheat = cheatJar(ctx);
                scenario.log("jar: " + (cheat ? "cheat" : "legit") + ", latency " + lat + " ms");
                ctx.getInput().holdKey(o -> o.keyShift);
                ZpewCampaignTests.latency(ctx, lat);
                // Control: Warps Per Tick 1, two vanilla clicks queued in ONE tick - the client's own two-uses-a-tick.
                setWpt(ctx, 1);
                problems.addAll(shot(ctx, server, hx, scenario, who, lat, "vanilla-2-clicks", 0, clicks(2), 2, 2, 0));
                // Twenty vanilla clicks in one tick, the floor 4 ahead (80 blocks of lane): the control for wpt-20.
                problems.addAll(shot(ctx, server, hx, scenario, who, lat, "vanilla-20-clicks", 1, 4, clicks(20), 20, 20, 0));
                for (int rep = 0; rep < 2; rep++) {
                    for (int n : COUNTS) {
                        setWpt(ctx, n);
                        int lane = n == 10 ? 1 : (n == 5 ? 2 : 0);
                        int expect = cheat ? n : 1;
                        problems.addAll(shot(ctx, server, hx, scenario, who, lat, "wpt-" + n + " rep " + rep, lane,
                                () -> ctx.getInput().pressKey(o -> o.keyUse), expect, expect, expect - 1));
                    }
                }
                // Warps Per Tick at its new top, 20 (zpewunify-1111: "why a limit of 10?"): the floor 4 ahead, 80 blocks.
                for (int rep = 0; rep < 2; rep++) {
                    setWpt(ctx, 20);
                    problems.addAll(shot(ctx, server, hx, scenario, who, lat, "wpt-20 rep " + rep, 1, 4,
                            () -> ctx.getInput().pressKey(o -> o.keyUse), 20, 20, 19));
                }
                // Multi Warps is its own feature (zpewunify-1111: "if i have zero ping off it should still show multiwarp"):
                // with Zero Ping Etherwarp OFF, and with it on but Works For Manual off, Warps Per Tick 3 still sends 3 and
                // the camera never leaves him (the shot's cam-vs-eye check).
                for (String mode : new String[]{"zpew off", "works for manual off"}) {
                    setWpt(ctx, 3);
                    ctx.runOnClient(mc -> {
                        Mod.set(ZP_CFG, "setEnabled", !mode.equals("zpew off"));
                        Mod.call(Mod.cfg(ZP_CFG), "setWorksForByName", "MANUAL", !mode.startsWith("works"));
                    });
                    long cam0 = ZpewCampaignTests.num(ctx, "cameraFrames");
                    problems.addAll(shot(ctx, server, hx, scenario, who, lat, "wpt-3 " + mode, 0,
                            () -> ctx.getInput().pressKey(o -> o.keyUse), 3, 3, 2));
                    long cam = ZpewCampaignTests.num(ctx, "cameraFrames") - cam0;
                    if (cam != 0) {
                        problems.add("wpt-3 " + mode + ": the camera was moved for " + cam + " frame(s)");
                    }
                    ctx.runOnClient(mc -> {
                        Mod.set(ZP_CFG, "setEnabled", true);
                        Mod.call(Mod.cfg(ZP_CFG), "setWorksForByName", "MANUAL", true);
                    });
                }
                // Two bursts on consecutive ticks: the second is cast from the first burst's last PREDICTED landing (the
                // server has answered none of it yet at 150/300 ms), so 3 + 3 warps walk on to 48.
                setWpt(ctx, 3);
                int two = cheat ? 6 : 2;
                problems.addAll(shot(ctx, server, hx, scenario, who, lat, "wpt-3 two bursts", 0, STEP, () -> {
                    ctx.getInput().pressKey(o -> o.keyUse);
                    ctx.getInput().pressKey(o -> o.keyUse);
                }, two, two, cheat ? 4 : 0, 2));
                // Left Click Etherwarp is his own warp too (cheat build only).
                if (cheat) {
                    ctx.runOnClient(mc -> Mod.set(ZpewCampaignTests.LC_CFG, "setEnabled", true));
                    try {
                        problems.addAll(shot(ctx, server, hx, scenario, who, lat, "wpt-3 left click etherwarp", 0,
                                () -> ctx.getInput().pressKey(o -> o.keyAttack), 3, 3, 2));
                    } finally {
                        ctx.runOnClient(mc -> Mod.set(ZpewCampaignTests.LC_CFG, "setEnabled", false));
                    }
                }
                // Off the end: the floor ends 16 in, so 2 land and the 3rd (aimed at nothing) is never sent.
                setWpt(ctx, 5);
                int cutUses = cheat ? 2 : 1;
                problems.addAll(shot(ctx, server, hx, scenario, who, lat, "wpt-5 short floor", SHORT_LANE,
                        () -> ctx.getInput().pressKey(o -> o.keyUse), cutUses, cutUses, cutUses - 1));
            } finally {
                ZpewCampaignTests.atTickStart = null;
                ctx.runOnClient(mc -> Mod.set(ZP_CFG, "setWarpsPerTick", 1));
                ZpewCampaignTests.tearDown(ctx, hx);
            }
            check(problems.isEmpty(), "zpewmt " + lat + " ms: " + problems);
        });
    }

    /**
     * 1027-zpewmt-difflook: the control for WHY the burst keeps one look. Two uses in one tick sent by the harness (not the
     * mod) at two different pitches must draw GrimAC BadPacketsJ, and the same pair at one pitch must not. Runs once, 0 ms.
     */
    static void diffLook(ClientGameTestContext ctx) {
        Scenario.runExpectingFlags(ctx, "1027-zpewmt-difflook", (server, scenario) -> build(server), (server, scenario) -> {
            scenario.assertDetectorWorks();
            Hx hx = null;
            List<String> problems = new ArrayList<>();
            try {
                hx = ZpewCampaignTests.setUp(ctx, 4);
                hookTicks(ctx);
                String who = ZpewCampaignTests.name(ctx);
                // Warps Per Tick survives a save and a reload from disk (ProfileManager.reloadAllConfigs calls the same
                // load), and clamps to 1..20.
                int[] persist = ctx.computeOnClient(mc -> {
                    Mod.call(Mod.cfg(ZP_CFG), "setWarpsPerTick", 99);
                    int high = (Integer) Mod.call(Mod.cfg(ZP_CFG), "getWarpsPerTick");
                    Mod.call(Mod.cfg(ZP_CFG), "setWarpsPerTick", 0);
                    int low = (Integer) Mod.call(Mod.cfg(ZP_CFG), "getWarpsPerTick");
                    Mod.call(Mod.cfg(ZP_CFG), "setWarpsPerTick", 4);
                    Mod.call(Mod.cfg(ZP_CFG), "save");
                    Mod.staticCall(ZP_CFG, "load");
                    int reloaded = (Integer) Mod.call(Mod.cfg(ZP_CFG), "getWarpsPerTick");
                    Mod.call(Mod.cfg(ZP_CFG), "setWarpsPerTick", 1);
                    Mod.call(Mod.cfg(ZP_CFG), "save");
                    Mod.staticCall(ZP_CFG, "load");
                    return new int[]{high, low, reloaded, (Integer) Mod.call(Mod.cfg(ZP_CFG), "getWarpsPerTick")};
                });
                scenario.log(String.format(Locale.ROOT, "persist: set 99 -> %d, set 0 -> %d, saved 4 reloaded %d, saved 1 "
                        + "reloaded %d", persist[0], persist[1], persist[2], persist[3]));
                if (persist[0] != 20 || persist[1] != 1 || persist[2] != 4 || persist[3] != 1) {
                    problems.add("Warps Per Tick did not clamp or persist: " + java.util.Arrays.toString(persist));
                }
                // setUp's settings again (the reload read them from disk)
                ctx.runOnClient(mc -> {
                    Mod.set(ZP_CFG, "setEnabled", true);
                    Mod.set(ZP_CFG, "setChain", true);
                });
                setWpt(ctx, 1);
                ctx.getInput().holdKey(o -> o.keyShift);
                ctx.waitTicks(5);
                float[] a = ZpewCampaignTests.aim(new Vec3(X0 + 0.5, Y + 1.27, Z0 + 0.5), new Vec3(X0 + 8.5, Y, Z0 + 0.5));
                float[] b = ZpewCampaignTests.aim(new Vec3(X0 + 8.5, Y + 1.32, Z0 + 0.5), new Vec3(X0 + 12.5, Y, Z0 + 0.5));
                for (boolean differ : new boolean[]{false, true}) {
                    ZpewCampaignTests.tpTo(ctx, server, who, X0 + 0.5, Y, Z0 + 0.5, true);
                    ZpewCampaignTests.look(ctx, a[0], a[1]);
                    ctx.waitTicks(3);
                    server.mark();
                    int w0 = ZpewCampaignTests.counters(ctx, hx).warps();
                    ctx.runOnClient(mc -> USES.clear());
                    float p2 = differ ? b[1] : a[1];
                    ZpewCampaignTests.atTickStart = () -> {
                        var mc = net.minecraft.client.Minecraft.getInstance();
                        // vanilla's sequenced send (MultiPlayerGameMode.startPrediction), reached by reflection
                        try {
                            var getter = net.minecraft.client.multiplayer.ClientLevel.class
                                    .getDeclaredMethod("getBlockStatePredictionHandler");
                            getter.setAccessible(true);
                            var handler = (net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler)
                                    getter.invoke(mc.level);
                            for (float pitch : new float[]{a[1], p2}) {
                                try (var h = handler.startPredicting()) {
                                    mc.getConnection().send(new ServerboundUseItemPacket(
                                            net.minecraft.world.InteractionHand.MAIN_HAND, h.currentSequence(),
                                            mc.player.getYRot(), pitch));
                                }
                            }
                        } catch (ReflectiveOperationException e) {
                            throw new IllegalStateException(e);
                        }
                    };
                    ctx.waitTicks(30);
                    int warps = ZpewCampaignTests.counters(ctx, hx).warps() - w0;
                    List<String> flags = scenario.flags();
                    boolean j = flags.stream().anyMatch(f -> f.contains("BadPacketsJ"));
                    scenario.log(String.format(Locale.ROOT, "%s looks: %d use(s) in tick(s) %s, %d server warp(s), flags %s",
                            differ ? "different" : "same", USES.size(), USES.stream().map(Use::tick).distinct().toList(),
                            warps, flags));
                    if (USES.size() != 2 || USES.stream().map(Use::tick).distinct().count() != 1 || warps != 2) {
                        problems.add((differ ? "different" : "same") + ": the pair did not go out in one tick and land twice");
                    }
                    if (differ != j) {
                        problems.add((differ ? "different looks did not draw" : "the same look drew") + " BadPacketsJ: " + flags);
                    }
                }
            } finally {
                ZpewCampaignTests.atTickStart = null;
                ZpewCampaignTests.tearDown(ctx, hx);
            }
            check(problems.isEmpty(), "zpewmt difflook: " + problems);
        });
    }

    static void check(boolean ok, String why) {
        if (!ok) {
            throw new AssertionError(why);
        }
    }
}
