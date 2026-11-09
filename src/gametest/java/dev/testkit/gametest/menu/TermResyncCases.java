package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;

import java.util.List;

/**
 * 298/299: the server's state wins over the client's prediction (killer560, 2026-11-03: "it thinks it clicked everything
 * yet it didnt finish the terminal"; the custom GUI was empty while the terminal was still open). Hx drops a click with
 * no answer at all ({@code dropClicks}): a Rubix PICKUP leaves the slot predicted empty, the other types leave the slot
 * unchanged. The slot must be shown again within a few ticks and the terminal must still be completable, by hand and by
 * Auto Terminals. Every case asserts the drop really happened (terminal.dropped events) and, for Rubix, that the
 * prediction really emptied the slot.
 */
final class TermResyncCases {

    private TermResyncCases() {
    }

    static void register(Session s) {
        for (TerminalCases.Spec spec : TerminalCases.SPECS) {
            if (spec.type().equals("MELODY")) {
                continue;
            }
            String t = spec.type().toLowerCase().replace("_", "");
            MenuSuite.test(s, "298-menu-term-resync-" + t, c -> manual(c, spec));
            MenuSuite.test(s, "299-menu-term-resync-auto-" + t, c -> auto(c, spec));
            MenuSuite.test(s, "1108-term-lastpane-auto-" + t, c -> lastPane(c, spec, false, "dropFinal", true));
            MenuSuite.test(s, "1108-term-lastpane-stream-" + t, c -> lastPane(c, spec, true, "dropFinal", true));
            MenuSuite.test(s, "1108-term-lastpane-reopen-" + t, c -> lastPane(c, spec, false, "dropFinal", true, "dropReopen", true));
            // termlast-1108: the final click dropped three times running (his 11-06 Rubix log: slot 32 restored three
            // times, about a second apart, never answered), and the final click answered and then taken back.
            MenuSuite.test(s, "1108-term-lastpane-thrice-" + t, c -> lastPane(c, spec, false, "dropFinal", true, "dropFinalTimes", 3));
            MenuSuite.test(s, "1108-term-lastpane-revert-" + t, c -> lastPane(c, spec, false, "revertFinal", true));
            MenuSuite.test(s, "1108-term-lastpane-revert-stream-" + t, c -> lastPane(c, spec, true, "revertFinal", true));
        }
    }

    private static boolean slotEmpty(Session c, int slot) {
        return c.onClient(mc -> !(McCompat.screen(mc) instanceof AbstractContainerScreen<?> sc)
                || slot >= sc.getMenu().slots.size() || sc.getMenu().slots.get(slot).getItem().isEmpty());
    }

    private static int serverEvents(Session c) {
        return c.events("terminal.click").size() + c.events("terminal.dropped").size();
    }

    /** One click through the mod's own shared click path (the one Auto, Hover and the Custom GUI use). */
    private static void send(Session c, int slot, int button, ContainerInput input) {
        c.onClient(mc -> Mod.staticCall(TerminalCases.FEATURE, "sendTerminalClick", McCompat.screen(mc), slot, button, input));
    }

