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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 1113-ui-accadvisor (accadvisor-1113, Accessory Advisor): a fixture accessory list, upgrade families and lowest-BIN
 * prices, and a fake "Accessory Bag (1/2)" holding Alpha Talisman (family A, Common) and Delta Talisman. Opening the bag
 * must build the panel (the feature acted: its last result is set and asks for the second page), then each mode must rank
 * exactly as worked out by hand below. The switch off builds nothing.
 * <p>
 * MP: Common 3, Uncommon 5, Rare 8, Epic 12. Family A owns 3 MP, so Alpha Ring (+2, 100k) is 50k/MP and Alpha Artifact
 * (+5, 300k) 60k/MP, within 1.25x: Best Overall jumps to the Artifact. Gamma Ring (+5, 50k) 10k/MP, Beta Talisman
 * (+12, 1.2M) 100k/MP, Ignored Relic is never listed, Unpriced Ring has no price.
 */
final class AccAdvisor1113Cases {

    private static final String CFG = "accadvisor.AccessoryAdvisorConfig";
    private static final String ADV = "accadvisor.AccessoryAdvisor";

    private AccAdvisor1113Cases() {
    }

    private static ItemStack acc(String id, String lore) {
        ItemStack s = new ItemStack(Items.PLAYER_HEAD);
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        s.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(lore))));
        return s;
    }

    /** The fixture catalog, families and prices described above. */
    static void fixture(UiCase c) throws Exception {
        Class<?> price = Mod.cls("pricetooltip.PriceData");
        Mod.staticCall("pricetooltip.PriceData", "markStartedForTest");
        Mod.staticCall("pricetooltip.PriceData", "clearForTest");
        R.setStatic(price, "lowestBin", Map.of("A_R2", 100_000.0, "A_A3", 300_000.0, "B_T", 1_200_000.0,
                "C_R", 50_000.0, "IGN", 1_000.0));
        c.onClient(mc -> {
            Mod.staticCall(ADV, "clearForTest");
            Mod.staticCall(ADV, "catalogForTest", Map.of(
                    "A_T1", "Alpha Talisman|COMMON", "A_R2", "Alpha Ring|UNCOMMON", "A_A3", "Alpha Artifact|RARE",
                    "B_T", "Beta Talisman|EPIC", "C_R", "Gamma Ring|UNCOMMON", "D_T", "Delta Talisman|COMMON",
                    "IGN", "Ignored Relic|LEGENDARY", "NOPRICE", "Unpriced Ring|RARE"));
            Mod.staticCall(ADV, "setFamilies", Map.of("A_T1", List.of("A_R2", "A_A3"), "A_R2", List.of("A_A3")),
                    Set.of("IGN"));
            return null;
        });
    }

    static void advisor(UiCase c) throws Exception {
        boolean was = (Boolean) Mod.call(Mod.cfg(CFG), "isEnabled");
        Object modeWas = Mod.call(Mod.cfg(CFG), "getMode");
        try {
            fixture(c);
            Mod.set(CFG, "setMode", Mod.enumValue(CFG + "$Mode", "BEST_OVERALL"));

            // Switch off: the bag opens and nothing is built.
            Mod.set(CFG, "setEnabled", false);
            open(c, "Accessory Bag (1/2)", true);
            c.ticks(6);
            c.check(c.onClient(mc -> Mod.staticCall(ADV, "lastResultForTest")) == null, "the panel was built with the switch off");
            close(c);

            Mod.set(CFG, "setEnabled", true);
            open(c, "Accessory Bag (1/2)", true);
            c.ticks(6);
            Object res = c.onClient(mc -> Mod.staticCall(ADV, "lastResultForTest"));
            c.check(res != null, "the panel never built on the Accessory Bag - the feature never acted");
            @SuppressWarnings("unchecked")
            List<String> lines = c.onClient(mc -> (List<String>) Mod.staticCall(ADV, "linesForTest"));
            c.note("panel: " + lines);
            c.check(String.join("\n", lines).contains("Open all bag pages (1/2 seen)"), "the panel did not ask for page 2");
            c.check(ids(res).equals(List.of("C_R", "A_A3", "B_T")), "panel Best Overall: " + ids(res));
            c.check(String.join("\n", lines).contains("Gamma Ring §d+5 MP §6"), "the panel did not list Gamma Ring +5 MP");
            close(c);

            // Page 2 (empty): the bag is complete.
            open(c, "Accessory Bag (2/2)", false);
            c.ticks(6);
            @SuppressWarnings("unchecked")
            List<String> lines2 = c.onClient(mc -> (List<String>) Mod.staticCall(ADV, "linesForTest"));
            c.check(!String.join("\n", lines2).contains("Open all bag pages"), "still asks for pages after both were seen: " + lines2);

            // A mode change rebuilds the open panel.
            Mod.set(CFG, "setMode", Mod.enumValue(CFG + "$Mode", "CHEAPEST"));
            c.ticks(4);
            Object cheapestPanel = c.onClient(mc -> Mod.staticCall(ADV, "lastResultForTest"));
            c.check(ids(cheapestPanel).equals(List.of("C_R", "A_R2", "B_T")), "panel after mode change: " + ids(cheapestPanel));
            close(c);

            check(c, "BEST_OVERALL", 15, List.of("C_R", "A_A3", "B_T"), 1);
            check(c, "UPGRADE_ONLY", 15, List.of("A_A3"), 0);
            check(c, "BUY_NEW", 15, List.of("C_R", "B_T"), 1);
            check(c, "CHEAPEST", 15, List.of("C_R", "A_R2", "B_T"), 1);
            check(c, "CHEAPEST_PER_MP", 15, List.of("C_R", "A_R2", "A_A3", "B_T"), 1);
            check(c, "MOST_MP", 15, List.of("B_T", "C_R", "A_A3"), 1);
            check(c, "CHEAPEST_PER_MP", 2, List.of("C_R", "A_R2"), 1);
        } finally {
            close(c);
            Mod.set(CFG, "setEnabled", was);
            Mod.set(CFG, "setMode", modeWas);
            c.onClient(mc -> Mod.staticCall(ADV, "clearForTest"));
            Mod.staticCall("pricetooltip.PriceData", "clearForTest");
        }
    }

    private static void check(UiCase c, String mode, int limit, List<String> want, int unpriced) {
        Object r = c.onClient(mc -> Mod.staticCall(ADV, "rankForTest", Mod.enumValue(CFG + "$Mode", mode), limit));
        List<String> got = ids(r);
        int up = (Integer) Mod.call(r, "unpriced");
        c.note(mode + " (limit " + limit + "): " + got + ", unpriced " + up);
        c.check(got.equals(want), mode + " ranked " + got + ", expected " + want);
        c.check(up == unpriced, mode + " counted " + up + " unpriced, expected " + unpriced);
    }

    private static List<String> ids(Object result) {
        List<String> out = new ArrayList<>();
        if (result != null) {
            for (Object row : (List<?>) Mod.call(result, "rows")) {
                out.add((String) Mod.call(row, "id"));
            }
        }
        return out;
    }

    static void open(UiCase c, String title, boolean filled) {
        open(c, title, filled ? 1 : 0);
    }

    /** {@code fill}: 0 empty, 1 two accessories, 2 every slot holds one (a click that leaks picks one up). */
    static void open(UiCase c, String title, int fill) {
        c.onClient(mc -> {
            SimpleContainer box = new SimpleContainer(27);
            if (fill == 1) {
                box.setItem(0, acc("A_T1", "COMMON ACCESSORY"));
                box.setItem(1, acc("D_T", "COMMON ACCESSORY"));
            }
            for (int i = 0; fill == 2 && i < 27; i++) {
                box.setItem(i, acc(i == 0 ? "A_T1" : "D_T", "COMMON ACCESSORY"));
            }
            ChestMenu menu = ChestMenu.threeRows(0, mc.player.getInventory(), box);
            McCompat.setScreen(mc, new ContainerScreen(menu, mc.player.getInventory(), Component.literal(title)));
            return null;
        });
    }

    static void close(UiCase c) {
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            return null;
        });
        c.ticks(2);
    }
}
