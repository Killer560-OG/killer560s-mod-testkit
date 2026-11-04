package dev.testkit.gametest.hx;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.testkit.gametest.hx.HxScoreboardCases.*;

/**
 * 1104-menu-bz-scoreboard (mod branch bzscore-1104, killer560 2026-11-04: "dont hide my scoreboardd in the /bz menu"),
 * on the Hx server's real Hypixel-shaped sidebar. With the Bazaar open and Hide HUDs in Bazaar on: the Bazaar draws the
 * board itself over its backdrop (the draw stamp is fresh and the view reports a box), the panel sits beside it without
 * overlapping, the other HUD layers are still skipped by the gate, and with another menu open Hide In Menus still hides
 * the board. On a window too small to share, the panel wins.
 */
final class HxBzScoreboard1104Cases {

    private static final String VIEW = "bazaar.BazaarView";
    private static final String BZCFG = "bazaar.BazaarConfig";

    private HxBzScoreboard1104Cases() {
    }

    static void register(Session s) {
        s.test("1104-menu-bz-scoreboard", HxBzScoreboard1104Cases::run);
    }

    private static long drawn(Minecraft mc) {
        return (Long) Mod.staticCall("hud.HudSeen", "msSince", "custom_scoreboard", System.currentTimeMillis());
    }

