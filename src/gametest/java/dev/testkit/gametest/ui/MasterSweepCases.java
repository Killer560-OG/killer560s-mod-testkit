package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Mod menufix-1102 (killer560, 2026-11-02: Architect's First Draft's card read OFF with Auto Get From Sack ON).
 * <p>525-ui-menu-every-master: for EVERY tab in the menu whose card stands for two or more rows ({@code
 * BaseTab.masterToggles}), each of those rows switched ON alone makes the card read ON, and all of them OFF makes it read
 * OFF. The tabs the 1102 sweep found are also pinned to the rows their card must stand for, so a row that falls out of
 * the list again fails here. Every row is put back as it was; config files are restored byte for byte.
 * <p>526-ui-ban-report-text: the Ban Reports section is the short bullet list, and no line is wider than the space it
 * is drawn in, at every content width from 120 to 440 px and in the real menu at the current GUI scale.
 */
final class MasterSweepCases {

    private MasterSweepCases() {
    }

    /** The rows each fixed tab's card must stand for (cheat-only rows marked with a leading '!'). */
    private static final Map<String, String[]> PINNED = new LinkedHashMap<>();

    static {
        PINNED.put("ArchitectDraftTab", new String[]{"Click Message On Puzzle Fail", "!Auto Get From Sack"});
        PINNED.put("AuctionHouseTab", new String[]{"Auction House Browser", "Listing Helper"});
        PINNED.put("AutoCorrectTab", new String[]{"Auto Correct Enabled", "Correct Commands"});
        PINNED.put("ArrowAlignTab", new String[]{"Solver", "Prevent Misclicks", "Solve Time", "!Trigger Bot", "!Aura"});
        PINNED.put("CosmeticsTab", new String[]{"Supporter Names", "Supporter Sizes", "Show Others' Trails",
                "Show Others' Mobs", "My Name in F5"});
        PINNED.put("GifPlayerTab", new String[]{"GIFs", "Audio"});
        PINNED.put("HealthAndManaBarsTab", new String[]{"Stat Bars", "XP Bar And Level", "Hypixel Stat Text", "Drill Fuel Text"});
        PINNED.put("TeamMelodyTab", new String[]{"Team Melody HUD", "Share My Progress"});
        PINNED.put("TerminalSolverTab", new String[]{"Terminal Solver", "Terminal Protection", "Terminal Drop Key",
                "Melody Keys", "Hide Completion Titles", "Hide Completion Chat"});
        PINNED.put("ZeroPingEtherwarpTab", new String[]{"!Zero Ping Etherwarp", "!Left Click Etherwarp"});
        // Mod menufix2-1102 (killer560 2026-11-02): Thorn Triggerbot folded into the Thorn card's cheat section.
        PINNED.put("ThornTab", new String[]{"Spirit Bear HUD", "Overkill Chat", "Thorn Highlight", "Stun Spot Waypoints",
                "!All Mobs Through Walls", "!Bats Through Walls", "!Thorn Triggerbot"});
        // Mod bloodrush2-1104: Learn Spawn Spots learns while the helper is off, so the card stands for both.
        PINNED.put("BloodRushHelperTab", new String[]{"Blood Rush Helper", "Learn Spawn Spots"});
    }

    /** Rows that do nothing on their own, so they must not make a card read ON. */
    private static final Map<String, String[]> NOT_PARTS = Map.of(
            "ThornTab", new String[]{"Show Overkill", "Overkill To Party"});

    // ---- 525 --------------------------------------------------------------------------------------------------------

    static void everyMaster(UiCase c) throws Exception {
        Path root = c.onClient(mc -> (Path) Mod.staticCall("util.ModPaths", "root"));
        Map<Path, byte[]> files = snapshotTree(root);
        boolean cheat = Mod.isCheat();
        Object[] gate = new Object[1];
        List<String> problems = new ArrayList<>();
        int[] counts = new int[3];
        try {
            gate[0] = c.onClient(mc -> {
                Object was = Mod.staticCall("util.SkyblockGate", "isEnabled");
                Mod.staticCall("util.SkyblockGate", "setEnabled", false);
                return was;
            });
            List<String> names = c.onClient(mc -> {
                List<String> out = new ArrayList<>();
                for (Object t : MenuACases.leaves()) {
                    out.add(t.getClass().getSimpleName());
                }
                return out;
            });
            c.note(names.size() + " tabs in the " + (cheat ? "cheat" : "legit") + " menu");
            for (String name : names) {
                String why = c.onClient(mc -> sweepOne(name, cheat, counts));
                if (why != null) {
                    problems.add(why);
                }
            }
            // Pinned tabs must be in the menu (the cheat-only ones only on a cheat jar).
            for (Map.Entry<String, String[]> e : PINNED.entrySet()) {
                boolean cheatOnlyTab = true;
                for (String l : e.getValue()) {
                    cheatOnlyTab &= l.startsWith("!");
                }
                if (!names.contains(e.getKey()) && (cheat || !cheatOnlyTab)) {
                    problems.add(e.getKey() + ": not in the menu");
                }
                if (!cheat && cheatOnlyTab && names.contains(e.getKey())) {
                    problems.add(e.getKey() + ": a cheat-only tab is in the legit menu");
                }
            }
        } finally {
            restoreTree(root, files);
            c.onClient(mc -> {
                Mod.staticCall("profiles.ProfileManager", "reloadAllConfigs");
                if (gate[0] != null) {
                    Mod.staticCall("util.SkyblockGate", "setEnabled", gate[0]);
                }
                McCompat.setScreen(mc, null);
                return null;
            });
        }
        c.note(counts[0] + " tabs with a card of 2+ rows swept, " + counts[1] + " rows switched on alone, "
                + counts[2] + " pinned tabs matched");
        c.check(counts[0] >= 10 && counts[1] >= 30, "the sweep did not run over the menu: " + counts[0] + " tabs, "
                + counts[1] + " rows");
        c.check(problems.isEmpty(), problems.size() + " problem(s): " + String.join(" | ", problems));
    }

