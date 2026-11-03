package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 1103-ui-invsort-list: the /invsort menu's saved layouts (mod invsort-1103; killer560 2026-11-03: "make the saved
 * loadouts portion bigger so it is easier for me to see what i have", "by default have fetch from storage on", and
 * 2026-11-02 "remove the gray text under the Rename button").
 * <p>
 * Twelve layouts holding eight hotbar items are listed. At 1920x1080 GUI 2 (960x540 units) and at a small 854x480 GUI 2
 * window (427x240): the panel fits, rows are at least as many as before (7) on the big window and at least 3 on the
 * small one, and each row's preview cells show items - measured from the screenshot, a filled cell differs from the
 * row's own empty ninth cell. With every button hidden, the strip where Save / Reset / Rename / Delete sit holds no pixel
 * of the dim text color (the old hint "Left: swap  Right: edit  Drag: reorder" was drawn there, behind the buttons); the
 * same color IS found in the header's dim "layouts" label (the positive control). A layout saved through the menu's Save
 * button has Fetch From Storage ON, in memory and in its file.
 */
final class InvSortListCases {

    private static final String STORE = "invsort.InventoryLayoutStore";
    private static final String ISC = "invsort.InventorySorterConfig";
    private static final Item[] HOTBAR = {Items.DIAMOND_BLOCK, Items.EMERALD_BLOCK, Items.GOLD_BLOCK, Items.IRON_BLOCK,
            Items.REDSTONE_BLOCK, Items.LAPIS_BLOCK, Items.COAL_BLOCK, Items.OAK_PLANKS};
    private static final int LAYOUTS = 12;
    /** {window w, h, GUI scale, big?}. */
    private static final int[][] SIZES = {{1920, 1080, 2, 1}, {854, 480, 2, 0}};

    private InvSortListCases() {
    }

    private static String nameOf(int i) {
        return String.format("ui1103-%02d", i);
    }

