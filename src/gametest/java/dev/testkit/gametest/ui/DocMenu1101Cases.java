package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 1101-ui-docmenu-*: killer560's 2026-11-01 to-do Doc menu items (mod doc-menu-1101). Each case drives the real
 * {@code gui.ModScreen} and proves the change ACTED:
 * <ul>
 *   <li>sections: selecting Dungeons lists its sections under it in the sidebar (Legit, Secrets, Puzzle Solvers and on
 *       a cheat jar Cheat); pressing Cheat (or the last section) scrolls the grid to that header, Legit back to 0; a
 *       second category gets its own; the category rows are all still there.</li>
 *   <li>scrollbar: a press on the grid's scrollbar track at the bottom scrolls to the end, a drag up to the top back to
 *       0, a drag to the middle about halfway; the same on a long drawer's bar moves its scroll.</li>
 *   <li>keybind: Freelook's drawer ends with "Toggle Keybind: None"; a press on it and a key binds the key (saved, and
 *       back after a reload from disk); with no screen the held key toggles Freelook on and off once per press;
 *       Escape clears it.</li>
 *   <li>etherwarp: one Etherwarp card (no Left Click Etherwarp card), its rows in order with no explanation lines and
 *       a tooltip on each, Works For the last legit rows, the cheat rows under it, Left Click Etherwarp last, the card's
 *       switch standing for both; Multi Warps gates Warps Per Tick and Aimed Warps; Dungeons Only stops ZPEW outside a
 *       dungeon; searching zpew finds the Zero Ping Etherwarp row.</li>
 *   <li>video browser: searching tiktok, tictok, yt, youtube and web finds Video Browser; its Auto Scroll row flips and
 *       saves the setting; the end-of-video watcher script is in the jar.</li>
 * </ul>
 */
final class DocMenu1101Cases {

    static final String[] CASES = {"1101-ui-docmenu-sections", "1101-ui-docmenu-scrollbar", "1101-ui-docmenu-keybind",
            "1101-ui-docmenu-etherwarp", "1101-ui-docmenu-video-browser"};

    private DocMenu1101Cases() {
    }

    private static Screen open(Minecraft mc) {
        try {
            return MenuACases.open(mc);
        } catch (Exception e) {
            throw new AssertionError("could not open gui.ModScreen: " + UiCase.describe(e), e);
        }
    }

    private static Screen screen(Minecraft mc) {
        return McCompat.screen(mc);
    }

    private static MouseButtonEvent ev(double x, double y) {
        return new MouseButtonEvent(x, y, new MouseButtonInfo(dev.testkit.compat.McKeys.mouse(0), 0));
    }

    @SuppressWarnings("unchecked")
    private static List<String> sections(Screen s) {
        return (List<String>) Mod.call(s, "sideSections");
    }

    private static int[] grid(Screen s) {
        return (int[]) Mod.call(s, "gridScrollState");
    }

    private static List<String> labels(Screen s) {
        List<String> out = new ArrayList<>();
        for (AbstractWidget w : MenuACases.paneRows(s)) {
            out.add(ModScreenDriver.label(w));
        }
        return out;
    }

    private static String cut(String label) {
        int colon = label.indexOf(':');
        return (colon > 0 ? label.substring(0, colon) : label).trim();
    }

    // ---- sections ---------------------------------------------------------------------------------------------------

