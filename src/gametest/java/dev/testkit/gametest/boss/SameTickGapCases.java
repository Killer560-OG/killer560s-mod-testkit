package dev.testkit.gametest.boss;

import com.google.gson.JsonObject;

import dev.testkit.gametest.SameTick;
import dev.testkit.gametest.hx.HxKit;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.hx.TriggerTiming;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * SAME-TICK GRIM CASES, second set (grimgapB-1105): the boss clickers the first set (grimsame-1105) left as gaps. Each runs
 * while HIS OWN action goes into every client tick ({@link SameTick}) and passes only when the server saw the feature act,
 * his action and the feature's packet both reached the wire, and GrimAC (default and experimental) said nothing.
 * <ul>
 *   <li>1105-same-autoss-&lt;act&gt;: Simon Says Auto Solve plays a four-round device behind him (use_item_on).</li>
 *   <li>1105-same-goldor-&lt;act&gt;: the Goldor Triggerbot in Use mode shooting Goldor in P3 (use_item).</li>
 *   <li>1105-same-witherbot-&lt;act&gt;: the same triggerbot in Attack mode, the wither in melee reach (attack).</li>
 *   <li>1105-same-thorn-&lt;act&gt;: the Thorn Triggerbot holding right click with the Tribal Spear on Thorn. No USE case:
 *       the triggerbot holds the very use key his right click would press.</li>
 * </ul>
 * His ATTACK goes on a NoAI zombie between him and the target (the triggerbots trace their own target through it).
 */
final class SameTickGapCases {

    private static final SameTick.Act[] ACTS = {SameTick.Act.USE, SameTick.Act.ATTACK, SameTick.Act.SWAP};

    private SameTickGapCases() {
    }

    static void register(Session s) {
        if (!dev.testkit.gametest.mod.Mod.isCheat()) {
            return;
        }
        for (SameTick.Act a : ACTS) {
            String n = a.name().toLowerCase(Locale.ROOT);
            s.test("1105-same-autoss-" + n, c -> autoSs(c, a));
            s.test("1105-same-goldor-" + n, c -> goldor(c, a, false));
            // Wither bot: his right click as a real key press, so the bot can see it coming (USE_KEY).
            s.test("1105-same-witherbot-" + n, c -> goldor(c, a == SameTick.Act.USE ? SameTick.Act.USE_KEY : a, true));
            if (a != SameTick.Act.USE) {
                s.test("1105-same-thorn-" + n, c -> thorn(c, a));
            }
        }
    }

    private static void zombie(Session c, double x, double y, double z) {
        c.server().command("summon zombie " + x + " " + y + " " + z + " {NoAI:1b,Invulnerable:1b,Silent:1b,Tags:[\"sametick\"]}");
        c.waitUntil("the zombie under his crosshair", mc -> mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult, 100);
    }

    /**
     * Onto a platform just built far from home: the first teleport lands before the client has the chunk (GroundSpoof /
     * Simulation at the landing, setup only), so it goes there once, waits for the blocks, and lands again.
     */
    private static void onPlatform(Session c, double x, double y, double z, BlockPos floor) {
        c.hx().call("dungeon.tp", "x", x, "y", y, "z", z, "yaw", 0f, "pitch", 0f);
        c.waitUntil("the client to have the platform", mc -> mc.level.getBlockState(floor)
                .is(net.minecraft.world.level.block.Blocks.STONE), 200);
        c.ctx().waitTicks(10);
        c.hx().call("dungeon.tp", "x", x, "y", y, "z", z, "yaw", 0f, "pitch", 0f);
        c.waitUntil("the player on the platform", mc -> mc.player.position().distanceTo(new Vec3(x, y, z)) < 0.1
                && mc.player.onGround(), 200);
    }

    private static void stop(Session c, SameTick st) {
        if (st != null) {
            c.onClient(mc -> {
                st.close();
                return null;
            });
        }
    }

    // ---- Simon Says Auto Solve -------------------------------------------------------------------------------------

