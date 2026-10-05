package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Does the dungeon map draw ONE room per placement?
 *
 * <p>killer560 (2026-09-30), on a screenshot of the HUD map: "the map is wrong in this second picture. THis
 * room is not part of supertall it is its on 1x1." A separate 1x1 room was drawn inside the 2x2 Supertall
 * beside it - one outline, one label, two rooms - so the map said something about the floor that the floor
 * did not say about itself.
 *
 * <p>Scenario 79 now makes the same check, but on the ONE floor it builds, and the floor is random: the run
 * that found this had Supertall on it and the next twenty may not. This scenario asks the same question over
 * a hundred floors, and it can, because neither half needs a world. {@code SimFloorGen.plan} lays a floor out
 * without touching the level (that is why scenario 73 can assert over hundreds), and
 * {@code LiveMapFeature.publishSimFloor} plus {@code DungeonLayout.capture} are the whole map path from "here
 * is the floor" to "here is what is drawn" - both client-thread-only, neither needing a block to exist.
 *
 * <p>The comparison is between two partitions of the same 36 room tiles: the map code says which PLACEMENT
 * owns each one, the map's grouping says which map room owns it. They have to be the same partition. Only the
 * even cells are compared - an odd connector is a room's interior on one floor and a door gap on the next, so
 * it cannot settle anything either way.
 */
public class SimMapGroupTests implements FabricClientGameTest {

    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String LIVE_MAP = "com.killer560.hub.livemap.LiveMapFeature";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";

    private static final String SOURCE_ROOMS =
            ModUnderTest.instanceConfig("C:/Users/Hunter/AppData/Roaming/PrismLauncher/instances/26.1.2 (Mod Only Test)"
                    + "/minecraft/config", "killer560smod-rooms");

    private static final int GRID = 11;

    /** Floors to plan per phase - once at random, once with {@link #PINNED_ROOM} pinned. */
    private static final int FLOORS = 120;

    /** The room from his report, so the case he photographed is on every floor of the second phase. */
    private static final String PINNED_ROOM = "Supertall";

