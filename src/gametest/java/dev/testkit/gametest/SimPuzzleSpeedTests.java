package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The 2026-10-16 speed round (mod branch puzzles-speed-1016, killer560: "Boulder pathfinder needs to be faster and blaze
 * finished needs to be faster. And the boulder pathfinder broke going out, it didn't just run in and out."), as
 * {@code 93-solve-*} cases on top of {@link SimPuzzleSolveTests}: the same room load, items, premise checks and sim
 * verdict, at his sim speed (600), each run of a {@code -PsolveRepeat} launch built at the next quarter turn.
 *
 * <ul>
 *   <li>{@code 93-solve-boulder-speed-aura} (Secret Aura on, his mode) and {@code -boulder-speed-buttons} (Secret Aura off):
 *   the walk back out must be ONE walk - no "pushed off the path", no second "walking to the way out", no re-plan - and
 *   the auto must finish. Timed: start to chest, start to finished, and finished to {@code AutoPuzzleActivity.isWorking}
 *   reading false (what Auto Secret, Autopilot and Auto Routes wait on).</li>
 *   <li>{@code 93-solve-lowerblaze-speed}, {@code -higherblaze-speed}: every blaze the sim reports dead must be the one
 *   due ("blaze N of M died (due: N)"), all M of them, and the puzzle complete. Timed: the first shot to the last kill,
 *   to "Blaze: done", and "done" to not working.</li>
 * </ul>
 * Not vacuous: each case requires the auto's own start line, the kills or the chest, and the finish line it times; a run
 * where any of them is missing fails, it does not pass with blank numbers. Every case prints one {@code SPEED} line.
 */
public final class SimPuzzleSpeedTests {

    private SimPuzzleSpeedTests() {
    }

    static void say(String name, String line) {
        System.out.println("[" + name + "] " + line);
    }

    /** His sim speed (his 2026-10-16 log: "[Sim] Speed set to 600"). */
    static final int HIS_SPEED = 600;

    private static final Pattern KILL = Pattern.compile("Sim blaze puzzle: blaze (\\d{1,3}) of (\\d{1,3}) died \\(due: (\\d{1,3})\\)");

    /** Reads the log every tick and stamps the first tick each line of interest appeared. */
    abstract static class Timed extends SolveExtra {
        long seen;
        int tick;
        int idleAfterEnd = -1;
        int endTick = -1;
        final List<String> notes = new ArrayList<>();

        @Override
        void begin(String name, long mark) {
            super.begin(name, mark);
            seen = mark;
        }

        @Override
        void tick(ClientGameTestContext ctx, int t, int solvedAt, boolean container) {
            tick = t;
            long now = LogTap.mark();
            List<String> lines = LogTap.since(seen);
            seen = now;
            for (String l : lines) {
                line(t, l);
            }
            if (endTick >= 0 && idleAfterEnd < 0) {
                boolean working = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(
                        "com.killer560.hub.autopuzzles.AutoPuzzleActivity", "isWorking"));
                if (!working) {
                    idleAfterEnd = t;
                }
            }
        }

        abstract void line(int t, String l);

        @Override
        boolean satisfied() {
            // Holds the watch open until the end line and the activity going quiet (bounded by the base's 20 s).
            return endTick >= 0 && idleAfterEnd >= 0;
        }

        static String s(int ticks) {
            return ticks < 0 ? "-" : String.format(Locale.ROOT, "%d ticks (%.2f s)", ticks, ticks / 20.0);
        }
    }

    // ------------------------------------------------------------------ Boulder

    static final class BoulderTimed extends Timed {
        int startTick = -1;
        int chestTick = -1;
        int exitWalks = 0;
        int pathWalks = 0;
        int runs = 0;
        int stalls = 0;
        int pushed = 0;
        int replans = 0;
        String gaveUp = null;
        String exitPlan = null;

        /** Every tick from the start to finished (at most 400): where he was and what he did - the
         *  evidence of how a walk went wrong. */
        final List<String> trace = new ArrayList<>();

        @Override
        void tick(ClientGameTestContext ctx, int t, int solvedAt, boolean container) {
            super.tick(ctx, t, solvedAt, container);
            if (startTick >= 0 && endTick < 0 && gaveUp == null && trace.size() < 400) {
                trace.add(ctx.computeOnClient(mc -> {
                    var p = mc.player;
                    var v = p.getDeltaMovement();
                    return String.format(Locale.ROOT, "t=%d pos %.2f %.2f %.2f vel %.2f %.2f yaw %.1f fwd %s sprint %s %s%s",
                            t, p.getX(), p.getY(), p.getZ(), v.x, v.z, p.getYRot(), mc.options.keyUp.isDown(),
                            p.isSprinting(), p.onGround() ? "ground" : "air", p.horizontalCollision ? " COLLIDING" : "");
                }));
            }
        }

        @Override
        void line(int t, String l) {
            if (!l.contains("[AutoPuzzles] Boulder:")) {
                return;
            }
            if (startTick < 0 && l.contains("Boulder: started")) {
                startTick = t;
            }
            if (chestTick < 0 && l.contains("Boulder: chest ") && l.contains(" taken ")) {
                chestTick = t;
            }
            boolean path = l.contains("Boulder: walking to the way out");
            boolean run = l.contains("Boulder: running out - straight");
            if (path || run) {
                exitWalks++;
                pathWalks += path ? 1 : 0;
                runs += run ? 1 : 0;
                if (exitPlan == null) {
                    exitPlan = l.substring(l.indexOf("Boulder:"));
                }
            }
            if (l.contains("made no headway")) {
                stalls++;
                notes.add("t=" + t + " " + l.substring(l.indexOf("Boulder:")));
            }
            if (l.contains("pushed off the path")) {
                pushed++;
                notes.add("t=" + t + " " + l.substring(l.indexOf("Boulder:")));
            }
            if (l.contains("stopped walking to the way out") && !l.contains("arrived")) {
                replans++;
            }
            if (l.contains("stopped for this room")) {
                gaveUp = l.substring(l.indexOf("Boulder:"));
            }
            if (endTick < 0 && l.contains("Boulder: finished")) {
                endTick = t;
            }
        }

        @Override
        String verdict(ClientGameTestContext ctx, int solvedAt) {
            List<String> bad = new ArrayList<>();
            if (startTick < 0) {
                bad.add("Auto Boulder never logged its start - nothing was timed");
            }
            if (chestTick < 0) {
                bad.add("the chest was never taken");
            }
            if (gaveUp != null) {
                bad.add("the auto gave up: " + gaveUp);
            }
            if (endTick < 0) {
                bad.add("the auto never finished");
            }
            if (exitWalks == 0 && endTick >= 0) {
                bad.add("finished without a walk out - the exit was never exercised");
            }
            if (pushed > 0 || pathWalks > 1 || runs > 1 || stalls > 0 || gaveUp != null) {
                for (String tr : trace) {
                    say(name, "exit trace: " + tr);
                }
            }
            if (pushed > 0) {
                bad.add(pushed + " \"pushed off the path\" on the way out (" + String.join("; ", notes) + ")");
            }
            if (pathWalks > 1) {
                bad.add(pathWalks + " walks out planned - it stopped and re-planned " + (pathWalks - 1) + " time(s)");
            }
            if (runs > 1 || stalls > 0) {
                bad.add(runs + " run(s) out, " + stalls + " stalled (" + String.join("; ", notes) + ")");
            }
            if (replans > 0) {
                bad.add(replans + " walk(s) out stopped before arriving");
            }
            if (endTick >= 0 && idleAfterEnd < 0) {
                bad.add("AutoPuzzleActivity still read working 20 s after the finish");
            }
            return bad.isEmpty() ? null : String.join("; ", bad);
        }

        @Override
        String summary() {
            String line = String.format(Locale.ROOT, "SPEED boulder start->chest %s, start->finished %s, finished->not "
                            + "working %s, exit walks %d (path %d, straight run %d), pushed off %d, exit plan: %s",
                    s(chestTick < 0 || startTick < 0 ? -1 : chestTick - startTick),
                    s(endTick < 0 || startTick < 0 ? -1 : endTick - startTick),
                    s(idleAfterEnd < 0 ? -1 : idleAfterEnd - endTick), exitWalks, pathWalks, runs, pushed, exitPlan);
            say(name, line);
            return line;
        }
    }

    abstract static class BoulderSpeedBase extends SimPuzzleSolveTests.Base {
        BoulderSpeedBase(String slug) {
            super(new SimPuzzleSolveTests.Spec(slug, "Boulder", "setAutoBoulderEnabled", "isAutoBoulderEnabled",
                    "BoulderSolverConfig", "SimBoulderPuzzle", SimPuzzleSolveTests.AOTV_SLOT, true, 90,
                    SimPuzzleSolveTests.Approach.NONE, "BoulderSolverFeature.getNextClick",
                    "BoulderSolverFeature.getRemainingClicks"));
        }

        @Override
        int rotation() {
            return ((runNo - 1) % 4) * 90;
        }

        @Override
        int simSpeed() {
            return HIS_SPEED;
        }

        @Override
        SolveExtra extra() {
            return new BoulderTimed();
        }
    }

    /** His mode: Secret Aura on, the run along the roof to the bars and straight back out. */
    public static final class BoulderSpeed extends BoulderSpeedBase {
        public BoulderSpeed() {
            super("boulder-speed-aura");
        }

        @Override
        boolean secretAura() {
            return true;
        }
    }

    /** Secret Aura off: the stairs, the solver's buttons, the chest by looking at it, and back up and out. */
    public static final class BoulderSpeedButtons extends BoulderSpeedBase {
        public BoulderSpeedButtons() {
            super("boulder-speed-buttons");
        }
    }

    // ------------------------------------------------------------------ Blaze

    static final class BlazeTimed extends Timed {
        int firstShot = -1;
        int lastKill = -1;
        int kills = 0;
        int total = -1;
        int shots = 0;
        int repositions = 0;
        int noShot = 0;
        final List<String> outOfOrder = new ArrayList<>();

        @Override
        void line(int t, String l) {
            Matcher m = KILL.matcher(l);
            if (m.find()) {
                int n = Integer.parseInt(m.group(1));
                int due = Integer.parseInt(m.group(3));
                total = Integer.parseInt(m.group(2));
                kills++;
                lastKill = t;
                if (n != due || n != kills) {
                    outOfOrder.add("kill " + kills + " was blaze " + n + " (due " + due + ")");
                }
                return;
            }
            if (!l.contains("[AutoPuzzles] Blaze:")) {
                return;
            }
            if (l.contains("Blaze: shot at")) {
                shots++;
                if (firstShot < 0) {
                    firstShot = t;
                }
            }
            if (l.contains("warp sent onto")) {
                repositions++;
            }
            if (l.contains("no clean shot")) {
                noShot++;
            }
            if (endTick < 0 && l.contains("Blaze: done")) {
                endTick = t;
            }
        }

        @Override
        String verdict(ClientGameTestContext ctx, int solvedAt) {
            List<String> bad = new ArrayList<>();
            if (firstShot < 0) {
                bad.add("Auto Blaze never shot - nothing was timed");
            }
            if (total < 0 || kills != total) {
                bad.add(kills + " kill(s) of " + total + " reported by the sim");
            }
            if (!outOfOrder.isEmpty()) {
                bad.add("out of order: " + String.join("; ", outOfOrder));
            }
            if (solvedAt < 0) {
                bad.add("the sim never called the puzzle complete");
            }
            if (endTick < 0) {
                bad.add("Auto Blaze never said done");
            }
            if (endTick >= 0 && idleAfterEnd < 0) {
                bad.add("AutoPuzzleActivity still read working 20 s after done");
            }
            return bad.isEmpty() ? null : String.join("; ", bad);
        }

        @Override
        String summary() {
            String line = String.format(Locale.ROOT, "SPEED blaze first shot->last kill %s, first shot->done %s, done->not "
                            + "working %s, %d kill(s) of %d in order %s, %d shot(s), %d warp(s), %d no-clean-shot",
                    s(lastKill < 0 || firstShot < 0 ? -1 : lastKill - firstShot),
                    s(endTick < 0 || firstShot < 0 ? -1 : endTick - firstShot),
                    s(idleAfterEnd < 0 ? -1 : idleAfterEnd - endTick), kills, total, outOfOrder.isEmpty(), shots,
                    repositions, noShot);
            say(name, line);
            return line;
        }
    }

    abstract static class BlazeSpeedBase extends SimPuzzleSolveTests.Base {
        BlazeSpeedBase(String slug, String room) {
            super(new SimPuzzleSolveTests.Spec(slug, room, "setAutoBlazeEnabled", "isAutoBlazeEnabled",
                    "BlazeSolverConfig", "SimBlazePuzzle", 1, false, 60, SimPuzzleSolveTests.Approach.NONE,
                    "BlazeSolverFeature.getOrderedBlazes"));
        }

        @Override
        int rotation() {
            return ((runNo - 1) % 4) * 90;
        }

        @Override
        int simSpeed() {
            return HIS_SPEED;
        }

        @Override
        SolveExtra extra() {
            return new BlazeTimed();
        }
    }

    public static final class LowerBlazeSpeed extends BlazeSpeedBase {
        public LowerBlazeSpeed() {
            super("lowerblaze-speed", "Lower Blaze");
        }
    }

    public static final class HigherBlazeSpeed extends BlazeSpeedBase {
        public HigherBlazeSpeed() {
            super("higherblaze-speed", "Higher Blaze");
        }
    }
}
