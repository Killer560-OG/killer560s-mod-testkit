package dev.testkit.gametest.logic;

import dev.testkit.gametest.mod.Mod;
import java.util.ArrayList;
import java.util.List;

/**
 * Mod branch maplabel-1117 (killer560, 2026-11-17, F7 map: "Museum is centered but to the right side"). The map showed
 * Museum's right tile column and the connectors to its unseen left column; the name was centered on the seen tiles alone,
 * one tile column right of the room's middle.
 * <ul>
 *   <li>1117-logic-maplabel-shape: every shape x offset x seen-subset, label inside the drawn shape; when the seen tiles
 *       and the connectors' far ends make up the whole room, the label is the exact center of the room.</li>
 *   <li>1117-logic-maplabel-museum: the exact Museum state of the screenshot (tile column 2 seen, connector column 1).</li>
 * </ul>
 */
final class MapLabel1117Cases {

    /** Shapes as tile cells (col, row). The last four are the L's. */
    private static final int[][][] SHAPES = {
        {{0, 0}}, {{0, 0}, {1, 0}}, {{0, 0}, {0, 1}}, {{0, 0}, {1, 0}, {2, 0}}, {{0, 0}, {0, 1}, {0, 2}},
        {{0, 0}, {1, 0}, {0, 1}, {1, 1}},
        {{0, 0}, {1, 0}, {0, 1}}, {{0, 0}, {1, 0}, {1, 1}}, {{0, 0}, {0, 1}, {1, 1}}, {{1, 0}, {0, 1}, {1, 1}},
    };

    private MapLabel1117Cases() {
    }

    private static boolean has(int[][] tiles, int c, int r) {
        for (int[] t : tiles) {
            if (t[0] == c && t[1] == r) {
                return true;
            }
        }
        return false;
    }

    /** Loads the floor for tiles {@code whole} at tile offset (ox, oz) with only {@code seen} (bit mask) tiles known, and returns the label. */
    private static float[] label(int[][] whole, int mask, int ox, int oz, List<int[]> drawn, boolean[] implied) {
        char[][] rows = new char[11][11];
        for (char[] row : rows) {
            java.util.Arrays.fill(row, '.');
        }
        boolean center2x2 = whole.length == 4;
        for (int i = 0; i < whole.length; i++) {
            int gx = 2 * (whole[i][0] + ox);
            int gz = 2 * (whole[i][1] + oz);
            if ((mask >> i & 1) != 0) {
                rows[gz][gx] = 'a';
                drawn.add(new int[]{gx, gz});
                implied[i] = true;
            }
        }
        for (int i = 0; i < whole.length; i++) {
            for (int j = i + 1; j < whole.length; j++) {
                int dc = Math.abs(whole[i][0] - whole[j][0]);
                int dr = Math.abs(whole[i][1] - whole[j][1]);
                if (dc + dr != 1 || ((mask >> i & 1) == 0 && (mask >> j & 1) == 0)) {
                    continue;
                }
                int cx = whole[i][0] + whole[j][0] + 2 * ox;
                int cz = whole[i][1] + whole[j][1] + 2 * oz;
                rows[cz][cx] = '=';
                drawn.add(new int[]{cx, cz});
                implied[i] = true;
                implied[j] = true;
            }
        }
        if (center2x2 && mask != 0) {
            rows[2 * oz + 1][2 * ox + 1] = '=';
            drawn.add(new int[]{2 * ox + 1, 2 * oz + 1});
        }
        String[] text = new String[11];
        for (int i = 0; i < 11; i++) {
            text[i] = new String(rows[i]);
        }
        Mod.staticCall("livemap.LiveMapFeature", "testLoadFloor", text, new String[]{"Shape|" + shapeName(whole) + "|NORMAL"});
        return (float[]) Mod.staticCall("livemap.LiveMapFeature", "testLabel", "Shape");
    }

    /** Whether the seen tiles form one group: touching each other, or all four of a 2x2 (its center connector joins them). */
    private static boolean joined(int[][] whole, int mask) {
        if (whole.length == 4) {
            return true;
        }
        int n = Integer.bitCount(mask);
        int start = Integer.numberOfTrailingZeros(mask);
        int reached = 1 << start;
        boolean grew = true;
        while (grew) {
            grew = false;
            for (int i = 0; i < whole.length; i++) {
                for (int j = 0; j < whole.length; j++) {
                    if ((mask >> i & 1) != 0 && (mask >> j & 1) != 0 && (reached >> i & 1) != 0 && (reached >> j & 1) == 0
                            && Math.abs(whole[i][0] - whole[j][0]) + Math.abs(whole[i][1] - whole[j][1]) == 1) {
                        reached |= 1 << j;
                        grew = true;
                    }
                }
            }
        }
        return Integer.bitCount(reached) == n;
    }

