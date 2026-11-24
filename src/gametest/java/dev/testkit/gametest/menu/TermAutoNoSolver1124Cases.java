package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.WireCapture;

/**
 * autoterms-nosolver-1124: Auto Terminals runs on its own switches. For each of the six types, with the Terminal Solver
 * master OFF, or the master ON and only that type's solver switch OFF, auto completes the terminal (terminal.solved
 * from the server, container clicks on the wire) while the solver draws nothing ({@code solverShows()} false, Custom
 * GUI not active). With auto and the solver both OFF nothing is tracked and nothing is sent.
 */
final class TermAutoNoSolver1124Cases {
    private static final String CONFIG = TerminalCases.CONFIG;

    private TermAutoNoSolver1124Cases() {
    }

    static void register(Session s) {
        for (TerminalCases.Spec spec : TerminalCases.SPECS) {
            String t = spec.type().toLowerCase().replace("_", "");
            MenuSuite.test(s, "1124-menu-term-auto-nosolver-master-" + t, c -> auto(c, spec, true));
            MenuSuite.test(s, "1124-menu-term-auto-nosolver-type-" + t, c -> auto(c, spec, false));
        }
        MenuSuite.test(s, "1124-menu-term-auto-and-solver-off", TermAutoNoSolver1124Cases::bothOff);
    }

    private static String typeSwitch(TerminalCases.Spec spec) {
        String[] parts = spec.type().toLowerCase().split("_");
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            b.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return b + "Enabled";
    }

    private static int wireClicks() {
        return (int) WireCapture.snapshot().stream()
                .filter(w -> w.phase().equals("play") && (w.type() + " " + w.className()).contains("ContainerClick")).count();
    }

    static void auto(Session c, TerminalCases.Spec spec, boolean masterOff) throws Exception {
        MenuKit.reset(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: Auto Terminals is cheat-only");
            return;
        }
        WireCapture.describe = p -> p.getClass().getSimpleName();
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CONFIG, "Enabled", !masterOff).set(CONFIG, "CustomGuiEnabled", true).set(CONFIG, "AutoTerminalsEnabled", true)
                    .set(CONFIG, spec.auto(), true).set(CONFIG, typeSwitch(spec), false)
                    .set(CONFIG, "HoverTerminalsEnabled", false).set(CONFIG, "QueueTerms", false);
            WireCapture.start();
            JsonObject t = TerminalCases.open(c, spec);
            MenuKit.awaitScreen(c, t.get("title").getAsString(), 100);
            c.waitUntil("auto to track " + spec.type() + " with the solver off", mc -> spec.type().equals(TerminalCases.currentType()), 100);
            boolean shows = c.onClient(mc -> (Boolean) Mod.staticCall(TerminalCases.FEATURE, "solverShows"));
            boolean gui = c.onClient(mc -> (Boolean) Mod.staticCall(TerminalCases.FEATURE, "isCustomGuiActive"));
            c.check(!shows && !gui, "the solver draws (solverShows " + shows + ", customGui " + gui + ") with its switch off");
            JsonObject solved = MenuKit.awaitEvent(c, "terminal.solved", spec.type().equals("MELODY") ? 900 : 500);
            int wire = wireClicks();
            c.note(spec.type() + (masterOff ? " master off" : " type off") + ": solved, " + solved.get("clicks") + " clicks, "
                    + solved.get("wrong") + " wrong, " + wire + " ContainerClick on the wire");
            c.check(wire > 0, "no ContainerClick on the wire: auto never acted");
            c.check(solved.get("maxClicksPerTick").getAsInt() <= 1, "two clicks in one server tick");
            if (!spec.type().equals("RUBIX")) {
                c.check(solved.get("wrong").getAsInt() == 0, "the server rejected " + solved.get("wrong") + " click(s)");
            }
        } finally {
            WireCapture.stop();
            WireCapture.describe = null;
            MenuKit.reset(c);
        }
    }

    static void bothOff(Session c) throws Exception {
        MenuKit.reset(c);
        WireCapture.describe = p -> p.getClass().getSimpleName();
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            TerminalCases.Spec spec = TerminalCases.SPECS.get(4);
            cfg.set(CONFIG, "Enabled", false).set(CONFIG, "AutoTerminalsEnabled", false).set(CONFIG, typeSwitch(spec), false)
                    .set(CONFIG, "HoverTerminalsEnabled", false).set(CONFIG, "QueueTerms", false);
            WireCapture.start();
            JsonObject t = TerminalCases.open(c, spec);
            MenuKit.awaitScreen(c, t.get("title").getAsString(), 100);
            c.ctx().waitTicks(120);
            int wire = wireClicks();
            c.note("auto and solver off: tracked " + TerminalCases.currentType() + ", " + wire + " ContainerClick");
            c.check(TerminalCases.currentType() == null, "a terminal is tracked with every switch off: " + TerminalCases.currentType());
            c.check(wire == 0, wire + " ContainerClick sent with every switch off");
        } finally {
            WireCapture.stop();
            WireCapture.describe = null;
            MenuKit.reset(c);
        }
    }
}
