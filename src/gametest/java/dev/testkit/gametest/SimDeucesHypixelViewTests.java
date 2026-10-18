package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.harness.SuiteVerdict;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 1434-sim-deuces-hypixelview: a screenshot survey of Deuces' chest doorway from killer560's Hypixel viewpoint (mod
 * deuces-door-2-1017). Run only when named.
 *
 * <p>His Hypixel shot of 2026-10-16 22:21 (killer560s-mod-logs/deuces-door-2/hypixel-2026-10-16_22.21.06.png): F7, Deuces
 * entered from Lava Ravine to the SOUTH, the chest doorway leading NORTH into the Fairy room (his log: "Dueces - WEST", and
 * the map's head upright, i.e. facing north). So the chest doorway (capture W0) faced north: Deuces pasted at 90.
 *
 * <p>Builds a solo Deuces at {@code -Dtestkit.deuces.rotation} (default 90), then for each camera (feet at capture x in
 * {@code -Dtestkit.deuces.xs}, z 16.5, facing the chest doorway, pitch in {@code -Dtestkit.deuces.pitches}, FOV 100 as in
 * his options) takes a shot of the room as built, then of each variant of the box over the doorway's trim, and copies
 * them to {@code killer560s-mod-logs/deuces-door-2/renders/}. The variants are edits on the server's level, in capture
 * coordinates turned by the paste, so the same camera sees each. It asserts only that the room was built and the chest
 * is where the capture has it; what the pictures show is for a person to compare.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimDeucesHypixelViewTests implements FabricClientGameTest {

    private static final String NAME = "1434-sim-deuces-hypixelview";
    private static final String ROOM = "Dueces";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String BUILDER = "com.killer560.hub.roomsim.SimBuilder";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String ALTITUDE = "com.killer560.hub.roomsim.SimAltitude";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final Path OUT = Path.of(System.getProperty("user.home"), "killer560s-mod-logs", "deuces-door-2",
            "renders");
    /** The solo room's cell, as 1433's solo checks use. */
    private static final int CELL = 4;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank() || Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        SimMapTests.copyRoomsForOthers();
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);
        List<String> failures = new ArrayList<>();
        int rotation = Integer.getInteger("testkit.deuces.rotation", 90);
        String tag = System.getProperty("testkit.deuces.tag", "built");
        try {
            solo(ctx, rotation);
            int[] map = findMap(ctx);
            if (map == null) {
                failures.add("premise: no chest at capture (3, 78, 16) under any turn of the paste");
                return;
            }
            println("paste turn read from the chest: map " + map[0] + ", origin " + map[1] + "," + map[2] + " dy " + map[3]);
            String[] xs = System.getProperty("testkit.deuces.xs", "7.5,8.5,9.5,10.5").split(",");
            String[] pitches = System.getProperty("testkit.deuces.pitches", "-30,-40").split(",");
            String[] variants = System.getProperty("testkit.deuces.variants", "built,box2air").split(",");
            String[] ys = System.getProperty("testkit.deuces.ys", "69").split(",");
            String[] fovs = System.getProperty("testkit.deuces.fovs", "100").split(",");
            ctx.getInput().resizeWindow(1920, 1080);
            ctx.waitTicks(10);
            for (String variant : variants) {
                applyVariant(ctx, map, variant.trim());
                for (String fv : fovs) {
                    for (String ys1 : ys) {
                        for (String xs1 : xs) {
                            for (String p : pitches) {
                                double cx = Double.parseDouble(xs1.trim());
                                double feet = Double.parseDouble(ys1.trim());
                                float pitch = Float.parseFloat(p.trim());
                                int fov = Integer.parseInt(fv.trim());
                                shot(ctx, map, cx, feet, 16.5, pitch, fov, tag + "-r" + rotation + "-" + variant.trim()
                                        + "-f" + fov + "-x" + xs1.trim() + "-y" + ys1.trim() + "-p" + (int) -pitch);
                            }
                        }
                    }
                }
            }
            ctx.runOnClient(mc -> ModUnderTest.staticCall(BUILDER, "setSingleRoomRotationForTest",
                    new Class<?>[]{int.class}, new Object[]{0}));
        } catch (Throwable t) {
            failures.add("threw: " + t);
            t.printStackTrace();
        } finally {
            teardown(ctx);
            if (failures.isEmpty()) {
                System.out.println("[" + NAME + "] PASS - shots in " + OUT);
            } else {
                String why = String.join("; ", failures);
                System.out.println("[" + NAME + "] FAIL: " + why);
                SuiteVerdict.failCase(NAME, new AssertionError(why));
            }
        }
    }

    private static void solo(ClientGameTestContext ctx, int rotation) {
        ctx.runOnClient(mc -> ModUnderTest.staticCall(BUILDER, "setSingleRoomRotationForTest",
                new Class<?>[]{int.class}, new Object[]{rotation}));
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> ModUnderTest.staticCall(BUILDER, "buildSingleRoom",
                new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, ROOM})));
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitFor(mc -> McCompat.screen(mc) == null, 1200);
        ctx.waitTicks(60);
    }

    /**
     * The paste's turn, read from where the capture's chest landed: {map, x0, z0, dy} with map 0..3 = rotation 0, 90
     * (capture (x, z) to (x0 + 32 - z, z0 + x)), 180, 270 (to (x0 + z, z0 + 32 - x)); null when no turn puts a chest there.
     */
    private static int[] findMap(ClientGameTestContext ctx) {
        AtomicReference<int[]> out = new AtomicReference<>();
        AtomicReference<Boolean> done = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            int dy = (Integer) ModUnderTest.staticCall(ALTITUDE, "offset");
            BlockPos o = (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class, int.class},
                    new Object[]{CELL, CELL});
            int x0 = o.getX() - 16;
            int z0 = o.getZ() - 16;
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                ServerLevel level = server.overworld();
                for (int m = 0; m < 4; m++) {
                    int[] w = block(new int[]{m, x0, z0, dy}, 3, 78, 16);
                    String id = BuiltInRegistries.BLOCK.getKey(level.getBlockState(new BlockPos(w[0], w[1], w[2]))
                            .getBlock()).getPath();
                    if (id.equals("chest") || id.equals("trapped_chest")) {
                        out.set(new int[]{m, x0, z0, dy});
                        break;
                    }
                }
                done.set(true);
            });
        });
        ctx.waitFor(mc -> done.get() != null, 600);
        return out.get();
    }

    /** World block of capture block (x, y, z). */
    private static int[] block(int[] map, int x, int y, int z) {
        int x0 = map[1];
        int z0 = map[2];
        int wy = y + map[3];
        return switch (map[0]) {
            case 1 -> new int[]{x0 + 32 - z, wy, z0 + x};
            case 2 -> new int[]{x0 + 32 - x, wy, z0 + 32 - z};
            case 3 -> new int[]{x0 + z, wy, z0 + 32 - x};
            default -> new int[]{x0 + x, wy, z0 + z};
        };
    }

    /** World point of a continuous capture position. */
    private static double[] point(int[] map, double x, double y, double z) {
        int x0 = map[1];
        int z0 = map[2];
        double wy = y + map[3];
        return switch (map[0]) {
            case 1 -> new double[]{x0 + 33 - z, wy, z0 + x};
            case 2 -> new double[]{x0 + 33 - x, wy, z0 + 33 - z};
            case 3 -> new double[]{x0 + z, wy, z0 + 33 - x};
            default -> new double[]{x0 + x, wy, z0 + z};
        };
    }

    /**
     * Edits the box over the chest doorway (capture W0: wall plane x 1, one in x 2; y 74..76; z 14..18) on the server:
     * "built" leaves it, "box2air" clears the layer one in, "box12air" clears both layers.
     */
    private static void applyVariant(ClientGameTestContext ctx, int[] map, String variant) {
        if (variant.equals("built")) {
            return;
        }
        AtomicReference<Boolean> done = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                ServerLevel level = server.overworld();
                for (int y = 74; y <= 76; y++) {
                    for (int z = 14; z <= 18; z++) {
                        int[] w2 = block(map, 2, y, z);
                        int[] w1 = block(map, 1, y, z);
                        if (variant.startsWith("box2air") || variant.equals("box12air")) {
                            level.setBlock(new BlockPos(w2[0], w2[1], w2[2]), Blocks.AIR.defaultBlockState(), 2);
                        }
                        if (variant.equals("box12air")) {
                            level.setBlock(new BlockPos(w1[0], w1[1], w1[2]), Blocks.AIR.defaultBlockState(), 2);
                        }
                    }
                }
                done.set(true);
            });
        });
        ctx.waitFor(mc -> done.get() != null, 200);
        ctx.waitTicks(40);
        println("variant " + variant + " applied");
    }

    private static void shot(ClientGameTestContext ctx, int[] map, double cx, double feet, double cz, float pitch,
                             int fov, String tag) {
        double[] at = point(map, cx, feet, cz);
        double[] ahead = point(map, cx - 1, feet, cz);
        float yaw = (float) Math.toDegrees(Math.atan2(-(ahead[0] - at[0]), ahead[2] - at[2]));
        AtomicReference<Boolean> done = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(uuid);
                // Flying, so a camera above the floor stays where it is put.
                sp.getAbilities().mayfly = true;
                sp.getAbilities().flying = true;
                sp.onUpdateAbilities();
                sp.teleportTo(server.overworld(), at[0], at[1], at[2], java.util.Set.<net.minecraft.world.entity.Relative>of(),
                        yaw, pitch, false);
                done.set(true);
            });
            mc.player.setYRot(yaw);
            mc.player.setXRot(pitch);
            McCompat.setHudHidden(mc, true);
            mc.options.fov().set(fov);
        });
        ctx.waitFor(mc -> done.get() != null, 100);
        ctx.waitTicks(30);
        ctx.runOnClient(mc -> {
            mc.player.setYRot(yaw);
            mc.player.setXRot(pitch);
        });
        ctx.waitTicks(5);
        Path shot = ctx.takeScreenshot(dev.testkit.harness.Report.fileName(NAME + "-" + tag));
        ctx.runOnClient(mc -> McCompat.setHudHidden(mc, false));
        try {
            Files.createDirectories(OUT);
            Path to = OUT.resolve(tag + ".png");
            Files.copy(shot, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            println("shot " + tag + " (yaw " + yaw + ") -> " + to);
        } catch (java.io.IOException e) {
            println("could not copy the screenshot: " + e);
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

    private static void println(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}
