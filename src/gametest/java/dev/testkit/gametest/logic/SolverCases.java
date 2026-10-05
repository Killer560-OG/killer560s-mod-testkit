package dev.testkit.gametest.logic;

import dev.testkit.gametest.mod.Mod;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 354 terminals, 355 experiments + puzzle solvers: boards in, clicks out, through the mod's own pure entry points
 * (TerminalSolverFeature.solve / pickAutoClickTarget, ExperimentSolver, TicTacToe minimax, Weirdos lists).
 */
final class SolverCases {

    private static final String TERM = "terminals.TerminalSolverFeature";
    private static final String TYPE = "terminals.TerminalType";

    private SolverCases() {
    }

    // ---- 354 terminals -------------------------------------------------------------------------------------------

    static void terminals(LogicCase c) {
        // Titles (TerminalType.java:12-17): each title is claimed by exactly one type, with matches().
        Map<String, String> titles = new LinkedHashMap<>();
        titles.put("Correct all the panes!", "PANES");
        titles.put("Change all to same color!", "RUBIX");
        titles.put("Click in order!", "NUMBERS");
        titles.put("What starts with: 'R'?", "STARTS_WITH");
        titles.put("Select all the LIGHT BLUE items!", "SELECT"); // TerminalSolverFeature.java crash note, 2026-09-20
        titles.put("Click the button on time!", "MELODY");
        for (Map.Entry<String, String> t : titles.entrySet()) {
            List<String> claimed = new ArrayList<>();
            for (Object type : Mod.cls(TYPE).getEnumConstants()) {
                Pattern p = (Pattern) Mod.call(type, "titlePattern");
                if (p.matcher(t.getKey()).matches()) {
                    claimed.add(((Enum<?>) type).name());
                }
            }
            c.eq("title \"" + t.getKey() + "\"", List.of(t.getValue()), claimed);
        }
        c.eq("forged title in chat is no terminal", List.of(), claimedBy("[VIP] Eve: Click in order!"));

        // PANES: every red pane, nothing else.
        List<ItemStack> panes = board(45);
        panes.set(11, stack(Items.RED_STAINED_GLASS_PANE, 1, "", false));
        panes.set(12, stack(Items.RED_STAINED_GLASS_PANE, 1, "", false));
        panes.set(20, stack(Items.RED_STAINED_GLASS_PANE, 1, "", false));
        panes.set(13, stack(Items.LIME_STAINED_GLASS_PANE, 1, "", false));
        Map<Integer, Object> hp = solve("PANES", "Correct all the panes!", panes);
        c.eq("panes highlights", Set.of(11, 12, 20), hp.keySet());
        Object pick = pick("PANES", hp, Set.of(11));
        c.eq("panes skips a slot awaiting confirm", "12/0/CLONE", target(pick));

        // NUMBERS: lowest count is the primary next click, then the following one.
        List<ItemStack> nums = board(36);
        nums.set(10, stack(Items.RED_STAINED_GLASS_PANE, 3, "", false));
        nums.set(11, stack(Items.RED_STAINED_GLASS_PANE, 1, "", false));
        nums.set(12, stack(Items.RED_STAINED_GLASS_PANE, 2, "", false));
        nums.set(14, stack(Items.RED_STAINED_GLASS_PANE, 4, "", false));
        nums.set(13, stack(Items.LIME_STAINED_GLASS_PANE, 5, "", false));
        Map<Integer, Object> hn = solve("NUMBERS", "Click in order!", nums);
        List<Integer> order = new ArrayList<>(hn.keySet());
        c.check("numbers first two", order.size() >= 2 && order.get(0) == 11 && order.get(1) == 12, "order " + order);
        c.check("numbers third tier is the next count when shown", order.size() < 3 || order.get(2) == 10,
                "order " + order);
        c.eq("numbers primary is the lowest count", true, Mod.call(hn.get(11), "primary"));
        c.eq("numbers clicks the primary", "11/0/CLONE", target(pick("NUMBERS", hn, Set.of())));
        c.eq("numbers waits while the primary awaits confirm", null, pick("NUMBERS", hn, Set.of(11)));

        // RUBIX: a worked board, then a property sweep.
        Mod.staticCall(TERM, "resetRubixTarget");
        List<ItemStack> rubix = board(45);
        rubix.set(12, stack(Items.ORANGE_STAINED_GLASS_PANE, 1, "", false));
        rubix.set(13, stack(Items.ORANGE_STAINED_GLASS_PANE, 1, "", false));
        rubix.set(14, stack(Items.YELLOW_STAINED_GLASS_PANE, 1, "", false));
        Map<Integer, Object> hr = solve("RUBIX", "Change all to same color!", rubix);
        c.eq("rubix worked board slots", Set.of(14), hr.keySet());
        c.eq("rubix worked board label", "-1", hr.isEmpty() ? null : Mod.call(hr.get(14), "label"));
        c.eq("rubix negative label right-clicks", "14/1/PICKUP", target(pick("RUBIX", hr, Set.of())));
        rubixSweep(c);
        Mod.staticCall(TERM, "resetRubixTarget");

        // STARTS_WITH: names by letter, glinting items skipped except a golden apple.
        List<ItemStack> sw = board(45);
        sw.set(10, stack(Items.REDSTONE, 1, "Redstone", false));
        sw.set(11, stack(Items.APPLE, 1, "Apple", false));
        sw.set(12, stack(Items.ROTTEN_FLESH, 1, "Rotten Flesh", true));
        sw.set(13, stack(Items.POPPY, 1, "§aRose Bush", false));
        c.eq("starts with R", Set.of(10, 13), solve("STARTS_WITH", "What starts with: 'R'?", sw).keySet());
        List<ItemStack> sg = board(45);
        sg.set(10, stack(Items.GOLDEN_APPLE, 1, "Golden Apple", true));
        sg.set(11, stack(Items.GOLD_INGOT, 1, "Gold Ingot", true));
        sg.set(12, stack(Items.GLASS, 1, "Glass", false));
        c.eq("starts with G keeps the glinting golden apple", Set.of(10, 12),
                solve("STARTS_WITH", "What starts with: 'G'?", sg).keySet());

        // SELECT: colour from the title (Hypixel writes it upper case), aliases, glint and black panes skipped.
        List<ItemStack> sel = board(45);
        sel.set(10, stack(Items.LIGHT_BLUE_WOOL, 1, "Light Blue Wool", false));
        sel.set(11, stack(Items.BLUE_WOOL, 1, "Blue Wool", false));
        sel.set(12, stack(Items.LIGHT_BLUE_DYE, 1, "Light Blue Dye", true));
        sel.set(13, stack(Items.BLACK_STAINED_GLASS_PANE, 1, "Light Blue", false));
        c.eq("select LIGHT BLUE", Set.of(10), solve("SELECT", "Select all the LIGHT BLUE items!", sel).keySet());
        List<ItemStack> silver = board(45);
        silver.set(10, stack(Items.LIGHT_GRAY_WOOL, 1, "Light Gray Wool", false));
        silver.set(11, stack(Items.GRAY_WOOL, 1, "Gray Wool", false));
        c.eq("select SILVER means light gray", Set.of(10),
                solve("SELECT", "Select all the SILVER items!", silver).keySet());
        List<ItemStack> blue = board(45);
        blue.set(10, stack(Items.BLUE_WOOL, 1, "Blue Wool", false));
        blue.set(11, stack(Items.LAPIS_LAZULI, 1, "Lapis Lazuli", false));
        blue.set(12, stack(Items.LIGHT_BLUE_WOOL, 1, "Light Blue Wool", false));
        c.eq("select BLUE takes lapis, not light blue", Set.of(10, 11),
                solve("SELECT", "Select all the BLUE items!", blue).keySet());

        c.eq("melody has no highlights", 0, solve("MELODY", "Click the button on time!", board(54)).size());
    }

