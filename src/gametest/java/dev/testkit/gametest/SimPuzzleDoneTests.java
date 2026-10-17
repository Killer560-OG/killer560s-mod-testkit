package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.List;
import java.util.Locale;

/**
 * Done-to-resume for every puzzle (killer560, 2026-10-16: "make sure my mod registers when a puzzle is done as fast as
 * possible, right now they all feel like it registers them a lot later than it is actually done so it just sits there
 * awaiting"). Each {@code 93-solve-<room>-resume} case plays the room as {@link SimPuzzleSolveTests} does and stamps, on the
 * test's tick clock: the sim calling the puzzle solved ({@code isComplete}), the "PUZZLE SOLVED!" chat line reaching the
 * client, {@code AutoPuzzleActivity.solvedByHim(room)} turning true, and the first tick {@code AutoPuzzleActivity.isWorking}
 * reads false after the solve - the tick Auto Secret, Autopilot and Auto Routes carry on (the sim's map checks the room
 * from his entry, so the activity is the only thing left they wait on). It fails when that last gap is over the room's
 * bound: 5 ticks where nothing follows the solve (the client
 * learns of a Creeper Beams pair or a board from the server's block updates 2-4 ticks after the sim's own flag), more where the auto still has a step of its own after it (the reward
 * chest, the walk out, the secret) - those bounds are the step, not a wait. Not vacuous: it fails if the sim never says
 * solved or the activity never goes quiet.
 */
public final class SimPuzzleDoneTests {

    private SimPuzzleDoneTests() {
    }

    static final class DoneTimed extends SolveExtra {
        private final String room;
        private final int bound;
        long seen;
        int solved = -1;
        int chat = -1;
        int filed = -1;
        int idle = -1;
        String chatLine = null;

        DoneTimed(String room, int bound) {
            this.room = room;
            this.bound = bound;
        }

        @Override
        void begin(String name, long mark) {
            super.begin(name, mark);
            seen = mark;
        }

        @Override
        void tick(ClientGameTestContext ctx, int t, int solvedAt, boolean container) {
            long now = LogTap.mark();
            for (String l : LogTap.since(seen)) {
                if (chat < 0 && l.contains("[CHAT] PUZZLE SOLVED! ") && l.contains(room + "! Good job!")) {
                    chat = t;
                    chatLine = l.substring(l.indexOf("PUZZLE SOLVED!"));
                }
            }
            seen = now;
            if (solvedAt >= 0 && solved < 0) {
                solved = solvedAt;
            }
            if (filed < 0) {
                Boolean f = ctx.computeOnClient(mc -> {
                    try {
                        return (Boolean) ModUnderTest.staticCall("com.killer560.hub.autopuzzles.AutoPuzzleActivity",
                                "solvedByHim", new Class<?>[]{String.class}, new Object[]{room});
                    } catch (AssertionError | RuntimeException e) {
                        return null; // a jar without solvedByHim
                    }
                });
                if (Boolean.TRUE.equals(f)) {
                    filed = t;
                }
            }
            if (solved >= 0 && idle < 0) {
                boolean working = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(
                        "com.killer560.hub.autopuzzles.AutoPuzzleActivity", "isWorking"));
                if (!working) {
                    idle = t;
                }
            }
        }

        @Override
        boolean satisfied() {
            return idle >= 0;
        }

        @Override
        String verdict(ClientGameTestContext ctx, int solvedAt) {
            if (solved < 0) {
                return "the sim never called " + room + " solved";
            }
            if (idle < 0) {
                return "AutoPuzzleActivity still read working 20 s after the solve";
            }
            if (idle - solved > bound) {
                return String.format(Locale.ROOT, "Auto Secret could carry on only %d tick(s) after the solve (bound %d)",
                        idle - solved, bound);
            }
            return null;
        }

