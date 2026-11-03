package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Mod branch bloodcamp-1015 (killer560, 2026-10-15): Auto Blood Camp's Trigger Bot and Aura, on the real dedicated
 * server with GrimAC (every case must be clean). A Watcher's skull stand and a blood mob's skull stand stand on the Hx
 * platform, the mob's move packets sent on server ticks the case knows (the way 792 does it), and a husk on the
 * predicted landing spot is the mob that "spawns" there. The player stands at (-419.5, 151, -419.5), room tile (-7, -7)
 * of the dungeon grid (centers at -185 + 32n); the Watcher goes in the same tile unless a case says otherwise.
 *
 * <ul>
 *   <li>177 Trigger Offset: the first click goes exactly -offset ticks after the kill timer (the tick the box turns
 *   green), for -1 (the default), 0, +2 and -5; at -1 the attack reaches the server after the countdown's end.</li>
 *   <li>178 Blood Room only: with the Watcher one room tile over, neither the Trigger Bot nor the Aura acts (no
 *   click, no turn); back in his tile, the same trip is clicked.</li>
 *   <li>179 Aim height: the Aura aims at the husk's upper chest (and 1.5 above an empty landing spot), never the feet,
 *   and its body look ends on that point.</li>
 *   <li>180 Human turn: a 70 degree turn waits a reaction time, takes several ticks, never moves more than 26 yaw / 16
 *   pitch a tick, and every tick's change is a whole number of mouse counts at his sensitivity.</li>
 *   <li>181 Mouse takes over: a mouse movement mid-turn stops the Aura (it stays where the mouse put it); in Freecam
 *   Mode the camera is handed straight back with the body under it.</li>
 *   <li>182 Miss retry: a husk that takes no damage (invulnerable) is clicked again after the hit window; a husk that
 *   is hurt is clicked once.</li>
 *   <li>183 Freecam Mode: while the Aura turns the body, the camera stays where it was; once the mob is hit the body
 *   turns back under the camera and the camera is released.</li>
 * </ul>
 * Legit jar: no Trigger Bot or Aura exists, so each case checks nothing was clicked or turned.
 */
final class HxBloodCampCases {

    private static final String BC_CFG = "bloodcamp.BloodCampConfig";
    private static final String BC = "bloodcamp.BloodCampFeature";
    private static final String VF = "util.ViewFreeze";
    private static final double PX = -419.5;
    private static final double PZ = -419.5;
    /** Tile -7 (round((-424.5 + 185) / 32) = -7, his), and within Blood Camp's 20 blocks of a first-wave trip's start. */
    private static final double[] WATCHER_IN = {-424.5, 151, -412.5};
    /** Tile -8 along x: round((-430.5 + 185) / 32) = -8, his is -7. */
    private static final double[] WATCHER_OUT = {-430.5, 151, -412.5};

    private HxBloodCampCases() {
    }

    static void register(Session s) {
        s.test("177-hx-bloodcamp-offset", HxBloodCampCases::offset);
        s.test("178-hx-bloodcamp-room", HxBloodCampCases::room);
        s.test("179-hx-bloodcamp-aim-height", HxBloodCampCases::aimHeight);
        s.test("180-hx-bloodcamp-human-turn", HxBloodCampCases::humanTurn);
        s.test("181-hx-bloodcamp-mouse", HxBloodCampCases::mouse);
        s.test("182-hx-bloodcamp-miss-retry", HxBloodCampCases::missRetry);
        s.test("183-hx-bloodcamp-freecam", HxBloodCampCases::freecam);
        // bloodcamp-2-1015: walls, mobs already on the floor, attack speed, Never Miss.
        s.test("184-hx-bloodcamp-walls", HxBloodCampCases::walls);
        s.test("185-hx-bloodcamp-floor-mobs", HxBloodCampCases::floorMobs);
        s.test("186-hx-bloodcamp-attack-speed", HxBloodCampCases::attackSpeed);
        s.test("187-hx-bloodcamp-never-miss", HxBloodCampCases::neverMiss);
        // bloodcamp-3-1015: any range with whatever he holds, and human clicks between the hits.
        s.test("188-hx-bloodcamp-ranged", HxBloodCampCases::ranged);
        s.test("189-hx-bloodcamp-human-clicks", HxBloodCampCases::humanClicks);
        // bloodcamp-4-1015: Blood Mob ESP, blood mobs only, a clear line of fire.
        s.test("190-hx-bloodcamp-blood-esp", HxBloodCampCases::bloodEsp);
        s.test("191-hx-bloodcamp-only-blood-mobs", HxBloodCampCases::onlyBloodMobs);
        s.test("192-hx-bloodcamp-line-of-fire", HxBloodCampCases::lineOfFire);
        // bloodcamp-5-1015: blood mobs recognized by their Hypixel name tags.
        s.test("193-hx-bloodcamp-named-blood-mobs", HxBloodCampCases::namedBloodMobs);
        // blood-ap3-1018 (doc item 19): the map calling his Blood Room something else must not stop the Trigger Bot.
        s.test("1018-bloodcamp-map-type", HxBloodCampCases::mapType);
        // blood-giant-esp-1022: the F7 Blood Room's reanimated giant, and mob-type icons in a blood mob's tag.
        s.test("1022-bloodcamp-giant-esp", HxBloodCampCases::giantEsp);
        // doc-blood-1101 (to-do Doc, 2026-11-01): BLOOD CAMP STUFF and STARRED MOB HIGHLIGHT.
        s.test("1101-bloodcamp-exclusions", HxBloodCampCases::exclusions);
        s.test("1101-bloodcamp-primal-fear", HxBloodCampCases::primalFearTrip);
        s.test("1101-bloodcamp-aura-late-pick", HxBloodCampCases::auraLatePick);
        s.test("1101-bloodcamp-mage-only", HxBloodCampCases::mageOnly);
        s.test("1101-bloodcamp-teleport-step", HxBloodCampCases::teleportStep);
        s.test("1101-bloodcamp-stats", HxBloodCampCases::stats);
        s.test("1102-bloodcamp-watcher-bar", HxBloodCampCases::watcherBar);
        s.test("1102-bloodcamp-npc-deaths", HxBloodCampCases::npcDeaths);
        s.test("1102-bloodcamp-hud-boss", HxBloodCampCases::hudBoss);
        // bloodcamp2-1102 (owner's F7 camp 2026-11-02): a missed wall spawn, slow Trigger Bot clicks, a Watchful Eye drawn.
        s.test("1102-bloodcamp-wall-spawn", HxBloodCampCases::wallSpawn);
        s.test("1102-bloodcamp-tag-latency", HxBloodCampCases::tagLatency);
        s.test("1102-bloodcamp-watchful-eye", HxBloodCampCases::watchfulEye);
        // nevermiss-1102 (owner 2026-11-03): Never Miss ON = no Trigger Offset, no kill timer, attack the tick after spawn.
        s.test("1102-bloodcamp-nevermiss-offset", HxBloodCampCases::neverMissOffset);
        s.test("1102-bloodcamp-nevermiss-killtimer", HxBloodCampCases::neverMissKillTimer);
        s.test("1102-bloodcamp-nevermiss-offspot", HxBloodCampCases::neverMissOffSpot);
        s.test("1102-bloodcamp-nevermiss-rehit", HxBloodCampCases::neverMissRehit);
        s.test("1102-bloodcamp-nevermiss-tab", HxBloodCampCases::neverMissTab);
        s.test("1101-esp-fels", HxBloodCampCases::fels);
        s.test("1101-esp-starred-memory", HxBloodCampCases::starredMemory);
        s.test("1101-esp-mimic", HxBloodCampCases::mimic);
    }

    // ---- 1022 -------------------------------------------------------------------------------------------------------

    /**
     * killer560, 2026-10-22 (F7 Blood Room screenshot): a giant tagged "MINIBOSS" over "<icon><icon> Boomer Giant 25.3M❤"
     * was not highlighted by Blood Mobs ESP. Until mod blood-giant-esp-1022 the name-tag pattern knew no plain "Giant"
     * and no mob-type icons, and a giant is never thrown as a skull, so nothing tagged it; and with ESP off the line of
     * sight went to the giant's eye, 10.4 blocks up, which is in the room's ceiling. Here a giant stands with its eye above
     * a stone ceiling and its body in plain view, ESP (through walls) OFF, with the screenshot's two tags; beside it a husk
     * tagged "[Lv79] <icon> Parasite 33,333/33,333❤" (the same screenshot's Parasite). Both must be drawn blood red. The
     * icon characters Hypixel really sends are not known (only how its pack draws them); any symbol stands in for them.
     */
    private static void giantEsp(Session c) {
        String ceiling = "-417 159 -428 -410 159 -421";
        try (HxKit.Settings s = base(c)) {
            boolean[] was = c.onClient(mc -> new boolean[]{(Boolean) Mod.call(Mod.cfg(ESP_CFG), "getBloodMobsRaw"),
                    (Boolean) Mod.call(Mod.cfg(ESP_CFG), "isEspRaw")});
            s.custom(() -> Mod.call(Mod.cfg(ESP_CFG), "setBloodMobs", true),
                            () -> Mod.call(Mod.cfg(ESP_CFG), "setBloodMobs", was[0]))
                    .custom(() -> Mod.call(Mod.cfg(ESP_CFG), "setEsp", false),
                            () -> Mod.call(Mod.cfg(ESP_CFG), "setEsp", was[1]));
            watcher(c, WATCHER_IN);
            stand(c, AWAY_YAW, 10f);
            int red = c.onClient(mc -> (Integer) Mod.call(Mod.cfg(ESP_CFG), "getBloodColor"));
            c.server().command("fill " + ceiling + " minecraft:smooth_stone replace minecraft:air");
            c.server().command("summon giant -413.5 151 -424.5 {Tags:[\"bcdecoy\"],NoAI:1b,NoGravity:1b,Silent:1b,"
                    + "PersistenceRequired:1b,Invulnerable:1b,CustomName:\"bloodgiant\"}");
            c.server().command("summon armor_stand -413.5 163.9 -424.5 {Tags:[\"bcdecoy\"],Invisible:1b,NoGravity:1b,"
                    + "Marker:1b,CustomNameVisible:1b,CustomName:\"MINIBOSS\"}");
            c.server().command("summon armor_stand -413.5 163.3 -424.5 {Tags:[\"bcdecoy\"],Invisible:1b,NoGravity:1b,"
                    + "Marker:1b,CustomNameVisible:1b,CustomName:\"☣♜ Boomer Giant 25.3M❤\"}");
            taggedHusk(c, "iconparasite", "[Lv79] ☠ Parasite 33,333/33,333❤", -421.0, -417.5);
            c.ctx().waitTicks(3);
            // The case is only about the ceiling if his eye cannot see the giant's eye, and can see its body.
            boolean[] rays = c.onClient(mc -> {
                net.minecraft.world.entity.Entity g = mc.level.getEntity(clientId(mc, "bloodgiant"));
                if (g == null) {
                    return null;
                }
                Vec3 eye = mc.player.getEyePosition();
                java.util.function.Function<Vec3, Boolean> clear = p -> mc.level.clip(new net.minecraft.world.level.ClipContext(
                        eye, p, net.minecraft.world.level.ClipContext.Block.COLLIDER,
                        net.minecraft.world.level.ClipContext.Fluid.NONE, g)).getType()
                        == net.minecraft.world.phys.HitResult.Type.MISS;
                return new boolean[]{clear.apply(g.getEyePosition()), clear.apply(g.getBoundingBox().getCenter())};
            });
            c.check(rays != null, "the giant never reached the client - nothing was tested");
            if (rays == null) {
                return;
            }
            c.note("giant eye visible " + rays[0] + ", giant body visible " + rays[1]);
            c.check(!rays[0] && rays[1], "the ceiling does not hide the giant's eye while showing its body - nothing was tested");
            Object[] seen = new Object[2];
            for (int i = 0; i < 60; i++) {
                c.ctx().waitTicks(1);
                if (i >= 20) {
                    Object[] r = c.onClient(mc -> {
                        @SuppressWarnings("unchecked")
                        Map<Integer, Integer> drawn = (Map<Integer, Integer>) Mod.staticCall(ESP, "drawnColors");
                        return new Object[]{drawn.get(clientId(mc, "bloodgiant")), drawn.get(clientId(mc, "iconparasite"))};
                    });
                    seen = r;
                    if (Integer.valueOf(red).equals(r[0]) && Integer.valueOf(red).equals(r[1])) {
                        break;
                    }
                }
            }
            c.note("Boomer Giant: ESP color " + seen[0] + "; [Lv79] <icon> Parasite: ESP color " + seen[1] + "; red "
                    + Integer.toHexString(red));
            c.check(Integer.valueOf(red).equals(seen[0]), "the Blood Room giant was not highlighted red");
            c.check(Integer.valueOf(red).equals(seen[1]), "the blood mob with a mob-type icon in its tag was not highlighted red");
        } finally {
            clearDecoys(c);
            c.server().command("fill " + ceiling + " minecraft:air replace minecraft:smooth_stone");
            c.hx().removeStands();
        }
    }

    // ---- 1018 -------------------------------------------------------------------------------------------------------

    /**
     * killer560, 2026-10-18: "I just tired blood camp triggerbot and it wasnt working in f1." Until mod blood-ap3-1018 the
     * map's room type, whenever it had one, decided "in the Blood Room" alone - and the same F1 run showed his Blood Room
     * "as a random room on the map". Here the map is made to call the room he stands in NORMAL while he stands in the
     * Watcher's tile: the Trigger Bot must still click. Then, with the Watcher one tile over and the map saying NORMAL,
     * it must not (the room gate still works). The map type goes through the mod's test hook; a jar without the hook
     * cannot be told what the map says, so it fails here.
     */
    private static void mapType(Session c) {
        boolean cheat = Mod.isCheat();
        try (HxKit.Settings s = base(c)) {
            s.custom(() -> Mod.staticCall(BC, "setTestMapRoomType", "NORMAL"),
                    () -> Mod.staticCall(BC, "setTestMapRoomType", (Object) null));
            if (cheat) {
                s.with(BC_CFG, "TriggerBotEnabled", true);
            }
            watcher(c, WATCHER_IN);
            stand(c, 0f, 0f);
            Trip in = trip(c, Mob.HURTABLE, true, 20, null);
            c.note("map says NORMAL, he is in the Watcher's tile: " + fmt(in));
            c.check(in.tracked, "the trip was never tracked - nothing was tested");
            c.check(in.inRoom, "with the map calling it NORMAL, Blood Camp did not see the Blood Room he and the Watcher are in");
            if (cheat) {
                c.check(in.clicks >= 1 && !in.serverAttacks.isEmpty(), "the Trigger Bot did not click in the Blood Room"
                        + " because the map called it NORMAL");
            }
            watcher(c, WATCHER_OUT);
            stand(c, 0f, 0f);
            Trip out = trip(c, Mob.HURTABLE, true, 20, null);
            c.note("map says NORMAL, Watcher one tile over: " + fmt(out));
            c.check(!out.inRoom && out.clicks == 0 && out.serverAttacks.isEmpty(),
                    "outside the Watcher's tile with the map saying NORMAL it still acted");
        } finally {
            c.hx().removeStands();
        }
    }

    // ---- shared -----------------------------------------------------------------------------------------------------

    /** HURTABLE 20 health, FRAGILE 1 (one hit kills it), INVULNERABLE takes no damage. */
    private enum Mob { NONE, HURTABLE, INVULNERABLE, FRAGILE, WEAK }

    /** Run on the test thread at each tick of the next trip (server commands mid-trip), then cleared. */
    private static java.util.function.IntConsumer nextServerStep;

    /** Set before a trip: summon its husk this many ticks after the mob is due instead of as soon as it is tracked
     *  (null = at once). The trip records the server tick it was summoned on and the clicks sent before it. */
    private static Integer nextSummonDelay;
    /** Set before a trip: the custom name its husk gets instead of "blood" (doc-blood-1101: a Primal Fear on the spot). */
    private static String nextHuskName;
    /** Set before a trip: its first step is an absolute teleport packet of this many blocks, then two nudges. */
    private static Double nextTeleportStep;
    /** Set before a trip: the server removes its skull stand at this tick of the trip (bloodcamp2-1102: Hypixel removes the
     *  skull around when the mob appears), null = at the end. */
    private static Integer nextRemoveStandAt;
    /** Set before a trip: its husk is summoned (and aimed at) this many blocks along +x from the predicted landing spot. */
    private static Double nextSummonDx;
    private static long lastSummonTick = -1;
    private static int lastClicksBeforeSummon = -1;

    /** What one trip measured. */
    private record Trip(boolean tracked, Vec3 end, double killAt, double triggerAt, int clicks, int attacks,
                        int retries, List<Long> serverAttacks, long firstTick, double due, List<float[]> looks,
                        Vec3 auraPoint, boolean everTurning, boolean inRoom) {
        Double serverLead() {
            return serverAttacks.isEmpty() ? null : firstTick + due - serverAttacks.get(0);
        }
    }

    private static String head(String texture) {
        return "player_head[profile={properties:[{name:\"textures\",value:\"" + texture + "\"}]}]";
    }

    private static String anyOf(Object set) {
        return (String) ((Collection<?>) set).iterator().next();
    }

    private static Vec3 endVector() {
        Map<?, ?> mobs = (Map<?, ?>) Mod.field(Mod.ROOT + BC, "bloodMobs");
        for (Object data : mobs.values()) {
            Object end = Mod.field(data, "endVector");
            if (end != null) {
                return (Vec3) end;
            }
        }
        return null;
    }

    private static int intStat(Session c, String method) {
        return c.onClient(mc -> (Integer) Mod.staticCall(BC, method));
    }

    /** Blood Camp on, in a "dungeon", sounds off, everything automated off; the case turns on what it tests. */
    private static HxKit.Settings base(Session c) {
        boolean gateWas = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled"));
        HxKit.Settings s = new HxKit.Settings(c)
                .custom(() -> Mod.staticCall("util.SkyblockGate", "setEnabled", false),
                        () -> Mod.staticCall("util.SkyblockGate", "setEnabled", gateWas))
                .custom(() -> Mod.staticCall("secrets.DungeonState", "setRoomSim", true),
                        () -> Mod.staticCall("secrets.DungeonState", "setRoomSim", false))
                .with(BC_CFG, "Enabled", true)
                .with(BC_CFG, "ShowOverlay", true)
                .with(BC_CFG, "CountdownStartSound", false)
                .with(BC_CFG, "KillSound", false);
        if (Mod.isCheat()) {
            // Clicks between the registering hits (bloodcamp-3-1015) would add attacks every case here counts; 189
            // switches them back on to test them.
            s.custom(() -> Mod.staticCall(BC, "setHumanClicks", false), () -> Mod.staticCall(BC, "setHumanClicks", true));
        }
        if (Mod.isCheat()) {
            s.with(BC_CFG, "TriggerBotEnabled", false).with(BC_CFG, "AuraEnabled", false)
                    .with(BC_CFG, "AuraFreecam", false).with(BC_CFG, "AutoDetectLag", true)
                    .with(BC_CFG, "TriggerOffsetTicks", 1); // +1 = one tick late (positive late since mod doc-blood-1101)
        }
        return s;
    }

    /** He stands on the platform looking at {@code yaw}/{@code pitch}; no container open. */
    private static void stand(Session c, float yaw, float pitch) {
        standAt(c, PX, PZ, yaw, pitch);
    }

    /** {@link #stand} at (x, 151, z). */
    private static void standAt(Session c, double x, double z, float yaw, float pitch) {
        c.server().command(String.format(Locale.ROOT, "tp @a %.2f 151 %.2f %.1f %.1f", x, z, yaw, pitch));
        c.ctx().waitTicks(5);
        c.onClient(mc -> {
            if (mc.player.containerMenu != mc.player.inventoryMenu) {
                mc.player.closeContainer();
            }
            mc.player.setYRot(yaw);
            mc.player.setXRot(pitch);
            return null;
        });
        c.ctx().waitTicks(2);
    }

    /** The Watcher's skull stand at {@code at}; waits until Blood Camp has him. */
    private static void watcher(Session c, double[] at) {
        c.hx().removeStands();
        c.ctx().waitTicks(3);
        String tex = c.onClient(mc -> anyOf(Mod.field(Mod.ROOT + BC, "WATCHER_SKULL_TEXTURES")));
        c.hx().stand(at[0], at[1], at[2], null, "marker", false, "invisible", false, "helmet", head(tex));
        c.waitUntil("Blood Camp sees the Watcher", mc -> {
            Object id = Mod.field(Mod.ROOT + BC, "watcherEntityId");
            return id != null && mc.level.getEntity((Integer) id) != null
                    && mc.level.getEntity((Integer) id).position().distanceTo(new Vec3(at[0], at[1], at[2])) < 1.0;
        }, 200);
    }

