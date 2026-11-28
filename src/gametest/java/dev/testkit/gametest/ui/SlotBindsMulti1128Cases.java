package dev.testkit.gametest.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.testkit.compat.McKeys;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Slot Binds many to one, quicker setup and the Inventory Sorter's editor (slotbinds-multi-1128, killer560 2026-11-28:
 * "slot binds should allow me to set multiple slots to one slot and it is a pain to set up. I should also be able to
 * change what binds i have set for them through invsort").
 * <ul>
 *   <li>1128-ui-slotbinds-many-to-one: with the bind key HELD, real left clicks bind inventory slots 9, 10 and 11 to
 *   hotbar slot 1; the three binds are on disk and survive a reload; a shift-click on each source and on the shared
 *   slot moves the items on the SERVER (the shared slot goes to its first bind, then to the one it swapped with last);
 *   a held-key click unlinks one, a tap removes a slot's binds, and the tab's Clear All Binds needs two presses.</li>
 *   <li>1128-ui-slotbinds-old-file: a file saved before this change ("binds" with both directions of each pair) loads
 *   with its binds and settings, still swaps, takes a second slot on the same hotbar slot, and is saved as "pairs".</li>
 *   <li>1128-ui-slotbinds-invsort-editor: {@code /invsort binds} opens the sorter on its Slot Binds editor; real
 *   clicks on its grid link, unlink and clear binds in Slot Binds' own file, and move nothing in the layout.</li>
 * </ul>
 */
final class SlotBindsMulti1128Cases {

    private static final String SBC = "slotbinds.SlotBindsConfig";
    private static final int KEY_B = 66;

    private SlotBindsMulti1128Cases() {
    }

    /** The binds in memory as sorted "low-high" strings. */
    private static TreeSet<String> pairs(UiCase c) {
        return c.onClient(mc -> {
            TreeSet<String> out = new TreeSet<>();
            for (Object o : (List<?>) Mod.call(Mod.cfg(SBC), "pairs")) {
                int[] p = (int[]) o;
                out.add(Math.min(p[0], p[1]) + "-" + Math.max(p[0], p[1]));
            }
            return out;
        });
    }

    /** The binds in the file on disk as sorted "low-high" strings; "NO-PAIRS-KEY" when the file has no "pairs". */
    private static TreeSet<String> diskPairs(Path p) throws Exception {
        TreeSet<String> out = new TreeSet<>();
        JsonObject obj = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
        if (!obj.has("pairs")) {
            out.add("NO-PAIRS-KEY");
            return out;
        }
        for (var e : obj.getAsJsonArray("pairs")) {
            JsonArray a = e.getAsJsonArray();
            out.add(Math.min(a.get(0).getAsInt(), a.get(1).getAsInt()) + "-" + Math.max(a.get(0).getAsInt(), a.get(1).getAsInt()));
        }
        return out;
    }

    private static TreeSet<String> set(String... s) {
        return new TreeSet<>(List.of(s));
    }

    private static void fresh(UiCase c) {
        c.onClient(mc -> {
            Object cfg = Mod.cfg(SBC);
            Mod.call(cfg, "setEnabled", true);
            Mod.call(cfg, "setBindKey", KEY_B);
            Mod.call(cfg, "clearBinds");
            Mod.call(cfg, "save");
            return null;
        });
    }

