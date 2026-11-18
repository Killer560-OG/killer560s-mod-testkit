package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;

/**
 * How Lucky Are You counted almost no kills (mod branch luckcount-1118): only a melee hit marked a mob as his, but he clears
 * with Hyperion's Implosion, Spirit Sceptre and bows, and Hypixel's drop line comes a tick before the kill is read. His
 * 11-16..18 logs: ~6,100 kills in finished Kill Combos, 86 counted; 14 of ~250 drop lines lost to ChatObserver's 250 ms
 * de-duplication; 86 drops filed under "other" (mob unknown at the drop line); armor pieces ("Zombie Soldier Boots") had no
 * row (the table holds one "Zombie Soldier Armor Piece" row); Soulstealer Bow / Dreadlord Sword / Sniper Bow were left out of
 * the table as "no drop message" though Hypixel prints them. Cases (hx session, 26.3 and 26.2, cheat and legit):
 * <ul>
 *   <li>1118-luck-ability: "Your Implosion hit 2 enemies ..." then the server kills a Zealot and a Water Hydra near him and
 *       an Enderman 30 blocks away: the two near ones count one kill each, the far one nothing; a corpse named by a second
 *       ability line is not counted twice; killsCounted equals the mobs killed.</li>
 *   <li>1118-luck-bow: a bow use aimed at a Zealot marks it (its kill counts one); the same use aimed away counts nothing.</li>
 *   <li>1118-luck-dropfirst: the drop line (Beating Heart, which five mobs drop) arrives BEFORE the kill is read and still
 *       lands on the mob that died, not on "other".</li>
 *   <li>1118-luck-dropforms: two identical drop lines a few ms apart count twice; an armor piece counts on its Armor Piece
 *       row; Soulstealer Bow counts with an expectation.</li>
 * </ul>
 */
final class HxLuck1118Cases {

    private static final String LS = "luck.LuckStore";
    private static final String LD = "luck.LuckDetector";
    private static final String LK = "luck.LuckKills";
    private static final String CFG = "luck.LuckConfig";

    private HxLuck1118Cases() {
    }

    static void register(Session s) {
        s.test("1118-luck-ability", HxLuck1118Cases::ability);
        s.test("1118-luck-bow", HxLuck1118Cases::bow);
        s.test("1118-luck-dropfirst", HxLuck1118Cases::dropFirst);
        s.test("1118-luck-dropforms", HxLuck1118Cases::dropForms);
    }

    private static Object row(Session c, String key) {
        return c.onClient(mc -> {
            @SuppressWarnings("unchecked")
            List<Object> rows = (List<Object>) Mod.staticCall(LS, "rows", null,
                    Mod.enumValue(CFG + "$ChestOdds", "BASE"), false);
            for (Object r : rows) {
                if (key.equals(Mod.call(r, "key"))) {
                    return r;
                }
            }
            return null;
        });
    }

    private static long got(Session c, String key) {
        Object r = row(c, key);
        return r == null ? 0 : ((Number) Mod.call(r, "got")).longValue();
    }

    private static double expected(Session c, String key) {
        Object r = row(c, key);
        return r == null ? 0 : ((Number) Mod.call(r, "expected")).doubleValue();
    }

    private static long attempts(Session c, String activity) {
        return c.onClient(mc -> {
            @SuppressWarnings("unchecked")
            Map<String, Long> m = (Map<String, Long>) Mod.staticCall(LS, "attempts", (Object) null);
            return m.getOrDefault(activity, 0L);
        });
    }

    private static int kills(Session c) {
        return c.onClient(mc -> (Integer) Mod.field(LK, "killsCounted"));
    }

    private static void skyblock(Session c) {
        c.hx().sidebar("§e§lSKYBLOCK", "§710/31/26 §8m12AB", "", " §7⏣ §bHub", "", "§ewww.hypixel.net");
    }

    /** Resets the tracker's memory and runs {@code body} with Magic Find 100. */
    private static void withMf(Session c, Runnable body) {
        Object cfg = c.onClient(mc -> Mod.staticCall(CFG, "getInstance"));
        Object mode0 = c.onClient(mc -> Mod.call(cfg, "getMagicFindMode"));
        int mf0 = c.onClient(mc -> (Integer) Mod.call(cfg, "getManualMagicFind"));
        try {
            c.ctx().runOnClient(mc -> {
                Mod.call(cfg, "setMagicFindMode", Mod.enumValue(CFG + "$MagicFindMode", "MANUAL"));
                Mod.call(cfg, "setManualMagicFind", 100);
                Mod.staticCall(LD, "resetForTest");
            });
            body.run();
        } finally {
            c.ctx().runOnClient(mc -> {
                Mod.call(cfg, "setMagicFindMode", mode0);
                Mod.call(cfg, "setManualMagicFind", mf0);
            });
        }
    }

