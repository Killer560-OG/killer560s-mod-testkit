package dev.testkit.gametest;

import dev.testkit.compat.McCompat;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Does a map click on a whole sim floor actually etherwarp him into the clicked room, in few warps and few ms?
 *
 * <p>killer560 (2026-10-04): "Get it to the point where it is only a few ms every time and prioritize using as few
 * warps as physically possible." The floor-wide planner ({@code WarpGraph}) is measured outside the game by
 * {@code tools/bench/FloorBench}; this is the in-game half: a generated F7 in the sim, an Aspect of the Void in hand,
 * the Interactive Map on, and the same call a map press makes ({@code AutoClearUtils.pathToRoom}) for several rooms
 * across the floor. Every claim is checked against what happened, not against the planner's word:
 * <ul>
 *   <li>he MOVED: straight-line distance from where the press started to where he ended, which must be over 10
 *       blocks for a room that is not his own;</li>
 *   <li>he ended in the clicked tile (its 32 x 32 column);</li>
 *   <li>the planner's own line says how many warps and how many ms, and the executor's "running N warp(s)" line says
 *       the hops were issued; no press may fall back to the room-by-room planner or go "off the plan".</li>
 * </ul>
 * Then one more press right after a block is placed in the world (a change under the warm graph, the case that fell
 * back to room by room with 39-40 warps in his 2026-10-04 log): it must plan on the graph, not room by room.
 */
public class SimMapWarpTests implements FabricClientGameTest {

    private static final String NAME = "95-sim-map-warp";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String SIM_ITEMS = "com.killer560.hub.roomsim.SimItems";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String LIVE_MAP = "com.killer560.hub.livemap.LiveMapFeature";
    private static final String LIVE_MAP_CONFIG = "com.killer560.hub.livemap.LiveMapConfig";
    private static final String CLEAR_UTILS = "com.killer560.hub.livemap.autoclear.AutoClearUtils";
    private static final String EXECUTOR = "com.killer560.hub.livemap.autoclear.ClearExecutor";
    private static final String AUTO_CONFIG = "com.killer560.hub.autopuzzles.AutoPuzzlesConfig";
    private static final int GRID = 11;
    private static final int PRESSES = 6;

