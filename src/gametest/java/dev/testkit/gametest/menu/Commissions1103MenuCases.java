package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Menu slot highlights from mod commissions-1103 (killer560, 2026-11-03). Both are draw-only, so every case also requires
 * that NO container click reached the server; the session's GrimAC check covers the rest.
 * <ul>
 *   <li>1103-menu-commissions-done: Hypixel's "Commissions" menu (title from his log) with five commission books: COMPLETED,
 *       45%, "Click to claim rewards!", "Progress: 100%", 10%. The mod's own answer is slots 11, 13, 14; the screenshot
 *       with Completed Commissions on, diffed against the same menu with it off, changes pixels in exactly those three
 *       slots (and none of the 33 others), and the changed pixels are green.</li>
 *   <li>1103-menu-loadout-selected: "(1/3) Loadouts" (his log) with loadouts in 14, 15, 16, 23: slot 15's lore "SELECTED",
 *       the others "Click to equip!"; the mod answers 15 and only slot 15's pixels change. Then a menu with no lore mark:
 *       a player's "[VIP] Bob: You equipped Loadout 7!" sets nothing, Hypixel's "You equipped Loadout 5!" picks slot 24
 *       (the fifth loadout slot), and on page 2 "You equipped Loadout 14!" picks slot 15.</li>
 * </ul>
 */
final class Commissions1103MenuCases {

    private static final String MQ = "miningqol.MiningQolConfig";
    private static final String HL = "miningqol.CommissionMenuHighlight";
    private static final String LK = "loadoutkeybinds.LoadoutKeybindsConfig";
    private static final String LH = "loadoutkeybinds.LoadoutHighlight";

