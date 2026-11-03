package dev.testkit.gametest;

import dev.testkit.compat.McCompat;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 98-prince-box: the Secret Waypoints prince box is drawn whole (killer560, 2026-11-02, F7 on 26.3: "this prince isnt
 * highlighted properly. it is missing most of the box").
 *
 * <p>His settings (26.3 instance): Fill + Outline, through walls on, Hitbox size, Show Princes on. His run's log put the
 * prince in Red Blue (3 blocks in a line) and in Pirate (12 blocks). Each room with a prince is built on its own in the sim,
 * the prince colour is set to pure cyan (nothing else in a room is), and the box is photographed from four diagonals above
 * it. With through walls on, every face and every edge must reach the screen whatever blocks stand in front:
 * <ul>
 *   <li>edges: 9 points along each of the 12 edges, projected with the camera's own position, yaw, pitch and FOV; a point
 *       counts when 4+ pure-cyan pixels (the full-alpha outline) lie within 2 px of it;</li>
 *   <li>faces: a 5x5 grid inside each of the 6 faces; a point counts when its pixel changed between Show Princes on and
 *       off (the half-alpha fill).</li>
 * </ul>
 * Both must hold at 90% of the on-screen samples in every view. Screenshots are kept in the report. A view whose spot
 * is outside the room (Secret Waypoints only draws the room you stand in) is skipped, not judged.
 *
 * <p>The cause turned out to be the box, not the drawing: Red Blue's prince is a row of three gold blocks laid along a 3x4
 * smooth stone slab lid, and the scanner boxed the gold alone - the tall one-block sliver in his picture. So the scenario
 * also requires the box to take in every lid (a horizontal run of 6+ smooth stone slabs) that touches it.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimPrinceBoxTests implements FabricClientGameTest {

    private static final String NAME = "98-prince-box";
    private static final String[] ROOMS = {"Red Blue", "Pirate", "Chambers"};

    private static final String CFG = "com.killer560.hub.secretwaypoints.SecretWaypointsConfig";
    private static final String FEATURE = "com.killer560.hub.secretwaypoints.SecretWaypointsFeature";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String BUILDER = "com.killer560.hub.roomsim.SimBuilder";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";

    private static final int PRINCE_ARGB = 0xFF00FFFF;

    private static final String SOURCE_ROOMS = ModUnderTest.instanceConfig(Machine.roomsConfigDir(), "killer560smod-rooms");

    private final List<String> failures = new ArrayList<>();
    private int viewsChecked;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        ctx.waitTicks(20);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (copyDir(SOURCE_ROOMS, "killer560smod-rooms", ".json") < 20 || Scenario.ensureRoomDatabase(ctx) == 0) {
            Scenario.skipped(NAME, "needs real room captures and room database");
            return;
        }
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);

        Object cfg = ModUnderTest.config(CFG);
        boolean wasEnabled = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.call(cfg, "isEnabled",
                new Class<?>[]{}, new Object[]{}));
        try {
            ctx.runOnClient(mc -> {
                ModUnderTest.set(cfg, "setEnabled", true);
                ModUnderTest.set(cfg, "setShowCrypts", false);
                ModUnderTest.set(cfg, "setShowPrinces", true);
                ModUnderTest.set(cfg, "setHidePrincesAfterOne", false);
                ModUnderTest.set(cfg, "setThroughWalls", true);
                ModUnderTest.set(cfg, "setShowNames", false);
                ModUnderTest.set(cfg, "setPrinceColor", PRINCE_ARGB);
                ModUnderTest.call(cfg, "setStyle", new Class<?>[]{enumClass(CFG + "$Style")},
                        new Object[]{ModUnderTest.enumValue(CFG + "$Style", "FILL_OUTLINE")});
            });
            int rooms = 0;
            for (String room : ROOMS) {
                if (room(ctx, cfg, room)) {
                    rooms++;
                }
            }
            if (rooms == 0 || viewsChecked == 0) {
                throw new AssertionError("no prince box was ever photographed (rooms with a prince " + rooms + ", views "
                        + viewsChecked + ") - nothing was tested");
            }
            if (!failures.isEmpty()) {
                throw new AssertionError("the prince box is not drawn whole in " + failures.size() + " view(s):\n  "
                        + String.join("\n  ", failures));
            }
            System.out.println("[" + NAME + "] PASS - " + viewsChecked + " views over " + rooms + " room(s): every edge "
                    + "and face of the prince box on screen");
        } finally {
            ctx.runOnClient(mc -> {
                try {
                    ModUnderTest.set(cfg, "setShowPrinces", false);
                    ModUnderTest.set(cfg, "setHidePrincesAfterOne", true);
                    ModUnderTest.set(cfg, "setEnabled", wasEnabled);
                    ModUnderTest.set(cfg, "setPrinceColor", 0xFFFFD700);
                } catch (Throwable ignored) {
                    // cleanup must not replace the verdict
                }
            });
        }
    }

    /** Builds one room and checks its prince from four sides; false when the room shows no prince. */
    private boolean room(ClientGameTestContext ctx, Object cfg, String room) {
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> ModUnderTest.staticCall(BUILDER, "buildSingleRoom",
                new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, room})));
        ctx.waitFor(mc -> mc.level != null && mc.player != null, 2400);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitFor(mc -> McCompat.screen(mc) == null, 1200);
        try {
            int landed = -1;
            for (int t = 0; t < 1800; t++) {
                boolean ok = ctx.computeOnClient(mc -> mc.player != null && mc.player.onGround()
                        && !mc.level.getBlockState(mc.player.blockPosition().below()).isAir());
                if (ok) {
                    landed = t;
                    break;
                }
                ctx.waitTicks(1);
            }
            if (landed < 0) {
                failures.add(room + ": never stood on the room");
                return false;
            }
            ctx.waitTicks(40);
            ctx.runOnClient(mc -> ModUnderTest.staticCall(FEATURE, "invalidateCache"));
            ctx.waitTicks(30);
            List<double[]> boxes = boxes(ctx);
            say(room + ": prince boxes " + boxes.stream().map(SimPrinceBoxTests::fmt).toList());
            if (boxes.isEmpty()) {
                return false;
            }
            double[] b = boxes.get(0);
            String lid = lidOutside(ctx, b);
            say(room + ": lid check " + (lid == null ? "every touching lid is inside the box" : lid));
            if (lid != null) {
                failures.add(room + ": " + lid);
            }
            creativeFlying(ctx);
            ctx.runOnClient(mc -> {
                McCompat.setHudHidden(mc, true);
                McCompat.clearChatAndToasts(mc);
                // flying widens the FOV, which the projection below does not model
                mc.options.fovEffectScale().set(0.0);
            });
            for (int yaw = 45; yaw < 360; yaw += 90) {
                view(ctx, cfg, room, b, yaw, false);
                view(ctx, cfg, room, b, yaw, true);
            }
            return true;
        } finally {
            ctx.runOnClient(mc -> {
                McCompat.setHudHidden(mc, false);
                mc.options.fovEffectScale().set(1.0);
            });
            teardown(ctx);
        }
    }

    /**
     * One view from {@code yawDeg} above the box. With {@code mob}, a still zombie stands between the camera and the
     * box (the Prince is a mob standing at its tomb): through walls, the box must be drawn over it too.
     */
    private void view(ClientGameTestContext ctx, Object cfg, String room, double[] b, int yawDeg, boolean mob) {
        double cx = (b[0] + b[3]) / 2, cy = (b[1] + b[4]) / 2, cz = (b[2] + b[5]) / 2;
        double half = Math.max(b[3] - b[0], b[5] - b[2]) / 2;
        double eyeY = b[4] + 1.6;
        double rad = Math.toRadians(yawDeg);
        double[] at = null;
        for (double d = half + 3.0; d <= half + 7.0; d += 0.5) {
            double x = cx + Math.sin(rad) * d, z = cz + Math.cos(rad) * d;
            double feet = eyeY - 1.62;
            // the whole body, eye included, and 0.35 around it: vanilla draws the in-wall overlay (and nothing else) when
            // a block is within the player's half-width of the eye, not only when the eye's own block is solid
            boolean free = ctx.computeOnClient(mc -> {
                for (double ox = -0.35; ox <= 0.35; ox += 0.35) {
                    for (double oz = -0.35; oz <= 0.35; oz += 0.35) {
                        for (double oy = 0; oy <= 1.62 + 0.3; oy += 0.5) {
                            if (!mc.level.getBlockState(BlockPos.containing(x + ox, feet + Math.min(oy, 1.92), z + oz)).isAir()) {
                                return false;
                            }
                        }
                    }
                }
                return true;
            });
            if (free) {
                at = new double[]{x, feet, z};
                break;
            }
        }
        if (at == null) {
            say(room + " yaw " + yawDeg + ": no open spot on this side - view skipped");
            return;
        }
        place(ctx, at, new double[]{cx, cy, cz});
        if (mob) {
            // 0.9 blocks out from the box toward the camera, feet on the box's bottom, no AI, no gravity
            double mx = cx + Math.sin(rad) * (half + 0.9), mz = cz + Math.cos(rad) * (half + 0.9);
            String cmd = String.format(Locale.ROOT, "summon zombie %.2f %.2f %.2f {NoAI:1b,NoGravity:1b,Silent:1b,"
                    + "Invulnerable:1b,PersistenceRequired:1b,Tags:[\"pbox\"]}", mx, b[1], mz);
            serverCommand(ctx, cmd);
            ctx.waitFor(mc -> !mc.level.getEntitiesOfClass(net.minecraft.world.entity.monster.zombie.Zombie.class,
                    new net.minecraft.world.phys.AABB(mx - 1, b[1] - 1, mz - 1, mx + 1, b[1] + 3, mz + 1)).isEmpty(), 100);
        }
        ctx.waitTicks(25);
        if (boxes(ctx).isEmpty()) {
            say(String.format(Locale.ROOT, "%s yaw %d: spot %.1f,%.1f,%.1f is outside the room - view skipped", room, yawDeg,
                    at[0], at[1], at[2]));
            if (mob) {
                serverCommand(ctx, "kill @e[tag=pbox]");
            }
            return;
        }
        String tag = room.replace(' ', '_') + "-" + yawDeg + (mob ? "-mob" : "");
        BufferedImage on = shot(ctx, tag + "-on");
        Cam cam = ctx.computeOnClient(SimPrinceBoxTests::camera);
        ctx.runOnClient(mc -> {
            ModUnderTest.set(cfg, "setShowPrinces", false);
            ModUnderTest.staticCall(FEATURE, "invalidateCache");
        });
        ctx.waitTicks(15);
        BufferedImage off = shot(ctx, tag + "-off");
        ctx.runOnClient(mc -> {
            ModUnderTest.set(cfg, "setShowPrinces", true);
            ModUnderTest.staticCall(FEATURE, "invalidateCache");
        });
        ctx.waitTicks(15);

        if (mob) {
            serverCommand(ctx, "kill @e[tag=pbox]");
            ctx.waitTicks(5);
        }
        int[] edges = edgeSamples(cam, on, b);
        int[] faces = faceSamples(cam, on, off, b);
        viewsChecked++;
        String line = String.format(Locale.ROOT, "%s yaw %d%s from %.1f,%.1f,%.1f: edges %d/%d, faces %d/%d",
                room, yawDeg, mob ? " (zombie in front)" : "", at[0], at[1], at[2], edges[0], edges[1], faces[0], faces[1]);
        say(line);
        if (edges[1] < 40 || faces[1] < 40) {
            failures.add(line + " - too little of the box on screen to judge");
        } else if (edges[0] < 0.9 * edges[1] || faces[0] < 0.9 * faces[1]) {
            failures.add(line);
        }
    }

    /**
     * Null when every lid touching the prince box lies inside it, else what is left out: each smooth stone slab within one
     * block of the box (horizontally, at the box's heights) that is outside it, flooded along its own layer, and kept
     * when the run has 6+ slabs (a crypt lid's minimum).
     */
    private static String lidOutside(ClientGameTestContext ctx, double[] b) {
        return ctx.computeOnClient(mc -> {
            int x0 = (int) Math.floor(b[0]), y0 = (int) Math.floor(b[1]), z0 = (int) Math.floor(b[2]);
            int x1 = (int) Math.ceil(b[3]) - 1, y1 = (int) Math.ceil(b[4]) - 1, z1 = (int) Math.ceil(b[5]) - 1;
            java.util.Set<BlockPos> seen = new java.util.HashSet<>();
            for (int x = x0 - 1; x <= x1 + 1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0 - 1; z <= z1 + 1; z++) {
                        BlockPos p = new BlockPos(x, y, z);
                        if (seen.contains(p) || !mc.level.getBlockState(p).is(net.minecraft.world.level.block.Blocks.SMOOTH_STONE_SLAB)) {
                            continue;
                        }
                        java.util.List<BlockPos> run = new java.util.ArrayList<>();
                        java.util.ArrayDeque<BlockPos> q = new java.util.ArrayDeque<>();
                        q.add(p);
                        seen.add(p);
                        while (!q.isEmpty() && run.size() < 64) {
                            BlockPos h = q.poll();
                            run.add(h);
                            for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                                BlockPos n = h.relative(d);
                                if (!seen.contains(n) && mc.level.getBlockState(n).is(net.minecraft.world.level.block.Blocks.SMOOTH_STONE_SLAB)) {
                                    seen.add(n);
                                    q.add(n);
                                }
                            }
                        }
                        if (run.size() < 6) {
                            continue;
                        }
                        int out = 0;
                        for (BlockPos r : run) {
                            if (r.getX() < x0 || r.getX() > x1 || r.getZ() < z0 || r.getZ() > z1 || r.getY() < y0 || r.getY() > y1) {
                                out++;
                            }
                        }
                        if (out > 0) {
                            return "a lid of " + run.size() + " smooth stone slabs at " + run.get(0).toShortString()
                                    + " touches the prince box " + fmt(b) + " but " + out + " of them are outside it";
                        }
                    }
                }
            }
            return null;
        });
    }

    // ---- sampling -----------------------------------------------------------------------------------------------

    /** {hit, usable}: 9 points on each of the 12 edges with 4+ outline pixels within 2 px. */
    private static int[] edgeSamples(Cam cam, BufferedImage img, double[] b) {
        double[] xs = {b[0], b[3]}, ys = {b[1], b[4]}, zs = {b[2], b[5]};
        int hit = 0, usable = 0;
        for (int axis = 0; axis < 3; axis++) {
            for (int i = 0; i < 2; i++) {
                for (int j = 0; j < 2; j++) {
                    for (int s = 1; s <= 9; s++) {
                        double t = s / 10.0;
                        Vec3 p = axis == 0 ? new Vec3(b[0] + t * (b[3] - b[0]), ys[i], zs[j])
                                : axis == 1 ? new Vec3(xs[i], b[1] + t * (b[4] - b[1]), zs[j])
                                : new Vec3(xs[i], ys[j], b[2] + t * (b[5] - b[2]));
                        int[] px = cam.project(p, img.getWidth(), img.getHeight());
                        if (px == null) {
                            continue;
                        }
                        usable++;
                        if (cyanCount(img, px, 2) >= 4) {
                            hit++;
                        }
                    }
                }
            }
        }
        return new int[]{hit, usable};
    }

    /** {changed, usable}: a 5x5 grid inside each of the 6 faces whose pixel differs with the box on and off. */
    private static int[] faceSamples(Cam cam, BufferedImage on, BufferedImage off, double[] b) {
        int changed = 0, usable = 0;
        for (int axis = 0; axis < 3; axis++) {
            for (int side = 0; side < 2; side++) {
                for (int u = 1; u <= 5; u++) {
                    for (int v = 1; v <= 5; v++) {
                        double a = (u - 0.5) / 5.0 * 0.8 + 0.1, c = (v - 0.5) / 5.0 * 0.8 + 0.1;
                        Vec3 p;
                        if (axis == 0) {
                            p = new Vec3(side == 0 ? b[0] : b[3], b[1] + a * (b[4] - b[1]), b[2] + c * (b[5] - b[2]));
                        } else if (axis == 1) {
                            p = new Vec3(b[0] + a * (b[3] - b[0]), side == 0 ? b[1] : b[4], b[2] + c * (b[5] - b[2]));
                        } else {
                            p = new Vec3(b[0] + a * (b[3] - b[0]), b[1] + c * (b[4] - b[1]), side == 0 ? b[2] : b[5]);
                        }
                        int[] px = cam.project(p, on.getWidth(), on.getHeight());
                        if (px == null) {
                            continue;
                        }
                        usable++;
                        int x = on.getRGB(px[0], px[1]), y = off.getRGB(px[0], px[1]);
                        int d = Math.abs(((x >> 16) & 0xFF) - ((y >> 16) & 0xFF)) + Math.abs(((x >> 8) & 0xFF) - ((y >> 8) & 0xFF))
                                + Math.abs((x & 0xFF) - (y & 0xFF));
                        if (d >= 24) {
                            changed++;
                        }
                    }
                }
            }
        }
        return new int[]{changed, usable};
    }

    private static int cyanCount(BufferedImage img, int[] p, int rad) {
        int n = 0;
        for (int dy = -rad; dy <= rad; dy++) {
            for (int dx = -rad; dx <= rad; dx++) {
                int x = p[0] + dx, y = p[1] + dy;
                if (x < 0 || y < 0 || x >= img.getWidth() || y >= img.getHeight()) {
                    continue;
                }
                int rgb = img.getRGB(x, y);
                if (((rgb >> 16) & 0xFF) <= 40 && ((rgb >> 8) & 0xFF) >= 215 && (rgb & 0xFF) >= 215) {
                    n++;
                }
            }
        }
        return n;
    }

    // ---- camera -------------------------------------------------------------------------------------------------

    private record Cam(Vec3 pos, float yaw, float pitch, double fov) {
        int[] project(Vec3 p, int w, int h) {
            Vec3 f = Vec3.directionFromRotation(pitch, yaw);
            Vec3 r = f.cross(new Vec3(0, 1, 0)).normalize();
            Vec3 u = r.cross(f);
            Vec3 d = p.subtract(pos);
            double z = d.dot(f);
            if (z < 0.06) {
                return null;
            }
            double t = Math.tan(Math.toRadians(fov) / 2);
            double nx = d.dot(r) / (z * t * ((double) w / h));
            double ny = d.dot(u) / (z * t);
            int sx = (int) Math.round((nx + 1) / 2 * w);
            int sy = (int) Math.round((1 - ny) / 2 * h);
            return sx < 4 || sy < 4 || sx >= w - 4 || sy >= h - 4 ? null : new int[]{sx, sy};
        }
    }

    private static Cam camera(Minecraft mc) {
        Object camera = null;
        for (String name : new String[]{"getMainCamera", "mainCamera"}) {
            try {
                camera = mc.gameRenderer.getClass().getMethod(name).invoke(mc.gameRenderer);
                break;
            } catch (NoSuchMethodException ignored) {
                // the other version's name
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }
        if (camera == null) {
            throw new AssertionError("GameRenderer has no camera accessor");
        }
        try {
            Vec3 pos = (Vec3) camera.getClass().getMethod("position").invoke(camera);
            float yaw = (Float) camera.getClass().getMethod("yRot").invoke(camera);
            float pitch = (Float) camera.getClass().getMethod("xRot").invoke(camera);
            return new Cam(pos, yaw, pitch, mc.options.fov().get());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("no Camera.position()/yRot()/xRot()", e);
        }
    }

    private static BufferedImage shot(ClientGameTestContext ctx, String tag) {
        Path shot = ctx.takeScreenshot(dev.testkit.harness.Report.fileName(NAME + "-" + tag));
        dev.testkit.harness.Report.screenshot(NAME + "-" + tag, shot);
        say("screenshot " + tag + ": " + shot.toAbsolutePath());
        try {
            return javax.imageio.ImageIO.read(shot.toFile());
        } catch (java.io.IOException e) {
            throw new AssertionError("could not read the screenshot " + shot, e);
        }
    }

    // ---- moving -------------------------------------------------------------------------------------------------

    private static void creativeFlying(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
                if (sp != null) {
                    sp.setGameMode(GameType.CREATIVE);
                    sp.getAbilities().mayfly = true;
                    sp.getAbilities().flying = true;
                    sp.onUpdateAbilities();
                }
            });
        });
        ctx.waitFor(mc -> mc.player.getAbilities().flying, 200);
    }

    /** Server teleport to {@code at}, then the camera faces {@code look} exactly. */
    private static void place(ClientGameTestContext ctx, double[] at, double[] look) {
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
                if (sp != null) {
                    sp.teleportTo(at[0], at[1], at[2]);
                }
            });
        });
        ctx.waitFor(mc -> mc.player.position().distanceToSqr(at[0], at[1], at[2]) < 0.01, 200);
        ctx.runOnClient(mc -> {
            mc.player.getAbilities().flying = true;
            mc.player.onUpdateAbilities();
            double dx = look[0] - mc.player.getX();
            double dy = look[1] - mc.player.getEyeY();
            double dz = look[2] - mc.player.getZ();
            mc.player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
            mc.player.setXRot((float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
        });
    }

    private static void serverCommand(ClientGameTestContext ctx, String cmd) {
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd));
        });
        ctx.waitTicks(2);
    }

    // ---- plumbing -----------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static List<double[]> boxes(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> new ArrayList<>((List<double[]>) ModUnderTest.staticCall(FEATURE,
                "cachedBoxes", new Class<?>[]{String.class}, new Object[]{"PRINCE"})));
    }

    private static String fmt(double[] b) {
        return String.format(Locale.ROOT, "%.2f,%.2f,%.2f..%.2f,%.2f,%.2f", b[0], b[1], b[2], b[3], b[4], b[5]);
    }

    private static void say(String s) {
        System.out.println("[" + NAME + "] " + s);
    }

    private static Class<?> enumClass(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new AssertionError("no class " + name, e);
        }
    }

    private static void teardown(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            try {
                ModUnderTest.staticCall(SIM_STATE, "leave", new Class<?>[]{}, new Object[]{});
            } catch (Throwable ignored) {
                // never let cleanup replace the verdict
            }
        });
        ctx.runOnClient(mc -> mc.execute(() -> {
            if (mc.level != null) {
                mc.level.disconnect(net.minecraft.network.chat.Component.literal("scenario over"));
                mc.disconnectWithSavingScreen();
            }
        }));
        ctx.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null, 1200);
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> mc.execute(() ->
                McCompat.setScreen(mc, new net.minecraft.client.gui.screens.TitleScreen())));
        ctx.waitFor(mc -> McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.TitleScreen, 400);
    }

    private static int copyDir(String from, String into, String suffix) {
        try {
            Path source = Path.of(from);
            if (!Files.isDirectory(source)) {
                return 0;
            }
            Path target = ModUnderTest.modConfig(into);
            Files.createDirectories(target);
            int n = 0;
            try (var s = Files.list(source)) {
                for (Path f : s.toList()) {
                    if (Files.isRegularFile(f) && f.toString().endsWith(suffix)) {
                        Files.copy(f, target.resolve(f.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        n++;
                    }
                }
            }
            return n;
        } catch (Exception e) {
            return 0;
        }
    }
}
