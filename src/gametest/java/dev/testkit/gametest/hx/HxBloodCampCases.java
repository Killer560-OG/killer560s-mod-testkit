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
 * of the dungeon grid (centres at -185 + 32n); the Watcher goes in the same tile unless a case says otherwise.
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
    }

    // ---- shared -----------------------------------------------------------------------------------------------------

    private enum Mob { NONE, HURTABLE, INVULNERABLE }

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
            s.with(BC_CFG, "TriggerBotEnabled", false).with(BC_CFG, "AuraEnabled", false)
                    .with(BC_CFG, "AuraFreecam", false).with(BC_CFG, "AutoDetectLag", true)
                    .with(BC_CFG, "TriggerOffsetTicks", -1);
        }
        return s;
    }

    /** He stands on the platform looking at {@code yaw}/{@code pitch}; no container open. */
    private static void stand(Session c, float yaw, float pitch) {
        c.server().command(String.format(Locale.ROOT, "tp @a %.2f 151 %.2f %.1f %.1f", PX, PZ, yaw, pitch));
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
        String uuid = c.hx().stand(x0, 151.0, -417.5, null, "marker", false, "invisible", false, "helmet", head(mobTex));
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
                long t = c.hx().call("entity.nudge", "uuid", uuid, "dx", 1.2).getAsLong();
                if (firstTick < 0) {
                    firstTick = t;
                }
                c.ctx().waitTicks(1);
            }
            c.waitUntil("the trip is tracked", mc -> endVector() != null, 100);
            tracked = true;
            end = c.onClient(mc -> endVector());
            if (mob != Mob.NONE) {
                c.server().command(String.format(Locale.ROOT, "summon husk %.3f %.3f %.3f {Tags:[\"bc1015\"],NoAI:1b,"
                                + "Silent:1b,PersistenceRequired:1b,Health:20f%s}", end.x, end.y, end.z,
                        mob == Mob.INVULNERABLE ? ",Invulnerable:1b" : ""));
            }
            Vec3 chest = end.add(0, 1.4, 0);
            int ticks = (int) Math.ceil(due) + extra;
            for (int i = 0; i < ticks; i++) {
                final int tick = i;
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
            int[] offsets = cheat ? new int[]{-1, 0, 2, -5} : new int[]{0};
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
                c.check(Math.abs(late - (-off)) < 0.01, String.format(Locale.ROOT,
                        "offset %d: the click went %.1f ticks after the kill timer, want %d", off, late, -off));
                if (off == -1) {
                    Double lead = t.serverLead();
                    c.check(lead != null && lead < 0, "offset -1: the attack reached the server " + lead
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
            Trip t = trip(c, Mob.HURTABLE, false, 10, null);
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
            Trip t = trip(c, Mob.HURTABLE, false, 40, (i, mc) -> {
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
            c.check(t.serverAttacks.size() == 1, "the Trigger Bot's attack (body on the husk, camera away) did not land once: "
                    + t.serverAttacks.size());
            c.check(!heldAtEnd && backOff < 0.6, "after the mob was hit the body did not come back under the camera"
                    + " and release it (held " + heldAtEnd + ", " + backOff + " deg off)");
        } finally {
            c.hx().removeStands();
        }
    }
}
