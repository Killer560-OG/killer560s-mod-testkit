package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Hx;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 773-776 (croesus-price, 2026-10-11). killer560: "auto croesus is mispricing chests ... on an m4 run with a spirit
 * stone, fuming potato book, thorn shard, 66 wither essence, and 94 undead essence. It thinks this chest is worth 35k
 * when it is actually worth about 900k-1m", and "croesus should have a profit option by insta sell or sell offer".
 * <p>
 * Prices are the live Bazaar's quick_status of 2026-10-11 (sellPrice = insta-sell, buyPrice = sell offer), injected
 * with {@code HypixelMarketPrices.putBazaarPriceForTest}:
 * <pre>
 *   Spirit Stone        SPIRIT_DECOY         110,013 / 891,095
 *   Fuming Potato Book  FUMING_POTATO_BOOK 1,161,842 / 1,293,156
 *   Thorn Shard         SHARD_THORN           44,252 / 145,094
 *   Wither Essence x66  ESSENCE_WITHER         3,325 / 3,451 each
 *   Undead Essence x94  ESSENCE_UNDEAD           631 / 693 each
 * </pre>
 * <ul>
 *   <li>773: Hypixel's M4 run view, those five items in the chest's lore: every line maps to its id and the total is
 *       exact, in both Price Modes (the overlay's own value).</li>
 *   <li>774: the same chest opened (the reward stacks carry their Skyblock ids, essences named "x66"): the same totals.</li>
 *   <li>775: Auto Croesus at a 2,000,000 cost: Insta-Sell (-405k) skips it, Sell Offer (+622k) claims it - the server
 *       gets the claim only in the second.</li>
 *   <li>776: Price Mode is saved and reloaded, and a config from before it follows the RNG Meter's old Instant-Buy
 *       setting.</li>
 * </ul>
 */
final class CroesusPriceCases {

    private CroesusPriceCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "773-menu-croesus-m4-run-view-prices", CroesusPriceCases::runView);
        MenuSuite.test(s, "774-menu-croesus-m4-chest-prices", CroesusPriceCases::chestScreen);
        MenuSuite.test(s, "775-menu-croesus-price-mode-decides", CroesusPriceCases::decides);
        MenuSuite.test(s, "776-menu-croesus-price-mode-saved", CroesusPriceCases::saved);
    }

    /** id -> {insta-sell, sell offer}. */
    static final Map<String, long[]> PRICES = new LinkedHashMap<>();
    /** id -> amount in the chest. */
    static final Map<String, Integer> AMOUNTS = new LinkedHashMap<>();

    static {
        PRICES.put("SPIRIT_DECOY", new long[]{110_013, 891_095});
        PRICES.put("FUMING_POTATO_BOOK", new long[]{1_161_842, 1_293_156});
        PRICES.put("SHARD_THORN", new long[]{44_252, 145_094});
        PRICES.put("ESSENCE_WITHER", new long[]{3_325, 3_451});
        PRICES.put("ESSENCE_UNDEAD", new long[]{631, 693});
        AMOUNTS.put("SPIRIT_DECOY", 1);
        AMOUNTS.put("FUMING_POTATO_BOOK", 1);
        AMOUNTS.put("SHARD_THORN", 1);
        AMOUNTS.put("ESSENCE_WITHER", 66);
        AMOUNTS.put("ESSENCE_UNDEAD", 94);
    }

    static long expected(boolean sellOffer) {
        long t = 0;
        for (String id : PRICES.keySet()) {
            t += PRICES.get(id)[sellOffer ? 1 : 0] * AMOUNTS.get(id);
        }
        return t; // 1,594,871 insta-sell; 2,622,253 sell offer
    }

    static final String RUN_TITLE = "Master Catacombs - Floor IV";

    /** The Obsidian chest's lore as Hypixel colors it (names in rarity color, essence counts in dark gray). */
    static JsonObject obsidianHead(String cost) {
        return FlipCroesusCases.item("minecraft:player_head", "§5Obsidian Chest", "§7Contents", "§9Spirit Stone",
                "§5Fuming Potato Book", "§5Thorn Shard", "§dWither Essence §8x66", "§dUndead Essence §8x94", "",
                "§7Cost", cost, "", "§eClick to open this chest!");
    }

    static JsonObject runView(String cost) {
        return FlipCroesusCases.menu(RUN_TITLE, 6, Map.of(
                11, obsidianHead(cost),
                49, FlipCroesusCases.item("minecraft:arrow", "§aGo Back")), new java.util.HashMap<>(Map.of(49,
                FlipCroesusCases.closeWith("§7(back)"))));
    }

    static JsonObject stack(String item, String id, String name, int count) {
        JsonObject o = FlipCroesusCases.item(item + "[minecraft:custom_data={id:\"" + id + "\"}]", name);
        if (count > 1) {
            o.addProperty("count", count);
        }
        return o;
    }

    static JsonObject chestScreen(String cost) {
        return FlipCroesusCases.menu("Obsidian Chest", 6, Map.of(
                10, stack("minecraft:player_head", "SPIRIT_DECOY", "§9Spirit Stone", 1),
                11, stack("minecraft:enchanted_book", "FUMING_POTATO_BOOK", "§5Fuming Potato Book", 1),
                12, stack("minecraft:prismarine_shard", "SHARD_THORN", "§5Thorn Shard", 1),
                13, stack("minecraft:player_head", "ESSENCE_WITHER", "§dWither Essence §8x66", 1),
                14, stack("minecraft:player_head", "ESSENCE_UNDEAD", "§dUndead Essence §8x94", 1),
                31, FlipCroesusCases.item("minecraft:chest", "§aOpen Reward Chest", "§7Cost", cost),
                49, FlipCroesusCases.item("minecraft:arrow", "§aGo Back")),
                Map.of(31, FlipCroesusCases.closeWith("§aYou claimed the Obsidian Chest!")));
    }

    /** Prices in, Chest Profit and essences on; returns the undo. */
    static AutoCloseable priced(Session c) {
        MenuKit.reset(c);
        MenuKit.Cfg cfg = new MenuKit.Cfg(c);
        cfg.set("croesus.CroesusConfig", "ChestProfitEnabled", true).set("croesus.CroesusConfig", "IncludeEssence", true)
                .set("croesus.CroesusConfig", "PriceMode", mode("INSTA_SELL"));
        c.ctx().runOnClient(mc -> {
            Object prices = Mod.field("rngmeter.RngMeterEngine", "PRICES");
            PRICES.forEach((id, p) -> Mod.call(prices, "putBazaarPriceForTest", id, p[0], p[1]));
        });
        return () -> {
            c.ctx().runOnClient(mc -> {
                Object prices = Mod.field("rngmeter.RngMeterEngine", "PRICES");
                PRICES.keySet().forEach(id -> Mod.call(prices, "removeBazaarPriceForTest", id));
                Mod.staticCall("croesus.AutoCroesusFeature", "requestStop");
            });
            MenuKit.reset(c);
            cfg.close();
        };
    }

    static Object mode(String name) {
        return Mod.enumValue("croesus.CroesusConfig$PriceMode", name);
    }

    static void setMode(Session c, String name) {
        c.ctx().runOnClient(mc -> Mod.set("croesus.CroesusConfig", "setPriceMode", mode(name)));
    }

    /** Every priced item as "id xN @unit = total", and the chest's value. */
    static String describe(Object chest) {
        List<String> parts = new ArrayList<>();
        for (Object it : (List<?>) Mod.call(chest, "items")) {
            parts.add(Mod.call(it, "id") + " x" + Mod.call(it, "amount") + " @" + Mod.call(it, "unitPrice") + " = "
                    + Mod.call(it, "total"));
        }
        return parts + " value " + Mod.call(chest, "value") + ", profit " + Mod.call(chest, "profit");
    }

    static void checkItems(Session c, Object chest, boolean sellOffer, String where) {
        Map<String, Long> got = new LinkedHashMap<>();
        for (Object it : (List<?>) Mod.call(chest, "items")) {
            got.merge(String.valueOf(Mod.call(it, "id")), ((Number) Mod.call(it, "total")).longValue(), Long::sum);
        }
        for (String id : PRICES.keySet()) {
            long want = PRICES.get(id)[sellOffer ? 1 : 0] * AMOUNTS.get(id);
            c.check(got.containsKey(id) && got.get(id) == want, where + ": " + id + " valued " + got.get(id) + ", want "
                    + want + " (" + describe(chest) + ")");
        }
        long value = ((Number) Mod.call(chest, "value")).longValue();
        c.check(value == expected(sellOffer), where + ": value " + value + ", want " + expected(sellOffer));
    }

    static void runView(Session c) throws Exception {
        try (AutoCloseable w = priced(c)) {
            MenuKit.show(c, runView("§62,000,000 Coins"));
            MenuKit.awaitScreen(c, RUN_TITLE, 100);
            StringBuilder note = new StringBuilder();
            for (String m : new String[]{"INSTA_SELL", "SELL_OFFER"}) {
                setMode(c, m);
                c.waitUntil("the run view valued in " + m, mc -> {
                    List<?> l = (List<?>) Mod.staticCall("croesus.ChestProfitFeature", "currentRunViewChests");
                    return l.size() == 1 && ((Number) Mod.call(l.get(0), "value")).longValue() == expected(m.equals("SELL_OFFER"));
                }, 60);
                Object chest = c.onClient(mc -> ((List<?>) Mod.staticCall("croesus.ChestProfitFeature", "currentRunViewChests")).get(0));
                checkItems(c, chest, m.equals("SELL_OFFER"), "run view " + m);
                note.append(m).append(": ").append(c.<String>onClient(mc -> describe(chest))).append("; ");
            }
            c.note(note.toString());
        }
    }

    static void chestScreen(Session c) throws Exception {
        try (AutoCloseable w = priced(c)) {
            MenuKit.show(c, chestScreen("§62,000,000 Coins"));
            MenuKit.awaitScreen(c, "Obsidian Chest", 100);
            c.ctx().waitTicks(10);
            StringBuilder note = new StringBuilder();
            for (String m : new String[]{"INSTA_SELL", "SELL_OFFER"}) {
                setMode(c, m);
                Object chest = c.onClient(mc -> {
                    AbstractContainerScreen<?> s = (AbstractContainerScreen<?>) McCompat.screen(mc);
                    return Mod.staticCall("croesus.DungeonChestValuer", "fromChestScreen",
                            Mod.enumValue("croesus.DungeonChestValuer$ChestType", "OBSIDIAN"),
                            Mod.staticCall("croesus.ChestProfitFeature", "containerStacks", s));
                });
                checkItems(c, chest, m.equals("SELL_OFFER"), "chest screen " + m);
                note.append(m).append(": ").append(c.<String>onClient(mc -> describe(chest))).append("; ");
            }
            c.note(note.toString());
        }
    }

    static void decides(Session c) throws Exception {
        if (!Mod.isCheat()) {
            c.note("legit jar: no Auto Croesus (773/774 cover the values)");
            return;
        }
        try (AutoCloseable w = priced(c); MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set("croesus.CroesusConfig", "AutoCroesusEnabled", true)
                    .set("croesus.CroesusConfig", "AutoMinProfitK", 100)
                    .set("croesus.CroesusConfig", "AutoUseChestKeys", false)
                    .set("croesus.CroesusConfig", "AutoUseKismets", false)
                    .set("croesus.CroesusConfig", "AutoMinDelayMs", 100)
                    .set("croesus.CroesusConfig", "AutoMaxDelayMs", 150);
            JsonObject view = runView("§62,000,000 Coins");
            view.getAsJsonObject("on").add("11", FlipCroesusCases.open(chestScreen("§62,000,000 Coins")));
            JsonObject head = FlipCroesusCases.item("minecraft:player_head", "§cMaster Mode The Catacombs", "§7Floor IV",
                    "", "§cNo chests opened yet!");
            JsonObject croesus = FlipCroesusCases.menu("Croesus", 6, Map.of(10, head), Map.of(10, FlipCroesusCases.open(view)));
            String[] notes = new String[2];
            int i = 0;
            for (String m : new String[]{"INSTA_SELL", "SELL_OFFER"}) {
                setMode(c, m);
                MenuKit.reset(c);
                long since = c.events("menu.click").size();
                MenuKit.show(c, croesus);
                MenuKit.awaitScreen(c, "Croesus", 100);
                c.ctx().runOnClient(mc -> {
                    Mod.setField("croesus.AutoCroesusFeature", "armed", true);
                    Mod.setField("croesus.AutoCroesusFeature", "armedAtMs", System.currentTimeMillis());
                });
                c.waitUntil("Auto Croesus to start and finish (" + m + ")", mc -> c.events("menu.click").size() > since
                        && !(Boolean) Mod.staticCall("croesus.AutoCroesusFeature", "isRunning"), 400);
                List<JsonObject> clicks = c.events("menu.click").subList((int) since, c.events("menu.click").size());
                List<String> got = new ArrayList<>();
                clicks.forEach(e -> got.add(e.get("title").getAsString() + "#" + e.get("slot").getAsInt()));
                boolean claimed = got.contains("Obsidian Chest#31");
                if (m.equals("SELL_OFFER")) {
                    c.check(claimed, "Sell Offer (+622k over a 100k floor) did not claim: " + got);
                } else {
                    c.check(!claimed && !got.contains(RUN_TITLE + "#11"), "Insta-Sell (-405k) opened or claimed: " + got);
                }
                notes[i++] = m + " -> " + got;
                c.ctx().runOnClient(mc -> Mod.staticCall("croesus.AutoCroesusFeature", "requestStop"));
            }
            c.note("cost 2,000,000, min profit 100k: " + notes[0] + "; " + notes[1]);
        }
    }

    static void saved(Session c) throws Exception {
        Path file = c.onClient(mc -> (Path) Mod.staticCall("util.ModPaths", "config", "killer560smod-croesus.json"));
        byte[] was = Files.exists(file) ? Files.readAllBytes(file) : null;
        boolean rngWas = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg("rngmeter.RngMeterConfig"), "isUseInstantBuyPrice"));
        try {
            c.ctx().runOnClient(mc -> {
                Mod.set("croesus.CroesusConfig", "setPriceMode", mode("SELL_OFFER"));
                Mod.call(Mod.cfg("croesus.CroesusConfig"), "save");
                Mod.set("croesus.CroesusConfig", "setPriceMode", mode("INSTA_SELL"));
                Mod.staticCall("croesus.CroesusConfig", "load");
            });
            String back = c.onClient(mc -> String.valueOf(Mod.call(Mod.cfg("croesus.CroesusConfig"), "getPriceMode")));
            c.check(back.equals("SELL_OFFER"), "saved Sell Offer, reloaded " + back);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "{\"chestProfitEnabled\": true}", StandardCharsets.UTF_8);
            String[] legacy = new String[2];
            int i = 0;
            for (boolean instantBuy : new boolean[]{true, false}) {
                c.ctx().runOnClient(mc -> {
                    Mod.set("rngmeter.RngMeterConfig", "setUseInstantBuyPrice", instantBuy);
                    Mod.staticCall("croesus.CroesusConfig", "load");
                });
                legacy[i++] = c.onClient(mc -> String.valueOf(Mod.call(Mod.cfg("croesus.CroesusConfig"), "getPriceMode")));
            }
            c.check(legacy[0].equals("SELL_OFFER") && legacy[1].equals("INSTA_SELL"),
                    "a config without Price Mode loaded " + legacy[0] + " (RNG instant-buy on) / " + legacy[1] + " (off)");
            c.note("saved/reloaded SELL_OFFER; config from before: RNG Meter instant-buy on -> " + legacy[0] + ", off -> "
                    + legacy[1]);
        } finally {
            if (was == null) {
                Files.deleteIfExists(file);
            } else {
                Files.write(file, was);
            }
            c.ctx().runOnClient(mc -> {
                Mod.set("rngmeter.RngMeterConfig", "setUseInstantBuyPrice", rngWas);
                Mod.staticCall("croesus.CroesusConfig", "load");
            });
        }
    }
}
