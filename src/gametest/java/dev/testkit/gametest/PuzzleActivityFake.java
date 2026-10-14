package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * Stands in for an auto puzzle that is working: while on, it marks the mod's
 * {@code autopuzzles.AutoPuzzleActivity} with the tag "testkit" every client tick, exactly as a running auto puzzle
 * marks it (its free camera's every-tick keep, Auto Boulder while it acts, ...). What reads that signal - Auto Routes'
 * arming, Auto Secret / Autopilot, Auto Clear - is then judged on what it does under it. That the real autos mark it is
 * checked separately, by 93-solve (every solve must have seen it on).
 */
final class PuzzleActivityFake {

    static final String ACTIVITY = "com.killer560.hub.autopuzzles.AutoPuzzleActivity";

    private static volatile boolean on;
    private static boolean hooked;

    private PuzzleActivityFake() {
    }

    static void start(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            if (!hooked) {
                hooked = true;
                ClientTickEvents.START_CLIENT_TICK.register(c -> {
                    if (on) {
                        ModUnderTest.staticCall(ACTIVITY, "note", new Class<?>[]{String.class}, new Object[]{"testkit"});
                    }
                });
            }
            on = true;
        });
        ctx.waitTicks(2);
    }

    static void stop(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> on = false);
    }

    static boolean working(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(ACTIVITY, "isWorking"));
    }
}
