package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.multiplayer.chat.GuiMessage;
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
import java.util.Map;

/**
 * autoreclaim-1117: Auto Reclaim's highlight and report. 1117-ui-reclaim-highlight fills a slot holding a recombobulated
 * VERY SPECIAL accessory (and not a Special accessory with no recombobulator) with the theme accent, only while the switch is
 * ON; 1117-ui-reclaim-report runs /autoreclaim and reads the chat; 1117-ui-reclaim-legit-no-cheat checks the cheat switch has
 * no effect on a legit jar.
 */
public final class AutoReclaim1117UiCases {

    public static final String CFG = "autoreclaim.AutoReclaimConfig";

    private AutoReclaim1117UiCases() {
    }

    /** A Skyblock stack: custom_data {id, rarity_upgrades?, enchantments?} and a lore line carrying the rarity. */
    public static ItemStack stack(Item item, String id, int upgrades, String rarityLine, String... enchants) {
        ItemStack s = new ItemStack(item);
        CompoundTag t = new CompoundTag();
        t.putString("id", id);
        if (upgrades > 0) {
            t.putInt("rarity_upgrades", upgrades);
        }
        if (enchants.length > 0) {
            CompoundTag e = new CompoundTag();
            for (String en : enchants) {
                e.putInt(en, 1);
            }
            t.put("enchantments", e);
        }
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
        s.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Some lore"), Component.literal(rarityLine))));
        return s;
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

    private static int[] slotPos(UiCase c, int invIndex) {
        return c.onClient(mc -> {
            AbstractContainerScreen<?> s = (AbstractContainerScreen<?>) McCompat.screen(mc);
            int left = (Integer) Mod.field(s, "leftPos");
            int top = (Integer) Mod.field(s, "topPos");
            for (Slot slot : s.getMenu().slots) {
                if (slot.container == mc.player.getInventory() && slot.getContainerSlot() == invIndex) {
                    return new int[]{left + slot.x, top + slot.y};
                }
            }
            return null;
        });
    }

    /** Pixels in the rectangle that differ clearly between two frames. */
    private static int changed(BufferedImage a, BufferedImage b, int x0, int y0, int x1, int y1) {
        int n = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int p = InvBatchCases.rgb(a, x, y);
                int q = InvBatchCases.rgb(b, x, y);
                int d = Math.abs((p >> 16 & 255) - (q >> 16 & 255)) + Math.abs((p >> 8 & 255) - (q >> 8 & 255))
                        + Math.abs((p & 255) - (q & 255));
                if (d > 60) {
                    n++;
                }
            }
        }
        return n;
    }

    private static Map<Integer, ItemStack> sample() {
        Map<Integer, ItemStack> items = new LinkedHashMap<>();
        items.put(9, stack(Items.GOLDEN_APPLE, "MATCH_ACC", 1, "VERY SPECIAL ACCESSORY"));
        items.put(10, stack(Items.GOLDEN_CARROT, "NEAR_ACC", 0, "SPECIAL ACCESSORY"));
        items.put(11, stack(Items.GOLDEN_SWORD, "MATCH_SWORD", 0, "LEGENDARY SWORD", "divine_gift"));
        items.put(12, stack(Items.STONE_SWORD, "NEAR_SWORD", 0, "EPIC SWORD", "looting"));
        return items;
    }

    static void highlight(UiCase c) throws Exception {
        Path path = InvBatchCases.configPath(c, CFG);
        byte[] old = InvBatchCases.read(path);
        int[] window = HudEditorCases.windowSize(c);
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        try {
            InvBatchCases.survival(c);
            InvBatchCases.inventory(c, sample());
            c.onClient(mc -> {
                Mod.call(Mod.cfg(CFG), "setEnabled", false);
                return null;
            });
            HudEditorCases.setWindow(c, 1280, 720, 2);
            int gs = c.onClient(mc -> mc.getWindow().getGuiScale());
            openInventory(c);
            c.ctx().getInput().setCursorPos(2, 2);
            c.ticks(3);
            int[][] pos = {slotPos(c, 9), slotPos(c, 10), slotPos(c, 11), slotPos(c, 12)};
            BufferedImage off = InvBatchCases.shot(c, "off");
            c.onClient(mc -> {
                Mod.call(Mod.cfg(CFG), "setEnabled", true);
                return null;
            });
            c.ticks(3);
            BufferedImage on = InvBatchCases.shot(c, "on");
            int[] n = new int[4];
            for (int i = 0; i < 4; i++) {
                n[i] = changed(off, on, pos[i][0] * gs, pos[i][1] * gs, (pos[i][0] + 16) * gs, (pos[i][1] + 16) * gs);
            }
            List<String> why = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                int idx = 9 + i;
                ItemStack st = c.onClient(mc -> mc.player.getInventory().getItem(idx));
                why.add(String.valueOf((Object) c.onClient(mc -> (Object) Mod.staticCall("autoreclaim.ReclaimRules", "reason", st))));
            }
            c.note("changed px per slot " + java.util.Arrays.toString(n) + " (of " + 256 * gs * gs + "); rule answers " + why);
            c.check("Recombobulator".equals(why.get(0)) && why.get(1).equals("null") && "Divine Gift".equals(why.get(2))
                    && why.get(3).equals("null"), "rule answers " + why);
            c.check(n[0] >= 100 * gs * gs, "the recombobulated Very Special accessory is not filled: " + n[0] + " px");
            c.check(n[2] >= 100 * gs * gs, "the Divine Gift item is not filled: " + n[2] + " px");
            c.check(n[1] == 0 && n[3] == 0, "a near miss was filled: " + n[1] + ", " + n[3]);
        } finally {
            closeScreen(c);
            HudEditorCases.restoreWindow(c, window, oldGui);
            InvBatchCases.restoreConfig(c, CFG, path, old);
            InvBatchCases.inventory(c, Map.of());
        }
    }

    private static List<String> chat(UiCase c) {
        return c.onClient(mc -> {
            @SuppressWarnings("unchecked")
            List<GuiMessage> all = (List<GuiMessage>) Mod.field(McCompat.chat(mc), "allMessages");
            List<String> out = new ArrayList<>();
            for (GuiMessage m : all) {
                out.add(m.content().getString());
            }
            return out;
        });
    }

    static void report(UiCase c) throws Exception {
        Path path = InvBatchCases.configPath(c, CFG);
        byte[] old = InvBatchCases.read(path);
        try {
            InvBatchCases.survival(c);
            InvBatchCases.inventory(c, sample());
            c.onClient(mc -> {
                McCompat.clearChatAndToasts(mc);
                Mod.call(Mod.cfg(CFG), "setEnabled", false);
                mc.player.connection.sendCommand("autoreclaim");
                return null;
            });
            c.ticks(5);
            c.check(chat(c).stream().anyMatch(l -> l.contains("Turn Auto Reclaim on first")),
                    "switch OFF: no 'turn it on' line; chat " + chat(c));
            c.onClient(mc -> {
                McCompat.clearChatAndToasts(mc);
                Mod.call(Mod.cfg(CFG), "setEnabled", true);
                mc.player.connection.sendCommand("autoreclaim");
                return null;
            });
            c.ticks(8);
            List<String> lines = chat(c);
            c.note("chat: " + lines);
            c.check(lines.stream().anyMatch(l -> l.contains("2 item(s) to reclaim")),
                    "want '2 item(s) to reclaim' (the two matches, not the two near misses); chat " + lines);
            c.check(lines.stream().anyMatch(l -> l.contains("Recombobulator")) && lines.stream().anyMatch(l -> l.contains("Divine Gift")),
                    "each found item is listed with its reason; chat " + lines);
            c.check(lines.stream().noneMatch(l -> l.contains("NEAR")), "a near miss was listed; chat " + lines);
        } finally {
            InvBatchCases.restoreConfig(c, CFG, path, old);
            InvBatchCases.inventory(c, Map.of());
        }
    }

    static void legitNoCheat(UiCase c) throws Exception {
        Path path = InvBatchCases.configPath(c, CFG);
        byte[] old = InvBatchCases.read(path);
        try {
            boolean cheat = Mod.isCheat();
            boolean on = c.onClient(mc -> {
                Mod.call(Mod.cfg(CFG), "setPullAndReclaim", true);
                return (Boolean) Mod.call(Mod.cfg(CFG), "isPullAndReclaim");
            });
            c.note("cheat jar " + cheat + ", Pull And Reclaim reads " + on);
            c.check(on == cheat, "Pull And Reclaim reads " + on + " on a " + (cheat ? "cheat" : "legit") + " jar");
        } finally {
            InvBatchCases.restoreConfig(c, CFG, path, old);
        }
    }
}
