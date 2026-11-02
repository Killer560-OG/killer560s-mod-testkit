package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.Report;
import dev.testkit.harness.SuiteVerdict;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 1102-map-* (mod mapcolor-1102), three owner items of 2026-11-02 for the Dungeon Map:
 * <ul>
 *   <li><b>1102-map-funny-same-color</b>: "the dungeon map when using funny map is one color while in green room then once
 *       the door drops it gets alot darker. have it be the same color and not change." Hypixel hands the dungeon map
 *       item over when the run starts; before it every scanned room was drawn in its own color, after it every room the
 *       item shows unopened went two {@code darker()} steps down. On the sim floor of 1101-map-colors (Entrance, New
 *       Trap, Andesite, Fairy, Cell), with Funny Map ON and no map item (the pre-run state), then with a Hypixel-style
 *       map item that shows only the Entrance opened (the run started), every room's fill and the map background must
 *       be the same color within 2 per channel. Premise: the item was read, and the mod itself says at least one
 *       drawn room is unexplored after it (the rooms that used to darken). Screenshots before/after are saved.</li>
 *   <li><b>1102-map-wither-outline</b>: "add an option to change the wither door outline in dungeon map." The locked
 *       wither door (New Trap | Andesite, coal in the doorway, color 119 on the item) is drawn with Wither Door Outline
 *       Color in Wither Door Outline Thickness rings, in the regular style and (cheat) on Funny Map; the default
 *       (amber, 2) is exactly the old two amber rings; OFF draws no outline at all; OFF is the fresh default.</li>
 *   <li><b>1102-map-funny-hides-rows</b>: "if i have funny map on then it doesnt need to show stuff like predictive rooms
 *       ... so have those hidden if it is toggled." The Dungeon Map tab built with Funny Map ON has no Predictive Rooms
 *       or Special Room Guess row; with it OFF both are there; the saved predictive file is byte-identical across both.</li>
 *   <li><b>1102-map-outline-saved</b>: the outline color and thickness are written to killer560smod-livemap.json and
 *       read back by {@code LiveMapConfig.load()}; a fresh config has amber and 2.</li>
 * </ul>
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimMapRunStartTests implements FabricClientGameTest {

    private static final String NAME = "1102-map-run-start";
    private static final String R_SAME = "1102-map-funny-same-color";
    private static final String R_OUTLINE = "1102-map-wither-outline";
    private static final String R_ROWS = "1102-map-funny-hides-rows";
    private static final String R_SAVED = "1102-map-outline-saved";
    private static final String MAP = "livemap.LiveMapConfig";
    private static final String PREDICT = "livemap.predict.PredictiveRoomsConfig";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final int ROOM_GRID = 6;
    private static final int AMBER = 0xFFFFAA00;
    private static final int CYAN = 0xFF00FFFF;
    private static final String[][] ROOMS = {{"Entrance", "1", "1"}, {"New Trap", "2", "1"}, {"Andesite", "3", "1"},
            {"Fairy", "2", "2"}, {"Cell", "3", "2"}};

    private final List<String> all = new ArrayList<>();

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        // First, before any Mod.with: load() replaces the config singleton, and a restore holds the old one.
        try {
            saved(ctx);
        } catch (AssertionError | RuntimeException old) {
            row(R_SAVED, false, "no outline color/thickness settings on this jar: " + old);
        }
        if (SimMapTests.copyRoomsForOthers() < 20) {
            Scenario.skipped(NAME, "needs real room captures and room database");
            return;
        }
        ctx.runOnClient(mc -> Scenario.loadRoomDatabaseNow());
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.roomdatabase.RoomDatabase", "isReady"),
                1000);
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.RoomLibrary", "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.roomsim.RoomLibrary", "isReady"));
        List<AutoCloseable> restore = new ArrayList<>();
        SimMapItemTests.lastOrigin = null;
        try {
            ctx.runOnClient(mc -> {
                restore.add(0, Mod.with("hud.HudConfig", "AutoScale", false));
                restore.add(0, Mod.with(MAP, "Enabled", true));
                restore.add(0, Mod.with(MAP, "RoomLabels", 0));
                restore.add(0, Mod.with(MAP, "ColourByType", true));
                restore.add(0, Mod.with(MAP, "CustomCheckmarks", false));
                restore.add(0, Mod.with(MAP, "FunnyMap", Mod.isCheat()));
                restore.add(0, Mod.with(MAP, "WitherDoorOutline", false));
                SimMapItemTests.tryWith(restore, MAP, "WitherDoorOutlineColor", AMBER); // absent on a jar before 1102
                SimMapItemTests.tryWith(restore, MAP, "WitherDoorOutlineThickness", 2);
                Mod.set(MAP, "setMapThemeName", "REAL");
                restore.add(0, Mod.with(MAP, "DarkenUnopened", 0f));
                restore.add(0, Mod.with("autopuzzles.AutoPuzzlesConfig", "AutoPuzzlesMasterEnabled", false));
                restore.add(0, Mod.with("mapping.MappingConfig", "ExtraInfoEnabled", false));
                restore.add(0, Mod.with(PREDICT, "Enabled", false));
            });
            rows(ctx);
            Map<Integer, String> placements = new LinkedHashMap<>();
            for (String[] r : ROOMS) {
                placements.put(Integer.parseInt(r[2]) * ROOM_GRID + Integer.parseInt(r[1]), r[0]);
            }
            SimMapItemTests.build(ctx, placements);
            ctx.waitTicks(20);

            // Before the run: no map item. The fills of every room and of the background, and a screenshot.
            boolean calibratedBefore = Boolean.TRUE.equals(
                    SimMapItemTests.hook(ctx, "livemap.MapPainter", "mapItemCalibrated"));
            List<int[]> before = SimMapItemTests.allFills(ctx);
            int[] o = SimMapItemTests.lastOrigin;
            Integer[] roomsBefore = roomColours(before, o);
            Integer bgBefore = background(before);
            java.nio.file.Path shotBefore = ctx.takeScreenshot(Report.fileName("1102-map-funny-before"));

            // The wither door, locked (coal in the doorway), for the outline case.
            int witherCell = cell(2, 1, true);
            BlockPos coal = ctx.computeOnClient(mc -> (BlockPos) ModUnderTest.staticCall(LAYOUT, "doorBlock",
                    new Class<?>[]{int.class}, new Object[]{witherCell}));
            AtomicReference<String> placed = new AtomicReference<>();
            ctx.runOnClient(mc -> {
                var server = mc.getSingleplayerServer();
                server.execute(() -> {
                    var sp = server.getPlayerList().getPlayers().get(0);
                    ServerLevel level = (ServerLevel) sp.level();
                    level.setBlockAndUpdate(coal, net.minecraft.world.level.block.Blocks.COAL_BLOCK.defaultBlockState());
                    placed.set("ok");
                });
            });
            ctx.waitFor(mc -> placed.get() != null, 200);
            ctx.waitFor(mc -> !mc.level.getBlockState(coal).isAir(), 200);

            // The run starts: Hypixel hands over the map, showing only the Entrance opened and the wither door.
            byte[] px = new byte[128 * 128];
            SimMapItemTests.room(px, 1, 1, 30);
            SimMapItemTests.door(px, 2, 1, 119);
            SimMapItemTests.giveMap(ctx, px);
            Boolean calibrated = false;
            for (int i = 0; i < 60 && !Boolean.TRUE.equals(calibrated); i++) {
                ctx.waitTicks(5);
                calibrated = SimMapItemTests.hook(ctx, "livemap.MapPainter", "mapItemCalibrated");
            }
            ctx.waitTicks(10);
            List<int[]> after = SimMapItemTests.allFills(ctx);
            Integer[] roomsAfter = roomColours(after, o);
            Integer bgAfter = background(after);
            java.nio.file.Path shotAfter = ctx.takeScreenshot(Report.fileName("1102-map-funny-after"));
            int unexplored = ctx.computeOnClient(mc -> {
                int n = 0;
                for (Object g : (List<?>) Mod.staticCall("livemap.LiveMapFeature", "groupsView")) {
                    if ((Boolean) Mod.staticCall("livemap.MapPainter", "unexplored", g)) {
                        n++;
                    }
                }
                return n;
            });
            same(calibratedBefore, Boolean.TRUE.equals(calibrated), unexplored, roomsBefore, roomsAfter, bgBefore,
                    bgAfter, o, shotBefore, shotAfter);
            if (Boolean.TRUE.equals(calibrated)) {
                outline(ctx, o);
            } else {
                row(R_OUTLINE, false, "premise: the painted dungeon map was never read");
            }
        } catch (Throwable t) {
            all.add("scenario threw: " + t);
            t.printStackTrace(System.out);
        } finally {
            ctx.runOnClient(mc -> {
                McCompat.setScreen(mc, null);
                for (AutoCloseable r : restore) {
                    try {
                        r.close();
                    } catch (Exception e) {
                        println("restore failed: " + e);
                    }
                }
                Mod.call(Mod.cfg(MAP), "save");
            });
            ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimState", "leave"));
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
        if (!all.isEmpty()) {
            throw new AssertionError(String.join(" | ", all));
        }
        println("PASS - every case passed");
    }

    // ------------------------------------------------------------------------------------------------ the cases

    private void same(boolean calBefore, boolean calAfter, int unexplored, Integer[] before, Integer[] after,
                      Integer bgBefore, Integer bgAfter, int[] o, java.nio.file.Path shotBefore,
                      java.nio.file.Path shotAfter) {
        Report.caseStarted(R_SAME);
        if (!Mod.isCheat()) {
            row(R_SAME, true, "legit jar: Funny Map is cheat only; nothing to judge here");
            return;
        }
        StringBuilder sb = new StringBuilder();
        boolean ok = o != null;
        for (int i = 0; i < ROOMS.length; i++) {
            boolean match = close(before[i], after[i]);
            ok &= match;
            sb.append(ROOMS[i][0]).append(' ').append(SimMapItemTests.hexOrNone(before[i])).append("->")
                    .append(SimMapItemTests.hexOrNone(after[i])).append(match ? "" : " DIFFERS").append("; ");
        }
        boolean bgOk = close(bgBefore, bgAfter);
        sb.append("background ").append(SimMapItemTests.hexOrNone(bgBefore)).append("->")
                .append(SimMapItemTests.hexOrNone(bgAfter)).append(bgOk ? "" : " DIFFERS").append("; ");
        boolean premise = !calBefore && calAfter && unexplored > 0;
        sb.append(String.format(Locale.ROOT, "map item read before %s, after %s; %d room(s) unexplored after; shots %s, %s",
                calBefore, calAfter, unexplored, shotBefore, shotAfter));
        row(R_SAME, premise && ok && bgOk, (premise ? "" : "PREMISE not met; ") + sb);
    }

    private void outline(ClientGameTestContext ctx, int[] o) {
        Report.caseStarted(R_OUTLINE);
        int[] door = gapCentre(o, 2, 1, true);
        boolean fresh = ctx.computeOnClient(mc -> {
            try {
                var k = Mod.cls(MAP).getDeclaredConstructor();
                k.setAccessible(true);
                Object c = k.newInstance();
                return (Boolean) Mod.call(c, "isWitherDoorOutline")
                        || (Integer) Mod.call(c, "getWitherDoorOutlineColor") != AMBER
                        || (Integer) Mod.call(c, "getWitherDoorOutlineThickness") != 2;
            } catch (ReflectiveOperationException e) {
                return true;
            }
        });
        StringBuilder sb = new StringBuilder();
        boolean ok = !fresh;
        sb.append("fresh config off/amber/2: ").append(!fresh).append("; ");
        List<Boolean> styles = Mod.isCheat() ? List.of(false, true) : List.of(false);
        for (boolean funny : styles) {
            ctx.runOnClient(mc -> Mod.set(MAP, "setFunnyMap", funny));
            int off = count(ctx, door, o, false, CYAN, 3) + count(ctx, door, o, false, AMBER, 2);
            int def = count(ctx, door, o, true, AMBER, 2);
            int one = count(ctx, door, o, true, CYAN, 1);
            int three = count(ctx, door, o, true, CYAN, 3);
            int amberWithCyan = countColour(ctx, door, o, AMBER);
            boolean styleOk = off == 0 && one > 0 && three == 3 * one && def == 2 * one && amberWithCyan == 0;
            ok &= styleOk;
            sb.append(String.format(Locale.ROOT, "%s: off %d outline part(s), default amber x2 %d, cyan x1 %d, cyan x3 %d, "
                    + "amber while cyan %d%s; ", funny ? "Funny Map" : "regular", off, def, one, three, amberWithCyan,
                    styleOk ? "" : " WRONG"));
        }
        row(R_OUTLINE, ok, sb.toString());
    }

    /** Outline parts of {@code colour} near the door with the outline on/off at that color and thickness. */
    private int count(ClientGameTestContext ctx, int[] door, int[] o, boolean on, int colour, int thickness) {
        ctx.runOnClient(mc -> {
            Mod.set(MAP, "setWitherDoorOutline", on);
            Mod.set(MAP, "setWitherDoorOutlineColor", colour);
            Mod.set(MAP, "setWitherDoorOutlineThickness", thickness);
        });
        ctx.waitTicks(2);
        return countColour(ctx, door, o, colour);
    }

    private int countColour(ClientGameTestContext ctx, int[] door, int[] o, int colour) {
        int n = 0;
        int pitch = o[2];
        for (int[] f : SimMapItemTests.allFills(ctx)) {
            if (f[4] == colour && Math.abs((f[0] + f[2]) / 2 - door[0]) <= pitch / 2
                    && Math.abs((f[1] + f[3]) / 2 - door[1]) <= pitch / 2) {
                n++;
            }
        }
        return n;
    }

    private void rows(ClientGameTestContext ctx) {
        Report.caseStarted(R_ROWS);
        String fileBefore = predictFile();
        Object[] valuesBefore = ctx.computeOnClient(mc -> new Object[]{Mod.get(PREDICT, "isEnabledRaw"),
                Mod.get(PREDICT, "isSpecialGuessRaw"), Mod.get(MAP, "isFunnyMapRaw")});
        List<String> withFunny = ctx.computeOnClient(mc -> {
            Mod.set(MAP, "setFunnyMap", true);
            return labels();
        });
        List<String> without = ctx.computeOnClient(mc -> {
            Mod.set(MAP, "setFunnyMap", false);
            return labels();
        });
        ctx.runOnClient(mc -> Mod.set(MAP, "setFunnyMap", valuesBefore[2]));
        String fileAfter = predictFile();
        Object[] valuesAfter = ctx.computeOnClient(mc -> new Object[]{Mod.get(PREDICT, "isEnabledRaw"),
                Mod.get(PREDICT, "isSpecialGuessRaw")});
        boolean predOn = has(withFunny, "Predictive Rooms");
        boolean guessOn = has(withFunny, "Special Room Guess");
        boolean predOff = has(without, "Predictive Rooms");
        boolean guessOff = has(without, "Special Room Guess");
        boolean kept = valuesBefore[0].equals(valuesAfter[0]) && valuesBefore[1].equals(valuesAfter[1])
                && fileBefore.equals(fileAfter);
        // On the legit jar Funny Map does nothing, so the rows stay.
        boolean hiddenOk = Mod.isCheat() ? !predOn && !guessOn : predOn && guessOn;
        boolean pass = hiddenOk && predOff && guessOff && kept && withFunny.size() > 10;
        row(R_ROWS, pass, String.format(Locale.ROOT, "Funny Map ON: Predictive Rooms %s, Special Room Guess %s (%d rows); "
                        + "OFF: %s, %s (%d rows); saved values %s/%s -> %s/%s, predict file unchanged %s",
                predOn, guessOn, withFunny.size(), predOff, guessOff, without.size(), valuesBefore[0], valuesBefore[1],
                valuesAfter[0], valuesAfter[1], fileBefore.equals(fileAfter)));
    }

    private static List<String> labels() {
        Object tab;
        try {
            var k = Mod.cls("gui.tab.LiveMapTab").getDeclaredConstructor();
            k.setAccessible(true);
            tab = k.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot make the Dungeon Map tab", e);
        }
        List<String> out = new ArrayList<>();
        Runnable rebuild = () -> { };
        for (Object w : (List<?>) Mod.call(tab, "buildWidgets", 0, 0, 300, rebuild)) {
            out.add(((AbstractWidget) w).getMessage().getString());
        }
        return out;
    }

    private static boolean has(List<String> labels, String label) {
        for (String l : labels) {
            if (l.startsWith(label)) {
                return true;
            }
        }
        return false;
    }

    private static String predictFile() {
        try {
            java.nio.file.Path p = ModUnderTest.modConfig("killer560smod-livemap-predict.json");
            return Files.exists(p) ? Files.readString(p) : "(none)";
        } catch (Exception e) {
            return "(unreadable: " + e + ")";
        }
    }

    private void saved(ClientGameTestContext ctx) {
        Report.caseStarted(R_SAVED);
        Object[] got = ctx.computeOnClient(mc -> {
            Object cfg = Mod.cfg(MAP);
            Object oldColour = Mod.call(cfg, "getWitherDoorOutlineColor");
            Object oldThick = Mod.call(cfg, "getWitherDoorOutlineThickness");
            Mod.call(cfg, "setWitherDoorOutlineColor", 0xFF123456);
            Mod.call(cfg, "setWitherDoorOutlineThickness", 3);
            Mod.call(cfg, "save");
            Mod.staticCall(MAP, "load");
            Object back = Mod.cfg(MAP);
            Object[] r = {Mod.call(back, "getWitherDoorOutlineColor"), Mod.call(back, "getWitherDoorOutlineThickness"),
                    back != cfg};
            Mod.call(back, "setWitherDoorOutlineColor", oldColour);
            Mod.call(back, "setWitherDoorOutlineThickness", oldThick);
            return r;
        });
        String file;
        try {
            java.nio.file.Path p = ModUnderTest.modConfig("killer560smod-livemap.json");
            file = Files.exists(p) ? Files.readString(p).replaceAll("\\s+", "") : "(no file at " + p + ")";
        } catch (Exception e) {
            file = "(unreadable: " + e + ")";
        }
        ctx.runOnClient(mc -> Mod.call(Mod.cfg(MAP), "save"));
        boolean inFile = file.contains("\"witherDoorOutlineColor\":" + 0xFF123456)
                && file.contains("\"witherDoorOutlineThickness\":3");
        boolean pass = Integer.valueOf(0xFF123456).equals(got[0]) && Integer.valueOf(3).equals(got[1])
                && Boolean.TRUE.equals(got[2]) && inFile;
        row(R_SAVED, pass, String.format(Locale.ROOT, "read back color %s thickness %s (reloaded instance %s); in file %s",
                got[0] instanceof Integer c ? String.format("#%08X", c) : got[0], got[1], got[2], inFile));
    }

    // ------------------------------------------------------------------------------------------------- geometry

    private static Integer[] roomColours(List<int[]> fills, int[] o) {
        Integer[] out = new Integer[ROOMS.length];
        if (o == null) {
            return out;
        }
        for (int i = 0; i < ROOMS.length; i++) {
            int col = Integer.parseInt(ROOMS[i][1]);
            int rowN = Integer.parseInt(ROOMS[i][2]);
            int[] p = {o[0] + (col - 1) * o[2] + o[3] / 2, o[1] + (rowN - 1) * o[2] + o[3] / 2};
            for (int[] f : fills) {
                int w = f[2] - f[0];
                int h = f[3] - f[1];
                if (w >= 20 && h >= 20 && w < o[2] * 3 && f[0] <= p[0] && f[2] > p[0] && f[1] <= p[1] && f[3] > p[1]) {
                    out[i] = f[4]; // the last room-sized fill over the room's middle
                }
            }
        }
        return out;
    }

    /** The map background: the largest fill drawn. */
    private static Integer background(List<int[]> fills) {
        int[] best = null;
        for (int[] f : fills) {
            if (best == null || (long) (f[2] - f[0]) * (f[3] - f[1]) > (long) (best[2] - best[0]) * (best[3] - best[1])) {
                best = f;
            }
        }
        return best == null ? null : best[4];
    }

    private static boolean close(Integer a, Integer b) {
        if (a == null || b == null) {
            return false;
        }
        for (int s = 0; s <= 24; s += 8) {
            if (Math.abs(((a >>> s) & 0xFF) - ((b >>> s) & 0xFF)) > 2) {
                return false;
            }
        }
        return true;
    }

    private static int cell(int col, int row, boolean east) {
        int gx = col * 2 + (east ? 1 : 0);
        int gz = row * 2 + (east ? 0 : 1);
        return gz * 11 + gx;
    }

    private static int[] gapCentre(int[] o, int col, int row, boolean east) {
        int x0 = o[0] + (col - 1) * o[2];
        int y0 = o[1] + (row - 1) * o[2];
        int gap = o[2] - o[3];
        return east ? new int[]{x0 + o[3] + gap / 2, y0 + o[3] / 2} : new int[]{x0 + o[3] / 2, y0 + o[3] + gap / 2};
    }

    private void row(String name, boolean pass, String detail) {
        System.out.println("[" + name + "] " + (pass ? "PASS" : "FAIL") + " - " + detail);
        Report.caseFinished(name, pass ? "PASS" : "FAIL", "n/a (singleplayer)", detail, List.of(), List.of());
        SuiteVerdict.finished(name);
        if (!pass) {
            all.add(name + ": " + detail);
        }
    }

    private static void println(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}
