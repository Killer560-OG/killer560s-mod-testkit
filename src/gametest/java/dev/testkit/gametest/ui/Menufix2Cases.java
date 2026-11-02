package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Mod menufix2-1102 (killer560, 2026-11-02).
 * <p>527-ui-thorn-triggerbot-fold: "move thorn triggerbot under the thorn stuff already built with that cheat build
 * section". No Thorn Triggerbot card on any jar. On a cheat jar the Thorn tab's drawer has the Thorn Triggerbot switch
 * and its Triggerbot Toggle Keybind row below every legit row and below the red "Cheat Build - Thorn" header; a real click
 * on the switch turns the saved setting on and shows Only When Aimed At Thorn; a bind saved under the old card's id
 * (ThornTriggerbotTab) flips the triggerbot in game; searching "thorn triggerbot" lands on the Thorn tab. On a legit jar
 * none of those rows, no header, no search hit, and the old id's key does nothing.
 * <p>528-ui-split-counting-legit: "this cheat portion should be legit" (mod splitcount-1102): Split Timers' Counting is
 * a normal row above the Core Entry header, with no red header in the tab, on every jar; a real click on it switches
 * Ticks on and the split times round to the 0.05 s tick.
 * <p>Config files and binds are put back byte for byte.
 */
final class Menufix2Cases {

    private Menufix2Cases() {
    }

    private static final String THORN = "thorn.ThornConfig";
    private static final String KEYS = "gui.ModuleKeybinds";
    private static final String OLD_ID = "ThornTriggerbotTab";

    private static String plain(AbstractWidget w) {
        return ModScreenDriver.label(w);
    }

    private static AbstractWidget row(Screen s, String prefix) {
        for (AbstractWidget w : MenuACases.paneRows(s)) {
            if (plain(w).startsWith(prefix)) {
                return w;
            }
        }
        return null;
    }

    private static List<String> rowLabels(Screen s) {
        List<String> out = new ArrayList<>();
        for (AbstractWidget w : MenuACases.paneRows(s)) {
            out.add(plain(w));
        }
        return out;
    }

    private static Screen drawer(Minecraft mc, String tabId) {
        Screen s;
        try {
            s = MenuACases.open(mc);
        } catch (Exception e) {
            throw new AssertionError("could not open gui.ModScreen: " + UiCase.describe(e), e);
        }
        Mod.call(s, "openFeature", MenuACases.leaf(tabId));
        return s;
    }

    private static void clickControl(Screen s, AbstractWidget w) {
        int[] p = (int[]) Mod.call(w, "controlBounds");
        double x = p[0] + p[2] * 0.75;
        double y = p[1] + p[3] / 2.0;
        MouseButtonEvent e = new MouseButtonEvent(x, y, new MouseButtonInfo(dev.testkit.compat.McKeys.mouse(0), 0));
        s.mouseClicked(e, false);
        s.mouseReleased(e);
    }

    private static byte[] snapshot(Path p) throws Exception {
        return p != null && Files.exists(p) ? Files.readAllBytes(p) : null;
    }

