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
        MenuSuite.test(s, "1104-invsort-dump-basic", c -> run(c, false));
        MenuSuite.test(s, "1104-invsort-dump-hotbar", c -> run(c, true));
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

    private static void run(Session c, boolean hotbar) throws Exception {
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
            cfg(c, "setDumpHotbar", hotbar);
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
