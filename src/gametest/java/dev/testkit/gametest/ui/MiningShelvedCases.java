package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 411: mining is shelved until after 2.0 (killer560, 2026-10-07: "make it so they no longer show on the menu and their
 * code is no longer shipped"; mod shelved/mining/README.md). Three checks, each against the real jar:
 * <ol>
 *   <li>the jar has no class under {@code com/killer560/hub/mining/} and none of the shelved tab/screen classes;</li>
 *   <li>the mod menu's whole tab tree (every folder expanded) has no mining tab, by class and by name;</li>
 *   <li>{@code /profit} opens the hub and its cards are exactly the non-mining trackers.</li>
 * </ol>
 * Each finding is a PROBLEM (the case fails at its end), so an old jar reports all three. Fails on any jar before the shelving (b7528f86 has 14 mining classes, the "Mining (WIP)" category and 4 cards).
 */
final class MiningShelvedCases {

    private static final String MINING_PKG = "com.killer560.hub.mining.";
    /** The shelved classes that lived outside the mining package. */
    private static final Set<String> SHELVED_OUTSIDE = Set.of(
            "com.killer560.hub.gui.tab.MiningWipTab", "com.killer560.hub.gui.tab.MiningProfitTab",
            "com.killer560.hub.gui.tab.NucleusRunProfitTab", "com.killer560.hub.gui.tab.CrystalHollowsMapTab",
            "com.killer560.hub.gui.profit.MiningProfitScreen", "com.killer560.hub.gui.profit.NucleusProfitScreen");
    /** Lower-cased words no tab name may contain while mining is shelved. */
    private static final List<String> MINING_WORDS = List.of("mining", "crystal hollows", "nucleus", "metal detector");
    private static final List<String> EXPECTED_CARDS = List.of("Dungeon Profit", "Experimentation Table");
    private static final String HUB = "gui.profit.ProfitHubScreen";

    private MiningShelvedCases() {
    }

    static void run(UiCase c) {
        jar(c);
        tabs(c);
        profitHub(c);
    }

    private static void jar(UiCase c) {
        List<String> all = JarIndex.classNames(s -> true);
        c.check(all.size() > 500, "the jar index listed only " + all.size() + " classes - the index itself is broken");
        List<String> bad = new ArrayList<>();
        for (String fq : all) {
            if (fq.startsWith(MINING_PKG) || SHELVED_OUTSIDE.contains(fq)) {
                bad.add(fq.replace("com.killer560.hub.", ""));
            }
        }
        // Inner classes are not in the index; the package itself must not load either.
        for (String fq : SHELVED_OUTSIDE) {
            if (JarIndex.peek(fq) != null && !bad.contains(fq.replace("com.killer560.hub.", ""))) {
                bad.add(fq.replace("com.killer560.hub.", "") + " (loadable)");
            }
        }
        c.note("jar: " + all.size() + " top-level classes under com.killer560.hub; shelved classes present: " + bad);
        if (!bad.isEmpty()) {
            c.problem("the jar still ships " + bad.size() + " shelved mining class(es): " + bad);
        }
    }

    private static void tabs(UiCase c) {
        Map<String, Object> out = c.onClient(mc -> {
            Map<String, Object> m = new LinkedHashMap<>();
            try {
                ModScreenDriver d = new ModScreenDriver(mc);
                List<ModScreenDriver.Node> flat = new ArrayList<>();
                ModScreenDriver.flatten(d.tree(), flat);
                List<String> top = new ArrayList<>();
                for (Object t : d.topTabs()) {
                    top.add((String) R.get(t, "name"));
                }
                List<String> bad = new ArrayList<>();
                for (ModScreenDriver.Node n : flat) {
                    String cls = n.tab().getClass().getName();
                    String name = n.name() == null ? "" : n.name().toLowerCase(Locale.ROOT);
                    boolean byClass = cls.startsWith(MINING_PKG) || SHELVED_OUTSIDE.contains(cls)
                            || (n.tab().getClass().getEnclosingClass() != null
                                && SHELVED_OUTSIDE.contains(n.tab().getClass().getEnclosingClass().getName()));
                    boolean byName = MINING_WORDS.stream().anyMatch(name::contains);
                    if (byClass || byName) {
                        bad.add(n.path() + " [" + cls.replace("com.killer560.hub.", "") + "]");
                    }
                }
                m.put("total", flat.size());
                m.put("top", top);
                m.put("bad", bad);
            } catch (Throwable t) {
                m.put("error", UiCase.describe(t));
            }
            McCompat.setScreen(mc, null);
            return m;
        });
        c.check(!out.containsKey("error"), "could not open gui.ModScreen: " + out.get("error"));
        c.note("menu: " + out.get("total") + " tabs walked; top level " + out.get("top"));
        @SuppressWarnings("unchecked")
        List<String> bad = (List<String>) out.get("bad");
        c.check((Integer) out.get("total") > 50, "only " + out.get("total") + " tabs walked - the walk is broken");
        if (!bad.isEmpty()) {
            c.problem("the menu still has " + bad.size() + " mining tab(s): " + bad);
        }
    }

    private static void profitHub(UiCase c) {
        List<String> constants = new ArrayList<>();
        for (Object t : Mod.cls("gui.profit.ProfitTracker").getEnumConstants()) {
            constants.add(t.toString());
        }
        c.note("ProfitTracker constants: " + constants);
        if (constants.contains("MINING") || constants.contains("NUCLEUS")) {
            c.problem("ProfitTracker still has a mining constant: " + constants);
        }

        String err = CommandSweep.execute(c, "profit");
        c.check(err == null, "'/profit' threw: " + err);
        c.ticks(4);
        Screen hub = c.onClient(mc -> McCompat.screen(mc));
        String got = hub == null ? "none" : hub.getClass().getName();
        c.check(got.equals(Mod.ROOT + HUB), "/profit should open " + HUB + " but the screen is " + got);
        List<String> cards = c.onClient(mc -> {
            List<String> labels = new ArrayList<>();
            for (AbstractWidget w : Frames.widgets(hub)) {
                if (w.getClass().getSimpleName().equals("ProfitCardWidget")) {
                    labels.add(w.getMessage().getString());
                }
            }
            McCompat.setScreen(mc, null);
            return labels;
        });
        c.ticks(1);
        c.note("/profit hub cards: " + cards);
        c.check(cards.equals(EXPECTED_CARDS), "/profit hub cards are " + cards + ", expected only the non-mining "
                + EXPECTED_CARDS);
    }
}
