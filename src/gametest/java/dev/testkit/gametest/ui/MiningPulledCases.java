package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Mining features pulled from mod branch staging-mining-3 onto main (mod chport-1104, killer560 2026-11-04: "add a ch
 * map", "i dont see stuff in the mod folder like the crystal hollows map"). Replaces 411-ui-mining-shelved, which asserted
 * the opposite.
 * <ul>
 *   <li>1104-ui-mining-pulled-jar: every pulled feature class is in the jar; the macros left on staging (Auto Nucleus Run's
 *       planner, Lobby Swapper) and the staging copies main already covers better (Commission HUD / Menu / Mob Highlight,
 *       Powder Tracker, Powder Chest Highlight, Powder Chest Chat Filter, the block-update mixin) are not.</li>
 *   <li>1104-ui-mining-pulled-menu: each pulled card sits under "Mining &gt; ..." exactly once, Ghost ESP and Structure
 *       Scanner only on a cheat jar and flagged cheat-only, no Lobby Swapper anywhere.</li>
 *   <li>1104-ui-mining-pulled-tabs: each pulled card's first row is its switch; switched on it builds more rows; ON
 *       survives a save and a re-read from disk (the in-memory value is put back to OFF first, so only the file can
 *       answer ON).</li>
 *   <li>1104-ui-mining-pulled-profit: /profit has the Mining Profit and Nucleus Runs cards.</li>
 *   <li>1104-ui-mining-chmap: a Mines of Divan find recorded through ChDiscovery is listed by it, the Crystal Hollows Map
 *       tab's real Open Map button opens the map screen, it draws 20 ticks with nothing disabled by FeatureGuard. The tab
 *       lists a visited find with "(visited)" and a scanned one by its name alone, no "(scanned)" line (mod chscan-1104),
 *       and has the Names On HUD switch.</li>
 *   <li>chscan-1104 in -menu and -tabs: on a cheat jar the Structure Scanner is the last card of Mining &gt; Crystal Hollows
 *       (its cheat block, after the Crystal Hollows Map), and its tab has no Scan Again button and no "chunks scanned"
 *       line.</li>
 * </ul>
 */
final class MiningPulledCases {

    /** Card name, config (a config class under com.killer560.hub, or "island:NAME" for IslandConfig.of("mining", NAME)),
     *  switch label, cheat only. */
    record Card(String name, String config, String switchLabel, boolean cheat) {
    }

    static final List<Card> CARDS = List.of(
            new Card("Profit Per Hour", "mining.profit.MiningProfitConfig", "Profit Per Hour Tracker", false),
            new Card("Nucleus Run Profit", "mining.nucleus.NucleusRunProfitConfig", "Nucleus Run Profit Tracker", false),
            new Card("Crystal Hollows Map", "mining.chmap.CrystalHollowsMapConfig", "Crystal Hollows Map", false),
            new Card("Metal Detector", "mining.metaldetector.MetalDetectorConfig", "Metal Detector", false),
            new Card("Mining Events", "mining.events.MiningEventsConfig", "Mining Events", false),
            new Card("Lobby Tracker", "mining.lobby.LobbyTrackerConfig", "Lobby Tracker", false),
            new Card("Crystal Tracker", "mining.crystals.CrystalTrackerConfig", "Crystal Tracker", false),
            new Card("Cold Overlay", "island:coldoverlay", "Cold Overlay", false),
            new Card("Wishing Compass Solver", "island:compass", "Wishing Compass Solver", false),
            new Card("Fetchur Solver", "island:fetchur", "Fetchur Solver", false),
            new Card("Fossil Solver", "island:fossil", "Fossil Solver", false),
            new Card("Puzzler Solver", "island:puzzler", "Puzzler Solver", false),
            new Card("Sky Mall Display", "island:skymall", "Sky Mall Display", false),
            new Card("Worm Alert", "island:worms", "Worm Alert", false),
            new Card("Structure Scanner", "mining.scanner.StructureScannerConfig", "Structure Scanner", true),
            new Card("Ghost ESP", "mining.ghosts.GhostEspConfig", "Ghost ESP", true));

    private static final List<String> PRESENT = List.of(
            "mining.chmap.CrystalHollowsMapScreen", "mining.chmap.ChDiscovery", "mining.chmap.ChShare",
            "mining.profit.MiningProfitTracker", "mining.nucleus.NucleusRunProfitTracker",
            "mining.metaldetector.MetalDetectorFeature", "mining.events.MiningEventsFeature",
            "mining.lobby.LobbyTrackerFeature", "mining.crystals.CrystalTrackerFeature", "mining.fetchur.FetchurSolver",
            "mining.puzzler.PuzzlerSolver", "mining.cold.ColdOverlay", "mining.skymall.SkyMallDisplay",
            "mining.worms.WormAlert", "mining.fossil.FossilSolver", "mining.compass.WishingCompassSolver",
            "mining.scanner.StructureScannerFeature", "mining.ghosts.GhostEspFeature",
            "gui.profit.MiningProfitScreen", "gui.profit.NucleusProfitScreen");
    private static final List<String> ABSENT_PREFIXES = List.of(
            "com.killer560.hub.mining.nucleusrun.", "com.killer560.hub.mining.lobbyswap.",
            "com.killer560.hub.mining.commissions.", "com.killer560.hub.mining.commissionmenu.",
            "com.killer560.hub.mining.commissionmobs.", "com.killer560.hub.mining.powder.",
            "com.killer560.hub.mining.powderchest.", "com.killer560.hub.mining.chestfilter.",
            "com.killer560.hub.mining.mixin.", "com.killer560.hub.gui.tab.LobbySwapperTab",
            "com.killer560.hub.gui.tab.PowderChestTab", "com.killer560.hub.gui.tab.CommissionHudTab");

    private MiningPulledCases() {
    }

    // ---- jar ------------------------------------------------------------------------------------------------------

    static void jar(UiCase c) {
        List<String> all = JarIndex.classNames(s -> true);
        c.check(all.size() > 500, "the jar index listed only " + all.size() + " classes - the index itself is broken");
        List<String> missing = new ArrayList<>();
        for (String s : PRESENT) {
            if (!all.contains(Mod.ROOT + s)) {
                missing.add(s);
            }
        }
        List<String> stray = new ArrayList<>();
        for (String fq : all) {
            for (String p : ABSENT_PREFIXES) {
                if (fq.startsWith(p)) {
                    stray.add(fq.replace(Mod.ROOT, ""));
                }
            }
        }
        c.note("pulled classes present: " + (PRESENT.size() - missing.size()) + "/" + PRESENT.size()
                + "; left-out classes found: " + stray);
        c.check(missing.isEmpty(), "pulled Mining classes missing from the jar: " + missing);
        c.check(stray.isEmpty(), "classes that should have stayed on staging-mining-3 are in the jar: " + stray);
    }

    // ---- menu -----------------------------------------------------------------------------------------------------

    static void menu(UiCase c) {
        Map<String, Object> out = c.onClient(mc -> {
            Map<String, Object> m = new LinkedHashMap<>();
            try {
                ModScreenDriver d = new ModScreenDriver(mc);
                List<ModScreenDriver.Node> flat = new ArrayList<>();
                ModScreenDriver.flatten(d.tree(), flat);
                Map<String, List<String>> paths = new LinkedHashMap<>();
                Map<String, Boolean> cheatFlag = new LinkedHashMap<>();
                for (ModScreenDriver.Node n : flat) {
                    paths.computeIfAbsent(n.name(), k -> new ArrayList<>()).add(n.path());
                    if (!cheatFlag.containsKey(n.name())) {
                        try {
                            cheatFlag.put(n.name(), (Boolean) Mod.call(n.tab(), "isCheatOnly"));
                        } catch (Throwable ignored) {
                            cheatFlag.put(n.name(), null);
                        }
                    }
                }
                m.put("total", flat.size());
                m.put("paths", paths);
                m.put("cheat", cheatFlag);
            } catch (Throwable t) {
                m.put("error", UiCase.describe(t));
            }
            McCompat.setScreen(mc, null);
            return m;
        });
        c.check(!out.containsKey("error"), "could not open gui.ModScreen: " + out.get("error"));
        c.check((Integer) out.get("total") > 50, "only " + out.get("total") + " tabs walked - the walk is broken");
        @SuppressWarnings("unchecked")
        Map<String, List<String>> paths = (Map<String, List<String>>) out.get("paths");
        @SuppressWarnings("unchecked")
        Map<String, Boolean> cheat = (Map<String, Boolean>) out.get("cheat");
        int found = 0;
        for (Card card : CARDS) {
            List<String> p = paths.getOrDefault(card.name(), List.of());
            if (card.cheat() && !Mod.isCheat()) {
                c.check(p.isEmpty(), "a legit jar shows the cheat card " + card.name() + " at " + p);
                continue;
            }
            c.check(p.size() == 1, card.name() + " is in the menu " + p.size() + " times: " + p);
            if (!p.isEmpty()) {
                found++;
                c.check(p.get(0).startsWith("Mining > "), card.name() + " is not in the Mining category: " + p);
                c.check(Boolean.valueOf(card.cheat()).equals(cheat.get(card.name())),
                        card.name() + " isCheatOnly() = " + cheat.get(card.name()) + ", want " + card.cheat());
            }
        }
        c.note("pulled cards in the menu: " + found + "; e.g. " + paths.get("Crystal Hollows Map"));
        if (Mod.isCheat()) {
            // killer560, 2026-11-04: "the cheat map scanner should be under the crystal hollows map".
            String group = "Mining > Crystal Hollows > ";
            c.check(List.of(group + "Structure Scanner").equals(paths.get("Structure Scanner")),
                    "Structure Scanner is at " + paths.get("Structure Scanner") + ", want " + group + "Structure Scanner");
            String last = null;
            for (Map.Entry<String, List<String>> e : paths.entrySet()) {
                for (String p : e.getValue()) {
                    if (p.startsWith(group)) {
                        last = e.getKey();
                    }
                }
            }
            c.check("Structure Scanner".equals(last), "the last card of Crystal Hollows is " + last
                    + ", want the cheat-only Structure Scanner at the bottom");
        }
        c.check(!paths.containsKey("Lobby Swapper"), "Lobby Swapper (a macro) is in the menu: " + paths.get("Lobby Swapper"));
        List<String> anr = paths.getOrDefault("Auto Nucleus Run", List.of());
        c.check(anr.isEmpty() || anr.equals(List.of("Misc > Auto Nucleus Run")),
                "Auto Nucleus Run should be only the old Misc stub: " + anr);
    }

    // ---- tabs -----------------------------------------------------------------------------------------------------

    static void tabs(UiCase c) {
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (Card card : CARDS) {
            if (card.cheat() && !Mod.isCheat()) {
                continue;
            }
            String result = c.onClient(mc -> {
                Object cfg = config(card);
                boolean was = enabledRaw(cfg);
                try {
                    Mod.call(cfg, "setEnabled", true);
                    Mod.call(cfg, "save");
                    Mod.call(cfg, "setEnabled", false);
                    reload(card);
                    Object fresh = config(card);
                    boolean reread = enabledRaw(fresh);
                    List<String> rows = labels(tabNamed(card.name()));
                    String first = rows.isEmpty() ? "" : rows.get(0);
                    String err = null;
                    if (!reread) {
                        err = card.name() + ": ON did not survive a save and a re-read";
                    } else if (!first.startsWith(card.switchLabel() + ":")) {
                        err = card.name() + ": first row is '" + first + "', want the '" + card.switchLabel() + ":' switch";
                    } else if (rows.size() < 2) {
                        err = card.name() + ": switched on, the tab built only " + rows;
                    } else if (card.name().equals("Structure Scanner") && rows.stream().anyMatch(r ->
                            r.startsWith("Scan Again") || r.contains("chunks scanned"))) {
                        err = card.name() + ": still has Scan Again or the chunks-scanned line: " + rows;
                    }
                    Mod.call(fresh, "setEnabled", was);
                    Mod.call(fresh, "save");
                    return err == null ? "ok " + rows.size() + " rows" : err;
                } catch (Throwable t) {
                    return card.name() + ": threw " + UiCase.describe(t);
                }
            });
            checked++;
            c.note(card.name() + ": " + result);
            if (!result.startsWith("ok")) {
                problems.add(result);
            }
        }
        c.check(checked >= 14, "only " + checked + " cards checked - the walk is broken");
        c.check(problems.isEmpty(), "Mining cards failing: " + problems);
    }

    private static Object config(Card card) {
        if (card.config().startsWith("island:")) {
            return Mod.staticCall("islands.IslandConfig", "of", "mining", card.config().substring(7));
        }
        return Mod.cfg(card.config());
    }

    private static void reload(Card card) {
        if (card.config().startsWith("island:")) {
            Mod.call(config(card), "load");
        } else {
            Mod.staticCall(card.config(), "load");
        }
    }

    private static boolean enabledRaw(Object cfg) {
        try {
            return (Boolean) Mod.call(cfg, "isEnabledRaw");
        } catch (Throwable t) {
            return (Boolean) Mod.call(cfg, "isEnabled");
        }
    }

    private static Object tabNamed(String name) {
        for (Object t : MenuACases.leaves()) {
            if (name.equals(MenuACases.name(t))) {
                return t;
            }
        }
        throw new AssertionError("no '" + name + "' tab among the menu's leaves");
    }

    @SuppressWarnings("unchecked")
    private static List<AbstractWidget> build(Object tab) {
        try {
            Method m = tab.getClass().getMethod("buildWidgets", int.class, int.class, int.class, Runnable.class);
            return (List<AbstractWidget>) m.invoke(tab, 10, 10, 330, (Runnable) () -> { });
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not build " + tab.getClass().getSimpleName() + ": " + UiCase.describe(e), e);
        }
    }

    private static List<String> labels(Object tab) {
        List<String> out = new ArrayList<>();
        for (AbstractWidget w : build(tab)) {
            out.add(ModScreenDriver.label(w));
        }
        return out;
    }

    // ---- profit ---------------------------------------------------------------------------------------------------

    static void profit(UiCase c) {
        List<String> constants = new ArrayList<>();
        for (Object t : Mod.cls("gui.profit.ProfitTracker").getEnumConstants()) {
            constants.add(t.toString());
        }
        c.note("ProfitTracker constants: " + constants);
        c.check(constants.contains("MINING") && constants.contains("NUCLEUS"), "no MINING/NUCLEUS tracker: " + constants);
        String err = CommandSweep.execute(c, "profit");
        c.check(err == null, "'/profit' threw: " + err);
        c.ticks(4);
        Screen hub = c.onClient(mc -> McCompat.screen(mc));
        List<String> cards = c.onClient(mc -> {
            List<String> labels = new ArrayList<>();
            if (hub != null) {
                for (AbstractWidget w : Frames.widgets(hub)) {
                    if (w.getClass().getSimpleName().equals("ProfitCardWidget")) {
                        labels.add(w.getMessage().getString());
                    }
                }
            }
            McCompat.setScreen(mc, null);
            return labels;
        });
        c.ticks(1);
        c.note("/profit hub cards: " + cards);
        c.check(cards.contains("Mining Profit") && cards.contains("Nucleus Runs"),
                "/profit has no Mining Profit / Nucleus Runs card: " + cards);
    }

    // ---- Crystal Hollows Map --------------------------------------------------------------------------------------

    static void chMap(UiCase c) {
        Mod.Mark mark = Mod.mark();
        Object structure = Mod.enumValue("mining.chmap.ChStructure", "MINES_OF_DIVAN");
        Object source = Mod.enumValue("mining.chmap.ChFind$Source", "VISITED");
        int finds = c.onClient(mc -> {
            Mod.staticCall("mining.chmap.ChDiscovery", "clear");
            Mod.staticCall("mining.chmap.ChDiscovery", "record", structure, source, 300.0, 70.0, 300.0);
            Mod.staticCall("mining.chmap.ChDiscovery", "record", structure, source, 320.0, 72.0, 310.0);
            return ((java.util.Collection<?>) Mod.staticCall("mining.chmap.ChDiscovery", "all")).size();
        });
        Object find = c.onClient(mc -> Mod.staticCall("mining.chmap.ChDiscovery", "find", structure));
        c.note("ChDiscovery after two Mines of Divan records: " + finds + " find(s), " + find);
        c.check(finds == 1 && find != null, "ChDiscovery holds " + finds + " finds, want the one Mines of Divan find");

        // The Structures list: a scanned find is its name alone (killer560, 2026-11-04: "remove the mines of divan
        // (scanned) line in structures"), a visited one keeps "(visited)".
        Object temple = Mod.enumValue("mining.chmap.ChStructure", "JUNGLE_TEMPLE");
        Object scanned = Mod.enumValue("mining.chmap.ChFind$Source", "SCANNED");
        List<String> listed = c.onClient(mc -> {
            Mod.staticCall("mining.chmap.ChDiscovery", "record", temple, scanned, 400.0, 100.0, 400.0);
            Object cfg = Mod.cfg("mining.chmap.CrystalHollowsMapConfig");
            Mod.call(cfg, "setEnabled", true);
            List<String> rows = new ArrayList<>();
            for (String l : labels(tabNamed("Crystal Hollows Map"))) {
                rows.add(l.replaceAll("§.", ""));
            }
            return rows;
        });
        c.note("Crystal Hollows Map rows: " + listed);
        c.check(listed.contains("- Jungle Temple"), "no '- Jungle Temple' line for the scanned find: " + listed);
        c.check(listed.contains("- Mines of Divan (visited)"), "no '- Mines of Divan (visited)' line: " + listed);
        c.check(listed.stream().noneMatch(l -> l.contains("(scanned)")), "a '(scanned)' line is still listed: " + listed);
        c.check(listed.stream().anyMatch(l -> l.startsWith("Names On HUD")), "no Names On HUD switch: " + listed);

        String pressed = c.onClient(mc -> {
            Object tab = tabNamed("Crystal Hollows Map");
            Object cfg = Mod.cfg("mining.chmap.CrystalHollowsMapConfig");
            Mod.call(cfg, "setEnabled", true);
            for (AbstractWidget w : build(tab)) {
                if (ModScreenDriver.label(w).startsWith("Open Map")) {
                    return ModScreenDriver.press(w) ? "pressed" : "press refused";
                }
            }
            return "no Open Map row";
        });
        c.ticks(20);
        String screen = c.onClient(mc -> {
            Screen s = McCompat.screen(mc);
            return s == null ? "none" : s.getClass().getName();
        });
        c.note("Open Map: " + pressed + "; screen after 20 ticks: " + screen);
        c.check("pressed".equals(pressed), "Open Map: " + pressed);
        c.check(screen.equals(Mod.ROOT + "mining.chmap.CrystalHollowsMapScreen"),
                "Open Map did not leave the map screen open: " + screen);
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            Mod.call(Mod.cfg("mining.chmap.CrystalHollowsMapConfig"), "setEnabled", false);
            Mod.staticCall("mining.chmap.ChDiscovery", "clear");
            return null;
        });
        Mod.assertHealthy(mark);
    }
}
