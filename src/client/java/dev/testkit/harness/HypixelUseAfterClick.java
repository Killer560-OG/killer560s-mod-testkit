package dev.testkit.harness;

import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Hypixel ignores a use of the item he has just right-clicked a block with, for the integrated server.
 *
 * <p>killer560's Mod Only Test log of a real Hypixel run (2026-10-14 17:22-17:29, Red Blue and Raccoon): six Auto Routes
 * etherwarps sent one client tick after a chest or lever click met their await, the AOTV already in hand, got "no
 * landing after 61 ticks" with no chat line; the one sent a tick after a click made with another item (a hotbar swap in
 * the same tick) landed, as did every one a tick after a left-click skip. A vanilla integrated server (and a mod jar
 * whose own sim predates the rule) answers that use, so without this hook no jar shows the stall.
 *
 * <p>Fed by {@code dev.testkit.mixin.MixinServerGamePacketListenerUse} at HEAD of {@code handleUseItemOn} /
 * {@code handleUseItem}, on the server thread. A use packet of the same item (same item and components) the server takes
 * within {@link #DROP_TICKS} of its ticks after the block click is acknowledged and otherwise ignored. Off unless a
 * scenario sets {@link #enabled}; {@link #reset} turns it off again.
 */
public final class HypixelUseAfterClick {

    /** Server ticks after a block click during which a use of the same item is ignored (the mod's sim uses the same). */
    public static final int DROP_TICKS = 2;

    public static volatile boolean enabled;
    /** Block clicks seen, and uses ignored, since the last {@link #reset}. */
    public static volatile int clicks;
    public static volatile int dropped;
    /** Server tick gap of the last ignored use, and of the last use let through after a click (-1: none). */
    public static volatile int lastDroppedGap = -1;
    public static volatile int lastPassedGap = -1;

    private static int clickTick = Integer.MIN_VALUE / 2;
    private static ItemStack clickItem = ItemStack.EMPTY;

    private HypixelUseAfterClick() {
    }

    public static void reset() {
        enabled = false;
        clicks = 0;
        dropped = 0;
        lastDroppedGap = -1;
        lastPassedGap = -1;
        clickTick = Integer.MIN_VALUE / 2;
        clickItem = ItemStack.EMPTY;
    }

    /** Server thread: a block click arrived. */
    public static void onUseItemOn(ServerPlayer sp, ServerboundUseItemOnPacket packet) {
        if (!enabled || sp == null) {
            return;
        }
        clicks++;
        clickTick = sp.level().getServer().getTickCount();
        clickItem = sp.getItemInHand(packet.getHand()).copy();
    }

    /** Server thread: a use arrived. True when it must be ignored. */
    public static boolean dropUse(ServerPlayer sp, ServerboundUseItemPacket packet) {
        if (!enabled || sp == null || clickItem.isEmpty()) {
            return false;
        }
        ItemStack held = sp.getItemInHand(packet.getHand());
        int gap = sp.level().getServer().getTickCount() - clickTick;
        if (gap < 0 || gap > 40 || !ItemStack.isSameItemSameComponents(held, clickItem)) {
            return false;
        }
        if (gap > DROP_TICKS) {
            lastPassedGap = gap;
            return false;
        }
        dropped++;
        lastDroppedGap = gap;
        System.out.println("[HypixelUseAfterClick] use ignored: " + gap + " server tick(s) after a block click with the same item");
        return true;
    }
}
