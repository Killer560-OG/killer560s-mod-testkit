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

        // H - P - Q (Quiz), X off P. X credited 30 s; going through X adds the leg X to Q beyond H to Q: 7 - 4 = 3 s.
        Map<String, Double> at = Map.of("H", 0.0, "P", 2.0, "Q", 4.0, "X", -3.0);
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

    /**
     * 1127-logic-instapick (instaclear-pick-1127, killer560 2026-11-27: "it isnt choosing good rooms yet to insta clear"):
     * his 11-27 F7s picked Slime (credited 4.3 s), then swapped it for Dome or Default, whose trip went through Slime, so
     * Slime was shown, passed and later killed by hand. H - S (big, picked) - D (small, behind S): going to D first shows
     * S, so D never goes first; a D worth more than S does (one of the two is all the map allows). Pure, both jars.
     */
    static void instaPick(LogicCase c) throws Exception {
        Map<String, Double> at = Map.of("H", 0.0, "S", 2.0, "D", 3.0);
        Map<String, List<String>> nb = Map.of("H", List.of("S"), "S", List.of("H", "D"), "D", List.of("S"));
        Object tr = java.lang.reflect.Proxy.newProxyInstance(InstaCost1126Cases.class.getClassLoader(),
                new Class<?>[]{Mod.cls(P + "$Travel")}, (proxy, m, args) -> switch (m.getName()) {
                    case "fromHere" -> at.get((String) args[0]);
                    case "between" -> Math.abs(at.get((String) args[0]) - at.get((String) args[1]));
                    default -> null;
                });
        List<Object> cands = new ArrayList<>();
        for (String r : List.of("S", "D")) {
            cands.add(Mod.cls(P + "$Candidate").getConstructor(Mod.cls(P + "$Kind"), String.class, double.class,
                    double.class, boolean.class, int.class).newInstance(Mod.enumValue(P + "$Kind", "CLEAR"), r, 3.9, 5.0,
                    false, -1));
        }
        Object pickS = Mod.cls(P + "$Choice").getConstructor(Mod.cls(P + "$Candidate"), String.class, List.class, List.class)
                .newInstance(cands.get(0), "test", cands, cands);
        Object big = shape(nb, Map.of("S", 4.3, "D", 2.4));
        Object way = Mod.staticCall(P, "instaBefore", "S", cands, tr, big, "H");
        c.check("the big room S (4.3 s) stays the pick: going to D (2.4 s) first goes through S", way == null,
                String.valueOf(way));
        Object kept = Mod.staticCall(P, "instaNextDoor", pickS, cands, tr, big, "H");
        c.check("instaNextDoor keeps CLEAR S", "S".equals(Mod.call(Mod.call(kept, "pick"), "room")), String.valueOf(kept));
        Object noFrom = Mod.staticCall(P, "instaBefore", "S", cands, tr, big, null);
        c.check("without the start room too (D next to S shows S)", noFrom == null, String.valueOf(noFrom));
        Object dBig = shape(nb, Map.of("S", 4.3, "D", 6.0));
        Object way2 = Mod.staticCall(P, "instaBefore", "S", cands, tr, dBig, "H");
        c.check("a D worth more than S still goes first", way2 != null && "D".equals(Mod.call(way2, "room")),
                String.valueOf(way2));
        Object to = Mod.staticCall(P, "instaNextDoor", pickS, cands, tr, dBig, "H");
        String why = String.valueOf(Mod.call(to, "why"));
        c.check("its net counts S's lost saving (6.0 - 4.3 = 1.7 s)", why.contains("saves 1.7 s net"), why);
        // instapick-fix-1127: H - Y - F (the pick), G off Y, B off G. The way to F shows G; G's own way goes through Y only,
        // so B (credited more, merely next to G) does not cost the swap. Counting it refused "Gravel first: the way to
        // Flags would show it" on 1126-sim-instacost-all and lost Gravel's insta clear.
        Map<String, Double> at2 = Map.of("H", 0.0, "Y", 1.0, "F", 2.0, "G", 2.0, "B", 3.0);
        Map<String, List<String>> nb2 = Map.of("H", List.of("Y"), "Y", List.of("H", "F", "G"), "F", List.of("Y"),
                "G", List.of("Y", "B"), "B", List.of("G"));
        Object tr2 = java.lang.reflect.Proxy.newProxyInstance(InstaCost1126Cases.class.getClassLoader(),
                new Class<?>[]{Mod.cls(P + "$Travel")}, (proxy, m, args) -> switch (m.getName()) {
                    case "fromHere" -> at2.get((String) args[0]);
                    case "between" -> Math.abs(at2.get((String) args[0]) - at2.get((String) args[1]));
                    default -> null;
                });
        List<Object> cands2 = new ArrayList<>();
        for (String r : List.of("G", "B")) {
            cands2.add(Mod.cls(P + "$Candidate").getConstructor(Mod.cls(P + "$Kind"), String.class, double.class,
                    double.class, boolean.class, int.class).newInstance(Mod.enumValue(P + "$Kind", "CLEAR"), r, 3.9, 5.0,
                    false, -1));
        }
        Object way3 = Mod.staticCall(P, "instaBefore", "F", cands2, tr2, shape(nb2, Map.of("G", 3.0, "B", 4.0)), "H");
        c.check("a bigger credited room merely next to the swap room does not block the swap", way3 != null
                && "G".equals(Mod.call(way3, "room")), String.valueOf(way3));
    }

    private static Object shape(Map<String, List<String>> nb, Map<String, Double> saving) throws Exception {
        return Mod.cls(P + "$Shape").getConstructors()[0].newInstance(
                Mod.enumValue("autosecret.AutoSecretConfig$SecretOrder", "LEAST_ETHERWARPS"), Map.of(), nb, "H", saving, null);
    }
}
