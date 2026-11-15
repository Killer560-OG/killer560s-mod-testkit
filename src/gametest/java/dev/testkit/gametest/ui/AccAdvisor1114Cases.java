package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.compat.McKeys;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import java.util.List;

/**
 * 1114-ui-accadv-drag / -mode / -copy (accadv2-1114): the Accessory Advisor panel is dragged by its header with real mouse
 * events (it saves where it was dropped, keeps its place across a reopen and a config reload, is pulled back on screen,
 * and Reset Position puts it beside the bag), its header button steps the mode both ways and wraps, and a click on a name
 * copies that plain name. In every case the press must not reach the slots under the panel: the bag is filled so a leaked
 * click would pick an accessory up.
 */
final class AccAdvisor1114Cases {

    private static final String CFG = "accadvisor.AccessoryAdvisorConfig";
    private static final String ADV = "accadvisor.AccessoryAdvisor";
    private static final String BAG = "Accessory Bag (1/2)";

    private AccAdvisor1114Cases() {
    }

    private interface Body {
        void run() throws Exception;
    }

    private static void with(UiCase c, Body body) throws Exception {
        boolean was = (Boolean) Mod.call(Mod.cfg(CFG), "isEnabled");
        Object modeWas = Mod.call(Mod.cfg(CFG), "getMode");
        try {
            AccAdvisor1113Cases.fixture(c);
            Mod.set(CFG, "setEnabled", true);
            Mod.set(CFG, "setMode", Mod.enumValue(CFG + "$Mode", "BEST_OVERALL"));
            Mod.staticCall(ADV, "resetPosition");
            body.run();
        } finally {
            AccAdvisor1113Cases.close(c);
            Mod.staticCall(ADV, "resetPosition");
            Mod.set(CFG, "setEnabled", was);
            Mod.set(CFG, "setMode", modeWas);
            c.onClient(mc -> Mod.staticCall(ADV, "clearForTest"));
            Mod.staticCall("pricetooltip.PriceData", "clearForTest");
        }
    }

    private static int[] panel(UiCase c) {
        return c.onClient(mc -> (int[]) Mod.staticCall(ADV, "panelRectForTest"));
    }

    private static int[] screenSize(UiCase c) {
        return c.onClient(mc -> new int[]{McCompat.screen(mc).width, McCompat.screen(mc).height});
    }

    private static void reopen(UiCase c, int fill) {
        AccAdvisor1113Cases.close(c);
        AccAdvisor1113Cases.open(c, BAG, fill);
        c.ticks(6);
    }

    private static void moveTo(UiCase c, double gx, double gy) {
        Screen s = c.onClient(mc -> McCompat.screen(mc));
        double[] a = c.onClient(mc -> {
            float f = ((Number) Mod.staticCall("hud.AutoScale", "appliedFactor", s)).floatValue();
            var w = mc.getWindow();
            return new double[]{gx * f * w.getScreenWidth() / (double) w.getGuiScaledWidth(),
                    gy * f * w.getScreenHeight() / (double) w.getGuiScaledHeight()};
        });
        c.ctx().getInput().setCursorPos(a[0], a[1]);
        c.ticks(2);
    }

    private static void click(UiCase c, int[] at, int button) {
        moveTo(c, at[0], at[1]);
        c.ctx().getInput().pressMouse(McKeys.mouse(button));
        c.ticks(3);
    }

    private static void drag(UiCase c, int[] from, int toX, int toY) {
        moveTo(c, from[0], from[1]);
        c.ctx().getInput().holdMouse(McKeys.mouse(0));
        c.ticks(2);
        for (int i = 1; i <= 6; i++) {
            moveTo(c, from[0] + (toX - from[0]) * i / 6.0, from[1] + (toY - from[1]) * i / 6.0);
        }
        c.ctx().getInput().releaseMouse(McKeys.mouse(0));
        c.ticks(3);
    }

    /** True while no slot of the open bag was clicked: nothing carried and every slot still holds its accessory. */
    private static boolean slotsUntouched(UiCase c) {
        return c.onClient(mc -> {
            var menu = ((AbstractContainerScreen<?>) McCompat.screen(mc)).getMenu();
            for (int i = 0; i < 27; i++) {
                if (!menu.getSlot(i).hasItem()) {
                    return false;
                }
            }
            return menu.getCarried().isEmpty();
        });
    }

    private static void same(UiCase c, int[] a, int[] b, String what) {
        c.check(a != null && b != null && a[0] == b[0] && a[1] == b[1], what + ": " + java.util.Arrays.toString(a) + " vs "
                + java.util.Arrays.toString(b));
    }

