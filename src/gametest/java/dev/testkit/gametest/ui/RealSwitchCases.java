package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Mod realswitch-1102 (killer560, 2026-11-02, the Chat History card: "this needs an actual toggle").
 * <p>530-ui-menu-real-switch: a feature that can be off has a real ON/OFF switch, never an Off value in a selector. For
 * EVERY tab in the menu whose card has no switch, each cycling row is stepped once round (left clicks until it reads
 * what it read before) and none of its values may read Off / None / Disabled / Never; the tabs that are pure settings
 * pages with no switch are listed in the note. Config files are restored byte for byte.
 * <p>539-ui-chat-history-switch: Chat History's first row is a switch and its card has it; the card switch and the
 * real drawer switch each turn it on and off; OFF applies vanilla's 100 messages, ON the chosen Length (Limited at the
 * History Limit, or Unlimited); the Length row has no Off value; OFF then ON brings back the Length last chosen (also
 * across a reload from disk); and files saved with the old Off/Limited/Unlimited selector (and the older "unlimited"
 * flag) load as the switch plus that length.
 */
final class RealSwitchCases {

    private RealSwitchCases() {
    }

    /** A selector value that means the feature is off. */
    private static final Pattern OFF_VALUE = Pattern.compile("(?i)^(off|none|disabled?|never)\\b.*");

    /**
     * Pure settings pages: never off as a whole, so no card switch, and the Off values they hold are a setting's level,
     * not the feature (realswitch-1102). Tab -> {"Label: Value" allowed, ...}.
     */
    private static final Map<String, List<String>> SETTINGS_PAGES = Map.of(
            // The menu's look: Animations Off is the lowest of Smooth / Reduced / Off; Music Off is one theme's track
            // choice inside Theme Extras (Theme Track, Off or a loop), per theme.
            "MenuThemeTab", List.of("Animations: Off", "Music: Off"));

    // ---- 530 --------------------------------------------------------------------------------------------------------

    static void everyRealSwitch(UiCase c) throws Exception {
        Path root = c.onClient(mc -> (Path) Mod.staticCall("util.ModPaths", "root"));
        Map<Path, byte[]> files = MasterSweepCases.snapshotTree(root);
        boolean cheat = Mod.isCheat();
        List<String> problems = new ArrayList<>();
        List<String> noSwitch = new ArrayList<>();
        int[] counts = new int[3];
        try {
            List<String> names = c.onClient(mc -> {
                List<String> out = new ArrayList<>();
                for (Object t : MenuACases.leaves()) {
                    out.add(t.getClass().getSimpleName());
                }
                return out;
            });
            c.note(names.size() + " tabs in the " + (cheat ? "cheat" : "legit") + " menu");
            for (String name : names) {
                String[] why = c.onClient(mc -> sweepOne(name, counts));
                if (why[0] != null) {
                    problems.add(why[0]);
                }
                if (why[1] != null) {
                    noSwitch.add(why[1]);
                }
            }
        } finally {
            MasterSweepCases.restoreTree(root, files);
            c.onClient(mc -> {
                Mod.staticCall("profiles.ProfileManager", "reloadAllConfigs");
                McCompat.setScreen(mc, null);
                return null;
            });
        }
        c.note(counts[0] + " cards with a switch, " + counts[1] + " without; " + counts[2]
                + " selectors stepped round on the cards without one");
        c.note("cards without a switch (first row; selector values): " + String.join(" | ", noSwitch));
        c.check(counts[0] >= 60 && counts[1] >= 1, "the sweep did not run over the menu: " + counts[0] + " with a switch, "
                + counts[1] + " without");
        c.check(problems.isEmpty(), problems.size() + " card(s) with an Off value in a selector and no switch: "
                + String.join(" | ", problems));
    }

