package dev.testkit.harness;

/**
 * Per-call timer around VANILLA hot paths that killer560s-mod's mixins inject into (95-mixin-bench). Fed by
 * {@code dev.testkit.mixin.bench} {@code @WrapMethod} mixins (config {@code testkit.bench.mixins.json}, optional, every
 * one {@code require = 0}). A {@code @WrapMethod} wraps the whole method, the other mods' injections included, so the
 * same timer with and without the mod measures exactly what the mod's mixins add per call. Each point keeps its own
 * nesting depth (a call that recurses into the same method counts once), and different points may nest (a slot's text
 * call is timed as text AND inside its slot). Inert (one volatile read) unless {@link #start} was called.
 */
public final class MixinBench {

    public static final String[] POINTS = {
            "AbstractContainerScreen.extractSlot", "AbstractContainerScreen.extractRenderState",
            "EntityRenderDispatcher.extractEntity", "EntityRenderDispatcher.shouldRender",
            "GuiGraphicsExtractor.text(seq)", "GuiGraphicsExtractor.blitSprite", "GuiGraphicsExtractor.fill(pipeline)",
    };

    private static volatile boolean on;
    private static final long[] CALLS = new long[POINTS.length];
    private static final long[] NANOS = new long[POINTS.length];
    private static final int[] DEPTH = new int[POINTS.length];
    private static final long[] START = new long[POINTS.length];
    private static Thread owner;

    private MixinBench() {
    }

    /** Starts counting on the calling thread (the render thread); calls on any other thread are ignored. */
    public static void start() {
        java.util.Arrays.fill(CALLS, 0);
        java.util.Arrays.fill(NANOS, 0);
        java.util.Arrays.fill(DEPTH, 0);
        owner = Thread.currentThread();
        on = true;
    }

    public static void stop() {
        on = false;
    }

    public static long[] calls() {
        return CALLS.clone();
    }

    public static long[] nanos() {
        return NANOS.clone();
    }

    public static boolean enter(int point) {
        if (!on || Thread.currentThread() != owner) {
            return false;
        }
        if (DEPTH[point]++ == 0) {
            START[point] = System.nanoTime();
        }
        return true;
    }

    public static void exit(int point) {
        if (--DEPTH[point] == 0) {
            NANOS[point] += System.nanoTime() - START[point];
            CALLS[point]++;
        }
    }

    /** The cost of one enter/exit pair with nothing between (the timer's own overhead per call), in ns. */
    public static double overheadNs() {
        long[] c = CALLS.clone();
        long[] n = NANOS.clone();
        boolean was = on;
        Thread prev = owner;
        owner = Thread.currentThread();
        on = true;
        int k = 400_000;
        long t0 = System.nanoTime();
        for (int i = 0; i < k; i++) {
            if (enter(0)) {
                exit(0);
            }
        }
        long t = System.nanoTime() - t0;
        System.arraycopy(c, 0, CALLS, 0, c.length);
        System.arraycopy(n, 0, NANOS, 0, n.length);
        on = was;
        owner = prev;
        return t / (double) k;
    }
}
