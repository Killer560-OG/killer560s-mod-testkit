package dev.testkit.gametest;

import dev.testkit.compat.McCompat;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.gui.screens.Screen;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 75-sim-designer-grid: killer560 (2026-10-16) on the Dungeon Sim's Create a New Map grid, of a placed Altar (an L room)
 * whose box fell between the grid's lines: "make this grid for rooms line up better."
 *
 * <p>Every check is made on the SCREENSHOT's pixels, not on anything the screen says about itself. A room of each shape
 * (1x1, 1x2, 1x3, 1x4, 2x2, L) is placed by real clicks - two arrangements, so each shape sits at two positions - and at
 * every window size / GUI scale / Auto Scale below:
 * <ul>
 *   <li>the grid's lines are found as columns and rows of the line color: there are {@code across - 1} and
 *       {@code down - 1} of them, all the same thickness and exactly the same distance apart, and one more step out on
 *       each side is the well's border (dark), so the first and last row and column are the same size as the rest;</li>
 *   <li>each room's drawn color, bounded on the pixel lattice those lines give, leaves exactly the same gutter to the
 *       lines on all four sides, the same for every room in the frame, and fills its cells (an L its three cells, its
 *       fourth cell empty);</li>
 *   <li>each room's label is centered in its shape (an L: on its horizontal pair, as the live map places it).</li>
 * </ul>
 * Fails on main: rooms start on a line and stop a fifth of a cell short of the next, the L is drawn as its whole 2x2 box,
 * and at a fractional pixels-per-unit (1920x1080 GUI 3 under Auto Scale is 2.25) the line spacing alternates.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimDesignerGridTests implements FabricClientGameTest {

    private static final String NAME = "75-sim-designer-grid";
    private static final String EDITOR = DesignerKit.EDITOR;
    private static final String[] SHAPES = {"1x4", "2x2", "L", "1x3", "1x2", "1x1"};

    /** {window width, height, GUI scale (0 = auto), Auto Scale on (1) / off (0)}. */
    private static final int[][] WINDOWS = {
            {854, 480, 2, 1}, {1920, 1080, 3, 1}, {1600, 900, 2, 1}, {1280, 720, 4, 1}, {1366, 768, 3, 1},
            {1920, 1080, 2, 1}, {1920, 1080, 3, 0}, {1280, 720, 3, 0}};

    private final List<String> failures = new ArrayList<>();
    private int framesChecked;
    private int roomsChecked;
    private int lShapesChecked;
    private int labelsChecked;

    /** A room placed for the check: its name, anchor, footprint and (an L) missing tile. */
    private record Placed(String name, String shape, int ax, int az, int w, int h, int[] missing, int colour) {
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (SimMapTests.copyRoomsForOthers() < 20) {
            Scenario.skipped(NAME, "needs real room captures and room database");
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(DesignerKit.ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(DesignerKit.ROOM_LIBRARY, "isReady"), 2400);
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.roomdatabase.RoomDatabase",
                "isReady"), 2400);

        int[] window = ctx.computeOnClient(mc -> new int[]{mc.getWindow().getScreenWidth(),
                mc.getWindow().getScreenHeight(), mc.options.guiScale().get()});
        Object hud = ctx.computeOnClient(mc -> ModUnderTest.config("com.killer560.hub.hud.HudConfig"));
        boolean autoScale = ctx.computeOnClient(mc -> ModUnderTest.getBoolean(hud, "isAutoScale"));
        try {
            Map<String, String> rooms = pickRooms(ctx);
            println("rooms by shape: " + rooms);
            for (String s : SHAPES) {
                check(rooms.containsKey(s), "no usable room of shape " + s + " in the library");
            }
            for (int arrangement = 0; arrangement < 2; arrangement++) {
                setWindow(ctx, 854, 480, 2);
                ctx.runOnClient(mc -> ModUnderTest.set(hud, "setAutoScale", true));
                DesignerKit.openEditor(ctx);
                ctx.waitTicks(10);
                List<Placed> placed = place(ctx, rooms, arrangement);
                println("arrangement " + arrangement + ": " + placed.size() + " rooms placed");
                check(placed.size() == SHAPES.length, "arrangement " + arrangement + " placed only " + placed.size()
                        + " of " + SHAPES.length + " rooms");
                for (int[] w : WINDOWS) {
                    ctx.runOnClient(mc -> ModUnderTest.set(hud, "setAutoScale", w[3] == 1));
                    setWindow(ctx, w[0], w[1], w[2]);
                    String tag = "arr " + arrangement + " " + w[0] + "x" + w[1] + " gui " + w[2]
                            + (w[3] == 1 ? " auto" : " no-auto");
                    checkFrame(ctx, tag, placed);
                }
                DesignerKit.click(ctx, "Clear");
                ctx.waitTicks(3);
                DesignerKit.closeScreen(ctx);
            }
        } finally {
            ctx.runOnClient(mc -> ModUnderTest.set(hud, "setAutoScale", autoScale));
            setWindow(ctx, window[0], window[1], window[2]);
            ctx.runOnClient(mc -> mc.execute(() ->
                    McCompat.setScreen(mc, new net.minecraft.client.gui.screens.TitleScreen())));
            ctx.waitTicks(5);
        }
        println("checked " + framesChecked + " frame(s), " + roomsChecked + " room(s) (" + lShapesChecked + " L), "
                + labelsChecked + " label(s)");
        int want = 2 * WINDOWS.length;
        check(framesChecked == want, "only " + framesChecked + " of " + want + " frames were measured");
        check(roomsChecked >= want * SHAPES.length, "only " + roomsChecked + " room boxes were measured");
        check(lShapesChecked >= want, "the L room was measured in only " + lShapesChecked + " frame(s)");
        check(labelsChecked >= want * SHAPES.length / 2, "only " + labelsChecked + " labels were measured");
        if (!failures.isEmpty()) {
            int shown = Math.min(40, failures.size());
            throw new AssertionError(failures.size() + " problem(s), first " + shown + ":\n  "
                    + String.join("\n  ", failures.subList(0, shown)));
        }
        println("PASS - evenly spaced grid lines and every room on its cells to the pixel, label centered, at every size");
    }

    // ============================================================================================ rooms and placing

    /** One usable library room per shape, preferring ordinary (brown) rooms. */
    private static Map<String, String> pickRooms(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            Map<String, String> best = new java.util.LinkedHashMap<>();
            Map<String, Boolean> normal = new java.util.HashMap<>();
            @SuppressWarnings("unchecked")
            List<String> names = (List<String>) ModUnderTest.staticCall(DesignerKit.ROOM_LIBRARY, "names");
            for (String n : names) {
                if (!(Boolean) ModUnderTest.staticCall(DesignerKit.ROOM_LIBRARY, "isUsable",
                        new Class<?>[]{String.class}, new Object[]{n})) {
                    continue;
                }
                String shape = (String) ModUnderTest.staticCall("com.killer560.hub.roomsim.SimFloorGen", "shapeOf",
                        new Class<?>[]{String.class}, new Object[]{n});
                if (shape == null) {
                    continue;
                }
                shape = shape.trim();
                int[] fp = footprint(n);
                boolean fits = switch (shape) {
                    case "1x1" -> fp[0] == 1 && fp[1] == 1;
                    case "1x2" -> fp[0] * fp[1] == 2;
                    case "1x3" -> fp[0] * fp[1] == 3;
                    case "1x4" -> fp[0] * fp[1] == 4 && (fp[0] == 1 || fp[1] == 1);
                    case "2x2" -> fp[0] == 2 && fp[1] == 2;
                    case "L" -> fp[0] == 2 && fp[1] == 2 && missing(n) != null;
                    default -> false;
                };
                if (!fits) {
                    continue;
                }
                String type = (String) ModUnderTest.staticCall("com.killer560.hub.roomsim.SimFloorGen", "typeOf",
                        new Class<?>[]{String.class}, new Object[]{n});
                boolean isNormal = "NORMAL".equalsIgnoreCase(type);
                if (!best.containsKey(shape) || (isNormal && !normal.get(shape))) {
                    best.put(shape, n);
                    normal.put(shape, isNormal);
                }
            }
            return best;
        });
    }

    private static int[] footprint(String name) {
        return (int[]) ModUnderTest.staticCall(EDITOR, "footprintOf", new Class<?>[]{String.class, int.class},
                new Object[]{name, 0});
    }

    /** An L's missing tile at rotation 0 (RoomLShape, the mod's one answer), null for any other room. */
    private static int[] missing(String name) {
        Object room = ModUnderTest.staticCall(DesignerKit.ROOM_LIBRARY, "get", new Class<?>[]{String.class},
                new Object[]{name});
        if (room == null) {
            return null;
        }
        try {
            Class<?> roomCls = Class.forName(DesignerKit.ROOM_LIBRARY + "$Room");
            return (int[]) ModUnderTest.staticCall("com.killer560.hub.roomsim.RoomLShape", "missingTilePasted",
                    new Class<?>[]{roomCls, int.class}, new Object[]{room, 0});
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * Places one room of each shape by real clicks (pick it, click its anchor cell), each with an empty cell between it
     * and the next so no doorway is drawn between them. Arrangement 0 fills from the top-left, 1 from the bottom-right,
     * so every shape is measured at two positions.
     */
    private List<Placed> place(ClientGameTestContext ctx, Map<String, String> rooms, int arrangement) {
        int[] size = ctx.computeOnClient(mc -> {
            Object floor = DesignerKit.reflect(McCompat.screen(mc), "floor");
            return new int[]{(Integer) DesignerKit.reflect(floor, "across"), (Integer) DesignerKit.reflect(floor, "down")};
        });
        int across = size[0];
        int down = size[1];
        boolean[][] taken = new boolean[across][down];
        List<Placed> out = new ArrayList<>();
        // Biggest first in both, so they pack; the scan direction is what moves every room.
        List<String> order = new ArrayList<>(List.of(SHAPES));
        for (String shape : order) {
            String name = rooms.get(shape);
            if (name == null) {
                continue;
            }
            int[] fp = ctx.computeOnClient(mc -> footprint(name));
            int[] miss = "L".equals(shape) ? ctx.computeOnClient(mc -> missing(name)) : null;
            int[] at = null;
            for (int k = 0; k < across * down && at == null; k++) {
                int idx = arrangement == 0 ? k : across * down - 1 - k;
                int ax = idx % across;
                int az = idx / across;
                if (fits(taken, ax, az, fp[0], fp[1])) {
                    at = new int[]{ax, az};
                }
            }
            if (at == null) {
                println("no free spot for " + shape + " " + name);
                continue;
            }
            int ax = at[0];
            int az = at[1];
            ctx.runOnClient(mc -> {
                Screen s = McCompat.screen(mc);
                try {
                    var f = s.getClass().getDeclaredField("selected");
                    f.setAccessible(true);
                    f.set(s, name);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            double[] centre = ctx.computeOnClient(mc -> {
                Screen s = McCompat.screen(mc);
                int gx = (Integer) DesignerKit.reflect(s, "gridX");
                int gy = (Integer) DesignerKit.reflect(s, "gridY");
                int cell = (Integer) DesignerKit.reflect(s, "cell");
                return new double[]{gx + (ax + 0.5) * cell, gy + (az + 0.5) * cell};
            });
            DesignerKit.clickAt(ctx, centre[0], centre[1], 0);
            int slot = az * 6 + ax;
            String there = ctx.computeOnClient(mc -> {
                @SuppressWarnings("unchecked")
                Map<Integer, String> p = (Map<Integer, String>) DesignerKit.reflect(McCompat.screen(mc), "placements");
                return p.get(slot);
            });
            if (!name.equals(there)) {
                check(false, "a click on cell " + ax + "," + az + " did not place " + name + " there (found " + there
                        + ", status '" + DesignerKit.field(ctx, "status") + "')");
                continue;
            }
            for (int dx = 0; dx < fp[0]; dx++) {
                for (int dz = 0; dz < fp[1]; dz++) {
                    taken[ax + dx][az + dz] = true;
                }
            }
            int colour = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(EDITOR, "colourFor",
                    new Class<?>[]{String.class}, new Object[]{name}));
            out.add(new Placed(name, shape, ax, az, fp[0], fp[1], miss, colour));
            println("placed " + shape + " " + name + " at " + ax + "," + az + " (" + fp[0] + "x" + fp[1]
                    + (miss != null ? ", missing tile " + miss[0] + "," + miss[1] : "") + ")");
        }
        return out;
    }

    /** Inside the floor, and neither on nor beside (edge to edge) a cell already taken. */
    private static boolean fits(boolean[][] taken, int ax, int az, int w, int h) {
        int across = taken.length;
        int down = taken[0].length;
        if (ax + w > across || az + h > down) {
            return false;
        }
        for (int x = ax - 1; x <= ax + w; x++) {
            for (int z = az - 1; z <= az + h; z++) {
                boolean corner = (x == ax - 1 || x == ax + w) && (z == az - 1 || z == az + h);
                if (corner || x < 0 || z < 0 || x >= across || z >= down) {
                    continue;
                }
                if (taken[x][z]) {
                    return false;
                }
            }
        }
        return true;
    }

    // ============================================================================================ the frame

    private void checkFrame(ClientGameTestContext ctx, String tag, List<Placed> placed) {
        ctx.getInput().setCursorPos(2, 2);
        ctx.waitTicks(10);
        if (!DesignerKit.isEditor(ctx)) {
            check(false, tag + ": the designer is not open");
            return;
        }
        int[] info = ctx.computeOnClient(mc -> {
            Screen s = McCompat.screen(mc);
            Object floor = DesignerKit.reflect(s, "floor");
            int well = (Integer) readStatic("com.killer560.hub.gui.MenuStyle", "WELL");
            int txt = (Integer) readStatic("com.killer560.hub.gui.MenuStyle", "TXT");
            int line = (Integer) ModUnderTest.staticCall("com.killer560.hub.gui.MenuStyle", "mix",
                    new Class<?>[]{int.class, int.class, float.class}, new Object[]{well, txt, 0.10f});
            return new int[]{s.width, s.height, (Integer) DesignerKit.reflect(s, "areaX"),
                    (Integer) DesignerKit.reflect(s, "areaY"), (Integer) DesignerKit.reflect(s, "areaSize"),
                    (Integer) DesignerKit.reflect(floor, "across"), (Integer) DesignerKit.reflect(floor, "down"), line,
                    mc.getWindow().getGuiScale(), mc.getWindow().getWidth(), mc.getWindow().getGuiScaledWidth()};
        });
        List<DesignerKit.Run> runs = DesignerKit.texts(ctx);
        String shotName = NAME + "-" + tag.replace(' ', '-');
        Path shot = ctx.takeScreenshot(dev.testkit.harness.Report.fileName(shotName));
        dev.testkit.harness.Report.screenshot(shotName, shot);
        BufferedImage img;
        try {
            img = ImageIO.read(shot.toFile());
        } catch (java.io.IOException e) {
            check(false, tag + ": could not read the screenshot " + shot + ": " + e);
            return;
        }
        int sw = info[0];
        int areaX = info[2];
        int areaY = info[3];
        int areaSize = info[4];
        int across = info[5];
        int down = info[6];
        int line = info[7] & 0xFFFFFF;
        int gs = info[8];
        double ppu = img.getWidth() / (double) sw;
        if (Math.abs(info[9] - img.getWidth()) > 1 || Math.abs(info[10] * gs - img.getWidth()) > gs) {
            check(false, tag + ": the window had not settled (window " + info[9] + " px, GUI " + info[10] + " x " + gs
                    + ", picture " + img.getWidth() + ")");
            return;
        }
        int bx0 = Math.max(0, (int) Math.floor(areaX * ppu) - 4);
        int by0 = Math.max(0, (int) Math.floor(areaY * ppu) - 4);
        int bx1 = Math.min(img.getWidth(), (int) Math.ceil((areaX + areaSize) * ppu) + 4);
        int by1 = Math.min(img.getHeight(), (int) Math.ceil((areaY + areaSize) * ppu) + 4);

        int[][] vLines = lines(img, line, bx0, by0, bx1, by1, true);
        int[][] hLines = lines(img, line, bx0, by0, bx1, by1, false);
        String vs = describe(vLines);
        String hs = describe(hLines);
        println(tag + ": " + String.format("%.3f", ppu) + " px/unit, vertical lines " + vs + ", horizontal " + hs);
        framesChecked++;
        int[] lx = lattice(tag + " vertical", vLines, across);
        int[] ly = lattice(tag + " horizontal", hLines, down);
        if (lx == null || ly == null) {
            return;
        }
        int t = vLines[0][1];
        // The well's border one pitch out on every side: the first and last cells are the same size as the rest.
        border(img, tag + " left", lx[0], ly[0] + t, ly[down], true);
        border(img, tag + " right", lx[across], ly[0] + t, ly[down], true);
        border(img, tag + " top", ly[0], lx[0] + t, lx[across], false);
        border(img, tag + " bottom", ly[down], lx[0] + t, lx[across], false);

        Integer gutter = null;
        for (Placed p : placed) {
            int[] g = room(img, tag, p, lx, ly, t, runs, gs);
            if (g == null) {
                continue;
            }
            roomsChecked++;
            if (p.missing() != null) {
                lShapesChecked++;
            }
            if (gutter == null) {
                gutter = g[0];
            } else if (g[0] != gutter) {
                check(false, tag + ": " + p.shape() + " " + p.name() + " has a " + g[0] + " px gutter, another room "
                        + gutter + " px");
            }
        }
        println(tag + ": gutter " + gutter + " px");
    }

    private static Object readStatic(String cls, String field) {
        try {
            var f = Class.forName(cls).getDeclaredField(field);
            f.setAccessible(true);
            return f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("no " + cls + "." + field, e);
        }
    }

    private static boolean near(int rgb, int want, int tol) {
        for (int s = 0; s < 24; s += 8) {
            if (Math.abs(((rgb >> s) & 0xFF) - ((want >> s) & 0xFF)) > tol) {
                return false;
            }
        }
        return true;
    }

    /** Runs of columns (vertical) or rows where at least a fifth of the band is the line color: {start, thickness}. */
    private static int[][] lines(BufferedImage img, int colour, int x0, int y0, int x1, int y1, boolean vertical) {
        List<int[]> out = new ArrayList<>();
        int len = vertical ? y1 - y0 : x1 - x0;
        int from = vertical ? x0 : y0;
        int to = vertical ? x1 : y1;
        int runStart = -1;
        for (int a = from; a <= to; a++) {
            boolean isLine = false;
            if (a < to) {
                int n = 0;
                for (int b = 0; b < len; b++) {
                    int rgb = vertical ? img.getRGB(a, y0 + b) : img.getRGB(x0 + b, a);
                    if (near(rgb & 0xFFFFFF, colour, 2)) {
                        n++;
                    }
                }
                isLine = n >= len / 5;
            }
            if (isLine && runStart < 0) {
                runStart = a;
            } else if (!isLine && runStart >= 0) {
                out.add(new int[]{runStart, a - runStart});
                runStart = -1;
            }
        }
        return out.toArray(new int[0][]);
    }

    private static String describe(int[][] lines) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < lines.length; i++) {
            sb.append(i == 0 ? "" : " ").append(lines[i][0]).append("+").append(lines[i][1]);
        }
        return sb.append("]").toString();
    }

    /**
     * The cells' boundary lines {@code b[0..n]} (each line's first pixel) from the {@code n - 1} interior lines found:
     * they must all be one thickness and one pitch apart; the outer two are a pitch beyond. Null (with the failure noted)
     * when the lines are not there to measure.
     */
    private int[] lattice(String tag, int[][] found, int n) {
        if (found.length != n - 1 || n < 3) {
            check(false, tag + ": found " + found.length + " interior grid line(s), want " + (n - 1) + ": "
                    + describe(found));
            return null;
        }
        int pitch = found[1][0] - found[0][0];
        for (int i = 0; i < found.length; i++) {
            if (found[i][1] != found[0][1]) {
                check(false, tag + ": line " + (i + 1) + " is " + found[i][1] + " px thick, line 1 " + found[0][1]
                        + ": " + describe(found));
            }
            if (i > 0 && found[i][0] - found[i - 1][0] != pitch) {
                check(false, tag + ": lines " + i + " and " + (i + 1) + " are " + (found[i][0] - found[i - 1][0])
                        + " px apart, lines 1 and 2 " + pitch + ": " + describe(found));
            }
        }
        int[] b = new int[n + 1];
        for (int i = 0; i <= n; i++) {
            b[i] = found[0][0] + (i - 1) * pitch;
        }
        return b;
    }

    /** The well's border sits exactly where one more line would: at least half its length dark. */
    private void border(BufferedImage img, String tag, int at, int from, int to, boolean vertical) {
        int dark = 0;
        int len = Math.max(1, to - from);
        for (int k = from; k < to; k++) {
            int x = vertical ? at : k;
            int y = vertical ? k : at;
            if (x < 0 || y < 0 || x >= img.getWidth() || y >= img.getHeight()) {
                continue;
            }
            int rgb = img.getRGB(x, y);
            if (((rgb >> 16) & 0xFF) <= 0x20 && ((rgb >> 8) & 0xFF) <= 0x20 && (rgb & 0xFF) <= 0x20) {
                dark++;
            }
        }
        if (dark * 2 < len) {
            check(false, tag + ": the well's border is not one pitch out from the last line (" + dark + " of " + len
                    + " px dark at " + (vertical ? "x " : "y ") + at + ") - that row/column is not the size of the rest");
        }
    }

    /**
     * One room: its color's bounding box inside its cells' span leaves the same gutter {@code g} to the lines on all
     * four sides; its cells are filled and an L's fourth cell is empty; its label is centered in it. Returns {g}.
     */
    private int[] room(BufferedImage img, String tag, Placed p, int[] lx, int[] ly, int t, List<DesignerKit.Run> runs,
                       int gs) {
        String who = tag + ": " + p.shape() + " " + p.name() + " at " + p.ax() + "," + p.az();
        int colour = p.colour() & 0xFFFFFF;
        int sx0 = lx[p.ax()];
        int sx1 = lx[p.ax() + p.w()] + t;
        int sy0 = ly[p.az()];
        int sy1 = ly[p.az() + p.h()] + t;
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = -1;
        int maxY = -1;
        int count = 0;
        for (int y = Math.max(0, sy0 - 2); y < Math.min(img.getHeight(), sy1 + 2); y++) {
            for (int x = Math.max(0, sx0 - 2); x < Math.min(img.getWidth(), sx1 + 2); x++) {
                if (near(img.getRGB(x, y) & 0xFFFFFF, colour, 2)) {
                    count++;
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        if (count == 0) {
            check(false, who + ": no pixel of its color " + String.format("#%06X", colour) + " inside its cells");
            return null;
        }
        int gl = minX - (sx0 + t);
        int gr = lx[p.ax() + p.w()] - (maxX + 1);
        int gt = minY - (sy0 + t);
        int gb = ly[p.az() + p.h()] - (maxY + 1);
        if (gl != gr || gl != gt || gl != gb || gl < 1) {
            check(false, who + ": drawn x " + minX + ".." + maxX + ", y " + minY + ".." + maxY + " leaves gutters left "
                    + gl + " right " + gr + " top " + gt + " bottom " + gb + " px to its cells' lines (x " + sx0 + ".."
                    + lx[p.ax() + p.w()] + ", y " + sy0 + ".." + ly[p.az() + p.h()] + ")");
        }
        int g = Math.max(1, gl);
        // Each tile: its inside (less the gutter and one more pixel) the room's color, an L's missing one empty.
        for (int dx = 0; dx < p.w(); dx++) {
            for (int dz = 0; dz < p.h(); dz++) {
                boolean isMissing = p.missing() != null && p.missing()[0] == dx && p.missing()[1] == dz;
                int x0 = lx[p.ax() + dx] + t + g + 1;
                int x1 = lx[p.ax() + dx + 1] - g - 1;
                int y0 = ly[p.az() + dz] + t + g + 1;
                int y1 = ly[p.az() + dz + 1] - g - 1;
                int in = 0;
                int all = 0;
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        all++;
                        if (near(img.getRGB(x, y) & 0xFFFFFF, colour, 2)) {
                            in++;
                        }
                    }
                }
                double frac = all == 0 ? 0 : in / (double) all;
                if (isMissing && frac > 0.02) {
                    check(false, who + ": its missing quarter " + dx + "," + dz + " is " + Math.round(frac * 100)
                            + "% room color - the L is drawn as its whole box");
                } else if (!isMissing && frac < 0.55) {
                    check(false, who + ": its tile " + dx + "," + dz + " is only " + Math.round(frac * 100)
                            + "% room color");
                }
            }
        }
        // The label: the text runs whose center falls in the room, centered on its shape (an L: its horizontal pair).
        int[] box = {minX, minY, maxX + 1, maxY + 1};
        if (p.missing() != null) {
            int row = p.missing()[1] == 0 ? 1 : 0;
            box = new int[]{lx[p.ax()] + t + g, ly[p.az() + row] + t + g, lx[p.ax() + 2] - g, ly[p.az() + row + 1] - g};
        }
        double ux0 = Double.MAX_VALUE;
        double uy0 = Double.MAX_VALUE;
        double ux1 = -1;
        double uy1 = -1;
        int n = 0;
        for (DesignerKit.Run r : runs) {
            double cx = (r.x() + r.w() / 2.0) * gs;
            double cy = (r.y() + r.h() / 2.0) * gs;
            if (cx >= minX && cx <= maxX && cy >= minY && cy <= maxY) {
                ux0 = Math.min(ux0, r.x() * gs);
                uy0 = Math.min(uy0, r.y() * gs);
                ux1 = Math.max(ux1, (r.x() + r.w()) * (double) gs);
                uy1 = Math.max(uy1, (r.y() + r.h()) * (double) gs);
                n++;
            }
        }
        if (n > 0) {
            labelsChecked++;
            double dx = (ux0 + ux1) / 2 - (box[0] + box[2]) / 2.0;
            double dy = (uy0 + uy1) / 2 - (box[1] + box[3]) / 2.0;
            double tol = 2.0 * gs;
            if (Math.abs(dx) > tol || Math.abs(dy) > tol) {
                check(false, who + ": its label's center is " + String.format("%.1f, %.1f", dx, dy)
                        + " px off its " + (p.missing() != null ? "horizontal pair" : "box") + "'s center");
            }
        }
        return new int[]{g};
    }

    // ============================================================================================ helpers

    private static void setWindow(ClientGameTestContext ctx, int w, int h, int gui) {
        ctx.getInput().resizeWindow(w, h);
        ctx.waitTicks(3);
        ctx.runOnClient(mc -> {
            mc.options.guiScale().set(gui);
            mc.resizeGui();
        });
        ctx.waitTicks(6);
    }

    private void check(boolean ok, String problem) {
        if (!ok) {
            failures.add(problem);
            if (failures.size() <= 200) {
                println("PROBLEM: " + problem);
            }
        }
    }

    private static void println(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}
