package dev.testkit.gametest.menu;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * How long class picks and Leap Order pins last (killer560, 2026-10-12; mod branch leap-3, {@code leapmenu/LeapRunMemory}).
 * Each "run" is real: the dungeon sidebar, the dungeon tab list (the party with their classes, him as Archer) and
 * Mort's opening line, all from the Hx server; the mod reads the party itself. Party A-D is LrA Mage, LrB Tank, LrC
 * Healer, LrD Berserk; newcomers are LrE Archer, LrF Mage, LrG Tank. Default Order Odin.
 * <ul>
 *   <li>829: the same party twice: picks and pins all kept.</li>
 *   <li>830: one swap (LrD out, LrE in): LrE is pinned in LrD's old spot, LrA-LrC are where they were, LrD's pick is gone,
 *       and LrE has it instead (since 2026-10-23 the newcomer takes the leaver's class).</li>
 *   <li>2345-2347 (2026-10-23): a clean one-for-one swap gives the newcomer the leaver's tab-list class with no notice;
 *       two leaving, or a stayer's class changing, shows the "Leap Order was reset" notice and runs the old rules.</li>
 *   <li>831: a run without LrD: LrD's pick and pin are cleared, LrA's pick and pin kept.</li>
 *   <li>832: three leave: every pick and pin reset, the layout is the plain Default Order.</li>
 *   <li>833: two leave (LrC, LrD out; LrE, LrF in): the vacated spots in spot order go to LrE then LrF (leap-menu order).</li>
 *   <li>834: guards: the previous party survives a reload; Mort's line typed by a player (all chat and party chat) starts
 *       nothing; a run in the room sim changes nothing.</li>
 * </ul>
 */
final class LeapRunMemoryCases {

    private static final String LEAPCFG = "leapmenu.LeapMenuConfig";
    private static final String MEM = "leapmenu.LeapRunMemory";
    private static final String OVR = "dungeonclass.ClassOverrides";
    static final String MORT = "[NPC] Mort: Here, I found this map when I first entered the dungeon.";
    static final List<String> P = List.of("LrA", "LrB", "LrC", "LrD");
    static final Map<String, String> TAB_CLASS = Map.of("LrA", "Mage", "LrB", "Tank", "LrC", "Healer", "LrD", "Berserk",
            "LrE", "Archer", "LrF", "Mage", "LrG", "Tank");

    private LeapRunMemoryCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "829-menu-leap-memory-same-party", LeapRunMemoryCases::sameParty);
        MenuSuite.test(s, "830-menu-leap-memory-one-swap", LeapRunMemoryCases::oneSwap);
        MenuSuite.test(s, "831-menu-leap-memory-run-without", LeapRunMemoryCases::runWithout);
        MenuSuite.test(s, "832-menu-leap-memory-three-leave", LeapRunMemoryCases::threeLeave);
        MenuSuite.test(s, "833-menu-leap-memory-two-leave", LeapRunMemoryCases::twoLeave);
        MenuSuite.test(s, "834-menu-leap-memory-guards", LeapRunMemoryCases::guards);
        // killer560, 2026-10-22: picks and pins set in the Dungeon Hub before a run were reset when the run started.
        MenuSuite.test(s, "2341-menu-leap-memory-hub-same-party", LeapRunMemoryCases::hubSameParty);
        MenuSuite.test(s, "2342-menu-leap-memory-hub-new-party", LeapRunMemoryCases::hubNewParty);
        MenuSuite.test(s, "2343-menu-leap-memory-hub-two-new", LeapRunMemoryCases::hubTwoNew);
        MenuSuite.test(s, "2344-menu-leap-memory-run-pick-next-run", LeapRunMemoryCases::runPickNextRun);
        // killer560, 2026-10-23: one out, one in, nothing else changed - the newcomer takes the leaver's class; any other
        // party change shows a "Leap Order was reset" notice.
        MenuSuite.test(s, "2345-menu-leap-memory-swap-takes-class", LeapRunMemoryCases::swapTakesClass);
        MenuSuite.test(s, "2346-menu-leap-memory-two-leave-notice", LeapRunMemoryCases::twoLeaveNotice);
        MenuSuite.test(s, "2347-menu-leap-memory-class-change-notice", LeapRunMemoryCases::classChangeNotice);
    }

    // ---- fixture ------------------------------------------------------------------------------------------------------

    static final class Saved {
        Path leap;
        byte[] leapBytes;
        Path ovr;
        byte[] ovrBytes;
    }

    static Saved setUp(Session c) throws Exception {
        Saved sv = new Saved();
        sv.leap = (Path) Mod.field(LEAPCFG, "CONFIG_PATH");
        sv.leapBytes = Files.exists(sv.leap) ? Files.readAllBytes(sv.leap) : null;
        sv.ovr = (Path) Mod.field(LEAPCFG, "LEGACY_PICKS_PATH");
        sv.ovrBytes = Files.exists(sv.ovr) ? Files.readAllBytes(sv.ovr) : null;
        c.onClient(mc -> {
            Object cfg = Mod.cfg(LEAPCFG);
            Mod.call(cfg, "setLastRunParty", List.of());
            // The tab list's own order is not stable (the client's player map), so give the party a leap-menu order, as
            // a Spirit Leap menu seen once does: A..G.
            Mod.call(cfg, "setLastLeapOrder", List.of("LrA", "LrB", "LrC", "LrD", "LrE", "LrF", "LrG"));
            Mod.call(cfg, "clearAllPins");
            Mod.call(cfg, "setDefaultOrder", Mod.enumValue("leapmenu.LeapOrderMode", "ODIN"));
            Mod.call(cfg, "save");
            Mod.staticCall(OVR, "clearAll");
            return null;
        });
        DungeonMenuCases.inDungeon(c);
        return sv;
    }

    static void restore(Session c, Saved sv) {
        c.hx().tabClear();
        DungeonMenuCases.leaveDungeon(c);
        c.onClient(mc -> {
            Mod.staticCall("secrets.DungeonState", "setRoomSim", false);
            try {
                write(sv.leap, sv.leapBytes);
                write(sv.ovr, sv.ovrBytes);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            Mod.staticCall(LEAPCFG, "load");
            return null;
        });
    }

    private static void write(Path p, byte[] b) throws Exception {
        if (b != null) {
            Files.write(p, b);
        } else {
            Files.deleteIfExists(p);
        }
    }

    static Object archer() {
        return Mod.enumValue("dungeonclass.DungeonClass", "ARCHER");
    }

    /** The dungeon tab list for {@code party} (him first, as Archer), then waits until the mod's party is exactly it. */
    @SuppressWarnings("unchecked")
    static void tab(Session c, List<String> party) {
        tab(c, party, TAB_CLASS);
    }

    /** The same, with the tab list showing each teammate as {@code classes} says (a class change between runs). */
    @SuppressWarnings("unchecked")
    static void tab(Session c, List<String> party, Map<String, String> classes) {
        String self = c.onClient(mc -> mc.player.getGameProfile().name());
        List<String> entries = new ArrayList<>();
        entries.add("[50] " + self + " (Archer L)");
        for (String n : party) {
            entries.add("[50] " + n + " (" + classes.get(n) + " L)");
        }
        c.hx().tab(entries, null, null);
        try {
            c.waitUntil("the party tracker to read " + party, mc ->
                    new java.util.HashSet<>((List<String>) Mod.staticCall("leapmenu.PartyTracker", "teammates"))
                            .equals(new java.util.HashSet<>(party))
                            && "ARCHER".equals(String.valueOf(Mod.staticCall("leapmenu.LeapMenuFeature", "playingClass"))), 100);
        } catch (AssertionError e) {
            String seen = c.onClient(mc -> "teammates " + Mod.staticCall("leapmenu.PartyTracker", "teammates")
                    + ", playing " + Mod.staticCall("leapmenu.LeapMenuFeature", "playingClass") + ", in dungeon "
                    + Mod.staticCall("secrets.DungeonState", "isInDungeon") + ", tab " + mc.getConnection()
                    .getListedOnlinePlayers().stream().map(i -> i.getTabListDisplayName() == null ? i.getProfile().name()
                            : i.getTabListDisplayName().getString()).toList());
            throw new AssertionError(e.getMessage() + " - " + seen, e);
        }
    }

    /** A run: the tab list for {@code party}, then Mort's line; waits for the mod to process it. @return its outcome. */
    static String run(Session c, List<String> party) {
        return run(c, party, TAB_CLASS);
    }

    static String run(Session c, List<String> party, Map<String, String> classes) {
        tab(c, party, classes);
        int before = runs(c);
        c.hx().chat(MORT);
        c.waitUntil("the run start to be processed", mc -> runs(mc) > before, 100);
        String out = c.onClient(mc -> String.valueOf(Mod.staticCall(MEM, "lastOutcome")));
        c.note("run " + party + ": " + out);
        return out;
    }

    static int runs(Session c) {
        return c.onClient(LeapRunMemoryCases::runs);
    }

    static int runs(Object mc) {
        return ((Number) Mod.staticCall(MEM, "runsProcessed")).intValue();
    }

    static Map<String, String> overrides(Session c) {
        return c.onClient(mc -> {
            Map<String, String> out = new java.util.TreeMap<>();
            ((Map<?, ?>) Mod.staticCall(OVR, "all")).forEach((k, v) -> out.put(String.valueOf(k), String.valueOf(v)));
            return out;
        });
    }

    @SuppressWarnings("unchecked")
    static List<String> pins(Session c) {
        return c.onClient(mc -> List.copyOf((List<String>) Mod.call(Mod.cfg(LEAPCFG), "getClassOrder", archer())));
    }

    @SuppressWarnings("unchecked")
    static List<String> layout(Session c) {
        return c.onClient(mc -> {
            String[] l = (String[]) Mod.staticCall("leapmenu.LeapMenuFeature", "layout",
                    Mod.staticCall("leapmenu.PartyTracker", "teammatesInLeapOrder"), archer());
            List<String> r = new ArrayList<>();
            for (String n : l) {
                r.add(n == null ? "" : n);
            }
            return r;
        });
    }

    static void pick(Session c, String name, String cls) {
        c.onClient(mc -> Mod.staticCall(OVR, "set", name, Mod.enumValue("dungeonclass.DungeonClass", cls)));
    }

    /** A hand swap of the two spots, the way the editor makes one (both names pinned). */
    static void swap(Session c, int a, int b) {
        c.onClient(mc -> {
            Object cfg = Mod.cfg(LEAPCFG);
            String[] l = (String[]) Mod.staticCall("leapmenu.LeapMenuFeature", "layout",
                    Mod.staticCall("leapmenu.PartyTracker", "teammatesInLeapOrder"), archer());
            Mod.call(cfg, "swap", archer(), l, a, b);
            Mod.call(cfg, "save");
            return null;
        });
    }

    static String cls(Session c, String name) {
        return c.onClient(mc -> String.valueOf(Mod.staticCall("leapmenu.PartyTracker", "classOf", name)));
    }

    // ---- 829 --------------------------------------------------------------------------------------------------------

    static void sameParty(Session c) throws Exception {
        Saved sv = setUp(c);
        try {
            run(c, P);
            pick(c, "LrA", "ARCHER");
            pick(c, "LrC", "TANK");
            swap(c, 0, 3);
            Map<String, String> ovr = overrides(c);
            List<String> pins = pins(c);
            List<String> lay = layout(c);
            c.check(ovr.size() == 2 && pins.stream().filter(s -> !s.isEmpty()).count() == 2, "setup: " + ovr + " " + pins);
            String out = run(c, P);
            c.check(out.startsWith("same party"), "second run outcome: " + out);
            c.check(overrides(c).equals(ovr), "picks changed: " + ovr + " -> " + overrides(c));
            c.check(pins(c).equals(pins), "pins changed: " + pins + " -> " + pins(c));
            c.check(layout(c).equals(lay), "layout changed: " + lay + " -> " + layout(c));
            c.note("same party twice: picks " + ovr + ", pins " + pins + ", layout " + lay + " all kept");
        } finally {
            restore(c, sv);
        }
    }

    // ---- 830 --------------------------------------------------------------------------------------------------------

    static void oneSwap(Session c) throws Exception {
        Saved sv = setUp(c);
        try {
            run(c, P);
            pick(c, "LrD", "MAGE");
            List<String> before = layout(c);
            int dSpot = before.indexOf("LrD");
            List<String> next = List.of("LrA", "LrB", "LrC", "LrE");
            String out = run(c, next);
            List<String> after = layout(c);
            c.check(after.get(dSpot).equals("LrE"), "LrE is in spot " + after.indexOf("LrE") + ", LrD's old spot is " + dSpot
                    + " (before " + before + ", after " + after + ")");
            for (String n : List.of("LrA", "LrB", "LrC")) {
                c.check(after.indexOf(n) == before.indexOf(n), n + " moved: " + before + " -> " + after);
            }
            c.check(!overrides(c).containsKey("LrD"), "LrD's pick survived: " + overrides(c));
            // Since 2026-10-23 a one-for-one swap gives the newcomer the leaver's class: LrD's pick, MAGE.
            c.check("MAGE".equals(overrides(c).get("LrE")) && cls(c, "LrE").equals("MAGE"),
                    "LrE's class is " + cls(c, "LrE") + " (overrides " + overrides(c) + "), want LrD's MAGE");
            c.check(pins(c).get(dSpot).equals("LrE"), "LrE is not pinned in spot " + dSpot + ": " + pins(c));
            c.note("one swap: before " + before + " -> after " + after + "; " + out);
        } finally {
            restore(c, sv);
        }
    }

    // ---- 831 --------------------------------------------------------------------------------------------------------

    static void runWithout(Session c) throws Exception {
        Saved sv = setUp(c);
        try {
            run(c, P);
            pick(c, "LrA", "ARCHER");
            pick(c, "LrD", "TANK");
            List<String> lay = layout(c);
            swap(c, lay.indexOf("LrA"), lay.indexOf("LrD"));
            List<String> pinsBefore = pins(c);
            c.check(pinsBefore.contains("LrA") && pinsBefore.contains("LrD"), "setup pins " + pinsBefore);
            String out = run(c, List.of("LrA", "LrB", "LrC"));
            Map<String, String> ovr = overrides(c);
            List<String> pinsAfter = pins(c);
            c.check(!ovr.containsKey("LrD"), "LrD's pick survived a run without him: " + ovr);
            c.check("ARCHER".equals(ovr.get("LrA")), "LrA's pick was cleared too: " + ovr);
            c.check(!pinsAfter.contains("LrD"), "LrD's pin survived: " + pinsAfter);
            c.check(pinsAfter.indexOf("LrA") == pinsBefore.indexOf("LrA"), "LrA's pin moved or went: " + pinsBefore + " -> "
                    + pinsAfter);
            c.note("run without LrD: picks " + ovr + ", pins " + pinsBefore + " -> " + pinsAfter + "; " + out);
        } finally {
            restore(c, sv);
        }
    }

    // ---- 832 --------------------------------------------------------------------------------------------------------

    static void threeLeave(Session c) throws Exception {
        Saved sv = setUp(c);
        try {
            run(c, P);
            pick(c, "LrA", "ARCHER");
            pick(c, "LrB", "HEALER");
            swap(c, 0, 1);
            List<String> next = List.of("LrA", "LrE", "LrF", "LrG");
            String out = run(c, next);
            c.check(overrides(c).isEmpty(), "picks left after 3 left: " + overrides(c));
            c.check(pins(c).stream().allMatch(String::isEmpty), "pins left after 3 left: " + pins(c));
            List<String> lay = layout(c);
            List<String> plain = c.onClient(mc -> {
                java.util.function.Function<String, Object> f = n ->
                        Mod.staticCall("leapmenu.PartyTracker", "classOf", n);
                String[] l = (String[]) Mod.staticCall("leapmenu.LeapMenuFeature", "layout", next,
                        List.of("", "", "", ""), Mod.enumValue("leapmenu.LeapOrderMode", "ODIN"), f);
                List<String> r = new ArrayList<>();
                for (String n : l) {
                    r.add(n == null ? "" : n);
                }
                return r;
            });
            c.check(lay.equals(plain), "the layout " + lay + " is not the plain Odin order " + plain);
            c.note("three left: " + out + "; layout " + lay);
        } finally {
            restore(c, sv);
        }
    }

    // ---- 833 --------------------------------------------------------------------------------------------------------

    static void twoLeave(Session c) throws Exception {
        Saved sv = setUp(c);
        try {
            run(c, P);
            pick(c, "LrC", "ARCHER");
            List<String> before = layout(c);
            List<Integer> vacated = new ArrayList<>(List.of(before.indexOf("LrC"), before.indexOf("LrD")));
            vacated.sort(Integer::compare);
            String out = run(c, List.of("LrA", "LrB", "LrE", "LrF"));
            List<String> after = layout(c);
            c.check(after.get(vacated.get(0)).equals("LrE") && after.get(vacated.get(1)).equals("LrF"),
                    "vacated spots " + vacated + " hold " + after + ", want LrE then LrF");
            c.check(after.indexOf("LrA") == before.indexOf("LrA") && after.indexOf("LrB") == before.indexOf("LrB"),
                    "LrA/LrB moved: " + before + " -> " + after);
            c.check(!overrides(c).containsKey("LrC"), "LrC's pick survived: " + overrides(c));
            c.note("two left: before " + before + ", vacated " + vacated + " -> after " + after + "; " + out);
        } finally {
            restore(c, sv);
        }
    }

    // ---- 834 --------------------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static void guards(Session c) throws Exception {
        Saved sv = setUp(c);
        try {
            run(c, P);
            pick(c, "LrD", "TANK");
            // Persisted: a reload keeps the previous party.
            List<String> remembered = c.onClient(mc -> {
                Mod.staticCall(LEAPCFG, "load");
                return List.copyOf((List<String>) Mod.call(Mod.cfg(LEAPCFG), "getLastRunParty"));
            });
            c.check(remembered.equals(P), "after a reload the previous party is " + remembered);
            // Forged run starts: Mort's line inside a player's message, all chat and party chat.
            tab(c, List.of("LrA", "LrB", "LrC"));
            int before = runs(c);
            c.hx().chat("[MVP+] Bob: " + MORT);
            c.hx().chat("Party > [MVP+] Bob: " + MORT);
            c.ctx().waitTicks(40);
            c.check(runs(c) == before, "a player's chat started a run (" + runs(c) + " vs " + before + ")");
            c.check("TANK".equals(overrides(c).get("LrD")), "LrD's pick went on a forged line: " + overrides(c));
            // A room-sim run is not a run with a party.
            c.onClient(mc -> Mod.staticCall("secrets.DungeonState", "setRoomSim", true));
            c.hx().chat(MORT);
            c.ctx().waitTicks(40);
            String out = c.onClient(mc -> String.valueOf(Mod.staticCall(MEM, "lastOutcome")));
            c.check(runs(c) == before && out.startsWith("skipped"), "the room sim ran the rules: " + out);
            c.check("TANK".equals(overrides(c).get("LrD")), "LrD's pick went in the room sim: " + overrides(c));
            c.onClient(mc -> Mod.staticCall("secrets.DungeonState", "setRoomSim", false));
            // Control: the same line from the server, in a real run, does act.
            String real = run(c, List.of("LrA", "LrB", "LrC"));
            c.check(!overrides(c).containsKey("LrD"), "the real run start did not clear LrD: " + real);
            c.note("reload kept " + remembered + "; forged all/party chat and a sim run changed nothing; the real line: " + real);
        } finally {
            restore(c, sv);
        }
    }

    // ---- 2341-2344: picks and pins set in the Dungeon Hub (killer560, 2026-10-22) -------------------------------------

    /** Leaves the dungeon for the Hub and forms the party there from Hypixel's own party lines (you join
     *  {@code leader}'s party, "You'll be partying with" the rest); waits until the mod's party is exactly it. */
    @SuppressWarnings("unchecked")
    static void hub(Session c, String leader, List<String> others) {
        c.hx().tabClear();
        DungeonMenuCases.leaveDungeon(c);
        c.hx().chat("You left the party.");
        c.hx().chat("You have joined [MVP+] " + leader + "'s party!");
        c.hx().chat("You'll be partying with: " + String.join(", ", others));
        java.util.Set<String> want = new java.util.HashSet<>(others);
        want.add(leader);
        c.waitUntil("the Hub party to read " + want, mc ->
                new java.util.HashSet<>((List<String>) Mod.staticCall("leapmenu.PartyTracker", "teammates")).equals(want), 100);
        c.note("in the Hub with " + want);
    }

    /** Back into a dungeon for the next run (sidebar first, then {@link #run} puts up the tab list and Mort's line). */
    static void enter(Session c) {
        DungeonMenuCases.inDungeon(c);
    }

    static long pinCount(List<String> pins) {
        return pins.stream().filter(s -> !s.isEmpty()).count();
    }

    /** The party ran together last time; picks and a pin set in the Hub before the next run with the same people. */
    static void hubSameParty(Session c) throws Exception {
        Saved sv = setUp(c);
        try {
            run(c, P);
            hub(c, "LrA", List.of("LrB", "LrC", "LrD"));
            pick(c, "LrB", "MAGE");
            pick(c, "LrD", "HEALER");
            swap(c, 0, 3);
            Map<String, String> ovr = overrides(c);
            List<String> pins = pins(c);
            c.check(ovr.size() == 2 && pinCount(pins) == 2, "Hub setup: " + ovr + " " + pins);
            enter(c);
            String out = run(c, P);
            c.check(overrides(c).equals(ovr), "Hub picks changed at the run start: " + ovr + " -> " + overrides(c) + " (" + out + ")");
            c.check(pins(c).equals(pins), "Hub pins changed at the run start: " + pins + " -> " + pins(c) + " (" + out + ")");
            c.note("same party, set in the Hub: picks " + ovr + ", pins " + pins + " kept; " + out);
        } finally {
            restore(c, sv);
        }
    }

    /** A new party formed in the Hub (three of last run's teammates gone): what he set there for them is kept, while the
     *  old party's pick still goes. */
    static void hubNewParty(Session c) throws Exception {
        Saved sv = setUp(c);
        try {
            run(c, P);
            pick(c, "LrB", "MAGE"); // last run's, for someone not in the next one
            hub(c, "LrE", List.of("LrF", "LrG", "LrA"));
            pick(c, "LrE", "HEALER");
            pick(c, "LrF", "TANK");
            swap(c, 0, 1);
            Map<String, String> ovr = new java.util.TreeMap<>(overrides(c));
            ovr.remove("LrB");
            List<String> pins = pins(c);
            c.check(pinCount(pins) == 2 && ovr.size() == 2, "Hub setup: " + overrides(c) + " " + pins);
            enter(c);
            String out = run(c, List.of("LrA", "LrE", "LrF", "LrG"));
            c.check(overrides(c).equals(ovr), "Hub picks after the run start: " + overrides(c) + ", want " + ovr + " (" + out + ")");
            c.check(pins(c).equals(pins), "Hub pins changed at the run start: " + pins + " -> " + pins(c) + " (" + out + ")");
            c.note("new party, set in the Hub: picks " + ovr + ", pins " + pins + " kept, LrB's old pick gone; " + out);
        } finally {
            restore(c, sv);
        }
    }

    /** Two newcomers formed in the Hub and pinned there: the run start must not move them into the departed's spots. */
    static void hubTwoNew(Session c) throws Exception {
        Saved sv = setUp(c);
        try {
            run(c, P);
            pick(c, "LrD", "ARCHER");
            hub(c, "LrA", List.of("LrB", "LrE", "LrF"));
            pick(c, "LrE", "MAGE");
            List<String> lay = layout(c);
            swap(c, lay.indexOf("LrE"), lay.indexOf("LrF"));
            List<String> pins = pins(c);
            c.check(pins.contains("LrE") && pins.contains("LrF"), "Hub setup: " + pins + " (layout " + lay + ")");
            enter(c);
            String out = run(c, List.of("LrA", "LrB", "LrE", "LrF"));
            c.check(pins(c).equals(pins), "Hub pins moved at the run start: " + pins + " -> " + pins(c) + " (" + out + ")");
            c.check("MAGE".equals(overrides(c).get("LrE")), "LrE's Hub pick went: " + overrides(c) + " (" + out + ")");
            c.check(!overrides(c).containsKey("LrD"), "LrD's pick survived a run without him: " + overrides(c));
            c.note("two new, pinned in the Hub: pins " + pins + " kept, LrE MAGE kept, LrD's pick gone; " + out);
        } finally {
            restore(c, sv);
        }
    }

    /** Set during a run, then a trip to the Hub with the same party: kept into the next run. */
    static void runPickNextRun(Session c) throws Exception {
        Saved sv = setUp(c);
        try {
            run(c, P);
            pick(c, "LrA", "TANK");
            swap(c, 1, 2);
            Map<String, String> ovr = overrides(c);
            List<String> pins = pins(c);
            c.check(ovr.size() == 1 && pinCount(pins) == 2, "in-run setup: " + ovr + " " + pins);
            hub(c, "LrA", List.of("LrB", "LrC", "LrD"));
            enter(c);
            String out = run(c, P);
            c.check(overrides(c).equals(ovr), "in-run picks changed at the next run: " + ovr + " -> " + overrides(c) + " (" + out + ")");
            c.check(pins(c).equals(pins), "in-run pins changed at the next run: " + pins + " -> " + pins(c) + " (" + out + ")");
            c.note("set during a run, kept into the next: picks " + ovr + ", pins " + pins + "; " + out);
        } finally {
            restore(c, sv);
        }
    }

    // ---- 2345-2347: one-for-one swap and the reset notice (killer560, 2026-10-23) --------------------------------------

    static final String NOTICE = "Leap Order was reset";

    /** Lines of the chat WINDOW (what he sees, newest first) holding the reset notice. */
    @SuppressWarnings("unchecked")
    static long notices(Session c) {
        return c.onClient(mc -> ((List<String>) Mod.staticCall("chattidy.ChatTidy", "testChatLines", 200)).stream()
                .filter(l -> l.contains(NOTICE)).count());
    }

    /** The on-screen title's text, or "?" when the Gui field cannot be read. */
    static String title(Session c) {
        return c.onClient(mc -> {
            try {
                java.lang.reflect.Field f = mc.gui.getClass().getDeclaredField("title");
                f.setAccessible(true);
                Object t = f.get(mc.gui);
                return t == null ? "" : ((net.minecraft.network.chat.Component) t).getString();
            } catch (ReflectiveOperationException | RuntimeException e) {
                return "?";
            }
        });
    }

    /** LrD (Berserk, no pick) out, LrE (tab list Archer) in, nobody else's class changed: LrE takes LrD's spot AND his
     *  Berserk, as a pick, and no notice is shown. */
    static void swapTakesClass(Session c) throws Exception {
        Saved sv = setUp(c);
        try {
            run(c, P);
            List<String> before = layout(c);
            int dSpot = before.indexOf("LrD");
            long n0 = notices(c);
            String out = run(c, List.of("LrA", "LrB", "LrC", "LrE"));
            List<String> after = layout(c);
            c.check("BERSERK".equals(overrides(c).get("LrE")) && cls(c, "LrE").equals("BERSERK"),
                    "LrE's class is " + cls(c, "LrE") + " (picks " + overrides(c) + "), want LrD's BERSERK (" + out + ")");
            c.check(after.get(dSpot).equals("LrE"), "LrE is not in LrD's old spot " + dSpot + ": " + before + " -> " + after);
            for (String n : List.of("LrA", "LrB", "LrC")) {
                c.check(after.indexOf(n) == before.indexOf(n), n + " moved: " + before + " -> " + after);
                c.check(!overrides(c).containsKey(n), n + " was given a pick: " + overrides(c));
            }
            c.ctx().waitTicks(5);
            c.check(notices(c) == n0, "a reset notice was shown for a clean swap (" + out + ")");
            c.note("one-for-one swap: LrE takes BERSERK and spot " + dSpot + ", no notice; " + before + " -> " + after
                    + "; " + out);
        } finally {
            restore(c, sv);
        }
    }

    /** Two out (LrC with a pick, LrD), two in: the old rules run (LrC's pick gone, newcomers in the vacated spots with
     *  their tab-list classes) and the reset notice is shown in chat and as a title. */
    static void twoLeaveNotice(Session c) throws Exception {
        Saved sv = setUp(c);
        try {
            run(c, P);
            pick(c, "LrC", "ARCHER");
            List<String> before = layout(c);
            List<Integer> vacated = new ArrayList<>(List.of(before.indexOf("LrC"), before.indexOf("LrD")));
            vacated.sort(Integer::compare);
            long n0 = notices(c);
            String out = run(c, List.of("LrA", "LrB", "LrE", "LrF"));
            c.ctx().waitTicks(5);
            long n1 = notices(c);
            String title = title(c);
            List<String> after = layout(c);
            c.check(n1 == n0 + 1, "the reset notice is in the chat window " + (n1 - n0) + " times, want once (" + out + ")");
            c.check(title.equals("?") || title.contains("Leap Order reset"), "the title reads '" + title + "'");
            c.check(!overrides(c).containsKey("LrC"), "LrC's pick survived: " + overrides(c));
            c.check(!overrides(c).containsKey("LrE") && !overrides(c).containsKey("LrF"),
                    "a newcomer was given a pick: " + overrides(c));
            c.check(after.get(vacated.get(0)).equals("LrE") && after.get(vacated.get(1)).equals("LrF"),
                    "vacated spots " + vacated + " hold " + after + ", want LrE then LrF");
            c.note("two left: notice shown (title '" + title + "'), LrC's pick gone, " + before + " -> " + after + "; " + out);
        } finally {
            restore(c, sv);
        }
    }

    /** LrD out, LrE in, but LrB came back as Mage instead of Tank: not a clean swap, so LrE keeps the tab list's Archer
     *  (no pick) and the reset notice is shown. */
    static void classChangeNotice(Session c) throws Exception {
        Saved sv = setUp(c);
        try {
            run(c, P);
            List<String> before = layout(c);
            int dSpot = before.indexOf("LrD");
            Map<String, String> changed = new java.util.HashMap<>(TAB_CLASS);
            changed.put("LrB", "Mage");
            long n0 = notices(c);
            String out = run(c, List.of("LrA", "LrB", "LrC", "LrE"), changed);
            c.ctx().waitTicks(5);
            long n1 = notices(c);
            c.check(cls(c, "LrB").equals("MAGE"), "setup: LrB reads as " + cls(c, "LrB") + ", want MAGE");
            c.check(n1 == n0 + 1, "the reset notice is in the chat window " + (n1 - n0) + " times, want once (" + out + ")");
            c.check(!overrides(c).containsKey("LrE") && cls(c, "LrE").equals("ARCHER"),
                    "LrE's class is " + cls(c, "LrE") + " (picks " + overrides(c) + "), want the tab list's ARCHER");
            c.check(pins(c).get(dSpot).equals("LrE"), "LrE is not pinned in LrD's old spot " + dSpot + ": " + pins(c));
            c.note("LrB's class changed: notice shown, LrE ARCHER pinned in spot " + dSpot + "; " + out);
        } finally {
            restore(c, sv);
        }
    }
}
