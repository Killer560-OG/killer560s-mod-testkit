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
 * between the Entrance and Blood (not counting either; the Fairy counts), computed here independently from the room tree.
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
    private static final int UNSEEN = -1;
    private static final int EMPTY = -2;

    private MapCycler1118Cases() {
    }

    /** A floor: slot -> room (or EMPTY), gaps (east, south per slot), names, entrance, blood. */
    private record Floor(int across, int down, int[] slots, byte[] gaps, int rooms, int entrance, int blood) {
    }

    static void bounds(LogicCase c) throws Exception {
        Method m = Mod.cls("mapcycler.RushBounds").getMethod("bounds", int.class, int.class, int[].class, byte[].class,
                String[].class, boolean[].class, int.class, int.class);
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
            int[] b = (int[]) m.invoke(null, f.across, f.down, f.slots, f.gaps, names, complete, f.entrance, f.blood);
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
        return new Object[]{f.across, f.down, slots, gaps, names, complete, f.entrance, blood};
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
                if (t >= 0 && f.gaps[2 * s + d] == DOOR) {
                    adj.get(f.slots[s]).add(f.slots[t]);
                    adj.get(f.slots[t]).add(f.slots[s]);
                }
            }
        }
        int[] dist = new int[f.rooms];
        Arrays.fill(dist, -1);
        dist[f.entrance] = 0;
        ArrayDeque<Integer> q = new ArrayDeque<>(List.of(f.entrance));
        while (!q.isEmpty()) {
            int r = q.poll();
            for (int o : adj.get(r)) {
                if (dist[o] < 0) {
                    dist[o] = dist[r] + 1;
                    q.add(o);
                }
            }
        }
        if (dist[f.blood] < 1) {
            throw new AssertionError("Blood not reachable on a test floor");
        }
        return dist[f.blood] - 1;
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
        if (kept < 2) {
            return synthetic(rnd);
        }
        return new Floor(across, down, slots, gaps, kept, entrance, blood);
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
        for (int i = 0; i < table.length; i++) {
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
        return new Floor(across, down, slots, gaps, table.length, entrance, blood);
    }

    private static byte gap(int[] cellRoom, int[] cellDoor, int idx) {
        return cellRoom[idx] >= 0 ? JOIN : cellDoor[idx] != 0 ? DOOR : WALL;
    }
}
