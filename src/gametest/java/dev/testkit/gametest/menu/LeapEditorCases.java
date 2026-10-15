package dev.testkit.gametest.menu;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * killer560's 2026-10-12 Leap Order editor changes (mod branch leap-4): "have a button in this menu titled reset classes and
 * a button titled reset positions. also have it auto refresh party every time i open this menu. Remove the text about
 * moving them as well. and remove the set classes button up top". In the menu session because the party refresh is a
 * real /pl the Hx server records.
 * <ul>
 *   <li>899: Reset Classes clears the class picks of the party (all four) and nothing else: a pick on someone outside the
 *       party and every pin stay.</li>
 *   <li>900: Reset Positions clears the pins of the class being edited and nothing else: class picks and another class's
 *       pins stay.</li>
 *   <li>901: opening the editor sends one /pl; reopening within the rate limit and a resize while open send none; once the
 *       limit has passed, an opening sends one again; inside a dungeon none.</li>
 *   <li>902: no Set Classes / Refresh Party / Reset to Default buttons, the hint line is not in the screen's code any more,
 *       and the bottom row is Back, Reset Classes, Reset Positions, Done - one height, one width, even gaps; a GUI 2
 *       picture.</li>
 * </ul>
 */
final class LeapEditorCases {

    private static final String LEAPCFG = "leapmenu.LeapMenuConfig";
    private static final String OVR = "dungeonclass.ClassOverrides";
    private static final String SCREEN = "leapmenu.LeapOrderScreen";
    static final List<String> PARTY = List.of("LoAnna", "LoBen", "LoCara", "LoDev");
    static final Map<String, String> PICKS = Map.of("LoAnna", "MAGE", "LoBen", "TANK", "LoCara", "HEALER", "LoDev",
            "BERSERKER");

