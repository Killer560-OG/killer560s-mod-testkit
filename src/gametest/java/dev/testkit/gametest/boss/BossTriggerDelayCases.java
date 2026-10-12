package dev.testkit.gametest.boss;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.HxKit;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.hx.TriggerTiming;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Mod branch trigger-delays (killer560, 2026-10-11: "The trigger bots should all have their own unique specific timer
 * for the player to select their delay they prefer"): the P3 trigger bots, each aimed at its target twice - at delay 0
 * and at a longer delay - with the click read on the server's clock ({@link TriggerTiming}). The delay must move it by
 * delay / 50 ticks (+-1.5).
 *
 * <ul>
 *   <li>799 Terminal Triggerbot ({@code Delay}): a terminal stand (hx/boss/HxTerminalStand), F7 sidebar and Goldor's P3
 *   line; 0 vs 400 ms, the server's {@code term.interact}.</li>
 *   <li>786 Simon Says Trigger Bot ({@code Trigger Bot Delay}): the device (hx/boss/HxSimonSays) started by hand, round
 *   1 revealed, crosshair onto its button; 0 vs 300 ms, {@code use_block} on that button. Run expecting flags: the
 *   frame-driven click draws GrimAC Post.</li>
 *   <li>787 Goldor Triggerbot ({@code Aim Delay}): a real (visible, no 800-tick invulnerability) NoAI wither in the boss
 *   area, Attack mode, in melee reach; 0 vs 400 ms to the FIRST {@code attack_entity}. Run expecting flags: the
 *   frame-driven attack draws GrimAC Post.</li>
 *   <li>788 Arrow Align Trigger Bot ({@code Trigger Bot Delay}): arrow item frames on the device grid (x -2, y 120-124,
 *   z 75-79) in layout 7 of the mod's solutions, each needing one click; 0 vs 300 ms, {@code use_entity} on the
 *   frame. Run expecting flags: the frame-driven click draws GrimAC Post.</li>
 * </ul>
 * Legit jar: none of these trigger bots exists, so each case checks nothing is clicked.
 */
final class BossTriggerDelayCases {

    private BossTriggerDelayCases() {
    }

    static void register(Session s) {
        s.test("799-boss-trigger-delay-terminal", BossTriggerDelayCases::terminal);
        // Expecting flags: the Simon Says Trigger Bot clicks from the render-frame hook, so its use packet can follow
        // the tick's movement packet and GrimAC says Post (2 on the first run, one per click, 2026-10-11) - the
        // feature's own behaviour, not this case's; recorded, not hidden.
        s.testExpectingFlags("786-boss-trigger-delay-simonsays", BossTriggerDelayCases::simonSays);
        // Expecting flags too: Goldor Triggerbot also clicks from the render-frame hook (GrimAC Post "attack").
        s.testExpectingFlags("787-boss-trigger-delay-goldor", BossTriggerDelayCases::goldor);
        // Expecting flags for the same reason as 786: the Trigger Bot clicks from the render-frame hook (GrimAC Post,
        // "interact entity", one per click on the first run).
        s.testExpectingFlags("788-boss-trigger-delay-arrowalign", BossTriggerDelayCases::arrowAlign);
    }

    private static HxKit.Settings gateOff(Session c) {
        boolean gateWas = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled"));
        return new HxKit.Settings(c).custom(() -> Mod.staticCall("util.SkyblockGate", "setEnabled", false),
                () -> Mod.staticCall("util.SkyblockGate", "setEnabled", gateWas));
    }

    /** F7 sidebar + Goldor's line: Floor7Tracker in P3. */
    private static void p3(Session c) {
        c.hx().sidebar("SKYBLOCK", "The Catac§combs §7(F7)");
        c.waitUntil("DungeonState F7", mc -> Boolean.TRUE.equals(Mod.staticCall("secrets.DungeonState", "isF7OrM7")), 100);
        c.hx().chat(TerminalAuraCases.GOLDOR);
        c.waitUntil("Floor7Tracker phase P3",
                mc -> "P3".equals(String.valueOf(Mod.staticCall(TerminalAuraCases.TRACKER, "getPhase"))), 100);
    }

    private static void closeScreen(Session c) {
        c.onClient(mc -> {
            if (McCompat.screen(mc) != null) {
                mc.player.closeContainer();
                McCompat.setScreen(mc, null);
            }
            return null;
        });
    }

    private static void home(Session c) {
        c.hx().sidebarClear();
        c.hx().call("dungeon.tp", "x", -619.5, "y", 151.0, "z", -619.5, "yaw", 0f, "pitch", 0f);
    }

    private static void verdict(Session c, String what, long[] t, int delayMs) {
        if (Mod.isCheat()) {
            TriggerTiming.judge(c, what, t[0], t[1], delayMs);
        } else {
            c.note(what + " on a legit jar: " + t[0] + " / " + t[1]);
            c.check(t[0] < 0 && t[1] < 0, "legit jar: " + what + " clicked by itself");
        }
    }

    // ---- 799 --------------------------------------------------------------------------------------------------------

    private static final String TT_CFG = "terminaltrigger.TerminalTriggerbotConfig";

    private static void terminal(Session c) throws Exception {
        int x = TerminalAuraCases.X;
        int z = TerminalAuraCases.Z;
        int g = TerminalAuraCases.G;
        int[] delays = {0, 400};
        long[] t = new long[2];
        try (HxKit.Settings off = gateOff(c);
             HxKit.Settings on = new HxKit.Settings(c).with(TerminalAuraCases.CONFIG, "Enabled", false)
                     .with(TT_CFG, "Enabled", true).with(TT_CFG, "DelayMs", 0)) {
            for (int i = 0; i < 2; i++) {
                int delay = delays[i];
                c.onClient(mc -> {
                    Mod.call(Mod.cfg(TT_CFG), "setDelayMs", delay);
                    return null;
                });
                c.hx().call("boss.term.build", "x", x, "z", z, "groundY", g);
                TerminalAuraCases.tp(c, x - 1.5, g + 3, z + 0.5, 90f);
                c.ctx().waitTicks(20);
                int standId = c.hx().call("boss.term.spawn").getAsJsonObject().get("standId").getAsInt();
                c.hx().call("boss.term.config", "line", true);
                c.waitUntil("the client to have the terminal stand", mc -> mc.level.getEntity(standId) != null, 200);
                if (i == 0) {
                    p3(c);
                }
                Vec3 at = c.onClient(mc -> mc.level.getEntity(standId).position().add(0, 1.0, 0));
                t[i] = TriggerTiming.ticksToFire(c, at, "term.interact", null, null, 60);
                c.ctx().waitTicks(5);
                closeScreen(c);
                c.hx().call("boss.term.clear");
                c.ctx().waitTicks(10);
            }
            verdict(c, "Terminal Triggerbot", t, delays[1]);
        } finally {
            closeScreen(c);
            c.hx().call("boss.term.clear");
            home(c);
        }
    }

    // ---- 786 --------------------------------------------------------------------------------------------------------

    private static void simonSays(Session c) throws Exception {
        String cfg = SimonSaysCases.CONFIG;
        int[] delays = {0, 300};
        long[] t = new long[2];
        try (HxKit.Settings off = gateOff(c);
             HxKit.Settings on = new HxKit.Settings(c).with(cfg, "SolverEnabled", true)
                     .with(cfg, "AutoRestartEnabled", false).with(cfg, "AutoSolveEnabled", false)
                     .with(cfg, "TriggerBotEnabled", true).with(cfg, "TriggerBotDelayMs", 0)) {
            c.hx().sidebar("SKYBLOCK", "The Catac§combs §7(F7)");
            c.waitUntil("DungeonState F7", mc -> "F7".equals(Mod.staticCall("secrets.DungeonState", "getFloor")), 100);
            for (int i = 0; i < 2; i++) {
                int delay = delays[i];
                c.onClient(mc -> {
                    Mod.call(Mod.cfg(cfg), "setTriggerBotDelayMs", delay);
                    Mod.call(Mod.cfg(cfg), "setEnabled", false);
                    return null;
                });
                c.hx().call("boss.ss.build", "rounds", 4, "line", true, "seed", 31 + i);
                c.hx().call("dungeon.tp", "x", 107.5, "y", 120.0, "z", 93.5, "yaw", -90f, "pitch", 0f);
                c.waitUntil("the client to have the device", mc ->
                        mc.level.getBlockState(new BlockPos(111, 120, 92)).is(net.minecraft.world.level.block.Blocks.OBSIDIAN)
                                && mc.level.getBlockState(SimonSaysCases.START).is(
                                net.minecraft.world.level.block.Blocks.STONE_BUTTON), 200);
                c.ctx().waitTicks(5);
                c.onClient(mc -> {
                    Mod.call(Mod.cfg(cfg), "setEnabled", true);
                    return null;
                });
                c.waitUntil("Simon Says at the device", mc -> (Boolean) Mod.field(SimonSaysCases.FEATURE, "wasActive"), 200);
                c.ctx().waitTicks(5);
                SimonSaysCases.click(c, SimonSaysCases.START);
                c.waitUntil("round 1 input (Hx)", mc -> {
                    JsonObject st = SimonSaysCases.state(c);
                    return "INPUT".equals(st.get("phase").getAsString()) && st.get("round").getAsInt() == 1;
                }, 400);
                c.ctx().waitTicks(14);
                // Off the device while the mod settles, so the first look at the button starts the delay.
                TriggerTiming.lookAway(c);
                c.ctx().waitTicks(3);
                JsonArray xyz = SimonSaysCases.state(c).getAsJsonArray("sequence").get(0).getAsJsonArray();
                BlockPos button = new BlockPos(xyz.get(0).getAsInt(), xyz.get(1).getAsInt(), xyz.get(2).getAsInt()).west();
                Vec3 at = c.onClient(mc -> SimonSaysCases.aimPoint(mc, button));
                t[i] = TriggerTiming.ticksToFire(c, at, "use_block", e -> {
                    JsonArray p = e.getAsJsonArray("pos");
                    return p.get(0).getAsInt() == button.getX() && p.get(1).getAsInt() == button.getY()
                            && p.get(2).getAsInt() == button.getZ();
                }, null, 60);
                c.hx().call("boss.ss.clear");
                c.ctx().waitTicks(10);
            }
            verdict(c, "Simon Says Trigger Bot", t, delays[1]);
        } finally {
            c.onClient(mc -> {
                Mod.call(Mod.cfg(cfg), "setEnabled", false);
                return null;
            });
            c.hx().call("boss.ss.clear");
            home(c);
        }
    }

    // ---- 787 --------------------------------------------------------------------------------------------------------

    private static final String GT_CFG = "goldor.GoldorTriggerbotConfig";

    private static void goldor(Session c) throws Exception {
        int[] delays = {0, 400};
        long[] t = new long[2];
        String ipWas = c.onClient(mc -> mc.getCurrentServer().ip);
        try (HxKit.Settings off = gateOff(c);
             HxKit.Settings on = new HxKit.Settings(c)
                     .custom(() -> net.minecraft.client.Minecraft.getInstance().getCurrentServer().ip = "mc.hypixel.net",
                             () -> net.minecraft.client.Minecraft.getInstance().getCurrentServer().ip = ipWas)
                     .with(GT_CFG, "Enabled", true).with(GT_CFG, "AimDelayMs", 0)) {
            // Loaded first: the console's fill refuses an unloaded area (the session spawn is 600 blocks away).
            c.hx().call("dungeon.tp", "x", 40.5, "y", 160.0, "z", 47.7, "yaw", 0f, "pitch", -90f);
            c.ctx().waitTicks(20);
            c.server().command("fill 34 148 42 46 150 56 stone");
            c.waitUntil("the client to have the platform", mc -> mc.level.getBlockState(new BlockPos(40, 150, 47))
                    .is(net.minecraft.world.level.block.Blocks.STONE), 200);
            c.hx().call("dungeon.tp", "x", 40.5, "y", 151.0, "z", 47.7, "yaw", 0f, "pitch", -90f);
            c.waitUntil("the player on the platform", mc -> mc.player.position().distanceTo(new Vec3(40.5, 151.0, 47.7))
                    < 0.1 && mc.player.onGround(), 200);
            p3(c);
            for (int i = 0; i < 2; i++) {
                // A fresh wither each trial: the first trial's hits knock a NoAI wither out of melee reach.
                c.server().command("kill @e[tag=pnggoldor]");
                // Gone completely first: a new wither summoned into a dying one is shoved out of reach.
                c.waitUntil("no wither left", mc -> mc.level.getEntitiesOfClass(
                        net.minecraft.world.entity.boss.wither.WitherBoss.class,
                        new net.minecraft.world.phys.AABB(30, 140, 40, 50, 170, 60)).isEmpty(), 100);
                c.server().command("summon wither 40.5 151 50.5 {Tags:[\"pnggoldor\"],NoAI:1b,Silent:1b,Invul:0,"
                        + "PersistenceRequired:1b,CustomName:\"Goldor\"}");
                c.waitUntil("the client to have one wither", mc -> mc.level.getEntitiesOfClass(
                        net.minecraft.world.entity.boss.wither.WitherBoss.class,
                        new net.minecraft.world.phys.AABB(30, 140, 40, 50, 170, 60), e -> e.isAlive()).size() == 1, 200);
                int delay = delays[i];
                c.onClient(mc -> {
                    Mod.call(Mod.cfg(GT_CFG), "setAimDelayMs", delay);
                    return null;
                });
                TriggerTiming.lookAway(c);
                c.ctx().waitTicks(10);
                Vec3 at = c.onClient(mc -> mc.level.getEntitiesOfClass(
                        net.minecraft.world.entity.boss.wither.WitherBoss.class,
                        new net.minecraft.world.phys.AABB(30, 140, 40, 50, 170, 60), e -> e.isAlive()).get(0)
                        .getBoundingBox().getCenter());
                // No second tp here: one to the same spot passes a position wait before it lands, and then resets
                // the look this case is about to set (seen 2026-10-11).
                c.waitUntil("the player on the platform", mc -> mc.player.position().distanceTo(
                        new Vec3(40.5, 151.0, 47.7)) < 0.1 && mc.player.onGround(), 200);
                c.note("goldor trial " + i + ": " + c.onClient(mc -> "player " + mc.player.position() + ", wither "
                        + mc.level.getEntitiesOfClass(net.minecraft.world.entity.boss.wither.WitherBoss.class,
                        new net.minecraft.world.phys.AABB(30, 140, 40, 50, 170, 60)).stream()
                        .map(w -> w.position() + (w.isAlive() ? "" : " dead")).toList() + ", floor under him "
                        + mc.level.getBlockState(mc.player.blockPosition().below())));
                t[i] = TriggerTiming.ticksToFire(c, at, "attack_entity", null, null, 60);
                if (t[i] < 0) {
                    c.note("goldor diag: " + c.onClient(mc -> "dungeonServer " + Mod.staticCall("cheatutils.CheatUtils",
                            "isOnDungeonServer", mc) + ", ip " + mc.getCurrentServer().ip + ", screen " + McCompat.screen(mc)
                            + ", bossActive " + Mod.staticCall("cheatutils.WitherEspFeature", "isWitherBossActive", mc)
                            + ", F7 " + Mod.staticCall("secrets.DungeonState", "isF7OrM7") + ", inBoss "
                            + Mod.staticCall("livemap.LiveMapFeature", "isInBoss") + ", phase "
                            + Mod.staticCall(TerminalAuraCases.TRACKER, "getPhase") + ", enabled "
                            + Mod.call(Mod.cfg(GT_CFG), "isEnabled") + ", aimTarget "
                            + Mod.field("goldor.GoldorTriggerbotFeature", "aimTargetId") + ", look "
                            + mc.player.getYRot() + "/" + mc.player.getXRot()));
                }
                TriggerTiming.lookAway(c);
                c.ctx().waitTicks(10);
            }
            verdict(c, "Goldor Triggerbot", t, delays[1]);
        } finally {
            c.server().command("kill @e[tag=pnggoldor]");
            c.server().command("fill 34 148 42 46 150 56 air");
            home(c);
        }
    }

    // ---- 788 --------------------------------------------------------------------------------------------------------

    private static final String AA_CFG = "arrowalign.ArrowAlignConfig";
    /** The mod's 7th solution (index 6): frames at these grid indices (dy + dz * 5), every one wanting rotation 1. */
    private static final int[] LAYOUT = {5, 7, 9, 10, 12, 14, 15, 17, 19};

    private static void arrowAlign(Session c) throws Exception {
        int[] delays = {0, 300};
        long[] t = new long[2];
        int[] targets = {7, 12};
        try (HxKit.Settings off = gateOff(c);
             HxKit.Settings on = new HxKit.Settings(c).with(AA_CFG, "SolverEnabled", true)
                     .with(AA_CFG, "PreventMisclicksEnabled", false).with(AA_CFG, "AuraEnabled", false)
                     .with(AA_CFG, "TriggerBotEnabled", true).with(AA_CFG, "TriggerBotDelayMs", 0)) {
            c.hx().sidebar("SKYBLOCK", "The Catac§combs §7(F7)");
            c.waitUntil("DungeonState F7", mc -> Boolean.TRUE.equals(Mod.staticCall("secrets.DungeonState", "isF7OrM7")), 100);
            // Loaded first: the console's fill refuses an unloaded area.
            c.hx().call("dungeon.tp", "x", 0.5, "y", 130.0, "z", 77.5, "yaw", 90f, "pitch", -90f);
            c.ctx().waitTicks(20);
            c.server().command("fill -1 119 72 3 119 82 stone");
            c.server().command("fill -3 119 74 -3 125 80 stone");
            c.waitUntil("the client to have the wall", mc -> mc.level.getBlockState(new BlockPos(-3, 122, 77))
                    .is(net.minecraft.world.level.block.Blocks.STONE), 200);
            for (int idx : LAYOUT) {
                int dy = idx % 5;
                int dz = idx / 5;
                c.hx().call("frame", "x", -2, "y", 120 + dy, "z", 75 + dz, "facing", "east", "item", "arrow");
            }
            c.hx().call("dungeon.tp", "x", 0.5, "y", 120.0, "z", 77.5, "yaw", 90f, "pitch", -90f);
            c.waitUntil("the client to have the frames", mc -> mc.level.getEntitiesOfClass(
                    net.minecraft.world.entity.decoration.ItemFrame.class,
                    new net.minecraft.world.phys.AABB(-4, 118, 73, 0, 126, 81)).size() == LAYOUT.length, 200);
            c.waitUntil("Arrow Align to read a solution", mc -> Mod.field(Mod.ROOT + "arrowalign.ArrowAlignFeature",
                    "solution") != null, 100);
            for (int i = 0; i < 2; i++) {
                int delay = delays[i];
                c.onClient(mc -> {
                    Mod.call(Mod.cfg(AA_CFG), "setTriggerBotDelayMs", delay);
                    return null;
                });
                TriggerTiming.lookAway(c);
                c.ctx().waitTicks(10);
                int idx = targets[i];
                Vec3 at = new Vec3(-1.97, 120 + idx % 5 + 0.5, 75 + idx / 5 + 0.5);
                int frameZ = 75 + idx / 5;
                int frameY = 120 + idx % 5;
                int frameId = c.onClient(mc -> {
                    for (var f : mc.level.getEntitiesOfClass(net.minecraft.world.entity.decoration.ItemFrame.class,
                            new net.minecraft.world.phys.AABB(-4, 118, 73, 0, 126, 81))) {
                        if (f.blockPosition().equals(new BlockPos(-2, frameY, frameZ))) {
                            return f.getId();
                        }
                    }
                    return -1;
                });
                c.check(frameId >= 0, "no arrow frame at grid index " + idx);
                t[i] = TriggerTiming.ticksToFire(c, at, "use_entity",
                        e -> e.get("entityId").getAsInt() == frameId, null, 60);
                if (t[i] < 0) {
                    String aa = Mod.ROOT + "arrowalign.ArrowAlignFeature";
                    c.note("arrow diag: " + c.onClient(mc -> "hit " + (mc.hitResult instanceof
                            net.minecraft.world.phys.EntityHitResult eh ? eh.getEntity() : mc.hitResult)
                            + ", eye " + mc.player.getEyePosition() + ", frame " + frameId + ", active "
                            + Mod.field(aa, "active") + ", solution " + java.util.Arrays.toString((int[]) Mod.field(aa, "solution"))
                            + ", observed " + java.util.Arrays.toString((int[]) Mod.field(aa, "observed"))
                            + ", aimIndex " + Mod.field(aa, "triggerAimIndex") + ", trigger on "
                            + Mod.call(Mod.cfg(AA_CFG), "isTriggerBotEnabled")));
                }
                TriggerTiming.lookAway(c);
                c.ctx().waitTicks(10);
            }
            verdict(c, "Arrow Align Trigger Bot", t, delays[1]);
        } finally {
            c.hx().removeStands();
            c.server().command("fill -3 119 72 3 125 82 air");
            home(c);
        }
    }
}
