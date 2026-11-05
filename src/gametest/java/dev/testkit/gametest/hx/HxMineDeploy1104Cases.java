package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;

import java.util.List;
import java.util.Locale;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Mod minedeploy-1104 (killer560, 2026-11-04: lantern area circles and a HUD timer, "Always" or "only if you are in its
 * radius"; the circle in the Door Keys styles, Circle or Sphere).
 * <ul>
 *   <li>1104-minedeploy: on a (faked) Dwarven Mines, armor stands named like Hypixel's labels ("Mithril Lantern 300s") are
 *       tracked and a bare "Mithril Lantern" is not; a power orb only with Other Deployables on, and off a mining island
 *       only power orbs; a circle (and with Sphere, a sphere) is drawn every frame; standing near one flips it "in", moving
 *       away flips it "out"; the HUD lists both on Always, only the one you are in on Only In Radius, and counts down; the
 *       switch OFF draws and lists nothing.</li>
 * </ul>
 */
final class HxMineDeploy1104Cases {

    private static final String MD = "mining.deployables.MiningDeployables";
    private static final String STATE = "miningqol.MiningState";

    private HxMineDeploy1104Cases() {
    }

    static void register(Session s) {
        s.test("1104-minedeploy", HxMineDeploy1104Cases::deploy);
    }

    @SuppressWarnings("unchecked")
    private static List<String> tracked(Session c) {
        return c.onClient(mc -> List.copyOf((List<String>) Mod.staticCall(MD, "tracked")));
    }

    @SuppressWarnings("unchecked")
    private static List<String> hud(Session c) {
        return c.onClient(mc -> List.copyOf((List<String>) Mod.staticCall(MD, "hudLines")));
    }

    private static boolean has(List<String> l, String prefix) {
        return l.stream().anyMatch(s -> s.startsWith(prefix));
    }

    private static int secsLeft(Session c, String label) {
        for (String s : tracked(c)) {
            if (s.startsWith(label + "|")) {
                return Integer.parseInt(s.substring(s.lastIndexOf('|') + 1));
            }
        }
        return -1;
    }

    private static long frames(Session c) {
        return c.onClient(mc -> ((Number) Mod.staticCall(MD, "framesDrawn")).longValue());
    }

    private static void stand(Session c, double x, double y, double z, String name) {
        c.server().command(String.format(Locale.ROOT, "summon armor_stand %.2f %.2f %.2f {Tags:[\"k1104md\"],CustomName:\"%s\","
                + "CustomNameVisible:1b,NoGravity:1b,Invisible:1b}", x, y, z, name));
    }

    private static void tp(Session c, double x, double y, double z) {
        c.server().command(String.format(Locale.ROOT, "tp @a %.2f %.2f %.2f", x, y, z));
    }

    @SuppressWarnings("unchecked")
    private static void deploy(Session c) {
        double[] at = c.onClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
        Object cfg = c.onClient(mc -> Mod.field(MD, "CFG"));
        Settings s0 = new Settings(c).custom(() -> {
            Mod.call(cfg, "set", "enabled", true);
            Mod.call(cfg, "set", "circle", true);
            Mod.call(cfg, "set", "style", 0);
            Mod.call(cfg, "set", "timer", true);
            Mod.call(cfg, "set", "timerShows", 0);
            Mod.call(cfg, "set", "others", false);
        }, () -> Mod.call(cfg, "load"));
        try (Settings set = s0) {
            c.hx().sidebar("SKYBLOCK", "Late Winter 3rd", "⏣ Royal Mines", "Purse: 0");
            c.hx().tab(List.of("Area: Dwarven Mines", "Profile: Test", ""), null, null);
            c.waitUntil("Dwarven Mines", mc -> "DWARVEN_MINES".equals(Mod.staticCall(STATE, "island")), 100);

            stand(c, at[0] + 3, at[1] + 1, at[2], "Mithril Lantern 300s");
            stand(c, at[0] + 45, at[1] + 1, at[2], "Glacite Lantern 300s");
            stand(c, at[0] - 4, at[1] + 1, at[2], "Plasmaflux 60s");
            stand(c, at[0], at[1] + 1, at[2] + 4, "Mithril Lantern");
            c.waitUntil("both lanterns tracked", mc -> {
                List<String> t = (List<String>) Mod.staticCall(MD, "tracked");
                return has(t, "Mithril Lantern|") && has(t, "Glacite Lantern|");
            }, 80);
            c.waitUntil("Mithril in range, Glacite out", mc -> {
                List<String> t = (List<String>) Mod.staticCall(MD, "tracked");
                return has(t, "Mithril Lantern|in|") && has(t, "Glacite Lantern|out|");
            }, 40);
            List<String> t0 = tracked(c);
            c.check(t0.size() == 2, "tracked exactly the two timed lanterns (no bare name, no orb with Others off): " + t0);

            // The circle: drawn every frame, on the ground under the stand (within a block and a half of the feet).
            long f0 = frames(c);
            c.ctx().waitTicks(10);
            c.check(frames(c) > f0, "no circle drawn");
            c.check("circle".equals(c.onClient(mc -> Mod.staticCall(MD, "lastDrawnStyle"))), "the Circle style drew no circle");
            double ry = c.onClient(mc -> ((Number) Mod.staticCall(MD, "ringY", "Mithril Lantern")).doubleValue());
            c.check(ry >= at[1] - 1.5 && ry <= at[1] + 1.5, "circle height " + ry + " is not at the ground (feet " + at[1] + ")");
            c.ctx().runOnClient(mc -> Mod.call(cfg, "set", "style", 1));
            c.ctx().waitTicks(5);
            c.check("sphere".equals(c.onClient(mc -> Mod.staticCall(MD, "lastDrawnStyle"))), "the Sphere style drew no sphere");
            c.ctx().runOnClient(mc -> Mod.call(cfg, "set", "style", 0));

            // HUD: Always lists both, and counts down.
            c.waitUntil("HUD lists both", mc -> {
                List<String> h = (List<String>) Mod.staticCall(MD, "hudLines");
                return h.size() == 2;
            }, 20);
            List<String> h = hud(c);
            c.check(h.stream().anyMatch(s -> s.contains("Mithril Lantern") && s.contains("(in range)")), "HUD Mithril line: " + h);
            c.check(h.stream().anyMatch(s -> s.contains("Glacite Lantern") && !s.contains("(in range)")), "HUD Glacite line: " + h);
            int s1 = secsLeft(c, "Mithril Lantern");
            c.ctx().waitTicks(50);
            int s2 = secsLeft(c, "Mithril Lantern");
            c.check(s1 > 0 && s2 >= 0 && s2 < s1, "timer did not count down: " + s1 + " -> " + s2);

            // Only In Radius: only the one you are in.
            c.ctx().runOnClient(mc -> Mod.call(cfg, "set", "timerShows", 1));
            c.waitUntil("HUD lists only Mithril", mc -> {
                List<String> l = (List<String>) Mod.staticCall(MD, "hudLines");
                return l.size() == 1 && l.get(0).contains("Mithril Lantern");
            }, 20);

            // Walk over to the Glacite one: the two flip.
            tp(c, at[0] + 40, at[1], at[2]);
            c.waitUntil("Mithril out, Glacite in", mc -> {
                List<String> t = (List<String>) Mod.staticCall(MD, "tracked");
                return has(t, "Mithril Lantern|out|") && has(t, "Glacite Lantern|in|");
            }, 60);
            c.waitUntil("HUD lists only Glacite", mc -> {
                List<String> l = (List<String>) Mod.staticCall(MD, "hudLines");
                return l.size() == 1 && l.get(0).contains("Glacite Lantern");
            }, 20);
            tp(c, at[0], at[1], at[2]);

            // Other Deployables: the power orb joins.
            c.ctx().runOnClient(mc -> Mod.call(cfg, "set", "others", true));
            c.waitUntil("Plasmaflux tracked", mc -> has((List<String>) Mod.staticCall(MD, "tracked"), "Plasmaflux|"), 40);
            // Off a mining island, lanterns stay away; the orb stays (Others on).
            hub(c);
            c.waitUntil("only the orb in the Hub", mc -> {
                List<String> t = (List<String>) Mod.staticCall(MD, "tracked");
                return t.size() == 1 && has(t, "Plasmaflux|");
            }, 40);
            c.ctx().runOnClient(mc -> Mod.call(cfg, "set", "others", false));
            c.waitUntil("nothing tracked in the Hub", mc -> ((List<?>) Mod.staticCall(MD, "tracked")).isEmpty(), 40);

            // Switch OFF on the mines: nothing drawn, nothing listed.
            c.hx().tab(List.of("Area: Dwarven Mines", "Profile: Test", ""), null, null);
            c.waitUntil("Dwarven Mines again", mc -> "DWARVEN_MINES".equals(Mod.staticCall(STATE, "island")), 100);
            c.waitUntil("tracked again", mc -> ((List<?>) Mod.staticCall(MD, "tracked")).size() == 2, 40);
            c.ctx().runOnClient(mc -> Mod.call(cfg, "set", "enabled", false));
            c.ctx().waitTicks(5);
            long f1 = frames(c);
            c.ctx().waitTicks(20);
            c.check(frames(c) == f1, "drew with the switch OFF");
            c.check(hud(c).isEmpty() && tracked(c).isEmpty(), "HUD or tracking left with the switch OFF");
        } finally {
            c.server().command("kill @e[tag=k1104md]");
            tp(c, at[0], at[1], at[2]);
            c.ctx().runOnClient(mc -> Mod.call(cfg, "load"));
            hub(c);
        }
    }
}
