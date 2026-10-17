package dev.testkit.server.mixin;

import dev.testkit.server.hx.menu.HxSigns;

import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.FilteredText;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Hands a sign update for one of Hx's fake sign prompts to {@link HxSigns} before vanilla looks for a real sign. The
 * target is the private {@code updateSignText(ServerboundSignUpdatePacket, List<FilteredText>)}, which
 * {@code handleSignUpdate} runs on the server thread after text filtering (javap, 26.1.2 and 26.2 alike).
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class MixinServerSignUpdate {

    @Shadow
    public ServerPlayer player;

    @Inject(method = "updateSignText", at = @At("HEAD"), cancellable = true)
    private void testkit$hxSign(ServerboundSignUpdatePacket packet, List<FilteredText> lines, CallbackInfo ci) {
        if (HxSigns.onSignUpdate(player, packet.getPos(), packet.getLines())) {
            ci.cancel();
        }
    }
}