    private static List<String> claimedBy(String title) {
        List<String> claimed = new ArrayList<>();
        for (Object type : Mod.cls(TYPE).getEnumConstants()) {
            if (((Pattern) Mod.call(type, "titlePattern")).matcher(title).matches()) {
                claimed.add(((Enum<?>) type).name());
            }
        }
        return claimed;
    }

    /**
     * Random 3x3 Rubix boards: applying each label's signed click count (one step per click through the 5-colour
     * cycle, negative = backwards) must leave every pane the same colour, and the total clicks must be the minimum
     * over every target colour.
     */
    private static void rubixSweep(LogicCase c) {
        Item[] cycle = {Items.ORANGE_STAINED_GLASS_PANE, Items.YELLOW_STAINED_GLASS_PANE,
            Items.GREEN_STAINED_GLASS_PANE, Items.BLUE_STAINED_GLASS_PANE, Items.RED_STAINED_GLASS_PANE};
        int[] slots = {12, 13, 14, 21, 22, 23, 30, 31, 32};
        Random rng = new Random(560);
        int bad = 0;
        String firstBad = "";
        for (int round = 0; round < 300; round++) {
            Mod.staticCall(TERM, "resetRubixTarget");
            List<ItemStack> b = board(45);
            int[] colour = new int[slots.length];
            for (int i = 0; i < slots.length; i++) {
                colour[i] = rng.nextInt(5);
                b.set(slots[i], stack(cycle[colour[i]], 1, "", false));
            }
            Map<Integer, Object> h = solve("RUBIX", "Change all to same color!", b);
            int[] after = colour.clone();
            int clicks = 0;
            for (int i = 0; i < slots.length; i++) {
                Object hl = h.get(slots[i]);
                if (hl != null) {
                    int n = Integer.parseInt((String) Mod.call(hl, "label"));
                    after[i] = Math.floorMod(colour[i] + n, 5);
                    clicks += Math.abs(n);
                }
            }
            int best = Integer.MAX_VALUE;
            for (int t = 0; t < 5; t++) {
                int cost = 0;
                for (int col : colour) {
                    int f = Math.floorMod(t - col, 5);
                    cost += Math.min(f, 5 - f);
                }
                best = Math.min(best, cost);
            }
            boolean uniform = Arrays.stream(after).distinct().count() == 1;
            if (!uniform || clicks != best) {
                bad++;
                if (firstBad.isEmpty()) {
                    firstBad = Arrays.toString(colour) + " -> " + Arrays.toString(after) + ", " + clicks
                            + " clicks vs minimum " + best;
                }
            }
        }
        c.check("rubix: 300 random boards solve uniformly in the fewest clicks", bad == 0,
                bad + " bad, first " + firstBad);
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, Object> solve(String type, String title, List<ItemStack> items) {
        return (Map<Integer, Object>) Mod.staticCall(TERM, "solve", Mod.enumValue(TYPE, type), title, items);
    }

    private static Object pick(String type, Map<Integer, Object> highlights, Set<Integer> awaiting) {
        return Mod.staticCall(TERM, "pickAutoClickTarget", Mod.enumValue(TYPE, type), highlights, awaiting);
    }

    private static String target(Object t) {
        if (t == null) {
            return null;
        }
        return Mod.call(t, "slot") + "/" + Mod.call(t, "button") + "/" + Mod.call(t, "clickType");
    }

    static List<ItemStack> board(int size) {
        List<ItemStack> out = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            out.add(ItemStack.EMPTY);
        }
        return out;
    }

