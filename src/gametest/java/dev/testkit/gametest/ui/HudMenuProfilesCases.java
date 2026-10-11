package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.TextRuns;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.Report;

import com.mojang.blaze3d.platform.Window;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/**
 * 611-618: killer560's 2026-10-08 HUD / menu / profiles list (mod hud-menu-profiles).
 * <ul>
 *   <li>611 the Edit HUD key, pressed for real (TestInput.holdKey on the bound key), opens the editor with no screen up
 *       (the positive control, first and last) and does nothing with chat, the pause menu, an inventory, the mod menu,
 *       another mod's screen (a testkit screen class) or the editor itself open.</li>
 *   <li>612 Edit HUD carries no widgets (the Snapping / Show Unseen / Done buttons are gone) and draws at most one
 *       short hint line, at the top left, and none while a box is dragged; none of the old button texts is drawn.</li>
 *   <li>613 the editor's settings are rows in Home's HUD Editor section: each pressed for real flips its HudConfig
 *       switch, which survives a reload from disk; and they still decide what the editor does - a probe dropped 3 px
 *       from the screen's left edge snaps to it with Snapping on and stays at 3 after the menu's Snapping row
 *       switches it off.</li>
 *   <li>614 Health and Mana Bars: no stat readout in Edit HUD (Show Unseen on, readouts on), the probe there; Home's
 *       "Edit Bars" and the Health and Mana Bars tab's "Edit Bars" both open the bar editor, which lists exactly the
 *       readouts that are on and nothing else; a real drag there moves the Health Bar and is saved.</li>
 *   <li>615 HUD Scale sizes the mod menu: at 50/100/150/200% the open ModScreen is laid out at
 *       {@code AutoScale.menuFactor()}, which grows with HUD Scale; a real click at the DRAWN centre of sidebar row 1
 *       selects tab 1 at each; the panel's drawn rectangle is inside the window at each, also at 854x480 GUI 2 with
 *       HUD Scale 300%; screenshots at 1920x1080 GUI 2.</li>
 *   <li>616 the menu factor's math, no Minecraft state: identity at 100%, the clamp, the fit cap, crisp snapping.</li>
 *   <li>617 New Blank Profile, pressed in the real Profiles tab: makes a profile without touching the live settings;
 *       after Load, every config class in the jar (walked by the testkit's own JarIndex, not the mod's list) reads
 *       every is/get...Enabled getter and every boolean setting field false, Auto Trap too; HudConfig and Skyblock
 *       Only stay at their defaults; then the settings from before come back.</li>
 *   <li>618 Export, pressed in the real Profiles tab: the zip lands in the share folder (not the profiles folder),
 *       holds the profile's setting files, and the open of that folder is made (the noExternalOpen hook's log line).</li>
 * </ul>
 */
final class HudMenuProfilesCases {

    private static final String HUD = "hud.HudConfig";
    private static final String PS = "playerstats.PlayerStatsConfig";
    private static final String EDITOR = "hud.HudEditorScreen";
    /** GLFW_KEY_KP_6: bound to nothing in vanilla, so a held key only reaches the Edit HUD poll. */
    private static final int KEY = 326;
    private static final String PROBE_ID = "testkit_hmp_probe";
    private static final int PROBE_W = 60;
    private static final int PROBE_H = 16;

    private HudMenuProfilesCases() {
    }

    // ---- 611 ------------------------------------------------------------------------------------------------------

    static void keyScreens(UiCase c) {
        Object hud = Mod.cfg(HUD);
        int oldKey = (Integer) Mod.call(hud, "getEditKeyCode");
        try {
            c.onClient(mc -> {
                Mod.call(hud, "setEditKeyCode", KEY);
                McCompat.setScreen(mc, null);
                return null;
            });
            c.ticks(2);
            String first = pressKeyAndSee(c);
            c.note("no screen open: the key opened " + first);
            c.check(first.startsWith(EDITOR + " (Edit HUD)"), "with no screen open the Edit HUD key opened " + first
                    + " - the control failed, so nothing below would mean anything");

            Map<String, Supplier<Screen>> kinds = new LinkedHashMap<>();
            kinds.put("chat", () -> new ChatScreen("", false));
            // In creative the survival inventory hands over to the creative one in its init; whichever is up is the
            // container under test.
            kinds.put("pause menu", () -> new PauseScreen(true));
            kinds.put("inventory (a container)", () -> new InventoryScreen(Minecraft.getInstance().player));
            kinds.put("the mod menu", () -> newModScreen());
            kinds.put("another mod's screen", () -> new Screen(Component.literal("Testkit Other Mod Screen")) {
            });
            kinds.put("the HUD editor itself", () -> newEditor(false));
            for (Map.Entry<String, Supplier<Screen>> k : kinds.entrySet()) {
                c.onClient(mc -> {
                    McCompat.setScreen(mc, k.getValue().get());
                    return null;
                });
                c.ticks(3);
                Screen open = c.onClient(McCompat::screen);
                c.check(open != null, "could not open " + k.getKey());
                String after = pressKeyAndSee(c);
                boolean same = c.onClient(mc -> McCompat.screen(mc) == open);
                c.note(k.getKey() + " open (" + open.getClass().getName() + "): after the key the screen is " + after);
                if (!same) {
                    c.problem("the Edit HUD key changed the screen while " + k.getKey() + " was open: now " + after);
                }
                c.onClient(mc -> {
                    McCompat.setScreen(mc, null);
                    return null;
                });
                c.ticks(3);
            }
            String last = pressKeyAndSee(c);
            c.note("no screen open again: the key opened " + last);
            c.check(last.startsWith(EDITOR + " (Edit HUD)"), "the closing control: with no screen the key opened " + last);
        } finally {
            c.ctx().getInput().releaseKey(KEY);
            c.onClient(mc -> {
                Mod.call(hud, "setEditKeyCode", oldKey);
                McCompat.setScreen(mc, null);
                return null;
            });
        }
    }

    /** Holds the bound key for a few ticks (the poll is a raw key read at the end of each tick), releases it, and says
     *  what screen is up. */
    private static String pressKeyAndSee(UiCase c) {
        c.ctx().getInput().holdKey(KEY);
        c.ticks(4);
        c.ctx().getInput().releaseKey(KEY);
        c.ticks(3);
        return c.onClient(mc -> describe(McCompat.screen(mc)));
    }

    private static String describe(Screen s) {
        if (s == null) {
            return "(no screen)";
        }
        String name = s.getClass().getName().replace(Mod.ROOT, "");
        if (name.equals(EDITOR)) {
            return EDITOR + ((Boolean) Mod.call(s, "isBarsMode") ? " (Edit Bars)" : " (Edit HUD)");
        }
        return name;
    }