    /** The SERVER's copy of inventory index {@code inv}, waited for up to 3 s. */
    private static void serverHas(UiCase c, int inv, Item want, String what) {
        Item got = null;
        for (int i = 0; i < 60; i++) {
            got = c.onClient(mc -> mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID())
                    .getInventory().getItem(inv).getItem());
            if (got == want) {
                return;
            }
            c.ticks(1);
        }
        c.check(false, what + ": the server has " + got + " in inventory slot " + inv + ", want " + want);
    }

    /** A shift + left click where the cursor is (TestInput sends no modifiers, so through MouseHandler.onButton). */
    private static void shiftClick(UiCase c, int[] pos) {
        ItemUiCases.hoverGui(c, pos[0] + 8, pos[1] + 8);
        c.onClient(mc -> {
            long window = mc.getWindow().handle();
            Mod.call(mc.mouseHandler, "onButton", window, new net.minecraft.client.input.MouseButtonInfo(McKeys.mouse(0), 1), 1);
            Mod.call(mc.mouseHandler, "onButton", window, new net.minecraft.client.input.MouseButtonInfo(McKeys.mouse(0), 1), 0);
            return null;
        });
        c.ticks(5);
    }

    private static void clickAt(UiCase c, int[] pos) {
        ItemUiCases.hoverGui(c, pos[0] + 8, pos[1] + 8);
        c.ctx().getInput().pressMouse(McKeys.mouse(0));
        c.ticks(2);
    }

    private static Map<Integer, ItemStack> items() {
        Map<Integer, ItemStack> items = new LinkedHashMap<>();
        items.put(0, new ItemStack(Items.IRON_INGOT));
        items.put(9, new ItemStack(Items.DIAMOND));
        items.put(10, new ItemStack(Items.EMERALD));
        items.put(11, new ItemStack(Items.GOLD_INGOT));
        return items;
    }

    // ==== 1128-ui-slotbinds-many-to-one ================================================================================

    static void manyToOne(UiCase c) throws Exception {
        Path p = InvBatchCases.configPath(c, SBC);
        byte[] old = InvBatchCases.read(p);
        try {
            InvBatchCases.survival(c);
            InvBatchCases.inventory(c, items());
            fresh(c);
            ItemUiCases.openInventory(c);
            int[] s9 = ItemUiCases.invSlotPos(c, 9);
            int[] s10 = ItemUiCases.invSlotPos(c, 10);
            int[] s11 = ItemUiCases.invSlotPos(c, 11);
            int[] h0 = ItemUiCases.invSlotPos(c, 0);
            c.check(s9[2] == 9 && s10[2] == 10 && s11[2] == 11 && h0[2] == 36, "unexpected menu slots");

            // Setup: hold the key, click the source, click the target, then two more sources (the target stays selected).
            ItemUiCases.hoverGui(c, s9[0] + 8, s9[1] + 8);
            c.ctx().getInput().holdKey(McKeys.nat(KEY_B));
            c.ticks(2);
            clickAt(c, s9);
            c.check(pairs(c).isEmpty(), "one setup click already made a bind: " + pairs(c));
            clickAt(c, h0);
            TreeSet<String> afterFirst = pairs(c);
            clickAt(c, s10);
            clickAt(c, s11);
            c.ctx().getInput().releaseKey(McKeys.nat(KEY_B));
            c.ticks(3);
            TreeSet<String> made = pairs(c);
            TreeSet<String> onDisk = diskPairs(p);
            boolean carried = c.onClient(mc -> !mc.player.containerMenu.getCarried().isEmpty());
            c.note("held key + clicks 9, hotbar 1, 10, 11: after the second click " + afterFirst + ", at release " + made
                    + ", on disk " + onDisk);
            c.check(afterFirst.equals(set("9-36")), "source then target did not bind 9-36: " + afterFirst);
            c.check(made.equals(set("9-36", "10-36", "11-36")), "three slots are not all bound to hotbar 1: " + made);
            c.check(onDisk.equals(made), "the file holds " + onDisk);
            c.check(!carried, "a setup click picked an item up");
            serverHas(c, 9, Items.DIAMOND, "setup clicks moved nothing");
            serverHas(c, 0, Items.IRON_INGOT, "setup clicks moved nothing");

            // Reload from disk: all three are still there.
            c.onClient(mc -> {
                Mod.staticCall(SBC, "load");
                return null;
            });
            TreeSet<String> reloaded = pairs(c);
            c.check(made.equals(reloaded), "after a reload the binds are " + reloaded);

            // Swaps, each checked on the server. The shared slot first goes to its first bind (9).
            shiftClick(c, h0);
            serverHas(c, 0, Items.DIAMOND, "shift-click on the shared slot, nothing swapped yet");
            serverHas(c, 9, Items.IRON_INGOT, "shift-click on the shared slot, nothing swapped yet");
            shiftClick(c, s10);
            serverHas(c, 0, Items.EMERALD, "shift-click on source 10");
            serverHas(c, 10, Items.DIAMOND, "shift-click on source 10");
            // The shared slot now goes back to the slot it swapped with last (10), not to its first bind.
            shiftClick(c, h0);
            serverHas(c, 0, Items.DIAMOND, "shift-click on the shared slot after source 10");
            serverHas(c, 10, Items.EMERALD, "shift-click on the shared slot after source 10");
            serverHas(c, 9, Items.IRON_INGOT, "the first bind's slot was left alone");
            shiftClick(c, s11);
            serverHas(c, 0, Items.GOLD_INGOT, "shift-click on source 11");
            serverHas(c, 11, Items.DIAMOND, "shift-click on source 11");
            shiftClick(c, s9);
            serverHas(c, 0, Items.IRON_INGOT, "shift-click on source 9");
            serverHas(c, 9, Items.GOLD_INGOT, "shift-click on source 9");
            c.note("swaps on the server: shared->9, 10, shared->10, 11, 9 all moved the items");

            // Unlink one with the held key: select the shared slot, click a linked slot.
            ItemUiCases.hoverGui(c, h0[0] + 8, h0[1] + 8);
            c.ctx().getInput().holdKey(McKeys.nat(KEY_B));
            c.ticks(2);
            clickAt(c, h0);
            clickAt(c, s10);
            c.ctx().getInput().releaseKey(McKeys.nat(KEY_B));
            c.ticks(3);
            TreeSet<String> unlinked = pairs(c);
            c.check(unlinked.equals(set("9-36", "11-36")), "held key + shared slot + slot 10 left " + unlinked);
            c.check(diskPairs(p).equals(unlinked), "the file after the unlink holds " + diskPairs(p));

            // A tap of the key over a source removes that source's bind only.
            ItemUiCases.hoverGui(c, s9[0] + 8, s9[1] + 8);
            c.ctx().getInput().pressKey(McKeys.nat(KEY_B));
            c.ticks(3);
            TreeSet<String> tapped = pairs(c);
            c.check(tapped.equals(set("11-36")), "a tap over slot 9 left " + tapped);
            ItemUiCases.closeScreen(c);

            // The tab: Clear All Binds shows the count, the first press only arms, the second clears.
            c.onClient(mc -> {
                Mod.call(Mod.cfg(SBC), "addBind", 12, 36);
                return null;
            });
            Object tab = c.onClient(mc -> {
                try {
                    return Mod.cls("gui.tab.SlotBindsTab").getConstructor().newInstance();
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            List<String> seen = new ArrayList<>();
            for (int press = 0; press < 2; press++) {
                TreeSet<String> before = pairs(c);
                String label = c.onClient(mc -> {
                    for (Object w : (List<?>) Mod.call(tab, "buildWidgets", 0, 0, 320, (Runnable) () -> { })) {
                        String text = ModScreenDriver.label((AbstractWidget) w);
                        if (text.startsWith("Clear All Binds") || text.equals("Confirm")) {
                            Mod.call(w, "press");
                            return text;
                        }
                    }
                    return "(no clear row)";
                });
                seen.add(label + " with " + before.size() + " binds");
            }
            TreeSet<String> cleared = pairs(c);
            c.note("Clear All Binds presses: " + seen + "; left " + cleared + ", on disk " + diskPairs(p));
            c.check(seen.get(0).startsWith("Clear All Binds: 2 with 2"), "first press: " + seen.get(0));
            c.check(seen.get(1).startsWith("Confirm with 2"), "the first press cleared or did not arm: " + seen.get(1));
            c.check(cleared.isEmpty() && diskPairs(p).isEmpty(), "the confirming press left " + cleared);
        } finally {
            c.ctx().getInput().releaseKey(McKeys.nat(KEY_B));
            ItemUiCases.closeScreen(c);
            InvBatchCases.restoreConfig(c, SBC, p, old);
        }
    }

    // ==== 1128-ui-slotbinds-old-file ===================================================================================

    static void oldFile(UiCase c) throws Exception {
        Path p = InvBatchCases.configPath(c, SBC);
        byte[] old = InvBatchCases.read(p);
        try {
            InvBatchCases.survival(c);
            InvBatchCases.inventory(c, items());
            // Exactly what the mod wrote before slotbinds-multi-1128, plus one broken entry.
            String before = "{\n  \"enabled\": true,\n  \"bindKey\": 66,\n  \"overlayMode\": \"ON_HOVER\",\n"
                    + "  \"overlayColor\": -16711936,\n  \"binds\": {\n    \"9\": 36,\n    \"36\": 9,\n    \"12\": 40,\n"
                    + "    \"40\": 12,\n    \"zz\": 3\n  }\n}";
            Files.createDirectories(p.getParent());
            Files.writeString(p, before, StandardCharsets.UTF_8);
            String read = c.onClient(mc -> {
                Mod.staticCall(SBC, "load");
                Object cfg = Mod.cfg(SBC);
                return Mod.field(cfg, "enabled") + " key " + Mod.call(cfg, "getBindKey") + " " + Mod.call(cfg, "getOverlayMode")
                        + " color " + Mod.call(cfg, "getOverlayColor");
            });
            TreeSet<String> loaded = pairs(c);
            c.note("old file loads as " + loaded + ", settings " + read);
            c.check(loaded.equals(set("9-36", "12-40")), "the old file's binds loaded as " + loaded);
            c.check(read.equals("true key 66 ON_HOVER color -16711936"), "the old file's settings loaded as " + read);
            c.check(Files.readString(p, StandardCharsets.UTF_8).equals(before), "loading rewrote the file");

            // The loaded bind still swaps.
            ItemUiCases.openInventory(c);
            shiftClick(c, ItemUiCases.invSlotPos(c, 9));
            serverHas(c, 0, Items.DIAMOND, "shift-click on a bind from the old file");
            serverHas(c, 9, Items.IRON_INGOT, "shift-click on a bind from the old file");
            ItemUiCases.closeScreen(c);

            // A second slot on the same hotbar slot is ADDED (before: it replaced 9-36), and the save is the new shape.
            boolean added = c.onClient(mc -> {
                Object cfg = Mod.cfg(SBC);
                boolean ok = (Boolean) Mod.call(cfg, "addBind", 10, 36);
                Mod.call(cfg, "save");
                return ok;
            });
            TreeSet<String> onDisk = diskPairs(p);
            String text = Files.readString(p, StandardCharsets.UTF_8);
            c.onClient(mc -> {
                Mod.staticCall(SBC, "load");
                return null;
            });
            TreeSet<String> again = pairs(c);
            c.note("after adding 10-36 and saving: disk " + onDisk + ", reloaded " + again);
            c.check(added, "addBind(10, 36) was refused beside 9-36");
            c.check(onDisk.equals(set("9-36", "10-36", "12-40")), "saved pairs " + onDisk);
            c.check(!text.contains("\"binds\""), "the saved file still has the old \"binds\" key");
            c.check(again.equals(onDisk), "reloaded " + again);
        } finally {
            ItemUiCases.closeScreen(c);
            InvBatchCases.restoreConfig(c, SBC, p, old);
        }
    }

    // ==== 1128-ui-slotbinds-invsort-editor =============================================================================

    static void sorterEditor(UiCase c) throws Exception {
        if (!Mod.isCheat()) {
            boolean root = c.onClient(mc -> {
                var d = net.fabricmc.fabric.impl.command.client.ClientCommandInternals.getActiveDispatcher();
                return d != null && d.getRoot().getChild("invsort") != null;
            });
            c.note("legit jar: /invsort registered " + root + " (the editor is reached through it)");
            c.check(!root, "the legit jar registers /invsort");
            return;
        }
        Path p = InvBatchCases.configPath(c, SBC);
        byte[] old = InvBatchCases.read(p);
        int[] window = HudEditorCases.windowSize(c);
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        try {
            InvBatchCases.survival(c);
            InvBatchCases.inventory(c, items());
            fresh(c);
            HudEditorCases.setWindow(c, 1280, 720, 2);
            c.onClient(mc -> {
                mc.player.connection.sendCommand("invsort binds");
                return null;
            });
            InvBatchCases.awaitScreen(c, "InventorySorterScreen");
            Screen s = InvBatchCases.screen(c);
            c.check((Boolean) c.onClient(mc -> Mod.call(s, "editingSlotBinds")), "/invsort binds did not open the Slot Binds editor");
            int[] p0 = (int[]) c.onClient(mc -> Mod.call(s, "slotPos", 0));
            int[] p9 = (int[]) c.onClient(mc -> Mod.call(s, "slotPos", 9));
            int[] p10 = (int[]) c.onClient(mc -> Mod.call(s, "slotPos", 10));

            // Source, target, a second source: two binds on hotbar 1, in Slot Binds' file.
            InvBatchCases.click(c, s, p9[0] + 9, p9[1] + 9);
            InvBatchCases.click(c, s, p0[0] + 9, p0[1] + 9);
            InvBatchCases.click(c, s, p10[0] + 9, p10[1] + 9);
            TreeSet<String> made = pairs(c);
            c.note("editor clicks 9, hotbar 1, 10: " + made + ", on disk " + diskPairs(p) + ", status '"
                    + c.onClient(mc -> Mod.call(s, "status")) + "'");
            c.check(made.equals(set("9-36", "10-36")), "the editor's clicks made " + made);
            c.check(diskPairs(p).equals(made), "Slot Binds' file holds " + diskPairs(p));
            // The layout grid is untouched: nothing picked up, the items where they were.
            String grid = c.onClient(mc -> Mod.field(s, "held") + " " + ((ItemStack) Mod.call(s, "gridAt", 9)).getItem() + " "
                    + ((ItemStack) Mod.call(s, "gridAt", 0)).getItem());
            c.check(grid.equals("-1 " + Items.DIAMOND + " " + Items.IRON_INGOT), "the editor moved layout items: " + grid);

            // Right click a slot: its binds go.
            InvBatchCases.cursor(c, s, p10[0] + 9, p10[1] + 9);
            c.ctx().getInput().pressMouse(McKeys.mouse(1));
            c.ticks(3);
            TreeSet<String> afterRight = pairs(c);
            c.check(afterRight.equals(set("9-36")), "a right click on slot 10 left " + afterRight);
            c.check(diskPairs(p).equals(afterRight), "the file after the right click holds " + diskPairs(p));

            // Select the target, click a linked slot: unlinked; click it again: linked.
            InvBatchCases.click(c, s, p0[0] + 9, p0[1] + 9);
            InvBatchCases.click(c, s, p9[0] + 9, p9[1] + 9);
            TreeSet<String> toggledOff = pairs(c);
            InvBatchCases.click(c, s, p9[0] + 9, p9[1] + 9);
            InvBatchCases.click(c, s, p10[0] + 9, p10[1] + 9);
            TreeSet<String> toggledOn = pairs(c);
            c.check(toggledOff.isEmpty(), "a click on a linked slot did not unlink it: " + toggledOff);
            c.check(toggledOn.equals(set("9-36", "10-36")), "the target did not stay selected for more slots: " + toggledOn);

            // Clear Binds: the first click arms, the second clears, on disk too.
            AbstractWidget clear = InvBatchCases.widget(c, s, "Clear Binds");
            InvBatchCases.clickWidget(c, s, clear);
            TreeSet<String> armed = pairs(c);
            String armedLabel = c.onClient(mc -> ModScreenDriver.label(clear));
            InvBatchCases.clickWidget(c, s, clear);
            TreeSet<String> cleared = pairs(c);
            c.note("Clear Binds: after click 1 " + armed + " label '" + armedLabel + "', after click 2 " + cleared
                    + ", on disk " + diskPairs(p));
            c.check(armed.equals(toggledOn) && armedLabel.equals("Confirm"), "the first click did not just arm: " + armed
                    + " '" + armedLabel + "'");
            c.check(cleared.isEmpty() && diskPairs(p).isEmpty(), "the confirming click left " + cleared);

            // Editor off: a grid click is the layout editor's again.
            InvBatchCases.clickWidget(c, s, InvBatchCases.widget(c, s, "Slot Binds"));
            InvBatchCases.click(c, s, p9[0] + 9, p9[1] + 9);
            String off = c.onClient(mc -> Mod.call(s, "editingSlotBinds") + " held " + Mod.field(s, "held"));
            c.check(off.equals("false held 9"), "with the editor off a click did: " + off);
            c.check(pairs(c).isEmpty(), "with the editor off a click made a bind: " + pairs(c));
        } finally {
            ItemUiCases.closeScreen(c);
            HudEditorCases.restoreWindow(c, window, oldGui);
            InvBatchCases.restoreConfig(c, SBC, p, old);
        }
    }
}
