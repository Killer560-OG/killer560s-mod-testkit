package dev.testkit.gametest.ui;

import dev.testkit.gametest.mod.Mod;

import java.util.ArrayList;
import java.util.List;

/** 1112-ui-ap3-preset (mod ap3preset-1112, cheat jar): the built-in "S1 to SS optimized" route is in the AP3 route list and loads 15 nodes. */
final class Ap3PresetCases {

    private static final String NAME = "S1 to SS optimized.json";

    private Ap3PresetCases() {
    }

    @SuppressWarnings("unchecked")
    static void run(UiCase c) throws Exception {
        if (!Mod.isCheat()) {
            c.note("legit jar: AP3 is cheat-only, skipped");
            return;
        }
        Object cfg = Mod.cfg("ap3.Ap3Config");
        String oldFile = (String) Mod.call(cfg, "getChainsFile");
        boolean oldForce = (Boolean) Mod.staticCall("ap3.Ap3Feature", "isForceDungeon");
        try {
            List<String> names = c.onClient(mc -> (List<String>) Mod.staticCall("ap3.Ap3Store", "listConfigNames"));
            c.note("route list: " + names);
            c.check(names.contains(NAME), "built-in route '" + NAME + "' is not in the route list " + names);
            int n = c.onClient(mc -> {
                Mod.call(cfg, "setChainsFile", NAME);
                Mod.staticCall("ap3.Ap3Feature", "setForceDungeon", true);
                Mod.staticCall("ap3.Ap3Store", "reload");
                return new ArrayList<>((List<Object>) Mod.staticCall("ap3.Ap3Feature", "currentChainNodes")).size();
            });
            c.note("built-in route loaded " + n + " nodes");
            c.check(n == 15, "built-in route loaded " + n + " nodes, expected 15");
        } finally {
            c.onClient(mc -> {
                Mod.call(cfg, "setChainsFile", oldFile);
                Mod.staticCall("ap3.Ap3Feature", "setForceDungeon", oldForce);
                Mod.staticCall("ap3.Ap3Store", "reload");
                return null;
            });
        }
    }
}
