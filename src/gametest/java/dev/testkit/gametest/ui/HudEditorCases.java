package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 399: the HUD editor's resize handles and snapping, and the Health and Mana Bars additions, from mod branch
 * hud-editor-bars (killer560, 2026-10-07).
 *
 * <ul>
 *   <li><b>399-ui-hud-editor-resize</b> ("make it so it is draggable to resize as well as the scroll ... the same as a
 *       normal Chrome window"): with real mouse drags (TestInput hold / move / release) on the real HudEditorScreen,
 *       a stat bar's right / left / bottom / top edge changes exactly that dimension and leaves the opposite edge where
 *       it was, its corner changes both; a text element's corner changes its scale with the opposite corner fixed and
 *       its edge is a plain move; the cursor the editor asks for over each handle (and the window's real cursor after
 *       real frames); scroll still scales; all of it survives a reload of both config files. Snapping is switched off
 *       for this case so only the resize is measured.</li>
 *   <li><b>399-ui-hud-editor-snap</b> ("Those should kind of do a snapping style where they snap to align with
 *       things"): a box dropped 3 px from another's left edge lands exactly on it; a text dropped 3 px off the screen's
 *       centre lands centred (within half a pixel, the parity limit); one dropped into a row lands at the gap its
 *       neighbours already have; with Alt held, or with Snapping switched off, the same drop stays where it was
 *       dropped; the snapped positions survive a reload with 0 units of difference. Guides are photographed mid-drag.
 *   </li>
 * </ul>
 * Both run at Auto Scale factor 0.5 (the default 854x480 window at GUI 2) and factor 1 (2560x1440 at GUI 3). Only the
 * case's own elements are kept in the editor's list (the others are taken out of {@code shown} after it opens, as
 * setup), so another element that happens to be on in a fresh config cannot be the thing a box snaps to.
 * <ul>
 *   <li><b>399-ui-stat-bars-vitality-xp</b>: an action bar with a Vitality segment ({@code 117/117} + U+E028) sent by
 *       the integrated server through the real packet path fills the Vitality readouts and not Other (and a second
 *       unknown resource beside it still goes to Other); the XP bar and text draw the client's own level and progress
 *       after the server sets them; Classic Display ({@code player_stats}) is no longer registered; and a saved config
 *       with Classic Display on and no readouts loads into the matching readouts at its old place, while one that
 *       already uses a readout gets nothing added.</li>
 * </ul>
 */
final class HudEditorCases {

    private static final String PS = "playerstats.PlayerStatsConfig";
    private static final String HUD = "hud.HudConfig";
    private static final String READOUT = "playerstats.StatElements$Readout";
    private static final String A = "statbar_health";
    private static final String B = "statbar_mana";
    private static final String C = "stattext_health";
    private static final int PAD = 2;

    private HudEditorCases() {
    }

    // ================================================================================================================
    // 399-ui-hud-editor-resize
    // ================================================================================================================

    static void resize(UiCase c) throws Exception {
        Map<Path, byte[]> saved = snapshot(c);
        int[] window = windowSize(c);
        int gui = c.onClient(mc -> mc.options.guiScale().get());
        try {
            resizeAt(c, "f0.5");
            setWindow(c, 2560, 1440, 3);
            resizeAt(c, "f1");
        } finally {
            closeEditor(c);
            restoreWindow(c, window, gui);
            restore(c, saved);
        }
    }

    private static void resizeAt(UiCase c, String tag) throws Exception {
        setupBars(c, 12);
        setSnap(c, false);
        Screen s = openEditor(c, A, C);
        int[] wh = {s.width, s.height};
        place(c, s, A, wh[0] / 5, wh[1] * 3 / 10);
        place(c, s, C, wh[0] / 5, wh[1] * 6 / 10);
        float factor = c.onClient(mc -> ((Number) Mod.staticCall("hud.AutoScale", "current")).floatValue());
        c.note(tag + ": Auto Scale factor " + factor + ", editor " + wh[0] + "x" + wh[1]);

        // ---- right edge of the bar: wider, left edge fixed --------------------------------------------------------
        int[] a = box(c, s, A);
        int[] u = units(c, s, A);
        int[] a0 = a;
        shot(c, tag + "-handles", () -> cursorTo(c, s, a0[2] + PAD, (a0[1] + a0[3]) / 2));
        drag(c, s, a[2] + PAD, (a[1] + a[3]) / 2, 20, 0, null);
        int[] a1 = box(c, s, A);
        int[] u1 = units(c, s, A);
        c.note(String.format(Locale.ROOT, "%s right edge +20: box %s -> %s, units %dx%d -> %dx%d", tag, str(a), str(a1),
                u[0], u[1], u1[0], u1[1]));
        expect(c, a1[0] == a[0] && a1[1] == a[1], tag + " right-edge drag moved the left/top edge: " + str(a) + " -> "
                + str(a1));
        expect(c, a1[2] - a[2] == 20, tag + " right-edge drag of 20 px moved the right edge " + (a1[2] - a[2]) + " px");
        expect(c, u1[0] > u[0] && u1[1] == u[1], tag + " right-edge drag should change only the bar's length: units "
                + u[0] + "x" + u[1] + " -> " + u1[0] + "x" + u1[1]);

        // ---- left edge: wider to the left, right edge fixed ---------------------------------------------------------
        a = a1;
        drag(c, s, a[0] - PAD, (a[1] + a[3]) / 2, -16, 0, null);
        a1 = box(c, s, A);
        c.note(tag + " left edge -16: " + str(a) + " -> " + str(a1));
        expect(c, a1[2] == a[2] && a1[1] == a[1], tag + " left-edge drag moved the right edge: " + str(a) + " -> "
                + str(a1));
        expect(c, a[0] - a1[0] == 16, tag + " left-edge drag of 16 px moved the left edge " + (a[0] - a1[0]) + " px");

        // ---- bottom edge: thicker, top fixed ------------------------------------------------------------------------
        a = a1;
        u = units(c, s, A);
        drag(c, s, (a[0] + a[2]) / 2, a[3] + PAD, 0, 4, null);
        a1 = box(c, s, A);
        u1 = units(c, s, A);
        c.note(String.format(Locale.ROOT, "%s bottom edge +4: %s -> %s, units %dx%d -> %dx%d", tag, str(a), str(a1),
                u[0], u[1], u1[0], u1[1]));
        expect(c, a1[1] == a[1] && a1[0] == a[0] && a1[2] == a[2], tag + " bottom-edge drag moved another edge: "
                + str(a) + " -> " + str(a1));
        expect(c, a1[3] - a[3] == 4, tag + " bottom-edge drag of 4 px moved the bottom edge " + (a1[3] - a[3]) + " px");
        expect(c, u1[0] == u[0] && u1[1] > u[1], tag + " bottom-edge drag should change only the thickness: " + u[0]
                + "x" + u[1] + " -> " + u1[0] + "x" + u1[1]);

        // ---- top edge: thinner, bottom fixed ------------------------------------------------------------------------
        a = a1;
        drag(c, s, (a[0] + a[2]) / 2, a[1] - PAD, 0, 4, null);
        a1 = box(c, s, A);
        c.note(tag + " top edge +4: " + str(a) + " -> " + str(a1));
        expect(c, a1[3] == a[3] && a1[0] == a[0] && a1[2] == a[2], tag + " top-edge drag moved another edge: " + str(a)
                + " -> " + str(a1));
        expect(c, a1[1] - a[1] == 4, tag + " top-edge drag of 4 px moved the top edge " + (a1[1] - a[1]) + " px");

        // ---- bottom-right corner of the bar: both, top-left fixed ----------------------------------------------------
        a = a1;
        u = units(c, s, A);
        drag(c, s, a[2] + PAD, a[3] + PAD, -10, 4, null);
        a1 = box(c, s, A);
        u1 = units(c, s, A);
        c.note(String.format(Locale.ROOT, "%s bar corner (-10,+4): %s -> %s, units %dx%d -> %dx%d", tag, str(a),
                str(a1), u[0], u[1], u1[0], u1[1]));
        expect(c, a1[0] == a[0] && a1[1] == a[1], tag + " bar corner drag moved the opposite corner");
        expect(c, a1[2] - a[2] == -10 && a1[3] - a[3] == 4, tag + " bar corner drag: right edge " + (a1[2] - a[2])
                + " (want -10), bottom " + (a1[3] - a[3]) + " (want +4)");
        float barScale = scale(c, s, A);
        expect(c, Math.abs(barScale - 1.0f) < 1e-4, tag + " resizing the bar changed its scale to " + barScale);

        // ---- top-left corner of a text: uniform scale, bottom-right fixed --------------------------------------------
        int[] t = box(c, s, C);
        float before = scale(c, s, C);
        drag(c, s, t[0] - PAD, t[1] - PAD, -14, -4, null);
        int[] t1 = box(c, s, C);
        float after = scale(c, s, C);
        c.note(String.format(Locale.ROOT, "%s text corner (-14,-4): %s -> %s, scale %.3f -> %.3f", tag, str(t), str(t1),
                before, after));
        expect(c, t1[2] == t[2] && t1[3] == t[3], tag + " text corner drag moved the opposite (bottom-right) corner: "
                + str(t) + " -> " + str(t1));
        expect(c, after > before + 0.05f, tag + " text corner drag did not scale it up (" + before + " -> " + after + ")");
        double rw = (t1[2] - t1[0]) / (double) (t[2] - t[0]);
        double rh = (t1[3] - t1[1]) / (double) (t[3] - t[1]);
        c.note(String.format(Locale.ROOT, "%s text grew x%.3f wide, x%.3f tall (shape kept within rounding)", tag, rw,
                rh));
        expect(c, Math.abs((t1[2] - t1[0]) - Math.round((t[2] - t[0]) * after / before)) <= 1
                        && Math.abs((t1[3] - t1[1]) - Math.round((t[3] - t[1]) * after / before)) <= 1,
                tag + " text corner drag did not keep the shape");

        // ---- a text's edge is not a handle: dragging it moves the box -----------------------------------------------
        t = t1;
        before = after;
        drag(c, s, t[2] + PAD, (t[1] + t[3]) / 2, 8, 0, null);
        t1 = box(c, s, C);
        after = scale(c, s, C);
        c.note(tag + " text edge +8: " + str(t) + " -> " + str(t1));
        expect(c, t1[0] - t[0] == 8 && t1[2] - t[2] == 8 && Math.abs(after - before) < 1e-4,
                tag + " a text element's edge should move it, not resize it");

        // ---- cursors ------------------------------------------------------------------------------------------------
        cursors(c, s, tag);

        // ---- scroll still scales -------------------------------------------------------------------------------------
        t = box(c, s, C);
        before = scale(c, s, C);
        cursorTo(c, s, (t[0] + t[2]) / 2, (t[1] + t[3]) / 2);
        c.ctx().getInput().scroll(1.0);
        c.ticks(3);
        after = scale(c, s, C);
        c.note(String.format(Locale.ROOT, "%s scroll up over the text: scale %.3f -> %.3f", tag, before, after));
        expect(c, Math.abs(after - before - 0.1f) < 1e-3, tag + " scroll did not add 0.1 to the scale");

        // ---- reload -----------------------------------------------------------------------------------------------
        reloadMatches(c, s, tag, A, C);
        setSnap(c, true);
        closeEditor(c);
    }

    private static void cursors(UiCase c, Screen s, String tag) {
        int[] a = box(c, s, A);
        int[] t = box(c, s, C);
        int cy = (a[1] + a[3]) / 2;
        int cx = (a[0] + a[2]) / 2;
        Object ew = CursorTypes.RESIZE_EW;
        Object ns = CursorTypes.RESIZE_NS;
        Object all = CursorTypes.RESIZE_ALL;
        Object[][] probes = {
                {"bar right edge", a[2] + PAD, cy, "ew"},
                {"bar left edge", a[0] - PAD, cy, "ew"},
                {"bar bottom edge", cx, a[3] + PAD, "ns"},
                {"bar top edge", cx, a[1] - PAD, "ns"},
                {"bar bottom-right corner", a[2] + PAD, a[3] + PAD, "nwse"},
                {"bar top-right corner", a[2] + PAD, a[1] - PAD, "nesw"},
                {"bar inside", cx, cy, "all"},
                {"text right edge (a move)", t[2] + PAD, (t[1] + t[3]) / 2, "all"},
                {"text top-left corner", t[0] - PAD, t[1] - PAD, "nwse"},
                {"empty space", 4, s.height / 2, "none"},
        };
        for (Object[] p : probes) {
            String got = c.onClient(mc -> {
                GuiGraphicsExtractor g = new GuiGraphicsExtractor(mc, new GuiRenderState(), (Integer) p[1],
                        (Integer) p[2]);
                s.extractRenderState(g, (Integer) p[1], (Integer) p[2], 0f);
                Object cur = R.get(g, "pendingCursor");
                return name(cur, ew, ns, all);
            });
            boolean ok = switch ((String) p[3]) {
                case "nwse" -> got.contains("nwse") || got.equals("all");
                case "nesw" -> got.contains("nesw") || got.equals("all");
                case "none" -> !got.startsWith("resize") && !got.equals("ew") && !got.equals("ns") && !got.equals("all");
                default -> got.equals(p[3]);
            };
            c.note(tag + " cursor over " + p[0] + ": " + got + (ok ? "" : "  <-- want " + p[3]));
            expect(c, ok, tag + " cursor over " + p[0] + " is " + got + ", want " + p[3]);
        }
        // The window's real cursor, after real frames with the real mouse over the bar's right edge.
        cursorTo(c, s, a[2] + PAD, cy);
        String real = c.onClient(mc -> {
            boolean allowed = (Boolean) R.get(mc.getWindow(), "allowCursorChanges");
            return name(R.get(mc.getWindow(), "currentCursor"), ew, ns, all) + (allowed ? "" : " (cursor changes off)");
        });
        c.note(tag + " window cursor with the mouse on the bar's right edge: " + real);
        expect(c, real.startsWith("ew") || real.contains("cursor changes off"), tag + " the window's cursor over the"
                + " bar's right edge is " + real);
        cursorTo(c, s, 4, s.height / 2);
    }

    private static String name(Object cursor, Object ew, Object ns, Object all) {
        if (cursor == null) {
            return "null";
        }
        if (cursor == ew) {
            return "ew";
        }
        if (cursor == ns) {
            return "ns";
        }
        if (cursor == all) {
            return "all";
        }
        return String.valueOf(cursor).toLowerCase(Locale.ROOT);
    }

    // ================================================================================================================
    // 399-ui-hud-editor-snap
    // ================================================================================================================

    static void snap(UiCase c) throws Exception {
        Map<Path, byte[]> saved = snapshot(c);
        int[] window = windowSize(c);
        int gui = c.onClient(mc -> mc.options.guiScale().get());
        try {
            snapAt(c, "f0.5");
            setWindow(c, 2560, 1440, 3);
            snapAt(c, "f1");
        } finally {
            closeEditor(c);
            restoreWindow(c, window, gui);
            restore(c, saved);
        }
    }

    private static void snapAt(UiCase c, String tag) throws Exception {
        setupBars(c, 8);
        setSnap(c, true);
        Screen s = openEditor(c, A, B, C);
        int w = s.width;
        int h = s.height;
        place(c, s, A, w / 10, h * 35 / 100);
        place(c, s, B, w * 62 / 100, h * 70 / 100);
        place(c, s, C, w / 10, h * 20 / 100);
        float factor = c.onClient(mc -> ((Number) Mod.staticCall("hud.AutoScale", "current")).floatValue());
        c.note(tag + ": Auto Scale factor " + factor + ", editor " + w + "x" + h + ", A " + str(box(c, s, A)) + ", B "
                + str(box(c, s, B)) + ", C " + str(box(c, s, C)));

        // ---- screen centre: the text dropped 3 px right and 2 px up of centred --------------------------------------
        int[] t = box(c, s, C);
        int tw = t[2] - t[0];
        int th = t[3] - t[1];
        int wantX = (int) Math.round(w / 2.0 - tw / 2.0) + 3;
        int wantY = (int) Math.round(h / 2.0 - th / 2.0) - 2;
        moveBox(c, s, C, wantX, wantY, tag + "-guides-centre");
        int[] t1 = box(c, s, C);
        double dcx = (t1[0] + t1[2]) / 2.0 - w / 2.0;
        double dcy = (t1[1] + t1[3]) / 2.0 - h / 2.0;
        c.note(String.format(Locale.ROOT, "%s centre: dropped at %d,%d -> landed %s, off the screen centre by %.1f, %.1f",
                tag, wantX, wantY, str(t1), dcx, dcy));
        expect(c, Math.abs(dcx) <= 0.5 && Math.abs(dcy) <= 0.5, tag + " the text did not snap to the screen centre ("
                + dcx + ", " + dcy + ")");

        // ---- B's left edge: A dropped 3 px right of it ---------------------------------------------------------------
        int[] b = box(c, s, B);
        int[] a = box(c, s, A);
        moveBox(c, s, A, b[0] + 3, a[1], tag + "-guides-edge");
        int[] a1 = box(c, s, A);
        c.note(tag + " edge: A dropped at x " + (b[0] + 3) + " -> " + str(a1) + ", B " + str(b));
        expect(c, a1[0] == b[0] && a1[1] == a[1], tag + " A did not snap onto B's left edge: A.x " + a1[0] + ", B.x "
                + b[0]);
        // Saved and reloaded: still exactly aligned, in the units drawn.
        int[][] back = reloadPositions(c, A, B, C);
        c.note(tag + " after reload: A " + back[0][0] + "," + back[0][1] + "  B " + back[1][0] + "," + back[1][1]);
        expect(c, back[0][0] == back[1][0], tag + " after save + reload A.x " + back[0][0] + " != B.x " + back[1][0]);
        expect(c, back[0][0] == a1[0] && back[0][1] == a1[1], tag + " A drew back at " + back[0][0] + "," + back[0][1]
                + ", dropped at " + a1[0] + "," + a1[1]);
        int[] cBack = back[2];
        double rcx = cBack[0] + tw / 2.0 - w / 2.0;
        expect(c, Math.abs(rcx) <= 0.5, tag + " after reload the centred text is " + rcx + " off centre");

        // ---- Alt held: the same drop stays put ----------------------------------------------------------------------
        a = box(c, s, A);
        moveBox(c, s, A, b[0] + 3, a[1] + 0, null, true);
        a1 = box(c, s, A);
        c.note(tag + " Alt: A dropped at x " + (b[0] + 3) + " -> " + a1[0]);
        expect(c, a1[0] == b[0] + 3, tag + " with Alt held A still snapped (x " + a1[0] + ", dropped at " + (b[0] + 3)
                + ")");

        // ---- Snapping switched off: the same --------------------------------------------------------------------------
        boolean switchable = setSnap(c, false);
        moveBox(c, s, A, b[0] + 2, a[1], null);
        a1 = box(c, s, A);
        c.note(tag + " Snapping OFF: A dropped at x " + (b[0] + 2) + " -> " + a1[0]);
        expect(c, switchable && a1[0] == b[0] + 2, tag + " with Snapping off A landed at " + a1[0] + " (dropped at "
                + (b[0] + 2) + ")" + (switchable ? "" : " - this jar has no snapping switch"));
        setSnap(c, true);

        // ---- equal spacing: C beside B with a gap, A dropped into the row --------------------------------------------
        int gap = 20;
        place(c, s, C, b[2] + gap, b[1]);
        int[] cc = box(c, s, C);
        a = box(c, s, A);
        int aw = a[2] - a[0];
        int target = b[0] - gap - aw;
        moveBox(c, s, A, target + 3, b[1] + 1, tag + "-guides-spacing");
        a1 = box(c, s, A);
        c.note(tag + " spacing: B " + str(b) + ", C " + str(cc) + " (gap " + (cc[0] - b[2]) + "); A dropped at "
                + (target + 3) + " -> " + str(a1) + " (gap to B " + (b[0] - a1[2]) + ")");
        expect(c, b[0] - a1[2] == cc[0] - b[2] && a1[1] == b[1], tag + " A did not land at the row's gap: gap to B "
                + (b[0] - a1[2]) + ", B to C " + (cc[0] - b[2]) + ", y " + a1[1] + " vs " + b[1]);

        // ---- a resized edge snaps too: A's right edge dragged to 2 px short of B's left edge -------------------------
        place(c, s, A, w / 10, h * 30 / 100);
        a = box(c, s, A);
        place(c, s, B, a[2] + 30, h * 45 / 100);
        b = box(c, s, B);
        drag(c, s, a[2] + PAD, (a[1] + a[3]) / 2, b[0] - 2 - a[2], 0, null);
        a1 = box(c, s, A);
        c.note(tag + " resize snap: A right edge " + a[2] + " dragged to " + (b[0] - 2) + " -> " + a1[2] + " (B.x "
                + b[0] + ")");
        expect(c, a1[2] == b[0] && a1[0] == a[0], tag + " A's dragged right edge did not snap onto B's left edge (" + a1[2]
                + " vs " + b[0] + ")");
        closeEditor(c);
    }

    // ================================================================================================================
    // 399-ui-stat-bars-vitality-xp
    // ================================================================================================================

    static void vitalityXp(UiCase c) throws Exception {
        Map<Path, byte[]> saved = snapshot(c);
        Class<?> feature = R.cls("playerstats.PlayerStatsFeature");
        try {
            c.onClient(mc -> {
                Object ps = Mod.cfg(PS);
                Mod.call(ps, "setEnabled", true);
                Mod.call(ps, "setBarShowValue", true);
                for (Object r : R.cls(READOUT).getEnumConstants()) {
                    Mod.call(ps, "setReadoutOn", r, true);
                }
                McCompat.setScreen(mc, null);
                return null;
            });
            boolean hasVitality = c.onClient(mc -> hasField(feature, "vitalityCur"));

            // ---- vitality on the action bar -------------------------------------------------------------------------
            actionBar(c, "§c1,000/1,000     §a250 Defense     §c117/117");
            waitFor(c, "the stat line to be read", () -> ((Number) R.getStatic(feature, "healthCur")).longValue() == 1000);
            long otherCur = c.onClient(mc -> ((Number) R.getStatic(feature, "otherCur")).longValue());
            long vit = hasVitality ? c.onClient(mc -> ((Number) R.getStatic(feature, "vitalityCur")).longValue()) : -2;
            long vitMax = hasVitality ? c.onClient(mc -> ((Number) R.getStatic(feature, "vitalityMax")).longValue()) : -2;
            c.note("vitality line: vitality " + vit + "/" + vitMax + ", Other " + otherCur + (hasVitality ? ""
                    : " (this jar has no vitality reading)"));
            expect(c, vit == 117 && vitMax == 117, "Vitality was not read from '117/117' + U+E028 (got " + vit + "/" + vitMax
                    + ")");
            expect(c, otherCur == -1, "the vitality segment went into Other (" + otherCur + ")");
            List<String> vbar = drawnText(c, "statbar_vitality");
            List<String> vtext = drawnText(c, "stattext_vitality");
            c.note("Vitality Bar draws " + vbar + ", Vitality Text draws " + vtext);
            expect(c, vbar != null && vbar.stream().anyMatch(x -> x.contains("117/117")),
                    "the Vitality Bar does not show 117/117: " + vbar);
            expect(c, vtext != null && vtext.stream().anyMatch(x -> x.contains("117/117")),
                    "the Vitality Text does not show 117/117: " + vtext);
            List<String> obar = drawnText(c, "statbar_other");
            expect(c, obar != null && obar.isEmpty(), "the Other Resource Bar drew " + obar + " for a vitality line");

            // Vitality and another resource on one line: each to its own.
            actionBar(c, "§c900/1,000     §c80/117     §d50/60");
            waitFor(c, "the second stat line", () -> ((Number) R.getStatic(feature, "healthCur")).longValue() == 900);
            long o2 = c.onClient(mc -> ((Number) R.getStatic(feature, "otherCur")).longValue());
            long v2 = hasVitality ? c.onClient(mc -> ((Number) R.getStatic(feature, "vitalityCur")).longValue()) : -2;
            c.note("vitality + other line: vitality " + v2 + ", Other " + o2);
            expect(c, v2 == 80 && o2 == 50, "with vitality and another resource: vitality " + v2 + " (want 80), Other "
                    + o2 + " (want 50)");

            // ---- XP -------------------------------------------------------------------------------------------------
            serverPlayer(c, sp -> {
                sp.setExperienceLevels(23);
                sp.setExperiencePoints(Math.max(1, sp.getXpNeededForNextLevel() * 37 / 100));
            });
            waitFor(c, "the client's XP level 23", () -> Minecraft.getInstance().player.experienceLevel == 23
                    && Minecraft.getInstance().player.experienceProgress > 0.2f);
            float[] xp = c.onClient(mc -> new float[]{mc.player.experienceLevel, mc.player.experienceProgress});
            String want = "Level 23 (" + Math.round(xp[1] * 100f) + "%)";
            List<String> xtext = drawnText(c, "stattext_xp");
            List<String> xbar = drawnText(c, "statbar_xp");
            int[] fill = fillWidth(c, "statbar_xp", 0xFF80FF20);
            c.note(String.format(Locale.ROOT, "client XP: level %d progress %.3f; XP Text %s, XP Bar %s, bar fill %s",
                    (int) xp[0], xp[1], xtext, xbar, fill == null ? "none" : fill[0] + " of " + fill[1]));
            expect(c, xtext != null && xtext.contains(want), "XP Text draws " + xtext + ", want '" + want + "'");
            expect(c, xbar != null && xbar.contains(want), "XP Bar draws " + xbar + ", want '" + want + "'");
            expect(c, fill != null && Math.abs(fill[0] - Math.round(fill[1] * xp[1])) <= 1,
                    "XP Bar's fill is " + (fill == null ? "missing" : fill[0] + " of " + fill[1]) + " for progress "
                            + xp[1]);

            // ---- Classic Display is gone ------------------------------------------------------------------------------
            Object classic = c.onClient(mc -> Mod.staticCall("hud.HudElementRegistry", "byId", "player_stats"));
            c.note("player_stats (Classic Display) registered: " + (classic != null));
            expect(c, classic == null, "Classic Display (player_stats) is still a registered HUD element");

            // ---- an old config with Classic Display on loads into the readouts ---------------------------------------
            migration(c);
        } finally {
            c.onClient(mc -> {
                for (String f : new String[]{"healthCur", "healthMax", "manaCur", "manaMax", "otherCur", "otherMax",
                        "vitalityCur", "vitalityMax", "defenceValue", "overflowMana"}) {
                    if (hasField(feature, f)) {
                        R.setStatic(feature, f, -1L);
                    }
                }
                return null;
            });
            serverPlayer(c, sp -> {
                sp.setExperienceLevels(0);
                sp.setExperiencePoints(0);
            });
            restore(c, saved);
        }
    }

    private static void migration(UiCase c) throws Exception {
        Path psPath = c.onClient(mc -> (Path) R.getStatic(R.cls(PS), "CONFIG_PATH"));
        Path hudPath = c.onClient(mc -> (Path) R.getStatic(R.cls(HUD), "CONFIG_PATH"));
        String hudJson = "{\"editKeyCode\":-1,\"positions\":{\"player_stats\":{\"x\":120,\"y\":90,\"scale\":1.5}}}";
        String oldPs = "{\"enabled\":true,\"showHealth\":true,\"showMana\":true,\"showDefense\":true,"
                + "\"showText\":true,\"showBar\":true,\"barHeight\":8,\"readouts\":{}}";
        Files.writeString(hudPath, hudJson, StandardCharsets.UTF_8);
        Files.writeString(psPath, oldPs, StandardCharsets.UTF_8);
        Map<String, Object> got = c.onClient(mc -> {
            Map<String, Object> out = new LinkedHashMap<>();
            Mod.staticCall(HUD, "load");
            Mod.staticCall(PS, "load");
            Object ps = Mod.cfg(PS);
            for (String r : new String[]{"HEALTH_BAR", "MANA_BAR", "HEALTH_TEXT", "MANA_TEXT", "DEFENCE_TEXT",
                    "DEFENCE_BAR", "OTHER_TEXT"}) {
                out.put(r, Mod.call(ps, "isReadoutOn", Mod.enumValue(READOUT, r)));
            }
            Object hud = Mod.cfg(HUD);
            out.put("healthBarPos", Mod.call(hud, "getPosition", "statbar_health", -1, -1));
            out.put("healthBarScale", Mod.call(hud, "getScale", "statbar_health", -1f));
            out.put("manaBarPos", Mod.call(hud, "getPosition", "statbar_mana", -1, -1));
            return out;
        });
        String rewritten = Files.readString(psPath, StandardCharsets.UTF_8);
        int[] hp = (int[]) got.get("healthBarPos");
        int[] mp = (int[]) got.get("manaBarPos");
        c.note("old Classic Display config -> " + got.entrySet().stream().filter(e -> e.getValue() instanceof Boolean)
                .map(e -> e.getKey() + "=" + e.getValue()).toList() + "; Health Bar at " + hp[0] + "," + hp[1] + " x"
                + got.get("healthBarScale") + ", Mana Bar at " + mp[0] + "," + mp[1] + "; file still has showText: "
                + rewritten.contains("showText"));
        for (String r : new String[]{"HEALTH_BAR", "MANA_BAR", "HEALTH_TEXT", "MANA_TEXT", "DEFENCE_TEXT"}) {
            expect(c, Boolean.TRUE.equals(got.get(r)), "old Classic Display config: " + r + " was not switched on");
        }
        expect(c, Boolean.FALSE.equals(got.get("DEFENCE_BAR")) && Boolean.FALSE.equals(got.get("OTHER_TEXT")),
                "old Classic Display config switched on a readout Classic Display never drew");
        expect(c, hp[0] == 120 && hp[1] == 90 && Math.abs(((Number) got.get("healthBarScale")).floatValue() - 1.5f) < 1e-4,
                "the Health Bar did not take Classic Display's place (" + hp[0] + "," + hp[1] + " x"
                        + got.get("healthBarScale") + ", want 120,90 x1.5)");
        expect(c, mp[0] == 120 && mp[1] > 90, "the Mana Bar is not under the Health Bar (" + mp[0] + "," + mp[1] + ")");
        expect(c, !rewritten.contains("showText"), "the migrated file still carries Classic Display's keys");

        // Already using a readout: nothing is added.
        String usedPs = "{\"enabled\":true,\"showText\":true,\"showBar\":true,"
                + "\"readouts\":{\"mana_text\":{\"on\":true}}}";
        Files.writeString(psPath, usedPs, StandardCharsets.UTF_8);
        boolean[] after = c.onClient(mc -> {
            Mod.staticCall(PS, "load");
            Object ps = Mod.cfg(PS);
            return new boolean[]{(Boolean) Mod.call(ps, "isReadoutOn", Mod.enumValue(READOUT, "MANA_TEXT")),
                    (Boolean) Mod.call(ps, "isReadoutOn", Mod.enumValue(READOUT, "HEALTH_BAR"))};
        });
        c.note("config already using Mana Text: Mana Text " + after[0] + ", Health Bar " + after[1]);
        expect(c, after[0] && !after[1], "a config already using a readout had readouts added (Health Bar " + after[1]
                + ")");
    }

    // ================================================================================================================
    // helpers
    // ================================================================================================================

    private static boolean hasField(Class<?> c, String name) {
        try {
            c.getDeclaredField(name);
            return true;
        } catch (NoSuchFieldException e) {
            return false;
        }
    }

    /** Records a problem (the case keeps going, so an old jar shows every check it fails). */
    private static void expect(UiCase c, boolean ok, String what) {
        if (!ok) {
            c.problem(what);
        }
    }

    private static void waitFor(UiCase c, String what, java.util.function.BooleanSupplier cond) {
        for (int i = 0; i < 100; i++) {
            if (c.onClient(mc -> cond.getAsBoolean())) {
                return;
            }
            c.ticks(1);
        }
        c.problem("timed out waiting for " + what);
    }

    private static void serverPlayer(UiCase c, java.util.function.Consumer<net.minecraft.server.level.ServerPlayer> f) {
        c.onClient(mc -> {
            var server = mc.getSingleplayerServer();
            if (server == null || mc.player == null) {
                return null;
            }
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(uuid);
                if (sp != null) {
                    f.accept(sp);
                }
            });
            return null;
        });
        c.ticks(5);
    }

    /** Sends {@code text} as an action bar from the integrated server: the real packet, the real MODIFY_GAME path. */
    private static void actionBar(UiCase c, String text) {
        serverPlayer(c, sp -> sp.sendSystemMessage(Component.literal(text), true));
    }

    /** The text runs {@code id}'s render() draws in game (no screen open), or null if it is not registered. */
    private static List<String> drawnText(UiCase c, String id) {
        return c.onClient(mc -> {
            Object e = Mod.staticCall("hud.HudElementRegistry", "byId", id);
            if (e == null) {
                return null;
            }
            GuiRenderState state = new GuiRenderState();
            render(mc, e, state);
            List<String> out = new ArrayList<>();
            state.forEachText(t -> out.add(dev.testkit.gametest.TextRuns.string(t)));
            return out;
        });
    }

    /** {filled width, full width} of the fill in {@code argb} that {@code id} draws at 1x, or null. */
    private static int[] fillWidth(UiCase c, String id, int argb) {
        return c.onClient(mc -> {
            Object e = Mod.staticCall("hud.HudElementRegistry", "byId", id);
            if (e == null) {
                return null;
            }
            GuiRenderState state = new GuiRenderState();
            render(mc, e, state);
            int[] w = {-1};
            state.forEachElement(el -> {
                try {
                    Method c1 = el.getClass().getMethod("col1");
                    if ((Integer) c1.invoke(el) == argb) {
                        w[0] = Math.max(w[0], el.bounds().width());
                    }
                } catch (ReflectiveOperationException ignored) {
                    // not a fill
                }
            }, GuiRenderState.TraverseRange.ALL);
            int full = (Integer) Mod.call(e, "width");
            return w[0] < 0 ? null : new int[]{w[0], full};
        });
    }

    private static void render(Minecraft mc, Object element, GuiRenderState state) {
        try {
            GuiGraphicsExtractor g = new GuiGraphicsExtractor(mc, state, -1, -1);
            g.pose().pushMatrix();
            g.pose().translate(10, 10);
            R.cls("hud.HudElement").getMethod("render", GuiGraphicsExtractor.class, int.class, int.class)
                    .invoke(element, g, 0, 0);
            g.pose().popMatrix();
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(UiCase.describe(ex), ex);
        }
    }

    /** Stat Bars on with the Health Bar, Mana Bar and Health Text, every bar 100 long and {@code thickness} thick (the
     *  shared sliders), own scale 1, Show Value on. */
    private static void setupBars(UiCase c, int thickness) {
        c.onClient(mc -> {
            Object ps = Mod.cfg(PS);
            Mod.call(ps, "setEnabled", true);
            // Free placement is the Custom layout (mod bars-anchors): a fresh config is Predefined there, where the
            // area decides position and length and there are no resize handles (407 covers that).
            try {
                Mod.call(ps, "setPredefinedLayout", false);
            } catch (RuntimeException | AssertionError e) {
                // a jar before the Layout setting: always custom
            }
            for (String r : new String[]{"HEALTH_BAR", "MANA_BAR", "HEALTH_TEXT"}) {
                Mod.call(ps, "setReadoutOn", Mod.enumValue(READOUT, r), true);
            }
            Mod.call(ps, "setBarWidth", 100);
            Mod.call(ps, "setBarHeight", thickness);
            Mod.call(ps, "setBarShowValue", true);
            Mod.call(ps, "save");
            Object hud = Mod.cfg(HUD);
            for (String id : new String[]{A, B, C}) {
                Mod.call(hud, "setScale", id, 1.0f);
            }
            Mod.call(hud, "setGlobalScale", 1.0f);
            Mod.call(hud, "save");
            return null;
        });
    }

    /** Sets HudConfig's snapping switch; false when this jar has none. */
    private static boolean setSnap(UiCase c, boolean on) {
        return c.onClient(mc -> {
            try {
                Mod.call(Mod.cfg(HUD), "setEditorSnap", on);
                return true;
            } catch (RuntimeException | AssertionError e) {
                return false;
            }
        });
    }

    /** Opens the real HUD editor with Show Unseen on, then keeps only {@code ids} in its list. */
    @SuppressWarnings("unchecked")
    private static Screen openEditor(UiCase c, String... ids) {
        Screen s = c.onClient(mc -> {
            Mod.call(Mod.cfg(HUD), "setEditorShowAll", true);
            try {
                Screen editor = (Screen) R.cls("hud.HudEditorScreen").getConstructor(Screen.class)
                        .newInstance((Object) null);
                McCompat.setScreen(mc, editor);
                return editor;
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        c.ticks(3);
        c.onClient(mc -> {
            List<Object> shown = (List<Object>) R.get(s, "shown");
            List<String> keep = List.of(ids);
            shown.removeIf(e -> !keep.contains((String) Mod.call(e, "id")));
            return null;
        });
        int n = c.onClient(mc -> ((List<?>) R.get(s, "shown")).size());
        c.check(n == ids.length, "the HUD editor lists " + n + " of the case's " + ids.length + " elements");
        return s;
    }

    static void closeEditor(UiCase c) {
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            return null;
        });
        c.ticks(2);
    }

    /** Puts {@code id} at screen position (x, y) the way the editor does after a drop: saved through toSaved, and the
     *  editor's live position updated. */
    @SuppressWarnings("unchecked")
    private static void place(UiCase c, Screen s, String id, int x, int y) {
        c.onClient(mc -> {
            int[] saved = (int[]) Mod.staticCall("hud.HudElementRegistry", "toSaved", x, y);
            Mod.call(Mod.cfg(HUD), "setPosition", id, saved[0], saved[1]);
            Mod.call(Mod.cfg(HUD), "save");
            ((Map<String, int[]>) R.get(s, "livePositions")).put(id, new int[]{x, y});
            return null;
        });
    }

    private static Object element(String id) {
        return Mod.staticCall("hud.HudElementRegistry", "byId", id);
    }

    /** {x0, y0, x1, y1} of {@code id}'s drawn box in the editor: live position plus width()/height() at its scale. */
    @SuppressWarnings("unchecked")
    private static int[] box(UiCase c, Screen s, String id) {
        return c.onClient(mc -> {
            int[] p = ((Map<String, int[]>) R.get(s, "livePositions")).get(id);
            float own = ((Map<String, Float>) R.get(s, "liveScales")).get(id);
            float g = ((Number) Mod.call(Mod.cfg(HUD), "getEffectiveGlobalScale")).floatValue();
            Object e = element(id);
            int w = Math.round((Integer) Mod.call(e, "width") * own * g);
            int h = Math.round((Integer) Mod.call(e, "height") * own * g);
            return new int[]{p[0], p[1], p[0] + w, p[1] + h};
        });
    }

    private static int[] units(UiCase c, Screen s, String id) {
        return c.onClient(mc -> {
            Object e = element(id);
            return new int[]{(Integer) Mod.call(e, "width"), (Integer) Mod.call(e, "height")};
        });
    }

    @SuppressWarnings("unchecked")
    private static float scale(UiCase c, Screen s, String id) {
        return c.onClient(mc -> ((Map<String, Float>) R.get(s, "liveScales")).get(id));
    }

    /** Reloads both config files from disk and checks each element draws back at its live box (position and size). */
    private static void reloadMatches(UiCase c, Screen s, String tag, String... ids) {
        List<int[]> live = new ArrayList<>();
        for (String id : ids) {
            live.add(box(c, s, id));
        }
        List<int[]> back = c.onClient(mc -> {
            Mod.staticCall(HUD, "load");
            Mod.staticCall(PS, "load");
            List<int[]> out = new ArrayList<>();
            float g = ((Number) Mod.call(Mod.cfg(HUD), "getEffectiveGlobalScale")).floatValue();
            for (String id : ids) {
                Object e = element(id);
                int[] p = (int[]) Mod.staticCall("hud.HudElementRegistry", "resolvePosition", e);
                float own = ((Number) Mod.staticCall("hud.HudElementRegistry", "elementScale", e)).floatValue();
                out.add(new int[]{p[0], p[1], p[0] + Math.round((Integer) Mod.call(e, "width") * own * g),
                        p[1] + Math.round((Integer) Mod.call(e, "height") * own * g)});
            }
            return out;
        });
        for (int i = 0; i < ids.length; i++) {
            c.note(tag + " reload " + ids[i] + ": live " + str(live.get(i)) + ", from disk " + str(back.get(i)));
            expect(c, java.util.Arrays.equals(live.get(i), back.get(i)), tag + " " + ids[i] + " changed on reload: "
                    + str(live.get(i)) + " -> " + str(back.get(i)));
        }
    }

    /** Reloads HudConfig from disk; each id's resolved (drawn) top-left. */
    private static int[][] reloadPositions(UiCase c, String... ids) {
        return c.onClient(mc -> {
            Mod.staticCall(HUD, "load");
            int[][] out = new int[ids.length][];
            for (int i = 0; i < ids.length; i++) {
                out[i] = (int[]) Mod.staticCall("hud.HudElementRegistry", "resolvePosition", element(ids[i]));
            }
            return out;
        });
    }

    /** Drags {@code id} by its middle so its top-left lands on (x, y). */
    private static void moveBox(UiCase c, Screen s, String id, int x, int y, String shotName) {
        moveBox(c, s, id, x, y, shotName, false);
    }

    private static void moveBox(UiCase c, Screen s, String id, int x, int y, String shotName, boolean alt) {
        int[] b = box(c, s, id);
        int gx = b[0] + Math.max(1, (b[2] - b[0]) / 2);
        int gy = b[1] + Math.max(1, (b[3] - b[1]) / 2);
        if (alt) {
            c.ctx().getInput().holdAlt();
            c.ticks(1);
        }
        try {
            drag(c, s, gx, gy, x - b[0], y - b[1], shotName);
        } finally {
            if (alt) {
                c.ctx().getInput().releaseAlt();
                c.ticks(1);
            }
        }
    }

    /** Real mouse: to (gx, gy), hold the left button, move to (gx+dx, gy+dy) in 6 steps, optionally photograph while
     *  still held (guides), release. GUI coordinates of the editor (which is not auto-scaled). */
    static void drag(UiCase c, Screen s, int gx, int gy, int dx, int dy, String shotName) {
        cursorTo(c, s, gx, gy);
        c.ctx().getInput().holdMouse(0);
        c.ticks(2);
        for (int i = 1; i <= 6; i++) {
            cursorTo(c, s, gx + dx * i / 6.0, gy + dy * i / 6.0, 1);
        }
        cursorTo(c, s, gx + dx, gy + dy, 2);
        if (shotName != null) {
            screenshot(c, shotName);
        }
        c.ctx().getInput().releaseMouse(0);
        c.ticks(3);
    }

    static void cursorTo(UiCase c, Screen s, double gx, double gy) {
        cursorTo(c, s, gx, gy, 2);
    }

    static void cursorTo(UiCase c, Screen s, double gx, double gy, int ticks) {
        double[] w = c.onClient(mc -> {
            Window win = mc.getWindow();
            return new double[]{gx * win.getScreenWidth() / (double) win.getGuiScaledWidth(),
                    gy * win.getScreenHeight() / (double) win.getGuiScaledHeight()};
        });
        c.ctx().getInput().setCursorPos(w[0], w[1]);
        c.ticks(ticks);
    }

    private static void shot(UiCase c, String name, Runnable before) {
        before.run();
        c.ticks(2);
        screenshot(c, name);
    }

    static void screenshot(UiCase c, String suffix) {
        String name = c.name() + "-" + suffix;
        Path taken = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(name));
        Path kept = dev.testkit.harness.Report.screenshot(name, taken);
        c.note("picture " + name + " -> " + (kept != null ? kept : taken));
    }

    static String str(int[] b) {
        return b[0] + "," + b[1] + ".." + b[2] + "," + b[3];
    }

    // ---- window ----------------------------------------------------------------------------------------------------

    static int[] windowSize(UiCase c) {
        return c.onClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight()});
    }

    static void setWindow(UiCase c, int w, int h, int gui) {
        closeEditor(c);
        c.ctx().getInput().resizeWindow(w, h);
        c.ticks(3);
        c.onClient(mc -> {
            mc.options.guiScale().set(gui);
            mc.resizeGui();
            return null;
        });
        c.ticks(3);
    }

    static void restoreWindow(UiCase c, int[] window, int gui) {
        try {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                mc.options.guiScale().set(gui);
                return null;
            });
            c.ctx().getInput().resizeWindow(window[0], window[1]);
            c.ticks(5);
            c.onClient(mc -> {
                mc.resizeGui();
                return null;
            });
            c.ticks(2);
        } catch (Throwable t) {
            c.note("window restore: " + UiCase.describe(t));
        }
    }

    // ---- config snapshot -------------------------------------------------------------------------------------------

    static Map<Path, byte[]> snapshot(UiCase c) {
        Map<Path, byte[]> out = new LinkedHashMap<>();
        for (String cls : new String[]{PS, HUD}) {
            Path p = c.onClient(mc -> (Path) R.getStatic(R.cls(cls), "CONFIG_PATH"));
            try {
                out.put(p, Files.exists(p) ? Files.readAllBytes(p) : null);
            } catch (java.io.IOException e) {
                c.note("snapshot " + p + ": " + e);
            }
        }
        return out;
    }

    static void restore(UiCase c, Map<Path, byte[]> saved) {
        try {
            c.onClient(mc -> {
                try {
                    for (Map.Entry<Path, byte[]> e : saved.entrySet()) {
                        if (e.getValue() == null) {
                            Files.deleteIfExists(e.getKey());
                        } else {
                            Files.write(e.getKey(), e.getValue());
                        }
                    }
                } catch (java.io.IOException e) {
                    throw new AssertionError(e);
                }
                Mod.staticCall(HUD, "load");
                Mod.staticCall(PS, "load");
                return null;
            });
        } catch (Throwable t) {
            c.note("config restore: " + UiCase.describe(t));
        }
    }
}