    private static boolean isL(int[][] whole) {
        return whole.length == 3 && !(whole[0][0] == whole[1][0] && whole[1][0] == whole[2][0])
                && !(whole[0][1] == whole[1][1] && whole[1][1] == whole[2][1]);
    }

    /** The database shape string whose tile cap fits the room (a 3-tile line is 1x3, a bent 3 is L). */
    private static String shapeName(int[][] whole) {
        return switch (whole.length) {
            case 1 -> "1x1";
            case 2 -> "1x2";
            case 3 -> isL(whole) ? "L" : "1x3";
            default -> "2x2";
        };
    }

    static void shape(LogicCase c) {
        int states = 0;
        int exact = 0;
        List<String> bad = new ArrayList<>();
        try {
            for (int[][] whole : SHAPES) {
                for (int ox = 0; ox <= 2; ox++) {
                    for (int mask = 1; mask < 1 << whole.length; mask++) {
                        if (!joined(whole, mask)) {
                            continue; // seen tiles with no connector or tile between them are separate groups, not one room
                        }
                        List<int[]> drawn = new ArrayList<>();
                        boolean[] implied = new boolean[whole.length];
                        float[] l = label(whole, mask, ox, 1, drawn, implied);
                        states++;
                        String id = whole.length + "t shape#" + java.util.Arrays.deepToString(whole) + " ox" + ox + " mask" + mask;
                        if (l == null) {
                            bad.add(id + ": no group");
                            continue;
                        }
                        boolean all = true;
                        float mnX = 99;
                        float mxX = -1;
                        float mnZ = 99;
                        float mxZ = -1;
                        for (int i = 0; i < whole.length; i++) {
                            all &= implied[i];
                            float tx = 2 * (whole[i][0] + ox);
                            float tz = 2 * (whole[i][1] + 1);
                            mnX = Math.min(mnX, tx);
                            mxX = Math.max(mxX, tx);
                            mnZ = Math.min(mnZ, tz);
                            mxZ = Math.max(mxZ, tz);
                        }
                        boolean inside = false;
                        for (int[] d : drawn) {
                            inside |= Math.abs(l[0] - d[0]) <= 0.5f && Math.abs(l[1] - d[1]) <= 0.5f;
                        }
                        if (!inside) {
                            bad.add(id + ": label " + l[0] + "," + l[1] + " not on a drawn cell");
                        }
                        boolean lWhole = isL(whole);
                        if (all && !lWhole) {
                            exact++;
                            if (Math.abs(l[0] - (mnX + mxX) / 2f) > 0.01 || Math.abs(l[1] - (mnZ + mxZ) / 2f) > 0.01) {
                                bad.add(id + ": label " + l[0] + "," + l[1] + " is not the room's center " + (mnX + mxX) / 2f + "," + (mnZ + mxZ) / 2f);
                            }
                        }
                    }
                }
            }
        } finally {
            Mod.staticCall("livemap.LiveMapFeature", "testLoadFloor", new String[0], new String[0]);
        }
        c.check(states + " states walked, " + exact + " with a known whole non-L room (acted)", states > 100 && exact > 20);
        c.check("every label sits on the drawn shape and centered where the whole room is known: " + bad.size() + " bad "
                + (bad.isEmpty() ? "" : String.join(" | ", bad.subList(0, Math.min(3, bad.size())))), bad.isEmpty());
    }

    static void museum(LogicCase c) {
        try {
            Mod.staticCall("livemap.LiveMapFeature", "testLoadFloor",
                    new String[]{"..a", "..=", "..a"}, new String[]{"Museum|2x2|NORMAL"});
            // the screenshot: Museum's right tile column (grid x 2) and the connectors at x 1 toward the unseen left column
            Mod.staticCall("livemap.LiveMapFeature", "testLoadFloor",
                    new String[]{".=a", ".==", ".=a"}, new String[]{"Museum|2x2|NORMAL"});
            float[] l = (float[]) Mod.staticCall("livemap.LiveMapFeature", "testLabel", "Museum");
            c.check("Museum has a group", l != null);
            c.near("label x is the middle of the 2x2 (grid x 1), not its right column", 1.0, l[0], 0.01);
            c.near("label z is the middle (grid z 1)", 1.0, l[1], 0.01);
        } finally {
            Mod.staticCall("livemap.LiveMapFeature", "testLoadFloor", new String[0], new String[0]);
        }
    }
}
