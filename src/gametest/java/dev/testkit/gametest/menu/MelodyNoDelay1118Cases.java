package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * melodynodelay-1118 (killer560, 2026-11-18: "an option for no first click delay in melody"): Auto Terminals' "No First
 * Click Delay In Melody". With First Click Delay at 1000 ms (20 ticks): ON = the first Melody click comes before the wait
 * would have passed, OFF = it waits, a Panes terminal waits in both, and the switch survives save and load.
 */
final class MelodyNoDelay1118Cases {

    private static final String CONFIG = TerminalCases.CONFIG;
    private static final int DELAY_MS = 1000;

    private MelodyNoDelay1118Cases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1118-menu-term-melody-nodelay", MelodyNoDelay1118Cases::run);
    }

    private static long firstClickTicks(Session c, MenuKit.Cfg cfg, TerminalCases.Spec spec, boolean on) throws Exception {
        cfg.set(CONFIG, "NoFirstClickDelayMelody", on);
        int solvedBefore = c.events("terminal.solved").size();
        int before = c.events("terminal.click").size();
        JsonObject t = TerminalCases.open(c, spec);
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
            if (melodyOn > 12) {
                problems.add("Melody with the switch ON still waited " + melodyOn + " ticks");
            }
            if (melodyOff < DELAY_MS / 50 - 1) {
                problems.add("Melody with the switch OFF clicked after only " + melodyOff + " ticks");
            }
            if (panesOn < DELAY_MS / 50 - 1 || panesOff < DELAY_MS / 50 - 1) {
                problems.add("Panes did not wait the delay: on " + panesOn + ", off " + panesOff);
            }
            cfg.set(CONFIG, "NoFirstClickDelayMelody", true);
            c.onClient(mc -> {
                Mod.call(Mod.staticCall(CONFIG, "getInstance"), "save");
                Mod.set(CONFIG, "NoFirstClickDelayMelody", false);
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
