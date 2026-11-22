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
 *       block: every landing but the last must lie outside the trap room, and the plan must end on the block (FAILS on
 *       the origin jar);</li>
 *   <li>(b) Auto Trap: no Full Trap route out of the box, Just Cleared selected and bundled;</li>
 *   <li>(c) the real start-node warp (the Interactive Map's, Auto Trap on) from that room, then the bundled route plays:
 *       he lands on the start node, the sim refuses no ability, and the route runs to its last node.</li>
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
    private static final String[] NORTH = {"Flags", "Temple", "Arrow Trap", "Cages", "Altar"};
    private static final int[][] SPOTS = {{0, 0}, {-9, -9}, {9, -9}, {-9, 9}, {9, 9}};

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
        List<String> others = new ArrayList<>();
        for (String n : NORTH) {
            if (usable.containsKey(n)) {
                others.add(n);
            }
        }
        if (others.size() < 3) {
            failures.add(tag + ": fewer than 3 neighbor rooms captured (" + others + ")");
            return;
        }
        String north = others.get(0);
        Map<Integer, String> floor = new LinkedHashMap<>();
        floor.put(key(1, 2), trap);
        floor.put(key(1, 1), north);
        floor.put(key(0, 2), others.get(1));
        floor.put(key(2, 2), others.get(2));
        floor.put(key(2, 1), "Entrance");
        // Explicit floors paste every room at rotation 0: turn the trap's cells to this case's rotation.
        String code = ctx.computeOnClient(mc -> {
            Object planned = ModUnderTest.staticCall(FLOOR_GEN, "planExplicit", new Class<?>[]{Map.class, boolean.class},
                    new Object[]{floor, true});
            Object d = ModUnderTest.call(planned, "decoded", new Class<?>[]{}, new Object[]{});
            String[] names = (String[]) ModUnderTest.call(d, "nameTable", new Class<?>[]{}, new Object[]{});
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
        // the map names a room only once it has scanned it: stand in the trap a moment (as a player who has seen it), then
        // go back to the north room and wait for the name
        standOnTile(ctx, 1, 2, 0, 0);
        ctx.waitTicks(40);
        String placed = standOnTile(ctx, 1, 1, 0, 0);
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
        if (cr[2] != rot) {
            failures.add(tag + ": the map reads the trap at rotation " + cr[2] + ", the case pasted " + rot);
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
            String at = standOnTile(ctx, 1, 1, s[0], s[1]);
            String here = liveRoomName(ctx);
            if (!north.equals(here)) {
                continue;
            }
            String verdict = ctx.computeOnClient(mc -> legacyVerdict(mc, goal, trapRoom));
            println(tag + " room by room from " + at + ": " + verdict);
            SUMMARY.add(tag + " legacy " + at + ": " + verdict);
            if (verdict.startsWith("BAD")) {
                failures.add(tag + ": room by room from " + at + " " + verdict);
            }
            if (verdict.startsWith("OK")) {
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
        standOnTile(ctx, 1, 1, 0, 0);
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
        for (int t = 0; t < 900 && !done; t++) {
            ctx.waitTicks(1);
            List<String> lines = LogTap.since(mark);
            for (String l : lines) {
                landed |= l.contains("Start-node warp landed");
                if (l.contains(REFUSAL) || l.contains(SIM_REFUSAL)) {
                    done = true;
                }
            }
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
        if (!landed) {
            failures.add(tag + ": the start-node warp never landed");
        }
        if (refused) {
            failures.add(tag + ": an ability was refused in the trap room (" + REFUSAL + ")");
        }
        if (completed == 0 && !(leftTrap && lastBegan)) {
            failures.add(tag + ": the bundled route did not run to its last node (completed " + completed + ", left "
                    + leftTrap + ", last node began " + lastBegan + ")");
        }
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
            StringBuilder sb = new StringBuilder();
            String bad = null;
            for (int i = 0; i < nodes.size(); i++) {
                Object n = nodes.get(i);
                double x = n.getClass().getField("x").getDouble(n);
                double y = n.getClass().getField("y").getDouble(n);
                double z = n.getClass().getField("z").getDouble(n);
                int room = (Integer) ModUnderTest.call(layout, "roomAtWorld", new Class<?>[]{double.class, double.class},
                        new Object[]{x, z});
                sb.append(String.format(" (%.1f,%.1f,%.1f)%s", x, y, z, room == trapRoom ? "T" : ""));
                if (i < nodes.size() - 1 && room == trapRoom && bad == null) {
                    bad = "landing " + i + " of " + nodes.size() + " is inside the trap";
                }
            }
            if (bad == null && !nodes.isEmpty()) {
                Object last = nodes.get(nodes.size() - 1);
                BlockPos p = (BlockPos) last.getClass().getField("pos").get(last);
                if (p.distManhattan(goal) > 2) {
                    bad = "ends on " + p.toShortString() + ", not the start block " + goal.toShortString();
                }
            }
            return (bad == null ? "OK " : "BAD " + bad + ":") + nodes.size() + " node(s)" + sb;
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
