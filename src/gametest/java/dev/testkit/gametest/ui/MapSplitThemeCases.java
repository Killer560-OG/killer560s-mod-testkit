package dev.testkit.gametest.ui;

import dev.testkit.gametest.mod.Mod;
import java.util.List;

/**
 * maptheme1108 (killer560, 2026-11-08): the dungeon map outline follows the menu theme, and Split Timers draw names in one
 * left column and times in one right-aligned column.
 * <ul>
 *   <li>1108-ui-map-border-theme: with the untouched border/door-outline colors, Amber draws the old look (white / amber) and
 *       Green draws the green accent; a color the player picked stays on both themes.</li>
 *   <li>1108-ui-splittimers-columns: the sample run's rows share ONE name x and ONE right edge for their times, the time
 *       column starts right of the widest name.</li>
 * </ul>
 */
final class MapSplitThemeCases {

    private static final String MAP_CFG = "livemap.LiveMapConfig";
    private static final String HUD = "splittimers.SplitTimersFeature$SplitTimersHudElement";

    private MapSplitThemeCases() {
    }

    private static int rgb(Object argb) {
        return ((Integer) argb) & 0xFFFFFF;
    }

    static void mapBorder(UiCase c) throws Exception {
        String old = MenuACases.themeName();
        Object cfg = Mod.cfg(MAP_CFG);
        int border = (Integer) Mod.call(cfg, "getMapBorderColor");
        int door = (Integer) Mod.call(cfg, "getWitherDoorOutlineColor");
        try {
            c.onClient(mc -> {
                Mod.call(cfg, "setMapBorderColor", 0xFFFFFFFF);
                Mod.call(cfg, "setWitherDoorOutlineColor", 0xFFFFAA00);
                Mod.call(Mod.cfg("gui.MenuConfig"), "setTheme", Mod.enumValue("gui.MenuTheme", "AMBER"));
                return null;
            });
            c.check(rgb(Mod.call(cfg, "drawnBorderColor")) == 0xFFFFFF, "Amber default border is no longer white");
            c.check(rgb(Mod.call(cfg, "drawnWitherDoorOutlineColor")) == 0xFFAA00, "Amber default door outline moved");
            c.onClient(mc -> {
                Mod.call(Mod.cfg("gui.MenuConfig"), "setTheme", Mod.enumValue("gui.MenuTheme", "GREEN"));
                return null;
            });
            Object menu = Mod.staticCall("gui.OverlayThemes", "menu");
            int accent = rgb(Mod.call(menu, "accent"));
            int hover = rgb(Mod.call(menu, "accentHover"));
            int drawn = rgb(Mod.call(cfg, "drawnBorderColor"));
            int drawnDoor = rgb(Mod.call(cfg, "drawnWitherDoorOutlineColor"));
            c.note("Green: border " + MenuACases.hex(drawn) + " (accent " + MenuACases.hex(accent) + "), door outline "
                    + MenuACases.hex(drawnDoor) + " (hover " + MenuACases.hex(hover) + ")");
            c.check(drawn == accent, "default map border on Green is " + MenuACases.hex(drawn) + ", not the theme accent");
            c.check(drawnDoor == hover, "default door outline on Green is " + MenuACases.hex(drawnDoor) + ", not the theme's");
            c.onClient(mc -> {
                Mod.call(cfg, "setMapBorderColor", 0xFF123456);
                Mod.call(cfg, "setWitherDoorOutlineColor", 0xFF654321);
                return null;
            });
            c.check(rgb(Mod.call(cfg, "drawnBorderColor")) == 0x123456, "a picked border color did not stay on Green");
            c.check(rgb(Mod.call(cfg, "drawnWitherDoorOutlineColor")) == 0x654321, "a picked door outline did not stay on Green");
        } finally {
            c.onClient(mc -> {
                Mod.call(cfg, "setMapBorderColor", border);
                Mod.call(cfg, "setWitherDoorOutlineColor", door);
                Mod.call(Mod.cfg("gui.MenuConfig"), "setTheme", Mod.enumValue("gui.MenuTheme", old));
                return null;
            });
        }
    }

    static void splitColumns(UiCase c) throws Exception {
        Object splits = Mod.cfg("splittimers.SplitTimersConfig");
        boolean was = (Boolean) Mod.call(splits, "isEnabled");
        try {
            c.onClient(mc -> {
                Mod.call(splits, "setEnabled", true);
                return null;
            });
            List<?> lines = c.onClient(mc -> (List<?>) Mod.staticCall(HUD, "buildLayout", true));
            int timed = 0;
            int nameX = Integer.MIN_VALUE;
            int right = Integer.MIN_VALUE;
            int maxNameEnd = 0;
            int minTimeX = Integer.MAX_VALUE;
            for (Object line : lines) {
                Object time = Mod.field(line, "time");
                if (time == null) {
                    continue;
                }
                timed++;
                int dx = (Integer) Mod.field(line, "dx");
                int timeDx = (Integer) Mod.field(line, "timeDx");
                int timeW = (Integer) Mod.field(line, "timeW");
                int nameW = c.onClient(mc -> mc.font.width((String) Mod.field(line, "text")));
                if (nameX == Integer.MIN_VALUE) {
                    nameX = dx;
                    right = timeDx + timeW;
                }
                c.check(dx == nameX, "name x " + dx + " differs from the column x " + nameX + " (" + Mod.field(line, "text") + ")");
                c.check(timeDx + timeW == right, "time right edge " + (timeDx + timeW) + " differs from " + right + " ("
                        + Mod.field(line, "text") + ")");
                maxNameEnd = Math.max(maxNameEnd, dx + nameW);
                minTimeX = Math.min(minTimeX, timeDx);
            }
            c.note(timed + " timed rows, names end by " + maxNameEnd + ", times start at " + minTimeX + ", right edge " + right);
            c.check(timed >= 10, "only " + timed + " rows carry a time: the sample run was not laid out");
            c.check(minTimeX > maxNameEnd, "a time starts at " + minTimeX + ", inside the widest name (ends " + maxNameEnd + ")");
        } finally {
            c.onClient(mc -> {
                Mod.call(splits, "setEnabled", was);
                return null;
            });
        }
    }
}