    static void list(UiCase c) throws Exception {
        if (!Mod.isCheat()) {
            boolean root = c.onClient(mc -> {
                var d = net.fabricmc.fabric.impl.command.client.ClientCommandInternals.getActiveDispatcher();
                return d != null && d.getRoot().getChild("invsort") != null;
            });
            c.note("legit jar: /invsort registered " + root);
            c.check(!root, "the legit jar registers /invsort");
            return;
        }
        Path cfgPath = InvBatchCases.configPath(c, ISC);
        byte[] oldCfg = InvBatchCases.read(cfgPath);
        int[] window = HudEditorCases.windowSize(c);
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        Path dir = c.onClient(mc -> (Path) Mod.staticCall(STORE, "directory"));
        try {
            InvBatchCases.survival(c);
            Map<Integer, ItemStack> items = new LinkedHashMap<>();
            for (int i = 0; i < HOTBAR.length; i++) {
                items.put(i, new ItemStack(HOTBAR[i]));
            }
            InvBatchCases.inventory(c, items);
            c.onClient(mc -> {
                try {
                    Object store = Mod.staticCall(STORE, "getInstance");
                    Map<Integer, String> slots = new LinkedHashMap<>();
                    for (int i = 0; i < HOTBAR.length; i++) {
                        slots.put(i, (String) Mod.staticCall("autoroutes.ItemIdentity", "of", new ItemStack(HOTBAR[i])));
                    }
                    for (int i = 1; i <= LAYOUTS; i++) {
                        Object l = Mod.cls("invsort.InventoryLayout").getConstructor(String.class, Map.class)
                                .newInstance(nameOf(i), slots);
                        Mod.call(store, "save", l);
                    }
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
                Mod.call(Mod.cfg(ISC), "setLayoutOrder", List.of());
                return null;
            });

            for (int[] size : SIZES) {
                boolean big = size[3] == 1;
                HudEditorCases.setWindow(c, size[0], size[1], size[2]);
                String tag = size[0] + "x" + size[1] + "-gui" + size[2];
                Screen s = open(c);
                int[] g = (int[]) c.onClient(mc -> Mod.call(s, "listGeometry"));
                int[] panel = (int[]) c.onClient(mc -> Mod.call(s, "themedPanelBounds"));
                int[] scr = c.onClient(mc -> new int[]{s.width, s.height});
                List<String> names = c.onClient(mc -> new ArrayList<>((List<String>) Mod.call(
                        Mod.staticCall(STORE, "getInstance"), "listNames")));
                int shown = Math.min(names.size(), g[4]);
                c.note(tag + ": screen " + scr[0] + "x" + scr[1] + ", panel " + panel[2] + "x" + panel[3] + ", list "
                        + g[2] + " wide, rows " + g[4] + " of " + g[3] + " units, preview cells " + g[5] + ", rows shown "
                        + shown + " of " + names.size() + " (was 7 rows of 16 / 13 units, names only)");
                c.check(panel[0] >= 0 && panel[1] >= 0 && panel[0] + panel[2] <= scr[0] && panel[1] + panel[3] <= scr[1],
                        tag + ": the panel does not fit the window");
                if (big) {
                    c.check(g[4] >= 9 && g[3] >= 30 && g[5] >= 18 && g[2] >= 300 && shown >= 9,
                            tag + ": the saved layouts are not bigger: " + java.util.Arrays.toString(g));
                } else {
                    c.check(g[4] >= 3 && g[5] >= 10 && shown >= 3, tag + ": too few rows: " + java.util.Arrays.toString(g));
                }

                BufferedImage img = InvBatchCases.shot(c, tag);
                double k = InvSortFitCases.pxPerUnit(c, s);
                // Preview cells of the first ui1103 row on screen: 0-7 hold items, 8 is empty.
                int row = -1;
                for (int r = 0; r < shown; r++) {
                    if (names.get(r).startsWith("ui1103-")) {
                        row = r;
                        break;
                    }
                }
                c.check(row >= 0, tag + ": no ui1103 layout on the first page: " + names);
                if (row >= 0) {
                    int cy = g[1] + 1 + row * g[3] + 12;
                    int x0 = g[0] + 4;
                    int[] ref = {x0 + 8 * g[5], cy};
                    int filled = 0;
                    List<String> diffs = new ArrayList<>();
                    for (int cell = 0; cell < HOTBAR.length; cell++) {
                        int d = diff(img, k, x0 + cell * g[5], cy, ref[0], ref[1], g[5]);
                        int area = (int) Math.pow(Math.floor((g[5] - 2) * k), 2);
                        diffs.add(cell + ":" + d + "/" + area);
                        if (d > area / 5) {
                            filled++;
                        }
                    }
                    c.note(tag + ": row " + row + " preview cells differing from the empty ninth " + diffs);
                    c.check(filled == HOTBAR.length, tag + ": " + filled + " of " + HOTBAR.length + " preview cells show an item");
                }

                // The strip under the buttons: hide every widget, then look for the dim text color there.
                List<int[]> buttons = c.onClient(mc -> {
                    List<int[]> out = new ArrayList<>();
                    for (GuiEventListener l : s.children()) {
                        if (l instanceof AbstractWidget w) {
                            String label = ModScreenDriver.label(w);
                            if (label.equals("Save") || label.equals("Reset") || label.equals("Rename") || label.endsWith("Delete")) {
                                out.add(new int[]{w.getX(), w.getY(), w.getWidth(), w.getHeight()});
                            }
                        }
                    }
                    for (GuiEventListener l : s.children()) {
                        if (l instanceof AbstractWidget w) {
                            w.visible = false;
                        }
                    }
                    return out;
                });
                c.check(buttons.size() == 4, tag + ": found " + buttons.size() + " of the 4 grid buttons");
                c.ticks(3);
                BufferedImage bare = InvBatchCases.shot(c, tag + "-no-buttons");
                c.onClient(mc -> {
                    for (GuiEventListener l : s.children()) {
                        if (l instanceof AbstractWidget w) {
                            w.visible = true;
                        }
                    }
                    return null;
                });
                int fontW = c.onClient(mc -> mc.font.width("Inventory Sorter"));
                int labelW = c.onClient(mc -> mc.font.width("layouts"));
                // The dim label's colors: what the "layouts" text adds over the header bar beside it.
                int hx = panel[0] + 11 + fontW + 6;
                int hy = panel[1] + 7;
                Set<Integer> bg = colors(bare, k, hx + labelW + 4, hy, 10, 8);
                Set<Integer> label = colors(bare, k, hx, hy, labelW, 8);
                label.removeAll(bg);
                int bx0 = Integer.MAX_VALUE, by0 = Integer.MAX_VALUE, bx1 = 0, by1 = 0;
                for (int[] b : buttons) {
                    bx0 = Math.min(bx0, b[0]);
                    by0 = Math.min(by0, b[1]);
                    bx1 = Math.max(bx1, b[0] + b[2]);
                    by1 = Math.max(by1, b[1] + b[3]);
                }
                int hits = count(bare, k, bx0, by0, bx1 - bx0, by1 - by0, label);
                int control = count(bare, k, hx, hy, labelW, 8, label);
                c.note(tag + ": dim label colors " + hex(label) + "; pixels of them in the button strip " + hits
                        + ", in the 'layouts' label " + control);
                c.check(!label.isEmpty() && control > 0, tag + ": positive control - no dim text found in the header label");
                c.check(hits == 0, tag + ": " + hits + " dim-text pixels under the buttons (the hint behind Rename)");
                c.onClient(mc -> {
                    McCompat.setScreen(mc, null);
                    return null;
                });
                c.ticks(2);
            }

            // A new layout saved with the menu's Save button: Fetch From Storage ON.
            HudEditorCases.setWindow(c, 1280, 720, 2);
            Screen s = open(c);
            EditBox box = InvBatchCases.editBox(c, s);
            c.onClient(mc -> {
                box.setValue("ui1103-new");
                return null;
            });
            InvBatchCases.clickWidget(c, s, InvBatchCases.widget(c, s, "Save"));
            Object saved = c.onClient(mc -> Mod.call(Mod.staticCall(STORE, "getInstance"), "get", "ui1103-new"));
            boolean fetch = saved != null && c.onClient(mc -> (Boolean) Mod.call(saved, "fetchFromStorage"));
            String file = Files.exists(dir.resolve("ui1103-new.json")) ? InvBatchCases.text(dir.resolve("ui1103-new.json")) : "";
            String label = c.onClient(mc -> {
                AbstractWidget w = InvBatchCases.widget(c, s, "Fetch From Storage");
                return w == null ? null : ModScreenDriver.label(w);
            });
            c.note("saved ui1103-new with Save: fetchFromStorage " + fetch + ", file has it on "
                    + file.contains("\"fetchFromStorage\":true") + ", button '" + label + "'");
            c.check(fetch && file.contains("\"fetchFromStorage\":true"), "a new layout is not Fetch From Storage ON");
            c.check("Fetch From Storage: ON".equals(label), "button reads " + label);
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
            HudEditorCases.restoreWindow(c, window, oldGui);
            try {
                for (int i = 1; i <= LAYOUTS; i++) {
                    Files.deleteIfExists(dir.resolve(nameOf(i) + ".json"));
                }
                Files.deleteIfExists(dir.resolve("ui1103-new.json"));
            } catch (Exception ignored) {
                // nothing to clean
            }
            c.onClient(mc -> {
                Mod.staticCall(STORE, "reload");
                return null;
            });
            InvBatchCases.restoreConfig(c, ISC, cfgPath, oldCfg);
            InvBatchCases.inventory(c, Map.of());
        }
    }

    private static Screen open(UiCase c) {
        c.onClient(mc -> {
            mc.player.connection.sendCommand("invsort");
            return null;
        });
        InvBatchCases.awaitScreen(c, "InventorySorterScreen");
        Screen s = InvBatchCases.screen(c);
        c.ctx().getInput().setCursorPos(1, 1);
        c.ticks(4);
        return s;
    }

    /** Pixels that differ between two cells' insides (layout units -&gt; px through {@code k}). */
    private static int diff(BufferedImage img, double k, int ax, int ay, int bx, int by, int cell) {
        int inside = (int) Math.floor((cell - 2) * k);
        int n = 0;
        for (int y = 0; y < inside; y++) {
            for (int x = 0; x < inside; x++) {
                int a = InvBatchCases.rgb(img, (int) Math.round((ax + 1) * k) + x, (int) Math.round((ay + 1) * k) + y);
                int b = InvBatchCases.rgb(img, (int) Math.round((bx + 1) * k) + x, (int) Math.round((by + 1) * k) + y);
                if (a != b) {
                    n++;
                }
            }
        }
        return n;
    }

    private static Set<Integer> colors(BufferedImage img, double k, int x, int y, int w, int h) {
        Set<Integer> out = new HashSet<>();
        for (int py = (int) Math.round(y * k); py < Math.round((y + h) * k); py++) {
            for (int px = (int) Math.round(x * k); px < Math.round((x + w) * k); px++) {
                int c = InvBatchCases.rgb(img, px, py);
                if (c >= 0) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    private static int count(BufferedImage img, double k, int x, int y, int w, int h, Set<Integer> want) {
        int n = 0;
        for (int py = (int) Math.round(y * k); py < Math.round((y + h) * k); py++) {
            for (int px = (int) Math.round(x * k); px < Math.round((x + w) * k); px++) {
                if (want.contains(InvBatchCases.rgb(img, px, py))) {
                    n++;
                }
            }
        }
        return n;
    }

    private static String hex(Set<Integer> cs) {
        List<String> out = new ArrayList<>();
        for (int c : cs) {
            out.add(Integer.toHexString(c));
        }
        return out.toString();
    }
}
