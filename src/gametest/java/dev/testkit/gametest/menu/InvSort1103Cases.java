package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;
import dev.testkit.compat.McCompat;
import dev.testkit.compat.McKeys;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Inventory Sorter, killer560's 2026-11-02/03 requests (mod invsort-1103), on the menu session's GrimAC server:
 * <ul>
 *   <li>1103-invsort-exact-sort / -exact-fetch: two runebooks (same Skyblock id, different enchantments). An id-only
 *   layout (the old rule, kept as the control) takes the first one; a layout saved with the exact key takes ITS book, both
 *   when sorting and when fetching from a storage page ("fetching a layout from storage grabbed the WRONG runebook").</li>
 *   <li>1103-invsort-free: a Free slot is never claimed or moved (control: the same layout without it moves the item);
 *   the editor's right click makes a slot Free, Reset puts it back, a saved Free slot survives a reload, and an old file
 *   (no "free", no "exact", no "fetchFromStorage") loads with none and Fetch From Storage ON.</li>
 *   <li>1103-invsort-dump-pick: Put Others In sends /storage, the page he opens becomes the layout's page and the menu comes
 *   back; Escape changes nothing; right click clears it; no container click goes out.</li>
 * </ul>
 * Every case asserts the sorter (or the pick) ACTED before it quotes a result, and runs on GrimAC with experimental checks.
 */
final class InvSort1103Cases {

    private static final String ISC = "invsort.InventorySorterConfig";
    private static final String EXEC = "invsort.InventorySorterExecutor";
    private static final String STORE = "invsort.InventoryLayoutStore";
    private static final String SCREEN = "invsort.InventorySorterScreen";
    private static final String BOOK_A = "minecraft:enchanted_book[custom_data={id:\"RUNEBOOK\",enchantments:{sharpness:5}}]";
    private static final String BOOK_B = "minecraft:enchanted_book[custom_data={id:\"RUNEBOOK\",enchantments:{smite:6}}]";

    private InvSort1103Cases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1103-invsort-exact-sort", InvSort1103Cases::exactSort);
        MenuSuite.test(s, "1103-invsort-exact-fetch", InvSort1103Cases::exactFetch);
        MenuSuite.test(s, "1103-invsort-free", InvSort1103Cases::free);
        MenuSuite.test(s, "1103-invsort-dump-pick", InvSort1103Cases::dumpPick);
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

    private static void pace(Session c) {
        cfg(c, "setTicksBetweenMoves", 2);
        cfg(c, "setRandomExtraTicks", 0);
        cfg(c, "setInstant", false);
    }

    private static String exact(Session c, int slot) {
        return c.onClient(mc -> (String) Mod.staticCall("invsort.InvSortMatch", "exact", mc.player.getInventory().getItem(slot)));
    }

    private static String id(Session c, Item item) {
        return c.onClient(mc -> (String) Mod.staticCall("autoroutes.ItemIdentity", "of", new ItemStack(item)));
    }

