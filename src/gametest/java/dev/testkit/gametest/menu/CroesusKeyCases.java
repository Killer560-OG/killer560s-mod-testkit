package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 879-882 (croesus-key, 2026-10-12). killer560: "for hide it needs a way of knowing if something has used a chest key as
 * well or not. If no chest key then it should be different, and if i need to go back and use a chest key it should also
 * have a different highlight."
 * <p>
 * One Croesus page with every run state: 10 unclaimed; 11 opened (Bedrock) whose Obsidian is worth 1.3M after a key
 * (needs a key); 12 opened (Bedrock) whose Obsidian loses money with a key (opened, nothing left); 13 "No more chests to
 * open!" (done with a key); 14 opened but never looked at (needs a look). Runs 11 and 12 are told apart by their players.
 * Their run views are opened once by a real mouse press on the head, as he would, which is what the mod learns from.
 * <ul>
 *   <li>879: the five states read off the heads, each drawn in its own colour (sampled in the shot).</li>
 *   <li>880: Hide Opened Runs hides 12 and 13 only - never 11 (needs a key), 14 (needs a look) or 10.</li>
 *   <li>881: the key floor is Auto Croesus's current one: raised to 2M, run 11 becomes "opened" (and hides).</li>
 *   <li>882: the two new colours are saved and reloaded.</li>
 * </ul>
 */
final class CroesusKeyCases {

