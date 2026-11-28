package dev.testkit.gametest.ui;

import com.mojang.brigadier.CommandDispatcher;
import dev.testkit.gametest.mod.Mod;
import java.util.ArrayList;
import java.util.List;

/**
 * 1126-ui-testjar-no-mining-foraging (mod testjar-no-mining-foraging-1126, killer560 2026-11-26: Mining and Foraging stay
 * out of every testing jar). On a testing jar: no Mining or Foraging category, none of their tab classes anywhere in the menu,
 * no HUD element and no command from their packages. On any other jar the same probes must FIND them (the case asserts
 * the features are there, so a gate that removes them everywhere fails it too).
 */
final class TestJarNoMiningForaging1126Cases {

    private static final String[] PKGS = {"miningqol.", "powdermining.", "mining.", "foraging.", "mineshaft."};

    private TestJarNoMiningForaging1126Cases() {
    }

    static void check(UiCase c) throws Exception {
        boolean testing = JarIndex.peek(JarIndex.ROOT + "testing.TestedFeatures") != null;
        try (AutoCloseable all = MenuLayoutCases.allTested(c)) {
            ModScreenDriver d = MenuLayoutCases.driver(c);
            List<ModScreenDriver.Node> flat = new ArrayList<>();
            List<String> top = new ArrayList<>();
            List<ModScreenDriver.Node> tree = c.onClient(mc -> d.tree());
            for (ModScreenDriver.Node n : tree) {
                top.add(n.name());
            }
            ModScreenDriver.flatten(tree, flat);
            int mqTabs = 0;
            for (ModScreenDriver.Node n : flat) {
                if (!n.folder() && MenuLayoutCases.removedForTesting(n.path())) {
                    mqTabs++;
                }
            }
            @SuppressWarnings("unchecked")
            List<Object> huds = (List<Object>) Mod.staticCall("hud.HudElementRegistry", "all");
            int hudHits = 0;
            for (Object h : huds) {
                String cn = h.getClass().getName().replace(Mod.ROOT, "");
                for (String p : PKGS) {
                    if (cn.startsWith(p)) {
                        hudHits++;
                        c.note("HUD element from a removed package: " + cn);
                    }
                }
            }
            CommandDispatcher<Object> disp = CommandSweep.dispatcher();
            boolean chmap = disp.getRoot().getChild("chmap") != null;
            c.note("testing jar: " + testing + "; top level " + top + "; mining/foraging tabs " + mqTabs + "; HUD elements from those packages "
                    + hudHits + " of " + huds.size() + "; /chmap " + chmap);
            if (testing) {
                c.check(!top.contains("Mining") && !top.contains("Foraging"), "a Mining or Foraging category exists: " + top);
                c.check(mqTabs == 0, mqTabs + " Mining/Foraging tab(s) in the menu");
                c.check(hudHits == 0, hudHits + " HUD element(s) from Mining/Foraging packages are registered");
                c.check(!chmap, "/chmap is registered");
            } else {
                c.check(top.contains("Mining") && top.contains("Foraging"), "Mining or Foraging category missing: " + top);
                c.check(mqTabs > 30, "only " + mqTabs + " Mining/Foraging tabs");
                c.check(hudHits > 5, "only " + hudHits + " HUD elements from Mining/Foraging packages");
                c.check(chmap, "/chmap is not registered");
            }
            MenuLayoutCases.close(c, d);
        }
    }
}
