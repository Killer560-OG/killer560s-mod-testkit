package dev.testkit.gametest.logic;

import dev.testkit.gametest.mod.Mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dungeons Autopilot's planner pieces from autopilot-menu-1126 (killer560 2026-11-26), on made-up floors (pure, no world;
 * the planner is in both jars, so these run on legit too):
 * <ul>
 *   <li>1126-logic-autopilot-roomorder: Room Order on a fixed door graph. Edges In takes each branch from its far end back
 *       toward the Entrance, the deepest branch first, never jumping between branches; Smallest To Largest and Largest To
 *       Smallest go by room tiles; Fastest Time is the old least-travel order.</li>
 *   <li>1126-logic-autopilot-instaclear: Allow Insta Clearing. A room whose clear an insta clear saves 20 s, which the
 *       least-travel order would show (a neighbour visited first), is moved ahead of that neighbour and its saving counted
 *       in the estimate; without the saving the order is the plain one; a small saving that costs more travel than it
 *       saves is not taken.</li>
 *   <li>1126-logic-autopilot-skipplan: Skip Unnecessary Secrets' floor plan takes the cheapest secret rooms over the whole
 *       floor (a far detour priced at twice the trip from the nearest visited room), and a plan keeps the run out of the
 *       skipped rooms' secrets.</li>
 * </ul>
 */
final class Autopilot1126Cases {
    private static final String P = "autosecret.AutopilotPlanner";

    private Autopilot1126Cases() {
    }

    private static Object cand(String kind, String room, double gain, double seconds) throws Exception {
        return Mod.cls(P + "$Candidate").getConstructor(Mod.cls(P + "$Kind"), String.class, double.class, double.class,
                boolean.class, int.class).newInstance(Mod.enumValue(P + "$Kind", kind), room, gain, seconds, false, -1);
    }

    /** Rooms at points on a line: the trip from here is the point, between two the gap. */
    private static Object travel(Map<String, Double> at) {
        return java.lang.reflect.Proxy.newProxyInstance(Autopilot1126Cases.class.getClassLoader(),
                new Class<?>[]{Mod.cls(P + "$Travel")}, (proxy, m, args) -> switch (m.getName()) {
                    case "fromHere" -> at.get((String) args[0]);
                    case "between" -> Math.abs(at.get((String) args[0]) - at.get((String) args[1]));
                    default -> null;
                });
    }

    private static Object shape(String order, Map<String, Integer> size, Map<String, List<String>> nb, String root,
                                Map<String, Double> insta, Set<String> plan) throws Exception {
        return Mod.cls(P + "$Shape").getConstructors()[0].newInstance(
                Mod.enumValue("autosecret.AutoSecretConfig$SecretOrder", order), size, nb, root, insta, plan);
    }

    private static String tour(Object choice) {
        if (choice == null) {
            return "none";
        }
        List<String> out = new ArrayList<>();
        for (Object k : (List<?>) Mod.call(choice, "tour")) {
            out.add(Mod.call(k, "kind") + " " + Mod.call(k, "room"));
        }
        return String.join(",", out);
    }

    private static String rooms(Object choice) {
        List<String> out = new ArrayList<>();
        for (Object k : (List<?>) Mod.call(choice, "tour")) {
            out.add(String.valueOf(Mod.call(k, "room")));
        }
        return String.join(",", out);
    }

    /** Entrance E; branch A-B-C (C deepest) with leaf D off A; branch F-G. */
    private static Map<String, List<String>> graph() {
        Map<String, List<String>> g = new LinkedHashMap<>();
        g.put("E", List.of("A", "F"));
        g.put("A", List.of("E", "B", "D"));
        g.put("B", List.of("A", "C"));
        g.put("C", List.of("B"));
        g.put("D", List.of("A"));
        g.put("F", List.of("E", "G"));
        g.put("G", List.of("F"));
        return g;
    }

