package dev.testkit.gametest.menu;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The Bazaar Flipper since mod de5cb49c (2026-10-20): the cheat build always runs the AUTOPILOT
 * ({@code BazaarFlipConfig.isAutopilot()} is {@code CHEAT_FEATURES_ENABLED}), which decides from the Bazaar API snapshot
 * ({@code BazaarApi.peekProducts}), the Bazaar Advisor's four answers and NPC prices from the item repository - never from
 * the old scanner's test candidate. Offline those are all empty, so a case feeds them here, through the mod's own parsers:
 * <ul>
 *   <li>products through {@code BazaarApi.applyResponseForTest} (Hypixel's {@code /v2/skyblock/bazaar} shape);</li>
 *   <li>NPC prices through {@code SkyblockItemRepository.parseHypixelItems} (Hypixel's {@code /resources/skyblock/items}
 *       shape) merged into the loaded catalog;</li>
 *   <li>the advisor's answers, and "answered this boot" (mod 4be360bb: start refuses until the advisor was answered
 *       since the game started).</li>
 * </ul>
 * Everything is put back on close: the client and its statics outlive every case.
 */
final class BazaarFlipKit {

    private BazaarFlipKit() {
    }

    static final String REPO = "itembrowser.SkyblockItemRepository";
    static final String ADVISOR = "bazaaradvisor.BazaarAdvisorConfig";
    static final String FLIP = "bazaarflip.BazaarFlipConfig";
    static final String AUTOPILOT = "bazaarflip.BazaarFlipAutopilot";

    /** One price level of a book. */
    record Level(double price, long amount) {
    }

    /**
     * One Bazaar product as the API reports it. {@code offers} are the sell offers an Insta Buy eats (Hypixel's
     * {@code buy_summary}), {@code bids} the buy orders an Insta Sell fills (its {@code sell_summary}). {@code sellWeek}
     * is the 7-day instant-sell volume (what fills buy orders), {@code bidCount} the number of buy orders competing.
     */
    record Product(String id, String material, Double npc, List<Level> offers, List<Level> bids, long sellWeek,
                   long buyWeek, int bidCount, int offerCount) {
    }

    /**
     * An Insta Buy flip to the NPC: {@code units} offered at {@code each} below the NPC's {@code npc} (then a wall at
     * twice the NPC price, so exactly {@code units} are worth buying), buy orders far under the cost (Insta Sell never
     * covers it).
     */
    static Product insta(String id, String material, int units, double each, double npc) {
        return new Product(id, material, npc, List.of(new Level(each, units), new Level(npc * 2, 1_000_000)),
                List.of(new Level(Math.max(0.1, Math.floor(each / 4)), 10_000)), 700_000, 700_000, 10, 10);
    }

    /** The advisor's four answers. */
    record Answers(boolean npc, boolean instaBuy, boolean instaSell) {
        /** The old flipper's flip: Insta Buy, sell to the NPC (also the advisor's defaults). */
        static final Answers INSTA_TO_NPC = new Answers(true, true, true);
        /** Buy orders, sold to the NPC. */
        static final Answers ORDER_TO_NPC = new Answers(true, false, true);
    }

    /**
     * The autopilot set up on {@code cfg}: the answers, answered this boot, reserve 10%, at most {@code maxOrders} new
     * orders or Insta Buys (1 = one buy and its sale, then the session ends with "the session's order cap (1) is
     * reached"), and the products fed. Close it BEFORE {@code cfg}.
     */
    static AutoCloseable autopilot(Session c, MenuKit.Cfg cfg, Answers a, int maxOrders, Product... products) {
        cfg.set(ADVISOR, "Mode", Mod.enumValue(ADVISOR + "$Mode", a.npc() ? "NPC" : "BAZAAR"))
                .set(ADVISOR, "Goal", Mod.enumValue(ADVISOR + "$Goal", "TOTAL_PROFIT"))
                .set(ADVISOR, "BuyMethod", Mod.enumValue(ADVISOR + "$BuyMethod", a.instaBuy() ? "INSTA_BUY" : "BUY_ORDER"))
                .set(ADVISOR, "SellMethod", Mod.enumValue(ADVISOR + "$SellMethod", a.instaSell() ? "INSTA_SELL" : "SELL_OFFER"))
                .set(FLIP, "AutopilotReservePercent", 10)
                .set(FLIP, "AutopilotMaxOrdersOn", true)
                .set(FLIP, "AutopilotMaxOrders", maxOrders);
        boolean answeredWas = c.onClient(mc -> (Boolean) Mod.staticCall(ADVISOR, "answeredThisBoot"));
        Object[] was = c.onClient(mc -> {
            Mod.setField(ADVISOR, "answeredThisBoot", true);
            Object snapshot = Mod.field("auction.BazaarApi", "products");
            // The catalog loads from disk on first use; load it now so the merge below is not replaced by that load.
            Mod.staticCall(REPO, "findById", "TESTKIT_NONE");
            Object items = Mod.field(REPO, "items");
            Object byId = Mod.field(REPO, "byId");
            return new Object[]{snapshot, items, byId};
        });
        int fed = c.onClient(mc -> (Integer) Mod.staticCall("auction.BazaarApi", "applyResponseForTest", bazaarJson(products)));
        c.check(fed == products.length, "the mod's Bazaar parser kept " + fed + " of " + products.length + " products");
        c.ctx().runOnClient(mc -> {
            JsonArray items = new JsonArray();
            Set<String> ids = new HashSet<>();
            for (Product p : products) {
                if (p.npc() != null) {
                    JsonObject it = new JsonObject();
                    it.addProperty("id", p.id());
                    it.addProperty("name", p.id());
                    it.addProperty("material", p.material());
                    it.addProperty("npc_sell_price", p.npc());
                    items.add(it);
                    ids.add(p.id());
                }
            }
            List<Object> merged = new ArrayList<>();
            for (Object e : (List<?>) was[1]) {
                if (!ids.contains((String) Mod.call(e, "id"))) {
                    merged.add(e);
                }
            }
            merged.addAll((List<?>) Mod.staticCall(REPO, "parseHypixelItems", items));
            Mod.staticCall(REPO, "setItems", merged);
        });
        for (Product p : products) {
            if (p.npc() != null) {
                Object got = c.onClient(mc -> Mod.call(Mod.staticCall(REPO, "findById", p.id()), "npcSellPrice"));
                c.check(p.npc().equals(got), "NPC price of " + p.id() + " reads " + got);
            }
        }
        return () -> c.ctx().runOnClient(mc -> {
            Mod.setField("auction.BazaarApi", "products", was[0]);
            Mod.setField(REPO, "items", was[1]);
            Mod.setField(REPO, "byId", was[2]);
            Mod.setField(ADVISOR, "answeredThisBoot", answeredWas);
        });
    }

