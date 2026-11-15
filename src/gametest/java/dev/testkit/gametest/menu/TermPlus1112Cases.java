package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import java.util.List;

/**
 * termplus-1112 (mod branch termplus-1112): the Terminal Solver's all-slots-loaded gate, the 2.5 s stall net and the
 * Burst Size sweep up to 21.
 * <ul>
 *   <li>1112-term-parts: the server opens an empty window and describes it in two parts of slot packets, 1.5 s apart: the
 *       solver clicks nothing until the last part is in, then finishes.</li>
 *   <li>1112-term-stall-plain / -burst: the pacing is wedged (nothing can click) and the server drops a reply; the stall net
 *       drops the pacing after 2.5 s and the terminal still finishes. -melody: Melody still finishes with the net on.</li>
 *   <li>1112-term-burst-size-&lt;N&gt;[-sametick]: Burst Size N on a Select board with 24 targets (his swap, use, swing and
 *       container click in the burst tick for -sametick); the burst must be exactly N clicks in one client tick.</li>
 *   <li>1112-term-burst-size21-&lt;type&gt;: a size above the board puts the whole board in one tick.</li>
 * </ul>
 */
final class TermPlus1112Cases {

    static final String CONFIG = TerminalCases.CONFIG;
    static final String FEATURE = TerminalCases.FEATURE;
    static final int[] SIZES = {5, 8, 10, 13, 16, 21};
    static final TerminalCases.Spec SELECT24 = new TerminalCases.Spec("SELECT",
            new Object[]{"seed", 31, "color", "RED", "count", 24}, "AutoSelectEnabled");

