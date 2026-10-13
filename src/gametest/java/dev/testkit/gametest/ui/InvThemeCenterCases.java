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
 * 897-ui-inventory-theme-item-centred, for the vanilla-shaped Inventory Theme (mod inv-theme-reskin; killer560,
 * 2026-10-12: "change inventory up to essentially be a reskin of the original with its coloring", presets Default /
 * Dark / Mod Theme).
 * <p>
 * For every slot of the player inventory, chests of 1, 3 and 6 rows and the HUD hotbar (slots 1-8; 0 is selected), at
 * GUI 2, 3, 4 and Auto in a 2560x1440 window, GUI 3 and Auto at 1920x1080, and windows whose height is not a multiple
 * of the GUI scale (2560x1369 GUI 3 / Auto, 1920x1017 GUI 2 / 4), in the Default preset (Dark and Mod Theme at the 1440p
 * GUI 3 and 1369 Auto sizes):
 * <ul>
 *   <li>the slot's inside (its fill colour, read out from the centre on a frame with no items) is exactly the 16-unit
 *   item box, and the stone's opaque box sits in it with equal margins on both axes;</li>
 *   <li>its sunken bevel is there: one GUI unit of the dark shade above and left of the inside, one of the light shade
 *   below and right;</li>
 *   <li>with a LEGENDARY item the Item Rarity Background reaches the inside's edge on all four sides and corners;</li>
 *   <li>the panel's raised bevel (two units light at the top, two dark at the bottom) and, for the hotbar, its outline
 *   along the very bottom of the window.</li>
 * </ul>
 * Not vacuous: the slot count per screen is checked and the test item is checked to have a rarity. Pictures (with the
 * theme off for the side-by-side) are saved to {@code inv-theme-shots/}.
 */
final class InvThemeCenterCases {

    private static final String ICFG = "inventorytheme.InventoryThemeConfig";
    private static final String RCFG = "itemrarity.ItemRarityConfig";
    /** Item Rarity Background colour of a LEGENDARY item (ItemRarity.LEGENDARY), drawn opaque in this case. */
    private static final int GOLD = 0xFFAA00;
    private static final Path SHOTS = Path.of("C:/Users/Hunter/killer560s-mod-logs/inv-theme-shots");

    private InvThemeCenterCases() {
    }

    /** The palette's colours (RGB, no alpha): border, panel, light, dark, slotFill, slotDark, slotLight. */
    private record Pal(int border, int panel, int light, int dark, int slotFill, int slotDark, int slotLight,
                       boolean bevel) {
        Pal(int border, int panel, int light, int dark, int slotFill, int slotDark, int slotLight) {
            this(border, panel, light, dark, slotFill, slotDark, slotLight, true);
        }

        /** The colour of a slot's top and left line: the dark bevel in Default, the light outline elsewhere (mod
         *  doc-misc, 2026-10-13: every preset but Default outlines each slot on all four sides in slotLight). */
        int topLeft() {
            return bevel ? slotDark : slotLight;
        }
    }

    private static void theme(UiCase c, String preset) {
        c.onClient(mc -> {
            Object cfg = Mod.cfg(ICFG);
            Mod.call(cfg, "setEnabled", true);
            Mod.call(cfg, "setHypixelOnly", false);
            Mod.call(cfg, "setPreset", Mod.enumValue(ICFG + "$Preset", preset));
            Mod.call(cfg, "setUseCustomAccent", false);
            Mod.call(cfg, "setUseCustomSlotColor", false);
            // 0.3: since mod inv-theme-ring2 everything is opaque whatever this says (killer560, 2026-10-12: "make the
            // inventories not transparent"), so every exact palette colour this case reads proves the pixel opaque.
            Mod.call(cfg, "setBackgroundOpacity", 0.3f);
            Mod.call(cfg, "setThemeHotbar", true);
            Mod.call(cfg, "setHotbarScale", 1.0f);
            Object rc = Mod.cfg(RCFG);
            Mod.call(rc, "setEnabled", true);
            Mod.call(rc, "setStyle", Mod.enumValue(RCFG + "$Style", "SQUARE"));
            Mod.call(rc, "setOpacity", 100);
            Mod.call(rc, "setShowInHotbar", true);
            Mod.call(rc, "setSkyblockOnly", false);
            return null;
        });
    }

