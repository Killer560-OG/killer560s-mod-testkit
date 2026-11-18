package dev.testkit.gametest.menu;

import com.google.gson.JsonParser;
import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;

import java.awt.image.BufferedImage;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * pv0273-1117 (SkyBlock 0.27.3 accessory rework; killer560's answers of 2026-11-17).
 * <ol>
 *   <li>1117-menu-pv0273-bag: the Profile Viewer's Accessory Bag view puts every accessory in its own slot: owned ones,
 *       a toggled-off one ({@code accessory_disabled}) marked DISABLED, one MISSING slot per family nobody owns (its lowest
 *       tier, from the Accessory Advisor's catalog), ignored ids left out, and Rift accessories only on the Rift tab.</li>
 *   <li>1117-menu-pv0273-tuning: Tuning Points In Use (the active tuning's points) and Bag Upgrades are read from
 *       {@code accessory_bag_storage}; a profile with no Rift bag in the API reads null (the tab says so).</li>
 *   <li>1117-menu-pv0273-enrich: an enriched accessory's value takes a recent comparable AH sale, else the enrichment's
 *       craft cost, else the base item, each labeled, never 0.</li>
 *   <li>1117-menu-pv0273-page: the real Accessories page draws the bag (screenshot kept for the owner).</li>
 * </ol>
 * The Compactor Preview hover is 963-965 (SlotTextCases).
 */
final class Pv0273Cases {

    private Pv0273Cases() {
    }

    static final String ADV = "accadvisor.AccessoryAdvisor";
    static final String VIEW = "profileviewer.data.AccessoryBagView";
    static final String API = "profileviewer.api.ProfileViewerApi";
    static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000001a7");

    /** The test catalog: one owned family (A/B), a toggled-off charm, a two-tier family nobody owns, a lone EPIC,
     *  an ignored id and a Rift accessory. */
    static final Map<String, String> CATALOG = Map.of(
            "PV_RING_A", "Pv Ring|COMMON", "PV_RING_B", "Pv Ring Plus|RARE",
            "PV_CHARM", "Pv Charm|UNCOMMON",
            "PV_TALI_1", "Pv Tali|COMMON", "PV_TALI_2", "Pv Tali Plus|RARE",
            "PV_ARTI", "Pv Artifact|EPIC",
            "PV_IGNORED", "Pv Ignored|COMMON",
            "PV_RIFT_ACC", "Pv Rift Ring|RARE");

    /** Items resource: the accessories with their tiers, the Rift one with origin RIFT (PriceData's rift flag). */
    static final String ITEMS = PriceTooltipCases.ITEMS.substring(0, PriceTooltipCases.ITEMS.length() - 2)
            + "," + acc("PV_RING_A", "Pv Ring", "COMMON", false) + "," + acc("PV_RING_B", "Pv Ring Plus", "RARE", false)
            + "," + acc("PV_CHARM", "Pv Charm", "UNCOMMON", false) + "," + acc("PV_TALI_1", "Pv Tali", "COMMON", false)
            + "," + acc("PV_TALI_2", "Pv Tali Plus", "RARE", false) + "," + acc("PV_ARTI", "Pv Artifact", "EPIC", false)
            + "," + acc("PV_IGNORED", "Pv Ignored", "COMMON", false) + "," + acc("PV_RIFT_ACC", "Pv Rift Ring", "RARE", true)
            + "," + acc("PV_ENRICH_A", "Pv Enriched", "LEGENDARY", false) + "," + acc("PV_ENRICH_B", "Pv Plain", "LEGENDARY", false)
            + "]}";

    static String acc(String id, String name, String tier, boolean rift) {
        return "{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"material\":\"SKULL_ITEM\",\"category\":\"ACCESSORY\",\"tier\":\""
                + tier + "\"" + (rift ? ",\"origin\":\"RIFT\"" : "") + "}";
    }

    static String stack(String id, String extra) {
        String v = CATALOG.getOrDefault(id, id + "|");
        return "minecraft:player_head[custom_name={text:\"" + v.substring(0, v.indexOf('|')) + "\",italic:false},"
                + "custom_data={id:\"" + id + "\"" + extra + "}]";
    }

    static void register(Session s) {
        MenuSuite.test(s, "1117-menu-pv0273-bag", Pv0273Cases::bag);
        MenuSuite.test(s, "1117-menu-pv0273-tuning", Pv0273Cases::tuning);
        MenuSuite.test(s, "1117-menu-pv0273-enrich", Pv0273Cases::enrich);
        MenuSuite.test(s, "1117-menu-pv0273-page", Pv0273Cases::page);
    }

    /** Prices with the test accessories, the test catalog and families; returns the owned stacks (main bag). */
    private static List<ItemStack> setUp(Session c, MenuKit.Cfg cfg, List<AutoCloseable> close) throws Exception {
        PriceTooltipCases.Fake.itemsOverride = ITEMS;
        close.add(PriceTooltipCases.loadPrices(c, cfg));
        c.onClient(mc -> {
            Mod.staticCall(ADV, "catalogForTest", CATALOG);
            Mod.staticCall(ADV, "setFamilies", Map.of("PV_RING_A", List.of("PV_RING_B"), "PV_TALI_1", List.of("PV_TALI_2")),
                    Set.of("PV_IGNORED"));
            return null;
        });
        ItemStack ring = PriceTooltipCases.give(c, 0, stack("PV_RING_A", "")).copy();
        ItemStack charm = PriceTooltipCases.give(c, 1, stack("PV_CHARM", ",accessory_disabled:1")).copy();
        return List.of(ring, charm);
    }

    private static void tearDown(Session c) {
        c.onClient(mc -> {
            Mod.staticCall(ADV, "clearForTest");
            Mod.staticCall(ADV, "setFamilies", Map.of(), Set.of());
            return null;
        });
        ItemCases.clear(c, 0, 1, 2, 3);
    }

    /** "ID:STATE" per slot, in order. */
    private static List<String> describe(Session c, List<ItemStack> owned, boolean rift, int sort) {
        return c.onClient(mc -> {
            List<String> out = new ArrayList<>();
            for (Object s : (List<?>) Mod.staticCall(VIEW, "slots", owned, rift, sort)) {
                out.add(Mod.call(s, "id") + ":" + Mod.call(s, "state"));
            }
            return out;
        });
    }

    // ---- 1117-menu-pv0273-bag -------------------------------------------------------------------------------------

    static void bag(Session c) throws Exception {
        List<AutoCloseable> close = new ArrayList<>();
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            List<ItemStack> owned = setUp(c, cfg, close);
            List<String> main = describe(c, owned, false, 0);
            c.note("main bag by rarity: " + main);
            c.check(main.equals(List.of("PV_ARTI:MISSING", "PV_CHARM:DISABLED", "PV_RING_A:OWNED", "PV_TALI_1:MISSING")),
                    "main bag slots " + main + ", expected the missing EPIC first, then the toggled-off UNCOMMON charm, then the "
                            + "COMMON group alphabetical with the owned ring among the missing lowest tier of the unowned Tali family "
                            + "(the 0.27.3 menu mixes missing into the rarity groups); nothing of the owned Ring family, the ignored id "
                            + "or the Rift accessory");
            List<String> byName = describe(c, owned, false, 1);
            c.check(byName.equals(List.of("PV_ARTI:MISSING", "PV_CHARM:DISABLED", "PV_RING_A:OWNED", "PV_TALI_1:MISSING")),
                    "main bag by name " + byName);
            List<String> missingFirst = describe(c, owned, false, 2);
            c.check(missingFirst.equals(List.of("PV_ARTI:MISSING", "PV_TALI_1:MISSING", "PV_CHARM:DISABLED", "PV_RING_A:OWNED")),
                    "main bag, Missing First " + missingFirst);
            int[] counts = c.onClient(mc -> (int[]) Mod.staticCall(VIEW, "counts", Mod.staticCall(VIEW, "slots", owned, false, 0)));
            c.check(counts[0] == 1 && counts[1] == 1 && counts[2] == 2,
                    "legend counts owned/off/missing " + counts[0] + "/" + counts[1] + "/" + counts[2] + ", expected 1/1/2");
            List<String> rift = describe(c, List.of(), true, 0);
            c.note("rift bag: " + rift);
            c.check(rift.equals(List.of("PV_RIFT_ACC:MISSING")), "Rift bag slots " + rift + ", expected only the Rift accessory");
            // Owning the Rift ring moves it out of missing on the Rift tab.
            ItemStack rr = PriceTooltipCases.give(c, 2, stack("PV_RIFT_ACC", "")).copy();
            List<String> rift2 = describe(c, List.of(rr), true, 0);
            c.check(rift2.equals(List.of("PV_RIFT_ACC:OWNED")), "Rift bag with the ring owned " + rift2);
            // A missing slot carries an icon (the catalog item), never an empty stack.
            boolean icons = c.onClient(mc -> {
                for (Object s : (List<?>) Mod.staticCall(VIEW, "slots", owned, false, 0)) {
                    if (((ItemStack) Mod.call(s, "stack")).isEmpty()) {
                        return false;
                    }
                }
                return true;
            });
            c.check(icons, "a slot of the bag view has no item to draw");
        } finally {
            closeAll(close);
            tearDown(c);
        }
    }

    // ---- 1117-menu-pv0273-tuning ----------------------------------------------------------------------------------

    static final String PROFILE = "{\"success\":true,\"profiles\":[{\"profile_id\":\"p1\",\"cute_name\":\"Kiwi\",\"selected\":true,"
            + "\"members\":{\"0000000000000000000000000000" + "01a7\":{\"currencies\":{\"coin_purse\":1000},"
            + "\"accessory_bag_storage\":{\"sort\":\"rarity\",\"highest_magical_power\":321,"
            + "\"tuning\":{\"slot_0\":{\"health\":10,\"strength\":7,\"purchase_ts\":1700000000},\"slot_1\":{\"health\":40}},"
            + "\"bag_upgrades_purchased\":4},\"inventory\":{\"inv_contents\":{\"type\":0,\"data\":\"\"}},\"rift\":{\"inventory\":{\"inv_contents\":{\"type\":0,\"data\":\"\"}}}}}}]}";

    static void tuning(Session c) throws Exception {
        Object[] got = c.onClient(mc -> {
            List<?> profiles = (List<?>) Mod.staticCall("profileviewer.data.SbProfile", "parseAll",
                    JsonParser.parseString(PROFILE).getAsJsonObject(), ID);
            return new Object[]{profiles.get(0), Mod.call(profiles.get(0), "riftAccessories")};
        });
        Object extras = ((CompletableFuture<?>) Mod.call(got[0], "extras")).get(10, TimeUnit.SECONDS);
        Object bag = Mod.field(extras, "bag");
        int inUse = (Integer) Mod.call(bag, "tuningInUse");
        int upgrades = (Integer) Mod.call(bag, "bagUpgrades");
        c.note("tuning in use " + inUse + ", bag upgrades " + upgrades + ", sort " + Mod.call(bag, "sort") + ", rift bag " + got[1]);
        c.check(inUse == 17, "Tuning Points In Use read " + inUse + ", expected 17 (slot_0 health 10 + strength 7; the saved preset in slot_1 not counted)");
        c.check(upgrades == 4, "Bag Upgrades read " + upgrades + ", expected 4");
        c.check(got[1] == null, "a Rift inventory with no accessory bag read as " + got[1] + ", expected null (the tab says the API does not send it)");
    }

    // ---- 1117-menu-pv0273-enrich ----------------------------------------------------------------------------------

    static final double BASE_A = 3_000_000, BASE_B = 2_000_000, ENRICH = 400_000, SALE = 5_000_000;

    static void enrich(Session c) throws Exception {
        List<AutoCloseable> close = new ArrayList<>();
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            // The Bazaar keeps 18 rows: the pet skin row becomes the critical damage enrichment.
            PriceTooltipCases.Fake.bazaarOverride = PriceTooltipCases.BAZAAR.replace("\"PET_SKIN_ENDERMAN_X\"",
                    "\"TALISMAN_ENRICHMENT_CRITICAL_DAMAGE\"");
            c.check(PriceTooltipCases.Fake.bazaarOverride.contains("TALISMAN_ENRICHMENT_CRITICAL_DAMAGE"), "the enrichment row was not added");
            PriceTooltipCases.Fake.lbinOverride = "{\"PV_ENRICH_A\":" + (long) BASE_A + ",\"PV_ENRICH_B\":" + (long) BASE_B + "}";
            PriceTooltipCases.Fake.itemsOverride = ITEMS;
            close.add(PriceTooltipCases.loadPrices(c, cfg));
            cfg.set(PriceTooltipCases.CFG, "ValueMode", Mod.enumValue(PriceTooltipCases.CFG + "$ValueMode", "NETWORTH"));
            double enrichPrice = c.onClient(mc -> ((Number) Mod.staticCall("pricetooltip.PriceData", "unitPrice",
                    "TALISMAN_ENRICHMENT_CRITICAL_DAMAGE")).doubleValue());
            c.check(enrichPrice > 0, "the fake Bazaar's enrichment has no price");

            // 3. base: an enrichment with no sale and no Bazaar product is valued as the plain accessory.
            ItemStack plain = PriceTooltipCases.give(c, 0, stack("PV_ENRICH_B", ",talisman_enrichment:\"intelligence\""));
            Map<String, Double> b = parts(c, plain);
            c.note("base fallback parts " + b);
            c.check(b.keySet().stream().noneMatch(k -> k.startsWith("Enrichment")), "an unpriced enrichment added a part: " + b);
            c.check(Math.abs(b.get("total") - BASE_B) < 1, "an unpriced enriched accessory is worth " + b.get("total") + ", expected its base " + BASE_B);

            // 2. craft cost: the enrichment's own Bazaar price, labeled as such.
            ItemStack crafted = PriceTooltipCases.give(c, 1, stack("PV_ENRICH_A", ",talisman_enrichment:\"critical_damage\""));
            Map<String, Double> k = parts(c, crafted);
            c.note("craft cost parts " + k);
            Double craft = k.get("Enrichment (craft cost)");
            c.check(craft != null && Math.abs(craft - enrichPrice) < 1, "craft cost part " + craft + ", expected " + enrichPrice);
            c.check(Math.abs(k.get("total") - (BASE_A + enrichPrice)) < 1, "craft cost total " + k.get("total") + ", expected " + (BASE_A + enrichPrice));

            // 1. a comparable recent sale (same id, enrichment and recomb) wins, older than 14 days does not count.
            long now = System.currentTimeMillis();
            c.onClient(mc -> {
                String key = (String) Mod.staticCall("auction.ah.AhMarket", "extrasKey", "PV_ENRICH_A", "critical_damage", 0);
                Mod.staticCall("auction.ah.AhMarket", "recordSaleForTest", key, now - 60_000L, (long) SALE);
                Mod.staticCall("auction.ah.AhMarket", "recordSaleForTest", key, now - 20L * 24 * 3600 * 1000, 90_000_000L);
                return null;
            });
            Map<String, Double> s = parts(c, crafted);
            c.note("recent sale parts " + s);
            c.check(s.containsKey("Enrichment (recent sale)") && !s.containsKey("Enrichment (craft cost)"),
                    "a recent comparable sale did not replace the craft cost: " + s);
            c.check(Math.abs(s.get("total") - SALE) < 1, "with a recent sale the accessory is worth " + s.get("total") + ", expected the sale " + SALE);
            // A sale of the same accessory recombobulated is not comparable to this one.
            ItemStack recomb = PriceTooltipCases.give(c, 2, stack("PV_ENRICH_A", ",talisman_enrichment:\"critical_damage\",rarity_upgrades:1"));
            Map<String, Double> r = parts(c, recomb);
            c.check(!r.containsKey("Enrichment (recent sale)"), "a recombobulated one took the plain one's sale: " + r);
            for (Map<String, Double> m : List.of(b, k, s, r)) {
                c.check(m.get("total") > 0, "an enriched accessory was valued at 0: " + m);
            }
        } finally {
            closeAll(close);
            ItemCases.clear(c, 0, 1, 2);
        }
    }

    /** The calculator's parts in NETWORTH mode, label -> value, plus "total". */
    private static Map<String, Double> parts(Session c, ItemStack st) {
        return c.onClient(mc -> {
            Object tag = Mod.staticCall("util.ItemNbt", "view", st);
            Object key = Mod.staticCall("pricetooltip.ItemKeys", "priceKey", tag, st.getHoverName().getString());
            Object mode = Mod.enumValue(PriceTooltipCases.CFG + "$ValueMode", "NETWORTH");
            Object res = Mod.staticCall("pricetooltip.ItemValueCalc", "compute", tag, key, mode);
            Map<String, Double> out = new java.util.LinkedHashMap<>();
            for (Object p : (List<?>) Mod.call(res, "parts")) {
                out.put(String.valueOf(Mod.call(p, "label")), ((Number) Mod.call(p, "value")).doubleValue());
            }
            out.put("total", ((Number) Mod.call(res, "total")).doubleValue());
            return out;
        });
    }

    // ---- 1117-menu-pv0273-page ------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static void page(Session c) throws Exception {
        List<AutoCloseable> close = new ArrayList<>();
        try (SocialCases.Env env = new SocialCases.Env(c); MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            List<ItemStack> owned = setUp(c, cfg, close);
            BazaarV3Cases.setWindow(c, 1600, 900, 2);
            c.ctx().runOnClient(mc -> Mod.call(Mod.cfg("profileviewer.ProfileViewerConfig"), "setRememberLastPage", false));
            Screen s = c.onClient(mc -> {
                try {
                    List<Object> parsed = (List<Object>) Mod.staticCall("profileviewer.data.SbProfile", "parseAll",
                            JsonParser.parseString(PROFILE).getAsJsonObject(), ID);
                    // The bag as the decoder would hand it over: one page of owned accessories.
                    Class<?> invCls = Mod.cls("profileviewer.data.SbProfile$Inventories");
                    Object inv = invCls.getDeclaredConstructors()[0].newInstance(List.of(), List.of(), List.of(), List.of(),
                            List.of(), List.of(), 0, List.of(owned), List.of(), List.of(), List.of(), List.of());
                    setField(parsed.get(0), "inventories", CompletableFuture.completedFuture(inv));
                    Class<?> resCls = Mod.cls(API + "$ProfilesResult");
                    Object result = resCls.getDeclaredConstructor(UUID.class, List.class, String.class, long.class)
                            .newInstance(ID, parsed, "PvAccTest", System.currentTimeMillis());
                    Constructor<?> cached = Mod.cls(API + "$Cached").getDeclaredConstructor(resCls, long.class);
                    cached.setAccessible(true);
                    ((Map<UUID, Object>) Mod.field(API, "CACHE")).put(ID, cached.newInstance(result, System.currentTimeMillis()));
                    Object target = Mod.cls("profileviewer.ProfileViewerFeature$Target")
                            .getDeclaredConstructor(String.class, UUID.class).newInstance("PvAccTest", ID);
                    Screen pv = (Screen) Mod.cls("profileviewer.screen.ProfileViewerScreen")
                            .getDeclaredConstructor(Screen.class, target.getClass()).newInstance(null, target);
                    McCompat.setScreen(mc, pv);
                    return pv;
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError("cannot open the PV: " + e, e);
                }
            });
            close.add(() -> c.ctx().runOnClient(mc -> {
                McCompat.setScreen(mc, null);
                ((Map<?, ?>) Mod.field(API, "CACHE")).remove(ID);
            }));
            c.waitUntil("the PV shows the offline profile", mc -> "READY".equals(String.valueOf(Mod.field(s, "state"))), 100);
            c.ctx().runOnClient(mc -> {
                Mod.call(s, "setPage", 2); // PAGE_INVENTORIES
                try {
                    setField(s, "invView", 4);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            c.ctx().waitTicks(6);
            List<?> flat = c.onClient(mc -> (List<?>) Mod.field(s, "accFlat"));
            c.check(flat.size() == 2, "the Accessories page flattened " + flat.size() + " owned accessories, expected 2 (did it draw?)");
            BufferedImage img = BazaarReskinCases.shot(c, "accessories");
            c.check(img.getWidth() > 0, "no screenshot");
            // The Rift Accessory Bag is a view of the Rift page now (owner, 11-17): its nav has a 4th entry, and with no
            // bag in the API the page says so. subView is clamped to the nav's length, so 3 only survives if it has four.
            c.ctx().runOnClient(mc -> {
                Mod.call(s, "setPage", 9); // PAGE_RIFT
                try {
                    setField(s, "subView", 3);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            c.ctx().waitTicks(30);
            Object sub = c.onClient(mc -> Mod.field(s, "subView"));
            c.check(Integer.valueOf(3).equals(sub), "the Rift page's view stayed " + sub + ", expected 3 (Accessory Bag nav entry)");
            BazaarReskinCases.shot(c, "accessories-rift");
        } finally {
            closeAll(close);
            tearDown(c);
        }
    }

    private static void setField(Object target, String name, Object value) throws ReflectiveOperationException {
        for (Class<?> k = target.getClass(); k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                // try the parent
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void closeAll(List<AutoCloseable> close) {
        for (int i = close.size() - 1; i >= 0; i--) {
            try {
                close.get(i).close();
            } catch (Exception ignored) {
                // best effort
            }
        }
    }
}
