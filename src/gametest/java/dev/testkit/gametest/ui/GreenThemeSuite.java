package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.ModUnderTest;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.gametest.mod.Quiet;
import dev.testkit.harness.Report;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 1650-1651: the Green menu theme (mod theme-green-1015, killer560 2026-10-15: make a default viridian green style theme
 * and call it green for now).
 * <ul>
 *   <li>1650 the preset: it is a preset with a stable saved id (GREEN) and the name from one constant; the Menu Theme row
 *       reaches it (Ocean, then Green, then Custom) and saves it across a reload; the mod menu draws its colors (ring and
 *       sidebar pixels, green-dominant and dark); its text has good contrast on panel and card; it is in the screen and
 *       overlay theme choices, the map theme list (with a palette of its own) and the main menu's themes.</li>
 *   <li>1651 the main menu: Match, Separate and Custom-Auto all give the Fireflies scene in Green's colors; its screenshot
 *       is dark and green, moves between two frames and the themed menu does not turn itself off.</li>
 * </ul>
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class GreenThemeSuite implements FabricClientGameTest {

    static final String[] CASES = {"1650-ui-green-theme", "1651-ui-green-mainmenu"};

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (dev.testkit.harness.ScenarioList.active() || dev.testkit.harness.ModGate.missing() != null) {
            for (String n : CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            return;
        }
        boolean any = false;
        for (String n : CASES) {
            any |= UiCase.selected(n);
        }
        if (!any) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.computeOnClient(mc -> {
            if (Quiet.enabled()) {
                Quiet.apply();
            }
            Mod.staticCall("util.SkyblockGate", "setEnabled", false);
            return null;
        });
        UiCase.run(ctx, "1650-ui-green-theme", GreenThemeSuite::theme);
        UiCase.run(ctx, "1651-ui-green-mainmenu", GreenThemeSuite::mainMenu);
    }

    static float contrast(int a, int b) {
        float la = rel(a);
        float lb = rel(b);
        return (Math.max(la, lb) + 0.05f) / (Math.min(la, lb) + 0.05f);
    }

    static float rel(int c) {
        float[] v = {(c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF};
        for (int i = 0; i < 3; i++) {
            float s = v[i] / 255f;
            v[i] = s <= 0.03928f ? s / 12.92f : (float) Math.pow((s + 0.055f) / 1.055f, 2.4);
        }
        return 0.2126f * v[0] + 0.7152f * v[1] + 0.0722f * v[2];
    }

    static boolean green(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return g > r + 4 && g >= b;
    }

    @SuppressWarnings("unchecked")
    static void theme(UiCase c) throws Exception {
        String old = MenuACases.themeName();
        try {
            // 1. identity: a preset, saved as GREEN, named from the one constant.
            String why = c.onClient(mc -> {
                Object green = Mod.enumValue("gui.MenuTheme", "GREEN");
                String label = (String) Mod.field(green, "label");
                String constant = (String) Mod.field("gui.GreenTheme", "NAME");
                if (!label.equals(constant)) {
                    return "label '" + label + "' is not GreenTheme.NAME '" + constant + "'";
                }
                Object[] presets = (Object[]) Mod.staticCall("gui.MenuTheme", "presets");
                if (!Arrays.asList(presets).contains(green)) {
                    return "GREEN is not in MenuTheme.presets()";
                }
                if (Mod.staticCall("gui.MenuTheme", "parse", "green") != green) {
                    return "parse of green is not GREEN";
                }
                return null;
            });
            c.check(why == null, why);

            // 2. the Menu Theme row: Ocean -> Green -> Custom, saved, survives a reload.
            Screen s = c.onClient(mc -> {
                Mod.call(Mod.cfg("gui.MenuConfig"), "setTheme", Mod.enumValue("gui.MenuTheme", "OCEAN"));
                try {
                    return MenuACases.open(mc);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            c.onClient(mc -> {
                Mod.staticCall("gui.tab.MenuThemeTab", "step", 1);
                Mod.call(s, "rebuild");
                return null;
            });
            c.check(MenuACases.themeName().equals("GREEN"), "one step after Ocean gave " + MenuACases.themeName());
            c.onClient(mc -> {
                Mod.staticCall("gui.tab.MenuThemeTab", "step", 1);
                return null;
            });
            c.check(MenuACases.themeName().equals("CUSTOM"), "one step after Green gave " + MenuACases.themeName());
            c.onClient(mc -> {
                Mod.staticCall("gui.tab.MenuThemeTab", "step", -1);
                Mod.call(s, "rebuild");
                return null;
            });
            c.check(MenuACases.themeName().equals("GREEN"), "a step back from Custom gave " + MenuACases.themeName());
            String shownName = c.onClient(mc -> (String) Mod.field("gui.GreenTheme", "NAME"));
            String row = c.onClient(mc -> {
                Object tab = MenuACases.leaf("MenuThemeTab");
                List<AbstractWidget> ws = (List<AbstractWidget>) Mod.call(tab, "buildWidgets", 0, 0, 300, (Runnable) () -> { });
                for (AbstractWidget w : ws) {
                    String t = w.getMessage().getString();
                    if (t.startsWith("Menu Theme:")) {
                        return t;
                    }
                }
                return "";
            });
            c.check(row.contains(shownName), "the Menu Theme row reads '" + row + "', not the name " + shownName);
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                Mod.staticCall("gui.MenuConfig", "load");
                return null;
            });
            c.check(MenuACases.themeName().equals("GREEN"), "after a reload from disk the theme is " + MenuACases.themeName());

            // 3. the colors reach the screen (pixels), and read as green.
            Screen shown = c.onClient(mc -> {
                try {
                    return MenuACases.open(mc);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            int[] px = MenuACases.sample(c, shown, "green");
            int want = (Integer) Mod.field("gui.MenuStyle", "FRAME_RING");
            c.note("Green: ring drawn " + MenuACases.hex(px[0]) + " (palette " + MenuACases.hex(want) + "), sidebar "
                    + MenuACases.hex(px[1]));
            c.check(MenuACases.near(px[0], want), "the panel ring is drawn " + MenuACases.hex(px[0]) + ", the palette says "
                    + MenuACases.hex(want));
            c.check(green(px[0]) && green(px[1]), "the ring " + MenuACases.hex(px[0]) + " or sidebar " + MenuACases.hex(px[1])
                    + " is not green-dominant");
            c.check(MenuACases.lum(px[1]) < 0.25f, "the sidebar " + MenuACases.hex(px[1]) + " is not a deep panel");
            MenuACases.shot(c, shown, "green-menu", null);

            // 4. contrast of the five base colors.
            int[] base = c.onClient(mc -> (int[]) Mod.call(Mod.enumValue("gui.MenuTheme", "GREEN"), "base"));
            float onPanel = contrast(base[3], base[1]);
            float onCard = contrast(base[3], base[2]);
            float accent = contrast(base[0], base[1]);
            c.note(String.format(Locale.ROOT, "contrast: text on panel %.1f, text on card %.1f, accent on panel %.1f",
                    onPanel, onCard, accent));
            c.check(onPanel >= 7f && onCard >= 7f && accent >= 4.5f, "Green's contrast is too low");

            // 5. everywhere presets appear.
            List<String> choices = c.onClient(mc -> (List<String>) Mod.staticCall("gui.ScreenThemes", "builtInChoices"));
            c.check(choices.contains("GREEN"), "the screen theme choices " + choices + " have no GREEN");
            c.check(choices.indexOf("GREEN") < choices.indexOf("CUSTOM"), "GREEN is not before CUSTOM in " + choices);
            String lab = c.onClient(mc -> (String) Mod.staticCall("gui.ScreenThemes", "label", "GREEN"));
            c.check(lab.equals(shownName), "the choice label is '" + lab + "', not " + shownName);
            String id = "storage_overlay";
            try {
                c.onClient(mc -> {
                    Mod.staticCall("gui.ScreenThemes", "set", id, "GREEN");
                    return null;
                });
                int[] cols = c.onClient(mc -> (int[]) Mod.staticCall("gui.ScreenThemes", "colours", id));
                c.check(Arrays.equals(cols, base), "a screen set to GREEN draws " + Arrays.toString(cols));
                Object pal = c.onClient(mc -> Mod.staticCall("gui.OverlayThemes", "of", id));
                c.check(pal != null, "an overlay set to GREEN has no palette");
            } finally {
                c.onClient(mc -> {
                    Mod.staticCall("gui.ScreenThemes", "set", id, "DEFAULT");
                    return null;
                });
            }
            Object[] mapThemes = c.onClient(mc -> Mod.cls("livemap.MapThemes$Theme").getEnumConstants());
            List<String> mapNames = new ArrayList<>();
            for (Object o : mapThemes) {
                mapNames.add(((Enum<?>) o).name());
            }
            c.check(mapNames.contains("GREEN"), "the map themes " + mapNames + " have no GREEN");
            int[] mapPal = c.onClient(mc -> (int[]) Mod.staticCall("livemap.MapThemes", "preset",
                    Mod.enumValue("livemap.MapThemes$Theme", "GREEN")));
            int[] oceanPal = c.onClient(mc -> (int[]) Mod.staticCall("livemap.MapThemes", "preset",
                    Mod.enumValue("livemap.MapThemes$Theme", "OCEAN")));
            c.check(mapPal.length == 10 && !Arrays.equals(mapPal, oceanPal), "the map's Green palette is missing or Ocean's");
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                Mod.call(Mod.cfg("gui.MenuConfig"), "setTheme", Mod.enumValue("gui.MenuTheme", old));
                Mod.call(Mod.cfg("gui.MenuConfig"), "save");
                return null;
            });
        }
    }

    static void mainMenu(UiCase c) throws Exception {
        FontsMainMenuSuite.MmState saved = FontsMainMenuSuite.saveMm(c);
        int[] win0 = c.onClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight(),
                mc.options.guiScale().get()});
        try {
            int[] green = c.onClient(mc -> (int[]) Mod.call(Mod.enumValue("gui.MenuTheme", "GREEN"), "base"));
            record Expect(String label, String menu, String style, String theme, String scene) {
            }
            for (Expect e : List.of(
                    new Expect("match", "GREEN", "MATCH", "OCEAN", "PLAIN"),
                    new Expect("separate", "AMBER", "SEPARATE", "GREEN", "PLAIN"),
                    new Expect("custom auto", "AMBER", "CUSTOM", "GREEN", "AUTO"))) {
                String why = c.onClient(mc -> {
                    Mod.call(Mod.cfg("gui.MenuConfig"), "setTheme", FontsMainMenuSuite.menuTheme(e.menu()));
                    FontsMainMenuSuite.setMm(mc, e.style(), e.theme(), e.scene(), 100, true, true);
                    Object[] l = FontsMainMenuSuite.look();
                    if (!"FIREFLIES".equals(l[0])) {
                        return e.label() + ": scene " + l[0] + ", expected FIREFLIES";
                    }
                    if (!Arrays.equals(green, (int[]) l[1])) {
                        return e.label() + ": colours " + FontsMainMenuSuite.hex((int[]) l[1]) + ", expected Green's";
                    }
                    return null;
                });
                c.check(why == null, why);
            }
            // The main menu's own theme setting saves and reloads.
            c.onClient(mc -> {
                FontsMainMenuSuite.setMm(mc, "SEPARATE", "GREEN", "AUTO", 100, true, true);
                Mod.call(Mod.cfg(FontsMainMenuSuite.MM), "save");
                Mod.staticCall(FontsMainMenuSuite.MM, "load");
                return null;
            });
            String reloaded = c.onClient(mc -> String.valueOf(Mod.call(Mod.cfg(FontsMainMenuSuite.MM), "getTheme")));
            c.check(reloaded.equals("GREEN"), "after a reload the main menu's theme is " + reloaded);

            FontsMainMenuSuite.setWindow(c, 1600, 900, 2);
            c.onClient(mc -> {
                Mod.call(Mod.cfg("gui.MenuConfig"), "setTheme", FontsMainMenuSuite.menuTheme("GREEN"));
                FontsMainMenuSuite.setMm(mc, "MATCH", "GREEN", "AUTO", 100, true, true);
                McCompat.setScreen(mc, new TitleScreen());
                return null;
            });
            c.ticks(40);
            FontsMainMenuSuite.Pic a = FontsMainMenuSuite.pic(FontsMainMenuSuite.shot(c, "fireflies"));
            c.ticks(30);
            Path second = c.ctx().takeScreenshot(Report.fileName(c.name() + "-fireflies-b"));
            BufferedImage b = FontsMainMenuSuite.pic(second).img();
            double moved = FontsMainMenuSuite.changed(a.img(), b);
            c.note(String.format(Locale.ROOT, "Green main menu: rgb %.0f,%.0f,%.0f lum %.2f moved %.2f%%", a.r(), a.g(), a.b(),
                    a.lum(), moved * 100));
            c.check(a.g() > a.r() + 4 && a.g() >= a.b() - 2, "the Green main menu is not green: " + a.r() + "," + a.g() + "," + a.b());
            c.check(a.lum() < 0.3, "the Green main menu is not dark: " + a.lum());
            c.check(moved > 0.0005, "the Fireflies scene did not move between two frames: " + moved);
            c.check(!(Boolean) c.onClient(mc -> Mod.field("mainmenu.MainMenuTheme", "failed")),
                    "the themed main menu turned itself off after an error");
        } finally {
            FontsMainMenuSuite.setWindow(c, win0[0], win0[1], win0[2]);
            FontsMainMenuSuite.restoreMm(c, saved);
        }
    }
}
