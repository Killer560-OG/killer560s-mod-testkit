package dev.testkit.gametest.menu;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What the Bazaar Flipper's autopilot decides from (mod f0641a08, 2026-10-19; the only mode a cheat jar runs since
 * "remove auto pilot as it should always be in autopilot mode"). The Insta Buy to /trades cycle the 761-774 cases were
 * written for is no longer reachable: {@code BazaarFlipConfig.isAutopilot()} is {@code CHEAT_FEATURES_ENABLED}, and the
 * autopilot never reads {@code BazaarFlipScanner} (whose test candidate the old cases fed). It decides from
 * <ul>
 *   <li>the Bazaar Advisor's /bzflip answers, which must have been given this boot or start refuses and opens the
 *       setup screen ({@code BazaarAdvisorConfig.answeredThisBoot}): here NPC, total profit, Insta Buy, Insta Sell -
 *       the answers that make the autopilot do what the old cycle did (Insta Buy, then sell to the NPC via /trades);</li>
 *   <li>{@code BazaarApi.peekProducts()}, fed with {@code applyResponseForTest} through the same parser a live fetch
 *       uses. The sell offers ({@code buy_summary}) below the NPC price are how many units it buys
 *       ({@code FlipAutopilot.profitableDepth}), so a case sizes a purchase with the book;</li>
 *   <li>the NPC price from the item repository ({@code SkyblockItemRepository.findById(..).npcSellPrice()}), seeded here
 *       beside whatever the repository already holds.</li>
 * </ul>
 * Before its first trade a session reads Manage Orders: {@code /bz} (any page with Manage Orders), then "Your Bazaar
 * Orders". The Hx fake answers every {@code /bz} with the one registered menu, so {@link #withManageOrders} gives that
 * page's Manage Orders (slot 50, the search page's bar in killer560's 2026-10-08 dump) an empty orders page.
 */
final class FlipMarket {

    private FlipMarket() {
    }

    /** One product: its id and display name (as the bundled Bazaar catalog names it), item material, NPC price, and
     *  the two sides of its book as {price, amount} levels. */
    record Prod(String id, String name, String material, double npc, double[][] sellOffers, double[][] buyOrders) {
    }

    static final Prod MAGMA = new Prod("LUMP_OF_MAGMA", "Lump of Magma", "MAGMA_CREAM", 50.0,
            new double[][]{{35.0, 128}, {60.0, 5_000}}, new double[][]{{30.0, 1_000}});

    static Prod magma(double npc) {
        return new Prod(MAGMA.id(), MAGMA.name(), MAGMA.material(), npc, MAGMA.sellOffers(), MAGMA.buyOrders());
    }

    /** {@code units} units at {@code each} below the NPC's {@code npc}, then a level above it. */
    static Prod product(String id, String name, String material, double npc, double each, int units) {
        return new Prod(id, name, material, npc, new double[][]{{each, units}, {npc * 1.1 + 1, 5_000}},
                new double[][]{{each * 0.8, 1_000}});
    }

    /**
     * The advisor answered (NPC, total profit, Insta Buy, Insta Sell) this boot, no reserve and no order cap, NPC sales
     * today 0, and {@code products} as the whole Bazaar with their NPC prices. Everything is put back on close.
     */
    static AutoCloseable autopilot(Session c, MenuKit.Cfg cfg, Prod... products) {
        String adv = "bazaaradvisor.BazaarAdvisorConfig";
        cfg.set(adv, "Mode", Mod.enumValue(adv + "$Mode", "NPC"))
                .set(adv, "Goal", Mod.enumValue(adv + "$Goal", "TOTAL_PROFIT"))
                .set(adv, "BuyMethod", Mod.enumValue(adv + "$BuyMethod", "INSTA_BUY"))
                .set(adv, "SellMethod", Mod.enumValue(adv + "$SellMethod", "INSTA_SELL"))
                .set("bazaarflip.BazaarFlipConfig", "AutopilotReservePercent", 0)
                .set("bazaarflip.BazaarFlipConfig", "AutopilotMaxOrdersOn", false)
                .set("bazaarflip.BazaarFlipConfig", "SellSpeed", 10);
        LogTap.install();
        String repo = "itembrowser.SkyblockItemRepository";
        Object[] was = c.onClient(mc -> {
            Object answered = Mod.field(adv, "answeredThisBoot");
            Mod.staticCall(adv, "markAnsweredThisBoot");
            Object oldProducts = Mod.field("auction.BazaarApi", "products");
            int n = (Integer) Mod.staticCall("auction.BazaarApi", "applyResponseForTest", bazaarJson(products));
            if (n != products.length) {
                throw new AssertionError("BazaarApi took " + n + " of " + products.length + " products");
            }
            Object oldItems = Mod.field(repo, "items");
            Mod.setField(repo, "triedCache", true);
            Mod.setField(repo, "lastFetchAtMs", System.currentTimeMillis());
            List<Object> items = new ArrayList<>((List<?>) oldItems);
            for (Prod p : products) {
                items.add(itemEntry(p.id(), p.name(), p.material(), p.npc()));
            }
            Mod.staticCall(repo, "setItems", items);
            if (Mod.has("bazaarflip.BazaarFlipNpcLimit")) {
                Mod.staticCall("bazaarflip.BazaarFlipNpcLimit", "setSoldTodayForTest", 0.0);
            }
            return new Object[]{answered, oldProducts, oldItems};
        });
        return () -> c.ctx().runOnClient(mc -> {
            Mod.setField(adv, "answeredThisBoot", was[0]);
            Mod.setField("auction.BazaarApi", "products", was[1]);
            Mod.staticCall(repo, "setItems", was[2]);
            if (Mod.has("bazaarflip.BazaarFlipNpcLimit")) {
                Mod.staticCall("bazaarflip.BazaarFlipNpcLimit", "setSoldTodayForTest", 0.0);
            }
        });
    }