    private TermPlus1112Cases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1112-term-parts", TermPlus1112Cases::parts);
        MenuSuite.test(s, "1112-term-stall-plain", c -> stall(c, false));
        MenuSuite.test(s, "1112-term-stall-burst", c -> stall(c, true));
        MenuSuite.test(s, "1112-term-stall-melody", c -> TerminalCases.autoCase(c, TerminalCases.SPECS.get(5), false));
        for (int size : SIZES) {
            MenuSuite.test(s, "1112-term-burst-size-" + size, c -> size(c, SELECT24, size, false));
            MenuSuite.test(s, "1112-term-burst-size-" + size + "-sametick", c -> size(c, SELECT24, size, true));
        }
        for (int i : new int[]{0, 1, 2}) {
            TerminalCases.Spec spec = TerminalCases.SPECS.get(i);
            MenuSuite.test(s, "1112-term-burst-size21-" + spec.type().toLowerCase(), c -> size(c, spec, 21, false));
        }
    }

    private static void size(Session c, TerminalCases.Spec spec, int size, boolean sameTick) throws Exception {
        TermBurst1111Cases.burst(c, spec, true, sameTick, size, sameTick);
        if (!MenuKit.cheat()) return;
        int last = c.onClient(mc -> ((Number) Mod.field(FEATURE, "lastBurstClicks")).intValue());
        if (spec == SELECT24) {
            c.check(last == size, "the burst was " + last + " clicks, not the Burst Size " + size + " on a 24-target board");
        }
        c.note("burst size " + size + (sameTick ? " same-tick" : "") + ": last burst " + last + " clicks");
    }

    private static void config(MenuKit.Cfg cfg, TerminalCases.Spec spec, boolean burst) {
        cfg.set(CONFIG, "Enabled", true).set(CONFIG, "AutoTerminalsEnabled", true).set(CONFIG, spec.auto(), true)
                .set(CONFIG, "AutoStreamSafe", false).set(CONFIG, "HoverTerminalsEnabled", false)
                .set(CONFIG, "QueueTerms", false).set(CONFIG, "BurstFinish", burst).set(CONFIG, "BurstSize", 5);
    }

    private static void parts(Session c) throws Exception {
        MenuKit.reset(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: the loaded gate is covered on the cheat jar (Auto Terminals is cheat-only)");
            return;
        }
        TerminalCases.Spec spec = new TerminalCases.Spec("PANES", new Object[]{"seed", 41, "count", 8, "parts", 2, "partGap", 30},
                "AutoPanesEnabled");
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            config(cfg, spec, false);
            cfg.set(CONFIG, "FirstClickDelayMs", 100);
            JsonObject t = TerminalCases.open(c, spec);
            MenuKit.awaitScreen(c, t.get("title").getAsString(), 100);
            JsonObject solved = MenuKit.awaitEvent(c, "terminal.solved", 300);
            List<JsonObject> parts = c.events("terminal.part");
            List<JsonObject> clicks = c.events("terminal.click");
            c.check(parts.size() == 2, "the board was not sent in two parts (" + parts.size() + " part events)");
            c.check(!clicks.isEmpty(), "no click acted");
            long partTick = parts.get(parts.size() - 1).get("tick").getAsLong();
            long firstClick = clicks.stream().mapToLong(e -> e.get("tick").getAsLong()).min().orElse(-1);
            c.note("parts: last part at server tick " + partTick + ", first click at " + firstClick + ", " + clicks.size()
                    + " clicks, solved in " + (solved.get("solvedTick").getAsLong() - solved.get("openedTick").getAsLong()) + " ticks");
            c.check(firstClick > partTick, "a click went out at tick " + firstClick + " before the board was complete (tick " + partTick + ")");
            c.check(solved.get("solved").getAsBoolean(), "the terminal was not solved");
        } finally {
            MenuKit.reset(c);
        }
    }

    private static void stall(Session c, boolean burst) throws Exception {
        MenuKit.reset(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: Auto Terminals is cheat-only");
            return;
        }
        TerminalCases.Spec spec = new TerminalCases.Spec("PANES", new Object[]{"seed", 51, "count", 7, "dropClicks", 1},
                "AutoPanesEnabled");
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            config(cfg, spec, burst);
            cfg.set(CONFIG, "FirstClickDelayMs", 1500);
            int resets0 = c.onClient(mc -> ((Number) Mod.field(FEATURE, "stallResets")).intValue());
            long click0 = c.onClient(mc -> ((Number) Mod.field(FEATURE, "lastClickProgressMs")).longValue());
            // The pacing is wedged from a START_CLIENT_TICK hook set BEFORE the open and re-set every tick until the stall net
            // acts, so neither the solver's own reset at the open nor a load-slow test thread lets a click out early (the
            // 1.5 s First Click Delay covers the frames between a reset and the next tick). Timed on the client's wall clock:
            // the server's ticks run slow under load, so a server-tick gap proves nothing about a 2.5 s timer.
            long[] seen = {0, 0}; // [0] ms a click went out while wedged, [1] ms from the board settling to the stall reset
            TermBurst1111Cases.hook();
            TermBurst1111Cases.perTick = () -> {
                if (((Number) Mod.field(FEATURE, "stallResets")).intValue() != resets0) {
                    if (seen[1] == 0) seen[1] = ((Number) Mod.field(FEATURE, "lastClickProgressMs")).longValue()
                            - ((Number) Mod.field(FEATURE, "stabilizedAtMs")).longValue();
                    return;
                }
                if (seen[0] == 0 && ((Number) Mod.field(FEATURE, "lastClickProgressMs")).longValue() != click0) {
                    seen[0] = System.currentTimeMillis();
                }
                Mod.setField(FEATURE, "nextAutoClickAllowedAtMs", System.currentTimeMillis() + 600_000L);
            };
            JsonObject t = TerminalCases.open(c, spec);
            MenuKit.awaitScreen(c, t.get("title").getAsString(), 100);
            JsonObject solved = MenuKit.awaitEvent(c, "terminal.solved", 400);
            TermBurst1111Cases.perTick = null;
            int resets = c.onClient(mc -> ((Number) Mod.field(FEATURE, "stallResets")).intValue()) - resets0;
            long wait = solved.get("firstTick").getAsLong() - solved.get("openedTick").getAsLong();
            c.note((burst ? "burst" : "plain") + " stall: first click " + wait + " server ticks after open, stall reset "
                    + seen[1] + " ms after the board settled, " + resets + " stall reset(s), solved with "
                    + solved.get("clicks") + " clicks");
            c.check(resets >= 1, "the stall net never acted");
            c.check(seen[0] == 0, "a click went out while the pacing was wedged, before the stall net acted");
            c.check(seen[1] >= 2400, "the stall net acted " + seen[1] + " ms after the board settled, before 2.5 s");
            c.check(solved.get("solved").getAsBoolean(), "the terminal was not solved after the stall");
        } finally {
            TermBurst1111Cases.perTick = null;
            MenuKit.reset(c);
        }
    }
}
