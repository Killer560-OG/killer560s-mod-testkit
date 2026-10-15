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
 * Makes a same-item block click land right before the next item use a mod sends: after EVERY item use this client sends
 * (while the selected hotbar slot is {@link #onlySlot}), a right click on a block is made through the game mode at the end
 * of that tick, with the same item still in hand. A mod that chains uses a tick apart (the Interactive Map's hops, Auto
 * Clear's trips) then has its next use due one tick after a block click, which Hypixel ignores
 * ({@link HypixelUseAfterClick}); one that waits ({@code util/BlockClickGap}) sends it later and it lands.
 *
 * <p>The block is 30 blocks east of him: the integrated server's reach check drops the click before any block, lever or
 * ability answers it (so no teleport, no chest, no door), but its handler is entered first, which is where
 * {@link HypixelUseAfterClick} takes the click, and the click went through {@code MultiPlayerGameMode.useItemOn}, which is
 * where the mod takes it. Client thread only; off unless a scenario sets {@link #enabled}.
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
        BlockPos far = mc.player.blockPosition().offset(30, 0, 0);
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(far), Direction.UP, far, false));
        primed++;
    }
}