    static void drag(UiCase c) throws Exception {
        with(c, () -> {
            AccAdvisor1113Cases.open(c, BAG, 2);
            c.ticks(6);
            int[] r0 = panel(c);
            c.check(r0 != null, "the panel never drew on the Accessory Bag - the feature never acted");
            int[] sc = screenSize(c);
            c.check(r0[0] >= (sc[0] - 176) / 2 + 176 || r0[0] + r0[2] <= (sc[0] - 176) / 2,
                    "default spot is not beside the bag: " + java.util.Arrays.toString(r0));
            int[] hp = c.onClient(mc -> (int[]) Mod.staticCall(ADV, "headerPointForTest"));
            int tx = hp[0] + (sc[0] / 2 - 60 - r0[0]);
            int ty = hp[1] + (sc[1] / 2 - 30 - r0[1]);
            drag(c, hp, tx, ty);
            int[] r1 = panel(c);
            c.note("panel " + java.util.Arrays.toString(r0) + " -> " + java.util.Arrays.toString(r1));
            c.check(Math.abs(r1[0] - (r0[0] + tx - hp[0])) <= 2 && Math.abs(r1[1] - (r0[1] + ty - hp[1])) <= 2,
                    "the panel did not follow the drag: " + java.util.Arrays.toString(r1));
            c.check(slotsUntouched(c), "the drag clicked a slot under the panel");

            reopen(c, 2);
            same(c, panel(c), r1, "after closing and reopening the bag");
            c.onClient(mc -> Mod.staticCall("hud.HudConfig", "load"));
            reopen(c, 2);
            same(c, panel(c), r1, "after a config reload");

            Object hud = c.onClient(mc -> Mod.staticCall("hud.HudConfig", "getInstance"));
            Mod.call(hud, "setPosition", "accessory_advisor_panel", 5000, 5000);
            reopen(c, 2);
            int[] far = panel(c);
            c.check(far[0] >= 0 && far[1] >= 0 && far[0] + far[2] <= sc[0] && far[1] + far[3] <= sc[1],
                    "a spot past the screen edge is not pulled back on screen: " + java.util.Arrays.toString(far) + " in " + java.util.Arrays.toString(sc));
            Mod.call(hud, "setPosition", "accessory_advisor_panel", -400, -400);
            reopen(c, 2);
            int[] neg = panel(c);
            c.check(neg[0] >= 0 && neg[1] >= 0, "a negative spot stays off screen: " + java.util.Arrays.toString(neg));

            Mod.staticCall(ADV, "resetPosition");
            reopen(c, 2);
            same(c, panel(c), r0, "Reset Position");
        });
    }

    static void mode(UiCase c) throws Exception {
        with(c, () -> {
            AccAdvisor1113Cases.open(c, BAG, 2);
            c.ticks(6);
            c.check(panel(c) != null, "the panel never drew - the feature never acted");
            c.check(rows(c).equals(List.of("Gamma Ring", "Alpha Artifact", "Beta Talisman")), "start rows " + rows(c));
            step(c, 0, "UPGRADE_ONLY", List.of("Alpha Artifact"));
            step(c, 1, "BEST_OVERALL", List.of("Gamma Ring", "Alpha Artifact", "Beta Talisman"));
            step(c, 1, "MOST_MP", List.of("Beta Talisman", "Gamma Ring", "Alpha Artifact"));
            step(c, 0, "BEST_OVERALL", List.of("Gamma Ring", "Alpha Artifact", "Beta Talisman"));
            step(c, 0, "UPGRADE_ONLY", List.of("Alpha Artifact"));
            Mod.staticCall(CFG, "load");
            c.check(String.valueOf(Mod.call(Mod.cfg(CFG), "getMode")).equals("UPGRADE_ONLY"),
                    "the mode set on the panel was not saved: " + Mod.call(Mod.cfg(CFG), "getMode"));
            c.check(slotsUntouched(c), "a mode click reached a slot");
        });
    }

    @SuppressWarnings("unchecked")
    private static List<String> rows(UiCase c) {
        return c.onClient(mc -> (List<String>) Mod.staticCall(ADV, "rowNamesForTest"));
    }

    private static void step(UiCase c, int button, String wantMode, List<String> wantRows) {
        click(c, c.onClient(mc -> (int[]) Mod.staticCall(ADV, "buttonCenterForTest")), button);
        c.ticks(3);
        String got = String.valueOf(Mod.call(Mod.cfg(CFG), "getMode"));
        c.check(got.equals(wantMode), (button == 0 ? "left" : "right") + " click: mode " + got + ", wanted " + wantMode);
        c.check(rows(c).equals(wantRows), wantMode + " rows " + rows(c) + ", wanted " + wantRows);
    }

    static void copy(UiCase c) throws Exception {
        with(c, () -> {
            AccAdvisor1113Cases.open(c, BAG, 2);
            c.ticks(6);
            int[] r0 = panel(c);
            c.check(r0 != null, "the panel never drew - the feature never acted");
            int[] sc = screenSize(c);
            // Park the panel over the slot grid, so a name click that leaked would pick an accessory up.
            int[] hp = c.onClient(mc -> (int[]) Mod.staticCall(ADV, "headerPointForTest"));
            drag(c, hp, hp[0] + ((sc[0] - 176) / 2 + 10 - r0[0]), hp[1] + ((sc[1] - 168) / 2 + 16 - r0[1]));
            c.onClient(mc -> {
                mc.keyboardHandler.setClipboard("OLD");
                return null;
            });
            int[] row0 = c.onClient(mc -> (int[]) Mod.staticCall(ADV, "rowCenterForTest", 0));
            moveTo(c, row0[0], row0[1]);
            c.check(((Integer) c.onClient(mc -> Mod.staticCall(ADV, "hoverForTest"))) == 0, "hovering the first name is not a hover state");
            click(c, row0, 0);
            String clip = c.onClient(mc -> mc.keyboardHandler.getClipboard());
            c.check("Gamma Ring".equals(clip), "clicking the first name copied '" + clip + "'");
            c.check(slotsUntouched(c), "the name click reached a slot under the panel");
            click(c, c.onClient(mc -> (int[]) Mod.staticCall(ADV, "rowCenterForTest", 1)), 0);
            clip = c.onClient(mc -> mc.keyboardHandler.getClipboard());
            c.check("Alpha Artifact".equals(clip), "clicking the second name copied '" + clip + "'");
            c.check(slotsUntouched(c), "the second name click reached a slot");
            c.check(clip.indexOf('§') < 0 && !clip.contains("MP"), "the copied text carries codes or stats: " + clip);
            c.onClient(mc -> {
                mc.keyboardHandler.setClipboard("");
                return null;
            });
        });
    }
}
