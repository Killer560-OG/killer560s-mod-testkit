package dev.testkit.gametest.menu;

import dev.testkit.gametest.hx.Session;

import java.util.ArrayList;
import java.util.List;

/**
 * killer560, 2026-10-08: "All terminal solvers and auto terminal is broken, probably because they changed all boss
 * dialogue." The six titles and the item rules are unchanged in every reference mod's current source (Odin, NoammAddons,
 * Skyblocker, Devonian, jcnlk's quoi), so what Hypixel changed was not found offline. The mod now reads every terminal
 * title through {@code TerminalType.normalizeTitle} (colour codes and resource-pack glyphs out). These cases open every
 * type with the title wrapped the way a resource-pack GUI title is - private-use glyphs and colour codes round the text -
 * and require the same results as {@link TerminalCases}' plain titles:
 * <ul>
 *   <li>554: the solver detects each type, its highlights match the server's board, a "human" following them solves it
 *       with 0 wrong clicks (Melody: detected, no highlights by design);</li>
 *   <li>555: Auto Terminals solves each type by itself, one click per server tick, 0 rejected (legit jar: 0 clicks).</li>
 * </ul>
 * The plain-title cases (201-219, 290, 291) stay the control that the boards themselves still solve.
 */
final class DungeonFixTerminalCases {

    /** A resource-pack style title: glyphs from the private-use area plus colour codes before, a glyph after. */
    static final String PREFIX = "\uE000\uF801§f§l";
    static final String SUFFIX = "§r\uF802";

    private DungeonFixTerminalCases() {
    }

    static List<TerminalCases.Spec> decorated() {
        List<TerminalCases.Spec> out = new ArrayList<>();
        List<TerminalCases.Spec> base = new ArrayList<>(TerminalCases.SPECS);
        base.set(2, TerminalCases.NUMBERS_10);
        base.set(5, TerminalCases.MELODY_3);
        for (TerminalCases.Spec spec : base) {
            List<Object> args = new ArrayList<>(List.of(spec.args()));
            args.addAll(List.of("titlePrefix", PREFIX, "titleSuffix", SUFFIX));
            out.add(new TerminalCases.Spec(spec.type(), args.toArray(), spec.auto()));
        }
        return out;
    }

    /** Six terminals in one case: each must judge only its own Hx events, as if it were a case of its own. */
    static void rebase(Session c) {
        try {
            java.lang.reflect.Field f = Session.class.getDeclaredField("caseEventHead");
            f.setAccessible(true);
            f.setLong(c, c.hx().head());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot rebase the case's events: " + e, e);
        }
    }

    static void register(Session s) {
        MenuSuite.test(s, "554-menu-term-decorated-solver", c -> {
            for (TerminalCases.Spec spec : decorated()) {
                c.note("---- " + spec.type() + " ----");
                rebase(c);
                TerminalCases.solverCase(c, spec);
            }
        });
        MenuSuite.test(s, "555-menu-term-decorated-auto", c -> {
            for (TerminalCases.Spec spec : decorated()) {
                c.note("---- " + spec.type() + " ----");
                rebase(c);
                TerminalCases.autoCase(c, spec, false);
            }
        });
    }
}
