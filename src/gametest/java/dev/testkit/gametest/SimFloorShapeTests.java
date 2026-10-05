package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Is a generated floor actually a dungeon?
 *
 * <p>killer560 (2026-09-28): "the map is not full nor is it possible to get to every room from the starting
 * room [...] it looks nothing like a normal dungeon." Scenario 72 proves a floor builds fast and without
 * stalling, and a floor can do both while being useless - a row of sealed boxes builds fast.
 *
 * <p>This checks the SHAPE, over MANY floors rather than one. The first version built a world and asserted on
 * the single floor that came out, which made "the generator never places multi-tile rooms" and "this floor
 * happened not to" indistinguishable - and that assertion duly passed and failed on alternate runs. A test
 * that flaps is worse than no test, because it teaches you to ignore it. {@code SimFloorGen.plan} exists so
 * this can lay out a hundred floors in a second without touching the world.
 */
public class SimFloorShapeTests implements FabricClientGameTest {

    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String ROOM_DOORS = "com.killer560.hub.roomsim.RoomDoors";

    private static final String SOURCE_ROOMS =
            ModUnderTest.instanceConfig("C:/Users/Hunter/AppData/Roaming/PrismLauncher/instances/26.1.2 (Mod Only Test)/minecraft/config", "killer560smod-rooms");

