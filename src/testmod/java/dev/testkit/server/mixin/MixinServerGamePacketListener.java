package dev.testkit.server.mixin;

import dev.testkit.server.hx.HxBridge;
import dev.testkit.server.hx.HxEvents;

import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records container clicks and closes for Hx. Fabric has no event for either.
 *
 * <p>Both handlers begin by hopping to the server thread (the first call, on the netty thread, throws to reschedule
 * itself), so HEAD runs twice per packet; only the server-thread pass is recorded.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class MixinServerGamePacketListener {

    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleContainerClick", at = @At("HEAD"))
    private void testkit$recordClick(ServerboundContainerClickPacket packet, CallbackInfo ci) {
        if (HxBridge.onServerThread()) {
            HxEvents.containerClick(player, packet.containerId(), packet.slotNum(), packet.buttonNum(),
                    packet.containerInput().name());
        }
    }

    @Inject(method = "handleContainerClose", at = @At("HEAD"))
    private void testkit$recordClose(ServerboundContainerClosePacket packet, CallbackInfo ci) {
        if (HxBridge.onServerThread()) {
            HxEvents.containerClose(player, packet.getContainerId());
        }
    }
}
