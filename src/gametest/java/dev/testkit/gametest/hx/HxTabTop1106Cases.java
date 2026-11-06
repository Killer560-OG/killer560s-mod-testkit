package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;

import java.awt.image.BufferedImage;

/**
 * Tab on top (mod branch tabtop-1106, killer560 2026-11-06: "tab should always go over any other thing on screen"):
 * <ul>
 *   <li>1106-tabtop-order: the player list is drawn after every mod HUD layer. Dark Mode's Tint HUD is the mod's last
 *       layer; with the list shown, its white text pixels must come through untinted (the old jar tinted them).</li>
 *   <li>1106-tabtop-hold: a shared Ability/player-list key hides the list for a quick tap, shows it once held 300 ms,
 *       shows it at once when Hypixel answers "on cooldown", and a release starts over (the tap/hold rule is driven with
 *       its own clock, because the real key poll cannot be faked).</li>
 *   <li>1106-tabtop-cast: outside a dungeon the shared key never hides the list (nothing is cast).</li>
 * </ul>
 */
final class HxTabTop1106Cases {

    private static final String FEATURE = "abilitykeybinds.AbilityKeybindsFeature";

    private HxTabTop1106Cases() {
    }

    static void register(Session s) {
        s.test("1106-tabtop-order", HxTabTop1106Cases::order);
        s.test("1106-tabtop-hold", HxTabTop1106Cases::hold);
        s.test("1106-tabtop-cast", HxTabTop1106Cases::cast);
    }

    private static void tab(Session c, boolean down) {
        c.onClient(mc -> {
            mc.options.keyPlayerList.setDown(down);
            return null;
        });
    }

    private static void order(Session c) throws Exception {
        HxKit.hub(c);
        byte[] saved = HxDarkMode1102Cases.saveFile(c);
        try {
            HxDarkMode1102Cases.settings(c, false, 60, false);
            HxDarkMode1102Cases.still(c);
            tab(c, true);
            BufferedImage base = HxDarkMode1102Cases.shot(c, "list");
            HxDarkMode1102Cases.settings(c, true, 60, true);
            BufferedImage tinted = HxDarkMode1102Cases.shot(c, "list-tinthud");
            tab(c, false);
            int w = base.getWidth(), h = base.getHeight();
            long white = 0, kept = 0;
            for (int y = h / 100; y < h * 14 / 100; y++) {
                for (int x = w * 30 / 100; x < w * 70 / 100; x++) {
                    if (HxDarkMode1102Cases.lum(base.getRGB(x, y)) >= 700) {
                        white++;
                        if (HxDarkMode1102Cases.lum(tinted.getRGB(x, y)) >= 600) {
                            kept++;
                        }
                    }
                }
            }
            c.note("player list white pixels " + white + ", still bright under the over-HUD tint " + kept);
            c.check(white > 40, "the player list never showed (" + white + " white pixels in the top band)");
            c.check(kept * 10 >= white * 8, "the mod's last HUD layer was drawn over the player list: " + kept + " of " + white
                    + " white pixels survived the tint");
        } finally {
            tab(c, false);
            HxDarkMode1102Cases.restore(c, saved);
        }
    }

    private static boolean hidden(Session c, boolean shared, long now) {
        return c.onClient(mc -> (Boolean) Mod.staticCall(FEATURE, "listHidden", shared, now));
    }

    private static void hold(Session c) throws Exception {
        HxKit.hub(c);
        c.onClient(mc -> Mod.staticCall(FEATURE, "listHidden", false, 0L));
        c.check(!hidden(c, false, 100), "an unshared key hid the list");
        c.check(hidden(c, true, 1000), "a tap on the shared key showed the list at once");
        c.check(hidden(c, true, 1150), "the list showed 150 ms into a tap");
        c.check(hidden(c, true, 1299), "the list showed 299 ms into a hold");
        c.check(!hidden(c, true, 1300), "the list stayed hidden after a 300 ms hold");
        c.check(!hidden(c, true, 2500), "a long hold hid the list");
        c.check(!hidden(c, false, 2600), "the key was released and the list was still hidden");
        c.check(hidden(c, true, 5000), "a second tap showed the list (the hold clock did not restart)");
        // Hypixel's own cooldown line while the key is down: nothing was cast, so the list shows.
        c.server().command("tellraw @a \"This ability is on cooldown for 7s more.\"");
        c.server().sync(20);
        c.ctx().waitTicks(8);
        c.check(!hidden(c, true, 5060), "the cooldown line did not bring the list back");
        c.check(!hidden(c, false, 5100), "released: still hidden");
        c.check(hidden(c, true, 9000), "after a release the next tap did not hide the list again");
    }

    private static void cast(Session c) throws Exception {
        HxKit.hub(c);
        Boolean off = c.onClient(mc -> {
            mc.options.keyPlayerList.setDown(true);
            try {
                return (Boolean) Mod.staticCall(FEATURE, "hidesPlayerList", mc);
            } finally {
                mc.options.keyPlayerList.setDown(false);
            }
        });
        c.note("outside a dungeon with the list key down, hidesPlayerList = " + off);
        c.check(Boolean.FALSE.equals(off), "the list was hidden outside a dungeon (nothing is cast there)");
    }
}
