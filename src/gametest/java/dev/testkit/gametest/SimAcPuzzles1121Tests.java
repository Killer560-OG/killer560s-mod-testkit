package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * Auto Clear drives the puzzle autos, so their idle ticks add up (mod acpuzzles-1121, killer560 11-21: the whole floor
 * must clear in about 1:50). Each case measures the ticks from the auto going on to the room solved and fails over a
 * bound set between the origin jar and the fix, and the room must really be solved.
 * <ul>
 *   <li>{@code 1121-sim-acpuzzles-icefill}: every hop's landing arrives about 3 ticks after its use (a 150 ms server
 *   stall on every hop, as Hypixel's round trip does). The landing's position packet sets the gate's 1-tick teleport
 *   settle after the hop's 3-tick self-teleport waiver ended, so the next hop was denied and waited ~3 ticks.</li>
 * </ul>
 */
public final class SimAcPuzzles1121Tests {

    private SimAcPuzzles1121Tests() {
    }

    /** Ticks bounds: origin measured 462 ticks, the fix 384 (11-21, 150 ms stalls); the bound sits between. */
    static final int ICEFILL_BOUND = Integer.getInteger("testkit.acp.icefill", 420);

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
                    dev.testkit.harness.LagSpike.armOnNextUse(150);
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

    /** Measure only by default: the maze's pad links are random, so its ticks vary run to run. */
    static final int MAZE_BOUND = Integer.getInteger("testkit.acp.maze", 100000);

    /**
     * {@code 1121-sim-acpuzzles-teleportmaze} (mod acpuzzles2-1121): abilities are refused in the maze, so every leg is on
     * foot, and Auto Teleport Maze held forward only, at walking pace. The case samples every client tick the auto holds
     * forward on the ground and fails unless most of them sprint and the average speed is above walking pace
     * (walk ~0.216, sprint ~0.28 blocks a tick); the maze must really be solved.
     */
    public static final class TeleportMaze extends SimPuzzleSolveTests.Base {
        public TeleportMaze() {
            super(new SimPuzzleSolveTests.Spec("teleportmaze-1121", "Teleport Maze", "setAutoTeleportMazeEnabled",
                    "isAutoTeleportMazeEnabled", "TeleportMazeSolverConfig", "SimTeleportMazePuzzle",
                    SimPuzzleSolveTests.AOTV_SLOT, false, 60, SimPuzzleSolveTests.Approach.WALK_ONTO_START_PAD,
                    "TeleportMazeSolverFeature.getVisited", "TeleportMazeSolverFeature.getTeleportSeq"));
        }

        @Override
        String scenarioName() {
            return "1121-sim-acpuzzles-teleportmaze";
        }

        @Override
        SolveExtra extra() {
            return new SolveExtra() {
                int walkTicks;
                int sprintTicks;
                double walked;
                double lastX = Double.NaN;
                double lastZ;
                int ticksTaken = -1;

                @Override
                void tick(ClientGameTestContext ctx, int t, int solvedAt, boolean container) {
                    if (solvedAt >= 0) {
                        return;
                    }
                    double[] s = ctx.computeOnClient(mc -> new double[]{mc.player.getX(), mc.player.getZ(),
                            mc.options.keyUp.isDown() && mc.player.onGround() ? 1 : 0, mc.player.isSprinting() ? 1 : 0});
                    if (!Double.isNaN(lastX) && s[2] == 1) {
                        double step = Math.hypot(s[0] - lastX, s[1] - lastZ);
                        if (step > 0.05 && step < 1.0) { // a pad teleport or a standing tick is no walking sample
                            walkTicks++;
                            walked += step;
                            if (s[3] == 1) {
                                sprintTicks++;
                            }
                        }
                    }
                    lastX = s[0];
                    lastZ = s[1];
                }

                @Override
                String verdict(ClientGameTestContext ctx, int solvedAt) {
                    ticksTaken = solvedAt;
                    if (solvedAt <= 0) {
                        return "the Teleport Maze was not solved";
                    }
                    if (walkTicks < 20) {
                        return "only " + walkTicks + " walking tick(s) - nothing measured";
                    }
                    if (sprintTicks * 2 < walkTicks) {
                        return String.format("sprinted on %d of %d walking ticks (%.3f blocks a tick)", sprintTicks,
                                walkTicks, walked / walkTicks);
                    }
                    if (walked / walkTicks < 0.24) {
                        return String.format("walking pace: %.3f blocks a tick", walked / walkTicks);
                    }
                    return solvedAt <= MAZE_BOUND ? null : "took " + solvedAt + " ticks (over " + MAZE_BOUND + ")";
                }

                @Override
                String summary() {
                    return String.format("solved in %d ticks, sprinted %d of %d walking ticks, %.3f blocks a tick",
                            ticksTaken, sprintTicks, walkTicks, walkTicks == 0 ? 0 : walked / walkTicks);
                }
            };
        }
    }
}