    private Commissions1103MenuCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1103-menu-commissions-done", Commissions1103MenuCases::commissions);
        MenuSuite.test(s, "1103-menu-loadout-selected", Commissions1103MenuCases::loadout);
        MenuSuite.test(s, "1103-menu-search-click", Commissions1103MenuCases::searchClick);
    }

    private static JsonObject book(String name, String... lore) {
        return FlipCroesusCases.item("minecraft:writable_book", name, lore);
    }

    static void commissions(Session c) throws Exception {
        MenuKit.reset(c);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(MQ, "CommissionHud", true).set(MQ, "CommissionMenuHighlight", true);
            Map<Integer, JsonObject> slots = new LinkedHashMap<>();
            slots.put(11, book("§6Goblin Slayer", "§7Slay §c100 §7Goblins.", "", "§a§lCOMPLETED", "", "§eClick to claim rewards!"));
            slots.put(12, book("§6Lava Springs Mithril", "§7Mine Mithril in Lava Springs.", "", "§7Progress: §e45%"));
            slots.put(13, book("§6Titanium Miner", "§7Mine 15 Titanium.", "", "§eClick to claim rewards!"));
            slots.put(14, book("§6Upper Mines Mithril", "§7Mine Mithril in Upper Mines.", "", "§7Progress: §a100%"));
            slots.put(15, book("§6Star Sentry Puncher", "§7Punch Star Sentries.", "", "§7Progress: §c10%"));
            MenuKit.show(c, FlipCroesusCases.menu("Commissions", 4, slots, Map.of()));
            MenuKit.awaitScreen(c, "Commissions", 100);
            c.ctx().getInput().setCursorPos(2, 2);
            c.ctx().waitTicks(5);
            List<Integer> mod = c.onClient(mc -> new ArrayList<>((List<Integer>) Mod.staticCall(HL, "highlightedSlots",
                    McCompat.screen(mc))));
            c.check(mod.equals(List.of(11, 13, 14)), "the mod highlights " + mod + ", want [11, 13, 14]");
            pixelCheck(c, "commissions", List.of(11, 13, 14), 36,
                    () -> Mod.set(MQ, "setCommissionMenuHighlight", false),
                    () -> Mod.set(MQ, "setCommissionMenuHighlight", true));
            c.check(c.events("container.click").isEmpty(), "a draw-only highlight clicked: " + c.events("container.click"));
            c.note("Commissions: mod slots " + mod + "; pixels changed only there; no click");
        } finally {
            MenuKit.reset(c);
        }
    }

    /** Owner 2026-11-03: "i cannot click into the inventory search bar to type stuff" (a Hypixel menu, Loadouts). A real
     *  left click in the middle of the bar must start typing, and typed characters must land in the query. */
    static void searchClick(Session c) throws Exception {
        String isf = "inventorysearch.InventorySearchFeature";
        MenuKit.reset(c);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set("inventorysearch.InventorySearchConfig", "Enabled", true);
            c.ctx().runOnClient(mc -> Mod.staticCall(isf, "setListening", false));
            Map<Integer, JsonObject> slots = new LinkedHashMap<>();
            slots.put(14, FlipCroesusCases.item("minecraft:iron_chestplate", "§aLoadout 1", "§7Armor: Iron", "", "§eClick to equip!"));
            MenuKit.show(c, FlipCroesusCases.menu("(1/3) Loadouts", 6, slots, Map.of()));
            MenuKit.awaitScreen(c, "(1/3) Loadouts", 100);
            c.ctx().waitTicks(5);
            int[] b = c.onClient(mc -> (int[]) Mod.field(isf, "lastBoxBounds"));
            c.check(b != null, "the search bar was not drawn in the Loadouts menu");
            if (b == null) {
                return;
            }
            double scale = c.onClient(mc -> (double) mc.getWindow().getGuiScale());
            c.ctx().getInput().setCursorPos((b[0] + b[2] / 2.0) * scale, (b[1] + b[3] / 2.0) * scale);
            c.ctx().waitTicks(3);
            c.ctx().getInput().pressMouse(dev.testkit.compat.McKeys.mouse(0));
            c.ctx().waitTicks(3);
            boolean listening = c.onClient(mc -> (Boolean) Mod.staticCall(isf, "isListening"));
            c.ctx().getInput().typeChars("iron");
            c.ctx().waitTicks(3);
            String q = c.onClient(mc -> (String) Mod.staticCall(isf, "getQuery"));
            c.note("bar " + java.util.Arrays.toString(b) + " gui scale " + scale + "; after click listening " + listening
                    + ", query \"" + q + "\"");
            c.check(listening, "a click on the bar did not start typing");
            c.check(q.contains("iron"), "typing after the click did not reach the query: \"" + q + "\"");
        } finally {
            c.ctx().runOnClient(mc -> {
                Mod.staticCall(isf, "setListening", false);
                ((StringBuilder) Mod.field(isf, "QUERY")).setLength(0);
            });
            MenuKit.reset(c);
        }
    }

    static void loadout(Session c) throws Exception {
        MenuKit.reset(c);
        int was = c.onClient(mc -> ((Number) Mod.staticCall(LH, "lastEquippedLoadout")).intValue());
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(LK, "Enabled", true).set(LK, "HighlightSelected", true);
            c.ctx().runOnClient(mc -> Mod.staticCall(LH, "setLastEquippedForTest", 0));
            Map<Integer, JsonObject> slots = new LinkedHashMap<>();
            slots.put(14, FlipCroesusCases.item("minecraft:iron_chestplate", "§aLoadout 1", "§7Armor: Iron", "", "§eClick to equip!"));
            slots.put(15, FlipCroesusCases.item("minecraft:diamond_chestplate", "§aLoadout 2", "§7Armor: Diamond", "", "§a§lSELECTED"));
            slots.put(16, FlipCroesusCases.item("minecraft:golden_chestplate", "§aLoadout 3", "§7Armor: Gold", "", "§eClick to equip!"));
            slots.put(23, FlipCroesusCases.item("minecraft:leather_chestplate", "§aLoadout 4", "§7Armor: Leather", "", "§eClick to equip!"));
            MenuKit.show(c, FlipCroesusCases.menu("(1/3) Loadouts", 6, slots, Map.of()));
            MenuKit.awaitScreen(c, "(1/3) Loadouts", 100);
            c.ctx().getInput().setCursorPos(2, 2);
            c.ctx().waitTicks(5);
            int lore = selected(c);
            c.check(lore == 15, "lore SELECTED: the mod picks slot " + lore + ", want 15");
            pixelCheck(c, "loadout", List.of(15), 54,
                    () -> Mod.set(LK, "setHighlightSelected", false),
                    () -> Mod.set(LK, "setHighlightSelected", true));

            // No lore mark: Hypixel's chat line decides; another player's look-alike does not.
            Map<Integer, JsonObject> plain = new LinkedHashMap<>();
            int[] at = {14, 15, 16, 23, 24, 25};
            for (int i = 0; i < at.length; i++) {
                plain.put(at[i], FlipCroesusCases.item("minecraft:iron_chestplate", "§aLoadout " + (i + 1), "§7Armor: Iron"));
            }
            MenuKit.reset(c);
            c.hx().chat("§b[VIP] Bob§f: You equipped Loadout 7!");
            c.hx().chat("§aYou equipped Loadout 5!");
            c.waitUntil("the mod read Hypixel's line", mc -> ((Number) Mod.staticCall(LH, "lastEquippedLoadout")).intValue() == 5, 60);
            MenuKit.show(c, FlipCroesusCases.menu("(1/3) Loadouts", 6, plain, Map.of()));
            MenuKit.awaitScreen(c, "(1/3) Loadouts", 100);
            c.ctx().waitTicks(3);
            int chat = selected(c);
            c.check(chat == 24, "after 'You equipped Loadout 5!' the mod picks " + chat + ", want 24");
            MenuKit.reset(c);
            c.hx().chat("§aYou equipped Loadout 14!");
            c.waitUntil("Loadout 14 read", mc -> ((Number) Mod.staticCall(LH, "lastEquippedLoadout")).intValue() == 14, 60);
            MenuKit.show(c, FlipCroesusCases.menu("(2/3) Loadouts", 6, plain, Map.of()));
            MenuKit.awaitScreen(c, "(2/3) Loadouts", 100);
            c.ctx().waitTicks(3);
            int page2 = selected(c);
            c.check(page2 == 15, "page 2 with Loadout 14 picks " + page2 + ", want 15");
            c.check(c.events("container.click").isEmpty(), "a draw-only highlight clicked: " + c.events("container.click"));
            c.note("Loadouts: lore -> 15 (pixels only there); a player's line ignored; chat 5 -> 24; page 2 chat 14 -> 15; no click");
        } finally {
            c.ctx().runOnClient(mc -> Mod.staticCall(LH, "setLastEquippedForTest", was));
            MenuKit.reset(c);
        }
    }

    private static int selected(Session c) {
        return c.onClient(mc -> ((Number) Mod.staticCall(LH, "selectedSlot", McCompat.screen(mc))).intValue());
    }

    /**
     * Shot with the highlight on, then off (the reference), then on again; per menu slot the pixels that differ by more
     * than the testkit's changed-pixel rule. {@code want} slots must have at least 40 changed pixels, mostly green; every
     * other menu slot below {@code topSlots} none.
     */
    private static void pixelCheck(Session c, String label, List<Integer> want, int topSlots, Runnable off, Runnable on) {
        BufferedImage img = CroesusUiCases.shot(c, label + "-on");
        c.ctx().runOnClient(mc -> off.run());
        c.ctx().waitTicks(3);
        BufferedImage ref = CroesusUiCases.shot(c, label + "-off");
        c.ctx().runOnClient(mc -> on.run());
        c.ctx().waitTicks(3);
        double[] g = CroesusUiCases.geometry(c);
        double px = img.getWidth() / g[2];
        StringBuilder sb = new StringBuilder();
        for (int slot = 0; slot < topSlots; slot++) {
            int[] xy = CroesusUiCases.slotXY(c, slot);
            double sx = (g[0] + xy[0]) * px;
            double sy = (g[1] + xy[1]) * px;
            int changed = 0;
            int green = 0;
            for (int gy = 0; gy < 16; gy++) {
                for (int gx = 0; gx < 16; gx++) {
                    int ix = (int) (sx + (gx + 0.5) * px);
                    int iy = (int) (sy + (gy + 0.5) * px);
                    int a = img.getRGB(ix, iy);
                    int b = ref.getRGB(ix, iy);
                    if (CroesusUiCases.changed(a, b)) {
                        changed++;
                        int r = (a >> 16) & 255;
                        int gr = (a >> 8) & 255;
                        int bl = a & 255;
                        if (gr > r && gr > bl) {
                            green++;
                        }
                    }
                }
            }
            if (want.contains(slot)) {
                sb.append(slot).append('=').append(changed).append('/').append(green).append(' ');
                c.check(changed >= 40, label + ": slot " + slot + " changed only " + changed + " pixels with the highlight on");
                c.check(green * 2 > changed, label + ": slot " + slot + "'s changed pixels are not green (" + green + "/" + changed + ")");
            } else {
                c.check(changed == 0, label + ": slot " + slot + " changed " + changed + " pixels but is not highlighted");
            }
        }
        c.note(label + " changed/green pixels: " + sb.toString().trim());
    }
}
