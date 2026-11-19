package dev.testkit.mixin;

import dev.testkit.harness.ForeignTermFilter;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@link ForeignTermFilter}: a terminal opens (TAIL, so only the render-thread pass after the screen is set). */
@Mixin(ClientPacketListener.class)
public abstract class MixinForeignTermPackets {

    @Inject(method = "handleOpenScreen", at = @At("TAIL"), require = 0)
    private void testkit$foreignTermOpen(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
        if (ForeignTermFilter.mode != ForeignTermFilter.Mode.OFF) {
            ForeignTermFilter.onOpen(packet.getContainerId(), packet.getTitle().getString());
        }
    }
}
