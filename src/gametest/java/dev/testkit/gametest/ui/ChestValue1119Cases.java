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
}
