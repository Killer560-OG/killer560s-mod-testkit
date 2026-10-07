package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.level.GameType;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * 407-ui-stat-bars-layout: Health and Mana Bars' Layout setting, Predefined / Custom (mod branch bars-anchors; killer560,
 * 2026-10-07: "a setting that is predefined spots or fully custom. Then for predefined look at how Skyblocker has their
 * snap-to-area kind of set up and use that").
 *
 * <ul>
 *   <li>SETTING: PlayerStatsConfig has the Layout; a fresh config, and an old file with no readout on, load Predefined;
 *       an old file with a readout on loads Custom; the value and every area/order survive a save and reload. The tab
 *       has a Layout row behind Stat Bars, with a tooltip, that flips the setting when pressed (real press).</li>
 *   <li>GEOMETRY, measured on screenshots at Auto Scale 0.5 (854x480 GUI 2) and 1 (2560x1440 GUI 3), in survival so
 *       the vanilla hearts, hunger and XP level are drawn: three full bars put in Above Hotbar draw as three solid
 *       bars sharing the hotbar's 182 GUI px (first on its left edge, last on its right, widths within 1, gaps 1-3, no
 *       overlap), none of their pixels at or below the heart/hunger row; Row 2's bar sits above them; the texts sit left
 *       and right of the hotbar near the bottom. With every vanilla row hidden the row drops to just above the hotbar.</li>
 *   <li>EDITOR (real mouse, Auto Scale 1): Defence dragged from Row 2 onto Above Hotbar between Health and Mana lands
 *       there in that order and is saved; Vitality dragged onto the Hidden tray is hidden (no pixel in game, still
 *       listed in the editor's tray); a drop on no area changes nothing. The mid-drag frame is photographed.</li>
 *   <li>CUSTOM: each bar's own saved position draws exactly where it did before Predefined was used (the pixel boxes
 *       match), and Predefined never wrote one.</li>
 *   <li>OLD CONFIG: a file in the old format (no layout key, a bar on at a saved position) loads Custom and draws at
 *       its saved place. Across jars: {@code TESTKIT_BARSLAYOUT_EXPORT=<dir>} on any jar writes such a config and the
 *       pixel boxes it draws; a later run with {@code -PseedConfig=<dir>} finds {@code bars-layout-export.properties}
 *       and requires the same boxes.</li>
 * </ul>
 */
final class StatBarsLayoutCases {

    private static final String PS = "playerstats.PlayerStatsConfig";
    private static final String HUD = "hud.HudConfig";
    private static final String READOUT = "playerstats.StatElements$Readout";
    private static final String AREA = "playerstats.StatLayout$Area";
    private static final String EXPORT_FILE = "bars-layout-export.properties";

    // Opaque colours nothing else on a superflat HUD has; every bar is filled completely except Defence.
    static final int HEALTH = 0xFFFE01FD;
    static final int MANA = 0xFF01FEFD;
    static final int VITALITY = 0xFFFDFE02;
    static final int DEFENCE = 0xFF02FD03;
    static final int HEALTH_TEXT = 0xFFFD03FE;
    static final int MANA_TEXT = 0xFF03FDFE;
    static final int BACKGROUND = 0xFF0B0C0D;

    private StatBarsLayoutCases() {
    }

    static void run(UiCase c) throws Exception {
        Path configDir = c.onClient(mc -> mc.gameDirectory.toPath().resolve("config"));
        Path seeded = findSeed(configDir);
        Map<Path, byte[]> saved = HudEditorCases.snapshot(c);
        int[] window = HudEditorCases.windowSize(c);
        int gui = c.onClient(mc -> mc.options.guiScale().get());
        Class<?> feature = R.cls("playerstats.PlayerStatsFeature");
        boolean has = c.onClient(mc -> hasMethod(R.cls(PS), "isPredefinedLayout"));
        try {
            if (seeded != null) {
                importCheck(c, seeded);
            }
            String export = System.getenv("TESTKIT_BARSLAYOUT_EXPORT");
            if (export != null && !export.isBlank()) {
                exportOld(c, configDir, Path.of(export));
            }
            if (!has) {
                c.problem("this jar has no Layout setting for Health and Mana Bars (PlayerStatsConfig"
                        + ".isPredefinedLayout) - Predefined / Custom is missing");
                return;
            }
            defaults(c);
            try {
                tab(c);
            } catch (Exception | Error e) {
                throw e;
            } catch (Throwable t) {
                throw new AssertionError(UiCase.describe(t), t);
            }
            survival(c, true);
            values(c, feature, true);

            geometry(c, "f0.5");
            HudEditorCases.setWindow(c, 2560, 1440, 3);
            List<int[]> custom = customBoxes(c, "f1-custom-before");
            geometry(c, "f1");
            editor(c, "f1");
            customAfter(c, custom, "f1");
            oldConfig(c, "f1");
        } finally {
            HudEditorCases.closeEditor(c);
            values(c, feature, false);
            survival(c, false);
            HudEditorCases.restoreWindow(c, window, gui);
            HudEditorCases.restore(c, saved);
        }
    }

    // ================================================================================================================
    // setting
    // ================================================================================================================

