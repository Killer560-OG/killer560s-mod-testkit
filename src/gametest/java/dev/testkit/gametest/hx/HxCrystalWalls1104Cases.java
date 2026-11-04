package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Locale;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Crystal Hollows Walls (mod chwalls-1104, owner 2026-11-04: "add section walls"):
 * <ul>
 *   <li>1104-chwalls-edges: in a fake Hollows with the switch ON, the drawn walls (what the render pass handed to the filled
 *       box draw, read back as lines) sit at the zone edges: standing near X 202 the Jungle slab is at x 202.0-202.1 in the
 *       Jungle color; standing at the 513 line the Jungle and Mithril slabs flank it; at the center only the Nucleus and the
 *       Magma top are within Near Only; Near Only 0 draws the outer walls from the center; the switch OFF and the hub draw nothing
 *       and do not move the frame counter.</li>
 *   <li>1104-chwalls-pixels: a screenshot looking at the X 202 wall from 3 blocks (opacity 100) is green at the center, and the
 *       same view in the hub is not.</li>
 * </ul>
 */
final class HxCrystalWalls1104Cases {

    private static final String CFG = "miningqol.MiningQolConfig";
    private static final String W = "miningqol.CrystalHollowsWalls";
    private static final String ZONE = "miningqol.CrystalHollowsWalls$Zone";
    private static final String STATE = "miningqol.MiningState";

    private HxCrystalWalls1104Cases() {
    }

    static void register(Session s) {
        s.test("1104-chwalls-edges", HxCrystalWalls1104Cases::edges);
        s.test("1104-chwalls-pixels", HxCrystalWalls1104Cases::pixels);
    }

    private static void hollows(Session c) {
        c.hx().sidebar("SKYBLOCK", "Late Winter 3rd", "⏣ Jungle", "Purse: 0");
        c.hx().tab(List.of("Area: Crystal Hollows", "Profile: Test", ""), null, null);
        c.waitUntil("Crystal Hollows", mc -> "CRYSTAL_HOLLOWS".equals(Mod.staticCall(STATE, "island")), 100);
    }

