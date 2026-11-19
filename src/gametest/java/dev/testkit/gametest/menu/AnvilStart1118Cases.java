package dev.testkit.gametest.menu;

import dev.testkit.gametest.SameTick;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Auto Anvil's Start Mode (autoanvil-start-1118): "Start Button" shows a Start / Stop Auto Anvil button over the anvil
 * menu and runs only after it is pressed; "Auto Start" begins by itself and never loops on one open menu. Cheat jar only.
 * 1118-anvil-start-button, -auto-start, -no-button, -persist and -same-tick (his click in the auto's ticks, Grim).
 */
final class AnvilStart1118Cases {

    private static final String CFG = "autoanvil.AutoAnvilConfig";
    private static final String FEATURE = "autoanvil.AutoAnvilFeature";

    private AnvilStart1118Cases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1118-anvil-start-button", AnvilStart1118Cases::startButton);
        MenuSuite.test(s, "1118-anvil-auto-start", AnvilStart1118Cases::autoStart);
        MenuSuite.test(s, "1118-anvil-no-button", AnvilStart1118Cases::noButton);
        MenuSuite.test(s, "1118-anvil-start-persist", AnvilStart1118Cases::persist);
        MenuSuite.test(s, "1118-anvil-start-same-tick", AnvilStart1118Cases::sameTick);
    }

    static Object mode(Session c, String name) {
        return Mod.enumValue(CFG + "$StartMode", name);
    }

    private static MenuKit.Cfg cfg(Session c, String mode, boolean enabled, int minMs, int maxMs) {
        MenuKit.Cfg cfg = new MenuKit.Cfg(c);
        cfg.set(CFG, "Enabled", enabled).set(CFG, "StartMode", mode(c, mode)).set(CFG, "MinDelayMs", minMs)
                .set(CFG, "MaxDelayMs", maxMs).set(CFG, "Cascade", true).set(CFG, "CloseWhenDone", false);
        return cfg;
    }

    private static boolean running(Session c) {
        return AnvilCases.running(c);
    }

    /** For a waitUntil predicate, which already runs on the client thread. */
    private static boolean runningNow() {
        return (Boolean) Mod.staticCall(FEATURE, "isRunning");
    }

    /** Press the Start button with the real mouse at the middle of its drawn box. */
    private static void pressButton(Session c) {
        double[] at = c.onClient(mc -> {
            int[] b = (int[]) Mod.staticCall(FEATURE, "startButtonBounds");
            double s = mc.getWindow().getGuiScale();
            return new double[]{(b[0] + b[2] / 2.0) * s, (b[1] + b[3] / 2.0) * s};
        });
        c.ctx().getInput().setCursorPos(at[0], at[1]);
        c.ctx().waitTicks(2);
        c.ctx().getInput().pressMouse(dev.testkit.compat.McKeys.mouse(0));
        c.ctx().waitTicks(2);
    }

    private static boolean buttonDrawn(Session c) {
        c.ctx().waitTicks(3);
        int[] b = c.onClient(mc -> (int[]) Mod.staticCall(FEATURE, "startButtonBounds"));
        return b[2] > 0 && b[3] > 0;
    }

    static void startButton(Session c) throws Exception {
        MenuKit.reset(c);
        InventoryCases.clearInv(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: Auto Anvil does not exist");
            return;
        }
        try (MenuKit.Cfg cfg = cfg(c, "START_BUTTON", true, 300, 400)) {
            // six pairs of different tiers so a run lasts long enough to stop in the middle
            String[] kinds = {"protection:1", "protection:2", "smite:1", "smite:2", "power:1", "power:2"};
            int s = 0;
            for (String k : kinds) {
                AnvilCases.give(c, s++, AnvilCases.book(k));
                AnvilCases.give(c, s++, AnvilCases.book(k));
            }
            c.ctx().waitTicks(5);
            AnvilCases.openAnvil(c, "{}");
            c.ctx().waitTicks(80);
            c.check(buttonDrawn(c), "no Start Auto Anvil button was drawn over the anvil");
            c.check(c.events("container.click").isEmpty() && !running(c), "the run started without a press (clicks "
                    + c.events("container.click").size() + ")");
            // 1: press -> runs
            pressButton(c);
            c.waitUntil("the run to start after the press", mc -> runningNow() || !c.events("container.click").isEmpty(), 100);
            c.waitUntil("the first combine", mc -> !c.events("anvil.combine").isEmpty(), 600);
            c.check(running(c), "not running after the press");
            // 2: press again -> stops, nothing more is clicked
            pressButton(c);
            c.waitUntil("the Stop press to end the run", mc -> !runningNow(), 60);
            int at = c.events("container.click").size();
            c.ctx().waitTicks(80);
            c.check(c.events("container.click").size() == at, (c.events("container.click").size() - at) + " click(s) after Stop");
            int done = c.events("anvil.combine").size();
            c.check(done < kinds.length, "every pair combined although Stop was pressed after the first");
            Map<Long, Integer> per = MenuKit.clicksPerTick(c);
            c.check(MenuKit.maxPerTick(per) <= 1, "more than one container click in a tick: max " + MenuKit.maxPerTick(per));
            // 3: press again on the same menu -> runs again (a stopped menu is not locked out)
            pressButton(c);
            c.waitUntil("a second run after pressing Start again", mc -> c.events("anvil.combine").size() > done, 400);
            c.note("press ran it, Stop ended it after " + done + " combine(s) with " + at + " clicks and none after, Start again went on; "
                    + MenuKit.cadence(c));
        } finally {
            MenuKit.reset(c);
            InventoryCases.clearInv(c);
        }
    }

    static void autoStart(Session c) throws Exception {
        MenuKit.reset(c);
        InventoryCases.clearInv(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: Auto Anvil does not exist");
            return;
        }
        try (MenuKit.Cfg cfg = cfg(c, "AUTO_START", true, 60, 100)) {
            AnvilCases.give(c, 0, AnvilCases.book("sharpness:4"));
            AnvilCases.give(c, 1, AnvilCases.book("sharpness:4"));
            AnvilCases.give(c, 2, AnvilCases.book("smite:2")); // an odd one left over: nothing more to combine
            c.ctx().waitTicks(5);
            AnvilCases.openAnvil(c, "{}");
            AnvilCases.awaitCombines(c, 1, 400); // waits for the finish and 30 more ticks
            int clicks = c.events("container.click").size();
            c.ctx().waitTicks(150);
            int later = c.events("container.click").size();
            c.check(c.events("anvil.combine").size() == 1, "combines " + c.events("anvil.combine").size() + ", expected 1");
            c.check(later == clicks && !running(c), "it started again on the same open menu: " + (later - clicks) + " click(s), running " + running(c));
            c.check(!(Boolean) c.onClient(mc -> Mod.staticCall(FEATURE, "isArmed")), "armed with no button pressed");
            // closing and reopening is a new menu: it runs again
            MenuKit.reset(c);
            AnvilCases.give(c, 3, AnvilCases.book("sharpness:4"));
            AnvilCases.give(c, 4, AnvilCases.book("sharpness:4"));
            AnvilCases.openAnvil(c, "{}");
            c.waitUntil("a second combine on a reopened menu", mc -> c.events("anvil.combine").size() >= 2, 400);
            c.note("Auto Start: no press, 1 combine, " + clicks + " clicks, 0 more in 150 ticks; reopened menu ran again");
        } finally {
            MenuKit.reset(c);
            InventoryCases.clearInv(c);
        }
    }

    static void noButton(Session c) throws Exception {
        MenuKit.reset(c);
        InventoryCases.clearInv(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: Auto Anvil does not exist");
            return;
        }
        // Auto Start mode with the button's own spot pressed: nothing to press, nothing happens beyond the auto run
        // (checked as: the button is never armed). Feature OFF and another container: a press at the spot does nothing.
        try (MenuKit.Cfg cfg = cfg(c, "START_BUTTON", false, 60, 100)) {
            AnvilCases.give(c, 0, AnvilCases.book("sharpness:4"));
            AnvilCases.give(c, 1, AnvilCases.book("sharpness:4"));
            c.ctx().waitTicks(5);
            AnvilCases.openAnvil(c, "{}");
            c.ctx().waitTicks(20);
            int[] at = c.onClient(mc -> (int[]) Mod.staticCall("croesus.AutoCroesusFeature", "sharedStartButtonPosition"));
            clickAt(c, at[0] + 20, at[1] + 8);
            c.ctx().waitTicks(60);
            c.check(c.events("container.click").isEmpty() && !running(c), "feature OFF: a press at the button spot started it");
            MenuKit.reset(c);
            // ON, but another container
            cfg.set(CFG, "Enabled", true);
            MenuKit.show(c, MenuKit.menu("menus.ah-browser"));
            c.waitUntil("another container screen", mc -> MenuKit.screenTitle(mc) != null, 100);
            c.ctx().waitTicks(10);
            clickAt(c, at[0] + 20, at[1] + 8);
            c.ctx().waitTicks(60);
            boolean armed = c.onClient(mc -> (Boolean) Mod.staticCall(FEATURE, "isArmed"));
            c.check(!armed && !running(c), "another container: a press at the button spot armed or started Auto Anvil");
            c.note("feature OFF and another container: a press at the button spot did nothing");
        } finally {
            MenuKit.reset(c);
            InventoryCases.clearInv(c);
        }
    }

    private static void clickAt(Session c, double guiX, double guiY) {
        double s = c.onClient(mc -> (double) mc.getWindow().getGuiScale());
        c.ctx().getInput().setCursorPos(guiX * s, guiY * s);
        c.ctx().waitTicks(2);
        c.ctx().getInput().pressMouse(dev.testkit.compat.McKeys.mouse(0));
        c.ctx().waitTicks(2);
    }

    static void persist(Session c) throws Exception {
        MenuKit.reset(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: Auto Anvil does not exist");
            return;
        }
        Path file = (Path) c.onClient(mc -> Mod.field(CFG, "CONFIG_PATH"));
        byte[] was = Files.exists(file) ? Files.readAllBytes(file) : null;
        try {
            Object auto = mode(c, "AUTO_START");
            Object btn = mode(c, "START_BUTTON");
            Object cfgObj = c.onClient(mc -> Mod.cfg(CFG));
            c.ctx().runOnClient(mc -> Mod.call(cfgObj, "setStartMode", auto));
            c.ctx().runOnClient(mc -> Mod.call(cfgObj, "save"));
            c.ctx().runOnClient(mc -> Mod.staticCall(CFG, "load"));
            c.check(auto.equals(c.onClient(mc -> Mod.get(CFG, "getStartMode"))), "Auto Start did not survive save + load");
            c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(CFG), "setStartMode", btn));
            c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(CFG), "save"));
            c.ctx().runOnClient(mc -> Mod.staticCall(CFG, "load"));
            c.check(btn.equals(c.onClient(mc -> Mod.get(CFG, "getStartMode"))), "Start Button did not survive save + load");
            // a file from before the setting: it started by itself, so it loads as Auto Start
            Files.writeString(file, "{\"enabled\":true}");
            c.ctx().runOnClient(mc -> Mod.staticCall(CFG, "load"));
            c.check(auto.equals(c.onClient(mc -> Mod.get(CFG, "getStartMode"))), "an old file with no key did not load as Auto Start");
            // a missing file is the new default
            Files.delete(file);
            c.ctx().runOnClient(mc -> Mod.staticCall(CFG, "load"));
            c.check(btn.equals(c.onClient(mc -> Mod.get(CFG, "getStartMode"))), "no file did not load as Start Button");
            c.note("Start Mode saved and loaded both ways; old file without the key = Auto Start; no file = Start Button");
        } finally {
            if (was != null) {
                Files.write(file, was);
            } else {
                Files.deleteIfExists(file);
            }
            c.ctx().runOnClient(mc -> Mod.staticCall(CFG, "load"));
        }
    }

    /** His click lands in every client tick of the run (a real press on the menu's last slot); Grim must stay silent. */
    static void sameTick(Session c) throws Exception {
        MenuKit.reset(c);
        InventoryCases.clearInv(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: Auto Anvil does not exist");
            return;
        }
        SameTick st = null;
        try (MenuKit.Cfg cfg = cfg(c, "START_BUTTON", true, 60, 100)) {
            String[] kinds = {"protection:1", "protection:2", "smite:1", "smite:2"};
            int s = 0;
            for (String k : kinds) {
                AnvilCases.give(c, s++, AnvilCases.book(k));
                AnvilCases.give(c, s++, AnvilCases.book(k));
            }
            c.ctx().waitTicks(5);
            AnvilCases.openAnvil(c, "{}");
            c.ctx().waitTicks(20);
            c.check(buttonDrawn(c), "no Start Auto Anvil button was drawn");
            st = c.onClient(mc -> SameTick.start(SameTick.Act.SCREEN_CLICK, 1, p -> p instanceof ServerboundContainerClickPacket, 4, 5));
            pressButton(c);
            c.waitUntil("the combines", mc -> c.events("anvil.combine").size() >= kinds.length, 900);
            c.waitUntil("Auto Anvil to finish", mc -> !runningNow(), 200);
            c.note("combined " + c.events("anvil.combine").size() + ", server clicks " + c.events("container.click").size()
                    + "; " + MenuKit.cadence(c));
            SameTick.judge(c, st, "auto anvil", SameTick.Expect.SHARE);
            st = null;
        } finally {
            if (st != null) {
                SameTick done = st;
                c.onClient(mc -> {
                    done.close();
                    return null;
                });
            }
            MenuKit.reset(c);
            InventoryCases.clearInv(c);
        }
    }
}
