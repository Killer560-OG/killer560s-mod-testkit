package dev.testkit.gametest.hx;

import com.mojang.blaze3d.platform.Window;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.Latency;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * killer560's GUI/HUD items of 2026-10-11 (mod branch gui-hud), 716-726:
 * <ul>
 *   <li>716-720 Custom Scoreboard: the board is edited by dragging its own lines in the visual editor (real mouse
 *       input), a line dropped on the right-hand panel comes off, Not Active lines go back by drag (where dropped) or
 *       click (bottom), a clicked line shows its settings, the Settings page (once General and Background) with its
 *       Advanced section and tooltips, the tab is only the switch and the editor button, and a drag list keeps every wheel turn.</li>
 *   <li>721-722 Quiver Display: the Low Arrow Alert fires once per dip below the threshold and re-arms above it; the
 *       threshold snaps to 20..200 in 20s; the HUD line is drawn in the Text Color, and the settings survive a reload.</li>
 *   <li>723 Spring Boots: real sound packets charge the height, and the box is drawn in the chosen colour and style
 *       (screenshot diff against a no-box frame: Filled covers far more pixels than Outline).</li>
 *   <li>724 Ping: the Performance HUD's ping follows an injected 200 ms round trip ({@link Latency}), and no ping
 *       requests are sent while nothing shows the ping.</li>
 *   <li>725 GIF Player / DVD: "Open GIF / Audio Folder", "Dancing Cat Girl" without "(built-in)", and DVD loads the
 *       built-in GIF.</li>
 *   <li>726 Ability Timers are gone.</li>
 * </ul>
 */
final class HxGuiHudCases {

    private static final String SB_CFG = "scoreboard.CustomScoreboardConfig";
    private static final String SB_EDITOR = "scoreboard.ScoreboardEditorScreen";
    private static final String SB_ENTRY = "scoreboard.ScoreboardEntry";
    private static final String QUIVER_CFG = "quiver.QuiverDisplayConfig";
    private static final String QUIVER = "quiver.QuiverDisplayFeature";
    private static final String DA_CFG = "dungeonalerts.DungeonAlertsConfig";
    private static final String SPRING = "dungeonalerts.SpringBootsOverlay";
    private static final String PING = "util.PingTracker";
    private static final String LAG_CFG = "lagdisplay.LagDisplayConfig";
    private static final List<String> START = List.of("TITLE", "DATE", "ISLAND", "PURSE", "MAYOR", "FOOTER");

    private HxGuiHudCases() {
    }

    static void register(Session s) {
        s.test("716-hx-scoreboard-board-drag", HxGuiHudCases::boardDrag);
        s.test("717-hx-scoreboard-remove-add", HxGuiHudCases::removeAdd);
        s.test("718-hx-scoreboard-editor-settings", HxGuiHudCases::editorSettings);
        s.test("719-hx-scoreboard-tab-minimal", HxGuiHudCases::tabMinimal);
        s.test("720-hx-drag-list-wheel", HxGuiHudCases::listWheel);
        s.test("721-hx-quiver-low-alert", HxGuiHudCases::quiverAlert);
        s.test("722-hx-quiver-color-config", HxGuiHudCases::quiverConfig);
        s.test("723-hx-spring-boots-box", HxGuiHudCases::springBoots);
        s.test("724-hx-ping-measured", HxGuiHudCases::ping);
        s.test("725-hx-gif-dvd-builtin", HxGuiHudCases::gifDvd);
        s.test("726-hx-ability-timers-gone", HxGuiHudCases::abilityTimersGone);
    }

    // ---- scoreboard helpers ------------------------------------------------------------------------------------------

    private static Object entry(String name) {
        return Mod.enumValue(SB_ENTRY, name);
    }

    private static void setBoard(List<String> ids) {
        Object cfg = Mod.cfg(SB_CFG);
        List<Object> list = new ArrayList<>();
        for (String id : ids) {
            list.add(entry(id));
        }
        Mod.call(Mod.call(cfg, "entries"), "setActive", list);
        Mod.call(cfg, "save");
    }

    @SuppressWarnings("unchecked")
    private static List<String> board() {
        List<String> out = new ArrayList<>();
        for (Object e : (List<Object>) Mod.call(Mod.call(Mod.cfg(SB_CFG), "entries"), "active")) {
            out.add(((Enum<?>) e).name());
        }
        return out;
    }

    /** A config snapshot to put back after the case (the board and every field the editor can change). */
    private static byte[] saveFile(Session c) throws Exception {
        Path p = c.onClient(mc -> (Path) Mod.field(SB_CFG, "CONFIG_PATH"));
        return Files.exists(p) ? Files.readAllBytes(p) : null;
    }

