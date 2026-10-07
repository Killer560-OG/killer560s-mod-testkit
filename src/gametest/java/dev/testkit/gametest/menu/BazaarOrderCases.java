package dev.testkit.gametest.menu;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 426-menu-bazaar-orders: the Bazaar browser's My Orders source. The server opens Hypixel's Manage Orders menu
 * ({@code menus.bazaar-orders}: two buy orders, a filled sell offer, an expired one, a co-op member's order and a
 * hostile item), and the mod's own tick reader ({@code auction.BazaarOrders}, through {@code BazaarFeature}'s client
 * tick, not a direct call) must read every order with its amount, fill, price, claim and expiry state, skip the
 * hostile item without throwing, and never click: the server must record zero container clicks. Then the same menu
 * under its co-op title with two orders must REPLACE the stored set (Manage Orders lists every order), and the stored
 * orders must survive a reload from the config file.
 */
final class BazaarOrderCases {

    private BazaarOrderCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "426-menu-bazaar-orders", BazaarOrderCases::orders);
    }

    static void orders(Session c) throws Exception {
        MenuKit.reset(c);
        boolean gateWas = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled"));
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set("auction.AuctionConfig", "BazaarEnabled", true);
            cfg.set("auction.AuctionConfig", "TrackBazaarOrders", true);
            c.ctx().runOnClient(mc -> {
                Mod.staticCall("util.SkyblockGate", "setEnabled", false);
                Mod.staticCall("auction.BazaarOrders", "clearForTest");
            });
            int clicksBefore = c.events("container.click").size();

            MenuKit.show(c, MenuKit.menu("menus.bazaar-orders"));
            MenuKit.awaitScreen(c, "Your Bazaar Orders", 100);
            c.waitUntil("the mod's tick reader to store 5 orders", mc -> orderCount() == 5, 100);
            List<String> orders = c.onClient(mc -> describe());
            c.note("read: " + String.join(" | ", orders));
            expect(c, orders, "BUY Enchanted Diamond id=ENCHANTED_DIAMOND amount=160 filled=64 full=false price=1275.3 "
                    + "claimItems=64 claimable=true expired=false owner=null");
            expect(c, orders, "BUY Wheat id=WHEAT amount=2000 filled=0 full=false price=2.1 claimItems=-1 "
                    + "claimable=false expired=false owner=null");
            expect(c, orders, "SELL Wither Essence id=ESSENCE_WITHER amount=1000 filled=1000 full=true price=4100.5 "
                    + "claimItems=-1 claimable=true expired=false owner=null");
            expect(c, orders, "SELL Rough Ruby Gemstone id=ROUGH_RUBY_GEM amount=640 filled=34 full=false price=99999.0 "
                    + "claimItems=-1 claimable=false expired=true owner=null");
            expect(c, orders, "BUY Enchanted Gold Ingot id=ENCHANTED_GOLD amount=1024 filled=819 full=false price=802.4 "
                    + "claimItems=-1 claimable=false expired=false owner=CoopFriend");
            c.check(orders.stream().noneMatch(o -> o.contains("Hostile")), "the hostile item was read as an order");
            String expires = c.onClient(mc -> {
                for (Object o : current()) {
                    if ("Wheat".equals(Mod.call(o, "productName"))) {
                        return String.valueOf(Mod.call(o, "expiresIn"));
                    }
                }
                return "none";
            });
            c.check("6d 23h".equals(expires), "Wheat expires in '" + expires + "'");

            // Co-op title: a fresh read replaces the whole set.
            MenuKit.reset(c);
            JsonObject coop = MenuKit.menu("menus.bazaar-orders");
            coop.addProperty("title", "Co-op Bazaar Orders");
            JsonObject slots = coop.getAsJsonObject("slots");
            for (String k : new ArrayList<>(slots.keySet())) {
                if (!k.equals("11") && !k.equals("14") && !k.equals("31")) {
                    slots.remove(k);
                }
            }
            MenuKit.show(c, coop);
            MenuKit.awaitScreen(c, "Co-op Bazaar Orders", 100);
            c.waitUntil("the co-op read to replace the set with 2 orders", mc -> orderCount() == 2, 100);
            List<String> after = c.onClient(mc -> describe());
            c.note("after the co-op read: " + String.join(" | ", after));
            c.check(after.stream().anyMatch(o -> o.startsWith("BUY Wheat")) && after.stream().anyMatch(o -> o.contains("owner=CoopFriend")),
                    "co-op read kept the wrong orders: " + after);

            int clicks = c.events("container.click").size() - clicksBefore;
            c.check(clicks == 0, "the order reader clicked " + clicks + " time(s) - it must be read only");
            c.note("container clicks while reading: " + clicks);

            // Persisted: forget memory, read the file back.
            c.ctx().runOnClient(mc -> {
                Mod.setField("auction.BazaarOrders", "loaded", false);
                ((java.util.Map<?, ?>) Mod.field("auction.BazaarOrders", "BY_PROFILE")).clear();
            });
            int reloaded = c.onClient(mc -> orderCount());
            c.check(reloaded == 2, "after a reload from the config file there are " + reloaded + " orders, not 2");
        } finally {
            MenuKit.reset(c);
            c.ctx().runOnClient(mc -> Mod.staticCall("util.SkyblockGate", "setEnabled", gateWas));
        }
    }

    private static List<?> current() {
        return (List<?>) Mod.staticCall("auction.BazaarOrders", "currentOrders");
    }

    private static int orderCount() {
        return current().size();
    }

    private static List<String> describe() {
        List<String> out = new ArrayList<>();
        for (Object o : current()) {
            out.add(Mod.call(o, "type") + " " + Mod.call(o, "productName") + " id=" + Mod.call(o, "productId")
                    + " amount=" + Mod.call(o, "amount") + " filled=" + Mod.call(o, "filled") + " full=" + Mod.call(o, "full")
                    + " price=" + Mod.call(o, "pricePerUnit") + " claimItems=" + Mod.call(o, "claimableItems")
                    + " claimable=" + Mod.call(o, "claimable") + " expired=" + Mod.call(o, "expired")
                    + " owner=" + Mod.call(o, "owner"));
        }
        return out;
    }

    private static void expect(Session c, List<String> orders, String want) {
        c.check(orders.contains(want), "missing or different: " + want);
    }
}
