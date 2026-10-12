package dev.testkit.server;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SkullBlockEntity;

import java.util.Locale;
import java.util.UUID;

/**
 * Hypixel's dungeon Redstone Key, as its server does it, for scenarios 847/848.
 * <ul>
 *   <li>Pickup: a right click on a player head wearing the redstone key skin (profile id
 *       fed95410-aba1-39df-9b95-1d4f361eb66e, the id killer560s-mod, QUOI, Odin and RSM identify it by) takes it away and
 *       says "You found a Secret Redstone Key!" (the line QUOI's chat filter lists). Any other head is left alone.</li>
 *   <li>Placement: since SkyBlock 0.18.4 the key is no longer an item - once found, any player may put it on the room's
 *       redstone node (hypixelskyblock.minecraft.wiki, Redstone Key). The node is a REDSTONE_BLOCK (QUOI and RSA
 *       click one once the key skull has gone); placing it opens a redstone door, which RSM detects by the piston sound;
 *       the key then sits on TOP of the block (killer560, 2026-10-12; Skyblocker files the key's secret at the redstone
 *       block's pos.above()). So: a right click on a redstone block, on a face whose neighbour is AIR, while a key is
 *       found, sets the key's head on top, plays a piston extend there and uses the key up. A covered face does
 *       nothing. Every use on a redstone block is logged with its face. Any placement chat line is not known.</li>
 * </ul>
 */
final class RedstoneKeyPickup {

    static final UUID REDSTONE_KEY = UUID.fromString("fed95410-aba1-39df-9b95-1d4f361eb66e");
    /** The key's profile as an SNBT int array, for the head set on the node. */
    private static final String KEY_ID = "[I;-19311600,-1415497249,-1684726449,907982446]";

    /** Keys found and not yet placed (any player may place one, as on Hypixel since 0.18.4). */
    private static int keysFound = 0;

    private RedstoneKeyPickup() {
    }

    static void register() {
        registerCommand();
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer) || hit == null
                    || !(level instanceof ServerLevel serverLevel)) {
                return InteractionResult.PASS;
            }
            BlockPos pos = hit.getBlockPos();
            if (level.getBlockEntity(pos) instanceof SkullBlockEntity skull && skull.getOwnerProfile() != null
                    && skull.getOwnerProfile().partialProfile() != null
                    && REDSTONE_KEY.equals(skull.getOwnerProfile().partialProfile().id())) {
                level.removeBlock(pos, false);
                keysFound++;
                run(serverLevel.getServer(), "tellraw @a {\"text\":\"You found a Secret Redstone Key!\"}");
                System.out.println("[testkit] redstone key at " + pos.getX() + "," + pos.getY() + "," + pos.getZ()
                        + " picked up");
                return InteractionResult.SUCCESS;
            }
            if (level.getBlockState(pos).is(Blocks.REDSTONE_BLOCK)) {
                // Only a face open to AIR takes the key (killer560, 2026-10-12: "it has to be on a side hitting air");
                // every use on a node is logged with its face, so a scenario can prove a covered face was never tried.
                boolean exposed = level.getBlockState(pos.relative(hit.getDirection())).isAir();
                System.out.println("[testkit] redstone block used at " + pos.getX() + "," + pos.getY() + "," + pos.getZ()
                        + " face " + hit.getDirection().getName() + (exposed ? " exposed" : " covered")
                        + ", keys found " + keysFound);
                if (keysFound <= 0 || !exposed || !level.getBlockState(pos.above()).isAir()) {
                    return InteractionResult.PASS;
                }
                keysFound--;
                MinecraftServer server = serverLevel.getServer();
                run(server, String.format(Locale.ROOT, "setblock %d %d %d minecraft:player_head{profile:{id:%s,name:\"RedstoneKey\"}}",
                        pos.getX(), pos.getY() + 1, pos.getZ(), KEY_ID));
                run(server, String.format(Locale.ROOT, "playsound minecraft:block.piston.extend block @a %d %d %d",
                        pos.getX(), pos.getY(), pos.getZ()));
                System.out.println("[testkit] redstone key placed at " + pos.getX() + "," + pos.getY() + "," + pos.getZ());
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        });
    }

    /** {@code testkitkey found}: a key picked up by someone else (a teammate), so the server knows one is found. */
    static void registerCommand() {
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
                dispatcher.register(net.minecraft.commands.Commands.literal("testkitkey")
                        .then(net.minecraft.commands.Commands.literal("found").executes(context -> {
                            keysFound++;
                            System.out.println("[testkit] redstone key found by someone else (" + keysFound + ")");
                            return 1;
                        }))));
    }

    private static void run(MinecraftServer server, String cmd) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd);
    }
}
