package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.Report;

import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;

/**
 * 1111-ui-whatsnew (mod whatsnew-1111, restyled whatsstyle-1113). The Themed Main Menu holds its three sections with the
 * logo clear of the panel ({@link MainMenuSections}, screenshot). Its What's New button opens
 * whatsnew.WhatsNewScreen, which lists at least 1 update with its sections' lines; a click on a date chip, Older > and
 * &lt; Newer pick updates (Newer is off on the newest); the wheel scrolls a long update; the content box carries a solid
 * ring in the theme accent that stands apart from both the panel outside it and the well inside it (screenshot pixels);
 * Back returns to the title screen.
 */
final class WhatsNewCases {

    static final String NAME = "1111-ui-whatsnew";
    private static final String MM = "mainmenu.MainMenuThemeConfig";

    private WhatsNewCases() {
    }

    private static AbstractWidget named(Screen s, String label) {
        for (AbstractWidget w : Screens.getWidgets(s)) {
            if (w.visible && w.getMessage() != null && w.getMessage().getString().equals(label)) {
                return w;
            }
        }
        return null;
    }

    private static boolean isWn(Screen s) {
        return s != null && s.getClass().getName().endsWith(".whatsnew.WhatsNewScreen");
    }

    private static boolean click(Screen s, double x, double y) {
        return s.mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(dev.testkit.compat.McKeys.mouse(0), 0)), false);
    }

    private static int i(Screen s, String f) {
        return (Integer) Mod.field(s, f);
    }

    private static Path shot(UiCase c, String label) {
        Path p = c.ctx().takeScreenshot(Report.fileName(c.name() + "-" + label));
        Path kept = Report.screenshot(c.name() + "-" + label, p);
        c.note("screenshot " + label + " -> " + (kept != null ? kept : p));
        return p;
    }

    private static int dist(int a, int b) {
        return Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF)) + Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF))
                + Math.abs((a & 0xFF) - (b & 0xFF));
    }

    static void run(UiCase c) {
        c.onClient(mc -> {
            Mod.call(Mod.cfg(MM), "setEnabled", true);
            McCompat.setScreen(mc, new TitleScreen());
            return null;
        });
        c.ticks(40);
        String row = c.onClient(mc -> MainMenuSections.check(McCompat.screen(mc)));
        c.check(row == null, row);
        shot(c, "mainmenu");
        String opened = c.onClient(mc -> {
            AbstractWidget b = named(McCompat.screen(mc), "What's New");
            if (b == null) {
                return "the title screen has no What's New button";
            }
            ModScreenDriver.press(b);
            return isWn(McCompat.screen(mc)) ? null : "pressing What's New opened " + McCompat.screen(mc);
        });
        c.check(opened == null, opened);
        if (opened != null) {
            return;
        }
        // The data loads in the background; wait for it, drawing frames as a player would.
        for (int k = 0; k < 20; k++) {
            c.onClient(mc -> {
                Frames.extract(mc, McCompat.screen(mc), -1, -1);
                return null;
            });
            c.ticks(2);
        }
        String shown = c.onClient(mc -> {
            Screen s = McCompat.screen(mc);
            List<?> ups = (List<?>) Mod.field(s, "updates");
            if (ups == null || ups.size() < 3) {
                return "fewer than 3 updates listed: " + ups;
            }
            c.note("updates: " + ups.size());
            List<?> lines = (List<?>) Mod.field(s, "lines");
            if (lines.isEmpty()) {
                return "the newest update shows no lines";
            }
            if (named(s, "< Newer").active || !named(s, "Older >").active) {
                return "on the newest update Newer should be off and Older on";
            }
            int cx = i(s, "chipX");
            int cy = i(s, "chipY");
            int cw = i(s, "chipW");
            click(s, cx + cw + 4 + cw / 2, cy + 5);
            if (i(s, "selected") != 1) {
                return "clicking the second date chip selected " + i(s, "selected");
            }
            ModScreenDriver.press(named(s, "Older >"));
            if (i(s, "selected") != 2) {
                return "Older > selected " + i(s, "selected") + ", want 2";
            }
            ModScreenDriver.press(named(s, "< Newer"));
            if (i(s, "selected") != 1) {
                return "< Newer selected " + i(s, "selected") + ", want 1";
            }
            // The wheel over the box scrolls the longest update.
            int best = 0;
            for (int k = 0; k < ups.size(); k++) {
                Mod.call(s, "select", k);
                if ((Integer) Mod.call(s, "maxScroll") > 0) {
                    best = k;
                    break;
                }
            }
            Mod.call(s, "select", best);
            if ((Integer) Mod.call(s, "maxScroll") > 0) {
                s.mouseScrolled(i(s, "boxX") + 20, i(s, "boxY") + 40, 0, -1);
                if (i(s, "scroll") <= 0) {
                    return "the wheel did not scroll update " + best;
                }
            } else {
                c.note("no update is long enough to scroll at this window size");
            }
            Mod.call(s, "select", 1);
            Frames.extract(mc, s, -1, -1);
            return null;
        });
        c.check(shown == null, shown);
        Path wn = shot(c, "screen");
        String box = c.onClient(mc -> {
            Screen s = McCompat.screen(mc);
            int bx = i(s, "boxX");
            int by = i(s, "boxY");
            int bh = i(s, "boxH");
            int accent = (Integer) Mod.field("gui.MenuStyle", "OR");
            try {
                // The shot may be scaled from the window: map GUI units through its own size.
                BufferedImage img = javax.imageio.ImageIO.read(wn.toFile());
                double sc = img.getWidth() / (double) s.width;
                int y = (int) ((by + bh / 2 + 0.5) * sc);
                int ring = img.getRGB((int) ((bx - 0.5) * sc), y);
                int outside = img.getRGB((int) ((bx - 5.5) * sc), y);
                int inside = img.getRGB((int) ((bx + 4.5) * sc), y);
                c.note(String.format("ring %06X accent %06X outside %06X inside %06X", ring & 0xFFFFFF,
                        accent & 0xFFFFFF, outside & 0xFFFFFF, inside & 0xFFFFFF));
                if (dist(ring, accent) > 60) {
                    return "the box ring is not the theme accent";
                }
                if (dist(ring, outside) < 150 || dist(ring, inside) < 150) {
                    return "the box ring does not stand apart from the panel and the well";
                }
                return null;
            } catch (java.io.IOException e) {
                return "could not read the screenshot: " + e;
            }
        });
        c.check(box == null, box);
        String back = c.onClient(mc -> {
            Screen s = McCompat.screen(mc);
            AbstractWidget b = named(s, "Back");
            if (b == null) {
                return "no Back button";
            }
            ModScreenDriver.press(b);
            return McCompat.screen(mc) instanceof TitleScreen ? null : "Back went to " + McCompat.screen(mc);
        });
        c.check(back == null, back);
    }
}
