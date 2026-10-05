package dev.testkit.server.hx.dungeon;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Hypixel's right/left-click abilities that Auto Routes drives, as SERVER behaviour on the GrimAC test server - the same
 * model the mod's own dungeon sim answers its packets with ({@code roomsim.SimAbilities} / {@code SimItems}), rebuilt
 * here because the testmod cannot load the mod. Off until {@code dungeon.abilities} turns it on, so no other scenario
 * sees it.
 *
 * <ul>
 *   <li><b>Etherwarp</b>: a use packet ({@code ServerboundUseItemPacket}) while the SERVER has the player sneaking, with
 *       an Aspect of the Void/End carrying {@code ethermerge:1} or an Etherwarp Conduit: a voxel walk from the sneaking
 *       eye (1.27) along the rotation the use packet carried, up to 57 + tuners blocks, to the first solid block with
 *       two free blocks above it; the player is teleported onto it at the top of its collision box + 0.05 with a real
 *       server teleport (relative rotation, so the camera is untouched) - a position packet and its confirmation, as
 *       Hypixel does. Same walk as {@code TeleportUtils.traverseVoxels}, except "passable" here means "no collision
 *       box" rather than the mod's block list; the two agree for every block the scenarios use.</li>
 *   <li><b>Instant Transmission</b>: the same items without sneak: along the look in quarter-block steps, up to
 *       8 + tuners, to the last block-centred spot the player's box fits ({@code SimAbilities.dashTarget}'s level
 *       walk, without its settle-on-blocked-vertical refinements).</li>
 *   <li><b>Superboom</b>: a START_DESTROY_BLOCK (left click) or a use on a block with {@code SUPERBOOM_TNT} /
 *       {@code INFINITE_SUPERBOOM_TNT}: the cracked stone bricks hit and every cracked stone brick connected to it
 *       within two blocks are destroyed; vanilla's own break / TNT placement is cancelled.</li>
 * </ul>
 * The Dungeon Breaker is NOT emulated: as in scenarios 48-52, its blocks are snow broken in one packet by a vanilla
 * diamond shovel with Efficiency V under Haste V, which GrimAC models as a legal instant break.
 */
public final class HxAbilities {

    private static volatile boolean enabled;
    private static boolean registered;

    private static int etherwarps;
    private static int etherwarpMisses;
    private static int transmissions;
    private static int superbooms;
    private static int boomBlocks;
    private static Vec3 lastLanding;

    private static final double ETHERWARP_RANGE = 57.0;
    private static final double ETHERWARP_LANDING_OFFSET = 0.05;
    private static final double TRANSMISSION_BASE = 8.0;
    private static final int MAX_TUNERS = 4;

    private HxAbilities() {
    }

    static boolean enabled() {
        return enabled;
    }

    static void setEnabled(boolean on) {
        enabled = on;
        System.out.println("[testkit] hypixel ability emulation " + (on ? "ON" : "off"));
    }

