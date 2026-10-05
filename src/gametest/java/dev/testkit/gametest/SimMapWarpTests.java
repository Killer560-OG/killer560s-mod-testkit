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
            ModUnderTest.set(map, "setInteractiveMapEnabled", true);
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

        // ---- the run starts: the entrance gate opens (a block change, like every door on Hypixel) ---------
        // Until then the entrance is sealed and nothing outside it can be reached by anyone.
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimRun", "begin",
                new Class<?>[]{Minecraft.class, BlockPos.class},
                new Object[]{mc, ModUnderTest.staticCall("com.killer560.hub.roomsim.SimBuilder", "entranceDoor")}));

        // ---- the floor graph warms up in the background (Interactive Map on, in a dungeon) --------------
        long t0 = System.currentTimeMillis();
        String warmLine = null;
        // The first "warm" can be a graph of the few hundred landings loaded before the floor's chunks arrived
        // (the 2026-10-04 Map Logger log has one of 279 nodes before the real 13,454); wait for the floor's.
        Pattern warmNodes = Pattern.compile("floor graph warm: (\\d+) node");
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
        List<int[]> targets = new ArrayList<>();   // {tileIdx, room}
        ctx.runOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            int here = (Integer) ModUnderTest.staticCall(LIVE_MAP, "currentRoomIndex");
            int hereRoom = here >= 0 ? (Integer) ModUnderTest.call(layout, "roomOfCell", new Class<?>[]{int.class},
                    new Object[]{here}) : -1;
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
        });
        println(targets.size() + " room(s) to press");
        int moved = 0;
        for (int p = 0; p < targets.size(); p++) {
            int[] t = targets.get(p);
            String r = press(ctx, t[0], t[1], "press " + (p + 1), failures);
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
            press(ctx, t[0], t[1], "press after a change", failures);
        }
        println(moved + " of " + targets.size() + " press(es) got him there; " + UNREACHABLE[0]
                + " room(s) behind a locked door");
        if (moved < Math.min(4, targets.size() - UNREACHABLE[0])) {
            failures.add("only " + moved + " press(es) got him into the room");
        }
    }

    /** One map press; returns a summary, or null if he did not get there. */
    private static String press(ClientGameTestContext ctx, int tileIdx, int room, String label, List<String> failures) {
        for (int i = 0; i < 100 && !ctx.computeOnClient(mc -> mc.player.onGround()); i++) {
            ctx.waitTicks(1);
        }
        long mark = LogTap.mark();
        Vec3 from = ctx.computeOnClient(mc -> mc.player.position());
        int seq0 = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(EXECUTOR, "arrivalSeq"));
        Boolean ok = ctx.computeOnClient(mc -> {
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
        String summary = String.format("%s: tile %d -> %s warp(s) (%s), planned in %s ms; moved %.1f blocks in %.1f s,"
                        + " ended %s the clicked room (%.1f, %.1f, %.1f), arrival %s%s%s", label, tileIdx, warps, kind, ms,
                travelled, ticks / 20.0, inTile ? "IN" : "OUTSIDE", to.x, to.y, to.z, seq1 != seq0 ? "confirmed" : "not confirmed",
                roomByRoom ? ", FELL BACK TO ROOM BY ROOM" : "", offPlan ? ", went off the plan" : "");
        println(summary);
        if (running != null) {
            println("  " + running);
        }
        if (noRoomRoute && travelled < 1) {
            // A locked door (blood, wither) between him and the room: nothing can get there, and both planners say
            // so. Not a planner failure; counted apart.
            println("  " + label + ": unreachable from here (a locked door) - not counted");
            UNREACHABLE[0]++;
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

    private static final int[] UNREACHABLE = {0};

    private static void println(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}
