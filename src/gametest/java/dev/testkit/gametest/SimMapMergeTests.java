package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Do two rooms the map shows as separate stay separate when the world scan has marked the gap between them as room?
 *
 * <p>killer560 (2026-10-18), a real run: the Well was drawn merged into the Fairy and the Fairy into the Puzzle, with
 * stray pixels. {@code LiveMapFeature.rebuildGroups} joined an identified room to a neighbour across any connector the
 * world scan called room - even one the calibrated dungeon map showed as nothing (a wall) - and joined two tiles that
 * shared a name anywhere on the floor.
 *
 * <p>The floor is built here without a world. Room 0 is Supertall (identified, a 2x2 so it has room for a second tile)
 * at grid cell 0; room 1 is a name the database does not know, so its tile is unidentified, at cell 2. The gap at cell 1
 * is marked room by the world scan, and the map is calibrated.
 *
 * <p>Phase A: the map shows nothing in the gap. The two tiles must stay two rooms.
 * Phase B (control): the map shows a room separator in the gap. They must join, which proves this case can merge at all.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimMapMergeTests implements FabricClientGameTest {

    private static final String LIVE_MAP = "com.killer560.hub.livemap.LiveMapFeature";
    private static final String SCANNER = "com.killer560.hub.livemap.DungeonMapScanner";
    private static final String SOURCE_ROOMS =
            ModUnderTest.instanceConfig(Machine.roomsConfigDir(), "killer560smod-rooms");

    private static final int GRID = 11;
    private static final int WALL_CELL = 1;
    private static final int SEPARATOR_KIND = 2;
    private static final int NONE_KIND = 0;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip("1018-map-merge-wall")) {
            return;
        }
        ModUnderTest.require("killer560smod");
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff(
                "com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));

        if (copyDir(SOURCE_ROOMS, "killer560smod-rooms", ".json") < 20
                || copyDir(Path.of(SOURCE_ROOMS).resolveSibling("killer560smod-roomdata").toString(),
                        "killer560smod-roomdata", "") == 0) {
            Scenario.skipped("1018-map-merge-wall", "needs real room captures and room database");
            return;
        }
        ctx.runOnClient(mc -> Scenario.loadRoomDatabaseNow());
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(
                "com.killer560.hub.roomdatabase.RoomDatabase", "isReady"));
        Scenario.ensureRoomDatabase(ctx);

        String[] phase = new String[2];
        ctx.runOnClient(mc -> {
            try {
                // Phase A: the map shows nothing in the gap. Phase B: the map shows a separator there.
                phase[0] = joined(NONE_KIND);
                phase[1] = joined(SEPARATOR_KIND);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("could not drive LiveMapFeature by reflection", e);
            } finally {
                try {
                    Method reset = Class.forName(SCANNER).getDeclaredMethod("reset");
                    reset.setAccessible(true);
                    reset.invoke(null);
                } catch (ReflectiveOperationException e) {
                    System.out.println("[1018-map-merge-wall] could not reset the map scanner: " + e);
                }
            }
        });

        boolean wallJoined = Boolean.parseBoolean(phase[0]);
        boolean separatorJoined = Boolean.parseBoolean(phase[1]);
        System.out.println("[1018-map-merge-wall] wall gap joined: " + wallJoined
                + ", separator gap joined: " + separatorJoined);
        if (!separatorJoined) {
            throw new AssertionError("control failed: a room separator between Supertall and the unidentified tile did "
                    + "not join them, so this case never tested a merge");
        }
        if (wallJoined) {
            throw new AssertionError("Supertall and the unidentified room joined across a gap the calibrated map shows "
                    + "as nothing - two rooms drawn as one");
        }
        System.out.println("[1018-map-merge-wall] PASS - a wall gap keeps two rooms apart, a separator still joins them");
    }

    /** Publishes the two-room floor with the gap at {@link #WALL_CELL} given {@code gapKind}, rebuilds the groups,
     *  and says whether the tile of cell 0 and the tile of cell 2 landed in one group. */
    private static String joined(int gapKind) throws ReflectiveOperationException {
        int[] roomCells = new int[GRID * GRID];
        java.util.Arrays.fill(roomCells, -1);
        roomCells[0] = 0;   // Supertall
        roomCells[2] = 1;   // unidentified
        ModUnderTest.staticCall(LIVE_MAP, "publishSimFloor",
                new Class<?>[]{int[].class, int[].class, String[].class, int[][].class},
                new Object[]{roomCells, new int[0], new String[]{"Supertall", "Nowhere Room"}, null});

        // The world scan marked the connector room; the map's own reading of that gap is what varies.
        Object room = ModUnderTest.enumValue(LIVE_MAP + "$Tile", "ROOM");
        Field gridField = Class.forName(LIVE_MAP).getDeclaredField("grid");
        gridField.setAccessible(true);
        Array.set(gridField.get(null), WALL_CELL, room);

        Field calibrated = Class.forName(SCANNER).getDeclaredField("calibrated");
        calibrated.setAccessible(true);
        calibrated.setBoolean(null, true);
        Field kinds = Class.forName(SCANNER).getDeclaredField("kinds");
        kinds.setAccessible(true);
        ((int[]) kinds.get(null))[WALL_CELL] = gapKind;

        Method rebuild = Class.forName(LIVE_MAP).getDeclaredMethod("rebuildGroups");
        rebuild.setAccessible(true);
        rebuild.invoke(null);

        Field groupsField = Class.forName(LIVE_MAP).getDeclaredField("groups");
        groupsField.setAccessible(true);
        List<?> groups = (List<?>) groupsField.get(null);
        Field cellsField = Class.forName(LIVE_MAP + "$RoomGroup").getDeclaredField("cells");
        cellsField.setAccessible(true);
        for (Object g : groups) {
            int[] cells = (int[]) cellsField.get(g);
            boolean hasSupertall = false;
            boolean hasOther = false;
            for (int c : cells) {
                hasSupertall |= c == 0;
                hasOther |= c == 2;
            }
            if (hasSupertall) {
                return Boolean.toString(hasOther);
            }
        }
        throw new AssertionError("cell 0 is in no group at all - the floor did not publish");
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
