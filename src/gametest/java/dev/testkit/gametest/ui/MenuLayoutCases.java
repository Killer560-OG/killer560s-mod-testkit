package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.Report;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * 511-514: the mod menu's layout after killer560's 2026-10-07 report on the New category's removal ("Crosshair should
 * not be its own tab, put it in General"; "Map, Leap and Party, all of those should be their own unique tab, not all
 * grouped under the one"; "Some cheat things are right below their setting instead of being at the bottom like they
 * should be if they are cheats"). All four walk the REAL menu (gui.ModScreen through {@link ModScreenDriver}).
 * <ul>
 *   <li>511 structure: (a) no top-level Crosshair tab, the Custom Crosshair section is in General and its rows build
 *       there; (b) no combined "X, Y &amp; Z" folder, no folder inside a category but Secrets / Puzzle Solvers, no
 *       folder holding a lone tab; (d) every tab of the menu before the change ({@code menu-layout-<variant>.json}) is
 *       in the menu exactly once, and nothing is new but the Correction Alarm section.</li>
 *   <li>512 cheat order: (c) in every category, folder and tab, every cheat-only entry comes after every legit one - the
 *       red cheat-only tabs after every orange tab, and inside a tab every red section header after every orange one,
 *       in the default layout AND with each toggle flipped (a cheat section behind a master switch only shows then).
 *       On a cheat jar, a row only the cheat jar builds in a tab (both fixtures, default layout) must sit below every
 *       row the legit jar builds there, red header or not. A legit jar has no cheat-only tab and no red header at all,
 *       and no header with nothing under it. On the pre-change cheat jar (mod 6a61dfa8) it fails with 145 findings.</li>
 *   <li>513 tooltips: (e) every row with a tooltip before the change shows the same text now (the Crosshair rows under
 *       their new sub-tab name), and no scoped tooltip key in gui.SettingTooltipsData names a tab that no longer
 *       exists (a renamed sub-tab orphans its "name/label" keys silently).</li>
 *   <li>514 screenshots: every category's tab list at GUI 2 (1920x1080, Auto Scale off), every page of it, with the
 *       menu asserted on screen first.</li>
 * </ul>
 * On a jar that still has the top-level Crosshair tab (the mod before the change) 511 records the fixture to
 * {@code build/testkit-report/} and fails - which is how the fixture was made (mod 6a61dfa8). A testing jar (mod
 * -PtestingBuild) is walked with every tab counted as tested for the duration, so its normal menu is what is measured;
 * the Untested category itself is 413's. Leaves no mark behind: the marks are put back in memory, never saved.
 */
final class MenuLayoutCases {

    private static final String TIPS = "gui.SettingTooltips";
    private static final String UNSCOPED = "(unscoped)";
    private static final String CHEAT = "§c§l";
    private static final String LEGIT = "§6§l";
    /** The only folders a category may hold: each was asked for as one tab (killer560, 2026-09-20). */
    private static final Set<String> NESTED_OK = Set.of("Secrets", "Puzzle Solvers");
    /** Sections added by this change (cheat build): Home's Correction Alarm, out of the middle of Mod & HUD. */
    private static final Set<String> NEW_LEAVES = Set.of("gui.tab.CorrectionAlarmTab");
    /** Sub-tabs renamed by this change: old scope -> new scope. */
    private static final Map<String, String> RENAMED = Map.of("Crosshair", "Custom Crosshair");
    private static final Pattern COMBINED = Pattern.compile(".*(, | & ).*");

    private MenuLayoutCases() {
    }

    /** A content row as built: who built it (sub-tab scope), its raw label (section signs kept) and where. */
    private record Row(String owner, String raw, String label, String key, String tip, boolean header, int x, int y) {
        boolean cheatHeader() {
            return header && raw.startsWith(CHEAT);
        }

        boolean legitHeader() {
            return header && raw.startsWith(LEGIT);
        }
    }

    // ---- 511 ------------------------------------------------------------------------------------------------------

