package dev.testkit.gametest.menu;

import dev.testkit.compat.McCompat;
import com.google.gson.JsonObject;

import dev.testkit.gametest.Fixtures;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import java.util.List;
import java.util.Map;

/** Dungeon menus: Croesus run view, auto close chest, Spirit Leap overlay, fast leap, class select, party finder. */
final class DungeonMenuCases {

    private DungeonMenuCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "230-menu-croesus-run-view", DungeonMenuCases::croesus);
        MenuSuite.test(s, "231-menu-autoclosechest", DungeonMenuCases::autoCloseChest);
        MenuSuite.test(s, "232-menu-spiritleap-overlay-key", DungeonMenuCases::spiritLeap);
        MenuSuite.test(s, "233-menu-fastleap-leap", DungeonMenuCases::fastLeap);
        MenuSuite.test(s, "234-menu-dungeonclass-select", DungeonMenuCases::classSelect);
        MenuSuite.test(s, "235-menu-partyfinder", DungeonMenuCases::partyFinder);
    }

    static void inDungeon(Session c) {
        c.hx().call("sidebar.set", Fixtures.byId("core.sidebar-catacombs-f7").payload().getAsJsonObject().deepCopy());
        c.waitUntil("DungeonState.getFloor() == F7",
                mc -> "F7".equals(Mod.staticCall("secrets.DungeonState", "getFloor")), 100);
    }

    static void leaveDungeon(Session c) {
        c.hx().sidebarClear();
        c.waitUntil("DungeonState.getFloor() == null",
                mc -> Mod.staticCall("secrets.DungeonState", "getFloor") == null, 100);
    }

    static void croesus(Session c) throws Exception {
        MenuKit.reset(c);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set("croesus.CroesusConfig", "ChestProfitEnabled", true);
            MenuKit.show(c, MenuKit.menu("menus.croesus-run-view-f7"));
            MenuKit.awaitScreen(c, "Catacombs - Floor VII", 100);
            c.waitUntil("ChestProfitFeature.currentRunViewChests() to hold 2 chests", mc ->
                    ((List<?>) Mod.staticCall("croesus.ChestProfitFeature", "currentRunViewChests")).size() == 2, 100);
            List<?> chests = c.onClient(mc -> List.copyOf((List<?>) Mod.staticCall("croesus.ChestProfitFeature", "currentRunViewChests")));
            Object bedrock = null;
            Object wood = null;
            for (Object ch : chests) {
                String type = String.valueOf(Mod.call(ch, "type"));
                if (type.contains("BEDROCK")) {
                    bedrock = ch;
                } else if (type.contains("WOOD")) {
                    wood = ch;
                }
            }
            c.check(bedrock != null && wood != null, "chests read: " + chests);
            long cost = ((Number) Mod.call(bedrock, "cost")).longValue();
            long woodCost = ((Number) Mod.call(wood, "cost")).longValue();
            int unpriced = ((Number) Mod.call(bedrock, "unpricedCount")).intValue();
            String floor = c.onClient(mc -> String.valueOf(Mod.staticCall("croesus.ChestProfitFeature", "currentRunViewFloor")));
            c.check(cost == 5_000_000L, "Bedrock cost " + cost + ", lore says 5,000,000 Coins");
            c.check(woodCost == 0, "Wood cost " + woodCost + ", lore says FREE");
            c.check(floor.contains("7"), "run view floor " + floor);
            c.note("Croesus run view: Bedrock cost " + cost + " (" + unpriced + " unpriced item(s) offline), Wood cost "
                    + woodCost + ", floor " + floor + "; items " + Mod.call(bedrock, "items"));
        } finally {
            MenuKit.reset(c);
        }
    }

    static void autoCloseChest(Session c) throws Exception {
        MenuKit.reset(c);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set("autoclosechest.AutoCloseChestConfig", "Enabled", true);
            // control first: outside a dungeon a "Chest" opens normally
            leaveDungeon(c);
            MenuKit.show(c, MenuKit.menu("menus.dungeon-secret-chest"));
            MenuKit.awaitScreen(c, "Chest", 100);
            MenuKit.reset(c);
            inDungeon(c);
            long before = c.events("container.close").size();
            int id = MenuKit.show(c, MenuKit.menu("menus.dungeon-secret-chest"));
            c.waitUntil("the server to get a close for container " + id, mc -> c.events("container.close").stream()
                    .anyMatch(e -> e.get("containerId").getAsInt() == id), 60);
            boolean everShown = c.onClient(mc -> "Chest".equals(MenuKit.screenTitle(mc)));
            c.check(!everShown, "the secret chest screen is open on the client");
            c.note("outside a dungeon 'Chest' opened (control); in F7 the server got container.close for " + id
                    + " and no screen showed (" + (c.events("container.close").size() - before) + " close event(s))");
        } finally {
            leaveDungeon(c);
            MenuKit.reset(c);
        }
    }

    static void spiritLeap(Session c) throws Exception {
        MenuKit.reset(c);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set("spiritleap.SpiritLeapOverlayConfig", "Enabled", true);
            MenuKit.show(c, MenuKit.menu("menus.spirit-leap"));
            MenuKit.awaitScreen(c, "Spirit Leap", 100);
            c.waitUntil("the overlay to hide the vanilla menu", mc ->
                    (Boolean) Mod.staticCall("spiritleap.SpiritLeapOverlayFeature", "isHiding", McCompat.screen(mc)), 100);
            c.ctx().waitTicks(10);   // SETTLE_MS = 110 before keys act (SpiritLeapOverlayFeature.java:72)
            c.ctx().getInput().pressKey(49);   // GLFW_KEY_1 -> spot 0
            JsonObject action = MenuKit.awaitEvent(c, "menu.action", 60);
            int slot = action.get("slot").getAsInt();
            String input = action.get("input").getAsString();
            List<?> order = c.onClient(mc -> List.copyOf((List<?>) Mod.call(Mod.cfg("leapmenu.LeapMenuConfig"), "getLastLeapOrder")));
            c.check(slot == 11 && input.equals("PICKUP"), "key 1 clicked slot " + slot + " " + input + ", expected slot 11 PICKUP (Alice)");
            c.check(order.contains("Alice") && order.contains("Bob") && order.contains("Carol"),
                    "LeapMenuConfig.getLastLeapOrder() " + order);
            c.note("Spirit Leap overlay: key 1 -> server click slot " + slot + " " + input + "; leap order noted " + order);
        } finally {
            MenuKit.reset(c);
        }
    }

    static void fastLeap(Session c) throws Exception {
        MenuKit.reset(c);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set("fastleap.FastLeapConfig", "Enabled", true);
            inDungeon(c);
            c.hx().give(0, MenuKit.item("items.spirit-leap"));
            c.waitUntil("the leap in hotbar slot 0", mc -> "SPIRIT_LEAP".equals(
                    Mod.staticCall("cheatutils.CheatUtils", "skyblockId", mc.player.getInventory().getItem(0))), 60);
            c.hx().call("menu.onUse", "id", "SPIRIT_LEAP", "menu", MenuKit.menu("menus.spirit-leap"));
            boolean on = c.onClient(mc -> (Boolean) Mod.get("fastleap.FastLeapConfig", "isEnabled"));
            if (!MenuKit.cheat()) {
                c.check(!on, "legit jar: FastLeapConfig.isEnabled() is true");
                c.note("legit jar: Fast Leap getter reads false with the setting on (cheat-only)");
                return;
            }
            c.ctx().runOnClient(mc -> Mod.staticCall("fastleap.LeapManager", "leap", "Bob", false, false, false));
            JsonObject action = MenuKit.awaitEvent(c, "menu.action", 200);
            c.check(!c.events("use_item").isEmpty(), "no use_item before the leap click");
            c.check(action.get("slot").getAsInt() == 13, "Fast Leap clicked slot " + action.get("slot") + ", Bob's head is 13");
            MenuKit.awaitNoScreen(c, 60);
            c.waitUntil("LeapManager idle", mc -> !(Boolean) Mod.staticCall("fastleap.LeapManager", "isBusy"), 100);
            long last = c.onClient(mc -> ((Number) Mod.staticCall("fastleap.LeapManager", "lastLeapMs")).longValue());
            c.note("Fast Leap to Bob: use_item, Spirit Leap opened, click slot 13 " + action.get("input")
                    + ", menu closed; lastLeapMs " + last + "; " + MenuKit.cadence(c));
        } finally {
            c.hx().call("menu.onUse", "id", "SPIRIT_LEAP", "menu", null);
            c.hx().call("give", "slot", 0, "stack", "minecraft:air");
            leaveDungeon(c);
            MenuKit.reset(c);
        }
    }

    static void classSelect(Session c) throws Exception {
        MenuKit.reset(c);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set("dungeonclass.ClassSelectionOverlayConfig", "Enabled", true)
                    .set("partyfinder.PartyFinderOverlayConfig", "Enabled", true);
            MenuKit.show(c, MenuKit.menu("menus.catacombs-gate-classes"));
            MenuKit.awaitScreen(c, "Catacombs Gate", 100);
            c.waitUntil("ClassSelectionOverlay to find 5 class slots", mc ->
                    ((Map<?, ?>) Mod.field("dungeonclass.ClassSelectionOverlay", "classSlots")).size() == 5, 100);
            c.waitUntil("PartyFinderOverlay.getCurrentRole() == MAGE (slot 45 lore)", mc ->
                    "MAGE".equals(String.valueOf(Mod.staticCall("partyfinder.PartyFinderOverlay", "getCurrentRole"))), 100);
            String keys = c.onClient(mc -> ((Map<?, ?>) Mod.field("dungeonclass.ClassSelectionOverlay", "classSlots")).keySet().toString());
            c.check(c.events("container.click").isEmpty(), "class select sent a click by itself");
            c.note("class select: overlay found " + keys + "; party finder read current role MAGE from slot 45");
        } finally {
            MenuKit.reset(c);
        }
    }

    static void partyFinder(Session c) throws Exception {
        MenuKit.reset(c);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set("partyfinder.PartyFinderOverlayConfig", "Enabled", true);
            MenuKit.show(c, MenuKit.menu("menus.party-finder"));
            MenuKit.awaitScreen(c, "Party Finder", 100);
            c.waitUntil("PartyFinderOverlay to parse the party in slot 10", mc ->
                    ((Object[]) Mod.field("partyfinder.PartyFinderOverlay", "parties"))[10] != null, 100);
            Object party = c.onClient(mc -> ((Object[]) Mod.field("partyfinder.PartyFinderOverlay", "parties"))[10]);
            int floor = ((Number) Mod.call(party, "floor")).intValue();
            List<?> members = (List<?>) Mod.call(party, "members");
            List<?> missing = (List<?>) Mod.call(party, "missing");
            String text = String.valueOf(party);
            c.check(floor == 7, "floor " + floor + " from 'Floor: Floor VII'");
            c.check(members.size() == 2 && text.contains("Bob") && text.contains("Eve"), "members " + members);
            c.check(missing.size() == 3, "missing classes " + missing + " (Mage and Tank are taken)");
            c.note("party finder slot 10: " + text);
        } finally {
            MenuKit.reset(c);
        }
    }
}
