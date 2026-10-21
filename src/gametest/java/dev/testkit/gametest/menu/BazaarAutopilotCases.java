package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.hx.Hx;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import java.util.List;
import java.util.Map;

/**
 * 2776-2779 (bazaar-cases-1020): the Bazaar Flipper autopilot's behaviours killer560 relies on, each judged by what the
 * SERVER recorded (menu.click by title and slot, menu.give, sell.sold) and by the autopilot's own books.
 * <ul>
 *   <li>2776 (mod 4be360bb) the advisor is asked again every boot: start refuses and opens the advisor's setup until it
 *       is answered ("Use last" counts); then it starts.</li>
 *   <li>2777 (mod e1309474, de5cb49c) a buy order of 4 through Hypixel's "Buy 4!" preset (no amount lore: read from its
 *       name), Top Order +0.1, Confirm Buy Order; Manage Orders then lists it filled, and the autopilot adopts it,
 *       collects the 4 and sells them to the NPC.</li>
 *   <li>2778 the same with one unit: "Buy only one!".</li>
 *   <li>2779 (mod de5cb49c) "[Bazaar] Placing orders is on cooldown for up to 1 minute!" after Confirm Buy Order: the
 *       refused order is not counted, the session pauses 60 s from the line (and stays running, doing nothing), then
 *       carries on and places the order.</li>
 * </ul>
 */
final class BazaarAutopilotCases {

