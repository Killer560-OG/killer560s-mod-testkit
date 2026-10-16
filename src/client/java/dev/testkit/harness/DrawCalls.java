package dev.testkit.harness;

/**
 * Draw calls made out of every immediate {@code MultiBufferSource.BufferSource} (vanilla's and any subclass, such as the
 * mod's own world batch), counted by {@code mixin/probe/ProbeBufferSourceDraws} at each {@code RenderType.draw}. 26.1.2
 * only: 26.2 has no such class (the probe config is optional and the mixin {@code @Pseudo}), and {@link #available}
 * says whether anything was ever counted. Render thread; read by 95-perf-world as a per-frame delta between phases.
 */
public final class DrawCalls {

    private static long count;

    private DrawCalls() {
    }

    public static void onDraw() {
        count++;
    }

    public static long count() {
        return count;
    }

    /** True once at least one draw was counted (false on 26.2, where the target class does not exist). */
    public static boolean available() {
        return count > 0;
    }
}
