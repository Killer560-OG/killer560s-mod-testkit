package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Every captured room's floor has to be at the SAME height, because the sim shifts the whole map by one
 * offset and never moves a room on its own.
 *
 * <p>{@code SimAltitude} says so in as many words: "One offset for the whole map, not one per room. Rooms have
 * to line up with each other - a doorway is cut between two of them at a y found by searching." So two rooms
 * whose captures put their floors at different heights meet at a step, and the doorway between them is a hole
 * that the carve does not bridge: the carve only ever removes the air space above a floor, it never lays one.
 *
 * <p><b>Why this scenario exists.</b> Scenario 81 fails intermittently with "a doorway could not be walked
 * through", and on 2026-09-30 an improved report finally said what was wrong: nothing solid across the seam,
 * every column at head and foot height air, and the floor MISSING for the three blocks on the approach side -
 * "y-1: air air air air air air air air air smooth_stone_slab* ...". The player walked 3.2 blocks and fell
 * into a two-deep trench between "Crypt" and "Mines".
 *
 * <p>Chasing that one doorway at a time costs a whole run and finds one room per failure, because the floor
 * is random. This asks every room in the library the same question at once, with no world build at all, and
 * names the ones to look at.
 *
 * <p>It REPORTS rather than fails. Which rooms are miscaptured is killer560's data, not the mod's behaviour,
 * and a scenario that went red over his captures would stay red until he rescanned - which is a worse signal
 * than a list he can act on.
 */
public class SimRoomFloorTests implements FabricClientGameTest {

    private static final String LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";

    /** Where a Catacombs floor sits, and what every capture's floor should therefore read. */
    private static final int EXPECTED_FLOOR_Y = 69;

