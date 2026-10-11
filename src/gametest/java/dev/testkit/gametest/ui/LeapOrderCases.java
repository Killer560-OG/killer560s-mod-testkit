package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * killer560 (2026-10-09): "once I set everyone's class then it should automatically move them where they would normally
 * be; I should still be able to manually move them ... there should be a few filter options: A-Z by class name, Z-A by
 * class name, Odin leaporder" (mod branch leaporder, {@code leapmenu/LeapOrderMode}). The leap menu half is 670 in the
 * menu suite.
 * <ul>
 *   <li>666: the pure layout against an oracle written here from Odin's / Skyblocker's source for every mode, then the
 *       real editor: classes assigned one at a time (the first by a real right-click on the spot) move each teammate to
 *       the spot the selected default gives that class, for every default (switched by the real Default Order button).</li>
 *   <li>667: a real click-click swap pins both names (and only them); assigning a class to someone else afterwards
 *       moves only the unpinned; pins, default and classes survive a reload from disk; an old-format file loads with no
 *       pins and keeps its spots under {@code legacyClassOrders}.</li>
 *   <li>668: shift-click unpins one teammate (he returns to his default spot); Reset to Default unpins all.</li>
 *   <li>669: screenshots at GUI 2 (default layout, after a swap with "pinned" drawn); copied to
 *       {@code TESTKIT_LEAPORDER_SHOTS} when set.</li>
 * </ul>
 */
final class LeapOrderCases {

    static final String CFG = "leapmenu.LeapMenuConfig";
    static final String MODE = "leapmenu.LeapOrderMode";
    static final String FEATURE = "leapmenu.LeapMenuFeature";
    static final String TRACKER = "leapmenu.PartyTracker";
    static final String OVERRIDES = "dungeonclass.ClassOverrides";
    /** Listing order (Hypixel's leap menu order); none of them is the test player. */
    static final List<String> PARTY = List.of("LoAnna", "LoBen", "LoCara", "LoDev");
    /** Playing Archer: Mage + Tank both want Odin's bottom-right, so Odin, A-Z, Z-A and Hypixel all differ. */
    static final String PLAYING = "ARCHER";
    static final Map<String, String> CLASSES = Map.of("LoAnna", "MAGE", "LoBen", "TANK", "LoCara", "HEALER",
            "LoDev", "BERSERKER");
    static final List<String> MODES = List.of("ODIN", "CLASS_AZ", "CLASS_ZA", "HYPIXEL");
    /** The full-party answers, worked by hand from the sources (guards the oracle as much as the mod). */
    static final Map<String, List<String>> FULL = Map.of(
            "ODIN", List.of("LoAnna", "LoDev", "LoCara", "LoBen"),
            "CLASS_AZ", List.of("LoDev", "LoCara", "LoAnna", "LoBen"),
            "CLASS_ZA", List.of("LoBen", "LoAnna", "LoCara", "LoDev"),
            "HYPIXEL", List.of("LoAnna", "LoBen", "LoCara", "LoDev"));

    private LeapOrderCases() {
    }

    // ---- oracle -----------------------------------------------------------------------------------------------------

    /** Odin {@code DungeonEnums.kt}: ARCHER(q0,p2) BERSERK(q1,p0) HEALER(q2,p2) MAGE(q3,p2) TANK(q3,p1). */
    private static int quadrant(String c) {
        return switch (c) {
            case "ARCHER" -> 0;
            case "BERSERKER" -> 1;
            case "HEALER" -> 2;
            default -> 3;
        };
    }

    private static int priority(String c) {
        return switch (c) {
            case "BERSERKER" -> 0;
            case "TANK" -> 1;
            default -> 2;
        };
    }

    private static String display(String c) {
        return c.charAt(0) + c.substring(1).toLowerCase(Locale.ROOT);
    }

