package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * killer560's 2026-10-15 Dungeon Map list (mod branch map-overhaul-1015), the menu and world half. The sim half (what the
 * map draws against a dungeon map item) is {@code SimMapItemTests}, 1015-sim-map-item-only.
 *
 * <ul>
 *   <li><b>1015-ui-switch-align</b> - "Make the name holding leap button all the way to the right like they should be.
 *       Check all areas of the mod for checkmarks like that being off". Every drawer of every tab, as built and with each
 *       of its switches pressed once: a switch, pill or value row that ends short of the drawer's right edge must have
 *       another row beside it. On main the Dungeon Map's Names Holding Leap (and Door Keys' Show Tracer with its tracer
 *       off, and others) stopped halfway with nothing to its right.</li>
 *   <li><b>1015-ui-map-tab</b> - the Dungeon Map drawer: no Room Size; Player Heads only under Show Teammates when that
 *       is on; Player Marker Size, Custom Checkmark Sprite and New Custom Scheme present; no room colour row unless the
 *       theme is Custom (then only a scheme pill and Edit Scheme); Funny Map only on the cheat jar, at the bottom under a
 *       red header, Show Mimic Room and Mimic Room Color only with it on; a fresh config has Mark Reported Rooms and
 *       Color Rooms by Type ON and Funny Map OFF.</li>
 *   <li><b>1015-ui-map-themes</b> - Map Theme steps through Real Map, Amber, Dark, Light, Ocean, Match Menu Theme and
 *       Custom; each preset paints a palette other than Real Map's; Match Menu Theme follows the menu's theme when that
 *       changes; New Custom Scheme opens the scheme editor on a new scheme; a colour and a name set in it are on disk
 *       (re-read with load()), the map paints with them, and the editor's sample map draws that colour; a second scheme
 *       can be made, both are kept, and deleting one leaves the other.</li>
 *   <li><b>1015-ui-door-key-style</b> - Door Keys' Pickup Range: Radius draws the circle at the key's height (not the floor under it), Sphere draws a shaded
 *       ball centred on the key's stand with the pickup radius; the Sphere picture has far more changed pixels around the
 *       key than the Radius one.</li>
 * </ul>
 */
final class MapOverhaulCases {

    private static final String MAP = "livemap.LiveMapConfig";
    private static final String KEYS = "doorkeys.DoorKeysConfig";

    private MapOverhaulCases() {
    }

    // =================================================================================================================
    // 1015-ui-switch-align
    // =================================================================================================================

    static void switchAlign(UiCase c, Deny deny) {
        int topCount = c.onClient(mc -> {
            try {
                return new ModScreenDriver(mc).topTabs().size();
            } catch (Throwable t) {
                return -1;
            }
        });
        c.check(topCount > 0, "could not open gui.ModScreen");
        Set<String> found = new LinkedHashSet<>();
        int[] n = new int[3]; // layouts, switch-type rows checked, rows narrower than the drawer
        for (int i = 0; i < topCount; i++) {
            int top = i;
            c.onClient(mc -> {
                ModScreenDriver d;
                try {
                    d = new ModScreenDriver(mc);
                } catch (Throwable t) {
                    c.problem("could not open gui.ModScreen: " + UiCase.describe(t));
                    return null;
                }
                Object topTab = d.topTabs().get(top);
                List<Object> leaves = new ArrayList<>();
                if (d.isFolder(topTab)) {
                    collectLeaves(d, topTab, leaves);
                } else {
                    leaves.add(topTab);
                }
                for (Object leaf : leaves) {
                    String where = R.get(topTab, "name") + " > " + R.get(leaf, "name");
                    try {
                        d.select(top);
                        d.open(leaf);
                        d.rebuild();
                        sweepLeaf(d, where, deny, found, n);
                    } catch (Throwable t) {
                        c.problem("'" + where + "': " + UiCase.describe(t));
                    }
                }
                d.select(0);
                return null;
            });
        }
        c.note(String.format(Locale.ROOT, "switch alignment: %d layouts, %d switch/pill/value rows checked, %d of them"
                + " narrower than the drawer (each must have a row beside it); %d lone row(s)", n[0], n[1], n[2],
                found.size()));
        for (String f : found) {
            c.problem("row stops short of the drawer's right edge with nothing beside it: " + f);
        }
        c.check(n[0] > 100, "only " + n[0] + " layouts checked - the sweep did not reach the menu");
        c.check(n[1] > 1000, "only " + n[1] + " switch/pill/value rows seen - the check had nothing to judge");
    }

