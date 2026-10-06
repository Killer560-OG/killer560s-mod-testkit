package dev.testkit.gametest.menu;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.world.inventory.ContainerInput;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * F7 terminals against {@code menu.terminal} (server-side Hypixel click mechanics, see HxTerminals): the solver's
 * highlights checked against the server's own board, every type solved end-to-end by a "human" following the
 * highlights, then Auto Terminals solving each type by itself with its clicks/tick and the anticheat verdict.
 * On the legit jar the auto cases assert that NOTHING is clicked.
 */
final class TerminalCases {

    static final String FEATURE = "terminals.TerminalSolverFeature";
    static final String CONFIG = "terminals.TerminalSolverConfig";

    /** type, Hx args, auto toggle bean name */
    record Spec(String type, Object[] args, String auto) {
    }

    static final List<Spec> SPECS = List.of(
            new Spec("PANES", new Object[]{"seed", 11, "count", 7}, "AutoPanesEnabled"),
            new Spec("RUBIX", new Object[]{"seed", 12}, "AutoRubixEnabled"),
            new Spec("NUMBERS", new Object[]{"seed", 13}, "AutoNumbersEnabled"),
            new Spec("STARTS_WITH", new Object[]{"seed", 14, "letter", "S", "count", 6}, "AutoStartsWithEnabled"),
            new Spec("SELECT", new Object[]{"seed", 15, "color", "RED", "count", 6}, "AutoSelectEnabled"),
            new Spec("MELODY", new Object[]{"seed", 16, "interval", 10}, "AutoMelodyEnabled"));

    /** The NEW layouts of Hypixel's 2026-10-06 update (killer560): "Click in order!" with 10 numbers, Melody with 3
     *  rows. HxTerminals' shapes for them are a guess until the real boards are seen (see its class doc). */
    static final Spec NUMBERS_10 = new Spec("NUMBERS", new Object[]{"seed", 23, "count", 10}, "AutoNumbersEnabled");
    static final Spec MELODY_3 = new Spec("MELODY", new Object[]{"seed", 26, "interval", 10, "bandRows", 3}, "AutoMelodyEnabled");
    static final String LAYOUTS = "terminals.TerminalLayouts";

    private TerminalCases() {
    }

    static void register(Session s) {
        int n = 201;
        for (Spec spec : SPECS) {
            String name = "2" + String.format("%02d", n++ - 200) + "-menu-term-solver-" + spec.type().toLowerCase().replace("_", "");
            MenuSuite.test(s, name, c -> solverCase(c, spec));
        }
        n = 211;
        for (Spec spec : SPECS) {
            String name = "2" + String.format("%02d", n++ - 200) + "-menu-term-auto-" + spec.type().toLowerCase().replace("_", "");
            MenuSuite.test(s, name, c -> autoCase(c, spec, false));
        }
        MenuSuite.test(s, "217-menu-term-auto-zero-delay", c -> autoCase(c, SPECS.get(2), true));
        MenuSuite.test(s, "218-menu-term-melody-jump", c -> melodyJump(c, 4));
        // The 2026-10-06 layouts, beside the old ones above.
        MenuSuite.test(s, "207-menu-term-solver-numbers10", c -> layoutCase(c, NUMBERS_10, () -> solverCase(c, NUMBERS_10)));
        MenuSuite.test(s, "208-menu-term-solver-melody3", c -> layoutCase(c, MELODY_3, () -> solverCase(c, MELODY_3)));
        MenuSuite.test(s, "209-menu-term-auto-numbers10", c -> layoutCase(c, NUMBERS_10, () -> autoCase(c, NUMBERS_10, false)));
        MenuSuite.test(s, "210-menu-term-auto-melody3", c -> layoutCase(c, MELODY_3, () -> autoCase(c, MELODY_3, false)));
        MenuSuite.test(s, "219-menu-term-melody-jump3", c -> layoutCase(c, MELODY_3, () -> melodyJump(c, 3)));
        // (220-229 are the experiment cases.)
        MenuSuite.test(s, "290-menu-term-melody-keys3", c -> layoutCase(c, MELODY_3, () -> melodyKeys(c)));
        MenuSuite.test(s, "291-menu-term-melody3-skip-all", TerminalCases::melodySkipAll);
    }