    /** What the layout must be: pins first, then the mode over everyone else (unknown classes last, in order). */
    static List<String> expected(String mode, List<String> party, List<String> pins, Map<String, String> classes) {
        String[] out = new String[4];
        List<String> rest = new ArrayList<>(party);
        for (int i = 0; i < 4; i++) {
            String p = i < pins.size() ? pins.get(i) : "";
            if (!p.isEmpty() && rest.remove(p)) {
                out[i] = p;
            }
        }
        List<String> known = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String p : rest) {
            (classes.containsKey(p) ? known : unknown).add(p);
        }
        List<String> fill = new ArrayList<>();
        switch (mode) {
            case "ODIN" -> {
                known.sort(Comparator.comparingInt(p -> priority(classes.get(p))));
                for (String p : known) {
                    int q = quadrant(classes.get(p));
                    if (out[q] == null) {
                        out[q] = p;
                    } else {
                        fill.add(p);
                    }
                }
                fill.addAll(unknown);
            }
            case "CLASS_AZ", "CLASS_ZA" -> {
                Comparator<String> byClass = Comparator.comparing(p -> display(classes.get(p)));
                known.sort((mode.equals("CLASS_AZ") ? byClass : byClass.reversed())
                        .thenComparing(p -> p.toLowerCase(Locale.ROOT)));
                fill.addAll(known);
                fill.addAll(unknown);
            }
            default -> fill.addAll(rest);
        }
        for (int i = 0; i < 4 && !fill.isEmpty(); i++) {
            if (out[i] == null) {
                out[i] = fill.remove(0);
            }
        }
        List<String> r = new ArrayList<>();
        for (String s : out) {
            r.add(s == null ? "" : s);
        }
        return r;
    }

    // ---- fixture ------------------------------------------------------------------------------------------------------

    /** Saves the leap config file, party and overrides; {@link #restore} puts them back. */
    static final class State {
        byte[] file;
        boolean existed;
        Path path;
    }

    static State setUp(UiCase c) throws Exception {
        State st = new State();
        st.path = (Path) Mod.field(CFG, "CONFIG_PATH");
        st.existed = Files.exists(st.path);
        st.file = st.existed ? Files.readAllBytes(st.path) : null;
        c.onClient(mc -> {
            Object cfg = Mod.cfg(CFG);
            Mod.call(cfg, "clearClassOrder", Mod.enumValue("dungeonclass.DungeonClass", PLAYING));
            Mod.call(cfg, "setLastEditedClass", Mod.enumValue("dungeonclass.DungeonClass", PLAYING));
            Mod.call(cfg, "setDefaultOrder", Mod.enumValue(MODE, "ODIN"));
            for (String n : PARTY) {
                Mod.staticCall(OVERRIDES, "clear", n);
            }
            members().clear();
            Mod.staticCall(TRACKER, "noteTeammates", PARTY);   // also the leap menu order: Anna, Ben, Cara, Dev
            Mod.call(cfg, "save");
            return null;
        });
        return st;
    }

    @SuppressWarnings("unchecked")
    static Collection<String> members() {
        return (Collection<String>) Mod.field(TRACKER, "MEMBERS");
    }

    static void restore(UiCase c, State st) {
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            for (String n : PARTY) {
                Mod.staticCall(OVERRIDES, "clear", n);
            }
            Mod.call(Mod.staticCall(OVERRIDES, "getInstance"), "save");
            members().clear();
            try {
                if (st.existed) {
                    Files.write(st.path, st.file);
                } else {
                    Files.deleteIfExists(st.path);
                }
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            Mod.staticCall(CFG, "load");
            return null;
        });
    }

    static void setClass(UiCase c, String name, String cls) {
        c.onClient(mc -> Mod.staticCall(OVERRIDES, "set", name, Mod.enumValue("dungeonclass.DungeonClass", cls)));
    }

    static void setMode(UiCase c, String mode) {
        c.onClient(mc -> {
            Mod.call(Mod.cfg(CFG), "setDefaultOrder", Mod.enumValue(MODE, mode));
            return null;
        });
    }

    static String mode(UiCase c) {
        return c.onClient(mc -> String.valueOf(Mod.call(Mod.cfg(CFG), "getDefaultOrder")));
    }

    static List<String> pins(UiCase c) {
        return c.onClient(mc -> {
            @SuppressWarnings("unchecked")
            List<String> l = (List<String>) Mod.call(Mod.cfg(CFG), "getClassOrder",
                    Mod.enumValue("dungeonclass.DungeonClass", PLAYING));
            return List.copyOf(l);
        });
    }

    /** Opens the editor and picks the playing class with a real click on its button. */
    static Screen open(UiCase c) throws Exception {
        Screen s = c.onClient(mc -> {
            try {
                Screen scr = (Screen) Mod.cls("leapmenu.LeapOrderScreen").getConstructor(Screen.class)
                        .newInstance((Object) null);
                McCompat.setScreen(mc, scr);
                return scr;
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        c.ticks(3);
        AbstractWidget archer = InvBatchCases.widget(c, s, "Archer");
        InvBatchCases.clickWidget(c, s, archer);
        Object editing = c.onClient(mc -> Mod.field(s, "editing"));
        c.check(String.valueOf(editing).equals(PLAYING), "clicking Archer left the editor on " + editing);
        return s;
    }

    /** The 4 spots exactly as the editor draws them ("" = empty). */
    static List<String> shown(UiCase c, Screen s) {
        return c.onClient(mc -> {
            String[] l = (String[]) Mod.call(s, "currentLayout");
            List<String> r = new ArrayList<>();
            for (String n : l) {
                r.add(n == null ? "" : n);
            }
            return r;
        });
    }

    /** Layout coordinates of a point near the top of a spot's box (clear of its Class button). */
    static double[] spotPoint(UiCase c, Screen s, int spot) {
        int[] b = c.onClient(mc -> (int[]) Mod.call(s, "spotBounds", spot));
        return new double[]{b[0] + b[2] / 2.0, b[1] + 4};
    }

    static void clickSpot(UiCase c, Screen s, int spot, int button) {
        double[] p = spotPoint(c, s, spot);
        InvBatchCases.cursor(c, s, p[0], p[1]);
        c.ctx().getInput().pressMouse(button);
        c.ticks(3);
    }

    static void shiftClickSpot(UiCase c, Screen s, int spot) {
        double[] p = spotPoint(c, s, spot);
        boolean took = c.onClient(mc -> s.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(p[0], p[1],
                new net.minecraft.client.input.MouseButtonInfo(0, 1)), false));   // GLFW_MOD_SHIFT
        c.check(took, "the editor did not take a shift-click on spot " + spot);
        c.ticks(2);
    }

    static void pressDefaultOrder(UiCase c, Screen s) {
        InvBatchCases.clickWidget(c, s, InvBatchCases.widget(c, s, "Default Order:"));
    }

    /** Cycles the real Default Order button until it shows {@code mode}. */
    static void pressUntil(UiCase c, Screen s, String mode) {
        for (int i = 0; i < 5 && !mode(c).equals(mode); i++) {
            pressDefaultOrder(c, s);
        }
        c.check(mode(c).equals(mode), "the Default Order button never reached " + mode + " (at " + mode(c) + ")");
    }

    // ---- 666 --------------------------------------------------------------------------------------------------------

    static void defaults(UiCase c) throws Exception {
        // (a) the pure layout against the oracle, every mode, every prefix of class assignments, with and without a pin.
        int pure = 0;
        for (String mode : MODES) {
            for (int k = 0; k <= PARTY.size(); k++) {
                Map<String, String> known = new HashMap<>();
                for (int i = 0; i < k; i++) {
                    known.put(PARTY.get(i), CLASSES.get(PARTY.get(i)));
                }
                for (List<String> pinSet : List.of(List.of("", "", "", ""), List.of("", "", "LoBen", ""))) {
                    List<String> want = expected(mode, PARTY, pinSet, known);
                    List<String> got = c.onClient(mc -> {
                        Function<String, Object> f = n -> known.containsKey(n)
                                ? Mod.enumValue("dungeonclass.DungeonClass", known.get(n)) : null;
                        String[] l = (String[]) Mod.staticCall(FEATURE, "layout", PARTY, pinSet,
                                Mod.enumValue(MODE, mode), f);
                        List<String> r = new ArrayList<>();
                        for (String n : l) {
                            r.add(n == null ? "" : n);
                        }
                        return r;
                    });
                    c.check(got.equals(want), mode + " with " + known + " pins " + pinSet + ": " + got + ", want " + want);
                    pure++;
                }
            }
            c.check(expected(mode, PARTY, List.of(), CLASSES).equals(FULL.get(mode)),
                    "the oracle's " + mode + " full party " + expected(mode, PARTY, List.of(), CLASSES)
                            + " is not the hand-worked " + FULL.get(mode));
        }
        c.note(pure + " pure layouts matched the oracle; full party: " + FULL);

        // (b) the real editor: classes assigned one at a time move each teammate, per default.
        State st = setUp(c);
        try {
            Screen s = open(c);
            for (String mode : MODES) {
                pressUntil(c, s, mode);
                c.onClient(mc -> {
                    for (String n : PARTY) {
                        Mod.staticCall(OVERRIDES, "clear", n);
                    }
                    return null;
                });
                c.ticks(2);
                List<String> before = shown(c, s);
                c.check(before.equals(PARTY), mode + ", no classes: the editor shows " + before + ", want " + PARTY);
                Map<String, String> known = new HashMap<>();
                List<String> trail = new ArrayList<>();
                for (String n : PARTY) {
                    if (n.equals("LoAnna")) {
                        // A real right-click on her spot: the override cycles None -> Mage.
                        int spot = shown(c, s).indexOf(n);
                        clickSpot(c, s, spot, 1);
                    } else {
                        setClass(c, n, CLASSES.get(n));
                    }
                    known.put(n, CLASSES.get(n));
                    c.ticks(2);
                    List<String> got = shown(c, s);
                    List<String> want = expected(mode, PARTY, List.of(), known);
                    String clsNow = c.onClient(mc -> String.valueOf(Mod.staticCall(TRACKER, "classOf", n)));
                    c.check(clsNow.equals(CLASSES.get(n)), n + "'s class is " + clsNow + " after assigning " + CLASSES.get(n));
                    c.check(got.equals(want), mode + ": after " + n + " = " + CLASSES.get(n) + " the editor shows " + got
                            + ", want " + want);
                    trail.add(n + "=" + CLASSES.get(n) + " -> " + got);
                }
                c.check(shown(c, s).equals(FULL.get(mode)), mode + " full party " + shown(c, s));
                c.check(pins(c).stream().allMatch(String::isEmpty), mode + ": class assignment pinned someone " + pins(c));
                c.note(mode + ": " + String.join("; ", trail));
            }
        } finally {
            restore(c, st);
        }
    }

    // ---- 667 --------------------------------------------------------------------------------------------------------

    static void pinCase(UiCase c) throws Exception {
        State st = setUp(c);
        try {
            setClass(c, "LoAnna", "MAGE");
            setClass(c, "LoBen", "TANK");
            setClass(c, "LoCara", "HEALER");
            Screen s = open(c);
            pressUntil(c, s, "ODIN");
            List<String> start = shown(c, s);
            Map<String, String> known = new HashMap<>(Map.of("LoAnna", "MAGE", "LoBen", "TANK", "LoCara", "HEALER"));
            c.check(start.equals(expected("ODIN", PARTY, List.of(), known)), "start " + start);
            // Real swap: click LoCara's spot, then LoBen's.
            int a = start.indexOf("LoCara");
            int b = start.indexOf("LoBen");
            clickSpot(c, s, a, 0);
            clickSpot(c, s, b, 0);
            List<String> swapped = shown(c, s);
            c.check(swapped.get(b).equals("LoCara") && swapped.get(a).equals("LoBen"),
                    "after clicking spot " + a + " then " + b + " the editor shows " + swapped + " (from " + start + ")");
            List<String> p = pins(c);
            List<String> wantPins = new ArrayList<>(List.of("", "", "", ""));
            wantPins.set(b, "LoCara");
            wantPins.set(a, "LoBen");
            c.check(p.equals(wantPins), "pins after the swap " + p + ", want " + wantPins);
            boolean annaPinned = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(CFG), "isPinned",
                    Mod.enumValue("dungeonclass.DungeonClass", PLAYING), "LoAnna"));
            c.check(!annaPinned, "LoAnna is pinned without being moved");
            // Someone else gets a class: only the unpinned move (Archer takes Odin's top-left from LoAnna).
            setClass(c, "LoDev", "ARCHER");
            known.put("LoDev", "ARCHER");
            c.ticks(2);
            List<String> after = shown(c, s);
            List<String> want = expected("ODIN", PARTY, wantPins, known);
            c.check(after.equals(want), "after LoDev = Archer the editor shows " + after + ", want " + want);
            c.check(!after.equals(swapped), "nobody moved when LoDev got a class: " + after);
            c.check(after.get(b).equals("LoCara") && after.get(a).equals("LoBen"), "a pinned teammate moved: " + after);
            // Each other default keeps the pins too.
            for (String mode : MODES) {
                pressUntil(c, s, mode);
                List<String> got = shown(c, s);
                List<String> w = expected(mode, PARTY, wantPins, known);
                c.check(got.equals(w), mode + " with pins: " + got + ", want " + w);
            }
            pressUntil(c, s, "CLASS_ZA");
            // Persisted: reload from disk.
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                Mod.staticCall(CFG, "load");
                return null;
            });
            c.check(pins(c).equals(wantPins), "pins after a reload " + pins(c));
            c.check(mode(c).equals("CLASS_ZA"), "default after a reload " + mode(c));
            String json = Files.readString(st.path);
            c.check(json.contains("\"format\": 2") && json.contains("\"defaultOrder\": \"CLASS_ZA\""), "file: " + json);
            c.note("swap " + start + " -> " + swapped + " pins " + p + "; LoDev=Archer -> " + after
                    + "; reload kept pins and CLASS_ZA");

            // Old format: every spot saved. It must load unpinned (default order applies) and keep the old spots.
            String old = "{\"classOrders\":{\"ARCHER\":[{\"ign\":\"LoDev\"},{\"ign\":\"LoCara\"},{\"ign\":\"LoBen\"},"
                    + "{\"ign\":\"LoAnna\"}]},\"lastEditedClass\":\"ARCHER\"}";
            Files.writeString(st.path, old);
            c.onClient(mc -> {
                Mod.staticCall(CFG, "load");
                Mod.call(Mod.cfg(CFG), "save");
                return null;
            });
            String migrated = Files.readString(st.path);
            c.check(pins(c).stream().allMatch(String::isEmpty), "an old file loaded as pins " + pins(c));
            c.check(mode(c).equals("ODIN"), "an old file's default is " + mode(c) + ", want ODIN");
            c.check(migrated.contains("legacyClassOrders") && migrated.contains("LoDev"), "old spots lost: " + migrated);
            c.onClient(mc -> {
                Mod.staticCall(CFG, "load");
                return null;
            });
            c.check(pins(c).stream().allMatch(String::isEmpty), "the legacy block came back as pins " + pins(c));
            c.note("old-format file: loaded unpinned on ODIN, spots kept under legacyClassOrders");
        } finally {
            restore(c, st);
        }
    }

    // ---- 668 --------------------------------------------------------------------------------------------------------

    static void reset(UiCase c) throws Exception {
        State st = setUp(c);
        try {
            CLASSES.forEach((n, k) -> setClass(c, n, k));
            Screen s = open(c);
            pressUntil(c, s, "CLASS_AZ");
            List<String> def = shown(c, s);
            c.check(def.equals(FULL.get("CLASS_AZ")), "A-Z default " + def);
            // Swap spots 0 and 3, then 1 and 2: everybody pinned, layout reversed.
            clickSpot(c, s, 0, 0);
            clickSpot(c, s, 3, 0);
            clickSpot(c, s, 1, 0);
            clickSpot(c, s, 2, 0);
            List<String> moved = shown(c, s);
            List<String> rev = new ArrayList<>(def);
            java.util.Collections.reverse(rev);
            c.check(moved.equals(rev), "after two swaps " + moved + ", want " + rev);
            c.check(pins(c).equals(rev), "pins " + pins(c));
            // Shift-click unpins one: two teammates swap back where the default puts them only if both are unpinned,
            // so unpin spot 0's and spot 3's names and check they return.
            // Fabric's pressMouse builds MouseButtonInfo(button, 0) - no modifiers, even with Shift held (see
            // BazaarReskinCases) - so the shift-clicks go to the screen itself, in its layout coordinates (Auto Scale's
            // mouse mixin converts before mouseClicked, so this is what a real click arrives as).
            shiftClickSpot(c, s, 0);
            shiftClickSpot(c, s, 3);
            c.ticks(2);
            List<String> half = shown(c, s);
            List<String> halfPins = new ArrayList<>(List.of("", rev.get(1), rev.get(2), ""));
            c.check(pins(c).equals(halfPins), "pins after two shift-clicks " + pins(c) + ", want " + halfPins);
            List<String> wantHalf = expected("CLASS_AZ", PARTY, halfPins, CLASSES);
            c.check(half.equals(wantHalf), "after unpinning spots 0 and 3 " + half + ", want " + wantHalf);
            c.check(half.get(0).equals(def.get(0)) && half.get(3).equals(def.get(3)),
                    "unpinned teammates did not go back to their default spots: " + half);
            // Reset to Default: everything back.
            InvBatchCases.clickWidget(c, s, InvBatchCases.widget(c, s, "Reset to Default"));
            List<String> back = shown(c, s);
            c.check(back.equals(def), "after Reset to Default " + back + ", want " + def);
            c.check(pins(c).stream().allMatch(String::isEmpty), "pins left after Reset " + pins(c));
            c.note("A-Z " + def + " -> swapped " + moved + " -> shift-click x2 " + half + " -> Reset " + back);
        } finally {
            restore(c, st);
        }
    }

    // ---- 669 --------------------------------------------------------------------------------------------------------

    static void shots(UiCase c) throws Exception {
        State st = setUp(c);
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        try {
            CLASSES.forEach((n, k) -> setClass(c, n, k));
            c.onClient(mc -> {
                mc.options.guiScale().set(2);
                mc.resizeGui();
                return null;
            });
            Screen s = open(c);
            pressUntil(c, s, "ODIN");
            shot(c, "odin-default");
            pressUntil(c, s, "CLASS_ZA");
            shot(c, "za-default");
            pressUntil(c, s, "ODIN");
            clickSpot(c, s, 0, 0);
            clickSpot(c, s, 1, 0);
            c.check(pins(c).stream().filter(x -> !x.isEmpty()).count() == 2, "the swap pinned " + pins(c));
            InvBatchCases.cursor(c, s, 2, 2);
            shot(c, "odin-pinned");
            int gs = c.onClient(mc -> mc.getWindow().getGuiScale());
            c.check(gs == 2, "pictures at GUI " + gs);
        } finally {
            c.onClient(mc -> {
                mc.options.guiScale().set(oldGui);
                mc.resizeGui();
                return null;
            });
            restore(c, st);
        }
    }

    private static void shot(UiCase c, String suffix) throws Exception {
        c.ticks(3);
        String name = c.name() + "-" + suffix;
        Path taken = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(name));
        Path kept = dev.testkit.harness.Report.screenshot(name, taken);
        c.note("picture " + name + " -> " + (kept != null ? kept : taken));
        String dir = System.getenv("TESTKIT_LEAPORDER_SHOTS");
        if (dir != null && !dir.isBlank()) {
            Path out = Path.of(dir).resolve(name + "-" + (Mod.isCheat() ? "cheat" : "legit") + ".png");
            Files.createDirectories(out.getParent());
            Files.copy(taken, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static String describe(List<String> l) {
        return Arrays.toString(l.toArray());
    }
}
