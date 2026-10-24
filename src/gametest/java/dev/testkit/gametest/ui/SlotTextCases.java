package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 960-967: killer560's 2026-10-15 to-do items "Slot Text" (Skyblocker's small text on item slots) and the Personal
 * Compactor / Deletor contents overlay (mod branch slottext-compactor-1015).
 * <ul>
 *   <li>960 Slot Text: sample items per type give the right text, corner and colour; every type's switch removes it;
 *       nothing for plain items; menu-only types stay off in the player's own inventory.</li>
 *   <li>961 Slot Text drawn: a real inventory screenshot with the feature off against on - the text lands in the right
 *       corner of each slot and nowhere else.</li>
 *   <li>962 Slot Text modes: Always / Hold Key to Show / Hold Key to Hide against a held and a released key (the key is
 *       the {@code keyHeldForTest} hook), one type's switch in the picture, and the settings surviving a reload.</li>
 *   <li>963 Compactor Preview reads a compactor / deletor: rows, columns, slots, Active, hostile keys, other items.</li>
 *   <li>964 Compactor Preview drawn on hover: the grid's slot grey appears in the tooltip, and scales with the size.</li>
 *   <li>965 Compactor Preview modes: On Hover against While Holding Key with the key held and released, and off.</li>
 *   <li>966 Compactor Preview settings survive a reload.</li>
 * </ul>
 */
final class SlotTextCases {

    private static final String STC = "slottext.SlotTextConfig";
    private static final String STF = "slottext.SlotTextFeature";
    private static final String STT = "slottext.SlotTextType";
    private static final String CPC = "compactorpreview.CompactorPreviewConfig";
    private static final String CPF = "compactorpreview.CompactorPreviewFeature";
    private static final String CREAM = "ffffffdd";
    private static final String GOLD = "ffffaa00";
    private static final String PURPLE = "ffff55ff";
    private static final int SLOT_GREY = 0x8B8B8B;

    private SlotTextCases() {
    }

    // ==== shared =======================================================================================================

    private static ItemStack mk(Item item, String name, CompoundTag data, String... lore) {
        ItemStack s = new ItemStack(item);
        if (name != null) {
            s.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        }
        if (data != null) {
            s.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        }
        if (lore.length > 0) {
            List<Component> lines = new ArrayList<>();
            for (String l : lore) {
                lines.add(Component.literal(l));
            }
            s.set(DataComponents.LORE, new ItemLore(lines));
        }
        return s;
    }

    private static CompoundTag tag(String id) {
        CompoundTag t = new CompoundTag();
        t.putString("id", id);
        return t;
    }

    private static List<String> text(UiCase c, ItemStack s, String title, int slot, boolean player) {
        return c.onClient(mc -> {
            List<?> entries = (List<?>) Mod.staticCall(STF, "textFor", s, title, slot, player);
            List<String> out = new ArrayList<>();
            for (Object e : entries) {
                out.add(Mod.call(e, "pos") + ":" + Mod.call(e, "text") + ":" + Integer.toHexString((Integer) Mod.call(e, "color")));
            }
            return out;
        });
    }

    private static void setType(UiCase c, String type, boolean on) {
        c.onClient(mc -> {
            Mod.call(Mod.cfg(STC), "setTypeEnabled", Mod.enumValue(STT, type), on);
            return null;
        });
    }

    private static void slotText(UiCase c, boolean enabled, String mode) {
        c.onClient(mc -> {
            Object cfg = Mod.cfg(STC);
            Mod.call(cfg, "setEnabled", enabled);
            Mod.call(cfg, "setMode", Mod.enumValue(STC + "$Mode", mode));
            Mod.call(cfg, "setSkyblockOnly", false);
            return null;
        });
    }

    private static void keyHeld(UiCase c, String feature, Boolean held) {
        c.onClient(mc -> {
            Mod.setField(feature, "keyHeldForTest", held);
            return null;
        });
    }

    private static void openInventory(UiCase c) {
        c.onClient(mc -> {
            McCompat.setScreen(mc, new InventoryScreen(mc.player));
            return null;
        });
        c.ticks(5);
    }

