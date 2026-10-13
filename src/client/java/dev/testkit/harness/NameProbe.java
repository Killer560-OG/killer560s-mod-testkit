package dev.testkit.harness;

/**
 * Per-call timer around killer560s-mod's name hooks (975-perf-nametag), fed by {@code dev.testkit.mixin.probe}
 * mixins that target the mod's classes by name (config {@code testkit.k560probe.mixins.json}, optional, every injection
 * {@code require = 0}: without the mod it is inert). Only the outermost timed call on a thread counts, so a hook that
 * calls another is not counted twice. Inert (one volatile read) unless {@link #start} was called.
 */
public final class NameProbe {

    public static final String[] HOOKS = {
            "NameReplacer.replace(seq)", "NameReplacer.replace(String)", "NameReplacer.replaceForWidth",
            "NameReplacer.markIfSuppressed", "NameAnimation.animate", "NameAnimation.iterate",
            "PlayerNameDisplay.displayNameFor", "PlayerNameDisplay.modelScaleFor", "SupportersTabText.replace",
    };

    private static volatile boolean on;
    private static final long[] CALLS = new long[HOOKS.length];
    private static final long[] NANOS = new long[HOOKS.length];
    private static final ThreadLocal<long[]> DEPTH = ThreadLocal.withInitial(() -> new long[2]);

    private NameProbe() {
    }

    public static void start() {
        synchronized (CALLS) {
            java.util.Arrays.fill(CALLS, 0);
            java.util.Arrays.fill(NANOS, 0);
        }
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

    public static void enter() {
        if (!on) {
            return;
        }
        long[] d = DEPTH.get();
        if (d[0]++ == 0) {
            d[1] = System.nanoTime();
        }
    }

    public static void exit(int hook) {
        if (!on) {
            return;
        }
        long[] d = DEPTH.get();
        if (d[0] <= 0) {
            return; // started mid-call
        }
        if (--d[0] == 0) {
            long ns = System.nanoTime() - d[1];
            CALLS[hook]++;
            NANOS[hook] += ns;
        }
    }

    /** The cost of one enter/exit pair with nothing between (the timer's own overhead per call), in ns. */
    public static double overheadNs() {
        boolean was = on;
        on = true;
        long[] c = CALLS.clone();
        long[] n = NANOS.clone();
        int k = 200_000;
        long t0 = System.nanoTime();
        for (int i = 0; i < k; i++) {
            enter();
            exit(0);
        }
        long t = System.nanoTime() - t0;
        System.arraycopy(c, 0, CALLS, 0, c.length);
        System.arraycopy(n, 0, NANOS, 0, n.length);
        on = was;
        return t / (double) k;
    }
}
