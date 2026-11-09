package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * 1109-ghostmob-hide (mod ghostmob-1109, killer560 2026-11-09: "if a teammate is kind of far away and kills mobs in
 * dungeons they will create ghost mobs ... see if there is a way for you to essentially hide these mobs").
 * <p>
 * A ghost is a mob the client still has but the server no longer sends anything for. Here: a v2-UUID player-model mob
 * (a Hypixel NPC mob to the mod) and its "✯ ... ❤" name tag stand put into the client world only, so the server never
 * sends a packet for them, beside a REAL NoAI husk and a real ❤ name tag stand that never move (the server still sends
 * them its 60-tick position heartbeat). With Hide Ghost Mobs on: the fake pair must be judged ghosts (GhostMobs ids,
 * the render hook says no, starredMobs() drops the mob it listed before), the still real pair never; one data packet for
 * the fake mob through the real handler brings it back (heard again). With the switch off nothing is hidden.
 */
final class HxGhostMob1109Cases {

    private static final String GM = "mobesp.GhostMobs";
    private static final String ESP = "mobesp.MobEspFeature";
    private static final String CFG = "mobesp.MobEspConfig";
    private static final int FAKE = 913_000;

    private HxGhostMob1109Cases() {
    }

    static void register(Session s) {
        s.test("1109-ghostmob-hide", HxGhostMob1109Cases::hide);
    }

    private static int[] ghosts() {
        return (int[]) Mod.staticCall(GM, "ghostIdsForTest");
    }

    private static boolean isGhost(int id) {
        return Arrays.stream(ghosts()).anyMatch(i -> i == id);
    }

    private static boolean hidden(net.minecraft.client.Minecraft mc, int id) {
        Entity e = mc.level.getEntity(id);
        return e != null && (Boolean) Mod.staticCall(GM, "hidden", e);
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

    private static int clientIdNamed(net.minecraft.client.Minecraft mc, String name) {
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e.getCustomName() != null && e.getCustomName().getString().equals(name)) {
                return e.getId();
            }
        }
        return -1;
    }

    private static void hide(Session c) {
        try (Settings set = new Settings(c).with(CFG, "HideGhostMobs", false)) {
            dungeon(c);
            BlockPos f = c.onClient(mc -> mc.player.blockPosition());
            String at = (f.getX() + 3.5) + " " + f.getY() + " " + (f.getZ() + 0.5);
            c.server().command("summon husk " + at + " {Tags:[\"gm1109\"],NoAI:1b,Silent:1b,PersistenceRequired:1b,"
                    + "Invulnerable:1b,CustomName:\"gmreal\"}");
            c.server().command("summon armor_stand " + (f.getX() + 3.5) + " " + (f.getY() + 2.2) + " " + (f.getZ() + 0.5)
                    + " {Tags:[\"gm1109\"],Invisible:1b,NoGravity:1b,Marker:1b,CustomNameVisible:1b,"
                    + "CustomName:\"✯ Real Husk 100❤\"}");
            c.onClient(mc -> {
                UUID u = UUID.randomUUID();
                UUID v2 = new UUID((u.getMostSignificantBits() & ~0xF000L) | 0x2000L, u.getLeastSignificantBits());
                net.minecraft.client.player.RemotePlayer p = new net.minecraft.client.player.RemotePlayer(mc.level,
                        new com.mojang.authlib.GameProfile(v2, "Lost Adventurer"));
                p.setId(FAKE);
                p.setPos(f.getX() - 2.5, f.getY(), f.getZ() + 0.5);
                mc.level.addEntity(p);
                net.minecraft.world.entity.decoration.ArmorStand tag = new net.minecraft.world.entity.decoration.ArmorStand(
                        mc.level, f.getX() - 2.5, f.getY() + 2.2, f.getZ() + 0.5);
                tag.setId(FAKE + 1);
                tag.setCustomName(Component.literal("✯ Ghost Zombie 100❤"));
                tag.setCustomNameVisible(true);
                mc.level.addEntity(tag);
                return null;
            });
            c.waitUntil("the real husk and its tag stand reached the client", mc -> clientIdNamed(mc, "gmreal") >= 0
                    && clientIdNamed(mc, "✯ Real Husk 100❤") >= 0, 100);
            int husk = c.onClient(mc -> clientIdNamed(mc, "gmreal"));
            int stand = c.onClient(mc -> clientIdNamed(mc, "✯ Real Husk 100❤"));
            c.check(c.onClient(mc -> listed(mc, FAKE)), "starredMobs() did not list the fake mob before it went quiet:"
                    + " nothing to hide");

            // Switch OFF: judged (the census runs either way) but never hidden.
            c.waitUntil("the fake mob and its tag judged ghosts (no packet for 200 ticks)", mc -> isGhost(FAKE)
                    && isGhost(FAKE + 1), 400);
            c.check(!c.onClient(mc -> hidden(mc, FAKE)), "Hide Ghost Mobs OFF still hid the ghost");
            c.check(c.onClient(mc -> listed(mc, FAKE)), "Hide Ghost Mobs OFF dropped the ghost from starredMobs()");

            // Switch ON: hidden everywhere it is used.
            run(c, () -> Mod.call(Mod.cfg(CFG), "setHideGhostMobs", true));
            c.ctx().waitTicks(2);
            boolean[] fake = c.onClient(mc -> {
                Entity e = mc.level.getEntity(FAKE);
                Entity t = mc.level.getEntity(FAKE + 1);
                return new boolean[]{hidden(mc, FAKE), hidden(mc, FAKE + 1),
                        dev.testkit.compat.McCompat.shouldRender(mc.getEntityRenderDispatcher(), e, null, 0, 0, 0),
                        dev.testkit.compat.McCompat.shouldRender(mc.getEntityRenderDispatcher(), t, null, 0, 0, 0),
                        listed(mc, FAKE)};
            });
            c.check(fake[0] && fake[1], "GhostMobs.hidden: mob " + fake[0] + ", tag " + fake[1]);
            c.check(!fake[2] && !fake[3], "the render hook still draws the ghost (mob " + fake[2] + ", tag " + fake[3] + ")");
            c.check(!fake[4], "starredMobs() still lists the ghost (Auto Clear would target it)");

            // The still real pair: same age, never a ghost, also after another 100 ticks.
            c.ctx().waitTicks(100);
            boolean[] real = c.onClient(mc -> new boolean[]{isGhost(husk), isGhost(stand), hidden(mc, husk), hidden(mc, stand)});
            c.check(!real[0] && !real[1] && !real[2] && !real[3], "a real still mob was judged a ghost: husk "
                    + real[0] + "/" + real[2] + ", stand " + real[1] + "/" + real[3]);
            double moved = c.onClient(mc -> mc.level.getEntity(husk).position().distanceTo(
                    new net.minecraft.world.phys.Vec3(f.getX() + 3.5, f.getY(), f.getZ() + 0.5)));
            c.check(moved < 0.2, "the real husk moved " + moved + " blocks - it was meant to stand still");

            // One packet for the ghost through the real handler: shown again at once.
            int[] before = c.onClient(mc -> (int[]) Mod.staticCall(GM, "countsForTest"));
            c.onClient(mc -> {
                mc.getConnection().handleSetEntityData(
                        new net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket(FAKE, List.of()));
                return null;
            });
            boolean back = c.onClient(mc -> !hidden(mc, FAKE) && listed(mc, FAKE));
            int[] after = c.onClient(mc -> (int[]) Mod.staticCall(GM, "countsForTest"));
            c.check(back, "a data packet for the ghost did not bring it back");
            c.check(after[1] == before[1] + 1, "heard-again count " + before[1] + " -> " + after[1]);
            c.check(c.onClient(mc -> hidden(mc, FAKE + 1)), "the tag stand came back with its mob's packet");
            c.note("fake mob #" + FAKE + " + tag judged ghosts; OFF: shown and listed; ON: hidden, render hook false,"
                    + " out of starredMobs(); real still husk #" + husk + " and tag #" + stand + " never judged (moved "
                    + String.format(java.util.Locale.ROOT, "%.2f", moved) + "); one data packet -> shown again; counts "
                    + Arrays.toString(after));
        } finally {
            c.server().command("kill @e[tag=gm1109]");
            run(c, () -> {
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                for (int id : new int[]{FAKE, FAKE + 1}) {
                    if (mc.level != null && mc.level.getEntity(id) != null) {
                        mc.level.removeEntity(id, Entity.RemovalReason.DISCARDED);
                    }
                }
            });
        }
    }
}
