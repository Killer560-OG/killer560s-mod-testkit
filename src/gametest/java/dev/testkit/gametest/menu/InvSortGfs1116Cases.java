package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;
import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Inventory Sorter Fetch Missing From Sacks, pacing and storage first (invsortgfs-1116, killer560 2026-11-16: "Command Failed:
 * This command is on cooldown! ... it needs to have a 3s delay between grabs" and "sometimes it is grabbing items even though
 * i have multiple stacks in my storage under the amount it should grab from those first, put excess back in the storage then
 * gfs"):
 * <ul>
 *   <li>1116-invsort-gfs-gap: two items short, the two /gfs sends are at least 3 s apart on the wall clock.</li>
 *   <li>1116-invsort-gfs-cooldown: the first /gfs is answered with Hypixel's cooldown line; the same /gfs goes out again,
 *   the item arrives and nothing is called missing.</li>
 *   <li>1116-invsort-gfs-combine: 40 wanted, storage holds stacks of 5, 12 and 30: no /gfs, the inventory ends with exactly 40
 *   and the 7 over go back onto the page (the 12 is split with cursor clicks).</li>
 *   <li>1116-invsort-gfs-partial: 40 wanted, storage holds 12 and 15: both are taken first, then exactly one /gfs for 13.</li>
 * </ul>
 */
final class InvSortGfs1116Cases {

    private static final String ISC = "invsort.InventorySorterConfig";
    private static final String EXEC = "invsort.InventorySorterExecutor";
    private static final String PEARL = "minecraft:ender_pearl[custom_data={id:\"ENDER_PEARL\"}]";
    private static final String TNT = "minecraft:tnt[custom_data={id:\"SUPERBOOM_TNT\"}]";
    private static final String COOLDOWN = "Command Failed: This command is on cooldown! Try again in about a second!";

    private InvSortGfs1116Cases() {
    }

    static void register(Session s) {
        for (String mode : new String[]{"gap", "cooldown", "combine", "partial"}) {
            MenuSuite.test(s, "1116-invsort-gfs-" + mode, c -> body(c, mode));
        }
    }

    private static ItemStack tnt(int n) {
        ItemStack st = new ItemStack(Items.TNT, n);
        CompoundTag t = new CompoundTag();
        t.putString("id", "SUPERBOOM_TNT");
        st.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
        return st;
    }

    private static int count(Session c, Item item) throws Exception {
        return c.onClient(mc -> {
            int n = 0;
            for (int i = 0; i < 36; i++) {
                ItemStack st = mc.player.getInventory().getItem(i);
                if (st.is(item)) {
                    n += st.getCount();
                }
            }
            return n;
        });
    }

    /** Total TNT in the open page's own slots (the first 54 of the container menu) as the client sees them, -1 with no container open. */
    private static int pageTnt(Session c) throws Exception {
        return c.onClient(mc -> {
            if (!(dev.testkit.compat.McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> s)
                    || !(s.getMenu() instanceof net.minecraft.world.inventory.ChestMenu)) {
                return -1;
            }
            int n = 0;
            for (int i = 0; i < Math.min(54, s.getMenu().slots.size()); i++) {
                ItemStack st = s.getMenu().slots.get(i).getItem();
                n += st.is(Items.TNT) ? st.getCount() : 0;
            }
            return n;
        });
    }

    private static void body(Session c, String mode) throws Exception {
        MenuKit.reset(c);
        InventoryCases.clearInv(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: the sorter is cheat-only");
            return;
        }
        boolean storage = mode.equals("combine") || mode.equals("partial");
        int[] stacks = mode.equals("combine") ? new int[]{5, 12, 30} : mode.equals("partial") ? new int[]{12, 15} : new int[0];
        Path p = c.onClient(mc -> (Path) Mod.field(ISC, "CONFIG_PATH"));
        byte[] old = c.onClient(mc -> {
            try {
                return java.nio.file.Files.exists(p) ? java.nio.file.Files.readAllBytes(p) : null;
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
        });
        try {
            c.ctx().runOnClient(mc -> {
                Mod.call(Mod.cfg(ISC), "setTicksBetweenMoves", 2);
                Mod.call(Mod.cfg(ISC), "setRandomExtraTicks", 0);
                Mod.call(Mod.cfg(ISC), "setInstant", false);
                Mod.call(Mod.cfg(ISC), "setFetchSacks", true);
                Mod.setField("util.SackCommands", "dead", false);
            });
            c.hx().sidebar("SKYBLOCK", "Hx gfs line");
            c.waitUntil("a Skyblock sidebar", mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isOnSkyblock"), 120);
            c.hx().stub(null, "enderchest", "backpack", "gfs");
            c.hx().give(0, "minecraft:emerald");
            c.hx().give(8, "minecraft:nether_star[custom_data={id:\"SKYBLOCK_MENU\"}]");
            c.waitUntil("the emerald", mc -> mc.player.getInventory().getItem(0).is(Items.EMERALD)
                    && mc.player.getInventory().getItem(8).is(Items.NETHER_STAR), 60);
            String key = c.onClient(mc -> (String) Mod.staticCall("storageoverlay.StorageOverlayFeature", "storageKeyForTitle",
                    "Ender Chest (1/9)"));
            c.ctx().runOnClient(mc -> {
                List<ItemStack> page = new ArrayList<>();
                for (int i = 0; i < 45; i++) {
                    page.add(ItemStack.EMPTY);
                }
                for (int k = 0; k < stacks.length; k++) {
                    page.set(10 + k, tnt(stacks[k]));
                }
                Mod.call(Mod.staticCall("storageoverlay.StorageOverlayCache", "getInstance"), "put", key, page);
            });
            Map<Integer, String> wants = mode.equals("gap") || mode.equals("cooldown")
                    ? Map.of(0, "EMERALD", 3, "ENDER_PEARL", 4, "SUPERBOOM_TNT") : Map.of(0, "EMERALD", 3, "SUPERBOOM_TNT");
            Map<Integer, Integer> counts = mode.equals("gap") ? Map.of(0, 1, 3, 5, 4, 6)
                    : mode.equals("cooldown") ? Map.of(0, 1, 3, 5) : Map.of(0, 1, 3, 40);
            if (mode.equals("cooldown")) {
                wants = Map.of(0, "EMERALD", 3, "ENDER_PEARL");
            }
            Map<Integer, String> w = wants;
            Object layout = c.onClient(mc -> {
                try {
                    return Mod.cls("invsort.InventoryLayout").getConstructor(String.class, Map.class, Map.class, Map.class,
                            Set.class, boolean.class, String.class, int.class, Map.class)
                            .newInstance("hx-1116-gfs", w, Map.of(), Map.of(), Set.of(), true, null, 0, counts);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            long mark = LogTap.mark();
            long since = c.hx().head();
            c.check(c.onClient(mc -> (Boolean) Mod.staticCall(EXEC, "start", layout)), "start returned false");
            boolean opened = false;
            int handled = 0;
            int pageLeft = -1;
            int tntAtFirstGfs = -1;
            List<Long> times = new ArrayList<>();
            for (int i = 0; i < 1500 && c.onClient(mc -> (Boolean) Mod.staticCall(EXEC, "isRunning")); i++) {
                c.ctx().waitTicks(1);
                List<String> cmds = c.hx().commands(since);
                if (!opened && cmds.stream().anyMatch(x -> x.startsWith("enderchest 1"))) {
                    JsonObject a = new JsonObject();
                    a.addProperty("title", "Ender Chest (1/9)");
                    a.addProperty("rows", 6);
                    a.addProperty("script", "storage");
                    JsonObject slots = new JsonObject();
                    slots.addProperty("0", "minecraft:black_stained_glass_pane");
                    for (int k = 0; k < stacks.length; k++) {
                        slots.addProperty(String.valueOf(10 + k), TNT + "*" + stacks[k]);
                    }
                    a.add("slots", slots);
                    c.hx().call("menu.open", a);
                    opened = true;
                }
                if (opened && storage && handled == 0) {
                    int seen = pageTnt(c);
                    pageLeft = seen >= 0 ? seen : pageLeft; // the last look at the page before the sorter closes it
                }
                List<String> gfs = cmds.stream().filter(x -> x.startsWith("gfs ")).toList();
                while (handled < gfs.size()) {
                    String g = gfs.get(handled++);
                    times.add(System.currentTimeMillis());
                    if (handled == 1) {
                        tntAtFirstGfs = count(c, Items.TNT);
                    }
                    if (mode.equals("cooldown") && handled == 1) {
                        c.hx().chat(COOLDOWN);
                        continue;
                    }
                    String[] parts = g.split(" ");
                    int n = Integer.parseInt(parts[2]);
                    boolean pearl = parts[1].equals("ender_pearl");
                    c.hx().call("give", "slot", 20 + handled, "stack", pearl ? PEARL : TNT, "count", n);
                    c.ctx().waitTicks(2);
                    c.hx().chat("Moved " + n + (pearl ? " Ender Pearl" : " Superboom TNT") + " from your Sacks to your inventory.");
                }
            }
            c.check(!(Boolean) c.onClient(mc -> (Boolean) Mod.staticCall(EXEC, "isRunning")), "the sorter never finished");
            List<String> cmds = c.hx().commands(since);
            List<String> gfs = cmds.stream().filter(x -> x.startsWith("gfs ")).toList();
            c.note(mode + ": commands " + cmds + " gaps " + times);
            switch (mode) {
                case "gap" -> {
                    c.check(Set.copyOf(gfs).equals(Set.of("gfs ender_pearl 5", "gfs superboom_tnt 6")) && gfs.size() == 2,
                            "want one /gfs per short item: " + gfs);
                    long gap = times.get(1) - times.get(0);
                    c.check(gap >= 2800, "two /gfs " + gap + " ms apart, want at least 3000 (seen within one tick)");
                }
                case "cooldown" -> {
                    c.check(gfs.equals(List.of("gfs ender_pearl 5", "gfs ender_pearl 5")), "want the refused /gfs sent again: " + gfs);
                    c.check(times.size() == 2 && times.get(1) - times.get(0) >= 2800,
                            "the second /gfs came too soon after the first: " + times);
                    c.check(count(c, Items.ENDER_PEARL) == 5, "the pearls never arrived: " + count(c, Items.ENDER_PEARL));
                    List<String> done = LogTap.since(mark).stream().filter(l -> l.contains("Sacks done")).toList();
                    c.check(done.size() == 1 && done.get(0).contains("1 fetched") && done.get(0).contains("[]"),
                            "the refused item was counted missing: " + done);
                }
                case "combine" -> {
                    c.check(cmds.stream().anyMatch(x -> x.startsWith("enderchest 1")), "storage was never visited: " + cmds);
                    c.check(gfs.isEmpty(), "storage covers it, no /gfs wanted: " + gfs);
                    c.check(count(c, Items.TNT) == 40, "inventory TNT " + count(c, Items.TNT) + ", want exactly 40");
                    c.check(pageLeft == 7, "the 7 over must be back on the page, last look " + pageLeft);
                }
                default -> {
                    c.check(cmds.stream().anyMatch(x -> x.startsWith("enderchest 1")), "storage was never visited: " + cmds);
                    c.check(gfs.equals(List.of("gfs superboom_tnt 13")), "want exactly /gfs superboom_tnt 13 (40 - 12 - 15): " + gfs);
                    c.check(tntAtFirstGfs == 27, "both storage stacks must be in the inventory before /gfs: " + tntAtFirstGfs);
                    c.check(count(c, Items.TNT) == 40, "inventory TNT " + count(c, Items.TNT) + ", want exactly 40");
                }
            }
        } finally {
            if ((Boolean) c.onClient(mc -> (Boolean) Mod.staticCall(EXEC, "isRunning"))) {
                c.ctx().runOnClient(mc -> Mod.staticCall(EXEC, "requestStop"));
            }
            c.hx().sidebarClear();
            MenuKit.reset(c);
            c.ctx().runOnClient(mc -> {
                try {
                    if (old == null) {
                        java.nio.file.Files.deleteIfExists(p);
                    } else {
                        java.nio.file.Files.write(p, old);
                    }
                } catch (java.io.IOException e) {
                    throw new AssertionError(e);
                }
                Mod.staticCall(ISC, "load");
                Mod.setField("util.SackCommands", "dead", false);
            });
            InventoryCases.clearInv(c);
        }
    }
}
