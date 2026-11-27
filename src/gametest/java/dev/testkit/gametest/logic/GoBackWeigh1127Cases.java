package dev.testkit.gametest.logic;

import dev.testkit.gametest.mod.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * goback-weigh-1127 (killer560 11-27: "others will be a 9 secret room and itll miss a secret and redo the whole room
 * wasting a bunch of time"): Dungeons Autopilot weighs a go-back to a room whose route ran short like any other secret
 * room, priced by the trip plus only the nodes that missed and worth only the secrets still missing. Pure planner, made-up
 * floors on a line (the planner is in both jars, so these run on legit too):
 * <ul>
 *   <li>1127-logic-goback-expensive: a far go-back for one secret loses to a near fresh room that covers the need.</li>
 *   <li>1127-logic-goback-cheap: a near go-back for one node of a 9-node route wins over a far fresh room, and is priced by
 *       its one node, not the whole route.</li>
 *   <li>1127-logic-goback-enough: with the secret score already covered no go-back is taken (Party spares included), and a
 *       fixed order takes every fresh route before any go-back.</li>
 * </ul>
 */
final class GoBackWeigh1127Cases {
    private static final String P = "autosecret.AutopilotPlanner";
    private static final double PER_NODE = 0.45;

    private GoBackWeigh1127Cases() {
    }

    private static Object cand(String kind, String room, double gain, double seconds) throws Exception {
        return Mod.cls(P + "$Candidate").getConstructor(Mod.cls(P + "$Kind"), String.class, double.class, double.class,
                boolean.class, int.class).newInstance(Mod.enumValue(P + "$Kind", kind), room, gain, seconds, false, -1);
    }

    /** A fresh route as Autopilot prices it: trip + 1 s + every node. */
    private static Object fresh(String room, double gain, double travel, int nodes) throws Exception {
        return cand("SECRET", room, gain, travel + 1.0 + PER_NODE * nodes);
    }

    private static Object goBack(String room, double gain, double travel, int missedNodes) {
        return Mod.staticCall(P, "goBack", room, gain, travel, missedNodes, PER_NODE, false, -1);
    }

    private static Object travel(Map<String, Double> at) {
        return java.lang.reflect.Proxy.newProxyInstance(GoBackWeigh1127Cases.class.getClassLoader(),
                new Class<?>[]{Mod.cls(P + "$Travel")}, (proxy, m, args) -> switch (m.getName()) {
                    case "fromHere" -> at.get((String) args[0]);
                    case "between" -> Math.abs(at.get((String) args[0]) - at.get((String) args[1]));
                    default -> null;
                });
    }

    private static String tour(Object choice) {
        if (choice == null) {
            return "none";
        }
        List<String> out = new ArrayList<>();
        List<?> t = (List<?>) Mod.call(choice, "tour");
        if (t.isEmpty()) {
            t = List.of(Mod.call(choice, "pick"));
        }
        for (Object k : t) {
            out.add(Mod.call(k, "kind") + " " + Mod.call(k, "room") + (Boolean.TRUE.equals(Mod.call(k, "goBack")) ? "*" : ""));
        }
        return String.join(",", out);
    }

    private static Object fastest(boolean party, List<Object> cands, Map<String, Double> at, double need) {
        return Mod.staticCall(P, "chooseFastest", party, cands, travel(at), need, false, 50_000_000L);
    }

    static void expensive(LogicCase c) throws Exception {
        // Clear X at 1. Go-back Far at 20 for 1 secret (1 node); fresh Near at 2 with 1 secret (5 nodes). Need 1 secret.
        Map<String, Double> at = Map.of("X", 1.0, "Far", 20.0, "Near", 2.0);
        List<Object> cands = new ArrayList<>();
        cands.add(cand("CLEAR", "X", 5.0, 1 + 3));
        Object far = goBack("Far", 1.0, 20.0, 1);
        cands.add(far);
        cands.add(fresh("Near", 1.0, 2.0, 5));
        c.check("the go-back is marked as one", Boolean.TRUE.equals(Mod.call(far, "goBack")), String.valueOf(far));
        String t = tour(fastest(false, cands, at, 1.0));
        c.check("the cheaper fresh room covers the need", t.contains("SECRET Near"), t);
        c.check("the far one-secret go-back is not taken", !t.contains("SECRET Far"), t);
        // Party too: secrets first, the need covered by Near; Far's single secret is a spare it does not go back for.
        String p = tour(fastest(true, cands, at, 1.0));
        c.check("Party: the cheaper fresh room, no far go-back", p.contains("SECRET Near") && !p.contains("SECRET Far"), p);
    }

    static void cheap(LogicCase c) throws Exception {
        // Rails (9-node route, ran; 1 node missed, 1 secret) is next door at 1; fresh Far at 15 has 1 secret in 6 nodes.
        Map<String, Double> at = Map.of("X", 1.0, "Rails", 1.0, "Far", 15.0);
        List<Object> cands = new ArrayList<>();
        cands.add(cand("CLEAR", "X", 5.0, 1 + 3));
        Object rails = goBack("Rails", 1.0, 1.0, 1);
        cands.add(rails);
        cands.add(fresh("Far", 1.0, 15.0, 6));
        double secs = (Double) Mod.call(rails, "seconds");
        c.check("priced by its one missed node, not the 9-node route", Math.abs(secs - (1.0 + 1.0 + PER_NODE)) < 1e-9,
                "seconds " + secs);
        String t = tour(fastest(false, cands, at, 1.0));
        c.check("the cheap go-back is taken", t.contains("SECRET Rails*"), t);
        c.check("the far fresh room is skipped for it", !t.contains("SECRET Far"), t);
        // Needing both: both are taken, the go-back in its least-travel place (before the far room).
        String both = tour(fastest(false, cands, at, 2.0));
        c.check("needing two, both are taken, Rails first", both.indexOf("SECRET Rails*") >= 0
                && both.indexOf("SECRET Rails*") < both.indexOf("SECRET Far"), both);
    }

    static void enough(LogicCase c) throws Exception {
        Map<String, Double> at = Map.of("X", 1.0, "Rails", 1.0, "Near", 2.0);
        // Party, need covered: only spare secrets are worth anything (a quarter each). The go-back is never taken; the
        // fresh spare still is (Party keeps going for spares, as before).
        List<Object> cands = new ArrayList<>();
        cands.add(goBack("Rails", 0.25, 1.0, 1));
        cands.add(fresh("Near", 0.25, 2.0, 3));
        String t = tour(fastest(true, cands, at, 0.0));
        c.check("Party with the need met: no go-back", !t.contains("Rails"), t);
        c.check("Party with the need met: the fresh spare is still taken", t.contains("SECRET Near"), t);
        // Only a go-back left and nothing needed: nothing is chosen at all (it finishes instead).
        List<Object> only = new ArrayList<>();
        only.add(goBack("Rails", 0.25, 1.0, 1));
        String o = tour(fastest(true, only, at, 0.0));
        c.eq("only a go-back, need met", "none", o);
        // A fixed order (Secret Then Clear) takes every fresh route before any go-back.
        List<Object> ord = new ArrayList<>();
        ord.add(cand("CLEAR", "X", 5.0, 1 + 3));
        ord.add(goBack("Rails", 1.0, 1.0, 1));
        ord.add(fresh("Near", 1.0, 2.0, 3));
        Object ch = Mod.staticCall(P, "chooseOrdered", false, ord, travel(at), false, 50_000_000L);
        String s = tour(ch);
        c.check("fixed order: fresh routes first, no go-back yet", s.contains("SECRET Near") && !s.contains("Rails"), s);
        List<Object> ordLeft = new ArrayList<>();
        ordLeft.add(goBack("Rails", 1.0, 1.0, 1));
        String left = tour(Mod.staticCall(P, "chooseOrdered", false, ordLeft, travel(at), false, 50_000_000L));
        c.check("fixed order: the go-back once no fresh route is left", left.contains("SECRET Rails*"), left);
    }
}
