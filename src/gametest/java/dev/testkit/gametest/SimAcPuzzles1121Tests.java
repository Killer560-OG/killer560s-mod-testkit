package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * Auto Clear drives the puzzle autos, so their idle ticks add up (mod acpuzzles-1121, killer560 11-21: the whole floor
 * must clear in about 1:50). Each case measures the ticks from the auto going on to the room solved and fails over a
 * bound set between the origin jar and the fix, and the room must really be solved.
 * <ul>
 *   <li>{@code 1121-sim-acpuzzles-icefill}: every hop's landing arrives about 3 ticks after its use (a 100 ms server
 *   stall on every hop, as Hypixel's round trip does). The landing's position packet sets the gate's 1-tick teleport
 *   settle after the hop's 3-tick self-teleport waiver ended, so the next hop was denied and waited ~3 ticks.</li>
 * </ul>
 */
public final class SimAcPuzzles1121Tests {

    private SimAcPuzzles1121Tests() {
    }

    /** Ticks bounds: origin measured 350 ticks, the fix 306 (11-21); the bound sits between. */
    static final int ICEFILL_BOUND = Integer.getInteger("testkit.acp.icefill", 330);

    public static final class IceFill extends SimPuzzleSolveTests.Base {
        public IceFill() {
            super(new SimPuzzleSolveTests.Spec("icefill-1121", "Ice Fill", "setAutoIceFillEnabled",
                    "isAutoIceFillEnabled", "IceFillSolverConfig", "SimIceFillPuzzle", SimPuzzleSolveTests.AOTV_SLOT,
                    false, 90, SimPuzzleSolveTests.Approach.NONE, "IceFillSolverFeature.getCurrentPath"));
        }

        @Override
        String scenarioName() {
            return "1121-sim-acpuzzles-icefill";
        }

        @Override
        SolveExtra extra() {
            return new SolveExtra() {
                int spikes;
                boolean armed;
                int ticksTaken = -1;

                @Override
                void configure(Object auto, Object cheat) {
                    ModUnderTest.set(auto, "setIceFillAdaptive", false);
                    ModUnderTest.set(auto, "setIceFillDelayTicks", 1);
                }

                @Override
                void tick(ClientGameTestContext ctx, int t, int solvedAt, boolean container) {
                    if (solvedAt >= 0) {
                        return;
                    }
                    if (armed) {
                        if (dev.testkit.harness.LagSpike.releasedAt() != 0L) {
                            armed = false;
                            spikes++;
                        }
                        return;
                    }
                    dev.testkit.harness.LagSpike.armOnNextUse(100);
                    armed = true;
                }

                @Override
                String verdict(ClientGameTestContext ctx, int solvedAt) {
                    dev.testkit.harness.LagSpike.disarm();
                    ticksTaken = solvedAt;
                    if (solvedAt <= 0) {
                        return "Ice Fill was not solved";
                    }
                    if (spikes < 20) {
                        return "only " + spikes + " stalled hop(s) - nothing measured";
                    }
                    return solvedAt <= ICEFILL_BOUND ? null : "took " + solvedAt + " ticks (over " + ICEFILL_BOUND + ")";
                }

                @Override
                String summary() {
                    return spikes + " stalled hops, solved in " + ticksTaken + " ticks (bound " + ICEFILL_BOUND + ")";
                }
            };
        }
    }
}
