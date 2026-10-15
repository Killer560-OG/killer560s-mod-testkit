package dev.testkit.gametest.ui;

import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.Report;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * 1596-menuanim-video: a scripted ~25 s tour of the mod menu's animations, one PNG per 1/60 s of animation clock, written
 * to {@code <report>/menu-anim-video/frame_NNNNN.png} for {@code ffmpeg -framerate 60}. The clock is stepped exactly
 * 1/60 s per frame, so the clip plays at real speed however long each screenshot takes. Only runs when named.
 */
final class MenuAnimVideo {

    private MenuAnimVideo() {
    }

    private static final double FRAME_MS = 1000.0 / 60.0;

    private static UiCase c;
    private static Screen s;
    private static Path dir;
    private static double t;
    private static int frameNo;
    private static double curX;
    private static double curY;

    /** One frame at the current clock, saved; then the clock moves on 1/60 s. */
    private static void capture() throws Exception {
        MenuAnimSuite.frame(c, t);
        Path taken = c.ctx().takeScreenshot("menuanim-video-frame");
        MenuAnimSuite.keep(taken, dir, String.format(Locale.ROOT, "frame_%05d.png", frameNo++));
        t += FRAME_MS;
    }

    private static void hold(double ms) throws Exception {
        int n = (int) Math.round(ms / FRAME_MS);
        for (int i = 0; i < n; i++) {
            capture();
        }
    }

    /** Glides the real cursor to (x, y) in the menu's layout units over {@code ms}, capturing each frame (ease in-out). */
    private static void move(double x, double y, double ms) throws Exception {
        int n = Math.max(1, (int) Math.round(ms / FRAME_MS));
        double x0 = curX;
        double y0 = curY;
        for (int i = 1; i <= n; i++) {
            double p = i / (double) n;
            double e = p < 0.5 ? 2 * p * p : 1 - Math.pow(-2 * p + 2, 2) / 2;
            curX = x0 + (x - x0) * e;
            curY = y0 + (y - y0) * e;
            MenuAnimSuite.cursor(c, s, curX, curY);
            capture();
        }
    }

    private static void moveTo(int[] box, double ms) throws Exception {
        move(box[0] + box[2] / 2.0, box[1] + box[3] / 2.0, ms);
    }

    private static void click() {
        double x = curX;
        double y = curY;
        MenuAnimSuite.clock(c, t);
        c.onClient(mc -> {
            MenuACases.press(s, x, y);
            return null;
        });
    }

    private static void wheel(double amount) {
        double x = curX;
        double y = curY;
        MenuAnimSuite.clock(c, t);
        c.onClient(mc -> s.mouseScrolled(x, y, 0, amount));
    }

    @SuppressWarnings("unchecked")
    private static int[] sideBox(String name) {
        return c.onClient(mc -> {
            List<String> texts = (List<String>) Mod.call(s, "sideTexts");
            List<Object> side = (List<Object>) Mod.field(s, "side");
            for (int i = 0; i < texts.size(); i++) {
                if (texts.get(i).startsWith(name)) {
                    Object e = side.get(i);
                    return new int[]{(Integer) Mod.call(e, "x"), (Integer) Mod.call(e, "y"), (Integer) Mod.call(e, "w"),
                            (Integer) Mod.call(e, "h")};
                }
            }
            throw new AssertionError("no sidebar entry " + name);
        });
    }

