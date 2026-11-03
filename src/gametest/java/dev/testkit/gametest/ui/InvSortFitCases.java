package dev.testkit.gametest.ui;

import com.mojang.blaze3d.platform.Window;
import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 588: the Inventory Sorter's layout grid fits its items to the cells and draws the Item Rarity box behind them
 * (killer560, 2026-11-02: "in /invsort the items dont fit. also if i have item rarity boxes on those should show here
 * too"; mod branch invsortfit-1102).
 * <p>
 * At four window / GUI scale pairs, /invsort is opened with known items and screenshotted. For each measured item the
 * icon's bounding box is found by diffing its cell against an empty cell of the same grid (pixels, not the mod's own
 * numbers), and must be centered in the cell (within one sprite pixel) and fill at least 70% of the cell's inside. The
 * mod's reported draw box must agree with the measured one. A count-15 stack's count text must lie inside its cell.
 * With Item Rarity on (Square, 100%), a LEGENDARY stone's cell is the rarity gold right inside its frame, on all four
 * corners; with it off, no gold there. Before the fix the icon sat at the cell's top-left (+1,+1) at 16 units in a
 * 24-unit inside: 4 units off center and 67% of the inside, so both checks fail on the old jar at every size but 18.
 * Legit: /invsort is cheat-only; the case checks it is absent there and that the rarity box helper is still present.
 */
final class InvSortFitCases {

    private static final String RCFG = "itemrarity.ItemRarityConfig";
    private static final int GOLD = 0xFFAA00;
    /** {window w, h, GUI scale}: the UI suite's sizes. */
    private static final int[][] SIZES = {{1280, 720, 2}, {1920, 1080, 2}, {2560, 1440, 3}, {854, 480, 1}};
    /** Inventory index -> what is there. 9: legendary stone (rarity); 10, 12: plain blocks (centering); 11: cobblestone x15
     *  (count); 14: the empty reference cell (same row). */
    private static final int LEG = 9;
    private static final int[] CENTERED = {10, 12, 13};

    private InvSortFitCases() {
    }

    private static ItemStack legendary() {
        ItemStack s = InvBatchCases.sb(Items.STONE, "IU_INVSORT_STONE", null, 0);
        s.set(net.minecraft.core.component.DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(
                List.of(Component.literal("LEGENDARY").withStyle(net.minecraft.ChatFormatting.GOLD,
                        net.minecraft.ChatFormatting.BOLD))));
        return s;
    }

    static void fit(UiCase c) throws Exception {
        if (!Mod.isCheat()) {
            boolean root = c.onClient(mc -> {
                var d = net.fabricmc.fabric.impl.command.client.ClientCommandInternals.getActiveDispatcher();
                return d != null && d.getRoot().getChild("invsort") != null;
            });
            boolean helper = c.onClient(mc -> {
                try {
                    Mod.cls("gui.ItemCell");
                    return true;
                } catch (Throwable t) {
                    return false;
                }
            });
            c.note("legit jar: /invsort registered " + root + ", gui.ItemCell present " + helper);
            c.check(!root, "the legit jar registers /invsort");
            c.check(helper, "gui.ItemCell is missing from the legit jar");
            return;
        }
        Path rarityPath = InvBatchCases.configPath(c, RCFG);
        byte[] oldRarity = InvBatchCases.read(rarityPath);
        int[] window = HudEditorCases.windowSize(c);
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        try {
            c.onClient(mc -> {
                Object rc = Mod.cfg(RCFG);
                Mod.call(rc, "setEnabled", true);
                Mod.call(rc, "setStyle", Mod.enumValue(RCFG + "$Style", "SQUARE"));
                Mod.call(rc, "setOpacity", 100);
                Mod.call(rc, "setSkyblockOnly", false);
                return null;
            });
            InvBatchCases.survival(c);
            Map<Integer, ItemStack> items = new LinkedHashMap<>();
            items.put(LEG, legendary());
            items.put(10, new ItemStack(Items.STONE));
            items.put(11, new ItemStack(Items.COBBLESTONE, 15));
            items.put(12, new ItemStack(Items.OAK_PLANKS));
            items.put(13, new ItemStack(Items.GOLD_BLOCK));
            InvBatchCases.inventory(c, items);
            int legColor = c.onClient(mc -> ((Number) Mod.staticCall("itemrarity.ItemRarityFeature", "boxColor",
                    mc.player.getInventory().getItem(LEG))).intValue());
            c.note("the legendary stone's rarity box color 0x" + Integer.toHexString(legColor));
            c.check((legColor & 0xFFFFFF) == GOLD, "the test item has no LEGENDARY box color: 0x"
                    + Integer.toHexString(legColor));

            int measured = 0;
            for (int[] size : SIZES) {
                HudEditorCases.setWindow(c, size[0], size[1], size[2]);
                String tag = size[0] + "x" + size[1] + "-gui" + size[2];
                for (boolean rarity : new boolean[]{true, false}) {
                    c.onClient(mc -> {
                        Mod.call(Mod.cfg(RCFG), "setEnabled", rarity);
                        return null;
                    });
                    Screen s = open(c);
                    BufferedImage img = InvBatchCases.shot(c, tag + (rarity ? "-rarity" : "-norarity"));
                    measured += measure(c, s, img, tag, rarity);
                    c.onClient(mc -> {
                        McCompat.setScreen(mc, null);
                        return null;
                    });
                    c.ticks(2);
                }
            }
            c.note(measured + " cells measured");
            c.check(measured == SIZES.length * 2 * (CENTERED.length + 2), "measured " + measured + " cells");
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
            HudEditorCases.restoreWindow(c, window, oldGui);
            InvBatchCases.restoreConfig(c, RCFG, rarityPath, oldRarity);
            InvBatchCases.inventory(c, Map.of());
        }
    }