    private CroesusKeyCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "879-menu-croesus-key-states", CroesusKeyCases::states);
        MenuSuite.test(s, "880-menu-croesus-key-hide", CroesusKeyCases::hide);
        MenuSuite.test(s, "881-menu-croesus-key-floor", CroesusKeyCases::floor);
        MenuSuite.test(s, "882-menu-croesus-key-colours-saved", CroesusKeyCases::saved);
    }

    static final String RUN = "Catacombs - Floor VII";

    static JsonObject head(String player, String stateLine) {
        return FlipCroesusCases.item("minecraft:player_head", "§cThe Catacombs", "§7Floor VII", "", "§7Players:",
                " §a" + player + "§f: §eMage (39)", "", "§7Completed: §a1 day ago", "", stateLine,
                "§8Chests expire in 1d 12h!", "", "§eClick to view chests!");
    }

    static JsonObject runView(String obsidianReward) {
        return FlipCroesusCases.menu(RUN, 6, Map.of(
                10, FlipCroesusCases.item("minecraft:chest", "§4Bedrock Chest", "§7Contents", "§aTestkit Relic", "",
                        "§7Cost", "§61,000,000 Coins", "§cAlready opened!"),
                11, FlipCroesusCases.item("minecraft:chest", "§5Obsidian Chest", "§7Contents", "§a" + obsidianReward, "",
                        "§7Cost", "§6500,000 Coins", "§9Dungeon Chest Key"),
                49, FlipCroesusCases.item("minecraft:arrow", "§aGo Back")), Map.of());
    }

    static JsonObject croesus() {
        Map<Integer, JsonObject> slots = new LinkedHashMap<>();
        slots.put(10, head("Alpha", "§cNo chests opened yet!"));
        slots.put(11, head("Bravo", "§7Opened Chest: §4Bedrock"));
        slots.put(12, head("Charlie", "§7Opened Chest: §4Bedrock"));
        slots.put(13, head("Delta", "§aNo more chests to open!"));
        slots.put(14, head("Echo", "§7Opened Chest: §4Bedrock"));
        slots.put(49, FlipCroesusCases.item("minecraft:barrier", "§cClose"));
        Map<Integer, JsonObject> on = new LinkedHashMap<>();
        on.put(11, FlipCroesusCases.open(runView("Testkit Idol")));   // 2M - 500k - 200k key = +1.3M
        on.put(12, FlipCroesusCases.open(runView("Testkit Bead")));   // 100k - 500k - 200k key = -600k
        on.put(10, new JsonObject());
        on.put(13, new JsonObject());
        on.put(14, new JsonObject());
        JsonObject m = FlipCroesusCases.menu("(1/1) Croesus", 6, slots, on);
        m.addProperty("fill", false);
        return m;
    }

    static final Map<String, long[]> PRICES = Map.of("TESTKIT_RELIC", new long[]{3_000_000, 3_000_000},
            "TESTKIT_IDOL", new long[]{2_000_000, 2_000_000}, "TESTKIT_BEAD", new long[]{100_000, 100_000},
            "DUNGEON_CHEST_KEY", new long[]{200_000, 200_000});

    /** Prices in, the key floor at 500k, the two runs looked at; leaves the Croesus page open. */
    static AutoCloseable world(Session c, boolean hide) {
        MenuKit.reset(c);
        MenuKit.Cfg cfg = new MenuKit.Cfg(c);
        cfg.set("croesus.CroesusConfig", "ChestProfitEnabled", true).set("croesus.CroesusConfig", "HighlightRuns", true)
                .set("croesus.CroesusConfig", "HideOpenedRuns", hide)
                .set("croesus.CroesusConfig", "AutoMinProfitK", 100).set("croesus.CroesusConfig", "AutoKeyMinProfitK", 500)
                .set("croesus.CroesusConfig", "ChestKeyMode", Mod.enumValue("croesus.CroesusConfig$ChestKeyMode", "SET_PROFIT"));
        c.ctx().runOnClient(mc -> {
            Object prices = Mod.field("rngmeter.RngMeterEngine", "PRICES");
            PRICES.forEach((id, p) -> Mod.call(prices, "putBazaarPriceForTest", id, p[0], p[1]));
            Mod.staticCall("croesus.ChestProfitFeature", "clearKeyWorthForTest");
        });
        for (int slot : new int[]{11, 12}) {
            MenuKit.reset(c);
            MenuKit.show(c, croesus());
            MenuKit.awaitScreen(c, "(1/1) Croesus", 100);
            c.ctx().waitTicks(8);
            CroesusUiCases.hover(c, slot);
            c.ctx().getInput().pressMouse(0);
            MenuKit.awaitScreen(c, RUN, 100);
            c.ctx().waitTicks(8); // a few ticks in the run view: the chests are valued and noted for this run
        }
        MenuKit.reset(c);
        MenuKit.show(c, croesus());
        MenuKit.awaitScreen(c, "(1/1) Croesus", 100);
        c.ctx().getInput().setCursorPos(2, 2);
        c.ctx().waitTicks(10);
        return () -> {
            c.ctx().runOnClient(mc -> {
                Object prices = Mod.field("rngmeter.RngMeterEngine", "PRICES");
                PRICES.keySet().forEach(id -> Mod.call(prices, "removeBazaarPriceForTest", id));
                Mod.staticCall("croesus.ChestProfitFeature", "clearKeyWorthForTest");
            });
            MenuKit.reset(c);
            cfg.close();
        };
    }

    static byte[] states(Session c, int... slots) {
        byte[] all = c.onClient(mc -> (byte[]) Mod.staticCall("croesus.ChestProfitFeature", "runStatesForTest"));
        byte[] out = new byte[slots.length];
        for (int i = 0; i < slots.length; i++) {
            out[i] = slots[i] < all.length ? all[slots[i]] : -1;
        }
        return out;
    }

    /** The colour of the pixel 1 GUI px inside a slot's top-left corner (its border, or its tint over the slot). */
    static int borderPixel(Session c, BufferedImage img, int slot) {
        double[] g = CroesusUiCases.geometry(c);
        double px = img.getWidth() / g[2];
        int[] xy = CroesusUiCases.slotXY(c, slot);
        return img.getRGB((int) ((g[0] + xy[0]) * px + px / 2), (int) ((g[1] + xy[1]) * px + px / 2)) & 0xFFFFFF;
    }

    static String hex(int rgb) {
        return String.format("%06X", rgb);
    }

    static void states(Session c) throws Exception {
        try (AutoCloseable w = world(c, false)) {
            byte[] st = states(c, 10, 11, 12, 13, 14);
            c.check(st[0] == 1 && st[1] == 2 && st[2] == 3 && st[3] == 4 && st[4] == 5,
                    "states 10..14 = " + java.util.Arrays.toString(st) + ", want [1 unclaimed, 2 needs key, 3 opened, 4 done, 5 not looked at]");
            BufferedImage img = CroesusUiCases.shot(c, "key-states");
            int[] px = new int[5];
            for (int i = 0; i < 5; i++) {
                px[i] = borderPixel(c, img, 10 + i);
            }
            // Since 898 (2026-10-12): orange border, magenta border (R and B high, G low), blue border (B above R), dark
            // tint, cyan corner mark (G and B high, R low). Since croesus-colors2 the quiet ones are light tints / a faint outline. 898 measures how far apart they are; this only names them.
            int r0 = px[0] >> 16 & 255, g0 = px[0] >> 8 & 255;
            int r1 = px[1] >> 16 & 255, g1 = px[1] >> 8 & 255, b1 = px[1] & 255;
            int r2 = px[2] >> 16 & 255, b2 = px[2] & 255;
            int lum3 = (px[3] >> 16 & 255) + (px[3] >> 8 & 255) + (px[3] & 255);
            int r4 = px[4] >> 16 & 255, g4 = px[4] >> 8 & 255, b4 = px[4] & 255;
            c.check(r0 > 200 && g0 > 100 && g0 < 180, "unclaimed border " + hex(px[0]) + " is not orange");
            c.check(r1 > 150 && b1 > 100 && g1 < 80, "needs-key border " + hex(px[1]) + " is not magenta");
            c.check(b2 > r2 + 15, "opened tint " + hex(px[2]) + " is not blue");
            c.check(lum3 < 3 * 110, "key-used tint " + hex(px[3]) + " is not dark");
            c.check(g4 > 180 && b4 > 180 && r4 < 160, "the not-looked-at run's corner " + hex(px[4]) + " is not cyan");
            c.check(px[2] != px[3] && px[1] != px[0], "two states share a colour: " + hex(px[0]) + " " + hex(px[1]) + " "
                    + hex(px[2]) + " " + hex(px[3]));
            List<String> lines = CroesusUiCases.panel(c);
            c.check(lines.contains("Worth a key=1 (1 not looked at)"), "info box: " + lines);
            c.note("states [unclaimed, needs key, opened, key used, not looked at] = " + java.util.Arrays.toString(st)
                    + "; corner pixels " + hex(px[0]) + " " + hex(px[1]) + " " + hex(px[2]) + " " + hex(px[3]) + " " + hex(px[4])
                    + "; info box " + lines);
        }
    }

    static void hide(Session c) throws Exception {
        try (AutoCloseable w = world(c, true)) {
            BufferedImage img = CroesusUiCases.shot(c, "key-hide");
            double[] g = CroesusUiCases.geometry(c);
            double px = img.getWidth() / g[2];
            int empty = 15; // a slot with no run
            int[] diff = new int[5];
            for (int i = 0; i < 5; i++) {
                diff[i] = CroesusUiCases.squareDiff(c, img, px, g, 10 + i, empty);
            }
            c.check(diff[2] == 0 && diff[3] == 0, "opened (12) / key used (13) not hidden: " + diff[2] + " / " + diff[3] + " px differ from empty");
            c.check(diff[1] > 50, "needs-a-key run 11 was hidden (" + diff[1] + " px)");
            c.check(diff[4] > 50 && diff[0] > 50, "not-looked-at 14 / unclaimed 10 hidden (" + diff[4] + " / " + diff[0] + " px)");
            // The hidden run still opens: a click on 13 reaches the server at slot 13.
            int before = c.events("menu.click").size();
            CroesusUiCases.hover(c, 13);
            c.ctx().getInput().pressMouse(0);
            c.waitUntil("the click on hidden run 13", mc -> c.events("menu.click").size() > before, 40);
            c.check(c.events("menu.click").get(before).get("slot").getAsInt() == 13, "click went to " + c.events("menu.click").get(before));
            c.note("squares vs an empty slot (px differing) 10..14: " + java.util.Arrays.toString(diff)
                    + " - 12 and 13 hidden, 11 (needs key) and 14 (not looked at) drawn; click on hidden 13 reached the server");
        }
    }

    static void floor(Session c) throws Exception {
        try (AutoCloseable w = world(c, true)) {
            byte before = states(c, 11)[0];
            c.ctx().runOnClient(mc -> Mod.set("croesus.CroesusConfig", "setAutoKeyMinProfitK", 2000));
            c.ctx().waitTicks(10);
            byte after = states(c, 11)[0];
            BufferedImage img = CroesusUiCases.shot(c, "key-floor-2m");
            double[] g = CroesusUiCases.geometry(c);
            int diff = CroesusUiCases.squareDiff(c, img, img.getWidth() / g[2], g, 11, 15);
            c.check(before == 2 && after == 3, "run 11 at a 500k key floor " + before + ", at 2M " + after + " (want 2 then 3)");
            c.check(diff == 0, "at a 2M floor run 11 (+1.3M) should hide like an opened run, " + diff + " px differ");
            c.note("run 11 (+1.3M after a key): needs key at a 500k floor, opened (hidden) at 2M");
        }
    }

    static void saved(Session c) throws Exception {
        Path file = c.onClient(mc -> (Path) Mod.staticCall("util.ModPaths", "config", "killer560smod-croesus.json"));
        byte[] was = Files.exists(file) ? Files.readAllBytes(file) : null;
        try {
            c.ctx().runOnClient(mc -> {
                Mod.set("croesus.CroesusConfig", "setRunKeyColor", 0x12AB34);
                Mod.set("croesus.CroesusConfig", "setRunOpenedColor", 0x56CD78);
                Mod.call(Mod.cfg("croesus.CroesusConfig"), "save");
                Mod.staticCall("croesus.CroesusConfig", "load");
            });
            int key = c.onClient(mc -> (Integer) Mod.call(Mod.cfg("croesus.CroesusConfig"), "getRunKeyColor"));
            int opened = c.onClient(mc -> (Integer) Mod.call(Mod.cfg("croesus.CroesusConfig"), "getRunOpenedColor"));
            String json = Files.readString(file, StandardCharsets.UTF_8);
            c.check(key == 0x12AB34 && opened == 0x56CD78, "reloaded " + hex(key) + " / " + hex(opened));
            c.check(json.contains("runKeyColor") && json.contains("runOpenedColor"), "not in the file: " + json);
            c.note("Needs Key and Opened Run colours saved and reloaded: " + hex(key) + " / " + hex(opened));
        } finally {
            if (was == null) {
                Files.deleteIfExists(file);
            } else {
                Files.write(file, was);
            }
            c.ctx().runOnClient(mc -> Mod.staticCall("croesus.CroesusConfig", "load"));
        }
    }
}