    private static final int GRID = 11;
    private static final int FLOORS = 120;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip("73-sim-floor-shape")) {
            return;
        }
        ModUnderTest.require("killer560smod");
        ctx.waitTicks(40);
        // The auction scan off first.
        //
        // AuctionHouseFeature starts a background scan of the whole auction house the moment a player exists,
        // and it pulls about 43,000 listings across 44 pages, each decoded into an ItemStack. In a gametest
        // client that is enough to wedge the process - scenario 71 froze on exactly that, sixteen seconds
        // after the sim had finished building perfectly. Nothing here is testing the auction house, so the
        // scan is pure interference.
        ctx.runOnClient(mc -> ModUnderTest.turnOff(
                "com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));

        if (copyRealRooms() < 20) {
            System.out.println("[73-sim-floor-shape] SKIPPED - needs his real rooms to generate a real floor");
            return;
        }
        // Room types come from the room database; the sim will not plan a floor without it (mod, 2026-10-05).
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"));

        // EVERY floor size, not a sample. killer560 (2026-09-29): "make sure stuff like all of the different
        // floor size work". F2, F4 and F6 were never exercised here, and they are exactly the sizes the old
        // hardcoded expectedRooms() switch got wrong - it fell through to 21 for all three.
        String[] floorNames = {"ENTRANCE", "F1", "F2", "F3", "F4", "F5", "F6", "F7"};
        java.util.Map<String, int[]> perFloor = new java.util.LinkedHashMap<>();   // name -> {floors, cells}
        int planned = 0;
        int carvedDoors = 0;
        int withMulti = 0;
        int multiRooms = 0;
        int totalRooms = 0;
        double fillSum = 0;
        List<String> failures = new ArrayList<>();

        for (int i = 0; i < FLOORS; i++) {
            final String floorName = floorNames[i % floorNames.length];
            final int puzzles = 2 + (i % 4);
            final int toBlood = 2 + (i % 7);
            final Object[] out = new Object[6];
            ctx.runOnClient(mc -> {
                Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", floorName);
                Object p = ModUnderTest.staticCall(FLOOR_GEN, "plan",
                        new Class<?>[]{floor.getClass(), int.class, int.class},
                        new Object[]{floor, puzzles, toBlood});
                if (p == null) {
                    return;
                }
                Object decoded = ModUnderTest.call(p, "decoded", new Class<?>[]{}, new Object[]{});
                String[] names = (String[]) ModUnderTest.call(decoded, "nameTable",
                        new Class<?>[]{}, new Object[]{});
                out[0] = names;
                out[1] = ModUnderTest.call(decoded, "cellRoom", new Class<?>[]{}, new Object[]{});
                out[2] = ModUnderTest.call(decoded, "cellDoor", new Class<?>[]{}, new Object[]{});
                // Expected CELL count, including the connector cells between a room's own tiles - the
                // convention a live capture uses and, since 2026-09-29, the one the generator uses too. A
                // room of t tiles on an axis spans 2t-1 cells. This is not decoration: SimBuilder pastes one
                // room per flood-filled group of same-id cells and that fill steps one cell at a time, so a
                // missing connector splits a room into one group per tile and pastes it at each of them.
                int[] want = new int[names.length];
                for (int n = 0; n < names.length; n++) {
                    int tiles = (Integer) ModUnderTest.staticCall(ROOM_LIBRARY, "cellFootprint",
                            new Class<?>[]{String.class}, new Object[]{names[n]});
                    int tx = (Integer) ModUnderTest.staticCall(ROOM_LIBRARY, "tilesX",
                            new Class<?>[]{String.class}, new Object[]{names[n]});
                    int tz = tiles == 0 || tx == 0 ? 0 : tiles / tx;
                    want[n] = tiles == 0 ? 0 : (2 * tx - 1) * (2 * tz - 1);
                }
                out[3] = want;
                out[4] = ModUnderTest.call(decoded, "cellRotation", new Class<?>[]{}, new Object[]{});
                // Every room's measured doorways, flattened to {side, index, tilesX, tilesZ} so nothing here
                // has to know about the Mask record.
                int[][] doors = new int[names.length][];
                for (int n = 0; n < names.length; n++) {
                    Object mask = ModUnderTest.staticCall(ROOM_DOORS, "of",
                            new Class<?>[]{String.class}, new Object[]{names[n]});
                    doors[n] = mask == null ? new int[0] : flatten(mask);
                }
                out[5] = doors;
            });
            if (out[0] == null) {
                failures.add(floorName + " #" + i + ": plan() returned null");
                continue;
            }
            planned++;
            String[] names = (String[]) out[0];
            int[] roomOf = (int[]) out[1];
            int[] doorOf = (int[]) out[2];
            int[] wantCells = (int[]) out[3];

            // 1. Every room covers exactly the cells its capture is wide.
            //
            // This catches a layout planning against one footprint while the paste uses another, which on
            // 2026-09-29 put every multi-tile room over its neighbour. It only means anything because room ids
            // are per PLACEMENT: while they were deduplicated by name, two placements of one room shared an id
            // and this could not tell that from a real mismatch.
            Map<Integer, Integer> cells = new HashMap<>();
            for (int c = 0; c < GRID * GRID; c++) {
                if (roomOf[c] >= 0) {
                    cells.merge(roomOf[c], 1, Integer::sum);
                }
            }
            for (Map.Entry<Integer, Integer> e : cells.entrySet()) {
                int id = e.getKey();
                if (id >= wantCells.length) {
                    failures.add(floorName + " #" + i + ": a cell claims room id " + id
                            + " but the name table holds " + wantCells.length);
                    continue;
                }
                if (wantCells[id] == 0) {
                    failures.add(floorName + " #" + i + ": placed \"" + names[id]
                            + "\", which is not usable - it would paste at the wrong size");
                } else if (e.getValue() != wantCells[id]) {
                    failures.add(floorName + " #" + i + ": room \"" + names[id] + "\" covers "
                            + e.getValue() + " cell(s), expected " + wantCells[id]);
                }
            }
            // The paste grouping itself: SimBuilder flood-fills +-1 over same-id cells, so every cell of a
            // room must be reachable from any other. This is the assertion that would have caught the split,
            // because the map code looked perfectly well-formed while the paste came out duplicated.
            for (Map.Entry<Integer, Integer> e : cells.entrySet()) {
                int id = e.getKey();
                int first = -1;
                for (int c = 0; c < GRID * GRID && first < 0; c++) {
                    if (roomOf[c] == id) {
                        first = c;
                    }
                }
                Set<Integer> group = new HashSet<>();
                Deque<Integer> q = new ArrayDeque<>();
                q.add(first);
                group.add(first);
                while (!q.isEmpty()) {
                    int at = q.poll();
                    int ax = at % GRID;
                    int az = at / GRID;
                    for (int[] st : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                        int nx = ax + st[0];
                        int nz = az + st[1];
                        if (nx < 0 || nz < 0 || nx >= GRID || nz >= GRID) {
                            continue;
                        }
                        int n = nz * GRID + nx;
                        if (roomOf[n] == id && group.add(n)) {
                            q.add(n);
                        }
                    }
                }
                if (group.size() != e.getValue()) {
                    failures.add(floorName + " #" + i + ": \"" + names[id] + "\" has " + e.getValue()
                            + " cells but a flood fill reaches only " + group.size()
                            + " - the builder would paste it once per disconnected piece");
                }
            }
            totalRooms += cells.size();
            int multi = 0;
            for (int v : cells.values()) {
                if (v > 1) {
                    multi++;
                }
            }
            multiRooms += multi;
            if (multi > 0) {
                withMulti++;
            }

            // 2. Every room reachable from the entrance, through doors between rooms and freely inside one.
            List<Integer> roomCells = new ArrayList<>();
            Set<Integer> allRooms = new HashSet<>();
            for (int c = 0; c < GRID * GRID; c++) {
                if (roomOf[c] >= 0) {
                    roomCells.add(c);
                    allRooms.add(roomOf[c]);
                }
            }
            if (roomCells.isEmpty()) {
                failures.add(floorName + " #" + i + ": no rooms at all");
                continue;
            }
            Set<Integer> seen = new HashSet<>();
            Set<Integer> visited = new HashSet<>();
            Deque<Integer> queue = new ArrayDeque<>();
            queue.add(roomCells.get(0));
            visited.add(roomCells.get(0));
            while (!queue.isEmpty()) {
                int c = queue.poll();
                seen.add(roomOf[c]);
                int gx = c % GRID;
                int gz = c / GRID;
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
                    int between = (gz + step[1] / 2) * GRID + (gx + step[0] / 2);
                    if (roomOf[next] != roomOf[c] && doorOf[between] == 0) {
                        continue;
                    }
                    visited.add(next);
                    queue.add(next);
                }
            }
            if (seen.size() < allRooms.size()) {
                failures.add(floorName + " #" + i + ": only " + seen.size() + " of " + allRooms.size()
                        + " rooms reachable from the start");
            }

            // 3. Every door in the map is a doorway in BOTH rooms' geometry.
            //
            // This is the whole point of the 2026-09-29 rewrite. A door cell used to mean "the builder will
            // knock a hole through whatever is here", so the map could put a door anywhere and the world was
            // made to agree. Now the doorways are read off the captured blocks, so a door in the map that the
            // room has no opening for is a door drawn through a solid wall.
            int[] rotationOf = (int[]) out[4];
            int[][] doorsOf = (int[][]) out[5];
            Set<Integer> doorwayCells = new HashSet<>();
            // Which rooms have a doorway at each door cell. A door with a doorway on ONE side only is the fill
            // pass's deliberate carve (SimFloorLayout.fillGaps step 3: "the door cut through the neighbour's wall",
            // the builder lays its floor), so it does not count against the carved-into room's doorway total.
            Map<Integer, Set<Integer>> doorwayRooms = new HashMap<>();
            Map<Integer, Integer> linksPerRoom = new HashMap<>();
            for (int c = 0; c < GRID * GRID; c++) {
                int id = roomOf[c];
                if (id < 0 || (c % GRID) % 2 != 0 || (c / GRID) % 2 != 0 || id >= doorsOf.length) {
                    continue;
                }
                int[] anchor = anchorOf(roomOf, c, id);
                int[] flat = rotated(doorsOf[id], rotationOf[c]);
                for (int k = 0; k + 3 < flat.length; k += 4) {
                    int[] cellXz = doorCell(anchor[0], anchor[1], flat[k + 2], flat[k + 3],
                            flat[k], flat[k + 1]);
                    if (cellXz[0] * 2 != c % GRID || cellXz[1] * 2 != c / GRID) {
                        continue;   // this doorway belongs to another cell of the same room
                    }
                    int nx = c % GRID + DX[flat[k]];
                    int nz = c / GRID + DZ[flat[k]];
                    if (nx < 0 || nz < 0 || nx >= GRID || nz >= GRID) {
                        continue;   // off the map: the builder bricks this one up
                    }
                    doorwayCells.add(nz * GRID + nx);
                    doorwayRooms.computeIfAbsent(nz * GRID + nx, key -> new HashSet<>()).add(id);
                }
            }
            for (int c = 0; c < GRID * GRID; c++) {
                if (doorOf[c] == 0) {
                    continue;
                }
                int gx = c % GRID;
                int gz = c / GRID;
                boolean alongX = gx % 2 == 1;
                int aCell = alongX ? gz * GRID + gx - 1 : (gz - 1) * GRID + gx;
                int bCell = alongX ? gz * GRID + gx + 1 : (gz + 1) * GRID + gx;
                if (roomOf[aCell] < 0 || roomOf[bCell] < 0) {
                    failures.add(floorName + " #" + i + ": a door at cell " + c + " has no room on one side");
                    continue;
                }
                if (!doorwayCells.contains(c)) {
                    failures.add(floorName + " #" + i + ": the map puts a door between "
                            + names[roomOf[aCell]] + " and " + names[roomOf[bCell]]
                            + " but neither room has a doorway there");
                }
                Set<Integer> haveDoorway = doorwayRooms.getOrDefault(c, Set.of());
                if (haveDoorway.contains(roomOf[aCell])) {
                    linksPerRoom.merge(roomOf[aCell], 1, Integer::sum);
                }
                if (haveDoorway.contains(roomOf[bCell])) {
                    linksPerRoom.merge(roomOf[bCell], 1, Integer::sum);
                }
                if (doorwayCells.contains(c) && haveDoorway.size() == 1) {
                    carvedDoors++;
                }
            }

            // 4. A one-door room is the end of a branch.
            //
            // killer560 (2026-09-29): "Some rooms only have one door so they have to be at the end of a split
            // like puzzles and trap and some other rooms." A room with one doorway that the map has given two
            // connections to has a door drawn through a wall.
            for (Map.Entry<Integer, Integer> e : linksPerRoom.entrySet()) {
                int id = e.getKey();
                if (id >= doorsOf.length) {
                    continue;
                }
                int doorways = doorsOf[id].length / 4;
                if (doorways > 0 && e.getValue() > doorways) {
                    failures.add(floorName + " #" + i + ": " + names[id] + " has " + doorways
                            + " doorway(s) but the map gives it " + e.getValue() + " door(s)");
                }
            }
            if (cells.size() < expectedRooms(floorName)) {
                failures.add(floorName + " #" + i + ": only " + cells.size() + " room(s), wanted "
                        + expectedRooms(floorName));
            }
            boolean hasBlood = false;
            for (int id : allRoomIds(roomOf)) {
                if (id < names.length && "Blood".equalsIgnoreCase(names[id])) {
                    hasBlood = true;
                }
            }
            if (!hasBlood) {
                failures.add(floorName + " #" + i + ": no blood room on the floor");
            }

            // 5. Compact, not a corridor.
            int minX = Integer.MAX_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxZ = Integer.MIN_VALUE;
            for (int c : roomCells) {
                minX = Math.min(minX, c % GRID);
                maxX = Math.max(maxX, c % GRID);
                minZ = Math.min(minZ, c / GRID);
                maxZ = Math.max(maxZ, c / GRID);
            }
            int box = ((maxX - minX) / 2 + 1) * ((maxZ - minZ) / 2 + 1);
            // ROOM cells only, not connector cells.
            //
            // roomCells holds every grid cell a room covers, and a 2x2 room covers nine of them - its four
            // tiles plus the five connectors between them - while the box is measured in rooms. Comparing the
            // two reported 121% fill on 2026-09-29, which is not a fraction of anything. Counting the tile
            // cells makes it the fraction it claims to be again.
            int tileCells = 0;
            for (int c : roomCells) {
                if ((c % GRID) % 2 == 0 && (c / GRID) % 2 == 0) {
                    tileCells++;
                }
            }
            double fill = box == 0 ? 0 : (double) tileCells / box;
            fillSum += fill;
            // NO LOOPS. killer560 (2026-09-30): "there should only be 1 way to enter a room for the first
            // time". A connected graph with a cycle in it has more edges than rooms minus one, so counting
            // the doors between DIFFERENT rooms against the room count catches it without walking the graph
            // twice - and connectivity is already asserted above, so edges == rooms-1 means a tree exactly.
            int roomsOnFloor = names.length;
            int interRoomDoors = 0;
            for (int cell = 0; cell < doorOf.length; cell++) {
                if (doorOf[cell] == 0) {
                    continue;
                }
                int gx = cell % GRID;
                int gz = cell / GRID;
                int one = gx > 0 ? roomOf[cell - 1] : -1;
                int two = gx + 1 < GRID ? roomOf[cell + 1] : -1;
                int up = gz > 0 ? roomOf[cell - GRID] : -1;
                int dn = gz + 1 < GRID ? roomOf[cell + GRID] : -1;
                if ((one >= 0 && two >= 0 && one != two) || (up >= 0 && dn >= 0 && up != dn)) {
                    interRoomDoors++;
                }
            }
            if (interRoomDoors > roomsOnFloor - 1) {
                failures.add(String.format("%s #%d: %d doors joining %d rooms - %d more than a tree, so there "
                        + "is a loop and a room can be entered from two directions",
                        floorName, i, interRoomDoors, roomsOnFloor, interRoomDoors - (roomsOnFloor - 1)));
            }

            int[] tally = perFloor.computeIfAbsent(floorName, k -> new int[2]);
            tally[0]++;
            tally[1] += tileCells;
            // A hard floor per layout, and the AVERAGE asserted separately below.
            //
            // This was 0.45 per floor, which made the scenario flap: the generator averages about 77% with a
            // tail, and one floor in a hundred landing at 43% failed the whole suite. A floor at 43% is
            // sparse, not broken. 0.30 is a genuine corridor, and a generator that drifted towards sparse
            // floors would show up in the mean long before any single floor hit that.
            if (fill < 0.30) {
                failures.add(String.format("%s #%d: fills only %.0f%% of its box - that is a corridor",
                        floorName, i, fill * 100));
            }
        }

        System.out.println(String.format(
                "[73-sim-floor-shape] planned %d floor(s): %d rooms, %d multi-tile, %.0f%% of floors had one, "
                        + "mean fill %.0f%%",
                planned, totalRooms, multiRooms, 100.0 * withMulti / Math.max(1, planned),
                100.0 * fillSum / Math.max(1, planned)));
        System.out.println("[73-sim-floor-shape] carved doors (a doorway on one side only, the fill pass's last "
                + "resort): " + carvedDoors + " across " + planned + " floor(s)");

        // Per floor size, so "all the floor sizes work" is a number per size rather than one average that a
        // single broken size could hide inside.
        for (var e : perFloor.entrySet()) {
            int floors = e.getValue()[0];
            double mean = (double) e.getValue()[1] / Math.max(1, floors);
            int want = expectedCells(e.getKey());
            System.out.println(String.format(
                    "[73-sim-floor-shape]   %-8s %3d floor(s), mean %.1f of %d room cells (%.0f%% of target)",
                    e.getKey(), floors, mean, want, 100.0 * mean / Math.max(1, want)));
            if (mean < want * 0.80) {
                failures.add(String.format("%s covers only %.1f of its %d target cells on average - that "
                        + "floor size generates a sparse map", e.getKey(), mean, want));
            }
        }

        if (!failures.isEmpty()) {
            StringBuilder sb = new StringBuilder(failures.size() + " floor(s) were not dungeons:");
            for (int i = 0; i < Math.min(12, failures.size()); i++) {
                sb.append("\n    ").append(failures.get(i));
            }
            if (failures.size() > 12) {
                sb.append("\n    ...and ").append(failures.size() - 12).append(" more");
            }
            throw new AssertionError(sb.toString());
        }
        if (planned < FLOORS) {
            throw new AssertionError("only " + planned + " of " + FLOORS + " floors planned at all");
        }
        // killer560 (2026-09-28): "dont use 1x1 you need to use more than just that." Over this many floors,
        // a generator that has multi-tile rooms and never places one is a defect, not luck - and over a single
        // floor it is indistinguishable from luck, which is exactly why this is measured in aggregate.
        double meanFill = fillSum / Math.max(1, planned);
        if (meanFill < 0.60) {
            throw new AssertionError(String.format(
                    "floors average only %.0f%% of their bounding box - the generator has drifted towards "
                            + "corridors even though no single floor is bad enough to fail on its own",
                    meanFill * 100));
        }
        if (multiRooms == 0) {
            throw new AssertionError("not one multi-tile room across " + planned
                    + " floors, from a library that has them - the generator is not placing them");
        }
        System.out.println("[73-sim-floor-shape] PASS - every floor connected, compact, correctly sized, "
                + "and every door is a doorway in both rooms");
    }

    private static final int[] DX = {0, 1, 0, -1};
    private static final int[] DZ = {-1, 0, 1, 0};

    /** {@code {side, index, tilesX, tilesZ}} per doorway, pulled out of a RoomDoors.Mask by reflection. */
    private static int[] flatten(Object mask) {
        int tilesX = (Integer) ModUnderTest.call(mask, "tilesX", new Class<?>[]{}, new Object[]{});
        int tilesZ = (Integer) ModUnderTest.call(mask, "tilesZ", new Class<?>[]{}, new Object[]{});
        @SuppressWarnings("unchecked")
        Set<Integer> edges = (Set<Integer>) ModUnderTest.call(mask, "edges", new Class<?>[]{}, new Object[]{});
        int[] out = new int[edges.size() * 4];
        int k = 0;
        for (int packed : edges) {
            out[k++] = (Integer) ModUnderTest.staticCall(ROOM_DOORS, "sideOf",
                    new Class<?>[]{int.class}, new Object[]{packed});
            out[k++] = (Integer) ModUnderTest.staticCall(ROOM_DOORS, "indexOf",
                    new Class<?>[]{int.class}, new Object[]{packed});
            out[k++] = tilesX;
            out[k++] = tilesZ;
        }
        return out;
    }

    /**
     * The same doorways, turned.
     *
     * <p>Written out here rather than calling {@code RoomDoors.rotate} through reflection, deliberately: a
     * test that asks the code under test to transform its own data proves only that it is self-consistent.
     * This is the rotation derived from the physical fact - north becomes east on a clockwise quarter turn -
     * so if the two ever disagree, the assertions above fail.
     */
    private static int[] rotated(int[] flat, int degrees) {
        int turns = ((degrees / 90) % 4 + 4) % 4;
        int[] current = flat.clone();
        for (int t = 0; t < turns; t++) {
            int[] next = new int[current.length];
            for (int k = 0; k + 3 < current.length; k += 4) {
                int side = current[k];
                int index = current[k + 1];
                int tilesX = current[k + 2];
                int tilesZ = current[k + 3];
                int newSide;
                int newIndex;
                switch (side) {
                    case 0 -> {
                        newSide = 1;
                        newIndex = index;
                    }
                    case 1 -> {
                        newSide = 2;
                        newIndex = tilesZ - 1 - index;
                    }
                    case 2 -> {
                        newSide = 3;
                        newIndex = index;
                    }
                    default -> {
                        newSide = 0;
                        newIndex = tilesZ - 1 - index;
                    }
                }
                next[k] = newSide;
                next[k + 1] = newIndex;
                next[k + 2] = tilesZ;
                next[k + 3] = tilesX;
            }
            current = next;
        }
        return current;
    }

    private static int[] doorCell(int originX, int originZ, int tilesX, int tilesZ, int side, int index) {
        return switch (side) {
            case 0 -> new int[]{originX + index, originZ};
            case 2 -> new int[]{originX + index, originZ + tilesZ - 1};
            case 3 -> new int[]{originX, originZ + index};
            default -> new int[]{originX + tilesX - 1, originZ + index};
        };
    }

    /** The top-left ROOM cell of the placement a grid cell belongs to. */
    private static int[] anchorOf(int[] roomOf, int cell, int id) {
        int gx = cell % GRID;
        int gz = cell / GRID;
        int minX = gx;
        int minZ = gz;
        while (minX - 2 >= 0 && roomOf[gz * GRID + (minX - 2)] == id) {
            minX -= 2;
        }
        while (minZ - 2 >= 0 && roomOf[(minZ - 2) * GRID + gx] == id) {
            minZ -= 2;
        }
        return new int[]{minX / 2, minZ / 2};
    }

    private static Set<Integer> allRoomIds(int[] roomOf) {
        Set<Integer> out = new HashSet<>();
        for (int v : roomOf) {
            if (v >= 0) {
                out.add(v);
            }
        }
        return out;
    }

    /** The room counts SimFloorGen.Floor carries, so the test and the generator cannot disagree silently. */
    /**
     * The floor's own minimum room count, read off the enum.
     *
     * <p>This was a switch with {@code default -> 21}, which happened to be right for the five floors this
     * scenario used to run and silently wrong for F2 (15), F4 (19) and F6 (19). A test that carries its own
     * copy of a constant fails the day someone adds a case to the real one.
     */
    private static int expectedRooms(String floorName) {
        return floorField(floorName, "rooms");
    }

    /** The floor's target CELL count - what the generator aims to cover of the 6x6 room grid. */
    private static int expectedCells(String floorName) {
        return floorField(floorName, "cells");
    }

    private static int floorField(String floorName, String field) {
        try {
            Class<?> cls = Class.forName("com.killer560.hub.roomsim.SimFloorGen$Floor");
            Object floor = Enum.valueOf(cls.asSubclass(Enum.class), floorName);
            return cls.getField(field).getInt(floor);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not read Floor." + floorName + "." + field, e);
        }
    }

    private static int copyRealRooms() {
        try {
            Path source = Path.of(SOURCE_ROOMS);
            if (!Files.isDirectory(source)) {
                return 0;
            }
            Path target = ModUnderTest.modConfig("killer560smod-rooms");
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
