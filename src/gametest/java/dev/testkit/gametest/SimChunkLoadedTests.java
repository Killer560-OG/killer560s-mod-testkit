package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.harness.SuiteVerdict;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 1491-sim-chunk-loaded: nothing in the mod reads a chunk the client does not have as air (mod chunk-loaded-1015,
 * 2026-10-15).
 *
 * <p>{@code ClientLevel.hasChunk} is hard-wired to {@code true} (javap 26.1.2 and 26.2), so every client scanner gated
 * on it read a chunk the server had told the client to forget as an empty chunk: "no door", "crypt opened", "no
 * chest", "nothing in the way". The mod now asks {@code util/ClientChunks}. This scenario takes each fixed reader, in a
 * sim world (Chambers on its own), and for one chunk at a time: proves the reader sees the thing with the chunk loaded
 * (so the rest is not vacuous), hands the client Hypixel's "forget chunk" packet for it through the real handler
 * (premise checked: the client's chunk source really lacks it, and the player is not standing in it), checks the reader
 * does NOT report air/absence, then hands the server's chunk back as a real chunk packet and checks the reader sees it
 * again:
 * <ol>
 *   <li><b>Wither Doors</b> ({@code witherdoors.WitherDoorScanner}) and <b>Door Helpers</b>
 *       ({@code doorhelpers.DoorScanner}): a shut wither door stays locked while its chunk is gone (it read as air =
 *       opened); and once it is back, opening it on the server does take it off (the reading still works).</li>
 *   <li><b>Secret Waypoints, crypts</b>: a crypt whose chunk is gone keeps its waypoint past the 3 s "opened and nothing
 *       alive" grace (it was marked done for the run), and still has it once the chunk is back.</li>
 *   <li><b>CryptScanner</b>: a 4x4 lid across a chunk border, one half forgotten, is not reported as a 2x4 crypt.</li>
 *   <li><b>Secret Waypoints, levers</b>: a lever whose chunk is gone keeps its waypoint.</li>
 *   <li><b>AutoPuzzleUtil.chestsInRoom</b> (Boulder, the reward chest): a box with a forgotten chunk is "unknown"
 *       ({@code null}), never a list without the chest in it.</li>
 *   <li><b>TeleportUtils.traverseVoxels</b> (the etherwarp ray): a wall in a forgotten chunk is not flown through to
 *       a landing beyond it.</li>
 * </ol>
 * The Chunk Cache and the Live Map's Keep Chunks Loaded are switched off for the run (they keep forgotten chunks
 * readable on purpose).
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimChunkLoadedTests implements FabricClientGameTest {

    private static final String NAME = "1491-sim-chunk-loaded";
    private static final String ROOM = "Chambers";

    private static final String M = "com.killer560.hub.";
    private static final String SW_CFG = M + "secretwaypoints.SecretWaypointsConfig";
    private static final String SW = M + "secretwaypoints.SecretWaypointsFeature";
    private static final String CRYPTS = M + "secretwaypoints.CryptScanner";
    private static final String WITHER_DOORS = M + "witherdoors.WitherDoorScanner";
    private static final String DOOR_HELPERS = M + "doorhelpers.DoorScanner";
    private static final String PUZZLE_UTIL = M + "autopuzzles.AutoPuzzleUtil";
    private static final String PUZZLE_COORDS = M + "puzzlesolvers.PuzzleCoords";
    private static final String TELEPORT = M + "livemap.autoclear.TeleportUtils";
    private static final String LIVE_MAP = M + "livemap.LiveMapFeature";
    private static final String LAYOUT = M + "livemap.DungeonLayout";
    private static final String BUILDER = M + "roomsim.SimBuilder";
    private static final String ROOM_LIBRARY = M + "roomsim.RoomLibrary";
    private static final String SIM_STATE = M + "roomsim.SimState";

    private static final int GRID = 11;
    private static final int START = -185;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(20);
        ctx.runOnClient(mc -> {
            ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled");
            ModUnderTest.turnOff("com.killer560.hub.chunkcache.ChunkCacheConfig", "setEnabled");
            ModUnderTest.turnOff("com.killer560.hub.livemap.LiveMapConfig", "setKeepChunksLoaded");
        });
        SimMapTests.copyRoomsForOthers();
        if (Scenario.ensureRoomDatabase(ctx) == 0) {
            Scenario.skipped(NAME, "needs the room database");
            return;
        }
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitTicks(2);
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);
        boolean have = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isUsable",
                new Class<?>[]{String.class}, new Object[]{ROOM}));
        if (!have) {
            Scenario.skipped(NAME, "needs a usable " + ROOM + " capture");
            return;
        }
        Object sw = ModUnderTest.config(SW_CFG);
        boolean swWas = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.call(sw, "isEnabled",
                new Class<?>[]{}, new Object[]{}));
        int renderBefore = ctx.computeOnClient(mc -> mc.options.renderDistance().get());
        long mark = LogTap.mark();
        List<String> failures = new ArrayList<>();
        List<String> passed = new ArrayList<>();
        try {
            ctx.runOnClient(mc -> {
                mc.options.renderDistance().set(8);
                ModUnderTest.set(sw, "setEnabled", true);
                ModUnderTest.set(sw, "setShowCrypts", true);
                ModUnderTest.set(sw, "setShowPrinces", true);
            });
            load(ctx);
            ctx.waitTicks(60);

            step(failures, passed, "wither doors", () -> doors(ctx, false));
            step(failures, passed, "door helpers", () -> doors(ctx, true));
            step(failures, passed, "crypt waypoint", () -> cryptWaypoint(ctx));
            step(failures, passed, "crypt scanner", () -> cryptScanner(ctx));
            step(failures, passed, "lever waypoint", () -> lever(ctx));
            step(failures, passed, "chestsInRoom", () -> chests(ctx));
            step(failures, passed, "etherwarp ray", () -> ray(ctx));

            for (String e : LogTap.modErrorsSince(mark)) {
                failures.add("mod ERROR: " + e);
            }
        } catch (Throwable t) {
            failures.add("threw: " + t);
            t.printStackTrace();
        } finally {
            ctx.runOnClient(mc -> {
                try {
                    mc.options.renderDistance().set(renderBefore);
                    ModUnderTest.set(sw, "setShowCrypts", false);
                    ModUnderTest.set(sw, "setShowPrinces", false);
                    ModUnderTest.set(sw, "setEnabled", swWas);
                } catch (Throwable ignored) {
                    // cleanup must not replace the verdict
                }
            });
            teardown(ctx);
            say("passed: " + passed);
            if (failures.isEmpty()) {
                System.out.println("[" + NAME + "] PASS - wither doors, door helpers, crypt waypoints, the crypt scanner, "
                        + "lever waypoints, chestsInRoom and the etherwarp ray treat a forgotten chunk as unknown, "
                        + "not air, and read it again once it is back");
            } else {
                String why = String.join("; ", failures);
                System.out.println("[" + NAME + "] FAIL: " + why);
                SuiteVerdict.failCase(NAME, new AssertionError(why));
            }
        }
    }

    private interface Part {
        void run();
    }

    private static void step(List<String> failures, List<String> passed, String what, Part part) {
        try {
            part.run();
            say(what + ": PASS");
            passed.add(what);
        } catch (AssertionError e) {
            say(what + ": FAIL - " + e.getMessage());
            failures.add(what + ": " + e.getMessage());
        }
    }

    // ---- 1. doors -------------------------------------------------------------------------------------------

    /** {@code helpers}: Door Helpers' DoorScanner (bare Hypixel heights); else Wither Doors' scanner (sim-shifted). */
    private static void doors(ClientGameTestContext ctx, boolean helpers) {
        String cls = helpers ? DOOR_HELPERS : WITHER_DOORS;
        int off = helpers ? 0 : ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(LAYOUT, "simYOffset",
                new Class<?>[]{}, new Object[]{}));
        int[] cell = emptyDoorCell(ctx, helpers ? 1 : 0);
        int wx = START + cell[0] * 16;
        int wz = START + cell[1] * 16;
        say((helpers ? "door helpers" : "wither doors") + ": door cell " + cell[0] + "," + cell[1] + " at " + wx + ","
                + wz + ", y offset " + off);
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        for (int x = wx - 1; x <= wx + 1; x++) {
            for (int z = wz - 1; z <= wz + 1; z++) {
                for (int y = 69; y <= 72; y++) {
                    blocks.put(new BlockPos(x, y + off, z), Blocks.COAL_BLOCK.defaultBlockState());
                }
                blocks.put(new BlockPos(x, 73 + off, z), Blocks.STONE.defaultBlockState());
            }
        }
        BlockPos anchor = new BlockPos(wx, 69 + off, wz);
        place(ctx, blocks);
        scanDoors(ctx, cls, true);
        if (!lockedAt(ctx, cls, wx, wz)) {
            throw new AssertionError("premise: the scanner never saw the shut wither door at " + anchor.toShortString()
                    + " with its chunk loaded (" + lockedDoors(ctx, cls) + ")");
        }
        forget(ctx, wx >> 4, wz >> 4);
        scanDoors(ctx, cls, false);
        boolean whileGone = lockedAt(ctx, cls, wx, wz);
        stillGone(ctx, wx >> 4, wz >> 4);
        say("  with its chunk forgotten: locked " + whileGone);
        if (!whileGone) {
            throw new AssertionError("a shut wither door dropped off the locked list once its chunk was forgotten "
                    + "(an unloaded chunk read as air = opened)");
        }
        resend(ctx, wx >> 4, wz >> 4);
        scanDoors(ctx, cls, false);
        if (!lockedAt(ctx, cls, wx, wz)) {
            throw new AssertionError("the door is not locked after its chunk came back");
        }
        // Positive control: a real opening, with the chunk loaded, still takes it off.
        Map<BlockPos, BlockState> open = new LinkedHashMap<>();
        for (BlockPos p : blocks.keySet()) {
            if (blocks.get(p).is(Blocks.COAL_BLOCK)) {
                open.put(p, Blocks.AIR.defaultBlockState());
            }
        }
        place(ctx, open);
        if (lockedAt(ctx, cls, wx, wz)) {
            throw new AssertionError("a door really opened on the server (chunk loaded) still reads locked");
        }
        say("  back: locked; opened on the server: not locked");
    }

    /** {@code fresh}: forget every cell first (a new run); else the scanner's own every-10-ticks rescan, as in play. */
    private static void scanDoors(ClientGameTestContext ctx, String cls, boolean fresh) {
        if (fresh) {
            ctx.runOnClient(mc -> ModUnderTest.staticCall(cls, "reset", new Class<?>[]{}, new Object[]{}));
        }
        // tick() scans on the call its countdown reaches 0 (the first after reset; within 10 otherwise).
        for (int i = 0; i < 11; i++) {
            ctx.runOnClient(mc -> ModUnderTest.staticCall(cls, "tick", new Class<?>[]{Minecraft.class},
                    new Object[]{mc}));
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> lockedDoors(ClientGameTestContext ctx, String cls) {
        return ctx.computeOnClient(mc -> new ArrayList<>((List<Object>) ModUnderTest.staticCall(cls, "lockedDoors",
                new Class<?>[]{Minecraft.class}, new Object[]{mc})));
    }

    private static boolean lockedAt(ClientGameTestContext ctx, String cls, int wx, int wz) {
        for (Object d : lockedDoors(ctx, cls)) {
            int x = (Integer) ModUnderTest.call(d, "x", new Class<?>[]{}, new Object[]{});
            int z = (Integer) ModUnderTest.call(d, "z", new Class<?>[]{}, new Object[]{});
            if (x == wx && z == wz) {
                return true;
            }
        }
        return false;
    }

    /** A door cell (one of gx/gz odd) whose whole column is air on the server, near the player but not in his chunk. */
    private static int[] emptyDoorCell(ClientGameTestContext ctx, int skip) {
        AtomicReference<int[]> out = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            int pcx = mc.player.blockPosition().getX() >> 4;
            int pcz = mc.player.blockPosition().getZ() >> 4;
            server.execute(() -> {
                ServerLevel level = server.overworld();
                List<int[]> found = new ArrayList<>();
                for (int gx = 0; gx < GRID; gx++) {
                    for (int gz = 0; gz < GRID; gz++) {
                        if ((gx % 2 == 0) == (gz % 2 == 0)) {
                            continue;
                        }
                        int wx = START + gx * 16;
                        int wz = START + gz * 16;
                        int dcx = Math.abs((wx >> 4) - pcx);
                        int dcz = Math.abs((wz >> 4) - pcz);
                        if (Math.max(dcx, dcz) < 2 || Math.max(dcx, dcz) > 4) {
                            continue;
                        }
                        boolean empty = true;
                        for (int x = wx - 1; x <= wx + 1 && empty; x++) {
                            for (int z = wz - 1; z <= wz + 1 && empty; z++) {
                                for (int y = level.getMinY(); y <= level.getMaxY(); y++) {
                                    if (!level.getBlockState(new BlockPos(x, y, z)).isAir()) {
                                        empty = false;
                                        break;
                                    }
                                }
                            }
                        }
                        if (empty) {
                            found.add(new int[]{gx, gz});
                        }
                    }
                }
                out.set(found.size() > skip ? found.get(skip) : new int[0]);
            });
        });
        ctx.waitFor(mc -> out.get() != null, 400);
        if (out.get().length == 0) {
            throw new AssertionError("premise: no empty door cell 2-4 chunks from the player");
        }
        return out.get();
    }

    // ---- 2. crypt waypoint ----------------------------------------------------------------------------------

    private static void cryptWaypoint(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SW, "invalidateCache"));
        ctx.waitTicks(30);
        List<BlockPos> crypts = positions(ctx, "CRYPT");
        List<double[]> boxes = boxes(ctx, "CRYPT");
        int[] pc = playerChunk(ctx);
        int pick = -1;
        for (int i = 0; i < boxes.size(); i++) {
            double[] b = boxes.get(i);
            boolean clear = true;
            for (int cx = (int) Math.floor(b[0]) >> 4; cx <= ((int) Math.floor(b[3] - 0.001)) >> 4; cx++) {
                for (int cz = (int) Math.floor(b[2]) >> 4; cz <= ((int) Math.floor(b[5] - 0.001)) >> 4; cz++) {
                    if (cx == pc[0] && cz == pc[1]) {
                        clear = false;
                    }
                }
            }
            if (clear) {
                pick = i;
                break;
            }
        }
        say("crypt waypoints with every chunk loaded: " + crypts);
        if (pick < 0) {
            throw new AssertionError("premise: no crypt waypoint outside the player's chunk (" + crypts + ")");
        }
        BlockPos anchor = crypts.get(pick);
        double[] b = boxes.get(pick);
        List<int[]> chunks = new ArrayList<>();
        for (int cx = (int) Math.floor(b[0]) >> 4; cx <= ((int) Math.floor(b[3] - 0.001)) >> 4; cx++) {
            for (int cz = (int) Math.floor(b[2]) >> 4; cz <= ((int) Math.floor(b[5] - 0.001)) >> 4; cz++) {
                chunks.add(new int[]{cx, cz});
            }
        }
        for (int[] c : chunks) {
            forget(ctx, c[0], c[1]);
        }
        ctx.waitTicks(100); // 5 s: past the 3 s grace a really opened, empty crypt gets
        List<BlockPos> whileGone = positions(ctx, "CRYPT");
        for (int[] c : chunks) {
            stillGone(ctx, c[0], c[1]);
        }
        say("  crypt " + anchor.toShortString() + ", " + chunks.size() + " chunk(s) forgotten; 5 s later: " + whileGone);
        for (int[] c : chunks) {
            resend(ctx, c[0], c[1]);
        }
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SW, "invalidateCache"));
        ctx.waitTicks(40);
        List<BlockPos> back = positions(ctx, "CRYPT");
        say("  chunks back: " + back);
        if (!whileGone.contains(anchor)) {
            throw new AssertionError("the crypt at " + anchor.toShortString() + " lost its waypoint while its chunk was "
                    + "forgotten (read as opened)" + (back.contains(anchor) ? "" : ", and it never came back"));
        }
        if (!back.contains(anchor)) {
            throw new AssertionError("the crypt at " + anchor.toShortString() + " has no waypoint after its chunk came back");
        }
    }

    // ---- 3. crypt scanner, a lid across a chunk border -------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static void cryptScanner(ClientGameTestContext ctx) {
        int[] pc = playerChunk(ctx);
        // The border between chunk pcx+1 and pcx+2: x = ((pcx+2) << 4) - 2 .. + 1, four wide on each axis.
        int border = (pc[0] + 2) << 4;
        int z0 = (pc[1] << 4) + 6;
        int y = ctx.computeOnClient(mc -> mc.level.getMaxY() - 8);
        Map<BlockPos, BlockState> lid = new LinkedHashMap<>();
        for (int x = border - 2; x <= border + 1; x++) {
            for (int z = z0; z < z0 + 4; z++) {
                lid.put(new BlockPos(x, y, z), Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
            }
        }
        place(ctx, lid);
        int[] bounds = {border - 6, z0 - 4, border + 5, z0 + 7};
        List<String> loaded = scanLid(ctx, bounds, y);
        say("lid at x " + (border - 2) + ".." + (border + 1) + ", z " + z0 + ".." + (z0 + 3) + ", y " + y
                + ", all loaded: " + loaded);
        if (!loaded.equals(List.of("CRYPT 16"))) {
            throw new AssertionError("premise: the 4x4 lid was not found whole with its chunks loaded: " + loaded);
        }
        forget(ctx, pc[0] + 2, pc[1]);
        List<String> half = scanLid(ctx, bounds, y);
        stillGone(ctx, pc[0] + 2, pc[1]);
        say("  east half's chunk forgotten: " + half);
        resend(ctx, pc[0] + 2, pc[1]);
        List<String> again = scanLid(ctx, bounds, y);
        say("  chunk back: " + again);
        Map<BlockPos, BlockState> clear = new LinkedHashMap<>();
        for (BlockPos p : lid.keySet()) {
            clear.put(p, Blocks.AIR.defaultBlockState());
        }
        place(ctx, clear);
        if (!half.isEmpty()) {
            throw new AssertionError("half a lid cut off by a forgotten chunk was reported: " + half);
        }
        if (!again.equals(List.of("CRYPT 16"))) {
            throw new AssertionError("the whole lid was not found again once its chunk came back: " + again);
        }
    }

    /** CryptScanner.scan over {@code bounds}, as "TYPE size" for every find at height {@code y}. */
    @SuppressWarnings("unchecked")
    private static List<String> scanLid(ClientGameTestContext ctx, int[] bounds, int y) {
        return ctx.computeOnClient(mc -> {
            List<String> out = new ArrayList<>();
            List<Object> found = (List<Object>) ModUnderTest.staticCall(CRYPTS, "scan",
                    new Class<?>[]{net.minecraft.world.level.Level.class, int[].class}, new Object[]{mc.level, bounds});
            for (Object f : found) {
                int minY = (Integer) ModUnderTest.call(f, "minY", new Class<?>[]{}, new Object[]{});
                if (minY != y) {
                    continue;
                }
                List<?> blocks = (List<?>) ModUnderTest.call(f, "blocks", new Class<?>[]{}, new Object[]{});
                out.add(ModUnderTest.call(f, "type", new Class<?>[]{}, new Object[]{}) + " " + blocks.size());
            }
            return out;
        });
    }

    // ---- 4. lever waypoint ----------------------------------------------------------------------------------

    private static void lever(ClientGameTestContext ctx) {
        BlockPos at = spotInRoom(ctx);
        Map<BlockPos, BlockState> lever = new LinkedHashMap<>();
        lever.put(at.below(), Blocks.STONE.defaultBlockState());
        lever.put(at, Blocks.LEVER.defaultBlockState().setValue(
                net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock.FACE,
                net.minecraft.world.level.block.state.properties.AttachFace.FLOOR));
        Map<BlockPos, BlockState> before = serverStates(ctx, lever.keySet());
        place(ctx, lever);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SW, "invalidateCache"));
        ctx.waitTicks(30);
        List<BlockPos> loaded = positions(ctx, "LEVER");
        say("lever placed at " + at.toShortString() + "; lever waypoints: " + loaded);
        try {
            if (!loaded.contains(at)) {
                throw new AssertionError("premise: Secret Waypoints never showed the lever at " + at.toShortString()
                        + " with its chunk loaded: " + loaded);
            }
            forget(ctx, at.getX() >> 4, at.getZ() >> 4);
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SW, "invalidateCache"));
            ctx.waitTicks(30);
            List<BlockPos> whileGone = positions(ctx, "LEVER");
            stillGone(ctx, at.getX() >> 4, at.getZ() >> 4);
            resend(ctx, at.getX() >> 4, at.getZ() >> 4);
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SW, "invalidateCache"));
            ctx.waitTicks(30);
            List<BlockPos> back = positions(ctx, "LEVER");
            say("  chunk forgotten: " + whileGone + "; back: " + back);
            if (!whileGone.contains(at)) {
                throw new AssertionError("the lever's waypoint went once its chunk was forgotten (read as air)");
            }
            if (!back.contains(at)) {
                throw new AssertionError("the lever's waypoint is missing after its chunk came back");
            }
        } finally {
            place(ctx, before);
        }
    }

    // ---- 5. chestsInRoom ------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static void chests(ClientGameTestContext ctx) {
        BlockPos at = spotInRoom(ctx);
        Map<BlockPos, BlockState> chest = new LinkedHashMap<>();
        chest.put(at, Blocks.CHEST.defaultBlockState());
        Map<BlockPos, BlockState> before = serverStates(ctx, chest.keySet());
        place(ctx, chest);
        try {
            int[] cr = ctx.computeOnClient(mc -> (int[]) ModUnderTest.staticCall(LIVE_MAP, "currentRoomClayAndRotation",
                    new Class<?>[]{}, new Object[]{}));
            if (cr == null) {
                throw new AssertionError("premise: the live map has no clay corner for the room he stands in");
            }
            BlockPos rel = ctx.computeOnClient(mc -> (BlockPos) ModUnderTest.staticCall(PUZZLE_COORDS, "relative",
                    new Class<?>[]{BlockPos.class, int[].class}, new Object[]{at, cr}));
            int[] box = {rel.getX() - 1, rel.getY() - 1, rel.getZ() - 1, rel.getX() + 1, rel.getY() + 1, rel.getZ() + 1};
            List<BlockPos> loaded = chestsIn(ctx, cr, box);
            say("chest placed at " + at.toShortString() + " (relative " + rel.toShortString() + "); chestsInRoom: "
                    + loaded);
            if (loaded == null || !loaded.contains(at)) {
                throw new AssertionError("premise: chestsInRoom did not find the chest with its chunk loaded: " + loaded);
            }
            forget(ctx, at.getX() >> 4, at.getZ() >> 4);
            List<BlockPos> whileGone = chestsIn(ctx, cr, box);
            stillGone(ctx, at.getX() >> 4, at.getZ() >> 4);
            resend(ctx, at.getX() >> 4, at.getZ() >> 4);
            List<BlockPos> back = chestsIn(ctx, cr, box);
            say("  chunk forgotten: " + (whileGone == null ? "null (unknown)" : whileGone) + "; back: " + back);
            if (whileGone != null && !whileGone.contains(at)) {
                throw new AssertionError("with the chest's chunk forgotten chestsInRoom answered " + whileGone
                        + " - a confident 'no chest there' - instead of unknown (null)");
            }
            if (back == null || !back.contains(at)) {
                throw new AssertionError("chestsInRoom did not find the chest after its chunk came back: " + back);
            }
        } finally {
            place(ctx, before);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<BlockPos> chestsIn(ClientGameTestContext ctx, int[] cr, int[] box) {
        return ctx.computeOnClient(mc -> {
            Object r = ModUnderTest.staticCall(PUZZLE_UTIL, "chestsInRoom",
                    new Class<?>[]{net.minecraft.world.level.Level.class, int[].class, int.class, int.class, int.class,
                            int.class, int.class, int.class},
                    new Object[]{mc.level, cr, box[0], box[1], box[2], box[3], box[4], box[5]});
            return r == null ? null : new ArrayList<>((List<BlockPos>) r);
        });
    }

    // ---- 6. the etherwarp ray --------------------------------------------------------------------------------

    private static void ray(ClientGameTestContext ctx) {
        int[] pc = playerChunk(ctx);
        int y = ctx.computeOnClient(mc -> mc.level.getMaxY() - 20);
        int z = (pc[1] << 4) + 8;
        BlockPos wall = new BlockPos(((pc[0] + 2) << 4) + 8, y, z);
        BlockPos beyond = new BlockPos(((pc[0] + 3) << 4) + 8, y, z);
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        blocks.put(wall, Blocks.STONE.defaultBlockState());
        blocks.put(beyond, Blocks.STONE.defaultBlockState());
        place(ctx, blocks);
        double sx = ((pc[0] + 1) << 4) + 4.5;
        double sy = y + 0.5;
        double sz = z + 0.5;
        double ex = beyond.getX() + 8.5;
        try {
            String loaded = rayHit(ctx, sx, sy, sz, ex);
            say("ray from x " + sx + " to " + ex + " at y " + sy + ", z " + sz + "; wall " + wall.toShortString()
                    + ", beyond " + beyond.toShortString() + "; all loaded: " + loaded);
            if (!loaded.equals(wall.toShortString())) {
                throw new AssertionError("premise: with every chunk loaded the ray did not stop at the wall: " + loaded);
            }
            forget(ctx, pc[0] + 2, pc[1]);
            String gone = rayHit(ctx, sx, sy, sz, ex);
            stillGone(ctx, pc[0] + 2, pc[1]);
            resend(ctx, pc[0] + 2, pc[1]);
            String back = rayHit(ctx, sx, sy, sz, ex);
            say("  wall's chunk forgotten: " + gone + "; back: " + back);
            if (gone.equals(beyond.toShortString())) {
                throw new AssertionError("the ray flew through the wall in the forgotten chunk and landed beyond it, at "
                        + gone);
            }
            if (!back.equals(wall.toShortString())) {
                throw new AssertionError("the ray did not stop at the wall after its chunk came back: " + back);
            }
        } finally {
            Map<BlockPos, BlockState> clear = new LinkedHashMap<>();
            clear.put(wall, Blocks.AIR.defaultBlockState());
            clear.put(beyond, Blocks.AIR.defaultBlockState());
            place(ctx, clear);
        }
    }

    /** TeleportUtils.traverseVoxels(level, ..., etherwarp=true) along +x: the landing block, or "none". */
    private static String rayHit(ClientGameTestContext ctx, double sx, double sy, double sz, double ex) {
        return ctx.computeOnClient(mc -> {
            Object r = ModUnderTest.staticCall(TELEPORT, "traverseVoxels",
                    new Class<?>[]{net.minecraft.world.level.Level.class, double.class, double.class, double.class,
                            double.class, double.class, double.class, boolean.class},
                    new Object[]{mc.level, sx, sy, sz, ex, sy, sz, true});
            Object pos = null;
            for (String accessor : new String[]{"pos", "position", "getPos"}) {
                try {
                    pos = ModUnderTest.call(r, accessor, new Class<?>[]{}, new Object[]{});
                    break;
                } catch (Throwable ignored) {
                    // try the next name
                }
            }
            return pos instanceof BlockPos bp ? bp.toShortString() : "none";
        });
    }

    // ---- world plumbing ------------------------------------------------------------------------------------

    /** Hands the client the server's "forget chunk" packet through the real handler; asserts the client lost it. */
    private static void forget(ClientGameTestContext ctx, int cx, int cz) {
        String bad = ctx.computeOnClient(mc -> {
            BlockPos feet = mc.player.blockPosition();
            if ((feet.getX() >> 4) == cx && (feet.getZ() >> 4) == cz) {
                return "it is the chunk the player stands in";
            }
            if (!mc.level.getChunkSource().hasChunk(cx, cz)) {
                return "the client never had it";
            }
            mc.getConnection().handleForgetLevelChunk(new ClientboundForgetLevelChunkPacket(new ChunkPos(cx, cz)));
            return mc.level.getChunkSource().hasChunk(cx, cz) ? "the client still has it after the packet" : null;
        });
        if (bad != null) {
            throw new AssertionError("premise: could not forget chunk " + cx + "," + cz + ": " + bad);
        }
    }

    /** The premise of every "while gone" reading: the client still lacks the chunk (the server did not send it back). */
    private static void stillGone(ClientGameTestContext ctx, int cx, int cz) {
        if (ctx.computeOnClient(mc -> mc.level.getChunkSource().hasChunk(cx, cz))) {
            throw new AssertionError("premise: chunk " + cx + "," + cz + " was back on the client before the reading");
        }
    }

    /** Sends the server's copy of the chunk to the client as a real chunk packet (the server will not resend it). */
    private static void resend(ClientGameTestContext ctx, int cx, int cz) {
        AtomicReference<ClientboundLevelChunkWithLightPacket> packet = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                ServerLevel level = server.overworld();
                packet.set(new ClientboundLevelChunkWithLightPacket(level.getChunk(cx, cz), level.getLightEngine(),
                        null, null));
            });
        });
        ctx.waitFor(mc -> packet.get() != null, 400);
        boolean ok = ctx.computeOnClient(mc -> {
            mc.getConnection().handleLevelChunkWithLight(packet.get());
            return mc.level.getChunkSource().hasChunk(cx, cz);
        });
        if (!ok) {
            throw new AssertionError("could not hand chunk " + cx + "," + cz + " back to the client");
        }
        ctx.waitTicks(2);
    }

    /** Sets blocks on the server and waits until the client sees every one. */
    private static void place(ClientGameTestContext ctx, Map<BlockPos, BlockState> blocks) {
        AtomicReference<Boolean> done = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                ServerLevel level = server.overworld();
                for (var e : blocks.entrySet()) {
                    level.setBlock(e.getKey(), e.getValue(), 2);
                }
                done.set(true);
            });
        });
        ctx.waitFor(mc -> done.get() != null, 400);
        ctx.waitFor(mc -> {
            for (var e : blocks.entrySet()) {
                if (mc.level.getBlockState(e.getKey()) != e.getValue()) {
                    return false;
                }
            }
            return true;
        }, 400);
    }

    private static Map<BlockPos, BlockState> serverStates(ClientGameTestContext ctx, java.util.Collection<BlockPos> at) {
        AtomicReference<Map<BlockPos, BlockState>> out = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                Map<BlockPos, BlockState> m = new LinkedHashMap<>();
                for (BlockPos p : at) {
                    m.put(p, server.overworld().getBlockState(p));
                }
                out.set(m);
            });
        });
        ctx.waitFor(mc -> out.get() != null, 400);
        return out.get();
    }

    private static int[] playerChunk(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> new int[]{mc.player.blockPosition().getX() >> 4,
                mc.player.blockPosition().getZ() >> 4});
    }

    /**
     * An air block with air above it and a free block under it, inside the room he stands in, at his feet's height,
     * in a chunk other than his and not on a chunk edge - so forgetting its chunk leaves him standing.
     */
    private static BlockPos spotInRoom(ClientGameTestContext ctx) {
        int[] bounds = ctx.computeOnClient(mc -> {
            int idx = (Integer) ModUnderTest.staticCall(LIVE_MAP, "currentRoomIndex", new Class<?>[]{}, new Object[]{});
            return idx < 0 ? null : (int[]) ModUnderTest.staticCall(LIVE_MAP, "roomWorldBounds",
                    new Class<?>[]{int.class}, new Object[]{idx});
        });
        if (bounds == null) {
            throw new AssertionError("premise: the live map does not know the room he stands in");
        }
        int[] pc = playerChunk(ctx);
        int feetY = ctx.computeOnClient(mc -> mc.player.blockPosition().getY());
        AtomicReference<BlockPos> out = new AtomicReference<>();
        AtomicReference<Boolean> done = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                ServerLevel level = server.overworld();
                search:
                for (int x = bounds[0] + 2; x <= bounds[2] - 2; x++) {
                    for (int z = bounds[1] + 2; z <= bounds[3] - 2; z++) {
                        if (((x >> 4) == pc[0] && (z >> 4) == pc[1]) || (x & 15) < 2 || (x & 15) > 13
                                || (z & 15) < 2 || (z & 15) > 13) {
                            continue;
                        }
                        for (int y = feetY; y <= feetY + 3; y++) {
                            BlockPos p = new BlockPos(x, y, z);
                            if (level.getBlockState(p).isAir() && level.getBlockState(p.above()).isAir()
                                    && level.getBlockState(p.below()).isAir()
                                    && level.getBlockState(p.below(2)).isSolidRender()) {
                                out.set(p);
                                break search;
                            }
                        }
                    }
                }
                done.set(true);
            });
        });
        ctx.waitFor(mc -> done.get() != null, 400);
        if (out.get() == null) {
            throw new AssertionError("premise: no free spot in the room outside the player's chunk");
        }
        return out.get();
    }

    @SuppressWarnings("unchecked")
    private static List<BlockPos> positions(ClientGameTestContext ctx, String kind) {
        return ctx.computeOnClient(mc -> new ArrayList<>((List<BlockPos>) ModUnderTest.staticCall(SW,
                "cachedPositions", new Class<?>[]{String.class}, new Object[]{kind})));
    }

    @SuppressWarnings("unchecked")
    private static List<double[]> boxes(ClientGameTestContext ctx, String kind) {
        return ctx.computeOnClient(mc -> new ArrayList<>((List<double[]>) ModUnderTest.staticCall(SW,
                "cachedBoxes", new Class<?>[]{String.class}, new Object[]{kind})));
    }

    private static void load(ClientGameTestContext ctx) {
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> ModUnderTest.staticCall(BUILDER, "buildSingleRoom",
                new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, ROOM})));
        ctx.waitFor(mc -> mc.level != null && mc.player != null, 2400);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitFor(mc -> McCompat.screen(mc) == null, 1200);
        ctx.waitFor(mc -> mc.player != null && mc.player.onGround()
                && !mc.level.getBlockState(mc.player.blockPosition().below()).isAir(), 1800);
        ctx.waitTicks(40);
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

    private static void say(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}
