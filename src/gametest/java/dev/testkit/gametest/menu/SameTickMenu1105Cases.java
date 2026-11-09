package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;

import dev.testkit.gametest.SameTick;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Map;

/**
 * SAME-TICK GRIM CASES for the container automations (grimgapC-1105, the "container clicks" gap of grimsame-1105). HIS
 * mouse click goes into every client tick on the open menu's last slot (his empty hotbar slot 9) through the screen, as
 * a real press ({@link SameTick.Act#SCREEN_CLICK}), while the feature clicks. Cheat jar only. Each case proves the
 * server took the feature's clicks, and GrimAC (default and experimental) must stay silent.
 * <ul>
 *   <li>1105-same-autosell-click: Auto Sell sells six listed stacks; his click lands in its server ticks.</li>
 *   <li>1105-same-autoterm-click: Auto Terminals on Numbers with Block Input While Auto Clicking at its default (ON):
 *       his click is swallowed, none reaches the wire, the terminal is solved with no wrong auto click.</li>
 *   <li>1105-same-autoterm-click-unblocked: the same with Block Input OFF: his clicks share the auto clicks' server ticks
 *       and the auto clicks are still all right.</li>
 *   <li>1105-same-invsort-click: the Inventory Sorter reorders four stacks in its own inventory screen, which holds his
 *       clicks: none reaches the wire, the layout is reached.</li>
 * </ul>
 */
final class SameTickMenu1105Cases {

    private static final String SELL = "autosell.AutoSellConfig";

