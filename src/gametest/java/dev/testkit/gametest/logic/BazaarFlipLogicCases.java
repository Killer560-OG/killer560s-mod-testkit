package dev.testkit.gametest.logic;

import dev.testkit.gametest.mod.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 2775-logic-bazaarflip (bazaarflip-custom-1016): the pure pieces behind killer560's 2026-10-16 report - Hypixel cuts
 * long container titles ("Enchanted Pufferfish ➜ Instant", his stop line; "Enchanted Gunpowder ➜ Instant B",
 * " Fine Jasper Gemstone ➜ Instan", both in his Dungeons logs), so the Instant Buy page must classify by any cut of its
 * title and only ever as the product's own; the Bought / You sold / NPC-limit lines; Custom Amount's "Buy up to"; the
 * sign prompt check; the typed Target Purse; and the scan with no floor and the NPC sell limit as a cap.
 */
final class BazaarFlipLogicCases {

    private BazaarFlipLogicCases() {
    }

    static final String BP = "bazaar.BazaarPages";
    static final String FF = "bazaarflip.BazaarFlipFeature";

    static Object item(int slot, String name, String... lore) {
        try {
            return Mod.cls(BP + "$Item").getDeclaredConstructors()[0].newInstance(slot, name, List.of(lore), false);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** killer560's page, by his dump: 10 Buy only one!, 12 Buy a stack!, 14 Fill my inventory!, 16 Custom Amount, 30, 31. */
    static List<Object> hisInstantBuyItems() {
        List<Object> items = new ArrayList<>();
        items.add(item(10, "Buy only one!", "Amount: 1x", "Price: 1,900.0 coins"));
        items.add(item(12, "Buy a stack!", "Amount: 64x", "Price: 121,600 coins"));
        items.add(item(14, "Fill my inventory!", "Amount: 2,304x", "Price: 4,377,600 coins"));
        items.add(item(16, "Custom Amount", "Buy Order Quantity", "Buy up to 71,680x.", "Click to specify!"));
        items.add(item(30, "Go Back"));
        items.add(item(31, "Close"));
        return items;
    }

    static String kind(String title, List<Object> items) {
        Object page = Mod.staticCall(BP, "classify", title, 4, items);
        return String.valueOf(Mod.call(page, "kind"));
    }

    static void run(LogicCase c) {
        List<Object> items = hisInstantBuyItems();
        // Every cut of " ➜ Instant Buy" is the Instant Buy page.
        c.eq("his title", "INSTANT_BUY", kind("Enchanted Pufferfish ➜ Instant", items));
        c.eq("full title", "INSTANT_BUY", kind("Enchanted Pufferfish ➜ Instant Buy", items));
        for (String cut : List.of("Enchanted Gunpowder ➜ Instant B", "Enchanted Gunpowder ➜ Instant Bu",
                "Fine Jasper Gemstone ➜ Instan", "Some Item ➜ Inst", "Some Item ➜ I")) {
            c.eq("cut " + cut, "INSTANT_BUY", kind(cut, items));
        }
        // Cut before the arrow (a 29+ character name): only with the full option set.
        c.eq("cut before the arrow", "INSTANT_BUY", kind("Enchanted Very Long Product Nam", items));
        c.eq("cut at the arrow", "INSTANT_BUY", kind("Enchanted Very Long Product ➜", items));
        List<Object> onlyCustom = List.of(item(16, "Custom Amount"), item(10, "Buy only one!"));
        c.eq("arrowless with one option is not enough", "UNKNOWN", kind("Enchanted Very Long Product Nam", onlyCustom));
        c.eq("short arrowless title", "UNKNOWN", kind("Some Menu", items));
        // A title cut wrongly ("Instant X") or another page is not the Instant Buy page.
        c.eq("not a cut", "UNKNOWN", kind("Enchanted Pufferfish ➜ Instance", items));
        // The product check the flipper makes on the page.
        c.eq("his title is Enchanted Pufferfish's", true,
                Mod.staticCall(BP, "isInstantBuyTitleFor", "Enchanted Pufferfish ➜ Instant", "Enchanted Pufferfish"));
        c.eq("full title is Wheat's", true, Mod.staticCall(BP, "isInstantBuyTitleFor", "Wheat ➜ Instant Buy", "Wheat"));
        c.eq("short cut is not accepted", false, Mod.staticCall(BP, "isInstantBuyTitleFor", "Wheat ➜ Inst", "Wheat"));
        c.eq("another product's page", false,
                Mod.staticCall(BP, "isInstantBuyTitleFor", "Enchanted Pufferfish ➜ Instant", "Enchanted Pumpkin"));
        c.eq("a product whose name starts the same", false,
                Mod.staticCall(BP, "isInstantBuyTitleFor", "Enchanted Puffer ➜ Instant Buy", "Enchanted Pufferfish"));
        c.eq("a cut product name", true, Mod.staticCall(BP, "isInstantBuyTitleFor", "Enchanted Very Long Product Nam",
                "Enchanted Very Long Product Name"));
        c.eq("a cut name of another product", false, Mod.staticCall(BP, "isInstantBuyTitleFor",
                "Enchanted Very Long Product Nam", "Enchanted Very Long Product Nose"));
        // The other Bazaar titles are short enough not to be cut, or match any cut already.
        c.eq("cut product page title", "PRODUCT", kind("EXP Bottles ➜ Titanic Experienc", List.of(
                item(10, "Buy Instantly"), item(11, "Sell Instantly"), item(13, "Titanic Experience Bottle"),
                item(15, "Create Buy Order"), item(16, "Create Sell Offer"))));

        // Chat lines (real ones from his logs), and forgeries.
        Pattern bought = Mod.pattern(FF, "BOUGHT_LINE");
        c.check("bought, custom amount", bought.matcher("[Bazaar] Bought 238x Enchanted Pumpkin for 401,411 coins!").matches());
        c.check("bought, decimals", bought.matcher("[Bazaar] Bought 16x Ender Pearl for 112.0 coins!").matches());
        c.check("bought, forged", !bought.matcher("[VIP] Bob: [Bazaar] Bought 16x Ender Pearl for 112.0 coins!").matches());
        Pattern sold = Mod.pattern("bazaarflip.BazaarFlipNpcLimit", "SOLD_LINE");
        c.check("sold", sold.matcher("You sold Box of Seeds x62 for 4,761,600 Coins!").matches());
        c.check("sold one coin", sold.matcher("You sold Oak Sign x1 for 1 Coin!").matches());
        c.check("sold, forged", !sold.matcher("Guild > Bob: You sold Box of Seeds x62 for 4,761,600 Coins!").matches());
        Pattern limit = Mod.pattern("bazaarflip.BazaarFlipNpcLimit", "LIMIT_LINE");
        c.check("limit line", limit.matcher(
                "You have reached the daily limit of coins you can earn by selling items to NPCs!").matches());
        c.check("limit, player", !limit.matcher("[MVP+] Bob: daily selling limit reached lol").matches());
        c.check("limit, party", !limit.matcher("Party > [VIP] Bob: you hit the daily sell limit").matches());
        c.check("limit, an ordinary sale", !limit.matcher("You sold Box of Seeds x62 for 4,761,600 Coins!").matches());
        c.eq("Buy up to", 71_680L, Mod.staticCall(FF, "loreBuyUpTo", List.of("Buy Order Quantity", "Buy up to 71,680x.")));
        c.eq("no Buy up to", -1L, Mod.staticCall(FF, "loreBuyUpTo", List.of("Click to specify!")));
        c.eq("his sign", true, Mod.staticCall(FF, "isAmountSign",
                (Object) new String[]{"", "^^^^^^^^^^^^^^^", "Enter amount", "to order"}));
        c.eq("another sign", false, Mod.staticCall(FF, "isAmountSign",
                (Object) new String[]{"", "^^^^^^^^^^^^^^^", "Enter query", ""}));

        // Target Purse text.
        String cfg = "bazaarflip.BazaarFlipConfig";
        Object[][] coins = {{"5,000,000", 5_000_000L}, {"750k", 750_000L}, {"2.5m", 2_500_000L}, {"1b", 1_000_000_000L},
                {"1.25B", 1_250_000_000L}, {"", 0L}, {"0", 0L}, {"  42 ", 42L}, {"abc", null}, {"-5", null}, {"5mm", null},
                {"9999999999b", 1_000_000_000_000L}};
        for (Object[] t : coins) {
            c.eq("parseCoins \"" + t[0] + "\"", t[1], Mod.staticCall(cfg, "parseCoins", t[0]));
        }

        // The scan: no floor (any profit after fees), and never past the NPC sell limit.
        String bz = "bazaarflip.BazaarFlipScanner";
        Object tiny = Mod.staticCall(bz, "size", "P", "P", DomainCases.book(100, 10), 101.0, 1e9, 64);
        c.check("a 10-coin flip is a candidate", tiny != null && ((Number) Mod.call(tiny, "profit")).doubleValue() == 10.0);
        Object capped = Mod.staticCall(bz, "size", "P", "P", DomainCases.book(100, 1000), 120.0, 1e9, 64, 120.0 * 25 + 5);
        c.eq("capped by the NPC limit", 25, Mod.call(capped, "units"));
        c.eq("no room on the NPC limit", null, Mod.staticCall(bz, "size", "P", "P", DomainCases.book(100, 10), 120.0, 1e9,
                64, 119.0));
        c.eq("instant buy tax", 0.0, Mod.field(bz, "INSTANT_BUY_TAX"));
    }
}
