package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 875-878 (croesus-ui, 2026-10-12), killer560's Croesus menu batch:
 * <ul>
 *   <li>875 Highlight Runs redesigned: with the cursor on a run (tooltip up), the highlight changes pixels only INSIDE
 *       run slots' 16x16 squares (no lines in the gaps), and none inside the tooltip's box - it is drawn behind the items,
 *       under the tooltip. The shots are in the report's screens/.</li>
 *   <li>876 Hide Opened Runs: a claimed run's slot draws exactly like an empty slot, hovering it shows no tooltip and no
 *       hover highlight, a click and a number key on it reach the server as nothing - and a click on an unclaimed run
 *       still does (control).</li>
 *   <li>877 Auto Croesus still goes back into a claimed run for the keyed chest with Hide Opened Runs on (766's flow).</li>
 *   <li>878 "Unclaimed runs X / 60" and "Already claimed" across all three pages: partial ("+", pages seen) until every
 *       page was read, exact after.</li>
 * </ul>
 */
final class CroesusUiCases {

    private CroesusUiCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "875-menu-croesus-highlight-under-tooltip", CroesusUiCases::highlight);
        MenuSuite.test(s, "876-menu-croesus-hide-opened-runs", CroesusUiCases::hide);
        MenuSuite.test(s, "877-menu-croesus-hide-auto-revisit", c -> {
            try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
                cfg.set("croesus.CroesusConfig", "HideOpenedRuns", true);
                FlipCroesusCases.croesus(c, true);
            }
        });
        MenuSuite.test(s, "878-menu-croesus-all-pages-total", CroesusUiCases::totals);
    }

    static final int[] RUN_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};

    static JsonObject head(boolean claimed) {
        return FlipCroesusCases.item("minecraft:player_head", "§cMaster Mode The Catacombs", "§7Floor IV", "",
                "§7Players:", " §aPlayer0§f: §eMage (39)", "", claimed ? "§7Opened Chest: §5Obsidian" : "§cNo chests opened yet!",
                "§8Chests expire in 1d 12h!", "", "§eClick to view chests!");
    }

    /** A Croesus page: {@code unclaimed} then {@code claimed} runs in the run slots; no filler, so free slots are empty. */
    static JsonObject page(String title, int unclaimed, int claimed) {
        Map<Integer, JsonObject> slots = new LinkedHashMap<>();
        Map<Integer, JsonObject> on = new LinkedHashMap<>();
        for (int i = 0; i < unclaimed + claimed; i++) {
            slots.put(RUN_SLOTS[i], head(i >= unclaimed));
            on.put(RUN_SLOTS[i], new JsonObject()); // recorded as menu.action, opens nothing
        }
        slots.put(49, FlipCroesusCases.item("minecraft:barrier", "§cClose"));
        JsonObject m = FlipCroesusCases.menu(title, 6, slots, on);
        m.addProperty("fill", false);
        return m;
    }

    static AutoCloseable cfg(Session c, boolean highlight, boolean hide) {
        MenuKit.reset(c);
        MenuKit.Cfg cfg = new MenuKit.Cfg(c);
        cfg.set("croesus.CroesusConfig", "ChestProfitEnabled", true)
                .set("croesus.CroesusConfig", "HighlightRuns", highlight);
        if (hide) { // only set when wanted, so 875 also runs on a jar from before the option (its "before" shots)
            cfg.set("croesus.CroesusConfig", "HideOpenedRuns", true);
        }
        return () -> {
            MenuKit.reset(c);
            cfg.close();
        };
    }

    // ---- geometry ---------------------------------------------------------------------------------------------------

    /** {left, top} of the open container in GUI units, and the screenshot's pixels per GUI unit given its width. */
    static double[] geometry(Session c) {
        return c.onClient(mc -> {
            AbstractContainerScreen<?> s = (AbstractContainerScreen<?>) McCompat.screen(mc);
            return new double[]{((Number) Mod.field(s, "leftPos")).doubleValue(), ((Number) Mod.field(s, "topPos")).doubleValue(),
                    s.width, mc.getWindow().getGuiScale(), ((Number) Mod.field(s, "imageWidth")).doubleValue(),
                    ((Number) Mod.field(s, "imageHeight")).doubleValue()};
        });
    }

    /** A slot's x/y in the menu (GUI, relative to the container). */
    static int[] slotXY(Session c, int slot) {
        return c.onClient(mc -> {
            var sl = ((AbstractContainerScreen<?>) McCompat.screen(mc)).getMenu().slots.get(slot);
            return new int[]{sl.x, sl.y};
        });
    }

    static void hover(Session c, int slot) {
        double[] g = geometry(c);
        int[] xy = slotXY(c, slot);
        c.ctx().getInput().setCursorPos((g[0] + xy[0] + 8) * g[3], (g[1] + xy[1] + 8) * g[3]);
        c.ctx().waitTicks(6);
    }

    static BufferedImage shot(Session c, String label) {
        String name = c.name() + "-" + label;
        c.ctx().runOnClient(McCompat::clearChatAndToasts); // a fading toast is not something the mod drew
        c.ctx().waitTicks(2);
        Path taken = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(name));
        Path kept = dev.testkit.harness.Report.screenshot(name, taken);
        try {
            return javax.imageio.ImageIO.read(taken.toFile());
        } catch (java.io.IOException e) {
            throw new AssertionError("could not read " + taken + " (kept " + kept + ")", e);
        }
    }

    /** Pixels whose summed RGB differs by more than 30 (the testkit's changed-pixel rule). */
    static boolean changed(int a, int b) {
        int d = Math.abs(((a >> 16) & 255) - ((b >> 16) & 255)) + Math.abs(((a >> 8) & 255) - ((b >> 8) & 255))
                + Math.abs((a & 255) - (b & 255));
        return d > 30;
    }

    // ---- 875 ----------------------------------------------------------------------------------------------------------

    static void highlight(Session c) throws Exception {
        try (AutoCloseable w = cfg(c, false, false)) {
            MenuKit.show(c, page("(1/3) Croesus", 27, 1));
            MenuKit.awaitScreen(c, "(1/3) Croesus", 100);
            c.ctx().waitTicks(10);
            int hoverSlot = 21; // mid-grid: its tooltip covers runs to its right and below
            c.ctx().getInput().setCursorPos(2, 2);
            c.ctx().waitTicks(6);
            BufferedImage plain = shot(c, "plain");
            hover(c, hoverSlot);
            BufferedImage offTip = shot(c, "hover-highlight-off");
            c.ctx().runOnClient(mc -> Mod.set("croesus.CroesusConfig", "setHighlightRuns", true));
            c.ctx().waitTicks(6);
            BufferedImage onTip = shot(c, "hover-highlight-on");
            c.ctx().getInput().setCursorPos(2, 2);
            c.ctx().waitTicks(6);
            BufferedImage onPlain = shot(c, "highlight-on");
            double[] g = geometry(c);
            double px = plain.getWidth() / g[2];
            // Run squares in screenshot pixels.
            List<int[]> squares = new ArrayList<>();
            for (int i = 0; i < 28; i++) {
                int[] xy = slotXY(c, RUN_SLOTS[i]);
                squares.add(new int[]{(int) Math.floor((g[0] + xy[0]) * px), (int) Math.floor((g[1] + xy[1]) * px),
                        (int) Math.ceil((g[0] + xy[0] + 16) * px), (int) Math.ceil((g[1] + xy[1] + 16) * px)});
            }
            // The tooltip's box: what hovering changed with the highlight off, outside the hovered slot itself.
            int[] hs = squares.get(indexOf(hoverSlot));
            int tx0 = Integer.MAX_VALUE, ty0 = Integer.MAX_VALUE, tx1 = -1, ty1 = -1;
            for (int y = 0; y < plain.getHeight(); y++) {
                for (int x = 0; x < plain.getWidth(); x++) {
                    boolean inHovered = x >= hs[0] - 2 && x < hs[2] + 2 && y >= hs[1] - 2 && y < hs[3] + 2;
                    if (!inHovered && changed(plain.getRGB(x, y), offTip.getRGB(x, y))) {
                        tx0 = Math.min(tx0, x);
                        ty0 = Math.min(ty0, y);
                        tx1 = Math.max(tx1, x);
                        ty1 = Math.max(ty1, y);
                    }
                }
            }
            c.check(tx1 > tx0 && ty1 > ty0, "no tooltip appeared when hovering slot " + hoverSlot);
            int overTooltip = 0, inSquares = 0, outsideSquares = 0, overlapsRuns = 0;
            // Bleed is judged inside the container; the info box is an Edit HUD element and may sit anywhere.
            int cx0 = (int) (g[0] * px), cy0 = (int) (g[1] * px);
            int cx1 = (int) ((g[0] + g[4]) * px), cy1 = (int) ((g[1] + g[5]) * px);
            for (int[] sq : squares) {
                if (sq[2] > tx0 && sq[0] < tx1 && sq[3] > ty0 && sq[1] < ty1) {
                    overlapsRuns++;
                }
            }
            for (int y = 0; y < plain.getHeight(); y++) {
                for (int x = 0; x < plain.getWidth(); x++) {
                    if (changed(offTip.getRGB(x, y), onTip.getRGB(x, y)) && x >= tx0 && x <= tx1 && y >= ty0 && y <= ty1) {
                        overTooltip++;
                    }
                    if (!changed(plain.getRGB(x, y), onPlain.getRGB(x, y))) {
                        continue;
                    }
                    boolean in = false;
                    for (int[] sq : squares) {
                        if (x >= sq[0] && x < sq[2] && y >= sq[1] && y < sq[3]) {
                            in = true;
                            break;
                        }
                    }
                    if (in) {
                        inSquares++;
                    } else if (x >= cx0 && x < cx1 && y >= cy0 && y < cy1) {
                        outsideSquares++;
                    }
                }
            }
            c.check(overlapsRuns > 0, "the tooltip box " + tx0 + "," + ty0 + "-" + tx1 + "," + ty1 + " covers no run - nothing tested");
            c.check(inSquares > 28 * 50, "the highlight changed only " + inSquares + " pixels inside the 28 run squares");
            c.check(outsideSquares == 0, outsideSquares + " highlight pixels in the container outside the run squares (grid lines / bleed)");
            c.check(overTooltip == 0, overTooltip + " pixels inside the tooltip changed with the highlight (drawn over it)");
            c.note("tooltip box " + tx0 + "," + ty0 + "-" + tx1 + "," + ty1 + " over " + overlapsRuns + " run square(s): "
                    + overTooltip + " changed pixels in it; highlight " + inSquares + " px inside the squares, "
                    + outsideSquares + " outside");
        }
    }

    static int indexOf(int slot) {
        for (int i = 0; i < RUN_SLOTS.length; i++) {
            if (RUN_SLOTS[i] == slot) {
                return i;
            }
        }
        return 0;
    }

    // ---- 876 ----------------------------------------------------------------------------------------------------------

    static void hide(Session c) throws Exception {
        try (AutoCloseable w = cfg(c, false, true)) {
            MenuKit.show(c, page("(1/3) Croesus", 2, 2)); // 10, 11 unclaimed; 12, 13 claimed; 14 empty
            MenuKit.awaitScreen(c, "(1/3) Croesus", 100);
            c.ctx().waitTicks(10);
            c.ctx().getInput().setCursorPos(2, 2);
            c.ctx().waitTicks(6);
            BufferedImage img = shot(c, "hidden");
            double[] g = geometry(c);
            double px = img.getWidth() / g[2];
            int diffHidden = squareDiff(c, img, px, g, 12, 14);
            int diffShown = squareDiff(c, img, px, g, 10, 14);
            c.check(diffShown > 50, "control: an unclaimed run's square looks empty too (" + diffShown + " px differ)");
            c.check(diffHidden == 0, "the claimed run's square differs from an empty slot by " + diffHidden + " px");
            // Hover the hidden run: nothing appears.
            hover(c, 12);
            BufferedImage hovered = shot(c, "hidden-hover");
            int tip = 0;
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    if (changed(img.getRGB(x, y), hovered.getRGB(x, y))) {
                        tip++;
                    }
                }
            }
            c.check(tip == 0, "hovering the hidden run changed " + tip + " px (tooltip or hover highlight)");
            // A click and a number key on it: the server hears nothing. Then a click on an unclaimed run (control).
            int before = c.events("menu.click").size();
            c.ctx().getInput().pressMouse(0);
            c.ctx().waitTicks(4);
            c.ctx().getInput().pressKey(options -> options.keyHotbarSlots[0]);
            c.ctx().waitTicks(10);
            int afterHidden = c.events("menu.click").size();
            c.check(afterHidden == before, "a click/number key on the hidden run reached the server: "
                    + c.events("menu.click").subList(before, afterHidden));
            hover(c, 10);
            c.ctx().getInput().pressMouse(0);
            c.waitUntil("the control click on an unclaimed run", mc -> c.events("menu.click").size() > afterHidden, 40);
            JsonObject e = c.events("menu.click").get(c.events("menu.click").size() - 1);
            c.check(e.get("slot").getAsInt() == 10, "control click landed on " + e);
            // Off again: the claimed run is drawn.
            c.ctx().runOnClient(mc -> Mod.set("croesus.CroesusConfig", "setHideOpenedRuns", false));
            c.ctx().getInput().setCursorPos(2, 2);
            c.ctx().waitTicks(6);
            BufferedImage shown = shot(c, "hide-off");
            int diffOff = squareDiff(c, shown, px, g, 12, 14);
            c.check(diffOff > 50, "with Hide Opened Runs off the claimed run still looks empty (" + diffOff + " px)");
            c.note("hidden run square vs empty slot: " + diffHidden + " px differ (unclaimed run: " + diffShown
                    + ", claimed with the option off: " + diffOff + "); hover changed " + tip
                    + " px; 0 clicks from a click + number key on it; control click reached slot 10");
        }
    }

    /** Changed pixels between slot {@code a}'s and slot {@code b}'s 16x16 squares. */
    static int squareDiff(Session c, BufferedImage img, double px, double[] g, int a, int b) {
        int[] pa = slotXY(c, a);
        int[] pb = slotXY(c, b);
        int n = 0;
        int size = (int) Math.floor(16 * px);
        for (int dy = 0; dy < size; dy++) {
            for (int dx = 0; dx < size; dx++) {
                int ax = (int) Math.round((g[0] + pa[0]) * px) + dx;
                int ay = (int) Math.round((g[1] + pa[1]) * px) + dy;
                int bx = (int) Math.round((g[0] + pb[0]) * px) + dx;
                int by = (int) Math.round((g[1] + pb[1]) * px) + dy;
                if (changed(img.getRGB(ax, ay), img.getRGB(bx, by))) {
                    n++;
                }
            }
        }
        return n;
    }

    // ---- 878 ----------------------------------------------------------------------------------------------------------

    static void totals(Session c) throws Exception {
        try (AutoCloseable w = cfg(c, true, false)) {
            String[] titles = {"(1/3) Croesus", "(2/3) Croesus", "(3/3) Croesus"};
            int[][] counts = {{27, 1}, {20, 8}, {2, 2}};
            List<String> seen = new ArrayList<>();
            for (int p = 0; p < 3; p++) {
                MenuKit.reset(c);
                MenuKit.show(c, page(titles[p], counts[p][0], counts[p][1]));
                MenuKit.awaitScreen(c, titles[p], 100);
                int want = p + 1;
                c.waitUntil("page " + want + " read", mc -> ((int[]) Mod.staticCall("croesus.ChestProfitFeature", "runTotals"))[2] >= want, 60);
                c.ctx().waitTicks(4);
                int[] t = c.onClient(mc -> (int[]) Mod.staticCall("croesus.ChestProfitFeature", "runTotals"));
                List<String> lines = panel(c);
                seen.add(titles[p] + " -> " + java.util.Arrays.toString(t) + " " + lines);
                int u = 0, k = 0;
                for (int i = 0; i <= p; i++) {
                    u += counts[i][0];
                    k += counts[i][1];
                }
                String plus = p < 2 ? "+" : "";
                c.check(t[0] == u && t[1] == k && t[2] == p + 1 && t[3] == 3, "after " + titles[p] + " totals " + java.util.Arrays.toString(t));
                c.check(lines.contains("Unclaimed runs=" + u + plus + " / 60") && lines.contains("Already claimed=" + k + plus),
                        "info box after " + titles[p] + ": " + lines);
                c.check((p < 2) == lines.contains("Pages seen " + (p + 1) + " of 3=null"), "partial marker after " + titles[p] + ": " + lines);
            }
            // Back to page 1 after a claim there: the total follows the page as it is now.
            MenuKit.reset(c);
            MenuKit.show(c, page(titles[0], 26, 2));
            MenuKit.awaitScreen(c, titles[0], 100);
            c.waitUntil("page 1 re-read", mc -> ((int[]) Mod.staticCall("croesus.ChestProfitFeature", "runTotals"))[1] == 12, 60);
            c.note(String.join("; ", seen) + "; page 1 again with one more claimed -> "
                    + java.util.Arrays.toString(c.<int[]>onClient(mc -> (int[]) Mod.staticCall("croesus.ChestProfitFeature", "runTotals"))));
        }
    }

    /** The info box's lines as "text=right". */
    static List<String> panel(Session c) {
        return c.onClient(mc -> {
            List<String> out = new ArrayList<>();
            for (Object l : (List<?>) Mod.field("croesus.ChestProfitFeature", "lastLines")) {
                out.add(Mod.call(l, "text") + "=" + Mod.call(l, "right"));
            }
            return out;
        });
    }
}
