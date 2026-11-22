package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.List;

import static dev.testkit.gametest.SimBossTests.call;
import static dev.testkit.gametest.SimBossTests.expect;
import static dev.testkit.gametest.SimBossTests.start;
import static dev.testkit.gametest.SimWardrobe1115.click;
import static dev.testkit.gametest.SimWardrobe1115.close;
import static dev.testkit.gametest.SimWardrobe1115.cmd;
import static dev.testkit.gametest.SimWardrobe1115.giveMask;
import static dev.testkit.gametest.SimWardrobe1115.head;
import static dev.testkit.gametest.SimWardrobe1115.invId;
import static dev.testkit.gametest.SimWardrobe1115.menuId;
import static dev.testkit.gametest.SimWardrobe1115.open;

/**
 * {@code 1120-sim-wardrobe-keep} (wardrobekeep-1120, killer560 2026-11-20: "in hypixel the two would be their own sets. if i
 * trade out a helmet from one and then close and change it over then it keeps that new set. that is what I want to imitate
 * ... so we can help people learn mask managment"). Terror is worn, a Bonzo's Mask goes on by hand (the Terror helmet lands in
 * the inventory), Thermodynamic is worn from /wardrobe, and Terror comes back through Loadout Keybinds' Open, Swap, Close
 * holding the mask, its helmet still in the inventory. Mask Invincibility's MaskSwapper then puts a Spirit Mask on, and after
 * Thermodynamic and back the Terror slot holds the Spirit Mask, also after a reload of the saved loadout. Every check reads
 * the worn armor, the inventory or the menu. An origin jar fails: its wardrobe wears Terror's own helmet again and the mask
 * is gone.
 */
final class SimWardrobeKeep1120 {

    static final String NAME = "1120-sim-wardrobe-keep";
    private static final String LOADOUT = "com.killer560.hub.roomsim.SimLoadout";
    private static final String SWAPPER = "com.killer560.hub.loadoutkeybinds.LoadoutSwapper";
    private static final String UTIL = "com.killer560.hub.cheatutils.CheatUtils";
    private static final int TERROR_DYE = 36, THERMO_DYE = 37;

    private SimWardrobeKeep1120() {
    }

    private static String worn(ClientGameTestContext ctx, EquipmentSlot slot) {
        return ctx.computeOnClient(mc -> String.valueOf(ModUnderTest.staticCall(UTIL, "skyblockId",
                new Class<?>[]{ItemStack.class}, new Object[]{mc.player.getItemBySlot(slot)})));
    }

