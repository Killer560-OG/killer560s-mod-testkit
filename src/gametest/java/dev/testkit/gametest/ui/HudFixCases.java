package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.GameType;

import java.awt.image.BufferedImage;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 441-447: killer560's in-game HUD report of 2026-10-07 (mod branch hud-fixes), each fix measured on screenshots or on
 * what the element really draws. Every case first proves its HUD drew (a pixel of its colour, a text run, a draw
 * stamp) so no pass is vacuous. Auto Scale is off in every case, so one GUI unit is exactly {@code guiScale} pixels.
 * {@code TESTKIT_HUDFIX_SHOTS=<dir>} also copies the GUI-scale-2 pictures there (before/after comparisons).
 *
 * <ul>
 *   <li>441-ui-bars-under-screens: "The custom health bars should show even in my inventory." A Health Bar straddling
 *       the survival inventory's left edge: with the inventory open, the half outside the panel changes the picture
 *       and has no pixel of the bar's raw colour (drawn, dimmed like vanilla's hotbar), the half under the panel does
 *       not change (the panel draws over it).</li>
 *   <li>442-ui-bars-absorption: "Whenever I get absorption it breaks the health one." Hypixel's gold "(6)12,000/10,464"
 *       health segment read through the real action-bar path (was 612,000); the bar's absorption segment measured for
 *       health over max and for the player's own absorption (Absorption I), the health fill unchanged.</li>
 *   <li>443-ui-bars-predefined-scale: "They are different scales when you use the predefined snap." Three bars with
 *       different own scales and thicknesses draw at one height in Predefined; Predefined Scale changes all of them;
 *       scrolling one in the HUD editor scales them all.</li>
 *   <li>444-ui-bars-padding: "that text almost feels trapped by the boxes." The number's pixels against its bar's, in
 *       GUI units, at GUI scale 2 and 4, in both layouts: at least 1.5 above and below, 3 at each end.</li>
 *   <li>445-ui-map-info: the Dungeon Map's Extra Info lines are smaller (text rows at most 7 units tall), inside the
 *       map's width, one row each; and Split Timers saved on top of them is drawn clear of the map's box.</li>
 *   <li>446-ui-split-format: "have the no lag in parentheses to the right of the total." Total reads "Total: X (Y)",
 *       no "No Lag" line, every split row "X (Y)", the Lag line under its toggle; the chat suffix the same.</li>
 *   <li>447-ui-scoreboard-solo: "Solo" (in any colour-code form) and the "Keys:" line are known dungeon lines; an
 *       unknown line is logged once per shape and, by default, never put in chat.</li>
 * </ul>
 */
final class HudFixCases {

    private static final String PS = "playerstats.PlayerStatsConfig";
    private static final String HUD = "hud.HudConfig";
    private static final String MAP = "livemap.LiveMapConfig";
    private static final String MAPPING = "mapping.MappingConfig";
    private static final String SPLITS = "splittimers.SplitTimersConfig";
    private static final String BOARD = "scoreboard.CustomScoreboardConfig";
    private static final String READOUT = "playerstats.StatElements$Readout";
    private static final String AREA = "playerstats.StatLayout$Area";
    private static final String FEATURE = "playerstats.PlayerStatsFeature";

    static final int HEALTH = 0xFFFE01FD;
    static final int MANA = 0xFF01FEFD;
    static final int DEFENCE = 0xFF02FD03;
    static final int ABSORB = 0xFFFDFE02;
    static final int BACKGROUND = 0xFF0B0C0D;
    static final int MAP_BG = 0xFF0C2D4E;

    private HudFixCases() {
    }

    // ================================================================================================================
    // 441 bars under screens
    // ================================================================================================================

    static void underScreens(UiCase c) throws Exception {
        Map<Path, byte[]> saved = snapshot(c, PS, HUD);
        int[] window = HudEditorCases.windowSize(c);
        int gui = c.onClient(mc -> mc.options.guiScale().get());
        try {
            HudEditorCases.setWindow(c, 854, 480, 2);
            survival(c, true);
            custom(c, new String[]{"HEALTH_BAR"}, 8, false);
            values(c, true);
            int gs = c.onClient(mc -> mc.getWindow().getGuiScale());

            // The inventory first, to know where its panel is; the bar then goes half outside, half under it.
            c.onClient(mc -> {
                McCompat.setScreen(mc, new InventoryScreen(mc.player));
                return null;
            });
            c.ticks(5);
            int[] panel = c.onClient(mc -> {
                Screen s = McCompat.screen(mc);
                if (!(s instanceof AbstractContainerScreen<?>)) {
                    return null;
                }
                return new int[]{(Integer) R.get(s, "leftPos"), (Integer) R.get(s, "topPos"),
                        (Integer) R.get(s, "imageWidth"), (Integer) R.get(s, "imageHeight")};
            });
            String screenName = c.onClient(mc -> String.valueOf(McCompat.screen(mc) == null ? null
                    : McCompat.screen(mc).getClass().getSimpleName()));
            if (panel == null) {
                c.problem("no container screen opened (" + screenName + ")");
                return;
            }
            int bx = panel[0] - 60;
            int by = panel[1] + panel[3] / 2;
            c.note(String.format(Locale.ROOT, "%s panel %d,%d %dx%d (GUI); Health Bar at %d,%d, 100x8: 60 units outside,"
                    + " 40 under the panel", screenName, panel[0], panel[1], panel[2], panel[3], bx, by));
            place(c, "statbar_health", bx, by);

            // In game with no screen: the bar draws where it was put (the HUD is live at all).
            // Within a few levels of its colour: in survival the vanilla vignette (drawn first) can shift it by one or two.
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
            c.ticks(4);
            BufferedImage plainShot = shot(c, "no-screen");
            int[] hb = near(plainShot, HEALTH & 0xFFFFFF, 12);
            c.note("no screen: Health Bar px " + (hb == null ? "none" : hb[0] + "," + hb[1] + ".." + hb[2] + "," + hb[3]));
            check(c, hb != null && hb[0] == bx * gs && hb[2] == (bx + 100) * gs - 1,
                    "with no screen open the Health Bar did not draw at " + bx + ".." + (bx + 100));

            c.onClient(mc -> {
                McCompat.setScreen(mc, new InventoryScreen(mc.player));
                return null;
            });
            c.ticks(5);
            BufferedImage on = shot(c, "inventory-bar-on");
            c.onClient(mc -> {
                Mod.call(Mod.cfg(PS), "setReadoutOn", r("HEALTH_BAR"), false);
                return null;
            });
            c.ticks(4);
            BufferedImage off = shot(c, "inventory-bar-off");
            boolean stillOpen = c.onClient(mc -> McCompat.screen(mc) instanceof AbstractContainerScreen<?>);
            check(c, stillOpen, "the inventory closed during the case");

            int outX0 = bx * gs;
            int outX1 = panel[0] * gs;
            int inX0 = (panel[0] + 2) * gs;
            int inX1 = (bx + 100) * gs;
            int y0 = by * gs;
            int y1 = (by + 8) * gs;
            int outside = changed(on, off, outX0, y0, outX1, y1);
            int outsideArea = (outX1 - outX0) * (y1 - y0);
            int under = changed(on, off, inX0, y0, inX1, y1);
            int underArea = (inX1 - inX0) * (y1 - y0);
            int elsewhere = changed(on, off, 0, 0, on.getWidth(), on.getHeight()) - changed(on, off, outX0 - gs, y0 - gs,
                    inX1 + gs, y1 + gs);
            c.note(String.format(Locale.ROOT, "inventory open: bar ON vs OFF changes %d of %d px outside the panel (%.0f%%),"
                    + " %d of %d px under it, %d px anywhere else", outside, outsideArea, 100.0 * outside / outsideArea,
                    under, underArea, elsewhere));
            check(c, outside >= outsideArea * 0.9, "with the inventory open the Health Bar is not drawn beside the panel"
                    + " (" + outside + " of " + outsideArea + " px changed) - the bars still hide behind screens");
            check(c, under <= underArea * 0.02, "the Health Bar shows through the inventory panel (" + under + " of "
                    + underArea + " px changed) - it must draw under the screen, not over it");
            // Behind the screen means behind its dimmed background too, as the vanilla hotbar is: not one pixel of the
            // bar's own colour (hud-fixes-2 drew it from Fabric's addLast layer, which vanilla defers past the dim).
            int raw = 0;
            for (int y = y0; y < y1; y++) {
                for (int x = outX0; x < outX1; x++) {
                    if ((on.getRGB(x, y) & 0xFFFFFF) == (HEALTH & 0xFFFFFF)) {
                        raw++;
                    }
                }
            }
            int sample = on.getRGB((outX0 + outX1) / 2, (y0 + y1) / 2) & 0xFFFFFF;
            c.note(String.format(Locale.ROOT, "beside the panel the bar reads #%06X (its colour #%06X), %d px undimmed",
                    sample, HEALTH & 0xFFFFFF, raw));
            check(c, outside == 0 || raw == 0, "the Health Bar draws over the screen's dimmed background (" + raw
                    + " px in its raw colour) - vanilla's hotbar is dimmed there");
            // The inventory's player model idles between the two pictures (46 px on main 81c894ef with no bar at all).
            check(c, elsewhere <= 400, elsewhere + " px changed away from the bar - something else drew with it");
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
            values(c, false);
            survival(c, false);
            HudEditorCases.restoreWindow(c, window, gui);
            restore(c, saved);
        }
    }

