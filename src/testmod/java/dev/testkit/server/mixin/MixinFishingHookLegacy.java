package dev.testkit.server.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

/**
 * Hypixel's fishing hook is the 1.8 one, and the Carnival's Catch a Fish is played with it (carnfix3-1114, his 11-14
 * game). For a player tagged {@code legacyhook} the test server runs that hook instead of the modern one: it leaves the
 * eye 0.1 lower and 0.16 to the side at 1.5 blocks a tick along the look, keeps 92% of its speed a tick, floats by the
 * part of its 0.25 box under the water surface (0.04 x (2 x part - 1) up, then 90% and 80% in water), so it splashes and
 * glides on 3.5-4 blocks, and it hooks ANY entity its path crosses (box grown 0.3) in the air or on the water, as the 1.8
 * hook does every tick it is not stuck, and a fish that swims onto the floating hook (his games catch that way too). The
 * modern hook hooks nothing once it floats, so a cast aimed to glide into a fish could never be tested on it. Every other
 * player keeps the vanilla hook.
 */
@Mixin(FishingHook.class)
public abstract class MixinFishingHookLegacy extends Projectile {

    @Shadow
    private Entity hookedIn;

    @Unique
    private boolean testkit$started;
    @Unique
    private boolean testkit$stuck;
    @Unique
    private int testkit$life;

    protected MixinFishingHookLegacy(EntityType<? extends Projectile> type, Level level) {
        super(type, level);
    }

    @Shadow
    private void setHookedEntity(Entity entity) {
    }

    @Shadow
    public abstract Player getPlayerOwner();

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void testkit$legacyTick(CallbackInfo ci) {
        Level level = level();
        Player owner = getPlayerOwner();
        if (level.isClientSide() || owner == null || !owner.entityTags().contains("legacyhook")) {
            return;
        }
        ci.cancel();
        boolean rod = owner.getMainHandItem().is(Items.FISHING_ROD) || owner.getOffhandItem().is(Items.FISHING_ROD);
        if (owner.isRemoved() || !owner.isAlive() || !rod || distanceToSqr(owner) > 1024 || ++testkit$life > 1200) {
            discard();
            return;
        }
        if (!testkit$started) {
            testkit$started = true;
            double yaw = Math.toRadians(owner.getYRot());
            Vec3 eye = owner.getEyePosition();
            setPos(eye.x - Math.cos(yaw) * 0.16, eye.y - 0.1, eye.z - Math.sin(yaw) * 0.16);
            setDeltaMovement(owner.getLookAngle().scale(1.5));
        }
        if (hookedIn != null) {
            if (hookedIn.isRemoved()) {
                setHookedEntity(null);
            } else {
                setPos(hookedIn.getX(), hookedIn.getY(0.8), hookedIn.getZ());
            }
            return;
        }
        if (testkit$stuck) {
            return;
        }
        Vec3 v = getDeltaMovement();
        Vec3 from = position();
        Vec3 to = from.add(v);
        BlockHitResult block = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
        boolean hitBlock = block.getType() != HitResult.Type.MISS;
        if (hitBlock) {
            to = block.getLocation();
        }
        Entity best = null;
        double bestD = Double.MAX_VALUE;
        AABB sweep = getBoundingBox().expandTowards(v).inflate(1.0);
        for (Entity e : level.getEntities(this, sweep, e -> e != owner && !e.isSpectator() && e.isPickable())) {
            AABB box = e.getBoundingBox().inflate(0.3);
            // A fish over the hook takes it even while the hook is still sunk under its box after a steep entry (how
            // Hypixel judges a catch is not known; his games catch a fish that swims over the bobber).
            boolean over = from.x > box.minX && from.x < box.maxX && from.z > box.minZ && from.z < box.maxZ
                    && from.y < box.maxY && from.y > box.minY - 2;
            Optional<Vec3> at = over ? Optional.of(from) : box.clip(from, to);
            if (at.isPresent() && from.distanceToSqr(at.get()) < bestD) {
                bestD = from.distanceToSqr(at.get());
                best = e;
            }
        }
        if (best != null) {
            setHookedEntity(best);
            return;
        }
        setPos(to.x, to.y, to.z);
        if (hitBlock) {
            testkit$stuck = true;
            setDeltaMovement(Vec3.ZERO);
            return;
        }
        double minY = getBoundingBox().minY;
        double surface = Double.NEGATIVE_INFINITY;
        BlockPos base = BlockPos.containing(getX(), minY, getZ());
        for (int dy = 1; dy >= 0; dy--) {
            BlockPos p = base.above(dy);
            if (level.getFluidState(p).is(FluidTags.WATER)) {
                surface = p.getY() + level.getFluidState(p).getHeight(level, p);
                break;
            }
        }
        double under = Math.max(0, Math.min(1, (surface - minY) / 0.25));
        double keep = 0.92;
        double vy = v.y + 0.04 * (2 * under - 1);
        if (under > 0) {
            keep *= 0.9;
            vy *= 0.8;
        }
        setDeltaMovement(v.x * keep, vy * keep, v.z * keep);
    }
}
