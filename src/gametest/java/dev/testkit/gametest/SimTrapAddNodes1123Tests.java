package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import dev.testkit.compat.McCompat;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 1123-sim-trap-addnodes (newtrap-pathnodes-1123, killer560 2026-11-23: "I am adding path nodes in new trap but they arent
 * shoing up yet chat recognizes it"): with Auto Trap on, a node added with {@code /ar add path} in New Trap or Old Trap is in
 * the route Auto Routes draws and plays ({@code RouteStore.forRoom}, what the renderer and arming read), and in the routes
 * file a restart reads back ({@code RouteStore.reload}). Before the fix Auto Trap's bundled route won over his own, so the
 * node went into his own route on disk and was never drawn.
 *
 * <p>Per trap (rotation 0): his own route for the room removed, he stands on the bundled route's start (the route that
 * arms is stopped), then two {@code /ar add path} lines: the first in a room with only the bundled route, the second once
 * he has his own. The neighbor room (an ordinary room, no overlay) gets the same two lines as a control.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimTrapAddNodes1123Tests implements FabricClientGameTest {

    private static final String NAME = "1123-sim-trap-addnodes";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String STORE = "com.killer560.hub.autoroutes.RouteStore";
    private static final String EXEC = "com.killer560.hub.autoroutes.RouteExecutor";
    private static final String ROUTE_COORDS = "com.killer560.hub.autoroutes.RouteCoords";

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
                        (c, f, t, r, tag, north) -> acted[0] += body(c, f, t, tag, north));
            }
        } catch (Throwable t) {
            failures.add("scenario threw: " + t);
            t.printStackTrace(System.out);
        } finally {
            ctx.runOnClient(mc -> {
                for (String room : new String[]{"New Trap", "Old Trap", "Temple", "Cages", "Arrow Trap"}) {
                    ModUnderTest.call(store(), "remove", new Class<?>[]{String.class}, new Object[]{room});
                }
                ModUnderTest.call(store(), "save", new Class<?>[]{}, new Object[]{});
            });
            SimTrapRoutes1121Tests.tearDown(ctx, renderBefore);
        }
        if (acted[0] < 6) {
            failures.add("only " + acted[0] + " of 6 /ar add path lines were answered with \"Added Path\" - the case never acted");
        }
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " problem(s): " + String.join("; ", failures));
        }
        System.out.println("[" + NAME + "] PASS");
    }

    private static Object store() {
        return ModUnderTest.staticCall(STORE, "getInstance");
    }

    /** @return how many adds chat confirmed. */
    private static int body(ClientGameTestContext ctx, List<String> failures, String trap, String tag, String north) {
        ctx.runOnClient(mc -> {
            ModUnderTest.call(store(), "remove", new Class<?>[]{String.class}, new Object[]{trap});
            ModUnderTest.call(store(), "remove", new Class<?>[]{String.class}, new Object[]{north});
            ModUnderTest.call(store(), "save", new Class<?>[]{}, new Object[]{});
        });
        SimTrapRoutes1121Tests.standOnTile(ctx, SimTrapRoutes1121Tests.NX, SimTrapRoutes1121Tests.NZ, 0, 0);
        int ok = addTwice(ctx, failures, north, tag + " control " + north);
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
            return ok;
        }
        tp(ctx, start.getX() + 0.5, start.getY(), start.getZ() + 0.5);
        ctx.waitTicks(30);
        stopRoute(ctx);
        Object shown = ctx.computeOnClient(mc -> ModUnderTest.call(store(), "forRoom", new Class<?>[]{String.class}, new Object[]{trap}));
        if (shown == null) {
            failures.add(tag + ": no route shown for " + trap + " before the adds (Auto Trap's bundled route missing)");
        }
        ok += addTwice(ctx, failures, trap, tag);
        return ok;
    }

    /** Two {@code /ar add path} lines in {@code room}: each must be confirmed in chat, drawn, and read back from the file. */
    private static int addTwice(ClientGameTestContext ctx, List<String> failures, String room, String tag) {
        int ok = 0;
        for (int n = 1; n <= 2; n++) {
            int before = count(ctx, room, "forRoom");
            long mark = LogTap.mark();
            cmd(ctx, "/ar add path");
            ctx.waitTicks(10);
            stopRoute(ctx);
            boolean chat = false;
            for (String l : LogTap.since(mark)) {
                chat |= l.contains("[Auto Routes] Added Path");
            }
            if (chat) {
                ok++;
            } else {
                failures.add(tag + " add " + n + ": chat never said \"Added Path\"");
            }
            int drawn = count(ctx, room, "forRoom");
            int own = count(ctx, room, "ownRoute");
            boolean lastIsPath = ctx.computeOnClient(mc -> {
                Object r = ModUnderTest.call(store(), "forRoom", new Class<?>[]{String.class}, new Object[]{room});
                List<?> nodes = r == null ? List.of() : (List<?>) ModUnderTest.call(r, "nodes", new Class<?>[]{}, new Object[]{});
                return !nodes.isEmpty() && "PATH".equals(String.valueOf(
                        ModUnderTest.call(nodes.get(nodes.size() - 1), "type", new Class<?>[]{}, new Object[]{})));
            });
            System.out.println("[" + NAME + "] " + tag + " add " + n + ": drawn route " + before + " -> " + drawn
                    + " node(s), his own route " + own + ", chat " + chat);
            if (drawn != before + 1 || !lastIsPath) {
                failures.add(tag + " add " + n + ": the drawn route went " + before + " -> " + drawn
                        + " node(s), last is PATH " + lastIsPath + " (his own route has " + own + ") - the node is not drawn");
            }
            if (own != drawn) {
                failures.add(tag + " add " + n + ": the drawn route (" + drawn + ") is not his own saved route (" + own + ")");
            }
        }
        ctx.runOnClient(mc -> ModUnderTest.staticCall(STORE, "reload"));
        ctx.waitTicks(2);
        int reread = count(ctx, room, "ownRoute");
        int drawn = count(ctx, room, "forRoom");
        System.out.println("[" + NAME + "] " + tag + ": after a reload of the routes file his own route has " + reread
                + " node(s), the drawn one " + drawn);
        if (reread != drawn || reread < 2) {
            failures.add(tag + ": after a reload the file holds " + reread + " node(s) and " + drawn + " are drawn");
        }
        return ok;
    }

    private static int count(ClientGameTestContext ctx, String room, String which) {
        return ctx.computeOnClient(mc -> {
            Object r = ModUnderTest.call(store(), which, new Class<?>[]{String.class}, new Object[]{room});
            return r == null ? 0 : ((List<?>) ModUnderTest.call(r, "nodes", new Class<?>[]{}, new Object[]{})).size();
        });
    }

    private static void stopRoute(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            if ((Boolean) ModUnderTest.staticCall(EXEC, "isRunning")) {
                ModUnderTest.staticCall(EXEC, "stop", new Class<?>[]{String.class}, new Object[]{"1123-sim-trap-addnodes"});
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