    /** One tab, on the client thread: {problem or null, no-switch description or null}. */
    private static String[] sweepOne(String name, int[] counts) {
        Object tab = MenuACases.leaf(name);
        try {
            Object ms = Mod.staticCall("gui.FeatureCards", "masterSwitch", tab, (Runnable) () -> { });
            if (ms != null) {
                counts[0]++;
                return new String[]{null, null};
            }
            counts[1]++;
            List<AbstractWidget> built = build(tab);
            String first = "(no rows)";
            AbstractWidget f = firstRow(built);
            if (f != null) {
                first = "'" + ModScreenDriver.label(f) + "'" + (isCycling(f) ? " (selector)" : "");
            }
            List<String> offs = new ArrayList<>();
            StringBuilder seen = new StringBuilder();
            int cyclingRows = cyclingRows(built).size();
            for (int i = 0; i < cyclingRows; i++) {
                List<AbstractWidget> now = cyclingRows(build(tab));
                if (i >= now.size()) {
                    break;
                }
                String label = rowLabel(now.get(i));
                LinkedHashSet<String> values = new LinkedHashSet<>();
                String start = value(now.get(i));
                values.add(start);
                AbstractWidget row = now.get(i);
                for (int step = 0; step < 40; step++) {
                    Mod.call(row, "press");
                    row = find(build(tab), label, i);
                    if (row == null) {
                        break;
                    }
                    String v = value(row);
                    if (v.equals(start)) {
                        break;
                    }
                    values.add(v);
                }
                // Back where it started even if the loop was cut short.
                for (int step = 0; row != null && step < 40 && !value(row).equals(start); step++) {
                    Mod.call(row, "press");
                    row = find(build(tab), label, i);
                }
                counts[2]++;
                seen.append(seen.length() == 0 ? "" : "; ").append(label.isEmpty() ? "#" + i : label).append(' ')
                        .append(values);
                for (String v : values) {
                    if (OFF_VALUE.matcher(v).matches()) {
                        offs.add("'" + label + ": " + v + "'");
                    }
                }
            }
            String desc = name + " " + first + (seen.length() == 0 ? "" : " [" + seen + "]");
            List<String> allowed = SETTINGS_PAGES.getOrDefault(name, List.of());
            offs.removeIf(o -> allowed.contains(o.substring(1, o.length() - 1)));
            String problem = offs.isEmpty() ? null : name + ": no card switch, and " + offs + " turns it off";
            return new String[]{problem, desc};
        } catch (RuntimeException | AssertionError e) {
            return new String[]{name + ": " + UiCase.describe(e), null};
        }
    }

    @SuppressWarnings("unchecked")
    private static List<AbstractWidget> build(Object tab) {
        return (List<AbstractWidget>) Mod.call(tab, "buildWidgets", 0, 0, 300, (Runnable) () -> { });
    }

    private static AbstractWidget firstRow(List<AbstractWidget> built) {
        AbstractWidget first = null;
        for (AbstractWidget w : built) {
            if (w instanceof StringWidget || !w.visible) {
                continue;
            }
            if (first == null || w.getY() < first.getY() || (w.getY() == first.getY() && w.getX() < first.getX())) {
                first = w;
            }
        }
        return first;
    }

    private static boolean isCycling(AbstractWidget w) {
        return w.getClass().getSimpleName().equals("SettingsButtonWidget") && (Boolean) Mod.call(w, "isCycling");
    }

    private static List<AbstractWidget> cyclingRows(List<AbstractWidget> built) {
        List<AbstractWidget> out = new ArrayList<>();
        for (AbstractWidget w : built) {
            if (w.visible && w.active && isCycling(w)) {
                out.add(w);
            }
        }
        return out;
    }

    private static String rowLabel(AbstractWidget w) {
        return (String) Mod.call(Mod.call(w, "parts"), "label");
    }

    private static String value(AbstractWidget w) {
        return (String) Mod.call(Mod.call(w, "parts"), "value");
    }

    /** The cycling row labeled {@code label} (or, for an unlabeled one, the {@code index}th). */
    private static AbstractWidget find(List<AbstractWidget> built, String label, int index) {
        List<AbstractWidget> rows = cyclingRows(built);
        if (!label.isEmpty()) {
            for (AbstractWidget w : rows) {
                if (label.equals(rowLabel(w))) {
                    return w;
                }
            }
            return null;
        }
        return index < rows.size() ? rows.get(index) : null;
    }

    // ---- 539 --------------------------------------------------------------------------------------------------------

    private static final String CHC = "chathistory.ChatHistoryConfig";

    private static Object cfg() {
        return Mod.cfg(CHC);
    }

