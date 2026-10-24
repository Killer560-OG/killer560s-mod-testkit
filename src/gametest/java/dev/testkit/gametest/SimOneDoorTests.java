package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.harness.SuiteVerdict;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 1447-sim-one-door: a room Hypixel only ever gives one door gets exactly one door in the sim (mod sim-oldtrap-1015,
 * 2026-10-15). killer560: "Old Trap does not have a door off of the back of it, only the one entrance."
 *
 * <p>The one-door rooms are the puzzle, trap, Blood, Entrance, champion and rare rooms: on his 50 real F7 maps (run
 * history, 2026-10-15) every Entrance, Blood, Old/New Trap, puzzle and champion room had exactly one door. Each measures
 * exactly one doorway in the captures, and the layout only joins rooms through doorways - except the fill pass's carve
 * through a neighbour's wall, which on main could pick Old Trap's back wall (or New Trap's, the Entrance's, a rare
 * room's) and give it a second door.
 * <ol>
 *   <li>MEASURE: every one-door-type room in the library measures exactly one doorway (RoomDoors.of).</li>
 *   <li>PLAN: {@value #SEEDED} F7s from fixed seeds (SimFloorGen.RNG seeded, recency cleared), then {@value #PINNED} each
 *       with Old Trap and New Trap pinned at room cell (2,2) - the middle of the map, four neighbours. Every one-door room
 *       on every floor must have exactly one door in the floor's door graph (the map code's door cells). Old Trap has to
 *       have been on at least {@value #PINNED} floors, or nothing was checked.</li>
 *   <li>BUILD the first pinned Old Trap floor and read the server's level at Old Trap's four seams: exactly one is open
 *       (three high at the doorway floor), the rest are wall.</li>
 * </ol>
 * Sim only, so no GrimAC.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimOneDoorTests implements FabricClientGameTest {

    private static final String NAME = "1447-sim-one-door";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String ROOM_DOORS = "com.killer560.hub.roomsim.RoomDoors";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String BUILDER = "com.killer560.hub.roomsim.SimBuilder";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String ALTITUDE = "com.killer560.hub.roomsim.SimAltitude";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final int GRID = 11;
    private static final int ROOM_GRID = 6;
    private static final int SEEDED = 300;
    private static final int PINNED = 40;
    private static final int DOOR_FLOOR = 69;
    private static final Set<String> ONE_DOOR_TYPES = Set.of("PUZZLE", "TRAP", "BLOOD", "ENTRANCE", "CHAMPION", "RARE");

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank() || Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        SimMapTests.copyRoomsForOthers();
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);
        List<String> failures = new ArrayList<>();
        boolean built = false;
        try {
            // 1. MEASURE
            for (String room : List.of("Old Trap", "New Trap", "Blood", "Entrance")) {
                int n = ctx.computeOnClient(mc -> doorways(room));
                println("MEASURE: " + room + " has " + n + " measured doorway(s)");
                if (n != 1) {
                    failures.add("MEASURE: " + room + " measures " + n + " doorways, not 1");
                }
            }
            // 2. PLAN
            Tally all = new Tally();
            String oldTrapCode = null;
            boolean oldTrapWrong = false;
            for (int i = 0; i < SEEDED + 2 * PINNED; i++) {
                final int n = i;
                String pin = i < SEEDED ? null : i < SEEDED + PINNED ? "Old Trap" : "New Trap";
                Object[] out = ctx.computeOnClient(mc -> plan(n, pin));
                if (out == null) {
                    continue;
                }
                String code = (String) out[0];
                @SuppressWarnings("unchecked")
                Map<String, Integer> doors = (Map<String, Integer>) out[1];
                all.floors++;
                for (Map.Entry<String, Integer> e : doors.entrySet()) {
                    all.seen.merge(e.getKey(), 1, Integer::sum);
                    if (e.getValue() != 1) {
                        all.wrong.merge(e.getKey() + ":" + e.getValue(), 1, Integer::sum);
                        all.badFloors.add(n);
                    }
                }
                // Build a floor where Old Trap came out with the wrong count if there is one (main), else the first.
                if ("Old Trap".equals(pin) && doors.containsKey("Old Trap")
                        && (oldTrapCode == null || (!oldTrapWrong && doors.get("Old Trap") != 1))) {
                    oldTrapCode = code;
                    oldTrapWrong = doors.get("Old Trap") != 1;
                }
            }
            println("PLAN: " + all.floors + " floors; one-door rooms seen " + all.seen);
            println("PLAN: one-door rooms with other than one door (name:doors -> rooms): " + all.wrong + " on "
                    + all.badFloors.size() + " floor(s)");
            if (all.seen.getOrDefault("Old Trap", 0) < PINNED) {
                failures.add("premise: Old Trap was on only " + all.seen.getOrDefault("Old Trap", 0) + " floors");
            }
            if (!all.wrong.isEmpty()) {
                failures.add("PLAN: " + all.badFloors.size() + " of " + all.floors + " floors give a one-door room "
                        + "another count of doors: " + all.wrong);
            }
            // 3. BUILD
            if (oldTrapCode == null) {
                failures.add("premise: no pinned floor kept Old Trap");
            } else {
                build(ctx, oldTrapCode);
                built = true;
                String code = oldTrapCode;
                AtomicReference<int[]> result = new AtomicReference<>();
                ctx.runOnClient(mc -> seams(mc, code, "Old Trap", result));
                ctx.waitFor(mc -> result.get() != null, 600);
                int[] open = result.get();
                println("BUILD: Old Trap's seams open at the doorway floor: " + open[0] + " of " + open[1]);
                if (open[1] != 4) {
                    failures.add("premise: Old Trap has " + open[1] + " neighboring seams, expected 4 at cell (2,2)");
                }
                if (open[0] != 1) {
                    failures.add("BUILD: Old Trap is open on " + open[0] + " of its sides in the world");
                }
            }
        } catch (Throwable t) {
            failures.add("threw: " + t);
            t.printStackTrace();
        } finally {
            if (built) {
                teardown(ctx);
            }
            if (failures.isEmpty()) {
                System.out.println("[" + NAME + "] PASS - every trap, puzzle, Blood, Entrance, champion and rare room has "
                        + "exactly one door on every floor, and the built Old Trap is open on one side");
            } else {
                String why = String.join("; ", failures);
                System.out.println("[" + NAME + "] FAIL: " + why);
                SuiteVerdict.failCase(NAME, new AssertionError(why));
            }
        }
    }

    private static final class Tally {
        int floors;
        final Map<String, Integer> seen = new TreeMap<>();
        final Map<String, Integer> wrong = new TreeMap<>();
        final java.util.Set<Integer> badFloors = new java.util.TreeSet<>();
    }

    private static int doorways(String room) {
        Object mask = ModUnderTest.staticCall(ROOM_DOORS, "of", new Class<?>[]{String.class}, new Object[]{room});
        if (mask == null) {
            return -1;
        }
        return ((Set<?>) ModUnderTest.call(mask, "edges", new Class<?>[]{}, new Object[]{})).size();
    }

    private static boolean oneDoor(String name) {
        if ("Old Trap".equalsIgnoreCase(name) || "New Trap".equalsIgnoreCase(name)) {
            return true;
        }
        String t = (String) ModUnderTest.staticCall(FLOOR_GEN, "typeOf", new Class<?>[]{String.class}, new Object[]{name});
        return t != null && ONE_DOOR_TYPES.contains(t.toUpperCase(Locale.ROOT));
    }

    /** One F7: {code, one-door room name -> its doors}; seeded (index i), with {@code pin} at room cell (2,2) if given. */
    private static Object[] plan(int i, String pin) {
        try {
            java.lang.reflect.Field rngField = Class.forName(FLOOR_GEN).getDeclaredField("RNG");
            rngField.setAccessible(true);
            ((java.util.Random) rngField.get(null)).setSeed(14470L + i);
            java.lang.reflect.Field recentField = Class.forName("com.killer560.hub.roomsim.SimFloorLayout")
                    .getDeclaredField("RECENT");
            recentField.setAccessible(true);
            ((Map<?, ?>) recentField.get(null)).clear();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
        Map<Integer, String> pinned = new HashMap<>();
        if (pin != null) {
            pinned.put(2 * ROOM_GRID + 2, pin);
        }
        Object p = ModUnderTest.staticCall(FLOOR_GEN, "plan", new Class<?>[]{floor.getClass(), int.class, int.class, Map.class},
                new Object[]{floor, 2 + (i % 3), 3 + (i % 5), pinned});
        if (p == null) {
            return null;
        }
        Object decoded = ModUnderTest.call(p, "decoded", new Class<?>[]{}, new Object[]{});
        String code = (String) ModUnderTest.call(p, "code", new Class<?>[]{}, new Object[]{});
        String[] names = (String[]) ModUnderTest.call(decoded, "nameTable", new Class<?>[]{}, new Object[]{});
        int[] cellRoom = (int[]) ModUnderTest.call(decoded, "cellRoom", new Class<?>[]{}, new Object[]{});
        int[] cellDoor = (int[]) ModUnderTest.call(decoded, "cellDoor", new Class<?>[]{}, new Object[]{});
        int[] doors = new int[names.length];
        boolean[] present = new boolean[names.length];
        for (int cell = 0; cell < GRID * GRID; cell++) {
            if (cellRoom[cell] >= 0) {
                present[cellRoom[cell]] = true;
            }
            int gx = cell % GRID;
            int gz = cell / GRID;
            if (cellDoor[cell] == 0 || (gx % 2) == (gz % 2)) {
                continue;
            }
            boolean alongX = gx % 2 == 1;
            int a = alongX ? cellRoom[gz * GRID + gx - 1] : cellRoom[(gz - 1) * GRID + gx];
            int b = alongX ? cellRoom[gz * GRID + gx + 1] : cellRoom[(gz + 1) * GRID + gx];
            if (a == b) {
                continue;   // inside one room
            }
            if (a >= 0) {
                doors[a]++;
            }
            if (b >= 0) {
                doors[b]++;
            }
        }
        Map<String, Integer> out = new TreeMap<>();
        for (int r = 0; r < names.length; r++) {
            if (present[r] && oneDoor(names[r])) {
                out.put(names[r], doors[r]);
            }
        }
        return new Object[]{code, out};
    }

    private static void build(ClientGameTestContext ctx, String code) {
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> ModUnderTest.staticCall(BUILDER, "build",
                new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, code})));
        ctx.waitFor(mc -> mc.level != null && mc.player != null, 2400);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitFor(mc -> McCompat.screen(mc) == null, 1200);
        ctx.waitTicks(60);
    }

    /** {open seams, neighbouring seams} of {@code room}'s one cell, read from the server level at the doorway floor. */
    private static void seams(Minecraft mc, String code, String room, AtomicReference<int[]> out) {
        Object decoded = ModUnderTest.staticCall("com.killer560.hub.roomsim.MapCode", "decode",
                new Class<?>[]{String.class}, new Object[]{code});
        String[] names = (String[]) ModUnderTest.call(decoded, "nameTable", new Class<?>[]{}, new Object[]{});
        int[] cellRoom = (int[]) ModUnderTest.call(decoded, "cellRoom", new Class<?>[]{}, new Object[]{});
        int dy = (Integer) ModUnderTest.staticCall(ALTITUDE, "offset");
        int cell = -1;
        for (int c = 0; c < GRID * GRID; c++) {
            if (cellRoom[c] >= 0 && room.equals(names[cellRoom[c]]) && (c % GRID) % 2 == 0 && (c / GRID) % 2 == 0) {
                cell = c;
            }
        }
        if (cell < 0) {
            out.set(new int[]{-1, -1});
            return;
        }
        int gx = cell % GRID;
        int gz = cell / GRID;
        int[][] steps = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
        List<BlockPos> seamsAt = new ArrayList<>();
        for (int[] s : steps) {
            int nx = gx + 2 * s[0];
            int nz = gz + 2 * s[1];
            if (nx < 0 || nz < 0 || nx >= GRID || nz >= GRID || cellRoom[nz * GRID + nx] < 0) {
                continue;
            }
            seamsAt.add((BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class},
                    new Object[]{(gz + s[1]) * GRID + gx + s[0]}));
        }
        var server = mc.getSingleplayerServer();
        server.execute(() -> {
            var level = server.overworld();
            int open = 0;
            for (BlockPos c : seamsAt) {
                boolean clear = true;
                for (int y = DOOR_FLOOR + dy; y <= DOOR_FLOOR + dy + 2 && clear; y++) {
                    BlockPos p = new BlockPos(c.getX(), y, c.getZ());
                    clear = level.getBlockState(p).getCollisionShape(level, p).isEmpty();
                }
                open += clear ? 1 : 0;
            }
            out.set(new int[]{open, seamsAt.size()});
        });
    }

    private static void teardown(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            try {
                ModUnderTest.staticCall(SIM_STATE, "leave", new Class<?>[]{}, new Object[]{});
            } catch (Throwable ignored) {
                // never let cleanup replace the verdict
            }
        });
        ctx.runOnClient(mc -> mc.execute(() -> {
            if (mc.level != null) {
                mc.level.disconnect(net.minecraft.network.chat.Component.literal("scenario over"));
                mc.disconnectWithSavingScreen();
            }
        }));
        ctx.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null, 1200);
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> mc.execute(() ->
                McCompat.setScreen(mc, new net.minecraft.client.gui.screens.TitleScreen())));
        ctx.waitFor(mc -> McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.TitleScreen, 400);
    }

    private static void println(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}
