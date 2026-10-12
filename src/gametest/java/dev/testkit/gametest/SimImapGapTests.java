package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The Interactive Map's gaps (mod branch imap-gap, 2026-10-12). killer560: "There are these gaps in rooms between where
 * interactive map thinks tiles are. If you are in one of these it chooses a different spot", and "that picture is just
 * showing in a room itself where I hover on it breaks the map", "for doorways you should be able to also teleport to
 * doorways". The map screen is opened for real on the pinned F7 (98-sim-insta-clear-live's, which has Waterfall), and
 * its own {@code cellAt} is asked about points of the drawn map.
 *
 * <ul>
 *   <li><b>851-sim-imapgap-hover</b>: every point of the strip between two tiles of every multi-tile room must resolve
 *       to a tile of that room - the one nearest the point - and never the strip itself or the room's main tile when
 *       another is nearer. (The jar before imap-gap returned the strip cell, which the hover and the press turned into
 *       the room's main tile.)</li>
 *   <li><b>852-sim-imapgap-click</b>: from Atlas, a press on Waterfall's strip right next to a tile that is not its main
 *       tile: he must end in THAT tile.</li>
 *   <li><b>853-sim-imapgap-door</b>: an open doorway on the map hovers as the door, and a press on it flies him into the
 *       doorway itself, standing on a block there.</li>
 *   <li><b>854-sim-imapgap-position</b>: his own position in a gap block: inside a room it is that room; in a doorway
 *       between two rooms each half of the gap block is the room on its side.</li>
 * </ul>
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimImapGapTests implements FabricClientGameTest {

    static final String HOVER = "851-sim-imapgap-hover";
    static final String CLICK = "852-sim-imapgap-click";
    static final String DOOR = "853-sim-imapgap-door";
    static final String POSITION = "854-sim-imapgap-position";
    private static final String[] CASES = {HOVER, POSITION, CLICK, DOOR};

    private static final String LIVE = "com.killer560.hub.livemap.LiveMapFeature";
    private static final String FEATURE = "com.killer560.hub.livemap.InteractiveMapFeature";
    private static final String SCREEN = "com.killer560.hub.livemap.InteractiveMapScreen";
    private static final String PAINTER = "com.killer560.hub.livemap.MapPainter";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String EXECUTOR = "com.killer560.hub.livemap.autoclear.ClearExecutor";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final int GRID = 11;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        List<String> selected = new ArrayList<>();
        for (String c : CASES) {
            if (!Scenario.skip(c)) {
                selected.add(c);
            }
        }
        if (selected.isEmpty()) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (!Mod.isCheat()) {
            selected.forEach(c -> Scenario.skipped(c, "the Interactive Map is a cheat-build feature"));
            return;
        }
        if (SimMapTests.copyRoomsForOthers() < 20) {
            selected.forEach(c -> Scenario.skipped(c, "needs real room captures and room database"));
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);
        int renderBefore = ctx.computeOnClient(mc -> mc.options.renderDistance().get());
        ctx.runOnClient(mc -> {
            mc.options.renderDistance().set(16);
            ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autopuzzles.AutoPuzzlesConfig"),
                    "setAutoPuzzlesMasterEnabled", false);
            ModUnderTest.set(ModUnderTest.config("com.killer560.hub.livemap.LiveMapConfig"), "setEnabled", true);
            ModUnderTest.staticCall(SIM_STATE, "enter", new Class<?>[]{String.class}, new Object[]{"gametest"});
        });
        Map<String, List<String>> failures = new LinkedHashMap<>();
        try {
            SimPlannerGapsTests.buildFloor(ctx, HOVER, SimPlannerGapsTests.SWEEP_FLOOR_CODE);
            for (String c : selected) {
                List<String> f = new ArrayList<>();
                failures.put(c, f);
                System.out.println("[" + c + "] ---- " + c + " ----");
                try {
                    switch (c) {
                        case HOVER -> caseHover(ctx, f);
                        case POSITION -> casePosition(ctx, f);
                        case CLICK -> caseClick(ctx, f);
                        case DOOR -> caseDoor(ctx, f);
                        default -> { }
                    }
                } catch (Throwable t) {
                    f.add("threw " + t);
                    t.printStackTrace(System.out);
                } finally {
                    closeMap(ctx);
                }
            }
        } finally {
            ctx.runOnClient(mc -> ModUnderTest.staticCall(EXECUTOR, "cancel", new Class<?>[]{}, new Object[]{}));
            ctx.runOnClient(mc -> mc.options.renderDistance().set(renderBefore));
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "leave"));
            ctx.runOnClient(mc -> mc.execute(() -> {
                if (mc.level != null) {
                    mc.level.disconnect(net.minecraft.network.chat.Component.literal("scenario over"));
                    mc.disconnectWithSavingScreen();
                }
            }));
            ctx.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null, 2400);
            ctx.waitTicks(40);
            ctx.runOnClient(mc -> mc.execute(() ->
                    McCompat.setScreen(mc, new net.minecraft.client.gui.screens.TitleScreen())));
            ctx.waitFor(mc -> McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.TitleScreen, 1200);
        }
        List<String> all = new ArrayList<>();
        for (var e : failures.entrySet()) {
            if (e.getValue().isEmpty()) {
                System.out.println("[" + e.getKey() + "] PASS");
            } else {
                System.out.println("[" + e.getKey() + "] FAIL - " + String.join("; ", e.getValue()));
                e.getValue().forEach(s -> all.add(e.getKey() + ": " + s));
            }
        }
        if (!all.isEmpty()) {
            throw new AssertionError(all.size() + " problem(s): " + String.join("; ", all));
        }
    }

    // ============================================================================================ the map's groups

    /** A room on the map: its name, main tile, tiles and every cell. */
    private record Group(String name, int main, int[] tiles, int[] cells) {
    }

    private static List<Group> groups(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            List<?> raw = (List<?>) ModUnderTest.staticCall(LIVE, "groupsView", new Class<?>[]{}, new Object[]{});
            List<Group> out = new ArrayList<>();
            for (Object g : raw) {
                Object entry = get(g, "entry");
                String name = entry == null ? "?" : (String) get(entry, "name");
                out.add(new Group(name, (Integer) get(g, "mainIdx"), (int[]) get(g, "tiles"), (int[]) get(g, "cells")));
            }
            return out;
        });
    }

    private static Object get(Object o, String field) {
        try {
            Field f = o.getClass().getDeclaredField(field);
            f.setAccessible(true);
            return f.get(o);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static int cellPos(int g) {
        return (Integer) ModUnderTest.staticCall(PAINTER, "cellPos", new Class<?>[]{int.class}, new Object[]{g});
    }

    private static int cellSize(int g) {
        return (Integer) ModUnderTest.staticCall(PAINTER, "cellSize", new Class<?>[]{int.class}, new Object[]{g});
    }

    // ============================================================================================ the screen

    private static void openMap(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            try {
                Object screen = Class.forName(SCREEN).getConstructor(boolean.class).newInstance(false);
                McCompat.setScreen(mc, (net.minecraft.client.gui.screens.Screen) screen);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        });
        ctx.waitTicks(5);
    }

    private static void closeMap(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            if (McCompat.screen(mc) != null && McCompat.screen(mc).getClass().getName().equals(SCREEN)) {
                McCompat.setScreen(mc, null);
            }
        });
        ctx.waitTicks(2);
    }

    /** The open map screen's own cellAt for a point given in map units. */
    private static int cellAtUnits(Minecraft mc, double ux, double uz) {
        Object screen = McCompat.screen(mc);
        float ox = (Float) ModUnderTest.call(screen, "originX", new Class<?>[]{}, new Object[]{});
        float oy = (Float) ModUnderTest.call(screen, "originY", new Class<?>[]{}, new Object[]{});
        float ppu = (Float) ModUnderTest.call(screen, "ppu", new Class<?>[]{}, new Object[]{});
        return (Integer) ModUnderTest.call(screen, "cellAt", new Class<?>[]{double.class, double.class},
                new Object[]{ox + ux * ppu, oy + uz * ppu});
    }

    /** The tile of {@code g} nearest a map point (centre to point), recomputed here. */
    private static int nearestTile(Group g, double ux, double uz) {
        int best = -1;
        double bestD = Double.MAX_VALUE;
        for (int t : g.tiles()) {
            double cx = cellPos(t % GRID) + cellSize(t % GRID) / 2.0;
            double cz = cellPos(t / GRID) + cellSize(t / GRID) / 2.0;
            double d = (cx - ux) * (cx - ux) + (cz - uz) * (cz - uz);
            if (d < bestD - 1e-9) {
                bestD = d;
                best = t;
            }
        }
        return best;
    }

    // ============================================================================================ 851 hover

    private static void caseHover(ClientGameTestContext ctx, List<String> f) {
        openMap(ctx);
        List<Group> groups = groups(ctx);
        int points = 0;
        int notMain = 0;
        int wrong = 0;
        boolean sawWaterfall = false;
        for (Group g : groups) {
            if (g.tiles().length < 2) {
                continue;
            }
            for (int c : g.cells()) {
                int gx = c % GRID;
                int gz = c / GRID;
                if (gx % 2 == 0 && gz % 2 == 0) {
                    continue;   // a tile, not a strip
                }
                for (int i = 0; i < 5; i++) {
                    for (int j = 0; j < 5; j++) {
                        double ux = cellPos(gx) + cellSize(gx) * (i + 0.5) / 5.0;
                        double uz = cellPos(gz) + cellSize(gz) * (j + 0.5) / 5.0;
                        int expect = nearestTile(g, ux, uz);
                        int got = ctx.computeOnClient(mc -> cellAtUnits(mc, ux, uz));
                        points++;
                        notMain += expect != g.main() ? 1 : 0;
                        if (got != expect) {
                            wrong++;
                            if (wrong <= 8) {
                                f.add(String.format(Locale.US, "%s strip cell %d at (%.1f, %.1f): the map took cell %d"
                                        + " (main tile %d), the nearest tile is %d", g.name(), c, ux, uz, got, g.main(),
                                        expect));
                            }
                        }
                    }
                }
                sawWaterfall |= "Waterfall".equals(g.name());
            }
        }
        System.out.println(String.format(Locale.US, "[%s] %d point(s) on the strips of multi-tile rooms; %d of them nearer"
                + " a tile other than the room's main tile; %d resolved wrong", HOVER, points, notMain, wrong));
        if (!sawWaterfall) {
            f.add("no strip of Waterfall on this floor - the case does not cover the screenshot");
        }
        hoverShots(ctx, f, groups);
        if (points == 0) {
            f.add("no multi-tile room strips found");
        }
    }

    /** Where 851/852's screenshots go (killer560s-mod-logs/imap-gap-shots), named before/after by the jar. */
    private static final java.nio.file.Path SHOTS = java.nio.file.Path.of(System.getProperty("user.home"),
            "killer560s-mod-logs", "imap-gap-shots");

    /** "after" on a jar whose map presses a room's strip as a spot (mod imap-gap-2), "before" otherwise. */
    private static String jarTag() {
        try {
            Class.forName(LIVE).getDeclaredMethod("onRoomStrip", double.class, double.class);
            return "after";
        } catch (ReflectiveOperationException e) {
            return "before";
        }
    }

    /**
     * 851's pictures (killer560, 2026-10-12: "since there is a room there that is actual tangible space that should also
     * be highlighted"): the cursor on a Waterfall tile that has another of its tiles to the east, then on the strip
     * between them, each screenshot read back. The half of the strip on the hovered tile's side must be lit like the
     * tile (no unlit line between the room's tiles), and with the cursor on the strip the spot under it must be lit.
     */
    private static void hoverShots(ClientGameTestContext ctx, List<String> f, List<Group> groups) {
        Group wf = null;
        for (Group g : groups) {
            if ("Waterfall".equals(g.name())) {
                wf = g;
            }
        }
        if (wf == null) {
            return;
        }
        int tile = -1;
        for (int t : wf.tiles()) {
            if (contains(wf.tiles(), t + 2) && contains(wf.cells(), t + 1)) {
                tile = t;
                break;
            }
        }
        if (tile < 0) {
            f.add("Waterfall has no tile with another of its tiles to the east");
            return;
        }
        int tx = tile % GRID;
        int tz = tile / GRID;
        double tileMidX = cellPos(tx) + cellSize(tx) / 2.0;
        double midZ = cellPos(tz) + cellSize(tz) / 2.0;
        double stripX0 = cellPos(tx + 1);
        double stripW = cellSize(tx + 1);
        String tag = jarTag();
        try {
            java.nio.file.Files.createDirectories(SHOTS);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        // (1) the cursor on the tile, (2) on the strip a quarter in from the tile's side.
        double[][] at = {{tileMidX, midZ}, {stripX0 + stripW * 0.25, midZ}};
        String[] what = {"tile", "strip"};
        for (int k = 0; k < 2; k++) {
            double cux = at[k][0];
            double cuz = at[k][1];
            double[] px = ctx.computeOnClient(mc -> {
                Object screen = McCompat.screen(mc);
                float ox = (Float) ModUnderTest.call(screen, "originX", new Class<?>[]{}, new Object[]{});
                float oy = (Float) ModUnderTest.call(screen, "originY", new Class<?>[]{}, new Object[]{});
                float ppu = (Float) ModUnderTest.call(screen, "ppu", new Class<?>[]{}, new Object[]{});
                // Window pixels per unit of the SCREEN's own layout (its width, as it was laid out) - not the window's
                // current GUI scale, which this test client can report differently from what the open screen used.
                double scale = mc.getWindow().getWidth() / (double) ((net.minecraft.client.gui.screens.Screen) screen).width;
                return new double[]{ox, oy, ppu, scale};
            });
            ctx.getInput().setCursorPos((px[0] + cux * px[2]) * px[3], (px[1] + cuz * px[2]) * px[3]);
            ctx.waitTicks(4);
            java.nio.file.Path shot = ctx.takeScreenshot(dev.testkit.harness.Report.fileName(HOVER + "-" + what[k]));
            java.awt.image.BufferedImage img;
            try {
                java.nio.file.Files.copy(shot, SHOTS.resolve("851-hover-" + what[k] + "-" + tag + ".png"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                img = javax.imageio.ImageIO.read(shot.toFile());
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
            // Pixels: the hovered tile's inside (lit), the strip's near half (must be lit the same), the next tile's
            // inside (not hovered - the room's plain colour).
            double sx = img.getWidth() / (double) ctx.computeOnClient(mc -> mc.getWindow().getWidth());
            java.util.function.BiFunction<Double, Double, Integer> pix = (ux, uz) -> {
                int x = (int) Math.round((px[0] + ux * px[2]) * px[3] * sx);
                int y = (int) Math.round((px[1] + uz * px[2]) * px[3] * sx);
                return img.getRGB(Math.max(0, Math.min(img.getWidth() - 1, x)), Math.max(0, Math.min(img.getHeight() - 1, y)));
            };
            double offZ = -cellSize(tz) * 0.32;   // near the room's top edge, off the label in its middle
            int lit = pix.apply(stripX0 - cellSize(tx) * 0.12, midZ + offZ);
            int nearHalf = pix.apply(stripX0 + stripW * 0.25, midZ + offZ);
            int farTile = pix.apply(cellPos(tx + 2) + cellSize(tx + 2) * 0.7, midZ + offZ);
            boolean nearLit = close(nearHalf, lit);
            boolean differs = !close(lit, farTile);
            System.out.println(String.format(Locale.US, "[%s] screenshot (%s, cursor on the %s): hovered tile %06X, the"
                            + " strip's near half %06X, the next tile %06X - strip lit like the tile: %s; hover visible: %s",
                    HOVER, tag, what[k], lit & 0xFFFFFF, nearHalf & 0xFFFFFF, farTile & 0xFFFFFF, nearLit, differs));
            if (!differs) {
                f.add("cursor on the " + what[k] + ": the hovered tile is not lit differently from the next one");
            } else if (!nearLit) {
                f.add("cursor on the " + what[k] + ": the strip beside the hovered tile is not lit (an unlit line between"
                        + " the room's tiles)");
            }
        }
        ctx.getInput().setCursorPos(2, 2);
    }

    private static boolean close(int a, int b) {
        int dr = Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF));
        int dg = Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF));
        int db = Math.abs((a & 0xFF) - (b & 0xFF));
        return dr + dg + db <= 12;
    }

    // ============================================================================================ 852 click

    private static void caseClick(ClientGameTestContext ctx, List<String> f) {
        Group wf = null;
        for (Group g : groups(ctx)) {
            if ("Waterfall".equals(g.name())) {
                wf = g;
            }
        }
        if (wf == null) {
            f.add("no Waterfall on this floor");
            return;
        }
        // A strip cell of Waterfall next to a tile that is not its main tile; the point a quarter into the strip on that
        // tile's side.
        int strip = -1;
        int side = -1;
        for (int c : wf.cells()) {
            int gx = c % GRID;
            int gz = c / GRID;
            if (gx % 2 == 1 && gz % 2 == 0) {
                int right = c + 1;
                if (right != wf.main() && contains(wf.tiles(), right)) {
                    strip = c;
                    side = right;
                    break;
                }
            }
        }
        if (strip < 0) {
            f.add("Waterfall has no east-west strip beside a non-main tile");
            return;
        }
        int sx = strip % GRID;
        int sz = strip / GRID;
        double ux = cellPos(sx) + cellSize(sx) * 0.5;
        double uz = cellPos(sz) + cellSize(sz) * 0.3;
        int target = side;
        // Start in another room: Atlas's centre tile area.
        Vec3 start = ctx.computeOnClient(mc -> mc.player.position());
        openMap(ctx);
        int cell = ctx.computeOnClient(mc -> cellAtUnits(mc, ux, uz));
        System.out.println(String.format(Locale.US, "[%s] press on Waterfall's strip %d at (%.1f, %.1f): the map took cell"
                + " %d (the tile beside it is %d, the main tile %d)", CLICK, strip, ux, uz, cell, target, wf.main()));
        long mark = LogTap.mark();
        boolean spotPress = "after".equals(jarTag());
        // The press the screen makes for a point (mod imap-gap-2: a point on a strip is a spot); the jar before had only
        // the cell press, which is what its screen sent.
        ctx.runOnClient(mc -> {
            if (spotPress) {
                ModUnderTest.staticCall(FEATURE, "onMapPressAt", new Class<?>[]{double.class, double.class,
                        boolean.class}, new Object[]{ux, uz, false});
            } else {
                ModUnderTest.staticCall(FEATURE, "onMapPress", new Class<?>[]{int.class}, new Object[]{cell});
            }
        });
        closeMap(ctx);
        Vec3 end = waitTrip(ctx, mark);
        // The strip's own blocks: the gap block column between the two tiles (world), a block either way of it.
        BlockPos gap = cellCentre(ctx, strip);
        double fromStrip = Math.abs(end.x - (gap.getX() + 0.5));
        System.out.println(String.format(Locale.US, "[%s] the press was on the strip's middle; he ended %.1f block(s) from"
                + " the strip's centre line (x %d)", CLICK, fromStrip, gap.getX()));
        if (fromStrip > 2.5) {
            f.add(String.format(Locale.US, "a press in the strip ended %.1f blocks from the strip (at %s), not in it",
                    fromStrip, fmt(end)));
        }
        BlockPos centre = ctx.computeOnClient(mc -> (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter",
                new Class<?>[]{int.class}, new Object[]{target}));
        int mainIdx = wf.main();
        BlockPos mainC = ctx.computeOnClient(mc -> (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter",
                new Class<?>[]{int.class}, new Object[]{mainIdx}));
        boolean inTile = Math.abs(end.x - (centre.getX() + 0.5)) <= 15.5 && Math.abs(end.z - (centre.getZ() + 0.5)) <= 15.5;
        System.out.println(String.format(Locale.US, "[%s] trip: from %s, ended %s - %s the tile beside the press (centre"
                + " %s; main tile centre %s)", CLICK, fmt(start), fmt(end), inTile ? "IN" : "NOT in", centre.toShortString(),
                mainC.toShortString()));
        if (end.distanceTo(start) < 5) {
            f.add("no trip happened (moved " + String.format(Locale.US, "%.1f", end.distanceTo(start)) + ")");
        }
        // (The tile check of imap-gap-1 is gone: a press on the strip now goes to the strip itself.)
    }

    // ============================================================================================ 853 door

    private static void caseDoor(ClientGameTestContext ctx, List<String> f) {
        // An open (unlocked) door between two rooms, far from where he stands.
        Vec3 here = ctx.computeOnClient(mc -> mc.player.position());
        int door = ctx.computeOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture", new Class<?>[]{}, new Object[]{});
            int best = -1;
            double bestD = -1;
            for (int i = 0; i < GRID * GRID; i++) {
                boolean isDoor = (Boolean) ModUnderTest.call(layout, "isDoor", new Class<?>[]{int.class}, new Object[]{i});
                boolean locked = isDoor && (Boolean) ModUnderTest.call(layout, "isLocked", new Class<?>[]{int.class},
                        new Object[]{i});
                if (!isDoor || locked) {
                    continue;
                }
                BlockPos b = (BlockPos) ModUnderTest.staticCall(LAYOUT, "doorBlock", new Class<?>[]{int.class},
                        new Object[]{i});
                double d = Math.hypot(b.getX() - here.x, b.getZ() - here.z);
                if (d > 25 && d < 70 && d > bestD) {
                    bestD = d;
                    best = i;
                }
            }
            return best;
        });
        if (door < 0) {
            f.add("no open door 25-70 blocks away");
            return;
        }
        int gx = door % GRID;
        int gz = door / GRID;
        double ux = cellPos(gx) + cellSize(gx) * 0.5;
        double uz = cellPos(gz) + cellSize(gz) * 0.5;
        openMap(ctx);
        int cell = ctx.computeOnClient(mc -> cellAtUnits(mc, ux, uz));
        boolean doorCell = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(PAINTER, "isDoorCell",
                new Class<?>[]{int.class}, new Object[]{cell}));
        int group = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(LIVE, "groupIdAt",
                new Class<?>[]{int.class}, new Object[]{cell}));
        System.out.println(String.format(Locale.US, "[%s] door cell %d: the map took cell %d (a door cell: %s, in a room:"
                + " %s)", DOOR, door, cell, doorCell, group >= 0));
        if (cell != door || !doorCell || group >= 0) {
            f.add("hovering the doorway did not give the door cell (" + cell + ")");
            closeMap(ctx);
            return;
        }
        long mark = LogTap.mark();
        ctx.runOnClient(mc -> ModUnderTest.staticCall(FEATURE, "onMapPress", new Class<?>[]{int.class},
                new Object[]{cell}));
        closeMap(ctx);
        Vec3 end = waitTrip(ctx, mark);
        BlockPos lock = ctx.computeOnClient(mc -> (BlockPos) ModUnderTest.staticCall(LAYOUT, "doorBlock",
                new Class<?>[]{int.class}, new Object[]{door}));
        double off = Math.max(Math.abs(end.x - (lock.getX() + 0.5)), Math.abs(end.z - (lock.getZ() + 0.5)));
        String under = ctx.computeOnClient(mc -> mc.level.getBlockState(BlockPos.containing(end.x, end.y - 0.2, end.z))
                .toString());
        boolean ground = ctx.computeOnClient(mc -> mc.player.onGround());
        System.out.println(String.format(Locale.US, "[%s] trip: from %s, ended %s, %.1f from the doorway's centre, on %s"
                + " (on the ground %s)", DOOR, fmt(here), fmt(end), off, under, ground));
        if (end.distanceTo(here) < 5) {
            f.add("no trip happened");
        }
        if (off > 3.5) {
            f.add(String.format(Locale.US, "ended %.1f blocks from the doorway, not in it", off));
        }
    }

    // ============================================================================================ 854 position

    private static void casePosition(ClientGameTestContext ctx, List<String> f) {
        List<Group> groups = groups(ctx);
        Group wf = null;
        for (Group g : groups) {
            if ("Waterfall".equals(g.name())) {
                wf = g;
            }
        }
        int checked = 0;
        // Inside a room: every strip cell's gap block between two of its tiles, both halves, is that room.
        for (Group g : groups) {
            if (g.tiles().length < 2) {
                continue;
            }
            for (int c : g.cells()) {
                int gx = c % GRID;
                int gz = c / GRID;
                if (gx % 2 == gz % 2) {
                    continue;   // a tile or a centre cross
                }
                BlockPos gap = ctx.computeOnClient(mc -> (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter",
                        new Class<?>[]{int.class}, new Object[]{c}));
                for (double frac : new double[]{0.2, 0.8}) {
                    double x = gx % 2 == 1 ? gap.getX() + frac : gap.getX() + 0.5;
                    double z = gz % 2 == 1 ? gap.getZ() + frac : gap.getZ() + 0.5;
                    String room = roomAt(ctx, x, z);
                    checked++;
                    if (!g.name().equals(room)) {
                        f.add(String.format(Locale.US, "%s: (%.1f, %.1f) in its own gap reads as %s", g.name(), x, z, room));
                    }
                }
            }
        }
        // Doorways between rooms: each half of the gap block is the room on its side.
        int doors = 0;
        for (int c = 0; c < GRID * GRID; c++) {
            int gx = c % GRID;
            int gz = c / GRID;
            if (gx % 2 == gz % 2) {
                continue;
            }
            int a = gx % 2 == 1 ? c - 1 : c - GRID;
            int b = gx % 2 == 1 ? c + 1 : c + GRID;
            String ra = roomOfCell(ctx, a);
            String rb = roomOfCell(ctx, b);
            if (ra == null || rb == null || ra.equals(rb)) {
                continue;
            }
            BlockPos gap = cellCentre(ctx, c);
            double xa = gx % 2 == 1 ? gap.getX() + 0.2 : gap.getX() + 0.5;
            double za = gz % 2 == 1 ? gap.getZ() + 0.2 : gap.getZ() + 0.5;
            double xb = gx % 2 == 1 ? gap.getX() + 0.8 : gap.getX() + 0.5;
            double zb = gz % 2 == 1 ? gap.getZ() + 0.8 : gap.getZ() + 0.5;
            String got1 = roomAt(ctx, xa, za);
            String got2 = roomAt(ctx, xb, zb);
            doors++;
            if (!ra.equals(got1) || !rb.equals(got2)) {
                f.add(String.format(Locale.US, "seam cell %d between %s and %s: the near half reads %s, the far half %s",
                        c, ra, rb, got1, got2));
            }
        }
        System.out.println(String.format(Locale.US, "[%s] %d point(s) in rooms' own gaps, %d seam(s) between two rooms"
                + " checked half by half (Waterfall on the floor: %s)", POSITION, checked, doors, wf != null));
        if (checked == 0 || doors == 0) {
            f.add("nothing checked");
        }
    }

    private static BlockPos cellCentre(ClientGameTestContext ctx, int c) {
        return ctx.computeOnClient(mc -> (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter",
                new Class<?>[]{int.class}, new Object[]{c}));
    }

    private static String roomAt(ClientGameTestContext ctx, double x, double z) {
        return ctx.computeOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture", new Class<?>[]{}, new Object[]{});
            int r = (Integer) ModUnderTest.call(layout, "roomAtWorld", new Class<?>[]{double.class, double.class},
                    new Object[]{x, z});
            return (String) ModUnderTest.call(layout, "name", new Class<?>[]{int.class}, new Object[]{r});
        });
    }

    private static String roomOfCell(ClientGameTestContext ctx, int c) {
        return ctx.computeOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture", new Class<?>[]{}, new Object[]{});
            int r = (Integer) ModUnderTest.call(layout, "roomOfCell", new Class<?>[]{int.class}, new Object[]{c});
            return r < 0 ? null : (String) ModUnderTest.call(layout, "name", new Class<?>[]{int.class}, new Object[]{r});
        });
    }

    // ============================================================================================ small things

    /** Waits for a map trip to start and finish; returns where he ended. Prints the trip's [Path] lines. */
    private static Vec3 waitTrip(ClientGameTestContext ctx, long mark) {
        boolean busySeen = false;
        for (int i = 0; i < 400; i++) {
            ctx.waitTicks(1);
            boolean busy = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(EXECUTOR, "isBusy",
                    new Class<?>[]{}, new Object[]{}));
            busySeen |= busy;
            if (busySeen && !busy) {
                break;
            }
        }
        ctx.waitTicks(15);
        for (String l : LogTap.since(mark)) {
            if (l.contains("[Path]") || l.contains("[Interactive Map]")) {
                System.out.println("    " + l.replaceAll("^.*?\\[(Path|Interactive Map)\\]", "[$1]"));
            }
        }
        return ctx.computeOnClient(mc -> mc.player.position());
    }

    private static boolean contains(int[] a, int v) {
        for (int x : a) {
            if (x == v) {
                return true;
            }
        }
        return false;
    }

    private static String fmt(Vec3 v) {
        return String.format(Locale.US, "(%.2f, %.2f, %.2f)", v.x, v.y, v.z);
    }
}
