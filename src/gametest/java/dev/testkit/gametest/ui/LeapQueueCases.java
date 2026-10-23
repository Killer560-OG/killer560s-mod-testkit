package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.Report;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * killer560's 2026-10-15 to-do items 1-3 (mod branch leap-queue-1015).
 * <ul>
 *   <li>1071: {@code /leaporder}, {@code /class}, {@code /killer560 leaporder} and {@code /killer560 class} each open a
 *       LeapOrderScreen, typed as a real player types them.</li>
 *   <li>1072: the Class Overrides setting is gone (no tab, no class) and its saved picks are migrated into the Leap Order
 *       data: an old classoverrides file in either format is read by the config load, its picks are now in
 *       {@code killer560smod-leapmenu.json} and survive a second load, the old file is deleted, and a pick already in the
 *       Leap Order wins over the old file's. The Class Selection Overlay switch is in the Leap Menu tab with an Edit
 *       Classes button that opens the editor.</li>
 *   <li>1073: the editor's page 1 fills the window (five cards edge to edge, tall, in a row, Done under them) and the
 *       editor's four spots span the window, at the test window and at 1920x1080 GUI 2; the cards' pixels are the card
 *       gradient of the menu theme, under Amber and under Light (and the two differ).</li>
 *   <li>1074: every built-in Party Finder style is selectable with the real "Party Finder style" button (left click
 *       forward, right click back) and its preview line for killer560 is the hand-worked text, Style 1 and 2 unchanged.</li>
 *   <li>1075: the custom style editor: placeholder buttons add themselves to the text, Save Style / Saved Style /
 *       Delete keep named styles, they survive a reload from disk, Load Built-in copies a built-in template in, and a
 *       typo'd placeholder is flagged.</li>
 * </ul>
 */
final class LeapQueueCases {

    private LeapQueueCases() {
    }

    private static final String LEAP_CFG = "leapmenu.LeapMenuConfig";
    private static final String OVR = "dungeonclass.ClassOverrides";
    private static final String CLASS = "dungeonclass.DungeonClass";
    private static final String PF = "partyfinder.PartyFinderOverlayConfig";
    private static final String PF_OVERLAY = "partyfinder.PartyFinderOverlay";
    private static final String MODE = PF + "$CompactMode";

    // ---- shared -------------------------------------------------------------------------------------------------------

    private static Screen screen(UiCase c) {
        return c.onClient(McCompat::screen);
    }

    private static String screenName(UiCase c) {
        Screen s = screen(c);
        return s == null ? "(none)" : s.getClass().getSimpleName();
    }

