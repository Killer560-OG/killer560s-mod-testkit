package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.harness.SuiteVerdict;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 855-sim-seal: no generated sim floor can be walked out of.
 *
 * <p>killer560 (2026-10-12, Bridges on an F7): "doors will generate with ground beneath you to walk on but not blocking you
 * from going outside of the map." Three generated floors (F7, F5, F3; the generator's RNG is unseeded, so each is a
 * different floor). On each, read back from the integrated server's level: every spot a player can stand on inside a
 * room tile, with a ceiling within 40 blocks above it (so roofs are not starting points), is a start; from there a
 * walk - a step to any side at the same height, up one block (a jump, with head room), or down any drop onto ground - is
 * followed to everything it reaches. Walking into a column that belongs to no room (outside the floor's tiles, or an
 * empty grid cell) at body height is an escape, and so is stepping into a column with nothing under it all the way to
 * the bottom of the world (the one-wide seam between two rooms, which falls out of the map). Zero escapes on every floor, with at least a thousand start spots per
 * floor so an empty world cannot pass.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimSealTests implements FabricClientGameTest {

    private static final String NAME = "855-sim-seal";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(20);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        SimMapTests.copyRoomsForOthers();
        if (Scenario.ensureRoomDatabase(ctx) == 0) {
            Scenario.skipped(NAME, "needs the room database");
            return;
        }
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);
        List<String> failures = new ArrayList<>();
        try {
            for (String floorName : new String[]{"F7", "F5", "F3"}) {
                long before = Scenario.simBuildCount(ctx);
                long mark = LogTap.mark();
                ctx.runOnClient(mc -> mc.execute(() -> {
                    Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", floorName);
                    ModUnderTest.staticCall(FLOOR_GEN, "generate",
                            new Class<?>[]{Minecraft.class, floor.getClass(), int.class, int.class},
                            new Object[]{mc, floor, 3, 4});
                }));
                ctx.waitFor(mc -> mc.level != null && mc.player != null, 2400);
                Scenario.awaitSimBuild(ctx, before);
                // The doorways and the edge walls go in from the build's completion callback.
                for (int i = 0; i < 400 && !logHas(mark, "Sim doors: "); i++) {
                    ctx.waitTicks(1);
                }
                ctx.waitTicks(40);
                Result r = walk(ctx);
                println(String.format(Locale.US, "%s: %d room tile(s), %d start spot(s), %d spot(s) reached, %d escape(s)%s",
                        floorName, r.tiles, r.starts, r.reached, r.escapes.size(),
                        r.escapes.isEmpty() ? "" : " - first: " + String.join("; ", r.escapes.subList(0,
                                Math.min(6, r.escapes.size())))));
                for (String l : LogTap.since(mark)) {
                    if (l.contains("Sim doors: ") && !l.contains("[STDOUT]")) {
                        println(floorName + " log: " + l);
                    }
                }
                if (r.starts < 1000) {
                    failures.add(floorName + ": only " + r.starts + " start spot(s) - the floor was not read");
                } else if (!r.escapes.isEmpty()) {
                    failures.add(floorName + ": " + r.escapes.size() + " way(s) out of the floor, first at "
                            + r.escapes.get(0));
                }
            }
        } catch (Throwable t) {
            failures.add("threw: " + t);
        } finally {
            teardown(ctx);
        }
        if (failures.isEmpty()) {
            System.out.println("[" + NAME + "] PASS - three generated floors, no walkable way out of any room");
        } else {
            String why = String.join("; ", failures);
            System.out.println("[" + NAME + "] FAIL: " + why);
            SuiteVerdict.failCase(NAME, new AssertionError(why));
        }
    }

    private record Result(int tiles, int starts, int reached, List<String> escapes) {
    }

    /** The walk, on the server thread, over the server's own level. */
    private static Result walk(ClientGameTestContext ctx) {
        AtomicReference<Object> out = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            BlockPos c0 = (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class},
                    new Object[]{0});
            int grid = 11;
            // Which tiles hold a room, and the floor's height, read on the client thread (the mod's static state).
            boolean[][] room = new boolean[grid][grid];
            int tiles = 0;
            for (int gz = 0; gz < grid; gz += 2) {
                for (int gx = 0; gx < grid; gx += 2) {
                    Object name = ModUnderTest.staticCall(SIM_STATE, "roomNameAt", new Class<?>[]{int.class, int.class},
                            new Object[]{c0.getX() + gx * 16, c0.getZ() + gz * 16});
                    if (name != null && !name.toString().isBlank()) {
                        room[gx][gz] = true;
                        tiles++;
                    }
                }
            }
            int floorY = ((BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class, int.class},
                    new Object[]{0, 0})).getY();
            int ftiles = tiles;
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                try {
                    out.set(walkOnServer(server.overworld(), c0, room, ftiles, floorY));
                } catch (Throwable t) {
                    out.set(t);
                }
            });
        });
        ctx.waitFor(mc -> out.get() != null, 6000);
        if (out.get() instanceof Throwable t) {
            throw new AssertionError("the walk threw", t);
        }
        return (Result) out.get();
    }

    private static Result walkOnServer(ServerLevel level, BlockPos c0, boolean[][] room, int tiles, int floorY) {
        int yLo = floorY - 30;
        int yHi = floorY + 50;
        Map<Long, Boolean> passCache = new HashMap<>();
        java.util.function.LongPredicate pass = key -> passCache.computeIfAbsent(key, k -> {
            BlockPos p = BlockPos.of(k);
            var st = level.getBlockState(p);
            return st.getCollisionShape(level, p).isEmpty();
        });
        Set<Long> seen = new HashSet<>();
        ArrayDeque<Long> queue = new ArrayDeque<>();
        int starts = 0;
        for (int gz = 0; gz < 11; gz += 2) {
            for (int gx = 0; gx < 11; gx += 2) {
                if (!room[gx][gz]) {
                    continue;
                }
                int cx = c0.getX() + gx * 16;
                int cz = c0.getZ() + gz * 16;
                for (int x = cx - 15; x <= cx + 15; x++) {
                    for (int z = cz - 15; z <= cz + 15; z++) {
                        for (int y = yLo; y <= yHi; y++) {
                            if (standable(pass, x, y, z) && ceiling(pass, x, y, z)) {
                                long k = BlockPos.asLong(x, y, z);
                                if (seen.add(k)) {
                                    queue.add(k);
                                    starts++;
                                }
                            }
                        }
                    }
                }
            }
        }
        List<String> escapes = new ArrayList<>();
        Set<Long> escapeCols = new HashSet<>();
        int[] dx = {1, -1, 0, 0};
        int[] dz = {0, 0, 1, -1};
        int reached = 0;
        while (!queue.isEmpty() && reached < 3_000_000) {
            long k = queue.poll();
            reached++;
            int x = BlockPos.getX(k);
            int y = BlockPos.getY(k);
            int z = BlockPos.getZ(k);
            for (int d = 0; d < 4; d++) {
                int nx = x + dx[d];
                int nz = z + dz[d];
                boolean bodyFits = pass.test(BlockPos.asLong(nx, y, nz)) && pass.test(BlockPos.asLong(nx, y + 1, nz));
                if (bodyFits && !inRoom(room, c0, nx, nz)) {
                    long col = BlockPos.asLong(nx, 0, nz);
                    if (escapeCols.add(col)) {
                        escapes.add(String.format(Locale.US, "(%d, %d, %d) from (%d, %d, %d)", nx, y, nz, x, y, z));
                    }
                    continue;
                }
                long next = Long.MIN_VALUE;
                if (standable(pass, nx, y, nz)) {
                    next = BlockPos.asLong(nx, y, nz);
                } else if (standable(pass, nx, y + 1, nz) && pass.test(BlockPos.asLong(x, y + 2, z))) {
                    next = BlockPos.asLong(nx, y + 1, nz);
                } else if (bodyFits) {
                    boolean ground = false;
                    for (int yy = y - 1; yy >= level.getMinY(); yy--) {
                        if (!pass.test(BlockPos.asLong(nx, yy, nz))) {
                            ground = true;
                            if (standable(pass, nx, yy + 1, nz)) {
                                next = BlockPos.asLong(nx, yy + 1, nz);
                            }
                            break;
                        }
                    }
                    // Nothing under it all the way down: he falls out of the bottom of the map - a way out too.
                    if (!ground && escapeCols.add(BlockPos.asLong(nx, 1, nz))) {
                        escapes.add(String.format(Locale.US, "fall at (%d, %d, %d) from (%d, %d, %d)", nx, y, nz, x, y, z));
                    }
                }
                if (next != Long.MIN_VALUE && seen.add(next)) {
                    queue.add(next);
                }
            }
        }
        return new Result(tiles, starts, reached, escapes);
    }

    /** By tile, as the sim's own SimState.roomNameAt does: a tile reaches 16 either side of its centre. */
    private static boolean inRoom(boolean[][] room, BlockPos c0, int x, int z) {
        int gx = 2 * Math.floorDiv(x - c0.getX() + 16, 32);
        int gz = 2 * Math.floorDiv(z - c0.getZ() + 16, 32);
        return gx >= 0 && gz >= 0 && gx < 11 && gz < 11 && room[gx][gz];
    }

    private static boolean standable(java.util.function.LongPredicate pass, int x, int y, int z) {
        return pass.test(BlockPos.asLong(x, y, z)) && pass.test(BlockPos.asLong(x, y + 1, z))
                && !pass.test(BlockPos.asLong(x, y - 1, z));
    }

    /** Something solid within 40 blocks overhead: inside a room, not on its roof. */
    private static boolean ceiling(java.util.function.LongPredicate pass, int x, int y, int z) {
        for (int yy = y + 2; yy <= y + 40; yy++) {
            if (!pass.test(BlockPos.asLong(x, yy, z))) {
                return true;
            }
        }
        return false;
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
