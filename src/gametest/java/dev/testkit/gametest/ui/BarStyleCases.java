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
 * at how skyblocker does theirs ... make your own type of style of it"). Classic is the flat bar as before; Sleek and
 * Bold are {@code playerstats.BarPainter}. Every case sets its style explicitly: the testkit's default style for a fresh
 * config is Classic ({@code -Dkiller560.test.barStyle=classic}), which keeps the older pixel-exact bar cases on the look
 * they measure. The painter records what each draw measured ({@code BarPainter.lastTrack/lastFill/lastOverflow/
 * lastGhost}, bar units); every case also proves on a screenshot that those pixels changed.
 *
 * <ul>
 *   <li>891-ui-bars-style-fill: each style at 100/50/10/0%: fill = round(track * value / max), the screenshot's changed
 *       columns between a value and 0 span exactly the fill; the per-frame value/animation path allocates nothing.</li>
 *   <li>892-ui-bars-style-overflow: absorption on health and overflow mana on mana appear exactly when there is some, at
 *       their share of max, ending at the bar's end; nothing changes on screen for overflow -1 against 0.</li>
 *   <li>893-ui-bars-style-settings: every new setting saved, reloaded from disk and by a profile reload; the tab's rows
 *       (with tooltips), hidden under Classic, Bar Style cycling both ways by real presses.</li>
 *   <li>894-ui-bars-style-ghost: smooth easing passes through values in between; after a drop the ghost marks the old
 *       value, holds, then drains to the new one; with Damage Ghost off there is none. Picture mid-drain.</li>
 *   <li>895-ui-bars-style-shots: a picture of each style at full, half, low, with absorption and overflow mana, Beside
 *       and square variants (copied to {@code TESTKIT_BARS_SHOTS} when set).</li>
 *   <li>896-ui-bars-style-editor: Bold with icons and the number Beside: everything drawn inside the element's box, and
 *       in Edit Bars a real drag moves and saves the bar and an edge drag resizes and saves it.</li>
 * </ul>
 */
final class BarStyleCases {

    private static final String PS = "playerstats.PlayerStatsConfig";
    private static final String HUD = "hud.HudConfig";
    private static final String READOUT = "playerstats.StatElements$Readout";
    private static final String STYLE = "playerstats.PlayerStatsConfig$BarStyle";
    private static final String POSITION = "playerstats.PlayerStatsConfig$ValuePosition";
    private static final String FEATURE = "playerstats.PlayerStatsFeature";
    private static final String PAINTER = "playerstats.BarPainter";
    private static final String TAB = "Health and Mana Bars";

    static final int HEALTH = 0xFFE03030;
    static final int MANA = 0xFF3070F0;
    static final int DEFENCE = 0xFF30C040;
    static final int ABSORB = 0xFFFFC020;
    static final int OVERFLOW = 0xFF9FE6FF;
    static final int BG = 0xAA000000;

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
            for (String style : new String[]{"CLASSIC", "SLEEK", "BOLD"}) {
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
                if (style.equals("CLASSIC")) {
                    trackX0 = BX;
                }
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
                c.check(m[0] > 0 && m[2] == 0, style + " defence: track " + m[0] + ", overflow " + m[2]);
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
                Mod.call(ps, "save");
                return null;
            });
            ModScreenDriver d = HudMenuProfilesCases.tabByClass(c, "HealthAndManaBarsTab");
            String[] rows = {"Bar Style", "Corners", "Icons", "Value Position", "Smooth Fill", "Damage Ghost",
                    "Overflow Mana Colour", "Absorption Colour"};
            List<String> labels = c.onClient(mc -> labels(d));
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
                AbstractWidget w = widget(d, "Bar Style");
                if (w == null) {
                    return "no Bar Style row";
                }
                w.mouseClicked(new MouseButtonEvent(w.getX() + 2, w.getY() + 2, new MouseButtonInfo(0, 0)), false);
                return String.valueOf(Mod.call(Mod.cfg(PS), "getBarStyle"));
            });
            c.ticks(2);
            c.note("Bar Style after a left click from Sleek: " + forward);
            c.check(forward.equals("BOLD"), "a left click on Bar Style (Sleek) gave " + forward + ", want BOLD");
            String backward = c.onClient(mc -> {
                AbstractWidget w = widget(d, "Bar Style");
                if (w == null) {
                    return "no Bar Style row";
                }
                w.mouseClicked(new MouseButtonEvent(w.getX() + 2, w.getY() + 2, new MouseButtonInfo(1, 0)), false);
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
            c.onClient(mc -> {
                Mod.call(Mod.cfg(PS), "setBarStyle", Mod.enumValue(STYLE, "CLASSIC"));
                try {
                    d.rebuild();
                } catch (Throwable t) {
                    throw new AssertionError(t);
                }
                return null;
            });
            List<String> classic = c.onClient(mc -> labels(d));
            for (String row : new String[]{"Corners", "Icons", "Value Position", "Smooth Fill", "Damage Ghost",
                    "Overflow Mana Colour"}) {
                c.check(find(classic, row) == null, "Classic still shows " + row);
            }
            c.check(find(classic, "Bar Style") != null, "Classic has no Bar Style row to leave it");
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
            reset(c);
            HudFixCases.restore(c, saved);
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
            String[][] variants = {{"CLASSIC", "round", "INSIDE"}, {"SLEEK", "round", "INSIDE"},
                    {"BOLD", "round", "INSIDE"}, {"SLEEK", "square", "BESIDE"}, {"BOLD", "square", "BESIDE"}};
            for (String[] v : variants) {
                setup(c, v[0], new String[]{"HEALTH_BAR", "MANA_BAR", "DEFENCE_BAR"}, true, false, false);
                c.onClient(mc -> {
                    Object ps = Mod.cfg(PS);
                    Mod.call(ps, "setBarRounded", v[1].equals("round"));
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
                String[][] states = {{"full", "1000", "1000", "-1", "1000"}, {"half", "500", "1000", "-1", "500"},
                        {"low", "120", "1000", "-1", "60"}, {"overflow", "1250", "1000", "400", "250"}};
                for (String[] s : states) {
                    health(c, Long.parseLong(s[1]), Long.parseLong(s[2]));
                    mana(c, Long.parseLong(s[4]), 1000, Long.parseLong(s[3]));
                    setStatic(c, "defenceValue", s[0].equals("low") ? 50L : 900L);
                    c.ticks(3);
                    String name = v[0].toLowerCase(Locale.ROOT) + "-" + v[1] + "-" + v[2].toLowerCase(Locale.ROOT)
                            + "-" + s[0];
                    BufferedImage img = HudFixCases.shot(c, name);
                    if (copyShot(c, name, img, gs, false)) {
                        taken++;
                    }
                    int[] h = measured(c, "HEALTH_BAR");
                    c.check(h[0] > 0, name + ": the health bar did not draw");
                }
            }
            c.note(taken + " picture(s) copied to TESTKIT_BARS_SHOTS");
        } finally {
            reset(c);
            HudEditorCases.restoreWindow(c, window, gui);
            HudFixCases.restore(c, saved);
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
            int x1 = Math.min(img.getWidth(), ghostArea ? (BX + BW + 6) * gs : 300 * gs);
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
        if (style.equals("CLASSIC")) {
            return 0;
        }
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
