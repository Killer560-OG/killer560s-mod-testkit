package dev.testkit.gametest.boss;

import com.google.gson.JsonObject;

import dev.testkit.gametest.SameTick;
import dev.testkit.gametest.hx.HxKit;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.WireCapture;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Wither Aimbot (witheraim-1111, part of the Wither Boss Triggerbot tab, cheat build): in F7 P3 it turns his view from
 * 150 degrees away onto Goldor and, with Auto Hit, attacks him at the triggerbot's CPS. Every case asserts on the WIRE
 * (WireCapture: the rotating move packets, the attacks the server logged) that the turn and the hits went out, then
 * GrimAC (default and experimental, by checkout) judges.
 * <ul>
 *   <li>1111-witheraim-turn: 20 CPS (the band's top), smooth: the 150-degree turn takes several packets, none over 100 degrees, the yaw never
 *       wrapped (it runs from 150 down toward 0 as one number), pitch within -90..90, the last look strikes Goldor.</li>
 *   <li>1111-witheraim-same-&lt;act&gt;: his own right click (use key), attack, hotbar swap, a container click (menu
 *       closed at once, so his click and the aimbot's first attack share a tick) and a teleport accept in the aimbot's
 *       ticks.</li>
 *   <li>1111-witheraim-hold: Hold Left Click: no turn until he holds attack, then it turns and hits.</li>
 *   <li>1111-witheraim-boss-off: Goldor's switch off: no turn and no hit in P3.</li>
 * </ul>
 */
final class WitherAimCases {

    private static final String CFG = GrimTickCases.GT_CFG;
    private static final String FEATURE = "goldor.GoldorTriggerbotFeature";
    private static final AABB BOX = new AABB(30, 140, 40, 50, 170, 60);

    private WitherAimCases() {
    }

    static void register(Session s) {
        if (!Mod.isCheat()) {
            return;
        }
        s.test("1111-witheraim-turn", c -> run(c, null, Mode.PLAIN));
        for (SameTick.Act a : new SameTick.Act[]{SameTick.Act.USE_KEY, SameTick.Act.ATTACK, SameTick.Act.SWAP,
                SameTick.Act.CLICK, SameTick.Act.TP}) {
            s.test("1111-witheraim-same-" + a.name().toLowerCase(Locale.ROOT).replace("_", ""), c -> run(c, a, Mode.PLAIN));
        }
        s.test("1111-witheraim-hold", c -> run(c, null, Mode.HOLD));
        s.test("1111-witheraim-boss-off", c -> run(c, null, Mode.BOSS_OFF));
    }

    private enum Mode { PLAIN, HOLD, BOSS_OFF }

    private static String describe(Object p) {
        if (p instanceof ServerboundMovePlayerPacket m && m.hasRotation()) {
            return String.format(Locale.ROOT, "rot %.4f %.4f", m.getYRot(0f), m.getXRot(0f));
        }
        if (p instanceof ServerboundAttackPacket a) {
            return "attack " + a.entityId();
        }
        return p.getClass().getSimpleName();
    }

    private static void run(Session c, SameTick.Act act, Mode mode) throws Exception {
        Object attack = c.onClient(mc -> Mod.enumValue(Mod.ROOT + CFG + "$ClickType", "ATTACK"));
        int[] cpsWas = c.onClient(mc -> new int[]{(Integer) Mod.call(Mod.cfg(CFG), "getDelayMin"),
                (Integer) Mod.call(Mod.cfg(CFG), "getDelayMax")});
        int cps = act == null ? 20 : 16;
        SameTick st = null;
        try (HxKit.Settings off = GrimTickCases.gateOff(c);
             HxKit.Settings ip = GrimTickCases.hypixelIp(c);
             HxKit.Settings on = new HxKit.Settings(c).field(CFG, "clickType", attack)
                     .custom(() -> Mod.call(Mod.cfg(CFG), "setDelayRange", 1000 / cps, 1000 / cps),
                             () -> Mod.call(Mod.cfg(CFG), "setDelayRange", cpsWas[0], cpsWas[1]))
                     .custom(() -> Mod.call(Mod.cfg(CFG), "setAimAt", 2, mode != Mode.BOSS_OFF),
                             () -> Mod.call(Mod.cfg(CFG), "setAimAt", 2, true))
                     .with(CFG, "AimDelayMs", 0).with(CFG, "AllWithers", false).with(CFG, "Enabled", false)
                     .with(CFG, "AimAutoHit", true).with(CFG, "AimHoldLeftClick", mode == Mode.HOLD)
                     .with(CFG, "Aimbot", false)) {
            c.server().command("forceload add 34 42 46 56");
            c.server().sync(20);
            c.server().command("fill 34 148 42 46 150 56 stone");
            SameTickGapCases.onPlatform(c, 40.5, 151.0, 47.7, new BlockPos(40, 150, 47));
            SameTickCases.sticks(c);
            c.hx().sidebar("SKYBLOCK", "The Catac§combs §7(F7)");
            c.waitUntil("DungeonState F7", mc -> Boolean.TRUE.equals(Mod.staticCall("secrets.DungeonState", "isF7OrM7")), 100);
            c.hx().chat(TerminalAuraCases.GOLDOR);
            c.waitUntil("Floor7Tracker phase P3",
                    mc -> "P3".equals(String.valueOf(Mod.staticCall(TerminalAuraCases.TRACKER, "getPhase"))), 100);
            c.server().command("kill @e[tag=pnggoldor]");
            // In melee reach of his eyes (box 2.45 blocks), so Auto Hit attacks.
            c.server().command("summon wither 40.5 151 50.6 {Tags:[\"pnggoldor\"],NoAI:1b,Silent:1b,Invul:0,"
                    + "PersistenceRequired:1b,CustomName:\"Goldor\"}");
            c.waitUntil("the client to have the wither", mc -> mc.level.getEntitiesOfClass(
                    net.minecraft.world.entity.boss.wither.WitherBoss.class, BOX, e -> e.isAlive()).size() == 1, 200);
            int wither = c.onClient(mc -> mc.level.getEntitiesOfClass(
                    net.minecraft.world.entity.boss.wither.WitherBoss.class, BOX, e -> e.isAlive()).get(0).getId());
            // Facing 150 degrees away from him, level.
            c.hx().call("dungeon.tp", "x", 40.5, "y", 151.0, "z", 47.7, "yaw", 150f, "pitch", 0f);
            c.waitUntil("the client facing away", mc -> Math.abs(net.minecraft.util.Mth.wrapDegrees(mc.player.getYRot() - 150f)) < 0.01f, 60);
            c.ctx().waitTicks(3);
            if (act == SameTick.Act.ATTACK) {
                // His attack lands on a zombie between him and Goldor once the aimbot has turned him (the aimbot traces
                // Goldor through it). Before that his click goes into the air.
                c.server().command("summon zombie 40.5 151 49.0 {NoAI:1b,Invulnerable:1b,Silent:1b,Tags:[\"sametick\"]}");
            }
            if (act == SameTick.Act.USE_KEY) {
                // Glass on the line to Goldor: his right click there is use_item_on + use_item (PacketOrderJ after an attack).
                c.server().command("setblock 40 152 49 minecraft:glass");
            }
            int aim0 = c.onClient(mc -> ((Number) Mod.staticCall(FEATURE, "aimTicks")).intValue());
            int[] before = c.onClient(mc -> (int[]) Mod.staticCall(FEATURE, "clickCounts"));
            long since = c.hx().head();
            c.onClient(mc -> {
                WireCapture.describe = WitherAimCases::describe;
                WireCapture.start();
                return null;
            });
            if (act != null) {
                st = SameTickCases.start(c, act, p -> p instanceof ServerboundAttackPacket a && a.entityId() == wither);
            }
            c.onClient(mc -> {
                Mod.set(CFG, "setAimbot", true);
                return null;
            });
            if (mode == Mode.HOLD) {
                c.ctx().waitTicks(30);
                int idle = c.onClient(mc -> ((Number) Mod.staticCall(FEATURE, "aimTicks")).intValue()) - aim0;
                float yaw = c.onClient(mc -> mc.player.getYRot());
                c.note("hold: before the key, aim ticks " + idle + ", yaw " + yaw);
                c.check(idle == 0 && Math.abs(yaw - 150f) < 0.01f, "Hold Left Click turned him without the attack key held ("
                        + idle + " aim ticks, yaw " + yaw + ")");
                c.onClient(mc -> {
                    mc.options.keyAttack.setDown(true);
                    return null;
                });
            }
            for (int i = 0; i < 80; i++) {
                if (act == SameTick.Act.TP && i % 4 == 0) {
                    c.server().command("execute as @a at @s run tp @s ~ ~ ~");
                }
                if (act == SameTick.Act.CLICK && i >= 20 && i % 6 == 0) {
                    // His inventory open for 3 ticks (his click goes in at END while it is), then closed: the aimbot's
                    // next attack follows his last click in the same server tick.
                    c.onClient(mc -> {
                        dev.testkit.compat.McCompat.setScreen(mc, new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player));
                        return null;
                    });
                    c.ctx().waitTicks(3);
                    c.onClient(mc -> {
                        mc.player.closeContainer();
                        return null;
                    });
                    i += 3;
                }
                c.ctx().waitTick();
            }
            c.onClient(mc -> {
                Mod.set(CFG, "setAimbot", false);
                mc.options.keyAttack.setDown(false);
                return null;
            });
            c.ctx().waitTicks(10);
            List<WireCapture.Sent> wire = c.onClient(mc -> {
                WireCapture.stop();
                WireCapture.describe = null;
                return WireCapture.snapshot();
            });
            int aimed = c.onClient(mc -> ((Number) Mod.staticCall(FEATURE, "aimTicks")).intValue()) - aim0;
            int[] after = c.onClient(mc -> (int[]) Mod.staticCall(FEATURE, "clickCounts"));
            List<float[]> rots = new ArrayList<>();
            int wireAttacks = 0;
            for (WireCapture.Sent s : wire) {
                if (s.text().startsWith("rot ")) {
                    String[] p = s.text().split(" ");
                    rots.add(new float[]{Float.parseFloat(p[1]), Float.parseFloat(p[2])});
                } else if (s.text().equals("attack " + wither)) {
                    wireAttacks++;
                }
            }
            // The server may still hold the last packets when the client stops (it lags under load): count only after
            // it has had up to 60 more ticks to catch up, so a short count is a real loss, not a counting window.
            // An attack the anticheat cancels never reaches the server: count those by reason. Its only expected cancel
            // is "teleport": every server teleport sent and not yet answered drops an attack the client sent before it
            // arrived (a race no client can see coming, so a TP case may lose a few), never one the client sent after.
            int server = 0;
            int tpCancels = 0;
            int otherCancels = 0;
            String cancelReasons = "";
            for (int w = 0; w <= 60; w++) {
                server = 0;
                tpCancels = 0;
                otherCancels = 0;
                cancelReasons = "";
                for (JsonObject e : c.hx().events(since, "attack_entity")) {
                    if (e.get("entityId").getAsInt() == wither) server++;
                }
                for (JsonObject e : c.hx().events(since, "ac_attack_cancel")) {
                    if (e.get("entityId").getAsInt() != wither) continue;
                    String why = e.get("reason").getAsString();
                    if (why.equals("teleport")) tpCancels++; else otherCancels++;
                    cancelReasons += " " + why + "@" + e.get("tick").getAsInt();
                }
                if (server + tpCancels + otherCancels >= wireAttacks || w == 60) break;
                c.ctx().waitTick();
            }
            float maxStep = 0f;
            float maxPitch = 0f;
            for (int i = 0; i < rots.size(); i++) {
                maxPitch = Math.max(maxPitch, Math.abs(rots.get(i)[1]));
                if (i > 0) {
                    maxStep = Math.max(maxStep, Math.abs(rots.get(i)[0] - rots.get(i - 1)[0]));
                }
            }
            float[] last = rots.isEmpty() ? null : rots.get(rots.size() - 1);
            boolean strikes = last != null && c.onClient(mc -> {
                Vec3 eye = mc.player.getEyePosition();
                Vec3 end = eye.add(Vec3.directionFromRotation(last[1], last[0]).scale(16));
                return mc.level.getEntity(wither).getBoundingBox().clip(eye, end).isPresent();
            });
            c.note(String.format(Locale.ROOT, "%s %s: aim ticks %d, client attacks %d, wire attacks %d, server %d, rot packets %d,"
                            + " canceled before the server: teleport %d, other %d%s,"
                    + " first %s last %s, max yaw step %.2f, max |pitch| %.2f, last look strikes Goldor %s%s", mode, act,
                    aimed, after[0] - before[0], wireAttacks, server, rots.size(), tpCancels, otherCancels, cancelReasons,
                    rots.isEmpty() ? "-" : rots.get(0)[0] + "/" + rots.get(0)[1], last == null ? "-" : last[0] + "/" + last[1],
                    maxStep, maxPitch, strikes, st == null ? "" : "; " + st.report()));
            if (mode == Mode.BOSS_OFF) {
                c.check(aimed == 0 && server == 0, "Goldor's switch was off but the aimbot aimed " + aimed + " ticks and hit "
                        + server + " times");
                return;
            }
            c.check(aimed >= 20, "the aimbot barely aimed: " + aimed + " ticks");
            c.check(strikes, "the last look on the wire does not strike Goldor (" + (last == null ? "no rot packet" : last[0] + "/" + last[1]) + ")");
            c.check(rots.size() >= 3 && maxStep <= 100f, "the 150-degree turn was a snap on the wire: " + rots.size() + " rot packets, max step " + maxStep);
            c.check(maxPitch <= 90f, "pitch left -90..90: " + maxPitch);
            // A wrapped yaw would jump by ~360 somewhere; the running yaw goes from 150 toward 0 (or 360) as one number.
            c.check(last == null || Math.abs(last[0] - 150f) < 180f, "the yaw was wrapped: last " + (last == null ? "-" : last[0]));
            c.check(otherCancels == 0 && (act == SameTick.Act.TP || tpCancels == 0), "the anticheat canceled attacks:"
                    + cancelReasons);
            c.check(wireAttacks >= 8 && server + tpCancels == wireAttacks, "Auto Hit: " + wireAttacks + " attacks on the wire,"
                    + " server " + server + ", canceled for a pending teleport " + tpCancels + " (80 ticks at " + cps + " CPS)");
            if (st != null) {
                SameTick done = st;
                st = null;
                if (act == SameTick.Act.TP) {
                    c.onClient(mc -> {
                        done.close();
                        return null;
                    });
                    c.note("tp " + done.report());
                } else if (act == SameTick.Act.CLICK) {
                    SameTick.judge(c, done, "wither aimbot", SameTick.Expect.ACTED);
                } else {
                    SameTickCases.judge(c, done, "wither aimbot");
                }
            }
        } finally {
            if (st != null) {
                SameTick s0 = st;
                c.onClient(mc -> {
                    s0.close();
                    return null;
                });
            }
            c.onClient(mc -> {
                Mod.set(CFG, "setAimbot", false);
                mc.options.keyAttack.setDown(false);
                WireCapture.stop();
                if (dev.testkit.compat.McCompat.screen(mc) != null) mc.player.closeContainer();
                return null;
            });
            c.server().command("kill @e[tag=pnggoldor]");
            c.server().command("kill @e[tag=sametick]");
            GrimTickCases.home(c);
            c.server().sync(5);
            c.server().command("fill 34 148 42 46 154 56 air");
            c.server().command("forceload remove 34 42 46 56");
            c.server().command("clear @a");
        }
    }
}
