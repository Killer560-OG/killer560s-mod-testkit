package dev.testkit.gametest.menu;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.world.inventory.ContainerInput;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * F7 terminals against {@code menu.terminal} (server-side Hypixel click mechanics, see HxTerminals): the solver's
 * highlights checked against the server's own board, every type solved end-to-end by a "human" following the
 * highlights, then Auto Terminals solving each type by itself with its clicks/tick and the anticheat verdict.
 * On the legit jar the auto cases assert that NOTHING is clicked.
 */
final class TerminalCases {

    static final String FEATURE = "terminals.TerminalSolverFeature";
    static final String CONFIG = "terminals.TerminalSolverConfig";

    /** type, Hx args, auto toggle bean name */
    record Spec(String type, Object[] args, String auto) {
    }

    static final List<Spec> SPECS = List.of(
            new Spec("PANES", new Object[]{"seed", 11, "count", 7}, "AutoPanesEnabled"),
            new Spec("RUBIX", new Object[]{"seed", 12}, "AutoRubixEnabled"),
            new Spec("NUMBERS", new Object[]{"seed", 13}, "AutoNumbersEnabled"),
            new Spec("STARTS_WITH", new Object[]{"seed", 14, "letter", "S", "count", 6}, "AutoStartsWithEnabled"),
            new Spec("SELECT", new Object[]{"seed", 15, "color", "RED", "count", 6}, "AutoSelectEnabled"),
            new Spec("MELODY", new Object[]{"seed", 16, "interval", 10}, "AutoMelodyEnabled"));

    private TerminalCases() {
    }

    static void register(Session s) {
        int n = 201;
        for (Spec spec : SPECS) {
            String name = "2" + String.format("%02d", n++ - 200) + "-menu-term-solver-" + spec.type().toLowerCase().replace("_", "");
            MenuSuite.test(s, name, c -> solverCase(c, spec));
        }
        n = 211;
        for (Spec spec : SPECS) {
            String name = "2" + String.format("%02d", n++ - 200) + "-menu-term-auto-" + spec.type().toLowerCase().replace("_", "");
            MenuSuite.test(s, name, c -> autoCase(c, spec, false));
        }
        MenuSuite.test(s, "217-menu-term-auto-zero-delay", c -> autoCase(c, SPECS.get(2), true));
    }

    @SuppressWarnings("unchecked")
    static Map<Integer, Object> highlights() {
        return (Map<Integer, Object>) Mod.field(FEATURE, "currentHighlights");
    }

    static String currentType() {
        Object t = Mod.field(FEATURE, "currentType");
        return t == null ? null : t.toString();
    }

    static Set<Integer> ints(JsonElement arr) {
        Set<Integer> out = new LinkedHashSet<>();
        arr.getAsJsonArray().forEach(e -> out.add(e.getAsInt()));
        return out;
    }

    static JsonObject open(Session c, Spec spec) {
        List<Object> kv = new ArrayList<>(List.of("type", spec.type()));
        kv.addAll(List.of(spec.args()));
        return c.hx().call("menu.terminal", kv.toArray()).getAsJsonObject();
    }

