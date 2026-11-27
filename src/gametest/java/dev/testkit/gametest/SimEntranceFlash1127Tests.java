package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * 1127-sim-entranceflash (entrance-flash-1127): no Auto Routes node is drawn or armed between a world change and the new
 * dungeon's own "Dungeon entered".
 *
 * <p>killer560 (2026-11-27 1:55 AM): "When I start a new run auto routes show in green room for a second then go away." His
 * log: "[AutoRoutes] Reset (world change)" at 01:30:34, "Arming: not in a dungeon clear (isInDungeon=false)", and the live map
 * ("Dungeon entered - grid reset") only at 01:30:35, because a held run keeps its grid for a rejoin. For that second the
 * map still held the LAST run's rooms, the new spawn sat in one of them, and the renderer drew that room's nodes.
 *
 * <p>Here a route is seeded in the room he stands in (one node 3 blocks ahead, a START node at his feet). Phase 1: it is
 * drawn (renderStats frames drawn rises). Phase 2 (the world-change gap, DungeonState held as "not in a dungeon" with the
 * map's grid untouched, as the real gap is): over 30 ticks no frame draws a node and the route never runs (FAILS on a jar
 * that draws from the map alone). Phase 3: back in the dungeon it draws again.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimEntranceFlash1127Tests implements FabricClientGameTest {

    private static final String NAME = "1127-sim-entranceflash";
    private static final String STATE = "com.killer560.hub.secrets.DungeonState";
    private static final String RENDERER = "com.killer560.hub.autoroutes.AutoRoutesRenderer";
    private static final String EXEC = "com.killer560.hub.autoroutes.RouteExecutor";
    private static final String COORDS = "com.killer560.hub.autoroutes.RouteCoords";
    private static final int GAP_TICKS = 30;

    private static volatile boolean outside;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        try {
            if (!Class.forName("com.killer560.hub.BuildVariant").getField("CHEAT_FEATURES_ENABLED").getBoolean(null)) {
                // Auto Routes is cheat-only: a legit jar draws no route node anywhere, so there is no gap to watch.
                System.out.println("[" + NAME + "] legit jar, Auto Routes is cheat-only (CHEAT_FEATURES_ENABLED false)");
                System.out.println("[" + NAME + "] PASS");
                return;
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("no BuildVariant.CHEAT_FEATURES_ENABLED", e);
        }
        Integer renderBefore = SimTrapRoutes1121Tests.setUp(ctx, NAME);
        if (renderBefore == null) {
            return;
        }
        List<String> failures = new ArrayList<>();
        try {
            SimTrapRoutes1121Tests.oneFloor(ctx, failures, "New Trap", 0, SimEntranceFlash1127Tests::body);
        } catch (Throwable t) {
            failures.add("scenario threw: " + t);
            t.printStackTrace(System.out);
        } finally {
            outside = false;
            SimTrapRoutes1121Tests.tearDown(ctx, renderBefore);
        }
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " problem(s): " + String.join("; ", failures));
        }
        System.out.println("[" + NAME + "] PASS");
    }

    /** Holds DungeonState at "not in a dungeon" while {@link #outside}, before and after the mod's own tick work. */
    private static void hold(Minecraft mc) {
        if (!outside) {
            return;
        }
        try {
            Class<?> c = Class.forName(STATE);
            for (String f : new String[]{"cachedFloor", "roomSimActive", "simOverrideActive"}) {
                Field fld = c.getDeclaredField(f);
                fld.setAccessible(true);
                fld.set(null, f.equals("cachedFloor") ? null : Boolean.FALSE);
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot hold DungeonState out of the dungeon", e);
        }
    }

    private static boolean inDungeon() {
        return (Boolean) ModUnderTest.staticCall(STATE, "isInDungeon");
    }

    private static long framesDrawn() {
        return ((long[]) ModUnderTest.staticCall(RENDERER, "renderStats"))[3];
    }

    private static boolean running() {
        return (Boolean) ModUnderTest.staticCall(EXEC, "isRunning");
    }

    private static void body(ClientGameTestContext ctx, List<String> failures, String trap, int rot, String tag, String north) {
        SimTrapRoutes1121Tests.standOnTile(ctx, SimTrapRoutes1121Tests.NX, SimTrapRoutes1121Tests.NZ, 0, 0);
        ClientTickEvents.START_CLIENT_TICK.register(SimEntranceFlash1127Tests::hold);
        ClientTickEvents.END_CLIENT_TICK.register(SimEntranceFlash1127Tests::hold);
        String seeded = ctx.computeOnClient(mc -> seed());
        System.out.println("[" + NAME + "] " + seeded);
        if (!seeded.startsWith("seeded")) {
            failures.add(tag + ": " + seeded);
            return;
        }
        // Phase 1: drawn in the dungeon.
        ctx.waitTicks(10);
        long a = ctx.computeOnClient(mc -> framesDrawn());
        ctx.waitTicks(20);
        long b = ctx.computeOnClient(mc -> framesDrawn());
        if (b <= a) {
            failures.add(tag + ": the seeded route was never drawn in the dungeon (frames drawn " + a + " -> " + b
                    + "), so the case proves nothing");
            return;
        }
        // Phase 2: the world-change gap.
        ctx.runOnClient(mc -> {
            outside = true;
            hold(mc);
        });
        ctx.waitTicks(3);
        if (ctx.computeOnClient(mc -> inDungeon())) {
            failures.add(tag + ": could not hold DungeonState out of the dungeon");
            return;
        }
        long c = ctx.computeOnClient(mc -> framesDrawn());
        int ranTicks = 0;
        for (int i = 0; i < GAP_TICKS; i++) {
            ctx.waitTicks(1);
            if (ctx.computeOnClient(mc -> running())) {
                ranTicks++;
            }
        }
        long d = ctx.computeOnClient(mc -> framesDrawn());
        System.out.println("[" + NAME + "] gap: frames drawn " + c + " -> " + d + ", route ran " + ranTicks + " tick(s)");
        if (d != c) {
            failures.add(tag + ": " + (d - c) + " Auto Routes frame(s) drew nodes between the world change and the new dungeon");
        }
        if (ranTicks > 0) {
            failures.add(tag + ": a route was armed and running " + ranTicks + " tick(s) in the world-change gap");
        }
        // Phase 3: back in the dungeon it draws again.
        ctx.runOnClient(mc -> outside = false);
        ctx.waitTicks(10);
        long e = ctx.computeOnClient(mc -> framesDrawn());
        ctx.waitTicks(20);
        long f = ctx.computeOnClient(mc -> framesDrawn());
        if (f <= e) {
            failures.add(tag + ": the route did not draw again once back in the dungeon (frames drawn " + e + " -> " + f + ")");
        }
    }

    /** One WALK node 3 blocks ahead and a START node at his feet, in the room he stands in. */
    private static String seed() {
        try {
            Minecraft mc = Minecraft.getInstance();
            Object frame = ModUnderTest.staticCall(COORDS + "$Frame", "current");
            if (frame == null) {
                return "not seeded: the live map has no room frame here";
            }
            String room = (String) ModUnderTest.call(frame, "roomName", new Class<?>[]{}, new Object[]{});
            Object store = ModUnderTest.staticCall("com.killer560.hub.autoroutes.RouteStore", "getInstance");
            Object route = ModUnderTest.call(store, "forRoomOrCreate", new Class<?>[]{String.class}, new Object[]{room});
            @SuppressWarnings("unchecked")
            List<Object> nodes = (List<Object>) ModUnderTest.call(route, "nodes", new Class<?>[]{}, new Object[]{});
            nodes.clear();
            Class<?> nodeCls = Class.forName("com.killer560.hub.autoroutes.RouteNode");
            Class<?> typeCls = Class.forName("com.killer560.hub.autoroutes.RouteNode$Type");
            java.lang.reflect.Constructor<?> ctor = nodeCls.getConstructor(typeCls, double.class, double.class, double.class,
                    float.class, float.class, int.class);
            java.lang.reflect.Method toRel = Class.forName(COORDS).getMethod("toRelative", frame.getClass(), double.class,
                    double.class, double.class);
            double px = Math.floor(mc.player.getX()) + 0.5;
            double py = Math.floor(mc.player.getY() + 0.001);
            double pz = Math.floor(mc.player.getZ()) + 0.5;
            Vec3 ahead = (Vec3) toRel.invoke(null, frame, px + 3, py, pz);
            Vec3 feet = (Vec3) toRel.invoke(null, frame, px, py, pz);
            Object walk = ctor.newInstance(ModUnderTest.enumValue(typeCls.getName(), "WALK"), ahead.x, ahead.y, ahead.z, 0f, 0f, 0);
            Object start = ctor.newInstance(ModUnderTest.enumValue(typeCls.getName(), "START"), feet.x, feet.y, feet.z, 0f, 0f, 0);
            Field s = nodeCls.getField("start");
            s.set(start, Boolean.TRUE);
            nodes.add(start);
            nodes.add(walk);
            return "seeded a 2-node route in " + room;
        } catch (ReflectiveOperationException e) {
            return "not seeded: " + e;
        }
    }
}
