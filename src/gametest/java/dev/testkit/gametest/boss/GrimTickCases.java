package dev.testkit.gametest.boss;

import com.google.gson.JsonObject;

import dev.testkit.gametest.hx.HxKit;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.hx.TriggerTiming;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;

/**
 * Mod branch grim-post (2026-10-11, killer560: "If anything ever flags grim find a way to fix it"): every automated
 * click that used to go out from a render frame now goes out at the START of the client tick, before the tick's
 * movement packet, which is where GrimAC's Post check wants it. 786-788 cover the three trigger bots; these cover the
 * other frame paths the sweep found, each proving the feature acted (the server's own packet record) before the
 * session's GrimAC verdict counts. Cheat jar only (none of these exists in a legit jar).
 * <ul>
 *   <li>831 Simon Says Auto Solve, ROTATE mode, facing the device: the camera turns every frame, the click waits for the
 *   tick. All four rounds to {@code ss.completed}.</li>
 *   <li>832 Arrow Align Aura: nine frames of layout 7, each one click from solved, at 16 CPS; every frame clicked once
 *   ({@code use_entity}). Then the Aura CPS setting (1-20, mod aura-cps) on a frame under the crosshair needing seven
 *   clicks, at 20 and 5 CPS: seven clicks each, at 17-24 and 4-6 CPS on the server's clock.</li>
 *   <li>833 / 834 Auto i4, Rotate / No Rotate: a bow on the i4 platform, targets lit by the server one after another;
 *   shots ({@code use_item}) must reach the server.</li>
 *   <li>835 Goldor Triggerbot rate: Use (right click) mode at 16 CPS for 60 ticks on a wither; must come out at 13-19 CPS (a flat
 *   tick-rounded interval would give 10).</li>
 * </ul>
 */
final class GrimTickCases {

    private static final String SS_CFG = SimonSaysCases.CONFIG;
    private static final String AA_CFG = "arrowalign.ArrowAlignConfig";
    private static final String I4_CFG = "i4sensors.I4SensorsConfig";
    private static final String GT_CFG = "goldor.GoldorTriggerbotConfig";
    /** Layout 7 of the mod's Arrow Align solutions (grid index dy + dz * 5), every frame wanting rotation 1. */
    private static final int[] LAYOUT = {5, 7, 9, 10, 12, 14, 15, 17, 19};

    private GrimTickCases() {
    }

    static void register(Session s) {
        if (!Mod.isCheat()) {
            return;
        }
        s.test("831-boss-grim-ss-autosolve-rotate", GrimTickCases::simonRotate);
        s.test("832-boss-grim-arrowalign-aura", GrimTickCases::arrowAura);
        s.test("833-boss-grim-autoi4-rotate", c -> autoI4(c, true));
        s.test("834-boss-grim-autoi4-norotate", c -> autoI4(c, false));
        s.test("835-boss-grim-goldor-cps", GrimTickCases::goldorRate);
    }

    private static HxKit.Settings gateOff(Session c) {
        boolean gateWas = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled"));
        return new HxKit.Settings(c).custom(() -> Mod.staticCall("util.SkyblockGate", "setEnabled", false),
                () -> Mod.staticCall("util.SkyblockGate", "setEnabled", gateWas));
    }

    private static HxKit.Settings hypixelIp(Session c) {
        String ipWas = c.onClient(mc -> mc.getCurrentServer().ip);
        return new HxKit.Settings(c).custom(
                () -> net.minecraft.client.Minecraft.getInstance().getCurrentServer().ip = "mc.hypixel.net",
                () -> net.minecraft.client.Minecraft.getInstance().getCurrentServer().ip = ipWas);
    }

    private static void home(Session c) {
        c.hx().sidebarClear();
        c.hx().call("dungeon.tp", "x", -619.5, "y", 151.0, "z", -619.5, "yaw", 0f, "pitch", 0f);
    }