    private static void defaults(UiCase c) throws Exception {
        Path ps = c.onClient(mc -> (Path) R.getStatic(R.cls(PS), "CONFIG_PATH"));
        Map<String, String> files = new LinkedHashMap<>();
        files.put("fresh (no file)", null);
        files.put("old file, no readout on", "{\"enabled\":true,\"readouts\":{\"health_bar\":{\"on\":false}}}");
        files.put("old file, Health Bar on", "{\"enabled\":true,\"readouts\":{\"health_bar\":{\"on\":true}}}");
        files.put("layout custom", "{\"enabled\":true,\"layout\":\"custom\",\"readouts\":{}}");
        files.put("layout predefined, Mana Text on",
                "{\"enabled\":true,\"layout\":\"predefined\",\"readouts\":{\"mana_text\":{\"on\":true}}}");
        boolean[] want = {true, true, false, false, true};
        int i = 0;
        for (Map.Entry<String, String> e : files.entrySet()) {
            if (e.getValue() == null) {
                Files.deleteIfExists(ps);
            } else {
                Files.writeString(ps, e.getValue(), StandardCharsets.UTF_8);
            }
            boolean predefined = c.onClient(mc -> {
                Mod.staticCall(PS, "load");
                return (Boolean) Mod.call(Mod.cfg(PS), "isPredefinedLayout");
            });
            c.note("default: " + e.getKey() + " -> " + (predefined ? "Predefined" : "Custom"));
            expect(c, predefined == want[i], "default: " + e.getKey() + " loads " + (predefined ? "Predefined" : "Custom")
                    + ", want " + (want[i] ? "Predefined" : "Custom"));
            i++;
        }
        // Round trip: the value and an explicit area/order.
        String back = c.onClient(mc -> {
            Object cfg = Mod.cfg(PS);
            Mod.call(cfg, "setPredefinedLayout", false);
            Mod.call(cfg, "moveTo", r("XP_TEXT"), area("ABOVE_HOTBAR_2"), null);
            Mod.call(cfg, "moveTo", r("DEFENCE_TEXT"), area("ABOVE_HOTBAR_2"), r("XP_TEXT"));
            Mod.call(cfg, "save");
            Mod.staticCall(PS, "load");
            Object l = Mod.cfg(PS);
            return Mod.call(l, "isPredefinedLayout") + " " + Mod.call(l, "getArea", r("XP_TEXT")) + " "
                    + Mod.call(l, "getArea", r("DEFENCE_TEXT")) + " " + Mod.call(l, "getOrder", r("DEFENCE_TEXT")) + "<"
                    + Mod.call(l, "getOrder", r("XP_TEXT"));
        });
        c.note("save + reload: " + back);
        String[] p = back.split(" ");
        expect(c, p[0].equals("false") && p[1].equals("ABOVE_HOTBAR_2") && p[2].equals("ABOVE_HOTBAR_2")
                        && Integer.parseInt(p[3].split("<")[0]) < Integer.parseInt(p[3].split("<")[1]),
                "Layout / area / order did not survive a save and reload: " + back);
        Files.deleteIfExists(ps);
        c.onClient(mc -> {
            Mod.staticCall(PS, "load");
            return null;
        });
    }

    /** The tab: Layout row behind Stat Bars, tooltip, a real press flips it, no overlap. Pressed on the client
     *  thread, as 393 does (a press reaches Minecraft.getInstance()). */
    private static void tab(UiCase c) throws Throwable {
        c.onClient(mc -> {
            Mod.call(Mod.cfg(PS), "setEnabled", false);
            return null;
        });
        ModScreenDriver d = StatBarsCases.open(c);
        try {
            c.onClient(mc -> {
                try {
                    expect(c, layoutRow(d) == null, "the Layout row shows while Stat Bars is off");
                    StatBarsCases.press(c, d, "Stat Bars");
                    AbstractWidget row = layoutRow(d);
                    expect(c, row != null, "no Layout row in the tab with Stat Bars on");
                    if (row == null) {
                        return null;
                    }
                    String label = ModScreenDriver.label(row);
                    Object tip = Mod.staticCall("gui.SettingTooltips", "describe", "Hud Elements", row,
                            row.getMessage().getString());
                    c.note("tab row '" + label + "', tooltip: " + tip);
                    expect(c, tip != null && !tip.toString().isBlank(), "the Layout row has no tooltip");
                    for (AbstractWidget w : StatBarsCases.ours(d)) {
                        if (w != row && row.getX() < w.getX() + w.getWidth() && w.getX() < row.getX() + row.getWidth()
                                && row.getY() < w.getY() + w.getHeight() && w.getY() < row.getY() + row.getHeight()) {
                            c.problem("the Layout row overlaps '" + ModScreenDriver.label(w) + "'");
                        }
                    }
                    boolean was = (Boolean) Mod.call(Mod.cfg(PS), "isPredefinedLayout");
                    StatBarsCases.press(c, d, "Layout");
                    boolean now = (Boolean) Mod.call(Mod.cfg(PS), "isPredefinedLayout");
                    AbstractWidget after = layoutRow(d);
                    String shown = after == null ? "" : ModScreenDriver.label(after);
                    c.note("pressed Layout: " + (was ? "Predefined" : "Custom") + " -> " + (now ? "Predefined"
                            : "Custom") + ", row reads '" + shown + "'");
                    expect(c, now != was && shown.endsWith(now ? "Predefined" : "Custom"),
                            "pressing Layout did not flip it (" + was + " -> " + now + ", reads '" + shown + "')");
                    StatBarsCases.press(c, d, "Layout");
                    boolean back = (Boolean) Mod.call(Mod.cfg(PS), "isPredefinedLayout");
                    expect(c, back == was, "pressing Layout again did not put it back");
                } catch (Throwable t) {
                    throw new AssertionError(UiCase.describe(t), t);
                }
                return null;
            });
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
            c.ticks(2);
        }
    }

    private static AbstractWidget layoutRow(ModScreenDriver d) {
        for (AbstractWidget w : StatBarsCases.ours(d)) {
            if (StatBarsCases.key(ModScreenDriver.label(w)).equals("Layout")) {
                return w;
            }
        }
        return null;
    }

