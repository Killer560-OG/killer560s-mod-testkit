package dev.testkit.mixin.probe;

import dev.testkit.harness.DrawCalls;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link DrawCalls}: one count per {@code RenderType.draw} out of an immediate buffer source, i.e. per draw call a
 * batch end makes (javap 26.1.2: the private {@code endBatch(RenderType, BufferBuilder)} builds the mesh and, when it is
 * not empty, calls {@code RenderType.draw}). Read-only. {@code @Pseudo} by name because 26.2 has no
 * {@code MultiBufferSource}; there it simply does not apply.
 */
@Pseudo
@Mixin(targets = "net.minecraft.client.renderer.MultiBufferSource$BufferSource", remap = false)
public abstract class ProbeBufferSourceDraws {

    @Inject(method = "endBatch(Lnet/minecraft/client/renderer/rendertype/RenderType;Lcom/mojang/blaze3d/vertex/BufferBuilder;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/rendertype/RenderType;draw(Lcom/mojang/blaze3d/vertex/MeshData;)V"),
            require = 0, remap = false)
    private void testkit$draw(CallbackInfo ci) {
        DrawCalls.onDraw();
    }
}
