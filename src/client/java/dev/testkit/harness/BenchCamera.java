package dev.testkit.harness;

import net.minecraft.client.Minecraft;

/**
 * A fixed camera path for the perf benches (95-perf-*): while {@link #start} is in effect, every client tick sets the
 * player's yaw to {@code startYaw + 360 * tick / period} and the pitch to a constant, so each phase of a bench looks
 * in exactly the same directions in the same order (a phase whose length is a multiple of the period sees the whole
 * circle). Client-side rotation only; the benches run in the integrated sim, never against the anticheat.
 * Ticked from {@code MixinFrameClockMinecraft} at the head of {@code Minecraft.tick}.
 */
public final class BenchCamera {

    private static volatile boolean active;
    private static float startYaw;
    private static float pitch;
    private static int period = 200;
    private static long tick;

    private BenchCamera() {
    }

    /** Begin (or restart from tick 0) the turn: one full circle every {@code periodTicks}. */
    public static void start(float yaw, float pitchDeg, int periodTicks) {
        startYaw = yaw;
        pitch = pitchDeg;
        period = Math.max(1, periodTicks);
        tick = 0;
        active = true;
    }

    public static void stop() {
        active = false;
    }

    public static boolean active() {
        return active;
    }

    public static void tickHead(Minecraft mc) {
        if (!active || mc.player == null) {
            return;
        }
        float yaw = startYaw + 360f * (tick % period) / period;
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);
        mc.player.yRotO = yaw;
        mc.player.xRotO = pitch;
        tick++;
    }
}
