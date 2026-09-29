package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Is the generated floor actually a dungeon?
 *
 * <p>killer560 (2026-09-28): "the map is not full nor is it possible to get to every room from the starting
 * room [...] it looks nothing like a normal dungeon." Scenario 72 proved the floor builds quickly and without
 * stalling, and a floor can do both of those while being useless - a row of sealed boxes builds fast.
 *
 * <p>So this checks the SHAPE rather than the speed, on the two properties that make a floor worth practising
 * on: every room is reachable from the green room by walking, and the layout is compact rather than a corridor.
 *
 * <p>Reachability is tested on the MAP DATA - rooms joined by door cells - rather than by walking the world,
 * because that is the thing the generator controls and the thing his map draws. The world doorways are checked
 * separately by sampling: a door cell whose centre is solid at head height never got cut.
 */
public class SimFloorShapeTests implements FabricClientGameTest {

    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String BUILD_QUEUE = "com.killer560.hub.roomsim.SimBuildQueue";
    private static final String MAP_CODE = "com.killer560.hub.roomsim.MapCode";

    private static final String SOURCE_ROOMS =
            "C:/Users/Hunter/AppData/Roaming/PrismLauncher/instances/Map Logger/minecraft/config/killer560smod-rooms";

    private static final int GRID = 11;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip("73-sim-floor-shape")) {
            return;
        }
        ModUnderTest.require("killer560smod");
        ctx.waitTicks(40);

        if (copyRealRooms() < 20) {
            System.out.println("[73-sim-floor-shape] SKIPPED - needs his real rooms to generate a real floor");
            return;
        }
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload",
                new Class<?>[]{}, new Object[]{}));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady",
                new Class<?>[]{}, new Object[]{}));

        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter",
                new Class<?>[]{String.class}, new Object[]{"gametest"}));
        ctx.runOnClient(mc -> mc.execute(() -> {
            Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
            ModUnderTest.staticCall(FLOOR_GEN, "generate",
                    new Class<?>[]{Minecraft.class, floor.getClass(), int.class, int.class},
                    new Object[]{mc, floor, 3, 5});
        }));
        ctx.waitFor(mc -> mc.level != null);
        ctx.waitFor(mc -> !(Boolean) ModUnderTest.staticCall(BUILD_QUEUE, "isBusy",
                new Class<?>[]{}, new Object[]{}));
        ctx.waitTicks(40);

        // Read the GENERATED MAP, not the live map.
        //
        // My first version asked DungeonLayout, which is built by scanning the world and only runs while the
        // mod believes it is in a dungeon - so in the harness it reported zero rooms and the scenario failed
        // on the test's own environment rather than on the generator. The map code is what the generator
        // actually produces, so that is the thing to assert on.
        int[] roomOf = new int[GRID * GRID];
        int[] doorOf = new int[GRID * GRID];
        ctx.runOnClient(mc -> {
            String code = (String) ModUnderTest.staticCall(SIM_STATE, "mapCode",
                    new Class<?>[]{}, new Object[]{});
            if (code == null || code.isBlank()) {
                throw new AssertionError("the sim has no map code after generating a floor");
            }
            Object decoded = ModUnderTest.staticCall(MAP_CODE, "decode",
                    new Class<?>[]{String.class}, new Object[]{code});
            if (decoded == null) {
                throw new AssertionError("the generated map code does not decode");
            }
            int[] cellRoom = (int[]) ModUnderTest.call(decoded, "cellRoom", new Class<?>[]{}, new Object[]{});
            int[] cellDoor = (int[]) ModUnderTest.call(decoded, "cellDoor", new Class<?>[]{}, new Object[]{});
            System.arraycopy(cellRoom, 0, roomOf, 0, Math.min(cellRoom.length, roomOf.length));
            System.arraycopy(cellDoor, 0, doorOf, 0, Math.min(cellDoor.length, doorOf.length));
        });

        Set<Integer> rooms = new HashSet<>();
        List<Integer> roomCells = new ArrayList<>();
        for (int cell = 0; cell < GRID * GRID; cell++) {
            if (roomOf[cell] >= 0) {
                rooms.add(roomOf[cell]);
                roomCells.add(cell);
            }
        }
        System.out.println("[73-sim-floor-shape] " + rooms.size() + " room(s) across " + roomCells.size()
                + " cell(s)");
        if (rooms.size() < 8) {
            throw new AssertionError("only " + rooms.size() + " rooms on a Floor 7 - that is not a floor");
        }

        // How many doors were placed at all. Zero means the map has no connections, which is exactly what he
        // saw: rooms drawn as islands.
        int doors = 0;
        for (int cell = 0; cell < GRID * GRID; cell++) {
            if (doorOf[cell] != 0) {
                doors++;
            }
        }
        System.out.println("[73-sim-floor-shape] " + doors + " door(s)");
        if (doors == 0) {
            throw new AssertionError("the floor has no doors at all - every room is an island and nothing "
                    + "links on the map");
        }

        // Reachability from the entrance, walking cell to cell, through doors between rooms and freely inside
        // one room.
        int start = roomCells.get(0);
        Set<Integer> seenRooms = new HashSet<>();
        Set<Integer> visited = new HashSet<>();
        Deque<Integer> queue = new ArrayDeque<>();
        queue.add(start);
        visited.add(start);
        while (!queue.isEmpty()) {
            int cell = queue.poll();
            seenRooms.add(roomOf[cell]);
            int gx = cell % GRID;
            int gz = cell / GRID;
            for (int[] step : new int[][]{{2, 0}, {-2, 0}, {0, 2}, {0, -2}}) {
                int nx = gx + step[0];
                int nz = gz + step[1];
                if (nx < 0 || nz < 0 || nx >= GRID || nz >= GRID) {
                    continue;
                }
                int next = nz * GRID + nx;
                if (roomOf[next] < 0 || visited.contains(next)) {
                    continue;
                }
                boolean sameRoom = roomOf[next] == roomOf[cell];
                int between = (gz + step[1] / 2) * GRID + (gx + step[0] / 2);
                if (!sameRoom && doorOf[between] == 0) {
                    continue;
                }
                visited.add(next);
                queue.add(next);
            }
        }
        System.out.println("[73-sim-floor-shape] reachable: " + seenRooms.size() + "/" + rooms.size()
                + " room(s)");
        if (seenRooms.size() < rooms.size()) {
            throw new AssertionError("only " + seenRooms.size() + " of " + rooms.size()
                    + " rooms are reachable from the start - the rest cannot be walked to, which is exactly "
                    + "what killer560 reported");
        }

        // Compactness. A depth-first growth makes a snake; a real floor fills a block. Measured as the share
        // of the layout's bounding box that actually holds rooms.
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int cell : roomCells) {
            minX = Math.min(minX, cell % GRID);
            maxX = Math.max(maxX, cell % GRID);
            minZ = Math.min(minZ, cell / GRID);
            maxZ = Math.max(maxZ, cell / GRID);
        }
        int boxCells = ((maxX - minX) / 2 + 1) * ((maxZ - minZ) / 2 + 1);
        double fill = boxCells == 0 ? 0 : (double) roomCells.size() / boxCells;
        System.out.println(String.format("[73-sim-floor-shape] fills %.0f%% of its %dx%d box",
                fill * 100, (maxX - minX) / 2 + 1, (maxZ - minZ) / 2 + 1));
        if (fill < 0.5) {
            throw new AssertionError(String.format("the floor fills only %.0f%% of its bounding box - that is "
                    + "a corridor, not a dungeon", fill * 100));
        }

        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "leave", new Class<?>[]{}, new Object[]{}));
        ctx.runOnClient(mc -> mc.execute(() -> {
            if (mc.level != null) {
                mc.level.disconnect(net.minecraft.network.chat.Component.literal("scenario over"));
                mc.disconnectWithSavingScreen();
            }
        }));
        ctx.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null);
        ctx.waitTicks(60);
        ctx.runOnClient(mc -> mc.execute(() ->
                mc.setScreen(new net.minecraft.client.gui.screens.TitleScreen())));
        ctx.waitFor(mc -> mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen);
        ctx.waitTicks(20);
        System.out.println("[73-sim-floor-shape] PASS - connected, fully reachable, compact");
    }

    private static int copyRealRooms() {
        try {
            Path source = Path.of(SOURCE_ROOMS);
            if (!Files.isDirectory(source)) {
                return 0;
            }
            Path target = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
                    .resolve("killer560smod-rooms");
            Files.createDirectories(target);
            List<Path> files = new ArrayList<>();
            try (var s = Files.list(source)) {
                s.filter(p -> p.toString().endsWith(".json")).forEach(files::add);
            }
            for (Path f : files) {
                Files.copy(f, target.resolve(f.getFileName()),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return files.size();
        } catch (Exception e) {
            return 0;
        }
    }
}
