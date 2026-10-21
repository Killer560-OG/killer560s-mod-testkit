package dev.testkit.harness;

/**
 * A free camera for video clips (1913-video-autoroutes-spectator): while {@link #active}, {@code MixinVideoCamera} draws
 * the frame from this position and rotation instead of the camera entity's eyes. Render-side only: the local player's
 * position, rotation, input and packets are untouched (the camera entity stays the player, so
 * {@code LocalPlayer.isControlledCamera} still holds and he keeps moving and sending as before). Inert unless set.
 */
public final class VideoCamera {

    private static volatile boolean active;
    private static volatile double x;
    private static volatile double y;
    private static volatile double z;
    private static volatile float yaw;
    private static volatile float pitch;

    private VideoCamera() {
    }

    public static void set(double px, double py, double pz, float pYaw, float pPitch) {
        x = px;
        y = py;
        z = pz;
        yaw = pYaw;
        pitch = Math.max(-90f, Math.min(90f, pPitch));
        active = true;
    }

    public static void clear() {
        active = false;
    }

    public static boolean active() {
        return active;
    }

    public static double x() {
        return x;
    }

    public static double y() {
        return y;
    }

    public static double z() {
        return z;
    }

    public static float yaw() {
        return yaw;
    }

    public static float pitch() {
        return pitch;
    }
}
