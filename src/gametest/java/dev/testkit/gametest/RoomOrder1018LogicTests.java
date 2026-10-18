package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * 1018-logic-room-order (mod sim-secrets-1018): Auto Secret's "Fewest Warps" room order, with made-up floors - no world.
 *
 * <p>killer560, 2026-10-18: "For auto secret make two modes. One is largest to smallest secret rooms and the other is as
 * few etherwarps possible pathfinding the whole dungeon no room order preference." {@code TourPlanner.shortest} is the
 * second: the order through every room whose trips add up to the least travel. Checked against brute force on 200 random
 * 7-room floors (all 5040 orders), and shown to be a different rule from the value-weighted {@code TourPlanner.plan} (on
 * some of those floors the plan's order travels further). On main there is no {@code shortest}.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class RoomOrder1018LogicTests implements FabricClientGameTest {

    private static final String NAME = "1018-logic-room-order";
    private static final String TOUR = "com.killer560.hub.autosecret.TourPlanner";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        List<String> problems = new ArrayList<>();
        ctx.runOnClient(mc -> {
            try {
                cases(problems);
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

    private static void cases(List<String> problems) {
        // His 2026-10-15 example: room 0 across the map (20 ticks from everywhere), 1 and 2 next door (5 ticks). Fewest
        // warps takes the two next door, then crosses once: 30 ticks of travel, where crossing first is 45.
        double[] from = {20, 5, 5};
        double[][] between = {{0, 20, 20}, {20, 0, 5}, {20, 5, 0}};
        int[] o = shortest(from, between);
        double t = travel(o, from, between);
        println("his example: fewest warps " + Arrays.toString(o) + ", travel " + t);
        check(problems, "his example: the room across the map last, 30 ticks of travel", o[2] == 0 && t == 30);

        Random rnd = new Random(1018);
        int exact = 0;
        int planFurther = 0;
        for (int k = 0; k < 200; k++) {
            int n = 7;
            double[][] pos = new double[n + 1][2];
            for (double[] p : pos) {
                p[0] = rnd.nextDouble() * 60;
                p[1] = rnd.nextDouble() * 60;
            }
            double[] f = new double[n];
            double[][] b = new double[n][n];
            double[] w = new double[n];
            double[] wk = new double[n];
            for (int i = 0; i < n; i++) {
                f[i] = d(pos[n], pos[i]);
                w[i] = 1 + rnd.nextInt(7);
                for (int j = 0; j < n; j++) {
                    b[i][j] = i == j ? 0 : d(pos[i], pos[j]);
                }
            }
            double best = bruteTravel(f, b);
            int[] order = shortest(f, b);
            double got = travel(order, f, b);
            if (Math.abs(got - best) < 1e-6) {
                exact++;
            }
            Object plan = ModUnderTest.staticCall(TOUR, "plan",
                    new Class<?>[]{double[].class, double[][].class, double[].class, double[].class, long.class},
                    new Object[]{f, b, w, wk, 2_000_000_000L});
            int[] planOrder = (int[]) ModUnderTest.call(plan, "order", new Class<?>[]{}, new Object[]{});
            if (travel(planOrder, f, b) > got + 1e-6) {
                planFurther++;
            }
        }
        println(String.format(Locale.US, "200 random 7-room floors: fewest warps equals the brute-force least travel on %d;"
                + " the value-weighted plan travels further on %d", exact, planFurther));
        check(problems, "fewest warps is the least travel of all 5040 orders on every floor", exact == 200);
        check(problems, "fewest warps is a different order from the value-weighted plan on some floors", planFurther > 0);
    }

    private static int[] shortest(double[] f, double[][] b) {
        Object res = ModUnderTest.staticCall(TOUR, "shortest", new Class<?>[]{double[].class, double[][].class, long.class},
                new Object[]{f, b, 2_000_000_000L});
        return (int[]) ModUnderTest.call(res, "order", new Class<?>[]{}, new Object[]{});
    }

    private static double travel(int[] order, double[] f, double[][] b) {
        double t = 0;
        int prev = -1;
        for (int i : order) {
            t += prev < 0 ? f[i] : b[prev][i];
            prev = i;
        }
        return t;
    }

    private static double bruteTravel(double[] f, double[][] b) {
        int n = f.length;
        int[] a = new int[n];
        for (int i = 0; i < n; i++) {
            a[i] = i;
        }
        double best = Double.MAX_VALUE;
        do {
            best = Math.min(best, travel(a, f, b));
        } while (nextPermutation(a));
        return best;
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
        int tmp = a[i];
        a[i] = a[j];
        a[j] = tmp;
        for (int l = i + 1, r = a.length - 1; l < r; l++, r--) {
            tmp = a[l];
            a[l] = a[r];
            a[r] = tmp;
        }
        return true;
    }

    private static double d(double[] a, double[] b) {
        return Math.hypot(a[0] - b[0], a[1] - b[1]);
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