    static void sections(UiCase c) {
        boolean cheat = Mod.isCheat();
        c.onClient(mc -> {
            Screen s = open(mc);
            Mod.call(s, "selectCategory", "Dungeons");
            return null;
        });
        c.ticks(2);
        c.onClient(mc -> {
            Screen s = screen(mc);
            List<String> secs = sections(s);
            @SuppressWarnings("unchecked")
            List<String> rows = (List<String>) Mod.call(s, "sideTexts");
            c.note("Dungeons sections: " + secs + "; sidebar: " + rows);
            c.check(!secs.isEmpty() && secs.get(0).equals("Legit"), "Dungeons lists no Legit section first: " + secs);
            c.check(secs.contains("Secrets") && secs.contains("Puzzle Solvers"), "the groups are not sections: " + secs);
            c.check(secs.contains("Cheat") == cheat, "Cheat section shown=" + secs.contains("Cheat") + " on a "
                    + (cheat ? "cheat" : "legit") + " jar");
            for (String want : new String[]{"Home", "Dungeons", "HUD", "Chat", "Items", "Social", "Cosmetics", "Misc"}) {
                c.check(rows.contains(want), "the sidebar lost its '" + want + "' row: " + rows);
            }
            int at = rows.indexOf("Dungeons");
            c.check(at >= 0 && rows.size() > at + 1 && rows.get(at + 1).equals("Legit"),
                    "the sections are not right under Dungeons: " + rows);
            String last = secs.get(secs.size() - 1);
            int[] g0 = grid(s);
            c.check(g0[1] > 0, "the Dungeons grid does not scroll (max " + g0[1] + ") - the jump would be vacuous");
            c.check((Boolean) Mod.call(s, "pressSection", last), "no section row '" + last + "' to press");
            int[] g1 = grid(s);
            c.note("pressed '" + last + "': grid scroll " + g0[0] + " -> " + g1[0] + " (max " + g1[1] + ")");
            c.check(g1[0] > g0[0], "pressing '" + last + "' did not scroll the grid down: " + g0[0] + " -> " + g1[0]);
            Mod.call(s, "pressSection", "Legit");
            int[] g2 = grid(s);
            c.check(g2[0] == 0, "pressing Legit did not go back to the top: " + g2[0]);
            // Another category: its own sections (or none when it has no header), never Dungeons'.
            Mod.call(s, "selectCategory", "Misc");
            List<String> misc = sections(s);
            c.note("Misc sections: " + misc);
            c.check(!misc.contains("Secrets") && !misc.contains("Puzzle Solvers"), "Misc shows Dungeons' sections: " + misc);
            c.check(misc.contains("Cheat") == cheat, "Misc's Cheat section shown=" + misc.contains("Cheat"));
            Mod.call(s, "selectCategory", "Home");
            c.check(sections(s).isEmpty(), "Home lists sections: " + sections(s));
            return null;
        });
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            return null;
        });
    }

    // ---- scrollbar --------------------------------------------------------------------------------------------------

    static void scrollbar(UiCase c) {
        Object shorts = Mod.cfg("shorts.ShortsConfig");
        boolean shortsWas = (Boolean) Mod.call(shorts, "isEnabled");
        try {
            scrollbarBody(c, shorts);
        } finally {
            c.onClient(mc -> {
                Mod.call(shorts, "setEnabled", shortsWas);
                Mod.call(shorts, "save");
                return null;
            });
        }
    }

    private static void scrollbarBody(UiCase c, Object shorts) {
        c.onClient(mc -> {
            Mod.call(shorts, "setEnabled", true);
            Screen s = open(mc);
            Mod.call(s, "selectCategory", "Dungeons");
            return null;
        });
        c.ticks(2);
        c.onClient(mc -> {
            Screen s = screen(mc);
            int[] b = (int[]) Mod.call(s, "scrollbarBounds", "grid");
            c.check(b != null, "the Dungeons grid has no scrollbar to drag");
            if (b == null) {
                return null;
            }
            int max = grid(s)[1];
            double x = b[0] + b[2] / 2.0;
            // A press near the bottom of the track: the thumb jumps under the cursor (the end).
            s.mouseClicked(ev(x, b[1] + b[3] - 1), false);
            int atEnd = grid(s)[0];
            // Dragged to the top.
            s.mouseDragged(ev(x, b[1]), 0, -b[3]);
            int atTop = grid(s)[0];
            // Dragged to the middle.
            s.mouseDragged(ev(x, b[1] + b[3] / 2.0), 0, b[3] / 2.0);
            int mid = grid(s)[0];
            s.mouseReleased(ev(x, b[1] + b[3] / 2.0));
            // After the release a move of the mouse with the button down elsewhere drags nothing.
            s.mouseDragged(ev(x, b[1]), 0, -b[3] / 2.0);
            int after = grid(s)[0];
            c.note("grid bar " + java.util.Arrays.toString(b) + ", max " + max + ": press at bottom " + atEnd
                    + ", drag to top " + atTop + ", drag to middle " + mid + ", drag after release " + after);
            c.check(atEnd == max, "a press at the bottom of the track did not scroll to the end: " + atEnd + " of " + max);
            c.check(atTop == 0, "dragging the thumb to the top did not scroll to 0: " + atTop);
            c.check(Math.abs(mid - max / 2) <= max / 5 + 2, "dragging to the middle scrolled to " + mid + " of " + max);
            c.check(after == mid, "the bar kept dragging after the release: " + mid + " -> " + after);
            // A long drawer: Video Browser switched on (the QUIET profile leaves every drawer short with its switch off).
            Object tab = MenuACases.leaf("ShortsTab");
            Mod.call(s, "openFeature", tab);
            if (Mod.call(s, "scrollbarBounds", "content") == null) {
                tab = null;
            }
            c.check(tab != null, "no drawer long enough to scroll - the drawer check would be vacuous");
            if (tab == null) {
                return null;
            }
            int[] d = (int[]) Mod.call(s, "scrollbarBounds", "content");
            double dx = d[0] + d[2] / 2.0;
            int before = (Integer) Mod.field(s, "scrollOffset");
            s.mouseClicked(ev(dx, d[1] + d[3] - 1), false);
            int dEnd = (Integer) Mod.field(s, "scrollOffset");
            s.mouseDragged(ev(dx, d[1]), 0, -d[3]);
            int dTop = (Integer) Mod.field(s, "scrollOffset");
            s.mouseReleased(ev(dx, d[1]));
            int dMax = (Integer) Mod.field(s, "maxScroll");
            c.note("drawer " + MenuACases.name(tab) + " bar " + java.util.Arrays.toString(d) + ", max " + dMax
                    + ": before " + before + ", press at bottom " + dEnd + ", drag to top " + dTop);
            c.check(dEnd == dMax && dMax > 0, "a press at the bottom of the drawer's bar did not scroll it to the end: "
                    + dEnd + " of " + dMax);
            c.check(dTop == 0, "dragging the drawer's thumb to the top did not scroll it to 0: " + dTop);
            Mod.call(s, "closeDrawer");
            return null;
        });
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            return null;
        });
    }

    // ---- keybind ----------------------------------------------------------------------------------------------------

    private static final String KB = "gui.ModuleKeybinds";
    private static final String FREELOOK = "freelook.FreelookConfig";
    private static final int KEY_K = 75; // GLFW_KEY_K

    static void keybind(UiCase c) {
        Object cfg = Mod.cfg(FREELOOK);
        boolean was = (Boolean) Mod.call(cfg, "isEnabled");
        try {
            c.onClient(mc -> {
                Mod.call(cfg, "setEnabled", false);
                Mod.staticCall(KB, "set", "FreelookTab", -1);
                Screen s = open(mc);
                Mod.call(s, "openFeature", MenuACases.leaf("FreelookTab"));
                List<AbstractWidget> rows = MenuACases.paneRows(s);
                AbstractWidget last = null;
                for (AbstractWidget w : rows) {
                    if (last == null || w.getY() >= last.getY()) {
                        last = w;
                    }
                }
                String lastLabel = last == null ? "" : ModScreenDriver.label(last);
                c.note("Freelook drawer rows: " + labels(s));
                c.check(lastLabel.equals("Toggle Keybind: None"), "the drawer's last row is '" + lastLabel
                        + "', want 'Toggle Keybind: None'");
                if (last == null) {
                    return null;
                }
                String tip = (String) Mod.staticCall("gui.SettingTooltips", "describe", "Freelook", "Toggle Keybind");
                c.check(tip != null && !tip.isBlank(), "the Toggle Keybind row has no tooltip");
                Mod.call(last, "press");
                MenuACases.key(s, dev.testkit.compat.McKeys.nat(KEY_K), 0);
                int bound = (Integer) Mod.staticCall(KB, "get", "FreelookTab");
                c.check(bound == KEY_K, "a press on the row and K bound " + bound + ", want " + KEY_K);
                String shown = "";
                for (AbstractWidget w : MenuACases.paneRows(s)) {
                    if (ModScreenDriver.label(w).startsWith("Toggle Keybind")) {
                        shown = ModScreenDriver.label(w);
                    }
                }
                c.note("row after binding: '" + shown + "'");
                c.check(shown.equals("Toggle Keybind: K"), "the row reads '" + shown + "' after binding K");
                Mod.staticCall(KB, "load");
                c.check((Integer) Mod.staticCall(KB, "get", "FreelookTab") == KEY_K, "the bind did not survive a reload from disk");
                McCompat.setScreen(mc, null);
                return null;
            });
            c.ticks(2);
            int t0 = c.onClient(mc -> (Integer) Mod.staticCall(KB, "toggles"));
            boolean[] states = new boolean[4];
            for (int i = 0; i < 2; i++) {
                c.onClient(mc -> {
                    Mod.setField(KB, "heldForTest", KEY_K);
                    return null;
                });
                c.ticks(3); // held for three ticks: one toggle, not three
                states[i * 2] = c.onClient(mc -> (Boolean) Mod.call(cfg, "isEnabled"));
                c.onClient(mc -> {
                    Mod.setField(KB, "heldForTest", null);
                    return null;
                });
                c.ticks(2);
                states[i * 2 + 1] = c.onClient(mc -> (Boolean) Mod.call(cfg, "isEnabled"));
            }
            int t1 = c.onClient(mc -> (Integer) Mod.staticCall(KB, "toggles"));
            String overlay = c.onClient(mc -> (String) Mod.staticCall("notify.ModOverlayMessage", "current"));
            c.note("Freelook after press 1 (held, released): " + states[0] + ", " + states[1] + "; after press 2: "
                    + states[2] + ", " + states[3] + "; toggles " + t0 + " -> " + t1 + "; overlay '" + overlay + "'");
            c.check(states[0] && states[1], "the first press of K did not turn Freelook on");
            c.check(!states[2] && !states[3], "the second press of K did not turn Freelook off");
            c.check(t1 - t0 == 2, "two presses toggled " + (t1 - t0) + " times");
            // With a screen open the key does nothing.
            c.onClient(mc -> {
                open(mc);
                Mod.setField(KB, "heldForTest", KEY_K);
                return null;
            });
            c.ticks(3);
            boolean withScreen = c.onClient(mc -> {
                Mod.setField(KB, "heldForTest", null);
                return (Boolean) Mod.call(cfg, "isEnabled");
            });
            c.check(!withScreen, "the key toggled Freelook with the menu open");
            // Escape clears it.
            c.onClient(mc -> {
                Screen s = screen(mc);
                Mod.call(s, "openFeature", MenuACases.leaf("FreelookTab"));
                for (AbstractWidget w : MenuACases.paneRows(s)) {
                    if (ModScreenDriver.label(w).startsWith("Toggle Keybind")) {
                        Mod.call(w, "press");
                    }
                }
                MenuACases.key(s, dev.testkit.compat.McKeys.nat(256), 0); // Escape
                int cleared = (Integer) Mod.staticCall(KB, "get", "FreelookTab");
                c.check(cleared == -1, "Escape left the bind at " + cleared);
                McCompat.setScreen(mc, null);
                return null;
            });
        } finally {
            c.onClient(mc -> {
                Mod.setField(KB, "heldForTest", null);
                Mod.staticCall(KB, "set", "FreelookTab", -1);
                Mod.call(cfg, "setEnabled", was);
                Mod.call(cfg, "save");
                return null;
            });
        }
    }

    // ---- etherwarp --------------------------------------------------------------------------------------------------

    private static final String ZP = "zpew.ZeroPingEtherwarpConfig";

    static void etherwarp(UiCase c) {
        boolean cheat = Mod.isCheat();
        Object cfg = Mod.cfg(ZP);
        boolean wasOn = (Boolean) Mod.call(cfg, "isEnabled");
        boolean wasMulti = (Boolean) Mod.call(cfg, "isMultiWarps");
        boolean wasDungeons = (Boolean) Mod.call(cfg, "isDungeonsOnly");
        int wasWpt = (Integer) Mod.call(cfg, "getWarpsPerTick");
        try {
            c.onClient(mc -> {
                boolean lceCard = false;
                Object tab = null;
                for (Object t : MenuACases.leaves()) {
                    String n = t.getClass().getSimpleName();
                    lceCard |= n.equals("LeftClickEtherwarpTab");
                    if (n.equals("ZeroPingEtherwarpTab")) {
                        tab = t;
                    }
                }
                c.check(!lceCard, "Left Click Etherwarp still has its own card");
                c.check(tab != null && MenuACases.name(tab).equals("Etherwarp"),
                        "no Etherwarp card: " + (tab == null ? "none" : MenuACases.name(tab)));
                c.check("Dungeons".equals(MenuACases.name(MenuACases.category(tab))), "the Etherwarp card is not in Dungeons");
                Mod.call(cfg, "setEnabled", true);
                Mod.call(cfg, "setMultiWarps", false);
                Mod.call(cfg, "setDungeonsOnly", false);
                Mod.call(cfg, "setWarpsPerTick", 3);
                Screen s = open(mc);
                Mod.call(s, "openFeature", tab);
                List<String> l = new ArrayList<>();
                List<AbstractWidget> rows = MenuACases.paneRows(s);
                rows.sort(java.util.Comparator.comparingInt(AbstractWidget::getY).thenComparingInt(AbstractWidget::getX));
                List<String> untipped = new ArrayList<>();
                for (AbstractWidget w : rows) {
                    String lab = ModScreenDriver.label(w);
                    l.add(lab);
                    if (w instanceof StringWidget) {
                        boolean header = lab.equals("Works For") || lab.equals("Multi Warps") || lab.startsWith("Cheat Build")
                                || lab.equals("Left Click Etherwarp");
                        c.check(header, "an explanation line is still in the tab: '" + lab + "'");
                        continue;
                    }
                    String tip = (String) Mod.staticCall("gui.SettingTooltips", "describe", "Etherwarp", cut(lab));
                    if (tip == null || tip.isBlank()) {
                        untipped.add(lab);
                    }
                }
                c.note((cheat ? "cheat" : "legit") + " Etherwarp rows (Multi Warps off): " + l);
                c.check(untipped.isEmpty(), "rows with no hover tooltip: " + untipped);
                List<String> cuts = new ArrayList<>();
                for (String x : l) {
                    cuts.add(cut(x));
                }
                c.check(cuts.indexOf("Zero Ping Etherwarp") == 0, "the first row is not Zero Ping Etherwarp: " + cuts);
                c.check(cuts.contains("Dungeons Only"), "no Dungeons Only row: " + cuts);
                c.check(!cuts.contains("Warps Per Tick") && !cuts.contains("Aimed Warps"),
                        "Warps Per Tick / Aimed Warps show with Multi Warps off: " + cuts);
                int works = cuts.indexOf("Works For");
                int give = cuts.indexOf("Give Up After");
                c.check(works > give && give > 0, "Works For is not after the settings: " + cuts);
                c.check(cuts.contains("Multi Warps") == cheat, "Multi Warps shown=" + cuts.contains("Multi Warps")
                        + " on a " + (cheat ? "cheat" : "legit") + " jar");
                if (cheat) {
                    // Cheat rows at the bottom (testkit 512): Multi Warps after the legit Works For rows.
                    c.check(cuts.indexOf("Multi Warps") > cuts.indexOf("Manual Etherwarps"),
                            "Multi Warps is not under the legit Works For rows: " + cuts);
                    int lce = cuts.lastIndexOf("Left Click Etherwarp");
                    c.check(lce > cuts.indexOf("Pathfinding Hopper"), "Left Click Etherwarp is not at the bottom: " + cuts);
                    c.check(cuts.contains("Left Click Etherwarps"), "the Works For row is not 'Left Click Etherwarps': " + cuts);
                    // The card's switch stands for both.
                    Object master = Mod.field(s, "drawerMaster");
                    int parts = ((List<?>) Mod.call(master, "parts")).size();
                    c.check(parts == 2, "the Etherwarp switch stands for " + parts + " rows, want 2 (ZPEW and Left Click)");
                    c.check((Integer) Mod.call(cfg, "effectiveWarpsPerTick") == 1, "Warps Per Tick 3 applies with Multi Warps off");
                    // Multi Warps on: the two rows come, and Warps Per Tick applies.
                    for (AbstractWidget w : MenuACases.paneRows(s)) {
                        if (cut(ModScreenDriver.label(w)).equals("Multi Warps") && !(w instanceof StringWidget)) {
                            Mod.call(w, "press");
                            break;
                        }
                    }
                    List<String> on = new ArrayList<>();
                    for (AbstractWidget w : MenuACases.paneRows(s)) {
                        on.add(cut(ModScreenDriver.label(w)));
                    }
                    c.note("with Multi Warps pressed on: " + on);
                    c.check((Boolean) Mod.call(cfg, "isMultiWarps"), "pressing Multi Warps did not turn it on");
                    c.check(on.contains("Warps Per Tick") && on.contains("Aimed Warps"), "Multi Warps on, but no Warps "
                            + "Per Tick / Aimed Warps rows: " + on);
                    c.check((Integer) Mod.call(cfg, "effectiveWarpsPerTick") == 3, "Warps Per Tick 3 does not apply with "
                            + "Multi Warps on");
                    Mod.staticCall(ZP, "load");
                    c.check((Boolean) Mod.call(Mod.cfg(ZP), "isMultiWarps"), "Multi Warps did not survive a reload");
                } else {
                    c.check(!cuts.contains("Left Click Etherwarp"), "a legit jar shows Left Click Etherwarp: " + cuts);
                }
                // Dungeons Only: outside a dungeon ZPEW stands down, and comes back when it is off.
                Object z = Mod.cfg(ZP);
                Mod.call(z, "setDungeonsOnly", true);
                boolean onHere = (Boolean) Mod.call(z, "isActiveHere");
                Mod.call(z, "setDungeonsOnly", false);
                boolean offHere = (Boolean) Mod.call(z, "isActiveHere");
                c.note("outside a dungeon, ZPEW on: active with Dungeons Only " + onHere + ", without " + offHere);
                c.check(!onHere && offHere, "Dungeons Only did not stop ZPEW outside a dungeon (" + onHere + ", " + offHere + ")");
                // Search "zpew".
                setSearch(s, "zpew");
                List<String> hits = hits(s);
                c.note("search 'zpew': " + hits);
                c.check(hits.stream().anyMatch(h -> h.startsWith("Zero Ping Etherwarp") && h.contains("Etherwarp")),
                        "searching zpew does not find the Zero Ping Etherwarp row: " + hits);
                McCompat.setScreen(mc, null);
                return null;
            });
        } finally {
            c.onClient(mc -> {
                Object z = Mod.cfg(ZP);
                Mod.call(z, "setEnabled", wasOn);
                Mod.call(z, "setMultiWarps", wasMulti);
                Mod.call(z, "setDungeonsOnly", wasDungeons);
                Mod.call(z, "setWarpsPerTick", wasWpt);
                Mod.call(z, "save");
                return null;
            });
        }
    }

    private static void setSearch(Screen s, String text) {
        Object box = Mod.field(s, "searchField");
        Mod.call(box, "setValue", text);
    }

    /** Each search hit as "label @ tab name". */
    @SuppressWarnings("unchecked")
    private static List<String> hits(Screen s) {
        List<String> out = new ArrayList<>();
        for (Object r : (List<Object>) Mod.call(s, "searchResults")) {
            Object tab = Mod.call(r, "tab");
            out.add(Mod.call(r, "label") + " @ " + MenuACases.name(tab));
        }
        return out;
    }

    // ---- video browser ----------------------------------------------------------------------------------------------

    static void videoBrowser(UiCase c) {
        Object cfg = Mod.cfg("shorts.ShortsConfig");
        boolean was = (Boolean) Mod.call(cfg, "isAutoScroll");
        boolean wasOn = (Boolean) Mod.call(cfg, "isEnabled");
        try {
            c.onClient(mc -> {
                Mod.call(cfg, "setEnabled", true);
                Screen s = open(mc);
                for (String q : new String[]{"tiktok", "tictok", "yt", "youtube", "web"}) {
                    setSearch(s, q);
                    List<String> h = hits(s);
                    c.note("search '" + q + "': " + h);
                    c.check(h.stream().anyMatch(x -> x.endsWith("@ Video Browser")), "searching '" + q
                            + "' does not find Video Browser: " + h);
                }
                setSearch(s, "");
                Mod.call(cfg, "setAutoScroll", false);
                Mod.call(s, "openFeature", MenuACases.leaf("ShortsTab"));
                AbstractWidget row = null;
                for (AbstractWidget w : MenuACases.paneRows(s)) {
                    if (ModScreenDriver.label(w).startsWith("Auto Scroll")) {
                        row = w;
                    }
                }
                c.check(row != null, "the Video Browser tab has no Auto Scroll row: " + labels(s));
                if (row != null) {
                    String tip = (String) Mod.staticCall("gui.SettingTooltips", "describe", "Video Browser", "Auto Scroll");
                    c.check(tip != null && !tip.isBlank(), "Auto Scroll has no tooltip");
                    Mod.call(row, "press");
                    c.check((Boolean) Mod.call(cfg, "isAutoScroll"), "pressing Auto Scroll did not turn it on");
                    Mod.staticCall("shorts.ShortsConfig", "load");
                    c.check((Boolean) Mod.call(Mod.cfg("shorts.ShortsConfig"), "isAutoScroll"),
                            "Auto Scroll did not survive a reload from disk");
                }
                String js = (String) Mod.field("shorts.ShortsFeature", "ENDED_HOOK_JS");
                c.check(js != null && js.contains("__k560Ended") && js.contains("timeupdate"),
                        "the end-of-video watcher is not in the jar");
                McCompat.setScreen(mc, null);
                return null;
            });
        } finally {
            c.onClient(mc -> {
                Object l = Mod.cfg("shorts.ShortsConfig");
                Mod.call(l, "setAutoScroll", was);
                Mod.call(l, "setEnabled", wasOn);
                Mod.call(l, "save");
                return null;
            });
        }
    }
}
