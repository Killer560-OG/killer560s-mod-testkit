package dev.testkit.mixin;

import dev.testkit.harness.VideoCamera;

import net.minecraft.client.Camera;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link VideoCamera}: after {@code Camera.alignWithEntity} has placed the camera on the camera entity (26.1.2, javap
 * 2026-10-20: {@code private void alignWithEntity(float)}, called from {@code update} before the FOV and the cull
 * frustum are worked out), put it at the video camera's pose instead and mark it detached, so the player model is
 * drawn. Only the picture changes. {@code require = 0}: a version without this method keeps booting.
 */
@Mixin(Camera.class)
public abstract class MixinVideoCamera {

    @Shadow
    private boolean detached;

    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    @Inject(method = "alignWithEntity", at = @At("TAIL"), require = 0)
    private void testkit$videoCamera(float partialTicks, CallbackInfo ci) {
        if (VideoCamera.active()) {
            setRotation(VideoCamera.yaw(), VideoCamera.pitch());
            setPosition(VideoCamera.x(), VideoCamera.y(), VideoCamera.z());
            detached = true;
        }
    }
}
