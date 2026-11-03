package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Inventory Search bar ("Ctrl+F or click to search...") has ONE fixed spot at the bottom middle, the same on every
 * screen at a given window size, and every menu lays out around it (killer560, 2026-11-02: first two screenshots of it
 * covering the Storage Overlay's Inventory panel and the hotbar / hearts, then "no i want it always at the bottom. adjust
 * other menus around it instead"; mod storagesearch-1102).
 * <ul>
 *   <li>1102-ui-storagesearch-overlay: the Storage Overlay with few and many storages at seven window sizes / GUI scales
 *       (1920x1080 GUI 2 and 854x480 among them), slider 125%, Auto Scale on and off, Amber / Dark / Light. The bar's rect
 *       must be the fixed spot (bottom middle, 63 above the bottom; at the very bottom only in a window under ~280 GUI
 *       units tall) and must not change with storages, slider, theme or a long typed query; every rect the overlay drew
 *       (measured from its own fields: grid viewport with margin and scroll bar, Inventory panel, side buttons; and the
 *       mod's drawnRects) must not intersect it. At three sizes a picture with gold blocks in the Inventory panel's third
 *       and hotbar rows against one without: every cell changes at least 60% of its pixels (an uncovered gold block 66%,
 *       under the old bar 17-53%). Ctrl+F (through KeyboardHandler) starts typing, the typed text points the grid at the
 *       match, Escape stops typing, a click on the bar starts it.</li>
 *   <li>1102-ui-storagesearch-screens: the plain inventory with the Item Browser (right-aligned, and centered with 9
 *       columns), a 6-row and a 3-row chest at the same sizes: the bar in the same fixed spot as on the overlay, and no
 *       slot, widget or ContainerCover panel (the Item Browser) under it; the hotbar / status bars only in the
 *       very-bottom fallback; the Item Browser's sort footer text inside its panel.</li>
 * </ul>
 */
final class StorageSearchCases {

    static final String[] CASES = {"1102-ui-storagesearch-overlay", "1102-ui-storagesearch-screens"};

    private static final String ISC = "inventorysearch.InventorySearchConfig";
    private static final String ISF = "inventorysearch.InventorySearchFeature";
    private static final String SCFG = "storageoverlay.StorageOverlayConfig";
    private static final String SFEAT = "storageoverlay.StorageOverlayFeature";
    private static final String IBC = "itembrowser.ItemBrowserConfig";
    private static final String IBF = "itembrowser.ItemBrowserFeature";
    private static final int[][] SIZES = {{1920, 1080, 2}, {1280, 720, 2}, {854, 480, 2}, {1920, 1080, 3},
            {1600, 900, 4}, {2560, 1440, 3}, {1024, 768, 3}};
    /** Panel indices (0-35, row-major) of the gold blocks: third row (player slots 27..35) and the hotbar row. */
    private static final int[] PANEL_ITEMS = {18, 22, 26, 27, 31, 35};

    private StorageSearchCases() {
    }

    // ==== shared ======================================================================================================

    static boolean intersects(int[] a, int[] b) {
        return a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3];
    }

    /** The bar as {x0, y0, x1, y1}, from the mod's own last frame, or null when it is not drawn. */
    private static int[] bar(UiCase c) {
        int[] b = c.onClient(mc -> (int[]) Mod.field(ISF, "lastBoxBounds"));
        return b == null ? null : new int[]{b[0], b[1], b[0] + b[2], b[1] + b[3]};
    }

    /** Rects of the bar per window size, from the overlay case, which the screens case must match. */
    private static final Map<String, int[]> BAR_BY_SIZE = new HashMap<>();

    /** The fixed spot as {x0, y0, x1, y1} for the open screen's size, worked out here independently of the mod. */
    private static int[] expectedBar(UiCase c) {
        return c.onClient(mc -> {
            Screen s = McCompat.screen(mc);
            float scale = ((Number) Mod.call(Mod.cfg(ISC), "getBoxScale")).floatValue();
            int w = Math.min(Math.round(160 * scale), Math.max(20, s.width - 4));
            int h = Math.round(16 * scale);
            int y = s.height - 60 - 3 - h;
            if (y < 200) { // a double chest's slots (18..213) would not fit above it
                y = s.height - 2 - h;
            }
            int x = (s.width - w) / 2;
            return new int[]{x, y, x + w, y + h};
        });
    }

    /** "above hotbar" or "very bottom" (the documented small-window fallback). */
    private static String mode(UiCase c, int[] b) {
        int h = c.onClient(mc -> McCompat.screen(mc).height);
        return b != null && b[3] == h - 2 ? "very bottom" : "above hotbar";
    }

    /** The bar must sit exactly in the fixed spot, and the same at every frame of this window size. */
    private static void checkFixed(UiCase c, String sizeKey, String label, int[] b) {
        int[] want = expectedBar(c);
        if (b == null || !Arrays.equals(b, want)) {
            c.problem(label + ": the bar " + r(b) + " is not in its fixed spot " + r(want));
        }
        int[] seen = BAR_BY_SIZE.putIfAbsent(sizeKey, b);
        if (seen != null && !Arrays.equals(seen, b)) {
            c.problem(label + ": the bar " + r(b) + " moved; at this size it was " + r(seen));
        }
    }

    private static String r(int[] a) {
        return a == null ? "null" : Arrays.toString(a);
    }

    private static boolean hasMethod(String cls, String name) {
        if (!Mod.has(cls)) {
            return false;
        }
        for (java.lang.reflect.Method m : Mod.cls(cls).getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** A picture kept in the report's screens/ folder (copied to killer560s-mod-logs/storagesearch-shots by hand). */
    private static BufferedImage shot(UiCase c, String label) {
        return InvStorageCases.shot(c, label, 4);
    }

    private static void close(UiCase c) {
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            return null;
        });
        c.ticks(2);
    }

    private static void setQuery(UiCase c, String q) {
        c.onClient(mc -> {
            StringBuilder sb = (StringBuilder) Mod.field(ISF, "QUERY");
            sb.setLength(0);
            sb.append(q);
            Mod.staticCall(ISF, "setListening", false);
            return null;
        });
    }

    /**
     * Ctrl+F the way a real key reaches the screen: through {@code KeyboardHandler.keyPress} (where Fabric's
     * ScreenKeyboardEvents run) with the version's own Ctrl bit. The harness's holdKey(Ctrl) + pressKey(F) left the bar
     * unfocused on 26.3 on the old and the new jar alike (its F carried no Ctrl modifier the mod reads), so it proves
     * nothing either way. keyPress is public on 26.3 and private on 26.2 (javap), hence the reflection.
     */
    private static void ctrlF(UiCase c) {
        c.onClient(mc -> {
            try {
                java.lang.reflect.Method m = net.minecraft.client.KeyboardHandler.class.getDeclaredMethod("keyPress",
                        long.class, int.class, net.minecraft.client.input.KeyEvent.class);
                m.setAccessible(true);
                var press = dev.testkit.compat.McKeys.keyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_F,
                        com.mojang.blaze3d.platform.InputConstants.MOD_CONTROL);
                m.invoke(mc.keyboardHandler, mc.getWindow().handle(), com.mojang.blaze3d.platform.InputConstants.PRESS, press);
                m.invoke(mc.keyboardHandler, mc.getWindow().handle(), com.mojang.blaze3d.platform.InputConstants.RELEASE, press);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("KeyboardHandler.keyPress", e);
            }
            return null;
        });
    }

    private static String key(int[] sz) {
        return sz[0] + "x" + sz[1] + "/" + sz[2];
    }

    // ==== overlay =====================================================================================================

    /** Ender Chest 2..ec with items, backpacks 1..bp known (placeholders), page 1 is the open one. */
    private static void seed(UiCase c, int ec, int bp) {
        c.onClient(mc -> {
            String prefix = (String) Mod.staticCall(SFEAT, "accountProfilePrefix");
            Object cache = Mod.staticCall("storageoverlay.StorageOverlayCache", "getInstance");
            for (String key : new ArrayList<>((List<String>) Mod.call(cache, "knownKeysFor", prefix))) {
                try {
                    Mod.call(cache, "unmarkKnown", key);
                } catch (Throwable ignored) {
                    // older cache API: the extra pages only make the grid taller
                }
            }
            for (int page = 2; page <= ec; page++) {
                List<ItemStack> items = new ArrayList<>();
                for (int i = 0; i < 45; i++) {
                    items.add(i % 7 == 0 ? new ItemStack(page % 2 == 0 ? Items.EMERALD : Items.IRON_INGOT, 1 + i % 5)
                            : ItemStack.EMPTY);
                }
                Mod.call(cache, "put", prefix + "|enderchest_" + page, items);
            }
            for (int n = 1; n <= bp; n++) {
                Mod.call(cache, "markKnown", prefix + "|backpack_" + n);
            }
            return null;
        });
    }

    private static void openEnderChest(UiCase c) {
        c.onClient(mc -> {
            SimpleContainer box = new SimpleContainer(54);
            box.setItem(0, new ItemStack(Items.BARRIER));
            box.setItem(9, new ItemStack(Items.DIAMOND, 5));
            box.setItem(10, new ItemStack(Items.EMERALD));
            ContainerScreen screen = new ContainerScreen(ChestMenu.sixRows(0, mc.player.getInventory(), box),
                    mc.player.getInventory(), Component.literal("Ender Chest (1/9)"));
            Mod.setField(SFEAT, "lastPos", null);
            Mod.setField(SFEAT, "lastInventoryOrigin", null);
            McCompat.setScreen(mc, screen);
            return null;
        });
        c.ctx().waitFor(mc -> Mod.field(SFEAT, "lastPos") != null && Mod.field(ISF, "lastBoxBounds") != null, 60);
        c.ticks(6);
    }

    /** Every rect the overlay drew last frame, from its own fields (independent of the mod's drawnRects). */
    private static Map<String, int[]> overlayRects(UiCase c) {
        return c.onClient(mc -> {
            Map<String, int[]> out = new java.util.LinkedHashMap<>();
            int[] pos = (int[]) Mod.field(SFEAT, "lastPos");
            int vw = ((Number) Mod.field(SFEAT, "lastViewportWidthPx")).intValue();
            int vh = ((Number) Mod.field(SFEAT, "lastViewportHeightPx")).intValue();
            if (pos != null && vw > 0) {
                out.put("grid", new int[]{pos[0] - 4, pos[1] - 4, pos[0] + vw + 8, pos[1] + vh + 4});
            }
            int[] inv = (int[]) Mod.field(SFEAT, "lastInventoryOrigin");
            if (inv != null) {
                float s = ((Number) Mod.field(SFEAT, "lastInventoryScale")).floatValue();
                int panelW = ((Number) Mod.field(SFEAT, "PANEL_WIDTH")).intValue();
                int panelH = 4 * 18 + mc.font.lineHeight + 6;
                out.put("inventory", new int[]{inv[0], inv[1], inv[0] + (int) (panelW * s), inv[1] + (int) (panelH * s)});
            }
            for (String f : new String[]{"lastBackButton", "lastSearchButton", "lastScanButton"}) {
                int[] b = (int[]) Mod.field(SFEAT, f);
                if (b != null) {
                    out.put(f.substring(4), new int[]{b[0], b[1], b[0] + b[2], b[1] + b[3]});
                }
            }
            return out;
        });
    }

    /** GUI rect {x0, y0, x1, y1} of the Inventory panel's item cell {@code i}. */
    private static int[] inventoryCell(UiCase c, int i) {
        return c.onClient(mc -> {
            int[] o = (int[]) Mod.field(SFEAT, "lastInventoryOrigin");
            int[] b = (int[]) Mod.field(SFEAT, "lastInventoryBounds");
            float s = ((Number) Mod.field(SFEAT, "lastInventoryScale")).floatValue();
            double x = b[0] + (i % 9) * 18 + 1;
            double y = b[1] + (i / 9) * 18 + 1;
            return new int[]{(int) Math.ceil(o[0] + x * s), (int) Math.ceil(o[1] + y * s),
                    (int) Math.floor(o[0] + (x + 16) * s), (int) Math.floor(o[1] + (y + 16) * s)};
        });
    }

    private static Map<Integer, ItemStack> golds() {
        Map<Integer, ItemStack> m = new HashMap<>();
        for (int i : PANEL_ITEMS) {
            // panel index -> player inventory slot: rows 0-2 are slots 9..35, row 3 the hotbar 0..8
            m.put(i < 27 ? i + 9 : i - 27, new ItemStack(Items.GOLD_BLOCK));
        }
        return m;
    }

    /** One frame's check; returns the line noted. */
    private static void checkOverlayFrame(UiCase c, String sizeKey, String label) {
        int[] b = bar(c);
        checkFixed(c, sizeKey, label, b);
        Map<String, int[]> rects = overlayRects(c);
        int[] scr = c.onClient(mc -> new int[]{McCompat.screen(mc).width, McCompat.screen(mc).height});
        List<String> hits = new ArrayList<>();
        if (b == null) {
            c.problem(label + ": the bar was not drawn");
            return;
        }
        for (Map.Entry<String, int[]> e : rects.entrySet()) {
            if (intersects(b, e.getValue())) {
                hits.add(e.getKey() + " " + r(e.getValue()));
            }
        }
        if (hasMethod(SFEAT, "drawnRects")) {
            List<int[]> own = c.onClient(mc -> (List<int[]>) Mod.staticCall(SFEAT, "drawnRects", McCompat.screen(mc)));
            for (int[] o : own) {
                if (intersects(b, o)) {
                    hits.add("mod-reported " + r(o));
                }
            }
            c.check(own.size() >= 2, label + ": drawnRects reports " + own.size() + " rects (no grid/inventory?)");
        }
        c.note(String.format(Locale.ROOT, "%s: screen %dx%d, bar %s (%s), inventory %s, grid %s -> overlaps %s", label,
                scr[0], scr[1], r(b), mode(c, b), r(rects.get("inventory")), r(rects.get("grid")), hits));
        c.check(rects.containsKey("inventory"), label + ": the Inventory panel was not drawn");
        if (!hits.isEmpty()) {
            c.problem(label + ": the bar " + r(b) + " overlaps " + hits);
        }
        if (b[0] < 0 || b[1] < 0 || b[2] > scr[0] || b[3] > scr[1]) {
            c.problem(label + ": the bar " + r(b) + " is off the " + scr[0] + "x" + scr[1] + " screen");
        }
    }

    /** Gold blocks in rows 3 and 4 of the Inventory panel: every cell must show its item (picture with vs without). */
    private static void pixelCheck(UiCase c, String label) {
        InvBatchCases.inventory(c, golds());
        openEnderChest(c);
        int scale = c.onClient(mc -> mc.getWindow().getGuiScale());
        int[][] cells = new int[PANEL_ITEMS.length][];
        for (int k = 0; k < PANEL_ITEMS.length; k++) {
            cells[k] = inventoryCell(c, PANEL_ITEMS[k]);
        }
        BufferedImage with = shot(c, label + "-items");
        close(c);
        InvBatchCases.inventory(c, Map.of());
        openEnderChest(c);
        BufferedImage without = shot(c, label + "-empty");
        close(c);
        List<String> line = new ArrayList<>();
        for (int k = 0; k < cells.length; k++) {
            int[] g = cells[k];
            int x0 = g[0] * scale;
            int y0 = g[1] * scale;
            int x1 = g[2] * scale;
            int y1 = g[3] * scale;
            int area = Math.max(1, (x1 - x0) * (y1 - y0));
            int changed = InvStorageCases.changed(with, without, x0, y0, x1, y1, 60);
            double frac = changed / (double) area;
            line.add(String.format(Locale.ROOT, "cell %d %.0f%%", PANEL_ITEMS[k], frac * 100));
            // A gold block's 3D icon fills about two thirds of its 16x16 cell: 66% on every uncovered cell of the old jar's
            // pictures, 17-53% under the bar there.
            if (frac < 0.6) {
                c.problem(label + ": the gold block in Inventory panel cell " + PANEL_ITEMS[k] + " shows only "
                        + Math.round(frac * 100) + "% of its pixels (covered?)");
            }
        }
        c.note(label + " pixels: " + line);
    }

    static void overlay(UiCase c) throws Exception {
        InvStorageCases.awaitNoLoadingOverlay(c);
        Path isPath = InvBatchCases.configPath(c, ISC);
        Path soPath = InvBatchCases.configPath(c, SCFG);
        byte[] oldIs = InvBatchCases.read(isPath);
        byte[] oldSo = InvBatchCases.read(soPath);
        Object hud = Mod.staticCall("hud.HudConfig", "getInstance");
        boolean oldAuto = c.onClient(mc -> (Boolean) Mod.call(hud, "isAutoScale"));
        int[] window = HudEditorCases.windowSize(c);
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        Object scfg = Mod.cfg(SCFG);
        try {
            InvBatchCases.survival(c);
            InvBatchCases.inventory(c, Map.of());
            setQuery(c, "");
            c.onClient(mc -> {
                Mod.call(Mod.cfg(ISC), "setEnabled", true);
                Mod.call(scfg, "setEnabled", true);
                Mod.call(scfg, "setScale", 1.0f);
                Mod.call(scfg, "setColumns", 3);
                Mod.call(hud, "setAutoScale", true);
                return null;
            });
            for (int[] counts : new int[][]{{3, 3}, {9, 18}}) {
                seed(c, counts[0], counts[1]);
                for (int[] sz : SIZES) {
                    HudEditorCases.setWindow(c, sz[0], sz[1], sz[2]);
                    openEnderChest(c);
                    String label = String.format(Locale.ROOT, "%dx%d GUI %d, %d EC + %d BP", sz[0], sz[1], sz[2],
                            counts[0], counts[1]);
                    checkOverlayFrame(c, key(sz), label);
                    close(c);
                }
            }
            // Slider 125% and Auto Scale off, on the sizes he uses and the small window.
            seed(c, 3, 6);
            for (int pass = 0; pass < 2; pass++) {
                boolean auto = pass == 0;
                float slider = pass == 0 ? 1.25f : 1.0f;
                c.onClient(mc -> {
                    Mod.call(scfg, "setScale", slider);
                    Mod.call(hud, "setAutoScale", auto);
                    return null;
                });
                for (int[] sz : new int[][]{{1920, 1080, 2}, {2560, 1440, 3}, {854, 480, 2}, {1600, 900, 4}}) {
                    HudEditorCases.setWindow(c, sz[0], sz[1], sz[2]);
                    openEnderChest(c);
                    checkOverlayFrame(c, key(sz), String.format(Locale.ROOT, "%dx%d GUI %d, slider %.0f%%, Auto Scale %s", sz[0],
                            sz[1], sz[2], slider * 100, auto ? "on" : "off"));
                    close(c);
                }
            }
            c.onClient(mc -> {
                Mod.call(scfg, "setScale", 1.0f);
                Mod.call(hud, "setAutoScale", true);
                return null;
            });
            // Themes, on his screen size.
            HudEditorCases.setWindow(c, 1920, 1080, 2);
            for (String theme : new String[]{"AMBER", "DARK", "LIGHT"}) {
                if (!Mod.has("gui.PanelTheme")) {
                    break;
                }
                Object t = Mod.enumValue("gui.PanelTheme", theme);
                c.onClient(mc -> Mod.call(scfg, "setTheme", t));
                openEnderChest(c);
                checkOverlayFrame(c, "1920x1080/2", "1920x1080 GUI 2, " + theme);
                close(c);
                pixelCheck(c, "overlay-1920x1080-gui2-" + theme.toLowerCase(Locale.ROOT));
            }
            c.onClient(mc -> Mod.call(scfg, "setTheme", Mod.enumValue("gui.PanelTheme", "AMBER")));
            HudEditorCases.setWindow(c, 854, 480, 2);
            pixelCheck(c, "overlay-854x480-gui2");
            HudEditorCases.setWindow(c, 1600, 900, 4);
            pixelCheck(c, "overlay-1600x900-gui4");

            // Ctrl+F (real keys) and typing, then a click on the bar.
            HudEditorCases.setWindow(c, 1920, 1080, 2);
            seed(c, 3, 3);
            setQuery(c, "");
            openEnderChest(c);
            c.onClient(mc -> {
                Mod.setField(SFEAT, "highlightIndex", -1);
                return null;
            });
            boolean before = c.onClient(mc -> (Boolean) Mod.staticCall(ISF, "isListening"));
            ctrlF(c);
            c.ticks(2);
            boolean afterCtrlF = c.onClient(mc -> (Boolean) Mod.staticCall(ISF, "isListening"));
            c.ctx().getInput().typeChars("diamond");
            c.ticks(4);
            String query = c.onClient(mc -> (String) Mod.staticCall(ISF, "getQuery"));
            int highlight = c.onClient(mc -> ((Number) Mod.field(SFEAT, "highlightIndex")).intValue());
            BufferedImage typed = shot(c, "overlay-typed-diamond");
            c.note("Ctrl+F: listening " + before + " -> " + afterCtrlF + "; typed \"" + query + "\"; grid highlight index "
                    + highlight + " (the diamond is page 1 content 0); bar " + r(bar(c)));
            c.check(!before && afterCtrlF, "Ctrl+F did not start typing in the bar");
            c.check("diamond".equals(query), "typed query reads \"" + query + "\"");
            c.check(highlight == 0, "typing \"diamond\" did not point the grid at the diamond (highlight " + highlight + ")");
            checkOverlayFrame(c, "1920x1080/2", "1920x1080 GUI 2 with \"diamond\" typed");
            // A long query: the bar keeps its width and place (its text shows the end of what is typed).
            c.ctx().getInput().typeChars(" and a very long search that does not fit in the bar at all");
            c.ticks(3);
            checkOverlayFrame(c, "1920x1080/2", "1920x1080 GUI 2 with a long query typed");
            shot(c, "overlay-long-query");
            setQuery(c, "diamond");
            // Escape stops typing; a click on the bar starts it again.
            c.onClient(mc -> Mod.staticCall(ISF, "setListening", true));
            c.ctx().getInput().pressKey(dev.testkit.compat.McKeys.nat(256));
            c.ticks(2);
            boolean afterEsc = c.onClient(mc -> (Boolean) Mod.staticCall(ISF, "isListening"));
            boolean stillOpen = c.onClient(mc -> McCompat.screen(mc) instanceof ContainerScreen);
            int[] b = bar(c);
            double scale = c.onClient(mc -> (double) mc.getWindow().getGuiScale());
            c.ctx().getInput().setCursorPos((b[0] + b[2]) / 2.0 * scale, (b[1] + b[3]) / 2.0 * scale);
            c.ticks(3);
            c.ctx().getInput().pressMouse(dev.testkit.compat.McKeys.mouse(0));
            c.ticks(2);
            boolean afterClick = c.onClient(mc -> (Boolean) Mod.staticCall(ISF, "isListening"));
            c.note("Escape: listening " + afterEsc + ", screen still open " + stillOpen + "; click on the bar: listening "
                    + afterClick);
            c.check(!afterEsc && stillOpen, "Escape while typing did not just stop typing");
            c.check(afterClick, "a click on the bar did not start typing");
        } finally {
            setQuery(c, "");
            close(c);
            InvBatchCases.inventory(c, Map.of());
            c.onClient(mc -> Mod.call(hud, "setAutoScale", oldAuto));
            HudEditorCases.restoreWindow(c, window, oldGui);
            InvBatchCases.restoreConfig(c, ISC, isPath, oldIs);
            InvBatchCases.restoreConfig(c, SCFG, soPath, oldSo);
        }
    }

    // ==== other screens ===============================================================================================

    private static void open(UiCase c, String kind) {
        c.onClient(mc -> {
            Screen s;
            if (kind.equals("inventory")) {
                s = new InventoryScreen(mc.player);
            } else {
                SimpleContainer box = new SimpleContainer(kind.equals("chest6") ? 54 : 27);
                box.setItem(0, new ItemStack(Items.DIAMOND));
                s = new ContainerScreen(kind.equals("chest6") ? ChestMenu.sixRows(0, mc.player.getInventory(), box)
                        : ChestMenu.threeRows(0, mc.player.getInventory(), box), mc.player.getInventory(),
                        Component.literal("Test Chest"));
            }
            Mod.setField(ISF, "lastBoxBounds", null);
            McCompat.setScreen(mc, s);
            return null;
        });
        c.ctx().waitFor(mc -> Mod.field(ISF, "lastBoxBounds") != null, 60);
        c.ticks(6);
    }

    /** {label -> rect} of everything the bar must keep off on the open screen: panel, slots, the mod's panels. */
    private static Map<String, int[]> screenRects(UiCase c) {
        return c.onClient(mc -> {
            Map<String, int[]> out = new java.util.LinkedHashMap<>();
            AbstractContainerScreen<?> s = (AbstractContainerScreen<?>) McCompat.screen(mc);
            int left = (Integer) Mod.field(s, "leftPos");
            int top = (Integer) Mod.field(s, "topPos");
            int w = (Integer) Mod.field(s, "imageWidth");
            int h = (Integer) Mod.field(s, "imageHeight");
            out.put("container", new int[]{left, top, left + w, top + h});
            for (var child : s.children()) {
                if (child instanceof net.minecraft.client.gui.components.AbstractWidget wd && wd.visible) {
                    out.put("widget " + wd.getMessage().getString(), new int[]{wd.getX(), wd.getY(), wd.getX() + wd.getWidth(),
                            wd.getY() + wd.getHeight()});
                }
            }
            for (Slot slot : s.getMenu().slots) {
                out.put("slot " + slot.index, new int[]{left + slot.x, top + slot.y, left + slot.x + 16, top + slot.y + 16});
            }
            double[] ib = (double[]) Mod.staticCall("containercover.ContainerCover", "rectOf", "itembrowser.panel");
            if (ib != null) {
                out.put("item browser", new int[]{(int) ib[0], (int) ib[1], (int) Math.ceil(ib[2]), (int) Math.ceil(ib[3])});
            }
            int cx = s.width / 2;
            out.put("hotbar", new int[]{cx - 91, s.height - 22, cx + 91, s.height});
            // XP bar (29..24 above the bottom) and two rows of hearts / armor / food (39..49 above)
            out.put("status bars", new int[]{cx - 91, s.height - 49, cx + 91, s.height - 22});
            return out;
        });
    }

    /**
     * A moved chest must draw its gray panel where its slots are: the middle of the first and of the last slot must be
     * slot gray (vanilla #8B8B8B) on the picture, and the panel's own light gray just above the first row. On the first
     * fixed-spot jar the panel stayed centered while the slots moved (ContainerScreen re-centers its texture from height).
     */
    private static void backgroundAligned(UiCase c, String label, BufferedImage img) {
        int[] g = c.onClient(mc -> {
            AbstractContainerScreen<?> s = (AbstractContainerScreen<?>) McCompat.screen(mc);
            int left = (Integer) Mod.field(s, "leftPos");
            int top = (Integer) Mod.field(s, "topPos");
            int minY = Integer.MAX_VALUE;
            int maxY = Integer.MIN_VALUE;
            int x0 = 0;
            for (Slot slot : s.getMenu().slots) {
                if (slot.y < minY || slot.y == minY && slot.x > x0) { // the rightmost of the top row: slot 0 holds the diamond
                    minY = slot.y;
                    x0 = slot.x;
                }
                maxY = Math.max(maxY, slot.y);
            }
            return new int[]{left + x0 + 8, top + minY + 8, top + maxY + 8, mc.getWindow().getGuiScale()};
        });
        int sc = g[3];
        int first = img.getRGB(g[0] * sc, g[1] * sc) & 0xFFFFFF;
        int last = img.getRGB(g[0] * sc, Math.min(img.getHeight() - 1, g[2] * sc)) & 0xFFFFFF;
        c.note(String.format(Locale.ROOT, "%s: picture at the first slot #%06X, at the last slot #%06X", label, first, last));
        if (!slotGray(first) || !slotGray(last)) {
            c.problem(label + ": the chest's panel is not drawn under its slots (first #" + Integer.toHexString(first)
                    + ", last #" + Integer.toHexString(last) + ", want slot gray #8B8B8B)");
        }
    }

    private static boolean slotGray(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int gr = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return Math.abs(r - 0x8B) <= 12 && Math.abs(gr - 0x8B) <= 12 && Math.abs(b - 0x8B) <= 12;
    }

    private static void checkScreen(UiCase c, String sizeKey, String label, String kind) {
        int[] b = bar(c);
        checkFixed(c, sizeKey, label, b);
        String how = mode(c, b);
        Map<String, int[]> rects = screenRects(c);
        List<String> hits = new ArrayList<>();
        List<String> hudHits = new ArrayList<>();
        List<String> panelHits = new ArrayList<>();
        for (Map.Entry<String, int[]> e : rects.entrySet()) {
            if (b != null && intersects(b, e.getValue())) {
                String k = e.getKey();
                (k.equals("hotbar") || k.equals("status bars") ? hudHits : k.equals("container") ? panelHits : hits)
                        .add(k + " " + r(e.getValue()));
            }
        }
        c.note(label + ": bar " + r(b) + " (" + how + "), container " + r(rects.get("container"))
                + ", item browser " + r(rects.get("item browser")) + " -> overlaps " + hits + " " + hudHits
                + (panelHits.isEmpty() ? "" : " (menu background only: " + panelHits + ")"));
        if (b == null) {
            c.problem(label + ": the bar was not drawn");
        } else if (!hits.isEmpty()) {
            c.problem(label + ": the bar " + r(b) + " overlaps " + hits);
        } else if (!hudHits.isEmpty() && !how.equals("very bottom")) {
            c.problem(label + ": the bar " + r(b) + " overlaps " + hudHits);
        }
        if (kind.equals("inventory")) {
            int[] ib = rects.get("item browser");
            if (ib == null) {
                c.problem(label + ": the Item Browser did not report its panel (nothing to keep clear of)");
            } else {
                String footer = c.onClient(mc -> (String) Mod.field(IBF, "lastFooterText"));
                int fw = c.onClient(mc -> footer == null ? -1 : mc.font.width(footer));
                float sc = c.onClient(mc -> ((Number) Mod.call(Mod.cfg(IBC), "getScale")).floatValue());
                int room = Math.round((ib[2] - ib[0]) / sc) - 2;
                c.note(label + ": Item Browser footer \"" + footer + "\" " + fw + " wide, panel " + room + " inside");
                if (footer == null || fw > room) {
                    c.problem(label + ": the Item Browser footer \"" + footer + "\" (" + fw + ") runs past its panel (" + room + ")");
                }
            }
        }
    }

    static void screens(UiCase c) throws Exception {
        InvStorageCases.awaitNoLoadingOverlay(c);
        Path isPath = InvBatchCases.configPath(c, ISC);
        Path ibPath = InvBatchCases.configPath(c, IBC);
        byte[] oldIs = InvBatchCases.read(isPath);
        byte[] oldIb = InvBatchCases.read(ibPath);
        int[] window = HudEditorCases.windowSize(c);
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        try {
            InvBatchCases.survival(c);
            InvBatchCases.inventory(c, Map.of());
            setQuery(c, "");
            c.onClient(mc -> {
                Mod.call(Mod.cfg(ISC), "setEnabled", true);
                Object ib = Mod.cfg(IBC);
                Mod.call(ib, "setEnabled", true);
                Mod.call(ib, "setScale", 1.0f);
                Mod.call(ib, "setColumns", 5);
                Mod.call(ib, "setAlign", Mod.enumValue(IBC + "$HorizontalAlign", "RIGHT"));
                return null;
            });
            for (int[] sz : SIZES) {
                HudEditorCases.setWindow(c, sz[0], sz[1], sz[2]);
                for (String kind : new String[]{"inventory", "chest6", "chest3"}) {
                    open(c, kind);
                    String label = String.format(Locale.ROOT, "%dx%d GUI %d %s", sz[0], sz[1], sz[2], kind);
                    checkScreen(c, key(sz), label, kind);
                    if (kind.equals("inventory") && (sz[0] == 1920 && sz[1] == 1080 && sz[2] == 2
                            || sz[0] == 854 && sz[2] == 2 || sz[0] == 1600 && sz[2] == 4)) {
                        shot(c, String.format(Locale.ROOT, "inventory-%dx%d-gui%d", sz[0], sz[1], sz[2]));
                    }
                    if (kind.equals("chest6") && (sz[0] == 854 && sz[2] == 2 || sz[0] == 1600 && sz[2] == 4
                            || sz[0] == 1920 && sz[2] == 2)) {
                        backgroundAligned(c, label, shot(c, String.format(Locale.ROOT, "chest6-%dx%d-gui%d", sz[0], sz[1], sz[2])));
                    }
                    close(c);
                }
            }
            // The Item Browser centered and 9 columns wide reaches over the bar: it must stop above it.
            c.onClient(mc -> {
                Object ib = Mod.cfg(IBC);
                Mod.call(ib, "setColumns", 9);
                Mod.call(ib, "setAlign", Mod.enumValue(IBC + "$HorizontalAlign", "CENTER"));
                return null;
            });
            for (int[] sz : new int[][]{{1920, 1080, 2}, {854, 480, 2}, {1600, 900, 4}}) {
                HudEditorCases.setWindow(c, sz[0], sz[1], sz[2]);
                open(c, "inventory");
                checkScreen(c, key(sz), String.format(Locale.ROOT, "%dx%d GUI %d, Item Browser centered", sz[0], sz[1], sz[2]),
                        "inventory");
                if (sz[0] == 1920) {
                    shot(c, "itembrowser-centered-1920x1080-gui2");
                }
                close(c);
            }
        } finally {
            setQuery(c, "");
            close(c);
            HudEditorCases.restoreWindow(c, window, oldGui);
            InvBatchCases.restoreConfig(c, ISC, isPath, oldIs);
            InvBatchCases.restoreConfig(c, IBC, ibPath, oldIb);
        }
    }
}