    private static void restoreFile(Session c, byte[] saved) {
        c.onClient(mc -> {
            try {
                if (McCompat.screen(mc) != null) {
                    McCompat.setScreen(mc, null);
                }
                Path p = (Path) Mod.field(SB_CFG, "CONFIG_PATH");
                if (saved == null) {
                    Files.deleteIfExists(p);
                } else {
                    Files.write(p, saved);
                }
                Mod.staticCall(SB_CFG, "load");
                Mod.staticCall(SB_EDITOR, "resetSession");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            return null;
        });
    }

    private static Screen openEditor(Session c, List<String> ids) {
        Screen s = c.onClient(mc -> {
            Mod.call(Mod.cfg(SB_CFG), "setEnabled", true);
            setBoard(ids);
            Mod.staticCall(SB_EDITOR, "resetSession");
            Mod.staticCall("scoreboard.ScoreboardListEditor", "resetSession");
            try {
                Screen e = (Screen) Mod.cls(SB_EDITOR).getConstructor(Screen.class).newInstance((Object) null);
                McCompat.setScreen(mc, e);
                return e;
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        c.ctx().waitTicks(3);
        c.check(c.onClient(mc -> McCompat.screen(mc) == s), "the scoreboard editor did not open");
        return s;
    }

    private static int[] rect(Session c, Screen s, String method, String entry) {
        return c.onClient(mc -> (int[]) Mod.call(s, method, entry(entry)));
    }

    private static double[] toWindow(Session c, Screen s, double sx, double sy) {
        return c.onClient(mc -> {
            float f = ((Number) Mod.staticCall("hud.AutoScale", "appliedFactor", s)).floatValue();
            Window w = mc.getWindow();
            return new double[]{sx * f * w.getScreenWidth() / (double) w.getGuiScaledWidth(),
                    sy * f * w.getScreenHeight() / (double) w.getGuiScaledHeight()};
        });
    }

    private static void moveTo(Session c, Screen s, double sx, double sy) {
        double[] a = toWindow(c, s, sx, sy);
        c.ctx().getInput().setCursorPos(a[0], a[1]);
    }

    private static void click(Session c, Screen s, double sx, double sy) {
        moveTo(c, s, sx, sy);
        c.ctx().waitTicks(2);
        c.ctx().getInput().pressMouse(0);
        c.ctx().waitTicks(3);
    }

    /** Real press, move in steps, release. Returns whether the editor reported a line drag before the release. */
    private static boolean drag(Session c, Screen s, double x0, double y0, double x1, double y1) {
        moveTo(c, s, x0, y0);
        c.ctx().waitTicks(2);
        c.ctx().getInput().holdMouse(0);
        c.ctx().waitTicks(2);
        int steps = 8;
        for (int i = 1; i <= steps; i++) {
            moveTo(c, s, x0 + (x1 - x0) * i / steps, y0 + (y1 - y0) * i / steps);
            c.ctx().waitTicks(1);
        }
        c.ctx().waitTicks(2);
        boolean dragging = c.onClient(mc -> (Boolean) Mod.call(s, "isDraggingLine"));
        c.ctx().getInput().releaseMouse(0);
        c.ctx().waitTicks(3);
        return dragging;
    }

    private static double cx(int[] r) {
        return r[0] + r[2] / 2.0;
    }

    private static double cy(int[] r) {
        return r[1] + r[3] / 2.0;
    }

    @SuppressWarnings("unchecked")
    private static List<String> diskBoard(Session c) {
        return c.onClient(mc -> {
            Mod.staticCall(SB_CFG, "load");
            List<String> out = board();
            return out;
        });
    }

    // ---- 716 --------------------------------------------------------------------------------------------------------

    private static void boardDrag(Session c) throws Exception {
        byte[] saved = saveFile(c);
        try {
            Screen s = openEditor(c, START);
            int[] purse = rect(c, s, "blockRectOf", "PURSE");
            int[] date = rect(c, s, "blockRectOf", "DATE");
            c.check(purse != null && date != null, "PURSE/DATE have no block on the editor's board");
            c.note("PURSE block " + java.util.Arrays.toString(purse) + ", DATE block " + java.util.Arrays.toString(date));
            boolean dragging = drag(c, s, cx(purse), cy(purse), cx(purse), date[1] + 1);
            List<String> after = c.onClient(mc -> board());
            c.note("dragged PURSE onto DATE's top edge: drag reported " + dragging + ", board " + after);
            c.check(dragging, "the editor never reported a drag in progress (the press did not pick the line up)");
            c.check(after.equals(List.of("TITLE", "PURSE", "DATE", "ISLAND", "MAYOR", "FOOTER")),
                    "after the drag the board is " + after);
            int[] p2 = rect(c, s, "blockRectOf", "PURSE");
            int[] d2 = rect(c, s, "blockRectOf", "DATE");
            c.check(p2[1] < d2[1], "PURSE is not drawn above DATE after the drag (" + p2[1] + " vs " + d2[1] + ")");
            // A press that does not move is a click, never a move.
            int[] island = rect(c, s, "blockRectOf", "ISLAND");
            click(c, s, cx(island), cy(island));
            List<String> clicked = c.onClient(mc -> board());
            Object sel = c.onClient(mc -> Mod.staticCall(SB_EDITOR, "selectedEntry"));
            c.check(clicked.equals(after), "a plain click on ISLAND changed the board to " + clicked);
            c.check(sel == entry("ISLAND"), "a plain click on ISLAND selected " + sel);
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
            List<String> disk = diskBoard(c);
            c.note("reloaded from disk: " + disk);
            c.check(disk.equals(after), "the board reloaded from disk is " + disk + ", not " + after);
        } finally {
            restoreFile(c, saved);
        }
    }

    // ---- 717 --------------------------------------------------------------------------------------------------------

    private static void removeAdd(Session c) throws Exception {
        byte[] saved = saveFile(c);
        try {
            Screen s = openEditor(c, START);
            int[] mayor = rect(c, s, "blockRectOf", "MAYOR");
            int[] panel = c.onClient(mc -> (int[]) Mod.call(s, "panelRect"));
            boolean d1 = drag(c, s, cx(mayor), cy(mayor), cx(panel), panel[1] + panel[3] / 3.0);
            List<String> afterRemove = c.onClient(mc -> board());
            int[] mayorRow = rect(c, s, "inactiveRectOf", "MAYOR");
            c.note("MAYOR dragged onto the panel: drag " + d1 + ", board " + afterRemove + ", Not Active row "
                    + java.util.Arrays.toString(mayorRow));
            c.check(d1 && !afterRemove.contains("MAYOR") && afterRemove.size() == 5,
                    "dropping MAYOR on the panel left the board " + afterRemove);
            c.check(mayorRow != null || c.onClient(mc -> (Boolean) Mod.call(Mod.call(Mod.cfg(SB_CFG), "entries"),
                    "available").toString().contains("MAYOR")), "MAYOR is not offered under Not Active");

            // Drag a Not Active line onto the board just under TITLE.
            int[] lobby = rect(c, s, "inactiveRectOf", "LOBBY_CODE");
            int[] title = rect(c, s, "blockRectOf", "TITLE");
            c.check(lobby != null, "LOBBY_CODE has no Not Active row on screen");
            boolean d2 = drag(c, s, cx(lobby), cy(lobby), cx(title), title[1] + title[3] + 1);
            List<String> afterInsert = c.onClient(mc -> board());
            c.note("LOBBY_CODE dragged under TITLE: drag " + d2 + ", board " + afterInsert);
            c.check(d2 && afterInsert.size() == 6 && "LOBBY_CODE".equals(afterInsert.get(1)),
                    "dropping LOBBY_CODE under TITLE gave " + afterInsert);

            // A click adds at the bottom.
            int[] players = rect(c, s, "inactiveRectOf", "PLAYER_AMOUNT");
            c.check(players != null, "PLAYER_AMOUNT has no Not Active row on screen");
            click(c, s, cx(players), cy(players));
            List<String> afterClick = c.onClient(mc -> board());
            c.note("clicked PLAYER_AMOUNT: board " + afterClick);
            c.check(afterClick.size() == 7 && "PLAYER_AMOUNT".equals(afterClick.get(6)),
                    "clicking PLAYER_AMOUNT gave " + afterClick);
            int[] onBoard = rect(c, s, "blockRectOf", "PLAYER_AMOUNT");
            c.check(onBoard != null, "PLAYER_AMOUNT was added but has no block on the drawn board");
            c.check(rect(c, s, "inactiveRectOf", "PLAYER_AMOUNT") == null, "PLAYER_AMOUNT is still under Not Active");
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
            List<String> disk = diskBoard(c);
            c.check(disk.equals(afterClick), "reloaded from disk the board is " + disk);
        } finally {
            restoreFile(c, saved);
        }
    }

    // ---- 718 --------------------------------------------------------------------------------------------------------

    private static List<AbstractWidget> widgets(Screen s) {
        List<AbstractWidget> out = new ArrayList<>();
        for (var child : s.children()) {
            if (child instanceof AbstractWidget w) {
                out.add(w);
            }
        }
        return out;
    }

    private static AbstractWidget widget(Session c, Screen s, String prefix) {
        return c.onClient(mc -> {
            for (AbstractWidget w : widgets(s)) {
                if (w.getMessage().getString().startsWith(prefix)) {
                    return w;
                }
            }
            return null;
        });
    }

    /** Overlaps between any two widgets, and buttons without a tooltip. */
    private static void layoutCheck(Session c, Screen s, String where) {
        List<String> problems = c.onClient(mc -> {
            List<String> out = new ArrayList<>();
            List<AbstractWidget> ws = widgets(s);
            for (int a = 0; a < ws.size(); a++) {
                for (int b = a + 1; b < ws.size(); b++) {
                    AbstractWidget p = ws.get(a);
                    AbstractWidget q = ws.get(b);
                    if (p.getX() < q.getX() + q.getWidth() && q.getX() < p.getX() + p.getWidth()
                            && p.getY() < q.getY() + q.getHeight() && q.getY() < p.getY() + p.getHeight()) {
                        out.add("overlap '" + p.getMessage().getString() + "' / '" + q.getMessage().getString() + "'");
                    }
                }
                AbstractWidget w = ws.get(a);
                if (w instanceof EditBox || w instanceof StringWidget) {
                    continue;
                }
                if (w.getX() + w.getWidth() > s.width || w.getY() + w.getHeight() > s.height) {
                    out.add("off screen '" + w.getMessage().getString() + "'");
                }
                String tip = (String) Mod.staticCall("gui.SettingTooltips", "describe", "Custom Scoreboard",
                        w.getMessage().getString());
                if (tip == null || tip.isBlank()) {
                    out.add("no tooltip '" + w.getMessage().getString() + "'");
                }
            }
            return out;
        });
        int n = c.onClient(mc -> widgets(s).size());
        c.note(where + ": " + n + " widgets, problems " + problems);
        c.check(problems.isEmpty(), where + ": " + problems);
    }

    private static void editorSettings(Session c) throws Exception {
        byte[] saved = saveFile(c);
        try {
            List<String> start = new ArrayList<>(START);
            start.add(4, "EVENTS");
            Screen s = openEditor(c, start);
            layoutCheck(c, s, "Board page, nothing selected");
            int[] mayor = rect(c, s, "blockRectOf", "MAYOR");
            click(c, s, cx(mayor), cy(mayor));
            Object sel = c.onClient(mc -> Mod.staticCall(SB_EDITOR, "selectedEntry"));
            c.check(sel == entry("MAYOR"), "clicking MAYOR selected " + sel);
            AbstractWidget perks = widget(c, s, "Mayor Perks:");
            c.check(perks != null, "MAYOR selected but no 'Mayor Perks' setting is shown");
            boolean before = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(SB_CFG), "isShowMayorPerks"));
            click(c, s, perks.getX() + perks.getWidth() / 2.0, perks.getY() + perks.getHeight() / 2.0);
            boolean after = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(SB_CFG), "isShowMayorPerks"));
            c.note("Mayor Perks " + before + " -> " + after);
            c.check(after != before, "pressing Mayor Perks did not flip it");
            layoutCheck(c, s, "Board page, Mayor selected");

            int[] events = rect(c, s, "blockRectOf", "EVENTS");
            click(c, s, cx(events), cy(events));
            boolean hasList = c.onClient(mc -> widgets(s).stream().anyMatch(w -> Mod.cls("gui.DragListWidget").isInstance(w)));
            c.check(hasList, "EVENTS selected but its event list is not shown");
            layoutCheck(c, s, "Board page, Events selected");

            // One Settings page since mod scoreboard-simple-1015 (the General and Background pages merged): the common
            // settings, then Advanced, checked closed and then open.
            c.onClient(mc -> {
                Mod.call(Mod.cfg(SB_CFG), "setEditorAdvancedOpen", false);
                return null;
            });
            AbstractWidget pageBtn = widget(c, s, "Settings");
            c.check(pageBtn != null, "no Settings page button");
            click(c, s, pageBtn.getX() + pageBtn.getWidth() / 2.0, pageBtn.getY() + pageBtn.getHeight() / 2.0);
            c.check(widget(c, s, "Background Color") != null, "the Settings page has no 'Background Color'");
            layoutCheck(c, s, "Settings page, Advanced closed");
            AbstractWidget adv = widget(c, s, "▶ §6§lAdvanced"); // CollapsibleSection: arrow, amber header codes, title
            c.check(adv != null, "the Settings page has no closed Advanced row");
            click(c, s, adv.getX() + adv.getWidth() / 2.0, adv.getY() + adv.getHeight() / 2.0);
            c.check(widget(c, s, "Hide Vanilla Scoreboard:") != null, "Advanced opened without 'Hide Vanilla Scoreboard'");
            layoutCheck(c, s, "Settings page, Advanced open");
            AbstractWidget boardBtn = widget(c, s, "Board");
            click(c, s, boardBtn.getX() + boardBtn.getWidth() / 2.0, boardBtn.getY() + boardBtn.getHeight() / 2.0);
        } finally {
            restoreFile(c, saved);
        }
    }

