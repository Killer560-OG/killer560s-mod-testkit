package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

import java.awt.image.BufferedImage;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 891-896: Health and Mana Bars' styles (mod branch bars-style, killer560 2026-10-12: "work on the custom bars more. Look
 * at how skyblocker does theirs ... make your own type of style of it"): Sleek and Bold, drawn by
 * {@code playerstats.BarPainter}. Classic was removed in mod bars-2 (killer560: "Please remove the classic option have the
 * default of it be bold"), which also outlined the number, added Text Size, a deeper overflow mana default and one Beside
 * width per column. The painter records what each draw measured ({@code BarPainter.lastTrack/lastFill/lastOverflow/
 * lastGhost/lastInset*}, bar units); every case also proves on a screenshot that those pixels changed.
 *
 * <ul>
 *   <li>891-ui-bars-style-fill: each style at 100/50/10/0%: fill = round(track * value / max), the screenshot's changed
 *       columns between a value and 0 span exactly the fill; the per-frame value/animation path allocates nothing.</li>
 *   <li>892-ui-bars-style-overflow: absorption on health and overflow mana on mana appear exactly when there is some, at
 *       their share of max, ending at the bar's end; nothing changes on screen for overflow -1 against 0.</li>
 *   <li>893-ui-bars-style-settings: every new setting saved, reloaded from disk and by a profile reload; the tab's rows
 *       (with tooltips), Bar Style only Sleek/Bold and cycling both ways by real presses, a saved "classic" loading
 *       as Bold, Bold for a fresh config, the old pale overflow mana default replaced by the deep blue one.</li>
 *   <li>894-ui-bars-style-ghost: smooth easing passes through values in between; after a drop the ghost marks the old
 *       value, holds, then drains to the new one; with Damage Ghost off there is none. Picture mid-drain.</li>
 *   <li>895-ui-bars-style-shots: Bold and Sleek at full, half and low at both text sizes, with overflow, and a Beside
 *       column, in the default colours (copied to {@code TESTKIT_BARS_SHOTS} as v2-*.png when set); on every picture
 *       each white pixel of a number touches only outline-dark pixels (contrast against white measured, at least 7:1),
 *       on red health, blue mana, green defence and the overflow mana layer.</li>
 *   <li>896-ui-bars-style-editor: Bold with icons and the number Beside: everything drawn inside the element's box; bars
 *       stacked in one column share one Beside width (the same track) and a bar elsewhere keeps its own; in Edit Bars a
 *       real drag moves and saves the bar and an edge drag resizes and saves it.</li>
 * </ul>
 */
final class BarStyleCases {

    /** A point on a cycler's value pill, right half (forward): in the menu a cycler steps from its pill, never from its
     *  label (mod doc-menu, 2026-10-13). */
    private static double pillX(AbstractWidget w) {
        int[] cb = (int[]) Mod.call(w, "controlBounds");
        return cb[0] + cb[2] * 0.75;
    }

    private static double pillY(AbstractWidget w) {
        int[] cb = (int[]) Mod.call(w, "controlBounds");
        return cb[1] + cb[3] / 2.0;
    }

    private static final String PS = "playerstats.PlayerStatsConfig";
    private static final String HUD = "hud.HudConfig";
    private static final String READOUT = "playerstats.StatElements$Readout";
    private static final String STYLE = "playerstats.PlayerStatsConfig$BarStyle";
    private static final String POSITION = "playerstats.PlayerStatsConfig$ValuePosition";
    private static final String SIZE = "playerstats.PlayerStatsConfig$TextSize";
    private static final String FEATURE = "playerstats.PlayerStatsFeature";
    private static final String PAINTER = "playerstats.BarPainter";
    private static final String TAB = "Health and Mana Bars";
    /** The style row's label: "Style" since mod bars-menu (was "Bar Style"). */
    private static final String STYLE_ROW = "Style:";

    static final int HEALTH = 0xFFE03030;
    static final int MANA = 0xFF3070F0;
    static final int DEFENCE = 0xFF30C040;
    static final int ABSORB = 0xFFFFC020;
    static final int OVERFLOW = 0xFF9FE6FF;
    static final int BG = 0xAA000000;
    /** Bold's orange rim (BarPainter), opaque. */
    static final int RIM = 0xFFB85C0A;

    private static final int BX = 100;
    private static final int BY = 100;
    private static final int BW = 160;
    private static final int BH = 14;

    private BarStyleCases() {
    }

    static boolean supported(UiCase c) {
        boolean has = c.onClient(mc -> Mod.has(PAINTER));
        if (!has) {
            c.note("this jar has no BarPainter (before bars-style): nothing to test");
        }
        return has;
    }

    // ================================================================================================================
    // 891 fill widths
    // ================================================================================================================

    static void fill(UiCase c) throws Exception {
        if (!supported(c)) {
            return;
        }
        Map<Path, byte[]> saved = HudFixCases.snapshot(c, PS, HUD);
        int[] window = HudEditorCases.windowSize(c);
        int gui = c.onClient(mc -> mc.options.guiScale().get());
        try {
            HudEditorCases.setWindow(c, 854, 480, 2);
            int gs = c.onClient(mc -> mc.getWindow().getGuiScale());
            for (String style : new String[]{"SLEEK", "BOLD"}) {
                setup(c, style, new String[]{"HEALTH_BAR"}, false, false, false);
                place(c, "statbar_health", BX, BY);
                long[][] cases = {{1000, 1000}, {500, 1000}, {100, 1000}, {0, 1000}, {333, 1000}};
                for (long[] v : cases) {
                    health(c, v[0], v[1]);
                    int[] m = measured(c, "HEALTH_BAR");
                    int want = Math.round(m[0] * (float) Math.max(0.0, Math.min(1.0, (double) v[0] / v[1])));
                    c.note(String.format(Locale.ROOT, "%s %d/%d: track %d, fill %d (want %d), overflow %d, ghost %d",
                            style, v[0], v[1], m[0], m[1], want, m[2], m[3]));
                    c.check(m[0] > 0, style + ": the bar drew no track");
                    c.check(m[1] == want, style + " " + v[0] + "/" + v[1] + ": fill " + m[1] + " units, want " + want);
                    c.check(m[2] == 0, style + " " + v[0] + "/" + v[1] + ": an overflow layer with no overflow");
                }
                // On the screen: a half bar against an empty one changes exactly the fill's columns.
                health(c, 500, 1000);
                int[] half = measured(c, "HEALTH_BAR");
                BufferedImage a = HudFixCases.shot(c, style.toLowerCase(Locale.ROOT) + "-half");
                health(c, 0, 1000);
                BufferedImage b = HudFixCases.shot(c, style.toLowerCase(Locale.ROOT) + "-empty");
                int[] cols = changedColumns(a, b, BX * gs, BY * gs, (BX + BW) * gs, (BY + BH) * gs);
                int trackX0 = BX + (BW - half[0] - iconRoom(c, BW, BH, style)) / 2 + iconRoom(c, BW, BH, style);
                c.note(String.format(Locale.ROOT, "%s screenshot: half vs empty changes columns %s (px), want %d..%d",
                        style, cols == null ? "none" : cols[0] + ".." + cols[1], trackX0 * gs,
                        (trackX0 + half[1]) * gs - 1));
                c.check(cols != null, style + ": the half bar and the empty bar look the same - nothing drew");
                if (cols != null) {
                    c.check(Math.abs(cols[0] - trackX0 * gs) <= 1, style + ": the fill starts at px " + cols[0]
                            + ", want " + trackX0 * gs);
                    c.check(Math.abs((cols[1] + 1) - (trackX0 + half[1]) * gs) <= 1, style + ": the fill ends at px "
                            + (cols[1] + 1) + ", want " + (trackX0 + half[1]) * gs);
                }
            }
            allocation(c);
        } finally {
            reset(c);
            HudEditorCases.restoreWindow(c, window, gui);
            HudFixCases.restore(c, saved);
        }
    }

    /** The per-frame value and animation path: no bytes allocated per call once warm. */
    private static void allocation(UiCase c) {
        String result = c.onClient(mc -> {
            try {
                Class<?> se = R.cls("playerstats.StatElements");
                Class<?> rc = R.cls(READOUT);
                MethodHandles.Lookup lk = MethodHandles.privateLookupIn(se, MethodHandles.lookup());
                MethodHandle read = lk.findStatic(se, "readBar", MethodType.methodType(String.class, rc))
                        .asType(MethodType.methodType(Object.class, Object.class));
                Class<?> bp = R.cls(PAINTER);
                MethodHandles.Lookup lk2 = MethodHandles.privateLookupIn(bp, MethodHandles.lookup());
                MethodHandle anim = lk2.findStatic(bp, "animate", MethodType.methodType(void.class, rc, float.class,
                        float.class, boolean.class, boolean.class, boolean.class, long.class))
                        .asType(MethodType.methodType(void.class, Object.class, float.class, float.class, boolean.class,
                                boolean.class, boolean.class, long.class));
                Object health = r("HEALTH_BAR");
                Object mana = r("MANA_BAR");
                com.sun.management.ThreadMXBean mx =
                        (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
                long t = System.nanoTime();
                for (int i = 0; i < 20000; i++) {
                    Object s1 = read.invokeExact(health);
                    Object s2 = read.invokeExact(mana);
                    anim.invokeExact(health, 0.5f, 0.1f, true, true, false, t + i * 16_000_000L);
                }
                long before = mx.getCurrentThreadAllocatedBytes();
                int n = 100000;
                for (int i = 0; i < n; i++) {
                    Object s1 = read.invokeExact(health);
                    Object s2 = read.invokeExact(mana);
                    anim.invokeExact(health, (i & 1) == 0 ? 0.5f : 0.4f, 0.1f, true, true, false, t + i * 16_000_000L);
                }
                long bytes = mx.getCurrentThreadAllocatedBytes() - before;
                return n + " frames of readBar(health) + readBar(mana) + animate: " + bytes + " bytes";
            } catch (Throwable e) {
                return "could not measure: " + e;
            }
        });
        c.note(result);
        long bytes = result.contains(": ") && result.endsWith(" bytes")
                ? Long.parseLong(result.substring(result.lastIndexOf(": ") + 2, result.length() - 6)) : -1;
        c.check(bytes >= 0, "the allocation measurement did not run: " + result);
        // A steady value: readBar returns its cached string; animate only writes arrays. Allow a few KB of JIT noise.
        c.check(bytes >= 0 && bytes < 8192, "the per-frame bar path allocates: " + result);
    }

    // ================================================================================================================
    // 892 overflow layer
    // ================================================================================================================

    static void overflow(UiCase c) throws Exception {
        if (!supported(c)) {
            return;
        }
        Map<Path, byte[]> saved = HudFixCases.snapshot(c, PS, HUD);
        int[] window = HudEditorCases.windowSize(c);
        int gui = c.onClient(mc -> mc.options.guiScale().get());
        try {
            HudEditorCases.setWindow(c, 854, 480, 2);
            int gs = c.onClient(mc -> mc.getWindow().getGuiScale());
            for (String style : new String[]{"SLEEK", "BOLD"}) {
                setup(c, style, new String[]{"HEALTH_BAR", "MANA_BAR", "DEFENCE_BAR"}, false, false, false);
                place(c, "statbar_health", BX, BY);
                place(c, "statbar_mana", BX, BY + 30);
                place(c, "statbar_defence", BX, BY + 60);
                setStatic(c, "defenceValue", 500L);

                // Health: none at max, none below it, a fifth past max = a fifth of the bar at its end.
                health(c, 500, 1000);
                int[] m = measured(c, "HEALTH_BAR");
                c.check(m[2] == 0, style + " health 500/1000: overflow " + m[2] + ", want none");
                health(c, 1000, 1000);
                m = measured(c, "HEALTH_BAR");
                c.check(m[2] == 0, style + " health 1000/1000: overflow " + m[2] + ", want none");
                BufferedImage plain = HudFixCases.shot(c, style.toLowerCase(Locale.ROOT) + "-health-full");
                health(c, 1200, 1000);
                m = measured(c, "HEALTH_BAR");
                int want = Math.max(1, Math.round(m[0] * 0.2f));
                c.note(style + " health 1200/1000: track " + m[0] + ", fill " + m[1] + ", overflow " + m[2]
                        + " (want " + want + ")");
                c.check(m[1] == m[0], style + " health 1200/1000: the health fill is not full");
                c.check(m[2] == want, style + " health 1200/1000: overflow " + m[2] + ", want " + want);
                BufferedImage abs = HudFixCases.shot(c, style.toLowerCase(Locale.ROOT) + "-health-absorption");
                endSegment(c, style + " absorption", plain, abs, gs, BY, m, BW, style);

                // Mana: overflow -1 (none read) and 0 draw the same; 300 of 1000 is 30% at the end.
                mana(c, 1000, 1000, -1);
                m = measured(c, "MANA_BAR");
                c.check(m[2] == 0, style + " mana, overflow -1: overflow layer " + m[2]);
                BufferedImage none = HudFixCases.shot(c, style.toLowerCase(Locale.ROOT) + "-mana-none");
                mana(c, 1000, 1000, 0);
                m = measured(c, "MANA_BAR");
                c.check(m[2] == 0, style + " mana, overflow 0: overflow layer " + m[2]);
                BufferedImage zero = HudFixCases.shot(c, style.toLowerCase(Locale.ROOT) + "-mana-zero");
                // From the track on: the mana icon is an enchanted book, whose glint moves between any two pictures.
                int icon = iconRoom(c, BW, BH, style);
                int diff = changedCount(none, zero, (BX + icon) * gs, (BY + 30) * gs, (BX + BW) * gs,
                        (BY + 30 + BH) * gs);
                c.check(diff == 0, style + ": overflow mana 0 changes " + diff + " px against none");
                mana(c, 1000, 1000, 300);
                m = measured(c, "MANA_BAR");
                want = Math.max(1, Math.round(m[0] * 0.3f));
                c.note(style + " mana 1000/1000 + 300 overflow: track " + m[0] + ", overflow " + m[2] + " (want " + want
                        + ")");
                c.check(m[2] == want, style + " overflow mana: " + m[2] + ", want " + want);
                BufferedImage ov = HudFixCases.shot(c, style.toLowerCase(Locale.ROOT) + "-mana-overflow");
                endSegment(c, style + " overflow mana", none, ov, gs, BY + 30, m, BW, style);
                mana(c, 400, 1000, 300);
                m = measured(c, "MANA_BAR");
                c.note(style + " mana 400/1000 + 300: fill " + m[1] + ", overflow " + m[2]);
                c.check(m[2] == Math.max(1, Math.round(m[0] * 0.3f)), style + " overflow mana below max is not 30%");

                // Defence: never an overflow layer.
                m = measured(c, "DEFENCE_BAR");
                c.check(m[0] > 0 && m[2] == 0, style + " defense: track " + m[0] + ", overflow " + m[2]);
            }
        } finally {
            reset(c);
            HudEditorCases.restoreWindow(c, window, gui);
            HudFixCases.restore(c, saved);
        }
    }

    /** The overflow layer on screen: the changed columns between {@code before} and {@code after} are the segment at
     *  the bar's end, {@code m[2]} units long. */
    private static void endSegment(UiCase c, String tag, BufferedImage before, BufferedImage after, int gs, int y,
                                   int[] m, int w, String style) {
        int icon = iconRoom(c, w, BH, style);
        // From the track on: the enchanted book icon's glint moves between any two pictures.
        int[] cols = changedColumns(before, after, (BX + icon) * gs, y * gs, (BX + w) * gs, (y + BH) * gs);
        int frame = (w - m[0] - icon) / 2;
        int endPx = (BX + w - frame) * gs;
        c.note(String.format(Locale.ROOT, "%s on screen: changed columns %s, want %d..%d", tag,
                cols == null ? "none" : cols[0] + ".." + cols[1], endPx - m[2] * gs, endPx - 1));
        c.check(cols != null, tag + ": the overflow layer changed nothing on screen");
        if (cols != null) {
            // The light top edge spans fill and overflow alike, so only the segment's own columns change.
            c.check(Math.abs(cols[1] + 1 - endPx) <= 1, tag + ": ends at px " + (cols[1] + 1) + ", want " + endPx);
            c.check(Math.abs(cols[0] - (endPx - m[2] * gs)) <= 1, tag + ": starts at px " + cols[0] + ", want "
                    + (endPx - m[2] * gs));
        }
    }

    // ================================================================================================================
    // 893 settings
    // ================================================================================================================

    static void settings(UiCase c) throws Exception {
        if (!supported(c)) {
            return;
        }
        Map<Path, byte[]> saved = HudFixCases.snapshot(c, PS, HUD);
        try {
            setup(c, "SLEEK", new String[]{"HEALTH_BAR", "MANA_BAR"}, true, true, true);
            // Every new setting away from its default, saved, then read back from the file.
            String back = c.onClient(mc -> {
                Object ps = Mod.cfg(PS);
                Mod.call(ps, "setBarStyle", Mod.enumValue(STYLE, "BOLD"));
                Mod.call(ps, "setBarRounded", false);
                Mod.call(ps, "setBarIcons", false);
                Mod.call(ps, "setBarValuePosition", Mod.enumValue(POSITION, "BESIDE"));
                Mod.call(ps, "setBarSmooth", false);
                Mod.call(ps, "setBarDamageGhost", false);
                Mod.call(ps, "setOverflowManaColor", 0xFF123456);
                Mod.call(ps, "save");
                Mod.staticCall(PS, "load");
                return readBack();
            });
            c.note("saved and loaded: " + back);
            c.check(back.equals("BOLD,false,false,BESIDE,false,false,ff123456"), "settings did not survive a reload: "
                    + back);
            String file = c.onClient(mc -> {
                try {
                    return Files.readString((Path) R.getStatic(R.cls(PS), "CONFIG_PATH"));
                } catch (java.io.IOException e) {
                    return "";
                }
            });
            for (String key : new String[]{"\"barStyle\": \"bold\"", "\"barRounded\": false", "\"barIcons\": false",
                    "\"barValuePosition\": \"beside\"", "\"barSmooth\": false", "\"barDamageGhost\": false",
                    "\"overflowManaColor\""}) {
                c.check(file.contains(key), "the settings file has no " + key);
            }
            // A profile reload reads them too (ProfileManager.reloadAllConfigs): an unsaved change is undone.
            String reloaded = c.onClient(mc -> {
                Object ps = Mod.cfg(PS);
                Mod.call(ps, "setBarStyle", Mod.enumValue(STYLE, "SLEEK"));
                Mod.call(ps, "setBarIcons", true);
                Mod.staticCall("profiles.ProfileManager", "reloadAllConfigs");
                return readBack();
            });
            c.note("after ProfileManager.reloadAllConfigs: " + reloaded);
            c.check(reloaded.startsWith("BOLD,false,false"), "a profile reload does not reload the bar style: "
                    + reloaded);

            // The tab: every row there with a tooltip, under Sleek; the style rows gone under Classic.
            c.onClient(mc -> {
                Object ps = Mod.cfg(PS);
                Mod.call(ps, "setBarStyle", Mod.enumValue(STYLE, "SLEEK"));
                Mod.call(ps, "setBarShowValue", true);
                // Since mod bars-menu everything but Style and Text Size is under the Advanced section.
                trySet(ps, "setMenuAdvancedOpen", true);
                Mod.call(ps, "save");
                return null;
            });
            ModScreenDriver d = HudMenuProfilesCases.tabByClass(c, "HealthAndManaBarsTab");
            String[] rows = {STYLE_ROW, "Corners", "Icons", "Value Position", "Smooth Fill", "Damage Ghost",
                    "Text Size", "Overflow Mana Colour", "Absorption Colour"};
            List<String> labels = c.onClient(mc -> barTabLabels(d));
            c.note("rows: " + labels);
            for (String row : rows) {
                String label = find(labels, row);
                c.check(label != null, "the tab has no " + row + " row under Sleek");
                if (label != null) {
                    String tip = c.onClient(mc -> (String) Mod.staticCall("gui.SettingTooltips", "describe", TAB, label));
                    c.check(tip != null && !tip.isBlank(), row + " has no tooltip");
                }
            }
            // Bar Style by real presses: left forward, right back (SettingsButtonWidget.cycling).
            String forward = c.onClient(mc -> {
                AbstractWidget w = barWidget(d, STYLE_ROW);
                if (w == null) {
                    return "no Style row";
                }
                w.mouseClicked(new MouseButtonEvent(pillX(w), pillY(w), new MouseButtonInfo(0, 0)), false);
                w.mouseReleased(new MouseButtonEvent(pillX(w), pillY(w), new MouseButtonInfo(0, 0)));
                return String.valueOf(Mod.call(Mod.cfg(PS), "getBarStyle"));
            });
            c.ticks(2);
            c.note("Bar Style after a left click from Sleek: " + forward);
            c.check(forward.equals("BOLD"), "a left click on Bar Style (Sleek) gave " + forward + ", want BOLD");
            String backward = c.onClient(mc -> {
                AbstractWidget w = barWidget(d, STYLE_ROW);
                if (w == null) {
                    return "no Style row";
                }
                w.mouseClicked(new MouseButtonEvent(pillX(w), pillY(w), new MouseButtonInfo(1, 0)), false);
                w.mouseReleased(new MouseButtonEvent(pillX(w), pillY(w), new MouseButtonInfo(1, 0)));
                return String.valueOf(Mod.call(Mod.cfg(PS), "getBarStyle"));
            });
            c.ticks(2);
            c.note("Bar Style after a right click: " + backward);
            c.check(backward.equals("SLEEK"), "a right click on Bar Style (Bold) gave " + backward + ", want SLEEK");
            String onDisk = c.onClient(mc -> {
                Mod.staticCall(PS, "load");
                return String.valueOf(Mod.call(Mod.cfg(PS), "getBarStyle"));
            });
            c.check(onDisk.equals("SLEEK"), "the pressed style was not saved (" + onDisk + " on disk)");
            // Classic is gone: two styles, a left click from Bold wraps to Sleek.
            String styles = c.onClient(mc -> java.util.Arrays.toString(R.cls(STYLE).getEnumConstants()));
            c.check(styles.equals("[SLEEK, BOLD]"), "Bar Style offers " + styles + ", want [SLEEK, BOLD]");
            String wrap = c.onClient(mc -> {
                Mod.call(Mod.cfg(PS), "setBarStyle", Mod.enumValue(STYLE, "BOLD"));
                // The load above made a new config object; the tab's buttons act on the one they were built with.
                try {
                    d.rebuild();
                } catch (Throwable t) {
                    throw new AssertionError(t);
                }
                AbstractWidget w = barWidget(d, STYLE_ROW);
                if (w == null) {
                    return "no Style row";
                }
                w.mouseClicked(new MouseButtonEvent(pillX(w), pillY(w), new MouseButtonInfo(0, 0)), false);
                w.mouseReleased(new MouseButtonEvent(pillX(w), pillY(w), new MouseButtonInfo(0, 0)));
                return String.valueOf(Mod.call(Mod.cfg(PS), "getBarStyle"));
            });
            c.check(wrap.equals("SLEEK"), "a left click on Bar Style (Bold) gave " + wrap + ", want SLEEK");
            String textSize = c.onClient(mc -> {
                try {
                    d.rebuild();
                } catch (Throwable t) {
                    throw new AssertionError(t);
                }
                AbstractWidget w = barWidget(d, "Text Size");
                if (w == null) {
                    return "no Text Size row";
                }
                Object before = Mod.call(Mod.cfg(PS), "getBarTextSize");
                w.mouseClicked(new MouseButtonEvent(pillX(w), pillY(w), new MouseButtonInfo(0, 0)), false);
                w.mouseReleased(new MouseButtonEvent(pillX(w), pillY(w), new MouseButtonInfo(0, 0)));
                Mod.staticCall(PS, "load");
                return before + "->" + Mod.call(Mod.cfg(PS), "getBarTextSize");
            });
            c.note("Text Size by a left click, saved: " + textSize);
            c.check(textSize.equals("NORMAL->SMALL") || textSize.equals("SMALL->NORMAL"),
                    "the Text Size row did not change and save the size: " + textSize);

            // Old files: a saved "classic" loads Bold; no style saved loads Bold; the first overflow mana default (pale
            // cyan, never chosen by anyone) loads as the deep blue one, a chosen colour stays.
            String old = c.onClient(mc -> {
                try {
                    Path f = (Path) R.getStatic(R.cls(PS), "CONFIG_PATH");
                    String json = Files.readString(f);
                    StringBuilder out = new StringBuilder();
                    String[][] variants = {{"\"barStyle\": \"classic\"", "\"overflowManaColor\": " + 0xFF9FE6FF},
                            {"\"barStyleGone\": 0", "\"overflowManaColor\": " + 0xFF123456}};
                    for (String[] v : variants) {
                        String edited = json.replaceAll("\"barStyle\": \"[a-z]+\"", v[0])
                                .replaceAll("\"overflowManaColor\": -?\\d+", v[1]);
                        Files.writeString(f, edited);
                        Mod.staticCall(PS, "load");
                        Object ps = Mod.cfg(PS);
                        out.append(Mod.call(ps, "getBarStyle")).append(',')
                                .append(Integer.toHexString((Integer) Mod.call(ps, "getOverflowManaColor"))).append(';');
                    }
                    Files.writeString(f, json);
                    Mod.staticCall(PS, "load");
                    return out.toString();
                } catch (java.io.IOException e) {
                    return "io: " + e;
                }
            });
            c.note("old files: classic + old overflow default -> " + old.split(";")[0] + "; no style + chosen color -> "
                    + (old.contains(";") ? old.split(";")[1] : "?"));
            c.check(old.equals("BOLD,ff2050c8;BOLD,ff123456;"), "old files load as " + old
                    + ", want BOLD with the new overflow default, then BOLD keeping a chosen color");
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
            reset(c);
            HudFixCases.restore(c, saved);
        }
    }

    private static void trySet(Object cfg, String setter, Object value) {
        for (var m : cfg.getClass().getMethods()) {
            if (m.getName().equals(setter)) {
                Mod.call(cfg, setter, value);
                return;
            }
        }
    }

    // ================================================================================================================
    // 898 the simplified tab (mod bars-menu)
    // ================================================================================================================

    /** Labels the main view may show with Stat Bars on and Advanced closed (killer560, 2026-10-12: "simplify the health
     *  and mana bar menu"): the switch, Edit Bars, one on/off per bar, Style, Text Size and the Advanced header. */
    private static final String[] MAIN_ROWS = {"Stat Bars", "Edit Bars", "Health Bar", "Mana Bar", "Defense Bar",
            "Vitality Bar", "Other Resource Bar", "XP Bar", "Style", "Text Size", "▶ Advanced"};

    static void menu(UiCase c) throws Exception {
        boolean has = c.onClient(mc -> {
            for (var m : Mod.cfg(PS).getClass().getMethods()) {
                if (m.getName().equals("isMenuAdvancedOpen")) {
                    return true;
                }
            }
            return false;
        });
        if (!has) {
            c.problem("this jar has no Advanced section on the Health and Mana Bars tab (PlayerStatsConfig"
                    + ".isMenuAdvancedOpen) - the simplified menu is missing");
            return;
        }
        Map<Path, byte[]> saved = HudFixCases.snapshot(c, PS, HUD);
        int[] window = HudEditorCases.windowSize(c);
        int gui = c.onClient(mc -> mc.options.guiScale().get());
        try {
            HudEditorCases.setWindow(c, 1920, 1080, 2);
            c.onClient(mc -> {
                Object ps = Mod.cfg(PS);
                Mod.call(ps, "setEnabled", true);
                Mod.call(ps, "setMenuAdvancedOpen", false);
                Mod.call(ps, "setReadoutOn", r("HEALTH_BAR"), true);
                Mod.call(ps, "setReadoutOn", r("MANA_BAR"), true);
                Mod.call(ps, "save");
                Mod.call(Mod.cfg(HUD), "setAutoScale", false);
                return null;
            });
            // Only this tab's section open (393's helper), so the pictures show it.
            ModScreenDriver d = StatBarsCases.open(c);
            c.ticks(2);
            List<String> main = c.onClient(mc -> barTabLabels(d));
            c.note("main view (" + main.size() + " rows): " + main);
            c.check(main.size() == MAIN_ROWS.length, "the main view has " + main.size() + " rows, want "
                    + MAIN_ROWS.length + ": " + main);
            for (String want : MAIN_ROWS) {
                c.check(find(main, want) != null, "the main view has no " + want + " row");
            }
            for (String hidden : new String[]{"Corners", "Icons", "Smooth Fill", "Damage Ghost", "Layout", "Bar Width",
                    "Health Colour", "Overflow Mana Colour", "Health Text", "Hearts"}) {
                c.check(find(main, hidden) == null, "the main view still shows " + hidden + " (it belongs in Advanced)");
            }
            copyScreen(c, "menu-collapsed");

            // Stat Bars off: the switch and Advanced only.
            List<String> off = c.onClient(mc -> {
                Mod.call(Mod.cfg(PS), "setEnabled", false);
                rebuildQuiet(d);
                return barTabLabels(d);
            });
            c.note("with Stat Bars off: " + off);
            c.check(off.size() == 2 && find(off, "Stat Bars") != null && find(off, "▶ Advanced") != null,
                    "with Stat Bars off the main view is " + off + ", want Stat Bars and Advanced");

            // Search finds a setting inside the closed section, and leaves it closed.
            String search = c.onClient(mc -> {
                Object tab = null;
                for (Object t : allTabs(d)) {
                    if (t.getClass().getSimpleName().equals("HealthAndManaBarsTab")) {
                        tab = t;
                    }
                }
                if (tab == null) {
                    return "no tab";
                }
                boolean ghost = (Boolean) Mod.call(tab, "matchesSearch", "Damage Ghost");
                boolean corner = (Boolean) Mod.call(tab, "matchesSearch", "corners");
                boolean nothing = (Boolean) Mod.call(tab, "matchesSearch", "zzqqxx");
                // The menu's search lists top-level tabs; the one holding this tab must stay listed.
                Object top = null;
                List<ModScreenDriver.Node> tree = d.tree();
                for (int i = 0; i < tree.size(); i++) {
                    List<ModScreenDriver.Node> sub = new ArrayList<>();
                    ModScreenDriver.flatten(List.of(tree.get(i)), sub);
                    for (ModScreenDriver.Node n : sub) {
                        if (n.tab() == tab) {
                            top = d.topTabs().get(i);
                        }
                    }
                }
                d.search("Damage Ghost");
                boolean listed = false;
                try {
                    for (Object t : d.visibleTabs()) {
                        listed |= t == top;
                    }
                } catch (Throwable e) {
                    return "visibleTabs threw " + e;
                }
                d.search("");
                boolean stillClosed = !(Boolean) Mod.call(Mod.cfg(PS), "isMenuAdvancedOpen");
                return ghost + "," + corner + "," + nothing + "," + listed + "," + stillClosed;
            });
            c.note("search (Advanced closed, Stat Bars off): 'Damage Ghost', 'corners', 'zzqqxx' match, tab listed for"
                    + " 'Damage Ghost', Advanced still closed -> " + search);
            c.check(search.equals("true,true,false,true,true"), "search over the closed Advanced section: " + search
                    + ", want true,true,false,true,true");

            // Advanced by a real press: opens, stays open on disk, holds the rest. The search closed the tab's section, so
            // it is opened again; the header is matched exactly ("Advanced Position" is another tab in this category).
            c.onClient(mc -> {
                Mod.call(Mod.cfg(PS), "setEnabled", true);
                return null;
            });
            ModScreenDriver d2 = StatBarsCases.open(c);
            String opened = c.onClient(mc -> {
                AbstractWidget h = null;
                for (AbstractWidget w : d2.content()) {
                    if (ModScreenDriver.label(w).equals("▶ Advanced")) {
                        h = w;
                    }
                }
                if (h == null) {
                    return "no Advanced header";
                }
                ModScreenDriver.press(h);
                rebuildQuiet(d2);
                Mod.staticCall(PS, "load");
                return String.valueOf(Mod.call(Mod.cfg(PS), "isMenuAdvancedOpen"));
            });
            c.check(opened.equals("true"), "pressing Advanced did not open it and save that (" + opened + ")");
            List<String> open = c.onClient(mc -> {
                rebuildQuiet(d2);
                return barTabLabels(d2);
            });
            c.note("Advanced open (" + open.size() + " rows): " + open);
            for (String want : new String[]{"▼ Advanced", "Layout", "Corners", "Icons", "Show Value", "Smooth Fill",
                    "Damage Ghost", "Bar Width", "Bar Height", "Health Colour", "Mana Colour", "Absorption Colour",
                    "Overflow Mana Colour", "Background", "Health Text", "Text Shadow", "Hearts", "XP Bar And Level",
                    "Held Item Name"}) {
                c.check(find(open, want) != null, "Advanced has no " + want + " row");
            }
            c.check(find(open, "Hide Vanilla XP Bar") == null, "the duplicate Hide Vanilla XP Bar row is back");
            for (String l : open) {
                String key = l.contains(":") ? l.substring(0, l.indexOf(':')) : l;
                if (key.isBlank() || key.endsWith("is on") || key.equals("Always") || key.equals("Look")
                        || key.equals("Colors") || key.equals("Text readouts") || key.equals("Hide")) {
                    continue;
                }
                String tip = c.onClient(mc -> (String) Mod.staticCall("gui.SettingTooltips", "describe", TAB, l));
                c.check(tip != null && !tip.isBlank(), "row '" + l + "' has no tooltip");
            }
            copyScreen(c, "menu-expanded-top");
            c.onClient(mc -> {
                d2.scrollTo(d2.maxScroll() / 2);
                rebuildQuiet(d2);
                return null;
            });
            c.ticks(2);
            copyScreen(c, "menu-expanded-middle");
            c.onClient(mc -> {
                d2.scrollTo(d2.maxScroll());
                rebuildQuiet(d2);
                return null;
            });
            c.ticks(2);
            copyScreen(c, "menu-expanded-bottom");
            c.onClient(mc -> {
                d2.scrollTo(0);
                McCompat.setScreen(mc, null);
                return null;
            });
            v3Shots(c);
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
            reset(c);
            HudEditorCases.restoreWindow(c, window, gui);
            HudFixCases.restore(c, saved);
        }
    }

    /** v3 pictures: Bold and Sleek with the rounder corners, the Vitality Bar with its symbol, a damage ghost and an
     *  overflow layer, so the rounded fill, ghost and overflow ends show. */
    private static void v3Shots(UiCase c) throws Exception {
        HudEditorCases.setWindow(c, 854, 480, 2);
        int gs = c.onClient(mc -> mc.getWindow().getGuiScale());
        for (String style : new String[]{"BOLD", "SLEEK"}) {
            setup(c, style, new String[]{"HEALTH_BAR", "MANA_BAR", "VITALITY_BAR", "DEFENCE_BAR", "XP_BAR",
                    "OTHER_BAR"}, true, false, false);
            c.onClient(mc -> {
                Object ps = Mod.cfg(PS);
                for (Object rr : R.cls(READOUT).getEnumConstants()) {
                    Mod.call(ps, "setReadoutColor", rr, (Integer) R.get(rr, "defaultColor"));
                }
                Mod.call(ps, "setAbsorptionColor", (Integer) R.getStatic(R.cls(PS), "DEFAULT_ABSORPTION_COLOR"));
                Mod.call(ps, "setOverflowManaColor", (Integer) R.getStatic(R.cls(PS), "DEFAULT_OVERFLOW_MANA_COLOR"));
                Mod.call(ps, "setBarBackground", (Integer) R.getStatic(R.cls(PS), "DEFAULT_BAR_BACKGROUND"));
                Mod.call(ps, "setBarWidth", 150);
                Mod.call(ps, "save");
                Object hud = Mod.cfg(HUD);
                String[] ids = {"statbar_health", "statbar_mana", "statbar_vitality", "statbar_defence", "statbar_xp",
                        "statbar_other"};
                for (int i = 0; i < ids.length; i++) {
                    Mod.call(hud, "setScale", ids[i], 1.5f);
                    Mod.call(hud, "setPosition", ids[i], 20, 12 + i * 26);
                }
                Mod.call(hud, "save");
                Class<?> f = R.cls(FEATURE);
                R.setStatic(f, "healthCur", 1250L);
                R.setStatic(f, "healthMax", 1000L);
                R.setStatic(f, "manaCur", 700L);
                R.setStatic(f, "manaMax", 1000L);
                R.setStatic(f, "overflowMana", 300L);
                R.setStatic(f, "vitalityCur", 117L);
                R.setStatic(f, "vitalityMax", 117L);
                R.setStatic(f, "defenceValue", 900L);
                R.setStatic(f, "otherCur", 40L);
                R.setStatic(f, "otherMax", 100L);
                return null;
            });
            c.ticks(4);
            String name = "v3-" + style.toLowerCase(Locale.ROOT);
            BufferedImage img = HudFixCases.shot(c, name);
            copyCrop(c, name, img, gs, 8, 6, 262, 12 + 6 * 26);
            int[] v = measured(c, "VITALITY_BAR");
            c.check(v[0] > 0, name + ": the Vitality Bar did not draw");
        }
        // The damage ghost with the rounded corners, mid-drain.
        setup(c, "BOLD", new String[]{"HEALTH_BAR"}, true, true, true);
        place(c, "statbar_health", BX, BY);
        c.onClient(mc -> {
            Mod.call(Mod.cfg(PS), "setReadoutColor", r("HEALTH_BAR"), (Integer) R.get(r("HEALTH_BAR"), "defaultColor"));
            return null;
        });
        health(c, 1000, 1000);
        c.ticks(20);
        c.onClient(mc -> {
            R.setStatic(R.cls(FEATURE), "healthCur", 250L);
            return null;
        });
        for (int i = 0; i < 400; i++) {
            c.ticks(1);
            int[] m = c.onClient(mc -> measuredNow("HEALTH_BAR"));
            if (m[3] > 8 && m[3] < m[0] - m[1] - 8 && m[1] == Math.round(m[0] * 0.25f)) {
                BufferedImage img = HudFixCases.shot(c, "v3-ghost");
                copyCrop(c, "v3-ghost", img, gs, BX - 6, BY - 6, BX + BW + 6, BY + BH + 6);
                break;
            }
        }
    }

    /** Every top-level tab and every sub-tab of the menu. */
    private static List<Object> allTabs(ModScreenDriver d) {
        List<Object> out = new ArrayList<>();
        List<ModScreenDriver.Node> flat = new ArrayList<>();
        ModScreenDriver.flatten(d.tree(), flat);
        for (ModScreenDriver.Node n : flat) {
            out.add(n.tab());
        }
        return out;
    }

    /** The labels the Health and Mana Bars tab built (its rows only, not another expanded section's). */
    private static List<String> barTabLabels(ModScreenDriver d) {
        List<String> out = new ArrayList<>();
        boolean in = false;
        for (AbstractWidget w : d.content()) {
            String l = ModScreenDriver.label(w);
            if (l.startsWith("Stat Bars")) {
                in = true;
            } else if (in && (l.startsWith("▼ ") || l.startsWith("▶ ")) && !l.contains("Advanced")) {
                break;
            }
            if (in) {
                out.add(l);
            }
        }
        return out;
    }

    /** The first row of the Health and Mana Bars tab whose label starts with {@code prefix}. */
    private static AbstractWidget barWidget(ModScreenDriver d, String prefix) {
        boolean in = false;
        for (AbstractWidget w : d.content()) {
            String l = ModScreenDriver.label(w);
            if (l.startsWith("Stat Bars")) {
                in = true;
            }
            if (in && l.startsWith(prefix)) {
                return w;
            }
        }
        return null;
    }

    private static void rebuildQuiet(ModScreenDriver d) {
        try {
            d.rebuild();
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    /** A screenshot of the menu as it is, into TESTKIT_BARS_SHOTS as {@code name}-<mc>-<jar>.png. */
    private static void copyScreen(UiCase c, String name) throws Exception {
        // The cursor off the menu, so no row's tooltip covers the picture.
        c.ctx().getInput().setCursorPos(2, 2);
        c.ticks(2);
        Path taken = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(c.name() + "-" + name));
        dev.testkit.harness.Report.screenshot(c.name() + "-" + name, taken);
        BufferedImage img = javax.imageio.ImageIO.read(taken.toFile());
        int gs = c.onClient(mc -> mc.getWindow().getGuiScale());
        copyCrop(c, name, img, gs, 0, 0, img.getWidth() / gs, img.getHeight() / gs);
    }

    // ================================================================================================================
    // 899 his bars config: every visible bar has its icon (mod bars-icon)
    // ================================================================================================================

    /** killer560, 2026-10-13: "the vitality symbol still doesn't show" - his Vitality Bar is 50 units wide in the
     *  right-of-hotbar column, under the old full-size icon rule (width at least 3 x thickness + 20). His config files
     *  (bars and the bars' HUD entries, copied from the Mod Only Test instance) are loaded as they are, the bars given
     *  the values his screenshot shows, at two window sizes. */
    static void hisIcons(UiCase c) throws Exception {
        if (!supported(c)) {
            return;
        }
        boolean has = c.onClient(mc -> {
            try {
                R.cls(PAINTER).getField("lastIcon");
                return true;
            } catch (NoSuchFieldException e) {
                return false;
            }
        });
        if (!has) {
            c.problem("this jar records no icon per bar (BarPainter.lastIcon) - before mod bars-icon");
            return;
        }
        Map<Path, byte[]> saved = HudFixCases.snapshot(c, PS, HUD);
        int[] window = HudEditorCases.windowSize(c);
        int gui = c.onClient(mc -> mc.options.guiScale().get());
        try {
            int[][] windows = {{1920, 1080, 2}, {2560, 1440, 3}};
            for (int[] win : windows) {
                HudEditorCases.setWindow(c, win[0], win[1], win[2]);
                // His files, as they are.
                c.onClient(mc -> {
                    try {
                        Files.writeString((Path) R.getStatic(R.cls(PS), "CONFIG_PATH"),
                                resource("bars-his-1013/killer560smod-playerstats.json"));
                        Path hudPath = (Path) R.getStatic(R.cls(HUD), "CONFIG_PATH");
                        Files.writeString(hudPath, resource("bars-his-1013/killer560smod-hud.json"));
                    } catch (java.io.IOException e) {
                        throw new AssertionError(e);
                    }
                    Mod.staticCall(HUD, "load");
                    Mod.staticCall(PS, "load");
                    McCompat.setScreen(mc, null);
                    McCompat.clearChatAndToasts(mc);
                    Class<?> f = R.cls(FEATURE);
                    R.setStatic(f, "healthCur", 3448L);
                    R.setStatic(f, "healthMax", 3073L);
                    R.setStatic(f, "manaCur", 2344L);
                    R.setStatic(f, "manaMax", 2344L);
                    R.setStatic(f, "vitalityCur", 117L);
                    R.setStatic(f, "vitalityMax", 117L);
                    R.setStatic(f, "otherCur", 40L);
                    R.setStatic(f, "otherMax", 100L);
                    return null;
                });
                c.ticks(10);
                BarPixels.settle(c);
                String tag = "gui" + win[2];
                int gs = c.onClient(mc -> mc.getWindow().getGuiScale());
                BufferedImage on = HudFixCases.shot(c, "his-" + tag);
                int gw = on.getWidth() / gs;
                int gh = on.getHeight() / gs;
                copyCrop(c, "icons-his-" + tag, on, gs, gw / 2 - 200, gh - 80, gw / 2 + 200, gh);
                String[] bars = {"HEALTH_BAR", "MANA_BAR", "VITALITY_BAR", "XP_BAR", "OTHER_BAR"};
                // Per bar: {drawn, icon units, box x0, y0, x1, y1 (GUI), scale}.
                List<double[]> info = new ArrayList<>();
                for (String b : bars) {
                    info.add(c.onClient(mc -> {
                        Class<?> p = R.cls(PAINTER);
                        int i = ordinal(b);
                        long drawn = ((long[]) R.getStatic(p, "lastDrawNanos"))[i];
                        boolean fresh = drawn > 0 && System.nanoTime() - drawn < 500_000_000L;
                        float[] sb = (float[]) R.getStatic(p, "lastScreenBox");
                        Object e = Mod.staticCall("hud.HudElementRegistry", "byId", (String) R.get(r(b), "hudId"));
                        float s = ((Number) Mod.staticCall("hud.HudElementRegistry", "resolveScale", e)).floatValue();
                        return new double[]{fresh ? 1 : 0, ((int[]) R.getStatic(p, "lastIcon"))[i], sb[i * 4],
                                sb[i * 4 + 1], sb[i * 4 + 2], sb[i * 4 + 3], s};
                    }));
                }
                // The same scene with every bar off: what the icon squares show without the bars.
                c.onClient(mc -> {
                    Object ps = Mod.cfg(PS);
                    for (String b : bars) {
                        Mod.call(ps, "setReadoutOn", r(b), false);
                    }
                    return null;
                });
                c.ticks(4);
                BufferedImage off = HudFixCases.shot(c, "his-" + tag + "-none");
                int drawnBars = 0;
                for (int k = 0; k < bars.length; k++) {
                    double[] v = info.get(k);
                    if (v[0] < 1) {
                        c.note(tag + " " + bars[k] + ": not drawn (his config has it "
                                + (bars[k].equals("OTHER_BAR") ? "on, " : "") + "off or with no value)");
                        continue;
                    }
                    drawnBars++;
                    double w = v[4] - v[2];
                    double h = v[5] - v[3];
                    int icon = (int) v[1];
                    // The icon square, centred on the bar's height at its left.
                    int x0 = (int) Math.floor(v[2] * gs);
                    int x1 = (int) Math.ceil((v[2] + icon * v[6]) * gs);
                    int y0 = (int) Math.floor((v[3] + (h - icon * v[6]) / 2) * gs);
                    int y1 = (int) Math.ceil((v[3] + (h + icon * v[6]) / 2) * gs);
                    int changed = changedCount(on, off, x0, y0, x1, y1);
                    c.note(String.format(Locale.ROOT, "%s %s: box %.1fx%.1f GUI, icon %d units, %d px of its square"
                            + " drawn", tag, bars[k], w, h, icon, changed));
                    c.check(icon > 0, tag + " " + bars[k] + " (" + Math.round(w) + "x" + Math.round(h) + " GUI) draws no"
                            + " icon");
                    c.check(icon == 0 || changed >= icon * v[6] * gs, tag + " " + bars[k] + ": its icon square changed"
                            + " only " + changed + " px against the bar turned off - nothing was drawn there");
                }
                c.check(drawnBars >= 4, tag + ": only " + drawnBars + " of his bars drew (want Health, Mana, Vitality,"
                        + " XP and Other)");
                c.onClient(mc -> {
                    Class<?> f = R.cls(FEATURE);
                    for (String n : new String[]{"healthCur", "healthMax", "manaCur", "manaMax", "vitalityCur",
                            "vitalityMax", "otherCur", "otherMax"}) {
                        R.setStatic(f, n, -1L);
                    }
                    return null;
                });
            }
        } finally {
            reset(c);
            HudEditorCases.restoreWindow(c, window, gui);
            HudFixCases.restore(c, saved);
        }
    }

    private static String resource(String name) throws java.io.IOException {
        try (var in = BarStyleCases.class.getResourceAsStream("/testkit-ui/" + name)) {
            if (in == null) {
                throw new java.io.IOException("missing resource " + name);
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static String readBack() {
        Object ps = Mod.cfg(PS);
        return Mod.call(ps, "getBarStyle") + "," + Mod.call(ps, "isBarRounded") + "," + Mod.call(ps, "isBarIcons")
                + "," + Mod.call(ps, "getBarValuePosition") + "," + Mod.call(ps, "isBarSmooth") + ","
                + Mod.call(ps, "isBarDamageGhost") + "," + Integer.toHexString((Integer) Mod.call(ps,
                "getOverflowManaColor"));
    }

    private static List<String> labels(ModScreenDriver d) {
        List<String> out = new ArrayList<>();
        for (AbstractWidget w : d.content()) {
            out.add(ModScreenDriver.label(w));
        }
        return out;
    }

    private static String find(List<String> labels, String prefix) {
        for (String l : labels) {
            if (l.startsWith(prefix)) {
                return l;
            }
        }
        return null;
    }

    private static AbstractWidget widget(ModScreenDriver d, String prefix) {
        for (AbstractWidget w : d.content()) {
            if (ModScreenDriver.label(w).startsWith(prefix)) {
                return w;
            }
        }
        return null;
    }

    // ================================================================================================================
    // 894 easing + damage ghost
    // ================================================================================================================

    static void ghost(UiCase c) throws Exception {
        if (!supported(c)) {
            return;
        }
        Map<Path, byte[]> saved = HudFixCases.snapshot(c, PS, HUD);
        int[] window = HudEditorCases.windowSize(c);
        int gui = c.onClient(mc -> mc.options.guiScale().get());
        try {
            HudEditorCases.setWindow(c, 854, 480, 2);
            setup(c, "SLEEK", new String[]{"HEALTH_BAR"}, true, true, true);
            place(c, "statbar_health", BX, BY);
            health(c, 1000, 1000);
            c.ticks(20);
            int[] full = measured(c, "HEALTH_BAR");
            c.check(full[1] == full[0] && full[3] == 0, "the bar did not settle full: fill " + full[1] + " of "
                    + full[0] + ", ghost " + full[3]);
            long t0 = c.onClient(mc -> {
                R.setStatic(R.cls(FEATURE), "healthCur", 300L);
                return System.nanoTime();
            });
            int target = Math.round(full[0] * 0.3f);
            List<long[]> samples = new ArrayList<>();
            boolean shotTaken = false;
            for (int i = 0; i < 600; i++) {
                c.ticks(1);
                long[] s = c.onClient(mc -> {
                    int[] m = measuredNow("HEALTH_BAR");
                    long drawn = ((long[]) R.getStatic(R.cls(PAINTER), "lastDrawNanos"))[ordinal("HEALTH_BAR")];
                    return new long[]{(drawn - t0) / 1_000_000L, m[1], m[3]};
                });
                if (s[0] < 0) {
                    continue;
                }
                samples.add(s);
                if (!shotTaken && s[0] >= 520 && s[2] > 4 && s[2] < full[0] - target - 4) {
                    BufferedImage mid = HudFixCases.shot(c, "ghost-mid-drain");
                    copyShot(c, "ghost-mid-drain", mid, c.onClient(mc -> mc.getWindow().getGuiScale()), true);
                    shotTaken = true;
                    c.note("mid-drain picture at " + s[0] + " ms: fill " + s[1] + ", ghost " + s[2]);
                }
                if (s[0] > 2500) {
                    break;
                }
            }
            StringBuilder sb = new StringBuilder();
            for (long[] s : samples) {
                if (sb.length() < 900) {
                    sb.append(s[0]).append("ms:").append(s[1]).append('+').append(s[2]).append(' ');
                }
            }
            c.note("after 1000 -> 300 (target " + target + " of " + full[0] + "): " + sb);
            boolean between = false;
            long firstGhost = -1;
            long ghostAtHoldEnd = -1;
            long drainedAt = -1;
            for (long[] s : samples) {
                if (s[1] > target && s[1] < full[0]) {
                    between = true;
                }
                if (firstGhost < 0 && s[2] > 0) {
                    firstGhost = s[2] + s[1];
                }
                if (s[0] <= 380 && s[1] == target) {
                    ghostAtHoldEnd = s[2];
                }
                if (drainedAt < 0 && s[2] == 0 && s[1] == target && s[0] > 0) {
                    drainedAt = s[0];
                }
            }
            long[] last = samples.isEmpty() ? new long[]{0, -1, -1} : samples.get(samples.size() - 1);
            c.check(samples.size() >= 10, "only " + samples.size() + " frames drew in 2.5 s");
            c.check(between, "Smooth Fill: no frame drew a value between 1000 and 300 - it jumped");
            c.check(firstGhost >= full[0] - 2, "the ghost did not start at the old value (" + firstGhost + " of "
                    + full[0] + ")");
            c.check(ghostAtHoldEnd < 0 || ghostAtHoldEnd >= full[0] - target - 2, "the ghost drained during its hold ("
                    + ghostAtHoldEnd + " at <=380 ms)");
            c.check(drainedAt > 400 && drainedAt < 2200, "the ghost drained at " + drainedAt
                    + " ms, want after its 400 ms hold and within ~1.5 s");
            c.check(last[1] == target && last[2] == 0, "the bar did not settle at " + target + " with no ghost: "
                    + last[1] + "+" + last[2]);
            c.check(shotTaken, "no frame mid-drain to picture");

            // Damage Ghost off: the fill still eases, no ghost.
            c.onClient(mc -> {
                Mod.call(Mod.cfg(PS), "setBarDamageGhost", false);
                R.setStatic(R.cls(FEATURE), "healthCur", 1000L);
                return null;
            });
            c.ticks(30);
            c.onClient(mc -> {
                R.setStatic(R.cls(FEATURE), "healthCur", 200L);
                return null;
            });
            int maxGhost = 0;
            for (int i = 0; i < 20; i++) {
                c.ticks(1);
                maxGhost = Math.max(maxGhost, c.onClient(mc -> measuredNow("HEALTH_BAR"))[3]);
            }
            c.check(maxGhost == 0, "Damage Ghost off still drew a ghost of " + maxGhost);
        } finally {
            reset(c);
            HudEditorCases.restoreWindow(c, window, gui);
            HudFixCases.restore(c, saved);
        }
    }

    // ================================================================================================================
    // 895 pictures
    // ================================================================================================================

    static void shots(UiCase c) throws Exception {
        if (!supported(c)) {
            return;
        }
        Map<Path, byte[]> saved = HudFixCases.snapshot(c, PS, HUD);
        int[] window = HudEditorCases.windowSize(c);
        int gui = c.onClient(mc -> mc.options.guiScale().get());
        int taken = 0;
        try {
            HudEditorCases.setWindow(c, 854, 480, 2);
            int gs = c.onClient(mc -> mc.getWindow().getGuiScale());
            String[][] variants = {{"BOLD", "NORMAL", "INSIDE"}, {"BOLD", "SMALL", "INSIDE"},
                    {"SLEEK", "NORMAL", "INSIDE"}, {"SLEEK", "SMALL", "INSIDE"}, {"BOLD", "NORMAL", "BESIDE"},
                    {"SLEEK", "SMALL", "BESIDE"}};
            String[][] states = {{"full", "1000", "1000", "-1", "1000"}, {"half", "500", "1000", "-1", "500"},
                    {"low", "120", "1000", "-1", "60"}, {"overflow", "1250", "1000", "400", "500"}};
            double worst = 99;
            for (String[] v : variants) {
                setup(c, v[0], new String[]{"HEALTH_BAR", "MANA_BAR", "DEFENCE_BAR"}, true, false, false);
                c.onClient(mc -> {
                    Object ps = Mod.cfg(PS);
                    // His colours: the defaults, not the cases' test colours.
                    for (Object rr : R.cls(READOUT).getEnumConstants()) {
                        Mod.call(ps, "setReadoutColor", rr, (Integer) R.get(rr, "defaultColor"));
                    }
                    Mod.call(ps, "setAbsorptionColor", (Integer) R.getStatic(R.cls(PS), "DEFAULT_ABSORPTION_COLOR"));
                    Mod.call(ps, "setOverflowManaColor", (Integer) R.getStatic(R.cls(PS),
                            "DEFAULT_OVERFLOW_MANA_COLOR"));
                    Mod.call(ps, "setBarBackground", (Integer) R.getStatic(R.cls(PS), "DEFAULT_BAR_BACKGROUND"));
                    Mod.call(ps, "setBarTextSize", Mod.enumValue(SIZE, v[1]));
                    Mod.call(ps, "setBarValuePosition", Mod.enumValue(POSITION, v[2]));
                    Mod.call(ps, "setBarWidth", 150);
                    Mod.call(ps, "save");
                    Object hud = Mod.cfg(HUD);
                    for (String id : new String[]{"statbar_health", "statbar_mana", "statbar_defence"}) {
                        Mod.call(hud, "setScale", id, 1.5f);
                    }
                    Mod.call(hud, "save");
                    return null;
                });
                place(c, "statbar_health", 20, 20);
                place(c, "statbar_mana", 20, 50);
                place(c, "statbar_defence", 20, 80);
                for (String[] st : states) {
                    if (v[2].equals("BESIDE") && !st[0].equals("full") && !st[0].equals("low")) {
                        continue;
                    }
                    health(c, Long.parseLong(st[1]), Long.parseLong(st[2]));
                    mana(c, Long.parseLong(st[4]), 1000, Long.parseLong(st[3]));
                    setStatic(c, "defenceValue", st[0].equals("low") ? 50L : 900L);
                    c.ticks(3);
                    String name = "v2-" + v[0].toLowerCase(Locale.ROOT) + "-" + v[1].toLowerCase(Locale.ROOT)
                            + (v[2].equals("BESIDE") ? "-beside" : "") + "-" + st[0];
                    BufferedImage img = HudFixCases.shot(c, name);
                    if (copyShot(c, name, img, gs, false)) {
                        taken++;
                    }
                    int[] h = measured(c, "HEALTH_BAR");
                    c.check(h[0] > 0, name + ": the health bar did not draw");
                    if (v[2].equals("INSIDE")) {
                        int icon = iconRoom(c, 150, BH, v[0]);
                        String[] bars = {"health", "mana", "defence"};
                        int[] ys = {20, 50, 80};
                        StringBuilder line = new StringBuilder(name + " number contrast against white, worst neighbor:");
                        for (int k = 0; k < 3; k++) {
                            // From past the icon: the iron chestplate has white highlights beside light grey.
                            double[] cr = contrast(img, (int) ((20 + icon * 1.5) * gs), ys[k] * gs,
                                    (int) ((20 + 150 * 1.5) * gs),
                                    (int) ((ys[k] + BH * 1.5) * gs));
                            line.append(String.format(Locale.ROOT, " %s %.1f:1 (%d white px)", bars[k], cr[0],
                                    (int) cr[1]));
                            c.check(cr[1] >= 20, name + " " + bars[k] + ": only " + (int) cr[1]
                                    + " white number pixels - the contrast check would be vacuous");
                            c.check(cr[0] >= 7.0, name + " " + bars[k] + String.format(Locale.ROOT,
                                    ": a white number pixel touches a pixel only %.1f:1 against it - the outline does"
                                    + " not hold", cr[0]));
                            worst = Math.min(worst, cr[0]);
                        }
                        c.note(line.toString());
                    }
                }
            }
            String fills = c.onClient(mc -> {
                StringBuilder b = new StringBuilder("white straight on each fill color (no outline):");
                Object ps = Mod.cfg(PS);
                for (String n : new String[]{"HEALTH_BAR", "MANA_BAR", "DEFENCE_BAR"}) {
                    int col = (Integer) Mod.call(ps, "getReadoutColor", r(n));
                    b.append(String.format(Locale.ROOT, " %s #%06X %.1f:1", n, col & 0xFFFFFF, ratio(0xFFFFFF, col)));
                }
                int ov = (Integer) Mod.call(ps, "getOverflowManaColor");
                b.append(String.format(Locale.ROOT, " overflow mana #%06X %.1f:1", ov & 0xFFFFFF, ratio(0xFFFFFF, ov)));
                return b.toString();
            });
            c.note(fills);
            c.note(String.format(Locale.ROOT, "worst contrast next to a white number pixel on any picture: %.1f:1",
                    worst));
            c.note(taken + " picture(s) copied to TESTKIT_BARS_SHOTS");
        } finally {
            reset(c);
            HudEditorCases.restoreWindow(c, window, gui);
            HudFixCases.restore(c, saved);
        }
    }

    /** {lowest contrast ratio between a white (#FFFFFF) pixel and any non-white pixel of its eight neighbours, white
     *  pixels counted} in the region. */
    private static double[] contrast(BufferedImage img, int x0, int y0, int x1, int y1) {
        double worst = 21;
        int whites = 0;
        for (int y = Math.max(1, y0); y < Math.min(img.getHeight() - 1, y1); y++) {
            for (int x = Math.max(1, x0); x < Math.min(img.getWidth() - 1, x1); x++) {
                if ((img.getRGB(x, y) & 0xFFFFFF) != 0xFFFFFF) {
                    continue;
                }
                whites++;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int n = img.getRGB(x + dx, y + dy) & 0xFFFFFF;
                        if (n != 0xFFFFFF) {
                            worst = Math.min(worst, ratio(0xFFFFFF, n));
                        }
                    }
                }
            }
        }
        return new double[]{worst, whites};
    }

    /** WCAG contrast ratio of two sRGB colours. */
    static double ratio(int a, int b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(int rgb) {
        double r = lin(((rgb >> 16) & 0xFF) / 255.0);
        double g = lin(((rgb >> 8) & 0xFF) / 255.0);
        double b = lin((rgb & 0xFF) / 255.0);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double lin(double v) {
        return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    /** Writes the GUI-unit rectangle {@code (x0, y0)..(x1, y1)} of {@code img} to TESTKIT_BARS_SHOTS. */
    private static void copyCrop(UiCase c, String name, BufferedImage img, int gs, int x0, int y0, int x1, int y1) {
        String dir = System.getenv("TESTKIT_BARS_SHOTS");
        if (dir == null || dir.isBlank()) {
            return;
        }
        try {
            int px0 = Math.max(0, x0 * gs);
            int py0 = Math.max(0, y0 * gs);
            BufferedImage crop = img.getSubimage(px0, py0, Math.min(img.getWidth(), x1 * gs) - px0,
                    Math.min(img.getHeight(), y1 * gs) - py0);
            String jar = Mod.isCheat() ? "cheat" : "legit";
            String mcv = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("minecraft").orElseThrow()
                    .getMetadata().getVersion().getFriendlyString();
            Path out = Path.of(dir).resolve(name + "-" + mcv + "-" + jar + ".png");
            Files.createDirectories(out.getParent());
            javax.imageio.ImageIO.write(crop, "png", out.toFile());
        } catch (Exception | Error e) {
            c.note("could not copy " + name + ": " + e);
        }
    }

    /** Crops the last screenshot of this case to the bars' corner and writes it to TESTKIT_BARS_SHOTS. */
    private static boolean copyShot(UiCase c, String name, BufferedImage img, int gs, boolean ghostArea) {
        String dir = System.getenv("TESTKIT_BARS_SHOTS");
        if (dir == null || dir.isBlank()) {
            return false;
        }
        try {
            int x0 = ghostArea ? (BX - 6) * gs : 8 * gs;
            int y0 = ghostArea ? (BY - 6) * gs : 12 * gs;
            int x1 = Math.min(img.getWidth(), ghostArea ? (BX + BW + 6) * gs : 262 * gs);
            int y1 = Math.min(img.getHeight(), ghostArea ? (BY + BH + 6) * gs : 112 * gs);
            BufferedImage crop = img.getSubimage(x0, y0, x1 - x0, y1 - y0);
            String jar = Mod.isCheat() ? "cheat" : "legit";
            String mcv = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("minecraft").orElseThrow()
                    .getMetadata().getVersion().getFriendlyString();
            Path out = Path.of(dir).resolve(name + "-" + mcv + "-" + jar + ".png");
            Files.createDirectories(out.getParent());
            javax.imageio.ImageIO.write(crop, "png", out.toFile());
            return true;
        } catch (Exception | Error e) {
            c.note("could not copy " + name + ": " + e);
            return false;
        }
    }

    // ================================================================================================================
    // 896 Edit Bars
    // ================================================================================================================

    static void editor(UiCase c) throws Exception {
        if (!supported(c)) {
            return;
        }
        Map<Path, byte[]> saved = HudFixCases.snapshot(c, PS, HUD);
        int[] window = HudEditorCases.windowSize(c);
        int gui = c.onClient(mc -> mc.options.guiScale().get());
        try {
            HudEditorCases.setWindow(c, 854, 480, 2);
            setup(c, "BOLD", new String[]{"HEALTH_BAR"}, true, false, false);
            c.onClient(mc -> {
                Object ps = Mod.cfg(PS);
                Mod.call(ps, "setBarValuePosition", Mod.enumValue(POSITION, "BESIDE"));
                Mod.call(ps, "setBarIcons", true);
                Mod.call(ps, "save");
                Object hud = Mod.cfg(HUD);
                Mod.call(hud, "setEditorSnap", false);
                Mod.call(hud, "save");
                return null;
            });
            place(c, "statbar_health", BX, BY);
            health(c, 600, 1000);
            // Everything inside the box: track + frames + icon + the number's room = the element's width.
            String inside = c.onClient(mc -> {
                Object e = Mod.staticCall("hud.HudElementRegistry", "byId", "statbar_health");
                int width = (Integer) Mod.call(e, "width");
                int[] m = measuredNow("HEALTH_BAR");
                int icon = (Integer) Mod.staticCall(PAINTER, "iconRoom", Mod.cfg(PS), width, BH);
                return width + "," + m[0] + "," + icon;
            });
            String[] p = inside.split(",");
            int width = Integer.parseInt(p[0]);
            int track = Integer.parseInt(p[1]);
            int icon = Integer.parseInt(p[2]);
            int rest = width - track - icon - 4;
            c.note("Bold, icon, Beside: box " + width + " = icon " + icon + " + frames 4 + track " + track
                    + " + number room " + rest);
            c.check(icon > 0, "no icon room on a " + width + "x" + BH + " bar with icons on");
            c.check(rest > 10 && rest < 80, "the Beside number's room is " + rest + " units - it is not beside the bar"
                    + " inside the box");
            int gs = c.onClient(mc -> mc.getWindow().getGuiScale());
            BufferedImage img = HudFixCases.shot(c, "bold-beside");
            int[] ink = inkColumns(img, (BX + width) * gs, BY * gs, (BX + width + 60) * gs, (BY + BH) * gs);
            c.check(ink == null, "the bar draws past its box's right end (columns " + (ink == null ? "" : ink[0] + ".."
                    + ink[1]) + ")");

            // One Beside width per column: Health and Defence stacked at one x take the wider of their numbers ("1,000/1,000"
            // against "50 (33.3%)"), so their tracks match; Mana elsewhere ("50/100") keeps its own, longer, track.
            c.onClient(mc -> {
                Object ps = Mod.cfg(PS);
                Mod.call(ps, "setReadoutOn", r("DEFENCE_BAR"), true);
                Mod.call(ps, "setReadoutOn", r("MANA_BAR"), true);
                Mod.call(ps, "save");
                return null;
            });
            place(c, "statbar_health", BX, BY);
            place(c, "statbar_defence", BX, BY + 22);
            place(c, "statbar_mana", 20, BY + 60);
            health(c, 1000, 1000);
            mana(c, 50, 100, -1);
            setStatic(c, "defenceValue", 50L);
            c.ticks(6);
            int[] hT = measured(c, "HEALTH_BAR");
            int[] dT = measured(c, "DEFENCE_BAR");
            int[] mT = measured(c, "MANA_BAR");
            String own = c.onClient(mc -> {
                var font = mc.font;
                return font.width("1,000/1,000") + "," + font.width("50 (33.3%)") + "," + font.width("50/100");
            });
            c.note("Beside column: tracks Health " + hT[0] + ", Defense " + dT[0] + " (stacked), Mana " + mT[0]
                    + " (elsewhere); number widths " + own);
            c.check(hT[0] == dT[0], "stacked Beside bars end at different lengths: Health track " + hT[0]
                    + ", Defense " + dT[0]);
            c.check(mT[0] > hT[0], "the Mana Bar in another column took the column's wider number room (track " + mT[0]
                    + " vs " + hT[0] + ")");
            int gsCol = c.onClient(mc -> mc.getWindow().getGuiScale());
            BufferedImage colImg = HudFixCases.shot(c, "beside-column");
            copyCrop(c, "v2-beside-column", colImg, gsCol, 14, BY - 6, BX + BW + 6, BY + 66);
            // Bold's orange rim marks each track's right end on screen.
            int hr = rightmost(colImg, RIM, BY * gsCol, (BY + BH) * gsCol);
            int dr = rightmost(colImg, RIM, (BY + 22) * gsCol, (BY + 22 + BH) * gsCol);
            c.note("Beside column on screen: the rims end at px " + hr + " (Health) and " + dr + " (Defense)");
            c.check(hr > 0 && hr == dr, "on screen the stacked bars end at px " + hr + " and " + dr);
            c.onClient(mc -> {
                Object ps = Mod.cfg(PS);
                Mod.call(ps, "setReadoutOn", r("DEFENCE_BAR"), false);
                Mod.call(ps, "setReadoutOn", r("MANA_BAR"), false);
                Mod.call(ps, "save");
                return null;
            });
            place(c, "statbar_health", BX, BY);
            health(c, 600, 1000);

            // Edit Bars: a real drag, saved.
            Screen ed = c.onClient(mc -> {
                try {
                    Screen s = (Screen) R.cls("hud.HudEditorScreen").getMethod("forBars", Screen.class)
                            .invoke(null, (Object) null);
                    McCompat.setScreen(mc, s);
                    return s;
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            c.ticks(3);
            String drag = c.onClient(mc -> {
                int[] pos = livePos(ed, "statbar_health");
                if (pos == null) {
                    return "no live position for statbar_health";
                }
                var info = new MouseButtonInfo(0, 0);
                double gx = pos[0] + 30;
                double gy = pos[1] + BH / 2.0;
                ed.mouseClicked(new MouseButtonEvent(gx, gy, info), false);
                Object dragging = R.get(ed, "draggingId");
                int edges = (Integer) R.get(ed, "resizeEdges");
                ed.mouseDragged(new MouseButtonEvent(gx + 23, gy + 11, info), 23, 11);
                ed.mouseReleased(new MouseButtonEvent(gx + 23, gy + 11, info));
                int[] want = (int[]) Mod.staticCall("hud.HudElementRegistry", "toSaved", pos[0] + 23, pos[1] + 11);
                Mod.staticCall(HUD, "load");
                int[] savedPos = (int[]) Mod.call(Mod.cfg(HUD), "getPosition", "statbar_health", -999, -999);
                return "grabbed " + dragging + " edges " + edges + "; from " + pos[0] + "," + pos[1] + " saved (disk) "
                        + savedPos[0] + "," + savedPos[1] + " want " + want[0] + "," + want[1]
                        + ("statbar_health".equals(dragging) && edges == 0 && savedPos[0] == want[0]
                        && savedPos[1] == want[1] ? " OK" : " WRONG");
            });
            c.note("Edit Bars drag: " + drag);
            c.check(drag.endsWith(" OK"), "the Edit Bars drag did not move and save the Bold bar: " + drag);
            c.ticks(2);

            // An edge drag: the right edge 20 units out, saved as the bar's own width.
            String resize = c.onClient(mc -> {
                int[] pos = livePos(ed, "statbar_health");
                Object e = Mod.staticCall("hud.HudElementRegistry", "byId", "statbar_health");
                int right = (Integer) R.getStatic(R.cls("hud.HudEditorScreen"), "RIGHT");
                double gy = pos[1] + BH / 2.0;
                double hx = -1;
                for (double x = pos[0] + 20; x < pos[0] + 400; x += 0.5) {
                    int h;
                    try {
                        var m = R.cls("hud.HudEditorScreen").getDeclaredMethod("handleAt",
                                R.cls("hud.HudElement"), double.class, double.class);
                        m.setAccessible(true);
                        h = (Integer) m.invoke(ed, e, x, gy);
                    } catch (ReflectiveOperationException ex) {
                        return "handleAt: " + ex;
                    }
                    if (h == right) {
                        hx = x;
                        break;
                    }
                }
                if (hx < 0) {
                    return "no right-edge handle found";
                }
                int before = (Integer) Mod.call(Mod.cfg(PS), "getBarWidth", r("HEALTH_BAR"));
                var info = new MouseButtonInfo(0, 0);
                ed.mouseClicked(new MouseButtonEvent(hx, gy, info), false);
                int edges = (Integer) R.get(ed, "resizeEdges");
                ed.mouseDragged(new MouseButtonEvent(hx + 20, gy, info), 20, 0);
                ed.mouseReleased(new MouseButtonEvent(hx + 20, gy, info));
                Mod.staticCall(PS, "load");
                int after = (Integer) Mod.call(Mod.cfg(PS), "getBarWidth", r("HEALTH_BAR"));
                return "edges " + edges + ", width " + before + " -> " + after + " (disk)"
                        + (edges == right && after == before + 20 ? " OK" : " WRONG");
            });
            c.note("Edit Bars right-edge drag: " + resize);
            c.check(resize.endsWith(" OK"), "the Edit Bars edge drag did not resize and save the bar: " + resize);
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
            reset(c);
            HudEditorCases.restoreWindow(c, window, gui);
            HudFixCases.restore(c, saved);
        }
    }

    @SuppressWarnings("unchecked")
    private static int[] livePos(Screen editor, String id) {
        Map<String, int[]> live = (Map<String, int[]>) R.get(editor, "livePositions");
        int[] p = live.get(id);
        return p == null ? null : p.clone();
    }

    // ================================================================================================================
    // helpers
    // ================================================================================================================

    private static void setup(UiCase c, String style, String[] on, boolean showValue, boolean smooth, boolean ghost) {
        c.onClient(mc -> {
            Object ps = Mod.cfg(PS);
            Mod.call(ps, "setEnabled", true);
            Mod.call(ps, "setPredefinedLayout", false);
            for (Object rr : R.cls(READOUT).getEnumConstants()) {
                Mod.call(ps, "setReadoutOn", rr, false);
            }
            for (String o : on) {
                Mod.call(ps, "setReadoutOn", r(o), true);
            }
            Mod.call(ps, "setReadoutColor", r("HEALTH_BAR"), HEALTH);
            Mod.call(ps, "setReadoutColor", r("MANA_BAR"), MANA);
            Mod.call(ps, "setReadoutColor", r("DEFENCE_BAR"), DEFENCE);
            Mod.call(ps, "setBarBackground", BG);
            Mod.call(ps, "setAbsorptionColor", ABSORB);
            Mod.call(ps, "setOverflowManaColor", OVERFLOW);
            Mod.call(ps, "setBarShowValue", showValue);
            Mod.call(ps, "setTextShadow", true);
            Mod.call(ps, "setBarWidth", BW);
            Mod.call(ps, "setBarHeight", BH);
            Mod.call(ps, "setBarStyle", Mod.enumValue(STYLE, style));
            Mod.call(ps, "setBarRounded", true);
            Mod.call(ps, "setBarIcons", true);
            Mod.call(ps, "setBarValuePosition", Mod.enumValue(POSITION, "INSIDE"));
            Mod.call(ps, "setBarSmooth", smooth);
            Mod.call(ps, "setBarDamageGhost", ghost);
            Mod.call(ps, "save");
            Object hud = Mod.cfg(HUD);
            for (Object rr : R.cls(READOUT).getEnumConstants()) {
                Mod.call(hud, "setScale", (String) R.get(rr, "hudId"), 1.0f);
            }
            Mod.call(hud, "setGlobalScale", 1.0f);
            Mod.call(hud, "setAutoScale", false);
            Mod.call(hud, "save");
            McCompat.setScreen(mc, null);
            McCompat.clearChatAndToasts(mc);
            return null;
        });
        c.ticks(3);
    }

    private static void place(UiCase c, String id, int x, int y) {
        c.onClient(mc -> {
            Mod.call(Mod.cfg(HUD), "setPosition", id, x, y);
            Mod.call(Mod.cfg(HUD), "save");
            return null;
        });
        c.ticks(2);
    }

    private static void health(UiCase c, long cur, long max) {
        c.onClient(mc -> {
            R.setStatic(R.cls(FEATURE), "healthCur", cur);
            R.setStatic(R.cls(FEATURE), "healthMax", max);
            return null;
        });
        c.ticks(3);
    }

    private static void mana(UiCase c, long cur, long max, long overflow) {
        c.onClient(mc -> {
            R.setStatic(R.cls(FEATURE), "manaCur", cur);
            R.setStatic(R.cls(FEATURE), "manaMax", max);
            R.setStatic(R.cls(FEATURE), "overflowMana", overflow);
            return null;
        });
        c.ticks(3);
    }

    private static void setStatic(UiCase c, String field, long v) {
        c.onClient(mc -> {
            R.setStatic(R.cls(FEATURE), field, v);
            return null;
        });
        c.ticks(2);
    }

    /** Back to nothing read, no absorption. */
    private static void reset(UiCase c) {
        try {
            c.onClient(mc -> {
                Class<?> f = R.cls(FEATURE);
                for (String n : new String[]{"healthCur", "healthMax", "manaCur", "manaMax", "defenceValue",
                        "overflowMana"}) {
                    R.setStatic(f, n, -1L);
                }
                return null;
            });
        } catch (Throwable t) {
            c.note("reset: " + UiCase.describe(t));
        }
    }

    /** {track, fill, overflow, ghost} of the readout's last draw, after making sure it drew since now. */
    private static int[] measured(UiCase c, String readout) {
        long since = c.onClient(mc -> System.nanoTime());
        for (int i = 0; i < 40; i++) {
            c.ticks(1);
            long drawn = c.onClient(mc -> ((long[]) R.getStatic(R.cls(PAINTER), "lastDrawNanos"))[ordinal(readout)]);
            if (drawn > since) {
                break;
            }
        }
        long drawn = c.onClient(mc -> ((long[]) R.getStatic(R.cls(PAINTER), "lastDrawNanos"))[ordinal(readout)]);
        c.check(drawn > since, readout + " did not draw in 40 ticks");
        return c.onClient(mc -> measuredNow(readout));
    }

    private static int[] measuredNow(String readout) {
        Class<?> p = R.cls(PAINTER);
        int i = ordinal(readout);
        return new int[]{((int[]) R.getStatic(p, "lastTrack"))[i], ((int[]) R.getStatic(p, "lastFill"))[i],
                ((int[]) R.getStatic(p, "lastOverflow"))[i], ((int[]) R.getStatic(p, "lastGhost"))[i]};
    }

    private static int iconRoom(UiCase c, int w, int h, String style) {
        return c.onClient(mc -> (Integer) Mod.staticCall(PAINTER, "iconRoom", Mod.cfg(PS), w, h));
    }

    private static int ordinal(String readout) {
        return ((Enum<?>) r(readout)).ordinal();
    }

    private static Object r(String name) {
        return Mod.enumValue(READOUT, name);
    }

    /** {first, last} pixel column in the region where any pixel differs (summed RGB > 30), or null. */
    private static int[] changedColumns(BufferedImage a, BufferedImage b, int x0, int y0, int x1, int y1) {
        int first = -1;
        int last = -1;
        for (int x = Math.max(0, x0); x < Math.min(a.getWidth(), x1); x++) {
            for (int y = Math.max(0, y0); y < Math.min(a.getHeight(), y1); y++) {
                if (delta(a.getRGB(x, y), b.getRGB(x, y)) > 30) {
                    if (first < 0) {
                        first = x;
                    }
                    last = x;
                    break;
                }
            }
        }
        return first < 0 ? null : new int[]{first, last};
    }

    /** The rightmost pixel column holding exactly {@code colour} in rows {@code y0..y1}; -1 when none. */
    private static int rightmost(BufferedImage img, int colour, int y0, int y1) {
        int best = -1;
        for (int y = Math.max(0, y0); y < Math.min(img.getHeight(), y1); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if ((img.getRGB(x, y) & 0xFFFFFF) == (colour & 0xFFFFFF)) {
                    best = Math.max(best, x);
                }
            }
        }
        return best;
    }

    private static int changedCount(BufferedImage a, BufferedImage b, int x0, int y0, int x1, int y1) {
        int n = 0;
        for (int y = Math.max(0, y0); y < Math.min(a.getHeight(), y1); y++) {
            for (int x = Math.max(0, x0); x < Math.min(a.getWidth(), x1); x++) {
                if (delta(a.getRGB(x, y), b.getRGB(x, y)) > 30) {
                    n++;
                }
            }
        }
        return n;
    }

    /** Columns in the region holding a near-black or bar-coloured pixel the plain superflat sky/ground has not: the
     *  bar's frame (black), or its value text (its colour). Null when none. */
    private static int[] inkColumns(BufferedImage img, int x0, int y0, int x1, int y1) {
        int first = -1;
        int last = -1;
        for (int x = Math.max(0, x0); x < Math.min(img.getWidth(), x1); x++) {
            for (int y = Math.max(0, y0); y < Math.min(img.getHeight(), y1); y++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                if (r + g + b < 40 || ((rgb & 0xFFFFFF) == (HEALTH & 0xFFFFFF))) {
                    if (first < 0) {
                        first = x;
                    }
                    last = x;
                    break;
                }
            }
        }
        return first < 0 ? null : new int[]{first, last};
    }

    private static int delta(int a, int b) {
        return Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF)) + Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF))
                + Math.abs((a & 0xFF) - (b & 0xFF));
    }
}