    static void structure(UiCase c) throws Exception {
        String variant = Mod.isCheat() ? "cheat" : "legit";
        try (AutoCloseable all = allTested(c)) {
            ModScreenDriver d = driver(c);
            List<ModScreenDriver.Node> tree = c.onClient(mc -> d.tree());
            List<String> top = new ArrayList<>();
            for (ModScreenDriver.Node n : tree) {
                top.add(n.name());
            }
            c.note("top level (" + variant + "): " + top);
            List<ModScreenDriver.Node> flat = new ArrayList<>();
            ModScreenDriver.flatten(tree, flat);
            Map<String, List<String>> leaves = new LinkedHashMap<>();
            for (ModScreenDriver.Node n : flat) {
                if (!n.folder()) {
                    leaves.computeIfAbsent(cls(n), k -> new ArrayList<>()).add(n.path());
                }
            }
            for (ModScreenDriver.Node n : tree) {
                c.note(n.name() + " -> " + describe(n));
            }

            if (top.contains("Crosshair")) {
                record(c, d, tree, flat, variant);
                c.problem("the menu still has a top-level 'Crosshair' tab (recorded the fixture instead)");
                return;
            }

            // (a) Crosshair: not top level, a section of General, and its rows build there.
            for (String t : top) {
                c.check(!t.toLowerCase(java.util.Locale.ROOT).contains("crosshair"), "a top-level tab is still called '" + t + "'");
            }
            List<String> crosshair = leaves.get("gui.tab.CrosshairTab");
            c.note("CrosshairTab at " + crosshair);
            c.check(crosshair != null && crosshair.size() == 1 && crosshair.get(0).equals("General > Custom Crosshair"),
                    "the crosshair editor is not the General > Custom Crosshair section: " + crosshair);
            int general = top.indexOf("General");
            c.check(general >= 0, "no General category");
            List<Row> rows = section(c, d, general, "Custom Crosshair");
            List<String> labels = new ArrayList<>();
            for (Row r : rows) {
                if ("Custom Crosshair".equals(r.owner())) {
                    labels.add(r.label());
                }
            }
            c.note("General > Custom Crosshair builds " + labels.size() + " rows: " + labels);
            c.check(labels.stream().anyMatch(l -> l.startsWith("Custom Crosshair: ")), "no 'Custom Crosshair:' toggle in General");
            c.check(labels.stream().anyMatch(l -> l.startsWith("Style: ")), "no 'Style:' row in General's crosshair section");
            c.check(labels.contains("Crosshair Preview"), "no crosshair preview in General's crosshair section");
            c.check(labels.size() >= 20, "the crosshair section built only " + labels.size() + " rows");
            boolean found = c.onClient(mc -> {
                try {
                    d.search("crosshair");
                    List<Object> vis = d.visibleTabs();
                    boolean g = vis.stream().anyMatch(t -> "General".equals(R.get(t, "name")));
                    d.search("");
                    return g;
                } catch (Throwable t) {
                    throw new AssertionError(t);
                }
            });
            c.check(found, "searching 'crosshair' does not surface General");

            // (b) no combined folders, no other nested folder, no lone tab in a folder.
            for (ModScreenDriver.Node n : flat) {
                if (!n.folder()) {
                    continue;
                }
                if (n.depth() > 0) {
                    c.check(!COMBINED.matcher(n.name()).matches(), "combined folder still there: " + n.path());
                    c.check(NESTED_OK.contains(n.name()), "a folder inside a category: " + n.path()
                            + " (only " + NESTED_OK + " are)");
                }
                c.check(n.children().size() >= 2, "folder " + n.path() + " holds " + n.children().size() + " tab(s)");
            }

            // (d) every tab exactly once, against the menu before the change.
            JsonObject fixture = Deny.read("menu-layout-" + variant + ".json");
            Set<String> before = fixture.getAsJsonObject("leaves").keySet();
            int kept = 0;
            for (String cls : before) {
                List<String> at = leaves.get(cls);
                if (at == null) {
                    c.problem(cls + " (was " + fixture.getAsJsonObject("leaves").get(cls).getAsString()
                            + ") is no longer in the menu");
                } else if (at.size() != 1) {
                    c.problem(cls + " is in the menu " + at.size() + " times: " + at);
                } else {
                    kept++;
                    String was = fixture.getAsJsonObject("leaves").get(cls).getAsString();
                    if (!was.equals(at.get(0))) {
                        c.note("moved: " + was + "  ->  " + at.get(0));
                    }
                }
            }
            for (Map.Entry<String, List<String>> e : leaves.entrySet()) {
                if (!before.contains(e.getKey())) {
                    c.note("new tab " + e.getKey() + " at " + e.getValue());
                    c.check(NEW_LEAVES.contains(e.getKey()), "unexpected new tab " + e.getKey() + " at " + e.getValue());
                    c.check(e.getValue().size() == 1, e.getKey() + " is in the menu " + e.getValue().size() + " times");
                }
            }
            c.note("tabs from before the change found exactly once: " + kept + " of " + before.size());
            c.check(kept == before.size() && kept > 100, "only " + kept + " of " + before.size() + " tabs found once");
            close(c, d);
        }
    }

