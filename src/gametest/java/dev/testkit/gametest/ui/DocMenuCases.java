package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 980-989: killer560's 2026-10-13 menu and settings list (mod doc-menu). Each case drives the real {@code gui.ModScreen}
 * and proves the change ACTED, not just that it compiled:
 * <ul>
 *   <li>980 Home: no Profiles or Changelog quick button, no Favourites box (the Favourites page still lists a starred
 *       card), no Menu Theme card, and the Menu Theme row's Customise... still opens its drawer. Screenshot.</li>
 *   <li>981 Mod & HUD: Check for Updates, Join Discord, Edit HUD Positions and Edit Bars are gone; Equal Spacing's switch
 *       lines up with Snapping's on the right; the card has no switch. Screenshot.</li>
 *   <li>982 Auto Update's switch: it is the card's switch; a real click on it turns the updater off (its request asks
 *       for Channel Off, which the updater refuses before any network), it survives a reload from disk, the Channel row
 *       hides while off; a file saved with the old Off channel loads as the switch off.</li>
 *   <li>983 the nine new master switches: each is its card's switch, a real click turns it off and the getters the
 *       feature code reads go false with every sub-switch on; DVD stops moving, Cringe sends nothing, Solver Highlights
 *       returns before the renderer.</li>
 *   <li>984 Bold Name: a real click on the row; your name preview is bold, the shared name carries &amp;l, the relay's
 *       own rules accept it, and it survives a reload.</li>
 *   <li>985 HUD Scale live: a press and drags on the slider (no release) change the HUD's scale AND relay the menu out
 *       mid-drag, the drag carrying on in the rebuilt slider.</li>
 *   <li>986 an open drawer survives closing the menu: Escape closes the menu, a new menu opens on the same drawer and
 *       scroll; the drawer's X still closes the drawer.</li>
 *   <li>987 a cycling row steps only from its pill: a click on the label does nothing, the right half goes forward, the
 *       left half back, a right click back.</li>
 *   <li>988 the search bar's calculator: implicit products next to brackets, every older expression unchanged.</li>
 *   <li>989 saved themes: Save as New Theme twice, rename one by typing in its box, Use, a reload from disk keeps names,
 *       colours and the one in use, Delete takes two clicks and is saved.</li>
 * </ul>
 */
final class DocMenuCases {

    private DocMenuCases() {
    }

    private static Screen openMenu(Minecraft mc) {
        try {
            return MenuACases.open(mc);
        } catch (Exception e) {
            throw new AssertionError("could not open gui.ModScreen: " + UiCase.describe(e), e);
        }
    }

    private static String plain(AbstractWidget w) {
        return ModScreenDriver.label(w);
    }

    private static AbstractWidget row(Screen s, String prefix) {
        for (AbstractWidget w : MenuACases.paneRows(s)) {
            if (plain(w).startsWith(prefix)) {
                return w;
            }
        }
        return null;
    }

    private static List<String> rowLabels(Screen s) {
        List<String> out = new ArrayList<>();
        for (AbstractWidget w : MenuACases.paneRows(s)) {
            out.add(plain(w));
        }
        return out;
    }

    private static int[] controlBounds(AbstractWidget w) {
        return (int[]) Mod.call(w, "controlBounds");
    }

    private static void click(Screen s, double x, double y, int button) {
        MouseButtonEvent e = new MouseButtonEvent(x, y, new MouseButtonInfo(button, 0));
        s.mouseClicked(e, false);
        s.mouseReleased(e);
    }

    /** Opens {@code tabId}'s drawer in its category on a fresh menu. */
    private static Screen drawer(Minecraft mc, String tabId) {
        Screen s = openMenu(mc);
        Object tab = MenuACases.leaf(tabId);
        Mod.call(s, "openFeature", tab);
        return s;
    }

    /** A config file's bytes (null when it does not exist), to put back after a case that saves through the menu. */
    private static byte[] snapshot(Path p) throws Exception {
        return Files.exists(p) ? Files.readAllBytes(p) : null;
    }

    private static void restore(Path p, byte[] bytes) throws Exception {
        if (bytes == null) {
            Files.deleteIfExists(p);
        } else {
            Files.write(p, bytes);
        }
    }

    private static Path configPath(String cls, String field) {
        return (Path) Mod.field(cls, field);
    }

