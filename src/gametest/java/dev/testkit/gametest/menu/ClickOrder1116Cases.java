package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.world.inventory.ContainerInput;

import java.util.Map;

/**
 * clickorder-1116 (killer560 2026-11-16: "click in order is completely bugged ... it wouldn't update the solver or click them
 * properly"; "the auto terms would click 1 properly then 2 wasn't right. it would keep clicking the wrong one"). The server
 * answers every taken click with a full update of the player's own inventory (window 0) before the board's slot change, so
 * two windows' packets interleave. termplus-1112's all-slots-loaded gate reset on the window-0 packet and read the board as
 * not loaded for 3 s after each click: the highlights froze on pane 1 and Auto Terminals clicked pane 1 again and again.
 * <ul>
 *   <li>1116-term-invsync-overlay: the solver alone; after a right click on pane 1 the first highlight moves on within 20
 *       ticks (it froze for 60).</li>
 *   <li>1116-term-invsync-solver-&lt;type&gt;: a player following the highlights solves it with 0 wrong clicks.</li>
 *   <li>1116-term-invsync-auto-&lt;type&gt;: Auto Terminals solves it with 0 wrong clicks.</li>
 * </ul>
 */
final class ClickOrder1116Cases {

    static final TerminalCases.Spec NUMBERS = new TerminalCases.Spec("NUMBERS",
            new Object[]{"seed", 23, "count", 10, "invSync", true}, "AutoNumbersEnabled");
    static final TerminalCases.Spec PANES = new TerminalCases.Spec("PANES",
            new Object[]{"seed", 41, "count", 8, "invSync", true}, "AutoPanesEnabled");
    static final TerminalCases.Spec SELECT = new TerminalCases.Spec("SELECT",
            new Object[]{"seed", 31, "color", "RED", "count", 8, "invSync", true}, "AutoSelectEnabled");

    private ClickOrder1116Cases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1116-term-invsync-overlay", ClickOrder1116Cases::overlay);
        for (TerminalCases.Spec spec : new TerminalCases.Spec[]{NUMBERS, PANES, SELECT}) {
            String type = spec.type().toLowerCase();
            MenuSuite.test(s, "1116-term-invsync-solver-" + type, c -> TerminalCases.solverCase(c, spec));
            MenuSuite.test(s, "1116-term-invsync-auto-" + type, c -> TerminalCases.autoCase(c, spec, false));
        }
    }

    private static void overlay(Session c) throws Exception {
        MenuKit.reset(c);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(TerminalCases.CONFIG, "Enabled", true).set(TerminalCases.CONFIG, "AutoTerminalsEnabled", false);
            JsonObject t = TerminalCases.open(c, NUMBERS);
            MenuKit.awaitScreen(c, t.get("title").getAsString(), 100);
            c.waitUntil("the solver to highlight pane 1", mc -> !TerminalCases.highlights().isEmpty(), 100);
            Object typeEnum = Mod.enumValue("terminals.TerminalType", "NUMBERS");
            Object target = c.onClient(mc -> Mod.staticCall(TerminalCases.FEATURE, "pickAutoClickTarget", typeEnum,
                    TerminalCases.highlights()));
            int first = (Integer) Mod.call(target, "slot");
            int sent = MenuKit.ev(c, "terminal.click").size();
            MenuKit.click(c, first, (Integer) Mod.call(target, "button"), (ContainerInput) Mod.call(target, "clickType"));
            c.waitUntil("the server to take the click on " + first, mc -> MenuKit.ev(c, "terminal.click").size() > sent, 60);
            JsonObject taken = MenuKit.ev(c, "terminal.click").get(sent);
            c.check(!taken.has("correct") || taken.get("correct").getAsBoolean(), "click 1 on slot " + first + " was wrong: " + taken);
            int moved = -1;
            for (int w = 0; w < 20; w++) {
                Map<Integer, Object> h = c.onClient(mc -> new java.util.HashMap<>(TerminalCases.highlights()));
                Object now = h.isEmpty() ? null : c.onClient(mc -> Mod.staticCall(TerminalCases.FEATURE, "pickAutoClickTarget",
                        typeEnum, h));
                if (now != null && (Integer) Mod.call(now, "slot") != first) {
                    moved = w;
                    break;
                }
                c.ctx().waitTicks(1);
            }
            c.note("pane 1 on slot " + first + " taken; the first highlight moved on after " + moved + " tick(s)");
            c.check(moved >= 0, "the overlay still pointed at pane 1 (slot " + first + ") 20 ticks after the server took it");
        } finally {
            MenuKit.reset(c);
        }
    }
}
