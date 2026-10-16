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
import java.nio.file.Files;
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
 *   <li>1650 also (green-v2-1015, the theme's designer: "The color is more teal than green, I think making it more green
 *       would be good"): every one of the five base colors has a hue of 120-142 degrees, a true green (the first pass sat
 *       at ~160, viridian), and the white knob reads on the on-switch.</li>
 *   <li>1652 the gradient background (the designer: "can you do gradients for the background or no?"): only Green has a
 *       panel background of its own - every other preset, and a Custom set one step off Green's, keeps the plain bevel;
 *       each of the three variants (A forest night, B moss floor, C canopy glow) is DRAWN (panel pixels at the top, middle
 *       and foot of the panel's left margin match that variant's stops, C's glow included); the background is a cached
 *       constant (the same object frames apart, nothing built per frame); a 1920x1080 GUI 2 screenshot of the menu in
 *       each variant (and before them, "default") and of the main menu is kept. On a jar without the gradient the
 *       "default" shot is the before picture and the case fails.</li>
 *   <li>1651 also, the forest (killer560, 2026-10-15: "can you make the main menu more of a forest?"): at 1920x1080 GUI 2
 *       the Fireflies scene has laid out a forest (trunks in three depth layers, crown and bush lobes, ferns, grass
 *       tufts); it is green-dominant and dark, moves over 4 s of its clock while the button column barely changes (under
 *       3%), the title band and the button column are dark (mean luminance under 0.2, 7:1 for the text on their median),
 *       and over 60 frames it allocates no more per rectangle than Amber's embers (nothing built per frame). It notes the
 *       rectangles per frame. TESTKIT_GREEN_SHOTS copies the frame to forest-mainmenu.png.</li>
 *   <li>1651 also, the acid cave (green-v3-1015, the designer: "an acid looking cave with an acid lake and waterfall ...
 *       a silhouette of my character fighting a big green slime"): it is the default Green scene (the forest above is
 *       drawn through {@code MainMenuScenes.setGreenSceneForTest("FOREST")}); at 1920x1080 GUI 2 it is green-dominant and
 *       dark, the lake along the bottom glows (brighter than the button column), the fight bottom left moves within one
 *       swing while the button column barely changes, the title band and column pass the forest's contrast checks, and it
 *       allocates no more per rectangle than Amber's embers. It notes rects per frame. TESTKIT_GREEN_SHOTS copies the frame
 *       to acid-cave.png. On a jar without the scene the hook is missing and the case fails.</li>
 *   <li>1651 also, the slime boss (green-v4-1015, the designer: "the radiance is the slime boss ... emerging from the acid
 *       lake"): BOSS is the default Green scene; it is a still (nothing changes over 4 s of its clock); the boss glows bright
 *       on the right, the character's silhouette is dark on the left, the title band and column pass the same contrast
 *       checks, and it allocates no more per rectangle than Amber's embers. TESTKIT_GREEN_SHOTS copies it to
 *       boss-scene.png.</li>
 *   <li>1652 also: Canopy Glow (C) is the default gradient (the designer: "the last gradient looks the best").</li>
 *   <li>1653 ~10 s of the default Green scene (or {@code -Dtestkit.greenScene=FOREST|SIMPLE|ACID}) at 60 fps, one PNG per
 *       1/60 s of the scene clock into {@code <report>/green-<scene>/} (for ffmpeg). Only runs when its number is named.</li>
 * </ul>
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class GreenThemeSuite implements FabricClientGameTest {

    static final String[] CASES = {"1650-ui-green-theme", "1651-ui-green-mainmenu", "1652-ui-green-gradient",
            "1653-ui-green-forest-video"};

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
        UiCase.run(ctx, "1652-ui-green-gradient", GreenThemeSuite::gradient);
        UiCase.run(ctx, "1653-ui-green-forest-video", GreenThemeSuite::video);
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

    /** Hue in degrees (0..360), HSV. */
    static float hue(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        if (max == min) {
            return 0f;
        }
        float d = max - min;
        float h;
        if (max == r) {
            h = 60f * (((g - b) / d) % 6f);
        } else if (max == g) {
            h = 60f * ((b - r) / d + 2f);
        } else {
            h = 60f * ((r - g) / d + 4f);
        }
        return h < 0 ? h + 360f : h;
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
            float onSwitch = contrast(0xFFFFFFFF, base[4]);
            c.note(String.format(Locale.ROOT, "white knob on the on-switch %.1f", onSwitch));
            c.check(onSwitch >= 3f, "the white knob on Green's on-switch is too faint: " + onSwitch);

            // 4b. a true green, not teal (designer, 2026-10-15): every base color's hue is 120-142 degrees.
            String[] slot = {"accent", "panel", "card", "text", "switch-on"};
            StringBuilder hues = new StringBuilder();
            for (int k = 0; k < 5; k++) {
                float h = hue(base[k]);
                hues.append(slot[k]).append(' ').append(MenuACases.hex(base[k]))
                        .append(String.format(Locale.ROOT, " %.0f; ", h));
                c.check(h >= 120f && h <= 142f, "Green's " + slot[k] + " " + MenuACases.hex(base[k]) + " has hue "
                        + Math.round(h) + " - teal, not green (wanted 120-142)");
            }
            c.note("hues: " + hues);

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
                    FontsMainMenuSuite.setMm(mc, e.style(), e.theme(), e.scene(), 100, true);
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
                FontsMainMenuSuite.setMm(mc, "SEPARATE", "GREEN", "AUTO", 100, true);
                Mod.call(Mod.cfg(FontsMainMenuSuite.MM), "save");
                Mod.staticCall(FontsMainMenuSuite.MM, "load");
                return null;
            });
            String reloaded = c.onClient(mc -> String.valueOf(Mod.call(Mod.cfg(FontsMainMenuSuite.MM), "getTheme")));
            c.check(reloaded.equals("GREEN"), "after a reload the main menu's theme is " + reloaded);

            // An animated Green scene for the "moves between two frames" check (the default, the slime boss, is a still).
            greenScene(c, "ACID");
            FontsMainMenuSuite.setWindow(c, 1600, 900, 2);
            c.onClient(mc -> {
                Mod.call(Mod.cfg("gui.MenuConfig"), "setTheme", FontsMainMenuSuite.menuTheme("GREEN"));
                FontsMainMenuSuite.setMm(mc, "MATCH", "GREEN", "AUTO", 100, true);
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
            greenScene(c, "FOREST");
            forest(c);
            greenScene(c, "ACID");
            acidCave(c);
            greenScene(c, "");
            bossScene(c);
            c.check(!(Boolean) c.onClient(mc -> Mod.field("mainmenu.MainMenuTheme", "failed")),
                    "the themed main menu turned itself off after an error");
        } finally {
            clock(c, -1);
            greenScene(c, "");
            FontsMainMenuSuite.setWindow(c, win0[0], win0[1], win0[2]);
            FontsMainMenuSuite.restoreMm(c, saved);
        }
    }

    // ---- 1652 the gradient background ------------------------------------------------------------------------------

    static final String[] VARIANTS = {"FOREST_NIGHT", "MOSS_FLOOR", "CANOPY_GLOW"};
    static final String[] VARIANT_BG = {"FOREST_NIGHT_BG", "MOSS_FLOOR_BG", "CANOPY_GLOW_BG"};
    static final String[] VARIANT_SHOT = {"variant-a-forest-night", "variant-b-moss-floor", "variant-c-canopy-glow"};

    /** The colour the panel's background should have at GUI row {@code y} (pixel centre) in its left margin column, for a
     *  background with {@code stops} spread over y0..y1 and, when {@code ring0} is set, the widest glow ring over it. */
    static int expected(int[] stops, int ring0, int y0, int y1, double y) {
        int n = stops.length - 1;
        int h = y1 - y0;
        int base = stops[n];
        for (int i = 0; i < n; i++) {
            int a = y0 + h * i / n;
            int b = y0 + h * (i + 1) / n;
            if (y >= a && y < b) {
                base = mixRgb(stops[i], stops[i + 1], (float) ((y - a) / (b - a)));
                break;
            }
        }
        if (ring0 != 0) {
            int hh = Math.round(h * 0.55f);
            double f = (y - y0) / hh;
            if (f < 1) {
                float alpha = (float) (((ring0 >>> 24) & 0xFF) * (1 - f) / 255.0);
                base = mixRgb(base, ring0, alpha);
            }
        }
        return base | 0xFF000000;
    }

    static int mixRgb(int a, int b, float t) {
        int r = 0;
        for (int s = 0; s < 24; s += 8) {
            int ca = (a >>> s) & 0xFF;
            int cb = (b >>> s) & 0xFF;
            r |= (Math.round(ca + (cb - ca) * t) & 0xFF) << s;
        }
        return r;
    }

    static boolean within(int a, int b, int tol) {
        for (int sh = 0; sh < 24; sh += 8) {
            if (Math.abs(((a >> sh) & 0xFF) - ((b >> sh) & 0xFF)) > tol) {
                return false;
            }
        }
        return true;
    }

    static void gradient(UiCase c) throws Exception {
        String old = MenuACases.themeName();
        int[] win0 = c.onClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight(),
                mc.options.guiScale().get()});
        Object hud = Mod.cfg("hud.HudConfig");
        boolean oldAuto = (Boolean) Mod.call(hud, "isAutoScale");
        boolean hasGradient = c.onClient(mc -> {
            try {
                return Mod.cls("gui.GreenTheme$Gradient") != null;
            } catch (Throwable t) {
                return false;
            }
        });
        FontsMainMenuSuite.MmState savedMm = FontsMainMenuSuite.saveMm(c);
        String defaultGradient = hasGradient ? c.onClient(mc -> String.valueOf(Mod.staticCall("gui.GreenTheme", "gradient")))
                : "none";
        try {
            // The designer picked C (green-v3-1015: "the last gradient looks the best").
            c.note("default gradient: " + defaultGradient);
            c.check("CANOPY_GLOW".equals(defaultGradient), "the default Green gradient is " + defaultGradient
                    + ", not CANOPY_GLOW (C)");
            // The menu as 940 shoots it: 1920x1080, GUI 2, Auto Scale on, the HUD category with the Health and Mana
            // Bars drawer. On a jar without the gradient this first shot is the "before".
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                Mod.call(hud, "setAutoScale", true);
                Mod.call(Mod.cfg("gui.MenuConfig"), "setTheme", Mod.enumValue("gui.MenuTheme", "GREEN"));
                return null;
            });
            FontsMainMenuSuite.setWindow(c, 1920, 1080, 2);
            c.ctx().getInput().setCursorPos(4, 4); // no hover tooltip in the shots
            Screen s = c.onClient(mc -> {
                try {
                    Screen m = MenuACases.open(mc);
                    Mod.call(m, "selectCategory", "HUD");
                    Mod.call(m, "openFeature", MenuACases.leaf("HealthAndManaBarsTab"));
                    return m;
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            c.ticks(10);
            MenuACases.shot(c, s, "default", "HealthAndManaBarsTab");
            if (!hasGradient) {
                c.problem("the mod has no GreenTheme.Gradient: the Green panel has no gradient background");
                return;
            }

            // Only Green has a background of its own: every other preset, and a set one color off Green's, does not.
            String why = c.onClient(mc -> {
                for (String t : new String[]{"AMBER", "DARK", "LIGHT", "OCEAN"}) {
                    int[] b = (int[]) Mod.call(Mod.enumValue("gui.MenuTheme", t), "base");
                    if (Mod.staticCall("gui.GreenTheme", "background", b[0], b[1], b[2], b[3], b[4]) != null) {
                        return t + " has a gradient background";
                    }
                }
                int[] g = (int[]) Mod.call(Mod.enumValue("gui.MenuTheme", "GREEN"), "base");
                if (Mod.staticCall("gui.GreenTheme", "background", g[0] + 1, g[1], g[2], g[3], g[4]) != null) {
                    return "a Custom set one step off Green's accent has Green's background";
                }
                if (Mod.staticCall("gui.GreenTheme", "background", g[0], g[1], g[2], g[3], g[4]) == null) {
                    return "Green's own colors give no background";
                }
                return null;
            });
            c.check(why == null, why);

            java.awt.image.BufferedImage[] imgs = new java.awt.image.BufferedImage[VARIANTS.length];
            for (int v = 0; v < VARIANTS.length; v++) {
                String variant = VARIANTS[v];
                String constant = VARIANT_BG[v];
                c.onClient(mc -> {
                    Mod.staticCall("gui.GreenTheme", "setGradient", Mod.enumValue("gui.GreenTheme$Gradient", variant));
                    return null;
                });
                String kept = MenuACases.shot(c, s, VARIANT_SHOT[v], "HealthAndManaBarsTab");
                String copyTo = System.getenv("TESTKIT_GREEN_SHOTS");
                if (v == 2 && copyTo != null && !copyTo.isBlank()) {
                    Files.createDirectories(Path.of(copyTo));
                    Files.copy(Path.of(kept), Path.of(copyTo).resolve("menu-variant-c.png"),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                // The background in use is the variant's cached constant, and the same object frames later.
                Object[] bg = c.onClient(mc -> new Object[]{Mod.field("gui.MenuStyle", "PANEL_BG"),
                        Mod.field("gui.GreenTheme", constant)});
                c.ticks(5);
                Object later = c.onClient(mc -> Mod.field("gui.MenuStyle", "PANEL_BG"));
                c.check(bg[0] != null && bg[0] == bg[1], variant + ": the drawn background is not the cached " + constant);
                c.check(later == bg[0], variant + ": the background object changed between frames (built per frame?)");
                if (bg[0] == null) {
                    continue;
                }
                int[] stops = (int[]) Mod.field(bg[0], "panel");
                int[] side = (int[]) Mod.field(bg[0], "side");
                int[] rings = (int[]) Mod.field(bg[0], "ringColours");
                c.check(v != 2 || rings.length > 0, "the canopy glow variant draws no glow");
                c.check(v == 2 || rings.length == 0, variant + " draws a glow");
                // Its pixels: the panel's left margin (one column inside the bevel's light edge) at the top, middle and
                // foot, and the sidebar's empty lower part.
                java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(Path.of(kept).toFile());
                imgs[v] = img;
                double sc = img.getWidth() / (double) s.width;
                int px = (Integer) Mod.field(s, "panelX");
                int py = (Integer) Mod.field(s, "panelY");
                int ph = (Integer) Mod.field(s, "panelH");
                int y0 = py + 1;
                int y1 = py + ph - 1;
                int x = px + 2;
                StringBuilder got = new StringBuilder();
                for (int gy : new int[]{y0 + 3, y0 + (y1 - y0) / 2, y1 - 4}) {
                    int drawn = img.getRGB((int) ((x + 0.25) * sc), (int) ((gy + 0.25) * sc)) | 0xFF000000;
                    int want = expected(stops, 0, y0, y1, gy + 0.25);
                    got.append(String.format(Locale.ROOT, "y%d %s/%s ", gy - py, MenuACases.hex(drawn), MenuACases.hex(want)));
                    c.check(within(drawn, want, 5), variant + ": the panel at row " + (gy - py) + " is drawn "
                            + MenuACases.hex(drawn) + ", the background says " + MenuACases.hex(want));
                    c.check(green(drawn) || MenuACases.lum(drawn) < 0.03f, variant + ": the panel pixel "
                            + MenuACases.hex(drawn) + " is not green");
                }
                int sx = (Integer) Mod.field(s, "sideX");
                int sy = (Integer) Mod.field(s, "sideY");
                int sw = (Integer) Mod.field(s, "sideW");
                int sh = (Integer) Mod.field(s, "sideH");
                int sideY = sy + sh - 12;
                int sideDrawn = img.getRGB((int) ((sx + sw / 2 + 0.25) * sc), (int) ((sideY + 0.25) * sc)) | 0xFF000000;
                int sideWant = expected(side, 0, sy + 1, sy + sh - 1, sideY + 0.25);
                c.check(within(sideDrawn, sideWant, 5), variant + ": the sidebar is drawn " + MenuACases.hex(sideDrawn)
                        + ", the background says " + MenuACases.hex(sideWant));
                c.note(variant + " (" + VARIANT_SHOT[v] + "): panel " + hexes(stops) + ", sidebar/drawer " + hexes(side)
                        + ", well " + hexes((int[]) Mod.field(bg[0], "well"))
                        + (rings.length > 0 ? ", glow " + MenuACases.hex(rings[0]) + " x" + rings.length + " at alpha "
                        + (rings[0] >>> 24) : "") + "; panel drawn/wanted " + got + "; sidebar " + MenuACases.hex(sideDrawn)
                        + "/" + MenuACases.hex(sideWant));
            }

            // C is A's stops plus the glow: in the card well, C is brighter than A in the top centre and the same at the
            // foot (the cards are the same in both, so only well pixels can differ).
            if (imgs[0] != null && imgs[2] != null) {
                double sc = imgs[0].getWidth() / (double) s.width;
                int gx = (Integer) Mod.field(s, "gridX");
                int gyy = (Integer) Mod.field(s, "gridY");
                int gw = (Integer) Mod.field(s, "gridW");
                int gh = (Integer) Mod.field(s, "gridH");
                int top = brighter(imgs[2], imgs[0], (int) ((gx + gw * 0.3) * sc), (int) ((gyy + 4) * sc),
                        (int) ((gx + gw * 0.7) * sc), (int) ((gyy + gh * 0.3) * sc));
                int foot = brighter(imgs[2], imgs[0], (int) ((gx + 4) * sc), (int) ((gyy + gh * 0.8) * sc),
                        (int) ((gx + gw - 8) * sc), (int) ((gyy + gh - 4) * sc));
                c.note("canopy glow: " + top + " well pixels brighter than forest night in the top centre, " + foot
                        + " at the foot");
                c.check(top > 500, "the canopy glow is not drawn: only " + top + " brighter pixels in the well's top centre");
                c.check(foot < 50, "the canopy glow reaches the foot of the well: " + foot + " brighter pixels there");
            }

            // The main menu in Green at the same size.
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                FontsMainMenuSuite.setMm(mc, "MATCH", "GREEN", "AUTO", 100, true);
                McCompat.setScreen(mc, new TitleScreen());
                return null;
            });
            c.ticks(40);
            FontsMainMenuSuite.Pic a = FontsMainMenuSuite.pic(FontsMainMenuSuite.shot(c, "mainmenu"));
            c.note(String.format(Locale.ROOT, "Green main menu 1920x1080: rgb %.1f,%.1f,%.1f", a.r(), a.g(), a.b()));
            c.check(a.g() > a.r() + 4 && a.g() > a.b() + 2, "the Green main menu is not a true green: " + a.r() + ","
                    + a.g() + "," + a.b());
        } finally {
            c.onClient(mc -> {
                if (hasGradient) {
                    Mod.staticCall("gui.GreenTheme", "setGradient", Mod.enumValue("gui.GreenTheme$Gradient", defaultGradient));
                }
                McCompat.setScreen(mc, null);
                Mod.call(Mod.cfg("gui.MenuConfig"), "setTheme", Mod.enumValue("gui.MenuTheme", old));
                Mod.call(Mod.cfg("gui.MenuConfig"), "save");
                Mod.call(hud, "setAutoScale", oldAuto);
                return null;
            });
            FontsMainMenuSuite.restoreMm(c, savedMm);
            FontsMainMenuSuite.setWindow(c, win0[0], win0[1], win0[2]);
        }
    }

    /** Pixels in the box where {@code a}'s green channel is more than 2 above {@code b}'s. */
    static int brighter(java.awt.image.BufferedImage a, java.awt.image.BufferedImage b, int x0, int y0, int x1, int y1) {
        int n = 0;
        for (int y = Math.max(0, y0); y < Math.min(y1, a.getHeight()); y++) {
            for (int x = Math.max(0, x0); x < Math.min(x1, a.getWidth()); x++) {
                if (((a.getRGB(x, y) >> 8) & 0xFF) > ((b.getRGB(x, y) >> 8) & 0xFF) + 2) {
                    n++;
                }
            }
        }
        return n;
    }

    static String hexes(int[] cs) {
        StringBuilder b = new StringBuilder();
        for (int col : cs) {
            b.append(b.isEmpty() ? "" : " > ").append(MenuACases.hex(col));
        }
        return b.toString();
    }

    // ---- 1651 the forest, 1653 its video ----------------------------------------------------------------------------

    static final String SCENES = "mainmenu.MainMenuScenes";

    static void clock(UiCase c, double ms) {
        c.onClient(mc -> {
            Mod.staticCall(SCENES, "setClockForTest", ms);
            return null;
        });
    }

    /** {bytes, frames, quads} the title background allocated over 60 frames. */
    static long[] alloc(UiCase c) {
        c.onClient(mc -> {
            Mod.setField(Mod.ROOT + SCENES, "allocBytes", 0L);
            Mod.setField(Mod.ROOT + SCENES, "allocFrames", 0L);
            Mod.setField(Mod.ROOT + SCENES, "allocQuads", 0L);
            Mod.setField(Mod.ROOT + SCENES, "allocProbe", true);
            return null;
        });
        for (int i = 0; i < 60; i++) {
            clock(c, 2_000_000 + i * (1000.0 / 60.0));
            c.ticks(1);
        }
        return c.onClient(mc -> {
            Mod.setField(Mod.ROOT + SCENES, "allocProbe", false);
            return new long[]{(Long) Mod.field(Mod.ROOT + SCENES, "allocBytes"), (Long) Mod.field(Mod.ROOT + SCENES, "allocFrames"),
                    (Long) Mod.field(Mod.ROOT + SCENES, "allocQuads")};
        });
    }

    /** Luminance (0..1) of a pixel, Rec. 709. */
    static double lum709(int p) {
        return (0.2126 * ((p >> 16) & 0xFF) + 0.7152 * ((p >> 8) & 0xFF) + 0.0722 * (p & 0xFF)) / 255.0;
    }

    /** {mean luminance, median pixel} of a box given in fractions of the image. */
    static double[] boxStats(BufferedImage img, double x0, double y0, double x1, double y1) {
        List<Integer> px = new ArrayList<>();
        double sum = 0;
        for (int y = (int) (img.getHeight() * y0); y < (int) (img.getHeight() * y1); y += 2) {
            for (int x = (int) (img.getWidth() * x0); x < (int) (img.getWidth() * x1); x += 2) {
                int p = img.getRGB(x, y);
                px.add(p);
                sum += lum709(p);
            }
        }
        px.sort((a, b) -> Double.compare(lum709(a), lum709(b)));
        return new double[]{px.isEmpty() ? 0 : sum / px.size(), px.isEmpty() ? 0 : px.get(px.size() / 2)};
    }

    /** Opens the title screen in Green's forest at 1920x1080 GUI 2 with the clock held. */
    static void forestTitle(UiCase c) {
        FontsMainMenuSuite.setWindow(c, 1920, 1080, 2);
        c.onClient(mc -> {
            Mod.call(Mod.cfg("gui.MenuConfig"), "setTheme", FontsMainMenuSuite.menuTheme("GREEN"));
            FontsMainMenuSuite.setMm(mc, "MATCH", "GREEN", "AUTO", 100, true);
            McCompat.setScreen(mc, new TitleScreen());
            return null;
        });
        c.ctx().getInput().setCursorPos(4, 4);
        c.ticks(40);
    }

    /** The forest itself (killer560, 2026-10-15: "can you make the main menu more of a forest?"). */
    static void forest(UiCase c) throws Exception {
        forestTitle(c);
        clock(c, 2_000_000);
        c.ticks(2);
        Path pa = FontsMainMenuSuite.shot(c, "forest-a");
        FontsMainMenuSuite.Pic a = FontsMainMenuSuite.pic(pa);
        clock(c, 2_004_000);
        c.ticks(2);
        FontsMainMenuSuite.Pic b = FontsMainMenuSuite.pic(FontsMainMenuSuite.shot(c, "forest-b"));
        double moved = FontsMainMenuSuite.changed(a.img(), b.img());
        String copy = System.getenv("TESTKIT_GREEN_SHOTS");
        if (copy != null && !copy.isBlank()) {
            Files.createDirectories(Path.of(copy));
            Files.copy(pa, Path.of(copy).resolve("forest-mainmenu.png"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        // Readable: the title band and the column the buttons sit in stay dark and calm.
        double[] title = boxStats(a.img(), 0.25, 0.0, 0.75, 0.22);
        double[] column = boxStats(a.img(), 0.3, 0.22, 0.7, 0.66);
        int text = c.onClient(mc -> ((int[]) Mod.call(Mod.enumValue("gui.MenuTheme", "GREEN"), "base"))[3]);
        float colContrast = contrast(text, (int) column[1] | 0xFF000000);
        float titleContrast = contrast(0xFFFFFFFF, (int) title[1] | 0xFF000000);
        int cx0 = a.img().getWidth() * 3 / 10;
        java.awt.image.BufferedImage colA = a.img().getSubimage(cx0, a.img().getHeight() * 22 / 100, a.img().getWidth() * 4 / 10,
                a.img().getHeight() * 44 / 100);
        java.awt.image.BufferedImage colB = b.img().getSubimage(cx0, b.img().getHeight() * 22 / 100, b.img().getWidth() * 4 / 10,
                b.img().getHeight() * 44 / 100);
        double colMoved = FontsMainMenuSuite.changed(colA, colB);
        long[] alloc = alloc(c);
        double quads = alloc[2] / (double) Math.max(1, alloc[1]);
        double bPerQuad = alloc[0] / (double) Math.max(1, alloc[2]);
        c.note(String.format(Locale.ROOT, "forest: rgb %.1f,%.1f,%.1f lum %.3f, moved %.2f%% over 4 s (button column %.2f%%); "
                        + "title band lum %.3f (white title on its median %.1f:1), button column lum %.3f (Green text on its median %.1f:1); "
                        + "%.0f rects/frame, %.0f B/frame, %.1f B/rect over %d frames",
                a.r(), a.g(), a.b(), a.lum(), moved * 100, colMoved * 100, title[0], titleContrast, column[0], colContrast, quads,
                alloc[0] / (double) Math.max(1, alloc[1]), bPerQuad, alloc[1]));
        int[] geo = c.onClient(mc -> {
            try {
                return new int[]{(Integer) Mod.field("mainmenu.MainMenuFireflies", "trunks"),
                (Integer) Mod.field("mainmenu.MainMenuFireflies", "lobes"), (Integer) Mod.field("mainmenu.MainMenuFireflies", "ferns"),
                (Integer) Mod.field("mainmenu.MainMenuFireflies", "tufts")};
            } catch (AssertionError e) {
                return null; // a jar from before the forest
            }
        });
        c.check(geo != null, "the Fireflies scene has no forest layout (an older jar)");
        c.note("forest geometry: " + geo[0] + " trunks, " + geo[1] + " crown/bush lobes, " + geo[2] + " ferns, " + geo[3]
                + " grass tufts");
        c.check(geo[0] >= 20 && geo[1] >= 60 && geo[2] >= 6 && geo[3] >= 20, "the forest was not laid out: "
                + Arrays.toString(geo));
        int[] layers = c.onClient(mc -> {
            int[] l = (int[]) Mod.field("mainmenu.MainMenuFireflies", "trunkLayer");
            int[] n = new int[3];
            for (int i = 0; i < geo[0]; i++) {
                n[l[i]]++;
            }
            return n;
        });
        c.check(layers[0] > 0 && layers[1] > 0 && layers[2] > 0, "trunks are not in three depth layers: " + Arrays.toString(layers));

        c.check(a.g() > a.r() + 4 && a.g() > a.b() + 2, "the forest is not green-dominant: " + a.r() + "," + a.g() + "," + a.b());
        c.check(a.lum() < 0.3, "the forest is too bright for the menu: " + a.lum());
        c.check(moved > 0.002, String.format(Locale.ROOT, "the forest did not move over 4 s: %.3f%% changed", moved * 100));
        c.check(colMoved < 0.03, String.format(Locale.ROOT, "the button column is busy: %.2f%% changed over 4 s", colMoved * 100));
        c.check(title[0] < 0.2 && titleContrast >= 7f, "the title band is too bright: lum " + title[0] + ", contrast " + titleContrast);
        c.check(column[0] < 0.2 && colContrast >= 7f, "the button column is too bright: lum " + column[0] + ", contrast " + colContrast);
        // Zero allocation beyond the batch's own rectangles: the bytes per rectangle are the batch's (an EMBERS-like
        // ~100 B/rect, testkit 1568); anything the scene built per frame would show up as more.
        c.check(alloc[1] >= 50 && alloc[2] > 0, "the allocation probe did not run: " + Arrays.toString(alloc));
        long[] ref = embersAlloc(c);
        double refPerQuad = ref[0] / (double) Math.max(1, ref[2]);
        c.note(String.format(Locale.ROOT, "Amber embers for reference: %.0f rects/frame, %.1f B/rect", ref[2] / (double) Math.max(1, ref[1]),
                refPerQuad));
        c.check(bPerQuad <= refPerQuad * 1.15, String.format(Locale.ROOT,
                "the forest allocates beyond its rectangles: %.1f B/rect against embers' %.1f", bPerQuad, refPerQuad));
    }

    /** Picks the Green scene through the mod's test hook; "" goes back to the default. Fails the case on a jar without
     *  the hook (before the acid cave), except when resetting. */
    static void greenScene(UiCase c, String name) {
        boolean ok = c.onClient(mc -> {
            try {
                Mod.staticCall(SCENES, "setGreenSceneForTest", name);
                return true;
            } catch (Throwable t) {
                return false;
            }
        });
        if (!ok && !name.isEmpty()) {
            c.problem("the mod has no MainMenuScenes.setGreenSceneForTest: no acid cave scene (an older jar)");
        }
    }

    /** The acid cave (green-v3-1015), the default Green scene. */
    static void acidCave(UiCase c) throws Exception {
        String def = c.onClient(mc -> {
            try {
                return String.valueOf(Mod.field(Mod.ROOT + SCENES, "GREEN_SCENE"));
            } catch (Throwable t) {
                return "none";
            }
        });
        c.note("default Green scene: " + def + " (the acid cave is drawn through the test hook)");
        forestTitle(c);
        clock(c, 2_000_000);
        c.ticks(2);
        Path pa = FontsMainMenuSuite.shot(c, "acid-a");
        FontsMainMenuSuite.Pic a = FontsMainMenuSuite.pic(pa);
        clock(c, 2_004_000);
        c.ticks(2);
        FontsMainMenuSuite.Pic b = FontsMainMenuSuite.pic(FontsMainMenuSuite.shot(c, "acid-b"));
        // Within one swing (1.6 s): the strike against the wind-up.
        clock(c, 2_000_000 + 1600 * 0.30);
        c.ticks(2);
        FontsMainMenuSuite.Pic windup = FontsMainMenuSuite.pic(FontsMainMenuSuite.shot(c, "acid-windup"));
        clock(c, 2_000_000 + 1600 * 0.60);
        c.ticks(2);
        FontsMainMenuSuite.Pic strike = FontsMainMenuSuite.pic(FontsMainMenuSuite.shot(c, "acid-strike"));
        String copy = System.getenv("TESTKIT_GREEN_SHOTS");
        if (copy != null && !copy.isBlank()) {
            Files.createDirectories(Path.of(copy));
            Files.copy(pa, Path.of(copy).resolve("acid-cave.png"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        double moved = FontsMainMenuSuite.changed(a.img(), b.img());
        double[] title = boxStats(a.img(), 0.25, 0.0, 0.75, 0.22);
        double[] column = boxStats(a.img(), 0.3, 0.22, 0.7, 0.66);
        double[] lakeBox = boxStats(a.img(), 0.4, 0.84, 0.7, 0.98);
        int text = c.onClient(mc -> ((int[]) Mod.call(Mod.enumValue("gui.MenuTheme", "GREEN"), "base"))[3]);
        float colContrast = contrast(text, (int) column[1] | 0xFF000000);
        float titleContrast = contrast(0xFFFFFFFF, (int) title[1] | 0xFF000000);
        BufferedImage A = a.img();
        int W = A.getWidth();
        int H = A.getHeight();
        double colMoved = FontsMainMenuSuite.changed(A.getSubimage(W * 3 / 10, H * 22 / 100, W * 4 / 10, H * 44 / 100),
                b.img().getSubimage(W * 3 / 10, H * 22 / 100, W * 4 / 10, H * 44 / 100));
        double fightMoved = FontsMainMenuSuite.changed(windup.img().getSubimage(0, H * 50 / 100, W * 3 / 10, H * 40 / 100),
                strike.img().getSubimage(0, H * 50 / 100, W * 3 / 10, H * 40 / 100));
        int lakeMedian = (int) lakeBox[1];
        long[] alloc = alloc(c);
        double quads = alloc[2] / (double) Math.max(1, alloc[1]);
        double bPerQuad = alloc[0] / (double) Math.max(1, alloc[2]);
        c.note(String.format(Locale.ROOT, "acid cave: rgb %.1f,%.1f,%.1f lum %.3f, moved %.2f%% over 4 s (button column %.2f%%), "
                        + "fight moved %.2f%% between wind-up and strike; lake lum %.3f median %s; title band lum %.3f (white title "
                        + "%.1f:1), button column lum %.3f (Green text %.1f:1); %.0f rects/frame, %.0f B/frame, %.1f B/rect over %d frames",
                a.r(), a.g(), a.b(), a.lum(), moved * 100, colMoved * 100, fightMoved * 100, lakeBox[0], MenuACases.hex(lakeMedian),
                title[0], titleContrast, column[0], colContrast, quads, alloc[0] / (double) Math.max(1, alloc[1]), bPerQuad, alloc[1]));
        c.check(a.g() > a.r() + 4 && a.g() > a.b() + 2, "the acid cave is not green-dominant: " + a.r() + "," + a.g() + "," + a.b());
        c.check(a.lum() < 0.3, "the acid cave is too bright for the menu: " + a.lum());
        c.check(lakeBox[0] > column[0] * 2 && ((lakeMedian >> 8) & 0xFF) > ((lakeMedian >> 16) & 0xFF) + 20,
                "no glowing acid lake along the bottom: lum " + lakeBox[0] + " median " + MenuACases.hex(lakeMedian));
        c.check(moved > 0.002, String.format(Locale.ROOT, "the acid cave did not move over 4 s: %.3f%%", moved * 100));
        c.check(fightMoved > 0.003, String.format(Locale.ROOT, "the fight did not move within a swing: %.3f%%", fightMoved * 100));
        c.check(colMoved < 0.03, String.format(Locale.ROOT, "the button column is busy: %.2f%% changed over 4 s", colMoved * 100));
        c.check(title[0] < 0.2 && titleContrast >= 7f, "the title band is too bright: lum " + title[0] + ", contrast " + titleContrast);
        c.check(column[0] < 0.2 && colContrast >= 7f, "the button column is too bright: lum " + column[0] + ", contrast " + colContrast);
        c.check(alloc[1] >= 50 && alloc[2] > 0, "the allocation probe did not run: " + Arrays.toString(alloc));
        long[] ref = embersAlloc(c);
        double refPerQuad = ref[0] / (double) Math.max(1, ref[2]);
        c.check(bPerQuad <= refPerQuad * 1.15, String.format(Locale.ROOT,
                "the acid cave allocates beyond its rectangles: %.1f B/rect against embers' %.1f", bPerQuad, refPerQuad));
    }

    /** The slime boss (green-v4-1015), the default Green scene, a still for now. */
    static void bossScene(UiCase c) throws Exception {
        String def = c.onClient(mc -> {
            try {
                return String.valueOf(Mod.field(Mod.ROOT + SCENES, "GREEN_SCENE"));
            } catch (Throwable t) {
                return "none";
            }
        });
        c.check("BOSS".equals(def), "the default Green scene is " + def + ", not BOSS");
        forestTitle(c);
        clock(c, 2_000_000);
        c.ticks(2);
        Path pa = FontsMainMenuSuite.shot(c, "boss-a");
        FontsMainMenuSuite.Pic a = FontsMainMenuSuite.pic(pa);
        clock(c, 2_004_000);
        c.ticks(2);
        FontsMainMenuSuite.Pic b = FontsMainMenuSuite.pic(FontsMainMenuSuite.shot(c, "boss-b"));
        String copy = System.getenv("TESTKIT_GREEN_SHOTS");
        if (copy != null && !copy.isBlank()) {
            Files.createDirectories(Path.of(copy));
            Files.copy(pa, Path.of(copy).resolve("boss-scene.png"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        double moved = FontsMainMenuSuite.changed(a.img(), b.img());
        double[] title = boxStats(a.img(), 0.25, 0.0, 0.75, 0.22);
        double[] column = boxStats(a.img(), 0.3, 0.22, 0.7, 0.66);
        double[] bossBox = boxStats(a.img(), 0.74, 0.55, 0.89, 0.78);
        double[] charBox = boxStats(a.img(), 0.115, 0.50, 0.145, 0.62); // the torso (green-v5: a slender smooth figure)
        int text = c.onClient(mc -> ((int[]) Mod.call(Mod.enumValue("gui.MenuTheme", "GREEN"), "base"))[3]);
        float colContrast = contrast(text, (int) column[1] | 0xFF000000);
        float titleContrast = contrast(0xFFFFFFFF, (int) title[1] | 0xFF000000);
        long[] alloc = alloc(c);
        double quads = alloc[2] / (double) Math.max(1, alloc[1]);
        double bPerQuad = alloc[0] / (double) Math.max(1, alloc[2]);
        c.note(String.format(Locale.ROOT, "slime boss: rgb %.1f,%.1f,%.1f lum %.3f, moved %.2f%% over 4 s; boss lum %.3f, character "
                        + "lum %.3f; title band lum %.3f (white title %.1f:1), button column lum %.3f (Green text %.1f:1); %.0f rects/frame, "
                        + "%.0f B/frame, %.1f B/rect over %d frames",
                a.r(), a.g(), a.b(), a.lum(), moved * 100, bossBox[0], charBox[0], title[0], titleContrast, column[0], colContrast,
                quads, alloc[0] / (double) Math.max(1, alloc[1]), bPerQuad, alloc[1]));
        c.check(a.g() > a.r() + 4 && a.g() > a.b() + 2, "the slime boss scene is not green-dominant: " + a.r() + "," + a.g() + "," + a.b());
        c.check(a.lum() < 0.3, "the slime boss scene is too bright for the menu: " + a.lum());
        c.check(moved < 0.002, String.format(Locale.ROOT, "the still moved over 4 s: %.3f%%", moved * 100));
        c.check(bossBox[0] > 0.45, "no glowing boss on the right: lum " + bossBox[0]);
        c.check(charBox[0] < 0.08, "no dark character silhouette on the left: lum " + charBox[0]);
        c.check(title[0] < 0.2 && titleContrast >= 7f, "the title band is too bright: lum " + title[0] + ", contrast " + titleContrast);
        c.check(column[0] < 0.2 && colContrast >= 7f, "the button column is too bright: lum " + column[0] + ", contrast " + colContrast);
        c.check(alloc[1] >= 50 && alloc[2] > 0, "the allocation probe did not run: " + Arrays.toString(alloc));
        long[] ref = embersAlloc(c);
        double refPerQuad = ref[0] / (double) Math.max(1, ref[2]);
        c.check(bPerQuad <= refPerQuad * 1.15, String.format(Locale.ROOT,
                "the slime boss allocates beyond its rectangles: %.1f B/rect against embers' %.1f", bPerQuad, refPerQuad));
    }

    static long[] embersAlloc(UiCase c) {
        c.onClient(mc -> {
            FontsMainMenuSuite.setMm(mc, "CUSTOM", "AMBER", "EMBERS", 100, true);
            Mod.staticCall(SCENES, "setAmberSceneForTest", "EMBERS");
            McCompat.setScreen(mc, new TitleScreen());
            return null;
        });
        c.ticks(10);
        long[] r = alloc(c);
        c.onClient(mc -> {
            Mod.staticCall(SCENES, "setAmberSceneForTest", "");
            return null;
        });
        return r;
    }

    /** 1653: ~10 s of the forest at 60 fps, one PNG per 1/60 s of the scene clock into {@code <report>/green-forest/}.
     *  Only runs when its number is named. */
    static void video(UiCase c) throws Exception {
        if (!System.getProperty("testkit.scenario", "").contains("1653")) {
            c.note("skipped: the video only runs when 1653 is named");
            return;
        }
        FontsMainMenuSuite.MmState saved = FontsMainMenuSuite.saveMm(c);
        int[] win0 = c.onClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight(),
                mc.options.guiScale().get()});
        String old = MenuACases.themeName();
        String scene = System.getProperty("testkit.greenScene", "");
        try {
            if (!scene.isEmpty()) {
                greenScene(c, scene);
            }
            forestTitle(c);
            int frames = 600;
            Path dir = Report.dir().resolve("green-" + (scene.isEmpty() ? "default" : scene.toLowerCase(Locale.ROOT)));
            for (int i = 0; i < frames; i++) {
                clock(c, 3_000_000 + i * (1000.0 / 60.0));
                c.ticks(1);
                MenuAnimSuite.keep(c.ctx().takeScreenshot("green-forest-frame"), dir,
                        String.format(Locale.ROOT, "frame_%05d.png", i));
            }
            c.note("wrote " + frames + " frames to " + dir);
        } finally {
            clock(c, -1);
            if (!scene.isEmpty()) {
                greenScene(c, "");
            }
            c.onClient(mc -> {
                Mod.call(Mod.cfg("gui.MenuConfig"), "setTheme", Mod.enumValue("gui.MenuTheme", old));
                return null;
            });
            FontsMainMenuSuite.setWindow(c, win0[0], win0[1], win0[2]);
            FontsMainMenuSuite.restoreMm(c, saved);
        }
    }
}