    private static void autoSs(Session c, SameTick.Act act) throws Exception {
        String cfg = SimonSaysCases.CONFIG;
        List<AutoCloseable> undo = new ArrayList<>();
        SameTick st = null;
        try {
            c.hx().sidebar("SKYBLOCK", "The Catac§combs §7(F7)");
            c.waitUntil("DungeonState F7", mc -> "F7".equals(Mod.staticCall("secrets.DungeonState", "getFloor")), 100);
            undo.add(c.onClient(mc -> Mod.with(cfg, "SolverEnabled", true)));
            undo.add(c.onClient(mc -> Mod.with(cfg, "AutoRestartEnabled", false)));
            undo.add(c.onClient(mc -> Mod.with(cfg, "AutoStartEnabled", false)));
            undo.add(c.onClient(mc -> Mod.with(cfg, "TriggerBotEnabled", false)));
            undo.add(c.onClient(mc -> Mod.with(cfg, "AutoSolveRotate", false)));
            undo.add(c.onClient(mc -> Mod.with(cfg, "AutoSolveEnabled", true)));
            undo.add(c.onClient(mc -> Mod.with(cfg, "DiagnosticLoggingEnabled", true)));
            c.hx().call("boss.ss.build", "rounds", 4, "line", true, "seed", 31 + act.ordinal());
            c.hx().call("dungeon.tp", "x", 107.5, "y", 120.0, "z", 93.5, "yaw", -90f, "pitch", 0f);
            c.waitUntil("the client to have the device wall and start button", mc ->
                    mc.level.getBlockState(new BlockPos(111, 120, 92)).is(net.minecraft.world.level.block.Blocks.OBSIDIAN)
                            && mc.level.getBlockState(SimonSaysCases.START).is(net.minecraft.world.level.block.Blocks.STONE_BUTTON), 200);
            c.ctx().waitTicks(5);
            undo.add(c.onClient(mc -> Mod.with(cfg, "Enabled", true)));
            c.waitUntil("the mod to be at the device", mc -> (Boolean) Mod.field(SimonSaysCases.FEATURE, "wasActive"), 200);
            c.ctx().waitTicks(5);
            SimonSaysCases.click(c, SimonSaysCases.START);
            c.waitUntil("the device to start (Hx)", mc -> !"IDLE".equals(SimonSaysCases.state(c).get("phase").getAsString()), 60);
            // Back to the device: his right click goes into the air, his attack onto a zombie in front of him.
            c.hx().call("dungeon.tp", "x", 107.5, "y", 120.0, "z", 93.5, "yaw", 90f, "pitch", 0f);
            c.waitUntil("the client facing away", mc -> Math.abs(net.minecraft.util.Mth.wrapDegrees(mc.player.getYRot() - 90f)) < 0.01f, 40);
            SameTickCases.sticks(c);
            if (act == SameTick.Act.ATTACK) {
                zombie(c, 105.5, 120, 93.5);
            }
            st = SameTickCases.start(c, act, p -> p instanceof ServerboundUseItemOnPacket);
            try {
                c.waitUntil("ss.completed (Hx) with Auto Solve playing", mc -> !c.events("ss.completed").isEmpty(), 1200);
            } finally {
                SameTick s0 = st;
                c.note("auto ss: state " + SimonSaysCases.state(c) + ", server presses " + c.events("ss.press").size()
                        + ", breaks " + c.events("ss.broke").size() + ", " + s0.report() + ", held " + c.onClient(mc ->
                        String.valueOf(mc.player.getMainHandItem())));
            }
            c.ctx().waitTicks(5);
            c.note("server presses " + c.events("ss.press").size() + ", breaks " + c.events("ss.broke").size());
            c.check(c.events("ss.broke").isEmpty(), "the device broke: " + c.events("ss.broke"));
            SameTickCases.judge(c, st, "auto ss");
            st = null;
        } finally {
            stop(c, st);
            c.hx().call("boss.ss.clear");
            c.onClient(mc -> {
                BossAuraTurnCases.restore(undo);
                return null;
            });
            c.server().command("kill @e[tag=sametick]");
            c.server().command("clear @a stick");
            GrimTickCases.home(c);
        }
    }

    // ---- Goldor Triggerbot / Wither Boss Triggerbot ----------------------------------------------------------------

