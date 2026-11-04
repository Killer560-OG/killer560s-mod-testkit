package dev.testkit.gametest.logic;

import dev.testkit.gametest.mod.Mod;

/**
 * Mod branch leapcore-1104 (killer560, 2026-11-04: "if i have someone is leaping to me in core then it should be any
 * person who leaps into core anywhere ... it misses people on occasion"). Drives LeapTracker's per-teammate judgement
 * through its test hooks (testBegin / testStep, id,x,y,z per teammate), no world needed.
 *
 * <ul>
 *   <li>1104-logic-leapcore: in the core a leap landing 12 blocks from him but inside the core counts (also while he
 *       walks), a walker entering the core and a leap that lands outside the core do not; in S3 a landing 12 away does
 *       not, one 3 away does.</li>
 *   <li>1104-logic-leapcore-suppress: a teammate whose leap lands within 3 s of HIS leap counts (the window used to eat
 *       it); one who merely appears (newly tracked) in that window still does not.</li>
 * </ul>
 */
final class LeapCore1104Cases {

    private static final String LT = "leapcounter.LeapTracker";

    private LeapCore1104Cases() {
    }

    private static int step(double sx, double sy, double sz, boolean moving, boolean suppressed, double... mates) {
        return (Integer) Mod.staticCall(LT, "testStep", sx, sy, sz, moving, suppressed, mates);
    }

    static void leapCore(LogicCase c) {
        Mod.staticCall(LT, "testBegin", "CORE");
        int n = 0;
        for (int t = 1; t <= 20; t++) {
            boolean leapt = t >= 5;
            n = step(55, 120, 100, t == 5,  false,
                    1, 55, 120, leapt ? 88 : 10,   // leaps to 12 blocks from him, inside the core
                    2, 55, 120, 50 + 0.3 * t,       // walks in from outside the core
                    3, leapt ? 80 : 55, 120, leapt ? 88 : 10); // leaps to a spot outside the core (x 80)
        }
        c.eq("core: only the leap that lands inside the core counts", 1, n);
        Mod.staticCall(LT, "testBegin", "S3");
        for (int t = 1; t <= 12; t++) {
            boolean leapt = t >= 5;
            n = step(55, 120, 100, false, false,
                    4, 55, 120, leapt ? 88 : 10,  // 12 away: outside the radius
                    5, 55, 120, leapt ? 97 : 10); // 3 away
        }
        c.eq("S3: only the landing inside the radius counts", 1, n);
    }

    static void suppress(LogicCase c) {
        Mod.staticCall(LT, "testBegin", "S3");
        int n = 0;
        for (int t = 1; t <= 12; t++) {
            n = t < 5 ? step(55, 120, 100, false, true, 6, 55, 120, 10)
                    : step(55, 120, 100, false, true, 6, 55, 120, 97,   // leaps to him inside his own-leap window
                            7, 55, 120, 98);                            // just appears: not a leap, not counted
        }
        c.eq("a leap landing inside his own-leap window counts", 1, n);
    }
}
