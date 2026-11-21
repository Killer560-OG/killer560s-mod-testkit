package dev.testkit.gametest.logic;

import dev.testkit.gametest.mod.Mod;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * 1118-logic-mapcycler-bounds (mod roomcycle-1118, Map Cycler): {@code RushBounds.bounds} against the true number of rooms
 * the player must open to reach Blood (killer560 11-18: the rooms on the door path from the Entrance to Blood, NOT counting
 * the Entrance, the Fairy or Blood), computed here independently from the room tree.
 * <ul>
 *   <li>Generated floors (pure Java here: rooms of 1-4 slots, a random door tree, so no room library is needed), 600 of
 *       them: with everything known min == max == truth; with slots, gaps, names and room completeness hidden at random
 *       (three levels, as the map fills in) min <= truth <= max on every one.</li>
 *   <li>Sim floors ({@code SimFloorGen.plan}, F7, 3-8 rooms to Blood asked) when the client has the room library: the
 *       same two checks on what the sim really lays out.</li>
 * </ul>
 */
public final class MapCycler1118Cases {

    private static final byte UNKNOWN = 0;
    private static final byte JOIN = 1;
    private static final byte DOOR = 2;
    private static final byte WALL = 3;
    private static final byte KEY = 4;
    private static final int UNSEEN = -1;
    private static final int EMPTY = -2;

    private MapCycler1118Cases() {
    }

    /** A floor: slot -> room (or EMPTY), gaps (east, south per slot), names, entrance, blood. */
    private record Floor(int across, int down, int[] slots, byte[] gaps, int rooms, int entrance, int blood, int fairy) {
    }

