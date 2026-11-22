package dev.testkit.gametest;

import dev.testkit.compat.McCompat;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 1121-sim-trap-routes (newtrap-1121): trap rooms entered and played with Auto Trap's bundled Just Cleared routes, New Trap
 * and Old Trap, each at all four rotations.
 *
 * <p>killer560 (2026-11-21): Auto Routes "sometimes etherwarped to the wrong spot" entering New Trap with a clear line. His
 * log: the start-node warp fell back to the room-by-room planner (the floor graph only landed NEAR the start block), whose
 * door leg took any landing inside the next room - inside the trap - and the next hop then had to go from inside it:
 * "You cannot use abilities in this room!" and the trip stopped. Per trap and rotation:
 * <ol>
 *   <li>(a) the room-by-room planner itself, from five spots in the room north of the trap to the bundled route's start
 *       block. A node is a FIRING spot (where he stands for the next warp, with its aim), so no node may lie in the trap
 *       room; the last warp is cast from the last node and must land on the start block, or, when the planner says the
 *       block itself cannot be reached, outside the trap (FAILS on the origin jar);</li>
 *   <li>(b) Auto Trap: no Full Trap route out of the box, Just Cleared selected and bundled;</li>
 *   <li>(c) the real start-node warp (the Interactive Map's, Auto Trap on) from that room: the warps go out, he lands
 *       on the start node, the sim refuses no ability, and the bundled route starts from it (node #1 fires). How far the
 *       route then gets is logged only: trap rooms are not playable in the sim yet (no secrets, no trap capture).</li>
 * </ol>
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimTrapRoutes1121Tests implements FabricClientGameTest {

    private static final String NAME = "1121-sim-trap-routes";
    private static final String R = "com.killer560.hub.roomsim.";
    private static final String SIM_STATE = R + "SimState";
    private static final String ROOM_LIBRARY = R + "RoomLibrary";
    private static final String FLOOR_GEN = R + "SimFloorGen";
    private static final String SIM_ITEMS = R + "SimItems";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String DB = "com.killer560.hub.roomdatabase.RoomDatabase";
    private static final String LIVE_MAP_CONFIG = "com.killer560.hub.livemap.LiveMapConfig";
    private static final String EXECUTOR = "com.killer560.hub.livemap.autoclear.ClearExecutor";
    private static final String PATHFINDER = "com.killer560.hub.livemap.autoclear.EtherwarpPathfinder";
    private static final String TELEPORT = "com.killer560.hub.livemap.autoclear.TeleportUtils";
    /** The planner's sneaking eye height (EtherSearch.SNEAK_EYE), used to cast the last node's warp. */
    private static final double SNEAK_EYE = 1.27;
    private static final String AUTO_CONFIG = "com.killer560.hub.autopuzzles.AutoPuzzlesConfig";
    private static final String AR_CONFIG = "com.killer560.hub.autoroutes.AutoRoutesConfig";
    private static final String AR_FEATURE = "com.killer560.hub.autoroutes.AutoRoutesFeature";
    private static final String ROUTE_EXEC = "com.killer560.hub.autoroutes.RouteExecutor";
    private static final String ROUTE_COORDS = "com.killer560.hub.autoroutes.RouteCoords";
    private static final String AUTO_TRAP = "com.killer560.hub.autotrap.AutoTrap";
    private static final int GRID = 11;
    private static final int ROOM_GRID = 6;
    private static final String REFUSAL = "You cannot use abilities in this room!";
    private static final String SIM_REFUSAL = "No abilities in a trap room";
    /** Neighbors around the trap on tile (1,2); the first usable one north of it is where he stands. */
    /** 1x1 rooms only: a 2x2 (Flags, Altar) at the north tile covers the trap's and the Entrance's tiles and drops them. */
    private static final String[] NORTH = {"Temple", "Cages", "Arrow Trap"};
    private static int TRAP_X;
    private static int TRAP_Z;
    private static int NX;
    private static int NZ;
    private static int MAP_ROT;
    private static final int[][] SPOTS ={{0, 0}, {-9, -9}, {9, -9}, {-9, 9}, {9, 9}};

    private static final List<String> SUMMARY = new ArrayList<>();

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        if (Scenario.legitSkip(NAME)) {
            return;
        }
        LogTap.install();
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (SimMapTests.copyRoomsForOthers() < 20) {
            Scenario.skipped(NAME, "needs real room captures and room database");
            return;
        }
        ctx.runOnClient(mc -> Scenario.loadRoomDatabaseNow());
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(DB, "isReady"), 1000);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"));
        Object mapCfg = ctx.computeOnClient(mc -> ModUnderTest.config(LIVE_MAP_CONFIG));
        ctx.runOnClient(mc -> {
            ModUnderTest.set(ModUnderTest.config(AUTO_CONFIG), "setAutoPuzzlesMasterEnabled", false);
            ModUnderTest.set(mapCfg, "setEnabled", true);
            ModUnderTest.set(mapCfg, "setInteractiveMapEnabled", true);
            ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setEnabled", true);
            for (String trap : new String[]{"New Trap", "Old Trap"}) {
                for (String m : new String[]{"FULL", "CLEARED"}) {
                    ModUnderTest.staticCall(AUTO_TRAP, "clear", new Class<?>[]{String.class, modeClass()},
                            new Object[]{trap, ModUnderTest.enumValue(AUTO_TRAP + "$Mode", m)});
                }
            }
            ModUnderTest.staticCall(AUTO_TRAP, "setEnabled", new Class<?>[]{boolean.class}, new Object[]{true});
        });
        int renderBefore = ctx.computeOnClient(mc -> mc.options.renderDistance().get());
        ctx.runOnClient(mc -> mc.options.renderDistance().set(16));

        List<String> failures = new ArrayList<>();
        try {
            for (String trap : new String[]{"New Trap", "Old Trap"}) {
                for (int rot = 0; rot < 360; rot += 90) {
                    oneFloor(ctx, failures, trap, rot);
                }
            }
        } catch (Throwable t) {
            failures.add("scenario threw: " + t);
            t.printStackTrace(System.out);
        } finally {
            ctx.runOnClient(mc -> {
                McCompat.setScreen(mc, null);
                mc.options.renderDistance().set(renderBefore);
                ModUnderTest.staticCall(AUTO_TRAP, "setEnabled", new Class<?>[]{boolean.class}, new Object[]{false});
            });
            leave(ctx);
            ctx.runOnClient(mc -> mc.execute(() ->
                    McCompat.setScreen(mc, new net.minecraft.client.gui.screens.TitleScreen())));
            ctx.waitFor(mc -> McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.TitleScreen);
        }
        for (String s : SUMMARY) {
            println("SUMMARY " + s);
        }
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " problem(s): " + String.join("; ", failures));
        }
        System.out.println("[" + NAME + "] PASS");
    }

    private static void leave(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "leave"));
        ctx.runOnClient(mc -> mc.execute(() -> {
            if (mc.level != null) {
                mc.level.disconnect(net.minecraft.network.chat.Component.literal("scenario over"));
                mc.disconnectWithSavingScreen();
            }
        }));
        ctx.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null, 2400);
        ctx.waitTicks(40);
    }

    private static int key(int gx, int gz) {
        return gz * ROOM_GRID + gx;
    }

    private static void oneFloor(ClientGameTestContext ctx, List<String> failures, String trap, int rot) {
        String tag = trap + " r" + rot;
        Map<String, ?> usable = ctx.computeOnClient(mc -> (Map<String, ?>) ModUnderTest.staticCall(FLOOR_GEN, "usableRooms"));
        if (!usable.containsKey(trap)) {
            failures.add(tag + ": no capture of " + trap + " to build");
            return;
        }
        String north = null;
        for (String n : NORTH) {
            if (north == null && usable.containsKey(n)) {
                north = n;
            }
        }
        if (north == null) {
            failures.add(tag + ": no 1x1 neighbor room captured");
            return;
        }
        // A trap has ONE door, and on Hypixel its rotation follows that door, so his route's start (by the door) tells the
        // side: the map reads paste + capture rotation; turn his start's offset from the room center by it and put the
        // neighbor on that side, the Entrance one further out. Trap at (2,2), nothing else touches it.
        int mapRot = ctx.computeOnClient(mc -> (rot + (Integer) ModUnderTest.staticCall(R + "RoomCaptureRotation", "of",
                new Class<?>[]{roomClass()}, new Object[]{ModUnderTest.staticCall(R + "RoomLibrary", "get",
                        new Class<?>[]{String.class}, new Object[]{trap})})) % 360);
        double ox = (trap.equals("New Trap") ? 16.5 : 12.5) - 15.5;
        double oz = (trap.equals("New Trap") ? 28.5 : 4.5) - 15.5;
        double rx = mapRot == 0 ? ox : mapRot == 90 ? -oz : mapRot == 180 ? -ox : oz;
        double rz = mapRot == 0 ? oz : mapRot == 90 ? ox : mapRot == 180 ? -oz : -ox;
        int sx = Math.abs(rx) > Math.abs(rz) ? (int) Math.signum(rx) : 0;
        int sz = sx == 0 ? (int) Math.signum(rz) : 0;
        TRAP_X = 2;
        TRAP_Z = 2;
        NX = 2 + sx;
        NZ = 2 + sz;
        MAP_ROT = mapRot;
        Map<Integer, String> floor = new LinkedHashMap<>();
        floor.put(key(TRAP_X, TRAP_Z), trap);
        floor.put(key(NX, NZ), north);
        floor.put(key(2 + 2 * sx, 2 + 2 * sz), "Entrance");
        println(tag + ": map rotation " + mapRot + ", door side " + sx + "," + sz + ", " + north + " at " + NX + "," + NZ);
        // Explicit floors paste every room at rotation 0: turn the trap's cells to this case's rotation.
        String code = ctx.computeOnClient(mc -> {
            Object planned = ModUnderTest.staticCall(FLOOR_GEN, "planExplicit", new Class<?>[]{Map.class, boolean.class},
                    new Object[]{floor, true});
            Object d = ModUnderTest.call(planned, "decoded", new Class<?>[]{}, new Object[]{});
            String[] names = (String[]) ModUnderTest.call(d, "nameTable", new Class<?>[]{}, new Object[]{});
            if (names.length != floor.size()) {
                return "PLANNED " + String.join(", ", names);
            }
            int[] cellRoom = (int[]) ModUnderTest.call(d, "cellRoom", new Class<?>[]{}, new Object[]{});
            int[] cellRot = (int[]) ModUnderTest.call(d, "cellRotation", new Class<?>[]{}, new Object[]{});
            for (int i = 0; i < cellRoom.length; i++) {
                if (cellRoom[i] >= 0 && cellRoom[i] < names.length && trap.equals(names[cellRoom[i]])) {
                    cellRot[i] = rot;
                }
            }
            return (String) ModUnderTest.staticCall(R + "MapCode", "encodeDecoded", new Class<?>[]{d.getClass()},
                    new Object[]{d});
        });
        if (code.startsWith("PLANNED ")) {
            failures.add(tag + ": the floor dropped rooms, placed only " + code.substring(8) + " of " + floor.values());
            return;
        }
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> {
            Consumer<Minecraft> build = c -> ModUnderTest.staticCall(R + "SimBuilder", "build",
                    new Class<?>[]{Minecraft.class, String.class}, new Object[]{c, code});
            ModUnderTest.staticCall(R + "SimWorld", "open", new Class<?>[]{Minecraft.class, String.class, Consumer.class,
                    String.class}, new Object[]{mc, code, build, "Building " + tag});
        }));
        ctx.waitFor(mc -> mc.level != null, 2400);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitTicks(60);
        SimAutoClearTests.awaitFloorChunks(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(R + "SimRun", "begin",
                new Class<?>[]{Minecraft.class, BlockPos.class},
                new Object[]{mc, ModUnderTest.staticCall(R + "SimBuilder", "entranceDoor")}));
        for (int i = 0; i < 400 && !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(R + "SimRun", "isRunning")); i++) {
            ctx.waitTicks(1);
        }
        giveItems(ctx);
        try {
            body(ctx, failures, trap, rot, tag, north);
        } finally {
            leave(ctx);
        }
    }

    private static void body(ClientGameTestContext ctx, List<String> failures, String trap, int rot, String tag,
                             String north) {
        // ---- where the bundled route starts in this pasted trap ----------------------------------------------------------
        String placed = standOnTile(ctx, NX, NZ, 0, 0);
        for (int i = 0; i < 300 && ctx.computeOnClient(mc -> roomNamed(ModUnderTest.staticCall(LAYOUT, "capture"), trap)) < 0; i++) {
            ctx.waitTicks(1);
        }
        Object[] trapInfo = ctx.computeOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            int room = roomNamed(layout, trap);
            if (room < 0) {
                return null;
            }
            int[] cr = (int[]) ModUnderTest.call(layout, "clayRotation", new Class<?>[]{int.class}, new Object[]{room});
            Object route = ModUnderTest.staticCall(AUTO_TRAP, "routeFor", new Class<?>[]{String.class}, new Object[]{trap});
            if (cr == null) {
                return new Object[]{room, null, route, null, null};
            }
            Object frame = newFrame(trap, cr);
            // a jar with no bundled route (origin before newtrap-1121) still gets (a) from his route's start spot
            Vec3 rel = route == null ? (trap.equals("New Trap") ? new Vec3(16.5, 69.0, 28.5) : new Vec3(12.5, 69.0, 4.5))
                    : (Vec3) ModUnderTest.call(ModUnderTest.call(route, "startNode", new Class<?>[]{}, new Object[]{}),
                    "relativePos", new Class<?>[]{}, new Object[]{});
            Vec3 real = (Vec3) ModUnderTest.staticCall(ROUTE_COORDS, "toReal", new Class<?>[]{frame.getClass(), Vec3.class},
                    new Object[]{frame, rel});
            if (route == null) {
                return new Object[]{room, cr, null, real, null};
            }
            List<?> nodes = (List<?>) ModUnderTest.call(route, "nodes", new Class<?>[]{}, new Object[]{});
            Object last = nodes.get(nodes.size() - 1);
            Vec3 lastReal = (Vec3) ModUnderTest.staticCall(ROUTE_COORDS, "toReal",
                    new Class<?>[]{frame.getClass(), Vec3.class}, new Object[]{frame,
                            ModUnderTest.call(last, "relativePos", new Class<?>[]{}, new Object[]{})});
            return new Object[]{room, cr, route, real, new Object[]{nodes.size(), lastReal}};
        });
        if (trapInfo == null) {
            failures.add(tag + ": the live map does not know " + trap + " (" + placed + "; rooms " + ctx.computeOnClient(mc -> {
                Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
                int n = layout == null ? 0 : (Integer) ModUnderTest.call(layout, "roomCount", new Class<?>[]{}, new Object[]{});
                List<Object> names = new java.util.ArrayList<>();
                for (int r = 0; r < n; r++) {
                    names.add(ModUnderTest.call(layout, "name", new Class<?>[]{int.class}, new Object[]{r}));
                }
                return names.toString();
            }) + ")");
            return;
        }
        int[] cr = (int[]) trapInfo[1];
        if (cr == null) {
            failures.add(tag + ": no clay/rotation for the trap");
            return;
        }
        boolean hasRoute = trapInfo[2] != null;
        if (!hasRoute) {
            failures.add(tag + ": Auto Trap has no route for it (no bundled Just Cleared route)");
        }
        if (cr[2] != MAP_ROT) {
            failures.add(tag + ": the map reads the trap at rotation " + cr[2] + ", expected " + MAP_ROT);
        }
        Vec3 startReal = (Vec3) trapInfo[3];
        int nodeCount = hasRoute ? (Integer) ((Object[]) trapInfo[4])[0] : 0;
        Vec3 lastReal = hasRoute ? (Vec3) ((Object[]) trapInfo[4])[1] : null;
        BlockPos goal = BlockPos.containing(startReal.x, startReal.y - 0.5, startReal.z);
        int trapRoom = (Integer) trapInfo[0];

        // ---- (b) Just Cleared only, out of the box -----------------------------------------------------------------------
        String modes = ctx.computeOnClient(mc -> {
            try {
                return ModUnderTest.staticCall(AUTO_TRAP, "selectedMode", new Class<?>[]{String.class}, new Object[]{trap})
                        + " full=" + ModUnderTest.staticCall(AUTO_TRAP, "hasRoute", new Class<?>[]{String.class, modeClass()},
                        new Object[]{trap, ModUnderTest.enumValue(AUTO_TRAP + "$Mode", "FULL")});
            } catch (Throwable t) {
                return "unreadable (" + t + ")";
            }
        });
        if (!modes.equals("CLEARED full=false")) {
            failures.add(tag + ": Auto Trap out of the box should be Just Cleared with no Full Trap route, is " + modes);
        }

        // ---- (a) the room-by-room planner never stops inside the trap before its last landing -----------------------------
        int planned = 0;
        for (int[] s : SPOTS) {
            String at = standOnTile(ctx, NX, NZ, s[0], s[1]);
            String here = liveRoomName(ctx);
            if (!north.equals(here)) {
                continue;
            }
            long planMark = LogTap.mark();
            String verdict = ctx.computeOnClient(mc -> legacyVerdict(mc, goal, trapRoom));
            if (verdict.startsWith("NEAR") && grep(planMark, "itself cannot be reached").isEmpty()) {
                verdict = "BAD the last warp misses the start block with no word that it cannot be reached: " + verdict;
            }
            println(tag + " room by room from " + at + ": " + verdict);
            SUMMARY.add(tag + " legacy " + at + ": " + verdict);
            if (verdict.startsWith("BAD")) {
                failures.add(tag + ": room by room from " + at + " " + verdict);
            }
            if (verdict.startsWith("OK") || verdict.startsWith("NEAR")) {
                planned++;
            }
        }
        if (planned == 0) {
            failures.add(tag + ": the room-by-room planner planned no trip into the trap from any spot - (a) never acted");
        }

        // ---- (c) the real start-node warp, then the bundled route plays ---------------------------------------------------
        if (!hasRoute) {
            return;
        }
        standOnTile(ctx, NX, NZ, 0, 0);
        int completionsBefore = ctx.computeOnClient(mc -> ((Number) ModUnderTest.staticCall(ROUTE_EXEC, "completions")).intValue());
        long mark = LogTap.mark();
        boolean warping = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(AR_FEATURE, "warpToStartNode",
                new Class<?>[]{layoutClass(), int.class},
                new Object[]{ModUnderTest.staticCall(LAYOUT, "capture"), trapRoom}));
        if (!warping) {
            failures.add(tag + ": warpToStartNode refused (" + String.join(" | ", grep(mark, "Start-node warp")) + ")");
            return;
        }
        boolean landed = false;
        boolean done = false;
        String lastNodeTag = "#" + nodeCount + " ";
        int beganAt = -1;
        boolean newTrap = trap.equals("New Trap");
        int alignsBefore = walkAligns(ctx);
        double minY = Double.MAX_VALUE;
        for (int t = 0; t < 1200 && !done; t++) {
            ctx.waitTicks(1);
            minY = Math.min(minY, ctx.computeOnClient(mc -> mc.player.getY()));
            List<String> lines = LogTap.since(mark);
            for (String l : lines) {
                landed |= l.contains("Start-node warp landed");
                if (l.contains(REFUSAL) || l.contains(SIM_REFUSAL)) {
                    done = true;
                }
                if (beganAt < 0 && l.contains("Node #1 ") && l.contains(" begins: player (")) {
                    beganAt = t;
                }
            }
            // the route started: New Trap is judged on its whole run (newtrap-route7), Old Trap's 10 s are logged only
            done |= beganAt >= 0 && t > beganAt + (newTrap ? 600 : 200);
            int c = ctx.computeOnClient(mc -> ((Number) ModUnderTest.staticCall(ROUTE_EXEC, "completions")).intValue());
            boolean left = lines.stream().anyMatch(l -> l.contains("[AutoTrap] left " + trap));
            done |= c > completionsBefore || (left && lines.stream().anyMatch(l -> l.contains("Node " + lastNodeTag)
                    || l.contains("Node " + lastNodeTag.trim() + " ")));
        }
        ctx.waitTicks(10);
        List<String> lines = LogTap.since(mark);
        boolean refused = lines.stream().anyMatch(l -> l.contains(REFUSAL) || l.contains(SIM_REFUSAL));
        int completed = ctx.computeOnClient(mc -> ((Number) ModUnderTest.staticCall(ROUTE_EXEC, "completions")).intValue())
                - completionsBefore;
        boolean leftTrap = lines.stream().anyMatch(l -> l.contains("[AutoTrap] left " + trap));
        boolean lastBegan = lines.stream().anyMatch(l -> l.contains("Node " + lastNodeTag));
        Vec3 end = ctx.computeOnClient(mc -> mc.player.position());
        String plan = String.join(" | ", grep(mark, "[Path] room by room", "only lands near", "[Path] running ",
                "not warping from inside"));
        String res = String.format("start-node warp landed %s, ability refused %s, route completed %d, left trap %s,"
                        + " last node #%d began %s, ends %.1f from the last node; plan: %s", landed, refused, completed,
                leftTrap, nodeCount, lastBegan, end.distanceTo(lastReal), plan);
        println(tag + " " + res);
        SUMMARY.add(tag + " run: " + res);
        if (newTrap && lines.stream().anyMatch(l -> l.contains("Node #1 ") && l.contains(" begins: player ("))) {
            // newtrap-route7 (2026-11-22): WALK #5 ended 0.68 off breaker #6's spot and the route stopped at #7. The whole
            // route must now act: #6's block under its spot is broken, he drops through it, and the last node fires.
            Vec3 holeRel = new Vec3(27.5, 89.0, 14.5);
            Object[] hole = ctx.computeOnClient(mc -> {
                Object frame = newFrame(trap, cr);
                Vec3 real = (Vec3) ModUnderTest.staticCall(ROUTE_COORDS, "toReal",
                        new Class<?>[]{frame.getClass(), Vec3.class}, new Object[]{frame, holeRel});
                BlockPos p = BlockPos.containing(real.x, real.y, real.z);
                return new Object[]{p, mc.level.getBlockState(p).isAir(), real.y};
            });
            int aligns = walkAligns(ctx) - alignsBefore;
            double dropTo = (Double) hole[2] - minY;
            String run = String.format("node #6 block %s broken %s, lowest %.1f (%.1f under #6), last node began %s,"
                    + " walk aligns %d", hole[0], hole[1], minY, dropTo, lastBegan, aligns);
            println(tag + " route run: " + run);
            SUMMARY.add(tag + " route: " + run);
            if (!(Boolean) hole[1]) {
                failures.add(tag + ": breaker #6 did not break the block under its spot (" + run + ")");
            }
            if (dropTo < 10) {
                failures.add(tag + ": he never dropped through #6's hole (" + run + ")");
            }
            if (!lastBegan) {
                failures.add(tag + ": the route never reached its last node #" + nodeCount + " (" + run + ")");
            }
            if (aligns < 1) {
                failures.add(tag + ": no trap-room walk aligned onto its next node (" + run + ")");
            }
        }
        boolean sent = lines.stream().anyMatch(l -> l.contains("[Path] running "));
        // Where the warps put him: the first node's "begins" line gives his feet and the node's spot on the tick it fired.
        String began = lines.stream().filter(l -> l.contains("Node #1 ") && l.contains(" begins: player ("))
                .findFirst().orElse(null);
        double off = began == null ? -1 : beganOffset(began);
        boolean honestNear = !lines.stream().filter(l -> l.contains("itself cannot be reached")).toList().isEmpty();
        int endRoom = ctx.computeOnClient(mc -> (Integer) ModUnderTest.call(ModUnderTest.staticCall(LAYOUT, "capture"),
                "roomAtWorld", new Class<?>[]{double.class, double.class}, new Object[]{mc.player.getX(), mc.player.getZ()}));
        println(tag + " warps sent " + sent + ", node #1 began " + (began != null) + " " + String.format("%.2f", off)
                + " from its spot, cannot-be-reached said " + honestNear + ", ends in room " + endRoom + " (trap " + trapRoom + ")");
        if (!sent) {
            failures.add(tag + ": the Interactive Map sent no warp toward the start node");
        }
        if (refused) {
            failures.add(tag + ": an ability was refused in the trap room (" + REFUSAL + ")");
        }
        if (landed && began != null && off >= 0 && off <= 0.3) {
            return;   // on the start block, and the bundled route started from it
        }
        if (honestNear && !refused && endRoom != trapRoom) {
            println(tag + " the start block cannot be reached: he was told so and stands outside the trap");
            return;
        }
        failures.add(tag + ": the start-node warp did not put him on the start node (landed " + landed + ", node #1 began "
                + (began != null) + ", " + String.format("%.2f", off) + " off) and no honest near landing outside the trap");
    }

    /**
     * Whether a player standing at {@code x, z} is in the trap to the server: the server's room edge lies half a block past
     * the map's (the sim refuses a warp from the doorway block the map gives to the next room).
     */
    private static boolean serverTrap(Object layout, double x, double z, int trapRoom) {
        double[][] probes = {{0, 0}, {0.5, 0}, {-0.5, 0}, {0, 0.5}, {0, -0.5}};
        for (double[] d : probes) {
            int room = (Integer) ModUnderTest.call(layout, "roomAtWorld", new Class<?>[]{double.class, double.class},
                    new Object[]{x + d[0], z + d[1]});
            if (room == trapRoom) {
                return true;
            }
        }
        return false;
    }

    /** Horizontal distance between "player (x, y, z)" and "node (x, y, z)" in a node's begins line, -1 when unreadable. */
    private static double beganOffset(String line) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "player \\((-?[\\d.]+), (-?[\\d.]+), (-?[\\d.]+)\\) node \\((-?[\\d.]+), (-?[\\d.]+), (-?[\\d.]+)\\)").matcher(line);
        if (!m.find()) {
            return -1;
        }
        double dx = Double.parseDouble(m.group(1)) - Double.parseDouble(m.group(4));
        double dz = Double.parseDouble(m.group(3)) - Double.parseDouble(m.group(6));
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * The room-by-room planner's trip from where he stands to {@code goal}: "OK n warps", "BAD ..." when a landing other
     * than the last lies in the trap room or the trip does not end on the goal, or "NONE" when it planned nothing.
     */
    private static String legacyVerdict(Minecraft mc, BlockPos goal, int trapRoom) {
        try {
            Class<?> pf = Class.forName(PATHFINDER);
            Class<?> cfgCls = Class.forName(PATHFINDER + "$PathConfig");
            Method m = pf.getDeclaredMethod("legacyDungeonPath", Vec3.class, BlockPos.class, cfgCls, double.class,
                    layoutClass());
            m.setAccessible(true);
            Object cfg = ModUnderTest.staticCall(EXECUTOR, "pathConfig");
            Method hop = Class.forName(EXECUTOR).getDeclaredMethod("hopRange");
            hop.setAccessible(true);
            double range = ((Number) hop.invoke(null)).doubleValue();
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            List<?> nodes = (List<?>) m.invoke(null, mc.player.position(), goal, cfg, range, layout);
            if (nodes == null) {
                return "NONE";
            }
            // Each node is where he stands to fire the next warp (smooth() leaves the final landing out), so a node in
            // the trap room is a warp Hypixel refuses. The final landing is the last node's warp, cast here.
            StringBuilder sb = new StringBuilder();
            String bad = null;
            Object last = null;
            for (int i = 0; i < nodes.size(); i++) {
                Object n = nodes.get(i);
                last = n;
                double x = n.getClass().getField("x").getDouble(n);
                double y = n.getClass().getField("y").getDouble(n);
                double z = n.getClass().getField("z").getDouble(n);
                boolean inTrap = serverTrap(layout, x, z, trapRoom);
                sb.append(String.format(" (%.1f,%.1f,%.1f)%s", x, y, z, inTrap ? "T" : ""));
                if (inTrap && bad == null) {
                    bad = "warp " + (i + 1) + " of " + nodes.size() + " fires from inside the trap";
                }
            }
            if (last == null) {
                return "NONE";
            }
            double lx = last.getClass().getField("x").getDouble(last);
            double ly = last.getClass().getField("y").getDouble(last);
            double lz = last.getClass().getField("z").getDouble(last);
            float yaw = last.getClass().getField("yaw").getFloat(last);
            float pitch = last.getClass().getField("pitch").getFloat(last);
            Object ray = ModUnderTest.staticCall(TELEPORT, "getEtherPos",
                    new Class<?>[]{Vec3.class, float.class, float.class, double.class},
                    new Object[]{new Vec3(lx, ly + SNEAK_EYE, lz), yaw, pitch, range});
            boolean hit = (Boolean) ModUnderTest.call(ray, "succeeded", new Class<?>[0], new Object[0]);
            BlockPos land = hit ? (BlockPos) ModUnderTest.call(ray, "pos", new Class<?>[0], new Object[0]) : null;
            sb.append(" -> ").append(land == null ? "no landing" : land.toShortString());
            if (bad != null) {
                return "BAD " + bad + ": " + nodes.size() + " warp(s)" + sb;
            }
            if (land != null && land.equals(goal)) {
                return "OK " + nodes.size() + " warp(s)" + sb;
            }
            if (land == null || serverTrap(layout, land.getX() + 0.5, land.getZ() + 0.5, trapRoom)
                    || land.distManhattan(goal) > 12) {
                return "BAD the last warp lands on " + (land == null ? "nothing" : land.toShortString())
                        + ", not the start block " + goal.toShortString() + ": " + nodes.size() + " warp(s)" + sb;
            }
            return "NEAR outside the trap, " + land.toShortString() + " for " + goal.toShortString() + ": "
                    + nodes.size() + " warp(s)" + sb;
        } catch (ReflectiveOperationException e) {
            return "BAD reflection " + e;
        }
    }

    private static List<String> grep(long mark, String... needles) {
        List<String> out = new ArrayList<>();
        for (String l : LogTap.since(mark)) {
            for (String n : needles) {
                if (l.contains(n)) {
                    out.add(l.length() > 220 ? l.substring(0, 220) : l);
                    break;
                }
            }
        }
        return out;
    }

    private static int roomNamed(Object layout, String name) {
        if (layout == null) {
            return -1;
        }
        int count = (Integer) ModUnderTest.call(layout, "roomCount", new Class<?>[]{}, new Object[]{});
        for (int r = 0; r < count; r++) {
            Object n = ModUnderTest.call(layout, "name", new Class<?>[]{int.class}, new Object[]{r});
            if (name.equals(n)) {
                return r;
            }
        }
        return -1;
    }

    /** RouteExecutor.walkAligns (newtrap-route7), -1 on a jar without it. */
    private static int walkAligns(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            try {
                return ((Number) ModUnderTest.staticCall(ROUTE_EXEC, "walkAligns")).intValue();
            } catch (Throwable t) {
                return -1;
            }
        });
    }

    private static Object newFrame(String name, int[] cr) {
        try {
            Class<?> f = Class.forName(ROUTE_COORDS + "$Frame");
            return f.getConstructor(String.class, int.class, int.class, int.class).newInstance(name, cr[0], cr[1], cr[2]);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("no RouteCoords.Frame(String, int, int, int)", e);
        }
    }

    private static void giveItems(ClientGameTestContext ctx) {
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
                String[] ids = {"ASPECT_OF_THE_VOID", "DUNGEONBREAKER", "SUPERBOOM_TNT", "ENDER_PEARL"};
                for (int i = 0; i < ids.length; i++) {
                    inv.setItem(i, (ItemStack) ModUnderTest.staticCall(SIM_ITEMS, "build", new Class<?>[]{String.class},
                            new Object[]{ids[i]}));
                }
                given.set(true);
            });
        });
        ctx.waitFor(mc -> given.get() != null, 200);
        ctx.waitTicks(10);
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
        ctx.waitTicks(10);
    }

    private static String liveRoomName(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            int r = (Integer) ModUnderTest.call(layout, "roomAtWorld", new Class<?>[]{double.class, double.class},
                    new Object[]{mc.player.getX(), mc.player.getZ()});
            return r < 0 ? null : (String) ModUnderTest.call(layout, "name", new Class<?>[]{int.class}, new Object[]{r});
        });
    }

    /** As 99-sim-arrowtrap: stands him in column (dx, dz) from tile (gx, gz)'s center, found on the server thread. */
    private static String standOnTile(ClientGameTestContext ctx, int gx, int gz, int dx, int dz) {
        AtomicReference<String> done = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            BlockPos c = (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class},
                    new Object[]{2 * gz * GRID + 2 * gx});
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(uuid);
                if (sp == null) {
                    done.set("no server player");
                    return;
                }
                var level = sp.level();
                int x = c.getX() + dx;
                int z = c.getZ() + dz;
                for (int y = c.getY() - 8; y < c.getY() + 14; y++) {
                    BlockPos b = new BlockPos(x, y, z);
                    var st = level.getBlockState(b);
                    if (!st.getCollisionShape(level, b).isEmpty() && st.getFluidState().isEmpty()
                            && level.getBlockState(b.above()).isAir() && level.getBlockState(b.above(2)).isAir()) {
                        sp.teleportTo(x + 0.5, y + 1, z + 0.5);
                        done.set(x + "," + (y + 1) + "," + z);
                        return;
                    }
                }
                done.set("no standable block at " + x + "," + z);
            });
        });
        ctx.waitFor(mc -> done.get() != null, 200);
        ctx.waitTicks(10);
        for (int i = 0; i < 60 && !ctx.computeOnClient(mc -> mc.player.onGround()); i++) {
            ctx.waitTicks(1);
        }
        return done.get();
    }

    private static Class<?> modeClass() {
        try {
            return Class.forName(AUTO_TRAP + "$Mode");
        } catch (ClassNotFoundException e) {
            throw new AssertionError("no " + AUTO_TRAP + "$Mode", e);
        }
    }

    private static Class<?> roomClass() {
        try {
            return Class.forName(R + "RoomLibrary$Room");
        } catch (ClassNotFoundException e) {
            throw new AssertionError("no RoomLibrary.Room", e);
        }
    }

    private static Class<?> layoutClass() {
        try {
            return Class.forName(LAYOUT);
        } catch (ClassNotFoundException e) {
            throw new AssertionError("no " + LAYOUT, e);
        }
    }

    private static void println(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}
