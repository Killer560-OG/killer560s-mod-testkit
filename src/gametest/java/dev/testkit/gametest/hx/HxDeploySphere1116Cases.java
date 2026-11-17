package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Mod deploysphere-1116 (killer560 11-16: "the sphere for the deployables is constantly bugging out and changing").
 * 1116-deploy-sphere-steady: three deployables of three kinds stand side by side as Hypixel sends them (the stand bobs
 * and sways every tick, its countdown name changes each second); the sphere of each must keep one center, one radius and
 * one color for 100 ticks, and three spheres are drawn every frame.
 */
final class HxDeploySphere1116Cases {

    private static final String MD = "mining.deployables.MiningDeployables";
    private static final String STATE = "miningqol.MiningState";
    private static final String[] TAGS = {"k1116a", "k1116b", "k1116c"};
    private static final String[] NAMES = {"Mithril Lantern", "Plasmaflux", "Radiant"};

    private HxDeploySphere1116Cases() {
    }

    static void register(Session s) {
        s.test("1116-deploy-sphere-steady", HxDeploySphere1116Cases::steady);
    }

    @SuppressWarnings("unchecked")
    private static List<String> draws(Session c) {
        return c.onClient(mc -> List.copyOf((List<String>) Mod.staticCall(MD, "sphereDraws")));
    }

    @SuppressWarnings("unchecked")
    private static void steady(Session c) {
        double[] at = c.onClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
        double[][] base = {{at[0] + 3, at[1] + 1, at[2]}, {at[0] - 4, at[1] + 1, at[2]}, {at[0], at[1] + 1, at[2] + 5}};
        Object cfg = c.onClient(mc -> Mod.field(MD, "CFG"));
        try (Settings set = new Settings(c).custom(() -> {
            Mod.call(cfg, "set", "enabled", true);
            Mod.call(cfg, "set", "circle", true);
            Mod.call(cfg, "set", "style", 1);
            Mod.call(cfg, "set", "others", true);
        }, () -> Mod.call(cfg, "load"))) {
            c.hx().sidebar("SKYBLOCK", "Late Winter 3rd", "⏣ Royal Mines", "Purse: 0");
            c.hx().tab(List.of("Area: Dwarven Mines", "Profile: Test", ""), null, null);
            c.waitUntil("Dwarven Mines", mc -> "DWARVEN_MINES".equals(Mod.staticCall(STATE, "island")), 100);
            for (int i = 0; i < 3; i++) {
                c.server().command(String.format(Locale.ROOT, "summon armor_stand %.2f %.2f %.2f {Tags:[\"%s\",\"k1116\"],"
                                + "CustomName:'\"%s 300s\"',CustomNameVisible:1b,NoGravity:1b,Invisible:1b}",
                        base[i][0], base[i][1], base[i][2], TAGS[i], NAMES[i]));
            }
            c.waitUntil("three tracked", mc -> ((List<?>) Mod.staticCall(MD, "tracked")).size() == 3, 80);
            c.waitUntil("three spheres drawn", mc -> ((Number) Mod.staticCall(MD, "sphereCount")).intValue() == 3, 40);

            Set<String>[] seen = new Set[3];
            for (int i = 0; i < 3; i++) {
                seen[i] = new TreeSet<>();
            }
            Set<Integer> counts = new HashSet<>();
            for (int t = 0; t < 100; t++) {
                for (int i = 0; i < 3; i++) {
                    // The stand bobs 0.3 up and down and sways 0.1 sideways, a different phase each.
                    double y = base[i][1] + 0.3 * Math.sin(t * 0.4 + i);
                    double x = base[i][0] + 0.1 * Math.cos(t * 0.3 + i);
                    c.server().command(String.format(Locale.ROOT, "tp @e[tag=%s] %.3f %.3f %.3f", TAGS[i], x, y, base[i][2]));
                    if (t % 20 == 0) {
                        c.server().command(String.format(Locale.ROOT, "data merge entity @e[tag=%s,limit=1] {CustomName:'\"%s %ds\"'}",
                                TAGS[i], NAMES[i], 290 - t / 2));
                    }
                }
                c.ctx().waitTicks(1);
                counts.add(c.onClient(mc -> ((Number) Mod.staticCall(MD, "sphereCount")).intValue()));
                for (String d : draws(c)) {
                    for (int i = 0; i < 3; i++) {
                        if (d.startsWith(NAMES[i] + "|")) {
                            seen[i].add(d);
                        }
                    }
                }
            }
            for (int i = 0; i < 3; i++) {
                c.check(!seen[i].isEmpty(), NAMES[i] + " drew no sphere");
                c.check(seen[i].size() == 1, NAMES[i] + " sphere changed " + seen[i].size() + " times in 100 ticks: " + seen[i]);
            }
            c.check(counts.equals(Set.of(3)), "sphere count per frame was not a steady 3: " + counts);
        } finally {
            c.server().command("kill @e[tag=k1116]");
            c.server().command(String.format(Locale.ROOT, "tp @a %.2f %.2f %.2f", at[0], at[1], at[2]));
            c.ctx().runOnClient(mc -> Mod.call(cfg, "load"));
            hub(c);
        }
    }
}
