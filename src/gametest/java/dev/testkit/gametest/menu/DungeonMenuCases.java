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
        MenuSuite.test(s, "1077-menu-class-overlay-editor", DungeonMenuCases::classOverlayEditor);
        MenuSuite.test(s, "235-menu-partyfinder", DungeonMenuCases::partyFinder);
        MenuSuite.test(s, "236-menu-partyfinder-style", PartyFinderStyleCases::style);
        MenuSuite.test(s, "400-menu-partyfinder-stats", PartyFinderStatsCases::stats);
        MenuSuite.test(s, "406-menu-partyfinder-stats-coverage", PartyFinderCoverageCases::coverage);
        MenuSuite.test(s, "410-menu-partyfinder-relay", PartyFinderRelayCases::relay);
        MenuSuite.test(s, "411-menu-partyfinder-speed", PartyFinderSpeedCases::speed);
        MenuSuite.test(s, "412-menu-partyfinder-background", PartyFinderSpeedCases::background);
        // Real backend, about 25 real requests: only with TESTKIT_PF_LIVE=1 and -PnetOnline.
        if ("1".equals(System.getenv("TESTKIT_PF_LIVE"))) {
            MenuSuite.test(s, "408-menu-partyfinder-stats-live", PartyFinderCoverageCases::live);
        }
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
            if (!Mod.isCheat()) {
                // Auto Close Chest is cheat-build only (mod, 2026-10-07): in a legit jar the saved setting forced ON
                // must still leave the chest open and send no close. (391 also checks its code is not in the jar.)
                MenuKit.awaitScreen(c, "Chest", 100);
                c.ctx().waitTicks(20);
                long closes = c.events("container.close").stream()
                        .filter(e -> e.get("containerId").getAsInt() == id).count();
                c.check(closes == 0, "a legit jar closed the secret chest (" + closes + " close event(s))");
                c.note("legit jar: in F7 the 'Chest' opened and stayed open with Auto Close Chest forced ON");
                return;
            }
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

    /** mod leap-queue-1015 (killer560, 2026-10-15: "have it use the /leaporder process"): on the real class-selection
     *  screen the party bar's name chip and a class box, clicked with the real mouse, set that player's class in the Leap
     *  Order's own data (killer560smod-leapmenu.json, no Class Overrides file any more), and the bar's Edit Classes chip
     *  opens the Leap Order editor over the screen. */
    static void classOverlayEditor(Session c) throws Exception {
        MenuKit.reset(c);
        String dungeonClass = "dungeonclass.DungeonClass";
        java.nio.file.Path leapPath = (java.nio.file.Path) Mod.field("leapmenu.LeapMenuConfig", "CONFIG_PATH");
        byte[] leapBytes = java.nio.file.Files.exists(leapPath) ? java.nio.file.Files.readAllBytes(leapPath) : null;
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set("dungeonclass.ClassSelectionOverlayConfig", "Enabled", true);
            c.onClient(mc -> {
                Mod.staticCall("dungeonclass.ClassOverrides", "clear", "LoAnna");
                Mod.staticCall("leapmenu.PartyTracker", "noteTeammates", List.of("LoAnna", "LoBen"));
                return null;
            });
            c.check(!c.onClient(mc -> (Boolean) Mod.staticCall("dungeonclass.ClassOverrides", "has", "LoAnna")),
                    "LoAnna already had a class pick");
            MenuKit.show(c, MenuKit.menu("menus.catacombs-gate-classes"));
            MenuKit.awaitScreen(c, "Catacombs Gate", 100);
            c.waitUntil("ClassSelectionOverlay to find 5 class slots", mc ->
                    ((Map<?, ?>) Mod.field("dungeonclass.ClassSelectionOverlay", "classSlots")).size() == 5, 100);
            c.waitUntil("the party bar to draw its chips", mc -> {
                Map<?, ?> chips = (Map<?, ?>) Mod.staticCall("dungeonclass.ClassSelectionOverlay", "chipBoundsForTest");
                return chips.containsKey("LoAnna") && chips.containsKey("<leap order>");
            }, 100);
            // click LoAnna's chip, then the Mage box
            int[] chip = c.onClient(mc -> (int[]) ((Map<?, ?>) Mod.staticCall("dungeonclass.ClassSelectionOverlay",
                    "chipBoundsForTest")).get("LoAnna"));
            guiClick(c, (chip[0] + chip[2]) / 2.0, (chip[1] + chip[3]) / 2.0);
            double[] mage = c.onClient(mc -> {
                Object screen = McCompat.screen(mc);
                Object slot = ((Map<?, ?>) Mod.field("dungeonclass.ClassSelectionOverlay", "classSlots"))
                        .get(Mod.enumValue(dungeonClass, "MAGE"));
                int lx = (Integer) Mod.field(screen, "leftPos");
                int ty = (Integer) Mod.field(screen, "topPos");
                return new double[]{lx + (Integer) Mod.field(slot, "x") + 8, ty + (Integer) Mod.field(slot, "y") + 8};
            });
            guiClick(c, mage[0], mage[1]);
            String pick = c.onClient(mc -> String.valueOf(Mod.staticCall("dungeonclass.ClassOverrides", "classOf",
                    "LoAnna", null)));
            c.check(pick.equals("MAGE"), "clicking LoAnna's chip then the Mage box left her class at " + pick);
            java.nio.file.Path leapFile = (java.nio.file.Path) Mod.field("leapmenu.LeapMenuConfig", "CONFIG_PATH");
            String saved = java.nio.file.Files.readString(leapFile);
            c.check(saved.contains("classPicks") && saved.contains("LoAnna"),
                    "the pick is not in the Leap Order data (killer560smod-leapmenu.json)");
            // the Edit Classes chip
            int[] edit = c.onClient(mc -> (int[]) ((Map<?, ?>) Mod.staticCall("dungeonclass.ClassSelectionOverlay",
                    "chipBoundsForTest")).get("<leap order>"));
            guiClick(c, (edit[0] + edit[2]) / 2.0, (edit[1] + edit[3]) / 2.0);
            String now = c.onClient(mc -> McCompat.screen(mc) == null ? "(none)" : McCompat.screen(mc).getClass().getSimpleName());
            c.check(now.equals("LeapOrderScreen"), "the Edit Classes chip opened " + now);
            c.note("class overlay: chip LoAnna + Mage box -> pick " + pick + " saved in killer560smod-leapmenu.json; Edit Classes chip -> " + now);
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                Mod.staticCall("dungeonclass.ClassOverrides", "clear", "LoAnna");
                @SuppressWarnings("unchecked")
                java.util.Collection<String> members = (java.util.Collection<String>) Mod.field("leapmenu.PartyTracker", "MEMBERS");
                members.removeIf(n -> n.equals("LoAnna") || n.equals("LoBen"));
                try {
                    if (leapBytes != null) {
                        java.nio.file.Files.write(leapPath, leapBytes);
                    } else {
                        java.nio.file.Files.deleteIfExists(leapPath);
                    }
                } catch (java.io.IOException e) {
                    throw new AssertionError(e);
                }
                Mod.staticCall("leapmenu.LeapMenuConfig", "load");
                return null;
            });
            MenuKit.reset(c);
        }
    }

    /** A real left click at a point in plain GUI units of a vanilla container screen (no Auto Scale on it). */
    private static void guiClick(Session c, double gx, double gy) {
        double[] at = c.onClient(mc -> {
            double scale = mc.getWindow().getGuiScale();
            return new double[]{gx * scale, gy * scale};
        });
        c.ctx().getInput().setCursorPos(at[0], at[1]);
        c.ctx().waitTicks(2);
        c.ctx().getInput().pressMouse(0);
        c.ctx().waitTicks(3);
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
