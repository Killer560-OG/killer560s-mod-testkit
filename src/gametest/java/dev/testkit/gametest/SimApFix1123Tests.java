package dev.testkit.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.testkit.harness.Report;
import dev.testkit.harness.SuiteVerdict;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Dungeons Autopilot fixes from his 11-22 F7 runs (apfix-1123):
 * <ul>
 *   <li>1123-sim-ap-walkgap (ap-stop289-1122): a path-less route whose next node needs a walk was given up ("waits for a
 *   walk to its next node - moving on") and its secrets never got; his Atlas, Archway, Bridges and Market routes left 13
 *   of the S+'s secrets and the run stopped at 289. A two-node route (start node, then a node across the room he would
 *   walk to) in a sim room: Auto Secret must warp onto the second node and the route must run to its end.</li>
 *   <li>1123-sim-ap-nopath-hold (ap-tripfail-done-1122): a trip that found no warp chain from the block he stands on is
 *   left out of the Autopilot's candidates while anything else is still worth trying from that block (his Hall, three
 *   tries from one block in 2 s, then skipped for the run), kept when it is the only thing left, and a failed trip is
 *   logged as failed, not as "done".</li>
 * </ul>
 */
public class SimApFix1123Tests implements FabricClientGameTest {
    private static final String WALK = "1123-sim-ap-walkgap";
    private static final String HOLD = "1123-sim-ap-nopath-hold";
    /** diagonal-short-1127: nodes numbered BELOW the start node (his Diagonal: start #12 of 14, #1-#11 never ran). */
    private static final String BELOW = "1127-sim-ap-start-below";
    /** diagonal-short-1127: one node below the start and two above it with a walk gap between each. */
    private static final String ABOVE = "1127-sim-ap-start-gap-above";
    /** goback-partial-1127: a go-back runs only the node whose secret wait was never met, not the whole route. */
    private static final String PARTIAL = "1127-sim-ap-goback-partial";
    private static final List<String> ALL = List.of(WALK, HOLD, BELOW, ABOVE, PARTIAL);
    private static final String AS = "com.killer560.hub.autosecret.AutoSecretFeature";
    private static final String AP = "com.killer560.hub.autosecret.Autopilot";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String TELEPORT_UTILS = "com.killer560.hub.livemap.autoclear.TeleportUtils";
    private static final String COORDS = "com.killer560.hub.autoroutes.RouteCoords";
    private static final String FRAME = "com.killer560.hub.autoroutes.RouteCoords$Frame";
    private static final String AR_CONFIG = "com.killer560.hub.autoroutes.AutoRoutesConfig";
    private static final String AS_CONFIG = "com.killer560.hub.autosecret.AutoSecretConfig";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        boolean any = false;
        for (String n : ALL) {
            any |= !System.getProperty("testkit.scenario", "").isBlank() && !Scenario.skip(n);
        }
        if (!any) {
            return;
        }
        if (!dev.testkit.gametest.mod.Mod.isCheat()) {
            for (String n : ALL) {
                Scenario.skipped(n, "Auto Secret and Dungeons Autopilot are cheat build only");
            }
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (SimMapTests.copyRoomsForOthers() < 20) {
            for (String n : ALL) {
                Scenario.skipped(n, "needs real room captures and room database");
            }
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.RoomLibrary", "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.roomsim.RoomLibrary", "isReady"), 2400);
        ctx.runOnClient(mc -> {
            ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autopuzzles.AutoPuzzlesConfig"),
                    "setAutoPuzzlesMasterEnabled", false);
            Object map = ModUnderTest.config("com.killer560.hub.livemap.LiveMapConfig");
            ModUnderTest.set(map, "setEnabled", true);
            ModUnderTest.set(map, "setInteractiveMapEnabled", true);
            Object ar = ModUnderTest.config(AR_CONFIG);
            ModUnderTest.set(ar, "setEnabled", true);
            ModUnderTest.set(ar, "setLegitMode", false);
            ModUnderTest.set(ar, "setStartFromStartNodeOnly", true);   // the walk node must fire though it is not a start
            ModUnderTest.call(ar, "setHeight", new Class<?>[]{float.class}, new Object[]{1.0f});
            Object as = ModUnderTest.config(AS_CONFIG);
            ModUnderTest.set(as, "setPuzzleWaitSeconds", 0);
            ModUnderTest.set(as, "setRouteStallSeconds", 10);
        });
        int renderBefore = ctx.computeOnClient(mc -> mc.options.renderDistance().get());
        try {
            floor(ctx);
        } finally {
            // The sim world goes before the class ends: a client gametest that ends with its server still running fails
            // the whole run (lane1123-0517, the cases after this class were NOTRUN).
            ctx.runOnClient(mc -> {
                if ((Boolean) ModUnderTest.staticCall(AS, "isRunning")) {
                    ModUnderTest.staticCall(AS, "stop", new Class<?>[]{String.class, boolean.class},
                            new Object[]{"test over", true});
                }
            });
            SimAutoSecretTests.writeRoutes(ctx, Map.of());
            ctx.runOnClient(mc -> mc.options.renderDistance().set(renderBefore));
            ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimState", "leave"));
            ctx.runOnClient(mc -> mc.execute(() -> {
                if (mc.level != null) {
                    mc.level.disconnect(net.minecraft.network.chat.Component.literal("scenario over"));
                    mc.disconnectWithSavingScreen();
                }
            }));
            ctx.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null);
            ctx.waitTicks(40);
            ctx.runOnClient(mc -> mc.execute(() ->
                    dev.testkit.compat.McCompat.setScreen(mc, new net.minecraft.client.gui.screens.TitleScreen())));
            ctx.waitFor(mc -> dev.testkit.compat.McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.TitleScreen);
        }
    }

    private static void floor(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> mc.options.renderDistance().set(16));
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimState", "enter",
                new Class<?>[]{String.class}, new Object[]{"gametest"}));
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> {
            Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
            ModUnderTest.staticCall(FLOOR_GEN, "generate",
                    new Class<?>[]{Minecraft.class, floor.getClass(), int.class, int.class},
                    new Object[]{mc, floor, 3, 4});
        }));
        ctx.waitFor(mc -> mc.level != null);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitTicks(80);
        SimAutoSecretTests.awaitFloorChunks(ctx);
        SimAutoSecretTests.giveAotv(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimRun", "begin",
                new Class<?>[]{Minecraft.class, BlockPos.class},
                new Object[]{mc, ModUnderTest.staticCall("com.killer560.hub.roomsim.SimBuilder", "entranceDoor")}));
        for (int i = 0; i < 400 && !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(
                "com.killer560.hub.roomsim.SimRun", "isRunning")); i++) {
            ctx.waitTicks(1);
        }
        ctx.waitTicks(40);
        for (int i = 0; i < 100 && !ctx.computeOnClient(mc -> mc.player.onGround()); i++) {
            ctx.waitTicks(1);
        }
        if (Scenario.named(HOLD)) {
            row(HOLD, ctx.computeOnClient(SimApFix1123Tests::holdCase));
        }
        if (Scenario.named(WALK)) {
            walkCase(ctx);
        }
        Set<String> used = new HashSet<>();
        if (Scenario.named(BELOW)) {
            wrapCase(ctx, BELOW, 3, 2, used);
        }
        if (Scenario.named(ABOVE)) {
            wrapCase(ctx, ABOVE, 4, 1, used);
        }
        if (Scenario.named(PARTIAL)) {
            partialCase(ctx, used);
        }
    }

    /** A new dungeon run for the route memory: an earlier case on this floor may have run the same room (Mines). */
    private static void freshRun(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.autoroutes.RouteRunMemory", "reset",
                new Class<?>[]{}, new Object[]{}));
    }

    /**
     * diagonal-short-1127 (killer560 11-27: "it keeps struggling to secret diagonal", then "the number for the node does
     * not matter"): a path-less route of {@code count} ROTATE nodes on separate blocks of one room, its start flag on node
     * {@code startIdx} (0-based), the rest a walk apart. Auto Secret enters at the start node; every other node, numbered
     * below or above it, must fire too (the warp across each walk gap), the start node exactly once, the route finish,
     * and the run memory must then hold every node as fired (the go-back guard). On origin the run ended at the last
     * number and the nodes before the start never fired.
     */
    private static void wrapCase(ClientGameTestContext ctx, String name, int count, int startIdx, Set<String> used) {
        String preBlood = ctx.computeOnClient(SimAutoSecretTests::preBloodName);
        int here = ctx.computeOnClient(mc -> (Integer) ModUnderTest.call(ModUnderTest.staticCall(LAYOUT, "capture"),
                "currentRoom", new Class<?>[]{}, new Object[]{}));
        String room = null;
        JsonObject route = null;
        List<SimAutoSecretTests.Room> rooms = ctx.computeOnClient(SimAutoSecretTests::readRooms);
        rooms.sort((a, b) -> Integer.compare(b.unfound(), a.unfound()));
        for (SimAutoSecretTests.Room r : rooms) {
            if (!r.reachable() || r.id() == here || r.unfound() <= 0 || r.name().equals("Unknown") || r.name().equals(preBlood)
                    || used.contains(r.name()) || !SimAutoSecretTests.pathsOut(r)
                    || List.of("PUZZLE", "TRAP", "BLOOD", "ENTRANCE").contains(r.type())) {
                continue;
            }
            route = ctx.computeOnClient(mc -> spreadRoute(r, name, count, startIdx, -1));
            if (route != null) {
                room = r.name();
                break;
            }
        }
        if (room == null) {
            row(name, "FAIL|no reachable room on this floor has " + count + " standable blocks 6+ apart in its main tile");
            return;
        }
        used.add(room);
        SimAutoSecretTests.writeRoutes(ctx, Map.of(room, route));
        long mark = LogTap.mark();
        freshRun(ctx);
        Boolean started = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(AS, "start"));
        String target = room;
        boolean finished = false;
        boolean gaveUp = false;
        for (int t = 0; t < 20 * 120 && !finished && !gaveUp && Boolean.TRUE.equals(started); t += 5) {
            ctx.waitTicks(5);
            for (String l : LogTap.since(mark)) {
                finished |= l.contains("[AutoSecret] route " + target + " finished");
                gaveUp |= l.contains("[AutoSecret] route " + target + " waits for a walk")
                        || l.contains("[AutoSecret] route " + target + ": the warp onto its next node failed")
                        || l.contains("[AutoSecret] route " + target + " stalled");
            }
        }
        ctx.runOnClient(mc -> ModUnderTest.staticCall(AS, "stop", new Class<?>[]{String.class, boolean.class},
                new Object[]{"test over", true}));
        int[] fired = new int[count];
        for (String l : LogTap.since(mark)) {
            for (int k = 0; k < count; k++) {
                if (l.contains("[AutoRoutes] Node #" + (k + 1) + " ROTATE finished")) {
                    fired[k]++;
                }
            }
        }
        boolean all = true;
        for (int k = 0; k < count; k++) {
            all &= k == startIdx ? fired[k] == 1 : fired[k] >= 1;
        }
        Boolean memory = ctx.computeOnClient(mc -> {
            try {
                Object store = ModUnderTest.staticCall("com.killer560.hub.autoroutes.RouteStore", "getInstance");
                Object r = ModUnderTest.call(store, "forRoom", new Class<?>[]{String.class}, new Object[]{target});
                return (Boolean) Class.forName("com.killer560.hub.autoroutes.RouteRunMemory")
                        .getMethod("everyNodeFired", Class.forName("com.killer560.hub.autoroutes.Route")).invoke(null, r);
            } catch (Throwable e) {
                return false;   // origin has no such memory
            }
        });
        boolean pass = Boolean.TRUE.equals(started) && finished && !gaveUp && all && Boolean.TRUE.equals(memory);
        row(name, (pass ? "PASS" : "FAIL") + "|route in " + room + " (" + count + " nodes, start #" + (startIdx + 1)
                + "): started " + started + ", fired per node " + java.util.Arrays.toString(fired) + " (start once, every"
                + " other at least once), route finished " + finished + ", given up " + gaveUp
                + ", run memory has every node fired " + memory);
    }

    /**
     * goback-partial-1127 (killer560 11-27: "itll miss a secret and redo the whole room wasting a bunch of time"): four
     * ROTATE nodes a walk apart, #3 waiting for a secret that never comes (it lets go). The only route on the floor, so
     * Auto Secret reaches its endgame and goes back for the room's missing secrets: the go-back must name that one node,
     * warp onto it and run it alone - no other node fires again - and the route must finish. On origin the go-back
     * reruns the whole route from its start node.
     */
    private static void partialCase(ClientGameTestContext ctx, Set<String> used) {
        String preBlood = ctx.computeOnClient(SimAutoSecretTests::preBloodName);
        int here = ctx.computeOnClient(mc -> (Integer) ModUnderTest.call(ModUnderTest.staticCall(LAYOUT, "capture"),
                "currentRoom", new Class<?>[]{}, new Object[]{}));
        String room = null;
        JsonObject route = null;
        List<SimAutoSecretTests.Room> rooms = ctx.computeOnClient(SimAutoSecretTests::readRooms);
        rooms.sort((a, b) -> Integer.compare(b.unfound(), a.unfound()));
        for (SimAutoSecretTests.Room r : rooms) {
            if (!r.reachable() || r.id() == here || r.unfound() <= 0 || r.name().equals("Unknown") || r.name().equals(preBlood)
                    || used.contains(r.name()) || !SimAutoSecretTests.pathsOut(r)
                    || List.of("PUZZLE", "TRAP", "BLOOD", "ENTRANCE").contains(r.type())) {
                continue;
            }
            route = ctx.computeOnClient(mc -> spreadRoute(r, PARTIAL, 4, 0, 2));
            if (route != null) {
                room = r.name();
                break;
            }
        }
        if (room == null) {
            row(PARTIAL, "FAIL|no reachable room on this floor has 4 standable blocks 6+ apart in its main tile");
            return;
        }
        used.add(room);
        SimAutoSecretTests.writeRoutes(ctx, Map.of(room, route));
        long mark = LogTap.mark();
        Boolean started = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(AS, "start"));
        String target = room;
        int finishes = 0;
        long goBack = -1;
        boolean stopped = false;
        for (int t = 0; t < 20 * 180 && finishes < 2 && !stopped && Boolean.TRUE.equals(started); t += 5) {
            ctx.waitTicks(5);
            finishes = 0;
            List<String> lines = LogTap.since(mark);
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i);
                if (l.contains("[AutoSecret] route " + target + " finished")) {
                    finishes++;
                }
                if (goBack < 0 && l.contains("[AutoSecret] endgame: " + target + " ended its route short")) {
                    goBack = i;
                }
            }
            stopped = !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(AS, "isRunning"));
        }
        ctx.runOnClient(mc -> ModUnderTest.staticCall(AS, "stop", new Class<?>[]{String.class, boolean.class},
                new Object[]{"test over", true}));
        List<String> lines = LogTap.since(mark);
        int[] first = new int[4];
        int[] again = new int[4];
        boolean named = false;
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            named |= goBack >= 0 && i > goBack && l.contains("going back for 1 node(s) of its route");
            for (int k = 0; k < 4; k++) {
                if (l.contains("[AutoRoutes] Node #" + (k + 1) + " ROTATE finished")) {
                    (goBack >= 0 && i > goBack ? again : first)[k]++;
                }
            }
        }
        boolean firstAll = first[0] >= 1 && first[1] >= 1 && first[2] >= 1 && first[3] >= 1;
        boolean onlyMissed = again[2] >= 1 && again[0] == 0 && again[1] == 0 && again[3] == 0;
        boolean pass = Boolean.TRUE.equals(started) && firstAll && goBack >= 0 && named && onlyMissed && finishes >= 2;
        row(PARTIAL, (pass ? "PASS" : "FAIL") + "|route in " + room + " (4 nodes, #3 waits for a secret that never comes):"
                + " started " + started + ", first run fired " + java.util.Arrays.toString(first) + ", went back "
                + (goBack >= 0) + ", named only #3 " + named + ", go-back fired " + java.util.Arrays.toString(again)
                + " (want only #3), route finished " + finishes + " time(s)");
    }

    /** {@code count} ROTATE nodes on standable blocks 6+ apart in the room's main tile, start flag on {@code startIdx}. */
    private static JsonObject spreadRoute(SimAutoSecretTests.Room r, String name, int count, int startIdx,
                                          int awaitIdx) {
        Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
        int[] cr = (int[]) ModUnderTest.call(layout, "clayRotation", new Class<?>[]{int.class}, new Object[]{r.id()});
        if (cr == null) {
            return null;
        }
        BlockPos centre = (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class},
                new Object[]{r.mainTile()});
        BlockPos a = inTile(centre, Vec3.atCenterOf(centre));
        if (a == null) {
            return null;
        }
        double eye = (Double) ModUnderTest.staticCall(TELEPORT_UTILS, "eyeHeight", new Class<?>[]{boolean.class},
                new Object[]{true});
        List<BlockPos> others = new ArrayList<>();
        for (int[] d : new int[][]{{10, 10}, {-10, -10}, {10, -10}, {-10, 10}, {8, 0}, {-8, 0}, {0, 8}, {0, -8}}) {
            BlockPos c = inTile(centre, Vec3.atCenterOf(centre).add(d[0], 0, d[1]));
            boolean apart = c != null && c.distSqr(a) >= 36;
            for (BlockPos o : others) {
                apart &= c != null && c.distSqr(o) >= 36;
            }
            if (apart && others.size() < count - 1 && ModUnderTest.staticCall(TELEPORT_UTILS, "getEtherwarpDirection",
                    new Class<?>[]{Vec3.class, BlockPos.class, double.class},
                    new Object[]{new Vec3(a.getX() + 0.5, a.getY() + 1 + eye, a.getZ() + 0.5), c, 61.0}) != null) {
                others.add(c);
            }
        }
        if (others.size() < count - 1) {
            return null;
        }
        try {
            Object frame = Class.forName(FRAME).getConstructor(String.class, int.class, int.class, int.class)
                    .newInstance(r.name(), cr[0], cr[1], cr[2]);
            JsonArray nodes = new JsonArray();
            StringBuilder where = new StringBuilder();
            int o = 0;
            for (int i = 0; i < count; i++) {
                BlockPos p = i == startIdx ? a : others.get(o++);
                Vec3 rel = (Vec3) ModUnderTest.staticCall(COORDS, "toRelative", new Class<?>[]{frame.getClass(), Vec3.class},
                        new Object[]{frame, new Vec3(p.getX() + 0.5, p.getY() + 1, p.getZ() + 0.5)});
                JsonObject node = new JsonObject();
                node.addProperty("type", "ROTATE");
                node.addProperty("x", rel.x);
                node.addProperty("y", rel.y);
                node.addProperty("z", rel.z);
                node.addProperty("yaw", 0f);
                node.addProperty("pitch", 0f);
                if (i == startIdx) {
                    node.addProperty("start", true);
                }
                if (i == awaitIdx) {
                    node.addProperty("awaitEnabled", true);
                    node.addProperty("await", "SECRET");
                    node.addProperty("amount", 1);
                }
                nodes.add(node);
                where.append(" #").append(i + 1).append(i == startIdx ? "(start)" : "").append(' ').append(p.toShortString());
            }
            JsonObject route = new JsonObject();
            route.add("nodes", nodes);
            System.out.println("[" + name + "] route for " + r.name() + ":" + where);
            return route;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /** Two candidate lists from the Autopilot on this floor: as is, and with the first one's trip failed from here. */
    @SuppressWarnings("unchecked")
    private static String holdCase(Minecraft mc) {
        try {
            if (getStatic(AS, "noPathTargets") == null) {
                return "FAIL|this jar has no no-path hold (AutoSecretFeature.noPathTargets)";
            }
            // Clears make every mob room a candidate from the entrance (no saved routes in the sandbox, unknown rooms
            // behind doors not yet opened): the floor needs two or more for the hold to be judged at all.
            ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autoclear.AutoClearConfig"), "setEnabled", true);
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            int here = (Integer) ModUnderTest.call(layout, "currentRoom", new Class<?>[]{}, new Object[]{});
            List<Object> rooms = (List<Object>) ModUnderTest.staticCall("com.killer560.hub.livemap.RoomStatus", "rooms");
            Map<Integer, Integer> dist = new HashMap<>();
            for (Object r : rooms) {
                int id = (Integer) ModUnderTest.call(r, "room", new Class<?>[]{}, new Object[]{});
                List<?> path = id == here ? List.of(1) : (List<?>) ModUnderTest.staticCall(
                        "com.killer560.hub.livemap.autoclear.DungeonMapPathfinder", "findPath",
                        new Class<?>[]{layout.getClass(), int.class, int.class, boolean.class},
                        new Object[]{layout, here, id, true});
                if (path != null) {
                    dist.put(id, path.size() - 1);
                }
            }
            Object state = ModUnderTest.staticCall(AP, "scoreState", new Class<?>[]{layout.getClass(), List.class},
                    new Object[]{layout, rooms});
            java.lang.reflect.Method cands = null;
            for (java.lang.reflect.Method m : Class.forName(AP).getDeclaredMethods()) {
                if (m.getName().equals("candidates")) {
                    cands = m;
                }
            }
            cands.setAccessible(true);
            Set<String> none = new HashSet<>();
            setStatic(AS, "noPathFrom", null);
            ((Set<String>) getStatic(AS, "noPathTargets")).clear();
            List<String> first = keys((List<Object>) cands.invoke(null, layout, rooms, dist, none, none, none, state, false));
            if (first.size() < 2) {
                return "FAIL|only " + first.size() + " candidate(s) on this floor: " + first;
            }
            String failed = first.get(0);
            // As tripFailed records it: the block he stands on, the target that found no warp chain from it.
            setStatic(AS, "noPathFrom", mc.player.blockPosition());
            ((Set<String>) getStatic(AS, "noPathTargets")).add(failed);
            List<String> held = keys((List<Object>) cands.invoke(null, layout, rooms, dist, none, none, none, state, false));
            ((Set<String>) getStatic(AS, "noPathTargets")).addAll(first);
            List<String> allHeld = keys((List<Object>) cands.invoke(null, layout, rooms, dist, none, none, none, state, false));
            setStatic(AS, "noPathFrom", mc.player.blockPosition().above(5));
            List<String> moved = keys((List<Object>) cands.invoke(null, layout, rooms, dist, none, none, none, state, false));
            setStatic(AS, "noPathFrom", null);
            ((Set<String>) getStatic(AS, "noPathTargets")).clear();
            // The failed trip's log line: "trip failed:", never "done".
            Object cand = ((List<Object>) cands.invoke(null, layout, rooms, dist, none, none, none, state, false)).get(0);
            long mark = LogTap.mark();
            setStatic(AP, "current", cand);
            setStatic(AP, "currentStartMs", System.currentTimeMillis() - 2500);
            setStatic(AP, "currentFailed", true);
            java.lang.reflect.Method close = Class.forName(AP).getDeclaredMethod("closeCurrent");
            close.setAccessible(true);
            close.invoke(null);
            String line = "";
            for (String l : LogTap.since(mark)) {
                if (l.contains("[Autopilot] ") && l.contains(String.valueOf(ModUnderTest.call(cand, "room",
                        new Class<?>[]{}, new Object[]{})))) {
                    line = l.substring(l.indexOf("[Autopilot]"));
                }
            }
            boolean pass = !held.contains(failed) && held.size() == first.size() - 1 && allHeld.equals(first)
                    && moved.equals(first) && line.contains("trip failed:") && !line.contains(" done ");
            return (pass ? "PASS" : "FAIL") + "|candidates " + first + "; with " + failed + "'s trip failed from here "
                    + held + " (want it left out); with every one failed from here " + allHeld + " (want all kept); after he"
                    + " moved " + moved + " (want all back); failed trip logged as: " + line;
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            return "FAIL|threw " + t;
        } finally {
            ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autoclear.AutoClearConfig"), "setEnabled", false);
        }
    }

    private static List<String> keys(List<Object> cands) {
        List<String> out = new ArrayList<>();
        for (Object c : cands) {
            out.add(String.valueOf(ModUnderTest.call(c, "room", new Class<?>[]{}, new Object[]{})));
        }
        return out;
    }

    /** A two-node path-less route in one reachable room, with a walk between the nodes; Auto Secret runs it. */
    private static void walkCase(ClientGameTestContext ctx) {
        String preBlood = ctx.computeOnClient(SimAutoSecretTests::preBloodName);
        int here = ctx.computeOnClient(mc -> (Integer) ModUnderTest.call(ModUnderTest.staticCall(LAYOUT, "capture"),
                "currentRoom", new Class<?>[]{}, new Object[]{}));
        String room = null;
        JsonObject route = null;
        List<SimAutoSecretTests.Room> rooms = ctx.computeOnClient(SimAutoSecretTests::readRooms);
        rooms.sort((a, b) -> Integer.compare(b.unfound(), a.unfound()));
        for (SimAutoSecretTests.Room r : rooms) {
            if (!r.reachable() || r.id() == here || r.unfound() <= 0 || r.name().equals("Unknown") || r.name().equals(preBlood)
                    || !SimAutoSecretTests.pathsOut(r) || List.of("PUZZLE", "TRAP", "BLOOD", "ENTRANCE").contains(r.type())) {
                continue;
            }
            route = ctx.computeOnClient(mc -> twoNodeRoute(r));
            if (route != null) {
                room = r.name();
                break;
            }
        }
        if (room == null) {
            row(WALK, "FAIL|no reachable room on this floor has two standable blocks 6+ apart in its main tile");
            return;
        }
        Map<String, JsonObject> routes = new HashMap<>();
        routes.put(room, route);
        SimAutoSecretTests.writeRoutes(ctx, routes);
        long mark = LogTap.mark();
        Boolean started = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(AS, "start"));
        String target = room;
        boolean warped = false;
        boolean second = false;
        boolean finished = false;
        boolean gaveUp = false;
        for (int t = 0; t < 20 * 90 && !finished && !gaveUp && Boolean.TRUE.equals(started); t += 5) {
            ctx.waitTicks(5);
            for (String l : LogTap.since(mark)) {
                warped |= l.contains("[AutoSecret] route " + target + ": its next node needs a walk - warping onto it");
                second |= l.contains("[AutoRoutes] Node #2 ROTATE finished");
                finished |= l.contains("[AutoSecret] route " + target + " finished");
                gaveUp |= l.contains("[AutoSecret] route " + target + " waits for a walk")
                        || l.contains("[AutoSecret] route " + target + ": the warp onto its next node failed");
            }
        }
        ctx.runOnClient(mc -> ModUnderTest.staticCall(AS, "stop", new Class<?>[]{String.class, boolean.class},
                new Object[]{"test over", true}));
        boolean pass = Boolean.TRUE.equals(started) && warped && second && finished && !gaveUp;
        row(WALK, (pass ? "PASS" : "FAIL") + "|route in " + room + " (start node, then a node across the room): started "
                + started + ", warped onto the walk node " + warped + ", node #2 fired " + second + ", route finished "
                + finished + ", given up " + gaveUp);
    }

    /** Start node on one standable block of the room's main tile, a second node on one 6+ blocks away. Client thread. */
    private static JsonObject twoNodeRoute(SimAutoSecretTests.Room r) {
        Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
        int[] cr = (int[]) ModUnderTest.call(layout, "clayRotation", new Class<?>[]{int.class}, new Object[]{r.id()});
        if (cr == null) {
            return null;
        }
        BlockPos centre = (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class},
                new Object[]{r.mainTile()});
        BlockPos a = inTile(centre, Vec3.atCenterOf(centre));
        BlockPos b = null;
        // The walk node is one a single etherwarp from the start node reaches: the case is the warp onto it, not the
        // planner's room search (Flags' 17.7-block pick timed out there, mine3 11-23).
        double eye = (Double) ModUnderTest.staticCall(TELEPORT_UTILS, "eyeHeight", new Class<?>[]{boolean.class},
                new Object[]{true});
        for (int[] d : new int[][]{{10, 10}, {-10, -10}, {10, -10}, {-10, 10}, {8, 0}, {-8, 0}, {0, 8}, {0, -8}}) {
            BlockPos c = a == null ? null : inTile(centre, Vec3.atCenterOf(centre).add(d[0], 0, d[1]));
            if (c != null && c.distSqr(a) >= 36 && (b == null || c.distSqr(a) > b.distSqr(a))
                    && ModUnderTest.staticCall(TELEPORT_UTILS, "getEtherwarpDirection",
                    new Class<?>[]{Vec3.class, BlockPos.class, double.class},
                    new Object[]{new Vec3(a.getX() + 0.5, a.getY() + 1 + eye, a.getZ() + 0.5), c, 61.0}) != null) {
                b = c;
            }
        }
        if (a == null || b == null) {
            return null;
        }
        try {
            Object frame = Class.forName(FRAME).getConstructor(String.class, int.class, int.class, int.class)
                    .newInstance(r.name(), cr[0], cr[1], cr[2]);
            JsonArray nodes = new JsonArray();
            for (BlockPos p : new BlockPos[]{a, b}) {
                Vec3 rel = (Vec3) ModUnderTest.staticCall(COORDS, "toRelative", new Class<?>[]{frame.getClass(), Vec3.class},
                        new Object[]{frame, new Vec3(p.getX() + 0.5, p.getY() + 1, p.getZ() + 0.5)});
                JsonObject node = new JsonObject();
                node.addProperty("type", "ROTATE");
                node.addProperty("x", rel.x);
                node.addProperty("y", rel.y);
                node.addProperty("z", rel.z);
                node.addProperty("yaw", 0f);
                node.addProperty("pitch", 0f);
                if (p == a) {
                    node.addProperty("start", true);
                }
                nodes.add(node);
            }
            JsonObject route = new JsonObject();
            route.add("nodes", nodes);
            System.out.println("[" + WALK + "] route for " + r.name() + ": start node on " + a.toShortString()
                    + ", walk node on " + b.toShortString() + String.format(Locale.US, " (%.1f apart)", Math.sqrt(a.distSqr(b))));
            return route;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static BlockPos inTile(BlockPos centre, Vec3 near) {
        return (BlockPos) ModUnderTest.staticCall(TELEPORT_UTILS, "etherwarpableInTile",
                new Class<?>[]{BlockPos.class, Vec3.class}, new Object[]{centre, near});
    }

    private static Object getStatic(String cls, String field) {
        try {
            java.lang.reflect.Field f = Class.forName(cls).getDeclaredField(field);
            f.setAccessible(true);
            return f.get(null);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static void setStatic(String cls, String field, Object value) throws ReflectiveOperationException {
        java.lang.reflect.Field f = Class.forName(cls).getDeclaredField(field);
        f.setAccessible(true);
        f.set(null, value);
    }

    /** {@code result} is "PASS|detail" or "FAIL|detail". */
    private static void row(String name, String result) {
        if (!Scenario.named(name)) {
            return;
        }
        boolean pass = result.startsWith("PASS");
        String detail = result.substring(result.indexOf('|') + 1);
        System.out.println("[" + name + "] " + (pass ? "PASS" : "FAIL") + " - " + detail);
        Report.caseStarted(name);
        Report.caseFinished(name, pass ? "PASS" : "FAIL", "n/a (singleplayer)", detail, List.of(), List.of());
        SuiteVerdict.finished(name);
    }
}
