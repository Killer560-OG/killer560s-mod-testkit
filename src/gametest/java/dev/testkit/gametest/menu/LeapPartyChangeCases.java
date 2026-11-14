package dev.testkit.gametest.menu;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static dev.testkit.gametest.menu.LeapRunMemoryCases.MORT;
import static dev.testkit.gametest.menu.LeapRunMemoryCases.P;
import static dev.testkit.gametest.menu.LeapRunMemoryCases.TAB_CLASS;

/**
 * killer560, 2026-11-02: "sometimes I'll do a run and someone leaves then a new person joins. then the class that they are
 * playing still gets picked up properly but they will get moved to the wrong position on the menu and swapped with
 * someone else. sometimes it'll just detect what class I am playing wrong as well." Reset All (every pick and pin) fixed
 * it. Mod branch leaporder-1102 ({@code leapmenu/LeapRunMemory}, {@code PartyTracker}, {@code LeapMenuFeature.playingClass}).
 * <p>
 * Unlike 829-834 and 2345-2347, every run here goes back to the Hub between runs (sidebar gone, then the next dungeon), as
 * a real requeue does: the mod forgets the last run's tab-list classes there, so the departed teammate has NO class at the
 * next run start. The older cases stayed in one dungeon, kept the departed's class, and so never saw the bug.
 * <ul>
 *   <li>1102-menu-leaporder-classchange: his log of 2026-10-31 23:55. A Healer leaves, a Tank joins, a stayer goes Tank to
 *       Healer. The newcomer used to be pinned into the departed's spot (the Healer's corner) and the stayer pushed out;
 *       now everyone is where the Default Order puts their class.</li>
 *   <li>1102-menu-leaporder-tieswap: a clean one-for-one swap of the Tank, with a Mage also wanting bottom-right. The
 *       departed Tank used to read as standing top-left (laid out with no class), so the new Tank was pinned top-left and
 *       the Mage moved; now the new Tank takes the Tank's corner and nobody else moves.</li>
 *   <li>1102-menu-leaporder-stalepick: a swap's newcomer gets the leaver's class as a pick; when they and a stayer switch
 *       class the next run, the pick masked the change and held them as the old class in the old spot.</li>
 *   <li>1102-menu-leaporder-midrun: one leaves and one joins DURING a run (the tab list changes), then the next run starts
 *       with that party: nobody who stayed moves, the newcomer takes the leaver's spot. On main the Default Order alone moved the Mage stayer for the
 *       Archer newcomer mid-run.</li>
 *   <li>1102-menu-leaporder-ownpick: a class pick on your own name (the removed Class Selection Overlay could set one, no
 *       run start cleared it) no longer wins over the tab list's class for you.</li>
 *   <li>1102-menu-leaporder-ownselected: in the Hub, and in a dungeon before the tab list names you, your class is the one
 *       Hypixel last said you selected, not the class last opened in the editor; forged lines change nothing.</li>
 * </ul>
 */
final class LeapPartyChangeCases {

    private static final String LEAPCFG = "leapmenu.LeapMenuConfig";
    private static final String OVR = "dungeonclass.ClassOverrides";

    private LeapPartyChangeCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1102-menu-leaporder-classchange", LeapPartyChangeCases::classChange);
        MenuSuite.test(s, "1102-menu-leaporder-tieswap", LeapPartyChangeCases::tieSwap);
        MenuSuite.test(s, "1102-menu-leaporder-stalepick", LeapPartyChangeCases::stalePick);
        MenuSuite.test(s, "1102-menu-leaporder-midrun", LeapPartyChangeCases::midRun);
        MenuSuite.test(s, "1102-menu-leaporder-ownpick", LeapPartyChangeCases::ownPick);
        MenuSuite.test(s, "1102-menu-leaporder-ownselected", LeapPartyChangeCases::ownSelected);
        MenuSuite.test(s, "1110-party-left-in-dungeon", LeapPartyChangeCases::leftInDungeon);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------

