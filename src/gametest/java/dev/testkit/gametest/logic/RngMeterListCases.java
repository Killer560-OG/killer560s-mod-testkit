package dev.testkit.gametest.logic;

import dev.testkit.gametest.mod.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * 1117-logic-rngmeter-superpairs: the mod's Experimentation Table (Superpairs) RNG list names the same enchantment books
 * the SkyBlock 0.27.3 menu offers. The expected names are copied from a live capture of "(1/2) / (2/2) Experimentation
 * Table RNG" (2026-11-17 2:30 PM): Chance IV and V and Looting are not in it, nine more books are.
 */
public final class RngMeterListCases {

    private static final List<String> MENU_BOOKS = List.of(
            "Scavenger V", "Sharpness VI", "Life Steal IV", "Power VI", "Ender Slayer VI", "Thunderbolt VI", "Growth VI",
            "Blast Protection VI", "Respite III", "Venomous VI", "Projectile Protection VI", "Fire Protection VI", "Woodsplitter VI",
            "Giant Killer VI", "Drain IV", "Protection VI", "Titan Killer VI", "Thunderlord VII", "Giant Killer VII", "Gravity VI",
            "Critical VII", "Snipe IV", "Life Steal V", "First Strike V", "Fire Protection VII", "Thunderbolt VII", "Cubism VI",
            "Triple-Strike V", "Drain V", "Blast Protection VII", "Cleave VI", "Titan Killer VII", "Execute VI", "Power VII",
            "Projectile Protection VII", "Growth VII", "Sharpness VII", "Protection VII", "Prosecute VI");

    private RngMeterListCases() {
    }

    static void superpairs(LogicCase c) throws Exception {
        List<?> list = (List<?>) Mod.field("rngmeter.RngItemData", "EXPERIMENTATION_TABLE");
        TreeSet<String> books = new TreeSet<>();
        for (Object item : list) {
            String name = String.valueOf(Mod.call(item, "name"));
            if (name.matches(".* [IVX]+")) {
                books.add(name);
            }
        }
        c.check("the list has enchantment books to compare (the feature acted)", books.size() >= 30, books.size() + " books of " + list.size());
        List<String> extra = new ArrayList<>(books);
        extra.removeAll(MENU_BOOKS);
        List<String> missing = new ArrayList<>(MENU_BOOKS);
        missing.removeAll(books);
        c.check("every book is one the menu offers", extra.isEmpty(), "not in the menu: " + extra);
        c.check("every book the menu offers is listed", missing.isEmpty(), "not listed: " + missing);
        c.check("Chance IV and Looting stay off", !books.contains("Chance IV") && !books.contains("Chance V")
                && books.stream().noneMatch(b -> b.startsWith("Looting")), books.toString());
    }
}
