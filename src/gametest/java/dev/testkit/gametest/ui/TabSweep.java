package dev.testkit.gametest.ui;

import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Cases 301-303: the mod menu (gui.ModScreen) - every tab, every folder section, search, and every two-state toggle.
 */
final class TabSweep {

    /**
     * Floors, measured on mod 8c43a6d (see docs/wp/ui.md): the tab tree as ModScreen builds it, counting folders and
     * leaves. Set at the measured value, so losing even one tab fails - a floor a broken menu can clear is not one.
     */
    static final int CHEAT_TAB_FLOOR = 170;
    static final int LEGIT_TAB_FLOOR = 150;

    /** A two-state toggle as the mod labels them: "Name: ON" / "Name: OFF" (section signs stripped). */
    static final Pattern TOGGLE = Pattern.compile("^(.+): (ON|OFF)$");

    private TabSweep() {
    }

    // ---- 301 ------------------------------------------------------------------------------------------------

    static void tabs(UiCase c, Deny deny) {
        Map<String, Object> stats = c.onClient(mc -> {
            Map<String, Object> out = new LinkedHashMap<>();
            try {
                ModScreenDriver d = new ModScreenDriver(mc);
                List<ModScreenDriver.Node> tree = d.tree();
                List<ModScreenDriver.Node> flat = new ArrayList<>();
                ModScreenDriver.flatten(tree, flat);
                int leaves = 0;
                int folders = 0;
                for (ModScreenDriver.Node n : flat) {
                    if (n.folder()) {
                        folders++;
                    } else {
                        leaves++;
                    }
                }
                out.put("top", tree.size());
                out.put("total", flat.size());
                out.put("folders", folders);
                out.put("leaves", leaves);
                out.put("driver", d);
                out.put("tree", tree);
                out.put("flat", flat);
            } catch (Throwable t) {
                out.put("error", UiCase.describe(t));
            }
            return out;
        });
        c.check(!stats.containsKey("error"), "could not open gui.ModScreen: " + stats.get("error"));
        ModScreenDriver d = (ModScreenDriver) stats.get("driver");
        @SuppressWarnings("unchecked")
        List<ModScreenDriver.Node> tree = (List<ModScreenDriver.Node>) stats.get("tree");
        @SuppressWarnings("unchecked")
        List<ModScreenDriver.Node> flat = (List<ModScreenDriver.Node>) stats.get("flat");
        int total = (Integer) stats.get("total");
        boolean cheat = Mod.isCheat();
        c.note("tab tree: " + stats.get("top") + " top-level, " + total + " in all (" + stats.get("folders")
                + " folders, " + stats.get("leaves") + " leaves), " + (cheat ? "cheat" : "legit") + " jar");
        int floor = cheat ? CHEAT_TAB_FLOOR : LEGIT_TAB_FLOOR;
        if (total < floor) {
            c.problem("only " + total + " tabs in the tree; the floor for the " + (cheat ? "cheat" : "legit")
                    + " jar is " + floor + " (measured on 8c43a6d) - tabs went missing");
        }

        // Every leaf, built on its own: attributes a throw to ONE tab, which the screen-level sweep cannot.
        int[] built = new int[2];
        List<String> empty = new ArrayList<>();
        c.onClient(mc -> {
            Method build = buildMethod();
            for (ModScreenDriver.Node n : flat) {
                if (n.folder()) {
                    continue;
                }
                try {
                    @SuppressWarnings("unchecked")
                    List<AbstractWidget> ws = (List<AbstractWidget>) build.invoke(n.tab(), 0, 0, 300,
                            (Runnable) () -> { });
                    built[0]++;
                    built[1] += ws.size();
                    if (ws.isEmpty()) {
                        empty.add(n.path());
                    }
                } catch (InvocationTargetException e) {
                    c.problem("tab '" + n.path() + "' buildWidgets threw " + UiCase.describe(e.getCause()));
                } catch (ReflectiveOperationException e) {
                    c.problem("tab '" + n.path() + "': " + e);
                }
            }
            return null;
        });
        c.note("leaves built on their own: " + built[0] + ", " + built[1] + " widgets"
                + (empty.isEmpty() ? "" : "; built NO widgets: " + empty));

        // Through the real screen: each top-level tab, then each folder section open (nested folders fully open),
        // every collapsible section inside opened, the content scrolled through, a frame extracted at every step.
        int[] counts = new int[4]; // states, frames, collapsibles opened, widgets seen
        List<String> denied = new ArrayList<>();
        for (int i = 0; i < tree.size(); i++) {
            int top = i;
            ModScreenDriver.Node topNode = tree.get(i);
            boolean clean = c.onClient(mc -> {
                boolean ok = true;
                List<Integer> sections = new ArrayList<>();
                if (topNode.folder()) {
                    for (int j = 0; j < topNode.children().size(); j++) {
                        sections.add(j);
                    }
                } else {
                    sections.add(-1);
                }
                // the folder with everything shut first (pinned first section still shows)
                ok &= visit(c, d, mc, top, -1, topNode.path() + " (closed)", deny, denied, counts);
                for (int j : sections) {
                    if (j < 0) {
                        continue;
                    }
                    String path = topNode.children().get(j).path();
                    ok &= visit(c, d, mc, top, j, path, deny, denied, counts);
                }
                return ok;
            });
            if (clean) {
                // Real frames: the same screen on the real window, the folder fully open.
                c.onClient(mc -> {
                    d.select(top);
                    d.expandAll(topNode.tab());
                    mc.setScreen(d.screen);
                    return null;
                });
                c.ticks(2);
                counts[1] += 2;
            }
        }
        c.onClient(mc -> {
            mc.setScreen(null);
            d.select(0);
            return null;
        });
        c.note("screen states " + counts[0] + ", frames " + counts[1] + ", collapsible sections opened "
                + counts[2] + ", widgets seen " + counts[3]);
        if (!denied.isEmpty()) {
            c.note("section headers NOT pressed (deny list): " + denied);
        }
        c.check(counts[0] >= total / 2, "visited only " + counts[0] + " screen states for " + total + " tabs");
        c.check(counts[3] > total, "only " + counts[3] + " widgets seen across " + total + " tabs");
    }

