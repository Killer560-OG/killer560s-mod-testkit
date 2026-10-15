package dev.testkit.gametest;

import dev.testkit.compat.McCompat;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

import java.awt.image.BufferedImage;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 97-sim-savedrun: a saved run on the Dungeon Sim's Previous Runs page, and that it rebuilds facing the right way.
 *
 * <p>killer560 (2026-10-15): "this page should have a small pic of the dungeon map with the info [...] those saved
 * rooms did not save the proper orientation from the real run, rooms have no doors that are aligning properly. Also
 * have it list who was in the party how many secrets each person got what rooms they cleared [...] i should click onto
 * run then it shows more detail about said run."
 *
 * <p>The run is a real floor: the sim generates an F7, the floor's live map is snapshotted the way Run Summary
 * snapshots a real run ({@code RunSummaryFeature.snapshotMap}; an older jar without it gets the same snapshot built
 * here from {@code DungeonLayout}, which is all it ever stored), and that snapshot is saved as a run with a party. The
 * ORACLE for orientation is the generated floor itself: {@code SimRoomIndex}'s rotation for every room before, which
 * the replay must reproduce room for room, and the build's own door audit ("Sim doors: the door at cell ... is backed
 * by N of its two rooms") which must say no more on the replay than on the original.
 *
 * <p>The page part reads pixels: the thumbnail's rectangle on a real screenshot must hold the run map's room colours.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimSavedRunTests implements FabricClientGameTest {

    private static final String NAME = "97-sim-savedrun";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String INDEX = "com.killer560.hub.roomsim.SimRoomIndex";
    private static final String MENU = "com.killer560.hub.roomsim.SimMenuScreen";
    private static final String HISTORY = "com.killer560.hub.roomsim.SimRunHistory";
    private static final String RECORD = "com.killer560.hub.runsummary.RunRecord";
    private static final String STORE = "com.killer560.hub.runsummary.RunHistoryStore";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String DOOR_AUDIT = "Sim doors: the door at cell";

    /** RunMapPainter's room colours (and the run log's before it). */
    private static final int[] ROOM_COLOURS = {0x8E7A5E, 0x2E9B3E, 0xB33A3A, 0x9B4FD1, 0xCC7722, 0xD8C13A, 0xE07FB8};

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
        deletePrefs();
        ctx.runOnClient(mc -> ModUnderTest.staticCall(STORE, "clear"));

        List<String> failures = new ArrayList<>();
        try {
            // ---- the run: a generated F7, snapshotted like a real one -------------------------------------
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter",
                    new Class<?>[]{String.class}, new Object[]{"gametest"}));
            long before = Scenario.simBuildCount(ctx);
            long mark = LogTap.mark();
            ctx.runOnClient(mc -> mc.execute(() -> {
                Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
                ModUnderTest.staticCall(FLOOR_GEN, "generate",
                        new Class<?>[]{Minecraft.class, floor.getClass(), int.class, int.class},
                        new Object[]{mc, floor, 3, 4});
            }));
            ctx.waitFor(mc -> mc.level != null, 2400);
            Scenario.awaitSimBuild(ctx, before);
            ctx.waitTicks(60);
            Map<String, int[]> original = placed(ctx);
            int originalUnbacked = count(LogTap.since(mark), DOOR_AUDIT);
            Object snapshot = ctx.computeOnClient(SimSavedRunTests::snapshot);
            int snapRooms = ((List<?>) call(snapshot, "rooms")).size();
            List<String> recorded = new ArrayList<>();
            for (Object room : (List<?>) call(snapshot, "rooms")) {
                recorded.add(call(room, "name") + "@" + (hasComponent(room, "rotation") ? call(room, "rotation") : "-"));
            }
            System.out.println("[" + NAME + "] generated F7: " + original.size() + " placed room(s), door audit lines "
                    + originalUnbacked + "; snapshot " + snapRooms + " room(s): " + recorded);
            if (original.size() < 10 || snapRooms < 10) {
                throw new AssertionError("the generated floor or its snapshot is too small to test with: placed "
                        + original.size() + ", snapshot " + snapRooms);
            }
            Object run = ctx.computeOnClient(mc -> makeRun(snapshot));
            ctx.runOnClient(mc -> ModUnderTest.staticCall(STORE, "add", new Class<?>[]{run.getClass()},
                    new Object[]{run}));
            teardown(ctx);

            // ---- the page: a card with a thumbnail, and the run's own page --------------------------------
            pagePart(ctx, failures);

            // ---- the replay: every room facing the way the run had it ------------------------------------
            before = Scenario.simBuildCount(ctx);
            mark = LogTap.mark();
            boolean viaPage = buttonLabels(ctx).contains("Load This Run");
            if (viaPage) {
                pressButton(ctx, "Load This Run");
            } else {
                // An older jar: its own path, the run picked by label (the newest - this one).
                ctx.runOnClient(mc -> mc.execute(() -> {
                    @SuppressWarnings("unchecked")
                    List<String> labels = (List<String>) ModUnderTest.staticCall(HISTORY, "savedRuns");
                    ModUnderTest.staticCall(HISTORY, "load", new Class<?>[]{Minecraft.class, String.class},
                            new Object[]{mc, labels.get(0)});
                }));
            }
            ctx.waitFor(mc -> mc.level != null && mc.player != null, 2400);
            Scenario.awaitSimBuild(ctx, before);
            ctx.waitTicks(60);
            Map<String, int[]> replay = placed(ctx);
            int replayUnbacked = count(LogTap.since(mark), DOOR_AUDIT);
            List<String> wrong = new ArrayList<>();
            for (Map.Entry<String, int[]> e : original.entrySet()) {
                int[] got = replay.get(e.getKey());
                if (got == null) {
                    wrong.add(e.getKey() + " missing");
                } else if (got[0] != e.getValue()[0]) {
                    wrong.add(e.getKey() + " " + e.getValue()[0] + "->" + got[0]);
                }
            }
            System.out.println("[" + NAME + "] replay (" + (viaPage ? "Load This Run" : "old label load") + "): "
                    + replay.size() + " placed room(s), " + wrong.size() + " with a different rotation "
                    + (wrong.size() <= 12 ? wrong : wrong.subList(0, 12)) + "; door audit lines " + replayUnbacked
                    + " (original " + originalUnbacked + ")");
            check(failures, wrong.isEmpty(), wrong.size() + " of " + original.size()
                    + " rooms rebuilt at a different rotation from the run: " + wrong);
            check(failures, replayUnbacked <= originalUnbacked, "the replay has " + replayUnbacked
                    + " door(s) not backed by both rooms, the run had " + originalUnbacked);
        } finally {
            teardown(ctx);
            ctx.runOnClient(mc -> {
                try {
                    ModUnderTest.staticCall(STORE, "clear");
                } catch (Throwable ignored) {
                    // cleanup never replaces the verdict
                }
            });
            deletePrefs();
        }
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " problem(s): " + String.join(" | ", failures));
        }
        System.out.println("[" + NAME + "] PASS - the saved run shows as a card with its map, opens a page with the "
                + "party, their rooms and deaths, and rebuilds with every room at the run's rotation and its doors met");
    }

    // =========================================================================================== the page

    private void pagePart(ClientGameTestContext ctx, List<String> failures) {
        ctx.runOnClient(mc -> mc.execute(() -> {
            try {
                McCompat.setScreen(mc, (Screen) Class.forName(MENU).getConstructor(Screen.class)
                        .newInstance((Object) null));
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }));
        ctx.waitFor(mc -> McCompat.screen(mc) != null && McCompat.screen(mc).getClass().getName().equals(MENU), 400);
        ctx.waitTicks(5);
        String nav = buttonLabels(ctx).contains("Previous Runs") ? "Previous Runs" : "Load a Previous Run";
        pressButton(ctx, nav);
        ctx.waitTicks(10);
        boolean cards = ctx.computeOnClient(mc -> hasMethod(McCompat.screen(mc), "listedRuns"));
        check(failures, cards, "Previous Runs has no run cards (the old one-line list)");
        if (!cards) {
            return;
        }
        int listedRuns = ctx.computeOnClient(mc -> ((List<?>) ModUnderTest.call(McCompat.screen(mc), "listedRuns",
                new Class<?>[]{}, new Object[]{})).size());
        int[] thumb = ctx.computeOnClient(mc -> (int[]) ModUnderTest.call(McCompat.screen(mc), "runThumbBounds",
                new Class<?>[]{int.class}, new Object[]{0}));
        int[] card = ctx.computeOnClient(mc -> (int[]) ModUnderTest.call(McCompat.screen(mc), "runCardBounds",
                new Class<?>[]{int.class}, new Object[]{0}));
        int screenW = ctx.computeOnClient(mc -> McCompat.screen(mc).width);
        ctx.waitTicks(5);
        Path shot = ctx.takeScreenshot(dev.testkit.harness.Report.fileName(NAME + "-runs"));
        int[] found = roomPixels(shot, thumb, screenW);
        System.out.println("[" + NAME + "] Previous Runs: " + listedRuns + " card(s); card 0 at "
                + java.util.Arrays.toString(card) + ", thumbnail " + java.util.Arrays.toString(thumb) + " holds "
                + found[0] + " room-colour pixel(s) in " + found[1] + " colour(s) (" + shot.getFileName() + ")");
        check(failures, listedRuns == 1, "Previous Runs lists " + listedRuns + " run(s), expected the 1 saved");
        check(failures, found[0] >= 100 && found[1] >= 3, "card 0's thumbnail holds " + found[0]
                + " room-colour pixel(s) in " + found[1] + " colour(s) - no map drawn there");

        // A click on the card opens the run's page.
        pressAt(ctx, card[0] + card[2] * 0.75, card[1] + card[3] / 2.0);
        ctx.waitTicks(5);
        @SuppressWarnings("unchecked")
        List<String> lines = ctx.computeOnClient(mc -> (List<String>) ModUnderTest.call(McCompat.screen(mc),
                "detailLines", new Class<?>[]{}, new Object[]{}));
        String text = net.minecraft.ChatFormatting.stripFormatting(String.join("\n", lines));
        ctx.takeScreenshot(dev.testkit.harness.Report.fileName(NAME + "-detail"));
        System.out.println("[" + NAME + "] run page, " + lines.size() + " line(s); buttons " + buttonLabels(ctx));
        for (String line : lines) {
            System.out.println("[" + NAME + "]   | " + net.minecraft.ChatFormatting.stripFormatting(line));
        }
        check(failures, !lines.isEmpty() && buttonLabels(ctx).contains("Load This Run"),
                "a click on the run's card did not open its page with Load This Run");
        for (String want : new String[]{"PartyOne", "PartyTwo", "Mage 42", "secrets 17", "Rooms cleared",
                "RoomA, RoomB", "with others: RoomC", "Deaths", "Withermancer"}) {
            check(failures, text != null && text.contains(want), "the run page does not show \"" + want + "\"");
        }
    }

    /** {pixels in a room colour, distinct room colours} inside the thumbnail's rectangle of a screenshot. */
    private static int[] roomPixels(Path shot, int[] rect, int screenWidth) {
        try {
            BufferedImage img = javax.imageio.ImageIO.read(shot.toFile());
            double s = img.getWidth() / (double) screenWidth;
            int x0 = (int) Math.ceil(rect[0] * s);
            int y0 = (int) Math.ceil(rect[1] * s);
            int x1 = (int) Math.floor((rect[0] + rect[2]) * s);
            int y1 = (int) Math.floor((rect[1] + rect[3]) * s);
            int n = 0;
            Set<Integer> colours = new HashSet<>();
            for (int y = Math.max(0, y0); y < Math.min(img.getHeight(), y1); y++) {
                for (int x = Math.max(0, x0); x < Math.min(img.getWidth(), x1); x++) {
                    int rgb = img.getRGB(x, y) & 0xFFFFFF;
                    for (int c : ROOM_COLOURS) {
                        if (rgb == c) {
                            n++;
                            colours.add(c);
                        }
                    }
                }
            }
            return new int[]{n, colours.size()};
        } catch (Exception e) {
            throw new AssertionError("could not read " + shot + ": " + e);
        }
    }

    // =========================================================================================== the run

    /** {@code SimRoomIndex.placed()} as "name@gx,gz" -> {database rotation, paste rotation}. */
    private static Map<String, int[]> placed(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            Map<String, int[]> out = new LinkedHashMap<>();
            for (Object p : (List<?>) ModUnderTest.staticCall(INDEX, "placed")) {
                out.put(call(p, "name") + "@" + call(p, "gridX") + "," + call(p, "gridZ"),
                        new int[]{(Integer) call(p, "rotation"), (Integer) call(p, "pasteRotation")});
            }
            return out;
        });
    }

    /** The floor's map as Run Summary stores it: its own snapshot when the jar has one, else built the old way. */
    private static Object snapshot(Minecraft mc) {
        Object layout = ModUnderTest.staticCall(LAYOUT, "current");
        try {
            Class<?> feature = Class.forName("com.killer560.hub.runsummary.RunSummaryFeature");
            var m = feature.getMethod("snapshotMap", Class.forName(LAYOUT));
            return m.invoke(null, layout);
        } catch (NoSuchMethodException e) {
            // An older jar: the snapshot it stored had no rotation at all.
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("snapshotMap failed", e);
        }
        try {
            int cells = 121;
            int[] rooms = new int[cells];
            int[] doors = new int[cells];
            for (int i = 0; i < cells; i++) {
                rooms[i] = (Integer) call(layout, "roomOfCell", new Class<?>[]{int.class}, i);
                doors[i] = (Integer) call(layout, "doorType", new Class<?>[]{int.class}, i);
            }
            int count = (Integer) call(layout, "roomCount");
            Class<?> mapRoom = Class.forName(RECORD + "$MapRoom");
            List<Object> list = new ArrayList<>();
            for (int id = 0; id < count; id++) {
                Object entry = call(layout, "entry", new Class<?>[]{int.class}, id);
                String type = entry == null ? null : (String) entry.getClass().getField("type").get(entry);
                list.add(mapRoom.getConstructor(String.class, String.class)
                        .newInstance(call(layout, "name", new Class<?>[]{int.class}, id), type));
            }
            return Class.forName(RECORD + "$MapSnapshot").getConstructor(int[].class, int[].class, List.class)
                    .newInstance(rooms, doors, list);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not build a map snapshot", e);
        }
    }

    /** A run record over {@code snapshot}, with a two-player party, built by component name for any jar. */
    private static Object makeRun(Object snapshot) {
        try {
            Class<?> memberClass = Class.forName(RECORD + "$PartyMember");
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("uuid", null);
            one.put("name", "PartyOne");
            one.put("dungeonClass", "Mage");
            one.put("classLevel", 42);
            one.put("soloRooms", 2);
            one.put("stackedRooms", 1);
            one.put("secrets", 17);
            one.put("deaths", 1);
            one.put("soloRoomNames", List.of("RoomA", "RoomB"));
            one.put("stackedRoomNames", List.of("RoomC"));
            one.put("deathCauses", List.of("Withermancer"));
            Map<String, Object> two = new LinkedHashMap<>();
            two.put("name", "PartyTwo");
            two.put("dungeonClass", "Tank");
            two.put("classLevel", 30);
            two.put("soloRooms", 3);
            two.put("stackedRooms", 0);
            two.put("secrets", 9);
            two.put("deaths", 0);
            List<Object> party = List.of(build(memberClass, one), build(memberClass, two));
            long now = System.currentTimeMillis();
            Map<String, Object> run = new LinkedHashMap<>();
            run.put("startedAtMs", now - 192_000L);
            run.put("endedAtMs", now);
            run.put("floor", "F7");
            run.put("masterMode", false);
            run.put("totalMs", 192_000L);
            run.put("hypixelTime", "3m 12s");
            run.put("secretsFound", 26);
            run.put("totalSecrets", 60);
            run.put("crypts", 5);
            run.put("deaths", 1);
            run.put("puzzlesFailed", 0);
            run.put("puzzleCount", 3);
            run.put("hypixelScore", 301);
            run.put("hypixelRank", "S+");
            run.put("dungeonClass", "Mage");
            run.put("partySize", 2);
            run.put("chestProfit", Long.MIN_VALUE);
            run.put("party", party);
            run.put("map", snapshot);
            return build(Class.forName(RECORD), run);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not build a run record", e);
        }
    }

    /** A record from named values; anything not named gets "unknown" (-1, 0L, false, null, empty list). */
    private static Object build(Class<?> recordClass, Map<String, Object> values) throws ReflectiveOperationException {
        RecordComponent[] comps = recordClass.getRecordComponents();
        Class<?>[] types = new Class<?>[comps.length];
        Object[] args = new Object[comps.length];
        for (int i = 0; i < comps.length; i++) {
            types[i] = comps[i].getType();
            String n = comps[i].getName();
            if (values.containsKey(n)) {
                args[i] = values.get(n);
            } else if (types[i] == int.class) {
                args[i] = -1;
            } else if (types[i] == long.class) {
                args[i] = 0L;
            } else if (types[i] == boolean.class) {
                args[i] = false;
            } else if (types[i] == List.class) {
                args[i] = List.of();
            } else {
                args[i] = null;
            }
        }
        Constructor<?> c = recordClass.getDeclaredConstructor(types);
        return c.newInstance(args);
    }

    // =========================================================================================== helpers

    private static Object call(Object target, String method) {
        return ModUnderTest.call(target, method, new Class<?>[]{}, new Object[]{});
    }

    private static Object call(Object target, String method, Class<?>[] types, Object... args) {
        return ModUnderTest.call(target, method, types, args);
    }

    private static boolean hasComponent(Object record, String name) {
        for (RecordComponent c : record.getClass().getRecordComponents()) {
            if (c.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasMethod(Object target, String name) {
        for (var m : target.getClass().getMethods()) {
            if (m.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static int count(List<String> lines, String needle) {
        int n = 0;
        for (String l : lines) {
            if (l.contains(needle)) {
                n++;
            }
        }
        return n;
    }

    private static void check(List<String> failures, boolean ok, String message) {
        if (!ok) {
            failures.add(message);
            System.out.println("[" + NAME + "] FAIL " + message);
        }
    }

    private static List<String> buttonLabels(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            List<String> out = new ArrayList<>();
            Screen s = McCompat.screen(mc);
            if (s != null) {
                for (var child : s.children()) {
                    if (child instanceof AbstractWidget w) {
                        String t = net.minecraft.ChatFormatting.stripFormatting(w.getMessage().getString());
                        out.add(t == null ? w.getMessage().getString() : t);
                    }
                }
            }
            return out;
        });
    }

    private static void pressButton(ClientGameTestContext ctx, String label) {
        String result = ctx.computeOnClient(mc -> {
            Screen s = McCompat.screen(mc);
            for (var child : s.children()) {
                if (child instanceof AbstractWidget w) {
                    String t = net.minecraft.ChatFormatting.stripFormatting(w.getMessage().getString());
                    if (label.equals(t)) {
                        double x = w.getX() + w.getWidth() / 2.0;
                        double y = w.getY() + w.getHeight() / 2.0;
                        s.mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0)), false);
                        if (McCompat.screen(mc) == s) {
                            s.mouseReleased(new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0)));
                        }
                        return "ok";
                    }
                }
            }
            return "no button \"" + label + "\"";
        });
        if (!"ok".equals(result)) {
            throw new AssertionError(result + " on " + buttonLabels(ctx));
        }
        ctx.waitTicks(3);
    }

    private static void pressAt(ClientGameTestContext ctx, double x, double y) {
        ctx.runOnClient(mc -> {
            Screen s = McCompat.screen(mc);
            s.mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0)), false);
            if (McCompat.screen(mc) == s) {
                s.mouseReleased(new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0)));
            }
        });
        ctx.waitTicks(3);
    }

    private static void deletePrefs() {
        try {
            java.nio.file.Files.deleteIfExists(ModUnderTest.modConfig("killer560smod-sim-menu.json"));
        } catch (Exception ignored) {
            // nothing to forget
        }
    }

    private static void teardown(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            try {
                ModUnderTest.staticCall(SIM_STATE, "leave");
            } catch (Throwable ignored) {
                // never let cleanup replace the verdict
            }
        });
        ctx.runOnClient(mc -> mc.execute(() -> {
            if (mc.level != null) {
                mc.level.disconnect(net.minecraft.network.chat.Component.literal("scenario over"));
                mc.disconnectWithSavingScreen();
            }
        }));
        ctx.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null, 1200);
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> mc.execute(() ->
                McCompat.setScreen(mc, new net.minecraft.client.gui.screens.TitleScreen())));
        ctx.waitFor(mc -> McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.TitleScreen, 400);
    }
}
