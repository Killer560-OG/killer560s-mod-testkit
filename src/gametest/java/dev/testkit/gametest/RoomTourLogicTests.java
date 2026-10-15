package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * 364-logic-room-tour (mod branch ac-optimize-1015): the room order and the Auto Clear standing plan with made-up floors -
 * no world.
 *
 * <p>killer560, 2026-10-15: "spending 20 ticks etherwarping across a map for 2 secrets is less optimal than doing 2 1
 * secret rooms taking 5 ticks each" and "For rooms that need repositioning make sure that it always chooses the
 * repositioning path that will require the least amount of total repositions in the room."
 * <ul>
 *   <li>{@code TourPlanner}: his example - two 1-secret rooms next door before a 2-secret room across the map - where
 *       "most secrets first" crosses the map first; a floor where the best secrets-per-tick NEXT room leads away from a
 *       pair of rooms worth more, so the greedy order loses; and on 200 random 7-room floors the plan is the cheapest of
 *       all 5040 orders (the branch and bound is exact).</li>
 *   <li>{@code RepositionPlanner}: three mobs nine blocks apart in a line - one block between the first two reaches both,
 *       so two blocks, where nearest-mob-first takes three; on 200 random rooms its count equals a brute-force minimum
 *       cover and is never more than nearest-mob-first's.</li>
 * </ul>
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class RoomTourLogicTests implements FabricClientGameTest {

    private static final String NAME = "364-logic-room-tour";
    private static final String TOUR = "com.killer560.hub.autosecret.TourPlanner";
    private static final String COVER = "com.killer560.hub.autoclear.RepositionPlanner";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        List<String> problems = new ArrayList<>();
        ctx.runOnClient(mc -> {
            try {
                tourCases(problems);
                coverCases(problems);
            } catch (Throwable t) {
                problems.add("threw: " + t);
                t.printStackTrace(System.out);
            }
        });
        for (String p : problems) {
            println("FAIL: " + p);
        }
        if (!problems.isEmpty()) {
            throw new AssertionError(problems.size() + " problem(s): " + String.join("; ", problems));
        }
        println("PASS");
    }

    // ================================================================================================ room order

    private static void tourCases(List<String> problems) {
        // His example. Rooms: 0 = across the map (2 secrets, 20 ticks from here and from the others), 1 and 2 next door
        // (1 secret each, 5 ticks from here and between them). Work 0: secrets per tick of travel.
        double[] from = {20, 5, 5};
        double[][] between = {{0, 20, 20}, {20, 0, 5}, {20, 5, 0}};
        double[] w = {2, 1, 1};
        double[] work = {0, 0, 0};
        Object res = plan(from, between, w, work);
        int[] order = order(res);
        double[] mine = evaluate(order, from, between, w, work);
        double[] most = evaluate(new int[]{0, 1, 2}, from, between, w, work);
        println(String.format(Locale.US, "his example: order %s, value-weighted wait %.0f, done in %.0f ticks; most secrets"
                + " first (0,1,2): %.0f, %.0f ticks", Arrays.toString(order), mine[0], mine[1], most[0], most[1]));
        check(problems, "his example: a room next door first", order[0] != 0);
        check(problems, "his example: the 2-secret room across the map last", order[2] == 0);
        check(problems, "his example: done sooner than most-secrets-first", mine[1] < most[1]);
        check(problems, "his example: cheaper than most-secrets-first", mine[0] < most[0]);

        // A big room across the map is worth the trip first ("Rooms of 5 secrets or more are worth it"): with these trips
        // crossing first pays from 8 secrets (the rooms next door wait 20 ticks more, the big room 10 less).
        double[] w8 = {8, 1, 1};
        int[] order8 = order(plan(from, between, w8, work));
        println("eight secrets across the map: order " + Arrays.toString(order8));
        check(problems, "eight secrets across the map: crossed for first", order8[0] == 0);

        // Greedy next-best-rate leads away: room 0 (2 secrets, 5 ticks, rate 0.4) is next door, rooms 1 and 2 (3 secrets
        // each) are 10 ticks off and 2 apart, and 0 is 30 from them. Greedy: 0, 1, 2. Best: 1, 2, 0.
        double[] gf = {5, 10, 12};
        double[][] gb = {{0, 30, 30}, {30, 0, 2}, {32, 2, 0}};
        double[] gw = {2, 3, 3};
        double[] gwork = {0, 0, 0};
        int[] greedy = (int[]) ModUnderTest.staticCall(TOUR, "greedy",
                new Class<?>[]{double[].class, double[][].class, double[].class, double[].class},
                new Object[]{gf, gb, gw, gwork});
        int[] best = order(plan(gf, gb, gw, gwork));
        double[] cg = evaluate(greedy, gf, gb, gw, gwork);
        double[] cb = evaluate(best, gf, gb, gw, gwork);
        println(String.format(Locale.US, "greedy leads away: greedy %s (%.0f), plan %s (%.0f)", Arrays.toString(greedy),
                cg[0], Arrays.toString(best), cb[0]));
        check(problems, "greedy leads away: the greedy order starts next door", greedy[0] == 0);
        check(problems, "greedy leads away: the plan takes the pair first", best[0] == 1 && best[1] == 2);
        check(problems, "greedy leads away: the plan is cheaper", cb[0] < cg[0]);

        // Exact on random floors.
        Random rnd = new Random(1015);
        int worse = 0;
        int exactRuns = 0;
        for (int t = 0; t < 200; t++) {
            int n = 7;
            double[][] pos = new double[n + 1][2];
            for (double[] p : pos) {
                p[0] = rnd.nextDouble() * 60;
                p[1] = rnd.nextDouble() * 60;
            }
            double[] f = new double[n];
            double[][] b = new double[n][n];
            double[] ww = new double[n];
            double[] wk = new double[n];
            for (int i = 0; i < n; i++) {
                f[i] = d(pos[n], pos[i]);
                ww[i] = 1 + rnd.nextInt(6);
                wk[i] = rnd.nextInt(3) == 0 ? 0 : rnd.nextDouble() * 20;
                for (int j = 0; j < n; j++) {
                    b[i][j] = i == j ? 0 : d(pos[i], pos[j]) + rnd.nextDouble() * 3;
                }
            }
            Object r = plan(f, b, ww, wk);
            boolean exact = (Boolean) ModUnderTest.call(r, "exact", new Class<?>[]{}, new Object[]{});
            exactRuns += exact ? 1 : 0;
            double got = evaluate(order(r), f, b, ww, wk)[0];
            double brute = bruteBest(f, b, ww, wk);
            if (got > brute + 1e-6) {
                worse++;
                if (worse <= 3) {
                    println(String.format(Locale.US, "random floor %d: plan %.3f, the best order %.3f", t, got, brute));
                }
            }
        }
        println("random 7-room floors: " + exactRuns + "/200 proved exact, " + worse + " worse than the best of all orders");
        check(problems, "random floors: every plan is the cheapest order", worse == 0);
        check(problems, "random floors: every plan proved exact in its budget", exactRuns == 200);
    }

    private static double bruteBest(double[] f, double[][] b, double[] w, double[] wk) {
        int n = f.length;
        int[] p = new int[n];
        for (int i = 0; i < n; i++) {
            p[i] = i;
        }
        double best = Double.MAX_VALUE;
        do {
            best = Math.min(best, localCost(p, f, b, w, wk));
        } while (nextPermutation(p));
        return best;
    }

    /** The same sum(w * done) the mod's evaluate computes, here so 5040 orders a floor need no reflection. */
    private static double localCost(int[] order, double[] f, double[][] b, double[] w, double[] wk) {
        double t = 0;
        double cost = 0;
        int prev = -1;
        for (int i : order) {
            t += (prev < 0 ? f[i] : b[prev][i]) + wk[i];
            cost += w[i] * t;
            prev = i;
        }
        return cost;
    }

    private static boolean nextPermutation(int[] a) {
        int i = a.length - 2;
        while (i >= 0 && a[i] >= a[i + 1]) {
            i--;
        }
        if (i < 0) {
            return false;
        }
        int j = a.length - 1;
        while (a[j] <= a[i]) {
            j--;
        }
        int t = a[i];
        a[i] = a[j];
        a[j] = t;
        for (int l = i + 1, r = a.length - 1; l < r; l++, r--) {
            t = a[l];
            a[l] = a[r];
            a[r] = t;
        }
        return true;
    }

    private static Object plan(double[] f, double[][] b, double[] w, double[] wk) {
        return ModUnderTest.staticCall(TOUR, "plan",
                new Class<?>[]{double[].class, double[][].class, double[].class, double[].class, long.class},
                new Object[]{f, b, w, wk, 2_000_000_000L});
    }

    private static int[] order(Object res) {
        return (int[]) ModUnderTest.call(res, "order", new Class<?>[]{}, new Object[]{});
    }

    private static double[] evaluate(int[] order, double[] f, double[][] b, double[] w, double[] wk) {
        return (double[]) ModUnderTest.staticCall(TOUR, "evaluate",
                new Class<?>[]{int[].class, double[].class, double[][].class, double[].class, double[].class},
                new Object[]{order, f, b, w, wk});
    }

    // ================================================================================================ standing plan

    private static void coverCases(List<String> problems) {
        // Mobs at x = 0, 9, 18 on a line; candidate blocks every block from -5 to 23 on the same line; a block reaches a
        // mob within 5 (the Hyperion's 6-block blast less the 1-block margin).
        double[][] mobs = {{0, 0, 0}, {9, 0, 0}, {18, 0, 0}};
        List<double[]> spots = new ArrayList<>();
        List<Long> covers = new ArrayList<>();
        for (int x = -5; x <= 23; x++) {
            double[] s = {x, 0, 0};
            long m = 0;
            for (int j = 0; j < mobs.length; j++) {
                if (d3(s, mobs[j]) <= 5.0) {
                    m |= 1L << j;
                }
            }
            if (m != 0) {
                spots.add(s);
                covers.add(m);
            }
        }
        long[] c = covers.stream().mapToLong(Long::longValue).toArray();
        double[][] sp = spots.toArray(new double[0][]);
        double[] from = {-30, 0, 0};
        Object plan = cover(c, 3, sp, from);
        int count = (Integer) ModUnderTest.call(plan, "count", new Class<?>[]{}, new Object[]{});
        int[] chosen = (int[]) ModUnderTest.call(plan, "spots", new Class<?>[]{}, new Object[]{});
        int old = (Integer) ModUnderTest.staticCall(COVER, "nearestMobFirst",
                new Class<?>[]{long[].class, int.class, double[][].class, double[][].class, double[].class},
                new Object[]{c, 3, sp, mobs, from});
        List<String> at = new ArrayList<>();
        for (int i : chosen) {
            at.add(String.valueOf((int) sp[i][0]));
        }
        println("three mobs in a line, 9 apart: plan " + count + " block(s) at x " + at + ", nearest mob first " + old);
        check(problems, "line of three: two blocks", count == 2);
        check(problems, "line of three: nearest-mob-first takes three", old == 3);
        check(problems, "line of three: the first block reaches the first two mobs",
                chosen.length > 0 && sp[chosen[0]][0] >= 4 && sp[chosen[0]][0] <= 5);

        // Random rooms: count == brute-force minimum, never more than nearest-mob-first.
        Random rnd = new Random(560);
        int wrong = 0;
        int better = 0;
        for (int t = 0; t < 200; t++) {
            int n = 3 + rnd.nextInt(5);
            double[][] mb = new double[n][];
            for (int j = 0; j < n; j++) {
                mb[j] = new double[]{rnd.nextDouble() * 30, 0, rnd.nextDouble() * 30};
            }
            List<double[]> ss = new ArrayList<>();
            List<Long> cc = new ArrayList<>();
            for (int x = -5; x <= 35; x += 2) {
                for (int z = -5; z <= 35; z += 2) {
                    double[] s = {x, 0, z};
                    long m = 0;
                    for (int j = 0; j < n; j++) {
                        if (d3(s, mb[j]) <= 5.0) {
                            m |= 1L << j;
                        }
                    }
                    if (m != 0 && rnd.nextInt(3) != 0) {
                        ss.add(s);
                        cc.add(m);
                    }
                }
            }
            long[] carr = cc.stream().mapToLong(Long::longValue).toArray();
            long all = (1L << n) - 1;
            long reach = 0;
            for (long v : carr) {
                reach |= v;
            }
            if (reach != all) {
                continue;
            }
            double[][] sarr = ss.toArray(new double[0][]);
            Object p = cover(carr, n, sarr, new double[]{-10, 0, -10});
            int got = (Integer) ModUnderTest.call(p, "count", new Class<?>[]{}, new Object[]{});
            int min = bruteCover(carr, all);
            int nm = (Integer) ModUnderTest.staticCall(COVER, "nearestMobFirst",
                    new Class<?>[]{long[].class, int.class, double[][].class, double[][].class, double[].class},
                    new Object[]{carr, n, sarr, mb, new double[]{-10, 0, -10}});
            if (got != min || got > nm) {
                wrong++;
                if (wrong <= 3) {
                    println("random room " + t + ": plan " + got + ", minimum " + min + ", nearest mob first " + nm);
                }
            }
            better += got < nm ? 1 : 0;
        }
        println("random rooms: " + wrong + " plan(s) not the minimum; fewer blocks than nearest-mob-first in " + better);
        check(problems, "random rooms: every plan is a minimum cover", wrong == 0);
    }

    private static int bruteCover(long[] covers, long all) {
        // Distinct sets only, then the smallest k whose some combination covers all.
        List<Long> sets = new ArrayList<>();
        for (long c : covers) {
            if (!sets.contains(c)) {
                sets.add(c);
            }
        }
        for (int k = 1; k <= Long.bitCount(all); k++) {
            if (anyCover(sets, 0, k, 0L, all)) {
                return k;
            }
        }
        return -1;
    }

    private static boolean anyCover(List<Long> sets, int from, int k, long got, long all) {
        if (got == all) {
            return true;
        }
        if (k == 0) {
            return false;
        }
        for (int i = from; i < sets.size(); i++) {
            if (anyCover(sets, i + 1, k - 1, got | sets.get(i), all)) {
                return true;
            }
        }
        return false;
    }

    private static Object cover(long[] c, int n, double[][] spots, double[] from) {
        return ModUnderTest.staticCall(COVER, "plan",
                new Class<?>[]{long[].class, int.class, double[][].class, double[].class, long.class},
                new Object[]{c, n, spots, from, 2_000_000_000L});
    }

    // ================================================================================================ helpers

    private static double d(double[] a, double[] b) {
        return Math.hypot(a[0] - b[0], a[1] - b[1]);
    }

    private static double d3(double[] a, double[] b) {
        double dx = a[0] - b[0];
        double dy = a[1] - b[1];
        double dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static void check(List<String> problems, String what, boolean ok) {
        println(what + ": " + (ok ? "ok" : "NO"));
        if (!ok) {
            problems.add(what);
        }
    }

    private static void println(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}
