package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * compactorhl-1117 (killer560, 2026-11-17: "add a red highlight if it is off and green if it is on"): the Personal
 * Compactor menu (title "(2/3) Personal Mining Compactor" from his log, entry lore from his screenshots: description,
 * "Click to disable!", a whole line ENABLED or DISABLED) gets a green slot for an enabled entry, a red one for a disabled
 * one, nothing for arrows, the barrier, filler, or a description that merely says the word. The mod's own list must be
 * non-empty first, the pixels of a green and a red slot must change in the right color and no other slot may change, an
 * entry replaced by the other state flips, and the row off, the card off or another title gives none. Draw-only: no click.
 */
final class CompactorHl1117MenuCases {

    private static final String CPC = "compactorpreview.CompactorPreviewConfig";
    private static final String HL = "compactorpreview.CompactorMenuHighlight";
    private static final String TITLE = "(2/3) Personal Mining Compactor";

    private CompactorHl1117MenuCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1117-menu-compactor-highlight", CompactorHl1117MenuCases::highlight);
    }

    private static JsonObject entry(String name, boolean on) {
        return FlipCroesusCases.item("minecraft:iron_block", name, "§7Automatically compacts §880x §7Flawed Opal Gemstone",
                "§7from your sacks into §81x §7Fine Opal Gemstone.", "", on ? "§eClick to disable!" : "§eClick to enable!", "",
                on ? "§a§lENABLED" : "§c§lDISABLED", "§7NPC Sell Price: §625,600.0 Coins");
    }

    private static Map<Integer, JsonObject> slots() {
        Map<Integer, JsonObject> m = new LinkedHashMap<>();
        m.put(10, entry("§aFine Opal Gemstone", true));
        m.put(11, entry("§cEnchanted Redstone Block", false));
        m.put(12, entry("§aFlawed Ruby Gemstone", true));
        m.put(13, entry("§cEnchanted Coal Block", false));
        m.put(14, FlipCroesusCases.item("minecraft:book", "§aHow it works", "§7When ENABLED, items compact.", "§7ENABLED entries only."));
        m.put(45, FlipCroesusCases.item("minecraft:arrow", "§aGo Back", "§7To Personal Compactor", "", "§eClick to go back!"));
        m.put(49, FlipCroesusCases.item("minecraft:barrier", "§cClose", "§7Close this menu."));
        m.put(50, FlipCroesusCases.item("minecraft:black_stained_glass_pane", "§r"));
        return m;
    }

    @SuppressWarnings("unchecked")
    private static TreeMap<Integer, Integer> marks(Session c) {
        List<int[]> l = c.onClient(mc -> (List<int[]>) Mod.staticCall(HL, "highlighted", McCompat.screen(mc)));
        TreeMap<Integer, Integer> m = new TreeMap<>();
        for (int[] p : l) {
            m.put(p[0], p[1]);
        }
        return m;
    }

    private static void open(Session c, String title, Map<Integer, JsonObject> slots) {
        MenuKit.show(c, FlipCroesusCases.menu(title, 6, slots, Map.of()));
        MenuKit.awaitScreen(c, title, 100);
        c.ctx().getInput().setCursorPos(2, 2);
        c.ctx().waitTicks(5);
    }

    static void highlight(Session c) throws Exception {
        MenuKit.reset(c);
        boolean wasOn = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(CPC), "isEnabledRaw"));
        c.ctx().runOnClient(mc -> Mod.set(CPC, "setEnabled", true));
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CPC, "SkyblockOnly", false).set(CPC, "Highlight", true);
            open(c, TITLE, slots());
            long drawn0 = c.onClient(mc -> ((Number) Mod.staticCall(HL, "drawnCount")).longValue());
            TreeMap<Integer, Integer> m = marks(c);
            c.check(!m.isEmpty(), "the highlight list is empty: the feature did not act");
            c.check(m.equals(new TreeMap<>(Map.of(10, 1, 11, -1, 12, 1, 13, -1))), "marks " + m + ", want 10=+1 11=-1 12=+1 13=-1");
            c.ctx().waitTicks(3);
            long drawn1 = c.onClient(mc -> ((Number) Mod.staticCall(HL, "drawnCount")).longValue());
            c.check(drawn1 > drawn0, "no slot was drawn (" + drawn0 + " -> " + drawn1 + ")");

            // Pixels: on against off, per slot; green and red where wanted, nothing elsewhere.
            BufferedImage img = CroesusUiCases.shot(c, "on");
            c.ctx().runOnClient(mc -> Mod.set(CPC, "setHighlight", false));
            c.ctx().waitTicks(3);
            c.check(marks(c).isEmpty(), "Highlight row OFF still marks " + marks(c));
            BufferedImage ref = CroesusUiCases.shot(c, "off");
            c.ctx().runOnClient(mc -> Mod.set(CPC, "setHighlight", true));
            c.ctx().waitTicks(3);
            double[] g = CroesusUiCases.geometry(c);
            double px = img.getWidth() / g[2];
            StringBuilder sb = new StringBuilder();
            for (int slot = 0; slot < 54; slot++) {
                int[] xy = CroesusUiCases.slotXY(c, slot);
                int changed = 0, green = 0, red = 0;
                for (int gy = 0; gy < 16; gy++) {
                    for (int gx = 0; gx < 16; gx++) {
                        int ix = (int) ((g[0] + xy[0]) * px + (gx + 0.5) * px);
                        int iy = (int) ((g[1] + xy[1]) * px + (gy + 0.5) * px);
                        int a = img.getRGB(ix, iy);
                        if (CroesusUiCases.changed(a, ref.getRGB(ix, iy))) {
                            changed++;
                            int r = (a >> 16) & 255, gr = (a >> 8) & 255, bl = a & 255;
                            if (gr > r && gr > bl) green++;
                            if (r > gr && r > bl) red++;
                        }
                    }
                }
                Integer want = m.get(slot);
                if (want != null) {
                    sb.append(slot).append('=').append(changed).append("/g").append(green).append("/r").append(red).append(' ');
                    c.check(changed >= 40, "slot " + slot + " changed only " + changed + " pixels");
                    int own = want > 0 ? green : red;
                    c.check(own * 2 > changed, "slot " + slot + " (" + (want > 0 ? "green" : "red") + ") has " + green + " green, "
                            + red + " red of " + changed + " changed pixels");
                } else {
                    c.check(changed == 0, "slot " + slot + " changed " + changed + " pixels but is not highlighted");
                }
            }
            c.note("pixels changed/green/red: " + sb.toString().trim());

            // The server resends an item after a click: a new stack with the other state arrives, the mark follows it.
            c.ctx().runOnClient(mc -> {
                AbstractContainerScreen<?> s = (AbstractContainerScreen<?>) McCompat.screen(mc);
                Slot on = s.getMenu().slots.get(10);
                on.set(s.getMenu().slots.get(11).getItem().copy());
            });
            c.ctx().waitTicks(3);
            TreeMap<Integer, Integer> flipped = marks(c);
            c.check(flipped.get(10) != null && flipped.get(10) == -1, "slot 10 after the server sent a disabled item: " + flipped.get(10));
            c.ctx().runOnClient(mc -> {
                AbstractContainerScreen<?> s = (AbstractContainerScreen<?>) McCompat.screen(mc);
                s.getMenu().slots.get(11).set(s.getMenu().slots.get(12).getItem().copy());
            });
            c.ctx().waitTicks(3);
            c.check(marks(c).get(11) != null && marks(c).get(11) == 1, "slot 11 after an enabled item arrived: " + marks(c).get(11));

            // Other titles of the family are covered, another menu is not.
            for (String t : List.of("Personal Husbandry Compactor", "(1/2) Personal Husbandry Compactor")) {
                MenuKit.reset(c);
                open(c, t, slots());
                c.check(marks(c).size() == 4, "skill title \"" + t + "\" marks " + marks(c));
            }
            // killer560 11-18: only the skill-specific menus; the plain compactor and the Deletor stay plain.
            for (String t : List.of("Personal Compactor", "(1/2) Personal Compactor", "Personal Deletor 7000", "Personal Deletor")) {
                MenuKit.reset(c);
                open(c, t, slots());
                c.check(marks(c).isEmpty(), "title \"" + t + "\" must not highlight but marks " + marks(c));
            }
            // Sack / skill sack compactor menus (owner scope 11-17): no overlay, even with ENABLED / DISABLED lore.
            for (String t : List.of("Sacks", "Mining Sack", "(1/2) Mining Sack Compactor", "Sack of Sacks", "Mining Compactor")) {
                MenuKit.reset(c);
                open(c, t, slots());
                c.check(marks(c).isEmpty(), "menu \"" + t + "\" with ENABLED lore got marks " + marks(c));
            }
            MenuKit.reset(c);
            open(c, TITLE, slots());
            c.ctx().runOnClient(mc -> Mod.set(CPC, "setEnabled", false));
            c.ctx().waitTicks(3);
            c.check(marks(c).isEmpty(), "the card OFF still marks " + marks(c));
            c.check(c.events("container.click").isEmpty(), "a draw-only highlight clicked: " + c.events("container.click"));
            c.note("compactor menu: green 10,12; red 11,13; none on 14/45/49/50; flips on a new stack; row/card off and other title none; no click");
        } finally {
            c.ctx().runOnClient(mc -> Mod.set(CPC, "setEnabled", wasOn));
            MenuKit.reset(c);
        }
    }
}
