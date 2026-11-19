package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.gametest.ui.AutoReclaim1117UiCases;

import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * autoreclaim-btn-1118 (menu session, `200-menu-session:,1118-menu-reclaim-btn`): the two buttons in the Reclaim Menu.
 * <ul>
 *   <li>-inv: "Reclaim Inventory" reclaims what the inventory holds when it is pressed and walks no page: a book that
 *       only the Ender Chest cache knows is left alone (no /enderchest, never put in the menu).</li>
 *   <li>-all: "Reclaim All" closes the menu, pulls every page that holds a match, and reclaims the inventory item and the
 *       pulled book (two claims), one click a tick, then stops.</li>
 *   <li>-close: closing the menu mid-run stops the run and sends no more clicks.</li>
 *   <li>-absent: no button on another container, with Pull And Reclaim off, or with the feature off.</li>
 * </ul>
 */
final class AutoReclaimBtn1118Cases {

    private static final String CFG = AutoReclaim1117UiCases.CFG;
    private static final String MENU = "autoreclaim.ReclaimMenu";
    private static final String BTN = "autoreclaim.ReclaimButtons";
    private static final String ACC = "minecraft:golden_apple[custom_data={id:\"R_ACC\",rarity_upgrades:1},"
            + "lore=[{text:\"VERY SPECIAL ACCESSORY\",italic:false}]]";
    private static final String BOOK = "minecraft:enchanted_book[custom_data={id:\"R_BOOK\",enchantments:{chance:3}},"
            + "lore=[{text:\"EPIC\",italic:false}]]";

