package dev.testkit.gametest.logic;

import dev.testkit.gametest.mod.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 1126-logic-roomcost (autopilot-instaclear-roomcost-1126, killer560 2026-11-26), pure, both jars:
 * <ul>
 *   <li>the bundled room clear table (autoclear.RoomClearTable, from where mobs spawn, not room size): Slime costs more
 *   than Cathedral (his anchors: "Slime is awful ... Cathedral is very simple"), a heavy 1x2 (Crypt) more than a light 2x2
 *   with one miniboss (Cathedral), every figure between 0.5 and 20 s, and the guess it shapes keeps its own scale;</li>
 *   <li>which rooms can be insta cleared ("it is impossible to insta-clear trap or any puzzles. You can insta-clear normal
 *   rooms and yellow room"): NORMAL, RARE and CHAMPION yes; PUZZLE, TRAP, FAIRY, BLOOD, ENTRANCE no;</li>
 *   <li>the way there: a trip from H through P to the Quiz Q shows X (next to P, not to Q), so a credited X goes first
 *   (AutopilotPlanner.instaBefore with where he stands); without the start room only Q's own neighbours count.</li>
 * </ul>
 */
final class InstaCost1126Cases {
    private static final String P = "autosecret.AutopilotPlanner";
    private static final String T = "autoclear.RoomClearTable";

    private InstaCost1126Cases() {
    }

    static void roomCost(LogicCase c) throws Exception {
        double slime = (Double) Mod.staticCall(T, "seconds", "Slime");
        double cathedral = (Double) Mod.staticCall(T, "seconds", "Cathedral");
        double crypt = (Double) Mod.staticCall(T, "seconds", "Crypt");
        c.note("table: Slime " + slime + " s, Cathedral " + cathedral + " s, Crypt (1x2) " + crypt + " s");
        c.check("Slime costs more than Cathedral", slime > cathedral, slime + " vs " + cathedral);
        c.check("a heavy 1x2 (Crypt) costs more than a light 2x2 (Cathedral)", crypt > cathedral, crypt + " vs " + cathedral);
        for (String r : List.of("Slime", "Cathedral", "Crypt", "Pit", "Market", "Atlas")) {
            double s = (Double) Mod.staticCall(T, "seconds", r);
            c.check(r + " is in the table between 0.5 and 20 s", s >= 0.5 && s <= 20, String.valueOf(s));
        }
        c.check("a room the table does not know keeps the guess",
                (Double) Mod.staticCall(T, "scaled", "No Such Room", 7.0) == 7.0, "");
        double sl = (Double) Mod.staticCall(T, "scaled", "Slime", 5.0);
        double ca = (Double) Mod.staticCall(T, "scaled", "Cathedral", 5.0);
        c.check("the shaped guess keeps the table's order", sl > ca, sl + " vs " + ca);

        for (String t : List.of("NORMAL", "RARE", "CHAMPION", "normal")) {
            c.check(t + " can be insta cleared", (Boolean) Mod.staticCall(P, "instaType", t), "");
        }
        for (String t : List.of("PUZZLE", "TRAP", "FAIRY", "BLOOD", "ENTRANCE", "")) {
            c.check(t + " can never be insta cleared", !(Boolean) Mod.staticCall(P, "instaType", t), "");
        }

        // H - P - Q (Quiz), X off P. X credited 30 s, its detour 4 s.
        Map<String, Double> at = Map.of("H", 0.0, "P", 2.0, "Q", 4.0, "X", 6.0);
        Map<String, List<String>> nb = Map.of("H", List.of("P"), "P", List.of("H", "Q", "X"), "Q", List.of("P"),
                "X", List.of("P"));
        Object tr = java.lang.reflect.Proxy.newProxyInstance(InstaCost1126Cases.class.getClassLoader(),
                new Class<?>[]{Mod.cls(P + "$Travel")}, (proxy, m, args) -> switch (m.getName()) {
                    case "fromHere" -> at.get((String) args[0]);
                    case "between" -> Math.abs(at.get((String) args[0]) - at.get((String) args[1]));
                    default -> null;
                });
        List<Object> cands = new ArrayList<>();
        cands.add(Mod.cls(P + "$Candidate").getConstructor(Mod.cls(P + "$Kind"), String.class, double.class, double.class,
                boolean.class, int.class).newInstance(Mod.enumValue(P + "$Kind", "CLEAR"), "X", 5.0, 33.0, false, -1));
        Object sh = Mod.cls(P + "$Shape").getConstructors()[0].newInstance(
                Mod.enumValue("autosecret.AutoSecretConfig$SecretOrder", "LEAST_ETHERWARPS"), Map.of(), nb, "H",
                Map.of("X", 30.0), null);
        Object way = Mod.staticCall(P, "instaBefore", "Q", cands, tr, sh, "H");
        c.check("on the way to the Quiz through P, X (next to P) goes first", way != null
                && "X".equals(Mod.call(way, "room")), String.valueOf(way));
        Object noFrom = Mod.staticCall(P, "instaBefore", "Q", cands, tr, sh, null);
        c.check("without the start room only the Quiz's own neighbours count", noFrom == null, String.valueOf(noFrom));
        Object small = Mod.cls(P + "$Shape").getConstructors()[0].newInstance(
                Mod.enumValue("autosecret.AutoSecretConfig$SecretOrder", "LEAST_ETHERWARPS"), Map.of(), nb, "H",
                Map.of("X", 1.0), null);
        c.check("a credit smaller than the detour is not taken",
                Mod.staticCall(P, "instaBefore", "Q", cands, tr, small, "H") == null, "");
    }
}