    /** A {@code /v2/skyblock/bazaar} body for these products (buy_summary = the sell offers an Insta Buy takes). */
    static String bazaarJson(Prod... products) {
        return bazaarJson(System.currentTimeMillis(), products);
    }

    /**
     * The Bazaar replaced by {@code products} as a snapshot Hypixel built at {@code lastUpdatedMs} (mod bazaar-fix-1021:
     * the autopilot judges a snapshot's age by this, not by when it arrived). A refresh in the harness always fails
     * (offline), so these are the products the autopilot keeps.
     */
    static void reapply(Session c, long lastUpdatedMs, Prod... products) {
        c.ctx().runOnClient(mc -> {
            int n = (Integer) Mod.staticCall("auction.BazaarApi", "applyResponseForTest", bazaarJson(lastUpdatedMs, products));
            if (n != products.length) {
                throw new AssertionError("BazaarApi took " + n + " of " + products.length + " products");
            }
        });
    }

    static String bazaarJson(long lastUpdatedMs, Prod... products) {
        JsonObject all = new JsonObject();
        for (Prod p : products) {
            JsonObject qs = new JsonObject();
            qs.addProperty("productId", p.id());
            qs.addProperty("buyPrice", p.sellOffers()[0][0]);
            qs.addProperty("sellPrice", p.buyOrders()[0][0]);
            qs.addProperty("buyVolume", 100_000);
            qs.addProperty("sellVolume", 100_000);
            qs.addProperty("buyMovingWeek", 700_000);
            qs.addProperty("sellMovingWeek", 700_000);
            qs.addProperty("buyOrders", 5);
            qs.addProperty("sellOrders", 5);
            JsonObject o = new JsonObject();
            o.addProperty("product_id", p.id());
            o.add("quick_status", qs);
            o.add("buy_summary", levels(p.sellOffers()));
            o.add("sell_summary", levels(p.buyOrders()));
            all.add(p.id(), o);
        }
        JsonObject root = new JsonObject();
        root.addProperty("success", true);
        root.addProperty("lastUpdated", lastUpdatedMs);
        root.add("products", all);
        return root.toString();
    }

    private static JsonArray levels(double[][] levels) {
        JsonArray a = new JsonArray();
        for (double[] l : levels) {
            JsonObject o = new JsonObject();
            o.addProperty("pricePerUnit", l[0]);
            o.addProperty("amount", (long) l[1]);
            o.addProperty("orders", 3);
            a.add(o);
        }
        return a;
    }

    /** A SkyblockItemEntry by its record components (name, material, tier, NPC price; the rest empty). */
    static Object itemEntry(String id, String name, String material, double npc) {
        try {
            Class<?> cls = Mod.cls("itembrowser.SkyblockItemEntry");
            RecordComponent[] rc = cls.getRecordComponents();
            Class<?>[] types = new Class<?>[rc.length];
            Object[] args = new Object[rc.length];
            for (int i = 0; i < rc.length; i++) {
                types[i] = rc[i].getType();
                args[i] = switch (rc[i].getName()) {
                    case "id" -> id;
                    case "name" -> name;
                    case "material" -> material;
                    case "tier" -> "UNCOMMON";
                    case "npcSellPrice" -> npc;
                    default -> types[i] == int.class ? (Object) 0 : List.class.isAssignableFrom(types[i]) ? List.of() : null;
                };
            }
            Constructor<?> k = cls.getDeclaredConstructor(types);
            k.setAccessible(true);
            return k.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** "Your Bazaar Orders" with no order on it (the classifier knows it by its title; Go Back in the bar). */
    static JsonObject ordersPage() {
        return FlipCroesusCases.menu("Your Bazaar Orders", 4, Map.of(
                31, FlipCroesusCases.item("minecraft:arrow", "§aGo Back", "§7To Bazaar")), Map.of());
    }

    /** {@code page} (a /bz page with Manage Orders at slot 50) with Manage Orders opening {@link #ordersPage}. */
    static JsonObject withManageOrders(JsonObject page) {
        page.getAsJsonObject("on").add("50", FlipCroesusCases.open(ordersPage()));
        return page;
    }

    // ---- what the session did ---------------------------------------------------------------------------------------

    static List<String> log(long mark) {
        return LogTap.since(mark);
    }

    static boolean logged(long mark, String part) {
        return LogTap.since(mark).stream().anyMatch(l -> l.contains(part));
    }

    /** The autopilot found nothing to do and said so ("Waiting: 0 order(s) open, nothing else worth doing"). */
    static boolean waiting(long mark) {
        return LogTap.since(mark).stream().anyMatch(l -> l.contains("Waiting: ") && l.contains("nothing else worth doing"));
    }

    /** The stop reason of a session that ended on nothing profitable (mod bazaar-fix-1021; it used to wait forever). */
    static final String NOTHING_PROFITABLE = "nothing on the Bazaar is profitable";
    /** The stop reason of a session that ended on the NPC sell limit (mod bazaar-fix-1021). */
    static final String NPC_LIMIT = "the daily NPC sell limit is reached";

    /** Server-recorded commands that are exactly {@code cmd}. */
    static long sent(Session c, String cmd) {
        return c.commands().stream().filter(cmd::equals).count();
    }

    /** The Manage Orders read before the first trade: /bz, Manage Orders (50) on the page it opened. */
    static boolean readOrders(Session c, String bzPageTitle) {
        return sent(c, "bz") >= 1 && FlipCroesusCases.clicksOn(c, bzPageTitle).contains(50);
    }
}
