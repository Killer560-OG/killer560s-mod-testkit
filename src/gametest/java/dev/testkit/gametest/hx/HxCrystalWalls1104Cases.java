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
        s.test("1104-chwalls-nucleus", HxCrystalWalls1104Cases::nucleus);
        s.test("1105-chwalls-structure", HxCrystalWalls1104Cases::structure);
        s.test("1105-chwalls-nearest", HxCrystalWalls1104Cases::nearest);
    }

    /**
     * Nucleus (chnucwall-1104, owner: "the area walls should not go into the nucleus"): with every zone on Near Only Always, no
     * drawn slab other than the Nucleus's own reaches inside the box (X 462..564, Z 461..565, Y 64..189), the quadrants' inner walls end at
     * 465 / start at 562 and still reach the Hollows edge, and the Nucleus is white by default and from an old pink save.
     */
    private static void nucleus(Session c) {
        Object cfg = c.onClient(mc -> Mod.cfg(CFG));
        boolean old = c.onClient(mc -> (Boolean) Mod.call(cfg, "isChWallsRaw"));
        String[] names = {"JUNGLE", "MITHRIL", "GOBLIN", "PRECURSOR", "MAGMA", "NUCLEUS"};
        Object[] zones = c.onClient(mc -> {
            Object[] z = new Object[names.length];
            for (int i = 0; i < z.length; i++) {
                z[i] = Mod.enumValue(ZONE, names[i]);
            }
            return z;
        });
        int n = zones.length;
        int[] nears = new int[n];
        int nuc = n - 1;
        for (int i = 0; i < n; i++) {
            final int k = i;
            nears[i] = c.onClient(mc -> ((Number) Mod.call(cfg, "getChWallNear", zones[k])).intValue());
        }
        int oldColor = c.onClient(mc -> ((Number) Mod.call(cfg, "getChWallColor", zones[nuc])).intValue());
        try {
            c.ctx().runOnClient(mc -> {
                Mod.call(cfg, "setChWalls", true);
                for (Object z : zones) {
                    Mod.call(cfg, "setChWallNear", z, 0);
                }
            });
            hollows(c);
            c.server().command("gamemode creative @p");
            at(c, 513, 100, 513, 0f);
            c.waitUntil("Nucleus walls drawn", mc -> find(drawn(c), "NUCLEUS", r -> true) != null, 100);
            List<String> d = drawn(c);
            int zoneWalls = 0;
            for (String l : d) {
                if (l.startsWith("NUCLEUS ")) {
                    continue;
                }
                zoneWalls++;
                double[] r = row(l);
                boolean inside = r[0] < 564 && r[3] > 462 && r[2] < 565 && r[5] > 461 && r[4] > 64 && r[1] < 189;
                c.check(!inside, "a zone wall reaches inside the Nucleus box: " + l);
            }
            c.check(zoneWalls >= 16, "too few zone walls drawn to prove anything: " + d);
            // the inner walls still run from the Hollows edge to the Nucleus
            c.check(find(d, "JUNGLE", r -> Math.abs(r[3] - 513.0) < 1e-6 && Math.abs(r[2] - 202.0) < 1e-6
                    && Math.abs(r[5] - 461.0) < 1e-6) != null, "Jungle X 513 wall is not Z 202..461: " + d);
            c.check(find(d, "PRECURSOR", r -> Math.abs(r[0] - 513.0) < 1e-6 && Math.abs(r[2] - 565.0) < 1e-6
                    && Math.abs(r[5] - 824.0) < 1e-6) != null, "Precursor X 513 wall is not Z 565..824: " + d);
            c.check(find(d, "GOBLIN", r -> Math.abs(r[3] - 462.0) < 1e-6 && Math.abs(r[0] - 202.0) < 1e-6
                    && Math.abs(r[5] - 513.1) < 1e-6) != null, "Goblin Z 513 wall is not X 202..462: " + d);
            // white by default, and a saved old pink moves to white
            double[] w = find(d, "NUCLEUS", r -> true);
            c.check(w[6] > 0.99 && w[7] > 0.99 && w[8] > 0.99, "the Nucleus walls are not white: " + java.util.Arrays.toString(w));
            c.ctx().runOnClient(mc -> Mod.call(cfg, "setChWallColor", zones[nuc], 0xFF55FF));
            c.ctx().runOnClient(mc -> {
                Mod.call(cfg, "save");
                Mod.staticCall("miningqol.MiningQolConfig", "load");
            });
            Object fresh = c.onClient(mc -> Mod.cfg(CFG));
            int migrated = c.onClient(mc -> ((Number) Mod.call(fresh, "getChWallColor", zones[nuc])).intValue());
            c.check(migrated == 0xFFFFFF, "a saved old pink Nucleus color did not move to white: " + Integer.toHexString(migrated));
            c.ctx().runOnClient(mc -> Mod.call(fresh, "setChWallColor", zones[nuc], 0xFF00AA));
            c.ctx().runOnClient(mc -> {
                Mod.call(fresh, "save");
                Mod.staticCall("miningqol.MiningQolConfig", "load");
            });
            int kept = c.onClient(mc -> ((Number) Mod.call(Mod.cfg(CFG), "getChWallColor", zones[nuc])).intValue());
            c.check(kept == 0xFF00AA, "a picked Nucleus color was changed on load: " + Integer.toHexString(kept));
            c.note("no zone wall inside the Nucleus box (" + zoneWalls + " zone slabs checked), inner walls end at 462/461 and 564/565, Nucleus white");
        } finally {
            c.ctx().runOnClient(mc -> {
                Object cur = Mod.cfg(CFG);
                Mod.call(cur, "setChWalls", old);
                for (int i = 0; i < n; i++) {
                    Mod.call(cur, "setChWallNear", zones[i], nears[i]);
                }
                Mod.call(cur, "setChWallColor", zones[nuc], oldColor);
                Mod.call(cur, "save");
            });
            hub(c);
        }
    }

    /** Hide Walls In Structures (nucwall-1105): walls drawn outside a structure, hidden inside one with the switch ON, drawn inside with it OFF. */
    private static void structure(Session c) {
        Object cfg = c.onClient(mc -> Mod.cfg(CFG));
        boolean old = c.onClient(mc -> (Boolean) Mod.call(cfg, "isChWallsRaw"));
        boolean oldHide = c.onClient(mc -> (Boolean) Mod.call(cfg, "isChWallsHideInStructures"));
        try {
            c.ctx().runOnClient(mc -> {
                Mod.call(cfg, "setChWalls", true);
                Mod.call(cfg, "setChWallsHideInStructures", true);
            });
            hollows(c);
            c.server().command("gamemode creative @p");
            at(c, 210, 100, 300, 90f);
            c.waitUntil("walls drawn outside a structure", mc -> !drawn(c).isEmpty(), 100);
            c.check(!(Boolean) c.onClient(mc -> Mod.staticCall(W, "inStructure")), "a plain Jungle biome read as a structure");
            c.hx().sidebar("SKYBLOCK", "Late Winter 3rd", "⏣ Mines of Divan", "Purse: 0");
            c.waitUntil("Mines of Divan read", mc -> (Boolean) Mod.staticCall(W, "inStructure"), 100);
            c.waitUntil("walls hidden inside the structure with the switch ON", mc -> drawn(c).isEmpty(), 100);
            c.ctx().runOnClient(mc -> Mod.call(cfg, "setChWallsHideInStructures", false));
            c.waitUntil("walls drawn inside the structure with the switch OFF", mc -> !drawn(c).isEmpty(), 100);
            c.ctx().runOnClient(mc -> Mod.call(cfg, "setChWallsHideInStructures", true));
            c.waitUntil("hidden again with the switch ON", mc -> drawn(c).isEmpty(), 100);
            c.hx().sidebar("SKYBLOCK", "Late Winter 3rd", "⏣ Jungle", "Purse: 0");
            c.waitUntil("walls back outside the structure", mc -> !drawn(c).isEmpty(), 100);
            c.note("walls drawn in the Jungle, hidden in Mines of Divan with Hide ON, drawn there with it OFF");
        } finally {
            c.ctx().runOnClient(mc -> {
                Mod.call(cfg, "setChWalls", old);
                Mod.call(cfg, "setChWallsHideInStructures", oldHide);
            });
            hub(c);
        }
    }

    /**
     * Nearest layer only (nucwall-1105, owner: "the blue looks different than jungle looking at the goblin area"): the Jungle wall
     * seen with the Mithril wall right behind it (the 513 line, looking east) must be the color of the same wall with nothing behind
     * it (the X 202 edge, looking west), at 50 percent; and a wall still lets the scene through (it is not opaque).
     */
    private static void nearest(Session c) {
        Object cfg = c.onClient(mc -> Mod.cfg(CFG));
        boolean old = c.onClient(mc -> (Boolean) Mod.call(cfg, "isChWallsRaw"));
        Object jungle = c.onClient(mc -> Mod.enumValue(ZONE, "JUNGLE"));
        Object mithril = c.onClient(mc -> Mod.enumValue(ZONE, "MITHRIL"));
        int oj = c.onClient(mc -> ((Number) Mod.call(cfg, "getChWallOpacity", jungle)).intValue());
        int om = c.onClient(mc -> ((Number) Mod.call(cfg, "getChWallOpacity", mithril)).intValue());
        try {
            c.ctx().runOnClient(mc -> {
                Mod.call(cfg, "setChWalls", true);
                Mod.call(cfg, "setChWallOpacity", jungle, 50);
                Mod.call(cfg, "setChWallOpacity", mithril, 50);
            });
            hollows(c);
            c.server().command("gamemode creative @p");
            at(c, 214, 100, 300, 90f);
            c.waitUntil("the X 202 wall drawn", mc -> find(drawn(c), "JUNGLE", r -> Math.abs(r[0] - 202.0) < 1e-6) != null, 100);
            c.ctx().waitTicks(5);
            int[] alone = patchAtCenter(c, "chwalls-alone");
            at(c, 501, 100, 300, -90f);
            c.waitUntil("both walls of the 513 line drawn", mc -> find(drawn(c), "MITHRIL", r -> Math.abs(r[0] - 513.0) < 1e-6) != null
                    && find(drawn(c), "JUNGLE", r -> Math.abs(r[3] - 513.0) < 1e-6) != null, 100);
            c.ctx().waitTicks(80);
            int[] front = patchAtCenter(c, "chwalls-front");
            int worst = 0;
            for (int i = 0; i < 3; i++) {
                worst = Math.max(worst, Math.abs(alone[i] - front[i]));
            }
            c.note("Jungle wall rgb alone " + java.util.Arrays.toString(alone) + ", in front of Mithril " + java.util.Arrays.toString(front));
            c.check(worst <= 14, "the Jungle wall looks different in front of another wall (worst channel gap " + worst + ")");
            c.check(alone[1] - Math.max(alone[0], alone[2]) >= 10, "the lone wall is not visibly green: " + java.util.Arrays.toString(alone));
        } finally {
            c.ctx().runOnClient(mc -> {
                Mod.call(cfg, "setChWalls", old);
                Mod.call(cfg, "setChWallOpacity", jungle, oj);
                Mod.call(cfg, "setChWallOpacity", mithril, om);
            });
            hub(c);
        }
    }

    /** Mean RGB of a 9x9 patch at the screen center. */
    private static int[] patchAtCenter(Session c, String name) {
        try {
            java.nio.file.Path shot = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(name));
            dev.testkit.harness.Report.screenshot(c.name(), shot);
            BufferedImage img = javax.imageio.ImageIO.read(shot.toFile());
            int cx = img.getWidth() / 2, cy = img.getHeight() / 2, r = 0, g = 0, b = 0, n = 0;
            for (int dy = -4; dy <= 4; dy++) {
                for (int dx = -4; dx <= 4; dx++) {
                    int a = img.getRGB(cx + dx, cy + dy);
                    r += (a >> 16) & 255;
                    g += (a >> 8) & 255;
                    b += a & 255;
                    n++;
                }
            }
            return new int[]{r / n, g / n, b / n};
        } catch (java.io.IOException e) {
            throw new AssertionError("screenshot unreadable", e);
        }
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
        // ...and wait until he stands on it: arriving before the client had the glass drew one Simulation line (26.2).
        c.waitUntil("standing at " + x + "," + z, mc -> Math.abs(mc.player.getX() - x) < 1.0 && Math.abs(mc.player.getZ() - z) < 1.0
                && mc.player.onGround() && mc.level.getBlockState(new net.minecraft.core.BlockPos(bx, by, bz)).isSolid(), 200);
        c.ctx().waitTicks(5);
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
