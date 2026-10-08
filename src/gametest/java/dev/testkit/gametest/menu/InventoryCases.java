package dev.testkit.gametest.menu;

import dev.testkit.compat.McCompat;
import com.google.gson.JsonObject;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Inventory-side menus: Sell (Auto Sell), Item Protect on a loss screen, the sorter, slot binds, storage overlay and
 * search, pets, loadout keys, the auction listing helper (never its clipboard button), the bazaar menu dump, and the
 * Chocolate Factory clicker.
 */
final class InventoryCases {

    private InventoryCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "240-menu-autosell-sell-menu", InventoryCases::autosell);
        MenuSuite.test(s, "241-menu-itemprotect-bin-auction", InventoryCases::itemProtect);
        MenuSuite.test(s, "242-menu-invsort-swap", InventoryCases::invsort);
        MenuSuite.test(s, "243-menu-slotbinds-shift-click", InventoryCases::slotBinds);
        MenuSuite.test(s, "244-menu-storageoverlay-ender-chest", InventoryCases::storageOverlay);
        MenuSuite.test(s, "245-menu-storagesearch-index", InventoryCases::storageSearch);
        MenuSuite.test(s, "246-menu-petwheel-scan-summon", InventoryCases::petWheel);
        MenuSuite.test(s, "247-menu-loadoutkeybinds", InventoryCases::loadout);
        MenuSuite.test(s, "248-menu-auction-listing-helper", InventoryCases::listingHelper);
        MenuSuite.test(s, "249-menu-bazaarflip-menu-dump", InventoryCases::bazaarDump);
        MenuSuite.test(s, "250-menu-chocolate-factory", InventoryCases::chocolate);
        PetWheelInstantCases.register(s);
        BazaarOrderCases.register(s);
        InvStorageMenuCases.register(s);
    }

    static void clearInv(Session c) {
        for (int i = 0; i < 36; i++) {
            c.hx().call("give", "slot", i, "stack", "minecraft:air");
        }
    }

    static void autosell(Session c) throws Exception {
        MenuKit.reset(c);
        clearInv(c);
        Object cfgObj = c.onClient(mc -> Mod.cfg("autosell.AutoSellConfig"));
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set("autosell.AutoSellConfig", "Enabled", true);
            c.ctx().runOnClient(mc -> Mod.call(cfgObj, "addSellIdentity", "ENCHANTED_DIAMOND"));
            // The default sell-screen pattern: an earlier scenario in the same client (InventoryAutomationTests)
            // points it at a plain chest and leaves it there, which made this refuse the Hypixel "Sell" menu.
            c.ctx().runOnClient(mc -> Mod.call(cfgObj, "setScreenTitlePattern", "(?i).*sell.*"));
            c.hx().call("give", "slot", 0, "stack", MenuKit.item("items.enchanted-diamond"), "count", 16);
            c.hx().call("give", "slot", 1, "stack", "minecraft:stone", "count", 3);
            MenuKit.show(c, MenuKit.menu("menus.sell"));
            MenuKit.awaitScreen(c, "Sell", 100);
            c.ctx().waitTicks(5);
            String refusal = c.onClient(mc -> (String) Mod.staticCall("autosell.AutoSellFeature", "start"));
            if (!MenuKit.cheat()) {
                c.check(refusal != null, "legit jar: AutoSellFeature.start() accepted");
                c.ctx().waitTicks(40);
                c.check(c.events("container.click").isEmpty(), "legit jar clicked in the Sell menu");
                c.note("legit jar: start() refused (" + refusal + "), 0 clicks");
                return;
            }
            c.check(refusal == null, "AutoSellFeature.start() refused: " + refusal);
            JsonObject sold = MenuKit.awaitEvent(c, "sell.sold", 200);
            c.waitUntil("Auto Sell to finish", mc -> !(Boolean) Mod.staticCall("autosell.AutoSellFeature", "isRunning"), 300);
            List<String> ids = new ArrayList<>();
            c.events("sell.sold").forEach(e -> ids.add(e.get("skyblockId").getAsString() + "x" + e.get("count").getAsInt()));
            c.check(ids.size() == 1 && ids.get(0).equals("ENCHANTED_DIAMONDx16"), "sold " + ids + ", expected only the Enchanted Diamond stack");
            boolean stoneKept = c.onClient(mc -> mc.player.getInventory().getItem(1).is(Items.STONE));
            c.check(stoneKept, "the stone (not on the sell list) is gone");
            c.check(sold.get("count").getAsInt() == 16, "sold count " + sold);
            c.note("Auto Sell in 'Sell': sold " + ids + " by " + c.events("container.click").get(0).get("input").getAsString()
                    + "; stone kept; " + MenuKit.cadence(c));
        } finally {
            c.ctx().runOnClient(mc -> Mod.call(cfgObj, "removeSellIdentity", "ENCHANTED_DIAMOND"));
            MenuKit.reset(c);
            clearInv(c);
        }
    }

    static void itemProtect(Session c) throws Exception {
        MenuKit.reset(c);
        clearInv(c);
        Object cfgObj = c.onClient(mc -> Mod.cfg("itemprotect.ItemProtectConfig"));
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set("itemprotect.ItemProtectConfig", "Enabled", true).set("itemprotect.ItemProtectConfig", "ProtectItemEnabled", true);
            boolean had = c.onClient(mc -> (Boolean) Mod.call(cfgObj, "hasProtectedKey", "hx-hyp-1"));
            if (!had) {
                c.ctx().runOnClient(mc -> Mod.call(cfgObj, "toggleProtectedKey", "hx-hyp-1"));
            }
            c.hx().give(0, MenuKit.item("items.hyperion-protected-uuid"));
            c.hx().give(1, "minecraft:diamond");
            MenuKit.show(c, MenuKit.obj("{\"title\":\"Create BIN Auction\",\"rows\":6,\"fill\":true}"));
            MenuKit.awaitScreen(c, "Create BIN Auction", 100);
            c.ctx().waitTicks(5);
            // a real screen click goes through AbstractContainerScreen.slotClicked, where Item Protect sits
            int hotbar0 = 54 + 27;
            c.ctx().runOnClient(mc -> screenClick(McCompat.screen(mc), hotbar0, ContainerInput.QUICK_MOVE));
            c.ctx().waitTicks(20);
            int blocked = c.events("container.click").size();
            c.ctx().runOnClient(mc -> screenClick(McCompat.screen(mc), hotbar0 + 1, ContainerInput.QUICK_MOVE));
            c.waitUntil("the unprotected diamond's click to reach the server", mc -> !c.events("container.click").isEmpty(), 60);
            int slot = c.events("container.click").get(0).get("slot").getAsInt();
            c.check(blocked == 0, "the protected Hyperion's shift-click reached the server");
            c.check(slot == hotbar0 + 1, "the control click arrived on slot " + slot);
            c.note("Create BIN Auction: protected Hyperion (uuid hx-hyp-1) shift-click blocked client-side (0 packets); "
                    + "an unprotected diamond's went through (slot " + slot + ")");
            if (!had) {
                c.ctx().runOnClient(mc -> Mod.call(cfgObj, "toggleProtectedKey", "hx-hyp-1"));
            }
        } finally {
            MenuKit.reset(c);
            clearInv(c);
        }
    }

    static void screenClick(Object screen, int slotIndex, ContainerInput input) {
        AbstractContainerScreen<?> s = (AbstractContainerScreen<?>) screen;
        Slot slot = s.getMenu().slots.get(slotIndex);
        Mod.call(s, "killer560smod$slotClicked", slot, slotIndex, 0, input);
    }

    static void invsort(Session c) throws Exception {
        MenuKit.reset(c);
        clearInv(c);
        try {
            if (!MenuKit.cheat()) {
                c.note("legit jar: the sorter is reachable only from /invsort load, which needs the cheat build");
                return;
            }
            c.hx().give(0, "minecraft:diamond");
            c.hx().give(1, "minecraft:emerald");
            c.waitUntil("diamond and emerald in slots 0/1", mc -> mc.player.getInventory().getItem(1).is(Items.EMERALD), 60);
            Object layout = c.onClient(mc -> {
                try {
                    return Mod.cls("invsort.InventoryLayout").getConstructor(String.class, Map.class)
                            .newInstance("hx-swap", Map.of(0, "EMERALD", 1, "DIAMOND"));
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            boolean started = c.onClient(mc -> (Boolean) Mod.staticCall("invsort.InventorySorterExecutor", "start", layout));
            c.check(started, "InventorySorterExecutor.start returned false");
            c.waitUntil("the sorter to finish", mc -> !(Boolean) Mod.staticCall("invsort.InventorySorterExecutor", "isRunning"), 400);
            c.ctx().waitTicks(10);
            boolean swapped = c.onClient(mc -> mc.player.getInventory().getItem(0).is(Items.EMERALD)
                    && mc.player.getInventory().getItem(1).is(Items.DIAMOND));
            List<String> clicks = new ArrayList<>();
            c.events("container.click").forEach(e -> clicks.add(e.get("containerId").getAsInt() + ":" + e.get("slot").getAsInt()
                    + ":" + e.get("input").getAsString()));
            c.check(swapped, "slots 0/1 not swapped; clicks " + clicks);
            c.check(!clicks.isEmpty() && clicks.stream().allMatch(x -> x.startsWith("0:")), "clicks " + clicks);
            c.note("sorter swapped diamond/emerald with " + clicks + "; " + MenuKit.cadence(c));
        } finally {
            MenuKit.reset(c);
            clearInv(c);
        }
    }

    static void slotBinds(Session c) throws Exception {
        MenuKit.reset(c);
        clearInv(c);
        Object cfgObj = c.onClient(mc -> Mod.cfg("slotbinds.SlotBindsConfig"));
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set("slotbinds.SlotBindsConfig", "Enabled", true);
            c.ctx().runOnClient(mc -> Mod.call(cfgObj, "addBind", 9, 36));
            c.hx().give(9, "minecraft:diamond");
            c.waitUntil("a diamond in inventory slot 9", mc -> mc.player.getInventory().getItem(9).is(Items.DIAMOND), 60);
            c.ctx().runOnClient(mc -> McCompat.setScreen(mc, new InventoryScreen(mc.player)));
            c.waitUntil("the inventory screen", mc -> McCompat.screen(mc) instanceof InventoryScreen, 40);
            double[] at = c.onClient(mc -> {
                AbstractContainerScreen<?> s = (AbstractContainerScreen<?>) McCompat.screen(mc);
                Slot slot = s.getMenu().slots.get(9);
                int left = (Integer) Mod.field(s, "leftPos");
                int top = (Integer) Mod.field(s, "topPos");
                double scale = mc.getWindow().getGuiScale();
                return new double[]{(left + slot.x + 8) * scale, (top + slot.y + 8) * scale};
            });
            c.ctx().getInput().setCursorPos(at[0], at[1]);
            c.ctx().waitTicks(3);
            // Fabric's TestInput.pressMouse always builds MouseButtonInfo(button, 0) - no modifiers, even with
            // holdShift() - so a shift-click goes in through MouseHandler.onButton with GLFW_MOD_SHIFT (1) directly.
            c.ctx().runOnClient(mc -> {
                long window = mc.getWindow().handle();
                Mod.call(mc.mouseHandler, "onButton", window, new net.minecraft.client.input.MouseButtonInfo(0, 1), 1);
                Mod.call(mc.mouseHandler, "onButton", window, new net.minecraft.client.input.MouseButtonInfo(0, 1), 0);
            });
            c.waitUntil("a click from the slot bind", mc -> !c.events("container.click").isEmpty(), 60);
            JsonObject click = c.events("container.click").get(0);
            c.check(click.get("input").getAsString().equals("SWAP") && click.get("slot").getAsInt() == 9
                    && click.get("button").getAsInt() == 0, "slot bind sent " + click);
            c.note("slot bind 9<->36: shift-click on slot 9 sent " + click.get("input") + " slot 9 button 0 (hotbar 0)");
        } finally {
            c.ctx().runOnClient(mc -> Mod.call(cfgObj, "removeBind", 9));
            MenuKit.reset(c);
            clearInv(c);
        }
    }

    static void storageOverlay(Session c) throws Exception {
        MenuKit.reset(c);
        try {
            MenuKit.show(c, MenuKit.menu("menus.ender-chest-1"));
            MenuKit.awaitScreen(c, "Ender Chest (1/9)", 100);
            String key = c.onClient(mc -> Mod.staticCall("storageoverlay.StorageOverlayFeature", "accountProfilePrefix") + "|enderchest_1");
            c.waitUntil("the storage cache to hold " + key, mc ->
                    (Boolean) Mod.call(Mod.staticCall("storageoverlay.StorageOverlayCache", "getInstance"), "hasContents", key), 100);
            String first = c.onClient(mc -> {
                List<?> page = (List<?>) Mod.call(Mod.staticCall("storageoverlay.StorageOverlayCache", "getInstance"), "get", key);
                ItemStack s = (ItemStack) page.get(0);
                return s.getItem() + "x" + s.getCount() + " of " + page.size();
            });
            c.check(first.startsWith("minecraft:diamondx5"), "cached page starts with " + first);
            c.note("Ender Chest (1/9) cached under " + key + ": " + first);
        } finally {
            MenuKit.reset(c);
        }
    }

    static void storageSearch(Session c) throws Exception {
        MenuKit.reset(c);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set("storagesearch.StorageSearchConfig", "Enabled", true);
            MenuKit.show(c, MenuKit.menu("menus.ender-chest-1"));
            MenuKit.awaitScreen(c, "Ender Chest (1/9)", 100);
            c.ctx().waitTicks(10);
            MenuKit.reset(c);
            int hits = c.onClient(mc -> ((List<?>) Mod.call(Mod.staticCall("storagesearch.StorageSearchIndex", "build", false),
                    "filter", "emerald", false, false)).size());
            c.check(hits >= 1, "the storage search index finds no 'emerald' after the ender chest page was seen");
            c.note("storage search: 'emerald' -> " + hits + " hit(s) from the ender chest page");
        } finally {
            MenuKit.reset(c);
        }
    }

    static void petWheel(Session c) throws Exception {
        MenuKit.reset(c);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set("petwheel.PetWheelConfig", "Enabled", true);
            MenuKit.show(c, MenuKit.menu("menus.pets"));
            MenuKit.awaitScreen(c, "Pets", 100);
            c.waitUntil("PetWheelConfig.getKnownPets() to hold hx-pet-1", mc -> ((List<?>) Mod.call(Mod.cfg("petwheel.PetWheelConfig"),
                    "getKnownPets")).stream().anyMatch(p -> "hx-pet-1".equals(Mod.call(p, "uuid"))), 100);
            Object pet = c.onClient(mc -> ((List<?>) Mod.call(Mod.cfg("petwheel.PetWheelConfig"), "getKnownPets")).stream()
                    .filter(p -> "hx-pet-1".equals(Mod.call(p, "uuid"))).findFirst().orElseThrow());
            c.check(((Number) Mod.call(pet, "level")).intValue() == 100 && "LEGENDARY".equals(Mod.call(pet, "tier")),
                    "pet read as " + pet);
            MenuKit.reset(c);
            c.hx().call("menu.onCommand", "name", "pets", "menu", MenuKit.menu("menus.pets"));
            long before = c.events("container.click").size();
            boolean asked = c.onClient(mc -> (Boolean) Mod.staticCall("petwheel.PetSummoner", "request", pet));
            c.check(asked, "PetSummoner.request refused");
            c.waitUntil("a click on the pet (slot 10)", mc -> c.events("container.click").stream()
                    .anyMatch(e -> e.get("slot").getAsInt() == 10), 200);
            c.check(c.commands().contains("pets"), "no /pets command: " + c.commands());
            JsonObject click = c.events("container.click").stream().filter(e -> e.get("slot").getAsInt() == 10).findFirst().orElseThrow();
            c.note("pets menu scanned " + pet + "; summon sent /pets then " + click.get("input") + " slot 10 (" + before
                    + " earlier clicks)");
        } finally {
            c.hx().call("menu.onCommand", "name", "pets", "menu", null);
            MenuKit.reset(c);
        }
    }

    static void loadout(Session c) throws Exception {
        MenuKit.reset(c);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set("loadoutkeybinds.LoadoutKeybindsConfig", "Enabled", true);
            MenuKit.show(c, MenuKit.menu("menus.loadout"));
            MenuKit.awaitScreen(c, "(1/2) Loadout", 100);
            c.ctx().waitTicks(5);
            c.ctx().getInput().pressKey(49);   // GLFW_KEY_1
            c.waitUntil("key 1 -> a click", mc -> !c.events("container.click").isEmpty(), 60);
            c.ctx().getInput().pressKey(262);  // GLFW_KEY_RIGHT
            c.waitUntil("Right -> a second click", mc -> c.events("container.click").size() >= 2, 60);
            List<JsonObject> clicks = c.events("container.click");
            c.check(clicks.get(0).get("slot").getAsInt() == 14 && clicks.get(0).get("input").getAsString().equals("PICKUP"),
                    "key 1 sent " + clicks.get(0));
            c.check(clicks.get(1).get("slot").getAsInt() == 44, "Right sent " + clicks.get(1));
            c.note("loadout keys: 1 -> slot 14 PICKUP, Right -> slot 44");
        } finally {
            MenuKit.reset(c);
        }
    }

    /**
     * The helper's panel and its copy button need AuctionHouseApi listings (network: offline in tests, hook H1), so
     * copyButtonRect stays null here. What is testable offline: it finds the listed item in the menu and has no match.
     * The clipboard button is never pressed.
     */
    static void listingHelper(Session c) throws Exception {
        MenuKit.reset(c);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set("auction.AuctionConfig", "ListingHelperEnabled", true);
            MenuKit.show(c, MenuKit.menu("menus.create-bin-auction"));
            MenuKit.awaitScreen(c, "Create BIN Auction", 100);
            c.ctx().waitTicks(5);
            String listed = c.onClient(mc -> {
                ItemStack st = (ItemStack) Mod.staticCall("auction.ListingHelperFeature", "findListedItem", McCompat.screen(mc));
                return st == null ? "null" : String.valueOf(Mod.staticCall("cheatutils.CheatUtils", "skyblockId", st));
            });
            Object match = c.onClient(mc -> Mod.staticCall("auction.ListingHelperFeature", "bestMatch",
                    Mod.staticCall("auction.ListingHelperFeature", "findListedItem", McCompat.screen(mc))));
            c.check("HYPERION".equals(listed), "the helper picked the listed item " + listed + ", slot 13 holds HYPERION");
            c.check(match == null, "a BIN match offline? " + match);
            c.check(c.events("container.click").isEmpty(), "the helper clicked the auction menu");
            c.note("listing helper on 'Create BIN Auction': listed item " + listed + ", no match offline (listings need H1); "
                    + "copy button not drawn and never pressed");
        } finally {
            MenuKit.reset(c);
        }
    }

    static void bazaarDump(Session c) throws Exception {
        MenuKit.reset(c);
        try {
            MenuKit.show(c, MenuKit.menu("menus.bazaar-product"));
            MenuKit.awaitScreen(c, "Hx Bazaar Product", 100);
            c.ctx().waitTicks(3);
            String dump = c.onClient(mc -> (String) Mod.staticCall("bazaarflip.BazaarFlipMenus", "describe", McCompat.screen(mc)));
            int buy = c.onClient(mc -> (Integer) Mod.staticCall("bazaarflip.BazaarFlipMenus", "findMenuSlot",
                    ((AbstractContainerScreen<?>) McCompat.screen(mc)).getMenu(), new String[]{"instant buy", "buy instantly"}));
            c.check(buy == 10, "findMenuSlot(instant buy) = " + buy);
            c.check(dump.contains("10=Buy Instantly") && dump.contains("13=Enchanted Diamond x2"), "dump " + dump);
            c.note("bazaar menu dump: " + dump);
        } finally {
            MenuKit.reset(c);
        }
    }

    static void chocolate(Session c) throws Exception {
        MenuKit.reset(c);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set("cheatutils.CheatUtilsConfig", "ChocolateEnabled", true).set("cheatutils.CheatUtilsConfig", "CfClickCookie", true);
            boolean on = c.onClient(mc -> (Boolean) Mod.get("cheatutils.CheatUtilsConfig", "isChocolateEnabled"));
            MenuKit.show(c, MenuKit.menu("menus.chocolate-factory"));
            MenuKit.awaitScreen(c, "Chocolate Factory", 100);
            c.ctx().waitTicks(80);
            List<JsonObject> clicks = c.events("container.click");
            if (!MenuKit.cheat()) {
                c.check(!on && clicks.isEmpty(), "legit jar: enabled=" + on + ", " + clicks.size() + " click(s)");
                c.note("legit jar: Chocolate Factory getter false, 0 clicks in 80 ticks");
                return;
            }
            c.check(clicks.size() >= 3, "only " + clicks.size() + " cookie click(s) in 80 ticks");
            c.check(clicks.stream().allMatch(e -> e.get("slot").getAsInt() == 13 && e.get("button").getAsInt() == 1
                    && e.get("input").getAsString().equals("PICKUP")), "clicks " + clicks);
            c.check(MenuKit.maxPerTick(MenuKit.clicksPerTick(c)) <= 1, "more than one click per tick");
            c.note("Chocolate Factory: cookie clicks slot 13 button 1 PICKUP; " + MenuKit.cadence(c));
        } finally {
            MenuKit.reset(c);
        }
    }
}