    private static List<AbstractWidget> widgets(UiCase c, Screen s) {
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

    private static AbstractWidget byLabel(List<AbstractWidget> ws, String label) {
        for (AbstractWidget w : ws) {
            if (ModScreenDriver.label(w).equals(label)) {
                return w;
            }
        }
        return null;
    }

    private static AbstractWidget byPrefix(List<AbstractWidget> ws, String prefix) {
        for (AbstractWidget w : ws) {
            if (ModScreenDriver.label(w).startsWith(prefix)) {
                return w;
            }
        }
        return null;
    }

    private static boolean press(UiCase c, AbstractWidget w) {
        return c.onClient(mc -> ModScreenDriver.press(w));
    }

    private static boolean rightClick(UiCase c, AbstractWidget w) {
        return c.onClient(mc -> {
            double x = w.getX() + w.getWidth() / 2.0;
            double y = w.getY() + Math.min(w.getHeight() / 2.0, 5);
            boolean took = w.mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(1, 0)), false);
            w.mouseReleased(new MouseButtonEvent(x, y, new MouseButtonInfo(1, 0)));
            return took;
        });
    }

    // ---- 1071 ---------------------------------------------------------------------------------------------------------

    static void commands(UiCase c) throws Exception {
        List<String> typed = List.of("leaporder", "class", "killer560 leaporder", "killer560 class");
        List<String> seen = new ArrayList<>();
        Class<?> first = null;
        for (String cmd : typed) {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
            c.ticks(2);
            c.onClient(mc -> {
                mc.player.connection.sendCommand(cmd);
                return null;
            });
            c.ticks(6);
            Screen s = screen(c);
            seen.add("/" + cmd + " -> " + screenName(c));
            c.check(s != null && s.getClass().getSimpleName().equals("LeapOrderScreen"),
                    "/" + cmd + " opened " + screenName(c) + ", not the Leap Order editor");
            if (s != null) {
                if (first == null) {
                    first = s.getClass();
                }
                c.check(s.getClass() == first, "/" + cmd + " opened a different screen class than the first command");
                // The editor really is the working one: its page 1 has the five class cards.
                List<AbstractWidget> ws = widgets(c, s);
                c.check(byLabel(ws, "Mage") != null && byLabel(ws, "Berserker") != null,
                        "/" + cmd + ": the opened editor has no class cards");
            }
        }
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            return null;
        });
        c.note(String.join("; ", seen));
    }

    // ---- 1072 ---------------------------------------------------------------------------------------------------------

    static void overridesGoneAndMigrated(UiCase c) throws Exception {
        // (a) the setting is gone
        c.check(!Mod.has("gui.tab.ClassOverridesTab"), "the ClassOverridesTab class is still in the jar");
        List<Object> leaves = c.onClient(mc -> {
            @SuppressWarnings("unchecked")
            List<Object> l = (List<Object>) Mod.staticCall("gui.ModScreen", "allLeaves");
            return l;
        });
        List<String> names = new ArrayList<>();
        for (Object leaf : leaves) {
            names.add(String.valueOf(Mod.field(leaf, "name")));
        }
        c.check(!names.contains("Class Overrides"), "the menu still has a Class Overrides tab");
        c.check(names.contains("Leap Menu"), "the Leap Menu tab is missing: " + names);

        // (b) its switch and an Edit Classes button are in the Leap Menu tab
        List<AbstractWidget> tab = c.onClient(mc -> {
            try {
                Object t = Mod.cls("gui.tab.LeapMenuTab").getConstructor().newInstance();
                @SuppressWarnings("unchecked")
                List<AbstractWidget> ws = (List<AbstractWidget>) Mod.call(t, "buildWidgets", 0, 0, 300, (Runnable) () -> { });
                return ws;
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        AbstractWidget overlay = byPrefix(tab, "Class Selection Overlay");
        AbstractWidget edit = byLabel(tab, "Edit Classes");
        c.check(overlay != null, "the Leap Menu tab has no Class Selection Overlay switch");
        c.check(edit != null, "the Leap Menu tab has no Edit Classes button");
        Object overlayCfg = Mod.cfg("dungeonclass.ClassSelectionOverlayConfig");
        boolean was = (Boolean) Mod.call(overlayCfg, "isEnabledRaw");
        try {
            press(c, overlay);
            c.check((Boolean) Mod.call(overlayCfg, "isEnabledRaw") == !was, "pressing the switch did not toggle the overlay");
            Mod.staticCall("dungeonclass.ClassSelectionOverlayConfig", "load");
            c.check((Boolean) Mod.call(Mod.cfg("dungeonclass.ClassSelectionOverlayConfig"), "isEnabledRaw") == !was,
                    "the overlay switch did not survive a reload from disk");
        } finally {
            Mod.call(Mod.cfg("dungeonclass.ClassSelectionOverlayConfig"), "setEnabled", was);
            Mod.call(Mod.cfg("dungeonclass.ClassSelectionOverlayConfig"), "save");
        }
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            return null;
        });
        press(c, edit);
        c.ticks(4);
        c.check(screenName(c).equals("LeapOrderScreen"), "Edit Classes opened " + screenName(c));
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            return null;
        });

        // (c) the migration
        LeapOrderCases.State st = LeapOrderCases.setUp(c);
        Path legacy = (Path) Mod.field(LEAP_CFG, "LEGACY_PICKS_PATH");
        boolean legacyExisted = Files.exists(legacy);
        byte[] legacyBytes = legacyExisted ? Files.readAllBytes(legacy) : null;
        List<String> mine = List.of("LqAnna", "LqBen", "LqCara", "LqDev");
        try {
            for (String n : mine) {
                c.onClient(mc -> Mod.staticCall(OVR, "clear", n));
            }
            // a pick already in the Leap Order, which the old file must not overwrite
            c.onClient(mc -> Mod.staticCall(OVR, "set", "LqBen", Mod.enumValue(CLASS, "ARCHER")));
            UUID devId = UUID.nameUUIDFromBytes("LqDev".getBytes());
            String uuid = devId.toString();
            c.onClient(mc -> Mod.staticCall("players.PlayerNames", "noteKnown", devId, "LqDev"));
            Files.createDirectories(legacy.getParent());
            Files.writeString(legacy, "{\"overrides\":[{\"ign\":\"LqAnna\",\"class\":\"MAGE\"},"
                    + "{\"ign\":\"LqBen\",\"class\":\"TANK\"},{\"ign\":\"LqDev\",\"uuid\":\"" + uuid + "\",\"class\":\"BERSERKER\"}]}");
            c.check(!c.onClient(mc -> (Boolean) Mod.staticCall(OVR, "has", "LqAnna")), "LqAnna had a pick before the migration");
            c.onClient(mc -> Mod.staticCall(LEAP_CFG, "load"));
            c.check(pick(c, "LqAnna").equals("MAGE"), "array format: LqAnna's migrated pick is " + pick(c, "LqAnna"));
            c.check(pick(c, "LqDev").equals("BERSERKER"), "array format with a uuid: LqDev's pick is " + pick(c, "LqDev"));
            c.check(pick(c, "LqBen").equals("ARCHER"), "an existing Leap Order pick lost to the old file: " + pick(c, "LqBen"));
            c.check(!Files.exists(legacy), "the old classoverrides file was left behind after migrating");
            Path leapFile = (Path) Mod.field(LEAP_CFG, "CONFIG_PATH");
            String saved = Files.readString(leapFile);
            c.check(saved.contains("classPicks") && saved.contains("LqAnna") && saved.contains("LqDev"),
                    "killer560smod-leapmenu.json does not hold the migrated picks: " + saved.substring(0, Math.min(200, saved.length())));
            c.onClient(mc -> Mod.staticCall(LEAP_CFG, "load"));
            c.check(pick(c, "LqAnna").equals("MAGE") && pick(c, "LqDev").equals("BERSERKER"),
                    "the migrated picks did not survive a second load from the Leap Order file alone");
            c.note("array format migrated: LqAnna MAGE, LqDev BERSERKER (uuid kept), LqBen kept ARCHER; old file deleted; "
                    + "picks read back from " + leapFile.getFileName());

            // the pre-2026-09-21 object format
            Files.writeString(legacy, "{\"overrides\":{\"LqCara\":\"HEALER\"}}");
            c.onClient(mc -> Mod.staticCall(LEAP_CFG, "load"));
            c.check(pick(c, "LqCara").equals("HEALER"), "object format: LqCara's pick is " + pick(c, "LqCara"));
            c.check(!Files.exists(legacy), "the old object-format file was left behind");

            // the editor reads the same store: a teammate's class comes out of the migrated pick
            String cls = c.onClient(mc -> String.valueOf(Mod.staticCall("leapmenu.PartyTracker", "classOf", "LqAnna")));
            c.check(cls.equals("MAGE"), "PartyTracker.classOf(LqAnna) is " + cls + " after the migration");
        } finally {
            c.onClient(mc -> {
                for (String n : mine) {
                    Mod.staticCall(OVR, "clear", n);
                }
                return null;
            });
            if (legacyExisted) {
                Files.write(legacy, legacyBytes);
            } else {
                Files.deleteIfExists(legacy);
            }
            LeapOrderCases.restore(c, st);
        }
    }

    private static String pick(UiCase c, String name) {
        return c.onClient(mc -> {
            Object p = Mod.staticCall(OVR, "classOf", name, null);
            return p == null ? "none" : String.valueOf(p);
        });
    }

    // ---- 1073 ---------------------------------------------------------------------------------------------------------

    static void layout(UiCase c) throws Exception {
        LeapOrderCases.State st = LeapOrderCases.setUp(c);
        int[] oldWindow = c.onClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight()});
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        Object hud = Mod.cfg("hud.HudConfig");
        boolean oldAuto = (Boolean) Mod.call(hud, "isAutoScale");
        String oldTheme = MenuACases.themeName();
        List<String> notes = new ArrayList<>();
        try {
            // (a) the test window as it is, then 1920x1080 GUI 2 with Auto Scale on (as he plays)
            page1AndSpots(c, "test window", notes);
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                Mod.call(hud, "setAutoScale", true);
                return null;
            });
            c.ctx().getInput().resizeWindow(1920, 1080);
            c.ticks(3);
            c.onClient(mc -> {
                mc.options.guiScale().set(2);
                mc.resizeGui();
                return null;
            });
            c.ticks(3);
            page1AndSpots(c, "1920x1080 GUI 2", notes);

            // (b) the cards are the menu theme's cards, under Amber and under Light
            int[][] seen = new int[2][];
            String[] themes = {"AMBER", "LIGHT"};
            for (int t = 0; t < 2; t++) {
                String theme = themes[t];
                c.onClient(mc -> {
                    Mod.call(Mod.cfg("gui.MenuConfig"), "setTheme", Mod.enumValue("gui.MenuTheme", theme));
                    return null;
                });
                Screen s = LeapWizardCases.openPage1(c);
                InvBatchCases.cursor(c, s, 2, 2);
                c.ticks(3);
                List<AbstractWidget> cards = widgets(c, s);
                AbstractWidget mage = byLabel(cards, "Mage");
                c.check(mage != null, "no Mage card under " + theme);
                int px = mage.getX() + 6;
                int py = mage.getY() + mage.getHeight() - 6;
                String shot = c.name() + "-page1-" + theme.toLowerCase(Locale.ROOT);
                Path taken = c.ctx().takeScreenshot(Report.fileName(shot));
                Report.screenshot(shot, taken);
                java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(taken.toFile());
                double sc = img.getWidth() / (double) s.width;
                int sampled = img.getRGB((int) ((px + 0.5) * sc), (int) ((py + 0.5) * sc)) | 0xFF000000;
                int top = (Integer) Mod.field("gui.MenuStyle", "CARD_TOP");
                int bottom = (Integer) Mod.field("gui.MenuStyle", "CARD_BOTTOM");
                float f = (py + 0.5f - (mage.getY() + 1)) / Math.max(1f, mage.getHeight() - 2);
                // Since mod 975f1d5e the whole card carries the class colour at alpha 0x30 over the theme's card.
                int mageColor = (Integer) Mod.call(Mod.enumValue("dungeonclass.DungeonClass", "MAGE"), "color");
                int expected = mix(mix(top, bottom, f) | 0xFF000000, mageColor | 0xFF000000, 0x30 / 255f) | 0xFF000000;
                seen[t] = new int[]{sampled, expected};
                c.note(String.format(Locale.ROOT, "%s: Mage card pixel %s, the %s card gradient under Mage blue at 0x30 gives %s",
                        theme, MenuACases.hex(sampled), theme, MenuACases.hex(expected)));
                c.check(near(sampled, expected), theme + ": the Mage card reads " + MenuACases.hex(sampled)
                        + " where the menu theme's card is " + MenuACases.hex(expected) + " - it does not follow the theme");
            }
            c.check(!near(seen[0][0], seen[1][0]), "the cards look the same under Amber and Light: " + MenuACases.hex(seen[0][0]));
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                Object cfg = Mod.cfg("gui.MenuConfig");
                Mod.call(cfg, "setTheme", Mod.enumValue("gui.MenuTheme", oldTheme));
                Mod.call(cfg, "save");
                Mod.call(hud, "setAutoScale", oldAuto);
                mc.options.guiScale().set(oldGui);
                return null;
            });
            c.ctx().getInput().resizeWindow(oldWindow[0], oldWindow[1]);
            c.ticks(3);
            c.onClient(mc -> {
                mc.resizeGui();
                return null;
            });
            LeapOrderCases.restore(c, st);
        }
        notes.forEach(c::note);
    }

    private static void page1AndSpots(UiCase c, String where, List<String> notes) throws Exception {
        Screen s = LeapWizardCases.openPage1(c);
        int w = s.width;
        int h = s.height;
        List<AbstractWidget> ws = widgets(c, s);
        List<AbstractWidget> cards = new ArrayList<>();
        for (String cls : List.of("Mage", "Tank", "Healer", "Archer", "Berserker")) {
            AbstractWidget card = byLabel(ws, cls);
            c.check(card != null, where + ": page 1 has no " + cls + " card");
            cards.add(card);
        }
        int left = Integer.MAX_VALUE;
        int right = 0;
        int top = Integer.MAX_VALUE;
        int bottom = 0;
        long area = 0;
        for (int i = 0; i < cards.size(); i++) {
            AbstractWidget a = cards.get(i);
            left = Math.min(left, a.getX());
            right = Math.max(right, a.getX() + a.getWidth());
            top = Math.min(top, a.getY());
            bottom = Math.max(bottom, a.getY() + a.getHeight());
            area += (long) a.getWidth() * a.getHeight();
            if (i > 0) {
                AbstractWidget p = cards.get(i - 1);
                c.check(a.getX() >= p.getX() + p.getWidth(), where + ": the cards overlap (" + ModScreenDriver.label(p) + " / "
                        + ModScreenDriver.label(a) + ")");
                c.check(a.getY() == p.getY() && a.getHeight() == p.getHeight(), where + ": the cards are not one row");
            }
        }
        c.check(left <= 24 && right >= w - 24, where + ": the cards span x " + left + ".." + right + " of " + w
                + ", not edge to edge");
        c.check(bottom - top >= h * 0.45, where + ": the cards are only " + (bottom - top) + " tall in a " + h + " tall screen");
        // Since mod 975f1d5e the header is taller (a 2x "Select the class" line): LeapOrderScreen.classArea starts the row
        // at y 76 under it, and past 280 units tall (c4e72491) the row is centred in the room between header and Done.
        int room = Math.max(40, h - 40 - 76);
        int wantTop = 76 + (room - Math.min(room, 280)) / 2;
        c.check(Math.abs(top - wantTop) <= 2, where + ": the cards start at y " + top + " of " + h + ", want " + wantTop
                + " (under the header, centred past 280 tall)");
        c.check(area >= (long) w * h * 0.40, where + ": the cards cover " + (100 * area / ((long) w * h)) + "% of the screen");
        AbstractWidget done = byLabel(ws, "Done");
        c.check(done != null && done.getY() >= bottom && done.getWidth() >= 80, where + ": Done is not a wide button under the cards");
        notes.add(where + ": page 1 cards x " + left + ".." + right + " y " + top + ".." + bottom + " of " + w + "x" + h
                + " (" + (100 * area / ((long) w * h)) + "% of the screen)");

        // the editor behind a class pick: its four spots span the window
        InvBatchCases.clickWidget(c, s, byLabel(ws, "Archer"));
        boolean wizard = c.onClient(mc -> Mod.field(s, "wizard") != null);
        if (wizard) {
            InvBatchCases.clickWidget(c, s, byLabel(widgets(c, s), "Skip"));
        }
        int sl = Integer.MAX_VALUE;
        int sr = 0;
        int st = Integer.MAX_VALUE;
        int sb = 0;
        for (int spot = 0; spot < 4; spot++) {
            int fs = spot;
            int[] b = c.onClient(mc -> (int[]) Mod.call(s, "spotBounds", fs));
            sl = Math.min(sl, b[0]);
            sr = Math.max(sr, b[0] + b[2]);
            st = Math.min(st, b[1]);
            sb = Math.max(sb, b[1] + b[3]);
        }
        c.check(sr - sl >= w * 0.80, where + ": the four spots span only " + (sr - sl) + " of " + w + " wide");
        c.check(sb - st >= Math.min(h * 0.35, 90), where + ": the four spots span only " + (sb - st) + " of " + h + " tall");
        notes.add(where + ": editor spots x " + sl + ".." + sr + " y " + st + ".." + sb + " of " + w + "x" + h);
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            return null;
        });
    }

    private static int mix(int a, int b, float t) {
        int r = 0;
        for (int sh = 0; sh < 32; sh += 8) {
            int ca = (a >>> sh) & 0xFF;
            int cb = (b >>> sh) & 0xFF;
            r |= (Math.round(ca + (cb - ca) * t) & 0xFF) << sh;
        }
        return r;
    }

    private static boolean near(int a, int b) {
        for (int sh = 0; sh < 24; sh += 8) {
            if (Math.abs(((a >> sh) & 0xFF) - ((b >> sh) & 0xFF)) > 8) {
                return false;
            }
        }
        return true;
    }

    // ---- Party Finder styles -------------------------------------------------------------------------------------------

    /** killer560 (Tank 50, 210,000 secrets, 133.7 average, S+ 13:37) as each built-in style prints him, worked by hand. */
    private static final String[][] STYLE_LINES = {
            {"NONE", " killer560: Tank (50) (50) [210,000 | 133.70] [S+ 13:37]"},
            {"STYLE1", "[T] killer560 [50 | 50] [210K | 133.7] [13:37]"},
            {"STYLE2", "[T 50] killer560 [50 | 210K | 133.7] 13:37"},
            {"COMPACT", "T killer560 50 | 50 | 13:37"},
            {"MINIMAL", "killer560 (50) 13:37"},
            {"DETAILED", "[Tank 50] killer560 - Cata 50 - 210000 secrets (133.70 avg) - 13:37"},
            {"STAT_ROW", "killer560 >> 50 / 50 / 210K / 133.7 / 13:37"},
            {"CLASS_TAG", "Tank | killer560 | 50 | 210K | 13:37"},
    };
    private static final String[] BUILT_IN_LABELS = {"None", "Style 1", "Style 2", "Compact", "Minimal", "Detailed", "Stat Row",
            "Class Tag", "Custom"};

    private static final class PfState {
        Path path;
        boolean existed;
        byte[] bytes;
    }

    private static PfState pfSave(UiCase c) throws Exception {
        PfState st = new PfState();
        st.path = (Path) Mod.field(PF, "CONFIG_PATH");
        st.existed = Files.exists(st.path);
        st.bytes = st.existed ? Files.readAllBytes(st.path) : null;
        return st;
    }

    private static void pfRestore(UiCase c, PfState st) throws Exception {
        if (st.existed) {
            Files.write(st.path, st.bytes);
        } else {
            Files.deleteIfExists(st.path);
        }
        c.onClient(mc -> Mod.staticCall(PF, "load"));
    }

    private static List<AbstractWidget> queueTab(UiCase c, Object tab) {
        return c.onClient(mc -> {
            @SuppressWarnings("unchecked")
            List<AbstractWidget> ws = (List<AbstractWidget>) Mod.call(tab, "buildWidgets", 0, 0, 300, (Runnable) () -> { });
            return ws;
        });
    }

    private static Object newQueueTab(UiCase c) {
        return c.onClient(mc -> {
            try {
                return Mod.cls("gui.tab.DungeonQueueTab").getConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
    }

    /** The preview line for killer560 as plain text. */
    private static String killerLine(UiCase c) {
        return c.onClient(mc -> {
            @SuppressWarnings("unchecked")
            List<Component> lines = (List<Component>) Mod.staticCall(PF_OVERLAY, "renderPreviewLines", Mod.cfg(PF));
            return net.minecraft.ChatFormatting.stripFormatting(lines.get(0).getString());
        });
    }

    private static String mode(UiCase c) {
        return c.onClient(mc -> String.valueOf(Mod.call(Mod.cfg(PF), "getCompactMode")));
    }

    static void queueStyles(UiCase c) throws Exception {
        PfState st = pfSave(c);
        try {
            c.onClient(mc -> {
                Object cfg = Mod.cfg(PF);
                Mod.call(cfg, "setEnabled", true);
                Mod.call(cfg, "setTooltip", true);
                Mod.call(cfg, "setRankNameColors", false);
                Mod.call(cfg, "setPbMode", Mod.enumValue(PF + "$PbMode", "BOTH"));
                Mod.call(cfg, "setCompactMode", Mod.enumValue(MODE, "NONE"));
                return null;
            });
            // the pure result of every style, set on the config directly
            List<String> got = new ArrayList<>();
            for (String[] style : STYLE_LINES) {
                c.onClient(mc -> {
                    Mod.call(Mod.cfg(PF), "setCompactMode", Mod.enumValue(MODE, style[0]));
                    return null;
                });
                String line = killerLine(c);
                got.add(style[0] + " = " + line);
                c.check(line.equals(style[1]), style[0] + ": the preview prints '" + line + "', want '" + style[1] + "'");
            }
            // the real button: Party Finder style, left click forward through every style and Custom, right click back
            c.onClient(mc -> {
                Mod.call(Mod.cfg(PF), "setCompactMode", Mod.enumValue(MODE, "NONE"));
                return null;
            });
            Object tab = newQueueTab(c);
            List<String> visited = new ArrayList<>();
            for (int i = 0; i < BUILT_IN_LABELS.length; i++) {
                AbstractWidget btn = byPrefix(queueTab(c, tab), "Party Finder style");
                c.check(btn != null, "no Party Finder style button");
                String label = ModScreenDriver.label(btn);
                visited.add(label.substring(label.indexOf(':') + 1).trim());
                press(c, btn);
            }
            c.check(visited.equals(List.of(BUILT_IN_LABELS)), "left clicks visited " + visited + ", want " + List.of(BUILT_IN_LABELS));
            c.check(mode(c).equals("NONE"), "nine left clicks did not wrap round to None (at " + mode(c) + ")");
            AbstractWidget btn = byPrefix(queueTab(c, tab), "Party Finder style");
            rightClick(c, btn);
            c.check(mode(c).equals("CUSTOM"), "a right click on None went to " + mode(c) + ", want CUSTOM (wraps back)");
            rightClick(c, byPrefix(queueTab(c, tab), "Party Finder style"));
            c.check(mode(c).equals("CLASS_TAG"), "a right click on Custom went to " + mode(c) + ", want CLASS_TAG");
            // selected styles survive a reload from disk
            c.onClient(mc -> {
                Mod.call(Mod.cfg(PF), "save");
                Mod.staticCall(PF, "load");
                return null;
            });
            c.check(mode(c).equals("CLASS_TAG"), "the selected style did not survive a reload (" + mode(c) + ")");
            c.note(String.join("; ", got));
        } finally {
            pfRestore(c, st);
        }
    }

    // ---- 1075 ---------------------------------------------------------------------------------------------------------

    private static EditBox editBox(List<AbstractWidget> ws, String name) {
        for (AbstractWidget w : ws) {
            if (w instanceof EditBox e && e.getMessage().getString().equals(name)) {
                return e;
            }
        }
        return null;
    }

    /** Types into a box on the client thread (an EditBox touches the render system when its text changes). */
    private static void type(UiCase c, EditBox e, String value) {
        c.onClient(mc -> {
            e.setValue(value);
            return null;
        });
    }

    @SuppressWarnings("unchecked")
    private static List<String> savedNames(UiCase c) {
        return c.onClient(mc -> List.copyOf((List<String>) Mod.call(Mod.cfg(PF), "savedStyleNames")));
    }

    private static String cfgString(UiCase c, String getter) {
        return c.onClient(mc -> String.valueOf(Mod.call(Mod.cfg(PF), getter)));
    }

    static void customEditor(UiCase c) throws Exception {
        PfState st = pfSave(c);
        try {
            c.onClient(mc -> {
                Object cfg = Mod.cfg(PF);
                Mod.call(cfg, "setEnabled", true);
                Mod.call(cfg, "setTooltip", true);
                Mod.call(cfg, "setRankNameColors", false);
                Mod.call(cfg, "setPbMode", Mod.enumValue(PF + "$PbMode", "BOTH"));
                Mod.call(cfg, "setCompactMode", Mod.enumValue(MODE, "CUSTOM"));
                Mod.call(cfg, "setCustomStyle", "");
                for (Object n : new ArrayList<>((List<?>) Mod.call(cfg, "savedStyleNames"))) {
                    Mod.call(cfg, "deleteStyle", n);
                }
                Mod.call(cfg, "clearActiveStyle");
                return null;
            });
            Object tab = newQueueTab(c);
            List<AbstractWidget> ws = queueTab(c, tab);
            EditBox text = editBox(ws, "Custom Style");
            EditBox name = editBox(ws, "Style Name");
            c.check(text != null && name != null, "the Custom editor has no style box / name box");

            // placeholder buttons add themselves to the text
            for (String token : List.of("$Name", "$Cata", "$PB")) {
                AbstractWidget chip = byLabel(queueTab(c, tab), token);
                c.check(chip != null, "no " + token + " placeholder button");
                ws = queueTab(c, tab);
                text = editBox(ws, "Custom Style");
                String before = text.getValue();
                press(c, byLabel(ws, token));
                c.check(text.getValue().equals(before + token), "pressing " + token + " made the text '" + text.getValue()
                        + "' from '" + before + "'");
            }
            c.check(cfgString(c, "getCustomStyle").equals("$Name$Cata$PB"), "the typed style is not in the config: "
                    + cfgString(c, "getCustomStyle"));
            type(c, text, "$NameColor$Name &8| &6$Cata &8| $PB");
            c.check(killerLine(c).equals("killer560 | 50 | 13:37"), "the Custom preview line is '" + killerLine(c) + "'");

            // Save Style under a name
            ws = queueTab(c, tab);
            name = editBox(ws, "Style Name");
            type(c, name, "Mine");
            press(c, byLabel(ws, "Save Style"));
            c.check(savedNames(c).equals(List.of("Mine")), "Save Style kept " + savedNames(c));
            c.check(cfgString(c, "getActiveStyle").equals("Mine"), "the saved style is not the active one");
            c.check(c.onClient(mc -> String.valueOf(Mod.call(Mod.cfg(PF), "savedStyle", "mine"))).equals("$NameColor$Name &8| &6$Cata &8| $PB"),
                    "the saved text is not what was typed");

            // a second style; the editor marks a changed one
            ws = queueTab(c, tab);
            text = editBox(ws, "Custom Style");
            type(c, text, "$Name only");
            c.check((Boolean) c.onClient(mc -> Mod.call(Mod.cfg(PF), "isActiveStyleModified")), "an edited saved style is not marked changed");
            c.check(!queueTab(c, tab).stream().filter(w -> w instanceof StringWidget)
                    .filter(w -> ModScreenDriver.label(w).startsWith("Changed since")).toList().isEmpty(),
                    "the editor says nothing about the unsaved change");
            name = editBox(ws, "Style Name");
            type(c, name, "Second");
            press(c, byLabel(ws, "Save Style"));
            c.check(savedNames(c).equals(List.of("Mine", "Second")), "two saves kept " + savedNames(c));

            // Saved Style steps: Second -> none -> Mine (loading its text) -> back with a right click
            ws = queueTab(c, tab);
            AbstractWidget saved = byPrefix(ws, "Saved Style");
            c.check(ModScreenDriver.label(saved).contains("Second"), "the Saved Style button reads " + ModScreenDriver.label(saved));
            press(c, saved);
            c.check(cfgString(c, "getActiveStyle").isEmpty(), "stepping past the last saved style did not reach 'none'");
            c.check(cfgString(c, "getCustomStyle").equals("$Name only"), "stepping to 'none' changed the text");
            press(c, byPrefix(queueTab(c, tab), "Saved Style"));
            c.check(cfgString(c, "getActiveStyle").equals("Mine"), "the next step is " + cfgString(c, "getActiveStyle") + ", want Mine");
            c.check(cfgString(c, "getCustomStyle").equals("$NameColor$Name &8| &6$Cata &8| $PB"),
                    "loading Mine put '" + cfgString(c, "getCustomStyle") + "' in the text");
            c.check(killerLine(c).equals("killer560 | 50 | 13:37"), "loading Mine did not change the preview (" + killerLine(c) + ")");
            rightClick(c, byPrefix(queueTab(c, tab), "Saved Style"));
            c.check(cfgString(c, "getActiveStyle").isEmpty(), "a right click on Mine went to " + cfgString(c, "getActiveStyle"));

            // they survive a reload from disk, in order
            c.onClient(mc -> {
                Mod.call(Mod.cfg(PF), "save");
                Mod.staticCall(PF, "load");
                return null;
            });
            c.check(savedNames(c).equals(List.of("Mine", "Second")), "saved styles after a reload: " + savedNames(c));
            c.check(c.onClient(mc -> String.valueOf(Mod.call(Mod.cfg(PF), "savedStyle", "Second"))).equals("$Name only"),
                    "Second's text did not survive a reload");

            // Delete
            ws = queueTab(c, tab);
            type(c, editBox(ws, "Style Name"), "Mine");
            press(c, byLabel(ws, "Delete"));
            c.check(savedNames(c).equals(List.of("Second")), "Delete left " + savedNames(c));

            // Load Built-in copies a built-in template in
            AbstractWidget builtIn = byPrefix(queueTab(c, tab), "Load Built-in");
            c.check(builtIn != null, "no Load Built-in button");
            press(c, builtIn);
            String t1 = cfgString(c, "getCustomStyle");
            c.check(t1.startsWith("&8[$RoleColor$RoleSingle&8] $NameColor$Name"), "Load Built-in (first) put '" + t1 + "' in the text");
            c.check(killerLine(c).equals("[T] killer560 [50 | 50] [210K | 133.7] [13:37]"),
                    "Style 1 loaded as custom prints '" + killerLine(c) + "'");
            press(c, byPrefix(queueTab(c, tab), "Load Built-in"));
            c.check(killerLine(c).equals("[T 50] killer560 [50 | 210K | 133.7] 13:37"),
                    "Style 2 loaded as custom prints '" + killerLine(c) + "'");
            c.check(cfgString(c, "getActiveStyle").isEmpty(), "Load Built-in left a saved style active");

            // a typo'd placeholder is flagged, a good one is not
            type(c, editBox(queueTab(c, tab), "Custom Style"), "$Nmae $Cata");
            List<AbstractWidget> flagged = queueTab(c, tab);
            boolean said = flagged.stream().anyMatch(w -> w instanceof StringWidget && ModScreenDriver.label(w).contains("$Nmae"));
            c.check(said, "a misspelled $Nmae is not flagged");
            type(c, editBox(queueTab(c, tab), "Custom Style"), "$Name $Cata");
            boolean still = queueTab(c, tab).stream().anyMatch(w -> w instanceof StringWidget
                    && ModScreenDriver.label(w).startsWith("Not a placeholder"));
            c.check(!still, "a correct style is flagged");

            // a bad name saves nothing
            ws = queueTab(c, tab);
            type(c, editBox(ws, "Style Name"), "   ");
            int before = savedNames(c).size();
            press(c, byLabel(ws, "Save Style"));
            c.check(savedNames(c).size() == before, "a blank name saved a style: " + savedNames(c));
            c.note("placeholder buttons, Save Style / Saved Style (both click directions) / Delete, reload from disk, Load Built-in "
                    + "(Style 1, Style 2) and the typo flag all acted; saved now " + savedNames(c));
        } finally {
            pfRestore(c, st);
        }
    }
}