    // ---- 512 ------------------------------------------------------------------------------------------------------

    static void cheatOrder(UiCase c, Deny deny) throws Exception {
        boolean cheat = Mod.isCheat();
        // On a cheat jar, which rows of each sub-tab are cheat-only: built by the cheat jar but not the legit one in the
        // same fresh default layout (both fixtures). No legit row may sit below one of those - which also catches a
        // cheat row with no red header over it (Home's Correction Alarm sat in the middle of Mod & HUD). Rows in
        // neither list (a row an earlier case's settings revealed, the testing build's Mark row) are not judged.
        Map<String, Set<String>> legitRows = new LinkedHashMap<>();
        Map<String, Set<String>> cheatRows = new LinkedHashMap<>();
        if (cheat) {
            for (Map.Entry<String, JsonElement> o : Deny.read("menu-layout-legit.json").getAsJsonObject("rows").entrySet()) {
                legitRows.put(RENAMED.getOrDefault(o.getKey(), o.getKey()), o.getValue().getAsJsonObject().keySet());
            }
            for (Map.Entry<String, JsonElement> o : Deny.read("menu-layout-cheat.json").getAsJsonObject("rows").entrySet()) {
                Set<String> only = new HashSet<>(o.getValue().getAsJsonObject().keySet());
                only.removeAll(legitRows.getOrDefault(RENAMED.getOrDefault(o.getKey(), o.getKey()), Set.of()));
                // Home's Correction Alarm rows moved to a sub-tab of their own; judge them wherever they are built.
                for (String k : only) {
                    cheatRows.computeIfAbsent(RENAMED.getOrDefault(o.getKey(), o.getKey()), x -> new HashSet<>()).add(k);
                }
            }
        }
        legitRowsForSweep = legitRows;
        cheatRowsForSweep = cheatRows;
        try (AutoCloseable all = allTested(c)) {
            ModScreenDriver d = driver(c);
            List<ModScreenDriver.Node> tree = c.onClient(mc -> d.tree());
            List<ModScreenDriver.Node> flat = new ArrayList<>();
            ModScreenDriver.flatten(tree, flat);
            // Tab order: in every folder, no legit tab after a cheat-only one.
            int cheatTabs = 0;
            for (ModScreenDriver.Node n : flat) {
                boolean isCheat = cheatOnly(c, n);
                if (isCheat) {
                    cheatTabs++;
                    c.check(cheat, "legit jar: cheat-only tab " + n.path());
                }
                if (!n.folder()) {
                    continue;
                }
                String firstCheat = null;
                for (ModScreenDriver.Node k : n.children()) {
                    if (cheatOnly(c, k)) {
                        if (firstCheat == null) {
                            firstCheat = k.name();
                        }
                    } else if (firstCheat != null) {
                        c.problem(n.path() + ": legit '" + k.name() + "' comes after cheat-only '" + firstCheat + "'");
                    }
                }
            }
            c.note("cheat-only tabs and folders in the tree: " + cheatTabs);
            if (cheat) {
                c.check(cheatTabs > 30, "only " + cheatTabs + " cheat-only tabs - the walk is broken");
            }
            for (ModScreenDriver.Node n : tree) {
                List<String> order = new ArrayList<>();
                for (ModScreenDriver.Node k : n.children()) {
                    order.add((cheatOnly(c, k) ? "[C] " : "") + k.name());
                }
                c.note(n.name() + ": " + order);
            }

            // Section order inside each tab, in every layout a toggle opens.
            int[] layouts = {0};
            int[] redHeaders = {0};
            Set<String> found = new LinkedHashSet<>();
            for (int i = 0; i < tree.size(); i++) {
                ModScreenDriver.Node topNode = tree.get(i);
                int sections = topNode.folder() ? topNode.children().size() : 1;
                for (int j = 0; j < sections; j++) {
                    int top = i;
                    int sec = j;
                    String where = topNode.name() + (topNode.folder() ? " > " + topNode.children().get(j).name() : "");
                    c.onClient(mc -> {
                        try {
                            open(d, top, sec, deny);
                            sweep(c, d, where, deny, found, layouts, redHeaders, cheat);
                        } catch (Throwable t) {
                            c.problem("'" + where + "': " + UiCase.describe(t));
                        }
                        return null;
                    });
                }
            }
            c.note("layouts checked: " + layouts[0] + ", red section headers seen: " + redHeaders[0]);
            for (String f : found) {
                c.problem(f);
            }
            c.check(layouts[0] > 100, "only " + layouts[0] + " layouts checked - the sweep did not reach the menu");
            if (cheat) {
                c.check(redHeaders[0] > 10, "only " + redHeaders[0] + " red headers seen on a cheat jar");
            } else {
                c.check(redHeaders[0] == 0, "legit jar shows " + redHeaders[0] + " red header(s)");
            }
            close(c, d);
        }
    }

