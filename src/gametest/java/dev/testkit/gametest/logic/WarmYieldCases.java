package dev.testkit.gametest.logic;

import dev.testkit.gametest.mod.Mod;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 1122-logic-warm-yield (mod acwaits5-1122): a server correction's replan waited 656 ms on the planner thread behind
 * the floor graph's warm-up (acwaits4 fixsim6 trial 2; his 11-21 waits of 684-905 ms after Boulder), because the
 * warm-up's last step built every tile's field in one go and a worker batch ran to its end past the slice. Now
 * WarpGraph.warm keeps to its budget: the batch and the field build stop at the slice's end and go on next call, and
 * nothing of a half-built field set is visible (warmDone stays false until the fields are published).
 *
 * <p>The case runs the planner the way EtherwarpPathfinder.tickWarm does (one planner thread, warm slices of 10 ms on
 * two workers, the slice loop stopping when a trip is waiting), and while it warms queues an empty "trip" every 15 ms.
 * Each trip must start within {@link #MAX_QUEUED_MS}. The floor has many tiles so the field build alone takes far
 * longer than that (checked: the case fails as vacuous otherwise). Fails on acwaits4-1122 aa3dacc3e.
 */
final class WarmYieldCases {

    private static final String AC = "livemap.autoclear.";
    private static final double RANGE = 57.0;
    private static final int FLOOR_Y = 64;
    private static final int HALF = 90;
    private static final int STEP = 6;
    private static final int TILES = 31 * 31;
    private static final long SLICE_NANOS = 10_000_000L;
    private static final long TASK_NANOS = 250_000_000L;
    private static final long MAX_QUEUED_MS = 100;

    private WarmYieldCases() {
    }

    static void yieldToTrip(LogicCase c) throws Exception {
        Class<?> gridI = Mod.cls(AC + "EtherSearch$Grid");
        Class<?> ruleI = Mod.cls(AC + "WarpGraph$LandingRule");
        Class<?> tilesI = Mod.cls(AC + "WarpGraph$Tiles");
        Object grid = Proxy.newProxyInstance(gridI.getClassLoader(), new Class<?>[]{gridI}, (p, m, a) -> switch (m.getName()) {
            case "flags" -> pillar((int) a[0], (int) a[1], (int) a[2]) ? 1 << 4 : 9;
            case "hashCode" -> System.identityHashCode(p);
            case "equals" -> p == a[0];
            default -> "grid";
        });
        Object rule = Proxy.newProxyInstance(ruleI.getClassLoader(), new Class<?>[]{ruleI}, (p, m, a) -> switch (m.getName()) {
            case "ok" -> pillar((int) a[1], (int) a[2], (int) a[3]);
            case "hashCode" -> System.identityHashCode(p);
            case "equals" -> p == a[0];
            default -> "rule";
        });
        Object tiles = Proxy.newProxyInstance(tilesI.getClassLoader(), new Class<?>[]{tilesI}, (p, m, a) -> switch (m.getName()) {
            case "tileOf" -> pillar((int) a[0], (int) a[1], (int) a[2]) ? index((int) a[0], (int) a[2]) : -1;
            case "cellOf" -> Math.abs((int) a[0]) <= HALF && Math.abs((int) a[1]) <= HALF
                    ? index((int) a[0], (int) a[1]) : -1;
            case "count" -> TILES;
            case "hashCode" -> System.identityHashCode(p);
            case "equals" -> p == a[0];
            default -> "tiles";
        });
        Class<?> wgc = Mod.cls(AC + "WarpGraph");
        Constructor<?> wc = wgc.getDeclaredConstructor(double.class, double.class, int.class, ruleI, int.class, int.class);
        wc.setAccessible(true);
        Object g = wc.newInstance(RANGE, 1.05, 2, rule, FLOOR_Y - 20, FLOOR_Y + 45);
        wgc.getMethod("setTiles", tilesI).invoke(g, tiles);
        Method warm = wgc.getMethod("warm", gridI, Supplier.class, ExecutorService.class, int.class, double.class,
                double.class, double.class, long.class);
        Method warmDone = wgc.getMethod("warmDone");
        Supplier<Object> grids = () -> grid;

        ExecutorService planner = Executors.newSingleThreadExecutor(r -> daemon(r, "warmyield-planner"));
        ExecutorService workers = Executors.newFixedThreadPool(2, r -> daemon(r, "warmyield-worker"));
        AtomicBoolean waiting = new AtomicBoolean();
        AtomicBoolean halfVisible = new AtomicBoolean();
        long maxQueuedNanos = 0;
        int trips = 0;
        int warmTasks = 0;
        boolean done = false;
        long t0 = System.nanoTime();
        try {
            Future<?> warmTask = null;
            long nextTrip = System.nanoTime();
            while (System.nanoTime() - t0 < 90_000_000_000L) {
                if (warmTask == null || warmTask.isDone()) {
                    if (warmTask != null) {
                        warmTask.get();
                    }
                    if ((boolean) warmDone.invoke(g)) {
                        done = true;
                        break;
                    }
                    warmTasks++;
                    // As EtherwarpPathfinder.tickWarm: slices until a trip waits or the task's time is up.
                    warmTask = planner.submit(() -> {
                        long s = System.nanoTime();
                        boolean more = true;
                        while (more && !waiting.get() && System.nanoTime() - s < TASK_NANOS) {
                            more = (boolean) warm.invoke(g, grid, grids, workers, 2, 0.5, FLOOR_Y + 1.0, 0.5, SLICE_NANOS);
                        }
                        return null;
                    });
                }
                if (System.nanoTime() >= nextTrip) {
                    long queuedAt = System.nanoTime();
                    waiting.set(true);
                    Future<Long> trip = planner.submit(() -> {
                        long q = System.nanoTime() - queuedAt;
                        // What a search sees: never a graph called warm without its fields.
                        if ((boolean) warmDone.invoke(g) && !(boolean) Mod.field(g, "fieldsValid")) {
                            halfVisible.set(true);
                        }
                        waiting.set(false);
                        return q;
                    });
                    long q = trip.get(60, TimeUnit.SECONDS);
                    maxQueuedNanos = Math.max(maxQueuedNanos, q);
                    trips++;
                    nextTrip = System.nanoTime() + 15_000_000L;
                }
                Thread.sleep(1);
            }
        } finally {
            planner.shutdownNow();
            workers.shutdownNow();
        }
        long fieldsNanos = (long) wgc.getField("nanosFields").get(g);
        long nodes = ((Number) wgc.getMethod("nodeCount").invoke(g)).longValue();
        c.note(String.format("%d node(s), warm in %.0f ms over %d task(s), field build %.1f ms; %d trip(s), the longest queued %.1f ms",
                nodes, (System.nanoTime() - t0) / 1e6, warmTasks, fieldsNanos / 1e6, trips, maxQueuedNanos / 1e6));
        c.check("the warm-up finished", done, "not warm after 90 s");
        c.check("the warm-up published its fields", done && (boolean) Mod.field(g, "fieldsValid"));
        c.check("the field build is long enough to matter (it alone would hold a search past the bound)",
                fieldsNanos > MAX_QUEUED_MS * 1_000_000L, String.format("field build %.1f ms", fieldsNanos / 1e6));
        c.check("trips were queued while it warmed", trips >= 5, trips + " trip(s)");
        c.check("every trip queued behind the warm-up started within " + MAX_QUEUED_MS + " ms",
                maxQueuedNanos <= MAX_QUEUED_MS * 1_000_000L, String.format("longest %.1f ms", maxQueuedNanos / 1e6));
        c.check("no trip saw a graph called warm without its fields", !halfVisible.get());
    }

    /** A one-block pillar top every 6 blocks on the floor: about 960 landings, each seeing ~200 others. */
    private static boolean pillar(int x, int y, int z) {
        return y == FLOOR_Y && Math.abs(x) <= HALF && Math.abs(z) <= HALF && Math.floorMod(x, STEP) == 0
                && Math.floorMod(z, STEP) == 0;
    }

    private static int index(int x, int z) {
        return Math.floorMod(Math.floorDiv(x + HALF, STEP) * 31 + Math.floorDiv(z + HALF, STEP), TILES);
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }
}
