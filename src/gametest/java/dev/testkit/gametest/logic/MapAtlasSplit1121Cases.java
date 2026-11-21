package dev.testkit.gametest.logic;

import dev.testkit.gametest.mod.Mod;
import java.util.ArrayList;
import java.util.List;

/**
 * Mod branch atlasmap-1121 (killer560, 2026-11-21, sim F7: "THE MAP JUST BROKE FOR ATLAS"). Atlas (2x2) split into two
 * rooms both named Atlas and the floor went from 21 to 22 rooms when the map item showed a second of its tiles as an
 * unopened (gray) square: the map draws an unopened tile with nothing in the gaps beside it, and the grouping read
 * those empty gaps as walls.
 * <ul>
 *   <li>1121-logic-map-atlas-split: his floor and the two map readings from his log (11:07:50 before / after), Atlas stays
 *       one room of 4 tiles / 9 cells and the floor 21 rooms; every shape (1x2, 1x3, 1x4, L, 2x2) whose tiles all show
 *       gray stays one room; controls: opened tiles with an empty gap still split (a real wall), and a new floor
 *       regroups.</li>
 * </ul>
 */
final class MapAtlasSplit1121Cases {

    private static final String LF = "livemap.LiveMapFeature";

    /** The world scan's floor (every cell of every room in his log), one letter per room. */
    private static final String[] FLOOR = {
        "a.c.e.g.m=m",
        "..=.....=..",
        "q.c.f=f.m.r",
        "..=.===...=",
        "j.c.f=f.o.r",
        "..........=",
        "b.d.h=h.i.r",
        "..........=",
        "t=t.u=u=u.r",
        "...........",
        "..k.l.n.p.s",
    };

    private static final String[] ROOMS = {
        "Lower Blaze|1x1|NORMAL", "Leaves|1x1|NORMAL", "Deathmite|1x3|NORMAL", "Blood|1x1|BLOOD", "New Trap|1x1|TRAP",
        "Atlas|2x2|NORMAL", "King Midas|1x1|NORMAL", "Pressure Plates|1x2|NORMAL", "Water Board|1x1|PUZZLE",
        "Admin|1x1|NORMAL", "Fairy|1x1|FAIRY", "Criss Cross|1x1|NORMAL", "Withermancer|L|NORMAL", "Raccoon|1x1|NORMAL",
        "Jumping Skulls|1x1|NORMAL", "Entrance|1x1|ENTRANCE", "Silver Sword|1x1|NORMAL", "Pit|1x4|NORMAL",
        "Tic Tac Toe|1x1|PUZZLE", "Lava Ravine|L|NORMAL", "Slime|1x3|NORMAL",
    };

    /** The map item at 11:07:50 before the split ("Map cells" line: k1 s4 = 'u', other k1 = 'r', k2 = 's'). */
    private static final String[] MAP_BEFORE = {
        "......r...u",
        "...........",
        "......u...r",
        "..........s",
        "......u.r.r",
        "..........s",
        "......u.u.r",
        "..........s",
        "....rsrsr.r",
        "...........",
        "......u.r.u",
    };

    /** The same second after: Deathmite opened, New Trap and Atlas's bottom-left tile (4,4) newly gray. */
    private static final String[] MAP_AFTER = {
        "..r.u.r...u",
        "..s........",
        "..r...u...r",
        "..s.......s",
        "..r.u.u.r.r",
        "..........s",
        "......u.u.r",
        "..........s",
        "....rsrsr.r",
        "...........",
        "......u.r.u",
    };

    private MapAtlasSplit1121Cases() {
    }

    private static String map(String[] rows) {
        return (String) Mod.staticCall(LF, "testLoadMap", (Object) rows);
    }

    private static int count(String groups, String name) {
        int n = 0;
        for (String g : groups.substring(groups.indexOf('|') + 1).split(" ")) {
            n += g.startsWith(name + ":") ? 1 : 0;
        }
        return n;
    }