    private static void goldor(Session c, SameTick.Act act, boolean attackMode) throws Exception {
        String cfg = GrimTickCases.GT_CFG;
        Object type = c.onClient(mc -> Mod.enumValue(Mod.ROOT + cfg + "$ClickType", attackMode ? "ATTACK" : "USE"));
        int[] cpsWas = c.onClient(mc -> new int[]{(Integer) Mod.call(Mod.cfg(cfg), "getCpsMin"),
                (Integer) Mod.call(Mod.cfg(cfg), "getCpsMax")});
        String what = attackMode ? "wither bot" : "goldor bot";
        SameTick st = null;
        try (HxKit.Settings off = GrimTickCases.gateOff(c);
             HxKit.Settings ip = GrimTickCases.hypixelIp(c);
             HxKit.Settings on = new HxKit.Settings(c).field(cfg, "clickType", type)
                     .custom(() -> Mod.call(Mod.cfg(cfg), "setCpsRange", 16, 16),
                             () -> Mod.call(Mod.cfg(cfg), "setCpsRange", cpsWas[0], cpsWas[1]))
                     .with(cfg, "AimDelayMs", 0).with(cfg, "AllWithers", false).with(cfg, "Enabled", false)) {
            c.server().command("forceload add 34 42 46 56");
            c.server().sync(20);
            c.server().command("fill 34 148 42 46 150 56 stone");
            onPlatform(c, 40.5, 151.0, 47.7, new BlockPos(40, 150, 47));
            SameTickCases.sticks(c);
            c.hx().sidebar("SKYBLOCK", "The Catac§combs §7(F7)");
            c.waitUntil("DungeonState F7", mc -> Boolean.TRUE.equals(Mod.staticCall("secrets.DungeonState", "isF7OrM7")), 100);
            c.hx().chat(TerminalAuraCases.GOLDOR);
            c.waitUntil("Floor7Tracker phase P3",
                    mc -> "P3".equals(String.valueOf(Mod.staticCall(TerminalAuraCases.TRACKER, "getPhase"))), 100);
            c.server().command("kill @e[tag=pnggoldor]");
            // In melee reach (box 2.45 blocks from his eyes), so Attack mode attacks rather than swings.
            c.server().command("summon wither 40.5 151 50.6 {Tags:[\"pnggoldor\"],NoAI:1b,Silent:1b,Invul:0,"
                    + "PersistenceRequired:1b,CustomName:\"Goldor\"}");
            AABB box = new AABB(30, 140, 40, 50, 170, 60);
            c.waitUntil("the client to have the wither", mc -> mc.level.getEntitiesOfClass(
                    net.minecraft.world.entity.boss.wither.WitherBoss.class, box, e -> e.isAlive()).size() == 1, 200);
            int[] wither = c.onClient(mc -> new int[]{mc.level.getEntitiesOfClass(
                    net.minecraft.world.entity.boss.wither.WitherBoss.class, box, e -> e.isAlive()).get(0).getId()});
            Vec3 at = c.onClient(mc -> mc.level.getEntity(wither[0]).getBoundingBox().getCenter());
            TriggerTiming.look(c, at);
            c.ctx().waitTicks(3);
            if (act == SameTick.Act.ATTACK) {
                zombie(c, 40.5, 151, 49.0);
            }
            if (act == SameTick.Act.USE_KEY) {
                // A glass block between him and the wither, on his crosshair: his right click is use_item_on + use_item
                // (no entity interact), which after the bot's attack in one tick is PacketOrderJ. The bot traces Goldor
                // through it (no occlusion), so it keeps attacking.
                c.server().command("setblock 40 152 49 minecraft:glass");
                c.waitUntil("the glass under his crosshair", mc -> mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult bh
                        && bh.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK, 100);
            }
            int[] before = c.onClient(mc -> (int[]) Mod.staticCall("goldor.GoldorTriggerbotFeature", "clickCounts"));
            long since = c.hx().head();
            st = SameTickCases.start(c, act, attackMode
                    ? p -> p instanceof ServerboundAttackPacket a && a.entityId() == wither[0]
                    : p -> p instanceof ServerboundUseItemPacket);
            c.onClient(mc -> {
                Mod.set(cfg, "setEnabled", true);
                return null;
            });
            c.ctx().waitTicks(60);
            c.onClient(mc -> {
                Mod.set(cfg, "setEnabled", false);
                return null;
            });
            c.ctx().waitTicks(20);
            int[] after = c.onClient(mc -> (int[]) Mod.staticCall("goldor.GoldorTriggerbotFeature", "clickCounts"));
            int mine = attackMode ? after[0] - before[0] : after[2] - before[2];
            int server = 0;
            for (JsonObject e : c.hx().events(since, attackMode ? "attack_entity" : "use_item")) {
                if (!attackMode || e.get("entityId").getAsInt() == wither[0]) server++;
            }
            c.note(what + ": " + (attackMode ? "attacks" : "uses") + " " + mine + ", server " + server + "; " + st.report());
            c.check(mine >= 10, what + ": only " + mine + " clicks in 60 ticks at 16 CPS");
            c.check(server >= mine, what + ": the server saw " + server + " of " + mine + " clicks");
            SameTickCases.judge(c, st, what);
            st = null;
        } finally {
            stop(c, st);
            c.onClient(mc -> {
                Mod.set(cfg, "setEnabled", false);
                return null;
            });
            c.server().command("kill @e[tag=pnggoldor]");
            c.server().command("kill @e[tag=sametick]");
            c.server().command("fill 34 148 42 46 154 56 air");
            c.server().command("forceload remove 34 42 46 56");
            c.server().command("clear @a");
            GrimTickCases.home(c);
        }
    }

