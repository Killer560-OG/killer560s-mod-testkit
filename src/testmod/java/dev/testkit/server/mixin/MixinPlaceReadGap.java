package dev.testkit.server.mixin;

import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Probe (ap3-blockuse-1027): how far apart the SERVER reads a use-on-block (a place) and the movement packet after it.
 * GrimAC 2.3.74 lends a place that next packet's rotation only when its own {@code System.currentTimeMillis()} at the
 * two reads differs by less than 15 ms ({@code CheckManagerListener.handleQueuedPlaces}). Both handlers are first
 * called on the netty thread (they throw there to hop to the server thread), right after Grim's own read of the same
 * packet in the same pipeline pass - that first call is timed here. One line per place, in the server log:
 * {@code [testkit-probe] place read -> next move read: N.NN ms (currentTimeMillis delta M)}.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class MixinPlaceReadGap {

    @Unique private static final Logger testkit$LOG = LoggerFactory.getLogger("testkit-probe");
    @Unique private long testkit$placeNanos = -1L;
    @Unique private long testkit$placeMillis;

    @Inject(method = "handleUseItemOn", at = @At("HEAD"))
    private void testkit$placeRead(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
        if (!Thread.currentThread().getName().startsWith("Server thread")) {
            testkit$placeNanos = System.nanoTime();
            testkit$placeMillis = System.currentTimeMillis();
        }
    }

    @Inject(method = "handleMovePlayer", at = @At("HEAD"))
    private void testkit$moveRead(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        if (testkit$placeNanos < 0L || Thread.currentThread().getName().startsWith("Server thread")) {
            return;
        }
        long gapNanos = System.nanoTime() - testkit$placeNanos;
        long gapMillis = System.currentTimeMillis() - testkit$placeMillis;
        testkit$placeNanos = -1L;
        testkit$LOG.info(String.format(java.util.Locale.ROOT,
                "[testkit-probe] place read -> next move read: %.2f ms (currentTimeMillis delta %d%s)",
                gapNanos / 1e6, gapMillis, gapMillis >= 15 ? ", GrimAC judges by the rotation before" : ""));
    }
}
