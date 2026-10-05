package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.world.inventory.ContainerInput;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Experimentation Table against {@code menu.experiment} (HxExperiments): the solver's learned sequence vs the
 * server's, Auto E-Table playing each game alone (cheat) or not at all (legit), the whole navigate-play-claim loop
 * from the table's main menu, and the profit tracker reading the reward screen.
 */
final class ExperimentCases {

    static final String FEATURE = "experiments.ExperimentsFeature";
    static final String CONFIG = "experiments.ExperimentsConfig";
    static final String PROFIT = "experiments.ExperimentsProfitTracker";

    private ExperimentCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "220-menu-exp-solver-chronomatron", c -> solver(c, "CHRONOMATRON"));
        MenuSuite.test(s, "221-menu-exp-solver-ultrasequencer", c -> solver(c, "ULTRASEQUENCER"));
        MenuSuite.test(s, "222-menu-exp-solver-superpairs", ExperimentCases::superpairsSolver);
        MenuSuite.test(s, "223-menu-exp-auto-chronomatron", c -> auto(c, "CHRONOMATRON"));
        MenuSuite.test(s, "224-menu-exp-auto-ultrasequencer", c -> auto(c, "ULTRASEQUENCER"));
        MenuSuite.test(s, "225-menu-exp-auto-superpairs", c -> auto(c, "SUPERPAIRS"));
        MenuSuite.test(s, "226-menu-exp-auto-etable-loop", ExperimentCases::etableLoop);
        MenuSuite.test(s, "227-menu-exp-profit-tracker", ExperimentCases::profit);
    }

    static Object solverObj() {
        return Mod.field(FEATURE, "SOLVER");
    }

    static JsonObject state(Session c) {
        return c.hx().call("menu.experiment.state").getAsJsonObject();
    }

    static List<Integer> seq(JsonObject st) {
        List<Integer> out = new ArrayList<>();
        st.getAsJsonArray("sequence").forEach(e -> out.add(e.getAsInt()));
        return out;
    }

    /** Solver only (both jars): the human plays from the server's own sequence; the solver must have learned it. */
    static void solver(Session c, String game) throws Exception {
        MenuKit.reset(c);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CONFIG, "Enabled", true).set(CONFIG, "AutonomousMode", false);
            JsonObject st = c.hx().call("menu.experiment", "game", game, "seed", 21, "rounds", 3, "watch", 8, "hold", 6)
                    .getAsJsonObject();
            MenuKit.awaitScreen(c, st.get("title").getAsString(), 100);
            List<String> notes = new ArrayList<>();
            for (int round = 1; round <= 3; round++) {
                int r = round;
                c.waitUntil("round " + r + " to reach the player's turn", mc -> {
                    JsonObject now = state(c);
                    return now.get("round").getAsInt() == r && now.get("phase").getAsString().equals("TURN");
                }, 200);
                c.ctx().waitTicks(3);
                List<Integer> server = seq(state(c));
                Object learned = c.onClient(mc -> game.equals("CHRONOMATRON")
                        ? new ArrayList<>((List<?>) Mod.field(solverObj(), "chronomatron"))
                        : new java.util.TreeMap<>((Map<?, ?>) Mod.field(solverObj(), "ultrasequencer")));
                List<Integer> learnedList = new ArrayList<>();
                if (learned instanceof List<?> l) {
                    l.forEach(x -> learnedList.add((Integer) x));
                } else {
                    ((Map<?, ?>) learned).values().forEach(x -> learnedList.add((Integer) x));
                }
                c.check(learnedList.equals(server), "round " + r + ": solver learned " + learnedList
                        + " but the server's sequence is " + server);
                notes.add("r" + r + " " + server);
                for (int slot : server) {
                    MenuKit.click(c, slot, 0, ContainerInput.PICKUP);
                    c.ctx().waitTicks(3);
                }
            }
            JsonObject over = MenuKit.awaitEvent(c, "experiment.over", 60);
            c.check(over.get("reason").getAsString().equals("max rounds"), "game ended early: " + over);
            // The human's PICKUP predicts the note into the cursor; Click Protection (on by default, solver-only mode)
            // clears it with PICKUP at slot -999 (ExperimentsFeature.java:779-783). Anything else is an auto click.
            long clears = c.events("container.click").stream().filter(e -> e.get("slot").getAsInt() == -999).count();
            long auto = c.events("container.click").stream().filter(e -> e.get("slot").getAsInt() != -999).count()
                    - over.get("clicks").getAsInt();
            c.check(auto == 0, auto + " click(s) besides the human's with Auto E-Table off");
            c.note(game + " solver learned every round's sequence: " + String.join("; ", notes) + "; " + clears
                    + " cursor clear(s) at slot -999 by Click Protection, 0 auto clicks");
        } finally {
            MenuKit.reset(c);
        }
    }

    static void superpairsSolver(Session c) throws Exception {
        MenuKit.reset(c);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CONFIG, "Enabled", true).set(CONFIG, "AutonomousMode", false).set(CONFIG, "SuperpairsEnabled", true);
            JsonObject st = c.hx().call("menu.experiment", "game", "SUPERPAIRS", "seed", 23, "pairs", 3, "clicks", 20)
                    .getAsJsonObject();
            MenuKit.awaitScreen(c, st.get("title").getAsString(), 100);
            c.ctx().waitTicks(5);
            // Reveal every tile once, two at a time, like a player exploring; the solver should remember them all.
            for (int slot = 9; slot <= 14; slot++) {
                MenuKit.click(c, slot, 0, ContainerInput.PICKUP);
                c.ctx().waitTicks(4);
                if ((slot - 9) % 2 == 1) {
                    c.ctx().waitTicks(14);    // the server re-covers a non-matching pair after 10 ticks
                }
            }
            int known = c.onClient(mc -> ((Map<?, ?>) Mod.field(solverObj(), "knownSuperpairsCells")).size());
            String names = c.onClient(mc -> String.valueOf(Mod.field(solverObj(), "knownSuperpairsCells")));
            JsonObject now = state(c);
            int pairsFound = now.get("pairsFound").getAsInt();
            c.check(known >= 6 - 2 * pairsFound, "the solver remembers " + known + " revealed tile(s) of 6 ("
                    + pairsFound + " pair(s) matched by chance): " + names);
            c.note("Superpairs: revealed 6 tiles by hand, solver remembers " + known + "; " + pairsFound
                    + " pair(s) matched by the human's order");
        } finally {
            MenuKit.reset(c);
        }
    }

    static AutoCloseable autoConfig(Session c, MenuKit.Cfg cfg) {
        cfg.set(CONFIG, "Enabled", true).set(CONFIG, "AutonomousMode", true).set(CONFIG, "SuperpairsEnabled", true)
                .set(CONFIG, "DelayMs", 150).set(CONFIG, "FirstClickDelayMs", 300).set(CONFIG, "RandomDelayMaxMs", 0)
                .set(CONFIG, "StopStrategy", Mod.enumValue("experiments.ExperimentStopStrategy", "MAX_XP"))
                .set(CONFIG, "BlockInputEnabled", false);
        c.ctx().runOnClient(mc -> Mod.setField(FEATURE, "armed", true));
        return () -> c.ctx().runOnClient(mc -> Mod.setField(FEATURE, "armed", false));
    }

    /** Auto E-Table on one game: plays to the server's end with no wrong click on cheat; clicks nothing on legit. */
    static void auto(Session c, String game) throws Exception {
        MenuKit.reset(c);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c); AutoCloseable armed = autoConfig(c, cfg)) {
            boolean on = c.onClient(mc -> (Boolean) Mod.get(CONFIG, "isAutonomousMode"));
            JsonObject st = c.hx().call("menu.experiment", "game", game, "seed", 31, "rounds", 3, "watch", 8,
                    "hold", 6, "pairs", 3, "clicks", 30).getAsJsonObject();
            MenuKit.awaitScreen(c, st.get("title").getAsString(), 100);
            if (!MenuKit.cheat()) {
                c.check(!on, "legit jar: isAutonomousMode() is true");
                c.ctx().waitTicks(160);
                int clicks = c.events("container.click").size();
                c.check(clicks == 0, "legit jar sent " + clicks + " experiment click(s) with Auto E-Table on and armed");
                c.note("legit jar: Auto E-Table set ON + armed, getter " + on + ", 0 clicks in 160 ticks of " + game);
                return;
            }
            c.check(on, "cheat jar: isAutonomousMode() is false after setAutonomousMode(true)");
            JsonObject over = MenuKit.awaitEvent(c, "experiment.over", 1200);
            String reason = over.get("reason").getAsString();
            String expect = game.equals("SUPERPAIRS") ? "all pairs" : "max rounds";
            c.note("Auto " + game + ": " + reason + " after round " + over.get("round") + ", " + over.get("clicks")
                    + " clicks, " + over.get("wrong") + " wrong, " + over.get("clicksDuringWatch")
                    + " during the watch phase; " + MenuKit.cadence(c));
            c.check(reason.equals(expect), "the game ended with '" + reason + "', expected '" + expect + "': " + over);
            c.check(over.get("clicksDuringWatch").getAsInt() == 0, "clicked while the server was still showing the pattern");
            c.check(MenuKit.maxPerTick(MenuKit.clicksPerTick(c)) <= 1, "more than one click in a tick");
        } finally {
            MenuKit.reset(c);
        }
    }

    /** Auto E-Table end to end: main menu -> stakes -> game -> reward screen -> claim, all by the mod. */
    static void etableLoop(Session c) throws Exception {
        MenuKit.reset(c);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c); AutoCloseable armed = autoConfig(c, cfg)) {
            cfg.set(CONFIG, "ProfitTrackerEnabled", true);
            if (!MenuKit.cheat()) {
                c.note("legit jar: the navigate/claim loop is cheat-only (covered by 223-225's no-click check)");
                return;
            }
            c.ctx().runOnClient(mc -> Mod.staticCall(PROFIT, "reset"));
            JsonObject main = MenuKit.menu("menus.experimentation-table");
            MenuKit.show(c, main);
            JsonObject done = MenuKit.awaitEvent(c, "experiment.over", 1600);
            c.waitUntil("the claim click on the reward screen", mc -> c.events("menu.action").stream()
                    .anyMatch(e -> e.getAsJsonObject("action").has("chat")), 400);
            List<String> path = new ArrayList<>();
            c.events("menu.opened").forEach(e -> path.add(e.get("title").getAsString()));
            c.check(path.size() >= 4 && path.get(0).equals("Experimentation Table") && path.contains("Chronomatron ➜ Stakes")
                    && path.contains("Chronomatron (High)") && path.contains("Experiment Over"), "menus opened: " + path);
            c.waitUntil("the profit tracker to commit the claim", mc ->
                    ((Number) Mod.staticCall(PROFIT, "getTotalSessions")).intValue() >= 1, 200);
            long xp = c.onClient(mc -> ((Number) Mod.staticCall(PROFIT, "getTotalXp")).longValue());
            c.check(xp == 30_000, "profit tracker XP " + xp + ", the reward lore says +30k Enchanting Exp");
            c.note("Auto E-Table loop: " + String.join(" -> ", path) + "; game " + done.get("reason") + " in "
                    + done.get("clicks") + " clicks; claimed; profit tracker XP " + xp);
        } finally {
            MenuKit.reset(c);
        }
    }

    /** Profit tracker (both jars): a puzzle screen, the reward screen, the claim chat -> one session, +30k XP. */
    static void profit(Session c) throws Exception {
        MenuKit.reset(c);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CONFIG, "ProfitTrackerEnabled", true).set(CONFIG, "AutonomousMode", false);
            c.ctx().runOnClient(mc -> Mod.staticCall(PROFIT, "reset"));
            JsonObject puzzle = MenuKit.obj("{\"title\":\"Chronomatron (High)\",\"rows\":6,\"fill\":true}");
            MenuKit.show(c, puzzle);
            MenuKit.awaitScreen(c, "Chronomatron (High)", 100);
            c.ctx().waitTicks(10);
            JsonObject over = MenuKit.menu("menus.experiment-over-chronomatron");
            MenuKit.show(c, over);
            MenuKit.awaitScreen(c, "Experiment Over", 100);
            c.ctx().waitTicks(10);
            MenuKit.click(c, 22, 0, ContainerInput.PICKUP);
            c.waitUntil("one profit session", mc -> ((Number) Mod.staticCall(PROFIT, "getTotalSessions")).intValue() == 1, 100);
            long xp = c.onClient(mc -> ((Number) Mod.staticCall(PROFIT, "getTotalXp")).longValue());
            Object summary = c.onClient(mc -> Mod.staticCall(PROFIT, "getLastSummary"));
            c.check(xp == 30_000, "getTotalXp " + xp + " after a +30k Enchanting Exp claim");
            c.note("profit tracker: 1 session, XP " + xp + ", summary " + summary);
        } finally {
            MenuKit.reset(c);
            c.ctx().runOnClient(mc -> Mod.staticCall(PROFIT, "reset"));
        }
    }
}