    private SameTickMenu1105Cases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1105-same-autosell-click", SameTickMenu1105Cases::autoSell);
        MenuSuite.test(s, "1105-same-autoterm-click", c -> autoTerm(c, true));
        MenuSuite.test(s, "1105-same-autoterm-click-unblocked", c -> autoTerm(c, false));
        MenuSuite.test(s, "1105-same-invsort-click", SameTickMenu1105Cases::invSort);
    }

    private static SameTick start(Session c) {
        return c.onClient(mc -> SameTick.start(SameTick.Act.SCREEN_CLICK, 1, p -> p instanceof ServerboundContainerClickPacket, 4, 5));
    }

    private static void stop(Session c, SameTick st) {
        if (st != null) {
            c.onClient(mc -> {
                st.close();
                return null;
            });
        }
    }

    // ---- Auto Sell ------------------------------------------------------------------------------------------------

    private static void autoSell(Session c) throws Exception {
        MenuKit.reset(c);
        InventoryCases.clearInv(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: Auto Sell does not exist");
            return;
        }
        Object cfgObj = c.onClient(mc -> Mod.cfg(SELL));
        SameTick st = null;
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(SELL, "Enabled", true);
            c.ctx().runOnClient(mc -> Mod.call(cfgObj, "addSellIdentity", "ENCHANTED_DIAMOND"));
            c.ctx().runOnClient(mc -> Mod.call(cfgObj, "setScreenTitlePattern", "(?i).*sell.*"));
            for (int i = 0; i < 6; i++) {
                c.hx().call("give", "slot", i, "stack", MenuKit.item("items.enchanted-diamond"), "count", 16);
            }
            st = start(c);
            MenuKit.show(c, MenuKit.menu("menus.sell"));
            MenuKit.awaitScreen(c, "Sell", 100);
            c.waitUntil("six stacks sold", mc -> c.events("sell.sold").size() >= 6, 400);
            c.waitUntil("Auto Sell to finish", mc -> !(Boolean) Mod.staticCall("autosell.AutoSellFeature", "isRunning"), 200);
            c.note("sold " + c.events("sell.sold").size() + ", server clicks " + c.events("container.click").size()
                    + "; " + MenuKit.cadence(c));
            SameTick.judge(c, st, "auto sell", SameTick.Expect.SHARE);
            st = null;
        } finally {
            stop(c, st);
            c.ctx().runOnClient(mc -> Mod.call(cfgObj, "removeSellIdentity", "ENCHANTED_DIAMOND"));
            MenuKit.reset(c);
            InventoryCases.clearInv(c);
        }
    }

    // ---- Auto Terminals ---------------------------------------------------------------------------------------------

    private static void autoTerm(Session c, boolean block) throws Exception {
        MenuKit.reset(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: Auto Terminals does not exist");
            return;
        }
        TerminalCases.Spec spec = TerminalCases.SPECS.get(2); // Numbers
        String cfgName = TerminalCases.CONFIG;
        SameTick st = null;
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(cfgName, "Enabled", true).set(cfgName, "AutoTerminalsEnabled", true).set(cfgName, spec.auto(), true)
                    .set(cfgName, "BlockInputWhileAutoClicking", block);
            JsonObject t = TerminalCases.open(c, spec);
            MenuKit.awaitScreen(c, t.get("title").getAsString(), 100);
            // From the screen's first drawn frame on: a real click reaches a screen only after it is drawn, and the solver
            // reads the terminal at the head of that draw (the harness's END click could come before it: awaitScreen can
            // return in the tick the screen opened, so one more tick lets a frame draw).
            c.ctx().waitTicks(1);
            st = start(c);
            int last = c.onClient(mc -> ((net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>)
                    dev.testkit.compat.McCompat.screen(mc)).getMenu().slots.size() - 1);
            JsonObject solved = MenuKit.awaitEvent(c, "terminal.solved", 500);
            MenuKit.awaitNoScreen(c, 40);
            int autoWrong = 0;
            int his = 0;
            for (JsonObject e : c.events("terminal.click")) {
                if (e.get("slot").getAsInt() == last) {
                    his++;
                } else if (!e.get("correct").getAsBoolean()) {
                    autoWrong++;
                }
            }
            c.note("solved " + solved + "; his clicks the server saw " + his + ", wrong auto clicks " + autoWrong);
            c.check(autoWrong == 0, autoWrong + " wrong auto click(s)");
            SameTick.judge(c, st, "auto terminals (block input " + block + ")", block ? SameTick.Expect.HELD : SameTick.Expect.SHARE);
            st = null;
        } finally {
            stop(c, st);
            MenuKit.reset(c);
        }
    }

    // ---- Inventory Sorter -------------------------------------------------------------------------------------------

    private static void invSort(Session c) throws Exception {
        MenuKit.reset(c);
        InventoryCases.clearInv(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: the sorter's start needs the cheat build");
            return;
        }
        SameTick st = null;
        try {
            c.hx().give(0, "minecraft:diamond");
            c.hx().give(1, "minecraft:emerald");
            c.hx().give(2, "minecraft:gold_ingot");
            c.hx().give(3, "minecraft:iron_ingot");
            c.waitUntil("four stacks in slots 0-3", mc -> mc.player.getInventory().getItem(3).is(Items.IRON_INGOT), 60);
            Object layout = c.onClient(mc -> {
                try {
                    return Mod.cls("invsort.InventoryLayout").getConstructor(String.class, Map.class)
                            .newInstance("hx-same", Map.of(0, "IRON_INGOT", 1, "GOLD_INGOT", 2, "EMERALD", 3, "DIAMOND"));
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            st = start(c);
            boolean started = c.onClient(mc -> (Boolean) Mod.staticCall("invsort.InventorySorterExecutor", "start", layout));
            c.check(started, "InventorySorterExecutor.start returned false");
            c.waitUntil("the sorter to finish", mc -> !(Boolean) Mod.staticCall("invsort.InventorySorterExecutor", "isRunning"), 400);
            c.ctx().waitTicks(10);
            boolean sorted = c.onClient(mc -> mc.player.getInventory().getItem(0).is(Items.IRON_INGOT)
                    && mc.player.getInventory().getItem(1).is(Items.GOLD_INGOT)
                    && mc.player.getInventory().getItem(2).is(Items.EMERALD)
                    && mc.player.getInventory().getItem(3).is(Items.DIAMOND));
            List<JsonObject> clicks = c.events("container.click");
            c.note("sorter: server clicks " + clicks.size() + ", sorted " + sorted + "; " + MenuKit.cadence(c));
            c.check(sorted, "the layout was not reached");
            SameTick.judge(c, st, "inventory sorter", SameTick.Expect.HELD);
            st = null;
        } finally {
            stop(c, st);
            MenuKit.reset(c);
            InventoryCases.clearInv(c);
        }
    }
}
