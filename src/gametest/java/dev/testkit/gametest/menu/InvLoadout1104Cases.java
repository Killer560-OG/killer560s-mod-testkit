package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;
import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.Items;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Inventory Sorter layouts select a Hypixel loadout (killer560, 2026-11-04: "allow invsort to select a loadout"):
 * <ul>
 *   <li>1104-invsort-loadout-save: the layout's loadout is picked in the menu's Loadout picker (a click on slot 1-12 or None), saved in the layout file, survives a reload from disk and a rename.</li>
 *   <li>1104-invsort-loadout-apply: applying a layout with loadout 5 sends /loadouts, clicks the faked "(1/2) Loadout"
 *       menu's slot 24 (loadout 5), closes it, and only then sorts (control: the same layout with no loadout sends no
 *       /loadouts and no menu click).</li>
 * </ul>
 */
final class InvLoadout1104Cases {

    private static final String EXEC = "invsort.InventorySorterExecutor";
    private static final String STORE = "invsort.InventoryLayoutStore";
    private static final String SCREEN = "invsort.InventorySorterScreen";
    private static final String ISC = "invsort.InventorySorterConfig";

    private InvLoadout1104Cases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1104-invsort-loadout-save", InvLoadout1104Cases::save);
        MenuSuite.test(s, "1104-invsort-loadout-apply", InvLoadout1104Cases::apply);
    }

    private static Object layout(Session c, String name, Map<Integer, String> slots, int loadout) {
        return c.onClient(mc -> {
            try {
                return Mod.cls("invsort.InventoryLayout").getConstructor(String.class, Map.class, Map.class, Map.class,
                        Set.class, boolean.class, String.class, int.class)
                        .newInstance(name, slots, Map.of(), Map.of(), Set.of(), false, null, loadout);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
    }

    private static Object store() {
        return Mod.staticCall(STORE, "getInstance");
    }

    private static int loadoutOf(Session c, String name) {
        return c.onClient(mc -> {
            Object l = Mod.call(store(), "get", name);
            return l == null ? -1 : (Integer) Mod.call(l, "loadout");
        });
    }

    /** Opens the picker from the editor's Loadout button and really clicks choice n (1-12, 0 = None) in it. */
    private static void pick(Session c, int n) {
        c.ctx().runOnClient(mc -> Mod.call(McCompat.screen(mc), "openLoadoutPicker"));
        c.waitUntil("the loadout picker", mc -> McCompat.screen(mc) != null
                && McCompat.screen(mc).getClass().getSimpleName().equals("InvSortLoadoutPick"), 40);
        c.ctx().runOnClient(mc -> {
            Object scr = McCompat.screen(mc);
            int[] b = (int[]) Mod.call(scr, "themedPanelBounds");
            // 6 columns of 28, 12 padding, header 22 + 4; the None button sits under the two rows of slots.
            double x = n == 0 ? b[0] + b[2] / 2.0 : b[0] + 12 + ((n - 1) % 6) * 28 + 12;
            double y = n == 0 ? b[1] + 22 + 4 + 12 + 56 + 4 + 9 : b[1] + 22 + 4 + 12 + ((n - 1) / 6) * 28 + 12;
            ((Screen) scr).mouseClicked(new net.minecraft.client.input.MouseButtonEvent(x, y,
                    new net.minecraft.client.input.MouseButtonInfo(dev.testkit.compat.McKeys.mouse(0), 0)), false);
        });
        c.waitUntil("back in the layouts menu", mc -> McCompat.screen(mc) != null
                && McCompat.screen(mc).getClass().getSimpleName().equals("InventorySorterScreen"), 40);
    }

    private static boolean legit(Session c) {
        if (!MenuKit.cheat()) {
            c.note("legit jar: the sorter is cheat-only");
            return true;
        }
        return false;
    }

    static void save(Session c) throws Exception {
        MenuKit.reset(c);
        InventoryCases.clearInv(c);
        if (legit(c)) {
            return;
        }
        String name = "hx-1104-lo";
        String renamed = "hx-1104-lo2";
        try {
            c.ctx().runOnClient(mc -> {
                Mod.call(store(), "delete", name);
                Mod.call(store(), "delete", renamed);
                Mod.call(store(), "save", layout(c, name, Map.of(0, "RUNEBOOK"), 0));
            });
            c.check(loadoutOf(c, name) == 0, "a new layout should have no loadout, got " + loadoutOf(c, name));
            c.ctx().runOnClient(mc -> {
                try {
                    McCompat.setScreen(mc, (Screen) Mod.cls(SCREEN).getConstructor(Screen.class).newInstance((Object) null));
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            c.waitUntil("the layouts menu", mc -> McCompat.screen(mc) != null
                    && McCompat.screen(mc).getClass().getSimpleName().equals("InventorySorterScreen"), 40);
            c.ctx().runOnClient(mc -> Mod.call(McCompat.screen(mc), "select", name));
            c.ctx().waitTicks(3);
            pick(c, 5);
            c.check(loadoutOf(c, name) == 5, "a click on slot 5 should give loadout 5, got " + loadoutOf(c, name));
            String five = c.onClient(mc -> {
                try {
                    return Files.readString(((Path) Mod.staticCall(STORE, "directory")).resolve(name + ".json"));
                } catch (Exception e) {
                    return "ERR " + e;
                }
            });
            c.check(five.contains("\"loadout\": 5") || five.contains("\"loadout\":5"),
                    "the layout file does not hold loadout 5: " + five);
            pick(c, 12);
            c.check(loadoutOf(c, name) == 12, "a click on slot 12 should give loadout 12, got " + loadoutOf(c, name));
            pick(c, 0);
            c.check(loadoutOf(c, name) == 0, "a click on None should clear it, got " + loadoutOf(c, name));
            pick(c, 7);
            c.check(loadoutOf(c, name) == 7, "expected 7, got " + loadoutOf(c, name));
            c.ctx().runOnClient(mc -> Mod.call(McCompat.screen(mc), "onClose"));

            // The disk: the layout file says it, and a reload from disk brings it back.
            String json = c.onClient(mc -> {
                try {
                    return Files.readString(((Path) Mod.staticCall(STORE, "directory")).resolve(name + ".json"));
                } catch (Exception e) {
                    return "ERR " + e;
                }
            });
            c.check(json.contains("\"loadout\": 7") || json.contains("\"loadout\":7"),
                    "the layout file does not hold loadout 7: " + json);
            c.ctx().runOnClient(mc -> Mod.staticCall(STORE, "reload"));
            c.check(loadoutOf(c, name) == 7, "after a reload from disk the loadout is " + loadoutOf(c, name));
            c.ctx().runOnClient(mc -> Mod.call(store(), "rename", name, renamed));
            c.check(loadoutOf(c, renamed) == 7, "after a rename the loadout is " + loadoutOf(c, renamed));
            c.note("loadout picked 5,12,None,7 in the picker, written to the layout file, kept over reload and rename");
        } finally {
            c.ctx().runOnClient(mc -> {
                Mod.call(store(), "delete", name);
                Mod.call(store(), "delete", renamed);
            });
            MenuKit.reset(c);
        }
    }

    static void apply(Session c) throws Exception {
        MenuKit.reset(c);
        InventoryCases.clearInv(c);
        if (legit(c)) {
            return;
        }
        String name = "hx-1104-apply";
        String plain = "hx-1104-plain";
        Path cfgPath = c.onClient(mc -> (Path) Mod.field(ISC, "CONFIG_PATH"));
        byte[] old = null;
        try {
            if (Files.exists(cfgPath)) {
                old = Files.readAllBytes(cfgPath);
            }
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        try {
            c.ctx().runOnClient(mc -> {
                Object cfg = Mod.cfg(ISC);
                Mod.call(cfg, "setTicksBetweenMoves", 2);
                Mod.call(cfg, "setRandomExtraTicks", 0);
                Mod.call(cfg, "setInstant", false);
            });
            c.hx().give(3, "minecraft:diamond");
            c.waitUntil("the diamond", mc -> mc.player.getInventory().getItem(3).is(Items.DIAMOND), 80);
            String diamond = c.onClient(mc -> (String) Mod.staticCall("autoroutes.ItemIdentity", "of",
                    mc.player.getInventory().getItem(3)));
            Map<Integer, String> want = Map.of(0, diamond);

            // Control: no loadout, so no /loadouts and no menu click, and the diamond still moves.
            int before = c.events("container.click").size();
            int cmdsBefore = c.commands().size();
            Object plainLayout = layout(c, plain, want, 0);
            c.check(c.onClient(mc -> (Boolean) Mod.staticCall(EXEC, "start", plainLayout)), "control start false");
            c.waitUntil("the control sort", mc -> !(Boolean) Mod.staticCall(EXEC, "isRunning"), 300);
            c.ctx().waitTicks(10);
            c.check(c.onClient(mc -> mc.player.getInventory().getItem(0).is(Items.DIAMOND)),
                    "control: the diamond did not reach slot 0");
            c.check(!c.commands().subList(cmdsBefore, c.commands().size()).contains("loadouts"),
                    "control: a layout without a loadout sent /loadouts");
            List<JsonObject> controlClicks = c.events("container.click").subList(before, c.events("container.click").size());
            c.check(!controlClicks.isEmpty(), "control: the sort sent no click, so it proves nothing");

            // Back to the diamond in slot 3, then the same with loadout 5.
            InventoryCases.clearInv(c);
            c.hx().give(3, "minecraft:diamond");
            c.waitUntil("the diamond again", mc -> mc.player.getInventory().getItem(3).is(Items.DIAMOND), 80);
            c.hx().call("menu.onCommand", "name", "loadouts", "menu", MenuKit.menu("menus.loadout"));
            before = c.events("container.click").size();
            int closesBefore = c.events("container.close").size();
            cmdsBefore = c.commands().size();
            Object loLayout = layout(c, name, want, 5);
            c.check(c.onClient(mc -> (Boolean) Mod.staticCall(EXEC, "start", loLayout)), "start returned false");
            c.waitUntil("the loadout then the sort", mc -> !(Boolean) Mod.staticCall(EXEC, "isRunning"), 600);
            c.ctx().waitTicks(10);
            List<String> cmds = c.commands().subList(cmdsBefore, c.commands().size());
            c.check(cmds.stream().filter("loadouts"::equals).count() == 1, "want exactly one /loadouts, server saw " + cmds);
            List<JsonObject> clicks = c.events("container.click").subList(before, c.events("container.click").size());
            c.check(clicks.size() >= 2, "want the loadout click then sort clicks, got " + clicks);
            JsonObject first = clicks.get(0);
            c.check(first.get("slot").getAsInt() == 24 && first.get("input").getAsString().equals("PICKUP"),
                    "the first click should be PICKUP on menu slot 24 (loadout 5), got " + first);
            c.check(c.events("container.close").size() > closesBefore, "the loadout menu was not closed");
            JsonObject last = clicks.get(clicks.size() - 1);
            c.check(last.get("tick").getAsLong() > first.get("tick").getAsLong(),
                    "the sort did not come after the loadout click: " + clicks);
            c.check(c.onClient(mc -> mc.player.getInventory().getItem(0).is(Items.DIAMOND)),
                    "the diamond did not reach slot 0 after the loadout");
            c.note("loadout 5: /loadouts x1, first click PICKUP menu slot 24 at tick " + first.get("tick") + ", menu closed, "
                    + (clicks.size() - 1) + " sort click(s) after it, diamond in slot 0; control sent no /loadouts");
        } finally {
            if (c.onClient(mc -> (Boolean) Mod.staticCall(EXEC, "isRunning"))) {
                c.ctx().runOnClient(mc -> Mod.staticCall(EXEC, "requestStop"));
            }
            c.hx().call("menu.onCommand", "name", "loadouts", "menu", null);
            MenuKit.reset(c);
            InventoryCases.clearInv(c);
            try {
                if (old == null) {
                    Files.deleteIfExists(cfgPath);
                } else {
                    Files.write(cfgPath, old);
                }
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
            c.ctx().runOnClient(mc -> Mod.staticCall(ISC, "load"));
        }
    }
}