    private static Pal palette(UiCase c) {
        return c.onClient(mc -> {
            Object p = Mod.call(Mod.cfg(ICFG), "getPalette");
            java.util.function.Function<String, Integer> f = n -> ((Number) Mod.call(p, n)).intValue() & 0xFFFFFF;
            boolean bevel;
            try {
                bevel = (Boolean) Mod.call(p, "bevelSlots");
            } catch (Throwable t) {
                bevel = true; // a jar from before doc-misc: every preset bevelled
            }
            return new Pal(f.apply("border"), f.apply("panel"), f.apply("light"), f.apply("dark"), f.apply("slotFill"),
                    f.apply("slotDark"), f.apply("slotLight"), bevel);
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

    /** Pixels of {@code colour} from (x, y) stepping by (dx, dy). */
    private static int run(BufferedImage img, int x, int y, int dx, int dy, int colour) {
        int n = 0;
        while (rgb(img, x + n * dx, y + n * dy) == colour) {
            n++;
        }
        return n;
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

    /** A stone with a Skyblock id and a LEGENDARY rarity line, so the Item Rarity Background draws gold behind it. */
    private static ItemStack legendary() {
        ItemStack s = InvBatchCases.sb(Items.STONE, "IU_THEME_STONE", null, 0);
        s.set(net.minecraft.core.component.DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(
                List.of(Component.literal("LEGENDARY").withStyle(net.minecraft.ChatFormatting.GOLD,
                        net.minecraft.ChatFormatting.BOLD))));
        return s;
    }

    private static void close(UiCase c) {
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            return null;
        });
    }

    /** Opens the screen (chest slots filled with {@code fill} when given); returns its measured slots as
     *  {screenX, screenY, menuIndex} in GUI units, plus the panel as the last element {leftPos, topPos, w, h}. */
    private static List<int[]> open(UiCase c, String kind, ItemStack fill) {
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
                if (fill != null) {
                    for (int i = 0; i < rows * 9; i++) {
                        menu.getSlot(i).set(fill.copy());
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
            out.add(new int[]{left, top, (Integer) Mod.field(s, "imageWidth"), (Integer) Mod.field(s, "imageHeight")});
            return out;
        });
    }

    /** Checks every slot against the 16-unit item box vanilla draws at slot.x / slot.y; returns how many were measured.
     *  Read from the frame WITH items (an empty armour slot shows vanilla's placeholder icon), where the stone leaves the
     *  inside's edge rows and columns showing. */
    private static int measure(String label, BufferedImage full, BufferedImage rarity, List<int[]> slots, int gs, Pal p,
                               List<String> problems, UiCase c) {
        return measure(label, null, full, rarity, slots, gs, p, problems, c);
    }

    /** With {@code empty} (a frame with no items) the inside may be translucent: it is judged as "what the item frame
     *  shows where the item is not", the item as "what changed", instead of against the palette's fill colour. */
    private static int measure(String label, BufferedImage empty, BufferedImage full, BufferedImage rarity, List<int[]> slots,
                               int gs, Pal p, List<String> problems, UiCase c) {
        int checked = 0;
        int worstOff = 0;
        String first = "";
        for (int[] s : slots) {
            int bx0 = s[0] * gs;
            int by0 = s[1] * gs;
            int bx1 = bx0 + 16 * gs - 1;
            int by1 = by0 + 16 * gs - 1;
            int cx = bx0 + 8 * gs;
            int cy = by0 + 8 * gs;
            List<String> bad = new ArrayList<>();
            // The inside reaches the item box's four edges, and the bevel sits right outside them, one unit wide.
            int[][] inside = {{bx0, cy}, {bx1, cy}, {cx, by0}, {cx, by1}};
            int notFill = 0;
            for (int[] e : inside) {
                boolean frame = rgb(full, e[0], e[1]) == p.slotDark() || rgb(full, e[0], e[1]) == p.slotLight();
                if (empty != null ? frame || rgb(full, e[0], e[1]) != rgb(empty, e[0], e[1])
                        : rgb(full, e[0], e[1]) != p.slotFill()) {
                    notFill++;
                }
            }
            if (notFill > 0) {
                bad.add(notFill + " of the item box's 4 edge midpoints are not the slot fill");
            }
            int dl = run(full, bx0 - 1, cy, -1, 0, p.topLeft());
            int dt = run(full, cx, by0 - 1, 0, -1, p.topLeft());
            int lr = run(full, bx1 + 1, cy, 1, 0, p.slotLight());
            int lb = run(full, cx, by1 + 1, 0, 1, p.slotLight());
            // A one-colour outline runs on into a touching neighbour's own line: one unit, or two between two slots.
            boolean ok = p.bevel() ? dl == gs && dt == gs && lr == gs && lb == gs
                    : one(dl, gs) && one(dt, gs) && one(lr, gs) && one(lb, gs);
            if (!ok) {
                bad.add("bevel dark L" + dl + " T" + dt + ", light R" + lr + " B" + lb + " px (want " + gs + " each)");
            }
            // The stone: everything inside the box that is not the fill.
            int x0 = Integer.MAX_VALUE;
            int y0 = Integer.MAX_VALUE;
            int x1 = -1;
            int y1 = -1;
            for (int y = by0; y <= by1; y++) {
                for (int x = bx0; x <= bx1; x++) {
                    if (empty != null ? differs(empty, full, x, y) : rgb(full, x, y) != p.slotFill()) {
                        x0 = Math.min(x0, x);
                        y0 = Math.min(y0, y);
                        x1 = Math.max(x1, x);
                        y1 = Math.max(y1, y);
                    }
                }
            }
            if (x1 < 0) {
                bad.add("no item drawn");
            } else {
                int dx = (x0 - bx0) - (bx1 - x1);
                int dy = (y0 - by0) - (by1 - y1);
                worstOff = Math.max(worstOff, Math.max(Math.abs(dx), Math.abs(dy)));
                if (dx != 0 || dy != 0) {
                    bad.add("item margins L" + (x0 - bx0) + " R" + (bx1 - x1) + " T" + (y0 - by0) + " B" + (by1 - y1));
                }
            }
            int[][] edge = {{bx0, cy}, {bx1, cy}, {cx, by0}, {cx, by1}, {bx0, by0}, {bx1, by0}, {bx0, by1}, {bx1, by1}};
            int notGold = 0;
            for (int[] e : edge) {
                if (rgb(rarity, e[0], e[1]) != GOLD) {
                    notGold++;
                }
            }
            int goldOut = (rgb(rarity, bx0 - 1, cy) == GOLD ? 1 : 0) + (rgb(rarity, bx1 + 1, cy) == GOLD ? 1 : 0);
            if (notGold > 0 || goldOut > 0) {
                bad.add(notGold + " of the inside's edge midpoints / corners are not the rarity gold, " + goldOut
                        + " gold pixel(s) over the bevel");
            }
            if (!bad.isEmpty()) {
                problems.add(label + " slot " + s[2] + ": " + String.join("; ", bad));
                if (first.isEmpty()) {
                    first = "slot " + s[2] + ": " + String.join("; ", bad);
                }
            }
            checked++;
        }
        c.note(String.format(Locale.ROOT, "%s: %d slots measured, worst item off-centre %d px%s", label, checked, worstOff,
                first.isEmpty() ? "; every inside, bevel and rarity edge as wanted" : "; first problem " + first));
        return checked;
    }

    private static boolean one(int run, int gs) {
        return run == gs || run == 2 * gs;
    }

    /** The container panel's raised bevel down its middle column: border, two units light at the top, two dark at the
     *  bottom, border. */
    private static void panelBevel(String label, BufferedImage img, int[] panel, int gs, Pal p, List<String> problems,
                                   UiCase c) {
        int x = (panel[0] + panel[2] / 2) * gs;
        int top = panel[1] * gs;
        int bottom = (panel[1] + panel[3]) * gs - 1;
        int b0 = run(img, x, top, 0, 1, p.border());
        int lt = run(img, x, top + gs, 0, 1, p.light());
        int b1 = run(img, x, bottom, 0, -1, p.border());
        int dk = run(img, x, bottom - gs, 0, -1, p.dark());
        c.note(label + ": panel top border " + b0 + " px, light bevel " + lt + " px; bottom border " + b1 + " px, dark bevel "
                + dk + " px");
        if (b0 != gs || lt != 2 * gs || b1 != gs || dk != 2 * gs) {
            problems.add(label + ": the panel bevel is not vanilla's (top border " + b0 + ", light " + lt + ", bottom border "
                    + b1 + ", dark " + dk + " px; want " + gs + "/" + 2 * gs + ")");
        }
    }

    private static int hotbar(UiCase c, String label, int gs, Pal p, String tag, List<String> problems) {
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            mc.player.getInventory().setSelectedSlot(0);
            return null;
        });
        InvBatchCases.inventory(c, Map.of());
        c.ticks(3);
        int[] g = c.onClient(mc -> new int[]{mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight(),
                mc.getWindow().getHeight()});
        // Default (killer560, 2026-10-12: Default is "the normal" vanilla look) draws vanilla's own hotbar: dark #5D5D5D
        // above and left of each inside, light #7E7E7E right and below, a black outline, translucent insides.
        boolean vanillaBar = label.startsWith("default-");
        Pal hp = vanillaBar ? new Pal(0x000000, 0x5D5D5D, 0x939393, 0x5D5D5D, -1, 0x5D5D5D, 0x7E7E7E) : p;
        BufferedImage emptyBar = vanillaBar ? shot(c, label + "-empty", tag) : null;
        if (vanillaBar) {
            int inside = c.onClient(mc -> ((Number) Mod.field("inventorytheme.HotbarTheme", "V_INSIDE")).intValue()) & 0xFFFFFF;
            int edge = c.onClient(mc -> ((Number) Mod.field("inventorytheme.HotbarTheme", "V_INSIDE_EDGE")).intValue()) & 0xFFFFFF;
            int wrong = 0;
            for (int i = 2; i < 9; i++) {
                int bx = (g[0] / 2 - 91 + 3 + 20 * i) * gs;
                int by = (g[1] - 19) * gs;
                wrong += rgb(emptyBar, bx + 8 * gs, by + 8 * gs) == inside ? 0 : 1;
                wrong += rgb(emptyBar, bx, by + 8 * gs) == edge ? 0 : 1;
                wrong += rgb(emptyBar, bx + 8 * gs, by) == edge ? 0 : 1;
            }
            c.note(String.format(Locale.ROOT, "%s: Default hotbar insides opaque #%06X with #%06X top/left edge: %d of 21 "
                    + "sampled pixels wrong", label, inside, edge, wrong));
            if (wrong > 0) {
                problems.add(label + ": " + wrong + " Default hotbar inside pixels are not their opaque colour (see-through?)");
            }
        }
        List<int[]> slots = new ArrayList<>();
        // Slots 2-8: slot 0 is selected, and its 24-unit frame overlaps slot 1's left edge by a unit, as vanilla's does.
        for (int i = 2; i < 9; i++) {
            slots.add(new int[]{g[0] / 2 - 91 + 3 + 20 * i, g[1] - 19, i});
        }
        Map<Integer, ItemStack> items = new LinkedHashMap<>();
        Map<Integer, ItemStack> rare = new LinkedHashMap<>();
        for (int i = 1; i < 9; i++) {
            items.put(i, new ItemStack(Items.STONE));
            rare.put(i, legendary());
        }
        InvBatchCases.inventory(c, items);
        c.ticks(3);
        BufferedImage full = shot(c, label + "-items", tag);
        InvBatchCases.inventory(c, rare);
        c.ticks(3);
        BufferedImage rarity = shot(c, label + "-rarity", tag);
        // The bar's outline along the very bottom of the window, under the middle of slot 4.
        int midX = (g[0] / 2 - 91 + 3 + 20 * 4 + 8) * gs;
        int bottomRun = run(full, midX, full.getHeight() - 1, 0, -1, hp.border());
        c.note(label + ": GUI height " + g[1] + " x " + gs + " = " + g[1] * gs + " px for a " + g[2] + " px window; "
                + "outline rows at the bottom of the window: " + bottomRun);
        if (bottomRun != gs) {
            problems.add(label + ": the hotbar's bottom outline is " + bottomRun + " px, want " + gs);
        }
        int n = measure(label, emptyBar, full, rarity, slots, gs, hp, problems, c);
        if (n != 7) {
            problems.add(label + ": measured " + n + " hotbar slots, want 7");
        }
        InvBatchCases.inventory(c, Map.of());
        return n;
    }

