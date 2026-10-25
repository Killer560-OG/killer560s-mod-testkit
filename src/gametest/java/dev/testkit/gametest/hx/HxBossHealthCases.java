package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;

import java.util.List;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Boss Health (mod branch boss-hp-1025, {@code bosshealth/BossHealthFeature}): one HUD line per Catacombs boss, read
 * from the boss's name-tag stand (F1-F6) or the boss bar (F4 Thorn, F7 withers). Each case puts up what Hypixel would
 * (a floor sidebar, a "﴾ Bonzo 250k❤ ﴿" stand, a "Maxor" boss bar at half) and reads back the exact lines the HUD draws
 * ({@code BossHealthFeature.currentLines()}), and the gate case also proves the element was really drawn (HudSeen).
 * <p>
 * The boss room is the mod's own per-floor box ({@code LiveMapFeature.isInBoss}); F1's is x -72..-14, y 55..146,
 * z -40..49, and (-30, 141, 0) stands on HxSuite's catch floor (y 140, |x|,|z| <= 64) outside F1's 4x5 room grid.
 * The latch it sets lasts until the dungeon is left, so the last case leaves it (hub sidebar) and goes back to the
 * platform, and the cases after this block start as before.
 */
final class HxBossHealthCases {

    private static final String CFG = "bosshealth.BossHealthConfig";
    private static final String FEATURE = "bosshealth.BossHealthFeature";

    private HxBossHealthCases() {
    }

    static void register(Session s) {
        s.test("1025-bosshp-f1-gate", HxBossHealthCases::f1Gate);
        s.test("1025-bosshp-f3-minions", HxBossHealthCases::f3Minions);
        s.test("1025-bosshp-f5-f6", HxBossHealthCases::f5f6);
        s.test("1025-bosshp-bars", HxBossHealthCases::bars);
        s.test("1025-bosshp-cleanup", HxBossHealthCases::cleanup);
    }

    @SuppressWarnings("unchecked")
    private static List<String> lines(Session c) {
        return c.onClient(mc -> List.copyOf((List<String>) Mod.staticCall(FEATURE, "currentLines")));
    }

    /** On the client thread already (inside a waitUntil predicate). */
    @SuppressWarnings("unchecked")
    private static List<String> clientLines() {
        return List.copyOf((List<String>) Mod.staticCall(FEATURE, "currentLines"));
    }

