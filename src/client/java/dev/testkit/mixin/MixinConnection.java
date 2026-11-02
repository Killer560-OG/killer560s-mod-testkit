package dev.testkit.mixin;

import dev.testkit.harness.PacketTrace;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;

import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feeds {@link PacketTrace}.
 *
 * <p>{@code send(Packet, ChannelFutureListener, boolean)} is the one overload the other two call (26.1.2 bytecode),
 * so every packet the client sends passes it once, on the thread that sent it. {@code channelRead0(ctx, Packet)}
 * is the connection's own inbound handler, which receives each decoded packet once, before it is dispatched —
 * unlike a packet listener's {@code handle…} methods, which run twice for anything bounced onto the main thread.
 */
@Mixin(Connection.class)
public class MixinConnection {

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
            at = @At("HEAD"))
    private void testkit$sent(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
        PacketTrace.onSent(packet);
        dev.testkit.harness.DigFlushProbe.onQueued(packet);
    }

    /** The write on the event loop (multibreak-1102 dig-gap probe): every packet is written and flushed on its own. */
    @Inject(method = "doSendPacket(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
            at = @At("HEAD"), require = 0)
    private void testkit$written(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
        dev.testkit.harness.DigFlushProbe.onWritten(packet, flush);
    }

    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
            at = @At("HEAD"))
    private void testkit$received(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
        PacketTrace.onReceived(packet);
    }
}
