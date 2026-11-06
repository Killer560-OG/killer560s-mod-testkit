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
 *   <li>1104-chwalls-pixels: a screenshot looking at the X 202 wall from 3 blocks (opacity 100) is purple at the center, and the
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
        s.test("1106-chwalls-mapcolor", HxCrystalWalls1104Cases::mapColor);
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
            c.server().command("gamemode survival @p");
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
            c.server().command("gamemode survival @p");
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
        int cm = c.onClient(mc -> ((Number) Mod.call(cfg, "getChWallColor", mithril)).intValue());
        try {
            c.ctx().runOnClient(mc -> {
                Mod.call(cfg, "setChWalls", true);
                Mod.call(cfg, "setChWallOpacity", jungle, 50);
                // Solid black right behind the Jungle wall: the old jar blended it through, so the Jungle wall read darker.
                Mod.call(cfg, "setChWallOpacity", mithril, 100);
                Mod.call(cfg, "setChWallColor", mithril, 0x000000);
            });
            hollows(c);
            c.server().command("gamemode creative @p");
            at(c, 214, 100, 300, 90f);
            clearSight(c, 214, 300, -1);
            c.waitUntil("the X 202 wall drawn", mc -> find(drawn(c), "JUNGLE", r -> Math.abs(r[0] - 202.0) < 1e-6) != null, 100);
            c.ctx().waitTicks(60);
            int[] alone = patchAtCenter(c, "chwalls-alone");
            at(c, 501, 100, 300, -90f);
            clearSight(c, 501, 300, 1);
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
            int darker = alone[0] + alone[1] + alone[2] - front[0] - front[1] - front[2];
            c.check(darker <= 15, "a solid wall behind darkened the Jungle wall in front by " + darker + " (old jar: 35, nearest layer: -9)");
            c.check((alone[0] + alone[2]) / 2 - alone[1] >= 10, "the lone wall is not visibly purple: " + java.util.Arrays.toString(alone));
        } finally {
            c.ctx().runOnClient(mc -> {
                Mod.call(cfg, "setChWalls", old);
                Mod.call(cfg, "setChWallOpacity", jungle, oj);
                Mod.call(cfg, "setChWallOpacity", mithril, om);
                Mod.call(cfg, "setChWallColor", mithril, cm);
            });
            // Back to survival: a later case digging in creative breaks at once (1103-mining-hold-* after this, ship7 gate).
            c.server().command("gamemode survival @p");
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

    /**
     * Air along the line of sight, 5 x 5 and 100 blocks long: the test world is random, and a hill in front of the camera is
     * the plain gray frame ([102,101,101], about 1 run in 4) the walls were measured against.
     */
    private static void clearSight(Session c, int x, int z, int dir) {
        int far = x + dir * 100;
        c.server().command(String.format(Locale.ROOT, "forceload add %d %d %d %d", x, z - 2, far, z + 2));
        c.ctx().waitTicks(20);
        c.server().command(String.format(Locale.ROOT, "fill %d 98 %d %d 102 %d minecraft:air", x + dir * 2, z - 2, far, z + 2));
        c.ctx().waitTicks(20);
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
            // 1) near the west edge: the Jungle slab is at 202.0-202.1, purple (0xC050FF), alpha .25.
            at(c, 210, 100, 300, 90f);
            c.waitUntil("walls drawn near the west edge", mc -> !drawn(c).isEmpty(), 100);
            List<String> d = drawn(c);
            double[] jw = find(d, "JUNGLE", r -> Math.abs(r[0] - 202.0) < 1e-6 && Math.abs(r[3] - 202.1) < 1e-6);
            c.check(jw != null, "no Jungle slab at X 202.0-202.1; drawn: " + d);
            c.check(Math.abs(jw[6] - 0xC0 / 255.0) < 0.01 && Math.abs(jw[7] - 0x50 / 255.0) < 0.01 && Math.abs(jw[8] - 1.0) < 0.01 && Math.abs(jw[9] - 0.25) < 0.01,
                    "the Jungle wall is not the default purple at 25%: " + java.util.Arrays.toString(jw));
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
            c.note("walls at X 202.0 (Jungle, purple 25%), the 513 line (Jungle|Mithril), Magma top Y 64, Nucleus; Near Only clips; off and hub draw none");
        } finally {
            c.ctx().runOnClient(mc -> Mod.call(cfg, "setChWalls", old));
            c.server().command("gamemode survival @p");
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
            int in = purpleAtCenter(c, "chwalls-hollows");
            hub(c);
            at(c, 205, 100, 300, 90f);
            c.ctx().waitTicks(10);
            int out = purpleAtCenter(c, "chwalls-hub");
            c.check(in >= 40, "the center pixel is not wall purple in the Hollows (purpleness " + in + ")");
            c.check(out < 40, "the center pixel is purple in the hub (purpleness " + out + ")");
            c.note("center purpleness " + in + " in the Hollows (wall 3 blocks ahead), " + out + " in the hub");
        } finally {
            c.ctx().runOnClient(mc -> {
                Mod.call(cfg, "setChWalls", old);
                Mod.call(cfg, "setChWallOpacity", jungle, oldOp);
            });
            c.server().command("gamemode survival @p");
            hub(c);
        }
    }

    private static final String MAP = "mining.chmap.ChMapRenderer";

    /**
     * chwallcolor-1106 (owner: "the area wall looking into jungle is blue ... jungle should be purple themed"): every drawn wall's
     * rgb equals the map's quadrant color for its zone (Nucleus: its own, the map draws none), by default (Jungle is purple) and
     * after Jungle is given a custom color.
     */
    private static void mapColor(Session c) {
        Object cfg = c.onClient(mc -> Mod.cfg(CFG));
        boolean old = c.onClient(mc -> (Boolean) Mod.call(cfg, "isChWallsRaw"));
        String[] names = {"JUNGLE", "MITHRIL", "GOBLIN", "PRECURSOR"};
        Object[] zones = c.onClient(mc -> {
            Object[] z = new Object[names.length];
            for (int i = 0; i < z.length; i++) {
                z[i] = Mod.enumValue(ZONE, names[i]);
            }
            return z;
        });
        int[] nears = new int[zones.length];
        for (int i = 0; i < zones.length; i++) {
            final int k = i;
            nears[i] = c.onClient(mc -> ((Number) Mod.call(cfg, "getChWallNear", zones[k])).intValue());
        }
        int oldJungle = c.onClient(mc -> ((Number) Mod.call(cfg, "getChWallColor", zones[0])).intValue());
        try {
            c.ctx().runOnClient(mc -> {
                Mod.call(cfg, "setChWalls", true);
                for (Object z : zones) {
                    Mod.call(cfg, "setChWallNear", z, 0);
                }
            });
            hollows(c);
            c.server().command("gamemode creative @p");
            at(c, 300, 100, 300, 90f);
            c.waitUntil("walls drawn", mc -> !drawn(c).isEmpty(), 100);
            for (int pass = 0; pass < 2; pass++) {
                if (pass == 1) {
                    c.ctx().runOnClient(mc -> Mod.call(cfg, "setChWallColor", zones[0], 0x123456));
                    c.ctx().waitTicks(5);
                }
                List<String> lines = drawn(c);
                for (int i = 0; i < zones.length; i++) {
                    final int k = i;
                    int fill = c.onClient(mc -> ((Number) Mod.staticCall(MAP, "zoneFill", k)).intValue());
                    int cfgColor = c.onClient(mc -> ((Number) Mod.call(cfg, "getChWallColor", zones[k])).intValue());
                    c.check((fill & 0xFFFFFF) == cfgColor, names[i] + " map fill " + Integer.toHexString(fill) + " is not the wall color " + Integer.toHexString(cfgColor));
                    int seen = 0;
                    for (String l : lines) {
                        if (l.startsWith(names[i] + " ")) {
                            double[] r = row(l);
                            int rgb = ((int) Math.round(r[6] * 255) << 16) | ((int) Math.round(r[7] * 255) << 8) | (int) Math.round(r[8] * 255);
                            c.check(rgb == (fill & 0xFFFFFF), names[i] + " wall drawn " + Integer.toHexString(rgb) + " but the map shows " + Integer.toHexString(fill & 0xFFFFFF));
                            seen++;
                        }
                    }
                    c.check(seen > 0, names[i] + " drew no wall (pass " + pass + ")");
                }
                if (pass == 0) {
                    int jf = c.onClient(mc -> ((Number) Mod.staticCall(MAP, "zoneFill", 0)).intValue());
                    c.check((jf & 0xFFFFFF) == 0xC050FF, "Jungle default is not purple: " + Integer.toHexString(jf));
                }
            }
            // a saved OLD default (green 0x55FF55) moves to the new purple on load; a picked color stays
            c.ctx().runOnClient(mc -> {
                Mod.call(Mod.cfg(CFG), "setChWallColor", zones[0], 0x55FF55);
                Mod.call(Mod.cfg(CFG), "save");
                Mod.staticCall("miningqol.MiningQolConfig", "load");
            });
            int moved = c.onClient(mc -> ((Number) Mod.call(Mod.cfg(CFG), "getChWallColor", zones[0])).intValue());
            c.check(moved == 0xC050FF, "a saved old green Jungle default did not move to purple: " + Integer.toHexString(moved));
            c.ctx().runOnClient(mc -> {
                Mod.call(Mod.cfg(CFG), "setChWallColor", zones[0], 0x123456);
                Mod.call(Mod.cfg(CFG), "save");
                Mod.staticCall("miningqol.MiningQolConfig", "load");
            });
            int kept = c.onClient(mc -> ((Number) Mod.call(Mod.cfg(CFG), "getChWallColor", zones[0])).intValue());
            c.check(kept == 0x123456, "a picked Jungle color was changed on load: " + Integer.toHexString(kept));
        } finally {
            c.ctx().runOnClient(mc -> {
                Mod.call(Mod.cfg(CFG), "setChWalls", old);
                Mod.call(Mod.cfg(CFG), "setChWallColor", zones[0], oldJungle);
                for (int i = 0; i < zones.length; i++) {
                    Mod.call(Mod.cfg(CFG), "setChWallNear", zones[i], nears[i]);
                }
            });
            hub(c);
        }
    }

    /** Purpleness (the mean of R and B minus G), averaged over a 9x9 patch at the center of a screenshot. */
    private static int purpleAtCenter(Session c, String name) {
        try {
            java.nio.file.Path shot = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(name));
            dev.testkit.harness.Report.screenshot(c.name(), shot);
            BufferedImage img = javax.imageio.ImageIO.read(shot.toFile());
            int cx = img.getWidth() / 2 - 60, cy = img.getHeight() / 2 + 40, sum = 0, n = 0;
            for (int dy = -4; dy <= 4; dy++) {
                for (int dx = -4; dx <= 4; dx++) {
                    int a = img.getRGB(cx + dx, cy + dy);
                    sum += (((a >> 16) & 255) + (a & 255)) / 2 - ((a >> 8) & 255);
                    n++;
                }
            }
            return sum / n;
        } catch (java.io.IOException e) {
            throw new AssertionError("screenshot unreadable", e);
        }
    }
}
