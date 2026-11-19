package dev.testkit.gametest.menu;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;

/**
 * killer560, 2026-11-18: "for leaporder it always shows my class as mage even if i click a different class as my
 * selection ... it should only change what class I am playing and the leap menu if i leave the party". Mod branch
 * leaporder-class-1118: a class card click on page 1 is a pick on your own name (it used to set only the editor's last
 * class, so Hypixel's last "You have selected the Mage Dungeon Class!" kept winning), it survives a teammate joining,
 * a dungeon round trip, a config reload and a reopened editor, and it (with every pin) resets only when you leave the party.
 */
final class LeapOwnClass1118Cases {

    private static final String LEAPCFG = "leapmenu.LeapMenuConfig";
    private static final String OVR = "dungeonclass.ClassOverrides";

    private LeapOwnClass1118Cases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1118-menu-leaporder-ownclass-sticks", c -> sticks(c));
        MenuSuite.test(s, "1118-menu-leaporder-ownclass-leave-line", c -> leave(c, "You left the party."));
        MenuSuite.test(s, "1118-menu-leaporder-ownclass-kicked", c -> leave(c, "You have been kicked from the party by LrA"));
    }

    private static String selfPick(Session c) {
        return c.onClient(mc -> String.valueOf(Mod.staticCall(OVR, "classOf", mc.player.getGameProfile().name(), null)));
    }

    private static boolean inParty(Session c) {
        return c.onClient(mc -> (Boolean) Mod.staticCall("leapmenu.PartyTracker", "inParty"));
    }

    /** In a party, Hypixel last said Mage, then the editor's Archer card is clicked. */
    private static void join(Session c) {
        c.hx().chat("You have joined LrA's party!");
        c.waitUntil("the party to be known", mc -> (Boolean) Mod.staticCall("leapmenu.PartyTracker", "inParty"), 60);
        c.hx().chat("You have selected the Mage Dungeon Class!");
        c.waitUntil("Mage to read as your class", mc ->
                "MAGE".equals(String.valueOf(Mod.staticCall("leapmenu.LeapMenuFeature", "playingClass"))), 60);
    }

    private static void clickArcher(Session c) {
        Screen s = LeapEditorCases.openEditor(c);
        LeapEditorCases.press(c, s, "Archer");
    }

    static void sticks(Session c) throws Exception {
        LeapRunMemoryCases.Saved sv = LeapRunMemoryCases.setUp(c);
        try {
            join(c);
            clickArcher(c);
            String now = LeapPartyChangeCases.playing(c);
            c.check(now.equals("ARCHER"), "clicked Archer, the leap order plays " + now + " (Hypixel's Mage line won)");
            c.check(LeapPartyChangeCases.selfClass(c).equals("ARCHER"), "selfClass() is " + LeapPartyChangeCases.selfClass(c));
            // A hand-set slot, then a teammate joins and others' classes arrive: auto-fill works, yours and the slot stay.
            c.onClient(mc -> {
                Mod.call(Mod.cfg(LEAPCFG), "pinAt", Mod.enumValue("dungeonclass.DungeonClass", "ARCHER"), "LrB", 0);
                Mod.call(Mod.cfg(LEAPCFG), "save");
                return null;
            });
            c.hx().chat("[MVP+] LrC joined the party.");
            c.waitUntil("LrC to be a teammate", mc ->
                    ((List<?>) Mod.staticCall("leapmenu.PartyTracker", "teammates")).contains("LrC"), 60);
            c.hx().chat("LrC selected the Healer Class!");
            c.hx().chat("Party Finder > LrB set their class to Tank Class Level 50!");
            // A dungeon round trip with the tab list naming you Mage, and a config reload.
            DungeonMenuCases.inDungeon(c);
            LeapRunMemoryCases.tab(c, LeapRunMemoryCases.P);
            c.ctx().waitTicks(25);
            String inRun = LeapPartyChangeCases.playing(c);
            DungeonMenuCases.leaveDungeon(c);
            c.onClient(mc -> {
                Mod.staticCall(LEAPCFG, "load");
                return null;
            });
            String reloaded = LeapPartyChangeCases.playing(c);
            boolean pinKept = c.onClient(mc -> String.valueOf(Mod.call(Mod.cfg(LEAPCFG), "getClassOrder",
                    Mod.enumValue("dungeonclass.DungeonClass", "ARCHER"))).contains("LrB"));
            clickArcherReopenCheck(c);
            c.check(inRun.equals("ARCHER"), "in a dungeon round trip the leap order plays " + inRun);
            c.check(reloaded.equals("ARCHER"), "after a config reload the leap order plays " + reloaded);
            c.check(pinKept, "the hand-set slot did not survive auto-fill");
            c.note("own class Archer kept: now " + now + ", in run " + inRun + ", reloaded " + reloaded);
        } finally {
            LeapRunMemoryCases.restore(c, sv);
        }
    }

    private static void clickArcherReopenCheck(Session c) {
        Screen s = LeapEditorCases.openEditor(c);
        String lit = LeapPartyChangeCases.playing(c);
        c.check(lit.equals("ARCHER"), "the reopened editor reads the class as " + lit);
        c.onClient(mc -> {
            dev.testkit.compat.McCompat.setScreen(mc, null);
            return null;
        });
    }

    static void leave(Session c, String line) throws Exception {
        LeapRunMemoryCases.Saved sv = LeapRunMemoryCases.setUp(c);
        try {
            join(c);
            clickArcher(c);
            c.onClient(mc -> {
                Mod.call(Mod.cfg(LEAPCFG), "pinAt", Mod.enumValue("dungeonclass.DungeonClass", "ARCHER"), "LrB", 0);
                Mod.call(Mod.cfg(LEAPCFG), "save");
                return null;
            });
            c.check(LeapPartyChangeCases.playing(c).equals("ARCHER"), "setup: not Archer");
            c.hx().chat(line);
            c.waitUntil("the party to end", mc -> !(Boolean) Mod.staticCall("leapmenu.PartyTracker", "inParty"), 60);
            boolean has = c.onClient(mc -> (Boolean) Mod.staticCall(OVR, "has", mc.player.getGameProfile().name()));
            boolean pinned = c.onClient(mc -> String.valueOf(Mod.call(Mod.cfg(LEAPCFG), "getClassOrder",
                    Mod.enumValue("dungeonclass.DungeonClass", "ARCHER"))).contains("LrB"));
            String after = LeapPartyChangeCases.playing(c);
            c.check(!has, "'" + line + "' left your class pick in place");
            c.check(!pinned, "'" + line + "' left the layout pins in place");
            c.check(after.equals("MAGE"), "after '" + line + "' the leap order plays " + after + " (Hypixel's last line says MAGE)");
            c.note("left via '" + line + "': pick gone " + !has + ", pins gone " + !pinned + ", plays " + after);
        } finally {
            LeapRunMemoryCases.restore(c, sv);
        }
    }
}
