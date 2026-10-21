package dev.testkit.gametest.menu;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Hx;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 2761-2774 (bazaarflip-custom-1016): killer560's 2026-10-16 report - "[Bazaar Flip] Stopped: unexpected menu
 * "Enchanted Pufferfish ➜ Instant" [... 10=Buy only one!, 12=Buy a stack! x64, 14=Fill my inventory!, 16=Custom Amount,
 * 30=Go Back, 31=Close ...]" and "it needs to click custom amount then paste in the amount" - plus the same day's tab
 * and money changes. Every buy is judged by what the SERVER recorded (menu.click, sign.update and its lines, menu.give,
 * sell.sold), never by what the mod meant to do.
 *
 * <p>Since mod de5cb49c (2026-10-20) the cheat build always runs the AUTOPILOT, which decides from Bazaar API prices,
 * the advisor's answers and NPC prices ({@link BazaarFlipKit} feeds all three) and reads Manage Orders before anything
 * else. With the advisor's Insta Buy / NPC answers its flip is the old cycle's; each case keeps its purpose:
 * <ul>
 *   <li>2761 his exact cut title and slot layout: Custom Amount, the sign gets "100", Done is sent, 100 arrive and are
 *       sold, and the profit tracker gets the sale (cost within a coin of the Bought line, NPC price per unit sold).
 *       On a jar before the fix this stops at "unexpected menu" with nothing bought.</li>
 *   <li>2762 the same through a Confirm Instant Buy page.</li>
 *   <li>2763 Custom Amount that never opens a sign: stops, nothing bought.</li>
 *   <li>2764 "Buy up to 40x.": 100 offered, one Insta Buy types 40 (capped by that line), and sells them.</li>
 *   <li>2765 Hypixel rejecting the typed amount: stops (nothing arrived), no /trades.</li>
 *   <li>2766 a key in a Bazaar container mid-run (reskin off) stops it and names the key.</li>
 *   <li>2767 a key while the amount sign is open stops it; the sign is never sent with a number.</li>
 *   <li>2768 the NPC sell limit line mid-sale: only the sold part is booked, the rest is kept, today's limit is marked
 *       and the session waits (the autopilot stops selling to the NPC, not the session).</li>
 *   <li>2769 near the limit: the buy is sized down to what the NPCs will still pay for (Buy a stack!, exactly 64).</li>
 *   <li>2770 the profit tracker's Bazaar Flipper entry exists on cheat jars only.</li>
 *   <li>2771 the tab: removed rows gone, Target Purse typed (commas, k/m/b, nonsense, empty), saved and reloaded; an
 *       old file's minProfitPerRun ignored.</li>
 *   <li>2772 nothing profitable: after reading Manage Orders it waits, never opening the product or /trades.</li>
 *   <li>2773 a live page dearer than the API: that product is skipped for the session, the next best flipped twice.</li>
 *   <li>2774 a new session starts from empty books (what the last one skipped is tried again); the old scanner's test
 *       hook still refuses a JVM that is not a harness.</li>
 * </ul>
 */
final class BazaarFlipCustomCases {

    private BazaarFlipCustomCases() {
    }

    /** {@code TESTKIT_BZFLIP_REPEAT=N} registers the whole set N times in one session (rounds 2.. get "-rN"), to tell
     *  an ordering flake from a bug. */
    static void register(Session s) {
        int rounds = 1;
        try {
            rounds = Math.max(1, Integer.parseInt(System.getenv().getOrDefault("TESTKIT_BZFLIP_REPEAT", "1").trim()));
        } catch (NumberFormatException ignored) {
        }
        for (int r = 1; r <= rounds; r++) {
            registerRound(s, r == 1 ? "" : "-r" + r);
        }
    }

    static void registerRound(Session s, String sfx) {
        MenuSuite.test(s, "2761-menu-bzflip-cut-title-custom" + sfx, c -> customCycle(c, false));
        MenuSuite.test(s, "2762-menu-bzflip-custom-confirm" + sfx, c -> customCycle(c, true));
        MenuSuite.test(s, "2763-menu-bzflip-sign-never-opens" + sfx, BazaarFlipCustomCases::signNeverOpens);
        MenuSuite.test(s, "2764-menu-bzflip-buy-up-to" + sfx, BazaarFlipCustomCases::buyUpTo);
        MenuSuite.test(s, "2765-menu-bzflip-amount-rejected" + sfx, BazaarFlipCustomCases::amountRejected);
        MenuSuite.test(s, "2766-menu-bzflip-key-in-container" + sfx, BazaarFlipCustomCases::keyInContainer);
        MenuSuite.test(s, "2767-menu-bzflip-key-in-sign" + sfx, BazaarFlipCustomCases::keyInSign);
        MenuSuite.test(s, "2768-menu-bzflip-npc-limit-midcycle" + sfx, BazaarFlipCustomCases::npcLimitMidCycle);
        MenuSuite.test(s, "2769-menu-bzflip-npc-near-limit" + sfx, BazaarFlipCustomCases::npcNearLimit);
        MenuSuite.test(s, "2770-menu-bzflip-profit-variant" + sfx, BazaarFlipCustomCases::profitVariant);
        MenuSuite.test(s, "2771-menu-bzflip-tab-target-purse" + sfx, BazaarFlipCustomCases::tabTargetPurse);
        MenuSuite.test(s, "2772-menu-bzflip-nothing-profitable" + sfx, BazaarFlipCustomCases::nothingProfitable);
        MenuSuite.test(s, "2773-menu-bzflip-live-price-next-best" + sfx, BazaarFlipCustomCases::livePriceNextBest);
        MenuSuite.test(s, "2774-menu-bzflip-no-reuse" + sfx, BazaarFlipCustomCases::noReuse);
    }

    // ---- the product and its Bazaar --------------------------------------------------------------------------

    /** One Bazaar product for a case. */
    record Product(String id, String name, String item, String group) {
        String stack() {
            return "minecraft:" + item + "[minecraft:custom_data={id:\"" + id + "\"}]";
        }

        String instantTitle() {
            return name + " ➜ Instant Buy";
        }

        String productTitle() {
            return group + " ➜ " + name;
        }

        String searchTitle() {
            return "Bazaar ➜ \"" + name + "\"";
        }
    }

    /** killer560's product (2026-10-16): its Instant Buy title is cut to "Enchanted Pufferfish ➜ Instant". */
    static final Product PUFFER = new Product("ENCHANTED_PUFFERFISH", "Enchanted Pufferfish", "pufferfish", "Fishing");
    static final String PUFFER_CUT = "Enchanted Pufferfish ➜ Instant";
    static final Product MAGMA = new Product("LUMP_OF_MAGMA", "Lump of Magma", "magma_cream", "Mining");
    static final String[] SIGN_LINES = {"", "^^^^^^^^^^^^^^^", "Enter amount", "to order"};

    /** What Custom Amount does on the server side. */
    record Sign(boolean none, boolean confirm, long max) {
        static Sign normal() {
            return new Sign(false, false, 71_680);
        }
    }

    static JsonObject unit(Product p) {
        return FlipCroesusCases.item(p.stack(), "§a" + p.name());
    }

