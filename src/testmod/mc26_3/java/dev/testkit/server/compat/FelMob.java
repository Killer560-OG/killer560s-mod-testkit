package dev.testkit.server.compat;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.level.Level;

/**
 * Hypixel's Fel for the Hx dungeon module (simmobs2-1101), as the mod's sim builds it (roomsim/SimMobs.SimEnderman): an
 * enderman named Dinnerbone (name hidden), never teleporting, invisible and untouchable while dormant. Per version only
 * because the class is {@code monster.Enderman} on 26.3 and {@code monster.EnderMan} on 26.2; identical public API.
 */
public final class FelMob {

    private FelMob() {
    }

    /** A dormant Fel, not yet added to the level. */
    @SuppressWarnings("unchecked")
    public static Mob create(ServerLevel level) {
        var type = (EntityType<? extends Enderman>) BuiltInRegistries.ENTITY_TYPE.getValue(
                Identifier.parse("minecraft:enderman"));
        return new Fel(type, level);
    }

    public static boolean isFel(Entity e) {
        return e instanceof Fel;
    }

    /** Wakes (false) or puts to sleep (true) a {@link #create}d Fel. */
    public static void setDormant(Entity e, boolean dormant) {
        if (e instanceof Fel f) {
            f.dormant = dormant;
            f.setInvisible(dormant);
        }
    }

    private static final class Fel extends Enderman {
        volatile boolean dormant = true;

        Fel(EntityType<? extends Enderman> type, Level level) {
            super(type, level);
            setCustomName(Component.literal("Dinnerbone"));
            setCustomNameVisible(false);
        }

        @Override
        public void checkDespawn() {
        }

        @Override
        protected boolean teleport() {
            return false;
        }

        @Override
        protected void updateInvisibilityStatus() {
            if (dormant) {
                setInvisible(true);
                return;
            }
            super.updateInvisibilityStatus();
        }

        @Override
        public boolean isSensitiveToWater() {
            return false;
        }

        @Override
        public boolean isInvulnerableTo(ServerLevel level, DamageSource source) {
            return dormant || super.isInvulnerableTo(level, source);
        }

        @Override
        public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
            if (dormant) {
                return false;
            }
            return super.hurtServer(level, source, amount);
        }
    }
}
