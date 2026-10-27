package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Auto Terminals' Stream Safe human cursor (mod autoterms-human-1027), against {@code menu.terminal}:
 * <ul>
 *   <li>1027-menu-term-human-*, one per terminal type: three solves with Stream Safe OFF (Min/Max timing: no cursor drawn) and four with
 *       it ON, all on the same board. ON: zero wrong clicks, every click with the drawn cursor inside the clicked slot
 *       as drawn, the cursor starting at the window center, no frame faster than a hand, and (order-free types) the click
 *       order differs between runs and is not always reading order. Rubix: a pane needing 2+ clicks gets them back to
 *       back without the cursor leaving it, and exactly its count. Solve times are noted per type and mode.</li>
 *   <li>human-rates: the miss / overshoot / second-look rates over every ON move of the human-* cases, against the configured chances.</li>
 *   <li>normal-hide: Stream Safe OFF with Hide Mouse Cursor: nothing drawn, the real pointer hidden while clicks land, clicks
 *       no closer than the Min delay.</li>
 *   <li>hold-rubix2: Hold To Click on a Rubix pane needing 2 (both builds): exactly two clicks on it, nothing extra.</li>
 * </ul>
 */
final class TermHumanCases {

    static final String CONFIG = TerminalCases.CONFIG;
    static final String CURSOR = "terminals.FakeCursor";
    static final String ORDER = "terminals.HumanClickOrder";
    /** Chances used for the rate check (higher than the defaults so 280-285 make enough events to count). */
    static final int MISS = 15;
    static final int OVERSHOOT = 25;

    /** Totals over 280-285's ON solves for 286. */
    static int movesSum;
    static int missSum;
    static int overSum;
    static int takeSum;
    static final Map<String, String> TIMES = new LinkedHashMap<>();