    /** The Hub between runs: out of the dungeon until the mod has dropped the last run's tab-list classes, then back in. */
    static void requeue(Session c) {
        c.hx().tabClear();
        DungeonMenuCases.leaveDungeon(c);
        c.waitUntil("the last run's tab-list classes to be forgotten", mc ->
                Mod.staticCall("leapmenu.PartyTracker", "detectedClassOf", "LrA") == null, 100);
        DungeonMenuCases.inDungeon(c);
    }

    /** The Default Order's layout for the live party with nobody pinned (what the menu should show). */
    @SuppressWarnings("unchecked")
    static List<String> plain(Session c) {
        return c.onClient(mc -> {
            java.util.function.Function<String, Object> f = n -> Mod.staticCall("leapmenu.PartyTracker", "classOf", n);
            String[] l = (String[]) Mod.staticCall("leapmenu.LeapMenuFeature", "layout",
                    Mod.staticCall("leapmenu.PartyTracker", "teammatesInLeapOrder"), List.of("", "", "", ""),
                    Mod.enumValue("leapmenu.LeapOrderMode", "ODIN"), f);
            List<String> r = new ArrayList<>();
            for (String n : l) {
                r.add(n == null ? "" : n);
            }
            return r;
        });
    }

    static Map<String, String> classes(Map<String, String> changes) {
        Map<String, String> m = new HashMap<>(TAB_CLASS);
        m.putAll(changes);
        return m;
    }

    static String selfName(Session c) {
        return c.onClient(mc -> mc.player.getGameProfile().name());
    }

    static String playing(Session c) {
        return c.onClient(mc -> String.valueOf(Mod.staticCall("leapmenu.LeapMenuFeature", "playingClass")));
    }

    static String selfClass(Session c) {
        return c.onClient(mc -> String.valueOf(Mod.staticCall("leapmenu.PartyTracker", "selfClass")));
    }

