package dev.testkit.gametest.logic;

import dev.testkit.gametest.mod.Mod;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 1122-logic-refused-hop (mod acwaits4-1122): after a warp never landed from a spot (his 11-21 log: warp 1 lost five
 * times from one spot), a fresh plan from that spot picked the same first warp again. Now the spot's refused landing is
 * never a plan's first hop: the floor graph plans around it (a one-warp trip becomes two), a precomputed trip for any
 * goal starting with it is not handed out, a block change near its ray or a world change forgets it, and the set stays
 * bounded. Fails on acwaits3-1122 645a32c6a (no WarpGraph.Goal.refusedFirst, no TripCache.refuseHop).
 */
final class RefusedHopCases {

    private static final String TC = "livemap.autoclear.TripCache";
    private static final String AC = "livemap.autoclear.";
    private static final double RANGE = 57.0;
    private static final int FLOOR_Y = 64;
    private static final int HALF = 40;

    private RefusedHopCases() {
    }

    static void refused(LogicCase c) throws Exception {
        graph(c);
        cache(c);
    }

    /** A flat 81x81 floor: a goal 30 blocks off is one warp; with that landing refused from the start, two. */
    @SuppressWarnings("unchecked")
    private static void graph(LogicCase c) throws Exception {
        Class<?> gridI = Mod.cls(AC + "EtherSearch$Grid");
        Class<?> ruleI = Mod.cls(AC + "WarpGraph$LandingRule");
        Object grid = Proxy.newProxyInstance(gridI.getClassLoader(), new Class<?>[]{gridI}, (p, m, a) -> switch (m.getName()) {
            case "flags" -> floor((int) a[0], (int) a[1], (int) a[2]) ? 1 << 4 : 9;
            case "hashCode" -> System.identityHashCode(p);
            case "equals" -> p == a[0];
            default -> "grid";
        });
        Object rule = Proxy.newProxyInstance(ruleI.getClassLoader(), new Class<?>[]{ruleI}, (p, m, a) -> switch (m.getName()) {
            case "ok" -> floor((int) a[1], (int) a[2], (int) a[3]);
            case "hashCode" -> System.identityHashCode(p);
            case "equals" -> p == a[0];
            default -> "rule";
        });
        Class<?> wgc = Mod.cls(AC + "WarpGraph");
        Constructor<?> wc = wgc.getDeclaredConstructor(double.class, double.class, int.class, ruleI, int.class, int.class);
        wc.setAccessible(true);
        Object g = wc.newInstance(RANGE, 1.05, 2, rule, FLOOR_Y - 20, FLOOR_Y + 45);
        Class<?> hopC = Mod.cls(AC + "EtherSearch$Hop");
        Object start = hopC.getDeclaredConstructor(double.class, double.class, double.class, int.class, int.class, int.class,
                float.class, float.class).newInstance(0.5, FLOOR_Y + 1.0, 0.5, 0, FLOOR_Y + 1, 0, 0f, 0f);
        Method plan = null;
        for (Method m : wgc.getDeclaredMethods()) {
            if (m.getName().equals("plan") && m.getParameterCount() == 5) {
                plan = m;
            }
        }
        plan.setAccessible(true);
        Object plain = goal(30, 0, null);
        List<Object> p1 = (List<Object>) plan.invoke(g, grid, start, plain, System.nanoTime() + 5_000_000_000L, 16);
        c.check("with nothing refused the goal 30 blocks off is one warp", p1 != null && p1.size() == 1,
                "plan " + (p1 == null ? "none" : p1.size() + " warp(s)"));
        long goalKey = (Long) Mod.staticCall(AC + "EtherSearch", "pack", 30, FLOOR_Y, 0);
        Object refused = goal(30, 0, Set.of(goalKey));
        List<Object> p2 = (List<Object>) plan.invoke(g, grid, start, refused, System.nanoTime() + 5_000_000_000L, 16);
        boolean around = p2 != null && p2.size() >= 2;
        String firstLanding = "none";
        if (around) {
            Object h = p2.get(1);
            int bx = (int) Math.floor((double) Mod.field(h, "x"));
            int bz = (int) Math.floor((double) Mod.field(h, "z"));
            firstLanding = bx + "," + bz;
            around = !(bx == 30 && bz == 0);
        }
        c.check("with that landing refused from the start the plan goes around it", around,
                "plan " + (p2 == null ? "none" : p2.size() + " warp(s), first landing " + firstLanding));
        c.note("plain " + (p1 == null ? -1 : p1.size()) + " warp(s), refused " + (p2 == null ? -1 : p2.size())
                + " warp(s), first landing " + firstLanding);
    }

    private static boolean floor(int x, int y, int z) {
        return y == FLOOR_Y && Math.abs(x) <= HALF && Math.abs(z) <= HALF;
    }