    private static double cps(List<JsonObject> events) {
        if (events.size() < 2) {
            return 0.0;
        }
        long first = events.get(0).get("tick").getAsLong();
        long last = events.get(events.size() - 1).get("tick").getAsLong();
        return last == first ? 0.0 : (events.size() - 1) * 20.0 / (last - first);
    }

    // ---- 831 --------------------------------------------------------------------------------------------------------

    private static void simonRotate(Session c) throws Exception {
        try (HxKit.Settings off = gateOff(c);
             HxKit.Settings on = new HxKit.Settings(c).with(SS_CFG, "SolverEnabled", true)
                     .with(SS_CFG, "AutoRestartEnabled", false).with(SS_CFG, "AutoStartEnabled", false)
                     .with(SS_CFG, "TriggerBotEnabled", false).with(SS_CFG, "AutoSolveRotate", true)
                     .with(SS_CFG, "AutoSolveEnabled", false)) {
            c.hx().sidebar("SKYBLOCK", "The Catac§combs §7(F7)");
            c.waitUntil("DungeonState F7", mc -> "F7".equals(Mod.staticCall("secrets.DungeonState", "getFloor")), 100);
            c.onClient(mc -> {
                Mod.set(SS_CFG, "setEnabled", false);
                return null;
            });
            c.hx().call("boss.ss.build", "rounds", 4, "line", true, "seed", 41);
            c.hx().call("dungeon.tp", "x", 107.5, "y", 120.0, "z", 93.5, "yaw", -90f, "pitch", 0f);
            c.waitUntil("the client to have the device", mc ->
                    mc.level.getBlockState(new BlockPos(111, 120, 92)).is(net.minecraft.world.level.block.Blocks.OBSIDIAN)
                            && mc.level.getBlockState(SimonSaysCases.START).is(
                            net.minecraft.world.level.block.Blocks.STONE_BUTTON), 200);
            c.ctx().waitTicks(5);
            c.onClient(mc -> {
                Mod.set(SS_CFG, "setEnabled", true);
                return null;
            });
            c.waitUntil("Simon Says at the device", mc -> (Boolean) Mod.field(SimonSaysCases.FEATURE, "wasActive"), 200);
            c.ctx().waitTicks(5);
            long since = c.hx().head();
            SimonSaysCases.click(c, SimonSaysCases.START);
            c.waitUntil("the device to start (Hx)", mc -> !"IDLE".equals(SimonSaysCases.state(c).get("phase").getAsString()), 60);
            // Auto Solve only now: with Rotate on, its idle look turns the camera off the start button during the test's
            // own turn-then-click, and the test's press then drew RotationPlace (this case's first two runs).
            c.onClient(mc -> {
                Mod.set(SS_CFG, "setAutoSolveEnabled", true);
                return null;
            });
            boolean done = false;
            try {
                c.waitUntil("ss.completed (Hx) with Auto Solve (Rotate) playing", mc -> !c.events("ss.completed").isEmpty(), 1600);
                done = true;
            } finally {
                int uses = c.hx().events(since, "use_block").size();
                c.note(String.format(Locale.ROOT, "rotate auto solve: completed %s, server use_block %d (1 by the test), "
                        + "presses %d, breaks %d, state %s", done, uses, c.events("ss.press").size(),
                        c.events("ss.broke").size(), SimonSaysCases.state(c)));
            }
            int uses = c.hx().events(since, "use_block").size();
            // 1 + 2 + 3 + 4 grid presses, plus the test's own start press.
            c.check(uses >= 11, "only " + uses + " use_block packets reached the server for a 4-round device");
            c.check(c.events("ss.broke").isEmpty(), "the device broke: " + c.events("ss.broke"));
        } finally {
            c.onClient(mc -> {
                Mod.set(SS_CFG, "setEnabled", false);
                return null;
            });
            c.hx().call("boss.ss.clear");
            home(c);
        }
    }

    // ---- 832 --------------------------------------------------------------------------------------------------------

