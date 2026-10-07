package dev.testkit.gametest;

import dev.testkit.compat.McCompat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 102-sim-autosecret: Auto Secret on a generated sim F7, judged on what happened.
 *
 * <p>The floor is generated as in 95-sim-map-warp (render distance 16, every chunk on the client, the gate opened by
 * {@code SimRun.begin}, an Aspect of the Void in hand). Routes are SEEDED into the routes file for a few rooms: one
 * ROTATE start node on a standable block of the room (the mod's own {@code TeleportUtils.etherwarpableInTile}), so a
 * route needs nothing of the room's geometry beyond a block to land on, arms when he lands, and finishes. The reachable
 * room with the MOST unfound secrets is deliberately left without a route, and so is any room behind a closed wither or
 * blood door that has one (nobody opens doors in the sim, so it stays closed all run).
 *
 * <p>Asserted, from the mod's log and chat and from where he went:
 * <ul>
 *   <li>it ACTED: he travelled, map warps arrived, and every seeded reachable room's route started and finished;</li>
 *   <li>the no-route room was skipped with the chat line, exactly once, and never travelled to;</li>
 *   <li>rooms were taken most-unfound first (nothing is found in the run, so the order must be non-increasing);</li>
 *   <li>no room behind a closed door was ever a target;</li>
 *   <li>with every secret found (the sim's score, so its tab list says 100%) it stops, saying so.</li>
 * </ul>
 * Only when named (it takes a couple of minutes).
 */
public class SimAutoSecretTests implements FabricClientGameTest {

    private static final String NAME = "102-sim-autosecret";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String SIM_ITEMS = "com.killer560.hub.roomsim.SimItems";
    private static final String SIM_SCORE = "com.killer560.hub.roomsim.SimScore";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String ROOM_STATUS = "com.killer560.hub.livemap.RoomStatus";
    private static final String MAP_PATHFINDER = "com.killer560.hub.livemap.autoclear.DungeonMapPathfinder";
    private static final String TELEPORT_UTILS = "com.killer560.hub.livemap.autoclear.TeleportUtils";
    private static final String LIVE_MAP_CONFIG = "com.killer560.hub.livemap.LiveMapConfig";
    private static final String EXECUTOR = "com.killer560.hub.livemap.autoclear.ClearExecutor";
    private static final String STORE = "com.killer560.hub.autoroutes.RouteStore";
    private static final String COORDS = "com.killer560.hub.autoroutes.RouteCoords";
    private static final String FRAME = "com.killer560.hub.autoroutes.RouteCoords$Frame";
    private static final String AR_CONFIG = "com.killer560.hub.autoroutes.AutoRoutesConfig";
    private static final String AS = "com.killer560.hub.autosecret.AutoSecretFeature";
    private static final String AS_CONFIG = "com.killer560.hub.autosecret.AutoSecretConfig";
    private static final String DUNGEON_INFO = "com.killer560.hub.dungeoninfo.DungeonInfoFeature";
    private static final String INSTA = "com.killer560.hub.autosecret.InstaClearTracker";
    private static final int GRID = 11;
    private static final int ROUTED = 3;

    private static final Pattern TARGET = Pattern.compile("\\[AutoSecret\\] target (.+?): (\\d+) unfound");
    private static final Pattern NO_ROUTE = Pattern.compile("\\[AutoSecret\\] no route for (.+?) \\((\\d+) unfound\\)");
    private static final Pattern FINISHED = Pattern.compile("\\[AutoSecret\\] route (.+?) finished");
    private static final Pattern STARTED = Pattern.compile("\\[AutoSecret\\] route (.+?) started");

    /** A room as the map sees it at the start. */
    private record Room(int id, String name, String type, int unfound, int mainTile, boolean reachable) {
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank() || Scenario.skip(NAME)) {
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
        ctx.runOnClient(mc -> {
            Object auto = ModUnderTest.config("com.killer560.hub.autopuzzles.AutoPuzzlesConfig");
            ModUnderTest.set(auto, "setAutoPuzzlesMasterEnabled", false);
            Object map = ModUnderTest.config(LIVE_MAP_CONFIG);
            ModUnderTest.set(map, "setEnabled", true);
            ModUnderTest.set(map, "setInteractiveMapEnabled", true);
            Object ar = ModUnderTest.config(AR_CONFIG);
            ModUnderTest.set(ar, "setEnabled", true);
            ModUnderTest.set(ar, "setLegitMode", false);
            ModUnderTest.set(ar, "setStartFromStartNodeOnly", true);
            ModUnderTest.set(ar, "setChatFeedback", true);
            ModUnderTest.call(ar, "setHeight", new Class<?>[]{float.class}, new Object[]{1.0f});
            Object as = ModUnderTest.config(AS_CONFIG);
            ModUnderTest.set(as, "setChatFeedback", true);
            ModUnderTest.set(as, "setPuzzleWaitSeconds", 0);
            ModUnderTest.set(as, "setRouteStallSeconds", 10);
        });
        int renderBefore = ctx.computeOnClient(mc -> mc.options.renderDistance().get());
        ctx.runOnClient(mc -> mc.options.renderDistance().set(16));
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter", new Class<?>[]{String.class},
                new Object[]{"gametest"}));
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

        List<String> failures = new ArrayList<>();
        try {
            body(ctx, failures);
        } catch (Throwable t) {
            failures.add("scenario threw: " + t);
            t.printStackTrace(System.out);
        } finally {
            ctx.runOnClient(mc -> {
                if ((Boolean) ModUnderTest.staticCall(AS, "isRunning")) {
                    ModUnderTest.staticCall(AS, "stop", new Class<?>[]{String.class, boolean.class},
                            new Object[]{"test over", true});
                }
            });
            writeRoutes(ctx, Map.of());
            ctx.runOnClient(mc -> ModUnderTest.staticCall(INSTA, "testUseFile", new Class<?>[]{String.class},
                    new Object[]{null}));
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
        }
        if (!failures.isEmpty()) {
            for (String f : failures) {
                println("FAIL: " + f);
            }
            throw new AssertionError(failures.size() + " problem(s): " + String.join("; ", failures));
        }
        println("PASS");
    }

    private static void body(ClientGameTestContext ctx, List<String> failures) {
        awaitFloorChunks(ctx);
        giveAotv(ctx);
        String held = ctx.computeOnClient(mc -> mc.player.getMainHandItem().getHoverName().getString());
        if (!held.contains("Aspect of the Void")) {
            failures.add("the Aspect of the Void never reached his hand (" + held + ")");
            return;
        }
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

        // ---- a closed wither door ------------------------------------------------------------------------
        // A generated sim floor builds no wither doors (SimWitherDoors: only "theoretical" ones drawn on the map), so
        // one is made: the map's tile for an ordinary door is marked a wither door, as Hypixel's map would show it, and
        // a coal block goes on its lock block, which is what DungeonLayout reads as "closed" (the client's own block,
        // as on Hypixel). Of the doors that put a secret room out of reach, the one cutting off the FEWEST rooms is
        // kept, so most of the floor stays open for the rest of the run.
        BlockPos closedDoor = null;
        int closedCell = -1;
        List<Integer> doors = ctx.computeOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            List<Integer> out = new ArrayList<>();
            for (int i = 0; i < GRID * GRID; i++) {
                int type = (Integer) ModUnderTest.call(layout, "doorType", new Class<?>[]{int.class}, new Object[]{i});
                boolean locked = (Boolean) ModUnderTest.call(layout, "isLocked", new Class<?>[]{int.class}, new Object[]{i});
                if ((type == 1 || type == 2) && !locked) {
                    out.add(i);
                }
            }
            return out;
        });
        int bestCut = Integer.MAX_VALUE;
        for (int door : doors) {
            BlockPos lock = ctx.computeOnClient(mc -> (BlockPos) ModUnderTest.staticCall(LAYOUT, "doorBlock",
                    new Class<?>[]{int.class}, new Object[]{door}));
            Object was = ctx.computeOnClient(mc -> markMapDoor(door, "DOOR_WITHER"));
            setBlock(ctx, lock, net.minecraft.world.level.block.Blocks.COAL_BLOCK.defaultBlockState());
            int cut = 0;
            for (Room r : ctx.computeOnClient(SimAutoSecretTests::readRooms)) {
                cut += !r.reachable() && r.unfound() > 0 && !r.type().equals("BLOOD") && !r.type().equals("PUZZLE")
                        && !r.type().equals("TRAP") ? 1 : 0;
            }
            setBlock(ctx, lock, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            ctx.runOnClient(mc -> markMapDoor(door, ((Enum<?>) was).name()));
            if (cut > 0 && cut < bestCut) {
                bestCut = cut;
                closedCell = door;
                closedDoor = lock;
            }
        }
        if (closedDoor == null) {
            println("no door on this floor puts a secret room out of reach - the closed-door cases are not tested");
        } else {
            int cell = closedCell;
            ctx.runOnClient(mc -> markMapDoor(cell, "DOOR_WITHER"));
            setBlock(ctx, closedDoor, net.minecraft.world.level.block.Blocks.COAL_BLOCK.defaultBlockState());
            println("closed a wither door at cell " + closedCell + " (lock block " + closedDoor.toShortString() + "), cutting off "
                    + bestCut + " secret room(s)");
        }

        // ---- the floor as the map sees it ----------------------------------------------------------------
        List<Room> rooms = ctx.computeOnClient(SimAutoSecretTests::readRooms);
        List<Room> eligible = new ArrayList<>();
        List<Room> blocked = new ArrayList<>();
        for (Room r : rooms) {
            boolean ok = !r.name().equals("Unknown") && !r.type().equals("PUZZLE") && !r.type().equals("TRAP")
                    && !r.type().equals("BLOOD") && !r.type().equals("ENTRANCE") && r.unfound() > 0;
            if (!ok) {
                continue;
            }
            (r.reachable() ? eligible : blocked).add(r);
        }
        eligible.sort((a, b) -> Integer.compare(b.unfound(), a.unfound()));
        blocked.sort((a, b) -> Integer.compare(b.unfound(), a.unfound()));
        println(rooms.size() + " room(s); eligible reachable: " + describe(eligible) + "; behind a closed door: "
                + describe(blocked));
        if (eligible.size() < 2) {
            failures.add("the floor has only " + eligible.size() + " reachable room(s) with secrets - nothing to test");
            return;
        }
        Room noRoute = eligible.get(0);
        List<Room> routed = new ArrayList<>();
        for (int i = 1; i < eligible.size() && routed.size() < ROUTED; i++) {
            if (pathsOut(eligible.get(i))) {
                routed.add(eligible.get(i));
            }
        }
        Room blockedRouted = blocked.isEmpty() ? null : blocked.get(0);
        Map<String, JsonObject> routes = new HashMap<>();
        for (Room r : routed) {
            JsonObject route = routeFor(ctx, r);
            if (route == null) {
                failures.add("no standable block for a route in " + r.name());
                continue;
            }
            routes.put(r.name(), route);
        }
        if (blockedRouted != null) {
            JsonObject route = routeFor(ctx, blockedRouted);
            if (route != null) {
                routes.put(blockedRouted.name(), route);
            }
        }
        println("no route (most unfound): " + noRoute.name() + "=" + noRoute.unfound() + "; routed: " + describe(routed)
                + (blockedRouted != null ? "; routed but behind a closed door: " + blockedRouted.name() : ""));
        writeRoutes(ctx, routes);

        // ---- one KNOWN insta-clear entry, seeded into a scratch evidence file ---------------------------------
        // The lowest-unfound reachable room with no route, entered from the room before it on the map path, landing on
        // a standable block of its main tile. Two INSTA observations = known (the tracker's default threshold).
        Room insta = null;
        String instaKey = null;
        String instaFrom = null;
        for (int i = eligible.size() - 1; i >= 1 && insta == null; i--) {
            Room r = eligible.get(i);
            if (routes.containsKey(r.name()) || !pathsOut(r)) {
                continue;
            }
            String[] seeded = ctx.computeOnClient(mc -> seedInsta(r));
            if (seeded != null) {
                insta = r;
                instaKey = seeded[0];
                instaFrom = seeded[1];
            }
        }
        println(insta == null ? "no room to seed an insta-clear entry for - that case is not tested"
                : "known insta entry: " + insta.name() + " from " + instaFrom + " key " + instaKey);

        // ---- run ----------------------------------------------------------------------------------------------
        boolean doorCase = closedDoor != null && blockedRouted != null && routes.containsKey(blockedRouted.name());
        long mark = LogTap.mark();
        Vec3 from = ctx.computeOnClient(mc -> mc.player.position());
        int seq0 = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(EXECUTOR, "arrivalSeq"));
        Boolean started = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(AS, "start"));
        if (!Boolean.TRUE.equals(started)) {
            failures.add("Auto Secret refused to start: " + tail(LogTap.since(mark), 5));
            return;
        }
        double maxTravel = 0;
        int unarmedSeen = 0;
        Set<String> finished = new HashSet<>();
        int ticks = 0;
        for (; ticks < 20 * 180; ticks += 10) {
            ctx.waitTicks(10);
            Vec3 at = ctx.computeOnClient(mc -> mc.player.position());
            maxTravel = Math.max(maxTravel, at.distanceTo(from));
            String phase = ctx.computeOnClient(mc -> (String) ModUnderTest.staticCall(AS, "phaseName"));
            finished.clear();
            int unarmed = 0;
            for (String l : LogTap.since(mark)) {
                Matcher m = FINISHED.matcher(l);
                if (m.find()) {
                    finished.add(m.group(1));
                }
                if (l.contains("start node did not arm")) {
                    unarmed++;
                }
            }
            if (unarmed > unarmedSeen) {
                unarmedSeen = unarmed;
                println("a start node did not arm: " + ctx.computeOnClient(SimAutoSecretTests::whereNow));
            }
            // With a closed door to judge, "at the door" is its no-key line, not the DOOR phase: the phase starts while
            // he is still on his way to the door (2026-10-07: the run ended 2.0 blocks short, before the line).
            boolean doorWaitSaid = false;
            for (String l : LogTap.since(mark)) {
                doorWaitSaid |= l.contains("[AutoSecret] waiting at the wither door at cell") && l.contains("no wither key");
            }
            boolean settled = doorCase ? doorWaitSaid : phase.equals("WAITING") || phase.equals("DOOR");
            if (phase.equals("IDLE") || settled && finished.size() >= routed.size() && ticks > 200) {
                break;
            }
        }
        String phaseAtEnd = ctx.computeOnClient(mc -> (String) ModUnderTest.staticCall(AS, "phaseName"));
        int seq1 = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(EXECUTOR, "arrivalSeq"));
        List<String> log = LogTap.since(mark);
        println(String.format(Locale.US, "main run: %.1f s, phase now %s, travelled up to %.1f blocks, %d map arrival(s)",
                ticks / 20.0, phaseAtEnd, maxTravel, seq1 - seq0));
        for (String l : log) {
            if (l.contains("[AutoSecret]") || l.contains("[CHAT]") && (l.contains("Auto Secret") || l.contains("Auto Routes"))) {
                println("  " + l.replaceAll("^.*?\\[AutoSecret\\]", "[AutoSecret]").replaceAll("^.*?\\[CHAT\\]", "[CHAT]"));
            }
        }

        // ---- it acted ---------------------------------------------------------------------------------------
        if (maxTravel < 10) {
            failures.add(String.format(Locale.US, "he never moved more than %.1f blocks - Auto Secret did nothing", maxTravel));
        }
        if (seq1 - seq0 < 1) {
            failures.add("no Interactive Map warp ever arrived");
        }
        Set<String> startedRoutes = new HashSet<>();
        for (String l : log) {
            Matcher m = STARTED.matcher(l);
            if (m.find()) {
                startedRoutes.add(m.group(1));
            }
        }
        for (Room r : routed) {
            if (!routes.containsKey(r.name())) {
                continue;
            }
            if (!startedRoutes.contains(r.name())) {
                failures.add("the route in " + r.name() + " never started");
            } else if (!finished.contains(r.name())) {
                failures.add("the route in " + r.name() + " started but never finished");
            }
        }

        // ---- the no-route room: skipped once, said in chat, never a target ------------------------------------
        int noRouteLines = 0;
        int noRouteChat = 0;
        List<int[]> order = new ArrayList<>();   // {unfound}
        List<String> orderNames = new ArrayList<>();
        Set<String> targets = new HashSet<>();
        for (String l : log) {
            Matcher n = NO_ROUTE.matcher(l);
            if (n.find()) {
                order.add(new int[]{Integer.parseInt(n.group(2))});
                orderNames.add("skip " + n.group(1));
                if (n.group(1).equals(noRoute.name())) {
                    noRouteLines++;
                }
            }
            Matcher t = TARGET.matcher(l);
            if (t.find()) {
                order.add(new int[]{Integer.parseInt(t.group(2))});
                orderNames.add("go " + t.group(1));
                targets.add(t.group(1));
            }
            if (l.contains("[CHAT]") && l.contains("No route for " + noRoute.name())) {
                noRouteChat++;
            }
        }
        println("decisions in order: " + orderNames);
        if (noRouteLines != 1) {
            failures.add("the no-route room " + noRoute.name() + " was skipped " + noRouteLines + " time(s) in the log, expected once");
        }
        if (noRouteChat != 1) {
            failures.add("the chat said \"No route for " + noRoute.name() + "\" " + noRouteChat + " time(s), expected once");
        }
        if (targets.contains(noRoute.name())) {
            failures.add("the no-route room " + noRoute.name() + " was a target");
        }
        // ---- the known insta-clear entry was taken first, and landed --------------------------------------------
        if (insta != null) {
            int instaAt = -1;
            int landedAt = -1;
            int firstTarget = -1;
            for (int i = 0; i < log.size(); i++) {
                String l = log.get(i);
                if (instaAt < 0 && l.contains("[AutoSecret] insta clear " + insta.name() + ": entry " + instaKey)) {
                    instaAt = i;
                }
                if (landedAt < 0 && l.contains("[AutoSecret] insta clear " + insta.name() + ": landed")) {
                    landedAt = i;
                }
                if (firstTarget < 0 && TARGET.matcher(l).find()) {
                    firstTarget = i;
                }
            }
            println("insta clear: chosen at line " + instaAt + ", landed at line " + landedAt + ", first secret target at line "
                    + firstTarget);
            if (instaAt < 0) {
                failures.add("the known insta-clear entry into " + insta.name() + " was never taken");
            } else if (landedAt < 0) {
                failures.add("the insta-clear warp into " + insta.name() + " never landed");
            } else if (firstTarget >= 0 && firstTarget < landedAt) {
                failures.add("secreting began before the insta clear of " + insta.name());
            }
        }

        // ---- most unfound first ----------------------------------------------------------------------------
        for (int i = 1; i < order.size(); i++) {
            if (order.get(i)[0] > order.get(i - 1)[0]) {
                failures.add("rooms were not taken most-unfound first: " + orderNames);
                break;
            }
        }
        if (orderNames.isEmpty() || !orderNames.get(0).equals("skip " + noRoute.name())) {
            // Ties at the top are ordered by distance, so only a strictly larger first room is required.
            if (!orderNames.isEmpty() && order.get(0)[0] < noRoute.unfound()) {
                failures.add("the first decision was " + orderNames.get(0) + ", not the room with the most unfound ("
                        + noRoute.name() + "=" + noRoute.unfound() + ")");
            }
        }
        // ---- never behind a closed door ---------------------------------------------------------------------
        for (Room b : blocked) {
            if (targets.contains(b.name())) {
                failures.add(b.name() + " is behind a closed door and was a target");
            }
        }
        // ---- the wither door: the only thing left, so it goes there; no key = it waits saying so; with a key it
        // opens it itself (killer560, 2026-10-06), and then takes the room behind it --------------------------------
        if (closedDoor != null && blockedRouted != null && routes.containsKey(blockedRouted.name())) {
            String doorWait = null;
            for (String l : log) {
                if (l.contains("[AutoSecret] waiting at the wither door at cell") && l.contains("no wither key")) {
                    doorWait = l;
                }
            }
            boolean wentToDoor = log.stream().anyMatch(l -> l.contains("[AutoSecret] wither door at cell")
                    && l.contains("the only way on"));
            BlockPos doorAt = closedDoor;
            double fromDoor = ctx.computeOnClient(mc -> Math.sqrt(mc.player.blockPosition().distSqr(doorAt)));
            String status = ctx.computeOnClient(mc -> (String) ModUnderTest.staticCall(AS, "statusText"));
            println("at the door: chosen " + wentToDoor + ", no-key wait " + (doorWait != null) + ", phase " + phaseAtEnd
                    + ", " + String.format(Locale.US, "%.1f", fromDoor) + " blocks from the lock block, status \""
                    + status + "\"");
            if (!wentToDoor || doorWait == null || !phaseAtEnd.equals("DOOR") || fromDoor > 5) {
                failures.add("with nothing left but the closed door it did not go and wait at it without a key (chosen "
                        + wentToDoor + ", no-key line " + (doorWait != null) + ", phase " + phaseAtEnd + ", "
                        + String.format(Locale.US, "%.1f", fromDoor) + " blocks away)");
            } else {
                // The sim opens a door it knows about for a wither key in hand: register this one, give the key.
                ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimDoors", "addDoor",
                        new Class<?>[]{BlockPos.class}, new Object[]{doorAt}));
                long markDoor = LogTap.mark();
                giveWitherKey(ctx);
                boolean clicked = false;
                boolean opened = false;
                boolean took = false;
                for (int i = 0; i < 20 * 60 && !took; i += 10) {
                    ctx.waitTicks(10);
                    List<String> since = LogTap.since(markDoor);
                    clicked |= since.stream().anyMatch(l -> l.contains("[AutoSecret] right-clicked the wither door"));
                    opened |= since.stream().anyMatch(l -> l.contains("[AutoSecret] the wither door at cell")
                            && l.contains("is open"));
                    took = since.stream().anyMatch(l -> l.contains("[AutoSecret] route " + blockedRouted.name() + " finished"));
                    if (!took && since.stream().anyMatch(l -> l.contains("start node did not arm"))) {
                        println("a start node did not arm after the door: " + ctx.computeOnClient(SimAutoSecretTests::whereNow));
                        break;
                    }
                }
                boolean air = ctx.computeOnClient(mc -> mc.level.getBlockState(doorAt).isAir());
                println("key given: clicked " + clicked + ", door open " + opened + " (lock block air " + air
                        + "), the route in " + blockedRouted.name() + (took ? " ran" : " NEVER ran"));
                for (String l : LogTap.since(markDoor)) {
                    if (l.contains("[AutoSecret]")) {
                        println("  " + l.replaceAll("^.*?\\[AutoSecret\\]", "[AutoSecret]"));
                    }
                }
                if (!clicked || !opened || !air) {
                    failures.add("with a wither key it did not open the door itself (clicked " + clicked + ", open "
                            + opened + ", air " + air + ")");
                }
                if (!took) {
                    failures.add("after the door opened the route in " + blockedRouted.name() + " never ran");
                }
            }
        }

        // ---- corrections (none expected in the sim; said, never stopped on) --------------------------------
        long corrections = log.stream().filter(l -> l.contains("[AutoSecret] correction")).count();
        println("server corrections reported: " + corrections);

        // ---- every secret found: it stops, saying so ----------------------------------------------------------
        boolean running = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(AS, "isRunning"));
        println("before 100%: Auto Secret " + (running ? "running (" + phaseAtEnd + ")" : "stopped"));
        long mark2 = LogTap.mark();
        ctx.runOnClient(mc -> {
            int total = (Integer) ModUnderTest.staticCall(SIM_SCORE, "secretsTotal");
            int found = (Integer) ModUnderTest.staticCall(SIM_SCORE, "secretsFound");
            for (int i = found; i < total; i++) {
                ModUnderTest.staticCall(SIM_SCORE, "secretFound");
            }
        });
        boolean full = false;
        for (int i = 0; i < 200 && !full; i++) {
            ctx.waitTicks(2);
            full = ctx.computeOnClient(mc -> (Double) ModUnderTest.staticCall(DUNGEON_INFO, "secretsFoundPercent")) >= 100.0;
        }
        if (!full) {
            failures.add("the tab list never said 100% secrets after the sim's score was filled");
            return;
        }
        if (!running) {
            Boolean again = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(AS, "start"));
            println("started again at 100%: " + again);
        }
        boolean stopped = false;
        for (int i = 0; i < 100 && !stopped; i++) {
            ctx.waitTicks(2);
            stopped = !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(AS, "isRunning"));
        }
        boolean said = LogTap.since(mark2).stream().anyMatch(l -> l.contains("[AutoSecret] stopped: all secrets found"));
        println("at 100%: " + (stopped ? "stopped" : "STILL RUNNING") + (said ? ", said all secrets found" : ""));
        if (!stopped || !said) {
            failures.add("with every secret found it did not stop saying so (stopped " + stopped + ", said " + said + ")");
        }
    }

    // ================================================================================================ helpers

    /**
     * Whether a map path can start again once he stands in the room: the mod starts none from inside a room named
     * Maze or Boulder (until done) or Trap past its start (AutoClearUtils.canPath, killer560's rule), so a one-node
     * route or an insta-clear landing there leaves Auto Secret waiting for him to walk out, which the run never does
     * (2026-10-07: an insta clear into Arrow Trap, then "can't path from here" for the rest of the run).
     */
    private static boolean pathsOut(Room r) {
        return !r.name().contains("Maze") && !r.name().contains("Boulder") && !r.name().contains("Trap");
    }

    /** Where he stands and where the last map warp put him (ClearExecutor.arrivalPos). Client thread. */
    private static String whereNow(Minecraft mc) {
        Vec3 at = mc.player.position();
        Object arrival = ModUnderTest.staticCall(EXECUTOR, "arrivalPos");
        return String.format(Locale.US, "he stands at %.2f %.2f %.2f; last map arrival %s", at.x, at.y, at.z, arrival);
    }

    /** Every room the mod's RoomStatus lists, with reachability from where he stands. Client thread. */
    private static List<Room> readRooms(Minecraft mc) {
        Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
        int here = (Integer) ModUnderTest.call(layout, "currentRoom", new Class<?>[]{}, new Object[]{});
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) ModUnderTest.staticCall(ROOM_STATUS, "rooms");
        List<Room> out = new ArrayList<>();
        for (Object r : list) {
            int id = (Integer) ModUnderTest.call(r, "room", new Class<?>[]{}, new Object[]{});
            boolean reach = id == here || ModUnderTest.staticCall(MAP_PATHFINDER, "findPath",
                    new Class<?>[]{layout.getClass(), int.class, int.class, boolean.class},
                    new Object[]{layout, here, id, false}) != null;
            out.add(new Room(id, (String) ModUnderTest.call(r, "name", new Class<?>[]{}, new Object[]{}),
                    (String) ModUnderTest.call(r, "type", new Class<?>[]{}, new Object[]{}),
                    (Integer) ModUnderTest.call(r, "unfound", new Class<?>[]{}, new Object[]{}),
                    (Integer) ModUnderTest.call(r, "mainTile", new Class<?>[]{}, new Object[]{}), reach));
        }
        return out;
    }

    /**
     * Seeds two INSTA observations for entering {@code r} from the room before it on the map path from here, landing on
     * a standable block of its main tile, into a scratch evidence file. Client thread. @return {key, from} or null.
     */
    private static String[] seedInsta(Room r) {
        Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
        int here = (Integer) ModUnderTest.call(layout, "currentRoom", new Class<?>[]{}, new Object[]{});
        @SuppressWarnings("unchecked")
        List<Object> path = (List<Object>) ModUnderTest.staticCall(MAP_PATHFINDER, "findPath",
                new Class<?>[]{layout.getClass(), int.class, int.class, boolean.class},
                new Object[]{layout, here, r.id(), false});
        if (path == null || path.size() < 2) {
            return null;
        }
        int fromId = (Integer) ModUnderTest.call(path.get(path.size() - 2), "room", new Class<?>[]{}, new Object[]{});
        String from = (String) ModUnderTest.call(layout, "name", new Class<?>[]{int.class}, new Object[]{fromId});
        if (from == null || from.equals("Unknown")) {
            return null;
        }
        BlockPos centre = (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class},
                new Object[]{r.mainTile()});
        BlockPos land = (BlockPos) ModUnderTest.staticCall(TELEPORT_UTILS, "etherwarpableInTile",
                new Class<?>[]{BlockPos.class, Vec3.class}, new Object[]{centre, Vec3.atCenterOf(centre)});
        if (land == null) {
            return null;
        }
        String key = (String) ModUnderTest.staticCall(INSTA, "entryKeyFor",
                new Class<?>[]{String.class, BlockPos.class, String.class}, new Object[]{r.name(), land, from});
        if (key == null) {
            return null;
        }
        try {
            Path scratch = Files.createTempFile("autosecret-insta", ".json");
            Files.deleteIfExists(scratch);
            ModUnderTest.staticCall(INSTA, "testUseFile", new Class<?>[]{String.class}, new Object[]{scratch.toString()});
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        ModUnderTest.staticCall(INSTA, "testClearAll");
        for (int i = 0; i < 2; i++) {
            ModUnderTest.staticCall(INSTA, "testAdd", new Class<?>[]{String.class, String.class, String.class, long.class},
                    new Object[]{r.name(), key, "INSTA", 500L});
        }
        boolean known = (Boolean) ModUnderTest.staticCall(INSTA, "knownToInstaClear",
                new Class<?>[]{String.class, String.class}, new Object[]{r.name(), key});
        System.out.println("[" + NAME + "] seeded insta entry " + r.name() + " / " + key + " (land " + land.toShortString()
                + ", from " + from + "): known=" + known);
        return known ? new String[]{key, from} : null;
    }

    /** A one-node route: a ROTATE start node standing on a standable block of the room's main tile. */
    private static JsonObject routeFor(ClientGameTestContext ctx, Room r) {
        return ctx.computeOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            int[] cr = (int[]) ModUnderTest.call(layout, "clayRotation", new Class<?>[]{int.class}, new Object[]{r.id()});
            if (cr == null) {
                return null;
            }
            BlockPos centre = (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class},
                    new Object[]{r.mainTile()});
            BlockPos stand = (BlockPos) ModUnderTest.staticCall(TELEPORT_UTILS, "etherwarpableInTile",
                    new Class<?>[]{BlockPos.class, Vec3.class}, new Object[]{centre, Vec3.atCenterOf(centre)});
            if (stand == null) {
                return null;
            }
            Object frame;
            try {
                frame = Class.forName(FRAME).getConstructor(String.class, int.class, int.class, int.class)
                        .newInstance(r.name(), cr[0], cr[1], cr[2]);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            Vec3 rel = (Vec3) ModUnderTest.staticCall(COORDS, "toRelative", new Class<?>[]{frame.getClass(), Vec3.class},
                    new Object[]{frame, new Vec3(stand.getX() + 0.5, stand.getY() + 1, stand.getZ() + 0.5)});
            JsonObject node = new JsonObject();
            node.addProperty("type", "ROTATE");
            node.addProperty("x", rel.x);
            node.addProperty("y", rel.y);
            node.addProperty("z", rel.z);
            node.addProperty("yaw", 0f);
            node.addProperty("pitch", 0f);
            node.addProperty("start", true);
            JsonArray nodes = new JsonArray();
            nodes.add(node);
            JsonObject route = new JsonObject();
            route.add("nodes", nodes);
            System.out.println("[" + NAME + "] route for " + r.name() + ": start node on " + stand.toShortString()
                    + " (relative " + String.format(Locale.US, "%.1f %.1f %.1f", rel.x, rel.y, rel.z) + ")");
            return route;
        });
    }

    private static void writeRoutes(ClientGameTestContext ctx, Map<String, JsonObject> routes) {
        ctx.runOnClient(mc -> {
            try {
                Path file = (Path) ModUnderTest.staticCall(STORE, "routesFile");
                JsonObject all = new JsonObject();
                routes.forEach(all::add);
                JsonObject root = new JsonObject();
                root.addProperty("version", 1);
                root.add("routes", all);
                Files.createDirectories(file.getParent());
                Files.writeString(file, root.toString(), StandardCharsets.UTF_8);
                ModUnderTest.staticCall(STORE, "reload");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        ctx.waitTicks(2);
    }

    /** Sets the live map's tile for a door cell (a {@code LiveMapFeature.Tile} name); returns the old one. Client thread.
     *  The sim publishes no wither doors, and this is the one map fact the test has to supply. */
    private static Object markMapDoor(int cell, String tile) {
        try {
            Class<?> live = Class.forName("com.killer560.hub.livemap.LiveMapFeature");
            java.lang.reflect.Field f = live.getDeclaredField("grid");
            f.setAccessible(true);
            Object grid = f.get(null);
            Object old = java.lang.reflect.Array.get(grid, cell);
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object value = Enum.valueOf((Class) Class.forName("com.killer560.hub.livemap.LiveMapFeature$Tile"), tile);
            java.lang.reflect.Array.set(grid, cell, value);
            return old;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    /** Sets one block on the integrated server and waits until the client has it. */
    private static void setBlock(ClientGameTestContext ctx, BlockPos pos,
                                 net.minecraft.world.level.block.state.BlockState state) {
        AtomicReference<Boolean> done = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(uuid);
                if (sp == null) {
                    done.set(false);
                    return;
                }
                ((net.minecraft.server.level.ServerLevel) sp.level()).setBlockAndUpdate(pos, state);
                done.set(true);
            });
        });
        ctx.waitFor(mc -> done.get() != null, 200);
        for (int i = 0; i < 100 && !ctx.computeOnClient(mc -> mc.level.getBlockState(pos).is(state.getBlock())); i++) {
            ctx.waitTicks(1);
        }
        ctx.waitTicks(2);
    }

    /** A sim Wither Key into hotbar slot 5, on the server (the sim's own key item, SimDoors.createWitherKey). */
    private static void giveWitherKey(ClientGameTestContext ctx) {
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
                sp.getInventory().setItem(5, (ItemStack) ModUnderTest.staticCall("com.killer560.hub.roomsim.SimDoors",
                        "createWitherKey"));
                given.set(true);
            });
        });
        ctx.waitFor(mc -> given.get() != null, 200);
        ctx.waitTicks(5);
    }

    private static void giveAotv(ClientGameTestContext ctx) {
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
    }

    /** Waits (at most 90 s) for every chunk under the floor's tiles to be on the client (as 95 does). */
    private static void awaitFloorChunks(ClientGameTestContext ctx) {
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

    private static String describe(List<Room> rooms) {
        List<String> parts = new ArrayList<>();
        for (Room r : rooms) {
            parts.add(r.name() + "=" + r.unfound());
        }
        return parts.isEmpty() ? "none" : String.join(", ", parts);
    }

    private static List<String> tail(List<String> lines, int n) {
        return lines.subList(Math.max(0, lines.size() - n), lines.size());
    }

    private static void println(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}