    private static final Pattern PLANNED = Pattern.compile("\\[Path\\] (\\d+) warp\\(s\\) \\(([^)]*)\\).*?total ([0-9.]+) ms");

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
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomdatabase.RoomDatabase", "ensureLoading"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.roomdatabase.RoomDatabase", "isReady"));
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"));
        // Every auto off: nothing but the map press may move him.
        ctx.runOnClient(mc -> {
            Object auto = ModUnderTest.config(AUTO_CONFIG);
            ModUnderTest.set(auto, "setAutoPuzzlesMasterEnabled", false);
            Object map = ModUnderTest.config(LIVE_MAP_CONFIG);
            ModUnderTest.set(map, "setEnabled", true);
            // OFF until the floor is on the client - see warmQuickOnly below.
            ModUnderTest.set(map, "setInteractiveMapEnabled", false);
        });

        // The test client runs at render distance 5, so a far room's chunks never reach it and the map press
        // cannot even find a block to aim for ("Couldn't find goal position"). A floor is 12 chunks across;
        // Hypixel sends a whole dungeon. Put it back afterwards for the scenarios that follow.
        int renderBefore = ctx.computeOnClient(mc -> mc.options.renderDistance().get());
        ctx.runOnClient(mc -> mc.options.renderDistance().set(16));
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter", new Class<?>[]{String.class},
                new Object[]{"gametest"}));
        long before = Scenario.simBuildCount(ctx);
        long mark0 = LogTap.mark();
        ctx.runOnClient(mc -> mc.execute(() -> {
            Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
            ModUnderTest.staticCall(FLOOR_GEN, "generate",
                    new Class<?>[]{Minecraft.class, floor.getClass(), int.class, int.class},
                    new Object[]{mc, floor, 3, 4});
        }));
        ctx.waitFor(mc -> mc.level != null);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitTicks(80);
        // The early press below must be planned on a warm QUICK graph of the whole floor while the FULL graph is
        // still cold - that is the floor's first warm-up. Both warm whenever the Interactive Map is on in a dungeon
        // (ClearExecutor -> EtherwarpPathfinder.tickWarm), so with it on from the start the premise was a race
        // (2026-10-05/06): the full graph (~5-9 s) sometimes finished during the run's 5 s countdown, and on a
        // loaded machine the quick graph first warmed on the ~120 landings present before the floor's chunks
        // arrived (behind the sealed entrance a warm-up sees only the entrance), so at GO the press had to warm it
        // inside its 600 ms and, on a loaded machine, fell to room by room. Now the map stays off until every chunk
        // of the floor is on the client and the gate is open, is switched on until the quick graph reports warm on
        // the floor, and is switched off again (pausing the full graph) until the early press resumes it.
        awaitFloorChunks(ctx);

        List<String> failures = new ArrayList<>();
        try {
            body(ctx, mark0, failures);
        } catch (Throwable t) {
            failures.add("scenario threw: " + t);
        }

        ctx.runOnClient(mc -> mc.options.renderDistance().set(renderBefore));
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "leave"));
        ctx.runOnClient(mc -> mc.execute(() -> {
            if (mc.level != null) {
                mc.level.disconnect(net.minecraft.network.chat.Component.literal("scenario over"));
                mc.disconnectWithSavingScreen();
            }
        }));
        ctx.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null);
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> mc.execute(() ->
                McCompat.setScreen(mc, new net.minecraft.client.gui.screens.TitleScreen())));
        ctx.waitFor(mc -> McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.TitleScreen);
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " problem(s): " + String.join("; ", failures));
        }
        System.out.println("[" + NAME + "] PASS");
    }

    private static void body(ClientGameTestContext ctx, long mark0, List<String> failures) {
        // ---- the Aspect of the Void in hand -------------------------------------------------------------
        AtomicReference<Boolean> given = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(uuid);
                if (sp == null) {
                    given.set(false);
                    return;
                }
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
        ctx.waitTicks(10);
        String held = ctx.computeOnClient(mc -> mc.player.getMainHandItem().getHoverName().getString());
        println("in hand: " + held);
        if (!held.contains("Aspect of the Void")) {
            failures.add("the Aspect of the Void never reached his hand (" + held + ")");
            return;
        }

        // ---- the presses' rooms, chosen while he is still in the entrance ---------------------------------
        List<int[]> targets = new ArrayList<>();   // {tileIdx, room}
        int[] entrance = {-1, -1};                  // {tileIdx, room}
        ctx.runOnClient(mc -> chooseTargets(targets, entrance));
        println(targets.size() + " room(s) to press; entrance tile " + entrance[0] + " room " + entrance[1]);

        // ---- the run starts: the entrance gate opens (a block change, like every door on Hypixel) ---------
        // Until then the entrance is sealed and nothing outside it can be reached by anyone.
        long markGo = LogTap.mark();
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimRun", "begin",
                new Class<?>[]{Minecraft.class, BlockPos.class},
                new Object[]{mc, ModUnderTest.staticCall("com.killer560.hub.roomsim.SimBuilder", "entranceDoor")}));
        for (int i = 0; i < 400 && !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(
                "com.killer560.hub.roomsim.SimRun", "isRunning")); i++) {
            ctx.waitTicks(1);
        }
        ctx.waitTicks(3);   // the gate's block updates reach the client
        warmQuickOnly(ctx);

        // ---- a press during the floor's FIRST warm-up (the gate just opened the whole floor) -------------
        // Before 2026-10-05 this got 40 ms on the half-built graph and then the room-by-room planner (about twice
        // the warps). Now it is planned on the quick graph (FloorGraphs), which warms first in about half a second.
        // It goes to the room farthest from him, so it crosses the whole not-yet-warm floor.
        String early = null;
        int[] far = targets.isEmpty() ? null : farthest(ctx, targets);
        if (far != null) {
            if (QUICK_NOT_WARM[0]) {
                failures.add("the quick floor graph never reported warm on the whole floor after the gate opened -"
                        + " the early press cannot test the first warm-up");
            }
            if (FULL_WARM_BEFORE_PAUSE[0]) {
                failures.add("the full floor graph was warm before the warm-up could be paused - the early press"
                        + " cannot test the first warm-up");
            }
            early = press(ctx, far[0], far[1], "press during the first warm-up", failures, true);
            // He is in that room now; a later press on it would be "already there" and move nothing.
            targets.remove(far);
            UNREACHABLE[0] -= LAST_UNREACHABLE[0] ? 1 : 0;   // out of targets, so not counted against them
            // The farthest room can be behind a locked door (wither/blood): no planner can get there and press()
            // counts it as unreachable. Pause the warm-up again at once (that press ran it for well under a second
            // of the full graph's 5-9 s) and take the next farthest, at most twice more.
            for (int retry = 0; early == null && LAST_UNREACHABLE[0] && retry < 2 && !targets.isEmpty(); retry++) {
                ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(LIVE_MAP_CONFIG),
                        "setInteractiveMapEnabled", false));
                far = farthest(ctx, targets);
                early = press(ctx, far[0], far[1], "press during the first warm-up (next farthest)", failures, true);
                targets.remove(far);
                UNREACHABLE[0] -= LAST_UNREACHABLE[0] ? 1 : 0;   // out of targets, so not counted against them
            }
            if (early == null) {
                failures.add("the press during the first warm-up did not get him into the room");
            } else if (!LAST_GRAPH[0].startsWith("quick")) {
                failures.add("the press during the first warm-up was planned on the '" + LAST_GRAPH[0]
                        + "' graph, not the quick one - so it did not test the first warm-up");
            }
            // The full graph must not have been warm when it was planned: its warm line comes after the press's.
            boolean warmFirst = false;
            boolean pressSeen = false;
            for (String l : LogTap.since(mark0)) {
                Matcher m = WARM_NODES.matcher(l);
                if (!pressSeen && m.find() && Integer.parseInt(m.group(1)) >= 3000) {
                    warmFirst = true;
                }
                pressSeen |= PLANNED.matcher(l).find();
            }
            if (warmFirst) {
                failures.add("the floor graph was already warm when the early press was planned - it tests nothing");
            }
        }

        // ---- the floor graph warms up in the background (Interactive Map on, in a dungeon) --------------
        long t0 = System.currentTimeMillis();
        String warmLine = null;
        // The first "warm" can be a graph of the few hundred landings loaded before the floor's chunks arrived
        // (the 2026-10-04 Map Logger log has one of 279 nodes before the real 13,454); wait for the floor's.
        // WARM_NODES, not the quick graph's own "quick floor graph warm" line.
        Pattern warmNodes = WARM_NODES;
        for (int i = 0; i < 1800 && warmLine == null; i++) {
            ctx.waitTicks(1);
            for (String l : LogTap.since(mark0)) {
                Matcher m = warmNodes.matcher(l);
                if (m.find() && Integer.parseInt(m.group(1)) >= 3000) {
                    warmLine = l;
                }
            }
        }
        println("floor graph: " + (warmLine == null ? "NOT warm after 90 s" : warmLine.replaceAll("^.*\\[Path\\]", "[Path]"))
                + " (" + (System.currentTimeMillis() - t0) + " ms after the build)");
        if (warmLine == null) {
            failures.add("the floor graph never reported warm (3,000+ nodes) in 90 s");
        }

        // ---- presses: rooms across the floor -------------------------------------------------------------
        int moved = 0;
        for (int p = 0; p < targets.size(); p++) {
            int[] t = targets.get(p);
            String r = press(ctx, t[0], t[1], "press " + (p + 1), failures, false);
            moved += r != null ? 1 : 0;
        }

        // ---- a press right after a block changes under the warm graph ------------------------------------
        AtomicReference<String> placed = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(uuid);
                if (sp == null) {
                    placed.set("no server player");
                    return;
                }
                var level = (net.minecraft.server.level.ServerLevel) sp.level();
                BlockPos base = sp.blockPosition();
                int n = 0;
                for (int dx = 2; dx <= 4; dx++) {
                    for (int dz = 2; dz <= 4; dz++) {
                        BlockPos q = base.offset(dx, 2, dz);
                        if (level.getBlockState(q).isAir()) {
                            level.setBlockAndUpdate(q, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                            n++;
                        }
                    }
                }
                placed.set(n + " stone block(s) placed 2 above the floor beside him at " + base.toShortString());
            });
        });
        ctx.waitFor(mc -> placed.get() != null, 100);
        ctx.waitTicks(2);
        println("change: " + placed.get());
        if (!targets.isEmpty()) {
            int[] t = targets.get(0);
            press(ctx, t[0], t[1], "press after a change", failures, false);
        }

        // ---- the early press's trip again, on the warm graph: back to the entrance, then the same room ----
        if (early != null && entrance[0] >= 0) {
            String back = press(ctx, entrance[0], entrance[1], "back to the entrance", failures, false);
            if (back != null) {
                String again = press(ctx, far[0], far[1], "the first-warm-up press's trip, warm", failures, false);
                println("FIRST WARM-UP vs WARM, same trip: " + EARLY_STATS[0] + " vs " + LAST_STATS[0]);
                if (again == null) {
                    failures.add("the repeat of the early press's trip on the warm graph did not get there");
                }
            }
        }
        println(moved + " of " + targets.size() + " press(es) got him there; " + UNREACHABLE[0]
                + " room(s) behind a locked door");
        if (moved < Math.min(4, targets.size() - UNREACHABLE[0])) {
            failures.add("only " + moved + " press(es) got him into the room");
        }
    }

    /** One map press; returns a summary, or null if he did not get there. */
    private static String press(ClientGameTestContext ctx, int tileIdx, int room, String label, List<String> failures,
                                boolean mapOnFirst) {
        for (int i = 0; i < 100 && !ctx.computeOnClient(mc -> mc.player.onGround()); i++) {
            ctx.waitTicks(1);
        }
        LAST_UNREACHABLE[0] = false;
        long mark = LogTap.mark();
        Vec3 from = ctx.computeOnClient(mc -> mc.player.position());
        int seq0 = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(EXECUTOR, "arrivalSeq"));
        Boolean ok = ctx.computeOnClient(mc -> {
            if (mapOnFirst) {
                // Resume the paused warm-up in the same client task as the press, so no warm-up slice runs between.
                ModUnderTest.set(ModUnderTest.config(LIVE_MAP_CONFIG), "setInteractiveMapEnabled", true);
            }
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            Class<?> layoutClass = layout.getClass();
            return (Boolean) ModUnderTest.staticCall(CLEAR_UTILS, "pathToRoom",
                    new Class<?>[]{layoutClass, int.class, int.class, int.class},
                    new Object[]{layout, room, tileIdx, 0});
        });
        if (!Boolean.TRUE.equals(ok)) {
            String why = ctx.computeOnClient(mc -> {
                Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
                int cur = (Integer) ModUnderTest.call(layout, "currentRoom", new Class<?>[]{}, new Object[]{});
                return "on ground " + mc.player.onGround() + ", current room " + cur + " "
                        + (cur >= 0 ? ModUnderTest.call(layout, "name", new Class<?>[]{int.class}, new Object[]{cur}) : "")
                        + ", at " + mc.player.position();
            });
            failures.add(label + ": the press was refused (pathToRoom false; " + why + ")");
            return null;
        }
        // Wait for the executor to take it and finish it.
        boolean busySeen = false;
        int ticks = 0;
        for (; ticks < 400; ticks++) {
            ctx.waitTicks(1);
            boolean busy = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(EXECUTOR, "isBusy"));
            busySeen |= busy;
            if (busySeen && !busy) {
                break;
            }
        }
        ctx.waitTicks(10);
        Vec3 to = ctx.computeOnClient(mc -> mc.player.position());
        int seq1 = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(EXECUTOR, "arrivalSeq"));
        BlockPos centre = ctx.computeOnClient(mc -> (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter",
                new Class<?>[]{int.class}, new Object[]{tileIdx}));
        // A press on a room with a recorded spot goes to that spot, which can be in another tile of a big room:
        // the check is the ROOM he ends in, as the map reads it.
        int endRoom = ctx.computeOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            return (Integer) ModUnderTest.call(layout, "roomAtWorld", new Class<?>[]{double.class, double.class},
                    new Object[]{to.x, to.z});
        });
        boolean inTile = endRoom == room;
        double travelled = from.distanceTo(to);
        String warps = "?";
        String ms = "?";
        String kind = "?";
        boolean roomByRoom = false;
        boolean noRoomRoute = false;
        boolean offPlan = false;
        String running = null;
        for (String l : LogTap.since(mark)) {
            Matcher m = PLANNED.matcher(l);
            if (m.find()) {
                warps = m.group(1);
                kind = m.group(2);
                ms = m.group(3);
            }
            roomByRoom |= l.contains("room by room:") || l.contains("- room by room instead");
            offPlan |= l.contains("[Path] off the plan");
            noRoomRoute |= l.contains("No ROOM route") || l.contains("no way to");
            if (l.contains("[Path] running")) {
                running = l.replaceAll("^.*\\[Path\\]", "[Path]");
            }
        }
        String graph = "?";
        for (String l : LogTap.since(mark)) {
            Matcher g = GRAPH.matcher(l);
            if (g.find()) {
                graph = g.group(1);
            }
        }
        LAST_GRAPH[0] = graph;
        LAST_STATS[0] = warps + " warp(s) in " + ms + " ms on the " + graph + " graph";
        if (label.startsWith("press during the first warm-up")) {
            EARLY_STATS[0] = LAST_STATS[0];
        }
        String summary = String.format("%s: tile %d -> %s warp(s) (%s), planned in %s ms; moved %.1f blocks in %.1f s,"
                        + " ended %s the clicked room (%.1f, %.1f, %.1f), arrival %s%s%s; %s graph", label, tileIdx,
                warps, kind, ms,
                travelled, ticks / 20.0, inTile ? "IN" : "OUTSIDE", to.x, to.y, to.z, seq1 != seq0 ? "confirmed" : "not confirmed",
                roomByRoom ? ", FELL BACK TO ROOM BY ROOM" : "", offPlan ? ", went off the plan" : "", graph);
        println(summary);
        if (running != null) {
            println("  " + running);
        }
        if (noRoomRoute && travelled < 1) {
            // A locked door (blood, wither) between him and the room: nothing can get there, and both planners say
            // so. Not a planner failure; counted apart.
            println("  " + label + ": unreachable from here (a locked door) - not counted");
            UNREACHABLE[0]++;
            LAST_UNREACHABLE[0] = true;
            return null;
        }
        if (roomByRoom) {
            failures.add(label + " fell back to the room-by-room planner");
        }
        if (travelled < 1 || !inTile) {
            failures.add(label + String.format(" did not get him into tile %d (moved %.1f blocks)", tileIdx, travelled));
            return null;
        }
        return summary;
    }

    /** Waits (at most 90 s) for every chunk under the floor's tiles to be on the client. */
    private static void awaitFloorChunks(ClientGameTestContext ctx) {
        // Every chunk under the floor's 6 x 6 tiles on the client (render distance 16 covers a floor from any tile).
        int missing = -1;
        int waited = 0;
        for (; waited < 1800; waited += 10) {
            missing = ctx.computeOnClient(mc -> {
                BlockPos first = (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class},
                        new Object[]{0});
                BlockPos last = (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class},
                        new Object[]{GRID * GRID - 1});
                int n = 0;
                for (int cx = (first.getX() - 16) >> 4; cx <= (last.getX() + 16) >> 4; cx++) {
                    for (int cz = (first.getZ() - 16) >> 4; cz <= (last.getZ() + 16) >> 4; cz++) {
                        if (!mc.level.hasChunk(cx, cz)) {
                            n++;
                        }
                    }
                }
                return n;
            });
            if (missing == 0) {
                break;
            }
            ctx.waitTicks(10);
        }
        println("floor chunks on the client: " + (missing == 0 ? "all, after " + waited / 20.0 + " s"
                : missing + " still missing after 90 s"));
    }

    /**
     * Turns the Interactive Map on (the floor's chunks are on the client and the gate is open), waits (at most
     * 60 s) for the quick graph to report warm on 3,000+ nodes, then turns the map off again, which stops the
     * background warm-up (ClearExecutor only calls tickWarm with it on). Records whether the quick graph never got
     * warm or the full graph got warm too, either of which would make the early press meaningless.
     */
    private static void warmQuickOnly(ClientGameTestContext ctx) {
        FULL_WARM_BEFORE_PAUSE[0] = false;
        long mark = LogTap.mark();
        ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(LIVE_MAP_CONFIG), "setInteractiveMapEnabled", true));
        String quickLine = null;
        for (int i = 0; i < 1200 && quickLine == null; i++) {
            for (String l : LogTap.since(mark)) {
                Matcher m = QUICK_WARM.matcher(l);
                if (m.find() && Integer.parseInt(m.group(1)) >= 3000) {
                    quickLine = l;
                }
            }
            if (quickLine == null) {
                ctx.waitTicks(1);
            }
        }
        ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(LIVE_MAP_CONFIG), "setInteractiveMapEnabled", false));
        QUICK_NOT_WARM[0] = quickLine == null;
        for (String l : LogTap.since(mark)) {
            Matcher m = WARM_NODES.matcher(l);
            if (m.find() && Integer.parseInt(m.group(1)) >= 3000) {
                FULL_WARM_BEFORE_PAUSE[0] = true;
            }
        }
        println("warm-up paused: " + (quickLine == null ? "the quick graph never reported warm on 3,000+ nodes in 60 s"
                : quickLine.replaceAll("^.*\\[Path\\]", "[Path]")) + (FULL_WARM_BEFORE_PAUSE[0]
                ? " - BUT the full graph was already warm" : "; full graph not warm"));
    }

    private static final boolean[] FULL_WARM_BEFORE_PAUSE = {false};
    private static final boolean[] QUICK_NOT_WARM = {false};
    private static final Pattern QUICK_WARM = Pattern.compile("\\[Path\\] quick floor graph warm: (\\d+) node");
    private static final int[] UNREACHABLE = {0};
    private static final boolean[] LAST_UNREACHABLE = {false};
    private static final String[] LAST_GRAPH = {""};
    private static final String[] LAST_STATS = {""};
    private static final String[] EARLY_STATS = {""};
    private static final Pattern GRAPH = Pattern.compile("\\[Path\\] \\d+ warp\\(s\\).*? on the (.+?) graph:");
    private static final Pattern WARM_NODES = Pattern.compile("\\[Path\\] floor graph warm: (\\d+) node");

    /** Rooms across the floor to press (spread out), and the tile/room he stands in. Client thread. */
    private static void chooseTargets(List<int[]> targets, int[] entrance) {
        Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
        int here = (Integer) ModUnderTest.staticCall(LIVE_MAP, "currentRoomIndex");
        int hereRoom = here >= 0 ? (Integer) ModUnderTest.call(layout, "roomOfCell", new Class<?>[]{int.class},
                new Object[]{here}) : -1;
        entrance[0] = here;
        entrance[1] = hereRoom;
        List<int[]> all = new ArrayList<>();
        java.util.Set<Integer> rooms = new java.util.HashSet<>();
        for (int gz = 0; gz < GRID; gz += 2) {
            for (int gx = 0; gx < GRID; gx += 2) {
                int idx = gz * GRID + gx;
                int room = (Integer) ModUnderTest.call(layout, "roomOfCell", new Class<?>[]{int.class},
                        new Object[]{idx});
                Object nm = room >= 0 ? ModUnderTest.call(layout, "name", new Class<?>[]{int.class},
                        new Object[]{room}) : null;
                String n = nm == null ? "" : nm.toString();
                // A map press is refused from inside a trap, a maze or Boulder (QUOI's canPath, the same on
                // Hypixel), so pressing one would end the run of presses there.
                boolean deadEnd = n.contains("Trap") || n.contains("Maze") || n.contains("Boulder");
                if (room >= 0 && room != hereRoom && !deadEnd && rooms.add(room)) {
                    all.add(new int[]{idx, room});
                }
            }
        }
        // Spread out: every k-th distinct room.
        int step = Math.max(1, all.size() / PRESSES);
        for (int i = 0; i < all.size() && targets.size() < PRESSES; i += step) {
            targets.add(all.get(i));
        }
    }

    /** The target whose tile centre is farthest from where he stands. */
    private static int[] farthest(ClientGameTestContext ctx, List<int[]> targets) {
        Vec3 at = ctx.computeOnClient(mc -> mc.player.position());
        int[] best = null;
        double bd = -1;
        for (int[] t : targets) {
            BlockPos c = ctx.computeOnClient(mc -> (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter",
                    new Class<?>[]{int.class}, new Object[]{t[0]}));
            double d = Math.hypot(c.getX() + 0.5 - at.x, c.getZ() + 0.5 - at.z);
            if (d > bd) {
                bd = d;
                best = t;
            }
        }
        return best;
    }

    private static void println(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}
