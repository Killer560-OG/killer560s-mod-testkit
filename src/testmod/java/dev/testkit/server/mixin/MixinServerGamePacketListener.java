package dev.testkit.server.mixin;

import dev.testkit.server.hx.HxBridge;
import dev.testkit.server.hx.HxEvents;

import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records container clicks and closes, and held-slot changes ({@code held}), for Hx. Fabric has no event for any.
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

    /** 1027-iwl-*: moves at HEAD (before the server applies them: x/y/z in the event is where he WAS). */
    @Inject(method = "handleMovePlayer", at = @At("HEAD"))
    private void testkit$wireMove(net.minecraft.network.protocol.game.ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        if (HxEvents.wire && HxBridge.onServerThread()) {
            HxEvents.wire(player, "move", String.format(java.util.Locale.ROOT, "%s %.4f %.4f %.4f %s",
                    packet.hasPosition() ? "pos" : "nopos", packet.getX(Double.NaN), packet.getY(Double.NaN),
                    packet.getZ(Double.NaN), packet.isOnGround()));
        }
    }

    @Inject(method = "handlePlayerInput", at = @At("HEAD"))
    private void testkit$wireInput(net.minecraft.network.protocol.game.ServerboundPlayerInputPacket packet, CallbackInfo ci) {
        if (HxEvents.wire && HxBridge.onServerThread()) {
            HxEvents.wire(player, "input", dev.testkit.server.hx.HxPrimitives.inputText(packet.input()));
        }
    }

    @Inject(method = "handlePlayerCommand", at = @At("HEAD"))
    private void testkit$wireCommand(net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket packet, CallbackInfo ci) {
        if (HxEvents.wire && HxBridge.onServerThread()) {
            HxEvents.wire(player, "cmd", String.valueOf(packet.getAction()));
        }
    }

    @Inject(method = "handleSetCarriedItem", at = @At("HEAD"))
    private void testkit$recordHeld(ServerboundSetCarriedItemPacket packet, CallbackInfo ci) {
        if (HxBridge.onServerThread()) {
            HxEvents.held(player, packet.getSlot());
        }
    }
}
