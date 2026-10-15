package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.harness.Report;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 75-sim-designer-load: Design a Map's one Load list (mod sim-designer-1015).
 *
 * <p>killer560 (2026-10-15): "Remove the save and load buttons in exchange for one load button. Any map generated and
 * played will show there with a date of when it was created. This is only for generated maps not saved maps from runs.
 * Also make there be a hair more spacing for the text it is really crammed."
 *
 * <ol>
 *   <li>The designer has a Load button and no Save button.</li>
 *   <li>A real run in the run history (seeded, and confirmed listed by the saved-runs code) and an old saved design
 *       (the removed Save's file) are both on disk.</li>
 *   <li>Entrance is picked and generated with the real buttons and Play is clicked: the floor is built for real, then
 *       left.</li>
 *   <li>Back on the designer, Load lists exactly that one floor - "Entrance · generated", today's date drawn on its
 *       row, its map code - and neither the run nor the old design.</li>
 *   <li>The list is read again from the file (the restart path) and still holds it with the same date.</li>
 *   <li>Every list row's two lines have at least 2 px between them, on the Load list and on the room list.</li>
 *   <li>Clicking the row puts that exact floor back on the grid at Entrance; recording it again does not list it
 *       twice.</li>
 * </ol>
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimDesignerLoadTests implements FabricClientGameTest {

    private static final String NAME = "75-sim-designer-load";
    private static final String HISTORY = "com.killer560.hub.roomsim.SimMapHistory";
    private static final String RUN_STORE = "com.killer560.hub.runsummary.RunHistoryStore";
    private static final String RUN_HISTORY = "com.killer560.hub.roomsim.SimRunHistory";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";

    private final List<String> failures = new ArrayList<>();

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        DesignerKit.prepare(ctx);
        try {
            run(ctx);
        } finally {
            leaveWorld(ctx);
            DesignerKit.closeScreen(ctx);
        }
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " problem(s):\n  " + String.join("\n  ", failures));
        }
        println("PASS");
    }

    private void run(ClientGameTestContext ctx) throws RuntimeException {
        // ---- 1. buttons ----------------------------------------------------------------------------------------
        DesignerKit.openEditor(ctx);
        check(DesignerKit.isEditor(ctx), "the map designer did not open");
        List<String> labels = DesignerKit.labels(ctx);
        println("designer controls: " + labels);
        check(!labels.contains("Save"), "the designer still has a Save button");
        check(labels.contains("Load"), "the designer has no Load button");
        boolean hasHistory = ctx.computeOnClient(mc -> {
            try {
                Class.forName(HISTORY);
                return true;
            } catch (ClassNotFoundException e) {
                return false;
            }
        });
        check(hasHistory, "no " + HISTORY + " in the mod under test - nothing records played floors");
        if (!hasHistory) {
            return;
        }

        // ---- 2. what must NOT be listed: a real run, and an old saved design --------------------------------------
        String runLabel = seedRunAndPreset(ctx);
        check(runLabel != null, "the seeded run is not in the saved-runs list - the 'not runs' check would be empty");
        Path file = ModUnderTest.modConfig("killer560smod-sim-map-history.json");
        ctx.runOnClient(mc -> {
            try {
                Files.deleteIfExists(file);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            ModUnderTest.staticCall(HISTORY, "reload");
        });
        DesignerKit.closeScreen(ctx);
        DesignerKit.openEditor(ctx);

        // ---- 3. Entrance, Generate, Play ----------------------------------------------------------------------------
        for (int i = 0; i < 8 && !"Entrance".equals(DesignerKit.floorLabel(ctx)); i++) {
            DesignerKit.click(ctx, DesignerKit.floorLabel(ctx));
        }
        check("Entrance".equals(DesignerKit.floorLabel(ctx)), "could not set the floor button to Entrance");
        check(DesignerKit.click(ctx, "Generate"), "no Generate button");
        Object plan = DesignerKit.generated(ctx);
        check(plan != null, "Generate put no plan on the grid");
        if (plan == null) {
            return;
        }
        String code = DesignerKit.code(ctx, plan);
        long builds = Scenario.simBuildCount(ctx);
        check(DesignerKit.click(ctx, "Play"), "no Play button");
        ctx.waitFor(mc -> mc.level != null, 20 * 60);
        Scenario.awaitSimBuild(ctx, builds);
        ctx.waitTicks(20);
        println("played the generated Entrance floor (" + code.length() + "-char map code)");
        leaveWorld(ctx);

        // ---- 4. the Load list --------------------------------------------------------------------------------------
        DesignerKit.openEditor(ctx);
        check(DesignerKit.click(ctx, "Load"), "no Load button");
        List<String> listed = listed(ctx);
        List<Object> entries = history(ctx);
        String today = LocalDate.now().toString();
        println("Load lists " + listed.size() + ": " + listed);
        check(listed.size() == 1, "Load should list exactly the one floor played, lists " + listed);
        check(!listed.contains("Old Design"), "Load lists the old saved design 'Old Design'");
        for (String l : listed) {
            check(runLabel == null || !l.contains(runLabel), "Load lists the run '" + runLabel + "'");
        }
        long created = -1;
        if (entries.size() == 1) {
            Object e = entries.get(0);
            String title = listed.get(0);
            String date = (String) call(ctx, e, "dateText");
            created = (Long) call(ctx, e, "createdMs");
            check(title.startsWith("Entrance") && title.contains("generated"), "the row reads '" + title + "'");
            check(date.startsWith(today), "the row's date is '" + date + "', not today " + today);
            check(code.equals(call(ctx, e, "code")), "the listed floor's map code is not the one played");
            List<DesignerKit.Run> runs = DesignerKit.texts(ctx);
            boolean dateDrawn = runs.stream().anyMatch(r -> r.text().equals(date));
            check(dateDrawn, "the date '" + date + "' is not drawn on the Load list");
            spacing(runs, title, "Load list");
        }
        Path loadShot = shot(ctx, "load-list");
        println("Load list screenshot " + loadShot);

        // ---- 5. read again from the file -------------------------------------------------------------------------
        check(Files.exists(file), "nothing written to " + file);
        String onDisk = readQuietly(file);
        check(onDisk.contains(code), "the file does not hold the played floor's code");
        ctx.runOnClient(mc -> ModUnderTest.staticCall(HISTORY, "reload"));
        DesignerKit.closeScreen(ctx);
        DesignerKit.openEditor(ctx);
        DesignerKit.click(ctx, "Load");
        List<Object> again = history(ctx);
        check(again.size() == 1, "after a re-read from disk Load lists " + again.size());
        if (again.size() == 1) {
            check((Long) call(ctx, again.get(0), "createdMs") == created, "the date changed across the re-read");
        }

        // ---- 6. room list spacing too ------------------------------------------------------------------------------
        DesignerKit.click(ctx, "Load");   // back to the rooms
        @SuppressWarnings("unchecked")
        List<String> rooms = new ArrayList<>((List<String>) DesignerKit.field(ctx, "listed"));
        if (!rooms.isEmpty()) {
            spacing(DesignerKit.texts(ctx), rooms.get(0), "room list");
        }

        // ---- 7. click the row; record again ----------------------------------------------------------------------
        DesignerKit.click(ctx, "Load");
        int listX = (Integer) DesignerKit.field(ctx, "listX");
        int panelY = (Integer) DesignerKit.field(ctx, "panelY");
        int rowH = rowHeight(ctx);
        // A freshly opened designer is on Floor 7, so loading has to change the floor to the entry's.
        DesignerKit.clickAt(ctx, listX + 20, panelY + 66 + rowH / 2.0, 0);
        Object loaded = DesignerKit.generated(ctx);
        check(loaded != null && code.equals(DesignerKit.code(ctx, loaded)),
                "clicking the row did not put the played floor back on the grid");
        check("Entrance".equals(DesignerKit.floorLabel(ctx)), "after loading, the floor button reads "
                + DesignerKit.floorLabel(ctx));
        ctx.runOnClient(mc -> ModUnderTest.staticCall(HISTORY, "recordGenerated",
                new Class<?>[]{String.class, String.class, String.class, int.class, int.class},
                new Object[]{"E", "Entrance", code, 1, 1}));
        check(historyNow(ctx) == 1, "recording the same floor again listed it twice");
        println("status after loading: '" + DesignerKit.field(ctx, "status") + "'");
    }

    /** A run with a map in the run history, and an old Save file; returns the run's saved-runs label, or null. */
    private String seedRunAndPreset(ClientGameTestContext ctx) {
        try {
            StringBuilder cells = new StringBuilder();
            StringBuilder doors = new StringBuilder();
            for (int i = 0; i < 121; i++) {
                cells.append(i == 0 ? "0" : "-1").append(i < 120 ? "," : "");
                doors.append("0").append(i < 120 ? "," : "");
            }
            String run = "{\"version\":1,\"runs\":[{\"startedAtMs\":1760000000000,\"endedAtMs\":1760000600000,"
                    + "\"floor\":\"F3\",\"totalMs\":600000,\"hypixelTime\":\"10:00\",\"map\":{\"rooms\":[{\"name\":"
                    + "\"Entrance\",\"type\":\"entrance\"}],\"roomCells\":\"" + cells + "\",\"doorCells\":\"" + doors
                    + "\"}}]}";
            Path runs = ModUnderTest.modConfig("killer560smod-runs").resolve("history.json");
            Files.createDirectories(runs.getParent());
            Files.writeString(runs, run, StandardCharsets.UTF_8);
            Path presets = ModUnderTest.modConfig("killer560smod-simmaps.json");
            Files.createDirectories(presets.getParent());
            Files.writeString(presets, "{\"Old Design\":{\"0\":\"Entrance\"}}", StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AssertionError("could not seed the run history / old design", e);
        }
        return ctx.computeOnClient(mc -> {
            ModUnderTest.staticCall(RUN_STORE, "load");
            @SuppressWarnings("unchecked")
            List<String> saved = (List<String>) ModUnderTest.staticCall(RUN_HISTORY, "savedRuns");
            println("saved-runs list after seeding: " + saved);
            return saved.isEmpty() ? null : saved.get(0);
        });
    }

    /** The first line of the row reading {@code title} and the next text below it must not touch. */
    private void spacing(List<DesignerKit.Run> runs, String title, String where) {
        DesignerKit.Run first = null;
        for (DesignerKit.Run r : runs) {
            if (r.text().equals(title)) {
                first = r;
                break;
            }
        }
        if (first == null) {
            check(false, where + ": '" + title + "' is not drawn");
            return;
        }
        DesignerKit.Run second = null;
        for (DesignerKit.Run r : runs) {
            if (r != first && Math.abs(r.x() - first.x()) <= 1 && r.y() > first.y()
                    && (second == null || r.y() < second.y())) {
                second = r;
            }
        }
        if (second == null) {
            check(false, where + ": no second line under '" + title + "'");
            return;
        }
        // In the screen's own units: the runs are boxed after the screen's scale (Auto Scale 0.5 in the test window, so
        // a 9-unit line reads h 5), and the font line is 9 units, so units = px * 9 / h.
        int gap = second.y() - (first.y() + first.h());
        double units = gap * 9.0 / Math.max(1, first.h());
        println(where + ": '" + title + "' at y " + first.y() + " (h " + first.h() + "), '" + second.text()
                + "' at y " + second.y() + " - gap " + gap + " px, " + String.format("%.1f", units) + " units");
        check(gap >= 1 && units >= 1.5, where + ": the two lines of a row are " + gap + " px ("
                + String.format("%.1f", units) + " units) apart - still crammed");
    }

    private static int rowHeight(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            try {
                var f = Class.forName(DesignerKit.EDITOR).getDeclaredField("ROW_H");
                f.setAccessible(true);
                return f.getInt(null);
            } catch (ReflectiveOperationException e) {
                return 22;
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static List<String> listed(ClientGameTestContext ctx) {
        return new ArrayList<>((List<String>) DesignerKit.field(ctx, "listed"));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> history(ClientGameTestContext ctx) {
        return new ArrayList<>((List<Object>) DesignerKit.field(ctx, "history"));
    }

    @SuppressWarnings("unchecked")
    private static int historyNow(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> ((List<Object>) ModUnderTest.staticCall(HISTORY, "entries")).size());
    }

    private static Object call(ClientGameTestContext ctx, Object target, String method) {
        return ctx.computeOnClient(mc -> ModUnderTest.call(target, method, new Class<?>[]{}, new Object[]{}));
    }

    private static String readQuietly(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static void leaveWorld(ClientGameTestContext ctx) {
        boolean inWorld = ctx.computeOnClient(mc -> mc.level != null);
        if (!inWorld) {
            return;
        }
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "leave"));
        ctx.runOnClient(mc -> mc.execute(() -> {
            if (mc.level != null) {
                mc.level.disconnect(net.minecraft.network.chat.Component.literal("scenario over"));
                mc.disconnectWithSavingScreen();
            }
        }));
        ctx.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null);
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> mc.execute(() ->
                McCompat.setScreen(mc, new net.minecraft.client.gui.screens.TitleScreen())));
        ctx.waitFor(mc -> McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.TitleScreen);
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
