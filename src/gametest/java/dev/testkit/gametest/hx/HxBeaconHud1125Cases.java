package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;

import java.util.List;

/**
 * 1125-hx-beacon-hud-draw: the Beacon Ready HUD element is registered and its render path draws the expected string on real
 * frames in a world (HudSeen stamp plus the last drawn text), and draws nothing with both switches off. The tab is fed again
 * on every poll because the feature's own once-a-second tab read (no beacon widget in a test world) would reset the state.
 */
final class HxBeaconHud1125Cases {

    private static final String F = "foraging.BeaconReadyAlert";
    private static final String HUD = "foraging.BeaconReadyHud";
    private static final String SEEN = "hud.HudSeen";
    private static final String ID = "beacon_ready";

    private HxBeaconHud1125Cases() {
    }

    static void register(Session s) {
        s.test("1125-hx-beacon-hud-draw", HxBeaconHud1125Cases::draw);
    }

    private static List<String> tab(String cooldown) {
        return List.of("Area: Moonglade Marsh", "Moonglade Beacon: 2 Stacks", " +32 Fig Fortune", " Cooldown: " + cooldown);
    }

    private static void feed(String cooldown) {
        Mod.staticCall(F, "onTab", tab(cooldown), System.currentTimeMillis());
    }

    private static String drawn(Session c) {
        return (String) c.onClient(mc -> Mod.staticCall(HUD, "lastDrawn"));
    }

    private static void set(Session c, boolean keep, boolean cd) {
        c.onClient(mc -> {
            Object cfg = Mod.field(F, "CFG");
            Mod.call(cfg, "set", "enabled", true);
            Mod.call(cfg, "set", "keep", keep);
            Mod.call(cfg, "set", "cooldownHud", cd);
            return null;
        });
    }

    private static void expect(Session c, String cooldown, String text) {
        c.waitUntil("the HUD drawing " + text, mc -> {
            feed(cooldown);
            return text.equals(Mod.staticCall(HUD, "lastDrawn"));
        }, 100);
        c.check((Boolean) c.onClient(mc -> Mod.staticCall(SEEN, "drawnRecently", ID)), "HudSeen never saw " + text);
    }

    private static void draw(Session c) {
        boolean reg = (Boolean) c.onClient(mc -> {
            Object e = Mod.staticCall("hud.HudElementRegistry", "byId", ID);
            return e != null && ((Number) Mod.call(e, "width")).intValue() > 1;
        });
        c.check(reg, "the Beacon Ready HUD element is not registered");
        try {
            set(c, false, false);
            c.onClient(mc -> {
                Mod.staticCall(SEEN, "forget", ID);
                feed("AVAILABLE");
                return null;
            });
            c.ctx().waitTicks(10);
            c.check(drawn(c) == null && !(Boolean) c.onClient(mc -> Mod.staticCall(SEEN, "drawnRecently", ID)),
                    "both switches off drew " + drawn(c));
            set(c, false, true);
            expect(c, "7m", "Beacon: ~7m");
            expect(c, "AVAILABLE", "Beacon: Ready");
            set(c, true, false);
            expect(c, "AVAILABLE", "Beacon Ready");
            c.onClient(mc -> {
                feed("7m");
                return null;
            });
            c.waitUntil("the HUD drawing nothing while cooling with only Keep on", mc -> {
                feed("7m");
                return Mod.staticCall(HUD, "lastDrawn") == null;
            }, 100);
            c.note("drew ~7m, Ready, Beacon Ready and nothing");
        } finally {
            set(c, false, false);
            c.onClient(mc -> Mod.call(Mod.field(F, "CFG"), "set", "enabled", false));
        }
    }
}