    /** Owner sub-tab -> label keys the legit jar builds there (cheat jars only; empty otherwise). */
    private static Map<String, Set<String>> legitRowsForSweep = Map.of();
    /** Owner sub-tab -> label keys only the cheat jar builds there (cheat jars only; empty otherwise). */
    private static Map<String, Set<String>> cheatRowsForSweep = Map.of();

    private static void sweep(UiCase c, ModScreenDriver d, String where, Deny deny, Set<String> found, int[] layouts,
                              int[] red, boolean cheat) throws Throwable {
        checkOrder(d, where, "", found, red, cheat);
        layouts[0]++;
        List<String> seen = new ArrayList<>();
        for (int guard = 0; guard < 200; guard++) {
            AbstractWidget target = null;
            String name = null;
            for (AbstractWidget w : d.content()) {
                var m = TabSweep.TOGGLE.matcher(ModScreenDriver.label(w));
                if (d.isSettingsButton(w) && m.matches() && !seen.contains(m.group(1))) {
                    target = w;
                    name = m.group(1);
                    break;
                }
            }
            if (target == null) {
                return;
            }
            seen.add(name);
            if (deny.button(ModScreenDriver.label(target)) != null) {
                continue;
            }
            ModScreenDriver.press(target);
            d.rebuild();
            checkOrder(d, where, " (with " + name + " flipped)", found, red, cheat);
            layouts[0]++;
            AbstractWidget again = toggle(d, name);
            ModScreenDriver.press(again != null ? again : target);
            d.rebuild();
        }
    }

    /** In each sub-tab's rows (top to bottom): no orange header after a red one, and no header with nothing under it. */
    private static void checkOrder(ModScreenDriver d, String where, String state, Set<String> found, int[] red,
                                   boolean cheat) throws Exception {
        Map<String, List<Row>> byOwner = new LinkedHashMap<>();
        for (Row r : rows(d, null)) {
            byOwner.computeIfAbsent(r.owner(), k -> new ArrayList<>()).add(r);
        }
        for (Map.Entry<String, List<Row>> e : byOwner.entrySet()) {
            List<Row> rs = e.getValue();
            rs.sort(Comparator.comparingInt(Row::y).thenComparingInt(Row::x));
            Set<String> legit = state.isEmpty() && !UNSCOPED.equals(e.getKey()) ? legitRowsForSweep.get(e.getKey()) : null;
            Set<String> cheatOnly = cheatRowsForSweep.getOrDefault(e.getKey(), Set.of());
            if (legit != null) {
                // Default layout, cheat jar: no row the legit jar builds here may sit below a cheat-only one.
                Row firstCheatRow = null;
                for (Row r : rs) {
                    if (r.key().isEmpty()) {
                        continue;
                    }
                    if (cheatOnly.contains(r.key()) && !legit.contains(r.key())) {
                        if (firstCheatRow == null) {
                            firstCheatRow = r;
                        }
                    } else if (legit.contains(r.key()) && firstCheatRow != null && r.y() > firstCheatRow.y()) {
                        found.add("'" + where + "' > " + e.getKey() + ": legit row '" + r.label()
                                + "' sits below cheat-only row '" + firstCheatRow.label() + "'");
                    }
                }
            }
            String firstRed = null;
            for (int k = 0; k < rs.size(); k++) {
                Row r = rs.get(k);
                if (r.cheatHeader()) {
                    red[0]++;
                    if (!cheat) {
                        found.add("'" + where + "' > " + e.getKey() + ": red header '" + r.label() + "' on a legit jar" + state);
                    }
                    if (firstRed == null) {
                        firstRed = r.label();
                    }
                } else if (r.legitHeader() && firstRed != null) {
                    found.add("'" + where + "' > " + e.getKey() + ": legit section '" + r.label()
                            + "' comes after cheat section '" + firstRed + "'" + state);
                }
                if ((r.cheatHeader() || r.legitHeader()) && k == rs.size() - 1) {
                    found.add("'" + where + "' > " + e.getKey() + ": header '" + r.label() + "' has nothing under it" + state);
                }
            }
        }
    }