    // ---- 719 --------------------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static void tabMinimal(Session c) {
        try (Settings set = new Settings(c).with(SB_CFG, "Enabled", true)) {
            List<String> labels = c.onClient(mc -> {
                try {
                    Object tab = Mod.cls("gui.tab.CustomScoreboardTab").getConstructor().newInstance();
                    List<AbstractWidget> ws = (List<AbstractWidget>) Mod.call(tab, "buildWidgets", 0, 0, 300,
                            (Runnable) () -> {
                            });
                    List<String> out = new ArrayList<>();
                    for (AbstractWidget w : ws) {
                        out.add(net.minecraft.ChatFormatting.stripFormatting(w.getMessage().getString()));
                    }
                    return out;
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            c.note("Custom Scoreboard tab (on): " + labels);
            // The Theme row (screen sweep, 2026-10-13) picks the editor's own theme; it is part of the tab since then.
            c.check(labels.equals(List.of("Custom Scoreboard: ON", "Open Visual Editor", "Theme: Theme Default")),
                    "the tab shows " + labels + ", expected only the switch and Open Visual Editor");
        }
    }

    // ---- 720 --------------------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static void listWheel(Session c) {
        String result = c.onClient(mc -> {
            Mod.staticCall("scoreboard.ScoreboardListEditor", "resetSession");
            List<AbstractWidget> out = new ArrayList<>();
            Object section = Mod.enumValue("scoreboard.ScoreboardListEditor$Section", "EVENTS");
            Mod.staticCall("scoreboard.ScoreboardListEditor", "buildList", out, section, 10, 10, 200, 3, (Runnable) () -> {
            });
            AbstractWidget list = out.stream().filter(w -> Mod.cls("gui.DragListWidget").isInstance(w)).findFirst()
                    .orElseThrow(() -> new AssertionError("no DragListWidget built"));
            Object state = Mod.field(list, "state");
            int before = (Integer) Mod.field(state, "scroll");
            boolean upAtTop = (Boolean) Mod.call(list, "wheel", 20.0, 20.0, 1.0);
            int afterUp = (Integer) Mod.field(state, "scroll");
            boolean down = (Boolean) Mod.call(list, "wheel", 20.0, 20.0, -1.0);
            int afterDown = (Integer) Mod.field(state, "scroll");
            boolean outside = (Boolean) Mod.call(list, "wheel", 500.0, 500.0, 1.0);
            return before + "|" + upAtTop + "|" + afterUp + "|" + down + "|" + afterDown + "|" + outside;
        });
        String[] r = result.split("\\|");
        c.note("events list (3 rows): scroll " + r[0] + "; wheel up at top consumed " + r[1] + ", scroll " + r[2]
                + "; wheel down consumed " + r[3] + ", scroll " + r[4] + "; wheel off the list consumed " + r[5]);
        c.check("0".equals(r[0]) && "true".equals(r[1]) && "0".equals(r[2]),
                "a wheel turn up at the list's top was handed on to the page (or moved it): " + result);
        c.check("true".equals(r[3]) && Integer.parseInt(r[4]) > 0, "a wheel turn down did not scroll the list: " + result);
        c.check("false".equals(r[5]), "a wheel turn off the list was taken by it: " + result);
    }

    // ---- 721 / 722 ----------------------------------------------------------------------------------------------------

    private static void setArrows(Session c, int count) {
        c.onClient(mc -> {
            ItemStack stack = new ItemStack(Items.ARROW);
            stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Arrows Remaining: " + count))));
            mc.player.getInventory().setItem(8, stack);
            return null;
        });
        c.ctx().waitTicks(3);
    }

    private static String guiTitle(Minecraft mc) {
        try {
            Field f = mc.gui.getClass().getDeclaredField("title");
            f.setAccessible(true);
            Object t = f.get(mc.gui);
            return t == null ? null : ((Component) t).getString();
        } catch (ReflectiveOperationException e) {
            return "?";
        }
    }

    private static void quiverAlert(Session c) {
        hub(c);
        try (Settings set = new Settings(c).with(QUIVER_CFG, "Enabled", true)
                .with(QUIVER_CFG, "AlertEnabled", true)
                .with(QUIVER_CFG, "AlertSound", true)
                .with(QUIVER_CFG, "AlertThreshold", 60)
                .with(QUIVER_CFG, "AlertText", "&cLow arrows: {count}")) {
            c.check(c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(QUIVER_CFG), "isEnabled")),
                    "Quiver Display is not enabled here (SkyblockGate?)");
            setArrows(c, 100);
            int base = c.onClient(mc -> (Integer) Mod.staticCall(QUIVER, "alertsFired"));
            c.ctx().waitTicks(5);
            int at100 = c.onClient(mc -> (Integer) Mod.staticCall(QUIVER, "alertsFired"));
            setArrows(c, 40);
            c.waitUntil("the low arrow alert fires at 40 < 60", mc -> (Integer) Mod.staticCall(QUIVER, "alertsFired") == base + 1, 40);
            String title = c.onClient(mc -> (String) Mod.staticCall(QUIVER, "lastAlertTitle"));
            String shown = c.onClient(HxGuiHudCases::guiTitle);
            setArrows(c, 30);
            c.ctx().waitTicks(10);
            int at30 = c.onClient(mc -> (Integer) Mod.staticCall(QUIVER, "alertsFired"));
            setArrows(c, 120);
            c.ctx().waitTicks(5);
            setArrows(c, 20);
            c.waitUntil("the alert fires again after a refill", mc -> (Integer) Mod.staticCall(QUIVER, "alertsFired") == base + 2, 40);
            c.note("alerts: base " + base + ", at 100 " + at100 + ", at 40 -> title '" + title + "' (Gui title '" + shown
                    + "'), at 30 " + at30 + ", refill 120 then 20 -> " + (base + 2));
            c.check(at100 == base, "an alert fired at 100 arrows with the threshold at 60");
            c.check(title != null && title.contains("40") && title.startsWith("§c"), "alert text was '" + title + "'");
            c.check(shown == null || "?".equals(shown) || shown.contains("40"), "the on-screen title is '" + shown + "'");
            c.check(at30 == base + 1, "the alert fired again at 30 without a refill (" + at30 + ")");
        } finally {
            c.onClient(mc -> {
                mc.player.getInventory().setItem(8, ItemStack.EMPTY);
                return null;
            });
        }
    }

    private static void quiverConfig(Session c) {
        hub(c);
        String snap = c.onClient(mc -> {
            Object cfg = Mod.cfg(QUIVER_CFG);
            StringBuilder sb = new StringBuilder();
            for (int[] p : new int[][]{{5, 20}, {47, 40}, {50, 60}, {199, 200}, {250, 200}}) {
                Mod.call(cfg, "setAlertThreshold", p[0]);
                int got = (Integer) Mod.call(cfg, "getAlertThreshold");
                sb.append(p[0]).append("->").append(got).append(got == p[1] ? " " : "(want " + p[1] + ") ");
            }
            return sb.toString();
        });
        c.note("threshold snapping: " + snap);
        c.check(!snap.contains("want"), "threshold snapping: " + snap);
        Path path = c.onClient(mc -> (Path) Mod.field(QUIVER_CFG, "CONFIG_PATH"));
        byte[] saved = c.onClient(mc -> {
            try {
                return Files.exists(path) ? Files.readAllBytes(path) : null;
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        try {
            c.onClient(mc -> {
                Object cfg = Mod.cfg(QUIVER_CFG);
                Mod.call(cfg, "setEnabled", true);
                Mod.call(cfg, "setTextColor", 0xFF33CCFF);
                Mod.call(cfg, "setAlertEnabled", true);
                Mod.call(cfg, "setAlertSound", false);
                Mod.call(cfg, "setAlertThreshold", 140);
                Mod.call(cfg, "setAlertText", "&eRefill {count}");
                Mod.call(cfg, "save");
                Mod.staticCall(QUIVER_CFG, "load");
                return null;
            });
            String back = c.onClient(mc -> {
                Object cfg = Mod.cfg(QUIVER_CFG);
                return Integer.toHexString((Integer) Mod.call(cfg, "getTextColor")) + "|" + Mod.call(cfg, "isAlertEnabled")
                        + "|" + Mod.call(cfg, "isAlertSound") + "|" + Mod.call(cfg, "getAlertThreshold") + "|"
                        + Mod.call(cfg, "getAlertText");
            });
            c.note("reloaded: " + back);
            c.check(back.equals("ff33ccff|true|false|140|&eRefill {count}"), "settings after a reload: " + back);

            // The HUD line: plain text in the chosen colour.
            setArrows(c, 250);
            c.waitUntil("the quiver count is read", mc -> "250".equals(Mod.field(QUIVER, "cachedCount")), 40);
            String drawn = c.onClient(mc -> {
                GuiRenderState state = new GuiRenderState();
                GuiGraphicsExtractor g = new GuiGraphicsExtractor(mc, state, -1, -1);
                Object el = Mod.staticCall("hud.HudElementRegistry", "byId", "quiver_display");
                Mod.call(el, "render", g, 5, 5);
                StringBuilder sb = new StringBuilder();
                state.forEachText(t -> sb.append(dev.testkit.gametest.TextRuns.string(t)).append('@')
                        .append(Integer.toHexString(color(t))).append(';'));
                return sb.toString();
            });
            c.note("drawn HUD line: " + drawn);
            c.check(drawn.contains("x250@ff33ccff"), "the quiver line is not drawn in the Text Color: " + drawn);
            c.check(!drawn.contains("§"), "the quiver line still carries formatting codes: " + drawn);
        } finally {
            c.onClient(mc -> {
                mc.player.getInventory().setItem(8, ItemStack.EMPTY);
                try {
                    if (saved == null) {
                        Files.deleteIfExists(path);
                    } else {
                        Files.write(path, saved);
                    }
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
                Mod.staticCall(QUIVER_CFG, "load");
                return null;
            });
        }
    }

    private static int color(GuiTextRenderState t) {
        try {
            Field f = GuiTextRenderState.class.getDeclaredField("color");
            f.setAccessible(true);
            return f.getInt(t);
        } catch (ReflectiveOperationException e) {
            return 0;
        }
    }

    // ---- 723 --------------------------------------------------------------------------------------------------------

    private static int[] shotDiff(Session c, BufferedImage base, String name) throws Exception {
        c.ctx().waitTicks(4);
        Path p = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(c.name() + "-" + name));
        BufferedImage img = javax.imageio.ImageIO.read(p.toFile());
        int changed = 0;
        long r = 0, g = 0, b = 0;
        if (base != null) {
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    int a = img.getRGB(x, y);
                    int o = base.getRGB(x, y);
                    int d = Math.abs(((a >> 16) & 255) - ((o >> 16) & 255)) + Math.abs(((a >> 8) & 255) - ((o >> 8) & 255))
                            + Math.abs((a & 255) - (o & 255));
                    if (d > 30) {
                        changed++;
                        r += (a >> 16) & 255;
                        g += (a >> 8) & 255;
                        b += a & 255;
                    }
                }
            }
        }
        LAST = img;
        return changed == 0 ? new int[]{0, 0, 0, 0} : new int[]{changed, (int) (r / changed), (int) (g / changed), (int) (b / changed)};
    }

    private static BufferedImage LAST;

    private static void springBoots(Session c) throws Exception {
        hub(c);
        Object outline = Mod.enumValue("dungeonalerts.DungeonAlertsConfig$BoxStyle", "OUTLINE");
        Object filled = Mod.enumValue("dungeonalerts.DungeonAlertsConfig$BoxStyle", "FILLED");
        Object both = Mod.enumValue("dungeonalerts.DungeonAlertsConfig$BoxStyle", "FILLED_OUTLINE");
        float[] look = c.onClient(mc -> new float[]{mc.player.getXRot(), mc.player.getYRot()});
        try (Settings set = new Settings(c).field(DA_CFG, "springBootsEnabled", true)
                .field(DA_CFG, "springBootsBox", false)
                .field(DA_CFG, "springBootsColor", 0xFFFF0000)
                .field(DA_CFG, "springBootsBoxStyle", outline)) {
            c.onClient(mc -> {
                ItemStack boots = new ItemStack(Items.LEATHER_BOOTS);
                net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
                tag.putString("id", "SPRING_BOOTS");
                boots.set(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(tag));
                mc.player.setItemSlot(EquipmentSlot.FEET, boots);
                mc.player.setXRot(-90f);
                return null;
            });
            c.ctx().getInput().holdKey(o -> o.keyShift);
            c.waitUntil("crouching", mc -> mc.player.isCrouching(), 40);
            // Two of Hypixel's charge notes (a high pitch each) from the real server: HEIGHTS[2] = 6.5 blocks.
            for (int i = 0; i < 2; i++) {
                c.server().command("execute as @a at @s run playsound minecraft:block.note_block.pling master @s ~ ~ ~ 1 0.8888889");
                c.ctx().waitTicks(4);
            }
            c.waitUntil("the sound packets charge the height", mc -> ((Number) Mod.field(SPRING, "blockAmount")).floatValue() > 0f, 60);
            float height = c.onClient(mc -> ((Number) Mod.field(SPRING, "blockAmount")).floatValue());
            c.note("charged height " + height);
            c.check(height == 6.5f, "two charge notes read as height " + height + ", expected 6.5");

            // An earlier case's title (721's low arrow alert) must not be in the no-box frame.
            c.onClient(mc -> {
                // 26.1.2 Gui.clearTitles; 26.2 moved titles to Gui.hud (mod McCompat), so by reflection.
                try {
                    Mod.call(mc.gui, "clearTitles");
                } catch (Throwable t) {
                    Mod.call(Mod.field(mc.gui, "hud"), "clearTitles");
                }
                return null;
            });
            c.ctx().waitTicks(5);
            int drawsBefore = c.onClient(mc -> (Integer) Mod.field(SPRING, "boxesDrawn"));
            shotDiff(c, null, "none");
            BufferedImage base = LAST;
            int drawsOff = c.onClient(mc -> (Integer) Mod.field(SPRING, "boxesDrawn"));
            c.onClient(mc -> {
                setInstanceField(Mod.cfg(DA_CFG), "springBootsBox", true);
                return null;
            });
            int[] redOutline = shotDiff(c, base, "outline-red");
            int drawsOn = c.onClient(mc -> (Integer) Mod.field(SPRING, "boxesDrawn"));
            c.onClient(mc -> {
                setInstanceField(Mod.cfg(DA_CFG), "springBootsBoxStyle", filled);
                return null;
            });
            int[] redFilled = shotDiff(c, base, "filled-red");
            c.onClient(mc -> {
                setInstanceField(Mod.cfg(DA_CFG), "springBootsBoxStyle", both);
                return null;
            });
            int[] redBoth = shotDiff(c, base, "filled-outline-red");
            c.onClient(mc -> {
                setInstanceField(Mod.cfg(DA_CFG), "springBootsBoxStyle", outline);
                setInstanceField(Mod.cfg(DA_CFG), "springBootsColor", 0xFF00FF00);
                return null;
            });
            int[] greenOutline = shotDiff(c, base, "outline-green");
            c.note("box off: " + (drawsOff - drawsBefore) + " draws; on: " + (drawsOn - drawsOff) + " draws");
            c.note("changed pixels [count, mean R, G, B]: outline red " + java.util.Arrays.toString(redOutline)
                    + ", filled red " + java.util.Arrays.toString(redFilled) + ", filled outline red "
                    + java.util.Arrays.toString(redBoth) + ", outline green " + java.util.Arrays.toString(greenOutline));
            c.check(drawsOff == drawsBefore, "the box was drawn with Height Box off");
            c.check(drawsOn > drawsOff, "the box was never drawn with Height Box on");
            c.check(redOutline[0] > 20, "the red outline changed only " + redOutline[0] + " pixels");
            c.check(redFilled[0] > redOutline[0] * 2, "Filled (" + redFilled[0] + ") does not cover far more than Outline ("
                    + redOutline[0] + ")");
            c.check(redBoth[0] >= redFilled[0], "Filled Outline (" + redBoth[0] + ") covers less than Filled (" + redFilled[0] + ")");
            c.check(redOutline[1] > redOutline[2] + 40, "the red outline's pixels are not red: " + java.util.Arrays.toString(redOutline));
            c.check(greenOutline[0] > 20 && greenOutline[2] > greenOutline[1] + 40,
                    "the green outline's pixels are not green: " + java.util.Arrays.toString(greenOutline));
        } finally {
            c.ctx().getInput().releaseKey(o -> o.keyShift);
            c.onClient(mc -> {
                mc.player.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
                mc.player.setXRot(look[0]);
                return null;
            });
        }
    }

    // ---- 724 --------------------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static String hudPingLine() {
        List<Object> lines = (List<Object>) Mod.staticCall("lagdisplay.LagDisplayFeature$LagHudElement", "lines");
        for (Object l : lines) {
            String t = String.valueOf(Mod.call(l, "text"));
            if (t.startsWith("Ping")) {
                return t;
            }
        }
        return null;
    }

    private static void ping(Session c) {
        try (Settings set = new Settings(c).with(LAG_CFG, "Enabled", true).with(LAG_CFG, "ShowPing", true)) {
            int sent0 = c.onClient(mc -> (Integer) Mod.staticCall(PING, "pingsSent"));
            int pongs0 = c.onClient(mc -> (Integer) Mod.staticCall(PING, "pongsReceived"));
            c.waitUntil("pongs arrive", mc -> (Integer) Mod.staticCall(PING, "pongsReceived") >= pongs0 + 3, 200);
            int base = c.onClient(mc -> (Integer) Mod.staticCall(PING, "measuredMs"));
            int tab = c.onClient(mc -> (Integer) Mod.staticCall(PING, "tabListMs"));
            String lineBase = c.onClient(mc -> hudPingLine());
            c.note("no added latency: measured " + base + " ms (tab list says " + tab + "), HUD '" + lineBase + "'");
            c.check(base >= 0 && base < 100, "local round trip measured " + base + " ms");

            c.onClient(mc -> {
                Latency.install(200);
                return null;
            });
            try {
                c.waitUntil("the measured ping follows +200 ms", mc -> (Integer) Mod.staticCall(PING, "measuredMs") >= 180, 400);
                int lagged = c.onClient(mc -> (Integer) Mod.staticCall(PING, "measuredMs"));
                long last = c.onClient(mc -> (Long) Mod.staticCall(PING, "lastSampleMs"));
                String line = c.onClient(mc -> hudPingLine());
                int tabLagged = c.onClient(mc -> (Integer) Mod.staticCall(PING, "tabListMs"));
                c.note("with +200 ms: measured " + lagged + " ms (last sample " + last + "), tab list " + tabLagged
                        + ", HUD '" + line + "'");
                c.check(lagged >= 180 && lagged < 400, "measured " + lagged + " ms with 200 ms injected");
                c.check(line != null && line.equals("Ping: " + lagged + "ms"), "the HUD line is '" + line + "'");
            } finally {
                c.onClient(mc -> {
                    Latency.remove();
                    return null;
                });
            }
            c.waitUntil("the measured ping drops back", mc -> (Integer) Mod.staticCall(PING, "measuredMs") < 100, 400);
            int sent1 = c.onClient(mc -> (Integer) Mod.staticCall(PING, "pingsSent"));
            c.check(sent1 > sent0, "no ping requests were sent while the HUD showed ping");
        }
        // Nothing shows the ping: no requests (party !ping off too).
        try (Settings set = new Settings(c).with(LAG_CFG, "Enabled", false)
                .with("partycommands.PartyCommandsConfig", "Enabled", false)) {
            c.ctx().waitTicks(25);
            int a = c.onClient(mc -> (Integer) Mod.staticCall(PING, "pingsSent"));
            c.ctx().waitTicks(80);
            int b = c.onClient(mc -> (Integer) Mod.staticCall(PING, "pingsSent"));
            c.note("with nothing showing ping: " + (b - a) + " requests in 80 ticks");
            c.check(b == a, (b - a) + " ping requests were sent with the HUD and party !ping off");
        }
    }

    // ---- 725 --------------------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static void gifDvd(Session c) {
        String builtin = c.onClient(mc -> (String) Mod.field("gifplayer.GifPlayerFeature", "BUILTIN_NAME"));
        List<String> labels = c.onClient(mc -> {
            try {
                Object tab = Mod.cls("gui.tab.GifPlayerTab").getConstructor().newInstance();
                List<AbstractWidget> ws = (List<AbstractWidget>) Mod.call(tab, "buildWidgets", 0, 0, 300, (Runnable) () -> {
                });
                List<String> out = new ArrayList<>();
                for (AbstractWidget w : ws) {
                    out.add(w.getMessage().getString());
                }
                return out;
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        String display = c.onClient(mc -> (String) Mod.staticCall("gifplayer.GifPlayerFeature", "displayName", builtin));
        c.note("GIF tab: " + labels + "; built-in shown as '" + display + "'");
        c.check(labels.contains("Open GIF / Audio Folder"), "no 'Open GIF / Audio Folder' button: " + labels);
        c.check(labels.stream().noneMatch(l -> l.contains("(built-in)")), "a GIF row still says (built-in): " + labels);
        c.check("Dancing Cat Girl".equals(display), "the built-in GIF is labeled '" + display + "'");

        String dvd = c.onClient(mc -> {
            Object cfg = Mod.cfg("dvd.DvdConfig");
            Object e = Mod.call(cfg, "addNew");
            String id = (String) Mod.field(e, "id");
            try {
                setInstanceField(e, "contentType", Mod.enumValue("dvd.DvdContentType", "GIF"));
                setInstanceField(e, "gifFileName", builtin);
                setInstanceField(e, "enabled", true);
                Mod.staticCall("dvd.DvdFeature", "reload");
                Object rts = Mod.field("dvd.DvdFeature", "runtimes");
                Object rt = ((java.util.Map<String, Object>) rts).get(id);
                Object gif = rt == null ? null : Mod.field(rt, "gif");
                Object tex = rt == null ? null : Mod.field(rt, "texture");
                int frames = gif == null ? 0 : ((List<Object>) Mod.call(gif, "frames")).size();
                return "runtime " + (rt != null) + ", frames " + frames + ", texture " + (tex != null);
            } finally {
                Mod.call(cfg, "remove", id);
                Mod.call(cfg, "save");
                Mod.staticCall("dvd.DvdFeature", "reload");
            }
        });
        c.note("DVD with the built-in GIF: " + dvd);
        c.check(dvd.startsWith("runtime true") && !dvd.contains("frames 0") && dvd.endsWith("texture true"),
                "DVD could not load the built-in Dancing Cat Girl: " + dvd);
    }

    // ---- 726 --------------------------------------------------------------------------------------------------------

    private static void abilityTimersGone(Session c) {
        boolean cls = c.onClient(mc -> Mod.has("abilitytimers.AbilityTimersFeature") || Mod.has("gui.tab.AbilityTimersTab"));
        Object el = c.onClient(mc -> Mod.staticCall("hud.HudElementRegistry", "byId", "ability_timers"));
        c.note("Ability Timers classes present " + cls + ", HUD element " + el);
        c.check(!cls, "Ability Timers classes are still in the jar");
        c.check(el == null, "the ability_timers HUD element is still registered");
        // A profile reload walks every config loader; it must not reach for the removed one.
        int failures = c.onClient(mc -> (Integer) Mod.staticCall("profiles.ProfileManager", "reloadConfigsOnly"));
        c.check(failures == 0, failures + " config loaders failed on a reload");
    }
}