    /**
     * The screen titles' colour per preset (killer560, 2026-10-12: "in dark mode make text pure white"): in a 2560x1440
     * GUI 3 window, the title of the inventory ("Crafting") and of a 6-row chest is counted pixel by pixel in its label
     * box. Dark must draw it #FFFFFF (and nothing of its old #D8D8DC), Default vanilla's #404040. Pictures of Dark go to
     * {@code inv-theme-shots/dark-white-*.png}.
     */
    private static void titles(UiCase c, List<String> problems) {
        HudEditorCases.setWindow(c, 2560, 1440, 3);
        int gs = c.onClient(mc -> mc.getWindow().getGuiScale());
        InvBatchCases.inventory(c, Map.of());
        String[][] presets = {{"DARK", "FFFFFF"}, {"DEFAULT", "404040"}};
        for (String[] pr : presets) {
            theme(c, pr[0]);
            int want = Integer.parseInt(pr[1], 16);
            for (String kind : new String[]{"inventory", "chest6"}) {
                open(c, kind, null);
                int[] box = c.onClient(mc -> {
                    AbstractContainerScreen<?> sc = (AbstractContainerScreen<?>) McCompat.screen(mc);
                    int x = (Integer) Mod.field(sc, "leftPos") + (Integer) Mod.field(sc, "titleLabelX");
                    int y = (Integer) Mod.field(sc, "topPos") + (Integer) Mod.field(sc, "titleLabelY");
                    return new int[]{x, y, mc.font.width(sc.getTitle())};
                });
                BufferedImage img = pr[0].equals("DARK")
                        ? shotTo(c, "dark-white-" + kind + "-2560x1440-gui3") : InvBatchCases.shot(c, "title-default-" + kind);
                close(c);
                int hit = 0;
                int old = 0;
                for (int y = box[1] * gs; y < (box[1] + 9) * gs; y++) {
                    for (int x = box[0] * gs; x < (box[0] + box[2]) * gs; x++) {
                        int px = rgb(img, x, y);
                        if (px == want) {
                            hit++;
                        } else if (px == 0xD8D8DC) {
                            old++;
                        }
                    }
                }
                c.note(String.format(Locale.ROOT, "%s %s title: %d px of #%06X in its label box (old Dark #D8D8DC: %d px)",
                        pr[0], kind, hit, want, old));
                if (hit < 100 || old > 0) {
                    problems.add(pr[0] + " " + kind + " title: " + hit + " px of #" + pr[1] + ", " + old + " px of #D8D8DC");
                }
            }
        }
    }

