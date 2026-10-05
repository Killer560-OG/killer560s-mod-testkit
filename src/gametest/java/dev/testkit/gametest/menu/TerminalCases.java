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
        MenuSuite.test(s, "218-menu-term-melody-jump", TerminalCases::melodyJump);
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
    static final int MELODY_STEPS = 16;

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
    static void melodyJump(Session c) throws Exception {
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
                        "keepDone", keepDone).getAsJsonObject();
                MenuKit.awaitScreen(c, t.get("title").getAsString(), 100);
                c.waitUntil("TerminalSolverFeature.currentType == MELODY", mc -> "MELODY".equals(currentType()), 100);
                steps += melodyStep(c, layout + " open row1", 10, failures);
                steps += melodyStep(c, layout + " marker move", melody(c, 1, 3, "slots"), failures);
                steps += melodyStep(c, layout + " +1 row (slots)", melody(c, 2, 1, "slots"), failures);
                steps += melodyStep(c, layout + " +2 rows (slots, one tick)", melody(c, 4, 2, "slots"), failures);
                MenuKit.reset(c);
                // Phase B: whole-window sends. Fresh terminal, jump two rows as one ContainerSetContent, resend, reopen.
                t = c.hx().call("menu.terminal", "type", "MELODY", "seed", 19, "target", 1, "freeze", true,
                        "keepDone", keepDone).getAsJsonObject();
                MenuKit.awaitScreen(c, t.get("title").getAsString(), 100);
                c.waitUntil("TerminalSolverFeature.currentType == MELODY", mc -> "MELODY".equals(currentType()), 100);
                steps += melodyStep(c, layout + " open row1 (B)", 10, failures);
                steps += melodyStep(c, layout + " +2 rows (content)", melody(c, 3, 4, "content"), failures);
                steps += melodyStep(c, layout + " full resend", melody(c, 3, 5, "resend"), failures);
                int idBefore = c.onClient(MenuKit::containerId);
                int expect = melody(c, 4, 3, "reopen");
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
}