    private static void manual(Session c, TerminalCases.Spec spec) throws Exception {
        MenuKit.reset(c);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(TerminalCases.CONFIG, "Enabled", true).set(TerminalCases.CONFIG, "AutoTerminalsEnabled", false);
            List<Object> kv = new java.util.ArrayList<>(List.of("type", spec.type()));
            kv.addAll(List.of(spec.args()));
            kv.addAll(List.of("dropClicks", 2));
            JsonObject t = c.hx().call("menu.terminal", kv.toArray()).getAsJsonObject();
            MenuKit.awaitScreen(c, t.get("title").getAsString(), 100);
            c.waitUntil("the solver to highlight something", mc -> !TerminalCases.highlights().isEmpty(), 100);
            c.ctx().waitTicks(20);
            Object typeEnum = Mod.enumValue("terminals.TerminalType", spec.type());
            boolean rubix = spec.type().equals("RUBIX");

            // 1. A click the server drops: the slot must come back (Rubix) / never leave (the rest).
            Object target = c.onClient(mc -> Mod.staticCall(TerminalCases.FEATURE, "pickAutoClickTarget", typeEnum, TerminalCases.highlights()));
            int slot = (Integer) Mod.call(target, "slot");
            int button = (Integer) Mod.call(target, "button");
            ContainerInput input = (ContainerInput) Mod.call(target, "clickType");
            int sent = serverEvents(c);
            send(c, slot, button, input);
            boolean emptied = slotEmpty(c, slot);
            if (rubix) {
                c.check(emptied, "Rubix: the PICKUP prediction did not empty slot " + slot + " (the case would prove nothing)");
            }
            c.waitUntil("the server to receive the click on " + slot, mc -> serverEvents(c) > sent, 60);
            int back = -1;
            for (int i = 0; i < 40; i++) {
                if (!slotEmpty(c, slot) && c.onClient(mc -> TerminalCases.highlights().containsKey(slot))) {
                    back = i;
                    break;
                }
                c.ctx().waitTicks(1);
            }
            c.check(back >= 0, spec.type() + ": slot " + slot + " never reappeared in the solver after the server dropped its click");
            c.check(c.events("terminal.dropped").size() >= 1, "the server never dropped a click (nothing was tested)");
            c.note(spec.type() + ": click on " + slot + " dropped by the server, slot " + (emptied ? "predicted empty, " : "")
                    + "shown again after " + back + " ticks");

            // 2. Finish the terminal by hand, one click per server answer (the next drop is somewhere in here).
            int empties = 0;
            for (int i = 0; i < 150 && MenuKit.ev(c, "terminal.solved").isEmpty(); i++) {
                Object tg = c.onClient(mc -> TerminalCases.highlights().isEmpty() ? null
                        : Mod.staticCall(TerminalCases.FEATURE, "pickAutoClickTarget", typeEnum, TerminalCases.highlights()));
                if (tg == null) {
                    if (++empties > 60) {
                        break;
                    }
                    c.ctx().waitTicks(1);
                    continue;
                }
                empties = 0;
                int n = serverEvents(c);
                send(c, (Integer) Mod.call(tg, "slot"), (Integer) Mod.call(tg, "button"), (ContainerInput) Mod.call(tg, "clickType"));
                c.waitUntil("the server to receive the click", mc -> serverEvents(c) > n, 60);
                // The answer, or the restore of a dropped click, takes a few ticks.
                c.ctx().waitTicks(rubix ? 8 : 3);
            }
            JsonObject solved = MenuKit.awaitEvent(c, "terminal.solved", 40);
            c.check(c.events("terminal.dropped").size() == 2, "expected 2 dropped clicks, saw " + c.events("terminal.dropped").size());
            c.note(spec.type() + ": completed by hand after the drops, server clicks " + solved.get("clicks") + ", wrong "
                    + solved.get("wrong"));
            MenuKit.awaitNoScreen(c, 40);
        } finally {
            MenuKit.reset(c);
        }
    }

    /**
     * 1108 (dungsolve; killer560: "it'll click all of them but the very last one will reappear but it doesnt pick it up
     * again"): the server drops the click that would SOLVE the board, so the last slot stays as it was. Auto Terminals must
     * click it again like any other slot. Asserts the final click really was dropped and that a later click solved it.
     */
    private static void lastPane(Session c, TerminalCases.Spec spec, boolean streamSafe, Object... mode) throws Exception {
        MenuKit.reset(c);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            if (!MenuKit.cheat()) {
                c.note("legit jar: Auto Terminals clicks nothing (checked by 211-216)");
                return;
            }
            cfg.set(TerminalCases.CONFIG, "Enabled", true).set(TerminalCases.CONFIG, "AutoTerminalsEnabled", true)
                    .set(TerminalCases.CONFIG, spec.auto(), true).set(TerminalCases.CONFIG, "AutoStreamSafe", streamSafe);
            List<Object> kv = new java.util.ArrayList<>(List.of("type", spec.type()));
            kv.addAll(List.of(spec.args()));
            kv.addAll(List.of(mode));
            JsonObject t = c.hx().call("menu.terminal", kv.toArray()).getAsJsonObject();
            MenuKit.awaitScreen(c, t.get("title").getAsString(), 100);
            JsonObject solved = MenuKit.awaitEvent(c, "terminal.solved", 600);
            boolean revert = List.of(mode).contains("revertFinal");
            int times = List.of(mode).contains("dropFinalTimes") ? 3 : 1;
            int dropped = c.events("terminal.dropped").size();
            int reverted = c.events("terminal.reverted").size();
            if (revert) {
                c.check(dropped == 0 && reverted == 1, "the server should answer and then take back the final click once: dropped "
                        + dropped + ", reverted " + reverted + " (nothing was tested)");
            } else {
                c.check(dropped == times, "the server should drop the final click " + times + "x, it dropped " + dropped + " (nothing was tested)");
            }
            c.note("Auto " + spec.type() + ": final click " + (revert ? "answered then taken back" : "dropped " + times + "x")
                    + ", solved after a re-click with " + solved.get("clicks") + " server clicks");
            MenuKit.awaitNoScreen(c, 40);
        } finally {
            MenuKit.reset(c);
        }
    }

    /** Cheat jars only: Auto Terminals clicks on after drops, one click per tick, and never a second click on an answered slot. */
    private static void auto(Session c, TerminalCases.Spec spec) throws Exception {
        MenuKit.reset(c);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            if (!MenuKit.cheat()) {
                c.note("legit jar: Auto Terminals clicks nothing (checked by 211-216)");
                return;
            }
            cfg.set(TerminalCases.CONFIG, "Enabled", true).set(TerminalCases.CONFIG, "AutoTerminalsEnabled", true)
                    .set(TerminalCases.CONFIG, spec.auto(), true);
            List<Object> kv = new java.util.ArrayList<>(List.of("type", spec.type()));
            kv.addAll(List.of(spec.args()));
            kv.addAll(List.of("dropClicks", 3));
            JsonObject t = c.hx().call("menu.terminal", kv.toArray()).getAsJsonObject();
            MenuKit.awaitScreen(c, t.get("title").getAsString(), 100);
            JsonObject solved = MenuKit.awaitEvent(c, "terminal.solved", 900);
            int dropped = c.events("terminal.dropped").size();
            c.check(dropped == 3, "expected 3 dropped clicks, the server saw " + dropped + " (nothing was tested)");
            c.check(solved.get("maxClicksPerTick").getAsInt() <= 1, "more than one terminal click in a server tick: " + solved);
            if (!spec.type().equals("RUBIX")) {
                c.check(solved.get("wrong").getAsInt() == 0, "Auto re-clicked a slot the server had already taken: " + solved);
            }
            c.note("Auto " + spec.type() + " after 3 dropped clicks: solved with " + solved.get("clicks") + " server clicks, "
                    + solved.get("wrong") + " wrong");
            MenuKit.awaitNoScreen(c, 40);
        } finally {
            MenuKit.reset(c);
        }
    }
}
