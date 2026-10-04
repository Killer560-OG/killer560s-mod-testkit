package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Loading ONE room after a floor: does the last floor go away, in the world AND on the map?
 *
 * <p>killer560 (2026-09-30): "if i load only a single room make sure it wipes everything else on the map
 * first and the map should only show the room that i loaded not the previous map."
 *
 * <p>Scenario 71 already loads a single room, but from the MAIN MENU into a brand new world - where there is
 * no previous floor to leave behind, so it cannot see either half of this. This builds a whole F7 first and
 * then asks for one room in the same world, which is what he does.
 *
 * <p>The control is in the run rather than in a second jar: the map is read BEFORE the single-room load as
 * well as after. A run that reports "21 rooms, then 1" has proved the reading can see a multi-room map on
 * this same code path; a broken build would report "21 rooms, then 21" and fail on the same line. The world
 * check works the same way - a block is found standing at the far corner of the floor first, and only then
 * is it required to be gone.
 */
public class SimSingleRoomTests implements FabricClientGameTest {

    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String BUILDER = "com.killer560.hub.roomsim.SimBuilder";
    private static final String ALTITUDE = "com.killer560.hub.roomsim.SimAltitude";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String SCEPTRE = "com.killer560.hub.roomsim.SimSpiritSceptre";

    private static final String SOURCE_ROOMS =
            ModUnderTest.instanceConfig("C:/Users/Hunter/AppData/Roaming/PrismLauncher/instances/26.1.2 (Mod Only Test)"
                    + "/minecraft/config", "killer560smod-rooms");

    /** The room he loaded when he reported this. 2x2, so it also exercises a multi-tile single-room map. */
    private static final String ROOM = "Supertall";