    private BazaarAutopilotCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "2776-menu-bzflip-advisor-each-boot", BazaarAutopilotCases::advisorEachBoot);
        MenuSuite.test(s, "2777-menu-bzflip-order-buy-n-collect", c -> buyOrder(c, 4, true));
        MenuSuite.test(s, "2778-menu-bzflip-order-buy-only-one", c -> buyOrder(c, 1, false));
        MenuSuite.test(s, "2779-menu-bzflip-order-cooldown", BazaarAutopilotCases::cooldown);
    }

    static final BazaarFlipCustomCases.Product MAGMA = BazaarFlipCustomCases.MAGMA;
    static final String AMOUNT_TITLE = "How many do you want?";
    static final String PRICE_TITLE = "How much do you want to pay?";
    static final String CONFIRM_TITLE = "Confirm Buy Order";
    static final String ORDERS_TITLE = "Your Bazaar Orders";
    static final double BID = 35.0;
    static final double NPC = 50.0;
    static final String COOLDOWN = "[Bazaar] Placing orders is on cooldown for up to 1 minute!";

    /**
     * Lump of Magma for a buy order: the best bid 35.0 (the autopilot bids 35.1), the NPC paying 50, no competing buy
     * orders, and {@code fillsPerDay} instant-sold a day - which is what sizes the order (FlipPricing: a buy order is
     * sized by what fills in a day).
     */
    static BazaarFlipKit.Product product(int fillsPerDay) {
        return new BazaarFlipKit.Product(MAGMA.id(), "MAGMA_CREAM", NPC,
                List.of(new BazaarFlipKit.Level(60.0, 10_000)), List.of(new BazaarFlipKit.Level(BID, 10_000)),
                7L * fillsPerDay, 70_000, 0, 10);
    }

    static AutoCloseable world(Session c, int fillsPerDay, int speed, int maxOrders) {
        return BazaarFlipCustomCases.world(c, List.of(product(fillsPerDay)), 0, speed, false, maxOrders,
                BazaarFlipKit.Answers.ORDER_TO_NPC);
    }

    // ---- Hypixel's buy order pages -----------------------------------------------------------------------------------

    /** "BUY Lump of Magma", {@code n} ordered at 35.1, all filled and waiting to be claimed. */
    static JsonObject filledOrder(int n) {
        return FlipCroesusCases.item("minecraft:magma_cream", "§a§lBUY §aLump of Magma", "§8Worth " + n + " items",
                "", "§7Order amount: §a" + n + "§7x", "§7Filled: §a" + n + "§7/" + n + " §a§l100%!", "",
                "§7Price per unit: §635.1 coins", "", "§7By: §bPlayer0", "", "§7You have §a" + n + " items §7to claim!", "",
                "§eClick to claim!");
    }

    /** Confirm Buy Order for {@code n} at 35.1; clicking Buy Order (13) runs {@code onBuy}. */
    static JsonObject confirm(int n, JsonObject onBuy) {
        return FlipCroesusCases.menu(CONFIRM_TITLE, 4, Map.of(
                        13, FlipCroesusCases.item("minecraft:magma_cream", "§aBuy Order", "§8Lump of Magma", "",
                                "§7Price per unit: §635.1 coins", "§7Order amount: §a" + n + "§7x",
                                String.format("§7Total price: §6%,.1f coins", n * 35.1), "", "§eClick to submit order!"),
                        31, FlipCroesusCases.item("minecraft:arrow", "§aGo Back")),
                Map.of(13, onBuy));
    }

    /** "How much do you want to pay?": Same as Top Order (10, 35.0), Top Order +0.1 (12, 35.1), Custom Price (16). */
    static JsonObject price(int n, JsonObject onBuy) {
        return FlipCroesusCases.menu(PRICE_TITLE, 4, Map.of(
                        10, FlipCroesusCases.item("minecraft:gold_nugget", "§6Same as Top Order", "§7Price per unit: §635.0 coins"),
                        12, FlipCroesusCases.item("minecraft:gold_ingot", "§6Top Order +0.1", "§7Price per unit: §635.1 coins"),
                        16, FlipCroesusCases.item("minecraft:oak_sign", "§6Custom Price"),
                        31, FlipCroesusCases.item("minecraft:arrow", "§aGo Back")),
                Map.of(12, FlipCroesusCases.open(confirm(n, onBuy))));
    }

    /**
     * "How many do you want?" as killer560's client showed it on 2026-10-20: Buy only one! (10), Buy 4! (12), Buy 32!
     * (14), Custom Amount (16) - none with an Amount line, so each preset's amount is read from its name. Each preset
     * leads on to the price page for its own amount; Custom Amount closes the page (it must not be used here).
     */
    static JsonObject amount(JsonObject onBuy) {
        return FlipCroesusCases.menu(AMOUNT_TITLE, 4, Map.of(
                        10, FlipCroesusCases.item("minecraft:magma_cream", "§aBuy only §eone§a!", "§8Lump of Magma"),
                        12, FlipCroesusCases.item("minecraft:magma_cream", "§aBuy §e4§a!", "§8Lump of Magma"),
                        14, FlipCroesusCases.item("minecraft:magma_cream", "§aBuy §e32§a!", "§8Lump of Magma"),
                        16, FlipCroesusCases.item("minecraft:oak_sign", "§aCustom Amount", "§8Buy Order Quantity"),
                        31, FlipCroesusCases.item("minecraft:arrow", "§aGo Back")),
                Map.of(10, FlipCroesusCases.open(price(1, onBuy)), 12, FlipCroesusCases.open(price(4, onBuy)),
                        14, FlipCroesusCases.open(price(32, onBuy)),
                        16, FlipCroesusCases.closeWith("unexpected: Custom Amount")));
    }

    /** /bz: the search page (Manage Orders at 50 opening {@code orders}), the product page with Create Buy Order. */
    static JsonObject bz(JsonObject orders, JsonObject onBuy) {
        JsonObject product = FlipCroesusCases.menu(MAGMA.productTitle(), 4, Map.of(
                        10, FlipCroesusCases.item("minecraft:golden_horse_armor", "§aBuy Instantly"),
                        11, FlipCroesusCases.item("minecraft:hopper", "§6Sell Instantly"),
                        13, BazaarFlipCustomCases.unit(MAGMA),
                        15, FlipCroesusCases.item("minecraft:map", "§aCreate Buy Order"),
                        16, FlipCroesusCases.item("minecraft:map", "§6Create Sell Offer"),
                        31, FlipCroesusCases.item("minecraft:arrow", "§aGo Back")),
                Map.of(15, FlipCroesusCases.open(amount(onBuy))));
        JsonObject search = FlipCroesusCases.menu(MAGMA.searchTitle(), 6, Map.of(
                        11, BazaarFlipCustomCases.priced(MAGMA),
                        45, FlipCroesusCases.item("minecraft:oak_sign", "§aSearch"),
                        49, FlipCroesusCases.item("minecraft:arrow", "§aGo Back"),
                        50, FlipCroesusCases.item("minecraft:book", "§aManage Orders")),
                Map.of(11, FlipCroesusCases.open(product), 50, FlipCroesusCases.open(orders)));
        return search;
    }

    /** Buy Order's click when Hypixel takes the order: the success line and the page closes; then {@code thenBz}. */
    static JsonObject placed(int n, JsonObject thenBz) {
        JsonObject a = FlipCroesusCases.closeWith(String.format("[Bazaar] Buy Order Setup! %dx Lump of Magma for %,.1f coins.",
                n, n * 35.1));
        if (thenBz != null) {
            a.add("command", BazaarFlipKit.commandMenu("bz", thenBz));
        }
        return a;
    }

    // ---- 2776 ---------------------------------------------------------------------------------------------------------

    static void advisorEachBoot(Session c) throws Exception {
        if (BazaarFlipCustomCases.cheatOnly(c) != null) {
            return;
        }
        try (AutoCloseable w = world(c, 4, 10, 1)) {
            c.hx().call("menu.onCommand", Hx.args("name", "bz", "menu", bz(BazaarFlipKit.ordersPage(), placed(4, null))));
            c.ctx().runOnClient(mc -> Mod.setField(BazaarFlipKit.ADVISOR, "answeredThisBoot", false));
            String refusal = FlipCroesusCases.start(c);
            c.check(refusal != null && refusal.contains("answer the advisor's questions first"),
                    "not answered this boot, start said: " + refusal);
            c.waitUntil("the advisor's setup to open", mc -> McCompat.screen(mc) != null
                    && McCompat.screen(mc).getClass().getSimpleName().equals("BazaarAdvisorSetupScreen"), 40);
            c.check(!BazaarFlipCustomCases.running(c), "it runs on answers from an earlier boot");
            // "Use last" (the setup's own button handler): answered for this boot, the screen closes.
            c.ctx().runOnClient(mc -> Mod.call(McCompat.screen(mc), "useLast"));
            c.waitUntil("the setup to close", mc -> McCompat.screen(mc) == null, 40);
            boolean answered = c.onClient(mc -> (Boolean) Mod.staticCall(BazaarFlipKit.ADVISOR, "answeredThisBoot"));
            c.check(answered, "Use last did not count as answered");
            c.ctx().waitTicks(10);
            c.check(c.commands().stream().noneMatch(x -> x.startsWith("bz")), "sent before it was answered: " + c.commands());
            String again = FlipCroesusCases.start(c);
            c.check(again == null, "answered, start still refused: " + again);
            c.check(BazaarFlipCustomCases.running(c), "answered, but not running");
            c.waitUntil("the autopilot to read Manage Orders", mc -> c.commands().contains("bz"), 200);
            c.ctx().runOnClient(mc -> Mod.staticCall("bazaarflip.BazaarFlipFeature", "requestStop"));
            c.note("not answered this boot: refused (\"" + refusal + "\") with the setup open; Use last -> started, /bz sent");
        }
    }

    // ---- 2777 / 2778 --------------------------------------------------------------------------------------------------

    static void buyOrder(Session c, int n, boolean collect) throws Exception {
        if (BazaarFlipCustomCases.cheatOnly(c) != null) {
            return;
        }
        try (AutoCloseable w = world(c, n, 10, 1)) {
            JsonObject after;
            if (collect) {
                // After the order: Manage Orders lists it filled; claiming it gives the units and empties the list.
                JsonObject claim = new JsonObject();
                JsonObject unit = BazaarFlipCustomCases.unit(MAGMA);
                unit.addProperty("count", n);
                claim.add("give", unit);
                JsonObject set = new JsonObject();
                JsonObject filler = new JsonObject();
                filler.addProperty("filler", true);
                set.add("10", filler);
                claim.add("set", set);
                claim.addProperty("chat", String.format("[Bazaar] Claimed %dx Lump of Magma worth %,.1f coins bought for 35.1 each!",
                        n, n * 35.1));
                claim.add("command", BazaarFlipKit.commandMenu("bz", bz(BazaarFlipKit.ordersPage(), placed(n, null))));
                after = bz(BazaarFlipKit.ordersPage(Map.of(10, filledOrder(n)), Map.of(10, claim)), placed(n, null));
            } else {
                after = null;
            }
            c.hx().call("menu.onCommand", Hx.args("name", "bz", "menu", bz(BazaarFlipKit.ordersPage(), placed(n, after))));
            c.hx().call("menu.onCommand", Hx.args("name", "trades", "menu", BazaarFlipCustomCases.trades(NPC, 0, null)));
            int cyclesBefore = BazaarFlipCustomCases.profitCycles(c);
            String refusal = FlipCroesusCases.start(c);
            c.check(refusal == null, "start refused: " + refusal);
            BazaarFlipCustomCases.awaitEnd(c, 1200);
            String reason = BazaarFlipCustomCases.stopReason(c);
            int preset = n == 1 ? 10 : 12;
            List<Integer> amountClicks = FlipCroesusCases.clicksOn(c, AMOUNT_TITLE);
            c.check(amountClicks.equals(List.of(preset)), "\"" + AMOUNT_TITLE + "\" clicks " + amountClicks + " (want "
                    + (n == 1 ? "Buy only one!, 10" : "Buy 4!, 12") + "); stop: " + reason + "; all " + FlipCroesusCases.allClicks(c));
            c.check(FlipCroesusCases.clicksOn(c, MAGMA.productTitle()).equals(List.of(15)),
                    "product clicks " + FlipCroesusCases.clicksOn(c, MAGMA.productTitle()));
            c.check(FlipCroesusCases.clicksOn(c, PRICE_TITLE).equals(List.of(12)), "price clicks "
                    + FlipCroesusCases.clicksOn(c, PRICE_TITLE) + " (want Top Order +0.1)");
            c.check(FlipCroesusCases.clicksOn(c, CONFIRM_TITLE).equals(List.of(13)), "confirm clicks "
                    + FlipCroesusCases.clicksOn(c, CONFIRM_TITLE));
            c.check(c.events("sign.opened").isEmpty(), "used Custom Amount for a preset's amount");
            c.check(reason.contains(BazaarFlipCustomCases.CAP_1), "the session ended with: " + reason);
            if (collect) {
                // Manage Orders: read first (empty), read after the order (lists it: adopted), then the claim click.
                c.check(FlipCroesusCases.clicksOn(c, ORDERS_TITLE).equals(List.of(10)), "Manage Orders clicks "
                        + FlipCroesusCases.clicksOn(c, ORDERS_TITLE) + " (want the order claimed once)");
                c.check(BazaarFlipCustomCases.given(c) == n, "claimed " + BazaarFlipCustomCases.given(c));
                c.check(BazaarFlipCustomCases.sold(c) == n, "sold " + BazaarFlipCustomCases.sold(c) + " to the NPC");
                c.check(BazaarFlipCustomCases.profitCycles(c) == cyclesBefore + 1, "profit cycles "
                        + cyclesBefore + " -> " + BazaarFlipCustomCases.profitCycles(c));
                Object cyc = BazaarFlipCustomCases.lastCycle(c);
                double spent = BazaarFlipCustomCases.num(cyc, "spent");
                double received = BazaarFlipCustomCases.num(cyc, "received");
                c.check(Math.abs(spent - n * 35.1) < 1e-6 && received == n * NPC, "profit record spent " + spent
                        + " received " + received + " (want " + n * 35.1 + " / " + n * NPC + ")");
                c.note("buy order of " + n + " via \"Buy " + n + "!\", adopted, claimed " + BazaarFlipCustomCases.given(c)
                        + ", sold " + BazaarFlipCustomCases.sold(c) + " to the NPC; record " + spent + " -> " + received
                        + "; clicks " + FlipCroesusCases.allClicks(c));
            } else {
                c.check(BazaarFlipCustomCases.given(c) == 0, "items arrived without a claim");
                c.note("buy order of 1 via \"Buy only one!\": clicks " + FlipCroesusCases.allClicks(c) + "; ended: " + reason);
            }
        }
    }

    // ---- 2779 ---------------------------------------------------------------------------------------------------------

    static void cooldown(Session c) throws Exception {
        if (BazaarFlipCustomCases.cheatOnly(c) != null) {
            return;
        }
        LogTap.install();
        try (AutoCloseable w = world(c, 4, 10, 1)) {
            // The first Buy Order click is refused with Hypixel's cooldown line; the page closes as Hypixel's does.
            JsonObject refused = FlipCroesusCases.closeWith(COOLDOWN);
            c.hx().call("menu.onCommand", Hx.args("name", "bz", "menu", bz(BazaarFlipKit.ordersPage(), refused)));
            long mark = LogTap.mark();
            String refusal = FlipCroesusCases.start(c);
            c.check(refusal == null, "start refused: " + refusal);
            c.waitUntil("the autopilot to pause on the cooldown", mc -> LogTap.since(mark).stream()
                    .anyMatch(l -> l.contains("Autopilot paused by Hypixel's order cooldown"))
                    || !(Boolean) Mod.staticCall("bazaarflip.BazaarFlipFeature", "isRunning"), 600);
            c.check(BazaarFlipCustomCases.running(c), "the cooldown stopped the session: " + BazaarFlipCustomCases.stopReason(c));
            String paused = LogTap.since(mark).stream().filter(l -> l.contains("Autopilot paused by Hypixel's order cooldown"))
                    .findFirst().orElse("");
            c.check(paused.contains("for 60 s") && paused.contains("refused, not counted"), "pause line: " + paused);
            long left = c.onClient(mc -> ((Number) Mod.field(BazaarFlipKit.AUTOPILOT, "cooldownUntilMs")).longValue()
                    - System.currentTimeMillis());
            c.check(left > 50_000 && left <= 60_000, "paused for " + left + " ms more (want up to 60 s from the line)");
            c.check(BazaarFlipKit.placed(c) == 0, "the refused order was counted: placed " + BazaarFlipKit.placed(c));
            c.check(FlipCroesusCases.clicksOn(c, CONFIRM_TITLE).equals(List.of(13)), "confirm clicks "
                    + FlipCroesusCases.clicksOn(c, CONFIRM_TITLE));
            // Paused: nothing is sent or clicked, and it stays running.
            int clicks = c.events("menu.click").size();
            int cmds = c.commands().size();
            c.ctx().waitTicks(100);
            c.check(BazaarFlipCustomCases.running(c), "stopped during the pause: " + BazaarFlipCustomCases.stopReason(c));
            c.check(c.events("menu.click").size() == clicks && c.commands().size() == cmds, "acted during the pause: "
                    + FlipCroesusCases.allClicks(c) + " " + c.commands());
            // Now Hypixel takes orders again. Rather than wait out the minute, end the pause a moment from now: the
            // autopilot carries on and places the order - which it could not if the refused one had used its cap of 1.
            c.hx().call("menu.onCommand", Hx.args("name", "bz", "menu", bz(BazaarFlipKit.ordersPage(), placed(4, null))));
            c.ctx().runOnClient(mc -> Mod.setField(BazaarFlipKit.AUTOPILOT, "cooldownUntilMs", System.currentTimeMillis() + 500L));
            c.waitUntil("the autopilot to place the order after the pause", mc -> FlipCroesusCases.clicksOn(c, CONFIRM_TITLE)
                    .size() >= 2 || !(Boolean) Mod.staticCall("bazaarflip.BazaarFlipFeature", "isRunning"), 600);
            BazaarFlipCustomCases.awaitEnd(c, 400);
            String reason = BazaarFlipCustomCases.stopReason(c);
            c.check(FlipCroesusCases.clicksOn(c, CONFIRM_TITLE).equals(List.of(13, 13)), "confirm clicks "
                    + FlipCroesusCases.clicksOn(c, CONFIRM_TITLE) + "; stop: " + reason);
            c.check(LogTap.since(mark).stream().anyMatch(l -> l.contains("Autopilot order cooldown over - carrying on")),
                    "no \"cooldown over\" line");
            c.check(reason.contains(BazaarFlipCustomCases.CAP_1), "the session ended with: " + reason);
            c.note("cooldown after Confirm Buy Order: \"" + paused.replaceAll(".*\\[BazaarFlip\\] ", "") + "\", " + left
                    + " ms left, nothing sent for 100 ticks; after the pause the order went in; ended: " + reason);
        }
    }
}
