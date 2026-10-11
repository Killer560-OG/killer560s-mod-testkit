package dev.testkit.gametest.menu;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import java.util.List;

/**
 * 708-menu-storagesearch-equipment-wardrobe (mod branch item-ui). killer560, 2026-10-11: "Storage search needs to
 * search through my equipment wardrobe as well." A real server menu titled like Hypixel's Equipment Wardrobe
 * ("Equipment Sets (1/2)", /equipment) is opened and closed; the Storage Search index must then find the necklace
 * that was in it, as an Equipment Wardrobe page. The rest of the item-ui list is UI-suite cases 701-712.
 */
final class ItemUiMenuCases {

    private ItemUiMenuCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "708-menu-storagesearch-equipment-wardrobe", ItemUiMenuCases::equipmentWardrobe);
    }

    static void equipmentWardrobe(Session c) throws Exception {
        MenuKit.reset(c);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set("storagesearch.StorageSearchConfig", "Enabled", true);
            int before = c.onClient(mc -> ((List<?>) Mod.call(Mod.staticCall("storagesearch.StorageSearchIndex", "build", false),
                    "filter", "glowmoss", false, false)).size());
            MenuKit.show(c, MenuKit.obj("{\"title\":\"Equipment Sets (1/2)\",\"rows\":6,\"slots\":{"
                    + "\"0\":{\"item\":\"minecraft:red_stained_glass_pane\",\"name\":\"§aSlot 1 Necklace\"},"
                    + "\"9\":{\"item\":\"minecraft:player_head\",\"name\":\"§5Glowmoss Necklace\","
                    + "\"lore\":[\"§7Health: §a+10\"]}}}"));
            MenuKit.awaitScreen(c, "Equipment Sets (1/2)", 100);
            c.ctx().waitTicks(10);
            MenuKit.reset(c); // the close is what records the page
            List<?> hits = c.onClient(mc -> (List<?>) Mod.call(Mod.staticCall("storagesearch.StorageSearchIndex", "build", false),
                    "filter", "glowmoss", false, false));
            String what = c.onClient(mc -> hits.isEmpty() ? "none"
                    : Mod.call(hits.get(0), "type") + " @ " + Mod.call(hits.get(0), "location"));
            c.note("'glowmoss' before the visit: " + before + " hit(s); after opening and closing 'Equipment Sets (1/2)': "
                    + hits.size() + " -> " + what);
            c.check(before == 0, "the necklace was already indexed before the visit (" + before + "), the case would be vacuous");
            c.check(hits.size() == 1, "the Equipment Wardrobe page was not indexed: " + hits.size() + " hit(s)");
            c.check(what.startsWith("EQUIPMENT @ Equipment Wardrobe p1"), "indexed as " + what);
        } finally {
            MenuKit.reset(c);
        }
    }
}
