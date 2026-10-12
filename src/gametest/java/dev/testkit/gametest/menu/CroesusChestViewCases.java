package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 903-905 (croesus-colors2, 2026-10-13). killer560: "in a chest menu if something is worth coming back and buying with a
 * key, highlight it yellow or orange. If there are multiple only highlight the best one. So if there are 3 profitable
 * chests after key the most profitable one should be green on open since no key will be used on it, the orange would be
 * the second most profitable ... and when you reopen still have it orange and the green one should no longer be green,
 * and the third would show nothing."
 * <p>
 * One run (F7) with four chests: Bedrock +2M (+1.8M with a key), Obsidian +1.5M (+1.3M), Emerald +1.2M (+1.0M) and
 * Diamond 0 (-200k). Key floor 500k (Auto Croesus SET_PROFIT). Three are profitable after a key.
 * <ul>
 *   <li>903: first visit (nothing claimed): Bedrock green, Obsidian orange, Emerald and Diamond nothing; the list says
 *   unclaimed.</li>
 *   <li>904: reopened after Bedrock was claimed without a key: no green, Obsidian still orange, Emerald nothing; the list
 *   says "needs a key" before and after the view.</li>
 *   <li>905: after a key (Obsidian bought too, "No more chests to open!"): nothing highlighted; the list says key used.</li>
 * </ul>
 * 903 also opens an all-loss run (every chest below its cost): no green - the free claim is only green when it makes
 * money - and no orange; the first run then still shows its green and orange.
 * Each step reads the marks the mod draws ({@code chestMarksForTest}) AND the drawn pixels of the shot.
 */
final class CroesusChestViewCases {