    private static void arrowAura(Session c) throws Exception {
        try (HxKit.Settings off = gateOff(c);
             HxKit.Settings on = new HxKit.Settings(c).with(AA_CFG, "SolverEnabled", true)
                     .with(AA_CFG, "PreventMisclicksEnabled", false).with(AA_CFG, "TriggerBotEnabled", false)
                     .with(AA_CFG, "AuraEnabled", false).with(AA_CFG, "AuraRange", 3.0).with(AA_CFG, "AuraCps", 16)) {
            c.hx().sidebar("SKYBLOCK", "The Catac§combs §7(F7)");
            c.waitUntil("DungeonState F7", mc -> Boolean.TRUE.equals(Mod.staticCall("secrets.DungeonState", "isF7OrM7")), 100);
            // Built before he is put there: a tp above it and a wait had him falling as the floor appeared, and GrimAC
            // said Simulation twice when this case ran first in a session (nt-arrow-aura, 2026-10-12).
            c.server().command("forceload add -3 72 3 82");
            c.server().sync(20);
            c.server().command("fill -1 119 72 3 119 82 stone");
            c.server().command("fill -3 119 74 -3 125 80 stone");
            // Close enough that every frame's box is inside 3.0 of the eye (the top row is 2.86 from here).
            c.hx().call("dungeon.tp", "x", -0.7, "y", 120.0, "z", 77.5, "yaw", 90f, "pitch", 0f);
            c.waitUntil("standing by the wall", mc -> mc.player.onGround() && Math.abs(mc.player.getY() - 120.0) < 0.01
                    && mc.level.getBlockState(new BlockPos(-3, 122, 77)).is(net.minecraft.world.level.block.Blocks.STONE), 200);

            // Sweep: nine frames, one click each, none on the look (facing level between rows), 16 CPS. Every click
            // needs a body turn first, so the rate is bound by turn + click = 2 ticks, not by the setting.
            grid(c, -1);
            long since = c.hx().head();
            c.note("Aura on (anticheat lines so far: " + c.scenario().flags().size() + ")");
            aura(c, true);
            c.waitUntil("every frame clicked once (server use_entity)",
                    mc -> c.hx().events(since, "use_entity").size() >= LAYOUT.length, 200);
            c.ctx().waitTicks(20); // nothing more: each frame wanted exactly one click
            aura(c, false);
            List<JsonObject> clicks = c.hx().events(since, "use_entity");
            c.note(String.format(Locale.ROOT, "arrow aura sweep at 16 CPS: %d use_entity, %.1f CPS (a turn before every "
                    + "frame)", clicks.size(), cps(clicks)));
            c.check(clicks.size() == LAYOUT.length, "the Aura sent " + clicks.size() + " clicks for "
                    + LAYOUT.length + " frames that each needed one");

            // On the look (killer560 2026-10-12, Aura CPS 1-20): the centre frame starts at rotation 2, so it needs seven
            // clicks, and he looks straight at it; the Aura takes it first with no turn. Its clicks alone are timed.
            for (int want : new int[]{20, 5}) {
                int centre = grid(c, 12)[0];
                Vec3 at = new Vec3(-1.97, 122.5, 77.5);
                TriggerTiming.look(c, at);
                c.ctx().waitTicks(3);
                c.onClient(mc -> {
                    Mod.set(AA_CFG, "setAuraCps", want);
                    return null;
                });
                long mark = c.hx().head();
                aura(c, true);
                c.waitUntil("seven clicks on the centre frame at " + want + " CPS", mc -> onFrame(c, mark, centre).size() >= 7,
                        200);
                aura(c, false);
                List<JsonObject> mine = onFrame(c, mark, centre);
                double rate = cps(mine);
                c.note(String.format(Locale.ROOT, "arrow aura on the look at %d CPS: %d clicks on the centre frame, %.1f CPS "
                        + "(ticks %s)", want, mine.size(), rate, mine.stream().map(e -> e.get("tick").getAsString()).toList()));
                c.check(mine.size() == 7, "the centre frame got " + mine.size() + " clicks, it needed 7");
                double lo = want == 20 ? 17.0 : want * 0.8;
                double hi = want * 1.2;
                c.check(rate >= lo && rate <= hi, String.format(Locale.ROOT,
                        "Aura CPS %d came out at %.1f on a frame under the crosshair", want, rate));
            }
        } finally {
            c.hx().removeStands();
            c.server().command("kill @e[type=item_frame,x=-2,y=122,z=77,distance=..6]");
            c.server().command("fill -3 119 72 3 125 82 air");
            c.server().command("forceload remove -3 72 3 82");
            home(c);
        }
    }

