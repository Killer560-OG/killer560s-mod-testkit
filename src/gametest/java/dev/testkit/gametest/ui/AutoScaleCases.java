package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.Report;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 380: the mod's Auto Scale (monitor) - hud/AutoScale plus the hud/mixin Screen and MouseHandler mixins.
 *
 * <p>Three parts, all against the real mod:
 * <ol>
 *   <li>The pure math, {@code AutoScale.factor(w, h, guiScale)}: 1.0 at 2560x1440 / GUI 3 (killer560's baseline
 *       monitor), and the expected values elsewhere.</li>
 *   <li>The real window resized to 2560x1440, 1920x1080 and 3840x2160 at GUI Scale 3 ({@code TestInput.resizeWindow}).
 *       At each size a probe HUD element (a 100x20 magenta block registered into the mod's HudElementRegistry) is
 *       shown in the mod's HUD editor and its DRAWN size is measured in screenshot pixels; the mod menu is opened
 *       and its header underline measured. Both must be the same fraction of the window at every size, and at the
 *       baseline the drawn box must be pixel-identical with Auto Scale off.</li>
 *   <li>A real click (cursor moved in window coordinates, button pressed through the input layer) on a mod menu
 *       sidebar row at its DRAWN position must select that row; and a click at the position the row would have
 *       without scaling must land where the /F mouse transform predicts, which proves the transform is exactly
 *       the one the render uses.</li>
 * </ol>
 */
final class AutoScaleCases {

    private static final String PROBE_ID = "testkit_autoscale_probe";
    private static final int PROBE_W = 100;
    private static final int PROBE_H = 20;
    private static final int PROBE_SAVED_X = 100;
    private static final int PROBE_SAVED_Y = 100;
    private static final int MAGENTA = 0xFFFF00FF;

    private AutoScaleCases() {
    }

    static void autoScale(UiCase c) throws Exception {
        math(c);
        windows(c);
    }

    // ---- 1. the math -----------------------------------------------------------------------------------------

    private static void math(UiCase c) {
        int[][] cases = {
                {2560, 1440, 3}, {1920, 1080, 3}, {3840, 2160, 3}, {2560, 1440, 2}, {2560, 1440, 4},
                {3440, 1440, 3}, {1280, 1024, 3}, {2560, 1361, 3}, {854, 480, 2}};
        float[] want = {1.0f, 0.75f, 1.5f, 1.5f, 0.75f, 1.0f, 0.5f, 1.0f, 0.5f};
        String[] why = {"baseline", "1080p", "4K", "GUI 2", "GUI 4", "ultrawide: height drives", "5:4: width drives",
                "maximised window: dead band", "tiny window: floor 1/guiScale"};
        for (int i = 0; i < cases.length; i++) {
            float got = ((Number) Mod.staticCall("hud.AutoScale", "factor", cases[i][0], cases[i][1], cases[i][2]))
                    .floatValue();
            String line = String.format(Locale.ROOT, "factor(%d x %d, GUI %d) = %.4f, expected %.4f (%s)",
                    cases[i][0], cases[i][1], cases[i][2], got, want[i], why[i]);
            c.note(line);
            if (Math.abs(got - want[i]) > 1e-4) {
                c.problem(line);
            }
        }
        // The baseline is EXACT, not approximately one: 1.0f == 1.0f so every draw path short-circuits to today's.
        float base = ((Number) Mod.staticCall("hud.AutoScale", "factor", 2560, 1440, 3)).floatValue();
        c.check(base == 1.0f, "factor at the baseline is " + base + ", not exactly 1.0");
    }

    // ---- 2 + 3. real window sizes ----------------------------------------------------------------------------

    private record Measured(int w, int h, int gui, float factor, int[] probeBox, int headerRun, float panelW,
                            float layoutFactor) {
    }

    private static void windows(UiCase c) throws Exception {
        Object hud = Mod.staticCall("hud.HudConfig", "getInstance");
        float oldGlobal = ((Number) Mod.call(hud, "getGlobalScale")).floatValue();
        boolean oldShowAll = (Boolean) Mod.call(hud, "isEditorShowAll");
        boolean oldAuto = (Boolean) Mod.call(hud, "isAutoScale");
        int[] oldWindow = c.onClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight()});
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        c.check(oldAuto, "Auto Scale is OFF in a fresh config - it must default ON");
        Object probe = probe();
        try {
            c.onClient(mc -> {
                Mod.call(hud, "setGlobalScale", 1.0f);
                Mod.call(hud, "setEditorShowAll", true);
                Mod.call(hud, "setPosition", PROBE_ID, PROBE_SAVED_X, PROBE_SAVED_Y);
                Mod.staticCall("hud.HudElementRegistry", "register", probe);
                mc.options.guiScale().set(3);
                mc.resizeGui();
                return null;
            });

            Measured base = measure(c, 2560, 1440, true);
            // Pixel-for-pixel at the baseline: the same probe with Auto Scale OFF draws the identical box.
            c.onClient(mc -> {
                Mod.call(hud, "setAutoScale", false);
                return null;
            });
            int[] offBox = probeBox(c, "baseline-auto-off");
            c.onClient(mc -> {
                Mod.call(hud, "setAutoScale", true);
                return null;
            });
            c.note("baseline probe box auto ON " + box(base.probeBox()) + " / auto OFF " + box(offBox));
            if (!java.util.Arrays.equals(base.probeBox(), offBox)) {
                c.problem("at 2560x1440 GUI 3 the probe draws differently with Auto Scale on " + box(base.probeBox())
                        + " and off " + box(offBox) + " - the baseline is not pixel-identical");
            }

            Measured hd = measure(c, 1920, 1080, false);
            Measured uhd = measure(c, 3840, 2160, false);

            for (Measured m : new Measured[]{base, hd, uhd}) {
                compare(c, base, m);
            }
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                Mod.staticCall("hud.HudElementRegistry", "unregister", PROBE_ID);
                Mod.call(hud, "setGlobalScale", oldGlobal);
                Mod.call(hud, "setEditorShowAll", oldShowAll);
                Mod.call(hud, "setAutoScale", oldAuto);
                mc.options.guiScale().set(oldGui);
                return null;
            });
            c.ctx().getInput().resizeWindow(oldWindow[0], oldWindow[1]);
            c.ticks(5);
            c.onClient(mc -> {
                mc.resizeGui();
                return null;
            });
        }
    }

    /** Fractions of the window must match the baseline's: the whole point of the feature. */
    private static void compare(UiCase c, Measured base, Measured m) {
        int probeW = m.probeBox()[2] - m.probeBox()[0] + 1;
        int baseProbeW = base.probeBox()[2] - base.probeBox()[0] + 1;
        double probeFrac = probeW / (double) m.h();
        double baseProbeFrac = baseProbeW / (double) base.h();
        double headerFrac = m.headerRun() / (double) m.h();
        double baseHeaderFrac = base.headerRun() / (double) base.h();
        String line = String.format(Locale.ROOT,
                "%dx%d GUI %d: factor %.3f | probe drawn %d px wide = %.4f of window height (baseline %.4f) | "
                        + "menu underline %d px = %.4f (baseline %.4f)",
                m.w(), m.h(), m.gui(), m.factor(), probeW, probeFrac, baseProbeFrac, m.headerRun(), headerFrac,
                baseHeaderFrac);
        c.note(line);
        // 2% covers whole-pixel rounding (the probe is 300 px wide at the baseline).
        if (Math.abs(probeFrac / baseProbeFrac - 1) > 0.02) {
            c.problem("the probe HUD element is not the baseline's fraction of the window: " + line);
        }
        if (Math.abs(headerFrac / baseHeaderFrac - 1) > 0.02) {
            c.problem("the mod menu is not the baseline's fraction of the window: " + line);
        }
    }

    private static Measured measure(UiCase c, int w, int h, boolean baseline) throws Exception {
        c.ctx().getInput().resizeWindow(w, h);
        c.ticks(3);
        // The window's own resize path keeps whatever scale was computed for the old size until the GUI is
        // re-derived; do what Video Settings does after a change so the option (3) is applied at the new size.
        c.onClient(mc -> {
            mc.options.guiScale().set(3);
            mc.resizeGui();
            return null;
        });
        c.ticks(3);
        int[] real = c.onClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight(),
                mc.getWindow().getGuiScale()});
        c.note("window resized to " + real[0] + "x" + real[1] + ", effective GUI scale " + real[2]);
        c.check(real[0] == w && real[1] == h, "resizeWindow(" + w + ", " + h + ") left the framebuffer at "
                + real[0] + "x" + real[1]);
        c.check(real[2] == 3, "GUI scale is " + real[2] + " at " + w + "x" + h + ", not 3");
        float f = c.onClient(mc -> ((Number) Mod.staticCall("hud.AutoScale", "current")).floatValue());
        float expected = 3f * Math.min(w / 2560f, h / 1440f) / 3f;
        c.check(Math.abs(f - (Math.abs(expected - 1) < 0.06f ? 1f : expected)) < 1e-4,
                "AutoScale.current() = " + f + " at " + w + "x" + h + ", expected " + expected);

        // The saved position is in baseline units: drawn at saved * factor.
        int[] resolved = c.onClient(mc -> (int[]) Mod.staticCall("hud.HudElementRegistry", "resolvePosition",
                HudProbe.element));
        c.note(String.format(Locale.ROOT, "probe saved at (%d,%d), resolved to (%d,%d) at factor %.3f",
                PROBE_SAVED_X, PROBE_SAVED_Y, resolved[0], resolved[1], f));
        if (resolved[0] != Math.round(PROBE_SAVED_X * f) || resolved[1] != Math.round(PROBE_SAVED_Y * f)) {
            c.problem("resolvePosition did not scale the saved position: " + resolved[0] + "," + resolved[1]);
        }

        int[] probeBox = probeBox(c, w + "x" + h);
        float drawScale = c.onClient(mc -> ((Number) Mod.staticCall("hud.HudElementRegistry", "resolveScale",
                HudProbe.element)).floatValue());
        int wantPx = Math.round(PROBE_W * drawScale * 3);
        int gotPx = probeBox[2] - probeBox[0] + 1;
        c.note(String.format(Locale.ROOT, "probe resolveScale %.3f -> expected %d px wide, drawn %d px %s", drawScale,
                wantPx, gotPx, box(probeBox)));
        if (Math.abs(gotPx - wantPx) > 2) {
            c.problem("HUD editor drew the probe " + gotPx + " px wide, resolveScale says " + wantPx
                    + " - the editor does not draw at the in-game scale");
        }

        // The mod menu: header underline width, then real clicks.
        Screen menu = c.onClient(mc -> {
            try {
                Class<?> cls = R.cls(ModScreenDriver.MOD_SCREEN);
                R.setStatic(cls, "searchQuery", "");
                R.setStatic(cls, "scrollOffset", 0);
                R.setStatic(cls, "selectedTab", 0);
                Screen s = (Screen) cls.getConstructor(Screen.class).newInstance((Object) null);
                McCompat.setScreen(mc, s);
                return s;
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        c.ticks(5);
        float layoutF = c.onClient(mc -> ((Number) Mod.staticCall("hud.AutoScale", "appliedFactor", menu))
                .floatValue());
        int panelX = (Integer) R.get(menu, "panelX");
        int panelY = (Integer) R.get(menu, "panelY");
        int panelW = (Integer) R.get(menu, "panelW");
        c.note(String.format(Locale.ROOT, "menu laid out at %dx%d (GUI area %dx%d) with factor %.3f, panel %d wide",
                menu.width, menu.height, real[0] / 3, real[1] / 3, layoutF, panelW));
        c.check(layoutF == f, "the menu was laid out with factor " + layoutF + ", HUD factor is " + f);
        Path shot = c.ctx().takeScreenshot(Report.fileName(c.name() + "-menu-" + w + "x" + h));
        Report.screenshot(c.name(), shot);
        int headerRun = longestOrangeRun(shot);
        int wantRun = Math.round(panelW * layoutF * 3);
        c.note("menu header underline drawn " + headerRun + " px, expected " + wantRun);
        if (Math.abs(headerRun - wantRun) > 3) {
            c.problem("menu underline drawn " + headerRun + " px at " + w + "x" + h + ", layout says " + wantRun);
        }

        clickRows(c, menu, panelX, panelY, layoutF, baseline);
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            return null;
        });
        c.ticks(2);
        return new Measured(w, h, real[2], f, probeBox, headerRun, panelW, layoutF);
    }

    /** Row {@code i} of the sidebar: x = panelX+8, y = panelY+40+24i, 94x20 (gui/ModScreen.rebuild). */
    private static void clickRows(UiCase c, Screen menu, int panelX, int panelY, float f, boolean baseline) {
        Class<?> cls = R.cls(ModScreenDriver.MOD_SCREEN);
        // Row 2, or the last row when there are fewer (a testing jar shows only Home and Untested at first).
        int target = Math.min(2, ((java.util.List<?>) R.getStatic(cls, "tabs")).size() - 1);
        double cx = panelX + 8 + 47;
        double cy = panelY + 40 + 24 * target + 10;
        int got = clickAt(c, cls, cx * f, cy * f);
        c.note(String.format(Locale.ROOT, "click at the DRAWN centre of sidebar row %d (layout %.0f,%.0f -> GUI %.1f,%.1f)"
                + " selected tab %d", target, cx, cy, cx * f, cy * f, got));
        if (got != target) {
            c.problem("a click on sidebar row " + target + " at its drawn position selected tab " + got
                    + " (factor " + f + ")");
        }
        if (baseline) {
            return;
        }
        // Control: click where row 4 would be WITHOUT scaling. The screen sees that point divided by f, so the row
        // it hits is predictable - and is not row 4 unless the transform is missing.
        int control = 4;
        double ux = panelX + 8 + 47;
        double uy = panelY + 40 + 24 * control + 10;
        double seenX = ux / f;
        double seenY = uy / f;
        int predicted = -1;
        if (seenX >= panelX + 8 && seenX < panelX + 8 + 94) {
            int rows = ((java.util.List<?>) R.getStatic(cls, "tabs")).size();
            for (int i = 0; i < rows; i++) {
                double top = panelY + 40 + 24 * i;
                if (seenY >= top && seenY < top + 20) {
                    predicted = i;
                }
            }
        }
        int expect = predicted >= 0 ? predicted : 0;
        int hit = clickAt(c, cls, ux, uy);
        c.note(String.format(Locale.ROOT, "control: click at row %d's UNSCALED spot (GUI %.0f,%.0f) is seen at layout "
                + "%.1f,%.1f; predicted tab %d, selected %d", control, ux, uy, seenX, seenY, expect, hit));
        if (hit != expect) {
            c.problem("the unscaled-position control click selected " + hit + ", the /f transform predicts " + expect);
        }
        if (hit == control) {
            c.problem("a click at the UNSCALED position still hit row " + control + " - the mouse is not transformed");
        }
    }

    /** Resets the selection, clicks at GUI coordinates (gx, gy) through the real input layer, returns the tab now
     *  selected. Window (cursor) coordinates come from the window's own GUI-to-screen ratio. */
    private static int clickAt(UiCase c, Class<?> cls, double gx, double gy) {
        R.setStatic(cls, "selectedTab", 0);
        double[] win = c.onClient(mc -> {
            Window w = mc.getWindow();
            return new double[]{gx * w.getScreenWidth() / (double) w.getGuiScaledWidth(),
                    gy * w.getScreenHeight() / (double) w.getGuiScaledHeight()};
        });
        c.ctx().getInput().setCursorPos(win[0], win[1]);
        c.ticks(1);
        c.ctx().getInput().pressMouse(0);
        c.ticks(3);
        return (Integer) R.getStatic(cls, "selectedTab");
    }

    /** Opens the HUD editor with the probe listed and returns the magenta bounding box {x0, y0, x1, y1} in px. */
    private static int[] probeBox(UiCase c, String tag) throws Exception {
        c.onClient(mc -> {
            try {
                Screen editor = (Screen) Mod.cls("hud.HudEditorScreen").getConstructor(Screen.class)
                        .newInstance((Object) null);
                McCompat.setScreen(mc, editor);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
            return null;
        });
        c.ticks(5);
        Path shot = c.ctx().takeScreenshot(Report.fileName(c.name() + "-hud-" + tag));
        Report.screenshot(c.name(), shot);
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            return null;
        });
        BufferedImage img = javax.imageio.ImageIO.read(shot.toFile());
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = -1, y1 = -1;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                if (r >= 0xF0 && g <= 0x20 && b >= 0xF0) {
                    x0 = Math.min(x0, x);
                    y0 = Math.min(y0, y);
                    x1 = Math.max(x1, x);
                    y1 = Math.max(y1, y);
                }
            }
        }
        c.check(x1 >= 0, "the probe never drew in the HUD editor at " + tag + " (" + img.getWidth() + "x"
                + img.getHeight() + " screenshot)");
        return new int[]{x0, y0, x1, y1};
    }

    /** Longest horizontal run of the theme orange 0xCC6600 - the menu header's underline. */
    private static int longestOrangeRun(Path shot) throws java.io.IOException {
        BufferedImage img = javax.imageio.ImageIO.read(shot.toFile());
        int best = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            int run = 0;
            for (int x = 0; x < img.getWidth(); x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                if (Math.abs(r - 0xCC) <= 10 && Math.abs(g - 0x66) <= 10 && b <= 10) {
                    run++;
                    best = Math.max(best, run);
                } else {
                    run = 0;
                }
            }
        }
        return best;
    }

    private static String box(int[] b) {
        return "[" + b[0] + "," + b[1] + " .. " + b[2] + "," + b[3] + "]";
    }

    // ---- the probe element -----------------------------------------------------------------------------------

    /** Holder so the measurement code can pass the same proxy to resolvePosition/resolveScale. */
    private static final class HudProbe {
        static Object element;
    }

    /** A real {@code hud.HudElement} (a dynamic proxy of the mod's interface) that draws a 100x20 magenta block. */
    private static Object probe() {
        Class<?> iface = Mod.cls("hud.HudElement");
        Object p = Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, (self, m, args) ->
                switch (m.getName()) {
                    case "id" -> PROBE_ID;
                    case "displayName" -> "Testkit Probe";
                    case "defaultX" -> PROBE_SAVED_X;
                    case "defaultY" -> PROBE_SAVED_Y;
                    case "width" -> PROBE_W;
                    case "height" -> PROBE_H;
                    case "defaultScale" -> 1.0f;
                    case "isEnabledInSettings" -> true;
                    case "render" -> {
                        GuiGraphicsExtractor g = (GuiGraphicsExtractor) args[0];
                        int x = (Integer) args[1];
                        int y = (Integer) args[2];
                        g.fill(x, y, x + PROBE_W, y + PROBE_H, MAGENTA);
                        yield null;
                    }
                    case "hashCode" -> System.identityHashCode(self);
                    case "equals" -> self == args[0];
                    case "toString" -> "TestkitAutoScaleProbe";
                    default -> null;
                });
        HudProbe.element = p;
        return p;
    }
}
