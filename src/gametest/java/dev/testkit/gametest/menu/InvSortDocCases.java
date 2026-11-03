package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;
import dev.testkit.compat.McCompat;
import dev.testkit.compat.McKeys;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.gametest.LogTap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Inventory Sorter, the to-do Doc's "INVENTORY SORTER" item (mod doc-invsort-1101, killer560 2026-11-01): fewest clicks,
 * click-a-layout-to-swap, the key cell on each row, drag to reorder, a bigger menu, the open-menu key, clicks blocked
 * while it sorts, Instant (0-tick), the still-input wait GrimAC's MultiActionsC needs, and storage mode (fetch + dump) on
 * a fake storage page whose shift-clicks really move items ({@code menu.open} script {@code storage}). Every case runs on
 * the session's GrimAC server and must leave it silent, and each asserts the sorter ACTED (clicks the server handled,
 * items where the layout wants them) before quoting that.
 */
final class InvSortDocCases {

    private static final String ISC = "invsort.InventorySorterConfig";
    private static final String EXEC = "invsort.InventorySorterExecutor";
    private static final String STORE = "invsort.InventoryLayoutStore";
    private static final String SCREEN = "invsort.InventorySorterScreen";

    private InvSortDocCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1101-invsort-minimal", InvSortDocCases::minimal);
        MenuSuite.test(s, "1101-invsort-instant", InvSortDocCases::instant);
        MenuSuite.test(s, "1101-invsort-walk-start", InvSortDocCases::walkStart);
        MenuSuite.test(s, "1101-invsort-block-clicks", InvSortDocCases::blockClicks);
        MenuSuite.test(s, "1101-invsort-menu-rows", InvSortDocCases::menuRows);
        MenuSuite.test(s, "1101-invsort-open-key", InvSortDocCases::openKey);
        MenuSuite.test(s, "1101-invsort-storage", InvSortDocCases::storage);
        MenuSuite.test(s, "1103-invsort-dump-all", InvSortDocCases::dumpAll);
    }

    // ------------------------------------------------------------------------------------------------- helpers

    private static Path cfgPath(Session c) {
        return c.onClient(mc -> (Path) Mod.field(ISC, "CONFIG_PATH"));
    }

    private static byte[] read(Path p) {
        try {
            return Files.exists(p) ? Files.readAllBytes(p) : null;
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    private static void restore(Session c, Path p, byte[] old) {
        c.ctx().runOnClient(mc -> {
            try {
                if (old == null) {
                    Files.deleteIfExists(p);
                } else {
                    Files.write(p, old);
                }
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
            Mod.staticCall(ISC, "load");
        });
    }

    private static void cfg(Session c, String setter, Object value) {
        c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(ISC), setter, value));
    }

    private static String id(Session c, Item item) {
        return c.onClient(mc -> (String) Mod.staticCall("autoroutes.ItemIdentity", "of", new ItemStack(item)));
    }

    private static Object layout(Session c, String name, Map<Integer, String> slots) {
        return c.onClient(mc -> {
            try {
                return Mod.cls("invsort.InventoryLayout").getConstructor(String.class, Map.class).newInstance(name, slots);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
    }

    private static Object storageLayout(Session c, String name, Map<Integer, String> slots, boolean fetch, String dump) {
        Object plain = layout(c, name, slots);
        return c.onClient(mc -> Mod.call(plain, "withStorage", fetch, dump));
    }

    private static void save(Session c, Object layout) {
        c.ctx().runOnClient(mc -> Mod.call(Mod.staticCall(STORE, "getInstance"), "save", layout));
    }

    private static void delete(Session c, String name) {
        c.ctx().runOnClient(mc -> Mod.call(Mod.staticCall(STORE, "getInstance"), "delete", name));
    }

    private static boolean running(Session c) {
        return c.onClient(mc -> (Boolean) Mod.staticCall(EXEC, "isRunning"));
    }

    private static int[] counters(Session c) {
        return c.onClient(mc -> (int[]) Mod.staticCall(EXEC, "counters"));
    }

    private static List<JsonObject> clicksSince(Session c, int before) {
        List<JsonObject> all = c.events("container.click");
        return new ArrayList<>(all.subList(before, all.size()));
    }

    private static String show(List<JsonObject> clicks) {
        List<String> out = new ArrayList<>();
        for (JsonObject e : clicks) {
            out.add(e.get("slot").getAsInt() + ":" + e.get("button").getAsInt() + ":" + e.get("input").getAsString()
                    + "@" + e.get("tick").getAsLong() + "[" + e.get("serverInput").getAsString() + "]");
        }
        return out.toString();
    }

    /** Inventory slot -> item, as the client holds it now. */
    private static Map<Integer, Item> inv(Session c, int... slots) {
        return c.onClient(mc -> {
            Map<Integer, Item> m = new LinkedHashMap<>();
            for (int s : slots) {
                m.put(s, mc.player.getInventory().getItem(s).getItem());
            }
            return m;
        });
    }

    /** The planner's own plan length for the live inventory and {@code want} (the minimum it proves). */
    private static int plannedMinimum(Session c, Map<Integer, String> want) {
        return c.onClient(mc -> {
            String[] cur = new String[36];
            for (int i = 0; i < 36; i++) {
                ItemStack s = mc.player.getInventory().getItem(i);
                cur[i] = s.isEmpty() ? null : (String) Mod.staticCall("autoroutes.ItemIdentity", "of", s);
            }
            Object plan = Mod.staticCall("invsort.InvSortPlanner", "plan", cur, null, want, 9);
            return ((List<?>) Mod.call(plan, "clicks")).size();
        });
    }

    /**
     * Gives the 7-item test inventory: a hotbar 2-cycle (0<->9), a main-storage 3-cycle (10->11->12->10) and a move to an
     * empty main slot (13 -> 20), so the plan mixes swaps, a cursor cycle and a cursor path.
     */
    private static Map<Integer, String> giveMixed(Session c) {
        c.hx().give(0, "minecraft:diamond");
        c.hx().give(9, "minecraft:emerald");
        c.hx().give(10, "minecraft:iron_ingot");
        c.hx().give(11, "minecraft:gold_ingot");
        c.hx().give(12, "minecraft:coal");
        c.hx().give(13, "minecraft:redstone");
        c.waitUntil("the test items", mc -> mc.player.getInventory().getItem(13).is(Items.REDSTONE)
                && mc.player.getInventory().getItem(0).is(Items.DIAMOND), 80);
        Map<Integer, String> want = new LinkedHashMap<>();
        want.put(0, id(c, Items.EMERALD));
        want.put(9, id(c, Items.DIAMOND));
        want.put(11, id(c, Items.IRON_INGOT));
        want.put(12, id(c, Items.GOLD_INGOT));
        want.put(10, id(c, Items.COAL));
        want.put(20, id(c, Items.REDSTONE));
        return want;
    }

    private static void checkMixedDone(Session c) {
        Map<Integer, Item> m = inv(c, 0, 9, 10, 11, 12, 20, 13);
        c.check(m.get(0) == Items.EMERALD && m.get(9) == Items.DIAMOND && m.get(11) == Items.IRON_INGOT
                && m.get(12) == Items.GOLD_INGOT && m.get(10) == Items.COAL && m.get(20) == Items.REDSTONE
                && m.get(13) == Items.AIR, "inventory after the sort " + m);
    }

    /** Every click's server-side view must be still (GrimAC MultiActionsC): no movement key, no sprint, no sneak. */
    private static void checkStill(Session c, List<JsonObject> clicks) {
        for (JsonObject e : clicks) {
            String in = e.get("serverInput").getAsString();
            boolean moving = !in.isEmpty(); // forward, back, left, right, jump, sneak or sprint
            c.check(!moving && !e.get("sprinting").getAsBoolean() && !e.get("shift").getAsBoolean(),
                    "a click went out with the server holding movement: " + show(List.of(e)));
        }
    }

    private static void finishRun(Session c) {
        if (running(c)) {
            c.ctx().runOnClient(mc -> Mod.staticCall(EXEC, "requestStop"));
        }
    }

    // ------------------------------------------------------------------------------------------------- cases

    /** Paced sort of the mixed inventory: the server gets exactly the planner's minimum, and the inventory is right. */
    static void minimal(Session c) throws Exception {
        MenuKit.reset(c);
        InventoryCases.clearInv(c);
        if (!MenuKit.cheat()) {
            // Legit: the sorter and its Instant option must be gone (compiled out through BuildVariant).
            Object l0 = layout(c, "hx-1101-legit", Map.of(0, "EMERALD"));
            boolean started = c.onClient(mc -> (Boolean) Mod.staticCall(EXEC, "start", l0));
            boolean instant = c.onClient(mc -> {
                Object cfg = Mod.cfg(ISC);
                boolean was = (Boolean) Mod.call(cfg, "isInstantRaw");
                Mod.call(cfg, "setInstant", true);
                boolean on = (Boolean) Mod.call(cfg, "isInstant");
                Mod.call(cfg, "setInstant", was);
                return on;
            });
            c.note("legit jar: start " + started + ", isInstant after setInstant(true) " + instant);
            c.check(!started && !instant, "the legit jar has the sorter or Instant");
            return;
        }
        Path p = cfgPath(c);
        byte[] old = read(p);
        try {
            cfg(c, "setTicksBetweenMoves", 2);
            cfg(c, "setRandomExtraTicks", 0);
            cfg(c, "setInstant", false);
            Map<Integer, String> want = giveMixed(c);
            int minimum = plannedMinimum(c, want);
            int before = c.events("container.click").size();
            Object l = layout(c, "hx-1101-min", want);
            c.check(c.onClient(mc -> (Boolean) Mod.staticCall(EXEC, "start", l)), "start returned false");
            c.waitUntil("the sorter to finish", mc -> !(Boolean) Mod.staticCall(EXEC, "isRunning"), 400);
            c.ctx().waitTicks(10);
            List<JsonObject> clicks = clicksSince(c, before);
            int[] k = counters(c);
            c.note("planner minimum " + minimum + " (1 swap for the hotbar pair, 4 for the 3-cycle, 2 for the path); server got "
                    + clicks.size() + " " + show(clicks) + "; counters " + java.util.Arrays.toString(k));
            checkMixedDone(c);
            // The vanilla shape (invwalk-legit-model.md): inventory open while it clicks, then a close of window 0 after
            // the last click.
            List<JsonObject> closes = c.events("container.close");
            long lastClick = clicks.isEmpty() ? -1 : clicks.get(clicks.size() - 1).get("tick").getAsLong();
            boolean closed = closes.stream().anyMatch(e -> e.get("containerId").getAsInt() == 0
                    && e.get("tick").getAsLong() >= lastClick);
            c.note("close(0) after the last click: " + closed);
            c.check(closed, "no close of window 0 after the sort's clicks");
            c.check(minimum == 7, "planner minimum " + minimum + ", want 7");
            c.check(clicks.size() == minimum && k[0] == minimum, "sent " + clicks.size() + " clicks, minimum " + minimum);
            c.check(clicks.stream().allMatch(e -> e.get("containerId").getAsInt() == 0), "not the inventory menu");
            c.check(c.onClient(mc -> (Boolean) Mod.staticCall(EXEC, "lastPlanProven")), "plan not proven minimal");
            checkStill(c, clicks);
        } finally {
            finishRun(c);
            MenuKit.reset(c);
            restore(c, p, old);
            InventoryCases.clearInv(c);
        }
    }

    /** Instant: every click in one client tick, no screen opened, the same minimum, on GrimAC. */
    static void instant(Session c) throws Exception {
        MenuKit.reset(c);
        InventoryCases.clearInv(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: no sorter");
            return;
        }
        Path p = cfgPath(c);
        byte[] old = read(p);
        try {
            cfg(c, "setInstant", true);
            Map<Integer, String> want = giveMixed(c);
            int minimum = plannedMinimum(c, want);
            int before = c.events("container.click").size();
            Object l = layout(c, "hx-1101-instant", want);
            List<String> screens = new ArrayList<>();
            c.check(c.onClient(mc -> (Boolean) Mod.staticCall(EXEC, "start", l)), "start returned false");
            for (int i = 0; i < 40 && running(c); i++) {
                c.ctx().waitTicks(1);
                screens.add(c.onClient(mc -> McCompat.screen(mc) == null ? "-" : McCompat.screen(mc).getClass().getSimpleName()));
            }
            c.ctx().waitTicks(10);
            List<JsonObject> clicks = clicksSince(c, before);
            int[] k = counters(c);
            long ticks = clicks.stream().map(e -> e.get("tick").getAsLong()).distinct().count();
            c.note("instant: " + clicks.size() + " clicks on " + ticks + " server tick(s) " + show(clicks)
                    + "; most in one client tick " + k[5] + "; screens while running " + screens);
            checkMixedDone(c);
            c.check(clicks.size() == minimum && k[5] == minimum, "instant sent " + clicks.size() + ", most in one tick "
                    + k[5] + ", minimum " + minimum);
            c.check(ticks <= 2, "instant clicks spread over " + ticks + " server ticks");
            c.check(screens.stream().allMatch("-"::equals), "a screen opened: " + screens);
            checkStill(c, clicks);
        } finally {
            finishRun(c);
            MenuKit.reset(c);
            restore(c, p, old);
            InventoryCases.clearInv(c);
        }
    }

    /** Started while walking forward and sprinting: no click may go out until the server has him still. */
    static void walkStart(Session c) throws Exception {
        MenuKit.reset(c);
        InventoryCases.clearInv(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: no sorter");
            return;
        }
        Path p = cfgPath(c);
        byte[] old = read(p);
        int fwd = McKeys.nat(87);
        try {
            cfg(c, "setTicksBetweenMoves", 1);
            cfg(c, "setRandomExtraTicks", 0);
            cfg(c, "setInstant", true); // the hardest case: everything in the first tick it may
            Map<Integer, String> want = giveMixed(c);
            int before = c.events("container.click").size();
            c.ctx().getInput().holdKey(fwd);
            c.ctx().runOnClient(mc -> mc.player.setSprinting(true));
            c.ctx().waitTicks(10);
            // What MultiActionsC reads is the server's last INPUT, not distance (a wall can stop him): it must say forward.
            String serverInput = c.hx().call("player.state").getAsJsonObject().get("input").getAsString();
            Object l = layout(c, "hx-1101-walk", want);
            c.check(c.onClient(mc -> (Boolean) Mod.staticCall(EXEC, "start", l)), "start returned false");
            c.waitUntil("the sorter to finish", mc -> !(Boolean) Mod.staticCall(EXEC, "isRunning"), 200);
            c.ctx().getInput().releaseKey(fwd);
            c.ctx().waitTicks(10);
            List<JsonObject> clicks = clicksSince(c, before);
            c.note("server input when the sort started: [" + serverInput + "]; clicks " + show(clicks));
            c.check(serverInput.contains("forward"), "the server never had him walking (input [" + serverInput
                    + "]), so the still wait proves nothing");
            c.check(!clicks.isEmpty(), "no clicks");
            checkMixedDone(c);
            checkStill(c, clicks);
        } finally {
            c.ctx().getInput().releaseKey(fwd);
            finishRun(c);
            MenuKit.reset(c);
            restore(c, p, old);
            InventoryCases.clearInv(c);
        }
    }

    /** While a slow sort runs: a real mouse click on a slot and a number key over it reach nothing. */
    static void blockClicks(Session c) throws Exception {
        MenuKit.reset(c);
        InventoryCases.clearInv(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: no sorter");
            return;
        }
        Path p = cfgPath(c);
        byte[] old = read(p);
        try {
            cfg(c, "setTicksBetweenMoves", 12);
            cfg(c, "setRandomExtraTicks", 0);
            cfg(c, "setInstant", false);
            Map<Integer, String> want = giveMixed(c);
            c.hx().give(15, "minecraft:stick");
            c.waitUntil("a stick in slot 15", mc -> mc.player.getInventory().getItem(15).is(Items.STICK), 40);
            want.put(15, id(c, Items.STICK)); // already right: the sort itself never touches it
            int minimum = plannedMinimum(c, want);
            int before2 = c.events("container.click").size();
            Object l2 = layout(c, "hx-1101-block", want);
            c.check(c.onClient(mc -> (Boolean) Mod.staticCall(EXEC, "start", l2)), "start returned false");
            c.waitUntil("the sorting inventory", mc -> McCompat.screen(mc) != null
                    && McCompat.screen(mc).getClass().getSimpleName().equals("SortingInventoryScreen"), 60);
            c.waitUntil("the first click", mc -> c.events("container.click").size() > before2, 60);
            // A real left and right click on inventory slot 15 through the mouse handler, then number key 3 over it.
            double[] at = c.onClient(mc -> {
                AbstractContainerScreen<?> s = (AbstractContainerScreen<?>) McCompat.screen(mc);
                Slot slot = s.getMenu().slots.get(15);
                int left = (Integer) Mod.field(s, "leftPos");
                int top = (Integer) Mod.field(s, "topPos");
                double scale = mc.getWindow().getGuiScale();
                return new double[]{(left + slot.x + 8) * scale, (top + slot.y + 8) * scale};
            });
            c.ctx().getInput().setCursorPos(at[0], at[1]);
            c.ctx().waitTicks(2);
            c.ctx().runOnClient(mc -> {
                long window = mc.getWindow().handle();
                Mod.call(mc.mouseHandler, "onButton", window, new MouseButtonInfo(McKeys.mouse(0), 0), 1);
                Mod.call(mc.mouseHandler, "onButton", window, new MouseButtonInfo(McKeys.mouse(0), 0), 0);
                Mod.call(mc.mouseHandler, "onButton", window, new MouseButtonInfo(McKeys.mouse(1), 0), 1);
                Mod.call(mc.mouseHandler, "onButton", window, new MouseButtonInfo(McKeys.mouse(1), 0), 0);
            });
            c.ctx().getInput().pressKey(McKeys.nat(51)); // number key 3 over slot 15
            String carried = c.onClient(mc -> mc.player.containerMenu.getCarried().getItem().toString());
            boolean stickHome = c.onClient(mc -> mc.player.getInventory().getItem(15).is(Items.STICK));
            c.waitUntil("the sorter to finish", mc -> !(Boolean) Mod.staticCall(EXEC, "isRunning"), 600);
            c.ctx().waitTicks(10);
            List<JsonObject> clicks = clicksSince(c, before2);
            c.note("while sorting: left + right click on slot 15 and key 3; cursor then " + carried + ", stick still in 15 "
                    + stickHome + "; server clicks " + clicks.size() + " (plan " + minimum + ") " + show(clicks));
            c.check(stickHome && carried.contains("air"), "a click reached the inventory: cursor " + carried);
            c.check(clicks.size() == minimum, "server got " + clicks.size() + " clicks, the sort's own are " + minimum);
            c.check(clicks.stream().noneMatch(e -> e.get("slot").getAsInt() == 15), "a click on slot 15 went out");
            checkMixedDone(c);
        } finally {
            finishRun(c);
            MenuKit.reset(c);
            restore(c, p, old);
            InventoryCases.clearInv(c);
        }
    }

    private static MouseButtonEvent ev(double x, double y, int glfwButton) {
        return new MouseButtonEvent(x, y, new MouseButtonInfo(McKeys.mouse(glfwButton), 0));
    }

    /** Row clicks: right click edits, the key cell captures, drag reorders (kept after a reload), left click swaps. */
    static void menuRows(Session c) throws Exception {
        MenuKit.reset(c);
        InventoryCases.clearInv(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: no sorter");
            return;
        }
        Path p = cfgPath(c);
        byte[] old = read(p);
        String[] names = {"hx-1101-a", "hx-1101-b", "hx-1101-c"};
        try {
            cfg(c, "setEnabled", true);
            cfg(c, "setInstant", false);
            cfg(c, "setTicksBetweenMoves", 1);
            cfg(c, "setRandomExtraTicks", 0);
            cfg(c, "setLayoutOrder", List.of());
            Map<Integer, String> want = giveMixed(c);
            for (String n : names) {
                save(c, layout(c, n, want));
            }
            c.ctx().runOnClient(mc -> {
                try {
                    Screen s = (Screen) Mod.cls(SCREEN).getConstructor(Screen.class).newInstance((Object) null);
                    McCompat.setScreen(mc, s);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            c.waitUntil("the layouts menu", mc -> McCompat.screen(mc) != null
                    && McCompat.screen(mc).getClass().getSimpleName().equals("InventorySorterScreen"), 40);
            // Size: bigger than the old fixed 18 px slots where the window allows, and it fits.
            int[] geo = c.onClient(mc -> {
                Screen s = McCompat.screen(mc);
                return new int[]{(Integer) Mod.field(s, "SLOT"), (Integer) Mod.field(s, "PANEL_W"), (Integer) Mod.field(s, "PANEL_H"),
                        s.width, s.height, (Integer) Mod.field(s, "LIST_ROW")};
            });
            int[] big = c.onClient(mc -> {
                Screen s = McCompat.screen(mc);
                int w = s.width;
                int h = s.height;
                s.width = 960;
                s.height = 540;
                Mod.call(s, "clearWidgets");
                Mod.call(s, "init");
                int[] out = {(Integer) Mod.field(s, "SLOT"), (Integer) Mod.field(s, "PANEL_W"), (Integer) Mod.field(s, "PANEL_H"),
                        (Integer) Mod.field(s, "LIST_ROW")};
                s.width = w;
                s.height = h;
                Mod.call(s, "clearWidgets");
                Mod.call(s, "init");
                return out;
            });
            c.note("menu at " + geo[3] + "x" + geo[4] + ": slot " + geo[0] + ", panel " + geo[1] + "x" + geo[2] + ", row " + geo[5]
                    + "; at 960x540: slot " + big[0] + ", panel " + big[1] + "x" + big[2] + ", row " + big[3]
                    + " (was 18 px slots, 356x176, rows 13)");
            c.check(geo[1] <= geo[3] && geo[2] <= geo[4], "the menu does not fit its window");
            // Rows carry the layout's items since mod invsort-1103 (killer560 2026-11-03: "make the saved loadouts portion
            // bigger"): 16 px name-only rows became preview rows of 30+ px.
            c.check(big[0] == 26 && big[3] >= 30 && big[1] > 356 && big[2] > 176, "not larger on a 960x540 window");

            // Row geometry.
            int[] g = c.onClient(mc -> {
                Screen s = McCompat.screen(mc);
                return new int[]{(Integer) Mod.field(s, "listX"), (Integer) Mod.field(s, "listY"), (Integer) Mod.field(s, "listW"),
                        (Integer) Mod.field(s, "LIST_ROW"), (Integer) Mod.field(SCREEN, "KEY_W")};
            });
            List<String> order0 = c.onClient(mc -> (List<String>) Mod.call(Mod.staticCall(STORE, "getInstance"), "listNames"));
            int rowA = order0.indexOf(names[0]);
            c.check(rowA >= 0 && order0.indexOf(names[2]) >= 0, "layouts not listed: " + order0);
            double nameX = g[0] + 10;
            double keyX = g[0] + g[2] - g[4] / 2.0;
            java.util.function.IntFunction<Double> rowY = r -> g[1] + 1 + r * g[3] + g[3] / 2.0;

            // Right click edits (selects, stays open).
            c.ctx().runOnClient(mc -> {
                Screen s = McCompat.screen(mc);
                s.mouseClicked(ev(nameX, rowY.apply(rowA), 1), false);
                s.mouseReleased(ev(nameX, rowY.apply(rowA), 1));
            });
            String sel = c.onClient(mc -> (String) Mod.call(McCompat.screen(mc), "selectedLayout"));
            c.check(names[0].equals(sel) && !running(c), "right click: selected " + sel + ", running " + running(c));

            // Key cell: capture mode for that row.
            int rowB = order0.indexOf(names[1]);
            c.ctx().runOnClient(mc -> McCompat.screen(mc).mouseClicked(ev(keyX, rowY.apply(rowB), 0), false));
            boolean capturing = c.onClient(mc -> (Boolean) Mod.field(McCompat.screen(mc), "capturingKey"));
            String selKey = c.onClient(mc -> (String) Mod.call(McCompat.screen(mc), "selectedLayout"));
            c.ctx().runOnClient(mc -> Mod.call(McCompat.screen(mc), "bind", 74));
            int bound = c.onClient(mc -> (Integer) Mod.call(Mod.cfg(ISC), "getLayoutKey", names[1]));
            c.check(capturing && names[1].equals(selKey) && bound == 74, "key cell: capturing " + capturing + " for "
                    + selKey + ", bound " + bound);

            // Drag the first row below the last.
            List<String> before = c.onClient(mc -> (List<String>) Mod.call(Mod.staticCall(STORE, "getInstance"), "listNames"));
            int last = before.size();
            c.ctx().runOnClient(mc -> {
                Screen s = McCompat.screen(mc);
                s.mouseClicked(ev(nameX, rowY.apply(0), 0), false);
                s.mouseDragged(ev(nameX, rowY.apply(1), 0), 0, g[3]);
                s.mouseReleased(ev(nameX, g[1] + 1 + last * g[3], 0));
            });
            List<String> after = c.onClient(mc -> {
                Mod.staticCall(ISC, "load");
                return (List<String>) Mod.call(Mod.staticCall(STORE, "getInstance"), "listNames");
            });
            List<String> expect = new ArrayList<>(before);
            expect.add(expect.remove(0));
            c.note("drag: " + before + " -> " + after + " (after a config reload)");
            c.check(after.equals(expect), "drag order " + after + ", want " + expect);
            c.check(!running(c), "a drag applied a layout");

            // Left click swaps at once: the menu closes and the sort runs to the end.
            int before2 = c.events("container.click").size();
            int rowAnow = after.indexOf(names[0]);
            c.ctx().runOnClient(mc -> {
                Screen s = McCompat.screen(mc);
                s.mouseClicked(ev(nameX, rowY.apply(rowAnow), 0), false);
                s.mouseReleased(ev(nameX, rowY.apply(rowAnow), 0));
            });
            c.waitUntil("the sorter to start", mc -> (Boolean) Mod.staticCall(EXEC, "isRunning"), 20);
            c.waitUntil("the sorter to finish", mc -> !(Boolean) Mod.staticCall(EXEC, "isRunning"), 400);
            c.ctx().waitTicks(10);
            List<JsonObject> clicks = clicksSince(c, before2);
            c.note("left click on " + names[0] + ": " + clicks.size() + " clicks " + show(clicks));
            checkMixedDone(c);
            c.check(clicks.size() == 7, "left click sorted with " + clicks.size() + " clicks");
        } finally {
            finishRun(c);
            for (String n : names) {
                delete(c, n);
            }
            MenuKit.reset(c);
            restore(c, p, old);
            InventoryCases.clearInv(c);
        }
    }

    /** The Open Menu Key opens the layouts menu with no screen up. */
    static void openKey(Session c) throws Exception {
        MenuKit.reset(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: no sorter");
            return;
        }
        Path p = cfgPath(c);
        byte[] old = read(p);
        try {
            cfg(c, "setEnabled", true);
            cfg(c, "setOpenMenuKey", 298); // F9 (GLFW number, as settings store it)
            c.ctx().getInput().holdKeyFor(McKeys.nat(298), 3);
            c.waitUntil("the layouts menu", mc -> McCompat.screen(mc) != null
                    && McCompat.screen(mc).getClass().getSimpleName().equals("InventorySorterScreen"), 40);
            c.note("F9 opened " + c.onClient(mc -> McCompat.screen(mc).getClass().getSimpleName()));
        } finally {
            c.ctx().runOnClient(mc -> McCompat.setScreen(mc, null));
            restore(c, p, old);
        }
    }

    /**
     * Storage mode on a fake Ender Chest page 1: the layout wants a diamond it does not have (cached in page 1) and the
     * inventory holds a stick and a bone the layout does not use; Put Others In is page 1. One visit: the stick and bone
     * go in, the diamond comes out, then the sort puts the diamond in its slot.
     */
    /**
     * killer560, 2026-11-03: "it should empty out my full inventory so only the stuff in the invsort menu is in my
     * inventory at the end". A layout of 3 items, eight other items scattered over the hotbar and the main inventory, the
     * Skyblock Menu in slot 9 (index 8), Put Others In = Ender Chest 1: afterwards the inventory holds exactly the three
     * layout items and the menu, the eight are in the page (eight shift-clicks out), and no click touches slot 9.
     */
    static void dumpAll(Session c) throws Exception {
        MenuKit.reset(c);
        InventoryCases.clearInv(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: no sorter");
            return;
        }
        Path p = cfgPath(c);
        byte[] old = read(p);
        try {
            cfg(c, "setTicksBetweenMoves", 2);
            cfg(c, "setRandomExtraTicks", 0);
            cfg(c, "setInstant", false);
            c.hx().stub(null, "enderchest", "backpack");
            c.hx().give(10, "minecraft:diamond");
            c.hx().give(11, "minecraft:emerald");
            c.hx().give(12, "minecraft:stick");
            String[] others = {"coal", "iron_ingot", "gold_ingot", "apple", "bread", "carrot", "cookie", "potato"};
            int[] at = {3, 4, 5, 9, 13, 14, 15, 16};
            for (int i = 0; i < others.length; i++) {
                c.hx().give(at[i], "minecraft:" + others[i]);
            }
            c.hx().give(8, "minecraft:nether_star[custom_data={id:\"SKYBLOCK_MENU\"}]");
            c.waitUntil("the items", mc -> mc.player.getInventory().getItem(16).is(Items.POTATO)
                    && mc.player.getInventory().getItem(8).is(Items.NETHER_STAR)
                    && mc.player.getInventory().getItem(10).is(Items.DIAMOND), 80);
            Map<Integer, String> want = new LinkedHashMap<>();
            want.put(0, id(c, Items.DIAMOND));
            want.put(1, id(c, Items.EMERALD));
            want.put(2, id(c, Items.STICK));
            Object l = storageLayout(c, "hx-1103-dumpall", want, false, "enderchest_1");
            long since = c.hx().head();
            int before = c.events("container.click").size();
            c.check(c.onClient(mc -> (Boolean) Mod.staticCall(EXEC, "start", l)), "start returned false");
            boolean opened = false;
            for (int i = 0; i < 200 && !opened; i++) {
                c.ctx().waitTicks(1);
                if (c.hx().commands(since).stream().anyMatch(cmd -> cmd.startsWith("enderchest 1"))) {
                    c.hx().menu("Ender Chest (1/9)", 6, Map.of(0, "minecraft:black_stained_glass_pane"), "storage");
                    opened = true;
                }
            }
            c.check(opened, "the sorter never sent /enderchest 1; commands " + c.hx().commands(since));
            c.waitUntil("the sorter to finish", mc -> !(Boolean) Mod.staticCall(EXEC, "isRunning"), 800);
            c.ctx().waitTicks(10);
            List<JsonObject> clicks = clicksSince(c, before);
            String left = c.onClient(mc -> {
                List<String> out = new ArrayList<>();
                for (int i = 0; i < 36; i++) {
                    ItemStack s = mc.player.getInventory().getItem(i);
                    if (!s.isEmpty()) {
                        out.add(i + ":" + s.getItem());
                    }
                }
                return out.toString();
            });
            long out = clicks.stream().filter(e -> e.get("input").getAsString().equals("QUICK_MOVE")).count();
            c.note("inventory after: " + left + "; shift-clicks " + out + "; clicks " + show(clicks));
            c.check(left.equals("[0:minecraft:diamond, 1:minecraft:emerald, 2:minecraft:stick, 8:minecraft:nether_star]"),
                    "the inventory is not just the layout and the menu: " + left);
            c.check(out == 8, "want 8 shift-clicks out into the page: " + show(clicks));
            c.check(clicks.stream().noneMatch(e -> e.get("slot").getAsInt() == 44 || e.get("slot").getAsInt() == 89),
                    "a click touched slot 9: " + show(clicks));
            checkStill(c, clicks);
        } finally {
            finishRun(c);
            MenuKit.reset(c);
            restore(c, p, old);
            InventoryCases.clearInv(c);
        }
    }

    static void storage(Session c) throws Exception {
        MenuKit.reset(c);
        InventoryCases.clearInv(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: no sorter");
            return;
        }
        // On Hypixel the last hotbar slot always holds the Skyblock Menu (or map/arrows) and the sorter never touches it
        // (killer560, 2026-11-03); an EMPTY slot 8 would be where a shift-click from storage lands first.
        c.hx().give(8, "minecraft:nether_star[custom_data={id:\"SKYBLOCK_MENU\"}]");
        Path p = cfgPath(c);
        byte[] old = read(p);
        String key =c.onClient(mc -> (String) Mod.staticCall("storageoverlay.StorageOverlayFeature", "storageKeyForTitle",
                "Ender Chest (1/9)"));
        try {
            cfg(c, "setTicksBetweenMoves", 2);
            cfg(c, "setRandomExtraTicks", 0);
            cfg(c, "setInstant", false);
            c.hx().stub(null, "enderchest", "backpack");
            c.hx().give(0, "minecraft:emerald");
            c.hx().give(5, "minecraft:stick");
            c.hx().give(14, "minecraft:bone");
            c.waitUntil("the test items", mc -> mc.player.getInventory().getItem(14).is(Items.BONE), 60);
            // The Storage Overlay's cache says page 1 holds a diamond (in page slot 12 -> cache index 3).
            c.ctx().runOnClient(mc -> {
                List<ItemStack> page = new ArrayList<>();
                for (int i = 0; i < 45; i++) {
                    page.add(i == 3 ? new ItemStack(Items.DIAMOND) : ItemStack.EMPTY);
                }
                Mod.call(Mod.staticCall("storageoverlay.StorageOverlayCache", "getInstance"), "put", key, page);
            });
            Map<Integer, String> want = new LinkedHashMap<>();
            want.put(1, id(c, Items.EMERALD));
            want.put(2, id(c, Items.DIAMOND));
            Object l = storageLayout(c, "hx-1101-storage", want, true, "enderchest_1");
            long since = c.hx().head();
            int before = c.events("container.click").size();
            c.check(c.onClient(mc -> (Boolean) Mod.staticCall(EXEC, "start", l)), "start returned false");
            boolean opened = false;
            for (int i = 0; i < 80 && !opened; i++) {
                c.ctx().waitTicks(1);
                if (c.hx().commands(since).stream().anyMatch(cmd -> cmd.startsWith("enderchest 1"))) {
                    c.hx().menu("Ender Chest (1/9)", 6, Map.of(0, "minecraft:black_stained_glass_pane",
                            12, "minecraft:diamond"), "storage");
                    opened = true;
                }
            }
            c.check(opened, "the sorter never sent /enderchest 1; commands " + c.hx().commands(since));
            c.waitUntil("the sorter to finish", mc -> !(Boolean) Mod.staticCall(EXEC, "isRunning"), 600);
            c.ctx().waitTicks(10);
            List<JsonObject> clicks = clicksSince(c, before);
            Map<Integer, Item> m = inv(c, 0, 1, 2, 5, 14);
            boolean stickAnywhere = c.onClient(mc -> mc.player.getInventory().contains(new ItemStack(Items.STICK)));
            boolean boneAnywhere = c.onClient(mc -> mc.player.getInventory().contains(new ItemStack(Items.BONE)));
            List<String> lines = LogTap.since(0).stream().filter(x -> x.contains("[InvSort]")).toList();
            c.note("storage: inventory " + m + ", stick kept " + stickAnywhere + ", bone kept " + boneAnywhere + "; clicks "
                    + show(clicks) + "; log " + lines.subList(Math.max(0, lines.size() - 6), lines.size()));
            c.check(m.get(1) == Items.EMERALD && m.get(2) == Items.DIAMOND, "layout not met: " + m);
            c.check(!stickAnywhere && !boneAnywhere, "the unused items were not put away");
            c.check(clicks.stream().filter(e -> e.get("input").getAsString().equals("QUICK_MOVE")).count() == 3,
                    "want 3 shift-clicks (2 in, 1 out): " + show(clicks));
            c.check(clicks.stream().noneMatch(e -> e.get("input").getAsString().equals("QUICK_MOVE")
                    && e.get("slot").getAsInt() < 9), "a chrome slot was clicked");
            checkStill(c, clicks);
        } finally {
            finishRun(c);
            MenuKit.reset(c);
            restore(c, p, old);
            InventoryCases.clearInv(c);
        }
    }
}