    static void roomOrder(LogicCase c) throws Exception {
        List<String> order = new ArrayList<>();
        for (Object o : (List<?>) Mod.staticCall(P, "edgesIn", graph(), "E")) {
            order.add(String.valueOf(o));
        }
        c.eq("Edges In order", "C,B,D,A,G,F,E", String.join(",", order));
        Map<String, Double> at = new HashMap<>();
        for (String r : List.of("A", "B", "C", "D", "F", "G")) {
            at.put(r, 1.0);   // all equally near: only the Room Order decides
        }
        at.put("E", 0.0);
        List<Object> cands = new ArrayList<>();
        for (String r : List.of("A", "B", "C", "D", "F", "G")) {
            cands.add(cand("CLEAR", r, 5.0, 4.0));
        }
        Map<String, Integer> size = Map.of("A", 4, "B", 1, "C", 2, "D", 1, "F", 3, "G", 2, "E", 1);
        Object tr = travel(at);
        c.eq("Edges In tour", "C,B,D,A,G,F", rooms(Mod.staticCall(P, "chooseOrdered", true, cands, tr, false, 50_000_000L,
                shape("EDGES_IN", size, graph(), "E", Map.of(), null))));
        c.eq("Smallest To Largest tour", "B,D,C,G,F,A", rooms(Mod.staticCall(P, "chooseOrdered", true, cands, tr, false,
                50_000_000L, shape("SMALLEST_TO_LARGEST", size, graph(), "E", Map.of(), null))));
        c.eq("Largest To Smallest tour", "A,F,C,G,B,D", rooms(Mod.staticCall(P, "chooseOrdered", true, cands, tr, false,
                50_000_000L, shape("LARGEST_TO_SMALLEST", size, graph(), "E", Map.of(), null))));
        // Fastest Time with the shape reads the same as the planner without one (the old least-travel order).
        Map<String, Double> line = Map.of("A", 1.0, "B", 2.0, "C", 3.0, "D", 10.0, "F", 4.0, "G", 5.0, "E", 0.0);
        Object lt = travel(line);
        c.eq("Fastest Time = least travel", tour(Mod.staticCall(P, "chooseOrdered", true, cands, lt, false, 50_000_000L)),
                tour(Mod.staticCall(P, "chooseOrdered", true, cands, lt, false, 50_000_000L,
                        shape("LEAST_ETHERWARPS", size, graph(), "E", Map.of(), null))));
        String labels = String.join(",", java.util.Arrays.stream(Mod.cls("autosecret.AutoSecretConfig$SecretOrder")
                .getEnumConstants()).map(o -> String.valueOf(Mod.call(o, "label"))).toList());
        c.eq("Room Order options", "Fastest Time,Largest To Smallest,Smallest To Largest,Edges In", labels);
    }

    static void instaClear(LogicCase c) throws Exception {
        // A line X(1) Y(2) Z(3): least travel is X, Y, Z. Z's only door is to Y, so visiting Y first shows Z.
        Map<String, Double> at = Map.of("X", 1.0, "Y", 2.0, "Z", 3.0);
        Map<String, List<String>> nb = Map.of("X", List.of("Y"), "Y", List.of("X", "Z"), "Z", List.of("Y"));
        List<Object> cands = new ArrayList<>();
        for (String r : List.of("X", "Y", "Z")) {
            cands.add(cand("CLEAR", r, 5.0, at.get(r) + 25.0));
        }
        Object tr = travel(at);
        Object plain = Mod.staticCall(P, "chooseOrdered", true, cands, tr, false, 50_000_000L,
                shape("LEAST_ETHERWARPS", Map.of(), nb, "X", Map.of(), null));
        c.eq("no insta: least travel", "X,Y,Z", rooms(plain));
        Object insta = Mod.staticCall(P, "chooseOrdered", true, cands, tr, false, 50_000_000L,
                shape("LEAST_ETHERWARPS", Map.of(), nb, "X", Map.of("Z", 20.0), null));
        c.eq("insta Z (20 s): Z before its neighbour Y", "X,Z,Y", rooms(insta));
        String why = String.valueOf(Mod.call(insta, "why"));
        c.check("the decide line names the insta clear", why.contains("insta clear Z (-20.0 s)"), why);
        double plainS = (Double) Mod.staticCall(P, "estimateSeconds", plain, tr);
        double instaS = (Double) Mod.staticCall(P, "estimateSeconds", insta, tr);
        c.note(String.format(java.util.Locale.US, "estimate plain %.1f s, with the insta clear %.1f s", plainS, instaS));
        double planS = travelPlusWork(insta);
        c.check("the plan with the insta clear is not slower", planS <= travelPlusWork(plain) + 1e-6,
                travelPlusWork(plain) + " vs " + planS);
        Object small = Mod.staticCall(P, "chooseOrdered", true, cands, tr, false, 50_000_000L,
                shape("LEAST_ETHERWARPS", Map.of(), nb, "X", Map.of("Z", 0.5), null));
        c.eq("insta Z (0.5 s) not worth 2 s more travel", "X,Y,Z", rooms(small));
        Object fixed = Mod.staticCall(P, "chooseOrdered", true, cands, tr, false, 50_000_000L,
                shape("SMALLEST_TO_LARGEST", Map.of("X", 1, "Y", 2, "Z", 3), nb, "X", Map.of("Z", 20.0), null));
        c.eq("a fixed Room Order is kept", "X,Y,Z", rooms(fixed));
        // At the step (his 11-26 F7: credited rooms shown on the map by a visit next door first): a pick next door to a
        // credited room gives way to that room's clear; a credit too small for the detour does not.
        Object pickY = Mod.cls(P + "$Choice").getConstructor(Mod.cls(P + "$Candidate"), String.class, List.class)
                .newInstance(cands.get(1), "test", List.of());
        Object sh = shape("LEAST_ETHERWARPS", Map.of(), nb, "X", Map.of("Z", 20.0), null);
        Object to = Mod.staticCall(P, "instaNextDoor", pickY, cands, tr, sh);
        c.eq("next door to Z: Z first", "CLEAR Z", Mod.call(Mod.call(to, "pick"), "kind") + " "
                + Mod.call(Mod.call(to, "pick"), "room"));
        c.check("the why names it", String.valueOf(Mod.call(to, "why")).contains("insta clear Z first"), String.valueOf(Mod.call(to, "why")));
        Object stay = Mod.staticCall(P, "instaNextDoor", pickY, cands, tr,
                shape("LEAST_ETHERWARPS", Map.of(), nb, "X", Map.of("Z", 0.5), null));
        // autopilot-instaclear-roomcost-1126 (killer560 2026-11-26: insta clearing is "100% of the time" faster): Z's clear
        // needs a visit anyway, so with no leg added beyond the trip to Y even a small credit goes first
        // (1126-logic-roomcost pins that a credit smaller than the added leg does not).
        c.check("a small credit with no added leg still goes first", stay != pickY
                && "Z".equals(Mod.call(Mod.call(stay, "pick"), "room")), String.valueOf(Mod.call(stay, "why")));
    }