    /** {enabled, length, effective mode, limit(), custom limit}. */
    private static String state(UiCase c) {
        return c.onClient(mc -> {
            Object cfg = cfg();
            return Mod.call(cfg, "isEnabled") + "/" + Mod.call(cfg, "getLength") + "/" + Mod.call(cfg, "getMode") + "/"
                    + Mod.staticCall(CHC, "limit") + "/" + Mod.call(cfg, "getCustomLimit");
        });
    }

    /** How many messages the chat keeps after {@code count} are added to an emptied chat. */
    private static int fill(UiCase c, int count) {
        return c.onClient(mc -> {
            McCompat.chat(mc).clearMessages(false);
            for (int i = 0; i < count; i++) {
                McCompat.chat(mc).addClientSystemMessage(Component.literal("realswitch chat history line " + i));
            }
            return ((List<?>) Mod.field(McCompat.chat(mc), "allMessages")).size();
        });
    }

    private static List<String> rows(UiCase c, Object tab) {
        return c.onClient(mc -> {
            List<String> out = new ArrayList<>();
            for (AbstractWidget w : build(tab)) {
                out.add(ModScreenDriver.label(w));
            }
            return out;
        });
    }

    static void chatHistory(UiCase c) throws Exception {
        Map<Path, byte[]> saved = DungeonB2Cases.snapshotConfig(c, CHC);
        Object tab = c.onClient(mc -> MenuACases.leaf("ChatHistoryTab"));
        try {
            c.onClient(mc -> {
                Object cfg = cfg();
                Mod.call(cfg, "setEnabled", false);
                Mod.call(cfg, "setLength", Mod.enumValue(CHC + "$Length", "LIMITED"));
                Mod.call(cfg, "setCustomLimit", 200);
                Mod.call(cfg, "save");
                return null;
            });
            // OFF: the first row is the switch, the card has it, nothing else shows, vanilla's 100.
            List<String> off = rows(c, tab);
            c.note("OFF rows " + off);
            String[] first = c.onClient(mc -> {
                AbstractWidget f = firstRow(build(tab));
                return new String[]{ModScreenDriver.label(f), String.valueOf(Mod.call(f, "isSwitch")),
                        String.valueOf(Mod.call(f, "isOn"))};
            });
            c.check("true".equals(first[1]) && "false".equals(first[2]) && first[0].startsWith("Chat History"),
                    "the first row is not the Chat History switch reading OFF: " + String.join(" ", first));
            c.check(off.size() == 1, "OFF shows more than the switch: " + off);
            Boolean card = c.onClient(mc -> (Boolean) Mod.staticCall("gui.FeatureCards", "refresh", tab));
            c.check(Boolean.FALSE.equals(card), "the Chat History card reads " + card + " with the switch OFF (null = no switch)");
            int vanilla = fill(c, 130);
            c.check(vanilla == 100, "OFF kept " + vanilla + " of 130 messages, want vanilla's 100");

            // The card switch turns it ON at the Length kept (Limited, 200).
            Boolean on = c.onClient(mc -> (Boolean) Mod.staticCall("gui.FeatureCards", "toggle", tab, (Runnable) () -> { }));
            String s = state(c);
            c.note("after the card switch: " + s);
            c.check(Boolean.TRUE.equals(on) && s.startsWith("true/LIMITED/LIMITED/200/"), "the card switch did not turn it on at Limited 200: " + on + " " + s);
            List<String> lim = rows(c, tab);
            c.note("ON Limited rows " + lim);
            c.check(lim.size() >= 3 && lim.get(1).startsWith("Length: Limited") && lim.get(2).startsWith("History Limit: 200"),
                    "ON Limited rows: " + lim);
            int limited = fill(c, 250);
            c.check(limited == 200, "Limited 200 kept " + limited + " of 250 messages");

            // The Length row: two values, no Off.
            List<String> lengths = c.onClient(mc -> {
                List<String> out = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    AbstractWidget r = find(build(tab), "Length", 0);
                    out.add(value(r));
                    Mod.call(r, "press");
                }
                return out;
            });
            c.note("Length values over four clicks: " + lengths);
            c.check(lengths.equals(List.of("Limited", "Unlimited", "Limited", "Unlimited")), "Length steps " + lengths);
            // One more click: Unlimited.
            c.onClient(mc -> {
                Mod.call(find(build(tab), "Length", 0), "press");
                return null;
            });
            s = state(c);
            c.check(s.startsWith("true/UNLIMITED/UNLIMITED/" + Integer.MAX_VALUE + "/"), "Unlimited: " + s);
            int unlimited = fill(c, 450);
            c.check(unlimited == 450, "Unlimited kept " + unlimited + " of 450 messages");
            c.check(rows(c, tab).stream().noneMatch(l -> l.startsWith("History Limit")), "Unlimited shows the slider");

            // The real drawer's switch turns it OFF: vanilla's 100 again, Unlimited kept for later.
            String drawer = c.onClient(mc -> {
                try {
                    Screen sc = MenuACases.open(mc);
                    Mod.call(sc, "openFeature", tab);
                    for (AbstractWidget w : MenuACases.paneRows(sc)) {
                        if (w.getClass().getSimpleName().equals("SettingsButtonWidget") && (Boolean) Mod.call(w, "isSwitch")
                                && "Chat History".equals(rowLabel(w))) {
                            Mod.call(w, "press");
                            return "pressed";
                        }
                    }
                    return "no Chat History switch in the drawer";
                } catch (Exception e) {
                    return UiCase.describe(e);
                } finally {
                    McCompat.setScreen(mc, null);
                }
            });
            s = state(c);
            c.note("after the drawer switch: " + drawer + ", " + s);
            c.check("pressed".equals(drawer) && s.startsWith("false/UNLIMITED/OFF/100/"), "the drawer switch: " + drawer + " " + s);
            vanilla = fill(c, 130);
            c.check(vanilla == 100, "OFF again kept " + vanilla + " of 130 messages");
            c.check(rows(c, tab).size() == 1, "OFF again shows " + rows(c, tab));

            // A reload from disk keeps OFF with Unlimited remembered; the card switch brings Unlimited back.
            c.onClient(mc -> {
                Mod.staticCall(CHC, "load");
                return null;
            });
            s = state(c);
            c.check(s.startsWith("false/UNLIMITED/OFF/"), "after a reload: " + s);
            c.onClient(mc -> Mod.staticCall("gui.FeatureCards", "toggle", tab, (Runnable) () -> { }));
            s = state(c);
            c.check(s.startsWith("true/UNLIMITED/UNLIMITED/"), "OFF then ON did not bring Unlimited back: " + s);
            String file = c.onClient(mc -> {
                try {
                    return Files.readString(saved.keySet().iterator().next());
                } catch (Exception e) {
                    return "unreadable " + e;
                }
            });
            c.check(file.contains("\"enabled\": true") && file.contains("\"length\": \"UNLIMITED\"") && !file.contains("\"mode\""),
                    "the saved file: " + file);

            // Old files: the Off/Limited/Unlimited selector (2026-11-01) and the "unlimited" flag before it.
            String[][] old = {
                    {"{\"mode\": \"OFF\", \"limit\": 600}", "false/LIMITED/OFF/100/600"},
                    {"{\"mode\": \"LIMITED\", \"limit\": 800}", "true/LIMITED/LIMITED/800/800"},
                    {"{\"mode\": \"UNLIMITED\", \"limit\": 1000}", "true/UNLIMITED/UNLIMITED/" + Integer.MAX_VALUE + "/1000"},
                    {"{\"unlimited\": true}", "true/UNLIMITED/UNLIMITED/" + Integer.MAX_VALUE + "/1000"},
                    {"{\"unlimited\": false}", "false/LIMITED/OFF/100/1000"},
            };
            Path p = saved.keySet().iterator().next();
            for (String[] o : old) {
                c.onClient(mc -> {
                    try {
                        Files.writeString(p, o[0]);
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                    Mod.staticCall(CHC, "load");
                    return null;
                });
                s = state(c);
                c.note(o[0] + " -> " + s);
                c.check(s.equals(o[1]), o[0] + " loads as " + s + ", want " + o[1]);
            }
        } finally {
            c.onClient(mc -> {
                McCompat.chat(mc).clearMessages(false);
                return null;
            });
            DungeonB2Cases.restoreConfig(c, saved, CHC);
        }
    }

