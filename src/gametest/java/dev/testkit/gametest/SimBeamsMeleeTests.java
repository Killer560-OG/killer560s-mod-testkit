package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code 1018-beams-melee-*} (mod branch todo-creeper-melee-1018, killer560's to-do item 29: "For creeper beams auto puzzle
 * make a mele option that basically etherwwarps and teleports around the room and will mele the blocks to do the
 * puzzle"). Auto Creeper Beams in its new Melee mode, on the same room load, items, premise checks and sim verdict as
 * {@link SimPuzzleSolveTests} ({@code SimCreeperPuzzle.isComplete()}, never failed).
 *
 * <p>On top of the solve, the case proves it was the MELEE that did it, not the bow:
 * <ul>
 *   <li>every lantern of every pair the sim joined was struck by a left click the sim counted ("Sim creeper beams: struck
 *   lantern ..."), each from within 4.5 blocks eye to the lantern's box (the sim refuses a hit from further and says so;
 *   no refusal is allowed either), and each joined lantern reads prismarine on the client;</li>
 *   <li>no shot: the Terminator fired no arrow ({@code SimTerminator.arrowsFired}), no sim "shot landed" line and no Shoot
 *   mode "shot at" line;</li>
 *   <li>the auto etherwarped at least once ("Beams: warp sent onto") and logged its own melee hits.</li>
 * </ul>
 * A jar without the mode (main before the branch) has no {@code AutoPuzzlesConfig.setAutoBeamsMode}: the case records that
 * and fails. Every run prints a {@code MELEE} line: hits, sim strikes, warps, retries, first hit to solved.
 *
 * <p>{@code -solve} plays the build's own rotation; {@code -rotations} builds the room at the next quarter turn each run
 * (use {@code -PsolveRepeat=4}).
 */
public final class SimBeamsMeleeTests {

    private SimBeamsMeleeTests() {
    }

    private static final String AUTO_CONFIG = "com.killer560.hub.autopuzzles.AutoPuzzlesConfig";
    private static final String PUZZLE = "com.killer560.hub.roomsim.puzzles.SimCreeperPuzzle";
    private static final String TERMINATOR = "com.killer560.hub.roomsim.SimTerminator";
    private static final double REACH = 4.5;

    private static final Pattern STRUCK = Pattern.compile(
            "Sim creeper beams: struck lantern BlockPos\\{x=(-?\\d{1,8}), y=(-?\\d{1,4}), z=(-?\\d{1,8})\\} \\(pair (\\d{1,3})\\) "
                    + "from (\\d{1,3}\\.\\d{2}) blocks");
    private static final Pattern HIT = Pattern.compile("Beams \\(melee\\): hit (-?\\d{1,8}), (-?\\d{1,4}), (-?\\d{1,8}) "
            + "\\(pair stage (\\d)\\) from .{1,40}, eye to box (\\d{1,3}\\.\\d{2})");

    static void say(String name, String line) {
        System.out.println("[" + name + "] " + line);
    }

    /** The log read every tick: the auto's hits, the sim's strikes and refusals, shots and warps. */
    static final class MeleeWatch extends SolveExtra {
        boolean noMode;
        String noModeWhy = "";
        long seen;
        int arrows0 = -1;
        int firstHit = -1;
        int solved = -1;
        final List<String> hits = new ArrayList<>();
        final Map<BlockPos, Double> struck = new LinkedHashMap<>();
        int strikes;
        int refused;
        int shotsLanded;
        int shootLines;
        int warps;
        int retries;
        double maxHitDist;
        double maxStruckDist;

        @Override
        void begin(String name, long mark) {
            super.begin(name, mark);
            seen = mark;
        }

        @Override
        void configure(Object auto, Object cheat) {
            try {
                Class<?> mode = Class.forName(AUTO_CONFIG + "$BeamsMode");
                ModUnderTest.call(auto, "setAutoBeamsMode", new Class<?>[]{mode},
                        new Object[]{ModUnderTest.enumValue(AUTO_CONFIG + "$BeamsMode", "MELEE")});
            } catch (ClassNotFoundException | AssertionError e) {
                noMode = true;
                noModeWhy = String.valueOf(e.getMessage());
            }
        }

        @Override
        void beforeAutoOn(ClientGameTestContext ctx) {
            arrows0 = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(TERMINATOR, "arrowsFired"));
            say(name, noMode ? "this jar has NO Melee mode for Auto Creeper Beams (" + noModeWhy + ")"
                    : "Auto Creeper Beams mode set to MELEE; Terminator arrows so far " + arrows0);
        }

        @Override
        void tick(ClientGameTestContext ctx, int t, int solvedAt, boolean container) {
            long now = LogTap.mark();
            if (now > seen) {
                List<String> fresh = LogTap.since(seen);
                seen = now;
                for (String l : fresh) {
                    read(l, t);
                }
            }
            if (solvedAt > 0 && solved < 0) {
                solved = solvedAt;
            }
        }

        private void read(String l, int t) {
            Matcher s = STRUCK.matcher(l);
            if (s.find()) {
                BlockPos p = new BlockPos(Integer.parseInt(s.group(1)), Integer.parseInt(s.group(2)),
                        Integer.parseInt(s.group(3)));
                double d = Double.parseDouble(s.group(5));
                struck.merge(p, d, Math::max);
                maxStruckDist = Math.max(maxStruckDist, d);
                strikes++;
                return;
            }
            Matcher h = HIT.matcher(l);
            if (h.find()) {
                if (firstHit < 0) {
                    firstHit = t;
                }
                double d = Double.parseDouble(h.group(5));
                maxHitDist = Math.max(maxHitDist, d);
                hits.add(h.group(1) + "," + h.group(2) + "," + h.group(3) + " stage " + h.group(4) + " at " + h.group(5));
                return;
            }
            if (l.contains("Sim creeper beams: a hit on lantern") && l.contains("refused")) {
                refused++;
            } else if (l.contains("Sim creeper beams: shot landed on lantern")) {
                shotsLanded++;
            } else if (l.contains("[AutoPuzzles] Beams: shot at")) {
                shootLines++;
            } else if (l.contains("[AutoPuzzles] Beams: warp sent onto")) {
                warps++;
            } else if (l.contains("Beams (melee): no answer to the hit")) {
                retries++;
            }
        }

        @Override
        String verdict(ClientGameTestContext ctx, int solvedAt) {
            if (noMode) {
                return "this jar has no Melee mode for Auto Creeper Beams (no AutoPuzzlesConfig.setAutoBeamsMode)";
            }
            int arrows = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(TERMINATOR, "arrowsFired"));
            // The joined pairs' ends, from the sim itself (worldPairs / connected), and how each reads on the client.
            List<BlockPos> joinedEnds = ctx.computeOnClient(mc -> joinedEnds());
            List<String> notStruck = new ArrayList<>();
            List<String> notPrismarine = new ArrayList<>();
            for (BlockPos p : joinedEnds) {
                if (!struck.containsKey(p)) {
                    notStruck.add(p.toShortString());
                }
                boolean prism = ctx.computeOnClient(mc -> mc.level.getBlockState(p)
                        .is(net.minecraft.world.level.block.Blocks.PRISMARINE));
                if (!prism) {
                    notPrismarine.add(p.toShortString());
                }
            }
            say(name, "joined lanterns (sim): " + joinedEnds.size() + "; struck by melee (sim): " + struck.keySet().size()
                    + " distinct, " + strikes + " strike(s); auto hits: " + hits);
            if (solvedAt < 0) {
                return "the room was not solved";
            }
            if (joinedEnds.size() < 8) {
                return "the sim names only " + joinedEnds.size() + " joined lantern(s), 8 expected for four beams";
            }
            if (!notStruck.isEmpty()) {
                return "joined lantern(s) never struck by a melee hit: " + notStruck;
            }
            if (!notPrismarine.isEmpty()) {
                return "joined lantern(s) not prismarine on the client: " + notPrismarine;
            }
            if (maxStruckDist > REACH + 1e-9 || maxHitDist > REACH + 1e-9) {
                return String.format(Locale.ROOT, "a hit from beyond reach: sim %.2f, auto %.2f (limit %.1f)", maxStruckDist,
                        maxHitDist, REACH);
            }
            if (refused > 0) {
                return refused + " hit(s) refused by the sim as out of reach";
            }
            if (arrows != arrows0 || shotsLanded > 0 || shootLines > 0) {
                return "the bow was used: " + (arrows - arrows0) + " arrow(s), " + shotsLanded + " shot(s) landed, "
                        + shootLines + " Shoot-mode line(s)";
            }
            if (hits.isEmpty()) {
                return "the auto logged no melee hit";
            }
            if (warps == 0) {
                return "the auto never etherwarped";
            }
            return null;
        }

        @Override
        String summary() {
            String line = String.format(Locale.ROOT, "MELEE hits %d, sim strikes %d (%d lanterns), warps %d, retries %d, "
                            + "max eye to box %.2f, first hit -> solved %s", hits.size(), strikes, struck.size(), warps,
                    retries, Math.max(maxHitDist, maxStruckDist),
                    firstHit < 0 || solved < 0 ? "n/a" : String.format(Locale.ROOT, "%.2fs (%d ticks)",
                            (solved - firstHit) / 20.0, solved - firstHit));
            say(name, line);
            return line;
        }
    }

    /** Every end of every pair the sim has joined, read from {@code SimCreeperPuzzle}'s own fields. */
    @SuppressWarnings("unchecked")
    static List<BlockPos> joinedEnds() {
        try {
            Class<?> c = Class.forName(PUZZLE);
            var wf = c.getDeclaredField("worldPairs");
            var cf = c.getDeclaredField("connected");
            wf.setAccessible(true);
            cf.setAccessible(true);
            List<Object> world = (List<Object>) wf.get(null);
            boolean[] connected = (boolean[]) cf.get(null);
            Set<BlockPos> out = new LinkedHashSet<>();
            for (int i = 0; i < connected.length && i < world.size(); i++) {
                if (!connected[i]) {
                    continue;
                }
                Object pair = world.get(i);
                var a = pair.getClass().getDeclaredMethod("a");
                var b = pair.getClass().getDeclaredMethod("b");
                a.setAccessible(true);
                b.setAccessible(true);
                out.add((BlockPos) a.invoke(pair));
                out.add((BlockPos) b.invoke(pair));
            }
            return new ArrayList<>(out);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not read SimCreeperPuzzle's joined pairs", e);
        }
    }

    abstract static class MeleeBase extends SimPuzzleSolveTests.Base {
        private final String caseName;

        MeleeBase(String caseName) {
            super(new SimPuzzleSolveTests.Spec("beams-melee-" + caseName, "Creeper Beams", "setAutoBeamsEnabled",
                    "isAutoBeamsEnabled", "BeamsSolverConfig", "SimCreeperPuzzle", SimPuzzleSolveTests.AOTV_SLOT, false, 60,
                    SimPuzzleSolveTests.Approach.NONE, "BeamsSolverFeature.getActivePairs"));
            this.caseName = caseName;
        }

        @Override
        String scenarioName() {
            return "1018-beams-melee-" + caseName;
        }

        @Override
        SolveExtra extra() {
            return new MeleeWatch();
        }
    }

    /** The build's own rotation. */
    public static final class Solve extends MeleeBase {
        public Solve() {
            super("solve");
        }
    }

    /** Each run at the next quarter turn ({@code -PsolveRepeat=4} plays all four). */
    public static final class Rotations extends MeleeBase {
        public Rotations() {
            super("rotations");
        }

        @Override
        int rotation() {
            return ((runNo - 1) % 4) * 90;
        }
    }
}