    private CroesusChestViewCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "903-menu-croesus-chest-view-first", c -> run(c, 1));
        MenuSuite.test(s, "904-menu-croesus-chest-view-reopen", c -> run(c, 2));
        MenuSuite.test(s, "905-menu-croesus-chest-view-key-used", c -> run(c, 3));
    }

    static final int RUN_SLOT = 11;
    static final int BEDROCK = 10, OBSIDIAN = 11, EMERALD = 12, DIAMOND = 13;

    static JsonObject chest(String item, String name, String reward, String cost, String state) {
        return state == null
                ? FlipCroesusCases.item(item, name, "§7Contents", "§a" + reward, "", "§7Cost", "§6" + cost + " Coins")
                : FlipCroesusCases.item(item, name, "§7Contents", "§a" + reward, "", "§7Cost", "§6" + cost + " Coins", state);
    }

    /** step 1: nothing claimed; 2: Bedrock claimed, the rest need a key; 3: Bedrock and Obsidian opened. */
    static JsonObject view(int step) {
        if (step == 0) {
            // The all-loss run (croesus-green, 2026-10-13): every chest costs more than its Bead (100k) is worth.
            Map<Integer, JsonObject> slots = new LinkedHashMap<>();
            slots.put(BEDROCK, chest("minecraft:chest", "§4Bedrock Chest", "Testkit Bead", "1,000,000", null));
            slots.put(OBSIDIAN, chest("minecraft:chest", "§5Obsidian Chest", "Testkit Bead", "500,000", null));
            slots.put(EMERALD, chest("minecraft:chest", "§2Emerald Chest", "Testkit Bead", "300,000", null));
            slots.put(DIAMOND, chest("minecraft:chest", "§bDiamond Chest", "Testkit Bead", "200,000", null));
            slots.put(49, FlipCroesusCases.item("minecraft:arrow", "§aGo Back"));
            return FlipCroesusCases.menu(CroesusKeyCases.RUN, 6, slots, Map.of());
        }
        String opened = "§cAlready opened!";
        String key = "§9Dungeon Chest Key";
        Map<Integer, JsonObject> slots = new LinkedHashMap<>();
        slots.put(BEDROCK, chest("minecraft:chest", "§4Bedrock Chest", "Testkit Relic", "1,000,000", step >= 2 ? opened : null));
        slots.put(OBSIDIAN, chest("minecraft:chest", "§5Obsidian Chest", "Testkit Idol", "500,000",
                step >= 3 ? opened : step == 2 ? key : null));
        slots.put(EMERALD, chest("minecraft:chest", "§2Emerald Chest", "Testkit Gem", "300,000", step == 2 ? key : null));
        slots.put(DIAMOND, chest("minecraft:chest", "§bDiamond Chest", "Testkit Bead", "100,000", step == 2 ? key : null));
        slots.put(49, FlipCroesusCases.item("minecraft:arrow", "§aGo Back"));
        return FlipCroesusCases.menu(CroesusKeyCases.RUN, 6, slots, Map.of());
    }

    static JsonObject list(int step) {
        String line = step <= 1 ? "§cNo chests opened yet!" : step == 2 ? "§7Opened Chest: §4Bedrock" : "§aNo more chests to open!";
        Map<Integer, JsonObject> heads = new LinkedHashMap<>();
        heads.put(RUN_SLOT, CroesusColorCases.head("minecraft:player_head", step == 0 ? "Loser" : "Viewer", line,
                step == 0 ? "1d 6h!" : "1d 12h!"));
        return CroesusStableCases.list(heads, Map.of(RUN_SLOT, FlipCroesusCases.open(view(step))));
    }

    static byte listState(Session c) {
        return CroesusKeyCases.states(c, RUN_SLOT)[0];
    }

    /** Shows the list at {@code step}, reads its state, opens the run view by a click, reads marks and pixels. */
    static String visit(Session c, int step, byte wantList, byte[] wantMarks, String shot) {
        CroesusStableCases.showList(c, list(step), 10);
        byte before = listState(c);
        CroesusStableCases.press(c, RUN_SLOT, CroesusKeyCases.RUN);
        c.ctx().waitTicks(6);
        byte[] marks = c.onClient(mc -> (byte[]) Mod.staticCall("croesus.ChestProfitFeature", "chestMarksForTest"));
        byte[] got = new byte[4];
        for (int i = 0; i < 4; i++) {
            got[i] = BEDROCK + i < marks.length ? marks[BEDROCK + i] : -1;
        }
        String pixels = "";
        if (shot != null) {
            BufferedImage img = CroesusUiCases.shot(c, shot);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                int px = CroesusKeyCases.borderPixel(c, img, BEDROCK + i);
                String drawn = kind(px);
                String want = wantMarks[i] == 1 ? "green" : wantMarks[i] == 2 ? "orange" : "none";
                c.check(drawn.equals(want), "step " + step + ": chest slot " + (BEDROCK + i) + " drawn " + drawn + " ("
                        + CroesusKeyCases.hex(px) + "), want " + want);
                sb.append(CroesusKeyCases.hex(px)).append('=').append(drawn).append(' ');
            }
            pixels = sb.toString().trim();
        }
        CroesusStableCases.showList(c, list(step), 10);
        byte after = listState(c);
        c.check(Arrays.equals(got, wantMarks), "step " + step + ": marks [Bedrock, Obsidian, Emerald, Diamond] = "
                + Arrays.toString(got) + ", want " + Arrays.toString(wantMarks) + " (1 green, 2 orange)");
        c.check(after == wantList, "step " + step + ": the list says " + after + " after the view, want " + wantList);
        if (step > 1) {
            c.check(before == wantList, "step " + step + ": the list says " + before + " before the view, want " + wantList);
        }
        boolean orange = false;
        for (byte m : got) {
            orange |= m == 2;
        }
        c.check(step <= 1 || orange == (after == 2), "step " + step + ": the list's needs-a-key (" + (after == 2)
                + ") disagrees with the view's orange (" + orange + ")");
        return "step " + step + ": list " + before + " -> " + after + ", marks " + Arrays.toString(got)
                + (pixels.isEmpty() ? "" : ", pixels " + pixels);
    }

    /** The old Highlight Best switch carries over to Highlight Free Chest; Highlight Key Chest starts on. */
    static String migration(Session c) throws Exception {
        java.nio.file.Path file = c.onClient(mc -> (java.nio.file.Path) Mod.staticCall("util.ModPaths", "config", "killer560smod-croesus.json"));
        byte[] was = java.nio.file.Files.exists(file) ? java.nio.file.Files.readAllBytes(file) : null;
        try {
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.writeString(file, "{\"highlightBest\": false, \"highlightSecondWithKey\": false}");
            c.ctx().runOnClient(mc -> Mod.staticCall("croesus.CroesusConfig", "load"));
            boolean free = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg("croesus.CroesusConfig"), "isHighlightFreeChest"));
            boolean key = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg("croesus.CroesusConfig"), "isHighlightKeyChest"));
            String said = "old highlightBest=false -> Highlight Free Chest " + free + ", Highlight Key Chest " + key;
            c.check(!free && key, said + " (want false, true)");
            return said;
        } finally {
            if (was == null) {
                java.nio.file.Files.deleteIfExists(file);
            } else {
                java.nio.file.Files.write(file, was);
            }
            c.ctx().runOnClient(mc -> Mod.staticCall("croesus.CroesusConfig", "load"));
        }
    }

    static String kind(int rgb) {
        int r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255;
        if (g > r + 60 && g > b + 60) {
            return "green";
        }
        if (r > 200 && g > 100 && g < 200 && b < 100) {
            return "orange";
        }
        return Math.abs(r - 0x8B) < 12 && Math.abs(g - 0x8B) < 12 && Math.abs(b - 0x8B) < 12 ? "none" : "other";
    }

    static void run(Session c, int upTo) throws Exception {
        try (AutoCloseable w = CroesusStableCases.world(c)) {
            c.ctx().runOnClient(mc -> {
                Mod.set("croesus.CroesusConfig", "setHideOpenedRuns", false);
                Mod.set("croesus.CroesusConfig", "setHighlightFreeChest", true);
                Mod.set("croesus.CroesusConfig", "setHighlightKeyChest", true);
                Object prices = Mod.field("rngmeter.RngMeterEngine", "PRICES");
                Mod.call(prices, "putBazaarPriceForTest", "TESTKIT_GEM", 1_500_000L, 1_500_000L);
            });
            try {
                StringBuilder note = new StringBuilder();
                note.append(visit(c, 1, (byte) 1, new byte[]{1, 2, 0, 0}, upTo == 1 ? "chestview-first" : null));
                if (upTo >= 2) {
                    note.append("; ").append(visit(c, 2, (byte) 2, new byte[]{0, 2, 0, 0}, upTo == 2 ? "chestview-reopen" : null));
                }
                if (upTo >= 3) {
                    note.append("; ").append(visit(c, 3, (byte) 4, new byte[]{0, 0, 0, 0}, "chestview-key-used"));
                }
                if (upTo == 1) {
                    // An all-loss run: the best chest loses money, so no green (killer560, 2026-10-13); nothing clears
                    // the key floor, so no orange either. Then the first run again: its green and orange are unchanged.
                    note.append("; all-loss ").append(visit(c, 0, (byte) 1, new byte[]{0, 0, 0, 0}, "chestview-all-loss"));
                    note.append("; again ").append(visit(c, 1, (byte) 1, new byte[]{1, 2, 0, 0}, null));
                    note.append("; ").append(migration(c));
                }
                c.note(note.toString());
            } finally {
                c.ctx().runOnClient(mc -> {
                    Object prices = Mod.field("rngmeter.RngMeterEngine", "PRICES");
                    Mod.call(prices, "removeBazaarPriceForTest", "TESTKIT_GEM");
                });
            }
        }
    }
}