    private static final int START = -185;
    private static final int HALF_ROOM = 16;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip("91-sim-single")) {
            return;
        }
        ModUnderTest.require("killer560smod");
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff(
                "com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));

        if (copyDir(SOURCE_ROOMS, "killer560smod-rooms", ".json") < 20
                || copyDir(Path.of(SOURCE_ROOMS).resolveSibling("killer560smod-roomdata").toString(),
                        "killer560smod-roomdata", "") == 0) {
            System.out.println("[91-sim-single] SKIPPED - needs his real rooms and room database");
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
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> {
            Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
            ModUnderTest.staticCall(FLOOR_GEN, "generate",
                    new Class<?>[]{Minecraft.class, floor.getClass(), int.class, int.class},
                    new Object[]{mc, floor, 3, 4});
        }));
        ctx.waitFor(mc -> mc.level != null);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitTicks(80);

        try {
            int roomsBefore = mapRoomCount(ctx);
            System.out.println("[91-sim-single] the map has " + roomsBefore + " room(s) after the floor build");
            if (roomsBefore < 2) {
                throw new AssertionError("the floor build did not put a multi-room map up, so this scenario "
                        + "cannot tell a map that was replaced from one that was already empty");
            }

            // A block of the floor as far from the single room's cells as the grid goes, so the wipe has to
            // reach it rather than merely cover the middle.
            //
            // FOUND, not assumed. The first version read one fixed position and it came back air - the corner
            // cell's centre column at the dungeon floor is often a doorway, and a room's y band moves with the
            // floor's altitude - so the run printed "air ... air" and the world half of this scenario proved
            // nothing while still passing. A probe that cannot find the floor must say so, not shrug.
            int shift = (Integer) ModUnderTest.staticCall(ALTITUDE, "offset");
            BlockPos far = null;
            String farBefore = "air";
            // Cells of the 11x11 grid the single room can never cover: it goes at the even centre cell (4) and
            // is at most two tiles, so anything at column or row 0 is outside it.
            int[][] probeCells = {{0, 0}, {2, 0}, {0, 2}, {4, 0}, {0, 4}, {6, 0}, {0, 6}, {8, 0}, {0, 8},
                    {10, 0}, {0, 10}};
            outer:
            for (int[] cell : probeCells) {
                for (int dy = 0; dy <= 20 && far == null; dy++) {
                    BlockPos probe = new BlockPos(START + cell[0] * HALF_ROOM, 69 + shift + dy,
                            START + cell[1] * HALF_ROOM);
                    String what = blockAt(ctx, probe);
                    if (!what.contains("air")) {
                        far = probe;
                        farBefore = what;
                        break outer;
                    }
                }
            }
            if (far == null) {
                throw new AssertionError("no block of the built floor could be found anywhere along the "
                        + "outside edge of the grid, so the wipe check has nothing to watch disappear");
            }
            System.out.println("[91-sim-single] floor block found at " + far.toShortString() + ": "
                    + farBefore);

            long beforeSingle = Scenario.simBuildCount(ctx);
            ctx.runOnClient(mc -> mc.execute(() -> ModUnderTest.staticCall(BUILDER, "buildSingleRoom",
                    new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, ROOM})));
            Scenario.awaitSimBuild(ctx, beforeSingle);
            ctx.waitTicks(100);

            int roomsAfter = mapRoomCount(ctx);
            String nameAfter = mapRoomName(ctx, 0);
            System.out.println("[91-sim-single] the map has " + roomsAfter + " room(s) after loading "
                    + ROOM + ", first named " + nameAfter);
            String farAfter = blockAt(ctx, far);
            System.out.println("[91-sim-single] block at the far corner afterwards: " + farAfter);

            if (roomsAfter != 1) {
                throw new AssertionError("after loading one room the dungeon map still shows " + roomsAfter
                        + " room(s) - he is looking at the previous floor's map over a world that holds one "
                        + "room");
            }
            if (!ROOM.equals(nameAfter)) {
                throw new AssertionError("the map's only room is \"" + nameAfter + "\", not the "
                        + ROOM + " that was loaded");
            }
            if (!farAfter.contains("air")) {
                throw new AssertionError("the previous floor is still standing at " + far.toShortString()
                        + " (" + farAfter + ") after a single-room load, which is supposed to wipe the grid");
            }
            // ---- and while a sim room is standing, does the Spirit Sceptre ACT? --------------------------
            //
            // killer560 (2026-09-30): "the sim spirit scepter doesnt work." It fired the whole time - his log
            // has the chat line six times - and did nothing anyone could see, in a room with nothing in it to
            // hit. So the thing to assert is not "did the handler run" but "did bats leave the hand and did
            // they go off": a counter that only goes up, watched across real client ticks.
            int blastsBefore = (Integer) ModUnderTest.staticCall(SCEPTRE, "blasts");
            boolean fired = (Boolean) ModUnderTest.staticCall(SCEPTRE, "fire",
                    new Class<?>[]{Minecraft.class}, new Object[]{ctx.computeOnClient(mc -> mc)});
            int inFlight = (Integer) ModUnderTest.staticCall(SCEPTRE, "inFlight");
            System.out.println("[91-sim-single] sceptre fired=" + fired + ", bats in flight=" + inFlight);
            if (!fired || inFlight == 0) {
                throw new AssertionError("the Spirit Sceptre reported fired=" + fired + " with " + inFlight
                        + " bats in the air - nothing left the hand");
            }
            ctx.waitTicks(80);
            int blastsAfter = (Integer) ModUnderTest.staticCall(SCEPTRE, "blasts");
            int stillFlying = (Integer) ModUnderTest.staticCall(SCEPTRE, "inFlight");
            System.out.println("[91-sim-single] sceptre blasts " + blastsBefore + " -> " + blastsAfter
                    + ", still in flight: " + stillFlying);
            if (blastsAfter <= blastsBefore) {
                throw new AssertionError("no bat ever exploded - they were launched and then nothing ticked "
                        + "them, which is the same as the sceptre doing nothing");
            }
            if (stillFlying != 0) {
                throw new AssertionError(stillFlying + " bat(s) are still in the air four seconds later - "
                        + "they are supposed to run out of range and go off");
            }

            System.out.println("[91-sim-single] PASS - one room in the world, one room on the map, and the "
                    + "sceptre's bats fly and detonate");
        } finally {
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
    }

    private static int mapRoomCount(ClientGameTestContext ctx) {
        int[] out = new int[1];
        ctx.runOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            out[0] = (Integer) ModUnderTest.call(layout, "roomCount", new Class<?>[]{}, new Object[]{});
        });
        return out[0];
    }

    private static String mapRoomName(ClientGameTestContext ctx, int room) {
        String[] out = new String[1];
        ctx.runOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            int rooms = (Integer) ModUnderTest.call(layout, "roomCount", new Class<?>[]{}, new Object[]{});
            if (room >= rooms) {
                out[0] = "(none)";
                return;
            }
            Object n = ModUnderTest.call(layout, "name", new Class<?>[]{int.class}, new Object[]{room});
            out[0] = n == null ? "null" : n.toString();
        });
        return out[0];
    }

    /** Read on the SERVER: the client's copy of a chunk a hundred blocks away is void_air. */
    private static String blockAt(ClientGameTestContext ctx, BlockPos at) {
        AtomicReference<String> got = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            if (server == null) {
                got.set("no server");
                return;
            }
            server.execute(() -> got.set(net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .getKey(server.overworld().getBlockState(at).getBlock()).getPath()));
        });
        ctx.waitFor(mc -> got.get() != null);
        return got.get();
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