    private TermHumanCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1027-menu-term-human-panes", c -> human(c, TerminalCases.SPECS.get(0)));
        MenuSuite.test(s, "1027-menu-term-human-rubix", c -> human(c, TerminalCases.SPECS.get(1)));
        MenuSuite.test(s, "1027-menu-term-human-numbers", c -> human(c, TerminalCases.NUMBERS_10));
        MenuSuite.test(s, "1027-menu-term-human-startswith", c -> human(c, TerminalCases.SPECS.get(3)));
        MenuSuite.test(s, "1027-menu-term-human-select", c -> human(c, TerminalCases.SPECS.get(4)));
        MenuSuite.test(s, "1027-menu-term-human-melody", c -> human(c, TerminalCases.MELODY_3));
        MenuSuite.test(s, "1027-menu-term-human-rates", TermHumanCases::rates);
        MenuSuite.test(s, "1027-menu-term-normal-hide", TermHumanCases::normalHide);
        MenuSuite.test(s, "1027-menu-term-hold-rubix2", TermHumanCases::holdRubix);
    }

    static int intField(Session c, String cls, String name) {
        return c.onClient(mc -> (Integer) Mod.field(cls, name));
    }

    static float floatField(Session c, String cls, String name) {
        return c.onClient(mc -> (Float) Mod.field(cls, name));
    }

    // ---- 280-285 ------------------------------------------------------------------------------------------------

    static void human(Session c, TerminalCases.Spec spec) throws Exception {
        MenuKit.reset(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: Auto Terminals is cheat-only");
            return;
        }
        boolean melody = spec.type().equals("MELODY");
        boolean orderFree = !melody && !spec.type().equals("NUMBERS");
        List<String> problems = new ArrayList<>();
        List<Long> offTicks = new ArrayList<>();
        List<Long> onTicks = new ArrayList<>();
        List<List<Integer>> orders = new ArrayList<>();
        int onWrong = 0;
        int notReading = 0;
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CONFIG, "Enabled", true).set(CONFIG, "AutoTerminalsEnabled", true).set(CONFIG, spec.auto(), true)
                    .set(CONFIG, "CustomGuiEnabled", true).set(CONFIG, "HoverTerminalsEnabled", false)
                    .set(CONFIG, "HoldToClickEnabled", false).set(CONFIG, "MelodyLookaheadClicks", 0)
                    .set(CONFIG, "CursorMissChance", MISS).set(CONFIG, "CursorOvershootChance", OVERSHOOT)
                    .set(CONFIG, "CursorReactionMs", 130).set(CONFIG, "FakeCursorSpeed", 100)
                    .set(CONFIG, "AutoClickMinDelayMs", 120).set(CONFIG, "AutoClickMaxDelayMs", 200)
                    .set(CONFIG, "AutoStreamSafe", false);
            for (int run = 0; run < 7; run++) {
                boolean on = run >= 3;
                c.onClient(mc -> Mod.with(CONFIG, "AutoStreamSafe", on));
                int moves0 = intField(c, CURSOR, "moves");
                int miss0 = intField(c, CURSOR, "misses");
                int over0 = intField(c, CURSOR, "overshoots");
                int take0 = intField(c, CURSOR, "doubleTakes");
                int picks0 = intField(c, ORDER, "picks");
                int notReading0 = intField(c, ORDER, "picksNotReadingOrder");
                int before = c.events("terminal.click").size();
                int solvedBefore = c.events("terminal.solved").size();
                JsonObject t = TerminalCases.open(c, spec);
                MenuKit.awaitScreen(c, t.get("title").getAsString(), 100);
                c.waitUntil("the solver to track " + spec.type(), mc -> spec.type().equals(TerminalCases.currentType()), 100);
                // Expected drawn centers of every grid slot, and Rubix's starting counts.
                List<Integer> grid = TermHoldCases.ints(t.get("grid"));
                Map<Integer, double[]> expected = new HashMap<>();
                Map<Integer, Integer> rubixNeed = new HashMap<>();
                c.waitUntil("the panel to lay out", mc -> TermHoldCases.slotPx(mc, grid.get(0), true) != null, 100);
                c.onClient(mc -> {
                    for (int slot : grid) {
                        expected.put(slot, TermHoldCases.slotPx(mc, slot, true));
                    }
                    if (spec.type().equals("RUBIX")) {
                        for (Map.Entry<Integer, Object> e : TerminalCases.highlights().entrySet()) {
                            String label = (String) Mod.call(e.getValue(), "label");
                            rubixNeed.put(e.getKey(), Math.abs(Integer.parseInt(label.trim())));
                        }
                    }
                    return null;
                });
                double[] center = c.onClient(mc -> new double[]{mc.getWindow().getWidth() / 2.0, mc.getWindow().getHeight() / 2.0});
                if (on && run == 3) {
                    for (int i = 0; i < 6 && c.events("terminal.solved").size() == solvedBefore; i++) {
                        String name = c.name() + "-" + String.format("%02d", i);
                        java.nio.file.Path shot = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(name));
                        dev.testkit.harness.Report.screenshot(name, shot);
                        c.ctx().waitTicks(4);
                    }
                }
                c.waitUntil(spec.type() + " run " + run + " to be solved", mc -> c.events("terminal.solved").size() > solvedBefore, 900);
                JsonObject solved = c.events("terminal.solved").get(c.events("terminal.solved").size() - 1);
                MenuKit.awaitNoScreen(c, 60);
                c.ctx().waitTicks(5);
                long took = solved.get("solvedTick").getAsLong() - solved.get("openedTick").getAsLong();
                List<JsonObject> evs = c.events("terminal.click");
                List<Integer> order = new ArrayList<>();
                for (JsonObject e : evs.subList(before, evs.size())) {
                    order.add(e.get("slot").getAsInt());
                }
                int wrong = solved.get("wrong").getAsInt();
                int perTick = solved.get("maxClicksPerTick").getAsInt();
                if (perTick > 1) {
                    problems.add("run " + run + ": " + perTick + " clicks in one server tick");
                }
                int frames = intField(c, CURSOR, "frames");
                if (!on) {
                    offTicks.add(took);
                    if (wrong > 0) {
                        problems.add("OFF run " + run + ": " + wrong + " wrong");
                    }
                    if (frames > 0) {
                        problems.add("OFF run " + run + ": the cursor drew " + frames + " frames with Stream Safe off");
                    }
                } else {
                    onTicks.add(took);
                    onWrong += wrong;
                    orders.add(order);
                    notReading += intField(c, ORDER, "picksNotReadingOrder") - notReading0;
                    float sx = floatField(c, CURSOR, "startX");
                    float sy = floatField(c, CURSOR, "startY");
                    if (Math.abs(sx - center[0]) > 1 || Math.abs(sy - center[1]) > 1) {
                        problems.add("ON run " + run + ": cursor started at " + sx + "," + sy + ", not the center");
                    }
                    float speed = floatField(c, CURSOR, "maxPxPerMs");
                    if (speed >= 6f) {
                        problems.add("ON run " + run + ": cursor moved " + speed + " px/ms in one frame");
                    }
                    int count = intField(c, CURSOR, "clickCount");
                    if (count != order.size()) {
                        problems.add("ON run " + run + ": cursor recorded " + count + " clicks, server saw " + order.size());
                    }
                    int[] slots = c.onClient(mc -> ((int[]) Mod.field(CURSOR, "CLICK_SLOT")).clone());
                    float[] xs = c.onClient(mc -> ((float[]) Mod.field(CURSOR, "CLICK_X")).clone());
                    float[] ys = c.onClient(mc -> ((float[]) Mod.field(CURSOR, "CLICK_Y")).clone());
                    float[] txs = c.onClient(mc -> ((float[]) Mod.field(CURSOR, "CLICK_TX")).clone());
                    float[] tys = c.onClient(mc -> ((float[]) Mod.field(CURSOR, "CLICK_TY")).clone());
                    float[] halves = c.onClient(mc -> ((float[]) Mod.field(CURSOR, "CLICK_HALF")).clone());
                    for (int i = 0; i < Math.min(count, slots.length); i++) {
                        double[] e = expected.get(slots[i]);
                        if (e == null || Math.hypot(txs[i] - e[0], tys[i] - e[1]) > 1.5) {
                            problems.add("ON run " + run + " click " + i + ": aimed at the wrong place for slot " + slots[i]);
                        } else if (Math.abs(xs[i] - e[0]) > halves[i] + 0.6 || Math.abs(ys[i] - e[1]) > halves[i] + 0.6) {
                            problems.add(String.format("ON run %d click %d: cursor (%.1f, %.1f) outside slot %d (%.1f, %.1f +-%.1f)",
                                    run, i, xs[i], ys[i], slots[i], e[0], e[1], halves[i]));
                        }
                    }
                    movesSum += intField(c, CURSOR, "moves") - moves0;
                    missSum += intField(c, CURSOR, "misses") - miss0;
                    overSum += intField(c, CURSOR, "overshoots") - over0;
                    takeSum += intField(c, CURSOR, "doubleTakes") - take0;
                    if (spec.type().equals("RUBIX")) {
                        float stray = floatField(c, CURSOR, "repeatMaxPx");
                        if (stray > 1f) {
                            problems.add("ON run " + run + ": the cursor left a pane between two clicks on it (" + stray + " px out)");
                        }
                    }
                }
                if (spec.type().equals("RUBIX")) {
                    // Each pane exactly its count, and its clicks back to back.
                    Map<Integer, Integer> per = new HashMap<>();
                    for (int s : order) {
                        per.merge(s, 1, Integer::sum);
                    }
                    if (!per.equals(rubixNeed)) {
                        problems.add("run " + run + ": Rubix clicks " + per + " but the panes needed " + rubixNeed);
                    }
                    Set<Integer> done = new LinkedHashSet<>();
                    for (int i = 0; i < order.size(); i++) {
                        int s = order.get(i);
                        if (done.contains(s) && order.get(i - 1) != s) {
                            problems.add("run " + run + ": went back to pane " + s + " after leaving it: " + order);
                            break;
                        }
                        done.add(s);
                    }
                }
                System.out.println("[menu] " + c.name() + " run " + run + (on ? " ON " : " OFF ") + took + " ticks, order " + order);
            }
        } finally {
            MenuKit.reset(c);
        }
        Set<List<Integer>> distinct = new LinkedHashSet<>(orders);
        boolean anyNotAscending = false;
        for (List<Integer> o : orders) {
            List<Integer> sorted = new ArrayList<>(o);
            sorted.sort(null);
            if (!sorted.equals(o)) {
                anyNotAscending = true;
            }
        }
        String times = String.format("OFF avg %.1f ticks (%s), Stream Safe avg %.1f ticks (%s)",
                offTicks.stream().mapToLong(Long::longValue).average().orElse(0), offTicks,
                onTicks.stream().mapToLong(Long::longValue).average().orElse(0), onTicks);
        TIMES.put(spec.type(), times);
        c.note(spec.type() + ": " + times + "; ON orders " + orders + " (" + distinct.size() + " distinct, " + notReading
                + " picks not in reading order); ON wrong " + onWrong + "; problems " + problems);
        c.check(onWrong == 0, onWrong + " wrong click(s) with Stream Safe on");
        c.check(problems.isEmpty(), "problems: " + problems);
        if (orderFree) {
            c.check(distinct.size() >= 2, "the same click order every run on the same board: " + orders);
            c.check(anyNotAscending, "every run clicked in plain ascending (reading) order: " + orders);
        }
    }

    // ---- 286 ----------------------------------------------------------------------------------------------------

    static void rates(Session c) {
        if (!MenuKit.cheat()) {
            c.note("legit jar: nothing to count");
            return;
        }
        double miss = movesSum == 0 ? 0 : 100.0 * missSum / movesSum;
        double over = movesSum == 0 ? 0 : 100.0 * overSum / movesSum;
        c.note(String.format("over %d Stream Safe moves: %d misses (%.1f%%, set %d%%), %d overshoots (%.1f%%, set %d%%), %d second looks;"
                + " solve times: %s", movesSum, missSum, miss, MISS, overSum, over, OVERSHOOT, takeSum, TIMES));
        c.check(movesSum >= 60, "only " + movesSum + " moves counted (run the 1027-menu-term-human-* cases first)");
        c.check(miss >= MISS * 0.4 && miss <= MISS * 1.8, "miss rate " + miss + "% far from the configured " + MISS + "%");
        // An overshoot is rolled only when the move did not miss, so its share of all moves sits a little under its chance.
        double overExpected = OVERSHOOT;
        c.check(over >= overExpected * 0.4 && over <= overExpected * 1.6, "overshoot rate " + over + "% far from the configured " + OVERSHOOT + "%");
    }

    // ---- 287 ----------------------------------------------------------------------------------------------------

    static void normalHide(Session c) throws Exception {
        MenuKit.reset(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: Auto Terminals is cheat-only");
            return;
        }
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CONFIG, "Enabled", true).set(CONFIG, "AutoTerminalsEnabled", true).set(CONFIG, "AutoNumbersEnabled", true)
                    .set(CONFIG, "AutoStreamSafe", false).set(CONFIG, "HideMouseCursor", true).set(CONFIG, "CustomGuiEnabled", true)
                    .set(CONFIG, "AutoClickMinDelayMs", 150).set(CONFIG, "AutoClickMaxDelayMs", 150);
            JsonObject t = TerminalCases.open(c, TerminalCases.NUMBERS_10);
            MenuKit.awaitScreen(c, t.get("title").getAsString(), 100);
            boolean sawHidden = false;
            for (int i = 0; i < 300 && c.events("terminal.solved").isEmpty(); i++) {
                if (c.onClient(mc -> (Boolean) Mod.staticCall(CURSOR, "realPointerHidden"))) {
                    sawHidden = true;
                }
                c.ctx().waitTicks(1);
            }
            JsonObject solved = MenuKit.awaitEvent(c, "terminal.solved", 200);
            // frames restarts at each terminal (FakeCursor.beginTerminal), so this is this terminal's count.
            int frames = intField(c, CURSOR, "frames");
            List<Long> ticks = new ArrayList<>();
            for (JsonObject e : c.events("terminal.click")) {
                ticks.add(e.get("tick").getAsLong());
            }
            long minGap = Long.MAX_VALUE;
            for (int i = 1; i < ticks.size(); i++) {
                minGap = Math.min(minGap, ticks.get(i) - ticks.get(i - 1));
            }
            c.note("Stream Safe off, Hide Mouse Cursor on, Min=Max=150 ms: " + ticks.size() + " clicks, gaps >= " + minGap
                    + " ticks, cursor frames " + frames + ", real pointer hidden seen " + sawHidden + ", solved in "
                    + (solved.get("solvedTick").getAsLong() - solved.get("openedTick").getAsLong()) + " ticks, "
                    + solved.get("wrong") + " wrong");
            c.check(frames == 0, "a cursor was drawn with Stream Safe off (" + frames + " frames)");
            c.check(sawHidden, "the real pointer was never hidden while auto clicks landed");
            c.check(minGap >= 2, "two clicks " + minGap + " tick(s) apart with a 150 ms minimum");
            c.check(solved.get("wrong").getAsInt() == 0, "wrong clicks");
        } finally {
            MenuKit.reset(c);
        }
    }

    // ---- 288 ----------------------------------------------------------------------------------------------------

    static void holdRubix(Session c) throws Exception {
        MenuKit.reset(c);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CONFIG, "Enabled", true).set(CONFIG, "AutoTerminalsEnabled", false).set(CONFIG, "HoverTerminalsEnabled", false)
                    .set(CONFIG, "CustomGuiEnabled", true).set(CONFIG, "HoldToClickEnabled", true)
                    .set(CONFIG, "HoldToClickFirstDelayMs", 250).set(CONFIG, "HoldToClickRepeatMs", 75);
            int pane = -1;
            for (int seed = 60; seed < 72 && pane < 0; seed++) {
                MenuKit.reset(c);
                c.ctx().getInput().setCursorPos(2, 2);
                JsonObject t = c.hx().call("menu.terminal", "type", "RUBIX", "seed", seed).getAsJsonObject();
                MenuKit.awaitScreen(c, t.get("title").getAsString(), 100);
                c.waitUntil("Rubix highlights", mc -> !TerminalCases.highlights().isEmpty(), 100);
                c.ctx().waitTicks(12);
                pane = c.onClient(mc -> {
                    for (Map.Entry<Integer, Object> e : TerminalCases.highlights().entrySet()) {
                        if ("2".equals(((String) Mod.call(e.getValue(), "label")).trim())) {
                            return e.getKey();
                        }
                    }
                    return -1;
                });
            }
            c.check(pane >= 0, "no Rubix board with a pane needing 2 in 12 seeds");
            if (pane < 0) {
                return;
            }
            int target = pane;
            int before = c.events("terminal.click").size();
            TermHoldCases.pointAt(c, target, true);
            c.ctx().waitTicks(2);
            c.ctx().getInput().holdMouse(0);
            try {
                c.waitUntil("two clicks on pane " + target, mc -> TermHoldCases.clicksPerSlot(c).getOrDefault(target, 0) >= 2, 80);
                // Keep holding: a third would show up here.
                c.ctx().waitTicks(20);
            } finally {
                c.ctx().getInput().releaseMouse(0);
            }
            List<JsonObject> evs = c.events("terminal.click");
            List<Integer> order = new ArrayList<>();
            for (JsonObject e : evs.subList(before, evs.size())) {
                order.add(e.get("slot").getAsInt());
            }
            boolean pending = c.onClient(mc -> TerminalCases.highlights().containsKey(target));
            c.note((MenuKit.cheat() ? "cheat" : "legit") + ": held on Rubix pane " + target + " needing 2: clicks " + order
                    + ", still highlighted after: " + pending);
            c.check(order.equals(List.of(target, target)), "expected exactly two clicks on " + target + ", got " + order);
            c.check(!pending, "pane " + target + " still needs clicks after two");
        } finally {
            MenuKit.reset(c);
        }
    }
}