    // ---- 1110-party-left-in-dungeon -------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static List<String> mates() {
        return (List<String>) Mod.staticCall("leapmenu.PartyTracker", "teammates");
    }

    /** killer560, 2026-11-10: "[MVP+] leafycofhe has left the party." arrived mid-run, Hypixel's dungeon tab kept the name
     *  until the run ended, and the tab read put him straight back (Custom Scoreboard "Party (4)"). The tab keeps listing
     *  him here on purpose; a later tab read (LrA's class changes) proves it ran after the leave line. */
    static void leftInDungeon(Session c) throws Exception {
        DungeonMenuCases.inDungeon(c);
        try {
            LeapRunMemoryCases.tab(c, LeapRunMemoryCases.P);
            c.hx().chat("[MVP+] LrD has left the party.");
            c.waitUntil("LrD to leave the party", mc -> !mates().contains("LrD"), 100);
            // The tab still lists LrD, and LrA's class changes so a read after the leave is provable.
            String self = selfName(c);
            c.hx().tab(List.of("[50] " + self + " (Archer L)", "[50] LrA (Healer L)", "[50] LrB (Healer L)",
                    "[50] LrC (Healer L)", "[50] LrD (Healer L)"), null, null);
            c.waitUntil("a tab read after the leave line", mc ->
                    "HEALER".equals(String.valueOf(Mod.staticCall("leapmenu.PartyTracker", "detectedClassOf", "LrC"))), 100);
            List<String> now = c.onClient(mc -> mates());
            c.check(!now.contains("LrD") && now.size() == 3, "LrD is back from the tab list: " + now);
            DungeonMenuCases.leaveDungeon(c);
            c.waitUntil("the dungeon to be left", mc -> Mod.staticCall("leapmenu.PartyTracker", "detectedClassOf", "LrC") == null, 100);
            c.check(!c.onClient(mc -> mates()).contains("LrD"), "LrD is back after the dungeon: " + c.onClient(mc -> mates()));
            c.hx().chat("[MVP+] LrD joined the party.");
            c.waitUntil("LrD to rejoin", mc -> mates().contains("LrD"), 100);
        } finally {
            c.hx().tabClear();
            DungeonMenuCases.leaveDungeon(c);
        }
    }

    // ---- 1102-menu-leaporder-classchange --------------------------------------------------------------------------------

    /** LrC (Healer) out, LrG (Tank) in, LrB Tank -> Healer: everyone at their class's Default Order spot. */
    static void classChange(Session c) throws Exception {
        LeapRunMemoryCases.Saved sv = LeapRunMemoryCases.setUp(c);
        try {
            LeapRunMemoryCases.run(c, P);
            List<String> first = LeapRunMemoryCases.layout(c);
            requeue(c);
            List<String> next = List.of("LrA", "LrB", "LrD", "LrG");
            String out = LeapRunMemoryCases.run(c, next, classes(Map.of("LrB", "Healer")));
            List<String> after = LeapRunMemoryCases.layout(c);
            List<String> want = plain(c);
            c.check(LeapRunMemoryCases.cls(c, "LrB").equals("HEALER") && LeapRunMemoryCases.cls(c, "LrG").equals("TANK"),
                    "setup: LrB " + LeapRunMemoryCases.cls(c, "LrB") + ", LrG " + LeapRunMemoryCases.cls(c, "LrG"));
            c.check(after.equals(want), "layout " + after + ", want the Default Order " + want + " (first run " + first
                    + "; " + out + ")");
            c.check(!LeapRunMemoryCases.pins(c).contains("LrG"), "LrG was pinned: " + LeapRunMemoryCases.pins(c) + " (" + out + ")");
            c.check(out.contains("reset notice"), "no reset notice: " + out);
            c.note("class change + newcomer: first " + first + " -> " + after + " (Default Order " + want + "); " + out);
        } finally {
            LeapRunMemoryCases.restore(c, sv);
        }
    }

    // ---- 1102-menu-leaporder-tieswap ----------------------------------------------------------------------------------

    /** LrB (Tank, bottom-right over the Mage LrA) out, LrG (Tank) in: LrG takes bottom-right, LrA/LrC/LrD stay. */
    static void tieSwap(Session c) throws Exception {
        LeapRunMemoryCases.Saved sv = LeapRunMemoryCases.setUp(c);
        try {
            LeapRunMemoryCases.run(c, P);
            List<String> before = LeapRunMemoryCases.layout(c);
            int bSpot = before.indexOf("LrB");
            requeue(c);
            String out = LeapRunMemoryCases.run(c, List.of("LrA", "LrC", "LrD", "LrG"));
            List<String> after = LeapRunMemoryCases.layout(c);
            c.check(bSpot >= 0 && "LrG".equals(after.get(bSpot)), "LrG is in spot " + after.indexOf("LrG") + ", LrB's was "
                    + bSpot + ": " + before + " -> " + after + " (" + out + ")");
            for (String n : List.of("LrA", "LrC", "LrD")) {
                c.check(after.indexOf(n) == before.indexOf(n), n + " moved: " + before + " -> " + after + " (" + out + ")");
            }
            c.check(out.contains("swap") && !out.contains("reset notice"), "not a clean swap: " + out);
            c.note("tie swap: " + before + " -> " + after + "; " + out);
        } finally {
            LeapRunMemoryCases.restore(c, sv);
        }
    }

    // ---- 1102-menu-leaporder-stalepick --------------------------------------------------------------------------------

    /** Swap LrD (Berserk) for LrE (Archer, given BERSERKER); next run LrE is a Healer and LrC a Berserk on the tab list. */
    @SuppressWarnings("unchecked")
    static void stalePick(Session c) throws Exception {
        LeapRunMemoryCases.Saved sv = LeapRunMemoryCases.setUp(c);
        try {
            LeapRunMemoryCases.run(c, P);
            requeue(c);
            List<String> next = List.of("LrA", "LrB", "LrC", "LrE");
            String swapOut = LeapRunMemoryCases.run(c, next);
            c.check("BERSERKER".equals(LeapRunMemoryCases.overrides(c).get("LrE")),
                    "setup: the swap did not give LrE BERSERKER: " + LeapRunMemoryCases.overrides(c) + " (" + swapOut + ")");
            requeue(c);
            String out = LeapRunMemoryCases.run(c, next, classes(Map.of("LrE", "Healer", "LrC", "Berserk")));
            List<String> after = LeapRunMemoryCases.layout(c);
            List<String> want = plain(c);
            c.check(LeapRunMemoryCases.cls(c, "LrE").equals("HEALER"), "LrE reads as " + LeapRunMemoryCases.cls(c, "LrE")
                    + " (picks " + LeapRunMemoryCases.overrides(c) + "), the tab list says HEALER (" + out + ")");
            c.check(!LeapRunMemoryCases.pins(c).contains("LrE"), "LrE is still pinned: " + LeapRunMemoryCases.pins(c));
            c.check(after.equals(want), "layout " + after + ", want the Default Order " + want + " (" + out + ")");
            c.note("stale swap pick: " + swapOut + " | next run: " + after + "; " + out);
        } finally {
            LeapRunMemoryCases.restore(c, sv);
        }
    }

    // ---- 1102-menu-leaporder-midrun -----------------------------------------------------------------------------------

    /** LrD leaves and LrE joins during the run; the leap menu then, and the next run's start. */
    static void midRun(Session c) throws Exception {
        LeapRunMemoryCases.Saved sv = LeapRunMemoryCases.setUp(c);
        try {
            LeapRunMemoryCases.run(c, P);
            List<String> before = LeapRunMemoryCases.layout(c);
            int dSpot = before.indexOf("LrD");
            List<String> next = List.of("LrA", "LrB", "LrC", "LrE");
            LeapRunMemoryCases.tab(c, next); // the tab list changes mid-run; no Mort line
            c.waitUntil("the mid-run party change to be applied", mc -> String.valueOf(
                    Mod.staticCall("leapmenu.LeapRunMemory", "lastMidRunOutcome")).contains("joined [LrE]"), 100);
            String mid = c.onClient(mc -> String.valueOf(Mod.staticCall("leapmenu.LeapRunMemory", "lastMidRunOutcome")));
            c.note(mid);
            List<String> during = LeapRunMemoryCases.layout(c);
            for (String n : List.of("LrA", "LrB", "LrC")) {
                c.check(during.indexOf(n) == before.indexOf(n), n + " moved mid-run: " + before + " -> " + during);
            }
            c.check("LrE".equals(during.get(dSpot)), "mid-run LrE is not in LrD's spot " + dSpot + ": " + during + " (" + mid + ")");
            requeue(c);
            String out = LeapRunMemoryCases.run(c, next);
            List<String> after = LeapRunMemoryCases.layout(c);
            c.check("LrE".equals(after.get(dSpot)), "LrE is not in LrD's spot " + dSpot + ": " + after + " (" + out + ")");
            for (String n : List.of("LrA", "LrB", "LrC")) {
                c.check(after.indexOf(n) == before.indexOf(n), n + " moved: " + before + " -> " + after + " (" + out + ")");
            }
            c.note("mid-run replacement: " + before + " -> during " + during + " -> next run " + after + "; " + out);
        } finally {
            LeapRunMemoryCases.restore(c, sv);
        }
    }

    // ---- 1102-menu-leaporder-ownpick ----------------------------------------------------------------------------------

    /** A pick of MAGE on your own name while the tab list shows you as Archer: the pick wins (killer560 11-13). */
    static void ownPick(Session c) throws Exception {
        LeapRunMemoryCases.Saved sv = LeapRunMemoryCases.setUp(c);
        try {
            String self = selfName(c);
            c.onClient(mc -> Mod.staticCall(OVR, "set", self, Mod.enumValue("dungeonclass.DungeonClass", "MAGE")));
            List<String> entries = new ArrayList<>();
            entries.add("[50] " + self + " (Archer L)");
            for (String n : P) {
                entries.add("[50] " + n + " (" + TAB_CLASS.get(n) + " L)");
            }
            c.hx().tab(entries, null, null);
            c.waitUntil("the tab list to name you", mc ->
                    Mod.staticCall("leapmenu.PartyTracker", "detectedClassOf", self) != null, 100);
            String sc = selfClass(c);
            String pc = playing(c);
            boolean hasPick = c.onClient(mc -> (Boolean) Mod.staticCall(OVR, "has", self));
            c.check(hasPick, "setup: the pick on " + self + " is not there");
            c.check(sc.equals("MAGE"), "selfClass() is " + sc + " with a MAGE pick on you (tab list ARCHER); the pick must win");
            c.check(pc.equals("MAGE"), "playingClass() is " + pc + " with a MAGE pick on you (tab list ARCHER); the pick must win");
            c.note("own pick MAGE, tab Archer: selfClass " + sc + ", playing " + pc);
        } finally {
            c.onClient(mc -> Mod.staticCall(OVR, "clear", mc.player.getGameProfile().name()));
            LeapRunMemoryCases.restore(c, sv);
        }
    }

    // ---- 1102-menu-leaporder-ownselected ------------------------------------------------------------------------------

    /** Last run Archer; in the Hub Hypixel says Tank; the next dungeon's tab list is late to name you. */
    static void ownSelected(Session c) throws Exception {
        LeapRunMemoryCases.Saved sv = LeapRunMemoryCases.setUp(c);
        try {
            String self = selfName(c);
            c.onClient(mc -> {
                Mod.call(Mod.cfg(LEAPCFG), "setLastEditedClass", LeapRunMemoryCases.archer());
                return null;
            });
            LeapRunMemoryCases.run(c, P); // you are Archer on the tab list
            c.hx().tabClear();
            DungeonMenuCases.leaveDungeon(c);
            c.waitUntil("your last run's class to be forgotten", mc ->
                    Mod.staticCall("leapmenu.PartyTracker", "detectedClassOf", self) == null, 100);
            // Forged: a player's chat holding the line, and another player's broadcast.
            c.hx().chat("[MVP+] Bob: You have selected the Mage Dungeon Class!");
            c.hx().chat("Party > [MVP+] Bob: You have selected the Mage Dungeon Class!");
            c.hx().chat("LrA selected the Mage Class!");
            c.ctx().waitTicks(10);
            String forged = playing(c);
            c.check(!forged.equals("MAGE"), "a forged or another player's line made you a Mage: " + forged);
            c.hx().chat("You have selected the Tank Dungeon Class!");
            c.waitUntil("playingClass() to follow Hypixel's line", mc ->
                    "TANK".equals(String.valueOf(Mod.staticCall("leapmenu.LeapMenuFeature", "playingClass"))), 60);
            String hub = playing(c);
            // The next dungeon: the tab list shows the teammates but not you yet.
            DungeonMenuCases.inDungeon(c);
            List<String> entries = new ArrayList<>();
            for (String n : P) {
                entries.add("[50] " + n + " (" + TAB_CLASS.get(n) + " L)");
            }
            c.hx().tab(entries, null, null);
            c.waitUntil("the teammates on the tab list", mc ->
                    Mod.staticCall("leapmenu.PartyTracker", "detectedClassOf", "LrA") != null, 100);
            String late = playing(c);
            int runs = LeapRunMemoryCases.runs(c);
            c.hx().chat(MORT);
            c.waitUntil("the run start to be processed", mc -> LeapRunMemoryCases.runs(mc) > runs, 100);
            String out = c.onClient(mc -> String.valueOf(Mod.staticCall("leapmenu.LeapRunMemory", "lastOutcome")));
            // A restart keeps it (Hypixel keeps your class too).
            String reloaded = c.onClient(mc -> {
                Mod.staticCall(LEAPCFG, "load");
                return String.valueOf(Mod.call(Mod.cfg(LEAPCFG), "getLastSelectedClass"));
            });
            c.check(hub.equals("TANK"), "in the Hub after 'You have selected the Tank Dungeon Class!' you read as " + hub);
            c.check(late.equals("TANK"), "in the dungeon before the tab list names you, you read as " + late);
            c.check(out.contains("playing TANK"), "the run start used another class: " + out);
            c.check(reloaded.equals("TANK"), "after a reload the selected class is " + reloaded);
            c.note("own class: forged lines -> " + forged + ", Hub " + hub + ", late tab " + late + ", reload " + reloaded
                    + "; " + out);
        } finally {
            LeapRunMemoryCases.restore(c, sv);
        }
    }
}
