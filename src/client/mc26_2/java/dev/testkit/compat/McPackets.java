package dev.testkit.compat;

import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Packet accessors that changed shape between versions, for 26.2. Identical public signatures in the 26.3 copy.
 *
 * <p>26.2: the use packets are plain classes with {@code getX()} getters, and an arm swing is
 * {@code ServerboundSwingPacket(InteractionHand)} (javap 26.2).
 */
public final class McPackets {

    private McPackets() {
    }

    /** Whether this outbound packet is an arm swing (26.2: {@code ServerboundSwingPacket}). */
    public static boolean isSwing(Object packet) {
        return packet instanceof ServerboundSwingPacket;
    }

    /** The id PacketTrace records for an arm swing: "swing" here (ServerboundSwingPacket). Count swings by this, never by a literal. */
    public static String swingId() {
        return "swing";
    }

    public static float useItemYRot(ServerboundUseItemPacket p) {
        return p.getYRot();
    }

    public static float useItemXRot(ServerboundUseItemPacket p) {
        return p.getXRot();
    }

    public static int useItemSequence(ServerboundUseItemPacket p) {
        return p.getSequence();
    }

    public static InteractionHand useItemHand(ServerboundUseItemPacket p) {
        return p.getHand();
    }

    public static BlockHitResult useItemOnHitResult(ServerboundUseItemOnPacket p) {
        return p.getHitResult();
    }

    public static int useItemOnSequence(ServerboundUseItemOnPacket p) {
        return p.getSequence();
    }

    public static InteractionHand useItemOnHand(ServerboundUseItemOnPacket p) {
        return p.getHand();
    }

    /** A relative move packet in 1/4096-block units (26.2: {@code Pos(id, short, short, short, onGround)}). */
    public static net.minecraft.network.protocol.game.ClientboundMoveEntityPacket moveEntityPos(int id, short dx, short dy,
            short dz, boolean onGround) {
        return new net.minecraft.network.protocol.game.ClientboundMoveEntityPacket.Pos(id, dx, dy, dz, onGround);
    }

    /** Bytes between a sign_update packet's id and its first line on the wire. 26.2: BlockPos long, then the boolean front flag, then the four strings. */
    public static int signUpdateBytesBeforeLines() {
        return 9;
    }
}