    private AutoReclaimBtn1118Cases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1118-menu-reclaim-btn-inv", c -> run(c, 0));
        MenuSuite.test(s, "1118-menu-reclaim-btn-all", c -> run(c, 1));
        MenuSuite.test(s, "1118-menu-reclaim-btn-close", AutoReclaimBtn1118Cases::closeMid);
        MenuSuite.test(s, "1118-menu-reclaim-btn-absent", AutoReclaimBtn1118Cases::absent);
    }

    private static JsonObject reclaimMenu() {
        return MenuKit.obj("{\"title\":\"Reclaim Menu\",\"rows\":6,\"reclaim\":true,"
                + "\"accept\":[\"R_ACC\",\"R_BOOK\"],\"slots\":{\"49\":{\"item\":\"minecraft:barrier\",\"name\":\"Close\"},"
                + "\"50\":{\"item\":\"minecraft:redstone_torch\",\"name\":\"Recently added reclaims\"}}}");
    }

    private static int count(Session c, String type, String key, String value) {
        int n = 0;
        for (JsonObject e : c.events(type)) {
            if (key == null || value.equals(e.get(key).getAsString())) {
                n++;
            }
        }
        return n;
    }

    /** The real mouse press the screen's own listeners see, at the middle of button {@code n}; returns whether it was let through. */
    private static boolean press(Session c, int n) {
        return c.onClient(mc -> {
            int[] b = (int[]) Mod.staticCall(BTN, "boxForTest", n);
            if (b == null) {
                throw new AssertionError("button " + n + " is not shown");
            }
            var s = dev.testkit.compat.McCompat.screen(mc);
            var ev = new MouseButtonEvent(b[0] + b[2] / 2.0, b[1] + b[3] / 2.0,
                    new MouseButtonInfo(dev.testkit.compat.McKeys.mouse(0), 0));
            return ScreenMouseEvents.allowMouseClick(s).invoker().allowMouseClick(s, ev);
        });
    }

    private static boolean running(Session c) {
        return c.onClient(mc -> (Boolean) Mod.staticCall(MENU, "isRunning"));
    }

    /** Gives the inventory item, puts the book on Ender Chest page 1 in the cache, opens the Reclaim Menu. */
    private static void setup(Session c, MenuKit.Cfg cfg, String key, Object cache) throws Exception {
        cfg.set(CFG, "Enabled", true).set(CFG, "PullAndReclaim", true);
        c.hx().give(0, ACC);
        c.hx().give(2, "minecraft:stone_sword[custom_data={id:\"R_PLAIN\"}]");
        c.waitUntil("the stacks", mc -> mc.player.getInventory().getItem(0).is(Items.GOLDEN_APPLE)
                && mc.player.getInventory().getItem(2).is(Items.STONE_SWORD), 60);
        c.ctx().runOnClient(mc -> {
            List<ItemStack> page = new ArrayList<>();
            for (int i = 0; i < 45; i++) {
                page.add(i == 3 ? AutoReclaim1117UiCases.stack(Items.ENCHANTED_BOOK, "R_BOOK", 0, "EPIC", "chance") : ItemStack.EMPTY);
            }
            Mod.call(cache, "put", key, page);
        });
        c.hx().stub(null, "enderchest", "backpack", "accessories");
        c.hx().call("menu.onCommand", "name", "reclaim", "menu", reclaimMenu());
        c.ctx().runOnClient(mc -> mc.player.connection.sendCommand("reclaim"));
        MenuKit.awaitScreen(c, "Reclaim Menu", 80);
        c.ctx().waitTicks(3); // the buttons draw from the first frame; the HUD element resolves its spot
    }

    private static void finish(Session c, Object cache, String key) {
        c.hx().call("menu.onCommand", "name", "reclaim", "menu", null);
        c.ctx().runOnClient(mc -> Mod.call(cache, "put", key, new ArrayList<ItemStack>()));
        MenuKit.reset(c);
        InventoryCases.clearInv(c);
    }

    private static void run(Session c, int button) throws Exception {
        MenuKit.reset(c);
        InventoryCases.clearInv(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: the Reclaim Menu buttons do not exist");
            return;
        }
        String key = c.onClient(mc -> (String) Mod.staticCall("storageoverlay.StorageOverlayFeature", "storageKeyForTitle",
                "Ender Chest (1/9)"));
        Object cache = c.onClient(mc -> Mod.staticCall("storageoverlay.StorageOverlayCache", "getInstance"));
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            setup(c, cfg, key, cache);
            long since = c.hx().head();
            boolean eaten = !press(c, button);
            c.check(eaten, "the click on the button was not consumed");
            boolean opened = false;
            boolean ended = false;
            for (int i = 0; i < 1200 && !ended; i++) {
                c.ctx().waitTicks(1);
                if (!opened && c.hx().commands(since).stream().anyMatch(cmd -> cmd.startsWith("enderchest 1"))) {
                    c.hx().menu("Ender Chest (1/9)", 6, Map.of(0, "minecraft:black_stained_glass_pane", 12, BOOK), "storage");
                    opened = true;
                }
                ended = i > 5 && !running(c);
            }
            c.ctx().waitTicks(5);
            List<String> cmds = c.hx().commands(since);
            int puts = count(c, "reclaim.put", null, null);
            int claims = count(c, "reclaim.claim", null, null);
            int acc = count(c, "reclaim.put", "skyblockId", "R_ACC");
            int book = count(c, "reclaim.put", "skyblockId", "R_BOOK");
            c.note((button == 0 ? "Reclaim Inventory" : "Reclaim All") + ": commands " + cmds + "; puts " + puts + " (acc "
                    + acc + ", book " + book + "), claims " + claims + "; " + MenuKit.cadence(c));
            c.check(ended, "the run never ended");
            c.check(acc >= 1 && count(c, "reclaim.put", "skyblockId", "R_PLAIN") == 0, "the inventory item was not reclaimed, or a plain item was put");
            c.check(MenuKit.maxPerTick(MenuKit.clicksPerTick(c)) <= 1, "more than one automated click in a tick: " + MenuKit.cadence(c));
            if (button == 0) {
                c.check(!opened && cmds.stream().noneMatch(cmd -> cmd.startsWith("enderchest")),
                        "Reclaim Inventory walked a storage page: " + cmds);
                c.check(book == 0, "the book that is only on a storage page was put in the Reclaim Menu");
                c.check(claims == 1, "the server saw " + claims + " claims, want 1 (the inventory item)");
            } else {
                c.check(opened, "Reclaim All never opened /enderchest 1: " + cmds);
                c.check(book >= 1 && claims == 2, "want the pulled book and the inventory item claimed; book " + book + ", claims " + claims);
            }
        } finally {
            finish(c, cache, key);
        }
    }

    private static void closeMid(Session c) throws Exception {
        MenuKit.reset(c);
        InventoryCases.clearInv(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: the Reclaim Menu buttons do not exist");
            return;
        }
        String key = c.onClient(mc -> (String) Mod.staticCall("storageoverlay.StorageOverlayFeature", "storageKeyForTitle",
                "Ender Chest (1/9)"));
        Object cache = c.onClient(mc -> Mod.staticCall("storageoverlay.StorageOverlayCache", "getInstance"));
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            setup(c, cfg, key, cache);
            c.check(!press(c, 0), "the click on the button was not consumed");
            c.check(running(c), "the run did not start");
            MenuKit.closeClient(c);
            c.hx().call("menu.close");
            c.waitUntil("the run to stop after the menu closed", mc -> !(Boolean) Mod.staticCall(MENU, "isRunning"), 200);
            long clicks = c.events("container.click").size();
            c.ctx().waitTicks(20);
            c.check(!running(c) && c.events("container.click").size() == clicks, "clicks kept going after the menu closed");
            c.note("closed mid-run: stopped, " + clicks + " clicks, none after");
        } finally {
            finish(c, cache, key);
        }
    }

    private static void absent(Session c) throws Exception {
        MenuKit.reset(c);
        InventoryCases.clearInv(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: the Reclaim Menu buttons do not exist");
            return;
        }
        String key = c.onClient(mc -> (String) Mod.staticCall("storageoverlay.StorageOverlayFeature", "storageKeyForTitle",
                "Ender Chest (1/9)"));
        Object cache = c.onClient(mc -> Mod.staticCall("storageoverlay.StorageOverlayCache", "getInstance"));
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            setup(c, cfg, key, cache);
            int[] shown = c.onClient(mc -> (int[]) Mod.staticCall(BTN, "boxForTest", 0));
            c.check(shown != null, "button 1 is not shown with the feature on");
            // Pull And Reclaim off: no buttons.
            cfg.set(CFG, "PullAndReclaim", false);
            c.check(c.onClient(mc -> Mod.staticCall(BTN, "boxForTest", 0)) == null
                    && c.onClient(mc -> Mod.staticCall(BTN, "boxForTest", 1)) == null, "a button shows with Pull And Reclaim off");
            // Feature off: no buttons.
            cfg.set(CFG, "PullAndReclaim", true).set(CFG, "Enabled", false);
            c.check(c.onClient(mc -> Mod.staticCall(BTN, "boxForTest", 0)) == null, "a button shows with Auto Reclaim off");
            cfg.set(CFG, "Enabled", true);
            // Another container: a click at the button spot goes through and starts nothing.
            MenuKit.reset(c);
            c.hx().call("menu.onCommand", "name", "other", "menu", MenuKit.obj("{\"title\":\"Some Chest\",\"rows\":3}"));
            c.ctx().runOnClient(mc -> mc.player.connection.sendCommand("other"));
            MenuKit.awaitScreen(c, "Some Chest", 80);
            boolean allowed = c.onClient(mc -> {
                var s = dev.testkit.compat.McCompat.screen(mc);
                var ev = new MouseButtonEvent(shown[0] + shown[2] / 2.0, shown[1] + shown[3] / 2.0,
                        new MouseButtonInfo(dev.testkit.compat.McKeys.mouse(0), 0));
                return ScreenMouseEvents.allowMouseClick(s).invoker().allowMouseClick(s, ev);
            });
            c.ctx().waitTicks(5);
            c.check(allowed && !running(c), "a click at the button spot on another container was eaten or started a run");
            c.note("buttons shown on the Reclaim Menu, hidden with the switches off, no effect on another container");
        } finally {
            c.hx().call("menu.onCommand", "name", "other", "menu", null);
            finish(c, cache, key);
        }
    }
}