    private static void collectLeaves(ModScreenDriver d, Object folder, List<Object> out) {
        for (Object sub : d.subTabs(folder)) {
            if (d.isFolder(sub)) {
                collectLeaves(d, sub, out);
            } else {
                out.add(sub);
            }
        }
    }

    private static void sweepLeaf(ModScreenDriver d, String where, Deny deny, Set<String> found, int[] n)
            throws Throwable {
        checkAlign(d, where, "", found, n);
        List<String> seen = new ArrayList<>();
        for (int guard = 0; guard < 60; guard++) {
            AbstractWidget target = null;
            for (AbstractWidget w : d.content()) {
                var m = TabSweep.TOGGLE.matcher(ModScreenDriver.label(w));
                if (d.isSettingsButton(w) && m.matches() && !seen.contains(m.group(1))) {
                    target = w;
                    break;
                }
            }
            if (target == null) {
                return;
            }
            String label = ModScreenDriver.label(target);
            var m = TabSweep.TOGGLE.matcher(label);
            m.matches();
            String name = m.group(1);
            seen.add(name);
            if (deny.button(label) != null) {
                continue;
            }
            ModScreenDriver.press(target);
            d.rebuild();
            checkAlign(d, where, " (with " + name + " flipped from " + m.group(2) + ")", found, n);
            AbstractWidget again = null;
            for (AbstractWidget w : d.content()) {
                var mm = TabSweep.TOGGLE.matcher(ModScreenDriver.label(w));
                if (d.isSettingsButton(w) && mm.matches() && mm.group(1).equals(name)) {
                    again = w;
                }
            }
            ModScreenDriver.press(again != null ? again : target);
            d.rebuild();
        }
    }

    /** Every switch / pill / value row narrower than the drawer must share its height band with another row. */
    private static void checkAlign(ModScreenDriver d, String where, String state, Set<String> found, int[] n) {
        n[0]++;
        int contentX = (Integer) R.get(d.screen, "contentX");
        int contentW = (Integer) R.get(d.screen, "contentW");
        int right = contentX + contentW;
        List<AbstractWidget> ws = d.content();
        for (AbstractWidget w : ws) {
            if (!w.visible || !d.isSettingsButton(w)) {
                continue;
            }
            String look = String.valueOf(Mod.call(Mod.call(w, "parts"), "look"));
            if (!look.equals("SWITCH") && !look.equals("PILL") && !look.equals("CHIP")) {
                continue;
            }
            n[1]++;
            if (w.getX() + w.getWidth() >= right - 1) {
                continue;
            }
            n[2]++;
            boolean partner = false;
            for (AbstractWidget o : ws) {
                if (o != w && o.visible && o.getY() < w.getY() + w.getHeight() && o.getY() + o.getHeight() > w.getY()) {
                    partner = true;
                    break;
                }
            }
            if (!partner) {
                found.add(where + state + ": '" + ModScreenDriver.label(w) + "' ends at x " + (w.getX() + w.getWidth())
                        + " of " + right);
            }
        }
    }

    // =================================================================================================================
    // 1015-ui-map-tab
    // =================================================================================================================

