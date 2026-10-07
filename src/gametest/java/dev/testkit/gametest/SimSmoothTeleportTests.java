package dev.testkit.gametest;

import dev.testkit.harness.PacketTrace;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 396-sim-smooth-tp: the mod's Smooth Teleport (mod branch smooth-tp, 2026-10-07) glides the CAMERA after a teleport
 * ability and nothing else.
 *
 * <p>A flat singleplayer world with the sim switched on, so the sim's server answers a real Aspect of the Void use packet
 * ({@code gameMode.useItem}) with a real position packet, 12 blocks east along a stone lane. Every rendered frame records
 * the camera ({@code Camera.position()}), the player's real position, the crosshair pick ({@code mc.hitResult}) and both
 * yaws. The test thread sleeps between ticks so wall time passes between frames, as in a real game. Cases:
 * <ul>
 *   <li><b>off</b> - feature off: on the first frame he is at the landing, the camera is too (one-frame jump);</li>
 *   <li><b>glide</b> - on, 400 ms: on that frame the camera has NOT jumped, then moves east monotonically through at
 *       least three in-between frames and arrives at about the duration; the crosshair already hits a wall 2.5 blocks
 *       past the LANDING (14.5 from the camera, out of reach) on that first frame, so the pick is the real eye's; the
 *       yaw of camera and body never changes;</li>
 *   <li><b>correction</b> - on, 1500 ms: mid-glide the server teleports him back with no use behind it; on the first frame
 *       that shows him back, the camera is exactly where he is (snap);</li>
 *   <li><b>chain</b> - on, 600 ms: a second use mid-glide; the camera never moves back and the second landing glides too;</li>
 *   <li><b>packets</b> - the serverbound packets of the glide case, movement aside, are exactly the off case's: one
 *       use_item and one accept_teleportation each.</li>
 * </ul>
 * Fails on a jar without the feature (no config class, and the camera jumps in one frame).
 */
public class SimSmoothTeleportTests implements FabricClientGameTest {

    private static final String NAME = "396-sim-smooth-tp";
    private static final String CONFIG = "com.killer560.hub.smoothtp.SmoothTeleportConfig";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String SIM_ITEMS = "com.killer560.hub.roomsim.SimItems";

    private static final int X0 = 1000;
    private static final int Y = 150;
    private static final int Z0 = 1000;
    /** The pick lane's wall: 2.5 blocks past a 12-block landing at X0 + 12.5. */
    private static final int WALL_X = X0 + 15;
    private static final int STEP_MS = 20;

