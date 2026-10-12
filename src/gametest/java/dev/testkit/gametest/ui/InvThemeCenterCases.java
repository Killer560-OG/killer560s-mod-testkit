package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 897-ui-inventory-theme-item-centred (mod branch inv-theme-fix). killer560, 2026-10-12: "In my inventory hud
 * everything is shifted item texture wise to the left and maybe up a little bit as well."
 * <p>
 * For every themed container (the player inventory, chests of 1, 3 and 6 rows) at GUI 2, 3, 4 and Auto in a 2560x1440
 * window and GUI 3 and Auto at 1920x1080, with Line Width 2 (the default), 1 and 3: for every drawn slot, the frame's
 * inner area is read from a frame with NO items (scan out from the slot's centre to the first accent pixel each way),
 * and the item's opaque bounding box from the same frame WITH items (pixels that changed). The item must sit centred:
 * left margin == right margin and top == bottom, to the pixel for an even Line Width; an odd width leaves one odd
 * pixel of room and is allowed one pixel of difference. Not vacuous: every slot must have an item box and an inner
 * area, and the slot count per screen is checked. Pictures are copied to {@code inv-theme-shots/}.
 */
final class InvThemeCenterCases {

    private static final String ICFG = "inventorytheme.InventoryThemeConfig";
    private static final int AMBER = 0xCC6600;
    private static final Path SHOTS = Path.of("C:/Users/Hunter/killer560s-mod-logs/inv-theme-shots");

    private InvThemeCenterCases() {
    }

    private static void theme(UiCase c, int line) {
        c.onClient(mc -> {
            Object cfg = Mod.cfg(ICFG);
            Mod.call(cfg, "setEnabled", true);
            Mod.call(cfg, "setHypixelOnly", false);
            Mod.call(cfg, "setLineWidth", line);
            if (Mod.has("gui.PanelTheme")) {
                Mod.call(cfg, "setTheme", Mod.enumValue("gui.PanelTheme", "AMBER"));
            }
            Mod.call(cfg, "setUseCustomAccent", false);
            Mod.call(cfg, "setUseCustomSlotColor", false);
            Mod.call(cfg, "setBackgroundOpacity", 0.85f);
            return null;
        });
    }

    private static int rgb(BufferedImage img, int x, int y) {
        if (x < 0 || y < 0 || x >= img.getWidth() || y >= img.getHeight()) {
            return -1;
        }
        return img.getRGB(x, y) & 0xFFFFFF;
    }

    private static boolean differs(BufferedImage a, BufferedImage b, int x, int y) {
        int p = a.getRGB(x, y);
        int q = b.getRGB(x, y);
        return Math.abs(((p >> 16) & 0xFF) - ((q >> 16) & 0xFF)) + Math.abs(((p >> 8) & 0xFF) - ((q >> 8) & 0xFF))
                + Math.abs((p & 0xFF) - (q & 0xFF)) > 30;
    }

    private static BufferedImage shot(UiCase c, String label, String tag) {
        BufferedImage img = InvBatchCases.shot(c, label);
        try {
            Files.createDirectories(SHOTS);
            String mcv = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("minecraft").orElseThrow()
                    .getMetadata().getVersion().getFriendlyString();
            javax.imageio.ImageIO.write(img, "png", SHOTS.resolve(tag + "-" + label + "-" + mcv + "-"
                    + (Mod.isCheat() ? "cheat" : "legit") + ".png").toFile());
        } catch (Exception e) {
            c.note("could not save " + label + ": " + e);
        }
        return img;
    }

    /** Opens the screen; returns its drawn slots as {screenX, screenY, menuIndex} in GUI units. */
    private static List<int[]> open(UiCase c, String kind, boolean withItems) {
        c.onClient(mc -> {
            if (kind.equals("inventory")) {
                McCompat.setScreen(mc, new InventoryScreen(mc.player));
            } else {
                int rows = Integer.parseInt(kind.substring("chest".length()));
                ChestMenu menu = switch (rows) {
                    case 1 -> ChestMenu.oneRow(0, mc.player.getInventory());
                    case 3 -> ChestMenu.threeRows(0, mc.player.getInventory());
                    default -> ChestMenu.sixRows(0, mc.player.getInventory());
                };
                if (withItems) {
                    for (int i = 0; i < rows * 9; i++) {
                        menu.getSlot(i).set(new ItemStack(Items.STONE));
                    }
                }
                McCompat.setScreen(mc, new ContainerScreen(menu, mc.player.getInventory(), Component.literal("Theme Test")));
            }
            return null;
        });
        c.ticks(6);
        c.ctx().getInput().setCursorPos(0, 0);
        c.ticks(2);
        return c.onClient(mc -> {
            AbstractContainerScreen<?> s = (AbstractContainerScreen<?>) McCompat.screen(mc);
            int left = (Integer) Mod.field(s, "leftPos");
            int top = (Integer) Mod.field(s, "topPos");
            List<int[]> out = new ArrayList<>();
            for (Slot slot : s.getMenu().slots) {
                boolean offhand = slot.container == mc.player.getInventory() && slot.getContainerSlot() == 40;
                boolean crafting = kind.equals("inventory") && slot.container != mc.player.getInventory();
                if (!slot.isActive() || offhand || crafting) {
                    continue; // the offhand is hidden by the theme; the crafting grid holds no item to measure
                }
                out.add(new int[]{left + slot.x, top + slot.y, slot.index});
            }
            return out;
        });
    }

    static void centred(UiCase c) throws Exception {
        Path cfgPath = InvBatchCases.configPath(c, ICFG);
        byte[] oldCfg = InvBatchCases.read(cfgPath);
        int[] window = HudEditorCases.windowSize(c);
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        boolean fixedJar = c.onClient(mc -> {
            for (java.lang.reflect.Method m : Mod.cls("inventorytheme.InventoryThemeFeature").getDeclaredMethods()) {
                if (m.getName().equals("lineShift")) {
                    return true;
                }
            }
            return false;
        });
        String tag = fixedJar ? "after" : "before";
        List<String> problems = new ArrayList<>();
        int slotsChecked = 0;
        try {
            InvBatchCases.survival(c);
            int[][] sizes = {{2560, 1440, 2}, {2560, 1440, 3}, {2560, 1440, 4}, {2560, 1440, 0}, {1920, 1080, 3}, {1920, 1080, 0}};
            String[] kinds = {"inventory", "chest1", "chest3", "chest6"};
            for (int line : new int[]{2, 1, 3}) {
                theme(c, line);
                for (int[] sz : sizes) {
                    // Line Widths 1 and 3 only at the scales his 1440p screen uses, to keep the run short.
                    if (line != 2 && !(sz[0] == 2560 && (sz[2] == 3 || sz[2] == 0))) {
                        continue;
                    }
                    HudEditorCases.setWindow(c, sz[0], sz[1], sz[2]);
                    int gs = c.onClient(mc -> mc.getWindow().getGuiScale());
                    for (String kind : kinds) {
                        String label = kind + "-" + sz[0] + "x" + sz[1] + "-gui" + (sz[2] == 0 ? "auto" + gs : String.valueOf(gs))
                                + "-line" + line;
                        InvBatchCases.inventory(c, Map.of());
                        List<int[]> slots = open(c, kind, false);
                        BufferedImage empty = shot(c, label + "-empty", tag);
                        c.onClient(mc -> {
                            McCompat.setScreen(mc, null);
                            return null;
                        });
                        Map<Integer, ItemStack> items = new LinkedHashMap<>();
                        for (int i = 0; i < 40; i++) {
                            items.put(i, new ItemStack(Items.STONE));
                        }
                        InvBatchCases.inventory(c, items);
                        open(c, kind, true);
                        BufferedImage full = shot(c, label + "-items", tag);
                        c.onClient(mc -> {
                            McCompat.setScreen(mc, null);
                            return null;
                        });
                        int worst = 0;
                        String worstAt = "";
                        int checked = 0;
                        for (int[] s : slots) {
                            int bx0 = s[0] * gs;
                            int by0 = s[1] * gs;
                            int cx = bx0 + 8 * gs;
                            int cy = by0 + 8 * gs;
                            // Inner area: out from the centre to the first accent pixel, on the empty frame.
                            int il = cx;
                            while (il > 0 && rgb(empty, il - 1, cy) != AMBER && cx - il < 20 * gs) {
                                il--;
                            }
                            int ir = cx;
                            while (ir < empty.getWidth() - 1 && rgb(empty, ir + 1, cy) != AMBER && ir - cx < 20 * gs) {
                                ir++;
                            }
                            int it = cy;
                            while (it > 0 && rgb(empty, cx, it - 1) != AMBER && cy - it < 20 * gs) {
                                it--;
                            }
                            int ib = cy;
                            while (ib < empty.getHeight() - 1 && rgb(empty, cx, ib + 1) != AMBER && ib - cy < 20 * gs) {
                                ib++;
                            }
                            // The item's opaque box: what changed inside the inner area once the stone is there.
                            int x0 = Integer.MAX_VALUE;
                            int y0 = Integer.MAX_VALUE;
                            int x1 = -1;
                            int y1 = -1;
                            for (int y = it; y <= ib; y++) {
                                for (int x = il; x <= ir; x++) {
                                    if (differs(empty, full, x, y)) {
                                        x0 = Math.min(x0, x);
                                        y0 = Math.min(y0, y);
                                        x1 = Math.max(x1, x);
                                        y1 = Math.max(y1, y);
                                    }
                                }
                            }
                            if (x1 < 0 || cx - il >= 20 * gs || ir - cx >= 20 * gs) {
                                problems.add(label + " slot " + s[2] + ": no item box or no frame (inner " + il + ".." + ir + ")");
                                continue;
                            }
                            // Stone's GUI cube is its own symmetric box, so compare it with the slot's 16-unit item box
                            // as well, to tell "the frame is off" from "the item is off".
                            int dx = (x0 - il) - (ir - x1);
                            int dy = (y0 - it) - (ib - y1);
                            int itemVsSlot = (x0 - bx0) - (bx0 + 16 * gs - 1 - x1);
                            int allowed = line % 2 == 0 ? 0 : 1;
                            int off = Math.max(Math.abs(dx), Math.abs(dy));
                            if (off > worst) {
                                worst = off;
                                worstAt = "slot " + s[2] + " margins L" + (x0 - il) + " R" + (ir - x1) + " T" + (y0 - it)
                                        + " B" + (ib - y1) + " (item vs its 16-unit box " + itemVsSlot + ")";
                            }
                            if (Math.abs(dx) > allowed || Math.abs(dy) > allowed) {
                                problems.add(label + " slot " + s[2] + ": margins L" + (x0 - il) + " R" + (ir - x1) + " T"
                                        + (y0 - it) + " B" + (ib - y1));
                            }
                            checked++;
                        }
                        slotsChecked += checked;
                        int want = kind.equals("inventory") ? 40 : Integer.parseInt(kind.substring(5)) * 9 + 36;
                        c.note(String.format(Locale.ROOT, "%s (%s jar): %d of %d slots measured, worst off-centre %d px%s",
                                label, tag, checked, want, worst, worst == 0 ? "" : " at " + worstAt));
                        if (checked != want) {
                            problems.add(label + ": measured " + checked + " slots, want " + want);
                        }
                    }
                }
            }
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
            HudEditorCases.restoreWindow(c, window, oldGui);
            InvBatchCases.restoreConfig(c, ICFG, cfgPath, oldCfg);
            InvBatchCases.inventory(c, Map.of());
        }
        c.note(slotsChecked + " slot(s) measured in all; " + problems.size() + " off centre"
                + (problems.isEmpty() ? "" : ", first: " + problems.subList(0, Math.min(5, problems.size()))));
        c.check(slotsChecked > 500, "too few slots measured: " + slotsChecked);
        c.check(problems.isEmpty(), problems.size() + " slot(s) not centred: " + problems.subList(0, Math.min(8, problems.size())));
    }
}
