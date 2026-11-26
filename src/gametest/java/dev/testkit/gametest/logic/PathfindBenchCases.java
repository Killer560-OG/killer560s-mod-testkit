package dev.testkit.gametest.logic;

import dev.testkit.gametest.mod.Mod;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Mod pathfind-bench-1126 (tools/bench/LongBench and IslandBench; his 11-26 "how good it is on super long trips compared to
 * what it should be at theoretical max").
 * 1126-logic-pathfind-shortcut: the floor graph's 18 aim points miss a landing seen only along the far tenth of its top
 * (ExactBench -Dlip geometry: a stone right in front of the goal, the start on a pillar 10 back and 10 up), so its plan
 * takes 2 warps where one exact aim does it. WarpGraph.plan now re-aims the plan's own stops with exact aims
 * (EtherSearch.shortcut): 1 warp, and that warp lands on the goal. Fails on origin (no WarpGraph.shortcut).
 * 1126-logic-pathfind-tour: the Fairy Soul route order (GraphPathfinder.orderRoute) is the exact best on 24 seeded sets of
 * 8 and 12 souls (checked against a brute force / Held-Karp here). Origin's greedy tour plus 2-opt and single moves misses
 * the best on some of them. Asserts on route length only, never on time.
 */
final class PathfindBenchCases {

    private static final String AC = "livemap.autoclear.";
    private static final String PF = "pathfinding.";
    private static final double RANGE = 60.0;
    private static final int TY = 4;

    private PathfindBenchCases() {
    }

    // ------------------------------------------------------------------------------------------- the shortcut

    static void shortcut(LogicCase c) throws Exception {
        Class<?> gridI = Mod.cls(AC + "EtherSearch$Grid");
        Class<?> ruleI = Mod.cls(AC + "WarpGraph$LandingRule");
        Object grid = Proxy.newProxyInstance(gridI.getClassLoader(), new Class<?>[]{gridI}, (p, m, a) -> switch (m.getName()) {
            case "flags" -> stone((int) a[0], (int) a[1], (int) a[2]) ? 1 << 4 : 9;
            case "hashCode" -> System.identityHashCode(p);
            case "equals" -> p == a[0];
            default -> "grid";
        });
        Object rule = Proxy.newProxyInstance(ruleI.getClassLoader(), new Class<?>[]{ruleI}, (p, m, a) -> switch (m.getName()) {
            case "ok" -> true;
            case "hashCode" -> System.identityHashCode(p);
            case "equals" -> p == a[0];
            default -> "rule";
        });
        Object origin = plan(grid, rule, false);
        Object now = plan(grid, rule, true);
        int o = origin == null ? -1 : ((List<?>) origin).size();
        int n = now == null ? -1 : ((List<?>) now).size();
        c.check("without the shortcut the floor graph takes 2 warps onto the lip goal (the case is real)", o == 2,
                "origin " + o + " warp(s)");
        c.check("with the shortcut it is 1 warp", n == 1, "now " + n + " warp(s)");
        if (n == 1) {
            Object h = ((List<?>) now).get(0);
            Class<?> es = Mod.cls(AC + "EtherSearch");
            Object search = es.getConstructor(gridI).newInstance(grid);
            double eye = (double) es.getField("SNEAK_EYE").get(null);
            boolean lands = (boolean) es.getMethod("holds", double.class, double.class, double.class, float.class,
                    float.class, int.class, int.class, int.class, double.class).invoke(search, Mod.field(h, "x"),
                    (double) Mod.field(h, "y") + eye, Mod.field(h, "z"), Mod.field(h, "yaw"), Mod.field(h, "pitch"),
                    20, TY, 12, RANGE);
            c.check("the one warp lands on the goal and holds within the aim margin", lands);
        }
        c.note("origin " + o + " warp(s), now " + n);
    }

    /** ExactBench.lip: a stone floor at y 4, one stone in front of the goal, the start's pillar at x 10. */
    private static boolean stone(int x, int y, int z) {
        if (x < 1 || x > 46 || z < 1 || z > 22 || y < 0) {
            return false;
        }
        return y == TY || (x == 19 && z == 12 && y == TY + 1) || (x == 10 && z == 12 && y > TY && y <= TY + 10);
    }

    private static Object plan(Object grid, Object rule, boolean shortcut) throws Exception {
        Class<?> ruleI = Mod.cls(AC + "WarpGraph$LandingRule");
        Class<?> gridI = Mod.cls(AC + "EtherSearch$Grid");
        Class<?> wgc = Mod.cls(AC + "WarpGraph");
        Constructor<?> wc = wgc.getDeclaredConstructor(double.class, double.class, int.class, ruleI, int.class, int.class);
        wc.setAccessible(true);
        Object g = wc.newInstance(RANGE, 1.05, 2, rule, 0, 29);
        wgc.getField("partialFrom").setDouble(g, 0.3);
        wgc.getField("shortcut").setBoolean(g, shortcut);
        Object grids = (java.util.function.Supplier<Object>) () -> grid;
        Method warm = null;
        Method plan = null;
        for (Method m : wgc.getDeclaredMethods()) {
            if (m.getName().equals("warm") && m.getParameterCount() == 8) {
                warm = m;
            }
            if (m.getName().equals("plan") && m.getParameterCount() == 5) {
                plan = m;
            }
        }
        warm.setAccessible(true);
        plan.setAccessible(true);
        double sx = 10.5;
        double sy = TY + 11.0;
        double sz = 12.5;
        for (int i = 0; i < 400 && (boolean) warm.invoke(g, grid, grids, null, 1, sx, sy, sz, 50_000_000L); i++) {
            // the whole little world
        }
        Class<?> hopC = Mod.cls(AC + "EtherSearch$Hop");
        Object start = hopC.getDeclaredConstructor(double.class, double.class, double.class, int.class, int.class, int.class,
                float.class, float.class).newInstance(sx, sy, sz, 10, TY + 11, 12, 0f, 0f);
        Class<?> gc = Mod.cls(AC + "WarpGraph$Goal");
        Constructor<?> k = gc.getDeclaredConstructor();
        k.setAccessible(true);
        Object goal = k.newInstance();
        gc.getField("x").setInt(goal, 20);
        gc.getField("y").setInt(goal, TY);
        gc.getField("z").setInt(goal, 12);
        gc.getField("tile").setInt(goal, -1);
        return plan.invoke(g, grid, start, goal, System.nanoTime() + 2_000_000_000L, 16);
    }

    // ------------------------------------------------------------------------------------------- the tour

    static void tour(LogicCase c) throws Exception {
        Class<?> gp = Mod.cls(PF + "GraphPathfinder");
        Class<?> ig = Mod.cls(PF + "IslandGraph");
        Class<?> legI = Mod.cls(PF + "GraphPathfinder$LegCost");
        Object dist = gp.getField("GRAPH_DISTANCE").get(null);
        Method parse = ig.getMethod("parse", String.class, String.class);
        Method order = gp.getMethod("orderRoute", ig, Mod.cls(PF + "IslandGraph$Node"), List.class, legI);
        Random rng = new Random(1126);
        int exact = 0;
        int sets = 0;
        StringBuilder misses = new StringBuilder();
        for (int size : new int[]{8, 12}) {
            for (int s = 0; s < 12; s++) {
                int n = size + 1;
                double[][] pt = new double[n][2];
                for (double[] p : pt) {
                    p[0] = Math.round(rng.nextDouble() * 400);
                    p[1] = Math.round(rng.nextDouble() * 400);
                }
                StringBuilder json = new StringBuilder("{");
                for (int i = 0; i < n; i++) {
                    json.append(i == 0 ? "" : ",").append('"').append(i + 1).append("\":{\"Position\":\"")
                            .append((int) pt[i][0]).append(":70:").append((int) pt[i][1]).append("\",\"Neighbours\":{");
                    boolean first = true;
                    for (int j = 0; j < n; j++) {
                        if (j != i) {
                            json.append(first ? "" : ",").append('"').append(j + 1).append("\":")
                                    .append(String.format(Locale.ROOT, "%.4f", d(pt, i, j)));
                            first = false;
                        }
                    }
                    json.append("}}");
                }
                json.append('}');
                Object graph = parse.invoke(null, "BENCH", json.toString());
                Object[] nodes = (Object[]) ig.getField("nodes").get(graph);
                Object start = null;
                List<Object> targets = new ArrayList<>();
                int[] ptOf = new int[n];
                for (Object node : nodes) {
                    int id = (int) Mod.field(node, "id");
                    if (id == 1) {
                        start = node;
                    } else {
                        targets.add(node);
                    }
                }
                for (int t = 0; t < targets.size(); t++) {
                    ptOf[t] = (int) Mod.field(targets.get(t), "id") - 1;
                }
                int[] ord = (int[]) order.invoke(null, graph, start, targets, dist);
                double ours = 0;
                int at = 0;
                for (int o : ord) {
                    ours += d(pt, at, ptOf[o]);
                    at = ptOf[o];
                }
                double best = heldKarp(pt);
                sets++;
                if (ord.length == size && ours <= best + 1e-3) {
                    exact++;
                } else if (misses.length() < 300) {
                    misses.append(String.format(Locale.ROOT, " %d souls set %d: %.1f vs best %.1f;", size, s, ours, best));
                }
            }
        }
        c.check("the route order is the exact best on every seeded set", exact == sets,
                exact + " of " + sets + " exact;" + misses);
        c.note(exact + " of " + sets + " exact");
    }

    private static double d(double[][] pt, int i, int j) {
        double dx = pt[i][0] - pt[j][0];
        double dz = pt[i][1] - pt[j][1];
        return Math.round(Math.sqrt(dx * dx + dz * dz) * 1e4) / 1e4;
    }

    /** The exact cheapest open tour from point 0 through all others. */
    private static double heldKarp(double[][] pt) {
        int k = pt.length - 1;
        int full = 1 << k;
        double[][] dp = new double[full][k];
        for (double[] r : dp) {
            java.util.Arrays.fill(r, Double.MAX_VALUE);
        }
        for (int j = 0; j < k; j++) {
            dp[1 << j][j] = d(pt, 0, j + 1);
        }
        for (int m = 1; m < full; m++) {
            for (int j = 0; j < k; j++) {
                if (dp[m][j] == Double.MAX_VALUE) {
                    continue;
                }
                for (int t = 0; t < k; t++) {
                    if ((m & (1 << t)) == 0) {
                        double v = dp[m][j] + d(pt, j + 1, t + 1);
                        if (v < dp[m | (1 << t)][t]) {
                            dp[m | (1 << t)][t] = v;
                        }
                    }
                }
            }
        }
        double best = Double.MAX_VALUE;
        for (int j = 0; j < k; j++) {
            best = Math.min(best, dp[full - 1][j]);
        }
        return best;
    }
}
