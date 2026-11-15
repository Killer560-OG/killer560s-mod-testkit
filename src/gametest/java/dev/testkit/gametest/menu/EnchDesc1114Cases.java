package dev.testkit.gametest.menu;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Hide Enchant Descriptions (enchdesc-1114): the tooltip of a Skyblock drill in Hypixel's expanded enchant layout
 * (name line in its rarity color, gray description lines under it), built line by line, read back through
 * {@code Screen.getTooltipFromItem}. Cases: OFF unchanged; ON removes exactly the description lines and keeps every other
 * line in order; the comma layout is untouched; an Enchanted Book keeps its description unless Also On Enchanted Books;
 * Enchant Colors still colors the names when both are on.
 */
final class EnchDesc1114Cases {

    private static final String CFG = "enchantcolors.EnchantColorsConfig";

    private EnchDesc1114Cases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1114-enchdesc-hide", EnchDesc1114Cases::hide);
        MenuSuite.test(s, "1114-enchdesc-compact", EnchDesc1114Cases::compact);
        MenuSuite.test(s, "1114-enchdesc-book", EnchDesc1114Cases::book);
        MenuSuite.test(s, "1114-enchdesc-colors", EnchDesc1114Cases::colors);
    }

    /** kind: n = enchant name line, d = description (must go), o = anything else (must stay). */
    private record Ln(char kind, String text, String snbt) {
    }

    private static Ln line(char kind, String text, String color, boolean bold) {
        return new Ln(kind, text, "{text:\"" + text + "\",color:\"" + color + "\",italic:false" + (bold ? ",bold:true" : "") + "}");
    }

    private static Ln n(String text, String color, boolean bold) {
        return line('n', text, color, bold);
    }

    private static Ln d(String text) {
        return line('d', text, "gray", false);
    }

    private static Ln o(String text, String color) {
        return line('o', text, color, false);
    }

    private static Ln blank() {
        return new Ln('o', "", "{text:\"\"}");
    }

    /** "Compact III 959": the name, then the stacking counter in dark gray. */
    private static Ln counter(String name, String color, String count) {
        return new Ln('n', name + " " + count, "[{text:\"" + name + " \",color:\"" + color + "\",italic:false},{text:\""
                + count + "\",color:\"dark_gray\",italic:false}]");
    }

    /** The screenshot's drill: stats, blank, five enchants with descriptions, blank, the sections after. */
    private static List<Ln> drill() {
        List<Ln> l = new ArrayList<>();
        l.add(o("Mining Speed: +2,150", "gray"));
        l.add(o("Mining Fortune: +100", "gray"));
        l.add(o("Gemstone: [X] [Y]", "gray"));
        l.add(blank());
        l.add(n("Flowstate III", "light_purple", true));
        l.add(d("Consecutive blocks broken grant +3"));
        l.add(d("Mining Speed. Stops after 10s of not mining and caps at"));
        l.add(d("200 blocks."));
        l.add(counter("Compact III", "blue", "959"));
        l.add(d("Gain +30 Mining Wisdom and a 0.29% chance to"));
        l.add(d("drop an enchanted item."));
        l.add(d("1.5k blocks to tier up!"));
        l.add(n("Efficiency V", "blue", false));
        l.add(d("Grants +110 Mining Speed."));
        l.add(n("Experience III", "blue", false));
        l.add(d("Gives a 7.5% chance for mining to drop double experience."));
        l.add(n("Fortune IV", "blue", false));
        l.add(d("Grants +40 Mining Fortune."));
        l.add(blank());
        l.add(o("Gemstone Fuel Tank: 3,000", "gray"));
        l.add(o("Drill Engine: Mithril-Infused Engine", "gray"));
        l.add(o("Ability: Pickobulus RIGHT CLICK", "gold"));
        l.add(o("Throw your pickaxe to create an explosion.", "gray"));
        l.add(blank());
        l.add(new Ln('o', "LEGENDARY DRILL", "{text:\"LEGENDARY DRILL\",color:\"gold\",bold:true,italic:false}"));
        l.add(blank());
        l.add(o("Lowest BIN: 12,345,678", "gold"));
        return l;
    }

    private static String stackOf(String id, String enchants, List<Ln> lines) {
        StringBuilder sb = new StringBuilder("minecraft:stick[custom_data={id:\"" + id + "\",enchantments:{" + enchants
                + "}},lore=[");
        for (int i = 0; i < lines.size(); i++) {
            sb.append(i == 0 ? "" : ",").append(lines.get(i).snbt());
        }
        return sb.append("]]").toString();
    }

    private static ItemStack giveRaw(Session c, String stack) {
        c.hx().give(0, stack);
        c.waitUntil("test stack in slot 0", mc -> !mc.player.getInventory().getItem(0).isEmpty(), 60);
        c.ctx().waitTicks(2);
        return c.onClient(mc -> mc.player.getInventory().getItem(0));
    }

    private static List<String> tip(Session c, ItemStack stack) {
        return c.onClient(mc -> {
            Mod.staticCall("enchantcolors.EnchantColorsFeature", "clearCache");
            List<String> out = new ArrayList<>();
            for (Component line : Screen.getTooltipFromItem(mc, stack)) {
                out.add(line.getString());
            }
            return out;
        });
    }

    private static List<String> texts(List<Ln> lines, boolean withDescriptions) {
        List<String> out = new ArrayList<>();
        for (Ln l : lines) {
            if (withDescriptions || l.kind() != 'd') {
                out.add(l.text());
            }
        }
        return out;
    }

    /** The tooltip with its first (name) line cut off. */
    private static List<String> lore(List<String> tip) {
        return tip.subList(1, tip.size());
    }

    private static final String DRILL_ENCH = "ultimate_flowstate:3,compact:3,efficiency:5,experience:3,fortune:4";

    static void hide(Session c) throws Exception {
        List<Ln> lines = drill();
        long desc = lines.stream().filter(l -> l.kind() == 'd').count();
        long names = lines.stream().filter(l -> l.kind() == 'n').count();
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CFG, "Enabled", false).set(CFG, "HideDescriptions", false);
            ItemStack stack = giveRaw(c, stackOf("DIAMOND_DRILL_4", DRILL_ENCH, lines));
            List<String> off = lore(tip(c, stack));
            c.check(off.equals(texts(lines, true)), "OFF changed the tooltip: " + off);
            c.ctx().runOnClient(mc -> Mod.with(CFG, "HideDescriptions", true));
            List<String> on = lore(tip(c, stack));
            List<String> want = texts(lines, false);
            c.check(on.equals(want), "ON: want " + want + " got " + on);
            c.check(off.size() - on.size() == desc, "removed " + (off.size() - on.size()) + ", expected " + desc);
            c.note("enchdesc hide: " + off.size() + " lore lines OFF, " + on.size() + " ON; removed " + desc
                    + " description lines, kept " + names + " enchant names and " + (on.size() - names) + " other lines");
        } finally {
            ItemCases.clear(c, 0);
        }
    }

    static void compact(Session c) throws Exception {
        List<Ln> lines = new ArrayList<>();
        lines.add(o("Damage: +300", "gray"));
        lines.add(blank());
        lines.add(new Ln('n', "Efficiency V, Fortune IV, Compact III 959", "[{text:\"Efficiency V, Fortune IV, Compact III \",color:\"blue\","
                + "italic:false},{text:\"959\",color:\"dark_gray\",italic:false}]"));
        lines.add(blank());
        lines.add(o("Ability: Pickobulus RIGHT CLICK", "gold"));
        lines.add(o("Throw your pickaxe to create an explosion.", "gray"));
        lines.add(blank());
        lines.add(o("RARE PICKAXE", "blue"));
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CFG, "Enabled", false).set(CFG, "HideDescriptions", true);
            ItemStack stack = giveRaw(c, stackOf("MITHRIL_PICKAXE", "efficiency:5,fortune:4,compact:3", lines));
            List<String> got = lore(tip(c, stack));
            c.check(got.equals(texts(lines, true)), "compact layout changed: " + got);
            c.note("enchdesc compact: " + got.size() + " lore lines kept, none removed");
        } finally {
            ItemCases.clear(c, 0);
        }
    }

    static void book(Session c) throws Exception {
        List<Ln> lines = new ArrayList<>();
        lines.add(n("Sharpness VI", "blue", false));
        lines.add(d("Increases melee damage dealt by 30%."));
        lines.add(blank());
        lines.add(o("Use this on an item in an Anvil to apply it!", "gray"));
        lines.add(blank());
        lines.add(o("RARE", "blue"));
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CFG, "Enabled", false).set(CFG, "HideDescriptions", true).set(CFG, "HideOnBooks", false);
            ItemStack stack = giveRaw(c, stackOf("ENCHANTED_BOOK", "sharpness:6", lines));
            List<String> kept = lore(tip(c, stack));
            c.check(kept.equals(texts(lines, true)), "a book lost its description by default: " + kept);
            c.ctx().runOnClient(mc -> Mod.with(CFG, "HideOnBooks", true));
            List<String> hidden = lore(tip(c, stack));
            c.check(hidden.equals(texts(lines, false)), "Also On Enchanted Books did not hide it: " + hidden);
            c.note("enchdesc book: default keeps " + kept.size() + " lines, Also On Enchanted Books leaves " + hidden.size());
        } finally {
            ItemCases.clear(c, 0);
        }
    }

    static void colors(Session c) throws Exception {
        List<Ln> lines = new ArrayList<>();
        lines.add(n("Sharpness VI", "blue", false));
        lines.add(d("Increases melee damage dealt by 30%."));
        lines.add(n("Ultimate Wise V", "light_purple", true));
        lines.add(d("Reduces the ability mana cost of this item by 50%."));
        lines.add(blank());
        lines.add(o("RARE", "blue"));
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CFG, "Enabled", true).set(CFG, "HideDescriptions", false);
            ItemStack stack = giveRaw(c, stackOf("HYPERION", "sharpness:6,ultimate_wise:5", lines));
            Map<String, Integer> colorsOnly = c.onClient(mc -> {
                Mod.staticCall("enchantcolors.EnchantColorsFeature", "clearCache");
                return ItemCases.segments(Screen.getTooltipFromItem(mc, stack));
            });
            c.ctx().runOnClient(mc -> Mod.with(CFG, "HideDescriptions", true));
            Map<String, Integer> both = c.onClient(mc -> {
                Mod.staticCall("enchantcolors.EnchantColorsFeature", "clearCache");
                return ItemCases.segments(Screen.getTooltipFromItem(mc, stack));
            });
            Integer sharp = ItemCases.colourOf(both, "Sharpness");
            Integer wise = ItemCases.colourOf(both, "Ultimate Wise");
            c.check(sharp != null && sharp == 0xFFAA00, "Sharpness VI with both on: " + sharp + ", expected GREAT ffaa00 (was blue 5555ff)");
            c.check(wise != null && wise == 0xFF55FF, "Ultimate Wise V with both on: " + wise);
            c.check(ItemCases.colourOf(colorsOnly, "Increases melee") != null, "colors-only run lost the description it should keep");
            c.check(ItemCases.colourOf(both, "Increases melee") == null && ItemCases.colourOf(both, "Reduces the ability") == null,
                    "both on still shows a description: " + both);
            c.note("enchdesc colors: both on -> Sharpness VI " + Integer.toHexString(sharp) + ", Ultimate Wise V "
                    + Integer.toHexString(wise) + ", 2 description lines gone");
        } finally {
            ItemCases.clear(c, 0);
        }
    }
}