    // ---- 612 ------------------------------------------------------------------------------------------------------

    static void editorClean(UiCase c) throws Exception {
        Object hud = Mod.cfg(HUD);
        boolean oldShowAll = (Boolean) Mod.call(hud, "isEditorShowAll");
        Object probe = probe();
        try {
            c.onClient(mc -> {
                Mod.call(hud, "setEditorShowAll", true);
                int[] saved = (int[]) Mod.staticCall("hud.HudElementRegistry", "toSaved", 120, 140);
                Mod.call(hud, "setPosition", PROBE_ID, saved[0], saved[1]);
                Mod.staticCall("hud.HudElementRegistry", "register", probe);
                return null;
            });
            Screen ed = openEditor(c, false);
            Map<String, Object> r = c.onClient(mc -> {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("widgets", Frames.widgets(ed).size());
                out.put("children", ed.children().size());
                out.put("probeListed", listedIds(ed).contains(PROBE_ID));
                // Only the probe stays listed, so every other text drawn is the editor's own.
                @SuppressWarnings("unchecked")
                List<Object> shown = (List<Object>) R.get(ed, "shown");
                shown.removeIf(e -> !PROBE_ID.equals(Mod.call(e, "id")));
                out.put("idle", texts(mc, ed, -1, -1));
                return out;
            });
            Path shot = c.ctx().takeScreenshot(Report.fileName(c.name() + "-edit-hud"));
            Report.screenshot(c.name(), shot);
            // A press on the probe starts a drag: the hint must go while it lasts.
            List<String[]> dragging = c.onClient(mc -> {
                int[] p = livePos(ed, PROBE_ID);
                float scale = ((Number) Mod.staticCall("hud.HudElementRegistry", "resolveScale", probeElement)).floatValue();
                double gx = p[0] + PROBE_W * scale / 2.0;
                double gy = p[1] + PROBE_H * scale / 2.0;
                var info = new MouseButtonInfo(0, 0);
                ed.mouseClicked(new MouseButtonEvent(gx, gy, info), false);
                c.check(PROBE_ID.equals(R.get(ed, "draggingId")), "the press did not grab the probe");
                ed.mouseDragged(new MouseButtonEvent(gx + 10, gy + 4, info), 10, 4);
                List<String[]> t = texts(mc, ed, (int) gx + 10, (int) gy + 4);
                ed.mouseReleased(new MouseButtonEvent(gx + 10, gy + 4, info));
                return t;
            });
            closeEditor(c);
            c.note("Edit HUD: " + r.get("widgets") + " widget(s), " + r.get("children") + " child(ren); probe listed "
                    + r.get("probeListed"));
            c.check((Boolean) r.get("probeListed"), "the probe is not listed in Edit HUD, so nothing below is measured");
            c.check((Integer) r.get("widgets") == 0 && (Integer) r.get("children") == 0,
                    "Edit HUD still has " + r.get("widgets") + " widget(s) / " + r.get("children") + " child(ren)");
            @SuppressWarnings("unchecked")
            List<String[]> idle = (List<String[]>) r.get("idle");
            int hints = 0;
            for (String[] t : idle) {
                c.note("drawn text: \"" + t[0] + "\" at " + t[1]);
                String plain = t[0];
                for (String gone : new String[]{"Snapping:", "Show Unseen:", "Element Edges", "Screen Centre",
                        "Equal Spacing", "Done"}) {
                    if (plain.contains(gone)) {
                        c.problem("Edit HUD still draws \"" + plain + "\" (the old " + gone + " control)");
                    }
                }
                if (!plain.startsWith("Testk")) {
                    hints++;
                    int[] b = box(t[1]);
                    if (b[1] > 20) {
                        c.problem("a hint line is drawn at y " + b[1] + ", not in the top strip: \"" + plain + "\"");
                    }
                }
            }
            c.check(idle.stream().anyMatch(t -> t[0].startsWith("Testk")),
                    "the probe's own label was not drawn - the text capture saw nothing of the editor");
            c.check(hints >= 1 && hints <= 2, "Edit HUD draws " + hints + " hint line(s); one is expected");
            int dragHints = 0;
            for (String[] t : dragging) {
                if (!t[0].startsWith("Testk")) {
                    dragHints++;
                    c.note("text while dragging: \"" + t[0] + "\"");
                }
            }
            c.check(dragHints == 0, "Edit HUD draws " + dragHints + " hint line(s) while a box is being dragged");
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                Mod.staticCall("hud.HudElementRegistry", "unregister", PROBE_ID);
                Mod.call(hud, "setEditorShowAll", oldShowAll);
                Mod.call(hud, "save");
                return null;
            });
        }
    }

    // ---- 613 ------------------------------------------------------------------------------------------------------

    static void editorSettings(UiCase c) throws Exception {
        Object hud = Mod.cfg(HUD);
        String[][] rows = {
                {"Snapping", "isEditorSnap"}, {"Show Unseen", "isEditorShowAll"},
                {"Element Edges", "isSnapElementEdges"}, {"Element Centres", "isSnapElementCentres"},
                {"Screen Edges", "isSnapScreenEdges"}, {"Screen Centre", "isSnapScreenCentre"},
                {"Equal Spacing", "isSnapEqualSpacing"}};
        Map<String, Boolean> before = new LinkedHashMap<>();
        for (String[] row : rows) {
            before.put(row[1], (Boolean) Mod.call(hud, row[1]));
        }
        Object probe = probe();
        try {
            ModScreenDriver d = homeTab(c);
            for (String[] row : rows) {
                String label = row[0];
                boolean was = (Boolean) Mod.call(Mod.cfg(HUD), row[1]);
                boolean took = c.onClient(mc -> pressRow(d, label + ":"));
                c.check(took, "Home has no \"" + label + ":\" row to press");
                boolean now = (Boolean) Mod.call(Mod.cfg(HUD), row[1]);
                // From the file: HudConfig.load() replaces the instance with what is on disk.
                boolean onDisk = c.onClient(mc -> {
                    Mod.staticCall(HUD, "load");
                    return (Boolean) Mod.call(Mod.cfg(HUD), row[1]);
                });
                c.note(String.format(Locale.ROOT, "Home > %s: %s -> %s (on disk after reload: %s)", label, was, now,
                        onDisk));
                if (now == was) {
                    c.problem("pressing Home's \"" + label + "\" row did not flip " + row[1]);
                }
                if (onDisk != now) {
                    c.problem("Home's \"" + label + "\" did not persist: reloaded " + onDisk + ", set " + now);
                }
                c.onClient(mc -> {
                    rebuild(d);
                    return null;
                });
            }
            Path shot = c.ctx().takeScreenshot(Report.fileName(c.name() + "-home"));
            Report.screenshot(c.name(), shot);
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });

            // Take effect: Snapping + Screen Edges on, the rest off; drop the probe 3 px from the left edge.
            c.onClient(mc -> {
                Object h = Mod.cfg(HUD);
                Mod.call(h, "setEditorSnap", true);
                Mod.call(h, "setSnapScreenEdges", true);
                Mod.call(h, "setSnapElementEdges", false);
                Mod.call(h, "setSnapElementCentres", false);
                Mod.call(h, "setSnapScreenCentre", false);
                Mod.call(h, "setSnapEqualSpacing", false);
                Mod.call(h, "setEditorShowAll", true);
                Mod.call(h, "setScale", PROBE_ID, 1.0f);
                Mod.staticCall("hud.HudElementRegistry", "register", probe);
                return null;
            });
            int snappedOn = dropProbeAt(c, 3);
            c.note("Snapping ON (Screen Edges only): probe dropped at x 3 landed at x " + snappedOn);
            ModScreenDriver d2 = homeTab(c);
            boolean took = c.onClient(mc -> pressRow(d2, "Snapping:"));
            c.check(took, "no Snapping row to switch it off with");
            boolean snapNow = (Boolean) Mod.call(Mod.cfg(HUD), "isEditorSnap");
            c.check(!snapNow, "the menu's Snapping row did not switch snapping off");
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
            int snappedOff = dropProbeAt(c, 3);
            c.note("Snapping OFF from the menu: probe dropped at x 3 landed at x " + snappedOff);
            c.check(snappedOn == 0, "with Snapping and Screen Edges on, a drop 3 px from the edge landed at x "
                    + snappedOn + " (expected the edge, 0)");
            c.check(snappedOff == 3, "with Snapping switched off in the menu, the same drop landed at x " + snappedOff);
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                Mod.staticCall("hud.HudElementRegistry", "unregister", PROBE_ID);
                Object h = Mod.cfg(HUD);
                for (String[] row : rows) {
                    Mod.call(h, "set" + row[1].substring(2), before.get(row[1]));
                }
                Mod.call(h, "save");
                return null;
            });
        }
    }

    /** Opens Edit HUD with the probe, drags it so its left edge is dropped at screen x {@code x} (y unchanged), and
     *  returns where it was saved to, back in screen units. */
    private static int dropProbeAt(UiCase c, int x) {
        Screen ed = openEditor(c, false);
        int got = c.onClient(mc -> {
            int[] p = livePos(ed, PROBE_ID);
            c.check(p != null, "the probe is not listed in Edit HUD");
            // The box is drawn at the element's in-game scale (HUD Scale x Auto Scale): grab its middle, away from
            // the corner handles, which would resize it instead.
            float scale = ((Number) Mod.staticCall("hud.HudElementRegistry", "resolveScale", probeElement)).floatValue();
            var info = new MouseButtonInfo(0, 0);
            double gx = p[0] + PROBE_W * scale / 2.0;
            double gy = p[1] + PROBE_H * scale / 2.0;
            ed.mouseClicked(new MouseButtonEvent(gx, gy, info), false);
            Object grabbed = R.get(ed, "draggingId");
            int edges = (Integer) R.get(ed, "resizeEdges");
            double tx = gx + (x - p[0]);
            ed.mouseDragged(new MouseButtonEvent(tx, gy, info), x - p[0], 0);
            int[] live = livePos(ed, PROBE_ID);
            ed.mouseReleased(new MouseButtonEvent(tx, gy, info));
            int[] back = (int[]) Mod.staticCall("hud.HudElementRegistry", "resolvePosition", probeElement);
            c.note("drop: probe at " + p[0] + "," + p[1] + " (scale " + scale + "), grabbed " + grabbed + " edges " + edges
                    + ", live after the drag " + live[0] + "," + live[1] + ", drawn back at " + back[0] + "," + back[1]);
            c.check(PROBE_ID.equals(grabbed) && edges == 0, "the press did not grab the probe for a move");
            return back[0];
        });
        closeEditor(c);
        return got;
    }

    // ---- 614 ------------------------------------------------------------------------------------------------------

    static void barsEditor(UiCase c) throws Exception {
        Object hud = Mod.cfg(HUD);
        Object ps = Mod.cfg(PS);
        boolean oldShowAll = (Boolean) Mod.call(hud, "isEditorShowAll");
        boolean oldSnap = (Boolean) Mod.call(hud, "isEditorSnap");
        boolean oldMaster = (Boolean) Mod.call(ps, "isEnabledRaw");
        boolean oldPredefined = (Boolean) Mod.call(ps, "isPredefinedLayout");
        String[] readouts = {"HEALTH_BAR", "MANA_BAR", "HEALTH_TEXT"};
        Map<String, Boolean> oldOn = new LinkedHashMap<>();
        Object probe = probe();
        int[] oldHealthPos = (int[]) Mod.call(hud, "getPosition", "statbar_health", -1, -1);
        try {
            c.onClient(mc -> {
                Mod.call(hud, "setEditorShowAll", true);
                // A free drop, so the saved spot is exactly where it was let go (snapping is 613's).
                Mod.call(hud, "setEditorSnap", false);
                Mod.call(ps, "setEnabled", true);
                Mod.call(ps, "setPredefinedLayout", false);
                for (String r : readouts) {
                    Object e = Mod.enumValue("playerstats.StatElements$Readout", r);
                    oldOn.put(r, (Boolean) Mod.call(ps, "isReadoutOn", e));
                    Mod.call(ps, "setReadoutOn", e, true);
                }
                Mod.call(ps, "save");
                Mod.staticCall("hud.HudElementRegistry", "register", probe);
                return null;
            });
            List<String> enabledStats = c.onClient(mc -> enabledStatIds());
            c.note("stat readouts switched on: " + enabledStats);
            c.check(enabledStats.containsAll(List.of("statbar_health", "statbar_mana", "stattext_health")),
                    "the readouts this case switched on are not on: " + enabledStats);

            // Edit HUD: never a stat readout, the probe yes.
            Screen hudEd = openEditor(c, false);
            List<String> inHud = c.onClient(mc -> listedIds(hudEd));
            closeEditor(c);
            c.note("Edit HUD lists " + inHud);
            c.check(inHud.contains(PROBE_ID), "Edit HUD does not list the probe (Show Unseen on), so its list is not real");
            for (String id : inHud) {
                if (id.startsWith("statbar_") || id.startsWith("stattext_")) {
                    c.problem("Edit HUD lists the stat readout " + id);
                }
            }

            // Home's Edit Bars.
            ModScreenDriver home = homeTab(c);
            boolean took = c.onClient(mc -> pressRow(home, "Edit Bars"));
            c.check(took, "Home has no Edit Bars row");
            c.ticks(3);
            checkBarsEditor(c, "Home > Edit Bars", enabledStats);
            Path shot = c.ctx().takeScreenshot(Report.fileName(c.name() + "-edit-bars"));
            Report.screenshot(c.name(), shot);
            closeEditor(c);

            // Health and Mana Bars' Edit Bars.
            ModScreenDriver bars = tabByClass(c, "HealthAndManaBarsTab");
            boolean took2 = c.onClient(mc -> pressRow(bars, "Edit Bars"));
            c.check(took2, "Health and Mana Bars has no Edit Bars row");
            c.ticks(3);
            Screen barEd = checkBarsEditor(c, "Health and Mana Bars > Edit Bars", enabledStats);

            // A real drag of the Health Bar in the bar editor, saved.
            String drag = c.onClient(mc -> {
                int[] p = livePos(barEd, "statbar_health");
                if (p == null) {
                    return "no live position for statbar_health";
                }
                var info = new MouseButtonInfo(0, 0);
                double gx = p[0] + 10;
                double gy = p[1] + 2;
                barEd.mouseClicked(new MouseButtonEvent(gx, gy, info), false);
                Object dragging = R.get(barEd, "draggingId");
                barEd.mouseDragged(new MouseButtonEvent(gx + 17, gy + 9, info), 17, 9);
                barEd.mouseReleased(new MouseButtonEvent(gx + 17, gy + 9, info));
                int[] want = (int[]) Mod.staticCall("hud.HudElementRegistry", "toSaved", p[0] + 17, p[1] + 9);
                int[] saved = (int[]) Mod.call(Mod.cfg(HUD), "getPosition", "statbar_health", -999, -999);
                return "grabbed " + dragging + "; from " + p[0] + "," + p[1] + " saved " + saved[0] + "," + saved[1]
                        + " want " + want[0] + "," + want[1] + (dragging != null && "statbar_health".equals(dragging)
                        && saved[0] == want[0] && saved[1] == want[1] ? " OK" : " WRONG");
            });
            c.note("bar editor drag: " + drag);
            c.check(drag.endsWith(" OK"), "the bar editor drag did not move and save the Health Bar: " + drag);
            closeEditor(c);
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                Mod.staticCall("hud.HudElementRegistry", "unregister", PROBE_ID);
                Mod.call(hud, "setEditorShowAll", oldShowAll);
                Mod.call(hud, "setEditorSnap", oldSnap);
                if (oldHealthPos[0] != -1 || oldHealthPos[1] != -1) {
                    Mod.call(hud, "setPosition", "statbar_health", oldHealthPos[0], oldHealthPos[1]);
                }
                Mod.call(hud, "save");
                for (Map.Entry<String, Boolean> e : oldOn.entrySet()) {
                    Mod.call(ps, "setReadoutOn", Mod.enumValue("playerstats.StatElements$Readout", e.getKey()), e.getValue());
                }
                Mod.call(ps, "setPredefinedLayout", oldPredefined);
                Mod.call(ps, "setEnabled", oldMaster);
                Mod.call(ps, "save");
                return null;
            });
        }
    }

    private static Screen checkBarsEditor(UiCase c, String via, List<String> enabledStats) {
        Map<String, Object> r = c.onClient(mc -> {
            Map<String, Object> out = new LinkedHashMap<>();
            Screen s = McCompat.screen(mc);
            out.put("screen", describe(s));
            out.put("listed", s != null && s.getClass().getName().equals(Mod.ROOT + EDITOR) ? listedIds(s) : List.of());
            out.put("obj", s);
            return out;
        });
        c.note(via + " opened " + r.get("screen") + " listing " + r.get("listed"));
        c.check(((String) r.get("screen")).endsWith("(Edit Bars)"), via + " opened " + r.get("screen"));
        @SuppressWarnings("unchecked")
        List<String> listed = (List<String>) r.get("listed");
        c.check(listed.size() == enabledStats.size() && listed.containsAll(enabledStats),
                via + " lists " + listed + ", the readouts on are " + enabledStats);
        c.check(!listed.contains(PROBE_ID), via + " lists a non-bar element (the probe)");
        return (Screen) r.get("obj");
    }

    @SuppressWarnings("unchecked")
    private static List<String> enabledStatIds() {
        List<String> out = new ArrayList<>();
        try {
            Class<?> reg = R.cls("hud.HudElementRegistry");
            Method en = reg.getMethod("isEnabledInSettings", R.cls("hud.HudElement"));
            for (Object e : (List<Object>) Mod.staticCall("hud.HudElementRegistry", "all")) {
                String id = (String) Mod.call(e, "id");
                if ((id.startsWith("statbar_") || id.startsWith("stattext_")) && (Boolean) en.invoke(null, e)) {
                    out.add(id);
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        return out;
    }

    // ---- 615 ------------------------------------------------------------------------------------------------------

    static void menuScale(UiCase c) throws Exception {
        Object hud = Mod.cfg(HUD);
        float oldGlobal = ((Number) Mod.call(hud, "getGlobalScale")).floatValue();
        int[] oldWindow = c.onClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight()});
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        Class<?> cls = R.cls(ModScreenDriver.MOD_SCREEN);
        try {
            setWindow(c, 1920, 1080, 2);
            float prev = 0f;
            for (float hs : new float[]{0.5f, 1.0f, 1.5f, 2.0f}) {
                float f = layoutAt(c, hs);
                // Larger at each step until the window-fit cap; never smaller.
                c.check(hs > 1.5f ? f >= prev : f > prev, String.format(Locale.ROOT, "HUD Scale %.0f%% lays the menu "
                        + "out at %.3f against %.3f at the step before", hs * 100, f, prev));
                prev = f;
                Path shot = c.ctx().takeScreenshot(Report.fileName(c.name() + "-menu-1920x1080-gui2-hud"
                        + Math.round(hs * 100)));
                Report.screenshot(c.name(), shot);
                clickSidebar(c, cls, f, String.format(Locale.ROOT, "1920x1080 GUI 2, HUD %.0f%%", hs * 100));
            }
            // A small window, a large HUD Scale: still on screen, still clickable.
            setWindow(c, 854, 480, 2);
            float f = layoutAt(c, 3.0f);
            Path shot = c.ctx().takeScreenshot(Report.fileName(c.name() + "-menu-854x480-gui2-hud300"));
            Report.screenshot(c.name(), shot);
            clickSidebar(c, cls, f, "854x480 GUI 2, HUD 300%");
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                Mod.call(hud, "setGlobalScale", oldGlobal);
                Mod.call(hud, "save");
                mc.options.guiScale().set(oldGui);
                return null;
            });
            c.ctx().getInput().resizeWindow(oldWindow[0], oldWindow[1]);
            c.ticks(5);
            c.onClient(mc -> {
                mc.resizeGui();
                return null;
            });
        }
    }

    private static void setWindow(UiCase c, int w, int h, int gui) {
        c.ctx().getInput().resizeWindow(w, h);
        c.ticks(3);
        c.onClient(mc -> {
            mc.options.guiScale().set(gui);
            mc.resizeGui();
            return null;
        });
        c.ticks(3);
        int[] real = c.onClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight(),
                mc.getWindow().getGuiScale()});
        c.check(real[0] == w && real[1] == h && real[2] == gui, "the window is " + real[0] + "x" + real[1] + " GUI "
                + real[2] + ", not " + w + "x" + h + " GUI " + gui);
    }

    /** Sets HUD Scale, opens a fresh ModScreen, checks it is laid out at menuFactor and drawn inside the window. */
    private static float layoutAt(UiCase c, float hudScale) {
        Map<String, Object> r = c.onClient(mc -> {
            Mod.call(Mod.cfg(HUD), "setGlobalScale", hudScale);
            McCompat.setScreen(mc, newModScreen());
            return null;
        });
        c.ticks(4);
        r = c.onClient(mc -> {
            Map<String, Object> out = new LinkedHashMap<>();
            Screen s = McCompat.screen(mc);
            Window w = mc.getWindow();
            out.put("menuFactor", ((Number) Mod.staticCall("hud.AutoScale", "menuFactor")).floatValue());
            out.put("applied", ((Number) Mod.staticCall("hud.AutoScale", "appliedFactor", s)).floatValue());
            out.put("auto", ((Number) Mod.staticCall("hud.AutoScale", "current")).floatValue());
            out.put("gui", new int[]{w.getGuiScaledWidth(), w.getGuiScaledHeight()});
            out.put("panel", new int[]{(Integer) R.get(s, "panelX"), (Integer) R.get(s, "panelY"),
                    (Integer) R.get(s, "panelW"), (Integer) R.get(s, "panelH")});
            out.put("layout", new int[]{s.width, s.height});
            out.put("tabs", ((List<?>) R.getStatic(R.cls(ModScreenDriver.MOD_SCREEN), "tabs")).size());
            return out;
        });
        float f = (Float) r.get("applied");
        float want = (Float) r.get("menuFactor");
        int[] gui = (int[]) r.get("gui");
        int[] p = (int[]) r.get("panel");
        int[] layout = (int[]) r.get("layout");
        double x0 = p[0] * f;
        double y0 = p[1] * f;
        double x1 = (p[0] + p[2]) * f;
        double y1 = (p[1] + p[3]) * f;
        c.note(String.format(Locale.ROOT, "HUD %.0f%%: Auto Scale %.3f, menu factor %.3f (applied %.3f), layout %dx%d "
                        + "on a %dx%d GUI area; panel drawn at %.1f,%.1f .. %.1f,%.1f", hudScale * 100,
                (Float) r.get("auto"), want, f, layout[0], layout[1], gui[0], gui[1], x0, y0, x1, y1));
        c.check(f == want, "the menu was laid out at " + f + ", AutoScale.menuFactor() says " + want);
        if (hudScale == 1.0f) {
            c.check(f == (Float) r.get("auto"), "at HUD Scale 100% the menu factor " + f + " is not Auto Scale's "
                    + r.get("auto"));
        }
        // The sidebar's last row (gui/ModScreen: rows from panelY+40, 24 apart, 20 tall) must end inside the panel.
        int tabs = (Integer) r.get("tabs");
        int lastRowBottom = p[1] + 40 + 24 * (tabs - 1) + 20;
        if (lastRowBottom > p[1] + p[3]) {
            c.problem(String.format(Locale.ROOT, "HUD %.0f%%: the sidebar's %d rows run to %d, past the panel's bottom %d",
                    hudScale * 100, tabs, lastRowBottom, p[1] + p[3]));
        }
        if (x0 < -0.5 || y0 < -0.5 || x1 > gui[0] + 0.5 || y1 > gui[1] + 0.5) {
            c.problem(String.format(Locale.ROOT, "HUD %.0f%%: the menu panel is drawn off screen (%.1f,%.1f .. %.1f,%.1f"
                    + " on %dx%d)", hudScale * 100, x0, y0, x1, y1, gui[0], gui[1]));
        }
        return f;
    }

    /** A real click (TestInput, through the mouse funnel) at the DRAWN centre of sidebar row 1; it must select tab 1. */
    private static void clickSidebar(UiCase c, Class<?> cls, float f, String where) {
        Screen s = c.onClient(McCompat::screen);
        int panelX = (Integer) R.get(s, "panelX");
        int panelY = (Integer) R.get(s, "panelY");
        int target = 1;
        double gx = (panelX + 8 + 47) * f;
        double gy = (panelY + 40 + 24 * target + 10) * f;
        R.setStatic(cls, "selectedTab", 0);
        double[] win = c.onClient(mc -> {
            Window w = mc.getWindow();
            return new double[]{gx * w.getScreenWidth() / (double) w.getGuiScaledWidth(),
                    gy * w.getScreenHeight() / (double) w.getGuiScaledHeight()};
        });
        c.ctx().getInput().setCursorPos(win[0], win[1]);
        c.ticks(1);
        c.ctx().getInput().pressMouse(0);
        c.ticks(3);
        int got = (Integer) R.getStatic(cls, "selectedTab");
        c.note(String.format(Locale.ROOT, "%s: click at the drawn centre of sidebar row %d (GUI %.1f,%.1f) selected tab %d",
                where, target, gx, gy, got));
        if (got != target) {
            c.problem(where + ": a click on sidebar row " + target + " at its drawn position selected tab " + got);
        }
        // Control: the same row at its UNSCALED spot must miss it whenever the factor is far from 1.
        if (Math.abs(f - 1f) > 0.3f) {
            double ux = panelX + 8 + 47;
            double uy = panelY + 40 + 24 * target + 10;
            R.setStatic(cls, "selectedTab", 0);
            double[] uwin = c.onClient(mc -> {
                Window w = mc.getWindow();
                return new double[]{ux * w.getScreenWidth() / (double) w.getGuiScaledWidth(),
                        uy * w.getScreenHeight() / (double) w.getGuiScaledHeight()};
            });
            c.ctx().getInput().setCursorPos(uwin[0], uwin[1]);
            c.ticks(1);
            c.ctx().getInput().pressMouse(0);
            c.ticks(3);
            int ctl = (Integer) R.getStatic(cls, "selectedTab");
            c.note(where + ": control click at the row's unscaled spot selected tab " + ctl);
            if (ctl == target) {
                c.problem(where + ": a click at the row's UNSCALED spot also selected it - the scale is not applied");
            }
        }
    }

    // ---- 616 ------------------------------------------------------------------------------------------------------

    static void menuFactorMath(UiCase c) {
        // base, hud, guiW, guiH, gui -> expected
        Object[][] cases = {
                {1.0f, 1.0f, 853, 480, 3, 1.0f},          // identity at 100%
                {1.125f, 1.0f, 960, 540, 2, 1.125f},      // identity keeps Auto Scale's own factor
                {1.0f, 0.05f, 853, 480, 3, 0.5f},         // held at 50%: 1.5 screen px per GUI px, not snapped
                {0.5f, 2.0f, 427, 240, 2, 0.5f},          // capped where 500x430 fits (0.558), snapped to 1 px
                {1.125f, 0.5f, 960, 540, 2, 0.5f},        // 0.5625 x2 = 1.125 px -> snapped to 1 px = 0.5
                {1.125f, 2.0f, 960, 540, 2, 540f / 430f}, // 2.25 capped to 540/430
                {1.0f, 1.5f, 1280, 720, 2, 1.5f},         // 3 px per GUI px, already whole
        };
        int bad = 0;
        for (Object[] k : cases) {
            float got = ((Number) Mod.staticCall("hud.AutoScale", "menuFactor", k[0], k[1], k[2], k[3], k[4]))
                    .floatValue();
            float want = (Float) k[5];
            boolean ok = Math.abs(got - want) < 0.002f;
            c.note(String.format(Locale.ROOT, "base %.3f hud %.2f gui %dx%d@%d -> %.4f (want %.4f)%s", k[0], k[1],
                    k[2], k[3], k[4], got, want, ok ? "" : "  WRONG"));
            if (!ok) {
                bad++;
            }
        }
        c.check(bad == 0, bad + " menu factor case(s) wrong");
    }

    // ---- 617 ------------------------------------------------------------------------------------------------------

    /** Getters that name a feature switch: isX...Enabled..., getX...EnabledRaw, isEnabled, isEnabledRaw. */
    private static final Pattern ENABLED_GETTER = Pattern.compile("(is|get)[A-Za-z]*Enabled[A-Za-z]*");
    /** One-time markers ("this migration / seeding already ran"), not settings: the blank profile sets them true. */
    private static final Pattern STATE_FIELD = Pattern.compile("(?i).*(migrat|defaulted|seeded).*");

    static void blankProfile(UiCase c) throws Exception {
        String pre = "tk-617-pre";
        String blank = "tk 617 blank";
        Class<?> pm = R.cls("profiles.ProfileManager");
        String activeBefore = (String) Mod.staticCall("profiles.ProfileManager", "getActiveProfile");
        Path marker = (Path) R.getStatic(pm, "ACTIVE_MARKER");
        List<ConfigSweep.Singleton> configs = ConfigSweep.singletons(c);
        c.note(configs.size() + " config singleton(s) in the jar (testkit JarIndex)");
        c.check(configs.size() > 100, "only " + configs.size() + " config classes found in the jar");
        // Something visibly ON in the live settings, so "the live settings did not change" is not vacuous.
        Map<String, Object> turnedOn = new LinkedHashMap<>();
        String[][] sample = {{"leapcounter.LeapCounterConfig", "Enabled"}, {"trajectories.TrajectoriesConfig", "Enabled"},
                {"position.PositionConfig", "Enabled"}};
        boolean applied = false;
        try {
            c.onClient(mc -> {
                for (String[] s : sample) {
                    Object cfg = Mod.cfg(s[0]);
                    turnedOn.put(s[0] + "." + s[1], getBool(cfg, s[1]));
                    Mod.call(cfg, "set" + s[1], true);
                    Mod.call(cfg, "save");
                }
                return null;
            });
            Map<String, Boolean> liveBefore = c.onClient(mc -> switches(configs));
            String save = c.onClient(mc -> resultText(Mod.staticCall("profiles.ProfileManager", "saveCurrentAsProfile", pre)));
            c.note("snapshot of the live settings: " + save);

            // The real button in the real Profiles tab, the name typed in the box.
            ModScreenDriver d = tabByClass(c, "ProfilesTab");
            c.onClient(mc -> {
                R.setStatic(R.cls("gui.tab.ProfilesTab"), "newProfileName", blank);
                rebuild(d);
                return null;
            });
            long mark = LogTap.mark();
            boolean took = c.onClient(mc -> pressRow(d, "New Blank Profile"));
            c.check(took, "the Profiles tab has no New Blank Profile row");
            c.ticks(2);
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
            for (String l : LogTap.since(mark)) {
                if (l.contains("Blank profile")) {
                    c.note("log: " + l.trim());
                }
            }
            @SuppressWarnings("unchecked")
            List<String> listed = (List<String>) Mod.staticCall("profiles.ProfileManager", "listProfiles");
            c.check(listed.contains(blank), "no profile \"" + blank + "\" after pressing New Blank Profile: " + listed);
            Map<String, Boolean> liveAfter = c.onClient(mc -> switches(configs));
            int changed = 0;
            for (Map.Entry<String, Boolean> e : liveBefore.entrySet()) {
                if (!e.getValue().equals(liveAfter.get(e.getKey()))) {
                    changed++;
                    c.problem("making the blank profile changed the LIVE " + e.getKey() + " from " + e.getValue());
                }
            }
            long onBefore = liveBefore.values().stream().filter(b -> b).count();
            c.note(liveBefore.size() + " live switches compared before/after making it (" + onBefore + " on); "
                    + changed + " changed");
            c.check(onBefore >= 3, "fewer than 3 live switches were on, so 'unchanged' proves little");

            // Load it and walk everything.
            String apply = c.onClient(mc -> resultText(Mod.staticCall("profiles.ProfileManager", "applyProfile", blank)));
            applied = true;
            c.ticks(2);
            c.note("applied: " + apply);
            Map<String, Object> walk = c.onClient(mc -> walkAllOff(configs));
            @SuppressWarnings("unchecked")
            List<String> on = (List<String>) walk.get("on");
            c.note("after Load: " + walk.get("configs") + " config(s) walked, " + walk.get("getters")
                    + " enabled getter(s) and " + walk.get("fields") + " boolean field(s) read; left at defaults: "
                    + walk.get("kept"));
            for (String s : on) {
                c.problem("still ON in the blank profile: " + s);
            }
            c.check((Integer) walk.get("getters") > 150, "only " + walk.get("getters") + " enabled getters were read");
            boolean trap = c.onClient(mc -> (Boolean) Mod.staticCall("autotrap.AutoTrap", "isEnabledRaw"));
            c.check(!trap, "Auto Trap is still on in the blank profile");
            boolean gate = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled"));
            c.note("Skyblock Only in the blank profile: " + gate + " (its default, a gate rather than a feature)");
        } finally {
            if (applied) {
                String back = c.onClient(mc -> resultText(Mod.staticCall("profiles.ProfileManager", "applyProfile", pre)));
                c.note("restored: " + back);
            }
            c.onClient(mc -> {
                for (String[] s : sample) {
                    Object cfg = Mod.cfg(s[0]);
                    Object was = turnedOn.get(s[0] + "." + s[1]);
                    if (was != null) {
                        Mod.call(cfg, "set" + s[1], was);
                        Mod.call(cfg, "save");
                    }
                }
                Mod.staticCall("profiles.ProfileManager", "deleteProfile", blank);
                Mod.staticCall("profiles.ProfileManager", "deleteProfile", pre);
                try {
                    if (activeBefore == null) {
                        Files.deleteIfExists(marker);
                    } else {
                        Files.writeString(marker, activeBefore);
                    }
                } catch (Exception ignored) {
                    // the next case does not read it
                }
                // The suite runs with Skyblock Only off; a profile load put the file's value back.
                Mod.staticCall("util.SkyblockGate", "setEnabled", false);
                return null;
            });
        }
        boolean gateNow = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled"));
        c.check(!gateNow, "Skyblock Only was not put back off after the case");
    }

    private static boolean getBool(Object cfg, String prop) {
        try {
            return (Boolean) Mod.call(cfg, "is" + prop);
        } catch (AssertionError | RuntimeException e) {
            return (Boolean) Mod.call(cfg, "get" + prop + "Raw");
        }
    }

    /** Every enabled getter of every config, by name: what the live settings say. */
    private static Map<String, Boolean> switches(List<ConfigSweep.Singleton> configs) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (ConfigSweep.Singleton s : configs) {
            Object inst;
            try {
                inst = ConfigSweep.instance(s.cls());
            } catch (Throwable t) {
                continue;
            }
            for (Method m : s.cls().getMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == boolean.class && !Modifier.isStatic(m.getModifiers())
                        && ENABLED_GETTER.matcher(m.getName()).matches()) {
                    try {
                        out.put(s.rel() + "." + m.getName(), (Boolean) m.invoke(inst));
                    } catch (ReflectiveOperationException ignored) {
                        // a getter that throws is not a switch reading
                    }
                }
            }
        }
        return out;
    }

    private static Map<String, Object> walkAllOff(List<ConfigSweep.Singleton> configs) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> on = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        int getters = 0;
        int fields = 0;
        int walked = 0;
        for (ConfigSweep.Singleton s : configs) {
            if (s.rel().equals("hud.HudConfig") || s.rel().equals("proxy.config.ProxyConfig")) {
                kept.add(s.rel());
                continue;
            }
            Object inst;
            try {
                inst = ConfigSweep.instance(s.cls());
            } catch (Throwable t) {
                on.add(s.rel() + " (getInstance threw " + UiCase.describe(t) + ")");
                continue;
            }
            walked++;
            for (Method m : s.cls().getMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == boolean.class && !Modifier.isStatic(m.getModifiers())
                        && ENABLED_GETTER.matcher(m.getName()).matches()) {
                    getters++;
                    try {
                        if ((Boolean) m.invoke(inst)) {
                            on.add(s.rel() + "." + m.getName() + "()");
                        }
                    } catch (ReflectiveOperationException e) {
                        on.add(s.rel() + "." + m.getName() + "() threw");
                    }
                }
            }
            for (Class<?> k = s.cls(); k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    int mod = f.getModifiers();
                    if (Modifier.isStatic(mod) || Modifier.isTransient(mod) || f.getType() != boolean.class
                            || STATE_FIELD.matcher(f.getName()).matches()) {
                        continue;
                    }
                    try {
                        f.setAccessible(true);
                        fields++;
                        if (f.getBoolean(inst)) {
                            on.add(s.rel() + "." + f.getName() + " (field)");
                        }
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        // unreadable: not counted
                    }
                }
            }
        }
        out.put("on", on);
        out.put("kept", kept);
        out.put("getters", getters);
        out.put("fields", fields);
        out.put("configs", walked);
        return out;
    }

    // ---- 618 ------------------------------------------------------------------------------------------------------

    static void shareProfile(UiCase c) throws Exception {
        String name = "tk-618-share";
        Path share = (Path) Mod.staticCall("profiles.ProfileManager", "shareFolder");
        Path profilesDir = (Path) R.getStatic(R.cls("profiles.ProfileManager"), "PROFILES_DIR");
        try {
            String save = c.onClient(mc -> resultText(Mod.staticCall("profiles.ProfileManager", "saveCurrentAsProfile", name)));
            c.note("saved: " + save);
            Files.deleteIfExists(share.resolve(name + ".zip"));
            Files.deleteIfExists(profilesDir.resolve(name + ".zip"));
            ModScreenDriver d = tabByClass(c, "ProfilesTab");
            // The Export button on this profile's row: the row label is the StringWidget just before it.
            long mark = LogTap.mark();
            boolean took = c.onClient(mc -> {
                List<AbstractWidget> rows = d.content();
                for (int i = 0; i + 2 < rows.size(); i++) {
                    if (ModScreenDriver.label(rows.get(i)).endsWith(name)
                            && ModScreenDriver.label(rows.get(i + 2)).equals("Export")) {
                        return ModScreenDriver.press(rows.get(i + 2));
                    }
                }
                return false;
            });
            c.check(took, "no Export button on the \"" + name + "\" row of the Profiles tab");
            c.ticks(2);
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
            Path zip = share.resolve(name + ".zip");
            c.note("share folder " + share + "; zip there: " + Files.isRegularFile(zip) + "; zip in the profiles folder: "
                    + Files.isRegularFile(profilesDir.resolve(name + ".zip")));
            c.check(Files.isRegularFile(zip), "Export put no zip in the share folder " + share);
            c.check(!Files.isRegularFile(profilesDir.resolve(name + ".zip")), "Export still wrote into the profiles folder");
            long files;
            try (java.util.stream.Stream<Path> s = Files.list(profilesDir.resolve(name))) {
                files = s.count();
            }
            int entries;
            try (ZipFile z = new ZipFile(zip.toFile())) {
                entries = z.size();
            }
            c.note("the profile has " + files + " file(s), the zip " + entries + " entr(ies)");
            c.check(entries >= 5 && entries == files, "the zip holds " + entries + " entries, the profile " + files);
            String opened = null;
            for (String l : LogTap.since(mark)) {
                if (l.contains("[TestHook] would open")) {
                    opened = l.trim();
                    c.note("log: " + opened);
                }
            }
            c.check(opened != null, "no open of the share folder was made (no [TestHook] would open line)");
            String folder = share.toString();
            c.check(opened.contains(folder) || opened.contains(folder.replace('\\', '/')),
                    "the open was not of the share folder: " + opened);
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                Mod.staticCall("profiles.ProfileManager", "deleteProfile", name);
                return null;
            });
            Files.deleteIfExists(share.resolve(name + ".zip"));
        }
    }

    // ---- shared -------------------------------------------------------------------------------------------------

    static String resultText(Object result) {
        return (Boolean) Mod.call(result, "success") + " " + Mod.call(result, "message");
    }

    private static Screen newModScreen() {
        try {
            Class<?> cls = R.cls(ModScreenDriver.MOD_SCREEN);
            R.setStatic(cls, "searchQuery", "");
            R.setStatic(cls, "scrollOffset", 0);
            R.setStatic(cls, "selectedTab", 0);
            return (Screen) cls.getConstructor(Screen.class).newInstance((Object) null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Screen newEditor(boolean bars) {
        try {
            Class<?> cls = R.cls(EDITOR);
            return bars ? (Screen) cls.getMethod("forBars", Screen.class).invoke(null, (Object) null)
                    : (Screen) cls.getConstructor(Screen.class).newInstance((Object) null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Screen openEditor(UiCase c, boolean bars) {
        Screen s = c.onClient(mc -> {
            Screen e = newEditor(bars);
            McCompat.setScreen(mc, e);
            return e;
        });
        c.ticks(3);
        return s;
    }

    private static void closeEditor(UiCase c) {
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            return null;
        });
        c.ticks(2);
    }

    @SuppressWarnings("unchecked")
    private static List<String> listedIds(Screen editor) {
        List<String> out = new ArrayList<>();
        for (Object e : (List<Object>) R.get(editor, "shown")) {
            out.add((String) Mod.call(e, "id"));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static int[] livePos(Screen editor, String id) {
        Map<String, int[]> live = (Map<String, int[]>) R.get(editor, "livePositions");
        int[] p = live.get(id);
        return p == null ? null : p.clone();
    }

    /** Every text one frame of {@code screen} draws: {plain text, "x,y,w,h"}. */
    private static List<String[]> texts(Minecraft mc, Screen screen, int mx, int my) {
        GuiRenderState state = new GuiRenderState();
        GuiGraphicsExtractor g = new GuiGraphicsExtractor(mc, state, mx, my);
        screen.extractRenderStateWithTooltipAndSubtitles(g, mx, my, 0.5f);
        List<String[]> out = new ArrayList<>();
        state.forEachText(t -> {
            String s = TextRuns.string(t);
            String plain = net.minecraft.ChatFormatting.stripFormatting(s);
            ScreenRectangle b = TextRuns.box(t);
            out.add(new String[]{plain == null ? s : plain,
                    b == null ? "?" : b.left() + "," + b.top() + "," + b.width() + "," + b.height()});
        });
        return out;
    }

    private static int[] box(String s) {
        if (s.equals("?")) {
            return new int[]{0, 0, 0, 0};
        }
        String[] p = s.split(",");
        return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3])};
    }

    /** The real ModScreen on Home (top-level tab 0, every section open). */
    private static ModScreenDriver homeTab(UiCase c) {
        ModScreenDriver d = c.onClient(mc -> {
            try {
                ModScreenDriver dr = new ModScreenDriver(mc);
                McCompat.setScreen(mc, dr.screen);
                return dr;
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        c.ticks(2);
        c.onClient(mc -> {
            d.select(0);
            d.expandAll(d.topTabs().get(0));
            rebuild(d);
            return null;
        });
        return d;
    }

    /** The real ModScreen on the category holding the tab of class {@code simpleName}, every section open. */
    private static ModScreenDriver tabByClass(UiCase c, String simpleName) {
        ModScreenDriver d = c.onClient(mc -> {
            try {
                ModScreenDriver dr = new ModScreenDriver(mc);
                McCompat.setScreen(mc, dr.screen);
                return dr;
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        c.ticks(2);
        int top = c.onClient(mc -> {
            List<ModScreenDriver.Node> tree = d.tree();
            for (int i = 0; i < tree.size(); i++) {
                List<ModScreenDriver.Node> flat = new ArrayList<>();
                ModScreenDriver.flatten(List.of(tree.get(i)), flat);
                for (ModScreenDriver.Node n : flat) {
                    if (n.tab().getClass().getSimpleName().equals(simpleName)) {
                        return i;
                    }
                }
            }
            return -1;
        });
        c.check(top >= 0, "no " + simpleName + " anywhere in the menu");
        c.onClient(mc -> {
            d.select(top);
            d.expandAll(d.topTabs().get(top));
            rebuild(d);
            return null;
        });
        return d;
    }

    private static void rebuild(ModScreenDriver d) {
        try {
            d.rebuild();
        } catch (Throwable t) {
            throw new AssertionError("ModScreen.rebuild threw: " + UiCase.describe(t), t);
        }
    }

    /** Presses the first content row whose label starts with {@code prefix}; false when there is none. */
    private static boolean pressRow(ModScreenDriver d, String prefix) {
        for (AbstractWidget w : d.content()) {
            if (d.isSettingsButton(w) && ModScreenDriver.label(w).startsWith(prefix)) {
                return ModScreenDriver.press(w);
            }
        }
        return false;
    }

    // ---- the probe element ---------------------------------------------------------------------------------------

    private static Object probeElement;

    /** A real {@code hud.HudElement} (a proxy of the mod's interface): a PROBE_W x PROBE_H grey block, always on. */
    private static Object probe() {
        if (probeElement != null) {
            return probeElement;
        }
        Class<?> iface = Mod.cls("hud.HudElement");
        probeElement = Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, (self, m, args) ->
                switch (m.getName()) {
                    case "id" -> PROBE_ID;
                    case "displayName" -> "Testkit Probe";
                    case "defaultX" -> 120;
                    case "defaultY" -> 140;
                    case "width" -> PROBE_W;
                    case "height" -> PROBE_H;
                    case "defaultScale" -> 1.0f;
                    case "isEnabledInSettings" -> true;
                    case "render" -> {
                        GuiGraphicsExtractor g = (GuiGraphicsExtractor) args[0];
                        int x = (Integer) args[1];
                        int y = (Integer) args[2];
                        g.fill(x, y, x + PROBE_W, y + PROBE_H, 0xFF808080);
                        yield null;
                    }
                    case "hashCode" -> System.identityHashCode(self);
                    case "equals" -> self == args[0];
                    case "toString" -> "TestkitHmpProbe";
                    default -> m.isDefault() ? java.lang.reflect.InvocationHandler.invokeDefault(self, m, args) : null;
                });
        return probeElement;
    }
}