    // ================================================================================================================
    // geometry
    // ================================================================================================================

    /** Stat Bars on, Predefined, Health/Mana/Vitality in Above Hotbar, Defence in Row 2, the two texts beside the
     *  hotbar; vanilla rows shown. */
    private static void setupPredefined(UiCase c) {
        c.onClient(mc -> {
            Object ps = Mod.cfg(PS);
            Mod.call(ps, "setEnabled", true);
            Mod.call(ps, "setPredefinedLayout", true);
            for (Object rr : R.cls(READOUT).getEnumConstants()) {
                Mod.call(ps, "setReadoutOn", rr, false);
            }
            String[][] on = {{"HEALTH_BAR", "ABOVE_HOTBAR"}, {"MANA_BAR", "ABOVE_HOTBAR"},
                    {"VITALITY_BAR", "ABOVE_HOTBAR"}, {"DEFENCE_BAR", "ABOVE_HOTBAR_2"},
                    {"HEALTH_TEXT", "LEFT_OF_HOTBAR"}, {"MANA_TEXT", "RIGHT_OF_HOTBAR"}};
            for (String[] o : on) {
                Mod.call(ps, "setReadoutOn", r(o[0]), true);
                Mod.call(ps, "moveTo", r(o[0]), area(o[1]), null);
            }
            Mod.call(ps, "setReadoutColor", r("HEALTH_BAR"), HEALTH);
            Mod.call(ps, "setReadoutColor", r("MANA_BAR"), MANA);
            Mod.call(ps, "setReadoutColor", r("VITALITY_BAR"), VITALITY);
            Mod.call(ps, "setReadoutColor", r("DEFENCE_BAR"), DEFENCE);
            Mod.call(ps, "setReadoutColor", r("HEALTH_TEXT"), HEALTH_TEXT);
            Mod.call(ps, "setReadoutColor", r("MANA_TEXT"), MANA_TEXT);
            Mod.call(ps, "setBarBackground", BACKGROUND);
            Mod.call(ps, "setBarShowValue", false);
            Mod.call(ps, "setTextShadow", false);
            Mod.call(ps, "setBarWidth", 100);
            Mod.call(ps, "setBarHeight", 6);
            vanillaShown(ps, true);
            Mod.call(ps, "save");
            Object hud = Mod.cfg(HUD);
            for (Object rr : R.cls(READOUT).getEnumConstants()) {
                Mod.call(hud, "setScale", (String) R.get(rr, "hudId"), 1.0f);
            }
            Mod.call(hud, "setGlobalScale", 1.0f);
            Mod.call(hud, "save");
            McCompat.setScreen(mc, null);
            return null;
        });
        c.ticks(4);
    }

    private static void vanillaShown(Object ps, boolean shown) {
        Mod.call(ps, "setHideVanillaHearts", !shown);
        Mod.call(ps, "setHideVanillaHunger", !shown);
        Mod.call(ps, "setHideVanillaArmour", !shown);
        Mod.call(ps, "setHideVanillaAir", !shown);
        Mod.call(ps, "setHideXpBar", !shown);
    }

    private static void geometry(UiCase c, String tag) throws Exception {
        setupPredefined(c);
        float factor = c.onClient(mc -> ((Number) Mod.staticCall("hud.AutoScale", "current")).floatValue());
        int[] gw = c.onClient(mc -> new int[]{mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight(),
                mc.getWindow().getGuiScale(), mc.player.experienceLevel,
                mc.gameMode.canHurtPlayer() ? 1 : 0});
        int w = gw[0];
        int h = gw[1];
        int hb = w / 2 - 91;
        c.note(String.format(Locale.ROOT, "%s: Auto Scale %.2f, GUI %dx%d at scale %d, hotbar x %d..%d, top %d; XP level"
                + " %d, survival %s", tag, factor, w, h, gw[2], hb, hb + 182, h - 22, gw[3], gw[4] == 1));
        expect(c, gw[4] == 1 && gw[3] > 0, tag + ": not in survival with an XP level - the vanilla rows are not drawn"
                + " and the overlap check would be vacuous");

        // ---- vanilla rows shown: hearts / hunger at h-39, the XP level at h-36 --------------------------------------
        Map<Integer, double[]> m = measure(c, tag + "-predefined", HEALTH, MANA, VITALITY, DEFENCE, HEALTH_TEXT,
                MANA_TEXT);
        double[] hp = m.get(HEALTH);
        double[] mp = m.get(MANA);
        double[] vp = m.get(VITALITY);
        double[] dp = m.get(DEFENCE);
        int vanillaTop = h - 39;
        row(c, tag + " vanilla shown", new double[][]{hp, mp, vp}, new String[]{"Health", "Mana", "Vitality"}, hb,
                vanillaTop);
        if (dp != null && hp != null) {
            c.note(String.format(Locale.ROOT, "%s Row 2 Defence fill %s, Row 1 top %.1f", tag, gbox(dp), hp[1]));
            expect(c, Math.abs(dp[0] - hb) <= 0.5, tag + " Row 2's first bar does not start at the hotbar's left (" + dp[0]
                    + " vs " + hb + ")");
            expect(c, dp[3] <= Math.min(hp[1], Math.min(mp == null ? 1e9 : mp[1], vp == null ? 1e9 : vp[1])),
                    tag + " Row 2 (bottom " + dp[3] + ") overlaps Row 1 (top " + hp[1] + ")");
        } else {
            c.problem(tag + " Row 2's Defence bar drew no pixel");
        }
        double[] ht = m.get(HEALTH_TEXT);
        double[] mt = m.get(MANA_TEXT);
        if (ht != null && mt != null) {
            c.note(String.format(Locale.ROOT, "%s texts: Health %s (hotbar left %d), Mana %s (hotbar right %d)", tag,
                    gbox(ht), hb, gbox(mt), hb + 182));
            expect(c, ht[2] <= hb - 1 && ht[2] >= hb - 14 && ht[3] >= h - 14,
                    tag + " Health Text is not just left of the hotbar at the bottom: " + gbox(ht));
            expect(c, mt[0] >= hb + 182 + 1 && mt[0] <= hb + 182 + 14 && mt[3] >= h - 14,
                    tag + " Mana Text is not just right of the hotbar at the bottom: " + gbox(mt));
        } else {
            c.problem(tag + " a text readout drew no pixel (Health " + (ht != null) + ", Mana " + (mt != null) + ")");
        }

        // ---- every vanilla row hidden: the row comes down to just above the hotbar ---------------------------------
        c.onClient(mc -> {
            Object ps = Mod.cfg(PS);
            vanillaShown(ps, false);
            return null;
        });
        c.ticks(4);
        Map<Integer, double[]> m2 = measure(c, tag + "-predefined-no-vanilla-rows", HEALTH, MANA, VITALITY);
        row(c, tag + " vanilla hidden", new double[][]{m2.get(HEALTH), m2.get(MANA), m2.get(VITALITY)},
                new String[]{"Health", "Mana", "Vitality"}, hb, h - 23);
        double[] h2 = m2.get(HEALTH);
        if (h2 != null && hp != null) {
            c.note(String.format(Locale.ROOT, "%s Row 1 bottom: %.1f with the vanilla rows, %.1f without (hotbar top %d)",
                    tag, hp[3], h2[3], h - 22));
            expect(c, h2[3] > hp[3] && h2[3] >= h - 27, tag + " with every vanilla row hidden Row 1 did not come down"
                    + " to the hotbar (bottom " + h2[3] + ", before " + hp[3] + ")");
        }
        c.onClient(mc -> {
            vanillaShown(Mod.cfg(PS), true);
            Mod.call(Mod.cfg(PS), "save");
            return null;
        });
        c.ticks(3);
    }

