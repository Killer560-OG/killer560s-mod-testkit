package dev.testkit.gametest.menu;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.hx.Hx;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 2798-2800 (ahrelist-1101, the Doc audit's P3): the AH Flipper's Auto Relist against a fake
 * Hypixel Auction House built from the best-known menu names (public mods; no recording of the real menus existed yet).
 * Judged by what the SERVER recorded: menu.click, menu.list (an inventory item moved into Create BIN Auction), sign.update.
 * <ul>
 *   <li>2798 one whole cycle: /ah, View Bids -> a won auction's Collect Auction, Manage Auctions -> Claim All (a sold and an
 *       expired listing), then Create Auction -> Create BIN Auction twice: the bought flip at its remembered price less
 *       the copier's Undercut, the expired item at its lowest BIN less the Undercut; each price typed into the sign, read
 *       back off the menu, then Create BIN Auction and Confirm.</li>
 *   <li>2799 the Emergency Cancel Key while the price sign is open: the relist stops and no price reaches the server.</li>
 *   <li>2801 the Bazaar Flipper's Insta Buy delivered over two parts six ticks apart (his "Bought 1,472x Gravel" was first
 *       counted as 832, 2026-10-30): the cycle records and sells every unit delivered, not the first part.</li>
 * </ul>
 */
final class Relist1102MenuCases {

