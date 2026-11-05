package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 2851-menu-reforge-helper: Reforge Helper ({@code reforge.ReforgeHelperFeature}, mod reforge-1104) in a fake Blacksmith
 * "Reforge Item" chest (item in slot 13, the Reforge button in 22, SkyHanni's layout). With a Legendary Dungeon Sword
 * carrying the Sharp reforge, the panel left of the menu lists exactly the nine NEU sword reforges and Sharp's Legendary
 * stats; a Reforge press with nothing wanted reaches the server; a real mouse click on the Sharp row saves "Sharp" as
 * wanted (on disk too) and the next Reforge press never reaches the server (the block counter goes up); a second click on
 * the row takes it off again and the press goes through; wanting Spicy (not the item's) lets it through. An Epic Dungeon
 * Chestplate lists the nine armor reforges instead.
 */
final class ReforgeHelperCases {

    private static final String CFG = "reforge.ReforgeHelperConfig";
    private static final String FEATURE = "reforge.ReforgeHelperFeature";
    private static final String TITLE = "Reforge Item";
    private static final List<String> SWORD = List.of("Epic", "Fair", "Fast", "Gentle", "Heroic", "Legendary", "Odd",
            "Sharp", "Spicy");
    private static final List<String> ARMOR = List.of("Clean", "Fierce", "Heavy", "Light", "Mythic", "Pure", "Smart",
            "Titanic", "Wise");

    private ReforgeHelperCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "2851-menu-reforge-helper", ReforgeHelperCases::helper);
    }

    private static JsonObject screen(String item, String name, String rarityLine) {
        return FlipCroesusCases.menu(TITLE, 6, Map.of(
                13, FlipCroesusCases.item(item, name, "§7Damage: §c+310", "", rarityLine),
                22, FlipCroesusCases.item("minecraft:anvil", "§aReforge Item", "§7Reforges the above item.", "",
                        "§eClick to reforge!")),
                Map.of(22, new JsonObject()));
    }

    private static void open(Session c, JsonObject screen) {
        MenuKit.reset(c);
        MenuKit.show(c, screen);
        MenuKit.awaitScreen(c, TITLE, 100);
        c.ctx().waitTicks(8);
    }

    /** A real mouse press on the Reforge button; returns how many clicks the server recorded on it. */
    private static int press(Session c) {
        int before = (int) FlipCroesusCases.clicksOn(c, TITLE).stream().filter(s -> s == 22).count();
        CroesusUiCases.hover(c, 22);
        c.ctx().getInput().pressMouse(dev.testkit.compat.McKeys.mouse(0));
        c.ctx().waitTicks(10);
        return (int) FlipCroesusCases.clicksOn(c, TITLE).stream().filter(s -> s == 22).count() - before;
    }

    /** A real mouse click on the panel row named {@code reforge}. */
    private static void clickRow(Session c, String reforge) {
        double[] p = c.onClient(mc -> (double[]) Mod.staticCall(FEATURE, "rowCenterForTest", reforge));
        c.check(p != null, "no panel row for " + reforge);
        if (p == null) {
            return;
        }
        double scale = CroesusUiCases.geometry(c)[3];
        c.ctx().getInput().setCursorPos(p[0] * scale, p[1] * scale);
        c.ctx().waitTicks(4);
        c.ctx().getInput().pressMouse(dev.testkit.compat.McKeys.mouse(0));
        c.ctx().waitTicks(6);
    }

    @SuppressWarnings("unchecked")
    private static List<String> rows(Session c) {
        return c.onClient(mc -> (List<String>) Mod.staticCall(FEATURE, "rowsForTest"));
    }

    private static int blocked(Session c) {
        return c.onClient(mc -> (Integer) Mod.staticCall(FEATURE, "blockedClicks"));
    }

    private static String wanted(Session c) {
        return c.onClient(mc -> (String) Mod.call(Mod.cfg(CFG), "getWanted"));
    }

    static void helper(Session c) throws Exception {
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CFG, "Enabled", true).set(CFG, "BlockWanted", true).set(CFG, "Wanted", "");
            open(c, screen("minecraft:diamond_sword[minecraft:custom_data={id:\"LIVID_DAGGER\",modifier:\"sharp\"}]",
                    "§6Sharp Livid Dagger", "§6§lLEGENDARY DUNGEON SWORD"));
            String header = c.onClient(mc -> (String) Mod.staticCall(FEATURE, "headerForTest"));
            List<String> rows = rows(c);
            c.note("header '" + header + "', rows " + rows);
            c.check("Legendary Sword".equals(header), "header '" + header + "', want 'Legendary Sword'");
            c.check(rows.stream().map(r -> r.substring(0, r.indexOf('|'))).toList().equals(SWORD),
                    "the sword's rows are " + rows + ", want " + SWORD);
            c.check(rows.contains("Sharp|+20☣ +75☠"), "Sharp's Legendary stats are not +20 Crit Chance +75 Crit Damage: " + rows);

            double[] g = CroesusUiCases.geometry(c);
            double[] rect = c.onClient(mc -> (double[]) Mod.staticCall("containercover.ContainerCover", "rectOf",
                    (String) Mod.field(FEATURE, "COVER")));
            c.note("screen width " + g[2] + ", menu left " + g[0] + " width " + g[4] + ", panel "
                    + (rect == null ? "none" : java.util.Arrays.toString(rect)));
            c.check(rect != null && rect[2] <= g[0], "the panel " + java.util.Arrays.toString(rect)
                    + " is not left of the menu (left " + g[0] + ")");
            double[] sharp = c.onClient(mc -> (double[]) Mod.staticCall(FEATURE, "rowCenterForTest", "Sharp"));
            if (sharp != null) {
                c.ctx().getInput().setCursorPos(sharp[0] * g[3], sharp[1] * g[3]);
                c.ctx().waitTicks(4);
                CroesusUiCases.shot(c, "panel");
            }
            int b0 = blocked(c);
            int free = press(c);
            c.check(free == 1, "with nothing wanted the Reforge press reached the server " + free + " time(s), want 1");

            clickRow(c, "Sharp");
            String w = wanted(c);
            Path file = c.onClient(mc -> (Path) Mod.field(CFG, "CONFIG_PATH"));
            String disk = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
            c.note("after clicking Sharp: wanted '" + w + "', file has Sharp " + disk.contains("Sharp"));
            c.check("Sharp".equals(w), "clicking the Sharp row left Wanted Reforges '" + w + "', want 'Sharp'");
            c.check(disk.contains("\"Sharp\""), "the click did not save Sharp to " + file);
            int held = press(c);
            int b1 = blocked(c);
            c.note("Sharp wanted: press reached the server " + held + " time(s), blocked " + (b1 - b0));
            c.check(held == 0, "the item has its wanted reforge but the Reforge press reached the server " + held + " time(s)");
            c.check(b1 - b0 == 1, "the block counter went up " + (b1 - b0) + ", want 1");

            clickRow(c, "Sharp");
            c.check(wanted(c).isEmpty(), "a second click on Sharp left Wanted Reforges '" + wanted(c) + "', want empty");
            int again = press(c);
            c.check(again == 1, "with Sharp unwanted again the press reached the server " + again + " time(s), want 1");

            clickRow(c, "Spicy");
            c.check("Spicy".equals(wanted(c)), "clicking Spicy left Wanted Reforges '" + wanted(c) + "'");
            int other = press(c);
            c.check(other == 1, "wanting Spicy blocked a Sharp item's press (" + other + " clicks reached the server)");
            c.check(blocked(c) == b1, "the block counter moved while the item had no wanted reforge");

            open(c, screen("minecraft:diamond_chestplate[minecraft:custom_data={id:\"POWER_WITHER_CHESTPLATE\"}]",
                    "§5Necron's Chestplate", "§5§lEPIC DUNGEON CHESTPLATE"));
            String h2 = c.onClient(mc -> (String) Mod.staticCall(FEATURE, "headerForTest"));
            List<String> armor = rows(c).stream().map(r -> r.substring(0, r.indexOf('|'))).toList();
            c.note("chestplate: header '" + h2 + "', rows " + armor);
            c.check("Epic Chestplate".equals(h2), "header '" + h2 + "', want 'Epic Chestplate'");
            c.check(armor.equals(ARMOR), "the chestplate's rows are " + armor + ", want " + ARMOR);
            MenuKit.reset(c);
        }
    }
}
