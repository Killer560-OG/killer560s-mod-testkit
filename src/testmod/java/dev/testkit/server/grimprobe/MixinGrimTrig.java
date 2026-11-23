package dev.testkit.server.grimprobe;

import dev.testkit.server.TestKitServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * simstreak-1123: the anticheat guesses from sub-threshold offsets (1e-5..1e-3) whether a client moves with OptiFine's
 * fast trig table. Its guess reads the movement as if the player's speed were 0.1 (TrigHandler.setOffset), so at a raised
 * movement_speed (ee3route sets 0.6) the votes it collects over a session are noise; after a net five it switches the player
 * to fast math, and from then on every fast tick of a vanilla client is mispredicted by .001-.0024: a Simulation streak with
 * the same values run after run, until the votes switch it back. Every testkit client is vanilla, so the switch is held at
 * vanilla math ({@link TestKitServer#grimTrigPin}, `testkit grimtrig pin|auto`) and every switch, made or held, is logged
 * as {@code [testkit-trig]}. No anticheat name in the line.
 */
@Mixin(targets = "ac.grim.grimac.utils.math.TrigHandler", remap = false)
public class MixinGrimTrig {

    @Unique private static final Logger testkit$LOG = LoggerFactory.getLogger("testkit-trig");
    @Shadow private boolean isVanillaMath;
    @Unique private boolean testkit$wasVanilla = true;

    @Inject(method = "setOffset", at = @At("HEAD"), require = 0, remap = false)
    private void testkit$head(double offset, CallbackInfo ci) {
        testkit$wasVanilla = isVanillaMath;
    }

    @Inject(method = "setOffset", at = @At("RETURN"), require = 0, remap = false)
    private void testkit$ret(double offset, CallbackInfo ci) {
        if (isVanillaMath == testkit$wasVanilla) {
            return;
        }
        boolean held = TestKitServer.grimTrigPin && !isVanillaMath;
        if (held) {
            isVanillaMath = true;
        }
        testkit$LOG.info(String.format(java.util.Locale.ROOT, "[testkit-trig] trig switch %s -> %s at offset %.6f%s",
                testkit$wasVanilla ? "vanilla" : "fast", testkit$wasVanilla ? "fast" : "vanilla", offset,
                held ? " - held at vanilla (testkit clients are vanilla)" : ""));
    }
}