    static String bazaarJson(Product... products) {
        JsonObject all = new JsonObject();
        for (Product p : products) {
            JsonObject qs = new JsonObject();
            qs.addProperty("productId", p.id());
            qs.addProperty("buyPrice", p.offers().isEmpty() ? 0 : p.offers().get(0).price());
            qs.addProperty("sellPrice", p.bids().isEmpty() ? 0 : p.bids().get(0).price());
            qs.addProperty("buyVolume", p.offers().stream().mapToLong(Level::amount).sum());
            qs.addProperty("sellVolume", p.bids().stream().mapToLong(Level::amount).sum());
            qs.addProperty("buyMovingWeek", p.buyWeek());
            qs.addProperty("sellMovingWeek", p.sellWeek());
            qs.addProperty("buyOrders", p.offerCount());
            qs.addProperty("sellOrders", p.bidCount());
            JsonObject o = new JsonObject();
            o.addProperty("product_id", p.id());
            o.add("quick_status", qs);
            o.add("buy_summary", levels(p.offers()));
            o.add("sell_summary", levels(p.bids()));
            all.add(p.id(), o);
        }
        JsonObject root = new JsonObject();
        root.addProperty("success", true);
        root.addProperty("lastUpdated", System.currentTimeMillis());
        root.add("products", all);
        return root.toString();
    }

    private static JsonArray levels(List<Level> levels) {
        JsonArray a = new JsonArray();
        for (Level l : levels) {
            JsonObject o = new JsonObject();
            o.addProperty("pricePerUnit", l.price());
            o.addProperty("amount", l.amount());
            o.addProperty("orders", 1);
            a.add(o);
        }
        return a;
    }

    // ---- Hypixel's Manage Orders -----------------------------------------------------------------------------------

    /** "Your Bazaar Orders" with {@code orders} (slot -> order item; empty: none), Go Back at 31. */
    static JsonObject ordersPage(java.util.Map<Integer, JsonObject> orders, java.util.Map<Integer, JsonObject> on) {
        java.util.Map<Integer, JsonObject> slots = new java.util.LinkedHashMap<>(orders);
        slots.put(31, FlipCroesusCases.item("minecraft:arrow", "§aGo Back"));
        return FlipCroesusCases.menu("Your Bazaar Orders", 4, slots, on);
    }

    static JsonObject ordersPage() {
        return ordersPage(java.util.Map.of(), java.util.Map.of());
    }

    /** A search page with Manage Orders (slot 50) opening {@code orders}: the autopilot reads it before anything else. */
    static JsonObject withManageOrders(JsonObject searchPage, JsonObject orders) {
        JsonObject m = searchPage.deepCopy();
        m.getAsJsonObject("on").add("50", FlipCroesusCases.open(orders));
        return m;
    }

    /** An action that, from then on, makes /name open {@code menu} (HxMenuModule.setCommandMenu). */
    static JsonObject commandMenu(String name, JsonObject menu) {
        JsonObject cmd = new JsonObject();
        cmd.addProperty("name", name);
        cmd.add("menu", menu);
        return cmd;
    }

    // ---- reading the autopilot ------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static Set<String> avoided(Session c) {
        return c.onClient(mc -> new HashSet<>((Set<String>) Mod.field(AUTOPILOT, "avoid")));
    }

    static String lastWait(Session c) {
        return c.onClient(mc -> String.valueOf(Mod.field(AUTOPILOT, "lastWaitWhy")));
    }

    static int placed(Session c) {
        return c.onClient(mc -> ((Number) Mod.field(AUTOPILOT, "placed")).intValue());
    }

    @SuppressWarnings("unchecked")
    static long held(Session c, String id) {
        return c.onClient(mc -> ((java.util.Map<String, Long>) Mod.field(AUTOPILOT, "holdings")).getOrDefault(id, 0L));
    }
}
