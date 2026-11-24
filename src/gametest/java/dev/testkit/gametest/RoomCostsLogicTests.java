package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 1124-logic-roomcosts (mod branch idea-roomcosts-1124): Learned Room Costs. Auto Clear's RoomClock records how long a room
 * took when it ends "cleared"; the clear-time estimate and Dungeons Autopilot read the median once a room has 3 clears,
 * the old guess before that. Proves: the guess before 3 clears, the median after, an unreal time is not kept, a room
 * ended through the real RoomClock lands in the record, the figure survives a reload from the shared store, and the
 * switch puts the guess back.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class RoomCostsLogicTests implements FabricClientGameTest {
    private static final String NAME = "1124-logic-roomcosts";
    private static final String COSTS = "com.killer560.hub.autoclear.RoomCosts";
    private static final String CLOCK = "com.killer560.hub.autoclear.RoomClock";
    private static final String CFG = "com.killer560.hub.autoclear.AutoClearConfig";
    private static final double GUESS = 12.0;

    private static void record(String room, double s) {
        ModUnderTest.staticCall(COSTS, "record", new Class<?>[]{String.class, double.class}, new Object[]{room, s});
    }

    private static double seconds(String room) {
        return (Double) ModUnderTest.staticCall(COSTS, "seconds", new Class<?>[]{String.class, double.class},
                new Object[]{room, GUESS});
    }

    private static int samples(String room) {
        return (Integer) ModUnderTest.staticCall(COSTS, "samples", new Class<?>[]{String.class}, new Object[]{room});
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        List<String> problems = new ArrayList<>();
        ctx.runOnClient(mc -> {
            try {
                ModUnderTest.staticCall(COSTS, "reloadForTest");
                record("Zz Test A", 5.0);
                record("Zz Test A", 7.0);
                if (seconds("Zz Test A") != GUESS) {
                    problems.add("two clears already changed the figure to " + seconds("Zz Test A"));
                }
                record("Zz Test A", 6.0);
                if (Math.abs(seconds("Zz Test A") - 6.0) > 1e-9) {
                    problems.add("three clears 5, 7, 6 should give the median 6, got " + seconds("Zz Test A"));
                }
                record("Zz Test A", 0.2);
                record("Zz Test A", 400.0);
                if (samples("Zz Test A") != 3) {
                    problems.add("an unreal time was kept: " + samples("Zz Test A") + " samples, wanted 3");
                }
                if (seconds("Zz Other") != GUESS) {
                    problems.add("a room never cleared did not keep the guess");
                }
            } catch (Throwable t) {
                problems.add("threw: " + t);
            }
        });
        // A room ended through the real clock, three times, about 31 ticks each.
        for (int run = 0; run < 3; run++) {
            ctx.runOnClient(mc -> ModUnderTest.staticCall(CLOCK, "start", new Class<?>[]{String.class}, new Object[]{"Zz Clock"}));
            for (int t = 0; t < 30; t++) {
                ctx.runOnClient(mc -> ModUnderTest.staticCall(CLOCK, "tick", new Class<?>[]{String.class, boolean.class},
                        new Object[]{"clearing", false}));
                ctx.waitTicks(1);
            }
            ctx.runOnClient(mc -> ModUnderTest.staticCall(CLOCK, "end", new Class<?>[]{String.class}, new Object[]{"cleared"}));
        }
        ctx.runOnClient(mc -> {
            try {
                double s = seconds("Zz Clock");
                System.out.println(String.format(Locale.US, "[%s] Zz Clock: %d samples, %.2f s", NAME, samples("Zz Clock"), s));
                if (samples("Zz Clock") != 3 || s == GUESS || s < 1.0 || s > 3.0) {
                    problems.add("the clock's three cleared rooms gave " + samples("Zz Clock") + " samples, " + s + " s");
                }
                // a room left (not cleared) must not be recorded
                ModUnderTest.staticCall(CLOCK, "start", new Class<?>[]{String.class}, new Object[]{"Zz Left"});
                ModUnderTest.staticCall(CLOCK, "end", new Class<?>[]{String.class}, new Object[]{"gave up: test"});
                if (samples("Zz Left") != 0) {
                    problems.add("a room that was given up was recorded");
                }
                ModUnderTest.staticCall(COSTS, "reloadForTest");
                if (Math.abs(seconds("Zz Test A") - 6.0) > 1e-9 || samples("Zz Clock") != 3) {
                    problems.add("after a reload from the store: Zz Test A " + seconds("Zz Test A") + ", Zz Clock samples "
                            + samples("Zz Clock"));
                }
                Object cfg = ModUnderTest.staticCall(CFG, "getInstance");
                cfg.getClass().getMethod("setLearnedRoomCosts", boolean.class).invoke(cfg, false);
                if (seconds("Zz Test A") != GUESS) {
                    problems.add("the switch off still used the learned figure");
                }
                cfg.getClass().getMethod("setLearnedRoomCosts", boolean.class).invoke(cfg, true);
            } catch (Throwable t) {
                problems.add("threw: " + t);
            }
        });
        for (String p : problems) {
            System.out.println("[" + NAME + "] FAIL: " + p);
        }
        if (!problems.isEmpty()) {
            throw new AssertionError(String.join("; ", problems));
        }
        System.out.println("[" + NAME + "] PASS");
    }
}