    /** One room of shape {@code shape} at tiles {@code tiles} (grid cells), its connectors world-scanned, the map showing
     *  every tile as {@code tileChar} and nothing in the gaps. @return the grouping. */
    private static String single(String shape, int[][] tiles, char tileChar) {
        char[][] floor = new char[5][7];
        char[][] mapRows = new char[5][7];
        for (int z = 0; z < 5; z++) {
            java.util.Arrays.fill(floor[z], '.');
            java.util.Arrays.fill(mapRows[z], '.');
        }
        for (int[] t : tiles) {
            floor[t[1]][t[0]] = 'a';
            mapRows[t[1]][t[0]] = tileChar;
        }
        for (int[] t : tiles) {
            for (int[] u : tiles) {
                if (Math.abs(t[0] - u[0]) + Math.abs(t[1] - u[1]) == 2 && (t[0] == u[0] || t[1] == u[1])) {
                    floor[(t[1] + u[1]) / 2][(t[0] + u[0]) / 2] = '=';
                }
            }
        }
        if (tiles.length == 4 && "2x2".equals(shape)) {
            floor[1][1] = '=';
        }
        String[] f = new String[5];
        String[] m = new String[5];
        for (int z = 0; z < 5; z++) {
            f[z] = new String(floor[z]);
            m[z] = new String(mapRows[z]);
        }
        Mod.staticCall(LF, "testLoadFloor", f, new String[]{"Shape|" + shape + "|NORMAL"});
        return map(m);
    }

    static void atlasSplit(LogicCase c) {
        try {
            Mod.staticCall(LF, "testLoadFloor", FLOOR, ROOMS);
            String before = map(MAP_BEFORE);
            c.check("before: 21 rooms, Atlas one room of 4 tiles / 9 cells (" + before + ")",
                    before.startsWith("21|") && before.contains("Atlas:4/9") && count(before, "Atlas") == 1);
            String after = map(MAP_AFTER);
            c.check("after a second Atlas tile shows gray: still 21 rooms and Atlas 4/9, not two Atlas rooms (" + after + ")",
                    after.startsWith("21|") && after.contains("Atlas:4/9") && count(after, "Atlas") == 1);
            c.check("the map reading acted: Deathmite's opened separators keep it one 3-tile room (" + after + ")",
                    after.contains("Deathmite:3/5"));

            Object[][] shapes = {
                {"1x2", new int[][]{{0, 0}, {2, 0}}}, {"1x3", new int[][]{{0, 0}, {2, 0}, {4, 0}}},
                {"1x4", new int[][]{{0, 0}, {2, 0}, {4, 0}, {6, 0}}}, {"1x3", new int[][]{{0, 0}, {0, 2}, {0, 4}}},
                {"L", new int[][]{{0, 0}, {2, 0}, {0, 2}}}, {"L", new int[][]{{0, 0}, {2, 0}, {2, 2}}},
                {"L", new int[][]{{0, 0}, {0, 2}, {2, 2}}}, {"L", new int[][]{{2, 0}, {0, 2}, {2, 2}}},
                {"2x2", new int[][]{{0, 0}, {2, 0}, {0, 2}, {2, 2}}},
            };
            List<String> bad = new ArrayList<>();
            List<String> walls = new ArrayList<>();
            for (Object[] s : shapes) {
                String shape = (String) s[0];
                int[][] tiles = (int[][]) s[1];
                String gray = single(shape, tiles, 'u');
                if (!gray.equals("1|Shape:" + tiles.length + "/" + ("2x2".equals(shape) ? 9 : 2 * tiles.length - 1))) {
                    bad.add(shape + java.util.Arrays.deepToString(tiles) + " -> " + gray);
                }
                // control: the same room with every tile OPENED and nothing in the gaps is a real wall - it splits
                String open = single(shape, tiles, 'r');
                if (!open.startsWith(tiles.length + "|")) {
                    walls.add(shape + java.util.Arrays.deepToString(tiles) + " -> " + open);
                }
            }
            c.check("every shape whose tiles all show gray stays one room: " + bad.size() + " bad " + bad, bad.isEmpty());
            c.check("control: opened tiles with an empty gap between them are still separate rooms: " + walls.size()
                    + " bad " + walls, walls.isEmpty());

            // control: a different floor (a new generation) regroups from scratch
            Mod.staticCall(LF, "testLoadFloor", new String[]{"a=a.b", "....=", "c...b"},
                    new String[]{"Atlas|1x2|NORMAL", "Pit|1x2|NORMAL", "Fairy|1x1|FAIRY"});
            String fresh = map(new String[]{"rsr.u", ".....", "r...u"});
            c.check("control: a new floor regroups (3 rooms, no 2x2 Atlas left): " + fresh,
                    fresh.startsWith("3|") && fresh.contains("Atlas:2/3") && fresh.contains("Pit:2/3"));
        } finally {
            Mod.staticCall(LF, "testLoadMap", (Object) new String[0]);
            Mod.staticCall(LF, "testLoadFloor", new String[0], new String[0]);
        }
    }
}
