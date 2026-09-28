package dev.testkit.mixin;

import dev.testkit.harness.PacketWatch;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * READ-ONLY tap on every packet this client sends, so a scenario can measure a module in the units a
 * server-side check uses rather than inferring them from behaviour.
 *
 * <p>Target verified with javap against the 26.1.2 mapped jar:
 * {@code public void send(net.minecraft.network.protocol.Packet<?>)} on
 * {@code ClientCommonPacketListenerImpl}, which {@code ClientPacketListener} inherits - so every
 * {@code connection.send(...)} in the game and in every loaded mod passes through here. This project's
 * mixin config uses {@code defaultRequire: 1}, so a wrong signature fails the launch instead of quietly
 * counting nothing.
 *
 * <p>At HEAD, nothing is cancelled and no packet is copied. Any throw is swallowed: this sits on the send
 * path of every packet the client produces, and a fault in a measuring tool must never be able to take
 * the connection down and turn itself into the test result.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class MixinOutboundPackets {

    @Inject(method = "send", at = @At("HEAD"))
    private void testkit$tapOutbound(Packet<?> packet, CallbackInfo ci) {
        try {
            PacketWatch.record(packet);
        } catch (Throwable ignored) {
            // measuring must never break the run it is measuring
        }
    }
}