    /** Checks bars sharing a row above the hotbar: solid, left to right, spanning the hotbar, equal, apart, above
     *  {@code floor} (GUI y nothing of the row may reach). Boxes are GUI {x0, y0, x1, y1} (x1/y1 exclusive). */
    private static void row(UiCase c, String tag, double[][] boxes, String[] names, int hb, int floor) {
        for (int i = 0; i < boxes.length; i++) {
            if (boxes[i] == null) {
                c.problem(tag + ": " + names[i] + " drew no pixel");
                return;
            }
            if (boxes[i][4] < 1) {
                c.problem(tag + ": " + names[i] + " is not a solid bar (" + gbox(boxes[i]) + ")");
            }
        }
        StringBuilder line = new StringBuilder(tag + ":");
        for (int i = 0; i < boxes.length; i++) {
            line.append(" ").append(names[i]).append(" ").append(gbox(boxes[i]));
        }
        c.note(line.toString());
        expect(c, Math.abs(boxes[0][0] - hb) <= 0.5, tag + ": the first bar starts at " + boxes[0][0]
                + ", the hotbar at " + hb);
        double right = boxes[boxes.length - 1][2];
        expect(c, right <= hb + 182 + 0.01 && right >= hb + 182 - 1.01, tag + ": the last bar ends at " + right
                + ", the hotbar at " + (hb + 182));
        double minW = 1e9;
        double maxW = 0;
        for (int i = 0; i < boxes.length; i++) {
            double bw = boxes[i][2] - boxes[i][0];
            minW = Math.min(minW, bw);
            maxW = Math.max(maxW, bw);
            expect(c, boxes[i][3] <= floor + 0.01, tag + ": " + names[i] + " reaches y " + boxes[i][3]
                    + ", the vanilla row starts at " + floor);
            if (i > 0) {
                double gap = boxes[i][0] - boxes[i - 1][2];
                expect(c, gap >= 1 - 0.01 && gap <= 3 + 0.01, tag + ": gap between " + names[i - 1] + " and " + names[i]
                        + " is " + gap + " (want 1..3, never an overlap)");
            }
        }
        expect(c, maxW - minW <= 1.01, tag + ": the bars do not share the width evenly (" + minW + " .. " + maxW + ")");
    }

    // ================================================================================================================
    // editor
    // ================================================================================================================

