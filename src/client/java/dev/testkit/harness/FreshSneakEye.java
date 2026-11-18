package dev.testkit.harness;

import net.minecraft.server.level.ServerPlayer;

/**
 * Hypixel's etherwarp eye for a use whose sneak arrived in the same tick (arwrong-1118, his 2026-11-18 wrong landings):
 * the server sets the sneak at once but crouches him (eye 1.62 -> 1.27) only on its next player tick, so the cast goes from
 * the STANDING eye. On the integrated server the sim casts from the crouched eye always; while {@link #enabled}, a use
 * (or block click) whose sneak was not yet on at the last client tick end ({@code client_tick_end}, the boundary the mod
 * counts too) is run with the server player lifted {@link #LIFT} and set back after, unless the use teleported him.
 * Fed by {@code MixinServerGamePacketListenerUse} on the server thread.
 */
public final class FreshSneakEye {

    /** 1.62 - 1.27: the standing eye over the crouched one. */
    public static final double LIFT = 0.35;

    public static volatile boolean enabled;
    /** Uses and clicks cast from the standing eye since the last {@link #reset}. */
    public static volatile int lifted;

    private static boolean shiftAtTickEnd;
    private static ServerPlayer liftedPlayer;
    private static double liftedX;
    private static double liftedY;
    private static double liftedZ;

    private FreshSneakEye() {
    }

    public static void reset() {
        enabled = false;
        lifted = 0;
        shiftAtTickEnd = false;
        liftedPlayer = null;
    }

    public static void onTickEnd(ServerPlayer p) {
        shiftAtTickEnd = p.isShiftKeyDown();
    }

    public static void before(ServerPlayer p) {
        if (!enabled || liftedPlayer != null || !p.isShiftKeyDown() || shiftAtTickEnd) {
            return;
        }
        liftedPlayer = p;
        liftedX = p.getX();
        liftedY = p.getY();
        liftedZ = p.getZ();
        p.setPos(p.getX(), liftedY + LIFT, p.getZ());
        lifted++;
    }

    public static void after(ServerPlayer p) {
        if (liftedPlayer != p) {
            return;
        }
        liftedPlayer = null;
        if (p.getX() == liftedX && p.getZ() == liftedZ && Math.abs(p.getY() - (liftedY + LIFT)) < 1e-9) {
            p.setPos(liftedX, liftedY, liftedZ);
        }
    }
}
