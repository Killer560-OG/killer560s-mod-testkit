package dev.testkit.gametest.ui;

import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 1597-1598 (mod menu-anim-2-1015, killer560 2026-10-15).
 * <ul>
 *   <li>1597 Animation Speed: a slider of four stops in the Menu Theme drawer named Slow, Normal, Fast and Extra Fast
 *       (Fast the default and today's timing). Every duration scales with it - Extra Fast &lt; Fast &lt; Normal &lt; Slow,
 *       measured on a real drawer slide - pressing each stop names it, and it survives a config reload.</li>
 *   <li>1598 every slider in the menu, every drawer: dragged across its track in 41 steps, the knob after each drag is
 *       exactly where a freshly opened drawer puts it for what was saved (so it can never rest between two values), the
 *       text shown is the fresh drawer's text, and two drags that show the same text leave the knob in the same place.
 *       Sliders that turned out to snap are counted; each slider is put back afterwards.</li>
 * </ul>
 */
final class MenuAnim2Cases {

    private MenuAnim2Cases() {
    }

    static String speed() {
        return ((Enum<?>) Mod.call(Mod.cfg("gui.MenuConfig"), "getAnimationSpeed")).name();
    }

    static void speed(String s) {
        Mod.call(Mod.cfg("gui.MenuConfig"), "setAnimationSpeed", Mod.enumValue("gui.MenuAnim$Speed", s));
    }

    // ---- 1597 ---------------------------------------------------------------------------------------------------------

    static void animationSpeed(UiCase c) throws Exception {
        String wasMode = MenuAnimSuite.mode();
        String wasSpeed = speed();
        MenuAnimSuite.mode("SMOOTH");
        try {
            String[] order = {"EXTRA_FAST", "FAST", "NORMAL", "SLOW"};
            long[] dur = new long[order.length];
            float[] slideAt = new float[order.length];
            for (int i = 0; i < order.length; i++) {
                speed(order[i]);
                // the clock is driven, so durations are the real ones (the run's hook makes undriven ones instant)
                MenuAnimSuite.clock(c, 1_000.0);
                dur[i] = c.onClient(mc -> (Long) Mod.staticCall(MenuAnimSuite.ANIM, "durationNanos", 180, true));
                // a real drawer slide 120 ms after the press
                double t0 = 500_000 + i * 20_000;
                Screen s = MenuAnimSuite.menu(c, "Chat", t0);
                MenuAnimSuite.cursorAway(c);
                Object tab = MenuAnimSuite.switchCard(c, s, 0);
                MenuAnimSuite.clock(c, t0 + 2000);
                MenuAnimSuite.pressBody(c, s, tab);
                MenuAnimSuite.frame(c, t0 + 2120);
                slideAt[i] = MenuAnimSuite.state(c, s)[2];
                c.onClient(mc -> Mod.call(s, "closeDrawer") == null);
                MenuAnimSuite.frame(c, t0 + 9000);
            }
            StringBuilder line = new StringBuilder("180 ms at Fast is:");
            for (int i = 0; i < order.length; i++) {
                line.append(String.format(Locale.ROOT, " %s %.0f ms (drawer slide left at 120 ms %.3f);", order[i],
                        dur[i] / 1e6, slideAt[i]));
            }
            c.note(line.toString());
            c.check(dur[1] == 180_000_000L, "Fast is not today's timing: " + dur[1] / 1e6 + " ms for 180");
            c.check(dur[0] < dur[1] && dur[1] < dur[2] && dur[2] < dur[3], "durations are not Extra Fast < Fast < Normal < Slow");
            c.check(slideAt[0] < slideAt[1] && slideAt[1] < slideAt[2] && slideAt[2] < slideAt[3],
                    "the drawer slide at 120 ms does not order by speed (more left to go when slower)");

            // ---- the slider: four stops, named, saved ----
            speed("FAST");
            Screen s = MenuAnimSuite.menu(c, "Chat", 900_000);
            Object themeTab = MenuACases.leaf("MenuThemeTab");
            c.onClient(mc -> Mod.call(s, "openFeature", themeTab) == null);
            MenuAnimSuite.frame(c, 901_000);
            AbstractWidget slider = speedSlider(c, s);
            c.check(slider != null, "no Animation Speed slider in the Menu Theme drawer");
            if (slider == null) {
                return;
            }
            c.check(slider.getMessage().getString().endsWith("Fast"), "the slider reads '" + slider.getMessage().getString()
                    + "' at the default");
            int[] track = c.onClient(mc -> (int[]) Mod.call(slider, "trackRange"));
            String[] want = {"SLOW", "NORMAL", "FAST", "EXTRA_FAST"};
            String[] label = {"Slow", "Normal", "Fast", "Extra Fast"};
            for (int i = 0; i < 4; i++) {
                double x = track[0] + (track[1] - track[0]) * (i / 3.0) + (i == 0 ? 2 : i == 3 ? -2 : 3);
                AbstractWidget sl = speedSlider(c, s);
                c.onClient(mc -> {
                    press(sl, x);
                    return null;
                });
                AbstractWidget now = speedSlider(c, s);
                String text = now.getMessage().getString();
                double knob = c.onClient(mc -> (Double) Mod.field(now, "value"));
                c.note("pressed stop " + i + ": " + text + ", knob " + knob + ", saved " + speed());
                c.check(speed().equals(want[i]), "pressing stop " + i + " saved " + speed());
                c.check(text.equals("Animation Speed: " + label[i]), "stop " + i + " reads '" + text + "'");
                c.check(Math.abs(knob - i / 3.0) < 1e-9, "stop " + i + " left the knob at " + knob);
            }
            c.onClient(mc -> {
                Mod.call(Mod.cfg("gui.MenuConfig"), "save");
                Mod.staticCall("gui.MenuConfig", "load");
                return null;
            });
            c.check(speed().equals("EXTRA_FAST"), "after a reload the speed is " + speed());
        } finally {
            speed(wasSpeed);
            MenuAnimSuite.mode(wasMode);
            Mod.call(Mod.cfg("gui.MenuConfig"), "save");
            MenuAnimSuite.releaseClock(c);
        }
    }

    static AbstractWidget speedSlider(UiCase c, Screen s) {
        return c.onClient(mc -> {
            for (AbstractWidget w : MenuACases.paneRows(s)) {
                if (w.getMessage().getString().startsWith("Animation Speed")) {
                    return w;
                }
            }
            return null;
        });
    }

    // ---- 2779 ---------------------------------------------------------------------------------------------------------

    /** The animationSpeed killer560smod-menu.json holds on disk right now. */
    static String menuFile() {
        try {
            java.nio.file.Path p = (java.nio.file.Path) Mod.staticCall("gui.MenuConfig", "path");
            if (!java.nio.file.Files.exists(p)) {
                return "no file";
            }
            com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(p))
                    .getAsJsonObject();
            return o.has("animationSpeed") ? o.get("animationSpeed").getAsString() : "no animationSpeed";
        } catch (Exception e) {
            return "unreadable: " + e;
        }
    }

    /**
     * 2779-ui-slider-save-on-release (mod sweep-fix-1021, item 6b): a ThemedSliderButton wrote its config file on every
     * drag step. Now the value applies live every step and the file is written once, on release. On the Menu Theme
     * drawer's Animation Speed slider: press at Slow and drag to Normal with the button held - the setting follows live
     * while killer560smod-menu.json still says FAST; release - the file says NORMAL. A press that is released at once
     * (no drag) saves too.
     */
    static void saveOnRelease(UiCase c) throws Exception {
        String wasMode = MenuAnimSuite.mode();
        String wasSpeed = speed();
        MenuAnimSuite.mode("OFF");
        try {
            speed("FAST");
            c.onClient(mc -> Mod.call(Mod.cfg("gui.MenuConfig"), "save") == null);
            Screen s = MenuAnimSuite.menu(c, "Chat", 3_000_000);
            Object themeTab = MenuACases.leaf("MenuThemeTab");
            c.onClient(mc -> Mod.call(s, "openFeature", themeTab) == null);
            MenuAnimSuite.frame(c, 3_001_000);
            AbstractWidget slider = speedSlider(c, s);
            c.check(slider != null, "no Animation Speed slider in the Menu Theme drawer");
            if (slider == null) {
                return;
            }
            int[] track = c.onClient(mc -> (int[]) Mod.call(slider, "trackRange"));
            double slowX = track[0] + 2;
            double normalX = track[0] + (track[1] - track[0]) / 3.0 + 3;
            double y = slider.getY() + slider.getHeight() / 2.0;
            c.check(menuFile().equals("FAST"), "control: the file says FAST before the drag: " + menuFile());

            c.onClient(mc -> slider.mouseClicked(new MouseButtonEvent(slowX, y, new MouseButtonInfo(0, 0)), false));
            String livePress = speed();
            boolean filePress = menuFile().equals("FAST");
            c.onClient(mc -> slider.mouseDragged(new MouseButtonEvent(normalX, y, new MouseButtonInfo(0, 0)),
                    normalX - slowX, 0));
            String liveDrag = speed();
            boolean fileDrag = menuFile().equals("FAST");
            c.onClient(mc -> {
                slider.mouseReleased(new MouseButtonEvent(normalX, y, new MouseButtonInfo(0, 0)));
                return null;
            });
            String fileAfter = menuFile();
            c.note("held press at Slow: live " + livePress + ", file still FAST " + filePress + "; dragged to Normal: live "
                    + liveDrag + ", file still FAST " + fileDrag + "; released: file says NORMAL "
                    + fileAfter.equals("NORMAL"));
            c.check(livePress.equals("SLOW"), "the press did not apply live: " + livePress);
            c.check(liveDrag.equals("NORMAL"), "the drag did not apply live: " + liveDrag);
            c.check(filePress, "the file was written while the press was still held: " + menuFile());
            c.check(fileDrag, "the file was written on a drag step: " + menuFile());
            c.check(fileAfter.equals("NORMAL"), "the release did not write the file: " + fileAfter);

            // A click (press and release with no drag) still saves.
            AbstractWidget again = speedSlider(c, s);
            c.onClient(mc -> {
                press(again, slowX);
                return null;
            });
            c.check(menuFile().equals("SLOW"), "a plain click did not save: " + menuFile());
        } finally {
            speed(wasSpeed);
            MenuAnimSuite.mode(wasMode);
            Mod.call(Mod.cfg("gui.MenuConfig"), "save");
            MenuAnimSuite.releaseClock(c);
        }
    }

    static void press(AbstractWidget w, double x) {
        MouseButtonEvent e = new MouseButtonEvent(x, w.getY() + w.getHeight() / 2.0, new MouseButtonInfo(0, 0));
        w.mouseClicked(e, false);
        w.mouseReleased(e);
    }

    // ---- 1598 ---------------------------------------------------------------------------------------------------------

    static boolean isSlider(AbstractWidget w) {
        for (Class<?> k = w.getClass(); k != null; k = k.getSuperclass()) {
            if (k.getName().equals(Mod.ROOT + "gui.ThemedSliderButton")) {
                return true;
            }
        }
        return false;
    }

    static String key(AbstractWidget w) {
        return (String) Mod.call(w, "dragKey");
    }

    /** The {@code ordinal}-th slider labelled {@code key} in the open pane, or null. */
    static AbstractWidget find(Screen s, String key, int ordinal) {
        int n = 0;
        for (AbstractWidget w : MenuACases.paneRows(s)) {
            if (isSlider(w) && key.equals(key(w)) && n++ == ordinal) {
                return w;
            }
        }
        return null;
    }

    /** Reopens the same drawer from scratch (what a player sees after closing and opening it). */
    static void reopen(Screen s, Object tab) {
        Mod.call(s, "closeDrawer");
        Mod.call(s, "openFeature", tab);
    }

    static void sliderSteps(UiCase c) throws Exception {
        String wasMode = MenuAnimSuite.mode();
        MenuAnimSuite.mode("OFF"); // the knob itself, no glide
        int sliders = 0;
        int stepped = 0;
        int drags = 0;
        List<String> stepNames = new ArrayList<>();
        try {
            Screen s = MenuAnimSuite.menu(c, "Chat", 2_000_000);
            MenuAnimSuite.cursorAway(c);
            for (Object tab : MenuACases.leaves()) {
                String tabName = MenuACases.name(tab);
                if (tabName.equals("Profiles") || c.onClient(mc -> {
                    Mod.call(s, "openFeature", tab);
                    return Mod.call(s, "openFeature") != tab;
                })) {
                    continue; // a page, not a drawer
                }
                c.ticks(1);
                List<String> results = c.onClient(mc -> sweepDrawer(s, tab, tabName, stepNames));
                for (String r : results) {
                    sliders++;
                    drags += 41;
                    if (r.startsWith("STEPPED")) {
                        stepped++;
                    } else if (!r.equals("OK")) {
                        c.problem(r);
                    }
                }
            }
            c.note(sliders + " sliders checked over " + drags + " drags; " + stepped + " snap to steps: " + stepNames);
            c.check(sliders >= 50, "only " + sliders + " sliders found in the menu - the sweep did not reach them");
            c.check(stepped >= 20, "only " + stepped + " sliders were seen to snap - the drags did not exercise them");
        } finally {
            MenuAnimSuite.mode(wasMode);
            MenuAnimSuite.releaseClock(c);
        }
    }

    static List<AbstractWidget> rowsWith(Screen s, String look) {
        List<AbstractWidget> out = new ArrayList<>();
        for (AbstractWidget w : MenuACases.paneRows(s)) {
            if (w.visible && w.active && w.getClass().getSimpleName().equals("SettingsButtonWidget")
                    && ((Enum<?>) Mod.call(Mod.call(w, "parts"), "look")).name().equals(look)) {
                out.add(w);
            }
        }
        return out;
    }

    static String label(AbstractWidget w) {
        return (String) Mod.call(Mod.call(w, "parts"), "label");
    }

    static AbstractWidget row(Screen s, String look, String label) {
        for (AbstractWidget w : rowsWith(s, look)) {
            if (label(w).equals(label)) {
                return w;
            }
        }
        return null;
    }

    /**
     * One drawer, on the client thread with no tick in between (nothing the switches turn on gets to run): every closed
     * section opened, every slider checked, then every switch that is off turned on once to show the sliders it gates,
     * those checked, and the switch turned back; the sections are closed again. Returns one result per slider.
     */
    static List<String> sweepDrawer(Screen s, Object tab, String tabName, List<String> stepNames) {
        List<String> results = new ArrayList<>();
        java.util.Set<String> done = new java.util.HashSet<>();
        List<String> opened = new ArrayList<>();
        for (int guard = 0; guard < 30; guard++) {
            AbstractWidget closed = null;
            for (AbstractWidget h : rowsWith(s, "HEADER")) {
                if (!(Boolean) Mod.call(Mod.call(h, "parts"), "on")) {
                    closed = h;
                    break;
                }
            }
            if (closed == null) {
                break;
            }
            opened.add(label(closed));
            Mod.call(closed, "press");
            Mod.call(s, "rebuild");
        }
        checkAll(s, tab, tabName, done, results, stepNames);
        List<String> offSwitches = new ArrayList<>();
        for (AbstractWidget w : rowsWith(s, "SWITCH")) {
            if (!(Boolean) Mod.call(w, "isOn")) {
                offSwitches.add(label(w));
            }
        }
        for (String sw : offSwitches) {
            AbstractWidget w = row(s, "SWITCH", sw);
            if (w == null || (Boolean) Mod.call(w, "isOn")) {
                continue;
            }
            Mod.call(w, "press");
            backToMenu(s);
            Mod.call(s, "rebuild");
            try {
                checkAll(s, tab, tabName + " [" + sw + " on]", done, results, stepNames);
            } finally {
                AbstractWidget back = row(s, "SWITCH", sw);
                if (back != null && (Boolean) Mod.call(back, "isOn")) {
                    Mod.call(back, "press");
                    backToMenu(s);
                    Mod.call(s, "rebuild");
                }
            }
        }
        for (int i = opened.size() - 1; i >= 0; i--) {
            AbstractWidget h = row(s, "HEADER", opened.get(i));
            if (h != null && (Boolean) Mod.call(Mod.call(h, "parts"), "on")) {
                Mod.call(h, "press");
                Mod.call(s, "rebuild");
            }
        }
        return results;
    }

    /**
     * Puts the menu back if a switch opened a screen over it. Turning the Bazaar Flipper on opens the Bazaar Advisor's
     * questions (mod f0641a08, killer560 2026-10-19); with the menu no longer the open screen a slider is not "in the
     * menu", skips its step snap, and every drawer swept after it read as knobs resting between values.
     */
    static void backToMenu(Screen s) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (dev.testkit.compat.McCompat.screen(mc) != s) {
            dev.testkit.compat.McCompat.setScreen(mc, s);
        }
    }

    static void checkAll(Screen s, Object tab, String where, java.util.Set<String> done, List<String> results,
                         List<String> stepNames) {
        Map<String, Integer> seen = new LinkedHashMap<>();
        List<String[]> found = new ArrayList<>();
        for (AbstractWidget w : MenuACases.paneRows(s)) {
            if (isSlider(w) && w.visible && w.active) {
                int ord = seen.merge(key(w), 1, Integer::sum) - 1;
                found.add(new String[]{key(w), String.valueOf(ord)});
            }
        }
        for (String[] f : found) {
            if (!done.add(f[0] + "#" + f[1])) {
                continue;
            }
            int ord = Integer.parseInt(f[1]);
            String r = checkSlider(s, tab, f[0], ord, where + " / " + f[0] + (ord > 0 ? " #" + (ord + 1) : ""), stepNames);
            results.add(r == null ? "OK" : r);
        }
    }

    /** Drags one slider across its track and checks every resting knob against a fresh drawer. Client thread. Returns
     *  null (fine, continuous), "STEPPED ..." (fine, snaps) or a problem. */
    static String checkSlider(Screen s, Object tab, String key, int ord, String where, List<String> stepNames) {
        if (dev.testkit.compat.McCompat.screen(net.minecraft.client.Minecraft.getInstance()) != s) {
            return where + ": the menu is not the open screen, so its sliders are not in the menu";
        }
        AbstractWidget w = find(s, key, ord);
        if (w == null) {
            return where + ": slider not found";
        }
        double original = (Double) Mod.field(w, "value");
        int[] track = (int[]) Mod.call(w, "trackRange");
        Map<String, Double> knobFor = new LinkedHashMap<>();
        String problem = null;
        try {
            for (int i = 0; i <= 40 && problem == null; i++) {
                // between-step positions on purpose: i/40 of the track plus a third of a sample
                double x = track[0] + (track[1] - track[0]) * Math.min(1.0, (i + 0.33) / 40.0);
                AbstractWidget cur = find(s, key, ord);
                if (cur == null) {
                    return where + ": the slider vanished after a drag";
                }
                press(cur, x);
                cur = find(s, key, ord);
                double knob = (Double) Mod.field(cur, "value");
                String text = cur.getMessage().getString();
                reopen(s, tab);
                AbstractWidget fresh = find(s, key, ord);
                if (fresh == null) {
                    return where + ": the slider is gone in a fresh drawer";
                }
                double saved = (Double) Mod.field(fresh, "value");
                String freshText = fresh.getMessage().getString();
                if (!freshText.equals(text)) {
                    problem = where + ": after a drag it read '" + text + "' but a fresh drawer reads '" + freshText + "'";
                } else if (Math.abs(saved - knob) > 1e-6) {
                    problem = String.format(Locale.ROOT, "%s: the knob rested at %.5f but '%s' belongs at %.5f "
                            + "(between two values)", where, knob, text, saved);
                } else {
                    Double before = knobFor.putIfAbsent(text, knob);
                    if (before != null && Math.abs(before - knob) > 1e-6) {
                        problem = String.format(Locale.ROOT, "%s: '%s' was shown with the knob at %.5f and at %.5f",
                                where, text, before, knob);
                    }
                }
            }
        } finally {
            AbstractWidget back = find(s, key, ord);
            if (back != null) {
                Mod.call(back, "setValue", original);
            }
            reopen(s, tab);
        }
        if (problem != null) {
            return problem;
        }
        if (knobFor.size() < 41) {
            stepNames.add(where + " (" + knobFor.size() + " stops)");
            return "STEPPED";
        }
        return null;
    }
}
