package dev.testkit.gametest.menu;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The terms-1026 terminal work (mod branch terms-1026), against {@code menu.terminal}:
 * <ul>
 *   <li>271/272 Hover Melody: the real mouse rests on each row's button in turn (Custom GUI on, then off); each row is
 *       clicked exactly once, at the right column, with no mouse move while it waits, and the terminal completes.</li>
 *   <li>273/274 Hold To Click (both builds): left click held on Numbers (Custom GUI) and Select (vanilla). The pointer
 *       parks on a wrong slot for a while (nothing may be sent), then on each correct slot in turn; the repeat clicks
 *       them, the terminal completes, no click is wrong and no server tick holds two.</li>
 *   <li>275-277 Melody Lookahead 0/1/2: the proper click plus N more rows; each row clicked once, all correct.</li>
 *   <li>278/279 Fake Cursor (Custom GUI / vanilla): starts at the window center, every auto click goes out with the
 *       drawn pointer on the clicked slot's drawn center, and it moves without jumps.</li>
 * </ul>
 */
final class TermHoldCases {

    static final String FEATURE = TerminalCases.FEATURE;
    static final String CONFIG = TerminalCases.CONFIG;
    static final String HOLD = "terminals.HoldToClickFeature";
    static final String CURSOR = "terminals.FakeCursor";

