package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;
import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Inventory Sorter's Dump Into Chest (mod invdump-1104, killer560 2026-11-04): a REAL vanilla chest on the menu session's
 * GrimAC server (real shift-clicks that move items), the dump started through {@code InvSortDump.start} (the button and the
 * key call the same method; the button's draw and click are checked through eligible()).
 * <ul>
 *   <li>1104-invsort-dump-basic: main-inventory items go into the chest by shift-click, slot 8 (a star) and the hotbar do not,
 *   exactly one QUICK_MOVE per moved stack, and Grim flags nothing (MenuSuite fails a case on any flag).</li>
 *   <li>1104-invsort-dump-hotbar: with Dump Hotbar on the hotbar goes too, slot 8 still stays.</li>
 *   <li>1104-invsort-dump-full: a chest with one free slot takes one stack, the run ends on its own ("the chest is full") and the
 *   rest stays in the inventory.</li>
 *   <li>1104-invsort-dump-refuse: a menu that is not storage (a "Shop" stub menu) is refused and no click goes out.</li>
 * </ul>
 * Every case asserts the dump ACTED (clicks recorded, items in the chest) before it quotes a result.
 */
final class InvSortDump1104Cases {

    private static final String ISC = "invsort.InventorySorterConfig";
    private static final String DUMP = "invsort.InvSortDump";

    private InvSortDump1104Cases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1104-invsort-dump-basic", c -> run(c, Boolean.FALSE));
        MenuSuite.test(s, "1104-invsort-dump-hotbar", c -> run(c, Boolean.TRUE));
        MenuSuite.test(s, "1105-invsort-dump-hotbar-default", c -> run(c, null));
        MenuSuite.test(s, "1105-invsort-dump-hotbar-migrate", InvSortDump1104Cases::migrate);
        MenuSuite.test(s, "1105-invsort-dump-below-scan", InvSortDump1104Cases::belowScan);
        MenuSuite.test(s, "1104-invsort-dump-full", InvSortDump1104Cases::full);
        MenuSuite.test(s, "1104-invsort-dump-refuse", InvSortDump1104Cases::refuse);
    }

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

    private static boolean legit(Session c) {
        if (!MenuKit.cheat()) {
            c.note("legit jar: the sorter is cheat-only (InvSortDump class present " + Mod.has(DUMP) + ")");
            return true;
        }
        return false;
    }

    private static BlockPos chestPos(Session c) {
        return c.onClient(mc -> mc.player.blockPosition().offset(2, 0, 0));
    }

    /** Puts an empty chest at {@code pos} (air above it), opens it with a real use packet, waits for the screen. */
    private static void openChest(Session c, BlockPos pos, int fill) {
        c.server().command(String.format("setblock %d %d %d minecraft:air", pos.getX(), pos.getY() + 1, pos.getZ()));
        c.server().command(String.format("setblock %d %d %d minecraft:chest[facing=west]", pos.getX(), pos.getY(), pos.getZ()));
        for (int i = 0; i < fill; i++) {
            c.server().command(String.format("item replace block %d %d %d container.%d with minecraft:cobblestone 64",
                    pos.getX(), pos.getY(), pos.getZ(), i));
        }
        c.server().sync(10);
        c.ctx().waitTicks(10);
        c.ctx().runOnClient(mc -> {
            double dx = pos.getX() + 0.5 - mc.player.getX();
            double dy = pos.getY() + 0.5 - mc.player.getEyeY();
            double dz = pos.getZ() + 0.5 - mc.player.getZ();
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
            mc.player.setYRot(mc.player.getYRot() + net.minecraft.util.Mth.wrapDegrees(yaw - mc.player.getYRot()));
            mc.player.setXRot(net.minecraft.util.Mth.clamp(pitch, -90f, 90f));
        });
        c.ctx().waitTicks(3);
        c.ctx().getInput().pressKey(o -> o.keyUse);
        c.waitUntil("the chest screen", mc -> McCompat.screen(mc) instanceof AbstractContainerScreen<?> s
                && s.getTitle().getString().equals("Chest") && mc.player.containerMenu.slots.size() == 63, 100);
        c.ctx().waitTicks(5);
    }

    private static void closeChest(Session c, BlockPos pos) {
        c.ctx().runOnClient(mc -> {
            if (mc.player != null && McCompat.screen(mc) != null) {
                mc.player.closeContainer();
            }
        });
        c.ctx().waitTicks(5);
        c.server().command(String.format("setblock %d %d %d minecraft:air", pos.getX(), pos.getY(), pos.getZ()));
    }

    private static Object startDump(Session c) {
        return c.onClient(mc -> Mod.staticCall(DUMP, "start", (AbstractContainerScreen<?>) McCompat.screen(mc)));
    }

    private static boolean dumping(Session c) {
        return c.onClient(mc -> (Boolean) Mod.staticCall(DUMP, "isRunning"));
    }

    /** Container items summed per Item in the chest's own 27 slots. */
    private static int chestHolds(Session c, Item item) {
        return c.onClient(mc -> {
            int n = 0;
            for (int i = 0; i < 27; i++) {
                var st = mc.player.containerMenu.getSlot(i).getItem();
                if (st.is(item)) {
                    n += st.getCount();
                }
            }
            return n;
        });
    }

    private static List<JsonObject> quickMoves(Session c, int before) {
        List<JsonObject> all = c.events("container.click");
        List<JsonObject> out = new ArrayList<>();
        for (JsonObject e : all.subList(before, all.size())) {
            out.add(e);
        }
        return out;
    }

    private static void give(Session c) {
        InventoryCases.clearInv(c);
        c.hx().give(8, "minecraft:nether_star[custom_data={id:\"SKYBLOCK_MENU\"}]");
        c.hx().call("give", "slot", 9, "stack", "minecraft:stone", "count", 5);
        c.hx().call("give", "slot", 10, "stack", "minecraft:dirt", "count", 7);
        c.hx().call("give", "slot", 20, "stack", "minecraft:emerald", "count", 3);
        c.hx().call("give", "slot", 35, "stack", "minecraft:diamond", "count", 2);
        c.hx().call("give", "slot", 2, "stack", "minecraft:gold_ingot", "count", 4);
        c.hx().call("give", "slot", 7, "stack", "minecraft:stick", "count", 6);
        c.waitUntil("the items", mc -> mc.player.getInventory().getItem(35).is(Items.DIAMOND)
                && mc.player.getInventory().getItem(7).is(Items.STICK) && mc.player.getInventory().getItem(8).is(Items.NETHER_STAR), 80);
    }

    private static void run(Session c, Boolean hotbarOpt) throws Exception {
        boolean hotbar = hotbarOpt == null || hotbarOpt;
        MenuKit.reset(c);
        if (legit(c)) {
            return;
        }
        Path p = cfgPath(c);
        byte[] old = read(p);
        BlockPos pos = chestPos(c);
        try {
            cfg(c, "setEnabled", true);
            cfg(c, "setTicksBetweenMoves", 2);
            cfg(c, "setRandomExtraTicks", 0);
            if (hotbarOpt == null) {
                // A fresh config (no file): Dump Hotbar must come up ON by itself.
                c.ctx().runOnClient(mc -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (java.io.IOException e) {
                        throw new AssertionError(e);
                    }
                    Mod.staticCall(ISC, "load");
                });
                cfg(c, "setEnabled", true);
                cfg(c, "setTicksBetweenMoves", 2);
                cfg(c, "setRandomExtraTicks", 0);
                c.check((Boolean) c.onClient(mc -> Mod.call(Mod.cfg(ISC), "isDumpHotbar")), "Dump Hotbar is not ON in a fresh config");
            } else {
                cfg(c, "setDumpHotbar", hotbar);
            }
            give(c);
            openChest(c, pos, 0);
            int before = c.events("container.click").size();
            Object why = startDump(c);
            c.check(why == null, "the dump did not start: " + why);
            c.check(dumping(c), "the dump is not running after start");
            c.waitUntil("the dump to finish", mc -> !(Boolean) Mod.staticCall(DUMP, "isRunning"), 300);
            c.ctx().waitTicks(10);
            List<JsonObject> clicks = quickMoves(c, before);
            int expected = hotbar ? 6 : 4;
            c.note((hotbar ? "hotbar pass" : "plain pass") + ": clicks " + clicks);
            c.check(clicks.size() == expected && clicks.stream().allMatch(e -> e.get("input").getAsString().equals("QUICK_MOVE")),
                    "want " + expected + " QUICK_MOVE clicks, got " + clicks);
            c.check(clicks.stream().noneMatch(e -> e.get("slot").getAsInt() == 62), "a click touched slot 8 (container slot 62): " + clicks);
            c.check(chestHolds(c, Items.STONE) == 5 && chestHolds(c, Items.DIRT) == 7 && chestHolds(c, Items.EMERALD) == 3
                    && chestHolds(c, Items.DIAMOND) == 2, "the main inventory did not reach the chest");
            c.check(chestHolds(c, Items.GOLD_INGOT) == (hotbar ? 4 : 0) && chestHolds(c, Items.STICK) == (hotbar ? 6 : 0),
                    "the hotbar " + (hotbar ? "was not" : "was") + " dumped");
            c.check(c.onClient(mc -> mc.player.getInventory().getItem(8).is(Items.NETHER_STAR)), "slot 8 (the star) moved");
            c.check(c.onClient(mc -> mc.player.getInventory().getItem(9).isEmpty()), "inventory slot 9 was not emptied");
        } finally {
            if (dumping(c)) {
                c.ctx().runOnClient(mc -> Mod.staticCall("invsort.InvSortDump", "isRunning"));
            }
            closeChest(c, pos);
            restore(c, p, old);
            InventoryCases.clearInv(c);
            MenuKit.reset(c);
        }
    }

    /** A saved file with Dump Hotbar off and no version key loads ON once; with the key it stays as saved. */
    private static void migrate(Session c) throws Exception {
        MenuKit.reset(c);
        if (legit(c)) {
            return;
        }
        Path p = cfgPath(c);
        byte[] old = read(p);
        try {
            for (String[] t : new String[][]{{"{\"dumpHotbar\":false}", "true"}, {"{\"dumpHotbar\":false,\"dumpHotbarV2\":true}", "false"}}) {
                c.ctx().runOnClient(mc -> {
                    try {
                        Files.createDirectories(p.getParent());
                        Files.writeString(p, t[0]);
                    } catch (java.io.IOException e) {
                        throw new AssertionError(e);
                    }
                    Mod.staticCall(ISC, "load");
                });
                boolean got = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(ISC), "isDumpHotbar"));
                c.check(String.valueOf(got).equals(t[1]), t[0] + " loaded dumpHotbar=" + got + ", want " + t[1]);
            }
            c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(ISC), "save"));
            c.check(Files.readString(p).contains("dumpHotbarV2"), "a saved file lacks the version key: " + Files.readString(p));
        } finally {
            restore(c, p, old);
            MenuKit.reset(c);
        }
    }

    /** With the Storage Overlay up, Dump Into Chest sits below Scan All, clear of every drawn rect, and its click starts the dump. */
    private static void belowScan(Session c) throws Exception {
        MenuKit.reset(c);
        if (legit(c)) {
            return;
        }
        Path p = cfgPath(c);
        byte[] old = read(p);
        String sf = ContainerCoverCases.SFEAT;
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(ContainerCoverCases.SCFG, "Enabled", true);
            cfg(c, "setEnabled", true);
            cfg(c, "setDumpButton", true);
            cfg(c, "setTicksBetweenMoves", 40);
            AuctionHouseCases.setWindow(c, 1280, 720, 2);
            c.onClient(mc -> {
                Mod.setField(sf, "lastPos", null);
                return null;
            });
            String focus = c.onClient(mc -> Mod.staticCall(sf, "accountProfilePrefix") + "|enderchest_1");
            c.onClient(mc -> {
                Object cache = Mod.staticCall("storageoverlay.StorageOverlayCache", "getInstance");
                if (!(Boolean) Mod.call(cache, "hasContents", focus)) {
                    List<net.minecraft.world.item.ItemStack> items = new ArrayList<>();
                    for (int i = 0; i < 45; i++) {
                        items.add(i == 0 ? new net.minecraft.world.item.ItemStack(Items.DIAMOND, 5) : net.minecraft.world.item.ItemStack.EMPTY);
                    }
                    Mod.call(cache, "put", focus, items);
                }
                Mod.staticCall(sf, "setFocus", focus);
                return null;
            });
            MenuKit.show(c, MenuKit.menu("menus.ender-chest-1"));
            MenuKit.awaitScreen(c, "Ender Chest (1/9)", 100);
            c.waitUntil("the overlay's Scan All button", mc -> Mod.field(sf, "lastScanButton") != null
                    && Mod.staticCall(sf, "dumpSlot", McCompat.screen(mc)) != null, 100);
            c.ctx().waitTicks(6);
            int[] scan = c.onClient(mc -> (int[]) Mod.field(sf, "lastScanButton"));
            int[] dump = c.onClient(mc -> (int[]) Mod.staticCall(sf, "dumpSlot", McCompat.screen(mc)));
            List<int[]> drawn = c.onClient(mc -> new ArrayList<>((List<int[]>) Mod.staticCall(sf, "drawnRects", McCompat.screen(mc))));
            c.note("scan " + java.util.Arrays.toString(scan) + " dump " + java.util.Arrays.toString(dump) + " drawn " + drawn.size());
            boolean below = dump[0] == scan[0] && dump[1] == scan[1] + scan[3] + 4;
            boolean row = dump[1] == scan[1] && dump[0] == scan[0] + scan[2] + 4;
            c.check(below || row, "Dump Into Chest is neither below Scan All (+4) nor next in the row");
            c.check(dump[2] >= scan[2] && dump[2] == scan[2], "the side buttons differ in width: scan " + scan[2] + " dump " + dump[2]);
            for (int[] r : drawn) {
                boolean over = dump[0] < r[2] && dump[0] + dump[2] > r[0] && dump[1] < r[3] && dump[1] + dump[3] > r[1];
                c.check(!over, "the dump button overlaps a drawn rect " + java.util.Arrays.toString(r));
            }
            // The click on that rect starts the dump (give it something to dump first).
            c.hx().call("give", "slot", 9, "stack", "minecraft:stone", "count", 5);
            c.ctx().waitTicks(5);
            ContainerCoverCases.cursor(c, dump[0] + dump[2] / 2.0, dump[1] + dump[3] / 2.0);
            ContainerCoverCases.press(c, 0, 0);
            c.check(dumping(c), "a click on the dump rect did not start the dump");
            ContainerCoverCases.press(c, 0, 0); // toggles it off again
            c.check(!dumping(c), "a second click did not stop the dump");
        } finally {
            restore(c, p, old);
            InventoryCases.clearInv(c);
            MenuKit.reset(c);
        }
    }

    private static void full(Session c) throws Exception {
        MenuKit.reset(c);
        if (legit(c)) {
            return;
        }
        Path p = cfgPath(c);
        byte[] old = read(p);
        BlockPos pos = chestPos(c);
        try {
            cfg(c, "setEnabled", true);
            cfg(c, "setTicksBetweenMoves", 2);
            cfg(c, "setRandomExtraTicks", 0);
            cfg(c, "setDumpHotbar", false);
            give(c);
            openChest(c, pos, 26); // 26 of 27 slots hold cobblestone: room for exactly one stack
            int before = c.events("container.click").size();
            c.check(startDump(c) == null, "the dump did not start");
            c.waitUntil("the dump to finish", mc -> !(Boolean) Mod.staticCall(DUMP, "isRunning"), 300);
            c.ctx().waitTicks(10);
            List<JsonObject> clicks = quickMoves(c, before);
            c.note("full chest: clicks " + clicks);
            c.check(clicks.size() >= 1 && clicks.size() <= 2, "want one (at most two, a late echo) shift-click, got " + clicks);
            int inChest = chestHolds(c, Items.STONE) + chestHolds(c, Items.DIRT) + chestHolds(c, Items.EMERALD)
                    + chestHolds(c, Items.DIAMOND);
            c.check(inChest > 0, "nothing reached the one free slot");
            int left = c.onClient(mc -> {
                int n = 0;
                for (int i = 9; i < 36; i++) {
                    n += mc.player.getInventory().getItem(i).isEmpty() ? 0 : 1;
                }
                return n;
            });
            c.check(left == 3, "want 3 stacks left in the inventory, found " + left);
            c.check(c.onClient(mc -> McCompat.screen(mc) != null) && !dumping(c), "it should end by itself with the chest still open");
        } finally {
            closeChest(c, pos);
            restore(c, p, old);
            InventoryCases.clearInv(c);
            MenuKit.reset(c);
        }
    }

    private static void refuse(Session c) throws Exception {
        MenuKit.reset(c);
        if (legit(c)) {
            return;
        }
        Path p = cfgPath(c);
        byte[] old = read(p);
        try {
            cfg(c, "setEnabled", true);
            cfg(c, "setTicksBetweenMoves", 2);
            cfg(c, "setRandomExtraTicks", 0);
            give(c);
            c.hx().menu("Shop", 3, java.util.Map.of(0, "minecraft:emerald"), null);
            c.waitUntil("the shop menu", mc -> McCompat.screen(mc) instanceof AbstractContainerScreen<?> s && s.getTitle().getString().equals("Shop"), 100);
            c.ctx().waitTicks(5);
            int before = c.events("container.click").size();
            Object why = startDump(c);
            c.ctx().waitTicks(10);
            c.note("shop menu: start answered " + why);
            c.check(why != null && !dumping(c), "a non-storage menu was accepted: " + why);
            c.check(quickMoves(c, before).isEmpty(), "a click went out in a Shop menu");
            c.check(!(Boolean) c.onClient(mc -> Mod.staticCall(DUMP, "eligible", McCompat.screen(mc))), "eligible() accepted a Shop");
        } finally {
            c.ctx().runOnClient(mc -> {
                if (mc.player != null && McCompat.screen(mc) != null) {
                    mc.player.closeContainer();
                }
            });
            restore(c, p, old);
            InventoryCases.clearInv(c);
            MenuKit.reset(c);
        }
    }
}
