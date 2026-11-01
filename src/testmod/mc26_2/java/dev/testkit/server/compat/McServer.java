package dev.testkit.server.compat;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;

/**
 * Server-side (companion mod) API that differs between Minecraft versions, for 26.2. The 26.3 copy keeps identical
 * public signatures; build.gradle puts exactly one of src/testmod/mc26_2/java / mc26_3/java on the testmod source path.
 */
public final class McServer {

    private McServer() {
    }

    /** Makes an entity immune to damage (26.2: {@code setInvulnerable}). */
    public static void setInvulnerable(Entity e, boolean on) {
        e.setInvulnerable(on);
    }

    /** A relative move packet in 1/4096-block units. */
    public static Packet<ClientGamePacketListener> moveEntityPos(int id, short dx, short dy, short dz, boolean onGround) {
        return new ClientboundMoveEntityPacket.Pos(id, dx, dy, dz, onGround);
    }

    /** Puts up to four lines on the sign's front text. */
    public static void setFrontLines(SignBlockEntity be, List<String> lines) {
        SignText text = new SignText();
        for (int i = 0; i < 4; i++) {
            text = text.setMessage(i, Component.literal(i < lines.size() ? lines.get(i) : ""));
        }
        be.setText(text, true);
    }

    /** Opens the client's sign editor on the front text. */
    public static Packet<ClientGamePacketListener> openSignFront(BlockPos pos) {
        return new ClientboundOpenSignEditorPacket(pos, true);
    }

    public static BlockPos signUpdatePos(ServerboundSignUpdatePacket p) {
        return p.getPos();
    }

    public static String[] signUpdateLines(ServerboundSignUpdatePacket p) {
        return p.getLines();
    }
}
