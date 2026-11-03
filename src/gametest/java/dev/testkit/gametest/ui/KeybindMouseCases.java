package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * killer560, 2026-11-02: "most of the set keybinds things wont let me set mouse buttons to it ... fix that for all areas"
 * (mod keybindmouse-1102: every KeyCaptureTab takes any mouse button through ModScreen, Escape cancels, Backspace clears).
 *
 * <ul>
 *   <li>1103-ui-keybind-mouse-walk: every feature drawer (all of {@code ModScreen.allLeaves}, Home's sections included),
 *       as it opens and again with its switch turned on, collapsibles opened. Every "Name: value" button that is not an
 *       ON/OFF switch or a cycler gets a REAL left click through the window; one that then has the menu listening
 *       ({@code isCapturingKey}) is a key row. The left click itself must not be bound. Then a REAL mouse button 4 click:
 *       the menu must stop listening, the row must read "Mouse 4", and a file under config/killer560 must have changed
 *       and hold -103 (KeyUtil's code for button 4). Every config file is restored after each drawer.</li>
 *   <li>1103-ui-keybind-mouse-game: Freelook's Toggle Keybind bound to Mouse 4 by a real click in its drawer, the menu
 *       closed, then a real Mouse 4 press in game turns Freelook on and a second one off.</li>
 * </ul>
 */
final class KeybindMouseCases {

    static final String WALK = "1103-ui-keybind-mouse-walk";
    static final String GAME = "1103-ui-keybind-mouse-game";
    static final String[] CASES = {WALK, GAME};

    /** Stored (GLFW) mouse button 4 and its KeyUtil code. */
    private static final int MOUSE_4 = 3;
    private static final String MOUSE_4_CODE = "-103";

    private static final Pattern VALUE_LABEL = Pattern.compile("^([^:]{2,48}): (.{1,40})$");

    private KeybindMouseCases() {
    }

    private record Row(String name, int ordinal) {
        String key(String tab) {
            return tab + " | " + name + (ordinal > 0 ? " #" + (ordinal + 1) : "");
        }
    }

    // ---- 1103 walk ------------------------------------------------------------------------------------------------

    static void walk(UiCase c, Deny deny) throws Exception {
        Path root = configRoot();
        try (AutoCloseable all = MenuLayoutCases.allTested(c)) {
            List<Object> leaves = c.onClient(mc -> {
                R.setStatic(R.cls(MenuACases.SCREEN), "tabs", null);
                return new ArrayList<>(MenuACases.leaves());
            });
            Map<Path, byte[]> original = snapshot(root);
            int[] n = new int[5]; // 0 key rows, 1 bound to Mouse 4, 2 failed, 3 candidates clicked, 4 drawers
            Set<String> done = new LinkedHashSet<>();
            List<String> keyRows = new ArrayList<>();
            try {
                for (Object leaf : leaves) {
                    String tab = c.onClient(mc -> MenuACases.name(leaf));
                    n[4]++;
                    Boolean master = c.onClient(mc -> MenuACases.freshMaster(leaf));
                    String masterLabel = c.onClient(mc -> MenuACases.masterLabel(leaf));
                    List<Boolean> layouts = new ArrayList<>();
                    layouts.add(false);
                    if (Boolean.FALSE.equals(master) && (masterLabel == null || deny.button(masterLabel) == null)) {
                        layouts.add(true);
                    }
                    for (boolean on : layouts) {
                        List<Row> rows = c.onClient(mc -> candidates(open(mc, leaf, on), deny));
                        for (Row row : rows) {
                            String key = row.key(tab);
                            if (!done.add(key)) {
                                continue;
                            }
                            n[3]++;
                            one(c, root, leaf, on, row, key, n, keyRows);
                        }
                    }
                    restore(c, root, original);
                }
            } finally {
                c.onClient(mc -> {
                    McCompat.setScreen(mc, null);
                    return null;
                });
                restore(c, root, original);
            }
            c.note("drawers " + n[4] + ", value-style buttons clicked " + n[3] + ", key rows " + n[0] + ", bound to Mouse 4 "
                    + n[1] + ", failed " + n[2]);
            c.note("key rows: " + keyRows);
            int floor = Mod.isCheat() ? 280 : 195; // measured on mod fddf98be6: 291 cheat, 205 legit
            c.check(n[0] >= floor, "only " + n[0] + " key rows found (floor " + floor + ") - the walk is broken");
            c.check(n[1] == n[0] && n[2] == 0, (n[0] - n[1]) + " key row(s) did not take Mouse 4");
        }
    }

    private static void one(UiCase c, Path root, Object leaf, boolean on, Row row, String key, int[] n, List<String> keyRows)
            throws IOException {
        double[] at = c.onClient(mc -> {
            Screen s = open(mc, leaf, on);
            AbstractWidget w = reveal(s, row);
            return w == null ? null : window(mc, s, w.getX() + w.getWidth() / 2.0, w.getY() + w.getHeight() / 2.0);
        });
        if (at == null) {
            c.note(key + ": could not bring it into view");
            return;
        }
        String before = c.onClient(mc -> labelOf(McCompat.screen(mc), row));
        c.ctx().getInput().setCursorPos(at[0], at[1]);
        c.ticks(1);
        c.ctx().getInput().pressMouse(dev.testkit.compat.McKeys.mouse(0));
        c.ticks(2);
        Object[] state = c.onClient(mc -> {
            Screen s = McCompat.screen(mc);
            boolean menu = s != null && s.getClass().getName().endsWith(".gui.ModScreen");
            boolean listening = menu && (Boolean) Mod.call(s, "isCapturingKey");
            return new Object[]{menu, listening, menu ? labelOf(s, row) : null};
        });
        if (!(Boolean) state[1]) {
            // Not a key row (a color, a name, an action): put the menu back for the next row.
            return;
        }
        n[0]++;
        keyRows.add(key);
        String listeningLabel = (String) state[2];
        Map<Path, byte[]> pre = snapshot(root);
        c.ctx().getInput().setCursorPos(at[0], at[1]);
        c.ticks(1);
        c.ctx().getInput().pressMouse(dev.testkit.compat.McKeys.mouse(MOUSE_4));
        c.ticks(2);
        Object[] after = c.onClient(mc -> {
            Screen s = McCompat.screen(mc);
            boolean menu = s != null && s.getClass().getName().endsWith(".gui.ModScreen");
            if (!menu) {
                return new Object[]{false, null};
            }
            try {
                R.call0(s, "rebuild");
            } catch (Throwable t) {
                throw new AssertionError(UiCase.describe(t), t);
            }
            return new Object[]{(Boolean) Mod.call(s, "isCapturingKey"), labelOf(s, row)};
        });
        String label = (String) after[1];
        List<String> saved = changedWith(root, pre, MOUSE_4_CODE);
        boolean ok = !(Boolean) after[0] && label != null && label.contains("Mouse 4") && !saved.isEmpty()
                && (listeningLabel == null || !listeningLabel.contains("Left Mouse"));
        if (ok) {
            n[1]++;
            c.note(key + ": '" + before + "' -> listening '" + listeningLabel + "' -> '" + label + "', saved in " + saved);
        } else {
            n[2]++;
            c.problem(key + ": '" + before + "' -> listening '" + listeningLabel + "' -> after Mouse 4 still listening "
                    + after[0] + ", label '" + label + "', files holding " + MOUSE_4_CODE + " that changed: " + saved);
        }
    }

    // ---- 1103 game ------------------------------------------------------------------------------------------------

    static void game(UiCase c) throws Exception {
        Path root = configRoot();
        Map<Path, byte[]> original = snapshot(root);
        String kb = "gui.ModuleKeybinds";
        Object cfg = c.onClient(mc -> Mod.cfg("freelook.FreelookConfig"));
        try {
            Object leaf = c.onClient(mc -> MenuACases.leaf("FreelookTab"));
            c.onClient(mc -> {
                Mod.call(cfg, "setEnabled", false);
                Mod.staticCall(kb, "set", "FreelookTab", -1);
                return null;
            });
            Row row = new Row("Toggle Keybind", 0);
            double[] at = c.onClient(mc -> {
                Screen s = open(mc, leaf, false);
                AbstractWidget w = reveal(s, row);
                return w == null ? null : window(mc, s, w.getX() + w.getWidth() / 2.0, w.getY() + w.getHeight() / 2.0);
            });
            c.check(at != null, "no Toggle Keybind row in the Freelook drawer");
            if (at == null) {
                return;
            }
            c.ctx().getInput().setCursorPos(at[0], at[1]);
            c.ticks(1);
            c.ctx().getInput().pressMouse(dev.testkit.compat.McKeys.mouse(0));
            c.ticks(2);
            c.ctx().getInput().pressMouse(dev.testkit.compat.McKeys.mouse(MOUSE_4));
            c.ticks(2);
            int code = c.onClient(mc -> (Integer) Mod.staticCall(kb, "get", "FreelookTab"));
            String label = c.onClient(mc -> labelOf(McCompat.screen(mc), row));
            c.note("Freelook Toggle Keybind after a real Mouse 4 click: code " + code + ", row '" + label + "'");
            c.check(code == -103, "the Toggle Keybind holds " + code + ", want -103 (Mouse 4)");
            c.onClient(mc -> {
                Mod.staticCall(kb, "load");
                return null;
            });
            int reloaded = c.onClient(mc -> (Integer) Mod.staticCall(kb, "get", "FreelookTab"));
            c.check(reloaded == -103, "Mouse 4 did not survive a reload from disk (" + reloaded + ")");
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
            c.ticks(3);
            int t0 = c.onClient(mc -> (Integer) Mod.staticCall(kb, "toggles"));
            boolean[] states = new boolean[2];
            for (int i = 0; i < 2; i++) {
                c.ctx().getInput().holdMouse(dev.testkit.compat.McKeys.mouse(MOUSE_4));
                c.ticks(3);
                c.ctx().getInput().releaseMouse(dev.testkit.compat.McKeys.mouse(MOUSE_4));
                c.ticks(3);
                states[i] = c.onClient(mc -> (Boolean) Mod.call(cfg, "isEnabled"));
            }
            int t1 = c.onClient(mc -> (Integer) Mod.staticCall(kb, "toggles"));
            c.note("Freelook after Mouse 4 press 1: " + states[0] + ", press 2: " + states[1] + "; toggles " + t0 + " -> " + t1);
            c.check(t1 - t0 == 2, "two real Mouse 4 presses toggled " + (t1 - t0) + " times");
            c.check(states[0] && !states[1], "Mouse 4 did not turn Freelook on then off (" + states[0] + ", " + states[1] + ")");
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
            restore(c, root, original);
        }
    }

    // ---- shared ---------------------------------------------------------------------------------------------------

    /** A fresh menu with {@code leaf}'s drawer open (its switch turned on when {@code on}), collapsibles opened. */
    private static Screen open(Minecraft mc, Object leaf, boolean on) {
        try {
            Screen s = McCompat.screen(mc);
            if (s == null || !s.getClass().getName().endsWith(".gui.ModScreen")) {
                s = MenuACases.open(mc);
            }
            Mod.call(s, "openFeature", leaf);
            if (on && Boolean.FALSE.equals(MenuACases.freshMaster(leaf))) {
                Mod.call(s, "pressDrawerMaster");
            }
            for (int i = 0; i < 30; i++) {
                AbstractWidget closed = null;
                for (AbstractWidget w : MenuACases.paneRows(s)) {
                    if (ModScreenDriver.label(w).startsWith(ModScreenDriver.CLOSED)) {
                        closed = w;
                        break;
                    }
                }
                if (closed == null) {
                    break;
                }
                ModScreenDriver.press(closed);
                R.call0(s, "rebuild");
            }
            return s;
        } catch (Throwable t) {
            throw new AssertionError(UiCase.describe(t), t);
        }
    }

    /** Every "Name: value" settings button that is not a switch, a cycler or on the deny list. */
    private static List<Row> candidates(Screen s, Deny deny) {
        List<Row> out = new ArrayList<>();
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (AbstractWidget w : MenuACases.paneRows(s)) {
            if (!w.visible || !w.active || !w.getClass().getName().endsWith("SettingsButtonWidget") || cycling(w)) {
                continue;
            }
            String label = ModScreenDriver.label(w);
            Matcher m = VALUE_LABEL.matcher(label);
            if (!m.matches() || TabSweep.TOGGLE.matcher(label).matches() || deny.button(label) != null) {
                continue;
            }
            String name = m.group(1);
            int ord = seen.merge(name, 1, Integer::sum) - 1;
            out.add(new Row(name, ord));
        }
        return out;
    }

    private static boolean cycling(AbstractWidget w) {
        try {
            return (Boolean) w.getClass().getMethod("isCycling").invoke(w);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    /** The row's widget, scrolled into the pane's visible part; null when it is not there. */
    private static AbstractWidget reveal(Screen s, Row row) {
        try {
            for (int attempt = 0; attempt < 4; attempt++) {
                R.call0(s, "rebuild");
                AbstractWidget w = find(s, row);
                AbstractWidget pane = (AbstractWidget) R.get(s, "contentPane");
                if (w == null || pane == null) {
                    return null;
                }
                int top = pane.getY() + 4;
                int bottom = pane.getY() + pane.getHeight() - 4;
                if (w.getY() >= top && w.getY() + w.getHeight() <= bottom) {
                    return w;
                }
                int offset = (Integer) R.getStatic(s.getClass(), "scrollOffset");
                R.setStatic(s.getClass(), "scrollOffset", Math.max(0, offset + (w.getY() - top) - 20));
            }
            return null;
        } catch (Throwable t) {
            throw new AssertionError(UiCase.describe(t), t);
        }
    }

    /** The {@code ordinal}-th row named {@code name}; while it listens its label may not carry the name, so a row
     *  saying "Press any key..." / "Press..." in the same slot counts too. */
    private static AbstractWidget find(Screen s, Row row) {
        int k = 0;
        for (AbstractWidget w : MenuACases.paneRows(s)) {
            String l = ModScreenDriver.label(w);
            if (l.startsWith(row.name() + ": ") || l.equals(row.name() + ":")) {
                if (k++ == row.ordinal()) {
                    return w;
                }
            }
        }
        for (AbstractWidget w : MenuACases.paneRows(s)) {
            if (ModScreenDriver.label(w).startsWith("Press any key")) {
                return w;
            }
        }
        return null;
    }

    private static String labelOf(Screen s, Row row) {
        if (s == null || !s.getClass().getName().endsWith(".gui.ModScreen")) {
            return null;
        }
        AbstractWidget w = find(s, row);
        return w == null ? null : ModScreenDriver.label(w);
    }

    /** GUI coordinates to window pixels, through the menu's Auto Scale factor. */
    private static double[] window(Minecraft mc, Screen s, double gx, double gy) {
        float f = ((Number) Mod.staticCall("hud.AutoScale", "appliedFactor", s)).floatValue();
        var w = mc.getWindow();
        return new double[]{gx * f * w.getScreenWidth() / (double) w.getGuiScaledWidth(),
                gy * f * w.getScreenHeight() / (double) w.getGuiScaledHeight()};
    }

    private static Path configRoot() {
        return FabricLoader.getInstance().getConfigDir().resolve("killer560");
    }

    private static Map<Path, byte[]> snapshot(Path root) throws IOException {
        Map<Path, byte[]> out = new LinkedHashMap<>();
        if (!Files.isDirectory(root)) {
            return out;
        }
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                out.put(p, Files.readAllBytes(p));
            }
        }
        return out;
    }

    /** Files that changed since {@code pre} (or are new) and contain {@code needle}. */
    private static List<String> changedWith(Path root, Map<Path, byte[]> pre, String needle) throws IOException {
        List<String> out = new ArrayList<>();
        for (Map.Entry<Path, byte[]> e : snapshot(root).entrySet()) {
            byte[] old = pre.get(e.getKey());
            if (old != null && Arrays.equals(old, e.getValue())) {
                continue;
            }
            if (new String(e.getValue(), StandardCharsets.UTF_8).contains(needle)) {
                out.add(root.relativize(e.getKey()).toString());
            }
        }
        return out;
    }

    /** Puts every file back as {@code original} had it (new files deleted) and reloads the mod's configs. */
    private static void restore(UiCase c, Path root, Map<Path, byte[]> original) {
        try {
            Map<Path, byte[]> now = snapshot(root);
            boolean changed = false;
            for (Map.Entry<Path, byte[]> e : now.entrySet()) {
                byte[] old = original.get(e.getKey());
                if (old == null) {
                    Files.deleteIfExists(e.getKey());
                    changed = true;
                } else if (!Arrays.equals(old, e.getValue())) {
                    Files.write(e.getKey(), old);
                    changed = true;
                }
            }
            for (Map.Entry<Path, byte[]> e : original.entrySet()) {
                if (!now.containsKey(e.getKey())) {
                    Files.createDirectories(e.getKey().getParent());
                    Files.write(e.getKey(), e.getValue());
                    changed = true;
                }
            }
            if (changed) {
                c.onClient(mc -> {
                    Mod.staticCall("profiles.ProfileManager", "reloadAllConfigs");
                    Mod.staticCall("gui.ModuleKeybinds", "load");
                    return null;
                });
            }
        } catch (Throwable t) {
            c.note("config restore: " + UiCase.describe(t));
        }
    }
}