    static void mapTab(UiCase c) throws Exception {
        Map<Path, byte[]> saved = DungeonB2Cases.snapshotConfig(c, MAP);
        try {
            // A fresh config: the file gone, then read.
            Path file = c.onClient(mc -> (Path) R.getStatic(R.cls(MAP), "CONFIG_PATH"));
            Files.deleteIfExists(file);
            boolean[] fresh = c.onClient(mc -> {
                Mod.staticCall(MAP, "load");
                Object cfg = Mod.cfg(MAP);
                return new boolean[]{(Boolean) Mod.call(cfg, "isMarkReportedRooms"), (Boolean) Mod.call(cfg, "isColourByType"),
                        (Boolean) Mod.call(cfg, "isFunnyMapRaw"), (Boolean) Mod.call(cfg, "isCustomCheckmarks")};
            });
            c.note("fresh config: Mark Reported Rooms " + fresh[0] + ", Color Rooms by Type " + fresh[1] + ", Funny Map "
                    + fresh[2] + ", Custom Checkmark Sprite " + fresh[3]);
            c.check(fresh[0] && fresh[1], "a fresh config must have Mark Reported Rooms and Color Rooms by Type ON");
            c.check(!fresh[2] && !fresh[3], "a fresh config must have Funny Map and Custom Checkmark Sprite OFF");

            c.onClient(mc -> {
                Object cfg = Mod.cfg(MAP);
                Mod.call(cfg, "setEnabled", true);
                Mod.call(cfg, "setShowTeammates", true);
                Mod.call(cfg, "setFunnyMap", false);
                return null;
            });
            List<String> on = drawerLabels(c, "Dungeon Map");
            c.note("Dungeon Map drawer (Show Teammates ON, Funny Map OFF): " + on);
            c.check(!contains(on, "Room Size"), "the Room Size slider is still there");
            c.check(indexOf(on, "Player Heads") == indexOf(on, "Show Teammates") + 1,
                    "Player Heads is not directly under Show Teammates while it is on");
            for (String want : new String[]{"Player Marker Size", "Custom Checkmark Sprite", "Darken Unexplored",
                    "Map Theme", "New Custom Scheme", "Names Holding Leap", "Recolour by Class"}) {
                c.check(contains(on, want), "no '" + want + "' row");
            }
            for (String gone : new String[]{"Room Colours", "Normal", "Save Theme", "Checkmark Sprites", "Icon Scale",
                    "Mimic Room Show/Hide", "Player-Head Class Recolor"}) {
                c.check(!containsExact(on, gone), "'" + gone + "' is still a row of the tab");
            }
            c.check(contains(on, "Funny Map") == Mod.isCheat(), "Funny Map shown on the " + (Mod.isCheat() ? "cheat" : "legit")
                    + " jar: " + contains(on, "Funny Map"));
            c.check(!contains(on, "Show Mimic Room"), "Show Mimic Room shown with Funny Map off");
            if (Mod.isCheat()) {
                c.check(indexOf(on, "Funny Map") == on.size() - 1, "Funny Map is not the last row (cheat block at the bottom)");
            }

            c.onClient(mc -> {
                Mod.call(Mod.cfg(MAP), "setShowTeammates", false);
                Mod.call(Mod.cfg(MAP), "setFunnyMap", true);
                return null;
            });
            List<String> off = drawerLabels(c, "Dungeon Map");
            c.note("Dungeon Map drawer (Show Teammates OFF, Funny Map ON): " + off);
            c.check(!contains(off, "Player Heads"), "Player Heads shown with Show Teammates off");
            c.check(contains(off, "Show Mimic Room") == Mod.isCheat() && contains(off, "Mimic Room Colour") == Mod.isCheat(),
                    "Show Mimic Room / Mimic Room Color must be there exactly on the cheat jar with Funny Map on");
            if (!Mod.isCheat()) {
                boolean funny = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(MAP), "isFunnyMap"));
                c.check(!funny, "the legit jar reports Funny Map on (a saved true must do nothing there)");
            }