    /** Room-grid cell to pin it at, keyed {@code gz * 6 + gx} the way the map designer keys it. */
    private static final int PIN_CELL = 2 * 6 + 2;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip("90-sim-group")) {
            return;
        }
        ModUnderTest.require("killer560smod");
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff(
                "com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));

        if (copyDir(SOURCE_ROOMS, "killer560smod-rooms", ".json") < 20
                || copyDir(Path.of(SOURCE_ROOMS).resolveSibling("killer560smod-roomdata").toString(),
                        "killer560smod-roomdata", "") == 0) {
            System.out.println("[90-sim-group] SKIPPED - needs his real rooms and room database");
            return;
        }
        ctx.runOnClient(mc -> ModUnderTest.staticCall(
                "com.killer560.hub.roomdatabase.RoomDatabase", "ensureLoading"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(
                "com.killer560.hub.roomdatabase.RoomDatabase", "isReady"));
        // Room types come from the room database; the sim will not plan a floor without it (mod, 2026-10-05).
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"));

        List<String> merged = new ArrayList<>();
        List<String> split = new ArrayList<>();
        int[] counts = new int[4];   // floors planned, room tiles compared, map rooms seen, floors with the pin

        ctx.runOnClient(mc -> {
            Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
            for (int run = 0; run < FLOORS * 2; run++) {
                // The second half PINS the room from his report. A 2x2 is not on every floor and the one he
                // named may be on very few, so a hundred random floors can miss the case entirely and report
                // "no merges" while never once drawing the thing he photographed. Pinning it makes the run
                // that matters happen every time.
                Map<Integer, String> pinned = run < FLOORS ? null : Map.of(PIN_CELL, PINNED_ROOM);
                Object planned = ModUnderTest.staticCall(FLOOR_GEN, "plan",
                        new Class<?>[]{floor.getClass(), int.class, int.class, Map.class},
                        new Object[]{floor, 3, 4, pinned});
                if (planned == null) {
                    continue;
                }
                Object decoded = ModUnderTest.call(planned, "decoded", new Class<?>[]{}, new Object[]{});
                int[] cellRoom = (int[]) ModUnderTest.call(decoded, "cellRoom",
                        new Class<?>[]{}, new Object[]{});
                int[] cellDoor = (int[]) ModUnderTest.call(decoded, "cellDoor",
                        new Class<?>[]{}, new Object[]{});
                String[] table = (String[]) ModUnderTest.call(decoded, "nameTable",
                        new Class<?>[]{}, new Object[]{});
                ModUnderTest.staticCall(LIVE_MAP, "publishSimFloor",
                        new Class<?>[]{int[].class, int[].class, String[].class, int[][].class},
                        new Object[]{cellRoom, cellDoor, table, null});
                Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
                counts[0]++;
                for (String n : table) {
                    if (PINNED_ROOM.equals(n)) {
                        counts[3]++;
                        break;
                    }
                }

                Map<Integer, Set<Integer>> placementsOfMapRoom = new LinkedHashMap<>();
                Map<Integer, Set<Integer>> mapRoomsOfPlacement = new LinkedHashMap<>();
                for (int c = 0; c < GRID * GRID; c++) {
                    if ((c % GRID) % 2 != 0 || (c / GRID) % 2 != 0) {
                        continue;
                    }
                    int placement = cellRoom[c];
                    int mapRoom = (Integer) ModUnderTest.call(layout, "roomOfCell",
                            new Class<?>[]{int.class}, new Object[]{c});
                    if (placement < 0 || mapRoom < 0) {
                        continue;
                    }
                    counts[1]++;
                    placementsOfMapRoom.computeIfAbsent(mapRoom, k -> new LinkedHashSet<>()).add(placement);
                    mapRoomsOfPlacement.computeIfAbsent(placement, k -> new LinkedHashSet<>()).add(mapRoom);
                }
                counts[2] += placementsOfMapRoom.size();
                for (Map.Entry<Integer, Set<Integer>> e : placementsOfMapRoom.entrySet()) {
                    if (e.getValue().size() < 2 || merged.size() >= 20) {
                        continue;
                    }
                    StringBuilder who = new StringBuilder();
                    for (int p : e.getValue()) {
                        who.append(who.length() == 0 ? "" : " + ")
                                .append(p < table.length ? table[p] : "?");
                    }
                    merged.add("floor " + run + ": one map room is " + e.getValue().size()
                            + " rooms drawn as one - " + who);
                }
                for (Map.Entry<Integer, Set<Integer>> e : mapRoomsOfPlacement.entrySet()) {
                    if (e.getValue().size() < 2 || split.size() >= 20) {
                        continue;
                    }
                    split.add("floor " + run + ": "
                            + (e.getKey() < table.length ? table[e.getKey()] : "?")
                            + " is drawn as " + e.getValue().size() + " separate map rooms");
                }
            }
        });

        System.out.println("[90-sim-group] planned " + counts[0] + " floor(s), compared " + counts[1]
                + " room tile(s) across " + counts[2] + " map room(s)");
        System.out.println("[90-sim-group] " + counts[3] + " of those floor(s) actually held " + PINNED_ROOM);
        if (counts[3] == 0) {
            throw new AssertionError(PINNED_ROOM + " never reached a single floor, pinned or not - so this "
                    + "scenario never drew the case it exists to check");
        }
        System.out.println("[90-sim-group] rooms merged into a neighbour: " + merged.size()
                + " (capped at 20), rooms split across map rooms: " + split.size() + " (capped at 20)");
        for (String s : split) {
            System.out.println("[90-sim-group]   SPLIT " + s);
        }
        for (String s : merged) {
            System.out.println("[90-sim-group]   MERGED " + s);
        }
        if (counts[0] == 0 || counts[1] == 0) {
            throw new AssertionError("no floor was planned, so nothing was compared - this scenario proved "
                    + "nothing rather than proving the map is right");
        }
        if (!merged.isEmpty()) {
            throw new AssertionError(merged.size() + " map room(s) cover more than one room: " + merged);
        }
        if (!split.isEmpty()) {
            throw new AssertionError(split.size() + " room(s) are drawn as more than one map room: " + split);
        }
        System.out.println("[90-sim-group] PASS - every map room is exactly one placed room");
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
