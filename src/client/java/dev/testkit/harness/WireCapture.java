package dev.testkit.harness;

import io.netty.buffer.ByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * Every SERVERBOUND packet as the bytes the encoder produced, in every protocol phase (handshake, login,
 * configuration, play), while armed.
 *
 * <p>Fed by {@code MixinPacketEncoder} at TAIL of {@code PacketEncoder.encode}: the buffer then holds the packet id
 * VarInt and the packet body, before compression, encryption and the length prefix - exactly what a server reads
 * after it decompresses. That is one hook for all four phases (each phase installs its own {@code PacketEncoder}),
 * which a listener-level hook is not: login packets never pass {@code ClientCommonPacketListenerImpl#send}.
 *
 * <p>Runs on the netty thread. Armed only by a scenario that asks (65-join-fingerprint); unarmed it costs one
 * volatile read per packet.
 */
public final class WireCapture {

    /** One encoded serverbound packet. {@code bytes} starts with the packet id VarInt. */
    public record Sent(long nanos, String phase, String type, String className, String text, byte[] bytes) {
    }

    private static volatile boolean armed;
    private static final List<Sent> SENT = new ArrayList<>();

    private WireCapture() {
    }

    public static void start() {
        synchronized (SENT) {
            SENT.clear();
        }
        armed = true;
    }

    public static void stop() {
        armed = false;
    }

    public static boolean armed() {
        return armed;
    }

    public static List<Sent> snapshot() {
        synchronized (SENT) {
            return new ArrayList<>(SENT);
        }
    }

    /** Called by the mixin. Never throws: a measuring tool must not take the connection down. */
    public static void onEncoded(String phase, String type, Object packet, ByteBuf out) {
        if (!armed) {
            return;
        }
        try {
            byte[] bytes = new byte[out.readableBytes()];
            out.getBytes(out.readerIndex(), bytes);
            String text;
            try {
                text = String.valueOf(packet);
            } catch (Throwable t) {
                text = "<toString threw " + t.getClass().getSimpleName() + ">";
            }
            Sent s = new Sent(System.nanoTime(), phase, type, packet.getClass().getName(), text, bytes);
            synchronized (SENT) {
                SENT.add(s);
            }
        } catch (Throwable ignored) {
            // measuring must never break the run it is measuring
        }
    }
}
