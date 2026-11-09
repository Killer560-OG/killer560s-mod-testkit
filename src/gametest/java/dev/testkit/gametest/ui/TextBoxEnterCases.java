package dev.testkit.gametest.ui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 1101-ui-textbox-enter (mod textbox-1101, killer560 2026-11-01: "any text box I type into if i press enter should save
 * that value. Wether it has a set button or not. If it doesnt have a set button add it as well.").
 *
 * <p>Walks every feature drawer of the real mod menu twice - as it opens, then with every allowed OFF switch turned on
 * (so boxes that only show for an enabled feature are reached) - and every mod screen that can be opened without a
 * network or account side effect. For each text box: it must be the mod's {@code gui.CommitEditBox} with a commit action
 * (a search box's live filter is the one allowed exception, the owner's rule), and in the menu it must have a Set-type
 * button. Then the case focuses it, TYPES through the real keyboard ({@code typeChars}), presses the real Enter key, and
 * asserts that Enter ran the box's commit (the mod-wide count went up, the box let go of the cursor) and that the value
 * is SAVED: the typed text is in a file under {@code config/killer560} (its content or its name), or for a number, a file
 * there changed. Every config file is put back as it was afterwards and the configs reloaded, so the UI cases after it
 * see the menu they expect.
 */
final class TextBoxEnterCases {

    private static final String COMMIT = "com.killer560.hub.gui.CommitEditBox";
    private static final Pattern NUMBER = Pattern.compile("^-?[0-9][0-9,]*(\\.[0-9]+)?$");
    /** Search boxes and live filters: left as they were on purpose (owner, 2026-11-01). Matched on the box's label. */
    private static final Set<String> SEARCH = Set.of("search", "search zones");
    /** Boxes whose saved value is a number though they open empty: what to type. */
    private static final Map<String, String> TYPE = Map.of("target purse", "1500000", "custom character", "~");
    /** Switches the walk never turns on: Streamer Mode keeps its state in memory (a config reload does not reset it) and
     *  hides every cheat tab from the search, which failed 944 after this case on the first run (2026-11-02). */
    // AP3's Test Mode and Force Dungeon are session state, not a config file, so the disk restore never turns them back off:
    // left on, the red "AP3 FORCE DUNGEON" reminder drew over 897's panel bevel in a later case (ship-1102h, 2026-11-02).
    private static final List<String> NEVER_SWITCH = List.of("streamer", "test mode", "force dungeon");

    private TextBoxEnterCases() {
    }

    private record Found(String key, String label, int index, boolean commits, boolean commitClass, boolean hasSetButton) {
    }

    static void enterSaves(UiCase c, Deny deny) throws Exception {
        Path root = c.onClient(mc -> (Path) Mod.staticCall("util.ModPaths", "root"));
        Map<Path, byte[]> before = snapshot(root);
        boolean streamerBefore = streamer(c);
        int[] stats = new int[4]; // 0 boxes seen, 1 tested and saved, 2 search boxes left alone, 3 screens walked
        try (AutoCloseable all = MenuLayoutCases.allTested(c)) {
            menu(c, deny, root, stats);
            screens(c, root, stats);
        } finally {
            c.onClient((Minecraft mc) -> {
                McCompat.setScreen(mc, null);
                return null;
            });
            restore(root, before);
            c.onClient(mc -> {
                try {
                    Mod.staticCall("profiles.ProfileManager", "reloadAllConfigs");
                } catch (Throwable t) {
                    c.note("reloadAllConfigs after the walk: " + UiCase.describe(t));
                }
                return null;
            });
        }
        c.check(streamer(c) == streamerBefore, "the walk left Streamer Mode " + (streamerBefore ? "off" : "on"));
        c.note("text boxes seen " + stats[0] + ", typed + Enter + saved on disk " + stats[1] + ", search boxes left alone "
                + stats[2] + ", screens walked " + stats[3]);
        // The case must have acted: dozens of boxes are reachable in either jar.
        c.check(stats[1] >= 20, "only " + stats[1] + " text boxes were typed into and saved with Enter (expected 20+)");
        c.check(stats[3] >= 2, "only " + stats[3] + " mod screens were walked");
    }

    // ---- the menu ------------------------------------------------------------------------------------------------

    private static void menu(UiCase c, Deny deny, Path root, int[] stats) throws Exception {
        ModScreenDriver d = c.onClient(mc -> {
            try {
                R.setStatic(R.cls(ModScreenDriver.MOD_SCREEN), "tabs", null);
                ModScreenDriver dd = new ModScreenDriver(mc);
                McCompat.setScreen(mc, dd.screen);
                return dd;
            } catch (Throwable t) {
                throw new AssertionError("could not open gui.ModScreen: " + UiCase.describe(t), t);
            }
        });
        c.ticks(3);
        List<Object> leaves = c.onClient(mc -> d.leaves());
        Set<String> done = new LinkedHashSet<>();
        int n = 0;
        for (boolean switched : new boolean[]{false, true}) {
            for (Object leaf : leaves) {
                String tab = (String) Mod.field(leaf, "name");
                List<Found> boxes;
                try {
                    boxes = c.onClient(mc -> unchecked(() -> {
                        layout(d, leaf, switched, deny);
                        return describe(d, tab);
                    }));
                } catch (Throwable t) {
                    c.note(tab + (switched ? " (switched on)" : "") + ": could not lay out: " + UiCase.describe(t));
                    continue;
                }
                for (Found f : boxes) {
                    if (!done.add(f.key())) {
                        continue;
                    }
                    stats[0]++;
                    if (SEARCH.contains(f.label().toLowerCase(Locale.ROOT))) {
                        stats[2]++;
                        c.check(!f.commits(), f.key() + ": a search box commits on Enter (it was to be left alone)");
                        continue;
                    }
                    if (!f.commitClass() || !f.commits()) {
                        c.problem(f.key() + ": Enter does not save (" + (f.commitClass() ? "no commit action" : "a plain EditBox") + ")");
                        continue;
                    }
                    c.check(f.hasSetButton(), f.key() + ": no Set / Save / Add button beside it");
                    String typed = typedFor(f.label(), c.onClient(mc -> unchecked(() -> {
                        layout(d, leaf, switched, deny);
                        return box(d, f).getValue();
                    })), ++n);
                    if (enter(c, root, f.key(), typed, () -> {
                        layout(d, leaf, switched, deny);
                        EditBox b = box(d, f);
                        Object pane = R.get(d.screen, "contentPane");
                        d.screen.setFocused((GuiEventListener) pane);
                        ((ContainerEventHandler) pane).setFocused(b);
                        return b;
                    })) {
                        stats[1]++;
                    }
                }
            }
        }
        c.onClient((Minecraft mc) -> {
            McCompat.setScreen(mc, null);
            d.select(0);
            return null;
        });
    }

    /** Opens {@code leaf}'s drawer with every section open; {@code switched} also turns on each allowed OFF switch. */
    private static void layout(ModScreenDriver d, Object leaf, boolean switched, Deny deny) throws Throwable {
        d.open(leaf);
        d.rebuild();
        d.openCollapsibles(deny, new ArrayList<>());
        if (!switched) {
            return;
        }
        Set<String> pressed = new LinkedHashSet<>();
        for (int round = 0; round < 30; round++) {
            AbstractWidget next = null;
            for (AbstractWidget w : d.content()) {
                String l = ModScreenDriver.label(w);
                if (d.isSettingsButton(w) && Boolean.TRUE.equals(Mod.call(w, "isSwitch")) && !Boolean.TRUE.equals(Mod.call(w, "isOn"))
                        && !pressed.contains(l) && deny.button(l) == null
                        && NEVER_SWITCH.stream().noneMatch(x -> l.toLowerCase(Locale.ROOT).contains(x))) {
                    next = w;
                    break;
                }
            }
            if (next == null) {
                break;
            }
            pressed.add(ModScreenDriver.label(next));
            ModScreenDriver.press(next);
            d.rebuild();
            d.openCollapsibles(deny, new ArrayList<>());
        }
    }

    private static List<Found> describe(ModScreenDriver d, String tab) {
        List<AbstractWidget> content = d.content();
        List<Found> out = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        for (AbstractWidget w : content) {
            if (!(w instanceof EditBox b)) {
                continue;
            }
            String label = ModScreenDriver.label(b);
            int index = seen.merge(label, 1, Integer::sum) - 1;
            boolean commitClass = isCommit(b);
            boolean commits = commitClass && Boolean.TRUE.equals(Mod.call(b, "commits"));
            out.add(new Found(tab + " / " + label + (index > 0 ? " #" + (index + 1) : ""), label, index, commits, commitClass,
                    hasButtonBeside(b, content)));
        }
        return out;
    }

    /** A button on the box's row, right of it, or right under it (the Profiles card puts Save below a narrow box). */
    private static boolean hasButtonBeside(EditBox b, List<AbstractWidget> content) {
        int[] r = drawn(b);
        for (AbstractWidget w : content) {
            if (w == b || w instanceof EditBox) {
                continue;
            }
            boolean sameRow = w.getY() < r[1] + r[3] && w.getY() + w.getHeight() > r[1] && w.getX() >= r[0] + r[2] - 1;
            boolean below = w.getY() >= r[1] + r[3] && w.getY() <= r[1] + r[3] + 26 && w.getX() < r[0] + r[2] && w.getX() + w.getWidth() > r[0];
            if (sameRow || below) {
                return true;
            }
        }
        return false;
    }

    private static int[] drawn(EditBox b) {
        if (isCommit(b)) {
            return (int[]) Mod.call(b, "drawnBox");
        }
        return new int[]{b.getX(), b.getY(), b.getWidth(), b.getHeight()};
    }

    private static boolean isCommit(Object o) {
        for (Class<?> k = o.getClass(); k != null; k = k.getSuperclass()) {
            if (k.getName().equals(COMMIT)) {
                return true;
            }
        }
        return false;
    }

    private static EditBox box(ModScreenDriver d, Found f) {
        int i = 0;
        for (AbstractWidget w : d.content()) {
            if (w instanceof EditBox b && ModScreenDriver.label(b).equals(f.label())) {
                if (i++ == f.index()) {
                    return b;
                }
            }
        }
        throw new AssertionError(f.key() + ": the box is gone after reopening its drawer");
    }

    // ---- screens -------------------------------------------------------------------------------------------------

    /**
     * Mod screens with text boxes that open with no network or account side effect. Left out on purpose: the proxy and
     * account screens (Apply / Connect change how the client connects), Party Finder (List Party goes to the relay),
     * Friends (Add sends a friend request), the AP3 and Auto Routes node editors (need a node; Enter is their Save, the
     * same method 387 drives), and the color picker, which {@link #picker} drives on its own.
     */
    private static final String[] SCREENS = {"autosell.AutoSellListScreen", "gui.CapeDesignerScreen",
            "invsort.InventorySorterScreen", "dungeonextras.BreakerAuraConfigScreen", "ap3.Ap3ConfigScreen"};

    private static void screens(UiCase c, Path root, int[] stats) throws Exception {
        int n = 100;
        for (String cls : SCREENS) {
            if (!Mod.has(cls)) {
                c.note(cls + ": not in this jar");
                continue;
            }
            // The sorter saves nothing for an empty grid (its own Save rule): give it one item to save.
            net.minecraft.world.item.ItemStack[] slot0 = {null};
            if (cls.endsWith("InventorySorterScreen")) {
                c.onClient(mc -> {
                    slot0[0] = mc.player.getInventory().getItem(0).copy();
                    mc.player.getInventory().setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND));
                    return null;
                });
            }
            try {
                screenBoxes(c, root, stats, cls, n);
                n += 10;
            } finally {
                if (slot0[0] != null) {
                    c.onClient(mc -> {
                        mc.player.getInventory().setItem(0, slot0[0]);
                        return null;
                    });
                }
            }
        }
        picker(c, stats);
    }

    private static void screenBoxes(UiCase c, Path root, int[] stats, String cls, int n0) throws Exception {
        int n = n0;
        {
            List<Found> boxes;
            try {
                boxes = c.onClient(mc -> unchecked(() -> {
                    Screen s = open(mc, cls);
                    List<Found> out = new ArrayList<>();
                    int i = 0;
                    for (GuiEventListener l : s.children()) {
                        if (l instanceof EditBox b) {
                            boolean commitClass = isCommit(b);
                            out.add(new Found(cls + " / " + ModScreenDriver.label(b), ModScreenDriver.label(b), i++,
                                    commitClass && Boolean.TRUE.equals(Mod.call(b, "commits")), commitClass, true));
                        }
                    }
                    return out;
                }));
            } catch (Throwable t) {
                c.note(cls + ": could not open: " + UiCase.describe(t));
                return;
            }
            stats[3]++;
            for (Found f : boxes) {
                stats[0]++;
                if (SEARCH.contains(f.label().toLowerCase(Locale.ROOT))) {
                    stats[2]++;
                    continue;
                }
                if (!f.commitClass() || !f.commits()) {
                    c.problem(f.key() + ": Enter does not save");
                    continue;
                }
                String typed = cls.endsWith("AutoSellListScreen") ? "ENCHANTED_COAL" : typedFor(f.label(), "", ++n);
                if (enter(c, root, f.key(), typed, () -> {
                    Screen s = open(Minecraft.getInstance(), cls);
                    int i = 0;
                    for (GuiEventListener l : s.children()) {
                        if (l instanceof EditBox b && i++ == f.index()) {
                            s.setFocused(b);
                            return b;
                        }
                    }
                    throw new AssertionError(f.key() + ": gone after reopening");
                })) {
                    stats[1]++;
                }
            }
        }
    }

    private static Screen open(Minecraft mc, String cls) throws Exception {
        Screen s = (Screen) Mod.cls(cls).getConstructor(Screen.class).newInstance((Object) null);
        McCompat.setScreen(mc, s);
        return s;
    }

    /** The color picker: Enter in the hex box applies the typed color (its onChange gets it) and closes, as Done does. */
    private static void picker(UiCase c, int[] stats) {
        int[] got = {0};
        boolean[] called = {false};
        EditBox hex = c.onClient(mc -> unchecked(() -> {
            IntConsumer onChange = argb -> {
                got[0] = argb;
                called[0] = true;
            };
            Screen s = (Screen) Mod.cls("gui.ColorPickerScreen")
                    .getConstructor(Screen.class, String.class, int.class, int.class, IntConsumer.class)
                    .newInstance(null, "Textbox Enter", 0xFF112233, 0xFF112233, onChange);
            McCompat.setScreen(mc, s);
            return null;
        }));
        c.ticks(3);
        hex = c.onClient(mc -> {
            Screen s = McCompat.screen(mc);
            EditBox b = (EditBox) R.get(s, "hexBox");
            b.setValue("");
            s.setFocused(b);
            b.setFocused(true);
            return b;
        });
        stats[3]++;
        stats[0]++;
        called[0] = false;
        c.ctx().getInput().typeChars("#33CC66");
        c.ticks(2);
        called[0] = false;
        int commitsBefore = commitCount(c);
        c.ctx().getInput().pressKey(InputConstants.KEY_RETURN);
        c.ticks(3);
        boolean closed = c.onClient(mc -> !Mod.cls("gui.ColorPickerScreen").isInstance(McCompat.screen(mc)));
        c.note("color picker: Enter -> onChange " + (called[0] ? String.format("#%08X", got[0]) : "not called")
                + ", closed " + closed + ", commits +" + (commitCount(c) - commitsBefore));
        c.check(commitCount(c) == commitsBefore + 1, "color picker: Enter in the hex box ran no commit");
        c.check(closed, "color picker: Enter did not close it like Done");
        c.check(called[0] && (got[0] & 0xFFFFFF) == 0x33CC66, "color picker: Enter did not apply #33CC66");
        if (called[0] && closed) {
            stats[1]++;
        }
    }

    // ---- type + Enter + disk -------------------------------------------------------------------------------------

    private interface Opener {
        EditBox open() throws Throwable;
    }

    /** Opens and focuses the box, types {@code typed} with the real keyboard, presses the real Enter, checks the save. */
    private static boolean enter(UiCase c, Path root, String key, String typed, Opener opener) throws Exception {
        try {
            c.onClient(mc -> unchecked(() -> {
                EditBox b = opener.open();
                b.setFocused(true);
                b.moveCursorToEnd(false);
                if (NUMBER.matcher(typed).matches()) {
                    // A number replaces the box's value: all but its last digit set directly, the last one typed.
                    b.setValue(typed.substring(0, typed.length() - 1));
                }
                return null;
            }));
        } catch (Throwable t) {
            c.problem(key + ": could not open and focus: " + UiCase.describe(t));
            return false;
        }
        c.ticks(1);
        Map<Path, byte[]> beforeTyping = snapshot(root);
        c.ctx().getInput().typeChars(NUMBER.matcher(typed).matches() ? typed.substring(typed.length() - 1) : typed);
        c.ticks(2);
        int commitsBefore = commitCount(c);
        Map<Path, byte[]> beforeEnter = snapshot(root);
        c.ctx().getInput().pressKey(InputConstants.KEY_RETURN);
        c.ticks(3);
        int commitsAfter = commitCount(c);
        Map<Path, byte[]> after = snapshot(root);
        boolean number = NUMBER.matcher(typed).matches();
        String token = typed.toLowerCase(Locale.ROOT);
        Path where = number ? changedContaining(beforeTyping, after, typed) : containing(root, after, token);
        boolean enterWrote = where != null && !Arrays.equals(beforeEnter.get(where), after.get(where));
        boolean ok = commitsAfter == commitsBefore + 1 && where != null;
        String line = key + ": typed \"" + typed + "\", Enter -> commits +" + (commitsAfter - commitsBefore) + ", "
                + (where == null ? "NOT on disk" : (number ? "changed " : "in ") + root.relativize(where))
                + (enterWrote ? " (Enter wrote it)" : " (already saved as typed)");
        if (ok) {
            c.note(line);
        } else {
            c.problem(line);
        }
        return ok;
    }

    private static boolean streamer(UiCase c) {
        return c.onClient(mc -> Mod.has("streamer.StreamerMode") && (Boolean) Mod.staticCall("streamer.StreamerMode", "active"));
    }

    private static int commitCount(UiCase c) {
        return c.onClient(mc -> (Integer) Mod.staticCall("gui.CommitEditBox", "commitCount"));
    }

    private static String typedFor(String label, String current, int n) {
        String l = label.toLowerCase(Locale.ROOT);
        if (TYPE.containsKey(l)) {
            return TYPE.get(l);
        }
        String v = current == null ? "" : current.trim();
        if (NUMBER.matcher(v).matches()) {
            double x = Double.parseDouble(v.replace(",", ""));
            double y = x > 2 ? x - 1 : x + 1;
            if (v.contains(".")) {
                int places = v.length() - v.indexOf('.') - 1;
                return String.format(Locale.US, "%." + places + "f", y);
            }
            return String.valueOf((long) y);
        }
        return "tbq" + (char) ('a' + n % 26) + (char) ('a' + (n / 26) % 26);
    }

    // ---- config files --------------------------------------------------------------------------------------------

    private static Map<Path, byte[]> snapshot(Path root) throws IOException {
        Map<Path, byte[]> out = new LinkedHashMap<>();
        if (!Files.isDirectory(root)) {
            return out;
        }
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (Files.isRegularFile(p)) {
                    try {
                        out.put(p, Files.readAllBytes(p));
                    } catch (IOException ignored) {
                        // a file being rewritten right now; the next snapshot reads it
                    }
                }
            }
        }
        return out;
    }

    private static Path changed(Map<Path, byte[]> a, Map<Path, byte[]> b) {
        for (Map.Entry<Path, byte[]> e : b.entrySet()) {
            byte[] old = a.get(e.getKey());
            if (old == null || !Arrays.equals(old, e.getValue())) {
                return e.getKey();
            }
        }
        return null;
    }

    /** A changed settings file (logs are not settings: the stutter log changes on its own) holding the typed number. */
    private static Path changedContaining(Map<Path, byte[]> a, Map<Path, byte[]> b, String number) {
        for (Map.Entry<Path, byte[]> e : b.entrySet()) {
            byte[] old = a.get(e.getKey());
            if (e.getKey().getFileName().toString().endsWith(".log") || (old != null && Arrays.equals(old, e.getValue()))) {
                continue;
            }
            String text = new String(e.getValue(), StandardCharsets.UTF_8);
            // "2.00" is saved as 2.0 (a float), so the short forms count too
            String shortForm = number.contains(".") ? number.replaceAll("0+$", "") : number;
            for (String form : new String[]{number, shortForm + "0", shortForm.endsWith(".") ? shortForm.substring(0, shortForm.length() - 1) : shortForm}) {
                if (!form.isEmpty() && text.contains(form)) {
                    return e.getKey();
                }
            }
        }
        return null;
    }

    /** A file whose path under {@code root} (a profile is a folder named after it) or content holds the token. */
    private static Path containing(Path root, Map<Path, byte[]> files, String token) {
        for (Map.Entry<Path, byte[]> e : files.entrySet()) {
            if (root.relativize(e.getKey()).toString().toLowerCase(Locale.ROOT).contains(token)
                    || new String(e.getValue(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT).contains(token)) {
                return e.getKey();
            }
        }
        return null;
    }

    private static void restore(Path root, Map<Path, byte[]> before) throws IOException {
        Map<Path, byte[]> now = snapshot(root);
        for (Path p : now.keySet()) {
            if (!before.containsKey(p)) {
                Files.deleteIfExists(p);
            }
        }
        for (Map.Entry<Path, byte[]> e : before.entrySet()) {
            byte[] cur = now.get(e.getKey());
            if (cur == null || !Arrays.equals(cur, e.getValue())) {
                Files.createDirectories(e.getKey().getParent());
                Files.write(e.getKey(), e.getValue());
            }
        }
    }

    private interface Body<T> {
        T run() throws Throwable;
    }

    private static <T> T unchecked(Body<T> body) {
        try {
            return body.run();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }
}