    static void record(UiCase uc) throws Exception {
        c = uc;
        frameNo = 0;
        dir = Report.dir().resolve("menu-anim-video");
        String was = MenuAnimSuite.mode();
        MenuAnimSuite.mode("SMOOTH");
        int[] oldWindow = c.onClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight()});
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        c.ctx().getInput().resizeWindow(1920, 1080);
        c.onClient(mc -> {
            mc.options.guiScale().set(2);
            mc.resizeGui();
            return null;
        });
        c.ticks(3);
        try {
            t = 1_000_000;
            MenuAnimSuite.cursorAway(c);
            hold(400); // the world, before the menu
            MenuAnimSuite.clock(c, t);
            s = c.onClient(mc -> {
                try {
                    Screen m = MenuACases.open(mc);
                    Mod.call(m, "closeDrawer");
                    Mod.setField(Mod.ROOT + "gui.ModScreen", "gridScroll", 0);
                    Mod.call(m, "selectCategory", "Dungeons");
                    return m;
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            int[] grid = c.onClient(mc -> new int[]{(Integer) Mod.field(s, "gridX"), (Integer) Mod.field(s, "gridY"),
                    (Integer) Mod.field(s, "gridW"), (Integer) Mod.field(s, "gridH")});
            curX = grid[0] + grid[2] / 2.0;
            curY = grid[1] + grid[3] + 20;
            hold(900); // opens

            // ---- hover across a few cards ----
            List<Object> cards = c.onClient(mc -> (List<Object>) Mod.call(s, "cards"));
            for (int i = 0; i < Math.min(4, cards.size()); i++) {
                int[][] b = MenuAnimSuite.bounds(c, s, cards.get(i));
                move(b[0][0] + b[0][2] * 0.4, b[0][1] + b[0][3] * 0.5, 280);
                hold(220);
            }

            // ---- toggle three card switches (and back) ----
            for (int i = 0, done = 0; i < cards.size() && done < 3; i++) {
                Object tab = cards.get(i);
                if (MenuAnimSuite.cardState(tab) == null) {
                    continue;
                }
                int[][] b = MenuAnimSuite.bounds(c, s, tab);
                moveTo(b[1], 300);
                click();
                hold(450);
                click();
                hold(350);
                done++;
            }

            // ---- category switches ----
            for (String cat : new String[]{"HUD", "Chat", "Items", "Dungeons"}) {
                moveTo(sideBox(cat), 350);
                click();
                hold(650);
            }

            // ---- open a drawer, flip a row, scroll it ----
            cards = c.onClient(mc -> (List<Object>) Mod.call(s, "cards"));
            Object first = cards.get(1);
            int[][] fb = MenuAnimSuite.bounds(c, s, first);
            move(fb[0][0] + fb[0][2] * 0.35, fb[0][1] + fb[0][3] * 0.5, 350);
            click();
            hold(700);
            List<AbstractWidget> rows = MenuAnimSuite.switchRows(c, s);
            if (!rows.isEmpty()) {
                AbstractWidget row = rows.get(rows.size() - 1);
                int[] cb = c.onClient(mc -> (int[]) Mod.call(row, "controlBounds"));
                moveTo(cb, 350);
                click();
                hold(500);
                click();
                hold(400);
            }
            int[] drawer = c.onClient(mc -> new int[]{(Integer) Mod.field(s, "drawerX"), (Integer) Mod.field(s, "drawerY"),
                    (Integer) Mod.field(s, "drawerW"), (Integer) Mod.field(s, "drawerH")});
            move(drawer[0] + drawer[2] * 0.5, drawer[1] + drawer[3] * 0.55, 300);
            for (int i = 0; i < 3; i++) {
                wheel(-1);
                hold(260);
            }
            hold(300);
            for (int i = 0; i < 3; i++) {
                wheel(1);
                hold(220);
            }
            hold(300);

            // ---- switch to another feature's drawer, then close it with the X ----
            Object second = cards.get(2);
            int[][] sb = MenuAnimSuite.bounds(c, s, second);
            move(sb[0][0] + sb[0][2] * 0.35, sb[0][1] + sb[0][3] * 0.5, 350);
            click();
            hold(700);
            move(drawer[0] + drawer[2] - 12, drawer[1] + 12, 400);
            click();
            hold(700);

            // ---- smooth grid scroll ----
            move(grid[0] + grid[2] * 0.5, grid[1] + grid[3] * 0.6, 300);
            for (int i = 0; i < 4; i++) {
                wheel(-1);
                hold(200);
            }
            hold(300);
            for (int i = 0; i < 4; i++) {
                wheel(1);
                hold(180);
            }
            hold(300);

            // ---- a tooltip (its delay is wall time; the fade is clock time) ----
            int[][] tb = MenuAnimSuite.bounds(c, s, cards.get(0));
            move(tb[0][0] + tb[0][2] * 0.4, tb[0][1] + tb[0][3] * 0.45, 300);
            hold(100);
            Thread.sleep(400);
            hold(1500);

            // ---- close ----
            move(grid[0] + grid[2] * 0.5, grid[1] + grid[3] + 20, 250);
            MenuAnimSuite.clock(c, t);
            c.onClient(mc -> {
                MenuACases.key(s, InputConstants.KEY_ESCAPE, 0);
                return null;
            });
            hold(800);
            c.note("wrote " + frameNo + " frames (" + String.format(Locale.ROOT, "%.1f", frameNo / 60.0) + " s) to "
                    + dir.toAbsolutePath());
            System.out.println("[1596] frames " + frameNo + " in " + dir.toAbsolutePath());
        } finally {
            MenuAnimSuite.releaseClock(c);
            MenuAnimSuite.mode(was);
            c.ctx().getInput().resizeWindow(oldWindow[0], oldWindow[1]);
            c.onClient(mc -> {
                mc.options.guiScale().set(oldGui);
                mc.resizeGui();
                return null;
            });
            c.ticks(3);
        }
    }
}
