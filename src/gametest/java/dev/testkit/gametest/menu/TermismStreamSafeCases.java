package dev.testkit.gametest.menu;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Auto Terminals' Stream Safe in Termism (mod termismss-1102; killer560 2026-11-02: "auto terms stream safe doesnt show in
 * termism"). Termism's practice screen is a plain Screen, not a container: it had its own auto-click loop on the Min/Max
 * delays and Fake Cursor drew only over container screens, so the cursor never showed there.
 * <ul>
 *   <li>1102-menu-termism-ss-*, one per terminal type (cheat): Auto Terminals + Stream Safe + Custom GUI on, a
 *       TermismPracticeScreen opened and played to solved. The cursor drew frames, started at the window center, made
 *       moves, and every click was taken with the drawn cursor inside the drawn cell it clicked (the cell's center worked
 *       out here from the screen's own grid origin and the Highlight Scale); no click before the opening settle; the
 *       click count is exactly what the board needed (wrong clicks are no-ops in Termism, so an extra one shows here).
 *       Melody plays four boards under Skip Mode EDGES + Lookahead 2 (his config) and two under ALL: EDGES queues a
 *       burst only from a target on the first or last slot, ALL from every row before the last. A Stream Safe OFF
 *       control board per type solves with no cursor frames.</li>
 *   <li>1102-menu-termism-ss-legit: on a legit jar the same settings neither click nor draw anything in Termism.</li>
 * </ul>
 */
final class TermismStreamSafeCases {

    static final String CONFIG = TerminalCases.CONFIG;
    static final String CURSOR = "terminals.FakeCursor";
    static final String SCREEN = "termism.TermismPracticeScreen";

    private TermismStreamSafeCases() {
    }

    record Kind(String type, String auto) {
    }

    static final List<Kind> KINDS = List.of(new Kind("PANES", "AutoPanesEnabled"), new Kind("RUBIX", "AutoRubixEnabled"),
            new Kind("NUMBERS", "AutoNumbersEnabled"), new Kind("STARTS_WITH", "AutoStartsWithEnabled"),
            new Kind("SELECT", "AutoSelectEnabled"), new Kind("MELODY", "AutoMelodyEnabled"));

    static void register(Session s) {
        for (Kind k : KINDS) {
            MenuSuite.test(s, "1102-menu-termism-ss-" + k.type().toLowerCase().replace("_", ""), c -> streamSafe(c, k));
        }
        MenuSuite.test(s, "1102-menu-termism-ss-legit", TermismStreamSafeCases::legit);
    }

    static Screen open(Session c, String type) {
        return c.onClient(mc -> {
            try {
                Object t = Mod.enumValue("terminals.TerminalType", type);
                Screen s = (Screen) Mod.cls(SCREEN).getConstructor(Screen.class, Mod.cls("terminals.TerminalType")).newInstance(null, t);
                McCompat.setScreen(mc, s);
                return s;
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("cannot open Termism " + type, e);
            }
        });
    }

    static int i(Object target, String f) {
        return ((Number) Mod.field(target, f)).intValue();
    }

    /** What the board needs, read once it has drawn: Rubix the sum of its labels, Melody its rows, the rest the highlights. */
    static int needed(Screen s, String type) {
        if (type.equals("MELODY")) {
            return i(s, "melodyBandRows");
        }
        @SuppressWarnings("unchecked")
        Map<Integer, Object> h = (Map<Integer, Object>) Mod.field(s, "cachedHighlights");
        if (h == null) {
            return -1;
        }
        if (type.equals("NUMBERS")) {
            // Every cell of a fresh Numbers board is a red numbered pane (TermismPracticeScreen#generateNumbers).
            return ((List<?>) Mod.field(s, "cells")).size();
        }
        if (type.equals("RUBIX")) {
            int sum = 0;
            for (Object v : h.values()) {
                String label = (String) Mod.call(v, "label");
                sum += label == null ? 1 : Math.abs(Integer.parseInt(label.trim()));
            }
            return sum;
        }
        return h.size();
    }

    /** One board to solved. @return problems found ("" for none). */
    static String playOne(Session c, Kind k, boolean on, List<String> notes) {
        List<String> problems = new ArrayList<>();
        c.onClient(mc -> Mod.with(CONFIG, "AutoStreamSafe", on));
        Screen s = open(c, k.type());
        long openedMs = System.currentTimeMillis();
        c.ctx().waitTicks(2);
        int need = c.onClient(mc -> needed(s, k.type()));
        double[] center = c.onClient(mc -> new double[]{mc.getWindow().getWidth() / 2.0, mc.getWindow().getHeight() / 2.0});
        boolean sawShowing = false;
        int ticks = 0;
        for (; ticks < 1200; ticks++) {
            if (c.onClient(mc -> (Boolean) Mod.field(s, "solved"))) {
                break;
            }
            if (c.onClient(mc -> (Boolean) Mod.staticCall(CURSOR, "active"))) {
                sawShowing = true;
            }
            c.ctx().waitTicks(1);
        }
        boolean solved = c.onClient(mc -> (Boolean) Mod.field(s, "solved"));
        long solvedMs = c.onClient(mc -> ((Number) Mod.field(s, "solvedAtMs")).longValue() - ((Number) Mod.field(s, "startedAtMs")).longValue());
        int frames = c.onClient(mc -> (Integer) Mod.field(CURSOR, "frames"));
        int count = c.onClient(mc -> (Integer) Mod.field(CURSOR, "clickCount"));
        int moves = c.onClient(mc -> (Integer) Mod.field(CURSOR, "moves"));
        long firstClick = c.onClient(mc -> ((Number) Mod.field(CURSOR, "firstClickAfterOpenMs")).longValue());
        if (!solved) {
            problems.add((on ? "ON" : "OFF") + " board not solved in " + ticks + " ticks");
        }
        if (!on) {
            if (frames > 0 || sawShowing) {
                problems.add("OFF: the cursor drew " + frames + " frames with Stream Safe off");
            }
            notes.add(k.type() + " OFF solved " + solved + " in " + solvedMs + " ms");
            closeTo(c, s);
            return String.join("; ", problems);
        }
        if (!sawShowing || frames < 10) {
            problems.add("ON: cursor showing " + sawShowing + ", drew only " + frames + " frames");
        }
        float sx = c.onClient(mc -> (Float) Mod.field(CURSOR, "startX"));
        float sy = c.onClient(mc -> (Float) Mod.field(CURSOR, "startY"));
        if (Math.abs(sx - center[0]) > 1 || Math.abs(sy - center[1]) > 1) {
            problems.add("ON: cursor started at " + sx + "," + sy + ", not the window center");
        }
        if (moves < 1) {
            problems.add("ON: the cursor never moved");
        }
        if (firstClick >= 0 && firstClick < 480) {
            problems.add("ON: first click " + firstClick + " ms after open, inside the 500 ms opening settle");
        }
        if (count != need) {
            problems.add("ON: " + count + " clicks, the board needed " + need);
        }
        // Every click: the cursor inside the drawn cell of the slot it clicked, aimed at that cell's center.
        List<String> bad = c.onClient(mc -> {
            List<String> out = new ArrayList<>();
            int[] slots = (int[]) Mod.field(CURSOR, "CLICK_SLOT");
            float[] xs = (float[]) Mod.field(CURSOR, "CLICK_X");
            float[] ys = (float[]) Mod.field(CURSOR, "CLICK_Y");
            float[] txs = (float[]) Mod.field(CURSOR, "CLICK_TX");
            float[] tys = (float[]) Mod.field(CURSOR, "CLICK_TY");
            float[] halves = (float[]) Mod.field(CURSOR, "CLICK_HALF");
            double gui = mc.getWindow().getGuiScale();
            int ox = i(s, "gridOriginX");
            int oy = i(s, "gridOriginY");
            boolean custom = (Boolean) Mod.get(CONFIG, "isCustomGuiEnabled");
            float scale = custom ? ((Number) Mod.get(CONFIG, "getScale")).floatValue() : 1f;
            for (int n = 0; n < Math.min(count, slots.length); n++) {
                int slot = slots[n];
                double ex = (ox + ((slot % 9) * 18 + 8) * scale) * gui;
                double ey = (oy + ((slot / 9) * 18 + 8) * scale) * gui;
                double half = 8 * scale * gui;
                if (Math.hypot(txs[n] - ex, tys[n] - ey) > 1.5) {
                    out.add(String.format("click %d slot %d aimed (%.1f,%.1f), cell center (%.1f,%.1f)", n, slot, txs[n], tys[n], ex, ey));
                } else if (Math.abs(xs[n] - ex) > half + 0.6 || Math.abs(ys[n] - ey) > half + 0.6) {
                    out.add(String.format("click %d slot %d: cursor (%.1f,%.1f) outside the cell (%.1f,%.1f +-%.1f)", n, slot, xs[n], ys[n], ex, ey, half));
                }
                if (Math.abs(halves[n] - half) > 0.6) {
                    out.add("click " + n + ": half size " + halves[n] + " vs drawn " + half);
                }
            }
            return out;
        });
        problems.addAll(bad);
        if (k.type().equals("MELODY")) {
            notes.add(String.format("MELODY ON %d clicks (needed %d), edge live %d / bursts %d, middle live %d / bursts %d,"
                            + " burst clicks %d, dropped %d, %d frames, %d ms",
                    count, need, c.onClient(mc -> i(s, "probeEdgeLiveClicks")), c.onClient(mc -> i(s, "probeEdgeBursts")),
                    c.onClient(mc -> i(s, "probeMiddleLiveClicks")), c.onClient(mc -> i(s, "probeMiddleBursts")),
                    c.onClient(mc -> i(s, "probeBurstClicks")), c.onClient(mc -> i(s, "probeBurstsDropped")), frames, solvedMs));
        } else {
            notes.add(String.format("%s ON %d clicks (needed %d), %d moves, %d frames, first click %d ms, solved in %d ms",
                    k.type(), count, need, moves, frames, firstClick, solvedMs));
        }
        closeTo(c, s);
        return String.join("; ", problems);
    }

    static void closeTo(Session c, Screen s) {
        c.onClient(mc -> {
            if (McCompat.screen(mc) == s) {
                McCompat.setScreen(mc, null);
            }
            return null;
        });
        c.ctx().waitTicks(3);
    }

    static void streamSafe(Session c, Kind k) throws Exception {
        MenuKit.reset(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: Auto Terminals is cheat-only (1102-menu-termism-ss-legit covers legit)");
            return;
        }
        List<String> problems = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CONFIG, "Enabled", true).set(CONFIG, "AutoTerminalsEnabled", true).set(CONFIG, k.auto(), true)
                    .set(CONFIG, "CustomGuiEnabled", true).set(CONFIG, "HoverTerminalsEnabled", false)
                    .set(CONFIG, "HoldToClickEnabled", false)
                    .set(CONFIG, "CursorMissChance", 15).set(CONFIG, "CursorOvershootChance", 25)
                    .set(CONFIG, "CursorReactionMs", 130).set(CONFIG, "FakeCursorSpeed", 100)
                    .set(CONFIG, "AutoClickMinDelayMs", 120).set(CONFIG, "AutoClickMaxDelayMs", 200)
                    .set(CONFIG, "MelodyLookaheadClicks", 2)
                    .set(CONFIG, "MelodySkipMode", Mod.enumValue(CONFIG + "$MelodySkipMode", "EDGES"))
                    .set(CONFIG, "AutoStreamSafe", true);
            boolean melody = k.type().equals("MELODY");
            String off = playOne(c, k, false, notes);
            if (!off.isEmpty()) {
                problems.add(off);
            }
            int onRuns = melody ? 4 : 3;
            int edgeLive = 0;
            int edgeBursts = 0;
            int middleBursts = 0;
            for (int run = 0; run < onRuns; run++) {
                String p = playOne(c, k, true, notes);
                if (!p.isEmpty()) {
                    problems.add("run " + run + ": " + p);
                }
                if (run == 0) {
                    shot(c, k);
                }
            }
            if (melody) {
                for (String n : notes) {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("edge live (\\d+) / bursts (\\d+), middle live (\\d+) / bursts (\\d+)").matcher(n);
                    if (m.find()) {
                        edgeLive += Integer.parseInt(m.group(1));
                        edgeBursts += Integer.parseInt(m.group(2));
                        middleBursts += Integer.parseInt(m.group(4));
                    }
                }
                if (middleBursts != 0) {
                    problems.add("EDGES: " + middleBursts + " burst(s) queued from a middle-slot target");
                }
                if (edgeBursts != edgeLive) {
                    problems.add("EDGES: " + edgeLive + " edge-slot live clicks before the last row but " + edgeBursts + " bursts");
                }
                // ALL: every proper click before the last row bursts, whatever the slot.
                c.onClient(mc -> Mod.with(CONFIG, "MelodySkipMode", Mod.enumValue(CONFIG + "$MelodySkipMode", "ALL")));
                int allLive = 0;
                int allBursts = 0;
                for (int run = 0; run < 2; run++) {
                    List<String> allNotes = new ArrayList<>();
                    String p = playOne(c, k, true, allNotes);
                    if (!p.isEmpty()) {
                        problems.add("ALL run " + run + ": " + p);
                    }
                    for (String n : allNotes) {
                        notes.add("ALL " + n);
                        java.util.regex.Matcher m = java.util.regex.Pattern.compile("edge live (\\d+) / bursts (\\d+), middle live (\\d+) / bursts (\\d+)").matcher(n);
                        if (m.find()) {
                            allLive += Integer.parseInt(m.group(1)) + Integer.parseInt(m.group(3));
                            allBursts += Integer.parseInt(m.group(2)) + Integer.parseInt(m.group(4));
                        }
                    }
                }
                if (allLive == 0 || allBursts != allLive) {
                    problems.add("ALL: " + allLive + " live clicks before the last row, " + allBursts + " bursts");
                }
            }
        } finally {
            MenuKit.reset(c);
        }
        c.note(String.join(" | ", notes) + (problems.isEmpty() ? "" : " | problems " + problems));
        c.check(problems.isEmpty(), "problems: " + problems);
    }

    /** A few screenshots of a Stream Safe board being played (the cursor in them), for the report. */
    static void shot(Session c, Kind k) {
        Screen s = open(c, k.type());
        c.ctx().waitTicks(14);
        for (int n = 0; n < 2; n++) {
            String name = c.name() + "-" + n;
            java.nio.file.Path p = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(name));
            dev.testkit.harness.Report.screenshot(name, p);
            c.ctx().waitTicks(5);
        }
        for (int t = 0; t < 1200 && !c.onClient(mc -> (Boolean) Mod.field(s, "solved")); t++) {
            c.ctx().waitTicks(1);
        }
        closeTo(c, s);
    }

    static void legit(Session c) throws Exception {
        MenuKit.reset(c);
        if (MenuKit.cheat()) {
            c.note("cheat jar: the legit check runs on a legit jar");
            return;
        }
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CONFIG, "Enabled", true).set(CONFIG, "AutoTerminalsEnabled", true).set(CONFIG, "AutoPanesEnabled", true)
                    .set(CONFIG, "AutoMelodyEnabled", true).set(CONFIG, "CustomGuiEnabled", true).set(CONFIG, "AutoStreamSafe", true);
            List<String> rows = new ArrayList<>();
            for (String type : new String[]{"PANES", "MELODY"}) {
                Screen s = open(c, type);
                boolean showing = false;
                for (int t = 0; t < 100; t++) {
                    if (c.onClient(mc -> (Boolean) Mod.staticCall(CURSOR, "active"))) {
                        showing = true;
                    }
                    c.ctx().waitTicks(1);
                }
                boolean solved = c.onClient(mc -> (Boolean) Mod.field(s, "solved"));
                int frames = c.onClient(mc -> (Integer) Mod.field(CURSOR, "frames"));
                int clicks = c.onClient(mc -> (Integer) Mod.field(CURSOR, "clickCount"));
                rows.add(type + ": solved " + solved + ", cursor showing " + showing + ", frames " + frames + ", clicks " + clicks);
                c.check(!solved && !showing && frames == 0 && clicks == 0, "legit Termism acted: " + rows);
                closeTo(c, s);
            }
            c.note(String.join("; ", rows));
        } finally {
            MenuKit.reset(c);
        }
    }
}
