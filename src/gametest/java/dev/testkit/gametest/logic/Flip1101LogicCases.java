package dev.testkit.gametest.logic;

import dev.testkit.gametest.mod.Mod;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 2797-logic-flip-1101 (doc-flip-1101, to-do Doc 2026-11-01 "BAZAAR FLIPPER" and "AH FLIPPER"): the pure pieces.
 * <ul>
 *   <li>Bazaar stack sizes: enchanted books do not stack; one order's limit is 256 for an unstackable item and 71,680
 *       otherwise (wiki, Bazaar); a live "Fill my inventory!" no bigger than the free slots teaches "does not stack".</li>
 *   <li>Hypixel's "You reached your maximum of 21 Bazaar orders!" (his Dungeons log, 2026-10-30) and a forgery.</li>
 *   <li>Min Profit Per Cycle's slider stops.</li>
 *   <li>AH fees (wiki, Auction House: BIN fee 1% / 2% / 2.5%, 1% collection tax above 1M, never under 1M), the relist
 *       price, and a flip judged against the cheapest OTHER BIN with Min Listings.</li>
 *   <li>"BIN Auction started for NAME!" and "You purchased NAME for N coins!" (his logs) against forgeries.</li>
 * </ul>
 */
final class Flip1101LogicCases {

    private Flip1101LogicCases() {
    }

    static Object record(String cls, Object... args) {
        try {
            return Mod.cls(cls).getDeclaredConstructors()[0].newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    static void run(LogicCase c) {
        if (!Mod.has("bazaarflip.BazaarStackSizes")) {
            c.skip("flip-1101", "mod before doc-flip-1101");
            return;
        }
        String ss = "bazaarflip.BazaarStackSizes";
        Mod.staticCall(ss, "clearLearnedForTest");
        c.eq("enchanted book stacks to 1", 1, Mod.staticCall(ss, "of", "ENCHANTMENT_SHARPNESS_6"));
        c.eq("order limit, unstackable", 256L, Mod.staticCall(ss, "orderLimit", 1));
        c.eq("order limit, stackable", 71_680L, Mod.staticCall(ss, "orderLimit", 64));
        c.eq("unknown item stacks to 64", 64, Mod.staticCall(ss, "of", "TESTKIT_NO_SUCH_ITEM"));
        c.eq("fill of 20 with 30 free slots: does not stack", 1,
                Mod.staticCall(ss, "learn", "TESTKIT_FILL_ONE", 20L, 30));
        c.eq("remembered", 1, Mod.staticCall(ss, "of", "TESTKIT_FILL_ONE"));
        c.eq("fill of 1,920 with 30 free slots: stacks", 64,
                Mod.staticCall(ss, "learn", "TESTKIT_FILL_MANY", 1_920L, 30));
        Mod.staticCall(ss, "clearLearnedForTest");

        Pattern maxOrders = Mod.pattern("bazaarflip.BazaarFlipAutopilot", "MAX_ORDERS_LINE");
        c.check("his max orders line", maxOrders.matcher("[Bazaar] You reached your maximum of 21 Bazaar orders!").matches());
        c.check("max orders, forged", !maxOrders.matcher("[VIP] Bob: [Bazaar] You reached your maximum of 21 Bazaar orders!")
                .matches());
        c.check("max orders, unbounded count", !maxOrders.matcher("[Bazaar] You reached your maximum of 99999 Bazaar orders!")
                .matches());

        long[] steps = (long[]) Mod.field("bazaarflip.BazaarFlipConfig", "MIN_PROFIT_STEPS");
        c.eq("min profit: first stop is Off", 0L, steps[0]);
        c.check("min profit: stops ascend", isAscending(steps));

        String ah = "ahflip.AhFlipPricing";
        c.near("BIN fee under 10M", 0.01, (Double) Mod.staticCall(ah, "listingFeeRate", 5_000_000L), 1e-9);
        c.near("BIN fee 10M-100M", 0.02, (Double) Mod.staticCall(ah, "listingFeeRate", 50_000_000L), 1e-9);
        c.near("BIN fee above 100M", 0.025, (Double) Mod.staticCall(ah, "listingFeeRate", 200_000_000L), 1e-9);
        c.eq("no tax at 900k", 0L, Mod.staticCall(ah, "collectTax", 900_000L));
        c.eq("tax never under 1M", 5_000L, Mod.staticCall(ah, "collectTax", 1_005_000L));
        c.eq("tax 1% at 5M", 50_000L, Mod.staticCall(ah, "collectTax", 5_000_000L));
        c.eq("net of a 5M sale", 4_900_000L, Mod.staticCall(ah, "netOfSale", 5_000_000L));
        c.eq("relist 1% under 1M", 990_000L, Mod.staticCall(ah, "relistPrice", 1_000_000L, 10));
        c.eq("relist with no undercut is a coin under", 999_999L, Mod.staticCall(ah, "relistPrice", 1_000_000L, 0));

        UUID lowest = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        Object market = record(ah + "$Market", 2_000_000L, lowest, 2_100_000L, 5);
        Object settings = record(ah + "$Settings", 10, 10, 1_000_000L, 100_000L, 0L, 3);
        Object flip = Mod.staticCall(ah, "evaluate", other, "HYPERION", "Hyperion", 1_000_000L, market, settings, 0L);
        c.check("a half-price BIN is a flip", flip != null);
        if (flip != null) {
            // 2,000,000 less 1% = 1,980,000; less 1% fee (19,800) and 1% tax (19,800) = 1,940,400; less 1,000,000.
            c.eq("sells at", 1_980_000L, Mod.call(flip, "sellAt"));
            c.eq("profit", 940_400L, Mod.call(flip, "profit"));
        }
        Object self = Mod.staticCall(ah, "evaluate", lowest, "HYPERION", "Hyperion", 1_000_000L, market, settings, 0L);
        c.check("the cheapest one judged against the second", self != null
                && ((Number) Mod.call(self, "sellAt")).longValue() == 2_079_000L, String.valueOf(self));
        Object thin = record(ah + "$Market", 2_000_000L, lowest, 2_100_000L, 2);
        c.eq("too few listings", null, Mod.staticCall(ah, "evaluate", other, "X", "X", 1_000_000L, thin, settings, 0L));
        c.eq("dearer than the purse", null,
                Mod.staticCall(ah, "evaluate", other, "X", "X", 1_000_000L, market, settings, 900_000L));
        c.eq("no flip at the market price", null,
                Mod.staticCall(ah, "evaluate", other, "X", "X", 1_950_000L, market, settings, 0L));

        Pattern started = Mod.pattern("ahflip.AhPriceCopier", "STARTED_LINE");
        c.check("started", started.matcher("BIN Auction started for Wither Boots!").matches());
        c.check("started, forged", !started.matcher("[MVP+] Bob: BIN Auction started for Wither Boots!").matches());
        Pattern purchased = Mod.pattern("ahflip.AhFlipMacro", "PURCHASED_LINE");
        c.check("purchased", purchased.matcher("You purchased Travel Scroll to the Dwarven Forge for 450,000 coins!").matches());
        c.check("purchased, forged", !purchased.matcher("Guild > Bob: You purchased Hyperion for 1 coins!").matches());
    }

    static boolean isAscending(long[] a) {
        for (int i = 1; i < a.length; i++) {
            if (a[i] <= a[i - 1]) {
                return false;
            }
        }
        return true;
    }
}