    private Relist1102MenuCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "2798-menu-ah-relist", c -> relist(c, false));
        MenuSuite.test(s, "2799-menu-ah-relist-stop", c -> relist(c, true));
        MenuSuite.test(s, "2800-menu-ah-menudump", Relist1102MenuCases::menuDump);
        MenuSuite.test(s, "2801-menu-bzflip-split-delivery", Relist1102MenuCases::splitDelivery);
    }

    static boolean newMod(Session c) {
        if (!Mod.has("ahflip.AhRelistMacro") && !Mod.has("ahflip.AhMenuRecorder")) {
            c.note("mod before ahrelist-1101: nothing to check");
            return false;
        }
        return true;
    }

    static final String AH = "Auction House";
    static final String PANE = "minecraft:black_stained_glass_pane";

    static String stack(String key) {
        return "minecraft:paper[minecraft:custom_data={id:\"" + key + "\"}]";
    }

    /** Two plain items (no stars, no recomb, no pet) with a lowest BIN, from the fixture AH. {key, lowest} pairs. */
    static List<Object[]> twoMarkets(Session c) {
        AuctionHouseCases.feedApi(c);
        return c.onClient(mc -> {
            Mod.staticCall("ahflip.AhMarketIndex", "rebuild", Mod.staticCall("auction.AuctionHouseApi", "getListings"));
            @SuppressWarnings("unchecked")
            Map<String, Object> markets = (Map<String, Object>) Mod.field("ahflip.AhMarketIndex", "markets");
            List<Object[]> out = new ArrayList<>();
            for (Map.Entry<String, Object> e : markets.entrySet()) {
                String key = e.getKey();
                if (key.contains("*") || key.contains("+") || key.startsWith("PET_") || !key.matches("[A-Z0-9_]{1,60}")) {
                    continue;
                }
                long lowest = ((Number) Mod.call(e.getValue(), "lowest")).longValue();
                if (lowest >= 1_000 && lowest < 1_000_000_000L) {
                    out.add(new Object[]{key, lowest});
                    if (out.size() == 2) {
                        break;
                    }
                }
            }
            return out;
        });
    }

    static JsonObject rebind(JsonObject action, JsonObject main) {
        JsonObject rb = new JsonObject();
        rb.addProperty("name", "ah");
        rb.add("menu", main);
        action.add("rebind", rb);
        return action;
    }

    static JsonObject mainMenu(JsonObject bids, JsonObject manage) {
        Map<Integer, JsonObject> slots = new HashMap<>();
        Map<Integer, JsonObject> on = new HashMap<>();
        slots.put(11, FlipCroesusCases.item("minecraft:gold_block", "§6Browse Auction House"));
        slots.put(13, FlipCroesusCases.item("minecraft:golden_carrot", "§aView Bids"));
        slots.put(15, FlipCroesusCases.item("minecraft:golden_horse_armor", "§aManage Auctions"));
        on.put(13, FlipCroesusCases.open(bids));
        on.put(15, FlipCroesusCases.open(manage));
        return FlipCroesusCases.menu(AH, 4, slots, on);
    }

    static void relist(Session c, boolean stopInSign) throws Exception {
        if (!newMod(c)) {
            return;
        }
        if (!Mod.isCheat()) {
            boolean active = c.onClient(mc -> {
                Object cfg = Mod.staticCall("ahflip.AhFlipConfig", "getInstance");
                Mod.call(cfg, "setAutoRelist", true);
                Mod.call(cfg, "setFlipperEnabled", true);
                boolean a = (Boolean) Mod.call(cfg, "isRelistActive");
                Mod.call(cfg, "setAutoRelist", false);
                Mod.call(cfg, "setFlipperEnabled", false);
                return a;
            });
            c.check(!active, "the legit jar's Auto Relist is active");
            c.note("legit jar: Auto Relist never active");
            return;
        }
        MenuKit.reset(c);
        c.server().command("clear @a");
        boolean gate = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled"));
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            LogTap.install();
            List<Object[]> m = twoMarkets(c);
            c.check(m.size() == 2, "two plain items with a BIN in the fixture: " + m.size());
            if (m.size() < 2) {
                return;
            }
            String bought = (String) m.get(0)[0];
            String expired = (String) m.get(1)[0];
            long expiredLowest = (Long) m.get(1)[1];
            long sellAt = 4_321_000L;
            c.ctx().runOnClient(mc -> {
                Mod.staticCall("util.SkyblockGate", "setEnabled", false);
                Mod.staticCall("ahflip.AhRelistMacro", "resetForTest");
                Mod.staticCall("ahflip.AhPriceCopier", "resetForTest");
                // What the buying macro leaves behind after a buy: the flip's sale price and the item to relist.
                Mod.staticCall("ahflip.AhPriceCopier", "remember", bought, sellAt);
                Mod.staticCall("ahflip.AhPriceCopier", "remember", expired, 99_000_000L); // an old price that did not sell
                Mod.staticCall("ahflip.AhRelistMacro", "noteBoughtForTest", bought, "Paper");
            });
            // The fake Auction House. After each claim the next /ah shows the page without it (rebind).
            Map<Integer, JsonObject> backOnly = new HashMap<>();
            JsonObject createConfirm = FlipCroesusCases.menu("Confirm BIN Auction", 3, Map.of(
                    11, FlipCroesusCases.item("minecraft:green_terracotta", "§aConfirm"),
                    15, FlipCroesusCases.item("minecraft:red_terracotta", "§cCancel")),
                    Map.of(11, FlipCroesusCases.closeWith("BIN Auction started for Paper!")));
            // After the price sign: the same page with the item kept and the price as typed.
            JsonObject createPriced = FlipCroesusCases.menu("Create BIN Auction", 6, Map.of(
                    31, FlipCroesusCases.item("minecraft:gold_ingot", "§6Item Price", "§7Buy it now: §6{price} coins"),
                    29, FlipCroesusCases.item("minecraft:green_terracotta", "§aCreate BIN Auction"),
                    33, FlipCroesusCases.item("minecraft:clock", "§aAuction Duration", "§7Duration: §e6 Hours")),
                    Map.of(29, FlipCroesusCases.open(createConfirm)));
            createPriced.addProperty("listSlot", 13);
            createPriced.addProperty("keepListed", true);
            JsonObject sign = new JsonObject();
            JsonArray lines = new JsonArray();
            lines.add("");
            lines.add("^^^^^^^^^^^^^^^");
            lines.add("Your auction");
            lines.add("starting bid");
            sign.add("lines", lines);
            sign.add("then", createPriced);
            JsonObject signAction = new JsonObject();
            signAction.add("sign", sign);
            JsonObject create = FlipCroesusCases.menu("Create BIN Auction", 6, Map.of(
                    13, FlipCroesusCases.item("minecraft:stone_button", "§eClick an item in your inventory!"),
                    31, FlipCroesusCases.item("minecraft:gold_ingot", "§6Item Price", "§7Buy it now: §60 coins"),
                    29, FlipCroesusCases.item("minecraft:red_terracotta", "§cCreate BIN Auction"),
                    33, FlipCroesusCases.item("minecraft:clock", "§aAuction Duration")),
                    Map.of(31, signAction));
            create.addProperty("listSlot", 13);
            JsonObject manageEmpty = FlipCroesusCases.menu("Manage Auctions", 6, Map.of(
                    24, FlipCroesusCases.item("minecraft:golden_horse_armor", "§aCreate Auction"),
                    49, FlipCroesusCases.item("minecraft:arrow", "§aGo Back")),
                    Map.of(24, FlipCroesusCases.open(create)));
            JsonObject bidsEmpty = FlipCroesusCases.menu("Your Bids", 6, Map.of(
                    49, FlipCroesusCases.item("minecraft:arrow", "§aGo Back")), backOnly);
            JsonObject mainDone = mainMenu(bidsEmpty, manageEmpty);
            JsonObject manageClaimAll = rebind(FlipCroesusCases.closeWith("You collected 2,000,000 coins from selling Paper to Bob in an auction!"), mainDone);
            JsonArray give = new JsonArray();
            give.add(FlipCroesusCases.item(stack(expired), "§fExpired Paper"));
            manageClaimAll.add("give", give);
            JsonObject manage = FlipCroesusCases.menu("Manage Auctions", 6, Map.of(
                    10, FlipCroesusCases.item("minecraft:paper", "§fSold Paper", "§7Buyer: §bBob", "", "§7Status: §aSold!"),
                    11, FlipCroesusCases.item(stack(expired), "§fExpired Paper", "§7Status: §cExpired!"),
                    22, FlipCroesusCases.item("minecraft:cauldron", "§aClaim All"),
                    24, FlipCroesusCases.item("minecraft:golden_horse_armor", "§aCreate Auction"),
                    49, FlipCroesusCases.item("minecraft:arrow", "§aGo Back")),
                    Map.of(22, manageClaimAll, 24, FlipCroesusCases.open(create)));
            JsonObject mainClaimed = mainMenu(bidsEmpty, manage);
            // Go Back just closes (a spec cannot hold the main page that holds it: the JSON would be a cycle); the
            // relist then opens /ah again, as it does after any closed page.
            JsonObject close = new JsonObject();
            close.addProperty("close", true);
            bidsEmpty.getAsJsonObject("on").add("49", close);
            JsonObject collect = rebind(FlipCroesusCases.closeWith("You claimed Paper from Alice's auction!"), mainClaimed);
            JsonArray giveWon = new JsonArray();
            giveWon.add(FlipCroesusCases.item(stack(bought), "§fPaper"));
            collect.add("give", giveWon);
            JsonObject view = FlipCroesusCases.menu("BIN Auction View", 6, Map.of(
                    13, FlipCroesusCases.item(stack(bought), "§fPaper"),
                    31, FlipCroesusCases.item("minecraft:gold_nugget", "§6Collect Auction", "§7Price: §61,000 coins"),
                    49, FlipCroesusCases.item("minecraft:arrow", "§aGo Back")),
                    Map.of(31, collect));
            JsonObject bids = FlipCroesusCases.menu("Your Bids", 6, Map.of(
                    10, FlipCroesusCases.item(stack(bought), "§fPaper", "§7Seller: §bAlice", "", "§7Status: §aEnded!"),
                    49, FlipCroesusCases.item("minecraft:arrow", "§aGo Back")),
                    Map.of(10, FlipCroesusCases.open(view)));
            JsonObject main = mainMenu(bids, manage);
            c.hx().call("menu.onCommand", Hx.args("name", "ah", "menu", main));

            cfg.set("ahflip.AhFlipConfig", "CopierUndercut", 1L)
                    .set("ahflip.AhFlipConfig", "CopierOwnPrice", true)
                    .set("ahflip.AhFlipConfig", "MinSellPrice", 0L)
                    .set("ahflip.AhFlipConfig", "RunMode", Mod.enumValue("ahflip.AhFlipConfig$RunMode", "BACKGROUND"))
                    .set("ahflip.AhFlipConfig", "FlipperEnabled", true)
                    .set("ahflip.AhFlipConfig", "AutoRelist", true);
            c.ctx().runOnClient(mc -> Mod.staticCall("ahflip.AhRelistMacro", "requestNow"));

            if (stopInSign) {
                c.waitUntil("the price sign to open", mc -> McCompat.screen(mc) instanceof AbstractSignEditScreen, 1200);
                c.ctx().runOnClient(mc -> Mod.set("macrostop.MacroStopConfig", "setEmergencyCancelKeyCode", 74));
                c.ctx().getInput().pressKey(dev.testkit.compat.McKeys.nat(74)); // J
                c.waitUntil("the relist to stop on the key", mc -> !(Boolean) Mod.staticCall("ahflip.AhRelistMacro",
                        "isRunning"), 40);
                c.ctx().runOnClient(mc -> {
                    Mod.set("macrostop.MacroStopConfig", "setEmergencyCancelKeyCode", -1);
                    Mod.staticCall("macrostop.MacroStop", "release", "testkit");
                });
                c.ctx().waitTicks(40);
                String status = status(c);
                List<String> typed = signLines(c);
                c.check(status.contains("|false|IDLE|") || status.contains("|true|IDLE|"), "status " + status);
                c.check(status.contains("Emergency Cancel Key"), "stop reason: " + status);
                c.check(typed.stream().noneMatch(l -> !l.isEmpty()), "a price reached the server: " + typed);
                c.check(c.events("sign.opened").size() == 1, "sign prompts: " + c.events("sign.opened").size());
                boolean signOpen = c.onClient(mc -> McCompat.screen(mc) instanceof AbstractSignEditScreen);
                c.check(!signOpen, "the sign is still open");
                c.note("stopped in the sign: " + status + "; sign lines sent " + typed + "; clicks " + FlipCroesusCases.allClicks(c));
                return;
            }

            c.waitUntil("the relist cycle to finish", mc -> {
                String s = String.valueOf(Mod.staticCall("ahflip.AhRelistMacro", "statusForTest"));
                return s.contains("|IDLE|") && !s.endsWith("|");
            }, 2400);
            String status = status(c);
            List<String> typed = signLines(c);
            List<String> listed = new ArrayList<>();
            for (JsonObject e : c.events("menu.list")) {
                listed.add(e.get("skyblockId").getAsString());
            }
            long wantBought = sellAt - 1;
            long wantExpired = expiredLowest - 1;
            c.check(status.startsWith("2|"), "listed " + status + "; clicks " + FlipCroesusCases.allClicks(c)
                    + "; commands " + c.commands());
            c.check(c.commands().contains("ah"), "server commands " + c.commands());
            c.check(FlipCroesusCases.clicksOn(c, "Your Bids").contains(10), "Your Bids clicks " + FlipCroesusCases.clicksOn(c, "Your Bids"));
            c.check(FlipCroesusCases.clicksOn(c, "BIN Auction View").equals(List.of(31)),
                    "BIN Auction View clicks " + FlipCroesusCases.clicksOn(c, "BIN Auction View"));
            c.check(FlipCroesusCases.clicksOn(c, "Manage Auctions").contains(22),
                    "Manage Auctions clicks " + FlipCroesusCases.clicksOn(c, "Manage Auctions"));
            c.check(listed.equals(List.of(bought, expired)), "items put up " + listed + ", want " + bought + ", " + expired);
            c.check(typed.equals(List.of(Long.toString(wantBought), Long.toString(wantExpired))),
                    "prices typed " + typed + ", want " + wantBought + " (sale price - 1), " + wantExpired + " (lowest BIN - 1)");
            c.check(FlipCroesusCases.clicksOn(c, "Create BIN Auction").stream().filter(s -> s == 29).count() == 2,
                    "Create BIN Auction clicks " + FlipCroesusCases.clicksOn(c, "Create BIN Auction"));
            c.check(FlipCroesusCases.clicksOn(c, "Confirm BIN Auction").equals(List.of(11, 11)),
                    "Confirm BIN Auction clicks " + FlipCroesusCases.clicksOn(c, "Confirm BIN Auction"));
            c.note("cycle: " + status + "; put up " + listed + " at " + typed + "; clicks " + FlipCroesusCases.allClicks(c));
        } finally {
            c.ctx().runOnClient(mc -> {
                Mod.staticCall("util.SkyblockGate", "setEnabled", gate);
                Mod.staticCall("ahflip.AhRelistMacro", "resetForTest");
                Mod.staticCall("ahflip.AhPriceCopier", "resetForTest");
                Mod.staticCall("ahflip.AhFlipper", "resetForTest");
                if (McCompat.screen(mc) != null) {
                    McCompat.setScreen(mc, null);
                }
            });
            c.hx().call("menu.onCommand", Hx.args("name", "ah", "menu", JsonNull.INSTANCE));
            c.server().command("clear @a");
            MenuKit.reset(c);
        }
    }

    static String status(Session c) {
        return c.onClient(mc -> String.valueOf(Mod.staticCall("ahflip.AhRelistMacro", "statusForTest")));
    }

    /** Line 1 of every sign update the server received. */
    static List<String> signLines(Session c) {
        List<String> out = new ArrayList<>();
        for (JsonObject e : c.events("sign.update")) {
            out.add(e.getAsJsonArray("lines").get(0).getAsString());
        }
        return out;
    }

    // ---- 2801 -------------------------------------------------------------------------------------------------------

    static void splitDelivery(Session c) throws Exception {
        if (BazaarFlipCustomCases.cheatOnly(c) != null || !Mod.has("bazaarflip.BazaarStackSizes")) {
            return;
        }
        BazaarFlipCustomCases.Product p = BazaarFlipCustomCases.MAGMA;
        double each = 300.0;
        double npc = 500.0;
        try (AutoCloseable w = BazaarFlipCustomCases.world(c, p, 10_000, each, npc, 0, 10, false)) {
            c.ctx().runOnClient(mc -> Mod.staticCall("bazaarflip.BazaarStackSizes", "clearLearnedForTest"));
            JsonObject page = BazaarFlipCustomCases.instant(p, p.instantTitle(), each, "71,680",
                    BazaarFlipCustomCases.Sign.normal(), 1);
            // Both purchases arrive in two parts, the second six server ticks (300 ms) after the first: Custom Amount's,
            // and Fill my inventory!'s (2,304, the page's own amount; his Gravel was bought that way).
            JsonObject amount = page.getAsJsonObject("on").getAsJsonObject("16").getAsJsonObject("sign")
                    .getAsJsonObject("amount");
            amount.addProperty("splitTicks", 6);
            JsonObject fill = amount.deepCopy();
            fill.remove("reopen");
            fill.remove("max");
            fill.remove("confirm");
            fill.addProperty("n", 2304);
            JsonObject fillAction = new JsonObject();
            fillAction.add("deliver", fill);
            page.getAsJsonObject("on").add("14", fillAction);
            c.hx().call("menu.onCommand", Hx.args("name", "bz", "menu", BazaarFlipCustomCases.bazaar(p, page)));
            c.hx().call("menu.onCommand", Hx.args("name", "trades", "menu", BazaarFlipCustomCases.trades(npc, 0, null)));
            int before = BazaarFlipCustomCases.profitCycles(c);
            BazaarFlipCustomCases.startAndAwaitSale(c, before, 1500);
            FlipCroesusCases.requestStop(c);
            List<String> amounts = BazaarFlipCustomCases.signAmounts(c);
            int gives = c.events("menu.give").size();
            int given = BazaarFlipCustomCases.given(c);
            Object cyc = BazaarFlipCustomCases.cycleAt(c, before);
            c.check(given > 64, "delivered " + given + " (need more than a stack to split); typed " + amounts);
            c.check(gives > 1, "the delivery came in " + gives + " stack(s)");
            c.check(cyc != null && BazaarFlipCustomCases.units(cyc, "bought") == given, "record " + cyc + ", delivered " + given);
            c.check(BazaarFlipCustomCases.sold(c) == given, "sold " + BazaarFlipCustomCases.sold(c) + " of " + given);
            c.note("typed " + amounts + ", delivered " + given + " in two parts, record " + cyc + ", sold " + BazaarFlipCustomCases.sold(c));
        }
    }
}
