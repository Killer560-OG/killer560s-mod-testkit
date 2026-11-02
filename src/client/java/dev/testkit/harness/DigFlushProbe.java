package dev.testkit.harness;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Client half of the dig-gap probe (multibreak-1102). For each dig (START/STOP) and the packets around it: when
 * {@code Connection.send} was called (the sending thread) and when {@code doSendPacket} wrote it on the event loop, and
 * whether it was flushed with it. Vanilla writes and flushes every packet on its own ({@code doSendPacket} calls
 * {@code writeAndFlush} when {@code flush} is true), so a dig and the movement packet after it are two flushes; this
 * measures the time between them on the wire side. One {@code [digflush]} line per movement packet that follows a dig.
 * Always on (cheap: a ring of 16).
 */
public final class DigFlushProbe {

    private record Ev(String name, long queuedNanos, long writtenNanos, boolean flush, String thread) {
    }

    private static final ArrayDeque<Ev> RING = new ArrayDeque<>();
    private static final java.util.IdentityHashMap<Packet<?>, Long> QUEUED = new java.util.IdentityHashMap<>();
    private static int sinceDig = Integer.MAX_VALUE;

    private DigFlushProbe() {
    }

    private static String name(Packet<?> p) {
        if (p instanceof ServerboundPlayerActionPacket a) {
            return "dig:" + a.getAction() + "@" + a.getPos().toShortString().replace(" ", "");
        }
        if (p instanceof ServerboundMovePlayerPacket m) {
            return m.hasRotation() ? String.format(Locale.ROOT, "move(%.2f/%.2f)", m.getYRot(0f), m.getXRot(0f)) : "move";
        }
        if (p instanceof ServerboundAcceptTeleportationPacket) {
            return "accept_tp";
        }
        if (p instanceof ServerboundClientTickEndPacket) {
            return "tick_end";
        }
        return null;
    }

    /** A dig written without a flush and not flushed yet (null when none). */
    private static volatile String unflushedDig;
    private static volatile String lastFlusher;
    /** The sending thread of every packet (to name what flushed a held dig). */
    private static final java.util.Map<Packet<?>, String> QUEUED_BY =
            java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());

    /** {@code ChannelOutboundBuffer.addFlush} HEAD (event loop): who flushed a dig that was written unflushed. */
    public static void onNettyFlush() {
        String d = unflushedDig;
        if (d == null) {
            return;
        }
        unflushedDig = null;
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        StringBuilder sb = new StringBuilder("[digflush] flushed " + d + " by packet " + lastFlusher + " -");
        for (int i = 2; i < Math.min(st.length, 16); i++) {
            sb.append(" < ").append(st[i].getClassName().replaceAll(".*\\.", "")).append('.').append(st[i].getMethodName());
        }
        System.out.println(sb);
    }

    /** {@code Connection.send} HEAD, the sending thread. */
    public static void onQueued(Packet<?> p) {
        QUEUED_BY.put(p, Thread.currentThread().getName());
        if (name(p) == null) {
            return;
        }
        synchronized (RING) {
            QUEUED.put(p, System.nanoTime());
        }
    }

    /** {@code Connection.doSendPacket} HEAD, the event loop. */
    public static void onWritten(Packet<?> p, boolean flush) {
        if (flush && unflushedDig != null) {
            lastFlusher = p.getClass().getSimpleName() + " on " + Thread.currentThread().getName() + " queued by "
                    + QUEUED_BY.getOrDefault(p, "?");
        }
        QUEUED_BY.remove(p);
        String n = name(p);
        if (n == null) {
            return;
        }
        long now = System.nanoTime();
        synchronized (RING) {
            Long q = QUEUED.remove(p);
            RING.addLast(new Ev(n, q == null ? now : q, now, flush, Thread.currentThread().getName()));
            while (RING.size() > 16) {
                RING.removeFirst();
            }
            if (n.startsWith("dig:")) {
                sinceDig = 0;
                unflushedDig = flush ? null : n;
                return;
            }
            if (!n.startsWith("move") || sinceDig > 3) {
                sinceDig++;
                return;
            }
            sinceDig = Integer.MAX_VALUE;
            StringBuilder sb = new StringBuilder("[digflush]");
            for (Ev e : RING) {
                if (now - e.writtenNanos() > 120_000_000L) {
                    continue;
                }
                sb.append(String.format(Locale.ROOT, " %s q%.2f w%.2f%s", e.name(), (e.queuedNanos() - now) / 1e6,
                        (e.writtenNanos() - now) / 1e6, e.flush() ? "F" : "nf"));
            }
            System.out.println(sb);
        }
    }
}