    private static void restore(Path p, byte[] bytes) throws Exception {
        if (p == null) {
            return;
        }
        if (bytes == null) {
            Files.deleteIfExists(p);
        } else {
            Files.write(p, bytes);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> hits(Screen s) {
        List<String> out = new ArrayList<>();
        for (Object r : (List<Object>) Mod.call(s, "searchResults")) {
            Object tab = Mod.call(r, "tab");
            out.add(Mod.call(r, "label") + " @ " + MenuACases.name(tab));
        }
        return out;
    }

    // ---- 527 --------------------------------------------------------------------------------------------------------

    static void thornTriggerbotFold(UiCase c) throws Exception {
        boolean cheat = Mod.isCheat();
        Path thornFile = (Path) Mod.field(THORN, "CONFIG_PATH");
        Path keyFile = (Path) Mod.field(KEYS, "CONFIG_PATH");
        byte[] thornBefore = snapshot(thornFile);
        byte[] keysBefore = snapshot(keyFile);
        try {
            // No card of its own, on any jar.
            boolean card = c.onClient(mc -> {
                for (Object t : MenuACases.leaves()) {
                    if (t.getClass().getSimpleName().equals(OLD_ID)) {
                        return true;
                    }
                }
                return false;
            });
            c.check(!card, "the menu still has a Thorn Triggerbot card (" + OLD_ID + ")");

            String why = c.onClient(mc -> {
                Object cfg = Mod.cfg(THORN);
                Mod.call(cfg, "setTriggerbot", false);
                Screen s = drawer(mc, "ThornTab");
                List<String> labels = rowLabels(s);
                c.note((cheat ? "cheat" : "legit") + " Thorn drawer, triggerbot off: " + labels);
                AbstractWidget sw = row(s, "Thorn Triggerbot");
                AbstractWidget keyRow = row(s, "Triggerbot Toggle Keybind");
                AbstractWidget header = row(s, "Cheat Build - Thorn");
                if (!cheat) {
                    if (sw != null || keyRow != null || header != null || row(s, "Only When Aimed") != null) {
                        return "a legit jar shows a cheat Thorn row: " + labels;
                    }
                    McCompat.setScreen(mc, null);
                    return null;
                }
                if (sw == null || keyRow == null || header == null) {
                    return "a cheat jar is missing the Thorn Triggerbot switch, its keybind row or the cheat header: " + labels;
                }
                if (row(s, "Only When Aimed At Thorn") != null) {
                    return "Only When Aimed At Thorn shows with the triggerbot off: " + labels;
                }
                // Below every legit row: the legit rows are all above the red header.
                AbstractWidget lastLegit = row(s, "Remove Last Stun Spot");
                if (lastLegit == null || !(lastLegit.getY() < header.getY() && header.getY() < sw.getY()
                        && sw.getY() < keyRow.getY())) {
                    return "order wrong: Remove Last Stun Spot y=" + (lastLegit == null ? "?" : lastLegit.getY())
                            + ", header y=" + header.getY() + ", switch y=" + sw.getY() + ", key row y=" + keyRow.getY();
                }
                // The card's own Toggle Keybind row is still the drawer's last row.
                AbstractWidget cardKey = row(s, "Toggle Keybind");
                if (cardKey == null || cardKey.getY() <= keyRow.getY()) {
                    return "the card's Toggle Keybind row is not below the triggerbot's: " + labels;
                }
                // A real click on the switch.
                clickControl(s, sw);
                if (!(Boolean) Mod.call(Mod.cfg(THORN), "isTriggerbotRaw")) {
                    return "a click on the Thorn Triggerbot switch left it off";
                }
                List<String> after = rowLabels(s);
                if (row(s, "Only When Aimed At Thorn") == null) {
                    return "Only When Aimed At Thorn did not appear with the triggerbot on: " + after;
                }
                c.note("after a click on the switch: " + after);
                McCompat.setScreen(mc, null);
                return null;
            });
            c.check(why == null, why);

            // The saved setting is the old key.
            Mod.staticCall(THORN, "load");
            boolean saved = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(THORN), "isTriggerbotRaw"));
            if (cheat) {
                c.check(saved, "the switch's click was not saved (ThornConfig reload reads it off)");
                String text = new String(Files.readAllBytes(thornFile), java.nio.charset.StandardCharsets.UTF_8);
                c.check(text.contains("\"triggerbot\": true"), "the saved file does not carry \"triggerbot\": true");
            }

            // A key bound under the old card's id, pressed in game.
            int code = 75; // K
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                Mod.staticCall(KEYS, "set", OLD_ID, code);
                return null;
            });
            boolean before = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(THORN), "isTriggerbotRaw"));
            int pressesBefore = c.onClient(mc -> (Integer) Mod.staticCall(KEYS, "toggles"));
            Mod.setField(KEYS, "heldForTest", Integer.valueOf(code));
            c.ticks(4);
            Mod.setField(KEYS, "heldForTest", null);
            c.ticks(2);
            boolean afterKey = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(THORN), "isTriggerbotRaw"));
            int pressesAfter = c.onClient(mc -> (Integer) Mod.staticCall(KEYS, "toggles"));
            c.note("key under " + OLD_ID + ": triggerbot " + before + " -> " + afterKey + ", presses "
                    + pressesBefore + " -> " + pressesAfter);
            if (cheat) {
                c.check(afterKey != before && pressesAfter == pressesBefore + 1,
                        "the Triggerbot Toggle Keybind did not flip the triggerbot once");
            } else {
                c.check(afterKey == before && pressesAfter == pressesBefore,
                        "a legit jar acted on the old Thorn Triggerbot key");
            }

            // Search.
            List<String> found = c.onClient(mc -> {
                Screen s;
                try {
                    s = MenuACases.open(mc);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
                Mod.call(Mod.field(s, "searchField"), "setValue", "thorn triggerbot");
                List<String> h = hits(s);
                McCompat.setScreen(mc, null);
                return h;
            });
            c.note("search 'thorn triggerbot': " + found);
            boolean onThorn = found.stream().anyMatch(h -> h.startsWith("Thorn Triggerbot") && h.endsWith("@ Thorn (F4)"));
            if (cheat) {
                c.check(onThorn, "searching thorn triggerbot does not land on the Thorn tab's row: " + found);
            } else {
                c.check(found.stream().noneMatch(h -> h.startsWith("Thorn Triggerbot")),
                        "a legit jar's search finds a Thorn Triggerbot row: " + found);
            }
        } finally {
            restore(thornFile, thornBefore);
            restore(keyFile, keysBefore);
            Mod.setField(KEYS, "heldForTest", null);
            c.onClient(mc -> {
                Mod.staticCall(THORN, "load");
                Mod.staticCall(KEYS, "load");
                McCompat.setScreen(mc, null);
                return null;
            });
        }
    }

    // ---- 529 --------------------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static List<String> sections(Screen s) {
        return (List<String>) Mod.call(s, "sideSections");
    }

    private static String current(Screen s) {
        return (String) Mod.call(s, "currentSectionLabel");
    }

    private static int[] grid(Screen s) {
        return (int[]) Mod.call(s, "gridScrollState");
    }

    /** Wheel over the grid until it stops moving (down when {@code down}). */
    private static void wheel(Screen s, boolean down) {
        int gx = (Integer) Mod.field(s, "gridX");
        int gy = (Integer) Mod.field(s, "gridY");
        int gw = (Integer) Mod.field(s, "gridW");
        int gh = (Integer) Mod.field(s, "gridH");
        for (int i = 0; i < 400; i++) {
            int before = grid(s)[0];
            s.mouseScrolled(gx + gw / 2.0, gy + gh / 2.0, 0, down ? -1 : 1);
            if (grid(s)[0] == before) {
                return;
            }
        }
    }

    private static int dist(int a, int b) {
        return Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF)) + Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF))
                + Math.abs((a & 0xFF) - (b & 0xFF));
    }

    /**
     * killer560, 2026-11-02: "as you can see i am clearly in the secret area of the left tab yet it doesnt show i am", and
     * "dont have it be orange have it fit the red theme but be different". Every category with sections: at the top of
     * the grid the first section is current, wheeled to the end the last one is; pressing each section scrolls there and
     * marks it. In Dungeons at the end (Cheat &gt; Secrets on a cheat jar) the marker bar is drawn, and in every menu
     * theme its pixels are the red variant, not the accent.
     */
    static void sidebarScrollSpy(UiCase c) throws Exception {
        boolean cheat = Mod.isCheat();
        Object menuCfg = Mod.cfg("gui.MenuConfig");
        Object themeWas = Mod.call(menuCfg, "getTheme");
        try {
            List<String> problems = c.onClient(mc -> {
                List<String> out = new ArrayList<>();
                Screen s;
                try {
                    s = MenuACases.open(mc);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
                int withSections = 0;
                for (String cat : new String[]{"Dungeons", "HUD", "Chat", "Items", "Social", "Cosmetics", "Misc"}) {
                    if (!(Boolean) Mod.call(s, "selectCategory", cat)) {
                        continue;
                    }
                    List<String> secs = sections(s);
                    if (secs.isEmpty() || grid(s)[1] <= 0) {
                        c.note(cat + ": sections " + secs + ", grid max " + grid(s)[1] + " (nothing to spy)");
                        continue;
                    }
                    withSections++;
                    wheel(s, false);
                    String top = current(s);
                    wheel(s, true);
                    String bottom = current(s);
                    int[] g = grid(s);
                    c.note(cat + ": sections " + secs + "; top -> " + top + ", end (" + g[0] + "/" + g[1] + ") -> " + bottom);
                    if (!secs.get(0).equals(top)) {
                        out.add(cat + ": at the top '" + top + "' is marked, not '" + secs.get(0) + "'");
                    }
                    if (g[0] != g[1] || !secs.get(secs.size() - 1).equals(bottom)) {
                        out.add(cat + ": at the end (" + g[0] + "/" + g[1] + ") '" + bottom + "' is marked, not '"
                                + secs.get(secs.size() - 1) + "'");
                    }
                    // Each section pressed is the one marked (the first of a repeated name: pressSection finds that one).
                    List<String> seen = new ArrayList<>();
                    for (String sec : secs) {
                        if (seen.contains(sec)) {
                            continue;
                        }
                        seen.add(sec);
                        Mod.call(s, "pressSection", sec);
                        String now = current(s);
                        if (!sec.equals(now)) {
                            out.add(cat + ": pressing '" + sec + "' marks '" + now + "'");
                        }
                    }
                }
                if (withSections == 0) {
                    out.add("no category had sections and a scrolling grid - the case measured nothing");
                }
                McCompat.setScreen(mc, null);
                return out;
            });
            c.check(problems.isEmpty(), problems.size() + " problem(s): " + String.join(" | ", problems));

            // Dungeons at the end, in every theme: the marker is drawn and red on a cheat jar (Cheat > Secrets).
            String[] themes = {"AMBER", "DARK", "LIGHT", "OCEAN", "GREEN", "CUSTOM"};
            for (String th : themes) {
                Object theme = Mod.enumValue("gui.MenuTheme", th);
                Object[] st = c.onClient(mc -> {
                    Mod.call(menuCfg, "setTheme", theme);
                    Screen s;
                    try {
                        s = MenuACases.open(mc);
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                    Mod.call(s, "selectCategory", "Dungeons");
                    wheel(s, true);
                    List<String> secs = sections(s);
                    return new Object[]{current(s), secs};
                });
                c.ticks(5);
                Object[] bar = c.onClient(mc -> {
                    Screen s = McCompat.screen(mc);
                    wheel(s, true);
                    int[] b = (int[]) Mod.call(s, "currentSectionBar");
                    int or = (Integer) Mod.field("gui.MenuStyle", "OR");
                    int red = (Integer) Mod.field("gui.MenuStyle", "RED_TITLE");
                    return new Object[]{b, or, red, mc.getWindow().getGuiScale(), current(s)};
                });
                Path shot = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(c.name() + "-dungeons-end-" + th.toLowerCase()));
                dev.testkit.harness.Report.screenshot(c.name(), shot);
                int[] b = (int[]) bar[0];
                int or = (Integer) bar[1];
                int red = (Integer) bar[2];
                double scale = ((Number) bar[3]).doubleValue();
                c.check(b != null, th + ": no section marker drawn at the end of Dungeons");
                if (b == null) {
                    continue;
                }
                java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(shot.toFile());
                int px = (int) Math.floor((b[0] + 0.5) * scale);
                int py = (int) Math.floor((b[1] + b[3] / 2.0) * scale);
                int pixel = img.getRGB(Math.min(px, img.getWidth() - 1), Math.min(py, img.getHeight() - 1)) & 0xFFFFFF;
                c.note(th + ": marked '" + bar[4] + "' (sections " + st[1] + "), bar " + java.util.Arrays.toString(b)
                        + " scale " + scale + ", pixel #" + Integer.toHexString(pixel) + " (red #"
                        + Integer.toHexString(red & 0xFFFFFF) + ", accent #" + Integer.toHexString(or & 0xFFFFFF) + "); " + shot);
                if (cheat) {
                    c.check("Secrets".equals(bar[4]), th + ": at the end of Dungeons '" + bar[4] + "' is marked, not Cheat > Secrets");
                    c.check(b[4] == red, th + ": the cheat marker is not the red variant: #" + Integer.toHexString(b[4]));
                    c.check(dist(pixel, red) <= 30 && dist(pixel, or) > 60, th + ": the drawn marker pixel #"
                            + Integer.toHexString(pixel) + " is not the red #" + Integer.toHexString(red & 0xFFFFFF)
                            + " (accent #" + Integer.toHexString(or & 0xFFFFFF) + ")");
                } else {
                    c.check(b[4] == or && dist(pixel, or) <= 30, th + ": a legit jar's marker pixel #"
                            + Integer.toHexString(pixel) + " is not the accent #" + Integer.toHexString(or & 0xFFFFFF));
                }
            }
        } finally {
            c.onClient(mc -> {
                Mod.call(menuCfg, "setTheme", themeWas);
                McCompat.setScreen(mc, null);
                return null;
            });
        }
    }

    // ---- 528 --------------------------------------------------------------------------------------------------------

    static void splitCountingLegit(UiCase c) throws Exception {
        String cls = "splittimers.SplitTimersConfig";
        Path file = (Path) Mod.field(cls, "CONFIG_PATH");
        byte[] before = snapshot(file);
        try {
            String why = c.onClient(mc -> {
                Mod.call(Mod.cfg(cls), "setTickCounting", false);
                Screen s = drawer(mc, "SplitTimersTab");
                List<String> labels = rowLabels(s);
                c.note((Mod.isCheat() ? "cheat" : "legit") + " Split Timers drawer: " + labels);
                AbstractWidget counting = row(s, "Counting");
                AbstractWidget core = row(s, "Core Entry (F7/M7)");
                if (counting == null || core == null) {
                    return "no Counting row or Core Entry header: " + labels;
                }
                if (counting.getY() >= core.getY()) {
                    return "Counting (y " + counting.getY() + ") is not above Core Entry (y " + core.getY() + ")";
                }
                for (String l : labels) {
                    if (l.equals("Cheat") || l.startsWith("Cheat Build")) {
                        return "Split Timers still has a cheat header: " + labels;
                    }
                }
                String a = (String) Mod.staticCall("splittimers.SplitTimersFeature", "formatTime", 12_324L);
                clickControl(s, counting);
                AbstractWidget now = row(s, "Counting");
                String label = now == null ? "?" : plain(now);
                boolean on = (Boolean) Mod.call(Mod.cfg(cls), "isTickCounting");
                String t = (String) Mod.staticCall("splittimers.SplitTimersFeature", "formatTime", 12_324L);
                c.note("12324 ms: " + a + " -> after a click on Counting (" + label + ", isTickCounting " + on + ") " + t);
                McCompat.setScreen(mc, null);
                if (!on || !label.contains("Ticks")) {
                    return "a click on Counting did not switch Ticks on: " + label + ", isTickCounting " + on;
                }
                if (!"12.32s".equals(a) || !"12.30s".equals(t)) {
                    return "tick rounding: " + a + " -> " + t + ", wanted 12.32s -> 12.30s";
                }
                return null;
            });
            c.check(why == null, why);
        } finally {
            restore(file, before);
            c.onClient(mc -> {
                Mod.staticCall(cls, "load");
                McCompat.setScreen(mc, null);
                return null;
            });
        }
    }
}
