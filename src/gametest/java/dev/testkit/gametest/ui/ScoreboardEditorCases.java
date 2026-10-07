package dev.testkit.gametest.ui;

import com.mojang.blaze3d.platform.Window;
import dev.testkit.compat.McCompat;
import dev.testkit.gametest.TextRuns;
import dev.testkit.gametest.mod.Mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.state.gui.GuiRenderState;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * 401-ui-scoreboard-editor: the Custom Scoreboard's lists as redone on mod branch scoreboard-editor (killer560,
 * 2026-10-07: "redo the custom scoreboard such that things are draggable to be in order and it has the add and
 * trashcan system SkyHanni uses instead of an individual toggle system").
 *
 * <ul>
 *   <li>DRAG: on a known six-line board, row 3 is dragged above row 1 with real mouse input (press on the row, move,
 *       release) through the real {@code gui.ModScreen}. The saved order, the file on disk and the DRAWN board (text
 *       runs of {@code CustomScoreboardFeature.drawBoard} over {@code previewLines()}, the routine the HUD layer and
 *       the editors draw with) must all read the new order, and the widget must have reported a drag in progress
 *       before the release (so a press that did nothing cannot pass).</li>
 *   <li>TRASH / ADD: a row's trash can, clicked for real, takes it off the saved order and the drawn board; Add's
 *       dropdown then offers it (and nothing that is on the board); its "+" button puts it back at the bottom and it
 *       is drawn last.</li>
 *   <li>AUTO-SCROLL: on the default board (longer than the list's box) the wheel scrolls the list to its end, the last
 *       row is held at the list's top edge until the list has scrolled to the top by itself, and dropped there: it
 *       must become line 1.</li>
 *   <li>OPTIONS: clicking Mayor shows Mayor's own settings beside the list, and pressing one flips its config field.</li>
 *   <li>SAVE/RELOAD: the config reloaded from disk keeps the order and the pool; the tab shows the same rows.</li>
 *   <li>MIGRATION: an old toggle-list save (entries with on/off flags, reordered, some off) loads as the same lines in
 *       the same order, the off ones in Add's pool; saved again it carries the new keys and the old ones. With
 *       {@code TESTKIT_SCOREBOARD_EXPORT=<dir>} the case writes that save THROUGH THE JAR UNDER TEST (an old jar writes
 *       its own toggle file) plus the lines that jar draws from it into {@code <dir>}; a later run with
 *       {@code -PseedConfig=<dir>} finds them and requires the seeded file, untouched, to draw the same lines.</li>
 *   <li>LAYOUT: no two rows overlap on the Lines, Events and Chunked Stats pages, with nothing selected, with a line
 *       selected, and with the dropdown open; every control has a tooltip; the full-screen editor holds the same list.</li>
 *   <li>PICTURES: the Lines page on this jar ({@code -old-} if it has no list widget), plus a selection, the open
 *       dropdown and a drag in flight on a new jar; copied to {@code TESTKIT_SCOREBOARD_SHOTS} when set.</li>
 * </ul>
 */
final class ScoreboardEditorCases {

    static final String TAB = "Custom Scoreboard";
    private static final String CFG = "scoreboard.CustomScoreboardConfig";
    private static final String FEATURE = "scoreboard.CustomScoreboardFeature";
    private static final String ENTRY = "scoreboard.ScoreboardEntry";
    private static final String EDITOR = "scoreboard.ScoreboardListEditor";
    private static final String TAB_CLASS = "gui.tab.CustomScoreboardTab";
    private static final String LIST_CLASS = "gui.DragListWidget";
    private static final String EXPORT_PROPS = "scoreboard-editor-export.properties";
    private static final String EXPORT_LINES = "scoreboard-editor-export-lines.txt";

    /** Text only that entry's preview draws. */
    private static final Map<String, String> SIGN = new LinkedHashMap<>();

    static {
        String[][] s = {{"TITLE", "SKYBLOCK"}, {"DATE", "Late Summer"}, {"TIME", "10:40pm"}, {"ISLAND", "Hub"},
                {"PLAYER_AMOUNT", "Players"}, {"LOCATION", "Village"}, {"PROFILE", "Ironman"}, {"PURSE", "Purse"},
                {"EVENTS", "Time Elapsed"}, {"COOKIE", "Cookie Buff"}, {"QUIVER", "Flint Arrow"}, {"POWER", "Power:"},
                {"TUNING", "Tunings"}, {"OBJECTIVE", "Goblin King"}, {"POWDER", "Mithril"}, {"MAYOR", "Diana"},
                {"FOOTER", "hypixel.net"}, {"PET", "Golden Dragon"}};
        for (String[] p : s) {
            SIGN.put(p[0], p[1]);
        }
    }

    private static final List<String> START = List.of("TITLE", "DATE", "ISLAND", "PURSE", "MAYOR", "FOOTER");

    private ScoreboardEditorCases() {
    }

    static void run(UiCase c) throws Exception {
        Path cfgPath = c.onClient(mc -> (Path) R.getStatic(R.cls(CFG), "CONFIG_PATH"));
        Path configDir = c.onClient(mc -> mc.gameDirectory.toPath().resolve("config"));
        byte[] saved = Files.exists(cfgPath) ? Files.readAllBytes(cfgPath) : null;
        boolean newJar = c.onClient(mc -> Mod.has(LIST_CLASS) && Mod.has("scoreboard.EntryOrder"));
        c.note("this jar " + (newJar ? "has" : "has NO") + " the add/trash list editor (gui.DragListWidget, "
                + "scoreboard.EntryOrder); config " + cfgPath);
        // The drawn board is the PREVIEW only off Skyblock: on Skyblock previewLines() is the live board. A case before
        // this one that leaves a "SKYBLOCK" sidebar in the UI world (395's sim room did, 2026-10-07) makes every check
        // below read the live board. Say which sidebar it is rather than failing on the symptom.
        String sidebar = c.onClient(mc -> {
            var o = mc.level.getScoreboard().getDisplayObjective(net.minecraft.world.scores.DisplaySlot.SIDEBAR);
            return o == null ? "none" : o.getName() + " \"" + o.getDisplayName().getString() + "\"";
        });
        boolean onSkyblock = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isOnSkyblock"));
        c.note("before the case: sidebar " + sidebar + ", SkyblockGate.isOnSkyblock " + onSkyblock);
        if (onSkyblock) {
            c.problem("the UI world reads as Skyblock (sidebar " + sidebar + ") - an earlier case left its sidebar; "
                    + "the board would be the live one, not the editor's preview");
            return;
        }
        try {
            Path seeded = configDir.resolve(EXPORT_PROPS);
            if (Files.exists(seeded)) {
                importCheck(c, configDir, cfgPath, seeded);
            }
            String export = System.getenv("TESTKIT_SCOREBOARD_EXPORT");
            if (export != null && !export.isBlank()) {
                exportOld(c, configDir, cfgPath, Path.of(export));
            }
            pictures(c, newJar);
            if (!newJar) {
                c.problem("no draggable list in this jar: the Custom Scoreboard still has its per-line on/off toggles "
                        + "and arrow buttons (no gui.DragListWidget / scoreboard.EntryOrder)");
                return;
            }
            dragTrashAdd(c, cfgPath);
            autoScroll(c);
            options(c);
            layout(c);
            reload(c, cfgPath);
            migrationInline(c, cfgPath);
            visualEditor(c);
        } finally {
            try {
                open(c, "LINES"); // the tab's page is the tab's own state: leave it on Lines, where it opens
            } catch (Throwable t) {
                c.note("page restore: " + UiCase.describe(t));
            }
            restore(c, cfgPath, saved);
        }
    }

    // ---- the real menu ------------------------------------------------------------------------------------------

    /** The mod menu on the Custom Scoreboard tab, {@code page} selected (Page enum constant, both jars have LINES). */
    private static ModScreenDriver open(UiCase c, String page) {
        ModScreenDriver d = c.onClient(mc -> {
            try {
                ModScreenDriver drv = new ModScreenDriver(mc);
                List<Object> tops = drv.topTabs();
                for (int i = 0; i < tops.size(); i++) {
                    List<Object[]> chain = new ArrayList<>();
                    Object[] found = new Object[1];
                    if (find(drv, tops.get(i), chain, found)) {
                        drv.select(i);
                        for (Object[] step : chain) {
                            drv.expanded(step[0]).add((Integer) step[1]);
                        }
                        Object tab = found[0];
                        Class<?> pageCls = R.field(tab.getClass(), "page").getType();
                        for (Object p : pageCls.getEnumConstants()) {
                            if (((Enum<?>) p).name().equals(page)) {
                                R.set(tab, "page", p);
                            }
                        }
                        McCompat.setScreen(mc, drv.screen);
                        drv.rebuild();
                        reveal(drv);
                        return drv;
                    }
                }
                return null;
            } catch (Throwable t) {
                throw new AssertionError("could not open gui.ModScreen: " + UiCase.describe(t), t);
            }
        });
        c.check(d != null, "no '" + TAB + "' tab in the mod menu");
        c.ticks(3);
        return d;
    }

    /**
     * Scrolls the menu so the tab's first row (its on/off switch) sits at the top of the content pane: in the New
     * folder the Custom Scoreboard section is many sections down, below the visible band.
     */
    private static void reveal(ModScreenDriver d) throws Throwable {
        for (int attempt = 0; attempt < 2; attempt++) {
            AbstractWidget pane = (AbstractWidget) R.get(d.screen, "contentPane");
            AbstractWidget head = null;
            for (AbstractWidget w : ours(d)) {
                if (ModScreenDriver.label(w).startsWith("Custom Scoreboard:")) {
                    head = w;
                    break;
                }
            }
            if (pane == null || head == null || Math.abs(head.getY() - (pane.getY() + 2)) <= 1) {
                return;
            }
            int offset = (Integer) R.getStatic(d.screenCls, "scrollOffset");
            d.scrollTo(Math.max(0, offset + head.getY() - (pane.getY() + 2)));
            d.rebuild();
        }
    }

    /** The list must lie inside the visible band of the menu, or a real click on it lands on something else. */
    private static void listVisible(UiCase c, ModScreenDriver d) {
        String bad = c.onClient(mc -> {
            AbstractWidget pane = (AbstractWidget) R.get(d.screen, "contentPane");
            AbstractWidget l = list(d);
            if (l == null) {
                return "no list widget on this page";
            }
            if (l.getY() < pane.getY() || l.getY() + l.getHeight() > pane.getY() + pane.getHeight()) {
                return "the list (" + l.getY() + ".." + (l.getY() + l.getHeight()) + ") is not inside the menu's visible "
                        + "band (" + pane.getY() + ".." + (pane.getY() + pane.getHeight()) + ")";
            }
            return null;
        });
        c.check(bad == null, String.valueOf(bad));
    }

    private static boolean find(ModScreenDriver d, Object tab, List<Object[]> chain, Object[] found) {
        if (TAB.equals(R.get(tab, "name"))) {
            found[0] = tab;
            return true;
        }
        if (!d.isFolder(tab)) {
            return false;
        }
        List<Object> subs = d.subTabs(tab);
        for (int j = 0; j < subs.size(); j++) {
            chain.add(new Object[]{tab, j});
            if (find(d, subs.get(j), chain, found)) {
                return true;
            }
            chain.remove(chain.size() - 1);
        }
        return false;
    }

    /** Rows our tab built (scoped to it by FolderTab), in build order. */
    @SuppressWarnings("unchecked")
    private static List<AbstractWidget> ours(ModScreenDriver d) {
        Map<AbstractWidget, String> scopes = (Map<AbstractWidget, String>) R.getStatic(R.cls("gui.SettingTooltips"),
                "SCOPES");
        List<AbstractWidget> out = new ArrayList<>();
        for (AbstractWidget w : d.content()) {
            if (TAB.equals(scopes.get(w))) {
                out.add(w);
            }
        }
        return out;
    }

    private static AbstractWidget list(ModScreenDriver d) {
        Class<?> cls = R.cls(LIST_CLASS);
        for (AbstractWidget w : d.content()) {
            if (cls.isInstance(w)) {
                return w;
            }
        }
        return null;
    }

    private static AbstractWidget button(ModScreenDriver d, String prefix) {
        for (AbstractWidget w : d.content()) {
            if (d.isSettingsButton(w) && ModScreenDriver.label(w).startsWith(prefix)) {
                return w;
            }
        }
        return null;
    }

    private static int num(Object target, String method, Object... args) {
        return ((Number) Mod.call(target, method, args)).intValue();
    }

    // ---- real input -----------------------------------------------------------------------------------------------

    /** Screen (layout) coordinates -> window coordinates, through the factor the screen was laid out with. */
    private static double[] toWindow(UiCase c, Screen s, double sx, double sy) {
        return c.onClient(mc -> {
            float f = ((Number) Mod.staticCall("hud.AutoScale", "appliedFactor", s)).floatValue();
            Window w = mc.getWindow();
            return new double[]{sx * f * w.getScreenWidth() / (double) w.getGuiScaledWidth(),
                    sy * f * w.getScreenHeight() / (double) w.getGuiScaledHeight()};
        });
    }

    private static void moveTo(UiCase c, Screen s, double sx, double sy) {
        double[] a = toWindow(c, s, sx, sy);
        c.ctx().getInput().setCursorPos(a[0], a[1]);
    }

    private static void click(UiCase c, Screen s, double sx, double sy) {
        moveTo(c, s, sx, sy);
        c.ticks(2);
        c.ctx().getInput().pressMouse(0);
        c.ticks(3);
    }

    private static void clickWidget(UiCase c, Screen s, AbstractWidget w) {
        click(c, s, w.getX() + w.getWidth() / 2.0, w.getY() + w.getHeight() / 2.0);
    }

    /** Presses at (x, y0), moves in {@code steps} to (x, y1), returns whether the list said "dragging" before release. */
    private static boolean drag(UiCase c, ModScreenDriver d, double x, double y0, double y1, int steps, String shot) {
        moveTo(c, d.screen, x, y0);
        c.ticks(2);
        c.ctx().getInput().holdMouse(0);
        c.ticks(2);
        for (int i = 1; i <= steps; i++) {
            moveTo(c, d.screen, x, y0 + (y1 - y0) * i / steps);
            c.ticks(1);
        }
        c.ticks(2);
        boolean dragging = c.onClient(mc -> {
            AbstractWidget l = list(d);
            return l != null && (Boolean) Mod.call(l, "isDraggingRow");
        });
        if (shot != null) {
            picture(c, shot);
        }
        c.ctx().getInput().releaseMouse(0);
        c.ticks(3);
        return dragging;
    }

    // ---- what is saved and what is drawn ---------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static List<String> savedOrder() {
        List<String> out = new ArrayList<>();
        for (Object e : (List<Object>) Mod.call(Mod.cfg(CFG), "getLineOrder")) {
            out.add(((Enum<?>) e).name());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<String> pool() {
        List<String> out = new ArrayList<>();
        Object order = Mod.call(Mod.cfg(CFG), "entries");
        for (Object e : (List<Object>) Mod.call(order, "available")) {
            out.add(((Enum<?>) e).name());
        }
        return out;
    }

    private static void setBoard(List<String> ids) {
        List<Object> list = new ArrayList<>();
        for (String id : ids) {
            list.add(Mod.enumValue(ENTRY, id));
        }
        Object cfg = Mod.cfg(CFG);
        Mod.call(cfg, "setLineOrder", list);
        Mod.call(cfg, "save");
    }

    /** The lines the board draws, top to bottom (text runs grouped by their row). Client thread. */
    @SuppressWarnings("unchecked")
    static List<String> drawnLines(Minecraft mc) {
        GuiRenderState state = new GuiRenderState();
        GuiGraphicsExtractor g = new GuiGraphicsExtractor(mc, state, -1, -1);
        Object cfg = Mod.cfg(CFG);
        Object lines = Mod.staticCall(FEATURE, "previewLines");
        Mod.staticCall(FEATURE, "drawBoard", g, mc.font, 0, 0, lines, cfg, false);
        TreeMap<Integer, TreeMap<Integer, String>> rows = new TreeMap<>();
        state.forEachText(t -> {
            ScreenRectangle r = TextRuns.box(t);
            String s = TextRuns.string(t);
            if (s.isBlank()) {
                return;
            }
            rows.computeIfAbsent(r.top(), k -> new TreeMap<>()).merge(r.left(), s, (a, b) -> a);
        });
        List<String> out = new ArrayList<>();
        for (TreeMap<Integer, String> row : rows.values()) {
            out.add(String.join("", row.values()));
        }
        return out;
    }

    /** Which of {@link #SIGN}'s entries the drawn lines show, in the order they are drawn. */
    private static List<String> drawnEntries(List<String> lines) {
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            for (Map.Entry<String, String> e : SIGN.entrySet()) {
                if (line.contains(e.getValue()) && !out.contains(e.getKey())) {
                    out.add(e.getKey());
                }
            }
        }
        return out;
    }

    private static List<String> onlySigned(List<String> ids) {
        return ids.stream().filter(SIGN::containsKey).toList();
    }

    /** The saved order, the file's lineOrder.active and the drawn board must all equal {@code want}. */
    private static void expectBoard(UiCase c, Path cfgPath, List<String> want, String when) throws Exception {
        List<String> saved = c.onClient(mc -> savedOrder());
        List<String> drawn = c.onClient(mc -> drawnEntries(drawnLines(mc)));
        List<String> file = fileOrder(cfgPath);
        c.note(when + ": saved " + saved + "; file " + file + "; drawn " + drawn);
        if (!saved.equals(want)) {
            c.problem(when + ": saved order " + saved + ", expected " + want);
        }
        if (!want.equals(file)) {
            c.problem(when + ": the file's lineOrder.active is " + file + ", expected " + want);
        }
        if (!drawn.equals(onlySigned(want))) {
            c.problem(when + ": the board draws " + drawn + ", expected " + onlySigned(want));
        }
    }

    private static List<String> fileOrder(Path cfgPath) throws Exception {
        JsonObject o = JsonParser.parseString(Files.readString(cfgPath, StandardCharsets.UTF_8)).getAsJsonObject();
        List<String> out = new ArrayList<>();
        if (!o.has("lineOrder")) {
            return null;
        }
        for (JsonElement e : o.getAsJsonObject("lineOrder").getAsJsonArray("active")) {
            out.add(e.getAsString());
        }
        return out;
    }

    // ---- drag, trash, add -----------------------------------------------------------------------------------------

    private static void dragTrashAdd(UiCase c, Path cfgPath) throws Exception {
        c.onClient(mc -> {
            Mod.call(Mod.cfg(CFG), "setEnabled", true);
            setBoard(START);
            Mod.staticCall(EDITOR, "resetSession");
            return null;
        });
        ModScreenDriver d = open(c, "LINES");
        listVisible(c, d);
        expectBoard(c, cfgPath, START, "start");

        // Drag row 3 (Island) above row 1 (Title).
        double[] at = c.onClient(mc -> {
            AbstractWidget l = list(d);
            c.check(l != null, "no list widget on the Lines page");
            return new double[]{num(l, "handleX"), num(l, "rowY", 2) + 8, num(l, "rowY", 0) + 3,
                    l.getY(), l.getY() + l.getHeight()};
        });
        c.note(String.format(Locale.ROOT, "drag: press at %.0f,%.0f, release at y %.0f (list %.0f..%.0f)", at[0], at[1],
                at[2], at[3], at[4]));
        boolean dragging = drag(c, d, at[0], at[1], at[2], 6, "drag-in-flight");
        c.check(dragging, "the list never reported a drag in progress - the press or the moves did not reach it");
        List<String> afterDrag = new ArrayList<>(List.of("ISLAND", "TITLE", "DATE", "PURSE", "MAYOR", "FOOTER"));
        expectBoard(c, cfgPath, afterDrag, "after dragging row 3 above row 1");

        // Trash Purse (now row 4).
        double[] trash = c.onClient(mc -> {
            AbstractWidget l = list(d);
            return new double[]{num(l, "trashX"), num(l, "rowY", 3) + 8};
        });
        click(c, d.screen, trash[0], trash[1]);
        List<String> afterTrash = new ArrayList<>(afterDrag);
        afterTrash.remove("PURSE");
        expectBoard(c, cfgPath, afterTrash, "after the trash can on Purse");
        List<String> pool = c.onClient(mc -> pool());
        c.check(pool.contains("PURSE"), "Purse is not in the pool after the trash: " + pool);

        // Add's dropdown offers it, and nothing on the board.
        AbstractWidget add = c.onClient(mc -> button(d, "Add Line"));
        c.check(add != null, "no 'Add Line' button");
        clickWidget(c, d.screen, add);
        List<String> offered = c.onClient(mc -> {
            List<String> out = new ArrayList<>();
            for (AbstractWidget w : d.content()) {
                String l = ModScreenDriver.label(w);
                if (d.isSettingsButton(w) && l.startsWith("+ ")) {
                    out.add(l.substring(2));
                }
            }
            return out;
        });
        c.note("Add offers " + offered.size() + ": " + offered);
        if (!offered.contains("Purse")) {
            c.problem("Add's dropdown does not offer Purse after it was trashed: " + offered);
        }
        for (String onBoard : new String[]{"Title", "Island", "Mayor", "Footer", "Date"}) {
            if (offered.contains(onBoard)) {
                c.problem("Add offers '" + onBoard + "', which is already on the board");
            }
        }
        if (offered.stream().filter("Separator"::equals).count() > 1) {
            c.problem("Add lists 'Separator' more than once");
        }
        picture(c, "add-open");
        AbstractWidget plus = c.onClient(mc -> button(d, "+ Purse"));
        c.check(plus != null, "no '+ Purse' button in the open dropdown");
        clickWidget(c, d.screen, plus);
        List<String> afterAdd = new ArrayList<>(afterTrash);
        afterAdd.add("PURSE");
        expectBoard(c, cfgPath, afterAdd, "after Add > Purse");
        boolean closed = c.onClient(mc -> button(d, "+ ") == null);
        if (!closed) {
            c.problem("the dropdown is still open after picking an entry");
        }
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            return null;
        });
    }

    // ---- auto-scroll -----------------------------------------------------------------------------------------------

    private static void autoScroll(UiCase c) throws Exception {
        c.onClient(mc -> {
            Object cfg = Mod.cfg(CFG);
            Mod.call(cfg, "resetEntries");
            Mod.call(cfg, "save");
            Mod.staticCall(EDITOR, "resetSession");
            return null;
        });
        ModScreenDriver d = open(c, "LINES");
        listVisible(c, d);
        int[] g = c.onClient(mc -> {
            AbstractWidget l = list(d);
            return new int[]{num(l, "maxScroll"), l.getX() + l.getWidth() / 2, l.getY() + l.getHeight() / 2};
        });
        c.check(g[0] > 0, "the default board fits the list without scrolling - nothing to auto-scroll");
        // The wheel over the list scrolls the list, not the page.
        moveTo(c, d.screen, g[1], g[2]);
        c.ticks(2);
        // One notch at a time until the list is at its end: every notch while it can still move must move the list and
        // leave the page where it is (a notch past the end goes to the page, by design, so none is sent).
        int pageBefore = c.onClient(mc -> (Integer) R.getStatic(d.screenCls, "scrollOffset"));
        int notches = 0;
        int[] after = null;
        for (; notches < 40; notches++) {
            after = c.onClient(mc -> {
                AbstractWidget l = list(d);
                return new int[]{num(l, "scroll"), num(l, "maxScroll"), (Integer) R.getStatic(d.screenCls, "scrollOffset")};
            });
            if (after[0] >= after[1] || after[2] != pageBefore) {
                break;
            }
            c.ctx().getInput().scroll(-1);
            c.ticks(1);
        }
        c.note("wheel over the list: " + notches + " notch(es); list scroll " + after[0] + " of " + after[1]
                + "; page scroll " + pageBefore + " -> " + after[2]);
        if (after[0] != after[1]) {
            c.problem("the wheel did not scroll the list to its end (" + after[0] + " of " + after[1] + ")");
        }
        if (after[2] != pageBefore) {
            c.problem("the wheel over the list also scrolled the page (" + pageBefore + " -> " + after[2] + ")");
        }
        List<String> before = c.onClient(mc -> savedOrder());
        String last = before.get(before.size() - 1);
        double[] at = c.onClient(mc -> {
            AbstractWidget l = list(d);
            return new double[]{num(l, "handleX"), num(l, "rowY", before.size() - 1) + 8, num(l, "innerTop") + 2,
                    num(l, "innerBottom")};
        });
        c.check(at[1] < at[3], "the last row is not visible after scrolling to the end (row y " + at[1] + ")");
        moveTo(c, d.screen, at[0], at[1]);
        c.ticks(2);
        c.ctx().getInput().holdMouse(0);
        c.ticks(2);
        for (int i = 1; i <= 6; i++) {
            moveTo(c, d.screen, at[0], at[1] + (at[2] - at[1]) * i / 6.0);
            c.ticks(1);
        }
        int waited = 0;
        int scroll = -1;
        boolean dragging = false;
        for (; waited < 200; waited++) {
            int[] s = c.onClient(mc -> {
                AbstractWidget l = list(d);
                return new int[]{num(l, "scroll"), (Boolean) Mod.call(l, "isDraggingRow") ? 1 : 0};
            });
            scroll = s[0];
            dragging |= s[1] == 1;
            if (scroll == 0) {
                break;
            }
            c.ticks(1);
        }
        c.ctx().getInput().releaseMouse(0);
        c.ticks(3);
        List<String> afterDrop = c.onClient(mc -> savedOrder());
        c.note("auto-scroll: held '" + last + "' at the top edge; list scroll reached " + scroll + " after " + waited
                + " tick(s) (from " + after[1] + "); dropped -> line 1 is " + afterDrop.get(0));
        c.check(dragging, "the list never reported a drag in progress during the auto-scroll hold");
        if (scroll != 0) {
            c.problem("holding a dragged row at the list's top edge did not scroll it to the top (scroll " + scroll
                    + " after " + waited + " ticks)");
        }
        List<String> want = new ArrayList<>(before);
        want.remove(want.size() - 1);
        want.add(0, last);
        if (!afterDrop.equals(want)) {
            c.problem("after the auto-scroll drop the order is " + afterDrop + ", expected '" + last + "' first then "
                    + "the rest unchanged");
        }
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            return null;
        });
    }

    // ---- per-line options -------------------------------------------------------------------------------------------

    private static void options(UiCase c) throws Exception {
        c.onClient(mc -> {
            setBoard(START);
            Mod.staticCall(EDITOR, "resetSession");
            return null;
        });
        ModScreenDriver d = open(c, "LINES");
        listVisible(c, d);
        boolean hiddenBefore = c.onClient(mc -> button(d, "Mayor Perks") == null);
        if (!hiddenBefore) {
            c.problem("Mayor Perks is shown with no line selected");
        }
        double[] row = c.onClient(mc -> {
            AbstractWidget l = list(d);
            int i = savedOrder().indexOf("MAYOR");
            return new double[]{l.getX() + l.getWidth() / 3.0, num(l, "rowY", i) + 8};
        });
        click(c, d.screen, row[0], row[1]);
        List<String> labels = c.onClient(mc -> ours(d).stream().map(ModScreenDriver::label).toList());
        c.note("Mayor selected: " + labels);
        for (String want : new String[]{"Mayor Perks", "Next Mayor Timer", "Show Minister", "Perkpocalypse Mayor"}) {
            if (labels.stream().noneMatch(l -> l.startsWith(want + ":"))) {
                c.problem("clicking Mayor did not show '" + want + "'");
            }
        }
        boolean beforeFlag = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(CFG), "isShowMayorPerks"));
        AbstractWidget perks = c.onClient(mc -> button(d, "Mayor Perks"));
        if (perks != null) {
            clickWidget(c, d.screen, perks);
            boolean afterFlag = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(CFG), "isShowMayorPerks"));
            if (afterFlag == beforeFlag) {
                c.problem("pressing Mayor Perks did not change isShowMayorPerks");
            }
            AbstractWidget back = c.onClient(mc -> button(d, "Mayor Perks"));
            if (back != null) {
                clickWidget(c, d.screen, back);
            }
        }
        boolean orderKept = c.onClient(mc -> savedOrder().equals(START));
        if (!orderKept) {
            c.problem("selecting a line changed the order: " + c.onClient(mc -> savedOrder()));
        }
        picture(c, "mayor-selected");
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            return null;
        });
    }

    // ---- layout ---------------------------------------------------------------------------------------------------

    private static void layout(UiCase c) {
        c.onClient(mc -> {
            setBoard(START);
            Mod.staticCall(EDITOR, "resetSession");
            return null;
        });
        int layouts = 0;
        for (String page : new String[]{"LINES", "EVENTS", "STATS"}) {
            ModScreenDriver d = open(c, page);
            layouts += c.onClient(mc -> {
                int n = 0;
                try {
                    check(c, d, page + " nothing selected");
                    n++;
                    AbstractWidget add = button(d, "Add ");
                    ModScreenDriver.press(add);
                    d.rebuild();
                    check(c, d, page + " Add open");
                    n++;
                    ModScreenDriver.press(button(d, "Add "));
                    d.rebuild();
                    if (page.equals("LINES")) {
                        for (String sel : new String[]{"PARTY", "TUNING", "QUIVER", "EVENTS"}) {
                            Mod.call(Mod.cfg(CFG), "setLineOrder", List.of(Mod.enumValue(ENTRY, "TITLE"),
                                    Mod.enumValue(ENTRY, sel), Mod.enumValue(ENTRY, "FOOTER")));
                            d.rebuild();
                            AbstractWidget l = list(d);
                            int y = num(l, "rowY", 1) + 8;
                            l.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(l.getX() + 30, y,
                                    new net.minecraft.client.input.MouseButtonInfo(0, 0)), false);
                            l.mouseReleased(new net.minecraft.client.input.MouseButtonEvent(l.getX() + 30, y,
                                    new net.minecraft.client.input.MouseButtonInfo(0, 0)));
                            d.rebuild();
                            Object selected = Mod.staticCall(EDITOR, "selected",
                                    Mod.enumValue(EDITOR + "$Section", "LINES"));
                            if (selected == null || !((Enum<?>) selected).name().equals(sel)) {
                                c.problem("clicking the " + sel + " row did not select it (selected " + selected + ")");
                            }
                            check(c, d, page + " " + sel + " selected");
                            n++;
                        }
                        setBoard(START);
                        Mod.staticCall(EDITOR, "resetSession");
                    }
                } catch (Throwable t) {
                    c.problem(page + ": " + UiCase.describe(t));
                }
                McCompat.setScreen(mc, null);
                return n;
            });
        }
        c.note("layout: " + layouts + " layouts checked for overlaps and tooltips");
    }

    /** No two of our rows intersect; every control has a tooltip under the tab's scope. */
    private static void check(UiCase c, ModScreenDriver d, String when) {
        List<AbstractWidget> ws = ours(d);
        List<String> labels = new ArrayList<>();
        for (int a = 0; a < ws.size(); a++) {
            AbstractWidget p = ws.get(a);
            labels.add(ModScreenDriver.label(p));
            for (int b = a + 1; b < ws.size(); b++) {
                AbstractWidget q = ws.get(b);
                if (p.getX() < q.getX() + q.getWidth() && q.getX() < p.getX() + p.getWidth()
                        && p.getY() < q.getY() + q.getHeight() && q.getY() < p.getY() + p.getHeight()) {
                    c.problem(when + ": overlapping rows '" + ModScreenDriver.label(p) + "' and '"
                            + ModScreenDriver.label(q) + "'");
                }
            }
            boolean control = d.isSettingsButton(p) || p instanceof AbstractSliderButton || R.cls(LIST_CLASS).isInstance(p);
            if (control) {
                Object desc = Mod.staticCall("gui.SettingTooltips", "describe", "New", p, p.getMessage().getString());
                if (desc == null || desc.toString().isBlank()) {
                    c.problem(when + ": no tooltip for '" + ModScreenDriver.label(p) + "'");
                }
            }
        }
        c.note(when + ": " + ws.size() + " rows " + labels);
    }

    // ---- save / reload ----------------------------------------------------------------------------------------------

    private static void reload(UiCase c, Path cfgPath) throws Exception {
        List<String> board = List.of("FOOTER", "MAYOR", "TITLE", "PET", "ISLAND");
        List<String>[] before = c.onClient(mc -> {
            setBoard(board);
            @SuppressWarnings("unchecked")
            List<String>[] r = new List[]{savedOrder(), pool(), drawnLines(mc)};
            Mod.staticCall(CFG, "load");
            return r;
        });
        List<String>[] after = c.onClient(mc -> {
            @SuppressWarnings("unchecked")
            List<String>[] r = new List[]{savedOrder(), pool(), drawnLines(mc)};
            return r;
        });
        String from = c.onClient(mc -> (String) Mod.call(Mod.cfg(CFG), "getLoadedFrom"));
        c.note("reload: loaded from " + from + "; order " + after[0] + "; drawn " + after[2]);
        if (!after[0].equals(board) || !after[0].equals(before[0])) {
            c.problem("after a reload the order is " + after[0] + ", saved " + before[0]);
        }
        if (!after[1].equals(before[1])) {
            c.problem("after a reload the pool is " + after[1] + ", was " + before[1]);
        }
        if (!after[2].equals(before[2])) {
            c.problem("after a reload the board draws " + after[2] + ", before " + before[2]);
        }
        if (!from.startsWith("current")) {
            c.problem("the reload read the lines from '" + from + "', not the new lineOrder key");
        }
        ModScreenDriver d = open(c, "LINES");
        List<String> rows = c.onClient(mc -> {
            AbstractWidget l = list(d);
            Object model = R.get(l, "model");
            List<String> out = new ArrayList<>();
            int n = num(model, "size");
            for (int i = 0; i < n; i++) {
                out.add((String) Mod.call(model, "label", i));
            }
            McCompat.setScreen(mc, null);
            return out;
        });
        c.note("reload: the tab lists " + rows);
        if (!rows.equals(List.of("Footer", "Mayor", "Title", "Pet", "Island"))) {
            c.problem("after a reload the tab lists " + rows);
        }
    }

    // ---- migration ----------------------------------------------------------------------------------------------

    /**
     * An old toggle-list save: every line in the jar's default order with its default on/off, then Purse moved up
     * under Title, Lobby Code, Cookie Buff and Objective switched off, Pet switched on.
     */
    static String legacyJson() {
        JsonArray entries = new JsonArray();
        List<Object[]> rows = new ArrayList<>();
        for (Object e : R.cls(ENTRY).getEnumConstants()) {
            rows.add(new Object[]{((Enum<?>) e).name(), Mod.field(e, "enabledByDefault")});
        }
        Object[] purse = rows.stream().filter(r -> r[0].equals("PURSE")).findFirst().orElseThrow();
        rows.remove(purse);
        rows.add(1, purse);
        for (Object[] r : rows) {
            switch ((String) r[0]) {
                case "LOBBY_CODE", "COOKIE", "OBJECTIVE" -> r[1] = false;
                case "PET" -> r[1] = true;
                default -> {
                }
            }
            JsonObject o = new JsonObject();
            o.addProperty("id", (String) r[0]);
            o.addProperty("enabled", (Boolean) r[1]);
            entries.add(o);
        }
        JsonObject root = new JsonObject();
        root.addProperty("enabled", true);
        root.add("entries", entries);
        return root.toString();
    }

    /** What the legacy save above must load as: its enabled ids in its order. */
    static List<String> legacyExpected() {
        JsonObject o = JsonParser.parseString(legacyJson()).getAsJsonObject();
        List<String> out = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("entries")) {
            if (e.getAsJsonObject().get("enabled").getAsBoolean()) {
                out.add(e.getAsJsonObject().get("id").getAsString());
            }
        }
        return out;
    }

    private static void migrationInline(UiCase c, Path cfgPath) throws Exception {
        Files.writeString(cfgPath, c.onClient(mc -> legacyJson()), StandardCharsets.UTF_8);
        List<String> want = c.onClient(mc -> legacyExpected());
        Object[] got = c.onClient(mc -> {
            Mod.staticCall(CFG, "load");
            return new Object[]{savedOrder(), pool(), Mod.call(Mod.cfg(CFG), "getLoadedFrom"), drawnLines(mc)};
        });
        @SuppressWarnings("unchecked")
        List<String> order = (List<String>) got[0];
        @SuppressWarnings("unchecked")
        List<String> pool = (List<String>) got[1];
        c.note("migration: loaded from " + got[2] + "; " + order.size() + " lines " + order + "; pool " + pool);
        if (!order.equals(want)) {
            c.problem("an old toggle save loaded as " + order + ", expected its enabled lines in order " + want);
        }
        for (String off : new String[]{"LOBBY_CODE", "COOKIE", "OBJECTIVE"}) {
            if (!pool.contains(off)) {
                c.problem("migration: " + off + " was off in the old save but is not in Add's pool");
            }
        }
        if (!String.valueOf(got[2]).startsWith("legacy")) {
            c.problem("migration: the lines were read from '" + got[2] + "', not migrated from the legacy key");
        }
        List<String> drawnMigrated = c.onClient(mc -> drawnLines(mc));
        // Saved again: both keys, and it reads back as "current" with the same order.
        c.onClient(mc -> Mod.call(Mod.cfg(CFG), "save"));
        String text = Files.readString(cfgPath, StandardCharsets.UTF_8);
        JsonObject o = JsonParser.parseString(text).getAsJsonObject();
        if (!o.has("lineOrder") || !o.has("entries")) {
            c.problem("a save after migration lacks " + (o.has("lineOrder") ? "" : "lineOrder ")
                    + (o.has("entries") ? "" : "entries (old jars read it)"));
        }
        List<String> reread = c.onClient(mc -> {
            Mod.staticCall(CFG, "load");
            return savedOrder();
        });
        if (!reread.equals(want)) {
            c.problem("the migrated board saved and reloaded as " + reread);
        }
        // Drawn: the same as a board set directly to the expected order.
        List<String> drawnDirect = c.onClient(mc -> {
            setBoard(want);
            return drawnLines(mc);
        });
        if (!drawnMigrated.equals(drawnDirect)) {
            c.problem("the migrated board draws " + drawnMigrated + " but the same order set directly draws "
                    + drawnDirect);
        }
        c.note("migration: drawn " + drawnMigrated.size() + " lines " + drawnMigrated);
    }

    // ---- old jar vs new jar ---------------------------------------------------------------------------------------

    private static void exportOld(UiCase c, Path configDir, Path cfgPath, Path out) throws Exception {
        Files.writeString(cfgPath, c.onClient(mc -> legacyJson()), StandardCharsets.UTF_8);
        List<String> lines = c.onClient(mc -> {
            Mod.staticCall(CFG, "load");
            Mod.call(Mod.cfg(CFG), "save"); // the file as THIS jar writes it
            return drawnLines(mc);
        });
        Path rel = configDir.relativize(cfgPath);
        Path dst = out.resolve(rel);
        Files.createDirectories(dst.getParent());
        Files.copy(cfgPath, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.write(out.resolve(EXPORT_LINES), lines, StandardCharsets.UTF_8);
        Properties p = new Properties();
        p.setProperty("config", rel.toString().replace('\\', '/'));
        p.setProperty("lines", String.valueOf(lines.size()));
        p.setProperty("jar", c.onClient(mc -> Mod.has(LIST_CLASS)) ? "new" : "old");
        try (var o = Files.newBufferedWriter(out.resolve(EXPORT_PROPS), StandardCharsets.UTF_8)) {
            p.store(o, "401-ui-scoreboard-editor export");
        }
        c.note("export (" + p.getProperty("jar") + " jar): " + rel + " and " + lines.size() + " drawn lines -> " + out
                + ": " + lines);
    }

    private static void importCheck(UiCase c, Path configDir, Path cfgPath, Path props) throws Exception {
        Properties p = new Properties();
        try (var in = Files.newBufferedReader(props, StandardCharsets.UTF_8)) {
            p.load(in);
        }
        List<String> want = Files.readAllLines(configDir.resolve(EXPORT_LINES), StandardCharsets.UTF_8);
        String raw = Files.readString(cfgPath, StandardCharsets.UTF_8);
        JsonObject o = JsonParser.parseString(raw).getAsJsonObject();
        c.note("import: seeded save from the " + p.getProperty("jar") + " jar; keys lineOrder=" + o.has("lineOrder")
                + " entries=" + o.has("entries"));
        if (o.has("lineOrder")) {
            c.problem("import: the seeded file already has lineOrder - it is not an old toggle save (rewritten before "
                    + "the case?)");
        }
        Object[] got = c.onClient(mc -> {
            Mod.staticCall(CFG, "load");
            Object from = Mod.has("scoreboard.EntryOrder") ? Mod.call(Mod.cfg(CFG), "getLoadedFrom") : "n/a";
            return new Object[]{drawnLines(mc), from};
        });
        @SuppressWarnings("unchecked")
        List<String> drawn = (List<String>) got[0];
        c.note("import: loaded from " + got[1] + "; old jar drew " + want.size() + " lines, this jar draws "
                + drawn.size());
        if (!drawn.equals(want)) {
            for (int i = 0; i < Math.max(want.size(), drawn.size()); i++) {
                String a = i < want.size() ? want.get(i) : "<none>";
                String b = i < drawn.size() ? drawn.get(i) : "<none>";
                if (!a.equals(b)) {
                    c.problem("import: line " + (i + 1) + " was '" + a + "' on the old jar, now '" + b + "'");
                }
            }
        } else {
            c.note("import: identical, line for line: " + drawn);
        }
    }

    // ---- full-screen editor -------------------------------------------------------------------------------------

    private static void visualEditor(UiCase c) {
        c.onClient(mc -> {
            setBoard(START);
            Mod.staticCall(EDITOR, "resetSession");
            try {
                Screen s = (Screen) R.cls("scoreboard.ScoreboardEditorScreen").getConstructor(Screen.class)
                        .newInstance((Object) null);
                McCompat.setScreen(mc, s);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
            return null;
        });
        c.ticks(3);
        c.onClient(mc -> {
            Screen s = McCompat.screen(mc);
            List<AbstractWidget> ws = new ArrayList<>();
            for (var child : s.children()) {
                if (child instanceof AbstractWidget w) {
                    ws.add(w);
                }
            }
            boolean hasList = ws.stream().anyMatch(w -> R.cls(LIST_CLASS).isInstance(w));
            if (!hasList) {
                c.problem("the full-screen editor has no draggable list");
            }
            for (int a = 0; a < ws.size(); a++) {
                for (int b = a + 1; b < ws.size(); b++) {
                    AbstractWidget p = ws.get(a);
                    AbstractWidget q = ws.get(b);
                    if (p.getX() < q.getX() + q.getWidth() && q.getX() < p.getX() + p.getWidth()
                            && p.getY() < q.getY() + q.getHeight() && q.getY() < p.getY() + p.getHeight()) {
                        c.problem("full-screen editor: overlapping '" + ModScreenDriver.label(p) + "' and '"
                                + ModScreenDriver.label(q) + "'");
                    }
                }
            }
            c.note("full-screen editor: " + ws.size() + " widgets, list " + hasList);
            return null;
        });
        picture(c, "visual-editor");
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            return null;
        });
    }

    // ---- pictures -----------------------------------------------------------------------------------------------

    private static void pictures(UiCase c, boolean newJar) {
        c.onClient(mc -> {
            Object cfg = Mod.cfg(CFG);
            Mod.call(cfg, "setEnabled", true);
            Mod.call(cfg, "resetEntries");
            Mod.call(cfg, "save");
            if (newJar) {
                Mod.staticCall(EDITOR, "resetSession");
            }
            return null;
        });
        open(c, "LINES");
        c.ticks(3);
        picture(c, newJar ? "new-lines" : "old-lines");
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            return null;
        });
    }

    private static void picture(UiCase c, String what) {
        try {
            String name = c.name() + "-" + what;
            Path taken = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(name));
            Path kept = dev.testkit.harness.Report.screenshot(name, taken);
            Path shown = kept != null ? kept : taken;
            c.note("picture " + name + " -> " + shown);
            String dir = System.getenv("TESTKIT_SCOREBOARD_SHOTS");
            if (dir != null && !dir.isBlank()) {
                Path d = Path.of(dir);
                Files.createDirectories(d);
                Files.copy(shown, d.resolve(shown.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            c.note("picture " + what + ": " + e);
        }
    }

    // ---- restore ----------------------------------------------------------------------------------------------------

    private static void restore(UiCase c, Path cfgPath, byte[] saved) {
        try {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                try {
                    if (saved == null) {
                        Files.deleteIfExists(cfgPath);
                    } else {
                        Files.write(cfgPath, saved);
                    }
                } catch (java.io.IOException e) {
                    throw new AssertionError(e);
                }
                Mod.staticCall(CFG, "load");
                if (Mod.has(EDITOR)) {
                    Mod.staticCall(EDITOR, "resetSession");
                }
                return null;
            });
        } catch (Throwable t) {
            c.note("config restore: " + UiCase.describe(t));
        }
    }
}