    /** One tab: null when fine, else what is wrong. Runs on the client thread, so no tick passes between ON and OFF. */
    private static String sweepOne(String name, boolean cheat, int[] counts) {
        Object tab = MenuACases.leaf(name);
        try {
            List<Object> parts = parts(tab);
            List<String> labels = labels(parts);
            String[] pinned = PINNED.get(name);
            if (pinned != null) {
                for (String p : pinned) {
                    boolean cheatRow = p.startsWith("!");
                    String l = cheatRow ? p.substring(1) : p;
                    if ((cheat || !cheatRow) && !labels.contains(l)) {
                        return name + ": the card does not stand for '" + l + "' (it stands for " + labels + ")";
                    }
                    if (!cheat && cheatRow && labels.contains(l)) {
                        return name + ": a legit card stands for the cheat row '" + l + "'";
                    }
                }
                counts[2]++;
            }
            String[] not = NOT_PARTS.get(name);
            if (not != null) {
                for (String l : not) {
                    if (labels.contains(l)) {
                        return name + ": the card stands for '" + l + "', which does nothing alone";
                    }
                }
            }
            if (parts.size() < 2) {
                return null;
            }
            counts[0]++;
            List<Boolean> was = states(parts);
            try {
                allOff(tab, labels);
                if (states(parts(tab)).contains(true)) {
                    return name + ": could not switch every row off: " + labels + " " + states(parts(tab));
                }
                if (Boolean.TRUE.equals(card(tab))) {
                    return name + ": card ON with every row off " + labels;
                }
                for (String l : labels) {
                    Object before = row(tab, l);
                    if (before instanceof AbstractWidget aw && !aw.active) {
                        // A row the account cannot switch (Share My Song is a supporter perk): nothing to test.
                        continue;
                    }
                    press(tab, l);
                    Object row = row(tab, l);
                    if (row == null) {
                        return name + ": '" + l + "' is gone once it is ON alone";
                    }
                    if (!(Boolean) Mod.call(row, "isOn")) {
                        return name + ": pressing '" + l + "' did not turn it on";
                    }
                    counts[1]++;
                    if (!Boolean.TRUE.equals(card(tab))) {
                        return name + ": card OFF with only '" + l + "' ON";
                    }
                    press(tab, l);
                    if (Boolean.TRUE.equals(card(tab))) {
                        return name + ": card ON after '" + l + "' went back off";
                    }
                }
            } finally {
                // Back as it was, row by row (files are restored byte for byte at the end too).
                for (int i = 0; i < labels.size(); i++) {
                    Object row = row(tab, labels.get(i));
                    if (row != null && (Boolean) Mod.call(row, "isOn") != was.get(i)) {
                        Mod.call(row, "press");
                    }
                }
            }
            return null;
        } catch (RuntimeException | AssertionError e) {
            return name + ": " + UiCase.describe(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> parts(Object tab) {
        Object ms = Mod.staticCall("gui.FeatureCards", "masterSwitch", tab, (Runnable) () -> { });
        return ms == null ? List.of() : new ArrayList<>((List<Object>) Mod.call(ms, "parts"));
    }

    private static List<String> labels(List<Object> parts) {
        List<String> out = new ArrayList<>();
        for (Object p : parts) {
            out.add((String) Mod.call(Mod.call(p, "parts"), "label"));
        }
        return out;
    }

    private static List<Boolean> states(List<Object> parts) {
        List<Boolean> out = new ArrayList<>();
        for (Object p : parts) {
            out.add((Boolean) Mod.call(p, "isOn"));
        }
        return out;
    }

    /** The row labeled {@code label} among the card's rows, freshly built. */
    private static Object row(Object tab, String label) {
        for (Object p : parts(tab)) {
            if (label.equals(Mod.call(Mod.call(p, "parts"), "label"))) {
                return p;
            }
        }
        return null;
    }

    private static void press(Object tab, String label) {
        Object r = row(tab, label);
        if (r == null) {
            throw new AssertionError("no row '" + label + "' to press");
        }
        Mod.call(r, "press");
    }

    /** Every row off, pressing each ON one; a press may rebuild what the others read, so a few rounds. */
    private static void allOff(Object tab, List<String> labels) {
        for (int round = 0; round < 3; round++) {
            boolean any = false;
            for (String l : labels) {
                Object r = row(tab, l);
                if (r != null && (Boolean) Mod.call(r, "isOn")) {
                    Mod.call(r, "press");
                    any = true;
                }
            }
            if (!any) {
                return;
            }
        }
    }

    private static Boolean card(Object tab) {
        return (Boolean) Mod.staticCall("gui.FeatureCards", "refresh", tab);
    }

    static Map<Path, byte[]> snapshotTree(Path root) throws IOException {
        Map<Path, byte[]> out = new LinkedHashMap<>();
        if (!Files.isDirectory(root)) {
            return out;
        }
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (Files.isRegularFile(p) && Files.size(p) < 4_000_000) {
                    out.put(p, Files.readAllBytes(p));
                }
            }
        }
        return out;
    }

    static void restoreTree(Path root, Map<Path, byte[]> files) throws IOException {
        if (Files.isDirectory(root)) {
            try (Stream<Path> s = Files.walk(root)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    if (Files.isRegularFile(p) && Files.size(p) < 4_000_000 && !files.containsKey(p)
                            && p.getFileName().toString().endsWith(".json")) {
                        Files.deleteIfExists(p);
                    }
                }
            }
        }
        for (Map.Entry<Path, byte[]> e : files.entrySet()) {
            Files.createDirectories(e.getKey().getParent());
            Files.write(e.getKey(), e.getValue());
        }
    }