    /** One screen state: select top tab, open section j (-1 = none), open collapsibles, scroll through. */
    private static boolean visit(UiCase c, ModScreenDriver d, Minecraft mc, int top, int section, String path,
                                 Deny deny, List<String> denied, int[] counts) {
        try {
            d.select(top);
            Object topTab = d.topTabs().get(top);
            if (section >= 0) {
                d.expanded(topTab).add(section);
                d.expandAll(d.subTabs(topTab).get(section));
            }
            d.rebuild();
            if (section >= 0 || !d.isFolder(topTab)) {
                counts[2] += d.openCollapsibles(deny, denied);
            }
            counts[0]++;
            int max = d.maxScroll();
            int step = Math.max(20, d.visibleContentHeight() - 10);
            for (int off = 0; ; off += step) {
                d.scrollTo(Math.min(off, max));
                d.rebuild();
                counts[3] += d.content().size();
                Frames.Drawn drawn = Frames.extractFrames(mc, d.screen, off == 0 ? 3 : 1);
                counts[1]++;
                if (drawn.texts() == 0) {
                    c.problem("'" + path + "' drew no text at scroll " + off);
                }
                if (off >= max) {
                    break;
                }
            }
            d.scrollTo(0);
            return true;
        } catch (Throwable t) {
            c.problem("'" + path + "': " + UiCase.describe(t));
            return false;
        }
    }

    static Method buildMethod() {
        try {
            Method m = R.cls(ModScreenDriver.BASE_TAB).getMethod("buildWidgets", int.class, int.class, int.class,
                    Runnable.class);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            throw new AssertionError("[ui] BaseTab.buildWidgets(int,int,int,Runnable) is gone", e);
        }
    }

    // ---- 302 ------------------------------------------------------------------------------------------------

