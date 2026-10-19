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

    private static final Pattern SHOT_LANDED = Pattern.compile(
            "Sim creeper beams: shot landed on lantern BlockPos\\{x=(-?\\d{1,8}), y=(-?\\d{1,4}), z=(-?\\d{1,8})\\}");

    static void say(String name, String line) {
        System.out.println("[" + name + "] " + line);
    }

    /** The log read every tick: the auto's hits, the sim's strikes and refusals, shots and warps. */
    static class MeleeWatch extends SolveExtra {
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
        /** Every auto hit in order, and whether it came straight after an AOTV teleport; lanterns the sim saw shot. */
        final List<BlockPos> hitPos = new ArrayList<>();
        final List<Boolean> hitAotv = new ArrayList<>();
        final Set<BlockPos> shotAt = new LinkedHashSet<>();
        int aotvLandings;

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
                boolean aotv = l.contains(" via aotv");
                hits.add(h.group(1) + "," + h.group(2) + "," + h.group(3) + " stage " + h.group(4) + " at " + h.group(5)
                        + (aotv ? " (aotv)" : ""));
                BlockPos hp = new BlockPos(Integer.parseInt(h.group(1)), Integer.parseInt(h.group(2)),
                        Integer.parseInt(h.group(3)));
                hitPos.add(hp);
                hitAotv.add(aotv);
                return;
            }
            Matcher sl = SHOT_LANDED.matcher(l);
            if (sl.find()) {
                shotAt.add(new BlockPos(Integer.parseInt(sl.group(1)), Integer.parseInt(sl.group(2)),
                        Integer.parseInt(sl.group(3))));
            }
            if (l.contains("Beams (melee): AOTV teleport landed")) {
                aotvLandings++;
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

    /** Every pair the sim armed, ends in absolute positions ({@code SimCreeperPuzzle.worldPairs}). */
    @SuppressWarnings("unchecked")
    static List<BlockPos[]> allPairs() {
        try {
            Class<?> c = Class.forName(PUZZLE);
            var wf = c.getDeclaredField("worldPairs");
            wf.setAccessible(true);
            List<BlockPos[]> out = new ArrayList<>();
            for (Object pair : (List<Object>) wf.get(null)) {
                var a = pair.getClass().getDeclaredMethod("a");
                var b = pair.getClass().getDeclaredMethod("b");
                a.setAccessible(true);
                b.setAccessible(true);
                out.add(new BlockPos[]{(BlockPos) a.invoke(pair), (BlockPos) b.invoke(pair)});
            }
            return out;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not read SimCreeperPuzzle.worldPairs", e);
        }
    }

    /** The mod's own count of etherwarp spots that reach a lantern ({@code AutoBeams.meleeSpots}); -1 on a jar without it. */
    static int meleeSpotCount(net.minecraft.client.Minecraft mc, BlockPos lantern) {
        try {
            Object l = ModUnderTest.staticCall("com.killer560.hub.autopuzzles.AutoBeams", "meleeSpots",
                    new Class<?>[]{net.minecraft.client.Minecraft.class, BlockPos.class}, new Object[]{mc, lantern});
            return ((List<?>) l).size();
        } catch (AssertionError e) {
            return -1;
        }
    }

    /**
     * His in-game room of 2026-10-18 rebuilt: only some pairs left that etherwarp spots alone can finish. Before the auto
     * starts, both ends of {@code burnFullPairs} of the pairs whose two lanterns BOTH have etherwarp spots are turned to
     * prismarine on the server (the solver then reads them as joined and does not list them; the sim still needs four
     * other beams) - all of them when {@code burnFullPairs} is negative. Two left ({@code -after}): the auto plays those two
     * on foot and must go on past them, hitting a ground lantern after its fourth hit and finishing with teleports or shots.
     * None left ({@code -aotv}): every remaining pair has a lantern no etherwarp spot reaches, and one of those must be hit
     * in the air straight after an AOTV teleport, from within 4.5 of its box.
     */
    static final class RestWatch extends MeleeWatch {
        final int burnFullPairs;
        final boolean needAotv;
        final Set<BlockPos> noSpot = new LinkedHashSet<>();
        final Set<BlockPos> withSpot = new LinkedHashSet<>();
        int burned;

        RestWatch(int burnFullPairs, boolean needAotv) {
            this.burnFullPairs = burnFullPairs;
            this.needAotv = needAotv;
        }

        @Override
        void beforeAutoOn(ClientGameTestContext ctx) {
            super.beforeAutoOn(ctx);
            if (noMode) {
                return;
            }
            List<BlockPos[]> pairs = ctx.computeOnClient(mc -> allPairs());
            List<BlockPos> burn = new ArrayList<>();
            int fullSeen = 0;
            for (BlockPos[] p : pairs) {
                int s0 = ctx.computeOnClient(mc -> meleeSpotCount(mc, p[0]));
                int s1 = ctx.computeOnClient(mc -> meleeSpotCount(mc, p[1]));
                (s0 > 0 ? withSpot : noSpot).add(p[0]);
                (s1 > 0 ? withSpot : noSpot).add(p[1]);
                if (s0 > 0 && s1 > 0) {
                    fullSeen++;
                    if (burnFullPairs < 0 || burn.size() < 2 * burnFullPairs) {
                        burn.add(p[0]);
                        burn.add(p[1]);
                    }
                }
            }
            burned = burn.size() / 2;
            say(name, pairs.size() + " pair(s) armed, " + fullSeen + " with etherwarp spots at both ends; burning " + burned
                    + " of those (" + burn.stream().map(BlockPos::toShortString).toList() + "); lanterns with no spot: "
                    + noSpot.size());
            ctx.runOnClient(mc -> {
                var server = mc.getSingleplayerServer();
                server.execute(() -> {
                    for (BlockPos b : burn) {
                        server.overworld().setBlockAndUpdate(b, net.minecraft.world.level.block.Blocks.PRISMARINE.defaultBlockState());
                    }
                });
            });
            ctx.waitTicks(20);
        }

        @Override
        String verdict(ClientGameTestContext ctx, int solvedAt) {
            if (noMode) {
                return "this jar has no Melee mode for Auto Creeper Beams (no AutoPuzzlesConfig.setAutoBeamsMode)";
            }
            List<BlockPos> joinedEnds = ctx.computeOnClient(mc -> joinedEnds());
            List<String> untouched = new ArrayList<>();
            for (BlockPos p : joinedEnds) {
                if (!struck.containsKey(p) && !shotAt.contains(p)) {
                    untouched.add(p.toShortString());
                }
            }
            say(name, "joined lanterns (sim): " + joinedEnds.size() + "; struck by melee " + struck.size() + ", shot "
                    + shotAt.size() + "; AOTV landings " + aotvLandings + "; auto hits in order: " + hits);
            if (burned == 0) {
                return "no fully reachable pair was burned - the fixture did not build his room";
            }
            if (solvedAt < 0) {
                return "the room was not solved - it stopped after " + hits.size() + " hit(s)";
            }
            if (joinedEnds.size() < 8) {
                return "the sim names only " + joinedEnds.size() + " joined lantern(s)";
            }
            if (!untouched.isEmpty()) {
                return "joined lantern(s) neither struck nor shot by the auto: " + untouched;
            }
            if (maxStruckDist > REACH + 1e-9 || maxHitDist > REACH + 1e-9 || refused > 0) {
                return String.format(Locale.ROOT, "a hit beyond reach (sim %.2f, auto %.2f) or refused (%d)", maxStruckDist,
                        maxHitDist, refused);
            }
            if (!needAotv) {
                boolean groundLater = false;
                for (int i = 4; i < hitPos.size(); i++) {
                    if (!hitAotv.get(i) && withSpot.contains(hitPos.get(i))) {
                        groundLater = true;
                    }
                }
                if (!groundLater) {
                    return "no ground lantern (one with an etherwarp spot) was hit after the first four hits " + hits;
                }
            } else {
                BlockPos high = null;
                for (int i = 0; i < hitPos.size(); i++) {
                    if (hitAotv.get(i) && noSpot.contains(hitPos.get(i)) && struck.containsKey(hitPos.get(i))) {
                        high = hitPos.get(i);
                    }
                }
                if (high == null) {
                    return "no lantern without an etherwarp spot was hit straight after an AOTV teleport " + hits;
                }
                say(name, "high lantern " + high.toShortString() + " hit after an AOTV teleport, struck from "
                        + String.format(Locale.ROOT, "%.2f", struck.get(high)) + " eye to box");
            }
            return null;
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

    /** Two of the pairs etherwarp spots can finish burned: it must carry on past its first four hits and finish. */
    public static final class After extends MeleeBase {
        public After() {
            super("after");
        }

        @Override
        SolveExtra extra() {
            return new RestWatch(2, false);
        }
    }

    /** Every pair etherwarp spots can finish burned: a high lantern must be hit after an AOTV teleport. */
    public static final class Aotv extends MeleeBase {
        public Aotv() {
            super("aotv");
        }

        @Override
        SolveExtra extra() {
            return new RestWatch(-1, true);
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