    /**
     * The Instant Buy page, laid out as killer560's dump: 10 Buy only one!, 12 Buy a stack! (x64), 14 Fill my
     * inventory!, 16 Custom Amount, 30 Go Back, 31 Close; 4 rows. {@code depth} more copies of itself are re-opened after
     * each Custom Amount purchase, so a case can buy several times.
     */
    static JsonObject instant(Product p, String title, double each, String upTo, Sign sign, int depth) {
        JsonObject one = new JsonObject();
        one.add("give", unit(p));
        one.addProperty("chat", String.format("[Bazaar] Bought 1x %s for %,.0f coins!", p.name(), each));
        JsonObject stack = new JsonObject();
        JsonObject s64 = unit(p);
        s64.addProperty("count", 64);
        stack.add("give", s64);
        stack.addProperty("chat", String.format("[Bazaar] Bought 64x %s for %,.0f coins!", p.name(), 64 * each));
        JsonObject customSpec = new JsonObject();
        if (sign.none()) {
            customSpec.addProperty("none", true);
        } else {
            JsonArray lines = new JsonArray();
            for (String l : SIGN_LINES) {
                lines.add(l);
            }
            customSpec.add("lines", lines);
            JsonObject amount = new JsonObject();
            amount.addProperty("max", sign.max());
            amount.addProperty("each", each);
            amount.add("unit", unit(p));
            amount.addProperty("name", p.name());
            amount.addProperty("stack", 64);
            amount.addProperty("confirm", sign.confirm());
            if (depth > 0) {
                amount.add("reopen", instant(p, title, each, upTo, sign, depth - 1));
            }
            customSpec.add("amount", amount);
        }
        JsonObject custom = new JsonObject();
        custom.add("sign", customSpec);
        JsonObject s12 = FlipCroesusCases.item("minecraft:" + p.item(), "§aBuy a stack!", "§8" + p.name(), "",
                "§7Amount: §a64§7x", String.format("§7Per unit: §6%,.1f coins", FlipCroesusCases.Q * each), "",
                String.format("§7Price: §6%,.0f coins", FlipCroesusCases.Q * 64 * each), "", "§eClick to buy now!");
        s12.addProperty("count", 64);
        Map<Integer, JsonObject> slots = new LinkedHashMap<>();
        slots.put(10, FlipCroesusCases.item("minecraft:golden_horse_armor", "§aBuy only §eone§a!", "§8" + p.name(), "",
                "§7Amount: §a1§7x", "", String.format("§7Price: §6%,.1f coins", FlipCroesusCases.Q * each), "", "§eClick to buy now!"));
        slots.put(12, s12);
        slots.put(14, FlipCroesusCases.item("minecraft:chest", "§aFill my inventory!", "§8" + p.name(), "",
                "§7Amount: §a2,304§7x", String.format("§7Per unit: §6%,.1f coins", FlipCroesusCases.Q * each), "",
                String.format("§7Price: §6%,.0f coins", FlipCroesusCases.Q * 2304 * each), "", "§eClick to buy now!"));
        // His tooltip: "Custom Amount / Buy Order Quantity / Buy up to 71,680x. / Click to specify!"
        slots.put(16, FlipCroesusCases.item("minecraft:oak_sign", "§aCustom Amount", "§8Buy Order Quantity", "",
                "§7Buy up to §a" + upTo + "§7x§7.", "", "§eClick to specify!"));
        slots.put(30, FlipCroesusCases.item("minecraft:arrow", "§aGo Back", "§7To " + p.name()));
        slots.put(31, FlipCroesusCases.item("minecraft:barrier", "§cClose"));
        Map<Integer, JsonObject> on = new LinkedHashMap<>();
        on.put(10, one);
        on.put(12, stack);
        on.put(14, FlipCroesusCases.closeWith("unexpected: Fill my inventory!"));
        on.put(16, custom);
        return FlipCroesusCases.menu(title, 4, slots, on);
    }

    /** /bz: the search page, the product page, then {@code instant}. */
    static JsonObject bazaar(Product p, JsonObject instant) {
        JsonObject product = FlipCroesusCases.menu(p.productTitle(), 4, Map.of(
                10, FlipCroesusCases.item("minecraft:golden_horse_armor", "§aBuy Instantly"),
                11, FlipCroesusCases.item("minecraft:hopper", "§6Sell Instantly"),
                13, unit(p),
                15, FlipCroesusCases.item("minecraft:map", "§aCreate Buy Order"),
                16, FlipCroesusCases.item("minecraft:map", "§6Create Sell Offer"),
                31, FlipCroesusCases.item("minecraft:arrow", "§aGo Back")),
                Map.of(10, FlipCroesusCases.open(instant)));
        JsonObject result = unit(p);
        JsonArray lore = new JsonArray();
        lore.add("§7Buy price: §634.9 coins");
        lore.add("§7Sell price: §630.1 coins");
        result.add("lore", lore);
        return FlipCroesusCases.menu(p.searchTitle(), 6, Map.of(
                0, FlipCroesusCases.item("minecraft:golden_hoe", "§eFarming"),
                9, FlipCroesusCases.item("minecraft:diamond_pickaxe", "§bMining"),
                11, result,
                45, FlipCroesusCases.item("minecraft:oak_sign", "§aSearch"),
                49, FlipCroesusCases.item("minecraft:arrow", "§aGo Back"),
                50, FlipCroesusCases.item("minecraft:book", "§aManage Orders")),
                Map.of(11, FlipCroesusCases.open(product)));
    }

    /** The NPC: sells at {@code npc} a unit with Hypixel's "You sold" line; {@code limit} coins in all (0 = none). */
    static JsonObject trades(double npc, double limit, String limitChat) {
        JsonObject m = FlipCroesusCases.menu("Trades", 6, Map.of(49, FlipCroesusCases.item("minecraft:barrier", "§cClose")),
                Map.of());
        m.addProperty("sell", true);
        m.addProperty("sellPrice", npc);
        if (limit > 0) {
            m.addProperty("sellLimit", limit);
            m.addProperty("limitChat", limitChat);
        }
        return m;
    }

    /** The Insta Buy product the API reports: {@code units} offered at {@code each}, the NPC paying {@code npc}. */
    static BazaarFlipKit.Product api(Product p, int units, double each, double npc) {
        return BazaarFlipKit.insta(p.id(), p.item().toUpperCase(java.util.Locale.ROOT), units, each, npc);
    }

    /** The flipper on, Hub sidebar with a 1,000,000 purse, a Cookie Buff, and one API product. */
    static AutoCloseable world(Session c, Product p, int units, double each, double npc, double npcSoldToday, int speed,
                               boolean reskin) {
        return world(c, List.of(api(p, units, each, npc)), npcSoldToday, speed, reskin, 1, BazaarFlipKit.Answers.INSTA_TO_NPC);
    }

