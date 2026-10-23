package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.Report;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Screen sweep B (mod sweep-b, killer560 2026-10-13: "make the theme for each match the gui's theme. Then redo them"):
 * the standalone screens drawn in the menu's theme through {@code gui/ScreenChrome}, and each screen's own Screen Theme.
 * Every screen is opened under Amber and Light at 1920x1080 GUI 2 (Auto Scale on, as he plays), screenshotted, and its
 * panel pixel read where layout x Auto Scale puts the frame's inside: it must be the theme's own panel gradient (Light
 * bright, Amber dark), so a screen still drawing its old fixed colours fails. Then its main actions are pressed with REAL
 * clicks at the controls' drawn centres under Light and proved by the screen's or config's state:
 * <ul>
 *   <li>1020 /profit hub, Experimentation Table, Dungeon Profit: a card opens its tracker, Back returns, By Floor switches.</li>
 *   <li>1021 Storage Search: a click on the search well focuses the (borderless) box, Inv toggles the config.</li>
 *   <li>1022 Run Log: Kuudra switches the mode.</li>
 *   <li>1023 Inventory Sorter layouts: click slot 9 then slot 10 swaps them in the grid.</li>
 *   <li>1024 Custom Items: a click on a held item reaches the selection, Done closes.</li>
 *   <li>1025 Theme (sweep-a's ScreenThemes): the Storage Search tab's row cycles Theme Default -> Amber -> Dark -> Light (saved); with the
 *       menu on Amber the screen draws Light and the menu palette is still Amber; cycled on to Theme Default it follows
 *       the menu again. The Bazaar and AH also offer Inventory Theme, which draws them flat (bevel off).</li>
 * </ul>
 */
final class ScreenSweepBCases {

    private ScreenSweepBCases() {
    }

    private static final String[] THEMES = {"AMBER", "LIGHT"};

    // ---- shared ---------------------------------------------------------------------------------------------------

    /** Runs {@code body} at 1920x1080 GUI 2, Auto Scale on, menu theme Amber, and puts everything back. */
    private static void inShotWindow(UiCase c, ThrowingRunnable body) throws Exception {
        Object menuCfg = Mod.cfg("gui.MenuConfig");
        String oldTheme = ((Enum<?>) Mod.call(menuCfg, "getTheme")).name();
        int[] oldWindow = c.onClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight()});
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        Object hud = Mod.cfg("hud.HudConfig");
        boolean oldAuto = (Boolean) Mod.call(hud, "isAutoScale");
        try {
            c.onClient(mc -> {
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
            body.run();
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                Mod.call(Mod.cfg("gui.MenuConfig"), "setTheme", Mod.enumValue("gui.MenuTheme", oldTheme));
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

    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static void menuTheme(UiCase c, String theme) {
        c.onClient(mc -> {
            Mod.call(Mod.cfg("gui.MenuConfig"), "setTheme", Mod.enumValue("gui.MenuTheme", theme));
            return null;
        });
    }

    private static Screen open(UiCase c, Function<Screen, Screen> make, String simpleName) {
        c.onClient(mc -> {
            McCompat.setScreen(mc, make.apply(null));
            return null;
        });
        InvBatchCases.awaitScreen(c, simpleName);
        c.ticks(4);
        return InvBatchCases.screen(c);
    }

    private static Screen construct(String cls, Class<?>[] types, Object... args) {
        try {
            return (Screen) Mod.cls(cls).getConstructor(types).newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot construct " + cls, e);
        }
    }

    private static int intField(Object target, String... names) {
        for (String n : names) {
            try {
                return ((Number) Mod.field(target, n)).intValue();
            } catch (AssertionError ignored) {
                // next name
            }
        }
        throw new AssertionError("no field " + String.join("/", names) + " on " + target.getClass().getName());
    }

    /** The screen's frame {x, y, w, h} in layout units. */
    private static int[] frame(UiCase c, Screen s) {
        return c.onClient(mc -> new int[]{intField(s, "panelX"), intField(s, "panelY"), intField(s, "panelW", "PANEL_W"),
                intField(s, "panelH", "PANEL_H")});
    }

    /**
     * Screenshots the open screen as {@code label}, then reads the panel pixel just inside the frame's left edge at half
     * height and checks it against the theme's own panel gradient there. Returns the sampled RGB.
     */
    private static int shotAndPanel(UiCase c, Screen s, String label, String theme, List<String> shots) throws Exception {
        c.ticks(3);
        String name = c.name() + "-" + label;
        Path taken = c.ctx().takeScreenshot(Report.fileName(name));
        Path kept = Report.screenshot(name, taken);
        shots.add(String.valueOf(kept));
        BufferedImage img = javax.imageio.ImageIO.read((kept != null ? kept : taken).toFile());
        int[] f = frame(c, s);
        double[] map = c.onClient(mc -> new double[]{
                ((Number) Mod.staticCall("hud.AutoScale", "appliedFactor", s)).doubleValue(),
                mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight()});
        double lx = f[0] + 4;
        double ly = f[1] + f[3] / 2.0;
        int px = (int) Math.floor(lx * map[0] * img.getWidth() / map[1]);
        int py = (int) Math.floor(ly * map[0] * img.getHeight() / map[2]);
        int rgb = img.getRGB(px, py) & 0xFFFFFF;
        int[] pal = c.onClient(mc -> new int[]{(Integer) R.getStatic(R.cls("gui.MenuStyle"), "PANEL_TOP"),
                (Integer) R.getStatic(R.cls("gui.MenuStyle"), "PANEL_BOTTOM")});
        int want = mix(pal[0], pal[1], (ly - f[1] - 1) / Math.max(1.0, f[3] - 2.0)) & 0xFFFFFF;
        int d = dist(rgb, want);
        int sum = ((rgb >> 16) & 0xFF) + ((rgb >> 8) & 0xFF) + (rgb & 0xFF);
        c.note(String.format(Locale.ROOT, "%s %s: frame %d,%d %dx%d, factor %.3f, panel pixel (%d,%d) #%06X, theme gradient "
                + "#%06X there (max channel diff %d), shot %s", label, theme, f[0], f[1], f[2], f[3], map[0], px, py, rgb,
                want, d, kept));
        c.check(d <= 18, label + " " + theme + ": the panel pixel #" + Integer.toHexString(rgb)
                + " is not the theme's panel #" + Integer.toHexString(want));
        c.check("LIGHT".equals(theme) ? sum > 600 : sum < 150, label + " " + theme + ": the panel reads #"
                + Integer.toHexString(rgb) + " (sum " + sum + "), not a " + theme.toLowerCase(Locale.ROOT) + " panel");
        return rgb;
    }

    static int mix(int a, int b, double t) {
        int r = 0;
        for (int s = 0; s < 24; s += 8) {
            int ca = (a >>> s) & 0xFF;
            int cb = (b >>> s) & 0xFF;
            r |= ((int) Math.round(ca + (cb - ca) * t) & 0xFF) << s;
        }
        return r;
    }

    static int dist(int a, int b) {
        int d = 0;
        for (int s = 0; s < 24; s += 8) {
            d = Math.max(d, Math.abs(((a >>> s) & 0xFF) - ((b >>> s) & 0xFF)));
        }
        return d;
    }

    /** The first widget whose plain label starts with {@code prefix}, or a failed check. */
    private static AbstractWidget need(UiCase c, Screen s, String prefix) {
        AbstractWidget w = InvBatchCases.widget(c, s, prefix);
        c.check(w != null, "no '" + prefix + "' control on " + s.getClass().getSimpleName());
        return w;
    }

    /** Screens under each theme: a pixel each, and the two must differ. */
    private static void themed(UiCase c, String label, Function<Screen, Screen> make, String simpleName,
            List<String> shots) throws Exception {
        int[] got = new int[THEMES.length];
        for (int i = 0; i < THEMES.length; i++) {
            menuTheme(c, THEMES[i]);
            Screen s = open(c, make, simpleName);
            got[i] = shotAndPanel(c, s, label + "-" + THEMES[i].toLowerCase(Locale.ROOT), THEMES[i], shots);
        }
        c.check(dist(got[0], got[1]) > 60, label + ": Amber and Light drew the same panel #" + Integer.toHexString(got[0]));
    }

    // ---- 1020 profit -------------------------------------------------------------------------------------------------

    static void profit(UiCase c) throws Exception {
        List<String> shots = new ArrayList<>();
        Class<?>[] one = {Screen.class};
        inShotWindow(c, () -> {
            themed(c, "profit-hub", p -> construct("gui.profit.ProfitHubScreen", one, p), "ProfitHubScreen", shots);
            themed(c, "profit-etable", p -> construct("gui.profit.ExperimentsProfitScreen", one, p),
                    "ExperimentsProfitScreen", shots);
            themed(c, "croesus", p -> construct("croesus.CroesusTrackerScreen", new Class<?>[]{Screen.class,
                    Mod.cls("croesus.CroesusTrackerScreen$View")}, p,
                    Mod.enumValue("croesus.CroesusTrackerScreen$View", "OVERVIEW")), "CroesusTrackerScreen", shots);

            // Actions, under Light: a card opens its tracker, Back returns to the hub.
            menuTheme(c, "LIGHT");
            Screen hub = open(c, p -> construct("gui.profit.ProfitHubScreen", one, p), "ProfitHubScreen");
            AbstractWidget card = need(c, hub, "Experimentation Table");
            InvBatchCases.clickWidget(c, hub, card);
            InvBatchCases.awaitScreen(c, "ExperimentsProfitScreen");
            Screen et = InvBatchCases.screen(c);
            c.note("a real click on the Experimentation Table card opened " + et.getClass().getSimpleName());
            InvBatchCases.clickWidget(c, et, need(c, et, "< Back"));
            InvBatchCases.awaitScreen(c, "ProfitHubScreen");
            c.note("< Back returned to the hub");

            Screen cr = open(c, p -> construct("croesus.CroesusTrackerScreen", new Class<?>[]{Screen.class,
                    Mod.cls("croesus.CroesusTrackerScreen$View")}, p,
                    Mod.enumValue("croesus.CroesusTrackerScreen$View", "OVERVIEW")), "CroesusTrackerScreen");
            InvBatchCases.clickWidget(c, cr, need(c, cr, "By Floor"));
            Screen cr2 = InvBatchCases.screen(c);
            String view = c.onClient(mc -> String.valueOf(Mod.field(cr2, "view")));
            c.check("BY_FLOOR".equals(view), "By Floor left the Croesus view on " + view);
            c.note("a real click on By Floor switched the view to " + view);
        });
        c.note("shots: " + shots);
    }

    // ---- 1021 storage search -----------------------------------------------------------------------------------------

    static void storageSearch(UiCase c) throws Exception {
        List<String> shots = new ArrayList<>();
        Function<Screen, Screen> make = p -> construct("storagesearch.StorageSearchScreen",
                new Class<?>[]{Screen.class, String.class}, p, "");
        inShotWindow(c, () -> {
            themed(c, "storage-search", make, "StorageSearchScreen", shots);
            menuTheme(c, "LIGHT");
            Screen s = open(c, make, "StorageSearchScreen");
            EditBox box = InvBatchCases.editBox(c, s);
            c.check(box != null, "no search box");
            int[] well = c.onClient(mc -> new int[]{intField(s, "boxX"), intField(s, "boxY"), intField(s, "boxW"),
                    intField(s, "boxH")});
            c.onClient(mc -> {
                s.setFocused(null); // the screen opens with the box focused; clear it so the click has to focus it
                return null;
            });
            c.check(!c.onClient(mc -> box.isFocused()), "the search box is still focused after clearing the screen's focus");
            InvBatchCases.click(c, s, well[0] + well[2] / 2.0, well[1] + well[3] / 2.0);
            boolean focused = c.onClient(mc -> box.isFocused());
            c.check(focused, "a click on the search well did not focus the search box");
            c.note("a real click in the search well focused the box: " + focused);
            Object cfg = Mod.cfg("storagesearch.StorageSearchConfig");
            boolean before = (Boolean) Mod.call(cfg, "isIncludeInventory");
            try {
                InvBatchCases.clickWidget(c, s, need(c, s, "Inv:"));
                boolean after = (Boolean) Mod.call(cfg, "isIncludeInventory");
                c.check(after != before, "Inv did not toggle Include Inventory (" + before + " -> " + after + ")");
                c.note("a real click on Inv toggled Include Inventory " + before + " -> " + after);
            } finally {
                c.onClient(mc -> {
                    Mod.call(cfg, "setIncludeInventory", before);
                    Mod.call(cfg, "save");
                    return null;
                });
            }
        });
        c.note("shots: " + shots);
    }

    // ---- 1022 run log --------------------------------------------------------------------------------------------------

    static void runLog(UiCase c) throws Exception {
        List<String> shots = new ArrayList<>();
        Function<Screen, Screen> make = p -> construct("runsummary.RunLogScreen", new Class<?>[]{Screen.class}, p);
        inShotWindow(c, () -> {
            themed(c, "run-log", make, "RunLogScreen", shots);
            menuTheme(c, "LIGHT");
            Screen s = open(c, make, "RunLogScreen");
            InvBatchCases.clickWidget(c, s, need(c, s, "Kuudra"));
            Screen s2 = InvBatchCases.screen(c);
            String mode = c.onClient(mc -> String.valueOf(Mod.field(s2, "mode")));
            c.check("KUUDRA".equals(mode), "Kuudra left the Run Log on " + mode);
            c.note("a real click on Kuudra switched the mode to " + mode);
            shotAndPanel(c, s2, "run-log-kuudra-light", "LIGHT", shots);
        });
        c.note("shots: " + shots);
    }

    // ---- 1023 inventory sorter -------------------------------------------------------------------------------------------

    static void invsort(UiCase c) throws Exception {
        List<String> shots = new ArrayList<>();
        Function<Screen, Screen> make = p -> construct("invsort.InventorySorterScreen", new Class<?>[]{Screen.class}, p);
        ItemStack[] old = c.onClient(mc -> new ItemStack[]{mc.player.getInventory().getItem(9).copy(),
                mc.player.getInventory().getItem(10).copy()});
        try {
            c.onClient(mc -> {
                mc.player.getInventory().setItem(9, new ItemStack(Items.DIAMOND));
                mc.player.getInventory().setItem(10, new ItemStack(Items.EMERALD, 3));
                return null;
            });
            inShotWindow(c, () -> {
                themed(c, "inventory-sorter", make, "InventorySorterScreen", shots);
                menuTheme(c, "LIGHT");
                Screen s = open(c, make, "InventorySorterScreen");
                int[] a = c.onClient(mc -> (int[]) Mod.call(s, "slotPos", 9));
                int[] b = c.onClient(mc -> (int[]) Mod.call(s, "slotPos", 10));
                InvBatchCases.click(c, s, a[0] + 9, a[1] + 9);
                InvBatchCases.click(c, s, b[0] + 9, b[1] + 9);
                String at9 = c.onClient(mc -> ((ItemStack) Mod.call(s, "gridAt", 9)).getItem().toString());
                String at10 = c.onClient(mc -> ((ItemStack) Mod.call(s, "gridAt", 10)).getItem().toString());
                c.check(at9.contains("emerald") && at10.contains("diamond"), "slot 9 then slot 10 did not swap them: "
                        + at9 + " / " + at10);
                c.note("real clicks on slot 9 then 10 swapped the grid: 9 " + at9 + ", 10 " + at10);
            });
        } finally {
            c.onClient(mc -> {
                mc.player.getInventory().setItem(9, old[0]);
                mc.player.getInventory().setItem(10, old[1]);
                return null;
            });
        }
        c.note("shots: " + shots);
    }

    // ---- 1024 custom items ---------------------------------------------------------------------------------------------

    static void customItems(UiCase c) throws Exception {
        List<String> shots = new ArrayList<>();
        Function<Screen, Screen> make = p -> construct("armourdye.CustomItemsScreen", new Class<?>[]{Screen.class}, p);
        ItemStack old = c.onClient(mc -> mc.player.getInventory().getItem(0).copy());
        try {
            c.onClient(mc -> {
                mc.player.getInventory().setItem(0, new ItemStack(Items.LEATHER_CHESTPLATE));
                return null;
            });
            inShotWindow(c, () -> {
                themed(c, "custom-items", make, "CustomItemsScreen", shots);
                menuTheme(c, "LIGHT");
                Screen s = open(c, make, "CustomItemsScreen");
                int[] cell = c.onClient(mc -> (int[]) Mod.call(s, "cellPos", 31));
                InvBatchCases.click(c, s, cell[0] + 9, cell[1] + 9);
                Screen s2 = InvBatchCases.screen(c);
                String key = c.onClient(mc -> (String) Mod.call(s2, "selectedKey"));
                String status = c.onClient(mc -> (String) Mod.call(s2, "status"));
                c.check(key != null || (status != null && !status.isEmpty()),
                        "a click on the hotbar's leather chestplate reached nothing (no selection, no status)");
                c.note("a real click on hotbar 1 selected key " + key + " (status '" + status + "')");
                shotAndPanel(c, s2, "custom-items-selected-light", "LIGHT", shots);
                InvBatchCases.clickWidget(c, s2, need(c, s2, "Done"));
                c.ticks(2);
                boolean closed = c.onClient(mc -> !(McCompat.screen(mc) != null
                        && McCompat.screen(mc).getClass().getSimpleName().equals("CustomItemsScreen")));
                c.check(closed, "Done did not close Custom Items");
            });
        } finally {
            c.onClient(mc -> {
                mc.player.getInventory().setItem(0, old);
                return null;
            });
        }
        c.note("shots: " + shots);
    }

    // ---- 1025 screen theme ---------------------------------------------------------------------------------------------

    private static AbstractWidget themeRow(UiCase c) {
        return c.onClient(mc -> {
            try {
                Object tab = Mod.cls("gui.tab.StorageSearchTab").getConstructor().newInstance();
                @SuppressWarnings("unchecked")
                List<AbstractWidget> ws = (List<AbstractWidget>) Mod.call(tab, "buildWidgets", 0, 0, 300,
                        (Runnable) () -> { });
                for (AbstractWidget w : ws) {
                    if (ModScreenDriver.label(w).startsWith("Theme:")) {
                        return w;
                    }
                }
                return null;
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
    }

    static void screenTheme(UiCase c) throws Exception {
        List<String> shots = new ArrayList<>();
        Function<Screen, Screen> make = p -> construct("storagesearch.StorageSearchScreen",
                new Class<?>[]{Screen.class, String.class}, p, "");
        String st = "gui.ScreenThemes";
        String oldChoice = (String) Mod.staticCall(st, "get", "storage_search");
        Path file = (Path) Mod.staticCall(st, "path");
        try (AutoCloseable on = Mod.with("storagesearch.StorageSearchConfig", "Enabled", true)) {
            inShotWindow(c, () -> {
                c.onClient(mc -> {
                    Mod.staticCall(st, "set", "storage_search", "DEFAULT");
                    return null;
                });
                AbstractWidget row = themeRow(c);
                c.check(row != null, "the Storage Search tab has no Theme row");
                c.check("Theme: Theme Default".equals(ModScreenDriver.label(row)), "the row starts on '"
                        + ModScreenDriver.label(row) + "', not Theme Default");
                List<String> seen = new ArrayList<>();
                for (int i = 0; i < 3; i++) {
                    c.onClient(mc -> {
                        Mod.call(row, "press");
                        return null;
                    });
                    seen.add(ModScreenDriver.label(row));
                }
                c.note("three left clicks on the row: " + seen);
                c.check(seen.equals(List.of("Theme: Amber", "Theme: Dark", "Theme: Light")),
                        "the row cycled " + seen);
                String saved = Files.exists(file) ? Files.readString(file).replaceAll("\\s+", "") : "";
                c.check(saved.contains("\"storage_search\":\"LIGHT\""), "Light was not saved: " + saved);
                c.note(file.getFileName() + " = " + saved);

                // Menu Amber, this screen Light.
                menuTheme(c, "AMBER");
                Screen s = open(c, make, "StorageSearchScreen");
                shotAndPanel(c, s, "screen-theme-light-on-amber-menu", "LIGHT", shots);
                c.onClient(mc -> {
                    McCompat.setScreen(mc, null);
                    Mod.staticCall("gui.MenuStyle", "use");
                    return null;
                });
                int menuPanel = c.onClient(mc -> (Integer) R.getStatic(R.cls("gui.MenuStyle"), "PANEL_TOP")) & 0xFFFFFF;
                int amber = c.onClient(mc -> ((int[]) Mod.call(Mod.enumValue("gui.MenuTheme", "AMBER"), "base"))[1])
                        & 0xFFFFFF;
                c.check(dist(menuPanel, amber) < 20, "after the Light screen the menu palette is #"
                        + Integer.toHexString(menuPanel) + ", not Amber's");
                c.note("menu palette after it: PANEL_TOP #" + Integer.toHexString(menuPanel) + " (Amber panel #"
                        + Integer.toHexString(amber) + ")");

                // On round to Theme Default: follows the menu again. The choices are ScreenThemes.builtInChoices():
                // Default, every MenuTheme (Viridian, GREEN, since mod 765563c6), then any theme saved in this client,
                // so from Light it takes (choices - index of LIGHT) clicks: Ocean, Viridian, Custom, [saved...], Default.
                @SuppressWarnings("unchecked")
                List<String> builtIn = c.onClient(mc -> (List<String>) Mod.staticCall(st, "builtInChoices"));
                int toDefault = builtIn.size() - builtIn.indexOf("LIGHT");
                c.note("built-in screen theme choices: " + builtIn);
                List<String> back = new ArrayList<>();
                for (int i = 0; i < toDefault; i++) {
                    c.onClient(mc -> {
                        Mod.call(row, "press");
                        return null;
                    });
                    back.add(ModScreenDriver.label(row));
                }
                c.check(back.size() >= 4 && back.subList(0, 3).equals(List.of("Theme: Ocean", "Theme: Viridian", "Theme: Custom"))
                        && back.get(back.size() - 1).equals("Theme: Theme Default"), toDefault + " more clicks: " + back);
                c.note(toDefault + " more clicks: " + back);
                Screen s2 = open(c, make, "StorageSearchScreen");
                shotAndPanel(c, s2, "screen-theme-default-amber", "AMBER", shots);
                menuTheme(c, "LIGHT");
                Screen s3 = open(c, make, "StorageSearchScreen");
                shotAndPanel(c, s3, "screen-theme-default-light", "LIGHT", shots);

                // The Bazaar and AH keep their older look as a choice: Inventory Theme, drawn flat. Their tab rows offer it
                // after every built-in choice (after Custom and any saved theme), so it is builtIn.size() clicks from Default.
                List<String> bzSeen = new ArrayList<>();
                String oldBz = (String) Mod.staticCall(st, "get", "bazaar");
                String oldAh = (String) Mod.staticCall(st, "get", "auction_house");
                Object auction = Mod.cfg("auction.AuctionConfig");
                boolean bzWas = (Boolean) Mod.call(auction, "isBazaarEnabledRaw");
                try {
                    c.onClient(mc -> {
                        Mod.call(auction, "setBazaarEnabled", true);
                        return null;
                    });
                    AbstractWidget bzRow = c.onClient(mc -> {
                        Mod.staticCall(st, "set", "bazaar", "DEFAULT");
                        try {
                            Object tab = Mod.cls("gui.tab.BazaarTab").getConstructor().newInstance();
                            @SuppressWarnings("unchecked")
                            List<AbstractWidget> ws = (List<AbstractWidget>) Mod.call(tab, "buildWidgets", 0, 0, 300,
                                    (Runnable) () -> { });
                            for (AbstractWidget w : ws) {
                                if (ModScreenDriver.label(w).startsWith("Theme:")) {
                                    return w;
                                }
                            }
                            return null;
                        } catch (ReflectiveOperationException e) {
                            throw new AssertionError(e);
                        }
                    });
                    c.check(bzRow != null, "the Bazaar tab has no Theme row");
                    for (int i = 0; i < builtIn.size(); i++) {
                        c.onClient(mc -> {
                            Mod.call(bzRow, "press");
                            return null;
                        });
                        bzSeen.add(ModScreenDriver.label(bzRow));
                    }
                } finally {
                    c.onClient(mc -> {
                        Mod.call(auction, "setBazaarEnabled", bzWas);
                        return null;
                    });
                }
                c.note("the Bazaar tab's Theme row: " + bzSeen);
                c.check(bzSeen.get(bzSeen.size() - 1).equals("Theme: Inventory"), "the Bazaar row's click " + bzSeen.size()
                        + " shows " + bzSeen.get(bzSeen.size() - 1));
                try {
                    boolean[] bevel = c.onClient(mc -> {
                        boolean[] out = new boolean[4];
                        Mod.staticCall(st, "set", "bazaar", "DEFAULT");
                        Mod.staticCall(st, "set", "auction_house", "DEFAULT");
                        out[0] = (Boolean) Mod.call(Mod.staticCall("bazaar.BazaarTheme", "current"), "bevel");
                        out[1] = (Boolean) Mod.call(Mod.staticCall("auction.ah.AhTheme", "current"), "bevel");
                        Mod.staticCall(st, "set", "bazaar", "INVENTORY");
                        Mod.staticCall(st, "set", "auction_house", "INVENTORY");
                        out[2] = (Boolean) Mod.call(Mod.staticCall("bazaar.BazaarTheme", "current"), "bevel");
                        out[3] = (Boolean) Mod.call(Mod.staticCall("auction.ah.AhTheme", "current"), "bevel");
                        return out;
                    });
                    c.check(bevel[0] && bevel[1] && !bevel[2] && !bevel[3], "bevel (bazaar, ah) default "
                            + bevel[0] + "," + bevel[1] + " / Inventory Theme " + bevel[2] + "," + bevel[3]);
                    c.note("Bazaar/AH: Theme Default draws the menu's bevelled look, Inventory Theme the old flat one");
                } finally {
                    c.onClient(mc -> {
                        Mod.staticCall(st, "set", "bazaar", oldBz);
                        Mod.staticCall(st, "set", "auction_house", oldAh);
                        return null;
                    });
                }
            });
        } finally {
            c.onClient(mc -> {
                Mod.staticCall(st, "set", "storage_search", oldChoice);
                Mod.staticCall(st, "save");
                return null;
            });
        }
        c.note("shots: " + shots);
    }
}