    // ==== shared with the other grids' rarity cases (589) ===========================================================

    /** Item Rarity on (Square, 100%, Skyblock Only off) or off; the config file is restored by the caller. */
    static void rarity(UiCase c, boolean on) {
        c.onClient(mc -> {
            Object rc = Mod.cfg(RCFG);
            Mod.call(rc, "setEnabled", on);
            Mod.call(rc, "setStyle", Mod.enumValue(RCFG + "$Style", "SQUARE"));
            Mod.call(rc, "setOpacity", 100);
            Mod.call(rc, "setSkyblockOnly", false);
            return null;
        });
    }

    static Path rarityPath(UiCase c) {
        return InvBatchCases.configPath(c, RCFG);
    }

    static void restoreRarity(UiCase c, Path p, byte[] old) {
        InvBatchCases.restoreConfig(c, RCFG, p, old);
    }

    /** A stack whose lore's last line is a bold gold LEGENDARY, as Hypixel writes it. */
    static ItemStack legendary(net.minecraft.world.item.Item item, String id) {
        ItemStack s = InvBatchCases.sb(item, id, null, 0);
        s.set(net.minecraft.core.component.DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(
                List.of(Component.literal("LEGENDARY").withStyle(net.minecraft.ChatFormatting.GOLD,
                        net.minecraft.ChatFormatting.BOLD))));
        return s;
    }

    /** Legendary-gold (0xFFAA00) pixels in a rectangle of the picture. */
    static int gold(BufferedImage img, int x0, int y0, int x1, int y1) {
        return InvBatchCases.count(img, x0, y0, x1, y1, GOLD);
    }

    // ==== 589-ui-rarity-custom-items ===================================================================================

    /**
     * Custom Items' grid (22-unit cells, now drawn through gui/ItemCell): a plain stone and a gold block are centered and
     * fill their cells' inside, and a LEGENDARY stone's cell is the rarity gold inside its frame with Item Rarity on,
     * none with it off.
     */
    static void customItems(UiCase c) throws Exception {
        if (!Mod.has("armourdye.CustomItemsScreen")) {
            c.note("this jar has no Custom Items screen");
            return;
        }
        Path rp = rarityPath(c);
        byte[] oldRarity = InvBatchCases.read(rp);
        int[] window = HudEditorCases.windowSize(c);
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        try {
            InvBatchCases.survival(c);
            Map<Integer, ItemStack> items = new LinkedHashMap<>();
            items.put(9, legendary(Items.STONE, "IU_CI_STONE"));
            items.put(10, new ItemStack(Items.STONE));
            items.put(11, new ItemStack(Items.GOLD_BLOCK));
            InvBatchCases.inventory(c, items);
            int measured = 0;
            for (int[] size : new int[][]{{1280, 720, 2}, {1920, 1080, 3}}) {
                HudEditorCases.setWindow(c, size[0], size[1], size[2]);
                String tag = size[0] + "x" + size[1] + "-gui" + size[2];
                for (boolean on : new boolean[]{true, false}) {
                    rarity(c, on);
                    c.onClient(mc -> {
                        mc.player.connection.sendCommand("customitems");
                        return null;
                    });
                    InvBatchCases.awaitScreen(c, "CustomItemsScreen");
                    Screen s = InvBatchCases.screen(c);
                    c.ctx().getInput().setCursorPos(1, 1);
                    c.ticks(4);
                    BufferedImage img = InvBatchCases.shot(c, "custom-items-" + tag + (on ? "-rarity" : "-norarity"));
                    double k = pxPerUnit(c, s);
                    int slot = c.onClient(mc -> ((Number) Mod.field("armourdye.CustomItemsScreen", "SLOT")).intValue());
                    java.util.function.IntFunction<int[]> pos = i -> (int[]) c.onClient(mc -> Mod.call(s, "cellPos", i));
                    List<String> notes = new ArrayList<>();
                    // cellPos 5, 6 = inventory 10, 11; 8 = inventory 13 (empty, same row) is the reference.
                    for (int cell : new int[]{5, 6}) {
                        measured += fitCell(c, img, k, slot, pos.apply(cell), pos.apply(8), null,
                                tag + (on ? " rarity" : " plain") + " cell " + cell, notes) ? 1 : 0;
                    }
                    measured += rarityCorners(c, img, k, slot, pos.apply(4), on, tag, notes) ? 1 : 0;
                    c.note("Custom Items " + tag + (on ? " rarity on" : " rarity off") + ", " + slot + " u cells, "
                            + String.format("%.2f", k) + " px/u: " + String.join("; ", notes));
                    c.onClient(mc -> {
                        McCompat.setScreen(mc, null);
                        return null;
                    });
                    c.ticks(2);
                }
            }
            c.check(measured == 2 * 2 * 3, "measured " + measured + " Custom Items cells of 12");
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
            HudEditorCases.restoreWindow(c, window, oldGui);
            restoreRarity(c, rp, oldRarity);
            InvBatchCases.inventory(c, Map.of());
        }
    }

