package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;
import net.minecraft.core.BlockPos;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * 1122-invisibug-* (mod invisibug-1122): Invisibug Highlight reads ONE crit particle every 5 ticks circling a spot (recorded
 * in his Galatea scan 11-23, tick 12189-12340: two circles, radius 0.5). The recorded points are replayed through the server's
 * particle command, shifted to the player: two boxes at the circle centers; hit crits, a lone scattered crit, a straight
 * line of crits and the same circle off Galatea give none; a circle that stops expires. Asserts boxes were drawn (boxCount).
 */
final class HxInvisibug1122Cases {

    private static final String K = "foraging.InvisibugHighlight";

    private HxInvisibug1122Cases() {
    }

    static void register(Session s) {
        s.test("1122-invisibug-body", HxInvisibug1122Cases::body);
        s.test("1122-invisibug-marker", HxInvisibug1122Cases::marker);
    }

    private static void island(Session c, String area, String graph) {
        sidebar(c, "template", "hub");
        tab(c, "template", "area", "area", area);
        c.waitUntil("IslandDetector reads " + graph, mc -> graph.equals(Mod.staticCall("pathfinding.IslandDetector",
                "graphIsland")), 100);
    }

    private static Settings on(Session c, boolean enabled) {
        Object cfg = c.onClient(mc -> Mod.field(K, "CFG"));
        boolean was = c.onClient(mc -> (Boolean) Mod.call(cfg, "isEnabledRaw"));
        return new Settings(c).custom(() -> Mod.call(cfg, "set", "enabled", enabled),
                () -> Mod.call(cfg, "set", "enabled", was));
    }

    // Recorded 11-23 (killer560s-mod-logs/invisibug-1123-recording.md): x,z per point at y 117.60 (A) and 118.90 (B).
    private static final double[] A = {-630.45, 83.07, -630.32, 83.18, -630.23, 83.33, -630.20, 83.50, -630.23, 83.67,
            -630.32, 83.82, -630.45, 83.93, -630.61, 83.99, -630.79, 83.99, -630.95, 83.93, -631.08, 83.82, -631.17, 83.67, -631.20, 83.50};
    private static final double[] B = {-592.62, 88.01, -592.78, 88.09, -592.95, 88.10, -593.12, 88.05, -593.26, 87.95,
            -593.36, 87.80, -593.40, 87.63, -593.38, 87.46, -593.30, 87.31, -593.18, 87.19, -593.02, 87.11, -592.85, 87.10,
            -592.68, 87.15, -592.54, 87.25, -592.44, 87.40, -592.40, 87.57, -592.42, 87.74, -592.50, 87.89};
    private static final double MID_X = -611.8, MID_Y = 118.25, MID_Z = 85.57;

    private static int count(Session c, String m) {
        return c.onClient(mc -> ((Number) Mod.staticCall(K, m)).intValue());
    }

    private static double[][] centers(Session c) {
        return c.onClient(mc -> (double[][]) Mod.staticCall(K, "boxCenters"));
    }

    /** Sends point i of a circle (shifted onto the player) as the server does: crit, count 1, no spread. */
    private static void send(Session c, double[] pts, int i, double y, BlockPos p) {
        c.server().command(String.format(java.util.Locale.ROOT, "particle crit %.2f %.2f %.2f 0 0 0 0 1 force",
                pts[2 * i] - MID_X + p.getX() + 0.5, y - MID_Y + p.getY() + 1, pts[2 * i + 1] - MID_Z + p.getZ() + 0.5));
    }

    /** Both recorded circles at once, one point each every 5 ticks. */
    private static void replay(Session c, BlockPos p) {
        for (int i = 0; i < B.length / 2; i++) {
            send(c, B, i, 118.90, p);
            if (i < A.length / 2) {
                send(c, A, i, 117.60, p);
            }
            c.ctx().waitTicks(5);
        }
    }

    private static boolean near(double[][] got, double x, double z, BlockPos p) {
        for (double[] g : got) {
            if (Math.hypot(g[0] - (x - MID_X + p.getX() + 0.5), g[2] - (z - MID_Z + p.getZ() + 0.5)) < 0.2) {
                return true;
            }
        }
        return false;
    }

    private static void circles(Session c) {
        BlockPos p = c.onClient(mc -> mc.player.blockPosition());
        try (Settings set = on(c, true)) {
            island(c, "Galatea", "GALATEA");
            replay(c, p);
            c.check(count(c, "boxCount") == 2, "boxes drawn: " + count(c, "boxCount") + " (want 2, one per recorded circle)");
            double[][] got = centers(c);
            c.check(near(got, -592.90, 87.60, p), "no box at the center of circle B");
            c.check(near(got, -630.70, 83.53, p), "no box at the center of circle A");
            c.waitUntil("both bugs expire after their particles stop", mc -> count(c, "boxCount") == 0, 100);
            island(c, "Hub", "HUB");
            replay(c, p);
            c.check(count(c, "boxCount") == 0, "boxes off Galatea: " + count(c, "boxCount"));
            island(c, "Galatea", "GALATEA");
            try (Settings off = on(c, false)) {
                replay(c, p);
                c.check(count(c, "boxCount") == 0, "boxes with the switch off: " + count(c, "boxCount"));
            }
        }
    }

    private static void lookalikes(Session c) {
        BlockPos p = c.onClient(mc -> mc.player.blockPosition());
        String at = p.getX() + 3.5 + " " + (p.getY() + 1) + " " + (p.getZ() + 0.5);
        try (Settings set = on(c, true)) {
            island(c, "Galatea", "GALATEA");
            for (int i = 0; i < 12; i++) {
                // a melee hit: many crits at once, scattered
                c.server().command("particle crit " + at + " 0.3 0.4 0.3 0.1 12 force");
                // a lone crit at a random height each time
                c.server().command("particle crit " + (p.getX() + 2.5 + 0.1 * i) + " " + (p.getY() + 1 + 0.37 * i) + " "
                        + (p.getZ() + 0.5) + " 0 0 0 0 1 force");
                c.ctx().waitTicks(5);
            }
            c.check(count(c, "boxCount") == 0, "hit crits or scattered crits were boxed: " + count(c, "boxCount"));
            for (int i = 0; i < 12; i++) {
                // steady count-1 crits at one height, 0.15 apart in a straight line: a step like the circle's, but no circle
                c.server().command("particle crit " + (p.getX() + 2.5 + 0.15 * i) + " " + (p.getY() + 1) + " "
                        + (p.getZ() + 0.5) + " 0 0 0 0 1 force");
                c.ctx().waitTicks(5);
            }
            c.check(count(c, "boxCount") == 0, "a straight line of crits was boxed: " + count(c, "boxCount"));
            c.server().command("particle flame " + at + " 0 0 0 0 1 force");
            c.ctx().waitTicks(6);
            c.check(count(c, "boxCount") == 0, "a flame particle was boxed");
        }
    }
}
