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
 *   <li>A real run in the run history (seeded, and confirmed listed by the saved-runs code) and an old Save file
 *       ({@code killer560smod-simmaps.json}, "Old Design", modified 2026-01-02 03:04) are on disk.</li>
 *   <li>Entrance is generated and played, then filled (a drawing) and played, with the real buttons; both builds finish
 *       and are left.</li>
 *   <li>Load lists exactly three rows, newest first: "Entrance · Drawn" (its cells), "Entrance · Generated" (its map
 *       code, today's date) and "Old Design · Saved design" dated by the old file's modified time (coordinator,
 *       2026-10-15: keep drawn floors marked "Drawn"; bring his old saved designs in once) - and not the run. Every
 *       row's date is drawn.</li>
 *   <li>Re-read from disk: still three (the old design is not brought in twice); right-clicking the saved design and
 *       re-reading leaves it gone, and the old file is untouched.</li>
 *   <li>Every list row's two lines are apart, on the Load list and on the room list.</li>
 *   <li>Clicking the generated row puts that exact floor back on the grid at Entrance; recording it again does not list
 *       it twice.</li>
 * </ol>
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimDesignerLoadTests implements FabricClientGameTest {

    private static final String NAME = "75-sim-designer-load";
    private static final String HISTORY = "com.killer560.hub.roomsim.SimMapHistory";
    private static final String RUN_STORE = "com.killer560.hub.runsummary.RunHistoryStore";
    private static final String RUN_HISTORY = "com.killer560.hub.roomsim.SimRunHistory";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    /** The seeded old Save file's modified time, which its brought-in entry must show. */
    private static final String OLD_DATE = "2026-01-02 03:04";

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

        // ---- 2. a real run (must NOT be listed) and an old Save file (brought in once as a Saved design) -----------
        String runLabel = seedRunAndPreset(ctx);
        Path presets = ModUnderTest.modConfig("killer560smod-simmaps.json");
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

        // ---- 3. Entrance: Generate + Play, then Fill (a drawing) + Play ------------------------------------------------
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
        play(ctx);
        println("played the generated Entrance floor (" + code.length() + "-char map code)");

        DesignerKit.openEditor(ctx);
        for (int i = 0; i < 8 && !"Entrance".equals(DesignerKit.floorLabel(ctx)); i++) {
            DesignerKit.click(ctx, DesignerKit.floorLabel(ctx));
        }
        check(DesignerKit.click(ctx, "Fill"), "no Fill button");
        @SuppressWarnings("unchecked")
        java.util.Map<Integer, String> drawing = new java.util.LinkedHashMap<>(
                (java.util.Map<Integer, String>) DesignerKit.field(ctx, "placements"));
        check(!drawing.isEmpty() && DesignerKit.generated(ctx) == null, "Fill drew nothing to play");
        play(ctx);
        println("played a drawn (Fill) Entrance floor of " + drawing.size() + " room(s)");

        // ---- 4. the Load list --------------------------------------------------------------------------------------
        DesignerKit.openEditor(ctx);
        check(DesignerKit.click(ctx, "Load"), "no Load button");
        List<String> listed = listed(ctx);
        List<Object> entries = history(ctx);
        String today = LocalDate.now().toString();
        println("Load lists " + listed.size() + ": " + listed);
        check(listed.size() == 3, "Load should list the drawn floor, the generated floor and the old saved design, "
                + "lists " + listed);
        for (String l : listed) {
            check(runLabel == null || !l.contains(runLabel), "Load lists the run '" + runLabel + "'");
        }
        long created = -1;
        int generatedRow = -1;
        List<DesignerKit.Run> runs = DesignerKit.texts(ctx);
        for (int i = 0; i < entries.size(); i++) {
            Object e = entries.get(i);
            String title = listed.get(i);
            String date = (String) call(ctx, e, "dateText");
            boolean dateDrawn = runs.stream().anyMatch(r -> r.text().equals(date));
            println("  row " + i + ": '" + title + "' " + date + (dateDrawn ? " (drawn on the row)" : " (NOT drawn)"));
            check(dateDrawn, "the date '" + date + "' of '" + title + "' is not drawn on the Load list");
            if (title.equals("Old Design · Saved design")) {
                check(date.equals(OLD_DATE), "the saved design is dated '" + date + "', not its file's " + OLD_DATE);
                check(i == 2, "the saved design (oldest) is row " + i + ", not last");
            } else if (title.equals("Entrance · Generated")) {
                generatedRow = i;
                created = (Long) call(ctx, e, "createdMs");
                check(date.startsWith(today), "the generated row's date is '" + date + "', not today " + today);
                check(code.equals(call(ctx, e, "code")), "the listed floor's map code is not the one played");
                check(i == 1, "the generated floor is row " + i + ", not second (newest first)");
            } else if (title.equals("Entrance · Drawn")) {
                check(i == 0, "the drawn floor is row " + i + ", not first (newest first)");
                check(drawing.equals(call(ctx, e, "cells")), "the drawn row's cells are not the drawing played");
                spacing(runs, title, "Load list");
            } else {
                check(false, "unexpected row '" + title + "'");
            }
        }
        check(listed.contains("Entrance · Drawn"), "no 'Entrance · Drawn' row for the drawn floor played");
        check(listed.contains("Old Design · Saved design"), "the old saved design was not brought into the list");
        Path loadShot = shot(ctx, "load-list");
        println("Load list screenshot " + loadShot);

        // ---- 5. read again from the file; the old design comes in only once ------------------------------------------
        check(Files.exists(file), "nothing written to " + file);
        String onDisk = readQuietly(file);
        check(onDisk.contains(code), "the file does not hold the played floor's code");
        reopenLoad(ctx);
        List<Object> again = history(ctx);
        check(again.size() == 3, "after a re-read from disk Load lists " + again.size() + " (the old design brought in "
                + "twice?)");
        if (generatedRow >= 0 && again.size() == 3) {
            check((Long) call(ctx, again.get(generatedRow), "createdMs") == created,
                    "the date changed across the re-read");
        }
        // Deleting the brought-in design keeps it deleted across a re-read, and the old file is left alone.
        int listX = (Integer) DesignerKit.field(ctx, "listX");
        int panelY = (Integer) DesignerKit.field(ctx, "panelY");
        int rowH = rowHeight(ctx);
        DesignerKit.clickAt(ctx, listX + 20, panelY + 66 + 2 * rowH + rowH / 2.0, 1);
        reopenLoad(ctx);
        List<String> afterDelete = listed(ctx);
        println("after right-clicking the saved design and a re-read: " + afterDelete);
        check(!afterDelete.contains("Old Design · Saved design") && afterDelete.size() == 2,
                "the deleted saved design came back: " + afterDelete);
        check(readQuietly(presets).contains("Old Design"), "the old Save file was changed or removed");

        // ---- 6. room list spacing too ------------------------------------------------------------------------------
        DesignerKit.click(ctx, "Load");   // back to the rooms
        @SuppressWarnings("unchecked")
        List<String> rooms = new ArrayList<>((List<String>) DesignerKit.field(ctx, "listed"));
        if (!rooms.isEmpty()) {
            spacing(DesignerKit.texts(ctx), rooms.get(0), "room list");
        }

        // ---- 7. click the generated row; record again ----------------------------------------------------------------
        reopenLoad(ctx);
        // A freshly opened designer is on Floor 7, so loading has to change the floor to the entry's.
        DesignerKit.clickAt(ctx, listX + 20, panelY + 66 + 1 * rowH + rowH / 2.0, 0);
        Object loaded = DesignerKit.generated(ctx);
        check(loaded != null && code.equals(DesignerKit.code(ctx, loaded)),
                "clicking the row did not put the played floor back on the grid");
        check("Entrance".equals(DesignerKit.floorLabel(ctx)), "after loading, the floor button reads "
                + DesignerKit.floorLabel(ctx));
        int countBefore = historyNow(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(HISTORY, "recordGenerated",
                new Class<?>[]{String.class, String.class, String.class, int.class, int.class},
                new Object[]{"E", "Entrance", code, 1, 1}));
        check(historyNow(ctx) == countBefore, "recording the same floor again listed it twice");
        println("status after loading: '" + DesignerKit.field(ctx, "status") + "'");
    }

    /** Plays what is on the grid with the real Play button, waits for the build, and leaves the world. */
    private void play(ClientGameTestContext ctx) {
        long builds = Scenario.simBuildCount(ctx);
        check(DesignerKit.click(ctx, "Play"), "no Play button");
        ctx.waitFor(mc -> mc.level != null, 20 * 60);
        Scenario.awaitSimBuild(ctx, builds);
        ctx.waitTicks(20);
        leaveWorld(ctx);
    }

    /** Re-reads the list from disk and opens a fresh designer on Load. */
    private static void reopenLoad(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> ModUnderTest.staticCall(HISTORY, "reload"));
        DesignerKit.closeScreen(ctx);
        DesignerKit.openEditor(ctx);
        DesignerKit.click(ctx, "Load");
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
            // The old file held no dates; the brought-in entry is dated by its modified time.
            Files.setLastModifiedTime(presets, java.nio.file.attribute.FileTime.from(
                    java.time.LocalDateTime.parse(OLD_DATE.replace(' ', 'T')).atZone(java.time.ZoneId.systemDefault())
                            .toInstant()));
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
