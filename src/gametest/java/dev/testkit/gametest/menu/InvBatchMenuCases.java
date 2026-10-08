package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 583 and 585, killer560's 2026-10-08 inventory batch (mod branch inv-batch), on the menu server so every click is
 * counted where it lands ({@code container.click}).
 * <ul>
 *   <li>583 Item Protect blocks everything Slot Lock blocked, per action: the drop key, Q over a slot, dropping the
 *       cursor outside the window, and a click in Sell / Salvage / Trade / Anvil / Create BIN Auction menus (protected
 *       by UUID and starred-only), a number-key swap that would put it in a sell menu; with Lock In Place, any pickup,
 *       shift-click or number-key swap in his own inventory. Every blocked action has an unprotected control that the
 *       server does receive.</li>
 *   <li>585 the sorter's real clicks: a hotbar destination is ONE SWAP click with the hotbar index as the button, any
 *       other destination PICKUP clicks; Ticks Between Moves sets the server-side gap exactly, Random Extra Ticks
 *       spreads it; a change to the inventory mid-run stops it; the legit jar sends nothing.</li>
 * </ul>
 */
final class InvBatchMenuCases {

    private static final String IPC = "itemprotect.ItemProtectConfig";
    private static final String ISC = "invsort.InventorySorterConfig";