    // ---- 540 --------------------------------------------------------------------------------------------------------

    private static final String CAPE = "cape.CapeConfig";

    /** {enabled, hasCape, getSelected, getDesign}. */
    private static String cape(UiCase c) {
        return c.onClient(mc -> {
            Object cfg = Mod.cfg(CAPE);
            return Mod.call(cfg, "isEnabled") + "/" + Mod.call(cfg, "hasCape") + "/" + Mod.call(cfg, "getSelected") + "/"
                    + Mod.call(cfg, "getDesign");
        });
    }

    /**
     * 540-ui-cape-switch: the Cape section (inside Player Cosmetics) had one "Cape: None / designs / Make Your Own"
     * selector; now its first row is a Cape switch and, while ON, a Design selector with no None. The switch turns the
     * cape on and off (hasCape, which the cape mixin reads), the design is kept across OFF then ON and a reload, and old
     * files with "selected" load as the switch plus that design.
     */
    static void capeSwitch(UiCase c) throws Exception {
        Map<Path, byte[]> saved = DungeonB2Cases.snapshotConfig(c, CAPE);
        Object tab = c.onClient(mc -> {
            try {
                return Mod.cls("gui.tab.CapeTab").getConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        try {
            c.onClient(mc -> {
                Object cfg = Mod.cfg(CAPE);
                Mod.call(cfg, "setSelected", "ocean");
                Mod.call(cfg, "setEnabled", false);
                Mod.call(cfg, "save");
                return null;
            });
            List<String> off = rows(c, tab);
            c.note("OFF rows " + off + ", " + cape(c));
            c.check(off.equals(List.of("Cape: OFF")), "OFF rows: " + off);
            c.check(cape(c).equals("false/false/none/ocean"), "OFF: " + cape(c));
            // The drawer switch (a real press on the row) turns it on wearing the design kept.
            c.onClient(mc -> {
                Mod.call(build(tab).get(0), "press");
                return null;
            });
            List<String> on = rows(c, tab);
            c.note("ON rows " + on + ", " + cape(c));
            c.check(cape(c).equals("true/true/ocean/ocean"), "ON did not wear the kept design: " + cape(c));
            c.check(on.size() >= 2 && on.get(0).equals("Cape: ON") && on.get(1).equals("Design: Ocean"), "ON rows: " + on);
            List<String> designs = c.onClient(mc -> {
                List<String> out = new ArrayList<>();
                for (int i = 0; i < 6; i++) {
                    AbstractWidget r = find(build(tab), "Design", 0);
                    out.add(value(r));
                    Mod.call(r, "press");
                }
                return out;
            });
            c.note("Design over six clicks: " + designs);
            c.check(designs.size() == 6 && new LinkedHashSet<>(designs).size() == 6
                            && designs.stream().noneMatch(v -> OFF_VALUE.matcher(v).matches())
                            && designs.contains("Make Your Own"),
                    "the Design selector steps " + designs);
            // Six clicks went round once: back on Ocean. Off, reload, on: still Ocean.
            c.onClient(mc -> {
                Mod.call(build(tab).get(0), "press");
                Mod.staticCall(CAPE, "load");
                return null;
            });
            c.check(cape(c).equals("false/false/none/ocean"), "OFF after a reload: " + cape(c));
            c.onClient(mc -> {
                Mod.call(build(tab).get(0), "press");
                return null;
            });
            c.check(cape(c).equals("true/true/ocean/ocean"), "OFF then ON: " + cape(c));
            Path p = saved.keySet().iterator().next();
            String file = Files.readString(p);
            c.check(file.contains("\"enabled\": true") && file.contains("\"design\": \"ocean\"") && !file.contains("\"selected\""),
                    "the saved file: " + file);
            String[][] old = {
                    {"{\"selected\": \"none\"}", "false/false/none/amber"},
                    {"{\"selected\": \"dark\"}", "true/true/dark/dark"},
                    {"{\"selected\": \"custom\"}", "true/true/custom/custom"},
            };
            for (String[] o : old) {
                c.onClient(mc -> {
                    try {
                        Files.writeString(p, o[0]);
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                    Mod.staticCall(CAPE, "load");
                    return null;
                });
                String s = cape(c);
                c.note(o[0] + " -> " + s);
                c.check(s.equals(o[1]), o[0] + " loads as " + s + ", want " + o[1]);
            }
        } finally {
            DungeonB2Cases.restoreConfig(c, saved, CAPE);
        }
    }
}
