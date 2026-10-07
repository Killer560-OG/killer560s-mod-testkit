package dev.testkit.gametest;

import dev.testkit.compat.McCompat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 142-sim-autopilot2: Dungeon Autopilot's autopilot2 round (mod 2026-10-06) on a generated sim F7, Party mode, Auto Clear
 * off and every mob room marked cleared, so each run has only the work set up for it. Three runs on one floor:
 * <ol>
 *   <li><b>trap</b> - a route in a room named "... Trap" whose start node stands PAST the trap's start line (where
 *       {@code AutoClearUtils.canPath} refuses by name), plus a route in another room. Both routes must finish and the
 *       run must end by itself ("nothing left") - before autopilot2 it sat on "Can't start a path from here yet" for
 *       good. Then a probe at that spot: canPath with no permit (must be false, or the case proved nothing) and with
 *       the permit (must be true).</li>
 *   <li><b>key</b> - a shut wither door (map tile + coal + {@code SimDoors.addDoor}, as 102 does) cutting off a room
 *       with a route, and a Wither Key dropped ({@code SimKeys.drop}) in a room it can reach. It must go for the key,
 *       pick it up (the sim's "&lt;name&gt; has obtained Wither Key!"), open the door with the team's key, and run the
 *       route behind it.</li>
 *   <li><b>puzzle</b> - Do Puzzles on, Auto Puzzles on for Boulder and Teleport Maze only: each one on the floor is a
 *       pick, it waits for the auto, and the run carries on and ends by itself (Teleport Maze ends on its end pad in the
 *       maze, where the map used to refuse to path).</li>
 * </ol>
 * Only when named. Boulder arms only with Map Logger's capture (docs/solve.md): run with
 * {@code $env:TESTKIT_SIM_INSTANCE = "Map Logger"}.
 */
public class SimAutopilot2Tests implements FabricClientGameTest {

    private static final String NAME = "142-sim-autopilot2";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String SIM_ITEMS = "com.killer560.hub.roomsim.SimItems";
    private static final String SIM_ROOM_STATE = "com.killer560.hub.roomsim.SimRoomState";
    private static final String SIM_KEYS = "com.killer560.hub.roomsim.SimKeys";
    private static final String SIM_DOORS = "com.killer560.hub.roomsim.SimDoors";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String ROOM_STATUS = "com.killer560.hub.livemap.RoomStatus";
    private static final String MAP_PATHFINDER = "com.killer560.hub.livemap.autoclear.DungeonMapPathfinder";
    private static final String TELEPORT_UTILS = "com.killer560.hub.livemap.autoclear.TeleportUtils";
    private static final String CLEAR_UTILS = "com.killer560.hub.livemap.autoclear.AutoClearUtils";
    private static final String ROOM_DB = "com.killer560.hub.roomdatabase.RoomDatabase";
    private static final String LIVE_MAP_CONFIG = "com.killer560.hub.livemap.LiveMapConfig";
    private static final String STORE = "com.killer560.hub.autoroutes.RouteStore";
    private static final String COORDS = "com.killer560.hub.autoroutes.RouteCoords";
    private static final String FRAME = "com.killer560.hub.autoroutes.RouteCoords$Frame";
    private static final String AR_CONFIG = "com.killer560.hub.autoroutes.AutoRoutesConfig";
    private static final String AS = "com.killer560.hub.autosecret.AutoSecretFeature";
    private static final String AS_CONFIG = "com.killer560.hub.autosecret.AutoSecretConfig";
    private static final String AUTO_CLEAR_CONFIG = "com.killer560.hub.autoclear.AutoClearConfig";
    private static final String AUTO_CLEAR = "com.killer560.hub.autoclear.AutoClearFeature";
    private static final String SCORE_CONFIG = "com.killer560.hub.scorecalc.ScoreCalculatorConfig";
    /** Score Calculator as this case found it; put back in the finally, so later cases see a fresh config. */
    private static boolean scoreWasOn;
    private static final String PUZZLES = "com.killer560.hub.autopuzzles.AutoPuzzlesConfig";
    private static final String SOLVERS = "com.killer560.hub.puzzlesolvers.";
    private static final int GRID = 11;
    private static final int CASE_TICKS = 20 * 200;

    private record Room(int id, String name, String type, int unfound, int mainTile, boolean reachable, boolean mob) {
    }

    private static final class Result {
        double travelled;
        int usesInside;
        int usesOutside;
        int ticksInside;
        boolean finished;
        String stopReason;
        List<String> log = new ArrayList<>();
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
            println("SKIPPED - needs his real rooms and room database");
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);
        ctx.runOnClient(mc -> {
            puzzles(false);
            Object map = ModUnderTest.config(LIVE_MAP_CONFIG);
            ModUnderTest.set(map, "setEnabled", true);
            ModUnderTest.set(map, "setInteractiveMapEnabled", true);
            Object ar = ModUnderTest.config(AR_CONFIG);
            ModUnderTest.set(ar, "setEnabled", true);
            ModUnderTest.set(ar, "setLegitMode", false);
            ModUnderTest.set(ar, "setStartFromStartNodeOnly", true);
            ModUnderTest.call(ar, "setHeight", new Class<?>[]{float.class}, new Object[]{1.0f});
            scoreWasOn = ModUnderTest.getBoolean(ModUnderTest.config(SCORE_CONFIG), "isEnabled");
            ModUnderTest.set(ModUnderTest.config(SCORE_CONFIG), "setEnabled", true);
            ModUnderTest.set(ModUnderTest.config(AUTO_CLEAR_CONFIG), "setEnabled", false);
            Object as = ModUnderTest.config(AS_CONFIG);
            ModUnderTest.set(as, "setChatFeedback", true);
            ModUnderTest.set(as, "setRouteStallSeconds", 10);
            ModUnderTest.set(as, "setInstaClear", false);
            ModUnderTest.set(as, "setDoPuzzles", false);
            ModUnderTest.set(as, "setBloodFirst", false);
            Object party = ModUnderTest.enumValue(AS_CONFIG + "$RunMode", "PARTY");
            ModUnderTest.call(as, "setRunMode", new Class<?>[]{party.getClass()}, new Object[]{party});
        });
        int renderBefore = ctx.computeOnClient(mc -> mc.options.renderDistance().get());
        ctx.runOnClient(mc -> mc.options.renderDistance().set(16));
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter", new Class<?>[]{String.class},
                new Object[]{"gametest"}));
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
                puzzles(false);
                ModUnderTest.set(ModUnderTest.config(SCORE_CONFIG), "setEnabled", scoreWasOn);
            });
            writeRoutes(ctx, Map.of());
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
        // ---- a floor with a trap room that has secrets, and a Boulder or a Teleport Maze if one comes up ----------
        List<Room> rooms = List.of();
        Room trap = null;
        List<String> puzzleRooms = new ArrayList<>();
        for (int attempt = 1; attempt <= 6; attempt++) {
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
            awaitFloorChunks(ctx);
            rooms = ctx.computeOnClient(SimAutopilot2Tests::readRooms);
            trap = null;
            puzzleRooms.clear();
            for (Room r : rooms) {
                if (r.type().equals("TRAP") && r.reachable() && trap == null) {
                    trap = r;
                }
                if (r.name().equals("Boulder") || r.name().equals("Teleport Maze")) {
                    puzzleRooms.add(r.name());
                }
            }
            println("floor " + attempt + ": " + rooms.size() + " rooms; trap " + (trap == null ? "none" : trap.name() + "="
                    + trap.unfound()) + "; Boulder/Teleport Maze " + puzzleRooms);
            if (trap != null && (!puzzleRooms.isEmpty() || attempt >= 4)) {
                break;
            }
        }
        if (trap == null) {
            failures.add("no generated floor in 6 had a trap room with secrets");
            return;
        }
        loadout(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimRun", "begin",
                new Class<?>[]{Minecraft.class, BlockPos.class},
                new Object[]{mc, ModUnderTest.staticCall("com.killer560.hub.roomsim.SimBuilder", "entranceDoor")}));
        for (int i = 0; i < 400 && !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(
                "com.killer560.hub.roomsim.SimRun", "isRunning")); i++) {
            ctx.waitTicks(1);
        }
        ctx.waitTicks(40);
        ground(ctx);
        int marked = 0;
        for (Room r : rooms) {
            if (r.mob()) {
                ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_ROOM_STATE, "markCleared", new Class<?>[]{String.class},
                        new Object[]{r.name()}));
                marked++;
            }
        }
        println(marked + " mob room(s) marked cleared (Auto Clear is off)");

        caseTrap(ctx, failures, rooms, trap);
        caseKey(ctx, failures);
        casePuzzle(ctx, failures, puzzleRooms);
    }

    // ================================================================================================ trap

    private static final String AUTO_TRAP = "com.killer560.hub.autotrap.AutoTrap";

    /**
     * Auto Trap (killer560, 2026-10-06): his trap route is captured from Auto Routes into Auto Trap's Full Trap slot (the
     * tab's Capture), his own Auto Routes copy removed, Auto Trap on. The route is one WALK start node inside the trap
     * facing a doorway - "I will add a start node of some sort, whether it is walk or pearl" - which sprints him out. The
     * autopilot must warp onto the start node from OUTSIDE (the one warp in), send no warp while he is inside, the moment
     * he leaves the room Auto Trap must end the trap route ("the second it exits the trap room it stops all the trap room
     * programming"), and the autopilot must carry on to the next room from where he is.
     */
    private static void caseTrap(ClientGameTestContext ctx, List<String> failures, List<Room> rooms, Room trap) {
        Object[] exit = trapExit(ctx, trap);
        if (exit == null) {
            failures.add("trap: no straight run from inside " + trap.name() + " out through a doorway to put a walk node on");
            return;
        }
        BlockPos stand = (BlockPos) exit[0];
        float yaw = (Float) exit[1];
        Room other = null;
        for (Room x : rooms) {
            if (x.reachable() && x.unfound() > 0 && !x.type().equals("TRAP") && !x.name().contains("Trap")
                    && !x.type().equals("PUZZLE") && !x.type().equals("BLOOD") && !x.type().equals("ENTRANCE")
                    && !x.name().equals("Unknown")) {
                other = x;
                break;
            }
        }
        if (other == null) {
            failures.add("trap: no other room with secrets to carry on to");
            return;
        }
        String trapName = trap.name();
        String otherName = other.name();
        // His recording -> Auto Trap's Full Trap slot, then his own copy goes (the overlay would win anyway).
        writeRoutes(ctx, Map.of(trapName, walkRoute(ctx, trap, stand, yaw)));
        String captured = ctx.computeOnClient(mc -> (String) ModUnderTest.staticCall(AUTO_TRAP, "capture",
                new Class<?>[]{String.class, classOf(AUTO_TRAP + "$Mode")},
                new Object[]{trapName, ModUnderTest.enumValue(AUTO_TRAP + "$Mode", "FULL")}));
        writeRoutes(ctx, Map.of(otherName, route(ctx, other, standIn(ctx, other))));
        ctx.runOnClient(mc -> ModUnderTest.staticCall(AUTO_TRAP, "setEnabled", new Class<?>[]{boolean.class},
                new Object[]{true}));
        boolean usable = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(AUTO_TRAP, "usable",
                new Class<?>[]{String.class}, new Object[]{trapName}));
        println("trap: walk node on " + stand.toShortString() + " facing yaw " + yaw + "; capture: " + captured
                + "; Auto Trap usable for " + trapName + ": " + usable + "; other route in " + otherName);
        if (!usable) {
            failures.add("trap: Auto Trap has no usable route after the capture (" + captured + ")");
            return;
        }
        Result r = run(ctx, "trap", trapName);
        int trapPick = -1;
        int left = -1;
        int otherDone = -1;
        for (int i = 0; i < r.log.size(); i++) {
            String l = r.log.get(i);
            if (trapPick < 0 && l.contains("| pick SECRET " + trapName + " ")) {
                trapPick = i;
            }
            if (left < 0 && l.contains("[AutoTrap] left " + trapName)) {
                left = i;
            }
            if (otherDone < 0 && l.contains("[AutoSecret] route " + otherName + " finished")) {
                otherDone = i;
            }
        }
        boolean trapStop = r.stopReason != null && r.stopReason.contains("trap room");
        println("trap: picked at line " + trapPick + ", Auto Trap saw him leave at line " + left + ", " + otherName
                + " route done at line " + otherDone + "; warp uses entering " + r.usesOutside + ", from inside "
                + r.usesInside + ", ticks inside " + r.ticksInside + "; stopped: " + r.stopReason);
        if (trapPick < 0) {
            failures.add("trap: the autopilot never took " + trapName + " through Auto Trap");
        }
        if (r.ticksInside == 0) {
            failures.add("trap: he never stood inside " + trapName + " - the case measured nothing");
        }
        if (r.usesOutside == 0) {
            failures.add("trap: no warp use was sent to enter the trap room");
        }
        if (r.usesInside > 0) {
            failures.add("trap: " + r.usesInside + " warp use packet(s) were sent from INSIDE the trap room");
        }
        if (left < 0) {
            failures.add("trap: Auto Trap never ended trap mode on leaving the room");
        }
        if (otherDone < 0 || otherDone < left) {
            failures.add("trap: it did not carry on to " + otherName + " after walking out of the trap");
        }
        if (trapStop || !r.finished) {
            failures.add("trap: the run did not end by itself outside the trap (stopped: " + r.stopReason + ")");
        }
        // ---- the rule at any spot inside: no path from inside a trap, permit or not ----
        tpTo(ctx, stand.above());
        ground(ctx);
        Boolean with = ctx.computeOnClient(mc -> {
            ModUnderTest.staticCall(CLEAR_UTILS, "permitLeave", new Class<?>[]{String.class}, new Object[]{trapName});
            return (Boolean) ModUnderTest.staticCall(CLEAR_UTILS, "canPath", new Class<?>[]{classOf(LAYOUT)},
                    new Object[]{ModUnderTest.staticCall(LAYOUT, "capture")});
        });
        println("trap: canPath inside the trap with a leave permit: " + with);
        if (Boolean.TRUE.equals(with)) {
            failures.add("trap: canPath allowed a path from inside the trap room");
        }
        ctx.runOnClient(mc -> ModUnderTest.staticCall(AUTO_TRAP, "setEnabled", new Class<?>[]{boolean.class},
                new Object[]{false}));
        tpTo(ctx, standIn(ctx, other).above());
        ground(ctx);
    }

    /**
     * A standable block inside the trap room on the axis of one of its doorways, 5 blocks in, with two blocks of air all
     * the way out through the doorway and 4 blocks beyond - and the real yaw facing out. {stand, yaw} or null.
     */
    private static Object[] trapExit(ClientGameTestContext ctx, Room trap) {
        AtomicReference<Object[]> out = new AtomicReference<>();
        Object layoutObj = ctx.computeOnClient(mc -> ModUnderTest.staticCall(LAYOUT, "capture"));
        int[] tiles = ctx.computeOnClient(mc -> (int[]) ModUnderTest.call(layoutObj, "tiles", new Class<?>[]{int.class},
                new Object[]{trap.id()}));
        List<int[]> tries = new ArrayList<>();   // {doorCell, dx, dz}
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int t : tiles) {
            int x = t % GRID;
            int z = t / GRID;
            for (int[] d : dirs) {
                int dxc = x + d[0];
                int dzc = z + d[1];
                if (dxc < 0 || dxc >= GRID || dzc < 0 || dzc >= GRID) {
                    continue;
                }
                int door = dzc * GRID + dxc;
                int type = ctx.computeOnClient(mc -> (Integer) ModUnderTest.call(layoutObj, "doorType",
                        new Class<?>[]{int.class}, new Object[]{door}));
                if (type == 1) {
                    tries.add(new int[]{door, d[0], d[1]});
                }
            }
        }
        for (int[] tr : tries) {
            BlockPos lock = ctx.computeOnClient(mc -> (BlockPos) ModUnderTest.staticCall(LAYOUT, "doorBlock",
                    new Class<?>[]{int.class}, new Object[]{tr[0]}));
            AtomicReference<Boolean> done = new AtomicReference<>();
            ctx.runOnClient(mc -> {
                var server = mc.getSingleplayerServer();
                server.execute(() -> {
                    var level = server.overworld();
                    for (int dy = -1; dy <= 1 && out.get() == null; dy++) {
                        BlockPos feet0 = lock.offset(-tr[1] * 5, dy, -tr[2] * 5);
                        if (!solid(level, feet0.below())) {
                            continue;
                        }
                        boolean clear = true;
                        for (int k = -5; k <= 4 && clear; k++) {
                            BlockPos f = lock.offset(tr[1] * k, dy, tr[2] * k);
                            clear = !solid(level, f) && !solid(level, f.above());
                        }
                        if (clear) {
                            float yaw = tr[1] == 1 ? -90f : tr[1] == -1 ? 90f : tr[2] == 1 ? 0f : 180f;
                            out.set(new Object[]{feet0.below(), yaw});
                        }
                    }
                    done.set(true);
                });
            });
            ctx.waitFor(mc -> done.get() != null, 200);
            if (out.get() != null) {
                return out.get();
            }
        }
        return null;
    }

    private static boolean solid(net.minecraft.server.level.ServerLevel level, BlockPos p) {
        return !level.getBlockState(p).getCollisionShape(level, p).isEmpty();
    }

    /** A one-node route: a WALK start node on {@code stand} facing {@code realYaw} (it sprints along the look). */
    private static JsonObject walkRoute(ClientGameTestContext ctx, Room r, BlockPos stand, float realYaw) {
        return ctx.computeOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            int[] cr = (int[]) ModUnderTest.call(layout, "clayRotation", new Class<?>[]{int.class}, new Object[]{r.id()});
            Object frame;
            try {
                frame = Class.forName(FRAME).getConstructor(String.class, int.class, int.class, int.class)
                        .newInstance(r.name(), cr[0], cr[1], cr[2]);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            Vec3 rel = (Vec3) ModUnderTest.staticCall(COORDS, "toRelative", new Class<?>[]{frame.getClass(), Vec3.class},
                    new Object[]{frame, new Vec3(stand.getX() + 0.5, stand.getY() + 1, stand.getZ() + 0.5)});
            float relYaw = (Float) ModUnderTest.staticCall(COORDS, "toRelativeYaw",
                    new Class<?>[]{frame.getClass(), float.class}, new Object[]{frame, realYaw});
            JsonObject node = new JsonObject();
            node.addProperty("type", "WALK");
            node.addProperty("x", rel.x);
            node.addProperty("y", rel.y);
            node.addProperty("z", rel.z);
            node.addProperty("yaw", relYaw);
            node.addProperty("pitch", 0f);
            node.addProperty("start", true);
            JsonArray nodes = new JsonArray();
            nodes.add(node);
            JsonObject route = new JsonObject();
            route.add("nodes", nodes);
            return route;
        });
    }

    // ================================================================================================ key

    private static void caseKey(ClientGameTestContext ctx, List<String> failures) {
        // A door that cuts off a room with secrets (102's search): marked a wither door on the map, coal on its lock.
        List<Room> before = ctx.computeOnClient(SimAutopilot2Tests::readRooms);
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
        int cell = -1;
        BlockPos lock = null;
        Room behind = null;
        int bestCut = Integer.MAX_VALUE;
        for (int door : doors) {
            BlockPos l = ctx.computeOnClient(mc -> (BlockPos) ModUnderTest.staticCall(LAYOUT, "doorBlock",
                    new Class<?>[]{int.class}, new Object[]{door}));
            Object was = ctx.computeOnClient(mc -> markMapDoor(door, "DOOR_WITHER"));
            setBlock(ctx, l, net.minecraft.world.level.block.Blocks.COAL_BLOCK.defaultBlockState());
            int cut = 0;
            Room first = null;
            for (Room r : ctx.computeOnClient(SimAutopilot2Tests::readRooms)) {
                if (!r.reachable() && !r.type().equals("BLOOD")) {
                    cut++;
                    if (first == null && r.unfound() > 0 && !r.type().equals("PUZZLE") && !r.name().contains("Trap")
                            && !r.name().equals("Unknown")) {
                        first = r;
                    }
                }
            }
            setBlock(ctx, l, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            ctx.runOnClient(mc -> markMapDoor(door, ((Enum<?>) was).name()));
            if (first != null && cut < bestCut) {
                bestCut = cut;
                cell = door;
                lock = l;
                behind = first;
            }
        }
        if (behind == null) {
            failures.add("key: no door on this floor cuts off a room with secrets");
            return;
        }
        int doorCell = cell;
        BlockPos doorLock = lock;
        ctx.runOnClient(mc -> markMapDoor(doorCell, "DOOR_WITHER"));
        setBlock(ctx, doorLock, net.minecraft.world.level.block.Blocks.COAL_BLOCK.defaultBlockState());
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_DOORS, "addDoor", new Class<?>[]{BlockPos.class},
                new Object[]{doorLock}));
        // The key: in the reachable room furthest from him, on a standable block of its main tile.
        Vec3 me = ctx.computeOnClient(mc -> mc.player.position());
        Room keyRoom = null;
        double far = -1;
        // A dropped key is an entity: the client sees it only within entity tracking range, so not the far end of the
        // floor (run 1 dropped one 180 blocks off and the client never had it). 25-100 blocks away.
        for (Room r : ctx.computeOnClient(SimAutopilot2Tests::readRooms)) {
            if (!r.reachable() || r.name().contains("Trap") || r.name().contains("Maze") || r.name().contains("Boulder")
                    || r.type().equals("BLOOD")) {
                continue;
            }
            BlockPos c = ctx.computeOnClient(mc -> (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter",
                    new Class<?>[]{int.class}, new Object[]{r.mainTile()}));
            double d = me.distanceTo(Vec3.atCenterOf(c));
            if (d > far && d <= 100 && d >= 25) {
                far = d;
                keyRoom = r;
            }
        }
        if (keyRoom == null) {
            failures.add("key: no reachable room 25-100 blocks from him to drop the key in");
            setBlock(ctx, doorLock, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            return;
        }
        BlockPos keyStand = standIn(ctx, keyRoom);
        Vec3 keyAt = new Vec3(keyStand.getX() + 0.5, keyStand.getY() + 1.0, keyStand.getZ() + 0.5);
        Room target = behind;
        writeRoutes(ctx, Map.of(target.name(), route(ctx, target, standIn(ctx, target))));
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_KEYS, "drop", new Class<?>[]{Minecraft.class, Vec3.class,
                boolean.class}, new Object[]{mc, keyAt, false}));
        ctx.waitTicks(40);
        int seen = ctx.computeOnClient(mc -> ((List<?>) ModUnderTest.staticCall("com.killer560.hub.doorkeys.DungeonKeys",
                "dropped", new Class<?>[]{Minecraft.class}, new Object[]{mc})).size());
        println(String.format(Locale.US, "key: wither door at cell %d (lock %s) cuts off %d room(s), route in %s behind it;"
                        + " key dropped in %s at %.1f %.1f %.1f, %.1f blocks from him; the client sees %d dropped key(s)",
                doorCell, doorLock.toShortString(), bestCut, target.name(), keyRoom.name(), keyAt.x, keyAt.y, keyAt.z, far,
                seen));
        if (seen < 1) {
            failures.add("key: the client never saw the dropped key (DungeonKeys.dropped is empty)");
            setBlock(ctx, doorLock, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            return;
        }
        Result r = run(ctx, "key", null);
        boolean picked = r.log.stream().anyMatch(l -> l.contains("[Autopilot] ") && l.contains("pick KEY"));
        boolean pickedUp = r.log.stream().anyMatch(l -> l.contains("[AutoSecret] key: picked up"));
        boolean chat = r.log.stream().anyMatch(l -> l.contains("has obtained Wither Key!"));
        boolean clicked = r.log.stream().anyMatch(l -> l.contains("right-clicked the wither door"));
        boolean open = ctx.computeOnClient(mc -> mc.level.getBlockState(doorLock).isAir());
        boolean ran = r.log.stream().anyMatch(l -> l.contains("[AutoSecret] route " + target.name() + " finished"));
        String pickup = ctx.computeOnClient(mc -> String.valueOf(ModUnderTest.staticCall(SIM_KEYS, "lastPickup")));
        println("key: chose the key " + picked + ", picked up " + pickedUp + " (sim: " + pickup + "), chat line " + chat
                + ", door clicked " + clicked + ", door open " + open + ", route behind it ran " + ran + ", stopped: "
                + r.stopReason);
        if (!picked || !pickedUp || !chat) {
            failures.add("key: it did not go and pick up the key (chose " + picked + ", picked up " + pickedUp + ", chat "
                    + chat + ")");
        }
        if (!clicked || !open) {
            failures.add("key: it did not open the door with the team's key (clicked " + clicked + ", open " + open + ")");
        }
        // keys-range: the pickup range (base + 5 from 0.27.2, no talisman: it does not apply to keys) - it must have been
        // picked up from further than the old 3-block guess and within 6, i.e. it stopped in range instead of warping onto the key.
        double range = ctx.computeOnClient(mc -> (Double) ModUnderTest.staticCall(SIM_KEYS, "pickupRange"));
        double at = -1;
        try {
            at = Double.parseDouble(pickup.substring(pickup.indexOf('|') + 1));
        } catch (RuntimeException e) {
            // no pickup recorded
        }
        boolean noWarpLine = r.log.stream().anyMatch(l -> l.contains("inside the") && l.contains("pickup range"));
        println(String.format(Locale.US, "key: pickup range %.1f; picked up from %.2f blocks%s", range, at,
                noWarpLine ? " (already in range - no warp)" : ""));
        if (at <= 3.0) {
            failures.add(String.format(Locale.US, "key: picked up from %.2f blocks - not beyond 3, so the longer range was"
                    + " not used", at));
        }
        if (Math.abs(range - 6.0) > 0.01) {
            failures.add(String.format(Locale.US, "key: the pickup range is %.1f, expected 6.0 (base 1 + 5, no talisman)", range));
        }
        if (at > range + 0.01) {
            failures.add(String.format(Locale.US, "key: the sim picked it up from %.2f, beyond its own %.1f range", at, range));
        }
        if (!ran) {
            failures.add("key: the route behind the door never ran");
        }
    }

    // ================================================================================================ puzzle

    private static void casePuzzle(ClientGameTestContext ctx, List<String> failures, List<String> puzzleRooms) {
        if (puzzleRooms.isEmpty()) {
            println("puzzle: no Boulder or Teleport Maze on this floor - NOT TESTED");
            return;
        }
        writeRoutes(ctx, Map.of());
        ctx.runOnClient(mc -> {
            puzzles(true);
            ModUnderTest.set(ModUnderTest.config(AS_CONFIG), "setDoPuzzles", true);
        });
        ctx.waitTicks(10);
        Result r = run(ctx, "puzzle", null);
        for (String p : puzzleRooms) {
            boolean picked = r.log.stream().anyMatch(l -> l.contains("| pick PUZZLE " + p + " "));
            boolean finished = r.log.stream().anyMatch(l -> l.contains("[AutoSecret] puzzle " + p + ": finished"));
            boolean timedOut = r.log.stream().anyMatch(l -> l.contains("[AutoSecret] puzzle " + p + ": not finished"));
            println("puzzle " + p + ": picked " + picked + ", finished " + finished + ", timed out " + timedOut);
            if (!picked) {
                failures.add("puzzle: " + p + " was never picked");
            } else if (!finished) {
                // The auto's own result (Auto Puzzles / the sim's puzzle), not the autopilot's: it gives the puzzle its
                // time and leaves. Reported, not failed - the autopilot carrying on is what this case is for.
                println("puzzle " + p + ": NOTE - its auto did not finish it in the sim (timed out " + timedOut + ")");
            }
        }
        boolean stuck = r.log.stream().anyMatch(l -> l.contains("Can't start a path from here yet"));
        if (stuck || !r.finished) {
            failures.add("puzzle: the run did not carry on and end by itself after the puzzles (stuck line " + stuck
                    + ", finished " + r.finished + ")");
        }
        ctx.runOnClient(mc -> puzzles(false));
    }

    /** Auto Puzzles: master, pathing and reposition, and only the Boulder and Teleport Maze autos (+ their solvers). */
    private static void puzzles(boolean on) {
        Object auto = ModUnderTest.config(PUZZLES);
        for (String s : new String[]{"setAutoQuizEnabled", "setAutoWeirdosEnabled", "setAutoBlazeEnabled",
                "setAutoBeamsEnabled", "setAutoIcePathEnabled", "setAutoWaterEnabled", "setAutoTicTacToeEnabled",
                "setAutoIceFillEnabled"}) {
            ModUnderTest.set(auto, s, false);
        }
        ModUnderTest.set(auto, "setAutoPuzzlesMasterEnabled", on);
        ModUnderTest.set(auto, "setEtherwarpReposition", true);
        ModUnderTest.set(auto, "setAutoPuzzlePathingEnabled", true);
        ModUnderTest.set(auto, "setAutoBoulderEnabled", on);
        ModUnderTest.set(auto, "setAutoTeleportMazeEnabled", on);
        ModUnderTest.set(ModUnderTest.config(SOLVERS + "BoulderSolverConfig"), "setEnabled", on);
        ModUnderTest.set(ModUnderTest.config(SOLVERS + "TeleportMazeSolverConfig"), "setEnabled", on);
    }

    // ================================================================================================ the run

    /** Whether he stands in the named room now (the mod's own map reading). Client thread. */
    private static boolean standsIn(Minecraft mc, String room) {
        Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
        int id = (Integer) ModUnderTest.call(layout, "roomAtWorld", new Class<?>[]{double.class, double.class},
                new Object[]{mc.player.getX(), mc.player.getZ()});
        return room.equals(ModUnderTest.call(layout, "name", new Class<?>[]{int.class}, new Object[]{id}));
    }

    /**
     * Runs the autopilot to its own stop (or the deadline). {@code trapRoom}: also sample every tick whether he stands in
     * that room and how many use packets went out that tick, filed under where he stood the tick BEFORE (a use is sent
     * from there; the tick it lands he is already somewhere else).
     */
    private static Result run(ClientGameTestContext ctx, String label, String trapRoom) {
        ground(ctx);
        Result r = new Result();
        long mark = LogTap.mark();
        if (trapRoom != null) {
            dev.testkit.harness.PacketWatch.start();
        }
        boolean wasInside = trapRoom != null && ctx.computeOnClient(mc -> standsIn(mc, trapRoom));
        int usesBefore = trapRoom == null ? 0 : dev.testkit.harness.PacketWatch.itemUses();
        Boolean started = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(AS, "startAutopilot"));
        if (!Boolean.TRUE.equals(started)) {
            r.stopReason = "refused to start";
            return r;
        }
        Vec3 last = ctx.computeOnClient(mc -> mc.player.position());
        int step = trapRoom == null ? 5 : 1;
        for (int t = 0; t < CASE_TICKS; t += step) {
            ctx.waitTicks(step);
            Vec3 now = ctx.computeOnClient(mc -> mc.player.position());
            r.travelled += now.distanceTo(last);
            last = now;
            if (trapRoom != null) {
                int uses = dev.testkit.harness.PacketWatch.itemUses();
                if (wasInside) {
                    r.usesInside += uses - usesBefore;
                } else {
                    r.usesOutside += uses - usesBefore;
                }
                usesBefore = uses;
                wasInside = ctx.computeOnClient(mc -> standsIn(mc, trapRoom));
                r.ticksInside += wasInside ? 1 : 0;
            }
            if (!(Boolean) ctx.computeOnClient(mc -> ModUnderTest.staticCall(AS, "isRunning"))) {
                r.finished = true;
                break;
            }
            if (t % 400 == 395) {
                println(label + " t=" + (t + 5) / 20 + "s: " + ctx.computeOnClient(mc -> String.join(" | ",
                        (String[]) ModUnderTest.staticCall(AS, "autopilotHud"))));
            }
        }
        if (!r.finished) {
            ctx.runOnClient(mc -> ModUnderTest.staticCall(AS, "stop", new Class<?>[]{String.class, boolean.class},
                    new Object[]{"test timeout", true}));
        }
        ctx.waitTicks(5);
        if (trapRoom != null) {
            dev.testkit.harness.PacketWatch.stop();
        }
        r.log = LogTap.since(mark);
        for (String l : r.log) {
            if (l.contains("[AutoSecret] stopped: ")) {
                r.stopReason = l.substring(l.indexOf("[AutoSecret] stopped: ") + 22);
            }
        }
        println(String.format(Locale.US, "%s: travelled %.1f blocks, finished %s, stopped: %s", label, r.travelled,
                r.finished, r.stopReason));
        for (String l : r.log) {
            if (l.contains("[Autopilot]") || l.contains("[AutoSecret]") || l.contains("has obtained")
                    || l.contains("opened a WITHER") || l.contains("[AutoPuzzles]") || l.contains("Can't start a path")) {
                String s = l.replaceAll("^.*?(\\[Autopilot\\]|\\[AutoSecret\\]|\\[CHAT\\])", "$1");
                println("  " + (s.length() > 500 ? s.substring(0, 500) + "..." : s));
            }
        }
        return r;
    }

    // ================================================================================================ the world

    private static void loadout(ClientGameTestContext ctx) {
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
                inv.setItem(1, (ItemStack) ModUnderTest.staticCall(SIM_ITEMS, "build", new Class<?>[]{String.class},
                        new Object[]{"TERMINATOR"}));
                given.set(true);
            });
        });
        ctx.waitFor(mc -> given.get() != null, 200);
        ctx.waitTicks(10);
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
    }

    private static void ground(ClientGameTestContext ctx) {
        for (int i = 0; i < 100 && !ctx.computeOnClient(mc -> mc.player.onGround()); i++) {
            ctx.waitTicks(1);
        }
    }

    private static Class<?> classOf(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean isCleared(Room r) {
        Boolean c = null;
        try {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) ModUnderTest.staticCall(ROOM_STATUS, "rooms");
            for (Object o : list) {
                if (r.name().equals(ModUnderTest.call(o, "name", new Class<?>[]{}, new Object[]{}))) {
                    c = (Boolean) ModUnderTest.call(o, "cleared", new Class<?>[]{}, new Object[]{});
                }
            }
        } catch (RuntimeException e) {
            return false;
        }
        return Boolean.TRUE.equals(c);
    }

    /** Every room RoomStatus lists, reachability from where he stands, and whether Auto Clear calls it a mob room. */
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
            boolean mob = (Boolean) ModUnderTest.staticCall(AUTO_CLEAR, "isMobRoom",
                    new Class<?>[]{layout.getClass(), int.class}, new Object[]{layout, id});
            out.add(new Room(id, (String) ModUnderTest.call(r, "name", new Class<?>[]{}, new Object[]{}),
                    (String) ModUnderTest.call(r, "type", new Class<?>[]{}, new Object[]{}),
                    (Integer) ModUnderTest.call(r, "unfound", new Class<?>[]{}, new Object[]{}),
                    (Integer) ModUnderTest.call(r, "mainTile", new Class<?>[]{}, new Object[]{}), reach, mob));
        }
        return out;
    }

    /** A standable block of the room's main tile (the mod's own TeleportUtils.etherwarpableInTile). */
    private static BlockPos standIn(ClientGameTestContext ctx, Room r) {
        return ctx.computeOnClient(mc -> {
            BlockPos centre = (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class},
                    new Object[]{r.mainTile()});
            return (BlockPos) ModUnderTest.staticCall(TELEPORT_UTILS, "etherwarpableInTile",
                    new Class<?>[]{BlockPos.class, Vec3.class}, new Object[]{centre, Vec3.atCenterOf(centre)});
        });
    }

    /**
     * A standable block of the trap room whose feet block is PAST its start line - room-relative z >= 0, the test
     * {@code AutoClearUtils.canPath} applies - nearest the main tile's centre.
     */
    private static BlockPos pastTrapLine(ClientGameTestContext ctx, Room trap) {
        return ctx.computeOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            int[] cr = (int[]) ModUnderTest.call(layout, "clayRotation", new Class<?>[]{int.class}, new Object[]{trap.id()});
            int[] tiles = (int[]) ModUnderTest.call(layout, "tiles", new Class<?>[]{int.class}, new Object[]{trap.id()});
            if (cr == null || tiles == null) {
                return null;
            }
            BlockPos best = null;
            double bestD = Double.MAX_VALUE;
            for (int t : tiles) {
                BlockPos c = (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class},
                        new Object[]{t});
                for (int dx = -13; dx <= 13; dx++) {
                    for (int dz = -13; dz <= 13; dz++) {
                        for (int dy = -8; dy <= 8; dy++) {
                            BlockPos p = c.offset(dx, dy, dz);
                            if (!(Boolean) ModUnderTest.staticCall(TELEPORT_UTILS, "etherwarpable",
                                    new Class<?>[]{BlockPos.class}, new Object[]{p})) {
                                continue;
                            }
                            Object rel = ModUnderTest.staticCall(ROOM_DB, "toRelativeCoord",
                                    new Class<?>[]{BlockPos.class, int.class, int.class, int.class},
                                    new Object[]{p.above(), cr[0], cr[1], cr[2]});
                            int z;
                            try {
                                z = rel.getClass().getField("z").getInt(rel);
                            } catch (ReflectiveOperationException e) {
                                throw new RuntimeException(e);
                            }
                            double d = p.distSqr(c);
                            if (z >= 2 && d < bestD) {
                                bestD = d;
                                best = p;
                            }
                        }
                    }
                }
            }
            return best;
        });
    }

    /** A one-node ROTATE start route standing on {@code stand} (102's shape). */
    private static JsonObject route(ClientGameTestContext ctx, Room r, BlockPos stand) {
        return ctx.computeOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            int[] cr = (int[]) ModUnderTest.call(layout, "clayRotation", new Class<?>[]{int.class}, new Object[]{r.id()});
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

    /** The live map's tile for a door cell (102's): the sim publishes no wither doors. Client thread. */
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

    private static void setBlock(ClientGameTestContext ctx, BlockPos pos, BlockState state) {
        AtomicReference<Boolean> done = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                server.overworld().setBlockAndUpdate(pos, state);
                done.set(true);
            });
        });
        ctx.waitFor(mc -> done.get() != null, 200);
        for (int i = 0; i < 100 && !ctx.computeOnClient(mc -> mc.level.getBlockState(pos).is(state.getBlock())); i++) {
            ctx.waitTicks(1);
        }
        ctx.waitTicks(2);
    }

    /** A server teleport onto {@code feet} (the probe only - never during a run). */
    private static void tpTo(ClientGameTestContext ctx, BlockPos feet) {
        AtomicReference<Boolean> done = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(uuid);
                if (sp != null) {
                    sp.teleportTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
                }
                done.set(true);
            });
        });
        ctx.waitFor(mc -> done.get() != null, 200);
        ctx.waitTicks(20);
    }

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

    private static void println(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}
