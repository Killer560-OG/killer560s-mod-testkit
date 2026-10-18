package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * Since mod sim-secrets-1018 a sim floor's secret chests appear only when the player comes within 10 blocks for 5 ticks,
 * as secret bats do (killer560, 2026-10-18). A scenario about something else that reads a far secret chest as a block (a
 * floor-wide chest count, a doorway picture, a room's secret scan, a mimic moved between chests) turns that off for its
 * own run with {@link #placeAtBuild} and back on with {@link #restore}. A jar without the hook (before sim-secrets-1018)
 * places every chest at the build anyway, so both are no-ops there.
 */
public final class SimChestHook {

    private static final String ITEMS = "com.killer560.hub.roomsim.SimSecretItems";

    private SimChestHook() {
    }

    /** Every secret chest in at the build, as before 2026-10-18. Call before the floor is built. */
    public static void placeAtBuild(ClientGameTestContext ctx) {
        set(ctx, false);
    }

    /** The mod's own rule again: secret chests appear when he comes near. */
    public static void restore(ClientGameTestContext ctx) {
        set(ctx, true);
    }

    private static void set(ClientGameTestContext ctx, boolean appearNear) {
        ctx.runOnClient(mc -> {
            try {
                Class.forName(ITEMS).getMethod("setChestsAppearNearForTest", boolean.class).invoke(null, appearNear);
            } catch (ReflectiveOperationException ignored) {
                // a jar from before sim-secrets-1018: chests are placed at the build
            }
        });
    }
}
