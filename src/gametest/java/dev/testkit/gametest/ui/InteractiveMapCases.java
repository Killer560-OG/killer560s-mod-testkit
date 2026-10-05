package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.Report;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.gui.screens.Screen;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 385: the Interactive Map screen's input and side panel (killer560 2026-10-05: "if i hold left click and drag around
 * it moves the map and it shouldnt", "See how cluttered that right side is, clean it up a bit", room labels "should
 * be the same and synced" with the Dungeon Map).
 *
 * <p>All through the real input layer (cursor moved and buttons held in WINDOW coordinates, so Auto Scale's mouse
 * transform is in the path) on a real {@code InteractiveMapScreen}, in a plain singleplayer world. Outside a dungeon
 * every map action answers with a chat line, which is the proof each click was actually dispatched:
 * <ul>
 *   <li>a LEFT drag across the map never changes zoom/pan, and its release still acts ("not in a dungeon");</li>
 *   <li>a RIGHT drag (bound to Locked Door, as on his instance) pans and does NOT run Locked Door; a right click
 *       that does not move runs it ("No locked doors found.") and does not pan;</li>
 *   <li>a MIDDLE drag (bound to Go + Secret) pans without acting; the legend's Reset view row puts the view back;
 *       the Controls header toggles the full list;</li>
 *   <li>the legend's text/swatch boxes never overlap each other, stay inside the legend panel and the screen, and
 *       the panel does not overlap the map - collapsed and expanded, at 854x480 GUI 2 and 1920x1080 GUI 3 with
 *       Auto Scale on and off;</li>
 *   <li>the Interactive Map has no Room Labels setting of its own any more (one field for both maps).</li>
 * </ul>
 */
final class InteractiveMapCases {

    private static final String SCREEN = "livemap.InteractiveMapScreen";
    private static final String CFG = "livemap.LiveMapConfig";
    private static final String NOT_IN_DUNGEON = "you are not in a dungeon";
    private static final String NO_LOCKED = "No locked doors found";

    private InteractiveMapCases() {
    }

