package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;
import net.minecraft.core.BlockPos;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * 1122-invisibug-* (mod invisibug-1122): Invisibug Highlight. A server-summoned invisible bat and the marker particle are
 * boxed on Galatea only, and only with the switch on; a visible bat and an invisible armor stand are never boxed.
 * Asserts the boxes were drawn from the tracker (boxCount > 0), not just that nothing threw.
 */
final class HxInvisibug1122Cases {

    private static final String K = "foraging.InvisibugHighlight";

    private HxInvisibug1122Cases() {
    }

    static void register(Session s) {
        s.test("1122-invisibug-body", HxInvisibug1122Cases::body);
        s.test("1122-invisibug-marker", HxInvisibug1122Cases::marker);
    }

    private static void island(Session c, String area, String graph) {
        sidebar(c, "template", "hub");
        tab(c, "template", "area", "area", area);
        c.waitUntil("IslandDetector reads " + graph, mc -> graph.equals(Mod.staticCall("pathfinding.IslandDetector",
                "graphIsland")), 100);
    }

    private static Settings on(Session c, boolean enabled) {
        Object cfg = c.onClient(mc -> Mod.field(K, "CFG"));
        boolean was = c.onClient(mc -> (Boolean) Mod.call(cfg, "isEnabledRaw"));
        return new Settings(c).custom(() -> Mod.call(cfg, "set", "enabled", enabled),
                () -> Mod.call(cfg, "set", "enabled", was));
    }

    private static int count(Session c, String m) {
        return c.onClient(mc -> ((Number) Mod.staticCall(K, m)).intValue());
    }

    private static void body(Session c) {
        c.server().command("kill @e[tag=ib1122]");
        BlockPos p = c.onClient(mc -> mc.player.blockPosition());
        String at = (p.getX() + 3.5) + " " + p.getY() + " " + (p.getZ() + 0.5);
        try (Settings set = on(c, true)) {
            island(c, "Galatea", "GALATEA");
            c.server().command("summon bat " + at + " {Tags:[\"ib1122\"],NoAI:1b,Silent:1b,Invisible:1b,active_effects:[{id:\"invisibility\",duration:2000,show_particles:0b}]}");
            c.server().command("summon bat " + (p.getX() + 3.5) + " " + p.getY() + " " + (p.getZ() + 3.5)
                    + " {Tags:[\"ib1122\"],NoAI:1b,Silent:1b}");
            c.server().command("summon armor_stand " + (p.getX() - 2.5) + " " + p.getY() + " " + (p.getZ() + 0.5)
                    + " {Tags:[\"ib1122\"],Invisible:1b,NoGravity:1b}");
            c.waitUntil("the invisible bat is boxed", mc -> count(c, "bodyCount") >= 1, 100);
            c.ctx().waitTicks(6);
            c.check(count(c, "bodyCount") == 1, "bodies boxed: " + count(c, "bodyCount")
                    + " (want 1: the invisible bat only, not the visible bat or the invisible armor stand)");
            c.check(count(c, "boxCount") == 1, "boxes drawn: " + count(c, "boxCount"));
            island(c, "Hub", "HUB");
            c.waitUntil("off Galatea nothing is boxed", mc -> count(c, "boxCount") == 0, 60);
            island(c, "Galatea", "GALATEA");
            c.waitUntil("back on Galatea the bat is boxed again", mc -> count(c, "boxCount") == 1, 100);
            try (Settings off = on(c, false)) {
                c.waitUntil("switch OFF: nothing is boxed", mc -> count(c, "boxCount") == 0, 60);
            }
        } finally {
            c.server().command("kill @e[tag=ib1122]");
        }
    }

    private static void marker(Session c) {
        BlockPos p = c.onClient(mc -> mc.player.blockPosition());
        String at = (p.getX() + 4.5) + " " + (p.getY() + 1) + " " + (p.getZ() + 0.5);
        try (Settings set = on(c, true)) {
            island(c, "Galatea", "GALATEA");
            c.server().command("particle flame " + at + " 0 0 0 0 1 force");
            c.ctx().waitTicks(6);
            c.check(count(c, "boxCount") == 0, "a flame particle was boxed");
            c.server().command("particle cloud " + at + " 0 0 0 0 1 force");
            c.waitUntil("the marker particle is boxed", mc -> count(c, "boxCount") == 1, 60);
            c.check(count(c, "bodyCount") == 0, "no body was expected");
            c.waitUntil("the marker expires", mc -> count(c, "boxCount") == 0, 120);
            island(c, "Hub", "HUB");
            c.server().command("particle cloud " + at + " 0 0 0 0 1 force");
            c.ctx().waitTicks(10);
            c.check(count(c, "boxCount") == 0, "a marker was boxed off Galatea");
        }
    }
}