    static ItemStack stack(Item item, int count, String name, boolean foil) {
        ItemStack s = new ItemStack(item, count);
        if (!name.isEmpty()) {
            s.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        }
        if (foil) {
            s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        return s;
    }

    // ---- 355 experiments + puzzles -------------------------------------------------------------------------------

    private static final String EXP = "experiments.ExperimentSolver";

    static void experimentsAndPuzzles(LogicCase c) {
        // Mode from the title.
        Object solver = R.construct(EXP);
        c.eq("mode Chronomatron", "CHRONOMATRON", name(Mod.call(solver, "select", "Chronomatron (High)")));
        c.eq("mode Ultrasequencer", "ULTRASEQUENCER", name(Mod.call(solver, "select", "Ultrasequencer (Supreme)")));
        c.eq("mode Superpairs", "SUPERPAIRS", name(Mod.call(solver, "select", "Superpairs (Metaphysical)")));
        c.eq("mode Stakes is not a game", "NONE", name(Mod.call(solver, "select", "Chronomatron Stakes")));
        c.eq("mode other", "NONE", name(Mod.call(solver, "select", "Experimentation Table")));

        // Chronomatron: a foiled note under the clock is appended; glowstone resets the round, keeps the sequence.
        Object chrono = R.construct(EXP);
        Mod.call(chrono, "select", "Chronomatron (High)");
        Mod.call(chrono, "observe", List.of(cell(49, "minecraft:clock", 1, false, "Timer"),
                cell(12, "minecraft:red_terracotta", 1, true, "Note")), false, 0L);
        c.eq("chronomatron next after one note", 12, Mod.call(chrono, "chronomatronNextClickSlot"));
        c.eq("chronomatron following after one note", -1, Mod.call(chrono, "chronomatronFollowingClickSlot"));
        Mod.call(chrono, "observe", List.of(cell(49, "minecraft:glowstone", 1, false, "Remember")), false, 100L);
        c.eq("chronomatron glowstone hides the next click", -1, Mod.call(chrono, "chronomatronNextClickSlot"));
        Mod.call(chrono, "observe", List.of(cell(49, "minecraft:clock", 1, false, "Timer"),
                cell(30, "minecraft:lime_terracotta", 1, true, "Note")), false, 200L);
        c.eq("chronomatron replays from the start", 12, Mod.call(chrono, "chronomatronNextClickSlot"));
        c.eq("chronomatron then the new note", 30, Mod.call(chrono, "chronomatronFollowingClickSlot"));
        OptionalInt early = (OptionalInt) Mod.call(chrono, "nextClick", List.of(cell(49, "minecraft:clock", 1,
                false, "Timer")), false, false, 300L, 0L, 100L, 500L);
        c.eq("chronomatron waits the first-click delay", OptionalInt.empty(), early);
        OptionalInt first = (OptionalInt) Mod.call(chrono, "nextClick", List.of(cell(49, "minecraft:clock", 1,
                false, "Timer")), false, false, 800L, 0L, 100L, 500L);
        c.eq("chronomatron clicks the first note after the delay", OptionalInt.of(12), first);

        // Ultrasequencer: numbers shown under glowstone give the order (count - 1 -> slot).
        Object ultra = R.construct(EXP);
        Mod.call(ultra, "select", "Ultrasequencer (Supreme)");
        Mod.call(ultra, "observe", List.of(cell(49, "minecraft:glowstone", 1, false, "Remember"),
                cell(10, "minecraft:red_dye", 2, false, "2"), cell(20, "minecraft:lime_dye", 1, false, "1"),
                cell(30, "minecraft:bone_meal", 3, false, "3")), false, 1000L);
        c.eq("ultrasequencer next", 20, Mod.call(ultra, "ultrasequencerNextClickSlot"));
        c.eq("ultrasequencer following", 10, Mod.call(ultra, "ultrasequencerFollowingClickSlot"));
        OptionalInt u = (OptionalInt) Mod.call(ultra, "nextClick", List.of(cell(49, "minecraft:clock", 1, false,
                "Timer")), false, false, 1600L, 0L, 100L, 500L);
        c.eq("ultrasequencer clicks under the clock", OptionalInt.of(20), u);

        // Superpairs labels.
        c.eq("label: wrapped book", "Protection 7", Mod.staticCall(EXP, "itemLabel",
                cell(10, "minecraft:enchanted_book", 1, false, "§9Enchanted Book (Protection VII)")));
        c.eq("label: book from lore", "Power 6", Mod.staticCall(EXP, "itemLabel",
                cellLore(10, "minecraft:enchanted_book", "Enchanted Book", "§9Power VI")));
        c.eq("label: plain item", "Diamond", Mod.staticCall(EXP, "itemLabel",
                cell(10, "minecraft:diamond", 1, false, "Diamond")));

        ticTacToe(c);
        weirdos(c);

        // Ice Fill gap filling (AutoIceFill.java:224).
        @SuppressWarnings("unchecked")
        List<Vec3> filled = (List<Vec3>) Mod.staticCall("autopuzzles.AutoIceFill", "fillGaps",
                List.of(new Vec3(0, 0, 0), new Vec3(3, 0, 0), new Vec3(3, 0, 2)));
        c.eq("ice fill gaps", List.of(new Vec3(0, 0, 0), new Vec3(1, 0, 0), new Vec3(2, 0, 0), new Vec3(3, 0, 0),
                new Vec3(3, 0, 1), new Vec3(3, 0, 2)), filled);
    }

    private static Object cell(int slot, String id, int count, boolean foil, String name) {
        return R.construct(EXP + "$Cell", slot, id, count, foil, name, false, "");
    }

    private static Object cellLore(int slot, String id, String name, String lore) {
        return R.construct(EXP + "$Cell", slot, id, 1, false, name, false, lore);
    }

    private static String name(Object e) {
        return e == null ? null : ((Enum<?>) e).name();
    }

    /**
     * Every position with O (the player) to move and no winner: the mod's move must be game-theoretically optimal
     * (same win/draw/loss value as the best move), against an independent full minimax.
     */
    private static void ticTacToe(LogicCase c) {
        String ttt = "puzzlesolvers.TicTacToeSolverFeature";
        char[] b = new char[9];
        b[0] = 'O';
        b[1] = 'O';
        b[3] = 'X';
        b[4] = 'X';
        c.eq("tic tac toe takes the win", 2, Mod.staticCall(ttt, "getBestMove", b, true));
        char[] block = new char[9];
        block[0] = 'O';
        block[8] = 'O';
        block[3] = 'X';
        block[4] = 'X';
        c.eq("tic tac toe blocks the only threat", 5, Mod.staticCall(ttt, "getBestMove", block, true));
        int positions = 0;
        int wrong = 0;
        String firstWrong = "";
        for (int code = 0; code < 19683; code++) {
            char[] board = new char[9];
            int x = 0;
            int o = 0;
            int v = code;
            for (int i = 0; i < 9; i++) {
                int d = v % 3;
                v /= 3;
                board[i] = d == 0 ? '\0' : d == 1 ? 'X' : 'O';
                x += d == 1 ? 1 : 0;
                o += d == 2 ? 1 : 0;
            }
            if (!(x == o || x == o + 1) || winner(board) != 0 || x + o == 9) {
                continue;
            }
            positions++;
            Integer move = (Integer) Mod.staticCall(ttt, "getBestMove", board.clone(), true);
            int bestValue = Integer.MIN_VALUE;
            for (int i = 0; i < 9; i++) {
                if (board[i] == '\0') {
                    board[i] = 'O';
                    bestValue = Math.max(bestValue, minimax(board, false));
                    board[i] = '\0';
                }
            }
            int got;
            if (move == null || board[move] != '\0') {
                got = Integer.MIN_VALUE;
            } else {
                board[move] = 'O';
                got = minimax(board, false);
                board[move] = '\0';
            }
            if (got != bestValue) {
                wrong++;
                if (firstWrong.isEmpty()) {
                    firstWrong = new String(board).replace('\0', '.') + " -> " + move + " (value " + got + ", best "
                            + bestValue + ")";
                }
            }
        }
        c.check("tic tac toe optimal in every position", wrong == 0 && positions > 3000,
                wrong + " of " + positions + " positions suboptimal, first " + firstWrong);
        c.note("tic tac toe: " + positions + " positions checked against an independent minimax");
    }

    private static int winner(char[] b) {
        int[][] lines = {{0, 1, 2}, {3, 4, 5}, {6, 7, 8}, {0, 3, 6}, {1, 4, 7}, {2, 5, 8}, {0, 4, 8}, {2, 4, 6}};
        for (int[] l : lines) {
            if (b[l[0]] != '\0' && b[l[0]] == b[l[1]] && b[l[0]] == b[l[2]]) {
                return b[l[0]] == 'O' ? 1 : -1;
            }
        }
        return 0;
    }

    /** +1 O wins, 0 draw, -1 X wins, with perfect play. */
    private static int minimax(char[] b, boolean oToMove) {
        int w = winner(b);
        if (w != 0) {
            return w;
        }
        boolean full = true;
        int best = oToMove ? -2 : 2;
        for (int i = 0; i < 9; i++) {
            if (b[i] != '\0') {
                continue;
            }
            full = false;
            b[i] = oToMove ? 'O' : 'X';
            int v = minimax(b, !oToMove);
            b[i] = '\0';
            best = oToMove ? Math.max(best, v) : Math.min(best, v);
        }
        return full ? 0 : best;
    }

    /**
     * Three Weirdos (WeirdosSolverFeature.java:46-70): every line each list describes, said by an NPC, must be
     * read out by NPC_LINE intact and classified into its own list only.
     */
    @SuppressWarnings("unchecked")
    private static void weirdos(LogicCase c) {
        String w = "puzzlesolvers.WeirdosSolverFeature";
        Pattern npc = Mod.pattern(w, "NPC_LINE");
        List<Pattern> solutions = (List<Pattern>) R.get(w, "SOLUTIONS");
        List<Pattern> wrong = (List<Pattern>) R.get(w, "WRONG");
        int lines = 0;
        for (int list = 0; list < 2; list++) {
            List<Pattern> own = list == 0 ? solutions : wrong;
            for (Pattern p : own) {
                for (String sample : samples(p.pattern())) {
                    lines++;
                    Matcher m = npc.matcher("[NPC] Aldous: " + sample);
                    if (!c.check("NPC_LINE reads \"" + sample + "\"", m.find() && sample.equals(m.group(2)),
                            "group 2 was " + (m.hitEnd() ? "?" : ""))) {
                        continue;
                    }
                    String dialogue = m.group(2);
                    boolean isSolution = solutions.stream().anyMatch(s -> s.matcher(dialogue).matches());
                    boolean isWrong = wrong.stream().anyMatch(s -> s.matcher(dialogue).matches());
                    c.check("weirdos \"" + sample + "\" is only a " + (list == 0 ? "solution" : "wrong answer"),
                            list == 0 ? isSolution && !isWrong : isWrong && !isSolution,
                            "solution " + isSolution + ", wrong " + isWrong);
                }
            }
        }
        c.check("weirdos lines generated", lines >= 18, lines + "");
    }

    /** Concrete lines a simple weirdo regex describes: ".+" -> a name, "\\." -> ".", "\\.?" -> both forms. */
    static List<String> samples(String regex) {
        List<String> out = new ArrayList<>();
        String base = regex.replace(".+", "Berta");
        if (base.endsWith("\\.?")) {
            String stem = base.substring(0, base.length() - 3).replace("\\.", ".");
            out.add(stem + ".");
            out.add(stem);
        } else {
            out.add(base.replace("\\.", "."));
        }
        return out;
    }
}
