package dev.testkit.gametest.hx;

import com.mojang.blaze3d.platform.Window;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.state.gui.GuiRenderState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Custom Scoreboard made simpler (mod branch scoreboard-simple-1015, killer560 2026-10-15: "can you make the custom
 * scoreboard simpler. Also it should be by default aligned on the right side of the screen where the normal scoreboard
 * should be and not able to go off the right side of the screen."), 1661-1667. Every case runs on a real Hypixel-shaped
 * sidebar from the Hx server ({@code hub} template plus a Purse line), so "the board drew" is the in-game HUD layer's
 * own draw stamp, never a preview.
 * <ul>
 *   <li>1661 default position: against the right edge (gap 0-1) and vertically centered, at three window sizes and two
 *       GUI scales, with the board seen drawing at each.</li>
 *   <li>1662 Edit HUD: dragging the board past the right edge (real mouse) keeps the whole box on screen during the drag
 *       and after the drop, and so does dragging it past the top-left corner.</li>
 *   <li>1663 an old config (no anchor keys, snapping off, a HudConfig x hanging two thirds off the right edge, Auto Scale
 *       off so the saved x is used as it is) loads pulled on screen; its other settings load unchanged; its old default
 *       look moves to the new one while a changed color is kept.</li>
 *   <li>1664 the lines drawn by the in-game layer are the real sidebar's (area, purse, lobby, footer).</li>
 *   <li>1665 a rebuild that reads the same keeps the same line list object (the frames' caches hold).</li>
 *   <li>1666 the editor's Settings page: the short list, Advanced closed by default and opening on click, Snap X / Snap Y /
 *       Screen Margin gone, every button with a tooltip and no overlaps.</li>
 *   <li>1667 a screenshot of the board in game (run on the old jar for "before"), copied to
 *       {@code TESTKIT_SCOREBOARD_SHOTS} when that is set.</li>
 * </ul>
 */
final class HxScoreboardCases {

    private static final String CFG = "scoreboard.CustomScoreboardConfig";
    private static final String FEATURE = "scoreboard.CustomScoreboardFeature";
    private static final String ID = "custom_scoreboard";
    private static final String PURSE = "4,321,000";

    private HxScoreboardCases() {
    }

    static void register(Session s) {
        s.test("1661-hx-sb-default-right", HxScoreboardCases::defaultRight);
        s.test("1662-hx-sb-drag-clamp", HxScoreboardCases::dragClamp);
        s.test("1663-hx-sb-old-config", HxScoreboardCases::oldConfig);
        s.test("1664-hx-sb-real-lines", HxScoreboardCases::realLines);
        s.test("1665-hx-sb-list-kept", HxScoreboardCases::listKept);
        s.test("1666-hx-sb-settings-page", HxScoreboardCases::settingsPage);
        s.test("1667-hx-sb-shot", HxScoreboardCases::shot);
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    private static Object element() {
        return Mod.staticCall("hud.HudElementRegistry", "byId", ID);
    }

    /** {x, y, w, h} of the board's box where the mod draws it now, plus the GUI screen size {sw, sh}. */
    private static int[] box(Minecraft mc) {
        Object el = element();
        int[] pos = (int[]) Mod.staticCall("hud.HudElementRegistry", "resolvePosition", el);
        float scale = ((Number) Mod.staticCall("hud.HudElementRegistry", "resolveScale", el)).floatValue();
        int w = Math.round((Integer) Mod.call(el, "width") * scale);
        int h = Math.round((Integer) Mod.call(el, "height") * scale);
        Window win = mc.getWindow();
        return new int[]{pos[0], pos[1], w, h, win.getGuiScaledWidth(), win.getGuiScaledHeight()};
    }

    private static String str(int[] b) {
        return "box x" + b[0] + " y" + b[1] + " " + b[2] + "x" + b[3] + " on " + b[4] + "x" + b[5]
                + " (right gap " + (b[4] - b[0] - b[2]) + ", bottom gap " + (b[5] - b[1] - b[3]) + ")";
    }

    private static boolean onScreen(int[] b) {
        return b[0] >= 0 && b[1] >= 0 && b[0] + b[2] <= b[4] && b[1] + b[3] <= b[5];
    }

    /** Ms since the in-game layer last drew the board. */
    private static long sinceDrawn(Minecraft mc) {
        return (Long) Mod.staticCall("hud.HudSeen", "msSince", ID, System.currentTimeMillis());
    }

    private static void skyblock(Session c) {
        sidebar(c, "template", "hub", "extra", List.of("Purse: §6" + PURSE, "§7Late Summer 12th", "§711:40am"));
        tab(c, "template", "area", "area", "Hub");
        c.waitUntil("Skyblock (the sidebar title)", mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isOnSkyblock"), 100);
    }

    /** Puts the board in its default spot on either jar: the new anchor reset, or the old snap's Right / Center. */
    private static void defaultSpot(Minecraft mc) {
        Object cfg = Mod.cfg(CFG);
        try {
            Mod.call(cfg, "resetAnchor");
        } catch (AssertionError | RuntimeException e) {
            Mod.call(cfg, "setHorizontalSnap", Mod.enumValue(CFG + "$HorizontalSnap", "RIGHT"));
            Mod.call(cfg, "setVerticalSnap", Mod.enumValue(CFG + "$VerticalSnap", "CENTER"));
        }
        Mod.call(cfg, "save");
    }

    private static byte[] read(Path p) {
        try {
            return Files.exists(p) ? Files.readAllBytes(p) : null;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void write(Path p, byte[] data) {
        try {
            if (data == null) {
                Files.deleteIfExists(p);
            } else {
                Files.write(p, data);
            }
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /** Snapshot of the scoreboard config and HudConfig, put back (and reloaded) on close. */
    private static final class Saved implements AutoCloseable {
        private final Session c;
        private final Path cfgPath;
        private final Path hudPath;
        private final byte[] cfgData;
        private final byte[] hudData;
        private final int[] window;

        Saved(Session c) {
            this.c = c;
            cfgPath = c.onClient(mc -> (Path) Mod.field(CFG, "CONFIG_PATH"));
            hudPath = c.onClient(mc -> (Path) Mod.field("hud.HudConfig", "CONFIG_PATH"));
            cfgData = c.onClient(mc -> read(cfgPath));
            hudData = c.onClient(mc -> read(hudPath));
            window = c.onClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight(), mc.options.guiScale().get()});
        }

        @Override
        public void close() {
            c.onClient(mc -> {
                if (McCompat.screen(mc) != null) {
                    McCompat.setScreen(mc, null);
                }
                write(cfgPath, cfgData);
                write(hudPath, hudData);
                Mod.staticCall("hud.HudConfig", "load");
                Mod.staticCall(CFG, "load");
                return null;
            });
            setWindow(c, window[0], window[1], window[2]);
        }
    }

    private static void setWindow(Session c, int w, int h, int gui) {
        c.ctx().getInput().resizeWindow(w, h);
        c.ctx().waitTicks(3);
        c.ctx().runOnClient(mc -> {
            mc.options.guiScale().set(gui);
            mc.resizeGui();
        });
        c.ctx().waitTicks(6);
    }

    private static void enable(Session c) {
        c.onClient(mc -> {
            Object cfg = Mod.cfg(CFG);
            Mod.call(cfg, "setEnabled", true);
            Mod.call(cfg, "save");
            return null;
        });
    }

    private static void waitDrawn(Session c, String where) {
        c.ctx().waitTicks(12); // rebuilt every 5 ticks; then drawn on the next frames
        c.waitUntil("the board draws in game (" + where + ")", mc -> sinceDrawn(mc) < 300, 100);
    }

    // ---- 1661 -------------------------------------------------------------------------------------------------------

    private static void defaultRight(Session c) {
        try (Saved saved = new Saved(c)) {
            skyblock(c);
            enable(c);
            c.onClient(mc -> {
                defaultSpot(mc);
                return null;
            });
            List<String> bad = new ArrayList<>();
            int[][] sizes = {{1024, 640}, {1366, 768}, {1600, 900}};
            int[] scales = {2, 3};
            int checked = 0;
            for (int[] size : sizes) {
                for (int gui : scales) {
                    setWindow(c, size[0], size[1], gui);
                    waitDrawn(c, size[0] + "x" + size[1] + " gui " + gui);
                    int[] b = c.onClient(HxScoreboardCases::box);
                    int rightGap = b[4] - b[0] - b[2];
                    int centerDelta = (b[1] + b[3] / 2) - b[5] / 2;
                    String where = size[0] + "x" + size[1] + " GUI " + gui + ": " + str(b) + ", center off by " + centerDelta;
                    c.note(where);
                    checked++;
                    if (!onScreen(b)) {
                        bad.add("off screen at " + where);
                    }
                    if (rightGap < 0 || rightGap > 1) {
                        bad.add("not against the right edge at " + where);
                    }
                    if (Math.abs(centerDelta) > 1) {
                        bad.add("not vertically centered at " + where);
                    }
                }
            }
            c.check(checked == 6, "only " + checked + " of 6 window/scale combinations were measured");
            c.check(bad.isEmpty(), String.join("; ", bad));
        }
    }

    // ---- 1662 -------------------------------------------------------------------------------------------------------

    /** GUI coordinates of the open screen (Auto Scale applied, as HxGuiHudCases does) to window pixels. */
    private static double[] toWindow(Session c, double sx, double sy) {
        return c.onClient(mc -> {
            float f = ((Number) Mod.staticCall("hud.AutoScale", "appliedFactor", McCompat.screen(mc))).floatValue();
            Window w = mc.getWindow();
            return new double[]{sx * f * w.getScreenWidth() / (double) w.getGuiScaledWidth(),
                    sy * f * w.getScreenHeight() / (double) w.getGuiScaledHeight()};
        });
    }

    private static void click(Session c, net.minecraft.client.gui.components.AbstractWidget w) {
        moveTo(c, w.getX() + w.getWidth() / 2.0, w.getY() + w.getHeight() / 2.0);
        c.ctx().waitTicks(2);
        c.ctx().getInput().pressMouse(0);
        c.ctx().waitTicks(3);
    }

    private static void moveTo(Session c, double sx, double sy) {
        double[] a = toWindow(c, sx, sy);
        c.ctx().getInput().setCursorPos(a[0], a[1]);
    }

    @SuppressWarnings("unchecked")
    private static int[] liveBox(Screen s, Minecraft mc) {
        int[] pos = ((Map<String, int[]>) Mod.field(s, "livePositions")).get(ID);
        int[] b = box(mc);
        return pos == null ? null : new int[]{pos[0], pos[1], b[2], b[3], s.width, s.height};
    }

    /** Press inside the board's box at ({@code fx}, {@code fy}) of its size, drag the mouse to (tx, ty), report the live
     *  box just before the release and the box after it. */
    private static int[][] drag(Session c, Screen s, double fx, double fy, double tx, double ty) {
        int[] start = c.onClient(mc -> liveBox(s, mc));
        double x0 = start[0] + start[2] * fx;
        double y0 = start[1] + start[3] * fy;
        moveTo(c, x0, y0);
        c.ctx().waitTicks(2);
        c.ctx().getInput().holdMouse(0);
        c.ctx().waitTicks(2);
        int steps = 10;
        for (int i = 1; i <= steps; i++) {
            moveTo(c, x0 + (tx - x0) * i / steps, y0 + (ty - y0) * i / steps);
            c.ctx().waitTicks(1);
        }
        c.ctx().waitTicks(2);
        String dragging = c.onClient(mc -> String.valueOf(Mod.field(s, "draggingId")));
        int[] during = c.onClient(mc -> liveBox(s, mc));
        c.ctx().getInput().releaseMouse(0);
        c.ctx().waitTicks(3);
        int[] after = c.onClient(mc -> liveBox(s, mc));
        c.note("drag from " + (int) x0 + "," + (int) y0 + " to " + (int) tx + "," + (int) ty + ": dragging '" + dragging
                + "'; during " + str(during) + "; after " + str(after));
        c.check(ID.equals(dragging), "the press did not pick the board up (dragging " + dragging + ")");
        return new int[][]{start, during, after};
    }

    private static void dragClamp(Session c) {
        try (Saved saved = new Saved(c)) {
            skyblock(c);
            enable(c);
            c.onClient(mc -> {
                defaultSpot(mc);
                return null;
            });
            setWindow(c, 1366, 768, 2);
            waitDrawn(c, "before Edit HUD");
            Screen s = c.onClient(mc -> {
                try {
                    Screen e = (Screen) Mod.cls("hud.HudEditorScreen").getConstructor(Screen.class).newInstance((Object) null);
                    McCompat.setScreen(mc, e);
                    return e;
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            c.ctx().waitTicks(4);
            c.check(c.onClient(mc -> McCompat.screen(mc) == s), "Edit HUD did not open");
            int[] listed = c.onClient(mc -> liveBox(s, mc));
            c.check(listed != null, "Edit HUD does not list the Custom Scoreboard");
            c.note("listed at " + str(listed));

            // Move it left first (so there is room to throw it at the right edge), then grab it near its left edge
            // and pull the mouse to the right edge: an unclamped box would end up almost wholly off screen.
            drag(c, s, 0.5, 0.5, listed[4] * 0.3, listed[5] * 0.5);
            int[][] right = drag(c, s, 0.1, 0.5, listed[4] - 1, listed[5] * 0.5);
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
            waitDrawn(c, "after the right-edge drag");
            int[] drawnRight = c.onClient(HxScoreboardCases::box);
            c.note("in game after the right-edge drop: " + str(drawnRight));

            // And the other way: grab it near its bottom-right and pull to the top-left corner.
            c.onClient(mc -> {
                McCompat.setScreen(mc, s);
                return null;
            });
            c.ctx().waitTicks(4);
            int[][] corner = drag(c, s, 0.9, 0.9, 1, 1);
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
            waitDrawn(c, "after the top-left drag");
            int[] drawnCorner = c.onClient(HxScoreboardCases::box);
            c.note("in game after the top-left drop: " + str(drawnCorner));

            c.check(right[1][0] > right[0][0], "the right-edge drag did not move the box right (" + right[0][0] + " -> "
                    + right[1][0] + ")");
            c.check(onScreen(right[1]), "while dragged at the right edge the box went off screen: " + str(right[1]));
            c.check(onScreen(right[2]), "dropped at the right edge the box is off screen: " + str(right[2]));
            c.check(onScreen(drawnRight), "in game after the right-edge drop the board is off screen: " + str(drawnRight));
            c.check(drawnRight[4] - drawnRight[0] - drawnRight[2] <= 1, "dropped against the right edge, the board is "
                    + (drawnRight[4] - drawnRight[0] - drawnRight[2]) + " px from it");
            c.check(onScreen(corner[1]), "while dragged past the top left the box went off screen: " + str(corner[1]));
            c.check(onScreen(corner[2]), "dropped past the top left the box is off screen: " + str(corner[2]));
            c.check(onScreen(drawnCorner), "in game after the top-left drop the board is off screen: " + str(drawnCorner));
            c.check(drawnCorner[0] <= 1 && drawnCorner[1] <= 1, "dropped in the top-left corner, the board is at "
                    + drawnCorner[0] + "," + drawnCorner[1]);
        }
    }

    // ---- 1663 -------------------------------------------------------------------------------------------------------

    private static void oldConfig(Session c) {
        try (Saved saved = new Saved(c)) {
            skyblock(c);
            setWindow(c, 1366, 768, 2);
            // The board's size first (default spot), so the old x can be put two thirds off the right edge.
            enable(c);
            c.onClient(mc -> {
                defaultSpot(mc);
                return null;
            });
            waitDrawn(c, "measuring");
            int[] measured = c.onClient(HxScoreboardCases::box);
            int oldX = measured[4] - measured[2] / 3;
            int oldY = 40;
            String json = "{\n"
                    + "  \"enabled\": true,\n"
                    + "  \"horizontalSnap\": \"NONE\",\n"
                    + "  \"verticalSnap\": \"NONE\",\n"
                    + "  \"margin\": 0,\n"
                    + "  \"lineSpacing\": 3,\n"
                    + "  \"textShadow\": false,\n"
                    + "  \"customTitle\": \"&&bOLD TITLE\",\n"
                    + "  \"backgroundColor\": " + 0xAA1A1108 + ",\n"
                    + "  \"padding\": 5,\n"
                    + "  \"roundedCorners\": true,\n"
                    + "  \"borderEnabled\": true,\n"
                    + "  \"borderColor\": " + 0xFF3366FF + "\n"
                    + "}\n";
            c.onClient(mc -> {
                write((Path) Mod.field(CFG, "CONFIG_PATH"), json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                Object hud = Mod.staticCall("hud.HudConfig", "getInstance");
                Mod.call(hud, "setAutoScale", false);
                Mod.call(hud, "setPosition", ID, oldX, oldY);
                Mod.call(hud, "save");
                Mod.staticCall(CFG, "load");
                return null;
            });
            c.note("old config: snapping off, HudConfig x " + oldX + " (board " + measured[2] + " wide on a " + measured[4]
                    + " wide screen, so " + (oldX + measured[2] - measured[4]) + " px past the right edge), y " + oldY);
            waitDrawn(c, "after loading the old config");
            int[] b = c.onClient(HxScoreboardCases::box);
            c.note("drawn " + str(b));
            String loaded = c.onClient(mc -> {
                Object cfg = Mod.cfg(CFG);
                return "lineSpacing " + Mod.call(cfg, "getLineSpacing") + ", textShadow " + Mod.call(cfg, "isTextShadow")
                        + ", title " + Mod.call(cfg, "getCustomTitle") + ", background " + Integer.toHexString((Integer) Mod.call(cfg, "getBackgroundColor"))
                        + ", padding " + Mod.call(cfg, "getPadding") + ", rounded " + Mod.call(cfg, "isRoundedCorners")
                        + ", border " + Mod.call(cfg, "isBorderEnabled") + " " + Integer.toHexString((Integer) Mod.call(cfg, "getBorderColor"));
            });
            c.note("loaded: " + loaded);
            c.check(onScreen(b), "the old off-screen position still draws off screen: " + str(b));
            c.check(Math.abs(b[1] - oldY) <= 1, "the old y " + oldY + " was not kept (" + b[1] + ")");
            c.check(loaded.startsWith("lineSpacing 3, textShadow false, title &&bOLD TITLE"),
                    "the old config's settings did not load unchanged: " + loaded);
            c.check(loaded.contains("background 50000000") && loaded.contains("padding 2") && loaded.contains("rounded false"),
                    "the old default look did not move to the new one: " + loaded);
            c.check(loaded.contains("border true 3366ff"), "a border he recolored was not kept: " + loaded);
            // Saved back: the anchor is written, and loading again gives the same spot.
            String file = c.onClient(mc -> new String(read((Path) Mod.field(CFG, "CONFIG_PATH")),
                    java.nio.charset.StandardCharsets.UTF_8));
            c.check(file.contains("\"anchorSide\""), "the converted position was not saved as an anchor");
            c.onClient(mc -> {
                Mod.staticCall(CFG, "load");
                return null;
            });
            waitDrawn(c, "after reloading");
            int[] again = c.onClient(HxScoreboardCases::box);
            c.check(again[0] == b[0] && again[1] == b[1], "reloaded, the board moved from " + str(b) + " to " + str(again));
        }
    }

    // ---- 1664 -------------------------------------------------------------------------------------------------------

    /** The text the in-game layer draws this frame (its private drawInGame into a fresh render state). */
    private static String drawnText(Minecraft mc) {
        GuiRenderState state = new GuiRenderState();
        GuiGraphicsExtractor g = new GuiGraphicsExtractor(mc, state, -1, -1);
        Mod.staticCall(FEATURE, "drawInGame", g);
        StringBuilder sb = new StringBuilder();
        state.forEachText(t -> sb.append(dev.testkit.gametest.TextRuns.string(t)).append(" | "));
        return sb.toString();
    }

    private static void realLines(Session c) {
        try (Saved saved = new Saved(c)) {
            skyblock(c);
            enable(c);
            waitDrawn(c, "real lines");
            String text = c.onClient(HxScoreboardCases::drawnText);
            c.note("drawn: " + text);
            c.check(text.contains("Village"), "the area line from the sidebar is not drawn: " + text);
            c.check(text.contains(PURSE), "the sidebar's purse " + PURSE + " is not drawn: " + text);
            c.check(text.contains("Purse"), "no Purse line is drawn: " + text);
            c.check(text.toLowerCase(java.util.Locale.ROOT).contains("skyblock"), "no title is drawn: " + text);
        }
    }

    // ---- 1665 -------------------------------------------------------------------------------------------------------

    private static void listKept(Session c) {
        try (Saved saved = new Saved(c)) {
            skyblock(c);
            enable(c);
            waitDrawn(c, "list identity");
            Object first = c.onClient(mc -> Mod.field(FEATURE, "current"));
            c.ctx().waitTicks(25); // five rebuilds of an unchanged sidebar
            Object later = c.onClient(mc -> Mod.field(FEATURE, "current"));
            c.note("after 25 ticks: same list object " + (first == later) + " (" + ((List<?>) later).size() + " lines)");
            c.check(!((List<?>) first).isEmpty(), "the board has no lines");
            c.check(first == later, "an unchanged sidebar rebuilt the board into a new list (the frames re-measure it)");
            // A real change does make a new one.
            sidebar(c, "template", "hub", "extra", List.of("Purse: §6" + "5,000,000", "§7Late Summer 12th", "§711:40am"));
            c.waitUntil("the board follows a purse change", mc -> Mod.field(FEATURE, "current") != later
                    && Mod.field(FEATURE, "current").toString().contains("5,000,000"), 100);
        }
    }

    // ---- 1666 -------------------------------------------------------------------------------------------------------

    private static List<String> labels(Screen s) {
        List<String> out = new ArrayList<>();
        for (var child : s.children()) {
            if (child instanceof net.minecraft.client.gui.components.AbstractWidget w) {
                out.add(net.minecraft.ChatFormatting.stripFormatting(w.getMessage().getString()));
            }
        }
        return out;
    }

    private static void settingsPage(Session c) {
        try (Saved saved = new Saved(c)) {
            enable(c);
            c.onClient(mc -> {
                Mod.call(Mod.cfg(CFG), "setEditorAdvancedOpen", false);
                return null;
            });
            setWindow(c, 1366, 768, 2);
            Screen s = c.onClient(mc -> {
                Mod.staticCall("scoreboard.ScoreboardEditorScreen", "resetSession");
                try {
                    Screen e = (Screen) Mod.cls("scoreboard.ScoreboardEditorScreen").getConstructor(Screen.class)
                            .newInstance((Object) null);
                    McCompat.setScreen(mc, e);
                    return e;
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            c.ctx().waitTicks(3);
            List<String> pages = c.onClient(mc -> labels(s));
            c.check(pages.contains("Settings") && !pages.contains("General") && !pages.contains("Background"),
                    "page buttons are " + pages);
            net.minecraft.client.gui.components.AbstractWidget settingsBtn = c.onClient(mc -> {
                for (var child : s.children()) {
                    if (child instanceof net.minecraft.client.gui.components.AbstractWidget w
                            && "Settings".equals(w.getMessage().getString())) {
                        return w;
                    }
                }
                return null;
            });
            c.check(settingsBtn != null, "no Settings page button");
            click(c, settingsBtn);
            Screen now = c.onClient(McCompat::screen);
            List<String> closed = c.onClient(mc -> labels(now));
            c.note("Settings page, Advanced closed: " + closed);
            c.check(closed.stream().anyMatch(l -> l.equals("▶ Advanced")), "no closed Advanced row: " + closed);
            c.check(closed.stream().noneMatch(l -> l.startsWith("Padding") || l.startsWith("Border")),
                    "box settings show with Advanced closed: " + closed);
            c.check(closed.stream().anyMatch(l -> l.startsWith("Reset Position")), "no Reset Position: " + closed);
            c.check(closed.size() <= 18, closed.size() + " widgets on the closed Settings page");
            net.minecraft.client.gui.components.AbstractWidget adv = c.onClient(mc -> {
                for (var child : now.children()) {
                    if (child instanceof net.minecraft.client.gui.components.AbstractWidget w
                            && w.getMessage().getString().endsWith("Advanced")) {
                        return w;
                    }
                }
                return null;
            });
            click(c, adv);
            boolean remembered = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(CFG), "isEditorAdvancedOpen"));
            c.check(remembered, "clicking Advanced did not open it (setting still closed)");
            List<String> open = c.onClient(mc -> labels(now));
            c.note("Settings page, Advanced open: " + open);
            c.check(open.stream().anyMatch(l -> l.startsWith("Padding")) && open.stream().anyMatch(l -> l.startsWith("Hide Vanilla Scoreboard")),
                    "Advanced opened without its settings: " + open);
            List<String> all = new ArrayList<>(closed);
            all.addAll(open);
            c.check(all.stream().noneMatch(l -> l.startsWith("Snap X") || l.startsWith("Snap Y") || l.startsWith("Screen Margin")),
                    "a removed setting is still shown: " + all);
            List<String> noTip = c.onClient(mc -> {
                List<String> out = new ArrayList<>();
                for (var child : now.children()) {
                    if (child instanceof net.minecraft.client.gui.components.AbstractWidget w
                            && !(w instanceof net.minecraft.client.gui.components.EditBox)
                            && !(w instanceof net.minecraft.client.gui.components.StringWidget)) {
                        String tip = (String) Mod.staticCall("gui.SettingTooltips", "describe", "Custom Scoreboard",
                                w.getMessage().getString());
                        if (tip == null || tip.isBlank()) {
                            out.add(w.getMessage().getString());
                        }
                    }
                }
                return out;
            });
            c.check(noTip.isEmpty(), "buttons without a tooltip: " + noTip);
        }
    }

    // ---- 1667 -------------------------------------------------------------------------------------------------------

    private static void shot(Session c) throws Exception {
        try (Saved saved = new Saved(c)) {
            skyblock(c);
            enable(c);
            setWindow(c, 1366, 768, 2);
            c.onClient(mc -> {
                defaultSpot(mc);
                mc.options.hideGui = false;
                return null;
            });
            waitDrawn(c, "screenshot");
            int[] b = c.onClient(HxScoreboardCases::box);
            c.note("board " + str(b));
            Path p = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(c.name() + "-board"));
            c.note("screenshot " + p);
            c.check(Files.exists(p), "no screenshot was written");
            String dir = System.getenv("TESTKIT_SCOREBOARD_SHOTS");
            if (dir != null && !dir.isBlank()) {
                Path out = Path.of(dir);
                Files.createDirectories(out);
                Files.copy(p, out.resolve(p.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                c.note("copied to " + out);
            }
        }
    }
}
