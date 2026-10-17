package dev.testkit.gametest.menu;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Hx;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 761-768 (2026-10-11): the Bazaar flipper's whole cycle against Hypixel-shaped Bazaar and Trades menus, and Auto
 * Croesus going back into a claimed run to spend a Dungeon Chest Key. Every case proves the SERVER received the clicks
 * (menu.click by title and slot, sell.sold, menu.give, menu.entity), not just that the mod meant to send them.
 */
final class FlipCroesusCases {

    private FlipCroesusCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "761-menu-bzflip-cycle-reskin-off", c -> flipCycle(c, false));
        MenuSuite.test(s, "762-menu-bzflip-cycle-reskin-on", c -> flipCycle(c, true));
        MenuSuite.test(s, "763-menu-bzflip-no-cookie", FlipCroesusCases::flipNoCookie);
        MenuSuite.test(s, "764-menu-bzflip-price-guard", FlipCroesusCases::flipPriceGuard);
        MenuSuite.test(s, "766-menu-croesus-key-revisit", c -> croesus(c, true));
        MenuSuite.test(s, "767-menu-croesus-no-key-chest", c -> croesus(c, false));
    }

    // ---- small JSON builders ------------------------------------------------------------------------------------

    static JsonObject item(String item, String name, String... lore) {
        JsonObject o = new JsonObject();
        o.addProperty("item", item);
        o.addProperty("name", name);
        if (lore.length > 0) {
            JsonArray a = new JsonArray();
            for (String l : lore) {
                a.add(l);
            }
            o.add("lore", a);
        }
        return o;
    }

    static JsonObject menu(String title, int rows, Map<Integer, JsonObject> slots, Map<Integer, JsonObject> on) {
        JsonObject m = new JsonObject();
        m.addProperty("title", title);
        m.addProperty("rows", rows);
        m.addProperty("fill", true);
        JsonObject s = new JsonObject();
        slots.forEach((k, v) -> s.add(String.valueOf(k), v));
        m.add("slots", s);
        JsonObject o = new JsonObject();
        on.forEach((k, v) -> o.add(String.valueOf(k), v));
        m.add("on", o);
        return m;
    }

    static JsonObject open(JsonObject spec) {
        JsonObject a = new JsonObject();
        a.add("open", spec);
        return a;
    }

    static JsonObject closeWith(String chat) {
        JsonObject a = new JsonObject();
        a.addProperty("close", true);
        a.addProperty("chat", chat);
        return a;
    }

    /** menu.click events the server recorded on a menu titled {@code title}, as slot numbers. */
    static List<Integer> clicksOn(Session c, String title) {
        List<Integer> out = new ArrayList<>();
        for (JsonObject e : c.events("menu.click")) {
            if (e.get("title").getAsString().equals(title)) {
                out.add(e.get("slot").getAsInt());
            }
        }
        return out;
    }

    static String allClicks(Session c) {
        StringBuilder sb = new StringBuilder();
        for (JsonObject e : c.events("menu.click")) {
            sb.append(e.get("title").getAsString()).append('#').append(e.get("slot").getAsInt()).append('/')
                    .append(e.get("input").getAsString()).append("; ");
        }
        return sb.toString();
    }

    // ---- Bazaar flipper -----------------------------------------------------------------------------------------

    static final String PRODUCT = "minecraft:magma_cream[minecraft:custom_data={id:\"LUMP_OF_MAGMA\"}]";
    static final String SEARCH_TITLE = "Bazaar ➜ \"Lump of Magma\"";
    static final String PRODUCT_TITLE = "Mining ➜ Lump of Magma";
    static final String INSTANT_TITLE = "Lump of Magma ➜ Instant Buy";

    /** Hypixel's Bazaar for one product: the /bz search page (layout from killer560's 2026-10-08 dump), the product
     *  page and the Instant Buy page, whose options cost {@code each} coins a unit. */
    static JsonObject bazaar(double each) {
        JsonObject bought64 = new JsonObject();
        JsonArray give = new JsonArray();
        JsonObject stack = item(PRODUCT, "§aLump of Magma");
        stack.addProperty("count", 64);
        give.add(stack);
        bought64.add("give", give);
        bought64.addProperty("chat", String.format("[Bazaar] Bought 64x Lump of Magma for %,.0f coins!", 64 * each));
        JsonObject bought1 = new JsonObject();
        JsonObject one = item(PRODUCT, "§aLump of Magma");
        bought1.add("give", one);
        bought1.addProperty("chat", String.format("[Bazaar] Bought 1x Lump of Magma for %,.0f coins!", each));
        // Since 2026-10-16 the flipper buys the exact amount: 128 is no preset, so Custom Amount's sign (HxSigns) gets it.
        JsonObject custom = new JsonObject();
        JsonObject sign = new JsonObject();
        JsonArray lines = new JsonArray();
        for (String l : BazaarFlipCustomCases.SIGN_LINES) {
            lines.add(l);
        }
        sign.add("lines", lines);
        JsonObject amount = new JsonObject();
        amount.addProperty("max", 71_680);
        amount.addProperty("each", each);
        amount.add("unit", item(PRODUCT, "§aLump of Magma"));
        amount.addProperty("name", "Lump of Magma");
        amount.addProperty("stack", 64);
        sign.add("amount", amount);
        custom.add("sign", sign);
        JsonObject instant = menu(INSTANT_TITLE, 4, Map.of(
                10, item("minecraft:magma_cream", "§aBuy only one!", "§7Amount: §a1§7x", String.format("§7Price: §6%,.0f coins", each)),
                12, item("minecraft:magma_cream", "§aBuy a stack!", "§7Amount: §a64§7x", String.format("§7Price: §6%,.0f coins", 64 * each)),
                14, item("minecraft:chest", "§aFill my inventory!", "§7Amount: §a2,304§7x", String.format("§7Price: §6%,.0f coins", 2304 * each)),
                16, item("minecraft:oak_sign", "§aCustom Amount"),
                31, item("minecraft:arrow", "§aGo Back")),
                Map.of(10, bought1, 12, bought64, 14, closeWith("unexpected: Fill my inventory!"), 16, custom));
        JsonObject product = menu(PRODUCT_TITLE, 4, Map.of(
                10, item("minecraft:golden_horse_armor", "§aBuy Instantly", "§7Price per unit: §635.0 coins"),
                11, item("minecraft:hopper", "§6Sell Instantly"),
                13, item(PRODUCT, "§aLump of Magma"),
                15, item("minecraft:map", "§aCreate Buy Order"),
                16, item("minecraft:map", "§6Create Sell Offer"),
                31, item("minecraft:arrow", "§aGo Back")),
                Map.of(10, open(instant)));
        return menu(SEARCH_TITLE, 6, Map.of(
                0, item("minecraft:golden_hoe", "§eFarming"),
                9, item("minecraft:diamond_pickaxe", "§bMining"),
                11, item(PRODUCT, "§aLump of Magma", "§7Buy price: §634.9 coins", "§7Sell price: §630.1 coins"),
                18, item("minecraft:iron_sword", "§cCombat"),
                45, item("minecraft:oak_sign", "§aSearch"),
                47, item("minecraft:chest", "§aSell Inventory Now"),
                49, item("minecraft:arrow", "§aGo Back"),
                50, item("minecraft:book", "§aManage Orders")),
                Map.of(11, open(product)));
    }

    static JsonObject trades() {
        JsonObject m = menu("Trades", 6, Map.of(49, item("minecraft:barrier", "§cClose")), Map.of());
        m.addProperty("sell", true);
        return m;
    }

    /** Hub sidebar with a purse, the tab footer's Cookie Buff, a fixed scan result, and the flipper on. */
    static AutoCloseable flipWorld(Session c, boolean cookie, boolean reskin, double npc) {
        MenuKit.reset(c);
        c.hx().sidebar("§e§lSKYBLOCK", "§710/11/26 §8m12AB", "", " §7⏣ §bVillage", "", "Purse: §61,000,000", "",
                "§ewww.hypixel.net");
        c.hx().tab(List.of("§b§lArea: §7Hub"), null, cookie ? BazaarV3Cases.COOKIE_ON : BazaarV3Cases.COOKIE_OFF);
        boolean gate = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled"));
        MenuKit.Cfg cfg = new MenuKit.Cfg(c);
        cfg.set("bazaarflip.BazaarFlipConfig", "Enabled", true)
                .set("bazaarflip.BazaarFlipConfig", "Speed", 10)
                .set("bazaarflip.BazaarFlipConfig", "TargetPurse", 0L)
                // One switch since 2026-10-11: the Bazaar feature is the browser AND the reskin.
                .set("auction.AuctionConfig", "BazaarEnabled", reskin);
        c.ctx().runOnClient(mc -> {
            Mod.staticCall("util.SkyblockGate", "setEnabled", false);
            Mod.staticCall("bazaarflip.BazaarFlipScanner", "setTestCandidateForTest", "LUMP_OF_MAGMA", "Lump of Magma",
                    64, 128, 35.0, npc);
        });
        c.ctx().waitTicks(10);
        return () -> {
            c.ctx().runOnClient(mc -> {
                Mod.staticCall("bazaarflip.BazaarFlipFeature", "requestStop");
                Mod.staticCall("bazaarflip.BazaarFlipScanner", "clearTestCandidateForTest");
                Mod.staticCall("util.SkyblockGate", "setEnabled", gate);
            });
            c.hx().call("menu.onCommand", Hx.args("name", "bz", "menu", JsonNull.INSTANCE));
            c.hx().call("menu.onCommand", Hx.args("name", "trades", "menu", JsonNull.INSTANCE));
            MenuKit.reset(c);
            c.hx().sidebarClear();
            c.hx().tab(List.of(), null, "");
            c.ctx().runOnClient(mc -> mc.player.getInventory().clearContent());
            cfg.close();
        };
    }

    static String start(Session c) {
        return c.onClient(mc -> (String) Mod.staticCall("bazaarflip.BazaarFlipFeature", "start"));
    }

    static void flipCycle(Session c, boolean reskin) throws Exception {
        try (AutoCloseable w = flipWorld(c, true, reskin, 50.0)) {
            c.hx().call("menu.onCommand", Hx.args("name", "bz", "menu", bazaar(35.0)));
            c.hx().call("menu.onCommand", Hx.args("name", "trades", "menu", trades()));
            Object purse = c.onClient(mc -> Mod.staticCall("bazaarflip.BazaarFlipFeature", "readPurse"));
            c.check(purse != null && ((Double) purse) == 1_000_000.0, "purse read as " + purse);
            String refusal = start(c);
            if (!Mod.isCheat()) {
                c.check(refusal != null, "the legit jar started the flipper");
                c.ctx().waitTicks(40);
                c.check(c.commands().stream().noneMatch(x -> x.startsWith("bz") || x.startsWith("trades")),
                        "the legit jar sent " + c.commands());
                c.check(c.events("menu.click").isEmpty(), "the legit jar clicked: " + allClicks(c));
                c.note("legit jar: start refused (\"" + refusal + "\"), no /bz, no /trades, no clicks");
                return;
            }
            c.check(refusal == null, "start refused: " + refusal);
            boolean[] reskinHid = {false};
            java.util.Set<String> drawn = new java.util.LinkedHashSet<>();
            c.waitUntil("the flipper to finish one cycle", mc -> {
                if (McCompat.screen(mc) instanceof AbstractContainerScreen<?> s
                        && (Boolean) Mod.staticCall("bazaar.BazaarReskin", "isHiding", s)) {
                    reskinHid[0] = true;
                    drawn.add(String.valueOf(Mod.staticCall("bazaar.BazaarReskin", "kindForTest")));
                }
                return ((Number) Mod.staticCall("bazaarflip.BazaarFlipFeature", "getCycles")).intValue() >= 1;
            }, 600);
            c.ctx().runOnClient(mc -> Mod.staticCall("bazaarflip.BazaarFlipFeature", "requestStop"));
            List<String> cmds = c.commands();
            c.check(cmds.contains("bz Lump of Magma"), "no /bz Lump of Magma, commands " + cmds);
            c.check(cmds.contains("trades"), "no /trades, commands " + cmds);
            c.check(clicksOn(c, SEARCH_TITLE).equals(List.of(11)), "search clicks " + clicksOn(c, SEARCH_TITLE));
            c.check(clicksOn(c, PRODUCT_TITLE).equals(List.of(10)), "product clicks " + clicksOn(c, PRODUCT_TITLE));
            c.check(clicksOn(c, INSTANT_TITLE).equals(List.of(16)), "instant-buy clicks "
                    + clicksOn(c, INSTANT_TITLE) + " (want Custom Amount once for 128 units)");
            List<String> typed = BazaarFlipCustomCases.signAmounts(c);
            c.check(typed.equals(List.of("128")), "the amount sign sent " + typed);
            int given = c.events("menu.give").stream().mapToInt(e -> e.get("count").getAsInt()).sum();
            c.check(given == 128, "the server delivered " + given + " units");
            List<JsonObject> sold = c.events("sell.sold");
            int soldUnits = sold.stream().mapToInt(e -> e.get("count").getAsInt()).sum();
            c.check(soldUnits == 128, "sold " + soldUnits + " units in " + sold.size() + " sale(s)");
            c.check(sold.stream().allMatch(e -> e.get("skyblockId").getAsString().equals("LUMP_OF_MAGMA")),
                    "sold something else: " + sold);
            long tradesClicks = c.events("menu.click").stream()
                    .filter(e -> e.get("title").getAsString().equals("Trades"))
                    .filter(e -> !e.get("input").getAsString().equals("QUICK_MOVE")).count();
            c.check(tradesClicks == 0, "non-shift clicks in Trades: " + allClicks(c));
            String control;
            if (reskin) {
                // killer560, 2026-10-11: "have reskin work at the same time" - the reskin draws every Bazaar page the
                // flipper walks through while the clicks above still reach the real menu.
                c.check(drawn.containsAll(List.of("SEARCH", "PRODUCT", "INSTANT_BUY")),
                        "the reskin drew only " + drawn + " while the flipper ran");
                control = "; the reskin drew " + drawn + " during the run";
            } else {
                c.check(!reskinHid[0], "the Bazaar is off but its reskin drew " + drawn);
                control = "; Bazaar off: Hypixel's menus throughout";
            }
            c.note("Bazaar " + (reskin ? "ON" : "OFF") + ": server got " + allClicks(c) + " delivered " + given
                    + ", sold " + soldUnits + " in " + sold.size() + " shift-clicks, commands " + cmds + control);
        }
    }

    static void flipNoCookie(Session c) throws Exception {
        if (!Mod.isCheat()) {
            c.note("legit jar: covered by 761");
            return;
        }
        try (AutoCloseable w = flipWorld(c, false, false, 50.0)) {
            String refusal = start(c);
            c.check(refusal != null && refusal.contains("Booster Cookie"), "without a Booster Cookie start said: " + refusal);
            c.ctx().waitTicks(30);
            c.check(c.commands().stream().noneMatch(x -> x.startsWith("bz")), "sent " + c.commands());
            c.note("no Cookie Buff: start refused (\"" + refusal + "\"), no command sent");
        }
    }

    static void flipPriceGuard(Session c) throws Exception {
        if (!Mod.isCheat()) {
            c.note("legit jar: covered by 761");
            return;
        }
        // The scan said 35 each against an NPC 50; the Instant Buy page now asks 55 each: nothing may be bought.
        try (AutoCloseable w = flipWorld(c, true, false, 50.0)) {
            c.hx().call("menu.onCommand", Hx.args("name", "bz", "menu", bazaar(55.0)));
            String refusal = start(c);
            c.check(refusal == null, "start refused: " + refusal);
            c.waitUntil("the flipper to stop", mc -> !(Boolean) Mod.staticCall("bazaarflip.BazaarFlipFeature", "isRunning"), 400);
            c.check(clicksOn(c, PRODUCT_TITLE).equals(List.of(10)), "it never reached Instant Buy: " + allClicks(c));
            c.check(clicksOn(c, INSTANT_TITLE).isEmpty(), "it bought at a loss: " + clicksOn(c, INSTANT_TITLE));
            c.check(c.events("menu.give").isEmpty(), "items were delivered");
            c.check(!c.commands().contains("trades"), "sent /trades with nothing bought");
            c.note("Instant Buy at 55/ea vs NPC 50: reached the page, clicked no option, stopped; " + allClicks(c));
        }
    }

    // ---- Auto Croesus ---------------------------------------------------------------------------------------------

    static final String RUN_TITLE = "Catacombs - Floor VII";

    static JsonObject chest(String name, String reward, String cost, boolean key, boolean opened) {
        List<String> lore = new ArrayList<>(List.of("§7Contents", "§a" + reward, "", "§7Cost", cost));
        if (key) {
            lore.add("§9Dungeon Chest Key");
        }
        if (opened) {
            lore.add("§cAlready opened!");
        }
        return item("minecraft:chest", name, lore.toArray(new String[0]));
    }

    static JsonObject chestScreen(String title, String reward, String cost, boolean key) {
        List<String> lore = new ArrayList<>(List.of("§7Cost", cost));
        if (key) {
            lore.add("§9Dungeon Chest Key");
        }
        return menu(title, 6, Map.of(
                // Hypixel's reward stacks carry their Skyblock id; the valuer prices them by it.
                13, item("minecraft:nether_star[minecraft:custom_data={id:\"" + reward.toUpperCase().replace(' ', '_')
                        + "\"}]", "§a" + reward),
                31, item("minecraft:chest", "§aOpen Reward Chest", lore.toArray(new String[0])),
                49, item("minecraft:arrow", "§aGo Back")),
                Map.of(31, closeWith("§aYou claimed the " + title + "!")));
    }

    static JsonObject croesusMenu(boolean opened, JsonObject runView) {
        JsonObject head = item("minecraft:player_head", "§cThe Catacombs", "§7Floor VII", "",
                opened ? "§aOpened Chest: §cBedrock" : "§cNo chests opened yet!");
        return menu("Croesus", 6, Map.of(10, head, 49, item("minecraft:barrier", "§cClose")), Map.of(10, open(runView)));
    }

    /**
     * A run with a Bedrock chest (profit 2M) and an Obsidian chest. With {@code worthAKey} the Obsidian holds 2M
     * against a 500k cost: 1.3M after a 200k key, over the 500k key floor, so Auto Croesus must claim Bedrock, let
     * Croesus close, right-click the NPC, go back into the same run and claim Obsidian with the key. Without, the
     * Obsidian holds 600k: -100k after the key, so it claims Bedrock and never goes back.
     */
    static void croesus(Session c, boolean worthAKey) throws Exception {
        MenuKit.reset(c);
        Object prices = c.onClient(mc -> Mod.field(Mod.field("rngmeter.RngMeterEngine", "PRICES"), "bazaarSellPrice"));
        @SuppressWarnings("unchecked")
        Map<String, Long> map = (Map<String, Long>) prices;
        Map<String, Long> seeded = Map.of("TESTKIT_RELIC", 3_000_000L, "TESTKIT_IDOL", worthAKey ? 2_000_000L : 600_000L,
                "DUNGEON_CHEST_KEY", 200_000L);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set("croesus.CroesusConfig", "AutoCroesusEnabled", true)
                    .set("croesus.CroesusConfig", "AutoMinProfitK", 100)
                    .set("croesus.CroesusConfig", "AutoKeyMinProfitK", 500)
                    .set("croesus.CroesusConfig", "AutoUseChestKeys", true)
                    .set("croesus.CroesusConfig", "AutoUseKismets", false)
                    .set("croesus.CroesusConfig", "ChestKeyMode", Mod.enumValue("croesus.CroesusConfig$ChestKeyMode", "SET_PROFIT"))
                    .set("croesus.CroesusConfig", "AutoMinDelayMs", 100)
                    .set("croesus.CroesusConfig", "AutoMaxDelayMs", 150);
            c.ctx().runOnClient(mc -> map.putAll(seeded));
            JsonObject keyed = chestScreen("Obsidian Chest", "Testkit Idol", "§6500,000 Coins", true);
            JsonObject runAfter = menu(RUN_TITLE, 6, Map.of(
                    10, chest("§4Bedrock Chest", "Testkit Relic", "§61,000,000 Coins", false, true),
                    11, chest("§5Obsidian Chest", "Testkit Idol", "§6500,000 Coins", true, false),
                    49, item("minecraft:arrow", "§aGo Back")),
                    Map.of(11, open(keyed)));
            JsonObject menuAfter = croesusMenu(true, runAfter);
            JsonObject runBefore = menu(RUN_TITLE, 6, Map.of(
                    10, chest("§4Bedrock Chest", "Testkit Relic", "§61,000,000 Coins", false, false),
                    11, chest("§5Obsidian Chest", "Testkit Idol", "§6500,000 Coins", false, false),
                    49, item("minecraft:arrow", "§aGo Back")),
                    Map.of(10, open(chestScreen("Bedrock Chest", "Testkit Relic", "§61,000,000 Coins", false))));
            // Croesus himself: an armour stand named Croesus beside the player; using it opens the post-claim menu.
            double[] at = c.onClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
            c.hx().stand(at[0] + 1.6, at[1], at[2], "Croesus", "marker", false, "invisible", false);
            c.hx().call("menu.onEntity", Hx.args("name", "Croesus", "menu", menuAfter));
            c.ctx().waitTicks(10);
            c.hx().call("menu.show", croesusMenu(false, runBefore));
            MenuKit.awaitScreen(c, "Croesus", 100);
            c.ctx().runOnClient(mc -> {
                Mod.setField("croesus.AutoCroesusFeature", "armed", true);
                Mod.setField("croesus.AutoCroesusFeature", "armedAtMs", System.currentTimeMillis());
            });
            if (!Mod.isCheat()) {
                c.ctx().waitTicks(60);
                c.check(c.events("menu.click").isEmpty(), "the legit jar clicked: " + allClicks(c));
                c.note("legit jar: armed by hand, Auto Croesus clicked nothing");
                return;
            }
            c.waitUntil("Auto Croesus to finish", mc -> !(Boolean) Mod.staticCall("croesus.AutoCroesusFeature", "isRunning")
                    && c.events("menu.click").size() >= 3, 900);
            List<Integer> menuClicks = clicksOn(c, "Croesus");
            List<Integer> runClicks = clicksOn(c, RUN_TITLE);
            List<Integer> bedrock = clicksOn(c, "Bedrock Chest");
            List<Integer> obsidian = clicksOn(c, "Obsidian Chest");
            int npcUses = c.events("menu.entity").size();
            c.check(bedrock.equals(List.of(31)), "Bedrock claim clicks " + bedrock + "; all " + allClicks(c));
            c.check(npcUses >= 1, "Croesus was never re-opened from the NPC after the claim closed him");
            if (worthAKey) {
                c.check(menuClicks.equals(List.of(10, 10)), "run head clicks " + menuClicks + " (want the run twice)");
                c.check(runClicks.equals(List.of(10, 11)), "run view clicks " + runClicks + " (want Bedrock, then Obsidian)");
                c.check(obsidian.equals(List.of(31)), "Obsidian (keyed) claim clicks " + obsidian);
            } else {
                c.check(menuClicks.equals(List.of(10)), "run head clicks " + menuClicks + " (the run must not be re-entered)");
                c.check(runClicks.equals(List.of(10)), "run view clicks " + runClicks);
                c.check(obsidian.isEmpty(), "spent a key on a losing chest: " + obsidian);
            }
            Object pending = c.onClient(mc -> Mod.staticCall("croesus.AutoCroesusFeature", "pendingKeyRevisitForTest"));
            c.check(pending == null, "a revisit is still pending: " + pending);
            c.note((worthAKey ? "Obsidian worth 1.3M after the key: " : "Obsidian worth -100k after the key: ")
                    + "server got " + allClicks(c) + "NPC re-opened " + npcUses + "x");
        } finally {
            c.ctx().runOnClient(mc -> {
                Mod.staticCall("croesus.AutoCroesusFeature", "requestStop");
                seeded.keySet().forEach(map::remove);
            });
            c.hx().call("menu.onEntity", Hx.args("name", "Croesus", "menu", JsonNull.INSTANCE));
            c.hx().removeStands();
            MenuKit.reset(c);
        }
    }
}