    private static Object goal(int x, int z, Set<Long> refused) throws Exception {
        Class<?> gc = Mod.cls(AC + "WarpGraph$Goal");
        Constructor<?> k = gc.getDeclaredConstructor();
        k.setAccessible(true);
        Object g = k.newInstance();
        gc.getField("x").setInt(g, x);
        gc.getField("y").setInt(g, FLOOR_Y);
        gc.getField("z").setInt(g, z);
        if (refused != null) {
            Field f = gc.getField("refusedFirst");
            f.set(g, refused);
        }
        return g;
    }

    /** TripCache's refused hops: recorded, matched by spot, skipped by find, forgotten on a nearby change, bounded. */
    @SuppressWarnings("unchecked")
    private static void cache(LogicCase c) throws Exception {
        Class<?> tc = Mod.cls(TC);
        Method clear = tc.getDeclaredMethod("clear");
        clear.setAccessible(true);
        Object range0 = Mod.field(TC, "lastRange");
        try (AutoCloseable map = Mod.with("livemap.LiveMapConfig", "InteractiveMapEnabled", true)) {
            clear.invoke(null);
            Mod.setField(TC, "lastRange", RANGE);
            Vec3 spot = new Vec3(10.5, 65.0, 10.5);
            Vec3 near = new Vec3(10.7, 65.0, 10.3);
            BlockPos land = new BlockPos(40, 64, 10);
            Mod.staticCall(TC, "refuseHop", spot, new Vec3(40.5, 65.05, 10.5));
            Set<Long> r = (Set<Long>) Mod.staticCall(TC, "refusedLandings", near);
            long key = (Long) Mod.staticCall(AC + "EtherSearch", "pack", 40, 64, 10);
            c.check("the refused landing is held for the spot", r != null && r.contains(key), "held " + r);
            c.check("another spot has none", Mod.staticCall(TC, "refusedLandings", new Vec3(30.5, 65.0, 30.5)) == null);
            Object bad = put(spot, 5, land);
            Object good = put(spot, 6, new BlockPos(20, 64, 10));
            c.check("a precomputed trip whose first warp is the refused one is not handed out", find(near, 5) == null,
                    "find handed out " + find(near, 5));
            c.check("a trip from the spot with another first warp still is", find(near, 6) == good);
            Mod.staticCall(TC, "blocksChanged", 100, 60, 100, 101, 61, 101);
            c.check("a block change away from its ray keeps the refusal",
                    Mod.staticCall(TC, "refusedLandings", near) != null);
            Mod.staticCall(TC, "blocksChanged", 25, 65, 10, 25, 66, 10);
            c.check("a block change on its ray forgets it (it may land now)",
                    Mod.staticCall(TC, "refusedLandings", near) == null);
            Mod.staticCall(TC, "refuseHop", spot, new Vec3(40.5, 65.05, 10.5));
            clear.invoke(null);
            c.check("a world change forgets it", Mod.staticCall(TC, "refusedLandings", near) == null);
            for (int i = 0; i < 300; i++) {
                Mod.staticCall(TC, "refuseHop", new Vec3(100.5 + i * 3, 65.0, 10.5), new Vec3(130.5 + i * 3, 65.05, 10.5));
            }
            Map<String, ?> hops = (Map<String, ?>) Mod.field(TC, "REFUSED_HOPS");
            c.check("the refused hops stay bounded", hops.size() <= 256, "size " + hops.size());
            c.note("bad entry " + (bad != null) + ", held after 300 refusals " + hops.size());
        } finally {
            clear.invoke(null);
            Mod.setField(TC, "lastRange", range0);
        }
    }

    private static Object find(Vec3 at, int tile) {
        return Mod.staticCall(TC, "find", at, BlockPos.ZERO, tile, false);
    }

    /** A one-warp precomputed trip from {@code from} into {@code tile} landing on {@code landing}. */
    @SuppressWarnings("unchecked")
    private static Object put(Vec3 from, int tile, BlockPos landing) throws Exception {
        Class<?> goalCls = Mod.cls(TC + "$Goal");
        Constructor<?> gc = goalCls.getDeclaredConstructor(BlockPos.class, int.class, boolean.class);
        gc.setAccessible(true);
        Object goal = gc.newInstance(BlockPos.ZERO, tile, false);
        Constructor<?> ec = Mod.cls(TC + "$Entry").getDeclaredConstructors()[0];
        ec.setAccessible(true);
        Object e = ec.newInstance(from, goal, List.of(), landing, new double[0], true, true, 0L, 1, RANGE);
        Map<String, Map<String, Object>> trips = (Map<String, Map<String, Object>>) Mod.field(TC, "TRIPS");
        String fromKey = (int) Math.floor(from.x) + "," + (int) Math.floor(from.y) + "," + (int) Math.floor(from.z);
        trips.computeIfAbsent("T" + tile, k -> new ConcurrentHashMap<>()).put(fromKey, e);
        return e;
    }
}
