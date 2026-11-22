package dev.testkit.gametest.logic;

import dev.testkit.gametest.mod.Mod;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 1122-logic-tripcache-refused (mod acwaits3-1122): his 11-21 log had a precomputed trip whose first warp never landed
 * from a spot handed out again five times, because the planner stored the same trip again after each reject. A trip
 * that went wrong from a spot is never handed out from that spot again in that world (bounded set, cleared on world
 * change); the same trip from another spot still is. Fails on a9d22cbef (step "the re-stored trip is refused").
 */
final class TripRefuseCases {

    private static final String TC = "livemap.autoclear.TripCache";
    private static final double RANGE = 57.0;

    private TripRefuseCases() {
    }

    @SuppressWarnings("unchecked")
    static void refused(LogicCase c) throws Exception {
        Class<?> tc = Mod.cls(TC);
        Method clear = tc.getDeclaredMethod("clear");
        clear.setAccessible(true);
        Method reject = null;
        for (Method m : tc.getDeclaredMethods()) {
            if (m.getName().equals("reject") && m.getParameterCount() == 2) {
                reject = m;
            }
        }
        reject.setAccessible(true);
        Object range0 = Mod.field(TC, "lastRange");
        try (AutoCloseable map = Mod.with("livemap.LiveMapConfig", "InteractiveMapEnabled", true)) {
            clear.invoke(null);
            Mod.setField(TC, "lastRange", RANGE);
            Vec3 spot = new Vec3(10.5, 70, 10.5);
            Vec3 near = new Vec3(10.7, 70, 10.3);
            Object e1 = put(tc, spot, 5);
            c.check("a stored trip is found from its spot", find(near, 5) == e1);
            reject.invoke(null, e1, "it went wrong in flight (warp 1 never landed)");
            c.check("a rejected trip is gone", find(near, 5) == null);
            Object e2 = put(tc, spot, 5);  // the planner stores the same trip again
            c.check("the re-stored trip is refused from the spot it failed from", find(near, 5) == null,
                    "find handed out " + find(near, 5));
            Object e3 = put(tc, new Vec3(30.5, 70, 30.5), 5);
            c.check("the same goal from another spot is still handed out", find(new Vec3(30.4, 70, 30.6), 5) == e3);
            Object e4 = put(tc, spot, 6);
            c.check("another goal from the failed spot is still handed out", find(near, 6) == e4);
            int[] st = (int[]) Mod.staticCall(TC, "stats");
            c.check("the refusal acted (a lookup skipped it)", st.length > 9 && st[9] > 0, "stats length " + st.length);
            clear.invoke(null);
            Mod.setField(TC, "lastRange", RANGE);
            Object e5 = put(tc, spot, 5);
            c.check("a world change forgets the refusal", find(near, 5) == e5);
            for (int i = 0; i < 300; i++) {
                reject.invoke(null, put(tc, new Vec3(100.5 + i * 3, 70, 10.5), 7), "bound");
            }
            Set<String> refused = (Set<String>) Mod.field(TC, "REFUSED");
            c.check("the refused set stays bounded", refused.size() <= 256, "size " + refused.size());
            c.note("refused skips " + st[9] + ", set after 300 rejects " + refused.size());
        } finally {
            clear.invoke(null);
            Mod.setField(TC, "lastRange", range0);
        }
    }

    private static Object find(Vec3 at, int tile) {
        return Mod.staticCall(TC, "find", at, BlockPos.ZERO, tile, false);
    }

    @SuppressWarnings("unchecked")
    private static Object put(Class<?> tc, Vec3 from, int tile) throws Exception {
        Class<?> goalCls = Mod.cls(TC + "$Goal");
        Constructor<?> gc = goalCls.getDeclaredConstructor(BlockPos.class, int.class, boolean.class);
        gc.setAccessible(true);
        Object goal = gc.newInstance(BlockPos.ZERO, tile, false);
        Constructor<?> ec = Mod.cls(TC + "$Entry").getDeclaredConstructors()[0];
        ec.setAccessible(true);
        Object e = ec.newInstance(from, goal, List.of(), BlockPos.ZERO, new double[0], true, true, 0L, 1, RANGE);
        Map<String, Map<String, Object>> trips = (Map<String, Map<String, Object>>) Mod.field(TC, "TRIPS");
        String fromKey = (int) Math.floor(from.x) + "," + (int) Math.floor(from.y) + "," + (int) Math.floor(from.z);
        trips.computeIfAbsent("T" + tile, k -> new ConcurrentHashMap<>()).put(fromKey, e);
        return e;
    }
}
