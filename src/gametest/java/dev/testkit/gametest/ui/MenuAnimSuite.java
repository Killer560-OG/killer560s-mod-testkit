package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.ModUnderTest;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.gametest.mod.Quiet;
import dev.testkit.harness.Report;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * 1590-1596: the mod menu's animations (mod menu-anim-1015, killer560 2026-10-15: "for the mod menu can you make
 * animations for it smooth"). Every case drives the mod's animation clock itself ({@code gui.MenuAnim.setClockForTest}):
 * the gametest lockstep renders one frame per tick as fast as it can, so wall time says nothing about what a frame shows.
 * <ul>
 *   <li>1590 frame rate: a card switch's knob, the grid's eased scroll and the drawer's slide, each run once at 30 FPS
 *       and once at 300 FPS (one rendered frame per clock step). At the same elapsed time both show the same value, part
 *       way (so it IS animating), and at 200 ms both are exactly at the final state.</li>
 *   <li>1591 the Animations setting: Off is instant (same clock, final state), Reduced moves nothing (the knob and the
 *       drawer are in place at once) but still fades (the switch colour and the drawer's opacity part way), the row in
 *       the Menu Theme drawer steps it, and it survives a config reload.</li>
 *   <li>1592 every animation reaches its final state within its duration and is part way before: a hover by the real
 *       cursor, a drawer row's switch (handed to the rebuilt row), the drawer closing, the drawer's eased scroll, the menu
 *       opening, the tooltip, and closing - the menu is gone the instant Escape is pressed, only its picture fades.</li>
 *   <li>1593 clicks during an animation act at once and land on the FINAL layout: a drawer row pressed mid-slide, a card
 *       switch pressed mid page change, a card switch pressed mid scroll.</li>
 *   <li>1594 allocation: bytes allocated by the render thread per frame with the menu idle, Smooth against Off (the
 *       animation path must add nothing), and while animating; prints the numbers. On a jar without animations it prints
 *       the per-tick number and fails for want of the clock.</li>
 *   <li>1595 screenshot sequences (open, hover, drawer, page change, switch) into the report's screens folder.</li>
 *   <li>1596 (only when named: {@code -Pscenario=1596-menuanim-video}) one PNG per 1/60 s of animation clock for a
 *       scripted ~25 s tour, in {@code <report>/menu-anim-video/}, for ffmpeg.</li>
 * </ul>
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class MenuAnimSuite implements FabricClientGameTest {

    static final String[] CASES = {"1590-ui-menuanim-framerate", "1591-ui-menuanim-setting", "1592-ui-menuanim-final",
            "1593-ui-menuanim-clicks", "1594-ui-menuanim-alloc", "1595-ui-menuanim-shots"};
    static final String VIDEO = "1596-menuanim-video";

    static final String ANIM = "gui.MenuAnim";
    static final String SCREEN = "gui.ModScreen";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (dev.testkit.harness.ScenarioList.active() || dev.testkit.harness.ModGate.missing() != null) {
            for (String n : CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            dev.testkit.gametest.Scenario.skip(VIDEO);
            return;
        }
        boolean any = false;
        for (String n : CASES) {
            any |= UiCase.selected(n);
        }
        boolean video = videoSelected();
        if (!any && !video) {
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
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            ctx.waitTicks(40);
            SafeWorld.apply(ctx);
            UiCase.run(ctx, "1594-ui-menuanim-alloc", MenuAnimSuite::alloc);
            UiCase.run(ctx, "1590-ui-menuanim-framerate", MenuAnimSuite::framerate);
            UiCase.run(ctx, "1591-ui-menuanim-setting", MenuAnimSuite::setting);
            UiCase.run(ctx, "1592-ui-menuanim-final", MenuAnimSuite::finalStates);
            UiCase.run(ctx, "1593-ui-menuanim-clicks", MenuAnimSuite::clicks);
            UiCase.run(ctx, "1595-ui-menuanim-shots", MenuAnimSuite::shots);
            if (video) {
                UiCase.run(ctx, VIDEO, MenuAnimVideo::record);
            }
        }
    }

    /** The video only runs when its name is in the filter (it takes minutes and writes ~1500 PNGs). */
    private static boolean videoSelected() {
        String filter = System.getProperty("testkit.scenario", "");
        return filter.contains(VIDEO);
    }

    // =================================================================================================================
    // shared
    // =================================================================================================================

    /** Fixes the menu's animation clock at {@code ms} (re-sampled for the current frame too). */
    static void clock(UiCase c, double ms) {
        c.onClient(mc -> {
            Mod.staticCall(ANIM, "setClockForTest", ms);
            return null;
        });
    }

    /** Sets the clock and lets exactly one frame render at it. */
    static void frame(UiCase c, double ms) {
        clock(c, ms);
        c.ticks(1);
    }

    static void releaseClock(UiCase c) {
        c.onClient(mc -> {
            Mod.staticCall(ANIM, "setClockForTest", -1.0);
            return null;
        });
    }

    static void mode(String m) {
        Object cfg = Mod.cfg("gui.MenuConfig");
        Mod.call(cfg, "setAnimations", Mod.enumValue("gui.MenuAnim$Mode", m));
    }

    static String mode() {
        return ((Enum<?>) Mod.call(Mod.cfg("gui.MenuConfig"), "getAnimations")).name();
    }

    static float[] state(UiCase c, Screen s) {
        return c.onClient(mc -> (float[]) Mod.call(s, "animState"));
    }

    static float[] card(UiCase c, Screen s, Object tab) {
        return c.onClient(mc -> (float[]) Mod.call(s, "cardAnimState", tab));
    }

    static Boolean cardState(Object tab) {
        return (Boolean) Mod.staticCall("gui.FeatureCards", "state", tab);
    }

    /** A fresh menu on {@code category} with the drawer closed and both scrolls at the top, settled at {@code ms}. */
    static Screen menu(UiCase c, String category, double ms) {
        clock(c, ms);
        Screen s = c.onClient(mc -> {
            try {
                Screen m = MenuACases.open(mc);
                Mod.call(m, "closeDrawer");
                Mod.setField(Mod.ROOT + SCREEN, "gridScroll", 0);
                if (!(Boolean) Mod.call(m, "selectCategory", category)) {
                    throw new AssertionError("no sidebar entry " + category);
                }
                return m;
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        frame(c, ms);
        frame(c, ms + 1000);
        return s;
    }

    /** The first card on the page that has a switch. */
    @SuppressWarnings("unchecked")
    static Object switchCard(UiCase c, Screen s, int skip) {
        return c.onClient(mc -> {
            int seen = 0;
            for (Object tab : (List<Object>) Mod.call(s, "cards")) {
                if (cardState(tab) != null && seen++ == skip) {
                    return tab;
                }
            }
            throw new AssertionError("no card with a switch on this page");
        });
    }

    static int[][] bounds(UiCase c, Screen s, Object tab) {
        return c.onClient(mc -> (int[][]) Mod.call(s, "cardBounds", tab));
    }

    static void pressCenter(UiCase c, Screen s, int[] b) {
        c.onClient(mc -> {
            MenuACases.press(s, b[0] + b[2] / 2.0, b[1] + b[3] / 2.0);
            return null;
        });
    }

    /** A point on a card's body away from its switch and star (a click there opens the drawer). */
    static void pressBody(UiCase c, Screen s, Object tab) {
        int[][] b = bounds(c, s, tab);
        c.onClient(mc -> {
            MenuACases.press(s, b[0][0] + b[0][2] / 3.0, b[0][1] + b[0][3] / 2.0);
            return null;
        });
    }

    /** Moves the real cursor onto a point of the menu's layout (GUI scale times the menu's Auto Scale factor). */
    static void cursor(UiCase c, Screen s, double gx, double gy) {
        double[] at = c.onClient(mc -> {
            double f = ((Number) Mod.staticCall("hud.AutoScale", "appliedFactor", s)).doubleValue();
            double scale = mc.getWindow().getGuiScale() * f;
            return new double[]{gx * scale, gy * scale};
        });
        c.ctx().getInput().setCursorPos(at[0], at[1]);
    }

    static void cursorAway(UiCase c) {
        c.ctx().getInput().setCursorPos(2, 2);
    }

    /** The drawer's switch rows that are on screen (in the pane's visible part). */
    static List<AbstractWidget> switchRows(UiCase c, Screen s) {
        return c.onClient(mc -> {
            List<AbstractWidget> out = new java.util.ArrayList<>();
            Object pane = Mod.field(s, "contentPane");
            int top = ((AbstractWidget) pane).getY();
            int bottom = top + ((AbstractWidget) pane).getHeight();
            for (AbstractWidget w : MenuACases.paneRows(s)) {
                if (w.visible && w.active && w.getClass().getSimpleName().equals("SettingsButtonWidget")
                        && (Boolean) Mod.call(w, "isSwitch") && w.getY() > top && w.getY() + w.getHeight() < bottom) {
                    out.add(w);
                }
            }
            return out;
        });
    }

    static String rowLabel(AbstractWidget w) {
        return (String) Mod.call(Mod.call(w, "parts"), "label");
    }

    static AbstractWidget rowByLabel(UiCase c, Screen s, String label) {
        for (AbstractWidget w : switchRows(c, s)) {
            if (rowLabel(w).equals(label)) {
                return w;
            }
        }
        return null;
    }

    static float tween(Object rowAnim, String name) {
        return ((Number) Mod.call(Mod.field(rowAnim, name), "get")).floatValue();
    }

    static boolean between(float v, float a, float b) {
        float lo = Math.min(a, b);
        float hi = Math.max(a, b);
        return v > lo + 1e-3f && v < hi - 1e-3f;
    }

    static String f(float v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }

    // =================================================================================================================
    // 1590 frame rate
    // =================================================================================================================

    private static double base = 100_000;

    /** Runs {@code action} at a fresh clock base, then renders one frame every 1000/fps ms up to 200 ms, and returns the
     *  probe at 0 (right after the action, before any frame), 100 and 200 ms. Frames rendered are counted in [3]. */
    static float[] series(UiCase c, double fps, Runnable action, Function<Minecraft, Float> probe) {
        base += 10_000;
        frame(c, base);
        frame(c, base);
        clock(c, base);
        c.onClient(mc -> {
            action.run();
            return null;
        });
        float[] out = new float[4];
        out[0] = c.onClient(probe::apply);
        int frames = 0;
        for (int k = 1; ; k++) {
            double t = k * 1000.0 / fps;
            if (t > 200.0 + 1e-6) {
                break;
            }
            frame(c, base + t);
            frames++;
            if (Math.abs(t - 100.0) < 1e-6) {
                out[1] = c.onClient(probe::apply);
            }
            if (Math.abs(t - 200.0) < 1e-6) {
                out[2] = c.onClient(probe::apply);
            }
        }
        out[3] = frames;
        return out;
    }

    static void compare(UiCase c, String what, float[] at30, float[] at300, float start, float end, float tol) {
        c.note(what + ": start " + f(start) + " end " + f(end) + " | 30 FPS (" + (int) at30[3] + " frames) 100ms="
                + f(at30[1]) + " 200ms=" + f(at30[2]) + " | 300 FPS (" + (int) at300[3] + " frames) 100ms=" + f(at300[1])
                + " 200ms=" + f(at300[2]));
        c.check(Math.abs(at30[1] - at300[1]) <= tol, what + ": at 100 ms 30 FPS shows " + f(at30[1]) + " but 300 FPS "
                + f(at300[1]) + " - the animation depends on the frame count");
        c.check(between(at30[1], start, end), what + ": at 100 ms it is not part way (" + f(at30[1]) + " between "
                + f(start) + " and " + f(end) + ") - nothing animates");
        c.check(at30[2] == end && at300[2] == end, what + ": not at its final state " + f(end) + " at 200 ms (30 FPS "
                + f(at30[2]) + ", 300 FPS " + f(at300[2]) + ")");
    }

    static void framerate(UiCase c) throws Exception {
        String was = mode();
        mode("SMOOTH");
        try {
            Screen s = menu(c, "Chat", 50_000);
            cursorAway(c);
            Object tab = switchCard(c, s, 0);
            boolean on = cardState(tab);
            int[] sw = bounds(c, s, tab)[1];
            float start = on ? 1f : 0f;
            float end = on ? 0f : 1f;
            Function<Minecraft, Float> knob = mc -> ((float[]) Mod.call(s, "cardAnimState", tab))[1];
            float[] k30 = series(c, 30, () -> MenuACases.press(s, sw[0] + sw[2] / 2.0, sw[1] + sw[3] / 2.0), knob);
            c.check(cardState(tab) != on, "the card switch press did not flip " + MenuACases.name(tab));
            series(c, 300, () -> MenuACases.press(s, sw[0] + sw[2] / 2.0, sw[1] + sw[3] / 2.0), knob); // back
            float[] k300 = series(c, 300, () -> MenuACases.press(s, sw[0] + sw[2] / 2.0, sw[1] + sw[3] / 2.0), knob);
            series(c, 30, () -> MenuACases.press(s, sw[0] + sw[2] / 2.0, sw[1] + sw[3] / 2.0), knob); // back
            c.check(cardState(tab) == on, "the card switch was not put back");
            compare(c, "card switch knob (" + MenuACases.name(tab) + ")", k30, k300, start, end, 0.003f);

            // the grid's eased scroll: Dungeons has rows of cards to scroll through
            Screen g = menu(c, "Dungeons", base + 5000);
            int gridMax = c.onClient(mc -> (Integer) Mod.field(g, "gridMax"));
            c.check(gridMax >= 60, "the Dungeons grid scrolls only " + gridMax + " units, not enough to measure");
            int gx = c.onClient(mc -> (Integer) Mod.field(g, "gridX") + 20);
            int gy = c.onClient(mc -> (Integer) Mod.field(g, "gridY") + 30);
            Function<Minecraft, Float> drawn = mc -> ((float[]) Mod.call(g, "animState"))[5];
            float[] g30 = series(c, 30, () -> g.mouseScrolled(gx, gy, 0, -3), drawn);
            int target = c.onClient(mc -> (Integer) Mod.field(Mod.ROOT + SCREEN, "gridScroll"));
            series(c, 30, () -> g.mouseScrolled(gx, gy, 0, 3), drawn);
            float[] g300 = series(c, 300, () -> g.mouseScrolled(gx, gy, 0, -3), drawn);
            series(c, 300, () -> g.mouseScrolled(gx, gy, 0, 3), drawn);
            compare(c, "grid scroll", g30, g300, 0f, target, 0.05f);

            // the drawer sliding in
            Object open = switchCard(c, g, 0);
            Function<Minecraft, Float> slide = mc -> ((float[]) Mod.call(g, "animState"))[2];
            float[] d30 = series(c, 30, () -> {
                int[][] b = (int[][]) Mod.call(g, "cardBounds", open);
                MenuACases.press(g, b[0][0] + b[0][2] / 3.0, b[0][1] + b[0][3] / 2.0);
            }, slide);
            c.check(c.onClient(mc -> Mod.call(g, "openFeature")) == open, "a click on the card did not open its drawer");
            series(c, 30, () -> Mod.call(g, "closeDrawer"), slide);
            float[] d300 = series(c, 300, () -> {
                int[][] b = (int[][]) Mod.call(g, "cardBounds", open);
                MenuACases.press(g, b[0][0] + b[0][2] / 3.0, b[0][1] + b[0][3] / 2.0);
            }, slide);
            series(c, 300, () -> Mod.call(g, "closeDrawer"), slide);
            compare(c, "drawer slide", d30, d300, 1f, 0f, 0.003f);
        } finally {
            mode(was);
            releaseClock(c);
        }
    }

    // =================================================================================================================
    // 1591 the setting
    // =================================================================================================================

    static void setting(UiCase c) throws Exception {
        String was = mode();
        try {
            // ---- Off: everything is already at its target on the very frame the change is made ----
            mode("OFF");
            Screen s = menu(c, "Chat", base + 20_000);
            base += 20_000;
            cursorAway(c);
            float[] st = state(c, s);
            c.note("Off, fresh menu: open fade " + f(st[0]) + " grow " + f(st[1]));
            Object tab = switchCard(c, s, 0);
            boolean on = cardState(tab);
            int[] sw = bounds(c, s, tab)[1];
            double t = base + 30_000;
            clock(c, t);
            Screen fresh = c.onClient(mc -> {
                try {
                    return MenuACases.open(mc);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            frame(c, t);
            float[] o = state(c, fresh);
            c.check(o[0] == 1f && o[1] == 1f, "Off: a menu opened this frame is not fully shown (fade " + f(o[0])
                    + ", grow " + f(o[1]) + ")");
            c.onClient(mc -> {
                Mod.call(fresh, "selectCategory", "Chat");
                return null;
            });
            frame(c, t);
            pressCenter(c, fresh, bounds(c, fresh, tab)[1]);
            frame(c, t);
            float[] k = card(c, fresh, tab);
            float want = on ? 0f : 1f;
            c.check(k[1] == want && k[2] == want, "Off: the card switch is not at once at " + want + " (knob " + f(k[1])
                    + ", colour " + f(k[2]) + ")");
            pressCenter(c, fresh, sw);
            frame(c, t);
            pressBody(c, fresh, tab);
            frame(c, t);
            float[] d = state(c, fresh);
            c.check(d[2] == 0f && d[3] == 1f, "Off: the drawer is not in place at once (slide " + f(d[2]) + ", fade "
                    + f(d[3]) + ")");
            c.onClient(mc -> {
                Mod.call(fresh, "closeDrawer");
                return null;
            });
            frame(c, t);
            c.check(state(c, fresh)[7] == 0f, "Off: the closed drawer is still sliding out");
            c.note("Off: open, card switch, drawer open and close all final on the same clock");

            // ---- Reduced: nothing moves, colours and opacity still fade ----
            mode("SMOOTH");
            frame(c, t + 2000);
            mode("REDUCED");
            double r = t + 5000;
            clock(c, r);
            pressCenter(c, fresh, sw);
            frame(c, r + 40);
            float[] rk = card(c, fresh, tab);
            c.note("Reduced, 40 ms after a switch: knob " + f(rk[1]) + " colour " + f(rk[2]));
            c.check(rk[1] == want, "Reduced: the knob moved instead of jumping (" + f(rk[1]) + ")");
            c.check(between(rk[2], 1f - want, want), "Reduced: the switch colour did not fade (" + f(rk[2]) + ")");
            frame(c, r + 1000);
            pressCenter(c, fresh, sw);
            frame(c, r + 2000);
            clock(c, r + 3000);
            pressBody(c, fresh, tab);
            frame(c, r + 3040);
            float[] rd = state(c, fresh);
            c.note("Reduced, 40 ms after opening the drawer: slide " + f(rd[2]) + " fade " + f(rd[3]));
            c.check(rd[2] == 0f, "Reduced: the drawer slid (" + f(rd[2]) + ")");
            c.check(between(rd[3], 0f, 1f), "Reduced: the drawer did not fade in (" + f(rd[3]) + ")");
            c.onClient(mc -> {
                Mod.call(fresh, "closeDrawer");
                return null;
            });
            frame(c, r + 5000);
            c.check(cardState(tab) == on, "the card was not put back");

            // ---- the row in the Menu Theme drawer, and a reload ----
            mode("SMOOTH");
            Object themeTab = MenuACases.leaf("MenuThemeTab");
            AbstractWidget row = c.onClient(mc -> {
                Mod.call(fresh, "openFeature", themeTab);
                for (AbstractWidget w : MenuACases.paneRows(fresh)) {
                    if (w.getMessage().getString().startsWith("Animations")) {
                        return w;
                    }
                }
                return null;
            });
            frame(c, r + 6000);
            c.check(row != null, "the Menu Theme drawer has no Animations row");
            if (row != null) {
                String label = row.getMessage().getString();
                c.check(label.contains("Smooth"), "the Animations row reads '" + label + "' while the setting is Smooth");
                int[] pb = c.onClient(mc -> (int[]) Mod.call(row, "controlBounds"));
                // the pill's right half steps forward: Smooth -> Reduced
                c.onClient(mc -> {
                    MenuACases.press(fresh, pb[0] + pb[2] * 0.75, pb[1] + pb[3] / 2.0);
                    return null;
                });
                c.check(mode().equals("REDUCED"), "pressing the Animations row's right half gave " + mode());
                c.onClient(mc -> {
                    Mod.staticCall("gui.MenuConfig", "load");
                    return null;
                });
                c.check(mode().equals("REDUCED"), "after a reload the setting is " + mode() + ", not REDUCED");
                String tip = c.onClient(mc -> (String) Mod.staticCall("gui.SettingTooltips", "describe",
                        Mod.staticCall(SCREEN, "tooltipCategory", row), row,
                        row.getMessage().getString()));
                c.note("tooltip: " + tip);
                c.check(tip != null && tip.contains("Smooth"), "the Animations row has no tooltip of its own: " + tip);
            }
        } finally {
            mode(was);
            Mod.call(Mod.cfg("gui.MenuConfig"), "save");
            releaseClock(c);
        }
    }

    // =================================================================================================================
    // 1592 final states
    // =================================================================================================================

    static void finalStates(UiCase c) throws Exception {
        String was = mode();
        mode("SMOOTH");
        try {
            base += 50_000;
            // ---- a menu at rest draws everything fully: a fresh menu's page, nothing left half faded ----
            clock(c, base);
            Screen rest = c.onClient(mc -> {
                try {
                    return MenuACases.open(mc);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            frame(c, base);
            frame(c, base + 500);
            float[] r = state(c, rest);
            c.note("fresh menu at rest: open " + f(r[0]) + ", page " + f(r[6]) + ", rows " + f(r[11]));
            c.check(r[0] == 1f && r[6] == 1f, "a fresh menu at rest is not fully drawn (open " + f(r[0]) + ", page "
                    + f(r[6]) + ")");
            Screen s = menu(c, "Chat", base + 600);
            cursorAway(c);
            frame(c, base + 3500);

            // ---- hover with the real cursor ----
            Object tab = switchCard(c, s, 1);
            int[][] b = bounds(c, s, tab);
            double h0 = base + 4000;
            clock(c, h0);
            cursor(c, s, b[0][0] + b[0][2] / 3.0, b[0][1] + b[0][3] / 2.0);
            c.ticks(1); // the frame at h0 sees the cursor: hover starts
            frame(c, h0 + 50);
            float mid = card(c, s, tab)[0];
            frame(c, h0 + 120);
            float done = card(c, s, tab)[0];
            c.note("hover: 50 ms " + f(mid) + ", 120 ms " + f(done) + ", lift " + f(card(c, s, tab)[8]));
            c.check(between(mid, 0f, 1f), "hover is not part way at 50 ms (" + f(mid) + ")");
            c.check(done == 1f, "hover is not complete at 120 ms (" + f(done) + ")");
            cursorAway(c);
            c.ticks(1);
            frame(c, h0 + 400);
            c.check(card(c, s, tab)[0] == 0f, "hover did not ease back out by 280 ms after leaving");

            // ---- the tooltip fades in after its delay ----
            double tt = base + 5000;
            clock(c, tt);
            cursor(c, s, b[0][0] + b[0][2] / 3.0, b[0][1] + b[0][3] / 2.0);
            c.ticks(1);
            Thread.sleep(420); // the hover delay is wall time (350 ms)
            frame(c, tt);
            frame(c, tt + 60);
            float tipMid = state(c, s)[10];
            frame(c, tt + 130);
            float tipDone = state(c, s)[10];
            c.note("tooltip fade: 60 ms " + f(tipMid) + ", 130 ms " + f(tipDone));
            c.check(between(tipMid, 0f, 1f), "the tooltip is not part way through its fade at 60 ms (" + f(tipMid) + ")");
            c.check(tipDone == 1f, "the tooltip has not finished fading in at 130 ms (" + f(tipDone) + ")");
            cursorAway(c);
            c.ticks(1);

            // ---- open the drawer; a row's switch inside it ----
            double d0 = base + 7000;
            clock(c, d0);
            pressBody(c, s, tab);
            frame(c, d0 + 90);
            float[] dm = state(c, s);
            frame(c, d0 + 180);
            float[] dd = state(c, s);
            c.note("drawer open: 90 ms slide " + f(dm[2]) + " fade " + f(dm[3]) + "; 180 ms slide " + f(dd[2])
                    + " fade " + f(dd[3]));
            c.check(between(dm[2], 0f, 1f) && between(dm[3], 0f, 1f), "the drawer is not part way in at 90 ms");
            c.check(dd[2] == 0f && dd[3] == 1f, "the drawer is not in place at 180 ms");
            c.check(dd[11] == 1f, "the drawer's rows are drawn at " + f(dd[11]) + " opacity once it has opened");
            List<AbstractWidget> rows = switchRows(c, s);
            c.check(!rows.isEmpty(), "the " + MenuACases.name(tab) + " drawer shows no switch row");
            if (!rows.isEmpty()) {
                AbstractWidget row = rows.get(rows.size() - 1);
                String label = rowLabel(row);
                boolean rowOn = (Boolean) Mod.call(row, "isOn");
                int[] cb = c.onClient(mc -> (int[]) Mod.call(row, "controlBounds"));
                double r0 = d0 + 1000;
                clock(c, r0);
                pressCenter(c, s, cb);
                AbstractWidget now = rowByLabel(c, s, label);
                c.check(now != null && (Boolean) Mod.call(now, "isOn") != rowOn, "pressing '" + label
                        + "' did not flip it at once");
                frame(c, r0 + 60);
                now = rowByLabel(c, s, label);
                float knobMid = now == null ? -1 : tween(Mod.call(now, "anim"), "on");
                frame(c, r0 + 150);
                now = rowByLabel(c, s, label);
                float knobDone = now == null ? -1 : tween(Mod.call(now, "anim"), "on");
                float want = rowOn ? 0f : 1f;
                c.note("row '" + label + "' switch: 60 ms " + f(knobMid) + ", 150 ms " + f(knobDone) + " (rebuilt row: "
                        + (now != row) + ")");
                c.check(between(knobMid, 1f - want, want), "the row's knob is not part way at 60 ms (" + f(knobMid)
                        + ") - the rebuilt row did not keep its animation");
                c.check(knobDone == want, "the row's knob is not at " + want + " at 150 ms (" + f(knobDone) + ")");
                if (now != null) {
                    AbstractWidget back = now;
                    int[] cb2 = c.onClient(mc -> (int[]) Mod.call(back, "controlBounds"));
                    pressCenter(c, s, cb2); // put it back
                    frame(c, r0 + 1000);
                }
            }

            // ---- the drawer's eased scroll ----
            int max = c.onClient(mc -> (Integer) Mod.field(s, "maxScroll"));
            if (max >= 32) {
                int dx = c.onClient(mc -> (Integer) Mod.field(s, "drawerX") + 40);
                int dy = c.onClient(mc -> (Integer) Mod.field(s, "drawerY") + 60);
                double s0 = d0 + 3000;
                clock(c, s0);
                c.onClient(mc -> s.mouseScrolled(dx, dy, 0, -2));
                int target = c.onClient(mc -> (Integer) Mod.field(Mod.ROOT + SCREEN, "scrollOffset"));
                frame(c, s0 + 60);
                float sm = state(c, s)[4];
                frame(c, s0 + 160);
                float sd = state(c, s)[4];
                c.note("drawer scroll to " + target + ": 60 ms " + f(sm) + ", 160 ms " + f(sd));
                c.check(between(sm, 0f, target), "the drawer's scroll is not part way at 60 ms (" + f(sm) + ")");
                c.check(sd == target, "the drawer's scroll is not at " + target + " at 160 ms (" + f(sd) + ")");
            } else {
                c.note("the drawer does not scroll (max " + max + "); scroll covered by 1590's grid");
            }

            // ---- closing the drawer: it slides out, then is gone ----
            double x0 = d0 + 5000;
            clock(c, x0);
            c.onClient(mc -> {
                Mod.call(s, "closeDrawer");
                return null;
            });
            frame(c, x0 + 90);
            float closingMid = state(c, s)[7];
            frame(c, x0 + 180);
            float closingDone = state(c, s)[7];
            c.check(closingMid == 1f, "the closed drawer is not sliding out at 90 ms");
            c.check(closingDone == 0f, "the closed drawer is still there at 180 ms");

            // ---- the menu opening ----
            double o0 = base + 13000;
            clock(c, o0);
            Screen fresh = c.onClient(mc -> {
                try {
                    return MenuACases.open(mc);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            frame(c, o0);
            frame(c, o0 + 80);
            float[] om = state(c, fresh);
            frame(c, o0 + 160);
            float[] od = state(c, fresh);
            c.note("open: 80 ms fade " + f(om[0]) + " grow " + f(om[1]) + "; 160 ms " + f(od[0]) + " / " + f(od[1]));
            c.check(between(om[0], 0f, 1f) && between(om[1], 0f, 1f), "the menu is not part way open at 80 ms");
            c.check(od[0] == 1f && od[1] == 1f, "the menu is not fully open at 160 ms");

            // ---- closing the menu: gone at once, its picture fades ----
            double e0 = o0 + 1000;
            clock(c, e0);
            boolean gone = c.onClient(mc -> {
                MenuACases.key(fresh, InputConstants.KEY_ESCAPE, 0);
                return McCompat.screen(mc) != fresh;
            });
            c.check(gone, "Escape did not close the menu at once");
            boolean fading = c.onClient(mc -> (Boolean) Mod.staticCall("gui.MenuCloseFade", "active"));
            frame(c, e0 + 60);
            boolean stillMid = c.onClient(mc -> (Boolean) Mod.staticCall("gui.MenuCloseFade", "active"));
            frame(c, e0 + 130);
            frame(c, e0 + 140);
            boolean after = c.onClient(mc -> (Boolean) Mod.staticCall("gui.MenuCloseFade", "active"));
            c.note("close: fading at once " + fading + ", at 60 ms " + stillMid + ", at 140 ms " + after);
            c.check(fading && stillMid, "the closed menu's picture is not fading out");
            c.check(!after, "the closed menu's picture is still drawn after 140 ms");
        } finally {
            cursorAway(c);
            mode(was);
            releaseClock(c);
        }
    }

    // =================================================================================================================
    // 1593 clicks mid-animation
    // =================================================================================================================

    static void clicks(UiCase c) throws Exception {
        String was = mode();
        mode("SMOOTH");
        try {
            base += 50_000;
            Screen s = menu(c, "Chat", base);
            cursorAway(c);
            Object tab = switchCard(c, s, 0);
            // ---- a drawer row pressed while the drawer is still sliding in ----
            double d0 = base + 2000;
            clock(c, d0);
            pressBody(c, s, tab);
            frame(c, d0 + 60);
            float slide = state(c, s)[2];
            c.check(between(slide, 0f, 1f), "the drawer is not mid-slide at 60 ms (" + f(slide) + ")");
            List<AbstractWidget> rows = switchRows(c, s);
            if (rows.isEmpty()) {
                c.problem("no switch row in the " + MenuACases.name(tab) + " drawer");
            } else {
                AbstractWidget row = rows.get(rows.size() - 1);
                String label = rowLabel(row);
                boolean before = (Boolean) Mod.call(row, "isOn");
                int[] cb = c.onClient(mc -> (int[]) Mod.call(row, "controlBounds"));
                pressCenter(c, s, cb); // no frame in between: still mid-slide
                AbstractWidget now = rowByLabel(c, s, label);
                c.check(now != null && (Boolean) Mod.call(now, "isOn") != before, "a press on '" + label
                        + "' mid-slide did nothing");
                c.note("mid-slide (" + f(slide) + ") press on '" + label + "' flipped it");
                if (now != null) {
                    AbstractWidget back = now;
                    int[] cb2 = c.onClient(mc -> (int[]) Mod.call(back, "controlBounds"));
                    pressCenter(c, s, cb2);
                }
            }
            c.onClient(mc -> {
                Mod.call(s, "closeDrawer");
                return null;
            });
            frame(c, d0 + 2000);

            // ---- a card switch pressed while a new page slides in ----
            double p0 = base + 5000;
            clock(c, p0);
            c.onClient(mc -> Mod.call(s, "selectCategory", "HUD"));
            frame(c, p0 + 50);
            float page = state(c, s)[6];
            c.check(between(page, 0f, 1f), "the HUD page is not mid-change at 50 ms (" + f(page) + ")");
            Object hud = switchCard(c, s, 0);
            boolean hudOn = cardState(hud);
            int[] hsw = bounds(c, s, hud)[1];
            pressCenter(c, s, hsw);
            c.check(cardState(hud) != hudOn, "a card switch pressed mid page change did not flip " + MenuACases.name(hud));
            c.note("mid page change (" + f(page) + ") the " + MenuACases.name(hud) + " switch flipped at once");
            pressCenter(c, s, hsw);
            c.check(cardState(hud) == hudOn, "the HUD card was not put back");

            // ---- a card switch pressed while the grid is still easing to its new scroll ----
            frame(c, p0 + 1000);
            c.onClient(mc -> Mod.call(s, "selectCategory", "Dungeons"));
            frame(c, p0 + 2000);
            double g0 = p0 + 3000;
            int gx = c.onClient(mc -> (Integer) Mod.field(s, "gridX") + 20);
            int gy = c.onClient(mc -> (Integer) Mod.field(s, "gridY") + 30);
            clock(c, g0);
            c.onClient(mc -> s.mouseScrolled(gx, gy, 0, -4));
            frame(c, g0 + 40);
            float[] gs = state(c, s);
            int target = c.onClient(mc -> (Integer) Mod.field(Mod.ROOT + SCREEN, "gridScroll"));
            c.check(between(gs[5], 0f, target), "the grid is not mid-scroll at 40 ms (" + f(gs[5]) + " of " + target + ")");
            // the card that is NOW (final layout) under a point near the grid's top
            @SuppressWarnings("unchecked")
            Object under = c.onClient(mc -> {
                for (Object t : (List<Object>) Mod.call(s, "cards")) {
                    int[][] cb = (int[][]) Mod.call(s, "cardBounds", t);
                    int top = (Integer) Mod.field(s, "gridY") + 8;
                    if (cardState(t) != null && cb[0][1] >= top) {
                        return t;
                    }
                }
                return null;
            });
            if (under == null) {
                c.problem("no switch card in view after scrolling Dungeons");
            } else {
                boolean uOn = cardState(under);
                int[] usw = bounds(c, s, under)[1];
                pressCenter(c, s, usw);
                c.check(cardState(under) != uOn, "a press at " + MenuACases.name(under) + "'s final switch position "
                        + "mid-scroll did not flip it");
                c.note("mid-scroll (" + f(gs[5]) + " -> " + target + ") press at the final layout flipped "
                        + MenuACases.name(under));
                pressCenter(c, s, usw);
                c.check(cardState(under) == uOn, "the card was not put back");
            }
        } finally {
            mode(was);
            releaseClock(c);
        }
    }

    // =================================================================================================================
    // 1594 allocation
    // =================================================================================================================

    static long allocated() {
        return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean()).getCurrentThreadAllocatedBytes();
    }

    /** Render-thread bytes per tick (one frame per tick in the lockstep with a screen open) over {@code ticks}. */
    static double bytesPerFrame(UiCase c, int ticks, Runnable eachTick) {
        long before = c.onClient(mc -> allocated());
        for (int i = 0; i < ticks; i++) {
            if (eachTick != null) {
                eachTick.run();
            }
            c.ticks(1);
        }
        long after = c.onClient(mc -> allocated());
        return (after - before) / (double) ticks;
    }

    /** Bytes the menu's own draw allocated per frame over {@code ticks} (ModScreen.allocProbe), or -1 without it. */
    static double menuBytesPerFrame(UiCase c, int ticks, Runnable eachTick) {
        c.onClient(mc -> {
            Mod.setField(Mod.ROOT + SCREEN, "allocBytes", 0L);
            Mod.setField(Mod.ROOT + SCREEN, "allocFrames", 0L);
            Mod.setField(Mod.ROOT + SCREEN, "allocProbe", true);
            return null;
        });
        for (int i = 0; i < ticks; i++) {
            if (eachTick != null) {
                eachTick.run();
            }
            c.ticks(1);
        }
        return c.onClient(mc -> {
            Mod.setField(Mod.ROOT + SCREEN, "allocProbe", false);
            long bytes = (Long) Mod.field(Mod.ROOT + SCREEN, "allocBytes");
            long frames = (Long) Mod.field(Mod.ROOT + SCREEN, "allocFrames");
            return frames == 0 ? -1.0 : bytes / (double) frames;
        });
    }

    static double median(double[] v) {
        double[] s = v.clone();
        java.util.Arrays.sort(s);
        return s[s.length / 2];
    }

    static void alloc(UiCase c) throws Exception {
        boolean hasAnim = Mod.has(ANIM);
        Screen s = c.onClient(mc -> {
            try {
                Screen m = MenuACases.open(mc);
                Mod.call(m, "selectCategory", "Dungeons");
                return m;
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        cursorAway(c);
        c.ticks(100);
        // The render thread's whole tick (menu, world, client tick) for comparison with an older jar.
        double tick = bytesPerFrame(c, 200, null);
        c.note(String.format(Locale.ROOT, "render thread, menu open and idle: %.0f bytes per tick (%s)", tick,
                hasAnim ? "this jar" : "a jar without menu animations"));
        System.out.println(String.format(Locale.ROOT, "[1594] render thread %s %.0f B/tick", hasAnim ? "new" : "old-jar",
                tick));
        if (!hasAnim) {
            c.problem("this jar has no gui.MenuAnim (no animations to measure)");
            return;
        }
        String was = mode();
        try {
            double[] off = new double[3];
            double[] smooth = new double[3];
            for (int round = 0; round < 3; round++) {
                mode("OFF");
                c.ticks(5);
                off[round] = menuBytesPerFrame(c, 100, null);
                mode("SMOOTH");
                c.ticks(5);
                smooth[round] = menuBytesPerFrame(c, 100, null);
            }
            // animating: the grid scrolling back and forth, its clock driven so it is always mid-ease
            int gx = c.onClient(mc -> (Integer) Mod.field(s, "gridX") + 20);
            int gy = c.onClient(mc -> (Integer) Mod.field(s, "gridY") + 30);
            double[] t = {base += 50_000};
            int[] n = {0};
            double animating = menuBytesPerFrame(c, 200, () -> {
                t[0] += 16.7;
                c.onClient(mc -> {
                    Mod.staticCall(ANIM, "setClockForTest", t[0]);
                    if (n[0]++ % 8 == 0) {
                        s.mouseScrolled(gx, gy, 0, (n[0] / 8) % 2 == 0 ? -2 : 2);
                    }
                    return null;
                });
            });
            releaseClock(c);
            double offM = median(off);
            double smoothM = median(smooth);
            String line = String.format(Locale.ROOT, "menu draw, bytes per frame: Off %.0f, Smooth at rest %.0f, Smooth "
                    + "while the grid eases %.0f (rounds Off %s, Smooth %s)", offM, smoothM, animating,
                    java.util.Arrays.toString(off), java.util.Arrays.toString(smooth));
            c.note(line);
            System.out.println("[1594] " + line);
            c.check(offM > 0 && smoothM > 0, "the allocation probe counted no menu frames");
            // The animation path allocates nothing: at rest Smooth draws exactly what Off draws.
            c.check(smoothM <= offM * 1.01 + 256, String.format(Locale.ROOT,
                    "the menu allocates more per frame with Smooth (%.0f B) than with Off (%.0f B)", smoothM, offM));
        } finally {
            mode(was);
            releaseClock(c);
        }
    }

    // =================================================================================================================
    // 1595 screenshot sequences
    // =================================================================================================================

    static void shot(UiCase c, String label) {
        String name = c.name() + "-" + label;
        Path taken = c.ctx().takeScreenshot(Report.fileName(name));
        Path kept = Report.screenshot(name, taken);
        c.check(kept != null, "no screenshot " + name);
    }

    static void shots(UiCase c) throws Exception {
        String was = mode();
        mode("SMOOTH");
        int[] oldWindow = c.onClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight()});
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        c.ctx().getInput().resizeWindow(1600, 900);
        c.onClient(mc -> {
            mc.options.guiScale().set(2);
            mc.resizeGui();
            return null;
        });
        c.ticks(3);
        try {
            cursorAway(c);
            base += 50_000;
            double o0 = base;
            clock(c, o0);
            Screen s = c.onClient(mc -> {
                try {
                    Screen m = MenuACases.open(mc);
                    Mod.call(m, "closeDrawer");
                    Mod.call(m, "selectCategory", "Dungeons");
                    return m;
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            for (int ms : new int[]{0, 40, 80, 120, 160}) {
                frame(c, o0 + ms);
                shot(c, String.format(Locale.ROOT, "open-%03dms", ms));
            }
            Object tab = switchCard(c, s, 1);
            int[][] b = bounds(c, s, tab);
            double h0 = o0 + 1000;
            clock(c, h0);
            cursor(c, s, b[0][0] + b[0][2] / 3.0, b[0][1] + b[0][3] / 2.0);
            for (int ms : new int[]{0, 40, 80, 120}) {
                frame(c, h0 + ms);
                shot(c, String.format(Locale.ROOT, "hover-%03dms", ms));
            }
            double w0 = o0 + 2000;
            clock(c, w0);
            pressCenter(c, s, b[1]);
            for (int ms : new int[]{0, 50, 100, 150}) {
                frame(c, w0 + ms);
                shot(c, String.format(Locale.ROOT, "switch-%03dms", ms));
            }
            pressCenter(c, s, b[1]); // put it back
            frame(c, w0 + 1000);
            double d0 = o0 + 3000;
            clock(c, d0);
            pressBody(c, s, tab);
            for (int ms : new int[]{0, 45, 90, 135, 180}) {
                frame(c, d0 + ms);
                shot(c, String.format(Locale.ROOT, "drawer-%03dms", ms));
            }
            cursorAway(c);
            double p0 = o0 + 4000;
            clock(c, p0);
            c.onClient(mc -> {
                Mod.call(s, "closeDrawer");
                return Mod.call(s, "selectCategory", "HUD");
            });
            for (int ms : new int[]{0, 45, 90, 135, 180}) {
                frame(c, p0 + ms);
                shot(c, String.format(Locale.ROOT, "page-%03dms", ms));
            }
        } finally {
            releaseClock(c);
            mode(was);
            c.ctx().getInput().resizeWindow(oldWindow[0], oldWindow[1]);
            c.onClient(mc -> {
                mc.options.guiScale().set(oldGui);
                mc.resizeGui();
                return null;
            });
            c.ticks(3);
        }
    }

    /** Copies {@code taken} to {@code dir/name}, for the video frames. */
    static void keep(Path taken, Path dir, String name) throws Exception {
        Files.createDirectories(dir);
        Files.move(taken, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
    }
}
