package dev.testkit.compat;

import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Packet accessors that changed shape between versions, for 26.3. Identical public signatures in the 26.2 copy.
 *
 * <p>26.3 (javap 26.3): the use packets are records ({@code yRot()}, {@code sequence()}, {@code hitResult()} ...), and
 * {@code ServerboundSwingPacket} is gone: the arm swing is {@code ServerboundPunchPacket}, a record with no fields
 * (no hand) sent as {@code ServerboundPunchPacket.INSTANCE}.
 */
public final class McPackets {

    private McPackets() {
    }

    /** Whether this outbound packet is an arm swing (26.3: {@code ServerboundPunchPacket}). */
    public static boolean isSwing(Object packet) {
        return packet instanceof ServerboundPunchPacket;
    }

    /** The id PacketTrace records for an arm swing: "punch" here (ServerboundPunchPacket). Count swings by this, never by a literal. */
    public static String swingId() {
        return "punch";
    }

    public static float useItemYRot(ServerboundUseItemPacket p) {
        return p.yRot();
    }

    public static float useItemXRot(ServerboundUseItemPacket p) {
        return p.xRot();
    }

    public static int useItemSequence(ServerboundUseItemPacket p) {
        return p.sequence();
    }

    public static InteractionHand useItemHand(ServerboundUseItemPacket p) {
        return p.hand();
    }

    public static BlockHitResult useItemOnHitResult(ServerboundUseItemOnPacket p) {
        return p.hitResult();
    }

    public static int useItemOnSequence(ServerboundUseItemOnPacket p) {
        return p.sequence();
    }

    public static InteractionHand useItemOnHand(ServerboundUseItemOnPacket p) {
        return p.hand();
    }

    /**
     * A relative move packet in 1/4096-block units. 26.3: {@code Pos(id, VecDelta, onGround)} with
     * {@code VecDelta.Linear(short, short, short)} (VecDeltaCodec.encode is still round(d * 4096), javap 26.3).
     */
    public static net.minecraft.network.protocol.game.ClientboundMoveEntityPacket moveEntityPos(int id, short dx, short dy,
            short dz, boolean onGround) {
        return new net.minecraft.network.protocol.game.ClientboundMoveEntityPacket.Pos(id,
                new net.minecraft.network.protocol.game.VecDelta.Linear(dx, dy, dz), onGround);
    }

    /** Bytes between a sign_update packet's id and its first line on the wire. 26.3: BlockPos long, then the four strings (fixedSizeList, no count), then the SignTextSlot after them (javap STREAM_CODEC). */
    public static int signUpdateBytesBeforeLines() {
        return 8;
    }
}
