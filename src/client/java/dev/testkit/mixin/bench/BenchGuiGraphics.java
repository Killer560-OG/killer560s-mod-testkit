package dev.testkit.mixin.bench;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.pipeline.RenderPipeline;

import dev.testkit.harness.MixinBench;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

import org.spongepowered.asm.mixin.Mixin;

/** {@link MixinBench} timers around the GUI draw calls the mod hooks per call (javap: same on 26.1.2 and 26.2). */
@Mixin(GuiGraphicsExtractor.class)
public abstract class BenchGuiGraphics {

    @WrapMethod(method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)V", require = 0)
    private void testkit$benchText(Font font, FormattedCharSequence s, int x, int y, int c, boolean shadow, Operation<Void> original) {
        if (!MixinBench.enter(4)) {
            original.call(font, s, x, y, c, shadow);
            return;
        }
        try {
            original.call(font, s, x, y, c, shadow);
        } finally {
            MixinBench.exit(4);
        }
    }

    @WrapMethod(method = "blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V", require = 0)
    private void testkit$benchSprite(RenderPipeline p, Identifier id, int x, int y, int w, int h, Operation<Void> original) {
        if (!MixinBench.enter(5)) {
            original.call(p, id, x, y, w, h);
            return;
        }
        try {
            original.call(p, id, x, y, w, h);
        } finally {
            MixinBench.exit(5);
        }
    }

    @WrapMethod(method = "fill(Lcom/mojang/blaze3d/pipeline/RenderPipeline;IIIII)V", require = 0)
    private void testkit$benchFill(RenderPipeline p, int x0, int y0, int x1, int y1, int c, Operation<Void> original) {
        if (!MixinBench.enter(6)) {
            original.call(p, x0, y0, x1, y1, c);
            return;
        }
        try {
            original.call(p, x0, y0, x1, y1, c);
        } finally {
            MixinBench.exit(6);
        }
    }
}
