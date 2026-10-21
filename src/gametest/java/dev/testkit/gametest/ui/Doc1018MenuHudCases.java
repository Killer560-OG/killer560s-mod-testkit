package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.PacketTrace;
import dev.testkit.harness.Report;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.world.inventory.ChestMenu;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongConsumer;

/**
 * 1018-ui-*: killer560's 2026-10-18 menu and HUD list (mod todo-menu-hud-1018). Every case drives the real settings
 * (the drawer's own rows, real key and mouse input where the item is about input) and checks that the change ACTED, not
 * that a method exists: a case that finds its row or effect missing says which one, and each was run against the main
 * jar (commit c099a9fa) to see it fail there.
 * <ul>
 *   <li>13 search-sum-enter: Enter in the Ctrl+F bar replaces a sum by its plain answer.</li>
 *   <li>16 menu-search-word-order: "chat copy" finds Copy Chat in the menu search.</li>
 *   <li>17 theme-one-per-section: Croesus, Experiments and the Leap Menu have exactly one Theme row, that one setting
 *       themes every part of the section; Croesus has no "claims logged" line; an old per-part choice migrates.</li>
 *   <li>25 chat-history-unlimited: the Chat History tab's switch lifts vanilla's 100-message cap.</li>
 *   <li>26 loadout-open-key: the Open Loadouts Key sends /loadouts once per press.</li>
 *   <li>30 profiles-count-gone: the Profiles heading band draws no "N profiles" on its right.</li>
 *   <li>31 home-sweat-once: no Sweat Mode row in Home's Mod or HUD drawer.</li>
 *   <li>32 solver-highlights-per-solver: no Solver Highlights tab; each solver has its own rows and settings; an old
 *       shared file starts every solver on its values.</li>
 *   <li>34 termism-menu: the explanatory line is gone, Random and Done have more room.</li>
 *   <li>35 spotify-lyrics-name: the tab is Spotify Lyrics and has no "not running" line.</li>
 *   <li>36 peek-chat-modes: Peek Mode Toggle/Hold; the wheel scrolls chat and never the hotbar.</li>
 *   <li>37 voice-status-hud: Show Status On HUD hides the overlay lines; the download percent line.</li>
 *   <li>38 inventory-hud-default / inventory-hud-opacity: centred above the hotbar until moved; no black box below 100%.</li>
 *   <li>39 pet-wheel-pages: pages as presets, switched in the tab and on the wheel; saved; an old file is page 1.</li>
 *   <li>40 sim-keybinds-text: no "Dungeon Sim only" line.</li>
 *   <li>41 performance-hud-colors: five colours, drawn and saved.</li>
 *   <li>42 chat-hider-ban: Hide Ban Announcements hides the Watchdog lines and nothing a player says.</li>
 *   <li>43 party-commands-defaults: every command on, Guild and All Chat off.</li>
 *   <li>44 inventory-theme-settings: the rearranged tab, Default follows the menu, Customize, the old preset carried over.</li>
 *   <li>46 ribbon-in-cosmetics: no Ribbon Trail card; its settings are a Player Cosmetics section.</li>
 * </ul>
 */
final class Doc1018MenuHudCases {

    private Doc1018MenuHudCases() {
    }

    // =================================================================================================================
    // shared
    // =================================================================================================================

    @SuppressWarnings("unchecked")
    private static List<AbstractWidget> build(Object tab) {
        try {
            Method m = tab.getClass().getMethod("buildWidgets", int.class, int.class, int.class, Runnable.class);
            return (List<AbstractWidget>) m.invoke(tab, 10, 10, 330, (Runnable) () -> { });
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not build " + tab.getClass().getSimpleName() + ": " + UiCase.describe(e), e);
        }
    }

    private static List<String> labels(UiCase c, String tabClass) {
        return c.onClient(mc -> {
            List<String> out = new ArrayList<>();
            for (AbstractWidget w : build(MenuACases.leaf(tabClass))) {
                out.add(ModScreenDriver.label(w));
            }
            return out;
        });
    }