    private static void aura(Session c, boolean on) {
        c.onClient(mc -> {
            Mod.set(AA_CFG, "setAuraEnabled", on);
            return null;
        });
    }

    private static List<JsonObject> onFrame(Session c, long since, int frameId) {
        return c.hx().events(since, "use_entity").stream().filter(e -> e.get("entityId").getAsInt() == frameId).toList();
    }

    /**
     * Layout 7 afresh (old frames removed), each frame one click from solved; {@code seven} (a grid index, or -1) starts
     * at rotation 2 instead, seven clicks from solved. Returns that frame's entity id (or -1) once the mod reads the grid.
     */
    private static int[] grid(Session c, int seven) {
        // Hx removes its own frames; a console kill here could land AFTER the new frames were spawned and kill them
        // (this case's first aura-cps run: "the client to have the frames" never came).
        c.hx().removeStands();
        AABB box = new AABB(-4, 118, 73, 0, 126, 81);
        c.waitUntil("the old frames gone", mc -> mc.level.getEntitiesOfClass(
                net.minecraft.world.entity.decoration.ItemFrame.class, box).isEmpty(), 100);
        int id = -1;
        for (int idx : LAYOUT) {
            JsonObject f = c.hx().call("frame", "x", -2, "y", 120 + idx % 5, "z", 75 + idx / 5, "facing", "east",
                    "item", "arrow", "rotation", idx == seven ? 2 : 0).getAsJsonObject();
            if (idx == seven) {
                id = f.get("id").getAsInt();
            }
        }
        c.waitUntil("the client to have the frames", mc -> mc.level.getEntitiesOfClass(
                net.minecraft.world.entity.decoration.ItemFrame.class, box).size() == LAYOUT.length, 200);
        c.waitUntil("Arrow Align to read a solution", mc -> Mod.field(Mod.ROOT + "arrowalign.ArrowAlignFeature",
                "solution") != null, 100);
        c.ctx().waitTicks(3); // the frames' rotations read in
        return new int[]{id};
    }

    // ---- 833 / 834 --------------------------------------------------------------------------------------------------

    private static final BlockPos[] I4_TARGETS = {new BlockPos(66, 128, 50), new BlockPos(64, 126, 50),
            new BlockPos(68, 130, 50)};

