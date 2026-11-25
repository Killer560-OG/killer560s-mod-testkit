package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;

import java.util.List;
import java.util.Map;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Dungeon Mob Highlight's starred tag to mob match (mod highlight-random-mobs-1125; killer560 2026-11-25: "the starred mob
 * highlight ... highlighting random mobs and things"). Client-only entities with chosen ids, so the id order Hypixel uses
 * (a mob's tag one id after it) is exact.
 * <ul>
 *   <li>1125-esp-starred-death-neighbor: a starred zombie dies while its tag stays a moment, a plain zombie (no tag) 1.5
 *   blocks away. The plain zombie must not become starred (origin: the left-over tag was matched again to the nearest free
 *   mob, which was then highlighted and remembered until it died).</li>
 *   <li>1125-esp-starred-plain-tag-neighbor: a starred tag whose id - 1 is not its mob (a later stand) has a plain mob WITH
 *   its own unstarred tag closer than its own body. The plain mob must not be taken; its real body is.</li>
 * </ul>
 * A Mimic-free pet-style armor stand (a named stand with a player head) and a real-looking player NPC stand nearby in both.
 */
final class HxStarredMatch1125Cases {

    private static final String ESP = "mobesp.MobEspFeature";
    private static final String CFG = "mobesp.MobEspConfig";
    private static final int ID = 925_000;

    private HxStarredMatch1125Cases() {
    }

    static void register(Session s) {
        s.test("1125-esp-starred-death-neighbor", HxStarredMatch1125Cases::deathNeighbor);
        s.test("1125-esp-starred-plain-tag-neighbor", HxStarredMatch1125Cases::plainTagNeighbor);
    }

    /** Starred Mobs on (it has no plain getter for Mod.with), Hide Ghost Mobs off; both put back after. */
    private static Settings settings(Session c) {
        boolean was = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(CFG), "getStarredMobsRaw"));
        return new Settings(c).with(CFG, "HideGhostMobs", false)
                .custom(() -> Mod.call(Mod.cfg(CFG), "setStarredMobs", true), () -> Mod.call(Mod.cfg(CFG), "setStarredMobs", was));
    }

    private static void zombie(Session c, int id, double x, double y, double z) {
        c.onClient(mc -> {
            net.minecraft.world.entity.monster.zombie.Zombie m = new net.minecraft.world.entity.monster.zombie.Zombie(
                    EntityType.ZOMBIE, mc.level);
            m.setId(id);
            m.setPos(x, y, z);
            mc.level.addEntity(m);
            return null;
        });
    }

    private static void tag(Session c, int id, double x, double y, double z, String name) {
        c.onClient(mc -> {
            ArmorStand s = new ArmorStand(mc.level, x, y, z);
            s.setId(id);
            s.setInvisible(true);
            if (name != null) {
                s.setCustomName(Component.literal(name));
                s.setCustomNameVisible(true);
            }
            mc.level.addEntity(s);
            return null;
        });
    }

    /** A pet-style stand: named, wearing a player head, beside the pack (never a star mob: it is an armor stand). */
    private static void pet(Session c, int id, double x, double y, double z) {
        c.onClient(mc -> {
            ArmorStand s = new ArmorStand(mc.level, x, y, z);
            s.setId(id);
            s.setInvisible(true);
            s.setCustomName(Component.literal("[Lv100] HxMateA's Golden Dragon"));
            s.setCustomNameVisible(true);
            s.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,
                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PLAYER_HEAD));
            mc.level.addEntity(s);
            return null;
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, Integer> drawn() {
        return (Map<Integer, Integer>) Mod.staticCall(ESP, "drawnColors");
    }

    @SuppressWarnings("unchecked")
    private static boolean listed(net.minecraft.client.Minecraft mc, int id) {
        for (Entity e : (List<Entity>) Mod.staticCall(ESP, "starredMobs", mc)) {
            if (e.getId() == id) {
                return true;
            }
        }
        return false;
    }

    private static void remove(Session c, int... ids) {
        run(c, () -> {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            for (int id : ids) {
                if (mc.level != null && mc.level.getEntity(id) != null) {
                    mc.level.removeEntity(id, Entity.RemovalReason.KILLED);
                }
            }
        });
    }

    /** In front of him, at his feet height, a short way out: open air in the hx world, so line of sight is clear. */
    private static double[] spot(Session c) {
        return c.onClient(mc -> {
            BlockPos f = mc.player.blockPosition();
            return new double[]{f.getX() + 0.5, f.getY(), f.getZ() + 0.5};
        });
    }

    private static void deathNeighbor(Session c) {
        int star = ID, starTag = ID + 1, plain = ID + 10, petId = ID + 20;
        try (Settings set = settings(c)) {
            dungeon(c);
            double[] p = spot(c);
            double x = p[0] + 3, y = p[1], z = p[2];
            zombie(c, star, x, y, z);
            tag(c, starTag, x, y + 2.0, z, "✯ Zombie Knight 1.3M❤");
            zombie(c, plain, x + 1.5, y, z + 0.5);
            pet(c, petId, x - 1.0, y + 0.5, z - 1.0);
            int color = c.onClient(mc -> (Integer) Mod.call(Mod.cfg(CFG), "getStarredColor"));
            c.waitUntil("the starred zombie highlighted with its tag", mc -> Integer.valueOf(color).equals(drawn().get(star)), 40);
            Object[] before = c.onClient(mc -> new Object[]{drawn().get(plain), drawn().get(petId), listed(mc, plain)});
            c.check(before[0] == null && before[1] == null && !(Boolean) before[2], "control: the plain zombie or the pet was"
                    + " starred while the starred zombie lived (plain " + before[0] + ", pet " + before[1] + ", listed "
                    + before[2] + ")");
            // The starred zombie dies; its tag stays (Hypixel's tag can outlive its mob for a moment).
            remove(c, star);
            c.ctx().waitTicks(10);
            Object[] after = c.onClient(mc -> new Object[]{drawn().get(plain), listed(mc, plain), drawn().get(petId),
                    mc.level.getEntity(starTag) != null, drawn().size()});
            c.note("starred zombie #" + star + " dead, its tag #" + starTag + " still there " + after[3] + "; plain zombie #"
                    + plain + " drawn " + after[0] + ", listed " + after[1] + "; pet " + after[2] + "; drawn now " + after[4]);
            c.check((Boolean) after[3], "the left-over tag was not on the client: nothing was tested");
            c.check(after[0] == null, "the plain zombie beside the dead starred one is highlighted as starred");
            c.check(!(Boolean) after[1], "the plain zombie beside the dead starred one is in starredMobs() (Auto Clear's list)");
            c.check(after[2] == null, "the pet stand was highlighted");
            // Its tag gone too, then a plain zombie still never starred.
            remove(c, starTag);
            c.ctx().waitTicks(5);
            c.check(c.onClient(mc -> drawn().get(plain)) == null, "the plain zombie is highlighted after the tag went too");
        } finally {
            remove(c, star, starTag, plain, petId);
        }
    }

    private static void plainTagNeighbor(Session c) {
        int body = ID + 30, filler = ID + 31, plain = ID + 32, plainTag = ID + 33, starTag = ID + 40;
        try (Settings set = settings(c)) {
            dungeon(c);
            double[] p = spot(c);
            double x = p[0] - 3, y = p[1], z = p[2];
            // Its body 1.2 blocks off its tag (a fast mob the tag trails), with an unnamed stand at id + 1 (so id - 1 of a
            // later tag is no help); a plain mob with its own unstarred tag 0.6 blocks off the starred tag.
            zombie(c, body, x - 1.2, y, z);
            tag(c, filler, x - 1.2, y + 0.5, z, null);
            zombie(c, plain, x + 0.6, y, z);
            tag(c, plainTag, x + 0.6, y + 2.0, z, "[Lv173] Undead Skeleton 360k❤");
            tag(c, starTag, x, y + 2.0, z, "✯ Speedy Skeleton Soldier 750k❤");
            int color = c.onClient(mc -> (Integer) Mod.call(Mod.cfg(CFG), "getStarredColor"));
            c.ctx().waitTicks(10);
            Object[] r = c.onClient(mc -> new Object[]{drawn().get(body), drawn().get(plain), listed(mc, body), listed(mc, plain)});
            c.note("starred tag #" + starTag + ": its body #" + body + " drawn " + r[0] + " listed " + r[2] + "; plain #" + plain
                    + " (own tag #" + plainTag + ") drawn " + r[1] + " listed " + r[3]);
            c.check(r[1] == null && !(Boolean) r[3], "the plain mob with its own unstarred tag was taken as the starred tag's body");
            c.check(Integer.valueOf(color).equals(r[0]) && (Boolean) r[2], "the starred tag's own body (1.2 blocks off) was not"
                    + " starred");
        } finally {
            remove(c, body, filler, plain, plainTag, starTag);
        }
    }
}
