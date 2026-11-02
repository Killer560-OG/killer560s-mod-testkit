package dev.testkit.gametest.menu;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.hx.Hx;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 2790-2796 (doc-flip-1101): the to-do Doc's "BAZAAR FLIPPER" and "AH FLIPPER" items of 2026-11-01. Every Bazaar case is
 * judged by what the SERVER recorded (sign.update, menu.click, menu.give), as {@link BazaarFlipCustomCases}' are.
 * <ul>
 *   <li>2790 Kaleidoscopic Mineral (his 2026-10-30 log: "Insta Buy 254 Kaleidoscopic Mineral", "typed 254"): a product that
 *       does not stack is sized one per free slot - 36 with an empty inventory - never 254.</li>
 *   <li>2791 the Confirm Instant Buy price over the purse above the Reserve (his "isnt accounting for the reserve"): the
 *       page is never confirmed; the buy is tried again smaller and the purchase made fits the reserve.</li>
 *   <li>2792 Min Profit Per Cycle: the best flip by percentage makes 1,000 a cycle and is passed over at a 5,000 minimum;
 *       the next one is bought.</li>
 *   <li>2793 Stop At Max Orders: on, a cap of one order ends the session after its cycle; off, a second round starts.</li>
 *   <li>2794 AH Price Copier: an item in Create BIN Auction copies its lowest BIN less one; after "BIN Auction started for
 *       NAME!" the same item copies the price it was just listed at.</li>
 *   <li>2795 AH Flipper, Background: a new BIN at half the item's lowest is announced with a clickable [Open] running
 *       /viewauction, and that command opens the auction.</li>
 *   <li>2796 AH Flipper, Main (cheat): the macro opens the flip, clicks Buy Item Right Now then Confirm, and counts the
 *       purchase from "You purchased NAME for N coins!".</li>
 * </ul>
 */
final class Flip1101MenuCases {

