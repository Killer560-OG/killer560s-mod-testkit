package dev.testkit.gametest.hx;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * 1117-arrow-particles (mod arrowpart-1117): Object Hider's Arrow Particles switch. A server-summoned critical arrow
 * flies across the room; the client engine's crit particles are counted every tick for 12 ticks. OFF: crit particles are
 * seen (so the arrow acted) and the arrow moved; ON: none, with the same arrow flight.
 */
final class HxArrowParticles1117Cases {

    private static final String OH = "objecthider.ObjectHiderConfig";

    private HxArrowParticles1117Cases() {
    }

    static void register(Session s) {
        s.test("1117-arrow-particles", HxArrowParticles1117Cases::arrowParticles);
    }

    /** CritParticle instances now in the client's particle engine (reflection into ParticleEngine.particles). */
    @SuppressWarnings("unchecked")
    private static int crits(net.minecraft.client.Minecraft mc) {
        try {
            java.lang.reflect.Field f = net.minecraft.client.particle.ParticleEngine.class.getDeclaredField("particles");
            f.setAccessible(true);
            java.lang.reflect.Field q = net.minecraft.client.particle.ParticleGroup.class.getDeclaredField("particles");
            q.setAccessible(true);
            int n = 0;
            for (Object g : ((java.util.Map<Object, Object>) f.get(mc.particleEngine)).values()) {
                for (Object p : (java.util.Collection<Object>) q.get(g)) {
                    if (p instanceof net.minecraft.client.particle.CritParticle) {
                        n++;
                    }
                }
            }
            return n;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** {crit particles seen over the flight, blocks the arrow moved}. */
    private static int[] fly(Session c, String tag) {
        c.waitUntil("no arrow on the client", mc -> arrow(mc) == null, 60);
        BlockPos f = c.onClient(mc -> mc.player.blockPosition());
        c.onClient(mc -> {
            mc.particleEngine.clearParticles();
            return null;
        });
        c.server().command("summon arrow " + (f.getX() + 0.5) + " " + (f.getY() + 6) + " " + (f.getZ() + 0.5)
                + " {Tags:[\"" + tag + "\"],crit:1b,NoGravity:1b,Motion:[0.4d,0.0d,0.0d]}");
        c.waitUntil("the arrow reached the client", mc -> arrow(mc) != null, 60);
        double x0 = c.onClient(mc -> arrow(mc).getX());
        int seen = 0;
        for (int i = 0; i < 12; i++) {
            c.ctx().waitTicks(1);
            seen = Math.max(seen, c.onClient(HxArrowParticles1117Cases::crits));
        }
        double moved = c.onClient(mc -> arrow(mc).getX()) - x0;
        c.server().command("kill @e[tag=" + tag + "]");
        c.waitUntil("the arrow left the client", mc -> arrow(mc) == null, 60);
        return new int[]{seen, (int) Math.round(moved)};
    }

    private static Entity arrow(net.minecraft.client.Minecraft mc) {
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof AbstractArrow) {
                return e;
            }
        }
        return null;
    }

    private static void arrowParticles(Session c) {
        c.server().command("kill @e[type=arrow]");
        try (Settings set = new Settings(c).with(OH, "ObjectHiderEnabled", true).with(OH, "HideArrowParticles", false)) {
            dungeon(c);
            net.minecraft.server.level.ParticleStatus before = c.onClient(mc -> mc.options.particles().get());
            c.ctx().runOnClient(mc -> mc.options.particles().set(net.minecraft.server.level.ParticleStatus.ALL));
            try {
                int[] off = fly(c, "ap1117off");
                c.check(off[1] >= 2, "OFF: the arrow did not fly (moved " + off[1] + " blocks)");
                c.check(off[0] > 0, "OFF: no crit particles were seen from a critical arrow in flight");
                set.with(OH, "HideArrowParticles", true);
                int[] on = fly(c, "ap1117on");
                c.check(on[1] >= 2, "ON: the arrow did not fly (moved " + on[1] + " blocks)");
                c.check(on[0] == 0, "ON: " + on[0] + " crit particles were still added");
                c.note("critical arrow: OFF moved " + off[1] + " blocks, up to " + off[0] + " crit particles at once; ON moved "
                        + on[1] + " blocks, " + on[0] + " crit particles");
            } finally {
                c.ctx().runOnClient(mc -> mc.options.particles().set(before));
            }
        } finally {
            c.server().command("kill @e[type=arrow]");
        }
    }
}
