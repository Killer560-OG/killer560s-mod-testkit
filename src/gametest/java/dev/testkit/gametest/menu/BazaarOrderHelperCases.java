package dev.testkit.gametest.menu;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 2810-menu-bz-order-helper: the Bazaar Order Helper ({@code bazaar.BazaarOrderHelper}, bzorders-1102) in Hypixel's
 * real Manage Orders chest (the reskin off, so the chest is what is drawn). The menu holds a filled buy order, a filled
 * sell offer, a buy order under the best buy order, a sell offer over the cheapest sell offer, a buy order AT the best
 * buy order and a sell offer AT the cheapest sell offer, against a synthetic {@code /v2/skyblock/bazaar} answer fed
 * through the mod's real parser. Asserted by pixels (each order slot's 1 px outline, read from a screenshot): exactly the
 * two filled slots get the Filled color, exactly the two beaten orders get the Outbid color, the two top orders and
 * every other slot get neither. Then: the tooltip names the better price and the price age; prices 30 minutes old leave
 * only the filled slots colored; Highlight Outbid off leaves only the filled ones; the feature off colors nothing. The
 * server must record zero container clicks.
 */
final class BazaarOrderHelperCases {

    private static final String CFG = "bazaar.BazaarOrderHelperConfig";
    /** Opaque outline colors the case sets (the fill keeps the alpha given). */
    private static final int OUTBID = 0xFF00FF;
    private static final int FILLED = 0x00FFFF;
    private static final int EXPIRED = 0xFFFF00;

