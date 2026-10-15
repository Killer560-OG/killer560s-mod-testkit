package dev.testkit.harness;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Secret Aura, as Hypixel's server sees it, right before the next item use a mod sends: after EVERY item use this client
 * sends (while the selected hotbar slot is {@link #onlySlot}), at the end of that tick the BODY is turned to look straight
 * down - so the next movement packet tells the server a look that meets the floor, as an aura's turn to a chest does - and
 * a right click on a block is made through the game mode with the same item still in hand. A mod whose next use goes out
 * before the server has heard ITS look then has that use taken as the click's second half ({@link HypixelUseAfterClick});
 * one that turns first ({@code util/RightClick}) has it land.
 *
 * <p>The block is the floor 7 blocks east of him: past the integrated server's reach for a block click (4.5 + 1), so no block, lever
 * or ability answers it (no teleport, no chest, no door), but inside the 8 blocks a 1.8 server stores a click from, and its
 * handler is entered first, which is where {@link HypixelUseAfterClick} stores it; the click went through
 * {@code MultiPlayerGameMode.useItemOn}, which is where the mod takes it. Client thread only; off unless a scenario sets
 * {@link #enabled}.
 */
public final class ClickPrimer {

    public static volatile boolean enabled;
    /** Only while this hotbar slot is selected; -1 = any. */
    public static volatile int onlySlot = -1;
    /** Item uses seen going out, and clicks made after them, since the last {@link #reset}. */
    public static volatile int uses;
    public static volatile int primed;

    private static boolean installed;
    private static boolean pending;

    private ClickPrimer() {
    }

    public static void install() {
        if (installed) {
            return;
        }
        installed = true;
        ClientTickEvents.END_CLIENT_TICK.register(ClickPrimer::tick);
    }

    public static void reset() {
        enabled = false;
        onlySlot = -1;
        uses = 0;
        primed = 0;
        pending = false;
    }

    /** From {@code MixinOutboundPackets}, for every packet the client sends. */
    public static void onOutbound(Packet<?> packet) {
        if (enabled && packet instanceof ServerboundUseItemPacket) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && (onlySlot < 0 || mc.player.getInventory().getSelectedSlot() == onlySlot)) {
                uses++;
                pending = true;
            }
        }
    }

    /**
     * A solid block a 1.8 server would store a click on (its centre under {@link HypixelUseAfterClick#CLICK_STORE_RANGE}
     * from his feet) but this server answers nothing on (more than 6 from his eye, past the 4.5 + 1 reach a block click
     * gets): the nearest such block east of him first. Null when there is none.
     */
    public static BlockPos storableOutOfReach(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            return null;
        }
        net.minecraft.world.phys.Vec3 feet = mc.player.position();
        net.minecraft.world.phys.Vec3 eye = mc.player.getEyePosition();
        BlockPos at = mc.player.blockPosition();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -8; dx <= 8; dx++) {
            for (int dy = -8; dy <= 8; dy++) {
                for (int dz = -8; dz <= 8; dz++) {
                    BlockPos p = at.offset(dx, dy, dz);
                    if (mc.level.getBlockState(p).isAir() || !mc.level.getBlockState(p).isSolid()) {
                        continue;
                    }
                    double toCentre = feet.distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(p));
                    double toBox = Math.sqrt(new net.minecraft.world.phys.AABB(p).distanceToSqr(eye));
                    if (toCentre >= HypixelUseAfterClick.CLICK_STORE_RANGE - 0.3 || toBox <= 6.0) {
                        continue;
                    }
                    double score = Math.abs(dz) + Math.abs(dy) - dx * 0.1;
                    if (score < bestScore) {
                        bestScore = score;
                        best = p.immutable();
                    }
                }
            }
        }
        return best;
    }

    private static void tick(Minecraft mc) {
        if (!pending) {
            return;
        }
        pending = false;
        if (!enabled || mc.player == null || mc.level == null || mc.gameMode == null) {
            return;
        }
        mc.player.setXRot(90f);
        BlockPos far = storableOutOfReach(mc);
        if (far == null) {
            return;
        }
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(far), Direction.UP, far, false));
        primed++;
    }
}