    private static void at(Session c, double x, double y, double z, float yaw) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z), by = (int) Math.floor(y) - 1;
        // A floor under him: a server teleport into mid-air made Grim flag a few Simulation lines, which is the test's doing.
        c.server().command("forceload add " + bx + " " + bz);
        c.ctx().waitTicks(10);
        c.server().command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:glass", bx - 2, by, bz - 2, bx + 2, by, bz + 2));
        c.server().command(String.format(Locale.ROOT, "tp @p %.2f %.2f %.2f %.1f 0", x, y, z, yaw));
        c.waitUntil("at " + x + "," + z, mc -> Math.abs(mc.player.getX() - x) < 1.0 && Math.abs(mc.player.getZ() - z) < 1.0, 100);
    }

    @SuppressWarnings("unchecked")
    private static List<String> drawn(Session c) {
        return (List<String>) c.onClient(mc -> Mod.staticCall(W, "drawnLines"));
    }

    private static long frames(Session c) {
        return c.onClient(mc -> (Long) Mod.staticCall(W, "framesDrawn"));
    }

    private static double[] row(String line) {
        String[] p = line.split(" ");
        double[] d = new double[p.length - 1];
        for (int i = 1; i < p.length; i++) {
            d[i - 1] = Double.parseDouble(p[i]);
        }
        return d;
    }

    /** The first drawn line of a zone satisfying the predicate, or null. */
    private static double[] find(List<String> lines, String zone, java.util.function.Predicate<double[]> ok) {
        for (String l : lines) {
            if (l.startsWith(zone + " ")) {
                double[] r = row(l);
                if (ok.test(r)) {
                    return r;
                }
            }
        }
        return null;
    }

    private static void edges(Session c) {
        Object cfg = c.onClient(mc -> Mod.cfg(CFG));
        boolean old = c.onClient(mc -> (Boolean) Mod.call(cfg, "isChWallsRaw"));
        try {
            c.ctx().runOnClient(mc -> Mod.call(cfg, "setChWalls", true));
            hollows(c);
            c.server().command("gamemode creative @p");
            // 1) near the west edge: the Jungle slab is at 202.0-202.1, green (0x55FF55), alpha .25.
            at(c, 210, 100, 300, 90f);
            c.waitUntil("walls drawn near the west edge", mc -> !drawn(c).isEmpty(), 100);
            List<String> d = drawn(c);
            double[] jw = find(d, "JUNGLE", r -> Math.abs(r[0] - 202.0) < 1e-6 && Math.abs(r[3] - 202.1) < 1e-6);
            c.check(jw != null, "no Jungle slab at X 202.0-202.1; drawn: " + d);
            c.check(Math.abs(jw[6] - 0x55 / 255.0) < 0.01 && Math.abs(jw[7] - 1.0) < 0.01 && Math.abs(jw[9] - 0.25) < 0.01,
                    "the Jungle wall is not the default green at 25%: " + java.util.Arrays.toString(jw));
            c.check(find(d, "JUNGLE", r -> Math.abs(r[1] - 64.0) < 1e-6) != null,
                    "the Jungle wall does not start at Y 64");
            c.check(find(d, "MITHRIL", r -> true) == null && find(d, "PRECURSOR", r -> true) == null,
                    "far zones drew near the west edge: " + d);
            // 2) on the quadrant line: Jungle on the west side of X 513, Mithril on the east side.
            at(c, 520, 100, 300, 90f);
            c.waitUntil("walls drawn at the quadrant line", mc -> find(drawn(c), "MITHRIL",
                    r -> Math.abs(r[0] - 513.0) < 1e-6) != null, 100);
            d = drawn(c);
            c.check(find(d, "JUNGLE", r -> Math.abs(r[3] - 513.0) < 1e-6 && Math.abs(r[0] - 512.9) < 1e-6) != null,
                    "no Jungle slab ending at X 513.0: " + d);
            c.check(find(d, "MITHRIL", r -> Math.abs(r[0] - 513.0) < 1e-6 && Math.abs(r[3] - 513.1) < 1e-6) != null,
                    "no Mithril slab starting at X 513.0: " + d);
            // 3) center, Near Only 96: no outer wall (300 blocks away), the Magma top (Y 64) and the Nucleus are there.
            at(c, 513, 100, 513, 0f);
            c.waitUntil("Nucleus walls drawn at the center", mc -> find(drawn(c), "NUCLEUS", r -> true) != null, 100);
            d = drawn(c);
            c.check(find(d, "MAGMA", r -> Math.abs(r[4] - 64.0) < 1e-6 && Math.abs(r[1] - 63.9) < 1e-6) != null,
                    "no Magma top at Y 63.9-64: " + d);
            c.check(find(d, "JUNGLE", r -> Math.abs(r[0] - 202.0) < 1e-6) == null, "the X 202 wall drew from the center with Near Only 96");
            // 4) Near Only Always on the Jungle: the whole wall (z 202..513) from the center.
            Object jungle = c.onClient(mc -> Mod.enumValue(ZONE, "JUNGLE"));
            int oldNear = c.onClient(mc -> ((Number) Mod.call(cfg, "getChWallNear", jungle)).intValue());
            c.ctx().runOnClient(mc -> Mod.call(cfg, "setChWallNear", jungle, 0));
            c.waitUntil("Jungle outer wall with Near Only Always", mc -> find(drawn(c), "JUNGLE",
                    r -> Math.abs(r[0] - 202.0) < 1e-6 && Math.abs(r[2] - 202.0) < 1e-6 && Math.abs(r[5] - 513.0) < 1e-6) != null, 100);
            c.ctx().runOnClient(mc -> Mod.call(cfg, "setChWallNear", jungle, oldNear));
            // 5) the switch OFF and the hub: nothing drawn, frames still.
            c.ctx().runOnClient(mc -> Mod.call(cfg, "setChWalls", false));
            c.waitUntil("nothing drawn with the switch OFF", mc -> drawn(c).isEmpty(), 60);
            c.ctx().runOnClient(mc -> Mod.call(cfg, "setChWalls", true));
            c.waitUntil("walls back with the switch ON", mc -> !drawn(c).isEmpty(), 60);
            hub(c);
            c.waitUntil("nothing drawn in the hub", mc -> drawn(c).isEmpty(), 60);
            long f0 = frames(c);
            c.ctx().waitTicks(20);
            c.check(frames(c) == f0, "walls drew outside the Crystal Hollows");
            c.note("walls at X 202.0 (Jungle, green 25%), the 513 line (Jungle|Mithril), Magma top Y 64, Nucleus; Near Only clips; off and hub draw none");
        } finally {
            c.ctx().runOnClient(mc -> Mod.call(cfg, "setChWalls", old));
            
            hub(c);
        }
    }

    private static void pixels(Session c) {
        Object cfg = c.onClient(mc -> Mod.cfg(CFG));
        boolean old = c.onClient(mc -> (Boolean) Mod.call(cfg, "isChWallsRaw"));
        Object jungle = c.onClient(mc -> Mod.enumValue(ZONE, "JUNGLE"));
        int oldOp = c.onClient(mc -> ((Number) Mod.call(cfg, "getChWallOpacity", jungle)).intValue());
        try {
            c.ctx().runOnClient(mc -> {
                Mod.call(cfg, "setChWalls", true);
                Mod.call(cfg, "setChWallOpacity", jungle, 100);
            });
            hollows(c);
            c.server().command("gamemode creative @p");
            at(c, 205, 100, 300, 90f);
            c.waitUntil("the wall drawn", mc -> !drawn(c).isEmpty(), 100);
            c.ctx().waitTicks(5);
            int in = greenAtCenter(c, "chwalls-hollows");
            hub(c);
            at(c, 205, 100, 300, 90f);
            c.ctx().waitTicks(10);
            int out = greenAtCenter(c, "chwalls-hub");
            c.check(in >= 80, "the center pixel is not wall green in the Hollows (greenness " + in + ")");
            c.check(out < 40, "the center pixel is green in the hub (greenness " + out + ")");
            c.note("center greenness " + in + " in the Hollows (wall 3 blocks ahead), " + out + " in the hub");
        } finally {
            c.ctx().runOnClient(mc -> {
                Mod.call(cfg, "setChWalls", old);
                Mod.call(cfg, "setChWallOpacity", jungle, oldOp);
            });
            
            hub(c);
        }
    }

    /** Greenness (G minus the larger of R and B), averaged over a 9x9 patch at the center of a screenshot. */
    private static int greenAtCenter(Session c, String name) {
        try {
            java.nio.file.Path shot = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(name));
            dev.testkit.harness.Report.screenshot(c.name(), shot);
            BufferedImage img = javax.imageio.ImageIO.read(shot.toFile());
            int cx = img.getWidth() / 2 - 60, cy = img.getHeight() / 2 + 40, sum = 0, n = 0;
            for (int dy = -4; dy <= 4; dy++) {
                for (int dx = -4; dx <= 4; dx++) {
                    int a = img.getRGB(cx + dx, cy + dy);
                    sum += ((a >> 8) & 255) - Math.max((a >> 16) & 255, a & 255);
                    n++;
                }
            }
            return sum / n;
        } catch (java.io.IOException e) {
            throw new AssertionError("screenshot unreadable", e);
        }
    }
}
