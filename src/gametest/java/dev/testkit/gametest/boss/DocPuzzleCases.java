package dev.testkit.gametest.boss;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.PacketTrace;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Mod branch doc-puzzles-1101 (killer560's to-do Doc, 2026-11-01): Quiz, Creeper Beams, Ice Fill, Teleport Maze, Tic Tac
 * Toe and Simon Says. Each case proves the new path ACTED (a click dropped or sent, a counter moved, a list changed), and
 * the clicks are real ({@code useItemOn} / vanilla's {@code startUseItem}) so GrimAC sees them.
 * <ul>
 *   <li>1101-pz-quiz: Sneak To Override off keeps a sneaking wrong click dropped; Oruo's wrong-answer line removes the
 *   highlight; the Quiz Timer shows Oruo's 220-tick lock and draws on the HUD.</li>
 *   <li>1101-pz-ttt: his board X.O/OOX/X.. (computer to move) gets a prediction of 7 or 8; every computer-to-move
 *   position is checked against an independent minimax (shown iff a safe prefire exists, and the shown one is safe);
 *   Sneak To Override off keeps a sneaking misclick blocked.</li>
 *   <li>1101-pz-beams: four real lantern pairs get four different palette colors at least 0.5 apart (RGB distance); All
 *   draws 4, One At A Time 1, Next 2; finishing the shown pair moves One At A Time on and leaves the others' colors.</li>
 *   <li>1101-pz-icefill: Etherwarp Protection on real sneaking right clicks with an ethermerged shovel over ice tiles.</li>
 *   <li>1101-pz-tpmaze: the pads the renderer draws under Current Cell Only / Best Pad Only, and the tracer color saved.</li>
 *   <li>1101-ss-skip-relight / 1101-ss-skip-second / 1101-ss-override-rescan: the three Simon Says manual-solve fixes.</li>
 * </ul>
 */
final class DocPuzzleCases {

    static final String QUIZ = "puzzlesolvers.QuizSolverFeature";
    static final String QUIZ_CFG = "puzzlesolvers.QuizSolverConfig";
    static final String TTT = "puzzlesolvers.TicTacToeSolverFeature";
    static final String TTT_CFG = "puzzlesolvers.TicTacToeSolverConfig";
    static final String BEAMS = "puzzlesolvers.BeamsSolverFeature";
    static final String BEAMS_CFG = "puzzlesolvers.BeamsSolverConfig";
    static final String ICE = "puzzlesolvers.IceFillSolverFeature";
    static final String ICE_CFG = "puzzlesolvers.IceFillSolverConfig";
    static final String MAZE = "puzzlesolvers.TeleportMazeSolverFeature";
    static final String MAZE_CFG = "puzzlesolvers.TeleportMazeSolverConfig";
    static final String SS = SimonSaysCases.FEATURE;
    static final String SS_CFG = SimonSaysCases.CONFIG;

    private DocPuzzleCases() {
    }

    static void register(Session s) {
        s.test("1101-pz-quiz", DocPuzzleCases::quiz);
        s.test("1101-pz-ttt", DocPuzzleCases::ticTacToe);
        s.test("1101-pz-beams", DocPuzzleCases::beams);
        s.test("1101-pz-icefill", DocPuzzleCases::iceFill);
        s.test("1101-pz-tpmaze", DocPuzzleCases::teleportMaze);
        s.test("1101-ss-skip-relight", DocPuzzleCases::ssRelight);
        s.test("1101-ss-skip-second", DocPuzzleCases::ssSecond);
        s.test("1101-ss-override-rescan", DocPuzzleCases::ssOverrideRescan);
    }

    private static void restore(Session c, List<AutoCloseable> undo) {
        for (int i = undo.size() - 1; i >= 0; i--) {
            final AutoCloseable u = undo.get(i);
            c.ctx().runOnClient(mc -> {
                try {
                    u.close();
                } catch (Exception e) {
                    System.out.println("[1101] restore failed: " + e);
                }
            });
        }
    }

    private static void inDungeon(Session c) {
        c.hx().sidebar("SKYBLOCK", "The Catac§combs §7(F7)");
        c.waitUntil("DungeonState in a dungeon", mc -> (Boolean) Mod.staticCall("secrets.DungeonState", "isInDungeon"), 100);
    }

    // ---- 1101-pz-quiz ----------------------------------------------------------------------------------------------

    static void quiz(Session c) throws Exception {
        List<AutoCloseable> undo = new ArrayList<>();
        BlockPos[] pillars = {new BlockPos(-617, 151, -623), new BlockPos(-620, 151, -623), new BlockPos(-623, 151, -623)};
        BlockPos[] buttons = new BlockPos[3];
        for (int i = 0; i < 3; i++) {
            buttons[i] = pillars[i].south();
        }
        try {
            c.hx().call("dungeon.tp", "x", -619.5, "y", 151.0, "z", -619.5, "yaw", 180f, "pitch", 0f);
            for (int i = 0; i < 3; i++) {
                BlockPos p = pillars[i];
                c.server().command("setblock " + p.getX() + " " + p.getY() + " " + p.getZ() + " minecraft:smooth_stone");
                BlockPos b = buttons[i];
                c.server().command("setblock " + b.getX() + " " + b.getY() + " " + b.getZ()
                        + " minecraft:stone_button[face=wall,facing=south]");
            }
            c.waitUntil("the three buttons on the client", mc -> {
                for (BlockPos b : buttons) {
                    if (!mc.level.getBlockState(b).is(Blocks.STONE_BUTTON)) {
                        return false;
                    }
                }
                return true;
            }, 100);
            inDungeon(c);
            undo.add(c.onClient(mc -> Mod.with(QUIZ_CFG, "Enabled", true)));
            undo.add(c.onClient(mc -> Mod.with(QUIZ_CFG, "BlockWrongAnswers", true)));
            undo.add(c.onClient(mc -> Mod.with(QUIZ_CFG, "SneakOverride", false)));
            undo.add(c.onClient(mc -> Mod.with(QUIZ_CFG, "ShowTimer", true)));
            c.ctx().waitTicks(5);
            c.ctx().runOnClient(mc -> {
                try {
                    Object[] opts = (Object[]) Mod.field(QUIZ, "options");
                    for (int i = 0; i < 3; i++) {
                        ClickGuardCases.setInstanceField(opts[i], "blockPos", pillars[i]);
                        ClickGuardCases.setInstanceField(opts[i], "correct", false);
                    }
                    Mod.staticCall(QUIZ, "clearForNewQuestion");
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });

            // Quiz Timer: Oruo's intro opens question 1, answerable 220 server ticks later.
            int draws0 = c.onClient(mc -> (Integer) Mod.staticCall(QUIZ, "timerDraws"));
            c.hx().chat("[STATUE] Oruo the Omniscient: I am Oruo the Omniscient. I have lived many lives. I have learned all there is to know.");
            c.waitUntil("QuizState in progress", mc -> (Boolean) Mod.staticCall("puzzlesolvers.QuizState", "inProgress"), 60);
            c.ctx().waitTicks(10);
            String timer = c.onClient(mc -> (String) Mod.staticCall(QUIZ, "timerText"));
            int draws = c.onClient(mc -> (Integer) Mod.staticCall(QUIZ, "timerDraws")) - draws0;
            String drawn = c.onClient(mc -> (String) Mod.staticCall(QUIZ, "lastTimerText"));
            c.note("timer text \"" + timer + "\", drawn " + draws + " frame(s), last drawn \"" + drawn + "\"");
            double secs = parseSeconds(timer);
            c.check(timer != null && timer.contains("Quiz 1/3") && secs > 8.0 && secs <= 11.0,
                    "the timer should read question 1 with ~10 s left, read \"" + timer + "\"");
            c.check(draws > 0 && drawn != null && drawn.contains("Quiz 1/3"), "the Quiz Timer HUD never drew");

            // The year question, answer b.
            long year = ((System.currentTimeMillis() / 1000L) - 1560276000L) / 446400L + 1;
            c.hx().chat("                            What SkyBlock year is it?");
            c.hx().chat("     §6 ⓐ §aYear " + (year + 7));
            c.hx().chat("     §6 ⓑ §aYear " + year);
            c.hx().chat("     §6 ⓒ §aYear " + (year + 13));
            c.waitUntil("the solver to mark b correct", mc -> pillars[1].equals(Mod.staticCall(QUIZ, "getCorrectAnswerPos")), 60);

            // Sneak To Override OFF: sneaking no longer lets a wrong click through.
            ClickGuardCases.sneak(c, true);
            Object[] sneakWrong = ClickGuardCases.use(c, buttons[0]);
            ClickGuardCases.sneak(c, false);
            c.note("Sneak To Override off, sneaking click on wrong a: " + sneakWrong[0] + ", " + sneakWrong[1] + " packet(s)");
            c.check(sneakWrong[0] == InteractionResult.FAIL && (Integer) sneakWrong[1] == 0,
                    "with Sneak To Override off a sneaking wrong click was sent");
            // ...and on: it goes through (the old behavior).
            c.ctx().runOnClient(mc -> Mod.set(QUIZ_CFG, "setSneakOverride", true));
            c.ctx().waitTicks(25);
            ClickGuardCases.sneak(c, true);
            Object[] sneakOn = ClickGuardCases.use(c, buttons[0]);
            ClickGuardCases.sneak(c, false);
            c.check(sneakOn[0] != InteractionResult.FAIL && (Integer) sneakOn[1] >= 1,
                    "with Sneak To Override on a sneaking click was dropped");

            // A wrong answer: the highlight goes.
            int wrong0 = c.onClient(mc -> (Integer) Mod.staticCall(QUIZ, "wrongAnswers"));
            c.hx().chat("[STATUE] Oruo the Omniscient: Bob chose the wrong answer! I shall never forget this moment of misrememberance.");
            c.waitUntil("the wrong-answer line handled", mc -> (Integer) Mod.staticCall(QUIZ, "wrongAnswers") > wrong0, 60);
            Object after = c.onClient(mc -> Mod.staticCall(QUIZ, "getCorrectAnswerPos"));
            String timerAfter = c.onClient(mc -> (String) Mod.staticCall(QUIZ, "timerText"));
            c.note("after the wrong answer: highlighted " + after + ", timer \"" + timerAfter + "\"");
            c.check(after == null, "the correct answer is still highlighted after a wrong answer: " + after);
            c.check(timerAfter == null, "the Quiz Timer still runs after the puzzle failed: " + timerAfter);
        } finally {
            ClickGuardCases.sneak(c, false);
            c.ctx().runOnClient(mc -> {
                try {
                    Object[] opts = (Object[]) Mod.field(QUIZ, "options");
                    for (Object o : opts) {
                        ClickGuardCases.setInstanceField(o, "blockPos", null);
                        ClickGuardCases.setInstanceField(o, "correct", false);
                    }
                    Mod.staticCall(QUIZ, "clearForNewQuestion");
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            restore(c, undo);
            for (int i = 0; i < 3; i++) {
                BlockPos b = buttons[i];
                BlockPos p = pillars[i];
                c.server().command("setblock " + b.getX() + " " + b.getY() + " " + b.getZ() + " minecraft:air");
                c.server().command("setblock " + p.getX() + " " + p.getY() + " " + p.getZ() + " minecraft:air");
            }
            c.hx().sidebarClear();
        }
    }

    private static double parseSeconds(String timer) {
        if (timer == null) {
            return -1;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("([0-9]+\\.[0-9])s").matcher(timer);
        return m.find() ? Double.parseDouble(m.group(1)) : -1;
    }

    // ---- 1101-pz-ttt -----------------------------------------------------------------------------------------------

    static void ticTacToe(Session c) throws Exception {
        // His picture (to-do Doc, 2026-11-01): X top-left, bottom-left, middle-right; O top-right, middle-left, middle.
        Integer his = c.onClient(mc -> (Integer) Mod.staticCall(TTT, "predictionFor", "X.OOOXX.."));
        c.note("X . O / O O X / X . . (computer to move): prediction " + his);
        c.check(his != null && (his == 7 || his == 8), "his board should predict 7 or 8, got " + his);

        Object[] sweep = c.onClient(mc -> {
            int positions = 0;
            int shown = 0;
            int expected = 0;
            int wrong = 0;
            int viaReply = 0;
            String first = "";
            for (int code = 0; code < 19683; code++) {
                char[] b = new char[9];
                int x = 0;
                int o = 0;
                int v = code;
                for (int i = 0; i < 9; i++) {
                    int d = v % 3;
                    v /= 3;
                    b[i] = d == 0 ? '.' : d == 1 ? 'X' : 'O';
                    x += d == 1 ? 1 : 0;
                    o += d == 2 ? 1 : 0;
                }
                if (x != o || winner(b) != 0 || x + o == 9) {
                    continue;
                }
                positions++;
                int bestX = Integer.MAX_VALUE;
                List<Integer> replies = new ArrayList<>();
                for (int r = 0; r < 9; r++) {
                    if (b[r] != '.') {
                        continue;
                    }
                    b[r] = 'X';
                    int val = minimax(b, true);
                    b[r] = '.';
                    if (val < bestX) {
                        bestX = val;
                        replies.clear();
                    }
                    if (val == bestX) {
                        replies.add(r);
                    }
                }
                Set<Integer> outside = new HashSet<>();
                Set<Integer> inside = new HashSet<>();
                for (int s = 0; s < 9; s++) {
                    if (b[s] != '.') {
                        continue;
                    }
                    boolean among = replies.contains(s);
                    if (among && replies.size() < 2) {
                        continue;
                    }
                    boolean safe = true;
                    for (int r : replies) {
                        if (r == s) {
                            continue;
                        }
                        b[r] = 'X';
                        int bestO = Integer.MIN_VALUE;
                        int mine = Integer.MIN_VALUE;
                        if (winner(b) != 0) {
                            safe = false;
                        } else {
                            for (int q = 0; q < 9; q++) {
                                if (b[q] == '.') {
                                    b[q] = 'O';
                                    int val = minimax(b, false);
                                    b[q] = '.';
                                    bestO = Math.max(bestO, val);
                                    if (q == s) {
                                        mine = val;
                                    }
                                }
                            }
                            safe = mine == bestO;
                        }
                        b[r] = '.';
                        if (!safe) {
                            break;
                        }
                    }
                    if (safe) {
                        (among ? inside : outside).add(s);
                    }
                }
                boolean exists = !outside.isEmpty() || !inside.isEmpty();
                if (exists) {
                    expected++;
                }
                Integer p = (Integer) Mod.staticCall(TTT, "predictionFor", new String(b));
                String why = null;
                if (p != null) {
                    shown++;
                    if (!outside.isEmpty() ? !outside.contains(p) : !inside.contains(p)) {
                        why = "shown " + p + ", safe outside " + outside + ", safe among replies " + inside;
                    } else if (outside.isEmpty()) {
                        viaReply++;
                    }
                } else if (exists) {
                    why = "nothing shown, safe " + outside + " / " + inside;
                }
                if (why != null) {
                    wrong++;
                    if (first.isEmpty()) {
                        first = new String(b) + ": " + why;
                    }
                }
            }
            return new Object[]{positions, shown, expected, wrong, viaReply, first};
        });
        c.note(String.format(Locale.ROOT, "%d computer-to-move positions; a safe prefire exists in %d, shown in %d (%d of"
                        + " them a square the computer might take); wrong %d%s", sweep[0], sweep[2], sweep[1], sweep[4],
                sweep[3], ((String) sweep[5]).isEmpty() ? "" : ", first " + sweep[5]));
        c.check((Integer) sweep[0] > 2000, "too few positions");
        c.check((Integer) sweep[3] == 0, sweep[3] + " positions disagree with the independent search, first " + sweep[5]);
        c.check((Integer) sweep[4] > 0, "no position used the new rule (a square the computer might take)");

        // Sneak To Override off: a sneaking misclick stays blocked (the decision the misclick mixin asks).
        List<AutoCloseable> undo = new ArrayList<>();
        try {
            undo.add(c.onClient(mc -> Mod.with(TTT_CFG, "Enabled", true)));
            undo.add(c.onClient(mc -> Mod.with(TTT_CFG, "PreventMisclicks", true)));
            undo.add(c.onClient(mc -> Mod.with(TTT_CFG, "SneakOverride", false)));
            Object[] r = c.onClient(mc -> {
                int[] cr = {300, 300, 0};
                BlockPos target = (BlockPos) Mod.staticCall(TTT, "indexToPos", 6, cr);
                BlockPos other = (BlockPos) Mod.staticCall(TTT, "indexToPos", 0, cr);
                Mod.setField(TTT, "boardCr", cr);
                Mod.setField(TTT, "bestMove", target);
                int b0 = (Integer) Mod.staticCall(TTT, "blockedClicks");
                boolean offBlocks = (Boolean) Mod.staticCall(TTT, "shouldBlockClick", other, true);
                Mod.call(Mod.cfg(TTT_CFG), "setSneakOverride", true);
                boolean onBlocks = (Boolean) Mod.staticCall(TTT, "shouldBlockClick", other, true);
                int b1 = (Integer) Mod.staticCall(TTT, "blockedClicks");
                Mod.setField(TTT, "boardCr", null);
                Mod.setField(TTT, "bestMove", null);
                return new Object[]{offBlocks, onBlocks, b1 - b0};
            });
            c.note("sneaking misclick: override off blocks " + r[0] + ", override on blocks " + r[1] + ", blocked " + r[2]);
            c.check(Boolean.TRUE.equals(r[0]) && Boolean.FALSE.equals(r[1]) && (Integer) r[2] == 1,
                    "Sneak To Override does not decide whether sneaking lets a misclick through");
        } finally {
            restore(c, undo);
        }
    }

    static int winner(char[] b) {
        int[][] lines = {{0, 1, 2}, {3, 4, 5}, {6, 7, 8}, {0, 3, 6}, {1, 4, 7}, {2, 5, 8}, {0, 4, 8}, {2, 4, 6}};
        for (int[] l : lines) {
            if (b[l[0]] != '.' && b[l[0]] == b[l[1]] && b[l[0]] == b[l[2]]) {
                return b[l[0]] == 'O' ? 1 : -1;
            }
        }
        return 0;
    }

    /** +1 O wins, 0 draw, -1 X wins, with perfect play. */
    static int minimax(char[] b, boolean oToMove) {
        int w = winner(b);
        if (w != 0) {
            return w;
        }
        boolean full = true;
        int best = oToMove ? -2 : 2;
        for (int i = 0; i < 9; i++) {
            if (b[i] != '.') {
                continue;
            }
            full = false;
            b[i] = oToMove ? 'O' : 'X';
            int v = minimax(b, !oToMove);
            b[i] = '.';
            best = oToMove ? Math.max(best, v) : Math.min(best, v);
        }
        return full ? 0 : best;
    }

    // ---- 1101-pz-beams ---------------------------------------------------------------------------------------------

    static void beams(Session c) throws Exception {
        List<AutoCloseable> undo = new ArrayList<>();
        int[] cr = {-640, -640, 0};
        List<BlockPos[]> pairs = new ArrayList<>();
        try {
            inDungeon(c);
            undo.add(c.onClient(mc -> Mod.with(BEAMS_CFG, "Enabled", true)));
            // Four candidate pairs that share no lantern, at the real candidate positions for this "room".
            c.ctx().runOnClient(mc -> {
                List<?> cands = (List<?>) Mod.field(BEAMS, "CANDIDATES");
                Set<BlockPos> used = new HashSet<>();
                for (Object cand : cands) {
                    BlockPos a = (BlockPos) Mod.staticCall("puzzlesolvers.PuzzleCoords", "real",
                            Mod.call(cand, "x1"), Mod.call(cand, "y1"), Mod.call(cand, "z1"), cr);
                    BlockPos b = (BlockPos) Mod.staticCall("puzzlesolvers.PuzzleCoords", "real",
                            Mod.call(cand, "x2"), Mod.call(cand, "y2"), Mod.call(cand, "z2"), cr);
                    if (used.contains(a) || used.contains(b)) {
                        continue;
                    }
                    used.add(a);
                    used.add(b);
                    pairs.add(new BlockPos[]{a, b});
                    if (pairs.size() == 4) {
                        break;
                    }
                }
            });
            c.check(pairs.size() == 4, "found only " + pairs.size() + " disjoint candidate pairs");
            for (BlockPos[] p : pairs) {
                for (BlockPos q : p) {
                    c.server().command("setblock " + q.getX() + " " + q.getY() + " " + q.getZ() + " minecraft:sea_lantern");
                }
            }
            c.waitUntil("the lanterns on the client", mc -> {
                for (BlockPos[] p : pairs) {
                    if (!mc.level.getBlockState(p[0]).is(Blocks.SEA_LANTERN) || !mc.level.getBlockState(p[1]).is(Blocks.SEA_LANTERN)) {
                        return false;
                    }
                }
                return true;
            }, 100);

            Object[] all = c.onClient(mc -> scanAndDraw(mc, cr, "ALL"));
            @SuppressWarnings("unchecked")
            List<Object[]> drawnAll = (List<Object[]>) all[0];
            Set<Integer> colors = new HashSet<>();
            double minDist = Double.MAX_VALUE;
            List<float[]> rgb = new ArrayList<>();
            for (Object[] d : drawnAll) {
                colors.add((Integer) d[2]);
                rgb.add(c.onClient(mc -> (float[]) Mod.staticCall(BEAMS, "paletteColor", (Integer) d[2])));
            }
            for (int i = 0; i < rgb.size(); i++) {
                for (int j = i + 1; j < rgb.size(); j++) {
                    float[] p = rgb.get(i);
                    float[] q = rgb.get(j);
                    minDist = Math.min(minDist, Math.sqrt((p[0] - q[0]) * (p[0] - q[0]) + (p[1] - q[1]) * (p[1] - q[1])
                            + (p[2] - q[2]) * (p[2] - q[2])));
                }
            }
            c.note("All: " + drawnAll.size() + " pairs drawn, colors " + colors + ", closest two colors " +
                    String.format(Locale.ROOT, "%.2f", minDist) + " apart (RGB 0-1)");
            c.check(drawnAll.size() == 4 && colors.size() == 4, "four lit pairs should draw in four colors: " + colors);
            c.check(minDist >= 0.5, "two pair colors are only " + minDist + " apart");

            Object[] one = c.onClient(mc -> scanAndDraw(mc, cr, "ONE"));
            Object[] next = c.onClient(mc -> scanAndDraw(mc, cr, "NEXT"));
            @SuppressWarnings("unchecked")
            List<Object[]> drawnOne = (List<Object[]>) one[0];
            @SuppressWarnings("unchecked")
            List<Object[]> drawnNext = (List<Object[]>) next[0];
            c.check(drawnOne.size() == 1, "One At A Time drew " + drawnOne.size());
            c.check(drawnNext.size() == 2, "Next drew " + drawnNext.size());
            BlockPos shownA = (BlockPos) drawnOne.get(0)[0];
            int shownColor = (Integer) drawnOne.get(0)[2];
            // Finish that pair: its lanterns go out (prismarine), as connecting them does.
            BlockPos[] done = null;
            for (BlockPos[] p : pairs) {
                if (p[0].equals(shownA) || p[1].equals(shownA)) {
                    done = p;
                }
            }
            c.check(done != null, "the shown pair is not one of the four");
            for (BlockPos q : done) {
                c.server().command("setblock " + q.getX() + " " + q.getY() + " " + q.getZ() + " minecraft:prismarine");
            }
            final BlockPos[] finished = done;
            c.waitUntil("the finished pair on the client", mc -> mc.level.getBlockState(finished[0]).is(Blocks.PRISMARINE), 60);
            Object[] after = c.onClient(mc -> scanAndDraw(mc, cr, "ONE"));
            Object[] afterAll = c.onClient(mc -> scanAndDraw(mc, cr, "ALL"));
            @SuppressWarnings("unchecked")
            List<Object[]> drawnAfter = (List<Object[]>) after[0];
            @SuppressWarnings("unchecked")
            List<Object[]> drawnAfterAll = (List<Object[]>) afterAll[0];
            boolean kept = true;
            for (Object[] d : drawnAfterAll) {
                for (Object[] e : drawnAll) {
                    if (d[0].equals(e[0]) && !d[2].equals(e[2])) {
                        kept = false;
                    }
                }
            }
            c.note("One At A Time showed " + shownA.toShortString() + " (color " + shownColor + "); after finishing it, "
                    + (drawnAfter.isEmpty() ? "nothing" : ((BlockPos) drawnAfter.get(0)[0]).toShortString()) + "; "
                    + drawnAfterAll.size() + " pairs left, colors kept " + kept);
            c.check(drawnAfter.size() == 1 && !drawnAfter.get(0)[0].equals(shownA), "One At A Time did not move on");
            c.check(drawnAfterAll.size() == 3 && kept, "the remaining pairs changed color or count");
        } finally {
            for (BlockPos[] p : pairs) {
                for (BlockPos q : p) {
                    c.server().command("setblock " + q.getX() + " " + q.getY() + " " + q.getZ() + " minecraft:air");
                }
            }
            restore(c, undo);
            c.hx().sidebarClear();
        }
    }

    /** Rescan the beams "room" with the mod's own scan and return {what it draws under {@code mode}}. */
    private static Object[] scanAndDraw(Minecraft mc, int[] cr, String mode) {
        Mod.set(BEAMS_CFG, "setShowMode", Mod.enumValue(BEAMS_CFG + "$ShowMode", mode));
        Mod.staticCall(BEAMS, "rescan", mc.level, cr);
        return new Object[]{Mod.staticCall(BEAMS, "drawnPairsForTest", mc.player.getEyePosition())};
    }

    // ---- 1101-pz-icefill -------------------------------------------------------------------------------------------

    static void iceFill(Session c) throws Exception {
        List<AutoCloseable> undo = new ArrayList<>();
        // Five ice tiles in the platform floor (y 150) along +x from under his feet; he stands at -619.5, 151, -619.5.
        int y = 150;
        int z = -620;
        int[] xs = {-620, -619, -618, -617, -616};
        try {
            for (int x : xs) {
                c.server().command("setblock " + x + " " + y + " " + z + " minecraft:ice");
            }
            c.server().command("give @p minecraft:diamond_shovel[minecraft:custom_data={ethermerge:1}]");
            c.hx().call("dungeon.tp", "x", -619.5, "y", 151.0, "z", -619.5, "yaw", -90f, "pitch", 30f);
            c.waitUntil("the tiles on the client", mc -> mc.level.getBlockState(new BlockPos(-616, y, z)).is(Blocks.ICE), 100);
            inDungeon(c);
            c.ctx().runOnClient(mc -> {
                for (int i = 0; i < 9; i++) {
                    if (mc.player.getInventory().getItem(i).is(net.minecraft.world.item.Items.DIAMOND_SHOVEL)) {
                        mc.player.getInventory().setSelectedSlot(i);
                    }
                }
            });
            c.waitUntil("the shovel in hand", mc -> mc.player.getMainHandItem().is(net.minecraft.world.item.Items.DIAMOND_SHOVEL), 40);
            undo.add(c.onClient(mc -> Mod.with(ICE_CFG, "Enabled", true)));
            undo.add(c.onClient(mc -> Mod.with(ICE_CFG, "EtherwarpProtection", true)));
            // The drawn path: one straight run across the five tiles (feet height, as the solver stores it).
            c.ctx().runOnClient(mc -> {
                @SuppressWarnings("unchecked")
                List<Vec3> path = (List<Vec3>) Mod.field(ICE, "currentPath");
                path.clear();
                path.add(new Vec3(-619.5, 151.1, -619.5));
                path.add(new Vec3(-615.5, 151.1, -619.5));
            });
            ClickGuardCases.sneak(c, true);
            BlockPos next = new BlockPos(-619, y, z);
            BlockPos skip = new BlockPos(-617, y, z);

            Object[] unreg = warp(c, next);
            c.server().command("setblock -620 " + y + " " + z + " minecraft:packed_ice");
            c.waitUntil("packed ice under him", mc -> mc.level.getBlockState(new BlockPos(-620, y, z)).is(Blocks.PACKED_ICE), 60);
            Object[] good = warp(c, next);
            Object[] wrongTile = warp(c, skip);
            c.server().command("setblock -620 " + y + " " + z + " minecraft:stone");
            c.waitUntil("stone under him", mc -> mc.level.getBlockState(new BlockPos(-620, y, z)).is(Blocks.STONE), 60);
            Object[] offIce = warp(c, skip);
            c.server().command("setblock -620 " + y + " " + z + " minecraft:ice");
            c.waitUntil("ice under him again", mc -> mc.level.getBlockState(new BlockPos(-620, y, z)).is(Blocks.ICE), 60);
            c.ctx().runOnClient(mc -> Mod.set(ICE_CFG, "setEtherwarpProtection", false));
            Object[] off = warp(c, next);
            ClickGuardCases.sneak(c, false);
            String report = "unregistered tile -> next: " + str(unreg) + "; packed -> next: " + str(good)
                    + "; packed -> two ahead: " + str(wrongTile) + "; stone -> anywhere: " + str(offIce)
                    + "; protection off, unregistered: " + str(off);
            c.note(report);
            System.out.println("[1101-pz-icefill] " + report);
            c.check((Integer) unreg[0] == 0 && (Integer) unreg[2] == 1, "a warp off an unregistered tile was sent");
            c.check((Integer) good[0] >= 1 && (Integer) good[2] == 0, "the warp to the next spot from packed ice was dropped");
            c.check((Integer) wrongTile[0] == 0 && (Integer) wrongTile[2] == 1, "a warp to the wrong tile was sent");
            c.check((Integer) offIce[0] >= 1 && (Integer) offIce[2] == 0, "a warp from off the ice was dropped");
            c.check((Integer) off[0] >= 1 && (Integer) off[2] == 0, "with protection off a warp was dropped");
        } finally {
            ClickGuardCases.sneak(c, false);
            c.ctx().runOnClient(mc -> {
                @SuppressWarnings("unchecked")
                List<Vec3> path = (List<Vec3>) Mod.field(ICE, "currentPath");
                path.clear();
                Mod.setField(ICE, "inIceFill", false);
            });
            restore(c, undo);
            for (int x : xs) {
                c.server().command("setblock " + x + " " + y + " " + z + " minecraft:stone");
            }
            c.server().command("clear @p minecraft:diamond_shovel");
            c.hx().sidebarClear();
        }
    }

    private static String str(Object[] r) {
        return r[0] + " use packet(s), " + r[2] + " blocked (" + r[1] + ")";
    }

    /**
     * Aim at the top of {@code tile} and right click the way the use key does (vanilla's {@code startUseItem}).
     * Returns {use + use_item_on packets sent, the protection's verdict, warps it blocked}.
     */
    private static Object[] warp(Session c, BlockPos tile) throws Exception {
        c.ctx().runOnClient(mc -> {
            Vec3 d = Vec3.atCenterOf(tile).add(0, 0.5, 0).subtract(mc.player.getEyePosition());
            float want = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
            float now = mc.player.getYRot();
            mc.player.setYRot(now + net.minecraft.util.Mth.wrapDegrees(want - now));
            mc.player.setXRot((float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z))));
        });
        c.ctx().waitTicks(3);
        Object[] r = c.onClient(mc -> {
            int blocked0 = (Integer) Mod.staticCall(ICE, "blockedWarps");
            PacketTrace.start();
            // The tick has just said where he is; the solver's own tick sets this only in the real room.
            Mod.setField(ICE, "inIceFill", true);
            try {
                Method m = Minecraft.class.getDeclaredMethod("startUseItem");
                m.setAccessible(true);
                m.invoke(mc);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
            return new Object[]{blocked0};
        });
        c.ctx().waitTicks(3);
        return c.onClient(mc -> {
            PacketTrace.stop();
            int sent = PacketTrace.sent("use_item") + PacketTrace.sent("use_item_on");
            int blocked = (Integer) Mod.staticCall(ICE, "blockedWarps") - (Integer) r[0];
            return new Object[]{sent, Mod.staticCall(ICE, "lastVerdict"), blocked};
        });
    }

    // ---- 1101-pz-tpmaze --------------------------------------------------------------------------------------------

    static void teleportMaze(Session c) throws Exception {
        List<AutoCloseable> undo = new ArrayList<>();
        try {
            undo.add(c.onClient(mc -> Mod.with(MAZE_CFG, "Enabled", true)));
            undo.add(c.onClient(mc -> Mod.with(MAZE_CFG, "CurrentCellOnly", false)));
            undo.add(c.onClient(mc -> Mod.with(MAZE_CFG, "BestPadOnly", false)));
            undo.add(c.onClient(mc -> Mod.with(MAZE_CFG, "TracerColor", 0xFF55FFFF)));
            Object[] r = c.onClient(mc -> {
                // Two cells of four pads and a start pad, at made-up coordinates.
                java.util.LinkedHashSet<BlockPos> pads = new java.util.LinkedHashSet<>();
                List<Set<BlockPos>> cells = new ArrayList<>();
                for (int cell = 0; cell < 2; cell++) {
                    java.util.LinkedHashSet<BlockPos> set = new java.util.LinkedHashSet<>();
                    for (int i = 0; i < 4; i++) {
                        BlockPos p = new BlockPos(1000 + cell * 10 + (i % 2) * 6, 69, 1000 + (i / 2) * 6);
                        set.add(p);
                        pads.add(p);
                    }
                    cells.add(set);
                }
                BlockPos start = new BlockPos(1005, 69, 990);
                pads.add(start);
                BlockPos best = cells.get(0).stream().skip(2).findFirst().orElseThrow();
                Mod.setField(MAZE, "tpPads", pads);
                Mod.setField(MAZE, "realCells", cells);
                Mod.setField(MAZE, "best", best);
                Mod.setField(MAZE, "correctPortals", new java.util.LinkedHashSet<BlockPos>());
                Vec3 inCell0 = Vec3.atCenterOf(cells.get(0).iterator().next()).add(0, 0.6, 0);
                int all = ((List<?>) Mod.staticCall(MAZE, "padsToDraw", inCell0)).size();
                Mod.set(MAZE_CFG, "setCurrentCellOnly", true);
                List<?> cell = (List<?>) Mod.staticCall(MAZE, "padsToDraw", inCell0);
                boolean cellIsCell0 = cell.size() == 4 && cells.get(0).containsAll(cell);
                int atStart = ((List<?>) Mod.staticCall(MAZE, "padsToDraw", Vec3.atCenterOf(start))).size();
                Mod.set(MAZE_CFG, "setBestPadOnly", true);
                List<?> only = (List<?>) Mod.staticCall(MAZE, "padsToDraw", inCell0);
                Mod.setField(MAZE, "best", null);
                java.util.LinkedHashSet<BlockPos> exit = new java.util.LinkedHashSet<>();
                exit.add(start);
                Mod.setField(MAZE, "correctPortals", exit);
                List<?> onlyExit = (List<?>) Mod.staticCall(MAZE, "padsToDraw", inCell0);
                // Tracer color survives a save and a reload.
                Mod.set(MAZE_CFG, "setTracerColor", 0xFFFF3300);
                Mod.call(Mod.cfg(MAZE_CFG), "save");
                Mod.staticCall(MAZE_CFG, "load");
                int color = (Integer) Mod.get(MAZE_CFG, "getTracerColor");
                Mod.setField(MAZE, "tpPads", new java.util.LinkedHashSet<BlockPos>());
                Mod.setField(MAZE, "realCells", new ArrayList<Set<BlockPos>>());
                Mod.setField(MAZE, "best", null);
                Mod.setField(MAZE, "correctPortals", new java.util.LinkedHashSet<BlockPos>());
                return new Object[]{all, cellIsCell0, atStart, only, best, onlyExit, start, color};
            });
            c.note("all " + r[0] + ", current cell is cell 0 " + r[1] + ", at the start pad " + r[2] + ", best only "
                    + r[3] + " (best " + r[4] + "), exit only " + r[5] + ", tracer color after reload "
                    + Integer.toHexString((Integer) r[7]));
            c.check((Integer) r[0] == 9, "both off should draw all 9 pads, drew " + r[0]);
            c.check(Boolean.TRUE.equals(r[1]), "Current Cell Only did not draw just his cell");
            c.check((Integer) r[2] == 9, "at the start pad (no cell) every pad should draw, drew " + r[2]);
            c.check(List.of(r[4]).equals(r[3]), "Best Pad Only drew " + r[3]);
            c.check(List.of(r[6]).equals(r[5]), "Best Pad Only with one exit candidate drew " + r[5]);
            c.check((Integer) r[7] == 0xFFFF3300, "the tracer color did not survive a reload");
        } finally {
            restore(c, undo);
            c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(MAZE_CFG), "save"));
        }
    }

    // ---- Simon Says ------------------------------------------------------------------------------------------------

    private static final BlockPos A = new BlockPos(111, 123, 92);
    private static final BlockPos B = new BlockPos(111, 122, 94);
    private static final BlockPos C = new BlockPos(111, 121, 93);

    /** The device built (idle), him at it, Simon Says on and at the device. */
    private static void ssSetUp(Session c, List<AutoCloseable> undo) {
        c.hx().sidebar("SKYBLOCK", "The Catac§combs §7(F7)");
        c.waitUntil("DungeonState F7", mc -> "F7".equals(Mod.staticCall("secrets.DungeonState", "getFloor")), 100);
        undo.add(c.onClient(mc -> Mod.with(SS_CFG, "SolverEnabled", true)));
        undo.add(c.onClient(mc -> Mod.with(SS_CFG, "AutoRestartEnabled", false)));
        undo.add(c.onClient(mc -> Mod.with(SS_CFG, "PreventMisclicksEnabled", true)));
        c.hx().call("boss.ss.build", "rounds", 4, "line", true, "seed", 71);
        c.hx().call("dungeon.tp", "x", 107.5, "y", 120.0, "z", 93.5, "yaw", -90f, "pitch", 0f);
        c.waitUntil("the client to have the device", mc ->
                mc.level.getBlockState(new BlockPos(111, 120, 92)).is(Blocks.OBSIDIAN)
                        && mc.level.getBlockState(SimonSaysCases.START).is(Blocks.STONE_BUTTON), 200);
        c.ctx().waitTicks(5);
        undo.add(c.onClient(mc -> Mod.with(SS_CFG, "Enabled", true)));
        c.waitUntil("Simon Says at the device", mc -> (Boolean) Mod.field(SS, "wasActive"), 200);
        c.ctx().waitTicks(10);
    }

    private static void ssTearDown(Session c, List<AutoCloseable> undo) {
        // Off the device BEFORE it is cleared: clearing first dropped him through the floor it stood on, and GrimAC
        // flagged the fall (GroundSpoof + Simulation x4 at a case boundary, 1101-ss-skip-second's first 26.2 run).
        c.hx().call("dungeon.tp", "x", -619.5, "y", 151.0, "z", -619.5, "yaw", 0f, "pitch", 0f);
        c.ctx().waitTicks(5);
        c.hx().call("boss.ss.clear");
        restore(c, undo);
        c.hx().sidebarClear();
    }

    private static void light(Session c, BlockPos lantern) {
        c.server().command("setblock " + lantern.getX() + " " + lantern.getY() + " " + lantern.getZ() + " minecraft:sea_lantern");
        c.waitUntil("lit " + lantern.toShortString(), mc -> mc.level.getBlockState(lantern).is(Blocks.SEA_LANTERN), 40);
        c.ctx().waitTicks(2);
        c.server().command("setblock " + lantern.getX() + " " + lantern.getY() + " " + lantern.getZ() + " minecraft:obsidian");
        c.waitUntil("dark " + lantern.toShortString(), mc -> mc.level.getBlockState(lantern).is(Blocks.OBSIDIAN), 40);
        c.ctx().waitTicks(2);
    }

    @SuppressWarnings("unchecked")
    private static List<BlockPos> order(Session c) {
        return c.onClient(mc -> new ArrayList<>((List<BlockPos>) Mod.field(SS, "clickInOrder")));
    }

    /** A skip attempt, then the device lights A, B, A again, B, C: the re-light drops A, leaving B, C. */
    static void ssRelight(Session c) throws Exception {
        List<AutoCloseable> undo = new ArrayList<>();
        try {
            ssSetUp(c, undo);
            int drops0 = c.onClient(mc -> (Integer) Mod.field(SS, "skipRelightDrops"));
            c.ctx().runOnClient(mc -> {
                ((List<?>) Mod.field(SS, "clickInOrder")).clear();
                Mod.setField(SS, "clickNeeded", 0);
                Mod.setField(SS, "firstPhase", true);
                Mod.setField(SS, "autoStartClickedThisPhase", true);
                Mod.setField(SS, "skipDropped", false);
                Mod.setField(SS, "totalClicksThisAttempt", 0);
            });
            light(c, A);
            light(c, B);
            List<BlockPos> beforeRelight = order(c);
            light(c, A);
            light(c, B);
            light(c, C);
            List<BlockPos> after = order(c);
            int drops = c.onClient(mc -> (Integer) Mod.field(SS, "skipRelightDrops")) - drops0;
            c.note("after A, B: " + beforeRelight + "; after A, B, A, B, C: " + after + "; re-light drops " + drops);
            c.check(beforeRelight.equals(List.of(A, B)), "the first two lights were not recorded in order: " + beforeRelight);
            c.check(drops == 1, "the re-light rule fired " + drops + " times, expected once");
            c.check(after.equals(List.of(B, C)), "after a landed skip the sequence should be B, C, got " + after);
        } finally {
            ssTearDown(c, undo);
        }
    }

    /** A skip attempt and lights A, B: Prevent Misclicks lets the second light through until the first click lands. */
    static void ssSecond(Session c) throws Exception {
        List<AutoCloseable> undo = new ArrayList<>();
        try {
            ssSetUp(c, undo);
            Object[] r = c.onClient(mc -> {
                @SuppressWarnings("unchecked")
                List<BlockPos> list = (List<BlockPos>) Mod.field(SS, "clickInOrder");
                list.clear();
                list.add(A);
                list.add(B);
                Mod.setField(SS, "clickNeeded", 0);
                Mod.setField(SS, "totalClicksThisAttempt", 0);
                Mod.setField(SS, "autoStartClickedThisPhase", true);
                boolean second = (Boolean) Mod.staticCall(SS, "shouldBlockClick", B.west(), false);
                boolean first = (Boolean) Mod.staticCall(SS, "shouldBlockClick", A.west(), false);
                boolean third = (Boolean) Mod.staticCall(SS, "shouldBlockClick", C.west(), false);
                Mod.setField(SS, "autoStartClickedThisPhase", false);
                boolean secondNoSkip = (Boolean) Mod.staticCall(SS, "shouldBlockClick", B.west(), false);
                list.clear();
                return new Object[]{second, first, third, secondNoSkip};
            });
            c.note("after a skip attempt: second blocked " + r[0] + ", first " + r[1] + ", other " + r[2]
                    + "; with no skip attempt the second is blocked " + r[3]);
            c.check(Boolean.FALSE.equals(r[0]), "the second light was blocked after a skip attempt");
            c.check(Boolean.FALSE.equals(r[1]), "the first light was blocked");
            c.check(Boolean.TRUE.equals(r[2]), "a light not in the sequence was let through");
            c.check(Boolean.TRUE.equals(r[3]), "with no skip attempt the second light was let through");
        } finally {
            ssTearDown(c, undo);
        }
    }

    /**
     * The device runs round 1; the test makes the mod's reading wrong (as a misread reveal would), then presses the right
     * button sneaking (the override). The device takes it; the mod must count the press and read round 2 fresh, in the
     * device's order - not with round 1's reverse applied to it (which the grid reset re-armed when no click had counted).
     */
    static void ssOverrideRescan(Session c) throws Exception {
        List<AutoCloseable> undo = new ArrayList<>();
        try {
            ssSetUp(c, undo);
            int unknown0 = c.onClient(mc -> (Integer) Mod.field(SS, "unknownPresses"));
            SimonSaysCases.click(c, SimonSaysCases.START);
            c.waitUntil("round 1 buttons (Hx)", mc -> {
                var st = SimonSaysCases.state(c);
                return "INPUT".equals(st.get("phase").getAsString()) && st.get("round").getAsInt() == 1;
            }, 400);
            c.ctx().waitTicks(14);
            var seq = SimonSaysCases.state(c).getAsJsonArray("sequence");
            BlockPos first = at(seq, 0);
            // The mod "misread" the reveal: it holds some other lantern.
            BlockPos other = first.equals(A) ? B : A;
            c.ctx().runOnClient(mc -> {
                @SuppressWarnings("unchecked")
                List<BlockPos> list = (List<BlockPos>) Mod.field(SS, "clickInOrder");
                list.clear();
                list.add(other);
                Mod.setField(SS, "clickNeeded", 0);
            });
            ClickGuardCases.sneak(c, true);
            SimonSaysCases.click(c, first.west());
            c.ctx().waitTicks(4);
            ClickGuardCases.sneak(c, false);
            c.waitUntil("round 2 buttons (Hx)", mc -> {
                var st = SimonSaysCases.state(c);
                return "INPUT".equals(st.get("phase").getAsString()) && st.get("round").getAsInt() == 2;
            }, 400);
            c.ctx().waitTicks(14);
            var seq2 = SimonSaysCases.state(c).getAsJsonArray("sequence");
            List<BlockPos> want = List.of(at(seq2, 0), at(seq2, 1));
            List<BlockPos> got = order(c);
            int unknown = c.onClient(mc -> (Integer) Mod.field(SS, "unknownPresses")) - unknown0;
            int broke = c.events("ss.broke").size();
            c.note("round 1 press on " + first.toShortString() + " while the mod held " + other.toShortString()
                    + ": unknown presses " + unknown + ", breaks " + broke + "; round 2 device " + want + ", mod " + got);
            c.check(broke == 0, "the device broke");
            c.check(unknown == 1, "the override press was counted " + unknown + " times as unknown, expected 1");
            c.check(got.equals(want), "round 2 was read as " + got + ", the device showed " + want);
        } finally {
            ClickGuardCases.sneak(c, false);
            ssTearDown(c, undo);
        }
    }

    private static BlockPos at(com.google.gson.JsonArray seq, int k) {
        var xyz = seq.get(k).getAsJsonArray();
        return new BlockPos(xyz.get(0).getAsInt(), xyz.get(1).getAsInt(), xyz.get(2).getAsInt());
    }
}