    private TermHoldCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "271-menu-term-hover-melody3", c -> hoverMelody(c, true));
        MenuSuite.test(s, "272-menu-term-hover-melody3-vanilla", c -> hoverMelody(c, false));
        MenuSuite.test(s, "273-menu-term-hold-numbers", c -> hold(c, TerminalCases.NUMBERS_10, true));
        MenuSuite.test(s, "274-menu-term-hold-select", c -> hold(c, TerminalCases.SPECS.get(4), false));
        MenuSuite.test(s, "275-menu-term-lookahead0", c -> lookahead(c, 0));
        MenuSuite.test(s, "276-menu-term-lookahead1", c -> lookahead(c, 1));
        MenuSuite.test(s, "277-menu-term-lookahead2", c -> lookahead(c, 2));
        MenuSuite.test(s, "278-menu-term-fakecursor-custom", c -> fakeCursor(c, true));
        MenuSuite.test(s, "279-menu-term-fakecursor-vanilla", c -> fakeCursor(c, false));
    }

    // ---- where things are drawn ---------------------------------------------------------------------------------

    /** Window-pixel center of {@code slot} as the screen draws it (the Custom GUI's own layout, or the vanilla slot). */
    static double[] slotPx(Minecraft mc, int slot, boolean customGui) {
        Object screen = dev.testkit.compat.McCompat.screen(mc);
        double gui = mc.getWindow().getGuiScale();
        if (customGui) {
            Object layout = Mod.staticCall(FEATURE, "computeCustomGuiLayout", screen);
            if (layout == null) {
                return null;
            }
            float scale = (Float) Mod.field(layout, "scale");
            double x = (Integer) Mod.field(layout, "originX")
                    + (((slot % 9) - (Integer) Mod.field(layout, "minCol")) * TerminalCases.CELL + TerminalCases.SQUARE / 2.0) * scale;
            double y = (Integer) Mod.field(layout, "originY")
                    + (((slot / 9) - (Integer) Mod.field(layout, "minRow")) * TerminalCases.CELL + TerminalCases.SQUARE / 2.0) * scale;
            return new double[]{x * gui, y * gui};
        }
        if (!(screen instanceof AbstractContainerScreen<?> s)) {
            return null;
        }
        int left = ((Number) Mod.field(s, "leftPos")).intValue();
        int top = ((Number) Mod.field(s, "topPos")).intValue();
        var sl = s.getMenu().slots.get(slot);
        return new double[]{(left + sl.x + 8) * gui, (top + sl.y + 8) * gui};
    }

    static void pointAt(Session c, int slot, boolean customGui) {
        double[] at = c.onClient(mc -> slotPx(mc, slot, customGui));
        c.check(at != null, "could not place slot " + slot + " on screen");
        if (at != null) {
            c.ctx().getInput().setCursorPos(at[0], at[1]);
        }
    }

    static List<JsonObject> clicks(Session c) {
        return c.events("terminal.click");
    }

    static Map<Integer, Integer> clicksPerSlot(Session c) {
        Map<Integer, Integer> m = new TreeMap<>();
        for (JsonObject e : clicks(c)) {
            m.merge(e.get("slot").getAsInt(), 1, Integer::sum);
        }
        return m;
    }

    static int wrongClicks(Session c) {
        int n = 0;
        for (JsonObject e : clicks(c)) {
            if (!e.get("correct").getAsBoolean()) {
                n++;
            }
        }
        return n;
    }

    static List<Integer> ints(JsonElement arr) {
        List<Integer> out = new ArrayList<>();
        arr.getAsJsonArray().forEach(e -> out.add(e.isJsonObject() ? e.getAsJsonObject().get("slot").getAsInt() : e.getAsInt()));
        return out;
    }

    // ---- 271/272 Hover Melody -----------------------------------------------------------------------------------

    static void hoverMelody(Session c, boolean customGui) throws Exception {
        MenuKit.reset(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: Hover Terminals is cheat-only");
            return;
        }
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CONFIG, "Enabled", true).set(CONFIG, "AutoTerminalsEnabled", false)
                    .set(CONFIG, "CustomGuiEnabled", customGui).set(CONFIG, "MelodyEnabled", true)
                    .set(CONFIG, "HoverTerminalsEnabled", true).set(CONFIG, "HoverMelodyEnabled", true)
                    .set(CONFIG, "HoverDelayMs", 100).set(CONFIG, "HoverJitterMs", 0);
            // Park the pointer in a corner first so the first rest is a real arrival on the button.
            c.ctx().getInput().setCursorPos(2, 2);
            JsonObject t = c.hx().call("menu.terminal", "type", "MELODY", "seed", 31, "interval", 6, "bandRows", 3)
                    .getAsJsonObject();
            MenuKit.awaitScreen(c, t.get("title").getAsString(), 100);
            c.waitUntil("the solver to track MELODY", mc -> "MELODY".equals(TerminalCases.currentType()), 100);
            c.ctx().waitTicks(15);
            List<String> log = new ArrayList<>();
            for (int row = 1; row <= 3; row++) {
                int button = 16 + (row - 1) * 9;
                pointAt(c, button, customGui);
                int r = row;
                // The pane bounces 1..5 every 6 ticks: a full lap is 48 ticks; allow three.
                c.waitUntil("row " + row + "'s button (slot " + button + ") to be clicked with the mouse resting on it",
                        mc -> clicksPerSlot(c).getOrDefault(button, 0) >= 1, 160);
                // Keep resting a while: a second click on the same row would show up here.
                c.ctx().waitTicks(row < 3 ? 10 : 20);
                log.add("row " + r + ": " + clicksPerSlot(c).getOrDefault(button, 0) + " click(s)");
            }
            JsonObject solved = MenuKit.awaitEvent(c, "terminal.solved", 60);
            Map<Integer, Integer> per = clicksPerSlot(c);
            c.note((customGui ? "Custom GUI" : "vanilla") + " Hover Melody: " + log + "; server: " + solved.get("clicks")
                    + " clicks, " + solved.get("wrong") + " wrong, max " + solved.get("maxClicksPerTick") + " a tick; per slot " + per);
            c.check(per.equals(Map.of(16, 1, 25, 1, 34, 1)), "expected exactly one click on each of 16/25/34, got " + per);
            c.check(wrongClicks(c) == 0, wrongClicks(c) + " hover click(s) were off the target column");
            c.check(solved.get("maxClicksPerTick").getAsInt() <= 1, "two clicks in one server tick");
        } finally {
            MenuKit.reset(c);
        }
    }

    // ---- 273/274 Hold To Click ----------------------------------------------------------------------------------

    static void hold(Session c, TerminalCases.Spec spec, boolean customGui) throws Exception {
        MenuKit.reset(c);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CONFIG, "Enabled", true).set(CONFIG, "AutoTerminalsEnabled", false)
                    .set(CONFIG, "HoverTerminalsEnabled", false).set(CONFIG, "CustomGuiEnabled", customGui)
                    .set(CONFIG, "HoldToClickEnabled", true).set(CONFIG, "HoldToClickFirstDelayMs", 250)
                    .set(CONFIG, "HoldToClickRepeatMs", 75);
            boolean on = c.onClient(mc -> (Boolean) Mod.get(CONFIG, "isHoldToClickEnabled"));
            c.check(on, (MenuKit.cheat() ? "cheat" : "legit") + " jar: isHoldToClickEnabled() is false after switching it on");
            int before = c.onClient(mc -> (Integer) Mod.field(HOLD, "clicks"));
            c.ctx().getInput().setCursorPos(2, 2);
            JsonObject t = TerminalCases.open(c, spec);
            MenuKit.awaitScreen(c, t.get("title").getAsString(), 100);
            c.waitUntil("the solver to track " + spec.type(), mc -> spec.type().equals(TerminalCases.currentType()), 100);
            c.ctx().waitTicks(15);
            List<Integer> todo = ints(t.get("remaining"));
            List<Integer> grid = ints(t.get("grid"));
            List<Integer> wrongSlots = new ArrayList<>(grid);
            wrongSlots.removeAll(todo);
            c.check(!todo.isEmpty(), "the board has nothing to click: " + t);
            // The press: on the first correct slot (his own click), then held.
            pointAt(c, todo.get(0), customGui);
            c.ctx().waitTicks(2);
            c.ctx().getInput().holdMouse(0);
            int wrongWaited = 0;
            try {
                c.waitUntil("the press on slot " + todo.get(0) + " to reach the server",
                        mc -> clicksPerSlot(c).getOrDefault(todo.get(0), 0) >= 1, 60);
                // Held on a wrong slot for 20 ticks: nothing may go out.
                if (!wrongSlots.isEmpty()) {
                    int n = clicks(c).size();
                    pointAt(c, wrongSlots.get(0), customGui);
                    c.ctx().waitTicks(20);
                    wrongWaited = clicks(c).size() - n;
                    c.check(wrongWaited == 0, "held on wrong slot " + wrongSlots.get(0) + " and " + wrongWaited + " click(s) went out");
                }
                // Held on the clicked slot again for 10 ticks: it is solved, so nothing more.
                int n0 = clicks(c).size();
                pointAt(c, todo.get(0), customGui);
                c.ctx().waitTicks(10);
                c.check(clicks(c).size() == n0, "held on the already-correct slot " + todo.get(0) + " and it was clicked again");
                for (int i = 1; i < todo.size(); i++) {
                    int slot = todo.get(i);
                    pointAt(c, slot, customGui);
                    c.waitUntil("the held repeat to click slot " + slot, mc -> clicksPerSlot(c).getOrDefault(slot, 0) >= 1, 60);
                }
            } finally {
                c.ctx().getInput().releaseMouse(0);
            }
            JsonObject solved = MenuKit.awaitEvent(c, "terminal.solved", 60);
            int repeats = c.onClient(mc -> (Integer) Mod.field(HOLD, "clicks")) - before;
            Map<Integer, Integer> per = clicksPerSlot(c);
            c.note((MenuKit.cheat() ? "cheat" : "legit") + " " + spec.type() + (customGui ? " (Custom GUI)" : " (vanilla)")
                    + ": " + todo.size() + " correct slots, 1 press + " + repeats + " held repeats; server " + solved.get("clicks")
                    + " clicks, " + solved.get("wrong") + " wrong, max " + solved.get("maxClicksPerTick") + " a tick; "
                    + MenuKit.cadence(c));
            c.check(repeats == todo.size() - 1, "expected " + (todo.size() - 1) + " repeat clicks, the feature sent " + repeats);
            c.check(solved.get("wrong").getAsInt() == 0, "the server rejected " + solved.get("wrong") + " click(s)");
            c.check(solved.get("maxClicksPerTick").getAsInt() <= 1, "two clicks in one server tick");
            for (Map.Entry<Integer, Integer> e : per.entrySet()) {
                c.check(e.getValue() == 1, "slot " + e.getKey() + " was clicked " + e.getValue() + " times");
            }
        } finally {
            MenuKit.reset(c);
        }
    }

    // ---- 275-277 Melody Lookahead -------------------------------------------------------------------------------

    static void lookahead(Session c, int n) throws Exception {
        MenuKit.reset(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: Auto Melody is cheat-only");
            return;
        }
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CONFIG, "Enabled", true).set(CONFIG, "AutoTerminalsEnabled", true).set(CONFIG, "AutoMelodyEnabled", true)
                    .set(CONFIG, "HoverTerminalsEnabled", false).set(CONFIG, "FakeCursor", false)
                    .set(CONFIG, "MelodySkipMode", Mod.enumValue(CONFIG + "$MelodySkipMode", "EDGES"))
                    .set(CONFIG, "MelodyLookaheadClicks", n);
            int got = c.onClient(mc -> (Integer) Mod.get(CONFIG, "getMelodyLookaheadClicks"));
            c.check(got == n, "lookahead set to " + n + " reads back " + got);
            int before = c.onClient(mc -> (Integer) Mod.field(FEATURE, "melodyLookaheadFired"));
            // keepTarget: the target column stays put, so the gamble the burst makes is a winning one here.
            JsonObject t = c.hx().call("menu.terminal", "type", "MELODY", "seed", 40 + n, "interval", 6, "bandRows", 3,
                    "target", 3, "keepTarget", true).getAsJsonObject();
            MenuKit.awaitScreen(c, t.get("title").getAsString(), 100);
            JsonObject solved = MenuKit.awaitEvent(c, "terminal.solved", 400);
            MenuKit.awaitNoScreen(c, 40);
            int fired = c.onClient(mc -> (Integer) Mod.field(FEATURE, "melodyLookaheadFired")) - before;
            Map<Integer, Integer> per = clicksPerSlot(c);
            List<Long> ticks = new ArrayList<>();
            for (JsonObject e : clicks(c)) {
                ticks.add(e.get("tick").getAsLong());
            }
            c.note("Lookahead " + n + ": " + fired + " lookahead click(s) after the proper one; per slot " + per + " at ticks "
                    + ticks + "; " + solved.get("wrong") + " wrong, max " + solved.get("maxClicksPerTick") + " a server tick");
            c.check(fired == n, "lookahead " + n + " should send " + n + " extra click(s) (" + (n + 1) + " in the burst), sent " + fired);
            c.check(per.equals(Map.of(16, 1, 25, 1, 34, 1)), "expected one click on each row button, got " + per);
            c.check(solved.get("wrong").getAsInt() == 0, solved.get("wrong") + " wrong click(s)");
        } finally {
            MenuKit.reset(c);
        }
    }

    // ---- 278/279 Fake Cursor ------------------------------------------------------------------------------------

    static void fakeCursor(Session c, boolean customGui) throws Exception {
        MenuKit.reset(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: Fake Cursor belongs to Auto Terminals (cheat-only)");
            return;
        }
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CONFIG, "Enabled", true).set(CONFIG, "AutoTerminalsEnabled", true).set(CONFIG, "AutoNumbersEnabled", true)
                    .set(CONFIG, "CustomGuiEnabled", customGui).set(CONFIG, "FakeCursor", true).set(CONFIG, "FakeCursorSpeed", 100)
                    .set(CONFIG, "HoverTerminalsEnabled", false);
            JsonObject t = TerminalCases.open(c, TerminalCases.NUMBERS_10);
            MenuKit.awaitScreen(c, t.get("title").getAsString(), 100);
            c.waitUntil("the solver to track NUMBERS", mc -> "NUMBERS".equals(TerminalCases.currentType()), 100);
            c.ctx().waitTicks(3);
            List<Integer> grid = ints(t.get("grid"));
            Map<Integer, double[]> expected = new LinkedHashMap<>();
            double[] center = c.onClient(mc -> {
                for (int slot : grid) {
                    expected.put(slot, slotPx(mc, slot, customGui));
                }
                return new double[]{mc.getWindow().getWidth() / 2.0, mc.getWindow().getHeight() / 2.0};
            });
            // A screenshot sequence of the solve, to look at.
            List<String> shots = new ArrayList<>();
            for (int i = 0; i < 12 && c.events("terminal.solved").isEmpty(); i++) {
                String name = c.name() + "-" + String.format("%02d", i);
                java.nio.file.Path shot = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(name));
                dev.testkit.harness.Report.screenshot(name, shot);
                shots.add(shot.getFileName().toString());
                c.ctx().waitTicks(3);
            }
            JsonObject solved = MenuKit.awaitEvent(c, "terminal.solved", 600);
            float sx = c.onClient(mc -> (Float) Mod.field(CURSOR, "startX"));
            float sy = c.onClient(mc -> (Float) Mod.field(CURSOR, "startY"));
            int count = c.onClient(mc -> (Integer) Mod.field(CURSOR, "clickCount"));
            int frames = c.onClient(mc -> (Integer) Mod.field(CURSOR, "frames"));
            float maxStep = c.onClient(mc -> (Float) Mod.field(CURSOR, "maxFramePx"));
            float maxSpeed = c.onClient(mc -> (Float) Mod.field(CURSOR, "maxPxPerMs"));
            int waited = c.onClient(mc -> (Integer) Mod.field(CURSOR, "clicksWaited"));
            int[] slots = c.onClient(mc -> ((int[]) Mod.field(CURSOR, "CLICK_SLOT")).clone());
            float[] xs = c.onClient(mc -> ((float[]) Mod.field(CURSOR, "CLICK_X")).clone());
            float[] ys = c.onClient(mc -> ((float[]) Mod.field(CURSOR, "CLICK_Y")).clone());
            int serverClicks = clicks(c).size();
            double worst = 0;
            List<String> off = new ArrayList<>();
            for (int i = 0; i < Math.min(count, slots.length); i++) {
                double[] e = expected.get(slots[i]);
                if (e == null) {
                    off.add("click " + i + " on slot " + slots[i] + " outside the grid");
                    continue;
                }
                double d = Math.hypot(xs[i] - e[0], ys[i] - e[1]);
                worst = Math.max(worst, d);
                if (d > 1.5) {
                    off.add(String.format("click %d slot %d: pointer (%.1f, %.1f), slot (%.1f, %.1f), %.1f px off",
                            i, slots[i], xs[i], ys[i], e[0], e[1], d));
                }
            }
            double diag = Math.hypot(center[0] * 2, center[1] * 2);
            c.note(String.format("%s: start (%.1f, %.1f) vs center (%.1f, %.1f); %d clicks recorded / %d at the server;"
                            + " worst pointer-to-slot at click %.2f px; %d frames, biggest frame step %.1f px, fastest %.2f px/ms;"
                            + " %d frame(s) a due click waited for the pointer; solved %s ticks after open; shots %s",
                    customGui ? "Custom GUI" : "vanilla", sx, sy, center[0], center[1], count, serverClicks, worst, frames, maxStep,
                    maxSpeed, waited, solved.get("solvedTick").getAsLong() - solved.get("openedTick").getAsLong(), shots));
            c.check(Math.abs(sx - center[0]) <= 1 && Math.abs(sy - center[1]) <= 1, "the pointer did not start at the window center");
            c.check(count == serverClicks && count > 0, "pointer recorded " + count + " clicks, the server saw " + serverClicks);
            c.check(off.isEmpty(), "pointer not on the clicked slot at click time: " + off);
            c.check(frames > 20, "only " + frames + " pointer frames drawn");
            // A hand at 100% peaks well under 6 px/ms; a teleport across the panel in one frame would be far over it.
            c.check(maxSpeed < 6f, "the pointer moved " + maxSpeed + " px/ms between two frames (a jump)");
            c.check(maxStep < diag / 4, "a single frame moved the pointer " + maxStep + " px (a jump)");
            c.check(solved.get("wrong").getAsInt() == 0, solved.get("wrong") + " wrong click(s)");
            c.check(solved.get("maxClicksPerTick").getAsInt() <= 1, "two clicks in one server tick");
        } finally {
            MenuKit.reset(c);
        }
    }
}