    private Flip1101MenuCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "2790-menu-bzflip-unstackable", Flip1101MenuCases::unstackable);
        MenuSuite.test(s, "2791-menu-bzflip-confirm-reserve", Flip1101MenuCases::confirmReserve);
        MenuSuite.test(s, "2792-menu-bzflip-min-profit", Flip1101MenuCases::minProfit);
        MenuSuite.test(s, "2793-menu-bzflip-max-orders-rounds", Flip1101MenuCases::maxOrderRounds);
        MenuSuite.test(s, "2794-menu-ah-price-copier", Flip1101MenuCases::priceCopier);
        MenuSuite.test(s, "2795-menu-ah-flipper-chat", c -> ahFlipper(c, false));
        MenuSuite.test(s, "2796-menu-ah-flipper-macro", c -> ahFlipper(c, true));
    }

    static boolean newMod(Session c) {
        if (!Mod.has("bazaarflip.BazaarStackSizes")) {
            c.note("mod before doc-flip-1101: nothing to check");
            return false;
        }
        return true;
    }

    static final BazaarFlipCustomCases.Product KALEIDO = new BazaarFlipCustomCases.Product("SALMON_OPAL",
            "Kaleidoscopic Mineral", "paper", "Reforge Stones");

    // ---- 2790 -------------------------------------------------------------------------------------------------------

    static void unstackable(Session c) throws Exception {
        if (BazaarFlipCustomCases.cheatOnly(c) != null || !newMod(c)) {
            return;
        }
        double each = 1_351.0;
        double npc = 2_000.0;
        try (AutoCloseable w = BazaarFlipCustomCases.world(c, KALEIDO, 254, each, npc, 0, 10, false)) {
            // The catalog's "unstackable" mark (Kaleidoscopic Mineral is a reforge stone: REFORGE_STONE in the real catalog).
            c.ctx().runOnClient(mc -> {
                Mod.staticCall("bazaarflip.BazaarStackSizes", "clearLearnedForTest");
                @SuppressWarnings("unchecked")
                Set<String> set = (Set<String>) Mod.field("itembrowser.SkyblockItemRepository", "UNSTACKABLE");
                set.add(KALEIDO.id());
            });
            JsonObject page = BazaarFlipCustomCases.instant(KALEIDO, KALEIDO.instantTitle(), each, "71,680",
                    BazaarFlipCustomCases.Sign.normal(), 1);
            c.hx().call("menu.onCommand", Hx.args("name", "bz", "menu", BazaarFlipCustomCases.bazaar(KALEIDO, page)));
            c.hx().call("menu.onCommand", Hx.args("name", "trades", "menu", BazaarFlipCustomCases.trades(npc, 0, null)));
            long mark = LogTap.mark();
            int before = BazaarFlipCustomCases.profitCycles(c);
            BazaarFlipCustomCases.startAndAwaitSale(c, before, 900);
            FlipCroesusCases.requestStop(c);
            List<String> amounts = BazaarFlipCustomCases.signAmounts(c);
            c.check(!amounts.isEmpty() && Long.parseLong(amounts.get(0)) <= 36 && Long.parseLong(amounts.get(0)) >= 1,
                    "typed " + amounts + " for an item that holds one per slot (36 free)");
            c.check(FlipMarket.logged(mark, "Insta Buy 36 Kaleidoscopic Mineral"), "the decision was not 36: "
                    + LogTap.since(mark).stream().filter(l -> l.contains("Insta Buy")).toList());
            c.check(BazaarFlipCustomCases.given(c) == 36, "delivered " + BazaarFlipCustomCases.given(c));
            Object cyc = BazaarFlipCustomCases.cycleAt(c, before);
            c.check(BazaarFlipCustomCases.units(cyc, "bought") == 36, "record " + cyc);
            c.note("unstackable, 254 offered, empty inventory: typed " + amounts + ", delivered "
                    + BazaarFlipCustomCases.given(c) + ", sold " + BazaarFlipCustomCases.sold(c));
        } finally {
            c.ctx().runOnClient(mc -> {
                @SuppressWarnings("unchecked")
                Set<String> set = (Set<String>) Mod.field("itembrowser.SkyblockItemRepository", "UNSTACKABLE");
                set.remove(KALEIDO.id());
            });
        }
    }

    // ---- 2791 -------------------------------------------------------------------------------------------------------

    /**
     * Purse 1,000,000, Reserve 50%: 500,000 to spend. The snapshot and the page's presets price Lump of Magma at 300 (NPC
     * 500), so 1,602 fit (1,602 x 312 = 499,824). The Confirm Instant Buy page then asks 1,602 x 400 x 1.04 = 666,432 - the
     * purchase that would have taken the purse to 333,568, under the reserve. It must not be confirmed; the buy is resized
     * to 1,201 (1,201 x 416 = 499,616), confirmed, and the 1,201 are sold.
     */
    static void confirmReserve(Session c) throws Exception {
        if (BazaarFlipCustomCases.cheatOnly(c) != null || !newMod(c)) {
            return;
        }
        BazaarFlipCustomCases.Product p = BazaarFlipCustomCases.MAGMA;
        double book = 300.0;
        double charged = 400.0;
        double npc = 500.0;
        try (AutoCloseable w = BazaarFlipCustomCases.world(c, p, 10_000, book, npc, 0, 10, false);
             MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set("bazaarflip.BazaarFlipConfig", "AutopilotReservePercent", 50);
            c.ctx().runOnClient(mc -> Mod.staticCall("bazaarflip.BazaarStackSizes", "clearLearnedForTest"));
            JsonObject page = BazaarFlipCustomCases.instant(p, p.instantTitle(), charged, book, "71,680",
                    new BazaarFlipCustomCases.Sign(false, true, 71_680), 1);
            c.hx().call("menu.onCommand", Hx.args("name", "bz", "menu", BazaarFlipCustomCases.bazaar(p, page)));
            c.hx().call("menu.onCommand", Hx.args("name", "trades", "menu", BazaarFlipCustomCases.trades(npc, 0, null)));
            long mark = LogTap.mark();
            int before = BazaarFlipCustomCases.profitCycles(c);
            BazaarFlipCustomCases.startAndAwaitSale(c, before, 1500);
            FlipCroesusCases.requestStop(c);
            List<String> amounts = BazaarFlipCustomCases.signAmounts(c);
            List<Integer> confirms = FlipCroesusCases.clicksOn(c, "Confirm Instant Buy");
            c.check(FlipCroesusCases.first(amounts, 2).equals(List.of("1602", "1201")), "sign amounts " + amounts
                    + " (want 1602, then 1201 after the confirm price came out over the reserve)");
            c.check(confirms.size() == 1, "Confirm Instant Buy clicked " + confirms.size() + " time(s) before the sale: "
                    + FlipCroesusCases.allClicks(c));
            int given = BazaarFlipCustomCases.given(c);
            c.check(given == 1201, "delivered " + given);
            c.check(given * charged <= 500_000.0, "spent " + given * charged + ", over the 500,000 above the reserve");
            c.check(FlipMarket.logged(mark, "Buying fewer Lump of Magma"), "no resize line");
            c.note("reserve 50% of 1,000,000: typed " + amounts + ", confirmed " + confirms.size() + "x, delivered " + given
                    + " at " + charged + " = " + given * charged);
        }
    }

    // ---- 2792 -------------------------------------------------------------------------------------------------------

    static void minProfit(Session c) throws Exception {
        if (BazaarFlipCustomCases.cheatOnly(c) != null || !newMod(c)) {
            return;
        }
        BazaarFlipCustomCases.Product a = BazaarFlipCustomCases.PUFFER; // 10 at 100 under NPC 200: +1,000, 100%
        BazaarFlipCustomCases.Product b = BazaarFlipCustomCases.MAGMA;  // 1,000 at 100 under NPC 120: +20,000, 20%
        try (AutoCloseable w = BazaarFlipCustomCases.world(c, a, 10, 100.0, 200.0, 0, 10, false, b.market(120.0, 100.0, 1_000));
             MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            String adv = "bazaaradvisor.BazaarAdvisorConfig";
            cfg.set(adv, "Goal", Mod.enumValue(adv + "$Goal", "PROFIT_PERCENT"))
                    .set("bazaarflip.BazaarFlipConfig", "MinProfitPerCycle", 5_000L);
            JsonObject aInstant = BazaarFlipCustomCases.instant(a, BazaarFlipCustomCases.PUFFER_CUT, 100.0, "71,680",
                    BazaarFlipCustomCases.Sign.normal(), 1);
            JsonObject bInstant = BazaarFlipCustomCases.instant(b, b.instantTitle(), 100.0, "71,680",
                    BazaarFlipCustomCases.Sign.normal(), 1);
            String searchTitle = "Bazaar ➜ \"Flip\"";
            JsonObject search = FlipMarket.withManageOrders(FlipCroesusCases.menu(searchTitle, 6, Map.of(
                    11, BazaarFlipCustomCases.priced(a), 12, BazaarFlipCustomCases.priced(b),
                    45, FlipCroesusCases.item("minecraft:oak_sign", "§aSearch"),
                    49, FlipCroesusCases.item("minecraft:arrow", "§aGo Back"),
                    50, FlipCroesusCases.item("minecraft:book", "§aManage Orders")),
                    Map.of(11, FlipCroesusCases.open(BazaarFlipCustomCases.productPage(a, aInstant)),
                            12, FlipCroesusCases.open(BazaarFlipCustomCases.productPage(b, bInstant)))));
            c.hx().call("menu.onCommand", Hx.args("name", "bz", "menu", search));
            c.hx().call("menu.onCommand", Hx.args("name", "trades", "menu", BazaarFlipCustomCases.trades(120.0, 0, null)));
            int before = BazaarFlipCustomCases.profitCycles(c);
            BazaarFlipCustomCases.startAndAwaitSale(c, before, 1200);
            FlipCroesusCases.requestStop(c);
            c.check(FlipMarket.sent(c, "bz " + a.name()) == 0, "opened the 1,000-coin flip: " + c.commands());
            c.check(FlipMarket.sent(c, "bz " + b.name()) >= 1, "never opened the 20,000-coin flip: " + c.commands());
            List<String> amounts = BazaarFlipCustomCases.signAmounts(c);
            c.check(FlipCroesusCases.first(amounts, 1).equals(List.of("1000")), "sign amounts " + amounts);
            Object cyc = BazaarFlipCustomCases.cycleAt(c, before);
            c.check(b.id().equals(Mod.call(cyc, "productId")), "first record " + cyc);
            c.note("min profit 5,000, best by % makes 1,000: commands " + c.commands() + ", typed " + amounts);
        }
    }

    // ---- 2793 -------------------------------------------------------------------------------------------------------

    static void maxOrderRounds(Session c) throws Exception {
        if (BazaarFlipCustomCases.cheatOnly(c) != null || !newMod(c)) {
            return;
        }
        BazaarFlipCustomCases.Product p = BazaarFlipCustomCases.PUFFER;
        for (boolean stop : new boolean[]{true, false}) {
            try (AutoCloseable w = BazaarFlipCustomCases.world(c, p, 100, 1_900.0, 2_400.0, 0, 10, false);
                 MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
                cfg.set("bazaarflip.BazaarFlipConfig", "AutopilotMaxOrdersOn", true)
                        .set("bazaarflip.BazaarFlipConfig", "AutopilotMaxOrders", 1)
                        .set("bazaarflip.BazaarFlipConfig", "StopAtMaxOrders", stop);
                JsonObject page = BazaarFlipCustomCases.instant(p, BazaarFlipCustomCases.PUFFER_CUT, 1_900.0, "40",
                        BazaarFlipCustomCases.Sign.normal(), 4);
                c.hx().call("menu.onCommand", Hx.args("name", "bz", "menu", BazaarFlipCustomCases.bazaar(p, page)));
                c.hx().call("menu.onCommand", Hx.args("name", "trades", "menu",
                        BazaarFlipCustomCases.trades(2_400.0, 0, null)));
                long mark = LogTap.mark();
                int base = BazaarFlipCustomCases.signAmounts(c).size(); // events run from the case's start
                int before = BazaarFlipCustomCases.profitCycles(c);
                BazaarFlipCustomCases.startAndAwaitSale(c, before, 1200);
                c.waitUntil("a second Insta Buy or the stop", mc -> BazaarFlipCustomCases.signAmounts(c).size() >= base + 2
                        || !(Boolean) Mod.staticCall("bazaarflip.BazaarFlipFeature", "isRunning"), 900);
                String reason = BazaarFlipCustomCases.stopReason(c);
                boolean running = BazaarFlipCustomCases.running(c);
                FlipCroesusCases.requestStop(c);
                List<String> all = BazaarFlipCustomCases.signAmounts(c);
                List<String> amounts = all.subList(base, all.size());
                if (stop) {
                    c.check(!running && reason.contains("order cap"), "Stop At Max Orders on: stopped with \"" + reason
                            + "\", running " + running);
                    c.check(amounts.size() == 1, "on: sign amounts " + amounts);
                } else {
                    c.check(amounts.size() >= 2, "off: sign amounts " + amounts + " (no second round); stop: " + reason);
                    c.check(FlipMarket.logged(mark, "Round 2"), "off: no \"Round 2\" line");
                }
                c.note("Stop At Max Orders " + (stop ? "on" : "off") + ", cap 1: typed " + amounts + ", stop \"" + reason + "\"");
            }
        }
    }

    // ---- AH helpers -------------------------------------------------------------------------------------------------

    /** Feeds the real API page, indexes it, and returns {key, lowest, lowestUuid(undashed), count} of the first item that
     *  qualifies ({@code plain}: no stars, no Recombobulator, not a pet), or null. */
    static Object[] seedMarket(Session c, int minCount, boolean plain) {
        AuctionHouseCases.feedApi(c);
        return c.onClient(mc -> {
            Mod.staticCall("ahflip.AhMarketIndex", "rebuild", Mod.staticCall("auction.AuctionHouseApi", "getListings"));
            @SuppressWarnings("unchecked")
            Map<String, Object> markets = (Map<String, Object>) Mod.field("ahflip.AhMarketIndex", "markets");
            for (Map.Entry<String, Object> e : markets.entrySet()) {
                String key = e.getKey();
                if (plain && (key.contains("*") || key.contains("+") || key.startsWith("PET_"))) {
                    continue;
                }
                if (key.startsWith("PET_")) {
                    continue;
                }
                int count = ((Number) Mod.call(e.getValue(), "count")).intValue();
                long lowest = ((Number) Mod.call(e.getValue(), "lowest")).longValue();
                if (count >= minCount && lowest >= 1_000) {
                    UUID u = (UUID) Mod.call(e.getValue(), "lowestUuid");
                    return new Object[]{key, lowest, u.toString().replace("-", ""), count};
                }
            }
            return null;
        });
    }

    /** The fixture auction with this id, with its times moved to now (as the feed did). */
    static JsonObject fixtureAuction(String undashed) {
        for (JsonElement e : AuctionHouseCases.apiPage().getAsJsonArray("auctions")) {
            if (e.getAsJsonObject().get("uuid").getAsString().equals(undashed)) {
                return e.getAsJsonObject();
            }
        }
        return null;
    }

    static String page(JsonObject... auctions) {
        JsonObject root = new JsonObject();
        root.addProperty("success", true);
        root.addProperty("page", 0);
        root.addProperty("totalPages", 1);
        root.addProperty("totalAuctions", auctions.length);
        root.addProperty("lastUpdated", System.currentTimeMillis());
        JsonArray a = new JsonArray();
        for (JsonObject o : auctions) {
            a.add(o);
        }
        root.add("auctions", a);
        return root.toString();
    }

    // ---- 2794 -------------------------------------------------------------------------------------------------------

    static void priceCopier(Session c) throws Exception {
        if (!newMod(c)) {
            return;
        }
        MenuKit.reset(c);
        boolean gate = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled"));
        String oldClip = c.onClient(mc -> mc.keyboardHandler.getClipboard());
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            LogTap.install();
            cfg.set("ahflip.AhFlipConfig", "CopierEnabled", true)
                    .set("ahflip.AhFlipConfig", "CopierUndercut", 1L)
                    .set("ahflip.AhFlipConfig", "CopierOwnPrice", true);
            c.ctx().runOnClient(mc -> {
                Mod.staticCall("util.SkyblockGate", "setEnabled", false);
                Mod.staticCall("ahflip.AhPriceCopier", "resetForTest");
                mc.keyboardHandler.setClipboard("before");
            });
            Object[] m = seedMarket(c, 1, true);
            c.check(m != null, "no plain item with a BIN in the fixture");
            if (m == null) {
                return;
            }
            String key = (String) m[0];
            long lowest = (Long) m[1];
            String stack = "minecraft:paper[minecraft:custom_data={id:\"" + key + "\"}]";
            JsonObject create = FlipCroesusCases.menu("Create BIN Auction", 6, Map.of(
                    13, FlipCroesusCases.item(stack, "§aTest Item"),
                    31, FlipCroesusCases.item("minecraft:gold_ingot", "§6Item Price", "§7Buy it now: §61,234,567 coins")),
                    Map.of());
            MenuKit.show(c, create);
            c.waitUntil("the lowest BIN copied", mc -> Long.toString(lowest - 1).equals(mc.keyboardHandler.getClipboard()), 100);
            String first = c.onClient(mc -> mc.keyboardHandler.getClipboard());
            c.check(Long.toString(lowest - 1).equals(first), "clipboard \"" + first + "\", lowest BIN of " + key + " is " + lowest);
            // He lists it: Hypixel says so in chat.
            c.server().command("tellraw @a {\"text\":\"BIN Auction started for Test Item!\"}");
            c.ctx().waitTicks(10);
            c.ctx().runOnClient(mc -> {
                if (McCompat.screen(mc) != null) {
                    McCompat.setScreen(mc, null);
                }
            });
            c.ctx().waitTicks(5);
            long remembered = c.onClient(mc -> (Long) Mod.staticCall("ahflip.AhPriceCopier", "remembered", key));
            c.check(remembered == 1_234_567L, "remembered " + remembered + " after BIN Auction started");
            // The next one of the same item copies the price just listed at, over the lowest BIN.
            MenuKit.show(c, create);
            c.waitUntil("the listed price copied", mc -> "1234566".equals(mc.keyboardHandler.getClipboard()), 100);
            String second = c.onClient(mc -> mc.keyboardHandler.getClipboard());
            c.check("1234566".equals(second), "clipboard \"" + second + "\" after listing at 1,234,567");
            c.note(key + ": lowest BIN " + lowest + " -> copied " + first + "; listed at 1,234,567 -> copied " + second);
        } finally {
            c.ctx().runOnClient(mc -> {
                Mod.staticCall("util.SkyblockGate", "setEnabled", gate);
                Mod.staticCall("ahflip.AhPriceCopier", "resetForTest");
                mc.keyboardHandler.setClipboard(oldClip == null ? "" : oldClip);
                if (McCompat.screen(mc) != null) {
                    McCompat.setScreen(mc, null);
                }
            });
            MenuKit.reset(c);
        }
    }

    // ---- 2795 / 2796 ------------------------------------------------------------------------------------------------

    static void ahFlipper(Session c, boolean macro) throws Exception {
        if (!newMod(c)) {
            return;
        }
        if (macro && !Mod.isCheat()) {
            c.note("legit jar: no macro (Run Mode reads Background)");
            boolean main = c.onClient(mc -> {
                Mod.staticCall("ahflip.AhFlipConfig", "getInstance");
                Object cfg = Mod.staticCall("ahflip.AhFlipConfig", "getInstance");
                Mod.call(cfg, "setRunMode", Mod.enumValue("ahflip.AhFlipConfig$RunMode", "MAIN"));
                boolean active = (Boolean) Mod.call(cfg, "isMacroActive");
                Mod.call(cfg, "setRunMode", Mod.enumValue("ahflip.AhFlipConfig$RunMode", "BACKGROUND"));
                return active;
            });
            c.check(!main, "the legit jar's macro is active");
            return;
        }
        MenuKit.reset(c);
        boolean gate = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled"));
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            LogTap.install();
            c.ctx().runOnClient(mc -> {
                Mod.staticCall("util.SkyblockGate", "setEnabled", false);
                Mod.staticCall("ahflip.AhFlipper", "resetForTest");
            });
            Object[] m = seedMarket(c, 1, false);
            c.check(m != null, "no item with a BIN in the fixture");
            if (m == null) {
                return;
            }
            long lowest = (Long) m[1];
            JsonObject clone = fixtureAuction((String) m[2]);
            c.check(clone != null, "fixture auction " + m[2] + " not found");
            if (clone == null) {
                return;
            }
            String id = UUID.randomUUID().toString().replace("-", "");
            clone = clone.deepCopy();
            clone.addProperty("uuid", id);
            clone.addProperty("auctioneer", UUID.randomUUID().toString().replace("-", ""));
            long price = Math.max(1, lowest / 2);
            clone.addProperty("starting_bid", price);
            String name = clone.get("item_name").getAsString().replaceAll("§.", "");
            cfg.set("ahflip.AhFlipConfig", "MinListings", 1)
                    .set("ahflip.AhFlipConfig", "MinSellPrice", 0L)
                    .set("ahflip.AhFlipConfig", "MinProfit", 0L)
                    .set("ahflip.AhFlipConfig", "MinProfitPercent", 2)
                    .set("ahflip.AhFlipConfig", "MaxBuyPrice", 0L)
                    .set("ahflip.AhFlipConfig", "RunMode", Mod.enumValue("ahflip.AhFlipConfig$RunMode",
                            macro ? "MAIN" : "BACKGROUND"));
            if (macro) {
                cfg.set("ahflip.AhFlipConfig", "MacroMaxBuys", 3);
                JsonObject confirm = FlipCroesusCases.menu("Confirm Purchase", 3, Map.of(
                        11, FlipCroesusCases.item("minecraft:green_terracotta", "§aConfirm"),
                        13, FlipCroesusCases.item("minecraft:paper", "§f" + name),
                        15, FlipCroesusCases.item("minecraft:red_terracotta", "§cCancel")),
                        Map.of(11, FlipCroesusCases.closeWith(String.format("You purchased %s for %,d coins!", name, price))));
                JsonObject view = FlipCroesusCases.menu("BIN Auction View", 6, Map.of(
                        13, FlipCroesusCases.item("minecraft:paper", "§f" + name),
                        31, FlipCroesusCases.item("minecraft:gold_nugget", "§6Buy Item Right Now",
                                String.format("§7Price: §6%,d coins", price)),
                        49, FlipCroesusCases.item("minecraft:arrow", "§aGo Back")),
                        Map.of(31, FlipCroesusCases.open(confirm)));
                c.hx().call("menu.onCommand", Hx.args("name", "viewauction", "menu", view));
            } else {
                JsonObject view = FlipCroesusCases.menu("BIN Auction View", 6, Map.of(
                        13, FlipCroesusCases.item("minecraft:paper", "§f" + name)), Map.of());
                c.hx().call("menu.onCommand", Hx.args("name", "viewauction", "menu", view));
            }
            String body = page(clone);
            int found = c.onClient(mc -> (Integer) Mod.staticCall("ahflip.AhFlipper", "judgePageForTest", body, 0L));
            c.check(found == 1, "flips found in a page with one BIN at half its lowest (" + price + " vs " + lowest + "): "
                    + found);
            long mark = LogTap.mark();
            cfg.set("ahflip.AhFlipConfig", "FlipperEnabled", true);
            c.waitUntil("the flip announced", mc -> Mod.staticCall("ahflip.AhFlipper", "lastOpenForTest") != null, 100);
            Component open = c.onClient(mc -> (Component) Mod.staticCall("ahflip.AhFlipper", "lastOpenForTest"));
            ClickEvent click = open == null ? null : open.getStyle().getClickEvent();
            String command = click instanceof ClickEvent.RunCommand rc ? rc.command() : String.valueOf(click);
            c.check(("/viewauction " + id).equals(command), "the [Open] runs \"" + command + "\", want /viewauction " + id);
            c.check(LogTap.since(mark).stream().anyMatch(l -> l.contains("[CHAT]") && l.contains("[Open]")),
                    "no chat line with [Open]");
            if (!macro) {
                // His click: the chat link runs the command.
                c.ctx().runOnClient(mc -> mc.player.connection.sendCommand("viewauction " + id));
                c.waitUntil("the auction to open", mc -> McCompat.screen(mc) != null
                        && McCompat.screen(mc).getTitle().getString().contains("BIN Auction View"), 100);
                c.check(c.commands().contains("viewauction " + id), "server commands " + c.commands());
                c.note("Background: " + name + " at " + price + " (lowest " + lowest + ") announced; [Open] runs "
                        + command + " and opened BIN Auction View");
                return;
            }
            c.waitUntil("the macro to buy", mc -> String.valueOf(Mod.staticCall("ahflip.AhFlipMacro", "statusForTest"))
                    .startsWith("1|"), 400);
            String status = c.onClient(mc -> String.valueOf(Mod.staticCall("ahflip.AhFlipMacro", "statusForTest")));
            c.check(status.startsWith("1|"), "macro status " + status + "; clicks " + FlipCroesusCases.allClicks(c)
                    + "; commands " + c.commands());
            c.check(c.commands().contains("viewauction " + id), "server commands " + c.commands());
            c.check(FlipCroesusCases.clicksOn(c, "BIN Auction View").equals(List.of(31)),
                    "BIN Auction View clicks " + FlipCroesusCases.clicksOn(c, "BIN Auction View"));
            c.check(FlipCroesusCases.clicksOn(c, "Confirm Purchase").equals(List.of(11)),
                    "Confirm Purchase clicks " + FlipCroesusCases.clicksOn(c, "Confirm Purchase"));
            String key = (String) m[0];
            long relist = c.onClient(mc -> (Long) Mod.staticCall("ahflip.AhPriceCopier", "remembered", key));
            c.check(relist > price, "relist price left for the copier: " + relist);
            c.note("Main: bought " + name + " at " + price + " (lowest " + lowest + "), clicks "
                    + FlipCroesusCases.allClicks(c) + "; relist at " + relist);
        } finally {
            c.ctx().runOnClient(mc -> {
                Mod.staticCall("util.SkyblockGate", "setEnabled", gate);
                Mod.staticCall("ahflip.AhFlipper", "resetForTest");
                Mod.staticCall("ahflip.AhPriceCopier", "resetForTest");
                if (McCompat.screen(mc) != null) {
                    McCompat.setScreen(mc, null);
                }
            });
            c.hx().call("menu.onCommand", Hx.args("name", "viewauction", "menu", JsonNull.INSTANCE));
            MenuKit.reset(c);
        }
    }
}