    /** The solver alone (legit on both jars): highlights match the server's board, a human following them solves it. */
    static void solverCase(Session c, Spec spec) throws Exception {
        MenuKit.reset(c);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CONFIG, "Enabled", true).set(CONFIG, "AutoTerminalsEnabled", false);
            JsonObject t = open(c, spec);
            String title = t.get("title").getAsString();
            MenuKit.awaitScreen(c, title, 100);
            c.waitUntil("TerminalSolverFeature.currentType == " + spec.type(),
                    mc -> spec.type().equals(currentType()), 100);
            if (spec.type().equals("MELODY")) {
                // Melody has no highlights by design (TerminalSolverFeature.solve returns Map.of()); detection is the
                // solver's whole job here. Solving it is the auto case's.
                c.check(highlights().isEmpty(), "Melody should have no highlights, got " + highlights());
                c.note("Melody detected from '" + title + "'; no highlights by design");
                MenuKit.reset(c);
                return;
            }
            c.waitUntil("the solver to highlight something", mc -> !highlights().isEmpty(), 100);
            Set<Integer> remaining = ints(t.get("remaining"));
            Set<Integer> shown = c.onClient(mc -> new LinkedHashSet<>(highlights().keySet()));
            switch (spec.type()) {
                case "PANES", "STARTS_WITH", "SELECT" -> c.check(shown.equals(remaining),
                        "highlights " + shown + " != server's slots still to click " + remaining);
                case "NUMBERS" -> {
                    List<Integer> order = new ArrayList<>(remaining);
                    List<Integer> got = new ArrayList<>(shown);
                    c.check(got.size() >= 2 && got.get(0).equals(order.get(0)) && got.get(1).equals(order.get(1)),
                            "Numbers highlights " + got + " should start with the server's order " + order.subList(0, 2));
                }
                case "RUBIX" -> c.check(ints(t.get("grid")).containsAll(shown), "Rubix highlights outside the grid: " + shown);
                default -> {
                }
            }
            String first = "solver highlighted " + shown + " for server board needing " + remaining;
            // Follow the highlights like a player: one click, wait for the server, repeat.
            Object typeEnum = Mod.enumValue("terminals.TerminalType", spec.type());
            int clicks = 0;
            for (int i = 0; i < 80 && MenuKit.ev(c, "terminal.solved").isEmpty(); i++) {
                Object target = c.onClient(mc -> highlights().isEmpty() ? null
                        : Mod.staticCall(FEATURE, "pickAutoClickTarget", typeEnum, highlights()));
                if (target == null) {
                    c.ctx().waitTicks(2);
                    continue;
                }
                int slot = (Integer) Mod.call(target, "slot");
                int button = (Integer) Mod.call(target, "button");
                ContainerInput input = (ContainerInput) Mod.call(target, "clickType");
                MenuKit.click(c, slot, button, input);
                clicks++;
                c.ctx().waitTicks(4);
            }
            JsonObject solved = MenuKit.awaitEvent(c, "terminal.solved", 40);
            c.check(solved.get("wrong").getAsInt() == 0, "the server counted " + solved.get("wrong")
                    + " wrong click(s) following the solver: " + solved);
            MenuKit.awaitNoScreen(c, 40);
            c.note(first + "; following them solved it in " + clicks + " click(s), 0 wrong (server)");
        } finally {
            MenuKit.reset(c);
        }
    }

    /** Auto Terminals: solves it alone on the cheat jar (clicks/tick + Grim verdict); clicks nothing on legit. */
    static void autoCase(Session c, Spec spec, boolean zeroDelay) throws Exception {
        MenuKit.reset(c);
        long head = c.hx().head();
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CONFIG, "Enabled", true).set(CONFIG, "AutoTerminalsEnabled", true).set(CONFIG, spec.auto(), true);
            if (zeroDelay) {
                cfg.set(CONFIG, "AutoClickMinDelayMs", 0).set(CONFIG, "AutoClickMaxDelayMs", 0);
            }
            boolean on = c.onClient(mc -> (Boolean) Mod.get(CONFIG, "isAutoTerminalsEnabled"));
            JsonObject t = open(c, spec);
            String title = t.get("title").getAsString();
            MenuKit.awaitScreen(c, title, 100);
            if (!MenuKit.cheat()) {
                c.check(!on, "legit jar: isAutoTerminalsEnabled() returned true");
                c.waitUntil("the solver to track " + spec.type(), mc -> spec.type().equals(currentType()), 100);
                c.ctx().waitTicks(100);
                int clicks = c.events("container.click").size();
                c.check(clicks == 0, "legit jar sent " + clicks + " terminal click(s) with Auto Terminals switched on");
                c.note("legit jar: Auto Terminals set ON, getter reads " + on + ", 0 clicks in 100 ticks on an open "
                        + spec.type() + " terminal");
                return;
            }
            c.check(on, "cheat jar: isAutoTerminalsEnabled() is false after setAutoTerminalsEnabled(true)");
            JsonObject solved = MenuKit.awaitEvent(c, "terminal.solved", spec.type().equals("MELODY") ? 900 : 500);
            int wrong = solved.get("wrong").getAsInt();
            int maxPerTick = solved.get("maxClicksPerTick").getAsInt();
            long took = solved.get("solvedTick").getAsLong() - solved.get("openedTick").getAsLong();
            long fromFirst = solved.get("solvedTick").getAsLong() - solved.get("firstTick").getAsLong();
            MenuKit.awaitNoScreen(c, 40);
            c.ctx().waitTicks(10);
            int after = (int) c.events("container.click").stream()
                    .filter(e -> e.get("tick").getAsLong() > solved.get("solvedTick").getAsLong()).count();
            String cadence = MenuKit.cadence(c);
            c.note("Auto " + spec.type() + (zeroDelay ? " (min=max=0 ms)" : " (default 120-200 ms)") + ": solved in "
                    + took + " ticks from open (" + fromFirst + " from first click), " + solved.get("clicks")
                    + " clicks, " + wrong + " wrong; " + cadence + "; clicks after solve " + after);
            c.check(maxPerTick <= 1, "more than one terminal click in a server tick: " + maxPerTick);
            c.check(after == 0, after + " click(s) arrived after the server solved and closed the terminal");
            if (!spec.type().equals("RUBIX")) {
                c.check(wrong == 0, "the server rejected " + wrong + " auto click(s): " + solved);
            }
        } finally {
            MenuKit.reset(c);
            System.out.println("[menu] " + c.name() + " events since " + head + ": " + c.events("terminal.click").size()
                    + " terminal clicks");
        }
    }
}