    /** 1920x1080 at GUI 2 with Auto Scale on, as he plays; returns what to put back. */
    private static Object[] bigWindow(UiCase c) {
        int[] oldWindow = c.onClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight()});
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        Object hud = Mod.cfg("hud.HudConfig");
        boolean oldAuto = (Boolean) Mod.call(hud, "isAutoScale");
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
        return new Object[]{oldWindow, oldGui, oldAuto};
    }

    private static void restoreWindow(UiCase c, Object[] old) {
        int[] oldWindow = (int[]) old[0];
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            Mod.call(Mod.cfg("hud.HudConfig"), "setAutoScale", (Boolean) old[2]);
            mc.options.guiScale().set((Integer) old[1]);
            return null;
        });
        c.ctx().getInput().resizeWindow(oldWindow[0], oldWindow[1]);
        c.ticks(3);
        c.onClient(mc -> {
            mc.resizeGui();
            return null;
        });
    }

    // ---- 980 -------------------------------------------------------------------------------------------------------

    /** Home's Menu Theme row (killer560, 2026-10-22: the two editing buttons were squeezed beside the themes and cut
     *  off): at every width the grid can take, from narrower than the 320-wide window's grid to 1000, the theme chips are
     *  no smaller than when they shared the row with the editing buttons; Customise... and Main Menu sit below the theme
     *  row, inside the box, no narrower than a theme chip, never overlapping, and every label they can show fits uncut.
     *  Null when it holds. */
    /** What the last {@link #themeRowWhy} measured, for the case's note. */
    static String themeRowNote = "";

    static String themeRowWhy(Minecraft mc, Object block, int n) {
        int widestStacked = -1;
        int narrowestWide = -1;
        String[] labels = {"Customize...", "Main Menu: Fancy", "Main Menu: Simple"};
        for (String l : labels) {
            if (!l.startsWith("Customize")) {
                // the scene labels the mod can show
                boolean known = false;
                for (Object m : Mod.cls("mainmenu.MainMenuThemeConfig$SceneMode").getEnumConstants()) {
                    known |= l.equals("Main Menu: " + Mod.field(m, "label"));
                }
                if (!known) {
                    return "the scene modes changed; '" + l + "' is not one of them";
                }
            }
        }
        for (int w = 150; w <= 1000; w++) {
            int h = (Integer) Mod.call(block, "height", w);
            int oldCw = Math.min(90, (w - 20 - (n + 1) * 6) / (n + 2));
            int[] last = null;
            for (int i = 0; i < n; i++) {
                int[] b = (int[]) Mod.call(block, "chip", i, 0, 0, w);
                if (b[2] < oldCw) {
                    return "at width " + w + " theme chip " + i + " is " + b[2] + " wide, narrower than the old " + oldCw;
                }
                if (last != null && b[0] < last[0] + last[2]) {
                    return "at width " + w + " theme chips " + (i - 1) + " and " + i + " overlap";
                }
                last = b;
            }
            int themeBottom = last[1] + last[3];
            if ((Boolean) Mod.call(block, "stacked", w)) {
                widestStacked = w;
            } else if (narrowestWide < 0) {
                narrowestWide = w;
            }
            int[] cz = (int[]) Mod.call(block, "chip", n, 0, 0, w);
            int[] mz = (int[]) Mod.call(block, "chip", n + 1, 0, 0, w);
            for (int[] b : new int[][]{cz, mz}) {
                if (b[1] < themeBottom + 2) {
                    return "at width " + w + " an editing button " + java.util.Arrays.toString(b) + " is on the theme row";
                }
                if (b[2] < last[2]) {
                    return "at width " + w + " an editing button is " + b[2] + " wide, a theme chip " + last[2];
                }
                if (b[0] < 0 || b[0] + b[2] > w || b[1] + b[3] > h - 2) {
                    return "at width " + w + " an editing button " + java.util.Arrays.toString(b) + " leaves the box ("
                            + w + "x" + h + ")";
                }
            }
            boolean apart = cz[1] + cz[3] <= mz[1] || mz[1] + mz[3] <= cz[1] || cz[0] + cz[2] <= mz[0]
                    || mz[0] + mz[2] <= cz[0];
            if (!apart) {
                return "at width " + w + " Customize... " + java.util.Arrays.toString(cz) + " overlaps Main Menu "
                        + java.util.Arrays.toString(mz);
            }
            if (mc.font.width("Customize...") > cz[2] - 6) {
                return "at width " + w + " 'Customize...' is cut in a " + cz[2] + "-wide button";
            }
            for (int k = 1; k < labels.length; k++) {
                if (mc.font.width(labels[k]) > mz[2] - 6) {
                    return "at width " + w + " '" + labels[k] + "' is cut in a " + mz[2] + "-wide button";
                }
            }
        }
        themeRowNote = "Menu Theme row measured at widths 150-1000: editing buttons stacked up to " + widestStacked
                + ", side by side from " + narrowestWide;
        return null;
    }

    static void home(UiCase c) throws Exception {
        String variant = Mod.isCheat() ? "cheat" : "legit";
        Object menuCfg = Mod.cfg("gui.MenuConfig");
        @SuppressWarnings("unchecked")
        List<String> oldFavs = (List<String>) Mod.call(menuCfg, "getFavourites");
        Object[] old = bigWindow(c);
        try {
            Screen[] s = new Screen[1];
            String why = c.onClient(mc -> {
                Mod.call(menuCfg, "setFavourites", new ArrayList<>(List.of("QuiverDisplayTab")));
                s[0] = openMenu(mc);
                Mod.call(s[0], "selectCategory", "Home");
                for (String gone : new String[]{"Profiles", "Changelog", "Reboot"}) {
                    if (Mod.call(s[0], "homeButtonBounds", gone) != null) {
                        return "Home still has a " + gone + " button";
                    }
                }
                List<String> kept = new ArrayList<>();
                for (String b : new String[]{"Edit HUD", "Edit Bars", "Check Updates", "Discord", "GitHub", "Features Doc"}) {
                    if (Mod.call(s[0], "homeButtonBounds", b) == null) {
                        return "Home lost its " + b + " button";
                    }
                    kept.add(b);
                }
                List<String> blocks = new ArrayList<>();
                Object themeItem = null;
                for (Object it : (List<?>) Mod.field(s[0], "items")) {
                    Object block = Mod.field(it, "block");
                    if (block != null) {
                        blocks.add(block.getClass().getSimpleName());
                        if (block.getClass().getSimpleName().equals("ThemeBlock")) {
                            themeItem = it;
                        }
                    }
                }
                if (blocks.contains("FavouritesBlock") || !blocks.contains("HeroBlock") || themeItem == null) {
                    return "Home's boxes are " + blocks + " (wanted HeroBlock and ThemeBlock, no FavouritesBlock)";
                }
                List<String> cards = new ArrayList<>();
                for (Object t : (List<?>) Mod.call(s[0], "cards")) {
                    cards.add(t.getClass().getSimpleName());
                }
                if (cards.contains("MenuThemeTab")) {
                    return "Home still has a Menu Theme card: " + cards;
                }
                // Customise... (the chip after the presets) still opens the Menu Theme drawer, by a real click.
                int n = Mod.cls("gui.MenuTheme").getEnumConstants().length;
                int gridScroll = (Integer) Mod.field(Mod.ROOT + "gui.ModScreen", "gridScroll");
                int[] chip = (int[]) Mod.call(Mod.field(themeItem, "block"), "chip", n, (Integer) Mod.field(themeItem, "x"),
                        (Integer) Mod.field(themeItem, "y") - gridScroll, (Integer) Mod.field(themeItem, "w"));
                MenuACases.press(s[0], chip[0] + chip[2] / 2.0, chip[1] + chip[3] / 2.0);
                Object open = Mod.call(s[0], "openFeature");
                if (open == null || !open.getClass().getSimpleName().equals("MenuThemeTab")) {
                    return "Customize... opened " + (open == null ? "nothing" : open.getClass().getSimpleName());
                }
                Mod.call(s[0], "closeDrawer");
                // killer560, 2026-10-22: Customise... and Main Menu sit full-size on their own row under the themes.
                String rowWhy = themeRowWhy(mc, Mod.field(themeItem, "block"), n);
                if (rowWhy != null) {
                    return rowWhy;
                }
                // (519 clicks the Main Menu button where it now is, through homeSceneChipBounds.)
                // The Favourites PAGE stays.
                if (!(Boolean) Mod.call(s[0], "selectCategory", "Favorites")) {
                    return "the sidebar has no Favorites page";
                }
                List<?> favCards = (List<?>) Mod.call(s[0], "cards");
                if (favCards.size() != 1 || !favCards.get(0).getClass().getSimpleName().equals("QuiverDisplayTab")) {
                    return "the Favorites page shows " + favCards + " with Quiver Display starred";
                }
                Mod.call(s[0], "selectCategory", "Home");
                c.note("Home: quick buttons " + kept + "; boxes " + blocks + "; cards " + cards
                        + "; Customize... opened Menu Theme; the Favorites page lists the starred card; " + themeRowNote);
                return null;
            });
            c.check(why == null, why);
            MenuACases.shot(c, s[0], variant + "-home", null);
        } finally {
            c.onClient(mc -> {
                Mod.call(menuCfg, "setFavourites", oldFavs);
                Mod.call(menuCfg, "save");
                return null;
            });
            restoreWindow(c, old);
        }
    }

    // ---- 519 -------------------------------------------------------------------------------------------------------

    /** killer560's 2026-10-15 Home cleanup: no Reboot button, eight quick buttons in two rows, a version line with no
     *  Minecraft version or build, and the Mod Settings cards in his order (not alphabetical). */
    static void homeCleanup(UiCase c) throws Exception {
        Object[] old = bigWindow(c);
        try {
            Screen[] s = new Screen[1];
            String why = c.onClient(mc -> {
                s[0] = openMenu(mc);
                Mod.call(s[0], "selectCategory", "Home");
                if (Mod.call(s[0], "homeButtonBounds", "Reboot") != null) {
                    return "Home still has a Reboot button";
                }
                @SuppressWarnings("unchecked")
                List<String> labels = (List<String>) Mod.call(s[0], "homeButtonLabels");
                List<String> want = List.of("Edit HUD", "Edit Bars", "Check Updates", "Sweat Mode", "YouTube", "Discord", "GitHub",
                        "Features Doc"); // killer560 2026-10-16: tools on top, links below
                if (!labels.equals(want)) {
                    return "Home's buttons are " + labels + ", wanted " + want;
                }
                java.util.Set<Integer> rows = new java.util.TreeSet<>();
                for (String l : labels) {
                    int[] b = (int[]) Mod.call(s[0], "homeButtonBounds", l);
                    if (b == null) {
                        return "no box for the " + l + " button";
                    }
                    rows.add(b[1]);
                }
                String line = (String) Mod.call(s[0], "versionLine");
                String lower = line.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("minecraft") || lower.contains("build") || !line.startsWith("Version ")
                        || !lower.endsWith(Mod.isCheat() ? "cheat" : "legit")) {
                    return "Home's version line is '" + line + "'";
                }
                List<String> cards = new ArrayList<>();
                for (Object t : (List<?>) Mod.call(s[0], "cards")) {
                    cards.add(t.getClass().getSimpleName());
                }
                List<String> wantCards = new ArrayList<>(List.of("HomeMainTab", "ClickSoundsTab", "DescriptionsTab",
                        "DiscordRpcTab", "AutoUpdateTab"));
                if (Mod.isCheat()) {
                    wantCards.add("CorrectionAlarmTab");
                    wantCards.add("StreamerModeTab"); // mod 23ef23da (2026-10-19): Home's cheat block
                }
                if (!cards.equals(wantCards)) {
                    return "Home's cards are " + cards + ", wanted " + wantCards;
                }
                // The Main Menu scene chip in the Menu Theme row (killer560, 2026-10-16): a click flips Fancy <-> Simple.
                int[] sc = (int[]) Mod.call(s[0], "homeSceneChipBounds");
                if (sc == null) {
                    return "Home has no Main Menu scene chip";
                }
                Object mm = Mod.staticCall("mainmenu.MainMenuThemeConfig", "getInstance");
                String m0 = String.valueOf(Mod.call(mm, "getSceneMode"));
                MenuACases.press(s[0], sc[0] + sc[2] / 2.0, sc[1] + sc[3] / 2.0);
                String m1 = String.valueOf(Mod.call(mm, "getSceneMode"));
                MenuACases.press(s[0], sc[0] + sc[2] / 2.0, sc[1] + sc[3] / 2.0);
                String m2 = String.valueOf(Mod.call(mm, "getSceneMode"));
                if (m0.equals(m1) || !m0.equals(m2)) {
                    return "the scene chip went " + m0 + " -> " + m1 + " -> " + m2 + " (wanted a flip and back)";
                }
                c.note("Home: scene chip " + m0 + " -> " + m1 + " -> " + m2);
                c.note("Home: buttons " + labels + " in " + rows.size() + " rows; version line '" + line + "'; cards " + cards);
                return rows.size() == 2 ? null : "Home's buttons are in " + rows.size() + " rows (wanted a 4x2 grid)";
            });
            c.check(why == null, why);
            MenuACases.shot(c, s[0], (Mod.isCheat() ? "cheat" : "legit") + "-home-cleanup", null);
        } finally {
            restoreWindow(c, old);
        }
    }

    // ---- 981 -------------------------------------------------------------------------------------------------------

    static void modHud(UiCase c) throws Exception {
        String variant = Mod.isCheat() ? "cheat" : "legit";
        Object[] old = bigWindow(c);
        try {
            Screen[] s = new Screen[1];
            String why = c.onClient(mc -> {
                s[0] = drawer(mc, "HomeMainTab");
                List<String> labels = rowLabels(s[0]);
                for (String gone : new String[]{"Check for Updates", "Join Discord", "Edit HUD Positions", "Edit Bars"}) {
                    for (String l : labels) {
                        if (l.startsWith(gone)) {
                            return "Mod & HUD still has '" + l + "'";
                        }
                    }
                }
                AbstractWidget equal = row(s[0], "Equal Spacing");
                AbstractWidget snapping = row(s[0], "Snapping");
                if (equal == null || snapping == null) {
                    return "no Equal Spacing / Snapping row: " + labels;
                }
                int[] e = controlBounds(equal);
                int[] sn = controlBounds(snapping);
                int right = 0;
                for (AbstractWidget w : MenuACases.paneRows(s[0])) {
                    if (w.getClass().getSimpleName().equals("SettingsButtonWidget")) {
                        right = Math.max(right, w.getX() + w.getWidth());
                    }
                }
                if (e[0] + e[2] != sn[0] + sn[2] || equal.getX() + equal.getWidth() != right) {
                    return "Equal Spacing's switch ends at x " + (e[0] + e[2]) + " (row " + equal.getX() + "+"
                            + equal.getWidth() + "), Snapping's at " + (sn[0] + sn[2]) + ", the rows' right edge " + right;
                }
                String master = MenuACases.masterLabel(MenuACases.leaf("HomeMainTab"));
                if (master != null) {
                    return "Mod & HUD's card has a switch: " + master;
                }
                c.note("Mod & HUD rows: " + labels);
                c.note("Equal Spacing's switch ends at x " + (e[0] + e[2]) + ", Snapping's at " + (sn[0] + sn[2])
                        + " (rows end at " + right + "); the card has no switch");
                return null;
            });
            c.check(why == null, why);
            MenuACases.shot(c, s[0], variant + "-mod-and-hud", "HomeMainTab");
        } finally {
            restoreWindow(c, old);
        }
    }

    // ---- 1016 ------------------------------------------------------------------------------------------------------

    /**
     * killer560 (2026-10-14): "add a new setting called sweat mode and have it open google chrome with this link ...
     * Make the setting description click to enter sweat mode. Have it as a toggle that turns on then opens the web page
     * then will turn back off." On the Home tab's Mod & HUD drawer: the row's tooltip, a real click (the harness's
     * no-external-open hook logs the launch instead of opening it), ON right after, OFF again within 30 ticks.
     */
    static void sweatMode(UiCase c) throws Exception {
        String url = "https://www.youtube.com/watch?v=mDUOibsW36w";
        // 2026-10-18 (killer560): Sweat Mode is the button at the top of Home only - no row in the Mod or HUD drawer.
        Screen[] s = new Screen[1];
        String rows = c.onClient(mc -> {
            s[0] = drawer(mc, "HomeMainTab");
            return row(s[0], "Sweat Mode") == null ? null : "Home's drawer still has a Sweat Mode row: " + rowLabels(s[0]);
        });
        c.check(rows == null, rows);
        long mark = dev.testkit.gametest.LogTap.mark();
        int before = c.onClient(mc -> (Integer) Mod.staticCall("util.SweatMode", "timesEntered"));
        c.onClient(mc -> {
            dev.testkit.compat.McCompat.setScreen(mc, null);
            return null;
        });
        // Home's quick buttons (2026-10-16): Edit HUD, Edit Bars, Check Updates, Sweat Mode, then YouTube, Discord, GitHub,
        // Features Doc.
        String home = c.onClient(mc -> {
            Screen h = openMenu(mc);
            Mod.call(h, "selectCategory", "Home");
            int[] yt = (int[]) Mod.call(h, "homeButtonBounds", "YouTube");
            int[] cu = (int[]) Mod.call(h, "homeButtonBounds", "Check Updates");
            int[] sw = (int[]) Mod.call(h, "homeButtonBounds", "Sweat Mode");
            if (yt == null || cu == null || sw == null) {
                return "Home is missing a quick button (YouTube " + (yt != null) + ", Sweat Mode " + (sw != null) + ")";
            }
            int n = (Integer) Mod.staticCall("util.SweatMode", "timesEntered");
            MenuACases.press(h, sw[0] + sw[2] / 2.0, sw[1] + sw[3] / 2.0);
            int m = (Integer) Mod.staticCall("util.SweatMode", "timesEntered");
            dev.testkit.compat.McCompat.setScreen(mc, null);
            String hTip = (String) Mod.staticCall("gui.SettingTooltips", "describeScoped", null, "home button", "Sweat Mode");
            c.note("Home buttons: YouTube at " + yt[0] + "," + yt[1] + ", Check Updates at " + cu[0] + "," + cu[1]
                    + ", Sweat Mode at " + sw[0] + "," + sw[1] + "; Sweat Mode pressed " + n + " -> " + m + "; tooltip " + hTip);
            if (m != n + 1) {
                return "Home's Sweat Mode button did not enter sweat mode";
            }
            if (!"Enters sweat mode.".equals(hTip)) {
                return "Home's Sweat Mode tooltip is " + hTip;
            }
            // 2026-10-16: the tools on the top row, the links (YouTube first) on the second.
            return yt[1] > cu[1] ? null : "YouTube is not on the second row";
        });
        c.check(home == null, home);
        int after = c.onClient(mc -> (Integer) Mod.staticCall("util.SweatMode", "timesEntered"));
        boolean launched = false;
        for (String l : dev.testkit.gametest.LogTap.since(mark)) {
            launched |= l.contains("would open") && l.contains(url);
        }
        c.note("Sweat Mode entered " + before + " -> " + after + ", launch logged " + launched);
        c.check(after == before + 1, "the Home button did not enter Sweat Mode exactly once (" + before + " -> " + after + ")");
        c.check(launched, "no browser launch of " + url + " was logged");
    }

    // ---- 982 -------------------------------------------------------------------------------------------------------

    private static String gate() {
        Object req = Mod.staticCall("autoupdate.AutoUpdateFeature", "currentRequest");
        return (String) Mod.staticCall("autoupdate.AutoUpdater", "blockedReason", req);
    }

    static void autoUpdate(UiCase c) throws Exception {
        String cls = "autoupdate.AutoUpdateConfig";
        Path file = configPath(cls, "CONFIG_PATH");
        byte[] before = snapshot(file);
        try {
            String why = c.onClient(mc -> {
                Object tab = MenuACases.leaf("AutoUpdateTab");
                Object cfg = Mod.cfg(cls);
                Mod.call(cfg, "setEnabled", true);
                Mod.call(cfg, "save");
                String master = MenuACases.masterLabel(tab);
                if (master == null || !net.minecraft.ChatFormatting.stripFormatting(master).equals("Auto Update: ON")) {
                    return "the Auto Update card's switch is " + master;
                }
                String gateOn = gate();
                Screen s = openMenu(mc);
                Mod.call(s, "selectCategory", MenuACases.name(MenuACases.category(tab)));
                int[][] b = MenuACases.reveal(s, tab);
                MenuACases.press(s, b[1][0] + b[1][2] / 2.0, b[1][1] + b[1][3] / 2.0);
                if ((Boolean) Mod.get(cls, "isEnabled")) {
                    return "a real click on the card switch left Auto Update on";
                }
                String channelOff = ((Enum<?>) Mod.get(cls, "effectiveChannel")).name();
                String gateOff = gate();
                boolean testing = Mod.has("testing.TestingMenu");
                if (!"OFF".equals(channelOff) || (!testing && !"Auto Update is Off".equals(gateOff))) {
                    return "switched off, the updater follows " + channelOff + " and its gate says " + gateOff;
                }
                if ("Auto Update is Off".equals(gateOn)) {
                    return "switched on, the gate still said 'Auto Update is Off'";
                }
                Mod.call(s, "openFeature", tab);
                List<String> offRows = rowLabels(s);
                for (String l : offRows) {
                    if (l.startsWith("Channel")) {
                        return "the Channel row shows while Auto Update is off: " + offRows;
                    }
                }
                Mod.staticCall(cls, "load");
                if ((Boolean) Mod.get(cls, "isEnabled")) {
                    return "Auto Update off did not survive a reload from " + file.getFileName();
                }
                // a second real click (the card again) turns it back on; the Channel row comes back
                Mod.call(s, "closeDrawer");
                b = MenuACases.reveal(s, tab);
                MenuACases.press(s, b[1][0] + b[1][2] / 2.0, b[1][1] + b[1][3] / 2.0);
                if (!(Boolean) Mod.get(cls, "isEnabled")) {
                    return "a second click did not turn Auto Update back on";
                }
                Mod.call(s, "openFeature", tab);
                AbstractWidget channel = row(s, "Channel");
                if (channel == null) {
                    return "the Channel row did not come back: " + rowLabels(s);
                }
                c.note("card switch: " + master + "; off -> channel " + channelOff + ", gate '" + gateOff + "'"
                        + (testing ? " (testing build gate first)" : "") + "; on -> gate '" + gateOn + "'; drawer off "
                        + offRows + "; on again shows '" + plain(channel) + "'");
                return null;
            });
            c.check(why == null, why);
            // A file from before the switch: channel OFF -> switch off, channel Full Release.
            String mig = c.onClient(mc -> {
                try {
                    Files.writeString(file, "{\"channel\": \"OFF\"}", StandardCharsets.UTF_8);
                } catch (Exception e) {
                    return "could not write the old file: " + e;
                }
                Mod.staticCall(cls, "load");
                boolean on = (Boolean) Mod.get(cls, "isEnabled");
                String ch = ((Enum<?>) Mod.get(cls, "getChannel")).name();
                if (on || !"FULL_RELEASE".equals(ch)) {
                    return "an old 'channel: OFF' file loads as enabled " + on + ", channel " + ch;
                }
                c.note("old file {channel: OFF} -> switch off, channel FULL_RELEASE");
                return null;
            });
            c.check(mig == null, mig);
        } finally {
            restore(file, before);
            c.onClient(mc -> {
                Mod.staticCall(cls, "load");
                return null;
            });
        }
    }

    // ---- 983 -------------------------------------------------------------------------------------------------------

    /** One master switch: the tab, its config, the master property, the row label, sub-switches put ON (setter names,
     *  or "field:" for a public boolean field) and the getters the feature code reads. */
    private record Master(String tab, String cfg, String prop, String label, String[] subs, String[] reads) {
    }

    private static final Master[] MASTERS = {
            // Dungeon Alerts, Thorn, Maxor's Crystals, P3 Nav and F7 Spots had a master here until 2026-10-15 (killer560:
            // "not always should the first setting dictate whether the mod as a whole is on or off"): their parts are
            // independent now and the card stands for all of them - 518-ui-menu-independent-masters.
            // Solver Highlights had one here until mod 4a7ffc31 (killer560, 2026-10-18: "remove solver highlights and put
            // each of those as an individual option in each of the solvers"): there is no SolverHighlightsTab card any more
            // and SolverEspConfig keeps one switch per solver, so solverDraws checks each solver's own switch instead.
            new Master("ObjectHiderTab", "objecthider.ObjectHiderConfig", "ObjectHiderEnabled", "Object Hider",
                    new String[]{"setHideFairy", "setDisableFog", "setHidePlayers"},
                    new String[]{"isHideFairy", "isDisableFog", "hidesAnyEntity"}),
            new Master("DvdTab", "dvd.DvdConfig", "DvdEnabled", "DVD", new String[]{}, new String[]{"isDvdEnabled"}),
            new Master("CringeTab", "cringe.CringeConfig", "Enabled", "Cringe", new String[]{}, new String[]{"isEnabled"}),
    };

    private static void setSub(Object cfg, String sub) {
        if (sub.startsWith("field:")) {
            try {
                cfg.getClass().getField(sub.substring(6)).setBoolean(cfg, true);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        } else {
            Mod.call(cfg, sub, true);
        }
    }

    private static Map<String, Boolean> reads(Master m) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        Object cfg = Mod.cfg(m.cfg());
        for (String r : m.reads()) {
            out.put(r, (Boolean) Mod.call(cfg, r));
        }
        return out;
    }

    static void masters(UiCase c) throws Exception {
        Map<Path, byte[]> files = new LinkedHashMap<>();
        for (Master m : MASTERS) {
            Path p = configPath(m.cfg(), "CONFIG_PATH");
            files.put(p, snapshot(p));
        }
        Path dvdMaster = configPath("dvd.DvdConfig", "MASTER_PATH");
        files.put(dvdMaster, snapshot(dvdMaster));
        try {
            for (Master m : MASTERS) {
                String why = c.onClient(mc -> {
                    Object cfg = Mod.cfg(m.cfg());
                    Mod.call(cfg, "set" + m.prop(), true);
                    for (String sub : m.subs()) {
                        setSub(cfg, sub);
                    }
                    Mod.call(cfg, "save");
                    Map<String, Boolean> on = reads(m);
                    if (on.containsValue(false)) {
                        return m.tab() + ": with everything on the feature reads " + on;
                    }
                    Object tab = MenuACases.leaf(m.tab());
                    String master = MenuACases.masterLabel(tab);
                    String want = m.label() + ": ON";
                    if (master == null || !want.equals(net.minecraft.ChatFormatting.stripFormatting(master))) {
                        return m.tab() + ": the card's switch is " + master + ", wanted '" + want + "'";
                    }
                    Screen s = openMenu(mc);
                    Mod.call(s, "selectCategory", MenuACases.name(MenuACases.category(tab)));
                    int[][] b = MenuACases.reveal(s, tab);
                    MenuACases.press(s, b[1][0] + b[1][2] / 2.0, b[1][1] + b[1][3] / 2.0);
                    cfg = Mod.cfg(m.cfg());
                    if ((Boolean) Mod.call(cfg, "is" + m.prop())) {
                        return m.tab() + ": a real click on the card switch left it on";
                    }
                    Map<String, Boolean> off = reads(m);
                    if (off.containsValue(true)) {
                        return m.tab() + ": switched off, the feature still reads " + off;
                    }
                    Mod.call(s, "openFeature", tab);
                    List<String> rows = new ArrayList<>(rowLabels(s));
                    rows.removeIf(l -> l.startsWith("Mark ") || l.contains("tested"));
                    if (rows.size() != 1 || !rows.get(0).startsWith(m.label() + ": OFF")) {
                        return m.tab() + ": switched off, its drawer shows " + rows;
                    }
                    Mod.call(s, "closeDrawer");
                    b = MenuACases.reveal(s, tab);
                    MenuACases.press(s, b[1][0] + b[1][2] / 2.0, b[1][1] + b[1][3] / 2.0);
                    Map<String, Boolean> back = reads(m);
                    if (back.containsValue(false)) {
                        return m.tab() + ": switched back on, the feature reads " + back;
                    }
                    c.note(m.tab() + ": card switch '" + want + "'; on " + on + " -> off " + off + " (drawer " + rows
                            + ") -> on again");
                    McCompat.setScreen(mc, null);
                    return null;
                });
                if (why != null) {
                    c.problem(why);
                }
            }
            dvdMoves(c);
            cringeSends(c);
            solverDraws(c);
        } finally {
            for (Map.Entry<Path, byte[]> e : files.entrySet()) {
                restore(e.getKey(), e.getValue());
            }
            c.onClient(mc -> {
                for (Master m : MASTERS) {
                    Mod.staticCall(m.cfg(), "load");
                }
                Mod.staticCall("dvd.DvdFeature", "reload");
                return null;
            });
        }
    }

    /** DVD: a box moves with the master on and stands still with it off (drawn by DvdFeature.renderOverlay each frame). */
    private static void dvdMoves(UiCase c) {
        Object entry = c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            Object cfg = Mod.cfg("dvd.DvdConfig");
            Mod.call(cfg, "setDvdEnabled", true);
            Object e = Mod.call(cfg, "addNew");
            Mod.staticCall("dvd.DvdFeature", "reload");
            return e;
        });
        try {
            float[] onMove = dvdTravel(c, entry);
            c.onClient(mc -> {
                Mod.call(Mod.cfg("dvd.DvdConfig"), "setDvdEnabled", false);
                return null;
            });
            float[] offMove = dvdTravel(c, entry);
            c.note("DVD box travel over 20 ticks: master on " + onMove[0] + " units, off " + offMove[0]);
            c.check(onMove[0] > 1f, "the DVD box did not move with DVD on (" + onMove[0] + ") - the instrument saw nothing");
            c.check(offMove[0] == 0f, "the DVD box moved " + offMove[0] + " units with DVD switched off");
        } finally {
            c.onClient(mc -> {
                Object cfg = Mod.cfg("dvd.DvdConfig");
                Mod.call(cfg, "remove", (String) Mod.field(entry, "id"));
                Mod.call(cfg, "setDvdEnabled", true);
                Mod.staticCall("dvd.DvdFeature", "reload");
                return null;
            });
        }
    }

    private static float[] dvdTravel(UiCase c, Object entry) {
        String id = (String) Mod.field(entry, "id");
        float[] a = c.onClient(mc -> dvdPos(id));
        c.ticks(20);
        float[] b = c.onClient(mc -> dvdPos(id));
        if (a == null || b == null) {
            return new float[]{-1f};
        }
        return new float[]{(float) Math.hypot(b[0] - a[0], b[1] - a[1])};
    }

    private static float[] dvdPos(String id) {
        Map<?, ?> runtimes = (Map<?, ?>) Mod.field(Mod.ROOT + "dvd.DvdFeature", "runtimes");
        Object rt = runtimes.get(id);
        if (rt == null) {
            return null;
        }
        return new float[]{(Float) Mod.field(rt, "x"), (Float) Mod.field(rt, "y")};
    }

    /** Cringe: switched off, /cringe's send says so and sends nothing. */
    private static void cringeSends(UiCase c) {
        String why = c.onClient(mc -> {
            Object cfg = Mod.cfg("cringe.CringeConfig");
            Mod.call(cfg, "setEnabled", false);
            Mod.staticCall("notify.ModOverlayMessage", "show", "", 1L);
            Mod.staticCall("cringe.CringeFeature", "sendRandom", "pc");
            String shown = (String) Mod.staticCall("notify.ModOverlayMessage", "current");
            Mod.call(cfg, "setEnabled", true);
            if (shown == null || !shown.contains("Cringe is off")) {
                return "with Cringe off, /cringe showed '" + shown + "' instead of saying it is off";
            }
            c.note("Cringe off: /cringe showed '" + net.minecraft.ChatFormatting.stripFormatting(shown) + "' and sent nothing");
            return null;
        });
        c.check(why == null, why);
    }

    /**
     * Each solver's Solver Highlight switch (mod 4a7ffc31, 2026-10-18: one per solver, the shared Solver Highlights switch
     * removed): for every {@code SolverEspConfig.Solver}, with its own highlight on a draw call reaches the renderer (which
     * throws on the case's null render context), and with it off the outline, waypoint and filled draws all return before
     * it - while every OTHER solver's highlight is on, so the switch is that solver's alone. Each solver's switch and
     * Through Walls are put back as they were.
     */
    private static void solverDraws(UiCase c) {
        String why = c.onClient(mc -> {
            Object cfg = Mod.cfg("puzzlesolvers.SolverEspConfig");
            Object[] solvers = Mod.cls("puzzlesolvers.SolverEspConfig$Solver").getEnumConstants();
            boolean[] wasOn = new boolean[solvers.length];
            boolean[] wasWalls = new boolean[solvers.length];
            for (int i = 0; i < solvers.length; i++) {
                wasOn[i] = (Boolean) Mod.call(cfg, "isEnabled", solvers[i]);
                wasWalls[i] = (Boolean) Mod.call(cfg, "isThroughWalls", solvers[i]);
            }
            Object box = new net.minecraft.world.phys.AABB(0, 0, 0, 1, 1, 1);
            String render = "puzzlesolvers.SolverEspRender";
            try {
                for (Object s : solvers) {
                    Mod.call(cfg, "setThroughWalls", s, false);
                    Mod.call(cfg, "setEnabled", s, true);
                }
                List<String> checked = new ArrayList<>();
                for (Object s : solvers) {
                    Mod.call(cfg, "setEnabled", s, true);
                    boolean onReached;
                    try {
                        Mod.staticCall(render, "renderOutlineBox", s, null, box, 1f, 0f, 0f, 1f, 2f);
                        onReached = false;
                    } catch (AssertionError e) {
                        onReached = true; // it reached the renderer, which threw on the case's null render context
                    }
                    if (!onReached) {
                        return s + ": with its Solver Highlight on, the draw call never reached the renderer - the "
                                + "instrument is blind";
                    }
                    Mod.call(cfg, "setEnabled", s, false);
                    try {
                        Mod.staticCall(render, "renderOutlineBox", s, null, box, 1f, 0f, 0f, 1f, 2f);
                        Mod.staticCall(render, "renderWaypoint", s, null, box, 1f, 0f, 0f, 2f);
                        Mod.staticCall(render, "renderFilledBox", s, null, box, 1f, 0f, 0f, 1f);
                    } catch (AssertionError e) {
                        return s + ": with its Solver Highlight off (every other solver's on), a draw still reached the "
                                + "renderer: " + e.getMessage();
                    }
                    Mod.call(cfg, "setEnabled", s, true);
                    checked.add(String.valueOf(s));
                }
                c.note("Solver Highlight per solver, " + checked.size() + " solvers " + checked + ": on, a draw reached the "
                        + "renderer (threw on the null context); off, outline, waypoint and filled draws all returned before it");
                return checked.size() >= 12 ? null : "only " + checked.size() + " solvers have a highlight switch: " + checked;
            } finally {
                for (int i = 0; i < solvers.length; i++) {
                    Mod.call(cfg, "setThroughWalls", solvers[i], wasWalls[i]);
                    Mod.call(cfg, "setEnabled", solvers[i], wasOn[i]);
                }
            }
        });
        c.check(why == null, why);
    }

    // ---- 984 -------------------------------------------------------------------------------------------------------

    static void boldName(UiCase c) throws Exception {
        String cls = "namechanger.NameChangerConfig";
        Path file = configPath(cls, "CONFIG_PATH");
        byte[] before = snapshot(file);
        // The names live in a second file since mod names-profiles-1015 (shared by every profile).
        Path namesFile = Mod.has("namechanger.NameRank") ? configPath(cls, "NAMES_PATH") : null;
        byte[] namesBefore = namesFile == null ? null : snapshot(namesFile);
        try {
            String why = c.onClient(mc -> {
                Object cfg = Mod.cfg(cls);
                Mod.call(cfg, "setEnabled", true);
                Mod.call(cfg, "setOwnNameEnabled", true);
                Mod.call(cfg, "setOwnDisplayName", "BoldTester");
                Mod.call(cfg, "setOwnColor", 0xFFFF5555);
                Mod.call(cfg, "setOwnBold", false);
                Mod.call(cfg, "save");
                Screen s = drawer(mc, "CosmeticsTab");
                AbstractWidget bold = row(s, "Bold Name");
                if (bold == null) {
                    // the Custom Names section starts closed
                    AbstractWidget header = row(s, "▶ Custom Names");
                    if (header == null) {
                        return "no Bold Name row and no closed Custom Names section: " + rowLabels(s);
                    }
                    ModScreenDriver.press(header);
                    bold = row(s, "Bold Name");
                }
                if (bold == null) {
                    return "no Bold Name row in Cosmetics: " + rowLabels(s);
                }
                ModScreenDriver.press(bold);
                cfg = Mod.cfg(cls);
                if (!(Boolean) Mod.call(cfg, "isOwnBold")) {
                    return "a click on Bold Name did not turn it on";
                }
                Object preview = Mod.staticCall("namechanger.NameChangerFeature", "ownPreview");
                boolean[] allBold = {true};
                int[] letters = {0};
                ((net.minecraft.network.chat.Component) preview).visit((style, text) -> {
                    if (!text.isEmpty()) {
                        letters[0] += text.length();
                        allBold[0] &= style.isBold();
                    }
                    return java.util.Optional.empty();
                }, net.minecraft.network.chat.Style.EMPTY);
                if (letters[0] == 0 || !allBold[0]) {
                    return "the name preview is not bold all through (" + letters[0] + " letters)";
                }
                Object tab = MenuACases.leaf("CosmeticsTab");
                String shared = (String) Mod.call(tab, "plainOwnName");
                String raw = "&c" + shared;
                String verdict = (String) Mod.staticCall("supporters.SupporterNameValidator", "validate", raw);
                if (!shared.startsWith("&l") || verdict != null) {
                    return "the shared name is '" + shared + "' (relay rules say " + verdict + ")";
                }
                Mod.staticCall(cls, "load");
                if (!(Boolean) Mod.get(cls, "isOwnBold")) {
                    return "Bold Name did not survive a reload";
                }
                c.note("Bold Name: preview bold over " + letters[0] + " letters; shared as '" + raw
                        + "', which the relay's rules accept (no relay change); kept after a reload");
                return null;
            });
            c.check(why == null, why);
        } finally {
            restore(file, before);
            if (namesFile != null) {
                restore(namesFile, namesBefore);
            }
            c.onClient(mc -> {
                Mod.staticCall(cls, "load");
                return null;
            });
        }
    }

    // ---- 985 -------------------------------------------------------------------------------------------------------

    static void hudScaleLive(UiCase c) throws Exception {
        Object hud = Mod.cfg("hud.HudConfig");
        float oldScale = (Float) Mod.call(hud, "getGlobalScale");
        Screen[] s = new Screen[1];
        AbstractWidget[] first = new AbstractWidget[1];
        double[] track = new double[2]; // window units at the press
        // 1920x1080 GUI 2 (Auto Scale 1.125): in the small test window every HUD Scale under 100% snaps the menu to the
        // same crisp factor, so only a real-size window shows the menu following the drag.
        Object[] old = bigWindow(c);
        try {
            String why = c.onClient(mc -> {
                Mod.call(hud, "setGlobalScale", 1.0f);
                s[0] = drawer(mc, "HomeMainTab");
                return null;
            });
            c.ticks(3);
            why = c.onClient(mc -> {
                AbstractWidget slider = row(s[0], "HUD Scale");
                if (slider == null) {
                    return "no HUD Scale row: " + rowLabels(s[0]);
                }
                first[0] = slider;
                int[] t = (int[]) Mod.call(slider, "trackRange");
                float f = (Float) Mod.staticCall("hud.AutoScale", "appliedFactor", s[0]);
                track[0] = t[0] * (double) f;
                track[1] = t[1] * (double) f;
                // the knob at 100%: value (1.0 - min) / span
                double v = valueFor(1.0f);
                double x = t[0] + v * (t[1] - t[0]);
                s[0].mouseClicked(new MouseButtonEvent(x, slider.getY() + slider.getHeight() / 2.0, new MouseButtonInfo(0, 0)), false);
                return Mod.staticCall("gui.ThemedSliderButton", "liveDragKey") == null ? "the press started no live drag" : null;
            });
            if (why != null) {
                c.problem(why);
                return;
            }
            Object element = c.onClient(mc -> {
                List<?> all = (List<?>) Mod.staticCall("hud.HudElementRegistry", "all");
                return all.isEmpty() ? null : all.get(0);
            });
            float elementBefore = element == null ? -1f
                    : c.onClient(mc -> (Float) Mod.staticCall("hud.HudElementRegistry", "resolveScale", element));
            float menuBefore = c.onClient(mc -> (Float) Mod.staticCall("hud.AutoScale", "appliedFactor", s[0]));
            List<String> steps = new ArrayList<>();
            boolean handedOver = false;
            float lastMenu = menuBefore;
            for (float want : new float[]{0.9f, 0.75f, 0.6f}) {
                c.onClient(mc -> {
                    float f = (Float) Mod.staticCall("hud.AutoScale", "appliedFactor", s[0]);
                    double windowX = track[0] + valueFor(want) * (track[1] - track[0]);
                    double y = first[0].getY() + first[0].getHeight() / 2.0;
                    s[0].mouseDragged(new MouseButtonEvent(windowX / f, y, new MouseButtonInfo(0, 0)), -1, 0);
                    return null;
                });
                c.ticks(2); // a frame: the relayout runs at the top of the menu's render
                Object[] now = c.onClient(mc -> {
                    float g = (Float) Mod.call(hud, "getGlobalScale");
                    float applied = (Float) Mod.staticCall("hud.AutoScale", "appliedFactor", s[0]);
                    float wanted = (Float) Mod.staticCall("hud.AutoScale", "menuFactor");
                    Object pane = Mod.field(s[0], "contentPane");
                    Object focused = pane == null ? null : Mod.call(pane, "getFocused");
                    boolean dragging = s[0].isDragging() && pane != null && (Boolean) Mod.call(pane, "isDragging");
                    float el = element == null ? -1f : (Float) Mod.staticCall("hud.HudElementRegistry", "resolveScale", element);
                    return new Object[]{g, applied, wanted, focused, dragging, el};
                });
                float g = (Float) now[0];
                float applied = (Float) now[1];
                float wanted = (Float) now[2];
                Object focused = now[3];
                steps.add(String.format(java.util.Locale.ROOT, "want %.2f: HUD Scale %.2f, menu factor %.3f (target %.3f), "
                        + "element scale %.3f, focused %s, dragging %s", want, g, applied, wanted, (Float) now[5],
                        focused == null ? "none" : focused.getClass().getSimpleName() + (focused == first[0] ? " (the pressed one)" : " (rebuilt)"),
                        now[4]));
                if (Math.abs(g - want) > 0.051f) {
                    c.problem("mid-drag the HUD Scale is " + g + ", wanted " + want);
                }
                if (applied != wanted) {
                    c.problem("mid-drag the menu is laid out at " + applied + ", its factor is " + wanted + " - not live");
                }
                if (focused == null || !focused.getClass().getSuperclass().getSimpleName().equals("ThemedSliderButton")
                        || !(Boolean) now[4]) {
                    c.problem("mid-drag the drag is no longer on a slider (focused " + focused + ", dragging " + now[4] + ")");
                }
                handedOver |= focused != null && focused != first[0];
                lastMenu = applied;
            }
            float elementAfter = element == null ? -1f
                    : c.onClient(mc -> (Float) Mod.staticCall("hud.HudElementRegistry", "resolveScale", element));
            for (String st : steps) {
                c.note(st);
            }
            c.note("element " + (element == null ? "none" : Mod.call(element, "id")) + " scale " + elementBefore + " -> "
                    + elementAfter + " mid-drag; menu factor " + menuBefore + " -> " + lastMenu);
            c.check(lastMenu < menuBefore, "the menu's factor did not change during the drag (" + menuBefore + " -> " + lastMenu + ")");
            c.check(element == null || elementAfter < elementBefore, "the HUD element's scale did not follow the drag");
            c.check(handedOver, "the menu was never rebuilt under the drag (the same slider throughout)");
            String rel = c.onClient(mc -> {
                s[0].mouseReleased(new MouseButtonEvent(0, 0, new MouseButtonInfo(0, 0)));
                return Mod.staticCall("gui.ThemedSliderButton", "liveDragKey") == null ? null : "the release left a live drag";
            });
            c.check(rel == null, rel);
        } finally {
            c.onClient(mc -> {
                Mod.staticCall("gui.ThemedSliderButton", "endLiveDrag");
                Mod.call(hud, "setGlobalScale", oldScale);
                Mod.call(hud, "save");
                McCompat.setScreen(mc, null);
                return null;
            });
            restoreWindow(c, old);
        }
    }

    private static double valueFor(float scale) {
        float min = (Float) Mod.field("hud.HudConfig", "MIN_GLOBAL_SCALE");
        float max = (Float) Mod.field("hud.HudConfig", "MAX_GLOBAL_SCALE");
        return (scale - min) / (max - min);
    }

    // ---- 986 -------------------------------------------------------------------------------------------------------

    static void drawerReopens(UiCase c) throws Exception {
        String why = c.onClient(mc -> {
            Screen s = drawer(mc, "ObjectHiderTab");
            Object tab = MenuACases.leaf("ObjectHiderTab");
            int max = (Integer) Mod.field(s, "maxScroll");
            if (max < 20) {
                return "the Object Hider drawer does not scroll (max " + max + "), nothing to remember";
            }
            Mod.setField(Mod.ROOT + "gui.ModScreen", "scrollOffset", max / 2);
            Mod.call(s, "refresh");
            int scrolled = (Integer) Mod.field(Mod.ROOT + "gui.ModScreen", "scrollOffset");
            String firstRow = rowLabels(s).isEmpty() ? "" : plainTopRow(s);
            MenuACases.key(s, InputConstants.KEY_ESCAPE, 0);
            if (McCompat.screen(mc) == s) {
                return "Escape with a drawer open did not close the menu";
            }
            // reopened the way /killer560 opens it
            Screen again = (Screen) newMenu(mc);
            McCompat.setScreen(mc, again);
            Object open = Mod.call(again, "openFeature");
            int scrollAgain = (Integer) Mod.field(Mod.ROOT + "gui.ModScreen", "scrollOffset");
            if (open != tab) {
                return "the reopened menu shows " + (open == null ? "no drawer" : open.getClass().getSimpleName());
            }
            if (scrollAgain != scrolled || !plainTopRow(again).equals(firstRow)) {
                return "the reopened drawer is at scroll " + scrollAgain + " (top row '" + plainTopRow(again)
                        + "'), was " + scrolled + " ('" + firstRow + "')";
            }
            // the drawer's X still closes the drawer (and the menu stays)
            int dx = (Integer) Mod.field(again, "drawerX");
            int dy = (Integer) Mod.field(again, "drawerY");
            int dw = (Integer) Mod.field(again, "drawerW");
            MenuACases.press(again, dx + dw - 18 + 6, dy + 6 + 6);
            if (Mod.call(again, "openFeature") != null || McCompat.screen(mc) != again) {
                return "the drawer's X did not close just the drawer";
            }
            c.note("Escape closed the menu; the reopened menu showed Object Hider at scroll " + scrollAgain
                    + " (top row '" + firstRow + "'); the X closed the drawer");
            return null;
        });
        c.check(why == null, why);
    }

    private static Object newMenu(Minecraft mc) {
        try {
            return Mod.cls("gui.ModScreen").getConstructor(Screen.class).newInstance(McCompat.screen(mc));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** The label of the topmost visible row of the content pane. */
    private static String plainTopRow(Screen s) {
        Object pane = Mod.field(s, "contentPane");
        int top = ((AbstractWidget) pane).getY();
        AbstractWidget best = null;
        for (AbstractWidget w : MenuACases.paneRows(s)) {
            if (w.getY() >= top && (best == null || w.getY() < best.getY())) {
                best = w;
            }
        }
        return best == null ? "" : plain(best);
    }

    // ---- 987 -------------------------------------------------------------------------------------------------------

    static void cycleHalves(UiCase c) throws Exception {
        String cls = "thorn.ThornConfig";
        Path file = configPath(cls, "CONFIG_PATH");
        byte[] before = snapshot(file);
        try {
            String why = c.onClient(mc -> {
                Object cfg = Mod.cfg(cls);
                Screen s = drawer(mc, "ThornTab");
                Object[] styles = Mod.cls("thorn.ThornConfig$Style").getEnumConstants();
                int n = styles.length;
                List<String> seen = new ArrayList<>();
                // label, right half, left half, left half, right click, right half
                String[] what = {"label", "right half", "left half", "left half", "right click", "right half"};
                int[] wantStep = {0, 1, -1, -1, -1, 1};
                int idx = ((Enum<?>) Mod.call(Mod.cfg(cls), "getStyle")).ordinal();
                for (int k = 0; k < what.length; k++) {
                    AbstractWidget w = row(s, "Thorn ESP Style");
                    if (w == null) {
                        return "no Thorn ESP Style row: " + rowLabels(s);
                    }
                    int[] p = controlBounds(w);
                    double y = p[1] + p[3] / 2.0;
                    switch (what[k]) {
                        case "label" -> click(s, w.getX() + 6, y, 0);
                        case "right half" -> click(s, p[0] + p[2] * 0.75, y, 0);
                        case "left half" -> click(s, p[0] + p[2] * 0.25, y, 0);
                        default -> click(s, p[0] + p[2] * 0.75, y, 1);
                    }
                    int now = ((Enum<?>) Mod.call(Mod.cfg(cls), "getStyle")).ordinal();
                    int want = Math.floorMod(idx + wantStep[k], n);
                    seen.add(what[k] + " -> " + styles[now]);
                    if (now != want) {
                        return "a click on the " + what[k] + " left Thorn ESP Style at " + styles[now] + ", wanted "
                                + styles[want] + " (" + seen + ")";
                    }
                    idx = now;
                }
                c.note("Thorn ESP Style (" + n + " values): " + seen);
                return null;
            });
            c.check(why == null, why);
        } finally {
            restore(file, before);
            c.onClient(mc -> {
                Mod.staticCall(cls, "load");
                return null;
            });
        }
    }

    // ---- 988 -------------------------------------------------------------------------------------------------------

    static void calculator(UiCase c) {
        String[][] table = {
                // implicit products (new)
                {"2(1+1)", "4"}, {"(1+1)(2)", "4"}, {"(1+1)2", "4"}, {"2k(3)", "6,000"}, {"3(2)(2)", "12"},
                {"-(2)(3)", "-6"}, {"2 (1+1)", "4"}, {"1.5(2)", "3"},
                // what worked before
                {"5k*3", "15,000"}, {"2s+10", "138"}, {"(1.5m-200k)/4", "325,000"}, {"2^3^2", "64"}, {"1,5*2", "3"},
                {"2x3", "6"}, {"10%", "0.1"}, {"-3+5", "2"},
                // not sums: still a search
                {"2 3", null}, {"hello", null}, {"(5)", null}, {"12", null}, {"2(", null}, {"()", null},
        };
        List<String> bad = new ArrayList<>();
        for (String[] row : table) {
            String got = (String) Mod.staticCall("inventorysearch.SearchCalculator", "resultFor", row[0]);
            if (!java.util.Objects.equals(got, row[1])) {
                bad.add("'" + row[0] + "' -> " + got + " (wanted " + row[1] + ")");
            } else {
                c.note("'" + row[0] + "' -> " + got);
            }
        }
        c.check(bad.isEmpty(), bad.size() + " wrong: " + bad);
    }

    // ---- 989 -------------------------------------------------------------------------------------------------------

    static void savedThemes(UiCase c) throws Exception {
        Path file = configPath("gui.MenuConfig", "CONFIG_PATH");
        byte[] before = snapshot(file);
        try {
            String why = c.onClient(mc -> {
                Object cfg = Mod.cfg("gui.MenuConfig");
                Mod.call(cfg, "setTheme", Mod.enumValue("gui.MenuTheme", "OCEAN"));
                Mod.call(cfg, "save");
                Screen s = drawer(mc, "MenuThemeTab");
                AbstractWidget save = row(s, "Save as New Theme");
                if (save == null) {
                    return "no Save as New Theme row: " + rowLabels(s);
                }
                ModScreenDriver.press(save);
                // Custom 1 is Ocean's colours; change the Accent while it is in use, then save a second theme.
                Object[] slots = Mod.cls("gui.MenuTheme$Slot").getEnumConstants();
                Mod.staticCall("gui.tab.MenuThemeTab", "setColour", slots[0], 0xFF22AA44);
                ModScreenDriver.press(row(s, "Save as New Theme"));
                Mod.staticCall("gui.tab.MenuThemeTab", "setColour", slots[0], 0xFFAA2266);
                cfg = Mod.cfg("gui.MenuConfig");
                List<?> saved = (List<?>) Mod.call(cfg, "getSavedThemes");
                if (saved.size() != 2) {
                    return "two presses saved " + saved.size() + " theme(s)";
                }
                int a0 = ((int[]) Mod.field(saved.get(0), "colours"))[0];
                int a1 = ((int[]) Mod.field(saved.get(1), "colours"))[0];
                if (a0 != 0xFF22AA44 || a1 != 0xFFAA2266) {
                    return String.format("the saved themes' accents are %08X / %08X, wanted FF22AA44 / FFAA2266", a0, a1);
                }
                // rename Custom 1 by typing in its box (a real click focuses it)
                Mod.call(s, "refresh");
                List<EditBox> boxes = new ArrayList<>();
                for (AbstractWidget w : MenuACases.paneRows(s)) {
                    if (w instanceof EditBox eb) {
                        boxes.add(eb);
                    }
                }
                if (boxes.size() != 2) {
                    return "the drawer has " + boxes.size() + " name box(es) for 2 saved themes";
                }
                EditBox first = boxes.get(0);
                click(s, first.getX() + 4, first.getY() + first.getHeight() / 2.0, 0);
                if (!first.isFocused()) {
                    return "a click on the first theme's name box did not focus it";
                }
                MenuACases.key(s, InputConstants.KEY_A, 2 /* GLFW_MOD_CONTROL */);
                MenuACases.key(s, InputConstants.KEY_BACKSPACE, 0);
                MenuACases.type(s, "Forest Night");
                String name0 = (String) Mod.field(((List<?>) Mod.call(Mod.cfg("gui.MenuConfig"), "getSavedThemes")).get(0), "name");
                if (!"Forest Night".equals(name0)) {
                    return "typing renamed the first theme to '" + name0 + "'";
                }
                // Use the first one (a real click on its Use)
                AbstractWidget use = null;
                for (AbstractWidget w : MenuACases.paneRows(s)) {
                    if (plain(w).equals("Use") && Math.abs(w.getY() - first.getY()) < 4) {
                        use = w;
                    }
                }
                if (use == null) {
                    return "no Use button beside the first theme: " + rowLabels(s);
                }
                ModScreenDriver.press(use);
                cfg = Mod.cfg("gui.MenuConfig");
                int active = (Integer) Mod.call(cfg, "getActiveSaved");
                int drawn = ((int[]) Mod.call(cfg, "activeColours"))[0];
                if (active != 0 || drawn != 0xFF22AA44) {
                    return String.format("Use on the first theme: active %d, accent drawn %08X", active, drawn);
                }
                // restart: the file re-read
                Mod.staticCall("gui.MenuConfig", "load");
                cfg = Mod.cfg("gui.MenuConfig");
                saved = (List<?>) Mod.call(cfg, "getSavedThemes");
                String names = saved.size() + " " + (saved.isEmpty() ? "" : Mod.field(saved.get(0), "name") + ", "
                        + (saved.size() > 1 ? Mod.field(saved.get(1), "name") : ""));
                int activeAfter = (Integer) Mod.call(cfg, "getActiveSaved");
                int drawnAfter = ((int[]) Mod.call(cfg, "activeColours"))[0];
                if (saved.size() != 2 || !"Forest Night".equals(Mod.field(saved.get(0), "name"))
                        || !"Custom 2".equals(Mod.field(saved.get(1), "name")) || activeAfter != 0 || drawnAfter != 0xFF22AA44) {
                    return String.format("after a reload: %s, active %d, accent %08X", names, activeAfter, drawnAfter);
                }
                // Delete the second: one click arms it (Confirm), the second deletes; saved
                Mod.setField(Mod.ROOT + "gui.ModScreen", "tabs", null);
                Screen s2 = drawer(mc, "MenuThemeTab");
                List<AbstractWidget> deletes = new ArrayList<>();
                for (AbstractWidget w : MenuACases.paneRows(s2)) {
                    if (plain(w).equals("Delete")) {
                        deletes.add(w);
                    }
                }
                if (deletes.size() != 2) {
                    return "the drawer has " + deletes.size() + " Delete button(s) for 2 themes";
                }
                ModScreenDriver.press(deletes.get(1));
                if (((List<?>) Mod.call(Mod.cfg("gui.MenuConfig"), "getSavedThemes")).size() != 2) {
                    return "one click on Delete deleted the theme";
                }
                AbstractWidget confirm = row(s2, "Confirm");
                if (confirm == null) {
                    return "Delete did not turn into Confirm: " + rowLabels(s2);
                }
                ModScreenDriver.press(confirm);
                Mod.staticCall("gui.MenuConfig", "load");
                saved = (List<?>) Mod.call(Mod.cfg("gui.MenuConfig"), "getSavedThemes");
                if (saved.size() != 1 || !"Forest Night".equals(Mod.field(saved.get(0), "name"))) {
                    return "after Delete + Confirm and a reload, the saved themes are " + saved.size();
                }
                c.note("saved Custom 1 and Custom 2 (accents FF22AA44 / FFAA2266), renamed Custom 1 to 'Forest Night' by "
                        + "typing, Use drew it, a reload kept " + names + " (in use: first), Delete needed Confirm and "
                        + "the reload showed 1 left");
                return null;
            });
            c.check(why == null, why);
        } finally {
            restore(file, before);
            c.onClient(mc -> {
                Mod.staticCall("gui.MenuConfig", "load");
                McCompat.setScreen(mc, null);
                return null;
            });
        }
    }
}