    // ---- 513 ------------------------------------------------------------------------------------------------------

    static void tooltips(UiCase c, Deny deny) throws Exception {
        String variant = Mod.isCheat() ? "cheat" : "legit";
        JsonObject fixture = Deny.read("menu-layout-" + variant + ".json");
        try (AutoCloseable all = allTested(c)) {
            ModScreenDriver d = driver(c);
            List<ModScreenDriver.Node> tree = c.onClient(mc -> d.tree());
            Map<String, Map<String, String>> now = collect(c, d, tree, deny);
            close(c, d);
            // No scoped key names a tab that the change took away (it would never be found again).
            Set<String> namesNow = new HashSet<>();
            List<ModScreenDriver.Node> flat = new ArrayList<>();
            ModScreenDriver.flatten(tree, flat);
            for (ModScreenDriver.Node n : flat) {
                namesNow.add(n.name().toLowerCase(java.util.Locale.ROOT));
            }
            Set<String> gone = new TreeSet<>();
            for (JsonElement e : fixture.getAsJsonArray("names")) {
                String n = e.getAsString().toLowerCase(java.util.Locale.ROOT);
                if (!namesNow.contains(n)) {
                    gone.add(n);
                }
            }
            int compared = 0;
            int moved = 0;
            for (Map.Entry<String, JsonElement> o : fixture.getAsJsonObject("rows").entrySet()) {
                String owner = RENAMED.getOrDefault(o.getKey(), o.getKey());
                for (Map.Entry<String, JsonElement> r : o.getValue().getAsJsonObject().entrySet()) {
                    String key = r.getKey();
                    String was = r.getValue().getAsString();
                    if (was.isEmpty()) {
                        continue;
                    }
                    compared++;
                    Map<String, String> m = now.get(owner);
                    String got = m == null ? null : m.get(key);
                    if (was.equals(got)) {
                        continue;
                    }
                    if (got == null && gone.contains(key)) {
                        // The accordion header of a folder this change removed (Map, Leap & Party; Timers, Score &
                        // Boss): there is no such row any more, so nothing to show a tooltip on.
                        c.note("header row '" + key + "' went with its folder");
                        continue;
                    }
                    // A row that moved to another sub-tab (Correction Alarm out of Mod & HUD) or a header now drawn by
                    // another folder: the same key showing the same text anywhere counts.
                    String elsewhere = null;
                    for (Map.Entry<String, Map<String, String>> e : now.entrySet()) {
                        if (was.equals(e.getValue().get(key))) {
                            elsewhere = e.getKey();
                            break;
                        }
                    }
                    if (elsewhere != null) {
                        moved++;
                        c.note("row '" + key + "' moved from '" + owner + "' to '" + elsewhere + "', same tooltip");
                        continue;
                    }
                    c.problem("tooltip changed for '" + owner + "' row '" + key + "': was \"" + was + "\", now "
                            + (got == null ? "none (row not built there)" : "\"" + got + "\""));
                }
            }
            c.note("rows with a tooltip compared: " + compared + " (" + moved + " moved to another sub-tab)");
            c.check(compared > 500, "only " + compared + " tooltips compared - the walk is broken");

            c.note("tab names gone since the fixture: " + gone);
            @SuppressWarnings("unchecked")
            Map<String, String> descriptions = (Map<String, String>) R.getStatic(R.cls(TIPS), "DESCRIPTIONS");
            int orphans = 0;
            for (String key : new TreeSet<>(descriptions.keySet())) {
                for (String g : gone) {
                    if (key.startsWith(g + "/")) {
                        orphans++;
                        c.problem("orphaned scoped tooltip '" + key + "': no tab is called '" + g + "' any more");
                    }
                }
            }
            c.note("orphaned scoped tooltips: " + orphans);
        }
    }

