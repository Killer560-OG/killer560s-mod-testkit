package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Does the dungeon map work inside the sim?
 *
 * <p>killer560 (2026-09-29), on the interactive map: "You should be able to test it in sim rooms now."
 *
 * <p>Before this it could not be. {@code LiveMapFeature} fills its grid by reading the vanilla dungeon map
 * ITEM and the clay markers Hypixel puts in each room, and a singleplayer world has neither - so the scan ran
 * every tick, found nothing, and left the grid empty. Every sim log says so: "Room groups rebuilt: rooms=0".
 * The map HUD, the interactive map, the teleport pathfinders and anything else reading the layout were all
 * blank in there, and nothing said why.
 *
 * <p>The sim now publishes what it placed instead of leaving the map to hunt for markers. This checks that
 * the published floor comes back out of {@code DungeonLayout} the way a real scan's would: the same number of
 * rooms, named, with doors between them, and with the room the player is standing in identified - because a
 * map that draws rooms but cannot say which one you are in is a map the interactive map cannot act on.
 */
public class SimMapTests implements FabricClientGameTest {

    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String BUILD_QUEUE = "com.killer560.hub.roomsim.SimBuildQueue";
    private static final String LIVE_MAP = "com.killer560.hub.livemap.LiveMapFeature";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";

    private static final String SOURCE_ROOMS =
            "C:/Users/Hunter/AppData/Roaming/PrismLauncher/instances/26.1.2 (Mod Only Test)"
                    + "/minecraft/config/killer560smod-rooms";

    private static final int GRID = 11;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip("79-sim-map")) {
            return;
        }
        ModUnderTest.require("killer560smod");
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff(
                "com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));

        if (copyRooms() < 20 || copyRoomData() == 0) {
            System.out.println("[79-sim-map] SKIPPED - needs his real rooms and room database");
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
        ctx.runOnClient(mc -> mc.execute(() -> {
            Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
            ModUnderTest.staticCall(FLOOR_GEN, "generate",
                    new Class<?>[]{Minecraft.class, floor.getClass(), int.class, int.class},
                    new Object[]{mc, floor, 3, 4});
        }));
        ctx.waitFor(mc -> mc.level != null);
        ctx.waitFor(mc -> !(Boolean) ModUnderTest.staticCall(BUILD_QUEUE, "isBusy"));
        // Long enough for the build's completion callback to publish and for a tick to snapshot it.
        ctx.waitTicks(80);

        // What the map says, read the way every consumer reads it.
        Object[] out = new Object[5];
        ctx.runOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            int rooms = (Integer) ModUnderTest.call(layout, "roomCount", new Class<?>[]{}, new Object[]{});
            List<String> names = new ArrayList<>();
            for (int i = 0; i < rooms; i++) {
                Object n = ModUnderTest.call(layout, "name",
                        new Class<?>[]{int.class}, new Object[]{i});
                names.add(n == null ? "null" : n.toString());
            }
            int doors = 0;
            int roomCells = 0;
            for (int c = 0; c < GRID * GRID; c++) {
                if ((Boolean) ModUnderTest.call(layout, "isDoor",
                        new Class<?>[]{int.class}, new Object[]{c})) {
                    doors++;
                }
                if ((Integer) ModUnderTest.call(layout, "roomOfCell",
                        new Class<?>[]{int.class}, new Object[]{c}) >= 0) {
                    roomCells++;
                }
            }
            out[0] = rooms;
            out[1] = names;
            out[2] = doors;
            out[3] = roomCells;
            out[4] = ModUnderTest.staticCall(LIVE_MAP, "currentRoomIndex");
        });

        int rooms = (Integer) out[0];
        @SuppressWarnings("unchecked")
        List<String> names = (List<String>) out[1];
        int doors = (Integer) out[2];
        int roomCells = (Integer) out[3];
        int currentCell = (Integer) out[4];
        System.out.println("[79-sim-map] the map has " + rooms + " room(s) over " + roomCells
                + " cell(s), " + doors + " door(s); the player is in cell " + currentCell);

        if (rooms == 0) {
            throw new AssertionError("the dungeon map is empty inside the sim - LiveMapFeature never got the "
                    + "floor, so the interactive map and every pathfinder that reads the layout have nothing "
                    + "to act on");
        }
        if (doors == 0) {
            throw new AssertionError("the map has rooms but no doors, so nothing can path between them");
        }
        int unnamed = 0;
        for (String n : names) {
            if (n == null || n.isEmpty() || "Unknown".equals(n) || "null".equals(n)) {
                unnamed++;
            }
        }
        System.out.println("[79-sim-map] " + (rooms - unnamed) + " of " + rooms + " room(s) identified: "
                + String.join(", ", names.subList(0, Math.min(8, names.size()))));
        if (unnamed == rooms) {
            throw new AssertionError("every room on the map is \"Unknown\" - the sim published cells but no "
                    + "identities, so nothing that keys on a room name (routes, waypoints, the interactive "
                    + "map's start node) can work");
        }
        if (currentCell < 0) {
            throw new AssertionError("the map cannot say which room the player is standing in, so a map press "
                    + "on \"the room I am in\" has nothing to resolve");
        }

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
        System.out.println("[79-sim-map] PASS - the sim's floor reaches the dungeon map");
    }

    private static int copyRooms() {
        return copyDir(SOURCE_ROOMS, "killer560smod-rooms", ".json");
    }

    private static int copyRoomData() {
        return copyDir(Path.of(SOURCE_ROOMS).resolveSibling("killer560smod-roomdata").toString(),
                "killer560smod-roomdata", "");
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
