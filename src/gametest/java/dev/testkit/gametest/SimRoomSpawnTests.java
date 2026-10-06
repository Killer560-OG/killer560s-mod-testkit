package dev.testkit.gametest;

import dev.testkit.compat.McCompat;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 97-sim-roomspawn: where a room puts him, and what the dungeon map draws for it.
 *
 * <p>killer560 (2026-10-06), on the room cycle: "it has two names in the map and the one needs to be centered" (the
 * first room, Altar, an L) and the sidebar said "Room: Entrance" while he stood in Altar; and "try to have me always
 * spawn on the same height as the entrances to the room are as that is the 'main floor' area; some rooms put me in
 * really random spots. You can put me really close to doors [...] This should be used for all /goto as well."
 *
 * <p>Three parts, each measured, none trusting the mod's own idea of where a doorway is:
 * <ul>
 *   <li><b>first load</b>: from the title screen through the All Rooms menu, as he did. The map must hold exactly ONE
 *       labelled group for the room, of the database's tile count, and the sidebar's room must be the room.</li>
 *   <li><b>single rooms</b>: every captured room loaded on its own. For each doorway the mod's mask names, the
 *       doorway's floor is MEASURED in the world (lowest y where all three columns of the opening have something
 *       to stand on and three blocks of nothing above, read on the server thread). He must stand at one of those
 *       floors, within 4 blocks of that doorway, on a solid block with feet and head clear and no fluid.</li>
 *   <li><b>/goto</b>: a generated F7, {@code /goto} every room on it through the real command; same checks.</li>
 * </ul>
 * Rooms whose doorway cannot be measured (Blood, Higher Blaze: no opening cut through the perimeter) are reported and
 * not judged. Also counts, per single room, the labelled map groups (must be 1) and the sidebar room.
 */
public class SimRoomSpawnTests implements FabricClientGameTest {

    private static final String NAME = "97-sim-roomspawn";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String ROOM_INDEX = "com.killer560.hub.roomsim.SimRoomIndex";
    private static final String ROOM_DOORS = "com.killer560.hub.roomsim.RoomDoors";
    private static final String BUILDER = "com.killer560.hub.roomsim.SimBuilder";
    private static final String MENU = "com.killer560.hub.roomsim.SimMenuScreen";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String LIVEMAP = "com.killer560.hub.livemap.LiveMapFeature";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String DB = "com.killer560.hub.roomdatabase.RoomDatabase";
    private static final int GRID = 11;

    /** One judged landing. */
    private record Landing(String room, String verdict, String detail) {
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (SimMapTests.copyRoomsForOthers() < 20) {
            System.out.println("[" + NAME + "] SKIPPED - needs his real rooms and room database");
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);
        String only = System.getProperty("testkit.roomspawnOnly", "");

        List<String> failures = new ArrayList<>();
        long mark = LogTap.mark();
        try {
            if (only.isEmpty() || only.contains("first")) {
                firstLoad(ctx, failures);
            }
            if (only.isEmpty() || only.contains("rooms")) {
                singleRooms(ctx, failures);
            }
            if (only.isEmpty() || only.contains("goto")) {
                int floors = Integer.getInteger("testkit.roomspawnFloors", 1);
                for (int f = 0; f < floors; f++) {
                    gotoFloor(ctx, failures);
                }
            }
            for (String e : LogTap.modErrorsSince(mark)) {
                failures.add("mod ERROR: " + e);
            }
            if (!failures.isEmpty()) {
                throw new AssertionError(failures.size() + " problem(s): "
                        + (failures.size() > 30 ? failures.subList(0, 30) + " ..." : failures));
            }
            System.out.println("[" + NAME + "] PASS");
        } finally {
            teardown(ctx);
        }
    }

    // =========================================================================================== first load