    /**
     * The flipper on, Hub sidebar with a 1,000,000 purse, a Cookie Buff, and the autopilot (BazaarFlipKit) on
     * {@code products} with {@code answers}. {@code maxOrders} 1: one buy and its sale, then the session ends itself
     * ("the session's order cap (1) is reached").
     */
    static AutoCloseable world(Session c, List<BazaarFlipKit.Product> products, double npcSoldToday, int speed,
                               boolean reskin, int maxOrders, BazaarFlipKit.Answers answers) {
        MenuKit.reset(c);
        // Server-side: units a case left unsold (2768 stops at the NPC limit with a stack) must not be the next case's.
        c.server().command("clear @a");
        c.ctx().waitTicks(5);
        c.hx().sidebar("§e§lSKYBLOCK", "§710/16/26 §8m12AB", "", " §7⏣ §bVillage", "", "Purse: §61,000,000", "",
                "§ewww.hypixel.net");
        c.hx().tab(List.of("§b§lArea: §7Hub"), null, BazaarV3Cases.COOKIE_ON);
        boolean gate = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled"));
        MenuKit.Cfg cfg = new MenuKit.Cfg(c);
        cfg.set("bazaarflip.BazaarFlipConfig", "Enabled", true)
                .set("bazaarflip.BazaarFlipConfig", "Speed", speed)
                .set("bazaarflip.BazaarFlipConfig", "TargetPurse", 0L)
                .set("auction.AuctionConfig", "BazaarEnabled", reskin);
        AutoCloseable kit;
        try {
            kit = BazaarFlipKit.autopilot(c, cfg, answers, maxOrders, products.toArray(new BazaarFlipKit.Product[0]));
        } catch (RuntimeException | AssertionError e) {
            cfg.close();
            throw e;
        }
        c.ctx().runOnClient(mc -> {
            Mod.staticCall("util.SkyblockGate", "setEnabled", false);
            if (Mod.has("bazaarflip.BazaarFlipNpcLimit")) {
                Mod.staticCall("bazaarflip.BazaarFlipNpcLimit", "setSoldTodayForTest", npcSoldToday);
            }
        });
        c.ctx().waitTicks(10);
        return () -> {
            c.ctx().runOnClient(mc -> {
                Mod.staticCall("bazaarflip.BazaarFlipFeature", "requestStop");
                if (Mod.has("bazaarflip.BazaarFlipNpcLimit")) {
                    Mod.staticCall("bazaarflip.BazaarFlipNpcLimit", "setSoldTodayForTest", 0.0);
                }
                Mod.staticCall("util.SkyblockGate", "setEnabled", gate);
                if (McCompat.screen(mc) != null) {
                    McCompat.setScreen(mc, null);
                }
            });
            c.hx().call("menu.onCommand", Hx.args("name", "bz", "menu", JsonNull.INSTANCE));
            c.hx().call("menu.onCommand", Hx.args("name", "trades", "menu", JsonNull.INSTANCE));
            MenuKit.reset(c);
            c.hx().sidebarClear();
            c.hx().tab(List.of(), null, "");
            c.server().command("clear @a");
            c.ctx().waitTicks(5);
            kit.close();
            cfg.close();
        };
    }

    /** /bz: the search page (whose Manage Orders opens an empty "Your Bazaar Orders" - the autopilot reads it first),
     *  the product page, then {@code instant}. */
    static JsonObject bz(Product p, JsonObject instant) {
        return BazaarFlipKit.withManageOrders(bazaar(p, instant), BazaarFlipKit.ordersPage());
    }

    /** Waits for the session to end by itself (the order cap, or a stop), up to {@code ticks}. */
    static void awaitEnd(Session c, int ticks) {
        c.waitUntil("the flipper session to end", mc -> !(Boolean) Mod.staticCall("bazaarflip.BazaarFlipFeature",
                "isRunning"), ticks);
    }

    static final String CAP_1 = "the session's order cap (1) is reached";

    static boolean running(Session c) {
        return c.onClient(mc -> (Boolean) Mod.staticCall("bazaarflip.BazaarFlipFeature", "isRunning"));
    }

    static String stopReason(Session c) {
        return c.onClient(mc -> Mod.has("bazaarflip.BazaarFlipNpcLimit")
                ? String.valueOf(Mod.staticCall("bazaarflip.BazaarFlipFeature", "lastStopReason")) : "?");
    }

    static int given(Session c) {
        return c.events("menu.give").stream().mapToInt(e -> e.get("count").getAsInt()).sum();
    }

    static int sold(Session c) {
        return c.events("sell.sold").stream().mapToInt(e -> e.get("count").getAsInt()).sum();
    }

    /** Line 1 of every sign update the server received. */
    static List<String> signAmounts(Session c) {
        List<String> out = new ArrayList<>();
        for (JsonObject e : c.events("sign.update")) {
            out.add(e.getAsJsonArray("lines").get(0).getAsString());
        }
        return out;
    }

    /** Profit-log cycles recorded so far (every scope). */
    static int profitCycles(Session c) {
        return c.onClient(mc -> ((List<?>) Mod.staticCall("bazaarflip.BazaarFlipProfitLog", "cycles", (Object) null)).size());
    }

    static Object lastCycle(Session c) {
        return c.onClient(mc -> {
            List<?> all = (List<?>) Mod.staticCall("bazaarflip.BazaarFlipProfitLog", "cycles", (Object) null);
            return all.isEmpty() ? null : all.get(all.size() - 1);
        });
    }

    static double num(Object record, String accessor) {
        return ((Number) Mod.call(record, accessor)).doubleValue();
    }

    static String cheatOnly(Session c) {
        if (!Mod.isCheat()) {
            c.note("legit jar: no flipper (761 checks it never starts)");
            return "legit";
        }
        return null;
    }

    // ---- 2761 / 2762 ------------------------------------------------------------------------------------------

