package dev.testkit.gametest.menu;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Missing Enchants (missingench-1118): a real stack with SkyBlock custom data and a rarity line, its tooltip read through
 * {@code Screen.getTooltipFromItem} with Shift forced up and down. Logic: Sharpness sword lists Smite and Bane of Arthropods,
 * never Knockback, never an ultimate, never a conflicting enchant (First Strike held: no Triple-Strike; Life Steal held: no Drain or
 * Mana Steal), below-max list only when asked. UI: OFF unchanged, hint only without Shift, section only with Shift, an unknown item
 * type, an enchanted book and a plain item get nothing.
 */
final class MissingEnch1118Cases {

    private static final String CFG = "missingenchants.MissingEnchantsConfig";
    private static final String FEATURE = "missingenchants.MissingEnchantsFeature";

    private MissingEnch1118Cases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1118-missingench-logic", MissingEnch1118Cases::logic);
        MenuSuite.test(s, "1118-missingench-tooltip", MissingEnch1118Cases::tooltip);
    }

    private static String stack(String id, String enchants, String rarityLine) {
        return "minecraft:stick[custom_data={id:\"" + id + "\",enchantments:{" + enchants + "}},lore=[{text:\"Damage: +100\",color:\"gray\","
                + "italic:false},{text:\"\"},{text:\"" + rarityLine + "\",color:\"gold\",bold:true,italic:false}]]";
    }

    private static ItemStack give(Session c, String stack) {
        c.hx().give(0, stack);
        c.waitUntil("test stack in slot 0", mc -> !mc.player.getInventory().getItem(0).isEmpty(), 60);
        c.ctx().waitTicks(2);
        return c.onClient(mc -> mc.player.getInventory().getItem(0));
    }

    /** The tooltip lines with Shift forced to {@code shift}. */
    private static List<String> tip(Session c, ItemStack stack, boolean shift) {
        return c.onClient(mc -> {
            Mod.setField(FEATURE, "shiftForTest", shift);
            Mod.staticCall(FEATURE, "clearCache");
            List<String> out = new ArrayList<>();
            for (Component line : Screen.getTooltipFromItem(mc, stack)) {
                out.add(line.getString());
            }
            return out;
        });
    }

    /** The text after the "Missing Enchants" header up to the next blank line, or "" when there is no section. */
    private static String section(List<String> tip, String header) {
        int i = tip.indexOf(header);
        if (i < 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int j = i + 1; j < tip.size() && !tip.get(j).isEmpty(); j++) {
            sb.append(tip.get(j)).append(", ");
        }
        return sb.toString();
    }

    private static boolean has(String section, String name) {
        for (String s : section.split(", ")) {
            if (s.equals(name)) {
                return true;
            }
        }
        return false;
    }

    static void logic(Session c) throws Exception {
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CFG, "Enabled", true).set(CFG, "BelowMax", false).set(CFG, "Hint", true);
            ItemStack sword = give(c, stack("HYPERION", "sharpness:5,critical:5,ultimate_wise:5,first_strike:4,life_steal:3", "LEGENDARY DUNGEON SWORD"));
            List<String> t = tip(c, sword, true);
            String miss = section(t, "Missing Enchants");
            c.check(!miss.isEmpty(), "no Missing Enchants section on a sword: " + t);
            c.check(has(miss, "Smite") && has(miss, "Bane of Arthropods"), "Smite and Bane of Arthropods must be listed beside Sharpness: " + miss);
            c.check(has(miss, "Looting") && has(miss, "Vampirism"), "ordinary missing enchants not listed: " + miss);
            c.check(!has(miss, "Knockback"), "Knockback must never be listed: " + miss);
            c.check(!has(miss, "Sharpness") && !has(miss, "Critical"), "an enchant the item has was listed: " + miss);
            c.check(!has(miss, "Triple-Strike"), "Triple-Strike conflicts with First Strike: " + miss);
            c.check(!has(miss, "Drain") && !has(miss, "Mana Steal"), "Drain / Mana Steal conflict with Life Steal: " + miss);
            c.check(!miss.toLowerCase().contains("soul eater") && !miss.toLowerCase().contains("chimera") && !miss.toLowerCase().contains("ultimate"),
                    "an ultimate was listed: " + miss);
            c.check(section(t, "Below Max Level").isEmpty(), "below-max list shown while its switch is off: " + t);
            // below max on: Sharpness V is under its true top level, the ultimate is never listed there
            c.ctx().runOnClient(mc -> Mod.with(CFG, "BelowMax", true));
            List<String> t2 = tip(c, sword, true);
            String below = section(t2, "Below Max Level");
            c.check(below.contains("Sharpness 5 -> "), "Sharpness 5 not in the below-max list: " + t2);
            c.check(!below.toLowerCase().contains("wise"), "an ultimate was in the below-max list: " + below);
            // a Triple-Strike-free item with none of First Strike: Triple-Strike is listed
            ItemStack plain = give(c, stack("ASPECT_OF_THE_END", "sharpness:5", "RARE SWORD"));
            String m3 = section(tip(c, plain, true), "Missing Enchants");
            c.check(has(m3, "Triple-Strike") && has(m3, "First Strike"), "no conflict held, both strikes should be listed: " + m3);
            c.note("missingench logic: sword lists " + miss.split(", ").length + " missing (Smite, Bane, Looting yes; Knockback, Triple-Strike, Drain, ultimates no); below max: " + below);
        } finally {
            Mod.setField(FEATURE, "shiftForTest", null);
            ItemCases.clear(c, 0);
        }
    }

    static void tooltip(Session c) throws Exception {
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CFG, "Enabled", false).set(CFG, "BelowMax", false).set(CFG, "Hint", true);
            ItemStack sword = give(c, stack("HYPERION", "sharpness:5", "LEGENDARY DUNGEON SWORD"));
            List<String> off = tip(c, sword, true);
            c.check(section(off, "Missing Enchants").isEmpty() && !off.contains("Shift: missing enchants"), "OFF changed the tooltip: " + off);
            c.ctx().runOnClient(mc -> Mod.with(CFG, "Enabled", true));
            List<String> up = tip(c, sword, false);
            c.check(up.contains("Shift: missing enchants"), "hint missing with Shift up: " + up);
            c.check(section(up, "Missing Enchants").isEmpty(), "section shown with Shift up: " + up);
            List<String> down = tip(c, sword, true);
            c.check(!section(down, "Missing Enchants").isEmpty(), "section missing with Shift down: " + down);
            c.check(!down.contains("Shift: missing enchants"), "hint shown with Shift down: " + down);
            c.ctx().runOnClient(mc -> Mod.with(CFG, "Hint", false));
            c.check(!tip(c, sword, false).contains("Shift: missing enchants"), "hint shown while its switch is off");
            c.check(down.size() > off.size() + 2, "section added no lines: " + down.size() + " vs " + off.size());
            // nothing for a book, an item type with no enchants, or a plain item
            ItemStack book = give(c, stack("ENCHANTED_BOOK", "sharpness:6", "RARE"));
            c.check(tip(c, book, true).equals(tip(c, book, false)), "a book got a section or hint");
            ItemStack misc = give(c, stack("SOME_ORB", "", "EPIC REFORGE STONE"));
            c.check(section(tip(c, misc, true), "Missing Enchants").isEmpty(), "an unenchantable type got a section");
            ItemStack vanilla = give(c, "minecraft:stick");
            c.check(section(tip(c, vanilla, true), "Missing Enchants").isEmpty(), "a plain item got a section");
            c.note("missingench tooltip: OFF " + off.size() + " lines, hint with Shift up, " + down.size() + " lines with Shift down");
        } finally {
            Mod.setField(FEATURE, "shiftForTest", null);
            ItemCases.clear(c, 0);
        }
    }
}