    private static void firstLoad(ClientGameTestContext ctx, List<String> failures) {
        ctx.runOnClient(mc -> mc.execute(() -> {
            try {
                Screen s = (Screen) Class.forName(MENU).getConstructor(Screen.class).newInstance((Object) null);
                McCompat.setScreen(mc, s);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }));
        ctx.waitFor(mc -> McCompat.screen(mc) != null && McCompat.screen(mc).getClass().getName().equals(MENU), 400);
        ctx.waitTicks(3);
        press(ctx, "All Rooms (route practice)", true);
        for (int i = 0; i < 100 && labels(ctx).stream().anyMatch(l -> l.contains("loading")); i++) {
            ctx.waitTicks(2);
        }
        long before = Scenario.simBuildCount(ctx);
        press(ctx, "All Rooms (", false);
        ctx.waitFor(mc -> mc.level != null && mc.player != null, 2400);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitFor(mc -> McCompat.screen(mc) == null, 1200);
        ctx.waitTicks(60);
        String room = soloRoom(ctx);
        String verdict = mapAndSidebar(ctx, room);
        System.out.println("[" + NAME + "] first load from the menu: " + verdict);
        if (!verdict.startsWith("ok")) {
            failures.add("first load " + verdict);
        }
    }

    /** "ok ..." or what is wrong: one labelled map group for {@code room}, of its tile count, and the sidebar room. */
    private static String mapAndSidebar(ClientGameTestContext ctx, String room) {
        return ctx.computeOnClient(mc -> {
            try {
                List<String> named = new ArrayList<>();
                int sameRoom = 0;
                int tiles = -1;
                for (Object g : (List<?>) ModUnderTest.staticCall(LIVEMAP, "groupsView", new Class<?>[]{},
                        new Object[]{})) {
                    Object entry = field(g, "entry");
                    int[] t = (int[]) field(g, "tiles");
                    String n = entry == null ? null : (String) entry.getClass().getField("name").get(entry);
                    named.add(n + "{tiles=" + t.length + ",label=" + field(g, "labelGX") + ","
                            + field(g, "labelGZ") + "}");
                    if (room != null && room.equals(n)) {
                        sameRoom++;
                        tiles = t.length;
                    }
                }
                Object entry = ModUnderTest.staticCall(DB, "lookupByName", new Class<?>[]{String.class},
                        new Object[]{room});
                String shape = entry == null ? null : (String) entry.getClass().getField("shape").get(entry);
                int want = shapeTiles(shape);
                String sidebar = (String) ModUnderTest.staticCall(SIM_STATE, "currentRoomName");
                String what = room + " (" + shape + "): " + sameRoom + " group(s) named for it, tiles " + tiles
                        + " (want " + want + "), sidebar room " + sidebar + "; groups " + named;
                boolean ok = sameRoom == 1 && (want < 0 || tiles == want) && room != null && room.equals(sidebar);
                return (ok ? "ok " : "WRONG ") + what;
            } catch (ReflectiveOperationException e) {
                return "ERROR " + e;
            }
        });
    }

    private static int shapeTiles(String shape) {
        if (shape == null) {
            return -1;
        }
        return switch (shape.trim().toUpperCase(Locale.ROOT)) {
            case "1X1" -> 1;
            case "1X2" -> 2;
            case "1X3" -> 3;
            case "1X4" -> 4;
            case "2X2" -> 4;
            case "L" -> 3;
            default -> -1;
        };
    }

    // =========================================================================================== single rooms

    private static void singleRooms(ClientGameTestContext ctx, List<String> failures) {
        @SuppressWarnings("unchecked")
        List<String> library = ctx.computeOnClient(mc ->
                new ArrayList<>((List<String>) ModUnderTest.staticCall(ROOM_LIBRARY, "names")));
        String subset = System.getProperty("testkit.roomspawnRooms", "");
        List<Landing> landings = new ArrayList<>();
        int mapWrong = 0;
        List<String> mapWrongNames = new ArrayList<>();
        for (String name : library) {
            if (!subset.isEmpty() && !subset.toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            boolean usable = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isUsable",
                    new Class<?>[]{String.class}, new Object[]{name}));
            if (!usable) {
                continue;
            }
            long before = Scenario.simBuildCount(ctx);
            ctx.runOnClient(mc -> mc.execute(() -> ModUnderTest.staticCall(BUILDER, "buildSingleRoom",
                    new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, name})));
            ctx.waitFor(mc -> mc.level != null && mc.player != null, 2400);
            Scenario.awaitSimBuild(ctx, before);
            ctx.waitTicks(15);
            Landing l = judge(ctx, name);
            landings.add(l);
            String map = mapAndSidebar(ctx, name);
            if (!map.startsWith("ok")) {
                mapWrong++;
                mapWrongNames.add(name);
                System.out.println("[" + NAME + "] map " + map);
            }
            System.out.println("[" + NAME + "] room " + name + ": " + l.verdict() + " " + l.detail());
        }
        summarise("single rooms", landings, failures);
        System.out.println("[" + NAME + "] single rooms: map/sidebar wrong for " + mapWrong + " room(s) "
                + mapWrongNames);
        if (mapWrong > 0) {
            failures.add("map/sidebar wrong for " + mapWrong + " single room(s): " + mapWrongNames);
        }
    }

    // =========================================================================================== /goto

    private static void gotoFloor(ClientGameTestContext ctx, List<String> failures) {
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> {
            Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
            ModUnderTest.staticCall(FLOOR_GEN, "generate",
                    new Class<?>[]{Minecraft.class, floor.getClass(), int.class, int.class},
                    new Object[]{mc, floor, 3, 4});
        }));
        ctx.waitFor(mc -> mc.level != null, 2000);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitTicks(40);
        List<String> rooms = ctx.computeOnClient(mc -> {
            List<String> out = new ArrayList<>();
            for (Object p : (List<?>) ModUnderTest.staticCall(ROOM_INDEX, "placed")) {
                String n = (String) ModUnderTest.call(p, "name", new Class<?>[]{}, new Object[]{});
                if (n != null && !out.contains(n)) {
                    out.add(n);
                }
            }
            return out;
        });
        String below = belowRooms(ctx);
        System.out.println("[" + NAME + "] blocks below/above the rooms' own captures on this floor: " + below);
        // A FAILURE since 2026-10-06. The first floor generated after the single-room sweep stood on a previous single
        // room's blocks (y -63..20 under a top-aligned floor), which is how the old trap-landing scan found a "floor"
        // 47 blocks down. Cause: SimBuildQueue.touchedBounds returned only the chunks the last room's SECRETS went into
        // (written after the build had finished), so the wipe cleared those and nothing else; and the clear's y band
        // was the previous offset's, so an older build's blocks under a top-aligned floor were out of reach.
        if (below.startsWith("none in 0 ") || below.startsWith("none (")) {
            failures.add("the leftover-block scan looked at nothing: " + below);
        } else if (!below.startsWith("none")) {
            failures.add("leftover blocks outside the rooms' capture bands on a generated floor: " + below);
        }
        List<Landing> landings = new ArrayList<>();
        for (String name : rooms) {
            ctx.runOnClient(mc -> mc.player.connection.sendCommand("goto " + name));
            ctx.waitTicks(25);
            Landing l = judge(ctx, name);
            landings.add(l);
            System.out.println("[" + NAME + "] goto " + name + ": " + l.verdict() + " " + l.detail());
        }
        if (rooms.size() < 10) {
            failures.add("only " + rooms.size() + " room(s) on the generated floor");
        }
        summarise("/goto", landings, failures);
    }

    /**
     * Anything standing BELOW a placed room's own capture band, in its tiles' centre and wall-centre columns. The
     * 2026-10-06 full check stood him 47 blocks under New Trap on a generated floor (y -56, its doorway at -9) on a
     * block New Trap's capture (y 60..100, solid walls on three sides) cannot have put there; something else - an
     * earlier floor's leftovers is the suspect - was in the column. "none ..." when every column is clean.
     */
    private static String belowRooms(ClientGameTestContext ctx) {
        AtomicReference<String> out = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            int off = (Integer) ModUnderTest.staticCall(LAYOUT, "simYOffset");
            List<int[]> cols = new ArrayList<>();   // {x, z, bandMinY, bandMaxY}
            List<String> names = new ArrayList<>();
            for (Object p : (List<?>) ModUnderTest.staticCall(ROOM_INDEX, "placed")) {
                String n = (String) ModUnderTest.call(p, "name", new Class<?>[]{}, new Object[]{});
                Object room = ModUnderTest.staticCall(ROOM_LIBRARY, "get", new Class<?>[]{String.class},
                        new Object[]{n});
                if (room == null) {
                    continue;
                }
                int minY;
                int maxY;
                try {
                    minY = room.getClass().getField("minY").getInt(room) + off;
                    maxY = room.getClass().getField("maxY").getInt(room) + off;
                } catch (ReflectiveOperationException e) {
                    continue;
                }
                for (int cell : (int[]) ModUnderTest.call(p, "cells", new Class<?>[]{}, new Object[]{})) {
                    BlockPos c = (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter",
                            new Class<?>[]{int.class}, new Object[]{cell});
                    for (int[] d : new int[][]{{0, 0}, {15, 0}, {-15, 0}, {0, 15}, {0, -15}}) {
                        cols.add(new int[]{c.getX() + d[0], c.getZ() + d[1], minY, maxY});
                        names.add(n);
                    }
                }
            }
            if (server == null) {
                out.set("none (no server)");
                return;
            }
            server.execute(() -> {
                ServerLevel level = server.overworld();
                List<String> hits = new ArrayList<>();
                for (int i = 0; i < cols.size(); i++) {
                    int[] col = cols.get(i);
                    int found = 0;
                    int top = Integer.MIN_VALUE;
                    int bottom = Integer.MIN_VALUE;
                    String topId = "";
                    for (int y = level.getMinY(); y <= level.getMaxY(); y++) {
                        if (y >= col[2] && y <= col[3]) {
                            continue;   // the room's own capture band
                        }
                        var st = level.getBlockState(new BlockPos(col[0], y, col[1]));
                        if (!st.isAir()) {
                            found++;
                            top = y;
                            if (bottom == Integer.MIN_VALUE) {
                                bottom = y;
                            }
                            topId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock())
                                    .getPath();
                        }
                    }
                    if (found > 0) {
                        hits.add(names.get(i) + "@" + col[0] + "," + col[1] + " " + found + " block(s) y" + bottom
                                + ".." + top + " top " + topId + " (band y" + col[2] + ".." + col[3] + ")");
                    }
                }
                out.set(hits.isEmpty() ? "none in " + cols.size() + " column(s)"
                        : hits.size() + " column(s): " + (hits.size() > 8 ? hits.subList(0, 8) : hits));
            });
        });
        ctx.waitFor(mc -> out.get() != null, 600);
        return out.get();
    }

    // =========================================================================================== judging

    private static void summarise(String what, List<Landing> landings, List<String> failures) {
        int ok = 0;
        int unjudged = 0;
        List<String> wrong = new ArrayList<>();
        for (Landing l : landings) {
            if (l.verdict().equals("ok")) {
                ok++;
            } else if (l.verdict().equals("unjudged")) {
                unjudged++;
            } else {
                wrong.add(l.room() + " " + l.verdict());
            }
        }
        System.out.println("[" + NAME + "] " + what + ": " + landings.size() + " landing(s), " + ok + " ok, "
                + wrong.size() + " wrong, " + unjudged + " unjudged (no measurable doorway); wrong: " + wrong);
        if (landings.isEmpty()) {
            failures.add(what + ": nothing was measured");
        }
        if (!wrong.isEmpty()) {
            failures.add(what + ": " + wrong.size() + " wrong landing(s): " + wrong);
        }
    }

    /** Measures, on the SERVER thread, the room's doorway floors and where the server has him. */
    private static Landing judge(ClientGameTestContext ctx, String name) {
        AtomicReference<Landing> out = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            UUID uuid = mc.player == null ? null : mc.player.getUUID();
            Object placed = null;
            for (Object p : (List<?>) ModUnderTest.staticCall(ROOM_INDEX, "placed")) {
                if (name.equals(ModUnderTest.call(p, "name", new Class<?>[]{}, new Object[]{}))) {
                    placed = p;
                    break;
                }
            }
            if (server == null || uuid == null || placed == null) {
                out.set(new Landing(name, "unjudged", "(no server, player or placement)"));
                return;
            }
            int gridX = (Integer) ModUnderTest.call(placed, "gridX", new Class<?>[]{}, new Object[]{});
            int gridZ = (Integer) ModUnderTest.call(placed, "gridZ", new Class<?>[]{}, new Object[]{});
            int rot = (Integer) ModUnderTest.call(placed, "pasteRotation", new Class<?>[]{}, new Object[]{});
            Object mask = ModUnderTest.staticCall(ROOM_DOORS, "of", new Class<?>[]{String.class},
                    new Object[]{name});
            List<int[]> doors = new ArrayList<>();
            if (mask != null) {
                Object turned = ModUnderTest.staticCall(ROOM_DOORS, "rotate",
                        new Class<?>[]{mask.getClass(), int.class}, new Object[]{mask, rot});
                @SuppressWarnings("unchecked")
                List<int[]> cells = (List<int[]>) ModUnderTest.staticCall(ROOM_DOORS, "doorCells",
                        new Class<?>[]{mask.getClass(), int.class, int.class}, new Object[]{turned, 0, 0});
                doors.addAll(cells);
            }
            List<BlockPos> centres = new ArrayList<>();
            for (int[] d : doors) {
                int cell = (gridZ + 2 * d[1]) * GRID + gridX + 2 * d[0];
                centres.add((BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class},
                        new Object[]{cell}));
            }
            int off = (Integer) ModUnderTest.staticCall(LAYOUT, "simYOffset");
            server.execute(() -> {
                ServerLevel level = server.overworld();
                ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
                if (sp == null) {
                    out.set(new Landing(name, "unjudged", "(no server player)"));
                    return;
                }
                int[] dx = {0, 1, 0, -1};
                int[] dz = {-1, 0, 1, 0};
                List<int[]> floors = new ArrayList<>();   // {wallX, wallZ, floorY}
                for (int i = 0; i < doors.size(); i++) {
                    int side = doors.get(i)[2];
                    BlockPos c = centres.get(i);
                    int wx = c.getX() + dx[side] * 15;
                    int wz = c.getZ() + dz[side] * 15;
                    Integer f = doorFloor(level, wx, wz, dz[side] != 0, off);
                    if (f != null) {
                        floors.add(new int[]{wx, wz, f});
                    }
                }
                BlockPos feet = sp.blockPosition();
                double px = sp.getX();
                double py = sp.getY();
                double pz = sp.getZ();
                String at = String.format(Locale.ROOT, "at %.1f,%.1f,%.1f", px, py, pz);
                if (floors.isEmpty()) {
                    out.set(new Landing(name, "unjudged", at + ", no measurable doorway (" + doors.size()
                            + " in the mask)"));
                    return;
                }
                // Measured on his box, not on block cells: a slab or stair under him is a floor, not a blocked
                // foot (the first run called every room with a slab floor "unsafe" that way).
                var box = sp.getBoundingBox();
                boolean solidUnder = !level.noCollision(sp, box.move(0.0, -0.0625, 0.0).setMaxY(box.minY));
                boolean feetClear = level.noCollision(sp, box)
                        && level.getBlockState(feet).getFluidState().isEmpty();
                boolean headClear = level.getBlockState(feet.above()).getFluidState().isEmpty();
                StringBuilder fs = new StringBuilder();
                double best = Double.MAX_VALUE;
                int bestFloor = Integer.MIN_VALUE;
                boolean atAFloor = false;
                for (int[] f : floors) {
                    fs.append(f[2]).append(' ');
                    double d = Math.hypot(px - (f[0] + 0.5), pz - (f[1] + 0.5));
                    if (Math.abs(py - f[2]) <= 0.51) {
                        atAFloor = true;
                        if (d < best) {
                            best = d;
                            bestFloor = f[2];
                        }
                    }
                }
                String detail = at + ", doorway floors [" + fs.toString().trim() + "]"
                        + (atAFloor ? String.format(Locale.ROOT, ", %.1f from the doorway at y%d", best, bestFloor)
                        : "") + (solidUnder ? "" : ", NOTHING UNDER") + (feetClear ? "" : ", FEET BLOCKED")
                        + (headClear ? "" : ", HEAD IN FLUID");
                String verdict;
                if (!solidUnder || !feetClear || !headClear) {
                    verdict = "unsafe";
                } else if (!atAFloor) {
                    verdict = "off-floor";
                } else if (best > 4.0) {
                    verdict = "far-from-door";
                } else {
                    verdict = "ok";
                }
                out.set(new Landing(name, verdict, detail));
            });
        });
        ctx.waitFor(mc -> out.get() != null, 400);
        return out.get();
    }

    /**
     * The doorway's floor. A Catacombs doorway is 3 wide and 4 high, its floor in Hypixel's y 64..82 (here shifted by
     * the sim's altitude offset): the lowest base where at least 11 of the 12 window blocks are open or a shut door
     * (coal, red terracotta, the entrance gate), with something solid under the middle column. The first version took
     * the lowest 3x3 gap anywhere in the wall column, and a captured shut door or a roof window then won (Blood at
     * 46 blocks above its floor, roof gaps at y 3 and 23 on the generated floor). Null when the wall has none.
     */
    private static Integer doorFloor(ServerLevel level, int wx, int wz, boolean wallAlongX, int off) {
        for (int base = 64 + off; base <= 82 + off; base++) {
            int hits = 0;
            for (int w = -1; w <= 1; w++) {
                int x = wallAlongX ? wx + w : wx;
                int z = wallAlongX ? wz : wz + w;
                for (int k = 0; k < 4; k++) {
                    BlockPos p = new BlockPos(x, base + k, z);
                    var state = level.getBlockState(p);
                    String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock())
                            .getPath();
                    if (state.getCollisionShape(level, p).isEmpty() || id.equals("coal_block")
                            || id.equals("red_terracotta") || id.equals("infested_chiseled_stone_bricks")) {
                        hits++;
                    }
                }
            }
            BlockPos under = new BlockPos(wx, base - 1, wz);
            if (hits >= 11 && !level.getBlockState(under).getCollisionShape(level, under).isEmpty()) {
                return base;
            }
        }
        return null;
    }

    // =========================================================================================== helpers

    private static Object field(Object o, String name) throws ReflectiveOperationException {
        var f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    private static String soloRoom(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> (String) ModUnderTest.staticCall("com.killer560.hub.roomsim.SimRoomRoutes",
                "currentSoloRoom"));
    }

    private static List<String> labels(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            List<String> out = new ArrayList<>();
            Screen s = McCompat.screen(mc);
            if (s != null) {
                for (var child : s.children()) {
                    if (child instanceof AbstractWidget w) {
                        out.add(net.minecraft.ChatFormatting.stripFormatting(w.getMessage().getString()));
                    }
                }
            }
            return out;
        });
    }

    private static void press(ClientGameTestContext ctx, String label, boolean exact) {
        String result = ctx.computeOnClient(mc -> {
            Screen s = McCompat.screen(mc);
            if (s == null) {
                return "no screen";
            }
            for (var child : s.children()) {
                if (child instanceof AbstractWidget w) {
                    String l = net.minecraft.ChatFormatting.stripFormatting(w.getMessage().getString());
                    if (exact ? l.equals(label) : l.startsWith(label)) {
                        double x = w.getX() + w.getWidth() / 2.0;
                        double y = w.getY() + w.getHeight() / 2.0;
                        s.mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0)), false);
                        s.mouseReleased(new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0)));
                        return "ok";
                    }
                }
            }
            return "no button " + label;
        });
        if (!"ok".equals(result)) {
            throw new AssertionError(result + " on " + labels(ctx));
        }
        ctx.waitTicks(3);
    }

    private static void teardown(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            try {
                ModUnderTest.staticCall(SIM_STATE, "leave");
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
}
