package dev.testkit.compat;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;

/**
 * Entity type constants, for 26.2, where {@code EntityType} has no constants left and they all live on
 * {@code EntityTypes} (javap'd on the 26.2 jar). Same constants as the 26.1 copy.
 */
public final class McEntities {

    private McEntities() {
    }

    public static final EntityType<net.minecraft.world.entity.animal.pig.Pig> PIG = EntityTypes.PIG;
    public static final EntityType<net.minecraft.world.entity.monster.zombie.Zombie> ZOMBIE = EntityTypes.ZOMBIE;
}