    /** {nanos, camX, camY, camZ, px, py, pz, hitType (0 miss, 1 block, 2 entity), hitBlockX, camYRot, bodyYRot}. */
    private static final List<double[]> FRAMES = Collections.synchronizedList(new ArrayList<>());
    private static volatile boolean recording;
    private static boolean hooked;
    private static final List<String> SENT = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(20);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        List<String> failures = new ArrayList<>();
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            try {
                body(ctx, sp, failures);
            } catch (Throwable t) {
                failures.add("scenario threw: " + t);
                t.printStackTrace(System.out);
            } finally {
                recording = false;
                PacketTrace.tap = null;
                setFeature(ctx, false, 100, new ArrayList<>());
                ctx.runOnClient(mc -> {
                    try {
                        ModUnderTest.staticCall(SIM_STATE, "leave", new Class<?>[]{}, new Object[]{});
                    } catch (Throwable ignored) {
                        // cleanup never replaces the verdict
                    }
                });
            }
        }
        if (!failures.isEmpty()) {
            for (String f : failures) {
                println("FAIL " + f);
            }
            throw new AssertionError(failures.size() + " problem(s): " + String.join("; ", failures));
        }
        println("PASS");
    }

    private static void body(ClientGameTestContext ctx, TestSingleplayerContext sp, List<String> failures) {
        ctx.runOnClient(mc -> {
            ModUnderTest.staticCall(SIM_STATE, "enter", new Class<?>[]{String.class}, new Object[]{"gametest"});
            if (!hooked) {
                hooked = true;
                net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES
                        .register(c -> {
                            if (recording) {
                                sample(Minecraft.getInstance());
                            }
                        });
            }
        });
        boolean canAct = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SIM_STATE, "canAct",
                new Class<?>[]{Minecraft.class}, new Object[]{mc}));
        if (!canAct) {
            failures.add("SimState.canAct is false - the sim would not answer the teleport");
            return;
        }
        // Four stone lanes running east, one with a wall for the pick check.
        sp.getServer().runOnServer(server -> {
            var level = server.overworld();
            for (int x = X0 - 5; x <= X0 + 45; x++) {
                for (int z = Z0 - 15; z <= Z0 + 25; z++) {
                    level.setBlockAndUpdate(new BlockPos(x, Y - 1, z), Blocks.STONE.defaultBlockState());
                    for (int y = Y; y <= Y + 4; y++) {
                        level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                    }
                }
            }
            for (int z = Z0 - 2; z <= Z0 + 2; z++) {
                for (int y = Y; y <= Y + 2; y++) {
                    level.setBlockAndUpdate(new BlockPos(WALL_X, y, z), Blocks.STONE.defaultBlockState());
                }
            }
        });
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_ITEMS, "give",
                new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, "ASPECT_OF_THE_VOID"}));
        ctx.waitTicks(20);
        int slot = ctx.computeOnClient(mc -> {
            for (int i = 0; i < 9; i++) {
                var data = mc.player.getInventory().getItem(i).get(DataComponents.CUSTOM_DATA);
                if (data != null && "ASPECT_OF_THE_VOID".equals(data.copyTag().getStringOr("id", ""))) {
                    mc.player.getInventory().setSelectedSlot(i);
                    return i;
                }
            }
            return -1;
        });
        if (slot < 0) {
            failures.add("the sim did not give an Aspect of the Void");
            return;
        }
        if (!place(ctx, sp, Z0 - 10, 1800)) {
            failures.add("the lane's chunks never reached the client");
            return;
        }

        // ---- off: one-frame jump --------------------------------------------------------------------------
        setFeature(ctx, false, 400, failures);
        Run off = teleport(ctx, sp, Z0 - 10, 40, -1, null);
        if (off.landed < 0) {
            failures.add("off: he never landed (" + off.describe() + ")");
        } else {
            double gap = Math.abs(off.camX(off.landed) - off.px(off.landed));
            println(String.format(Locale.ROOT, "off: moved %.2f blocks; camera %.3f from him on the landing frame",
                    off.dest() - off.startX, gap));
            if (gap > 0.01) {
                failures.add(String.format(Locale.ROOT, "off: the camera was %.2f blocks from him on the landing frame "
                        + "with the feature OFF", gap));
            }
        }

        // ---- glide ----------------------------------------------------------------------------------------
        boolean set = setFeature(ctx, true, 400, failures);
        Run glide = teleport(ctx, sp, Z0, 45, -1, null);
        judgeGlide("glide", glide, 400, failures);
        if (glide.landed >= 0) {
            double[] f = glide.frames.get(glide.landed);
            double camToWall = WALL_X - f[1];
            println(String.format(Locale.ROOT, "glide: landing frame pick %s at x %.0f; camera %.2f from the wall face, "
                    + "eye %.2f", f[7] == 1 ? "BLOCK" : f[7] == 2 ? "ENTITY" : "MISS", f[8], camToWall, WALL_X - f[4]));
            if (camToWall <= 5.0) {
                failures.add(String.format(Locale.ROOT, "glide: camera only %.2f from the wall on the landing frame - "
                        + "the pick check proves nothing", camToWall));
            }
            if (f[7] != 1 || (int) f[8] != WALL_X) {
                failures.add("glide: the crosshair on the landing frame did not hit the wall 2.5 blocks past the "
                        + "landing - the pick is not from the real eye");
            }
            double yaw0 = glide.frames.get(0)[9];
            for (double[] g : glide.frames) {
                if (Math.abs(g[9] - yaw0) > 1e-3 || Math.abs(g[10] - yaw0) > 1e-3) {
                    failures.add(String.format(Locale.ROOT, "glide: yaw changed (camera %.3f body %.3f, was %.3f)",
                            g[9], g[10], yaw0));
                    break;
                }
            }
        }

        // ---- packets: the glide case sends what the off case sends --------------------------------------------
        Map<String, Integer> offPk = nonMovement(off.sent);
        Map<String, Integer> onPk = nonMovement(glide.sent);
        println("packets off: " + offPk + "  on: " + onPk);
        if (!offPk.equals(onPk)) {
            failures.add("packets: the glide case sent " + onPk + " where the off case sent " + offPk);
        }
        if (onPk.getOrDefault("use_item", 0) != 1 || onPk.getOrDefault("accept_teleportation", 0) != 1) {
            failures.add("packets: expected one use_item and one accept_teleportation, got " + onPk);
        }

        // ---- correction mid-glide ---------------------------------------------------------------------------
        setFeature(ctx, set, 1500, failures);
        Run corr = teleport(ctx, sp, Z0 + 10, 40, 4, () -> sp.getServer().runOnServer(server -> {
            var p = server.getPlayerList().getPlayers().get(0);
            p.teleportTo(server.overworld(), X0 + 0.5, Y, Z0 + 10 + 0.5, Set.of(Relative.Y_ROT, Relative.X_ROT),
                    0f, 0f, false);
        }));
        if (corr.landed < 0) {
            failures.add("correction: he never landed (" + corr.describe() + ")");
        } else {
            int back = -1;
            for (int i = corr.landed + 1; i < corr.frames.size(); i++) {
                if (corr.px(i) < corr.startX + 1.0) {
                    back = i;
                    break;
                }
            }
            if (back < 0) {
                failures.add("correction: the server's teleport back never reached the client");
            } else {
                double before = corr.camX(back - 1);
                double landing = corr.px(corr.landed);
                double gap = Math.abs(corr.camX(back) - corr.px(back));
                println(String.format(Locale.ROOT, "correction: frame before it camera at +%.2f (landing +%.2f); on "
                        + "the corrected frame camera %.3f from him", before - corr.startX, landing - corr.startX, gap));
                if (landing - before < 1.0) {
                    failures.add("correction: no glide was in progress when the correction came - nothing tested");
                }
                if (gap > 0.01) {
                    failures.add(String.format(Locale.ROOT, "correction: camera %.2f blocks from him on the corrected "
                            + "frame - a correction must snap", gap));
                }
            }
        }

        // ---- chain ------------------------------------------------------------------------------------------
        setFeature(ctx, set, 600, failures);
        Run chain = teleport(ctx, sp, Z0 + 20, 60, 2, () -> ctx.runOnClient(mc ->
                mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND)));
        if (chain.landed < 0) {
            failures.add("chain: he never landed (" + chain.describe() + ")");
        } else {
            double end = chain.dest();
            int second = -1;
            for (int i = chain.landed + 1; i < chain.frames.size(); i++) {
                if (chain.px(i) > chain.px(chain.landed) + 6) {
                    second = i;
                    break;
                }
            }
            double worstBack = 0;
            for (int i = 1; i < chain.frames.size(); i++) {
                worstBack = Math.min(worstBack, chain.camX(i) - chain.camX(i - 1));
            }
            println(String.format(Locale.ROOT, "chain: moved %.2f blocks in two uses; second landing frame %d, camera "
                    + "%.2f behind him there; worst backward step %.4f", end - chain.startX, second,
                    second < 0 ? Double.NaN : chain.px(second) - chain.camX(second), worstBack));
            if (second < 0) {
                failures.add("chain: the second teleport never landed");
            } else if (chain.px(second) - chain.camX(second) < 1.0) {
                failures.add("chain: the second landing was not glided");
            }
            if (worstBack < -1e-6) {
                failures.add(String.format(Locale.ROOT, "chain: the camera moved BACK %.3f blocks", -worstBack));
            }
            double last = chain.camX(chain.frames.size() - 1);
            if (Math.abs(last - end) > 0.01) {
                failures.add(String.format(Locale.ROOT, "chain: camera ended %.2f from the second landing", end - last));
            }
        }
    }

    /** The glide checks shared by the glide case. */
    private static void judgeGlide(String name, Run r, int durationMs, List<String> failures) {
        if (r.landed < 0) {
            failures.add(name + ": he never landed (" + r.describe() + ")");
            return;
        }
        double dist = r.dest() - r.startX;
        double camAtLanding = r.camX(r.landed) - r.startX;
        int between = 0;
        double worstBack = 0;
        int arrived = -1;
        for (int i = Math.max(1, r.landed); i < r.frames.size(); i++) {
            worstBack = Math.min(worstBack, r.camX(i) - r.camX(i - 1));
            double c = r.camX(i);
            if (c > r.startX + 0.5 && c < r.dest() - 0.5) {
                between++;
            }
            if (arrived < 0 && Math.abs(c - r.px(i)) < 0.01) {
                arrived = i;
            }
        }
        double ms = arrived < 0 ? Double.NaN : (r.frames.get(arrived)[0] - r.frames.get(r.landed)[0]) / 1e6;
        StringBuilder path = new StringBuilder();
        for (int i = r.landed; i < r.frames.size() && i <= r.landed + 30; i++) {
            path.append(String.format(Locale.ROOT, " %.2f", r.camX(i) - r.startX));
        }
        println(String.format(Locale.ROOT, "%s: moved %.2f; camera at +%.2f on the landing frame; %d frames in "
                + "between; arrived after %.0f ms (setting %d); worst backward step %.4f; camera path%s", name, dist,
                camAtLanding, between, ms, durationMs, worstBack, path));
        if (camAtLanding > 0.6 * dist) {
            failures.add(String.format(Locale.ROOT, "%s: camera jumped %.2f of %.2f blocks on the landing frame", name,
                    camAtLanding, dist));
        }
        if (between < 3) {
            failures.add(name + ": only " + between + " frame(s) between start and landing - no glide");
        }
        if (worstBack < -1e-6) {
            failures.add(String.format(Locale.ROOT, "%s: camera moved back %.3f", name, -worstBack));
        }
        if (arrived < 0) {
            failures.add(name + ": the camera never reached him");
        } else if (ms < 0.7 * durationMs || ms > durationMs + 4 * STEP_MS + 150) {
            failures.add(String.format(Locale.ROOT, "%s: glide took %.0f ms for a %d ms setting", name, ms, durationMs));
        }
        for (double[] f : r.frames) {
            if (Math.abs(f[3] - f[6]) > 0.01) {
                failures.add(String.format(Locale.ROOT, "%s: camera left the lane sideways (z %.3f vs %.3f)", name,
                        f[3], f[6]));
                break;
            }
        }
    }

    /** Movement, timing and chunk-batch acks aside (chunk loading differs per lane), what was sent, by name. */
    private static Map<String, Integer> nonMovement(List<String> sent) {
        Map<String, Integer> out = new TreeMap<>();
        for (String s : sent) {
            if (s.startsWith("move_player") || s.equals("client_tick_end") || s.equals("player_input")
                    || s.equals("keep_alive") || s.equals("pong") || s.equals("player_loaded")
                    || s.equals("chunk_batch_received")) {
                continue;
            }
            out.merge(s, 1, Integer::sum);
        }
        return out;
    }

    // ---- one teleport -----------------------------------------------------------------------------------------

    private static final class Run {
        final List<double[]> frames;
        final List<String> sent;
        final double startX;
        final int landed;

        Run(List<double[]> frames, List<String> sent, double startX) {
            this.frames = frames;
            this.sent = sent;
            this.startX = startX;
            int l = -1;
            for (int i = 0; i < frames.size(); i++) {
                if (frames.get(i)[4] > startX + 8) {
                    l = i;
                    break;
                }
            }
            this.landed = l;
        }

        double camX(int i) {
            return frames.get(i)[1];
        }

        double px(int i) {
            return frames.get(i)[4];
        }

        double dest() {
            return px(frames.size() - 1);
        }

        String describe() {
            return frames.size() + " frames, player x " + (frames.isEmpty() ? "?" : String.format(Locale.ROOT,
                    "%.2f -> %.2f", frames.get(0)[4], dest()));
        }
    }

    /**
     * Stands him at the lane's west end facing east, uses the AOTV, and records {@code ticks} frames with real time
     * between them. {@code midAt} frames after the landing frame, {@code mid} runs (a correction, a second use).
     */
    private static Run teleport(ClientGameTestContext ctx, TestSingleplayerContext sp, int laneZ, int ticks, int midAt,
                                Runnable mid) {
        place(ctx, sp, laneZ, 400);
        double startX = ctx.computeOnClient(mc -> mc.player.getX());
        FRAMES.clear();
        SENT.clear();
        PacketTrace.tap = SimSmoothTeleportTests::onSent;
        recording = true;
        ctx.runOnClient(mc -> mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND));
        int landedAt = -1;
        boolean midDone = false;
        for (int t = 0; t < ticks; t++) {
            ctx.waitTicks(1);
            sleep(STEP_MS);
            if (mid != null && !midDone) {
                if (landedAt < 0) {
                    synchronized (FRAMES) {
                        for (int i = 0; i < FRAMES.size(); i++) {
                            if (FRAMES.get(i)[4] > startX + 8) {
                                landedAt = i;
                                break;
                            }
                        }
                    }
                }
                if (landedAt >= 0 && FRAMES.size() >= landedAt + midAt) {
                    midDone = true;
                    mid.run();
                }
            }
        }
        recording = false;
        PacketTrace.tap = null;
        List<double[]> frames;
        synchronized (FRAMES) {
            frames = new ArrayList<>(FRAMES);
        }
        List<String> sent;
        synchronized (SENT) {
            sent = new ArrayList<>(SENT);
        }
        return new Run(frames, sent, startX);
    }

    /** Server teleport to the lane's west end, facing east; true once the client stands there on the stone. */
    private static boolean place(ClientGameTestContext ctx, TestSingleplayerContext sp, int laneZ, int maxTicks) {
        double x = X0 + 0.5;
        double z = laneZ + 0.5;
        sp.getServer().runOnServer(server -> {
            var p = server.getPlayerList().getPlayers().get(0);
            p.teleportTo(server.overworld(), x, Y, z, Set.of(), -90f, 0f, false);
        });
        for (int t = 0; t < maxTicks; t++) {
            ctx.waitTicks(1);
            boolean ok = ctx.computeOnClient(mc -> mc.player != null
                    && Math.abs(mc.player.getX() - x) < 0.05 && Math.abs(mc.player.getZ() - z) < 0.05
                    && Math.abs(mc.player.getY() - Y) < 0.05
                    && mc.level.getBlockState(BlockPos.containing(x, Y - 1, z)).is(Blocks.STONE));
            if (ok) {
                ctx.waitTicks(10);
                return true;
            }
        }
        return false;
    }

    private static boolean setFeature(ClientGameTestContext ctx, boolean on, int durationMs, List<String> failures) {
        String err = ctx.computeOnClient(mc -> {
            try {
                Object cfg = ModUnderTest.config(CONFIG);
                ModUnderTest.set(cfg, "setEnabled", on);
                ModUnderTest.set(cfg, "setDurationMs", durationMs);
                return null;
            } catch (RuntimeException | AssertionError e) {
                return String.valueOf(e.getMessage());
            }
        });
        if (err != null) {
            if (on && failures.stream().noneMatch(f -> f.startsWith("feature absent"))) {
                failures.add("feature absent: " + err);
            }
            return false;
        }
        return on;
    }

    private static void sample(Minecraft mc) {
        if (mc.player == null) {
            return;
        }
        Object camera = camera(mc);
        Vec3 cam;
        float camYaw;
        try {
            cam = (Vec3) camera.getClass().getMethod("position").invoke(camera);
            camYaw = (Float) camera.getClass().getMethod("yRot").invoke(camera);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("no Camera.position()/yRot()", e);
        }
        HitResult hit = mc.hitResult;
        double type = hit == null || hit.getType() == HitResult.Type.MISS ? 0
                : hit.getType() == HitResult.Type.BLOCK ? 1 : 2;
        double hitX = hit instanceof BlockHitResult b ? b.getBlockPos().getX() : Double.NaN;
        FRAMES.add(new double[]{System.nanoTime(), cam.x, cam.y, cam.z, mc.player.getX(), mc.player.getY(),
                mc.player.getZ(), type, hitX, camYaw, mc.player.getYRot()});
    }

    /** {@code GameRenderer.getMainCamera()} on 26.1.2, {@code mainCamera()} on 26.2. */
    private static Object camera(Minecraft mc) {
        for (String name : new String[]{"getMainCamera", "mainCamera"}) {
            try {
                return mc.gameRenderer.getClass().getMethod(name).invoke(mc.gameRenderer);
            } catch (NoSuchMethodException ignored) {
                // try the other version's name
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }
        throw new AssertionError("GameRenderer has no camera accessor");
    }

    private static void onSent(Packet<?> packet) {
        if (!recording) {
            return;
        }
        Identifier id = packet.type().id();
        SENT.add(id.getNamespace().equals("minecraft") ? id.getPath() : id.toString());
    }

    private static void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void println(String line) {
        System.out.println("[" + NAME + "] " + line);
    }
}