    private BazaarOrderHelperCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "2810-menu-bz-order-helper", BazaarOrderHelperCases::helper);
        MenuSuite.test(s, "1108-menu-ah-highlight", BazaarOrderHelperCases::auctions);
    }

    private static JsonObject order(String item, String name, String amountLine, String filledLine, String price,
                                    String... tail) {
        JsonObject o = new JsonObject();
        o.addProperty("item", item);
        o.addProperty("name", name);
        JsonArray lore = new JsonArray();
        lore.add("");
        lore.add(amountLine);
        if (filledLine != null) {
            lore.add(filledLine);
        }
        lore.add("");
        lore.add("§7Price per unit: §6" + price + " coins");
        for (String t : tail) {
            lore.add(t);
        }
        o.add("lore", lore);
        return o;
    }

    static JsonObject menu() {
        JsonObject slots = new JsonObject();
        slots.add("10", order("minecraft:diamond", "§a§lBUY §aEnchanted Diamond", "§7Order amount: §a160§7x",
                "§7Filled: §6160§7/160 §a§l100%!", "1,275.3", "", "§eClick to claim!"));
        slots.add("11", order("minecraft:sugar", "§6§lSELL §dWither Essence", "§7Offer amount: §a1,000§7x",
                "§7Filled: §61k§7/1k §a§l100%!", "4,100.5", "", "§eClick to claim!"));
        slots.add("12", order("minecraft:wheat", "§a§lBUY §fWheat", "§7Order amount: §a2,000§7x", null, "2.1",
                "", "§7Expires in §e6d 23h"));
        slots.add("13", order("minecraft:gold_ingot", "§6§lSELL §aEnchanted Gold Ingot", "§7Offer amount: §a64§7x",
                null, "900.0"));
        slots.add("14", order("minecraft:paper", "§a§lBUY §fRough Ruby Gemstone", "§7Order amount: §a640§7x", null,
                "50.0"));
        slots.add("15", order("minecraft:diamond", "§6§lSELL §aEnchanted Diamond", "§7Offer amount: §a32§7x", null,
                "1,300.0"));
        // Owner 2026-11-03: an expired order is red (its own Expired Color), never the outbid one.
        slots.add("16", order("minecraft:wheat", "§a§lBUY §fWheat", "§7Order amount: §a500§7x", null, "1.5",
                "", "§cExpired!"));
        JsonObject back = new JsonObject();
        back.addProperty("item", "minecraft:arrow");
        back.addProperty("name", "§aGo Back");
        JsonArray bl = new JsonArray();
        bl.add("§7To Bazaar");
        back.add("lore", bl);
        slots.add("31", back);
        JsonObject spec = new JsonObject();
        spec.addProperty("title", "Your Bazaar Orders");
        spec.addProperty("rows", 4);
        spec.addProperty("fill", true);
        spec.add("slots", slots);
        return spec;
    }

    /** {id, best buy order (sell_summary top), cheapest sell offer (buy_summary top)}. */
    private static final Object[][] BOOK = {
            {"ENCHANTED_DIAMOND", 1270.0, 1300.0},
            {"ESSENCE_WITHER", 4000.0, 4200.0},
            {"WHEAT", 2.5, 2.9},          // our buy order at 2.1 is beaten
            {"ENCHANTED_GOLD", 700.0, 850.0}, // our sell offer at 900 is beaten
            {"ROUGH_RUBY_GEM", 50.0, 60.0},   // our buy order at 50 is the top
    };

    static void feedPrices(Session c, long ageMs) {
        JsonObject products = new JsonObject();
        for (Object[] row : BOOK) {
            String id = (String) row[0];
            double bestBuy = (Double) row[1];
            double bestSell = (Double) row[2];
            JsonObject qs = new JsonObject();
            qs.addProperty("productId", id);
            qs.addProperty("sellPrice", bestBuy);
            qs.addProperty("buyPrice", bestSell);
            qs.addProperty("sellVolume", 1000);
            qs.addProperty("buyVolume", 1000);
            qs.addProperty("sellMovingWeek", 100000);
            qs.addProperty("buyMovingWeek", 100000);
            qs.addProperty("sellOrders", 10);
            qs.addProperty("buyOrders", 10);
            JsonArray sellSummary = new JsonArray(); // the buy orders, best = highest
            JsonArray buySummary = new JsonArray();  // the sell offers, best = lowest
            for (int i = 0; i < 3; i++) {
                JsonObject b = new JsonObject();
                b.addProperty("amount", 64);
                b.addProperty("pricePerUnit", bestBuy - i);
                b.addProperty("orders", 1);
                sellSummary.add(b);
                JsonObject s = new JsonObject();
                s.addProperty("amount", 64);
                s.addProperty("pricePerUnit", bestSell + i);
                s.addProperty("orders", 1);
                buySummary.add(s);
            }
            JsonObject p = new JsonObject();
            p.addProperty("product_id", id);
            p.add("quick_status", qs);
            p.add("sell_summary", sellSummary);
            p.add("buy_summary", buySummary);
            products.add(id, p);
        }
        JsonObject root = new JsonObject();
        root.addProperty("success", true);
        root.addProperty("lastUpdated", System.currentTimeMillis() - ageMs);
        root.add("products", products);
        String body = root.toString();
        int n = c.onClient(mc -> (Integer) Mod.staticCall("auction.BazaarApi", "applyResponseForTest", body));
        c.check(n == BOOK.length, "the mod's Bazaar parser kept " + n + " of " + BOOK.length + " products");
    }

    /** Slot index -> the outline color read at its four corners (or "mixed:..." when they disagree). */
    static Map<Integer, String> outlines(Session c, String label) {
        // The window-center cursor sat on slot 16's corner and its hover highlight read DEDEDE (2810 failed on base too).
        c.ctx().getInput().setCursorPos(2, 2);
        c.ctx().waitTicks(2);
        BufferedImage img = BazaarReskinCases.shot(c, label);
        double[] g = c.onClient(mc -> new double[]{mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight()});
        List<int[]> slots = c.onClient(mc -> {
            AbstractContainerScreen<?> s = (AbstractContainerScreen<?>) McCompat.screen(mc);
            int left = ((Number) Mod.field(s, "leftPos")).intValue();
            int top = ((Number) Mod.field(s, "topPos")).intValue();
            List<int[]> out = new ArrayList<>();
            int chest = s.getMenu().slots.size() - 36;
            for (int i = 0; i < chest; i++) {
                Slot sl = s.getMenu().slots.get(i);
                out.add(new int[]{i, left + sl.x, top + sl.y});
            }
            return out;
        });
        double rx = img.getWidth() / g[0];
        double ry = img.getHeight() / g[1];
        Map<Integer, String> out = new LinkedHashMap<>();
        for (int[] s : slots) {
            int[][] corners = {{s[1], s[2]}, {s[1] + 15, s[2]}, {s[1], s[2] + 15}, {s[1] + 15, s[2] + 15}};
            String first = null;
            boolean same = true;
            StringBuilder all = new StringBuilder();
            for (int[] p : corners) {
                int rgb = img.getRGB((int) ((p[0] + 0.5) * rx), (int) ((p[1] + 0.5) * ry)) & 0xFFFFFF;
                String hex = String.format("%06X", rgb);
                all.append(all.isEmpty() ? "" : "/").append(hex);
                if (first == null) {
                    first = hex;
                } else if (!first.equals(hex)) {
                    same = false;
                }
            }
            out.put(s[0], same ? first : "mixed:" + all);
        }
        return out;
    }

    /** Checks that exactly {@code filled} slots show the Filled outline and exactly {@code outbid} the Outbid one. */
    static void expect(Session c, String label, List<Integer> filled, List<Integer> outbid) {
        Map<Integer, String> got = outlines(c, label);
        String f = String.format("%06X", FILLED);
        String o = String.format("%06X", OUTBID);
        List<Integer> gotFilled = new ArrayList<>();
        List<Integer> gotOutbid = new ArrayList<>();
        List<String> mixed = new ArrayList<>();
        for (Map.Entry<Integer, String> e : got.entrySet()) {
            if (e.getValue().equals(f)) {
                gotFilled.add(e.getKey());
            } else if (e.getValue().equals(o)) {
                gotOutbid.add(e.getKey());
            } else if (e.getValue().contains(f) || e.getValue().contains(o)) {
                mixed.add(e.getKey() + "=" + e.getValue());
            }
        }
        c.note(label + ": filled outline on " + gotFilled + ", outbid outline on " + gotOutbid
                + "; order slots 10-15 read " + got.get(10) + " " + got.get(11) + " " + got.get(12) + " " + got.get(13)
                + " " + got.get(14) + " " + got.get(15));
        c.check(gotFilled.equals(filled), label + ": filled outline on " + gotFilled + ", want " + filled);
        c.check(gotOutbid.equals(outbid), label + ": outbid outline on " + gotOutbid + ", want " + outbid);
        c.check(mixed.isEmpty(), label + ": partly drawn outlines " + mixed);
    }

    static String tooltip(Session c, int slot) {
        return c.onClient(mc -> {
            AbstractContainerScreen<?> s = (AbstractContainerScreen<?>) McCompat.screen(mc);
            @SuppressWarnings("unchecked")
            List<Component> lines = (List<Component>) Mod.call(s, "getTooltipFromContainerItem",
                    s.getMenu().slots.get(slot).getItem());
            StringBuilder b = new StringBuilder();
            for (Component l : lines) {
                b.append(b.isEmpty() ? "" : " | ").append(l.getString());
            }
            return b.toString();
        });
    }

    private static JsonObject auction(String item, String name, String... lore) {
        JsonObject o = new JsonObject();
        o.addProperty("item", item);
        o.addProperty("name", name);
        JsonArray l = new JsonArray();
        for (String t : lore) {
            l.add(t);
        }
        o.add("lore", l);
        return o;
    }

    /** 1108-menu-ah-highlight: in Manage Auctions the sold listing is green, the expired one red, a running one and
     *  Go Back plain; Highlight Auction House off clears both; Your Bids colors an ended bid green. */
    static void auctions(Session c) throws Exception {
        MenuKit.reset(c);
        boolean gateWas = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled"));
        c.ctx().runOnClient(mc -> Mod.staticCall("util.SkyblockGate", "setEnabled", false));
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set("auction.AuctionConfig", "BazaarEnabled", false);
            cfg.set(CFG, "Enabled", true);
            cfg.set(CFG, "HighlightAuctions", true);
            cfg.set(CFG, "FilledColor", 0x60000000 | FILLED);
            cfg.set(CFG, "ExpiredColor", 0x60000000 | EXPIRED);
            JsonObject slots = new JsonObject();
            slots.add("10", auction("minecraft:diamond_sword", "§aHyperion", "§7Seller: §a[MVP] Me", "",
                    "§7Buy it now: §61,000,000 coins", "", "§7Status: §aSold!", "§eClick to claim!"));
            slots.add("11", auction("minecraft:bow", "§5Terminator", "§7Seller: §a[MVP] Me", "",
                    "§7Buy it now: §62,000,000 coins", "", "§7Status: §cExpired!", "§eClick to claim!"));
            slots.add("12", auction("minecraft:stick", "§fStick", "§7Seller: §a[MVP] Me", "",
                    "§7Buy it now: §65 coins", "", "§7Ends in: §e1h"));
            slots.add("31", auction("minecraft:arrow", "§aGo Back", "§7To Auction House"));
            JsonObject spec = new JsonObject();
            spec.addProperty("title", "Manage Auctions");
            spec.addProperty("rows", 4);
            spec.addProperty("fill", true);
            spec.add("slots", slots);
            MenuKit.show(c, spec);
            MenuKit.awaitScreen(c, "Manage Auctions", 100);
            c.waitUntil("the auction items", mc -> McCompat.screen(mc) instanceof AbstractContainerScreen<?> s
                    && !s.getMenu().slots.get(12).getItem().isEmpty(), 100);
            c.ctx().waitTicks(4);
            Map<Integer, String> got = outlines(c, "ah-on");
            String f = String.format("%06X", FILLED);
            String e = String.format("%06X", EXPIRED);
            c.check(got.get(10).contains(f), "sold listing outline " + got.get(10) + ", want " + f);
            c.check(got.get(11).contains(e), "expired listing outline " + got.get(11) + ", want " + e);
            c.check(!got.get(12).contains(f) && !got.get(12).contains(e), "running listing colored " + got.get(12));
            c.check(!got.get(31).contains(f) && !got.get(31).contains(e), "Go Back colored " + got.get(31));
            c.ctx().runOnClient(mc -> Mod.call(Mod.staticCall(CFG, "getInstance"), "setHighlightAuctions", false));
            Map<Integer, String> off = outlines(c, "ah-off");
            c.check(!off.get(10).contains(f) && !off.get(11).contains(e), "Highlight Auction House off, still " + off.get(10) + "/" + off.get(11));
            c.note("Manage Auctions: sold " + got.get(10) + ", expired " + got.get(11) + ", running " + got.get(12)
                    + "; off: " + off.get(10) + "/" + off.get(11));
        } finally {
            MenuKit.reset(c);
            c.ctx().runOnClient(mc -> Mod.staticCall("util.SkyblockGate", "setEnabled", gateWas));
        }
    }

    static void helper(Session c) throws Exception {
        MenuKit.reset(c);
        boolean gateWas = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled"));
        // Gate off FIRST: Cfg remembers isEnabled() (which reads the gate) as the value to restore.
        c.ctx().runOnClient(mc -> Mod.staticCall("util.SkyblockGate", "setEnabled", false));
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            // Reskin off: Hypixel's own chest is what is drawn (the reskin hides it).
            cfg.set("auction.AuctionConfig", "BazaarEnabled", false);
            cfg.set(CFG, "Enabled", true);
            cfg.set(CFG, "HighlightOutbid", true);
            cfg.set(CFG, "HighlightFilled", true);
            cfg.set(CFG, "OutbidColor", 0x60000000 | OUTBID);
            cfg.set(CFG, "FilledColor", 0x60000000 | FILLED);
            cfg.set(CFG, "HighlightExpired", true);
            cfg.set(CFG, "ExpiredColor", 0x60000000 | EXPIRED);
            int clicksBefore = c.events("container.click").size();
            feedPrices(c, 5_000L);

            MenuKit.show(c, menu());
            MenuKit.awaitScreen(c, "Your Bazaar Orders", 100);
            c.waitUntil("the order items", mc -> McCompat.screen(mc) instanceof AbstractContainerScreen<?> s
                    && !s.getMenu().slots.get(15).getItem().isEmpty(), 100);
            c.ctx().waitTicks(4);

            expect(c, "fresh", List.of(10, 11), List.of(12, 13));
            // Up to three shots: under gate load one shot read slot 16 with a black top-right corner (a frame caught
            // mid-draw; it passed alone on the same jar), so judge the first shot whose four corners agree.
            String ex = outlines(c, "expired").get(16);
            for (int n = 1; n < 3 && ex.startsWith("mixed:"); n++) {
                c.ctx().waitTicks(2);
                ex = outlines(c, "expired-" + n).get(16);
            }
            c.check(ex.contains(String.format("%06X", EXPIRED)), "expired order outline " + ex + ", want "
                    + String.format("%06X", EXPIRED));
            String tExpired = tooltip(c, 16);
            c.check(tExpired.contains("Order Helper: Expired"), "expired tooltip: " + tExpired);

            String tWheat = tooltip(c, 12);
            String tGold = tooltip(c, 13);
            String tRuby = tooltip(c, 14);
            String tFull = tooltip(c, 10);
            c.note("tooltips: wheat [" + tWheat + "] gold [" + tGold + "] ruby [" + tRuby + "] diamond [" + tFull + "]");
            c.check(tWheat.contains("Order Helper: Outbid by a buy order at 2.5"), "wheat tooltip: " + tWheat);
            c.check(tGold.contains("Order Helper: Undercut by a sell offer at 850"), "gold tooltip: " + tGold);
            c.check(tRuby.contains("Order Helper: Best buy order"), "ruby tooltip: " + tRuby);
            c.check(tFull.contains("Order Helper: Filled - ready to claim"), "diamond tooltip: " + tFull);
            c.check(tWheat.contains("Bazaar prices from ") && tWheat.contains(" ago") && !tWheat.contains("too old"),
                    "wheat tooltip has no fresh price age: " + tWheat);
            String tBack = tooltip(c, 31);
            c.check(!tBack.contains("Order Helper"), "Go Back got helper lines: " + tBack);

            // Prices 30 minutes old: not used to call an order outbid; the filled ones stay.
            feedPrices(c, 30L * 60_000L);
            expect(c, "stale-prices", List.of(10, 11), List.of());
            String tStale = tooltip(c, 12);
            c.check(tStale.contains("No Bazaar price to compare") && tStale.contains("too old to judge"),
                    "stale wheat tooltip: " + tStale);

            feedPrices(c, 5_000L);
            c.ctx().runOnClient(mc -> Mod.call(Mod.staticCall(CFG, "getInstance"), "setHighlightOutbid", false));
            expect(c, "outbid-off", List.of(10, 11), List.of());
            c.ctx().runOnClient(mc -> Mod.call(Mod.staticCall(CFG, "getInstance"), "setHighlightOutbid", true));
            c.ctx().runOnClient(mc -> Mod.call(Mod.staticCall(CFG, "getInstance"), "setHighlightFilled", false));
            expect(c, "filled-off", List.of(), List.of(12, 13));
            c.ctx().runOnClient(mc -> Mod.call(Mod.staticCall(CFG, "getInstance"), "setHighlightFilled", true));

            c.ctx().runOnClient(mc -> Mod.call(Mod.staticCall(CFG, "getInstance"), "setEnabled", false));
            expect(c, "feature-off", List.of(), List.of());
            String tOff = tooltip(c, 12);
            c.check(!tOff.contains("Order Helper"), "feature off, tooltip still has helper lines: " + tOff);
            c.ctx().runOnClient(mc -> Mod.call(Mod.staticCall(CFG, "getInstance"), "setEnabled", true));

            // Positive control on the same screen: on again, the colors come back.
            expect(c, "on-again", List.of(10, 11), List.of(12, 13));

            int clicks = c.events("container.click").size() - clicksBefore;
            c.check(clicks == 0, "the order helper clicked " + clicks + " time(s) - it must be draw-only");
            c.note("container clicks: " + clicks);
        } finally {
            MenuKit.reset(c);
            c.ctx().runOnClient(mc -> Mod.staticCall("util.SkyblockGate", "setEnabled", gateWas));
        }
    }
}
