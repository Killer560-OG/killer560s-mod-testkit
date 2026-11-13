package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

import java.util.List;
import java.util.Set;

/**
 * 1111-ui-whatsnew (mod whatsnew-1111): the Themed Main Menu's What's New button opens whatsnew.WhatsNewScreen, which lists at
 * least 1 update, shows cards for the first one, opens a card with a real click (and closes it with a second), picks another
 * update with a click, scrolls with the wheel, draws a frame, and Back returns to the title screen. Home's What's New row is
 * checked to exist through the same screen class opening from the menu's button list.
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

    static void run(UiCase c) {
        c.onClient(mc -> {
            Mod.call(Mod.cfg(MM), "setEnabled", true);
            McCompat.setScreen(mc, new TitleScreen());
            return null;
        });
        c.ticks(20);
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
        for (int i = 0; i < 20; i++) {
            c.onClient(mc -> {
                Frames.extract(mc, McCompat.screen(mc), -1, -1);
                return null;
            });
            c.ticks(2);
        }
        String shown = c.onClient(mc -> {
            Screen s = McCompat.screen(mc);
            List<?> ups = (List<?>) Mod.field(s, "updates");
            if (ups == null || ups.isEmpty()) {
                return "no updates listed: " + ups;
            }
            c.note("updates: " + ups.size());
            List<?> lines = (List<?>) Mod.field(s, "lines");
            if (lines.isEmpty()) {
                return "the first update has no cards";
            }
            c.note("cards of update 0: " + lines.size());
            Set<?> open = (Set<?>) Mod.field(s, "open");
            int cx = (Integer) Mod.field(s, "cardX");
            int cy = (Integer) Mod.field(s, "cardY");
            if (!open.isEmpty()) {
                return "a card is open before any click";
            }
            if (!click(s, cx + 20, cy + 10) || open.size() != 1) {
                return "clicking the first card did not open it (open " + open + ")";
            }
            if (!click(s, cx + 20, cy + 10) || !open.isEmpty()) {
                return "a second click did not close it (open " + open + ")";
            }
            click(s, cx + 20, cy + 10);
            int lx = (Integer) Mod.field(s, "listX");
            int ly = (Integer) Mod.field(s, "listY");
            if (ups.size() > 1) {
                click(s, lx + 10, ly + 28 + 5);
                if ((Integer) Mod.field(s, "selected") != 1 || !open.isEmpty()) {
                    return "clicking the second update did not select it and close the cards";
                }
            }
            s.mouseScrolled(cx + 5, cy + 5, 0, -1);
            s.mouseScrolled(lx + 5, ly + 5, 0, -1);
            Frames.extract(mc, s, -1, -1);
            return null;
        });
        c.check(shown == null, shown);
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