    static void register() {
        if (registered) {
            return;
        }
        registered = true;
        UseItemCallback.EVENT.register((player, level, hand) -> player instanceof ServerPlayer sp && enabled
                ? onUse(sp, hand) : InteractionResult.PASS);
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!(player instanceof ServerPlayer sp) || !enabled) {
                return InteractionResult.PASS;
            }
            String id = skyblockId(sp.getItemInHand(hand));
            if (isBoom(id)) {
                superboom(sp, hit.getBlockPos().immutable());
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        });
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
            if (!(player instanceof ServerPlayer sp) || !enabled) {
                return InteractionResult.PASS;
            }
            if (isBoom(skyblockId(sp.getItemInHand(hand)))) {
                superboom(sp, pos.immutable());
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        });
    }

    static JsonObject stats() {
        JsonObject o = new JsonObject();
        o.addProperty("etherwarps", etherwarps);
        o.addProperty("etherwarpMisses", etherwarpMisses);
        o.addProperty("transmissions", transmissions);
        o.addProperty("superbooms", superbooms);
        o.addProperty("boomBlocks", boomBlocks);
        if (lastLanding != null) {
            JsonArray l = new JsonArray();
            l.add(lastLanding.x);
            l.add(lastLanding.y);
            l.add(lastLanding.z);
            o.add("lastLanding", l);
        }
        return o;
    }

    static void resetStats() {
        etherwarps = 0;
        etherwarpMisses = 0;
        transmissions = 0;
        superbooms = 0;
        boomBlocks = 0;
        lastLanding = null;
    }

    private static String skyblockId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? null : data.copyTag().getStringOr("id", null);
    }

    private static boolean isBoom(String id) {
        return "SUPERBOOM_TNT".equals(id) || "INFINITE_SUPERBOOM_TNT".equals(id);
    }

    private static InteractionResult onUse(ServerPlayer sp, InteractionHand hand) {
        ItemStack held = sp.getItemInHand(hand);
        String id = skyblockId(held);
        boolean aspect = "ASPECT_OF_THE_VOID".equals(id) || "ASPECT_OF_THE_END".equals(id);
        boolean conduit = "ETHERWARP_CONDUIT".equals(id);
        if (!aspect && !conduit) {
            return InteractionResult.PASS;
        }
        // Only the use packet: a use-on-block with these items does nothing (shovel on stone), and a vanilla client
        // follows an unconsumed use-on-block with a use, so one click is one ability.
        CompoundTag tag = held.get(DataComponents.CUSTOM_DATA).copyTag();
        int tuners = Math.min(MAX_TUNERS, Math.max(0, tag.getIntOr("tuned_transmission", 0)));
        boolean ethermerge = conduit || tag.getIntOr("ethermerge", 0) == 1;
        if (sp.isShiftKeyDown() && ethermerge) {
            etherwarp(sp, ETHERWARP_RANGE + tuners);
        } else if (aspect) {
            transmission(sp, TRANSMISSION_BASE + tuners);
        }
        return InteractionResult.SUCCESS;
    }

    /** {@code TeleportUtils.getLook}: the direction a yaw/pitch points. */
    private static Vec3 look(float yaw, float pitch) {
        double f2 = -Math.cos(-pitch * 0.017453292f);
        return new Vec3(Math.sin(-yaw * 0.017453292f - 3.1415927f) * f2, Math.sin(-pitch * 0.017453292f),
                Math.cos(-yaw * 0.017453292f - 3.1415927f) * f2);
    }

    private static boolean passable(Level level, BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    private static void etherwarp(ServerPlayer sp, double range) {
        ServerLevel level = (ServerLevel) sp.level();
        // The rotation the use packet carried: handleUseItem snaps the server player to it before useItem runs.
        Vec3 dir = look(sp.getYRot(), sp.getXRot());
        double x0 = sp.getX();
        double y0 = sp.getY() + 1.27;
        double z0 = sp.getZ();
        double x1 = x0 + dir.x * range;
        double y1 = y0 + dir.y * range;
        double z1 = z0 + dir.z * range;
        BlockPos hit = traverse(level, x0, y0, z0, x1, y1, z1);
        if (hit == null) {
            etherwarpMisses++;
            System.out.println("[testkit] etherwarp: no valid block along the look (yaw " + sp.getYRot() + " pitch "
                    + sp.getXRot() + ")");
            return;
        }
        var shape = level.getBlockState(hit).getCollisionShape(level, hit);
        double top = shape.isEmpty() ? 1.0 : shape.max(Direction.Axis.Y);
        double standY = hit.getY() + Math.max(1.0, Math.ceil(top));
        teleport(sp, hit.getX() + 0.5, standY + ETHERWARP_LANDING_OFFSET, hit.getZ() + 0.5);
        etherwarps++;
    }

    /** {@code TeleportUtils.traverseVoxels(level, ..., etherwarp=true)}: the first solid block with feet and head room. */
    private static BlockPos traverse(Level level, double x0, double y0, double z0, double x1, double y1, double z1) {
        double x = Math.floor(x0);
        double y = Math.floor(y0);
        double z = Math.floor(z0);
        double endX = Math.floor(x1);
        double endY = Math.floor(y1);
        double endZ = Math.floor(z1);
        double dirX = x1 - x0;
        double dirY = y1 - y0;
        double dirZ = z1 - z0;
        int stepX = (int) Math.signum(dirX);
        int stepY = (int) Math.signum(dirY);
        int stepZ = (int) Math.signum(dirZ);
        double invDirX = dirX != 0.0 ? 1.0 / dirX : Double.MAX_VALUE;
        double invDirY = dirY != 0.0 ? 1.0 / dirY : Double.MAX_VALUE;
        double invDirZ = dirZ != 0.0 ? 1.0 / dirZ : Double.MAX_VALUE;
        double tDeltaX = Math.abs(invDirX * stepX);
        double tDeltaY = Math.abs(invDirY * stepY);
        double tDeltaZ = Math.abs(invDirZ * stepZ);
        double tMaxX = Math.abs((x + Math.max(stepX, 0) - x0) * invDirX);
        double tMaxY = Math.abs((y + Math.max(stepY, 0) - y0) * invDirY);
        double tMaxZ = Math.abs((z + Math.max(stepZ, 0) - z0) * invDirZ);
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        for (int iter = 0; iter < 1000; iter++) {
            mut.set(x, y, z);
            if (!passable(level, mut)) {
                BlockPos hitPos = mut.immutable();
                var shape = level.getBlockState(hitPos).getCollisionShape(level, hitPos);
                double top = shape.isEmpty() ? 1.0 : shape.max(Direction.Axis.Y);
                int feetY = hitPos.getY() + (int) Math.max(1.0, Math.ceil(top));
                if (!passable(level, new BlockPos(hitPos.getX(), feetY, hitPos.getZ()))
                        || !passable(level, new BlockPos(hitPos.getX(), feetY + 1, hitPos.getZ()))) {
                    return null;
                }
                return hitPos;
            }
            if (x == endX && y == endY && z == endZ) {
                return null;
            }
            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                tMaxX += tDeltaX;
                x += stepX;
            } else if (tMaxY <= tMaxZ) {
                tMaxY += tDeltaY;
                y += stepY;
            } else {
                tMaxZ += tDeltaZ;
                z += stepZ;
            }
        }
        return null;
    }

    private static void transmission(ServerPlayer sp, double range) {
        Level level = sp.level();
        Vec3 dir = look(sp.getYRot(), sp.getXRot());
        Vec3 from = sp.position();
        AABB box = sp.getBoundingBox();
        Vec3 best = null;
        for (double d = 0.25; d <= range + 1.0e-6; d += 0.25) {
            Vec3 full = from.add(dir.scale(d));
            Vec3 candidate = new Vec3(Math.floor(full.x) + 0.5, Math.floor(full.y), Math.floor(full.z) + 0.5);
            if (candidate.equals(best)) {
                continue;
            }
            if (level.noCollision(sp, box.move(candidate.subtract(from)))) {
                best = candidate;
                continue;
            }
            break;
        }
        if (best != null) {
            teleport(sp, best.x, best.y, best.z);
            transmissions++;
        }
    }

    private static void superboom(ServerPlayer sp, BlockPos target) {
        ServerLevel level = (ServerLevel) sp.level();
        if (!level.getBlockState(target).is(Blocks.CRACKED_STONE_BRICKS)) {
            System.out.println("[testkit] superboom at " + target.toShortString() + ": not cracked stone bricks ("
                    + level.getBlockState(target) + ") - nothing to blow up");
            return;
        }
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(target);
        seen.add(target);
        int broken = 0;
        while (!queue.isEmpty() && broken < 64) {
            BlockPos p = queue.poll();
            level.destroyBlock(p, false, sp, 512);
            broken++;
            for (Direction d : Direction.values()) {
                BlockPos n = p.relative(d);
                if (!seen.contains(n) && n.distManhattan(target) <= 3) {
                    seen.add(n);
                    BlockState s = level.getBlockState(n);
                    if (s.is(Blocks.CRACKED_STONE_BRICKS)) {
                        queue.add(n);
                    }
                }
            }
        }
        superbooms++;
        boomBlocks += broken;
    }

    /** {@code SimAbilities.teleport}: a real server teleport, rotation relative and zero. */
    private static void teleport(ServerPlayer sp, double x, double y, double z) {
        sp.teleportTo((ServerLevel) sp.level(), x, y, z, Set.of(Relative.Y_ROT, Relative.X_ROT), 0f, 0f, false);
        lastLanding = new Vec3(x, y, z);
    }
}
