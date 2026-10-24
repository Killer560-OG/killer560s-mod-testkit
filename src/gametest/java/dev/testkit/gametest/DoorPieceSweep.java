package dev.testkit.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The box over every standard doorway of every placed sim room, read from the server's level (mod sim-deuces-door-1016).
 *
 * <p>Over a Catacombs doorway's 3x4 opening and its trim row, a 5 wide, 3 high box (capture y 74..76) in the wall plane
 * and the layer one block in holds one of two fillings in 207 of the shipped captures' doorways: on EAST and SOUTH walls
 * cyan terracotta then air (a recess), on WEST and NORTH walls stone then stone bricks with four upside-down stairs (a
 * fill). Hypixel's door connector, laid the same way round in every dungeon - so the side a doorway faces IN THE WORLD
 * decides which it has, and a turned room must not carry its capture's half round with it.
 *
 * <p>For each placed room ({@code SimRoomIndex.placed}) and each measured doorway ({@code RoomDoors.of}) whose capture box
 * is one of the two, the world box at the pasted position must be the filling for the side it faces once turned: a fill
 * with its stairs facing that side on the west and north, a recess on the east and south. Anything else - the room's own
 * doorway design - is counted as not standard and left alone.
 */
final class DoorPieceSweep {

    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String ROOM_DOORS = "com.killer560.hub.roomsim.RoomDoors";
    private static final String ROOM_INDEX = "com.killer560.hub.roomsim.SimRoomIndex";
    private static final String PLACER = "com.killer560.hub.roomsim.RoomPlacer";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String[] FACING = {"north", "east", "south", "west"};
    private static final String[] SIDE = {"N", "E", "S", "W"};
    /** Deuces' capture name (sic); its W0, the chest doorway, is exempt. */
    private static final String DEUCES = "Dueces";

    /**
     * @param checked  standard doorways compared
     * @param turned   of those, in a room pasted at 90, 180 or 270
     * @param crossed  of those, where the world side's filling differs from the captured one (what a correct paste swaps)
     * @param wrong    doorways whose world box is not the filling for the side it faces
     * @param skipped  measured doorways whose capture box is not one of the two (the room's own)
     */
    record Result(int rooms, int checked, int turned, int crossed, int wrong, int skipped, List<String> samples,
                  Map<String, Integer> found, int exempt) {
        String summary() {
            return rooms + " room(s), " + checked + " standard doorway(s) checked (" + turned + " in a turned room, "
                    + crossed + " facing a side whose filling differs from the capture's), " + skipped
                    + " not standard, " + exempt + " exempt (Deuces' chest doorway), " + wrong + " wrong"
                    + (samples.isEmpty() ? "" : " e.g. " + samples) + "; world fillings " + found;
        }
    }

    /** One placed room's doorway, in the capture's frame. */
    private record Box(String[] k0, String[] k1) {
    }

    private DoorPieceSweep() {
    }

    /** Server thread. {@code dy} is {@code SimAltitude.offset()}: capture y + dy is world y. */
    static Result run(ServerLevel level, int dy) throws Exception {
        @SuppressWarnings("unchecked")
        List<Object> placed = (List<Object>) ModUnderTest.staticCall(ROOM_INDEX, "placed", new Class<?>[]{}, new Object[]{});
        int rooms = 0;
        int checked = 0;
        int turned = 0;
        int crossed = 0;
        int wrong = 0;
        int skipped = 0;
        int exempt = 0;
        List<String> samples = new ArrayList<>();
        Map<String, Integer> found = new TreeMap<>();
        for (Object p : placed) {
            String name = (String) ModUnderTest.call(p, "name", new Class<?>[]{}, new Object[]{});
            int gx = (Integer) ModUnderTest.call(p, "gridX", new Class<?>[]{}, new Object[]{});
            int gz = (Integer) ModUnderTest.call(p, "gridZ", new Class<?>[]{}, new Object[]{});
            int rot = (Integer) ModUnderTest.call(p, "pasteRotation", new Class<?>[]{}, new Object[]{});
            Object room = ModUnderTest.staticCall(ROOM_LIBRARY, "get", new Class<?>[]{String.class}, new Object[]{name});
            Object mask = ModUnderTest.staticCall(ROOM_DOORS, "of", new Class<?>[]{String.class}, new Object[]{name});
            if (room == null || mask == null) {
                continue;
            }
            rooms++;
            int sizeX = room.getClass().getField("sizeX").getInt(room);
            int sizeZ = room.getClass().getField("sizeZ").getInt(room);
            int margin = room.getClass().getField("margin").getInt(room);
            BlockPos origin = (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter",
                    new Class<?>[]{int.class, int.class}, new Object[]{gx, gz});
            int x0 = origin.getX() - 15 - margin;
            int z0 = origin.getZ() - 15 - margin;
            @SuppressWarnings("unchecked")
            Set<Integer> edges = (Set<Integer>) ModUnderTest.call(mask, "edges", new Class<?>[]{}, new Object[]{});
            for (int packed : edges) {
                int side = packed / 8;
                int index = packed % 8;
                if (DEUCES.equals(name) && side == 3 && index == 0) {
                    // Deuces' chest doorway is its own design, the same at every turn (DeucesChestDoorway, 2026-10-18):
                    // not a door piece, checked by 1435 instead.
                    exempt++;
                    continue;
                }
                Box cap = new Box(new String[15], new String[15]);
                Box world = new Box(new String[15], new String[15]);
                int n = 0;
                boolean readable = true;
                for (int u = -2; u <= 2; u++) {
                    for (int y = 74; y <= 76; y++) {
                        for (int k = 0; k <= 1; k++) {
                            int[] c = at(sizeX, sizeZ, margin, side, index, k, u, y);
                            String s = captureState(room, c);
                            if (s == null) {
                                readable = false;
                            }
                            int wx = x0 + (Integer) ModUnderTest.staticCall(PLACER, "rotatedX",
                                    new Class<?>[]{int.class, int.class, int.class, int.class, int.class},
                                    new Object[]{c[0], c[2], sizeX, sizeZ, rot});
                            int wz = z0 + (Integer) ModUnderTest.staticCall(PLACER, "rotatedZ",
                                    new Class<?>[]{int.class, int.class, int.class, int.class, int.class},
                                    new Object[]{c[0], c[2], sizeX, sizeZ, rot});
                            BlockState st = level.getBlockState(new BlockPos(wx, y + dy, wz));
                            String ws = BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString() + st.toString()
                                    .replaceFirst("^[^\\[]*", "");
                            (k == 0 ? cap.k0 : cap.k1)[n] = s;
                            (k == 0 ? world.k0 : world.k1)[n] = ws;
                        }
                        n++;
                    }
                }
                String capClass = readable ? classify(cap, FACING[side]) : "unread";
                if (!capClass.equals("fill") && !capClass.equals("recess")) {
                    skipped++;
                    continue;
                }
                int worldSide = Math.floorMod(side + rot / 90, 4);
                String want = worldSide == 0 || worldSide == 3 ? "fill" : "recess";
                String got = classify(world, FACING[worldSide]);
                checked++;
                if (rot != 0) {
                    turned++;
                }
                if (!want.equals(capClass)) {
                    crossed++;
                }
                found.merge(SIDE[worldSide] + ":" + got, 1, Integer::sum);
                if (!want.equals(got)) {
                    wrong++;
                    if (samples.size() < 8) {
                        samples.add(name + " " + SIDE[side] + index + " at " + rot + " faces " + SIDE[worldSide]
                                + ": " + got + ", Hypixel " + want);
                    }
                }
            }
        }
        return new Result(rooms, checked, turned, crossed, wrong, skipped, samples, found, exempt);
    }