    // ---- 514 ------------------------------------------------------------------------------------------------------

    static void screenshots(UiCase c) throws Exception {
        String variant = Mod.isCheat() ? "cheat" : "legit";
        Object hud = Mod.cfg("hud.HudConfig");
        boolean oldAuto = (Boolean) Mod.call(hud, "isAutoScale");
        int[] oldWindow = c.onClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight()});
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        try (AutoCloseable all = allTested(c)) {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                Mod.call(hud, "setAutoScale", false);
                return null;
            });
            c.ctx().getInput().resizeWindow(1920, 1080);
            c.ticks(3);
            c.onClient(mc -> {
                mc.options.guiScale().set(2);
                mc.resizeGui();
                return null;
            });
            c.ticks(3);
            ModScreenDriver d = driver(c);
            int tops = c.onClient(mc -> {
                McCompat.setScreen(mc, d.screen);
                return d.topTabs().size();
            });
            c.ticks(3);
            int shots = 0;
            for (int i = 0; i < tops; i++) {
                int top = i;
                Object[] st = c.onClient(mc -> {
                    try {
                        d.select(top);
                        d.rebuild();
                        return new Object[]{R.get(d.topTabs().get(top), "name"), d.maxScroll(), d.visibleContentHeight()};
                    } catch (Throwable t) {
                        throw new AssertionError(t);
                    }
                });
                String name = (String) st[0];
                int max = (Integer) st[1];
                int step = Math.max(20, (Integer) st[2] - 20);
                int page = 0;
                for (int off = 0; ; off += step) {
                    int o = Math.min(off, max);
                    c.onClient(mc -> {
                        try {
                            d.scrollTo(o);
                            d.rebuild();
                        } catch (Throwable t) {
                            throw new AssertionError(t);
                        }
                        return null;
                    });
                    c.ticks(3);
                    // Assert the menu is what is on screen, on this category, with rows in it, before the shot.
                    String why = c.onClient(mc -> {
                        if (McCompat.screen(mc) != d.screen) {
                            return "the mod menu is not on screen (" + McCompat.screen(mc) + ")";
                        }
                        int sel = (Integer) R.getStatic(d.screenCls, "selectedTab");
                        if (sel != top) {
                            return "selected tab " + sel + ", wanted " + top;
                        }
                        return d.content().isEmpty() ? "the content pane is empty" : null;
                    });
                    if (why != null) {
                        c.problem(name + ": " + why);
                        break;
                    }
                    String shot = c.name() + "-" + variant + "-" + String.format("%02d", i) + "-"
                            + name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-") + "-p" + (++page);
                    Path taken = c.ctx().takeScreenshot(Report.fileName(shot));
                    Path kept = Report.screenshot(shot, taken);
                    c.check(kept != null, "no screenshot for " + shot);
                    shots++;
                    if (o >= max) {
                        break;
                    }
                }
                c.note(name + ": " + page + " page(s) at GUI 2");
            }
            c.note("screenshots taken: " + shots);
            c.check(shots >= tops, "only " + shots + " screenshots for " + tops + " categories");
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
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

    // ---- shared ---------------------------------------------------------------------------------------------------

    private static ModScreenDriver driver(UiCase c) {
        return c.onClient(mc -> {
            try {
                R.setStatic(R.cls(ModScreenDriver.MOD_SCREEN), "tabs", null);
                return new ModScreenDriver(mc);
            } catch (Throwable t) {
                throw new AssertionError("could not open gui.ModScreen: " + UiCase.describe(t), t);
            }
        });
    }

    /**
     * A testing jar (mod -PtestingBuild) shows untested tabs in its Untested category; for the duration, count every
     * tab tested (TestedFeatures' repo list, in memory only) so the normal menu is what is walked. A no-op otherwise.
     */
    @SuppressWarnings("unchecked")
    private static AutoCloseable allTested(UiCase c) {
        if (JarIndex.peek(JarIndex.ROOT + "testing.TestedFeatures") == null) {
            return () -> { };
        }
        Object[] saved = c.onClient(mc -> {
            try {
                R.setStatic(R.cls(ModScreenDriver.MOD_SCREEN), "tabs", null);
                new ModScreenDriver(mc); // runs TestingMenu.arrange, which records the normal leaves
                Class<?> tf = R.cls("testing.TestedFeatures");
                Set<String> repo = (Set<String>) R.getStatic(tf, "repo");
                Set<String> untested = (Set<String>) R.getStatic(tf, "markedUntested");
                Map<String, String> normal = (Map<String, String>) R.cls("testing.TestingMenu")
                        .getMethod("normalLeaves").invoke(null);
                Set<String> everything = new HashSet<>(repo);
                everything.addAll(normal.keySet());
                Object[] keep = {repo, new HashSet<>(untested)};
                R.setStatic(tf, "repo", everything);
                untested.clear();
                R.setStatic(R.cls(ModScreenDriver.MOD_SCREEN), "tabs", null);
                return keep;
            } catch (Throwable t) {
                throw new AssertionError("could not count every tab tested: " + UiCase.describe(t), t);
            }
        });
        c.note("testing jar: every tab counted as tested while this case walks the menu");
        return () -> c.onClient(mc -> {
            Class<?> tf = R.cls("testing.TestedFeatures");
            R.setStatic(tf, "repo", saved[0]);
            Set<String> untested = (Set<String>) R.getStatic(tf, "markedUntested");
            untested.addAll((Set<String>) saved[1]);
            R.setStatic(R.cls(ModScreenDriver.MOD_SCREEN), "tabs", null);
            return null;
        });
    }

    private static String cls(ModScreenDriver.Node n) {
        return n.tab().getClass().getName().replace(Mod.ROOT, "");
    }

    private static boolean cheatOnly(UiCase c, ModScreenDriver.Node n) {
        return c.onClient(mc -> {
            try {
                return (Boolean) n.tab().getClass().getMethod("isCheatOnly").invoke(n.tab());
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
    }

    private static String describe(ModScreenDriver.Node n) {
        if (!n.folder()) {
            return "(a tab)";
        }
        List<String> out = new ArrayList<>();
        for (ModScreenDriver.Node k : n.children()) {
            out.add(k.folder() ? k.name() + " " + describe(k) : k.name());
        }
        return out.toString();
    }

    /** Select top-level tab {@code top}, open its section {@code sec} (and every folder inside), and every collapsible. */
    private static void open(ModScreenDriver d, int top, int sec, Deny deny) throws Throwable {
        Object topTab = d.topTabs().get(top);
        d.select(top);
        if (d.isFolder(topTab)) {
            d.expanded(topTab).add(sec);
            d.expandAll(d.subTabs(topTab).get(sec));
        }
        d.rebuild();
        d.openCollapsibles(deny, new ArrayList<>());
    }

    /** The rows of {@code top}'s section named {@code sectionName} (its only section when the tab is not a folder). */
    private static List<Row> section(UiCase c, ModScreenDriver d, int top, String sectionName) {
        return c.onClient(mc -> {
            try {
                Object topTab = d.topTabs().get(top);
                int sec = 0;
                if (d.isFolder(topTab)) {
                    List<Object> subs = d.subTabs(topTab);
                    sec = -1;
                    for (int i = 0; i < subs.size(); i++) {
                        if (sectionName.equals(R.get(subs.get(i), "name"))) {
                            sec = i;
                        }
                    }
                    if (sec < 0) {
                        return List.of();
                    }
                }
                open(d, top, sec, Deny.load());
                return rows(d, (String) R.get(topTab, "name"));
            } catch (Throwable t) {
                throw new AssertionError(UiCase.describe(t), t);
            }
        });
    }

    /** What the selected tab built, with each row's owner and (when {@code topName} is given) its tooltip. */
    private static List<Row> rows(ModScreenDriver d, String topName) throws Exception {
        Method describe = R.cls(TIPS).getMethod("describe", String.class, AbstractWidget.class, String.class);
        Method key = R.cls(TIPS).getMethod("key", String.class);
        @SuppressWarnings("unchecked")
        Map<AbstractWidget, String> scopes = (Map<AbstractWidget, String>) R.getStatic(R.cls(TIPS), "SCOPES");
        List<Row> out = new ArrayList<>();
        for (AbstractWidget w : d.content()) {
            String raw = w.getMessage().getString();
            String label = ModScreenDriver.label(w);
            String k = (String) key.invoke(null, raw);
            String owner = scopes.get(w);
            String tip = topName == null || k.isEmpty() ? null : (String) describe.invoke(null, topName, w, raw);
            out.add(new Row(owner == null ? UNSCOPED : owner, raw, label, k, tip == null ? "" : tip,
                    w instanceof StringWidget, w.getX(), w.getY()));
        }
        return out;
    }

    private static AbstractWidget toggle(ModScreenDriver d, String name) {
        for (AbstractWidget w : d.content()) {
            var m = TabSweep.TOGGLE.matcher(ModScreenDriver.label(w));
            if (d.isSettingsButton(w) && m.matches() && m.group(1).equals(name)) {
                return w;
            }
        }
        return null;
    }

    /** owner sub-tab -> label key -> tooltip, every section of every category opened in turn (default layout). */
    private static Map<String, Map<String, String>> collect(UiCase c, ModScreenDriver d, List<ModScreenDriver.Node> tree,
                                                            Deny deny) {
        Map<String, Map<String, String>> out = new TreeMap<>();
        for (int i = 0; i < tree.size(); i++) {
            ModScreenDriver.Node topNode = tree.get(i);
            int sections = topNode.folder() ? topNode.children().size() : 1;
            for (int j = 0; j < sections; j++) {
                int top = i;
                int sec = j;
                c.onClient(mc -> {
                    try {
                        open(d, top, sec, deny);
                        for (Row r : rows(d, topNode.name())) {
                            if (!r.key().isEmpty()) {
                                out.computeIfAbsent(r.owner(), x -> new TreeMap<>()).putIfAbsent(r.key(), r.tip());
                            }
                        }
                    } catch (Throwable t) {
                        c.problem("walking " + topNode.name() + " section " + sec + " threw " + UiCase.describe(t));
                    }
                    return null;
                });
            }
        }
        return out;
    }

    private static void record(UiCase c, ModScreenDriver d, List<ModScreenDriver.Node> tree,
                               List<ModScreenDriver.Node> flat, String variant) throws Exception {
        Map<String, String> leaves = new TreeMap<>();
        Set<String> names = new TreeSet<>();
        for (ModScreenDriver.Node n : flat) {
            names.add(n.name());
            if (!n.folder()) {
                leaves.put(cls(n), n.path());
            }
        }
        Map<String, Map<String, String>> rows = collect(c, d, tree, Deny.load());
        close(c, d);
        JsonObject out = new JsonObject();
        out.addProperty("_doc", "Recorded by 511-ui-menu-layout from the menu before killer560's 2026-10-07 layout "
                + "report (Crosshair still top level): every node name, every leaf tab (class -> path) and the tooltip "
                + "each row showed (owner sub-tab -> label key -> tooltip). Variant " + variant + ".");
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        out.add("names", gson.toJsonTree(names));
        out.add("leaves", gson.toJsonTree(leaves));
        out.add("rows", gson.toJsonTree(rows));
        Path file = Report.dir().resolve("menu-layout-" + variant + ".json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, gson.toJson(out), StandardCharsets.UTF_8);
        c.note("recorded " + leaves.size() + " leaf tabs and " + rows.size() + " row owners to " + file);
    }

    private static void close(UiCase c, ModScreenDriver d) {
        c.onClient((Minecraft mc) -> {
            McCompat.setScreen(mc, null);
            d.select(0);
            return null;
        });
    }
}
