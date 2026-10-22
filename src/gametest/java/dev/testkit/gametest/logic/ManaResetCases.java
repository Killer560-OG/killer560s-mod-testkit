package dev.testkit.gametest.logic;

import dev.testkit.gametest.mod.Mod;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.network.chat.Component;

/**
 * 2776-logic-mana-world-reset (mod sweep-fix-1021, item 1): Auto Routes and Auto Clear hold an etherwarp while
 * {@code PlayerStatsFeature.manaTooLowForAotv()} is true (the action bar read NOT ENOUGH MANA, or its last mana figure
 * was under 50). Nothing reset that reading, so leaving Hypixel low on mana held every etherwarp in the next world.
 * The mod now clears it whenever {@code client.level} changes. This feeds real action-bar lines through the mod's own
 * reader and goes through two real world changes (title screen to a singleplayer world, and back), each of which must
 * clear the hold. Positive control: the same lines hold the etherwarp in the world they were read in.
 */
final class ManaResetCases {

    static ClientGameTestContext ctx;

    private static final String FEATURE = "playerstats.PlayerStatsFeature";
    private static final String NOT_ENOUGH = "§c§lNOT ENOUGH MANA";
    /** A mana figure under the 50 an Aspect of the Void costs, with the classic quill icon the reader accepts. */
    private static final String LOW_FIGURE = "§c900/900❤     §b20/500✎ Mana";

    private ManaResetCases() {
    }

    private static boolean held() {
        return ctx.computeOnClient(mc -> (Boolean) Mod.staticCall(FEATURE, "manaTooLowForAotv"));
    }

    private static long manaNow() {
        return ctx.computeOnClient(mc -> (Long) Mod.staticCall(FEATURE, "manaNow"));
    }

    private static void actionBar(String text) {
        ctx.runOnClient(mc -> Mod.staticCall(FEATURE, "onActionBar", (Object) Component.literal(text)));
    }

    static void worldReset(LogicCase c) {
        // At the title screen (no world): the reading is taken as it would be on the way out of Hypixel.
        actionBar(NOT_ENOUGH);
        c.check("control: NOT ENOUGH MANA holds an etherwarp", held(), "manaNow " + manaNow());
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            ctx.waitTicks(3);
            c.check("a new world clears the hold", !held(), "manaNow " + manaNow());
            c.eq("a new world starts with no mana figure", -1L, manaNow());
            actionBar(LOW_FIGURE);
            c.check("control: a figure under 50 holds an etherwarp in this world", held(), "manaNow " + manaNow());
            c.eq("control: the figure was read", 20L, manaNow());
            ctx.waitTicks(3);
            c.check("control: the hold stays while the world does", held(), "manaNow " + manaNow());
            actionBar(NOT_ENOUGH);
        }
        ctx.waitTicks(3);
        c.check("leaving the world clears the hold", !held(), "manaNow " + manaNow());
        c.eq("leaving the world forgets the figure", -1L, manaNow());
        c.note("NOT ENOUGH MANA and a 20/500 figure each held the etherwarp in their own world; both world changes cleared it");
    }
}
