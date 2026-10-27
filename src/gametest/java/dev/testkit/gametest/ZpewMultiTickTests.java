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
        if (first != null) {
            throw first;
        }
    }

    static void build(TestServer server) {
        TestMap.on(server).platform(X0, Y - 1, Z0, 4).catchFloor(120).survival().clearInventory()
                .spawn(X0 + 0.5, Z0 + 0.5, -90f).build();
        server.command(String.format(Locale.ROOT, "forceload add %d %d %d %d", X0 - 4, Z0 - 3, X0 + 100, Z0 + 66));
        for (int lane = 0; lane <= SHORT_LANE; lane++) {
            int z = Z0 + 10 * lane;
            int end = lane == SHORT_LANE ? X0 + 16 : X0 + 96;
            server.command(String.format(Locale.ROOT, "testkit fill %d %d %d %d %d %d minecraft:air", X0 - 3, Y, z - 3,
                    X0 + 100, Y + 6, z + 3));
            server.command(String.format(Locale.ROOT, "testkit fill %d %d %d %d %d %d minecraft:stone", X0 - 3, Y - 1,
                    z - 2, end, Y - 1, z + 2));
        }
        // The square for different-look bursts (ZpewLooksTests): 14 x 14 of floor at Z0 + 50.
        server.command(String.format(Locale.ROOT, "testkit fill %d %d %d %d %d %d minecraft:air", X0 - 4, Y, SQ_Z - 4,
                X0 + 14, Y + 6, SQ_Z + 14));
        server.command(String.format(Locale.ROOT, "testkit fill %d %d %d %d %d %d minecraft:stone", X0 - 3, Y - 1,
                SQ_Z - 3, X0 + 12, Y - 1, SQ_Z + 12));
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
                ClientTickEvents.START_CLIENT_TICK.register(client -> TICK.incrementAndGet());
            }
            PacketTrace.tap = p -> {
                if (p instanceof ServerboundUseItemPacket u) {
                    USES.add(new Use(TICK.get(), u.getSequence(), u.getYRot(), u.getXRot()));
                } else if (p instanceof ServerboundMovePlayerPacket m && m.hasRotation()) {
                    ROTS.add(new float[]{TICK.get(), m.getYRot(0), m.getXRot(0)});
                }
            };
        });
    }

    static void setWpt(ClientGameTestContext ctx, int n) {
        ctx.runOnClient(mc -> Mod.set(ZP_CFG, "setWarpsPerTick", n));
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
        if (warps != expectWarps || c1.hits() - c0.hits() != expectWarps || c1.misses() != c0.misses()
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
                // Probe past the setting's 10: twenty vanilla clicks in one tick, the floor 4 ahead (80 blocks of lane).
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
                // load), and clamps to 1..10.
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
                if (persist[0] != 10 || persist[1] != 1 || persist[2] != 4 || persist[3] != 1) {
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