    /** The yaw/pitch from his eye to {@code p}. */
    private static float[] look(Minecraft mc, Vec3 p) {
        Vec3 d = p.subtract(mc.player.getEyePosition());
        return new float[]{(float) Math.toDegrees(Math.atan2(-d.x, d.z)),
                (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)))};
    }

    /**
     * One blood mob trip (792's): three move packets a tick apart from the server, a husk on the predicted spot as
     * soon as the trip is tracked. {@code aimManually}: the case points him at the husk's chest every tick (no Aura).
     * {@code each(tick, mc)} runs on the client every tick after that. Runs until {@code extra} ticks past the mob's due
     * moment (the first wave is due 78.8 ticks after its first packet, later ones 38.8).
     */
    private static Trip trip(Session c, Mob mob, boolean aimManually, int extra, BiConsumer<Integer, Minecraft> each) {
        boolean firstWave = c.onClient(mc -> (Boolean) Mod.staticCall("bloodcamp.BloodCampMoveTimer", "isFirstSpawns"));
        double scale = firstWave ? 16.1 : 11.9;
        double due = (firstWave ? 40 : 0) + 38 + 0.8;
        double x0 = -420.5 - scale - 1.0;
        String mobTex = c.onClient(mc -> anyOf(Mod.field(Mod.ROOT + BC, "MOB_SKULL_TEXTURES")));
        c.onClient(mc -> {
            Mod.setField(BC, "lastKillableAt", Double.NaN);
            Mod.setField(BC, "lastTriggerAt", Double.NaN);
            Mod.setField(BC, "lastAuraPoint", null);
            if (Mod.isCheat()) {
                Mod.staticCall(BC, "resetAuraStats");
            }
            return null;
        });
        boolean cheat = Mod.isCheat();
        int clicks0 = intStat(c, "triggerClicks");
        int attacks0 = intStat(c, "triggerAttacks");
        int retries0 = cheat ? intStat(c, "triggerRetries") : 0;
        Double tpStep = nextTeleportStep;
        nextTeleportStep = null;
        Integer removeStandAt = nextRemoveStandAt;
        nextRemoveStandAt = null;
        String huskName = nextHuskName == null ? "blood" : nextHuskName;
        nextHuskName = null;
        double summonDx = nextSummonDx == null ? 0.0 : nextSummonDx;
        nextSummonDx = null;
        String uuid = c.hx().stand(x0 - (tpStep == null ? 0.0 : tpStep), 151.0, -417.5, null, "marker", false, "invisible",
                false, "helmet", head(mobTex));
        long firstTick = -1;
        boolean tracked = false;
        Vec3 end = null;
        List<float[]> looks = new ArrayList<>();
        Vec3 auraPoint = null;
        boolean everTurning = false;
        boolean inRoom = false;
        long since = c.hx().head();
        try {
            c.ctx().waitTicks(10);
            for (int i = 0; i < 3; i++) {
                long t = tpStep != null && i == 0
                        ? c.hx().call("entity.teleport", "uuid", uuid, "x", x0, "y", 151.0, "z", -417.5).getAsLong()
                        : c.hx().call("entity.nudge", "uuid", uuid, "dx", 1.2).getAsLong();
                if (firstTick < 0) {
                    firstTick = t;
                }
                c.ctx().waitTicks(1);
            }
            c.waitUntil("the trip is tracked", mc -> endVector() != null, 100);
            tracked = true;
            end = c.onClient(mc -> endVector());
            Integer delay = nextSummonDelay;
            nextSummonDelay = null;
            lastSummonTick = -1;
            lastClicksBeforeSummon = -1;
            int summonAt = delay == null ? 0 : Math.max(0, (int) Math.ceil(due) - 3 + delay);
            Vec3 chest = end.add(summonDx, 1.4, 0);
            int ticks = (int) Math.ceil(due) + extra;
            for (int i = 0; i < ticks; i++) {
                final int tick = i;
                if (nextServerStep != null) {
                    nextServerStep.accept(i);
                }
                if (removeStandAt != null && i == removeStandAt) {
                    c.hx().call("entity.remove", "uuid", uuid);
                }
                if (i == summonAt && mob != Mob.NONE) {
                    lastClicksBeforeSummon = intStat(c, "triggerClicks") - clicks0;
                    c.server().command(String.format(Locale.ROOT, "summon husk %.3f %.3f %.3f {Tags:[\"bc1015\"],NoAI:1b,"
                                    + "NoGravity:1b,CustomName:\"%s\",Silent:1b,PersistenceRequired:1b,Health:%s%s}",
                            end.x + summonDx, end.y, end.z, huskName, mob == Mob.FRAGILE ? "1f" : mob == Mob.WEAK ? "3f" : "20f", mob == Mob.INVULNERABLE ? ",Invulnerable:1b" : ""));
                    lastSummonTick = c.hx().call("server.tick").getAsLong();
                }
                Object[] r = c.onClient(mc -> {
                    if (aimManually) {
                        float[] l = look(mc, chest);
                        mc.player.setYRot(l[0]);
                        mc.player.setXRot(l[1]);
                    }
                    if (each != null) {
                        each.accept(tick, mc);
                    }
                    boolean turning = Mod.isCheat() && (Boolean) Mod.staticCall(BC, "auraTurning");
                    return new Object[]{new float[]{mc.player.getYRot(), mc.player.getXRot()},
                            Mod.staticCall(BC, "lastAuraPoint"), turning, Mod.staticCall(BC, "lastInBloodRoom")};
                });
                looks.add((float[]) r[0]);
                if (r[1] != null) {
                    auraPoint = (Vec3) r[1];
                }
                everTurning |= (Boolean) r[2];
                inRoom |= (Boolean) r[3];
                c.ctx().waitTicks(1);
            }
        } finally {
            nextServerStep = null;
            c.hx().call("entity.remove", "uuid", uuid);
            c.server().command("kill @e[tag=bc1015]");
            c.ctx().waitTicks(5);
        }
        List<Long> serverAttacks = new ArrayList<>();
        for (JsonObject e : c.hx().events(since, "attack_entity")) {
            serverAttacks.add(e.get("tick").getAsLong());
        }
        double killAt = c.onClient(mc -> (Double) Mod.staticCall(BC, "lastKillableAt"));
        double trig = c.onClient(mc -> (Double) Mod.staticCall(BC, "lastTriggerAt"));
        int clicks = intStat(c, "triggerClicks") - clicks0;
        int attacks = intStat(c, "triggerAttacks") - attacks0;
        int retries = (cheat ? intStat(c, "triggerRetries") : 0) - retries0;
        return new Trip(tracked, end, killAt, trig, clicks, attacks, retries, serverAttacks, firstTick, due, looks,
                auraPoint, everTurning, inRoom);
    }

    private static String fmt(Trip t) {
        return String.format(Locale.ROOT, "kill timer at %.1f ticks left, first click %s, %d click(s) (%d retries), %d"
                        + " attack(s), server attack ticks %s (first %s), in room %s",
                t.killAt, Double.isNaN(t.triggerAt) ? "none" : String.format(Locale.ROOT, "at %.1f", t.triggerAt),
                t.clicks, t.retries, t.attacks, t.serverAttacks, t.serverLead() == null ? "never"
                        : String.format(Locale.ROOT, "%.1f ticks before due", t.serverLead()), t.inRoom);
    }

    /** Per-tick changes of a look sequence, {yaw, pitch}. */
    private static List<float[]> steps(List<float[]> looks) {
        List<float[]> out = new ArrayList<>();
        for (int i = 1; i < looks.size(); i++) {
            float dy = net.minecraft.util.Mth.wrapDegrees(looks.get(i)[0] - looks.get(i - 1)[0]);
            float dp = looks.get(i)[1] - looks.get(i - 1)[1];
            out.add(new float[]{dy, dp});
        }
        return out;
    }

    private static double gcd(Minecraft mc) {
        double f = mc.options.sensitivity().get() * 0.6 + 0.2;
        return f * f * f * 8.0 * 0.15;
    }

    /** Whether his body look from the eye meets {@code box}. */
    private static boolean bodyHits(Minecraft mc, AABB box) {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 dir = Vec3.directionFromRotation(mc.player.getXRot(), mc.player.getYRot());
        return box.clip(eye, eye.add(dir.scale(10))).isPresent() || box.contains(eye);
    }

    /** Yaw that puts the landing spot ~70 degrees to his right. */
    private static final float AWAY_YAW = 26.6f + 70f;

    // ---- 177 --------------------------------------------------------------------------------------------------------

    private static void offset(Session c) {
        boolean cheat = Mod.isCheat();
        try (HxKit.Settings s = base(c)) {
            watcher(c, WATCHER_IN);
            if (cheat) {
                s.with(BC_CFG, "TriggerBotEnabled", true);
                int def = c.onClient(mc -> (Integer) Mod.call(Mod.cfg(BC_CFG), "getTriggerOffsetTicks"));
                c.note("Trigger Offset as he finds it: " + def);
            }
            // Positive Trigger Offset is LATE since mod doc-blood-1101 (killer560, 2026-11-01: slider left early, right late).
            int[] offsets = cheat ? new int[]{1, 0, -2, 5} : new int[]{0};
            for (int off : offsets) {
                if (cheat) {
                    c.onClient(mc -> {
                        Mod.call(Mod.cfg(BC_CFG), "setTriggerOffsetTicks", off);
                        return null;
                    });
                }
                stand(c, 0f, 0f);
                Trip t = trip(c, Mob.HURTABLE, true, 20, null);
                c.note("offset " + off + ": " + fmt(t));
                c.check(t.tracked, "offset " + off + ": the trip was never tracked");
                c.check(!Double.isNaN(t.killAt), "offset " + off + ": the kill timer never went off");
                if (!cheat) {
                    c.check(t.clicks == 0 && t.serverAttacks.isEmpty(), "legit jar: the Trigger Bot clicked");
                    continue;
                }
                c.check(t.clicks == 1 && t.attacks == 1, "offset " + off + ": want one attack, got " + t.clicks
                        + " click(s), " + t.attacks + " attack(s)");
                double late = t.killAt - t.triggerAt;
                c.check(Math.abs(late - off) < 0.01, String.format(Locale.ROOT,
                        "offset %d: the click went %.1f ticks after the kill timer, want %d", off, late, off));
                if (off == 1) {
                    Double lead = t.serverLead();
                    c.check(lead != null && lead < 0, "offset +1: the attack reached the server " + lead
                            + " ticks BEFORE the countdown's end, want after it");
                }
            }
        } finally {
            c.hx().removeStands();
        }
    }

    // ---- 178 --------------------------------------------------------------------------------------------------------

    private static void room(Session c) {
        boolean cheat = Mod.isCheat();
        try (HxKit.Settings s = base(c)) {
            if (cheat) {
                s.with(BC_CFG, "TriggerBotEnabled", true).with(BC_CFG, "AuraEnabled", true);
            }
            // Outside: the Watcher one room tile over. He aims at the mob himself (so only the room gate can stop the
            // Trigger Bot), and the Aura must not even pick a target.
            watcher(c, WATCHER_OUT);
            stand(c, 0f, 0f);
            Trip out = trip(c, Mob.HURTABLE, true, 20, null);
            c.note("Watcher one tile over: " + fmt(out) + ", aura turning " + out.everTurning + ", aura point "
                    + out.auraPoint);
            c.check(out.tracked, "outside: the trip was never tracked (the overlay still has to work outside)");
            c.check(!out.inRoom, "outside: Blood Camp thought he was in the Blood Room");
            c.check(out.clicks == 0 && out.serverAttacks.isEmpty(), "outside the Blood Room the Trigger Bot clicked "
                    + out.clicks + " time(s)");
            c.check(!out.everTurning && out.auraPoint == null, "outside the Blood Room the Aura aimed at " + out.auraPoint);
            // Back in his tile: the same trip is clicked (control - the case CAN see a click).
            watcher(c, WATCHER_IN);
            stand(c, 0f, 0f);
            Trip in = trip(c, Mob.HURTABLE, true, 20, null);
            c.note("Watcher in his tile: " + fmt(in));
            c.check(in.inRoom, "in his tile Blood Camp did not see the Blood Room");
            if (cheat) {
                c.check(in.clicks >= 1 && !in.serverAttacks.isEmpty(), "control: in the Blood Room nothing was clicked");
            }
        } finally {
            c.hx().removeStands();
        }
    }

    // ---- 179 --------------------------------------------------------------------------------------------------------

    private static void aimHeight(Session c) {
        boolean cheat = Mod.isCheat();
        try (HxKit.Settings s = base(c)) {
            if (cheat) {
                s.with(BC_CFG, "AuraEnabled", true);
            }
            watcher(c, WATCHER_IN);
            // Empty landing spot: 1.5 above it.
            stand(c, AWAY_YAW, 10f);
            Trip empty = trip(c, Mob.NONE, false, 10, null);
            // A husk on it: its upper chest; and his body look ends on the husk.
            stand(c, AWAY_YAW, 10f);
            boolean[] onHusk = {false};
            int[] lastTick = {-1};
            Trip husk = trip(c, Mob.HURTABLE, false, 10, (i, mc) -> {
                if (i > lastTick[0]) {
                    lastTick[0] = i;
                    Vec3 e = endVector();
                    if (e != null) {
                        onHusk[0] = bodyHits(mc, new AABB(e.x - 0.3, e.y + 1.0, e.z - 0.3, e.x + 0.3, e.y + 1.95, e.z + 0.3));
                    }
                }
            });
            c.note("empty spot: aura point " + empty.auraPoint + " for landing " + empty.end);
            c.note("husk: aura point " + husk.auraPoint + " for landing " + husk.end + "; body ends on its upper half "
                    + onHusk[0]);
            if (!cheat) {
                c.check(empty.auraPoint == null && husk.auraPoint == null, "legit jar: an Aura aimed");
                return;
            }
            c.check(empty.auraPoint != null && husk.auraPoint != null, "the Aura never aimed");
            double hEmpty = empty.auraPoint.y - empty.end.y;
            double hHusk = husk.auraPoint.y - husk.end.y;
            c.check(hEmpty >= 1.3 && hEmpty <= 1.7, String.format(Locale.ROOT,
                    "empty landing spot: aimed %.2f above it, want about 1.5 (it used to be 0, the feet)", hEmpty));
            c.check(hHusk >= 1.2 && hHusk <= 1.6, String.format(Locale.ROOT,
                    "husk (1.95 tall): aimed %.2f above its feet, want its upper chest (~1.4)", hHusk));
            c.check(onHusk[0], "the Aura's turn did not end with his body on the husk's upper half");
        } finally {
            c.hx().removeStands();
        }
    }

    // ---- 180 --------------------------------------------------------------------------------------------------------

    private static void humanTurn(Session c) {
        boolean cheat = Mod.isCheat();
        try (HxKit.Settings s = base(c)) {
            if (cheat) {
                s.with(BC_CFG, "AuraEnabled", true);
            }
            watcher(c, WATCHER_IN);
            stand(c, AWAY_YAW, 10f);
            // One hit kills it, so the Aura (which now attacks) has no second approach to overwrite the reaction time.
            Trip t = trip(c, Mob.FRAGILE, false, 10, null);
            List<float[]> st = steps(t.looks);
            double total = 0;
            int moving = 0;
            float maxY = 0;
            float maxP = 0;
            int offGrid = 0;
            double g = c.onClient(HxBloodCampCases::gcd);
            int firstMove = -1;
            for (int i = 0; i < st.size(); i++) {
                float[] d = st.get(i);
                double m = Math.hypot(d[0], d[1]);
                total += m;
                if (m > 1e-4) {
                    moving++;
                    if (firstMove < 0) {
                        firstMove = i;
                    }
                    for (float v : d) {
                        double counts = v / g;
                        if (Math.abs(counts - Math.round(counts)) > 0.02) {
                            offGrid++;
                        }
                    }
                }
                maxY = Math.max(maxY, Math.abs(d[0]));
                maxP = Math.max(maxP, Math.abs(d[1]));
            }
            if (!cheat) {
                c.check(total < 0.01, "legit jar: he was turned " + total + " degrees");
                return;
            }
            float modMaxY = c.onClient(mc -> (Float) Mod.staticCall(BC, "auraMaxTickYaw"));
            float modMaxP = c.onClient(mc -> (Float) Mod.staticCall(BC, "auraMaxTickPitch"));
            int reaction = intStat(c, "auraLastReactionTicks");
            StringBuilder trace = new StringBuilder();
            for (float[] d : st) {
                trace.append(String.format(Locale.ROOT, " %.2f/%.2f", d[0], d[1]));
            }
            c.note(String.format(Locale.ROOT, "turned %.1f deg over %d moving ticks; biggest tick %.2f yaw / %.2f pitch"
                            + " (mod's own %.2f / %.2f); reaction %d ticks; %d step(s) off the %.4f-degree mouse grid",
                    total, moving, maxY, maxP, modMaxY, modMaxP, reaction, offGrid, g));
            c.note("per-tick steps:" + trace);
            c.check(total >= 40, "the Aura turned him only " + total + " degrees - nothing to judge");
            c.check(reaction >= 3, "the turn started " + reaction + " ticks after the mob was picked, want a reaction (>= 3)");
            c.check(moving >= 5, "the turn took " + moving + " ticks, a snap");
            c.check(maxY <= 26.01f && maxP <= 16.01f && modMaxY <= 26.01f && modMaxP <= 16.01f, String.format(Locale.ROOT,
                    "a tick moved %.2f yaw / %.2f pitch, over the 26 / 16 a tick a hand does", Math.max(maxY, modMaxY),
                    Math.max(maxP, modMaxP)));
            // Not snappy: the first moving tick is a small part of the turn (a bell, not an exponential's big first step).
            float[] f = st.get(firstMove);
            double firstStep = Math.hypot(f[0], f[1]);
            c.check(firstStep < total * 0.2, String.format(Locale.ROOT,
                    "the first moving tick carried %.1f of %.1f degrees - a snap, not a hand speeding up", firstStep, total));
            c.check(offGrid == 0, offGrid + " per-tick rotation change(s) are not whole mouse counts");
        } finally {
            c.hx().removeStands();
        }
    }

    // ---- 181 --------------------------------------------------------------------------------------------------------

    private static void mouse(Session c) {
        if (!Mod.isCheat()) {
            c.note("legit jar: no Aura");
            return;
        }
        try (HxKit.Settings s = base(c)) {
            s.with(BC_CFG, "AuraEnabled", true);
            watcher(c, WATCHER_IN);
            for (boolean freecam : new boolean[]{false, true}) {
                c.onClient(mc -> {
                    Mod.call(Mod.cfg(BC_CFG), "setAuraFreecam", freecam);
                    return null;
                });
                stand(c, AWAY_YAW, 10f);
                int take0 = intStat(c, "auraTakeovers");
                float[] startLook = {AWAY_YAW, 10f};
                int[] movedAt = {-1};
                float[] afterMouse = new float[4]; // body yaw, pitch, view yaw (NaN = not held), owns camera
                Trip t = trip(c, Mob.HURTABLE, false, 20, (i, mc) -> {
                    float yaw = mc.player.getYRot();
                    if (movedAt[0] < 0 && Math.abs(net.minecraft.util.Mth.wrapDegrees(yaw - startLook[0])) > 8f) {
                        movedAt[0] = i;
                        // His mouse: 200 counts right (30 degrees), the way MouseHandler turns him.
                        mc.player.turn(200.0, 0.0);
                    } else if (movedAt[0] >= 0 && i == movedAt[0] + 2) {
                        afterMouse[0] = mc.player.getYRot();
                        afterMouse[1] = mc.player.getXRot();
                        afterMouse[2] = (Float) Mod.staticCall(VF, "viewYaw");
                        afterMouse[3] = (Boolean) Mod.staticCall(BC, "auraOwnsCamera") ? 1 : 0;
                    }
                });
                int takeovers = intStat(c, "auraTakeovers") - take0;
                // After the mouse: no aura write for the yield (10 ticks): the look stays where the mouse left it.
                int from = movedAt[0] + 2;
                double drift = 0;
                for (int i = from + 1; i < Math.min(t.looks.size(), from + 8); i++) {
                    drift = Math.max(drift, Math.abs(net.minecraft.util.Mth.wrapDegrees(t.looks.get(i)[0] - afterMouse[0])));
                }
                c.note(String.format(Locale.ROOT, "freecam %s: aura moved him at tick %d, mouse +30 deg; takeovers %d;"
                                + " body after %.2f/%.2f, camera %s, aura owns camera %s; drift over the next 6 ticks %.3f",
                        freecam, movedAt[0], takeovers, afterMouse[0], afterMouse[1],
                        Float.isNaN(afterMouse[2]) ? "free" : String.valueOf(afterMouse[2]), afterMouse[3] == 1, drift));
                c.check(movedAt[0] >= 0, "freecam " + freecam + ": the Aura never turned him - no mouse to take over from");
                c.check(takeovers == 1, "freecam " + freecam + ": the mouse movement was not seen (" + takeovers + ")");
                c.check(drift < 0.01, "freecam " + freecam + ": the Aura kept turning him after his mouse moved ("
                        + drift + " deg)");
                if (freecam) {
                    c.check(Float.isNaN(afterMouse[2]) && afterMouse[3] == 0,
                            "freecam: the camera was not handed back after his mouse moved");
                }
            }
        } finally {
            c.hx().removeStands();
        }
    }

    // ---- 182 --------------------------------------------------------------------------------------------------------

    private static void missRetry(Session c) {
        boolean cheat = Mod.isCheat();
        try (HxKit.Settings s = base(c)) {
            if (cheat) {
                s.with(BC_CFG, "TriggerBotEnabled", true);
            }
            watcher(c, WATCHER_IN);
            stand(c, 0f, 0f);
            Trip miss = trip(c, Mob.INVULNERABLE, true, 40, null);
            c.note("husk that takes no damage: " + fmt(miss));
            stand(c, 0f, 0f);
            Trip hit = trip(c, Mob.HURTABLE, true, 40, null);
            c.note("husk that is hurt: " + fmt(hit));
            if (!cheat) {
                c.check(miss.clicks == 0 && hit.clicks == 0 && miss.serverAttacks.isEmpty(),
                        "legit jar: the Trigger Bot clicked");
                return;
            }
            c.check(miss.retries >= 1 && miss.serverAttacks.size() >= 2, "a hit that did no damage was not retried ("
                    + miss.clicks + " click(s), " + miss.serverAttacks.size() + " attack(s) at the server)");
            c.check(miss.clicks <= 5, "more than the 4 retries allowed: " + miss.clicks + " clicks");
            if (miss.serverAttacks.size() >= 2) {
                long gap = miss.serverAttacks.get(1) - miss.serverAttacks.get(0);
                c.check(gap >= 2 && gap <= 12, "the retry came " + gap + " ticks after the first attack, want right after"
                        + " the hit window (a round trip + 4 ticks)");
            }
            c.check(hit.clicks == 1 && hit.retries == 0 && hit.serverAttacks.size() == 1,
                    "a hit that landed was clicked again: " + hit.clicks + " click(s), " + hit.serverAttacks.size()
                            + " attack(s)");
        } finally {
            c.hx().removeStands();
        }
    }

    // ---- 183 --------------------------------------------------------------------------------------------------------

    private static void freecam(Session c) {
        if (!Mod.isCheat()) {
            c.note("legit jar: no Aura");
            return;
        }
        try (HxKit.Settings s = base(c)) {
            s.with(BC_CFG, "AuraEnabled", true).with(BC_CFG, "AuraFreecam", true).with(BC_CFG, "TriggerBotEnabled", true);
            watcher(c, WATCHER_IN);
            stand(c, AWAY_YAW, 10f);
            List<float[]> views = new ArrayList<>();
            List<Boolean> held = new ArrayList<>();
            Trip t = trip(c, Mob.FRAGILE, false, 40, (i, mc) -> {
                views.add(new float[]{(Float) Mod.staticCall(VF, "viewYaw"), (Float) Mod.staticCall(VF, "viewPitch")});
                held.add((Boolean) Mod.staticCall(VF, "isHeld"));
            });
            double maxBodyTurn = 0;
            double maxViewMove = 0;
            int heldTicks = 0;
            for (int i = 0; i < t.looks.size(); i++) {
                maxBodyTurn = Math.max(maxBodyTurn, Math.abs(net.minecraft.util.Mth.wrapDegrees(t.looks.get(i)[0] - AWAY_YAW)));
                if (held.get(i)) {
                    heldTicks++;
                    maxViewMove = Math.max(maxViewMove, Math.abs(net.minecraft.util.Mth.wrapDegrees(views.get(i)[0] - AWAY_YAW))
                            + Math.abs(views.get(i)[1] - 10f));
                }
            }
            float[] last = t.looks.get(t.looks.size() - 1);
            boolean heldAtEnd = held.get(held.size() - 1);
            double backOff = Math.abs(net.minecraft.util.Mth.wrapDegrees(last[0] - AWAY_YAW)) + Math.abs(last[1] - 10f);
            c.note(String.format(Locale.ROOT, "body turned up to %.1f deg while the camera was held %d ticks and moved"
                            + " %.3f deg; %s; at the end camera held %s, body %.2f deg off where the camera was",
                    maxBodyTurn, heldTicks, maxViewMove, fmt(t), heldAtEnd, backOff));
            c.check(maxBodyTurn >= 40, "the Aura turned his body only " + maxBodyTurn + " degrees");
            c.check(heldTicks >= 5, "Freecam Mode never held the camera");
            c.check(maxViewMove < 0.01, "the camera moved " + maxViewMove + " degrees while the Aura turned him");
            // The Aura attacks too since bloodcamp-2-1015, so a second hit is allowed; the body's look did the hitting.
            c.check(t.attacks >= 1 && !t.serverAttacks.isEmpty(), "the Trigger Bot's attack (body on the husk, camera"
                    + " away) never landed");
            c.check(!heldAtEnd && backOff < 0.6, "after the mob was hit the body did not come back under the camera"
                    + " and release it (held " + heldAtEnd + ", " + backOff + " deg off)");
        } finally {
            c.hx().removeStands();
        }
    }

    // ---- bloodcamp-2-1015 helpers ----------------------------------------------------------------------------------

    /** A stone wall between him (block z -420) and the landing spot (block z -418): block row z -419. */
    private static void wall(Session c, boolean on) {
        c.server().command("fill -424 151 -419 -416 154 -419 " + (on ? "minecraft:stone" : "minecraft:air"));
        c.ctx().waitTicks(3);
    }

    /** {@code stack} in hotbar slot 0, selected. */
    private static void holding(Session c, String stack) {
        c.hx().give(0, stack);
        c.onClient(mc -> {
            mc.player.getInventory().setSelectedSlot(0);
            return null;
        });
        c.ctx().waitTicks(5);
    }

    /** The hx session's server reads as Hypixel (its label); this makes it read as a plain server for a while, so the
     *  vanilla cooldown applies. The dialled address is unchanged. */
    private static HxKit.Settings vanillaServer(Session c) {
        String ipWas = c.onClient(mc -> mc.getCurrentServer().ip);
        return new HxKit.Settings(c)
                .custom(() -> Minecraft.getInstance().getCurrentServer().ip = "localhost",
                        () -> Minecraft.getInstance().getCurrentServer().ip = ipWas);
    }

    private static List<Long> attacksSince(Session c, long since) {
        List<Long> out = new ArrayList<>();
        for (JsonObject e : c.hx().events(since, "attack_entity")) {
            out.add(e.get("tick").getAsLong());
        }
        return out;
    }

    private static List<Long> gaps(List<Long> ticks) {
        List<Long> out = new ArrayList<>();
        for (int i = 1; i < ticks.size(); i++) {
            out.add(ticks.get(i) - ticks.get(i - 1));
        }
        return out;
    }

    private static int aliveTagged(Session c, String tag) {
        return c.onClient(mc -> {
            int n = 0;
            for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
                if (e instanceof net.minecraft.world.entity.LivingEntity && !(e instanceof net.minecraft.world.entity.player.Player)
                        && !(e instanceof net.minecraft.world.entity.decoration.ArmorStand) && e.isAlive() && !e.isRemoved()
                        && e.position().distanceTo(new Vec3(PX, 151, PZ)) < 6) {
                    n++;
                }
            }
            return n;
        });
    }

    /** Husks standing on the floor of the Blood Room beside him, no trip anywhere. Returns the server's attack ticks. */
    private static List<Long> floorHusks(Session c, String extraNbt, double[][] at, int ticks, boolean untilDead) {
        long since = c.hx().head();
        for (double[] p : at) {
            c.server().command(String.format(Locale.ROOT, "summon husk %.2f 151 %.2f {Tags:[\"bcfloor\"],NoAI:1b,Silent:1b,"
                    + "PersistenceRequired:1b%s}", p[0], p[1], extraNbt));
        }
        try {
            for (int i = 0; i < ticks; i++) {
                c.ctx().waitTicks(1);
                if (untilDead && i > 20 && aliveTagged(c, "bcfloor") == 0) {
                    break;
                }
            }
        } finally {
            c.server().command("kill @e[tag=bcfloor]");
            c.ctx().waitTicks(5);
        }
        return attacksSince(c, since);
    }

    // ---- 184 --------------------------------------------------------------------------------------------------------

    private static void walls(Session c) {
        boolean cheat = Mod.isCheat();
        try (HxKit.Settings s = base(c)) {
            watcher(c, WATCHER_IN);
            wall(c, true);
            try {
                // Aura alone: a mob behind a wall is not tracked, aimed at or hit.
                if (cheat) {
                    c.onClient(mc -> {
                        Mod.call(Mod.cfg(BC_CFG), "setAuraEnabled", true);
                        return null;
                    });
                }
                stand(c, AWAY_YAW, 10f);
                Trip a = trip(c, Mob.HURTABLE, false, 20, null);
                float[] f = a.looks.get(0);
                float[] l = a.looks.get(a.looks.size() - 1);
                double turned = Math.abs(net.minecraft.util.Mth.wrapDegrees(l[0] - f[0])) + Math.abs(l[1] - f[1]);
                c.note("wall, Aura: " + fmt(a) + String.format(Locale.ROOT, ", turned %.2f, aura point %s", turned,
                        a.auraPoint));
                c.check(a.tracked, "the trip behind the wall was never tracked (the overlay still has to work)");
                c.check(!a.everTurning && a.auraPoint == null && turned < 0.01, "the Aura aimed at a mob behind a wall");
                c.check(a.serverAttacks.isEmpty(), "something attacked the mob behind the wall");
                // Trigger Bot alone, his own aim on the mob's chest - through the wall.
                if (cheat) {
                    c.onClient(mc -> {
                        Mod.call(Mod.cfg(BC_CFG), "setAuraEnabled", false);
                        Mod.call(Mod.cfg(BC_CFG), "setTriggerBotEnabled", true);
                        return null;
                    });
                }
                stand(c, 0f, 0f);
                Trip b = trip(c, Mob.HURTABLE, true, 20, null);
                c.note("wall, Trigger Bot aimed through it: " + fmt(b));
                c.check(b.clicks == 0 && b.serverAttacks.isEmpty(), "the Trigger Bot clicked through a wall: "
                        + b.clicks + " click(s), " + b.serverAttacks.size() + " attack(s)");
            } finally {
                wall(c, false);
            }
            // Control: the same trip with no wall is clicked.
            stand(c, 0f, 0f);
            Trip open = trip(c, Mob.HURTABLE, true, 20, null);
            c.note("no wall, Trigger Bot: " + fmt(open));
            if (cheat) {
                c.check(open.clicks >= 1 && !open.serverAttacks.isEmpty(), "control: with no wall nothing was clicked");
            } else {
                c.check(open.clicks == 0, "legit jar: the Trigger Bot clicked");
            }
        } finally {
            c.hx().removeStands();
        }
    }

    // ---- 185 --------------------------------------------------------------------------------------------------------

    private static final double[][] FLOOR = {{-421.5, -417.5}, {-417.5, -421.0}};

    private static void floorMobs(Session c) {
        boolean cheat = Mod.isCheat();
        try (HxKit.Settings s = base(c)) {
            watcher(c, WATCHER_IN);
            holding(c, "minecraft:iron_sword");
            stand(c, AWAY_YAW + 60f, 10f);
            // The blood mob lands while the Aura is off (he is not there yet); it is switched on 6 ticks after the mob is
            // due - "I run into the room after the kill timer and there are mobs on the floor".
            int aura0 = cheat ? intStat(c, "auraAttacks") : 0;
            int[] onAt = {-1};
            Trip t = trip(c, Mob.WEAK, false, 40, (i, mc) -> {
                if (onAt[0] < 0 && cheat && i >= 82) {
                    onAt[0] = i;
                    Mod.call(Mod.cfg(BC_CFG), "setAuraEnabled", true);
                }
            });
            int auraAttacks = (cheat ? intStat(c, "auraAttacks") : 0) - aura0;
            c.note("blood mob already on the floor, Aura switched on after its timer: " + fmt(t) + ", aura attacks "
                    + auraAttacks + ", attack gaps " + gaps(t.serverAttacks));
            if (!cheat) {
                c.check(t.serverAttacks.isEmpty(), "legit jar: something attacked");
                return;
            }
            c.check(auraAttacks >= 1 && !t.serverAttacks.isEmpty(), "the Aura did not go for the blood mob already on"
                    + " the floor");
            c.check(gaps(t.serverAttacks).stream().allMatch(g -> g >= 10), "attacks came faster than 10 ticks: "
                    + gaps(t.serverAttacks));
        } finally {
            holding(c, "minecraft:air");
            c.hx().removeStands();
        }
    }

    // ---- 186 --------------------------------------------------------------------------------------------------------

    private static void attackSpeed(Session c) {
        boolean cheat = Mod.isCheat();
        try (HxKit.Settings s = base(c)) {
            watcher(c, WATCHER_IN);
            holding(c, "minecraft:iron_sword");
            // (a) Trigger Bot retries on a husk that takes no damage (the session reads as Hypixel, 0 Attack Speed): never
            // sooner than 10 ticks apart. On main they came every 6.
            if (cheat) {
                s.with(BC_CFG, "TriggerBotEnabled", true);
            }
            stand(c, 0f, 0f);
            Trip t = trip(c, Mob.INVULNERABLE, true, 70, null);
            List<Long> g = gaps(t.serverAttacks);
            c.note("Trigger Bot, iron sword, Hypixel 0 Attack Speed: " + fmt(t) + ", gaps " + g);
            if (!cheat) {
                c.check(t.serverAttacks.isEmpty(), "legit jar: the Trigger Bot clicked");
                return;
            }
            c.check(t.serverAttacks.size() >= 2, "no retry to measure (" + t.serverAttacks.size() + " attack(s))");
            c.check(g.stream().allMatch(x -> x >= 10), "Trigger Bot clicks came faster than 10 ticks: " + g);
            // (b) The Aura on a blood mob that takes no damage: round(10 / (1 + AS/100)) ticks apart on Hypixel, and the
            // vanilla cooldown (an iron sword's 12.5, so 13) on a plain server.
            c.onClient(mc -> {
                Mod.call(Mod.cfg(BC_CFG), "setTriggerBotEnabled", false);
                Mod.call(Mod.cfg(BC_CFG), "setAuraEnabled", true);
                return null;
            });
            String[][] weapons = {{"minecraft:iron_sword", "10", "hypixel"},
                    {"minecraft:iron_sword[lore=[\"Attack Speed: +100%\"]]", "5", "hypixel"},
                    {"minecraft:iron_sword", "13", "vanilla"}};
            for (String[] w : weapons) {
                try (HxKit.Settings h = w[2].equals("vanilla") ? vanillaServer(c) : new HxKit.Settings(c)) {
                    holding(c, w[0]);
                    stand(c, 0f, 0f);
                    int a0 = intStat(c, "auraAttacks");
                    Trip at = trip(c, Mob.INVULNERABLE, false, 90, null);
                    int n = intStat(c, "auraAttacks") - a0;
                    List<Long> sent = clientTicks(c, "recentClickTicks");
                    List<Long> sentGaps = gaps(sent.subList(Math.max(0, sent.size() - n), sent.size()));
                    List<Long> gg = gaps(at.serverAttacks);
                    int cd = intStat(c, "lastCooldownTicks");
                    int want = Integer.parseInt(w[1]);
                    c.note("Aura on " + w[2] + ", " + w[0] + ": cooldown " + cd + " ticks, server attacks "
                            + at.serverAttacks + ", gaps " + gg + "; client gaps " + sentGaps);
                    c.check(cd == want, w[0] + ": the cooldown read " + cd + " ticks, want " + want);
                    c.check(n >= 3, w[0] + ": the Aura hit the blood mob only " + n + " time(s)");
                    // The client's own clicks are never closer than the cap; at the server a click can land a tick early
                    // or late against the one before (each end acts on packets only at its own ticks).
                    c.check(sentGaps.stream().allMatch(x -> x >= want), w[0] + ": the client clicked faster than " + want
                            + " ticks: " + sentGaps);
                    long near = sentGaps.stream().filter(x -> x <= want + 2).count();
                    c.check(near * 2 >= sentGaps.size(), w[0] + ": most hits were not at the weapon's pace (" + sentGaps
                            + ") - the cap is not what limits them");
                }
            }
        } finally {
            holding(c, "minecraft:air");
            c.hx().removeStands();
        }
    }

    // ---- 187 --------------------------------------------------------------------------------------------------------


    private static void neverMiss(Session c) {
        boolean cheat = Mod.isCheat();
        try (HxKit.Settings s = base(c)) {
            watcher(c, WATCHER_IN);
            if (cheat) {
                s.with(BC_CFG, "TriggerBotEnabled", true).with(BC_CFG, "NeverMiss", true);
            }
            // The mob turns up 6 ticks after the timer says it is due.
            stand(c, 0f, 0f);
            nextSummonDelay = 6;
            Trip on = trip(c, Mob.HURTABLE, true, 30, null);
            long summon = lastSummonTick;
            int before = lastClicksBeforeSummon;
            boolean present = cheat && c.onClient(mc -> (Boolean) Mod.staticCall(BC, "lastTriggerMobPresent"));
            c.note("Never Miss on: " + fmt(on) + "; clicks before the mob existed " + before + ", husk summoned on server"
                    + " tick " + summon + ", mob present at the click " + present);
            if (!cheat) {
                c.check(on.clicks == 0, "legit jar: the Trigger Bot clicked");
                return;
            }
            c.check(before == 0, "Never Miss swung " + before + " time(s) before the mob was there");
            c.check(on.attacks >= 1 && !on.serverAttacks.isEmpty(), "Never Miss never attacked the mob once it was there");
            long lag = on.serverAttacks.isEmpty() ? -1 : on.serverAttacks.get(0) - summon;
            c.check(lag >= 0 && lag <= 3 && present, "Never Miss's first attack reached the server " + lag
                    + " ticks after the mob appeared, want at once (0-3)");
            // Control: Never Miss off - the timer's swing goes before the mob is there, as before.
            c.onClient(mc -> {
                Mod.call(Mod.cfg(BC_CFG), "setNeverMiss", false);
                return null;
            });
            stand(c, 0f, 0f);
            nextSummonDelay = 6;
            Trip off = trip(c, Mob.HURTABLE, true, 30, null);
            c.note("Never Miss off: " + fmt(off) + "; clicks before the mob existed " + lastClicksBeforeSummon);
            c.check(lastClicksBeforeSummon >= 1, "Never Miss off: the timer's click did not go before the mob appeared");
            c.check(Math.abs((off.killAt - off.triggerAt) - 1.0) < 0.01, "Never Miss off: the first click was not one tick"
                    + " after the kill timer (" + (off.killAt - off.triggerAt) + ")");
        } finally {
            c.hx().removeStands();
        }
    }

    // ---- bloodcamp-3-1015 -------------------------------------------------------------------------------------------

    private static final String SHORTBOW = "minecraft:bow[lore=[\"Shortbow: Instantly shoots!\"]]";
    private static final String MAGE = "minecraft:blaze_rod";
    /** A spot 6 blocks from the landing spot, out of melee reach, still in his room tile. */
    private static final double[] FAR_STAND = {-414.5, -419.5};

    private static List<Long> useItemsSince(Session c, long since) {
        List<Long> out = new ArrayList<>();
        for (JsonObject e : c.hx().events(since, "use_item")) {
            out.add(e.get("tick").getAsLong());
        }
        return out;
    }

    private static int kind(Session c, String k) {
        return c.onClient(mc -> (Integer) Mod.staticCall(BC, "clicksOfKind", k));
    }

    @SuppressWarnings("unchecked")
    private static List<Long> clientTicks(Session c, String method) {
        return c.onClient(mc -> (List<Long>) Mod.staticCall(BC, method));
    }

    /** The Aura on a blood mob that takes no damage, him standing 6 blocks off, holding {@code stack}. Returns
     *  {swings, uses, attacks at server, use_item at server}; {@code regGaps} gets the client's click gaps. */
    private static long[] farAura(Session c, String stack, int extra, List<Long> regGaps) {
        holding(c, stack);
        standAt(c, FAR_STAND[0], FAR_STAND[1], 0f, 0f);
        int sw0 = kind(c, "SWING");
        int use0 = kind(c, "USE");
        long since = c.hx().head();
        Trip t;
        try {
            t = trip(c, Mob.INVULNERABLE, false, extra, null);
        } finally {
            standAt(c, PX, PZ, 0f, 0f);
        }
        List<Long> reg = clientTicks(c, "recentClickTicks");
        int n = kind(c, "SWING") - sw0 + kind(c, "USE") - use0;
        regGaps.addAll(gaps(reg.subList(Math.max(0, reg.size() - n), reg.size())));
        return new long[]{kind(c, "SWING") - sw0, kind(c, "USE") - use0, t.serverAttacks.size(),
                useItemsSince(c, since).size()};
    }

    // ---- 188 --------------------------------------------------------------------------------------------------------

    private static void ranged(Session c) {
        boolean cheat = Mod.isCheat();
        try (HxKit.Settings s = base(c)) {
            if (cheat) {
                s.with(BC_CFG, "AuraEnabled", true);
            }
            watcher(c, WATCHER_IN);
            // A mage weapon: a left click (swing) at a blood mob 6 blocks out - the beam's click, no melee attack.
            List<Long> mageGaps = new ArrayList<>();
            long[] mage = farAura(c, MAGE, 50, mageGaps);
            c.note("mage weapon, blood mob 6 blocks out: " + mage[0] + " swing(s), " + mage[1] + " use(s), " + mage[2]
                    + " attack(s) and " + mage[3] + " use_item at the server; click gaps " + mageGaps);
            // A shortbow: a right click (use) each shot.
            List<Long> bowGaps = new ArrayList<>();
            long[] bow = farAura(c, SHORTBOW, 50, bowGaps);
            c.note("shortbow, blood mob 6 blocks out: " + bow[0] + " swing(s), " + bow[1] + " use(s), " + bow[2]
                    + " attack(s) and " + bow[3] + " use_item at the server; click gaps " + bowGaps);
            // The same, behind a wall (row z -419 between the far spot and the landing spot? the far spot is east of it):
            // a wall at x -417 between him (x -414.5) and the mob (x -420.5).
            c.server().command("fill -417 151 -424 -417 154 -414 minecraft:stone");
            long[] walled;
            List<Long> wallGaps = new ArrayList<>();
            try {
                walled = farAura(c, MAGE, 30, wallGaps);
            } finally {
                c.server().command("fill -417 151 -424 -417 154 -414 minecraft:air");
            }
            c.note("mage weapon, blood mob behind a wall: " + walled[0] + " swing(s), " + walled[1] + " use(s)");
            // The Trigger Bot with a shortbow on a trip's mob: a use, not an attack.
            if (cheat) {
                c.onClient(mc -> {
                    Mod.call(Mod.cfg(BC_CFG), "setAuraEnabled", false);
                    Mod.call(Mod.cfg(BC_CFG), "setTriggerBotEnabled", true);
                    return null;
                });
            }
            holding(c, SHORTBOW);
            stand(c, 0f, 0f);
            long sinceT = c.hx().head();
            Trip t = trip(c, Mob.HURTABLE, true, 20, null);
            List<Long> tUses = useItemsSince(c, sinceT);
            c.note("Trigger Bot with a shortbow: " + fmt(t) + "; use_item at the server " + tUses);
            if (!cheat) {
                c.check(mage[0] + mage[1] + bow[0] + bow[1] == 0 && t.clicks == 0, "legit jar: something clicked");
                return;
            }
            c.check(mage[0] >= 3 && mage[1] == 0 && mage[2] == 0, "mage weapon: want left clicks (swings) at the far"
                    + " blood mob and no melee attack, got " + mage[0] + " swing(s), " + mage[1] + " use(s), " + mage[2]
                    + " attack(s)");
            c.check(bow[1] >= 3 && bow[3] >= 3 && bow[0] == 0, "shortbow: want uses at the far blood mob, got " + bow[1]
                    + " (server " + bow[3] + "), swings " + bow[0]);
            c.check(mageGaps.stream().allMatch(x -> x >= 10) && bowGaps.stream().allMatch(x -> x >= 10),
                    "a click came inside the 10-tick cap: mage " + mageGaps + ", shortbow " + bowGaps);
            c.check(walled[0] + walled[1] == 0, "the Aura clicked at a blood mob behind a wall");
            c.check(t.clicks >= 1 && !tUses.isEmpty() && t.serverAttacks.isEmpty(),
                    "Trigger Bot with a shortbow: want a use and no attack");
        } finally {
            holding(c, "minecraft:air");
            standAt(c, PX, PZ, 0f, 0f);
            c.hx().removeStands();
        }
    }

    // ---- 189 --------------------------------------------------------------------------------------------------------

    private static void humanClicks(Session c) {
        if (!Mod.isCheat()) {
            c.note("legit jar: no Aura");
            return;
        }
        try (HxKit.Settings s = base(c)) {
            s.with(BC_CFG, "AuraEnabled", true);
            c.onClient(mc -> {
                Mod.staticCall(BC, "setHumanClicks", true);
                return null;
            });
            watcher(c, WATCHER_IN);
            holding(c, "minecraft:iron_sword");
            stand(c, 0f, 0f);
            int f0 = intStat(c, "fillerClicks");
            int a0 = intStat(c, "auraAttacks");
            Trip t = trip(c, Mob.INVULNERABLE, false, 160, null);
            int fillers = intStat(c, "fillerClicks") - f0;
            int registering = intStat(c, "auraAttacks") - a0;
            List<Long> all = clientTicks(c, "allClickTicks");
            List<Long> reg = clientTicks(c, "recentClickTicks");
            int regN = Math.min(reg.size(), registering);
            List<Long> regGaps = gaps(reg.subList(reg.size() - regN, reg.size()));
            List<Long> allGaps = gaps(all.subList(Math.max(0, all.size() - (regN + fillers)), all.size()));
            double mean = allGaps.stream().mapToLong(Long::longValue).average().orElse(0);
            double sd = Math.sqrt(allGaps.stream().mapToDouble(x -> (x - mean) * (x - mean)).average().orElse(0));
            java.util.Map<Long, Long> counts = new java.util.TreeMap<>();
            for (long g : allGaps) {
                counts.merge(g, 1L, Long::sum);
            }
            long top = counts.values().stream().mapToLong(Long::longValue).max().orElse(0);
            double cps = mean > 0 ? 20.0 / mean : 0;
            long minGap = allGaps.stream().mapToLong(Long::longValue).min().orElse(0);
            c.note(String.format(Locale.ROOT, "%d between-hits clicks; all-click gaps %s (mean %.2f ticks = %.1f CPS, sd"
                            + " %.2f, most common gap %d of %d); registering gaps %s; server attacks %d",
                    fillers, counts, mean, cps, sd, top, allGaps.size(), regGaps, t.serverAttacks.size()));
            c.check(fillers >= 5, "only " + fillers + " clicks went between the registering hits");
            c.check(regGaps.stream().allMatch(g -> g == 10), "the registering hits were not exactly the 10-tick cap"
                    + " apart: " + regGaps);
            c.check(sd > 0.3 && top * 10 < allGaps.size() * 6, "the click stream is too regular (sd " + sd
                    + ", most common gap " + top + " of " + allGaps.size() + ")");
            c.check(cps >= 4 && cps <= 14 && minGap >= 2, String.format(Locale.ROOT,
                    "click rate %.1f CPS (shortest gap %d ticks) is outside a human's 4-14", cps, minGap));
        } finally {
            holding(c, "minecraft:air");
            c.hx().removeStands();
        }
    }

    // ---- bloodcamp-4-1015 -------------------------------------------------------------------------------------------

    private static final String ESP_CFG = "mobesp.MobEspConfig";
    private static final String ESP = "mobesp.MobEspFeature";

    /** A plain husk that is NOT a blood mob, named {@code name}, standing still at (x, 151, z). */
    private static void decoy(Session c, String name, double x, double z) {
        c.server().command(String.format(Locale.ROOT, "summon husk %.2f 151 %.2f {Tags:[\"bcdecoy\"],NoAI:1b,NoGravity:1b,"
                + "Silent:1b,PersistenceRequired:1b,Invulnerable:1b,CustomName:\"%s\"}", x, z, name));
        c.ctx().waitTicks(3);
    }

    private static void clearDecoys(Session c) {
        c.server().command("kill @e[tag=bcdecoy]");
        c.server().command("kill @e[tag=bcstar]");
        c.ctx().waitTicks(3);
    }

    /** The client entity id of the living husk named {@code name}, or -1. */
    private static int clientId(Minecraft mc, String name) {
        for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
            if (e.isAlive() && e.getCustomName() != null && e.getCustomName().getString().equals(name)) {
                return e.getId();
            }
        }
        return -1;
    }

    private static long namedAttacks(Session c, long since, String name) {
        return c.hx().events(since, "attack_entity").stream()
                .filter(e -> e.has("name") && e.get("name").getAsString().equals(name)).count();
    }

    // ---- 190 --------------------------------------------------------------------------------------------------------

    private static void bloodEsp(Session c) {
        try (HxKit.Settings s = base(c)) {
            // Blood Camp itself OFF: Dungeon ESP's Blood Mobs tracks the Watcher's mobs on its own.
            boolean[] was = c.onClient(mc -> new boolean[]{(Boolean) Mod.call(Mod.cfg(ESP_CFG), "getBloodMobsRaw"),
                    (Boolean) Mod.call(Mod.cfg(ESP_CFG), "getStarredMobsRaw"),
                    (Boolean) Mod.call(Mod.cfg(ESP_CFG), "isRoomScoped")});
            s.with(BC_CFG, "Enabled", false)
                    .custom(() -> Mod.call(Mod.cfg(ESP_CFG), "setBloodMobs", true),
                            () -> Mod.call(Mod.cfg(ESP_CFG), "setBloodMobs", was[0]))
                    .custom(() -> Mod.call(Mod.cfg(ESP_CFG), "setStarredMobs", true),
                            () -> Mod.call(Mod.cfg(ESP_CFG), "setStarredMobs", was[1]))
                    .custom(() -> Mod.call(Mod.cfg(ESP_CFG), "setRoomScoped", false),
                            () -> Mod.call(Mod.cfg(ESP_CFG), "setRoomScoped", was[2]));
            int red = c.onClient(mc -> (Integer) Mod.call(Mod.cfg(ESP_CFG), "getBloodColor"));
            int starColor = c.onClient(mc -> (Integer) Mod.call(Mod.cfg(ESP_CFG), "getStarredColor"));
            watcher(c, WATCHER_IN);
            stand(c, 0f, 0f);
            // A random mob, and a starred mob (a zombie with its "star ... heart" stand), both in plain view.
            decoy(c, "decoy", -417.5, -417.5);
            c.server().command("summon zombie -422.5 151 -421.5 {Tags:[\"bcstar\"],NoAI:1b,NoGravity:1b,Silent:1b,"
                    + "PersistenceRequired:1b,Invulnerable:1b,CustomName:\"starz\"}");
            c.server().command("summon armor_stand -422.5 152 -421.5 {Tags:[\"bcstar\"],Invisible:1b,NoGravity:1b,"
                    + "CustomNameVisible:1b,CustomName:\"✯ Zombie 100❤\"}");
            Object[][] seen = new Object[2][];
            nextServerStep = i -> {
                if (i == 95) {
                    // Out of the Blood Room (room tile x -8): it must stay highlighted.
                    c.server().command("tp @e[tag=bc1015] -440.5 151 -417.5");
                }
            };
            Trip t = trip(c, Mob.HURTABLE, false, 30, (i, mc) -> {
                if (i == 90 || i == 105) {
                    @SuppressWarnings("unchecked")
                    Map<Integer, Integer> drawn = (Map<Integer, Integer>) Mod.staticCall(ESP, "drawnColors");
                    int blood = clientId(mc, "blood");
                    net.minecraft.world.entity.Entity be = blood < 0 ? null : mc.level.getEntity(blood);
                    seen[i == 90 ? 0 : 1] = new Object[]{drawn.get(blood), drawn.get(clientId(mc, "decoy")),
                            drawn.get(clientId(mc, "starz")), be == null ? Double.NaN : be.getX()};
                }
            });
            c.note("blood mob ESP: in the room " + java.util.Arrays.toString(seen[0]) + " (blood, decoy, starred, x);"
                    + " moved out " + java.util.Arrays.toString(seen[1]) + "; red is " + Integer.toHexString(red)
                    + ", starred " + Integer.toHexString(starColor));
            c.check(t.tracked, "the trip was never tracked");
            c.check(seen[0] != null && Integer.valueOf(red).equals(seen[0][0]), "the blood mob was not drawn red");
            c.check(seen[0] != null && seen[0][1] == null, "a mob that is not a blood mob was highlighted");
            c.check(seen[0] != null && !Integer.valueOf(red).equals(seen[0][2]), "the starred mob was drawn as a blood mob");
            c.check(seen[1] != null && (Double) seen[1][3] < -425.0, "the blood mob was not moved out of the room");
            c.check(seen[1] != null && Integer.valueOf(red).equals(seen[1][0]),
                    "the blood mob stopped being highlighted once it left the Blood Room");
        } finally {
            nextServerStep = null;
            clearDecoys(c);
            c.hx().removeStands();
        }
    }

    // ---- 191 --------------------------------------------------------------------------------------------------------

    private static void onlyBloodMobs(Session c) {
        boolean cheat = Mod.isCheat();
        try (HxKit.Settings s = base(c)) {
            if (cheat) {
                s.with(BC_CFG, "AuraEnabled", true);
            }
            watcher(c, WATCHER_IN);
            holding(c, "minecraft:iron_sword");
            stand(c, 0f, 0f);
            // A plain mob right in front of him, in reach, in the room - closer than any blood mob.
            decoy(c, "decoy", -418.5, -418.0);
            long since = c.hx().head();
            c.ctx().waitTicks(40);
            Object point = c.onClient(mc -> Mod.staticCall(BC, "lastAuraPoint"));
            long early = namedAttacks(c, since, "decoy");
            c.note("a plain mob alone in front of him: " + early + " attack(s) on it, aura point " + point);
            Trip t = trip(c, Mob.HURTABLE, false, 30, null);
            long onDecoy = namedAttacks(c, since, "decoy");
            long onBlood = namedAttacks(c, since, "blood");
            c.note("with a blood mob landing too: " + onDecoy + " attack(s) on the plain mob, " + onBlood
                    + " on the blood mob; " + fmt(t));
            c.check(onDecoy == 0 && point == null, "the Aura went for a mob that is not a blood mob");
            if (cheat) {
                c.check(onBlood >= 1, "control: the Aura never attacked the blood mob");
            }
        } finally {
            clearDecoys(c);
            holding(c, "minecraft:air");
            c.hx().removeStands();
        }
    }

    // ---- 192 --------------------------------------------------------------------------------------------------------

    private static void lineOfFire(Session c) {
        boolean cheat = Mod.isCheat();
        try (HxKit.Settings s = base(c)) {
            if (cheat) {
                s.with(BC_CFG, "AuraEnabled", true);
            }
            watcher(c, WATCHER_IN);
            holding(c, MAGE);
            // A plain mob standing on the line from his eye to the landing spot: no click may go.
            stand(c, 0f, 0f);
            decoy(c, "decoy", -420.0, -418.5);
            int blocks0 = cheat ? intStat(c, "lineOfFireBlocks") : 0;
            int a0 = cheat ? intStat(c, "auraAttacks") : 0;
            long since = c.hx().head();
            Trip blocked = trip(c, Mob.HURTABLE, false, 30, null);
            int blocks = (cheat ? intStat(c, "lineOfFireBlocks") : 0) - blocks0;
            int auraClicks = (cheat ? intStat(c, "auraAttacks") : 0) - a0;
            long onDecoy = namedAttacks(c, since, "decoy");
            c.note("plain mob in the line of fire: aura clicks " + auraClicks + ", line-of-fire holds " + blocks
                    + ", attacks on the plain mob " + onDecoy + ", " + fmt(blocked));
            clearDecoys(c);
            // Control: nothing in the way.
            stand(c, 0f, 0f);
            int a1 = cheat ? intStat(c, "auraAttacks") : 0;
            Trip open = trip(c, Mob.HURTABLE, false, 30, null);
            int openClicks = (cheat ? intStat(c, "auraAttacks") : 0) - a1;
            c.note("nothing in the way: aura clicks " + openClicks + ", " + fmt(open));
            if (!cheat) {
                c.check(auraClicks + openClicks == 0, "legit jar: the Aura clicked");
                return;
            }
            c.check(auraClicks == 0 && blocked.serverAttacks.isEmpty() && onDecoy == 0,
                    "a click went with a plain mob in the line of fire");
            c.check(blocks >= 1, "the line-of-fire check never held a click - nothing was tested");
            c.check(openClicks >= 1, "control: with nothing in the way the Aura never clicked");
        } finally {
            clearDecoys(c);
            holding(c, "minecraft:air");
            c.hx().removeStands();
        }
    }

    // ---- 193 (bloodcamp-5-1015) ---------------------------------------------------------------------------------------

    /** A husk named {@code mobName} with a Hypixel-style name-tag stand reading {@code tag} just above it - no trip. */
    private static void taggedHusk(Session c, String mobName, String tag, double x, double z) {
        c.server().command(String.format(Locale.ROOT, "summon husk %.2f 151 %.2f {Tags:[\"bcdecoy\"],NoAI:1b,NoGravity:1b,"
                + "Silent:1b,PersistenceRequired:1b,Invulnerable:1b,CustomName:\"%s\"}", x, z, mobName));
        c.server().command(String.format(Locale.ROOT, "summon armor_stand %.2f 152.2 %.2f {Tags:[\"bcdecoy\"],Invisible:1b,"
                + "NoGravity:1b,Marker:1b,CustomNameVisible:1b,CustomName:\"%s\"}", x, z, tag));
        c.ctx().waitTicks(3);
    }

    private static void namedBloodMobs(Session c) {
        boolean cheat = Mod.isCheat();
        try (HxKit.Settings s = base(c)) {
            boolean[] was = c.onClient(mc -> new boolean[]{(Boolean) Mod.call(Mod.cfg(ESP_CFG), "getBloodMobsRaw")});
            s.custom(() -> Mod.call(Mod.cfg(ESP_CFG), "setBloodMobs", true),
                    () -> Mod.call(Mod.cfg(ESP_CFG), "setBloodMobs", was[0]));
            if (cheat) {
                s.with(BC_CFG, "AuraEnabled", true);
            }
            watcher(c, WATCHER_IN);
            holding(c, "minecraft:iron_sword");
            stand(c, AWAY_YAW, 10f);
            int red = c.onClient(mc -> (Integer) Mod.call(Mod.cfg(ESP_CFG), "getBloodColor"));
            long since = c.hx().head();
            // A Revoker already standing in the room - Blood Camp never saw it land - and a look-alike whose tag is not
            // a Hypixel mob tag (a chat-style line), both in reach.
            taggedHusk(c, "namedblood", "Revoker 1.4M❤", -421.0, -417.5);
            taggedHusk(c, "lookalike", "[VIP] Revoker: hi ❤", -417.5, -421.0);
            Object[] seen = new Object[2];
            for (int i = 0; i < 80; i++) {
                c.ctx().waitTicks(1);
                if (i == 20) {
                    Object[] r = c.onClient(mc -> {
                        @SuppressWarnings("unchecked")
                        Map<Integer, Integer> drawn = (Map<Integer, Integer>) Mod.staticCall(ESP, "drawnColors");
                        return new Object[]{drawn.get(clientId(mc, "namedblood")), drawn.get(clientId(mc, "lookalike"))};
                    });
                    seen = r;
                }
            }
            long onNamed = namedAttacks(c, since, "namedblood");
            long onLookalike = namedAttacks(c, since, "lookalike");
            c.note("named Revoker (never seen landing): ESP color " + seen[0] + ", attacks " + onNamed + "; look-alike tag:"
                    + " ESP color " + seen[1] + ", attacks " + onLookalike + "; red " + Integer.toHexString(red));
            c.check(Integer.valueOf(red).equals(seen[0]), "the named blood mob was not highlighted red");
            c.check(seen[1] == null, "a mob whose tag only looks like a blood mob's was highlighted");
            c.check(onLookalike == 0, "the Aura attacked a mob whose tag only looks like a blood mob's");
            if (cheat) {
                c.check(onNamed >= 1, "the Aura never attacked the named blood mob");
            } else {
                c.check(onNamed == 0, "legit jar: something attacked");
            }
        } finally {
            clearDecoys(c);
            holding(c, "minecraft:air");
            c.hx().removeStands();
        }
    }

    // ---- 1101 (doc-blood-1101: the to-do Doc's BLOOD CAMP STUFF and STARRED MOB HIGHLIGHT) ---------------------------

    private static final String BC_MT = "bloodcamp.BloodCampMoveTimer";
    private static final String BC_STATS = "bloodcamp.BloodCampStats";

    @SuppressWarnings("unchecked")
    private static java.util.Set<Integer> bloodIds(Minecraft mc) {
        return (java.util.Set<Integer>) Mod.staticCall(BC, "bloodMobIds");
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, Integer> drawn(Minecraft mc) {
        return (Map<Integer, Integer>) Mod.staticCall(ESP, "drawnColors");
    }

    /** An entity of {@code type} named {@code mobName} with a name-tag stand reading {@code tag} summoned right after it. */
    private static void taggedMob(Session c, String type, String mobName, String tag, double x, double z) {
        c.server().command(String.format(Locale.ROOT, "summon %s %.2f 151 %.2f {Tags:[\"bcdecoy\"],NoAI:1b,NoGravity:1b,"
                + "Silent:1b,PersistenceRequired:1b,Invulnerable:1b,CustomName:\"%s\"}", type, x, z, mobName));
        c.server().command(String.format(Locale.ROOT, "summon armor_stand %.2f 152.2 %.2f {Tags:[\"bcdecoy\"],Invisible:1b,"
                + "NoGravity:1b,Marker:1b,CustomNameVisible:1b,CustomName:\"%s\"}", x, z, tag));
        c.ctx().waitTicks(3);
    }

    /**
     * killer560, 2026-11-01: "Sometimes it is highlighting those silverfish for the esp", "it can highlight the watcher",
     * and "something is highlighting all fels ... It is coming currently from the blood mob highlight". A blood name tag
     * over a silverfish (his 2026-10-29 log: "blood mob tagged by name tag: silverfish"), a blood name tag over a zombie
     * wearing the Watcher's skull, and a "Fels" tag one room tile away must not make a blood mob; a Revoker and a Fels
     * inside the Blood Room still must (control).
     */
    private static void exclusions(Session c) {
        try (HxKit.Settings s = base(c)) {
            boolean was = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(ESP_CFG), "getBloodMobsRaw"));
            s.custom(() -> Mod.call(Mod.cfg(ESP_CFG), "setBloodMobs", true),
                    () -> Mod.call(Mod.cfg(ESP_CFG), "setBloodMobs", was));
            watcher(c, WATCHER_IN);
            stand(c, AWAY_YAW, 10f);
            int red = c.onClient(mc -> (Integer) Mod.call(Mod.cfg(ESP_CFG), "getBloodColor"));
            String wtex = c.onClient(mc -> anyOf(Mod.field(Mod.ROOT + BC, "WATCHER_SKULL_TEXTURES")));
            taggedHusk(c, "xrevoker", "Revoker 1.4M❤", -421.0, -417.5);
            taggedMob(c, "silverfish", "xsilver", "Mute 5❤", -417.5, -421.0);
            taggedHusk(c, "xfelsin", "Fels 1.2M❤", -418.5, -415.5);
            taggedHusk(c, "xfelsout", "Fels 1.2M❤", -440.5, -417.5);
            c.server().command(String.format(Locale.ROOT, "summon zombie -416.5 151 -416.5 {Tags:[\"bcdecoy\"],NoAI:1b,"
                    + "NoGravity:1b,Silent:1b,PersistenceRequired:1b,Invulnerable:1b,CustomName:\"xwatcher\","
                    + "equipment:{head:{id:\"minecraft:player_head\",count:1,components:{\"minecraft:profile\":{properties:"
                    + "[{name:\"textures\",value:\"%s\"}]}}}}}", wtex));
            c.server().command("summon armor_stand -416.5 152.2 -416.5 {Tags:[\"bcdecoy\"],Invisible:1b,NoGravity:1b,"
                    + "Marker:1b,CustomNameVisible:1b,CustomName:\"Revoker 1M❤\"}");
            c.ctx().waitTicks(30);
            Object[] r = c.onClient(mc -> {
                java.util.Set<Integer> ids = bloodIds(mc);
                Map<Integer, Integer> d = drawn(mc);
                int w = clientId(mc, "xwatcher");
                net.minecraft.world.entity.Entity we = w < 0 ? null : mc.level.getEntity(w);
                boolean skinned = we instanceof net.minecraft.world.entity.LivingEntity le
                        && le.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD)
                        .is(net.minecraft.world.item.Items.PLAYER_HEAD);
                String[] names = {"xrevoker", "xsilver", "xfelsin", "xfelsout", "xwatcher"};
                Object[] out = new Object[names.length * 2 + 1];
                for (int i = 0; i < names.length; i++) {
                    int id = clientId(mc, names[i]);
                    out[i * 2] = id >= 0 && ids.contains(id);
                    out[i * 2 + 1] = d.get(id);
                }
                out[names.length * 2] = skinned;
                return out;
            });
            c.note(String.format(Locale.ROOT, "blood mob / ESP color: revoker %s/%s, silverfish %s/%s, Fels in room %s/%s,"
                    + " Fels one tile over %s/%s, Watcher-skull zombie %s/%s (skull on it client-side: %s); red %s",
                    r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7], r[8], r[9], r[10], Integer.toHexString(red)));
            c.check(Boolean.TRUE.equals(r[0]) && Integer.valueOf(red).equals(r[1]),
                    "control: the Revoker in the Blood Room was not a highlighted blood mob");
            c.check(Boolean.TRUE.equals(r[4]), "control: a Fels inside the Blood Room was not a blood mob");
            c.check(Boolean.FALSE.equals(r[2]) && r[3] == null, "the silverfish under a blood name tag was a blood mob");
            c.check(Boolean.FALSE.equals(r[6]) && r[7] == null, "a Fels one room tile from the Watcher was a blood mob");
            if (Boolean.TRUE.equals(r[10])) {
                c.check(Boolean.FALSE.equals(r[8]) && r[9] == null, "the zombie in the Watcher's skull was a blood mob");
            } else {
                c.note("the Watcher-skull zombie's head did not reach the client - that check was not made");
            }
        } finally {
            clearDecoys(c);
            c.hx().removeStands();
        }
    }

    /**
     * killer560, 2026-11-01: "I had a primal fear spawn in the room as blood camp was happening and trigger bot would hit
     * it as well. And the mob esp would highlight it." A husk named "Primal Fear" lands where a trip ends, under his aim:
     * it must not become a blood mob, be highlighted or be attacked. Control: the same trip with a plain blood husk is.
     */
    private static void primalFearTrip(Session c) {
        boolean cheat = Mod.isCheat();
        try (HxKit.Settings s = base(c)) {
            boolean was = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(ESP_CFG), "getBloodMobsRaw"));
            s.custom(() -> Mod.call(Mod.cfg(ESP_CFG), "setBloodMobs", true),
                    () -> Mod.call(Mod.cfg(ESP_CFG), "setBloodMobs", was));
            if (cheat) {
                s.with(BC_CFG, "TriggerBotEnabled", true);
            }
            watcher(c, WATCHER_IN);
            holding(c, "minecraft:iron_sword");
            stand(c, 0f, 0f);
            long since = c.hx().head();
            Object[] seen = new Object[2];
            nextHuskName = "Primal Fear";
            Trip fear = trip(c, Mob.HURTABLE, true, 20, (i, mc) -> {
                int id = clientId(mc, "Primal Fear");
                if (id >= 0) {
                    seen[0] = Boolean.TRUE.equals(seen[0]) || bloodIds(mc).contains(id);
                    seen[1] = seen[1] != null ? seen[1] : drawn(mc).get(id);
                }
            });
            long onFear = namedAttacks(c, since, "Primal Fear");
            c.note("Primal Fear on the landing spot: blood mob " + seen[0] + ", ESP color " + seen[1] + ", attacks on it "
                    + onFear + "; " + fmt(fear));
            c.check(fear.tracked, "the trip was never tracked - nothing was tested");
            c.check(!Boolean.TRUE.equals(seen[0]) && seen[1] == null, "the Primal Fear was taken for a blood mob");
            c.check(onFear == 0 && fear.serverAttacks.isEmpty(), "the Primal Fear was attacked");
            stand(c, 0f, 0f);
            Trip control = trip(c, Mob.HURTABLE, true, 20, null);
            c.note("control, a blood husk on the spot: " + fmt(control));
            if (cheat) {
                c.check(control.attacks >= 1 && !control.serverAttacks.isEmpty(), "control: the blood husk was not attacked");
            } else {
                c.check(control.serverAttacks.isEmpty(), "legit jar: something attacked");
            }
        } finally {
            nextHuskName = null;
            holding(c, "minecraft:air");
            c.hx().removeStands();
        }
    }

    /**
     * killer560, 2026-11-01: "for aura sometimes it is a bit too snappy ... Dont have it track until the timer is about to
     * go off ... it was doing kind of this snap thing if a mob was spawned with a new one being spawned close to it."
     * Facing 70 degrees away, the Aura must pick the mob only a reaction plus its turn before the click moment (never the
     * old 35 ticks before due), still be on it to attack it, and never jump while resting on a point that moved (the
     * husk appearing 2 ticks after the mob is due moves the point from the empty spot to the husk's chest).
     */
    private static void auraLatePick(Session c) {
        boolean cheat = Mod.isCheat();
        try (HxKit.Settings s = base(c)) {
            if (cheat) {
                s.with(BC_CFG, "AuraEnabled", true);
            }
            watcher(c, WATCHER_IN);
            holding(c, "minecraft:iron_sword");
            stand(c, AWAY_YAW, 10f);
            int[] firstTurnTick = {-1};
            nextSummonDelay = 2;
            Trip t = trip(c, Mob.HURTABLE, false, 30, (i, mc) -> {
                if (firstTurnTick[0] < 0 && Mod.staticCall(BC, "lastAuraPoint") != null) {
                    firstTurnTick[0] = i;
                }
            });
            if (!cheat) {
                c.check(t.serverAttacks.isEmpty() && firstTurnTick[0] < 0, "legit jar: the Aura turned or attacked");
                return;
            }
            double pickedAt = c.onClient(mc -> (Double) Mod.staticCall(BC, "auraPickedAtRemaining"));
            float rest = c.onClient(mc -> (Float) Mod.staticCall(BC, "auraMaxRestingFrameYaw"));
            float tickYaw = c.onClient(mc -> (Float) Mod.staticCall(BC, "auraMaxTickYaw"));
            c.note(String.format(Locale.ROOT, "aura picked the mob at loop tick %d of %.1f to due, %.1f ticks before its click"
                    + " moment; biggest resting frame turn %.2f deg, biggest tick turn %.2f; %s", firstTurnTick[0], t.due,
                    pickedAt, rest, tickYaw, fmt(t)));
            c.check(firstTurnTick[0] >= 0, "the Aura never picked the mob - nothing was tested");
            c.check(!Double.isNaN(pickedAt) && pickedAt <= 24.0 && pickedAt > 0.0, "the Aura picked the mob " + pickedAt
                    + " ticks before its click moment, want within a reaction plus its turn (at most 24)");
            c.check(firstTurnTick[0] >= Math.floor(t.due) - 26, "the Aura was turning " + (t.due - firstTurnTick[0])
                    + " ticks before the mob was due (the old 35-tick start)");
            c.check(!t.serverAttacks.isEmpty(), "the Aura never attacked the mob");
            c.check(rest <= 4.0f, "while resting on the mob the Aura jumped " + rest + " degrees in one frame");
            c.check(tickYaw <= 26.0f + 1e-3f, "a tick's turn was " + tickYaw + " degrees, over the 26 cap");
        } finally {
            nextSummonDelay = null;
            holding(c, "minecraft:air");
            c.hx().removeStands();
        }
    }

    /** killer560, 2026-11-01: "Have an option to only toggle as mage." With Mage Only on and no class (not the Mage), the
     *  Trigger Bot must not click a trip he aims at; with it off it must (control). */
    private static void mageOnly(Session c) {
        boolean cheat = Mod.isCheat();
        try (HxKit.Settings s = base(c)) {
            if (cheat) {
                s.with(BC_CFG, "TriggerBotEnabled", true).with(BC_CFG, "MageOnly", true);
            }
            watcher(c, WATCHER_IN);
            stand(c, 0f, 0f);
            Trip held = trip(c, Mob.HURTABLE, true, 20, null);
            c.note("Mage Only, not the Mage: " + fmt(held));
            c.check(held.tracked, "the trip was never tracked - nothing was tested");
            c.check(held.clicks == 0 && held.serverAttacks.isEmpty(), "with Mage Only on and not the Mage it clicked");
            if (cheat) {
                c.onClient(mc -> {
                    Mod.call(Mod.cfg(BC_CFG), "setMageOnly", false);
                    return null;
                });
                stand(c, 0f, 0f);
                Trip open = trip(c, Mob.HURTABLE, true, 20, null);
                c.note("Mage Only off: " + fmt(open));
                c.check(open.clicks >= 1 && !open.serverAttacks.isEmpty(), "control: with Mage Only off it never clicked");
            }
        } finally {
            c.hx().removeStands();
        }
    }

    /**
     * killer560, 2026-11-01: "Sometimes if it lags then it wont pick up a mob spawning arrow thing either." A trip whose first
     * movement arrives as an absolute teleport packet (a 9-block step: what a server sends after a stall instead of relative
     * moves) must be tracked like one made of move packets: a path, and its landing spot one wave's length along +x.
     */
    private static void teleportStep(Session c) {
        try (HxKit.Settings s = base(c)) {
            watcher(c, WATCHER_IN);
            stand(c, 0f, 0f);
            int abs0 = intStat(c, "absoluteMoves");
            boolean firstWave = c.onClient(mc -> (Boolean) Mod.staticCall(BC_MT, "isFirstSpawns"));
            double scale = firstWave ? 16.1 : 11.9;
            double x0 = -420.5 - scale - 1.0;
            // 4 blocks back: the stand must stay within Blood Camp's 20 blocks of the Watcher before the step (a 9-block
            // step started 22 blocks out and was rightly ignored, first run).
            nextTeleportStep = 4.0;
            // The landing spot a few ticks into the trip: the server stand is moved with the teleport, and its tracker's own
            // next packet (relative to where IT last was) briefly pulls the client's copy back, which can show for a tick.
            Vec3[] settled = new Vec3[1];
            Trip t = trip(c, Mob.NONE, false, 5, (i, mc) -> {
                if (i == 10) {
                    settled[0] = endVector();
                }
            });
            if (settled[0] != null) {
                t = new Trip(t.tracked(), settled[0], t.killAt(), t.triggerAt(), t.clicks(), t.attacks(), t.retries(),
                        t.serverAttacks(), t.firstTick(), t.due(), t.looks(), t.auraPoint(), t.everTurning(), t.inRoom());
            }
            int abs = intStat(c, "absoluteMoves") - abs0;
            c.note(String.format(Locale.ROOT, "teleport first step: absolute moves taken %d, tracked %s, landing %s (stand"
                    + " started at x %.2f, want about x %.2f)", abs, t.tracked, t.end, x0, x0 + scale));
            c.check(abs >= 1, "the teleport packet was never taken as a trip step");
            c.check(t.tracked && t.end != null, "the trip that began with a teleport was never tracked");
            c.check(t.end != null && Math.abs(t.end.x - (x0 + scale)) < 1.6 && Math.abs(t.end.z - (-417.5)) < 0.6,
                    "the landing spot " + t.end + " is not " + scale + " along +x from the stand");
        } finally {
            nextTeleportStep = null;
            c.hx().removeStands();
        }
    }

    /**
     * killer560, 2026-11-01: mob ticks alive "from the second kill goes off to the second the first mob is killed ... Then
     * from there on out it should only count every tick a blood mob is alive", with a HUD line and an end-of-run party
     * message. The Watcher's lines arrive as chat; the kill timer is brought to 10 ticks away; two tagged blood husks are
     * killed 20 and 35 ticks after it. The count must be ~20 at the first kill, ~35 at the second and then stop.
     */
    private static void stats(Session c) {
        try (HxKit.Settings s = base(c)) {
            s.with(BC_CFG, "HudMobTicks", true).with(BC_CFG, "HudMobsLeft", true).with(BC_CFG, "HudCampLag", true)
                    .with(BC_CFG, "PartyMessage", true).with(BC_CFG, "PartyMessageAnyClass", false)
                    .with(BC_CFG, "PartyMessageLag", true);
            watcher(c, WATCHER_IN);
            stand(c, 0f, 0f);
            taggedHusk(c, "stat1", "Revoker 1M❤", -421.0, -417.5);
            taggedHusk(c, "stat2", "Revoker 1M❤", -417.5, -421.0);
            c.hx().chat("[BOSS] The Watcher: Ah, you've finally arrived.");
            c.ctx().waitTicks(5);
            c.hx().chat("[BOSS] The Watcher: Let's see how you can handle this.");
            c.ctx().waitTicks(5);
            int sent0 = c.onClient(mc -> (Integer) Mod.staticCall(BC_STATS, "partyMessagesSent"));
            c.onClient(mc -> {
                long elapsed = (Long) Mod.staticCall(BC_MT, "elapsedSinceGreeting");
                int lead = (Integer) Mod.call(Mod.cfg(BC_CFG), "getKillPopupLeadTicks");
                Mod.setField(BC_MT, "moveAtElapsed", elapsed + 10 + lead);
                return null;
            });
            c.waitUntil("the kill timer goes off", mc -> "FIRST".equals(Mod.staticCall(BC_STATS, "phaseName")), 60);
            long t0 = c.onClient(mc -> (Long) Mod.staticCall(BC_STATS, "ticksLost"));
            c.ctx().waitTicks(20);
            c.server().command("kill @e[name=stat1]");
            c.waitUntil("the first kill is counted", mc -> "ALIVE".equals(Mod.staticCall(BC_STATS, "phaseName")), 40);
            long first = c.onClient(mc -> (Long) Mod.staticCall(BC_STATS, "firstKillTicks"));
            c.ctx().waitTicks(15);
            long atKill2 = c.onClient(mc -> (Long) Mod.staticCall(BC_STATS, "ticksLost"));
            c.server().command("kill @e[name=stat2]");
            c.ctx().waitTicks(25);
            long[] after = c.onClient(mc -> new long[]{(Long) Mod.staticCall(BC_STATS, "ticksLost")});
            c.ctx().waitTicks(20);
            long idle = c.onClient(mc -> (Long) Mod.staticCall(BC_STATS, "ticksLost"));
            @SuppressWarnings("unchecked")
            List<String> hud = c.onClient(mc -> (List<String>) Mod.staticCall(BC_STATS, "hudLinesForTest"));
            c.hx().chat("[BOSS] The Watcher: You have proven yourself. You may pass.");
            c.ctx().waitTicks(3);
            String phase = c.onClient(mc -> (String) Mod.staticCall(BC_STATS, "phaseName"));
            boolean camped = c.onClient(mc -> (Boolean) Mod.staticCall(BC_STATS, "camped"));
            c.onClient(mc -> Mod.staticCall(BC_STATS, "endOfRunForTest"));
            String msg = c.onClient(mc -> (String) Mod.staticCall(BC_STATS, "lastMessage"));
            int sent = c.onClient(mc -> (Integer) Mod.staticCall(BC_STATS, "partyMessagesSent")) - sent0;
            c.note(String.format(Locale.ROOT, "at KILL %d; first kill at %d (want ~20); after both kills %d (want ~35); 20"
                    + " idle ticks later %d; phase after the Watcher's last line %s; camped %s; HUD %s; message \"%s\", sent %d",
                    t0, first, after[0], idle, phase, camped, hud, msg, sent));
            c.check(first >= 17 && first <= 24, "the first kill was counted at " + first + " ticks, want about 20");
            c.note("count when the second kill was sent: " + atKill2);
            c.check(atKill2 >= first + 14 && after[0] >= atKill2 && after[0] <= atKill2 + 6, "with one blood mob alive the"
                    + " count went " + first + " -> " + atKill2 + " (15 ticks) -> " + after[0] + " after its kill, want +15 then"
                    + " stop within a few ticks of the kill");
            c.check(idle == after[0], "the count kept going with no blood mob alive: " + after[0] + " -> " + idle);
            c.check("DONE".equals(phase), "the Watcher's last line did not end the camp (phase " + phase + ")");
            c.check(hud.stream().anyMatch(l -> l.startsWith("Mob Ticks: " + after[0])), "the HUD does not show the count: "
                    + hud);
            c.check(camped, "he stood in the Blood Room after the kill timer and was not counted as camping");
            c.check(msg != null && msg.startsWith("Blood Camp: " + after[0] + " ticks lost") && msg.contains("server lag"),
                    "the party message is \"" + msg + "\"");
            c.check(sent == 1, "the party message went " + sent + " times, want once");
        } finally {
            clearDecoys(c);
            c.onClient(mc -> {
                Mod.staticCall(BC_MT, "reset");
                return null;
            });
            c.hx().removeStands();
        }
    }

    // ---- 1102 (bloodleft-1102: Mobs Left from the Watcher's bar; player-model blood mobs' deaths) ----------------------

    private static final String WATCHER_BAR = "k560:watcher";

    @SuppressWarnings("unchecked")
    private static String mobsLeftLine(Session c) {
        List<String> hud = c.onClient(mc -> (List<String>) Mod.staticCall(BC_STATS, "hudLinesForTest"));
        for (String l : hud) {
            if (l.startsWith("Mobs Left: ")) {
                return l.substring("Mobs Left: ".length());
            }
        }
        return null;
    }

    private static void greet(Session c) {
        c.hx().chat("[BOSS] The Watcher: Ah, you've finally arrived.");
        c.ctx().waitTicks(5);
        c.hx().chat("[BOSS] The Watcher: Let's see how you can handle this.");
        c.ctx().waitTicks(5);
        c.waitUntil("the camp starts", mc -> "WAITING_KILL".equals(Mod.staticCall(BC_STATS, "phaseName")), 40);
    }

    /** Sets the Watcher bar to value/max and returns the Mobs Left line the HUD shows once the client has it. */
    private static String barAt(Session c, int value, int max) {
        return barWalk(c, value, max, null);
    }

    /**
     * {@link #barAt}, reading the HUD every tick until the client's bar settles (the same reading 4 ticks running after
     * the server's packet can have arrived, at most 40 ticks): the server's packet and the client's 100 ms ease take a
     * variable few ticks. Every reading on the way goes into {@code seen} when given, so a walk can check none was LOW.
     */
    private static String barWalk(Session c, int value, int max, List<String> seen) {
        c.server().command("bossbar set " + WATCHER_BAR + " max " + max);
        c.server().command("bossbar set " + WATCHER_BAR + " value " + value);
        float want = value / (float) max;
        String last = null;
        int same = 0;
        for (int t = 0; t < 40; t++) {
            c.ctx().waitTicks(1);
            Object[] r = c.onClient(mc -> new Object[]{Mod.staticCall(BC_STATS, "mobsLeftForTest"),
                    Mod.staticCall(BC_STATS, "watcherBarForTest")});
            String now = (String) r[0];
            if (seen != null) {
                seen.add(now);
            }
            boolean arrived = r[1] instanceof Float f && Math.abs(f - want) < 1e-4f;
            same = now != null && now.equals(last) && arrived ? same + 1 : 0;
            last = now;
            if (same >= 4) {
                break;
            }
        }
        return mobsLeftLine(c);
    }


    /**
     * killer560, 2026-11-02 (F7 solo, HUD "Mobs Left: 1+" with the Watcher's bar about 20% full): "if you can see that top
     * bar that is how many mobs are left. i dont want it to say 1 if one mob is on the ground unless that 1 mob is the
     * very last in the camp." A real server boss bar named "The Watcher" is driven through a whole F7 camp (16 mobs, the
     * wiki's 9 + tier) one mob at a time, and the HUD must read exactly the mobs left at every step, never "1" while more
     * than one is left; a part-drained step reads the higher count; with Odin's 15-mob size the bar's own steps calibrate
     * the size; with no bar it says "?" rather than a low guess.
     */
    private static void watcherBar(Session c) {
        try (HxKit.Settings s = base(c)) {
            s.with(BC_CFG, "HudMobsLeft", true)
                    .custom(() -> Mod.staticCall(BC_STATS, "setWatcherBarFloorForTest", "F7"),
                            () -> Mod.staticCall(BC_STATS, "setWatcherBarFloorForTest", ""));
            c.server().command("bossbar remove " + WATCHER_BAR);
            watcher(c, WATCHER_IN);
            stand(c, 0f, 0f);
            greet(c);
            String none = mobsLeftLine(c);
            c.note("no Watcher bar, spawning not done: Mobs Left " + none);
            c.check("?".equals(none), "with no Watcher bar and mobs still to come Mobs Left read \"" + none + "\", want ?");

            c.server().command("bossbar add " + WATCHER_BAR + " \"The Watcher\"");
            c.server().command("bossbar set " + WATCHER_BAR + " color red");
            c.server().command("bossbar set " + WATCHER_BAR + " players @a");
            c.server().command("bossbar set " + WATCHER_BAR + " max 16");
            c.server().command("bossbar set " + WATCHER_BAR + " value 16");
            c.waitUntil("the client shows the Watcher's bar", mc -> "16".equals(Mod.staticCall(BC_STATS, "mobsLeftForTest")), 60);
            StringBuilder walk = new StringBuilder("16");
            for (int k = 15; k >= 0; k--) {
                List<String> seen = new ArrayList<>();
                String got = barWalk(c, k, 16, seen);
                int kk = k;
                List<String> low = seen.stream().filter(v -> v != null && !"?".equals(v) && Integer.parseInt(v) < kk).toList();
                c.check(low.isEmpty(), "on the way from " + (k + 1) + " to " + k + " of 16 Mobs Left read low: " + seen);
                walk.append(' ').append(got);
                c.check(got != null, "the HUD has no Mobs Left line at " + k + "/16");
                c.check(!(k > 1 && "1".equals(got)), "Mobs Left read 1 with " + k + " mobs left in the camp");
                c.check(Integer.toString(k).equals(got), "Mobs Left read " + got + " with the bar at " + k + "/16");
            }
            int size16 = c.onClient(mc -> (Integer) Mod.staticCall(BC_STATS, "watcherBarSizeForTest"));
            c.note("F7 camp of 16, one mob at a time: " + walk + "; calibrated size " + size16);
            c.check(size16 == 16, "the bar's steps calibrated the camp to " + size16 + ", want 16");

            // Part-drained steps read the higher count: 1.7 mobs is 2, 2.5 is 3, 1.1 is 2, exactly 1 is 1.
            String p17 = barAt(c, 17, 160);
            String p25 = barAt(c, 25, 160);
            String p11 = barAt(c, 11, 160);
            String p10 = barAt(c, 10, 160);
            c.note("part steps of 16: 1.7 -> " + p17 + ", 2.5 -> " + p25 + ", 1.1 -> " + p11 + ", 1.0 -> " + p10);
            c.check("2".equals(p17) && "3".equals(p25) && "2".equals(p11), "a part-drained step read low: 1.7 -> " + p17
                    + ", 2.5 -> " + p25 + ", 1.1 -> " + p11);
            c.check("1".equals(p10), "exactly one mob left read " + p10);

            // A camp of 15 (Odin's and Noamm's 8 + floor): the first step calibrates the size from the bar.
            greet(c);
            String full15 = barAt(c, 15, 15);
            String at14 = barAt(c, 14, 15);
            int size15 = c.onClient(mc -> (Integer) Mod.staticCall(BC_STATS, "watcherBarSizeForTest"));
            String at2 = barAt(c, 2, 15);
            String at1 = barAt(c, 1, 15);
            c.note("camp of 15: full -> " + full15 + " (before any step), 14 -> " + at14 + " (size " + size15 + "), 2 -> "
                    + at2 + ", 1 -> " + at1);
            c.check(size15 == 15, "a bar stepping in fifteenths calibrated to " + size15 + ", want 15");
            c.check("14".equals(at14) && "2".equals(at2) && "1".equals(at1), "a camp of 15 read 14 -> " + at14 + ", 2 -> "
                    + at2 + ", 1 -> " + at1);
        } finally {
            c.server().command("bossbar remove " + WATCHER_BAR);
            c.onClient(mc -> {
                Mod.staticCall(BC_MT, "reset");
                return null;
            });
            c.hx().removeStands();
        }
    }

    /**
     * killer560, 2026-11-02: "blood camp hud should go away once boss is entered." The Blood Camp Stats HUD shows through
     * the camp, stays after "You have proven yourself" (Mob Ticks and Camp Lag; Mobs Left goes with the camp), and is gone
     * the moment the boss phase starts - here DungeonState's F7 boss state (its testkit override). The end-of-run stats still work in the boss.
     */
    @SuppressWarnings("unchecked")
    private static void hudBoss(Session c) {
        try (HxKit.Settings s = base(c)) {
            s.with(BC_CFG, "HudMobTicks", true).with(BC_CFG, "HudMobsLeft", true).with(BC_CFG, "HudCampLag", true)
                    .with(BC_CFG, "PartyMessage", false);
            watcher(c, WATCHER_IN);
            stand(c, 0f, 0f);
            greet(c);
            c.onClient(mc -> {
                long elapsed = (Long) Mod.staticCall(BC_MT, "elapsedSinceGreeting");
                int lead = (Integer) Mod.call(Mod.cfg(BC_CFG), "getKillPopupLeadTicks");
                Mod.setField(BC_MT, "moveAtElapsed", elapsed + 5 + lead);
                return null;
            });
            c.waitUntil("the kill timer goes off", mc -> "FIRST".equals(Mod.staticCall(BC_STATS, "phaseName")), 60);
            c.ctx().waitTicks(10);
            List<String> camp = c.onClient(mc -> (List<String>) Mod.staticCall(BC_STATS, "hudLinesForTest"));
            c.hx().chat("[BOSS] The Watcher: You have proven yourself. You may pass.");
            c.ctx().waitTicks(3);
            List<String> cleared = c.onClient(mc -> (List<String>) Mod.staticCall(BC_STATS, "hudLinesForTest"));
            // DungeonState's F7 boss state, through its testkit override (simBossBegins only holds inside a running sim:
            // DungeonState's tick clears a boss phase while no F7/M7 floor is read off the scoreboard).
            boolean overrideWas = c.onClient(mc -> (Boolean) Mod.staticCall("secrets.DungeonState", "isSimOverrideActive"));
            if (!overrideWas) {
                c.onClient(mc -> Mod.staticCall("secrets.DungeonState", "toggleSimOverride"));
            }
            c.ctx().waitTicks(2);
            Object[] boss = c.onClient(mc -> new Object[]{Mod.staticCall(BC_STATS, "hudLinesForTest"),
                    Mod.staticCall("secrets.DungeonState", "isBossPhaseActive")});
            c.onClient(mc -> Mod.staticCall(BC_STATS, "endOfRunForTest"));
            String msg = c.onClient(mc -> (String) Mod.staticCall(BC_STATS, "lastMessage"));
            c.note("camp: " + camp + "; after the last line: " + cleared + "; boss phase " + boss[1] + ": " + boss[0]
                    + "; end-of-run message \"" + msg + "\"");
            c.check(camp.size() == 3, "the HUD did not show all three lines during the camp: " + camp);
            c.check(!cleared.isEmpty() && cleared.stream().anyMatch(l -> l.startsWith("Mob Ticks: ")),
                    "the HUD went before the boss, at the camp's last line: " + cleared);
            c.check(Boolean.TRUE.equals(boss[1]), "the sim's boss start did not start the boss phase - nothing was tested");
            c.check(((List<String>) boss[0]).isEmpty(), "the HUD stayed up in the boss: " + boss[0]);
            c.check(msg != null && msg.startsWith("Blood Camp: "), "the end-of-run stats stopped in the boss: " + msg);
        } finally {
            c.onClient(mc -> {
                if ((Boolean) Mod.staticCall("secrets.DungeonState", "isSimOverrideActive")) {
                    Mod.staticCall("secrets.DungeonState", "toggleSimOverride");
                }
                Mod.staticCall(BC_MT, "reset");
                return null;
            });
            c.hx().removeStands();
        }
    }

    private static final int NPC_ID_BASE = 1_902_200;
    private static final double[][] NPC_AT = {{-421.5, -417.5}, {-417.5, -421.5}, {-420.5, -421.5}};

    /**
     * killer560, 2026-11-02: "i killed mobs and for some reason the counter kept ticking for about 6s after i killed the
     * first 4 with no mobs spawned." Three of those four were player-model NPCs. Hypixel kills a mob with a death event
     * (entity event 3); vanilla only zeroes health on that for a non-player, so the NPC stayed alive on the client. Here
     * three player-model blood mobs (version-2 UUIDs, in the Blood Room) get the real death packet one by one through the
     * client's own packet handler and stay in the level, alive as far as vanilla is concerned, exactly as on Hypixel:
     * Mobs Left must drop on each kill and Mob Ticks must stop counting once none is left.
     */
    private static void npcDeaths(Session c) {
        try (HxKit.Settings s = base(c)) {
            s.with(BC_CFG, "HudMobTicks", true).with(BC_CFG, "HudMobsLeft", true);
            c.server().command("bossbar remove " + WATCHER_BAR);
            watcher(c, WATCHER_IN);
            stand(c, 0f, 0f);
            greet(c);
            c.onClient(mc -> {
                for (int i = 0; i < NPC_AT.length; i++) {
                    long msb = (java.util.UUID.randomUUID().getMostSignificantBits() & ~0xF000L) | 0x2000L;
                    net.minecraft.client.player.RemotePlayer p = new net.minecraft.client.player.RemotePlayer(mc.level,
                            new com.mojang.authlib.GameProfile(new java.util.UUID(msb, java.util.UUID.randomUUID()
                                    .getLeastSignificantBits()), "BloodNpc" + i));
                    p.setId(NPC_ID_BASE + i);
                    p.setPos(NPC_AT[i][0], 151, NPC_AT[i][1]);
                    mc.level.addEntity(p);
                }
                return null;
            });
            c.waitUntil("Blood Camp tags the three player-model NPCs", mc -> bloodIds(mc).containsAll(
                    List.of(NPC_ID_BASE, NPC_ID_BASE + 1, NPC_ID_BASE + 2)), 60);
            String before = mobsLeftLine(c);
            c.hx().chat("[BOSS] The Watcher: That will be enough for now.");
            c.ctx().waitTicks(3);
            String three = mobsLeftLine(c);
            c.note("Mobs Left with no bar: " + before + " while mobs are due, " + three + " after the last one was sent");
            c.check("?".equals(before), "with mobs still due and no bar Mobs Left read " + before + ", want ?");
            c.check("3".equals(three), "three blood mobs alive and none due read " + three);
            c.onClient(mc -> {
                long elapsed = (Long) Mod.staticCall(BC_MT, "elapsedSinceGreeting");
                int lead = (Integer) Mod.call(Mod.cfg(BC_CFG), "getKillPopupLeadTicks");
                Mod.setField(BC_MT, "moveAtElapsed", elapsed + 5 + lead);
                return null;
            });
            c.waitUntil("the kill timer goes off", mc -> "FIRST".equals(Mod.staticCall(BC_STATS, "phaseName")), 60);
            c.ctx().waitTicks(10);
            StringBuilder trail = new StringBuilder();
            for (int i = 0; i < NPC_AT.length; i++) {
                int id = NPC_ID_BASE + i;
                c.onClient(mc -> {
                    net.minecraft.world.entity.Entity e = mc.level.getEntity(id);
                    mc.getConnection().handleEntityEvent(new net.minecraft.network.protocol.game.ClientboundEntityEventPacket(e,
                            (byte) 3));
                    return null;
                });
                c.ctx().waitTicks(2);
                int want = NPC_AT.length - 1 - i;
                Object[] r = c.onClient(mc -> new Object[]{Mod.staticCall(BC_STATS, "aliveForTest"),
                        Mod.staticCall(BC_STATS, "phaseName"), mc.level.getEntity(id) != null && mc.level.getEntity(id).isAlive()});
                String left = mobsLeftLine(c);
                trail.append(String.format(Locale.ROOT, "kill %d: alive %s, Mobs Left %s, phase %s, vanilla isAlive %s; ", i + 1,
                        r[0], left, r[1], r[2]));
                c.check(Boolean.TRUE.equals(r[2]), "the client took the death event as a death by itself, so this did not test"
                        + " the player-model case: " + trail);
                c.check(Integer.valueOf(want).equals(r[0]), "after kill " + (i + 1) + " Blood Camp counts " + r[0] + " alive, want "
                        + want);
                c.check(Integer.toString(want).equals(left), "after kill " + (i + 1) + " Mobs Left read " + left + ", want " + want);
                c.check("ALIVE".equals(r[1]), "the first kill did not end the first-kill wait (phase " + r[1] + ")");
                if (i < NPC_AT.length - 1) {
                    long a = c.onClient(mc -> (Long) Mod.staticCall(BC_STATS, "ticksLost"));
                    c.ctx().waitTicks(10);
                    long b = c.onClient(mc -> (Long) Mod.staticCall(BC_STATS, "ticksLost"));
                    trail.append("ticks ").append(a).append("->").append(b).append("; ");
                    c.check(b - a >= 8, "with " + want + " blood mob(s) alive Mob Ticks went " + a + " -> " + b + " in 10 ticks");
                }
            }
            long t0 = c.onClient(mc -> (Long) Mod.staticCall(BC_STATS, "ticksLost"));
            c.ctx().waitTicks(40);
            long t1 = c.onClient(mc -> (Long) Mod.staticCall(BC_STATS, "ticksLost"));
            @SuppressWarnings("unchecked")
            java.util.Set<Integer> dead = c.onClient(mc -> (java.util.Set<Integer>) Mod.staticCall(BC, "deadIds"));
            boolean stillThere = c.onClient(mc -> mc.level.getEntity(NPC_ID_BASE) != null);
            c.note(trail + "after the last kill " + t0 + " -> " + t1 + " over 40 ticks with the dead NPCs still in the level ("
                    + stillThere + "); dead ids " + dead);
            c.check(stillThere, "the dead NPCs left the level, so nothing tested that a dead one stops counting");
            c.check(t1 == t0, "Mob Ticks kept counting with every blood mob dead: " + t0 + " -> " + t1);
        } finally {
            c.onClient(mc -> {
                for (int i = 0; i < NPC_AT.length; i++) {
                    net.minecraft.world.entity.Entity e = mc.level.getEntity(NPC_ID_BASE + i);
                    if (e != null) {
                        e.discard();
                    }
                }
                Mod.staticCall(BC_MT, "reset");
                return null;
            });
            c.hx().removeStands();
        }
    }


    // ---- Dungeon ESP (STARRED MOB HIGHLIGHT) ----

    private static void felSkull(Session c, String tag, double x, double z) {
        c.server().command(String.format(Locale.ROOT, "summon armor_stand %.2f 151 %.2f {Tags:[\"bcdecoy\",\"%s\"],Invisible:1b,"
                + "NoGravity:1b,equipment:{head:{id:\"minecraft:skeleton_skull\",count:1}}}", x, z, tag));
    }

    private static int taggedEntity(Minecraft mc, String scoreTag) {
        for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof net.minecraft.world.entity.decoration.ArmorStand st && st.isInvisible()
                    && !st.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).isEmpty()
                    && Math.abs(st.getX() - FEL_AT.get(scoreTag)[0]) < 0.1 && Math.abs(st.getZ() - FEL_AT.get(scoreTag)[1]) < 0.1) {
                return e.getId();
            }
        }
        return -1;
    }

    private static final Map<String, double[]> FEL_AT = Map.of("felA", new double[]{-420.5, -421.5},
            "felB", new double[]{-410.5, -410.5});

    /**
     * killer560, 2026-11-01: Fel skulls by starred mobs first; once the starred mobs and predicted Fels are dead, the other
     * skulls; none once the room is clear. A starred zombie with a Fel skull 2 blocks away (predicted) and one 14 blocks
     * away (not), off the map (the room is the 32x32 cell; its cleared state goes through the test hook).
     */
    private static void fels(Session c) {
        try (HxKit.Settings s = base(c)) {
            boolean[] was = c.onClient(mc -> new boolean[]{(Boolean) Mod.call(Mod.cfg(ESP_CFG), "getFelSkullsRaw"),
                    (Boolean) Mod.call(Mod.cfg(ESP_CFG), "getStarredMobsRaw")});
            s.custom(() -> Mod.call(Mod.cfg(ESP_CFG), "setFelSkulls", true),
                            () -> Mod.call(Mod.cfg(ESP_CFG), "setFelSkulls", was[0]))
                    .custom(() -> Mod.call(Mod.cfg(ESP_CFG), "setStarredMobs", true),
                            () -> Mod.call(Mod.cfg(ESP_CFG), "setStarredMobs", was[1]))
                    .custom(() -> Mod.staticCall(ESP, "setTestRoomCleared", -420.5, -421.5, false),
                            () -> Mod.staticCall(ESP, "setTestRoomCleared", -420.5, -421.5, false));
            c.hx().removeStands(); // no Watcher: nothing here is the Blood Room
            stand(c, AWAY_YAW, 10f);
            int pink = c.onClient(mc -> (Integer) Mod.call(Mod.cfg(ESP_CFG), "getFelColor"));
            c.server().command("summon zombie -422.5 151 -421.5 {Tags:[\"bcstar\"],NoAI:1b,NoGravity:1b,Silent:1b,"
                    + "PersistenceRequired:1b,Invulnerable:1b,CustomName:\"starz\"}");
            c.server().command("summon armor_stand -422.5 152 -421.5 {Tags:[\"bcstar\"],Invisible:1b,NoGravity:1b,"
                    + "CustomNameVisible:1b,CustomName:\"✯ Zombie 100❤\"}");
            felSkull(c, "felA", -420.5, -421.5);
            felSkull(c, "felB", -410.5, -410.5);
            c.ctx().waitTicks(20);
            Object[] a = c.onClient(mc -> new Object[]{drawn(mc).get(taggedEntity(mc, "felA")),
                    drawn(mc).get(taggedEntity(mc, "felB")), taggedEntity(mc, "felA"), taggedEntity(mc, "felB")});
            c.note("with the starred zombie alive: Fel by it " + a[0] + ", lone Fel " + a[1] + " (ids " + a[2] + ", " + a[3]
                    + "); pink " + Integer.toHexString(pink));
            c.check((Integer) a[2] >= 0 && (Integer) a[3] >= 0, "the Fel skulls never reached the client - nothing was tested");
            c.check(Integer.valueOf(pink).equals(a[0]), "the Fel skull beside the starred mob was not highlighted");
            c.check(a[1] == null, "a lone Fel skull away from any starred mob was highlighted while the pack lived");
            c.server().command("kill @e[tag=bcstar]");
            c.server().command("kill @e[tag=felA]");
            c.ctx().waitTicks(20);
            Object[] b = c.onClient(mc -> new Object[]{drawn(mc).get(taggedEntity(mc, "felB")),
                    Mod.staticCall(ESP, "lastOtherFelsDrawn")});
            c.note("starred mob and its Fel dead, room not cleared: lone Fel " + b[0] + ", other Fels drawn " + b[1]);
            c.check(Integer.valueOf(pink).equals(b[0]), "with the room's starred mobs dead the other Fel skull was not shown");
            c.onClient(mc -> Mod.staticCall(ESP, "setTestRoomCleared", -420.5, -421.5, true));
            c.ctx().waitTicks(10);
            Object cleared = c.onClient(mc -> drawn(mc).get(taggedEntity(mc, "felB")));
            c.note("room cleared: lone Fel " + cleared);
            c.check(cleared == null, "the Fel skull was still highlighted after the room was cleared");
        } finally {
            clearDecoys(c);
        }
    }

    /**
     * killer560, 2026-11-01: "it seems to be missing mobs that are slightly far away". A starred zombie whose star name tag
     * goes away (as a stand does past the server's misc tracking range) must stay highlighted while it lives.
     */
    private static void starredMemory(Session c) {
        try (HxKit.Settings s = base(c)) {
            boolean was = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(ESP_CFG), "getStarredMobsRaw"));
            s.custom(() -> Mod.call(Mod.cfg(ESP_CFG), "setStarredMobs", true),
                    () -> Mod.call(Mod.cfg(ESP_CFG), "setStarredMobs", was));
            c.hx().removeStands();
            stand(c, AWAY_YAW, 10f);
            int star = c.onClient(mc -> (Integer) Mod.call(Mod.cfg(ESP_CFG), "getStarredColor"));
            c.server().command("summon zombie -422.5 151 -421.5 {Tags:[\"bcstar\"],NoAI:1b,NoGravity:1b,Silent:1b,"
                    + "PersistenceRequired:1b,Invulnerable:1b,CustomName:\"starz\"}");
            c.server().command("summon armor_stand -422.5 152 -421.5 {Tags:[\"bcstar\",\"bcstartag\"],Invisible:1b,"
                    + "NoGravity:1b,CustomNameVisible:1b,CustomName:\"✯ Zombie 100❤\"}");
            c.ctx().waitTicks(15);
            Object before = c.onClient(mc -> drawn(mc).get(clientId(mc, "starz")));
            c.server().command("kill @e[tag=bcstartag]");
            c.ctx().waitTicks(15);
            Object after = c.onClient(mc -> drawn(mc).get(clientId(mc, "starz")));
            c.note("starred zombie with its star tag: " + before + "; tag gone: " + after + "; starred " + Integer.toHexString(star));
            c.check(Integer.valueOf(star).equals(before), "control: the starred zombie was not highlighted with its tag");
            c.check(Integer.valueOf(star).equals(after), "the starred zombie lost its highlight when its name tag went away");
        } finally {
            clearDecoys(c);
        }
    }

    /** killer560, 2026-11-01: "Add an option for mimic highlight as well." A trapped chest in range is drawn; a baby zombie
     *  (the Mimic) is drawn; once the mimic stands by its chest the chest is no longer drawn. */
    private static void mimic(Session c) {
        try (HxKit.Settings s = base(c)) {
            boolean was = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(ESP_CFG), "getMimicRaw"));
            s.custom(() -> Mod.call(Mod.cfg(ESP_CFG), "setMimic", true),
                    () -> Mod.call(Mod.cfg(ESP_CFG), "setMimic", was));
            c.hx().removeStands();
            stand(c, 0f, 0f);
            int purple = c.onClient(mc -> (Integer) Mod.call(Mod.cfg(ESP_CFG), "getMimicColor"));
            c.server().command("setblock -419 151 -414 minecraft:trapped_chest");
            c.ctx().waitTicks(25);
            int chests = c.onClient(mc -> (Integer) Mod.staticCall(ESP, "chestTargetCount"));
            c.server().command("summon zombie -425.5 151 -421.5 {Tags:[\"bcdecoy\"],IsBaby:1b,NoAI:1b,NoGravity:1b,Silent:1b,"
                    + "PersistenceRequired:1b,Invulnerable:1b,CustomName:\"mimicz\"}");
            c.ctx().waitTicks(10);
            Object mimicColor = c.onClient(mc -> drawn(mc).get(clientId(mc, "mimicz")));
            c.server().command("tp @e[name=mimicz] -418.5 151 -415.5");
            c.ctx().waitTicks(25);
            int chestsAfter = c.onClient(mc -> (Integer) Mod.staticCall(ESP, "chestTargetCount"));
            c.note("trapped chest drawn " + chests + "; mimic color " + mimicColor + " (purple " + Integer.toHexString(purple)
                    + "); chests drawn once the mimic is beside it " + chestsAfter);
            c.check(chests >= 1, "the trapped chest was not highlighted");
            c.check(Integer.valueOf(purple).equals(mimicColor), "the mimic was not highlighted");
            c.check(chestsAfter == 0, "the trapped chest stayed highlighted after its mimic came out");
        } finally {
            clearDecoys(c);
            c.server().command("setblock -419 151 -414 minecraft:air");
        }
    }

    // ---- bloodcamp2-1102 ---------------------------------------------------------------------------------------------

    /** The Watcher on the east side of his tile (-7, -7): 24 blocks from a skull on the west side of the same tile. */
    private static final double[] WATCHER_EAST = {-397.5, 151, -412.5};

    private static long tileOf(double coord) {
        return Math.round((coord + 185.0) / 32.0);
    }

    /**
     * A blood mob's skull stand at (x, 151, z) moved east three times, 1.2 blocks a tick, as a trip leaves its wall. Returns
     * whether Blood Camp tracked the trip (a predicted landing spot) within 40 ticks. The stand is removed after.
     */
    private static boolean skullTrip(Session c, double x, double z) {
        String mobTex = c.onClient(mc -> anyOf(Mod.field(Mod.ROOT + BC, "MOB_SKULL_TEXTURES")));
        String uuid = c.hx().stand(x, 151.0, z, null, "marker", false, "invisible", false, "helmet", head(mobTex));
        try {
            c.ctx().waitTicks(10);
            for (int i = 0; i < 3; i++) {
                c.hx().call("entity.nudge", "uuid", uuid, "dx", 1.2);
                c.ctx().waitTicks(1);
            }
            for (int i = 0; i < 40; i++) {
                if (c.onClient(mc -> endVector() != null)) {
                    return true;
                }
                c.ctx().waitTicks(1);
            }
            return false;
        } finally {
            c.hx().call("entity.remove", "uuid", uuid);
            c.ctx().waitTicks(5);
        }
    }

    /**
     * Owner's F7 camp, 2026-11-02 (bloodcamp-owner-run2-1102.log): "the blood camp solver missed a wall spawn". A mob landed
     * with no trip tracked for it, 6 ticks after a trip from the far wall started. The Watcher flies to the case of the mob
     * he picks, and the room's cases are 24 blocks apart; the trip gate was 20 blocks from the Watcher. Here the Watcher is
     * on the east side of his room tile and a skull leaves the west side, 24 blocks off: its trip must be tracked. A skull
     * one tile over and 48 blocks off must not be, and the same skull 6 blocks from the Watcher is (control).
     */
    private static void wallSpawn(Session c) {
        double sx = -421.0;
        double sz = -417.5;
        try (HxKit.Settings s = base(c)) {
            watcher(c, WATCHER_EAST);
            stand(c, 0f, 0f);
            double far = Math.sqrt(Math.pow(sx - WATCHER_EAST[0], 2) + Math.pow(sz - WATCHER_EAST[2], 2));
            boolean sameTile = tileOf(sx) == tileOf(WATCHER_EAST[0]) && tileOf(sz) == tileOf(WATCHER_EAST[2]);
            c.check(far > 20.0 && sameTile, String.format(Locale.ROOT, "layout: the skull is %.1f blocks from the Watcher"
                    + " (same tile %s) - nothing tested", far, sameTile));
            boolean wall = skullTrip(c, sx, sz);
            double out = Math.sqrt(Math.pow(-446.0 - WATCHER_EAST[0], 2) + Math.pow(sz - WATCHER_EAST[2], 2));
            boolean otherTile = skullTrip(c, -446.0, sz);
            watcher(c, WATCHER_IN);
            stand(c, 0f, 0f);
            boolean near = skullTrip(c, sx, sz);
            c.note(String.format(Locale.ROOT, "skull %.1f blocks from the Watcher in his tile: tracked %s; skull one tile over"
                    + " %.1f blocks off (tile %d): tracked %s; control, Watcher beside it: tracked %s", far, wall, out,
                    tileOf(-446.0), otherTile, near));
            c.check(near, "control: a skull 6 blocks from the Watcher was not tracked - nothing tested");
            c.check(wall, String.format(Locale.ROOT, "a wall skull %.1f blocks from the Watcher, inside his Blood Room tile, was"
                    + " not tracked (the missed wall spawn)", far));
            c.check(!otherTile, "a skull one room tile away, 48 blocks from the Watcher, was tracked as a blood mob trip");
        } finally {
            c.hx().removeStands();
        }
    }

    /**
     * Owner's F7 camp, 2026-11-02: "Trigger Bot clicks were very delayed". His log: Hypixel removes a mob's skull stand
     * around the tick the mob appears, often before it; the trip went with its stand, so 9 of the camp's 16 mobs were found
     * only by a room scan that ran every 5 ticks (every one tagged on a multiple of 5), and the Trigger Bot had no target
     * until then. (a) A player-model NPC added on a tick 1 past a multiple of 5 must be a blood mob within 2 ticks.
     * (b) A trip whose stand the server removes at the due tick, its mob appearing on the spot one tick later, must still
     * tag that mob within 1 tick of the client having it, and (cheat) the Trigger Bot must click it within 2 ticks and the
     * server must see the attack.
     */
    private static void tagLatency(Session c) {
        boolean cheat = Mod.isCheat();
        int npc = NPC_ID_BASE + 10;
        try (HxKit.Settings s = base(c)) {
            watcher(c, WATCHER_IN);
            stand(c, 0f, 0f);
            // (a) - Trigger Bot off: this NPC exists on the client only, nothing may be sent at it.
            long added = -1;
            for (int i = 0; i < 12 && added < 0; i++) {
                added = c.onClient(mc -> {
                    long g = mc.level.getGameTime();
                    if (Math.floorMod(g, 5) != 1) {
                        return -1L;
                    }
                    long msb = (java.util.UUID.randomUUID().getMostSignificantBits() & ~0xF000L) | 0x2000L;
                    net.minecraft.client.player.RemotePlayer p = new net.minecraft.client.player.RemotePlayer(mc.level,
                            new com.mojang.authlib.GameProfile(new java.util.UUID(msb, java.util.UUID.randomUUID()
                                    .getLeastSignificantBits()), "LatencyNpc"));
                    p.setId(npc);
                    p.setPos(NPC_AT[0][0], 151, NPC_AT[0][1]);
                    mc.level.addEntity(p);
                    return g;
                });
                if (added < 0) {
                    c.ctx().waitTicks(1);
                }
            }
            c.check(added >= 0, "never found a tick 1 past a multiple of 5 to add the NPC on - nothing tested");
            long seen = -1;
            for (int i = 0; i < 15 && seen < 0 && added >= 0; i++) {
                c.ctx().waitTicks(1);
                seen = c.onClient(mc -> bloodIds(mc).contains(npc) ? mc.level.getGameTime() : -1L);
            }
            c.onClient(mc -> {
                net.minecraft.world.entity.Entity e = mc.level.getEntity(npc);
                if (e != null) {
                    e.discard();
                }
                return null;
            });
            c.note("player-model NPC added at game time " + added + ", a blood mob by game time " + seen
                    + (seen >= 0 ? " (" + (seen - added) + " ticks)" : " (never)"));
            c.check(seen >= 0 && seen - added <= 2, "a player-model NPC in the Blood Room took " + (seen < 0 ? "forever"
                    : (seen - added) + " ticks") + " to become a blood mob (want 2 or fewer)");
            c.ctx().waitTicks(10);

            // (b)
            if (cheat) {
                s.with(BC_CFG, "TriggerBotEnabled", true).with(BC_CFG, "NeverMiss", true);
            }
            boolean firstWave = c.onClient(mc -> (Boolean) Mod.staticCall(BC_MT, "isFirstSpawns"));
            int dueTick = (int) Math.ceil((firstWave ? 40 : 0) + 38 + 0.8);
            nextRemoveStandAt = dueTick;
            nextSummonDelay = 4; // summoned at dueTick + 1
            int[] at = {-1, -1, -1, -1}; // client tick index the husk was seen, tagged, first clicked after; clicks when seen
            long since = c.hx().head();
            Trip t = trip(c, Mob.HURTABLE, true, 25, (tick, mc) -> {
                int id = clientId(mc, "blood");
                int clicks = (Integer) Mod.staticCall(BC, "triggerClicks");
                if (id >= 0 && at[0] < 0) {
                    at[0] = tick;
                    at[3] = clicks;
                }
                if (id >= 0 && at[1] < 0 && bloodIds(mc).contains(id)) {
                    at[1] = tick;
                }
                if (at[0] >= 0 && at[2] < 0 && clicks > at[3]) {
                    at[2] = tick;
                }
            });
            long attacks = namedAttacks(c, since, "blood");
            c.note("stand removed at trip tick " + dueTick + ", husk summoned " + (dueTick + 1) + ": seen at " + at[0]
                    + ", tagged at " + at[1] + ", first click after it appeared at " + at[2] + ", server attacks on it "
                    + attacks + "; " + fmt(t));
            c.check(t.tracked, "the trip was never tracked - nothing was tested");
            c.check(at[0] >= 0, "the husk never reached the client - nothing was tested");
            c.check(at[1] >= 0 && at[1] - at[0] <= 1, "the mob that appeared on a landed trip's spot after its stand was"
                    + " removed was " + (at[1] < 0 ? "never" : "only " + (at[1] - at[0]) + " ticks later") + " a blood mob");
            if (cheat) {
                c.check(at[2] >= 0 && at[2] - at[0] <= 2, "the Trigger Bot clicked the landed mob " + (at[2] < 0 ? "never"
                        : (at[2] - at[0]) + " ticks after it appeared") + " (want 2 or fewer)");
                c.check(attacks >= 1, "the server never saw an attack on the landed mob");
            } else {
                c.check(t.clicks == 0 && t.serverAttacks.isEmpty(), "legit jar: something clicked");
            }
        } finally {
            nextRemoveStandAt = null;
            nextSummonDelay = null;
            c.hx().removeStands();
        }
    }

    /**
     * Owner's F7 camp, 2026-11-02: "the Watcher's orbiting eye skull was highlighted as a mob". His log: zombie #330210, one
     * of two zombies with ids just after the Watcher's (#330208), passed a trip's landing spot 13 ticks before the mob and
     * was "tagged by trip landing". Here, during a trip whose mob arrives a tick before it is due, two Watchful Eyes sit on
     * the landing spot from its first tick: one a zombie spawned with the Watcher (the id right after his), the other a
     * zombie under a "[Lv1] Watchful Eye" name tag. Neither may ever be a blood mob or be drawn by Blood Mobs ESP; the real
     * mob must be both (and, cheat, be the one the Trigger Bot hits).
     */
    private static void watchfulEye(Session c) {
        boolean cheat = Mod.isCheat();
        try (HxKit.Settings s = base(c)) {
            boolean was = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(ESP_CFG), "getBloodMobsRaw"));
            s.custom(() -> Mod.call(Mod.cfg(ESP_CFG), "setBloodMobs", true),
                    () -> Mod.call(Mod.cfg(ESP_CFG), "setBloodMobs", was));
            if (cheat) {
                s.with(BC_CFG, "TriggerBotEnabled", true);
            }
            int red = c.onClient(mc -> (Integer) Mod.call(Mod.cfg(ESP_CFG), "getBloodColor"));
            // The Watcher and eye A in one go, as Hypixel spawns them.
            c.hx().removeStands();
            c.ctx().waitTicks(3);
            String tex = c.onClient(mc -> anyOf(Mod.field(Mod.ROOT + BC, "WATCHER_SKULL_TEXTURES")));
            c.hx().stand(WATCHER_IN[0], WATCHER_IN[1], WATCHER_IN[2], null, "marker", false, "invisible", false, "helmet",
                    head(tex));
            c.server().command(String.format(Locale.ROOT, "summon zombie %.2f 151 %.2f {Tags:[\"bceye\"],NoAI:1b,NoGravity:1b,"
                    + "Silent:1b,PersistenceRequired:1b,Invulnerable:1b,CustomName:\"xeyea\"}", WATCHER_IN[0] + 2, WATCHER_IN[2]));
            c.waitUntil("Blood Camp sees the Watcher", mc -> Mod.field(Mod.ROOT + BC, "watcherEntityId") != null
                    && clientId(mc, "xeyea") >= 0, 200);
            int[] ids = c.onClient(mc -> new int[]{(Integer) Mod.field(Mod.ROOT + BC, "watcherEntityId"), clientId(mc, "xeyea")});
            c.note("Watcher #" + ids[0] + ", eye A #" + ids[1]);
            c.check(ids[1] > ids[0] && ids[1] - ids[0] <= 8, "eye A's id is not just after the Watcher's - the spawned-with-him"
                    + " case was not set up");
            stand(c, AWAY_YAW, 10f);
            taggedMob(c, "zombie", "xeyeb", "[Lv1] Watchful Eye 250❤", -416.5, -416.5);
            c.server().command("tag @e[type=armor_stand,tag=bcdecoy] add bceyetag");
            nextSummonDelay = 2; // the mob one tick before due: both eyes sit on the spot through the landing window
            int[] firstTag = {-1, -1, -1};
            boolean[] drawnEye = {false};
            int[] drawnMob = {0};
            // Each eye 1.5 blocks to a side of the spot, across his line to it: inside the landing search (2 blocks), out of
            // the line of fire (0.3 + 0.6 blocks), so only the tagging decides.
            nextServerStep = tick -> {
                if (tick == 0) {
                    Vec3[] r = c.onClient(mc -> new Vec3[]{endVector(), mc.player.position()});
                    if (r[0] != null) {
                        Vec3 end = r[0];
                        double dx = end.x - r[1].x;
                        double dz = end.z - r[1].z;
                        double len = Math.max(1e-6, Math.sqrt(dx * dx + dz * dz));
                        double px = -dz / len * 1.5;
                        double pz = dx / len * 1.5;
                        c.server().command(String.format(Locale.ROOT, "tp @e[type=zombie,name=xeyea] %.3f %.3f %.3f",
                                end.x + px, end.y, end.z + pz));
                        c.server().command(String.format(Locale.ROOT, "tp @e[type=zombie,name=xeyeb] %.3f %.3f %.3f",
                                end.x - px, end.y, end.z - pz));
                        c.server().command(String.format(Locale.ROOT, "tp @e[tag=bceyetag] %.3f %.3f %.3f", end.x - px,
                                end.y + 2.2, end.z - pz));
                    }
                }
            };
            long since = c.hx().head();
            Trip t = trip(c, Mob.HURTABLE, true, 20, (tick, mc) -> {
                java.util.Set<Integer> b = bloodIds(mc);
                Map<Integer, Integer> d = drawn(mc);
                int a = clientId(mc, "xeyea");
                int e = clientId(mc, "xeyeb");
                int m = clientId(mc, "blood");
                if (a >= 0 && firstTag[0] < 0 && b.contains(a)) {
                    firstTag[0] = tick;
                }
                if (e >= 0 && firstTag[1] < 0 && b.contains(e)) {
                    firstTag[1] = tick;
                }
                if (m >= 0 && firstTag[2] < 0 && b.contains(m)) {
                    firstTag[2] = tick;
                }
                drawnEye[0] |= (a >= 0 && d.containsKey(a)) || (e >= 0 && d.containsKey(e));
                if (m >= 0 && Integer.valueOf(red).equals(d.get(m))) {
                    drawnMob[0]++;
                }
            });
            long eyeHits = namedAttacks(c, since, "xeyea") + namedAttacks(c, since, "xeyeb");
            long mobHits = namedAttacks(c, since, "blood");
            c.note("tagged at trip tick: eye A " + firstTag[0] + ", eye B " + firstTag[1] + ", mob " + firstTag[2]
                    + "; an eye drawn " + drawnEye[0] + ", mob drawn red on " + drawnMob[0] + " ticks; server attacks: eyes "
                    + eyeHits + ", mob " + mobHits + "; " + fmt(t));
            c.check(t.tracked, "the trip was never tracked - nothing was tested");
            c.check(firstTag[0] < 0, "the Watchful Eye spawned with the Watcher was tagged a blood mob at trip tick " + firstTag[0]);
            c.check(firstTag[1] < 0, "the zombie under a Watchful Eye name tag was tagged a blood mob at trip tick " + firstTag[1]);
            c.check(!drawnEye[0], "Blood Mobs ESP drew a Watchful Eye");
            c.check(firstTag[2] >= 0 && drawnMob[0] > 0, "control: the real blood mob was not a highlighted blood mob");
            if (cheat) {
                c.check(eyeHits == 0, "the Trigger Bot hit a Watchful Eye");
                c.check(mobHits >= 1, "control: the Trigger Bot never hit the real blood mob");
            }
        } finally {
            nextServerStep = null;
            nextSummonDelay = null;
            c.server().command("kill @e[tag=bceye]");
            clearDecoys(c);
            c.hx().removeStands();
        }
    }
    // ---- nevermiss-1102 ---------------------------------------------------------------------------------------------
    // killer560, 2026-11-03: "it shouldn't control it. if never miss is on then it should just attack the tick after it
    // spawns." With Never Miss ON: no Trigger Offset, no kill timer, no landing-spot wait; the Trigger Offset row is
    // hidden. Each case measures the tick: the mod's first click on the mob against the tick Blood Camp tagged it (the
    // first client tick it existed, testkit 1102-bloodcamp-tag-latency), and the server's attack against its summon tick.

    /** The mod's last first click: {mob id, click tick, the tick that mob was tagged}. */
    private static long[] firstClick(Session c) {
        return c.onClient(mc -> (long[]) Mod.staticCall(BC, "lastFirstClickForTest"));
    }

    private static void setNeverMiss(Session c, boolean on, int offset) {
        c.onClient(mc -> {
            Mod.call(Mod.cfg(BC_CFG), "setNeverMiss", on);
            Mod.call(Mod.cfg(BC_CFG), "setTriggerOffsetTicks", offset);
            return null;
        });
    }

    /** Never Miss ON at Trigger Offset -5, 0 and +5: the same attack tick every time, never before the mob; OFF: shifted. */
    private static void neverMissOffset(Session c) {
        boolean cheat = Mod.isCheat();
        try (HxKit.Settings s = base(c)) {
            watcher(c, WATCHER_IN);
            if (!cheat) {
                stand(c, 0f, 0f);
                Trip t = trip(c, Mob.HURTABLE, true, 20, null);
                c.check(t.tracked, "legit: the trip was never tracked");
                c.check(t.clicks == 0 && t.serverAttacks.isEmpty(), "legit jar: the Trigger Bot clicked");
                return;
            }
            s.with(BC_CFG, "TriggerBotEnabled", true).with(BC_CFG, "NeverMiss", true);
            List<Long> lags = new ArrayList<>();
            for (int off : new int[]{-5, 0, 5}) {
                setNeverMiss(c, true, off);
                stand(c, 0f, 0f);
                long[] before0 = firstClick(c);
                nextSummonDelay = 3; // the mob appears on its due tick
                Trip t = trip(c, Mob.HURTABLE, true, 20, null);
                long summon = lastSummonTick;
                int before = lastClicksBeforeSummon;
                long[] fc = firstClick(c);
                long lag = t.serverAttacks.isEmpty() ? -1 : t.serverAttacks.get(0) - summon;
                c.note(String.format(Locale.ROOT, "Never Miss ON, offset %+d: %s; clicks before the mob %d; summoned at server"
                        + " tick %d, first attack +%d; mod first click at tick %d on #%d, tagged at %d", off, fmt(t), before,
                        summon, lag, fc[1], fc[0], fc[2]));
                c.check(t.tracked, "offset " + off + ": the trip was never tracked - nothing was tested");
                c.check(before == 0, "Never Miss, offset " + off + ": swung " + before + " time(s) before the mob existed");
                c.check(t.attacks >= 1 && !t.serverAttacks.isEmpty(), "Never Miss, offset " + off + ": never attacked");
                c.check(fc[1] != before0[1] && fc[2] >= 0 && fc[1] == fc[2], "Never Miss, offset " + off + ": the first click"
                        + " went at tick " + fc[1] + ", the mob was tagged at " + fc[2] + " - want the same tick (the first tick"
                        + " after it spawned)");
                c.check(lag >= 0 && lag <= 2, "Never Miss, offset " + off + ": the attack reached the server " + lag
                        + " ticks after the summon, want the next tick (0-2)");
                lags.add(lag);
            }
            long min = lags.stream().mapToLong(Long::longValue).min().orElse(-1);
            long max = lags.stream().mapToLong(Long::longValue).max().orElse(-1);
            c.check(max - min <= 1, "Never Miss: the attack moved with Trigger Offset -5/0/+5: " + lags);
            // Control: Never Miss OFF, the offset still moves the first click.
            for (int off : new int[]{-5, 5}) {
                setNeverMiss(c, false, off);
                stand(c, 0f, 0f);
                nextSummonDelay = 3;
                Trip t = trip(c, Mob.HURTABLE, true, 20, null);
                double late = t.killAt - t.triggerAt;
                c.note(String.format(Locale.ROOT, "Never Miss OFF, offset %+d: %s; clicks before the mob %d; first click %.1f"
                        + " ticks after the kill timer", off, fmt(t), lastClicksBeforeSummon, late));
                c.check(Math.abs(late - off) < 0.01, String.format(Locale.ROOT,
                        "Never Miss OFF, offset %+d: the click went %.1f ticks after the kill timer, want %d", off, late, off));
            }
        } finally {
            nextSummonDelay = null;
            c.hx().removeStands();
        }
    }

    /** The Kill Popup's kill timer far away (closed): Never Miss attacks anyway; OFF holds every click. */
    private static void neverMissKillTimer(Session c) {
        boolean cheat = Mod.isCheat();
        try (HxKit.Settings s = base(c)) {
            watcher(c, WATCHER_IN);
            stand(c, 0f, 0f);
            greet(c);
            c.onClient(mc -> {
                long elapsed = (Long) Mod.staticCall(BC_MT, "elapsedSinceGreeting");
                int lead = (Integer) Mod.call(Mod.cfg(BC_CFG), "getKillPopupLeadTicks");
                Mod.setField(BC_MT, "moveAtElapsed", elapsed + 3000 + lead);
                return null;
            });
            c.ctx().waitTicks(2);
            boolean timerOpen = c.onClient(mc -> (Boolean) Mod.staticCall(BC_MT, "killTimerAllowsForTest", 0));
            c.check(!timerOpen, "the kill timer was open with KILL 3000 ticks away - nothing was tested");
            if (!cheat) {
                Trip t = trip(c, Mob.HURTABLE, true, 20, null);
                c.check(t.clicks == 0 && t.serverAttacks.isEmpty(), "legit jar: the Trigger Bot clicked");
                return;
            }
            s.with(BC_CFG, "TriggerBotEnabled", true).with(BC_CFG, "NeverMiss", true);
            setNeverMiss(c, true, 5);
            stand(c, 0f, 0f);
            nextSummonDelay = 3;
            Trip on = trip(c, Mob.HURTABLE, true, 20, null);
            long summon = lastSummonTick;
            boolean open = c.onClient(mc -> (Boolean) Mod.staticCall(BC, "lastAttackOpen"));
            long[] fc = firstClick(c);
            long lag = on.serverAttacks.isEmpty() ? -1 : on.serverAttacks.get(0) - summon;
            c.note("kill timer closed, Never Miss ON: " + fmt(on) + "; attack gate open " + open + "; first attack +" + lag
                    + " server ticks after the summon; first click tick " + fc[1] + ", tagged " + fc[2]);
            c.check(open, "Never Miss: the attack gate was closed by the kill timer");
            c.check(on.attacks >= 1 && lag >= 0 && lag <= 2, "Never Miss: no attack on the tick after the mob spawned"
                    + " while the kill timer was closed (lag " + lag + ")");
            c.check(fc[1] == fc[2], "Never Miss: the first click (tick " + fc[1] + ") was not on the mob's tag tick " + fc[2]);
            setNeverMiss(c, false, 5);
            stand(c, 0f, 0f);
            nextSummonDelay = 3;
            Trip off = trip(c, Mob.HURTABLE, true, 20, null);
            boolean openOff = c.onClient(mc -> (Boolean) Mod.staticCall(BC, "lastAttackOpen"));
            c.note("kill timer closed, Never Miss OFF: " + fmt(off) + "; attack gate open " + openOff);
            c.check(off.tracked, "control: the trip was never tracked");
            c.check(!openOff && off.clicks == 0 && off.serverAttacks.isEmpty(), "control: Never Miss OFF clicked "
                    + off.clicks + " time(s) before the kill timer");
        } finally {
            nextSummonDelay = null;
            c.onClient(mc -> {
                Mod.staticCall(BC_MT, "reset");
                return null;
            });
            c.hx().removeStands();
        }
    }

    /** A mob 1.5 blocks off its trip's landing spot, and a blood mob with no trip at all: both hit the tick after spawn. */
    private static void neverMissOffSpot(Session c) {
        boolean cheat = Mod.isCheat();
        try (HxKit.Settings s = base(c)) {
            watcher(c, WATCHER_IN);
            if (cheat) {
                s.with(BC_CFG, "TriggerBotEnabled", true).with(BC_CFG, "NeverMiss", true);
                setNeverMiss(c, true, 5);
            }
            stand(c, 0f, 0f);
            long since = c.hx().head();
            nextSummonDelay = 3;
            nextSummonDx = 1.5;
            Trip t = trip(c, Mob.HURTABLE, true, 20, null);
            long summon = lastSummonTick;
            int before = lastClicksBeforeSummon;
            long[] fc = firstClick(c);
            long lag = t.serverAttacks.isEmpty() ? -1 : t.serverAttacks.get(0) - summon;
            long onIt = namedAttacks(c, since, "blood");
            c.note("mob 1.5 blocks off the landing spot: " + fmt(t) + "; clicks before it " + before + "; attacks on it "
                    + onIt + ", first +" + lag + " server ticks after the summon; first click tick " + fc[1] + ", tagged "
                    + fc[2]);
            c.check(t.tracked, "the trip was never tracked - nothing was tested");
            if (!cheat) {
                c.check(t.clicks == 0 && t.serverAttacks.isEmpty(), "legit jar: the Trigger Bot clicked");
                return;
            }
            c.check(before == 0, "Never Miss swung " + before + " time(s) before the off-spot mob existed");
            c.check(onIt >= 1 && lag >= 0 && lag <= 2, "the mob off its landing spot was not attacked the tick after it"
                    + " spawned (attacks " + onIt + ", lag " + lag + ")");
            c.check(fc[1] == fc[2] && fc[2] >= 0, "off-spot: first click tick " + fc[1] + ", tagged " + fc[2]);

            // No trip at all: a blood mob (name tag) appears under his look.
            double x = -421.0;
            double z = -417.5;
            c.onClient(mc -> {
                float[] l = look(mc, new Vec3(x, 152.4, z));
                mc.player.setYRot(l[0]);
                mc.player.setXRot(l[1]);
                return null;
            });
            c.ctx().waitTicks(3);
            long since2 = c.hx().head();
            long[] fc0 = firstClick(c);
            c.server().command(String.format(Locale.ROOT, "summon husk %.2f 151 %.2f {Tags:[\"bcdecoy\"],NoAI:1b,NoGravity:1b,"
                    + "Silent:1b,PersistenceRequired:1b,Health:20f,CustomName:\"loner\"}", x, z));
            c.server().command(String.format(Locale.ROOT, "summon armor_stand %.2f 152.2 %.2f {Tags:[\"bcdecoy\"],Invisible:1b,"
                    + "NoGravity:1b,Marker:1b,CustomNameVisible:1b,CustomName:\"Revoker 1M❤\"}", x, z));
            long summon2 = c.hx().call("server.tick").getAsLong();
            c.ctx().waitTicks(15);
            long[] fc2 = firstClick(c);
            int id = c.onClient(mc -> clientId(mc, "loner"));
            List<Long> at = new ArrayList<>();
            for (JsonObject e : c.hx().events(since2, "attack_entity")) {
                if (e.has("name") && e.get("name").getAsString().equals("loner")) {
                    at.add(e.get("tick").getAsLong());
                }
            }
            long lag2 = at.isEmpty() ? -1 : at.get(0) - summon2;
            c.note("blood mob with no trip: client #" + id + ", server attacks " + at + " (summoned at " + summon2 + ", first +"
                    + lag2 + "); first click on #" + fc2[0] + " tick " + fc2[1] + ", tagged " + fc2[2]);
            c.check(id >= 0, "the no-trip husk never reached the client - nothing was tested");
            c.check(fc2[1] != fc0[1] && fc2[0] == id, "the blood mob with no trip was never clicked");
            c.check(fc2[1] == fc2[2], "no trip: first click tick " + fc2[1] + ", tagged " + fc2[2] + " - want the same tick");
            c.check(lag2 >= 0 && lag2 <= 3, "no trip: the attack reached the server " + lag2 + " ticks after the summon");
        } finally {
            nextSummonDelay = null;
            nextSummonDx = null;
            clearDecoys(c);
            c.hx().removeStands();
        }
    }

    /**
     * Owner's camp 3 (2026-11-02 20:25:04): player #481791 was hit once, survived, and was left "already hit" for six
     * seconds while he looked at it. With Never Miss ON a hit blood mob that is still alive is hit again at the weapon's
     * pace; OFF keeps the old one click.
     */
    private static void neverMissRehit(Session c) {
        boolean cheat = Mod.isCheat();
        try (HxKit.Settings s = base(c)) {
            watcher(c, WATCHER_IN);
            if (cheat) {
                s.with(BC_CFG, "TriggerBotEnabled", true).with(BC_CFG, "NeverMiss", true);
            }
            long[] counts = new long[2];
            for (int pass = 0; pass < (cheat ? 2 : 1); pass++) {
                boolean on = pass == 0;
                if (cheat) {
                    setNeverMiss(c, on, 1);
                }
                stand(c, 0f, 0f);
                double x = -421.0;
                double z = -417.5;
                String name = on ? "rehit" : "rehitoff";
                c.onClient(mc -> {
                    float[] l = look(mc, new Vec3(x, 152.4, z));
                    mc.player.setYRot(l[0]);
                    mc.player.setXRot(l[1]);
                    return null;
                });
                long since = c.hx().head();
                c.server().command(String.format(Locale.ROOT, "summon husk %.2f 151 %.2f {Tags:[\"bcdecoy\"],NoAI:1b,"
                        + "NoGravity:1b,Silent:1b,PersistenceRequired:1b,Health:20f,CustomName:\"%s\"}", x, z, name));
                c.server().command(String.format(Locale.ROOT, "summon armor_stand %.2f 152.2 %.2f {Tags:[\"bcdecoy\"],"
                        + "Invisible:1b,NoGravity:1b,Marker:1b,CustomNameVisible:1b,CustomName:\"Revoker 1M❤\"}", x, z));
                c.ctx().waitTicks(40);
                boolean hit = c.onClient(mc -> {
                    int id = clientId(mc, name);
                    return id >= 0 && mc.level.getEntity(id) instanceof net.minecraft.world.entity.LivingEntity le
                            && le.getHealth() < le.getMaxHealth();
                });
                counts[pass] = namedAttacks(c, since, name);
                c.note("Never Miss " + (on ? "ON" : "OFF") + ": " + counts[pass] + " attacks on a live blood mob in 40 ticks,"
                        + " hurt " + hit);
                clearDecoys(c);
                if (!cheat) {
                    c.check(counts[0] == 0, "legit jar: something attacked");
                    return;
                }
                c.check(hit, (on ? "ON" : "OFF") + ": the mob was never hurt - nothing was tested");
            }
            c.check(counts[0] >= 4, "Never Miss ON: a hit blood mob still alive was attacked only " + counts[0]
                    + " time(s) in 40 ticks, want it hit again at the weapon's pace (5 ticks)");
            c.check(counts[1] == 1, "Never Miss OFF: want the one click as before, got " + counts[1]);
        } finally {
            clearDecoys(c);
            c.hx().removeStands();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> autoBloodCampLabels(Minecraft mc) {
        try {
            Object tab = Mod.cls("gui.tab.AutoBloodCampTab").getConstructor().newInstance();
            List<net.minecraft.client.gui.components.AbstractWidget> ws =
                    (List<net.minecraft.client.gui.components.AbstractWidget>) Mod.call(tab, "buildWidgets", 0, 0, 300,
                            (Runnable) () -> {
                            });
            List<String> out = new ArrayList<>();
            for (net.minecraft.client.gui.components.AbstractWidget w : ws) {
                out.add(net.minecraft.ChatFormatting.stripFormatting(w.getMessage().getString()));
            }
            return out;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** The Trigger Offset row: hidden while Never Miss is on, back (same value) when it is off; the search still finds it. */
    @SuppressWarnings("unchecked")
    private static void neverMissTab(Session c) {
        if (!Mod.isCheat()) {
            List<String> labels = c.onClient(HxBloodCampCases::autoBloodCampLabels);
            c.note("legit: Auto Blood Camp tab builds " + labels);
            c.check(labels.isEmpty(), "legit jar: the cheat-only Auto Blood Camp tab built rows " + labels);
            return;
        }
        try (HxKit.Settings s = base(c)) {
            s.with(BC_CFG, "TriggerBotEnabled", true).with(BC_CFG, "NeverMiss", false);
            setNeverMiss(c, false, 5);
            List<String> off = c.onClient(HxBloodCampCases::autoBloodCampLabels);
            setNeverMiss(c, true, 5);
            List<String> on = c.onClient(HxBloodCampCases::autoBloodCampLabels);
            List<String> hits = c.onClient(mc -> {
                try {
                    Object tab = Mod.cls("gui.tab.AutoBloodCampTab").getConstructor().newInstance();
                    return (List<String>) Mod.call(tab, "searchHits", "trigger offset");
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            int[] eff = c.onClient(mc -> new int[]{(Integer) Mod.call(Mod.cfg(BC_CFG), "getTriggerOffsetTicks"),
                    (Integer) Mod.staticCall(BC, "effectiveTriggerOffsetTicks", Mod.cfg(BC_CFG))});
            setNeverMiss(c, false, eff[0]);
            List<String> back = c.onClient(HxBloodCampCases::autoBloodCampLabels);
            c.note("Never Miss OFF rows " + off + "; ON rows " + on + "; search hits with it ON " + hits + "; saved offset "
                    + eff[0] + ", in force " + eff[1] + "; OFF again " + back);
            c.check(off.stream().anyMatch(l -> l.startsWith("Trigger Offset: +5t")), "Never Miss OFF: no Trigger Offset row");
            c.check(on.stream().noneMatch(l -> l.startsWith("Trigger Offset")), "Never Miss ON: Trigger Offset still shown");
            c.check(on.stream().anyMatch(l -> l.startsWith("Never Miss")), "Never Miss ON: the Never Miss row is gone");
            c.check(!hits.isEmpty(), "Never Miss ON: the menu search no longer finds Trigger Offset");
            c.check(eff[0] == 5 && eff[1] == 0, "Never Miss ON: saved offset " + eff[0] + " (want 5 kept), in force " + eff[1]
                    + " (want 0)");
            c.check(back.stream().anyMatch(l -> l.startsWith("Trigger Offset: +5t")), "Never Miss OFF again: the row did not"
                    + " come back with its value");
        }
    }
}
