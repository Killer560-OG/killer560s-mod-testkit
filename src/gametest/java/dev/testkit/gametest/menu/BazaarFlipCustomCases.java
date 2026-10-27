package dev.testkit.gametest.menu;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.LogTap;
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
 * <p>Rewritten 2026-10-21 for the autopilot (mod f0641a08; the only mode a cheat jar runs since 2026-10-19), which
 * decides from the Bazaar snapshot and the item repository's NPC prices ({@link FlipMarket}) instead of the old scan's
 * test candidate. With the advisor's NPC + Insta Buy answers it makes the same trade the old cycle did, one Insta Buy
 * action at a time: read Manage Orders, Insta Buy the units offered under the NPC's price, sell them from /trades, log the
 * sale, decide again. What changed per case is in its javadoc.
 *
 * <ul>
 *   <li>2761 his exact cut title and slot layout: Custom Amount, the sign gets "100", Done is sent, 100 arrive and are
 *       sold, and the profit tracker gets the sale (spent at the cost basis, received at the NPC's price).</li>
 *   <li>2762 the same through a Confirm Instant Buy page.</li>
 *   <li>2763 Custom Amount that never opens a sign: stops, nothing bought.</li>
 *   <li>2764 "Buy up to 40x.": the Insta Buy is capped at 40 (typed 40), and the next Insta Buy is another 40.</li>
 *   <li>2765 Hypixel rejecting the typed amount: stops (nothing arrived), no /trades.</li>
 *   <li>2766 a key in a Bazaar container mid-run (reskin off) stops it and names the key.</li>
 *   <li>2767 a key while the amount sign is open stops it; the sign is never sent with a number.</li>
 *   <li>2768 the NPC sell limit line mid-sale: selling ends, only the sold part is revenue, nothing more is bought, it
 *       stops on the limit and a restart is refused.</li>
 *   <li>2769 near the limit: the buy is sized down to what the NPCs will still pay for (exactly one stack), then it
 *       stops.</li>
 *   <li>2770 the profit tracker's Bazaar Flipper entry exists on cheat jars only.</li>
 *   <li>2771 the tab: removed rows gone, Target Purse typed (commas, k/m/b, nonsense, empty), saved and reloaded; an
 *       old file's minProfitPerRun ignored.</li>
 *   <li>2772 nothing profitable: nothing is bought and the autopilot stops and says why (mod bazaar-fix-1021).</li>
 *   <li>2773 the snapshot's best pick priced out by its live page: skipped, the next best bought, never back to it.</li>
 *   <li>2774 nothing from an earlier session is reused: units it bought then are his now, never sold or added to.</li>
 *   <li>2780 a Bazaar snapshot Hypixel built ten minutes ago (arrived just now) and a failed refresh: no trade, it
 *       stops naming the age; two minutes old is traded.</li>
 *   <li>2781 the profit record's spent is the Bought line's charge, not the Instant Buy page's quote.</li>
 *   <li>2782 with no Bought line, spent is the page quote's estimate and the sale's chat line says so.</li>
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
        MenuSuite.test(s, "2780-menu-bzflip-stale-snapshot" + sfx, BazaarFlipCustomCases::staleSnapshot);
        MenuSuite.test(s, "2781-menu-bzflip-spent-true-cost" + sfx, BazaarFlipCustomCases::spentTrueCost);
        MenuSuite.test(s, "2782-menu-bzflip-spent-estimated" + sfx, BazaarFlipCustomCases::spentEstimated);
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

        /** The autopilot's market entry: {@code units} offered at {@code each} under the NPC's {@code npc}. */
        FlipMarket.Prod market(double npc, double each, int units) {
            return FlipMarket.product(id, name, item.toUpperCase(java.util.Locale.ROOT), npc, each, units);
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
        return instant(p, title, each, each, upTo, sign, depth);
    }

    /** {@link #instant} whose presets quote {@code quoteEach} a unit (plus Hypixel's 4%) while a purchase is charged
     *  {@code each} in its Bought line: the page's quote and the true cost apart. */
    static JsonObject instant(Product p, String title, double each, double quoteEach, String upTo, Sign sign, int depth) {
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
                amount.add("reopen", instant(p, title, each, quoteEach, upTo, sign, depth - 1));
            }
            customSpec.add("amount", amount);
        }
        JsonObject custom = new JsonObject();
        custom.add("sign", customSpec);
        double q = quoteEach;
        JsonObject s12 = FlipCroesusCases.item("minecraft:" + p.item(), "§aBuy a stack!", "§8" + p.name(), "",
                "§7Amount: §a64§7x", String.format("§7Per unit: §6%,.1f coins", FlipCroesusCases.Q * q), "",
                String.format("§7Price: §6%,.0f coins", FlipCroesusCases.Q * 64 * q), "", "§eClick to buy now!");
        s12.addProperty("count", 64);
        Map<Integer, JsonObject> slots = new LinkedHashMap<>();
        slots.put(10, FlipCroesusCases.item("minecraft:golden_horse_armor", "§aBuy only §eone§a!", "§8" + p.name(), "",
                "§7Amount: §a1§7x", "", String.format("§7Price: §6%,.1f coins", FlipCroesusCases.Q * q), "", "§eClick to buy now!"));
        slots.put(12, s12);
        slots.put(14, FlipCroesusCases.item("minecraft:chest", "§aFill my inventory!", "§8" + p.name(), "",
                "§7Amount: §a2,304§7x", String.format("§7Per unit: §6%,.1f coins", FlipCroesusCases.Q * q), "",
                String.format("§7Price: §6%,.0f coins", FlipCroesusCases.Q * 2304 * q), "", "§eClick to buy now!"));
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

    /** /bz: the search page (its Manage Orders opening an empty orders page), the product page, then {@code instant}. */
    static JsonObject bazaar(Product p, JsonObject instant) {
        return FlipMarket.withManageOrders(FlipCroesusCases.menu(p.searchTitle(), 6, Map.of(
                0, FlipCroesusCases.item("minecraft:golden_hoe", "§eFarming"),
                9, FlipCroesusCases.item("minecraft:diamond_pickaxe", "§bMining"),
                11, priced(p),
                45, FlipCroesusCases.item("minecraft:oak_sign", "§aSearch"),
                49, FlipCroesusCases.item("minecraft:arrow", "§aGo Back"),
                50, FlipCroesusCases.item("minecraft:book", "§aManage Orders")),
                Map.of(11, FlipCroesusCases.open(productPage(p, instant)))));
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

    /**
     * The flipper on, Hub sidebar with a 1,000,000 purse, a Cookie Buff, and the autopilot's market: {@code p} with
     * {@code units} offered at {@code each} under the NPC's {@code npc} (plus {@code extra} products), {@code npcSoldToday}
     * coins already sold to NPCs today.
     */
    static AutoCloseable world(Session c, Product p, int units, double each, double npc, double npcSoldToday, int speed,
                               boolean reskin, FlipMarket.Prod... extra) {
        MenuKit.reset(c);
        // Server-side: units a case left unsold (2768 stops at the NPC limit with a stack) must not be the next case's -
        // the autopilot leaves every product he carries alone.
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
        FlipMarket.Prod[] products = new FlipMarket.Prod[extra.length + 1];
        products[0] = p.market(npc, each, units);
        System.arraycopy(extra, 0, products, 1, extra.length);
        AutoCloseable market = FlipMarket.autopilot(c, cfg, products);
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
            market.close();
            cfg.close();
        };
    }

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

    /** Profit-log records so far (every scope). */
    static int profitCycles(Session c) {
        return c.onClient(mc -> ((List<?>) Mod.staticCall("bazaarflip.BazaarFlipProfitLog", "cycles", (Object) null)).size());
    }

    /** The profit-log record at {@code index} (every scope, oldest first). */
    static Object cycleAt(Session c, int index) {
        return c.onClient(mc -> {
            List<?> all = (List<?>) Mod.staticCall("bazaarflip.BazaarFlipProfitLog", "cycles", (Object) null);
            return index < all.size() ? all.get(index) : null;
        });
    }

    static double num(Object record, String accessor) {
        return ((Number) Mod.call(record, accessor)).doubleValue();
    }

    static int units(Object record, String accessor) {
        return ((Number) Mod.call(record, accessor)).intValue();
    }

    static String cheatOnly(Session c) {
        if (!Mod.isCheat()) {
            c.note("legit jar: no flipper (761 checks it never starts)");
            return "legit";
        }
        return null;
    }

    /** Starts the flipper and waits until the profit log gains a record (one Insta Buy bought and sold) or it stops. */
    static void startAndAwaitSale(Session c, int recordsBefore, int ticks) {
        String refusal = FlipCroesusCases.start(c);
        c.check(refusal == null, "start refused: " + refusal);
        c.waitUntil("the autopilot to buy and sell one Insta Buy (a profit record) or stop", mc -> ((List<?>) Mod.staticCall(
                "bazaarflip.BazaarFlipProfitLog", "cycles", (Object) null)).size() > recordsBefore
                || !(Boolean) Mod.staticCall("bazaarflip.BazaarFlipFeature", "isRunning"), ticks);
        c.check(profitCycles(c) > recordsBefore, "no sale logged; stopped with: " + stopReason(c) + "; commands "
                + c.commands() + "; clicks " + FlipCroesusCases.allClicks(c));
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
            JsonObject page = instant(PUFFER, PUFFER_CUT, each, "71,680", new Sign(false, confirm, 71_680), 1);
            c.hx().call("menu.onCommand", Hx.args("name", "bz", "menu", bazaar(PUFFER, page)));
            c.hx().call("menu.onCommand", Hx.args("name", "trades", "menu", trades(npc, 0, null)));
            int before = profitCycles(c);
            startAndAwaitSale(c, before, 900);
            FlipCroesusCases.requestStop(c);
            List<Integer> instantClicks = FlipCroesusCases.clicksOn(c, PUFFER_CUT);
            c.check(FlipMarket.readOrders(c, PUFFER.searchTitle()), "no Manage Orders read first: " + c.commands()
                    + "; " + FlipCroesusCases.allClicks(c));
            c.check(FlipCroesusCases.first(FlipCroesusCases.clicksOn(c, PUFFER.productTitle()), 1).equals(List.of(10)),
                    "product clicks " + FlipCroesusCases.clicksOn(c, PUFFER.productTitle()) + "; all " + FlipCroesusCases.allClicks(c));
            c.check(FlipCroesusCases.first(instantClicks, 1).equals(List.of(16)), "Instant Buy clicks " + instantClicks
                    + " on \"" + PUFFER_CUT + "\" (want Custom Amount, 16, first)");
            c.check(c.events("sign.opened").size() >= 1, "sign prompts opened: " + c.events("sign.opened").size());
            List<String> amounts = signAmounts(c);
            c.check(FlipCroesusCases.first(amounts, 1).equals(List.of("100")), "sign updates the server received, line 1: "
                    + amounts);
            JsonArray lines = c.events("sign.update").get(0).getAsJsonArray("lines");
            c.check(lines.get(1).getAsString().equals(SIGN_LINES[1]) && lines.get(2).getAsString().equals(SIGN_LINES[2])
                    && lines.get(3).getAsString().equals(SIGN_LINES[3]), "the other lines came back changed: " + lines);
            if (confirm) {
                c.check(FlipCroesusCases.first(FlipCroesusCases.clicksOn(c, "Confirm Instant Buy"), 1).equals(List.of(13)),
                        "Confirm Instant Buy clicks " + FlipCroesusCases.clicksOn(c, "Confirm Instant Buy"));
            }
            c.check(given(c) == units, "the server delivered " + given(c));
            c.check(sold(c) == units, "sold " + sold(c));
            c.check(c.commands().contains("trades"), "no /trades: " + c.commands());
            // The profit tracker: the sale, at the cost basis the Insta Buy set, against the NPC's price.
            Object cyc = cycleAt(c, before);
            double spent = num(cyc, "spent");
            double received = num(cyc, "received");
            double net = num(cyc, "net");
            c.check(Math.abs(spent - units * each) < 1, "spent " + spent + ", the buy was " + units * each);
            c.check(received == units * npc, "received " + received + ", the You sold lines said " + units * npc);
            c.check(num(cyc, "tax") == 0.0, "tax " + num(cyc, "tax"));
            c.check(Math.abs(net - (received - spent)) < 1e-6 && Math.abs(net - units * (npc - each)) < 1,
                    "net " + net + " (want " + units * (npc - each) + ")");
            c.check(units(cyc, "sold") == units && units(cyc, "bought") == units, "record " + cyc);
            c.note("\"" + PUFFER_CUT + "\": clicks " + FlipCroesusCases.allClicks(c) + "sign line 1 " + amounts
                    + ", delivered " + given(c) + ", sold " + sold(c) + "; profit record spent " + spent + " received "
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
            c.hx().call("menu.onCommand", Hx.args("name", "bz", "menu", bazaar(PUFFER, page)));
            String refusal = FlipCroesusCases.start(c);
            c.check(refusal == null, "start refused: " + refusal);
            c.waitUntil("the flipper to stop", mc -> !(Boolean) Mod.staticCall("bazaarflip.BazaarFlipFeature", "isRunning"), 600);
            String reason = stopReason(c);
            c.check(reason.contains("sign never opened"), "stopped with: " + reason);
            c.check(FlipCroesusCases.clicksOn(c, PUFFER_CUT).equals(List.of(16)), "clicks " + FlipCroesusCases.allClicks(c));
            c.check(given(c) == 0 && c.events("sign.update").isEmpty(), "bought " + given(c));
            c.check(!c.commands().contains("trades"), "sent /trades");
            c.note("Custom Amount opened no sign: \"" + reason + "\"; clicks " + FlipCroesusCases.allClicks(c));
        }
    }

    // ---- 2764 ---------------------------------------------------------------------------------------------------

    /**
     * "Buy up to 40x." with 100 offered under the NPC's price. The old cycle typed 40, 40, 20 in one cycle; the autopilot
     * caps each Insta Buy at the page's Buy up to and sells before deciding again, so the first Insta Buy types 40, the
     * 40 are sold and logged, and the next Insta Buy is another 40.
     */
    static void buyUpTo(Session c) throws Exception {
        if (cheatOnly(c) != null) {
            return;
        }
        try (AutoCloseable w = world(c, PUFFER, 100, 1_900.0, 2_400.0, 0, 10, false)) {
            JsonObject page = instant(PUFFER, PUFFER_CUT, 1_900.0, "40", Sign.normal(), 4);
            c.hx().call("menu.onCommand", Hx.args("name", "bz", "menu", bazaar(PUFFER, page)));
            c.hx().call("menu.onCommand", Hx.args("name", "trades", "menu", trades(2_400.0, 0, null)));
            int before = profitCycles(c);
            startAndAwaitSale(c, before, 1200);
            c.waitUntil("the next Insta Buy to type its amount", mc -> signAmounts(c).size() >= 2
                    || !(Boolean) Mod.staticCall("bazaarflip.BazaarFlipFeature", "isRunning"), 600);
            FlipCroesusCases.requestStop(c);
            List<String> amounts = signAmounts(c);
            c.check(FlipCroesusCases.first(amounts, 2).equals(List.of("40", "40")), "sign amounts " + amounts
                    + " (Buy up to 40, 100 offered); stop: " + stopReason(c) + "; clicks " + FlipCroesusCases.allClicks(c));
            Object cyc = cycleAt(c, before);
            c.check(units(cyc, "bought") == 40 && units(cyc, "sold") == 40, "first sale record " + cyc);
            c.check(given(c) >= 40 && given(c) % 40 == 0 && sold(c) >= 40 && sold(c) % 40 == 0,
                    "delivered " + given(c) + ", sold " + sold(c));
            c.note("Buy up to 40x., 100 offered: typed " + amounts + ", delivered " + given(c) + ", sold " + sold(c)
                    + "; first record bought " + units(cyc, "bought"));
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
            c.hx().call("menu.onCommand", Hx.args("name", "bz", "menu", bazaar(PUFFER, page)));
            String refusal = FlipCroesusCases.start(c);
            c.check(refusal == null, "start refused: " + refusal);
            c.waitUntil("the flipper to stop", mc -> !(Boolean) Mod.staticCall("bazaarflip.BazaarFlipFeature", "isRunning"), 600);
            String reason = stopReason(c);
            c.check(signAmounts(c).equals(List.of("100")), "sign amounts " + signAmounts(c));
            c.check(c.events("sign.rejected").size() == 1, "the server rejected " + c.events("sign.rejected").size());
            c.check(reason.contains("nothing arrived"), "stopped with: " + reason);
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
            c.hx().call("menu.onCommand", Hx.args("name", "bz", "menu", bazaar(PUFFER, page)));
            String refusal = FlipCroesusCases.start(c);
            c.check(refusal == null, "start refused: " + refusal);
            c.waitUntil("the product page to be open", mc -> PUFFER.productTitle().equals(MenuKit.screenTitle(mc)), 600);
            int before = c.events("menu.click").size();
            c.ctx().getInput().pressKey(74); // GLFW_KEY_J, inside Hypixel's (un-reskinned) container
            c.waitUntil("the flipper to stop on the key", mc -> !(Boolean) Mod.staticCall("bazaarflip.BazaarFlipFeature",
                    "isRunning"), 20);
            c.ctx().waitTicks(60);
            String reason = stopReason(c);
            c.check(reason.contains("J"), "stop reason does not name the key: " + reason);
            c.check(before >= 2, "the autopilot had not clicked its way to the product page: " + FlipCroesusCases.allClicks(c));
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
            c.hx().call("menu.onCommand", Hx.args("name", "bz", "menu", bazaar(PUFFER, page)));
            String refusal = FlipCroesusCases.start(c);
            c.check(refusal == null, "start refused: " + refusal);
            c.waitUntil("the amount sign to open", mc -> McCompat.screen(mc) instanceof AbstractSignEditScreen, 900);
            c.ctx().getInput().pressKey(74); // J, while the sign editor is up and before the flipper types
            c.waitUntil("the flipper to stop on the key", mc -> !(Boolean) Mod.staticCall("bazaarflip.BazaarFlipFeature",
                    "isRunning"), 20);
            c.ctx().waitTicks(40);
            String reason = stopReason(c);
            boolean signStillOpen = c.onClient(mc -> McCompat.screen(mc) instanceof AbstractSignEditScreen);
            c.check(reason.contains("J"), "stop reason: " + reason);
            c.check(c.events("sign.opened").size() == 1, "sign prompts the server opened: " + c.events("sign.opened").size());
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

    /**
     * The NPC pays for one stack of the 128 and then says the daily limit is reached. The autopilot ends the NPC sale
     * (the stack it could not sell stays), logs only the sold stack as revenue, marks today's limit reached, buys
     * nothing more and stops on "the daily NPC sell limit is reached" (mod bazaar-fix-1021; it used to wait forever). A
     * restart is refused with the limit, instead of starting a session that could only wait.
     */
    static void npcLimitMidCycle(Session c) throws Exception {
        if (cheatOnly(c) != null) {
            return;
        }
        double each = 35.0;
        double npc = 50.0;
        try (AutoCloseable w = world(c, MAGMA, 128, each, npc, 0, 10, false)) {
            JsonObject page = instant(MAGMA, MAGMA.instantTitle(), each, "71,680", Sign.normal(), 1);
            c.hx().call("menu.onCommand", Hx.args("name", "bz", "menu", bazaar(MAGMA, page)));
            // The NPC pays for one stack (64 x 50) and then says the limit is reached.
            c.hx().call("menu.onCommand", Hx.args("name", "trades", "menu", trades(npc, 64 * npc, LIMIT_CHAT)));
            int before = profitCycles(c);
            long mark = LogTap.mark();
            startAndAwaitSale(c, before, 900);
            c.waitUntil("the autopilot to stop with the NPC limit reached", mc -> FlipMarket.waiting(mark)
                    || !(Boolean) Mod.staticCall("bazaarflip.BazaarFlipFeature", "isRunning"), 400);
            boolean stillRunning = running(c);
            String reason = stopReason(c);
            int clicksAfter = c.events("menu.click").size();
            c.ctx().waitTicks(40);
            c.check(c.events("menu.click").size() == clicksAfter, "clicked after the limit: " + FlipCroesusCases.allClicks(c));
            FlipCroesusCases.requestStop(c);
            c.check(!stillRunning && reason.contains(FlipMarket.NPC_LIMIT) && reason.contains("64 unit(s) it holds stay with you")
                    && reason.contains("resets at 00:00 UTC"), "did not stop on the limit: running " + stillRunning
                    + ", stop \"" + reason + "\", waiting " + FlipMarket.waiting(mark));
            // A restart with the limit still reached: refused, nothing sent.
            int commandsBefore = c.commands().size();
            String restart = FlipCroesusCases.start(c);
            c.ctx().waitTicks(20);
            c.check(restart != null && restart.contains("daily NPC sell limit") && !running(c),
                    "a restart after the limit: \"" + restart + "\", running " + running(c));
            c.check(c.commands().size() == commandsBefore, "the refused restart sent " + c.commands());
            FlipCroesusCases.requestStop(c);
            c.check(given(c) == 128, "delivered " + given(c));
            c.check(sold(c) == 64 && c.events("sell.refused").size() == 1, "sold " + sold(c) + ", refused "
                    + c.events("sell.refused").size());
            c.check(FlipMarket.sent(c, "bz Lump of Magma") == 1 && FlipMarket.sent(c, "trades") == 1,
                    "bought or sold again after the limit: " + c.commands());
            Object cyc = cycleAt(c, before);
            c.check(num(cyc, "received") == 64 * npc, "received " + num(cyc, "received") + " (only the sold stack pays)");
            c.check(Math.abs(num(cyc, "spent") - 64 * each) < 1, "spent " + num(cyc, "spent") + " (the sold stack's cost)");
            c.check(units(cyc, "sold") == 64, "record sold " + Mod.call(cyc, "sold"));
            boolean limitLeft = c.onClient(mc -> ((Number) Mod.staticCall("bazaarflip.BazaarFlipNpcLimit", "remaining"))
                    .doubleValue() <= 0);
            c.check(limitLeft, "the limit line did not mark today's limit reached");
            int left = c.onClient(mc -> {
                int n = 0;
                for (int i = 0; i < 36; i++) {
                    if (mc.player.getInventory().getItem(i).getHoverName().getString().equals(MAGMA.name())) {
                        n += mc.player.getInventory().getItem(i).getCount();
                    }
                }
                return n;
            });
            c.check(left == 64, "units left with him: " + left);
            c.note("limit after one stack: sold " + sold(c) + " of " + given(c) + ", record received "
                    + num(cyc, "received") + " of spent " + num(cyc, "spent") + ", " + left + " left unsold; stopped \""
                    + reason + "\"; restart refused \"" + restart + "\"");
        }
    }

    static void npcNearLimit(Session c) throws Exception {
        if (cheatOnly(c) != null) {
            return;
        }
        double npc = 50.0;
        // 64 units (3,200 coins) plus 10 coins left today: the 128 offered are sized down to one stack.
        double soldToday = 500_000_000.0 - 64 * npc - 10;
        try (AutoCloseable w = world(c, MAGMA, 128, 35.0, npc, soldToday, 10, false)) {
            JsonObject page = instant(MAGMA, MAGMA.instantTitle(), 35.0, "71,680", Sign.normal(), 1);
            c.hx().call("menu.onCommand", Hx.args("name", "bz", "menu", bazaar(MAGMA, page)));
            c.hx().call("menu.onCommand", Hx.args("name", "trades", "menu", trades(npc, 0, null)));
            int before = profitCycles(c);
            long mark = LogTap.mark();
            startAndAwaitSale(c, before, 900);
            c.waitUntil("the autopilot to stop with 10 coins of NPC room left", mc -> FlipMarket.waiting(mark)
                    || !(Boolean) Mod.staticCall("bazaarflip.BazaarFlipFeature", "isRunning"), 400);
            boolean stillRunning = running(c);
            String reason = stopReason(c);
            FlipCroesusCases.requestStop(c);
            // Mod bazaar-fix-1021: with nothing open or held and no unit the NPCs still pay for, it stops (it used to wait).
            c.check(!stillRunning && reason.contains(FlipMarket.NOTHING_PROFITABLE) && reason.contains("10 coins left"),
                    "running " + stillRunning + ", stop \"" + reason + "\", waiting " + FlipMarket.waiting(mark));
            List<Integer> clicks = FlipCroesusCases.clicksOn(c, MAGMA.instantTitle());
            c.check(clicks.equals(List.of(12)), "Instant Buy clicks " + clicks + " (want Buy a stack! once: exactly 64)");
            c.check(given(c) == 64 && sold(c) == 64, "delivered " + given(c) + ", sold " + sold(c));
            c.check(c.events("sign.update").isEmpty(), "used Custom Amount for an exact stack");
            c.check(FlipMarket.sent(c, "bz Lump of Magma") == 1, "bought again past the limit: " + c.commands());
            c.note("10 + 3,200 coins left on the NPC limit: bought " + given(c) + " (Buy a stack!), sold " + sold(c)
                    + ", then stopped \"" + reason + "\"");
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
            Files.createDirectories(file.getParent()); // mod layout-fixes-1026: ModPaths.config no longer makes the folder
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

    /**
     * Nothing profitable: today's NPC limit has room for no unit (49 coins left, 50 a unit), so no product prices. It
     * reads Manage Orders, buys nothing, never opens the product, and stops on "nothing on the Bazaar is profitable right
     * now within the 49 coins left on today's NPC sell limit" (mod bazaar-fix-1021; it used to wait forever).
     */
    static void nothingProfitable(Session c) throws Exception {
        if (cheatOnly(c) != null) {
            return;
        }
        try (AutoCloseable w = world(c, MAGMA, 128, 35.0, 50.0, 500_000_000.0 - 49, 10, false)) {
            JsonObject page = instant(MAGMA, MAGMA.instantTitle(), 35.0, "71,680", Sign.normal(), 0);
            c.hx().call("menu.onCommand", Hx.args("name", "bz", "menu", bazaar(MAGMA, page)));
            long mark = LogTap.mark();
            String refusal = FlipCroesusCases.start(c);
            c.check(refusal == null, "start refused: " + refusal);
            c.waitUntil("the autopilot to stop", mc -> FlipMarket.waiting(mark)
                    || !(Boolean) Mod.staticCall("bazaarflip.BazaarFlipFeature", "isRunning"), 400);
            boolean stillRunning = running(c);
            String reason = stopReason(c);
            FlipCroesusCases.requestStop(c);
            c.check(FlipMarket.readOrders(c, MAGMA.searchTitle()), "it never read Manage Orders (did it act at all?): "
                    + c.commands() + "; " + FlipCroesusCases.allClicks(c));
            c.check(!stillRunning && reason.contains(FlipMarket.NOTHING_PROFITABLE) && reason.contains("49 coins left"),
                    "running " + stillRunning + ", stop \"" + reason + "\", waiting " + FlipMarket.waiting(mark));
            c.check(c.commands().stream().noneMatch(x -> x.startsWith("bz ")), "opened a product: " + c.commands());
            c.check(given(c) == 0 && !c.commands().contains("trades"), "bought " + given(c) + ", " + c.commands());
            c.note("no unit sellable today: read Manage Orders, then stopped \"" + reason + "\"; no /bz <product>");
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
     * killer560, 2026-10-17: "It keeps going back to Enchanted Pufferfish then it says Stopped: bought nothing". The
     * snapshot ranks Enchanted Pufferfish first (100 at 1,900 against an NPC 2,400), but its live Instant Buy page prices
     * the book at 2,500 (quoting 2,600); Lump of Magma (128 at 35 against 50) is second. The autopilot must leave the
     * pufferfish untouched, skip it, take the Lump of Magma, sell it - and on its next decision buy Lump of Magma again,
     * never the pufferfish (the session's skip list).
     */
    static void livePriceNextBest(Session c) throws Exception {
        if (cheatOnly(c) != null) {
            return;
        }
        try (AutoCloseable w = world(c, PUFFER, 100, 1_900.0, 2_400.0, 0, 10, false, MAGMA.market(50.0, 35.0, 128))) {
            JsonObject pufferInstant = instant(PUFFER, PUFFER_CUT, 2_500.0, "71,680", Sign.normal(), 1);
            JsonObject magmaInstant = instant(MAGMA, MAGMA.instantTitle(), 35.0, "71,680", Sign.normal(), 1);
            String searchTitle = "Bazaar ➜ \"Enchanted\"";
            JsonObject search = FlipMarket.withManageOrders(FlipCroesusCases.menu(searchTitle, 6, Map.of(
                    11, priced(PUFFER), 12, priced(MAGMA),
                    45, FlipCroesusCases.item("minecraft:oak_sign", "§aSearch"),
                    49, FlipCroesusCases.item("minecraft:arrow", "§aGo Back"),
                    50, FlipCroesusCases.item("minecraft:book", "§aManage Orders")),
                    Map.of(11, FlipCroesusCases.open(productPage(PUFFER, pufferInstant)),
                            12, FlipCroesusCases.open(productPage(MAGMA, magmaInstant)))));
            c.hx().call("menu.onCommand", Hx.args("name", "bz", "menu", search));
            c.hx().call("menu.onCommand", Hx.args("name", "trades", "menu", trades(50.0, 0, null)));
            int before = profitCycles(c);
            long mark = LogTap.mark();
            startAndAwaitSale(c, before, 1200);
            // Its next decision: Lump of Magma again, not the pufferfish at the same snapshot price.
            c.waitUntil("the next decision to open a product", mc -> FlipMarket.sent(c, "bz Lump of Magma") >= 2
                    || FlipMarket.sent(c, "bz Enchanted Pufferfish") >= 2
                    || !(Boolean) Mod.staticCall("bazaarflip.BazaarFlipFeature", "isRunning"), 600);
            String reason = stopReason(c);
            FlipCroesusCases.requestStop(c);
            List<String> cmds = c.commands();
            List<Integer> pufferClicks = FlipCroesusCases.clicksOn(c, PUFFER_CUT);
            c.check(pufferClicks.isEmpty(), "bought on the pufferfish page: " + pufferClicks);
            c.check(FlipMarket.sent(c, "bz Enchanted Pufferfish") == 1, "/bz Enchanted Pufferfish sent "
                    + FlipMarket.sent(c, "bz Enchanted Pufferfish") + " time(s): " + cmds + " (stop: " + reason + ")");
            c.check(FlipMarket.sent(c, "bz Lump of Magma") >= 2, "the next decision was not Lump of Magma: " + cmds
                    + " (stop: " + reason + ")");
            c.check(FlipMarket.logged(mark, "Skipping Enchanted Pufferfish"), "no skip line for the pufferfish");
            Object cyc = cycleAt(c, before);
            c.check("LUMP_OF_MAGMA".equals(Mod.call(cyc, "productId")) && units(cyc, "sold") == 128, "first record " + cyc);
            c.check(FlipCroesusCases.first(signAmounts(c), 1).equals(List.of("128")) && given(c) >= 128
                            && given(c) % 128 == 0 && sold(c) >= 128,
                    "Lump of Magma: sign " + signAmounts(c) + ", delivered " + given(c) + ", sold " + sold(c));
            c.note("pufferfish live at 2,500 (quoted 2,600) vs NPC 2,400: skipped once, then " + cmds
                    + "; magma sign " + signAmounts(c) + ", sold " + sold(c));
        }
    }

    /**
     * Nothing from an earlier session is reused (killer560, 2026-10-17: "it is pulling data from tests earlier"). Now the
     * autopilot's books are per session: a first session buys 128 Lump of Magma and is stopped before it can sell them
     * (/trades opens nothing). A second session must not treat those units as its own - they are his now: it says it left
     * one carried item type alone, never sells them (no /trades, no sale) and never buys more of that product. And the
     * old scanner's test hook still refuses a JVM that is not a harness.
     */
    static void noReuse(Session c) throws Exception {
        if (cheatOnly(c) != null) {
            return;
        }
        try (AutoCloseable w = world(c, MAGMA, 128, 35.0, 50.0, 0, 10, false)) {
            JsonObject page = instant(MAGMA, MAGMA.instantTitle(), 35.0, "71,680", Sign.normal(), 0);
            c.hx().call("menu.onCommand", Hx.args("name", "bz", "menu", bazaar(MAGMA, page)));
            String first = FlipCroesusCases.start(c);
            c.check(first == null, "start refused: " + first);
            c.waitUntil("the first session's Insta Buy to arrive", mc -> given(c) >= 128
                    || !(Boolean) Mod.staticCall("bazaarflip.BazaarFlipFeature", "isRunning"), 600);
            c.ctx().runOnClient(mc -> {
                Mod.staticCall("bazaarflip.BazaarFlipFeature", "requestStop");
                McCompat.setScreen(mc, null);
            });
            c.check(given(c) == 128, "the first session bought " + given(c) + " (stop: " + stopReason(c) + ")");
            c.ctx().waitTicks(10);
            // Now /trades would sell: anything the second session sold would show as sell.sold.
            c.hx().call("menu.onCommand", Hx.args("name", "trades", "menu", trades(50.0, 0, null)));
            long bzBefore = FlipMarket.sent(c, "bz Lump of Magma");
            long tradesBefore = FlipMarket.sent(c, "trades");
            long bareBefore = FlipMarket.sent(c, "bz");
            long mark = LogTap.mark();
            String second = FlipCroesusCases.start(c);
            c.check(second == null, "second start refused: " + second);
            c.waitUntil("the second session to stop", mc -> FlipMarket.waiting(mark)
                    || !(Boolean) Mod.staticCall("bazaarflip.BazaarFlipFeature", "isRunning"), 400);
            String reason = stopReason(c);
            boolean stillRunning = running(c);
            FlipCroesusCases.requestStop(c);
            // His units are left alone, so nothing pays: it stops (mod bazaar-fix-1021; it used to wait forever).
            c.check(!stillRunning && reason.contains(FlipMarket.NOTHING_PROFITABLE), "second session: running "
                    + stillRunning + ", stop \"" + reason + "\", waiting " + FlipMarket.waiting(mark));
            c.check(FlipMarket.sent(c, "bz") > bareBefore, "the second session never read Manage Orders: " + c.commands());
            c.check(FlipMarket.logged(mark, "1 carried item type(s) left alone"), "the carried units were not left alone: "
                    + FlipMarket.log(mark).stream().filter(l -> l.contains("Autopilot start")).toList());
            c.check(FlipMarket.sent(c, "bz Lump of Magma") == bzBefore, "the second session bought more: " + c.commands());
            c.check(FlipMarket.sent(c, "trades") == tradesBefore && c.events("sell.sold").isEmpty(),
                    "the second session sold the first one's units: " + c.commands() + ", sold " + sold(c));
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
                    Mod.staticCall("bazaarflip.BazaarFlipScanner", "clearTestCandidateForTest");
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
            c.note("first session bought 128 and was stopped; the second left them alone (no sale, no buy), stopped \""
                    + reason + "\"; test candidate refused outside a harness");
        }
    }

    // ---- 2780-2782 (bazaar-fix-1021) ----------------------------------------------------------------------------

    /**
     * Mod bazaar-fix-1021, bug 2: a failed refresh left the old products in place and the autopilot traded on them with no
     * age check. Here Hypixel built the snapshot ten minutes ago but it arrived just now (so a check on arrival time
     * would pass), and every refresh in the harness fails (offline). The autopilot must ask for a refresh, and when that
     * fails stop naming the age - no /bz, no click, nothing bought. Control: the same market two minutes old is traded.
     */
    static void staleSnapshot(Session c) throws Exception {
        if (cheatOnly(c) != null) {
            return;
        }
        try (AutoCloseable w = world(c, MAGMA, 128, 35.0, 50.0, 0, 10, false)) {
            JsonObject page = instant(MAGMA, MAGMA.instantTitle(), 35.0, "71,680", Sign.normal(), 0);
            c.hx().call("menu.onCommand", Hx.args("name", "bz", "menu", bazaar(MAGMA, page)));
            FlipMarket.reapply(c, System.currentTimeMillis() - 10 * 60_000L, MAGMA.market(50.0, 35.0, 128));
            long mark = LogTap.mark();
            String refusal = FlipCroesusCases.start(c);
            c.check(refusal == null, "start refused: " + refusal);
            c.waitUntil("the autopilot to stop on ten-minute-old prices", mc -> !(Boolean) Mod.staticCall(
                    "bazaarflip.BazaarFlipFeature", "isRunning"), 600);
            String reason = stopReason(c);
            c.check(FlipMarket.logged(mark, "refreshing them before the next trade"), "it never asked for a refresh: "
                    + FlipMarket.log(mark).stream().filter(l -> l.contains("BazaarFlip") || l.contains("Bazaar Flip")).toList());
            c.check(reason.contains("10 min old") && reason.contains("refreshing them failed")
                    && reason.contains("not trading on them"), "stopped with: \"" + reason + "\"");
            c.check(c.commands().stream().noneMatch(x -> x.startsWith("bz")), "sent on stale prices: " + c.commands());
            c.check(c.events("menu.click").isEmpty() && given(c) == 0, "clicked " + FlipCroesusCases.allClicks(c)
                    + ", delivered " + given(c));
            // Control: two minutes old is inside the limit, so the same market is traded at once.
            FlipMarket.reapply(c, System.currentTimeMillis() - 2 * 60_000L, MAGMA.market(50.0, 35.0, 128));
            long mark2 = LogTap.mark();
            String again = FlipCroesusCases.start(c);
            c.check(again == null, "second start refused: " + again);
            c.waitUntil("the autopilot to open Lump of Magma on two-minute-old prices", mc -> FlipMarket.sent(c,
                    "bz Lump of Magma") >= 1 || !(Boolean) Mod.staticCall("bazaarflip.BazaarFlipFeature", "isRunning"), 600);
            String reason2 = stopReason(c);
            FlipCroesusCases.requestStop(c);
            c.check(FlipMarket.sent(c, "bz Lump of Magma") >= 1, "two-minute-old prices were not traded: "
                    + c.commands() + ", stop \"" + reason2 + "\"");
            c.check(!FlipMarket.logged(mark2, "refreshing them before the next trade"),
                    "two-minute-old prices were taken as stale");
            c.note("built 10 min ago, arrived now, refresh failed: stopped \"" + reason + "\" with no command or click;"
                    + " 2 min old: traded (" + c.commands() + ")");
        }
    }

    /**
     * Bug 3: the profit record's spent came from the Instant Buy page's quote (rounded, less an assumed 4%) whenever the
     * units arrived before Hypixel's Bought line - which is the order the fake (and, it seems, Hypixel) sends them. Here
     * the page quotes 1,950 a unit (plus 4%) and the Bought line charges 1,900: the record must spend 190,000, the
     * Bought line's figure, and the sale's chat line must not call it an estimate.
     */
    static void spentTrueCost(Session c) throws Exception {
        if (cheatOnly(c) != null) {
            return;
        }
        double each = 1_900.0;
        double quote = 1_950.0;
        double npc = 2_400.0;
        int units = 100;
        // The bug needs the units BEFORE the Bought line. Under load (preexisting-1026, nine clients) the Bought line once
        // arrived first: the mod then took its charge straight away (spent 190,000, right) and wrote no correction line,
        // so that run never reached the path this case is about. Such a run is tried again, three times in all.
        for (int attempt = 1; ; attempt++) {
        try (AutoCloseable w = world(c, PUFFER, units, each, npc, 0, 10, false)) {
            JsonObject page = instant(PUFFER, PUFFER_CUT, each, quote, "71,680", Sign.normal(), 1);
            c.hx().call("menu.onCommand", Hx.args("name", "bz", "menu", bazaar(PUFFER, page)));
            c.hx().call("menu.onCommand", Hx.args("name", "trades", "menu", trades(npc, 0, null)));
            int before = profitCycles(c);
            long mark = LogTap.mark();
            startAndAwaitSale(c, before, 900);
            FlipCroesusCases.requestStop(c);
            c.check(FlipCroesusCases.first(signAmounts(c), 1).equals(List.of("100")), "sign amounts " + signAmounts(c));
            c.check(given(c) >= units && sold(c) >= units, "delivered " + given(c) + ", sold " + sold(c));
            boolean corrected = FlipMarket.logged(mark, "Bought line for " + PUFFER.name());
            if (!corrected && attempt < 3) {
                double early = num(cycleAt(c, before), "spent");
                c.note("attempt " + attempt + ": the Bought line came before the units (spent " + early
                        + ", no correction needed) - the units-first path was not reached, again");
                continue;
            }
            c.check(corrected, "the Bought line never corrected the quote's estimate in three runs (the units never came"
                    + " first)");
            Object cyc = cycleAt(c, before);
            double spent = num(cyc, "spent");
            c.check(Math.abs(spent - units * each) < 1, "spent " + spent + ": the Bought line charged " + units * each
                    + " (the quote's estimate is " + units * quote + ")");
            String line = FlipMarket.log(mark).stream().filter(l -> l.contains("Sold " + units + " " + PUFFER.name()))
                    .findFirst().orElse("");
            c.check(line.contains("spent 190,000") && !line.contains("estimated"), "sale line: \"" + line + "\"");
            c.note("quote 1,950 (+4%), Bought line 1,900: record spent " + spent + "; \"" + line.trim() + "\"");
        }
        break;
        }
    }

    /**
     * Bug 3, the other half: Buy a stack! delivers its 64 with no Bought line at all, so no true cost reaches the client.
     * The record's spent is then the page quote's estimate (2,330 rounded, less 4%) and the sale's chat line says it is
     * an estimate from the rounded quote.
     */
    static void spentEstimated(Session c) throws Exception {
        if (cheatOnly(c) != null) {
            return;
        }
        double each = 35.0;
        double npc = 50.0;
        try (AutoCloseable w = world(c, MAGMA, 64, each, npc, 0, 10, false)) {
            JsonObject page = instant(MAGMA, MAGMA.instantTitle(), each, "71,680", Sign.normal(), 0);
            page.getAsJsonObject("on").getAsJsonObject("12").remove("chat");
            c.hx().call("menu.onCommand", Hx.args("name", "bz", "menu", bazaar(MAGMA, page)));
            c.hx().call("menu.onCommand", Hx.args("name", "trades", "menu", trades(npc, 0, null)));
            int before = profitCycles(c);
            long mark = LogTap.mark();
            startAndAwaitSale(c, before, 900);
            FlipCroesusCases.requestStop(c);
            List<Integer> clicks = FlipCroesusCases.clicksOn(c, MAGMA.instantTitle());
            c.check(FlipCroesusCases.first(clicks, 1).equals(List.of(12)), "Instant Buy clicks " + clicks);
            c.check(given(c) >= 64 && sold(c) >= 64, "delivered " + given(c) + ", sold " + sold(c));
            Object cyc = cycleAt(c, before);
            double spent = num(cyc, "spent");
            double estimate = Math.round(FlipCroesusCases.Q * 64 * each) / FlipCroesusCases.Q;
            c.check(Math.abs(spent - estimate) < 0.01, "spent " + spent + " (want the quote's estimate " + estimate + ")");
            String line = FlipMarket.log(mark).stream().filter(l -> l.contains("Sold 64 " + MAGMA.name())).findFirst()
                    .orElse("");
            c.check(line.contains("estimated from the Instant Buy page's rounded quote"), "sale line: \"" + line + "\"");
            c.note("no Bought line: record spent " + spent + " (quote estimate " + estimate + "); \"" + line.trim() + "\"");
        }
    }
}