    private static void openBazaar(Session c) {
        c.ctx().runOnClient(mc -> {
            try {
                Screen s = (Screen) Mod.cls("bazaar.BazaarScreen").getConstructor(Screen.class).newInstance((Screen) null);
                McCompat.setScreen(mc, s);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        c.ctx().waitTicks(8);
        c.check(c.onClient(mc -> McCompat.screen(mc) != null
                && McCompat.screen(mc).getClass().getName().endsWith("bazaar.BazaarScreen")), "the Bazaar screen did not open");
    }

    private static void close(Session c) {
        c.ctx().runOnClient(mc -> McCompat.setScreen(mc, null));
        c.ctx().waitTicks(4);
    }

    private static int skipped(Session c) {
        return c.onClient(mc -> (Integer) Mod.staticCall("bazaar.BazaarHud", "skippedForTest"));
    }

    private static void run(Session c) throws Exception {
        try (Saved saved = new Saved(c)) {
            skyblock(c);
            enable(c);
            c.onClient(mc -> {
                Object sb = Mod.cfg("scoreboard.CustomScoreboardConfig");
                Mod.call(sb, "setHideInMenus", true);
                Mod.call(sb, "save");
                McCompat.setHudHidden(mc, false);
                return null;
            });
            c.ctx().runOnClient(mc -> Mod.set(BZCFG, "setHideHuds", true));
            setWindow(c, 1366, 768, 2);
            waitDrawn(c, "no screen");

            // 1. Bazaar open, Hide HUDs on, Hide In Menus on: the board is still drawn, by the Bazaar.
            long framesBefore = c.onClient(mc -> (Long) Mod.staticCall("scoreboard.CustomScoreboardFeature", "drawnFrames"));
            int skipBefore = skipped(c);
            openBazaar(c);
            c.ctx().waitTicks(20);
            c.check(c.onClient(mc -> drawn(mc) < 300), "Bazaar open: the scoreboard was not drawn (stamp "
                    + c.onClient(mc -> drawn(mc)) + " ms old)");
            long framesAfter = c.onClient(mc -> (Long) Mod.staticCall("scoreboard.CustomScoreboardFeature", "drawnFrames"));
            c.check(framesAfter - framesBefore > 10, "Bazaar open: the board drew only " + (framesAfter - framesBefore) + " frames");
            int[] box = c.onClient(mc -> (int[]) Mod.staticCall(VIEW, "scoreboardBox"));
            c.check(box != null && box[2] > 20 && box[3] > 20, "the view reported no scoreboard box");
            int[] span = c.onClient(mc -> (int[]) Mod.staticCall(VIEW, "panelSpan"));
            boolean clear = c.onClient(mc -> (Boolean) Mod.staticCall(VIEW, "scoreboardClear"));
            int[] hb = c.onClient(HxScoreboardCases::box);
            c.note("board " + java.util.Arrays.toString(box) + " (HUD box x" + hb[0] + " y" + hb[1] + " " + hb[2] + "x" + hb[3]
                    + ", screen " + hb[4] + "x" + hb[5] + "), panel x" + span[0] + " w" + span[1] + ", clear=" + clear);
            c.check(clear, "the panel was not fitted beside the board on a 683 px wide GUI");
            c.check(span[0] + span[1] <= box[0], "the panel (right edge " + (span[0] + span[1]) + ") overlaps the board (left "
                    + box[0] + ")");
            // The view's canvas is Auto Scale's (not 1:1 with GUI pixels): same spot means the same ratio on every number.
            double rx = box[0] / (double) hb[0];
            c.check(Math.abs(box[1] / (double) hb[1] - rx) < 0.04 && Math.abs(box[2] / (double) hb[2] - rx) < 0.04
                    && Math.abs(box[3] / (double) hb[3] - rx) < 0.04, "the Bazaar drew the board away from its HUD spot");
            c.check(skipped(c) - skipBefore > 20, "other HUD layers are not skipped by the gate (" + (skipped(c) - skipBefore) + ")");
            Path p = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(c.name() + "-bazaar-board"));
            c.note("screenshot " + p);
            c.check(Files.exists(p), "no screenshot was written");

            // 2. A window too small to share: the panel wins.
            setWindow(c, 800, 500, 2);
            c.ctx().waitTicks(12);
            int[] span2 = c.onClient(mc -> (int[]) Mod.staticCall(VIEW, "panelSpan"));
            boolean clear2 = c.onClient(mc -> (Boolean) Mod.staticCall(VIEW, "scoreboardClear"));
            int[] box2 = c.onClient(mc -> (int[]) Mod.staticCall(VIEW, "scoreboardBox"));
            c.note("small window: board " + java.util.Arrays.toString(box2) + ", panel x" + span2[0] + " w" + span2[1]
                    + ", clear=" + clear2);
            c.check(box2 != null && c.onClient(mc -> drawn(mc) < 300), "small window: the board was not drawn");
            c.check(clear2 ? span2[0] + span2[1] <= box2[0] : span2[1] >= 350,
                    "small window: the panel neither fits beside the board nor keeps its size (w " + span2[1] + ")");
            setWindow(c, 1366, 768, 2);

            // 3. Hide HUDs off: still exactly one draw path (the Bazaar's), board still drawn.
            c.ctx().runOnClient(mc -> Mod.set(BZCFG, "setHideHuds", false));
            c.ctx().waitTicks(10);
            c.check(c.onClient(mc -> drawn(mc) < 300), "Hide HUDs off: the board was not drawn");
            c.ctx().runOnClient(mc -> Mod.set(BZCFG, "setHideHuds", true));
            close(c);

            // 4. Another menu: Hide In Menus still hides the board; off, it shows.
            waitDrawn(c, "after closing");
            c.ctx().runOnClient(mc -> McCompat.setScreen(mc, new Screen(Component.literal("other menu")) {
            }));
            c.ctx().waitTicks(15);
            c.check(c.onClient(mc -> drawn(mc) > 500), "another menu, Hide In Menus on: the board was still drawn ("
                    + c.onClient(mc -> drawn(mc)) + " ms)");
            c.onClient(mc -> {
                Object sb = Mod.cfg("scoreboard.CustomScoreboardConfig");
                Mod.call(sb, "setHideInMenus", false);
                return null;
            });
            c.ctx().waitTicks(15);
            c.check(c.onClient(mc -> drawn(mc) < 300), "another menu, Hide In Menus off: the board was not drawn");
            close(c);
            // 5. Custom Scoreboard off: the Bazaar draws vanilla's sidebar (through the mixin invoker) instead.
            c.onClient(mc -> {
                Mod.call(Mod.cfg("scoreboard.CustomScoreboardConfig"), "setEnabled", false);
                return null;
            });
            c.ctx().waitTicks(10);
            long v0 = c.onClient(mc -> (Long) Mod.staticCall("scoreboard.CustomScoreboardFeature", "vanillaOverBazaarFrames"));
            openBazaar(c);
            c.ctx().waitTicks(20);
            long v1 = c.onClient(mc -> (Long) Mod.staticCall("scoreboard.CustomScoreboardFeature", "vanillaOverBazaarFrames"));
            int[] vbox = c.onClient(mc -> (int[]) Mod.staticCall(VIEW, "scoreboardBox"));
            c.note("Custom Scoreboard off: vanilla sidebar drawn " + (v1 - v0) + " frames, box " + java.util.Arrays.toString(vbox));
            c.check(v1 - v0 > 10 && vbox != null, "Custom Scoreboard off: the Bazaar did not draw vanilla's sidebar");
            c.ctx().waitTicks(2);
            Path p2 = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(c.name() + "-bazaar-vanilla"));
            c.note("screenshot " + p2);
            close(c);
            c.note("1104: Bazaar keeps the board, panel beside it, other menus still hide it with Hide In Menus");
        }
    }
}
