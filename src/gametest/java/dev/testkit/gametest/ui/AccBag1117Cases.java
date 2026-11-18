package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ChestMenu;
import dev.testkit.compat.McItems;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 1117-accbag-* (accbag-0273-1117, SkyBlock 0.27.3 Accessory Bag). The real bag (owner's capture) is titled "(1/6) Accessory
 * Bag"; an owned accessory is a player head or item carrying its SkyBlock id, a missing one a gray dye with a name and no id,
 * the Search / Sort / Next Page buttons are other id-less items, and the Rift's own bag is titled plain "Accessory Bag".
 * Fixture: Alpha Talisman (A_T1) and Delta Talisman owned; placeholders for Alpha Ring (family A is owned, so not missing),
 * Gamma Ring, Beta Talisman AND Beta Ring (one family, "lowest tier only": counted once) and an accessory the catalog does not
 * list. Expected header: "2 owned, 6 MP, 3 missing".
 */
final class AccBag1117Cases {

    private static final String CFG = "accadvisor.AccessoryAdvisorConfig";
    private static final String ADV = "accadvisor.AccessoryAdvisor";

    private AccBag1117Cases() {
    }

    private static ItemStack owned(String id) {
        ItemStack s = new ItemStack(Items.PLAYER_HEAD);
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return s;
    }

    private static ItemStack named(net.minecraft.world.item.Item item, String name) {
        ItemStack s = new ItemStack(item);
        s.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return s;
    }

    private static void open(UiCase c, String title, List<ItemStack> items) {
        c.onClient(mc -> {
            SimpleContainer box = new SimpleContainer(27);
            for (int i = 0; i < items.size(); i++) {
                box.setItem(i, items.get(i));
            }
            ChestMenu menu = ChestMenu.threeRows(0, mc.player.getInventory(), box);
            McCompat.setScreen(mc, new ContainerScreen(menu, mc.player.getInventory(), Component.literal(title)));
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

    @SuppressWarnings("unchecked")
    private static String panel(UiCase c) {
        return String.join("\n", (List<String>) c.onClient(mc -> Mod.staticCall(ADV, "linesForTest")));
    }

    private static List<String> rankIds(UiCase c) {
        Object r = c.onClient(mc -> Mod.staticCall(ADV, "rankForTest", Mod.enumValue(CFG + "$Mode", "CHEAPEST_PER_MP"), 15));
        List<String> out = new ArrayList<>();
        for (Object row : (List<?>) Mod.call(r, "rows")) {
            out.add((String) Mod.call(row, "id"));
        }
        return out;
    }

    static void ownedMissing(UiCase c) throws Exception {
        boolean was = (Boolean) Mod.call(Mod.cfg(CFG), "isEnabled");
        try {
            AccAdvisor1113Cases.fixture(c);
            c.onClient(mc -> {
                Mod.staticCall(ADV, "catalogForTest", Map.of(
                        "A_T1", "Alpha Talisman|COMMON", "A_R2", "Alpha Ring|UNCOMMON", "B_T", "Beta Talisman|EPIC",
                        "B_R", "Beta Ring|LEGENDARY", "C_R", "Gamma Ring|UNCOMMON", "D_T", "Delta Talisman|COMMON"));
                Mod.staticCall(ADV, "setFamilies", Map.of("A_T1", List.of("A_R2"), "B_T", List.of("B_R")), Set.of());
                return null;
            });
            Mod.set(CFG, "setEnabled", true);
            open(c, "(1/6) Accessory Bag", List.of(owned("A_T1"), owned("D_T"), named(McItems.GRAY_DYE, "Alpha Ring"),
                    named(McItems.GRAY_DYE, "Gamma Ring"), named(McItems.GRAY_DYE, "Beta Talisman"),
                    named(McItems.GRAY_DYE, "Beta Ring"), named(McItems.GRAY_DYE, "Mystery Charm"),
                    named(Items.OAK_SIGN, "Search"), named(Items.HOPPER, "Sort"), named(Items.BARRIER, "Close")));
            String p = panel(c);
            c.note("panel: " + p);
            c.check(c.onClient(mc -> Mod.staticCall(ADV, "lastResultForTest")) != null, "the panel never built - the feature never acted");
            c.check(p.contains("2 owned, 6 MP, 3 missing"), "owned vs missing not told apart (want '2 owned, 6 MP, 3 missing'): " + p);
            c.check(p.contains("Open all bag pages (1/6 seen)"), "the new title's page count was not read: " + p);
            List<String> ids = rankIds(c);
            c.check(ids.contains("A_R2") && ids.contains("C_R") && !ids.contains("A_T1") && !ids.contains("D_T"),
                    "owned accessories must not be suggested, missing ones must: " + ids);
            close(c);

            // The Rift's own bag (plain title, other accessories) is not the main bag: nothing of it is read.
            open(c, "Accessory Bag", List.of(owned("A_R2"), owned("C_R"), named(McItems.GRAY_DYE, "Crux Talisman")));
            c.check(rankIds(c).contains("A_R2") && rankIds(c).contains("C_R"), "the Rift bag was read as the main bag: " + rankIds(c));
            c.check(panel(c).equals(p), "the Rift bag changed the panel: " + panel(c));
            close(c);

            // The old "Accessory Bag (1/2)" order is gone with 0.27.3: not a bag title any more.
            c.check(c.onClient(mc -> Mod.staticCall(ADV, "page", "Accessory Bag (1/2)")) == null, "old title order still read as a bag");
            int[] pg = (int[]) c.onClient(mc -> Mod.staticCall(ADV, "page", "(5/6) Accessory Bag"));
            c.check(pg != null && pg[0] == 5 && pg[1] == 6, "page of '(5/6) Accessory Bag' was not 5 of 6");
        } finally {
            close(c);
            Mod.set(CFG, "setEnabled", was);
            c.onClient(mc -> Mod.staticCall(ADV, "clearForTest"));
            Mod.staticCall("pricetooltip.PriceData", "clearForTest");
        }
    }
}
