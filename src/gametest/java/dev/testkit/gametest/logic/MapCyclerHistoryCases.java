package dev.testkit.gametest.logic;

import dev.testkit.gametest.mod.Mod;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 1121-logic-mapcycler-history (mod mapcycler-history-1121, Map Cycler): replays killer560's 454 real F7 floors, rebuilt
 * from his game logs (testkit-logic/mapcycler-f7-history.txt: each floor's true rooms before Blood and its map as it was
 * before the run started).
 * <ul>
 *   <li>The real-floor proof ({@code RushBounds.bounds} with puzzles and the trap off the path, the Entrance's one door):
 *       for limits 2-8 not one PROVEN keep is longer and not one PROVEN re-roll is within the limit, on every floor.</li>
 *   <li>It proves more maps within the limit than the plain proof the shipped cycler used (the 10-argument
 *       {@code bounds}).</li>
 *   <li>Keep Chance at its default ({@code RushOdds.percent} >= MapCyclerConfig.DEFAULT_KEEP_CHANCE) for limits 4-6:
 *       unproven maps kept and how many of them were longer, against the shipped cycler, which kept none. At limit 5 it
 *       must keep some, with at most 1 in 8 longer.</li>
 * </ul>
 * The odds table was counted from these same floors, so the wrong-keep numbers here are a little better than on new maps
 * (leave-one-out on his history: limit 5 at 90% kept 103 with 9 longer).
 */
public final class MapCyclerHistoryCases {

    private static final Set<String> OFF_PATH = Set.of("Boulder", "Creeper Beams", "Higher Blaze", "Ice Fill", "Ice Path",
            "Lower Blaze", "New Trap", "Old Trap", "Quiz", "Teleport Maze", "Three Weirdos", "Tic Tac Toe", "Water Board");

    private MapCyclerHistoryCases() {
    }

    private record Map(int truth, int across, int down, int entrance, int blood, int fairy, int[] slots, byte[] gaps,
                       String[] names, String[] shapes, boolean[] complete, boolean[] offPath, int keyDoors) {
    }

    static void history(LogicCase c) throws Exception {
        List<Map> maps = load();
        c.check("454 floors in the fixture", maps.size() == 454, "got " + maps.size());
        Class<?> rb = Mod.cls("mapcycler.RushBounds");
        Method real = rb.getMethod("bounds", int.class, int.class, int[].class, byte[].class, String[].class, String[].class,
                boolean[].class, boolean[].class, int.class, int.class, int.class);
        Method plain = rb.getMethod("bounds", int.class, int.class, int[].class, byte[].class, String[].class, String[].class,
                boolean[].class, int.class, int.class, int.class);
        Method odds = Mod.cls("mapcycler.RushOdds").getMethod("percent", String.class, int.class, int.class, int.class, int.class);
        int def = Mod.cls("mapcycler.MapCyclerConfig").getField("DEFAULT_KEEP_CHANCE").getInt(null);
        int[][] now = new int[maps.size()][];
        int[][] old = new int[maps.size()][];
        for (int i = 0; i < maps.size(); i++) {
            Map m = maps.get(i);
            now[i] = (int[]) real.invoke(null, m.across, m.down, m.slots, m.gaps, m.names, m.shapes, m.offPath, m.complete,
                    m.entrance, m.blood, m.fairy);
            old[i] = (int[]) plain.invoke(null, m.across, m.down, m.slots, m.gaps, m.names, m.shapes, m.complete,
                    m.entrance, m.blood, m.fairy);
        }
        int wrongProven = 0;
        int contains = 0;
        for (int i = 0; i < maps.size(); i++) {
            int t = maps.get(i).truth;
            contains += now[i][0] <= t && t <= now[i][1] ? 1 : 0;
            for (int n = 2; n <= 8; n++) {
                wrongProven += now[i][1] <= n && t > n || now[i][0] > n && t <= n ? 1 : 0;
            }
        }
        c.check("least <= truth <= most on every floor", contains == maps.size(), contains + " of " + maps.size());
        c.check("no wrong PROVEN keep or re-roll, limits 2-8", wrongProven == 0, wrongProven + " wrong");
        for (int n = 4; n <= 6; n++) {
            int provenNow = 0;
            int provenOld = 0;
            int unsure = 0;
            int unsureOk = 0;
            int kept = 0;
            int keptWrong = 0;
            for (int i = 0; i < maps.size(); i++) {
                Map m = maps.get(i);
                provenNow += now[i][1] <= n ? 1 : 0;
                provenOld += old[i][1] <= n ? 1 : 0;
                if (now[i][1] > n && now[i][0] <= n) {
                    unsure++;
                    unsureOk += m.truth <= n ? 1 : 0;
                    int pct = (int) odds.invoke(null, "F7", now[i][0], now[i][1], m.keyDoors, n);
                    if (pct >= def) {
                        kept++;
                        keptWrong += m.truth > n ? 1 : 0;
                    }
                }
            }
            c.note("limit " + n + ": proven keeps " + provenNow + " (shipped proof " + provenOld + "), unsure " + unsure + " ("
                    + unsureOk + " really within), Keep Chance " + def + "% keeps " + kept + " of them, " + keptWrong
                    + " longer; the shipped cycler kept none");
            c.check("limit " + n + ": the real-floor proof proves at least as many keeps", provenNow >= provenOld,
                    provenNow + " vs " + provenOld);
            if (n == 5) {
                c.check("limit 5: proves more keeps than the shipped proof", provenNow > provenOld, provenNow + " vs " + provenOld);
                c.check("limit 5: Keep Chance keeps unproven maps", kept > 0, "kept " + kept);
                c.check("limit 5: at most 1 in 8 of those kept is longer", keptWrong * 8 <= kept, keptWrong + " of " + kept);
            }
        }
        int other = (int) odds.invoke(null, "M7", 0, 20, 4, 5);
        c.check("no estimate off F7 (unproven maps re-roll as before)", other == -1, "M7 gave " + other);
    }

    private static List<Map> load() throws Exception {
        List<Map> out = new ArrayList<>();
        try (InputStream in = MapCyclerHistoryCases.class.getResourceAsStream("/testkit-logic/mapcycler-f7-history.txt");
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                if (line.startsWith("#") || line.isBlank()) {
                    continue;
                }
                String[] tp = line.split("#", 2);
                String[] p = tp[1].split("\\|", -1);
                String[] h = p[0].trim().split(" ");
                int[] slots = java.util.Arrays.stream(p[1].split(",")).mapToInt(Integer::parseInt).toArray();
                String[] gs = p[2].split(",");
                byte[] gaps = new byte[gs.length];
                int keyDoors = 0;
                for (int i = 0; i < gs.length; i++) {
                    gaps[i] = Byte.parseByte(gs[i]);
                    keyDoors += gaps[i] == 4 ? 1 : 0;
                }
                String[] names = p[3].split(";", -1);
                String[] shapes = p[4].split(";", -1);
                String[] cs = p[5].split(";", -1);
                boolean[] complete = new boolean[names.length];
                boolean[] offPath = new boolean[names.length];
                for (int i = 0; i < names.length; i++) {
                    names[i] = names[i].equals("null") ? null : names[i];
                    shapes[i] = shapes[i].equals("null") ? null : shapes[i];
                    complete[i] = cs[i].equals("1");
                    offPath[i] = names[i] != null && OFF_PATH.contains(names[i]);
                }
                out.add(new Map(Integer.parseInt(tp[0]), Integer.parseInt(h[0]), Integer.parseInt(h[1]), Integer.parseInt(h[2]),
                        Integer.parseInt(h[3]), Integer.parseInt(h[4]), slots, gaps, names, shapes, complete, offPath, keyDoors));
            }
        }
        return out;
    }
}
