package dev.testkit.gametest.menu;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import java.awt.image.BufferedImage;
import java.nio.file.Path;

/**
 * 1108-leaphud-lag (mod branch leaphud-1108; killer560's to-do: "The tps and ping and such hud should be shown in the leap
 * menu as well"). With the Spirit Leap menu open, a screenshot with the Performance HUD (Lag Display) ON must differ from
 * one with it OFF by a block of lit pixels in the top-left quarter, where its default position is. Both shots are taken
 * over the same open menu, so only a draw over the menu can make the difference.
 */
final class LeapHudCases {

    private LeapHudCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1108-leaphud-lag", LeapHudCases::run);
    }

    private static void run(Session c) throws Exception {
        MenuKit.reset(c);
        Object simWas = Mod.field("secrets.DungeonState", "simOverrideActive");
        boolean gateWas = (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled");
        Mod.staticCall("util.SkyblockGate", "setEnabled", false);
        Mod.setField("secrets.DungeonState", "simOverrideActive", true);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set("spiritleap.SpiritLeapOverlayConfig", "Enabled", false);
            MenuKit.show(c, MenuKit.menu("menus.spirit-leap"));
            MenuKit.awaitScreen(c, "Spirit Leap", 100);
            BufferedImage off;
            BufferedImage on;
            try (AutoCloseable lag = Mod.with("lagdisplay.LagDisplayConfig", "Enabled", false)) {
                c.ctx().waitTicks(6);
                off = shot(c, "off");
            }
            try (AutoCloseable lag = Mod.with("lagdisplay.LagDisplayConfig", "Enabled", true)) {
                c.ctx().waitTicks(6);
                on = shot(c, "on");
            }
            int w = Math.min(on.getWidth(), off.getWidth()) / 2;
            int h = Math.min(on.getHeight(), off.getHeight()) / 2;
            int lit = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int a = on.getRGB(x, y);
                    int b = off.getRGB(x, y);
                    int d = Math.abs(((a >> 16) & 255) - ((b >> 16) & 255)) + Math.abs(((a >> 8) & 255) - ((b >> 8) & 255))
                            + Math.abs((a & 255) - (b & 255));
                    if (d > 150) {
                        lit++;
                    }
                }
            }
            c.note("leap menu, Performance HUD on vs off: " + lit + " pixels differ in the top-left quarter");
            c.check(lit >= 40, "the Performance HUD did not draw over the leap menu (" + lit + " differing pixels)");
        } finally {
            MenuKit.reset(c);
            Mod.staticCall("util.SkyblockGate", "setEnabled", gateWas);
            Mod.setField("secrets.DungeonState", "simOverrideActive", simWas);
        }
    }

    private static BufferedImage shot(Session c, String tag) throws Exception {
        Path p = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(c.name() + "-" + tag));
        return javax.imageio.ImageIO.read(p.toFile());
    }
}
