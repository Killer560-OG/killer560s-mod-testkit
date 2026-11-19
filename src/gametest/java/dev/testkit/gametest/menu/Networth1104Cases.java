package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * networth-1104 (killer560 2026-11-04: "the networth estimate can be off for a lot of stuff, it should use the est item
 * value"; "let networth have a non cosmetic networth option"; "the est item value doesnt show in the storage").
 * <ol>
 *   <li>1104-networth-itemvalue: the profile viewer's per-stack networth is the Price Tooltip calculator's NETWORTH total
 *       times the count (a built Hyperion is 178,637,500 not its 5,900,000 base price), falls back to the old base price
 *       when the calculator has no base, and the fallback still prices an item the table does not know.</li>
 *   <li>1104-networth-cosmetic: a skinned pet and dyed armor count in the normal total and not in the non-cosmetic one, in
 *       the whole {@code Networth.calculate} result and per category; the setting survives a save and load.</li>
 *   <li>1104-menu-storage-pricetip: hovering an item in the Storage Overlay's inventory panel draws the Est. Item Value
 *       line exactly once.</li>
 * </ol>
 */
final class Networth1104Cases {

    private Networth1104Cases() {
    }

    static final String NW = "profileviewer.data.Networth";
    static final String PVCFG = "profileviewer.ProfileViewerConfig";

    static final String DYED_ARMOR = "minecraft:leather_chestplate[custom_data={id:\"FAIRY_CHESTPLATE\",dye_item:\"DYE_AQUAMARINE\"}]";
    static final String SKINNED_PET = "minecraft:player_head[custom_data={id:\"PET\","
            + "petInfo:'{\"type\":\"ENDERMAN\",\"tier\":\"LEGENDARY\",\"exp\":0.0,\"skin\":\"ENDERMAN_X\"}'}]";
    static final String UNKNOWN = "minecraft:stick[custom_data={id:\"FALLBACK_X\"}]";

    static void register(Session s) {
        MenuSuite.test(s, "1104-networth-itemvalue", Networth1104Cases::itemValue);
        MenuSuite.test(s, "1104-networth-cosmetic", Networth1104Cases::cosmetic);
        MenuSuite.test(s, "1104-menu-storage-pricetip", Networth1104Cases::storageTip);
        MenuSuite.test(s, "1118-networth-pricing-soulbound", Networth1104Cases::pricingSoulbound);
    }

    private static double total(Object itemValue) {
        return ((Number) Mod.call(itemValue, "total")).doubleValue();
    }

    private static double cosmetic(Object itemValue) {
        return ((Number) Mod.call(itemValue, "cosmetic")).doubleValue();
    }

    private static Object value(Session c, ItemStack stack, Map<String, Double> bins) {
        return c.onClient(mc -> Mod.staticCall(NW, "itemValue", stack, bins));
    }

    // ---- 1104-networth-itemvalue --------------------------------------------------------------------------------

    static void itemValue(Session c) throws Exception {
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c); PriceTooltipCases.Fake fake = PriceTooltipCases.loadPrices(c, cfg)) {
            // Networth follows the Price Tooltip's Value Mode since recombprice-1104; these numbers are its NETWORTH weights.
            cfg.set(PriceTooltipCases.CFG, "ValueMode", Mod.enumValue(PriceTooltipCases.CFG + "$ValueMode", "NETWORTH"));
            Map<String, Double> bins = Map.of("HYPERION", 5_900_000.0, "FALLBACK_X", 1234.0);
            ItemStack built = PriceTooltipCases.give(c, 0, PriceTooltipCases.HYPERION_BUILT);

            // What the calculator itself says in NETWORTH mode, read through the same key the tooltip uses.
            double calc = c.onClient(mc -> {
                Object tag = Mod.staticCall("util.ItemNbt", "view", built);
                Object key = Mod.staticCall("pricetooltip.ItemKeys", "priceKey", tag, built.getHoverName().getString());
                Object mode = Mod.enumValue(PriceTooltipCases.CFG + "$ValueMode", "NETWORTH");
                Object res = Mod.staticCall("pricetooltip.ItemValueCalc", "compute", tag, key, mode);
                return ((Number) Mod.call(res, "total")).doubleValue();
            });
            double oldBase = c.onClient(mc -> ((Number) Mod.staticCall(NW, "price", "HYPERION", bins)).doubleValue());
            double now = total(value(c, built, bins));
            c.note("built Hyperion networth: before (base price only) " + oldBase + ", now " + now + ", calculator " + calc);
            c.check(Math.abs(calc - 178_637_500.0) < 1.0, "the calculator's NETWORTH total was " + calc + ", expected 178,637,500");
            c.check(Math.abs(now - calc) < 1.0, "networth of the built Hyperion " + now + " is not the calculator's " + calc);
            c.check(oldBase == 5_900_000.0 && now > oldBase * 20, "still base-priced? old " + oldBase + " now " + now);

            // A stack counts the unit value once per item.
            ItemStack dia = PriceTooltipCases.give(c, 1, PriceTooltipCases.DIAMONDS, 64);
            double dv = total(value(c, dia, bins));
            c.check(Math.abs(dv - 64 * 1700.5) < 0.5, "64 Enchanted Diamonds are worth " + dv + ", expected " + (64 * 1700.5));

            // No price in the table: the old lookup still prices it, and an item nobody prices stays 0.
            ItemStack unk = PriceTooltipCases.give(c, 2, UNKNOWN, 3);
            double uv = total(value(c, unk, bins));
            c.check(Math.abs(uv - 3 * 1234.0) < 0.5, "the fallback price of 3 unknown items was " + uv + ", expected 3702");
            double zero = total(value(c, unk, Map.of()));
            c.check(zero == 0, "an item with no price anywhere was worth " + zero);
            c.note("diamonds " + dv + ", fallback " + uv);
        } finally {
            PriceTooltipCases.shift(null);
            ItemCases.clear(c, 0, 1, 2);
        }
    }

    // ---- 1104-networth-cosmetic ---------------------------------------------------------------------------------

    static final String PROFILE = "{\"profiles\":[{\"profile_id\":\"p1\",\"cute_name\":\"Test\",\"selected\":true,"
            + "\"members\":{\"00000000000000000000000000000001\":{\"currencies\":{\"coin_purse\":123456},"
            + "\"pets_data\":{\"pets\":[{\"type\":\"ENDERMAN\",\"tier\":\"LEGENDARY\",\"exp\":0,\"skin\":\"ENDERMAN_X\"}]}}}}]}";

    static void cosmetic(Session c) throws Exception {
        boolean oldNon = c.onClient(mc -> (Boolean) Mod.get(PVCFG, "isNonCosmeticNetworth"));
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c); PriceTooltipCases.Fake fake = PriceTooltipCases.loadPrices(c, cfg)) {
            cfg.set(PriceTooltipCases.CFG, "ValueMode", Mod.enumValue(PriceTooltipCases.CFG + "$ValueMode", "NETWORTH"));
            Map<String, Double> bins = Map.of("FAIRY_CHESTPLATE", 200_000.0, "ENDERMAN;4", 1_000_000.0,
                    "PET_SKIN_ENDERMAN_X", 700_000.0);
            ItemStack armor = PriceTooltipCases.give(c, 0, DYED_ARMOR);
            ItemStack pet = PriceTooltipCases.give(c, 1, SKINNED_PET);

            // Per stack: the dye (Networth mode, 90% of 5,000,000) and the pet skin (3,000,000) are cosmetic.
            Object av = value(c, armor, bins);
            c.note("dyed armor: total " + total(av) + ", cosmetic " + cosmetic(av));
            c.check(Math.abs(total(av) - 4_700_000.0) < 1 && Math.abs(cosmetic(av) - 4_500_000.0) < 1,
                    "dyed armor total " + total(av) + " cosmetic " + cosmetic(av) + ", expected 4,700,000 / 4,500,000");
            Object pv = value(c, pet, bins);
            c.note("skinned pet stack: total " + total(pv) + ", cosmetic " + cosmetic(pv));
            c.check(Math.abs(cosmetic(pv) - 3_000_000.0) < 1 && total(pv) >= 3_000_000.0,
                    "skinned pet stack total " + total(pv) + " cosmetic " + cosmetic(pv) + ", expected cosmetic 3,000,000");

            // The whole calculation: purse + one skinned pet in the API pet list + dyed armor in the armor slots.
            Object[] res = c.onClient(mc -> {
                Object profiles = Mod.staticCall("profileviewer.data.SbProfile", "parseAll",
                        JsonParser.parseString(PROFILE).getAsJsonObject(), UUID.fromString("00000000-0000-0000-0000-000000000001"));
                Object p = ((List<?>) profiles).get(0);
                Class<?> invCls = Mod.cls("profileviewer.data.SbProfile$Inventories");
                try {
                    Object inv = invCls.getDeclaredConstructors()[0].newInstance(List.of(), List.of(armor.copy()), List.of(),
                            List.of(), List.of(), List.of(), 0, List.of(), List.of(), List.of(), List.of(), List.of());
                    return new Object[]{p, Mod.staticCall(NW, "calculate", p, inv, null, bins, null)};
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            Object result = res[1];
            long all = ((Number) Mod.call(result, "total", false)).longValue();
            long non = ((Number) Mod.call(result, "total", true)).longValue();
            c.note("networth with the skinned pet and dyed armor: all " + all + ", non-cosmetic " + non);
            c.check(all == 123_456L + 1_700_000L + 4_700_000L, "normal total " + all + ", expected 6,523,456");
            c.check(non == 123_456L + 1_000_000L + 200_000L, "non-cosmetic total " + non + ", expected 1,323,456");
            c.check(((Number) Mod.call(result, "totalNonCosmetic")).longValue() == non, "totalNonCosmetic disagrees with total(true)");
            c.check(categoryTotal(result, false, "Armor") == 4_700_000L && categoryTotal(result, true, "Armor") == 200_000L,
                    "Armor category " + categoryTotal(result, false, "Armor") + " / " + categoryTotal(result, true, "Armor"));
            c.check(categoryTotal(result, false, "Pets") == 1_700_000L && categoryTotal(result, true, "Pets") == 1_000_000L,
                    "Pets category " + categoryTotal(result, false, "Pets") + " / " + categoryTotal(result, true, "Pets"));

            // The setting survives a restart.
            c.onClient(mc -> {
                Mod.set(PVCFG, "setNonCosmeticNetworth", true);
                Mod.call(Mod.cfg(PVCFG), "save");
                Mod.set(PVCFG, "setNonCosmeticNetworth", false);
                Mod.staticCall(PVCFG, "load");
                return null;
            });
            boolean back = c.onClient(mc -> (Boolean) Mod.get(PVCFG, "isNonCosmeticNetworth"));
            c.check(back, "Non-Cosmetic Networth did not survive a save and load");
        } finally {
            c.onClient(mc -> {
                Mod.set(PVCFG, "setNonCosmeticNetworth", oldNon);
                Mod.call(Mod.cfg(PVCFG), "save");
                return null;
            });
            ItemCases.clear(c, 0, 1);
        }
    }

    // ---- 1118-networth-pricing-soulbound ------------------------------------------------------------------------

    static final String PRICING = "pricetooltip.PriceData$Pricing";
    static final String VIEW = "profileviewer.data.Networth$View";
    static final String LORE_SB = "minecraft:iron_sword[custom_data={id:\"HYPERION\",timestamp:1777032000000L},"
            + "lore=['\"* Soulbound *\"']]";
    static final String COOP_SB = "minecraft:iron_sword[custom_data={id:\"HYPERION\",timestamp:1777032000000L},"
            + "lore=['\"* Co-op Soulbound *\"']]";
    static final String DONATED = "minecraft:iron_sword[custom_data={id:\"HYPERION\",timestamp:1777032000000L,donated_museum:1b}]";
    static final String PURSE_ONLY = "{\"profiles\":[{\"profile_id\":\"p1\",\"cute_name\":\"Test\",\"selected\":true,"
            + "\"members\":{\"00000000000000000000000000000001\":{\"currencies\":{\"coin_purse\":123456}}}}]}";

    private static Object calc(Session c, List<ItemStack> armor, List<ItemStack> museum, String pricing) {
        return c.onClient(mc -> {
            Object profiles = Mod.staticCall("profileviewer.data.SbProfile", "parseAll",
                    JsonParser.parseString(PURSE_ONLY).getAsJsonObject(), UUID.fromString("00000000-0000-0000-0000-000000000001"));
            Object p = ((List<?>) profiles).get(0);
            try {
                Object inv = Mod.cls("profileviewer.data.SbProfile$Inventories").getDeclaredConstructors()[0].newInstance(
                        List.of(), armor, List.of(), List.of(), List.of(), List.of(), 0, List.of(), List.of(), List.of(), List.of(), List.of());
                return Mod.staticCall(NW, "calculate", p, inv, null, Map.of(), museum, Mod.enumValue(PRICING, pricing));
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
    }

    private static long total(Object result, String view) {
        return ((Number) Mod.call(result, "total", Mod.enumValue(VIEW, view))).longValue();
    }

    private static long cat(Object result, String view, String name) {
        for (Object cat : (List<?>) Mod.call(result, "categories", Mod.enumValue(VIEW, view))) {
            if (name.equals(Mod.call(cat, "name"))) return ((Number) Mod.call(cat, "total")).longValue();
        }
        return -1;
    }

    /** Each pricing mode prices a fixed set of stacks as expected, the Non-Soulbound view drops exactly the soulbound ones
     *  (lore Soulbound, lore Co-op Soulbound, donated_museum) and the Museum category, and the choices survive a reload. */
    static void pricingSoulbound(Session c) throws Exception {
        Object oldPricing = c.onClient(mc -> Mod.call(Mod.cfg(PVCFG), "getNetworthPricing"));
        Object oldView = c.onClient(mc -> Mod.call(Mod.cfg(PVCFG), "getNetworthView"));
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c); PriceTooltipCases.Fake fake = PriceTooltipCases.loadPrices(c, cfg)) {
            ItemStack plain = PriceTooltipCases.give(c, 0, PriceTooltipCases.HYPERION);
            ItemStack lore = PriceTooltipCases.give(c, 1, LORE_SB);
            ItemStack coop = PriceTooltipCases.give(c, 2, COOP_SB);
            ItemStack donated = PriceTooltipCases.give(c, 3, DONATED);
            ItemStack dia = PriceTooltipCases.give(c, 4, PriceTooltipCases.DIAMONDS, 64);
            List<ItemStack> armor = List.of(plain.copy(), lore.copy(), coop.copy(), donated.copy(), dia.copy());
            List<ItemStack> museum = List.of(plain.copy());
            long purse = 123_456L;
            // Hyperion: lowest BIN 5,900,000, 3 day average 4,790,000, NPC 56,000, no Bazaar sell. 64 Enchanted Diamonds:
            // Bazaar buy 1,700.5, sell 1,600.5, NPC 1,280, no BIN or average.
            long[][] want = {
                    {purse + 4 * 5_900_000L + 108_832L + 5_900_000L},
                    {purse + 4 * 5_900_000L + 102_432L + 5_900_000L},
                    {purse + 4 * 4_790_000L + 108_832L + 4_790_000L},
                    {purse + 4 * 56_000L + 81_920L + 56_000L}};
            String[] modes = {"ITEM_VALUE", "INSTANT_SELL", "AVERAGE_BIN", "NPC_SELL"};
            long[] seen = new long[4];
            for (int i = 0; i < 4; i++) {
                Object r = calc(c, armor, museum, modes[i]);
                seen[i] = total(r, "ALL");
                c.note(modes[i] + ": all " + seen[i] + ", non-soulbound " + total(r, "NON_SOULBOUND"));
                c.check(seen[i] == want[i][0], modes[i] + " total " + seen[i] + ", expected " + want[i][0]);
            }
            c.check(seen[0] != seen[1] && seen[1] != seen[2] && seen[2] != seen[3], "the pricing modes gave the same totals: " + java.util.Arrays.toString(seen));

            // Non-soulbound: the plain Hyperion, the diamonds and the coins stay; three soulbound swords and the Museum go.
            Object r = calc(c, armor, museum, "ITEM_VALUE");
            long keep = purse + 5_900_000L + 108_832L;
            c.check(total(r, "NON_SOULBOUND") == keep, "non-soulbound total " + total(r, "NON_SOULBOUND") + ", expected " + keep);
            c.check(cat(r, "NON_SOULBOUND", "Armor") == 5_900_000L + 108_832L, "non-soulbound Armor " + cat(r, "NON_SOULBOUND", "Armor"));
            c.check(cat(r, "NON_SOULBOUND", "Museum") == 0 && cat(r, "ALL", "Museum") == 5_900_000L,
                    "Museum " + cat(r, "ALL", "Museum") + " / " + cat(r, "NON_SOULBOUND", "Museum") + ", expected 5,900,000 / 0");
            c.check(total(r, "NON_COSMETIC") == total(r, "ALL"), "no cosmetics here, yet non-cosmetic differs from all value");

            // Both choices survive a save and load.
            c.onClient(mc -> {
                Object pv = Mod.cfg(PVCFG);
                Mod.call(pv, "setNetworthPricing", Mod.enumValue(PRICING, "NPC_SELL"));
                Mod.call(pv, "setNetworthView", Mod.enumValue(VIEW, "NON_SOULBOUND"));
                Mod.call(pv, "save");
                Mod.call(pv, "setNetworthPricing", Mod.enumValue(PRICING, "ITEM_VALUE"));
                Mod.call(pv, "setNetworthView", Mod.enumValue(VIEW, "ALL"));
                Mod.staticCall(PVCFG, "load");
                return null;
            });
            String back = c.onClient(mc -> Mod.call(Mod.cfg(PVCFG), "getNetworthPricing") + "/" + Mod.call(Mod.cfg(PVCFG), "getNetworthView"));
            c.check(back.equals("NPC_SELL/NON_SOULBOUND"), "pricing and view after a reload: " + back);
        } finally {
            c.onClient(mc -> {
                Object pv = Mod.cfg(PVCFG);
                Mod.call(pv, "setNetworthPricing", oldPricing);
                Mod.call(pv, "setNetworthView", oldView);
                Mod.call(pv, "save");
                return null;
            });
            PriceTooltipCases.shift(null);
            ItemCases.clear(c, 0, 1, 2, 3, 4);
        }
    }

    static long categoryTotal(Object result, boolean nonCosmetic, String name) {
        for (Object cat : (List<?>) Mod.call(result, "categories", nonCosmetic)) {
            if (name.equals(Mod.call(cat, "name"))) {
                return ((Number) Mod.call(cat, "total")).longValue();
            }
        }
        return -1;
    }

    // ---- 1104-menu-storage-pricetip -----------------------------------------------------------------------------

    static final String SFEAT = "storageoverlay.StorageOverlayFeature";
    static final String SCFG = "storageoverlay.StorageOverlayConfig";

    static void storageTip(Session c) throws Exception {
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c); PriceTooltipCases.Fake fake = PriceTooltipCases.loadPrices(c, cfg)) {
            cfg.set(PriceTooltipCases.CFG, "NpcPrice", false);
            cfg.set(PriceTooltipCases.CFG, "Obtained", false);
            cfg.set(PriceTooltipCases.CFG, "Museum", false);
            cfg.set(SCFG, "Enabled", true);
            PriceTooltipCases.shift(false);
            ItemStack hype = PriceTooltipCases.give(c, 0, PriceTooltipCases.HYPERION);
            // Main inventory index 9 is the first cell of the overlay's inventory panel.
            c.onClient(mc -> {
                mc.player.getInventory().setItem(9, hype.copy());
                PriceTooltipFeatureProbe.reset();
                SimpleContainer box = new SimpleContainer(54);
                box.setItem(0, new ItemStack(Items.BARRIER));
                ContainerScreen screen = new ContainerScreen(ChestMenu.sixRows(0, mc.player.getInventory(), box),
                        mc.player.getInventory(), Component.literal("Ender Chest (1/9)"));
                Mod.setField(SFEAT, "lastInventoryOrigin", null);
                McCompat.setScreen(mc, screen);
                return null;
            });
            c.waitUntil("overlay inventory panel drawn", mc -> Mod.field(SFEAT, "lastInventoryOrigin") != null, 60);
            c.ctx().waitTicks(3);
            double[] at = c.onClient(mc -> {
                int[] o = (int[]) Mod.field(SFEAT, "lastInventoryOrigin");
                float scale = ((Number) Mod.field(SFEAT, "lastInventoryScale")).floatValue();
                int lineHeight = mc.font.lineHeight;
                double gui = mc.getWindow().getGuiScale();
                double gx = o[0] + (3 + 8) * scale;
                double gy = o[1] + (lineHeight + 4 + 1 + 8) * scale;
                return new double[]{gx * gui, gy * gui};
            });
            c.ctx().getInput().setCursorPos(at[0], at[1]);
            c.ctx().waitTicks(6);
            List<Component> drawn = c.onClient(mc -> PriceTooltipFeatureProbe.last());
            List<String> lines = new ArrayList<>();
            for (Component l : drawn) {
                lines.add(l.getString());
            }
            long est = lines.stream().filter(s -> s.startsWith("§eEst. Item Value")).count();
            long bin = lines.stream().filter(s -> s.startsWith("§eLowest BIN Price")).count();
            c.note("storage overlay tooltip: " + lines);
            c.check(!lines.isEmpty(), "the Storage Overlay drew no item tooltip through the price helper (cursor " + at[0] + "," + at[1] + ")");
            c.check(est == 1 && bin == 1, "Est. Item Value shown " + est + "x and Lowest BIN " + bin + "x in the Storage Overlay tooltip: " + lines);
            // The vanilla builder alone, with the mixin hook, must not already add a second copy to what the helper drew.
            List<String> hook = c.onClient(mc -> {
                List<String> out = new ArrayList<>();
                for (Component l : Screen.getTooltipFromItem(mc, hype)) {
                    out.add(l.getString());
                }
                return out;
            });
            long hookEst = hook.stream().filter(s -> s.startsWith("§eEst. Item Value")).count();
            c.note("Screen.getTooltipFromItem alone carries Est. Item Value " + hookEst + "x");
            c.check(hookEst == 1, "Screen.getTooltipFromItem carries Est. Item Value " + hookEst + "x (the hook path)");
        } finally {
            PriceTooltipCases.shift(null);
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                mc.player.getInventory().setItem(9, ItemStack.EMPTY);
                return null;
            });
            ItemCases.clear(c, 0);
        }
    }

    /** Reads the mod's last drawn tooltip lines. */
    static final class PriceTooltipFeatureProbe {
        static void reset() {
            Mod.setField(PriceTooltipCases.FEATURE, "lastDrawnLines", List.of());
        }

        @SuppressWarnings("unchecked")
        static List<Component> last() {
            return (List<Component>) Mod.field(PriceTooltipCases.FEATURE, "lastDrawnLines");
        }
    }
}
