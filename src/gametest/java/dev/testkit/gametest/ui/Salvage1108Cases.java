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
import net.minecraft.world.item.component.ItemLore;

import java.util.List;
import java.util.Map;

/**
 * 1108-ui-salvage-highlight (featF-1108, Salvage Helper): with lowest-BIN prices faked, cheap dungeon gear (lore
 * "DUNGEON <slot>", price under the limit) is marked on a "Salvage Items" screen and on "Trades", an expensive piece,
 * a "DUNGEON ITEM" and a stick are not, a plain chest is never marked, and the switch off marks nothing. The marked
 * counter must rise, so a screen the mixin never drew cannot pass.
 */
final class Salvage1108Cases {

    private static final String CFG = "itemhighlight.DungeonItemHighlightConfig";
    private static final String HL = "itemhighlight.DungeonItemHighlighter";

    private Salvage1108Cases() {
    }

    private static ItemStack gear(String id, String lore) {
        ItemStack s = new ItemStack(Items.IRON_HELMET);
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        s.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(lore))));
        return s;
    }

    static void salvage(UiCase c) throws Exception {
        Class<?> price = Mod.cls("pricetooltip.PriceData");
        Mod.staticCall("pricetooltip.PriceData", "markStartedForTest");
        Mod.staticCall("pricetooltip.PriceData", "clearForTest");
        R.setStatic(price, "lowestBin", Map.of("CHEAP_HELM", 20_000.0, "RICH_HELM", 9_000_000.0));
        Mod.set(CFG, "setEnabled", true);
        Mod.set(CFG, "setSalvage", true);
        Mod.set(CFG, "setSell", true);

        // the verdict itself
        boolean[] v = c.onClient(mc -> new boolean[]{
                (Boolean) Mod.staticCall(HL, "cheapGear", gear("CHEAP_HELM", "COMMON DUNGEON HELMET"), 100_000L),
                (Boolean) Mod.staticCall(HL, "cheapGear", gear("RICH_HELM", "COMMON DUNGEON HELMET"), 100_000L),
                (Boolean) Mod.staticCall(HL, "cheapGear", gear("CHEAP_HELM", "COMMON DUNGEON ITEM"), 100_000L),
                (Boolean) Mod.staticCall(HL, "cheapGear", new ItemStack(Items.STICK), 100_000L)});
        c.check(v[0], "cheap dungeon gear was not judged cheap");
        c.check(!v[1], "an expensive piece was judged cheap");
        c.check(!v[2], "a DUNGEON ITEM was judged gear");
        c.check(!v[3], "a stick was judged cheap");

        int base;
        for (String title : new String[]{"Salvage Items", "Trades", "Chest"}) {
            base = marked(c);
            open(c, title);
            c.ticks(8);
            int gained = marked(c) - base;
            c.note(title + ": marked " + gained);
            if (title.equals("Chest")) {
                c.check(gained == 0, "a plain chest was marked (" + gained + ")");
            } else {
                c.check(gained > 0, title + " drew no highlight - the feature never acted");
            }
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
            c.ticks(2);
        }

        Mod.set(CFG, "setEnabled", false);
        base = marked(c);
        open(c, "Salvage Items");
        c.ticks(8);
        c.check(marked(c) == base, "marked with the switch off");
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            return null;
        });
        Mod.staticCall("pricetooltip.PriceData", "clearForTest");
    }

    private static int marked(UiCase c) {
        return c.onClient(mc -> (Integer) Mod.staticCall(HL, "marked"));
    }

    private static void open(UiCase c, String title) {
        c.onClient(mc -> {
            SimpleContainer box = new SimpleContainer(27);
            box.setItem(0, gear("CHEAP_HELM", "COMMON DUNGEON HELMET"));
            box.setItem(1, gear("RICH_HELM", "COMMON DUNGEON HELMET"));
            box.setItem(2, gear("CHEAP_HELM", "COMMON DUNGEON ITEM"));
            box.setItem(3, new ItemStack(Items.STICK));
            ChestMenu menu = ChestMenu.threeRows(0, mc.player.getInventory(), box);
            McCompat.setScreen(mc, new ContainerScreen(menu, mc.player.getInventory(), Component.literal(title)));
            return null;
        });
    }
}
