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

    /**
     * puzzle-leave-1020 (killer560, 2026-10-20: "it waits a long time after finishing"; his Mod Only Test log, Higher Blaze
     * "room finished and the secret trip over" at 09:55:27, Autopilot's "puzzle Higher Blaze: finished" at 09:55:34 when the
     * map's check came, handed back 09:55:35). Auto Secret is put in Dungeon Autopilot's PUZZLE_WAIT for the room before
     * the auto is switched on (reflection, so any jar runs it); the room then plays as 93-solve-higherblaze. Stamped on the
     * test's tick clock: the auto's "ended its last step" line and Auto Secret's "puzzle Higher Blaze: ..." line (its leave).
     * Fails when it leaves more than 2 ticks after the auto ended, before the auto ended, or not within 20 s of the solve.
     * NOT a main-vs-fix discriminator: the sim sends its "PUZZLE SOLVED!" line with the solve, a tick before the auto
     * ends, so main leaves on that check at the same tick (2026-10-20: both 0 ticks; the fixed jar's line names the auto's
     * own signal). On Hypixel the check came 7 s late; holding it back from the test thread raced the packet and failed.
     */
    static final class LeaveTimed extends SolveExtra {
        private final String room;
        long seen;
        boolean armed;
        String armWhy = "";
        int ended = -1;
        int left = -1;
        String leftLine;
        String stoppedLine;
        boolean stopped;

        LeaveTimed(String room) {
            this.room = room;
        }

        @Override
        void begin(String name, long mark) {
            super.begin(name, mark);
            seen = mark;
        }

        @Override
        void beforeAutoOn(ClientGameTestContext ctx) {
            armWhy = ctx.computeOnClient(mc -> armPuzzleWait(mc, room));
            armed = armWhy.isEmpty();
            SimPuzzleSpeedTests.say(name, "LEAVE " + room + ": Auto Secret put in PUZZLE_WAIT - "
                    + (armed ? "ok" : armWhy));
        }

        @Override
        void tick(ClientGameTestContext ctx, int t, int solvedAt, boolean container) {
            if (!armed) {
                return;
            }
            long now = LogTap.mark();
            for (String l : LogTap.since(seen)) {
                if (ended < 0 && l.contains("[AutoPuzzles] Activity: ") && l.contains("ended its last step")) {
                    ended = t;
                }
                if (left < 0 && l.contains("[AutoSecret] puzzle " + room + ":")) {
                    left = t;
                    leftLine = l.substring(l.indexOf("[AutoSecret]"));
                }
                if (stoppedLine == null && l.contains("[AutoSecret] stopped:")) {
                    stoppedLine = l.substring(l.indexOf("[AutoSecret]"));
                }
            }
            seen = now;
            if ((left >= 0 || stoppedLine != null) && !stopped) {
                stopped = true;
                ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.autosecret.AutoSecretFeature", "stop",
                        new Class<?>[]{String.class, boolean.class}, new Object[]{"test over", true}));
            }
        }

        @Override
        boolean satisfied() {
            return !armed || left >= 0 || stoppedLine != null;
        }

        @Override
        String verdict(ClientGameTestContext ctx, int solvedAt) {
            if (!stopped && armed) {
                ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.autosecret.AutoSecretFeature", "stop",
                        new Class<?>[]{String.class, boolean.class}, new Object[]{"test over", true}));
                stopped = true;
            }
            if (!armed) {
                return "could not put Auto Secret in PUZZLE_WAIT: " + armWhy;
            }
            if (ended < 0) {
                return "the auto never ended its last step";
            }
            if (left < 0) {
                return stoppedLine != null ? "Auto Secret stopped before it left the puzzle: " + stoppedLine
                        : "Auto Secret was still in PUZZLE_WAIT 20 s after the solve (" + (solvedAt < 0 ? "?" : "solve t="
                        + solvedAt) + ", auto ended t=" + ended + ")";
            }
            if (left < ended) {
                return "Auto Secret left the puzzle (t=" + left + ") before its auto ended (t=" + ended + ")";
            }
            if (left - ended > 2) {
                return "Auto Secret left " + (left - ended) + " tick(s) after the auto ended (bound 2): " + leftLine;
            }
            return null;
        }

        @Override
        String summary() {
            String line = String.format(Locale.ROOT, "LEAVE %s: auto ended t=%d, Auto Secret left t=%d - %s tick(s) [%s]%s",
                    room, ended, left, ended < 0 || left < 0 ? "-" : String.valueOf(left - ended), leftLine,
                    stoppedLine == null ? "" : " stopped: " + stoppedLine);
            SimPuzzleSpeedTests.say(name, line);
            return line;
        }

        /** Auto Secret into Dungeon Autopilot's PUZZLE_WAIT for {@code room}, by its fields. @return "" or why not */
        private static String armPuzzleWait(net.minecraft.client.Minecraft mc, String room) {
            try {
                Class<?> c = Class.forName("com.killer560.hub.autosecret.AutoSecretFeature");
                Class<?> phase = Class.forName("com.killer560.hub.autosecret.AutoSecretFeature$Phase");
                Object wait = null;
                for (Object o : phase.getEnumConstants()) {
                    if ("PUZZLE_WAIT".equals(o.toString())) {
                        wait = o;
                    }
                }
                set(c, "level", mc.level);
                set(c, "pilot", true);
                set(c, "target", room);
                set(c, "phaseTicks", 0);
                try {
                    set(c, "puzzleSince", ModUnderTest.staticCall("com.killer560.hub.autopuzzles.AutoPuzzleActivity",
                            "clock"));
                } catch (ReflectiveOperationException | AssertionError e) {
                    // a jar from before the auto's own done signal: it has no puzzleSince
                }
                set(c, "phase", wait);
                return "";
            } catch (ReflectiveOperationException | RuntimeException e) {
                return String.valueOf(e);
            }
        }

        private static void set(Class<?> c, String field, Object value) throws ReflectiveOperationException {
            var f = c.getDeclaredField(field);
            f.setAccessible(true);
            f.set(null, value);
        }
    }

    /** Higher Blaze under Dungeon Autopilot's PUZZLE_WAIT (see {@link LeaveTimed}). */
    public static final class HigherBlazeLeave extends SimPuzzleSolveTests.Base {
        public HigherBlazeLeave() {
            super(new SimPuzzleSolveTests.Spec("higherblaze-leave", "Higher Blaze", "setAutoBlazeEnabled",
                    "isAutoBlazeEnabled", "BlazeSolverConfig", "SimBlazePuzzle", SimPuzzleSolveTests.TERM_SLOT, false, 60,
                    SimPuzzleSolveTests.Approach.NONE, "BlazeSolverFeature.getOrderedBlazes"));
        }

        @Override
        SolveExtra extra() {
            return new LeaveTimed("Higher Blaze");
        }
    }

    static final List<String> ROOMS = List.of("Creeper Beams", "Three Weirdos", "Quiz", "Tic Tac Toe", "Ice Path", "Ice Fill",
            "Water Board", "Teleport Maze");
}