    // ---- Thorn Triggerbot -----------------------------------------------------------------------------------------

    private static void thorn(Session c, SameTick.Act act) throws Exception {
        String cfg = GrimTickCases.THORN_CFG;
        SameTick st = null;
        try (HxKit.Settings off = GrimTickCases.gateOff(c);
             HxKit.Settings ip = GrimTickCases.hypixelIp(c);
             HxKit.Settings on = new HxKit.Settings(c).with(cfg, "TriggerbotAimed", true)
                     .custom(() -> Mod.set(cfg, "setTriggerbot", false), () -> Mod.set(cfg, "setTriggerbot", false))) {
            // Two spears: his swap goes between them and the triggerbot keeps holding.
            c.server().command("item replace entity @a hotbar.4 with " + GrimTickCases.TRIBAL);
            c.server().command("item replace entity @a hotbar.5 with " + GrimTickCases.TRIBAL);
            c.server().command("forceload add 34 42 46 76");
            c.server().sync(20);
            c.server().command("fill 34 167 42 46 169 56 stone");
            onPlatform(c, 40.5, 170.0, 47.7, new BlockPos(40, 169, 47));
            c.onClient(mc -> {
                mc.player.getInventory().setSelectedSlot(4);
                return null;
            });
            c.hx().sidebar("SKYBLOCK", "The Catac§combs §7(F4)");
            c.waitUntil("DungeonState F4", mc -> "F4".equals(Mod.staticCall("secrets.DungeonState", "getFloor")), 100);
            c.hx().chat("[BOSS] Thorn: Welcome Adventurers! I am Thorn, the Spirit! And host of the Vegan Trials!");
            c.waitUntil("in the Thorn fight", mc -> Boolean.TRUE.equals(Mod.staticCall("thorn.ThornFeature", "inThornBoss")), 100);
            c.server().command("kill @e[tag=pngthorn]");
            c.server().command("summon ghast 40.5 169 62 {Tags:[\"pngthorn\"],NoAI:1b,NoGravity:1b,Silent:1b,"
                    + "PersistenceRequired:1b,CustomName:\"Thorn\"}");
            AABB box = new AABB(30, 160, 50, 50, 190, 80);
            c.waitUntil("the client to have Thorn", mc -> mc.level.getEntitiesOfClass(
                    net.minecraft.world.entity.monster.Ghast.class, box, e -> e.isAlive()).size() == 1, 200);
            Vec3 at = c.onClient(mc -> mc.level.getEntitiesOfClass(
                    net.minecraft.world.entity.monster.Ghast.class, box, e -> e.isAlive()).get(0).getBoundingBox().getCenter());
            TriggerTiming.look(c, at);
            c.ctx().waitTicks(3);
            if (act == SameTick.Act.ATTACK) {
                zombie(c, 40.5, 170, 49.0);
            }
            long since = c.hx().head();
            SameTick done = SameTickCases.start(c, act, p -> p instanceof ServerboundUseItemPacket);
            st = done;
            c.onClient(mc -> {
                Mod.set(cfg, "setTriggerbot", true);
                return null;
            });
            c.ctx().waitTicks(60);
            boolean holding = c.onClient(mc -> (Boolean) Mod.staticCall("thorn.ThornTriggerbot", "isHolding"));
            c.onClient(mc -> {
                Mod.set(cfg, "setTriggerbot", false);
                return null;
            });
            c.ctx().waitTicks(5);
            int uses = c.hx().events(since, "use_item").size();
            c.onClient(mc -> {
                done.close();
                return null;
            });
            st = null;
            c.note("thorn: holding " + holding + ", server use_item " + uses + "; " + done.report());
            c.check(holding, "the triggerbot was not holding right click on Thorn");
            c.check(uses >= 5, "only " + uses + " spear throws reached the server in 60 ticks");
            c.check(done.done() > 0 && done.playerWindows() > 0 && done.featureWindows() > 0,
                    "his " + act + " or the triggerbot's throw never reached the wire (" + done.report() + ")");
            // Vanilla sends both (the triggerbot only holds the use key): his attack goes before the throw in its own
            // handleKeybinds, so a shared tick is vanilla's own order, and GrimAC is the judge.
        } finally {
            stop(c, st);
            c.onClient(mc -> {
                Mod.set(cfg, "setTriggerbot", false);
                mc.options.keyUse.setDown(false);
                return null;
            });
            c.server().command("kill @e[tag=pngthorn]");
            c.server().command("kill @e[tag=sametick]");
            c.server().command("fill 34 167 42 46 169 56 air");
            c.server().command("forceload remove 34 42 46 76");
            c.server().command("clear @a");
            GrimTickCases.home(c);
        }
    }
}