    private InvBatchMenuCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "583-menu-itemprotect-blocks", InvBatchMenuCases::protectBlocks);
        MenuSuite.test(s, "585-menu-invsort-apply", InvBatchMenuCases::invsortApply);
    }

    private static Path configPath(Session c, String cls) {
        return c.onClient(mc -> (Path) Mod.field(cls, "CONFIG_PATH"));
    }

    private static byte[] read(Path p) {
        try {
            return Files.exists(p) ? Files.readAllBytes(p) : null;
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    private static void restore(Session c, String cls, Path p, byte[] old) {
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
            Mod.staticCall(cls, "load");
        });
    }

    /** A click through the screen's own slotClicked (where Item Protect sits); {@code slotIndex} -999 = outside. */
    private static void screenClick(Session c, int slotIndex, int button, ContainerInput input) {
        c.ctx().runOnClient(mc -> {
            AbstractContainerScreen<?> s = (AbstractContainerScreen<?>) McCompat.screen(mc);
            Slot slot = slotIndex < 0 ? null : s.getMenu().slots.get(slotIndex);
            Mod.call(s, "killer560smod$slotClicked", slot, slotIndex, button, input);
        });
    }

    private static int clicks(Session c) {
        return c.events("container.click").size();
    }

    /** Runs one action and returns how many clicks the server got from it (20 ticks to arrive). */
    private static int sent(Session c, Runnable action) {
        int before = clicks(c);
        action.run();
        c.ctx().waitTicks(20);
        return clicks(c) - before;
    }

    // ==== 583 =========================================================================================================

    static void protectBlocks(Session c) throws Exception {
        MenuKit.reset(c);
        InventoryCases.clearInv(c);
        Path cfgPath = configPath(c, IPC);
        byte[] old = read(cfgPath);
        Map<String, String> table = new LinkedHashMap<>();
        List<String> wrong = new ArrayList<>();
        try {
            c.ctx().runOnClient(mc -> {
                Object cfg = Mod.cfg(IPC);
                Mod.call(cfg, "setEnabled", true);
                Mod.call(cfg, "setProtectItemEnabled", true);
                Mod.call(cfg, "setProtectStarredEnabled", true);
                Mod.call(cfg, "setLockInPlace", false);
                Mod.call(cfg, "setPreventHotbarDropEnabled", true);
                Mod.call(cfg, "setConfirmToForce", false);
                Mod.call(cfg, "setBlockEveryHotbarDrop", false);
                Mod.call(cfg, "setBlockSound", false);
                Mod.call(cfg, "addProtectedKey", "hx-hyp-1");
            });
            c.hx().give(0, MenuKit.item("items.hyperion-protected-uuid"));
            c.hx().give(1, "minecraft:diamond");
            c.hx().give(2, MenuKit.item("items.necron-chestplate-starred"));
            c.waitUntil("the three test items", mc -> mc.player.getInventory().getItem(2).is(Items.LEATHER_CHESTPLATE)
                    && mc.player.getInventory().getItem(1).is(Items.DIAMOND), 80);

            // ---- the drop key (LocalPlayer.drop, the path the real key takes) ----
            c.ctx().runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
            c.ctx().waitTicks(3);
            c.ctx().runOnClient(mc -> mc.player.drop(false));
            c.ctx().waitTicks(20);
            boolean kept = c.onClient(mc -> mc.player.getInventory().getItem(0).is(Items.DIAMOND_SWORD));
            c.ctx().runOnClient(mc -> mc.player.getInventory().setSelectedSlot(1));
            c.ctx().waitTicks(3);
            c.ctx().runOnClient(mc -> mc.player.drop(false));
            c.waitUntil("the control diamond to be dropped", mc -> mc.player.getInventory().getItem(1).isEmpty(), 40);
            record(table, wrong, "drop key", kept ? 0 : 1, 1);
            c.ctx().waitTicks(20); // let the server apply the drop before giving the diamond back, or it lands first
            c.hx().give(1, "minecraft:diamond");
            c.ctx().runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
            c.waitUntil("the diamond back", mc -> mc.player.getInventory().getItem(1).is(Items.DIAMOND), 40);

            // ---- his own inventory: Q over the slot, the cursor dropped outside ----
            c.ctx().runOnClient(mc -> McCompat.setScreen(mc, new InventoryScreen(mc.player)));
            c.waitUntil("the inventory screen", mc -> McCompat.screen(mc) instanceof InventoryScreen, 40);
            c.ctx().waitTicks(3);
            int qProt = sent(c, () -> screenClick(c, 36, 0, ContainerInput.THROW));
            int qCtrl = sent(c, () -> screenClick(c, 37, 0, ContainerInput.THROW));
            record(table, wrong, "Q over the slot", qProt, qCtrl);
            c.ctx().waitTicks(10);
            c.hx().give(1, "minecraft:diamond");
            c.waitUntil("the diamond back", mc -> mc.player.getInventory().getItem(1).is(Items.DIAMOND), 40);
            int pick = sent(c, () -> screenClick(c, 36, 0, ContainerInput.PICKUP));
            int outside = sent(c, () -> screenClick(c, -999, 0, ContainerInput.PICKUP));
            int back = sent(c, () -> screenClick(c, 36, 0, ContainerInput.PICKUP));
            boolean stillThere = c.onClient(mc -> mc.player.getInventory().getItem(0).is(Items.DIAMOND_SWORD));
            table.put("cursor dropped outside", "picked up " + pick + ", outside click " + outside + ", put back " + back);
            if (pick != 1 || outside != 0 || back != 1 || !stillThere) {
                wrong.add("cursor outside: pick " + pick + " outside " + outside + " back " + back + " kept " + stillThere);
            }
            MenuKit.reset(c);

            // ---- the loss menus: protected (hotbar 0), starred (hotbar 2), control diamond (hotbar 1) ----
            String[][] menus = {
                    {"Sell", null},
                    {"Salvage Items", "{\"title\":\"Salvage Items\",\"rows\":6,\"fill\":true}"},
                    {"You            Bob", "{\"title\":\"You            Bob\",\"rows\":6,\"fill\":true}"},
                    {"Anvil", "{\"title\":\"Anvil\",\"rows\":6,\"fill\":true}"},
                    {"Create BIN Auction", "{\"title\":\"Create BIN Auction\",\"rows\":6,\"fill\":true}"},
            };
            for (String[] m : menus) {
                MenuKit.show(c, m[1] == null ? MenuKit.menu("menus.sell") : MenuKit.obj(m[1]));
                MenuKit.awaitScreen(c, m[0], 100);
                c.ctx().waitTicks(5);
                int top = c.onClient(mc -> ((AbstractContainerScreen<?>) McCompat.screen(mc)).getMenu().slots.size() - 36);
                int hot0 = top + 27;
                int prot = sent(c, () -> screenClick(c, hot0, 0, ContainerInput.PICKUP));
                int star = sent(c, () -> screenClick(c, hot0 + 2, 0, ContainerInput.QUICK_MOVE));
                int ctrl = sent(c, () -> screenClick(c, hot0 + 1, 0, ContainerInput.PICKUP));
                // Put the control back where it was if the menu let it onto the cursor.
                if (ctrl > 0) {
                    sent(c, () -> screenClick(c, hot0 + 1, 0, ContainerInput.PICKUP));
                }
                record(table, wrong, m[0] + ": click", prot, ctrl);
                record(table, wrong, m[0] + ": starred shift-click", star, ctrl);
                if (m[1] == null) {
                    // Number key 1 over the diamond would put the Hyperion into the sell menu's player slot.
                    int swap = sent(c, () -> screenClick(c, hot0 + 1, 0, ContainerInput.SWAP));
                    record(table, wrong, "Sell: number-key swap with it", swap, ctrl);
                }
                MenuKit.reset(c);
            }

            // ---- Lock In Place: his own inventory, any click ----
            c.ctx().runOnClient(mc -> McCompat.setScreen(mc, new InventoryScreen(mc.player)));
            c.waitUntil("the inventory screen", mc -> McCompat.screen(mc) instanceof InventoryScreen, 40);
            c.ctx().waitTicks(3);
            int freePick = sent(c, () -> screenClick(c, 36, 0, ContainerInput.PICKUP));
            sent(c, () -> screenClick(c, 36, 0, ContainerInput.PICKUP));
            c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(IPC), "setLockInPlace", true));
            int lockPick = sent(c, () -> screenClick(c, 36, 0, ContainerInput.PICKUP));
            int lockShift = sent(c, () -> screenClick(c, 36, 0, ContainerInput.QUICK_MOVE));
            int lockSwapFrom = sent(c, () -> screenClick(c, 36, 4, ContainerInput.SWAP));
            int lockSwapInto = sent(c, () -> screenClick(c, 37, 0, ContainerInput.SWAP));
            int lockCtrl = sent(c, () -> screenClick(c, 37, 0, ContainerInput.PICKUP));
            if (lockCtrl > 0) {
                sent(c, () -> screenClick(c, 37, 0, ContainerInput.PICKUP));
            }
            table.put("own inventory pickup, Lock In Place OFF", freePick + " click(s) (moving is allowed)");
            record(table, wrong, "Lock In Place: pickup", lockPick, lockCtrl);
            record(table, wrong, "Lock In Place: shift-click", lockShift, lockCtrl);
            record(table, wrong, "Lock In Place: number key from it", lockSwapFrom, lockCtrl);
            record(table, wrong, "Lock In Place: number key into its slot", lockSwapInto, lockCtrl);
            if (freePick != 1) {
                wrong.add("with Lock In Place OFF the protected item could not be moved (" + freePick + ")");
            }
            boolean endOk = c.onClient(mc -> mc.player.getInventory().getItem(0).is(Items.DIAMOND_SWORD)
                    && mc.player.getInventory().getItem(2).is(Items.LEATHER_CHESTPLATE));
            table.forEach((k, v) -> c.note(k + ": " + v));
            c.check(endOk, "the protected or starred item is gone from its slot at the end");
            c.check(wrong.isEmpty(), "not blocked / control failed: " + wrong);
        } finally {
            MenuKit.reset(c);
            restore(c, IPC, cfgPath, old);
            InventoryCases.clearInv(c);
        }
    }

    private static void record(Map<String, String> table, List<String> wrong, String what, int protectedClicks,
                               int controlClicks) {
        table.put(what, "protected " + protectedClicks + " packet(s), unprotected control " + controlClicks);
        if (protectedClicks != 0) {
            wrong.add(what + " (" + protectedClicks + " sent)");
        }
        if (controlClicks < 1) {
            wrong.add(what + ": the control sent nothing, so the block proves nothing");
        }
    }

    // ==== 585 =========================================================================================================

    private static Object layout(Session c, String name, Map<Integer, String> slots) {
        return c.onClient(mc -> {
            try {
                return Mod.cls("invsort.InventoryLayout").getConstructor(String.class, Map.class).newInstance(name, slots);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
    }

    private static String id(Session c, ItemStack s) {
        return c.onClient(mc -> (String) Mod.staticCall("autoroutes.ItemIdentity", "of", s));
    }

    private static boolean running(Session c) {
        return c.onClient(mc -> (Boolean) Mod.staticCall("invsort.InventorySorterExecutor", "isRunning"));
    }

    /** Runs a layout to the end; returns the clicks the server got from it, as "slot:button:input@tick". */
    private static List<JsonObject> run(Session c, Object layout) {
        int before = clicks(c);
        boolean started = c.onClient(mc -> (Boolean) Mod.staticCall("invsort.InventorySorterExecutor", "start", layout));
        c.check(started, "InventorySorterExecutor.start returned false");
        c.waitUntil("the sorter to finish", mc -> !(Boolean) Mod.staticCall("invsort.InventorySorterExecutor", "isRunning"), 600);
        c.ctx().waitTicks(10);
        List<JsonObject> all = c.events("container.click");
        return new ArrayList<>(all.subList(before, all.size()));
    }

    private static String show(List<JsonObject> clicks) {
        List<String> out = new ArrayList<>();
        for (JsonObject e : clicks) {
            out.add(e.get("slot").getAsInt() + ":" + e.get("button").getAsInt() + ":" + e.get("input").getAsString()
                    + "@" + e.get("tick").getAsLong());
        }
        return out.toString();
    }

    private static List<Long> gaps(List<JsonObject> clicks) {
        List<Long> g = new ArrayList<>();
        for (int i = 1; i < clicks.size(); i++) {
            g.add(clicks.get(i).get("tick").getAsLong() - clicks.get(i - 1).get("tick").getAsLong());
        }
        return g;
    }

    static void invsortApply(Session c) throws Exception {
        MenuKit.reset(c);
        InventoryCases.clearInv(c);
        if (!MenuKit.cheat()) {
            boolean root = c.onClient(mc -> {
                var d = net.fabricmc.fabric.impl.command.client.ClientCommandInternals.getActiveDispatcher();
                return d != null && d.getRoot().getChild("invsort") != null;
            });
            Object l = layout(c, "hx", Map.of(0, "EMERALD"));
            boolean started = c.onClient(mc -> (Boolean) Mod.staticCall("invsort.InventorySorterExecutor", "start", l));
            c.ctx().waitTicks(40);
            int sentLegit = clicks(c);
            c.note("legit jar: /invsort registered " + root + ", start " + started + ", clicks " + sentLegit);
            c.check(!root && !started && sentLegit == 0, "the legit jar has the sorter");
            return;
        }
        Path cfgPath = configPath(c, ISC);
        byte[] old = read(cfgPath);
        long mark = LogTap.mark();
        try {
            c.ctx().runOnClient(mc -> {
                Object cfg = Mod.cfg(ISC);
                Mod.call(cfg, "setEnabled", true);
                Mod.call(cfg, "setTicksBetweenMoves", 4);
                Mod.call(cfg, "setRandomExtraTicks", 0);
            });
            c.hx().give(0, "minecraft:diamond");
            c.hx().give(3, "minecraft:iron_ingot");
            c.hx().give(9, "minecraft:emerald");
            c.hx().give(10, "minecraft:gold_ingot");
            c.waitUntil("the four items", mc -> mc.player.getInventory().getItem(10).is(Items.GOLD_INGOT)
                    && mc.player.getInventory().getItem(3).is(Items.IRON_INGOT), 80);
            String dia = id(c, new ItemStack(Items.DIAMOND));
            String eme = id(c, new ItemStack(Items.EMERALD));
            String iron = id(c, new ItemStack(Items.IRON_INGOT));
            String gold = id(c, new ItemStack(Items.GOLD_INGOT));
            Map<Integer, String> want = new LinkedHashMap<>();
            want.put(0, eme);   // hotbar destination: the emerald from slot 9
            want.put(9, dia);
            want.put(10, iron); // main destination: the iron from hotbar 3
            want.put(3, gold);
            List<JsonObject> first = run(c, layout(c, "hx-585", want));
            String inv = c.onClient(mc -> {
                var i = mc.player.getInventory();
                return i.getItem(0).getItem() + "," + i.getItem(3).getItem() + "," + i.getItem(9).getItem() + "," + i.getItem(10).getItem();
            });
            int[] counters = c.onClient(mc -> (int[]) Mod.staticCall("invsort.InventorySorterExecutor", "counters"));
            c.note("ticks 4 / random 0: clicks " + show(first) + "; inventory 0,3,9,10 = " + inv + "; moves/clicks/swaps/pickups "
                    + java.util.Arrays.toString(counters) + "; gaps " + gaps(first));
            c.check(inv.equals("minecraft:emerald,minecraft:gold_ingot,minecraft:diamond,minecraft:iron_ingot"), "inventory " + inv);
            c.check(first.size() == 4, first.size() + " clicks, want 4 (1 swap + 3 pickups)");
            JsonObject s0 = first.get(0);
            c.check(s0.get("input").getAsString().equals("SWAP") && s0.get("slot").getAsInt() == 9 && s0.get("button").getAsInt() == 0,
                    "the hotbar move was not one SWAP on slot 9 with button 0: " + show(first));
            for (int i = 1; i < 4; i++) {
                c.check(first.get(i).get("input").getAsString().equals("PICKUP"), "a main-storage move used " + show(first));
            }
            c.check(first.get(1).get("slot").getAsInt() == 39 && first.get(2).get("slot").getAsInt() == 10
                    && first.get(3).get("slot").getAsInt() == 39, "pickup slots " + show(first));
            c.check(first.stream().allMatch(e -> e.get("containerId").getAsInt() == 0), "not the inventory menu: " + show(first));
            // Server ticks: the client's click can land a tick either side of its own tick, so allow +-1 per gap
            // and require the mean to be 4.
            List<Long> g4 = gaps(first);
            double mean = g4.stream().mapToLong(Long::longValue).average().orElse(0);
            c.check(g4.stream().allMatch(g -> Math.abs(g - 4) <= 1) && Math.abs(mean - 4) <= 0.34,
                    "Ticks Between Moves 4 gave gaps " + g4);

            // Random Extra Ticks: back and forth three times at 2 + 0..4.
            c.ctx().runOnClient(mc -> {
                Object cfg = Mod.cfg(ISC);
                Mod.call(cfg, "setTicksBetweenMoves", 2);
                Mod.call(cfg, "setRandomExtraTicks", 4);
            });
            Map<Integer, String> back = new LinkedHashMap<>();
            back.put(0, dia);
            back.put(9, eme);
            back.put(10, gold);
            back.put(3, iron);
            List<Long> all = new ArrayList<>();
            for (int round = 0; round < 3; round++) {
                List<JsonObject> r1 = run(c, layout(c, "hx-585b", back));
                List<JsonObject> r2 = run(c, layout(c, "hx-585", want));
                all.addAll(gaps(r1));
                all.addAll(gaps(r2));
            }
            long min = all.stream().mapToLong(Long::longValue).min().orElse(-1);
            long max = all.stream().mapToLong(Long::longValue).max().orElse(-1);
            long distinct = all.stream().distinct().count();
            c.note("ticks 2 / random 4: " + all.size() + " gaps " + all + " (min " + min + ", max " + max + ", " + distinct + " distinct)");
            c.check(min >= 1 && max <= 7, "gaps outside 2..6 (+-1 for arrival): " + all);
            c.check(distinct >= 3, "Random Extra Ticks did not vary the gap: " + all);

            // The inventory changing under it stops it.
            c.ctx().runOnClient(mc -> {
                Object cfg = Mod.cfg(ISC);
                Mod.call(cfg, "setTicksBetweenMoves", 15);
                Mod.call(cfg, "setRandomExtraTicks", 0);
            });
            int before = clicks(c);
            Object slow = layout(c, "hx-585c", back);
            c.check(c.onClient(mc -> (Boolean) Mod.staticCall("invsort.InventorySorterExecutor", "start", slow)), "start (slow)");
            c.waitUntil("the first slow click", mc -> clicks(c) > before, 120);
            c.hx().give(20, "minecraft:cobblestone");
            c.waitUntil("the sorter to stop", mc -> !(Boolean) Mod.staticCall("invsort.InventorySorterExecutor", "isRunning"), 60);
            int afterStop = clicks(c) - before;
            c.ctx().waitTicks(40);
            int later = clicks(c) - before;
            List<String> stopLines = LogTap.since(mark).stream().filter(l -> l.contains("[InvSort] Stopped")).toList();
            c.note("inventory changed mid-run: clicks before the stop " + afterStop + ", 40 ticks later " + later + "; " + stopLines);
            c.check(later == afterStop, "it kept clicking after it stopped");
            c.check(stopLines.stream().anyMatch(l -> l.contains("inventory changed")), "no 'inventory changed' stop: " + stopLines);
            c.check(!running(c), "still running");
        } finally {
            if (running(c)) {
                c.ctx().runOnClient(mc -> Mod.staticCall("invsort.InventorySorterExecutor", "requestStop"));
            }
            MenuKit.reset(c);
            restore(c, ISC, cfgPath, old);
            InventoryCases.clearInv(c);
        }
    }
}