    private static void autoI4(Session c, boolean rotate) throws Exception {
        try (HxKit.Settings off = gateOff(c);
             HxKit.Settings ip = hypixelIp(c);
             HxKit.Settings on = new HxKit.Settings(c).with(I4_CFG, "AutoSwapToBow", false).with(I4_CFG, "AutoMask", false)
                     .with(I4_CFG, "AutoI4Predictions", false).with(I4_CFG, "ShotAccuracyPercent", 100)
                     .with(I4_CFG, "AutoI4Rotate", rotate).with(I4_CFG, "AutoI4RotationTimeMs", 150)
                     .custom(() -> Mod.call(Mod.cfg(I4_CFG), "setCpsRange", 7, 7),
                             restoreCps(c))
                     .with(I4_CFG, "AutoI4Enabled", false)) {
            // Built before he is put there (forceloaded, as 421 does): a tp above it and a 20-tick wait had him falling
            // while the floor appeared, and GrimAC said AntiKB + Simulation (this case's second run).
            c.server().command("forceload add 60 32 70 51");
            c.server().sync(20);
            c.server().command("fill 60 126 32 67 126 39 stone");
            c.server().command("fill 62 124 51 70 132 51 stone");
            for (int x = 64; x <= 68; x += 2) {
                for (int y = 126; y <= 130; y += 2) {
                    c.server().command("setblock " + x + " " + y + " 50 blue_terracotta");
                }
            }
            c.server().command("item replace entity @a hotbar.0 with bow");
            c.server().command("give @a arrow 64");
            c.hx().call("dungeon.tp", "x", 63.5, "y", 127.0, "z", 35.5, "yaw", 0f, "pitch", 0f);
            c.waitUntil("on the i4 platform with a bow", mc -> mc.player.onGround()
                    && Math.abs(mc.player.getY() - 127.0) < 0.01 && !mc.level.getBlockState(I4_TARGETS[0]).isAir() // the wall (no colour-block constant: 26.2 has none)
                    && mc.player.getInventory().getItem(0).is(net.minecraft.world.item.Items.BOW), 200);
            c.onClient(mc -> {
                mc.player.getInventory().setSelectedSlot(0);
                Mod.set(I4_CFG, "setAutoI4Enabled", true);
                return null;
            });
            c.ctx().waitTicks(10);
            long since = c.hx().head();
            int[] perTarget = new int[I4_TARGETS.length];
            for (int i = 0; i < I4_TARGETS.length; i++) {
                BlockPos t = I4_TARGETS[i];
                long mark = c.hx().head();
                c.server().command("setblock " + t.getX() + " " + t.getY() + " " + t.getZ() + " emerald_block");
                c.ctx().waitTicks(30);
                perTarget[i] = c.hx().events(mark, "use_item").size();
                c.server().command("setblock " + t.getX() + " " + t.getY() + " " + t.getZ() + " blue_terracotta");
                c.ctx().waitTicks(5);
            }
            c.onClient(mc -> {
                Mod.set(I4_CFG, "setAutoI4Enabled", false);
                return null;
            });
            List<JsonObject> shots = c.hx().events(since, "use_item");
            c.note(String.format(Locale.ROOT, "auto i4 %s: %d use_item (per target %s), %.1f CPS (7 set)",
                    rotate ? "rotate" : "no rotate", shots.size(), java.util.Arrays.toString(perTarget), cps(shots)));
            for (int i = 0; i < perTarget.length; i++) {
                c.check(perTarget[i] > 0, "no shot reached the server while target " + I4_TARGETS[i] + " was lit");
            }
        } finally {
            c.onClient(mc -> {
                Mod.set(I4_CFG, "setAutoI4Enabled", false);
                return null;
            });
            c.server().command("kill @e[type=arrow]");
            c.server().command("clear @a");
            c.server().command("fill 60 124 32 70 132 51 air");
            c.server().command("forceload remove 60 32 70 51");
            home(c);
        }
    }

    private static Runnable restoreCps(Session c) {
        int[] was = c.onClient(mc -> new int[]{(Integer) Mod.call(Mod.cfg(I4_CFG), "getCpsMin"),
                (Integer) Mod.call(Mod.cfg(I4_CFG), "getCpsMax")});
        return () -> Mod.call(Mod.cfg(I4_CFG), "setCpsRange", was[0], was[1]);
    }

    // ---- 835 --------------------------------------------------------------------------------------------------------

