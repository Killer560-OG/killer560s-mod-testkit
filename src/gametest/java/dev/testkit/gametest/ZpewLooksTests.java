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
        try {
            emergencyCancel(ctx);
        } catch (AssertionError e) {
            first = first == null ? e : first;
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
        ctx.runOnClient(mc -> {
            dev.testkit.harness.WireCapture.describe = ZpewLooksTests::describe;
            dev.testkit.harness.WireCapture.start();
        });
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
        ctx.runOnClient(mc -> dev.testkit.harness.WireCapture.stop());
        if (label.startsWith("aimed")) {
            wireLog(scenario, label);
        }
        return new Result(uses.size(), ticks.size(), warps, tps, flags);
    }

    /** The serverbound packets as the encoder wrote them (the real wire order, after Blink's holds): move and use
     *  packets with their rotation. zpew263-1101: a BadPacketsJ seen only at 0 ms needed the wire, not the hand-off. */
    static String describe(Object p) {
        if (p instanceof ServerboundUseItemPacket u) {
            return String.format(Locale.ROOT, "USE %.2f/%.2f seq %d", dev.testkit.compat.McPackets.useItemYRot(u),
                    dev.testkit.compat.McPackets.useItemXRot(u), dev.testkit.compat.McPackets.useItemSequence(u));
        }
        if (p instanceof ServerboundMovePlayerPacket m) {
            String k = m.hasPosition() ? (m.hasRotation() ? "posrot" : "pos") : (m.hasRotation() ? "rot" : "status");
            return m.hasRotation() ? String.format(Locale.ROOT, "move-%s %.2f/%.2f", k, m.getYRot(0), m.getXRot(0)) : "move-" + k;
        }
        String n = p.getClass().getSimpleName();
        return n.startsWith("Serverbound") ? n.substring(11).replace("Packet", "") : n;
    }

    /** One line per wire packet from the first use_item -3 packets to the end of the window, with a BadPacketsJ replay. */
    static void wireLog(Scenario scenario, String label) {
        List<dev.testkit.harness.WireCapture.Sent> w = dev.testkit.harness.WireCapture.snapshot();
        int first = -1;
        for (int i = 0; i < w.size(); i++) {
            if (w.get(i).text().startsWith("USE")) {
                first = i;
                break;
            }
        }
        if (first < 0) {
            scenario.log("  wire " + label + ": no use_item on the wire (" + w.size() + " packets)");
            return;
        }
        long t0 = w.get(first).nanos();
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        float[] held = null;
        boolean tpAnswer = false;
        boolean moved = false;
        float yaw = Float.NaN;
        float pitch = Float.NaN;
        for (int i = Math.max(0, first - 3); i < w.size() && shown < 80; i++, shown++) {
            String t = w.get(i).text();
            String mark = "";
            if (t.startsWith("USE")) {
                String[] a = t.substring(4).split(" ")[0].split("/");
                held = new float[]{Float.parseFloat(a[0]), Float.parseFloat(a[1])};
            } else if (t.startsWith("move-")) {
                if (t.contains("/")) {
                    String[] a = t.substring(t.indexOf(' ') + 1).split("/");
                    yaw = Float.parseFloat(a[0]);
                    pitch = Float.parseFloat(a[1]);
                }
                if (tpAnswer) {
                    tpAnswer = false;
                } else {
                    moved = true;
                    if (held != null && (Math.abs(held[0] - yaw) > 0.005f || Math.abs(held[1] - pitch) > 0.005f)) {
                        mark = "  <-- J? use " + held[0] + "/" + held[1];
                    }
                    held = null;
                }
            } else if (t.equals("AcceptTeleportation")) {
                tpAnswer = true;
            } else if (t.equals("ClientTickEnd")) {
                if (!moved && held != null && (Math.abs(held[0] - yaw) > 0.005f || Math.abs(held[1] - pitch) > 0.005f)) {
                    mark = "  <-- J? (tick-end) use " + held[0] + "/" + held[1];
                }
                if (!moved) {
                    held = null;
                }
                moved = false;
            }
            sb.append(String.format(Locale.ROOT, "%n    %+7.1f ms %s%s", (w.get(i).nanos() - t0) / 1e6, t, mark));
        }
        scenario.log("  wire " + label + ":" + sb);
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
                    ZpewMultiTickTests.multiWarps(true);
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
                            long looks0 = lookReports(ctx);
                            long sent0 = ZpewCampaignTests.num(ctx, "aimedWarpsSent");
                            Result r = shot(ctx, server, hx, scenario, who, lat, "aimed n=" + n + " rep " + rep + " (bank " + bank + ")",
                                    presses(ctx, n), n);
                            long sent = ZpewCampaignTests.num(ctx, "aimedWarpsSent") - sent0;
                            scenario.log("  aimed n=" + n + " rep " + rep + ": spent ticks that reported their own look: "
                                    + (looks0 < 0 ? "n/a (jar without spentLooksReported)" : String.valueOf(lookReports(ctx) - looks0)));
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

    static final String MS = "macrostop.MacroStop";
    static final String MS_CFG = "macrostop.MacroStopConfig";
    /** GLFW K, the Emergency Cancel Key for the case (mod settings keep GLFW numbers; pressed through McKeys.nat). */
    static final int K = 75;

    static boolean held(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> (Boolean) Mod.staticCall(MS, "emergencyHeld"));
    }

    static void pressK(ClientGameTestContext ctx) {
        ctx.getInput().pressKey(dev.testkit.compat.McKeys.nat(K));
        ctx.waitTicks(2);
    }

    /**
     * 1102-zpew-ecancel (mod zpew2-1101; killer560 2026-11-01: the Emergency Cancel Key "should stop EVERYTHING automatic").
     * ZPEW's own sends - Warps Per Tick's extras and an Aimed Warps burst - never went through ActionGate, so the hold did not
     * reach them. Each half is proved twice, acting with the hold off and refused with it on, on the same lanes:
     * <ol>
     *   <li>Warps Per Tick 5, hold off: 5 uses in one tick, 4 extra, 5 server warps (the control that it acts);</li>
     *   <li>K: the hold is on; Warps Per Tick 5: exactly his own use (1), 0 extra, 1 warp, heldRefused +1;</li>
     *   <li>Aimed Warps n=3 with a Blink bank, hold on: no aimed warp sent (aimedWarpsSent +0), heldRefused +1, the presses
     *       go as vanilla sends them (3 uses, one tick);</li>
     *   <li>K again: the hold is off; the same aimed n=3 sends 3 aimed warps landing on every corner.</li>
     * </ol>
     * GrimAC must say nothing throughout (Scenario.run). Cheat jar only: a legit jar has neither part.
     */
    static void emergencyCancel(ClientGameTestContext ctx) {
        if (!cheat(ctx)) {
            Scenario.skipped("1102-zpew-ecancel", "Warps Per Tick and Aimed Warps are cheat-only");
            return;
        }
        Scenario.run(ctx, "1102-zpew-ecancel", (server, scenario) -> ZpewMultiTickTests.build(server), (server, scenario) -> {
            scenario.assertDetectorWorks();
            Hx hx = null;
            boolean gate = false;
            boolean blink = false;
            int oldKey = ctx.computeOnClient(mc -> (Integer) Mod.call(Mod.cfg(MS_CFG), "getEmergencyCancelKeyCode"));
            List<String> problems = new ArrayList<>();
            try {
                hx = ZpewCampaignTests.setUp(ctx, 4);
                ZpewMultiTickTests.hookTicks(ctx);
                String who = ZpewCampaignTests.name(ctx);
                ctx.runOnClient(mc -> {
                    Mod.set(MS_CFG, "setEmergencyCancelKeyCode", K);
                    Mod.staticCall(MS, "release", "test setup");
                    Mod.set(ZP_CFG, "setAimedWarps", true);
                });
                ctx.getInput().holdKey(o -> o.keyShift);
                ZpewCampaignTests.latency(ctx, 0);
                // 1. Warps Per Tick, hold off: it acts.
                ZpewMultiTickTests.setWpt(ctx, 5);
                problems.addAll(ZpewMultiTickTests.shot(ctx, server, hx, scenario, who, 0, "wpt-5 hold off", 0,
                        () -> ctx.getInput().pressKey(o -> o.keyUse), 5, 5, 4));
                // 2. K: the hold; Warps Per Tick sends only his own warp.
                pressK(ctx);
                boolean on = held(ctx);
                long ref0 = ZpewCampaignTests.num(ctx, "heldRefused");
                problems.addAll(ZpewMultiTickTests.shot(ctx, server, hx, scenario, who, 0, "wpt-5 hold on", 0,
                        () -> ctx.getInput().pressKey(o -> o.keyUse), 1, 1, 0));
                long refWpt = ZpewCampaignTests.num(ctx, "heldRefused") - ref0;
                scenario.log("after K: hold " + on + "; Warps Per Tick extras refused by the hold: " + refWpt);
                if (!on || refWpt != 1) {
                    problems.add("hold on after K " + on + ", wpt refusals " + refWpt + " (expected 1)");
                }
                // 3. Aimed Warps with a bank, hold on: refused, left to vanilla.
                ZpewMultiTickTests.setWpt(ctx, 1);
                gate = BlinkTests.setUp(ctx, 20);
                blink = true;
                BlinkTests.chargeOn(ctx);
                Vec3 s = feet(0);
                ZpewCampaignTests.tpTo(ctx, server, who, s.x, s.y, s.z, true);
                ctx.waitTicks(30); // the bank fills standing still
                long bank = BlinkTests.cnum(ctx, "banked");
                long sent0 = ZpewCampaignTests.num(ctx, "aimedWarpsSent");
                long ref1 = ZpewCampaignTests.num(ctx, "heldRefused");
                Result r = shot(ctx, server, hx, scenario, who, 0, "aimed n=3 hold on (bank " + bank + ")", presses(ctx, 3), 3);
                long sentHeld = ZpewCampaignTests.num(ctx, "aimedWarpsSent") - sent0;
                long refAimed = ZpewCampaignTests.num(ctx, "heldRefused") - ref1;
                boolean stillHeld = held(ctx);
                scenario.log(String.format(Locale.ROOT, "aimed hold on: bank %d, aimed sent %d, refused %d, uses %d in %d tick(s), "
                        + "hold still on %s", bank, sentHeld, refAimed, r.uses(), r.ticks(), stillHeld));
                if (bank < 2) {
                    problems.add("no Blink bank to refuse (" + bank + "): the aimed half proved nothing");
                }
                if (sentHeld != 0 || refAimed < 1 || r.uses() != 3 || r.ticks() != 1 || !stillHeld) {
                    problems.add("aimed under the hold: sent " + sentHeld + ", refused " + refAimed + ", " + r + ", hold " + stillHeld);
                }
                // 4. K again: released; the same burst goes, aimed.
                pressK(ctx);
                boolean off = !held(ctx);
                ZpewCampaignTests.tpTo(ctx, server, who, s.x, s.y, s.z, true);
                ctx.waitTicks(30);
                long bank2 = BlinkTests.cnum(ctx, "banked");
                long sent1 = ZpewCampaignTests.num(ctx, "aimedWarpsSent");
                waitAimed = true;
                Result r2 = shot(ctx, server, hx, scenario, who, 0, "aimed n=3 hold off (bank " + bank2 + ")", presses(ctx, 3), 3);
                waitAimed = false;
                long sentFree = ZpewCampaignTests.num(ctx, "aimedWarpsSent") - sent1;
                scenario.log("after K again: hold off " + off + ", aimed sent " + sentFree + ", landed every corner " + landedAll(r2, 3));
                if (!off || sentFree != 3 || r2.ticks() != 1 || !landedAll(r2, 3)) {
                    problems.add("aimed after release: hold off " + off + ", sent " + sentFree + ", " + r2);
                }
            } finally {
                waitAimed = false;
                ZpewCampaignTests.atTickStart = null;
                if (blink) {
                    BlinkTests.chargeOff(ctx);
                    BlinkTests.tearDown(ctx, gate);
                }
                ctx.runOnClient(mc -> {
                    Mod.staticCall(MS, "release", "test teardown");
                    Mod.set(MS_CFG, "setEmergencyCancelKeyCode", oldKey);
                    Mod.set(ZP_CFG, "setAimedWarps", false);
                    Mod.set(ZP_CFG, "setWarpsPerTick", 1);
                });
                ZpewCampaignTests.tearDown(ctx, hx);
            }
            if (!problems.isEmpty()) {
                throw new AssertionError("zpew ecancel: " + problems);
            }
        });
    }

    /** BlinkCharge.spentLooksReported (zpew263-1101), -1 on a jar without it. */
    static long lookReports(ClientGameTestContext ctx) {
        try {
            return BlinkTests.cnum(ctx, "spentLooksReported");
        } catch (RuntimeException e) {
            return -1;
        }
    }

    static boolean cheat(ClientGameTestContext ctx) {
        return ZpewMultiTickTests.cheatJar(ctx);
    }
}
