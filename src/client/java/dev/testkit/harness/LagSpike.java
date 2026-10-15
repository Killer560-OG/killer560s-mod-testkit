package dev.testkit.harness;

import dev.testkit.mixin.AccessorConnection;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelPromise;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;

/**
 * A server lag spike: for {@code ms} the connection is frozen BOTH ways - nothing the client sends reaches the server
 * and nothing the server sends (no position packet, no block change, no per-tick ping) reaches the client - and then
 * the client's packets reach the server all at once, in order, and the server's answers follow. That is what a server
 * that stalls looks like from both ends: it handles the client's queued uses together when it wakes, and the client
 * hears nothing until then. It is the only way to make one in a client gametest: blocking the
 * integrated server's thread does not work, because the gametest API ticks the client and the server in LOCKSTEP, so
 * the client stops ticking too and no client-side timeout ever runs (found 2026-10-15, server-lag-1015: a 2.5 s server
 * block let the Interactive Map's 20-tick stall pass in 26 client ticks on any jar).
 *
 * <p>Like {@link Latency}, a stage in front of the connection's {@code packet_handler}, strictly first in first out in
 * each direction; at the end the held outbound packets go first (the server handles the queued uses), then the held
 * inbound ones, and the stage leaves the pipeline. (Inbound-only was tried first: the server then answered every
 * queued warp in real time while the client was blind, which no stalled server does.)
 *
 * <p>{@link #armOnNextUse(long)} starts the spike the moment the client sends its next {@code use_item} (from the
 * outbound tap, {@code MixinOutboundPackets}), so the answer to that use is guaranteed to be inside the spike: the stage
 * is installed on the channel's event loop BEFORE the use itself is written.
 */
public final class LagSpike {

    private static final String NAME = "testkit_lagspike";
    private static final String BEFORE = "packet_handler";

    private static volatile long armedMs = 0L;
    private static volatile long startedAt = 0L;
    private static volatile long releasedAt = 0L;
    private static volatile int held = 0;

    private LagSpike() {
    }

    /** The next {@code use_item} this client sends starts a spike of {@code ms}. */
    public static void armOnNextUse(long ms) {
        startedAt = 0L;
        releasedAt = 0L;
        held = 0;
        armedMs = ms;
    }

    /** Cancels an armed spike that has not started. */
    public static void disarm() {
        armedMs = 0L;
    }

    /** Wall-clock ms the spike started (0: not yet). */
    public static long startedAt() {
        return startedAt;
    }

    /** Wall-clock ms the held packets were released (0: not yet). */
    public static long releasedAt() {
        return releasedAt;
    }

    /** Packets the spike held back. */
    public static int held() {
        return held;
    }

    /** From the outbound tap, on whatever thread sends. */
    public static void onOutbound(Packet<?> packet) {
        long ms = armedMs;
        if (ms <= 0L || !(packet instanceof ServerboundUseItemPacket)) {
            return;
        }
        armedMs = 0L;
        start(ms);
    }

    /** Starts a spike of {@code ms} now. */
    public static void start(long ms) {
        Channel channel = channel();
        if (channel == null) {
            return;
        }
        startedAt = System.currentTimeMillis();
        releasedAt = 0L;
        held = 0;
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms);
        channel.eventLoop().execute(() -> {
            if (channel.pipeline().get(NAME) instanceof Hold existing) {
                existing.untilNanos = until;
                return;
            }
            channel.pipeline().addBefore(BEFORE, NAME, new Hold(until));
        });
    }

    private static Channel channel() {
        ClientPacketListener listener = Minecraft.getInstance().getConnection();
        return listener == null ? null : ((AccessorConnection) listener.getConnection()).testkit$channel();
    }

    /** Runs on the channel's event loop only. */
    private static final class Hold extends ChannelDuplexHandler {
        private record Out(Object msg, ChannelPromise promise) {
        }

        private final ArrayDeque<Object> queue = new ArrayDeque<>();
        private final ArrayDeque<Out> out = new ArrayDeque<>();
        private long untilNanos;
        private boolean scheduled;

        Hold(long untilNanos) {
            this.untilNanos = untilNanos;
        }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            schedule(ctx);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (System.nanoTime() >= untilNanos && queue.isEmpty()) {
                ctx.fireChannelRead(msg);
                return;
            }
            queue.addLast(msg);
            held++;
            schedule(ctx);
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (System.nanoTime() >= untilNanos && out.isEmpty()) {
                ctx.write(msg, promise);
                return;
            }
            out.addLast(new Out(msg, promise));
            held++;
            schedule(ctx);
        }

        @Override
        public void flush(ChannelHandlerContext ctx) {
            if (out.isEmpty()) {
                ctx.flush();
            }
        }

        private void schedule(ChannelHandlerContext ctx) {
            if (scheduled) {
                return;
            }
            scheduled = true;
            long wait = Math.max(0L, untilNanos - System.nanoTime());
            ctx.executor().schedule(() -> release(ctx), wait, TimeUnit.NANOSECONDS);
        }

        private void release(ChannelHandlerContext ctx) {
            scheduled = false;
            if (System.nanoTime() < untilNanos) {
                schedule(ctx);   // the spike was extended meanwhile
                return;
            }
            while (!out.isEmpty()) {
                Out o = out.pollFirst();
                ctx.write(o.msg(), o.promise());
            }
            ctx.flush();
            while (!queue.isEmpty()) {
                ctx.fireChannelRead(queue.pollFirst());
            }
            releasedAt = System.currentTimeMillis();
            if (!ctx.isRemoved()) {
                ctx.pipeline().remove(this);
            }
        }
    }
}
