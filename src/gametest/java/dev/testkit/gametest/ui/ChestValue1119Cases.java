package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 1119-ui-chestvalue (chestvalue-1119): a fake "Chest" holding Alpha 3+2 (1000 each, one line "5x" worth 5000), Beta 4
 * (250 each), Gamma 7 (no price: "?" and left out of the total), Delta (10) and Epsilon (20). Worked out by hand: total
 * 5000 + 1000 + 10 + 20 = 6030, 7 unpriced, five lines. Checks the switch off draws nothing, merged stacks, the
 * valuer's numbers, both sorts, Max Lines "+N more", a recompute only when a slot changes, and that a button menu
 * (a pane with no Skyblock id), the Storage overview and an Other container with Other off show nothing.
 */
final class ChestValue1119Cases {

    private static final String CFG = "chestvalue.ChestValueConfig";
    private static final String CV = "chestvalue.ChestValue";

    private ChestValue1119Cases() {
    }

    private static ItemStack item(String id, String name, int count) {
        ItemStack s = new ItemStack(Items.PLAYER_HEAD, count);
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        s.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return s;
    }

    private static void open(UiCase c, String title, List<ItemStack> items) {
        c.onClient(mc -> {
            SimpleContainer box = new SimpleContainer(27);
            for (int i = 0; i < items.size(); i++) {
                box.setItem(i, items.get(i));
            }
            McCompat.setScreen(mc, new ContainerScreen(ChestMenu.threeRows(0, mc.player.getInventory(), box),
                    mc.player.getInventory(), Component.literal(title)));
            return null;
        });
        c.ticks(6);
    }