        @Override
        String summary() {
            String line = String.format(Locale.ROOT, "DONE %s: solved at t=%d, chat line %s, solvedByHim %s, not working at "
                            + "t=%s - solve to resume %s tick(s) (bound %d)%s", room, solved,
                    chat < 0 ? "never" : "+" + (chat - solved), filed < 0 ? "never" : "+" + (filed - solved),
                    idle < 0 ? "-" : String.valueOf(idle), idle < 0 || solved < 0 ? "-" : String.valueOf(idle - solved),
                    bound, chatLine == null ? "" : " [" + chatLine + "]");
            SimPuzzleSpeedTests.say(name, line);
            return line;
        }
    }

    abstract static class DoneBase extends SimPuzzleSolveTests.Base {
        private final String room;
        private final int bound;

        DoneBase(SimPuzzleSolveTests.Spec spec, int bound) {
            super(spec);
            this.room = spec.room();
            this.bound = bound;
        }

        @Override
        SolveExtra extra() {
            return new DoneTimed(room, bound);
        }
    }

    private static SimPuzzleSolveTests.Spec spec(String slug, String room, String setter, String getter, String solver,
                                                 String puzzle, int slot, SimPuzzleSolveTests.Approach approach,
                                                 String... probes) {
        return new SimPuzzleSolveTests.Spec(slug, room, setter, getter, solver, puzzle, slot, false, 60, approach, probes);
    }

    /** Nothing after the last pair. */
    public static final class CreeperBeamsDone extends DoneBase {
        public CreeperBeamsDone() {
            super(spec("creeperbeams-resume", "Creeper Beams", "setAutoBeamsEnabled", "isAutoBeamsEnabled",
                    "BeamsSolverConfig", "SimCreeperPuzzle", 1, SimPuzzleSolveTests.Approach.NONE,
                    "BeamsSolverFeature.getActivePairs"), 5);
        }
    }

    /** Nothing after the right chest. */
    public static final class ThreeWeirdosDone extends DoneBase {
        public ThreeWeirdosDone() {
            super(spec("threeweirdos-resume", "Three Weirdos", "setAutoWeirdosEnabled", "isAutoWeirdosEnabled",
                    "WeirdosSolverConfig", "SimQuizPuzzle", SimPuzzleSolveTests.AOTV_SLOT,
                    SimPuzzleSolveTests.Approach.NEAR_WEIRDOS, "WeirdosSolverFeature.getCorrectChestPos"), 5);
        }
    }

    /** Nothing after the final answer. */
    public static final class QuizDone extends DoneBase {
        public QuizDone() {
            super(spec("quiz-resume", "Quiz", "setAutoQuizEnabled", "isAutoQuizEnabled", "QuizSolverConfig",
                    "SimQuizPuzzle", SimPuzzleSolveTests.AOTV_SLOT, SimPuzzleSolveTests.Approach.QUIZ_SPOT,
                    "QuizSolverFeature.getCorrectAnswerPos"), 5);
        }

        @Override
        boolean workingMayBeInstant() {
            return true;
        }
    }

    /** Nothing after the game (Grab Secret and Walk Out off, as 93-solve-tictactoe). */
    public static final class TicTacToeDone extends DoneBase {
        public TicTacToeDone() {
            super(spec("tictactoe-resume", "Tic Tac Toe", "setAutoTicTacToeEnabled", "isAutoTicTacToeEnabled",
                    "TicTacToeSolverConfig", "SimTicTacToePuzzle", SimPuzzleSolveTests.AOTV_SLOT,
                    SimPuzzleSolveTests.Approach.NONE, "TicTacToeSolverFeature.getBestMove"), 5);
        }
    }

    /** The reward chest follows the solve (warp into reach, the click): a step, bounded at 2 s. */
    public static final class IcePathDone extends DoneBase {
        public IcePathDone() {
            super(spec("icepath-resume", "Ice Path", "setAutoIcePathEnabled", "isAutoIcePathEnabled",
                    "IcePathSolverConfig", "SimIcePathPuzzle", 1, SimPuzzleSolveTests.Approach.NONE,
                    "IcePathSolverFeature.getPath"), 40);
        }
    }

    public static final class IceFillDone extends DoneBase {
        public IceFillDone() {
            super(spec("icefill-resume", "Ice Fill", "setAutoIceFillEnabled", "isAutoIceFillEnabled",
                    "IceFillSolverConfig", "SimIceFillPuzzle", SimPuzzleSolveTests.AOTV_SLOT,
                    SimPuzzleSolveTests.Approach.NONE, "IceFillSolverFeature.getCurrentPath"), 40);
        }
    }

    public static final class WaterBoardDone extends DoneBase {
        public WaterBoardDone() {
            super(spec("waterboard-resume", "Water Board", "setAutoWaterEnabled", "isAutoWaterEnabled",
                    "WaterSolverConfig", "SimWaterPuzzle", SimPuzzleSolveTests.AOTV_SLOT,
                    SimPuzzleSolveTests.Approach.NONE, "WaterSolverFeature.nextClick"), 40);
        }
    }

    /** The chest on the end pad, then the walk back out: a step, bounded at 10 s. */
    public static final class TeleportMazeDone extends DoneBase {
        public TeleportMazeDone() {
            super(spec("teleportmaze-resume", "Teleport Maze", "setAutoTeleportMazeEnabled", "isAutoTeleportMazeEnabled",
                    "TeleportMazeSolverConfig", "SimTeleportMazePuzzle", SimPuzzleSolveTests.AOTV_SLOT,
                    SimPuzzleSolveTests.Approach.WALK_ONTO_START_PAD, "TeleportMazeSolverFeature.getVisited"), 200);
        }
    }

    static final List<String> ROOMS = List.of("Creeper Beams", "Three Weirdos", "Quiz", "Tic Tac Toe", "Ice Path", "Ice Fill",
            "Water Board", "Teleport Maze");
}