    /**
     * Search: for every leaf tab, typing its name must keep its top-level folder in the sidebar and show that
     * leaf's section header inside it (gui/ModScreen.java:151 responder, gui/tab/FolderTab.java:57 filter). Plus a
     * query nothing matches, which must show the "No tabs match" line and build no content.
     */
    static void search(UiCase c) {
        int[] n = new int[3];
        c.onClient(mc -> {
            ModScreenDriver d;
            try {
                d = new ModScreenDriver(mc);
            } catch (Throwable t) {
                c.problem("could not open gui.ModScreen: " + UiCase.describe(t));
                return null;
            }
            List<ModScreenDriver.Node> tree = d.tree();
            try {
                for (int i = 0; i < tree.size(); i++) {
                    ModScreenDriver.Node topNode = tree.get(i);
                    for (ModScreenDriver.Node child : topNode.children()) {
                        String q = child.name();
                        n[0]++;
                        d.select(i);
                        d.search(q);
                        List<Object> visible = d.visibleTabs();
                        if (!visible.contains(topNode.tab())) {
                            c.problem("search '" + q + "' hid its own folder '" + topNode.name() + "'");
                            continue;
                        }
                        // the responder may have moved the selection to another matching folder; look at ours
                        R.setStatic(d.screenCls, "selectedTab", i);
                        d.rebuild();
                        boolean header = false;
                        for (AbstractWidget w : d.content()) {
                            String l = ModScreenDriver.label(w);
                            if (l.contains(q)) {
                                header = true;
                                break;
                            }
                        }
                        if (!header) {
                            c.problem("search '" + q + "': folder '" + topNode.name()
                                    + "' is listed but shows no row named '" + q + "'");
                        } else {
                            n[1]++;
                        }
                        Frames.extract(mc, d.screen, -1, -1);
                    }
                }
                String nothing = "zz-no-tab-has-this-qx";
                d.search(nothing);
                List<Object> visible = d.visibleTabs();
                if (!visible.isEmpty()) {
                    c.problem("search '" + nothing + "' still lists " + visible.size() + " tab(s)");
                }
                if (!d.content().isEmpty()) {
                    c.problem("search '" + nothing + "' still built " + d.content().size() + " content rows");
                }
                Frames.Drawn drawn = Frames.extract(mc, d.screen, -1, -1);
                n[2] = drawn.texts();
            } catch (Throwable t) {
                c.problem("search threw: " + UiCase.describe(t));
            } finally {
                d.search("");
            }
            return null;
        });
        c.note("searched " + n[0] + " sub-tab names, " + n[1] + " found their row; no-match frame drew "
                + n[2] + " text(s)");
        c.check(n[0] > 50, "only " + n[0] + " sub-tab names searched - the tree is not what it should be");
    }

    // ---- 303 ------------------------------------------------------------------------------------------------

    /**
     * Every two-state toggle in every folder section, pressed twice with a real click: the first press must change
     * its label (it acted), the second must bring every label in the section back (it is a toggle and nothing else
     * moved). Both presses happen inside one client task, so no feature ticks while a toggle is flipped. Deny-listed
     * labels are never pressed.
     */
    static void toggles(UiCase c, Deny deny) {
        int[] n = new int[4]; // pressed, acted, restored, denied
        List<String> deniedLabels = new ArrayList<>();
        List<String> silent = new ArrayList<>();
        int topCount = c.onClient(mc -> {
            try {
                return new ModScreenDriver(mc).topTabs().size();
            } catch (Throwable t) {
                return -1;
            }
        });
        c.check(topCount > 0, "could not open gui.ModScreen");
        for (int i = 0; i < topCount; i++) {
            int top = i;
            c.onClient(mc -> {
                ModScreenDriver d;
                try {
                    d = new ModScreenDriver(mc);
                } catch (Throwable t) {
                    c.problem("could not open gui.ModScreen: " + UiCase.describe(t));
                    return null;
                }
                Object topTab = d.topTabs().get(top);
                int sections = d.isFolder(topTab) ? d.subTabs(topTab).size() : 1;
                for (int j = 0; j < sections; j++) {
                    String where = (String) R.get(topTab, "name");
                    try {
                        d.select(top);
                        if (d.isFolder(topTab)) {
                            d.expanded(topTab).add(j);
                            Object sub = d.subTabs(topTab).get(j);
                            d.expandAll(sub);
                            where += " > " + R.get(sub, "name");
                        }
                        d.rebuild();
                        toggleSection(c, d, where, deny, n, deniedLabels, silent);
                    } catch (Throwable t) {
                        c.problem("'" + where + "': " + UiCase.describe(t));
                    }
                }
                d.select(0);
                return null;
            });
        }
        c.note("toggles pressed " + n[0] + " (twice each): " + n[1] + " changed their label, " + n[2]
                + " sections fully restored; " + n[3] + " deny-listed and not pressed");
        if (!silent.isEmpty()) {
            c.note("toggles whose label did not change on the first press (rebuild-style or inert): " + silent);
        }
        if (!deniedLabels.isEmpty()) {
            c.note("deny-listed toggles: " + deniedLabels);
        }
        c.check(n[0] > 100, "only " + n[0] + " toggles pressed - the sweep did not reach the menu");
        c.check(n[1] > n[0] / 2, "only " + n[1] + " of " + n[0] + " toggles changed their label - presses not landing");
    }

