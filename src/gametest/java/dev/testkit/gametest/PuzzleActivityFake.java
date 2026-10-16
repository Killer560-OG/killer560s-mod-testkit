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
    /** The tags marked, one per tick in turn (default the one "testkit"). */
    private static volatile String[] tags = {"testkit"};
    private static int turn;

    private PuzzleActivityFake() {
    }

    static void start(ClientGameTestContext ctx) {
        start(ctx, "testkit");
    }

    /**
     * Marks one of {@code marks} each tick, in turn - as Ice Path's chest step ended a tick on "Ice Path chest" (its free
     * camera) and a clicking tick on "Puzzles" (the click), which main read as a new name every tick (killer560's log,
     * 2026-10-15).
     */
    static void start(ClientGameTestContext ctx, String... marks) {
        tags = marks.clone();
        ctx.runOnClient(mc -> {
            if (!hooked) {
                hooked = true;
                ClientTickEvents.START_CLIENT_TICK.register(c -> {
                    if (on) {
                        String[] t = tags;
                        String tag = t[Math.floorMod(turn++, t.length)];
                        ModUnderTest.staticCall(ACTIVITY, "note", new Class<?>[]{String.class}, new Object[]{tag});
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
