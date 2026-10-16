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
 * </ul>
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class GreenThemeSuite implements FabricClientGameTest {

    static final String[] CASES = {"1650-ui-green-theme", "1651-ui-green-mainmenu", "1652-ui-green-gradient"};

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
        try {
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
                FontsMainMenuSuite.setMm(mc, "MATCH", "GREEN", "AUTO", 100, true, true);
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
                    Mod.staticCall("gui.GreenTheme", "setGradient", Mod.enumValue("gui.GreenTheme$Gradient", "FOREST_NIGHT"));
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
}
