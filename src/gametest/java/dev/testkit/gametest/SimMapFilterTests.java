package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.harness.Report;

import com.mojang.blaze3d.platform.Window;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 75-sim-map-editor-filters: the map designer's room filters (killer560, 2026-10-06: "in the generate a map thing
 * have a filter section where i can filter based on things like puzzles, room size, secrets in a room, etc.").
 *
 * <p>Runs with {@code -Pscenario=75-sim-map-editor} (the name contains it). Every filter change is made with the
 * REAL mouse - cursor moved and button pressed in window coordinates, so Auto Scale's transform is in the path - on
 * the real screens. What it asserts, each against the mod's own state rather than its say-so:
 * <ol>
 *   <li>the designer's list count changes when a size filter is clicked, and every listed non-puzzle room is then
 *       the allowed size;</li>
 *   <li>Generate with a size filter (1x1) and a puzzle filter (three named puzzles) gives a floor whose puzzles are
 *       all allowed ones (exactly the slider's three) and whose ordinary rooms are all 1x1, with Entrance, Blood,
 *       Fairy and a trap still on it and nothing missed;</li>
 *   <li>one allowed puzzle against a slider of three: one puzzle placed and the note says so;</li>
 *   <li>an impossible filter (no Normal rooms): the floor falls back to every room, is still whole, and says so;</li>
 *   <li>the filters survive a re-read from disk, and Reset filters puts the count back;</li>
 *   <li>no two controls overlap, on either screen, at four window sizes (Auto Scale on and off), and nothing is
 *       drawn on the list or grid; screenshots of both screens.</li>
 * </ol>
 */
public class SimMapFilterTests implements FabricClientGameTest {

    private static final String NAME = "75-sim-map-editor-filters";
    private static final String EDITOR = "com.killer560.hub.roomsim.SimMapEditorScreen";
    private static final String FILTERS = "com.killer560.hub.roomsim.SimRoomFilters";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String HUD_CONFIG = "com.killer560.hub.hud.HudConfig";
    private static final Set<String> REQUIRED = Set.of("ENTRANCE", "BLOOD", "FAIRY", "TRAP");

    private final List<String> failures = new ArrayList<>();

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"));
        ctx.runOnClient(mc -> ModUnderTest.staticCall(FILTERS, "reset"));
        LogTap.install();
        try {
            run(ctx);
        } finally {
            ctx.runOnClient(mc -> {
                McCompat.setScreen(mc, null);
                ModUnderTest.staticCall(FILTERS, "reset");
            });
            ctx.waitTicks(5);
        }
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " problem(s):\n  " + String.join("\n  ", failures));
        }
        println("PASS");
    }

    private void run(ClientGameTestContext ctx) {
        openEditor(ctx);
        int total = intField(ctx, "roomTotal");
        int n0 = listed(ctx).size();
        println("designer opened: " + n0 + " of " + total + " rooms listed");
        check(total > 50 && n0 == total, "with no filters the list should show every usable room, got " + n0
                + " of " + total);

        // ---- 1. a size filter, clicked, changes the list ------------------------------------------------------
        click(ctx, "Filters");
        check(isFilterScreen(ctx), "the Filters button did not open the filter screen");
        if (!isFilterScreen(ctx)) {
            return;
        }
        for (String shape : new String[]{"1x2", "1x3", "1x4", "2x2", "L"}) {
            click(ctx, shape);
        }
        for (String shape : new String[]{"1x2", "1x3", "1x4", "2x2", "L"}) {
            check(!(Boolean) ctx.computeOnClient(mc -> ModUnderTest.staticCall(FILTERS, "isShapeShown",
                    new Class<?>[]{String.class}, new Object[]{shape})), "clicking " + shape + " did not hide it");
        }
        // ---- puzzles: None, then three named ones ---------------------------------------------------------------
        @SuppressWarnings("unchecked")
        List<String> puzzles = (List<String>) ctx.computeOnClient(mc -> ModUnderTest.staticCall(FILTERS, "puzzleNames"));
        check(puzzles.size() >= 4, "only " + puzzles.size() + " puzzle rooms in the library");
        click(ctx, "None");
        List<String> allowed = new ArrayList<>(puzzles.subList(0, Math.min(3, puzzles.size())));
        for (String p : allowed) {
            click(ctx, p);
        }
        int allowedCount = (Integer) ctx.computeOnClient(mc -> ModUnderTest.staticCall(FILTERS, "allowedPuzzleCount"));
        check(allowedCount == allowed.size(), "after None + " + allowed + " the filter allows " + allowedCount
                + " puzzles");
        Path filterShot = shot(ctx, "filter-screen");
        println("filter screen screenshot " + filterShot);
        click(ctx, "Done");
        check(isEditor(ctx), "Done did not return to the designer");

        List<String> list1 = listed(ctx);
        println("after 1x1 only + 3 puzzles: " + list1.size() + " of " + total + " listed");
        check(list1.size() < n0, "the size filter did not shrink the list (" + list1.size() + " of " + n0 + ")");
        check(!list1.isEmpty(), "the size filter emptied the list");
        for (String room : list1) {
            String type = typeKey(ctx, room);
            if ("PUZZLE".equals(type)) {
                check(allowed.contains(room), "puzzle " + room + " is listed but was not allowed");
            } else {
                check("1x1".equals(shapeKey(ctx, room)), room + " (" + shapeKey(ctx, room)
                        + ") is listed under a 1x1-only filter");
            }
        }

        // ---- 2. Generate with those filters --------------------------------------------------------------------
        click(ctx, "Generate");
        Object plan = generated(ctx);
        check(plan != null, "Generate with filters left no plan");
        if (plan != null) {
            String note = (String) call(ctx, plan, "filterNote");
            @SuppressWarnings("unchecked")
            List<String> missed = (List<String>) call(ctx, plan, "missed");
            String[] names = names(ctx, plan);
            int placedPuzzles = (Integer) call(ctx, plan, "placedPuzzles");
            println("filtered Generate: " + names.length + " rooms, " + placedPuzzles + " puzzles, note '" + note
                    + "', missed " + missed + ", status '" + stringField(ctx, "status") + "'");
            check("filtered".equals(note), "a feasible filter set should keep the filtered floor, note was '" + note + "'");
            check(missed.isEmpty(), "the filtered floor misses " + missed);
            Set<String> types = new HashSet<>();
            int puzzlesOn = 0;
            for (String room : names) {
                String type = typeKey(ctx, room);
                types.add(type);
                if ("PUZZLE".equals(type)) {
                    puzzlesOn++;
                    check(allowed.contains(room), "Generate placed puzzle " + room + ", allowed " + allowed);
                } else if (!REQUIRED.contains(type)) {
                    check("1x1".equals(shapeKey(ctx, room)), "Generate placed " + room + " ("
                            + shapeKey(ctx, room) + ") under a 1x1-only filter");
                }
            }
            check(puzzlesOn == 3, "the floor has " + puzzlesOn + " puzzles, the slider asks 3 and 3 are allowed");
            for (String t : new String[]{"ENTRANCE", "BLOOD", "FAIRY", "TRAP"}) {
                check(types.contains(t), "the filtered floor has no " + t);
            }
        }
        Path editorShot = shot(ctx, "designer-filtered");
        println("designer screenshot " + editorShot);

        // ---- 3. fewer puzzles allowed than the slider asks --------------------------------------------------------
        click(ctx, "Filters");
        click(ctx, "None");
        click(ctx, allowed.get(0));
        click(ctx, "Done");
        click(ctx, "Generate");
        plan = generated(ctx);
        if (plan != null) {
            String note = (String) call(ctx, plan, "filterNote");
            int placed = (Integer) call(ctx, plan, "placedPuzzles");
            String[] names = names(ctx, plan);
            List<String> placedNames = new ArrayList<>();
            for (String room : names) {
                if ("PUZZLE".equals(typeKey(ctx, room))) {
                    placedNames.add(room);
                }
            }
            println("one puzzle allowed: placed " + placedNames + ", note '" + note + "', status '"
                    + stringField(ctx, "status") + "'");
            check(note != null && note.startsWith("only 1 puzzle"), "one allowed puzzle against a slider of 3 "
                    + "should say so, note was '" + note + "'");
            check(placed == 1 && placedNames.equals(List.of(allowed.get(0))), "expected exactly "
                    + allowed.get(0) + " placed, got " + placedNames);
            check(stringField(ctx, "status").startsWith("only 1 puzzle"), "the status line does not lead with the "
                    + "puzzle note: '" + stringField(ctx, "status") + "'");
        } else {
            check(false, "Generate with one allowed puzzle left no plan");
        }

        // ---- 4. impossible: no Normal rooms ------------------------------------------------------------------------
        click(ctx, "Filters");
        click(ctx, "Normal");
        boolean normalShown = (Boolean) ctx.computeOnClient(mc -> ModUnderTest.staticCall(FILTERS, "isTypeShown",
                new Class<?>[]{String.class}, new Object[]{"NORMAL"}));
        check(!normalShown, "clicking Normal did not hide normal rooms");
        click(ctx, "Done");
        long chatMark = LogTap.mark();
        click(ctx, "Generate");
        plan = generated(ctx);
        if (plan != null) {
            String note = (String) call(ctx, plan, "filterNote");
            @SuppressWarnings("unchecked")
            List<String> missed = (List<String>) call(ctx, plan, "missed");
            String[] names = names(ctx, plan);
            Set<String> types = new HashSet<>();
            for (String room : names) {
                types.add(typeKey(ctx, room));
            }
            println("no Normal rooms: " + names.length + " rooms, note '" + note + "', missed " + missed
                    + ", types " + types + ", status '" + stringField(ctx, "status") + "'");
            check(note != null && note.startsWith("filters too strict"), "an impossible filter should fall back "
                    + "and say so, note was '" + note + "'");
            check(missed.isEmpty(), "the fallback floor misses " + missed);
            check(names.length >= 21, "the fallback F7 has only " + names.length + " rooms");
            check(types.contains("NORMAL"), "the fallback floor has no normal rooms - it did not fall back");
            check(types.containsAll(List.of("ENTRANCE", "BLOOD", "FAIRY", "TRAP")), "the fallback floor lacks a "
                    + "required room: " + types);
            check(stringField(ctx, "status").startsWith("filters too strict"), "the status line does not say the "
                    + "filters were too strict: '" + stringField(ctx, "status") + "'");
        } else {
            check(false, "the impossible filter produced no floor at all");
        }
        // Not chat: the designer runs from the main menu, where there is no player, and ModChat.send drops every line
        // then (client.player == null). So the status line (asserted above) is what he sees, and the log says why.
        boolean logSaid = false;
        for (String line : LogTap.since(chatMark)) {
            logSaid |= line.contains("room filters cannot make a whole");
        }
        check(logSaid, "no log line said the room filters could not make a whole floor");

        // ---- 5. persistence, and Reset filters --------------------------------------------------------------------
        int beforeReload = listed(ctx).size();
        ctx.runOnClient(mc -> {
            McCompat.setScreen(mc, null);
            ModUnderTest.staticCall(FILTERS, "load");
        });
        ctx.waitTicks(3);
        openEditor(ctx);
        int afterReload = listed(ctx).size();
        boolean stillHidden = !(Boolean) ctx.computeOnClient(mc -> ModUnderTest.staticCall(FILTERS, "isTypeShown",
                new Class<?>[]{String.class}, new Object[]{"NORMAL"}))
                && !(Boolean) ctx.computeOnClient(mc -> ModUnderTest.staticCall(FILTERS, "isShapeShown",
                new Class<?>[]{String.class}, new Object[]{"2x2"}));
        println("re-read from disk and reopened: " + afterReload + " listed (was " + beforeReload + "), filters kept "
                + stillHidden);
        check(stillHidden && afterReload == beforeReload, "the filters did not survive a re-read and reopen ("
                + afterReload + " vs " + beforeReload + ")");
        click(ctx, "Filters");
        click(ctx, "Reset filters");
        int active = (Integer) ctx.computeOnClient(mc -> ModUnderTest.staticCall(FILTERS, "activeCount"));
        check(active == 0, "Reset filters left " + active + " active");
        click(ctx, "Done");
        int afterReset = listed(ctx).size();
        println("after Reset filters: " + afterReset + " of " + total);
        check(afterReset == total, "after Reset filters the list shows " + afterReset + " of " + total);

        // ---- 6. overlap sweep at four window sizes ----------------------------------------------------------------
        // With filters set, so the Filters button carries its "(n)" and the list is narrowed.
        ctx.runOnClient(mc -> {
            ModUnderTest.staticCall(FILTERS, "setShapeShown", new Class<?>[]{String.class, boolean.class},
                    new Object[]{"L", false});
            ModUnderTest.staticCall(FILTERS, "setSecrets", new Class<?>[]{int.class, int.class}, new Object[]{2, 8});
        });
        overlapSweep(ctx);
    }

    // ---- overlap ---------------------------------------------------------------------------------------------------

    private void overlapSweep(ClientGameTestContext ctx) {
        int[] oldWindow = ctx.computeOnClient(mc -> new int[]{mc.getWindow().getScreenWidth(),
                mc.getWindow().getScreenHeight()});
        int oldGui = ctx.computeOnClient(mc -> mc.options.guiScale().get());
        Object hud = ctx.computeOnClient(mc -> ModUnderTest.config(HUD_CONFIG));
        boolean oldAuto = (Boolean) ctx.computeOnClient(mc -> ModUnderTest.call(hud, "isAutoScale",
                new Class<?>[]{}, new Object[]{}));
        // {width, height, GUI scale, Auto Scale}
        int[][] sizes = {{854, 480, 2, 1}, {854, 480, 2, 0}, {1920, 1080, 3, 1}, {1920, 1080, 3, 0}};
        int checked = 0;
        try {
            for (int[] sz : sizes) {
                ctx.runOnClient(mc -> ModUnderTest.set(hud, "setAutoScale", sz[3] == 1));
                ctx.getInput().resizeWindow(sz[0], sz[1]);
                ctx.waitTicks(3);
                ctx.runOnClient(mc -> {
                    mc.options.guiScale().set(sz[2]);
                    mc.resizeGui();
                });
                ctx.waitTicks(3);
                String tag = sz[0] + "x" + sz[1] + "-gui" + sz[2] + (sz[3] == 1 ? "" : "-noautoscale");
                openEditor(ctx);
                checked += overlapCheck(ctx, "designer " + tag, true);
                shot(ctx, "designer-" + tag);
                ctx.runOnClient(mc -> mc.execute(() -> {
                    try {
                        Screen parent = McCompat.screen(mc);
                        Class<?> cls = Class.forName("com.killer560.hub.roomsim.SimRoomFilterScreen");
                        McCompat.setScreen(mc, (Screen) cls.getConstructor(Screen.class).newInstance(parent));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));
                ctx.waitTicks(5);
                checked += overlapCheck(ctx, "filters " + tag, false);
                shot(ctx, "filters-" + tag);
            }
        } finally {
            ctx.runOnClient(mc -> {
                McCompat.setScreen(mc, null);
                ModUnderTest.set(hud, "setAutoScale", oldAuto);
            });
            ctx.getInput().resizeWindow(oldWindow[0], oldWindow[1]);
            ctx.waitTicks(3);
            ctx.runOnClient(mc -> {
                mc.options.guiScale().set(oldGui);
                mc.resizeGui();
            });
            ctx.waitTicks(3);
        }
        println("overlap sweep: " + checked + " visible controls checked over " + sizes.length + " sizes x 2 screens");
        check(checked > 40, "the overlap sweep checked only " + checked + " controls - it measured nothing");
    }

    /** Every visible control against every other, against the window, and (designer) against the list and grid. */
    private int overlapCheck(ClientGameTestContext ctx, String where, boolean designer) {
        List<String> found = new ArrayList<>();
        int n = ctx.computeOnClient(mc -> {
            Screen s = McCompat.screen(mc);
            List<AbstractWidget> ws = new ArrayList<>();
            for (var ch : s.children()) {
                if (ch instanceof AbstractWidget w && w.visible && w.getWidth() > 0 && w.getHeight() > 0) {
                    ws.add(w);
                }
            }
            for (int a = 0; a < ws.size(); a++) {
                AbstractWidget p = ws.get(a);
                if (p.getX() < 0 || p.getY() < 0 || p.getX() + p.getWidth() > s.width
                        || p.getY() + p.getHeight() > s.height) {
                    found.add("'" + label(p) + "' " + box(p) + " runs off the " + s.width + "x" + s.height + " screen");
                }
                for (int b = a + 1; b < ws.size(); b++) {
                    AbstractWidget q = ws.get(b);
                    if (intersects(p.getX(), p.getY(), p.getX() + p.getWidth(), p.getY() + p.getHeight(),
                            q.getX(), q.getY(), q.getX() + q.getWidth(), q.getY() + q.getHeight())) {
                        found.add("'" + label(p) + "' " + box(p) + " overlaps '" + label(q) + "' " + box(q));
                    }
                }
            }
            if (designer) {
                int listX = (Integer) reflect(s, "listX");
                int listW = (Integer) reflect(s, "listW");
                int panelY = (Integer) reflect(s, "panelY");
                int gridX = (Integer) reflect(s, "gridX");
                int gridY = (Integer) reflect(s, "gridY");
                int cell = (Integer) reflect(s, "cell");
                int listBottom;
                try {
                    var m = s.getClass().getDeclaredMethod("listBottom");
                    m.setAccessible(true);
                    listBottom = (Integer) m.invoke(s);
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }
                int[][] areas = {{listX, panelY + 66, listX + listW, listBottom},
                        {gridX, gridY, gridX + cell * 6, gridY + cell * 6}};
                String[] areaNames = {"the room list", "the grid"};
                for (AbstractWidget w : ws) {
                    for (int i = 0; i < areas.length; i++) {
                        int[] r = areas[i];
                        if (intersects(w.getX(), w.getY(), w.getX() + w.getWidth(), w.getY() + w.getHeight(),
                                r[0], r[1], r[2], r[3])) {
                            found.add(areaNames[i] + " " + java.util.Arrays.toString(r) + " is under '" + label(w)
                                    + "' " + box(w));
                        }
                    }
                }
            }
            return ws.size();
        });
        println(where + ": " + n + " visible controls, " + found.size() + " overlap(s)");
        for (String f : found) {
            failures.add(where + ": " + f);
        }
        check(n > 3, where + ": only " + n + " visible controls - the screen did not open");
        return n;
    }

    private static boolean intersects(int ax0, int ay0, int ax1, int ay1, int bx0, int by0, int bx1, int by1) {
        return ax0 < bx1 && bx0 < ax1 && ay0 < by1 && by0 < ay1;
    }

    private static String box(AbstractWidget w) {
        return "[" + w.getX() + "," + w.getY() + " " + w.getWidth() + "x" + w.getHeight() + "]";
    }

    // ---- real input ------------------------------------------------------------------------------------------------

    /** Clicks the visible control whose label (colour codes stripped) is exactly {@code text}, or starts with it. */
    private void click(ClientGameTestContext ctx, String text) {
        double[] at = ctx.computeOnClient(mc -> {
            Screen s = McCompat.screen(mc);
            if (s == null) {
                return null;
            }
            AbstractWidget best = null;
            for (var ch : s.children()) {
                if (ch instanceof AbstractWidget w && w.visible && w.active) {
                    String l = label(w);
                    if (l.equals(text)) {
                        best = w;
                        break;
                    }
                    if (best == null && l.startsWith(text)) {
                        best = w;
                    }
                }
            }
            if (best == null) {
                return null;
            }
            float f = ((Number) ModUnderTest.staticCall("com.killer560.hub.hud.AutoScale", "appliedFactor",
                    new Class<?>[]{Screen.class}, new Object[]{s})).floatValue();
            Window win = mc.getWindow();
            double sx = best.getX() + best.getWidth() / 2.0;
            double sy = best.getY() + best.getHeight() / 2.0;
            return new double[]{sx * f * win.getScreenWidth() / (double) win.getGuiScaledWidth(),
                    sy * f * win.getScreenHeight() / (double) win.getGuiScaledHeight()};
        });
        if (at == null) {
            failures.add("no visible, active control labelled '" + text + "' on " + screenName(ctx));
            return;
        }
        ctx.getInput().setCursorPos(at[0], at[1]);
        ctx.waitTicks(2);
        ctx.getInput().pressMouse(0);
        ctx.waitTicks(4);
    }

    private static String label(AbstractWidget w) {
        return w.getMessage().getString().replaceAll("§.", "").trim();
    }

    // ---- reading the mod -------------------------------------------------------------------------------------------

    private void openEditor(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> mc.execute(() -> {
            try {
                Class<?> cls = Class.forName(EDITOR);
                McCompat.setScreen(mc, (Screen) cls.getConstructor(Screen.class).newInstance((Object) null));
            } catch (Exception e) {
                throw new RuntimeException("could not open the map designer", e);
            }
        }));
        ctx.waitTicks(10);
        check(isEditor(ctx), "the map designer did not open");
    }

    private boolean isEditor(ClientGameTestContext ctx) {
        return screenName(ctx).endsWith("SimMapEditorScreen");
    }

    private boolean isFilterScreen(ClientGameTestContext ctx) {
        return screenName(ctx).endsWith("SimRoomFilterScreen");
    }

    private static String screenName(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> McCompat.screen(mc) == null ? "no screen"
                : McCompat.screen(mc).getClass().getName());
    }

    @SuppressWarnings("unchecked")
    private static List<String> listed(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> new ArrayList<>((List<String>) reflect(McCompat.screen(mc), "listed")));
    }

    private static int intField(ClientGameTestContext ctx, String name) {
        return ctx.computeOnClient(mc -> (Integer) reflect(McCompat.screen(mc), name));
    }

    private static String stringField(ClientGameTestContext ctx, String name) {
        return ctx.computeOnClient(mc -> String.valueOf(reflect(McCompat.screen(mc), name)));
    }

    private static Object generated(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> McCompat.screen(mc) == null ? null : reflect(McCompat.screen(mc), "generated"));
    }

    private static Object call(ClientGameTestContext ctx, Object target, String method) {
        return ctx.computeOnClient(mc -> ModUnderTest.call(target, method, new Class<?>[]{}, new Object[]{}));
    }

    private static String[] names(ClientGameTestContext ctx, Object plan) {
        return ctx.computeOnClient(mc -> {
            Object decoded = ModUnderTest.call(plan, "decoded", new Class<?>[]{}, new Object[]{});
            return (String[]) ModUnderTest.call(decoded, "nameTable", new Class<?>[]{}, new Object[]{});
        });
    }

    private static String typeKey(ClientGameTestContext ctx, String room) {
        return ctx.computeOnClient(mc -> (String) ModUnderTest.staticCall(FILTERS, "typeKey",
                new Class<?>[]{String.class}, new Object[]{room}));
    }

    private static String shapeKey(ClientGameTestContext ctx, String room) {
        return ctx.computeOnClient(mc -> (String) ModUnderTest.staticCall(FILTERS, "shapeKey",
                new Class<?>[]{String.class}, new Object[]{room}));
    }

    private static Object reflect(Object target, String field) {
        try {
            var f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            return f.get(target);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("no field " + field + " on " + target.getClass().getSimpleName(), e);
        }
    }

    private static Path shot(ClientGameTestContext ctx, String what) {
        ctx.waitTicks(3);
        Path p = ctx.takeScreenshot(Report.fileName(NAME + "-" + what));
        Report.screenshot(NAME, p);
        return p;
    }

    private void check(boolean ok, String problem) {
        if (!ok) {
            failures.add(problem);
            println("PROBLEM: " + problem);
        }
    }

    private static void println(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}