    @SuppressWarnings("unchecked")
    private static void editor(UiCase c, String tag) throws Exception {
        setupPredefined(c);
        Screen s = openEditor(c);
        try {
            int[] hb = HudEditorBox.box(c, s, "statbar_health");
            int[] mb = HudEditorBox.box(c, s, "statbar_mana");
            int[] db = HudEditorBox.box(c, s, "statbar_defence");
            c.note(tag + " editor: Health " + HudEditorCases.str(hb) + ", Mana " + HudEditorCases.str(mb) + ", Defence "
                    + HudEditorCases.str(db));
            // ---- Defence from Row 2 onto Above Hotbar, between Health and Mana --------------------------------------
            int gx = (db[0] + db[2]) / 2;
            int gy = (db[1] + db[3]) / 2;
            int tx = ((hb[0] + hb[2]) / 2 + (mb[0] + mb[2]) / 2) / 2;
            int ty = (hb[1] + hb[3]) / 2;
            HudEditorCases.drag(c, s, gx, gy, tx - gx, ty - gy, tag + "-editor-drag");
            String got = c.onClient(mc -> {
                Object ps = Mod.cfg(PS);
                return Mod.call(ps, "getArea", r("DEFENCE_BAR")) + " " + Mod.call(ps, "getOrder", r("HEALTH_BAR")) + ","
                        + Mod.call(ps, "getOrder", r("DEFENCE_BAR")) + "," + Mod.call(ps, "getOrder", r("MANA_BAR"));
            });
            String disk = c.onClient(mc -> {
                try {
                    return Files.readString((Path) R.getStatic(R.cls(PS), "CONFIG_PATH"), StandardCharsets.UTF_8);
                } catch (java.io.IOException e) {
                    return "";
                }
            });
            c.note(tag + " dragged Defence onto Above Hotbar between Health and Mana: area/order " + got
                    + "; saved file has defence in above_hotbar: " + disk.replaceAll("\\s", "").contains(
                    "\"defence_bar\":{\"on\":true,\"color\":" + DEFENCE));
            String[] g = got.split(" ");
            String[] o = g[1].split(",");
            expect(c, g[0].equals("ABOVE_HOTBAR") && Integer.parseInt(o[0]) < Integer.parseInt(o[1])
                    && Integer.parseInt(o[1]) < Integer.parseInt(o[2]),
                    tag + " Defence did not land in Above Hotbar between Health and Mana: " + got);
            String reloaded = c.onClient(mc -> {
                Mod.staticCall(PS, "load");
                Object ps = Mod.cfg(PS);
                return Mod.call(ps, "getArea", r("DEFENCE_BAR")) + " " + Mod.call(ps, "getOrder", r("DEFENCE_BAR"));
            });
            expect(c, reloaded.startsWith("ABOVE_HOTBAR "), tag + " the drop was not saved (reload reads " + reloaded + ")");

            // ---- Vitality onto the Hidden tray ------------------------------------------------------------------------
            int[] vb = HudEditorBox.box(c, s, "statbar_vitality");
            int[] tray = trayZone(c);
            c.note(tag + " Hidden tray " + HudEditorCases.str(tray) + ", Vitality " + HudEditorCases.str(vb));
            gx = (vb[0] + vb[2]) / 2;
            gy = (vb[1] + vb[3]) / 2;
            HudEditorCases.drag(c, s, gx, gy, (tray[0] + tray[2]) / 2 - gx, (tray[1] + tray[3]) / 2 - gy,
                    tag + "-editor-hide");
            String vArea = c.onClient(mc -> String.valueOf(Mod.call(Mod.cfg(PS), "getArea", r("VITALITY_BAR"))));
            c.note(tag + " dragged Vitality onto Hidden: " + vArea);
            expect(c, vArea.equals("HIDDEN"), tag + " Vitality dropped on the Hidden tray is in " + vArea);

            // ---- a drop on no area: nothing changes -----------------------------------------------------------------
            int[] hb2 = HudEditorBox.box(c, s, "statbar_health");
            String beforeNowhere = order(c);
            gx = (hb2[0] + hb2[2]) / 2;
            gy = (hb2[1] + hb2[3]) / 2;
            HudEditorCases.drag(c, s, gx, gy, s.width / 2 + 40 - gx, 70 - gy, null);
            String afterNowhere = order(c);
            c.note(tag + " Health dropped on no area: " + beforeNowhere + " -> " + afterNowhere);
            expect(c, beforeNowhere.equals(afterNowhere), tag + " a drop on no area changed the layout: "
                    + beforeNowhere + " -> " + afterNowhere);
            HudEditorCases.screenshot(c, tag + "-editor-after");
        } finally {
            HudEditorCases.closeEditor(c);
        }
        // Reopened: the hidden one is still listed (in the tray), so it can be dragged back.
        Screen again = openEditor(c);
        boolean listed = c.onClient(mc -> ((List<Object>) R.get(again, "shown")).stream()
                .anyMatch(e -> "statbar_vitality".equals(Mod.call(e, "id"))));
        c.note(tag + " reopened editor lists the hidden Vitality Bar: " + listed);
        expect(c, listed, tag + " a hidden readout is not listed in the HUD editor, so it can never be dragged back");
        HudEditorCases.closeEditor(c);

        // ---- in game: Health, Defence, Mana share the row, Vitality draws nothing ---------------------------------
        int[] gw = c.onClient(mc -> new int[]{mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight()});
        int hbX = gw[0] / 2 - 91;
        Map<Integer, double[]> m = measure(c, tag + "-predefined-after-drag", HEALTH, MANA, VITALITY, DEFENCE,
                BACKGROUND);
        double[] hp = m.get(HEALTH);
        double[] dp = m.get(DEFENCE);
        double[] mp = m.get(MANA);
        c.note(tag + " after the drags: Health " + gbox(hp) + ", Defence " + gbox(dp) + ", Mana " + gbox(mp)
                + ", Vitality " + gbox(m.get(VITALITY)));
        expect(c, m.get(VITALITY) == null, tag + " the hidden Vitality Bar still draws: " + gbox(m.get(VITALITY)));
        if (hp != null && dp != null && mp != null) {
            expect(c, hp[2] <= dp[0] && dp[0] < mp[0], tag + " the row is not Health, Defence, Mana from the left");
            expect(c, Math.abs(hp[0] - hbX) <= 0.5 && Math.abs(mp[2] - (hbX + 182)) <= 1.01,
                    tag + " the three bars do not span the hotbar (" + hp[0] + ".." + mp[2] + " vs " + hbX + ".."
                            + (hbX + 182) + ")");
            expect(c, Math.abs(dp[1] - hp[1]) <= 0.01, tag + " Defence is not on Health's row (y " + dp[1] + " vs "
                    + hp[1] + ")");
        } else {
            c.problem(tag + " after the drags a bar drew nothing");
        }
    }

