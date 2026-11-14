package dev.testkit.server.mixin;

import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;

import io.netty.channel.ChannelHandlerContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Attack probe, network side: one log line per attack packet that reaches the server's connection (after any packet filter in front of it). */
@Mixin(Connection.class)
public class MixinAttackProbeNetty {

    private static final Logger TESTKIT$LOG = LoggerFactory.getLogger("testkit-atkprobe");

    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"))
    private void testkit$nettyAttack(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
        if (packet instanceof ServerboundAttackPacket) {
            TESTKIT$LOG.info("[atkprobe-net] attack packet reached the connection");
        }
    }
}
