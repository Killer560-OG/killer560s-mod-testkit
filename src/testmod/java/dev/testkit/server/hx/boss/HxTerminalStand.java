package dev.testkit.server.hx.boss;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.testkit.server.hx.HxEvents;
import dev.testkit.server.hx.menu.HxTerminalApi;

import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A P3 terminal as the mod's Terminal Open Logger sees one: an invisible, NON-marker armour stand named "Inactive
 * Terminal" standing on a three-block pillar whose top block is a command block, with a platform to stand LEVEL with
 * the stand's feet on one side and one two blocks ABOVE them on another; standing on the ground beside the pillar is
 * BELOW. Right-clicking the stand opens a "Click in order!" terminal (menu.terminal's NUMBERS) when the player's eye is
 * at least {@code minDy} above the stand's feet, else sends {@code belowLine} (when {@code line} is on) and opens
 * nothing. That rule is the testkit's stand-in for Hypixel's new one, which nobody knows yet - the logger is what will
 * find it; this only gives the logger a server that answers some clicks and not others.
 *
 * <p>Ops: {@code boss.term.build {x, z, groundY=151, minDy=0, line=true}} (the pillar stands at block x/z, ground
 * blocks at groundY-1), {@code boss.term.spawn} (the stand, once the player is beside it), {@code boss.term.config {line?, minDy?}}, {@code boss.term.state}, {@code boss.term.clear}.
 * Event {@code term.interact} per main-hand interact: {@code opened}, {@code eyeDy}, {@code hand}.
 */
final class HxTerminalStand {

    static final String BELOW_LINE = "You can't open a terminal from below it!";

    private static ArmorStand stand;
    private static double minDy = 0;
    private static boolean line = true;
    private static int interacts;
    private static int opens;
    private static int x;
    private static int z;
    private static int groundY;

    private HxTerminalStand() {
    }

    static void register() {
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer sp) || stand == null || entity != stand) {
                return InteractionResult.PASS;
            }
            if (hand != InteractionHand.MAIN_HAND) {
                return InteractionResult.SUCCESS;
            }
            interacts++;
            double eyeDy = sp.getEyeY() - stand.getY();
            boolean open = eyeDy >= minDy;
            if (open) {
                opens++;
                JsonObject a = new JsonObject();
                a.addProperty("type", "numbers");
                a.addProperty("seed", 7);
                a.addProperty("player", sp.getGameProfile().name());
                HxTerminalApi.open(sp.level().getServer(), a);
            } else if (line) {
                sp.connection.send(new ClientboundSystemChatPacket(Component.literal(BELOW_LINE), false));
            }
            JsonObject d = new JsonObject();
            d.addProperty("opened", open);
            d.addProperty("eyeDy", Math.round(eyeDy * 1000) / 1000.0);
            d.addProperty("hand", hand.name());
            HxEvents.custom("term.interact", sp, d);
            return InteractionResult.SUCCESS;
        });
    }

    static JsonElement build(MinecraftServer server, JsonObject a) {
        clear(server, a);
        ServerLevel level = server.overworld();
        x = a.get("x").getAsInt();
        z = a.get("z").getAsInt();
        groundY = a.has("groundY") ? a.get("groundY").getAsInt() : 151;
        minDy = a.has("minDy") ? a.get("minDy").getAsDouble() : 0;
        line = !a.has("line") || a.get("line").getAsBoolean();
        interacts = 0;
        opens = 0;
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        for (int bx = x - 4; bx <= x + 4; bx++) {
            for (int bz = z - 4; bz <= z + 5; bz++) {
                for (int by = groundY; by <= groundY + 9; by++) {
                    level.setBlockAndUpdate(new BlockPos(bx, by, bz), air);
                }
                level.setBlockAndUpdate(new BlockPos(bx, groundY - 1, bz), stone);
            }
        }
        // The pillar: two stone, then the terminal block. The stand stands on its top (groundY + 3).
        level.setBlockAndUpdate(new BlockPos(x, groundY, z), stone);
        level.setBlockAndUpdate(new BlockPos(x, groundY + 1, z), stone);
        level.setBlockAndUpdate(new BlockPos(x, groundY + 2, z), Blocks.COMMAND_BLOCK.defaultBlockState());
        // LEVEL platform, west of the pillar: top at groundY + 3.
        for (int bx = x - 2; bx <= x - 1; bx++) {
            for (int bz = z - 1; bz <= z + 1; bz++) {
                level.setBlockAndUpdate(new BlockPos(bx, groundY + 2, bz), stone);
            }
        }
        // ABOVE platform, south of the pillar: top at groundY + 5.
        for (int bx = x - 1; bx <= x + 1; bx++) {
            for (int bz = z + 2; bz <= z + 3; bz++) {
                level.setBlockAndUpdate(new BlockPos(bx, groundY + 4, bz), stone);
            }
        }
        return describe();
    }

    /** The stand itself, once the player is there: an entity added to a chunk the server is not ticking entities in
     *  never reaches the client (testkit CLAUDE.md, "An entity reads back only from a section the server ticks"). */
    static JsonElement spawn(MinecraftServer server, JsonObject a) {
        ServerLevel level = server.overworld();
        if (stand != null) {
            stand.discard();
        }
        ArmorStand s = new ArmorStand(level, x + 0.5, groundY + 3, z + 0.5);
        s.setNoGravity(true);
        s.setInvisible(true);
        s.setCustomName(Component.literal("Inactive Terminal"));
        s.setCustomNameVisible(true);
        if (!level.addFreshEntity(s)) {
            throw new IllegalStateException("the level refused the terminal stand");
        }
        stand = s;
        return describe();
    }

    static JsonElement config(MinecraftServer server, JsonObject a) {
        if (a.has("line")) {
            line = a.get("line").getAsBoolean();
        }
        if (a.has("minDy")) {
            minDy = a.get("minDy").getAsDouble();
        }
        return describe();
    }

    static JsonElement clear(MinecraftServer server, JsonObject a) {
        if (stand != null) {
            stand.discard();
            stand = null;
        }
        return describe();
    }

    static JsonObject describe() {
        JsonObject o = new JsonObject();
        o.addProperty("built", stand != null);
        o.addProperty("standId", stand == null ? -1 : stand.getId());
        if (stand != null) {
            o.addProperty("standX", stand.getX());
            o.addProperty("standY", stand.getY());
            o.addProperty("standZ", stand.getZ());
            o.addProperty("marker", stand.isMarker());
        }
        o.addProperty("minDy", minDy);
        o.addProperty("line", line);
        o.addProperty("interacts", interacts);
        o.addProperty("opens", opens);
        return o;
    }
}