    /** "all in N s" from the why line (travel plus work, insta savings off). */
    private static double travelPlusWork(Object choice) {
        String why = String.valueOf(Mod.call(choice, "why"));
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("all in ([0-9.]+) s").matcher(why);
        return m.find() ? Double.parseDouble(m.group(1)) : Double.NaN;
    }

    static void skipPlan(LogicCase c) throws Exception {
        // Clears in A(1), B(2), C(3); secret rooms A (3 secrets, 1 s route), C (2, 1 s), N (3, 2 s route, 9 from C: a
        // detour of 18 s). Need 5: A + C cover it; N is skipped even though, standing at 3, it is no farther than C was.
        Map<String, Double> at = Map.of("A", 1.0, "B", 2.0, "C", 3.0, "N", 12.0);
        Object tr = travel(at);
        List<Object> cands = new ArrayList<>();
        for (String r : List.of("A", "B", "C")) {
            cands.add(cand("CLEAR", r, 5.0, at.get(r) + 3));
        }
        cands.add(cand("SECRET", "A", 3.0, 1 + 1));
        cands.add(cand("SECRET", "C", 2.0, 3 + 1));
        cands.add(cand("SECRET", "N", 3.0, 12 + 2));
        List<String> plan = new ArrayList<>();
        for (Object o : (List<?>) Mod.staticCall(P, "skipPlan", cands, tr, 5.0)) {
            plan.add(String.valueOf(o));
        }
        c.eq("plan for 5 secret score", "A,C", String.join(",", plan));
        // With that plan, a tour needing 5 takes no secret in N.
        Object ch = Mod.staticCall(P, "chooseFastest", false, cands, tr, 5.0, false, 50_000_000L,
                shape("LEAST_ETHERWARPS", Map.of(), Map.of(), null, Map.of(), Set.copyOf(plan)));
        String t = tour(ch);
        c.check("no secret taken in a skipped room", !t.contains("SECRET N"), t);
        c.check("the planned secrets are taken", t.contains("SECRET A") && t.contains("SECRET C"), t);
        // A plan of only N (say the score moved) still covers the need first from N, then the cheapest others.
        Object only = Mod.staticCall(P, "chooseFastest", false, cands, tr, 3.0, false, 50_000_000L,
                shape("LEAST_ETHERWARPS", Map.of(), Map.of(), null, Map.of(), Set.of("N")));
        c.check("a planned room comes before cheaper unplanned ones", tour(only).contains("SECRET N")
                && !tour(only).contains("SECRET A"), tour(only));
    }
}
