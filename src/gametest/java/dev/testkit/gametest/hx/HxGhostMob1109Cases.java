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
 * Hide Ghost Mobs (mod ghostmob-1109, rule rebuilt in ghostmob-redo-1118; killer560 2026-11-09: "if a teammate is kind of
 * far away and kills mobs in dungeons they will create ghost mobs ... see if there is a way for you to essentially hide
 * these mobs").
 * <p>
 * Hypixel sends nothing for an idle mob, so silence proves nothing (his 26.3 logs: 898 of 4091 silence-flagged mobs were
 * real). A ghost is now a STARRED mob in a room the map shows cleared that has had no packet since before the clear. The
 * fakes here are client-only pairs (a v2-UUID player-model mob and its name tag stand), so the server never sends a
 * packet for them, i.e. idle on Hypixel's terms; rooms are cleared through MobEspFeature.setTestRoomCleared.
 * <ul>
 *   <li>1109-ghostmob-hide: no room cleared, nothing judged after 260 ticks of silence; the room cleared: the starred pair
 *   is judged, hidden with the switch on (render hook, starredMobs()), a real mob that came in after the clear never; a
 *   packet brings the ghost back and it is not hidden again.</li>
 *   <li>1118-ghostmob-quiet-shown: a starred pair silent for 2000 ticks, its own room never cleared (another one is), stays
 *   shown.</li>
 *   <li>1118-ghostmob-unstarred: in a cleared room, a Blood room waiting mob (plain name, plain tag) and an unstarred ❤ tag
 *   stay shown while a starred pair beside them is hidden.</li>
 * </ul>
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
        s.test("1118-ghostmob-quiet-shown", HxGhostMob1109Cases::quietShown);
        s.test("1118-ghostmob-unstarred", HxGhostMob1109Cases::unstarred);
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

    /** A client-only mob (v2 player model named {@code mob}) at id, with a name tag stand {@code tag} at id + 1. */
    private static void fakePair(Session c, int id, double x, double y, double z, String mob, String tag) {
        c.onClient(mc -> {
            UUID u = UUID.randomUUID();
            UUID v2 = new UUID((u.getMostSignificantBits() & ~0xF000L) | 0x2000L, u.getLeastSignificantBits());
            net.minecraft.client.player.RemotePlayer p = new net.minecraft.client.player.RemotePlayer(mc.level,
                    new com.mojang.authlib.GameProfile(v2, mob));
            p.setId(id);
            p.setPos(x, y, z);
            mc.level.addEntity(p);
            net.minecraft.world.entity.decoration.ArmorStand stand = new net.minecraft.world.entity.decoration.ArmorStand(
                    mc.level, x, y + 2.2, z);
            stand.setId(id + 1);
            stand.setCustomName(Component.literal(tag));
            stand.setCustomNameVisible(true);
            mc.level.addEntity(stand);
            return null;
        });
    }

    /** The middle of the 32-block dungeon cell holding {@code v} (LiveMapFeature.START_X/Z = -185), so every entity of a
     *  case sits in one test room wherever the session left him. */
    private static double mid(int v) {
        return -185 + 32 * Math.round((v + 185) / 32.0) + 0.5;
    }

    private static void roomCleared(Session c, BlockPos f, double dx, boolean on) {
        c.onClient(mc -> Mod.staticCall(ESP, "setTestRoomCleared", mid(f.getX()) + dx, mid(f.getZ()), on));
    }

    private static void cleanup(Session c, BlockPos f, int... ids) {
        c.server().command("kill @e[tag=gm1109]");
        if (f != null) {
            roomCleared(c, f, 0, false);
            roomCleared(c, f, 64, false);
            roomCleared(c, f, -32, false);
        }
        run(c, () -> {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            for (int id : ids) {
                if (mc.level != null && mc.level.getEntity(id) != null) {
                    mc.level.removeEntity(id, Entity.RemovalReason.DISCARDED);
                }
            }
        });
    }

    private static void hide(Session c) {
        BlockPos f = null;
        try (Settings set = new Settings(c).with(CFG, "HideGhostMobs", false)) {
            dungeon(c);
            f = c.onClient(mc -> mc.player.blockPosition());
            BlockPos at = f;
            fakePair(c, FAKE, mid(f.getX()) - 2, f.getY(), mid(f.getZ()), "Lost Adventurer", "✯ Ghost Zombie 100❤");
            c.check(c.onClient(mc -> listed(mc, FAKE)), "starredMobs() did not list the fake mob: nothing to hide");

            // Silence alone: never a ghost (Hypixel sends nothing for an idle mob).
            c.ctx().waitTicks(260);
            c.check(!isGhost(FAKE) && !isGhost(FAKE + 1), "a starred mob silent for 260 ticks in a room not cleared was"
                    + " judged a ghost");

            // Its room cleared on the map: the silent starred pair is dead by definition.
            roomCleared(c, f, 0, true);
            c.waitUntil("the starred pair judged ghosts once its room cleared", mc -> isGhost(FAKE) && isGhost(FAKE + 1), 60);
            c.check(!c.onClient(mc -> hidden(mc, FAKE)), "Hide Ghost Mobs OFF still hid the ghost");
            c.check(c.onClient(mc -> listed(mc, FAKE)), "Hide Ghost Mobs OFF dropped the ghost from starredMobs()");

            // A real starred mob that walks into the cleared room afterwards (heard after the clear): never judged.
            String where = (mid(f.getX()) + 3) + " " + f.getY() + " " + mid(f.getZ());
            c.server().command("summon husk " + where + " {Tags:[\"gm1109\"],NoAI:1b,Silent:1b,PersistenceRequired:1b,"
                    + "Invulnerable:1b,CustomName:\"gmreal\"}");
            c.server().command("summon armor_stand " + (mid(f.getX()) + 3) + " " + (f.getY() + 2.2) + " " + mid(f.getZ())
                    + " {Tags:[\"gm1109\"],Invisible:1b,NoGravity:1b,Marker:1b,CustomNameVisible:1b,"
                    + "CustomName:\"✯ Real Husk 100❤\"}");
            c.waitUntil("the real husk and its tag stand reached the client", mc -> clientIdNamed(mc, "gmreal") >= 0
                    && clientIdNamed(mc, "✯ Real Husk 100❤") >= 0, 100);
            int husk = c.onClient(mc -> clientIdNamed(mc, "gmreal"));
            int stand = c.onClient(mc -> clientIdNamed(mc, "✯ Real Husk 100❤"));

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

            c.ctx().waitTicks(120);
            boolean[] real = c.onClient(mc -> new boolean[]{isGhost(husk), isGhost(stand), hidden(mc, husk), hidden(mc, stand)});
            c.check(!real[0] && !real[1] && !real[2] && !real[3], "a real mob heard after the clear was judged a ghost:"
                    + " husk " + real[0] + "/" + real[2] + ", stand " + real[1] + "/" + real[3]);

            // One packet for the ghost through the real handler: shown again at once, and not hidden again.
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
            c.ctx().waitTicks(80);
            c.check(!c.onClient(mc -> hidden(mc, FAKE)), "the mob heard after the clear was hidden again");
            c.note("silent 260 ticks, room not cleared: shown; room cleared: fake #" + FAKE + " + tag judged; OFF shown;"
                    + " ON hidden, render hook false, out of starredMobs(); real husk #" + husk + " + tag #" + stand
                    + " (in after the clear) never judged; one packet -> shown and stays; counts " + Arrays.toString(after)
                    + " at " + at);
        } finally {
            cleanup(c, f, FAKE, FAKE + 1);
        }
    }

    private static void quietShown(Session c) {
        BlockPos f = null;
        try (Settings set = new Settings(c).with(CFG, "HideGhostMobs", true)) {
            dungeon(c);
            f = c.onClient(mc -> mc.player.blockPosition());
            fakePair(c, FAKE + 10, mid(f.getX()) - 2, f.getY(), mid(f.getZ()), "Lost Adventurer", "✯ Idle Zombie 100❤");
            roomCleared(c, f, 64, true);   // another room is cleared: the judge runs, its room is not this one
            c.check(c.onClient(mc -> listed(mc, FAKE + 10)), "starredMobs() did not list the idle mob");
            c.ctx().waitTicks(2000);
            boolean[] r = c.onClient(mc -> new boolean[]{isGhost(FAKE + 10), isGhost(FAKE + 11), hidden(mc, FAKE + 10),
                    listed(mc, FAKE + 10)});
            c.check(!r[0] && !r[1] && !r[2], "an idle starred mob silent 2000 ticks in a room not cleared was hidden: mob "
                    + r[0] + "/" + r[2] + ", tag " + r[1]);
            c.check(r[3], "the idle starred mob dropped out of starredMobs()");
            c.note("starred pair silent 2000 ticks, only another room cleared: never judged, still listed");
        } finally {
            cleanup(c, f, FAKE + 10, FAKE + 11);
        }
    }

    private static void unstarred(Session c) {
        BlockPos f = null;
        try (Settings set = new Settings(c).with(CFG, "HideGhostMobs", true)) {
            dungeon(c);
            f = c.onClient(mc -> mc.player.blockPosition());
            fakePair(c, FAKE + 20, mid(f.getX()) - 34, f.getY(), mid(f.getZ()), "Lost Adventurer", "✯ Dead Zombie 100❤");
            fakePair(c, FAKE + 30, mid(f.getX()) - 34, f.getY(), mid(f.getZ()) + 3, "Mr. Dead", "Revoker");
            fakePair(c, FAKE + 40, mid(f.getX()) - 34, f.getY(), mid(f.getZ()) - 3, "Crypt Lurker", "Crypt Lurker 672k❤");
            c.ctx().waitTicks(60);
            roomCleared(c, f, -32, true);   // a room of its own: earlier cases already cleared his
            c.waitUntil("the starred control pair judged ghosts (the judge ran)", mc -> isGhost(FAKE + 20)
                    && isGhost(FAKE + 21), 60);
            c.ctx().waitTicks(100);
            int[] wrong = c.onClient(mc -> java.util.stream.IntStream.of(FAKE + 30, FAKE + 31, FAKE + 40, FAKE + 41)
                    .filter(id -> isGhost(id) || hidden(mc, id)).toArray());
            c.check(wrong.length == 0, "unstarred mobs in a cleared room were hidden: " + Arrays.toString(wrong));
            c.check(c.onClient(mc -> hidden(mc, FAKE + 20)), "the starred control pair was judged but not hidden");
            c.note("cleared room: starred pair hidden; Blood waiting mob 'Mr. Dead'/'Revoker' and unstarred"
                    + " 'Crypt Lurker 672k❤' shown");
        } finally {
            cleanup(c, f, FAKE + 20, FAKE + 21, FAKE + 30, FAKE + 31, FAKE + 40, FAKE + 41);
        }
    }
}
