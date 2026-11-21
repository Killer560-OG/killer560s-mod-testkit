package dev.testkit.gametest.logic;

import dev.testkit.gametest.mod.Mod;

/**
 * 1120-logic-magicfind-plus: Hypixel's drop line now reads "(+175 Magic Find)" (plus sign, no percent), which the tracker's
 * regex missed, so the Luck counter used 0% for every kill (found in the 11-20 run). Feeds the real lines through the
 * tracker; a player's chat line with the same text must still change nothing.
 */
final class MagicFindCases {

    private static final String TRACKER = "rngmeter.MagicFindTracker";

    private MagicFindCases() {
    }

    private static Object feed(String line) {
        Mod.setField(TRACKER, "lastMagicFind", null);
        Mod.staticCall(TRACKER, "onMessage", line);
        return Mod.staticCall(TRACKER, "getLastMagicFind");
    }

    static void plus(LogicCase c) {
        c.eq("'+175 Magic Find' (the 11-20 line)", 175, feed("RARE DROP! Wither Essence (+175 Magic Find)"));
        c.eq("'+175 ✯ Magic Find' with the glyph", 175, feed("RARE DROP! Wither Essence (+175 ✯ Magic Find)"));
        c.eq("'175 Magic Find' with neither sign", 175, feed("RARE DROP! Wither Essence (175 Magic Find)"));
        c.eq("the old '164% ✯ Magic Find' still reads", 164, feed("RARE DROP! HxTestItem (164% ✯ Magic Find)"));
        c.eq("'+164.5%' rounds", 165, feed("RARE DROP! HxTestItem (+164.5% Magic Find)"));
        c.eq("a player's chat line with the same text changes nothing", null,
                feed("[MVP+] Someone: RARE DROP! Wither Essence (+99999 Magic Find)"));
    }

    /** 1120-logic-maxor-respawn: the Tick Timer Audit measured the tower crystals 6 and 7 ticks after the 34-tick timer ended, twice. */
    static void maxorRespawn(LogicCase c) {
        c.eq("Maxor crystal respawn timer is 40 server ticks", 40, Mod.field("maxor.MaxorCrystalsFeature", "RESPAWN_TICKS"));
    }
}
