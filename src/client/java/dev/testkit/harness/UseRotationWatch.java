package dev.testkit.harness;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * GrimAC 2.3.74's BadPacketsJ, replayed on the client's own outbound packets so a scenario sees the fault on every run
 * instead of only when the anticheat's log is read (grim-autopilot-flag-1016).
 *
 * <p>BadPacketsJ (javap of {@code ac.grim.grimac.checks.impl.badpackets.BadPacketsJ}, 1.21+ client on a 1.21+ server, no
 * tick skipping): a {@code use_item} carries its own rotation, and the server holds every use until the tick's packet.
 * A second use in the same tick with a DIFFERENT rotation from the one before it flags once per use still held; and at the
 * tick packet, the last use's rotation must be the one the player now has (the movement packet's, or the last one told
 * when it carries none). A vanilla client always sends the use at the look its movement packet then reports.
 *
 * <p>Chains onto {@link PacketTrace#tap} (keeps whatever tap was there). Start, run, stop, read {@link #violations()}.
 */
public final class UseRotationWatch {

    private static final List<String> violations = new ArrayList<>();
    private static final List<float[]> held = new ArrayList<>();
    private static volatile boolean recording;
    private static java.util.function.Consumer<Packet<?>> chained;
    private static boolean known;
    private static float yaw;
    private static float pitch;
    private static int ticks;
    private static int uses;
    /** An accept_teleportation went last: the next movement packet is the teleport's answer, which Grim does not take
     *  as a tick packet (GrimProcessor.isTickPacket: lastPacketWasTeleport) - it moves the rotation and checks nothing. */
    private static boolean teleportAnswer;
    /** A tick packet (movement) went since the last client_tick_end; without one the tick end is the tick packet. */
    private static boolean movedThisTick;

    private UseRotationWatch() {
    }

    /** Client thread. */
    public static void start() {
        synchronized (violations) {
            violations.clear();
            held.clear();
            known = false;
            teleportAnswer = false;
            movedThisTick = false;
            ticks = 0;
            uses = 0;
        }
        if (!recording) {
            java.util.function.Consumer<Packet<?>> before = PacketTrace.tap;
            chained = before;
            PacketTrace.tap = p -> {
                onPacket(p);
                java.util.function.Consumer<Packet<?>> c = chained;
                if (c != null) {
                    c.accept(p);
                }
            };
        }
        recording = true;
    }

    /** Client thread. Puts back the tap that was there at {@link #start}. */
    public static void stop() {
        if (recording) {
            recording = false;
            PacketTrace.tap = chained;
            chained = null;
        }
    }

    /** What would have flagged, one line each ("tick N: ..."). */
    public static List<String> violations() {
        synchronized (violations) {
            return List.copyOf(violations);
        }
    }

    /** Uses seen since {@link #start} - so a clean result can be told from one that saw nothing. */
    public static int uses() {
        synchronized (violations) {
            return uses;
        }
    }

    private static void onPacket(Packet<?> packet) {
        if (!recording) {
            return;
        }
        synchronized (violations) {
            if (packet instanceof ServerboundUseItemPacket u) {
                uses++;
                float y = u.getYRot();
                float p = u.getXRot();
                if (!held.isEmpty()) {
                    float[] last = held.get(held.size() - 1);
                    if (last[0] != y || last[1] != p) {
                        violations.add(String.format(Locale.ROOT, "tick %d: use %d at %.2f/%.2f follows use %.2f/%.2f in "
                                + "the same tick (%d held)", ticks, uses, y, p, last[0], last[1], held.size()));
                        held.clear();
                    }
                }
                held.add(new float[]{y, p});
            } else if (packet instanceof ServerboundMovePlayerPacket m) {
                if (m.hasRotation()) {
                    yaw = m.getYRot(yaw);
                    pitch = m.getXRot(pitch);
                    known = true;
                }
                if (teleportAnswer) {
                    teleportAnswer = false;
                } else {
                    movedThisTick = true;
                    tickPacket("move");
                }
            } else if (packet instanceof ServerboundAcceptTeleportationPacket) {
                teleportAnswer = true;
            } else if (packet instanceof ServerboundClientTickEndPacket) {
                if (!movedThisTick) {
                    tickPacket("tick-end");
                }
                movedThisTick = false;
                ticks++;
            }
        }
    }

    private static void tickPacket(String what) {
        if (held.isEmpty()) {
            return;
        }
        float[] last = held.get(held.size() - 1);
        if (known && (last[0] != yaw || last[1] != pitch)) {
            violations.add(String.format(Locale.ROOT, "tick %d: use at %.2f/%.2f, then the %s packet reports %.2f/%.2f "
                    + "(%d use(s) held)", ticks, last[0], last[1], what, yaw, pitch, held.size()));
        }
        held.clear();
    }
}