    /** Framebuffer pixels per GUI unit of a mod screen drawn on Auto Scale's canvas. */
    static double pxPerUnit(UiCase c, Screen s) {
        return c.onClient(mc -> {
            float f = ((Number) Mod.staticCall("hud.AutoScale", "appliedFactor", s)).floatValue();
            Window win = mc.getWindow();
            return f * win.getWidth() / (double) win.getGuiScaledWidth();
        });
    }

    /**
     * One cell's icon, found by diffing it against an empty reference cell of the same grid: centered within about a
     * sprite pixel and at least 70% of the inside wide. {@code drawn} (the mod's own {x, y, edge}, or null) must agree.
     */
    static boolean fitCell(UiCase c, BufferedImage img, double k, int slot, int[] p, int[] ref, float[] drawn,
                           String where, List<String> notes) {
        int cellPx = (int) Math.floor(slot * k);
        int insidePx = (int) Math.floor((slot - 2) * k);
        int x0 = (int) Math.round(p[0] * k);
        int y0 = (int) Math.round(p[1] * k);
        int rx = (int) Math.round(ref[0] * k);
        int ry = (int) Math.round(ref[1] * k);
        int m = (int) Math.ceil(k) + 1;
        int bx0 = Integer.MAX_VALUE, by0 = Integer.MAX_VALUE, bx1 = -1, by1 = -1;
        for (int dy = m; dy < cellPx - m; dy++) {
            for (int dx = m; dx < cellPx - m; dx++) {
                int a = InvBatchCases.rgb(img, x0 + dx, y0 + dy);
                int b = InvBatchCases.rgb(img, rx + dx, ry + dy);
                if (a != b && a != -1 && b != -1) {
                    bx0 = Math.min(bx0, dx);
                    by0 = Math.min(by0, dy);
                    bx1 = Math.max(bx1, dx);
                    by1 = Math.max(by1, dy);
                }
            }
        }
        c.check(bx1 >= 0, where + ": no item pixels in the cell");
        if (bx1 < 0) {
            return false;
        }
        double cx = (bx0 + bx1 + 1) / 2.0;
        double cy = (by0 + by1 + 1) / 2.0;
        double mid = cellPx / 2.0;
        double tol = Math.max(2.0, (drawn == null ? 16 : drawn[2]) * k / 16.0 + 1);
        int w = bx1 - bx0 + 1;
        String shortWhere = where.substring(where.lastIndexOf(' ') + 1);
        notes.add(shortWhere + ": box " + w + "x" + (by1 - by0 + 1) + " at (" + bx0 + "," + by0 + ") in a "
                + cellPx + " px cell, center off (" + String.format("%.1f", cx - mid) + "," + String.format("%.1f", cy - mid)
                + ")" + (drawn == null ? "" : String.format(", mod says %.2f u at +%.2f", drawn[2], drawn[0] - p[0])));
        c.check(Math.abs(cx - mid) <= tol && Math.abs(cy - mid) <= tol, where + ": icon not centered, center off ("
                + (cx - mid) + "," + (cy - mid) + ") px, tolerance " + tol);
        c.check(w >= 0.70 * insidePx, where + ": icon " + w + " px wide in a " + insidePx + " px inside");
        if (drawn != null) {
            c.check(Math.abs((drawn[0] - p[0]) * k - bx0) <= tol + k
                    && Math.abs((drawn[0] - p[0] + drawn[2]) * k - (bx1 + 1)) <= tol + k,
                    where + ": the mod's draw box disagrees with the pixels");
        }
        return true;
    }