    private static boolean has(List<String> labels, String prefix) {
        for (String l : labels) {
            if (l.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static int count(List<String> labels, String contains) {
        int n = 0;
        for (String l : labels) {
            if (l.contains(contains)) {
                n++;
            }
        }
        return n;
    }

    /** Presses (a real click on) the first row of {@code tabClass}'s drawer whose label starts with {@code prefix}. */
    private static boolean pressRow(UiCase c, String tabClass, String prefix) {
        return c.onClient(mc -> {
            for (AbstractWidget w : build(MenuACases.leaf(tabClass))) {
                if (ModScreenDriver.label(w).startsWith(prefix)) {
                    return ModScreenDriver.press(w);
                }
            }
            return false;
        });
    }

    private static List<String> leafNames() {
        List<String> out = new ArrayList<>();
        for (Object t : MenuACases.leaves()) {
            out.add(MenuACases.name(t));
        }
        return out;
    }

    private static boolean hasLeaf(String simpleClass) {
        for (Object t : MenuACases.leaves()) {
            if (t.getClass().getSimpleName().equals(simpleClass)) {
                return true;
            }
        }
        return false;
    }

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

    private static void closeScreen(UiCase c) {
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            return null;
        });
        c.ticks(2);
    }

    private static BufferedImage shot(UiCase c, String label) {
        c.ticks(2);
        String name = c.name() + "-" + label;
        Path taken = c.ctx().takeScreenshot(Report.fileName(name));
        Path kept = Report.screenshot(name, taken);
        try {
            return javax.imageio.ImageIO.read((kept != null ? kept : taken).toFile());
        } catch (Exception e) {
            throw new AssertionError("could not read the screenshot: " + e, e);
        }
    }

    private static int lum(BufferedImage img, int x, int y) {
        if (x < 0 || y < 0 || x >= img.getWidth() || y >= img.getHeight()) {
            return 0;
        }
        int p = img.getRGB(x, y);
        return (((p >> 16) & 0xFF) * 3 + ((p >> 8) & 0xFF) * 6 + (p & 0xFF)) / 10;
    }

    private static Screen chestScreen() {
        var player = Minecraft.getInstance().player;
        return new ContainerScreen(ChestMenu.threeRows(0, player.getInventory()), player.getInventory(),
                Component.literal("Testkit Chest"));
    }

    // =================================================================================================================
    // 13 Inventory Search: Enter replaces a sum by its answer
    // =================================================================================================================

    static void searchSumEnter(UiCase c) {
        String feature = "inventorysearch.InventorySearchFeature";
        Object cfg = Mod.cfg("inventorysearch.InventorySearchConfig");
        boolean was = (Boolean) Mod.call(cfg, "isEnabled");
        try {
            c.onClient(mc -> {
                Mod.call(cfg, "setEnabled", true);
                McCompat.setScreen(mc, chestScreen());
                return null;
            });
            c.ticks(3);
            // ---- a sum: shown with its result while typed, then only the answer after Enter ----
            c.onClient(mc -> {
                Object q = Mod.field(feature, "QUERY");
                Mod.call(q, "setLength", 0);
                Mod.staticCall(feature, "setListening", true);
                return null;
            });
            c.ctx().getInput().typeChars("5k*3");
            c.ticks(2);
            String typed = c.onClient(mc -> (String) Mod.staticCall(feature, "barText"));
            c.note("while typing: '" + typed + "'");
            c.check(typed.contains("15,000"), "the bar does not show the sum's result while typing: '" + typed + "' - the control failed");
            c.ctx().getInput().pressKey(257); // Enter
            c.ticks(3);
            String after = c.onClient(mc -> (String) Mod.staticCall(feature, "barText"));
            boolean listening = c.onClient(mc -> (Boolean) Mod.staticCall(feature, "isListening"));
            c.note("after Enter: '" + after + "', still typing: " + listening);
            c.check("15000".equals(after), "after Enter the bar reads '" + after + "', want only the answer 15000");
            c.check(!listening, "Enter did not end typing");

            // ---- plain text is left alone ----
            c.onClient(mc -> {
                Mod.call(Mod.field(feature, "QUERY"), "setLength", 0);
                Mod.staticCall(feature, "setListening", true);
                return null;
            });
            c.ctx().getInput().typeChars("hyperion");
            c.ctx().getInput().pressKey(257);
            c.ticks(3);
            String plain = c.onClient(mc -> (String) Mod.staticCall(feature, "barText"));
            c.check("hyperion".equals(plain), "Enter changed plain text: '" + plain + "'");
        } finally {
            c.onClient(mc -> {
                Mod.call(Mod.field(feature, "QUERY"), "setLength", 0);
                Mod.staticCall(feature, "setListening", false);
                Mod.call(cfg, "setEnabled", was);
                McCompat.setScreen(mc, null);
                return null;
            });
        }
    }

    // =================================================================================================================
    // 16 menu search: words in any order
    // =================================================================================================================

    static void menuSearchOrder(UiCase c) {
        Screen s = c.onClient(mc -> {
            try {
                ModScreenDriver d = new ModScreenDriver(mc);
                McCompat.setScreen(mc, d.screen);
                return d.screen;
            } catch (Exception e) {
                throw new AssertionError(UiCase.describe(e), e);
            }
        });
        try {
            for (String[] q : new String[][]{{"copy chat", "yes"}, {"chat copy", "yes"}, {"CHAT  COPY", "yes"}, {"copy zzzz", "no"}}) {
                c.onClient(mc -> {
                    ((net.minecraft.client.gui.components.EditBox) R.get(s, "searchField")).setValue(q[0]);
                    return null;
                });
                c.ticks(3);
                List<String> found = c.onClient(mc -> {
                    List<String> out = new ArrayList<>();
                    for (Object r : (List<?>) Mod.field(s, "results")) {
                        if ((Boolean) Mod.call(r, "feature")) {
                            out.add((String) Mod.call(r, "label"));
                        }
                    }
                    return out;
                });
                c.note("search '" + q[0] + "' lists " + found);
                if (q[1].equals("yes")) {
                    c.check(found.contains("Copy Chat"), "searching '" + q[0] + "' does not list Copy Chat: " + found);
                } else {
                    c.check(!found.contains("Copy Chat"), "searching '" + q[0] + "' lists Copy Chat: " + found);
                }
            }
        } finally {
            c.onClient(mc -> {
                ((net.minecraft.client.gui.components.EditBox) R.get(s, "searchField")).setValue("");
                return null;
            });
            closeScreen(c);
        }
    }

    // =================================================================================================================
    // 17 one Theme per mod section
    // =================================================================================================================

    static void themeOnePerSection(UiCase c) throws Exception {
        Path file = (Path) Mod.staticCall("gui.ScreenThemes", "path");
        byte[] saved = snapshot(file);
        Object croesus = Mod.cfg("croesus.CroesusConfig");
        boolean loggerWas = (Boolean) Mod.call(croesus, "isLoggerEnabled");
        try {
            c.onClient(mc -> {
                Mod.call(croesus, "setLoggerEnabled", true);
                return null;
            });
            // ---- the rows ----
            for (String[] t : new String[][]{{"CroesusTab", "Croesus"}, {"ExperimentsTab", "Experiments"}, {"LeapMenuTab", "Leap Menu"}}) {
                List<String> l = labels(c, t[0]);
                int themeRows = 0;
                for (String s : l) {
                    if (s.startsWith("Theme") || s.contains("Menu Theme") || s.contains("Button Menu Theme")) {
                        themeRows++;
                    }
                }
                c.note(t[1] + ": " + themeRows + " theme row(s): " + l.stream().filter(x -> x.contains("Theme")).toList());
                c.check(themeRows == 1, t[1] + " has " + themeRows + " theme rows, want exactly one: "
                        + l.stream().filter(x -> x.contains("Theme")).toList());
            }
            List<String> cro = labels(c, "CroesusTab");
            c.check(count(cro, "claims logged") == 0, "Croesus still has the 'claims logged' line: " + cro);

            // ---- one choice themes every part ----
            String[][] sections = {{"croesus", "chest_profit", "start_buttons"}, {"profit", "experiments_start"},
                    {"leaporder", "leap_menu"}};
            for (String[] sec : sections) {
                c.onClient(mc -> {
                    Mod.staticCall("gui.ScreenThemes", "set", sec[0], "OCEAN");
                    return null;
                });
                for (String part : sec) {
                    String got = c.onClient(mc -> (String) Mod.staticCall("gui.ScreenThemes", "get", part));
                    c.check("OCEAN".equals(got), "setting the " + sec[0] + " Theme to Ocean leaves '" + part + "' on " + got);
                    if (part.equals("chest_profit") || part.equals("start_buttons") || part.equals("leap_menu")
                            || part.equals("experiments_start")) {
                        Object palette = c.onClient(mc -> Mod.staticCall("gui.OverlayThemes", "of", part));
                        c.check(palette != null, "the overlay '" + part + "' does not draw in the section's Ocean theme (palette null)");
                    }
                }
                c.onClient(mc -> {
                    Mod.staticCall("gui.ScreenThemes", "set", sec[0], "DEFAULT");
                    return null;
                });
            }

            // ---- the real Theme row steps the whole section ----
            boolean pressed = pressRow(c, "CroesusTab", "Theme");
            Object pal = c.onClient(mc -> Mod.staticCall("gui.OverlayThemes", "of", "chest_profit"));
            c.check(pressed && pal != null, "pressing Croesus' one Theme row did not theme its chest profit panel");
            c.onClient(mc -> {
                Mod.staticCall("gui.ScreenThemes", "set", "croesus", "DEFAULT");
                return null;
            });

            // ---- a choice saved under a part's old id moves to its section ----
            Files.writeString(file, "{\"chest_profit\":\"LIGHT\",\"leap_menu\":\"DARK\",\"start_buttons\":\"OCEAN\"}", StandardCharsets.UTF_8);
            c.onClient(mc -> {
                Mod.staticCall("gui.ScreenThemes", "load");
                return null;
            });
            String cr = c.onClient(mc -> (String) Mod.staticCall("gui.ScreenThemes", "get", "croesus"));
            String lm = c.onClient(mc -> (String) Mod.staticCall("gui.ScreenThemes", "get", "leaporder"));
            c.note("old file: croesus " + cr + ", leaporder " + lm);
            c.check("LIGHT".equals(cr) || "OCEAN".equals(cr), "an old chest_profit/start_buttons choice did not carry to Croesus: " + cr);
            c.check("DARK".equals(lm), "an old leap_menu choice did not carry to the Leap Menu's theme: " + lm);
        } finally {
            restore(file, saved);
            c.onClient(mc -> {
                Mod.staticCall("gui.ScreenThemes", "load");
                Mod.call(croesus, "setLoggerEnabled", loggerWas);
                return null;
            });
        }
    }

    // =================================================================================================================
    // 25 Chat History: unlimited
    // =================================================================================================================

    static void chatHistory(UiCase c) {
        List<String> row = labels(c, "ChatHistoryTab");
        c.check(has(row, "Unlimited Chat History"), "the Chat History tab has no Unlimited Chat History row: " + row);
        Object chat = c.onClient(mc -> Mod.staticCall("compat.McCompat", "chat", mc));
        try {
            // Off: vanilla's cap.
            c.onClient(mc -> {
                Mod.call(Mod.cfg("chathistory.ChatHistoryConfig"), "setUnlimited", false);
                for (int i = 1; i <= 130; i++) {
                    Mod.call(chat, "addClientSystemMessage", Component.literal("§7history-off line " + i));
                }
                return null;
            });
            c.ticks(2);
            int off = c.onClient(mc -> ((List<?>) Mod.field(chat, "allMessages")).size());
            int offLines = c.onClient(mc -> ((List<?>) Mod.field(chat, "trimmedMessages")).size());
            c.note("Unlimited off: " + off + " messages, " + offLines + " wrapped lines after 130 more");
            c.check(off == 100, "with the switch off the chat keeps " + off + " messages, want vanilla's 100");

            // On, by a real click on its row.
            boolean pressed = pressRow(c, "ChatHistoryTab", "Unlimited Chat History");
            boolean on = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg("chathistory.ChatHistoryConfig"), "isUnlimited"));
            c.check(pressed && on, "a click on Unlimited Chat History did not turn it on");
            c.onClient(mc -> {
                for (int i = 1; i <= 130; i++) {
                    Mod.call(chat, "addClientSystemMessage", Component.literal("§7history-on line " + i));
                }
                return null;
            });
            c.ticks(2);
            int onCount = c.onClient(mc -> ((List<?>) Mod.field(chat, "allMessages")).size());
            int onLines = c.onClient(mc -> ((List<?>) Mod.field(chat, "trimmedMessages")).size());
            c.note("Unlimited on: " + onCount + " messages, " + onLines + " wrapped lines after 130 more");
            c.check(onCount >= 230, "with the switch on the chat keeps " + onCount + " messages, want at least 230 (100 kept + 130 new)");
            c.check(onLines >= 230, "with the switch on the chat keeps " + onLines + " wrapped lines, want at least 230");
        } finally {
            c.onClient(mc -> {
                Mod.call(Mod.cfg("chathistory.ChatHistoryConfig"), "setUnlimited", false);
                return null;
            });
        }
        // It is saved: a reload from disk keeps it.
        c.onClient(mc -> {
            Object cfg = Mod.cfg("chathistory.ChatHistoryConfig");
            Mod.call(cfg, "setUnlimited", true);
            Mod.call(cfg, "save");
            Mod.staticCall("chathistory.ChatHistoryConfig", "load");
            return null;
        });
        boolean reloaded = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg("chathistory.ChatHistoryConfig"), "isUnlimited"));
        c.check(reloaded, "Unlimited Chat History did not survive a reload from disk");
        c.onClient(mc -> {
            Object cfg = Mod.cfg("chathistory.ChatHistoryConfig");
            Mod.call(cfg, "setUnlimited", false);
            Mod.call(cfg, "save");
            return null;
        });
    }

    // =================================================================================================================
    // 26 Loadout Keybinds: Open Loadouts Key
    // =================================================================================================================

    private static final int KEY = 326; // GLFW_KEY_KP_6

    static void loadoutOpenKey(UiCase c) {
        Object cfg = Mod.cfg("loadoutkeybinds.LoadoutKeybindsConfig");
        boolean was = (Boolean) Mod.call(cfg, "isEnabled");
        List<String> sent = new CopyOnWriteArrayList<>();
        try {
            c.onClient(mc -> {
                Mod.call(cfg, "setEnabled", true);
                return null;
            });
            List<String> l = labels(c, "LoadoutKeybindsTab");
            c.check(has(l, "Open Loadouts Key"), "the Loadout Keybinds tab has no Open Loadouts Key row: " + l);
            c.onClient(mc -> {
                Mod.call(cfg, "setOpenKey", KEY);
                McCompat.setScreen(mc, null);
                return null;
            });
            PacketTrace.tap = p -> {
                if (p instanceof ServerboundChatCommandPacket cmd) {
                    sent.add(cmd.command());
                }
            };
            c.ticks(3);
            // A held key sends once; a second press sends again.
            c.ctx().getInput().holdKey(KEY);
            c.ticks(12);
            c.ctx().getInput().releaseKey(KEY);
            c.ticks(3);
            c.note("after one long press: " + sent);
            c.check(sent.equals(List.of("loadouts")), "one press of the Open Loadouts Key sent " + sent + ", want exactly [loadouts]");
            c.ctx().getInput().holdKey(KEY);
            c.ticks(3);
            c.ctx().getInput().releaseKey(KEY);
            c.ticks(3);
            c.check(sent.size() == 2, "a second press did not send again: " + sent);

            // With a screen open it does nothing.
            sent.clear();
            c.onClient(mc -> {
                McCompat.setScreen(mc, chestScreen());
                return null;
            });
            c.ticks(3);
            c.ctx().getInput().holdKey(KEY);
            c.ticks(3);
            c.ctx().getInput().releaseKey(KEY);
            c.ticks(3);
            c.check(sent.isEmpty(), "the key sent " + sent + " with a menu open");
            // Unbound: nothing.
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                Mod.call(cfg, "setOpenKey", -1);
                return null;
            });
            c.ticks(3);
            c.ctx().getInput().holdKey(KEY);
            c.ticks(3);
            c.ctx().getInput().releaseKey(KEY);
            c.ticks(3);
            c.check(sent.isEmpty(), "with no key set the old key still sent " + sent);
        } finally {
            PacketTrace.tap = null;
            c.ctx().getInput().releaseKey(KEY);
            c.onClient(mc -> {
                Mod.call(cfg, "setOpenKey", -1);
                Mod.call(cfg, "setEnabled", was);
                McCompat.setScreen(mc, null);
                return null;
            });
        }
    }

    // =================================================================================================================
    // 30 Profiles heading: no "N profiles" on the right
    // =================================================================================================================

    static void profilesCount(UiCase c) {
        Screen s = c.onClient(mc -> {
            try {
                ModScreenDriver d = new ModScreenDriver(mc);
                McCompat.setScreen(mc, d.screen);
                Mod.call(d.screen, "openFeature", MenuACases.leaf("ProfilesTab"));
                return d.screen;
            } catch (Exception e) {
                throw new AssertionError(UiCase.describe(e), e);
            }
        });
        try {
            c.ticks(5);
            int[] band = c.onClient(mc -> {
                for (AbstractWidget w : MenuACases.paneRows(s)) {
                    if (ModScreenDriver.label(w).equals("Saved profiles")) {
                        return new int[]{w.getX(), w.getY(), w.getWidth(), w.getHeight()};
                    }
                }
                return null;
            });
            c.check(band != null, "no 'Saved profiles' heading band on the Profiles page");
            if (band == null) {
                return;
            }
            BufferedImage img = shot(c, "profiles");
            double f = c.onClient(mc -> ((Number) Mod.staticCall("hud.AutoScale", "appliedFactor", s)).doubleValue());
            double k = img.getWidth() / (double) c.onClient(mc -> mc.getWindow().getGuiScaledWidth());
            int x0 = (int) Math.round(band[0] * f * k);
            int w = (int) Math.round(band[2] * f * k);
            int y0 = (int) Math.round((band[1] + 2) * f * k);
            int y1 = (int) Math.round((band[1] + band[3] - 2) * f * k);
            int left = text(img, x0 + (int) (w * 0.02), x0 + (int) (w * 0.35), y0, y1, x0 + w / 2);
            int right = text(img, x0 + (int) (w * 0.62), x0 + (int) (w * 0.97), y0, y1, x0 + w / 2);
            c.note("heading band " + band[2] + " wide at " + band[0] + "," + band[1] + " (factor " + f + "): text pixels left "
                    + left + ", right " + right);
            c.check(left > 10, "the probe finds no text in the heading's left part (SAVED PROFILES) - it would prove nothing: " + left);
            c.check(right <= 2, "the heading still draws text on its right (" + right + " px) - the 'N profiles' count");
        } finally {
            closeScreen(c);
        }
    }

    /** Pixels in the box whose brightness differs by more than 40 from the same row's pixel at {@code refX}. */
    private static int text(BufferedImage img, int xa, int xb, int ya, int yb, int refX) {
        int n = 0;
        for (int y = ya; y < yb; y++) {
            int ref = lum(img, refX, y);
            for (int x = xa; x < xb; x++) {
                if (Math.abs(lum(img, x, y) - ref) > 40) {
                    n++;
                }
            }
        }
        return n;
    }

    // =================================================================================================================
    // 31 Sweat Mode only at the top of Home
    // =================================================================================================================

    static void homeSweat(UiCase c) {
        List<String> l = labels(c, "HomeMainTab");
        c.note("Home's drawer rows: " + l);
        c.check(count(l, "Sweat Mode") == 0, "Sweat Mode is still a row in Home's Mod or HUD drawer: " + l);
        c.check(has(l, "HUD Scale") || has(l, "Auto Scale") || has(l, "Skyblock Only"), "the probe did not build Home's drawer: " + l);
        // The top button is still there and works (1016 presses it for real).
        Object btn = c.onClient(mc -> {
            try {
                ModScreenDriver d = new ModScreenDriver(mc);
                McCompat.setScreen(mc, d.screen);
                Mod.call(d.screen, "selectCategory", "Home");
                Object b = Mod.call(d.screen, "homeButtonBounds", "Sweat Mode");
                McCompat.setScreen(mc, null);
                return b;
            } catch (Exception e) {
                throw new AssertionError(UiCase.describe(e), e);
            }
        });
        c.check(btn != null, "Home's top Sweat Mode button is gone");
    }

    // =================================================================================================================
    // 32 Solver highlights: per solver
    // =================================================================================================================

    private static final String[][] SOLVERS = {
            // tab class, config class, Solver constant, draws waypoints
            {"BoulderSolverTab", "puzzlesolvers.BoulderSolverConfig", "BOULDER", "y"},
            {"QuizSolverTab", "puzzlesolvers.QuizSolverConfig", "QUIZ", "y"},
            {"IceFillSolverTab", "puzzlesolvers.IceFillSolverConfig", "ICE_FILL", "n"},
            {"IcePathSolverTab", "puzzlesolvers.IcePathSolverConfig", "ICE_PATH", "y"},
            {"WeirdosSolverTab", "puzzlesolvers.WeirdosSolverConfig", "WEIRDOS", "y"},
            {"WaterSolverTab", "puzzlesolvers.WaterSolverConfig", "WATER", "y"},
            {"BeamsSolverTab", "puzzlesolvers.BeamsSolverConfig", "BEAMS", "y"},
            {"BlazeSolverTab", "puzzlesolvers.BlazeSolverConfig", "BLAZE", "n"},
            {"TicTacToeSolverTab", "puzzlesolvers.TicTacToeSolverConfig", "TIC_TAC_TOE", "n"},
            {"TeleportMazeSolverTab", "puzzlesolvers.TeleportMazeSolverConfig", "TELEPORT_MAZE", "y"},
            {"LividSolverTab", "boss.LividSolverConfig", "LIVID", "n"},
            {"P4PlatformHighlightTab", "p4platform.P4PlatformHighlightConfig", "P4_PLATFORM", "n"},
    };

    static void solverHighlights(UiCase c) throws Exception {
        c.check(!hasLeaf("SolverHighlightsTab"), "the Solver Highlights tab is still in the menu: " + leafNames());
        c.check(!leafNames().contains("Solver Highlights"), "a 'Solver Highlights' card is still listed");
        String cfgCls = "puzzlesolvers.SolverEspConfig";
        Path file = (Path) Mod.field(cfgCls, "CONFIG_PATH");
        byte[] saved = snapshot(file);
        List<Boolean> were = new ArrayList<>();
        try {
            for (String[] s : SOLVERS) {
                Object cfg = Mod.cfg(s[1]);
                were.add((Boolean) Mod.call(cfg, "isEnabled"));
                c.onClient(mc -> {
                    Mod.call(cfg, "setEnabled", true);
                    return null;
                });
            }
            // ---- the rows, in every solver ----
            for (String[] s : SOLVERS) {
                List<String> l = labels(c, s[0]);
                boolean ok = has(l, "Solver Highlight: ") && has(l, "Solver Highlight Through Walls");
                c.check(ok, s[0] + " has no Solver Highlight / Solver Highlight Through Walls rows: " + l);
                c.check(has(l, "Solver Highlight Style") == s[3].equals("y"),
                        s[0] + (s[3].equals("y") ? " is missing" : " has") + " a Solver Highlight Style row: " + l);
            }
            // ---- independent per solver, through the real rows ----
            boolean pressed = pressRow(c, "BoulderSolverTab", "Solver Highlight Through Walls");
            boolean pressedOff = pressRow(c, "BoulderSolverTab", "Solver Highlight: ");
            c.check(pressed && pressedOff, "the Boulder rows did not take clicks");
            Object esp = Mod.cfg(cfgCls);
            Object boulder = Mod.enumValue(cfgCls + "$Solver", "BOULDER");
            Object quiz = Mod.enumValue(cfgCls + "$Solver", "QUIZ");
            boolean bTw = (Boolean) Mod.call(esp, "isThroughWalls", boulder);
            boolean bOn = (Boolean) Mod.call(esp, "isEnabled", boulder);
            boolean qTw = (Boolean) Mod.call(esp, "isThroughWalls", quiz);
            boolean qOn = (Boolean) Mod.call(esp, "isEnabled", quiz);
            c.note("after Boulder's two rows: Boulder through walls " + bTw + ", highlight " + bOn + "; Quiz " + qTw + ", " + qOn);
            c.check(!bTw && !bOn, "Boulder's rows did not change Boulder (through walls " + bTw + ", highlight " + bOn + ")");
            c.check(qTw && qOn, "Boulder's rows changed Quiz as well (through walls " + qTw + ", highlight " + qOn + ")");
            // The renderer honours the solver's own switch.
            boolean offBoulder = (Boolean) Mod.staticCall("puzzlesolvers.SolverEspRender", "off", boulder);
            boolean offQuiz = (Boolean) Mod.staticCall("puzzlesolvers.SolverEspRender", "off", quiz);
            c.check(offBoulder && !offQuiz, "the renderer's gate is Boulder " + offBoulder + ", Quiz " + offQuiz + " (want true / false)");
            // Saved and reloaded.
            Mod.call(esp, "save");
            Mod.staticCall(cfgCls, "load");
            Object esp2 = Mod.cfg(cfgCls);
            boolean back = (Boolean) Mod.call(esp2, "isEnabled", Mod.enumValue(cfgCls + "$Solver", "BOULDER"));
            c.check(!back, "Boulder's Highlight did not survive a reload from disk");
            // ---- an old shared file starts every solver on its values ----
            Files.writeString(file, "{\"throughWalls\":false,\"enabled\":false,\"waypointStyle\":\"FULL\"}", StandardCharsets.UTF_8);
            Mod.staticCall(cfgCls, "load");
            Object old = Mod.cfg(cfgCls);
            int wrong = 0;
            for (Object sv : Mod.cls(cfgCls + "$Solver").getEnumConstants()) {
                boolean on = (Boolean) Mod.call(old, "isEnabled", sv);
                boolean tw = (Boolean) Mod.call(old, "isThroughWalls", sv);
                String style = String.valueOf(Mod.call(old, "getWaypointStyle", sv));
                if (on || tw || !style.equals("FULL")) {
                    wrong++;
                }
            }
            c.check(wrong == 0, wrong + " solver(s) did not start on the old shared values (off / off / Full Block)");
        } finally {
            restore(file, saved);
            c.onClient(mc -> {
                Mod.staticCall(cfgCls, "load");
                for (int i = 0; i < SOLVERS.length; i++) {
                    Mod.call(Mod.cfg(SOLVERS[i][1]), "setEnabled", were.get(i));
                }
                return null;
            });
        }
    }

    // =================================================================================================================
    // 34 Termism menu
    // =================================================================================================================

    static void termismMenu(UiCase c) {
        Screen s = c.onClient(mc -> {
            try {
                Screen t = (Screen) Mod.cls("termism.TermismMenuScreen").getConstructor(Screen.class).newInstance((Object) null);
                McCompat.setScreen(mc, t);
                return t;
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(UiCase.describe(e), e);
            }
        });
        try {
            c.ticks(4);
            List<AbstractWidget> ws = c.onClient(mc -> {
                List<AbstractWidget> out = new ArrayList<>();
                for (var child : s.children()) {
                    if (child instanceof AbstractWidget w) {
                        out.add(w);
                    }
                }
                out.sort(java.util.Comparator.comparingInt(AbstractWidget::getY));
                return out;
            });
            c.check(ws.size() >= 8, "the Termism menu has only " + ws.size() + " buttons");
            AbstractWidget random = ws.get(0);
            AbstractWidget firstTerm = ws.get(1);
            AbstractWidget done = ws.get(ws.size() - 1);
            AbstractWidget lastTerm = ws.get(ws.size() - 2);
            int gapTop = firstTerm.getY() - (random.getY() + random.getHeight());
            int gapDone = done.getY() - (lastTerm.getY() + lastTerm.getHeight());
            c.note("Random '" + ModScreenDriver.label(random) + "', Done '" + ModScreenDriver.label(done) + "'; gap Random to the first terminal "
                    + gapTop + ", last terminal to Done " + gapDone);
            c.check(gapTop >= 12, "Random is only " + gapTop + " units above the terminal buttons (was 6)");
            c.check(gapDone >= 16, "Done is only " + gapDone + " units below the last terminal (was 10)");
            // The explanatory line sat across the panel at 126 above the centre; a row of pixels across it there must now be flat.
            BufferedImage img = shot(c, "menu");
            double f = c.onClient(mc -> ((Number) Mod.staticCall("hud.AutoScale", "appliedFactor", s)).doubleValue());
            double k = img.getWidth() / (double) c.onClient(mc -> mc.getWindow().getGuiScaledWidth());
            int cx = s.width / 2;
            int cy = s.height / 2;
            int busy = 0;
            for (int gy = cy - 126; gy < cy - 117; gy++) {
                int ya = (int) Math.round(gy * f * k);
                int lo = 255;
                int hi = 0;
                for (int gx = cx - 124; gx < cx - 113; gx++) {
                    for (int dx = 0; dx < Math.max(1, (int) Math.round(f * k)); dx++) {
                        int v = lum(img, (int) Math.round(gx * f * k) + dx, ya);
                        lo = Math.min(lo, v);
                        hi = Math.max(hi, v);
                    }
                }
                if (hi - lo > 40) {
                    busy++;
                }
            }
            c.note("rows with something drawn in the strip where the line was: " + busy);
            c.check(busy == 0, busy + " pixel rows of the old explanatory line's strip are not blank");
        } finally {
            closeScreen(c);
        }
    }

    // =================================================================================================================
    // 35 Spotify Lyrics
    // =================================================================================================================

    static void spotifyName(UiCase c) {
        List<String> names = leafNames();
        c.check(names.contains("Spotify Lyrics"), "no 'Spotify Lyrics' tab in the menu: " + names);
        c.check(!names.contains("Spotify Mod"), "the 'Spotify Mod' tab name is still in the menu");
        boolean was = (Boolean) Mod.field("spotify.SpotifyLyricsFeature", "enabled");
        try {
            Mod.setField("spotify.SpotifyLyricsFeature", "enabled", true);
            String problem = (String) Mod.staticCall("spotify.SpotifyLyricsFeature", "sourceProblem");
            c.note("Spotify's own status on this machine: '" + problem + "'");
            c.check(!problem.isEmpty(), "Spotify is running here, so the 'not running' line could not show before either - "
                    + "this case proves nothing on this machine");
            List<String> l = labels(c, "SpotifyTab");
            c.note("Spotify Lyrics rows: " + l);
            c.check(count(l, "not running") == 0 && count(l, "Spotify is") == 0, "the drawer still shows the status line: " + l);
            c.check(has(l, "Mod Enabled") || has(l, "Chat Destination"), "the probe did not build the Spotify drawer: " + l);
        } finally {
            Mod.setField("spotify.SpotifyLyricsFeature", "enabled", was);
        }
    }

    // =================================================================================================================
    // 36 Peek Chat: Toggle / Hold, the wheel scrolls the chat
    // =================================================================================================================

    private static boolean peekOpen(Minecraft mc) {
        Screen s = McCompat.screen(mc);
        return s != null && s.getClass().getName().endsWith(".peekchat.PeekChatScreen");
    }

    static void peekModes(UiCase c) {
        Object cfg = Mod.cfg("peekchat.PeekChatConfig");
        boolean wasEnabled = (Boolean) Mod.call(cfg, "isEnabled");
        int wasKey = (Integer) Mod.call(cfg, "getKeyCode");
        String feature = "peekchat.PeekChatFeature";
        try {
            c.onClient(mc -> {
                Mod.call(cfg, "setEnabled", true);
                Mod.call(cfg, "setKeyCode", KEY);
                Mod.call(cfg, "setHoldToPeek", false);
                McCompat.setScreen(mc, null);
                return null;
            });
            List<String> l = labels(c, "PeekChatTab");
            c.check(has(l, "Peek Mode: Toggle Peek"), "the Peek Chat tab has no 'Peek Mode: Toggle Peek' row: " + l);
            // The real row flips it.
            boolean pressed = pressRow(c, "PeekChatTab", "Peek Mode");
            boolean hold = c.onClient(mc -> (Boolean) Mod.call(cfg, "isHoldToPeek"));
            c.check(pressed && hold, "a click on the Peek Mode row did not switch to Hold To Peek");
            c.ticks(2);

            // ---- Hold: open while the key is held, closed the tick it is let go ----
            Mod.setField(feature, "keyHeldForTest", Boolean.TRUE);
            c.ticks(3);
            boolean openHeld = c.onClient(Doc1018MenuHudCases::peekOpen);
            Mod.setField(feature, "keyHeldForTest", Boolean.FALSE);
            c.ticks(3);
            boolean openReleased = c.onClient(Doc1018MenuHudCases::peekOpen);
            c.note("Hold To Peek: open while held " + openHeld + ", open after release " + openReleased);
            c.check(openHeld, "Hold To Peek did not open the chat while the key was held");
            c.check(!openReleased, "Hold To Peek left the chat open after the key was released");

            // ---- the wheel scrolls the chat and never the hotbar, while it is open ----
            c.onClient(mc -> {
                Object chat = Mod.staticCall("compat.McCompat", "chat", mc);
                for (int i = 1; i <= 80; i++) {
                    Mod.call(chat, "addClientSystemMessage", Component.literal("§7peek line " + i));
                }
                return null;
            });
            Mod.setField(feature, "keyHeldForTest", Boolean.TRUE);
            c.ticks(3);
            int slot0 = c.onClient(mc -> mc.player.getInventory().getSelectedSlot());
            int pos0 = c.onClient(mc -> (Integer) Mod.field(Mod.staticCall("compat.McCompat", "chat", mc), "chatScrollbarPos"));
            c.ctx().getInput().scroll(2.0);
            c.ticks(2);
            c.ctx().getInput().scroll(-1.0);
            c.ticks(2);
            c.ctx().getInput().scroll(3.0);
            c.ticks(2);
            int slot1 = c.onClient(mc -> mc.player.getInventory().getSelectedSlot());
            int pos1 = c.onClient(mc -> (Integer) Mod.field(Mod.staticCall("compat.McCompat", "chat", mc), "chatScrollbarPos"));
            c.note("wheel over Peek Chat: hotbar slot " + slot0 + " -> " + slot1 + ", chat scroll " + pos0 + " -> " + pos1);
            c.check(slot0 == slot1, "the wheel moved the hotbar from slot " + slot0 + " to " + slot1);
            c.check(pos1 > pos0, "the wheel did not scroll the chat (" + pos0 + " -> " + pos1 + ")");
            c.check(c.onClient(Doc1018MenuHudCases::peekOpen), "Peek Chat closed while scrolling in Hold To Peek");
            Mod.setField(feature, "keyHeldForTest", Boolean.FALSE);
            c.ticks(3);

            // ---- Toggle: opens on the press, stays after release, a second press closes ----
            c.onClient(mc -> {
                Mod.call(cfg, "setHoldToPeek", false);
                return null;
            });
            Mod.setField(feature, "keyHeldForTest", Boolean.TRUE);
            c.ticks(3);
            Mod.setField(feature, "keyHeldForTest", Boolean.FALSE);
            c.ticks(3);
            boolean stays = c.onClient(Doc1018MenuHudCases::peekOpen);
            c.ctx().getInput().pressKey(KEY);
            c.ticks(3);
            boolean closed = !c.onClient(Doc1018MenuHudCases::peekOpen);
            c.note("Toggle Peek: still open after release " + stays + ", closed by a second press " + closed);
            c.check(stays, "Toggle Peek closed when the key was released");
            c.check(closed, "Toggle Peek did not close on a second press");
        } finally {
            Mod.setField(feature, "keyHeldForTest", null);
            c.onClient(mc -> {
                Mod.call(cfg, "setHoldToPeek", false);
                Mod.call(cfg, "setKeyCode", wasKey);
                Mod.call(cfg, "setEnabled", wasEnabled);
                McCompat.setScreen(mc, null);
                return null;
            });
        }
    }

    // =================================================================================================================
    // 37 Voice To Text: status HUD and the download percent
    // =================================================================================================================

    static void voiceStatus(UiCase c) throws Exception {
        Object cfg = Mod.cfg("voicetotext.VoiceToTextConfig");
        boolean wasEnabled = (Boolean) Mod.call(cfg, "isEnabled");
        String feature = "voicetotext.VoiceToTextFeature";
        String overlay = "notify.ModOverlayMessage";
        try {
            c.onClient(mc -> {
                Mod.call(cfg, "setEnabled", true);
                return null;
            });
            List<String> l = labels(c, "VoiceToTextTab");
            c.check(has(l, "Show Status On HUD"), "the Voice To Text tab has no Show Status On HUD row: " + l);
            // Off by a real click on the row.
            boolean pressed = pressRow(c, "VoiceToTextTab", "Show Status On HUD");
            boolean shown = c.onClient(mc -> (Boolean) Mod.call(cfg, "isShowStatusHud"));
            c.check(pressed && !shown, "a click on Show Status On HUD did not turn it off");
            c.onClient(mc -> {
                Mod.staticCall(overlay, "show", "baseline", 60000L);
                Mod.staticCall(feature, "overlay", "[Voice] Listening...", 5000L);
                return null;
            });
            String hidden = c.onClient(mc -> (String) Mod.staticCall(overlay, "current"));
            c.check("baseline".equals(hidden), "with Show Status On HUD off the line still drew: '" + hidden + "'");
            c.onClient(mc -> {
                Mod.staticCall(feature, "overlay", "§c[Voice] No compatible microphone found.", 5000L);
                return null;
            });
            String failure = c.onClient(mc -> (String) Mod.staticCall(overlay, "current"));
            c.check(failure != null && failure.contains("No compatible microphone"),
                    "a failure line was hidden along with the rest: '" + failure + "'");
            c.onClient(mc -> {
                Mod.call(cfg, "setShowStatusHud", true);
                Mod.staticCall(feature, "overlay", "[Voice] Listening...", 5000L);
                return null;
            });
            String back = c.onClient(mc -> (String) Mod.staticCall(overlay, "current"));
            c.check(back != null && back.contains("Listening"), "with it back on the line did not draw: '" + back + "'");

            // ---- the download percent ----
            c.onClient(mc -> {
                LongConsumer progress = (LongConsumer) Mod.staticCall(feature, "downloadProgress", "speech model", 1000L);
                progress.accept(370L);
                return null;
            });
            c.ticks(3);
            String pct = c.onClient(mc -> (String) Mod.staticCall(overlay, "current"));
            c.note("download line at 370/1000: '" + pct + "'");
            c.check(pct != null && pct.contains("Downloading speech model: 37%"), "the progress line reads '" + pct + "', want 37%");
            // The shared downloader reports a running total up to the real size.
            List<Long> seen = new ArrayList<>();
            Path tmp = Files.createTempFile("vtt-progress", ".bin");
            try {
                Mod.staticCall("util.BoundedDownload", "toFile", new ByteArrayInputStream(new byte[300_000]), tmp, 1_000_000L, "test",
                        (LongConsumer) seen::add);
                c.note("BoundedDownload progress callbacks: " + seen.size() + ", last " + (seen.isEmpty() ? -1 : seen.get(seen.size() - 1)));
                c.check(!seen.isEmpty() && seen.get(seen.size() - 1) == 300_000L, "the downloader's progress did not reach the size: " + seen);
                boolean up = true;
                for (int i = 1; i < seen.size(); i++) {
                    up &= seen.get(i) > seen.get(i - 1);
                }
                c.check(up, "the downloader's progress is not increasing: " + seen);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } finally {
            c.onClient(mc -> {
                Mod.call(cfg, "setShowStatusHud", true);
                Mod.call(cfg, "setEnabled", wasEnabled);
                return null;
            });
        }
    }

    // =================================================================================================================
    // 38 Inventory HUD: default place, no black box
    // =================================================================================================================

    private static final String INV_HUD = "inventoryhud.InventoryHudConfig";

    static void inventoryHudDefault(UiCase c) {
        Object cfg = Mod.cfg(INV_HUD);
        boolean wasEnabled = (Boolean) Mod.call(cfg, "isEnabledRaw");
        Object hud = Mod.cfg("hud.HudConfig");
        try {
            c.onClient(mc -> {
                Mod.call(cfg, "setEnabled", true);
                Map<?, ?> positions = (Map<?, ?>) Mod.field(hud, "positions");
                positions.remove("inventory_hud");
                Map<?, ?> scales = (Map<?, ?>) Mod.field(hud, "scales");
                scales.remove("inventory_hud");
                McCompat.setScreen(mc, null);
                return null;
            });
            c.ticks(3);
            double[] r = c.onClient(mc -> {
                Object el = Mod.field("inventoryhud.InventoryHudFeature$InventoryHudElement", "INSTANCE");
                int[] p = (int[]) Mod.staticCall("hud.HudElementRegistry", "resolvePosition", el);
                float scale = ((Number) Mod.staticCall("hud.HudElementRegistry", "resolveScale", el)).floatValue();
                int w = (Integer) Mod.call(el, "width");
                int h = (Integer) Mod.call(el, "height");
                return new double[]{p[0], p[1], scale, w, h, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight()};
            });
            double boxW = r[3] * r[2];
            double boxH = r[4] * r[2];
            double midX = r[0] + boxW / 2;
            double bottom = r[1] + boxH;
            double aboveHotbar = r[6] - 22 - bottom;
            c.note(String.format("Inventory HUD default: x %.0f y %.0f, box %.0f x %.0f, screen %.0f x %.0f; centre %.1f vs %.1f, "
                    + "bottom edge %.1f above the hotbar", r[0], r[1], boxW, boxH, r[5], r[6], midX, r[5] / 2, aboveHotbar));
            c.check(Math.abs(midX - r[5] / 2) <= 1.5, "the panel is not centred over the hotbar (centre " + midX + ", screen centre " + r[5] / 2 + ")");
            c.check(aboveHotbar >= 20 && aboveHotbar <= 90, "the panel's bottom edge is " + aboveHotbar + " units above the hotbar, want a little way up (20 to 90)");
        } finally {
            c.onClient(mc -> {
                Mod.call(cfg, "setEnabled", wasEnabled);
                return null;
            });
        }
    }

    static void inventoryHudOpacity(UiCase c) {
        Object cfg = Mod.cfg(INV_HUD);
        boolean wasEnabled = (Boolean) Mod.call(cfg, "isEnabledRaw");
        Object bg = Mod.call(cfg, "getBackground");
        int wasOpacity = (Integer) Mod.call(cfg, "getBackgroundOpacity");
        Object hud = Mod.cfg("hud.HudConfig");
        try {
            c.onClient(mc -> {
                Mod.call(cfg, "setBackground", Mod.enumValue("inventoryhud.InventoryHudConfig$Background", "NONE"));
                Mod.call(cfg, "setBackgroundOpacity", 20);
                Map<?, ?> positions = (Map<?, ?>) Mod.field(hud, "positions");
                positions.remove("inventory_hud");
                McCompat.setScreen(mc, null);
                Mod.call(cfg, "setEnabled", false);
                return null;
            });
            c.ticks(4);
            BufferedImage off = shot(c, "hud-off");
            c.onClient(mc -> {
                Mod.call(cfg, "setEnabled", true);
                return null;
            });
            c.ticks(4);
            BufferedImage on = shot(c, "hud-on-opacity-20");
            double[] r = c.onClient(mc -> {
                Object el = Mod.field("inventoryhud.InventoryHudFeature$InventoryHudElement", "INSTANCE");
                int[] p = (int[]) Mod.staticCall("hud.HudElementRegistry", "resolvePosition", el);
                float scale = ((Number) Mod.staticCall("hud.HudElementRegistry", "resolveScale", el)).floatValue();
                return new double[]{p[0], p[1], (Integer) Mod.call(el, "width") * scale, (Integer) Mod.call(el, "height") * scale,
                        mc.getWindow().getGuiScaledWidth()};
            });
            double k = on.getWidth() / r[4];
            long sum = 0;
            int n = 0;
            for (int y = (int) (r[1] * k) + 2; y < (int) ((r[1] + r[3]) * k) - 2; y += 2) {
                for (int x = (int) (r[0] * k) + 2; x < (int) ((r[0] + r[2]) * k) - 2; x += 2) {
                    sum += lum(off, x, y) - lum(on, x, y);
                    n++;
                }
            }
            double drop = n == 0 ? 0 : sum / (double) n;
            c.note(String.format("mean brightness change over the panel's box at opacity 20 and no background: %.1f levels (%d samples)", drop, n));
            c.check(n > 100, "the probe sampled only " + n + " pixels");
            c.check(drop < 8, String.format("the panel's box is %.1f brightness levels darker with the Inventory HUD on at 20%% opacity - the black box", drop));
        } finally {
            c.onClient(mc -> {
                Mod.call(cfg, "setBackground", bg);
                Mod.call(cfg, "setBackgroundOpacity", wasOpacity);
                Mod.call(cfg, "setEnabled", wasEnabled);
                return null;
            });
        }
    }

    // =================================================================================================================
    // 39 Pet Wheel: pages
    // =================================================================================================================

    private static Object pet(String id, String name, int level) {
        try {
            var ctor = Mod.cls("petwheel.PetEntry").getConstructors()[0];
            return ctor.newInstance(id, name, "LEGENDARY", level, null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not make a PetEntry: " + UiCase.describe(e), e);
        }
    }

    @SuppressWarnings("unchecked")
    static void petWheelPages(UiCase c) throws Exception {
        String cfgCls = "petwheel.PetWheelConfig";
        Path file = (Path) Mod.field(cfgCls, "CONFIG_PATH");
        byte[] saved = snapshot(file);
        Object cfg = Mod.cfg(cfgCls);
        try {
            c.onClient(mc -> {
                Mod.call(cfg, "setEnabled", true);
                return null;
            });
            List<String> l = labels(c, "PetWheelTab");
            c.note("Pet Wheel rows: " + l);
            c.check(has(l, "Add Page") && has(l, "Delete Page") && has(l, "Page: 1/"), "the Pet Wheel tab has no page rows (Page / Add Page / Delete Page): " + l);

            // Page 1 has two pets; Add Page (a real click) makes an empty page 2, which gets one.
            c.onClient(mc -> {
                for (Object p : new Object[]{pet("u-1", "Ender Dragon", 100), pet("u-2", "Golden Dragon", 200), pet("u-3", "Black Cat", 50)}) {
                    Mod.call(cfg, "recordSeenPet", p);
                }
                Mod.call(cfg, "addToWheel", "u-1");
                Mod.call(cfg, "addToWheel", "u-2");
                return null;
            });
            boolean added = pressRow(c, "PetWheelTab", "Add Page");
            int pages = c.onClient(mc -> (Integer) Mod.call(cfg, "getPageCount"));
            int index = c.onClient(mc -> (Integer) Mod.call(cfg, "getPageIndex"));
            c.check(added && pages == 2 && index == 1, "Add Page gave " + pages + " page(s), current " + index + " (want 2 and 1)");
            int emptyNew = c.onClient(mc -> ((List<?>) Mod.call(cfg, "getWheelPets")).size());
            c.check(emptyNew == 0, "the new page is not empty (" + emptyNew + " pets)");
            c.onClient(mc -> {
                Mod.call(cfg, "addToWheel", "u-3");
                Mod.call(cfg, "setPageName", 1, "Mining");
                Mod.call(cfg, "setPageIndex", 0);
                return null;
            });

            // ---- the wheel itself: scroll and Left/Right change page ----
            c.onClient(mc -> {
                Mod.staticCall("petwheel.PetWheelFeature", "openWheel", mc, cfg);
                return null;
            });
            c.ticks(3);
            Screen wheel = c.onClient(McCompat::screen);
            c.check(wheel != null && wheel.getClass().getSimpleName().equals("PetWheelScreen"), "the wheel did not open: " + wheel);
            int onWheel0 = c.onClient(mc -> ((List<?>) Mod.field(wheel, "allPets")).size());
            c.ctx().getInput().scroll(-1.0);
            c.ticks(3);
            int idxScroll = c.onClient(mc -> (Integer) Mod.call(cfg, "getPageIndex"));
            int onWheel1 = c.onClient(mc -> ((List<?>) Mod.field(wheel, "allPets")).size());
            c.note("wheel: page 1 shows " + onWheel0 + " pets; after a scroll page " + (idxScroll + 1) + " shows " + onWheel1);
            c.check(onWheel0 == 2 && idxScroll == 1 && onWheel1 == 1, "scrolling the wheel did not switch to the Mining page (" + onWheel0 + " pets, page " + (idxScroll + 1) + ", " + onWheel1 + " pets)");
            c.ctx().getInput().pressKey(263); // Left
            c.ticks(3);
            int idxLeft = c.onClient(mc -> (Integer) Mod.call(cfg, "getPageIndex"));
            c.ctx().getInput().pressKey(262); // Right
            c.ticks(3);
            int idxRight = c.onClient(mc -> (Integer) Mod.call(cfg, "getPageIndex"));
            c.check(idxLeft == 0 && idxRight == 1, "Left/Right on the wheel went to page " + (idxLeft + 1) + " then " + (idxRight + 1));
            closeScreen(c);

            // ---- saved: a reload keeps pages, names, pets and the page it was on ----
            c.onClient(mc -> {
                Mod.call(cfg, "save");
                Mod.staticCall(cfgCls, "load");
                return null;
            });
            Object back = c.onClient(mc -> Mod.cfg(cfgCls));
            int bp = c.onClient(mc -> (Integer) Mod.call(back, "getPageCount"));
            int bi = c.onClient(mc -> (Integer) Mod.call(back, "getPageIndex"));
            String bn = c.onClient(mc -> (String) Mod.call(back, "getPageName", 1));
            int bpets = c.onClient(mc -> ((List<?>) Mod.call(back, "getWheelPets")).size());
            c.note("after a reload: " + bp + " pages, on page " + (bi + 1) + ", page 2 is '" + bn + "', current page has " + bpets + " pet(s)");
            c.check(bp == 2 && bi == 1 && "Mining".equals(bn) && bpets == 1, "pages did not survive a reload");

            // ---- an old file (one wheelPets list) is page 1 ----
            Files.writeString(file, "{\"enabled\":true,\"wheelPets\":[{\"uuid\":\"old-1\",\"name\":\"Wolf\",\"tier\":\"EPIC\",\"level\":10},"
                    + "{\"uuid\":\"old-2\",\"name\":\"Rock\",\"tier\":\"RARE\",\"level\":20}]}", StandardCharsets.UTF_8);
            c.onClient(mc -> {
                Mod.staticCall(cfgCls, "load");
                return null;
            });
            Object old = c.onClient(mc -> Mod.cfg(cfgCls));
            int op = c.onClient(mc -> (Integer) Mod.call(old, "getPageCount"));
            int opets = c.onClient(mc -> ((List<?>) Mod.call(old, "getWheelPets")).size());
            c.check(op == 1 && opets == 2, "an old file loaded as " + op + " page(s) with " + opets + " pets, want 1 and 2");
            // Delete never removes the last page.
            c.onClient(mc -> {
                Mod.call(old, "removeCurrentPage");
                return null;
            });
            int still = c.onClient(mc -> (Integer) Mod.call(old, "getPageCount"));
            c.check(still == 1, "Delete Page removed the only page");
        } finally {
            restore(file, saved);
            c.onClient(mc -> {
                Mod.staticCall(cfgCls, "load");
                McCompat.setScreen(mc, null);
                return null;
            });
        }
    }

    // =================================================================================================================
    // 40 Dungeon Sim keybinds: no "Dungeon Sim only" line
    // =================================================================================================================

    static void simKeybindsText(UiCase c) {
        List<String> l = labels(c, "SimKeybindsTab");
        c.note("Sim keybind rows: " + l);
        c.check(count(l, "Dungeon Sim only") == 0, "the Sim keybinds still show the 'Dungeon Sim only' line: " + l);
        c.check(has(l, "Next Room") && has(l, "Previous Room"), "the probe did not build the Sim keybinds: " + l);
    }

    // =================================================================================================================
    // 41 Performance HUD: colours
    // =================================================================================================================

    @SuppressWarnings("unchecked")
    static void performanceColors(UiCase c) throws Exception {
        String cfgCls = "lagdisplay.LagDisplayConfig";
        Path file = (Path) Mod.field(cfgCls, "CONFIG_PATH");
        byte[] saved = snapshot(file);
        Object cfg = Mod.cfg(cfgCls);
        try {
            c.onClient(mc -> {
                Mod.call(cfg, "setEnabled", true);
                Mod.call(cfg, "setShowTps", true);
                Mod.call(cfg, "setShowPing", true);
                Mod.call(cfg, "setShowFps", true);
                Mod.call(cfg, "setShowCps", true);
                Mod.call(cfg, "setColorByValue", true);
                return null;
            });
            List<String> l = labels(c, "LagDisplayTab");
            for (String want : new String[]{"Good Colour", "Okay Colour", "Bad Colour", "Awful Colour", "Text Colour"}) {
                c.check(has(l, want), "the Performance HUD tab has no '" + want + "' row: " + l);
            }
            int good = 0xFF0A0B01;
            int ok = 0xFF0A0B02;
            int bad = 0xFF0A0B03;
            int awful = 0xFF0A0B04;
            int text = 0xFF0A0B05;
            c.onClient(mc -> {
                Mod.call(cfg, "setColorGood", good);
                Mod.call(cfg, "setColorOk", ok);
                Mod.call(cfg, "setColorBad", bad);
                Mod.call(cfg, "setColorAwful", awful);
                Mod.call(cfg, "setColorText", text);
                return null;
            });
            Class<?> el = Mod.cls("lagdisplay.LagDisplayFeature$LagHudElement");
            Method lines = el.getDeclaredMethod("lines");
            lines.setAccessible(true);
            List<int[]> colored = new ArrayList<>();
            List<Object> out = c.onClient(mc -> {
                try {
                    return new ArrayList<>((List<Object>) lines.invoke(null));
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(UiCase.describe(e), e);
                }
            });
            List<Integer> colours = new ArrayList<>();
            List<String> texts = new ArrayList<>();
            for (Object line : out) {
                Method color = line.getClass().getDeclaredMethod("color");
                color.setAccessible(true);
                Method t = line.getClass().getDeclaredMethod("text");
                t.setAccessible(true);
                colours.add((Integer) color.invoke(line));
                texts.add((String) t.invoke(line));
            }
            c.note("lines " + texts + " colours " + colours.stream().map(x -> String.format("%08X", x)).toList());
            c.check(texts.size() == 4, "expected four lines (TPS, Ping, FPS, CPS), got " + texts);
            for (int i = 0; i < colours.size(); i++) {
                int col = colours.get(i);
                if (texts.get(i).startsWith("CPS")) {
                    c.check(col == text, "the CPS line is " + String.format("%08X", col) + ", want the Text Color");
                } else if (texts.get(i).endsWith("-")) {
                    // A value the game does not know yet ("TPS: -") is drawn plain, in the Text Color.
                    c.check(col == text, "the unknown-value line '" + texts.get(i) + "' is " + String.format("%08X", col) + ", want the Text Color");
                } else {
                    c.check(col == good || col == ok || col == bad || col == awful,
                            "'" + texts.get(i) + "' is " + String.format("%08X", col) + ", not one of the four custom value colours");
                }
            }
            // Color By Value off: every line in the Text Color (and the cached lines were rebuilt for the new colours).
            c.onClient(mc -> {
                Mod.call(cfg, "setColorByValue", false);
                return null;
            });
            List<Object> plain = c.onClient(mc -> {
                try {
                    return new ArrayList<>((List<Object>) lines.invoke(null));
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(UiCase.describe(e), e);
                }
            });
            int wrong = 0;
            for (Object line : plain) {
                Method color = line.getClass().getDeclaredMethod("color");
                color.setAccessible(true);
                if ((Integer) color.invoke(line) != text) {
                    wrong++;
                }
            }
            c.check(wrong == 0, wrong + " line(s) are not in the Text Color with Color By Value off");
            // Saved.
            c.onClient(mc -> {
                Mod.call(cfg, "save");
                Mod.staticCall(cfgCls, "load");
                return null;
            });
            Object back = c.onClient(mc -> Mod.cfg(cfgCls));
            int gb = c.onClient(mc -> (Integer) Mod.call(back, "getColorGood"));
            int tb = c.onClient(mc -> (Integer) Mod.call(back, "getColorText"));
            c.check(gb == good && tb == text, "the colours did not survive a reload: good " + String.format("%08X", gb) + ", text " + String.format("%08X", tb));
        } finally {
            restore(file, saved);
            c.onClient(mc -> {
                Mod.staticCall(cfgCls, "load");
                return null;
            });
        }
    }

    // =================================================================================================================
    // 42 Chat Hider: ban announcements
    // =================================================================================================================

    static void chatHiderBan(UiCase c) {
        String cfgCls = "chattidy.ChatTidyConfig";
        Object cfg = Mod.cfg(cfgCls);
        boolean wasOn = (Boolean) Mod.call(cfg, "getEnabledRaw");
        try {
            List<String> l = labels(c, "ChatHiderTab");
            c.check(has(l, "Hide Ban Announcements"), "the Chat Hider tab has no Hide Ban Announcements row: " + l);
            String[] watchdog = {
                    "[WATCHDOG ANNOUNCEMENT]",
                    "Watchdog has banned 7,500 players in the last 7 days.",
                    "Staff have banned an additional 1,892 in the last 7 days.",
                    "Blacklisted modifications are a bannable offense!",
                    "[WATCHDOG ANNOUNCEMENT]\nWatchdog has banned 6,120 players in the last 7 days.\nStaff have banned an additional 1,402 in the last 7 days.",
            };
            String[] others = {
                    "[MVP+] Bob: Watchdog has banned 7,500 players in the last 7 days.",
                    "Staff have banned me from the party",
                    "Watchdog has banned nobody",
                    "You can also join the Discord.",
            };
            c.onClient(mc -> {
                Mod.call(cfg, "setEnabled", true);
                Mod.call(cfg, "setHideBanAnnouncements", false);
                return null;
            });
            for (String w : watchdog) {
                c.check(allowed(c, w), "with the option OFF a line was hidden: " + w.replace("\n", " / "));
            }
            // On by the real row.
            boolean pressed = pressRow(c, "ChatHiderTab", "Hide Ban Announcements");
            boolean on = c.onClient(mc -> (Boolean) Mod.call(cfg, "getHideBanAnnouncementsRaw"));
            c.check(pressed && on, "a click on Hide Ban Announcements did not turn it on");
            for (String w : watchdog) {
                c.check(!allowed(c, w), "with the option ON the Watchdog line was shown: " + w.replace("\n", " / "));
            }
            for (String o : others) {
                c.check(allowed(c, o), "a line that is not the announcement was hidden: " + o);
            }
        } finally {
            c.onClient(mc -> {
                Mod.call(cfg, "setHideBanAnnouncements", false);
                Mod.call(cfg, "setEnabled", wasOn);
                return null;
            });
        }
    }

    private static boolean allowed(UiCase c, String text) {
        return c.onClient(mc -> (Boolean) Mod.staticCall("objecthider.ObjectHiderFeature", "allowsLine", Component.literal(text)));
    }

    // =================================================================================================================
    // 43 Party Commands defaults
    // =================================================================================================================

    static void partyCommandDefaults(UiCase c) throws Exception {
        String cfgCls = "partycommands.PartyCommandsConfig";
        Path file = (Path) Mod.field(cfgCls, "CONFIG_PATH");
        byte[] saved = snapshot(file);
        try {
            Files.deleteIfExists(file);
            c.onClient(mc -> {
                Mod.staticCall(cfgCls, "load");
                return null;
            });
            Object cfg = c.onClient(mc -> Mod.cfg(cfgCls));
            List<String> off = new ArrayList<>();
            int total = 0;
            for (Object cmd : Mod.cls(cfgCls + "$Command").getEnumConstants()) {
                total++;
                if (!(Boolean) Mod.call(cfg, "isOn", cmd)) {
                    off.add(((Enum<?>) cmd).name());
                }
            }
            c.note(total + " commands, off by default: " + off);
            c.check(total >= 20 && off.isEmpty(), "these commands start OFF: " + off);
            for (Object ch : Mod.cls(cfgCls + "$Channel").getEnumConstants()) {
                String n = ((Enum<?>) ch).name();
                boolean on = (Boolean) Mod.call(cfg, "isChannelOn", ch);
                boolean want = !(n.equals("GUILD") || n.equals("ALL"));
                c.check(on == want, "the " + n + " channel starts " + (on ? "ON" : "OFF") + ", want " + (want ? "ON" : "OFF"));
            }
            boolean master = (Boolean) Mod.call(cfg, "isEnabledRaw");
            c.check(!master, "the Party Commands switch itself starts ON");
        } finally {
            restore(file, saved);
            c.onClient(mc -> {
                Mod.staticCall(cfgCls, "load");
                return null;
            });
        }
    }

    // =================================================================================================================
    // 44 Inventory Theme settings
    // =================================================================================================================

    static void inventoryThemeSettings(UiCase c) throws Exception {
        String cfgCls = "inventorytheme.InventoryThemeConfig";
        Path file = (Path) Mod.field(cfgCls, "CONFIG_PATH");
        Path themes = (Path) Mod.staticCall("gui.ScreenThemes", "path");
        byte[] savedCfg = snapshot(file);
        byte[] savedThemes = snapshot(themes);
        Object cfg = Mod.cfg(cfgCls);
        try {
            c.onClient(mc -> {
                Mod.staticCall("gui.ScreenThemes", "set", "inventory_theme", "DEFAULT");
                Mod.call(cfg, "setEnabled", true);
                return null;
            });
            List<String> l = labels(c, "InventoryThemeTab");
            c.note("Inventory Theme rows: " + l);
            c.check(!l.isEmpty() && l.get(0).startsWith("Theme: Default"), "the first row is not 'Theme: Default': " + l);
            c.check(!has(l, "Inventory Theme") && !has(l, "Hide Potion Effects") && !has(l, "Preset") && !has(l, "Slot Colour")
                    && !has(l, "Accent"), "rows from before are still there: " + l);
            c.check(has(l, "Theme Hotbar") && has(l, "Applies To"), "Applies To / Theme Hotbar are missing: " + l);
            c.check(has(l, "Hotbar Scale"), "the Hotbar Scale slider is not with Theme Hotbar: " + l);
            c.check(!has(l, "Customise"), "Customize shows while the Theme is not Custom: " + l);

            // The card's own switch is the setting (the drawer has no row for it).
            Object tab = c.onClient(mc -> MenuACases.leaf("InventoryThemeTab"));
            List<?> masters = c.onClient(mc -> (List<?>) Mod.call(tab, "masterToggles", build(tab)));
            c.check(masters.size() == 1, "the card has " + masters.size() + " switch(es), want one");
            boolean was = (Boolean) Mod.call(cfg, "isEnabled");
            c.onClient(mc -> {
                ModScreenDriver.press((AbstractWidget) masters.get(0));
                return null;
            });
            boolean now = (Boolean) Mod.call(cfg, "isEnabled");
            c.check(now != was, "pressing the card's switch did not flip Inventory Theme (" + was + " -> " + now + ")");
            c.onClient(mc -> {
                Mod.call(cfg, "setEnabled", true);
                return null;
            });
            c.check((Boolean) Mod.call(cfg, "isHidePotionEffects"), "the potion effects panel is not hidden with the theme on");
            c.onClient(mc -> {
                Mod.call(cfg, "setEnabled", false);
                return null;
            });
            c.check(!(Boolean) Mod.call(cfg, "isHidePotionEffects"), "the potion effects panel is hidden with the theme off");
            c.onClient(mc -> {
                Mod.call(cfg, "setEnabled", true);
                return null;
            });

            // ---- Default follows the mod menu; a named theme and Vanilla differ; Custom shows Customize ----
            Object menuPal = c.onClient(mc -> Mod.staticCall("gui.OverlayThemes", "menu"));
            Object def = c.onClient(mc -> Mod.staticCall("inventorytheme.InventoryPalette", "fromMenu", menuPal));
            Object got = c.onClient(mc -> Mod.call(cfg, "getPalette"));
            c.check(def.equals(got), "on Default the palette is not the mod menu's theme");
            c.onClient(mc -> {
                Mod.call(cfg, "setChoice", "OCEAN");
                return null;
            });
            Object ocean = c.onClient(mc -> Mod.call(cfg, "getPalette"));
            Object oceanWant = c.onClient(mc -> Mod.staticCall("inventorytheme.InventoryPalette", "fromMenu",
                    Mod.staticCall("gui.OverlayThemes", "of", "inventory_theme")));
            c.check(ocean.equals(oceanWant) && !ocean.equals(def), "Ocean did not give Ocean's palette");
            c.onClient(mc -> {
                Mod.call(cfg, "setChoice", "VANILLA");
                return null;
            });
            Object vanilla = c.onClient(mc -> Mod.call(cfg, "getPalette"));
            c.check(vanilla.equals(Mod.field("inventorytheme.InventoryPalette", "DEFAULT")), "Vanilla did not give vanilla's greys");
            // The real Theme row steps through the choices and reaches Custom.
            c.onClient(mc -> {
                Mod.call(cfg, "setChoice", "DEFAULT");
                return null;
            });
            int steps = 0;
            while (steps < 20 && !(Boolean) Mod.call(cfg, "isCustom")) {
                pressRow(c, "InventoryThemeTab", "Theme:");
                steps++;
            }
            c.note("the Theme row reached Custom after " + steps + " click(s)");
            c.check((Boolean) Mod.call(cfg, "isCustom"), "clicking the Theme row never reached Custom");
            List<String> custom = labels(c, "InventoryThemeTab");
            c.check(has(custom, "Customise"), "a Customize row does not show on Custom: " + custom);
            boolean opened = pressRow(c, "InventoryThemeTab", "Customise");
            c.ticks(3);
            String screen = c.onClient(mc -> String.valueOf(McCompat.screen(mc)));
            c.note("after Customize: " + screen);
            c.check(opened && screen.contains("InventoryCustomizeScreen"), "Customize did not open the customize menu: " + screen);
            Screen cs = c.onClient(McCompat::screen);
            List<String> customRows = c.onClient(mc -> {
                List<String> out = new ArrayList<>();
                if (cs != null) {
                    for (var child : cs.children()) {
                        if (child instanceof AbstractWidget w) {
                            out.add(ModScreenDriver.label(w));
                        }
                    }
                }
                return out;
            });
            c.note("the customize menu: " + customRows);
            c.check(has(customRows, "Base:") && has(customRows, "Slot Colour Source") && has(customRows, "Accent Source")
                    && has(customRows, "Done"), "the customize menu is missing its rows: " + customRows);
            shot(c, "customize");
            closeScreen(c);

            // ---- an old file's preset is carried over, once ----
            for (String[] t : new String[][]{{"DEFAULT", "VANILLA"}, {"DARK", "DARK"}, {"MOD_THEME", "AMBER"}, {"CUSTOM", "INVENTORY_CUSTOM"}}) {
                c.onClient(mc -> {
                    Mod.staticCall("gui.ScreenThemes", "set", "inventory_theme", "DEFAULT");
                    return null;
                });
                Files.writeString(file, "{\"enabled\":true,\"preset\":\"" + t[0] + "\",\"hidePotionEffects\":true}", StandardCharsets.UTF_8);
                c.onClient(mc -> {
                    Mod.staticCall(cfgCls, "load");
                    return null;
                });
                String choice = c.onClient(mc -> (String) Mod.call(Mod.cfg(cfgCls), "getChoice"));
                c.check(t[1].equals(choice), "an old file with preset " + t[0] + " became the Theme '" + choice + "', want " + t[1]);
            }
            String rewritten = Files.readString(file);
            c.check(rewritten.contains("themeChoiceMigrated"), "the migrated file was not rewritten with its flag: " + rewritten);
            // A fresh install starts on Default.
            c.onClient(mc -> {
                Mod.staticCall("gui.ScreenThemes", "set", "inventory_theme", "DEFAULT");
                return null;
            });
            Files.deleteIfExists(file);
            c.onClient(mc -> {
                Mod.staticCall(cfgCls, "load");
                return null;
            });
            String fresh = c.onClient(mc -> (String) Mod.call(Mod.cfg(cfgCls), "getChoice"));
            c.check("DEFAULT".equals(fresh), "a fresh install starts on '" + fresh + "', want DEFAULT");
        } finally {
            restore(file, savedCfg);
            restore(themes, savedThemes);
            c.onClient(mc -> {
                Mod.staticCall("gui.ScreenThemes", "load");
                Mod.staticCall(cfgCls, "load");
                McCompat.setScreen(mc, null);
                return null;
            });
        }
    }

    // =================================================================================================================
    // 46 Ribbon Trail: only in Player Cosmetics
    // =================================================================================================================

    static void ribbonInCosmetics(UiCase c) {
        c.check(!hasLeaf("RibbonTrailTab"), "Ribbon Trail is still a card of its own: " + leafNames());
        c.check(!leafNames().contains("Ribbon Trail"), "a 'Ribbon Trail' card is still listed");
        c.check(hasLeaf("TrailTab"), "the squares Trail card is gone");
        Object tab = c.onClient(mc -> MenuACases.leaf("CosmeticsTab"));
        // The tab object is the menu's own, and an earlier case can leave its sections open (every one was, 2026-10-20):
        // close the Ribbon Trail section first so the case starts from the closed header.
        c.onClient(mc -> {
            for (AbstractWidget w : build(tab)) {
                if (ModScreenDriver.label(w).startsWith("▼ Ribbon Trail")) {
                    ModScreenDriver.press(w);
                }
            }
            return null;
        });
        List<AbstractWidget> closed = c.onClient(mc -> build(tab));
        List<String> before = new ArrayList<>();
        for (AbstractWidget w : closed) {
            before.add(ModScreenDriver.label(w));
        }
        c.note("Player Cosmetics rows (sections closed): " + before);
        c.check(has(before, "▶ Ribbon Trail"), "Player Cosmetics has no Ribbon Trail section: " + before);
        // Open it with a real click.
        AbstractWidget header = null;
        for (AbstractWidget w : closed) {
            if (ModScreenDriver.label(w).startsWith("▶ Ribbon Trail")) {
                header = w;
            }
        }
        if (header == null) {
            return;
        }
        AbstractWidget h = header;
        c.onClient(mc -> ModScreenDriver.press(h));
        List<String> open = c.onClient(mc -> {
            List<String> out = new ArrayList<>();
            for (AbstractWidget w : build(tab)) {
                out.add(ModScreenDriver.label(w));
            }
            return out;
        });
        c.note("with the Ribbon Trail section open: " + open);
        c.check(has(open, "Ribbon Trail:"), "the open section has no 'Ribbon Trail' switch: " + open);
        c.check(count(open, "Share Ribbon Trail") == 1, "'Share Ribbon Trail' shows " + count(open, "Share Ribbon Trail") + " times, want once (the top one)");
        c.check(count(open, "Show Others' Trails") == 1, "'Show Others' Trails' shows " + count(open, "Show Others' Trails") + " times, want once");
        c.onClient(mc -> {
            // Close it again for the next case.
            for (AbstractWidget w : build(tab)) {
                if (ModScreenDriver.label(w).startsWith("▼ Ribbon Trail")) {
                    ModScreenDriver.press(w);
                }
            }
            return null;
        });
    }
}