    private static String plain(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            sb.append(l.replaceAll("§.", "")).append(" | ");
        }
        return sb.toString();
    }

    private static void floor(Session c, String floor) {
        sidebar(c, "template", "dungeon", "floor", floor);
        c.waitUntil("floor " + floor, mc -> floor.equals(Mod.staticCall("secrets.DungeonState", "getFloor")), 100);
    }

    private static void settings(Session c, boolean enabled, boolean max, boolean percent, boolean minions) {
        run(c, () -> {
            Object cfg = Mod.cfg(CFG);
            Mod.call(cfg, "setEnabled", enabled);
            Mod.call(cfg, "setShowMax", max);
            Mod.call(cfg, "setShowPercent", percent);
            Mod.call(cfg, "setShowMinions", minions);
        });
    }

    /** Waits until the plain HUD text equals {@code want} (lines joined by " | "), else fails naming what it shows. */
    private static void expect(Session c, String why, String want) {
        try {
            c.waitUntil(why, mc -> plain(clientLines()).equals(want), 60);
        } catch (AssertionError e) {
            c.check(false, why + ": wanted [" + want + "] got [" + plain(lines(c)) + "]");
        }
    }

    private static void f1Gate(Session c) {
        settings(c, true, true, false, true);
        floor(c, "F1");
        boolean inBoss = c.onClient(mc -> (Boolean) Mod.staticCall("livemap.LiveMapFeature", "isInBoss"));
        c.check(!inBoss, "premise: LiveMap says in boss on the platform");
        c.hx().removeStands();
        // Outside the boss room, a Bonzo tag right next to him: nothing.
        c.hx().stand(-417.5, 152, -419.5, "§e﴾ §c§lBonzo§r §a250k§c❤ §e﴿");
        c.ctx().waitTicks(20);
        c.check(lines(c).isEmpty(), "outside the boss room it showed: " + plain(lines(c)));
        // Into F1's boss room.
        c.hx().removeStands();
        c.hx().call("dungeon.tp", "x", -30.5, "y", 141.0, "z", 0.5, "yaw", 0f, "pitch", 0f);
        c.waitUntil("LiveMap boss latch in F1's boss room",
                mc -> (Boolean) Mod.staticCall("livemap.LiveMapFeature", "isInBoss"), 100);
        c.hx().stand(-28.5, 142, 0.5, "§e﴾ §c§lBonzo§r §a250k§c❤ §e﴿");
        expect(c, "Bonzo full", "Bonzo 250k/250k❤ | ");
        boolean drawn = false;
        run(c, () -> Mod.staticCall("hud.HudSeen", "forget", "boss_health"));
        try {
            c.waitUntil("the boss_health element drawn in game",
                    mc -> (Boolean) Mod.staticCall("hud.HudSeen", "drawnRecently", "boss_health"), 60);
            drawn = true;
        } catch (AssertionError e) {
            c.check(false, "the HUD element was never drawn in game (HudSeen) though its lines were " + plain(lines(c)));
        }
        // He hits it: the tag drops to 99.5k.
        c.hx().removeStands();
        c.hx().stand(-28.5, 142, 0.5, "§e﴾ §c§lBonzo§r §c99.5k§c❤ §e﴿");
        expect(c, "Bonzo hit", "Bonzo 99.5k/250k❤ | ");
        String colored = lines(c).get(0);
        c.check(colored.contains("§e99.5k"), "39.8% left should be yellow: " + colored);
        settings(c, true, false, true, true);
        expect(c, "Show Max off, Show Percent on", "Bonzo 99.5k❤ (40%) | ");
        // A player's chat-style name or another floor's boss is not Bonzo.
        c.hx().removeStands();
        c.hx().stand(-28.5, 142, 0.5, "[VIP] Bonzo: 1❤");
        c.hx().stand(-28.5, 142, 2.5, "§e﴾ §c§lSadan§r §a40M§c❤ §e﴿");
        c.ctx().waitTicks(20);
        c.check(lines(c).isEmpty(), "a forged / other-floor tag showed on F1: " + plain(lines(c)));
        // Switched off: nothing.
        c.hx().stand(-28.5, 142, 0.5, "§e﴾ §c§lBonzo§r §a250k§c❤ §e﴿");
        expect(c, "Bonzo back", "Bonzo 250k❤ (100%) | ");
        settings(c, false, true, false, true);
        c.ctx().waitTicks(20);
        c.check(lines(c).isEmpty(), "switched off it still showed: " + plain(lines(c)));
        c.note("platform: none; boss room: 'Bonzo 250k/250k❤', hit to 'Bonzo 99.5k/250k❤' (yellow), percent '(40%)',"
                + " forged/other-floor tags ignored, off -> none; drawn in game=" + drawn);
    }

    private static void f3Minions(Session c) {
        settings(c, true, true, false, true);
        floor(c, "F3");
        c.hx().removeStands();
        c.hx().stand(-28.5, 142, 0.5, "§e﴾ §c§lThe Professor§r §a3M§c❤ §e﴿");
        c.hx().stand(-26.5, 142, 0.5, "§cHealthy Guardian §a1M§c❤");
        c.hx().stand(-24.5, 142, 0.5, "§cReinforced Guardian §a600k§c❤");
        c.hx().stand(-22.5, 142, 0.5, "§cChaos Guardian §e0§c❤");
        c.hx().stand(-20.5, 142, 0.5, "§cLaser Guardian §a250k§c❤");
        expect(c, "F3 with minions", "The Professor 3M/3M❤ | Healthy Guardian 1M/1M❤ | Reinforced Guardian 600k/1.2M❤"
                + " | Chaos Guardian 0/1M❤ | Laser Guardian 250k/1M❤ | ");
        settings(c, true, true, false, false);
        expect(c, "F3 minions off", "The Professor 3M/3M❤ | ");
        // Master Mode max.
        floor(c, "M3");
        expect(c, "M3 Professor max", "The Professor 3M/600M❤ | ");
        c.note("F3: Professor + four Guardians in order, Reinforced max 1.2M (wiki); minions off -> Professor only; M3 max 600M");
    }

    private static void f5f6(Session c) {
        settings(c, true, true, false, true);
        floor(c, "F5");
        c.hx().removeStands();
        c.hx().stand(-28.5, 142, 0.5, "§e﴾ §c§lLivid§r §a3.5M§c❤ §e﴿");
        expect(c, "F5 one Livid", "Livid 3.5M/7M❤ | ");
        floor(c, "F6");
        c.hx().removeStands();
        c.hx().stand(-28.5, 142, 0.5, "§e﴾ §c§lSadan§r §a40M§c❤ §e﴿");
        c.hx().stand(-26.5, 142, 0.5, "§c§3§lThe Diamond Giant §a12.5M§c❤");
        c.hx().stand(-24.5, 142, 0.5, "§c§4§lL.A.S.R. §a25M§c❤");
        expect(c, "F6 Sadan + giants", "Sadan 40M/40M❤ | The Diamond Giant 12.5M/25M❤ | L.A.S.R. 25M/25M❤ | ");
        c.hx().removeStands();
        c.note("F5 Livid 3.5M/7M; F6 Sadan and two giants");
    }

    private static void bars(Session c) {
        settings(c, true, true, false, true);
        c.hx().removeStands();
        floor(c, "F7");
        c.hx().call("boss.bar.set", "key", "w", "name", "§c§lMaxor", "progress", 0.5);
        expect(c, "F7 Maxor at half", "Maxor 50M/100M❤ | ");
        floor(c, "M7");
        c.hx().call("boss.bar.set", "key", "w", "name", "§d§lNecron", "progress", 0.25);
        expect(c, "M7 Necron at a quarter", "Necron 350M/1.4B❤ | ");
        // A bar that is not a boss's (Sadan's interest bar) shows nothing on F7.
        c.hx().call("boss.bar.set", "key", "w", "name", "§cSadan's Interest Level", "progress", 0.5);
        c.ctx().waitTicks(20);
        c.check(lines(c).isEmpty(), "a non-boss bar showed: " + plain(lines(c)));
        floor(c, "F4");
        c.hx().call("boss.bar.set", "key", "w", "name", "§c§lThorn", "progress", 0.75);
        expect(c, "F4 Thorn three lives of four", "Thorn 3/4 lives | ");
        c.hx().call("boss.bar.clear");
        c.ctx().waitTicks(10);
        c.check(lines(c).isEmpty(), "the bar was removed but it still showed: " + plain(lines(c)));
        c.note("F7 Maxor 50M/100M; M7 Necron 350M/1.4B; Sadan's Interest bar ignored; F4 Thorn 3/4 lives; bar removed -> none");
    }

    private static void cleanup(Session c) {
        settings(c, false, true, false, true);
        c.hx().removeStands();
        c.hx().call("boss.bar.clear");
        hub(c);
        c.hx().call("dungeon.tp", "x", -419.5, "y", 151.0, "z", -419.5, "yaw", 0f, "pitch", 0f);
        c.ctx().waitTicks(10);
        floor(c, "F7");
        c.waitUntil("boss latch cleared after leaving",
                mc -> !(Boolean) Mod.staticCall("livemap.LiveMapFeature", "isInBoss"), 100);
        hub(c);
        c.note("left the dungeon, back on the platform, LiveMap boss latch cleared");
    }
}