    /** A legendary item's cell: gold right inside the frame's four corners with rarity on, no gold at all with it off. */
    static boolean rarityCorners(UiCase c, BufferedImage img, double k, int slot, int[] pl, boolean on, String tag,
                                 List<String> notes) {
        int cellPx = (int) Math.floor(slot * k);
        int lx = (int) Math.round(pl[0] * k);
        int ly = (int) Math.round(pl[1] * k);
        int in0 = (int) Math.ceil(k) + 1;
        int in1 = cellPx - (int) Math.ceil(k) - 2;
        int corners = 0;
        for (int[] e : new int[][]{{in0, in0}, {in1, in0}, {in0, in1}, {in1, in1}}) {
            corners += InvBatchCases.rgb(img, lx + e[0], ly + e[1]) == GOLD ? 1 : 0;
        }
        int all = gold(img, lx, ly, lx + cellPx, ly + cellPx);
        notes.add("legendary: " + corners + "/4 corners gold, " + all + " gold px in the cell");
        if (on) {
            c.check(corners == 4, tag + ": rarity on, only " + corners + "/4 inside corners of the legendary cell are gold");
        } else {
            c.check(all == 0, tag + ": rarity off, still " + all + " gold px in the legendary cell");
        }
        return true;
    }

    private static Screen open(UiCase c) {
        c.onClient(mc -> {
            mc.player.connection.sendCommand("invsort");
            return null;
        });
        InvBatchCases.awaitScreen(c, "InventorySorterScreen");
        Screen s = InvBatchCases.screen(c);
        // Mouse well away from the grid (no hover fill), then let a frame or two draw.
        c.ctx().getInput().setCursorPos(1, 1);
        c.ticks(4);
        return s;
    }

