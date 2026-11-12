package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;

import java.awt.image.BufferedImage;

/**
 * Tab on top (mod branch tabtop-1106, killer560 2026-11-06: "tab should always go over any other thing on screen"):
 * <ul>
 *   <li>1106-tabtop-order: the player list is drawn after every mod HUD layer. With Dark Mode (one fill at the end of
 *       the frame, darkall-1110) the list is darkened once like everything else: its white text must still show (0.40).</li>
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
            HxDarkMode1102Cases.settings(c, false, 60);
            HxDarkMode1102Cases.still(c);
            tab(c, true);
            BufferedImage base = HxDarkMode1102Cases.shot(c, "list");
            HxDarkMode1102Cases.settings(c, true, 60);
            BufferedImage tinted = HxDarkMode1102Cases.shot(c, "list-tinthud");
            tab(c, false);
            int w = base.getWidth(), h = base.getHeight();
            long white = 0, kept = 0;
            for (int y = h / 100; y < h * 14 / 100; y++) {
                for (int x = w * 30 / 100; x < w * 70 / 100; x++) {
                    if (HxDarkMode1102Cases.lum(base.getRGB(x, y)) >= 700) {
                        white++;
                        if (HxDarkMode1102Cases.lum(tinted.getRGB(x, y)) >= 250) {
                            kept++;
                        }
                    }
                }
            }
            c.note("player list white pixels " + white + ", still bright under the over-HUD tint " + kept);
            c.check(white > 40, "the player list never showed (" + white + " white pixels in the top band)");
            c.check(kept * 10 >= white * 8, "the player list vanished under Dark Mode: " + kept + " of " + white
                    + " white pixels survived the tint");
        } finally {
            tab(c, false);
            HxDarkMode1102Cases.restore(c, saved);
        }
    }

    private static boolean hidden(boolean shared, long now) {
        return (Boolean) Mod.staticCall(FEATURE, "listHidden", shared, now);
    }

    /** The whole sequence runs in ONE client task: the real HUD layer asks the same rule every frame with the real,
     *  unheld key, and a frame in between would reset the held state. */
    private static void hold(Session c) throws Exception {
        HxKit.hub(c);
        boolean[] r = c.onClient(mc -> {
            hidden(false, 0L);
            boolean[] o = new boolean[11];
            o[0] = hidden(false, 100);
            o[1] = hidden(true, 1000);
            o[2] = hidden(true, 1150);
            o[3] = hidden(true, 1299);
            o[4] = hidden(true, 1300);
            o[5] = hidden(true, 2500);
            o[6] = hidden(false, 2600);
            o[7] = hidden(true, 5000);
            // Hypixel's own cooldown line while the key is down: nothing was cast, so the list shows.
            Mod.staticCall("util.ChatObserver", "onChatComponentAdd", net.minecraft.network.chat.Component.literal(
                    "This ability is on cooldown for 7s more."));
            o[8] = hidden(true, 5060);
            o[9] = hidden(false, 5100);
            o[10] = hidden(true, 9000);
            return o;
        });
        c.check(!r[0], "an unshared key hid the list");
        c.check(r[1], "a tap on the shared key showed the list at once");
        c.check(r[2], "the list showed 150 ms into a tap");
        c.check(r[3], "the list showed 299 ms into a hold");
        c.check(!r[4], "the list stayed hidden after a 300 ms hold");
        c.check(!r[5], "a long hold hid the list");
        c.check(!r[6], "the key was released and the list was still hidden");
        c.check(r[7], "a second tap showed the list (the hold clock did not restart)");
        c.check(!r[8], "the cooldown line did not bring the list back");
        c.check(!r[9], "released: still hidden");
        c.check(r[10], "after a release the next tap did not hide the list again");
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
