package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.harness.SuiteVerdict;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 1446-sim-door-height: the sim never joins two rooms through an opening that is not a doorway, and every door of the rooms
 * that have one is walkable at the doorway floor (mod sim-doorheight-1015, 2026-10-15).
 *
 * <p>Every Hypixel doorway is at one height (opening y 69..72) and so is every doorway in the captures. The mod's RoomDoors
 * used to accept any 3x4 opening from y64 to y86, which also took two openings that are not doors: Mage's north wall on
 * its second tile (open y72..87 - that tile of the capture is a tile of Altar, by the room database's core hash) and a red
 * terracotta panel at y77..80 in Quad Lava's east wall (red terracotta reads as a shut blood door). The layout then joined
 * a neighbor to them there, and the carve, at the doorway floor, cut 3x4x3 into the solid wall below the opening: a
 * tunnel that ends in stone.
 *
 * <p>For each of those rooms (Mage, and Quad Lava with its edge E0 in the capture's own frame):
 * <ol>
 *   <li>The edge is on the room's tile grid and is NOT measured as a doorway.</li>
 *   <li>PLAN: {@value #PLANS} F7s with the room pinned at room cell (2,2); count (and print) floors whose door tree uses
 *       that edge (turned by the room's placed rotation, through the mod's own RoomDoors.rotate/doorCells). It is not a
 *       doorway, so such a join is the fill pass's carve through the wall - by design, and checked in BUILD like any
 *       other. The Entrance's west wall (its back staircase) is counted on every floor as a control and must be 0.</li>
 *   <li>BUILD the first floor that used the edge (or, when none did, the first that kept the room) and read the server's
 *       level at every door of the room: the 3x7 passage at the doorway floor is clear three high with floor under it,
 *       you can walk on from it 4..6 blocks into EACH room (solid underfoot, feet and head clear, one block up or any drop), and the
 *       carve removed no more than {@value #MAX_SOLID_REMOVED} solid capture blocks inside the room (wall plane and the
 *       two columns behind it, the opening's lower three rows) - at a doorway the mod measures for the room. A door that
 *       is the fill pass's carve through the room's wall (no doorway of the room there, by design) is held to the walk
 *       checks only; what it cut behind the wall plane is printed.</li>
 *   <li>TUNNEL: {@value #TUNNEL_PLANS} F7s from fixed seeds; the solid capture blocks every fill carve cuts behind its
 *       wall plane, summed. Must be at most {@value #MAX_TUNNEL}: mod sim-doorblock-1015 takes the walkable wall with
 *       the least behind it anywhere on the floor, where main took the first in cell order.</li>
 * </ol>
 * The Mage case's edge used to be N1: the stitched 65x33 capture, whose second tile was Altar's interior. The re-capture
 * of 2026-10-15 is 33x65 and has no N1, and the old edge pointed at the cell EAST of Mage - the "4 of 30 floors joined"
 * on main 4a2b306d were doors between two other rooms. Quad Lava's "1 of 30" was the fill pass's carve through its
 * three-thick east wall, the first walkable wall in cell order.
 * Sim only, so no GrimAC.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimDoorHeightTests implements FabricClientGameTest {

    private static final String NAME = "1446-sim-door-height";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String ROOM_DOORS = "com.killer560.hub.roomsim.RoomDoors";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String BUILDER = "com.killer560.hub.roomsim.SimBuilder";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String ALTITUDE = "com.killer560.hub.roomsim.SimAltitude";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final int GRID = 11;
    private static final int ROOM_GRID = 6;
    private static final int PLANS = 30;
    private static final int MAX_SOLID_REMOVED = 2;
    /** The opening's lower three rows through a one-thick wall: what a fill-pass carve through a wall must cut. */
    private static final int WALL_PLANE = 9;
    private static final int TUNNEL_PLANS = 1000;
    /**
     * Between the two jars' totals on the TUNNEL plans (his Mod Only Test library, 2026-10-15): main 4a2b306d 2,505
     * blocks over 325 off-doorway doors (73 through a one-thick wall), sim-doorblock-1015 2,041 over 332 (115). The floors
     * move with the room library, so re-measure both if a capture changes and this starts failing.
     */
    private static final int MAX_TUNNEL = 2270;
    private static final int DOOR_FLOOR = 69;
    private static final int[] DX = {0, 1, 0, -1};
    private static final int[] DZ = {-1, 0, 1, 0};

    /** A room and the capture-frame edge (side NESW 0..3, tile index) that is an opening but not a doorway. */
    private record Case(String room, int side, int index, String label) {
    }

    /**
     * Mage carries no edge (side -1): the stitched capture whose second tile was Altar's interior (N1 of a 65x33 room) was
     * replaced on 2026-10-15 by a real 33x65 one, which has no N1 - the old case's edge then pointed at the cell EAST of
     * Mage and counted other rooms' doors (4 of 30 "joined" floors on main 4a2b306d, every one a door between two
     * neighbors). It stays as a room to pin and build, so every door of it is still checked.
     */
    private static final List<Case> CASES = List.of(
            new Case("Mage", -1, 0, "Mage (re-captured 2026-10-15, 1x2)"),
            new Case("Quad Lava", 1, 0, "Quad Lava E0 (red terracotta panel, y77..80, three-thick wall)"));
    private static final Case CONTROL = new Case("Entrance", 3, 0, "Entrance W0 (above the back staircase)");

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
            for (Case c : CASES) {
                boolean usable = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isUsable",
                        new Class<?>[]{String.class}, new Object[]{c.room()}));
                if (!usable) {
                    failures.add("premise: " + c.room() + " is not a usable capture here");
                    continue;
                }
                if (c.side() >= 0) {
                    String off = ctx.computeOnClient(mc -> offGrid(c));
                    if (off != null) {
                        failures.add("premise: " + c.label() + " is not an edge of " + c.room() + " here (" + off + ")");
                        continue;
                    }
                    boolean doorway = ctx.computeOnClient(mc -> hasDoorway(c));
                    println(c.label() + ": measured as a doorway by this jar: " + doorway);
                    if (doorway) {
                        failures.add(c.label() + ": measured as a doorway");
                    }
                }
                Plan p = planMany(ctx, c);
                // Not a doorway (asserted above), so a join there is the fill pass's carve through the wall - by design,
                // and held to the walk checks below like every other fill carve. Since mod sim-doorblock-1015 the fill
                // takes the walkable wall with the least behind its plane anywhere on the floor, so this three-thick one
                // only when nothing thinner fits (layoutsim, Quad Lava pinned: 5 -> 2 of 840 F7s).
                println(c.label() + ": joined by the fill pass's carve on " + p.linked() + " of " + p.kept() + " floors");
                if (p.controlLinked() > 0) {
                    failures.add(CONTROL.label() + ": joined on " + p.controlLinked() + " floors");
                }
                if (p.code() == null) {
                    failures.add("premise: no floor kept " + c.room() + " in " + PLANS + " plans");
                    continue;
                }
                build(ctx, p.code());
                built = true;
                Check k = check(ctx, p.code(), c.room());
                for (String l : k.lines()) {
                    println(l);
                }
                if (k.doors() == 0) {
                    failures.add("premise: " + c.room() + " has no door on the built floor");
                }
                for (String f : k.failures()) {
                    failures.add(c.room() + ": " + f);
                }
            }
            Tunnel t = tunnels(ctx);
            println("TUNNEL: " + TUNNEL_PLANS + " seeded F7s (recency cleared), " + t.carves() + " door(s) off a measured "
                    + "doorway, " + t.thin() + " through a one-thick wall, " + t.behind() + " solid capture block(s) behind "
                    + "their wall planes (limit " + MAX_TUNNEL + ")");
            if (t.carves() == 0) {
                failures.add("premise: no fill carve on " + TUNNEL_PLANS + " floors - the tunnel check measured nothing");
            } else if (t.behind() > MAX_TUNNEL) {
                failures.add("TUNNEL: the fill carves cut " + t.behind() + " solid block(s) behind wall planes over "
                        + TUNNEL_PLANS + " seeded F7s (limit " + MAX_TUNNEL + ") - it is not taking the thinnest wall");
            }
        } catch (Throwable t) {
            failures.add("threw: " + t);
            t.printStackTrace();
        } finally {
            if (built) {
                teardown(ctx);
            }
            if (failures.isEmpty()) {
                System.out.println("[" + NAME + "] PASS - no floor joins rooms through an opening above the doorway "
                        + "floor, and every door of Mage and Quad Lava is walkable with no stone carved out");
            } else {
                String why = String.join("; ", failures);
                System.out.println("[" + NAME + "] FAIL: " + why);
                SuiteVerdict.failCase(NAME, new AssertionError(why));
            }
        }
    }

    private record Tunnel(int carves, int thin, int behind) {
    }

    /**
     * TUNNEL: {@value #TUNNEL_PLANS} F7s planned from fixed seeds (SimFloorGen's RNG seeded, the recency memory cleared
     * before each, so a jar plans the same floors every run and two jars the same floors up to the fill pass). Every door
     * on an edge a room does not measure as a doorway is the fill pass's carve through that room's wall; this sums the
     * solid capture blocks behind the wall plane that the 3-deep cut reaches (columns 2..3 in from the seam, the
     * opening's three columns, its lower three rows), read from the capture by this scenario, not by the mod.
     */
    private static Tunnel tunnels(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            try {
                ModUnderTest.staticCall("com.killer560.hub.roomsim.SimRecencyStore", "ensureLoaded");
                java.lang.reflect.Field rngField = Class.forName(FLOOR_GEN).getDeclaredField("RNG");
                rngField.setAccessible(true);
                java.util.Random rng = (java.util.Random) rngField.get(null);
                java.lang.reflect.Field recentField = Class.forName("com.killer560.hub.roomsim.SimFloorLayout")
                        .getDeclaredField("RECENT");
                recentField.setAccessible(true);
                Map<?, ?> recent = (Map<?, ?>) recentField.get(null);
                Map<Integer, BlockPos> centres = new HashMap<>();
                for (int cell = 0; cell < GRID * GRID; cell++) {
                    centres.put(cell, (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class},
                            new Object[]{cell}));
                }
                int carves = 0;
                int thin = 0;
                int behind = 0;
                for (int i = 0; i < TUNNEL_PLANS; i++) {
                    recent.clear();
                    rng.setSeed(5600L + i);
                    Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
                    Object p = ModUnderTest.staticCall(FLOOR_GEN, "plan",
                            new Class<?>[]{floor.getClass(), int.class, int.class, Map.class},
                            new Object[]{floor, 2 + (i % 3), 3 + (i % 5), new HashMap<Integer, String>()});
                    if (p == null) {
                        continue;
                    }
                    Object decoded = ModUnderTest.call(p, "decoded", new Class<?>[]{}, new Object[]{});
                    String[] names = (String[]) ModUnderTest.call(decoded, "nameTable", new Class<?>[]{}, new Object[]{});
                    int[] cellRoom = (int[]) ModUnderTest.call(decoded, "cellRoom", new Class<?>[]{}, new Object[]{});
                    int[] cellDoor = (int[]) ModUnderTest.call(decoded, "cellDoor", new Class<?>[]{}, new Object[]{});
                    int[] cellRotation = (int[]) ModUnderTest.call(decoded, "cellRotation", new Class<?>[]{},
                            new Object[]{});
                    Map<String, Set<Integer>> measured = new HashMap<>();
                    for (int cell = 0; cell < GRID * GRID; cell++) {
                        if (cellDoor[cell] == 0) {
                            continue;
                        }
                        int gx = cell % GRID;
                        int gz = cell / GRID;
                        boolean alongX = gx % 2 == 1;
                        int[] side = {alongX ? gz * GRID + gx - 1 : (gz - 1) * GRID + gx,
                                alongX ? gz * GRID + gx + 1 : (gz + 1) * GRID + gx};
                        for (int k = 0; k < 2; k++) {
                            int rc = side[k];
                            int r = cellRoom[rc];
                            if (r < 0 || r == cellRoom[side[1 - k]]) {
                                continue;
                            }
                            Set<Integer> doors = measured.computeIfAbsent(names[r],
                                    n -> measuredDoorCells(names, cellRoom, cellRotation, n));
                            if (doors.contains(cell)) {
                                continue;
                            }
                            Object room = ModUnderTest.staticCall(ROOM_LIBRARY, "get", new Class<?>[]{String.class},
                                    new Object[]{names[r]});
                            int n = behindWall(room, anchorCell(cellRoom, rc), cellRotation[rc], centres,
                                    centres.get(cell), alongX, k == 0 ? -1 : 1);
                            carves++;
                            thin += n <= MAX_SOLID_REMOVED ? 1 : 0;
                            behind += n;
                        }
                    }
                }
                return new Tunnel(carves, thin, behind);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /** Solid capture blocks at d 2..3 (behind the wall plane at d 1) on side {@code s}, w -1..1, capture y 69..71. */
    private static int behindWall(Object room, int anchor, int rotation, Map<Integer, BlockPos> centres, BlockPos c,
                                  boolean alongX, int s) throws ReflectiveOperationException {
        @SuppressWarnings("unchecked")
        List<String> palette = (List<String>) room.getClass().getField("palette").get(room);
        var atM = room.getClass().getMethod("at", int.class, int.class, int.class);
        int sizeX = room.getClass().getField("sizeX").getInt(room);
        int sizeZ = room.getClass().getField("sizeZ").getInt(room);
        int margin = room.getClass().getField("margin").getInt(room);
        BlockPos origin = centres.get(anchor);
        int x0 = origin.getX() - 15 - margin;
        int z0 = origin.getZ() - 15 - margin;
        int solid = 0;
        for (int d = 2; d <= 3; d++) {
            for (int w = -1; w <= 1; w++) {
                for (int y = DOOR_FLOOR; y <= DOOR_FLOOR + 2; y++) {
                    BlockPos p = at(c, alongX, s * d, w, y);
                    int px = p.getX() - x0;
                    int pz = p.getZ() - z0;
                    int x;
                    int z;
                    switch (rotation) {
                        case 90 -> { x = pz; z = sizeZ - 1 - px; }
                        case 180 -> { x = sizeX - 1 - px; z = sizeZ - 1 - pz; }
                        case 270 -> { x = sizeX - 1 - pz; z = px; }
                        default -> { x = px; z = pz; }
                    }
                    short v = (Short) atM.invoke(room, x, y, z);
                    if (v >= 0 && solid(bare(palette.get(v)))) {
                        solid++;
                    }
                }
            }
        }
        return solid;
    }

    /** Null when {@code c}'s edge is on the room's tile grid, else what the grid is. */
    private static String offGrid(Case c) {
        int tilesX = (Integer) ModUnderTest.staticCall(ROOM_LIBRARY, "tilesX", new Class<?>[]{String.class},
                new Object[]{c.room()});
        int tiles = (Integer) ModUnderTest.staticCall(ROOM_LIBRARY, "cellFootprint", new Class<?>[]{String.class},
                new Object[]{c.room()});
        int tilesZ = tilesX == 0 ? 1 : Math.max(1, tiles / tilesX);
        int along = c.side() == 0 || c.side() == 2 ? tilesX : tilesZ;
        return c.index() < along ? null : tilesX + "x" + tilesZ + " tiles";
    }

    private static boolean hasDoorway(Case c) {
        Object mask = ModUnderTest.staticCall(ROOM_DOORS, "of", new Class<?>[]{String.class}, new Object[]{c.room()});
        return mask != null && (Boolean) ModUnderTest.call(mask, "has", new Class<?>[]{int.class, int.class},
                new Object[]{c.side(), c.index()});
    }

    private record Plan(int kept, int linked, int controlLinked, String code) {
    }

    /** {@value #PLANS} F7s with the room pinned; how many used the edge, and the map code to build. */
    private static Plan planMany(ClientGameTestContext ctx, Case c) {
        int kept = 0;
        int linked = 0;
        int controlLinked = 0;
        String firstLinked = null;
        String firstKept = null;
        for (int i = 0; i < PLANS; i++) {
            final int puzzles = 2 + (i % 3);
            final int toBlood = 3 + (i % 5);
            Object[] out = ctx.computeOnClient(mc -> {
                Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
                Map<Integer, String> pinned = new HashMap<>();
                pinned.put(2 * ROOM_GRID + 2, c.room());
                Object p = ModUnderTest.staticCall(FLOOR_GEN, "plan",
                        new Class<?>[]{floor.getClass(), int.class, int.class, Map.class},
                        new Object[]{floor, puzzles, toBlood, pinned});
                if (p == null) {
                    return null;
                }
                Object decoded = ModUnderTest.call(p, "decoded", new Class<?>[]{}, new Object[]{});
                String code = (String) ModUnderTest.call(p, "code", new Class<?>[]{}, new Object[]{});
                return new Object[]{code, usesEdge(decoded, c), usesEdge(decoded, CONTROL)};
            });
            if (out == null) {
                continue;
            }
            int uses = (Integer) out[1];
            if (uses < 0) {
                continue;   // the pin was not kept
            }
            kept++;
            if (firstKept == null) {
                firstKept = (String) out[0];
            }
            if (uses > 0) {
                linked++;
                if (firstLinked == null) {
                    firstLinked = (String) out[0];
                }
            }
            if ((Integer) out[2] > 0) {
                controlLinked++;
            }
        }
        println(c.label() + ": " + PLANS + " F7s planned with " + c.room() + " pinned, " + kept + " kept it, " + linked
                + " joined a room through the edge; " + CONTROL.label() + " joined on " + controlLinked);
        return new Plan(kept, linked, controlLinked, firstLinked != null ? firstLinked : firstKept);
    }

    /**
     * -1 when the room is not on the floor, else 1 when a door cell sits on {@code c}'s edge (turned by the room's
     * rotation, through RoomDoors' own rotate and doorCells), else 0.
     */
    private static int usesEdge(Object decoded, Case c) {
        String[] names = (String[]) ModUnderTest.call(decoded, "nameTable", new Class<?>[]{}, new Object[]{});
        int[] cellRoom = (int[]) ModUnderTest.call(decoded, "cellRoom", new Class<?>[]{}, new Object[]{});
        int[] cellDoor = (int[]) ModUnderTest.call(decoded, "cellDoor", new Class<?>[]{}, new Object[]{});
        int[] cellRotation = (int[]) ModUnderTest.call(decoded, "cellRotation", new Class<?>[]{}, new Object[]{});
        int idx = -1;
        for (int n = 0; n < names.length; n++) {
            if (c.room().equals(names[n])) {
                idx = n;
            }
        }
        if (idx < 0) {
            return -1;
        }
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int anyCell = -1;
        for (int cell = 0; cell < cellRoom.length; cell++) {
            if (cellRoom[cell] == idx && (cell % GRID) % 2 == 0 && (cell / GRID) % 2 == 0) {
                minX = Math.min(minX, cell % GRID);
                minZ = Math.min(minZ, cell / GRID);
                anyCell = cell;
            }
        }
        if (anyCell < 0) {
            return -1;
        }
        if (c.side() < 0) {
            return 0;   // no edge to watch: pinned to be built and checked
        }
        int tilesX = (Integer) ModUnderTest.staticCall(ROOM_LIBRARY, "tilesX", new Class<?>[]{String.class},
                new Object[]{c.room()});
        int tiles = (Integer) ModUnderTest.staticCall(ROOM_LIBRARY, "cellFootprint", new Class<?>[]{String.class},
                new Object[]{c.room()});
        int tilesZ = tilesX == 0 ? 1 : Math.max(1, tiles / tilesX);
        try {
            Class<?> maskClass = Class.forName(ROOM_DOORS + "$Mask");
            int packed = (Integer) ModUnderTest.staticCall(ROOM_DOORS, "edge", new Class<?>[]{int.class, int.class},
                    new Object[]{c.side(), c.index()});
            Object mask = maskClass.getConstructor(int.class, int.class, Set.class)
                    .newInstance(tilesX, tilesZ, Set.of(packed));
            Object turned = ModUnderTest.staticCall(ROOM_DOORS, "rotate", new Class<?>[]{maskClass, int.class},
                    new Object[]{mask, cellRotation[anyCell]});
            @SuppressWarnings("unchecked")
            List<int[]> cells = (List<int[]>) ModUnderTest.staticCall(ROOM_DOORS, "doorCells",
                    new Class<?>[]{maskClass, int.class, int.class}, new Object[]{turned, minX / 2, minZ / 2});
            for (int[] d : cells) {
                int gx = 2 * d[0] + DX[d[2]];
                int gz = 2 * d[1] + DZ[d[2]];
                if (gx >= 0 && gz >= 0 && gx < GRID && gz < GRID && cellDoor[gz * GRID + gx] != 0) {
                    return 1;
                }
            }
            return 0;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The door cells (11-grid index) of every doorway the mod measures for {@code room} on this floor: RoomDoors.of,
     * turned by the room's rotation, through doorCells. A door of the room NOT in this set is the fill pass's carve
     * through a wall (SimFloorLayout's last resort, "X at a,b through Room" in its WARN), which cuts the wall by design.
     */
    private static Set<Integer> measuredDoorCells(String[] names, int[] cellRoom, int[] cellRotation, String room) {
        Set<Integer> out = new java.util.HashSet<>();
        int idx = -1;
        for (int n = 0; n < names.length; n++) {
            if (room.equals(names[n])) {
                idx = n;
            }
        }
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int anyCell = -1;
        for (int cell = 0; cell < cellRoom.length && idx >= 0; cell++) {
            if (cellRoom[cell] == idx && (cell % GRID) % 2 == 0 && (cell / GRID) % 2 == 0) {
                minX = Math.min(minX, cell % GRID);
                minZ = Math.min(minZ, cell / GRID);
                anyCell = cell;
            }
        }
        Object mask = ModUnderTest.staticCall(ROOM_DOORS, "of", new Class<?>[]{String.class}, new Object[]{room});
        if (anyCell < 0 || mask == null) {
            return out;
        }
        try {
            Class<?> maskClass = Class.forName(ROOM_DOORS + "$Mask");
            Object turned = ModUnderTest.staticCall(ROOM_DOORS, "rotate", new Class<?>[]{maskClass, int.class},
                    new Object[]{mask, cellRotation[anyCell]});
            @SuppressWarnings("unchecked")
            List<int[]> cells = (List<int[]>) ModUnderTest.staticCall(ROOM_DOORS, "doorCells",
                    new Class<?>[]{maskClass, int.class, int.class}, new Object[]{turned, minX / 2, minZ / 2});
            for (int[] d : cells) {
                out.add((2 * d[1] + DZ[d[2]]) * GRID + 2 * d[0] + DX[d[2]]);
            }
            return out;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static void build(ClientGameTestContext ctx, String code) {
        long before = Scenario.simBuildCount(ctx);
        long mark = LogTap.mark();
        ctx.runOnClient(mc -> mc.execute(() -> ModUnderTest.staticCall(BUILDER, "build",
                new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, code})));
        ctx.waitFor(mc -> mc.level != null && mc.player != null, 2400);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitFor(mc -> McCompat.screen(mc) == null, 1200);
        for (int i = 0; i < 400 && !logHas(mark, "Sim doors: "); i++) {
            ctx.waitTicks(1);
        }
        ctx.waitTicks(40);
        for (String l : LogTap.since(mark)) {
            if (l.contains("Sim doors: ") && !l.contains("[STDOUT]")) {
                println("log: " + l);
            }
        }
    }

    private record Check(List<String> lines, List<String> failures, int doors) {
    }

    /** Every door of {@code room} on the built floor, read on the server thread from the server's level. */
    private static Check check(ClientGameTestContext ctx, String code, String room) {
        AtomicReference<Object> out = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            Object decoded = ModUnderTest.staticCall("com.killer560.hub.roomsim.MapCode", "decode",
                    new Class<?>[]{String.class}, new Object[]{code});
            String[] names = (String[]) ModUnderTest.call(decoded, "nameTable", new Class<?>[]{}, new Object[]{});
            int[] cellRoom = (int[]) ModUnderTest.call(decoded, "cellRoom", new Class<?>[]{}, new Object[]{});
            int[] cellDoor = (int[]) ModUnderTest.call(decoded, "cellDoor", new Class<?>[]{}, new Object[]{});
            int[] cellRotation = (int[]) ModUnderTest.call(decoded, "cellRotation", new Class<?>[]{}, new Object[]{});
            int dy = (Integer) ModUnderTest.staticCall(ALTITUDE, "offset");
            Set<Integer> measured = measuredDoorCells(names, cellRoom, cellRotation, room);
            Map<String, Object> captures = new HashMap<>();
            for (String n : names) {
                captures.put(n, ModUnderTest.staticCall(ROOM_LIBRARY, "get", new Class<?>[]{String.class}, new Object[]{n}));
            }
            Map<Integer, BlockPos> centres = new HashMap<>();
            for (int cell = 0; cell < GRID * GRID; cell++) {
                centres.put(cell, (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class},
                        new Object[]{cell}));
            }
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                try {
                    out.set(checkOnServer(server.overworld(), room, names, cellRoom, cellDoor, cellRotation, dy, captures,
                            centres, measured));
                } catch (Throwable t) {
                    out.set(t);
                }
            });
        });
        ctx.waitFor(mc -> out.get() != null, 1200);
        if (out.get() instanceof Throwable t) {
            throw new AssertionError("the check threw", t);
        }
        return (Check) out.get();
    }

    private static Check checkOnServer(ServerLevel level, String room, String[] names, int[] cellRoom, int[] cellDoor,
                                       int[] cellRotation, int dy, Map<String, Object> captures,
                                       Map<Integer, BlockPos> centres, Set<Integer> measured) throws Exception {
        List<String> lines = new ArrayList<>();
        List<String> fail = new ArrayList<>();
        int f = DOOR_FLOOR + dy;
        int doors = 0;
        for (int cell = 0; cell < GRID * GRID; cell++) {
            if (cellDoor[cell] == 0) {
                continue;
            }
            int gx = cell % GRID;
            int gz = cell / GRID;
            boolean alongX = gx % 2 == 1;
            int ax = alongX ? gx - 1 : gx;
            int az = alongX ? gz : gz - 1;
            int bx = alongX ? gx + 1 : gx;
            int bz = alongX ? gz : gz + 1;
            int ra = cellRoom[az * GRID + ax];
            int rb = cellRoom[bz * GRID + bx];
            boolean aIs = ra >= 0 && room.equals(names[ra]);
            boolean bIs = rb >= 0 && room.equals(names[rb]);
            if (aIs == bIs) {
                continue;   // not a door of this room (or a door inside it)
            }
            doors++;
            BlockPos c = centres.get(cell);
            String other = names[aIs ? rb : ra];
            boolean doorway = measured.contains(cell);
            String label = "door at cell (" + gx + "," + gz + ") " + room + " <-> " + other
                    + (doorway ? "" : " (fill-pass carve through " + room + "'s wall, not a doorway of it)");
            // 1. The passage: d -3..3 across the seam, w -1..1, three high, floored.
            int blocked = 0;
            int noFloor = 0;
            String firstBlocked = null;
            for (int d = -3; d <= 3; d++) {
                for (int w = -1; w <= 1; w++) {
                    for (int y = f; y <= f + 2; y++) {
                        BlockPos p = at(c, alongX, d, w, y);
                        if (!level.getBlockState(p).getCollisionShape(level, p).isEmpty()) {
                            blocked++;
                            if (firstBlocked == null) {
                                firstBlocked = "d" + d + " w" + w + " y+" + (y - f) + " " + id(level, p);
                            }
                        }
                    }
                    BlockPos under = at(c, alongX, d, w, f - 1);
                    if (level.getBlockState(under).getCollisionShape(level, under).isEmpty()) {
                        noFloor++;
                    }
                }
            }
            // 2. On into each room: from d=3 to d=6 (negative side: room a, positive: room b) along some w.
            boolean intoA = walkOn(level, c, alongX, -1, f);
            boolean intoB = walkOn(level, c, alongX, 1, f);
            boolean intoRoom = aIs ? intoA : intoB;
            boolean intoOther = aIs ? intoB : intoA;
            // 3. Solid capture blocks inside this room that the carve removed: d 1..3 on its side, rows f..f+2.
            int s = aIs ? -1 : 1;
            int roomCell = aIs ? az * GRID + ax : bz * GRID + bx;
            int removed = removedSolid(level, captures.get(room), anchorCell(cellRoom, roomCell), cellRotation[roomCell],
                    centres, c, alongX, s, f, dy);
            // The room's side of the doorway, center column (w 0), d 1..6, rows f-1..f+4: one letter per block.
            StringBuilder prof = new StringBuilder();
            for (int y = f + 4; y >= f - 1; y--) {
                prof.append(" y+").append(y - f).append(':');
                for (int d = 1; d <= 6; d++) {
                    BlockPos p = at(c, alongX, s * d, 0, y);
                    String b = bare(id(level, p));
                    prof.append(b.equals("air") ? '.' : level.getBlockState(p).getCollisionShape(level, p).isEmpty()
                            ? '-' : Character.toUpperCase(b.charAt(0)));
                }
            }
            lines.add(label + ": " + room + " rotation " + cellRotation[roomCell] + ", its side d1..6 at w0 (letter = solid "
                    + "block's first letter, - = passable, . = air):" + prof);
            lines.add(label + ": passage blocked at " + blocked + " of 63" + (firstBlocked == null ? "" : " (first "
                    + firstBlocked + ")") + ", floor missing at " + noFloor + " of 21; walk on into " + room + " "
                    + intoRoom + ", into " + other + " " + intoOther + "; solid capture blocks carved out of " + room
                    + ": " + removed);
            if (blocked > 0 || noFloor > 0) {
                fail.add(label + ": the passage is blocked (" + blocked + ") or unfloored (" + noFloor + ")");
            }
            if (!intoRoom || !intoOther) {
                fail.add(label + ": a dead end - cannot walk on 4..6 blocks into " + (!intoRoom ? room : other));
            }
            if (removed < 0) {
                fail.add(label + ": the capture could not be lined up with the world (" + removed + ")");
            } else if (doorway && removed > MAX_SOLID_REMOVED) {
                fail.add(label + ": the carve cut " + removed + " solid block(s) out of " + room + "'s capture");
            } else if (!doorway) {
                lines.add(label + ": the fill carve cut " + Math.max(0, removed - WALL_PLANE) + " block(s) behind "
                        + room + "'s wall plane (a one-thick wall is " + WALL_PLANE + ")");
            }
        }
        lines.add(room + ": " + doors + " door(s) on the built floor");
        return new Check(lines, fail, doors);
    }

    /** Walkable from the end of the passage (d = 3) on to d = 6 in direction {@code s}: level, one up, or a drop. */
    private static boolean walkOn(ServerLevel level, BlockPos c, boolean alongX, int s, int f) {
        for (int w = -1; w <= 1; w++) {
            int feet = f;
            boolean ok = true;
            for (int d = 4; d <= 6 && ok; d++) {
                ok = false;
                // Level, one block up, or any drop of up to 12 (a room can open onto a lower floor; you can drop).
                for (int k = 1; k >= -12; k--) {
                    int y = feet + (k == 1 ? 0 : k == 0 ? 1 : k);
                    BlockPos p = at(c, alongX, s * d, w, y);
                    // A drop has to fall through open air: everything from the feet's old level down is clear.
                    boolean shaft = true;
                    for (int yy = y + 2; yy <= feet + 1 && shaft; yy++) {
                        shaft = clear(level, at(c, alongX, s * d, w, yy));
                    }
                    if (shaft && !clear(level, p.below()) && clear(level, p) && clear(level, p.above())) {
                        feet = y;
                        ok = true;
                        break;
                    }
                }
            }
            if (ok) {
                return true;
            }
        }
        return false;
    }

    private static boolean clear(ServerLevel level, BlockPos p) {
        return level.getBlockState(p).getCollisionShape(level, p).isEmpty();
    }

    /** The room's top-left even cell (SimBuilder.anchorOf). */
    private static int anchorCell(int[] cellRoom, int cell) {
        int idx = cellRoom[cell];
        int gx = cell % GRID;
        int gz = cell / GRID;
        while (gx - 2 >= 0 && cellRoom[gz * GRID + gx - 2] == idx) {
            gx -= 2;
        }
        while (gz - 2 >= 0 && cellRoom[(gz - 2) * GRID + gx] == idx) {
            gz -= 2;
        }
        return gz * GRID + gx;
    }

    /**
     * Capture blocks with collision, inside the room (d = 1..3 on side {@code s}, w -1..1, y f..f+2), that are air in the
     * world. -1 when the capture does not line up with the world round the doorway (a self-check of the transform:
     * at least 70% of the room's blocks two to six in from the door, y f-1..f+6, must match by id).
     */
    private static int removedSolid(ServerLevel level, Object room, int anchor, int rotation, Map<Integer, BlockPos> centres,
                                    BlockPos c, boolean alongX, int s, int f, int dy) throws Exception {
        @SuppressWarnings("unchecked")
        List<String> palette = (List<String>) room.getClass().getField("palette").get(room);
        var atM = room.getClass().getMethod("at", int.class, int.class, int.class);
        int sizeX = room.getClass().getField("sizeX").getInt(room);
        int sizeZ = room.getClass().getField("sizeZ").getInt(room);
        int margin = room.getClass().getField("margin").getInt(room);
        BlockPos origin = centres.get(anchor);
        int x0 = origin.getX() - 15 - margin;
        int z0 = origin.getZ() - 15 - margin;
        java.util.function.Function<BlockPos, String> captureAt = p -> {
            int px = p.getX() - x0;
            int pz = p.getZ() - z0;
            int x;
            int z;
            switch (rotation) {
                case 90 -> { x = pz; z = sizeZ - 1 - px; }
                case 180 -> { x = sizeX - 1 - px; z = sizeZ - 1 - pz; }
                case 270 -> { x = sizeX - 1 - pz; z = px; }
                default -> { x = px; z = pz; }
            }
            try {
                short v = (Short) atM.invoke(room, x, p.getY() - dy, z);
                return v < 0 ? null : bare(palette.get(v));
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        };
        int compared = 0;
        int same = 0;
        for (int d = 4; d <= 8; d++) {
            for (int w = -4; w <= 4; w++) {
                for (int y = f - 1; y <= f + 6; y++) {
                    BlockPos p = at(c, alongX, s * d, w, y);
                    String want = captureAt.apply(p);
                    if (want == null) {
                        continue;
                    }
                    compared++;
                    if (want.equals(bare(id(level, p))) || (want.equals("air") && bare(id(level, p)).equals("fire"))) {
                        same++;
                    }
                }
            }
        }
        if (compared < 100 || same * 10 < compared * 7) {
            return -1000 - (compared == 0 ? 0 : same * 100 / compared);
        }
        int removed = 0;
        for (int d = 1; d <= 3; d++) {
            for (int w = -1; w <= 1; w++) {
                for (int y = f; y <= f + 2; y++) {
                    BlockPos p = at(c, alongX, s * d, w, y);
                    String want = captureAt.apply(p);
                    if (want == null || !solid(want)) {
                        continue;
                    }
                    if (level.getBlockState(p).isAir()) {
                        removed++;
                    }
                }
            }
        }
        return removed;
    }

    /** A capture block a player cannot walk through (RoomDoors.isOpen's list, inverted). */
    private static boolean solid(String id) {
        return !(id.equals("air") || id.endsWith("carpet") || id.endsWith("torch") || id.endsWith("_button")
                || id.equals("lever") || id.endsWith("_sign") || id.endsWith("rail") || id.equals("vine")
                || id.equals("ladder") || id.equals("short_grass") || id.equals("tall_grass") || id.equals("cobweb")
                || id.endsWith("_pressure_plate") || id.equals("fire") || id.endsWith("banner") || id.equals("water")
                || id.equals("lava") || id.equals("chest") || id.equals("trapped_chest"));
    }

    private static BlockPos at(BlockPos c, boolean alongX, int d, int w, int y) {
        return alongX ? new BlockPos(c.getX() + d, y, c.getZ() + w) : new BlockPos(c.getX() + w, y, c.getZ() + d);
    }

    private static String id(ServerLevel level, BlockPos p) {
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(p).getBlock()).toString();
    }

    private static String bare(String state) {
        String s = state.contains("[") ? state.substring(0, state.indexOf('[')) : state;
        if (s.startsWith("minecraft:")) {
            s = s.substring("minecraft:".length());
        }
        return s.equals("cave_air") || s.equals("void_air") ? "air" : s;
    }

    private static boolean logHas(long mark, String text) {
        for (String l : LogTap.since(mark)) {
            if (l.contains(text)) {
                return true;
            }
        }
        return false;
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
