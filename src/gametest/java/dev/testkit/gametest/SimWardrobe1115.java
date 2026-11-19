package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static dev.testkit.gametest.SimBossTests.call;
import static dev.testkit.gametest.SimBossTests.expect;
import static dev.testkit.gametest.SimBossTests.println;
import static dev.testkit.gametest.SimBossTests.start;

/**
 * {@code 1115-sim-wardrobe-*} (simwardrobe-1115, killer560 2026-11-15: "add in custom wardrobe and stats menu and stuff to
 * swap things between"). The sim's /wardrobe: open it, click a set, and the worn pieces, the saved loadout and the next bow
 * shot follow; the stats menu lists numbers that change with the set. Every check reads what the menu really held or what
 * the sim did, never only that no error came.
 */
final class SimWardrobe1115 {

    static final String NAME = "1115-sim-wardrobe";
    private static final String WARD = "com.killer560.hub.roomsim.SimWardrobe";
    private static final int[] SET_SLOTS = {36, 37};

    private SimWardrobe1115() {
    }

    private static boolean open(ClientGameTestContext ctx, boolean stats) {
        boolean ok = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(WARD, "open",
                new Class<?>[]{Minecraft.class, boolean.class}, new Object[]{mc, stats}));
        ctx.waitTicks(8);
        return ok;
    }

    private static String title(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> McCompat.screen(mc) == null ? "none" : McCompat.screen(mc).getTitle().getString());
    }

    private static void click(ClientGameTestContext ctx, int slot) {
        ctx.runOnClient(mc -> mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId, slot, 0,
                ContainerInput.PICKUP, mc.player));
        ctx.waitTicks(6);
    }

    private static void close(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> mc.player.closeContainer());
        ctx.waitTicks(4);
    }

    /** Menu slots' hover name -> first lore line, for the open menu. */
    private static Map<String, String> sheet(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            Map<String, String> m = new HashMap<>();
            for (int i = 0; i < 54; i++) {
                ItemStack s = mc.player.containerMenu.getSlot(i).getItem();
                ItemLore lore = s.get(DataComponents.LORE);
                m.put(s.getHoverName().getString(), lore == null || lore.lines().isEmpty() ? "" : lore.lines().get(0).getString());
            }
            return m;
        });
    }

    /** The Skyblock id in a menu slot of the open menu, "null" for none. */
    private static String menuId(ClientGameTestContext ctx, int slot) {
        return ctx.computeOnClient(mc -> String.valueOf(ModUnderTest.staticCall("com.killer560.hub.cheatutils.CheatUtils", "skyblockId",
                new Class<?>[]{ItemStack.class}, new Object[]{mc.player.containerMenu.getSlot(slot).getItem()})));
    }

    private static int arrows(ClientGameTestContext ctx) {
        return (Integer) call("com.killer560.hub.roomsim.SimTerminator", "arrowsFired");
    }

    /** The sim's own weapon rates (shortbow, melee/beam, Terminator left click) in ticks: Attack Speed 150 with Thermodynamic, 100 without. */
    private static void rates(ClientGameTestContext ctx, List<String> f, String set, int bow, int melee, int click) {
        String a = "com.killer560.hub.roomsim.SimAttackSpeed";
        expect(f, set + " shortbow interval", ctx.computeOnClient(mc -> String.valueOf(call(a, "shortbowInterval"))), String.valueOf(bow));
        expect(f, set + " melee interval", ctx.computeOnClient(mc -> String.valueOf(call(a, "meleeInterval"))), String.valueOf(melee));
        expect(f, set + " terminator left click interval", ctx.computeOnClient(mc -> String.valueOf(call(a, "terminatorClickInterval"))), String.valueOf(click));
    }

    private static void cmd(ClientGameTestContext ctx, String line) {
        ctx.runOnClient(mc -> mc.player.connection.sendCommand(line));
        ctx.waitTicks(10);
    }

    private static String head(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> String.valueOf(ModUnderTest.staticCall("com.killer560.hub.cheatutils.CheatUtils", "skyblockId",
                new Class<?>[]{ItemStack.class}, new Object[]{mc.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD)})));
    }

    private static String invId(ClientGameTestContext ctx, int invSlot) {
        return ctx.computeOnClient(mc -> String.valueOf(ModUnderTest.staticCall("com.killer560.hub.cheatutils.CheatUtils", "skyblockId",
                new Class<?>[]{ItemStack.class}, new Object[]{mc.player.getInventory().getItem(invSlot)})));
    }

    private static void giveMask(ClientGameTestContext ctx, int invSlot, String id) {
        SimBossTests.serverRun(ctx, sv -> {
            ItemStack m = new ItemStack(net.minecraft.world.item.Items.LEATHER_HELMET);
            net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
            tag.putString("id", id);
            m.set(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(tag));
            sv.getPlayerList().getPlayers().get(0).getInventory().setItem(invSlot, m);
        });
        ctx.waitTicks(6);
    }

    /** /wd, /stats, /equipment, /eq open the right menus, and a mask clicked in the inventory swaps with the worn helmet. */
    private static void commandsAndMasks(ClientGameTestContext ctx, List<String> f) {
        cmd(ctx, "wd");
        expect(f, "/wd opens the wardrobe", title(ctx), "Wardrobe (1/2)");
        Map<String, String> wd = sheet(ctx);
        expect(f, "the wardrobe has no Stats item", wd.containsKey("Stats") || wd.containsKey("Attack Speed (effective)"), false);
        expect(f, "the wardrobe still lists the sets", wd.containsKey("Slot 1"), true);
        close(ctx);
        for (String c : new String[]{"stats", "equipment", "eq"}) {
            cmd(ctx, c);
            expect(f, "/" + c + " opens the equipment menu", title(ctx), "Stats & Equipment");
            expect(f, "/" + c + " shows no stat sheet", sheet(ctx).containsKey("Attack Speed (effective)"), false);
            close(ctx);
        }
        // Real pieces: Terror is worn here. Head is a player head, the others leather with Hypixel's dye.
        String[] slots = {"HEAD", "CHEST", "LEGS", "FEET"};
        String[] mat = {"minecraft:player_head", "minecraft:leather_chestplate", "minecraft:leather_leggings", "minecraft:leather_boots"};
        int[] dye = {-1, 0x3E05AF, 0x5D23D1, 0x7C44EC};
        for (int i = 0; i < 4; i++) {
            final int k = i;
            String got = ctx.computeOnClient(mc -> {
                ItemStack st = mc.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.valueOf(slots[k]));
                var dc = st.get(DataComponents.DYED_COLOR);
                var pr = st.get(DataComponents.PROFILE);
                return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()) + "|"
                        + (dc == null ? -1 : dc.rgb() & 0xFFFFFF) + "|" + (pr != null);
            });
            expect(f, "Terror " + slots[i] + " is the real piece (item, dye, head profile)", got, mat[i] + "|" + dye[i] + "|" + (i == 0));
        }
        rates(ctx, f, "Terror worn", 5, 5, 4);
        // Quick swap by hand: /stats, click the mask in the inventory.
        giveMask(ctx, 10, "BONZO_MASK");
        cmd(ctx, "stats");
        click(ctx, 54 + 1);
        expect(f, "hand click swaps the mask onto the head", head(ctx), "BONZO_MASK");
        expect(f, "the old helmet lands in the clicked slot", invId(ctx, 10), "INFERNAL_TERROR_HELMET");
        close(ctx);
        expect(f, "a mask on the head breaks the 4-piece bonus (3 arrows)", ctx.computeOnClient(mc ->
                String.valueOf(call("com.killer560.hub.roomsim.SimArmor", "extraArrows"))), "0");
        // MaskSwapper's own swap, back from the inventory (Spirit Mask this time).
        giveMask(ctx, 11, "SPIRIT_MASK");
        Object target = ModUnderTest.enumValue("com.killer560.hub.maskinvincibility.MaskSwapper$Target", "SPIRIT");
        boolean started = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.maskinvincibility.MaskSwapper", "request",
                new Class<?>[]{target.getClass(), String.class}, new Object[]{target, "simwd"}));
        expect(f, "MaskSwapper starts a swap in the sim", started, true);
        for (int t = 0; t < 120 && !"SPIRIT_MASK".equals(head(ctx)); t += 5) ctx.waitTicks(5);
        expect(f, "MaskSwapper put the Spirit Mask on", head(ctx), "SPIRIT_MASK");
        expect(f, "the Bonzo's Mask went back to the inventory", invId(ctx, 11), "BONZO_MASK");
        for (int t = 0; t < 120 && "none".equals(title(ctx)) == false; t += 5) ctx.waitTicks(5);
        close(ctx);
        // Leave the sim loadout as the other cases expect.
        SimBossTests.serverRun(ctx, sv -> {
            var inv = sv.getPlayerList().getPlayers().get(0).getInventory();
            inv.setItem(10, ItemStack.EMPTY);
            inv.setItem(11, ItemStack.EMPTY);
            sv.getPlayerList().getPlayers().get(0).setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, ItemStack.EMPTY);
        });
    }

    static void run(ClientGameTestContext ctx, List<String> f) throws Exception {
        start(ctx, "S1", true);
        try {
            expect(f, "wardrobe opens in the sim", open(ctx, false), true);
            expect(f, "wardrobe title", title(ctx), "Wardrobe (1/2)");
            Map<String, String> w0 = sheet(ctx);
            println(NAME, "wardrobe: " + w0.entrySet().stream().filter(e -> e.getKey().contains("Armor") || e.getKey().equals("Stats")).toList());
            expect(f, "slot 1 (Terror) waits to be equipped", w0.get("Slot 1"), "Click to equip");
            expect(f, "slot 2 (Thermodynamic) waits to be equipped", w0.get("Slot 2"), "Click to equip");
            expect(f, "slot 3 is empty", w0.get("Slot 3"), "Empty");
            expect(f, "Terror's helmet sits in column 1, row 1", menuId(ctx, 0), "INFERNAL_TERROR_HELMET");
            expect(f, "Terror's boots sit in column 1, row 4", menuId(ctx, 27), "INFERNAL_TERROR_BOOTS");
            expect(f, "Thermodynamic's chestplate sits in column 2, row 2", menuId(ctx, 10), "THERMODYNAMIC_CHESTPLATE");
            expect(f, "no set in column 3", menuId(ctx, 2), "null");

            click(ctx, SET_SLOTS[1]);
            Map<String, String> w1 = sheet(ctx);
            expect(f, "click on slot 2 equips Thermodynamic in the menu", w1.get("Slot 2"), "Equipped");
            expect(f, "slot 1 is no longer marked", w1.get("Slot 1"), "Click to equip");
            expect(f, "the loadout now holds Thermodynamic", ctx.computeOnClient(mc -> String.valueOf(call("com.killer560.hub.roomsim.SimLoadout", "armor"))), "THERMODYNAMIC");
            close(ctx);
            String worn = ctx.computeOnClient(mc -> String.valueOf(ModUnderTest.staticCall("com.killer560.hub.cheatutils.CheatUtils", "skyblockId",
                    new Class<?>[]{ItemStack.class}, new Object[]{mc.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST)})));
            expect(f, "the chestplate is on", worn, "THERMODYNAMIC_CHESTPLATE");
            rates(ctx, f, "Thermodynamic", 4, 4, 4);

            // Stats of Thermodynamic, then swap to Terror from the wardrobe and read them again.
            expect(f, "stats menu opens", open(ctx, true), true);
            expect(f, "stats title", title(ctx), "Stats & Equipment");
            expect(f, "the stats menu has no stat numbers", sheet(ctx).containsKey("Attack Speed (effective)"), false);
            close(ctx);

            open(ctx, false);
            click(ctx, SET_SLOTS[0]);
            Map<String, String> w2 = sheet(ctx);
            expect(f, "Terror equipped from the menu", w2.get("Slot 1"), "Equipped");
            rates(ctx, f, "Terror", 5, 5, 4);
            close(ctx);
            expect(f, "the chestplate swapped", ctx.computeOnClient(mc -> String.valueOf(ModUnderTest.staticCall("com.killer560.hub.cheatutils.CheatUtils", "skyblockId",
                    new Class<?>[]{ItemStack.class}, new Object[]{mc.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST)}))), "INFERNAL_TERROR_CHESTPLATE");
            open(ctx, true);
            close(ctx);

            // The next bow shot follows the swap.
            int before = arrows(ctx);
            ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimTerminator", "use",
                    new Class<?>[]{Minecraft.class, boolean.class}, new Object[]{mc, false}));
            ctx.waitTicks(6);
            expect(f, "next shot fires the Terror 5 arrows", arrows(ctx) - before, 5);

            // Saved on disk and read back after a restart.
            ctx.runOnClient(mc -> call("com.killer560.hub.roomsim.SimLoadout", "reload"));
            expect(f, "reload (a restart) keeps Terror", ctx.computeOnClient(mc -> String.valueOf(call("com.killer560.hub.roomsim.SimLoadout", "armor"))), "TERROR");

            // Barrier button closes the wardrobe.
            open(ctx, false);
            click(ctx, 49);
            expect(f, "Close button closes the menu", title(ctx), "none");
            commandsAndMasks(ctx, f);
        } finally {
            ctx.runOnClient(mc -> mc.player.closeContainer());
            SimArmor1115.selectNone(ctx);
        }
    }
}
