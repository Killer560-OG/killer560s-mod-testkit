package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tooltips draw above the inventory search bar (mod tooltipbar-1103; killer560, 2026-11-03: "make tooltips show above the
 * search bar"). Fabric's {@code ScreenEvents.afterExtract} runs after the deferred tooltip pass, so the bar used to land on
 * top of a tooltip; the bar now draws at the tail of the container's own draw ({@code gui/BelowTooltip}).
 * <ul>
 *   <li>1103-menu-tooltip-over-bar: a very tall tooltip (hovering a slot in the middle column) covers the bar. Four shots:
 *       bar off / no hover (D), bar off / hover (C), bar on / no hover (A), bar on / hover (B). Inside the bar's rectangle
 *       the tooltip must change D (overlap, so the case is not vacuous), the bar must change D in A (it was drawn), and B
 *       must equal C there: the tooltip's pixels win over the bar's.</li>
 * </ul>
 */
final class TooltipBar1103Cases {

    private static final String ISF = "inventorysearch.InventorySearchFeature";
    private static final String ISC = "inventorysearch.InventorySearchConfig";

    private TooltipBar1103Cases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1103-menu-tooltip-over-bar", TooltipBar1103Cases::overBar);
    }

    static void overBar(Session c) throws Exception {
        MenuKit.reset(c);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(ISC, "Enabled", true);
            c.ctx().runOnClient(mc -> Mod.staticCall(ISF, "setListening", false));
            String[] lore = new String[34];
            for (int i = 0; i < lore.length; i++) {
                lore[i] = "§7Line " + (i + 1) + " of a very tall tooltip that is wide enough to reach the bar";
            }
            Map<Integer, JsonObject> slots = new LinkedHashMap<>();
            slots.put(40, FlipCroesusCases.item("minecraft:diamond_sword", "§aTall Tooltip", lore));
            MenuKit.show(c, FlipCroesusCases.menu("(1/3) Loadouts", 6, slots, Map.of()));
            MenuKit.awaitScreen(c, "(1/3) Loadouts", 100);
            c.ctx().waitTicks(8);
            int[] b = c.onClient(mc -> (int[]) Mod.field(ISF, "lastBoxBounds"));
            c.check(b != null, "the search bar was not drawn");
            if (b == null) {
                return;
            }
            c.ctx().getInput().setCursorPos(2, 2);
            c.ctx().waitTicks(6);
            BufferedImage a = CroesusUiCases.shot(c, "bar-no-hover");
            c.ctx().runOnClient(mc -> Mod.set(ISC, "setEnabled", false));
            c.ctx().waitTicks(6);
            BufferedImage d = CroesusUiCases.shot(c, "nobar-no-hover");
            CroesusUiCases.hover(c, 40);
            BufferedImage cc = CroesusUiCases.shot(c, "nobar-hover");
            c.ctx().runOnClient(mc -> Mod.set(ISC, "setEnabled", true));
            c.ctx().waitTicks(6);
            BufferedImage bb = CroesusUiCases.shot(c, "bar-hover");
            double[] g = CroesusUiCases.geometry(c);
            double px = a.getWidth() / g[2];
            int x0 = Math.max(0, (int) Math.floor(b[0] * px)), y0 = Math.max(0, (int) Math.floor(b[1] * px));
            int x1 = Math.min(a.getWidth(), (int) Math.ceil((b[0] + b[2]) * px));
            int y1 = Math.min(a.getHeight(), (int) Math.ceil((b[1] + b[3]) * px));
            int barPixels = 0, overlap = 0, barOverTooltip = 0;
            for (int y = y0; y < y1; y++) {
                for (int x = x0; x < x1; x++) {
                    if (CroesusUiCases.changed(a.getRGB(x, y), d.getRGB(x, y))) {
                        barPixels++;
                    }
                    if (CroesusUiCases.changed(cc.getRGB(x, y), d.getRGB(x, y))) {
                        overlap++;
                    }
                    if (CroesusUiCases.changed(bb.getRGB(x, y), cc.getRGB(x, y))) {
                        barOverTooltip++;
                    }
                }
            }
            c.note("bar " + java.util.Arrays.toString(b) + " px/gui " + px + " rect " + x0 + "," + y0 + "-" + x1 + "," + y1
                    + ": bar pixels " + barPixels + ", tooltip pixels in the bar area " + overlap
                    + ", bar pixels over the tooltip " + barOverTooltip);
            c.check(barPixels > 200, "the bar changed only " + barPixels + " pixels in its rectangle - it was not drawn");
            c.check(overlap > 200, "the tooltip covers only " + overlap + " pixels of the bar's rectangle - nothing tested");
            c.check(barOverTooltip == 0, barOverTooltip + " pixels in the overlap show the bar instead of the tooltip");
        } finally {
            c.ctx().runOnClient(mc -> Mod.staticCall(ISF, "setListening", false));
            MenuKit.reset(c);
        }
    }
}