    /** Returns how many cells were measured (each counted once it was checked). */
    private static int measure(UiCase c, Screen s, BufferedImage img, String tag, boolean rarity) {
        double k = c.onClient(mc -> {
            float f = ((Number) Mod.staticCall("hud.AutoScale", "appliedFactor", s)).floatValue();
            Window win = mc.getWindow();
            return f * win.getWidth() / (double) win.getGuiScaledWidth();
        });
        int slot = c.onClient(mc -> ((Number) Mod.call(s, "slotSize")).intValue());
        int n = 0;
        int[] ref = (int[]) c.onClient(mc -> Mod.call(s, "slotPos", 14)); // an empty cell in the same row
        int cellPx = (int) Math.floor(slot * k);
        int insidePx = (int) Math.floor((slot - 2) * k);
        List<String> notes = new ArrayList<>();
        for (int i : CENTERED) {
            int[] p = (int[]) c.onClient(mc -> Mod.call(s, "slotPos", i));
            float[] drawn = (float[]) c.onClient(mc -> Mod.call(s, "drawnItemBox", i));
            int x0 = (int) Math.round(p[0] * k);
            int y0 = (int) Math.round(p[1] * k);
            int rx = (int) Math.round(ref[0] * k);
            int ry = (int) Math.round(ref[1] * k);
            // Bounding box of pixels that differ from the empty cell, inside the frame (2 px margin past the line).
            int m = (int) Math.ceil(k) + 1;
            int bx0 = Integer.MAX_VALUE, by0 = Integer.MAX_VALUE, bx1 = -1, by1 = -1;
            for (int dy = m; dy < cellPx - m; dy++) {
                for (int dx = m; dx < cellPx - m; dx++) {
                    int a = InvBatchCases.rgb(img, x0 + dx, y0 + dy);
                    int b = InvBatchCases.rgb(img, rx + dx, ry + dy);
                    if (a != b && a != -1 && b != -1) {
                        bx0 = Math.min(bx0, dx);
                        by0 = Math.min(by0, dy);
                        bx1 = Math.max(bx1, dx);
                        by1 = Math.max(by1, dy);
                    }
                }
            }
            String where = tag + (rarity ? " rarity" : " plain") + " slot " + i;
            c.check(bx1 >= 0, where + ": no item pixels in the cell");
            if (bx1 < 0) {
                continue;
            }
            double cx = (bx0 + bx1 + 1) / 2.0;
            double cy = (by0 + by1 + 1) / 2.0;
            double mid = cellPx / 2.0;
            double tol = Math.max(2.0, (drawn == null ? 16 : drawn[2]) * k / 16.0 + 1);
            int w = bx1 - bx0 + 1;
            notes.add(i + ": box " + w + "x" + (by1 - by0 + 1) + " at (" + bx0 + "," + by0 + ") in a " + cellPx
                    + " px cell, center off (" + String.format("%.1f", cx - mid) + "," + String.format("%.1f", cy - mid)
                    + ")" + (drawn == null ? "" : String.format(", mod says %.2f u at +%.2f", drawn[2], drawn[0] - p[0])));
            c.check(Math.abs(cx - mid) <= tol && Math.abs(cy - mid) <= tol, where + ": icon not centered, center off ("
                    + (cx - mid) + "," + (cy - mid) + ") px, tolerance " + tol);
            c.check(w >= 0.70 * insidePx, where + ": icon " + w + " px wide in a " + insidePx + " px inside");
            c.check(drawn != null && Math.abs((drawn[0] - p[0]) * k - bx0) <= tol + k
                    && Math.abs((drawn[0] - p[0] + drawn[2]) * k - (bx1 + 1)) <= tol + k,
                    where + ": the mod's draw box disagrees with the pixels");
            n++;
        }
        // The count-15 stack: white count text inside its own cell, lower right.
        int[] pc = (int[]) c.onClient(mc -> Mod.call(s, "slotPos", 11));
        int cx0 = (int) Math.round(pc[0] * k);
        int cy0 = (int) Math.round(pc[1] * k);
        int white = InvBatchCases.count(img, cx0, cy0, cx0 + cellPx, cy0 + cellPx, 0xFFFFFF);
        // The count scales with the item (vanilla's proportions: right-aligned at the icon's right edge, on its bottom), so
        // a two-digit count reaches left past the middle; it must sit in the lower half and mostly in the right half.
        int whiteLow = InvBatchCases.count(img, cx0, cy0 + cellPx / 2, cx0 + cellPx, cy0 + cellPx, 0xFFFFFF);
        int whiteRight = InvBatchCases.count(img, cx0 + cellPx / 2, cy0 + cellPx / 2, cx0 + cellPx, cy0 + cellPx, 0xFFFFFF);
        int whiteSpill = InvBatchCases.count(img, cx0 + cellPx, cy0, cx0 + cellPx + (int) (6 * k), cy0 + cellPx, 0xFFFFFF);
        notes.add("count: " + white + " white px in the cell, " + whiteLow + " in its lower half, " + whiteRight
                + " in its lower right, " + whiteSpill + " spilling right");
        c.check(white > 0 && whiteLow * 10 >= white * 9 && whiteRight * 10 >= white * 6,
                tag + ": the count text is not in the cell's lower right (" + white + " px, " + whiteLow + " low, "
                        + whiteRight + " lower right)");
        c.check(whiteSpill == 0, tag + ": the count text hangs out of its cell");
        n++;
        // The rarity box: gold right inside the frame's four corners when on, none when off.
        int[] pl = (int[]) c.onClient(mc -> Mod.call(s, "slotPos", LEG));
        int lx = (int) Math.round(pl[0] * k);
        int ly = (int) Math.round(pl[1] * k);
        int in0 = (int) Math.ceil(k) + 1;
        int in1 = cellPx - (int) Math.ceil(k) - 2;
        int gold = 0;
        for (int[] e : new int[][]{{in0, in0}, {in1, in0}, {in0, in1}, {in1, in1}}) {
            gold += InvBatchCases.rgb(img, lx + e[0], ly + e[1]) == GOLD ? 1 : 0;
        }
        int goldAll = InvBatchCases.count(img, lx, ly, lx + cellPx, ly + cellPx, GOLD);
        notes.add("legendary: " + gold + "/4 corners gold, " + goldAll + " gold px in the cell");
        if (rarity) {
            c.check(gold == 4, tag + ": rarity on, only " + gold + "/4 inside corners of the legendary cell are gold");
        } else {
            c.check(goldAll == 0, tag + ": rarity off, still " + goldAll + " gold px in the legendary cell");
        }
        n++;
        c.note(tag + (rarity ? " rarity on" : " rarity off") + ", slot " + slot + " u, " + String.format("%.2f", k)
                + " px/u: " + String.join("; ", notes));
        return n;
    }
}
