package dev.testkit.gametest.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Drives the mod's real {@code gui.ModScreen} through its own private state: which top-level tab is selected
 * ({@code static selectedTab}), which accordion sections of each {@code FolderTab} are open ({@code expanded}), the
 * scroll offset, the search text - then calls the screen's own {@code rebuild()} and reads back what it built
 * ({@code contentPane.children}). Nothing here builds widgets itself; it only flips the switches a click would flip.
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

    /** Select top-level tab {@code index}, scroll to the top, and close every section of every folder. */
    void select(int index) {
        R.setStatic(screenCls, "selectedTab", index);
        R.setStatic(screenCls, "scrollOffset", 0);
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

    void rebuild() throws Throwable {
        R.call0(screen, "rebuild");
    }

    void scrollTo(int offset) {
        R.setStatic(screenCls, "scrollOffset", offset);
    }

    int maxScroll() {
        return (Integer) R.get(screen, "maxScroll");
    }

    int visibleContentHeight() {
        return (Integer) R.get(screen, "visibleContentHeight");
    }

    /** What the selected tab built (the rows inside the scrolling pane); empty when nothing matched a search. */
    @SuppressWarnings("unchecked")
    List<AbstractWidget> content() {
        Object pane = R.get(screen, "contentPane");
        if (pane == null) {
            return List.of();
        }
        return new ArrayList<>((Collection<AbstractWidget>) R.get(pane, "children"));
    }

    @SuppressWarnings("unchecked")
    List<Object> visibleTabs() throws Throwable {
        return (List<Object>) R.call0(screen, "visibleTabs");
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

    /** A real left click on the widget's centre, through its own mouseClicked (active/visible/bounds checks). */
    static boolean press(AbstractWidget w) {
        double x = w.getX() + w.getWidth() / 2.0;
        double y = w.getY() + Math.min(w.getHeight() / 2.0, 5);
        boolean took = w.mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0)), false);
        w.mouseReleased(new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0)));
        return took;
    }

    /** Put the search text in the real search field (its responder rebuilds, exactly as typing does). */
    void search(String text) {
        Object field = R.get(screen, "searchField");
        ((net.minecraft.client.gui.components.EditBox) field).setValue(text);
    }
}
