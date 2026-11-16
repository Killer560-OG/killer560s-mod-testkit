package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.List;

import static dev.testkit.gametest.SimBossTests.call;
import static dev.testkit.gametest.SimBossTests.expect;
import static dev.testkit.gametest.SimBossTests.println;
import static dev.testkit.gametest.SimBossTests.serverRun;
import static dev.testkit.gametest.SimBossTests.start;

/**
 * {@code 1116-sim-masks} (simmasks-1116, killer560 2026-11-16: "add in bonzo mask, spirit mask, a fishing rod that swaps you
 * between phoenix and golden dragon"). Each saver acts on a real lethal hit: the player lives, the Hypixel chat line shows,
 * the mod's own mask timer starts from that line, and a second lethal hit inside the cooldown kills. The rod swaps the pet
 * with the Autopet line and Phoenix then saves once. Real item ids come from SimItems.build.
 */
final class SimMasks1116 {

    static final String NAME = "1116-sim-masks";
    private static final String MASKS = "com.killer560.hub.roomsim.SimMasks";
    private static final String ITEMS = "com.killer560.hub.roomsim.SimItems";
    private static final String SWAP = "com.killer560.hub.maskinvincibility.MaskSwapper$Target";
    private static final String FEATURE = "com.killer560.hub.maskinvincibility.MaskInvincibilityFeature";
    private static final String TIDY = "com.killer560.hub.chattidy.ChatTidy";

    private SimMasks1116() {
    }

    private static String id(ItemStack s) {
        return String.valueOf(ModUnderTest.staticCall("com.killer560.hub.cheatutils.CheatUtils", "skyblockId",
                new Class<?>[]{ItemStack.class}, new Object[]{s}));
    }

    @SuppressWarnings("unchecked")
    private static boolean said(ClientGameTestContext ctx, String line) {
        return ctx.computeOnClient(mc -> ((List<String>) ModUnderTest.staticCall(TIDY, "testChatLines",
                new Class<?>[]{int.class}, new Object[]{40})).contains(line));
    }

    /** One lethal hit on the server thread; returns whether the player is dead afterwards. */
    private static boolean lethal(ClientGameTestContext ctx) {
        boolean[] dead = new boolean[1];
        serverRun(ctx, sv -> {
            ServerPlayer p = sv.getPlayerList().getPlayers().get(0);
            p.hurtServer(p.level(), p.damageSources().generic(), 100000f);
            dead[0] = p.isDeadOrDying();
        });
        ctx.waitTicks(4);
        return dead[0];
    }

    private static void wear(ClientGameTestContext ctx, String skyblockId) {
        serverRun(ctx, sv -> {
            ServerPlayer p = sv.getPlayerList().getPlayers().get(0);
            p.setItemSlot(EquipmentSlot.HEAD, (ItemStack) ModUnderTest.staticCall(ITEMS, "build",
                    new Class<?>[]{String.class}, new Object[]{skyblockId}));
            p.setHealth(p.getMaxHealth());
        });
        ctx.waitTicks(4);
    }

    private static void reset(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> call(MASKS, "reset"));
        serverRun(ctx, sv -> sv.getPlayerList().getPlayers().get(0).setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY));
    }

    private static boolean onCooldown(ClientGameTestContext ctx, String target) {
        Object t = ModUnderTest.enumValue(SWAP, target);
        return ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(FEATURE, "onCooldown", new Class<?>[]{t.getClass()}, new Object[]{t}));
    }

    private static void mask(ClientGameTestContext ctx, List<String> f, String skyblockId, String target, String line) {
        reset(ctx);
        wear(ctx, skyblockId);
        expect(f, skyblockId + " is a player head with its skin", ctx.computeOnClient(mc -> {
            ItemStack h = mc.player.getItemBySlot(EquipmentSlot.HEAD);
            return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(h.getItem()) + "|" + (h.get(DataComponents.PROFILE) != null);
        }), "minecraft:player_head|true");
        expect(f, skyblockId + " no cooldown before a proc", onCooldown(ctx, target), false);
        expect(f, skyblockId + " saves from the first lethal hit (alive)", lethal(ctx), false);
        expect(f, skyblockId + " chat line", said(ctx, line), true);
        expect(f, "the mod's " + target + " timer started from the line", onCooldown(ctx, target), true);
        expect(f, skyblockId + " full health after the save", ctx.computeOnClient(mc -> mc.player.getHealth() > 1f), true);
        // Inside the 3 s window nothing hurts.
        expect(f, skyblockId + " invulnerable inside its window", lethal(ctx), false);
        ctx.waitTicks(70);
        expect(f, skyblockId + " second lethal hit inside its cooldown kills", lethal(ctx), true);
        ctx.runOnClient(mc -> mc.player.respawn());
        ctx.waitTicks(30);
    }

    static void run(ClientGameTestContext ctx, List<String> f) {
        start(ctx, "S1", true);
        mask(ctx, f, "BONZO_MASK", "BONZO", "Your Bonzo's Mask saved your life!");
        mask(ctx, f, "SPIRIT_MASK", "SPIRIT", "Second Wind Activated! Your Spirit Mask saved your life!");

        // Rod: toggles the pet with the Autopet line, Phoenix saves once.
        reset(ctx);
        expect(f, "the pet starts as Golden Dragon (not Phoenix)", ctx.computeOnClient(mc -> String.valueOf(call(MASKS, "phoenixOut"))), "false");
        serverRun(ctx, sv -> {
            ServerPlayer p = sv.getPlayerList().getPlayers().get(0);
            p.getInventory().setItem(0, (ItemStack) ModUnderTest.staticCall(ITEMS, "build", new Class<?>[]{String.class}, new Object[]{"FISHING_ROD"}));
            p.getInventory().setSelectedSlot(0);
        });
        ctx.waitTicks(6);
        expect(f, "the rod is the sim's FISHING_ROD", ctx.computeOnClient(mc -> id(mc.player.getInventory().getItem(0))), "FISHING_ROD");
        ctx.runOnClient(mc -> mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND));
        ctx.waitTicks(8);
        expect(f, "the rod toggles to Phoenix", ctx.computeOnClient(mc -> String.valueOf(call(MASKS, "phoenixOut"))), "true");
        expect(f, "Autopet line for Phoenix", said(ctx, "Autopet equipped your [Lvl 100] Phoenix! VIEW RULE"), true);
        expect(f, "Phoenix saves the first lethal hit", lethal(ctx), false);
        expect(f, "Phoenix chat line", said(ctx, "Your Phoenix Pet saved you from certain death!"), true);
        expect(f, "the mod's Phoenix timer started", onCooldown(ctx, "PHOENIX"), true);
        ctx.waitTicks(100);
        expect(f, "a second lethal hit inside Phoenix's cooldown kills", lethal(ctx), true);
        ctx.runOnClient(mc -> mc.player.respawn());
        ctx.waitTicks(20);
        reset(ctx);
        ctx.runOnClient(mc -> mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND));
        ctx.waitTicks(8);
        ctx.runOnClient(mc -> mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND));
        ctx.waitTicks(8);
        expect(f, "a second cast toggles back to Golden Dragon", said(ctx, "Autopet equipped your [Lvl 100] Golden Dragon! VIEW RULE"), true);
        println(NAME, "done");
    }
}
