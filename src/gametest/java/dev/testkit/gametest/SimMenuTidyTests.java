package dev.testkit.gametest;

import dev.testkit.compat.McCompat;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.state.gui.GuiRenderState;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 97-sim-menu-tidy: two requests for the Dungeon Sim menu (killer560, 2026-10-15).
 *
 * <ol>
 *   <li>Create a New Map: "Remove the fill button, it no longer has a use." At every window below, the designer has no
 *       Fill control; Clear, Load and Play sit on one row, equally wide (within 1) with equal gaps, Clear starting at the
 *       floor button's left edge and Play ending at Generate's right edge; and no two of the screen's controls
 *       intersect.</li>
 *   <li>Load a Room: "sometimes this text can overlap so make that something that can't happen" (his screenshot:
 *       "SecretsCrypts ▼" with Crypts the sort column). At every window size and GUI scale below - the smallest, 320x240
 *       GUI units, included - with Auto Scale on and off, under every menu theme, with each column the sort key in both
 *       directions, scrolled to the top and to the end, every heading and cell text run is measured from what one frame
 *       really draws: no two runs on a line may come within 2 units of each other, and each must lie inside its own
 *       column's bounds (the screen's columnHeaderBounds) and inside the list. The same rule on the designer's room list
 *       and Load list (a seeded floor with a long name beside its date) and on Previous Runs (its count beside Sort and
 *       Floor): no text run that is not a control's label may touch a control or another text run.</li>
 * </ol>
 *
 * <p>Fails on main at the Crypts heading; failures are collected and all reported.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimMenuTidyTests implements FabricClientGameTest {

    private static final String NAME = "97-sim-menu-tidy";
    private static final String MENU = "com.killer560.hub.roomsim.SimMenuScreen";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String HISTORY = "com.killer560.hub.roomsim.SimMapHistory";
    private static final String SORT = "com.killer560.hub.roomsim.SimMenuPrefs$RoomSort";
    private static final String PAGE = "com.killer560.hub.roomsim.SimMenuNav$Page";
    private static final String THEME = "com.killer560.hub.gui.MenuTheme";
    private static final String[] KEYS = {"NAME", "TYPE", "SIZE", "SECRETS", "CRYPTS", "ROUTES"};
    /** The long seeded floor label for the designer's Load list. */
    private static final String LONG_LABEL = "Entrance Practice Floor With A Deliberately Long Name";
    /** Least space between two text runs on one line, in screen units. */
    private static final float MIN_GAP = 2f;

    /** {window width, window height, GUI scale (0 = auto)}. 320x240 at GUI 1 is the smallest GUI Minecraft allows. */
    private static final int[][] WINDOWS = {
            {854, 480, 2}, {854, 480, 1}, {854, 480, 3}, {854, 480, 0}, {320, 240, 1}, {640, 480, 2}, {1280, 720, 2},
            {1280, 720, 3}, {1920, 1080, 2}, {1920, 1080, 4}};

    private final List<String> failures = new ArrayList<>();
    private int tableChecks;
    private int runsMeasured;
    private boolean cryptsArrowSeen;
    private boolean themesDone;

    private record Run(String text, float x0, float y0, float x1, float y1) {
        float w() {
            return x1 - x0;
        }
    }

    private record Box(String label, int x, int y, int w, int h) {
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (SimMapTests.copyRoomsForOthers() < 20) {
            Scenario.skipped(NAME, "needs real room captures and room database");
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.roomdatabase.RoomDatabase",
                "isReady"), 2400);
        resetPrefs();
        seedRuns(ctx);
        Object seeded = ctx.computeOnClient(mc -> ModUnderTest.staticCall(HISTORY, "recordGenerated",
                new Class<?>[]{String.class, String.class, String.class, int.class, int.class},
                new Object[]{"E", LONG_LABEL, "tidy-" + System.nanoTime(), 4, 1}));

        int[] window = ctx.computeOnClient(mc -> new int[]{mc.getWindow().getScreenWidth(),
                mc.getWindow().getScreenHeight(), mc.options.guiScale().get()});
        Object hud = ctx.computeOnClient(mc -> ModUnderTest.config("com.killer560.hub.hud.HudConfig"));
        boolean autoScale = ctx.computeOnClient(mc -> ModUnderTest.getBoolean(hud, "isAutoScale"));
        Object menuCfg = ctx.computeOnClient(mc -> ModUnderTest.config("com.killer560.hub.gui.MenuConfig"));
        Object theme = ctx.computeOnClient(mc -> ModUnderTest.call(menuCfg, "getTheme", new Class<?>[]{},
                new Object[]{}));
        try {
            screenshots(ctx);
            for (boolean auto : new boolean[]{true, false}) {
                ctx.runOnClient(mc -> ModUnderTest.set(hud, "setAutoScale", auto));
                for (int[] w : WINDOWS) {
                    setWindow(ctx, w[0], w[1], w[2]);
                    String tag = w[0] + "x" + w[1] + " gui " + w[2] + (auto ? " auto-scale" : " no-auto-scale");
                    designer(ctx, tag);
                    roomTable(ctx, tag, menuCfg);
                    previousRuns(ctx, tag);
                }
            }
        } finally {
            ctx.runOnClient(mc -> ModUnderTest.set(hud, "setAutoScale", autoScale));
            ctx.runOnClient(mc -> ModUnderTest.call(menuCfg, "setTheme", new Class<?>[]{((Enum<?>) theme).getDeclaringClass()},
                    new Object[]{theme}));
            setWindow(ctx, window[0], window[1], window[2]);
            if (seeded != null) {
                ctx.runOnClient(mc -> ModUnderTest.staticCall(HISTORY, "remove", new Class<?>[]{seeded.getClass()},
                        new Object[]{seeded}));
            }
            resetPrefs();
            ctx.runOnClient(mc -> mc.execute(() ->
                    McCompat.setScreen(mc, new net.minecraft.client.gui.screens.TitleScreen())));
            ctx.waitTicks(5);
        }
        println("measured " + runsMeasured + " table text run(s) over " + tableChecks + " table layout(s); the Crypts "
                + "heading with its sort arrow was drawn: " + cryptsArrowSeen);
        check(tableChecks >= WINDOWS.length * 2 * KEYS.length * 2, "only " + tableChecks + " table layouts were checked");
        check(runsMeasured > tableChecks * 10, "only " + runsMeasured + " text runs were measured - the check saw nothing");
        check(cryptsArrowSeen, "the Crypts heading with its sort arrow was never drawn, so his case was never checked");
        if (!failures.isEmpty()) {
            int shown = Math.min(40, failures.size());
            throw new AssertionError(failures.size() + " problem(s), first " + shown + ":\n  "
                    + String.join("\n  ", failures.subList(0, shown)));
        }
        println("PASS - no Fill button, Clear/Load/Play even, and no table text overlaps or crosses its column");
    }

    // =========================================================================================== designer

    private void designer(ClientGameTestContext ctx, String tag) {
        DesignerKit.openEditor(ctx);
        ctx.waitTicks(3);
        List<Box> boxes = widgets(ctx);
        Box clear = find(boxes, "Clear");
        Box load = find(boxes, "Load");
        Box play = find(boxes, "Play");
        Box gen = find(boxes, "Generate");
        check(find(boxes, "Fill") == null, tag + ": the designer still has a Fill button");
        if (clear == null || load == null || play == null || gen == null) {
            check(false, tag + ": designer is missing Clear/Load/Play/Generate: " + labels(boxes));
        } else {
            Box floor = null;
            for (Box b : boxes) {
                if (b.y() == gen.y() && (floor == null || b.x() < floor.x())) {
                    floor = b;
                }
            }
            check(clear.y() == load.y() && load.y() == play.y(), tag + ": Clear, Load and Play are not on one row");
            int wMin = Math.min(clear.w(), Math.min(load.w(), play.w()));
            int wMax = Math.max(clear.w(), Math.max(load.w(), play.w()));
            check(wMax - wMin <= 1, tag + ": Clear/Load/Play widths " + clear.w() + "/" + load.w() + "/" + play.w());
            int gap1 = load.x() - (clear.x() + clear.w());
            int gap2 = play.x() - (load.x() + load.w());
            check(gap1 == gap2 && gap1 > 0, tag + ": Clear/Load/Play gaps " + gap1 + "/" + gap2);
            check(floor != null && clear.x() == floor.x(), tag + ": Clear starts at x " + clear.x()
                    + ", the floor button at " + (floor == null ? "?" : floor.x()));
            check(play.x() + play.w() == gen.x() + gen.w(), tag + ": Play ends at " + (play.x() + play.w())
                    + ", Generate at " + (gen.x() + gen.w()));
        }
        for (int i = 0; i < boxes.size(); i++) {
            for (int j = i + 1; j < boxes.size(); j++) {
                Box a = boxes.get(i);
                Box b = boxes.get(j);
                if (a.x() < b.x() + b.w() && b.x() < a.x() + a.w() && a.y() < b.y() + b.h() && b.y() < a.y() + a.h()) {
                    check(false, tag + ": designer controls '" + a.label() + "' and '" + b.label() + "' intersect");
                }
            }
        }
        // The room list, then the Load list with the long seeded floor beside its date.
        listRows(ctx, tag + " designer rooms");
        if (DesignerKit.click(ctx, "Load")) {
            ctx.waitTicks(2);
            listRows(ctx, tag + " designer Load list");
            DesignerKit.click(ctx, "Load");
        }
        DesignerKit.closeScreen(ctx);
    }

    /** Every text run inside the designer's list: inside the list, and apart from every other run on its line. */
    private void listRows(ClientGameTestContext ctx, String tag) {
        int listX = (Integer) DesignerKit.field(ctx, "listX");
        int listW = (Integer) DesignerKit.field(ctx, "listW");
        int top = ctx.computeOnClient(mc -> DesignerKit.listTop(McCompat.screen(mc)));
        int bottom = ctx.computeOnClient(mc -> {
            Screen s = McCompat.screen(mc);
            try {
                var m = s.getClass().getDeclaredMethod("listBottom");
                m.setAccessible(true);
                return (Integer) m.invoke(s);
            } catch (ReflectiveOperationException e) {
                return s.height;
            }
        });
        List<Run> runs = new ArrayList<>();
        for (Run r : runs(ctx)) {
            if (r.x0() >= listX - 0.01f && r.x0() < listX + listW && r.y0() >= top && r.y0() < bottom - 4) {
                runs.add(r);
            }
        }
        for (Run r : runs) {
            check(r.x1() <= listX + listW + 0.01f, tag + ": '" + r.text() + "' runs past the list's right edge ("
                    + fmt(r.x1()) + " > " + (listX + listW) + ")");
        }
        apart(runs, tag);
    }

    // =========================================================================================== Load a Room

    private void roomTable(ClientGameTestContext ctx, String tag, Object menuCfg) {
        openPicker(ctx);
        Object[] themes = ctx.computeOnClient(mc -> {
            try {
                return Class.forName(THEME).getEnumConstants();
            } catch (ClassNotFoundException e) {
                return new Object[0];
            }
        });
        // Every theme at the first window; the default theme after that (the themes share one font).
        boolean every = !themesDone;
        themesDone = true;
        for (Object t : themes) {
            if (!every && t != themes[0]) {
                continue;
            }
            ctx.runOnClient(mc -> ModUnderTest.call(menuCfg, "setTheme", new Class<?>[]{((Enum<?>) t).getDeclaringClass()},
                    new Object[]{t}));
            for (String key : KEYS) {
                for (boolean descending : new boolean[]{false, true}) {
                    sortBy(ctx, key, descending);
                    for (boolean end : new boolean[]{false, true}) {
                        tableAt(ctx, tag + " " + t + " sort " + key + (descending ? " desc" : " asc")
                                + (end ? " scrolled to end" : ""), end);
                    }
                }
            }
        }
    }

    private void tableAt(ClientGameTestContext ctx, String tag, boolean scrolledToEnd) {
        ctx.runOnClient(mc -> {
            Screen s = McCompat.screen(mc);
            int max = (Integer) ModUnderTest.call(s, "maxScroll", new Class<?>[]{}, new Object[]{});
            setField(s, "scroll", scrolledToEnd ? max : 0);
        });
        int[] list = ctx.computeOnClient(mc -> (int[]) ModUnderTest.call(McCompat.screen(mc), "roomListBounds",
                new Class<?>[]{}, new Object[]{}));
        int rowsTop = list[1];
        int rowsBottom = list[1] + list[3];
        Map<String, int[]> cols = new LinkedHashMap<>();
        int headY = -1;
        for (String key : KEYS) {
            int[] b = ctx.computeOnClient(mc -> (int[]) ModUnderTest.call(McCompat.screen(mc), "columnHeaderBounds",
                    new Class<?>[]{String.class}, new Object[]{key}));
            if (b != null) {
                cols.put(key, b);
                headY = b[1];
            }
        }
        if (!cols.containsKey("NAME") || headY < 0) {
            check(false, tag + ": no Name column");
            return;
        }
        int listLeft = list[0];
        int listRight = list[0] + list[2] - 6;
        List<Run> table = new ArrayList<>();
        for (Run r : runs(ctx)) {
            boolean inHead = r.y0() >= headY && r.y0() < rowsTop;
            boolean inRows = r.y1() > rowsTop && r.y0() < rowsBottom;
            if ((inHead || inRows) && r.x0() >= listLeft - 40 && r.x0() < list[0] + list[2]) {
                table.add(r);
            }
        }
        tableChecks++;
        runsMeasured += table.size();
        if (table.size() < cols.size()) {
            check(false, tag + ": only " + table.size() + " text run(s) in the table");
            return;
        }
        for (Run r : table) {
            if (r.text().startsWith("Crypts ") && (r.text().contains("▼") || r.text().contains("▲"))) {
                cryptsArrowSeen = true;
            }
            check(r.x0() >= listLeft - 0.01f && r.x1() <= listRight + 0.01f, tag + ": '" + r.text() + "' at "
                    + fmt(r.x0()) + ".." + fmt(r.x1()) + " leaves the list " + listLeft + ".." + listRight);
            // Its own column: the one whose span (with the click margin) holds the run's middle.
            float mid = (r.x0() + r.x1()) / 2;
            String own = null;
            for (Map.Entry<String, int[]> c : cols.entrySet()) {
                int[] b = c.getValue();
                if (mid >= b[0] - 4 && mid < b[0] + b[2] + 4) {
                    own = c.getKey();
                }
            }
            if (own == null) {
                check(false, tag + ": '" + r.text() + "' (" + fmt(r.x0()) + ".." + fmt(r.x1()) + ") is in no column "
                        + spans(cols));
                continue;
            }
            int[] b = cols.get(own);
            check(r.x0() >= b[0] - 0.01f && r.x1() <= b[0] + b[2] + 0.01f, tag + ": '" + r.text() + "' ("
                    + fmt(r.x0()) + ".." + fmt(r.x1()) + ") crosses its " + own + " column " + b[0] + ".." + (b[0] + b[2]));
        }
        apart(table, tag);
    }

    private static String spans(Map<String, int[]> cols) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, int[]> c : cols.entrySet()) {
            sb.append(c.getKey()).append(' ').append(c.getValue()[0]).append("..")
                    .append(c.getValue()[0] + c.getValue()[2]).append(' ');
        }
        return sb.toString().trim();
    }

    // =========================================================================================== Previous Runs

    private void previousRuns(ClientGameTestContext ctx, String tag) {
        openPicker(ctx);
        ctx.runOnClient(mc -> {
            try {
                Object page = enumConstant(PAGE, "PREVIOUS");
                ModUnderTest.call(McCompat.screen(mc), "showPage", new Class<?>[]{((Enum<?>) page).getDeclaringClass()}, new Object[]{page});
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        });
        ctx.waitTicks(2);
        List<Box> boxes = widgets(ctx);
        List<Run> loose = new ArrayList<>();
        for (Run r : runs(ctx)) {
            float mx = (r.x0() + r.x1()) / 2;
            float my = (r.y0() + r.y1()) / 2;
            boolean label = false;
            for (Box b : boxes) {
                if (mx >= b.x() && mx < b.x() + b.w() && my >= b.y() && my < b.y() + b.h()) {
                    label = true;
                }
            }
            if (!label) {
                loose.add(r);
                for (Box b : boxes) {
                    if (r.x0() < b.x() + b.w() && b.x() < r.x1() && r.y0() < b.y() + b.h() && b.y() < r.y1()) {
                        check(false, tag + " Previous Runs: '" + r.text() + "' (" + fmt(r.x0()) + ".." + fmt(r.x1())
                                + ") runs into the '" + b.label() + "' button (" + b.x() + ".." + (b.x() + b.w()) + ")");
                    }
                }
            }
        }
        apart(loose, tag + " Previous Runs");
        DesignerKit.closeScreen(ctx);
    }

    // =========================================================================================== shared

    /** No two runs whose lines overlap vertically may come within {@link #MIN_GAP} of each other across. */
    private void apart(List<Run> runs, String tag) {
        List<Run> sorted = new ArrayList<>(runs);
        sorted.sort(Comparator.comparingDouble(Run::x0));
        for (int i = 0; i < sorted.size(); i++) {
            for (int j = i + 1; j < sorted.size(); j++) {
                Run a = sorted.get(i);
                Run b = sorted.get(j);
                boolean sameLine = a.y0() < b.y1() - 1 && b.y0() < a.y1() - 1;
                if (!sameLine || a.text().isBlank() || b.text().isBlank()) {
                    continue;
                }
                float gap = b.x0() - a.x1();
                if (gap < MIN_GAP) {
                    check(false, tag + ": '" + a.text() + "' (" + fmt(a.x0()) + ".." + fmt(a.x1()) + ") and '"
                            + b.text() + "' (" + fmt(b.x0()) + ".." + fmt(b.x1()) + ") are " + fmt(gap) + " apart");
                }
            }
        }
    }

    /** One frame's text runs, in the open screen's own units. */
    private static List<Run> runs(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            Screen s = McCompat.screen(mc);
            float f = autoScale(s);
            GuiRenderState state = new GuiRenderState();
            GuiGraphicsExtractor g = new GuiGraphicsExtractor(mc, state, -1000, -1000);
            s.extractRenderStateWithTooltipAndSubtitles(g, -1000, -1000, 0.5f);
            List<Run> out = new ArrayList<>();
            state.forEachText(t -> {
                float[] b = TextRuns.screenBox(t, f);
                out.add(new Run(TextRuns.string(t), b[0], b[1], b[2], b[3]));
            });
            return out;
        });
    }

    private static float autoScale(Screen s) {
        try {
            return ((Number) ModUnderTest.staticCall("com.killer560.hub.hud.AutoScale", "appliedFactor",
                    new Class<?>[]{Screen.class}, new Object[]{s})).floatValue();
        } catch (RuntimeException | AssertionError e) {
            return 1f;
        }
    }

    private static List<Box> widgets(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            List<Box> out = new ArrayList<>();
            for (var ch : McCompat.screen(mc).children()) {
                if (ch instanceof AbstractWidget w && w.visible) {
                    out.add(new Box(DesignerKit.label(w), w.getX(), w.getY(), w.getWidth(), w.getHeight()));
                }
            }
            return out;
        });
    }

    private static Box find(List<Box> boxes, String label) {
        for (Box b : boxes) {
            if (b.label().equals(label)) {
                return b;
            }
        }
        return null;
    }

    private static List<String> labels(List<Box> boxes) {
        List<String> out = new ArrayList<>();
        for (Box b : boxes) {
            out.add(b.label());
        }
        return out;
    }

    private static void openPicker(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> mc.execute(() -> {
            try {
                McCompat.setScreen(mc, (Screen) Class.forName(MENU).getMethod("roomPicker", Screen.class)
                        .invoke(null, (Object) null));
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }));
        ctx.waitFor(mc -> McCompat.screen(mc) != null && McCompat.screen(mc).getClass().getName().equals(MENU), 400);
        ctx.waitTicks(3);
        for (int i = 0; i < 100 && listedCount(ctx) == 0; i++) {
            ctx.waitTicks(2);
        }
    }

    @SuppressWarnings("unchecked")
    private static int listedCount(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> ((List<String>) ModUnderTest.call(McCompat.screen(mc), "listedRooms",
                new Class<?>[]{}, new Object[]{})).size());
    }

    private static void sortBy(ClientGameTestContext ctx, String key, boolean descending) {
        ctx.runOnClient(mc -> {
            try {
                Object sort = enumConstant(SORT, key);
                ModUnderTest.call(McCompat.screen(mc), "setRoomSort", new Class<?>[]{((Enum<?>) sort).getDeclaringClass(), Boolean.class},
                        new Object[]{sort, descending});
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumConstant(String cls, String name) throws ClassNotFoundException {
        return Enum.valueOf((Class) Class.forName(cls), name);
    }

    private static void setField(Object target, String name, int value) {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                var f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.setInt(target, value);
                return;
            } catch (NoSuchFieldException e) {
                // the superclass
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
        }
        throw new AssertionError("no field " + name);
    }

    private static void setWindow(ClientGameTestContext ctx, int w, int h, int gui) {
        ctx.getInput().resizeWindow(w, h);
        ctx.waitTicks(3);
        ctx.runOnClient(mc -> {
            mc.options.guiScale().set(gui);
            mc.resizeGui();
        });
        ctx.waitTicks(4);
    }

    /** Load a Room sorted by Crypts (his screenshot) and the designer's button row, at the default window. */
    private void screenshots(ClientGameTestContext ctx) {
        openPicker(ctx);
        sortBy(ctx, "CRYPTS", true);
        ctx.waitTicks(3);
        ctx.takeScreenshot(dev.testkit.harness.Report.fileName(NAME + "-load-a-room-crypts"));
        DesignerKit.closeScreen(ctx);
        DesignerKit.openEditor(ctx);
        ctx.waitTicks(5);
        ctx.takeScreenshot(dev.testkit.harness.Report.fileName(NAME + "-designer"));
        DesignerKit.closeScreen(ctx);
    }

    /** Two runs with maps in the run history, so Previous Runs has cards and its count. */
    private static void seedRuns(ClientGameTestContext ctx) {
        try {
            StringBuilder cells = new StringBuilder();
            StringBuilder doors = new StringBuilder();
            for (int i = 0; i < 121; i++) {
                cells.append(i == 0 ? "0" : "-1").append(i < 120 ? "," : "");
                doors.append("0").append(i < 120 ? "," : "");
            }
            StringBuilder runs = new StringBuilder("{\"version\":1,\"runs\":[");
            for (int k = 0; k < 2; k++) {
                long start = 1760000000000L + k * 3_600_000L;
                runs.append(k == 0 ? "" : ",").append("{\"startedAtMs\":").append(start).append(",\"endedAtMs\":")
                        .append(start + 600000).append(",\"floor\":\"F3\",\"totalMs\":600000,\"hypixelTime\":\"10:00\","
                                + "\"map\":{\"rooms\":[{\"name\":\"Entrance\",\"type\":\"entrance\"}],\"roomCells\":\"")
                        .append(cells).append("\",\"doorCells\":\"").append(doors).append("\"}}");
            }
            runs.append("]}");
            Path file = ModUnderTest.modConfig("killer560smod-runs").resolve("history.json");
            Files.createDirectories(file.getParent());
            Files.writeString(file, runs, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AssertionError("could not seed the run history", e);
        }
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.runsummary.RunHistoryStore", "load"));
    }

    private static void resetPrefs() {
        try {
            Files.deleteIfExists(ModUnderTest.modConfig("killer560smod-sim-menu.json"));
        } catch (Exception ignored) {
            // nothing to forget
        }
    }

    private void check(boolean ok, String problem) {
        if (!ok) {
            failures.add(problem);
            if (failures.size() <= 200) {
                println("PROBLEM: " + problem);
            }
        }
    }

    private static String fmt(float v) {
        return String.format("%.1f", v);
    }

    private static void println(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}
