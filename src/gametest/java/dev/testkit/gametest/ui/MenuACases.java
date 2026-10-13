package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.Report;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 940-949: the Option A mod menu (mod menu-a, killer560 2026-10-12) - a sidebar of categories with on/total counts, a
 * grid of feature cards with their master switch, a settings drawer per feature, Favourites, and the Ctrl F search.
 * Everything is driven through the real {@code gui.ModScreen}: its public navigation methods, and real mouse presses
 * at the bounds it reports.
 * <ul>
 *   <li>940 screenshots at 1920x1080 GUI 2 (Auto Scale on, as he plays): HUD with the Health and Mana Bars drawer, the
 *       Dungeons grid, Home, the Ctrl F search, the bottom of Dungeons (cheat block on a cheat jar).</li>
 *   <li>941 every feature card has a description, and its switch is its tab's own ON/OFF row; a real click on a card's
 *       switch flips the feature's CONFIG (Health and Mana Bars' Stat Bars, Quiver Display) and the category count by
 *       one, and a second click puts it back.</li>
 *   <li>942 a real click on a card body opens THAT feature's drawer with exactly the rows the tab builds; Escape closes the
 *       drawer, a second Escape the menu.</li>
 *   <li>943 the star on a card saves the feature in Favourites; after a restart (config re-read from disk, tab list and
 *       screen rebuilt) Favourites still lists it; unstarring removes it from the file.</li>
 *   <li>944 Ctrl F focuses the search (Ctrl K no longer does, and a plain f typed in it is a letter); typing "etable" lists the Experimentation Table features and a setting; Enter
 *       opens the first hit's drawer; "bar width" finds the Health and Mana Bars setting and opening it scrolls the
 *       drawer to that row; a hit's switch toggles the feature in place.</li>
 *   <li>945 the sidebar shows no on/total counts (killer560, 2026-10-12: "HUD 7/12" becomes "HUD"): every row's drawn
 *       text is its name (Favourites keeps its number of stars).</li>
 *   <li>946 the menu theme: a real click on the header's theme button steps Amber - Dark - Light (right click back); each
 *       preset changes the DRAWN colours (the panel ring pixel is the palette's, the three differ, Light's sidebar is
 *       light) and the choice survives a restart.</li>
 *   <li>947 Custom: the Menu Theme drawer's Accent button opens the colour picker, its answer switches the menu to Custom
 *       and the drawn ring follows the new accent; it survives a restart; Reset puts the preset's colours back.</li>
 * </ul>
 */
final class MenuACases {

    static final String SCREEN = "gui.ModScreen";

    private MenuACases() {
    }

    /** A fresh menu on screen at the current window size. Client thread. */
    static Screen open(Minecraft mc) throws Exception {
        Screen s = (Screen) Mod.cls(SCREEN).getConstructor(Screen.class).newInstance((Object) null);
        McCompat.setScreen(mc, s);
        return s;
    }

    @SuppressWarnings("unchecked")
    static List<Object> leaves() {
        return (List<Object>) Mod.staticCall(SCREEN, "allLeaves");
    }

    static Object leaf(String simpleName) {
        for (Object t : leaves()) {
            if (t.getClass().getSimpleName().equals(simpleName)) {
                return t;
            }
        }
        throw new AssertionError("no feature tab " + simpleName + " in the menu");
    }

    static String name(Object tab) {
        return (String) Mod.field(tab, "name");
    }

    static void press(Screen s, double x, double y) {
        net.minecraft.client.input.MouseButtonEvent e = new net.minecraft.client.input.MouseButtonEvent(x, y,
                new net.minecraft.client.input.MouseButtonInfo(0, 0));
        s.mouseClicked(e, false);
        s.mouseReleased(e);
    }

    static void key(Screen s, int key, int mods) {
        s.keyPressed(new net.minecraft.client.input.KeyEvent(key, 0, mods));
    }

    /** Card, switch and star bounds of {@code tab} on the current page, scrolled into view. */
    static int[][] reveal(Screen s, Object tab) {
        Mod.call(s, "revealCard", tab);
        int[][] b = (int[][]) Mod.call(s, "cardBounds", tab);
        if (b == null) {
            throw new AssertionError("no card for " + name(tab) + " on the current page");
        }
        return b;
    }

    /** The tab's master row freshly built off-screen: its ON/OFF state, or null when it has none. */
    static Boolean freshMaster(Object tab) {
        return (Boolean) Mod.staticCall("gui.FeatureCards", "refresh", tab);
    }

    static String masterLabel(Object tab) {
        Runnable none = () -> { };
        Object m = Mod.staticCall("gui.FeatureCards", "master", tab, none);
        return m == null ? null : ((AbstractWidget) m).getMessage().getString();
    }

    static Object category(Object tab) {
        return Mod.staticCall("gui.tab.MenuCategories", "category", tab);
    }