    // ---- 526 --------------------------------------------------------------------------------------------------------

    private static final List<String> BAN_LINES = List.of(
            "If you're ever banned, this shares with the Discord admins:",
            "• the features you had on and their settings",
            "• this session's mod log",
            "• the ban message and mod version",
            "It never shares:",
            "• tokens, passwords or session IDs",
            "• other players' names or chat",
            "• your IP or API keys");

    static void banReportText(UiCase c) throws Exception {
        List<String> problems = c.onClient(mc -> {
            List<String> out = new ArrayList<>();
            Object tab;
            try {
                tab = Mod.cls("gui.tab.BanReportTab").getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                out.add("no BanReportTab: " + e);
                return out;
            }
            var font = mc.font;
            for (int w = 120; w <= 440; w += 10) {
                @SuppressWarnings("unchecked")
                List<AbstractWidget> built = (List<AbstractWidget>) Mod.call(tab, "buildWidgets", 0, 0, w,
                        (Runnable) () -> { });
                StringBuilder joined = new StringBuilder();
                for (AbstractWidget x : built) {
                    if (!(x instanceof StringWidget)) {
                        continue;
                    }
                    String text = net.minecraft.ChatFormatting.stripFormatting(x.getMessage().getString());
                    if (text.equals("Ban Reports")) {
                        continue;
                    }
                    int tw = font.width(x.getMessage());
                    if (tw > w) {
                        out.add("width " + w + ": '" + text + "' is " + tw + " px");
                    }
                    joined.append(text.strip()).append(' ');
                }
                String want = String.join(" ", BAN_LINES) + " ";
                if (!joined.toString().replaceAll("\\s+", " ").equals(want.replaceAll("\\s+", " "))) {
                    out.add("width " + w + ": the text reads '" + joined + "'");
                }
                if (w == 340) {
                    c.note("at 340 px (the narrowest docked drawer): " + joined);
                }
            }
            return out;
        });
        c.check(problems.isEmpty(), "Ban Reports text: " + problems);
        // The real menu at this GUI scale: every Ban Reports line fits inside the drawer.
        String real = c.onClient(mc -> {
            try {
                Screen s = MenuACases.open(mc);
                Object tab = MenuACases.leaf("BanReportTab");
                Mod.call(s, "openFeature", tab);
                int drawerX = (Integer) Mod.field(s, "drawerX");
                int drawerW = (Integer) Mod.field(s, "drawerW");
                List<String> cut = new ArrayList<>();
                int lines = 0;
                for (AbstractWidget w : MenuACases.paneRows(s)) {
                    if (w instanceof StringWidget sw) {
                        int tw = mc.font.width(sw.getMessage());
                        lines++;
                        if (tw > sw.getWidth() || sw.getX() + tw > drawerX + drawerW) {
                            cut.add("'" + sw.getMessage().getString() + "' " + tw + " px in " + sw.getWidth());
                        }
                    }
                }
                McCompat.setScreen(mc, null);
                return "gui scale " + mc.getWindow().getGuiScale() + ", drawer " + drawerW + " px, " + lines
                        + " lines, cut off: " + cut;
            } catch (Exception e) {
                return "error " + UiCase.describe(e);
            }
        });
        c.note(real);
        c.check(real.endsWith("cut off: []"), "the real menu cuts a Ban Reports line: " + real);
    }
}