    private static void close(UiCase c) {
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            return null;
        });
        c.ticks(2);
    }

    private static List<ItemStack> chest() {
        return List.of(item("ALPHA", "Alpha", 3), item("BETA", "Beta", 4), item("GAMMA", "Gamma", 7),
                item("ALPHA", "Alpha", 2), item("DELTA", "Delta", 1), item("EPSILON", "Epsilon", 1));
    }

    private static Object last(UiCase c) {
        return c.onClient(mc -> Mod.staticCall(CV, "lastResultForTest"));
    }

    private static List<String> names(Object res) {
        List<String> out = new ArrayList<>();
        for (Object l : (List<?>) Mod.call(res, "lines")) {
            out.add(Mod.call(l, "name") + "=" + Mod.call(l, "count") + "x" + Math.round((Double) Mod.call(l, "value")));
        }
        return out;
    }

    static void chestValue(UiCase c) throws Exception {
        Object cfg = Mod.cfg(CFG);
        boolean wasOn = (Boolean) Mod.call(cfg, "isEnabled");
        boolean wasOther = (Boolean) Mod.call(cfg, "isOther");
        Object wasSort = Mod.call(cfg, "getSort");
        int wasLines = (Integer) Mod.call(cfg, "getMaxLines");
        Class<?> price = Mod.cls("pricetooltip.PriceData");
        try {
            Mod.staticCall("pricetooltip.PriceData", "markStartedForTest");
            Mod.staticCall("pricetooltip.PriceData", "clearForTest");
            dev.testkit.gametest.ui.R.setStatic(price, "lowestBin", Map.of("ALPHA", 1000.0, "BETA", 250.0, "DELTA", 10.0,
                    "EPSILON", 20.0));
            Mod.set(CFG, "setSort", Mod.enumValue(CFG + "$Sort", "VALUE"));
            Mod.set(CFG, "setMaxLines", 10);
            Mod.set(CFG, "setOther", false);

            Mod.set(CFG, "setEnabled", false);
            open(c, "Chest", chest());
            c.check(last(c) == null, "the panel was built with the switch off");
            close(c);

            Mod.set(CFG, "setEnabled", true);
            open(c, "Chest", chest());
            Object res = last(c);
            c.check(res != null, "the panel never built on the Chest - the feature never acted");
            c.note("lines: " + names(res) + " total " + Mod.call(res, "total"));
            c.check(Math.round((Double) Mod.call(res, "total")) == 6030, "total " + Mod.call(res, "total") + ", expected 6030");
            c.check((Integer) Mod.call(res, "unpriced") == 7, "unpriced " + Mod.call(res, "unpriced") + ", expected 7");
            c.check(names(res).equals(List.of("Alpha=5x5000", "Beta=4x1000", "Epsilon=1x20", "Delta=1x10", "Gamma=7x-1")),
                    "value order/merge: " + names(res));
            @SuppressWarnings("unchecked")
            List<String> lines = c.onClient(mc -> (List<String>) Mod.staticCall(CV, "linesForTest"));
            String all = String.join("\n", lines);
            c.check(all.contains("5x") && all.contains("Gamma") && all.contains("?") && all.contains("7 unpriced"),
                    "panel text: " + lines);

            // Recomputes only on a change: ticks add no compute, a slot change adds exactly one.
            int n0 = (Integer) c.onClient(mc -> Mod.staticCall(CV, "computesForTest"));
            c.ticks(20);
            int n1 = (Integer) c.onClient(mc -> Mod.staticCall(CV, "computesForTest"));
            c.check(n1 == n0, "recomputed " + (n1 - n0) + " times with no slot change");
            c.onClient(mc -> {
                ((ContainerScreen) McCompat.screen(mc)).getMenu().getSlot(1).set(item("BETA", "Beta", 8));
                return null;
            });
            c.ticks(6);
            int n2 = (Integer) c.onClient(mc -> Mod.staticCall(CV, "computesForTest"));
            c.check(n2 == n1 + 1, "a slot change caused " + (n2 - n1) + " computes, expected 1");
            c.check(Math.round((Double) Mod.call(last(c), "total")) == 7030, "total after change " + Mod.call(last(c), "total"));
            close(c);

            // Sort by name, Max Lines.
            Mod.set(CFG, "setSort", Mod.enumValue(CFG + "$Sort", "NAME"));
            Mod.set(CFG, "setMaxLines", 3);
            open(c, "Chest", chest());
            res = last(c);
            c.check(names(res).equals(List.of("Alpha=5x5000", "Beta=4x1000", "Delta=1x10")), "name order/cut: " + names(res));
            c.check((Integer) Mod.call(res, "more") == 2, "more " + Mod.call(res, "more") + ", expected 2");
            c.check(Math.round((Double) Mod.call(res, "total")) == 6030, "a cut changed the total");
            close(c);
            Mod.set(CFG, "setSort", Mod.enumValue(CFG + "$Sort", "VALUE"));
            Mod.set(CFG, "setMaxLines", 10);

            // Not covered: a button menu, the Storage overview, an Other container with Other off.
            open(c, "Other Menu", chest());
            c.check(last(c) == null, "an Other container showed with Other off");
            close(c);
            open(c, "Storage", chest());
            c.check(last(c) == null, "the Storage overview showed");
            close(c);
            Mod.set(CFG, "setOther", true);
            List<ItemStack> buttons = new ArrayList<>(chest());
            buttons.add(new ItemStack(Items.ARROW));
            open(c, "Reward Chest", buttons);
            c.check(last(c) == null, "a menu with a button pane showed as an Other container");
            close(c);
            open(c, "Other Menu", chest());
            c.check(last(c) != null, "Other Containers on, a plain item menu showed nothing");
            close(c);
            open(c, "Ender Chest (1/9)", chest());
            c.check(last(c) != null, "an Ender Chest page showed nothing");
            close(c);
        } finally {
            close(c);
            Mod.set(CFG, "setEnabled", wasOn);
            Mod.set(CFG, "setOther", wasOther);
            Mod.set(CFG, "setSort", wasSort);
            Mod.set(CFG, "setMaxLines", wasLines);
            Mod.staticCall("pricetooltip.PriceData", "clearForTest");
        }
    }

    private static List<String> panel(UiCase c) {
        @SuppressWarnings("unchecked")
        List<String> l = c.onClient(mc -> (List<String>) Mod.staticCall(CV, "linesForTest"));
        return l;
    }

    private static boolean wheel(UiCase c, double dx, double dy, double wheel) {
        return (Boolean) c.onClient(mc -> Mod.staticCall(CV, "scrollForTest",
                dx, dy, wheel));
    }

    private static ItemStack lore(ItemStack s, String line) {
        s.set(DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(List.of(Component.literal(line))));
        return s;
    }

    private static com.google.gson.JsonObject recipe(String... cells) {
        com.google.gson.JsonObject r = new com.google.gson.JsonObject();
        String[] grid = {"A1", "A2", "A3", "B1", "B2", "B3", "C1", "C2", "C3"};
        for (int i = 0; i < cells.length; i++) {
            r.addProperty(grid[i], cells[i]);
        }
        com.google.gson.JsonObject j = new com.google.gson.JsonObject();
        j.add("recipe", r);
        return j;
    }

    /**
     * 1125-ui-chestvalue-minion (chestvalue-minions-1125): a fake 54-slot "Glowstone Minion III" in the layout his logger
     * recorded (head 4, skin 10, fuel 19, hopper 28, upgrades 37/46, storage 21-25/30-34/39-43, black panes elsewhere).
     * By hand: storage 64 dust x2 = 128 + 2 enchanted dust x400 = 800; hopper coins 1,500 (lore); Enchanted Hopper 10,000;
     * Enchanted Lava Bucket 50,000; Super Compactor 300,000; Diamond Spreading unpriced "?"; empty skin pane = nothing.
     * Total 362,428. Craft Cost I-III from seeded recipes: I 8x10 dust + pickaxe 5 = 165, II 8x20 dust (+ tier I) = 320,
     * III 7x40 dust + an unpriced item = 560 + ? -> 1,045 + ?, not in the total. Generated 1,234 from resources_generated.
     */
    static void minion(UiCase c) throws Exception {
        Object cfg = Mod.cfg(CFG);
        boolean wasOn = (Boolean) Mod.call(cfg, "isEnabled");
        boolean wasMinions = (Boolean) Mod.call(cfg, "isMinions");
        Object wasSort = Mod.call(cfg, "getSort");
        int wasLines = (Integer) Mod.call(cfg, "getMaxLines");
        Class<?> price = Mod.cls("pricetooltip.PriceData");
        try {
            Mod.staticCall("pricetooltip.PriceData", "markStartedForTest");
            Mod.staticCall("pricetooltip.PriceData", "clearForTest");
            R.setStatic(price, "lowestBin", Map.of("GLOWSTONE_DUST", 2.0, "ENCHANTED_GLOWSTONE_DUST", 400.0,
                    "ENCHANTED_HOPPER", 10000.0, "ENCHANTED_LAVA_BUCKET", 50000.0, "SUPER_COMPACTOR_3000", 300000.0,
                    "WOOD_PICKAXE", 5.0));
            String nd = "itembrowser.NeuItemData";
            String d10 = "GLOWSTONE_DUST:10", d20 = "GLOWSTONE_DUST:20", d40 = "GLOWSTONE_DUST:40";
            Mod.staticCall(nd, "put", "GLOWSTONE_GENERATOR_1", recipe(d10, d10, d10, d10, "WOOD_PICKAXE:1", d10, d10, d10, d10));
            Mod.staticCall(nd, "put", "GLOWSTONE_GENERATOR_2", recipe(d20, d20, d20, d20, "GLOWSTONE_GENERATOR_1:1", d20, d20, d20, d20));
            Mod.staticCall(nd, "put", "GLOWSTONE_GENERATOR_3", recipe(d40, d40, d40, d40, "GLOWSTONE_GENERATOR_2:1", d40, d40, d40,
                    "UNPRICED_THING_1125:1"));
            Mod.set(CFG, "setSort", Mod.enumValue(CFG + "$Sort", "VALUE"));
            Mod.set(CFG, "setMaxLines", 10);
            Mod.set(CFG, "setEnabled", true);
            Mod.set(CFG, "setMinions", true);

            ItemStack head = item("GLOWSTONE_GENERATOR_3", "Glowstone Minion III", 1);
            CompoundTag ht = head.get(DataComponents.CUSTOM_DATA).copyTag();
            ht.putInt("resources_generated", 1234);
            head.set(DataComponents.CUSTOM_DATA, CustomData.of(ht));
            ItemStack[] slots = new ItemStack[54];
            for (int i = 0; i < 54; i++) {
                slots[i] = new ItemStack(Items.BLACK_STAINED_GLASS_PANE);
            }
            for (int i : new int[]{21, 22, 23, 24, 25, 30, 31, 32, 33, 34, 39, 40, 41, 42, 43}) {
                slots[i] = ItemStack.EMPTY;
            }
            slots[4] = head;
            slots[10] = new ItemStack(Items.LIME_STAINED_GLASS_PANE);
            slots[19] = item("ENCHANTED_LAVA_BUCKET", "Enchanted Lava Bucket", 1);
            slots[28] = lore(item("ENCHANTED_HOPPER", "Enchanted Hopper", 1), "Held Coins: 1,500");
            slots[37] = item("SUPER_COMPACTOR_3000", "Super Compactor 3000", 1);
            slots[46] = item("DIAMOND_SPREADING", "Diamond Spreading", 1);
            slots[21] = item("GLOWSTONE_DUST", "Glowstone Dust", 64);
            slots[30] = item("ENCHANTED_GLOWSTONE_DUST", "Enchanted Glowstone Dust", 2);
            slots[48] = new ItemStack(Items.CHEST);

            Mod.set(CFG, "setMinions", false);
            openMinion(c, slots);
            c.check(c.onClient(mc -> Mod.staticCall(CV, "minionForTest")) == null && panel(c).isEmpty(),
                    "the minion panel was built with Minions off: " + panel(c));
            close(c);
            Mod.set(CFG, "setMinions", true);
            openMinion(c, slots);
            Object res = c.onClient(mc -> Mod.staticCall(CV, "minionForTest"));
            c.check(res != null, "no minion panel on \"Glowstone Minion III\" - the feature never acted");
            List<String> ls = panel(c);
            String all = String.join("|", ls);
            c.note("panel: " + ls);
            c.check(Math.round((Double) Mod.call(res, "total")) == 362428, "total " + Mod.call(res, "total") + ", expected 362428");
            c.check(Math.round((Double) Mod.call(res, "hopperCoins")) == 1500, "hopper coins " + Mod.call(res, "hopperCoins"));
            c.check((Long) Mod.call(res, "generated") == 1234L, "generated " + Mod.call(res, "generated"));
            c.check(((List<?>) Mod.call(res, "parts")).size() == 4, "parts " + Mod.call(res, "parts") + ", expected 4 (no skin)");
            Object craft = Mod.call(res, "craft");
            c.check(Math.round((Double) Mod.call(craft, "priced")) == 1045 && (Integer) Mod.call(craft, "unknown") == 1
                    && !(Boolean) Mod.call(craft, "loading") && "I-III".equals(Mod.call(craft, "tiers")), "craft " + craft);
            c.check(ls.get(0).contains("Minion Value") && ls.get(0).contains("362.4k"), "title line: " + ls.get(0));
            c.check(all.contains("64x") && all.contains("Glowstone Dust") && all.contains("Hopper Coins") && all.contains("1.5k")
                    && all.contains("Fuel: ") && all.contains("Upgrade: ") && all.contains("Diamond Spreading  §8?")
                    && all.contains("Generated: §f1,234") && all.contains("Craft Cost (I-III)") && all.contains("+ ?")
                    && !all.contains("Skin:"), "panel text: " + ls);
            close(c);
            c.check(c.onClient(mc -> Mod.staticCall(CV, "minionForTest")) == null, "minion result kept after the menu closed");
        } finally {
            close(c);
            Mod.set(CFG, "setEnabled", wasOn);
            Mod.set(CFG, "setMinions", wasMinions);
            Mod.set(CFG, "setSort", wasSort);
            Mod.set(CFG, "setMaxLines", wasLines);
            Mod.staticCall("pricetooltip.PriceData", "clearForTest");
        }
    }

    private static void openMinion(UiCase c, ItemStack[] slots) {
        c.onClient(mc -> {
            SimpleContainer box = new SimpleContainer(54);
            for (int i = 0; i < slots.length; i++) {
                box.setItem(i, slots[i].copy());
            }
            McCompat.setScreen(mc, new ContainerScreen(ChestMenu.sixRows(0, mc.player.getInventory(), box),
                    mc.player.getInventory(), Component.literal("Glowstone Minion III")));
            return null;
        });
        c.ticks(6);
    }

    /** 1125-ui-chestvalue-scroll: five lines, window of three: the wheel over the panel moves it, clamps, resets. */
    static void scroll(UiCase c) throws Exception {
        Object cfg = Mod.cfg(CFG);
        boolean wasOn = (Boolean) Mod.call(cfg, "isEnabled");
        Object wasSort = Mod.call(cfg, "getSort");
        int wasLines = (Integer) Mod.call(cfg, "getMaxLines");
        Class<?> price = Mod.cls("pricetooltip.PriceData");
        try {
            Mod.staticCall("pricetooltip.PriceData", "markStartedForTest");
            Mod.staticCall("pricetooltip.PriceData", "clearForTest");
            dev.testkit.gametest.ui.R.setStatic(price, "lowestBin", Map.of("ALPHA", 1000.0, "BETA", 250.0, "DELTA", 10.0,
                    "EPSILON", 20.0));
            Mod.set(CFG, "setSort", Mod.enumValue(CFG + "$Sort", "NAME"));
            Mod.set(CFG, "setMaxLines", 3);
            Mod.set(CFG, "setEnabled", true);
            open(c, "Chest", chest());
            int[] r = (int[]) c.onClient(mc -> Mod.staticCall(CV, "panelRectForTest"));
            double in = r[0] + r[2] / 2.0, inY = r[1] + r[3] / 2.0;
            c.note("rect " + java.util.Arrays.toString(r) + " lines " + panel(c));
            c.check(r[2] > 8 && String.join("|", panel(c)).contains("Alpha") && String.join("|", panel(c)).contains("+2 more"),
                    "panel not built as expected: " + panel(c));
            c.check(!wheel(c, r[0] - 30, inY, -1), "the wheel outside the panel was consumed");
            c.check(wheel(c, in, inY, -1), "the wheel over an overflowing panel was not consumed");
            String s1 = String.join("|", panel(c));
            c.check(!s1.contains("Alpha") && s1.contains("Beta") && s1.contains("+1 more") && s1.contains("1 above"), "after 1 notch: " + s1);
            c.check(wheel(c, in, inY, -1), "second notch not consumed");
            String s2 = String.join("|", panel(c));
            c.check(s2.contains("Gamma") && !s2.contains("more") && s2.contains("2 above"), "after 2 notches: " + s2);
            c.check(wheel(c, in, inY, -1) && String.join("|", panel(c)).equals(s2), "scrolling past the end moved or was not consumed");
            c.check(wheel(c, in, inY, 1) && String.join("|", panel(c)).contains("Beta"), "scroll back up failed: " + panel(c));
            wheel(c, in, inY, 1);
            wheel(c, in, inY, 1);
            c.check(String.join("|", panel(c)).contains("Alpha") && String.join("|", panel(c)).contains("+2 more"), "top clamp: " + panel(c));
            wheel(c, in, inY, -1);
            close(c);
            open(c, "Chest", chest());
            c.check(String.join("|", panel(c)).contains("Alpha"), "offset kept on a new container: " + panel(c));
            close(c);
            Mod.set(CFG, "setMaxLines", 10);
            open(c, "Chest", chest());
            int[] r2 = (int[]) c.onClient(mc -> Mod.staticCall(CV, "panelRectForTest"));
            c.check(!wheel(c, r2[0] + r2[2] / 2.0, r2[1] + r2[3] / 2.0, -1), "a short list consumed the wheel");
            close(c);
            c.check(!wheel(c, in, inY, -1), "the wheel was consumed with no container open");
        } finally {
            close(c);
            Mod.set(CFG, "setEnabled", wasOn);
            Mod.set(CFG, "setSort", wasSort);
            Mod.set(CFG, "setMaxLines", wasLines);
            Mod.staticCall("pricetooltip.PriceData", "clearForTest");
        }
    }
}
