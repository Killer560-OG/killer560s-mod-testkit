package dev.testkit.gametest.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

import dev.testkit.gametest.mod.Mod;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Drives the mod's real {@code gui.ModScreen} through its own private state: which top-level tab is selected
 * ({@code static selectedTab}), which feature's drawer is open ({@code static openFeature}), the scroll offset, the
 * search text - then calls the screen's own {@code rebuild()} and reads back what it built ({@code contentPane.children}).
 * Nothing here builds widgets itself; it only flips the switches a click would flip.
 *
 * <p>Since the Option A menu (mod menu-a, 2026-10-12) a category is a grid of feature cards and a feature's settings
 * are in its drawer, one feature at a time. The cases written for the accordion menu still say what they want opened
 * the old way - a section index in the folder's {@code expanded} set, {@link #expandAll} for a group - and
 * {@link #rebuild()} turns that into the new navigation: one opened feature is its real drawer; several (a whole
 * folder, the Secrets group, Home's pinned sections) are each opened in their real drawer in turn and their rows
 * stacked one after another into {@link #content()} (each re-scoped to its tab for tooltips), with the scroll applied
 * across the stack. A row's own press works the same on any of them; a real-mouse press through the screen only reaches
 * the drawer open last, so a real-input case opens one feature at a time ({@link #open}).
 *
 * <p>Source read for this (mod 8c43a6d): gui/ModScreen.java (tabs, selectedTab, searchQuery, scrollOffset, maxScroll,
 * visibleContentHeight, contentPane, rebuild, visibleTabs), gui/tab/FolderTab.java (subTabs, expanded, pinFirst),
 * gui/tab/BaseTab.java (name), gui/tab/CollapsibleSection.java (the "▶ Title" header rows). Client thread only.
 */
final class ModScreenDriver {

    static final String MOD_SCREEN = "gui.ModScreen";
    static final String FOLDER_TAB = "gui.tab.FolderTab";
    static final String BASE_TAB = "gui.tab.BaseTab";
    static final String SETTINGS_BUTTON = "gui.SettingsButtonWidget";
    static final String CLOSED = "▶ ";
    static final String OPEN = "▼ ";

    final Class<?> screenCls;
    final Class<?> folderCls;
    final Class<?> settingsButtonCls;
    final Screen screen;

    /** A tab with its path from the top ("Dungeon > Secrets > Secret Sound"). */
    record Node(Object tab, String path, int depth, boolean folder, List<Node> children) {
        String name() {
            return (String) R.get(tab, "name");
        }
    }

    ModScreenDriver(Minecraft mc) throws Exception {
        screenCls = R.cls(MOD_SCREEN);
        folderCls = R.cls(FOLDER_TAB);
        settingsButtonCls = R.cls(SETTINGS_BUTTON);
        R.setStatic(screenCls, "searchQuery", "");
        R.setStatic(screenCls, "scrollOffset", 0);
        R.setStatic(screenCls, "selectedTab", 0);
        R.setStatic(screenCls, "openFeature", null);
        R.setStatic(screenCls, "favouritesSelected", false);
        screen = (Screen) screenCls.getConstructor(Screen.class).newInstance((Object) null);
        screen.init(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
    }

    @SuppressWarnings("unchecked")
    List<Object> topTabs() {
        return (List<Object>) R.getStatic(screenCls, "tabs");
    }

    boolean isFolder(Object tab) {
        return folderCls.isInstance(tab);
    }

    @SuppressWarnings("unchecked")
    List<Object> subTabs(Object folder) {
        return (List<Object>) R.get(folder, "subTabs");
    }

    @SuppressWarnings("unchecked")
    Set<Integer> expanded(Object folder) {
        return (Set<Integer>) R.get(folder, "expanded");
    }

    /** The whole tab tree, every folder expanded into its children. */
    List<Node> tree() {
        List<Node> out = new ArrayList<>();
        for (Object t : topTabs()) {
            out.add(node(t, "", 0));
        }
        return out;
    }

    private Node node(Object tab, String parentPath, int depth) {
        String name = (String) R.get(tab, "name");
        String path = parentPath.isEmpty() ? name : parentPath + " > " + name;
        List<Node> kids = new ArrayList<>();
        boolean folder = isFolder(tab);
        if (folder) {
            for (Object sub : subTabs(tab)) {
                kids.add(node(sub, path, depth + 1));
            }
        }
        return new Node(tab, path, depth, folder, kids);
    }

    static int count(List<Node> nodes) {
        int n = 0;
        for (Node node : nodes) {
            n += 1 + count(node.children());
        }
        return n;
    }

    static void flatten(List<Node> nodes, List<Node> out) {
        for (Node node : nodes) {
            out.add(node);
            flatten(node.children(), out);
        }
    }

    /** Select top-level tab {@code index}, scroll to the top, close the drawer and every section of every folder. */
    void select(int index) {
        R.setStatic(screenCls, "selectedTab", index);
        R.setStatic(screenCls, "favouritesSelected", false);
        R.setStatic(screenCls, "openFeature", null);
        R.setStatic(screenCls, "scrollOffset", 0);
        R.setStatic(screenCls, "gridScroll", 0);
        merged = null;
        keepDrawer = false;
        for (Object t : topTabs()) {
            collapseAll(t);
        }
    }

    void collapseAll(Object tab) {
        if (isFolder(tab)) {
            expanded(tab).clear();
            for (Object sub : subTabs(tab)) {
                collapseAll(sub);
            }
        }
    }

    /** Open every section of {@code tab} and of every folder inside it. */
    void expandAll(Object tab) {
        if (isFolder(tab)) {
            List<Object> subs = subTabs(tab);
            for (int i = 0; i < subs.size(); i++) {
                expanded(tab).add(i);
                expandAll(subs.get(i));
            }
        }
    }

    /** Rows of several drawers stacked (see the class doc), or null when one drawer or a page is what is shown. */
    private List<AbstractWidget> merged;
    private int mergedMax;
    private int mergedVisible;

    /** Opens {@code leaf}'s drawer through the screen's own navigation (its category selected) and rebuilds. Later
     *  {@link #rebuild()} calls keep it open until {@link #select} or another {@code open}. */
    void open(Object leaf) {
        for (Object t : topTabs()) {
            collapseAll(t);
        }
        merged = null;
        R.setStatic(screenCls, "scrollOffset", 0);
        Mod.call(screen, "openFeature", leaf);
        keepDrawer = true;
    }

    /** Opens the feature an old-style chain of (folder, section index) steps names - the last step's section - in its
     *  drawer, so a screen put up next shows it (the Option A menu ignores the old {@code expanded} sets). */
    void openChain(List<Object[]> chain) {
        if (chain.isEmpty()) {
            return;
        }
        Object[] last = chain.get(chain.size() - 1);
        Object target = subTabs(last[0]).get((Integer) last[1]);
        if (!isFolder(target)) {
            open(target);
        }
    }

    /** The menu's sidebar rows as laid out: {x, y, w, h, tab index (-1 = Favorites)} (gui/ModScreen.layout). */
    static List<int[]> sideRows(net.minecraft.client.gui.screens.Screen s) {
        List<int[]> out = new ArrayList<>();
        for (Object e : (List<?>) R.get(s, "side")) {
            out.add(new int[]{(Integer) Mod.call(e, "x"), (Integer) Mod.call(e, "y"), (Integer) Mod.call(e, "w"),
                    (Integer) Mod.call(e, "h"), (Integer) Mod.call(e, "tab")});
        }
        return out;
    }

    /** The sidebar row of top-level tab {@code tab}, or null. */
    static int[] sideRow(net.minecraft.client.gui.screens.Screen s, int tab) {
        for (int[] r : sideRows(s)) {
            if (r[4] == tab) {
                return r;
            }
        }
        return null;
    }

    /** Every feature tab of the menu (leaves of categories and groups, Home's sections, top-level pages). */
    @SuppressWarnings("unchecked")
    List<Object> leaves() {
        return (List<Object>) Mod.staticCall("gui.ModScreen", "allLeaves");
    }

    /** The feature tab whose class is {@code simpleName}, or null. */
    Object leaf(String simpleName) {
        for (Object t : leaves()) {
            if (t.getClass().getSimpleName().equals(simpleName)) {
                return t;
            }
        }
        return null;
    }

    private int pinned(Object folder) {
        Object p = R.get(folder, "pinnedCount");
        return p == null ? 0 : (Integer) p;
    }

    /** The features the old-style switches ask to see under the selected category, in order. */
    List<Object> openedLeaves() {
        int sel = (Integer) R.getStatic(screenCls, "selectedTab");
        Object top = topTabs().get(sel);
        List<Object> out = new ArrayList<>();
        if (!isFolder(top)) {
            return out;
        }
        collectOpened(top, out);
        return out;
    }

    private void collectOpened(Object folder, List<Object> out) {
        List<Object> subs = subTabs(folder);
        java.util.TreeSet<Integer> open = new java.util.TreeSet<>(expanded(folder));
        // Pinned sections (Home's) count only when nothing was asked for by name: a case that opens one section of
        // Home gets that section's drawer alone, so a real click lands on it.
        if (open.isEmpty()) {
            for (int i = 0; i < Math.min(pinned(folder), subs.size()); i++) {
                open.add(i);
            }
        }
        for (int i : open) {
            if (i < 0 || i >= subs.size()) {
                continue;
            }
            Object sub = subs.get(i);
            if (isFolder(sub)) {
                collectOpened(sub, out);
            } else {
                out.add(sub);
            }
        }
    }

    /** Set by {@link #open}: rebuilds keep that drawer instead of reading the old-style switches. */
    private boolean keepDrawer;

    void rebuild() throws Throwable {
        merged = null;
        int sel = (Integer) R.getStatic(screenCls, "selectedTab");
        Object top = topTabs().get(sel);
        List<Object> opened = openedLeaves();
        if (keepDrawer && opened.isEmpty()) {
            R.call0(screen, "rebuild");
            return;
        }
        keepDrawer = false;
        R.setStatic(screenCls, "favouritesSelected", false);
        if (!isFolder(top) || opened.isEmpty()) {
            if (isFolder(top)) {
                R.setStatic(screenCls, "openFeature", null);
            }
            R.call0(screen, "rebuild");
            return;
        }
        if (opened.size() == 1) {
            R.setStatic(screenCls, "openFeature", opened.get(0));
            R.call0(screen, "rebuild");
            return;
        }
        int scroll = (Integer) R.getStatic(screenCls, "scrollOffset");
        List<AbstractWidget> all = new ArrayList<>();
        java.util.Map<AbstractWidget, String> scopes = new java.util.IdentityHashMap<>();
        int y = 0;
        int top0 = -1;
        for (Object leaf : opened) {
            R.setStatic(screenCls, "openFeature", leaf);
            R.setStatic(screenCls, "scrollOffset", 0);
            R.call0(screen, "rebuild");
            List<AbstractWidget> part = paneChildren();
            if (part.isEmpty()) {
                continue;
            }
            int contentY = (Integer) R.get(screen, "contentY");
            if (top0 < 0) {
                top0 = contentY;
            }
            int min = Integer.MAX_VALUE;
            int max = Integer.MIN_VALUE;
            for (AbstractWidget w : part) {
                min = Math.min(min, w.getY());
                max = Math.max(max, w.getY() + w.getHeight());
            }
            for (AbstractWidget w : part) {
                w.setY(w.getY() - min + top0 + y);
                scopes.put(w, (String) R.get(leaf, "name"));
            }
            y += (max - min) + 12;
            all.addAll(part);
        }
        mergedVisible = (Integer) R.get(screen, "visibleContentHeight");
        mergedMax = Math.max(0, y - mergedVisible);
        scroll = Math.max(0, Math.min(scroll, mergedMax));
        for (AbstractWidget w : all) {
            w.setY(w.getY() - scroll);
        }
        // the screen's rebuilds cleared every scope but the last drawer's: put them back
        for (java.util.Map.Entry<AbstractWidget, String> e : scopes.entrySet()) {
            Mod.staticCall("gui.SettingTooltips", "scope", e.getKey(), e.getValue());
        }
        R.setStatic(screenCls, "scrollOffset", scroll);
        merged = all;
    }

    void scrollTo(int offset) {
        R.setStatic(screenCls, "scrollOffset", offset);
    }

    int maxScroll() {
        return merged != null ? mergedMax : (Integer) R.get(screen, "maxScroll");
    }

    int visibleContentHeight() {
        return merged != null ? mergedVisible : (Integer) R.get(screen, "visibleContentHeight");
    }

    @SuppressWarnings("unchecked")
    private List<AbstractWidget> paneChildren() {
        Object pane = R.get(screen, "contentPane");
        if (pane == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>((Collection<AbstractWidget>) R.get(pane, "children"));
    }

    /** What the open drawer (or page) built - or, for several opened features, all their rows stacked (class doc). */
    List<AbstractWidget> content() {
        if (merged != null) {
            return new ArrayList<>(merged);
        }
        return paneChildren();
    }

    @SuppressWarnings("unchecked")
    List<Object> visibleTabs() throws Throwable {
        return (List<Object>) R.call0(screen, "visibleTabs");
    }

    /**
     * The tooltips the old accordion's section header rows ("▶ Name", no sub-tab scope) carried, as the Option A menu
     * shows them now: hovering each card (or group title) of {@code top} shows {@code gui.ModScreen.cardTooltip}.
     * label key -> tooltip ("" = none), for the tooltip cases' "(unscoped)" rows.
     */
    Map<String, String> cardTips(Node top) {
        Map<String, String> out = new java.util.TreeMap<>();
        List<Node> flat = new ArrayList<>();
        flatten(top.children(), flat);
        for (Node n : flat) {
            String k = (String) Mod.staticCall("gui.SettingTooltips", "key", n.name());
            String tip = (String) Mod.staticCall("gui.ModScreen", "cardTooltip", n.tab());
            if (!k.isEmpty()) {
                out.putIfAbsent(k, tip == null ? "" : tip);
            }
        }
        return out;
    }

    static String label(AbstractWidget w) {
        String s = w.getMessage().getString();
        String stripped = net.minecraft.ChatFormatting.stripFormatting(s);
        return stripped == null ? s : stripped;
    }

    boolean isSettingsButton(AbstractWidget w) {
        return settingsButtonCls.isInstance(w);
    }

    /**
     * Open every {@code CollapsibleSection} in the current content (a SettingsButtonWidget whose label starts
     * "▶ "), one at a time, each followed by the tab's own rebuild. Bounded; returns how many it opened.
     * Labels on the deny list are never pressed.
     */
    int openCollapsibles(Deny deny, List<String> denied) throws Throwable {
        Set<String> tried = new HashSet<>();
        int opened = 0;
        for (int round = 0; round < 40; round++) {
            AbstractWidget next = null;
            for (AbstractWidget w : content()) {
                String l = label(w);
                if (isSettingsButton(w) && l.startsWith(CLOSED) && !tried.contains(l)) {
                    next = w;
                    break;
                }
            }
            if (next == null) {
                break;
            }
            String l = label(next);
            tried.add(l);
            String why = deny.button(l);
            if (why != null) {
                denied.add(l + " (" + why + ")");
                continue;
            }
            if (press(next)) {
                opened++;
            }
            rebuild();
        }
        return opened;
    }

    /** A real left click on the widget's center, through its own mouseClicked (active/visible/bounds checks). */
    static boolean press(AbstractWidget w) {
        double x = w.getX() + w.getWidth() / 2.0;
        double y = w.getY() + Math.min(w.getHeight() / 2.0, 5);
        int[] pill = forwardPill(w);
        if (pill != null) {
            // A cycling row in the mod menu steps only from its value pill (mod doc-menu, 2026-10-13): forward is the
            // pill's right half.
            x = pill[0] + pill[2] * 0.75;
            y = pill[1] + pill[3] / 2.0;
        }
        boolean took = w.mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0)), false);
        w.mouseReleased(new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0)));
        return took;
    }

    /** The value pill {x, y, w, h} of a cycling SettingsButtonWidget drawn as a menu row, or null for anything else. */
    static int[] forwardPill(AbstractWidget w) {
        try {
            var isCycling = w.getClass().getMethod("isCycling");
            if (!Boolean.TRUE.equals(isCycling.invoke(w))) {
                return null;
            }
            Object parts = w.getClass().getMethod("parts").invoke(w);
            Object look = parts.getClass().getMethod("look").invoke(parts);
            Object open = dev.testkit.compat.McCompat.screen(net.minecraft.client.Minecraft.getInstance());
            if (!"PILL".equals(String.valueOf(look)) || open == null || !R.cls(MOD_SCREEN).isInstance(open)) {
                return null;
            }
            return (int[]) w.getClass().getMethod("controlBounds").invoke(w);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** Put the search text in the real search field (its responder rebuilds, exactly as typing does). */
    void search(String text) {
        Object field = R.get(screen, "searchField");
        ((net.minecraft.client.gui.components.EditBox) field).setValue(text);
    }
}