    private static String armor(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> String.valueOf(call(LOADOUT, "armor")));
    }

    private static void waitHead(ClientGameTestContext ctx, String id) {
        for (int t = 0; t < 100 && !id.equals(head(ctx)); t += 5) ctx.waitTicks(5);
    }

    /** Loadout Keybinds' Open, Swap, Close to loadout {@code n}, as the key does, until it is done. */
    private static void swapper(ClientGameTestContext ctx, List<String> f, int n) {
        boolean started = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SWAPPER, "requestLoadout",
                new Class<?>[]{Minecraft.class, int.class}, new Object[]{mc, n}));
        expect(f, "Open, Swap, Close starts for loadout " + n, started, true);
        for (int t = 0; t < 120 && ctx.computeOnClient(mc -> (Boolean) call(SWAPPER, "isBusy")); t += 5) ctx.waitTicks(5);
        ctx.waitTicks(8);
        expect(f, "Open, Swap, Close clicked loadout " + n, ctx.computeOnClient(mc -> (Boolean) call(SWAPPER, "lastSwapped")), true);
    }

    private static String keptHelmet(ClientGameTestContext ctx, String set) {
        Object v = ModUnderTest.enumValue("com.killer560.hub.roomsim.SimArmor", set);
        return ctx.computeOnClient(mc -> String.valueOf(ModUnderTest.staticCall(LOADOUT, "keptId",
                new Class<?>[]{v.getClass(), int.class}, new Object[]{v, 0})));
    }

    static void run(ClientGameTestContext ctx, List<String> f) throws Exception {
        start(ctx, "S1", true);
        try {
            SimArmor1115.selectNone(ctx);
            for (String[] sp : new String[][]{{"TERROR", "1"}, {"THERMODYNAMIC", "2"}}) {
                Object v = ModUnderTest.enumValue("com.killer560.hub.roomsim.SimArmor", sp[0]);
                ctx.runOnClient(mc -> ModUnderTest.staticCall(LOADOUT, "setSpot", new Class<?>[]{v.getClass(), int.class}, new Object[]{v, Integer.parseInt(sp[1])}));
            }
            for (int s : new int[]{10, 11}) {
                SimBossTests.serverRun(ctx, sv -> sv.getPlayerList().getPlayers().get(0).getInventory().setItem(s, ItemStack.EMPTY));
            }
            ctx.waitTicks(4);

            // Terror on from /wardrobe, then a Bonzo's Mask on by hand: the Terror helmet goes to the inventory.
            open(ctx, false);
            click(ctx, TERROR_DYE);
            close(ctx);
            waitHead(ctx, "INFERNAL_TERROR_HELMET");
            expect(f, "Terror is worn", head(ctx), "INFERNAL_TERROR_HELMET");
            giveMask(ctx, 10, "BONZO_MASK");
            cmd(ctx, "stats");
            click(ctx, 54 + 1);
            close(ctx);
            expect(f, "the Bonzo's Mask is on by hand", head(ctx), "BONZO_MASK");
            expect(f, "the displaced Terror helmet is in the inventory", invId(ctx, 10), "INFERNAL_TERROR_HELMET");

            // Wear Thermodynamic: slot 1 keeps the mask, the helmet stays in the inventory.
            open(ctx, false);
            expect(f, "the worn slot 1 shows the mask on the head", menuId(ctx, 0), "BONZO_MASK");
            click(ctx, THERMO_DYE);
            close(ctx);
            waitHead(ctx, "THERMODYNAMIC_HELMET");
            expect(f, "Thermodynamic is worn whole", head(ctx) + "/" + worn(ctx, EquipmentSlot.CHEST), "THERMODYNAMIC_HELMET/THERMODYNAMIC_CHESTPLATE");
            expect(f, "the Terror helmet is still in the inventory", invId(ctx, 10), "INFERNAL_TERROR_HELMET");
            expect(f, "slot 1 now keeps the mask", keptHelmet(ctx, "TERROR"), "BONZO_MASK");
            open(ctx, false);
            expect(f, "the wardrobe's slot 1 column shows the mask", menuId(ctx, 0), "BONZO_MASK");
            expect(f, "the wardrobe's slot 1 column keeps Terror's chestplate", menuId(ctx, 9), "INFERNAL_TERROR_CHESTPLATE");
            close(ctx);

            // Back to Terror through Loadout Keybinds against the sim's /loadouts: the set comes back with the mask.
            swapper(ctx, f, 1);
            waitHead(ctx, "BONZO_MASK");
            expect(f, "Terror came back with the mask it was left with", head(ctx), "BONZO_MASK");
            expect(f, "and its own chestplate", worn(ctx, EquipmentSlot.CHEST), "INFERNAL_TERROR_CHESTPLATE");
            expect(f, "the loadout says Terror", armor(ctx), "TERROR");
            expect(f, "the Terror helmet was not duplicated onto the head and is still in the inventory", invId(ctx, 10), "INFERNAL_TERROR_HELMET");
            expect(f, "the mask breaks the 4-piece bonus", ctx.computeOnClient(mc ->
                    String.valueOf(call("com.killer560.hub.roomsim.SimArmor", "extraArrows"))), "0");

            // MaskSwapper puts a Spirit Mask on; after Thermodynamic and back, Terror holds the Spirit Mask.
            giveMask(ctx, 11, "SPIRIT_MASK");
            Object target = ModUnderTest.enumValue("com.killer560.hub.maskinvincibility.MaskSwapper$Target", "SPIRIT");
            boolean started = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.maskinvincibility.MaskSwapper", "request",
                    new Class<?>[]{target.getClass(), String.class}, new Object[]{target, "wdkeep"}));
            expect(f, "MaskSwapper starts in the sim", started, true);
            waitHead(ctx, "SPIRIT_MASK");
            for (int t = 0; t < 100 && ctx.computeOnClient(mc -> (Boolean) call("com.killer560.hub.maskinvincibility.MaskSwapper", "isBusy")); t += 5) ctx.waitTicks(5);
            ctx.runOnClient(mc -> mc.player.closeContainer());
            ctx.waitTicks(4);
            expect(f, "MaskSwapper put the Spirit Mask on", head(ctx), "SPIRIT_MASK");
            expect(f, "the Bonzo's Mask went to the inventory", invId(ctx, 11), "BONZO_MASK");
            swapper(ctx, f, 2);
            waitHead(ctx, "THERMODYNAMIC_HELMET");
            expect(f, "Thermodynamic again", head(ctx), "THERMODYNAMIC_HELMET");
            expect(f, "slot 1 keeps the Spirit Mask", keptHelmet(ctx, "TERROR"), "SPIRIT_MASK");
            ctx.runOnClient(mc -> call(LOADOUT, "reload"));
            expect(f, "the kept Spirit Mask survives a reload of the saved loadout", keptHelmet(ctx, "TERROR"), "SPIRIT_MASK");
            open(ctx, false);
            click(ctx, TERROR_DYE);
            close(ctx);
            waitHead(ctx, "SPIRIT_MASK");
            expect(f, "Terror from /wardrobe comes back with the Spirit Mask", head(ctx), "SPIRIT_MASK");
            expect(f, "Thermodynamic's slot was left whole", keptHelmet(ctx, "THERMODYNAMIC"), "THERMODYNAMIC_HELMET");
        } finally {
            ctx.runOnClient(mc -> mc.player.closeContainer());
            SimArmor1115.selectNone(ctx);
            SimBossTests.serverRun(ctx, sv -> {
                var p = sv.getPlayerList().getPlayers().get(0);
                p.getInventory().setItem(10, ItemStack.EMPTY);
                p.getInventory().setItem(11, ItemStack.EMPTY);
                p.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
            });
        }
    }
}