    private static String order(UiCase c) {
        return c.onClient(mc -> {
            Object ps = Mod.cfg(PS);
            StringBuilder b = new StringBuilder();
            for (Object rr : R.cls(READOUT).getEnumConstants()) {
                if ((Boolean) Mod.call(ps, "isReadoutOn", rr)) {
                    b.append(rr).append('=').append(Mod.call(ps, "getArea", rr)).append('#')
                            .append(Mod.call(ps, "getOrder", rr)).append(' ');
                }
            }
            return b.toString().trim();
        });
    }

    /** The Hidden tray's zone {x0, y0, x1, y1} as the editor lays it out (the case's drop target). */
    private static int[] trayZone(UiCase c) {
        return c.onClient(mc -> {
            Object layout = Mod.staticCall("playerstats.StatLayout", "current");
            Object z = Mod.call(layout, "zone", area("HIDDEN"));
            return new int[]{(Integer) Mod.call(z, "x0"), (Integer) Mod.call(z, "y0"), (Integer) Mod.call(z, "x1"),
                    (Integer) Mod.call(z, "y1")};
        });
    }

    @SuppressWarnings("unchecked")
    private static Screen openEditor(UiCase c) {
        Screen s = c.onClient(mc -> {
            Mod.call(Mod.cfg(HUD), "setEditorShowAll", true);
            try {
                Screen editor = (Screen) R.cls("hud.HudEditorScreen").getConstructor(Screen.class)
                        .newInstance((Object) null);
                McCompat.setScreen(mc, editor);
                return editor;
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        c.ticks(3);
        c.onClient(mc -> {
            List<Object> shown = (List<Object>) R.get(s, "shown");
            shown.removeIf(e -> {
                String id = (String) Mod.call(e, "id");
                return !id.startsWith("statbar_") && !id.startsWith("stattext_");
            });
            return null;
        });
        c.ticks(2);
        return s;
    }

    /** The editor's drawn box of an element, from its live position (refreshed each frame for a laid-out readout). */
    private static final class HudEditorBox {
        @SuppressWarnings("unchecked")
        static int[] box(UiCase c, Screen s, String id) {
            c.ticks(1);
            return c.onClient(mc -> {
                int[] p = ((Map<String, int[]>) R.get(s, "livePositions")).get(id);
                float own = ((Map<String, Float>) R.get(s, "liveScales")).get(id);
                float g = ((Number) Mod.call(Mod.cfg(HUD), "getEffectiveGlobalScale")).floatValue();
                Object e = Mod.staticCall("hud.HudElementRegistry", "byId", id);
                int w = Math.round((Integer) Mod.call(e, "width") * own * g);
                int h = Math.round((Integer) Mod.call(e, "height") * own * g);
                return new int[]{p[0], p[1], p[0] + w, p[1] + h};
            });
        }
    }

    // ================================================================================================================
    // custom
    // ================================================================================================================

    /** Custom layout with the Health and Mana Bars at their own saved places; their drawn boxes. */
    private static List<int[]> customBoxes(UiCase c, String shot) throws Exception {
        setupPredefined(c);
        c.onClient(mc -> {
            Object ps = Mod.cfg(PS);
            Mod.call(ps, "setPredefinedLayout", false);
            Mod.call(ps, "save");
            Object hud = Mod.cfg(HUD);
            Mod.call(hud, "setPosition", "statbar_health", 300, 120);
            Mod.call(hud, "setPosition", "statbar_mana", 300, 140);
            Mod.call(hud, "save");
            return null;
        });
        c.ticks(3);
        Map<Integer, double[]> m = measure(c, shot, HEALTH, MANA);
        List<int[]> out = new ArrayList<>();
        for (int col : new int[]{HEALTH, MANA}) {
            double[] b = m.get(col);
            out.add(b == null ? null : new int[]{(int) Math.round(b[5]), (int) Math.round(b[6]), (int) Math.round(b[7]),
                    (int) Math.round(b[8])});
        }
        c.note("custom (before Predefined): Health " + gbox(m.get(HEALTH)) + ", Mana " + gbox(m.get(MANA)));
        expect(c, out.get(0) != null && out.get(1) != null, "Custom: a bar drew nothing at its own position");
        return out;
    }

    private static void customAfter(UiCase c, List<int[]> before, String tag) throws Exception {
        int[][] pos = c.onClient(mc -> {
            Object hud = Mod.cfg(HUD);
            Object ps = Mod.cfg(PS);
            Mod.call(ps, "setPredefinedLayout", false);
            Mod.call(ps, "save");
            Mod.staticCall(HUD, "load");
            return new int[][]{(int[]) Mod.call(Mod.cfg(HUD), "getPosition", "statbar_health", -1, -1),
                    (int[]) Mod.call(Mod.cfg(HUD), "getPosition", "statbar_mana", -1, -1)};
        });
        c.ticks(3);
        Map<Integer, double[]> m = measure(c, tag + "-custom-after", HEALTH, MANA);
        String[] names = {"Health", "Mana"};
        int[] cols = {HEALTH, MANA};
        c.note(tag + " saved positions after using Predefined: Health " + pos[0][0] + "," + pos[0][1] + ", Mana "
                + pos[1][0] + "," + pos[1][1]);
        expect(c, pos[0][0] == 300 && pos[0][1] == 120 && pos[1][0] == 300 && pos[1][1] == 140,
                tag + " Predefined (or its editor drags) changed a saved Custom position");
        for (int i = 0; i < 2; i++) {
            double[] b = m.get(cols[i]);
            int[] now = b == null ? null : new int[]{(int) Math.round(b[5]), (int) Math.round(b[6]),
                    (int) Math.round(b[7]), (int) Math.round(b[8])};
            c.note(tag + " back to Custom: " + names[i] + " px " + (now == null ? "none" : HudEditorCases.str(now))
                    + ", before " + (before.get(i) == null ? "none" : HudEditorCases.str(before.get(i))));
            expect(c, now != null && before.get(i) != null && java.util.Arrays.equals(now, before.get(i)),
                    tag + " switching back to Custom did not draw " + names[i] + " where it was");
        }
    }

    // ================================================================================================================
    // old config
    // ================================================================================================================

    /** A file in the old format with the Health Bar on at a saved place: loads Custom and draws there. */
    private static void oldConfig(UiCase c, String tag) throws Exception {
        Path ps = c.onClient(mc -> (Path) R.getStatic(R.cls(PS), "CONFIG_PATH"));
        Path hud = c.onClient(mc -> (Path) R.getStatic(R.cls(HUD), "CONFIG_PATH"));
        Files.writeString(ps, oldPsJson(), StandardCharsets.UTF_8);
        Files.writeString(hud, oldHudJson(), StandardCharsets.UTF_8);
        boolean predefined = c.onClient(mc -> {
            Mod.staticCall(HUD, "load");
            Mod.staticCall(PS, "load");
            return (Boolean) Mod.call(Mod.cfg(PS), "isPredefinedLayout");
        });
        c.ticks(3);
        float f = c.onClient(mc -> ((Number) Mod.staticCall("hud.AutoScale", "current")).floatValue());
        int gs = c.onClient(mc -> mc.getWindow().getGuiScale());
        Map<Integer, double[]> m = measure(c, tag + "-old-config", HEALTH, MANA);
        double[] hpx = m.get(HEALTH);
        double[] mpx = m.get(MANA);
        int wantX = Math.round(250 * f);
        int wantY = Math.round(100 * f);
        c.note(String.format(Locale.ROOT, "%s old-format file loads %s; Health Bar %s, Mana Bar %s (saved 250,100 and"
                + " 250,116 at factor %.2f -> want x %d)", tag, predefined ? "Predefined" : "Custom", gbox(hpx),
                gbox(mpx), f, wantX));
        expect(c, !predefined, tag + " an old file that draws a bar loaded as Predefined, which moves it");
        expect(c, hpx != null && Math.abs(hpx[0] - wantX) <= 0.01 && Math.abs(hpx[1] - wantY) <= 0.51,
                tag + " the old file's Health Bar is not at its saved place (" + gbox(hpx) + ", want " + wantX + ","
                        + wantY + ")");
    }

    static String oldPsJson() {
        return "{\"enabled\":true,\"hideVanillaHearts\":true,\"hideVanillaHunger\":true,\"hideVanillaArmour\":true,"
                + "\"hideVanillaAir\":true,\"showHeartsInRift\":true,\"hideHypixelStatText\":false,\"hideXpBar\":true,"
                + "\"readouts\":{\"health_bar\":{\"on\":true,\"color\":" + HEALTH + "},\"mana_bar\":{\"on\":true,"
                + "\"color\":" + MANA + "}},\"barWidth\":100,\"barHeight\":8,\"barShowValue\":false,"
                + "\"absorptionColor\":-22016,\"barBackground\":" + BACKGROUND + ",\"textShadow\":true}";
    }

    static String oldHudJson() {
        return "{\"editKeyCode\":-1,\"positions\":{\"statbar_health\":{\"x\":250,\"y\":100,\"scale\":1.0},"
                + "\"statbar_mana\":{\"x\":250,\"y\":116,\"scale\":1.0}}}";
    }

    // ---- cross-jar -------------------------------------------------------------------------------------------------

    private static Path findSeed(Path configDir) {
        try (var walk = Files.walk(configDir, 4)) {
            return walk.filter(p -> p.getFileName().toString().equals(EXPORT_FILE)).findFirst().orElse(null);
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /** Any jar: the old-format config (bars on, saved places) drawn and measured; files and boxes written to {@code out}. */
    private static void exportOld(UiCase c, Path configDir, Path out) throws Exception {
        Path ps = c.onClient(mc -> (Path) R.getStatic(R.cls(PS), "CONFIG_PATH"));
        Path hud = c.onClient(mc -> (Path) R.getStatic(R.cls(HUD), "CONFIG_PATH"));
        Files.writeString(ps, oldPsJson(), StandardCharsets.UTF_8);
        Files.writeString(hud, oldHudJson(), StandardCharsets.UTF_8);
        c.onClient(mc -> {
            Mod.staticCall(HUD, "load");
            Mod.staticCall(PS, "load");
            // Saved again by this jar, so the exported files are in its own format.
            Mod.call(Mod.cfg(HUD), "save");
            Mod.call(Mod.cfg(PS), "save");
            return null;
        });
        values(c, R.cls("playerstats.PlayerStatsFeature"), true);
        Map<Integer, double[]> m = measure(c, "export", HEALTH, MANA);
        Files.createDirectories(out);
        for (Path src : new Path[]{ps, hud}) {
            Path dst = out.resolve(configDir.relativize(src));
            Files.createDirectories(dst.getParent());
            Files.copy(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        Properties p = new Properties();
        p.setProperty("health", pxs(m.get(HEALTH)));
        p.setProperty("mana", pxs(m.get(MANA)));
        p.setProperty("window", c.onClient(mc -> mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight() + " gui "
                + mc.getWindow().getGuiScale()));
        try (var o = Files.newBufferedWriter(out.resolve(EXPORT_FILE), StandardCharsets.UTF_8)) {
            p.store(o, "407-ui-stat-bars-layout export");
        }
        c.note("export: Health " + p.getProperty("health") + ", Mana " + p.getProperty("mana") + " px at "
                + p.getProperty("window") + " -> " + out.resolve(EXPORT_FILE));
    }

    /** New jar, seeded with an export: the untouched files draw both bars at the same pixels. */
    private static void importCheck(UiCase c, Path props) throws Exception {
        Properties p = new Properties();
        try (var in = Files.newBufferedReader(props, StandardCharsets.UTF_8)) {
            p.load(in);
        }
        String window = c.onClient(mc -> mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight() + " gui "
                + mc.getWindow().getGuiScale());
        values(c, R.cls("playerstats.PlayerStatsFeature"), true);
        Map<Integer, double[]> m = measure(c, "import", HEALTH, MANA);
        String h = pxs(m.get(HEALTH));
        String mn = pxs(m.get(MANA));
        c.note("import: the old jar drew Health " + p.getProperty("health") + ", Mana " + p.getProperty("mana") + " at "
                + p.getProperty("window") + "; this jar draws Health " + h + ", Mana " + mn + " at " + window);
        expect(c, window.equals(p.getProperty("window")), "import: the window differs from the export's");
        expect(c, h.equals(p.getProperty("health")) && mn.equals(p.getProperty("mana")),
                "import: an old config draws its bars somewhere else after the update");
        Files.deleteIfExists(props);
    }

    private static String pxs(double[] b) {
        return b == null ? "none" : (int) Math.round(b[5]) + "," + (int) Math.round(b[6]) + ".." + (int) Math.round(b[7])
                + "," + (int) Math.round(b[8]);
    }

    // ================================================================================================================
    // helpers
    // ================================================================================================================

    private static Object r(String name) {
        return Mod.enumValue(READOUT, name);
    }

    private static Object area(String name) {
        return Mod.enumValue(AREA, name);
    }

    private static boolean hasMethod(Class<?> c, String name) {
        for (var m : c.getMethods()) {
            if (m.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static void expect(UiCase c, boolean ok, String what) {
        if (!ok) {
            c.problem(what);
        }
    }

    /** Survival (hearts, hunger, XP drawn) but invulnerable, with XP level 5; or back to the suite's creative. */
    private static void survival(UiCase c, boolean on) {
        if (!on) {
            dev.testkit.gametest.ui.SafeWorld.apply(c.ctx());
            return;
        }
        c.onClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(uuid);
                if (sp != null) {
                    sp.setGameMode(GameType.SURVIVAL);
                    sp.getAbilities().invulnerable = true;
                    sp.getAbilities().mayfly = true;
                    sp.getAbilities().flying = true;
                    sp.onUpdateAbilities();
                    sp.setExperienceLevels(5);
                }
            });
            return null;
        });
        for (int i = 0; i < 100; i++) {
            boolean ready = c.onClient(mc -> mc.gameMode != null && mc.gameMode.canHurtPlayer()
                    && mc.player.experienceLevel == 5);
            if (ready) {
                break;
            }
            c.ticks(1);
        }
    }

    /** Full readings for every bar (or back to "nothing read"). */
    private static void values(UiCase c, Class<?> feature, boolean on) {
        c.onClient(mc -> {
            long v = on ? 1000L : -1L;
            R.setStatic(feature, "healthCur", v);
            R.setStatic(feature, "healthMax", v);
            R.setStatic(feature, "manaCur", v);
            R.setStatic(feature, "manaMax", v);
            R.setStatic(feature, "defenceValue", on ? 250L : -1L);
            try {
                R.setStatic(feature, "vitalityCur", on ? 117L : -1L);
                R.setStatic(feature, "vitalityMax", on ? 117L : -1L);
            } catch (AssertionError ignored) {
                // no vitality in this jar
            }
            return null;
        });
        c.ticks(2);
    }

    /**
     * One screenshot of the HUD (no screen open); for each colour, its box. Returns colour -> {x0, y0, x1, y1, solid,
     * px0, py0, px1, py1}: GUI units (x1/y1 exclusive), 1 when every pixel of the box is that colour, then framebuffer
     * pixels (inclusive). Absent when no pixel has the colour.
     */
    static Map<Integer, double[]> measure(UiCase c, String shot, int... colours) throws Exception {
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            McCompat.clearChatAndToasts(mc);
            return null;
        });
        c.ticks(4);
        int gs = c.onClient(mc -> mc.getWindow().getGuiScale());
        String name = c.name() + "-" + shot;
        Path taken = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(name));
        Path kept = dev.testkit.harness.Report.screenshot(name, taken);
        c.note("picture " + name + " -> " + (kept != null ? kept : taken));
        BufferedImage img = javax.imageio.ImageIO.read(taken.toFile());
        Map<Integer, double[]> out = new LinkedHashMap<>();
        for (int col : colours) {
            int want = col & 0xFFFFFF;
            int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = -1, y1 = -1, n = 0;
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    if ((img.getRGB(x, y) & 0xFFFFFF) == want) {
                        n++;
                        x0 = Math.min(x0, x);
                        y0 = Math.min(y0, y);
                        x1 = Math.max(x1, x);
                        y1 = Math.max(y1, y);
                    }
                }
            }
            if (n == 0) {
                continue;
            }
            boolean solid = n == (x1 - x0 + 1) * (y1 - y0 + 1);
            out.put(col, new double[]{x0 / (double) gs, y0 / (double) gs, (x1 + 1) / (double) gs, (y1 + 1) / (double) gs,
                    solid ? 1 : 0, x0, y0, x1, y1});
        }
        return out;
    }

    private static String gbox(double[] b) {
        if (b == null) {
            return "none";
        }
        return String.format(Locale.ROOT, "%.1f,%.1f..%.1f,%.1f%s", b[0], b[1], b[2], b[3], b[4] >= 1 ? "" : " (not solid)");
    }
}
