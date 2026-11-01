package dev.testkit.compat;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;

/**
 * Entity type constants, for 26.3 (as on 26.2), where {@code EntityType} has no constants left and they all live on
 * {@code EntityTypes} (javap'd on the 26.2 jar). Same constants as the 26.2 copy.
 */
public final class McEntities {

    private McEntities() {
    }

    public static final EntityType<net.minecraft.world.entity.animal.pig.Pig> PIG = EntityTypes.PIG;
    public static final EntityType<net.minecraft.world.entity.monster.zombie.Zombie> ZOMBIE = EntityTypes.ZOMBIE;

    // ---- added 2026-11-01 for the 26.3 port ----

    /** The enderman class (26.3: renamed {@code monster.Enderman}). */
    public static Class<? extends net.minecraft.world.entity.Mob> endermanClass() {
        return net.minecraft.world.entity.monster.Enderman.class;
    }

    /** Makes an entity immune to damage (26.3: {@code setPermanentlyInvulnerable}; setInvulnerable is gone). */
    public static void setInvulnerable(net.minecraft.world.entity.Entity e, boolean on) {
        e.setPermanentlyInvulnerable(on);
    }
}
