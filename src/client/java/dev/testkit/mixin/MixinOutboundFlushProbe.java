package dev.testkit.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Dig-gap probe (rbcause-1102): every netty flush of the client's outbound buffer - who flushed a dig written unflushed. */
@Mixin(targets = "io.netty.channel.ChannelOutboundBuffer", remap = false)
public class MixinOutboundFlushProbe {

    @Inject(method = "addFlush", at = @At("HEAD"), require = 0, remap = false)
    private void testkit$addFlush(CallbackInfo ci) {
        dev.testkit.harness.DigFlushProbe.onNettyFlush();
    }
}
