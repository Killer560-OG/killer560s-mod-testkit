package dev.testkit.harness;

import net.minecraft.server.level.ServerPlayer;

/**
 * 1121-sim-acgaps2-warp-lost: the sim server drops ONE sneaking use (an etherwarp) as Hypixel does now and then (his
 * acslow-1121 log, door 81: use seq 15 never answered). Counts every sneaking use it sees while armed; the {@link #dropAt}-th
 * (1-based) is thrown away unanswered. Read and written from the server thread through {@code MixinServerGamePacketListenerUse}.
 */
public final class WarpDrop {

    /** 1-based index of the sneaking use to drop; 0 = count only; -1 = off. */
    public static volatile int dropAt = -1;
    public static volatile int seen;
    public static volatile int dropped;

    private WarpDrop() {
    }

    public static void arm(int at) {
        seen = 0;
        dropped = 0;
        dropAt = at;
    }

    public static boolean drop(ServerPlayer sp) {
        if (dropAt < 0 || sp == null || !sp.isShiftKeyDown()) {
            return false;
        }
        if (++seen == dropAt) {
            dropped++;
            return true;
        }
        return false;
    }
}
