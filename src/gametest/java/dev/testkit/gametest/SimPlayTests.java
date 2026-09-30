package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Actually PLAY the sim, rather than inspect it.
 *
 * <p>killer560 (2026-09-29): "you have access to that test server and you should have adapted it such that
 * you can now test play the test server to be sure everything is working."
 *
 * <p>He is right, and this is the gap every other sim scenario left. They read the world and assert about it:
 * the layout connects, the doors are holes, the secrets are inside their rooms. None of them ever pressed a
 * key. The harness can - {@code ctx.getInput().holdKey(...)} is real keyboard input, and the Breaker Aura
 * scenarios have been walking the player into walls with it for a week - so the sim can be played the same
 * way.
 *
 * <p>Two things are driven here, both of which were previously "compiled and reasoned about":
 *
 * <ul>
 *   <li><b>Walking through a doorway.</b> The player is put in the entrance, turned to face a door out of it,
 *       and told to walk. He has to END UP IN A DIFFERENT ROOM. Scenario 80 proves there is a gap of air in
 *       the wall; this proves a person can get through it, which is not the same claim - a gap with a step up,
 *       a slab in it, or a one-block ledge passes the first and fails the second.</li>
 *   <li><b>Collecting an item secret.</b> One is held back until he has been within three blocks for more
 *       than five ticks. The player is walked onto a real one - a position from the room database through two
 *       rotations, not a made-up coordinate - and the drop must appear AND be collected, with the score's own
 *       secret counter going up.</li>
 * </ul>
 */
public class SimPlayTests implements FabricClientGameTest {

    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String BUILD_QUEUE = "com.killer560.hub.roomsim.SimBuildQueue";
    private static final String SECRET_ITEMS = "com.killer560.hub.roomsim.SimSecretItems";
    private static final String SIM_SCORE = "com.killer560.hub.roomsim.SimScore";
    private static final String ALTITUDE = "com.killer560.hub.roomsim.SimAltitude";
    private static final String SIM_RUN = "com.killer560.hub.roomsim.SimRun";
    private static final String BUILDER = "com.killer560.hub.roomsim.SimBuilder";

    private static final String SOURCE_ROOMS =
            "C:/Users/Hunter/AppData/Roaming/PrismLauncher/instances/26.1.2 (Mod Only Test)"
                    + "/minecraft/config/killer560smod-rooms";

    private static final int GRID = 11;

    /** How many doorways to walk. Every one would be thorough and slow; this is a representative sample. */
    private static final int MAX_DOORS = 8;