    /** Selects hotbar slot {@code sel} and takes a frame. */
    private static BufferedImage hotbarWith(UiCase c, int sel, String name) {
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            mc.player.getInventory().setSelectedSlot(sel);
            return null;
        });
        c.ticks(3);
        return shotTo(c, name);
    }

    /** {x0, y0, x1, y1} (inclusive) of the pixels that differ between {@code a} and {@code b} inside a pixel rect, or null. */
    private static int[] changedBox(BufferedImage a, BufferedImage b, int rx0, int ry0, int rx1, int ry1) {
        int[] box = null;
        for (int y = Math.max(0, ry0); y < Math.min(ry1, a.getHeight()); y++) {
            for (int x = Math.max(0, rx0); x < Math.min(rx1, a.getWidth()); x++) {
                if (differs(a, b, x, y)) {
                    if (box == null) {
                        box = new int[]{x, y, x, y};
                    }
                    box[0] = Math.min(box[0], x);
                    box[1] = Math.min(box[1], y);
                    box[2] = Math.max(box[2], x);
                    box[3] = Math.max(box[3], y);
                }
            }
        }
        return box;
    }

    /**
     * The selected-slot ring (killer560, 2026-10-12: "held items with my mods theme looks kind of off for the main bar.
     * also dark is way too bright"). Empty hotbar, 2560x1440 GUI 3. For vanilla (theme off) and every preset, slot 4
     * selected against slot 0 selected: the box of pixels the ring changes round slot 4 must be vanilla's exactly, none
     * may change inside slot 4's 16-unit inside (the ring hides no more of the item or its rarity fill than vanilla's),
     * and in Dark the brightest ring pixel stays under a cap. Pictures of slots 0, 4 and 8 selected per preset go to
     * {@code inv-theme-shots/hotbar-sel-*.png}.
     */
    private static void selection(UiCase c, List<String> problems) {
        HudEditorCases.setWindow(c, 2560, 1440, 3);
        int gs = c.onClient(mc -> mc.getWindow().getGuiScale());
        int[] g = c.onClient(mc -> new int[]{mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight()});
        InvBatchCases.inventory(c, Map.of());
        int x0 = g[0] / 2 - 91;
        int rx0 = (x0 + 55) * gs;
        int rx1 = (x0 + 107) * gs;
        int ry0 = (g[1] - 30) * gs;
        int ry1 = g[1] * gs;
        int[] inside = {(x0 + 83) * gs, (g[1] - 19) * gs, (x0 + 99) * gs, (g[1] - 3) * gs};
        int[] vanillaBox = null;
        for (String preset : new String[]{"VANILLA", "DEFAULT", "DARK", "MOD_THEME"}) {
            if (preset.equals("VANILLA")) {
                c.onClient(mc -> {
                    Mod.call(Mod.cfg(ICFG), "setEnabled", false);
                    return null;
                });
            } else {
                theme(c, preset);
            }
            String tag = preset.toLowerCase(Locale.ROOT);
            BufferedImage s0 = hotbarWith(c, 0, "ring2-hotbar-" + tag + "-slot0");
            BufferedImage s4 = hotbarWith(c, 4, "ring2-hotbar-" + tag + "-slot4");
            hotbarWith(c, 8, "ring2-hotbar-" + tag + "-slot8");
            int[] box = changedBox(s0, s4, rx0, ry0, rx1, ry1);
            int insideChanged = 0;
            for (int y = inside[1]; y < inside[3]; y++) {
                for (int x = inside[0]; x < inside[2]; x++) {
                    if (differs(s0, s4, x, y)) {
                        insideChanged++;
                    }
                }
            }
            int bright = 0;
            if (box != null) {
                for (int y = box[1]; y <= box[3]; y++) {
                    for (int x = box[0]; x <= box[2]; x++) {
                        if (differs(s0, s4, x, y)) {
                            int p = rgb(s4, x, y);
                            bright = Math.max(bright, Math.max((p >> 16) & 0xFF, Math.max((p >> 8) & 0xFF, p & 0xFF)));
                        }
                    }
                }
            }
            c.note(String.format(Locale.ROOT, "%s: ring box %s, %d px changed inside slot 4, brightest ring channel %d", preset,
                    box == null ? "none" : java.util.Arrays.toString(box), insideChanged, bright));
            if (box == null) {
                problems.add(preset + ": selecting slot 4 changed nothing round it");
                continue;
            }
            if (preset.equals("VANILLA")) {
                vanillaBox = box;
            } else if (vanillaBox != null && !java.util.Arrays.equals(box, vanillaBox)) {
                problems.add(preset + ": the ring covers " + java.util.Arrays.toString(box) + ", vanilla's "
                        + java.util.Arrays.toString(vanillaBox));
            }
            if (insideChanged > 0) {
                problems.add(preset + ": the ring changes " + insideChanged + " px inside the slot's 16-unit inside");
            }
            // Vanilla's band structure, mirrored: from the outline inward on the left light > mid > faint; on the right the
            // reverse (faint by the outline, light by the slot). The outline's top row is the bar's own edge colour.
            int sxp = (x0 - 1 + 80) * gs + gs / 2;
            int my = (g[1] - 10) * gs + gs / 2;
            int[] left = {rgb(s4, sxp + gs, my), rgb(s4, sxp + 2 * gs, my), rgb(s4, sxp + 3 * gs, my)};
            int[] right = {rgb(s4, sxp + 22 * gs, my), rgb(s4, sxp + 21 * gs, my), rgb(s4, sxp + 20 * gs, my)};
            int topRow = rgb(s4, sxp + 12 * gs, (g[1] - 23) * gs + gs / 2);
            int sideCol = rgb(s4, sxp, my);
            boolean fades = lum(left[0]) > lum(left[1]) && lum(left[1]) > lum(left[2])
                    && lum(right[2]) > lum(right[1]) && lum(right[1]) > lum(right[0]);
            c.note(String.format(Locale.ROOT, "%s ring: outline top #%06X side #%06X; left bands #%06X #%06X #%06X, right bands "
                    + "(outside in) #%06X #%06X #%06X", preset, topRow, sideCol, left[0], left[1], left[2], right[0], right[1],
                    right[2]));
            if (!fades) {
                problems.add(preset + ": the ring's bands do not fade like vanilla's (light-mid-faint inward on the left, "
                        + "faint-mid-light on the right)");
            }
            if (!preset.equals("VANILLA")) {
                int border = preset.equals("DEFAULT") ? 0x000000 : palette(c).border();
                if (topRow != border || (sideCol != border && sideCol != 0x2B2B2B)) {
                    problems.add(String.format(Locale.ROOT, "%s: the ring's outline (top #%06X, side #%06X) is not the bar's edge "
                            + "colour #%06X", preset, topRow, sideCol, border));
                }
            }
            if (preset.equals("DARK") && bright > 0xA0) {
                problems.add("DARK: the ring is too bright (channel " + bright + ", cap 160)");
            }
        }
    }

    private static int lum(int rgb) {
        return ((rgb >> 16) & 0xFF) * 3 + ((rgb >> 8) & 0xFF) * 6 + (rgb & 0xFF);
    }

    /** A screenshot saved as {@code inv-theme-shots/<name>.png} exactly (no tag or version suffix). */
    private static BufferedImage shotTo(UiCase c, String name) {
        BufferedImage img = InvBatchCases.shot(c, name);
        try {
            Files.createDirectories(SHOTS);
            javax.imageio.ImageIO.write(img, "png", SHOTS.resolve(name + ".png").toFile());
        } catch (Exception e) {
            c.note("could not save " + name + ": " + e);
        }
        return img;
    }

    static void centred(UiCase c) throws Exception {
        Path cfgPath = InvBatchCases.configPath(c, ICFG);
        byte[] oldCfg = InvBatchCases.read(cfgPath);
        Path rarityPath = InvBatchCases.configPath(c, RCFG);
        byte[] oldRarity = InvBatchCases.read(rarityPath);
        int[] window = HudEditorCases.windowSize(c);
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        boolean reskin = c.onClient(mc -> Mod.has(ICFG + "$Preset"));
        if (!reskin) {
            dev.testkit.gametest.Scenario.skipped(c.name(), "this jar has the flat line theme, not the vanilla-shaped reskin "
                    + "this case measures (mod inv-theme-reskin)");
            return;
        }
        String envTag = System.getenv("TESTKIT_SHOT_TAG");
        String tag = envTag != null && !envTag.isBlank() ? envTag : "reskin";
        List<String> problems = new ArrayList<>();
        int slotsChecked = 0;
        try {
            InvBatchCases.survival(c);
            theme(c, "DEFAULT");
            String probe = c.onClient(mc -> {
                Object entry = Mod.staticCall("itemrarity.ItemRarityFeature", "lookup", legendary());
                return "rarity enabled " + Mod.call(Mod.cfg(RCFG), "isEnabled") + ", the test item reads as "
                        + Mod.field(entry, "rarity");
            });
            c.note("Item Rarity Background probe: " + probe);
            c.check(probe.endsWith("LEGENDARY"), "the test item has no rarity, the rarity checks would be vacuous: " + probe);
            titles(c, problems);
            selection(c, problems);

            int[][] sizes = {{2560, 1440, 3}, {2560, 1440, 2}, {2560, 1440, 4}, {2560, 1440, 0}, {1920, 1080, 3},
                    {1920, 1080, 0}, {2560, 1369, 3}, {2560, 1369, 0}, {1920, 1017, 2}, {1920, 1017, 4}};
            String[] kinds = {"inventory", "chest1", "chest3", "chest6"};
            for (String preset : new String[]{"DEFAULT", "DARK", "MOD_THEME"}) {
                for (int[] sz : sizes) {
                    if (!preset.equals("DEFAULT") && !(sz[0] == 2560 && ((sz[1] == 1440 && sz[2] == 3) || (sz[1] == 1369 && sz[2] == 0)))) {
                        continue;
                    }
                    HudEditorCases.setWindow(c, sz[0], sz[1], sz[2]);
                    int gs = c.onClient(mc -> mc.getWindow().getGuiScale());
                    String size = sz[0] + "x" + sz[1] + "-gui" + (sz[2] == 0 ? "auto" + gs : String.valueOf(gs));
                    boolean showcase = sz[0] == 2560 && sz[1] == 1440 && sz[2] == 3;
                    if (showcase && preset.equals("DEFAULT")) {
                        // Vanilla, for the side-by-side: the same screens with the theme off.
                        c.onClient(mc -> {
                            Mod.call(Mod.cfg(ICFG), "setEnabled", false);
                            return null;
                        });
                        Map<Integer, ItemStack> rare = new LinkedHashMap<>();
                        for (int i = 0; i < 40; i++) {
                            rare.put(i, legendary());
                        }
                        InvBatchCases.inventory(c, rare);
                        for (String kind : new String[]{"inventory", "chest6"}) {
                            open(c, kind, legendary());
                            shot(c, "vanilla-" + kind + "-" + size, tag);
                            close(c);
                        }
                        c.ticks(3);
                        shot(c, "vanilla-hotbar-" + size, tag);
                        theme(c, preset);
                    }
                    theme(c, preset);
                    Pal p = palette(c);
                    for (String kind : kinds) {
                        String label = preset.toLowerCase(Locale.ROOT) + "-" + kind + "-" + size;
                        Map<Integer, ItemStack> items = new LinkedHashMap<>();
                        Map<Integer, ItemStack> rare = new LinkedHashMap<>();
                        for (int i = 0; i < 40; i++) {
                            items.put(i, new ItemStack(Items.STONE));
                            rare.put(i, legendary());
                        }
                        InvBatchCases.inventory(c, items);
                        List<int[]> slots = open(c, kind, new ItemStack(Items.STONE));
                        int[] panel = slots.remove(slots.size() - 1);
                        BufferedImage full = shot(c, label + "-items", tag);
                        close(c);
                        InvBatchCases.inventory(c, rare);
                        open(c, kind, legendary());
                        BufferedImage rarity = shot(c, label + "-rarity", tag);
                        close(c);
                        panelBevel(label, full, panel, gs, p, problems, c);
                        int n = measure(label, full, rarity, slots, gs, p, problems, c);
                        slotsChecked += n;
                        int want = kind.equals("inventory") ? 40 : Integer.parseInt(kind.substring(5)) * 9 + 36;
                        if (n != want) {
                            problems.add(label + ": measured " + n + " slots, want " + want);
                        }
                    }
                    slotsChecked += hotbar(c, preset.toLowerCase(Locale.ROOT) + "-hotbar-" + size, gs, p, tag, problems);
                }
            }
        } finally {
            close(c);
            HudEditorCases.restoreWindow(c, window, oldGui);
            InvBatchCases.restoreConfig(c, ICFG, cfgPath, oldCfg);
            InvBatchCases.restoreConfig(c, RCFG, rarityPath, oldRarity);
            InvBatchCases.inventory(c, Map.of());
        }
        c.note(slotsChecked + " slot(s) measured in all; " + problems.size() + " problem(s)"
                + (problems.isEmpty() ? "" : ", first: " + problems.subList(0, Math.min(5, problems.size()))));
        c.check(slotsChecked > 1000, "too few slots measured: " + slotsChecked);
        c.check(problems.isEmpty(), problems.size() + " problem(s): " + problems.subList(0, Math.min(8, problems.size())));
    }
}
