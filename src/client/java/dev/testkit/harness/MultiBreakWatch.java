package dev.testkit.harness;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * GrimAC 2.3.74's experimental MultiBreak, replayed on the client's own outbound packets (multibreak-1102), so a case sees
 * the fault on every run and can count what was dug per server tick.
 *
 * <p>MultiBreak ({@code checks/impl/breaking/MultiBreak.java}, tag v2.3.74): a START_DESTROY_BLOCK or STOP_DESTROY_BLOCK
 * (an ABORT is ignored) on another block or face than the last dig, before a TICK packet has cleared the state, flags.
 * The tick packet is a movement packet that is not a teleport's answer, or {@code client_tick_end} when no movement packet
 * went before it in that client tick ({@code GrimProcessor.isTickPacket}). The teleport answer is taken as NOT clearing
 * it here (the strict side).
 *
 * <p>Chains onto {@link PacketTrace#tap}. Start, run, stop, read.
 */
public final class MultiBreakWatch {

    /** One dig: the client tick (counted by client_tick_end since start), the server-tick window, the block. */
    public record Dig(int tick, int window, BlockPos pos, Direction face, ServerboundPlayerActionPacket.Action action) {
    }

    private static final List<String> violations = new ArrayList<>();
    private static final List<Dig> digs = new ArrayList<>();
    private static volatile boolean recording;
    private static java.util.function.Consumer<Packet<?>> chained;
    private static int ticks;
    private static int window;
    private static boolean dug;
    private static BlockPos lastPos;
    private static Direction lastFace;
    private static boolean teleportAnswer;
    private static boolean movedThisTick;

    private MultiBreakWatch() {
    }

    /** Client thread. */
    public static void start() {
        synchronized (violations) {
            violations.clear();
            digs.clear();
            ticks = 0;
            window = 0;
            dug = false;
            lastPos = null;
            lastFace = null;
            teleportAnswer = false;
            movedThisTick = false;
        }
        if (!recording) {
            chained = PacketTrace.tap;
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

    /** What GrimAC MultiBreak would have flagged, one line each. */
    public static List<String> violations() {
        synchronized (violations) {
            return List.copyOf(violations);
        }
    }

    /** Every START/STOP dig since {@link #start}, in order. */
    public static List<Dig> digs() {
        synchronized (violations) {
            return List.copyOf(digs);
        }
    }

    private static void onPacket(Packet<?> packet) {
        if (!recording) {
            return;
        }
        synchronized (violations) {
            if (packet instanceof ServerboundPlayerActionPacket a
                    && (a.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK
                    || a.getAction() == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK)) {
                BlockPos pos = a.getPos().immutable();
                Direction face = a.getDirection();
                if (dug && (!pos.equals(lastPos) || face != lastFace)) {
                    violations.add(String.format(Locale.ROOT, "tick %d window %d: %s %s %s after %s %s in the same server "
                            + "tick", ticks, window, a.getAction(), pos.toShortString(), face, lastPos.toShortString(),
                            lastFace));
                }
                dug = true;
                lastPos = pos;
                lastFace = face;
                digs.add(new Dig(ticks, window, pos, face, a.getAction()));
            } else if (packet instanceof ServerboundMovePlayerPacket) {
                if (teleportAnswer) {
                    teleportAnswer = false;
                } else {
                    movedThisTick = true;
                    tickPacket();
                }
            } else if (packet instanceof ServerboundAcceptTeleportationPacket) {
                teleportAnswer = true;
            } else if (packet instanceof ServerboundClientTickEndPacket) {
                if (!movedThisTick) {
                    tickPacket();
                }
                movedThisTick = false;
                ticks++;
            }
        }
    }

    private static void tickPacket() {
        dug = false;
        window++;
    }
}
