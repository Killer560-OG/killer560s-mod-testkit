package dev.testkit.gametest.menu;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

/**
 * Mod branch f7fix-1103 (killer560, 2026-11-03: "sometimes i fast leap while it is on delay and it freezes my movement even
 * though it cant even open the menu"). The server never opens a menu here (menu.onUse null), so Fast Leap must give inputs
 * back within the menu wait cap, at once on Hypixel's "This ability is on cooldown for Ns." line, and never start a leap on
 * a cooldown it knows (from its own leap or from Hypixel's "You have teleported to X!" line of a leap done by hand).
 */
final class FastLeapFix1103Cases {

    private static final String LM = "fastleap.LeapManager";
    private static final String FLC = "fastleap.FastLeapConfig";

    private FastLeapFix1103Cases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1103-leap-no-menu-releases", FastLeapFix1103Cases::noMenu);
        MenuSuite.test(s, "1103-leap-cooldown-line", FastLeapFix1103Cases::cooldownLine);
        MenuSuite.test(s, "1103-leap-manual-cooldown", FastLeapFix1103Cases::manualCooldown);
    }

    private static boolean busy(Session c) {
        return c.onClient(mc -> (Boolean) Mod.staticCall(LM, "isBusy"));
    }

    private static boolean blocks(Session c) {
        return c.onClient(mc -> (Boolean) Mod.staticCall(LM, "blocksInput"));
    }

    private static double cd(Session c) {
        return c.onClient(mc -> ((Number) Mod.staticCall(LM, "cooldownTicks")).doubleValue());
    }

    private static void setup(Session c) {
        DungeonMenuCases.inDungeon(c);
        c.hx().give(0, MenuKit.item("items.spirit-leap"));
        c.waitUntil("the leap in hotbar slot 0", mc -> "SPIRIT_LEAP".equals(
                Mod.staticCall("cheatutils.CheatUtils", "skyblockId", mc.player.getInventory().getItem(0))), 60);
        // the server answers a use with NO menu
        c.hx().call("menu.onUse", "id", "SPIRIT_LEAP", "menu", null);
        c.ctx().runOnClient(mc -> Mod.staticCall(LM, "setCooldownTicksForTest", 0.0));
    }

    private static void teardown(Session c) {
        c.ctx().runOnClient(mc -> Mod.staticCall(LM, "setCooldownTicksForTest", 0.0));
        c.hx().call("menu.onUse", "id", "SPIRIT_LEAP", "menu", null);
        c.hx().call("give", "slot", 0, "stack", "minecraft:air");
        DungeonMenuCases.leaveDungeon(c);
    }

    private static void leap(Session c) {
        c.ctx().runOnClient(mc -> Mod.staticCall(LM, "leap", "Bob", true, false, false));
    }

    private static void noMenu(Session c) {
        MenuKit.reset(c);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(FLC, "Enabled", true);
            if (!MenuKit.cheat()) {
                c.note("legit jar: Fast Leap is cheat-only, nothing to check");
                return;
            }
            cfg.set(FLC, "BlockInputs", true);
            setup(c);
            int cap = c.onClient(mc -> ((Number) Mod.staticCall(LM, "menuWaitCapTicks")).intValue());
            int timeout = c.onClient(mc -> ((Number) Mod.get(FLC, "getTimeoutTicks")).intValue());
            c.check(cap >= 1 && cap < timeout, "the menu wait cap " + cap + " is not below the Leap Timeout " + timeout);
            int uses0 = c.events("use_item").size();
            leap(c);
            c.waitUntil("the leap used the item", mc -> c.events("use_item").size() > uses0, 60);
            int ticks = 0;
            while (busy(c) && ticks < 80) {
                c.ctx().waitTicks(1);
                ticks++;
            }
            c.note("menu never opened: leap released after " + ticks + " ticks of waiting; cap " + cap + ", timeout " + timeout);
            c.check(!busy(c), "the leap still holds inputs after 80 ticks without a menu");
            c.check(ticks <= cap + 4, "the leap took " + ticks + " ticks to release, cap is " + cap);
            c.check(!blocks(c), "inputs are still blocked after the leap gave up");
        } finally {
            teardown(c);
            MenuKit.reset(c);
        }
    }

    private static void cooldownLine(Session c) {
        MenuKit.reset(c);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(FLC, "Enabled", true);
            if (!MenuKit.cheat()) {
                c.note("legit jar: Fast Leap is cheat-only, nothing to check");
                return;
            }
            cfg.set(FLC, "BlockInputs", true);
            setup(c);
            int uses0 = c.events("use_item").size();
            leap(c);
            c.waitUntil("the leap used the item", mc -> c.events("use_item").size() > uses0, 60);
            c.hx().chat("This ability is on cooldown for 2s.");
            int ticks = 0;
            while (busy(c) && ticks < 20) {
                c.ctx().waitTicks(1);
                ticks++;
            }
            c.note("Hypixel's cooldown line released the leap after " + ticks + " ticks; believed cooldown " + cd(c) + " ticks");
            c.check(!busy(c) && !blocks(c), "the cooldown line did not release the leap");
            c.check(ticks <= 4, "the cooldown line took " + ticks + " ticks to release it");
            c.check(cd(c) > 30 && cd(c) <= 40, "the cooldown should read about 2 s (40 ticks), read " + cd(c));
            // A second leap on the known cooldown never uses the item and never blocks.
            int uses1 = c.events("use_item").size();
            leap(c);
            c.ctx().waitTicks(6);
            c.check(!busy(c) && !blocks(c), "a leap on a known cooldown held inputs");
            c.check(c.events("use_item").size() == uses1, "a leap on a known cooldown used the item");
            // The line with no leap of ours waiting is harmless.
            c.hx().chat("This ability is on cooldown for 9s.");
            c.ctx().waitTicks(3);
            c.check(!busy(c), "a stray cooldown line started something");
        } finally {
            teardown(c);
            MenuKit.reset(c);
        }
    }

    private static void manualCooldown(Session c) {
        MenuKit.reset(c);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(FLC, "Enabled", true);
            if (!MenuKit.cheat()) {
                c.note("legit jar: Fast Leap is cheat-only, nothing to check");
                return;
            }
            cfg.set(FLC, "BlockInputs", true);
            setup(c);
            c.check(cd(c) == 0.0, "premise: cooldown is clear");
            c.hx().chat("You have teleported to Bob!");
            c.ctx().waitTicks(2);
            c.check(cd(c) >= 40, "a leap done by hand left cooldown " + cd(c) + ", want about 48");
            int uses0 = c.events("use_item").size();
            leap(c);
            c.ctx().waitTicks(6);
            c.check(!busy(c) && !blocks(c), "a leap right after a manual one held inputs");
            c.check(c.events("use_item").size() == uses0, "a leap right after a manual one used the item");
            c.note("manual leap line set cooldown; the next Fast Leap refused without touching inputs or the item");
        } finally {
            teardown(c);
            MenuKit.reset(c);
        }
    }
}