    interface Body {
        void run() throws Exception;
    }

    /**
     * Runs a new-layout case and then checks the mod RECORDED the layout it was shown (TerminalLayouts, mod
     * e5fd5db1+): Melody's row count, or the number count of "Click in order!". The record is cleared first, so
     * an old board earlier in the session cannot satisfy it. A jar without TerminalLayouts (main before the
     * 2026-10-06 work) only gets the case itself.
     */
    static void layoutCase(Session c, Spec spec, Body body) throws Exception {
        boolean has = c.onClient(mc -> {
            try {
                Mod.staticCall(LAYOUTS, "resetSeen");
                return true;
            } catch (AssertionError e) {
                return false;
            }
        });
        body.run();
        if (!has) {
            c.note("jar has no " + LAYOUTS + "; layout detection not checked");
            return;
        }
        // The SEEN fields, not melodyRows()/numbersCount(): those fall back to ASSUME_NEW_LAYOUT (true since mod
        // 0.27.2 work) and would read 3/10 with nothing detected at all.
        if (spec.type().equals("MELODY")) {
            int rows = c.onClient(mc -> (Integer) Mod.field(LAYOUTS, "seenMelodyRows"));
            c.check(rows == 3, "TerminalLayouts.seenMelodyRows is " + rows + " after a 3-row Melody board");
            c.note("layout detected: Melody " + rows + " rows");
        } else {
            int count = c.onClient(mc -> (Integer) Mod.field(LAYOUTS, "seenNumbersCount"));
            c.check(count == 10, "TerminalLayouts.seenNumbersCount is " + count + " after a 10-number board");
            c.note("layout detected: Click in order " + count + " numbers");
        }
    }

    @SuppressWarnings("unchecked")
    static Map<Integer, Object> highlights() {
        return (Map<Integer, Object>) Mod.field(FEATURE, "currentHighlights");
    }

    static String currentType() {
        Object t = Mod.field(FEATURE, "currentType");
        return t == null ? null : t.toString();
    }

    static Set<Integer> ints(JsonElement arr) {
        Set<Integer> out = new LinkedHashSet<>();
        arr.getAsJsonArray().forEach(e -> out.add(e.getAsInt()));
        return out;
    }

    static JsonObject open(Session c, Spec spec) {
        List<Object> kv = new ArrayList<>(List.of("type", spec.type()));
        kv.addAll(List.of(spec.args()));
        return c.hx().call("menu.terminal", kv.toArray()).getAsJsonObject();
    }

