package dev.testkit.harness;

import java.util.Arrays;
import java.util.Locale;

/**
 * Per-frame CPU time on the render thread, for the FPS bench (scenario 95-fps-bench).
 *
 * <p>Three spans, fed by {@code MixinFrameClockMinecraft} and {@code MixinFrameClockGameRenderer}:
 * {@code Minecraft.tick()} (the client tick: every ClientTickEvents handler runs inside it), and
 * {@code GameRenderer.extract} + {@code GameRenderer.render} (the frame's CPU work: world and HUD extraction, every
 * HUD layer, LevelRenderEvents, the render submit). Swap, vsync and the frame limiter run AFTER render in
 * {@code Minecraft.renderFrame} and are deliberately outside every span, so a vsync-bound window cannot hide a cost.
 *
 * <p>Nothing is recorded until {@link #start} and nothing allocates while recording: samples go into preallocated
 * arrays, and a frame past capacity is dropped (and counted). Render thread only.
 */
public final class FrameClock {

    private static volatile boolean recording;
    private static long[] frameNs = new long[0];
    private static long[] tickNs = new long[0];
    private static int frames;
    private static int ticks;
    private static int dropped;

    private static long tickStart;
    private static long extractStart;
    private static long extractNs;
    private static long renderStart;

    /** Always-on counters (not tied to {@link #start}): client ticks begun and frames rendered since launch. A
     *  scenario reads them to tell "the same frame" from "a later tick" (397-menu-petwheel-instant). */
    private static volatile long tickCount;
    private static volatile long frameCount;

    private FrameClock() {
    }

    /** Client ticks begun since launch, counted at HEAD of {@code Minecraft.tick}. */
    public static long tickCount() {
        return tickCount;
    }

    /** Frames rendered since launch, counted at RETURN of {@code GameRenderer.render}. */
    public static long frameCount() {
        return frameCount;
    }

    /** Begin a fresh recording with room for {@code capacity} frames and as many ticks. */
    public static void start(int capacity) {
        frameNs = new long[capacity];
        tickNs = new long[capacity];
        frames = 0;
        ticks = 0;
        dropped = 0;
        extractNs = 0;
        recording = true;
    }

    public static void stop() {
        recording = false;
    }

    public static int frames() {
        return frames;
    }

    public static int ticks() {
        return ticks;
    }

    // ---- fed by the mixins -------------------------------------------------------------------------------------

    public static void tickHead() {
        tickCount++;
        if (recording) {
            tickStart = System.nanoTime();
        }
    }

    public static void tickReturn() {
        if (recording && tickStart != 0) {
            if (ticks < tickNs.length) {
                tickNs[ticks++] = System.nanoTime() - tickStart;
            }
            tickStart = 0;
        }
    }

    public static void extractHead() {
        if (recording) {
            extractStart = System.nanoTime();
        }
    }

    public static void extractReturn() {
        if (recording && extractStart != 0) {
            extractNs = System.nanoTime() - extractStart;
            extractStart = 0;
        }
    }

    public static void renderHead() {
        if (recording) {
            renderStart = System.nanoTime();
        }
    }

    public static void renderReturn() {
        frameCount++;
        if (recording && renderStart != 0) {
            long ns = System.nanoTime() - renderStart + extractNs;
            renderStart = 0;
            extractNs = 0;
            if (frames < frameNs.length) {
                frameNs[frames++] = ns;
            } else {
                dropped++;
            }
        }
    }

    // ---- results ---------------------------------------------------------------------------------------------

    /** Mean / p50 / p95 / p99 / max in milliseconds of the samples so far. */
    public record Stats(int n, double mean, double p50, double p95, double p99, double max) {
        public String line() {
            return String.format(Locale.ROOT, "n=%d mean=%.3f p50=%.3f p95=%.3f p99=%.3f max=%.3f ms",
                    n, mean, p50, p95, p99, max);
        }
    }

    public static Stats frameStats() {
        return stats(frameNs, frames);
    }

    public static Stats tickStats() {
        return stats(tickNs, ticks);
    }

    public static int dropped() {
        return dropped;
    }

    public static long[] frameSamples() {
        return Arrays.copyOf(frameNs, frames);
    }

    public static long[] tickSamples() {
        return Arrays.copyOf(tickNs, ticks);
    }

    public static Stats stats(long[] samples, int n) {
        if (n == 0) {
            return new Stats(0, 0, 0, 0, 0, 0);
        }
        long[] s = Arrays.copyOf(samples, n);
        Arrays.sort(s);
        double sum = 0;
        for (long v : s) {
            sum += v;
        }
        return new Stats(n, sum / n / 1e6, pct(s, 0.50), pct(s, 0.95), pct(s, 0.99), s[n - 1] / 1e6);
    }

    private static double pct(long[] sorted, double p) {
        int i = (int) Math.ceil(p * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, i))] / 1e6;
    }
}