    private static void goldorRate(Session c) throws Exception {
        Object shoot = c.onClient(mc -> Mod.enumValue(Mod.ROOT + GT_CFG + "$ClickType", "USE"));
        int[] cpsWas = c.onClient(mc -> new int[]{(Integer) Mod.call(Mod.cfg(GT_CFG), "getCpsMin"),
                (Integer) Mod.call(Mod.cfg(GT_CFG), "getCpsMax")});
        try (HxKit.Settings off = gateOff(c);
             HxKit.Settings ip = hypixelIp(c);
             HxKit.Settings on = new HxKit.Settings(c).field(GT_CFG, "clickType", shoot)
                     .custom(() -> Mod.call(Mod.cfg(GT_CFG), "setCpsRange", 16, 16),
                             () -> Mod.call(Mod.cfg(GT_CFG), "setCpsRange", cpsWas[0], cpsWas[1]))
                     .with(GT_CFG, "AimDelayMs", 0).with(GT_CFG, "Enabled", false)) {
            // Something in the hand: Hypixel's Use mode is a Terminator shot, and an empty hand's use is not recorded.
            c.server().command("item replace entity @a hotbar.0 with stick");
            c.server().command("forceload add 34 42 46 56");
            c.server().sync(20);
            c.server().command("fill 34 148 42 46 150 56 stone");
            c.hx().call("dungeon.tp", "x", 40.5, "y", 151.0, "z", 47.7, "yaw", 0f, "pitch", -90f);
            c.waitUntil("the client to have the platform", mc -> mc.level.getBlockState(new BlockPos(40, 150, 47))
                    .is(net.minecraft.world.level.block.Blocks.STONE), 200);
            c.waitUntil("the player on the platform", mc -> mc.player.position().distanceTo(new Vec3(40.5, 151.0, 47.7))
                    < 0.1 && mc.player.onGround(), 200);
            c.hx().sidebar("SKYBLOCK", "The Catac§combs §7(F7)");
            c.waitUntil("DungeonState F7", mc -> Boolean.TRUE.equals(Mod.staticCall("secrets.DungeonState", "isF7OrM7")), 100);
            c.hx().chat(TerminalAuraCases.GOLDOR);
            c.waitUntil("Floor7Tracker phase P3",
                    mc -> "P3".equals(String.valueOf(Mod.staticCall(TerminalAuraCases.TRACKER, "getPhase"))), 100);
            c.server().command("kill @e[tag=pnggoldor]");
            c.server().command("summon wither 40.5 151 54.5 {Tags:[\"pnggoldor\"],NoAI:1b,Silent:1b,Invul:0,"
                    + "PersistenceRequired:1b,CustomName:\"Goldor\"}");
            AABB box = new AABB(30, 140, 40, 50, 170, 60);
            c.waitUntil("the client to have the wither", mc -> mc.level.getEntitiesOfClass(
                    net.minecraft.world.entity.boss.wither.WitherBoss.class, box, e -> e.isAlive()).size() == 1, 200);
            Vec3 at = c.onClient(mc -> mc.level.getEntitiesOfClass(
                    net.minecraft.world.entity.boss.wither.WitherBoss.class, box, e -> e.isAlive()).get(0)
                    .getBoundingBox().getCenter());
            TriggerTiming.look(c, at);
            c.ctx().waitTicks(3);
            long since = c.hx().head();
            c.onClient(mc -> {
                Mod.set(GT_CFG, "setEnabled", true);
                return null;
            });
            c.ctx().waitTicks(60);
            c.onClient(mc -> {
                Mod.set(GT_CFG, "setEnabled", false);
                return null;
            });
            List<JsonObject> shots = c.hx().events(since, "use_item");
            double rate = cps(shots);
            c.note(String.format(Locale.ROOT, "goldor use 16 CPS: %d use_item over ~60 ticks, %.1f CPS", shots.size(), rate));
            if (shots.isEmpty()) {
                c.note("goldor diag: " + c.onClient(mc -> "aimTarget " + Mod.field("goldor.GoldorTriggerbotFeature", "aimTargetId")
                        + ", due " + Mod.field("goldor.GoldorTriggerbotFeature", "nextClickDueTick") + ", tick "
                        + Mod.staticCall("util.ActionGate", "currentTick") + ", held " + mc.player.getMainHandItem()
                        + ", look " + mc.player.getYRot() + "/" + mc.player.getXRot()));
            }
            c.check(shots.size() >= 20, "only " + shots.size() + " shots in 60 ticks at 16 CPS");
            c.check(rate >= 13.0 && rate <= 19.0, String.format(Locale.ROOT,
                    "16 CPS came out at %.1f - the rate was not kept on the tick", rate));
        } finally {
            c.server().command("kill @e[tag=pnggoldor]");
            c.server().command("fill 34 148 42 46 150 56 air");
            c.server().command("forceload remove 34 42 46 56");
            c.server().command("clear @a");
            home(c);
        }
    }
}
