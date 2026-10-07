package dev.testkit.mixin;

import dev.testkit.harness.WireCapture;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

import net.minecraft.network.PacketEncoder;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feeds {@link WireCapture} with the encoded bytes of every serverbound packet.
 *
 * <p>Target verified with javap against the 26.1.2 and 26.2 mapped jars:
 * {@code protected void encode(ChannelHandlerContext, Packet<T>, ByteBuf)} on {@code PacketEncoder}, with a
 * {@code private final ProtocolInfo<T> protocolInfo} field. The success path ends in a single {@code return}
 * after {@code ProtocolSwapHandler.handleOutboundTerminalPacket}, so TAIL sees the finished buffer: packet id
 * VarInt plus body, before the compress/encrypt/prepender handlers. {@code defaultRequire: 1} in this config, so a
 * wrong signature fails the launch instead of capturing nothing.
 */
@Mixin(PacketEncoder.class)
public abstract class MixinPacketEncoder {

    @Shadow
    @Final
    private ProtocolInfo<?> protocolInfo;

    @Inject(method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Lio/netty/buffer/ByteBuf;)V",
            at = @At("TAIL"))
    private void testkit$encoded(ChannelHandlerContext context, Packet<?> packet, ByteBuf out, CallbackInfo ci) {
        if (!WireCapture.armed()) {
            return;
        }
        try {
            if (protocolInfo.flow() != PacketFlow.SERVERBOUND) {
                return;
            }
            WireCapture.onEncoded(protocolInfo.id().id(), packet.type().id().toString(), packet, out);
        } catch (Throwable ignored) {
            // measuring must never break the run it is measuring
        }
    }
}
