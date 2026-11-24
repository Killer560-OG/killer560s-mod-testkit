package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import dev.testkit.compat.McCompat;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 1123-sim-trap-editnodes (trapedit-memonly-1123): with Auto Trap on and no own route in New Trap / Old Trap, every edit of
 * the shown bundled route must first make it his own route and reach his routes file: a node-editor save
 * ({@code applyNodeEdit}), {@code /ar undo} of it, the empty-history undo (deletes the last node) and, in New Trap, a Dungeon
 * Breaker edit-mode pick with its undo. Each step is read back through {@code RouteStore.reload}, the file a restart reads.
 * Before the fix those paths changed only the in-memory bundled copy.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimTrapEditNodes1123Tests implements FabricClientGameTest {

    private static final String NAME = "1123-sim-trap-editnodes";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String STORE = "com.killer560.hub.autoroutes.RouteStore";
    private static final String EXEC = "com.killer560.hub.autoroutes.RouteExecutor";
    private static final String ROUTE_COORDS = "com.killer560.hub.autoroutes.RouteCoords";
    private static final String FEATURE = "com.killer560.hub.autoroutes.AutoRoutesFeature";
    private static final Class<?>[] NONE = {};
    private static final Object[] NOARGS = {};

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        if (Scenario.legitSkip(NAME)) {
            return;
        }
        Integer renderBefore = SimTrapRoutes1121Tests.setUp(ctx, NAME);
        if (renderBefore == null) {
            return;
        }
        List<String> failures = new ArrayList<>();
        int[] acted = {0};
        try {
            for (String trap : new String[]{"New Trap", "Old Trap"}) {
                SimTrapRoutes1121Tests.oneFloor(ctx, failures, trap, 0,
                        (c, f, t, r, tag, north) -> acted[0] += body(c, f, t, tag));
            }
        } catch (Throwable t) {
            failures.add("scenario threw: " + t);
            t.printStackTrace(System.out);
        } finally {
            ctx.runOnClient(mc -> {
                ModUnderTest.staticCall(FEATURE, "setEditMode", new Class<?>[]{boolean.class}, new Object[]{false});
                for (String room : new String[]{"New Trap", "Old Trap"}) {
                    ModUnderTest.call(store(), "remove", new Class<?>[]{String.class}, new Object[]{room});
                }
                ModUnderTest.call(store(), "save", NONE, NOARGS);
            });
            SimTrapRoutes1121Tests.tearDown(ctx, renderBefore);
        }
        if (acted[0] < 9) {
            failures.add("only " + acted[0] + " of 9 edit paths proven - the case never acted");
        }
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " problem(s): " + String.join("; ", failures));
        }
        System.out.println("[" + NAME + "] PASS");
    }

    private static Object store() {
        return ModUnderTest.staticCall(STORE, "getInstance");
    }

    /** @return how many edit paths were proven to have acted. */
    private static int body(ClientGameTestContext ctx, List<String> failures, String trap, String tag) {
        SimTrapRoutes1121Tests.standOnTile(ctx, SimTrapRoutes1121Tests.NX, SimTrapRoutes1121Tests.NZ, 0, 0);
        for (int i = 0; i < 300 && ctx.computeOnClient(mc -> SimTrapRoutes1121Tests.roomNamed(
                ModUnderTest.staticCall(LAYOUT, "capture"), trap)) < 0; i++) {
            ctx.waitTicks(1);
        }
        BlockPos start = ctx.computeOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            int room = SimTrapRoutes1121Tests.roomNamed(layout, trap);
            int[] cr = room < 0 ? null
                    : (int[]) ModUnderTest.call(layout, "clayRotation", new Class<?>[]{int.class}, new Object[]{room});
            if (cr == null) {
                return null;
            }
            Object frame = SimTrapRoutes1121Tests.newFrame(trap, cr);
            BlockPos rel = trap.equals("New Trap") ? new BlockPos(16, 69, 28) : new BlockPos(12, 69, 4);
            return (BlockPos) ModUnderTest.staticCall(ROUTE_COORDS, "toRealBlock",
                    new Class<?>[]{frame.getClass(), BlockPos.class}, new Object[]{frame, rel});
        });
        if (start == null) {
            failures.add(tag + ": no live room for " + trap);
            return 0;
        }
        int acted = 0;
        removeOwn(ctx, trap);
        tp(ctx, start.getX() + 0.5, start.getY(), start.getZ() + 0.5);
        ctx.waitTicks(30);
        stopRoute(ctx);
        int shown = count(ctx, trap, "forRoom");
        if (shown < 3 || count(ctx, trap, "ownRoute") != 0) {
            failures.add(tag + ": start state wrong, shown " + shown + ", own " + count(ctx, trap, "ownRoute"));
            return 0;
        }
        // 1. node editor save on node 2 (radius +1.5), the call the editor's Save button makes
        double r0 = ctx.computeOnClient(mc -> nodeRadius(trap, "forRoom"));
        boolean saved = ctx.computeOnClient(mc -> {
            Object route;
            try {
                route = ModUnderTest.staticCall(FEATURE, "editRouteForEditor", NONE, NOARGS);
            } catch (AssertionError noSuchMethod) { // an origin jar: the editor opens on the shown route
                route = ModUnderTest.staticCall(FEATURE, "editableRoute", NONE, NOARGS);
            }
            Object node = ((List<?>) ModUnderTest.call(route, "nodes", NONE, NOARGS)).get(1);
            Object edited = ModUnderTest.call(node, "copy", NONE, NOARGS);
            try {
                edited.getClass().getField("radius").setDouble(edited, r0 + 1.5);
            } catch (ReflectiveOperationException ex) {
                throw new AssertionError(ex);
            }
            return (Boolean) ModUnderTest.staticCall(FEATURE, "applyNodeEdit",
                    new Class<?>[]{route.getClass(), node.getClass(), node.getClass()}, new Object[]{route, node, edited});
        });
        reload(ctx);
        double r1 = ctx.computeOnClient(mc -> nodeRadius(trap, "ownRoute"));
        System.out.println("[" + NAME + "] " + tag + " editor save: applied " + saved + ", radius " + r0 + " -> " + r1
                + " (own route after reload), nodes own " + count(ctx, trap, "ownRoute") + " shown " + count(ctx, trap, "forRoom"));
        if (!saved || r1 != r0 + 1.5 || count(ctx, trap, "ownRoute") != shown) {
            failures.add(tag + ": node editor save did not reach his own saved route (radius " + r0 + " -> " + r1 + ", own nodes "
                    + count(ctx, trap, "ownRoute") + " of " + shown + ")");
        } else {
            acted++;
        }
        // 2. /ar undo of that edit
        cmd(ctx, "/ar undo");
        ctx.waitTicks(3);
        reload(ctx);
        double r2 = ctx.computeOnClient(mc -> nodeRadius(trap, "ownRoute"));
        System.out.println("[" + NAME + "] " + tag + " undo: radius " + r1 + " -> " + r2);
        if (r2 != r0) {
            failures.add(tag + ": /ar undo of the editor save left radius " + r2 + " in his saved route, wanted " + r0);
        } else {
            acted++;
        }
        // 3. the empty-history undo deletes the last node: in a room showing only the bundled route it must hit his own copy
        removeOwn(ctx, trap);
        if (count(ctx, trap, "ownRoute") != 0 || count(ctx, trap, "forRoom") != shown) {
            failures.add(tag + ": could not return to the bundled-only state before the fallback undo");
        }
        cmd(ctx, "/ar undo");
        ctx.waitTicks(3);
        reload(ctx);
        int own3 = count(ctx, trap, "ownRoute");
        int drawn3 = count(ctx, trap, "forRoom");
        System.out.println("[" + NAME + "] " + tag + " fallback undo: own " + own3 + ", drawn " + drawn3 + ", bundled " + shown);
        if (own3 != shown - 1 || drawn3 != own3) {
            failures.add(tag + ": the empty-history /ar undo left his saved route with " + own3 + " node(s) (drawn " + drawn3
                    + "), wanted " + (shown - 1));
        } else {
            acted++;
        }
        // 4. Dungeon Breaker pick and its undo (New Trap's bundled route has breaker nodes, Old Trap's has none)
        if (trap.equals("New Trap")) {
            removeOwn(ctx, trap);
            int blocks0 = ctx.computeOnClient(mc -> breakerBlocks(trap, "forRoom"));
            ctx.runOnClient(mc -> ModUnderTest.staticCall(FEATURE, "setEditMode", new Class<?>[]{boolean.class}, new Object[]{true}));
            BlockPos click = start.offset(2, -1, 0);
            boolean consumed = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(FEATURE, "onEditRightClick",
                    new Class<?>[]{BlockPos.class, boolean.class}, new Object[]{click, false}));
            reload(ctx);
            int blocks1 = ctx.computeOnClient(mc -> breakerBlocks(trap, "ownRoute"));
            System.out.println("[" + NAME + "] " + tag + " breaker pick: consumed " + consumed + ", blocks " + blocks0 + " -> "
                    + blocks1 + " (own after reload)");
            if (!consumed || blocks1 != blocks0 + 1) {
                failures.add(tag + ": the Dungeon Breaker pick did not reach his own saved route (blocks " + blocks0 + " -> " + blocks1 + ")");
            } else {
                acted++;
            }
            cmd(ctx, "/ar undo");
            ctx.waitTicks(3);
            reload(ctx);
            int blocks2 = ctx.computeOnClient(mc -> breakerBlocks(trap, "ownRoute"));
            System.out.println("[" + NAME + "] " + tag + " breaker undo: blocks " + blocks1 + " -> " + blocks2);
            if (blocks2 != blocks0) {
                failures.add(tag + ": /ar undo of the breaker pick left " + blocks2 + " block(s) in his saved route, wanted " + blocks0);
            } else {
                acted++;
            }
            ctx.runOnClient(mc -> ModUnderTest.staticCall(FEATURE, "setEditMode", new Class<?>[]{boolean.class}, new Object[]{false}));
        }
        return acted;
    }

    private static void reload(ClientGameTestContext ctx) {
        ctx.waitTicks(2);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(STORE, "reload"));
        ctx.waitTicks(2);
    }

    private static void removeOwn(ClientGameTestContext ctx, String room) {
        ctx.runOnClient(mc -> {
            ModUnderTest.call(store(), "remove", new Class<?>[]{String.class}, new Object[]{room});
            ModUnderTest.call(store(), "save", NONE, NOARGS);
        });
        ctx.waitTicks(2);
    }

    private static List<?> nodesOf(String room, String which) {
        Object r = ModUnderTest.call(store(), which, new Class<?>[]{String.class}, new Object[]{room});
        return r == null ? List.of() : (List<?>) ModUnderTest.call(r, "nodes", NONE, NOARGS);
    }

    /** Radius of node 2 of the room's route, -1 when missing. */
    private static double nodeRadius(String room, String which) {
        List<?> nodes = nodesOf(room, which);
        return nodes.size() > 1 ? (Double) ModUnderTest.call(nodes.get(1), "radius", NONE, NOARGS) : -1;
    }

    /** Blocks of the first breaker node of the room's route, -1 when none. */
    private static int breakerBlocks(String room, String which) {
        for (Object n : nodesOf(room, which)) {
            if ("DUNGEON_BREAKER".equals(String.valueOf(ModUnderTest.call(n, "type", NONE, NOARGS)))) {
                return ((List<?>) ModUnderTest.call(n, "breakerBlocks", NONE, NOARGS)).size();
            }
        }
        return -1;
    }

    private static int count(ClientGameTestContext ctx, String room, String which) {
        return ctx.computeOnClient(mc -> nodesOf(room, which).size());
    }

    private static void stopRoute(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            if ((Boolean) ModUnderTest.staticCall(EXEC, "isRunning")) {
                ModUnderTest.staticCall(EXEC, "stop", new Class<?>[]{String.class}, new Object[]{NAME});
            }
        });
        ctx.waitTicks(2);
    }

    /** His chat line, typed into a real chat screen. */
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

    private static void tp(ClientGameTestContext ctx, double x, double y, double z) {
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            UUID uuid = mc.player.getUUID();
            server.execute(() -> server.getPlayerList().getPlayer(uuid).teleportTo(server.overworld(), x, y, z,
                    java.util.Set.<net.minecraft.world.entity.Relative>of(), 0f, 0f, false));
        });
    }
}
