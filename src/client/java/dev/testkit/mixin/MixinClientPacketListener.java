package dev.testkit.mixin;

import dev.testkit.harness.ChatWatch;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records every chat line the server sends.
 *
 * <p>An anticheat's verbose output is ordinary chat, so this is how a scenario can read what the server
 * said rather than inferring it from behaviour. Both flavours are hooked because a server is free to use
 * either. The harness reads flags from the server console rather than from here — chat only reaches
 * players with the alerts permission, and granting the test player that permission also makes it exempt —
 * but this stays because a scenario often wants the server's replies to its own commands.
 */
@Mixin(ClientPacketListener.class)
public class MixinClientPacketListener {

    @Inject(method = "handleSystemChat", at = @At("HEAD"))
    private void testkit$systemChat(ClientboundSystemChatPacket packet, CallbackInfo ci) {
        ChatWatch.record(packet.content());
    }

    @Inject(method = "handleDisguisedChat", at = @At("HEAD"))
    private void testkit$disguisedChat(ClientboundDisguisedChatPacket packet, CallbackInfo ci) {
        ChatWatch.record(packet.message());
    }

    /**
     * {@link dev.testkit.harness.HypixelTileRegister}. {@code public void handleBlockUpdate(ClientboundBlockUpdatePacket)}
     * - javap on the 26.1.2 and 26.2 merged jars, the same on both. Entered first on the network thread (rescheduled) and
     * then on the render thread; only the second is looked at.
     */
    @Inject(method = "handleBlockUpdate", at = @At("HEAD"), cancellable = true)
    private void testkit$tileRegister(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        if (dev.testkit.harness.HypixelTileRegister.delayTicks > 0
                && net.minecraft.client.Minecraft.getInstance().isSameThread()
                && dev.testkit.harness.HypixelTileRegister.hold(packet.getPos(), packet.getBlockState())) {
            ci.cancel();
        }
    }
}
