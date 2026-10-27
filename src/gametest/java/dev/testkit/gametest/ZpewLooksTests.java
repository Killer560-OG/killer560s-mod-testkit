package dev.testkit.gametest;

import dev.testkit.gametest.hx.Hx;
import dev.testkit.gametest.mod.Mod;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * 1027-zpewlk-* - etherwarps at DIFFERENT looks inside one client tick (killer560, 2026-10-27: "Can you make it etherwarp
 * different directions during the same tick?"). On a 16 x 24 floor each warp of a burst goes 8 blocks a new way (+x, +z,
 * -x, +z, +x), cast by the server from the landing before, so every landing is known in advance.
 *
 * <p>Shapes measured against GrimAC 2.3.74, each shot on its own server mark so its flags are its own:
 * <ul>
 *   <li>{@code rsa} (harness): n uses at n looks, nothing between - RSA's Auto Routes shape. Expected BadPacketsJ.</li>
 *   <li>{@code before} (harness): look packet THEN use, per warp (how a 1.8 server picks up a look). Expected BadPacketsJ:
 *       the check wants each use's look reported by the NEXT tick packet.</li>
 *   <li>{@code after} (harness, no bank): use then a look-only packet carrying that look, the last use reported by the
 *       tick's own move. Every look packet is an extra tick for Timer; the flags are recorded, not demanded.</li>
 *   <li>{@code aimed} (the mod: ZPEW Aimed Warps with a Blink Charge bank): n presses queued in one tick at n looks; the mod
 *       sends each in a banked tick. Must be clean, every warp landing where planned.</li>
 *   <li>{@code aimed-nobank}: the same presses with Charge off - the mod must leave them to vanilla (one look).</li>
 * </ul>
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class ZpewLooksTests implements FabricClientGameTest {

    static final String ZP = ZpewCampaignTests.ZP;
    static final String ZP_CFG = ZpewCampaignTests.ZP_CFG;
    static final int X0 = ZpewMultiTickTests.X0;
    static final int Y = ZpewMultiTickTests.Y;
    static final int SQ_Z = ZpewMultiTickTests.SQ_Z;
    // A zigzag, not a square: five turns of a whole circle put a 360 between the first press and the last, and a client
    // whose look jumps 360 in one move is GrimAC AimModulo360 with or without the mod (1027-zpewlk n=5, first runs).
    static final int[][] DIRS = {{1, 0}, {0, 1}, {-1, 0}, {0, 1}, {1, 0}};
    static final int[] LATENCIES = {0, 50, 150, 300};

    @Override
    public void runTest(ClientGameTestContext ctx) {
        AssertionError first = null;
        for (int lat : LATENCIES) {
            try {
                looks(ctx, lat);
            } catch (AssertionError e) {
                first = first == null ? e : first;
            }
        }
        if (first != null) {
            throw first;
        }
    }

    /** Feet after k warps of the square (k = 0: the start). */
    static Vec3 feet(int k) {
        double x = X0 + 0.5;
        double z = SQ_Z + 0.5;
        for (int i = 0; i < k; i++) {
            x += 8 * DIRS[i][0];
            z += 8 * DIRS[i][1];
        }
        return new Vec3(x, k == 0 ? Y : Y + 0.05, z);
    }

    /** The look (absolute yaw, pitch) for warp k (1-based) from warp k-1's landing to the floor 8 ahead. */
    static float[] lookFor(int k) {
        Vec3 from = feet(k - 1);
        Vec3 to = feet(k);
        return ZpewCampaignTests.aim(from.add(0, 1.27, 0), new Vec3(to.x, Y, to.z));
    }

    /** {@code yaw} as a turn from his running yaw (never an absolute jump across the seam: AimModulo360). */
    static float rel(Minecraft mc, float yaw) {
        return mc.player.getYRot() + Mth.wrapDegrees(yaw - mc.player.getYRot());
    }

    static void use(Minecraft mc, float yaw, float pitch) {
        try {
            var getter = net.minecraft.client.multiplayer.ClientLevel.class.getDeclaredMethod("getBlockStatePredictionHandler");
            getter.setAccessible(true);
            var handler = (net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler) getter.invoke(mc.level);
            try (var h = handler.startPredicting()) {
                mc.getConnection().send(new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, h.currentSequence(), yaw, pitch));
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static void rot(Minecraft mc, float yaw, float pitch) {
        mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(yaw, pitch, mc.player.onGround(), mc.player.horizontalCollision));
    }

    /** A harness burst of n warps at START of the next tick, in {@code shape}. */
    static Runnable harness(String shape, int n) {
        return () -> ZpewCampaignTests.atTickStart = () -> {
            Minecraft mc = Minecraft.getInstance();
            float base = mc.player.getYRot();
            float[] yaws = new float[n];
            float[] pitches = new float[n];
            for (int k = 1; k <= n; k++) {
                float[] l = lookFor(k);
                yaws[k - 1] = base + Mth.wrapDegrees(l[0] - base);
                pitches[k - 1] = l[1];
            }
            for (int i = 0; i < n; i++) {
                boolean last = i == n - 1;
                switch (shape) {
                    case "rsa" -> use(mc, yaws[i], pitches[i]);
                    case "before" -> {
                        rot(mc, yaws[i], pitches[i]);
                        use(mc, yaws[i], pitches[i]);
                    }
                    case "after" -> {
                        use(mc, yaws[i], pitches[i]);
                        if (!last) {
                            rot(mc, yaws[i], pitches[i]);
                        }
                    }
                    default -> throw new IllegalArgumentException(shape);
                }
            }
            // The tick's own move reports the last look (the body is there, as a vanilla client's would be).
            mc.player.setYRot(yaws[n - 1]);
            mc.player.setYHeadRot(yaws[n - 1]);
            mc.player.setXRot(pitches[n - 1]);
        };
    }

    /** The mod's Aimed Warps: n presses of the use key queued before the next tick, each at its own look. */
    static Runnable presses(ClientGameTestContext ctx, int n) {
        return () -> ctx.runOnClient(mc -> {
            for (int k = 1; k <= n; k++) {
                float[] l = lookFor(k);
                float yaw = rel(mc, l[0]);
                mc.player.setYRot(yaw);
                mc.player.setYHeadRot(yaw);
                mc.player.setXRot(l[1]);
                KeyMapping.click(mc.options.keyUse.getDefaultKey()); // fresh test options: bound key = default
                Mod.staticCall(ZP, "pollClicks"); // the frame hook would give it this look
            }
        });
    }

    /** Set for the mod's bursts: {@link #shot} waits for the burst to go before its fixed wait. */
    static boolean waitAimed;

    record Result(int uses, int ticks, int warps, List<Vec3> tps, List<String> flags) {
    }

    static Result shot(ClientGameTestContext ctx, TestServer server, Hx hx, Scenario scenario, String who, int lat, String label,
                       Runnable fire, int n) {
        Vec3 s = feet(0);
        ZpewCampaignTests.tpTo(ctx, server, who, s.x, s.y, s.z, true);
        float[] l1 = lookFor(1);
        ZpewCampaignTests.look(ctx, l1[0], l1[1]);
        ctx.waitTicks(3);
        server.mark();
        int w0 = ZpewCampaignTests.counters(ctx, hx).warps();
        ctx.runOnClient(mc -> ZpewMultiTickTests.USES.clear());
        ZpewCampaignTests.captureStart(ctx);
        long sent0 = ZpewCampaignTests.num(ctx, "aimedWarpsSent");
        fire.run();
        if (waitAimed) {
            // A burst after a kept teleport waits for Blink's bank to be covered (at most 3 s): wait for it to go.
            for (int i = 0; i < 100 && ZpewCampaignTests.num(ctx, "aimedWarpsSent") == sent0; i++) {
                ctx.waitTicks(1);
            }
        }
        ctx.waitTicks(30 + lat / 25 + 2 * n);
        ZpewCampaignTests.captureStop(ctx);
        int warps = ZpewCampaignTests.counters(ctx, hx).warps() - w0;
        List<ZpewMultiTickTests.Use> uses;
        synchronized (ZpewMultiTickTests.USES) {
            uses = new ArrayList<>(ZpewMultiTickTests.USES);
        }
        TreeSet<Long> ticks = new TreeSet<>();
        uses.forEach(u -> ticks.add(u.tick()));
        List<Vec3> tps;
        synchronized (ZpewCampaignTests.TELEPORTS) {
            tps = new ArrayList<>(ZpewCampaignTests.TELEPORTS);
        }
        List<String> flags = new ArrayList<>();
        for (String f : scenario.flags()) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("failed ([A-Za-z0-9]+) \\(x").matcher(f);
            flags.add(m.find() ? m.group(1) : f);
        }
        StringBuilder at = new StringBuilder();
        for (Vec3 t : tps) {
            at.append(String.format(Locale.ROOT, " (%+.1f,%+.1f)", t.x - X0, t.z - SQ_Z));
        }
        scenario.log(String.format(Locale.ROOT, "%s: %d use(s) in client tick(s) %s, %d server warp(s), landings [%s ], flags %s",
                label, uses.size(), ticks, warps, at, flags));
        return new Result(uses.size(), ticks.size(), warps, tps, flags);
    }

    /**
     * True when every landing is the planned square corner, in order. A position packet repeating the one before it is the
     * vanilla server re-sending a teleport whose confirm Blink Charge still holds behind its ping answer (more than 20
     * ticks: {@code ServerGamePacketListenerImpl} re-sends), "in place (re-sync)" in Blink's log - not a warp.
     */
    static boolean landedAll(Result r, int n) {
        List<Vec3> tps = new ArrayList<>();
        for (Vec3 t : r.tps()) {
            if (tps.isEmpty() || tps.get(tps.size() - 1).distanceTo(t) > 0.1) {
                tps.add(t);
            }
        }
        if (r.warps() != n || tps.size() != n) {
            return false;
        }
        for (int k = 1; k <= n; k++) {
            Vec3 want = feet(k);
            Vec3 got = tps.get(k - 1);
            if (Math.abs(got.x - want.x) > 0.01 || Math.abs(got.z - want.z) > 0.01) {
                return false;
            }
        }
        return true;
    }

    static void looks(ClientGameTestContext ctx, int lat) {
        Scenario.runExpectingFlags(ctx, "1027-zpewlk-" + lat, (server, scenario) -> ZpewMultiTickTests.build(server),
                (server, scenario) -> {
            scenario.assertDetectorWorks();
            Hx hx = null;
            boolean gate = false;
            List<String> problems = new ArrayList<>();
            try {
                hx = ZpewCampaignTests.setUp(ctx, 4);
                ZpewMultiTickTests.hookTicks(ctx);
                String who = ZpewCampaignTests.name(ctx);
                boolean cheat = ZpewMultiTickTests.cheatJar(ctx);
                scenario.log("jar: " + (cheat ? "cheat" : "legit") + ", latency " + lat + " ms");
                ctx.runOnClient(mc -> {
                    Mod.set(ZP_CFG, "setWarpsPerTick", 1);
                    Mod.set(ZP_CFG, "setAimedWarps", true);
                });
                ctx.getInput().holdKey(o -> o.keyShift);
                ZpewCampaignTests.latency(ctx, lat);
                // The harness shapes, Charge off.
                for (String shape : new String[]{"rsa", "before"}) {
                    Result r = shot(ctx, server, hx, scenario, who, lat, shape + " n=2", harness(shape, 2), 2);
                    if (r.ticks() != 1 || !r.flags().contains("BadPacketsJ")) {
                        problems.add(shape + ": expected two uses in one tick to draw BadPacketsJ, got " + r);
                    }
                }
                for (int n : new int[]{2, 3, 5}) {
                    // Measured, not demanded: each look packet is an extra flying with no tick-end before it (TickTimer)
                    // and an extra 50 ms for Timer.
                    shot(ctx, server, hx, scenario, who, lat, "after n=" + n + " (no bank)", harness("after", n), n);
                }
                if (cheat) {
                    // Charge off: the mod leaves the presses to vanilla.
                    long unf0 = ZpewCampaignTests.num(ctx, "aimedUnfunded");
                    Result r0 = shot(ctx, server, hx, scenario, who, lat, "aimed n=3 no bank", presses(ctx, 3), 3);
                    long unf = ZpewCampaignTests.num(ctx, "aimedUnfunded") - unf0;
                    if (unf != 1 || !r0.flags().isEmpty() || r0.ticks() != 1) {
                        problems.add("aimed without a bank: expected it left to vanilla, clean: unfunded " + unf + " " + r0);
                    }
                    // Charge on: each extra look spends a banked tick.
                    gate = BlinkTests.setUp(ctx, 20);
                    BlinkTests.chargeOn(ctx);
                    waitAimed = true;
                    for (int rep = 0; rep < 2; rep++) {
                        for (int n : new int[]{2, 3, 5}) {
                            ZpewCampaignTests.tpTo(ctx, server, who, feet(0).x, feet(0).y, feet(0).z, true);
                            ctx.waitTicks(30); // bank refills standing still
                            long bank = BlinkTests.cnum(ctx, "banked");
                            long sent0 = ZpewCampaignTests.num(ctx, "aimedWarpsSent");
                            Result r = shot(ctx, server, hx, scenario, who, lat, "aimed n=" + n + " rep " + rep + " (bank " + bank + ")",
                                    presses(ctx, n), n);
                            long sent = ZpewCampaignTests.num(ctx, "aimedWarpsSent") - sent0;
                            // the n uses leave inside ONE real tick; on the wire they are n ticks (n - 1 banked)
                            if (sent != n || r.ticks() != 1 || !landedAll(r, n) || !r.flags().isEmpty()) {
                                problems.add("aimed n=" + n + " rep " + rep + ": sent " + sent + ", " + r);
                            }
                        }
                    }
                }
            } finally {
                waitAimed = false;
                ZpewCampaignTests.atTickStart = null;
                if (cheat(ctx)) {
                    BlinkTests.chargeOff(ctx);
                    BlinkTests.tearDown(ctx, gate);
                }
                ctx.runOnClient(mc -> Mod.set(ZP_CFG, "setAimedWarps", false));
                ZpewCampaignTests.tearDown(ctx, hx);
            }
            if (!problems.isEmpty()) {
                throw new AssertionError("zpewlk " + lat + " ms: " + problems);
            }
        });
    }

    static boolean cheat(ClientGameTestContext ctx) {
        return ZpewMultiTickTests.cheatJar(ctx);
    }
}