    /** The full constructor: slots, icons, exact keys, Free slots, fetch, dump page. */
    private static Object layout(Session c, String name, Map<Integer, String> slots, Map<Integer, String> exact,
                                 Set<Integer> free, boolean fetch, String dump) {
        return c.onClient(mc -> {
            try {
                return Mod.cls("invsort.InventoryLayout").getConstructor(String.class, Map.class, Map.class, Map.class,
                        Set.class, boolean.class, String.class).newInstance(name, slots, Map.of(), exact, free, fetch, dump);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
    }

    private static Object store() {
        return Mod.staticCall(STORE, "getInstance");
    }

    private static boolean running(Session c) {
        return c.onClient(mc -> (Boolean) Mod.staticCall(EXEC, "isRunning"));
    }

    private static void run(Session c, Object layout, int ticks) {
        c.check(c.onClient(mc -> (Boolean) Mod.staticCall(EXEC, "start", layout)), "start returned false");
        c.waitUntil("the sorter to finish", mc -> !(Boolean) Mod.staticCall(EXEC, "isRunning"), ticks);
        c.ctx().waitTicks(10);
    }

    private static List<JsonObject> clicksSince(Session c, int before) {
        List<JsonObject> all = c.events("container.click");
        return new ArrayList<>(all.subList(before, all.size()));
    }

    private static String show(List<JsonObject> clicks) {
        List<String> out = new ArrayList<>();
        for (JsonObject e : clicks) {
            out.add(e.get("slot").getAsInt() + ":" + e.get("input").getAsString() + "@" + e.get("containerId").getAsInt());
        }
        return out.toString();
    }

    private static void checkStill(Session c, List<JsonObject> clicks) {
        for (JsonObject e : clicks) {
            c.check(e.get("serverInput").getAsString().isEmpty() && !e.get("sprinting").getAsBoolean()
                    && !e.get("shift").getAsBoolean(), "a click went out with the server holding movement: " + show(List.of(e)));
        }
    }

    private static void finishRun(Session c) {
        if (running(c)) {
            c.ctx().runOnClient(mc -> Mod.staticCall(EXEC, "requestStop"));
        }
    }

    private static boolean legit(Session c) {
        if (!MenuKit.cheat()) {
            boolean has = Mod.has("invsort.InvSortMatch");
            c.note("legit jar: the sorter is cheat-only (InvSortMatch class present " + has + ", start compiled out)");
            return true;
        }
        return false;
    }

    private static void giveBooks(Session c, int slotA, int slotB) {
        c.hx().give(slotA, BOOK_A);
        c.hx().give(slotB, BOOK_B);
        c.waitUntil("the two runebooks", mc -> mc.player.getInventory().getItem(slotA).is(Items.ENCHANTED_BOOK)
                && mc.player.getInventory().getItem(slotB).is(Items.ENCHANTED_BOOK), 80);
    }

    private static ItemStack book(String enchant, int level) {
        ItemStack s = new ItemStack(Items.ENCHANTED_BOOK);
        CompoundTag t = new CompoundTag();
        t.putString("id", "RUNEBOOK");
        CompoundTag ench = new CompoundTag();
        ench.putInt(enchant, level);
        t.put("enchantments", ench);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
        return s;
    }

    // ------------------------------------------------------------------------------------------------- cases

    /**
     * Book A in slot 3, book B in slot 4, the layout wants a runebook in slot 0. Control: id-only, the planner takes the
     * first copy (A). Exact (saved with B's key): B goes to slot 0 and A stays in slot 3.
     */
    static void exactSort(Session c) throws Exception {
        MenuKit.reset(c);
        InventoryCases.clearInv(c);
        if (legit(c)) {
            return;
        }
        Path p = cfgPath(c);
        byte[] old = read(p);
        try {
            pace(c);
            giveBooks(c, 3, 4);
            String a = exact(c, 3);
            String b = exact(c, 4);
            c.note("exact keys: A " + a + ", B " + b);
            c.check(a != null && b != null && !a.equals(b) && a.startsWith("RUNEBOOK|D:") && b.startsWith("RUNEBOOK|D:"),
                    "the two books do not have two exact keys: " + a + " / " + b);

            // Control: the old id-only slot.
            int before = c.events("container.click").size();
            run(c, layout(c, "hx-1103-idonly", Map.of(0, "RUNEBOOK"), Map.of(), Set.of(), false, null), 300);
            String gotOld = exact(c, 0);
            List<JsonObject> oldClicks = clicksSince(c, before);
            c.note("id-only layout: slot 0 now " + gotOld + " (clicks " + show(oldClicks) + ")");
            c.check(!oldClicks.isEmpty() && a.equals(gotOld), "control: the id-only layout did not take the first copy (A)"
                    + " - the case would not show the fix");

            // Back to A in 3, B in 4.
            InventoryCases.clearInv(c);
            giveBooks(c, 3, 4);
            before = c.events("container.click").size();
            run(c, layout(c, "hx-1103-exact", Map.of(0, "RUNEBOOK"), Map.of(0, b), Set.of(), false, null), 300);
            List<JsonObject> clicks = clicksSince(c, before);
            String got0 = exact(c, 0);
            String got3 = exact(c, 3);
            String got4 = exact(c, 4);
            c.note("exact layout (B): slot 0 " + got0 + ", 3 " + got3 + ", 4 " + got4 + "; clicks " + show(clicks));
            c.check(!clicks.isEmpty(), "the exact layout sent no click");
            c.check(b.equals(got0) && a.equals(got3) && got4 == null, "slot 0 does not hold the layout's own book");
            checkStill(c, clicks);
        } finally {
            finishRun(c);
            MenuKit.reset(c);
            restore(c, p, old);
            InventoryCases.clearInv(c);
        }
    }

    /** Fetch: page 1 holds A (page slot 12) then B (13); the layout wants B in slot 2. Control id-only takes A. */
    static void exactFetch(Session c) throws Exception {
        MenuKit.reset(c);
        InventoryCases.clearInv(c);
        if (legit(c)) {
            return;
        }
        Path p = cfgPath(c);
        byte[] old = read(p);
        String key = c.onClient(mc -> (String) Mod.staticCall("storageoverlay.StorageOverlayFeature", "storageKeyForTitle",
                "Ender Chest (1/9)"));
        try {
            pace(c);
            c.hx().stub(null, "enderchest", "backpack");
            String a = c.onClient(mc -> (String) Mod.staticCall("invsort.InvSortMatch", "exact", book("sharpness", 5)));
            String b = c.onClient(mc -> (String) Mod.staticCall("invsort.InvSortMatch", "exact", book("smite", 6)));
            String[] took = new String[2];
            for (int pass = 0; pass < 2; pass++) {
                InventoryCases.clearInv(c);
                c.hx().give(0, "minecraft:emerald");
                c.waitUntil("the emerald", mc -> mc.player.getInventory().getItem(0).is(Items.EMERALD), 60);
                c.ctx().runOnClient(mc -> {
                    List<ItemStack> page = new ArrayList<>();
                    for (int i = 0; i < 45; i++) {
                        page.add(i == 3 ? book("sharpness", 5) : i == 4 ? book("smite", 6) : ItemStack.EMPTY);
                    }
                    Mod.call(Mod.staticCall("storageoverlay.StorageOverlayCache", "getInstance"), "put", key, page);
                });
                boolean exactPass = pass == 1;
                Object l = layout(c, exactPass ? "hx-1103-fetch-exact" : "hx-1103-fetch-id", Map.of(0, "EMERALD", 2, "RUNEBOOK"),
                        exactPass ? Map.of(2, b) : Map.of(), Set.of(), true, null);
                long since = c.hx().head();
                int before = c.events("container.click").size();
                c.check(c.onClient(mc -> (Boolean) Mod.staticCall(EXEC, "start", l)), "start returned false");
                boolean opened = false;
                for (int i = 0; i < 80 && !opened; i++) {
                    c.ctx().waitTicks(1);
                    if (c.hx().commands(since).stream().anyMatch(cmd -> cmd.startsWith("enderchest 1"))) {
                        c.hx().menu("Ender Chest (1/9)", 6, Map.of(0, "minecraft:black_stained_glass_pane", 12, BOOK_A,
                                13, BOOK_B), "storage");
                        opened = true;
                    }
                }
                c.check(opened, "the sorter never sent /enderchest 1; commands " + c.hx().commands(since));
                c.waitUntil("the sorter to finish", mc -> !(Boolean) Mod.staticCall(EXEC, "isRunning"), 600);
                c.ctx().waitTicks(10);
                List<JsonObject> clicks = clicksSince(c, before);
                List<JsonObject> shift = clicks.stream().filter(e -> e.get("input").getAsString().equals("QUICK_MOVE")).toList();
                took[pass] = exact(c, 2);
                c.note((exactPass ? "exact" : "id-only") + " layout: shift-clicks " + show(shift) + ", slot 2 now " + took[pass]);
                c.check(shift.size() == 1, "want one shift-click out of the page: " + show(clicks));
                c.check(shift.size() != 1 || shift.get(0).get("slot").getAsInt() == (exactPass ? 13 : 12),
                        (exactPass ? "the exact layout" : "the id-only control") + " took page slot "
                                + (shift.isEmpty() ? -1 : shift.get(0).get("slot").getAsInt()));
                checkStill(c, clicks);
            }
            c.check(a.equals(took[0]), "control: the id-only layout did not fetch the first book (A): " + took[0]);
            c.check(b.equals(took[1]), "the exact layout did not fetch its own book (B): " + took[1]);
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

    /**
     * Diamond in 0, emerald in 1, stick in 5; the layout wants 0 = emerald, 1 = diamond, 2 = stick. Control (no Free
     * slot): the stick moves 5 -&gt; 2. With slot 5 Free: 0 and 1 swap, the stick stays in 5, no click touches slot 5.
     * Then the editor and the file format.
     */
    static void free(Session c) throws Exception {
        MenuKit.reset(c);
        InventoryCases.clearInv(c);
        if (legit(c)) {
            return;
        }
        Path p = cfgPath(c);
        byte[] old = read(p);
        String[] names = {"hx-1103-free", "hx-1103-old"};
        try {
            pace(c);
            Map<Integer, String> want = new LinkedHashMap<>();
            want.put(0, id(c, Items.EMERALD));
            want.put(1, id(c, Items.DIAMOND));
            want.put(2, id(c, Items.STICK));
            for (int pass = 0; pass < 2; pass++) {
                boolean freePass = pass == 1;
                InventoryCases.clearInv(c);
                c.hx().give(0, "minecraft:diamond");
                c.hx().give(1, "minecraft:emerald");
                c.hx().give(5, "minecraft:stick");
                c.waitUntil("the test items", mc -> mc.player.getInventory().getItem(5).is(Items.STICK)
                        && mc.player.getInventory().getItem(0).is(Items.DIAMOND), 80);
                int before = c.events("container.click").size();
                run(c, layout(c, "hx-1103-free-run", want, Map.of(), freePass ? Set.of(5) : Set.of(), false, null), 300);
                List<JsonObject> clicks = clicksSince(c, before);
                Map<Integer, Item> m = c.onClient(mc -> {
                    Map<Integer, Item> out = new LinkedHashMap<>();
                    for (int s : new int[]{0, 1, 2, 5}) {
                        out.put(s, mc.player.getInventory().getItem(s).getItem());
                    }
                    return out;
                });
                c.note((freePass ? "slot 5 Free" : "control, no Free slot") + ": inventory " + m + ", clicks " + show(clicks));
                c.check(!clicks.isEmpty(), "no clicks");
                c.check(m.get(0) == Items.EMERALD && m.get(1) == Items.DIAMOND, "0/1 not sorted: " + m);
                if (freePass) {
                    c.check(m.get(5) == Items.STICK && m.get(2) == Items.AIR, "the Free slot's stick was moved: " + m);
                    // Container slot of inventory index 5 is 5 + 36 = 41 (hotbar).
                    c.check(clicks.stream().noneMatch(e -> e.get("slot").getAsInt() == 41), "a click touched the Free slot");
                } else {
                    c.check(m.get(2) == Items.STICK && m.get(5) == Items.AIR, "control: the stick did not move 5 -> 2: " + m);
                }
                checkStill(c, clicks);
            }

            // The editor: right click makes slot 5 Free, the saved layout keeps it, Reset clears it.
            Object[] ed = c.onClient(mc -> {
                try {
                    Screen s = (Screen) Mod.cls(SCREEN).getConstructor(Screen.class).newInstance((Object) null);
                    McCompat.setScreen(mc, s);
                    return new Object[]{s};
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            c.waitUntil("the layouts menu", mc -> McCompat.screen(mc) != null
                    && McCompat.screen(mc).getClass().getSimpleName().equals("InventorySorterScreen"), 40);
            String editor = c.onClient(mc -> {
                Screen s = McCompat.screen(mc);
                Mod.call(s, "resetToInventory");
                int[] at = (int[]) Mod.call(s, "slotPos", 5);
                s.mouseClicked(ev(at[0] + 4, at[1] + 4, 1), false);
                s.mouseReleased(ev(at[0] + 4, at[1] + 4, 1));
                boolean freed = (Boolean) Mod.call(s, "isFreeSlot", 5);
                // A left click on a Free slot does not pick its item up.
                s.mouseClicked(ev(at[0] + 4, at[1] + 4, 0), false);
                s.mouseReleased(ev(at[0] + 4, at[1] + 4, 0));
                int held = (Integer) Mod.field(s, "held");
                Object l = Mod.call(s, "toLayout", names[0]);
                String freeSet = String.valueOf(Mod.call(l, "freeSlots"));
                String entries = String.valueOf(Mod.call(l, "entries"));
                String exactKeys = String.valueOf(Mod.call(l, "exactKeys"));
                Mod.call(store(), "save", Mod.call(l, "withStorage", true, null));
                Mod.call(s, "resetToInventory");
                boolean afterReset = (Boolean) Mod.call(s, "isFreeSlot", 5);
                return "freed " + freed + ", held after left click " + held + ", layout free " + freeSet + " entries "
                        + entries + " exact " + exactKeys + ", Free after Reset " + afterReset;
            });
            c.note("editor: " + editor);
            c.check(editor.startsWith("freed true, held after left click -1, layout free [5]"), "editor: " + editor);
            c.check(!editor.contains("5=") && editor.endsWith("Free after Reset false"), "editor: " + editor);
            c.check(editor.contains("exact {") && !editor.contains("exact {}"), "the saved layout has no exact keys: " + editor);

            // Reload from disk: Free survives; an old file (no free/exact/fetchFromStorage) loads with none and fetch ON.
            Path dir = c.onClient(mc -> (Path) Mod.staticCall(STORE, "directory"));
            Files.writeString(dir.resolve(names[1] + ".json"), "{\"version\":1,\"name\":\"" + names[1]
                    + "\",\"slots\":{\"0\":\"EMERALD\"}}");
            Files.writeString(dir.resolve("hx-1103-off.json"), "{\"version\":1,\"name\":\"hx-1103-off\",\"slots\":{\"0\":"
                    + "\"EMERALD\"},\"fetchFromStorage\":false}");
            String reload = c.onClient(mc -> {
                Mod.staticCall(STORE, "reload");
                Object f = Mod.call(store(), "get", names[0]);
                Object o = Mod.call(store(), "get", names[1]);
                Object off = Mod.call(store(), "get", "hx-1103-off");
                return "free " + Mod.call(f, "freeSlots") + "; old: free " + Mod.call(o, "freeSlots") + " exact "
                        + Mod.call(o, "exactKeys") + " fetch " + Mod.call(o, "fetchFromStorage") + "; explicit off: fetch "
                        + Mod.call(off, "fetchFromStorage");
            });
            c.note("after reload: " + reload);
            c.check(reload.equals("free [5]; old: free [] exact {} fetch true; explicit off: fetch false"), "reload: " + reload);
        } finally {
            c.ctx().runOnClient(mc -> McCompat.setScreen(mc, null));
            finishRun(c);
            c.ctx().runOnClient(mc -> {
                for (String n : new String[]{names[0], names[1], "hx-1103-off"}) {
                    Mod.call(store(), "delete", n);
                }
            });
            MenuKit.reset(c);
            restore(c, p, old);
            InventoryCases.clearInv(c);
        }
    }

    /** Put Others In: /storage, he opens Backpack 3, the layout's page is backpack_3; Escape cancels; right click None. */
    static void dumpPick(Session c) throws Exception {
        MenuKit.reset(c);
        InventoryCases.clearInv(c);
        if (legit(c)) {
            return;
        }
        Path p = cfgPath(c);
        byte[] old = read(p);
        String name = "hx-1103-pick";
        try {
            cfg(c, "setEnabled", true);
            c.hx().stub(null, "storage", "enderchest", "backpack");
            Object pickLayout = layout(c, name, Map.of(0, "EMERALD"), Map.of(), Set.of(), true, null);
            c.ctx().runOnClient(mc -> Mod.call(store(), "save", pickLayout));
            int clicksBefore = c.events("container.click").size();
            for (int pass = 0; pass < 2; pass++) {
                boolean cancel = pass == 1;
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
                long since = c.hx().head();
                c.ctx().runOnClient(mc -> {
                    Screen s = McCompat.screen(mc);
                    Mod.call(s, "select", name);
                    Mod.call(s, "pickDumpPage");
                });
                boolean sent = false;
                for (int i = 0; i < 60 && !sent; i++) {
                    c.ctx().waitTicks(1);
                    sent = c.hx().commands(since).stream().anyMatch(cmd -> cmd.equals("storage"));
                }
                String picking = c.onClient(mc -> (String) Mod.staticCall("invsort.InvSortPagePick", "pickingFor"));
                c.note((cancel ? "cancel pass" : "pick pass") + ": /storage sent " + sent + ", picking for " + picking
                        + ", screen " + c.onClient(mc -> McCompat.screen(mc) == null ? "-" : McCompat.screen(mc).getClass().getSimpleName()));
                c.check(sent && name.equals(picking), "Put Others In did not open /storage: commands " + c.hx().commands(since));
                c.hx().menu("Storage", 6, Map.of(9, "minecraft:ender_chest", 29, "minecraft:chest"), null);
                c.waitUntil("the storage menu", mc -> McCompat.screen(mc) != null
                        && McCompat.screen(mc).getTitle().getString().equals("Storage"), 60);
                c.ctx().waitTicks(5);
                if (!cancel) {
                    // He opens Backpack 3 from the storage menu (the server opens it).
                    c.hx().menu("Large Backpack (Slot #3)", 6, Map.of(0, "minecraft:black_stained_glass_pane"), "storage");
                } else {
                    c.ctx().runOnClient(mc -> mc.player.closeContainer()); // Escape
                }
                c.waitUntil("the layouts menu to come back", mc -> McCompat.screen(mc) != null
                        && McCompat.screen(mc).getClass().getSimpleName().equals("InventorySorterScreen"), 120);
                String page = c.onClient(mc -> (String) Mod.call(Mod.call(store(), "get", name), "dumpPage"));
                String status = c.onClient(mc -> (String) Mod.call(McCompat.screen(mc), "status"));
                String sel = c.onClient(mc -> (String) Mod.call(McCompat.screen(mc), "selectedLayout"));
                String still = c.onClient(mc -> (String) Mod.staticCall("invsort.InvSortPagePick", "pickingFor"));
                c.note((cancel ? "after Escape" : "after opening Backpack #3") + ": page " + page + ", selected " + sel
                        + ", status '" + status + "', still picking " + still);
                c.check("backpack_3".equals(page), "the layout's page is " + page + ", want backpack_3");
                c.check(name.equals(sel) && still == null, "the menu did not come back on the layout");
                c.check(cancel ? status.startsWith("No page picked") : status.contains("Backpack #3"), "status " + status);
            }
            // Right click: none.
            String cleared = c.onClient(mc -> {
                Mod.call(McCompat.screen(mc), "clearDumpPage");
                return String.valueOf(Mod.call(Mod.call(store(), "get", name), "dumpPage"));
            });
            c.note("right click (clear): page " + cleared);
            c.check("null".equals(cleared), "clear left page " + cleared);
            List<JsonObject> clicks = clicksSince(c, clicksBefore);
            c.note("container clicks during the picks: " + show(clicks));
            c.check(clicks.isEmpty(), "the page pick sent container clicks: " + show(clicks));
        } finally {
            c.ctx().runOnClient(mc -> {
                if (mc.player != null && McCompat.screen(mc) != null) {
                    McCompat.setScreen(mc, null);
                }
                Mod.call(store(), "delete", name);
            });
            MenuKit.reset(c);
            restore(c, p, old);
            InventoryCases.clearInv(c);
        }
    }
}
