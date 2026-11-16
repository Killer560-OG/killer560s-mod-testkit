package dev.testkit.gametest.logic;

import dev.testkit.gametest.mod.Mod;

import java.util.UUID;

/**
 * 1111-logic-term-rate (mod branch termspeed-1111; killer560, 2026-11-11: the sim Terminator "doesn't quite feel like
 * 100% attack speed"): shots per 100 client ticks through the sim's own fire-rate gate at 100 Attack Speed. Wiki:
 * a left-click spam is 4 ticks a shot (5.00 a second), a held right click 5 ticks (4.00 a second, vanilla's cap).
 */
final class TermSpeedCases {

    private static final String TERM = "roomsim.SimTerminator";

    private TermSpeedCases() {
    }

    static void termRate(LogicCase c) throws Exception {
        int left = shots(true);
        int right = shots(false);
        c.eq("100 ticks of left-click spam at 100 AS = 5.00 shots a second (4 ticks a shot)", 25, left);
        c.eq("100 ticks of held right click at 100 AS = 4.00 shots a second (5 ticks a shot)", 20, right);
        Mod.staticCall(TERM, "reset");
    }

    /** A click every tick for 100 ticks; how many the gate let through. */
    private static int shots(boolean leftClick) {
        Mod.staticCall(TERM, "reset");
        UUID who = UUID.randomUUID();
        int n = 0;
        for (int i = 0; i < 100; i++) {
            Mod.staticCall(TERM, "tick");
            if ((Boolean) Mod.staticCall(TERM, "readyToFire", who, leftClick)) {
                n++;
            }
        }
        return n;
    }
}