    private static void closeScreen(UiCase c) {
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            return null;
        });
        c.ticks(2);
    }

    /** GUI position {x, y} of the player-inventory slot with container index {@code invIndex} on the open screen. */
    private static int[] invSlotPos(UiCase c, int invIndex) {
        return c.onClient(mc -> {
            AbstractContainerScreen<?> s = (AbstractContainerScreen<?>) McCompat.screen(mc);
            int left = (Integer) Mod.field(s, "leftPos");
            int top = (Integer) Mod.field(s, "topPos");
            for (Slot slot : s.getMenu().slots) {
                if (slot.container == mc.player.getInventory() && slot.getContainerSlot() == invIndex) {
                    return new int[]{left + slot.x, top + slot.y, slot.index};
                }
            }
            throw new AssertionError("no slot for inventory index " + invIndex);
        });
    }

    private static void cursorGui(UiCase c, double gx, double gy) {
        double scale = c.onClient(mc -> (double) mc.getWindow().getGuiScale());
        c.ctx().getInput().setCursorPos(gx * scale, gy * scale);
        c.ticks(3);
    }

    /** Pixels whose summed RGB delta between two frames is above 30, inside the rectangle. */
    private static int changedIn(BufferedImage a, BufferedImage b, int x0, int y0, int x1, int y1) {
        int n = 0;
        for (int y = Math.max(0, y0); y < Math.min(y1, Math.min(a.getHeight(), b.getHeight())); y++) {
            for (int x = Math.max(0, x0); x < Math.min(x1, Math.min(a.getWidth(), b.getWidth())); x++) {
                int p = a.getRGB(x, y);
                int q = b.getRGB(x, y);
                int d = Math.abs(((p >> 16) & 0xFF) - ((q >> 16) & 0xFF)) + Math.abs(((p >> 8) & 0xFF) - ((q >> 8) & 0xFF))
                        + Math.abs((p & 0xFF) - (q & 0xFF));
                if (d > 30) {
                    n++;
                }
            }
        }
        return n;
    }

    private static int greys(BufferedImage img) {
        return InvBatchCases.count(img, 0, 0, img.getWidth(), img.getHeight(), SLOT_GREY);
    }

    /** Changed pixels in one 7x7 corner box (0 TL, 1 TR, 2 BL, 3 BR) of the 16x16 slot at GUI x,y. The boxes leave a
     *  2-unit gutter in the middle, because the text's drop shadow reaches one unit past each glyph. */
    private static int quadrant(BufferedImage a, BufferedImage b, int[] pos, int gs, int quad) {
        int qx = (quad % 2) * 9;
        int qy = (quad / 2) * 9;
        return changedIn(a, b, (pos[0] + qx) * gs, (pos[1] + qy) * gs, (pos[0] + qx + 7) * gs, (pos[1] + qy + 7) * gs);
    }

    // ==== 960 types ====================================================================================================

    static void types(UiCase c) throws Exception {
        Path path = InvBatchCases.configPath(c, STC);
        byte[] old = InvBatchCases.read(path);
        try {
            slotText(c, true, "ALWAYS");
            // everything on, including the off-by-default abbreviation, then each case below turns it back off
            for (Object t : c.onClient(mc -> Mod.cls(STT).getEnumConstants())) {
                setType(c, ((Enum<?>) t).name(), true);
            }
            setType(c, "ENCHANT_ABBREVIATION", false);

            CompoundTag sharp = tag("ENCHANTED_BOOK");
            CompoundTag enchants = new CompoundTag();
            enchants.putInt("sharpness", 6);
            sharp.put("enchantments", enchants);
            CompoundTag wise = tag("ENCHANTED_BOOK");
            CompoundTag wiseE = new CompoundTag();
            wiseE.putInt("ultimate_wise", 5);
            wise.put("enchantments", wiseE);
            CompoundTag potion = tag("POTION");
            potion.putInt("potion_level", 5);
            CompoundTag shard = tag("ATTRIBUTE_SHARD");
            CompoundTag attrs = new CompoundTag();
            attrs.putInt("mana_pool", 3);
            shard.put("attributes", attrs);

            ItemStack pet = mk(Items.PLAYER_HEAD, "[Lvl 87] Wolf", tag("PET"));
            ItemStack bookSharp = mk(Items.ENCHANTED_BOOK, "Enchanted Book", sharp);
            ItemStack bookWise = mk(Items.ENCHANTED_BOOK, "Enchanted Book", wise);
            ItemStack bazaarBook = mk(Items.ENCHANTED_BOOK, "Sharpness VII", tag("ENCHANTED_BOOK"));
            ItemStack minion = mk(Items.PLAYER_HEAD, "Cobblestone Minion XI", null);
            ItemStack potionItem = mk(Items.PAPER, "Strength Potion", potion);
            ItemStack boots = mk(Items.LEATHER_BOOTS, "Rancher's Boots", tag("RANCHERS_BOOTS"), "Current Speed Cap: 200");
            ItemStack bootsBeyond = mk(Items.LEATHER_BOOTS, "Rancher's Boots", tag("RANCHERS_BOOTS"), "Current Speed Cap: 400 250");
            ItemStack attribute = mk(Items.PRISMARINE_SHARD, "Attribute Shard", shard);
            ItemStack skill = mk(Items.WHEAT, "Farming XII", null, "Progress to Level XIII: 40%");
            ItemStack skillMax = mk(Items.WHEAT, "Combat L", null, "Max Skill level reached!");
            ItemStack skillFresh = mk(Items.WHEAT, "Taming", null);
            ItemStack collection = mk(Items.WHEAT, "Wheat IX", null, "Progress to Wheat X: 45%");
            ItemStack collectionDone = mk(Items.WHEAT, "Wheat XI", null, "Collection maxed out!");
            ItemStack bestiary = mk(Items.ROTTEN_FLESH, "Zombie IV", null, "Overall Progress: 40%");
            ItemStack bestiaryDone = mk(Items.ROTTEN_FLESH, "Zombie XII", null, "Overall Progress: 100%");
            ItemStack perk = mk(Items.DIAMOND, "Mining Speed", null, "Level 5/50", "Increases mining speed.");
            ItemStack tuneStat = mk(Items.PAPER, "Health", null, "Stat has: 12 points");
            ItemStack tuneZero = mk(Items.PAPER, "Speed", null, "Stat has: 0 points");
            ItemStack tuneButton = mk(Items.PAPER, "Stats Tuning", null, "Unassigned Points: 7!!!");
            ItemStack stone = new ItemStack(Items.STONE);

            Object[][] table = {
                    // label, stack, title, slot, playerSlot, expected
                    {"pet level", pet, "Pets", 10, false, "[TOP_LEFT:87:" + CREAM + "]"},
                    {"pet in own inventory", pet, "Inventory", 12, true, "[TOP_LEFT:87:" + CREAM + "]"},
                    {"book level", bookSharp, "Chest", 4, false, "[BOTTOM_LEFT:6:" + CREAM + "]"},
                    {"ultimate book level", bookWise, "Chest", 4, false, "[BOTTOM_LEFT:5:" + CREAM + "]"},
                    {"bazaar book level", bazaarBook, "Bazaar", 11, false, "[BOTTOM_LEFT:7:" + CREAM + "]"},
                    {"minion tier", minion, "Chest", 4, false, "[TOP_RIGHT:11:" + CREAM + "]"},
                    {"potion level", potionItem, "Chest", 4, false, "[BOTTOM_RIGHT:5:" + CREAM + "]"},
                    {"rancher speed", boots, "Chest", 4, false, "[BOTTOM_LEFT:200:" + CREAM + "]"},
                    {"rancher speed beyond cap", bootsBeyond, "Chest", 4, false, "[BOTTOM_LEFT:250:" + CREAM + "]"},
                    {"attribute shard", attribute, "Chest", 4, false, "[BOTTOM_RIGHT:3:" + CREAM + "]"},
                    {"skill", skill, "Your Skills", 11, false, "[BOTTOM_LEFT:12:" + CREAM + "]"},
                    {"skill maxed", skillMax, "Your Skills", 12, false, "[BOTTOM_LEFT:50:" + GOLD + "]"},
                    {"skill never leveled", skillFresh, "Your Skills", 13, false, "[BOTTOM_LEFT:0:" + CREAM + "]"},
                    {"skill header row", skill, "Your Skills", 4, false, "[]"},
                    {"skill in own inventory", skill, "Your Skills", 11, true, "[]"},
                    {"skill in another menu", skill, "Chest", 11, false, "[]"},
                    {"collection in progress", collection, "Farming Collections", 20, false, "[BOTTOM_RIGHT:9:" + CREAM + "]"},
                    {"collection done", collectionDone, "Farming Collections", 21, false, "[BOTTOM_RIGHT:11:" + GOLD + "]"},
                    {"bestiary", bestiary, "Bestiary ➜ Hub", 10, false, "[BOTTOM_RIGHT:4:" + CREAM + "]"},
                    {"bestiary border slot", bestiary, "Bestiary ➜ Hub", 9, false, "[]"},
                    {"bestiary done", bestiaryDone, "(1/2) Fishing ➜ Water", 11, false, "[BOTTOM_RIGHT:12:" + GOLD + "]"},
                    {"heart perk", perk, "Heart of the Mountain", 20, false, "[BOTTOM_RIGHT:5:" + CREAM + "]"},
                    {"stats tuning stat", tuneStat, "Stats Tuning", 11, false, "[BOTTOM_RIGHT:12:" + CREAM + "]"},
                    {"stats tuning zero", tuneZero, "Stats Tuning", 12, false, "[]"},
                    {"stats tuning button", tuneButton, "Stats Tuning", 4, false, "[BOTTOM_RIGHT:7:" + CREAM + "]"},
                    {"plain stone", stone, "Chest", 4, false, "[]"},
                    {"empty", ItemStack.EMPTY, "Chest", 4, false, "[]"},
            };
            List<String> wrong = new ArrayList<>();
            for (Object[] row : table) {
                String got = String.valueOf(text(c, (ItemStack) row[1], (String) row[2], (Integer) row[3], (Boolean) row[4]));
                if (!got.equals(row[5])) {
                    wrong.add(row[0] + " -> " + got + " (want " + row[5] + ")");
                }
            }
            c.note(table.length + " samples; wrong: " + wrong);
            c.check(wrong.isEmpty(), "slot text samples: " + wrong);

            // The abbreviation is its own switch, off by default.
            c.check(String.valueOf(text(c, bookSharp, "Chest", 4, false)).equals("[BOTTOM_LEFT:6:" + CREAM + "]"),
                    "abbreviation drawn while its switch is off");
            setType(c, "ENCHANT_ABBREVIATION", true);
            String abbr = String.valueOf(text(c, bookSharp, "Chest", 4, false));
            String abbrUlt = String.valueOf(text(c, bookWise, "Chest", 4, false));
            c.note("abbreviations on: sharpness " + abbr + "; ultimate wise " + abbrUlt);
            c.check(abbr.equals("[BOTTOM_LEFT:6:" + CREAM + ", TOP_LEFT:SHA:" + CREAM + "]"), "sharpness abbreviation: " + abbr);
            c.check(abbrUlt.equals("[BOTTOM_LEFT:5:" + CREAM + ", TOP_LEFT:WIS:" + PURPLE + "]"), "ultimate wise abbreviation: " + abbrUlt);
            setType(c, "ENCHANT_ABBREVIATION", false);

            // Every type has a sample above, and its switch alone removes it.
            Map<String, Object[]> byType = new LinkedHashMap<>();
            byType.put("PET_LEVEL", new Object[]{pet, "Pets", 10});
            byType.put("ENCHANT_LEVEL", new Object[]{bookSharp, "Chest", 4});
            byType.put("MINION_TIER", new Object[]{minion, "Chest", 4});
            byType.put("POTION_LEVEL", new Object[]{potionItem, "Chest", 4});
            byType.put("RANCHER_SPEED", new Object[]{boots, "Chest", 4});
            byType.put("ATTRIBUTE_LEVEL", new Object[]{attribute, "Chest", 4});
            byType.put("SKILL_LEVEL", new Object[]{skill, "Your Skills", 11});
            byType.put("COLLECTION_LEVEL", new Object[]{collection, "Farming Collections", 20});
            byType.put("BESTIARY_LEVEL", new Object[]{bestiary, "Bestiary ➜ Hub", 10});
            byType.put("PERK_LEVEL", new Object[]{perk, "Heart of the Mountain", 20});
            byType.put("STATS_TUNING", new Object[]{tuneStat, "Stats Tuning", 11});
            int typeCount = c.onClient(mc -> Mod.cls(STT).getEnumConstants().length);
            c.check(typeCount == byType.size() + 1, "the type list changed (" + typeCount + " types, " + byType.size()
                    + " + the abbreviation sampled): add a sample for the new one");
            List<String> stuck = new ArrayList<>();
            for (var e : byType.entrySet()) {
                Object[] v = e.getValue();
                boolean before = !text(c, (ItemStack) v[0], (String) v[1], (Integer) v[2], false).isEmpty();
                setType(c, e.getKey(), false);
                boolean after = !text(c, (ItemStack) v[0], (String) v[1], (Integer) v[2], false).isEmpty();
                setType(c, e.getKey(), true);
                boolean back = !text(c, (ItemStack) v[0], (String) v[1], (Integer) v[2], false).isEmpty();
                if (!before || after || !back) {
                    stuck.add(e.getKey() + " on=" + before + " off=" + after + " on-again=" + back);
                }
            }
            c.note("each type's switch: " + (stuck.isEmpty() ? "all " + byType.size() + " remove their text and bring it back" : stuck));
            c.check(stuck.isEmpty(), "type switches that did not act: " + stuck);
        } finally {
            InvBatchCases.restoreConfig(c, STC, path, old);
        }
    }

    // ==== 961 drawn ====================================================================================================

    private static Map<Integer, ItemStack> pictureItems() {
        Map<Integer, ItemStack> items = new LinkedHashMap<>();
        items.put(9, mk(Items.PLAYER_HEAD, "[Lvl 87] Wolf", tag("PET")));
        items.put(10, mk(Items.PLAYER_HEAD, "Cobblestone Minion XI", null));
        items.put(11, mk(Items.LEATHER_BOOTS, "Rancher's Boots", tag("RANCHERS_BOOTS"), "Current Speed Cap: 200"));
        CompoundTag potion = tag("POTION");
        potion.putInt("potion_level", 5);
        items.put(12, mk(Items.PAPER, "Strength Potion", potion));
        return items;
    }

    static void drawn(UiCase c) throws Exception {
        Path path = InvBatchCases.configPath(c, STC);
        byte[] old = InvBatchCases.read(path);
        int[] window = HudEditorCases.windowSize(c);
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        try {
            InvBatchCases.survival(c);
            InvBatchCases.inventory(c, pictureItems());
            HudEditorCases.setWindow(c, 1280, 720, 2);
            int gs = c.onClient(mc -> mc.getWindow().getGuiScale());
            slotText(c, false, "ALWAYS");
            openInventory(c);
            cursorGui(c, 2, 2);
            int[][] pos = {invSlotPos(c, 9), invSlotPos(c, 10), invSlotPos(c, 11), invSlotPos(c, 12)};
            BufferedImage off = InvBatchCases.shot(c, "off");
            slotText(c, true, "ALWAYS");
            c.ticks(3);
            BufferedImage on = InvBatchCases.shot(c, "on");
            // expected corner per slot: pet TL(0), minion TR(1), boots BL(2), potion BR(3)
            String[] names = {"pet", "minion", "boots", "potion"};
            StringBuilder note = new StringBuilder("GUI " + gs + " changed px in the four 7x7 corner boxes [TL TR BL BR]: ");
            for (int i = 0; i < 4; i++) {
                int[] q = {quadrant(off, on, pos[i], gs, 0), quadrant(off, on, pos[i], gs, 1), quadrant(off, on, pos[i], gs, 2),
                        quadrant(off, on, pos[i], gs, 3)};
                note.append(names[i]).append(java.util.Arrays.toString(q)).append(' ');
                int opposite = 3 - i;
                c.check(q[i] >= 20, names[i] + ": text missing from its corner (" + q[i] + " changed px)");
                c.check(q[opposite] == 0, names[i] + ": text spilled into the opposite corner (" + q[opposite] + " px)");
            }
            // The whole inventory grid (rows 1-3 of the main inventory): everything that changed in it must be inside the four
            // slots. The player model above it turns with the cursor, so the rest of the frame is not compared.
            int[] first = invSlotPos(c, 9);
            int[] last = invSlotPos(c, 17);
            int gx0 = (first[0] - 2) * gs;
            int gy0 = (first[1] - 2) * gs;
            int gx1 = (last[0] + 18) * gs;
            int gy1 = (first[1] + 3 * 18 + 2) * gs;
            int inSlots = 0;
            int outside = 0;
            for (int y = gy0; y < gy1; y++) {
                for (int x = gx0; x < gx1; x++) {
                    if (changedIn(off, on, x, y, x + 1, y + 1) == 0) {
                        continue;
                    }
                    boolean inside = false;
                    for (int[] p : pos) {
                        // 2 units of margin: bottom text sits low, so its shadow lands just under the slot
                        if (x >= (p[0] - 2) * gs && x < (p[0] + 18) * gs && y >= (p[1] - 2) * gs && y < (p[1] + 19) * gs) {
                            inside = true;
                        }
                    }
                    if (inside) {
                        inSlots++;
                    } else {
                        outside++;
                    }
                }
            }
            c.note(note + "; inventory grid: " + inSlots + " changed px at the four slots, " + outside + " elsewhere");
            c.check(outside == 0, "pixels changed outside the four slots in the inventory grid: " + outside);
            c.check(inSlots > 0, "nothing was drawn at all");
        } finally {
            closeScreen(c);
            HudEditorCases.restoreWindow(c, window, oldGui);
            InvBatchCases.restoreConfig(c, STC, path, old);
            InvBatchCases.inventory(c, Map.of());
        }
    }

    // ==== 962 modes ====================================================================================================

    static void modes(UiCase c) throws Exception {
        Path path = InvBatchCases.configPath(c, STC);
        byte[] old = InvBatchCases.read(path);
        int[] window = HudEditorCases.windowSize(c);
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        try {
            // the rule on its own
            String[][] rule = {{"ALWAYS", "false", "true"}, {"ALWAYS", "true", "true"}, {"HOLD_TO_SHOW", "false", "false"},
                    {"HOLD_TO_SHOW", "true", "true"}, {"HOLD_TO_HIDE", "false", "true"}, {"HOLD_TO_HIDE", "true", "false"}};
            for (String[] r : rule) {
                boolean got = c.onClient(mc -> (Boolean) Mod.staticCall(STF, "modeAllows", Mod.enumValue(STC + "$Mode", r[0]),
                        Boolean.parseBoolean(r[1])));
                c.check(String.valueOf(got).equals(r[2]), r[0] + " with key held=" + r[1] + " -> " + got + ", want " + r[2]);
            }

            InvBatchCases.survival(c);
            InvBatchCases.inventory(c, pictureItems());
            HudEditorCases.setWindow(c, 1280, 720, 2);
            int gs = c.onClient(mc -> mc.getWindow().getGuiScale());
            slotText(c, false, "ALWAYS");
            openInventory(c);
            cursorGui(c, 2, 2);
            int[][] pos = {invSlotPos(c, 9), invSlotPos(c, 10), invSlotPos(c, 11), invSlotPos(c, 12)};
            BufferedImage off = InvBatchCases.shot(c, "off");
            List<String> wrong = new ArrayList<>();
            StringBuilder note = new StringBuilder();
            String[][] cases = {
                    {"ALWAYS", "null", "drawn"}, {"ALWAYS", "true", "drawn"}, {"HOLD_TO_SHOW", "false", "hidden"},
                    {"HOLD_TO_SHOW", "true", "drawn"}, {"HOLD_TO_HIDE", "false", "drawn"}, {"HOLD_TO_HIDE", "true", "hidden"}};
            for (String[] k : cases) {
                slotText(c, true, k[0]);
                keyHeld(c, STF, k[1].equals("null") ? null : Boolean.valueOf(k[1]));
                c.ticks(3);
                BufferedImage img = InvBatchCases.shot(c, k[0].toLowerCase(Locale.ROOT) + "-key-" + k[1]);
                int petPx = quadrant(off, img, pos[0], gs, 0);
                boolean visible = c.onClient(mc -> (Boolean) Mod.staticCall(STF, "visible"));
                note.append(k[0]).append("/key=").append(k[1]).append(" pet px ").append(petPx).append(" visible ").append(visible).append("; ");
                boolean drawn = petPx >= 20;
                if (drawn != k[2].equals("drawn") || visible != drawn) {
                    wrong.add(k[0] + " key=" + k[1] + ": pet px " + petPx + ", visible() " + visible + ", expected " + k[2]);
                }
            }
            c.note(note.toString());
            c.check(wrong.isEmpty(), "show rule: " + wrong);

            // one type's switch, in the picture: the pet text goes, the minion text stays
            keyHeld(c, STF, null);
            slotText(c, true, "ALWAYS");
            setType(c, "PET_LEVEL", false);
            c.ticks(3);
            BufferedImage noPet = InvBatchCases.shot(c, "pet-level-off");
            int pet = quadrant(off, noPet, pos[0], gs, 0);
            int minion = quadrant(off, noPet, pos[1], gs, 1);
            c.note("Pet Level off: pet corner " + pet + " px, minion corner " + minion + " px");
            c.check(pet == 0, "pet text still drawn with Pet Level off: " + pet + " px");
            c.check(minion >= 20, "minion text vanished with only Pet Level off: " + minion + " px");
            setType(c, "PET_LEVEL", true);

            // settings survive a reload from disk
            c.onClient(mc -> {
                Object cfg = Mod.cfg(STC);
                Mod.call(cfg, "setMode", Mod.enumValue(STC + "$Mode", "HOLD_TO_HIDE"));
                Mod.call(cfg, "setKeyCode", 70);
                Mod.call(cfg, "setSkyblockOnly", false);
                Mod.call(cfg, "setTypeEnabled", Mod.enumValue(STT, "ENCHANT_ABBREVIATION"), true);
                Mod.call(cfg, "setTypeEnabled", Mod.enumValue(STT, "MINION_TIER"), false);
                Mod.call(cfg, "save");
                Mod.staticCall(STC, "load");
                return null;
            });
            String back = c.onClient(mc -> {
                Object cfg = Mod.cfg(STC);
                return Mod.call(cfg, "isEnabledRaw") + "/" + Mod.call(cfg, "getMode") + "/" + Mod.call(cfg, "getKeyCode") + "/"
                        + Mod.call(cfg, "isSkyblockOnly") + "/" + Mod.call(cfg, "isTypeEnabled", Mod.enumValue(STT, "ENCHANT_ABBREVIATION"))
                        + "/" + Mod.call(cfg, "isTypeEnabled", Mod.enumValue(STT, "MINION_TIER"));
            });
            c.note("after save + load: " + back);
            c.check(back.equals("true/HOLD_TO_HIDE/70/false/true/false"), "settings after a reload: " + back);
        } finally {
            keyHeld(c, STF, null);
            closeScreen(c);
            HudEditorCases.restoreWindow(c, window, oldGui);
            InvBatchCases.restoreConfig(c, STC, path, old);
            InvBatchCases.inventory(c, Map.of());
        }
    }

    // ==== 963 compactor contents =======================================================================================

    private static ItemStack compactor(String id, String... slotKeyValue) {
        CompoundTag t = tag(id);
        for (int i = 0; i + 1 < slotKeyValue.length; i += 2) {
            t.putString(slotKeyValue[i], slotKeyValue[i + 1]);
        }
        return mk(Items.PLAYER_HEAD, "Personal Compactor", t, "", "Lore", "");
    }

    private static String contents(UiCase c, ItemStack s) {
        return c.onClient(mc -> {
            Object o = Mod.staticCall(CPF, "contentsOf", s);
            if (o == null) {
                return "null";
            }
            return Mod.call(o, "deletor") + " " + Mod.call(o, "tier") + " " + Mod.call(o, "rows") + "x" + Mod.call(o, "cols")
                    + " " + Mod.call(o, "slots") + " active=" + Mod.call(o, "active");
        });
    }

    static void contentsRead(UiCase c) throws Exception {
        ItemStack c7 = compactor("PERSONAL_COMPACTOR_7000", "personal_compact_0", "ENCHANTED_COBBLESTONE",
                "personal_compact_5", "ENCHANTED_DIAMOND", "personal_compact_11", "WHEAT");
        String r7 = contents(c, c7);
        c.note("7000: " + r7);
        c.check(r7.equals("false 7000 2x6 {0=ENCHANTED_COBBLESTONE, 5=ENCHANTED_DIAMOND, 11=WHEAT} active=null"), "7000 read as: " + r7);

        CompoundTag del = tag("PERSONAL_DELETOR_4000");
        del.putString("personal_deletor_0", "COBBLESTONE");
        del.putBoolean("PERSONAL_DELETOR_ACTIVE", true);
        String r4 = contents(c, mk(Items.PLAYER_HEAD, "Personal Deletor", del));
        c.note("deletor 4000: " + r4);
        c.check(r4.equals("true 4000 1x1 {0=COBBLESTONE} active=true"), "deletor 4000 read as: " + r4);

        String r5 = contents(c, compactor("PERSONAL_COMPACTOR_5000", "personal_compact_2", "ENCHANTED_IRON"));
        String r6 = contents(c, compactor("PERSONAL_COMPACTOR_6000", "personal_compact_6", "ENCHANTED_IRON"));
        c.note("5000: " + r5 + "; 6000: " + r6);
        c.check(r5.equals("false 5000 1x3 {2=ENCHANTED_IRON} active=null"), "5000 read as: " + r5);
        c.check(r6.equals("false 6000 1x7 {6=ENCHANTED_IRON} active=null"), "6000 read as: " + r6);

        // a deletor key on a compactor is not its slot; hostile and malformed keys are dropped
        String mixed = contents(c, compactor("PERSONAL_COMPACTOR_7000", "personal_deletor_0", "COBBLESTONE",
                "personal_compact_99999999999", "X", "personal_compact_-1", "Y", "personal_compact_abc", "Z", "personal_compact_3", ""));
        c.note("foreign / hostile keys: " + mixed);
        c.check(mixed.equals("false 7000 2x6 {} active=null"), "foreign and hostile keys were read: " + mixed);

        // not a compactor
        String aotv = contents(c, mk(Items.DIAMOND_SWORD, "Aspect of the Void", tag("ASPECT_OF_THE_VOID")));
        String bare = contents(c, new ItemStack(Items.STONE));
        String fake = contents(c, mk(Items.PLAYER_HEAD, "Personal Compactor", tag("PERSONAL_COMPACTOR_7000_FAKE")));
        c.check(aotv.equals("null") && bare.equals("null") && fake.equals("null"), "non-compactors read as: " + aotv + " / " + bare + " / " + fake);

        // the icons: the vanilla look-alike for an enchanted id glints, a plain one does not
        List<String> icons = c.onClient(mc -> {
            Object cont = Mod.staticCall(CPF, "contentsOf", c7);
            Map<?, ?> stacks = (Map<?, ?>) Mod.staticCall(CPF, "resolveStacks", cont);
            List<String> out = new ArrayList<>();
            for (var e : stacks.entrySet()) {
                ItemStack s = (ItemStack) e.getValue();
                out.add(e.getKey() + "=" + s.getItem() + (Boolean.TRUE.equals(s.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE)) ? "+glint" : ""));
            }
            return out;
        });
        c.note("icons: " + icons);
        c.check(icons.size() == 3 && icons.get(0).contains("cobblestone") && icons.get(0).endsWith("+glint")
                && icons.get(1).contains("diamond") && icons.get(1).endsWith("+glint")
                && icons.get(2).contains("wheat") && !icons.get(2).endsWith("+glint"), "icons: " + icons);
    }

    // ==== 964 / 965 drawn on hover =====================================================================================

    private static void compactorEnabled(UiCase c, boolean enabled, String mode) {
        c.onClient(mc -> {
            Object cfg = Mod.cfg(CPC);
            Mod.call(cfg, "setEnabled", enabled);
            Mod.call(cfg, "setMode", Mod.enumValue(CPC + "$Mode", mode));
            Mod.call(cfg, "setSkyblockOnly", false);
            return null;
        });
    }

    /** Puts {@code stack} in inventory slot 17 (right column, so its tooltip falls over the world, not over slots). */
    private static BufferedImage hoverShot(UiCase c, ItemStack stack, int[] hoverPos, String label) {
        InvBatchCases.inventory(c, Map.of(17, stack));
        c.ticks(2);
        cursorGui(c, hoverPos[0] + 8, hoverPos[1] + 8);
        return InvBatchCases.shot(c, label);
    }

    static void hoverDrawn(UiCase c) throws Exception {
        Path path = InvBatchCases.configPath(c, CPC);
        byte[] old = InvBatchCases.read(path);
        int[] window = HudEditorCases.windowSize(c);
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        try {
            InvBatchCases.survival(c);
            InvBatchCases.inventory(c, Map.of());
            HudEditorCases.setWindow(c, 1280, 720, 2);
            compactorEnabled(c, false, "ON_HOVER");
            openInventory(c);
            int[] pos = invSlotPos(c, 17);
            ItemStack big = compactor("PERSONAL_COMPACTOR_7000", "personal_compact_0", "ENCHANTED_COBBLESTONE", "personal_compact_5", "ENCHANTED_DIAMOND");
            ItemStack mid = compactor("PERSONAL_COMPACTOR_5000", "personal_compact_1", "ENCHANTED_IRON");
            ItemStack empty = compactor("PERSONAL_COMPACTOR_7000");

            BufferedImage offBig = hoverShot(c, big, pos, "big-off");
            int greyOffBig = greys(offBig);
            compactorEnabled(c, true, "ON_HOVER");
            BufferedImage onBig = hoverShot(c, big, pos, "big-on");
            BufferedImage onMid = hoverShot(c, mid, pos, "mid-on");
            BufferedImage offMid;
            compactorEnabled(c, false, "ON_HOVER");
            offMid = hoverShot(c, mid, pos, "mid-off");
            compactorEnabled(c, true, "ON_HOVER");
            BufferedImage onEmpty = hoverShot(c, empty, pos, "empty-on");
            compactorEnabled(c, false, "ON_HOVER");
            BufferedImage offEmpty = hoverShot(c, empty, pos, "empty-off");

            int extraBig = greys(onBig) - greyOffBig;
            int extraMid = greys(onMid) - greys(offMid);
            int extraEmpty = greys(onEmpty) - greys(offEmpty);
            int changedEmpty = changedIn(onEmpty, offEmpty, 0, 0, onEmpty.getWidth(), onEmpty.getHeight());
            c.note("slot-gray px added by the grid: compactor 7000 (2 items of 12) " + extraBig + ", 5000 (1 item of 3) " + extraMid
                    + ", empty 7000 " + extraEmpty + " (frame changed " + changedEmpty + " px: the '12 slots' line)");
            c.check(greyOffBig > 0, "the instrument saw no slot gray at all in the baseline frame");
            c.check(extraBig >= 8000, "7000 grid missing: only " + extraBig + " extra gray px");
            c.check(extraMid >= 1500, "5000 grid missing: only " + extraMid + " extra gray px");
            c.check(extraBig > 3 * extraMid, "the 7000 grid (" + extraBig + ") is not far bigger than the 5000's (" + extraMid + ")");
            c.check(extraEmpty <= 200, "an empty compactor drew a grid: " + extraEmpty + " extra gray px");
            c.check(changedEmpty > 0, "an empty compactor added nothing (no '12 slots' line): the feature did not act");
        } finally {
            closeScreen(c);
            HudEditorCases.restoreWindow(c, window, oldGui);
            InvBatchCases.restoreConfig(c, CPC, path, old);
            InvBatchCases.inventory(c, Map.of());
        }
    }

    static void hoverModes(UiCase c) throws Exception {
        Path path = InvBatchCases.configPath(c, CPC);
        byte[] old = InvBatchCases.read(path);
        int[] window = HudEditorCases.windowSize(c);
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        try {
            String[][] rule = {{"ON_HOVER", "false", "true"}, {"ON_HOVER", "true", "true"}, {"WHILE_HOLDING_KEY", "false", "false"},
                    {"WHILE_HOLDING_KEY", "true", "true"}};
            for (String[] r : rule) {
                boolean got = c.onClient(mc -> (Boolean) Mod.staticCall(CPF, "modeAllows", Mod.enumValue(CPC + "$Mode", r[0]),
                        Boolean.parseBoolean(r[1])));
                c.check(String.valueOf(got).equals(r[2]), r[0] + " with key held=" + r[1] + " -> " + got + ", want " + r[2]);
            }
            InvBatchCases.survival(c);
            InvBatchCases.inventory(c, Map.of());
            HudEditorCases.setWindow(c, 1280, 720, 2);
            compactorEnabled(c, false, "ON_HOVER");
            openInventory(c);
            int[] pos = invSlotPos(c, 17);
            ItemStack big = compactor("PERSONAL_COMPACTOR_7000", "personal_compact_0", "ENCHANTED_COBBLESTONE", "personal_compact_5", "ENCHANTED_DIAMOND");
            int base = greys(hoverShot(c, big, pos, "off"));
            String[][] cases = {{"true", "ON_HOVER", "null", "grid"}, {"true", "WHILE_HOLDING_KEY", "false", "none"},
                    {"true", "WHILE_HOLDING_KEY", "true", "grid"}, {"true", "ON_HOVER", "false", "grid"}, {"false", "ON_HOVER", "null", "none"},
                    {"false", "WHILE_HOLDING_KEY", "true", "none"}};
            List<String> wrong = new ArrayList<>();
            StringBuilder note = new StringBuilder();
            for (String[] k : cases) {
                compactorEnabled(c, Boolean.parseBoolean(k[0]), k[1]);
                keyHeld(c, CPF, k[2].equals("null") ? null : Boolean.valueOf(k[2]));
                BufferedImage img = hoverShot(c, big, pos, "enabled-" + k[0] + "-" + k[1].toLowerCase(Locale.ROOT) + "-key-" + k[2]);
                int extra = greys(img) - base;
                note.append(k[0]).append('/').append(k[1]).append("/key=").append(k[2]).append(" +").append(extra).append("; ");
                boolean grid = extra >= 8000;
                if (grid != k[3].equals("grid") || (!grid && extra > 200)) {
                    wrong.add("enabled=" + k[0] + " " + k[1] + " key=" + k[2] + ": +" + extra + " gray px, expected " + k[3]);
                }
            }
            c.note(note.toString());
            c.check(wrong.isEmpty(), "show rule: " + wrong);
        } finally {
            keyHeld(c, CPF, null);
            closeScreen(c);
            HudEditorCases.restoreWindow(c, window, oldGui);
            InvBatchCases.restoreConfig(c, CPC, path, old);
            InvBatchCases.inventory(c, Map.of());
        }
    }

    static void compactorSettings(UiCase c) throws Exception {
        Path path = InvBatchCases.configPath(c, CPC);
        byte[] old = InvBatchCases.read(path);
        try {
            c.onClient(mc -> {
                Object cfg = Mod.cfg(CPC);
                Mod.call(cfg, "setEnabled", true);
                Mod.call(cfg, "setMode", Mod.enumValue(CPC + "$Mode", "WHILE_HOLDING_KEY"));
                Mod.call(cfg, "setKeyCode", 70);
                Mod.call(cfg, "setSkyblockOnly", false);
                Mod.call(cfg, "save");
                Mod.staticCall(CPC, "load");
                return null;
            });
            String back = c.onClient(mc -> {
                Object cfg = Mod.cfg(CPC);
                return Mod.call(cfg, "isEnabledRaw") + "/" + Mod.call(cfg, "getMode") + "/" + Mod.call(cfg, "getKeyCode") + "/"
                        + Mod.call(cfg, "isSkyblockOnly");
            });
            c.note("after save + load: " + back);
            c.check(back.equals("true/WHILE_HOLDING_KEY/70/false"), "settings after a reload: " + back);
        } finally {
            InvBatchCases.restoreConfig(c, CPC, path, old);
        }
    }
}
