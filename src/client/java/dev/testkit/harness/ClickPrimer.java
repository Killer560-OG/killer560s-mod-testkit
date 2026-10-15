package dev.testkit.harness;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Secret Aura, as Hypixel's server sees it, right before the next item use a mod sends: after EVERY item use this client
 * sends (while the selected hotbar slot is {@link #onlySlot}), at the end of that tick the BODY is turned to look straight
 * down - so the next movement packet tells the server a look that meets the floor, as an aura's turn to a chest does - and
 * a right click on a block is made through the game mode with the same item still in hand. A mod whose next use goes out
 * before the server has heard ITS look then has that use taken as the click's second half ({@link HypixelUseAfterClick});
 * one that turns first ({@code util/RightClick}) has it land.
 *
 * <p>The block is the floor 7 blocks east of him: past the integrated server's reach for a block click (4.5 + 1), so no block, lever
 * or ability answers it (no teleport, no chest, no door), but inside the 8 blocks a 1.8 server stores a click from, and its
 * handler is entered first, which is where {@link HypixelUseAfterClick} stores it; the click went through
 * {@code MultiPlayerGameMode.useItemOn}, which is where the mod takes it. Client thread only; off unless a scenario sets
 * {@link #enabled}.
 */
public final class ClickPrimer {

    public static volatile boolean enabled;
    /** Only while this hotbar slot is selected; -1 = any. */
    public static volatile int onlySlot = -1;
    /** Item uses seen going out, and clicks made after them, since the last {@link #reset}. */
    public static volatile int uses;
    public static volatile int primed;

    private static boolean installed;
    private static boolean pending;

    private ClickPrimer() {
    }

    public static void install() {
        if (installed) {
            return;
        }
        installed = true;
        ClientTickEvents.END_CLIENT_TICK.register(ClickPrimer::tick);
    }

    public static void reset() {
        enabled = false;
        onlySlot = -1;
        uses = 0;
        primed = 0;
        pending = false;
    }

    /** From {@code MixinOutboundPackets}, for every packet the client sends. */
    public static void onOutbound(Packet<?> packet) {
        if (enabled && packet instanceof ServerboundUseItemPacket) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && (onlySlot < 0 || mc.player.getInventory().getSelectedSlot() == onlySlot)) {
                uses++;
                pending = true;
            }
        }
    }

    private static void tick(Minecraft mc) {
        if (!pending) {
            return;
        }
        pending = false;
        if (!enabled || mc.player == null || mc.level == null || mc.gameMode == null) {
            return;
        }
        mc.player.setXRot(90f);
        BlockPos far = mc.player.blockPosition().offset(7, -1, 0);
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(far), Direction.UP, far, false));
        primed++;
    }
}