    static void customCycle(Session c, boolean confirm) throws Exception {
        if (cheatOnly(c) != null) {
            return;
        }
        double each = 1_900.0;
        double npc = 2_400.0;
        int units = 100;
        try (AutoCloseable w = world(c, PUFFER, units, each, npc, 0, 10, false)) {
            JsonObject page = instant(PUFFER, PUFFER_CUT, each, "71,680", new Sign(false, confirm, 71_680), 0);
            c.hx().call("menu.onCommand", Hx.args("name", "bz", "menu", bz(PUFFER, page)));
            c.hx().call("menu.onCommand", Hx.args("name", "trades", "menu", trades(npc, 0, null)));
            int cyclesBefore = Mod.has("bazaarflip.BazaarFlipProfitLog") ? profitCycles(c) : 0;
            String refusal = FlipCroesusCases.start(c);
            c.check(refusal == null, "start refused: " + refusal);
            java.util.Set<String> screens = new java.util.LinkedHashSet<>();
            c.waitUntil("the autopilot to buy, sell and end on its order cap", mc -> {
                screens.add(McCompat.screen(mc) == null ? "none" : McCompat.screen(mc).getClass().getSimpleName());
                return !(Boolean) Mod.staticCall("bazaarflip.BazaarFlipFeature", "isRunning");
            }, 900);
            c.note("screens seen during the run: " + screens);
            String reason = stopReason(c);
            c.check(reason.contains(CAP_1), "the session ended with: " + reason + "; all " + FlipCroesusCases.allClicks(c));
            // Manage Orders (50) is read before anything else, then the product (11).
            c.check(FlipCroesusCases.clicksOn(c, PUFFER.searchTitle()).equals(List.of(50, 11)),
                    "search page clicks " + FlipCroesusCases.clicksOn(c, PUFFER.searchTitle()));
            List<Integer> instantClicks = FlipCroesusCases.clicksOn(c, PUFFER_CUT);
            c.check(FlipCroesusCases.clicksOn(c, PUFFER.productTitle()).equals(List.of(10)),
                    "product clicks " + FlipCroesusCases.clicksOn(c, PUFFER.productTitle()) + "; all " + FlipCroesusCases.allClicks(c));
            c.check(!instantClicks.isEmpty(), "nothing clicked on \"" + PUFFER_CUT + "\" (stop: " + reason + "); all "
                    + FlipCroesusCases.allClicks(c));
            c.check(instantClicks.equals(List.of(16)), "Instant Buy clicks " + instantClicks + " (want Custom Amount, 16, once)");
            c.check(c.events("sign.opened").size() == 1, "sign prompts opened: " + c.events("sign.opened").size());
            List<String> amounts = signAmounts(c);
            c.check(amounts.equals(List.of("100")), "sign updates the server received, line 1: " + amounts);
            JsonArray lines = c.events("sign.update").get(0).getAsJsonArray("lines");
            c.check(lines.get(1).getAsString().equals(SIGN_LINES[1]) && lines.get(2).getAsString().equals(SIGN_LINES[2])
                    && lines.get(3).getAsString().equals(SIGN_LINES[3]), "the other lines came back changed: " + lines);
            if (confirm) {
                c.check(FlipCroesusCases.clicksOn(c, "Confirm Instant Buy").equals(List.of(13)),
                        "Confirm Instant Buy clicks " + FlipCroesusCases.clicksOn(c, "Confirm Instant Buy"));
            }
            c.check(given(c) == units, "the server delivered " + given(c));
            c.check(sold(c) == units, "sold " + sold(c));
            c.check(c.commands().contains("trades"), "no /trades: " + c.commands());
            // The profit tracker: one sale. The autopilot books the cost from the Bought line (or, when the items beat the
            // line in, the page's quote less Hypixel's 4%, which is within a coin of it) and the NPC price per unit sold.
            c.check(profitCycles(c) == cyclesBefore + 1, "profit cycles " + cyclesBefore + " -> " + profitCycles(c));
            Object cyc = lastCycle(c);
            double spent = num(cyc, "spent");
            double received = num(cyc, "received");
            double net = num(cyc, "net");
            double purseChange = units * npc - units * each;
            c.check(Math.abs(spent - units * each) <= 1.0, "spent " + spent + ", the Bought line said " + units * each);
            c.check(received == units * npc, "received " + received + ", the You sold lines said " + units * npc);
            c.check(num(cyc, "tax") == 0.0, "tax " + num(cyc, "tax"));
            c.check(Math.abs(net - purseChange) <= 1.0, "net " + net + " vs the purse change " + purseChange);
            c.check(((Number) Mod.call(cyc, "sold")).intValue() == units, "sold in the record " + Mod.call(cyc, "sold"));
            c.note("\"" + PUFFER_CUT + "\": clicks " + FlipCroesusCases.allClicks(c) + "sign line 1 " + amounts
                    + ", delivered " + given(c) + ", sold " + sold(c) + "; profit cycle spent " + spent + " received "
                    + received + " net " + net + (confirm ? " (through Confirm Instant Buy)" : ""));
        }
    }

    // ---- 2763 ---------------------------------------------------------------------------------------------------

    static void signNeverOpens(Session c) throws Exception {
        if (cheatOnly(c) != null) {
            return;
        }
        try (AutoCloseable w = world(c, PUFFER, 100, 1_900.0, 2_400.0, 0, 10, false)) {
            JsonObject page = instant(PUFFER, PUFFER_CUT, 1_900.0, "71,680", new Sign(true, false, 0), 0);
            c.hx().call("menu.onCommand", Hx.args("name", "bz", "menu", bz(PUFFER, page)));
            String refusal = FlipCroesusCases.start(c);
            c.check(refusal == null, "start refused: " + refusal);
            c.waitUntil("the flipper to stop", mc -> !(Boolean) Mod.staticCall("bazaarflip.BazaarFlipFeature", "isRunning"), 400);
            String reason = stopReason(c);
            c.check(reason.contains("sign never opened"), "stopped with: " + reason);
            c.check(FlipCroesusCases.clicksOn(c, PUFFER_CUT).equals(List.of(16)), "clicks " + FlipCroesusCases.allClicks(c));
            c.check(given(c) == 0 && c.events("sign.update").isEmpty(), "bought " + given(c));
            c.check(!c.commands().contains("trades"), "sent /trades");
            c.note("Custom Amount opened no sign: \"" + reason + "\"; clicks " + FlipCroesusCases.allClicks(c));
        }
    }

    // ---- 2764 ---------------------------------------------------------------------------------------------------

    static void buyUpTo(Session c) throws Exception {
        if (cheatOnly(c) != null) {
            return;
        }
        // The API offers 100 under the NPC price; Custom Amount says "Buy up to 40x.": one Insta Buy takes 40 (the autopilot
        // caps a buy by that line; the next decision would buy again), sells them, and the order cap ends the session.
        try (AutoCloseable w = world(c, PUFFER, 100, 1_900.0, 2_400.0, 0, 10, false)) {
            JsonObject page = instant(PUFFER, PUFFER_CUT, 1_900.0, "40", Sign.normal(), 0);
            c.hx().call("menu.onCommand", Hx.args("name", "bz", "menu", bz(PUFFER, page)));
            c.hx().call("menu.onCommand", Hx.args("name", "trades", "menu", trades(2_400.0, 0, null)));
            String refusal = FlipCroesusCases.start(c);
            c.check(refusal == null, "start refused: " + refusal);
            awaitEnd(c, 1200);
            List<String> amounts = signAmounts(c);
            c.check(amounts.equals(List.of("40")), "sign amounts " + amounts + " (Buy up to 40, 100 offered); stop: "
                    + stopReason(c) + "; sign prompts the server opened " + c.events("sign.opened").size() + "; clicks "
                    + FlipCroesusCases.allClicks(c));
            c.check(given(c) == 40 && sold(c) == 40, "delivered " + given(c) + ", sold " + sold(c));
            c.check(stopReason(c).contains(CAP_1), "the session ended with: " + stopReason(c));
            c.note("Buy up to 40x., 100 offered: typed " + amounts + ", delivered " + given(c) + ", sold " + sold(c));
        }
    }

    // ---- 2765 ---------------------------------------------------------------------------------------------------

