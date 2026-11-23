package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 1123-sim-crossdoor (simcross-1123): a puzzle in the middle of a cross of four normal rooms leaves three of them
 * with no way in (the puzzle has one door), so Play refuses it (buildExplicit -2, nothing opens); a connected drawing
 * is accepted by the same check.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimCrossDoor1123Tests implements FabricClientGameTest {

    private static final String NAME = "1123-sim-crossdoor";
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
        // Cross: puzzle at (3,2), normals N (3,1), W (2,2), E (4,2), S (3,3).
        Map<Integer, String> cross = new LinkedHashMap<>();
        cross.put(1 * ROOM_GRID + 3, normals.get(0));
        cross.put(2 * ROOM_GRID + 2, normals.get(1));
        cross.put(2 * ROOM_GRID + 3, puzzles.get(0));
        cross.put(2 * ROOM_GRID + 4, normals.get(2));
        cross.put(3 * ROOM_GRID + 3, normals.get(3));
        // Connected: a row of three normals.
        Map<Integer, String> row = new LinkedHashMap<>();
        row.put(2 * ROOM_GRID + 2, normals.get(0));
        row.put(2 * ROOM_GRID + 3, normals.get(1));
        row.put(2 * ROOM_GRID + 4, normals.get(2));

        final int[] cut = {-1, -1, 0};
        ctx.runOnClient(mc -> {
            cut[0] = unreachable(planOf(cross));
            cut[1] = unreachable(planOf(row));
            cut[2] = (Integer) ModUnderTest.staticCall(FLOOR_GEN, "buildExplicit",
                    new Class<?>[]{net.minecraft.client.Minecraft.class, Map.class}, new Object[]{mc, new LinkedHashMap<>(cross)});
        });
        System.out.println("[" + NAME + "] cross unreachable=" + cut[0] + " row unreachable=" + cut[1] + " buildExplicit(cross)=" + cut[2]);
        if (cut[0] != 3) {
            throw new AssertionError("cross: expected 3 rooms unreachable, got " + cut[0]);
        }
        if (cut[1] != 0) {
            throw new AssertionError("row: expected all reachable, got " + cut[1] + " unreachable");
        }
        if (cut[2] != -2) {
            throw new AssertionError("buildExplicit(cross) returned " + cut[2] + ", expected -2 (refused)");
        }
        System.out.println("[" + NAME + "] PASS - cross refused (3 rooms cut off), connected row accepted");
    }

    private static Object planOf(Map<Integer, String> drawn) {
        Object p = ModUnderTest.staticCall(FLOOR_GEN, "planExplicit", new Class<?>[]{Map.class, boolean.class}, new Object[]{drawn, true});
        if (p == null) {
            throw new AssertionError("planExplicit gave nothing");
        }
        return ModUnderTest.call(p, "decoded", new Class<?>[]{}, new Object[]{});
    }

    private static int unreachable(Object decoded) {
        return (Integer) ModUnderTest.staticCall(FLOOR_GEN, "unreachable",
                new Class<?>[]{decoded.getClass()}, new Object[]{decoded});
    }
}