    @SuppressWarnings("unchecked")
    static List<AbstractWidget> paneRows(Screen s) {
        Object pane = Mod.field(s, "contentPane");
        if (pane == null) {
            return List.of();
        }
        return new ArrayList<>((java.util.Collection<AbstractWidget>) Mod.field(pane, "children"));
    }

    // ---- 941 ------------------------------------------------------------------------------------------------------

    static void cards(UiCase c) throws Exception {
        List<String> missing = new ArrayList<>();
        int[] n = new int[2];
        c.onClient(mc -> {
            try {
                open(mc);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            for (Object leaf : leaves()) {
                String desc = (String) Mod.staticCall("gui.FeatureCards", "description", leaf);
                String master = masterLabel(leaf);
                String cat = name(category(leaf));
                n[0]++;
                if (master != null) {
                    n[1]++;
                }
                c.note(cat + " | " + leaf.getClass().getSimpleName() + " | " + name(leaf) + " | switch="
                        + (master == null ? "-" : net.minecraft.ChatFormatting.stripFormatting(master)) + " | " + desc);
                if (desc == null || desc.isBlank()) {
                    missing.add(leaf.getClass().getSimpleName());
                }
            }
            return null;
        });
        c.check(missing.isEmpty(), missing.size() + " card(s) without a description: " + missing);
        c.check(n[0] > 80 && n[1] > 40, "only " + n[0] + " cards, " + n[1] + " with a switch - the menu is not what it should be");

        // Real clicks on two cards' switches: the feature's config flips, the card shows it, a second click restores.
        String[][] probes = {{"HealthAndManaBarsTab", "playerstats.PlayerStatsConfig", "isEnabledRaw"},
                {"QuiverDisplayTab", "quiver.QuiverDisplayConfig", "isEnabled"}};
        for (String[] probe : probes) {
            String why = c.onClient(mc -> {
                try {
                    Screen s = McCompat.screen(mc);
                    Object tab = leaf(probe[0]);
                    Object cat = category(tab);
                    Mod.call(s, "selectCategory", name(cat));
                    boolean before = (Boolean) Mod.get(probe[1], probe[2]);
                    Object drawerBefore = Mod.call(s, "openFeature");
                    int[][] b = reveal(s, tab);
                    press(s, b[1][0] + b[1][2] / 2.0, b[1][1] + b[1][3] / 2.0);
                    boolean after = (Boolean) Mod.get(probe[1], probe[2]);
                    Object shown = Mod.staticCall("gui.FeatureCards", "state", tab);
                    Boolean fresh = freshMaster(tab);
                    if (after == before) {
                        return probe[0] + ": the card switch did not change " + probe[1] + "." + probe[2] + " (" + before + ")";
                    }
                    if (fresh == null || fresh != after) {
                        return probe[0] + ": the tab's own master row reads " + fresh + " after the card switch, config " + after;
                    }
                    if (!Boolean.valueOf(after).equals(shown)) {
                        return probe[0] + ": the card shows " + shown + " after its switch set the config to " + after;
                    }
                    b = reveal(s, tab);
                    press(s, b[1][0] + b[1][2] / 2.0, b[1][1] + b[1][3] / 2.0);
                    boolean back = (Boolean) Mod.get(probe[1], probe[2]);
                    if (back != before) {
                        return probe[0] + ": a second click left the config at " + back + ", started " + before;
                    }
                    if (Mod.call(s, "openFeature") != drawerBefore) {
                        return probe[0] + ": a switch click changed the drawer";
                    }
                    c.note(probe[0] + ": card switch " + before + " -> " + after + " -> " + back + " (card showed " + shown + ")");
                    return null;
                } catch (Exception e) {
                    return probe[0] + ": " + UiCase.describe(e);
                }
            });
            if (why != null) {
                c.problem(why);
            }
        }
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            return null;
        });
    }

    // ---- 942 ------------------------------------------------------------------------------------------------------

    static void drawer(UiCase c) throws Exception {
        List<String> ids = new ArrayList<>(List.of("LiveMapTab", "SecretWaypointsTab", "QuizSolverTab",
                "HealthAndManaBarsTab", "TranslateTab"));
        if (Mod.isCheat()) {
            ids.add("AutoRoutesTab");
        }
        for (String id : ids) {
            String why = c.onClient(mc -> {
                try {
                    Screen s = open(mc);
                    Object tab = leaf(id);
                    Mod.call(s, "selectCategory", name(category(tab)));
                    if (Mod.call(s, "openFeature") != null) {
                        Mod.call(s, "closeDrawer");
                    }
                    int[][] b = reveal(s, tab);
                    // the card body, clear of the switch (top right) and the star (bottom left)
                    press(s, b[0][0] + b[0][2] / 2.0, b[0][1] + b[0][3] / 2.0);
                    Object open = Mod.call(s, "openFeature");
                    if (open != tab) {
                        return id + ": clicking its card opened " + (open == null ? "nothing" : name(open));
                    }
                    List<String> shown = new ArrayList<>();
                    for (AbstractWidget w : paneRows(s)) {
                        shown.add(ModScreenDriver.label(w));
                    }
                    List<String> built = new ArrayList<>();
                    Runnable none = () -> { };
                    for (Object w : (List<?>) Mod.call(tab, "buildWidgets", 0, 0, 300, none)) {
                        built.add(ModScreenDriver.label((AbstractWidget) w));
                    }
                    if (Mod.has("testing.TestingMenu") && !shown.isEmpty()) {
                        shown.remove(0); // the testing build's Mark tested row
                    }
                    if (!new java.util.HashSet<>(shown).equals(new java.util.HashSet<>(built))) {
                        return id + ": the drawer shows " + shown + " but the tab builds " + built;
                    }
                    key(s, InputConstants.KEY_ESCAPE, 0);
                    if (Mod.call(s, "openFeature") != null || McCompat.screen(mc) != s) {
                        return id + ": Escape did not close only the drawer (open " + Mod.call(s, "openFeature") + ", screen "
                                + McCompat.screen(mc) + ")";
                    }
                    key(s, InputConstants.KEY_ESCAPE, 0);
                    if (McCompat.screen(mc) == s) {
                        return id + ": a second Escape did not close the menu";
                    }
                    c.note(id + ": its card opened its drawer, " + shown.size()
                            + " rows = the tab's own; Escape twice closed the drawer, then the menu");
                    return null;
                } catch (Exception e) {
                    return id + ": " + UiCase.describe(e);
                }
            });
            if (why != null) {
                c.problem(why);
            }
        }
    }

    // ---- 943 ------------------------------------------------------------------------------------------------------

    static void favourites(UiCase c) throws Exception {
        String id = "QuiverDisplayTab";
        String why = c.onClient(mc -> {
            try {
                Object cfg = Mod.cfg("gui.MenuConfig");
                Mod.call(cfg, "setFavourites", new ArrayList<String>());
                Mod.call(cfg, "save");
                Screen s = open(mc);
                Object tab = leaf(id);
                Mod.call(s, "selectCategory", name(category(tab)));
                int[][] b = reveal(s, tab);
                press(s, b[2][0] + b[2][2] / 2.0, b[2][1] + b[2][3] / 2.0);
                Path file = (Path) Mod.staticCall("gui.MenuConfig", "path");
                String text = java.nio.file.Files.readString(file);
                if (!text.contains("\"" + id + "\"")) {
                    return "the star did not save " + id + " (file: " + text.replace('\n', ' ') + ")";
                }
                if (Mod.call(s, "openFeature") != null) {
                    return "the star click also opened a drawer";
                }
                // restart: the config read back from disk, the tab list and the screen rebuilt
                McCompat.setScreen(mc, null);
                Mod.staticCall("gui.MenuConfig", "load");
                Mod.setField(Mod.ROOT + SCREEN, "tabs", null);
                Screen fresh = open(mc);
                Mod.call(fresh, "selectCategory", "Favourites");
                List<?> cards = (List<?>) Mod.call(fresh, "cards");
                if (cards.size() != 1 || !cards.get(0).getClass().getSimpleName().equals(id)) {
                    return "after a restart Favourites shows " + cards;
                }
                Object again = leaf(id);
                Mod.call(fresh, "selectCategory", name(category(again)));
                b = reveal(fresh, again);
                press(fresh, b[2][0] + b[2][2] / 2.0, b[2][1] + b[2][3] / 2.0);
                text = java.nio.file.Files.readString(file);
                if (text.contains("\"" + id + "\"")) {
                    return "unstarring left " + id + " in the file";
                }
                Mod.call(fresh, "selectCategory", "Favourites");
                if (!((List<?>) Mod.call(fresh, "cards")).isEmpty()) {
                    return "Favourites still shows a card after unstarring";
                }
                c.note("starred " + id + " by its card; it survived a restart (" + file.getFileName()
                        + " re-read, tabs and screen rebuilt); unstarred cleanly");
                return null;
            } catch (Exception e) {
                return UiCase.describe(e);
            } finally {
                McCompat.setScreen(mc, null);
            }
        });
        if (why != null) {
            c.problem(why);
        }
    }

    // ---- 944 ------------------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static List<Object> results(Screen s) {
        return (List<Object>) Mod.call(s, "searchResults");
    }

    static void type(Screen s, String text) {
        for (char ch : text.toCharArray()) {
            s.charTyped(new net.minecraft.client.input.CharacterEvent(ch));
        }
    }

    static String tabId(Object result) {
        return Mod.call(result, "tab").getClass().getSimpleName();
    }

    static void search(UiCase c) throws Exception {
        Boolean[] statsWas = {null};
        Boolean[] advWas = {null};
        String why = c.onClient(mc -> {
            try {
                Screen s = open(mc);
                // Ctrl F is the shortcut since mod menu-profiles-ctrlf (killer560, 2026-10-13); Ctrl K no longer is.
                EditBox box = (EditBox) Mod.field(s, "searchField");
                key(s, InputConstants.KEY_K, 2 /* GLFW_MOD_CONTROL */);
                if (box.isFocused()) {
                    return "Ctrl K still focuses the search box";
                }
                key(s, InputConstants.KEY_F, 2 /* GLFW_MOD_CONTROL */);
                if (!box.isFocused()) {
                    return "Ctrl F did not focus the search box";
                }
                // a plain F typed in the focused box is a letter, not the shortcut
                key(s, InputConstants.KEY_F, 0);
                type(s, "f");
                if (!"f".equals(box.getValue()) || !box.isFocused()) {
                    return "typing f in the focused search box gave '" + box.getValue() + "' (focused " + box.isFocused() + ")";
                }
                key(s, InputConstants.KEY_BACKSPACE, 0);
                if (!box.getValue().isEmpty()) {
                    return "Backspace left '" + box.getValue() + "' in the search box";
                }
                c.note("Ctrl K: search not focused; Ctrl F: focused; a plain f typed into it as a letter");
                type(s, "etable");
                if (!"etable".equals(box.getValue())) {
                    return "typing reached the box as '" + box.getValue() + "'";
                }
                List<Object> hits = results(s);
                List<String> features = new ArrayList<>();
                int settings = 0;
                for (Object r : hits) {
                    if ((Boolean) Mod.call(r, "feature")) {
                        features.add(tabId(r));
                    } else {
                        settings++;
                    }
                }
                List<String> want = new ArrayList<>(List.of("ExperimentsTab"));
                if (Mod.isCheat()) {
                    want.add("AutoExperimentsTab");
                }
                if (!features.containsAll(want) || settings == 0) {
                    return "'etable' found features " + features + " and " + settings + " setting(s); wanted " + want
                            + " and a setting";
                }
                Object first = Mod.call(hits.get(0), "tab");
                key(s, InputConstants.KEY_RETURN, 0);
                if (Mod.call(s, "openFeature") != first) {
                    return "Enter on 'etable' opened " + Mod.call(s, "openFeature") + ", wanted " + name(first);
                }
                if (!box.getValue().isEmpty()) {
                    return "the search kept its text after Enter";
                }
                c.note("etable: features " + features + ", " + settings + " setting(s); Enter opened " + name(first));

                // a setting hit (it sits in Health and Mana Bars' Advanced section, shown while Stat Bars is on)
                Object stats = Mod.cfg("playerstats.PlayerStatsConfig");
                statsWas[0] = (Boolean) Mod.call(stats, "isEnabledRaw");
                advWas[0] = (Boolean) Mod.call(stats, "isMenuAdvancedOpen");
                Mod.call(stats, "setEnabled", true);
                Mod.call(stats, "setMenuAdvancedOpen", false);
                key(s, InputConstants.KEY_F, 2);
                type(s, "bar width");
                Object hit = null;
                for (Object r : results(s)) {
                    if (!(Boolean) Mod.call(r, "feature") && tabId(r).equals("HealthAndManaBarsTab")) {
                        hit = r;
                        break;
                    }
                }
                if (hit == null) {
                    return "'bar width' found no Health and Mana Bars setting";
                }
                String label = (String) Mod.call(hit, "label");
                int at = results(s).indexOf(hit);
                for (int i = 0; i < at; i++) {
                    key(s, InputConstants.KEY_DOWN, 0);
                }
                key(s, InputConstants.KEY_RETURN, 0);
                Object open = Mod.call(s, "openFeature");
                if (open == null || !open.getClass().getSimpleName().equals("HealthAndManaBarsTab")) {
                    return "Enter on the 'bar width' hit opened " + open;
                }
                AbstractWidget row = null;
                for (AbstractWidget w : paneRows(s)) {
                    if (ModScreenDriver.label(w).toLowerCase(java.util.Locale.ROOT).contains("bar width")) {
                        row = w;
                    }
                }
                int top = (Integer) Mod.field(s, "contentY");
                int height = (Integer) Mod.field(s, "visibleContentHeight");
                if (row == null || row.getY() < top || row.getY() + row.getHeight() > top + height) {
                    return "the drawer did not bring the '" + label + "' row into view (" + (row == null ? "no row"
                            : "y " + row.getY()) + ", pane " + top + ".." + (top + height) + ")";
                }
                c.note("'bar width': hit '" + label + "' opened Health and Mana Bars with the row in view at y " + row.getY());

                // a hit's switch toggles the feature in place
                key(s, InputConstants.KEY_F, 2);
                type(s, "quiver display");
                Object q = null;
                for (Object r : results(s)) {
                    if ((Boolean) Mod.call(r, "feature") && tabId(r).equals("QuiverDisplayTab")) {
                        q = r;
                    }
                }
                if (q == null) {
                    return "'quiver display' found no feature hit";
                }
                boolean before = (Boolean) Mod.get("quiver.QuiverDisplayConfig", "isEnabled");
                Object openBefore = Mod.call(s, "openFeature");
                Mod.call(s, "layoutPalette");
                int palX = (Integer) Mod.field(s, "palX");
                int palW = (Integer) Mod.field(s, "palW");
                int rowsY = (Integer) Mod.field(s, "palRowsY");
                int idx = results(s).indexOf(q) - (Integer) Mod.field(s, "paletteScroll");
                press(s, palX + palW - 8 - 10, rowsY + idx * 16 + 8);
                boolean after = (Boolean) Mod.get("quiver.QuiverDisplayConfig", "isEnabled");
                if (after == before) {
                    return "the switch on the 'quiver display' hit did not toggle it";
                }
                if (Mod.call(s, "openFeature") != openBefore) {
                    return "the hit's switch changed the drawer instead of toggling in place";
                }
                Mod.call(Mod.cfg("quiver.QuiverDisplayConfig"), "setEnabled", before);
                c.note("'quiver display' hit switch: " + before + " -> " + after + ", drawer unchanged");
                return null;
            } catch (Exception e) {
                return UiCase.describe(e);
            } finally {
                McCompat.setScreen(mc, null);
                Object stats = Mod.cfg("playerstats.PlayerStatsConfig");
                if (statsWas[0] != null) {
                    Mod.call(stats, "setEnabled", statsWas[0]);
                    Mod.call(stats, "setMenuAdvancedOpen", advWas[0]);
                    Mod.call(stats, "save");
                }
            }
        });
        if (why != null) {
            c.problem(why);
        }
    }

    // ---- 945 ------------------------------------------------------------------------------------------------------

    static void noCounts(UiCase c) throws Exception {
        String why = c.onClient(mc -> {
            try {
                Screen s = open(mc);
                @SuppressWarnings("unchecked")
                List<String> rows = (List<String>) Mod.call(s, "sideTexts");
                c.note("sidebar rows as drawn: " + rows);
                if (rows.size() < 9) {
                    return "only " + rows.size() + " sidebar rows - the check would be vacuous";
                }
                for (String r : rows) {
                    if (r.matches(".*\\d+\\s*/\\s*\\d+.*")) {
                        return "the sidebar still draws a count: '" + r + "'";
                    }
                }
                for (String want : new String[]{"Home", "Dungeons", "HUD", "Chat", "Items", "Social", "Cosmetics", "Misc"}) {
                    if (!rows.contains(want)) {
                        return "no plain '" + want + "' row in " + rows;
                    }
                }
                return null;
            } catch (Exception e) {
                return UiCase.describe(e);
            } finally {
                McCompat.setScreen(mc, null);
            }
        });
        if (why != null) {
            c.problem(why);
        }
    }

    // ---- 946 / 947 ----------------------------------------------------------------------------------------------

    /** Takes a screenshot of the menu and returns {ring ARGB, sidebar ARGB} at the panel ring's top centre and in the
     *  sidebar's empty lower part (screen pixels per layout unit from the open screen's own width). */
    static int[] sample(UiCase c, Screen s, String label) throws Exception {
        c.ticks(3);
        Path shot = c.ctx().takeScreenshot(Report.fileName(c.name() + "-" + label));
        Report.screenshot(c.name() + "-" + label, shot);
        java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(shot.toFile());
        double sc = img.getWidth() / (double) s.width;
        int px = (Integer) Mod.field(s, "panelX");
        int py = (Integer) Mod.field(s, "panelY");
        int pw = (Integer) Mod.field(s, "panelW");
        int sx = (Integer) Mod.field(s, "sideX");
        int sy = (Integer) Mod.field(s, "sideY");
        int sw = (Integer) Mod.field(s, "sideW");
        int sh = (Integer) Mod.field(s, "sideH");
        int ring = img.getRGB((int) ((px + pw / 2 + 0.5) * sc), (int) ((py - 1 + 0.5) * sc));
        int side = img.getRGB((int) ((sx + sw / 2 + 0.5) * sc), (int) ((sy + sh - 12 + 0.5) * sc));
        return new int[]{ring | 0xFF000000, side | 0xFF000000};
    }

    static boolean near(int a, int b) {
        for (int sh = 0; sh < 24; sh += 8) {
            if (Math.abs(((a >> sh) & 0xFF) - ((b >> sh) & 0xFF)) > 3) {
                return false;
            }
        }
        return true;
    }

    static float lum(int c) {
        return (0.299f * ((c >> 16) & 0xFF) + 0.587f * ((c >> 8) & 0xFF) + 0.114f * (c & 0xFF)) / 255f;
    }

    static String hex(int c) {
        return String.format("#%06X", c & 0xFFFFFF);
    }

    static String themeName() {
        return ((Enum<?>) Mod.call(Mod.cfg("gui.MenuConfig"), "getTheme")).name();
    }

    static void themes(UiCase c) throws Exception {
        Object cfg = Mod.cfg("gui.MenuConfig");
        String old = themeName();
        try {
            Screen s = c.onClient(mc -> {
                Mod.call(Mod.cfg("gui.MenuConfig"), "setTheme", Mod.enumValue("gui.MenuTheme", "AMBER"));
                try {
                    return open(mc);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            java.util.Map<String, int[]> seen = new java.util.LinkedHashMap<>();
            String[] order = {"AMBER", "DARK", "LIGHT"};
            for (int i = 0; i < order.length; i++) {
                if (i > 0) {
                    // a real left click on the header's theme button
                    c.onClient(mc -> {
                        int[] b = (int[]) Mod.call(s, "iconBounds", 3);
                        press(s, b[0] + b[2] / 2.0, b[1] + b[3] / 2.0);
                        return null;
                    });
                }
                String now = themeName();
                c.check(now.equals(order[i]), "after " + i + " click(s) the theme is " + now + ", wanted " + order[i]);
                int[] px = sample(c, s, order[i].toLowerCase(java.util.Locale.ROOT));
                int want = (Integer) Mod.field("gui.MenuStyle", "FRAME_RING");
                c.note(order[i] + ": ring drawn " + hex(px[0]) + " (palette " + hex(want) + "), sidebar " + hex(px[1]));
                c.check(near(px[0], want), order[i] + ": the panel ring is drawn " + hex(px[0]) + ", the palette says " + hex(want));
                seen.put(order[i], px);
            }
            c.check(!near(seen.get("AMBER")[0], seen.get("DARK")[0]) && !near(seen.get("DARK")[0], seen.get("LIGHT")[0])
                    && !near(seen.get("AMBER")[0], seen.get("LIGHT")[0]), "two presets draw the same ring colour");
            c.check(lum(seen.get("LIGHT")[1]) > 0.6f && lum(seen.get("AMBER")[1]) < 0.25f && lum(seen.get("DARK")[1]) < 0.25f,
                    "Light's sidebar is not light, or a dark preset's is not dark");
            // right click goes back
            c.onClient(mc -> {
                int[] b = (int[]) Mod.call(s, "iconBounds", 3);
                net.minecraft.client.input.MouseButtonEvent e = new net.minecraft.client.input.MouseButtonEvent(
                        b[0] + b[2] / 2.0, b[1] + b[3] / 2.0, new net.minecraft.client.input.MouseButtonInfo(1, 0));
                s.mouseClicked(e, false);
                return null;
            });
            c.check(themeName().equals("DARK"), "a right click on the theme button went to " + themeName() + ", not Dark");
            // restart: the file read back, a new screen
            Screen fresh = c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                Mod.staticCall("gui.MenuConfig", "load");
                try {
                    return open(mc);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            c.check(themeName().equals("DARK"), "after a restart the theme is " + themeName() + ", not Dark");
            int[] px = sample(c, fresh, "dark-after-restart");
            c.check(near(px[0], seen.get("DARK")[0]), "after a restart the ring is drawn " + hex(px[0]) + ", Dark drew "
                    + hex(seen.get("DARK")[0]));
            c.note("Dark survived a restart: ring " + hex(px[0]));
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                Mod.call(Mod.cfg("gui.MenuConfig"), "setTheme", Mod.enumValue("gui.MenuTheme", old));
                Mod.call(Mod.cfg("gui.MenuConfig"), "save");
                return null;
            });
        }
    }

    static void customColour(UiCase c) throws Exception {
        String old = themeName();
        int teal = 0xFF2EC4B6;
        try {
            Screen s = c.onClient(mc -> {
                Object cfg = Mod.cfg("gui.MenuConfig");
                Mod.call(cfg, "setTheme", Mod.enumValue("gui.MenuTheme", "AMBER"));
                Mod.call(cfg, "resetCustom");
                try {
                    Screen screen = open(mc);
                    Mod.call(screen, "openFeature", leaf("MenuThemeTab"));
                    return screen;
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            int[] amber = sample(c, s, "amber");
            // a real press on the drawer's Accent row: the colour picker opens; its answer is what a pick sends
            String why = c.onClient(mc -> {
                AbstractWidget accent = null;
                for (AbstractWidget w : paneRows(s)) {
                    if (ModScreenDriver.label(w).startsWith("Accent:")) {
                        accent = w;
                    }
                }
                if (accent == null) {
                    return "no Accent row in the Menu Theme drawer";
                }
                ModScreenDriver.press(accent);
                Screen picker = McCompat.screen(mc);
                if (picker == null || !picker.getClass().getSimpleName().equals("ColorPickerScreen")) {
                    return "the Accent row opened " + picker + ", not the colour picker";
                }
                ((java.util.function.IntConsumer) Mod.field(picker, "onChange")).accept(teal);
                picker.onClose();
                return McCompat.screen(mc) == s ? null : "closing the picker did not return to the menu";
            });
            if (why != null) {
                c.problem(why);
                return;
            }
            c.check(themeName().equals("CUSTOM"), "picking an accent left the theme on " + themeName());
            int[] custom = sample(c, s, "custom-teal");
            int want = (Integer) Mod.field("gui.MenuStyle", "FRAME_RING");
            int accentNow = (Integer) Mod.field("gui.MenuStyle", "OR");
            c.note("custom accent " + hex(accentNow) + ": ring " + hex(custom[0]) + " (palette " + hex(want) + "), Amber's was "
                    + hex(amber[0]));
            c.check((accentNow & 0xFFFFFF) == (teal & 0xFFFFFF), "the palette's accent is " + hex(accentNow) + ", not the picked "
                    + hex(teal));
            c.check(near(custom[0], want) && !near(custom[0], amber[0]), "the ring did not follow the custom accent");
            // restart keeps it
            c.onClient(mc -> {
                Mod.staticCall("gui.MenuConfig", "load");
                return null;
            });
            int saved = (Integer) Mod.call(Mod.cfg("gui.MenuConfig"), "getCustomColour",
                    Mod.enumValue("gui.MenuTheme$Slot", "ACCENT"));
            c.check(themeName().equals("CUSTOM") && (saved & 0xFFFFFF) == (teal & 0xFFFFFF),
                    "after a restart the theme is " + themeName() + " with accent " + hex(saved));
            // Reset puts Amber's colours back (a real press on the Reset row)
            c.onClient(mc -> {
                Mod.call(s, "refresh");
                for (AbstractWidget w : paneRows(s)) {
                    if (ModScreenDriver.label(w).startsWith("Reset Custom to")) {
                        ModScreenDriver.press(w);
                    }
                }
                return null;
            });
            int[] reset = sample(c, s, "custom-reset");
            c.check(near(reset[0], amber[0]), "after Reset the ring is " + hex(reset[0]) + ", Amber's is " + hex(amber[0]));
            c.note("Reset: ring back to " + hex(reset[0]));
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                Object cfg = Mod.cfg("gui.MenuConfig");
                Mod.call(cfg, "resetCustom");
                Mod.call(cfg, "setTheme", Mod.enumValue("gui.MenuTheme", old));
                Mod.call(cfg, "save");
                return null;
            });
        }
    }

    // ---- 940 ------------------------------------------------------------------------------------------------------

    static void shots(UiCase c) throws Exception {
        String variant = Mod.isCheat() ? "cheat" : "legit";
        int[] oldWindow = c.onClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight()});
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        Object hud = Mod.cfg("hud.HudConfig");
        boolean oldAuto = (Boolean) Mod.call(hud, "isAutoScale");
        List<String> taken = new ArrayList<>();
        // A livelier frame, like the mockup: a few HUD features on and four favourites; all put back in finally.
        String[] lively = {"HealthAndManaBarsTab", "QuiverDisplayTab", "AbilityCooldownTab", "CustomScoreboardTab",
                "LagDisplayTab"};
        List<Object> flipped = new ArrayList<>();
        Object menuCfg = Mod.cfg("gui.MenuConfig");
        String oldTheme = ((Enum<?>) Mod.call(menuCfg, "getTheme")).name();
        @SuppressWarnings("unchecked")
        List<String> oldFavs = (List<String>) Mod.call(menuCfg, "getFavourites");
        try {
            c.onClient(mc -> {
                for (String id : lively) {
                    Object t = leaf(id);
                    if (Boolean.FALSE.equals(freshMaster(t))) {
                        Runnable none = () -> { };
                        Mod.staticCall("gui.FeatureCards", "toggle", t, none);
                        flipped.add(t);
                    }
                }
                Mod.call(menuCfg, "setFavourites", new ArrayList<>(List.of("HealthAndManaBarsTab", "LiveMapTab",
                        "QuiverDisplayTab", "ChunkCacheTab")));
                Mod.call(menuCfg, "setTheme", Mod.enumValue("gui.MenuTheme", "AMBER"));
                return null;
            });
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                Mod.call(hud, "setAutoScale", true);
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
            Screen[] s = new Screen[1];
            c.onClient(mc -> {
                try {
                    s[0] = open(mc);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
                return null;
            });
            c.ticks(3);

            // 1: HUD with the Health and Mana Bars drawer open (the mockup's frame)
            c.onClient(mc -> {
                Mod.call(s[0], "selectCategory", "HUD");
                Mod.call(s[0], "openFeature", leaf("HealthAndManaBarsTab"));
                return null;
            });
            taken.add(shot(c, s[0], variant + "-1-hud-bars-drawer", "HealthAndManaBarsTab"));

            // 2: Dungeons, no drawer
            c.onClient(mc -> {
                Mod.call(s[0], "selectCategory", "Dungeons");
                Mod.call(s[0], "closeDrawer");
                return null;
            });
            taken.add(shot(c, s[0], variant + "-2-dungeons", null));

            // 3: Home
            c.onClient(mc -> {
                Mod.call(s[0], "selectCategory", "Home");
                return null;
            });
            taken.add(shot(c, s[0], variant + "-3-home", null));

            // 4: Ctrl F search with "etable" typed
            c.onClient(mc -> {
                Mod.call(s[0], "selectCategory", "HUD");
                Mod.call(s[0], "focusSearch");
                ((net.minecraft.client.gui.components.EditBox) Mod.field(s[0], "searchField")).setValue("etable");
                return null;
            });
            taken.add(shot(c, s[0], variant + "-4-search", null));

            // 5: the bottom of Dungeons (the red Cheat block on a cheat jar)
            c.onClient(mc -> {
                ((net.minecraft.client.gui.components.EditBox) Mod.field(s[0], "searchField")).setValue("");
                Mod.call(s[0], "selectCategory", "Dungeons");
                Mod.setField(Mod.ROOT + SCREEN, "gridScroll", 100000);
                Mod.call(s[0], "refresh");
                return null;
            });
            taken.add(shot(c, s[0], variant + "-5-dungeons-bottom", null));

            // 6-9: the HUD drawer in every colour preset and one Custom set (teal on blue-grey)
            int[] customSet = {0xFF2EC4B6, 0xFF101820, 0xFF1B2733, 0xFFE8F1F2, 0xFF2EC4B6};
            for (String theme : new String[]{"AMBER", "DARK", "LIGHT", "CUSTOM"}) {
                c.onClient(mc -> {
                    Object cfg = Mod.cfg("gui.MenuConfig");
                    Mod.setField(Mod.ROOT + SCREEN, "gridScroll", 0);
                    if (theme.equals("CUSTOM")) {
                        Object[] slots = Mod.cls("gui.MenuTheme$Slot").getEnumConstants();
                        for (int k = 0; k < slots.length; k++) {
                            Mod.call(cfg, "setCustomColour", slots[k], customSet[k]);
                        }
                    } else {
                        Mod.call(cfg, "setTheme", Mod.enumValue("gui.MenuTheme", theme));
                    }
                    Mod.call(s[0], "selectCategory", "HUD");
                    Mod.call(s[0], "openFeature", leaf("HealthAndManaBarsTab"));
                    return null;
                });
                taken.add(shot(c, s[0], variant + "-theme-" + theme.toLowerCase(java.util.Locale.ROOT), "HealthAndManaBarsTab"));
            }
            c.note("shots: " + taken);
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                for (Object t : flipped) {
                    Runnable none = () -> { };
                    Mod.staticCall("gui.FeatureCards", "toggle", t, none);
                }
                Mod.call(menuCfg, "setFavourites", oldFavs);
                Mod.call(menuCfg, "resetCustom");
                Mod.call(menuCfg, "setTheme", Mod.enumValue("gui.MenuTheme", oldTheme));
                Mod.call(menuCfg, "save");
                Mod.call(hud, "setAutoScale", oldAuto);
                mc.options.guiScale().set(oldGui);
                return null;
            });
            c.ctx().getInput().resizeWindow(oldWindow[0], oldWindow[1]);
            c.ticks(3);
            c.onClient(mc -> {
                mc.resizeGui();
                return null;
            });
        }
    }

    /** Asserts the menu is up (and the drawer shows {@code drawer}, when given), then takes and keeps a screenshot. */
    static String shot(UiCase c, Screen screen, String label, String drawer) throws Exception {
        c.ticks(4);
        String why = c.onClient(mc -> {
            if (McCompat.screen(mc) != screen) {
                return "the mod menu is not on screen (" + McCompat.screen(mc) + ")";
            }
            if (drawer != null) {
                Object open = Mod.call(screen, "openFeature");
                if (open == null || !open.getClass().getSimpleName().equals(drawer)) {
                    return "the drawer shows " + (open == null ? "nothing" : open.getClass().getSimpleName())
                            + ", wanted " + drawer;
                }
            }
            return null;
        });
        if (why != null) {
            c.problem(label + ": " + why);
            return label + " (not taken)";
        }
        String shot = c.name() + "-" + label;
        Path taken = c.ctx().takeScreenshot(Report.fileName(shot));
        Path kept = Report.screenshot(shot, taken);
        c.check(kept != null, "no screenshot for " + shot);
        return String.valueOf(kept);
    }
}