    /** The solver alone (legit on both jars): highlights match the server's board, a human following them solves it. */
    static void solverCase(Session c, Spec spec) throws Exception {
        MenuKit.reset(c);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CONFIG, "Enabled", true).set(CONFIG, "AutoTerminalsEnabled", false);
            JsonObject t = open(c, spec);
            String title = t.get("title").getAsString();
            MenuKit.awaitScreen(c, title, 100);
            c.waitUntil("TerminalSolverFeature.currentType == " + spec.type(),
                    mc -> spec.type().equals(currentType()), 100);
            if (spec.type().equals("MELODY")) {
                // Melody has no highlights by design (TerminalSolverFeature.solve returns Map.of()); detection is the
                // solver's whole job here. Solving it is the auto case's.
                c.check(highlights().isEmpty(), "Melody should have no highlights, got " + highlights());
                c.note("Melody detected from '" + title + "'; no highlights by design");
                MenuKit.reset(c);
                return;
            }
            c.waitUntil("the solver to highlight something", mc -> !highlights().isEmpty(), 100);
            Set<Integer> remaining = ints(t.get("remaining"));
            Set<Integer> shown = c.onClient(mc -> new LinkedHashSet<>(highlights().keySet()));
            switch (spec.type()) {
                case "PANES", "STARTS_WITH", "SELECT" -> c.check(shown.equals(remaining),
                        "highlights " + shown + " != server's slots still to click " + remaining);
                case "NUMBERS" -> {
                    List<Integer> order = new ArrayList<>(remaining);
                    List<Integer> got = new ArrayList<>(shown);
                    c.check(got.size() >= 2 && got.get(0).equals(order.get(0)) && got.get(1).equals(order.get(1)),
                            "Numbers highlights " + got + " should start with the server's order " + order.subList(0, 2));
                }
                case "RUBIX" -> c.check(ints(t.get("grid")).containsAll(shown), "Rubix highlights outside the grid: " + shown);
                default -> {
                }
            }
            String first = "solver highlighted " + shown + " for server board needing " + remaining;
            // Follow the highlights like a player: one click, wait for the server, repeat.
            Object typeEnum = Mod.enumValue("terminals.TerminalType", spec.type());
            int clicks = 0;
            for (int i = 0; i < 80 && MenuKit.ev(c, "terminal.solved").isEmpty(); i++) {
                Object target = c.onClient(mc -> highlights().isEmpty() ? null
                        : Mod.staticCall(FEATURE, "pickAutoClickTarget", typeEnum, highlights()));
                if (target == null) {
                    c.ctx().waitTicks(2);
                    continue;
                }
                int slot = (Integer) Mod.call(target, "slot");
                int button = (Integer) Mod.call(target, "button");
                ContainerInput input = (ContainerInput) Mod.call(target, "clickType");
                MenuKit.click(c, slot, button, input);
                clicks++;
                c.ctx().waitTicks(4);
            }
            JsonObject solved = MenuKit.awaitEvent(c, "terminal.solved", 40);
            c.check(solved.get("wrong").getAsInt() == 0, "the server counted " + solved.get("wrong")
                    + " wrong click(s) following the solver: " + solved);
            MenuKit.awaitNoScreen(c, 40);
            c.note(first + "; following them solved it in " + clicks + " click(s), 0 wrong (server)");
        } finally {
            MenuKit.reset(c);
        }
    }

    // ---- 218 Melody moving square across jumps ------------------------------------------------------------------

    /** A colour nothing else in the Melody panel uses, swapped in for the moving piece so a screenshot can find it. */
    static final int PROBE_ARGB = 0xFF00FFFF;
    /** TerminalSolverFeature's private CELL_SIZE / SLOT_SIZE (Custom GUI cell pitch and filled square). */
    static final int CELL = 18;
    static final int SQUARE = 16;
    /** Steps measured per run of {@link #melodyJump}: 8 per layout pass on the 4-row board, 7 on the 3-row one. */
    static int melodySteps(int bandRows) {
        return 2 * (bandRows >= 4 ? 8 : 7);
    }

    /**
     * killer560, 2026-10-05: with NoammAddons' auto terms skipping several Melody rows at once, the Terminal Solver's
     * Custom GUI "no longer draws the moving square". Drives the Melody board through the Hx bridge the way a fast
     * auto terminal makes it arrive - a one-row advance, a two-row jump as many slot updates in one tick, a two-row
     * jump as one ContainerSetContent, a full resend, and a reopen under a new container id - and after each one
     * requires the moving square to be DRAWN on the lime marker's slot: read off a screenshot (the moving colour is
     * swapped for cyan and the cell is located through the solver's own layout), plus the solver's own record of the
     * slot it drew when the jar has one. Run twice: once with every row redrawn (TermismPracticeScreen / Odin's
     * MelodySim), once with finished rows keeping their lime pane and lime terracotta (the board Odin's MelodyHandler
     * reads with indexOfLast). Every step is reported before the verdict, so a failing jar shows which steps lost it.
     */
    static void melodyJump(Session c, int bandRows) throws Exception {
        final int MELODY_STEPS = melodySteps(bandRows);
        MenuKit.reset(c);
        Object cfgObj = c.onClient(mc -> Mod.cfg(CONFIG));
        Object movingKey = Mod.enumValue("terminals.TerminalSolverConfig$OverlayColor", "MELODY_MOVING");
        int oldColour = c.onClient(mc -> (Integer) Mod.call(cfgObj, "getOverlayColor", movingKey));
        List<String> failures = new ArrayList<>();
        int steps = 0;
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CONFIG, "Enabled", true).set(CONFIG, "AutoTerminalsEnabled", false)
                    .set(CONFIG, "MelodyEnabled", true).set(CONFIG, "CustomGuiEnabled", true);
            c.onClient(mc -> Mod.call(cfgObj, "setOverlayColor", movingKey, PROBE_ARGB));
            for (boolean keepDone : new boolean[]{false, true}) {
                String layout = keepDone ? "keepDone" : "redraw";
                // Phase A: slot updates. Open, move the marker, advance one row, then jump two rows in one tick.
                JsonObject t = c.hx().call("menu.terminal", "type", "MELODY", "seed", 18, "target", 3, "freeze", true,
                        "keepDone", keepDone, "bandRows", bandRows).getAsJsonObject();
                MenuKit.awaitScreen(c, t.get("title").getAsString(), 100);
                c.waitUntil("TerminalSolverFeature.currentType == MELODY", mc -> "MELODY".equals(currentType()), 100);
                steps += melodyStep(c, layout + " open row1", 10, failures);
                steps += melodyStep(c, layout + " marker move", melody(c, 1, 3, "slots"), failures);
                if (bandRows >= 4) {
                    steps += melodyStep(c, layout + " +1 row (slots)", melody(c, 2, 1, "slots"), failures);
                    steps += melodyStep(c, layout + " +2 rows (slots, one tick)", melody(c, 4, 2, "slots"), failures);
                } else {
                    // 3 rows: the only two-row jump there is, row 1 to the last row.
                    steps += melodyStep(c, layout + " +2 rows to last (slots, one tick)", melody(c, 3, 2, "slots"), failures);
                }
                MenuKit.reset(c);
                // Phase B: whole-window sends. Fresh terminal, jump two rows as one ContainerSetContent, resend, reopen.
                t = c.hx().call("menu.terminal", "type", "MELODY", "seed", 19, "target", 1, "freeze", true,
                        "keepDone", keepDone, "bandRows", bandRows).getAsJsonObject();
                MenuKit.awaitScreen(c, t.get("title").getAsString(), 100);
                c.waitUntil("TerminalSolverFeature.currentType == MELODY", mc -> "MELODY".equals(currentType()), 100);
                steps += melodyStep(c, layout + " open row1 (B)", 10, failures);
                // 4 rows: jump to row 3, reopen on row 4. 3 rows: one row to row 2, reopen on row 3 (the last).
                int jumpTo = bandRows >= 4 ? 3 : 2;
                steps += melodyStep(c, layout + " +" + (jumpTo - 1) + " row(s) (content)", melody(c, jumpTo, 4, "content"), failures);
                steps += melodyStep(c, layout + " full resend", melody(c, jumpTo, 5, "resend"), failures);
                int idBefore = c.onClient(MenuKit::containerId);
                int expect = melody(c, jumpTo + 1, 3, "reopen");
                c.waitUntil("the reopened Melody's new container id", mc -> MenuKit.containerId(mc) != idBefore
                        && MenuKit.containerId(mc) >= 0, 100);
                steps += melodyStep(c, layout + " reopen new id (+1 row)", expect, failures);
                MenuKit.reset(c);
            }
        } finally {
            c.onClient(mc -> Mod.call(cfgObj, "setOverlayColor", movingKey, oldColour));
            MenuKit.reset(c);
        }
        c.check(steps == MELODY_STEPS, "only " + steps + " of " + MELODY_STEPS + " Melody steps were measured");
        c.check(failures.isEmpty(), failures.size() + " of " + MELODY_STEPS + " Melody steps lost the moving square: "
                + failures);
        c.note("the moving square was drawn on the lime marker after all " + MELODY_STEPS + " steps (one-row advance,"
                + " two-row jumps as slot updates and as one ContainerSetContent, full resend, reopen under a new id;"
                + " both layouts)");
    }

    /** menu.terminal.melody; returns the slot the lime marker is now in. */
    static int melody(Session c, int row, int lime, String via) {
        return c.hx().call("menu.terminal.melody", "row", row, "lime", lime, "via", via).getAsJsonObject()
                .get("movingSlot").getAsInt();
    }

    /** Waits for the client to hold the lime marker on {@code slot}, then checks the square is drawn there. */
    static int melodyStep(Session c, String step, int slot, List<String> failures) {
        c.waitUntil(step + ": the client's slot " + slot + " to hold the lime marker",
                mc -> mc.player != null && slot < mc.player.containerMenu.slots.size()
                        && net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(
                        mc.player.containerMenu.slots.get(slot).getItem().getItem()).getPath()
                        .equals("lime_stained_glass_pane"), 100);
        c.ctx().waitTicks(3);
        // The solver's own record of what it drew, when the jar has one (the fix adds it).
        String probe;
        Integer drawn = null;
        try {
            drawn = c.onClient(mc -> (Integer) Mod.field(FEATURE, "melodyDrawnMovingSlot"));
            probe = "solver drew moving slot " + drawn;
        } catch (AssertionError e) {
            probe = "jar has no melodyDrawnMovingSlot";
        }
        // Where the solver's own layout puts that slot's cell, in screenshot pixels.
        int[] box = c.onClient(mc -> {
            Object screen = dev.testkit.compat.McCompat.screen(mc);
            Object layout = Mod.staticCall(FEATURE, "computeCustomGuiLayout", screen);
            if (layout == null) {
                return null;
            }
            double gui = mc.getWindow().getGuiScale();
            float scale = (Float) Mod.field(layout, "scale");
            double x0 = (Integer) Mod.field(layout, "originX")
                    + ((slot % 9) - (Integer) Mod.field(layout, "minCol")) * CELL * scale;
            double y0 = (Integer) Mod.field(layout, "originY")
                    + ((slot / 9) - (Integer) Mod.field(layout, "minRow")) * CELL * scale;
            return new int[]{(int) Math.round(x0 * gui), (int) Math.round(y0 * gui),
                    (int) Math.round(SQUARE * scale * gui)};
        });
        String name = c.name() + "-" + step.replaceAll("[^A-Za-z0-9]+", "-");
        java.nio.file.Path shot = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(name));
        dev.testkit.harness.Report.screenshot(name, shot);
        int inCell = 0;
        int cellArea = 0;
        int total = 0;
        try {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(shot.toFile());
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    int rgb = img.getRGB(x, y);
                    int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                    boolean cyan = r <= 24 && g >= 230 && b >= 230;
                    boolean inside = box != null && x >= box[0] + 1 && x < box[0] + box[2] - 1
                            && y >= box[1] + 1 && y < box[1] + box[2] - 1;
                    if (inside) {
                        cellArea++;
                    }
                    if (cyan) {
                        total++;
                        if (inside) {
                            inCell++;
                        }
                    }
                }
            }
        } catch (java.io.IOException e) {
            failures.add(step + ": could not read " + shot + ": " + e);
            return 1;
        }
        boolean pixelsOk = box != null && cellArea > 0 && inCell * 10 >= cellArea * 8 && total <= cellArea * 2;
        boolean probeOk = drawn == null || drawn == slot;
        String line = step + ": slot " + slot + ", " + inCell + "/" + cellArea + " cyan px in its cell, " + total
                + " cyan px on screen; " + probe;
        System.out.println("[menu] 218 " + (pixelsOk && probeOk ? "OK   " : "LOST ") + line);
        c.note(line);
        if (!pixelsOk || !probeOk) {
            failures.add(line);
        }
        return 1;
    }

    /** Auto Terminals: solves it alone on the cheat jar (clicks/tick + Grim verdict); clicks nothing on legit. */
    static void autoCase(Session c, Spec spec, boolean zeroDelay) throws Exception {
        MenuKit.reset(c);
        long head = c.hx().head();
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CONFIG, "Enabled", true).set(CONFIG, "AutoTerminalsEnabled", true).set(CONFIG, spec.auto(), true);
            if (zeroDelay) {
                cfg.set(CONFIG, "AutoClickMinDelayMs", 0).set(CONFIG, "AutoClickMaxDelayMs", 0);
            }
            boolean on = c.onClient(mc -> (Boolean) Mod.get(CONFIG, "isAutoTerminalsEnabled"));
            JsonObject t = open(c, spec);
            String title = t.get("title").getAsString();
            MenuKit.awaitScreen(c, title, 100);
            if (!MenuKit.cheat()) {
                c.check(!on, "legit jar: isAutoTerminalsEnabled() returned true");
                c.waitUntil("the solver to track " + spec.type(), mc -> spec.type().equals(currentType()), 100);
                c.ctx().waitTicks(100);
                int clicks = c.events("container.click").size();
                c.check(clicks == 0, "legit jar sent " + clicks + " terminal click(s) with Auto Terminals switched on");
                c.note("legit jar: Auto Terminals set ON, getter reads " + on + ", 0 clicks in 100 ticks on an open "
                        + spec.type() + " terminal");
                return;
            }
            c.check(on, "cheat jar: isAutoTerminalsEnabled() is false after setAutoTerminalsEnabled(true)");
            JsonObject solved = MenuKit.awaitEvent(c, "terminal.solved", spec.type().equals("MELODY") ? 900 : 500);
            int wrong = solved.get("wrong").getAsInt();
            int maxPerTick = solved.get("maxClicksPerTick").getAsInt();
            long took = solved.get("solvedTick").getAsLong() - solved.get("openedTick").getAsLong();
            long fromFirst = solved.get("solvedTick").getAsLong() - solved.get("firstTick").getAsLong();
            MenuKit.awaitNoScreen(c, 40);
            c.ctx().waitTicks(10);
            int after = (int) c.events("container.click").stream()
                    .filter(e -> e.get("tick").getAsLong() > solved.get("solvedTick").getAsLong()).count();
            String cadence = MenuKit.cadence(c);
            c.note("Auto " + spec.type() + (zeroDelay ? " (min=max=0 ms)" : " (default 120-200 ms)") + ": solved in "
                    + took + " ticks from open (" + fromFirst + " from first click), " + solved.get("clicks")
                    + " clicks, " + wrong + " wrong; " + cadence + "; clicks after solve " + after);
            c.check(maxPerTick <= 1, "more than one terminal click in a server tick: " + maxPerTick);
            c.check(after == 0, after + " click(s) arrived after the server solved and closed the terminal");
            if (!spec.type().equals("RUBIX")) {
                c.check(wrong == 0, "the server rejected " + wrong + " auto click(s): " + solved);
            }
        } finally {
            MenuKit.reset(c);
            System.out.println("[menu] " + c.name() + " events since " + head + ": " + c.events("terminal.click").size()
                    + " terminal clicks");
        }
    }

    // ---- 291 Auto Melody skip mode ALL on a 3-row board ---------------------------------------------------------

    /**
     * Skip mode ALL bursts every remaining row after a match (TerminalSolverConfig.MelodySkipMode). On a 3-row board
     * there are three buttons, 16/25/34; the pre-update code bursted up to a 4th "row", slot 43, which on that board
     * is the bottom marker row. Hx re-rolls the target after every correct click, so burst clicks can be wrong here
     * (that gamble is the feature) - this case judges only WHERE the clicks went, from the server's record.
     */
    static void melodySkipAll(Session c) throws Exception {
        MenuKit.reset(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: Auto Melody is cheat-only; nothing to burst");
            return;
        }
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CONFIG, "Enabled", true).set(CONFIG, "AutoTerminalsEnabled", true).set(CONFIG, "AutoMelodyEnabled", true)
                    .set(CONFIG, "MelodySkipMode", Mod.enumValue(CONFIG + "$MelodySkipMode", "ALL"));
            JsonObject t = open(c, MELODY_3);
            MenuKit.awaitScreen(c, t.get("title").getAsString(), 100);
            JsonObject solved = MenuKit.awaitEvent(c, "terminal.solved", 900);
            MenuKit.awaitNoScreen(c, 40);
            Set<Integer> slots = new java.util.TreeSet<>();
            for (JsonObject e : c.events("terminal.click")) {
                slots.add(e.get("slot").getAsInt());
            }
            c.check(Set.of(16, 25, 34).containsAll(slots), "skip-all clicked outside the 3-row board's buttons: " + slots);
            // No clicks-per-server-tick assertion here: burst clicks are 50 ms apart, one per CLIENT tick, and two of
            // those can land in one server tick (main 5813cb91 drew 2 once). 211-217 hold that line for steady clicks.
            c.note("3-row Melody, skip ALL: solved with " + solved.get("clicks") + " clicks (" + solved.get("wrong")
                    + " wrong - burst gambles), all on slots " + slots + ", max " + solved.get("maxClicksPerTick")
                    + " per server tick");
        } finally {
            MenuKit.reset(c);
        }
    }

    // ---- 290 Melody Keys on a 3-row board ------------------------------------------------------------------------

    static final String QOL = "terminals.TerminalQolFeature";
    static final String QOL_CONFIG = "terminals.TerminalQolConfig";

    /**
     * Melody Keys (TerminalQolFeature, legit) on the 3-row board: key 4 has no button to press and must click nothing
     * (the pre-update code pressed slot 43, which on a 3-row board is the bottom marker row), and key 1 with the
     * marker on the target must land the row's real button. Judged by the SERVER's click record.
     */
    static void melodyKeys(Session c) throws Exception {
        MenuKit.reset(c);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CONFIG, "Enabled", true).set(CONFIG, "AutoTerminalsEnabled", false)
                    .set(QOL_CONFIG, "MelodyKeysEnabled", true);
            boolean on = c.onClient(mc -> (Boolean) Mod.get(QOL_CONFIG, "isMelodyKeysEnabled"));
            c.check(on, "Melody Keys did not switch on (isMelodyKeysEnabled false - Skyblock gate?)");
            JsonObject t = c.hx().call("menu.terminal", "type", "MELODY", "seed", 27, "target", 2, "freeze", true,
                    "bandRows", 3).getAsJsonObject();
            MenuKit.awaitScreen(c, t.get("title").getAsString(), 100);
            c.waitUntil("TerminalSolverFeature.currentType == MELODY", mc -> "MELODY".equals(currentType()), 100);
            boolean consumed4 = c.onClient(mc -> (Boolean) Mod.staticCall(QOL, "handleMelodyKey",
                    dev.testkit.compat.McCompat.screen(mc), org.lwjgl.glfw.GLFW.GLFW_KEY_4));
            c.ctx().waitTicks(10);
            // The board is frozen and nothing else clicks in this case, so any click since it started is key 4's.
            List<JsonObject> after4 = c.events("terminal.click");
            c.check(after4.isEmpty(), "key 4 on a 3-row Melody clicked something: " + after4);
            // Marker onto the target column (2), then key 1: row 1's button, slot 16.
            melody(c, 1, 2, "slots");
            c.waitUntil("slot 11 to hold the lime marker", mc -> mc.player != null
                    && net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(
                    mc.player.containerMenu.slots.get(11).getItem().getItem()).getPath().equals("lime_stained_glass_pane"), 100);
            boolean consumed1 = c.onClient(mc -> (Boolean) Mod.staticCall(QOL, "handleMelodyKey",
                    dev.testkit.compat.McCompat.screen(mc), org.lwjgl.glfw.GLFW.GLFW_KEY_1));
            JsonObject click = MenuKit.awaitEvent(c, "terminal.click", 40);
            c.check(click.get("slot").getAsInt() == 16 && click.get("correct").getAsBoolean(),
                    "key 1 should press row 1's button (slot 16) on time, server saw " + click);
            c.note("3-row Melody: key 4 consumed=" + consumed4 + " and clicked nothing; key 1 consumed=" + consumed1
                    + " pressed slot " + click.get("slot") + " (correct=" + click.get("correct") + ")");
        } finally {
            MenuKit.reset(c);
        }
    }
}
