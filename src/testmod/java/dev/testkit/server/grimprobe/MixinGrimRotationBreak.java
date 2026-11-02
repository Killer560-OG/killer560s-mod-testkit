package dev.testkit.server.grimprobe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Probe (multibreak-1102): what the anticheat's RotationBreak traces each dig with (2.3.74: the block's cube from the
 * player's x/y/z at his eye heights along (lastYaw, pitch), (yaw, pitch), (lastYaw, lastPitch)), at the moment it judges
 * it - post-flying from {@code handleQueuedBreaks} (x/y/z set to the last claimed position), and on arrival (pre).
 * Lines {@code [testkit-dig] judge ...}, enough to replay the ray offline. No anticheat name in the line.
 */
@Mixin(targets = "ac.grim.grimac.checks.impl.breaking.RotationBreak", remap = false)
public class MixinGrimRotationBreak {

    @Unique private static final Logger testkit$LOG = LoggerFactory.getLogger("testkit-dig");
    @Unique private static java.lang.reflect.Field testkit$player;
    @Unique private static java.lang.reflect.Field testkit$position;
    @Unique private static java.lang.reflect.Field testkit$action;
    @Unique private static boolean testkit$warned;

    @Inject(method = "onPostFlyingBlockBreak", at = @At("HEAD"), require = 0, remap = false)
    private void testkit$post(@Coerce Object blockBreak, CallbackInfo ci) {
        testkit$log("post", blockBreak);
    }

    @Inject(method = "onBlockBreak", at = @At("HEAD"), require = 0, remap = false)
    private void testkit$pre(@Coerce Object blockBreak, CallbackInfo ci) {
        testkit$log("arrival", blockBreak);
    }

    @Unique
    private static void testkit$log(String when, Object bb) {
        try {
            if (testkit$player == null) {
                Class<?> c = bb.getClass();
                testkit$player = c.getDeclaredField("player");
                testkit$player.setAccessible(true);
                testkit$position = c.getDeclaredField("position");
                testkit$action = c.getDeclaredField("action");
            }
            GrimPlayerAccess p = (GrimPlayerAccess) testkit$player.get(bb);
            testkit$LOG.info(String.format(java.util.Locale.ROOT,
                    "[testkit-dig] judge %s: %s %s from %.4f %.4f %.4f, yaw %.3f pitch %.3f, lastYaw %.3f lastPitch %.3f,"
                            + " t %d", when, testkit$action.get(bb), testkit$position.get(bb), p.testkit$x(), p.testkit$y(),
                    p.testkit$z(), p.testkit$yaw(), p.testkit$pitch(), p.testkit$lastYaw(), p.testkit$lastPitch(),
                    System.nanoTime() / 1000));
        } catch (Throwable t) {
            if (!testkit$warned) {
                testkit$warned = true;
                testkit$LOG.warn("[testkit-dig] judge probe unavailable: " + t);
            }
        }
    }
}