    static void amountRejected(Session c) throws Exception {
        if (cheatOnly(c) != null) {
            return;
        }
        // The lore says up to 71,680 but the server takes at most 50: Hypixel refusing what was typed.
        try (AutoCloseable w = world(c, PUFFER, 100, 1_900.0, 2_400.0, 0, 10, false)) {
            JsonObject page = instant(PUFFER, PUFFER_CUT, 1_900.0, "71,680", new Sign(false, false, 50), 0);
            c.hx().call("menu.onCommand", Hx.args("name", "bz", "menu", bz(PUFFER, page)));
            String refusal = FlipCroesusCases.start(c);
            c.check(refusal == null, "start refused: " + refusal);
            c.waitUntil("the flipper to stop", mc -> !(Boolean) Mod.staticCall("bazaarflip.BazaarFlipFeature", "isRunning"), 500);
            String reason = stopReason(c);
            c.check(signAmounts(c).equals(List.of("100")), "sign amounts " + signAmounts(c));
            c.check(c.events("sign.rejected").size() == 1, "the server rejected " + c.events("sign.rejected").size());
            // The autopilot's words: it waits for the items, and stops when none arrive.
            c.check(reason.contains("nothing arrived in your inventory"), "stopped with: " + reason);
            c.check(given(c) == 0 && !c.commands().contains("trades"), "delivered " + given(c) + ", commands " + c.commands());
            c.note("amount over the server's limit: typed " + signAmounts(c) + ", rejected, stopped: \"" + reason + "\"");
        }
    }

    // ---- 2766 / 2767 --------------------------------------------------------------------------------------------

    static void keyInContainer(Session c) throws Exception {
        if (cheatOnly(c) != null) {
            return;
        }
        try (AutoCloseable w = world(c, PUFFER, 100, 1_900.0, 2_400.0, 0, 1, false)) { // speed 1: ~1 s per step
            JsonObject page = instant(PUFFER, PUFFER_CUT, 1_900.0, "71,680", Sign.normal(), 0);
            c.hx().call("menu.onCommand", Hx.args("name", "bz", "menu", bz(PUFFER, page)));
            String refusal = FlipCroesusCases.start(c);
            c.check(refusal == null, "start refused: " + refusal);
            c.waitUntil("the product page to be open", mc -> PUFFER.productTitle().equals(MenuKit.screenTitle(mc)), 400);
            int before = c.events("menu.click").size();
            c.ctx().getInput().pressKey(74); // GLFW_KEY_J, inside Hypixel's (un-reskinned) container
            c.waitUntil("the flipper to stop on the key", mc -> !(Boolean) Mod.staticCall("bazaarflip.BazaarFlipFeature",
                    "isRunning"), 20);
            c.ctx().waitTicks(60);
            String reason = stopReason(c);
            c.check(reason.contains("J"), "stop reason does not name the key: " + reason);
            c.check(c.events("menu.click").size() == before, "clicks after the key: " + FlipCroesusCases.allClicks(c));
            c.note("J in the product container: stopped (\"" + reason + "\"), " + before + " click(s) before, none after");
        }
    }

    static void keyInSign(Session c) throws Exception {
        if (cheatOnly(c) != null) {
            return;
        }
        try (AutoCloseable w = world(c, PUFFER, 100, 1_900.0, 2_400.0, 0, 1, false)) {
            JsonObject page = instant(PUFFER, PUFFER_CUT, 1_900.0, "71,680", Sign.normal(), 0);
            c.hx().call("menu.onCommand", Hx.args("name", "bz", "menu", bz(PUFFER, page)));
            String refusal = FlipCroesusCases.start(c);
            c.check(refusal == null, "start refused: " + refusal);
            c.waitUntil("the amount sign to open", mc -> McCompat.screen(mc) instanceof AbstractSignEditScreen, 600);
            c.ctx().getInput().pressKey(74); // J, while the sign editor is up and before the flipper types
            c.waitUntil("the flipper to stop on the key", mc -> !(Boolean) Mod.staticCall("bazaarflip.BazaarFlipFeature",
                    "isRunning"), 20);
            c.ctx().waitTicks(40);
            String reason = stopReason(c);
            boolean signStillOpen = c.onClient(mc -> McCompat.screen(mc) instanceof AbstractSignEditScreen);
            c.check(reason.contains("J"), "stop reason: " + reason);
            c.check(signAmounts(c).isEmpty(), "the sign was sent: " + signAmounts(c));
            c.check(given(c) == 0, "delivered " + given(c));
            // He closes it himself now: whatever goes out holds no number.
            c.ctx().runOnClient(mc -> McCompat.setScreen(mc, null));
            c.ctx().waitTicks(20);
            c.check(signAmounts(c).stream().allMatch(String::isEmpty), "a number was sent: " + signAmounts(c));
            c.note("J in the amount sign: stopped (\"" + reason + "\"), sign left " + (signStillOpen ? "open" : "closed")
                    + " with nothing typed; closing it sent " + signAmounts(c));
        }
    }

    // ---- 2768 / 2769 --------------------------------------------------------------------------------------------

    static final String LIMIT_CHAT = "You have reached the daily limit of coins you can earn by selling items to NPCs!";

    static void npcLimitMidCycle(Session c) throws Exception {
        if (cheatOnly(c) != null) {
            return;
        }
        double each = 35.0;
        double npc = 50.0;
        try (AutoCloseable w = world(c, MAGMA, 128, each, npc, 0, 10, false)) {
            JsonObject page = instant(MAGMA, MAGMA.instantTitle(), each, "71,680", Sign.normal(), 0);
            c.hx().call("menu.onCommand", Hx.args("name", "bz", "menu", bz(MAGMA, page)));
            // The NPC pays for one stack (64 x 50) and then says the limit is reached.
            c.hx().call("menu.onCommand", Hx.args("name", "trades", "menu", trades(npc, 64 * npc, LIMIT_CHAT)));
            int before = profitCycles(c);
            String refusal = FlipCroesusCases.start(c);
            c.check(refusal == null, "start refused: " + refusal);
            // The autopilot books the part the NPC took and keeps the rest: it stops SELLING at the limit, not the session.
            c.waitUntil("the NPC sale to be booked", mc -> ((List<?>) Mod.staticCall("bazaarflip.BazaarFlipProfitLog",
                    "cycles", (Object) null)).size() > before || !(Boolean) Mod.staticCall("bazaarflip.BazaarFlipFeature",
                    "isRunning"), 900);
            c.ctx().waitTicks(20);
            c.check(running(c), "the session ended: " + stopReason(c));
            c.check(given(c) == 128, "delivered " + given(c));
            c.check(sold(c) == 64 && c.events("sell.refused").size() == 1, "sold " + sold(c) + ", refused "
                    + c.events("sell.refused").size());
            c.check(profitCycles(c) == before + 1, "profit cycles " + before + " -> " + profitCycles(c));
            Object cyc = lastCycle(c);
            c.check(num(cyc, "received") == 64 * npc, "received " + num(cyc, "received") + " (only the sold stack pays)");
            c.check(Math.abs(num(cyc, "spent") - 64 * each) <= 1.0, "spent " + num(cyc, "spent") + " (the sold stack's cost)");
            c.check(((Number) Mod.call(cyc, "sold")).intValue() == 64 && ((Number) Mod.call(cyc, "bought")).intValue() == 64,
                    "record sold " + Mod.call(cyc, "sold") + " bought " + Mod.call(cyc, "bought"));
            boolean limitLeft = c.onClient(mc -> ((Number) Mod.staticCall("bazaarflip.BazaarFlipNpcLimit", "remaining"))
                    .doubleValue() <= 0);
            c.check(limitLeft, "the limit line did not mark today's limit reached");
            c.check(BazaarFlipKit.held(c, MAGMA.id()) == 64, "the session holds " + BazaarFlipKit.held(c, MAGMA.id())
                    + " (want the unsold stack)");
            int clicksAfter = c.events("menu.click").size();
            int cmdsAfter = c.commands().size();
            c.ctx().waitTicks(60);
            c.check(c.events("menu.click").size() == clicksAfter && c.commands().size() == cmdsAfter,
                    "acted after the limit: " + FlipCroesusCases.allClicks(c) + " " + c.commands());
            String waiting = BazaarFlipKit.lastWait(c);
            c.check(waiting.contains("nothing else worth doing"), "not waiting: \"" + waiting + "\"");
            c.note("limit after one stack: record received " + num(cyc, "received") + " of spent " + num(cyc, "spent")
                    + ", net " + num(cyc, "net") + "; 64 kept, then \"Waiting: " + waiting + "\"");
        }
    }