            c.onClient(mc -> {
                Mod.call(Mod.cfg(MAP), "setMapThemeName", "CUSTOM");
                return null;
            });
            List<String> custom = drawerLabels(c, "Dungeon Map");
            c.note("Dungeon Map drawer with the Custom theme: " + custom);
            c.check(contains(custom, "Scheme") && contains(custom, "Edit Scheme"), "Custom shows no scheme pill / Edit Scheme");
            c.check(!containsExact(custom, "Normal"), "room colors are rows of the tab under Custom - they belong in the editor");
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
            DungeonB2Cases.restoreConfig(c, saved, MAP);
        }
    }

    private static List<String> drawerLabels(UiCase c, String tab) {
        return c.onClient(mc -> {
            try {
                ModScreenDriver d = new ModScreenDriver(mc);
                for (Object leaf : d.leaves()) {
                    if (tab.equals(R.get(leaf, "name"))) {
                        d.open(leaf);
                        d.rebuild();
                        List<AbstractWidget> ws = new ArrayList<>(d.content());
                        ws.sort((a, b) -> a.getY() != b.getY() ? Integer.compare(a.getY(), b.getY())
                                : Integer.compare(a.getX(), b.getX()));
                        List<String> out = new ArrayList<>();
                        for (AbstractWidget w : ws) {
                            if (d.isSettingsButton(w) || w instanceof net.minecraft.client.gui.components.AbstractSliderButton) {
                                out.add(ModScreenDriver.label(w));
                            }
                        }
                        return out;
                    }
                }
                throw new AssertionError("no '" + tab + "' tab");
            } catch (Throwable e) {
                throw new AssertionError(e);
            }
        });
    }

    private static String nameOf(String label) {
        int colon = label.indexOf(':');
        return (colon > 0 ? label.substring(0, colon) : label).trim();
    }

    private static boolean contains(List<String> labels, String name) {
        return indexOf(labels, name) >= 0;
    }

    private static boolean containsExact(List<String> labels, String name) {
        for (String l : labels) {
            if (nameOf(l).equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static int indexOf(List<String> labels, String name) {
        for (int i = 0; i < labels.size(); i++) {
            if (nameOf(labels.get(i)).equals(name)) {
                return i;
            }
        }
        return -1;
    }

    // =================================================================================================================
    // 1015-ui-map-themes
    // =================================================================================================================

    static void mapThemes(UiCase c) throws Exception {
        Map<Path, byte[]> saved = DungeonB2Cases.snapshotConfig(c, MAP);
        Map<Path, byte[]> menuSaved = DungeonB2Cases.snapshotConfig(c, "gui.MenuConfig");
        try {
            c.onClient(mc -> {
                Mod.call(Mod.cfg(MAP), "setMapThemeName", "REAL");
                return null;
            });
            String[] themes = {"REAL", "AMBER", "DARK", "LIGHT", "OCEAN", "MATCH", "CUSTOM"};
            int[] real = palette(c);
            for (String t : themes) {
                c.onClient(mc -> {
                    Mod.call(Mod.cfg(MAP), "setMapThemeName", t);
                    return null;
                });
                int[] p = palette(c);
                String got = c.onClient(mc -> String.valueOf(Mod.call(Mod.cfg(MAP), "getMapTheme")));
                c.note(t + " -> " + got + ", Normal #" + hex(p[0]) + ", Entrance #" + hex(p[1]) + ", Unexplored #" + hex(p[8]));
                c.check(got.equals(t), "Map Theme " + t + " reads back as " + got);
                if (!t.equals("REAL") && !t.equals("CUSTOM") && !t.equals("MATCH")) {
                    c.check(!java.util.Arrays.equals(p, real), t + " paints Real Map's palette");
                }
            }
            // The theme pill steps through all seven, in order, and wraps.
            List<String> stepped = c.onClient(mc -> {
                Object cfg = Mod.cfg(MAP);
                Mod.call(cfg, "setMapThemeName", "REAL");
                List<String> out = new ArrayList<>();
                Class<?> sbw = Mod.cls("gui.SettingsButtonWidget");
                for (int i = 0; i < 7; i++) {
                    Object cur = Mod.call(cfg, "getMapTheme");
                    try {
                        Object next = sbw.getMethod("step", Enum.class, int.class).invoke(null, cur, 1);
                        Mod.call(cfg, "setMapTheme", next);
                    } catch (ReflectiveOperationException e) {
                        throw new AssertionError(e);
                    }
                    out.add(String.valueOf(Mod.call(cfg, "getMapTheme")));
                }
                return out;
            });
            c.note("stepping forward from Real Map: " + stepped);
            c.check(stepped.equals(List.of("AMBER", "DARK", "LIGHT", "OCEAN", "MATCH", "CUSTOM", "REAL")),
                    "Map Theme does not step Real > Amber > Dark > Light > Ocean > Match > Custom > Real");

            // Match Menu Theme follows the menu.
            int[][] match = new int[2][];
            for (int k = 0; k < 2; k++) {
                String menu = k == 0 ? "DARK" : "OCEAN";
                c.onClient(mc -> {
                    Object mcfg = Mod.cfg("gui.MenuConfig");
                    Mod.call(mcfg, "setTheme", Mod.enumValue("gui.MenuTheme", menu));
                    Mod.call(Mod.cfg(MAP), "setMapThemeName", "MATCH");
                    return null;
                });
                match[k] = palette(c);
                c.onClient(mc -> {
                    Mod.call(Mod.cfg(MAP), "setMapThemeName", menu);
                    return null;
                });
                int[] preset = palette(c);
                c.check(java.util.Arrays.equals(match[k], preset), "Match Menu Theme with the menu on " + menu
                        + " does not paint the " + menu + " map palette");
            }
            c.check(!java.util.Arrays.equals(match[0], match[1]), "Match Menu Theme did not change when the menu's did");

            // New Custom Scheme opens the editor on a new scheme; edits are saved.
            c.onClient(mc -> {
                Mod.call(Mod.cfg(MAP), "setMapThemeName", "REAL");
                return null;
            });
            Screen editor = pressNewScheme(c);
            c.check(editor != null && editor.getClass().getSimpleName().equals("MapSchemeEditorScreen"),
                    "New Custom Scheme did not open the scheme editor (screen " + (editor == null ? "none"
                    : editor.getClass().getSimpleName()) + ")");
            int idx = editor == null ? -1 : c.onClient(mc -> (Integer) Mod.call(editor, "schemeIndex"));
            final int pick = 0xFF12AB34;
            c.onClient(mc -> {
                Object cfg = Mod.cfg(MAP);
                Mod.call(cfg, "setSchemeColor", idx, 0, pick);
                Mod.call(cfg, "renameScheme", idx, "Testkit Green");
                Mod.call(cfg, "save");
                Mod.staticCall(MAP, "load");
                return null;
            });
            Object[] after = c.onClient(mc -> {
                Object cfg = Mod.cfg(MAP);
                List<?> schemes = (List<?>) Mod.call(cfg, "getSchemes");
                Object s = schemes.get(idx);
                return new Object[]{String.valueOf(Mod.call(cfg, "getMapTheme")), R.get(s, "name"),
                        ((int[]) R.get(s, "colors"))[0], ((int[]) Mod.call(cfg, "palette"))[0], schemes.size()};
            });
            c.note("after save and load: theme " + after[0] + ", scheme '" + after[1] + "' Normal #" + hex((Integer) after[2])
                    + ", map paints Normal #" + hex((Integer) after[3]) + ", " + after[4] + " scheme(s)");
            c.check("CUSTOM".equals(after[0]), "the theme is not Custom after New Custom Scheme");
            c.check("Testkit Green".equals(after[1]), "the scheme's name was not saved");
            c.check((Integer) after[2] == pick && (Integer) after[3] == pick, "the scheme's Normal color was not saved or not painted");
            // The editor's sample map draws the scheme's colour.
            boolean drawn = c.onClient(mc -> {
                try {
                    Screen s = (Screen) Mod.cls("livemap.MapSchemeEditorScreen").getConstructor(Screen.class, int.class)
                            .newInstance(null, idx);
                    McCompat.setScreen(mc, s);
                    GuiRenderState state = new GuiRenderState();
                    GuiGraphicsExtractor g = new GuiGraphicsExtractor(mc, state, -1, -1);
                    s.extractRenderStateWithTooltipAndSubtitles(g, -1, -1, 0f);
                    boolean[] hit = {false};
                    state.forEachElement(el -> {
                        for (Object part : parts(el)) {
                            try {
                                int col = (Integer) part.getClass().getMethod("col1").invoke(part);
                                hit[0] |= col == pick;
                            } catch (ReflectiveOperationException ignored) {
                                // not a coloured rectangle
                            }
                        }
                    }, GuiRenderState.TraverseRange.ALL);
                    return hit[0];
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            c.check(drawn, "the scheme editor's sample map never draws the scheme's Normal color #" + hex(pick));
            // A second scheme; both kept; delete the first, the second stays.
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                Object cfg = Mod.cfg(MAP);
                Mod.call(cfg, "newScheme");
                Mod.call(cfg, "save");
                Mod.staticCall(MAP, "load");
                return null;
            });
            int count = c.onClient(mc -> ((List<?>) Mod.call(Mod.cfg(MAP), "getSchemes")).size());
            c.check(count == idx + 2, "a second scheme was not kept (" + count + " scheme(s))");
            String left = c.onClient(mc -> {
                Object cfg = Mod.cfg(MAP);
                Mod.call(cfg, "deleteScheme", idx);
                Mod.call(cfg, "save");
                Mod.staticCall(MAP, "load");
                List<?> schemes = (List<?>) Mod.call(cfg = Mod.cfg(MAP), "getSchemes");
                return schemes.size() + " " + R.get(schemes.get(schemes.size() - 1), "name");
            });
            c.note("after deleting 'Testkit Green': " + left);
            c.check(!left.contains("Testkit Green") && left.startsWith(String.valueOf(count - 1)),
                    "deleting a scheme did not leave the other one: " + left);
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
            DungeonB2Cases.restoreConfig(c, saved, MAP);
            DungeonB2Cases.restoreConfig(c, menuSaved, "gui.MenuConfig");
        }
    }

    private static List<Object> parts(Object el) {
        List<Object> out = new ArrayList<>();
        if (el.getClass().getName().equals("com.killer560.hub.hud.GuiRects$Batch")) {
            try {
                java.lang.reflect.Method m = el.getClass().getDeclaredMethod("parts");
                m.setAccessible(true);
                out.addAll(java.util.Arrays.asList((Object[]) m.invoke(el)));
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        } else {
            out.add(el);
        }
        return out;
    }

    private static Screen pressNewScheme(UiCase c) {
        c.onClient(mc -> {
            try {
                ModScreenDriver d = new ModScreenDriver(mc);
                for (Object leaf : d.leaves()) {
                    if ("Dungeon Map".equals(R.get(leaf, "name"))) {
                        d.open(leaf);
                        McCompat.setScreen(mc, d.screen);
                        d.rebuild();
                        for (AbstractWidget w : d.content()) {
                            if (ModScreenDriver.label(w).equals("New Custom Scheme")) {
                                ModScreenDriver.press(w);
                                return null;
                            }
                        }
                    }
                }
                throw new AssertionError("no New Custom Scheme button");
            } catch (Throwable e) {
                throw new AssertionError(e);
            }
        });
        c.ticks(3);
        return c.onClient(McCompat::screen);
    }

    private static int[] palette(UiCase c) {
        return c.onClient(mc -> ((int[]) Mod.call(Mod.cfg(MAP), "palette")).clone());
    }

    private static String hex(int argb) {
        return String.format(Locale.ROOT, "%08X", argb);
    }

    // =================================================================================================================
    // 1015-ui-door-key-style
    // =================================================================================================================

    static void doorKeyStyle(UiCase c) throws Exception {
        Map<Path, byte[]> saved = DungeonB2Cases.snapshotConfig(c, KEYS);
        int gui = c.onClient(mc -> mc.options.guiScale().get());
        try {
            command(c, "tp @a 270.5 116 259.5 0 50");
            c.ticks(20);
            command(c, "fill 255 99 255 285 99 285 minecraft:stone");
            command(c, "fill 255 100 255 285 110 285 minecraft:air");
            command(c, "kill @e[tag=mokey]");
            command(c, "summon armor_stand 270.5 101.3 270.5 {Tags:[\"mokey\"],NoGravity:1b,Invisible:1b}");
            server(c, s -> {
                for (ArmorStand st : s.overworld().getEntities(EntityTypeTest.forClass(ArmorStand.class),
                        e -> e.entityTags().contains("mokey"))) {
                    st.setCustomName(Component.literal("Blood Key"));
                    st.setCustomNameVisible(true);
                }
            });
            c.onClient(mc -> {
                Object k = Mod.cfg(KEYS);
                Mod.call(k, "setEnabled", true);
                Mod.call(k, "setHighlightWither", true);
                Mod.call(k, "setHighlightBlood", true);
                Mod.call(k, "setShowTracer", false);
                Mod.call(k, "setThroughWalls", false);
                Mod.call(k, "setRangeStyle", 0);
                Mod.staticCall("secrets.DungeonState", "setRoomSim", true);
                return null;
            });
            c.ticks(80);   // the floor's chunks render (a fresh section reads as sky for a while)
            Path off = null;
            Path radius = shotAndStyle(c, "radius", "radius");
            double[] sphere0 = c.onClient(mc -> (double[]) Mod.staticCall("doorkeys.DoorKeysFeature", "pickupSphere"));
            // killer560, 2026-10-15: "Radius is just a circle drawn at its height" - the key floats 1.3 above the floor
            // top (y 100), and the circle must be at the key's y 101.3, not on the floor.
            double[] ring = c.onClient(mc -> (double[]) Mod.staticCall("doorkeys.DoorKeysFeature", "pickupRing"));
            c.note("radius circle " + java.util.Arrays.toString(ring));
            c.check(ring != null && Math.abs(ring[1] - 101.3) < 1e-6 && Math.abs(ring[0] - 270.5) < 1e-6
                    && Math.abs(ring[2] - 270.5) < 1e-6, "the Radius circle is not centred on the key at its height (y 101.3)");
            c.onClient(mc -> {
                Mod.call(Mod.cfg(KEYS), "setRangeStyle", 1);
                return null;
            });
            Path sphere = shotAndStyle(c, "sphere", "sphere");
            double[] sp = c.onClient(mc -> (double[]) Mod.staticCall("doorkeys.DoorKeysFeature", "pickupSphere"));
            double range = c.onClient(mc -> (Double) Mod.call(Mod.cfg("autosecret.AutoSecretConfig"), "keyPickupRange"));
            c.note("sphere " + java.util.Arrays.toString(sp) + " (pickup range " + range + "; before switching "
                    + java.util.Arrays.toString(sphere0) + ")");
            c.check(sp != null && Math.abs(sp[0] - 270.5) < 1e-6 && Math.abs(sp[1] - 101.3) < 1e-6
                    && Math.abs(sp[2] - 270.5) < 1e-6, "the sphere is not centred on the key's stand");
            c.check(sp != null && Math.abs(sp[3] - range) < 1e-9, "the sphere's radius is not the pickup range");
            c.onClient(mc -> {
                Mod.call(Mod.cfg(KEYS), "setEnabled", false);
                return null;
            });
            c.ticks(5);
            lookAt(c, new Vec3(270.5, 101.3, 270.5));
            off = DungeonB2Cases.shot(c, "door-key-off");
            int radiusPx = changed(radius, off);
            int spherePx = changed(sphere, off);
            c.note("pixels changed against the Door-Keys-off frame: Radius " + radiusPx + ", Sphere " + spherePx);
            c.check(radiusPx >= 100, "the Radius circle is barely in the picture (" + radiusPx + " px)");
            c.check(spherePx > radiusPx * 3, "the Sphere does not shade much more than the Radius circle draws ("
                    + spherePx + " vs " + radiusPx + " px)");
        } finally {
            command(c, "kill @e[tag=mokey]");
            c.onClient(mc -> {
                Mod.staticCall("secrets.DungeonState", "setRoomSim", false);
                mc.options.guiScale().set(gui);
                mc.resizeGui();
                return null;
            });
            DungeonB2Cases.restoreConfig(c, saved, KEYS);
        }
    }

    private static Path shotAndStyle(UiCase c, String suffix, String want) {
        lookAt(c, new Vec3(270.5, 101.3, 270.5));
        Path p = DungeonB2Cases.shot(c, "door-key-" + suffix);
        String style = c.onClient(mc -> (String) Mod.staticCall("doorkeys.DoorKeysFeature", "lastDrawnStyle"));
        c.note(suffix + ": the last frame drew style '" + style + "'");
        c.check(want.equals(style), "Pickup Range " + suffix + " drew '" + style + "'");
        return p;
    }

    /** Pixels whose summed RGB differs by more than 30 between two pictures. */
    private static int changed(Path a, Path b) {
        try {
            java.awt.image.BufferedImage ia = javax.imageio.ImageIO.read(a.toFile());
            java.awt.image.BufferedImage ib = javax.imageio.ImageIO.read(b.toFile());
            int n = 0;
            for (int y = 0; y < Math.min(ia.getHeight(), ib.getHeight()); y++) {
                for (int x = 0; x < Math.min(ia.getWidth(), ib.getWidth()); x++) {
                    int p = ia.getRGB(x, y);
                    int q = ib.getRGB(x, y);
                    int d = Math.abs(((p >> 16) & 255) - ((q >> 16) & 255)) + Math.abs(((p >> 8) & 255) - ((q >> 8) & 255))
                            + Math.abs((p & 255) - (q & 255));
                    if (d > 30) {
                        n++;
                    }
                }
            }
            return n;
        } catch (java.io.IOException e) {
            return -1;
        }
    }

    private static void lookAt(UiCase c, Vec3 target) {
        c.onClient(mc -> {
            Vec3 d = target.subtract(mc.player.getEyePosition());
            mc.player.setYRot((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
            mc.player.setXRot((float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z))));
            return null;
        });
        c.ticks(2);
    }

    private static void command(UiCase c, String cmd) {
        server(c, s -> s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(), cmd));
    }

    private static void server(UiCase c, java.util.function.Consumer<net.minecraft.server.MinecraftServer> task) {
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        c.onClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                try {
                    task.accept(server);
                } finally {
                    done.set(true);
                }
            });
            return null;
        });
        c.ctx().waitFor(mc -> done.get(), 100);
    }
}
