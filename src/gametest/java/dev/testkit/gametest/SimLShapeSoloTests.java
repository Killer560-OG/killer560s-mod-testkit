package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 97-sim-lshape: an L room loaded on its own is built on the cells the map draws, and he lands inside it, out of lava.
 *
 * <p>killer560 (2026-10-13): "lava ravine as a solo room tends to generate outside where it thinks the room should be
 * rotated for the kind of 3 piece l shape. See how it thinks I am outside the room." and "also make it not spawn me in
 * the lava." His map drew the L a quarter turn off the room that was built, his marker sat in the quarter the map left
 * out, and the sidebar said "Room: Entrance".
 *
 * <p>Every L room in the library (room database shape "L") is loaded through {@code SimBuilder.buildSingleRoom}, and
 * for each, read on the server and from the mod's own lookups:
 * <ul>
 *   <li><b>inside</b>: {@code SimScore.roomAt} and {@code SimState.roomNameAt} (the sidebar) at his position name the
 *       room, and his tile cell is one of the live map's tiles for it;</li>
 *   <li><b>one answer</b>: the map's tiles, {@code SimRoomIndex}'s cells for the room and the sidebar's map code agree,
 *       and there are three of them;</li>
 *   <li><b>built where drawn</b>: the quarter of the 2x2 box the map leaves out holds no block (a 5x5 of columns at its
 *       center, whole world height), and every tile the map draws does;</li>
 *   <li><b>no lava</b>: a solid block under him, his feet and head clear of blocks and fluid, and no lava, fire,
 *       magma (or the other hazard blocks below) at the head, feet, the block stood on or the two under it.</li>
 * </ul>
 * Lava Ravine is loaded twice: first with the instance's own capture (Mod Only Test holds one with Entrance in its
 * fourth quarter - his), then with that file removed so the jar's bundled capture is the one built.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimLShapeSoloTests implements FabricClientGameTest {

    private static final String NAME = "97-sim-lshape";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String SIM_SCORE = "com.killer560.hub.roomsim.SimScore";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String ROOM_INDEX = "com.killer560.hub.roomsim.SimRoomIndex";
    private static final String BUILDER = "com.killer560.hub.roomsim.SimBuilder";
    private static final String MAP_CODE = "com.killer560.hub.roomsim.MapCode";
    private static final String LIVEMAP = "com.killer560.hub.livemap.LiveMapFeature";
    private static final String DB = "com.killer560.hub.roomdatabase.RoomDatabase";
    private static final String RAVINE = "Lava Ravine";
    private static final int GRID = 11;
    private static final int START = -185;
    private static final Set<String> HAZARDS = Set.of("lava", "fire", "soul_fire", "magma_block", "cactus",
            "powder_snow", "campfire", "soul_campfire", "sweet_berry_bush", "wither_rose", "pointed_dripstone");

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
            Scenario.skipped(NAME, "needs real room captures and room database");
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        reloadLibrary(ctx);

        List<String> failures = new ArrayList<>();
        long mark = LogTap.mark();
        int judged = 0;
        int ravineJudged = 0;
        try {
            // 1. Lava Ravine as the instance has it.
            String source = ravineSource(ctx);
            System.out.println("[" + NAME + "] Lava Ravine capture loaded first: " + source);
            if (source != null) {
                judged++;
                ravineJudged++;
                check(ctx, RAVINE, "instance capture (" + source + ")", failures);
            }
            // 2. The bundled capture: the instance's file out of the way, the library reloaded.
            Path local = ModUnderTest.modConfig("killer560smod-rooms").resolve("Lava_Ravine.json");
            try {
                Files.deleteIfExists(local);
            } catch (java.io.IOException e) {
                failures.add("could not remove " + local + ": " + e);
            }
            reloadLibrary(ctx);
            String bundled = ravineSource(ctx);
            System.out.println("[" + NAME + "] Lava Ravine capture after removing the instance's file: " + bundled);
            if (!"jar".equals(bundled)) {
                failures.add("the bundled Lava Ravine is not the one loaded after removing the instance's file: "
                        + bundled);
            }
            if (bundled != null) {
                judged++;
                ravineJudged++;
                check(ctx, RAVINE, "bundled capture", failures);
            }
            // 3. Every other L room.
            for (String name : lRooms(ctx)) {
                if (name.equals(RAVINE)) {
                    continue;
                }
                judged++;
                check(ctx, name, "library capture", failures);
            }
            System.out.println("[" + NAME + "] judged " + judged + " single-room load(s) of L rooms, " + ravineJudged
                    + " of them Lava Ravine");
            if (ravineJudged < 1) {
                failures.add("Lava Ravine was never loaded");
            }
            if (judged < 8) {
                failures.add("only " + judged + " L room load(s) judged - expected the eleven L rooms");
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

    private static void reloadLibrary(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitTicks(2);
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);
    }

    /** "jar" or "disk" for the Lava Ravine the library holds, or null when it holds none. */
    private static String ravineSource(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            Object room = ModUnderTest.staticCall(ROOM_LIBRARY, "get", new Class<?>[]{String.class},
                    new Object[]{RAVINE});
            if (room == null) {
                return null;
            }
            try {
                var f = room.getClass().getDeclaredField("fromJar");
                f.setAccessible(true);
                return f.getBoolean(room) ? "jar" : "disk";
            } catch (ReflectiveOperationException e) {
                return "unknown";
            }
        });
    }

    /** Every captured room whose database shape is "L". */
    private static List<String> lRooms(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            List<String> out = new ArrayList<>();
            @SuppressWarnings("unchecked")
            List<String> names = (List<String>) ModUnderTest.staticCall(ROOM_LIBRARY, "names");
            for (String n : names) {
                Object entry = ModUnderTest.staticCall(DB, "lookupByName", new Class<?>[]{String.class},
                        new Object[]{n});
                try {
                    String shape = entry == null ? null : (String) entry.getClass().getField("shape").get(entry);
                    if (shape != null && "L".equalsIgnoreCase(shape.trim())) {
                        out.add(n);
                    }
                } catch (ReflectiveOperationException ignored) {
                    // not an L as far as this can tell
                }
            }
            return out;
        });
    }

    /** Loads {@code name} on its own and judges where he is and what was built. */
    private static void check(ClientGameTestContext ctx, String name, String what, List<String> failures) {
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> ModUnderTest.staticCall(BUILDER, "buildSingleRoom",
                new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, name})));
        ctx.waitFor(mc -> mc.level != null && mc.player != null, 2400);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitTicks(20);

        // What the map, the index and the sidebar's map code say, read on the client.
        AtomicReference<String> problem = new AtomicReference<>();
        Set<Integer> mapTiles = new TreeSet<>();
        Set<Integer> indexCells = new TreeSet<>();
        Set<Integer> codeTiles = new TreeSet<>();
        int[] anchor = new int[2];
        ctx.runOnClient(mc -> {
            try {
                for (Object g : (List<?>) ModUnderTest.staticCall(LIVEMAP, "groupsView", new Class<?>[]{},
                        new Object[]{})) {
                    Object entry = field(g, "entry");
                    String n = entry == null ? null : (String) entry.getClass().getField("name").get(entry);
                    if (name.equals(n)) {
                        for (int t : (int[]) field(g, "tiles")) {
                            mapTiles.add(t);
                        }
                    }
                }
                for (Object p : (List<?>) ModUnderTest.staticCall(ROOM_INDEX, "placed")) {
                    if (name.equals(ModUnderTest.call(p, "name", new Class<?>[]{}, new Object[]{}))) {
                        for (int c : (int[]) ModUnderTest.call(p, "cells", new Class<?>[]{}, new Object[]{})) {
                            indexCells.add(c);
                        }
                        anchor[0] = (Integer) ModUnderTest.call(p, "gridX", new Class<?>[]{}, new Object[]{});
                        anchor[1] = (Integer) ModUnderTest.call(p, "gridZ", new Class<?>[]{}, new Object[]{});
                    }
                }
                String code = (String) ModUnderTest.staticCall(SIM_STATE, "mapCode");
                Object decoded = ModUnderTest.staticCall(MAP_CODE, "decode", new Class<?>[]{String.class},
                        new Object[]{code});
                int[] cellRoom = (int[]) ModUnderTest.call(decoded, "cellRoom", new Class<?>[]{}, new Object[]{});
                for (int c = 0; c < cellRoom.length; c++) {
                    if (cellRoom[c] >= 0 && (c % GRID) % 2 == 0 && (c / GRID) % 2 == 0) {
                        codeTiles.add(c);
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                problem.set("could not read the map: " + e);
            }
        });
        String label = name + " [" + what + "]";
        if (problem.get() != null) {
            failures.add(label + ": " + problem.get());
            return;
        }
        // The 2x2 box's tiles that the map leaves out.
        List<Integer> box = new ArrayList<>();
        for (int a = 0; a < 2; a++) {
            for (int b = 0; b < 2; b++) {
                box.add((anchor[1] + 2 * b) * GRID + anchor[0] + 2 * a);
            }
        }
        List<Integer> outside = new ArrayList<>();
        for (int c : box) {
            if (!mapTiles.contains(c)) {
                outside.add(c);
            }
        }

        // His position and the world, read on the server.
        AtomicReference<String[]> world = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            UUID uuid = mc.player == null ? null : mc.player.getUUID();
            if (server == null || uuid == null) {
                world.set(new String[]{"no server or player"});
                return;
            }
            server.execute(() -> {
                ServerLevel level = server.overworld();
                ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
                if (sp == null) {
                    world.set(new String[]{"no server player"});
                    return;
                }
                BlockPos feet = sp.blockPosition();
                String scoreRoom = (String) ModUnderTest.staticCall(SIM_SCORE, "roomAt",
                        new Class<?>[]{BlockPos.class}, new Object[]{feet});
                String sidebarRoom = (String) ModUnderTest.staticCall(SIM_STATE, "roomNameAt",
                        new Class<?>[]{int.class, int.class}, new Object[]{feet.getX(), feet.getZ()});
                int gx = 2 * Math.floorDiv(feet.getX() - START + 16, 32);
                int gz = 2 * Math.floorDiv(feet.getZ() - START + 16, 32);
                // Safety, on his box for the floor (a slab is a floor) and on blocks for the hazards.
                var bb = sp.getBoundingBox();
                boolean solidUnder = !level.noCollision(sp, bb.move(0.0, -0.0625, 0.0).setMaxY(bb.minY));
                boolean clear = level.noCollision(sp, bb);
                List<String> hazards = new ArrayList<>();
                for (int dy = 1; dy >= -3; dy--) {
                    BlockPos p = feet.offset(0, dy, 0);
                    var st = level.getBlockState(p);
                    String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
                    // Any fluid at the feet or head; lava, fire, magma ... (by id) anywhere down to three below.
                    if ((dy >= 0 && !st.getFluidState().isEmpty()) || HAZARDS.contains(id)) {
                        hazards.add(id + "@dy" + dy);
                    }
                }
                // Blocks standing in each box tile: a 5x5 of columns at the tile center, the whole world height.
                StringBuilder tiles = new StringBuilder();
                for (int c : box) {
                    int cx = START + (c % GRID) * 16;
                    int cz = START + (c / GRID) * 16;
                    int found = 0;
                    for (int dx = -2; dx <= 2; dx++) {
                        for (int dz = -2; dz <= 2; dz++) {
                            for (int y = level.getMinY(); y <= level.getMaxY(); y++) {
                                if (!level.getBlockState(new BlockPos(cx + dx, y, cz + dz)).isAir()) {
                                    found++;
                                }
                            }
                        }
                    }
                    tiles.append(c).append('=').append(found).append(' ');
                }
                world.set(new String[]{null, String.format(Locale.ROOT, "%.1f,%.1f,%.1f", sp.getX(), sp.getY(),
                        sp.getZ()), scoreRoom, sidebarRoom, String.valueOf(gz * GRID + gx), String.valueOf(solidUnder),
                        String.valueOf(clear), String.join(",", hazards), tiles.toString().trim()});
            });
        });
        ctx.waitFor(mc -> world.get() != null, 600);
        String[] w = world.get();
        if (w[0] != null) {
            failures.add(label + ": " + w[0]);
            return;
        }
        int myCell = Integer.parseInt(w[4]);
        java.util.Map<Integer, Integer> blocksIn = new java.util.HashMap<>();
        for (String part : w[8].split(" ")) {
            String[] kv = part.split("=");
            blocksIn.put(Integer.parseInt(kv[0]), Integer.parseInt(kv[1]));
        }
        List<String> wrong = new ArrayList<>();
        if (!name.equals(w[2])) {
            wrong.add("SimScore.roomAt says " + w[2]);
        }
        if (!name.equals(w[3])) {
            wrong.add("sidebar room " + w[3]);
        }
        if (!mapTiles.contains(myCell)) {
            wrong.add("his tile cell " + myCell + " is not one of the map's " + mapTiles);
        }
        if (mapTiles.size() != 3) {
            wrong.add("the map draws " + mapTiles.size() + " tile(s), not an L's three: " + mapTiles);
        }
        if (!mapTiles.equals(indexCells)) {
            wrong.add("map tiles " + mapTiles + " but SimRoomIndex cells " + indexCells);
        }
        if (!mapTiles.equals(codeTiles)) {
            wrong.add("map tiles " + mapTiles + " but the map code's tiles " + codeTiles);
        }
        for (int c : outside) {
            if (blocksIn.getOrDefault(c, 0) > 0) {
                wrong.add("the left-out quarter (cell " + c + ") holds " + blocksIn.get(c) + " block(s)");
            }
        }
        for (int c : mapTiles) {
            if (blocksIn.getOrDefault(c, 0) == 0) {
                wrong.add("the map's tile " + c + " has nothing built in it");
            }
        }
        if (!"true".equals(w[5])) {
            wrong.add("nothing solid under him");
        }
        if (!"true".equals(w[6])) {
            wrong.add("feet or head blocked");
        }
        if (!w[7].isEmpty()) {
            wrong.add("hazard at/under him: " + w[7]);
        }
        String line = label + ": at " + w[1] + ", cell " + myCell + ", map tiles " + mapTiles + ", index " + indexCells
                + ", left out " + outside + ", blocks per box tile {" + w[8] + "}, roomAt " + w[2] + ", sidebar "
                + w[3] + ", hazards [" + w[7] + "]";
        System.out.println("[" + NAME + "] " + (wrong.isEmpty() ? "ok " : "WRONG ") + line
                + (wrong.isEmpty() ? "" : " -> " + wrong));
        if (!wrong.isEmpty()) {
            failures.add(label + ": " + wrong);
        }
    }

    private static Object field(Object o, String name) throws ReflectiveOperationException {
        var f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
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
                dev.testkit.compat.McCompat.setScreen(mc, new net.minecraft.client.gui.screens.TitleScreen())));
        ctx.waitFor(mc -> dev.testkit.compat.McCompat.screen(mc)
                instanceof net.minecraft.client.gui.screens.TitleScreen, 400);
    }
}