    /** A one-health, no-AI husk named like a Hypixel mob, {@code ahead} blocks in front of him; returns its client id. */
    private static int summon(Session c, String name, double ahead) {
        c.server().command("execute as @p at @s run summon minecraft:husk ^ ^ ^" + ahead + " {NoAI:1b,Silent:1b,"
                + "PersistenceRequired:1b,CustomNameVisible:1b,CustomName:\"" + name + "\",Health:1.0f,"
                + "attributes:[{id:\"minecraft:max_health\",base:1.0d}]}");
        String plain = name.substring(0, Math.min(12, name.length()));
        c.waitUntil("the " + name + " husk reached the client", mc -> find(mc, plain) != null, 60);
        return c.onClient(mc -> find(mc, plain).getId());
    }

    private static Entity find(Minecraft mc, String namePart) {
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e.hasCustomName() && e.getCustomName().getString().contains(namePart) && e.isAlive()) {
                return e;
            }
        }
        return null;
    }

    private static void killAll(Session c) {
        c.server().command("kill @e[type=minecraft:husk]");
        c.ctx().waitTicks(30);
    }

    private static void aim(Session c, int id, boolean away) {
        c.ctx().runOnClient(mc -> {
            Entity e = mc.level.getEntity(id);
            Vec3 eye = mc.player.getEyePosition();
            Vec3 at = e.getBoundingBox().getCenter();
            double dx = at.x - eye.x;
            double dy = at.y - eye.y;
            double dz = at.z - eye.z;
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz)) + (away ? 180f : 0f);
            float pitch = away ? 0f : (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
            mc.player.setYRot(yaw);
            mc.player.setXRot(Math.max(-90, Math.min(90, pitch)));
        });
        c.ctx().waitTicks(6);
    }

    private static final String ZEALOT = "[Lv73] Zealot 13,000/13,000❤";
    private static final String HYDRA = "[Lv100] Water Hydra 500k/500k❤";
    private static final String ENDER = "[Lv45] Enderman 4,500/4,500❤";
    private static final String IMPLOSION = "§r§7Your §r§6Implosion §r§7hit §r§c%d §r§7enem%s for §r§c36,865,522.3 §r§7damage.";

    private static String implosion(int n) {
        return String.format(IMPLOSION, n, n == 1 ? "y" : "ies");
    }

    private static void ability(Session c) {
        skyblock(c);
        withMf(c, () -> {
            killAll(c);
            long z0 = attempts(c, "mob:Zealot");
            long h0 = attempts(c, "mob:Water Hydra");
            long e0 = attempts(c, "mob:Enderman:L45");
            int k0 = kills(c);
            summon(c, ZEALOT, 2.2);
            summon(c, HYDRA, 4.5);
            summon(c, ENDER, 30);
            // Control: the server kills a mob he never hit and no line named: nothing counts (Mob Kills: Hit By Me).
            c.hx().chat(implosion(2));
            c.ctx().waitTicks(4);
            c.server().command("kill @e[type=minecraft:husk]");
            c.waitUntil("the Zealot and the Hydra were counted", mc -> attempts(c, "mob:Zealot") == z0 + 1
                    && attempts(c, "mob:Water Hydra") == h0 + 1, 100);
            c.ctx().waitTicks(30);
            c.note("kills " + k0 + " -> " + kills(c) + "; " + c.onClient(mc -> Mod.field(LK, "lastKill")));
            c.check(attempts(c, "mob:Enderman:L45") == e0, "the Enderman 30 blocks away, outside Implosion's reach, counted");
            c.check(kills(c) == k0 + 2, "killsCounted moved by " + (kills(c) - k0) + ", want 2 (two mobs killed in reach)");
            // A second line while the corpses still stand must not count them again.
            c.hx().chat(implosion(2));
            c.ctx().waitTicks(30);
            c.check(kills(c) == k0 + 2, "a second ability line recounted a corpse (kills " + (kills(c) - k0) + ")");
            // Control: an unrelated kill with no ability line counts nothing.
            summon(c, ZEALOT, 2.2);
            c.server().command("kill @e[type=minecraft:husk]");
            c.ctx().waitTicks(40);
            c.check(attempts(c, "mob:Zealot") == z0 + 1, "a Zealot killed with no hit and no ability line counted");
        });
    }

    private static void bow(Session c) {
        skyblock(c);
        withMf(c, () -> {
            killAll(c);
            long z0 = attempts(c, "mob:Zealot");
            c.hx().give(0, "minecraft:bow");
            c.ctx().runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
            int id = summon(c, ZEALOT, 6);
            // Control: the bow used looking away from the mob marks nothing.
            aim(c, id, true);
            c.ctx().getInput().pressKey(o -> o.keyUse);
            c.ctx().waitTicks(6);
            c.server().command("kill @e[type=minecraft:husk]");
            c.ctx().waitTicks(40);
            c.check(attempts(c, "mob:Zealot") == z0, "a Zealot killed after a bow shot aimed away counted");
            // The shot at it.
            id = summon(c, ZEALOT, 6);
            aim(c, id, false);
            c.ctx().getInput().pressKey(o -> o.keyUse);
            c.ctx().waitTicks(6);
            c.server().command("kill @e[type=minecraft:husk]");
            c.waitUntil("the shot Zealot was counted", mc -> attempts(c, "mob:Zealot") == z0 + 1, 100);
            c.hx().give(0, "minecraft:air");
        });
    }

    private static void dropFirst(Session c) {
        skyblock(c);
        withMf(c, () -> {
            killAll(c);
            String key = "mob:Lost Adventurer|Beating Heart";
            long a0 = attempts(c, "mob:Lost Adventurer");
            long g0 = got(c, key);
            long o0 = got(c, "other|Beating Heart");
            summon(c, "Lost Adventurer 1,000/1,000❤", 2.2);
            c.hx().chat(implosion(1));
            c.hx().chat("§r§6§lRARE DROP! §r§9Beating Heart §r§b(+100 Magic Find)");
            c.ctx().waitTicks(1);
            c.server().command("kill @e[type=minecraft:husk]");
            c.waitUntil("the Lost Adventurer kill was counted", mc -> attempts(c, "mob:Lost Adventurer") == a0 + 1, 100);
            c.waitUntil("the drop was filed", mc -> got(c, key) == g0 + 1 || got(c, "other|Beating Heart") == o0 + 1, 100);
            c.note("Beating Heart: lost adventurer " + g0 + " -> " + got(c, key) + ", other " + o0 + " -> " + got(c, "other|Beating Heart"));
            c.check(got(c, key) == g0 + 1, "the drop line before the kill was not filed on the mob that died");
            c.check(got(c, "other|Beating Heart") == o0, "the drop line was filed under other");
        });
    }

    private static void dropForms(Session c) {
        skyblock(c);
        withMf(c, () -> {
            killAll(c);
            // 1. Two identical lines a few ms apart are two drops (two mobs dying in one Implosion).
            String piece = "mob:Zombie Soldier|Zombie Soldier Armor Piece";
            long p0 = got(c, piece);
            summon(c, "[Lv94] Zombie Soldier 5,000/5,000❤", 2.2);
            c.hx().chat(implosion(1));
            c.ctx().waitTicks(2);
            c.server().command("kill @e[type=minecraft:husk]");
            c.ctx().waitTicks(20);
            String line = "§r§6§lRARE DROP! §r§5Zombie Soldier Boots §r§b(+145 Magic Find)";
            c.hx().chat(line);
            c.hx().chat(line);
            c.waitUntil("both armor drop lines were counted on the Armor Piece row", mc -> got(c, piece) == p0 + 2, 60);
            c.check(got(c, piece) == p0 + 2, "armor piece row got " + (got(c, piece) - p0) + ", want 2");
            // 2. Soulstealer Bow: Hypixel prints it, the table now has its chance.
            String bow = "mob:Crypt Souleater|Soulstealer Bow";
            long b0 = got(c, bow);
            summon(c, "Crypt Souleater 7,000/7,000❤", 2.2);
            c.hx().chat(implosion(1));
            c.ctx().waitTicks(2);
            double e0 = expected(c, bow);
            c.server().command("kill @e[type=minecraft:husk]");
            c.waitUntil("the Souleater kill was counted", mc -> expected(c, bow) > e0, 100);
            c.hx().chat("§r§6§lRARE DROP! §r§9Soulstealer Bow §r§b(+100 Magic Find)");
            c.waitUntil("the bow drop was counted", mc -> got(c, bow) == b0 + 1, 60);
            c.check(expected(c, bow) > e0, "Soulstealer Bow has no expectation (not in the table as tracked)");
        });
    }
}
