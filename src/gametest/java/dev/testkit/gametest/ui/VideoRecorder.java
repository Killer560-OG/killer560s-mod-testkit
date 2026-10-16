package dev.testkit.gametest.ui;

import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.Report;

import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Records one video clip as 1920x1080 PNG frames at 60 frames per second of game time, for the YouTube pilot
 * (1900-video-general, {@link VideoGeneralSuite}). Builds on 1569/1596's approach (one PNG per 1/60 s of a driven clock)
 * and adds two things those did not need:
 *
 * <ul>
 *   <li><b>Sub-tick frames.</b> The gametest lockstep runs one client tick per frame; recorded that way the world plays
 *       three times too fast at 60 fps. Here the client ticks on every THIRD frame and the two frames between are
 *       re-rendered by the screenshot itself at partial ticks 1/3 and 2/3 ({@code withDeltaTicks}), so mobs, the sim and
 *       every HUD move at real speed. The camera is posed per frame by the scenario ({@link #pose}), with the old
 *       position equal to the new, so it is exact at any partial tick.</li>
 *   <li><b>The cursor.</b> A screenshot is the framebuffer and never contains the OS cursor. Every frame's cursor
 *       position (window pixels), whether it is shown and whether the button is down go to {@code cursor.csv}; the
 *       video build draws the arrow from it.</li>
 * </ul>
 *
 * <p>The mod menu's animation clock ({@code gui.MenuAnim}) and the title screen's scene clock are stepped exactly 1/60 s
 * per frame, so their animations play at real speed however long a screenshot takes. Things timed by the wall clock
 * (a sim run's timer, a tooltip's delay) are not, which is why the clips avoid them.
 */
final class VideoRecorder {

    static final double FRAME_MS = 1000.0 / 60.0;

    final UiCase c;
    final Path dir;
    private final BufferedWriter cursorLog;
    private int frame;
    /** Driven clock, ms. */
    double t = 1_000_000;
    /** 0..2: which third of a tick the next frame is. */
    private int sub;
    private boolean titleClock;

    double cx = -1;
    double cy = -1;
    boolean shown;
    boolean down;

    VideoRecorder(UiCase c, String clip) throws IOException {
        this.c = c;
        this.dir = base().resolve(clip);
        if (Files.isDirectory(dir)) {
            try (Stream<Path> old = Files.list(dir)) {
                for (Path p : old.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
        Files.createDirectories(dir);
        cursorLog = Files.newBufferedWriter(dir.resolve("cursor.csv"), StandardCharsets.UTF_8);
        cursorLog.write("frame,x,y,shown,down\n");
        // The driven clock never goes back. Each clip used to restart it at 1,000,000 ms, behind where the previous clip
        // left it; a menu opened before this clip had stamped its open fade at that later moment, and MenuAnim reads a
        // start up to 1 s ahead as "not begun", so the whole menu went transparent for a second once the new clock came
        // within 1 s of the old one (14.6 s into Home after the 15.6 s sim-run clip; 1921). Start a second past now.
        double now = c.onClient(mc -> ((Number) Mod.staticCall("gui.MenuAnim", "now")).longValue() / 1_000_000.0);
        t = Math.max(t, Math.ceil(now) + 1000);
    }

    /** Where clips go: {@code -PvideoOut=<dir>}, else {@code <report>/video-general}. */
    static Path base() {
        String out = System.getProperty("testkit.videoOut", "");
        return out.isBlank() ? Report.dir().resolve("video-general") : Path.of(out);
    }

    /** Also step the title screen's scene clock (mainmenu.MainMenuScenes) with the frames. */
    VideoRecorder titleClock() {
        titleClock = true;
        return this;
    }

    int frames() {
        return frame;
    }

    /** Fixes the driven clocks at the current {@link #t} (call before an input, so a tween starts at that moment). */
    void clocks() {
        double now = t;
        boolean title = titleClock;
        c.onClient(mc -> {
            Mod.staticCall("gui.MenuAnim", "setClockForTest", now);
            if (title) {
                Mod.staticCall("mainmenu.MainMenuScenes", "setClockForTest", now);
            }
            return null;
        });
    }

    /** One frame: the clocks, a client tick on every third frame, the picture at this third of the tick. */
    void frame() throws IOException {
        clocks();
        if (sub == 0) {
            c.ticks(1);
        }
        float delta = (sub + 1) / 3f;
        String name = String.format(Locale.ROOT, "frame_%05d", frame);
        c.ctx().takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix().withDeltaTicks(delta)
                .withDestinationDir(dir));
        cursorLog.write(String.format(Locale.ROOT, "%d,%.1f,%.1f,%d,%d\n", frame, cx, cy, shown ? 1 : 0, down ? 1 : 0));
        frame++;
        sub = (sub + 1) % 3;
        t += FRAME_MS;
    }

    /** Names the next frame in {@code markers.csv}, so the edit can cut on a moment instead of a guessed second. */
    void mark(String label) throws IOException {
        Files.writeString(dir.resolve("markers.csv"), frame + "," + label + "\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }

    void hold(double ms) throws IOException {
        int n = (int) Math.round(ms / FRAME_MS);
        for (int i = 0; i < n; i++) {
            frame();
        }
    }

    /** Puts the real cursor at window pixel (x, y) without a frame. */
    void cursorAt(double x, double y) {
        cx = x;
        cy = y;
        shown = true;
        c.ctx().getInput().setCursorPos(x, y);
    }

    void hideCursor() {
        shown = false;
        c.ctx().getInput().setCursorPos(2, 2);
    }

    /**
     * Glides the real cursor to window pixel (x, y) over {@code ms} like a hand does: eased in and out, along a slight
     * arc, a frame per step. A button held down stays down (a drag).
     */
    void glide(double x, double y, double ms) throws IOException {
        if (!shown || cx < 0) {
            cursorAt(x, y);
            frame();
            return;
        }
        int n = Math.max(1, (int) Math.round(ms / FRAME_MS));
        double x0 = cx;
        double y0 = cy;
        double dx = x - x0;
        double dy = y - y0;
        double len = Math.hypot(dx, dy);
        // a bow of up to 4% of the distance, to one side, so the path is not a ruler line
        double bow = Math.min(40, len * 0.04);
        double nx = len == 0 ? 0 : -dy / len;
        double ny = len == 0 ? 0 : dx / len;
        for (int i = 1; i <= n; i++) {
            double p = i / (double) n;
            double e = p < 0.5 ? 4 * p * p * p : 1 - Math.pow(-2 * p + 2, 3) / 2;
            double arc = Math.sin(Math.PI * e) * bow;
            cursorAt(x0 + dx * e + nx * arc, y0 + dy * e + ny * arc);
            frame();
        }
    }

    /** A left click where the cursor is: down for two frames, up, as the real mouse does it. */
    void click() throws IOException {
        clocks();
        down = true;
        c.ctx().getInput().holdMouse(0);
        frame();
        frame();
        clocks();
        c.ctx().getInput().releaseMouse(0);
        down = false;
        frame();
    }

    void press() {
        clocks();
        down = true;
        c.ctx().getInput().holdMouse(0);
    }

    void release() {
        clocks();
        c.ctx().getInput().releaseMouse(0);
        down = false;
    }

    /** One notch of the wheel where the cursor is. */
    void wheel(double amount) {
        clocks();
        c.ctx().getInput().scroll(amount);
    }

    /** Menu layout units of a ModScreen (GUI scale times its Auto Scale factor) to window pixels. */
    static double[] menuToWindow(UiCase c, Screen s, double gx, double gy) {
        return c.onClient(mc -> {
            double f = ((Number) Mod.staticCall("hud.AutoScale", "appliedFactor", s)).doubleValue();
            double scale = mc.getWindow().getGuiScale() * f;
            return new double[]{gx * scale, gy * scale};
        });
    }

    /** Plain GUI coordinates (a vanilla screen, the HUD editor) to window pixels. */
    static double[] guiToWindow(Minecraft mc, double gx, double gy) {
        var win = mc.getWindow();
        return new double[]{gx * win.getScreenWidth() / (double) win.getGuiScaledWidth(),
                gy * win.getScreenHeight() / (double) win.getGuiScaledHeight()};
    }

    /**
     * Poses the camera (the local player) exactly: position, yaw and pitch, with the previous values set equal so any
     * partial tick renders this pose, and no motion left to carry it on. Client thread.
     */
    static void pose(Minecraft mc, double x, double y, double z, float yaw, float pitch) {
        var p = mc.player;
        p.setPos(x, y, z);
        p.xo = x;
        p.yo = y;
        p.zo = z;
        p.xOld = x;
        p.yOld = y;
        p.zOld = z;
        p.setDeltaMovement(0, 0, 0);
        p.setYRot(yaw);
        p.setXRot(Math.max(-90f, Math.min(90f, pitch)));
        p.yRotO = yaw;
        p.xRotO = p.getXRot();
        p.setYHeadRot(yaw);
        p.yHeadRotO = yaw;
        p.setYBodyRot(yaw);
        p.yBodyRotO = yaw;
    }

    void close() throws IOException {
        cursorLog.close();
        c.note("wrote " + frame + " frames (" + String.format(Locale.ROOT, "%.1f", frame / 60.0) + " s) to " + dir);
        System.out.println("[video] " + dir.getFileName() + ": " + frame + " frames in " + dir.toAbsolutePath());
    }
}
