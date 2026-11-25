package dev.testkit.gametest.menu;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import java.util.List;

/**
 * 1125-menu-beacon-hud: the Beacon Ready HUD line in each state, fed the tab widget as the logger recorded it (stat lines
 * between the header and "Cooldown: 7m", whole minutes only): both switches off draws nothing; Cooldown HUD shows "~7m"
 * until the minute drops, then counts seconds and says Ready at AVAILABLE; Keep Showing Until Tuned holds "Beacon Ready"
 * far past the old title time and drops it the moment the cooldown starts.
 */
final class BeaconHudCases {

    private static final String F = "foraging.BeaconReadyAlert";

    private BeaconHudCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1125-menu-beacon-hud", BeaconHudCases::hud);
    }

    private static List<String> tab(String cooldown) {
        return List.of("Area: Moonglade Marsh", "Moonglade Beacon: 2 Stacks", " +32 Fig Fortune", " +32 Mangrove Fortune",
                " Cooldown: " + cooldown);
    }

    private static void hud(Session c) throws Exception {
        long t = System.currentTimeMillis();
        String[] r = c.onClient(mc -> {
            Object cfg = Mod.field(F, "CFG");
            String[] o = new String[12];
            Mod.call(cfg, "set", "enabled", true);
            Mod.call(cfg, "set", "keep", false);
            Mod.call(cfg, "set", "cooldownHud", false);
            Mod.staticCall(F, "onTab", tab("AVAILABLE"), t);
            o[0] = (String) Mod.staticCall(F, "hudText", t);
            Mod.staticCall(F, "onTab", tab("7m"), t + 1000);
            o[1] = (String) Mod.staticCall(F, "hudText", t + 1000);
            Mod.call(cfg, "set", "cooldownHud", true);
            o[2] = (String) Mod.staticCall(F, "hudText", t + 1000);
            Mod.staticCall(F, "onTab", tab("6m"), t + 61_000);
            o[3] = (String) Mod.staticCall(F, "hudText", t + 62_000);
            o[4] = (String) Mod.staticCall(F, "hudText", t + 61_000 + 360_000 - 45_000);
            Mod.staticCall(F, "onTab", tab("AVAILABLE"), t + 500_000);
            o[5] = (String) Mod.staticCall(F, "hudText", t + 500_000);
            Mod.staticCall(F, "onTab", tab("4m 12s"), t + 600_000);
            o[6] = (String) Mod.staticCall(F, "hudText", t + 602_000);
            Mod.call(cfg, "set", "cooldownHud", false);
            Mod.call(cfg, "set", "keep", true);
            o[7] = (String) Mod.staticCall(F, "hudText", t + 602_000);
            Mod.staticCall(F, "onTab", tab("AVAILABLE"), t + 700_000);
            o[8] = (String) Mod.staticCall(F, "hudText", t + 700_000 + 10 * 60_000L);
            Mod.staticCall(F, "onTab", tab("9m"), t + 800_000);
            o[9] = (String) Mod.staticCall(F, "hudText", t + 800_000);
            Mod.staticCall(F, "onTab", List.of("Players (9)"), t + 900_000);
            Mod.call(cfg, "set", "cooldownHud", true);
            o[10] = (String) Mod.staticCall(F, "hudText", t + 900_000);
            Mod.call(cfg, "set", "keep", false);
            Mod.call(cfg, "set", "cooldownHud", false);
            Mod.call(cfg, "set", "enabled", false);
            return o;
        });
        c.note("hud: " + String.join(" | ", java.util.Arrays.stream(r).map(String::valueOf).toList()));
        c.check(r[0] == null && r[1] == null, "both switches off drew text: " + r[0] + " / " + r[1]);
        c.check("Beacon: ~7m".equals(r[2]), "first read of 7m said " + r[2]);
        c.check("Beacon: 5m 59s".equals(r[3]), "one second after the drop to 6m said " + r[3]);
        c.check("Beacon: 45s".equals(r[4]), "45 s from zero said " + r[4]);
        c.check("Beacon: Ready".equals(r[5]), "available said " + r[5]);
        c.check("Beacon: 4m 10s".equals(r[6]), "an exact 4m 12s read 2 s later said " + r[6]);
        c.check(r[7] == null, "Keep alone drew during the cooldown: " + r[7]);
        c.check("Beacon Ready".equals(r[8]), "Keep did not hold Beacon Ready 10 minutes on: " + r[8]);
        c.check(r[9] == null, "Keep alone still drew after the beacon was tuned: " + r[9]);
        c.check(r[10] == null, "no beacon widget drew " + r[10]);
    }
}
