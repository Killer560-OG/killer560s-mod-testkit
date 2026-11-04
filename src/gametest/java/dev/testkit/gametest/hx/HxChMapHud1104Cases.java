package dev.testkit.gametest.hx;

import dev.testkit.gametest.menu.CroesusUiCases;
import dev.testkit.gametest.mod.Mod;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Locale;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Mod chhud-1104 (killer560, 2026-11-04: "the crystal hollows map should function more like skyhanni and skyblockers do.
 * it should be a hud element like theirs"). 1104-chmap-hud: in the Crystal Hollows the Map HUD is drawn (HudSeen), and a
 * screenshot with it on against one with its switch off shows, inside its editor box, the dark panel over most of the box,
 * the orange frame, a found structure's border in its color and the green player arrow where the player stands; in the Hub
 * with the switch on, nothing in the box changes and it is not drawn.
 */
final class HxChMapHud1104Cases {

    private static final String CFG = "mining.chmap.CrystalHollowsMapConfig";
    private static final String STATE = "miningqol.MiningState";
    private static final String HUD = "mining_ch_map";
    /** The HUD's square in its own units, the cave (x/z 202..823) fitted into 98 of them one unit in. */
    private static final double SIZE = 100;

    private HxChMapHud1104Cases() {
    }

    static void register(Session s) {
        s.test("1104-chmap-hud", HxChMapHud1104Cases::hud);
    }

    private static void hollows(Session c) {
        c.hx().sidebar("SKYBLOCK", "Late Winter 3rd", "⏣ Jungle", "Purse: 0");
        c.hx().tab(List.of("Area: Crystal Hollows", "Profile: Test", ""), null, null);
        c.waitUntil("Crystal Hollows", mc -> "CRYSTAL_HOLLOWS".equals(Mod.staticCall(STATE, "island")), 100);
    }

    /** HUD unit of world x or z, as ChMapRenderer.setWholeCave lays it out. */
    private static double unit(double world) {
        return 1 + (world - 202) * 98 / 621.0;
    }

    private static void hud(Session c) {
        double[] home = c.onClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
        try (Settings set = new Settings(c).with(CFG, "Enabled", true).with(CFG, "HudEnabled", true)
                .with(CFG, "ShowZones", true).with(CFG, "ShowBorders", true)) {
            hollows(c);
            c.server().command("setblock 400 99 300 minecraft:stone");
            c.server().command("tp @a 400.5 100 300.5 0 90");
            c.ctx().waitTicks(20);
            // A first-hand find: Mines of Divan, x 600..700, z 300..400 (cyan border).
            c.ctx().runOnClient(mc -> {
                Object s = Mod.enumValue("mining.chmap.ChStructure", "MINES_OF_DIVAN");
                Object v = Mod.enumValue("mining.chmap.ChFind$Source", "VISITED");
                Mod.staticCall("mining.chmap.ChDiscovery", "record", s, v, 600.0, 80.0, 300.0);
                Mod.staticCall("mining.chmap.ChDiscovery", "record", s, v, 700.0, 80.0, 400.0);
            });
            c.waitUntil("the map HUD is drawn", mc -> (Boolean) Mod.staticCall("hud.HudSeen", "drawnRecently", HUD), 60);
            c.ctx().waitTicks(5); // a frame after the find, so the layout holds it
            int rects = c.onClient(mc -> ((Number) Mod.staticCall("mining.chmap.ChMapHud", "layoutRects")).intValue());
            c.check(rects >= 10, "the map laid out only " + rects + " rectangles");

            // Box in GUI units and the screenshot's pixels per GUI unit.
            double[] g = c.onClient(mc -> {
                Object el = Mod.staticCall("hud.HudElementRegistry", "byId", HUD);
                int[] pos = (int[]) Mod.staticCall("hud.HudElementRegistry", "resolvePosition", el);
                float sc = ((Number) Mod.staticCall("hud.HudElementRegistry", "resolveScale", el)).floatValue();
                return new double[]{pos[0], pos[1], sc, mc.getWindow().getGuiScaledWidth()};
            });
            BufferedImage on = CroesusUiCases.shot(c, "hud-on");
            c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(CFG), "setHudEnabled", false));
            c.ctx().waitTicks(3);
            BufferedImage off = CroesusUiCases.shot(c, "hud-off");
            double px = on.getWidth() / g[3];

            // The panel: most of the box changed.
            int changed = 0;
            int samples = 0;
            for (double u = 4; u < SIZE - 4; u += 2) {
                for (double v = 4; v < SIZE - 4; v += 2) {
                    int[] p = pixel(g, px, u, v);
                    samples++;
                    if (CroesusUiCases.changed(on.getRGB(p[0], p[1]), off.getRGB(p[0], p[1]))) {
                        changed++;
                    }
                }
            }
            c.check(changed * 10 >= samples * 6, "only " + changed + "/" + samples + " of the box changed with the HUD on");

            // The orange frame along the top edge.
            int orange = 0;
            int edge = 0;
            for (double u = 4; u < SIZE - 4; u += 3) {
                int[] p = pixel(g, px, u, 0.5);
                int a = on.getRGB(p[0], p[1]);
                edge++;
                if (((a >> 16) & 255) > 150 && ((a >> 8) & 255) > 50 && ((a >> 8) & 255) < 150 && (a & 255) < 70) {
                    orange++;
                }
            }
            c.check(orange * 10 >= edge * 7, "the frame is orange at only " + orange + "/" + edge);

            // Mines of Divan's top border, cyan, at z 300 between x 600 and 700.
            int cyan = count(on, g, px, unit(610), unit(690), unit(300) - 1.5, unit(300) + 1.5,
                    a -> (a & 255) > 150 && ((a >> 8) & 255) > 150 && ((a >> 16) & 255) < 140);
            c.check(cyan >= 5, "the found structure's border has only " + cyan + " cyan pixels");

            // The player arrow at x 400.5, z 300.5.
            int green = count(on, g, px, unit(400.5) - 4, unit(400.5) + 4, unit(300.5) - 4, unit(300.5) + 4,
                    a -> ((a >> 8) & 255) > 200 && ((a >> 16) & 255) < 150 && (a & 255) < 150);
            c.check(green >= 3, "no player arrow at the player's spot (" + green + " green pixels)");
            c.note(String.format(Locale.ROOT, "box %.0f,%.0f x%.2f: %d/%d changed, frame %d/%d, cyan %d, arrow %d, rects %d",
                    g[0], g[1], g[2], changed, samples, orange, edge, cyan, green, rects));

            // Outside the Crystal Hollows, switch on: nothing in the box changes and nothing draws.
            // Back home first and settled, so the world behind the box holds still between the two shots.
            c.server().command(String.format(Locale.ROOT, "tp @a %.2f %.2f %.2f 0 90", home[0], home[1], home[2]));
            hub(c);
            c.waitUntil("out of the Crystal Hollows", mc -> !"CRYSTAL_HOLLOWS".equals(Mod.staticCall(STATE, "island")), 100);
            c.ctx().waitTicks(60);
            BufferedImage hubOff = CroesusUiCases.shot(c, "hub-off");
            c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(CFG), "setHudEnabled", true));
            c.ctx().waitTicks(40);
            BufferedImage hubOn = CroesusUiCases.shot(c, "hub-on");
            int hubChanged = 0;
            for (double u = 4; u < SIZE - 4; u += 2) {
                for (double v = 4; v < SIZE - 4; v += 2) {
                    int[] p = pixel(g, px, u, v);
                    if (CroesusUiCases.changed(hubOn.getRGB(p[0], p[1]), hubOff.getRGB(p[0], p[1]))) {
                        hubChanged++;
                    }
                }
            }
            c.check(hubChanged * 50 < samples, hubChanged + "/" + samples + " of the box changed in the Hub");
            long since = c.onClient(mc -> ((Number) Mod.staticCall("hud.HudSeen", "msSince", HUD,
                    System.currentTimeMillis())).longValue());
            c.check(since >= 1500, "the map HUD drew in the Hub (" + since + " ms ago)");
            c.note("hub: " + hubChanged + "/" + samples + " changed, last drawn " + since + " ms ago");
        } finally {
            c.ctx().runOnClient(mc -> Mod.staticCall("mining.chmap.ChDiscovery", "clear"));
            c.server().command(String.format(Locale.ROOT, "tp @a %.2f %.2f %.2f", home[0], home[1], home[2]));
            c.server().command("setblock 400 99 300 minecraft:air");
        }
    }

    private static int[] pixel(double[] g, double px, double u, double v) {
        return new int[]{(int) ((g[0] + u * g[2]) * px), (int) ((g[1] + v * g[2]) * px)};
    }

    private static int count(BufferedImage img, double[] g, double px, double u0, double u1, double v0, double v1,
                             java.util.function.IntPredicate want) {
        int n = 0;
        int x0 = (int) ((g[0] + u0 * g[2]) * px);
        int x1 = (int) ((g[0] + u1 * g[2]) * px);
        int y0 = (int) ((g[1] + v0 * g[2]) * px);
        int y1 = (int) ((g[1] + v1 * g[2]) * px);
        for (int y = Math.max(0, y0); y <= Math.min(img.getHeight() - 1, y1); y++) {
            for (int x = Math.max(0, x0); x <= Math.min(img.getWidth() - 1, x1); x++) {
                if (want.test(img.getRGB(x, y))) {
                    n++;
                }
            }
        }
        return n;
    }
}