    @Override
    @SuppressWarnings("unchecked")
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip("92-sim-room-floors")) {
            return;
        }
        ModUnderTest.require("killer560smod");
        ctx.waitTicks(40);

        List<String> names = (List<String>) ctx.computeOnClient(mc ->
                ModUnderTest.staticCall(LIBRARY, "names"));
        if (names == null || names.isEmpty()) {
            System.out.println("[92-sim-room-floors] SKIPPED - the room library is empty, so there is nothing "
                    + "to measure. That is the harness, not the mod.");
            return;
        }

        // UNREAD COLUMNS, exactly - not "complete", which is a threshold.
        //
        // One candidate cause of the trench scenario 81 found is a capture missing blocks there: an unread
        // column pastes nothing and leaves air. RoomLibrary.complete() cannot rule that out, because it is
        // completeness >= 0.999 rather than 1.0 - on a three-tile room that is 9409 columns, so nine unread
        // columns still counts as "complete", and nine is exactly the size of the hole that was measured.
        // So the count is printed rather than the verdict.
        List<String> gaps = new ArrayList<>();
        for (String name : names) {
            String gap = ctx.computeOnClient(mc -> unreadColumns(name));
            if (gap != null) {
                gaps.add(gap);
            }
        }
        if (gaps.isEmpty()) {
            System.out.println("[92-sim-room-floors] every column of every room was read - a capture missing "
                    + "blocks is NOT the cause of an unwalkable doorway");
        } else {
            System.out.println("[92-sim-room-floors] captures with unread columns (these paste as nothing and "
                    + "leave air, which is one candidate for the doorway trench):");
            for (String line : gaps) {
                System.out.println("[92-sim-room-floors]   " + line);
            }
        }

        List<String> odd = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        int agreeing = 0;
        for (String name : names) {
            Integer floor = ctx.computeOnClient(mc -> modalFloor(name));
            if (floor == null) {
                unreadable.add(name);
                continue;
            }
            if (floor == EXPECTED_FLOOR_Y) {
                agreeing++;
            } else {
                odd.add(String.format(Locale.ROOT, "%s floor at y%d (%+d)",
                        name, floor, floor - EXPECTED_FLOOR_Y));
            }
        }

        System.out.println("[92-sim-room-floors] " + names.size() + " room(s) in the library; " + agreeing
                + " have their floor at y" + EXPECTED_FLOOR_Y + ", " + odd.size() + " do not, "
                + unreadable.size() + " could not be measured");
        // The unmeasurable ones are named too. A room this cannot read is not a room that passed, and
        // leaving it out of the output would let it read as one.
        if (!unreadable.isEmpty()) {
            System.out.println("[92-sim-room-floors] not measurable (no standable column found): "
                    + String.join(", ", unreadable));
        }
        if (odd.isEmpty()) {
            System.out.println("[92-sim-room-floors] PASS - every measurable room's floor is at the same "
                    + "height, so no doorway between two of them can open onto a step");
            return;
        }
        // Two kinds of offset, and only one of them is worth chasing.
        //
        // A large one usually means the room has a big second level and the mode landed on it - Lava Ravine
        // and Lower Blaze are lower-level rooms and belong nowhere near y69. A SMALL one is the dangerous
        // kind: one or two blocks is exactly the step that makes a doorway unwalkable, and it is small enough
        // that nothing else about the room looks wrong.
        List<String> near = new ArrayList<>();
        List<String> far = new ArrayList<>();
        for (String line : odd) {
            (line.matches(".*\\([+-][1-5]\\)$") ? near : far).add(line);
        }
        System.out.println("[92-sim-room-floors] WITHIN 5 BLOCKS of y" + EXPECTED_FLOOR_Y + " - these are the "
                + "ones to look at, because a step of one or two blocks is what makes a doorway unwalkable "
                + "and is scenario 81's intermittent impassable doorway:");
        for (String line : near) {
            System.out.println("[92-sim-room-floors]   " + line);
        }
        System.out.println("[92-sim-room-floors] further out - usually a room with a large second level, "
                + "where the most common walkable height is a mezzanine rather than the floor, so these are "
                + "probably fine:");
        for (String line : far) {
            System.out.println("[92-sim-room-floors]   " + line);
        }
        System.out.println("[92-sim-room-floors] REPORT ONLY - these are captures to look at, not a fault in "
                + "the mod, so this does not fail the suite");
    }

    /**
     * The most common standable floor height in a room, in the capture's own coordinates.
     *
     * <p>Modal rather than lowest or highest: rooms have pits, pillars and mezzanines, and any single column
     * can be any of those. The height most of the room's columns agree on is the floor a player walks on, and
     * it is the one a doorway is cut at.
     *
     * @return the floor height, or null for a room with no standable column at all
     */
    private static Integer modalFloor(String name) {
        Object room = ModUnderTest.staticCall(LIBRARY, "get",
                new Class<?>[]{String.class}, new Object[]{name});
        if (room == null) {
            return null;
        }
        try {
            Class<?> type = room.getClass();
            int sizeX = type.getField("sizeX").getInt(room);
            int sizeZ = type.getField("sizeZ").getInt(room);
            int minY = type.getField("minY").getInt(room);
            int maxY = type.getField("maxY").getInt(room);
            @SuppressWarnings("unchecked")
            List<String> palette = (List<String>) type.getField("palette").get(room);
            var at = type.getMethod("at", int.class, int.class, int.class);
            at.setAccessible(true);

            Map<Integer, Integer> tally = new TreeMap<>();
            // Every fourth column. A room is up to 97 across, the mode does not need every sample, and a
            // full scan of 135 rooms through reflection in a gametest client is a stall.
            for (int x = 1; x < sizeX - 1; x += 4) {
                for (int z = 1; z < sizeZ - 1; z += 4) {
                    // EVERY standable surface in the column, not the first one from either end.
                    //
                    // Both one-per-column versions of this were wrong, in opposite directions, and each
                    // produced a confident list of rooms to rescan that was really a list of the probe's own
                    // mistakes. Scanning DOWN from the top finds the roof: a room is captured between y 60
                    // and 140, so there is open air above its ceiling and the ceiling's top face is perfectly
                    // standable - that version called forty rooms miscaptured at exactly y100, which is not a
                    // floor, it is how high a standard room's roof is. Scanning UP from the bottom finds the
                    // bottom of any shaft: that version put Mines at y38 and Lava Ravine at y20, which are
                    // their lower levels, not their floors.
                    //
                    // The floor is not the first surface from either end. It is the height that MOST of the
                    // room's columns share, so every surface is counted and the mode decides.
                    for (int y = minY; y <= maxY - 2; y++) {
                        if (!solid(palette, (Short) at.invoke(room, x, y, z))) {
                            continue;
                        }
                        // Standable: two blocks of air over it, which is what the player needs and what the
                        // doorway carve leaves.
                        if (solid(palette, (Short) at.invoke(room, x, y + 1, z))
                                || solid(palette, (Short) at.invoke(room, x, y + 2, z))) {
                            continue;
                        }
                        // INDOORS: there has to be a ceiling somewhere above it.
                        //
                        // Without this the mode is won by the ROOF, because a roof is a clean 31 by 31 slab
                        // with nothing over it while a floor is broken up by furniture - so the roof has more
                        // clear columns than the floor does. That is why three versions of this in a row put
                        // twenty-odd rooms at exactly y100: not a capture fault, the probe measuring the top
                        // of the building. A floor has a ceiling over it; a roof, by definition, does not.
                        boolean covered = false;
                        for (int up = y + 3; up <= maxY && !covered; up++) {
                            covered = solid(palette, (Short) at.invoke(room, x, up, z));
                        }
                        if (!covered) {
                            continue;
                        }
                        tally.merge(y + 1, 1, Integer::sum);
                    }
                }
            }
            int best = -1;
            int bestCount = 0;
            for (Map.Entry<Integer, Integer> e : tally.entrySet()) {
                if (e.getValue() > bestCount) {
                    bestCount = e.getValue();
                    best = e.getKey();
                }
            }
            return bestCount == 0 ? null : best;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * How many of a room's columns were never read, or null when all of them were.
     *
     * @return a one-line description including the count and the fraction, or null for a complete capture
     */
    private static String unreadColumns(String name) {
        Object room = ModUnderTest.staticCall(LIBRARY, "get",
                new Class<?>[]{String.class}, new Object[]{name});
        if (room == null) {
            return null;
        }
        try {
            boolean[] seen = (boolean[]) room.getClass().getField("seenColumn").get(room);
            int unread = 0;
            for (boolean b : seen) {
                if (!b) {
                    unread++;
                }
            }
            if (unread == 0) {
                return null;
            }
            return String.format(Locale.ROOT, "%s: %d of %d columns unread (%.3f complete%s)",
                    name, unread, seen.length, 1.0 - (double) unread / seen.length,
                    // Whether the mod's own threshold would still call it complete, which is the point.
                    1.0 - (double) unread / seen.length >= 0.999 ? ", still passes complete()" : "");
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** A palette index that is a real, solid block - not air, and not a column that was never read. */
    private static boolean solid(List<String> palette, Short index) {
        if (index == null || index < 0 || index >= palette.size()) {
            return false;
        }
        String state = palette.get(index);
        return !state.startsWith("minecraft:air") && !state.startsWith("minecraft:cave_air")
                && !state.startsWith("minecraft:void_air");
    }
}