    private LeapEditorCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "899-menu-leap-reset-classes", LeapEditorCases::resetClasses);
        MenuSuite.test(s, "900-menu-leap-reset-positions", LeapEditorCases::resetPositions);
        MenuSuite.test(s, "901-menu-leap-auto-refresh", LeapEditorCases::autoRefresh);
        MenuSuite.test(s, "902-menu-leap-editor-cleanup", LeapEditorCases::cleanup);
    }

    // ---- fixture ------------------------------------------------------------------------------------------------------

    static final class Saved {
        Path leap;
        byte[] leapBytes;
        Path ovr;
        byte[] ovrBytes;
        List<String> members;
    }

    @SuppressWarnings("unchecked")
    static Saved setUp(Session c) throws Exception {
        Saved sv = new Saved();
        sv.leap = (Path) Mod.field(LEAPCFG, "CONFIG_PATH");
        sv.leapBytes = Files.exists(sv.leap) ? Files.readAllBytes(sv.leap) : null;
        sv.ovr = (Path) Mod.field(OVR, "CONFIG_PATH");
        sv.ovrBytes = Files.exists(sv.ovr) ? Files.readAllBytes(sv.ovr) : null;
        sv.members = c.onClient(mc -> {
            Collection<String> m = (Collection<String>) Mod.field("leapmenu.PartyTracker", "MEMBERS");
            List<String> was = new ArrayList<>(m);
            m.clear();
            return was;
        });
        c.onClient(mc -> {
            Object cfg = Mod.cfg(LEAPCFG);
            Mod.call(cfg, "clearAllPins");
            Mod.call(cfg, "setDefaultOrder", Mod.enumValue("leapmenu.LeapOrderMode", "ODIN"));
            Mod.staticCall(OVR, "clearAll");
            Mod.staticCall("leapmenu.PartyTracker", "noteTeammates", PARTY);
            PICKS.forEach((n, k) -> Mod.staticCall(OVR, "set", n, Mod.enumValue("dungeonclass.DungeonClass", k)));
            Mod.staticCall(OVR, "set", "LoZed", Mod.enumValue("dungeonclass.DungeonClass", "ARCHER"));
            Mod.call(cfg, "pinAt", cls("ARCHER"), "LoBen", 0);
            Mod.call(cfg, "pinAt", cls("ARCHER"), "LoAnna", 3);
            Mod.call(cfg, "pinAt", cls("MAGE"), "LoCara", 1);
            Mod.call(cfg, "save");
            return null;
        });
        return sv;
    }

    @SuppressWarnings("unchecked")
    static void restore(Session c, Saved sv) {
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            Collection<String> m = (Collection<String>) Mod.field("leapmenu.PartyTracker", "MEMBERS");
            m.clear();
            m.addAll(sv.members);
            try {
                write(sv.leap, sv.leapBytes);
                write(sv.ovr, sv.ovrBytes);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            Mod.staticCall(LEAPCFG, "load");
            Mod.staticCall(OVR, "load");
            return null;
        });
    }

    private static void write(Path p, byte[] b) throws Exception {
        if (b != null) {
            Files.write(p, b);
        } else {
            Files.deleteIfExists(p);
        }
    }

    static Object cls(String name) {
        return Mod.enumValue("dungeonclass.DungeonClass", name);
    }

    static String label(AbstractWidget w) {
        return w.getMessage().getString().replaceAll("§.", "");
    }

    static Screen openEditor(Session c) {
        Screen s = c.onClient(mc -> {
            try {
                Screen scr = (Screen) Mod.cls(SCREEN).getConstructor(Screen.class).newInstance((Object) null);
                McCompat.setScreen(mc, scr);
                return scr;
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        c.ctx().waitTicks(3);
        return s;
    }

    static AbstractWidget widget(Session c, Screen s, String prefix) {
        return c.onClient(mc -> {
            for (GuiEventListener l : s.children()) {
                if (l instanceof AbstractWidget w && label(w).startsWith(prefix)) {
                    return w;
                }
            }
            return null;
        });
    }

    static List<AbstractWidget> widgets(Session c, Screen s) {
        return c.onClient(mc -> {
            List<AbstractWidget> out = new ArrayList<>();
            for (GuiEventListener l : s.children()) {
                if (l instanceof AbstractWidget w) {
                    out.add(w);
                }
            }
            return out;
        });
    }

    /** A left click through the widget's own mouseClicked (its active/visible/bounds checks). */
    static void press(Session c, Screen s, String prefix) {
        AbstractWidget w = widget(c, s, prefix);
        c.check(w != null, "no '" + prefix + "' control");
        c.onClient(mc -> {
            double x = w.getX() + w.getWidth() / 2.0;
            double y = w.getY() + w.getHeight() / 2.0;
            w.mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0)), false);
            w.mouseReleased(new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0)));
            return null;
        });
        c.ctx().waitTicks(2);
    }

    /** The editor on the Archer layout (every class known, so no wizard). */
    static Screen editArcher(Session c) {
        Screen s = openEditor(c);
        press(c, s, "Archer");
        c.check(Mod.field(s, "wizard") == null, "the wizard started with every class known");
        c.check(String.valueOf(Mod.field(s, "editing")).equals("ARCHER"), "not editing Archer");
        return s;
    }

    static Map<String, String> overrides(Session c) {
        return c.onClient(mc -> {
            Map<String, String> out = new java.util.TreeMap<>();
            ((Map<?, ?>) Mod.staticCall(OVR, "all")).forEach((k, v) -> out.put(String.valueOf(k), String.valueOf(v)));
            return out;
        });
    }

    @SuppressWarnings("unchecked")
    static List<String> pins(Session c, String cls) {
        return c.onClient(mc -> List.copyOf((List<String>) Mod.call(Mod.cfg(LEAPCFG), "getClassOrder", cls(cls))));
    }

    // ---- 899 --------------------------------------------------------------------------------------------------------

    static void resetClasses(Session c) throws Exception {
        Saved sv = setUp(c);
        try {
            Screen s = editArcher(c);
            Map<String, String> before = overrides(c);
            List<String> archerPins = pins(c, "ARCHER");
            List<String> magePins = pins(c, "MAGE");
            c.check(before.size() == 5, "setup picks " + before);
            press(c, s, "Reset Classes");
            Map<String, String> after = overrides(c);
            c.check(after.equals(Map.of("LoZed", "ARCHER")), "after Reset Classes the picks are " + after
                    + " - want only LoZed's (not in the party)");
            c.check(pins(c, "ARCHER").equals(archerPins) && pins(c, "MAGE").equals(magePins),
                    "Reset Classes touched pins: " + archerPins + " -> " + pins(c, "ARCHER") + ", " + magePins + " -> "
                            + pins(c, "MAGE"));
            c.note("Reset Classes: picks " + before + " -> " + after + "; pins unchanged " + archerPins + " / Mage " + magePins);
        } finally {
            restore(c, sv);
        }
    }

    // ---- 900 --------------------------------------------------------------------------------------------------------

    static void resetPositions(Session c) throws Exception {
        Saved sv = setUp(c);
        try {
            Screen s = editArcher(c);
            Map<String, String> picks = overrides(c);
            List<String> archerPins = pins(c, "ARCHER");
            List<String> magePins = pins(c, "MAGE");
            c.check(archerPins.contains("LoBen") && magePins.contains("LoCara"), "setup pins " + archerPins + " " + magePins);
            press(c, s, "Reset Positions");
            c.check(pins(c, "ARCHER").stream().allMatch(String::isEmpty), "Archer pins left: " + pins(c, "ARCHER"));
            c.check(pins(c, "MAGE").equals(magePins), "Reset Positions on Archer touched Mage's pins: " + pins(c, "MAGE"));
            c.check(overrides(c).equals(picks), "Reset Positions touched class picks: " + picks + " -> " + overrides(c));
            c.note("Reset Positions: Archer pins " + archerPins + " -> " + pins(c, "ARCHER") + "; Mage pins " + magePins
                    + " and picks kept");
        } finally {
            restore(c, sv);
        }
    }

    // ---- 901 --------------------------------------------------------------------------------------------------------

    static long pls(Session c, long since) {
        return c.hx().commands(since).stream().filter(cmd -> cmd.equals("pl")).count();
    }

    static void autoRefresh(Session c) throws Exception {
        Saved sv = setUp(c);
        try {
            c.onClient(mc -> {
                Mod.setField(SCREEN, "lastPartyRefreshMs", 0L);
                return null;
            });
            long head = c.hx().head();
            openEditor(c);
            c.ctx().waitTicks(10);
            long first = pls(c, head);
            c.check(first == 1, "opening the editor sent " + first + " /pl, want 1");
            c.onClient(mc -> {
                mc.resizeGui();
                return null;
            });
            c.ctx().waitTicks(5);
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
            openEditor(c);
            c.ctx().waitTicks(10);
            long second = pls(c, head);
            c.check(second == 1, "a resize and a reopen within the limit sent " + (second - 1) + " more /pl");
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                Mod.setField(SCREEN, "lastPartyRefreshMs", System.currentTimeMillis() - 11_000L);
                return null;
            });
            openEditor(c);
            c.ctx().waitTicks(10);
            long third = pls(c, head);
            c.check(third == 2, "an opening after the limit sent " + (third - 1) + " /pl, want 1");
            // Inside a dungeon the tab list names the party: no /pl.
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                Mod.setField(SCREEN, "lastPartyRefreshMs", 0L);
                return null;
            });
            DungeonMenuCases.inDungeon(c);
            try {
                openEditor(c);
                c.ctx().waitTicks(10);
            } finally {
                DungeonMenuCases.leaveDungeon(c);
            }
            long fourth = pls(c, head);
            c.check(fourth == 2, "opening in a dungeon sent " + (fourth - 2) + " /pl");
            int counted = c.onClient(mc -> ((Number) Mod.staticCall(SCREEN, "partyRefreshes")).intValue());
            c.note("/pl sent by the server's count: open 1, resize + reopen +0, after the limit +1, in a dungeon +0 (mod's own count "
                    + counted + ")");
        } finally {
            restore(c, sv);
        }
    }

    // ---- 902 --------------------------------------------------------------------------------------------------------

    static void cleanup(Session c) throws Exception {
        Saved sv = setUp(c);
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        try {
            c.onClient(mc -> {
                mc.options.guiScale().set(2);
                mc.resizeGui();
                return null;
            });
            Screen s = editArcher(c);
            List<String> labels = new ArrayList<>();
            for (AbstractWidget w : widgets(c, s)) {
                labels.add(label(w));
            }
            for (String gone : List.of("Set Classes", "Refresh Party", "Reset to Default")) {
                c.check(labels.stream().noneMatch(l -> l.startsWith(gone)), "'" + gone + "' is still there: " + labels);
            }
            List<String> row = List.of("Back", "Reset Classes", "Reset Positions", "Done");
            List<AbstractWidget> bottom = new ArrayList<>();
            for (String l : row) {
                AbstractWidget w = widget(c, s, l);
                c.check(w != null, "no '" + l + "' button: " + labels);
                bottom.add(w);
            }
            for (int i = 1; i < bottom.size(); i++) {
                AbstractWidget a = bottom.get(i - 1);
                AbstractWidget b = bottom.get(i);
                c.check(a.getY() == b.getY() && a.getWidth() == b.getWidth() && a.getHeight() == b.getHeight(),
                        row.get(i - 1) + " and " + row.get(i) + " differ in row/size");
                int gap = b.getX() - (a.getX() + a.getWidth());
                int firstGap = bottom.get(1).getX() - (bottom.get(0).getX() + bottom.get(0).getWidth());
                c.check(gap == firstGap && gap > 0, "uneven gaps in the bottom row (" + gap + " vs " + firstGap + ")");
            }
            int left = bottom.get(0).getX();
            int right = s.width - (bottom.get(3).getX() + bottom.get(3).getWidth());
            c.check(Math.abs(left - right) <= 1, "bottom row not centered: " + left + " left, " + right + " right");
            String code;
            try (InputStream in = Mod.cls(SCREEN).getResourceAsStream("LeapOrderScreen.class")) {
                code = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
            }
            c.check(!code.contains("Click a name, then a spot"), "the hint line is still in LeapOrderScreen");
            c.check(code.contains("Reset Positions"), "control: the class file read is not the editor");
            Path taken = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(c.name()));
            dev.testkit.harness.Report.screenshot(c.name(), taken);
            String dir = System.getenv("TESTKIT_LEAP4_SHOTS");
            if (dir != null && !dir.isBlank()) {
                Path out = Path.of(dir).resolve(c.name() + "-" + (MenuKit.cheat() ? "cheat" : "legit") + ".png");
                Files.createDirectories(out.getParent());
                Files.copy(taken, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            c.note("widgets " + labels + "; bottom row " + row + " at x " + bottom.stream().map(AbstractWidget::getX).toList()
                    + ", width " + bottom.get(0).getWidth() + "; hint string absent from the class");
        } finally {
            c.onClient(mc -> {
                mc.options.guiScale().set(oldGui);
                mc.resizeGui();
                return null;
            });
            restore(c, sv);
        }
    }
}
