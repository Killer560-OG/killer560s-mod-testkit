package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 1122-sim-puzzledoor (simpuzzledoor-1122): a hand-drawn puzzle room is a dead end with exactly ONE door, like
 * Hypixel's, and the rest of the drawing stays reachable. The drawing is a puzzle with a normal room on three
 * sides and a row of rooms under it joining those three, so the old link order gave the puzzle three doors. Checked
 * on the editor's plan (planExplicit, what the grid draws), on the code the world is built from, and for reachability.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimPuzzleDoor1122Tests implements FabricClientGameTest {

    private static final String NAME = "1122-sim-puzzledoor";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String MAP_CODE = "com.killer560.hub.roomsim.MapCode";
    private static final int GRID = 11;
    private static final int ROOM_GRID = 6;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (SimMapTests.copyRoomsForOthers() < 20) {
            Scenario.skipped(NAME, "needs real room captures");
            return;
        }
        ctx.runOnClient(mc -> Scenario.loadRoomDatabaseNow());
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.roomdatabase.RoomDatabase", "isReady"), 1000);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"));

        List<String> normals = new ArrayList<>();
        List<String> puzzles = new ArrayList<>();
        ctx.runOnClient(mc -> {
            @SuppressWarnings("unchecked")
            Iterable<String> names = (Iterable<String>) ModUnderTest.staticCall(ROOM_LIBRARY, "names");
            for (String n : names) {
                if (!(Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isUsable",
                        new Class<?>[]{String.class}, new Object[]{n})
                        || (Integer) ModUnderTest.staticCall(ROOM_LIBRARY, "cellFootprint",
                        new Class<?>[]{String.class}, new Object[]{n}) != 1) {
                    continue;
                }
                String type = (String) ModUnderTest.staticCall(FLOOR_GEN, "typeOf",
                        new Class<?>[]{String.class}, new Object[]{n});
                if ("NORMAL".equalsIgnoreCase(type) && normals.size() < 5) {
                    normals.add(n);
                } else if ("PUZZLE".equalsIgnoreCase(type) && puzzles.isEmpty()) {
                    puzzles.add(n);
                }
            }
        });
        if (normals.size() < 5 || puzzles.isEmpty()) {
            throw new AssertionError("library has " + normals.size() + " normal and " + puzzles.size() + " puzzle 1x1 rooms");
        }
        // Columns 2..4, rows 2..3: normal, PUZZLE, normal on top; normal, normal, normal under it.
        Map<Integer, String> drawn = new LinkedHashMap<>();
        drawn.put(2 * ROOM_GRID + 2, normals.get(0));
        drawn.put(2 * ROOM_GRID + 3, puzzles.get(0));
        drawn.put(2 * ROOM_GRID + 4, normals.get(1));
        drawn.put(3 * ROOM_GRID + 2, normals.get(2));
        drawn.put(3 * ROOM_GRID + 3, normals.get(3));
        drawn.put(3 * ROOM_GRID + 4, normals.get(4));

        final Object[] out = new Object[4];
        ctx.runOnClient(mc -> {
            Object p = ModUnderTest.staticCall(FLOOR_GEN, "planExplicit", new Class<?>[]{Map.class}, new Object[]{drawn});
            if (p == null) {
                return;
            }
            Object d = ModUnderTest.call(p, "decoded", new Class<?>[]{}, new Object[]{});
            out[0] = ModUnderTest.call(d, "nameTable", new Class<?>[]{}, new Object[]{});
            out[1] = ModUnderTest.call(d, "cellRoom", new Class<?>[]{}, new Object[]{});
            out[2] = ModUnderTest.call(d, "cellDoor", new Class<?>[]{}, new Object[]{});
            // The world is built from this code: decode it again and read the doors back.
            Object back = ModUnderTest.staticCall(MAP_CODE, "decode", new Class<?>[]{String.class},
                    new Object[]{ModUnderTest.call(p, "code", new Class<?>[]{}, new Object[]{})});
            out[3] = back == null ? null : ModUnderTest.call(back, "cellDoor", new Class<?>[]{}, new Object[]{});
        });
        if (out[0] == null || out[3] == null) {
            throw new AssertionError("planExplicit or the map code gave nothing");
        }
        String[] names = (String[]) out[0];
        int[] roomOf = (int[]) out[1];
        int puzzleCell = (2 * 2) * GRID + 3 * 2;
        int id = roomOf[puzzleCell];
        if (id < 0 || !names[id].equals(puzzles.get(0))) {
            throw new AssertionError("the puzzle is not at its cell");
        }
        for (int which = 2; which <= 3; which++) {
            int[] doorOf = (int[]) out[which];
            int doors = 0;
            for (int off : new int[]{1, -1, GRID, -GRID}) {
                if (doorOf[puzzleCell + off] != 0) {
                    doors++;
                }
            }
            System.out.println("[" + NAME + "] " + (which == 2 ? "editor plan" : "built code") + ": puzzle has " + doors + " door(s)");
            if (doors != 1) {
                throw new AssertionError((which == 2 ? "editor plan" : "built code") + ": the puzzle has " + doors + " doors, Hypixel gives it one");
            }
        }
        // Every drawn room reachable from the first one over doors.
        int[] doorOf = (int[]) out[3];
        boolean[] seen = new boolean[GRID * GRID];
        java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
        int start = (2 * 2) * GRID + 2 * 2;
        q.add(start);
        seen[start] = true;
        int reached = 0;
        while (!q.isEmpty()) {
            int c = q.poll();
            if (roomOf[c] >= 0) {
                reached++;
            }
            for (int off : new int[]{1, -1, GRID, -GRID}) {
                int door = c + off;
                int next = c + 2 * off;
                if (door < 0 || next < 0 || next >= seen.length || seen[next] || doorOf[door] == 0
                        || Math.abs(off) == 1 && next / GRID != c / GRID) {
                    continue;
                }
                seen[next] = true;
                q.add(next);
            }
        }
        if (reached != drawn.size()) {
            throw new AssertionError("only " + reached + " of " + drawn.size() + " rooms are reachable over doors");
        }
        System.out.println("[" + NAME + "] PASS - one puzzle door on the plan and in the built code, " + reached + " rooms reachable");
    }
}