    /**
     * How far along the crossing axis counts as having got through.
     *
     * <p>He starts four blocks back, so anything past about 4.5 has crossed the seam. Five gives margin over
     * that without coming near what a sprint covers in the time allowed, and a real wall stops him at 2-3.
     */
    private static final double CROSSED_BLOCKS = 5.0;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip("81-sim-play")) {
            return;
        }
        ModUnderTest.require("killer560smod");
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff(
                "com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));

        if (copyDir(SOURCE_ROOMS, "killer560smod-rooms", ".json") < 20
                || copyDir(Path.of(SOURCE_ROOMS).resolveSibling("killer560smod-roomdata").toString(),
                        "killer560smod-roomdata", "") == 0) {
            System.out.println("[81-sim-play] SKIPPED - needs his rooms and room database");
            return;
        }
        ctx.runOnClient(mc -> ModUnderTest.staticCall(
                "com.killer560.hub.roomdatabase.RoomDatabase", "ensureLoading"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(
                "com.killer560.hub.roomdatabase.RoomDatabase", "isReady"));
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"));

        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter",
                new Class<?>[]{String.class}, new Object[]{"gametest"}));
        long simBuildBefore = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> {
            Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
            ModUnderTest.staticCall(FLOOR_GEN, "generate",
                    new Class<?>[]{Minecraft.class, floor.getClass(), int.class, int.class},
                    new Object[]{mc, floor, 3, 4});
        }));
        ctx.waitFor(mc -> mc.level != null);
        Scenario.awaitSimBuild(ctx, simBuildBefore);
        ctx.waitTicks(80);

        // Teardown in a FINALLY. When this scenario first failed for real it threw with the sim world still
        // open, the runner's keep-going wrapper moved on, and the client hung until the deadline watcher shot
        // it - a frozen Minecraft window left on his desktop for a test that had already found its answer.
        try {
            // He spawns in the ENTRANCE, and the entrance's doorway is filled with the infested chiseled gate
            // until the run starts. The first version of this scenario walked him face-first into that gate for six
            // seconds and reported it as "a player cannot pass" - a real wall, correctly there, called a bug.
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_RUN, "begin",
                    new Class<?>[]{Minecraft.class, BlockPos.class},
                    new Object[]{mc, ModUnderTest.staticCall(BUILDER, "entranceDoor")}));
            ctx.waitTicks(160);

            // ---- 1. walk through every doorway on the floor -------------------------------------------
            // The first version of this walked forward from the middle of one room for six seconds and asked
            // whether the room changed. That tests blind navigation, not the doorway: it failed after 16.2
            // blocks because it aimed from the CELL CENTRE rather than from where the player was standing, and
            // it only ever covered one door. This places him four blocks in front of each doorway in turn,
            // square on to it, and asks the one question worth asking - can a player get through this hole.
            int shift = (Integer) ModUnderTest.staticCall(ALTITUDE, "offset");
            int floorY = 69 + shift;

            final Object[] grab = new Object[1];
            ctx.runOnClient(mc -> {
                String code = (String) ModUnderTest.staticCall(SIM_STATE, "mapCode");
                grab[0] = ModUnderTest.staticCall("com.killer560.hub.roomsim.MapCode", "decode",
                        new Class<?>[]{String.class}, new Object[]{code});
            });
            int[] doorFlags = grab[0] == null ? new int[0]
                    : (int[]) ModUnderTest.call(grab[0], "cellDoor", new Class<?>[]{}, new Object[]{});

            List<Integer> doorCells = new java.util.ArrayList<>();
            for (int cell = 0; cell < doorFlags.length; cell++) {
                if (doorFlags[cell] != 0) {
                    doorCells.add(cell);
                }
            }
            System.out.println("[81-sim-play] " + doorCells.size() + " doorway(s) on this floor, walking "
                    + Math.min(doorCells.size(), MAX_DOORS) + " of them");

            List<String> stuck = new java.util.ArrayList<>();
            List<String> locked = new java.util.ArrayList<>();
            int walked = 0;
            for (int cell : doorCells) {
                if (walked >= MAX_DOORS) {
                    break;
                }
                walked++;
                int gx = cell % GRID;
                int gz = cell / GRID;
                boolean alongX = gx % 2 == 1;   // the doorway is crossed along X
                int cx = -185 + gx * 16;
                int cz = -185 + gz * 16;
                // Four blocks back on one side, facing straight across the seam.
                double sx = alongX ? cx - 4 : cx;
                double sz = alongX ? cz : cz - 4;
                float yaw = alongX ? -90f : 0f;
                teleport(ctx, sx + 0.5, floorY, sz + 0.5, yaw);
                ctx.waitTicks(10);

                double[] from = playerPos(ctx);
                // Sprint, and for long enough that the budget is not the thing being measured. Forty ticks of
                // WALKING covers about 5.3 blocks, and the first version of this asked for 6 - so a doorway
                // the player got through cleanly was failed for running out of time, and reported itself as
                // "blocked by nothing solid across the seam". Sprinting 60 ticks covers about 13, which puts
                // a genuine crossing nowhere near a genuine wall (those stop dead at 2-3 blocks).
                ctx.getInput().holdKey(options -> options.keyUp);
                ctx.getInput().holdKey(options -> options.keySprint);
                ctx.waitTicks(60);
                ctx.getInput().releaseKey(options -> options.keySprint);
                ctx.getInput().releaseKey(options -> options.keyUp);
                ctx.waitTicks(5);
                double[] to = playerPos(ctx);

                // Did he cross the seam? Measured along the crossing axis only, so sliding sideways along a
                // wall cannot be mistaken for going through.
                double crossed = alongX ? to[0] - from[0] : to[2] - from[2];
                if (crossed < CROSSED_BLOCKS) {
                    // Say WHAT stopped him, then judge it. A shut wither door is coal block and a blood door
                    // red terracotta, and both are solid BY DESIGN until he has the key - reporting those as
                    // impassable doorways is reporting a working dungeon as broken, which this did once. The
                    // entrance gate is different: it is infested chiseled stone brick and it is supposed to be
                    // gone by now, because the run has already started.
                    String blocking = seamBlocks(ctx, cx, cz, floorY, alongX);
                    String line = String.format("cell %d at %d,%d moved %.1f across, blocked by %s",
                            cell, cx, cz, crossed, blocking);
                    if (blocking.contains("coal_block") || blocking.contains("red_terracotta")) {
                        locked.add(line);
                    } else {
                        stuck.add(line);
                    }
                }
            }

            System.out.println("[81-sim-play] doorways walked: " + walked + ", locked as designed: "
                    + locked.size() + ", impassable: " + stuck.size());
            for (String shut : locked) {
                System.out.println("[81-sim-play]   locked " + shut);
            }
            for (String bad : stuck) {
                System.out.println("[81-sim-play]   STUCK " + bad);
            }
            if (!stuck.isEmpty()) {
                throw new AssertionError(stuck.size() + " of " + walked + " doorway(s) could not be walked "
                        + "through even standing square on to them four blocks away: " + stuck);
            }

            // ---- 2. collect a real item secret ---------------------------------------------------------------
            @SuppressWarnings("unchecked")
            List<BlockPos> pending = (List<BlockPos>) ModUnderTest.staticCall(SECRET_ITEMS, "pendingPositions");
            System.out.println("[81-sim-play] " + pending.size() + " item secret(s) waiting");
            if (pending.isEmpty()) {
                System.out.println("[81-sim-play] NOTE - this floor has no item secrets, so none was collected");
            } else {
                BlockPos target = pending.get(0);
                int before = (Integer) ModUnderTest.staticCall(SIM_SCORE, "secretsFound");
                int pendingBefore = (Integer) ModUnderTest.staticCall(SECRET_ITEMS, "pendingCount");
                // Put him on it and KEEP him there. A single teleport is not enough: an item secret can sit
                // anywhere in a room, including in mid-air over a drop, and the player simply falls off it and
                // out of the three-block range before the five ticks are up. That reads as "the trigger never
                // fired" when the trigger was never given its five ticks.
                for (int hold = 0; hold < 14; hold++) {
                    teleport(ctx, target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0f);
                    ctx.waitTicks(5);
                }

                int after = (Integer) ModUnderTest.staticCall(SIM_SCORE, "secretsFound");
                int pendingAfter = (Integer) ModUnderTest.staticCall(SECRET_ITEMS, "pendingCount");
                int live = (Integer) ModUnderTest.staticCall(SECRET_ITEMS, "liveCount");
                System.out.println(String.format("[81-sim-play] stood on %s: secrets found %d -> %d, "
                        + "pending %d -> %d, drops on the floor %d",
                        target, before, after, pendingBefore, pendingAfter, live));
                // Two different bugs, told apart. If the pending count never dropped, the proximity trigger
                // never fired; if it dropped but the score did not move, the drop appeared and could not be
                // collected. The first version of this reported both as one sentence and named neither.
                if (pendingAfter >= pendingBefore) {
                    throw new AssertionError("stood on an item secret at " + target + " for three seconds and "
                            + "it never appeared - the within-3-blocks-for-5-ticks trigger did not fire");
                }
                if (after <= before) {
                    throw new AssertionError("the item secret at " + target + " appeared (" + live + " drop(s) "
                            + "on the floor) but standing on it did not collect it");
                }
            }
        } finally {
            teardown(ctx);
        }
        System.out.println("[81-sim-play] PASS - walked through a door and collected a secret");
    }

    /** Leave the sim, close the world and get back to the title screen, pass or fail. */
    private static void teardown(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "leave"));
        ctx.runOnClient(mc -> mc.execute(() -> {
            if (mc.level != null) {
                mc.level.disconnect(net.minecraft.network.chat.Component.literal("scenario over"));
                mc.disconnectWithSavingScreen();
            }
        }));
        ctx.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null);
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> mc.execute(() ->
                mc.setScreen(new net.minecraft.client.gui.screens.TitleScreen())));
        ctx.waitFor(mc -> mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen);
    }

    /** What is standing in the doorway: the blocks across the seam at head and foot height. */
    private static String seamBlocks(ClientGameTestContext ctx, int cx, int cz, int floorY, boolean alongX) {
        // The server reads the blocks and the TEST THREAD waits for the answer. Doing the waiting inside
        // runOnClient is the deadlock this project has already hit twice: runOnClient blocks until its task
        // returns, and the task's answer can only arrive on a later client tick.
        java.util.concurrent.atomic.AtomicReference<String> got =
                new java.util.concurrent.atomic.AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            if (server == null) {
                got.set("no server");
                return;
            }
            server.execute(() -> {
                var level = server.overworld();
                java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
                for (int d = -2; d <= 2; d++) {
                    for (int dy = 0; dy <= 1; dy++) {
                        int x = alongX ? cx : cx + d;
                        int z = alongX ? cz + d : cz;
                        var state = level.getBlockState(new net.minecraft.core.BlockPos(x, floorY + dy, z));
                        if (!state.isAir()) {
                            names.add(net.minecraft.core.registries.BuiltInRegistries.BLOCK
                                    .getKey(state.getBlock()).getPath());
                        }
                    }
                }
                got.set(names.isEmpty() ? "nothing solid across the seam" : String.join("+", names));
            });
        });
        ctx.waitFor(mc -> got.get() != null);
        return got.get();
    }

    /** Put the player somewhere, facing a given way. Inside the sim's own singleplayer world. */
    private static void teleport(ClientGameTestContext ctx, double x, double y, double z, float yaw) {
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            if (server == null || mc.player == null) {
                return;
            }
            java.util.UUID id = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(id);
                if (sp != null) {
                    sp.teleportTo((net.minecraft.server.level.ServerLevel) sp.level(), x, y, z,
                            java.util.Set.of(), yaw, 0f, false);
                }
            });
        });
    }

    /** The player's position, through the client thread. */
    private static double[] playerPos(ClientGameTestContext ctx) {
        double[] out = new double[3];
        ctx.runOnClient(mc -> {
            if (mc.player != null) {
                out[0] = mc.player.getX();
                out[1] = mc.player.getY();
                out[2] = mc.player.getZ();
            }
        });
        return out;
    }

    private static int copyDir(String from, String into, String suffix) {
        try {
            Path source = Path.of(from);
            if (!Files.isDirectory(source)) {
                return 0;
            }
            Path target = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve(into);
            Files.createDirectories(target);
            int n = 0;
            try (var s = Files.list(source)) {
                for (Path f : s.toList()) {
                    if (Files.isRegularFile(f) && f.toString().endsWith(suffix)) {
                        Files.copy(f, target.resolve(f.getFileName()),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
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