    static void bounds(LogicCase c) throws Exception {
        Method m = Mod.cls("mapcycler.RushBounds").getMethod("bounds", int.class, int.class, int[].class, byte[].class,
                String[].class, boolean[].class, int.class, int.class, int.class);
        Random rnd = new Random(1118);
        List<Floor> floors = new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            floors.add(synthetic(rnd));
        }
        judge(c, m, "generated", floors, rnd);
        List<Floor> sim = simFloors(c);
        if (sim != null) {
            judge(c, m, "sim", sim, rnd);
        }
    }

    private static void judge(LogicCase c, Method m, String kind, List<Floor> floors, Random rnd) throws Exception {
        int fullBad = 0;
        int partBad = 0;
        int partial = 0;
        int resolved = 0;
        int[] hist = new int[16];
        List<String> examples = new ArrayList<>();
        for (Floor f : floors) {
            int truth = truth(f);
            hist[Math.min(15, truth)]++;
            String[] names = new String[f.rooms];
            boolean[] complete = new boolean[f.rooms];
            for (int r = 0; r < f.rooms; r++) {
                names[r] = "R" + r;
                complete[r] = true;
            }
            int[] b = (int[]) m.invoke(null, f.across, f.down, f.slots, f.gaps, names, complete, f.entrance, f.blood, f.fairy);
            if (b[0] != truth || b[1] != truth) {
                fullBad++;
                if (examples.size() < 4) {
                    examples.add(kind + " full: truth " + truth + " got " + b[0] + ".." + b[1]);
                }
            }
            for (double p : new double[]{0.15, 0.35, 0.6}) {
                partial++;
                Object[] view = hide(f, p, rnd);
                int[] pb = (int[]) m.invoke(null, view);
                if (pb[0] > truth || pb[1] < truth) {
                    partBad++;
                    if (examples.size() < 8) {
                        examples.add(kind + " p=" + p + ": truth " + truth + " got " + pb[0] + ".." + pb[1]);
                    }
                }
                if (pb[0] == pb[1]) {
                    resolved++;
                }
            }
        }
        c.check(kind + " floors, everything known: min == max == truth", fullBad == 0,
                fullBad + " of " + floors.size() + " wrong; " + examples);
        c.check(kind + " floors, part known: min <= truth <= max", partBad == 0,
                partBad + " of " + partial + " wrong; " + examples);
        c.note(String.format(Locale.ROOT, "%s: %d floors, truth histogram %s; part-known views resolved exactly %d of %d",
                kind, floors.size(), Arrays.toString(hist), resolved, partial));
        c.check(kind + " floors: the bounds were exercised (some views resolved, some not)",
                resolved > 0 && resolved < partial, "resolved " + resolved + " of " + partial);
    }

    /**
     * 1121-logic-mapcycler-proof (mod mapcycler-keep-1121): the proven most ({@code RushBounds.proven}, reached through the
     * shapes overload of {@code bounds}).
     * <ul>
     *   <li>killer560's 11-21 2:04 AM F7 map, copied cell for cell from his log (wither doors Well-Wizard, Wizard-Double
     *       Diamond, Double Diamond-Fairy, Fairy-Altar; only Altar's two top neighbors unseen near it): Blood is proven at
     *       most 5 rooms away while the least is lower (the "Kept: Blood is at most 5" path, not a guess).</li>
     *   <li>The same map without the wither doors, or without the room shapes, proves nothing within 5.</li>
     *   <li>600 generated floors whose one-cell Blood is reached through wither doors marked at random on the true path
     *       (the blood door always): with everything known min == max == truth, and in every part-known view the proven
     *       most is never under the truth; on some views it is lower than without wither doors and shapes.</li>
     * </ul>
     */
    static void proof(LogicCase c) throws Exception {
        Method m10 = Mod.cls("mapcycler.RushBounds").getMethod("bounds", int.class, int.class, int[].class, byte[].class,
                String[].class, String[].class, boolean[].class, int.class, int.class, int.class);
        Method m9 = Mod.cls("mapcycler.RushBounds").getMethod("bounds", int.class, int.class, int[].class, byte[].class,
                String[].class, boolean[].class, int.class, int.class, int.class);
        int[] his = (int[]) m10.invoke(null, hisMap(true, true));
        c.check("11-21 map: Blood proven at most 5 rooms away, least lower", his[1] == 5 && his[0] < 5,
                "got " + his[0] + ".." + his[1]);
        int[] noKey = (int[]) m10.invoke(null, hisMap(false, true));
        c.check("11-21 map without wither doors: nothing proven within 5", noKey[1] > 5, "got " + noKey[0] + ".." + noKey[1]);
        int[] noShape = (int[]) m10.invoke(null, hisMap(true, false));
        c.check("11-21 map without room shapes: nothing proven within 5", noShape[1] > 5,
                "got " + noShape[0] + ".." + noShape[1]);
        Random rnd = new Random(1121);
        int floors = 0;
        int fullBad = 0;
        int partBad = 0;
        int views = 0;
        int lowered = 0;
        int proved = 0;
        List<String> examples = new ArrayList<>();
        while (floors < 600) {
            Floor f = keyed(synthetic(rnd), rnd);
            if (f == null) {
                continue;
            }
            floors++;
            int truth = truth(f);
            String[] shapes = shapes(f);
            String[] names = new String[f.rooms];
            boolean[] complete = new boolean[f.rooms];
            for (int r = 0; r < f.rooms; r++) {
                names[r] = "R" + r;
                complete[r] = true;
            }
            int[] b = (int[]) m10.invoke(null, f.across, f.down, f.slots, f.gaps, names, shapes, complete, f.entrance, f.blood, f.fairy);
            if (b[0] != truth || b[1] != truth) {
                fullBad++;
                if (examples.size() < 4) {
                    examples.add("full: truth " + truth + " got " + b[0] + ".." + b[1]);
                }
            }
            for (double p : new double[]{0.15, 0.35, 0.6}) {
                views++;
                Object[] v = hide(f, p, rnd);
                String[] vNames = (String[]) v[4];
                String[] vShapes = new String[f.rooms];
                for (int r = 0; r < f.rooms; r++) {
                    vShapes[r] = vNames[r] != null ? shapes[r] : null;
                }
                int[] pb = (int[]) m10.invoke(null, v[0], v[1], v[2], v[3], v[4], vShapes, v[5], v[6], v[7], v[8]);
                byte[] plain = ((byte[]) v[3]).clone();
                for (int i = 0; i < plain.length; i++) {
                    plain[i] = plain[i] == KEY ? DOOR : plain[i];
                }
                int[] ob = (int[]) m9.invoke(null, v[0], v[1], v[2], plain, v[4], v[5], v[6], v[7], v[8]);
                if (pb[0] > truth || pb[1] < truth) {
                    partBad++;
                    if (examples.size() < 8) {
                        examples.add("p=" + p + ": truth " + truth + " got " + pb[0] + ".." + pb[1]);
                    }
                }
                lowered += pb[1] < ob[1] ? 1 : 0;
                proved += pb[0] < pb[1] && pb[1] != Integer.MAX_VALUE ? 1 : 0;
            }
        }
        c.check("keyed floors, everything known: min == max == truth", fullBad == 0, fullBad + " of " + floors + " wrong; " + examples);
        c.check("keyed floors, part known: the proven most is never under the truth", partBad == 0,
                partBad + " of " + views + " wrong; " + examples);
        c.check("keyed floors: wither doors and shapes lowered the most on some views", lowered > 0, "lowered " + lowered + " of " + views);
        c.note(String.format(Locale.ROOT, "11-21 map %d..%d (no wither doors %d..%d, no shapes %d..%d); %d keyed floors, "
                        + "%d of %d views lowered by the proof, %d with a finite most above the least",
                his[0], his[1], noKey[0], noKey[1], noShape[0], noShape[1], floors, lowered, views, proved));
    }

    /** His 10:41 AM 11-21 map (killer560s-mod-logs/mapcycler-1121-shot2.webp, limit 5): "it should have skipped this one
     *  since the 3 but then all that bottom left space is rooms leading up to blood". It was kept on a guess; with When
     *  Unsure gone it is not proven within 5, so it re-rolls. The least stays 3 (Blood may be the unseen cell below the
     *  Fairy), and no "on a guess" text, When Unsure or delay setting is left in the jar. */
    static void unproven(LogicCase c) throws Exception {
        Method m10 = Mod.cls("mapcycler.RushBounds").getMethod("bounds", int.class, int.class, int[].class, byte[].class,
                String[].class, String[].class, boolean[].class, int.class, int.class, int.class);
        int across = 6;
        String[] names = {"Entrance", "Withermancer", "Mural", "Ice Fill", "Ice Path", "Spider", "Skull", "New Trap", "Supertall",
                "Default", "Basement", "Drop", "Chains", "Small Waterfall", "Granite", "Red Green", "Fairy", "Rails"};
        String[] shapes = {"1x1", "L", "1x1", "1x1", "1x1", "L", "1x2", "1x1", "2x2", "1x1", "1x1", "1x1", "1x1", "1x1", "1x1",
                "1x1", "1x1", "2x2"};
        int[][] cells = {{4, 0}, {0, 0, 1, 0, 0, 1}, {2, 0}, {3, 0}, {5, 0}, {2, 1, 3, 1, 3, 2}, {1, 1, 1, 2}, {2, 2},
                {4, 1, 5, 1, 4, 2, 5, 2}, {1, 3}, {2, 3}, {3, 3}, {4, 3}, {5, 3}, {1, 4}, {2, 4}, {3, 4}, {4, 4, 5, 4, 4, 5, 5, 5}};
        int[] slots = new int[36];
        Arrays.fill(slots, UNSEEN);
        boolean[] complete = new boolean[names.length];
        Arrays.fill(complete, true);
        for (int r = 0; r < cells.length; r++) {
            for (int i = 0; i < cells[r].length; i += 2) {
                slots[cells[r][i + 1] * across + cells[r][i]] = r;
            }
        }
        byte[] gaps = new byte[72];
        for (int s = 0; s < 36; s++) {
            int t = s + 1;
            int u = s + across;
            gaps[2 * s] = s % across == across - 1 ? WALL : slots[s] >= 0 && slots[s] == slots[t] ? JOIN : UNKNOWN;
            gaps[2 * s + 1] = s / across == 5 ? WALL : slots[s] >= 0 && slots[s] == slots[u] ? JOIN : UNKNOWN;
        }
        gaps[2 * (0 * across + 4) + 1] = DOOR; // Entrance - Supertall
        gaps[2 * (1 * across + 3)] = KEY;      // Spider - Supertall (the marked door)
        gaps[2 * (2 * across + 3) + 1] = KEY;  // Spider - Drop (the marked door)
        int[] b = (int[]) m10.invoke(null, across, 6, slots, gaps, names, shapes, complete, 0, -1, 16);
        c.check("10:41 map: not proven within 5, so it re-rolls (never kept)", b[1] > 5, "got " + b[0] + ".." + b[1]);
        c.check("10:41 map: at least 3 rooms (Supertall, Spider, Drop; Blood may sit below the Fairy)", b[0] == 3,
                "got " + b[0] + ".." + b[1]);
        Class<?> cfg = Mod.cls("mapcycler.MapCyclerConfig");
        for (String gone : new String[]{"isRerollWhenUnsure", "setRerollWhenUnsure", "getDelayMs", "setDelayMs"}) {
            c.check("MapCyclerConfig." + gone + " is gone", Arrays.stream(cfg.getMethods()).noneMatch(m -> m.getName().equals(gone)), gone);
        }
        try (java.io.InputStream in = Mod.cls("mapcycler.MapCyclerFeature").getResourceAsStream("MapCyclerFeature.class")) {
            String bytes = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
            c.check("no \"on a guess\" text left in Map Cycler", !bytes.contains("on a guess") && !bytes.contains("Kept on"), "found");
        }
        c.note("10:41 map " + b[0] + ".." + (b[1] == Integer.MAX_VALUE ? "?" : b[1]));
    }

    /** RushBounds.bounds' 10 arguments for his 11-21 map (killer560s-mod-logs/mapcycler-1121-shot.webp; slots from the
     *  "Map cells" and door cells from the "snapshot retaken" lines of his 2:03:44 log). Gaps the log does not name stay
     *  unknown (less than the live map knew, so the proof rests on less). */
    private static Object[] hisMap(boolean witherDoors, boolean withShapes) {
        int across = 6;
        String[] names = {"Entrance", "Well", "Wizard", "Double Diamond", "Fairy", "Altar", "Mossy", "Catwalk", "Stairs",
                "Grass Ruin", "Tic Tac Toe", "Dragon", "Red Green", "Three Weirdos", "Redstone Key", "Big Red Flag", "Water Board"};
        String[] shapes = {"1x1", "L", "1x3", "1x1", "1x1", "L", "1x4", "1x3", "2x2", null, "1x1", "1x1", "1x1", "1x1", "1x1", "1x1", "1x1"};
        boolean[] complete = {true, true, true, true, true, false, false, false, false, false, true, true, true, true, true, true, true};
        int[][] cells = {{4, 5}, {3, 3, 3, 4, 4, 4}, {5, 3, 5, 4, 5, 5}, {4, 3}, {4, 2}, {3, 1, 4, 1}, {2, 1, 2, 2, 2, 3},
                {5, 1, 5, 2}, {1, 1}, {1, 2}, {3, 2}, {1, 3}, {2, 4}, {1, 4}, {1, 5}, {2, 5}, {3, 5}};
        int[] slots = new int[36];
        Arrays.fill(slots, UNSEEN);
        for (int r = 0; r < cells.length; r++) {
            for (int i = 0; i < cells[r].length; i += 2) {
                slots[cells[r][i + 1] * across + cells[r][i]] = r;
            }
        }
        byte[] gaps = new byte[72];
        for (int s = 0; s < 36; s++) {
            gaps[2 * s] = s % across == across - 1 ? WALL : UNKNOWN;
            gaps[2 * s + 1] = s / across == 5 ? WALL : UNKNOWN;
        }
        byte wither = witherDoors ? KEY : DOOR;
        // {x, z, 0 east / 1 south, kind}: the log's door and join cells.
        int[][] marks = {{4, 4, 1, DOOR}, {4, 4, 0, wither}, {4, 3, 0, wither}, {4, 2, 1, wither}, {4, 1, 1, wither},
                {2, 4, 0, DOOR}, {2, 4, 1, DOOR}, {3, 4, 1, DOOR}, {5, 2, 1, DOOR}, {2, 3, 0, DOOR}, {1, 4, 0, DOOR},
                {1, 5, 0, DOOR}, {3, 2, 0, DOOR}, {1, 3, 0, DOOR}, {0, 5, 0, DOOR}, {1, 2, 0, DOOR}, {1, 1, 0, DOOR},
                {3, 3, 1, JOIN}, {3, 4, 0, JOIN}, {5, 3, 1, JOIN}, {5, 4, 1, JOIN}, {3, 1, 0, JOIN}, {2, 1, 1, JOIN},
                {2, 2, 1, JOIN}, {5, 1, 1, JOIN}, {0, 1, 0, JOIN}, {0, 2, 0, JOIN}};
        for (int[] m : marks) {
            gaps[2 * (m[1] * across + m[0]) + m[2]] = (byte) m[3];
        }
        return new Object[]{across, 6, slots, gaps, names, withShapes ? shapes : null, complete, 0, -1, 4};
    }

    /** {@code f} with Blood moved to a one-cell room (as on every real floor) and the doors of the true path marked as
     *  wither doors at random (the blood door always); null when no one-cell room can be Blood. */
    private static Floor keyed(Floor f, Random rnd) {
        int n = f.across * f.down;
        int[] size = new int[f.rooms];
        for (int s = 0; s < n; s++) {
            if (f.slots[s] >= 0) {
                size[f.slots[s]]++;
            }
        }
        List<Integer> ones = new ArrayList<>();
        for (int r = 0; r < f.rooms; r++) {
            if (size[r] == 1 && r != f.entrance && r != f.fairy) {
                ones.add(r);
            }
        }
        if (ones.isEmpty()) {
            return null;
        }
        int blood = ones.get(rnd.nextInt(ones.size()));
        Floor g = new Floor(f.across, f.down, f.slots, f.gaps.clone(), f.rooms, f.entrance, blood, f.fairy);
        List<List<int[]>> adj = new ArrayList<>();
        for (int r = 0; r < g.rooms; r++) {
            adj.add(new ArrayList<>());
        }
        for (int s = 0; s < n; s++) {
            for (int d = 0; d < 2; d++) {
                int t = d == 0 ? (s % g.across + 1 < g.across ? s + 1 : -1) : (s / g.across + 1 < g.down ? s + g.across : -1);
                if (t >= 0 && g.gaps[2 * s + d] == DOOR) {
                    adj.get(g.slots[s]).add(new int[]{g.slots[t], 2 * s + d});
                    adj.get(g.slots[t]).add(new int[]{g.slots[s], 2 * s + d});
                }
            }
        }
        int[] via = new int[g.rooms];
        int[] from = new int[g.rooms];
        Arrays.fill(from, -2);
        from[g.entrance] = -1;
        ArrayDeque<Integer> q = new ArrayDeque<>(List.of(g.entrance));
        while (!q.isEmpty()) {
            int r = q.poll();
            for (int[] e : adj.get(r)) {
                if (from[e[0]] == -2) {
                    from[e[0]] = r;
                    via[e[0]] = e[1];
                    q.add(e[0]);
                }
            }
        }
        for (int r = blood; r != g.entrance; r = from[r]) {
            if (r == blood || rnd.nextDouble() < 0.6) {
                g.gaps[via[r]] = KEY;
            }
        }
        return g;
    }

    /** Each room's shape as the room database writes it ("1xN" for a line, "2x2", "L"), null for any other blob. */
    private static String[] shapes(Floor f) {
        String[] out = new String[f.rooms];
        for (int r = 0; r < f.rooms; r++) {
            int k = 0;
            int x0 = 99, x1 = -1, z0 = 99, z1 = -1;
            for (int s = 0; s < f.slots.length; s++) {
                if (f.slots[s] == r) {
                    k++;
                    x0 = Math.min(x0, s % f.across);
                    x1 = Math.max(x1, s % f.across);
                    z0 = Math.min(z0, s / f.across);
                    z1 = Math.max(z1, s / f.across);
                }
            }
            int w = x1 - x0 + 1;
            int h = z1 - z0 + 1;
            out[r] = (w == 1 || h == 1) && k == w * h ? "1x" + k : w == 2 && h == 2 ? (k == 4 ? "2x2" : k == 3 ? "L" : null) : null;
        }
        return out;
    }

    /** What the map shows with each slot hidden at {@code p}, each gap between two seen slots hidden at p/2, each name at
     *  p; a room is complete only when all its slots are seen. Returns the arguments of RushBounds.bounds. */
    private static Object[] hide(Floor f, double p, Random rnd) {
        int n = f.across * f.down;
        int[] slots = new int[n];
        boolean[] seenAll = new boolean[f.rooms];
        Arrays.fill(seenAll, true);
        for (int s = 0; s < n; s++) {
            boolean hidden = rnd.nextDouble() < p && f.slots[s] != f.entrance;
            slots[s] = hidden ? UNSEEN : f.slots[s];
            if (hidden && f.slots[s] >= 0) {
                seenAll[f.slots[s]] = false;
            }
        }
        byte[] gaps = new byte[2 * n];
        for (int i = 0; i < 2 * n; i++) {
            int s = i / 2;
            int x = s % f.across;
            int z = s / f.across;
            int t = i % 2 == 0 ? (x + 1 < f.across ? s + 1 : -1) : (z + 1 < f.down ? s + f.across : -1);
            if (t < 0) {
                gaps[i] = WALL;
            } else if (slots[s] == UNSEEN || slots[t] == UNSEEN || rnd.nextDouble() < p / 2) {
                gaps[i] = UNKNOWN;
            } else {
                gaps[i] = f.gaps[i];
            }
        }
        String[] names = new String[f.rooms];
        boolean[] complete = new boolean[f.rooms];
        for (int r = 0; r < f.rooms; r++) {
            names[r] = r == f.entrance || rnd.nextDouble() >= p ? "R" + r : null;
            complete[r] = seenAll[r] && rnd.nextDouble() < 0.8;
        }
        int blood = names[f.blood] == null ? -1 : f.blood;
        int fairy = f.fairy < 0 || names[f.fairy] == null ? -1 : f.fairy;
        return new Object[]{f.across, f.down, slots, gaps, names, complete, f.entrance, blood, fairy};
    }

    /** Rooms between the Entrance and Blood on the door tree: BFS over rooms, independent of RushBounds. */
    private static int truth(Floor f) {
        List<List<Integer>> adj = new ArrayList<>();
        for (int r = 0; r < f.rooms; r++) {
            adj.add(new ArrayList<>());
        }
        int n = f.across * f.down;
        for (int s = 0; s < n; s++) {
            int x = s % f.across;
            int z = s / f.across;
            for (int d = 0; d < 2; d++) {
                int t = d == 0 ? (x + 1 < f.across ? s + 1 : -1) : (z + 1 < f.down ? s + f.across : -1);
                if (t >= 0 && (f.gaps[2 * s + d] == DOOR || f.gaps[2 * s + d] == KEY)) {
                    adj.get(f.slots[s]).add(f.slots[t]);
                    adj.get(f.slots[t]).add(f.slots[s]);
                }
            }
        }
        int[] from = new int[f.rooms];
        Arrays.fill(from, -2);
        from[f.entrance] = -1;
        ArrayDeque<Integer> q = new ArrayDeque<>(List.of(f.entrance));
        while (!q.isEmpty()) {
            int r = q.poll();
            for (int o : adj.get(r)) {
                if (from[o] == -2) {
                    from[o] = r;
                    q.add(o);
                }
            }
        }
        if (from[f.blood] == -2) {
            throw new AssertionError("Blood not reachable on a test floor");
        }
        int between = 0;
        for (int r = from[f.blood]; r != f.entrance; r = from[r]) {
            between += r == f.fairy ? 0 : 1;
        }
        return between;
    }

    /** Rooms of 1-4 slots (lines, squares, L and other shapes), a few empty slots, a random spanning door tree. */
    private static Floor synthetic(Random rnd) {
        int across = 4 + rnd.nextInt(3);
        int down = 4 + rnd.nextInt(3);
        int n = across * down;
        int[] slots = new int[n];
        Arrays.fill(slots, UNSEEN);
        int rooms = 0;
        List<Integer> order = new ArrayList<>();
        for (int s = 0; s < n; s++) {
            order.add(s);
        }
        java.util.Collections.shuffle(order, rnd);
        int empties = rnd.nextInt(3);
        for (int s : order) {
            if (slots[s] != UNSEEN) {
                continue;
            }
            if (empties > 0 && rnd.nextInt(8) == 0) {
                slots[s] = EMPTY;
                empties--;
                continue;
            }
            int id = rooms++;
            slots[s] = id;
            int size = rnd.nextInt(10) < 6 ? 1 : 2 + rnd.nextInt(3);
            List<Integer> mine = new ArrayList<>(List.of(s));
            for (int k = 1; k < size; k++) {
                int from = mine.get(rnd.nextInt(mine.size()));
                int[] nb = neighbors(from, across, down);
                int t = nb[rnd.nextInt(nb.length)];
                if (slots[t] == UNSEEN) {
                    slots[t] = id;
                    mine.add(t);
                }
            }
        }
        byte[] gaps = new byte[2 * n];
        Arrays.fill(gaps, WALL);
        // Joins inside a room; candidate doors between different rooms.
        List<int[]> candidates = new ArrayList<>();
        for (int s = 0; s < n; s++) {
            int x = s % across;
            int z = s / across;
            for (int d = 0; d < 2; d++) {
                int t = d == 0 ? (x + 1 < across ? s + 1 : -1) : (z + 1 < down ? s + across : -1);
                if (t < 0 || slots[s] < 0 || slots[t] < 0) {
                    continue;
                }
                if (slots[s] == slots[t]) {
                    gaps[2 * s + d] = JOIN;
                } else {
                    candidates.add(new int[]{2 * s + d, slots[s], slots[t]});
                }
            }
        }
        java.util.Collections.shuffle(candidates, rnd);
        int[] parent = new int[rooms];
        for (int r = 0; r < rooms; r++) {
            parent[r] = r;
        }
        for (int[] e : candidates) {
            int a = find(parent, e[1]);
            int b = find(parent, e[2]);
            if (a != b) {
                parent[a] = b;
                gaps[e[0]] = DOOR;
            }
        }
        // Rooms cut off by the empty slots are dropped (made empty), keeping the Entrance's component.
        int entrance = slots[order.stream().filter(s -> slots[s] >= 0).findFirst().orElseThrow()];
        int root = find(parent, entrance);
        int[] remap = new int[rooms];
        int kept = 0;
        for (int r = 0; r < rooms; r++) {
            remap[r] = find(parent, r) == root ? kept++ : -1;
        }
        for (int s = 0; s < n; s++) {
            if (slots[s] >= 0) {
                slots[s] = remap[slots[s]] >= 0 ? remap[slots[s]] : EMPTY;
            }
        }
        for (int i = 0; i < 2 * n; i++) {
            int s = i / 2;
            int x = s % across;
            int z = s / across;
            int t = i % 2 == 0 ? (x + 1 < across ? s + 1 : -1) : (z + 1 < down ? s + across : -1);
            if (t < 0 || slots[s] < 0 || slots[t] < 0) {
                gaps[i] = WALL;
            }
        }
        entrance = remap[entrance];
        int blood = entrance;
        while (kept > 1 && blood == entrance) {
            blood = rnd.nextInt(kept);
        }
        if (kept < 3) {
            return synthetic(rnd);
        }
        int fairy = -1;
        while (rnd.nextInt(10) > 0 && (fairy < 0 || fairy == entrance || fairy == blood)) {
            fairy = rnd.nextInt(kept);
        }
        return new Floor(across, down, slots, gaps, kept, entrance, blood, fairy == entrance || fairy == blood ? -1 : fairy);
    }

    private static int find(int[] parent, int r) {
        while (parent[r] != r) {
            parent[r] = parent[parent[r]];
            r = parent[r];
        }
        return r;
    }

    private static int[] neighbors(int s, int across, int down) {
        int x = s % across;
        int z = s / across;
        List<Integer> out = new ArrayList<>();
        if (x + 1 < across) out.add(s + 1);
        if (x > 0) out.add(s - 1);
        if (z + 1 < down) out.add(s + across);
        if (z > 0) out.add(s - across);
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    /** F7 floors the sim lays out, read from the planned map code; null (with a skip) without a room library. */
    private static List<Floor> simFloors(LogicCase c) throws Exception {
        java.util.Map<?, ?> usable;
        try {
            usable = (java.util.Map<?, ?>) Mod.staticCall("roomsim.SimFloorGen", "usableRooms");
        } catch (AssertionError e) {
            c.skip("sim floors", "usableRooms threw: " + e.getMessage());
            return null;
        }
        if (usable == null || usable.size() < 10) {
            c.skip("sim floors", "no room library in this client");
            return null;
        }
        // plan() answers null until the room database is ready, and offline it never loads by itself
        if (dev.testkit.gametest.Scenario.copyRoomDatabase() > 0) {
            dev.testkit.gametest.Scenario.loadRoomDatabaseNow();
            for (int i = 0; i < 300 && !(Boolean) Mod.staticCall("roomdatabase.RoomDatabase", "isReady"); i++) {
                Mod.staticCall("roomdatabase.RoomDatabase", "ensureLoading");
                Thread.sleep(100);
            }
        }
        Object f7 = Mod.enumValue("roomsim.SimFloorGen$Floor", "F7");
        List<Floor> out = new ArrayList<>();
        long t0 = System.nanoTime();
        int noEnds = 0;
        for (int i = 0; i < 80 && (System.nanoTime() - t0) / 1e9 < 40; i++) {
            Object planned = Mod.staticCall("roomsim.SimFloorGen", "plan", f7, 3, 3 + i % 6);
            if (planned == null) {
                continue;
            }
            Object dec = Mod.call(planned, "decoded");
            String[] table = (String[]) Mod.call(dec, "nameTable");
            int[] cellRoom = (int[]) Mod.call(dec, "cellRoom");
            int[] cellDoor = (int[]) Mod.call(dec, "cellDoor");
            Floor fl = fromCells(table, cellRoom, cellDoor);
            if (fl == null) {
                noEnds++;
            } else {
                out.add(fl);
            }
        }
        c.note("sim floors planned: " + out.size() + " (" + noEnds + " without an Entrance and Blood by name) in "
                + (System.nanoTime() - t0) / 1_000_000 + " ms");
        c.check("sim floors: at least 20 planned", out.size() >= 20, "only " + out.size());
        return out;
    }

    /** Rooms between the Entrance and Blood of a map code's cells (6x6 floor), -1 when it names no Entrance or Blood. */
    public static int truthFromCells(String[] table, int[] cellRoom, int[] cellDoor) {
        Floor f = fromCells(table, cellRoom, cellDoor);
        return f == null ? -1 : truth(f);
    }

    /** 11x11 map cells -> 6x6 slots (slot (x,z) is cell (2x,2z); the cells between are joins or doors). */
    private static Floor fromCells(String[] table, int[] cellRoom, int[] cellDoor) {
        int grid = 11;
        int across = 6;
        int down = 6;
        int n = across * down;
        int[] slots = new int[n];
        byte[] gaps = new byte[2 * n];
        int entrance = -1;
        int blood = -1;
        int fairy = -1;
        for (int i = 0; i < table.length; i++) {
            if (table[i] != null && table[i].equalsIgnoreCase("Fairy")) fairy = i;
            if (table[i] != null && table[i].equalsIgnoreCase("Entrance")) entrance = i;
            if (table[i] != null && table[i].equalsIgnoreCase("Blood")) blood = i;
        }
        if (entrance < 0 || blood < 0) {
            return null;
        }
        for (int z = 0; z < down; z++) {
            for (int x = 0; x < across; x++) {
                int s = z * across + x;
                int idx = 2 * z * grid + 2 * x;
                slots[s] = cellRoom[idx] >= 0 ? cellRoom[idx] : EMPTY;
                gaps[2 * s] = x + 1 < across ? gap(cellRoom, cellDoor, idx + 1) : WALL;
                gaps[2 * s + 1] = z + 1 < down ? gap(cellRoom, cellDoor, idx + grid) : WALL;
            }
        }
        return new Floor(across, down, slots, gaps, table.length, entrance, blood, fairy);
    }

    private static byte gap(int[] cellRoom, int[] cellDoor, int idx) {
        return cellRoom[idx] >= 0 ? JOIN : cellDoor[idx] != 0 ? DOOR : WALL;
    }
}
