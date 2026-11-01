package dev.testkit.server.compat;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.network.protocol.game.VecDelta;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;

/**
 * Server-side (companion mod) API that differs between Minecraft versions, for 26.3 (javap 26.3). The 26.2 copy keeps
 * identical public signatures.
 */
public final class McServer {

    private McServer() {
    }

    /** Makes an entity immune to damage (26.3: {@code setPermanentlyInvulnerable}; setInvulnerable is gone). */
    public static void setInvulnerable(Entity e, boolean on) {
        e.setPermanentlyInvulnerable(on);
    }

    /** A relative move packet in 1/4096-block units (26.3: {@code Pos(id, VecDelta.Linear(dx, dy, dz), onGround)}). */
    public static Packet<ClientGamePacketListener> moveEntityPos(int id, short dx, short dy, short dz, boolean onGround) {
        return new ClientboundMoveEntityPacket.Pos(id, new VecDelta.Linear(dx, dy, dz), onGround);
    }

    /** Puts up to four lines on the sign's front text (26.3: SignText is built through {@code asMutable().setLine}). */
    public static void setFrontLines(SignBlockEntity be, List<String> lines) {
        SignText.Mutable text = SignText.EMPTY.asMutable();
        for (int i = 0; i < 4; i++) {
            text = text.setLine(i, Component.literal(i < lines.size() ? lines.get(i) : ""));
        }
        be.setText(text.asImmutable(), SignTextSlot.FRONT);
    }

    /** Opens the client's sign editor on the front text. */
    public static Packet<ClientGamePacketListener> openSignFront(BlockPos pos) {
        return new ClientboundOpenSignEditorPacket(pos, SignTextSlot.FRONT);
    }

    public static BlockPos signUpdatePos(ServerboundSignUpdatePacket p) {
        return p.pos();
    }

    public static String[] signUpdateLines(ServerboundSignUpdatePacket p) {
        return p.lines().toArray(new String[0]);
    }
}