    static void npcNearLimit(Session c) throws Exception {
        if (cheatOnly(c) != null) {
            return;
        }
        double npc = 50.0;
        // 64 units (3,200 coins) plus 10 coins left today: the 128 the scan wanted is sized down to one stack.
        double soldToday = 500_000_000.0 - 64 * npc - 10;
        try (AutoCloseable w = world(c, MAGMA, 128, 35.0, npc, soldToday, 10, false)) {
            JsonObject page = instant(MAGMA, MAGMA.instantTitle(), 35.0, "71,680", Sign.normal(), 0);
            c.hx().call("menu.onCommand", Hx.args("name", "bz", "menu", bz(MAGMA, page)));
            c.hx().call("menu.onCommand", Hx.args("name", "trades", "menu", trades(npc, 0, null)));
            String refusal = FlipCroesusCases.start(c);
            c.check(refusal == null, "start refused: " + refusal);
            awaitEnd(c, 900);
            c.check(stopReason(c).contains(CAP_1), "the session ended with: " + stopReason(c));
            List<Integer> clicks = FlipCroesusCases.clicksOn(c, MAGMA.instantTitle());
            c.check(clicks.equals(List.of(12)), "Instant Buy clicks " + clicks + " (want Buy a stack! once: exactly 64)");
            c.check(given(c) == 64 && sold(c) == 64, "delivered " + given(c) + ", sold " + sold(c));
            c.check(c.events("sign.update").isEmpty(), "used Custom Amount for an exact stack");
            c.note("10 + 3,200 coins left on the NPC limit: bought " + given(c) + " (Buy a stack!), sold " + sold(c));
        }
    }

    // ---- 2770 ---------------------------------------------------------------------------------------------------

    static void profitVariant(Session c) {
        @SuppressWarnings("unchecked")
        List<Object> available = c.onClient(mc -> (List<Object>) Mod.staticCall("gui.profit.ProfitTracker", "available"));
        List<String> names = new ArrayList<>();
        available.forEach(t -> names.add(((Enum<?>) t).name()));
        Object byWord = c.onClient(mc -> Mod.staticCall("gui.profit.ProfitTracker", "byWord", "bazaarflip"));
        @SuppressWarnings("unchecked")
        List<String> words = c.onClient(mc -> (List<String>) Mod.staticCall("gui.profit.ProfitTracker", "allWords"));
        if (Mod.isCheat()) {
            c.check(names.contains("BAZAAR_FLIP"), "cheat jar trackers: " + names);
            c.check(byWord != null && ((Enum<?>) byWord).name().equals("BAZAAR_FLIP"), "/profit bazaarflip -> " + byWord);
            Object screen = c.onClient(mc -> Mod.call(byWord, "open", (Object) null));
            c.check(screen.getClass().getSimpleName().equals("BazaarFlipProfitScreen"), "opens " + screen.getClass());
            c.note("cheat jar: trackers " + names + ", /profit bazaarflip opens " + screen.getClass().getSimpleName());
        } else {
            c.check(!names.contains("BAZAAR_FLIP"), "legit jar shows the Bazaar Flipper tracker: " + names);
            c.check(byWord == null, "legit jar: /profit bazaarflip -> " + byWord);
            c.check(words.stream().noneMatch(x -> x.contains("bazaar") || x.contains("flip")), "legit words " + words);
            Object label = c.onClient(mc -> Mod.field(Mod.enumValue("gui.profit.ProfitTracker", "BAZAAR_FLIP"), "label"));
            c.check("".equals(label), "legit jar's BAZAAR_FLIP still carries the label \"" + label + "\"");
            c.note("legit jar: trackers " + names + ", words " + words + ", no Bazaar Flipper (its folded label is \""
                    + label + "\")");
        }
    }