    static void run(UiCase c) throws Exception {
        Object cfg = Mod.cfg(CFG);
        int oldStart = (Integer) Mod.call(cfg, "getStartKeyCode");
        int oldDoor = (Integer) Mod.call(cfg, "getLockedDoorKeyCode");
        int oldSecret = (Integer) Mod.call(cfg, "getGoSecretKeyCode");
        boolean oldExpanded = (Boolean) Mod.call(cfg, "isMapControlsExpanded");
        int[] oldWindow = c.onClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight()});
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        try {
            // His binds (screenshot 2026-10-05): Start unset (= LMB), RMB Locked door, MMB Go + secret.
            c.onClient(mc -> {
                Mod.call(cfg, "setStartKeyCode", -1);
                Mod.call(cfg, "setLockedDoorKeyCode", (Integer) Mod.staticCall("util.KeyUtil", "codeForMouseButton", 1));
                Mod.call(cfg, "setGoSecretKeyCode", (Integer) Mod.staticCall("util.KeyUtil", "codeForMouseButton", 2));
                Mod.call(cfg, "setMapControlsExpanded", false);
                return null;
            });
            singleSetting(c, cfg);
            Screen screen = open(c);
            input(c, screen, cfg);
            layouts(c, cfg);
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                Mod.call(cfg, "setStartKeyCode", oldStart);
                Mod.call(cfg, "setLockedDoorKeyCode", oldDoor);
                Mod.call(cfg, "setGoSecretKeyCode", oldSecret);
                Mod.call(cfg, "setMapControlsExpanded", oldExpanded);
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

    // ---- one Room Labels setting ------------------------------------------------------------------------------

    private static void singleSetting(UiCase c, Object cfg) {
        boolean stillHasCopy = false;
        for (var m : cfg.getClass().getDeclaredMethods()) {
            stillHasCopy |= m.getName().equals("getMapRoomLabels") || m.getName().equals("setMapRoomLabels");
        }
        c.note("LiveMapConfig has a separate Interactive Map Room Labels: " + stillHasCopy);
        if (stillHasCopy) {
            c.problem("LiveMapConfig still has getMapRoomLabels/setMapRoomLabels - the two maps' labels are not one setting");
        }
    }

    // ---- input ------------------------------------------------------------------------------------------------

    private static Screen open(UiCase c) {
        Screen s = c.onClient(mc -> {
            try {
                Screen screen = (Screen) Mod.cls(SCREEN).getConstructor(boolean.class).newInstance(false);
                McCompat.setScreen(mc, screen);
                return screen;
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        c.ticks(5);
        return s;
    }

    private static float[] view(UiCase c, Screen s) {
        return c.onClient(mc -> (float[]) Mod.call(s, "viewState"));
    }

    private static String fmt(float[] v) {
        return String.format(Locale.ROOT, "zoom %.3f pan %.1f,%.1f", v[0], v[1], v[2]);
    }

    /** Screen (layout) coordinates -> window coordinates, through the factor the screen was laid out with. */
    private static double[] toWindow(UiCase c, Screen s, double sx, double sy) {
        return c.onClient(mc -> {
            float f = ((Number) Mod.staticCall("hud.AutoScale", "appliedFactor", s)).floatValue();
            Window w = mc.getWindow();
            return new double[]{sx * f * w.getScreenWidth() / (double) w.getGuiScaledWidth(),
                    sy * f * w.getScreenHeight() / (double) w.getGuiScaledHeight()};
        });
    }

    /** Holds {@code button} at (sx, sy), moves 6 steps to (sx+dx, sy) in screen units, releases. */
    private static void drag(UiCase c, Screen s, int button, double sx, double sy, double dx) {
        double[] a = toWindow(c, s, sx, sy);
        c.ctx().getInput().setCursorPos(a[0], a[1]);
        c.ticks(2);
        c.ctx().getInput().holdMouse(button);
        c.ticks(2);
        for (int i = 1; i <= 6; i++) {
            double[] p = toWindow(c, s, sx + dx * i / 6.0, sy);
            c.ctx().getInput().setCursorPos(p[0], p[1]);
            c.ticks(1);
        }
        c.ctx().getInput().releaseMouse(button);
        c.ticks(3);
    }

    private static void click(UiCase c, Screen s, int button, double sx, double sy) {
        double[] a = toWindow(c, s, sx, sy);
        c.ctx().getInput().setCursorPos(a[0], a[1]);
        c.ticks(2);
        c.ctx().getInput().pressMouse(button);
        c.ticks(3);
    }

    private static boolean said(long mark, String what) {
        for (String l : LogTap.since(mark)) {
            if (l.contains(what)) {
                return true;
            }
        }
        return false;
    }

    private static void input(UiCase c, Screen s, Object cfg) {
        int[] p = c.onClient(mc -> (int[]) Mod.call(s, "panel"));
        double cx = (p[0] + p[2]) / 2.0;
        double cy = (p[1] + p[3]) / 2.0;
        double dx = (p[2] - p[0]) / 4.0;
        c.note("map panel " + java.util.Arrays.toString(p) + ", drag " + dx + " screen units from its centre");

        // LMB drag: no pan, and the release still dispatched as a Go press.
        float[] v0 = view(c, s);
        long m = LogTap.mark();
        drag(c, s, 0, cx, cy, dx);
        float[] v1 = view(c, s);
        boolean acted = said(m, NOT_IN_DUNGEON);
        c.note("LMB drag: " + fmt(v0) + " -> " + fmt(v1) + "; release dispatched a Go press: " + acted);
        if (v1[0] != v0[0] || v1[1] != v0[1] || v1[2] != v0[2]) {
            c.problem("a LEFT drag changed the view: " + fmt(v0) + " -> " + fmt(v1));
        }
        if (!acted) {
            c.problem("the left drag's release did not dispatch a Go press (no '" + NOT_IN_DUNGEON + "' line)");
        }

        // RMB click (bound to Locked Door): acts, no pan.
        m = LogTap.mark();
        click(c, s, 1, cx, cy);
        float[] v2 = view(c, s);
        boolean door = said(m, NO_LOCKED);
        c.note("RMB click: " + fmt(v2) + "; Locked Door ran: " + door);
        if (!door) {
            c.problem("a right click (bound to Locked Door) did not run it");
        }
        if (v2[1] != v1[1] || v2[2] != v1[2]) {
            c.problem("a right CLICK panned the view");
        }

        // RMB drag: pans, does not act.
        m = LogTap.mark();
        drag(c, s, 1, cx, cy, dx);
        float[] v3 = view(c, s);
        boolean doorOnDrag = said(m, NO_LOCKED);
        c.note("RMB drag: " + fmt(v2) + " -> " + fmt(v3) + "; Locked Door ran: " + doorOnDrag);
        if (v3[1] - v2[1] < dx * 0.5) {
            c.problem(String.format(Locale.ROOT, "a RIGHT drag of %.0f units panned only %.1f", dx, v3[1] - v2[1]));
        }
        if (doorOnDrag) {
            c.problem("a right DRAG also ran Locked Door");
        }

        // MMB drag (bound to Go + Secret): pans, does not act.
        m = LogTap.mark();
        drag(c, s, 2, cx, cy, -dx);
        float[] v4 = view(c, s);
        boolean secretOnDrag = said(m, NOT_IN_DUNGEON);
        c.note("MMB drag: " + fmt(v3) + " -> " + fmt(v4) + "; Go + Secret ran: " + secretOnDrag);
        if (v3[1] - v4[1] < dx * 0.5) {
            c.problem("a MIDDLE drag did not pan back");
        }
        if (secretOnDrag) {
            c.problem("a middle DRAG also ran Go + Secret");
        }

        // Move the view, then the legend's Reset view row.
        drag(c, s, 1, cx, cy, dx);
        c.ticks(2);
        int[] reset = boxOf(c, s, "Reset view");
        if (reset == null) {
            c.problem("the view is moved (" + fmt(view(c, s)) + ") but the legend shows no Reset view row");
        } else {
            m = LogTap.mark();
            click(c, s, 0, (reset[0] + reset[2]) / 2.0, (reset[1] + reset[3]) / 2.0);
            float[] v5 = view(c, s);
            c.note("Reset view clicked: " + fmt(v5));
            if (v5[0] != 1f || v5[1] != 0f || v5[2] != 0f) {
                c.problem("Reset view left the view at " + fmt(v5));
            }
            if (said(m, NOT_IN_DUNGEON)) {
                c.problem("a left click on the legend ALSO dispatched a map Go press");
            }
        }

        // Controls header toggles the full list.
        int[] hdr = boxOf(c, s, "Controls");
        boolean before = (Boolean) Mod.call(cfg, "isMapControlsExpanded");
        if (hdr == null) {
            c.problem("no Controls header on the legend");
        } else {
            click(c, s, 0, (hdr[0] + hdr[2]) / 2.0, (hdr[1] + hdr[3]) / 2.0);
            boolean after = (Boolean) Mod.call(cfg, "isMapControlsExpanded");
            c.note("Controls header clicked: expanded " + before + " -> " + after);
            if (after == before) {
                c.problem("clicking the Controls header did not toggle it");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<int[]> boxes(UiCase c, Screen s, List<String> texts) {
        List<String> raw = c.onClient(mc -> (List<String>) Mod.call(s, "legendTextBoxes"));
        List<int[]> out = new ArrayList<>();
        for (String r : raw) {
            String[] f = r.split("\\|");
            int n = f.length;
            texts.add(String.join("|", java.util.Arrays.copyOf(f, n - 4)));
            out.add(new int[]{Integer.parseInt(f[n - 4]), Integer.parseInt(f[n - 3]), Integer.parseInt(f[n - 2]),
                    Integer.parseInt(f[n - 1])});
        }
        return out;
    }

    private static int[] boxOf(UiCase c, Screen s, String text) {
        List<String> texts = new ArrayList<>();
        List<int[]> b = boxes(c, s, texts);
        for (int i = 0; i < b.size(); i++) {
            if (texts.get(i).equals(text)) {
                return b.get(i);
            }
        }
        return null;
    }

    // ---- layout at two window sizes ---------------------------------------------------------------------------

    private static void layouts(UiCase c, Object cfg) throws Exception {
        // {width, height, GUI scale, Auto Scale}: the default window, 1080p with Auto Scale, 1080p with it OFF (640x360).
        int[][] sizes = {{854, 480, 2, 1}, {1920, 1080, 3, 1}, {1920, 1080, 3, 0}};
        Object hud = Mod.cfg("hud.HudConfig");
        boolean oldAuto = (Boolean) Mod.call(hud, "isAutoScale");
        try {
            layoutsAt(c, cfg, hud, sizes);
        } finally {
            c.onClient(mc -> {
                Mod.call(hud, "setAutoScale", oldAuto);
                return null;
            });
        }
    }

    private static void layoutsAt(UiCase c, Object cfg, Object hud, int[][] sizes) throws Exception {
        for (int[] sz : sizes) {
            c.onClient(mc -> {
                Mod.call(hud, "setAutoScale", sz[3] == 1);
                return null;
            });
            c.ctx().getInput().resizeWindow(sz[0], sz[1]);
            c.ticks(3);
            c.onClient(mc -> {
                mc.options.guiScale().set(sz[2]);
                mc.resizeGui();
                return null;
            });
            c.ticks(3);
            for (boolean expanded : new boolean[]{false, true}) {
                c.onClient(mc -> {
                    Mod.call(cfg, "setMapControlsExpanded", expanded);
                    return null;
                });
                Screen s = open(c);
                String tag = sz[0] + "x" + sz[1] + (sz[3] == 1 ? "" : "-noautoscale")
                        + (expanded ? "-expanded" : "-collapsed");
                checkLayout(c, s, tag);
                Path shot = c.ctx().takeScreenshot(Report.fileName(c.name() + "-" + tag));
                Report.screenshot(c.name(), shot);
                c.note("screenshot " + shot);
            }
        }
    }

    /** No two legend boxes intersect; all inside the legend panel and the screen; the panel clear of the map. */
    static void checkLayout(UiCase c, Screen s, String tag) {
        List<String> texts = new ArrayList<>();
        List<int[]> b = boxes(c, s, texts);
        int[] legend = c.onClient(mc -> (int[]) Mod.call(s, "legendPanelRect"));
        int[] map = c.onClient(mc -> (int[]) Mod.call(s, "panel"));
        float f = c.onClient(mc -> ((Number) Mod.staticCall("hud.AutoScale", "appliedFactor", s)).floatValue());
        c.note(String.format(Locale.ROOT, "%s: screen %dx%d (factor %.3f), legend %s, %d box(es): %s", tag, s.width,
                s.height, f, legend == null ? "none" : java.util.Arrays.toString(legend), b.size(), texts));
        if (legend == null || b.size() < 20) {
            c.problem(tag + ": the legend did not draw (" + b.size() + " boxes)");
            return;
        }
        for (int i = 0; i < b.size(); i++) {
            int[] x = b.get(i);
            if (x[0] < legend[0] || x[1] < legend[1] || x[2] > legend[2] || x[3] > legend[3]) {
                c.problem(tag + ": '" + texts.get(i) + "' " + java.util.Arrays.toString(x) + " is outside the legend "
                        + java.util.Arrays.toString(legend));
            }
            if (x[2] > s.width || x[3] > s.height) {
                c.problem(tag + ": '" + texts.get(i) + "' runs off the screen");
            }
            for (int j = i + 1; j < b.size(); j++) {
                int[] y = b.get(j);
                if (x[0] < y[2] && y[0] < x[2] && x[1] < y[3] && y[1] < x[3]) {
                    c.problem(tag + ": '" + texts.get(i) + "' " + java.util.Arrays.toString(x) + " overlaps '"
                            + texts.get(j) + "' " + java.util.Arrays.toString(y));
                }
            }
        }
        if (map[0] < legend[2] && legend[0] < map[2] && map[1] < legend[3] && legend[1] < map[3]) {
            c.problem(tag + ": the legend panel overlaps the map panel");
        }
    }
}
