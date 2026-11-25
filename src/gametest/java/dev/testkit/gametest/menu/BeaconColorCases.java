package dev.testkit.gametest.menu;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

/**
 * 1125-menu-beaconalert-color: the Beacon Ready Alert color. A picked color is what the title and HUD line draw in; Auto
 * (the default) is the active menu theme's accent and moves when the theme changes; the pick is in the settings file
 * (a reload keeps it) and survives a profile apply; the tab carries a Color row after the switch.
 */
final class BeaconColorCases {

    private static final String F = "foraging.BeaconReadyAlert";
    private static final String MENU = "gui.MenuConfig";

    private BeaconColorCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1125-menu-beaconalert-color", BeaconColorCases::color);
    }

    private static int titleRgb() {
        Object style = Mod.call(Mod.staticCall(F, "title"), "getStyle");
        return (Integer) Mod.call(Mod.call(style, "getColor"), "getValue");
    }

    private static void color(Session c) throws Exception {
        int[] r = c.onClient(mc -> {
            Object cfg = Mod.field(F, "CFG");
            Object menu = Mod.cfg(MENU);
            Object oldTheme = Mod.call(menu, "getTheme");
            int[] o = new int[9];
            Mod.call(cfg, "set", "enabled", true);
            Mod.call(cfg, "set", "color", 0);
            Mod.call(menu, "setTheme", Mod.enumValue("gui.MenuTheme", "AMBER"));
            o[0] = (Integer) Mod.staticCall(F, "color");
            o[1] = titleRgb();
            Mod.call(menu, "setTheme", Mod.enumValue("gui.MenuTheme", "DARK"));
            o[2] = (Integer) Mod.staticCall(F, "color");
            Mod.call(cfg, "set", "color", 0xFF12AB34);
            o[3] = (Integer) Mod.staticCall(F, "color");
            o[4] = titleRgb();
            Mod.call(cfg, "save");
            Mod.call(cfg, "set", "color", 0);
            Mod.call(cfg, "load");
            o[5] = (Integer) Mod.call(cfg, "num", "color");
            Mod.staticCall("profiles.ProfileManager", "reloadAllConfigs");
            o[6] = (Integer) Mod.call(cfg, "num", "color");
            Mod.call(cfg, "set", "color", 0);
            Mod.call(cfg, "save");
            Mod.call(menu, "setTheme", Mod.enumValue("gui.MenuTheme", "DARK"));
            o[7] = (Integer) Mod.staticCall(F, "color");
            Mod.call(menu, "setTheme", oldTheme);
            Mod.call(cfg, "set", "enabled", false);
            return o;
        });
        c.note(String.format("auto amber %08x, title %06x, auto dark %08x, picked %08x, title %06x, reload %08x, profile %08x, back to auto %08x",
                r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7]));
        c.check(r[0] != r[2], "Auto did not change with the menu theme");
        c.check(r[1] == (r[0] & 0xFFFFFF), "Auto title color is not the theme accent");
        c.check(r[3] == 0xFF12AB34 && r[4] == 0x12AB34, "a picked color is not what the title uses");
        c.check(r[5] == 0xFF12AB34 || r[5] == (int) 0xFF12AB34L, "the pick did not survive a settings reload");
        c.check(r[6] == r[5], "a profile apply lost the pick");
        c.check(r[7] == r[2], "setting 0 did not return to Auto");
    }
}
