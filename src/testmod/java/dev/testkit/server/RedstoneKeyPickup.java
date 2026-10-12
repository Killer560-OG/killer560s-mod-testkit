package dev.testkit.server;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.entity.SkullBlockEntity;

import java.util.UUID;

/**
 * Hypixel's dungeon Redstone Key pickup, as a server does it: a right click on a player head wearing the redstone key
 * skin (profile id fed95410-aba1-39df-9b95-1d4f361eb66e, the id killer560s-mod and QUOI both identify it by) takes it
 * away. Any other head is left alone, as on Hypixel. Lets a scenario (848) see the key gone after a real click.
 */
final class RedstoneKeyPickup {

    static final UUID REDSTONE_KEY = UUID.fromString("fed95410-aba1-39df-9b95-1d4f361eb66e");

    private RedstoneKeyPickup() {
    }

    static void register() {
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer) || hit == null) {
                return InteractionResult.PASS;
            }
            BlockPos pos = hit.getBlockPos();
            if (!(level.getBlockEntity(pos) instanceof SkullBlockEntity skull) || skull.getOwnerProfile() == null
                    || skull.getOwnerProfile().partialProfile() == null
                    || !REDSTONE_KEY.equals(skull.getOwnerProfile().partialProfile().id())) {
                return InteractionResult.PASS;
            }
            level.removeBlock(pos, false);
            System.out.println("[testkit] redstone key at " + pos.getX() + "," + pos.getY() + "," + pos.getZ() + " picked up");
            return InteractionResult.SUCCESS;
        });
    }
}