    /** The box's filling: "fill" (stairs facing {@code facing}), "recess", or a description of what is there. */
    static String classify(Box b, String facing) {
        boolean recess = true;
        boolean stone = true;
        for (int i = 0; i < 15; i++) {
            recess &= id(b.k0[i]).equals("minecraft:cyan_terracotta") && isAir(b.k1[i]);
            stone &= id(b.k0[i]).equals("minecraft:stone");
        }
        if (recess) {
            return "recess";
        }
        Map<String, Integer> k1 = new TreeMap<>();
        boolean stairsRight = true;
        for (int i = 0; i < 15; i++) {
            k1.merge(id(b.k1[i]).replace("minecraft:", ""), 1, Integer::sum);
            if (id(b.k1[i]).equals("minecraft:stone_brick_stairs")
                    && !(b.k1[i].contains("facing=" + facing) && b.k1[i].contains("half=top"))) {
                stairsRight = false;
            }
        }
        if (stone && stairsRight && Integer.valueOf(9).equals(k1.get("stone_bricks")) && Integer.valueOf(1).equals(k1.get("stone"))
                && Integer.valueOf(1).equals(k1.get("mossy_stone_bricks")) && Integer.valueOf(4).equals(k1.get("stone_brick_stairs"))) {
            return "fill";
        }
        return (stone ? "stone+" : "?+") + k1 + (stairsRight ? "" : " stairs turned");
    }

    private static String id(String state) {
        if (state == null) {
            return "?";
        }
        int b = state.indexOf('[');
        return b < 0 ? state : state.substring(0, b);
    }

    private static boolean isAir(String state) {
        String id = id(state);
        return id.equals("minecraft:air") || id.equals("minecraft:cave_air") || id.equals("minecraft:void_air");
    }

    private static String captureState(Object room, int[] c) throws Exception {
        @SuppressWarnings("unchecked")
        List<String> palette = (List<String>) room.getClass().getField("palette").get(room);
        int sizeX = room.getClass().getField("sizeX").getInt(room);
        int sizeZ = room.getClass().getField("sizeZ").getInt(room);
        int minY = room.getClass().getField("minY").getInt(room);
        int maxY = room.getClass().getField("maxY").getInt(room);
        if (c[0] < 0 || c[2] < 0 || c[0] >= sizeX || c[2] >= sizeZ || c[1] < minY || c[1] > maxY) {
            return null;
        }
        short s = (Short) room.getClass().getMethod("at", int.class, int.class, int.class).invoke(room, c[0], c[1], c[2]);
        if (s < 0) {
            return null;
        }
        String e = palette.get(s);
        return e.contains(":") ? e : "minecraft:" + e;
    }

    /**
     * Capture position of the box over doorway {@code (side, index)}: {@code k} blocks in from the wall plane, {@code u}
     * along the wall from the tile's center. The same arithmetic {@code RoomDoors} measures with (wall plane {@code margin}
     * in, tile centers {@code margin + 15} and every 32 after).
     */
    static int[] at(int sizeX, int sizeZ, int margin, int side, int index, int k, int u, int y) {
        int c = margin + 15 + index * 32;
        int d = margin + k;
        return switch (side) {
            case 0 -> new int[]{c + u, y, d};
            case 2 -> new int[]{c + u, y, sizeZ - 1 - d};
            case 3 -> new int[]{d, y, c + u};
            default -> new int[]{sizeX - 1 - d, y, c + u};
        };
    }
}