    // ---- 2771 ---------------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static void tabTargetPurse(Session c) throws Exception {
        if (cheatOnly(c) != null) {
            return;
        }
        Path file = c.onClient(mc -> (Path) Mod.staticCall("util.ModPaths", "config", "killer560smod-bazaarflip.json"));
        byte[] was = Files.exists(file) ? Files.readAllBytes(file) : null;
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set("bazaarflip.BazaarFlipConfig", "Enabled", true).set("bazaarflip.BazaarFlipConfig", "TargetPurse", 0L);
            List<AbstractWidget> widgets = c.onClient(mc -> build());
            List<String> labels = labels(widgets);
            for (String gone : List.of("Not running", "Running -", "Any key stops", "Min Profit", "Last scan", "Best:",
                    "Target:")) {
                c.check(labels.stream().noneMatch(l -> l.contains(gone)), "the tab still shows \"" + gone + "\": " + labels);
            }
            c.check(labels.stream().anyMatch(l -> l.startsWith("Target Purse")), "no Target Purse label: " + labels);
            EditBox box = (EditBox) widgets.stream().filter(x -> x instanceof EditBox).findFirst().orElse(null);
            c.check(box != null, "no text box in the tab: " + labels);
            c.check(box.getValue().isEmpty(), "with no target the box shows \"" + box.getValue() + "\"");
            String[][] typed = {
                    {"5,000,000", "5000000"}, {"750k", "750000"}, {"2.5m", "2500000"}, {"1B", "1000000000"},
                    {"12 345", "12345"}, {"abc", "12345"}, {"-5", "12345"}, {"5000b", "1000000000000"}, {"", "0"},
                    {"0", "0"}, {"3m", "3000000"}};
            List<String> seen = new ArrayList<>();
            for (String[] t : typed) {
                String label = c.onClient(mc -> {
                    box.setValue(t[0]);
                    return labels(widgets).stream().filter(l -> l.startsWith("Target Purse")).findFirst().orElse("");
                });
                long got = c.onClient(mc -> ((Number) Mod.get("bazaarflip.BazaarFlipConfig", "getTargetPurse")).longValue());
                seen.add("\"" + t[0] + "\"->" + got + " (" + label + ")");
                c.check(got == Long.parseLong(t[1]), "typed \"" + t[0] + "\": target " + got + ", want " + t[1] + "; " + seen);
                if (t[0].equals("abc")) {
                    c.check(label.contains("not a number"), "typed abc, label " + label);
                }
            }
            // Saved as typed; reload from the file; the box opens with commas.
            c.ctx().runOnClient(mc -> Mod.staticCall("bazaarflip.BazaarFlipConfig", "load"));
            long reloaded = c.onClient(mc -> ((Number) Mod.get("bazaarflip.BazaarFlipConfig", "getTargetPurse")).longValue());
            c.check(reloaded == 3_000_000L, "after reload the target is " + reloaded);
            List<AbstractWidget> again = c.onClient(mc -> build());
            String shown = again.stream().filter(x -> x instanceof EditBox).map(x -> ((EditBox) x).getValue()).findFirst()
                    .orElse("?");
            c.check(shown.equals("3,000,000"), "the rebuilt box shows \"" + shown + "\"");
            // An old file with the removed minProfitPerRun: ignored on load, gone after the next save.
            Files.writeString(file, "{\"enabled\":true,\"minProfitPerRun\":60000,\"targetPurse\":123456,\"speed\":4}",
                    StandardCharsets.UTF_8);
            c.ctx().runOnClient(mc -> Mod.staticCall("bazaarflip.BazaarFlipConfig", "load"));
            long old = c.onClient(mc -> ((Number) Mod.get("bazaarflip.BazaarFlipConfig", "getTargetPurse")).longValue());
            c.check(old == 123_456L, "old file's target read as " + old);
            c.ctx().runOnClient(mc -> Mod.call(Mod.cfg("bazaarflip.BazaarFlipConfig"), "save"));
            String saved = Files.readString(file, StandardCharsets.UTF_8);
            c.check(!saved.contains("minProfitPerRun"), "minProfitPerRun survived a save: " + saved);
            c.note("tab rows " + labels + "; typed " + seen + "; reload 3,000,000 shows \"" + shown
                    + "\"; old minProfitPerRun file loads target " + old + " and saves without it");
        } finally {
            if (was != null) {
                Files.write(file, was);
            } else {
                Files.deleteIfExists(file);
            }
            c.ctx().runOnClient(mc -> Mod.staticCall("bazaarflip.BazaarFlipConfig", "load"));
        }
    }

    @SuppressWarnings("unchecked")
    static List<AbstractWidget> build() {
        try {
            Object tab = Mod.cls("gui.tab.BazaarFlipTab").getDeclaredConstructor().newInstance();
            Runnable none = () -> {
            };
            return (List<AbstractWidget>) Mod.call(tab, "buildWidgets", 0, 0, 320, none);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    static List<String> labels(List<AbstractWidget> widgets) {
        List<String> out = new ArrayList<>();
        for (AbstractWidget w : widgets) {
            if (!(w instanceof EditBox)) {
                out.add(net.minecraft.ChatFormatting.stripFormatting(w.getMessage().getString()));
            }
        }
        return out;
    }

    // ---- 2772 ---------------------------------------------------------------------------------------------------

    static void nothingProfitable(Session c) throws Exception {
        if (cheatOnly(c) != null) {
            return;
        }
        // The NPC limit leaves room for no unit: the API's one product sizes to nothing. The autopilot reads Manage Orders
        // (a plain /bz), finds nothing worth doing and waits - it never opens the product or /trades.
        try (AutoCloseable w = world(c, MAGMA, 128, 35.0, 50.0, 500_000_000.0 - 49, 10, false)) {
            JsonObject page = instant(MAGMA, MAGMA.instantTitle(), 35.0, "71,680", Sign.normal(), 0);
            c.hx().call("menu.onCommand", Hx.args("name", "bz", "menu", bz(MAGMA, page)));
            String refusal = FlipCroesusCases.start(c);
            c.check(refusal == null, "start refused: " + refusal);
            c.waitUntil("the autopilot to wait", mc -> !String.valueOf(Mod.field(BazaarFlipKit.AUTOPILOT, "lastWaitWhy"))
                    .isEmpty() || !(Boolean) Mod.staticCall("bazaarflip.BazaarFlipFeature", "isRunning"), 300);
            c.ctx().waitTicks(40);
            String waiting = BazaarFlipKit.lastWait(c);
            c.check(running(c), "the session ended: " + stopReason(c));
            c.check(waiting.contains("nothing else worth doing"), "waiting: \"" + waiting + "\"");
            c.check(c.commands().stream().noneMatch(x -> x.startsWith("bz ") || x.startsWith("trades")), "sent " + c.commands());
            c.check(FlipCroesusCases.clicksOn(c, MAGMA.searchTitle()).equals(List.of(50)) && c.events("menu.click").size() == 1,
                    "clicks " + FlipCroesusCases.allClicks(c) + " (want Manage Orders only)");
            c.note("no unit sellable today: \"Waiting: " + waiting + "\", commands " + c.commands());
        }
    }

    // ---- 2773 / 2774 (bazaarflip-fresh-1017) ----------------------------------------------------------------------

    static JsonObject productPage(Product p, JsonObject instant) {
        return FlipCroesusCases.menu(p.productTitle(), 4, Map.of(
                10, FlipCroesusCases.item("minecraft:golden_horse_armor", "§aBuy Instantly"),
                11, FlipCroesusCases.item("minecraft:hopper", "§6Sell Instantly"),
                13, unit(p),
                15, FlipCroesusCases.item("minecraft:map", "§aCreate Buy Order"),
                16, FlipCroesusCases.item("minecraft:map", "§6Create Sell Offer"),
                31, FlipCroesusCases.item("minecraft:arrow", "§aGo Back")),
                Map.of(10, FlipCroesusCases.open(instant)));
    }

    static JsonObject priced(Product p) {
        JsonObject result = unit(p);
        JsonArray lore = new JsonArray();
        lore.add("§7Buy price: §634.9 coins");
        lore.add("§7Sell price: §630.1 coins");
        result.add("lore", lore);
        return result;
    }

    /**
     * killer560, 2026-10-17: "It keeps going back to Enchanted Pufferfish then it says Stopped: bought nothing". The API
     * ranks Enchanted Pufferfish first at 1,900 a unit, but its live Instant Buy page prices the book at 2,500 (quoting
     * 2,600) against an NPC 2,400; Lump of Magma is second. The autopilot must leave the pufferfish untouched (skip it for
     * the session), take the Lump of Magma and flip it - and never open the pufferfish again.
     */
    static void livePriceNextBest(Session c) throws Exception {
        if (cheatOnly(c) != null) {
            return;
        }
        // Two orders: the pufferfish is skipped once, then two whole Lump of Magma flips, and the pufferfish is never
        // opened again in the session (it stays skipped at the same price).
        try (AutoCloseable w = world(c, List.of(api(PUFFER, 100, 1_900.0, 2_400.0), api(MAGMA, 128, 35.0, 50.0)), 0, 10,
                false, 2, BazaarFlipKit.Answers.INSTA_TO_NPC)) {
            JsonObject pufferInstant = instant(PUFFER, PUFFER_CUT, 2_500.0, "71,680", Sign.normal(), 0);
            JsonObject magmaInstant = instant(MAGMA, MAGMA.instantTitle(), 35.0, "71,680", Sign.normal(), 0);
            JsonObject search = FlipCroesusCases.menu("Bazaar ➜ \"Enchanted\"", 6, Map.of(
                    11, priced(PUFFER), 12, priced(MAGMA),
                    45, FlipCroesusCases.item("minecraft:oak_sign", "§aSearch"),
                    49, FlipCroesusCases.item("minecraft:arrow", "§aGo Back"),
                    50, FlipCroesusCases.item("minecraft:book", "§aManage Orders")),
                    Map.of(11, FlipCroesusCases.open(productPage(PUFFER, pufferInstant)),
                            12, FlipCroesusCases.open(productPage(MAGMA, magmaInstant))));
            c.hx().call("menu.onCommand", Hx.args("name", "bz", "menu",
                    BazaarFlipKit.withManageOrders(search, BazaarFlipKit.ordersPage())));
            c.hx().call("menu.onCommand", Hx.args("name", "trades", "menu", trades(50.0, 0, null)));
            String refusal = FlipCroesusCases.start(c);
            c.check(refusal == null, "start refused: " + refusal);
            c.waitUntil("the pufferfish to be skipped", mc -> ((java.util.Set<?>) Mod.field(BazaarFlipKit.AUTOPILOT, "avoid"))
                    .contains(PUFFER.id()) || !(Boolean) Mod.staticCall("bazaarflip.BazaarFlipFeature", "isRunning"), 600);
            awaitEnd(c, 1600);
            String reason = stopReason(c);
            c.check(reason.contains("the session's order cap (2) is reached"), "the session ended with: " + reason);
            List<String> cmds = c.commands();
            long pufferBz = cmds.stream().filter(x -> x.equals("bz Enchanted Pufferfish")).count();
            List<Integer> pufferClicks = FlipCroesusCases.clicksOn(c, PUFFER_CUT);
            c.check(pufferClicks.isEmpty(), "bought on the pufferfish page: " + pufferClicks);
            c.check(pufferBz == 1, "/bz Enchanted Pufferfish sent " + pufferBz + " time(s): " + cmds + " (stop: " + reason + ")");
            c.check(cmds.contains("bz Lump of Magma"), "never went to the next best: " + cmds + " (stop: " + reason + ")");
            c.check(signAmounts(c).equals(List.of("128", "128")) && given(c) == 256 && sold(c) == 256,
                    "Lump of Magma: sign " + signAmounts(c) + ", delivered " + given(c) + ", sold " + sold(c));
            c.note("pufferfish live at 2,500 (quoted 2,600) vs NPC 2,400: skipped once, then " + cmds
                    + "; magma sign " + signAmounts(c) + ", sold " + sold(c));
        }
    }

    /**
     * Nothing from an earlier session is reused (killer560, 2026-10-17: "it is pulling data from tests earlier"): a
     * session that ran and stopped leaves a scan behind; the next start must fetch afresh (here offline, so it fails and
     * stops) instead of acting on it. And a test candidate is refused when the JVM does not look like a harness.
     */
    static void noReuse(Session c) throws Exception {
        if (cheatOnly(c) != null) {
            return;
        }
        // The live page asks 55 against the NPC's 50: the first session skips Lump of Magma (its books say "avoid").
        // A second session starts from empty books, so it goes to Lump of Magma again rather than acting on the first's.
        try (AutoCloseable w = world(c, MAGMA, 128, 35.0, 50.0, 0, 10, false)) {
            JsonObject page = instant(MAGMA, MAGMA.instantTitle(), 55.0, "71,680", Sign.normal(), 0);
            c.hx().call("menu.onCommand", Hx.args("name", "bz", "menu", bz(MAGMA, page)));
            String first = FlipCroesusCases.start(c);
            c.check(first == null, "start refused: " + first);
            c.waitUntil("the first session to skip Lump of Magma", mc -> ((java.util.Set<?>) Mod.field(
                    BazaarFlipKit.AUTOPILOT, "avoid")).contains(MAGMA.id())
                    || !(Boolean) Mod.staticCall("bazaarflip.BazaarFlipFeature", "isRunning"), 400);
            c.check(BazaarFlipKit.avoided(c).contains(MAGMA.id()), "the first session never skipped it - the case proves"
                    + " nothing (stop: " + stopReason(c) + ")");
            c.ctx().runOnClient(mc -> {
                Mod.staticCall("bazaarflip.BazaarFlipFeature", "requestStop");
                McCompat.setScreen(mc, null);
            });
            c.ctx().waitTicks(10);
            long bzBefore = c.commands().stream().filter(x -> x.equals("bz Lump of Magma")).count();
            String second = FlipCroesusCases.start(c);
            c.check(second == null, "second start refused: " + second);
            c.check(BazaarFlipKit.avoided(c).isEmpty(), "the second session started with the first's books: "
                    + BazaarFlipKit.avoided(c));
            c.waitUntil("the second session to open Lump of Magma", mc -> c.commands().stream()
                    .filter(x -> x.equals("bz Lump of Magma")).count() > bzBefore
                    || !(Boolean) Mod.staticCall("bazaarflip.BazaarFlipFeature", "isRunning"), 400);
            String reason = stopReason(c);
            long bzAfter = c.commands().stream().filter(x -> x.equals("bz Lump of Magma")).count();
            c.check(bzBefore == 1 && bzAfter == 2, "/bz Lump of Magma " + bzBefore + " then " + bzAfter + ": " + c.commands()
                    + " (stop: " + reason + ")");
            c.check(c.events("menu.give").isEmpty(), "bought at 55 against 50");
            // The hook refuses a JVM that is not the testkit's.
            boolean refusedOutside = c.onClient(mc -> {
                String off = System.getProperty("killer560.net.offline");
                String open = System.getProperty("killer560.test.noExternalOpen");
                try {
                    System.setProperty("killer560.net.offline", "false");
                    System.setProperty("killer560.test.noExternalOpen", "false");
                    Mod.staticCall("bazaarflip.BazaarFlipScanner", "setTestCandidateForTest", "X", "X", 64, 1, 1.0, 2.0);
                    return !(Boolean) Mod.staticCall("bazaarflip.BazaarFlipScanner", "hasTestCandidateForTest");
                } finally {
                    if (off == null) {
                        System.clearProperty("killer560.net.offline");
                    } else {
                        System.setProperty("killer560.net.offline", off);
                    }
                    if (open == null) {
                        System.clearProperty("killer560.test.noExternalOpen");
                    } else {
                        System.setProperty("killer560.test.noExternalOpen", open);
                    }
                }
            });
            c.check(refusedOutside, "a test candidate was accepted outside a harness");
            c.note("restart after a session that skipped Lump of Magma: the new session's books are empty and it went back"
                    + " to it (/bz x" + bzAfter + "); test candidate refused outside a harness");
        }
    }
}