    private static void toggleSection(UiCase c, ModScreenDriver d, String where, Deny deny, int[] n,
                                      List<String> deniedLabels, List<String> silent) throws Throwable {
        List<String> seen = new ArrayList<>();
        for (int guard = 0; guard < 200; guard++) {
            AbstractWidget target = null;
            for (AbstractWidget w : d.content()) {
                String l = ModScreenDriver.label(w);
                if (d.isSettingsButton(w) && TOGGLE.matcher(l).matches() && !seen.contains(nameOf(l))) {
                    target = w;
                    break;
                }
            }
            if (target == null) {
                return;
            }
            String label = ModScreenDriver.label(target);
            seen.add(nameOf(label));
            String why = deny.button(label);
            if (why != null) {
                n[3]++;
                deniedLabels.add(where + " / " + label);
                continue;
            }
            List<String> before = labels(d);
            try {
                ModScreenDriver.press(target);
                boolean acted = !ModScreenDriver.label(target).equals(label);
                d.rebuild();
                List<String> mid = labels(d);
                if (!acted) {
                    acted = !mid.equals(before);
                }
                // the second press: on the rebuilt widget of the same name if the tab rebuilt, else the same one
                AbstractWidget again = findToggle(d, nameOf(label));
                ModScreenDriver.press(again != null ? again : target);
                d.rebuild();
                n[0]++;
                if (acted) {
                    n[1]++;
                } else {
                    silent.add(where + " / " + label);
                }
                List<String> after = labels(d);
                if (after.equals(before)) {
                    n[2]++;
                } else if (acted) {
                    c.problem("'" + where + "' toggle '" + label + "' pressed twice did not restore the section: "
                            + diff(before, after));
                }
            } catch (Throwable t) {
                c.problem("'" + where + "' toggle '" + label + "': " + UiCase.describe(t));
                d.rebuild();
            }
        }
    }

    private static AbstractWidget findToggle(ModScreenDriver d, String name) {
        for (AbstractWidget w : d.content()) {
            String l = ModScreenDriver.label(w);
            if (d.isSettingsButton(w) && TOGGLE.matcher(l).matches() && nameOf(l).equals(name)) {
                return w;
            }
        }
        return null;
    }

    private static String nameOf(String toggleLabel) {
        var m = TOGGLE.matcher(toggleLabel);
        return m.matches() ? m.group(1) : toggleLabel;
    }

    private static List<String> labels(ModScreenDriver d) {
        List<String> out = new ArrayList<>();
        for (AbstractWidget w : d.content()) {
            out.add(ModScreenDriver.label(w));
        }
        return out;
    }

    private static String diff(List<String> before, List<String> after) {
        List<String> gone = new ArrayList<>(before);
        gone.removeAll(after);
        List<String> added = new ArrayList<>(after);
        added.removeAll(before);
        return "before-only " + gone + ", after-only " + added;
    }
}
