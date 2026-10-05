package dev.testkit.server.mixin;

import net.minecraft.world.entity.decoration.ArmorStand;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** {@code ArmorStand.setSmall} / {@code setMarker} are private in 26.1.2 (javap); Hx stands need both. */
@Mixin(ArmorStand.class)
public interface InvokerArmorStand {

    @Invoker("setSmall")
    void testkit$setSmall(boolean small);

    @Invoker("setMarker")
    void testkit$setMarker(boolean marker);
}
