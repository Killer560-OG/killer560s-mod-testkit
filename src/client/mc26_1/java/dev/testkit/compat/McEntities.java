package dev.testkit.compat;

import net.minecraft.world.entity.EntityType;

/**
 * Entity type constants, for 26.1.x, where they live on {@code EntityType}. 26.2 moves every one to
 * {@code EntityTypes}. Same constants in both copies; the generic parameter is spelled out so a caller's
 * {@code create(...)} keeps its real type.
 */
public final class McEntities {

    private McEntities() {
    }

    public static final EntityType<net.minecraft.world.entity.animal.pig.Pig> PIG = EntityType.PIG;
    public static final EntityType<net.minecraft.world.entity.monster.zombie.Zombie> ZOMBIE = EntityType.ZOMBIE;
}