    // ================================================================================================================
    // 442 absorption
    // ================================================================================================================

    static void absorption(UiCase c) throws Exception {
        Map<Path, byte[]> saved = snapshot(c, PS, HUD);
        int[] window = HudEditorCases.windowSize(c);
        int gui = c.onClient(mc -> mc.options.guiScale().get());
        try {
            HudEditorCases.setWindow(c, 854, 480, 2);
            custom(c, new String[]{"HEALTH_BAR"}, 8, false);
            c.onClient(mc -> {
                Mod.call(Mod.cfg(PS), "setAbsorptionColor", ABSORB);
                Mod.call(Mod.cfg(PS), "save");
                return null;
            });
            place(c, "statbar_health", 100, 100);

            // ---- Hypixel's action bar through the real path ------------------------------------------------------
            String normal = "§c10,464/10,464❤     §a1,234§a❈ Defense     §b12,699/12,699✎ Mana";
            String gold = "§612,000/10,464❤     §a1,234§a❈ Defense     §b12,699/12,699✎ Mana";
            actionBar(c, normal);
            long[] read1 = health(c);
            actionBar(c, gold);
            long[] read2 = health(c);
            c.note("action bar " + show(normal) + " -> health " + read1[0] + "/" + read1[1]);
            c.note("action bar " + show(gold) + " (absorption: gold) -> health " + read2[0] + "/" + read2[1]);
            check(c, read1[0] == 10464 && read1[1] == 10464, "the plain health segment read as " + read1[0] + "/" + read1[1]);
            check(c, read2[0] == 12000 && read2[1] == 10464, "the gold (absorption) health segment read as " + read2[0]
                    + "/" + read2[1] + ", want 12000/10464 - the colour code's digit was taken into the number");
            Map<Integer, double[]> m = StatBarsLayoutCases.measure(c, "hypixel-gold", HEALTH, ABSORB);
            segment(c, "gold action bar 12,000/10,464", m, 100, 100, 100 * 1536.0 / 10464, 200);

            // ---- health over max, set directly -------------------------------------------------------------------
            set(c, 12000, 10000);
            m = StatBarsLayoutCases.measure(c, "over-max", HEALTH, ABSORB);
            segment(c, "12,000/10,000", m, 100, 100, 20, 200);

            // ---- the player's own absorption: Absorption I is 4 of 20 vanilla health, a fifth of max ---------------
            set(c, 5000, 10000);
            serverPlayer(c, sp -> sp.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 2400, 0)));
            for (int i = 0; i < 40 && c.onClient(mc -> mc.player.getAbsorptionAmount()) <= 0f; i++) {
                c.ticks(1);
            }
            float[] abs = c.onClient(mc -> new float[]{mc.player.getAbsorptionAmount(), mc.player.getMaxHealth()});
            c.note("player absorption " + abs[0] + " of max health " + abs[1]);
            check(c, abs[0] > 0f, "Absorption I gave the player no absorption - the check would be vacuous");
            m = StatBarsLayoutCases.measure(c, "player-absorption", HEALTH, ABSORB);
            double[] hp = m.get(HEALTH);
            c.note("5,000/10,000 with absorption: health " + gbox(hp) + ", absorption " + gbox(m.get(ABSORB)));
            check(c, hp != null && Math.abs(hp[0] - 100) <= 0.01 && Math.abs(hp[2] - 150) <= 0.51,
                    "the health fill is not half the bar with absorption on (" + gbox(hp) + ")");
            segment(c, "5,000/10,000 + player absorption", m, 100, 100, 100.0 * abs[0] / abs[1], 150 + 100.0 * abs[0]
                    / abs[1]);
        } finally {
            serverPlayer(c, sp -> sp.removeAllEffects());
            values(c, false);
            HudEditorCases.restoreWindow(c, window, gui);
            restore(c, saved);
        }
    }

    /** The absorption segment: {@code len} units long, ending at {@code end}, full bar height, solid; the health fill
     *  starts at the bar's left. */
    private static void segment(UiCase c, String tag, Map<Integer, double[]> m, int barX, int barY, double len,
                                double end) {
        double[] hp = m.get(HEALTH);
        double[] ap = m.get(ABSORB);
        c.note(tag + ": health " + gbox(hp) + ", absorption " + gbox(ap) + String.format(Locale.ROOT,
                " (want absorption %.1f units ending at %.1f)", len, end));
        if (hp == null || ap == null) {
            c.problem(tag + ": " + (hp == null ? "no health fill" : "no absorption segment") + " drawn");
            return;
        }
        check(c, Math.abs(hp[0] - barX) <= 0.01, tag + ": the health fill does not start at the bar's left");
        check(c, ap[4] >= 1, tag + ": the absorption segment is not one solid block");
        check(c, Math.abs((ap[2] - ap[0]) - len) <= 1.01, tag + String.format(Locale.ROOT,
                ": absorption segment is %.1f units, want %.1f", ap[2] - ap[0], len));
        check(c, Math.abs(ap[2] - end) <= 1.01, tag + ": absorption segment ends at " + ap[2] + ", want " + end);
        check(c, Math.abs(ap[1] - hp[1]) <= 0.01 && Math.abs(ap[3] - hp[3]) <= 0.01,
                tag + ": the absorption segment is not the bar's height");
        check(c, ap[2] <= barX + 100 + 0.01, tag + ": the absorption segment runs past the bar's end");
    }

    // ================================================================================================================
    // 443 predefined scale
    // ================================================================================================================

    static void predefinedScale(UiCase c) throws Exception {
        Map<Path, byte[]> saved = snapshot(c, PS, HUD);
        int[] window = HudEditorCases.windowSize(c);
        int gui = c.onClient(mc -> mc.options.guiScale().get());
        boolean has = c.onClient(mc -> hasMethod(R.cls(PS), "getPredefinedScale"));
        try {
            HudEditorCases.setWindow(c, 854, 480, 2);
            c.onClient(mc -> {
                Object ps = Mod.cfg(PS);
                Mod.call(ps, "setEnabled", true);
                Mod.call(ps, "setPredefinedLayout", true);
                for (Object rr : R.cls(READOUT).getEnumConstants()) {
                    Mod.call(ps, "setReadoutOn", rr, false);
                }
                String[][] on = {{"HEALTH_BAR", "ABOVE_HOTBAR"}, {"MANA_BAR", "ABOVE_HOTBAR"},
                        {"DEFENCE_BAR", "ABOVE_HOTBAR_2"}};
                for (String[] o : on) {
                    Mod.call(ps, "setReadoutOn", r(o[0]), true);
                    Mod.call(ps, "moveTo", r(o[0]), Mod.enumValue(AREA, o[1]), null);
                }
                Mod.call(ps, "setReadoutColor", r("HEALTH_BAR"), HEALTH);
                Mod.call(ps, "setReadoutColor", r("MANA_BAR"), MANA);
                Mod.call(ps, "setReadoutColor", r("DEFENCE_BAR"), DEFENCE);
                Mod.call(ps, "setBarBackground", BACKGROUND);
                Mod.call(ps, "setBarShowValue", false);
                Mod.call(ps, "setBarWidth", 100);
                Mod.call(ps, "setBarHeight", 6);
                // Each bar its own thickness and its own HUD-editor scale, as a Custom setup can leave behind.
                Mod.call(ps, "setBarHeight", r("HEALTH_BAR"), 4);
                Mod.call(ps, "setBarHeight", r("MANA_BAR"), 14);
                if (has) {
                    Mod.call(ps, "setPredefinedScale", 1.0f);
                }
                Mod.call(ps, "save");
                Object hud = Mod.cfg(HUD);
                Mod.call(hud, "setScale", "statbar_health", 1.6f);
                Mod.call(hud, "setScale", "statbar_mana", 0.7f);
                Mod.call(hud, "setScale", "statbar_defence", 1.2f);
                Mod.call(hud, "setGlobalScale", 1.0f);
                Mod.call(hud, "setAutoScale", false);
                Mod.call(hud, "save");
                return null;
            });
            values(c, true);
            if (!has) {
                c.problem("this jar has no Predefined Scale (PlayerStatsConfig.getPredefinedScale)");
            }
            heights(c, "scale-1.0", 6.0);
            if (has) {
                c.onClient(mc -> {
                    Mod.call(Mod.cfg(PS), "setPredefinedScale", 1.5f);
                    return null;
                });
                heights(c, "scale-1.5", 9.0);
                scrollInEditor(c);
            }
            // Custom keeps each bar's own size (a sanity check that the setup really differs).
            c.onClient(mc -> {
                Mod.call(Mod.cfg(PS), "setPredefinedLayout", false);
                return null;
            });
            Map<Integer, double[]> m = StatBarsLayoutCases.measure(c, "custom", HEALTH, MANA, DEFENCE);
            c.note("Custom (own scales and thicknesses): heights Health " + h(m.get(HEALTH)) + ", Mana " + h(m.get(MANA))
                    + ", Defence " + h(m.get(DEFENCE)));
            check(c, m.get(HEALTH) != null && m.get(MANA) != null && Math.abs(h(m.get(HEALTH)) - h(m.get(MANA))) > 1,
                    "in Custom the two bars' own sizes did not differ - the Predefined check was not tested against"
                            + " differing sizes");
        } finally {
            HudEditorCases.closeEditor(c);
            values(c, false);
            HudEditorCases.restoreWindow(c, window, gui);
            restore(c, saved);
        }
    }

    private static void heights(UiCase c, String tag, double want) throws Exception {
        Map<Integer, double[]> m = StatBarsLayoutCases.measure(c, tag, HEALTH, MANA, DEFENCE);
        double hh = h(m.get(HEALTH));
        double mh = h(m.get(MANA));
        double dh = h(m.get(DEFENCE));
        c.note(String.format(Locale.ROOT, "Predefined %s: heights Health %.1f, Mana %.1f, Defence %.1f (want all %.1f);"
                + " Health %s, Mana %s, Defence %s", tag, hh, mh, dh, want, gbox(m.get(HEALTH)), gbox(m.get(MANA)),
                gbox(m.get(DEFENCE))));
        if (m.get(HEALTH) == null || m.get(MANA) == null || m.get(DEFENCE) == null) {
            c.problem(tag + ": a bar drew no pixel");
            return;
        }
        check(c, Math.abs(hh - mh) <= 0.01 && Math.abs(hh - dh) <= 0.01, tag + ": the Predefined bars are different"
                + " heights (" + hh + ", " + mh + ", " + dh + ")");
        check(c, Math.abs(hh - want) <= 0.51, tag + ": the Predefined bars are " + hh + " tall, want " + want);
    }

    @SuppressWarnings("unchecked")
    private static void scrollInEditor(UiCase c) {
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
        String got = c.onClient(mc -> {
            float before = ((Number) Mod.call(Mod.cfg(PS), "getPredefinedScale")).floatValue();
            int[] p = ((Map<String, int[]>) R.get(s, "livePositions")).get("statbar_mana");
            if (p == null) {
                return "no live position for the Mana Bar";
            }
            s.mouseScrolled(p[0] + 2, p[1] + 2, 0, 1);
            float after = ((Number) Mod.call(Mod.cfg(PS), "getPredefinedScale")).floatValue();
            Map<String, Float> live = (Map<String, Float>) R.get(s, "liveScales");
            return String.format(Locale.ROOT, "%.2f->%.2f health %.2f mana %.2f defence %.2f", before, after,
                    live.get("statbar_health"), live.get("statbar_mana"), live.get("statbar_defence"));
        });
        c.note("HUD editor: one scroll up over the Mana Bar: Predefined Scale " + got);
        Matcher mm = Pattern.compile("([\\d.]+)->([\\d.]+) health ([\\d.]+) mana ([\\d.]+) defence ([\\d.]+)").matcher(got);
        if (!mm.matches()) {
            c.problem("editor scroll: " + got);
        } else {
            double after = Double.parseDouble(mm.group(2));
            check(c, Math.abs(after - Double.parseDouble(mm.group(1)) - 0.1) <= 0.011,
                    "scrolling a Predefined bar in the HUD editor did not raise Predefined Scale by 0.1: " + got);
            check(c, Math.abs(Double.parseDouble(mm.group(3)) - after) <= 0.001
                    && Math.abs(Double.parseDouble(mm.group(4)) - after) <= 0.001
                    && Math.abs(Double.parseDouble(mm.group(5)) - after) <= 0.001,
                    "after the scroll the editor's bars are not all at the new scale: " + got);
        }
        HudEditorCases.closeEditor(c);
    }

    // ================================================================================================================
    // 444 padding
    // ================================================================================================================

    static void padding(UiCase c) throws Exception {
        Map<Path, byte[]> saved = snapshot(c, PS, HUD);
        int[] window = HudEditorCases.windowSize(c);
        int gui = c.onClient(mc -> mc.options.guiScale().get());
        try {
            int[][] windows = {{854, 480, 2}, {1708, 960, 4}};
            for (int[] w : windows) {
                HudEditorCases.setWindow(c, w[0], w[1], w[2]);
                for (boolean predefined : new boolean[]{true, false}) {
                    String tag = "gui" + w[2] + "-" + (predefined ? "predefined" : "custom");
                    if (predefined) {
                        c.onClient(mc -> {
                            Object ps = Mod.cfg(PS);
                            Mod.call(ps, "setEnabled", true);
                            Mod.call(ps, "setPredefinedLayout", true);
                            return null;
                        });
                        custom(c, new String[]{"HEALTH_BAR", "MANA_BAR"}, 8, true);
                        c.onClient(mc -> {
                            Object ps = Mod.cfg(PS);
                            Mod.call(ps, "setPredefinedLayout", true);
                            Mod.call(ps, "moveTo", r("HEALTH_BAR"), Mod.enumValue(AREA, "ABOVE_HOTBAR"), null);
                            Mod.call(ps, "moveTo", r("MANA_BAR"), Mod.enumValue(AREA, "ABOVE_HOTBAR"), null);
                            Mod.call(ps, "save");
                            return null;
                        });
                    } else {
                        custom(c, new String[]{"HEALTH_BAR", "MANA_BAR"}, 8, true);
                        place(c, "statbar_health", 40, 60);
                        place(c, "statbar_mana", 40, 100);
                    }
                    set(c, 10464, 10464);
                    c.onClient(mc -> {
                        R.setStatic(R.cls(FEATURE), "manaCur", 12699L);
                        R.setStatic(R.cls(FEATURE), "manaMax", 12699L);
                        return null;
                    });
                    c.ticks(2);
                    c.onClient(mc -> {
                        McCompat.setScreen(mc, null);
                        McCompat.clearChatAndToasts(mc);
                        return null;
                    });
                    c.ticks(4);
                    int gs = c.onClient(mc -> mc.getWindow().getGuiScale());
                    BufferedImage img = shot(c, tag);
                    pad(c, tag + " Health", img, HEALTH, gs);
                    pad(c, tag + " Mana", img, MANA, gs);
                }
            }
        } finally {
            values(c, false);
            HudEditorCases.restoreWindow(c, window, gui);
            restore(c, saved);
        }
    }

    /** The bar's box (its colour's bounding box) against the number's pixels (white and its shadow) near it. */
    private static void pad(UiCase c, String tag, BufferedImage img, int colour, int gs) {
        int[] box = bbox(img, colour & 0xFFFFFF, 0, 0, img.getWidth(), img.getHeight());
        if (box == null) {
            c.problem(tag + ": the bar drew no pixel");
            return;
        }
        // One unit round the bar: a number spilling out of it still counts (and reads as no room), while the hotbar
        // below a Predefined row and the next bar beside it (2 units off) stay out of the search.
        int m = gs;
        int x0 = Math.max(0, box[0] - m);
        int y0 = Math.max(0, box[1] - m);
        int x1 = Math.min(img.getWidth(), box[2] + 1 + m);
        int y1 = Math.min(img.getHeight(), box[3] + 1 + m);
        int tx0 = Integer.MAX_VALUE, ty0 = Integer.MAX_VALUE, tx1 = -1, ty1 = -1, n = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int rgb = img.getRGB(x, y) & 0xFFFFFF;
                if (rgb == 0xFFFFFF || rgb == 0x3F3F3F) {
                    n++;
                    tx0 = Math.min(tx0, x);
                    ty0 = Math.min(ty0, y);
                    tx1 = Math.max(tx1, x);
                    ty1 = Math.max(ty1, y);
                }
            }
        }
        if (n == 0) {
            c.problem(tag + ": no number drawn on the bar - the padding check would be vacuous");
            return;
        }
        double top = (ty0 - box[1]) / (double) gs;
        double bottom = (box[3] - ty1) / (double) gs;
        double left = (tx0 - box[0]) / (double) gs;
        double right = (box[2] - tx1) / (double) gs;
        c.note(String.format(Locale.ROOT, "%s: bar %dx%d px (%.1fx%.1f units), number %dx%d px; room top %.1f, bottom"
                + " %.1f, left %.1f, right %.1f units", tag, box[2] - box[0] + 1, box[3] - box[1] + 1,
                (box[2] - box[0] + 1) / (double) gs, (box[3] - box[1] + 1) / (double) gs, tx1 - tx0 + 1, ty1 - ty0 + 1,
                top, bottom, left, right));
        check(c, top >= 1.5 && bottom >= 1.5, tag + String.format(Locale.ROOT,
                ": the number has %.1f units above and %.1f below it inside the bar, want at least 1.5", top, bottom));
        check(c, left >= 3 && right >= 3, tag + String.format(Locale.ROOT,
                ": the number has %.1f units left and %.1f right of it inside the bar, want at least 3", left, right));
    }

    // ================================================================================================================
    // 445 map info + split timers guard
    // ================================================================================================================

    static void mapInfo(UiCase c) throws Exception {
        Map<Path, byte[]> saved = snapshot(c, PS, HUD, MAP, MAPPING, SPLITS);
        int[] window = HudEditorCases.windowSize(c);
        int gui = c.onClient(mc -> mc.options.guiScale().get());
        try {
            HudEditorCases.setWindow(c, 854, 480, 2);
            c.onClient(mc -> {
                Mod.staticCall("secrets.DungeonState", "setRoomSim", true);
                Object map = Mod.cfg(MAP);
                Mod.call(map, "setEnabled", true);
                Mod.call(map, "setRoomPx", 16);
                Mod.call(map, "setMapBackground", MAP_BG);
                Mod.call(map, "setMapBorderColor", 0);
                Mod.call(map, "save");
                Mod.call(Mod.cfg(MAPPING), "setExtraInfoEnabled", true);
                Mod.call(Mod.cfg(MAPPING), "save");
                Mod.call(Mod.cfg(SPLITS), "setEnabled", false);
                Object hud = Mod.cfg(HUD);
                Mod.call(hud, "setAutoScale", false);
                Mod.call(hud, "setGlobalScale", 1.0f);
                Mod.call(hud, "setScale", "live_map", 1.0f);
                Mod.call(hud, "setPosition", "live_map", 10, 10);
                Mod.call(hud, "save");
                return null;
            });
            c.ticks(10);
            int gs = c.onClient(mc -> mc.getWindow().getGuiScale());
            int[] size = c.onClient(mc -> {
                Object e = Mod.staticCall("hud.HudElementRegistry", "byId", "live_map");
                return new int[]{(Integer) Mod.call(e, "width"), (Integer) Mod.call(e, "height")};
            });
            @SuppressWarnings("unchecked")
            List<String> lines = c.onClient(mc -> (List<String>) Mod.staticCall("scorecalc.ScoreCalculatorFeature",
                    "mapInfoLines", false));
            int mapPx = size[0];
            c.note("Dungeon Map " + size[0] + "x" + size[1] + " units at 10,10 (map " + mapPx + " square), Extra Info "
                    + lines.size() + " lines: " + lines);
            BufferedImage on = shot(c, "map-info-on");
            c.onClient(mc -> {
                Mod.call(Mod.cfg(MAPPING), "setExtraInfoEnabled", false);
                return null;
            });
            c.ticks(4);
            BufferedImage off = shot(c, "map-info-off");
            check(c, size[1] > mapPx, "the map's box has no Extra Info section - the check would be vacuous");

            // Text pixels: inside the Extra Info panel (opaque, so nothing in the world shows through it), anything
            // that is not the panel's own colour. Beside the panel: the labels' exact orange (every line starts with
            // one), so a moving mob or the map's own player arrow out there is never read as spilled text.
            int top = (10 + mapPx) * gs;
            int panelX0 = 10 * gs;
            int panelX1 = (10 + mapPx) * gs;
            int panelY1 = Math.min(on.getHeight(), (10 + size[1]) * gs);
            int[] rows = new int[on.getHeight()];
            int tx0 = Integer.MAX_VALUE, tx1 = -1, n = 0;
            for (int y = top; y < panelY1; y++) {
                for (int x = panelX0; x < panelX1; x++) {
                    if ((on.getRGB(x, y) & 0xFFFFFF) != (MAP_BG & 0xFFFFFF)) {
                        rows[y]++;
                        n++;
                        tx0 = Math.min(tx0, x);
                        tx1 = Math.max(tx1, x);
                    }
                }
            }
            int spill = 0;
            for (int y = top; y < panelY1; y++) {
                for (int x = panelX1; x < Math.min(on.getWidth(), panelX1 + 60 * gs); x++) {
                    if ((on.getRGB(x, y) & 0xFFFFFF) == 0xFFAA00) {
                        spill++;
                        tx1 = Math.max(tx1, x);
                    }
                }
            }
            c.note("Extra Info orange label pixels beside the panel: " + spill);
            List<int[]> bands = new ArrayList<>();
            int start = -1;
            for (int y = top; y <= panelY1; y++) {
                boolean ink = y < panelY1 && rows[y] > 0;
                if (ink && start < 0) {
                    start = y;
                } else if (!ink && start >= 0) {
                    bands.add(new int[]{start, y - 1});
                    start = -1;
                }
            }
            StringBuilder b = new StringBuilder();
            double tallest = 0;
            for (int[] band : bands) {
                double hgt = (band[1] - band[0] + 1) / (double) gs;
                tallest = Math.max(tallest, hgt);
                b.append(String.format(Locale.ROOT, " %.1f..%.1f", band[0] / (double) gs, (band[1] + 1) / (double) gs));
            }
            double leftU = tx0 / (double) gs;
            double rightU = (tx1 + 1) / (double) gs;
            c.note(String.format(Locale.ROOT, "Extra Info text: %d px, x %.1f..%.1f units (map 10..%d), %d row band(s):%s;"
                    + " tallest %.1f units", n, leftU, rightU, 10 + mapPx, bands.size(), b, tallest));
            check(c, n > 0, "Extra Info drew no text under the map - nothing measured");
            check(c, leftU >= 10 && rightU <= 10 + mapPx, String.format(Locale.ROOT,
                    "Extra Info text runs outside the map's width (%.1f..%.1f, map 10..%d)", leftU, rightU, 10 + mapPx));
            check(c, bands.size() == lines.size(), "Extra Info drew " + bands.size() + " separate text rows for "
                    + lines.size() + " lines - rows touch or overlap");
            check(c, tallest <= 7.0, String.format(Locale.ROOT, "an Extra Info row is %.1f units tall, want at most 7"
                    + " (smaller than the font's 9)", tallest));

            // ---- Split Timers saved on top of the Extra Info lines --------------------------------------------------
            c.onClient(mc -> {
                Mod.call(Mod.cfg(MAPPING), "setExtraInfoEnabled", true);
                Object st = Mod.cfg(SPLITS);
                Mod.call(st, "setEnabled", true);
                Mod.call(st, "setLaglessTimes", true);
                // Gone since mod dungeon-fixes (2026-10-08): the layout is fixed and always draws Total and Lag.
                trySet(st, "setTotalWithLag", true);
                trySet(st, "setTotalWithoutLag", true);
                trySet(st, "setLagLostLine", true);
                Object hud = Mod.cfg(HUD);
                Mod.call(hud, "setScale", "split_timers", 1.0f);
                Mod.call(hud, "setPosition", "split_timers", 12, 10 + mapPx + 4);
                Mod.call(hud, "save");
                return null;
            });
            injectRun(c);
            c.ticks(10);
            String boxes = c.onClient(mc -> {
                Object map = Mod.staticCall("hud.HudElementRegistry", "byId", "live_map");
                Object st = Mod.staticCall("hud.HudElementRegistry", "byId", "split_timers");
                int[] mb = drawnBox(map);
                int[] sb = drawnBox(st);
                long since = ((Number) Mod.staticCall("hud.HudSeen", "msSince", "split_timers",
                        System.currentTimeMillis())).longValue();
                long mapSince = ((Number) Mod.staticCall("hud.HudSeen", "msSince", "live_map",
                        System.currentTimeMillis())).longValue();
                return mb[0] + " " + mb[1] + " " + mb[2] + " " + mb[3] + " " + sb[0] + " " + sb[1] + " " + sb[2] + " "
                        + sb[3] + " " + since + " " + mapSince;
            });
            String[] v = boxes.split(" ");
            int[] mb = {Integer.parseInt(v[0]), Integer.parseInt(v[1]), Integer.parseInt(v[2]), Integer.parseInt(v[3])};
            int[] sb = {Integer.parseInt(v[4]), Integer.parseInt(v[5]), Integer.parseInt(v[6]), Integer.parseInt(v[7])};
            long since = Long.parseLong(v[8]);
            long mapSince = Long.parseLong(v[9]);
            c.note("saved Split Timers at 12," + (10 + mapPx + 4) + " (inside the Extra Info lines): map box "
                    + HudEditorCases.str(mb) + ", Split Timers drawn box " + HudEditorCases.str(sb) + "; drew " + since
                    + " ms ago, map " + mapSince + " ms ago");
            shot(c, "map-and-splits");
            check(c, since < 1000 && mapSince < 1000, "Split Timers or the map did not draw - the overlap check would be"
                    + " vacuous");
            check(c, sb[3] - sb[1] >= 9 * 3, "Split Timers drew fewer than three rows - the injected run did not show");
            boolean meets = sb[0] < mb[2] && mb[0] < sb[2] && sb[1] < mb[3] && mb[1] < sb[3];
            check(c, !meets, "Split Timers " + HudEditorCases.str(sb) + " is drawn on top of the Dungeon Map "
                    + HudEditorCases.str(mb));
        } finally {
            c.onClient(mc -> {
                Mod.staticCall("secrets.DungeonState", "setRoomSim", false);
                return null;
            });
            clearRun(c);
            HudEditorCases.restoreWindow(c, window, gui);
            restore(c, saved);
        }
    }

    private static int[] drawnBox(Object e) {
        int[] p = (int[]) Mod.staticCall("hud.HudElementRegistry", "resolvePosition", e);
        float s = ((Number) Mod.staticCall("hud.HudElementRegistry", "resolveScale", e)).floatValue();
        return new int[]{p[0], p[1], p[0] + Math.round((Integer) Mod.call(e, "width") * s),
                p[1] + Math.round((Integer) Mod.call(e, "height") * s)};
    }

    // ================================================================================================================
    // 446 split format
    // ================================================================================================================

    static void splitFormat(UiCase c) throws Exception {
        Map<Path, byte[]> saved = snapshot(c, SPLITS);
        try {
            c.onClient(mc -> {
                Object st = Mod.cfg(SPLITS);
                Mod.call(st, "setEnabled", true);
                Mod.call(st, "setLaglessTimes", true);
                trySet(st, "setTotalWithLag", true);
                trySet(st, "setTotalWithoutLag", true);
                trySet(st, "setLagLostLine", true);
                return null;
            });
            boolean totalToggles = c.onClient(mc -> hasMethod(Mod.cfg(SPLITS).getClass(), "setTotalWithLag"));
            int n = injectRun(c);
            List<String> lines = drawn(c);
            c.note("injected an F7 run of " + n + " splits, 10 s apart, 0.7 s of lag in each; the HUD draws:");
            for (String l : lines) {
                c.note("  | " + l);
            }
            check(c, lines.size() >= 4, "Split Timers drew " + lines.size() + " line(s) - nothing to judge");
            String t = "(?:\\d+h )?(?:\\d+m )?\\d+\\.\\d{2}s|0s";
            Pattern total = Pattern.compile("Total: (" + t + ") \\((" + t + ")\\)");
            String totalLine = lines.stream().filter(l -> l.startsWith("Total")).findFirst().orElse(null);
            check(c, totalLine != null && total.matcher(totalLine).matches(), "the Total line is not \"Total: X (Y)\": "
                    + totalLine);
            check(c, lines.stream().noneMatch(l -> l.startsWith("No Lag")), "a separate \"No Lag\" line is still drawn");
            check(c, lines.stream().anyMatch(l -> l.matches("Lag: (" + t + ")")), "no \"Lag: X\" line with Lag Lost on");
            Pattern row = Pattern.compile(".+: (" + t + ") \\((" + t + ")\\)");
            int rows = 0;
            for (String l : lines) {
                if (l.startsWith("Total") || l.startsWith("Lag") || l.isBlank() || !l.contains(":")) {
                    continue;
                }
                rows++;
                check(c, row.matcher(l).matches(), "a split row is not \"Name: X (Y)\": " + l);
            }
            check(c, rows >= 3, "only " + rows + " split row(s) drawn");

            if (!totalToggles) {
                // mod dungeon-fixes (2026-10-08): Total and Lag are part of the fixed layout, no toggles left to try.
                c.note("jar has no Total With/Without Lag toggles (fixed split layout); the toggle checks are skipped");
            } else {
            c.onClient(mc -> {
                Mod.call(Mod.cfg(SPLITS), "setTotalWithLag", false);
                return null;
            });
            String onlyLagless = drawn(c).stream().filter(l -> l.startsWith("Total")).findFirst().orElse(null);
            c.onClient(mc -> {
                Mod.call(Mod.cfg(SPLITS), "setTotalWithLag", true);
                Mod.call(Mod.cfg(SPLITS), "setTotalWithoutLag", false);
                return null;
            });
            String onlyReal = drawn(c).stream().filter(l -> l.startsWith("Total")).findFirst().orElse(null);
            c.note("Total Without Lag only: " + onlyLagless + "; Total With Lag only: " + onlyReal);
            check(c, onlyLagless != null && onlyLagless.matches("Total: \\((" + t + ")\\)"),
                    "with only Total Without Lag on the line is not \"Total: (Y)\": " + onlyLagless);
            check(c, onlyReal != null && onlyReal.matches("Total: (" + t + ")"),
                    "with only Total With Lag on the line is not \"Total: X\": " + onlyReal);
            }

            String suffix;
            try {
                suffix = c.onClient(mc -> {
                    try {
                        Method m = R.cls("splittimers.SplitTimersFeature").getDeclaredMethod("laglessSuffix",
                                boolean.class, long.class, boolean.class);
                        m.setAccessible(true);
                        return (String) m.invoke(null, true, 19590L, true);
                    } catch (ReflectiveOperationException e) {
                        return "missing: " + e;
                    }
                });
            } catch (RuntimeException | AssertionError e) {
                suffix = "missing: " + e;
            }
            c.note("chat time suffix for 19.59 s lagless: '" + suffix + "' (\"Blood Rush took 22.39s" + suffix + "!\")");
            check(c, " (19.59s)".equals(suffix), "the chat lines do not put the lagless time in parentheses: " + suffix);
        } finally {
            clearRun(c);
            restore(c, saved);
        }
    }

    /** The text runs Split Timers' render() draws, top to bottom. */
    private static List<String> drawn(UiCase c) {
        c.ticks(2);
        return c.onClient(mc -> {
            Object e = Mod.staticCall("hud.HudElementRegistry", "byId", "split_timers");
            GuiRenderState state = new GuiRenderState();
            try {
                GuiGraphicsExtractor g = new GuiGraphicsExtractor(mc, state, -1, -1);
                R.cls("hud.HudElement").getMethod("render", GuiGraphicsExtractor.class, int.class, int.class)
                        .invoke(e, g, 0, 0);
            } catch (ReflectiveOperationException ex) {
                throw new AssertionError(UiCase.describe(ex), ex);
            }
            List<String> out = new ArrayList<>();
            state.forEachText(t -> out.add(dev.testkit.gametest.TextRuns.string(t)));
            return out;
        });
    }

    /** A finished F7 run in Split Timers' run state: every split 10 s after the last, 0.7 s of lag in each, and the lag
     *  clock marked as having seen real server ticks. Returns the split count. */
    private static int injectRun(UiCase c) {
        return c.onClient(mc -> {
            try {
                Class<?> f = R.cls("splittimers.SplitTimersFeature");
                Class<?> rs = R.cls("splittimers.SplitTimersFeature$RunState");
                Constructor<?> ctor = rs.getDeclaredConstructor();
                ctor.setAccessible(true);
                Object run = ctor.newInstance();
                List<?> splits;
                try {
                    // mod dungeon-fixes (2026-10-08): one layout, no Clear Splits flag.
                    Method floor = f.getDeclaredMethod("splitsForFloor", String.class);
                    floor.setAccessible(true);
                    splits = (List<?>) floor.invoke(null, "F7");
                } catch (NoSuchMethodException old) {
                    Method floor = f.getDeclaredMethod("splitsForFloor", String.class, boolean.class);
                    floor.setAccessible(true);
                    splits = (List<?>) floor.invoke(null, "F7", false);
                }
                int n = splits.size();
                long base = System.currentTimeMillis() - (n + 1) * 10_000L;
                long[] time = new long[n];
                long[] lag = new long[n];
                for (int i = 0; i < n; i++) {
                    time[i] = base + i * 10_000L;
                    lag[i] = i * 700L;
                }
                R.set(run, "splits", splits);
                R.set(run, "timeMs", time);
                R.set(run, "lagMs", lag);
                R.setStatic(f, "run", run);
                R.setStatic(R.cls("splittimers.SplitLagClock"), "pingClockSeen", true);
                return n;
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(UiCase.describe(e), e);
            }
        });
    }

    private static void clearRun(UiCase c) {
        try {
            c.onClient(mc -> {
                try {
                    Constructor<?> ctor = R.cls("splittimers.SplitTimersFeature$RunState").getDeclaredConstructor();
                    ctor.setAccessible(true);
                    R.setStatic(R.cls("splittimers.SplitTimersFeature"), "run", ctor.newInstance());
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
                return null;
            });
        } catch (Throwable t) {
            c.note("run reset: " + UiCase.describe(t));
        }
    }

    // ================================================================================================================
    // 447 scoreboard Solo
    // ================================================================================================================

    static void scoreboardSolo(UiCase c) throws Exception {
        Map<Path, byte[]> saved = snapshot(c, BOARD);
        Class<?> data = R.cls("scoreboard.ScoreboardData");
        Class<?> feature = R.cls("scoreboard.CustomScoreboardFeature");
        Object oldLines = c.onClient(mc -> R.getStatic(data, "sidebarLines"));
        Object oldIsland = c.onClient(mc -> R.getStatic(data, "island"));
        try {
            // ---- the patterns themselves --------------------------------------------------------------------------
            Pattern solo = c.onClient(mc -> Mod.pattern("scoreboard.ScoreboardPattern", "SOLO"));
            Pattern keys = c.onClient(mc -> Mod.pattern("scoreboard.ScoreboardPattern", "KEYS"));
            String[] soloLines = {"§3§lSolo", "§3§l§3§lSolo", "§l§3Solo", "§bSolo", "Solo"};
            String[] keyLines = {"Keys: §c■ §cx §8■ §a0x", "Keys: §c■ §c✗ §8■ §a1x", "Keys: §c■ §a✓ §8■ §a10x",
                    "§fKeys: §c■ §cx §8■ §a0x"};
            for (String s : soloLines) {
                boolean ok = solo.matcher(s).matches();
                c.note("SOLO " + show(s) + " -> " + ok);
                check(c, ok, "the Solo line " + show(s) + " is not recognised");
            }
            for (String s : keyLines) {
                boolean ok = keys.matcher(s).matches();
                c.note("KEYS " + show(s) + " -> " + ok);
                check(c, ok, "the Keys line " + show(s) + " is not recognised");
            }
            check(c, !solo.matcher("§7[VIP] Bob§f: Solo run time").matches(), "SOLO matches a chat-like line");

            // ---- the unknown-line pass over a real dungeon board --------------------------------------------------
            List<String> board = List.of("§7Time Elapsed: §a1m 2s", "§fKeys: §c■ §cx §8■ §a0x", "§3§l§3§lSolo",
                    "§l§3Solo", "§7Made Up Line 12");
            @SuppressWarnings("unchecked")
            List<String> unknown = c.onClient(mc -> {
                R.setStatic(data, "sidebarLines", board);
                R.setStatic(data, "island", "Catacombs");
                try {
                    Method m = feature.getDeclaredMethod("computeUnknownLines");
                    m.setAccessible(true);
                    return (List<String>) m.invoke(null);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(UiCase.describe(e), e);
                }
            });
            c.note("dungeon board " + board.stream().map(HudFixCases::show).toList() + " -> unknown " + unknown.stream()
                    .map(HudFixCases::show).toList());
            check(c, unknown.size() == 1 && unknown.get(0).contains("Made Up Line"),
                    "the unknown-line pass did not leave exactly the made-up line: " + unknown);

            // ---- the notice: off by default (an old file's saved true included), logged once per shape ------------
            Path file = c.onClient(mc -> (Path) R.getStatic(R.cls(BOARD), "CONFIG_PATH"));
            Files.deleteIfExists(file);
            boolean fresh = c.onClient(mc -> {
                Mod.staticCall(BOARD, "load");
                return (Boolean) Mod.call(Mod.cfg(BOARD), "isUnknownLinesWarning");
            });
            Files.writeString(file, "{\"unknownLinesWarning\":true}");
            boolean oldTrue = c.onClient(mc -> {
                Mod.staticCall(BOARD, "load");
                return (Boolean) Mod.call(Mod.cfg(BOARD), "isUnknownLinesWarning");
            });
            boolean roundTrip = c.onClient(mc -> {
                Mod.call(Mod.cfg(BOARD), "setUnknownLinesWarning", true);
                Mod.call(Mod.cfg(BOARD), "save");
                Mod.staticCall(BOARD, "load");
                boolean v = (Boolean) Mod.call(Mod.cfg(BOARD), "isUnknownLinesWarning");
                Mod.call(Mod.cfg(BOARD), "setUnknownLinesWarning", false);
                Mod.call(Mod.cfg(BOARD), "save");
                return v;
            });
            c.note("Unknown Line Warning: fresh " + fresh + ", old file with unknownLinesWarning:true " + oldTrue
                    + ", set on + reload " + roundTrip);
            check(c, !fresh, "Unknown Line Warning is on in a fresh config - it prints to chat in normal play");
            check(c, !oldTrue, "an old file's saved unknownLinesWarning:true still turns the chat note on");
            check(c, roundTrip, "Unknown Line Warning switched on did not survive a save and reload");

            long mark = LogTap.mark();
            String warned = c.onClient(mc -> {
                try {
                    Method w = feature.getDeclaredMethod("warnUnknownLines", R.cls(BOARD));
                    w.setAccessible(true);
                    Object cfg = Mod.cfg(BOARD);
                    for (String line : new String[]{"§7Made Up Line 12", "§7Made Up Line 13", "§7Made Up Line 14"}) {
                        R.setStatic(feature, "unknown", List.of(line));
                        w.invoke(null, cfg);
                    }
                    return "ok";
                } catch (ReflectiveOperationException e) {
                    return "failed: " + UiCase.describe(e);
                }
            });
            c.ticks(3);
            List<String> log = LogTap.since(mark);
            long logged = log.stream().filter(l -> l.contains("Unknown scoreboard line") && !l.contains("[CHAT]")).count();
            long chat = log.stream().filter(l -> l.contains("[CHAT]") && l.contains("Unknown scoreboard line")).count();
            c.note("three unknown lines differing only in a number (" + warned + "): " + logged + " log line(s), " + chat
                    + " chat line(s)");
            check(c, logged == 1, "the unknown line was logged " + logged + " time(s), want once per shape");
            check(c, chat == 0, "an unknown scoreboard line reached chat with the warning off");
        } finally {
            c.onClient(mc -> {
                R.setStatic(data, "sidebarLines", oldLines);
                R.setStatic(data, "island", oldIsland);
                R.setStatic(feature, "unknown", List.of());
                return null;
            });
            restore(c, saved);
        }
    }

    // ================================================================================================================
    // helpers
    // ================================================================================================================

    /** Stat Bars on, Custom layout, only {@code on} switched on (own scale 1, Auto Scale off), unique colours. */
    private static void custom(UiCase c, String[] on, int thickness, boolean showValue) {
        c.onClient(mc -> {
            Object ps = Mod.cfg(PS);
            Mod.call(ps, "setEnabled", true);
            Mod.call(ps, "setPredefinedLayout", false);
            for (Object rr : R.cls(READOUT).getEnumConstants()) {
                Mod.call(ps, "setReadoutOn", rr, false);
            }
            for (String o : on) {
                Mod.call(ps, "setReadoutOn", r(o), true);
            }
            Mod.call(ps, "setReadoutColor", r("HEALTH_BAR"), HEALTH);
            Mod.call(ps, "setReadoutColor", r("MANA_BAR"), MANA);
            Mod.call(ps, "setReadoutColor", r("DEFENCE_BAR"), DEFENCE);
            Mod.call(ps, "setBarBackground", BACKGROUND);
            Mod.call(ps, "setBarShowValue", showValue);
            Mod.call(ps, "setTextShadow", true);
            Mod.call(ps, "setBarWidth", 100);
            Mod.call(ps, "setBarHeight", thickness);
            if (hasMethod(R.cls(PS), "setPredefinedScale")) {
                Mod.call(ps, "setPredefinedScale", 1.0f);
            }
            Mod.call(ps, "save");
            Object hud = Mod.cfg(HUD);
            for (Object rr : R.cls(READOUT).getEnumConstants()) {
                Mod.call(hud, "setScale", (String) R.get(rr, "hudId"), 1.0f);
            }
            Mod.call(hud, "setGlobalScale", 1.0f);
            Mod.call(hud, "setAutoScale", false);
            Mod.call(hud, "save");
            McCompat.setScreen(mc, null);
            return null;
        });
        c.ticks(3);
    }

    private static void place(UiCase c, String id, int x, int y) {
        c.onClient(mc -> {
            Mod.call(Mod.cfg(HUD), "setPosition", id, x, y);
            Mod.call(Mod.cfg(HUD), "save");
            return null;
        });
        c.ticks(3);
    }

    private static void set(UiCase c, long cur, long max) {
        c.onClient(mc -> {
            R.setStatic(R.cls(FEATURE), "healthCur", cur);
            R.setStatic(R.cls(FEATURE), "healthMax", max);
            return null;
        });
        c.ticks(2);
    }

    private static long[] health(UiCase c) {
        return c.onClient(mc -> new long[]{(Long) R.getStatic(R.cls(FEATURE), "healthCur"),
                (Long) R.getStatic(R.cls(FEATURE), "healthMax")});
    }

    /** Full readings (1000/1000 for every bar), or back to "nothing read". */
    private static void values(UiCase c, boolean on) {
        c.onClient(mc -> {
            Class<?> f = R.cls(FEATURE);
            long v = on ? 1000L : -1L;
            R.setStatic(f, "healthCur", v);
            R.setStatic(f, "healthMax", v);
            R.setStatic(f, "manaCur", v);
            R.setStatic(f, "manaMax", v);
            R.setStatic(f, "defenceValue", on ? 250L : -1L);
            return null;
        });
        c.ticks(2);
    }

    private static void actionBar(UiCase c, String text) {
        serverPlayer(c, sp -> sp.sendSystemMessage(Component.literal(text), true));
        c.ticks(3);
    }

    private static void serverPlayer(UiCase c, java.util.function.Consumer<net.minecraft.server.level.ServerPlayer> f) {
        c.onClient(mc -> {
            var server = mc.getSingleplayerServer();
            if (server == null || mc.player == null) {
                return null;
            }
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(uuid);
                if (sp != null) {
                    f.accept(sp);
                }
            });
            return null;
        });
        c.ticks(5);
    }

    /** Survival (hearts, hunger and the real inventory screen) but invulnerable; or back to the suite's creative. */
    private static void survival(UiCase c, boolean on) {
        if (!on) {
            SafeWorld.apply(c.ctx());
            return;
        }
        serverPlayer(c, sp -> {
            sp.setGameMode(GameType.SURVIVAL);
            sp.getAbilities().invulnerable = true;
            sp.getAbilities().mayfly = true;
            sp.getAbilities().flying = true;
            sp.onUpdateAbilities();
        });
        for (int i = 0; i < 100 && !c.onClient(mc -> mc.gameMode != null && mc.gameMode.canHurtPlayer()); i++) {
            c.ticks(1);
        }
    }

    private static BufferedImage shot(UiCase c, String suffix) throws Exception {
        // Earlier cases' chat lines draw over the HUD (over the vanilla hotbar too): on 26.2 the smoke cases' lines
        // covered 441's bar. Clear them so the picture is the HUD under test.
        c.onClient(mc -> {
            McCompat.clearChatAndToasts(mc);
            return null;
        });
        c.ticks(2);
        String name = c.name() + "-" + suffix;
        Path taken = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(name));
        Path kept = dev.testkit.harness.Report.screenshot(name, taken);
        c.note("picture " + name + " -> " + (kept != null ? kept : taken));
        int gs = c.onClient(mc -> mc.getWindow().getGuiScale());
        if (gs == 2) {
            copy(c, taken, name);
        }
        return javax.imageio.ImageIO.read(taken.toFile());
    }

    private static void copy(UiCase c, Path taken, String name) {
        String dir = System.getenv("TESTKIT_HUDFIX_SHOTS");
        if (dir == null || dir.isBlank()) {
            return;
        }
        try {
            String jar = Mod.isCheat() ? "cheat" : "legit";
            String mc = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("minecraft").orElseThrow()
                    .getMetadata().getVersion().getFriendlyString();
            Path out = Path.of(dir).resolve(name + "-" + mc + "-" + jar + ".png");
            Files.createDirectories(out.getParent());
            Files.copy(taken, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception | Error e) {
            c.note("could not copy " + name + ": " + e);
        }
    }

    /** Pixels in [x0,x1)x[y0,y1) whose summed RGB differs by more than 30 between the two pictures. */
    private static int changed(BufferedImage a, BufferedImage b, int x0, int y0, int x1, int y1) {
        int n = 0;
        for (int y = Math.max(0, y0); y < Math.min(a.getHeight(), y1); y++) {
            for (int x = Math.max(0, x0); x < Math.min(a.getWidth(), x1); x++) {
                if (delta(a.getRGB(x, y), b.getRGB(x, y)) > 30) {
                    n++;
                }
            }
        }
        return n;
    }

    private static int delta(int a, int b) {
        return Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF)) + Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF))
                + Math.abs((a & 0xFF) - (b & 0xFF));
    }

    /** {x0, y0, x1, y1} (inclusive) of the pixels within {@code tol} (summed RGB) of {@code rgb}, or null. */
    private static int[] near(BufferedImage img, int rgb, int tol) {
        int bx0 = Integer.MAX_VALUE, by0 = Integer.MAX_VALUE, bx1 = -1, by1 = -1;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if (delta(img.getRGB(x, y), rgb) <= tol) {
                    bx0 = Math.min(bx0, x);
                    by0 = Math.min(by0, y);
                    bx1 = Math.max(bx1, x);
                    by1 = Math.max(by1, y);
                }
            }
        }
        return bx1 < 0 ? null : new int[]{bx0, by0, bx1, by1};
    }

    /** {x0, y0, x1, y1} (inclusive) of {@code rgb} in the region, or null. */
    private static int[] bbox(BufferedImage img, int rgb, int x0, int y0, int x1, int y1) {
        int bx0 = Integer.MAX_VALUE, by0 = Integer.MAX_VALUE, bx1 = -1, by1 = -1;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                if ((img.getRGB(x, y) & 0xFFFFFF) == rgb) {
                    bx0 = Math.min(bx0, x);
                    by0 = Math.min(by0, y);
                    bx1 = Math.max(bx1, x);
                    by1 = Math.max(by1, y);
                }
            }
        }
        return bx1 < 0 ? null : new int[]{bx0, by0, bx1, by1};
    }

    private static Map<Path, byte[]> snapshot(UiCase c, String... classes) {
        Map<Path, byte[]> out = new LinkedHashMap<>();
        for (String cls : classes) {
            Path p = c.onClient(mc -> (Path) R.getStatic(R.cls(cls), "CONFIG_PATH"));
            try {
                out.put(p, Files.exists(p) ? Files.readAllBytes(p) : null);
            } catch (java.io.IOException e) {
                c.note("snapshot " + p + ": " + e);
            }
        }
        return out;
    }

    private static void restore(UiCase c, Map<Path, byte[]> saved) {
        try {
            c.onClient(mc -> {
                try {
                    for (Map.Entry<Path, byte[]> e : saved.entrySet()) {
                        if (e.getValue() == null) {
                            Files.deleteIfExists(e.getKey());
                        } else {
                            Files.write(e.getKey(), e.getValue());
                        }
                    }
                } catch (java.io.IOException e) {
                    throw new AssertionError(e);
                }
                for (String cls : new String[]{HUD, PS, MAP, MAPPING, SPLITS, BOARD}) {
                    try {
                        Mod.staticCall(cls, "load");
                    } catch (RuntimeException | AssertionError ignored) {
                        // a config this jar lacks
                    }
                }
                McCompat.setScreen(mc, null);
                return null;
            });
            c.ticks(2);
        } catch (Throwable t) {
            c.note("config restore: " + UiCase.describe(t));
        }
    }

    /** A setter an older or newer jar may not have. */
    private static void trySet(Object cfg, String setter, Object value) {
        if (hasMethod(cfg.getClass(), setter)) {
            Mod.call(cfg, setter, value);
        }
    }

    private static Object r(String name) {
        return Mod.enumValue(READOUT, name);
    }

    private static boolean hasMethod(Class<?> c, String name) {
        for (var m : c.getMethods()) {
            if (m.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static void check(UiCase c, boolean ok, String what) {
        if (!ok) {
            c.problem(what);
        }
    }

    private static double h(double[] b) {
        return b == null ? -1 : b[3] - b[1];
    }

    private static String gbox(double[] b) {
        if (b == null) {
            return "none";
        }
        return String.format(Locale.ROOT, "%.1f,%.1f..%.1f,%.1f%s", b[0], b[1], b[2], b[3], b[4] >= 1 ? "" : " (not solid)");
    }

    /** A § line made readable in a note. */
    private static String show(String s) {
        return "\"" + s.replace('§', '&') + "\"";
    }
}
