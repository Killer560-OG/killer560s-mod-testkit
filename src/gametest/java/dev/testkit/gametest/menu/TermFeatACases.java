package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;

import dev.testkit.gametest.hx.HxKit;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.WireCapture;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.world.InteractionHand;

import java.util.ArrayList;
import java.util.List;

/**
 * featA-1108 (mod branch featA-1108): Terminal Sounds, Terminal Click Path (legit) and Click Order, Queue Terms (cheat),
 * against {@code menu.terminal}. Every case asserts the feature acted: sounds counted, path points kept, the server's
 * own click order, held-and-sent counters, and for the same-tick case the wire (WireCapture).
 */
final class TermFeatACases {

    static final String CONFIG = TerminalCases.CONFIG;
    static final String QOL = "terminals.TerminalQolConfig";
    static final String EXTRAS = "terminals.TerminalExtras";
    static final String QUEUE = "terminals.QueueTermsFeature";
    static final String ORDER = CONFIG + "$ClickOrder";

    private TermFeatACases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1108-menu-term-sounds-path", TermFeatACases::soundsPath);
        for (String o : new String[]{"first", "closest", "furthest", "random"}) {
            MenuSuite.test(s, "1108-menu-term-order-" + o, c -> order(c, o.toUpperCase()));
        }
        MenuSuite.test(s, "1108-menu-term-queue-select", c -> queue(c, TerminalCases.SPECS.get(4), false));
        MenuSuite.test(s, "1108-menu-term-queue-numbers", c -> queue(c, TerminalCases.NUMBERS_10, false));
        MenuSuite.test(s, "1108-menu-term-queue-sametick", c -> queue(c, TerminalCases.SPECS.get(4), true));
        // 2026-11-09: Queue Terms on its own switch (the Terminal Solver OFF), his hotbar swap and use in the click tick.
        MenuSuite.test(s, "1109-menu-term-queue-solver-off", c -> queue(c, TerminalCases.SPECS.get(4), true, false));
    }

    private static int num(Session c, String cls, String field) {
        return c.onClient(mc -> ((Number) Mod.field(cls, field)).intValue());
    }

    /** One real left click on {@code slot}: the pointer rests on it, the button goes down for a tick. */
    private static void tap(Session c, int slot) {
        TermHoldCases.pointAt(c, slot, false);
        c.ctx().waitTicks(2); // the screen's hovered slot follows the pointer on the next frame
        c.ctx().getInput().holdMouse(dev.testkit.compat.McKeys.mouse(0));
        c.ctx().waitTicks(1);
        c.ctx().getInput().releaseMouse(dev.testkit.compat.McKeys.mouse(0));
    }

    // ---- Terminal Sounds + Click Path (legit) ----------------------------------------------------------------------

    static void soundsPath(Session c) throws Exception {
        MenuKit.reset(c);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CONFIG, "Enabled", true).set(CONFIG, "AutoTerminalsEnabled", false).set(CONFIG, "CustomGuiEnabled", false)
                    .set(CONFIG, "HoverTerminalsEnabled", false)
                    .set(QOL, "TerminalSounds", true).set(QOL, "ClickPath", true);
            int clickBefore = num(c, EXTRAS, "clickSoundsPlayed");
            int doneBefore = num(c, EXTRAS, "completeSoundsPlayed");
            c.ctx().getInput().setCursorPos(2, 2);
            JsonObject t = TerminalCases.open(c, TerminalCases.SPECS.get(4));
            MenuKit.awaitScreen(c, t.get("title").getAsString(), 100);
            c.waitUntil("the solver to track SELECT", mc -> "SELECT".equals(TerminalCases.currentType()), 100);
            c.ctx().waitTicks(15);
            List<Integer> todo = TermHoldCases.ints(t.get("remaining"));
            List<Integer> wrong = new ArrayList<>(TermHoldCases.ints(t.get("grid")));
            wrong.removeAll(todo);
            c.check(!todo.isEmpty() && !wrong.isEmpty(), "board has no right or no wrong slots: " + t);
            // One wrong click first: it adds a red point and no sound.
            tap(c, wrong.get(0));
            c.ctx().waitTicks(6);
            c.check(num(c, EXTRAS, "clickSoundsPlayed") == clickBefore, "a wrong click played the click sound");
            for (int slot : todo) {
                tap(c, slot);
                c.ctx().waitTicks(4);
            }
            MenuKit.awaitEvent(c, "terminal.solved", 80);
            MenuKit.awaitNoScreen(c, 60);
            int played = num(c, EXTRAS, "clickSoundsPlayed") - clickBefore;
            @SuppressWarnings("unchecked")
            List<String> path = c.onClient(mc -> (List<String>) Mod.staticCall(EXTRAS, "pathForTest"));
            boolean showing = c.onClient(mc -> (Boolean) Mod.staticCall(EXTRAS, "showingForTest"));
            long red = path.stream().filter(p -> p.endsWith("false")).count();
            c.note("sounds played " + played + " for " + todo.size() + " right clicks; path " + path.size() + " points, " + red
                    + " red, showing " + showing);
            c.check(played == todo.size(), "click sounds " + played + " != right clicks " + todo.size());
            c.check(path.size() == todo.size() + 1 && red == 1, "path points " + path.size() + ", red " + red);
            c.check(showing, "path not showing after the terminal closed");
            // The own "completed a terminal" line plays the complete sound once.
            c.hx().chat(HxKit.self(c) + " completed a terminal! (1/7)");
            c.ctx().waitTicks(5);
            c.check(num(c, EXTRAS, "completeSoundsPlayed") - doneBefore == 1, "complete sound not played once");
            c.ctx().waitTicks(100);
            c.check(!c.onClient(mc -> (Boolean) Mod.staticCall(EXTRAS, "showingForTest")), "path still showing after 5 s");
        } finally {
            MenuKit.reset(c);
        }
    }

    // ---- Click Order (cheat) -----------------------------------------------------------------------------------------

    static void order(Session c, String order) throws Exception {
        MenuKit.reset(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: Auto Terminals is cheat-only");
            return;
        }
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CONFIG, "Enabled", true).set(CONFIG, "AutoTerminalsEnabled", true).set(CONFIG, "AutoSelectEnabled", true)
                    .set(CONFIG, "AutoStreamSafe", false).set(CONFIG, "HoverTerminalsEnabled", false)
                    .set(CONFIG, "AutoClickMinDelayMs", 0).set(CONFIG, "AutoClickMaxDelayMs", 0)
                    .set(CONFIG, "AutoClickOrder", Mod.enumValue(ORDER, order));
            JsonObject t = TerminalCases.open(c, new TerminalCases.Spec("SELECT",
                    new Object[]{"seed", 15, "color", "RED", "count", 8}, "AutoSelectEnabled"));
            MenuKit.awaitScreen(c, t.get("title").getAsString(), 100);
            JsonObject solved = MenuKit.awaitEvent(c, "terminal.solved", 500);
            List<Integer> slots = new ArrayList<>();
            for (JsonObject e : TermHoldCases.clicks(c)) {
                slots.add(e.get("slot").getAsInt());
            }
            c.note(order + " order: " + slots + ", " + solved.get("wrong") + " wrong");
            c.check(slots.size() >= 5, "too few clicks to judge the order: " + slots);
            c.check(solved.get("wrong").getAsInt() == 0, "the server rejected " + solved.get("wrong") + " click(s)");
            c.check(solved.get("maxClicksPerTick").getAsInt() <= 1, "two clicks in one server tick");
            for (int i = 0; i < slots.size() && !order.equals("RANDOM"); i++) {
                int from = i == 0 ? 0 : slots.get(i - 1);
                double picked = dist(slots.get(i), from);
                for (int j = i + 1; j < slots.size(); j++) {
                    double d = dist(slots.get(j), from);
                    boolean ok = switch (order) {
                        case "FIRST" -> slots.get(i) < slots.get(j);
                        case "CLOSEST" -> picked <= d + 1e-9;
                        default -> picked >= d - 1e-9;
                    };
                    c.check(ok, order + ": click " + i + " (slot " + slots.get(i) + ") but slot " + slots.get(j) + " was better");
                }
            }
            if (order.equals("FURTHEST") || order.equals("CLOSEST")) {
                List<Integer> asc = new ArrayList<>(slots);
                asc.sort(null);
                c.check(!asc.equals(slots), order + " clicked in plain first order: " + slots);
            }
        } finally {
            MenuKit.reset(c);
        }
    }

    private static double dist(int a, int b) {
        return Math.hypot(a % 9 - b % 9, a / 9 - b / 9);
    }

    // ---- Queue Terms (cheat) ----------------------------------------------------------------------------------------

    static void queue(Session c, TerminalCases.Spec spec, boolean sameTick) throws Exception {
        queue(c, spec, sameTick, true);
    }

    static void queue(Session c, TerminalCases.Spec spec, boolean sameTick, boolean solverOn) throws Exception {
        MenuKit.reset(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: Queue Terms is cheat-only");
            return;
        }
        WireCapture.describe = p -> p.getClass().getSimpleName();
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CONFIG, "Enabled", solverOn).set(CONFIG, "AutoTerminalsEnabled", false).set(CONFIG, "CustomGuiEnabled", false)
                    .set(CONFIG, "HoverTerminalsEnabled", false).set(CONFIG, "QueueTerms", true);
            int held0 = num(c, QUEUE, "held");
            int sent0 = num(c, QUEUE, "sent");
            c.ctx().getInput().setCursorPos(2, 2);
            JsonObject t = TerminalCases.open(c, spec);
            MenuKit.awaitScreen(c, t.get("title").getAsString(), 100);
            c.waitUntil("the solver to track " + spec.type(), mc -> spec.type().equals(TerminalCases.currentType()), 100);
            c.ctx().waitTicks(15);
            List<Integer> todo = TermHoldCases.ints(t.get("remaining"));
            if (sameTick) {
                WireCapture.start();
            }
            // Bursts of three clicks in ONE client tick, faster than the server can answer: the first goes, the rest are held
            // and paced one per update. The pointer is set by hand (hovered slot and coordinates) so each click lands.
            int size = spec.type().equals("NUMBERS") ? 2 : 3; // Numbers highlights only the next two
            for (int i = 0; i < todo.size(); i += size) {
                List<Integer> burst = todo.subList(i, Math.min(i + size, todo.size()));
                c.ctx().runOnClient(mc -> {
                    if (sameTick) {
                        // The player's own hotbar swap and use in the tick of the click.
                        mc.player.getInventory().setSelectedSlot((mc.player.getInventory().getSelectedSlot() + 1) % 9);
                        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                    }
                    for (int slot : burst) {
                        clickNow(mc, slot);
                    }
                });
                c.ctx().waitTicks(12);
            }
            JsonObject solved = MenuKit.awaitEvent(c, "terminal.solved", 300);
            int held = num(c, QUEUE, "held") - held0;
            int sent = num(c, QUEUE, "sent") - sent0;
            c.note(spec.type() + (sameTick ? " same-tick" : "") + ": " + todo.size() + " clicks, held " + held + ", sent "
                    + sent + "; server " + solved.get("clicks") + " clicks, " + solved.get("wrong") + " wrong, max "
                    + solved.get("maxClicksPerTick") + " a tick");
            c.check(held > 0 && sent > 0, "Queue Terms never held or sent a click (held " + held + ", sent " + sent + ")");
            c.check(solved.get("wrong").getAsInt() == 0, "the server rejected " + solved.get("wrong") + " click(s)");
            c.check(solved.get("maxClicksPerTick").getAsInt() <= 1, "two clicks in one server tick");
            if (sameTick) {
                int clicks = 0, swaps = 0, uses = 0;
                for (WireCapture.Sent w : WireCapture.snapshot()) {
                    if (!w.phase().equals("play")) continue;
                    String n = w.type() + " " + w.className();
                    clicks += n.contains("ContainerClick") ? 1 : 0;
                    uses += n.contains("UseItem") ? 1 : 0;
                    swaps += n.contains("SetCarriedItem") ? 1 : 0;
                }
                c.note("wire: " + clicks + " container clicks, " + uses + " use_item, " + swaps + " set_carried_item");
                c.check(clicks >= todo.size() && swaps > 0, "the wire shows too few clicks or no swap");
            }
        } finally {
            if (sameTick) {
                WireCapture.stop();
            }
            WireCapture.describe = null;
            MenuKit.reset(c);
        }
    }

    /** A left press then release on {@code slot} straight through the open screen, hovered slot set as a render would. */
    private static void clickNow(Minecraft mc, int slot) {
        Screen s = dev.testkit.compat.McCompat.screen(mc);
        double scale = mc.getWindow().getGuiScale();
        double[] at = TermHoldCases.slotPx(mc, slot, false);
        try {
            var f = net.minecraft.client.gui.screens.inventory.AbstractContainerScreen.class.getDeclaredField("hoveredSlot");
            f.setAccessible(true);
            f.set(s, ((net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) s).getMenu().slots.get(slot));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        var ev = new MouseButtonEvent(at[0] / scale, at[1] / scale, new MouseButtonInfo(dev.testkit.compat.McKeys.mouse(0), 0));
        s.mouseClicked(ev, false);
        s.mouseReleased(ev);
    }
}
