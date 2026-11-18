package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * melodynodelay-1118 (killer560, 2026-11-18: "an option for no first click delay in melody"): Auto Terminals' "No First
 * Click Delay In Melody". With First Click Delay at its 300 ms top (6 ticks) and a Melody board whose pane
 * already stands on row 1's target (interval 40): ON = the first click comes at least 3 ticks sooner than OFF, a Panes
 * terminal gets the same delay in both, and the switch survives save and load.
 */
final class MelodyNoDelay1118Cases {

    private static final String CONFIG = TerminalCases.CONFIG;
    private static final int DELAY_MS = 300; // First Click Delay tops out at 300 ms

    private MelodyNoDelay1118Cases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1118-menu-term-melody-nodelay", MelodyNoDelay1118Cases::run);
    }

    private static long firstClickTicks(Session c, MenuKit.Cfg cfg, TerminalCases.Spec spec, boolean on) throws Exception {
        cfg.set(CONFIG, "NoFirstClickDelayMelody", on);
        int solvedBefore = c.events("terminal.solved").size();
        int before = c.events("terminal.click").size();
        JsonObject t;
        if (spec.type().equals("MELODY")) {
            JsonObject args = new JsonObject();
            com.google.gson.JsonArray ts = new com.google.gson.JsonArray();
            for (int x : new int[]{1, 1, 1}) {
                ts.add(x);
            }
            args.addProperty("type", "MELODY");
            args.addProperty("seed", 1181);
            args.addProperty("interval", 40);
            args.addProperty("bandRows", 3);
            args.add("targets", ts);
            t = c.hx().call("menu.terminal", args).getAsJsonObject();
        } else {
            t = TerminalCases.open(c, spec);
        }
        MenuKit.awaitScreen(c, t.get("title").getAsString(), 100);
        c.waitUntil(spec.type() + " solved", mc -> c.events("terminal.solved").size() > solvedBefore, 1800);
        JsonObject solved = c.events("terminal.solved").get(c.events("terminal.solved").size() - 1);
        MenuKit.awaitNoScreen(c, 60);
        c.ctx().waitTicks(5);
        List<JsonObject> evs = c.events("terminal.click");
        c.check(evs.size() > before, spec.type() + " (switch " + on + "): no click happened");
        return evs.get(before).get("tick").getAsLong() - solved.get("openedTick").getAsLong();
    }

    static void run(Session c) throws Exception {
        MenuKit.reset(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: Auto Terminals is cheat-only");
            return;
        }
        List<String> problems = new ArrayList<>();
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CONFIG, "Enabled", true).set(CONFIG, "AutoTerminalsEnabled", true).set(CONFIG, "AutoMelodyEnabled", true)
                    .set(CONFIG, "AutoPanesEnabled", true).set(CONFIG, "HoverTerminalsEnabled", false)
                    .set(CONFIG, "HoldToClickEnabled", false).set(CONFIG, "AutoStreamSafe", false)
                    .set(CONFIG, "FirstClickDelayMs", DELAY_MS);
            TerminalCases.Spec melody = TerminalCases.SPECS.get(5);
            TerminalCases.Spec panes = TerminalCases.SPECS.get(0);
            firstClickTicks(c, cfg, panes, false); // cold first open is not measured
            long melodyOn = firstClickTicks(c, cfg, melody, true);
            long melodyOff = firstClickTicks(c, cfg, melody, false);
            long panesOn = firstClickTicks(c, cfg, panes, true);
            long panesOff = firstClickTicks(c, cfg, panes, false);
            c.note("first click ticks: melody on " + melodyOn + ", melody off " + melodyOff + ", panes on " + panesOn
                    + ", panes off " + panesOff + " (delay " + DELAY_MS / 50 + " ticks)");
            if (melodyOff - melodyOn < 3) {
                problems.add("Melody first click: switch ON " + melodyOn + " ticks, OFF " + melodyOff + " (expected ON 3+ sooner)");
            }
            if (Math.abs(panesOn - panesOff) > 2) {
                problems.add("Panes changed with the switch: on " + panesOn + ", off " + panesOff);
            }
            cfg.set(CONFIG, "NoFirstClickDelayMelody", true);
            c.onClient(mc -> {
                Mod.call(Mod.staticCall(CONFIG, "getInstance"), "save");
                Mod.set(CONFIG, "setNoFirstClickDelayMelody", false);
                Mod.staticCall(CONFIG, "load");
                return null;
            });
            boolean back = c.onClient(mc -> (Boolean) Mod.get(CONFIG, "isNoFirstClickDelayMelody"));
            if (!back) {
                problems.add("switch did not survive save and load");
            }
        } finally {
            MenuKit.reset(c);
        }
        c.check(problems.isEmpty(), "problems: " + problems);
    }
}
