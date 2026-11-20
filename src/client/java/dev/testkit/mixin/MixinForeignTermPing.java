package dev.testkit.mixin;

import dev.testkit.harness.ForeignTermFilter;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.common.ClientboundPingPacket;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@link ForeignTermFilter}: NoFrills counts server ticks by non-zero pings (TAIL: the render-thread pass only). */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class MixinForeignTermPing {

    @Inject(method = "handlePing", at = @At("TAIL"), require = 0)
    private void testkit$foreignTermPing(ClientboundPingPacket packet, CallbackInfo ci) {
        if (ForeignTermFilter.mode != ForeignTermFilter.Mode.OFF) {
            ForeignTermFilter.onPing(packet.getId());
        }
    }
}
