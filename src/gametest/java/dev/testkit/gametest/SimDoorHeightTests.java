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
 * a neighbour to them there, and the carve, at the doorway floor, cut 3x4x3 into the solid wall below the opening: a
 * tunnel that ends in stone.
 *
 * <p>For each of those rooms, in the capture's own frame (Mage N1, Quad Lava E0):
 * <ol>
 *   <li>PLAN: {@value #PLANS} F7s with the room pinned at room cell (2,2); count floors whose door tree uses that edge
 *       (the edge turned by the room's placed rotation, through the mod's own RoomDoors.rotate/doorCells). Must be 0.
 *       The Entrance's west wall (its back staircase, same kind of opening) is counted on every floor as a control.</li>
 *   <li>BUILD the first floor that used the edge (or, when none did, the first that kept the room) and read the server's
 *       level at every door of the room: the 3x7 passage at the doorway floor is clear three high with floor under it,
 *       you can walk on from it 4..6 blocks into EACH room (solid underfoot, feet and head clear, one block up or any drop), and the
 *       carve removed no more than {@value #MAX_SOLID_REMOVED} solid capture blocks inside the room (wall plane and the
 *       two columns behind it, the opening's lower three rows).</li>
 * </ol>
 * On the main jar of 2026-10-15 the plan uses both edges and the built door into Mage/Quad Lava is a dead end in stone.
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
    private static final int DOOR_FLOOR = 69;
    private static final int[] DX = {0, 1, 0, -1};
    private static final int[] DZ = {-1, 0, 1, 0};

    /** A room and the capture-frame edge (side NESW 0..3, tile index) that is an opening but not a doorway. */
    private record Case(String room, int side, int index, String label) {
    }

    private static final List<Case> CASES = List.of(
            new Case("Mage", 0, 1, "Mage N1 (an Altar tile's interior, y72..87)"),
            new Case("Quad Lava", 1, 0, "Quad Lava E0 (red terracotta panel, y77..80)"));
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
                println(c.label() + ": measured as a doorway by this jar: " + ctx.computeOnClient(mc -> hasDoorway(c)));
                Plan p = planMany(ctx, c);
                if (p.linked() > 0) {
                    failures.add(c.label() + ": the layout joined a room through it on " + p.linked() + " of "
                            + p.kept() + " floors that kept " + c.room());
                }
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
                            centres));
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
                                       Map<Integer, BlockPos> centres) throws Exception {
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
            String label = "door at cell (" + gx + "," + gz + ") " + room + " <-> " + other;
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
            // The room's side of the doorway, centre column (w 0), d 1..6, rows f-1..f+4: one letter per block.
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
            } else if (removed > MAX_SOLID_REMOVED) {
                fail.add(label + ": the carve cut " + removed + " solid block(s) out of " + room + "'s capture");
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
