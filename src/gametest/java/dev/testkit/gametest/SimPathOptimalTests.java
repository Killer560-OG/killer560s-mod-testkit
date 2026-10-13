package dev.testkit.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * The fewest-warps planner and the bottom-slab rule (mod branch path-optimal, 2026-10-11). killer560 (2026-10-09): "I
 * will line up where it is only 1 etherwarp and it will take 2 [...] Make sure that it is 100% perfect as to the most
 * optimal route" and "never teleport onto a half slab on the bottom of a block [...] if I place a node specifically on a
 * bottom half slab then it can go there". Every answer is judged against a ground truth computed here on the SERVER's
 * level: real etherwarps ({@code TeleportUtils.traverseVoxels}, the sim server's own rule) cast at 32 x 32 points on every
 * face of the block that faces the eye, each kept only when it still lands with the yaw or pitch 0.01 degrees off.
 *
 * <ul>
 *   <li><b>631-sim-pathopt-lip</b>: a goal block whose top is seen from a pillar only along its far edge (one stone block
 *       on the floor right in front of it). The ground truth: one warp lands on it. The floor graph alone takes 2 (its
 *       line says so); the planner must save ONE warp for an Auto Routes path pair, and flying it must land there.</li>
 *   <li><b>632-sim-pathopt-slab</b>: a wall with a one-block window; through it the only thing in sight is a bottom
 *       slab, from which the goal (inside a ring wall) is in sight: 2 warps over the slab (ground truth), and none of 2 or
 *       fewer without it (ground truth). The planner must take the 3-warp way round through the far doorway, never
 *       landing on the slab, and the flown path pair must arrive without him ever standing on it.</li>
 *   <li><b>633-sim-pathopt-slabnode</b>: the same arena, the second path node placed ON the slab: one warp, and he ends
 *       on the slab.</li>
 *   <li><b>634-sim-pathopt-sweep</b>: real rooms (Slabs, Three Floors, Museum, Scaffolding - the ones the library has):
 *       random path-node pairs, each planned as a path pair is; every plan replayed on the server (each warp lands where
 *       the next starts, the last on the goal, no landing on the way a bottom slab or other blacklisted block), every
 *       2-warp answer checked to have no 1-warp route, and some 3-warp answers no route of 2 or fewer. Prints the floor
 *       graph's warps and time beside the exact planner's.</li>
 *   <li><b>635-sim-pathopt-replan</b>: {@code /ar replan} throws away every saved path plan (in two rooms' routes) and
 *       plans this room's again at once.</li>
 *   <li><b>941-sim-pathopt-altar</b>: his Altar route, pair #9 -> #10 from the breaker hole down to the room's lower
 *       level (25 below the dungeon floor): planned and flown, he ends on #10.</li>
 *   <li><b>636-sim-pathopt-mapslab</b>: the Interactive Map's own trip (ClearExecutor.etherPathExact) to the 632 goal:
 *       arrives, never on the slab.</li>
 * </ul>
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimPathOptimalTests implements FabricClientGameTest {

    static final String LIP = "631-sim-pathopt-lip";
    static final String SLAB = "632-sim-pathopt-slab";
    static final String SLAB_NODE = "633-sim-pathopt-slabnode";
    static final String SWEEP = "634-sim-pathopt-sweep";
    static final String REPLAN = "635-sim-pathopt-replan";
    static final String MAP_SLAB = "636-sim-pathopt-mapslab";
    static final String BREAKER = "637-sim-pathopt-breaker";
    static final String RW = "871-sim-pathopt-dbwarrior";
    static final String REFIRE = "873-sim-pathopt-dbrefire";
    static final String CHEST = "638-sim-pathopt-chest";
    // ar-path-altar (2026-10-12): his Altar pair #9 -> #10, down into the room's lower level.
    static final String ALTAR = "941-sim-pathopt-altar";
    // The map trip (636) goes after every path-pair flight: after a map warp only a START node may arm until he has
    // been through one (the map-arrival interlock), and these pairs have none.
    private static final String[] CASES = {LIP, SLAB, SLAB_NODE, CHEST, BREAKER, RW, REFIRE,
            ALTAR,
            MAP_SLAB, REPLAN, SWEEP};

    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String BUILDER = "com.killer560.hub.roomsim.SimBuilder";
    private static final String MAP_CODE = "com.killer560.hub.roomsim.MapCode";
    private static final String SIM_ITEMS = "com.killer560.hub.roomsim.SimItems";
    private static final String STORE = "com.killer560.hub.autoroutes.RouteStore";
    private static final String COORDS = "com.killer560.hub.autoroutes.RouteCoords";
    private static final String FRAME = "com.killer560.hub.autoroutes.RouteCoords$Frame";
    private static final String FEATURE = "com.killer560.hub.autoroutes.AutoRoutesFeature";
    private static final String EXECUTOR = "com.killer560.hub.autoroutes.RouteExecutor";
    private static final String PLANNER = "com.killer560.hub.autoroutes.RoutePathPlanner";
    private static final String AR_CONFIG = "com.killer560.hub.autoroutes.AutoRoutesConfig";
    private static final String CLEAR = "com.killer560.hub.livemap.autoclear.ClearExecutor";
    private static final String PATHFINDER = "com.killer560.hub.livemap.autoclear.EtherwarpPathfinder";
    private static final String TELEPORT_UTILS = "com.killer560.hub.livemap.autoclear.TeleportUtils";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String OVERLAY = "com.killer560.hub.etherwarpoverlay.EtherwarpOverlayFeature";
    private static final String DX_CONFIG = "com.killer560.hub.dungeonextras.DungeonExtrasConfig";

    /** The room the arena cases are built in (a 1x1 room, as 96-ar picks), and its relative floor height. */
    private static String room;
    private static int F;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        List<String> selected = new ArrayList<>();
        for (String c : CASES) {
            if (!Scenario.skip(c)) {
                selected.add(c);
            }
        }
        if (selected.isEmpty()) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (!Mod.isCheat()) {
            for (String c : selected) {
                Scenario.skipped(c, "Auto Routes path nodes and the Interactive Map are cheat-build features");
            }
            return;
        }
        if (SimMapTests.copyRoomsForOthers() < 20) {
            for (String c : selected) {
                Scenario.skipped(c, "needs real room captures and room database");
            }
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);
        ctx.runOnClient(mc -> {
            Object ar = ModUnderTest.config(AR_CONFIG);
            ModUnderTest.set(ar, "setEnabled", true);
            ModUnderTest.set(ar, "setLegitMode", false);
            ModUnderTest.set(ar, "setStartFromStartNodeOnly", false);
            ModUnderTest.set(ar, "setChatFeedback", true);
            ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autopuzzles.AutoPuzzlesConfig"),
                    "setAutoPuzzlesMasterEnabled", false);
            Object map = ModUnderTest.config("com.killer560.hub.livemap.LiveMapConfig");
            ModUnderTest.set(map, "setEnabled", true);
            ModUnderTest.set(map, "setInteractiveMapEnabled", true);
            ModUnderTest.set(ModUnderTest.config(DX_CONFIG), "setBreakerAuraEnabled", false);
        });
        Map<String, List<String>> failures = new LinkedHashMap<>();
        try {
            boolean arena = selected.stream().anyMatch(c -> !c.equals(SWEEP));
            if (arena) {
                room = pickRoom(ctx);
                buildRoom(ctx, room);
                giveAotv(ctx);
            }
            for (String c : selected) {
                List<String> f = new ArrayList<>();
                failures.put(c, f);
                println(c, "---- " + c + " ----");
                try {
                    resetRoutes(ctx);
                    switch (c) {
                        case LIP -> caseLip(ctx, f);
                        case SLAB -> caseSlab(ctx, f);
                        case SLAB_NODE -> caseSlabNode(ctx, f);
                        case MAP_SLAB -> caseMapSlab(ctx, f);
                        case CHEST -> caseChest(ctx, f);
                        case BREAKER -> caseBreaker(ctx, f);
                        case RW -> caseRedstoneWarrior(ctx, f);
                        case REFIRE -> caseRefire(ctx, f);
                        case ALTAR -> caseAltar(ctx, f);
                        case REPLAN -> caseReplan(ctx, f);
                        case SWEEP -> caseSweep(ctx, f);
                        default -> { }
                    }
                } catch (Throwable t) {
                    f.add("threw " + t);
                    t.printStackTrace(System.out);
                }
            }
        } finally {
            ctx.runOnClient(mc -> {
                ModUnderTest.staticCall(EXECUTOR, "stop", new Class<?>[]{String.class}, new Object[]{"scenario over"});
                ModUnderTest.staticCall(CLEAR, "cancel");
            });
            resetRoutes(ctx);
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "leave"));
            ctx.runOnClient(mc -> mc.execute(() -> {
                if (mc.level != null) {
                    mc.level.disconnect(net.minecraft.network.chat.Component.literal("scenario over"));
                    mc.disconnectWithSavingScreen();
                }
            }));
            ctx.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null, 2400);
            ctx.waitTicks(40);
            ctx.runOnClient(mc -> mc.execute(() ->
                    McCompat.setScreen(mc, new net.minecraft.client.gui.screens.TitleScreen())));
            ctx.waitFor(mc -> McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.TitleScreen, 1200);
        }
        List<String> all = new ArrayList<>();
        for (var e : failures.entrySet()) {
            if (e.getValue().isEmpty()) {
                System.out.println("[" + e.getKey() + "] PASS");
            } else {
                System.out.println("[" + e.getKey() + "] FAIL - " + String.join("; ", e.getValue()));
                for (String s : e.getValue()) {
                    all.add(e.getKey() + ": " + s);
                }
            }
        }
        if (!all.isEmpty()) {
            throw new AssertionError(all.size() + " problem(s): " + String.join("; ", all));
        }
    }

    // ============================================================================================ 631 lip

    /** Lip arena (relative): goal T = floor block (20, F-1, 15), stone at (19, F, 15), a pillar x 10 z 15 up to F+9. */
    private static void caseLip(ClientGameTestContext ctx, List<String> f) {
        clearArena(ctx, 4);
        Map<int[], BlockState> b = new LinkedHashMap<>();
        b.put(new int[]{19, F, 15}, Blocks.STONE.defaultBlockState());
        for (int y = F; y <= F + 9; y++) {
            b.put(new int[]{10, y, 15}, Blocks.STONE.defaultBlockState());
        }
        setBlocks(ctx, b);
        settle(ctx);
        Vec3 start = rel(ctx, 10.5, F + 10, 15.5);
        BlockPos goal = relBlock(ctx, 20, F - 1, 15);
        String direct = onServer(ctx, (mc, level) -> {
            float[] aim = oracleDirect(level, start, goal);
            return aim == null ? "none" : String.format(Locale.US, "%.4f/%.4f", aim[0], aim[1]);
        });
        println(LIP, "ground truth, one warp from the pillar onto the goal: " + direct);
        if ("none".equals(direct)) {
            f.add("the ground truth sees no single warp onto the goal - the arena is not what this case needs");
            return;
        }
        Plan p = plan(ctx, start, goal);
        println(LIP, "planner: " + p);
        if (p.warps != 1) {
            f.add("the planner took " + p.warps + " warp(s) where one does it");
        }
        if (p.graphWarps < 2) {
            f.add("the floor graph alone found " + p.graphWarps + " warp(s) - this arena no longer shows the miss"
                    + " (the case proves nothing about the exact search)");
        }
        String replay = onServer(ctx, (mc, level) -> replay(level, start, p.hops, goal));
        if (replay != null) {
            f.add("the plan does not replay on the server: " + replay);
        }
        // An Auto Routes path pair, as he makes one: stand on the pillar, /ar add path; stand on the goal, /ar add path.
        int hops = pathPair(ctx, f, start, rel(ctx, 20.5, F, 15.5));
        if (hops != 1) {
            f.add("the path pair saved " + hops + " warp(s), not 1");
        }
        Vec3 end = fly(ctx, start, rel(ctx, 20.5, F, 15.5), 200);
        println(LIP, "flown: ended " + fmt(end));
        if (end == null || end.distanceTo(rel(ctx, 20.5, F, 15.5)) > 0.7) {
            f.add("flying the pair did not land on the goal (ended " + fmt(end) + ")");
        }
    }

    // ============================================================================================ 632 / 633 / 636 slab

    /**
     * Slab arena (relative): a box x,z 3..27 walled 12 high (all high walls topped with a cobblestone wall, no landing); a
     * wall at x 15 (z 4..26, 10 high) with a one-block window at
     * z 15, y F+4 and a doorway z 25..26, y F..F+2; a bottom slab P at (18, F+5, 15) seen through the window from S
     * (6.5, F, 15.5); the goal G = floor block (24, F-1, 15) inside a 2-high ring x 21..26 z 13..17 (no eye on the floor
     * sees over it; one on the slab does).
     */
    private static void slabArena(ClientGameTestContext ctx) {
        clearArena(ctx, 12);
        Map<int[], BlockState> b = new LinkedHashMap<>();
        BlockState stone = Blocks.STONE.defaultBlockState();
        for (int z = 4; z <= 26; z++) {
            for (int y = F; y <= F + 9; y++) {
                boolean window = z == 15 && y == F + 4;
                boolean door = z >= 25 && y <= F + 2;
                if (!window && !door) {
                    b.put(new int[]{15, y, z}, y == F + 9 ? Blocks.COBBLESTONE_WALL.defaultBlockState() : stone);
                }
            }
        }
        for (int x = 21; x <= 26; x++) {
            for (int z = 13; z <= 17; z++) {
                if (x == 21 || x == 26 || z == 13 || z == 17) {
                    for (int y = F; y <= F + 1; y++) {
                        b.put(new int[]{x, y, z}, stone);
                    }
                }
            }
        }
        b.put(new int[]{18, F + 5, 15}, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
        setBlocks(ctx, b);
        settle(ctx);
    }

    private static void caseSlab(ClientGameTestContext ctx, List<String> f) {
        slabArena(ctx);
        Vec3 s = rel(ctx, 6.5, F, 15.5);
        BlockPos slab = relBlock(ctx, 18, F + 5, 15);
        BlockPos goal = relBlock(ctx, 24, F - 1, 15);
        String state = onServer(ctx, (mc, level) -> level.getBlockState(slab).toString());
        println(SLAB, "P is " + state);
        // Ground truth: over the slab it is 2 warps; without landing on it there is no route of 2 or fewer.
        String truth = onServer(ctx, (mc, level) -> {
            float[] a = oracleDirect(level, s, slab);
            // Standing on the slab once he has dropped onto it (its top), the lower of the two eyes he could warp from.
            Vec3 onSlab = new Vec3(slab.getX() + 0.5, slab.getY() + 0.5, slab.getZ() + 0.5);
            float[] b = oracleDirect(level, onSlab, goal);
            float[] d = oracleDirect(level, s, goal);
            String two = noRouteOfTwo(level, s, goal, arenaBox(mc), 32);
            return "S->P " + (a != null) + ", P->G " + (b != null) + ", S->G " + (d != null) + "; without the slab: " + two;
        });
        println(SLAB, "ground truth: " + truth);
        if (!truth.startsWith("S->P true, P->G true, S->G false; without the slab: none")) {
            f.add("the arena is not what this case needs (" + truth + ")");
            return;
        }
        Plan p = plan(ctx, s, goal);
        println(SLAB, "planner: " + p);
        if (p.warps != 3) {
            f.add("the planner took " + p.warps + " warp(s); without the slab the fewest is 3");
        }
        for (double[] h : p.hops) {
            if (BlockPos.containing(h[0], h[1] - 0.5, h[2]).equals(slab)) {
                f.add("a planned warp starts on the bottom slab");
            }
        }
        String replay = onServer(ctx, (mc, level) -> replay(level, s, p.hops, goal));
        if (replay != null) {
            f.add("the plan does not replay on the server: " + replay);
        }
        Vec3 g = rel(ctx, 24.5, F, 15.5);
        int hops = pathPair(ctx, f, s, g);
        if (hops != 3) {
            f.add("the path pair saved " + hops + " warp(s), not 3");
        }
        List<Vec3> seen = new ArrayList<>();
        Vec3 end = flyWatching(ctx, s, g, 300, seen);
        Vec3 slabTop = rel(ctx, 18.5, F + 5.5, 15.5);
        boolean onSlab = seen.stream().anyMatch(v -> Math.abs(v.x - slabTop.x) < 0.7 && Math.abs(v.z - slabTop.z) < 0.7
                && Math.abs(v.y - slabTop.y) < 0.8);
        println(SLAB, "flown: ended " + fmt(end) + ", " + seen.size() + " position(s) sampled, on the slab: " + onSlab);
        if (end == null || end.distanceTo(g) > 0.7) {
            f.add("flying the pair did not arrive on the goal (ended " + fmt(end) + ")");
        }
        if (onSlab) {
            f.add("he stood on the bottom slab during the flight");
        }
    }

    private static void caseSlabNode(ClientGameTestContext ctx, List<String> f) {
        slabArena(ctx);
        Vec3 s = rel(ctx, 6.5, F, 15.5);
        BlockPos slab = relBlock(ctx, 18, F + 5, 15);
        Vec3 onSlab = new Vec3(slab.getX() + 0.5, slab.getY() + 0.5, slab.getZ() + 0.5);
        Plan p = plan(ctx, s, slab);
        println(SLAB_NODE, "planner, onto the slab itself: " + p);
        if (p.warps != 1) {
            f.add("the planner took " + p.warps + " warp(s) onto the slab node; one does it");
        }
        // The node is placed standing ON the slab (feet at its top), as he would place it.
        int hops = pathPair(ctx, f, s, onSlab);
        if (hops != 1) {
            f.add("the path pair onto the slab saved " + hops + " warp(s), not 1");
        }
        Vec3 end = fly(ctx, s, null, 200);
        println(SLAB_NODE, "flown: ended " + fmt(end) + " (slab top " + fmt(onSlab) + ")");
        if (end == null || Math.abs(end.x - onSlab.x) > 0.6 || Math.abs(end.z - onSlab.z) > 0.6
                || Math.abs(end.y - onSlab.y) > 0.1) {
            f.add("flying the pair did not end standing on the slab (ended " + fmt(end) + ")");
        }
    }

    private static void caseMapSlab(ClientGameTestContext ctx, List<String> f) {
        slabArena(ctx);
        Vec3 s = rel(ctx, 6.5, F, 15.5);
        BlockPos goal = relBlock(ctx, 24, F - 1, 15);
        Vec3 g = rel(ctx, 24.5, F, 15.5);
        Vec3 slabTop = rel(ctx, 18.5, F + 5.5, 15.5);
        place(ctx, s);
        long mark = LogTap.mark();
        List<Vec3> seen = new ArrayList<>();
        long[] pressed = new long[1];
        ctx.runOnClient(mc -> {
            pressed[0] = System.nanoTime();
            ModUnderTest.staticCall(CLEAR, "etherPathExact", new Class<?>[]{BlockPos.class,
                    Runnable.class}, new Object[]{goal, (Runnable) () -> { }});
        });
        boolean busySeen = false;
        long firstMove = -1;
        int firstMoveTick = -1;
        for (int i = 0; i < 300; i++) {
            ctx.waitTicks(1);
            Vec3 now = ctx.computeOnClient(mc -> mc.player.position());
            if (firstMove < 0 && now.distanceTo(s) > 1.0) {
                firstMove = System.nanoTime();
                firstMoveTick = i + 1;
            }
            seen.add(now);
            boolean busy = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(CLEAR, "isBusy"));
            busySeen |= busy;
            if (busySeen && !busy) {
                break;
            }
        }
        ctx.waitTicks(10);
        Vec3 end = ctx.computeOnClient(mc -> mc.player.position());
        for (String l : LogTap.since(mark)) {
            if (l.contains("[Path]")) {
                println(MAP_SLAB, "  " + l.replaceAll("^.*?\\[Path\\]", "[Path]"));
            }
        }
        boolean onSlab = seen.stream().anyMatch(v -> Math.abs(v.x - slabTop.x) < 0.7 && Math.abs(v.z - slabTop.z) < 0.7
                && Math.abs(v.y - slabTop.y) < 0.8);
        println(MAP_SLAB, "map trip: busy seen " + busySeen + ", ended " + fmt(end) + ", on the slab: " + onSlab);
        String turn = "?";
        for (String l : LogTap.since(mark)) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("- ([0-9.]+) ms from the press to the first"
                    + " warp's turn").matcher(l);
            if (m.find()) {
                turn = m.group(1);
            }
        }
        println(MAP_SLAB, String.format(Locale.US, "click to first warp: planned and queued %s ms after the press;"
                        + " he had moved by client tick %d (%.0f ms after the press, ticks are 50 ms)", turn,
                firstMoveTick, firstMove < 0 ? -1.0 : (firstMove - pressed[0]) / 1e6));
        // The plan is ready in milliseconds; the rest is the warp itself (sneak to the server, the use, the server's
        // teleport back), the same few ticks it always took.
        if (firstMoveTick < 0 || firstMoveTick > 6) {
            f.add("the map trip did not start moving within 6 client ticks of the press (" + firstMoveTick + ")");
        }
        if (!"?".equals(turn) && Double.parseDouble(turn) > 50) {
            f.add("the press took " + turn + " ms to plan - not an instant start");
        }
        if (!busySeen || end.distanceTo(g) > 0.7) {
            f.add("the map trip did not arrive on the goal (ended " + fmt(end) + ")");
        }
        if (onSlab) {
            f.add("the map trip stood on the bottom slab");
        }
    }

    // ============================================================================================ 638 chest

    /**
     * The slab arena with a CHEST where the slab was (killer560, 2026-10-11: only blocks with a completely flat, full
     * top face; a chest's top is 7/8 of a block): the planner must go round (3 warps), and a path node placed ON the
     * chest is still reached in one.
     */
    private static void caseChest(ClientGameTestContext ctx, List<String> f) {
        slabArena(ctx);
        setBlocks(ctx, Map.of(new int[]{18, F + 5, 15}, Blocks.CHEST.defaultBlockState()));
        settle(ctx);
        Vec3 s = rel(ctx, 6.5, F, 15.5);
        BlockPos chest = relBlock(ctx, 18, F + 5, 15);
        BlockPos goal = relBlock(ctx, 24, F - 1, 15);
        String truth = onServer(ctx, (mc, level) -> {
            float[] a = oracleDirect(level, s, chest);
            Vec3 onChest = new Vec3(chest.getX() + 0.5, chest.getY() + 0.875, chest.getZ() + 0.5);
            float[] b = oracleDirect(level, onChest, goal);
            return level.getBlockState(chest).getBlock() + ": S->C " + (a != null) + ", C->G " + (b != null)
                    + "; without it: " + noRouteOfTwo(level, s, goal, arenaBox(mc), 32);
        });
        println(CHEST, "ground truth: " + truth);
        if (!truth.contains("S->C true, C->G true; without it: none")) {
            f.add("the arena is not what this case needs (" + truth + ")");
            return;
        }
        Plan p = plan(ctx, s, goal);
        println(CHEST, "planner, to the goal: " + p);
        if (p.warps != 3) {
            f.add("the planner took " + p.warps + " warp(s); without the chest the fewest is 3");
        }
        for (double[] h : p.hops) {
            if (BlockPos.containing(h[0], h[1] - 0.5, h[2]).equals(chest)) {
                f.add("a planned warp starts on the chest");
            }
        }
        String replay = onServer(ctx, (mc, level) -> replay(level, s, p.hops, goal));
        if (replay != null) {
            f.add("the plan does not replay on the server: " + replay);
        }
        Plan q = plan(ctx, s, chest);
        println(CHEST, "planner, onto the chest itself (a node placed on it): " + q);
        if (q.warps != 1) {
            f.add("the planner took " + q.warps + " warp(s) onto the chest node; one does it");
        }
    }

    // ============================================================================================ 637 breaker

    /**
     * killer560 (2026-10-12): "the path node isn't planning like there aren't any blocks blocking it for dungeon breaker
     * stuff" and "breaker aura should only work in boss phase [...] During clear the only way to auto break blocks is
     * through the breaker node." A wall across the arena (x 15, 10 high, capped) whose only straight gap is three stone
     * blocks at z 15, y F..F+2; the way round is the far doorway (z 25..26).
     * <ol>
     *   <li>Breaker node source: a DUNGEON_BREAKER node (#1, out of the way) breaks those three blocks; the path pair
     *       #2 -> #3 after it plans them as gone - ONE warp through the gap (no single warp with them standing, ground
     *       truth). Fired while they stand it waits (sneak held, 0 use packets, one chat line naming the block); once
     *       they are broken it warps on its own and lands.</li>
     *   <li>Breaker Aura in clear: the same three blocks picked, the aura on, a Dungeon Breaker in hand - nothing is
     *       broken in 60 ticks, and a pair planned with no breaker node does NOT go through them.</li>
     *   <li>Breaker Aura in boss (/killer560 sim's override): it breaks a pick.</li>
     * </ol>
     */
    private static void caseBreaker(ClientGameTestContext ctx, List<String> f) {
        wallArena(ctx);
        Vec3 s = rel(ctx, 6.5, F, 15.5);
        BlockPos goal = relBlock(ctx, 24, F - 1, 15);
        Vec3 g = rel(ctx, 24.5, F, 15.5);
        String truth = onServer(ctx, (mc, level) -> {
            float[] d = oracleDirect(level, s, goal);
            return (d == null ? "no single warp" : "ONE warp lands") + "; 2 or fewer: "
                    + noRouteOfTwo(level, s, goal, arenaBox(mc), 32);
        });
        println(BREAKER, "ground truth with the wall's gap blocks standing: " + truth);
        if (!truth.startsWith("no single warp")) {
            f.add("with the gap blocks standing one warp already lands (" + truth + ")");
        }
        // ---- 1. the breaker node source ----
        writeBreakerNode(ctx);
        long planMark = LogTap.mark();
        int hops = pathPair(ctx, f, s, g, false);
        boolean said = false;
        for (String l : LogTap.since(planMark)) {
            said |= l.contains("3 breaker node block(s) planned as broken");
        }
        println(BREAKER, "path pair after the breaker node: " + hops + " warp(s) saved; planner said it planned the 3"
                + " breaker node blocks as broken: " + said);
        if (hops != 1) {
            f.add("the path pair saved " + hops + " warp(s); through the breaker node's blocks one does it");
        }
        if (!said) {
            f.add("the planner did not plan the breaker node's blocks as broken");
        }
        Vec3 off = rel(ctx, 4.5, F, 4.5);
        place(ctx, off);
        ctx.waitTicks(10);
        int waitedBefore = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(EXECUTOR, "pickWaitTicksTotal"));
        long flyMark = LogTap.mark();
        ctx.runOnClient(mc -> dev.testkit.harness.PacketTrace.start());
        tpNoWait(ctx, s);
        ctx.waitTicks(60);
        Vec3 held = ctx.computeOnClient(mc -> mc.player.position());
        boolean sneaking = ctx.computeOnClient(mc -> mc.player.getLastSentInput().shift());
        boolean running = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(EXECUTOR, "isRunning"));
        int usesWaiting = ctx.computeOnClient(mc -> dev.testkit.harness.PacketTrace.sent("use_item"));
        int waited = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(EXECUTOR, "pickWaitTicksTotal"))
                - waitedBefore;
        int named = 0;
        for (String l : LogTap.since(flyMark)) {
            named += l.contains("[CHAT]") && l.contains("Waiting for the breaker block at") ? 1 : 0;
        }
        println(BREAKER, String.format(Locale.US, "blocks standing, 60 ticks on #2: at %s (%.2f from the start), route"
                        + " running %s, sneak held %s, %d use packet(s), %d tick(s) waited, chat line(s) naming it: %d",
                fmt(held), held.distanceTo(s), running, sneaking, usesWaiting, waited, named));
        if (held.distanceTo(s) > 0.5 || !running || !sneaking || usesWaiting != 0 || waited < 40 || named != 1) {
            f.add("while the breaker blocks stood the hop did not wait as it should (moved " + String.format(Locale.US,
                    "%.2f", held.distanceTo(s)) + ", running " + running + ", sneak " + sneaking + ", " + usesWaiting
                    + " use(s), waited " + waited + " tick(s), " + named + " chat line(s))");
        }
        setBlocks(ctx, gapBlocks(Blocks.AIR.defaultBlockState()));
        Vec3 end = null;
        for (int i = 0; i < 100; i++) {
            ctx.waitTicks(1);
            end = ctx.computeOnClient(mc -> mc.player.position());
            if (end.distanceTo(g) < 0.3) {
                break;
            }
        }
        ctx.waitTicks(5);
        int usesTotal = ctx.computeOnClient(mc -> dev.testkit.harness.PacketTrace.sent("use_item"));
        ctx.runOnClient(mc -> dev.testkit.harness.PacketTrace.stop());
        println(BREAKER, "blocks broken: ended " + fmt(end) + ", " + usesTotal + " use packet(s) in all");
        if (end == null || end.distanceTo(g) > 0.7) {
            f.add("once the breaker blocks broke the waiting hop did not land on the goal (ended " + fmt(end) + ")");
        }
        if (usesTotal != 1) {
            f.add("the hop sent " + usesTotal + " use packet(s); one warp was due");
        }
        ctx.runOnClient(mc -> ModUnderTest.staticCall(EXECUTOR, "stop", new Class<?>[]{String.class},
                new Object[]{"test reset"}));
        // ---- 2. Breaker Aura in clear: breaks nothing, plans nothing ----
        setBlocks(ctx, gapBlocks(Blocks.STONE.defaultBlockState()));
        settle(ctx);
        List<BlockPos> picks = List.of(relBlock(ctx, 15, F, 15), relBlock(ctx, 15, F + 1, 15), relBlock(ctx, 15, F + 2, 15));
        giveBreaker(ctx);
        ctx.runOnClient(mc -> {
            Object store = ModUnderTest.staticCall("com.killer560.hub.dungeonextras.BreakerAuraStore", "getInstance");
            for (BlockPos p : picks) {
                ModUnderTest.call(store, "addPick", new Class<?>[]{String.class},
                        new Object[]{p.getX() + "," + p.getY() + "," + p.getZ()});
            }
            ModUnderTest.set(ModUnderTest.config(DX_CONFIG), "setBreakerAuraEnabled", true);
        });
        try {
            place(ctx, rel(ctx, 13.5, F, 15.5));   // in reach of the picks, Dungeon Breaker in hand
            ctx.runOnClient(mc -> dev.testkit.harness.PacketTrace.start());
            ctx.waitTicks(60);
            int digs = ctx.computeOnClient(mc -> dev.testkit.harness.PacketTrace.sent("player_action"));
            ctx.runOnClient(mc -> dev.testkit.harness.PacketTrace.stop());
            int standing = onServer(ctx, (mc, level) -> {
                int n = 0;
                for (BlockPos p : picks) {
                    n += level.getBlockState(p).isAir() ? 0 : 1;
                }
                return n;
            });
            println(BREAKER, "Breaker Aura on in clear, 60 ticks in reach with a Dungeon Breaker: " + standing
                    + " of 3 picks still standing, " + digs + " dig packet(s)");
            if (standing != 3 || digs != 0) {
                f.add("Breaker Aura acted during clear (" + (3 - standing) + " broken, " + digs + " dig packet(s))");
            }
            // A pair with no breaker node before it: the aura's picks are not planned as gone.
            resetRoutes(ctx);
            giveAotv(ctx);
            long m2 = LogTap.mark();
            int noNode = pathPair(ctx, f, s, g, false);
            boolean airSaid = false;
            for (String l : LogTap.since(m2)) {
                airSaid |= l.contains("planned as broken");
            }
            println(BREAKER, "path pair with only Breaker Aura's picks: " + noNode + " warp(s), planned anything as"
                    + " broken: " + airSaid);
            if (noNode <= 1 || airSaid) {
                f.add("the planner counted Breaker Aura's picks as broken during clear (" + noNode + " warp(s))");
            }
            // ---- 3. Breaker Aura in boss: it breaks ----
            giveBreaker(ctx);
            place(ctx, rel(ctx, 13.5, F, 15.5));
            ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.secrets.DungeonState", "toggleSimOverride"));
            int broke = 0;
            try {
                for (int i = 0; i < 100 && broke == 0; i++) {
                    ctx.waitTicks(1);
                    broke = onServer(ctx, (mc, level) -> {
                        int n = 0;
                        for (BlockPos p : picks) {
                            n += level.getBlockState(p).isAir() ? 1 : 0;
                        }
                        return n;
                    });
                }
            } finally {
                ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.secrets.DungeonState",
                        "toggleSimOverride"));
            }
            println(BREAKER, "Breaker Aura in the boss phase (sim override): " + broke + " pick(s) broken");
            if (broke == 0) {
                f.add("Breaker Aura broke nothing in the boss phase");
            }
        } finally {
            ctx.runOnClient(mc -> {
                Object store = ModUnderTest.staticCall("com.killer560.hub.dungeonextras.BreakerAuraStore", "getInstance");
                for (BlockPos p : picks) {
                    ModUnderTest.call(store, "removePick", new Class<?>[]{String.class},
                            new Object[]{p.getX() + "," + p.getY() + "," + p.getZ()});
                }
                ModUnderTest.set(ModUnderTest.config(DX_CONFIG), "setBreakerAuraEnabled", false);
            });
            giveAotv(ctx);
        }
    }

    /** 637's arena: the wall at x 15 with the far doorway and its three-block gap at z 15 filled with stone. */
    private static void wallArena(ClientGameTestContext ctx) {
        clearArena(ctx, 12);
        Map<int[], BlockState> b = new LinkedHashMap<>();
        for (int z = 4; z <= 26; z++) {
            for (int y = F; y <= F + 9; y++) {
                boolean door = z >= 25 && y <= F + 2;
                if (!door) {
                    b.put(new int[]{15, y, z}, y == F + 9 ? Blocks.COBBLESTONE_WALL.defaultBlockState()
                            : Blocks.STONE.defaultBlockState());
                }
            }
        }
        setBlocks(ctx, b);
        settle(ctx);
    }

    private static Map<int[], BlockState> gapBlocks(BlockState state) {
        Map<int[], BlockState> m = new LinkedHashMap<>();
        for (int y = F; y <= F + 2; y++) {
            m.put(new int[]{15, y, 15}, state);
        }
        return m;
    }

    /** The route file with one DUNGEON_BREAKER node (out of the way, at 8.5 F 8.5) whose blocks are the gap. */
    private static void writeBreakerNode(ClientGameTestContext ctx) {
        writeBreakerNode(ctx, 8.5, 8.5);
    }

    private static void writeBreakerNode(ClientGameTestContext ctx, double nx, double nz) {
        ctx.runOnClient(mc -> {
            try {
                Path file = (Path) ModUnderTest.staticCall(STORE, "routesFile");
                JsonObject n = new JsonObject();
                n.addProperty("type", "DUNGEON_BREAKER");
                n.addProperty("x", nx);
                n.addProperty("y", (double) F);
                n.addProperty("z", nz);
                n.addProperty("yaw", 0f);
                n.addProperty("pitch", 0f);
                JsonArray blocks = new JsonArray();
                for (int y = F; y <= F + 2; y++) {
                    blocks.add("15 " + y + " 15");
                }
                n.add("blocks", blocks);
                JsonArray nodes = new JsonArray();
                nodes.add(n);
                JsonObject r = new JsonObject();
                r.add("nodes", nodes);
                JsonObject routes = new JsonObject();
                routes.add(room, r);
                JsonObject root = new JsonObject();
                root.addProperty("version", 1);
                root.add("routes", routes);
                Files.writeString(file, root.toString(), StandardCharsets.UTF_8);
                ModUnderTest.staticCall(STORE, "reload");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        ctx.waitTicks(3);
    }

    private static void giveBreaker(ClientGameTestContext ctx) {
        AtomicReference<Boolean> given = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            UUID uuid = mc.player.getUUID();
            server.execute(() -> {
                var inv = server.getPlayerList().getPlayer(uuid).getInventory();
                inv.setItem(0, (ItemStack) ModUnderTest.staticCall(SIM_ITEMS, "build", new Class<?>[]{String.class},
                        new Object[]{"DUNGEONBREAKER"}));
                given.set(true);
            });
        });
        ctx.waitFor(mc -> given.get() != null, 200);
        ctx.waitTicks(5);
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
        ctx.waitTicks(3);
    }

    private static void tpNoWait(ClientGameTestContext ctx, Vec3 to) {
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            UUID me = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(me);
                if (sp != null) {
                    sp.teleportTo(server.overworld(), to.x, to.y, to.z,
                            java.util.Set.of(net.minecraft.world.entity.Relative.Y_ROT,
                                    net.minecraft.world.entity.Relative.X_ROT), 0f, 0f, false);
                }
            });
        });
    }

    // ============================================================================================ 873 breaker re-fire

    /**
     * killer560 (2026-10-12), blocks a breaker node broke growing back: "Only if I am on the node and there are blocks
     * to be broke then it should break them." 637's wall gap, a breaker node at (13.5, F, 15.5) in reach of its three
     * blocks, a Dungeon Breaker in hand. He steps on: the node fires and breaks them. Then, still standing there:
     * (1) the blocks grow back - the node fires again and breaks each once (3 dig packets); (2) all gone - nothing
     * fires in 40 ticks; (3) off the node with the blocks back - nothing fires, they stay.
     */
    private static void caseRefire(ClientGameTestContext ctx, List<String> f) {
        wallArena(ctx);
        resetRoutes(ctx);
        writeBreakerNode(ctx, 13.5, 15.5);
        giveBreaker(ctx);
        Vec3 node = rel(ctx, 13.5, F, 15.5);
        List<BlockPos> gap = List.of(relBlock(ctx, 15, F, 15), relBlock(ctx, 15, F + 1, 15), relBlock(ctx, 15, F + 2, 15));
        Supplier<Integer> standing = () -> onServer(ctx, (mc, level) -> {
            int n = 0;
            for (BlockPos p : gap) {
                n += level.getBlockState(p).isAir() ? 0 : 1;
            }
            return n;
        });
        place(ctx, rel(ctx, 6.5, F, 6.5));
        ctx.waitTicks(10);
        place(ctx, node);
        boolean first = waitFor(ctx, 100, () -> standing.get() == 0);
        println(REFIRE, "stepping on the breaker node: blocks broken " + first);
        if (!first) {
            f.add("the breaker node did not break its blocks when he stepped on it");
            return;
        }
        ctx.waitTicks(20);
        // (1) grown back while he stands on it
        long m1 = LogTap.mark();
        ctx.runOnClient(mc -> dev.testkit.harness.PacketTrace.start());
        setBlocks(ctx, gapBlocks(Blocks.STONE.defaultBlockState()));
        boolean again = waitFor(ctx, 100, () -> standing.get() == 0);
        ctx.waitTicks(20);
        int digs1 = ctx.computeOnClient(mc -> dev.testkit.harness.PacketTrace.sent("player_action"));
        ctx.runOnClient(mc -> dev.testkit.harness.PacketTrace.stop());
        boolean said = logHas(m1, "of its 3 block(s) standing while he is on it");
        println(REFIRE, "(1) on the node, blocks grown back: broken again " + again + ", re-fire logged " + said + ", "
                + digs1 + " dig packet(s)");
        if (!again || !said) {
            f.add("standing on the node with its blocks back, it did not re-fire and break them");
        }
        if (digs1 != 3) {
            f.add("the re-fire sent " + digs1 + " dig packet(s) for 3 standing blocks");
        }
        // (2) all gone, still on it
        long m2 = LogTap.mark();
        ctx.runOnClient(mc -> dev.testkit.harness.PacketTrace.start());
        ctx.waitTicks(40);
        int digs2 = ctx.computeOnClient(mc -> dev.testkit.harness.PacketTrace.sent("player_action"));
        ctx.runOnClient(mc -> dev.testkit.harness.PacketTrace.stop());
        boolean fired2 = logHas(m2, "standing while he is on it") || logHas(m2, "Started \"");
        println(REFIRE, "(2) on the node, all blocks gone, 40 ticks: fired " + fired2 + ", " + digs2 + " dig packet(s)");
        if (fired2 || digs2 != 0) {
            f.add("standing on the node with every block gone, something fired (" + digs2 + " dig packet(s))");
        }
        // (3) off the node, blocks back
        place(ctx, rel(ctx, 6.5, F, 6.5));
        ctx.waitTicks(10);
        long m3 = LogTap.mark();
        ctx.runOnClient(mc -> dev.testkit.harness.PacketTrace.start());
        setBlocks(ctx, gapBlocks(Blocks.STONE.defaultBlockState()));
        ctx.waitTicks(40);
        int digs3 = ctx.computeOnClient(mc -> dev.testkit.harness.PacketTrace.sent("player_action"));
        ctx.runOnClient(mc -> dev.testkit.harness.PacketTrace.stop());
        int left = standing.get();
        boolean fired3 = logHas(m3, "standing while he is on it") || logHas(m3, "Started \"");
        println(REFIRE, "(3) off the node, blocks back, 40 ticks: fired " + fired3 + ", " + digs3 + " dig packet(s), "
                + left + " of 3 standing");
        if (fired3 || digs3 != 0 || left != 3) {
            f.add("off the node something fired or broke (" + digs3 + " dig packet(s), " + left + " of 3 standing)");
        }
        resetRoutes(ctx);
        giveAotv(ctx);
    }

    // ============================================================================================ 871 Redstone Warrior

    /** His own route for Redstone Warrior (26.1.2 Mod Only Test, 2026-10-12), plans stripped so every pair plans fresh. */
    private static final String RW_ROUTE = "{\"nodes\":["
            + "{\"type\":\"PATH\",\"x\":6.5,\"y\":58.0,\"z\":15.5,\"yaw\":-87.9,\"pitch\":-4.6,\"start\":true},"
            + "{\"type\":\"PATH\",\"x\":27.5,\"y\":58.0,\"z\":15.5,\"yaw\":-93.5,\"pitch\":-34.2},"
            + "{\"type\":\"PATH\",\"x\":27.5,\"y\":58.0,\"z\":15.5,\"yaw\":-93.5,\"pitch\":-34.2},"
            + "{\"type\":\"PATH\",\"x\":54.5,\"y\":77.0,\"z\":27.5,\"yaw\":-84.6,\"pitch\":6.7},"
            + "{\"type\":\"DUNGEON_BREAKER\",\"x\":54.5,\"y\":77.0,\"z\":27.5,\"yaw\":-84.6,\"pitch\":6.7,"
            + "\"blocks\":[\"55 78 27\",\"55 77 27\"]},"
            + "{\"type\":\"ETHERWARP\",\"x\":54.5,\"y\":77.0,\"z\":27.5,\"yaw\":-89.193604,\"pitch\":17.877462,"
            + "\"landing\":\"58.500000 77.050000 27.500000\"},"
            + "{\"type\":\"PATH\",\"x\":58.5,\"y\":77.0,\"z\":27.5,\"yaw\":80.6,\"pitch\":26.6},"
            + "{\"type\":\"PATH\",\"x\":34.5,\"y\":79.0,\"z\":29.5,\"yaw\":-12.1,\"pitch\":35.8}]}";

    /**
     * killer560's own test (2026-10-12, "I just tested in redstone warrior"): his Redstone Warrior route rebuilt in the
     * sim, the room alone at rotation 0 as his frame had it. Every pair is planned with /ar replan; the log must say which
     * breaker node blocks each pair planned as broken (pair #7 -> #8 comes after the breaker node #5, whose two blocks
     * 55 77..78 27 are in the wall between). Reported: each pair's saved warps, and whether #7's first warp lands with
     * the two blocks broken (server) - and with them standing does not.
     */
    private static void caseRedstoneWarrior(ClientGameTestContext ctx, List<String> f) {
        List<String> names = ctx.computeOnClient(mc -> new ArrayList<>((List<String>) ModUnderTest.staticCall(
                ROOM_LIBRARY, "names")));
        if (!names.contains("Redstone Warrior")) {
            f.add("Redstone Warrior is not in the room library");
            return;
        }
        String keep = room;
        int keepF = F;
        buildRoom(ctx, "Redstone Warrior");
        giveAotv(ctx);
        try {
            ctx.runOnClient(mc -> {
                try {
                    Path file = (Path) ModUnderTest.staticCall(STORE, "routesFile");
                    JsonObject routes = new JsonObject();
                    routes.add("Redstone Warrior", JsonParser.parseString(RW_ROUTE));
                    JsonObject root = new JsonObject();
                    root.addProperty("version", 1);
                    root.add("routes", routes);
                    Files.writeString(file, root.toString(), StandardCharsets.UTF_8);
                    ModUnderTest.staticCall(STORE, "reload");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            ctx.waitTicks(5);
            long mark = LogTap.mark();
            cmd(ctx, "/ar replan");
            waitFor(ctx, 1200, () -> {
                int done = 0;
                for (String l : LogTap.since(mark)) {
                    done += l.contains("planned by the floor planner") || l.contains("not planned") ? 1 : 0;
                }
                return done >= 3;
            });
            boolean said = false;
            for (String l : LogTap.since(mark)) {
                if (l.contains("[AutoRoutes] Path #")) {
                    println(RW, "  " + l.replaceAll("^.*?\\[AutoRoutes\\]", "[AutoRoutes]"));
                }
                said |= l.contains("Path #7 -> #8") && l.contains("2 breaker node block(s) planned as broken");
            }
            if (!said) {
                f.add("pair #7 -> #8 (after breaker node #5) was not planned with its 2 breaker blocks as broken");
            }
            // Pair #7's saved warps, replayed on the server with the two blocks standing and broken.
            JsonArray hops7 = savedHopsOf(ctx, "Redstone Warrior", 6);
            println(RW, "pair #7 -> #8 saved " + hops7.size() + " warp(s): " + hops7);
            BlockPos b1 = relBlock(ctx, 55, 78, 27);
            BlockPos b2 = relBlock(ctx, 55, 77, 27);
            boolean throughWall = false;
            if (hops7.size() > 0) {
                String[] h = hops7.get(0).getAsString().split(" ");
                Vec3 o = rel(ctx, Double.parseDouble(h[0]), Double.parseDouble(h[1]), Double.parseDouble(h[2]));
                float yaw = ctx.computeOnClient(mc -> (Float) ModUnderTest.staticCall(COORDS, "toRealYaw",
                        new Class<?>[]{ModUnderTest.staticCall(FRAME, "current").getClass(), float.class},
                        new Object[]{ModUnderTest.staticCall(FRAME, "current"), Float.parseFloat(h[3])}));
                float pitch = Float.parseFloat(h[4]);
                BlockPos target = relBlock(ctx, Integer.parseInt(h[5]), Integer.parseInt(h[6]), Integer.parseInt(h[7]));
                String standing = onServer(ctx, (mc, level) -> {
                    BlockPos land = cast(level, o.x, o.y + 1.27, o.z, yaw, pitch, range());
                    return String.valueOf(land);
                });
                setRealBlocks(ctx, List.of(b1, b2), Blocks.AIR.defaultBlockState());
                String broken = onServer(ctx, (mc, level) -> {
                    BlockPos land = cast(level, o.x, o.y + 1.27, o.z, yaw, pitch, range());
                    return String.valueOf(land);
                });
                throughWall = broken.equals(String.valueOf(target)) && !standing.equals(String.valueOf(target));
                println(RW, "pair #7's first warp lands on " + standing + " with the breaker blocks standing, on " + broken
                        + " with them broken (planned " + target.toShortString() + "): goes through the broken blocks "
                        + throughWall);
            }
            if (!throughWall) {
                println(RW, "pair #7's plan does not depend on the breaker blocks here (reported, not judged)");
            }
        } finally {
            buildRoom(ctx, keep);
            F = keepF;
            giveAotv(ctx);
        }
    }

    // ============================================================================================ 941 Altar

    /** His Altar route (2026-10-12 file), nodes #1-#10 as saved but no saved warps, #8's await off (he skipped it). */
    private static final String ALTAR_ROUTE = "{\"nodes\":["
            + "{\"type\":\"PATH\",\"x\":15.5,\"y\":71.813,\"z\":47.5,\"yaw\":-107.007233,\"pitch\":4.481466,\"at\":0,"
            + "\"start\":true,\"awaitEnabled\":true,\"await\":\"SECRET\",\"amount\":1},"
            + "{\"type\":\"PATH\",\"x\":51.5,\"y\":84.0,\"z\":49.5,\"yaw\":155.278824,\"pitch\":80.066437,\"at\":0,"
            + "\"awaitEnabled\":true,\"await\":\"SECRET\",\"amount\":1},"
            + "{\"type\":\"PATH\",\"x\":51.5,\"y\":84.0,\"z\":49.5,\"yaw\":114.185654,\"pitch\":79.742622,\"at\":0},"
            + "{\"type\":\"PATH\",\"x\":28.5,\"y\":55.0,\"z\":39.5,\"yaw\":-109.529785,\"pitch\":10.501122,\"at\":0},"
            + "{\"type\":\"DUNGEON_BREAKER\",\"x\":28.5,\"y\":55.0,\"z\":39.5,\"yaw\":-109.529785,\"pitch\":10.501122,"
            + "\"at\":0,\"blocks\":[\"29 56 38\",\"29 55 38\",\"29 55 39\",\"30 55 37\",\"30 56 37\",\"31 56 37\"]},"
            + "{\"type\":\"ETHERWARP\",\"x\":28.5,\"y\":55.0,\"z\":39.5,\"yaw\":-120.210144,\"pitch\":22.343706,\"at\":0,"
            + "\"landing\":\"35.500000 53.050000 35.500000\"},"
            + "{\"type\":\"DUNGEON_BREAKER\",\"x\":35.5,\"y\":53.0,\"z\":35.5,\"yaw\":96.771881,\"pitch\":82.720116,"
            + "\"at\":0,\"blocks\":[\"34 52 36\",\"33 52 36\",\"34 52 35\",\"33 51 36\",\"34 51 36\",\"33 50 36\","
            + "\"34 50 36\"]},"
            + "{\"type\":\"USE_ITEM\",\"x\":35.5,\"y\":53.0,\"z\":35.5,\"yaw\":80.008026,\"pitch\":86.020653,\"at\":0,"
            + "\"awaitEnabled\":false,\"await\":\"SECRET\",\"amount\":1,\"item\":\"BAT_WAND\"},"
            + "{\"type\":\"PATH\",\"x\":35.5,\"y\":53.0,\"z\":35.5,\"yaw\":96.769135,\"pitch\":63.30603,\"at\":0},"
            + "{\"type\":\"PATH\",\"x\":15.5,\"y\":44.0,\"z\":39.5,\"yaw\":92.898499,\"pitch\":88.576324,\"at\":0}]}";

    /**
     * killer560 (2026-10-12, "see why that path was failing to generate from the bat to the chest"): his Altar route in
     * the room alone. He is put on node #7's tile (the breaker node, the bat wand and path #9 stacked there); the stack
     * fires, node #7 breaks its seven blocks (the hole down), and path #9 must be PLANNED to #10 on the room's lower
     * level (relative y 44, 25 below the dungeon floor) and FLOWN: he ends on node #10.
     */
    private static void caseAltar(ClientGameTestContext ctx, List<String> f) {
        @SuppressWarnings("unchecked")
        List<String> names = ctx.computeOnClient(mc -> new ArrayList<>((List<String>) ModUnderTest.staticCall(
                ROOM_LIBRARY, "names")));
        if (!names.contains("Altar")) {
            f.add("Altar is not in the room library");
            return;
        }
        String keep = room;
        int keepF = F;
        buildRoom(ctx, "Altar");
        try {
            ctx.runOnClient(mc -> {
                try {
                    Path file = (Path) ModUnderTest.staticCall(STORE, "routesFile");
                    JsonObject routes = new JsonObject();
                    routes.add("Altar", JsonParser.parseString(ALTAR_ROUTE));
                    JsonObject root = new JsonObject();
                    root.addProperty("version", 1);
                    root.add("routes", routes);
                    Files.writeString(file, root.toString(), StandardCharsets.UTF_8);
                    ModUnderTest.staticCall(STORE, "reload");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            AtomicReference<Boolean> given = new AtomicReference<>();
            ctx.runOnClient(mc -> {
                var server = mc.getSingleplayerServer();
                UUID uuid = mc.player.getUUID();
                server.execute(() -> {
                    var inv = server.getPlayerList().getPlayer(uuid).getInventory();
                    for (int i = 0; i < 36; i++) {
                        inv.setItem(i, ItemStack.EMPTY);
                    }
                    String[] ids = {"ASPECT_OF_THE_VOID", "DUNGEONBREAKER", "BAT_WAND"};
                    for (int i = 0; i < ids.length; i++) {
                        inv.setItem(i, (ItemStack) ModUnderTest.staticCall(SIM_ITEMS, "build",
                                new Class<?>[]{String.class}, new Object[]{ids[i]}));
                    }
                    given.set(true);
                });
            });
            ctx.waitFor(mc -> given.get() != null, 200);
            ctx.waitTicks(10);
            ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
            Vec3 n7 = rel(ctx, 35.5, 53.0, 35.5);
            Vec3 n10 = rel(ctx, 15.5, 44.0, 39.5);
            BlockPos goal = relBlock(ctx, 15, 43, 39);
            String goalBlocks = onServer(ctx, (mc, level) -> level.getBlockState(goal) + ", above "
                    + level.getBlockState(goal.above()) + " / " + level.getBlockState(goal.above(2)));
            println(ALTAR, "node #7 " + fmt(n7) + ", node #10 " + fmt(n10) + " on " + goal.toShortString() + " ("
                    + goalBlocks + ")");
            long mark = LogTap.mark();
            tpNoWait(ctx, n7);
            double travelled = 0;
            Vec3 last = n7;
            boolean arrived = false;
            for (int i = 0; i < 600; i++) {
                ctx.waitTicks(1);
                Vec3 p = ctx.computeOnClient(mc -> mc.player.position());
                if (i > 5) {
                    travelled += p.distanceTo(last);
                }
                last = p;
                if (p.distanceTo(n10) < 0.3) {
                    arrived = true;
                    ctx.waitTicks(5);
                    break;
                }
                if (logHas(mark, "[AutoRoutes] Stopped")) {
                    break;
                }
            }
            for (String l : LogTap.since(mark)) {
                if (l.contains("[Path]") || l.contains("Path #") || l.contains("Stopped") || l.contains("Breaker:")
                        || l.contains("acted") || l.contains("Etherwarp: landed")) {
                    println(ALTAR, "  " + l.replaceAll("^.*?\\[(AutoRoutes|Path)\\]", "[$1]"));
                }
            }
            Vec3 end = ctx.computeOnClient(mc -> mc.player.position());
            boolean planned = logHas(mark, "Path #9 -> #10 planned by the floor planner");
            println(ALTAR, "planned " + planned + ", ended at " + fmt(end) + " (" + String.format(Locale.ROOT, "%.2f",
                    end.distanceTo(n10)) + " from node #10), " + String.format(Locale.ROOT, "%.1f", travelled)
                    + " block(s) travelled after the stack fired");
            if (!planned) {
                f.add("pair #9 -> #10 was not planned");
            }
            if (!arrived || travelled < 10) {
                f.add("he did not fly to node #10 (ended " + fmt(end) + ", " + String.format(Locale.ROOT, "%.2f",
                        end.distanceTo(n10)) + " from it, " + String.format(Locale.ROOT, "%.1f", travelled)
                        + " travelled)");
            }
        } finally {
            ctx.runOnClient(mc -> ModUnderTest.staticCall(EXECUTOR, "stop", new Class<?>[]{String.class},
                    new Object[]{"941 over"}));
            buildRoom(ctx, keep);
            F = keepF;
            giveAotv(ctx);
        }
    }

    private static JsonArray savedHopsOf(ClientGameTestContext ctx, String roomName, int index) {
        return ctx.computeOnClient(mc -> {
            try {
                Path file = (Path) ModUnderTest.staticCall(STORE, "routesFile");
                JsonObject routes = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                        .getAsJsonObject().getAsJsonObject("routes");
                JsonObject n = routes.getAsJsonObject(roomName).getAsJsonArray("nodes").get(index).getAsJsonObject();
                return n.has("hops") ? n.getAsJsonArray("hops") : new JsonArray();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static void setRealBlocks(ClientGameTestContext ctx, List<BlockPos> blocks, BlockState state) {
        AtomicReference<Boolean> done = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                for (BlockPos p : blocks) {
                    server.overworld().setBlockAndUpdate(p, state);
                }
                done.set(true);
            });
        });
        ctx.waitFor(mc -> done.get() != null, 200);
        ctx.waitTicks(5);
    }

    // ============================================================================================ 635 replan

    private static void caseReplan(ClientGameTestContext ctx, List<String> f) {
        clearArena(ctx, 4);
        setBlocks(ctx, Map.of(new int[]{15, F, 15}, Blocks.STONE.defaultBlockState(),
                new int[]{15, F + 1, 15}, Blocks.STONE.defaultBlockState()));
        settle(ctx);
        Vec3 a = rel(ctx, 6.5, F, 15.5);
        Vec3 b = rel(ctx, 24.5, F, 15.5);
        int hops = pathPair(ctx, f, a, b);
        // A second room's route with a saved plan, written straight into the file: it must lose its plan too.
        addForeignRoute(ctx);
        int savedHere = savedHops(ctx, room);
        int savedThere = savedHops(ctx, "Some Other Room");
        println(REPLAN, "before: " + savedHere + " saved warp(s) here (" + hops + " planned), " + savedThere
                + " in the other room");
        int plans0 = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(PLANNER, "plansStarted"));
        long mark = LogTap.mark();
        cmd(ctx, "/ar replan");
        boolean cleared = waitFor(ctx, 40, () -> logHas(mark, "cleared 2 saved path plan(s) in 2 room(s)"));
        boolean replanned = waitFor(ctx, 800, () -> logHas(mark, "planned by the floor planner"));
        ctx.waitTicks(10);
        int plans1 = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(PLANNER, "plansStarted"));
        int afterHere = savedHops(ctx, room);
        int afterThere = savedHops(ctx, "Some Other Room");
        println(REPLAN, "after: cleared line " + cleared + ", re-planned " + replanned + " (" + (plans1 - plans0)
                + " plan(s)), " + afterHere + " saved warp(s) here, " + afterThere + " in the other room");
        if (!cleared) {
            f.add("/ar replan did not report clearing both rooms' plans");
        }
        if (!replanned || plans1 - plans0 != 1 || afterHere < 1) {
            f.add("this room's pair was not planned again (" + (plans1 - plans0) + " plan(s), " + afterHere + " warp(s))");
        }
        if (afterThere != 0) {
            f.add("the other room's plan was not thrown away (" + afterThere + " warp(s) still saved)");
        }
    }

    private static void addForeignRoute(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            try {
                ModUnderTest.staticCall(STORE, "getInstance");
                Object store = ModUnderTest.staticCall(STORE, "getInstance");
                ModUnderTest.call(store, "save", new Class<?>[]{}, new Object[]{});
                Path file = (Path) ModUnderTest.staticCall(STORE, "routesFile");
                JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                JsonObject routes = root.getAsJsonObject("routes");
                JsonObject here = routes.getAsJsonObject(room);
                routes.add("Some Other Room", here.deepCopy());
                Files.writeString(file, root.toString(), StandardCharsets.UTF_8);
                ModUnderTest.staticCall(STORE, "reload");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        ctx.waitTicks(3);
    }

    private static int savedHops(ClientGameTestContext ctx, String roomName) {
        return ctx.computeOnClient(mc -> {
            try {
                Path file = (Path) ModUnderTest.staticCall(STORE, "routesFile");
                if (!Files.exists(file)) {
                    return 0;
                }
                JsonObject routes = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                        .getAsJsonObject().getAsJsonObject("routes");
                if (routes == null || !routes.has(roomName)) {
                    return 0;
                }
                int n = 0;
                for (JsonElement e : routes.getAsJsonObject(roomName).getAsJsonArray("nodes")) {
                    JsonObject o = e.getAsJsonObject();
                    n += o.has("hops") ? o.getAsJsonArray("hops").size() : 0;
                }
                return n;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    // ============================================================================================ 634 sweep

    private static final String[] SWEEP_ROOMS = {"Slabs", "Three Floors", "Museum", "Scaffolding"};
    private static final int PAIRS_PER_ROOM = Integer.getInteger("testkit.pathopt.pairs", 40);
    private static final int THREE_CHECKS_PER_ROOM = Integer.getInteger("testkit.pathopt.threechecks", 2);

    private static void caseSweep(ClientGameTestContext ctx, List<String> f) {
        int rooms = 0;
        int pairs = 0;
        int fewer = 0;
        int directMissed = 0;
        int twoChecked = 0;
        int threeChecked = 0;
        List<String> examples = new ArrayList<>();
        List<String> timing = new ArrayList<>();
        List<String> names = ctx.computeOnClient(mc -> new ArrayList<>((List<String>) ModUnderTest.staticCall(
                ROOM_LIBRARY, "names")));
        for (String name : SWEEP_ROOMS) {
            if (!names.contains(name)) {
                println(SWEEP, "room " + name + " is not in the library - skipped");
                continue;
            }
            buildRoom(ctx, name);
            giveAotv(ctx);
            rooms++;
            List<Vec3> spots = onServer(ctx, (mc, level) -> spotsInRoom(mc, level));
            println(SWEEP, name + ": " + spots.size() + " standable spot(s) on the 2-block grid");
            if (spots.size() < 4) {
                f.add(name + ": fewer than 4 standable spots found - the room did not build");
                continue;
            }
            Random rng = new Random(560L + name.hashCode());
            List<Vec3[]> sample = new ArrayList<>();
            for (int guard = 0; sample.size() < PAIRS_PER_ROOM && guard < PAIRS_PER_ROOM * 50; guard++) {
                Vec3 a = spots.get(rng.nextInt(spots.size()));
                Vec3 b = spots.get(rng.nextInt(spots.size()));
                if (Math.hypot(a.x - b.x, a.z - b.z) >= 6) {
                    sample.add(new Vec3[]{a, b});
                }
            }
            List<Plan> plans = planMany(ctx, sample);
            double graphMs = 0;
            double totalMs = 0;
            double maxMs = 0;
            int threesLeft = THREE_CHECKS_PER_ROOM;
            for (int i = 0; i < sample.size(); i++) {
                Plan p = plans.get(i);
                Vec3 a = sample.get(i)[0];
                BlockPos goal = BlockPos.containing(sample.get(i)[1].x, sample.get(i)[1].y - 0.5, sample.get(i)[1].z);
                pairs++;
                graphMs += p.graphMs;
                totalMs += p.totalMs;
                maxMs = Math.max(maxMs, p.totalMs);
                if (p.warps < 0) {
                    String why = onServer(ctx, (mc, level) -> oracleDirect(level, a, goal) != null ? "DIRECT" : "ok");
                    println(SWEEP, String.format(Locale.US, "  %s #%d %s -> %s: no plan (%s); ground truth one warp: %s",
                            name, i, fmt(a), goal.toShortString(), p.why, why));
                    if ("DIRECT".equals(why)) {
                        f.add(name + " #" + i + ": no plan where one warp does it");
                    }
                    continue;
                }
                if (p.graphWarps > p.warps) {
                    fewer++;
                    if (p.warps == 1) {
                        directMissed++;
                    }
                    if (examples.size() < 10) {
                        examples.add(String.format(Locale.US, "%s #%d %s -> %s: graph %d, planner %d", name, i, fmt(a),
                                goal.toShortString(), p.graphWarps, p.warps));
                    }
                }
                String replay = onServer(ctx, (mc, level) -> replay(level, a, p.hops, goal));
                if (replay != null) {
                    f.add(name + " #" + i + ": the plan does not replay: " + replay);
                    continue;
                }
                if (!p.proven) {
                    f.add(name + " #" + i + ": not proved the fewest (" + p.why + ")");
                }
                if (p.warps == 2) {
                    twoChecked++;
                    float[] d = onServer(ctx, (mc, level) -> oracleDirect(level, a, goal));
                    if (d != null) {
                        f.add(String.format(Locale.US, "%s #%d %s -> %s: planned 2 warps, but one lands (aim %.4f/%.4f)",
                                name, i, fmt(a), goal.toShortString(), d[0], d[1]));
                    }
                } else if (p.warps == 3 && threesLeft > 0) {
                    threesLeft--;
                    threeChecked++;
                    long t0 = System.nanoTime();
                    String two = onServer(ctx, (mc, level) -> noRouteOfTwo(level, a, goal, roomBox(mc, level), 16));
                    println(SWEEP, String.format(Locale.US, "  %s #%d: 3 warps; ground truth for 2 or fewer: %s (%.1f s)",
                            name, i, two, (System.nanoTime() - t0) / 1e9));
                    if (!two.startsWith("none")) {
                        f.add(name + " #" + i + ": planned 3 warps, but " + two);
                    }
                }
            }
            timing.add(String.format(Locale.US, "%s: %d pair(s), floor graph mean %.1f ms, graph + exact proof mean %.1f ms,"
                    + " max %.1f ms", name, sample.size(), graphMs / sample.size(), totalMs / sample.size(), maxMs));
        }
        timing.forEach(t -> println(SWEEP, "timing " + t));
        examples.forEach(e -> println(SWEEP, "fewer than the graph: " + e));
        println(SWEEP, String.format(Locale.US, "%d room(s), %d pair(s): the planner took fewer warps than the floor graph"
                        + " on %d (%d of them one warp); %d two-warp answer(s) checked for a single warp, %d three-warp"
                        + " answer(s) checked for two", rooms, pairs, fewer, directMissed, twoChecked, threeChecked));
        if (rooms == 0 || pairs < 20) {
            f.add("swept only " + pairs + " pair(s) in " + rooms + " room(s)");
        }
    }

    /** Standable allowed landings of the built room on a 2-block grid, as feet positions (world). Server thread. */
    private static List<Vec3> spotsInRoom(Minecraft mc, ServerLevel level) {
        int[] box = roomBox(mc, level);
        List<Vec3> out = new ArrayList<>();
        for (int x = box[0]; x <= box[1]; x += 2) {
            for (int z = box[2]; z <= box[3]; z += 2) {
                for (int y = box[4]; y <= box[5]; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (standable(level, p) && allowedLanding(level, p)) {
                        out.add(new Vec3(x + 0.5, y + 1.0, z + 0.5));
                    }
                }
            }
        }
        return out;
    }

    /** The built room's block box {x0, x1, z0, z1, y0, y1} (its map cells, F-20 .. F+30 relative). */
    private static int[] roomBox(Minecraft mc, ServerLevel level) {
        Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
        int x0 = Integer.MAX_VALUE;
        int x1 = Integer.MIN_VALUE;
        int z0 = Integer.MAX_VALUE;
        int z1 = Integer.MIN_VALUE;
        for (int i = 0; i < 121; i++) {
            BlockPos c = (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class},
                    new Object[]{i});
            int r = (Integer) ModUnderTest.call(layout, "roomAtWorld", new Class<?>[]{double.class, double.class},
                    new Object[]{c.getX() + 0.5, c.getZ() + 0.5});
            if (r >= 0) {
                x0 = Math.min(x0, c.getX() - 15);
                x1 = Math.max(x1, c.getX() + 15);
                z0 = Math.min(z0, c.getZ() - 15);
                z1 = Math.max(z1, c.getZ() + 15);
            }
        }
        int floor = 69 + (Integer) ModUnderTest.staticCall(LAYOUT, "simYOffset");
        return new int[]{x0, x1, z0, z1, floor - 20, floor + 30};
    }

    /** The arena's box (relative 3..27, F-1..F+12) in world blocks. Server thread. */
    private static int[] arenaBox(Minecraft mc) {
        Object frame = ModUnderTest.staticCall(FRAME, "current");
        BlockPos a = real(frame, 3, F - 1, 3);
        BlockPos b = real(frame, 27, F + 12, 27);
        return new int[]{Math.min(a.getX(), b.getX()), Math.max(a.getX(), b.getX()), Math.min(a.getZ(), b.getZ()),
                Math.max(a.getZ(), b.getZ()), a.getY(), b.getY()};
    }

    // ============================================================================================ the planner

    /** One plan: warps (-1 none), the floor graph's, proved or not, the hops {x, y, z, yaw, pitch}, times. */
    private static final class Plan {
        int warps = -1;
        int graphWarps = -1;
        boolean proven;
        String why = "";
        double graphMs;
        double totalMs;
        final List<double[]> hops = new ArrayList<>();

        @Override
        public String toString() {
            return String.format(Locale.US, "%d warp(s) (floor graph %d, %s, %s; graph %.1f ms, total %.1f ms)", warps,
                    graphWarps, proven ? "proved fewest" : "NOT proved", why, graphMs, totalMs);
        }
    }

    private static Plan plan(ClientGameTestContext ctx, Vec3 from, BlockPos to) {
        return planMany(ctx, List.<Vec3[]>of(new Vec3[]{from, new Vec3(to.getX() + 0.5, to.getY() + 1.0,
                to.getZ() + 0.5)})).get(0);
    }

    /** Plans every pair (feet -> feet, the goal the block under the second) on the planner thread, as a path pair is. */
    private static List<Plan> planMany(ClientGameTestContext ctx, List<Vec3[]> pairs) {
        AtomicReference<List<Plan>> out = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            Object cfg = ModUnderTest.staticCall(CLEAR, "pathConfig");
            double range = (Double) ModUnderTest.staticCall(CLEAR, "hopRange");
            long budget = (Long) field(PATHFINDER, "PATH_EXACT_NANOS");
            Runnable task = () -> {
                List<Plan> res = new ArrayList<>();
                for (Vec3[] pr : pairs) {
                    Plan p = new Plan();
                    BlockPos goal = BlockPos.containing(pr[1].x, pr[1].y - 0.5, pr[1].z);
                    long t0 = System.nanoTime();
                    try {
                        List<?> path = (List<?>) ModUnderTest.staticCall(PATHFINDER, "findOptimalPath",
                                new Class<?>[]{Vec3.class, BlockPos.class, cfg.getClass(), double.class,
                                        layout.getClass(), long.class},
                                new Object[]{pr[0], goal, cfg, range, layout, budget});
                        p.totalMs = (System.nanoTime() - t0) / 1e6;
                        Object info = field(PATHFINDER, "lastExact");
                        if (info != null) {
                            p.graphWarps = (Integer) ModUnderTest.call(info, "graphWarps", new Class<?>[]{}, new Object[]{});
                            p.proven = (Boolean) ModUnderTest.call(info, "proven", new Class<?>[]{}, new Object[]{});
                            p.graphMs = (Double) ModUnderTest.call(info, "graphMs", new Class<?>[]{}, new Object[]{});
                            p.why = (String) ModUnderTest.call(info, "why", new Class<?>[]{}, new Object[]{});
                        }
                        Object prover = field(PATHFINDER, "lastProver");
                        if (prover != null) {
                            // Not proved in its planner-thread turn: the proof carries on with no deadline (as
                            // RoutePathPlanner runs it on its own thread) - run it here and take its answer.
                            List<?> shorter = (List<?>) ModUnderTest.call(prover, "run", new Class<?>[]{},
                                    new Object[]{});
                            p.proven = (Boolean) ModUnderTest.call(prover, "proven", new Class<?>[]{}, new Object[]{});
                            p.why = "proved in the background";
                            if (shorter != null) {
                                path = shorter;
                            }
                            p.totalMs = (System.nanoTime() - t0) / 1e6;
                        }
                        if (path != null) {
                            p.warps = path.size();
                            for (Object n : path) {
                                Class<?> c = n.getClass();
                                p.hops.add(new double[]{c.getField("x").getDouble(n), c.getField("y").getDouble(n),
                                        c.getField("z").getDouble(n), c.getField("yaw").getFloat(n),
                                        c.getField("pitch").getFloat(n)});
                            }
                        }
                    } catch (Throwable e) {
                        p.why = "threw " + e;
                    }
                    res.add(p);
                }
                out.set(res);
            };
            ModUnderTest.staticCall(CLEAR, "onPlanner", new Class<?>[]{Runnable.class}, new Object[]{task});
        });
        for (int i = 0; i < 20 * 60 * 15 && out.get() == null; i++) {
            ctx.waitTicks(1);
        }
        if (out.get() == null) {
            throw new AssertionError("the planner did not answer " + pairs.size() + " plan(s) in 15 minutes");
        }
        return out.get();
    }

    // ============================================================================================ Auto Routes

    /**
     * Makes a path pair the way he does - stands at {@code a} and types /ar add path, stands at {@code b} and types it
     * again - and waits for the plan. Returns the saved warps (or -1).
     */
    private static int pathPair(ClientGameTestContext ctx, List<String> f, Vec3 a, Vec3 b) {
        return pathPair(ctx, f, a, b, true);
    }

    /** {@code reset} false keeps the route file as it is (637's breaker node stays node #1). */
    private static int pathPair(ClientGameTestContext ctx, List<String> f, Vec3 a, Vec3 b, boolean reset) {
        if (reset) {
            resetRoutes(ctx);
        }
        place(ctx, a);
        cmd(ctx, "/ar add path");
        place(ctx, b);
        long mark = LogTap.mark();
        cmd(ctx, "/ar add path");
        boolean planned = waitFor(ctx, 800, () -> logHas(mark, "planned by the floor planner")
                || logHas(mark, "not planned"));
        for (String l : LogTap.since(mark)) {
            if (l.contains("[AutoRoutes] Path") || l.contains("[Path] exact")) {
                println("path pair", "  " + l.replaceAll("^.*?\\[(AutoRoutes|Path)\\]", "[$1]"));
            }
        }
        if (!planned) {
            f.add("adding the second path node did not plan the pair");
            return -1;
        }
        return savedHops(ctx, room);
    }

    /** Stands him off the route, then on node #1 (which fires the pair); returns where he ends after it flew. */
    private static Vec3 fly(ClientGameTestContext ctx, Vec3 start, Vec3 goal, int ticks) {
        return flyWatching(ctx, start, goal, ticks, new ArrayList<>());
    }

    private static Vec3 flyWatching(ClientGameTestContext ctx, Vec3 start, Vec3 goal, int ticks, List<Vec3> seen) {
        Vec3 off = rel(ctx, 4.5, F, 4.5);
        place(ctx, off);
        ctx.waitTicks(10);
        long mark = LogTap.mark();
        // Onto node #1 without waiting to stand there: the pair fires the moment he arrives, and every tick of the flight
        // is sampled from here.
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            UUID me = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(me);
                if (sp != null) {
                    sp.teleportTo(server.overworld(), start.x, start.y, start.z,
                            java.util.Set.of(net.minecraft.world.entity.Relative.Y_ROT,
                                    net.minecraft.world.entity.Relative.X_ROT), 0f, 0f, false);
                }
            });
        });
        Vec3 last = null;
        int still = 0;
        for (int i = 0; i < ticks; i++) {
            ctx.waitTicks(1);
            Vec3 p = ctx.computeOnClient(mc -> mc.player.position());
            seen.add(p);
            if (goal != null && p.distanceTo(goal) < 0.3) {
                ctx.waitTicks(5);
                break;
            }
            still = last != null && p.distanceTo(last) < 1e-4 ? still + 1 : 0;
            last = p;
            if (goal == null && logHas(mark, "(path arrival)") && still > 20) {
                break;
            }
        }
        for (String l : LogTap.since(mark)) {
            if (l.contains("[AutoRoutes]") && (l.contains("PATH") || l.contains("path") || l.contains("warp"))) {
                println("fly", "  " + l.replaceAll("^.*?\\[AutoRoutes\\]", "[AutoRoutes]"));
            }
        }
        return ctx.computeOnClient(mc -> mc.player.position());
    }

    private static void resetRoutes(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            if (mc.level == null) {
                return;
            }
            McCompat.setScreen(mc, null);
            ModUnderTest.staticCall(EXECUTOR, "stop", new Class<?>[]{String.class}, new Object[]{"test reset"});
            try {
                Path file = (Path) ModUnderTest.staticCall(STORE, "routesFile");
                JsonObject root = new JsonObject();
                root.addProperty("version", 1);
                root.add("routes", new JsonObject());
                Files.createDirectories(file.getParent());
                Files.writeString(file, root.toString(), StandardCharsets.UTF_8);
                ModUnderTest.staticCall(STORE, "reload");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        ctx.waitTicks(2);
    }

    private static void cmd(ClientGameTestContext ctx, String line) {
        ctx.runOnClient(mc -> {
            net.minecraft.client.gui.screens.ChatScreen screen = new net.minecraft.client.gui.screens.ChatScreen("", false);
            McCompat.setScreen(mc, screen);
            screen.handleChatInput(line, true);
            if (McCompat.screen(mc) == screen) {
                McCompat.setScreen(mc, null);
            }
        });
        ctx.waitTicks(2);
    }

    // ============================================================================================ the ground truth

    private static Method lookM;
    private static Method walkM;
    private static Method okM;
    private static Method posM;
    private static Method standM;

    private static void reflect() throws Exception {
        if (lookM == null) {
            Class<?> tu = Class.forName(TELEPORT_UTILS);
            lookM = tu.getMethod("getLook", float.class, float.class);
            walkM = tu.getMethod("traverseVoxels", Level.class, double.class, double.class, double.class, double.class,
                    double.class, double.class, boolean.class);
            Class<?> rr = Class.forName(TELEPORT_UTILS + "$RaycastResult");
            okM = rr.getMethod("succeeded");
            posM = rr.getMethod("pos");
            standM = Class.forName(OVERLAY).getMethod("standYOn", Level.class, BlockPos.class);
        }
    }

    private static double range() {
        return (Double) ModUnderTest.staticCall(CLEAR, "hopRange");
    }

    /** The block a real etherwarp aimed (yaw, pitch) from the eye lands on, or null. Server thread. */
    private static BlockPos cast(ServerLevel level, double ex, double ey, double ez, float yaw, float pitch, double range)
            throws Exception {
        reflect();
        Vec3 look = (Vec3) lookM.invoke(null, yaw, pitch);
        Object r = walkM.invoke(null, level, ex, ey, ez, ex + look.x * range, ey + look.y * range, ez + look.z * range,
                true);
        return (Boolean) okM.invoke(r) ? (BlockPos) posM.invoke(r) : null;
    }

    private static float wrap(float v) {
        float f = v % 360.0f;
        if (f >= 180.0f) {
            f -= 360.0f;
        }
        if (f < -180.0f) {
            f += 360.0f;
        }
        return f;
    }

    /**
     * Ground truth for one warp: any aim at a point of a 32 x 32 grid on each face of {@code target} turned toward the
     * eye (sneaking eye over {@code feet}) whose real etherwarp lands on it, and still does with the yaw or the pitch
     * 0.01 degrees off. Returns {yaw, pitch}, or null. Server thread.
     */
    private static float[] oracleDirect(ServerLevel level, Vec3 feet, BlockPos target) {
        return oracleDirect(level, feet, target, 32);
    }

    private static float[] oracleDirect(ServerLevel level, Vec3 feet, BlockPos target, int grid) {
        try {
            double range = range();
            double ex = feet.x;
            double ey = feet.y + 1.27;
            double ez = feet.z;
            int bx = target.getX();
            int by = target.getY();
            int bz = target.getZ();
            double nx = Math.max(bx, Math.min(ex, bx + 1.0)) - ex;
            double ny = Math.max(by, Math.min(ey, by + 1.0)) - ey;
            double nz = Math.max(bz, Math.min(ez, bz + 1.0)) - ez;
            if (nx * nx + ny * ny + nz * nz > range * range) {
                return null;
            }
            double in = 0.004;
            // The face centres first (the common hit), then the whole grid.
            for (int pass = 0; pass < 2; pass++) {
                int cells = pass == 0 ? 1 : grid;
                for (int axis = 0; axis < 3; axis++) {
                    double e = axis == 0 ? ex : axis == 1 ? ey : ez;
                    int b = axis == 0 ? bx : axis == 1 ? by : bz;
                    for (int side = 0; side < 2; side++) {
                        if (side == 0 ? e >= b : e <= b + 1) {
                            continue;
                        }
                        double plane = side == 0 ? in : 1 - in;
                        for (int i = 0; i < cells; i++) {
                            for (int j = 0; j < cells; j++) {
                                double u = in + (1 - 2 * in) * (i + 0.5) / cells;
                                double v = in + (1 - 2 * in) * (j + 0.5) / cells;
                                double px = bx + (axis == 0 ? plane : u);
                                double py = by + (axis == 1 ? plane : axis == 0 ? u : v);
                                double pz = bz + (axis == 2 ? plane : v);
                                double dx = px - ex;
                                double dy = py - ey;
                                double dz = pz - ez;
                                float yaw = wrap((float) -Math.toDegrees(Math.atan2(dx, dz)));
                                float pitch = wrap((float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
                                if (!target.equals(cast(level, ex, ey, ez, yaw, pitch, range))) {
                                    continue;
                                }
                                boolean holds = target.equals(cast(level, ex, ey, ez, yaw + 0.01f, pitch, range))
                                        && target.equals(cast(level, ex, ey, ez, yaw - 0.01f, pitch, range))
                                        && target.equals(cast(level, ex, ey, ez, yaw, pitch + 0.01f, range))
                                        && target.equals(cast(level, ex, ey, ez, yaw, pitch - 0.01f, range));
                                if (holds) {
                                    return new float[]{yaw, pitch};
                                }
                            }
                        }
                    }
                }
            }
            return null;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Where an etherwarp onto {@code b} puts his feet: its collision top + 0.05 (the sim server's rule). */
    private static Vec3 standOn(ServerLevel level, BlockPos b) {
        try {
            reflect();
            double y = (Double) standM.invoke(null, level, b);
            return new Vec3(b.getX() + 0.5, y + 0.05, b.getZ() + 0.5);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Solid, with two blocks of nothing to collide with over it. */
    private static boolean standable(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        if (s.isAir() || s.getCollisionShape(level, p).isEmpty()) {
            return false;
        }
        return level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()
                && level.getBlockState(p.above(2)).getCollisionShape(level, p.above(2)).isEmpty()
                && !level.getBlockState(p.above()).getFluidState().isSource();
    }

    /**
     * The landing rule on the way, as killer560 set it (2026-10-11): only a block whose collision shape has a completely
     * flat, full top face, not a carpet / wall / fence / gate / hopper / cauldron / banner, and above the dungeon floor
     * + 6 only under cover (the sim's roof rule).
     */
    private static boolean allowedLanding(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        var block = s.getBlock();
        var shape = s.getCollisionShape(level, p);
        boolean fullTop = !shape.isEmpty() && net.minecraft.world.level.block.Block.isFaceFull(shape, Direction.UP);
        if (!fullTop || block instanceof net.minecraft.world.level.block.CarpetBlock
                || block instanceof net.minecraft.world.level.block.WallBlock
                || block instanceof net.minecraft.world.level.block.FenceBlock
                || block instanceof net.minecraft.world.level.block.FenceGateBlock
                || block instanceof net.minecraft.world.level.block.HopperBlock
                || block instanceof net.minecraft.world.level.block.CauldronBlock
                || block instanceof net.minecraft.world.level.block.BannerBlock) {
            return false;
        }
        int floor = 69 + (Integer) ModUnderTest.staticCall(LAYOUT, "simYOffset");
        if (p.getY() > floor + 6) {
            for (int y = p.getY() + 3; y <= Math.min(level.getMaxY(), p.getY() + 64); y++) {
                if (!level.getBlockState(new BlockPos(p.getX(), y, p.getZ())).isAir()) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    /** Replays a plan with real etherwarps: null when it lands hop by hop and ends on the goal, else why not. */
    private static String replay(ServerLevel level, Vec3 start, List<double[]> hops, BlockPos goal) {
        try {
            double range = range();
            Vec3 at = start;
            BlockPos land = null;
            for (int i = 0; i < hops.size(); i++) {
                double[] h = hops.get(i);
                if (Math.abs(h[0] - at.x) > 0.01 || Math.abs(h[2] - at.z) > 0.01 || Math.abs(h[1] - at.y) > 0.06) {
                    return "warp " + (i + 1) + " starts at " + fmt(new Vec3(h[0], h[1], h[2])) + ", the last landed "
                            + fmt(at);
                }
                land = cast(level, at.x, at.y + 1.27, at.z, (float) h[3], (float) h[4], range);
                if (land == null) {
                    return "warp " + (i + 1) + " lands nowhere";
                }
                if (i < hops.size() - 1 && !allowedLanding(level, land)) {
                    return "warp " + (i + 1) + " lands on " + level.getBlockState(land) + " at " + land.toShortString()
                            + ", which is off limits on the way";
                }
                at = standOn(level, land);
            }
            return goal.equals(land) ? null : "ends on " + (land == null ? "nothing" : land.toShortString()) + ", not "
                    + goal.toShortString();
        } catch (Exception e) {
            return "threw " + e;
        }
    }

    /**
     * Ground truth for "no route of two warps or fewer": no single warp onto the goal, and no allowed landing in the box
     * that one warp from the start reaches and one more warp from there reaches the goal from. {@code grid} is the
     * first warp's face grid (the second is always 32). Returns "none ..." or the route found.
     */
    private static String noRouteOfTwo(ServerLevel level, Vec3 start, BlockPos goal, int[] box, int grid) {
        float[] d = oracleDirect(level, start, goal);
        if (d != null) {
            return "ONE warp lands (aim " + d[0] + "/" + d[1] + ")";
        }
        int tried = 0;
        int reached = 0;
        for (int x = box[0]; x <= box[1]; x++) {
            for (int z = box[2]; z <= box[3]; z++) {
                for (int y = box[4]; y <= box[5]; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (!standable(level, p) || !allowedLanding(level, p)) {
                        continue;
                    }
                    tried++;
                    if (oracleDirect(level, start, p, grid) == null) {
                        continue;
                    }
                    reached++;
                    if (oracleDirect(level, standOn(level, p), goal) != null) {
                        return "TWO warps land, via " + p.toShortString();
                    }
                }
            }
        }
        return "none (" + tried + " landing(s) in the box, " + reached + " of them one warp from the start)";
    }

    // ============================================================================================ world

    private interface ServerJob<T> {
        T run(Minecraft mc, ServerLevel level) throws Exception;
    }

    private static <T> T onServer(ClientGameTestContext ctx, ServerJob<T> job) {
        AtomicReference<Object[]> out = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                try {
                    out.set(new Object[]{job.run(mc, server.overworld()), null});
                } catch (Throwable t) {
                    out.set(new Object[]{null, t});
                }
            });
        });
        for (int i = 0; i < 20 * 60 * 10 && out.get() == null; i++) {
            ctx.waitTicks(1);
        }
        Object[] r = out.get();
        if (r == null) {
            throw new AssertionError("the server did not answer in 10 minutes");
        }
        if (r[1] != null) {
            throw new AssertionError("server job threw " + r[1], (Throwable) r[1]);
        }
        @SuppressWarnings("unchecked")
        T t = (T) r[0];
        return t;
    }

    private static Vec3 rel(ClientGameTestContext ctx, double x, double y, double z) {
        return ctx.computeOnClient(mc -> {
            Object frame = ModUnderTest.staticCall(FRAME, "current");
            return (Vec3) ModUnderTest.staticCall(COORDS, "toReal", new Class<?>[]{frame.getClass(), double.class,
                    double.class, double.class}, new Object[]{frame, x, y, z});
        });
    }

    private static BlockPos relBlock(ClientGameTestContext ctx, int x, int y, int z) {
        return ctx.computeOnClient(mc -> real(ModUnderTest.staticCall(FRAME, "current"), x, y, z));
    }

    private static BlockPos real(Object frame, int x, int y, int z) {
        return (BlockPos) ModUnderTest.staticCall(COORDS, "toRealBlock", new Class<?>[]{frame.getClass(), BlockPos.class},
                new Object[]{frame, new BlockPos(x, y, z)});
    }

    /** Relative x,z 3..27: stone floor at F-1, air F..F+14, stone walls round it {@code wallHigh} blocks high. */
    private static void clearArena(ClientGameTestContext ctx, int wallHigh) {
        Map<int[], BlockState> set = new LinkedHashMap<>();
        for (int x = 3; x <= 27; x++) {
            for (int z = 3; z <= 27; z++) {
                boolean edge = x == 3 || x == 27 || z == 3 || z == 27;
                set.put(new int[]{x, F - 1, z}, Blocks.STONE.defaultBlockState());
                for (int y = F; y <= F + 14; y++) {
                    // The top course of a high wall is a cobblestone wall (the landing blacklist): a wall top is
                    // otherwise a landing wherever the room has a roof over it (632's first ground truth went over it).
                    BlockState wall = wallHigh > 6 && y == F + wallHigh - 1 ? Blocks.COBBLESTONE_WALL.defaultBlockState()
                            : Blocks.STONE.defaultBlockState();
                    set.put(new int[]{x, y, z}, edge && y < F + wallHigh ? wall : Blocks.AIR.defaultBlockState());
                }
            }
        }
        setBlocks(ctx, set);
    }

    private static void setBlocks(ClientGameTestContext ctx, Map<int[], BlockState> blocks) {
        AtomicReference<Boolean> done = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            Object frame = ModUnderTest.staticCall(FRAME, "current");
            Map<BlockPos, BlockState> set = new LinkedHashMap<>();
            for (var e : blocks.entrySet()) {
                set.put(real(frame, e.getKey()[0], e.getKey()[1], e.getKey()[2]), e.getValue());
            }
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                for (var e : set.entrySet()) {
                    server.overworld().setBlockAndUpdate(e.getKey(), e.getValue());
                }
                done.set(true);
            });
        });
        ctx.waitFor(mc -> done.get() != null, 200);
        ctx.waitTicks(3);
    }

    /** Lets the client receive the blocks and the planner's grid hear about them. */
    private static void settle(ClientGameTestContext ctx) {
        ctx.waitTicks(40);
    }

    /** Server teleport onto {@code feet}, then waits until the client stands there. */
    private static boolean place(ClientGameTestContext ctx, Vec3 feet) {
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            UUID me = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(me);
                if (sp != null) {
                    sp.teleportTo(server.overworld(), feet.x, feet.y, feet.z,
                            java.util.Set.of(net.minecraft.world.entity.Relative.Y_ROT,
                                    net.minecraft.world.entity.Relative.X_ROT), 0f, 0f, false);
                }
            });
        });
        for (int i = 0; i < 100 && !ctx.computeOnClient(mc -> mc.player.onGround()
                && mc.player.position().distanceTo(feet) < 0.1); i++) {
            ctx.waitTicks(1);
        }
        ctx.waitTicks(5);
        return ctx.computeOnClient(mc -> mc.player.position().distanceTo(feet) < 0.1);
    }

    private static void giveAotv(ClientGameTestContext ctx) {
        AtomicReference<Boolean> given = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            UUID uuid = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(uuid);
                var inv = sp.getInventory();
                for (int i = 0; i < 36; i++) {
                    inv.setItem(i, ItemStack.EMPTY);
                }
                inv.setItem(0, (ItemStack) ModUnderTest.staticCall(SIM_ITEMS, "build", new Class<?>[]{String.class},
                        new Object[]{"ASPECT_OF_THE_VOID"}));
                given.set(true);
            });
        });
        ctx.waitFor(mc -> given.get() != null, 200);
        ctx.waitTicks(10);
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
        ctx.waitTicks(5);
    }

    /** A 1x1 room with secrets that is not a puzzle, trap, blood, entrance or fairy room (96-ar's choice). */
    private static String pickRoom(ClientGameTestContext ctx) {
        Map<String, JsonObject> db = new HashMap<>();
        try {
            Path file = ModUnderTest.modConfig("killer560smod-roomdata").resolve("rooms-modern.json");
            for (JsonElement e : JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonArray()) {
                db.put(e.getAsJsonObject().get("name").getAsString(), e.getAsJsonObject());
            }
        } catch (Exception e) {
            throw new AssertionError("could not read the room database: " + e);
        }
        @SuppressWarnings("unchecked")
        List<String> names = ctx.computeOnClient(mc -> new ArrayList<>((List<String>) ModUnderTest.staticCall(ROOM_LIBRARY,
                "names")));
        for (String n : names) {
            JsonObject o = db.get(n);
            if (o == null) {
                continue;
            }
            String type = o.has("type") ? o.get("type").getAsString().toUpperCase(Locale.ROOT) : "";
            int secrets = o.has("secrets") ? o.get("secrets").getAsInt() : 0;
            if (secrets < 1 || type.equals("PUZZLE") || type.equals("TRAP") || type.equals("BLOOD")
                    || type.equals("ENTRANCE") || type.equals("FAIRY") || n.contains("Maze") || n.contains("Trap")) {
                continue;
            }
            int[] size = roomSize(ctx, n);
            if (size[0] <= 33 && size[1] <= 33) {
                return n;
            }
        }
        throw new AssertionError("no 1x1 room with secrets in the library");
    }

    private static int[] roomSize(ClientGameTestContext ctx, String n) {
        return ctx.computeOnClient(mc -> {
            Object r = ModUnderTest.staticCall(ROOM_LIBRARY, "get", new Class<?>[]{String.class}, new Object[]{n});
            try {
                return new int[]{r.getClass().getField("sizeX").getInt(r), r.getClass().getField("sizeZ").getInt(r)};
            } catch (ReflectiveOperationException e) {
                return new int[]{32, 32};
            }
        });
    }

    /** Loads {@code name} alone through a map code (rotation 0), as 96-ar does, and starts the run. */
    private static void buildRoom(ClientGameTestContext ctx, String name) {
        int[] cellRoom = new int[121];
        java.util.Arrays.fill(cellRoom, -1);
        int[] cellDoor = new int[121];
        int[] cellRotation = new int[121];
        int[] size = roomSize(ctx, name);
        int sx = Math.max(1, (int) Math.round(size[0] / 32.0));
        int sz = Math.max(1, (int) Math.round(size[1] / 32.0));
        for (int gz = 4; gz <= 4 + 2 * (sz - 1); gz++) {
            for (int gx = 4; gx <= 4 + 2 * (sx - 1); gx++) {
                cellRoom[gz * 11 + gx] = 0;
            }
        }
        String code = ctx.computeOnClient(mc -> {
            try {
                Class<?> dec = Class.forName(MAP_CODE + "$Decoded");
                Object d = dec.getConstructor(String[].class, int[].class, int[].class, int[].class)
                        .newInstance(new String[]{name}, cellRoom, cellDoor, cellRotation);
                return (String) ModUnderTest.staticCall(MAP_CODE, "encodeDecoded", new Class<?>[]{dec}, new Object[]{d});
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        });
        if (ctx.computeOnClient(mc -> mc.level == null)) {
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter", new Class<?>[]{String.class},
                    new Object[]{"gametest"}));
        }
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> ModUnderTest.staticCall(BUILDER, "build",
                new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, code})));
        ctx.waitFor(mc -> mc.level != null && mc.player != null, 2400);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitFor(mc -> McCompat.screen(mc) == null, 1200);
        ctx.waitFor(mc -> mc.player.onGround() && ModUnderTest.staticCall(FRAME, "current") != null, 1200);
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimRun", "begin",
                new Class<?>[]{Minecraft.class, BlockPos.class},
                new Object[]{mc, ModUnderTest.staticCall(BUILDER, "entranceDoor")}));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.roomsim.SimRun", "hasStarted"), 400);
        ctx.waitTicks(40);
        Vec3 r = ctx.computeOnClient(mc -> {
            Object frame = ModUnderTest.staticCall(FRAME, "current");
            return (Vec3) ModUnderTest.staticCall(COORDS, "toRelative", new Class<?>[]{frame.getClass(), Vec3.class},
                    new Object[]{frame, mc.player.position()});
        });
        F = (int) Math.floor(r.y + 0.01);
        println("build", "built " + name + "; frame " + ctx.computeOnClient(mc -> String.valueOf(
                ModUnderTest.staticCall(FRAME, "current"))) + "; floor y " + F + " (relative)");
    }

    // ============================================================================================ small things

    private static boolean waitFor(ClientGameTestContext ctx, int ticks, Supplier<Boolean> cond) {
        for (int i = 0; i < ticks; i++) {
            if (cond.get()) {
                return true;
            }
            ctx.waitTicks(1);
        }
        return cond.get();
    }

    private static boolean logHas(long mark, String text) {
        for (String l : LogTap.since(mark)) {
            if (l.contains(text)) {
                return true;
            }
        }
        return false;
    }

    private static Object field(String className, String name) {
        try {
            return Class.forName(className).getField(name).get(null);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static String fmt(Vec3 v) {
        return v == null ? "null" : String.format(Locale.US, "(%.2f, %.2f, %.2f)", v.x, v.y, v.z);
    }

    private static void println(String name, String s) {
        System.out.println("[" + name + "] " + s);
    }
}
