package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 1123-logic-apclearest (mod branch apclearest-1123): the Dungeons Autopilot's "clear ~N s" estimate, fed the 77 real clear
 * work times (took minus travel) of his 11-22 F7 runs. Room times there were bimodal (about 1 s for a room with nothing left,
 * 6-11 s for ten starred mobs), and the old rate-0.4 average only remembered the last two or three rooms, so the estimate swung
 * from 0.5 s to 6.5 s and ranked rooms on the swing. Measured offline: old 0.53..6.50 (x12), running mean 1.19..3.85 (x3.2).
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class ApClearEstimateLogicTests implements FabricClientGameTest {
    private static final String NAME = "1123-logic-apclearest";
    private static final double[] WORK = {2.4, 1.2, 1.1, 2.0, 0.5, 2.2, 1.3, 1.2, 5.3, 6.5, 1.5, 11.4, 1.1, 2.6, 0.5, 0.5, 5.6,
        10.0, 2.9, 0.6, 4.4, 3.1, 2.5, 0.8, 0.5, 0.6, 0.9, 0.5, 1.6, 2.2, 0.5, 0.5, 5.3, 0.5, 3.0, 5.8, 4.0, 0.6, 1.2, 4.8, 0.5,
        6.1, 3.4, 6.0, 0.6, 0.5, 0.7, 2.2, 0.5, 0.8, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 1.8, 2.4, 2.4, 4.2, 5.6, 2.8, 2.2, 0.5, 2.2,
        0.7, 6.4, 0.5, 0.5, 3.0, 0.5, 0.5, 0.5, 3.0, 0.5, 2.3, 0.5};

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        List<String> problems = new ArrayList<>();
        ctx.runOnClient(mc -> {
            try {
                double e = 5.0;
                double lo = 1e9;
                double hi = 0;
                for (int i = 0; i < WORK.length; i++) {
                    if (i >= 10) {
                        lo = Math.min(lo, e);
                        hi = Math.max(hi, e);
                    }
                    e = (Double) ModUnderTest.staticCall("com.killer560.hub.autosecret.Autopilot", "learnClear",
                            new Class<?>[]{double.class, int.class, double.class}, new Object[]{e, i, WORK[i]});
                }
                System.out.println(String.format(Locale.US, "[%s] estimate after 10 clears: %.2f..%.2f s (x%.1f)", NAME, lo, hi, hi / lo));
                if (hi / lo > 4.0) {
                    problems.add("the clear estimate swings x" + String.format(Locale.US, "%.1f", hi / lo) + " (bound 4)");
                }
                if (e < 1.5 || e > 3.5) {
                    problems.add("the final estimate " + e + " is not near the 2.2 s mean");
                }
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
