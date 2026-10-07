package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.state.gui.GuiRenderState;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 394-ui-hud-boxes: every HUD element's editor box ({@code width() x height()} at its drawn scale) against what the
 * element actually draws (killer560, 2026-10-07: "those split timers the box is way too large for how big they
 * actually are for the editor portion").
 *
 * <p>With the real {@code HudEditorScreen} as the current screen (so every element draws its editor preview), each
 * registered element's {@code render(g, 0, 0)} is extracted under a translate to (40, 40) and a scale of 2 into a
 * throwaway {@link GuiRenderState}. The drawn bounds are the union of every recorded fill (fully transparent ones
 * skipped), item, and text run (its advance box - see {@link dev.testkit.gametest.TextRuns#box}), read from the render state rather than a
 * screenshot so that 50-odd elements cost one extract each and no world pixels can leak in. The box is (40, 40) plus
 * {@code round(width() * 2) x round(height() * 2)}. Every side's gap is printed in element units.
 *
 * <p>Fails, for each element the editor lists (setting on), when the box is more than {@link #SLACK} units larger
 * than the drawn content on any side, or the content spills more than {@link #SPILL} unit past it. Split Timers,
 * the Secrets HUD and Mask Invincibility Timers (his three) are switched on for the run and must draw something, so
 * the check on them is never vacuous. An element that draws nothing is listed, not judged.
 */
final class HudBoxCases {

    /** Element units of empty box allowed on any side. */
    static final int SLACK = 3;
    /** Element units the drawing may run past the box (a text shadow is one). */
    static final int SPILL = 1;
    private static final float SCALE = 2f;
    /**
     * Elements that draw CENTRED in their box, so the box's centre is where they are anchored and a box that hugged
     * the text would move the text whenever it changed length (and move every saved one when it shrank): Room Alerts
     * centre "<room> Cleared!" across the box's width, the blood camp popup centres "KILL"/"Kill in 1.2s" on the
     * box's middle. Judged on the axes that are not centred only.
     */
    static final java.util.Map<String, String> CENTRED = java.util.Map.of(
            "room_alerts", "x", "blood_camp_kill", "xy");
    private static final int X0 = 40;
    private static final int Y0 = 40;

    private HudBoxCases() {
    }

    record Measured(String id, boolean listed, int w, int h, int[] drawn) {
    }

    /**
     * Settings switched on for the run (and put back after), so as many elements as possible draw their editor
     * preview and are judged: {config class, bean property, value}. Split Timers gets his three total lines (the
     * screenshot's Total / No Lag / Lag); the mask timers' location gates are opened because the old jar obeyed
     * them even in the editor. Automation (AP3, Auto Clear, Autopilot) is NOT switched on - their previews draw
     * with the setting off anyway.
     */
    private static final Object[][] SWITCH_ON = {
            {"splittimers.SplitTimersConfig", "Enabled", true},
            {"splittimers.SplitTimersConfig", "TotalWithLag", true},
            {"splittimers.SplitTimersConfig", "TotalWithoutLag", true},
            {"splittimers.SplitTimersConfig", "LagLostLine", true},
            {"dungeoninfo.DungeonInfoConfig", "SecretsHudEnabled", true},
            {"maskinvincibility.MaskInvincibilityConfig", "Enabled", true},
            {"maskinvincibility.MaskInvincibilityConfig", "OnlyInDungeons", false},
            {"maskinvincibility.MaskInvincibilityConfig", "BossOnly", false},
            {"abilitycooldown.AbilityCooldownConfig", "Enabled", true},
            {"abilitytimers.AbilityTimersConfig", "Enabled", true},
            {"inventoryhud.InventoryHudConfig", "Enabled", true},
            {"lagdisplay.LagDisplayConfig", "Enabled", true},
            {"melody.MelodyHudConfig", "HudEnabled", true},
            {"playerstats.PlayerStatsConfig", "Enabled", true},
            {"position.PositionConfig", "Enabled", true},
            {"quiver.QuiverDisplayConfig", "Enabled", true},
            {"realtime.RealTimeConfig", "Enabled", true},
            {"scoreboard.CustomScoreboardConfig", "Enabled", true},
            {"scorecalc.ScoreCalculatorConfig", "Enabled", true},
            {"simonsays.SimonSaysConfig", "PartyProgressTrackerEnabled", true},
            {"ticktimers.TickTimersConfig", "Enabled", true},
            {"ragaxe.RagAxeConfig", "Enabled", true},
            {"boss.LividSolverConfig", "Enabled", true},
            {"boss.LividSolverConfig", "ShowTimer", true},
            {"witherdragons.WitherDragonsConfig", "Enabled", true},
            {"witherdragons.WitherDragonsConfig", "DragonTimer", true},
            {"witherdragons.WitherDragonsConfig", "RelicsEnabled", true},
            {"witherdragons.WitherDragonsConfig", "RelicSpawnTimer", true},
    };

    static void run(UiCase c) throws Exception {
        List<AutoCloseable> restore = new ArrayList<>();
        List<Measured> all;
        try {
            c.onClient(mc -> {
                for (Object[] on : SWITCH_ON) {
                    try {
                        restore.add(0, Mod.with((String) on[0], (String) on[1], on[2]));
                    } catch (RuntimeException | AssertionError e) {
                        c.note("could not set " + on[0] + "." + on[1] + ": " + UiCase.describe(e));
                    }
                }
                try {
                    Screen editor = (Screen) R.cls("hud.HudEditorScreen").getConstructor(Screen.class)
                            .newInstance((Object) null);
                    McCompat.setScreen(mc, editor);
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }
                return null;
            });
            c.ticks(3);
            all = c.onClient(HudBoxCases::measureAll);
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                for (AutoCloseable r : restore) {
                    try {
                        r.close();
                    } catch (Exception e) {
                        c.note("restore failed: " + UiCase.describe(e));
                    }
                }
                return null;
            });
        }

        int judged = 0;
        for (Measured m : all) {
            if (m.drawn() == null) {
                c.note(String.format(Locale.ROOT, "%-28s box %4dx%-4d drew nothing%s", m.id(), m.w(), m.h(),
                        m.listed() ? "" : " (setting off)"));
                continue;
            }
            int[] d = m.drawn();
            float left = (d[0] - X0) / SCALE;
            float top = (d[1] - Y0) / SCALE;
            float right = (Math.round(m.w() * SCALE) + X0 - d[2]) / SCALE;
            float bottom = (Math.round(m.h() * SCALE) + Y0 - d[3]) / SCALE;
            float dw = (d[2] - d[0]) / SCALE;
            float dh = (d[3] - d[1]) / SCALE;
            String centred = CENTRED.getOrDefault(m.id(), "");
            boolean badX = !centred.contains("x") && (left > SLACK || right > SLACK || left < -SPILL || right < -SPILL);
            boolean badY = !centred.contains("y") && (top > SLACK || bottom > SLACK || top < -SPILL || bottom < -SPILL);
            boolean bad = badX || badY;
            String line = String.format(Locale.ROOT,
                    "%-28s box %4dx%-4d drawn %6.1fx%-6.1f gaps L %5.1f T %5.1f R %6.1f B %6.1f%s%s", m.id(), m.w(),
                    m.h(), dw, dh, left, top, right, bottom, (m.listed() ? "" : " (setting off)")
                            + (centred.isEmpty() ? "" : " (centred on " + centred + ", not judged there)"),
                    bad ? (m.listed() ? "  <-- BAD" : "  <-- off by more than the slack") : "");
            c.note(line);
            if (m.listed()) {
                judged++;
                if (bad) {
                    c.problem(m.id() + ": box " + m.w() + "x" + m.h() + " vs drawn " + dw + "x" + dh + " at offset "
                            + left + "," + top + " (gaps R " + right + ", B " + bottom + "; slack " + SLACK + ")");
                }
            }
        }
        for (String id : new String[]{"split_timers", "dungeon_info", "mask_invincibility"}) {
            Measured m = all.stream().filter(x -> x.id().equals(id)).findFirst().orElse(null);
            if (m == null || m.drawn() == null) {
                c.problem(id + " drew nothing in the HUD editor with its setting on - its box check would be vacuous");
            }
        }
        c.note("judged " + judged + " listed element(s) that drew; slack " + SLACK + " units a side, spill " + SPILL);
        System.out.println("[394-ui-hud-boxes] " + (c.problemCount() == 0 ? "PASS" : "FAIL") + " (" + judged
                + " judged, " + c.problemCount() + " problem(s))");
    }

    @SuppressWarnings("unchecked")
    private static List<Measured> measureAll(net.minecraft.client.Minecraft mc) {
        List<Measured> out = new ArrayList<>();
        try {
            Class<?> reg = R.cls("hud.HudElementRegistry");
            Class<?> elCls = R.cls("hud.HudElement");
            Method enabled = reg.getMethod("isEnabledInSettings", elCls);
            Method render = elCls.getMethod("render", GuiGraphicsExtractor.class, int.class, int.class);
            List<Object> elements = new ArrayList<>((List<Object>) Mod.staticCall("hud.HudElementRegistry", "all"));
            for (Object e : elements) {
                String id = (String) Mod.call(e, "id");
                boolean listed = (Boolean) enabled.invoke(null, e);
                int w;
                int h;
                try {
                    w = (Integer) Mod.call(e, "width");
                    h = (Integer) Mod.call(e, "height");
                } catch (RuntimeException | AssertionError ex) {
                    out.add(new Measured(id + " (width/height threw)", listed, 0, 0, null));
                    continue;
                }
                GuiRenderState state = new GuiRenderState();
                GuiGraphicsExtractor g = new GuiGraphicsExtractor(mc, state, -1, -1);
                g.pose().pushMatrix();
                try {
                    g.pose().translate(X0, Y0);
                    g.pose().scale(SCALE, SCALE);
                    render.invoke(e, g, 0, 0);
                } catch (ReflectiveOperationException | RuntimeException ex) {
                    out.add(new Measured(id + " (render threw)", listed, w, h, null));
                    continue;
                } finally {
                    g.pose().popMatrix();
                }
                int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
                state.forEachElement(el -> {
                    if (transparent(el)) {
                        return;
                    }
                    grow(b, el.bounds());
                }, GuiRenderState.TraverseRange.ALL);
                state.forEachText(t -> grow(b, dev.testkit.gametest.TextRuns.box(t)));
                state.forEachItem(i -> grow(b, i.bounds()));
                out.add(new Measured(id, listed, w, h, b[0] == Integer.MAX_VALUE ? null : b));
            }
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
        return out;
    }

    private static void grow(int[] b, ScreenRectangle r) {
        if (r == null || r.width() <= 0 || r.height() <= 0) {
            return;
        }
        b[0] = Math.min(b[0], r.left());
        b[1] = Math.min(b[1], r.top());
        b[2] = Math.max(b[2], r.right());
        b[3] = Math.max(b[3], r.bottom());
    }

    /** A fill whose colours are both fully transparent rasterises nothing. Read by reflection: the record's
     *  accessors are the same on 26.1.2 and 26.2, but nothing else here needs the class. */
    private static boolean transparent(Object el) {
        try {
            Method c1 = el.getClass().getMethod("col1");
            Method c2 = el.getClass().getMethod("col2");
            return ((Integer) c1.invoke(el) >>> 24) == 0 && ((Integer) c2.invoke(el) >>> 24) == 0;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }
}
