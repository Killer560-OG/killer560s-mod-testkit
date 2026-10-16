package dev.testkit.mixin.bench;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import dev.testkit.harness.MixinBench;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;

/** {@link MixinBench} timers around per-entity extraction and culling (javap: same on 26.1.2 and 26.2). */
@Mixin(EntityRenderDispatcher.class)
public abstract class BenchEntityRenderDispatcher {

    @WrapMethod(method = "extractEntity(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;", require = 0)
    private EntityRenderState testkit$benchExtract(Entity e, float pt, Operation<EntityRenderState> original) {
        if (!MixinBench.enter(2)) {
            return original.call(e, pt);
        }
        try {
            return original.call(e, pt);
        } finally {
            MixinBench.exit(2);
        }
    }

    @WrapMethod(method = "shouldRender(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z", require = 0)
    private boolean testkit$benchShouldRender(Entity e, Frustum f, double x, double y, double z, Operation<Boolean> original) {
        if (!MixinBench.enter(3)) {
            return original.call(e, f, x, y, z);
        }
        try {
            return original.call(e, f, x, y, z);
        } finally {
            MixinBench.exit(3);
        }
    }
}
