package dev.testkit.mixin;

import dev.testkit.harness.HypixelUseAfterClick;

import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HypixelUseAfterClick}. {@code public void handleUseItemOn(ServerboundUseItemOnPacket)} and {@code public void
 * handleUseItem(ServerboundUseItemPacket)} - javap on the 26.1.2 and 26.2 merged jars, the same on both. Each is entered
 * twice, first on the network thread (which reschedules it onto the server thread) and then on the server thread; only
 * the second is looked at.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class MixinServerGamePacketListenerUse {

    @Inject(method = "handleUseItemOn", at = @At("HEAD"))
    private void testkit$recordClick(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
        ServerGamePacketListenerImpl self = (ServerGamePacketListenerImpl) (Object) this;
        if (HypixelUseAfterClick.enabled && self.player != null && self.player.level().getServer().isSameThread()) {
            HypixelUseAfterClick.onUseItemOn(self.player, packet);
        }
    }

    /** {@code public void handleMovePlayer(ServerboundMovePlayerPacket)} (javap 26.1.2 and 26.2): a movement packet ends
     *  the client tick a right click's two halves are sent in. */
    @Inject(method = "handleMovePlayer", at = @At("HEAD"))
    private void testkit$move(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        ServerGamePacketListenerImpl self = (ServerGamePacketListenerImpl) (Object) this;
        if (HypixelUseAfterClick.enabled && self.player != null && self.player.level().getServer().isSameThread()) {
            HypixelUseAfterClick.onMove();
        }
    }

    @Inject(method = "handleUseItem", at = @At("HEAD"), cancellable = true)
    private void testkit$dropUse(ServerboundUseItemPacket packet, CallbackInfo ci) {
        ServerGamePacketListenerImpl self = (ServerGamePacketListenerImpl) (Object) this;
        if (HypixelUseAfterClick.enabled && self.player != null && self.player.level().getServer().isSameThread()
                && HypixelUseAfterClick.dropUse(self.player, packet)) {
            self.ackBlockChangesUpTo(packet.getSequence());
            ci.cancel();
        }
    }
}
