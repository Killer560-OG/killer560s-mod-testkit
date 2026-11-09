package dev.testkit.gametest.boss;

import com.google.gson.JsonObject;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.LeverBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Boss auras whose target the player may not be looking at (mod aura-turn, 2026-10-07): GrimAC flags a block use the
 * reported look misses as RotationPlace and drops it (419, and SimonSaysCases' own first run), and an entity interact
 * as Hitboxes (415/418). Cheat jar only; every case proves the feature acted (the server's own record) before it
 * judges anything, and checks the camera never moved and the body was given back.
 * <ul>
 *   <li>421 Lever Aura: the S2 section lever (27,124,127), "Not Activated" stand above it, directly BEHIND him.</li>
 *   <li>422 Simon Says Auto Solve, No Rotate: the test presses start, then turns his back to the device (server tp,
 *       yaw 90); Auto Solve must play all four rounds to "completed" with GrimAC silent.</li>
 *   <li>423 the same facing the device: the buttons are in front, but only one is ever under the crosshair, so each
 *       of the others is a use the look misses just the same.</li>
 * </ul>
 */
final class BossAuraTurnCases {

    static final String LEVER_CONFIG = "leveraura.LeverAuraConfig";
    static final BlockPos LEVER = new BlockPos(27, 124, 127);

    private BossAuraTurnCases() {
    }

    static void register(Session s) {
        if (!Mod.isCheat()) {
            return;
        }
        s.test("421-boss-leveraura-behind", BossAuraTurnCases::leverBehind);
        s.test("422-boss-ss-autosolve-norotate-behind", c -> simonAuto(c, 90f, 21));
        s.test("423-boss-ss-autosolve-norotate-front", c -> simonAuto(c, -90f, 22));
        s.test("1109-boss-ss-auto-only", c -> simonAuto(c, -90f, 23, true));
        s.test("1109-boss-lever-cooldown", BossAuraTurnCases::leverCooldown);
        s.test("1109-boss-lever-cps-migrates", BossAuraTurnCases::leverMigrate);
    }

    // ---------------------------------------------------------------------------------------------------- Lever Aura

    static void leverBehind(Session c) throws Exception {
        List<AutoCloseable> undo = new ArrayList<>();
        try {
            // Loaded first: a fill into an unloaded chunk does nothing, and he then fell through a floor that was never
            // built (the first runs' Simulation lines, and once 75 blocks of falling).
            c.server().command("forceload add 21 123 30 131");
            c.server().sync(20);
            c.server().command("fill 21 123 123 30 123 131 minecraft:stone");
            c.server().command("fill 21 124 123 30 129 131 minecraft:air");
            c.server().command("setblock 28 124 127 minecraft:stone");
            c.server().command("setblock 27 124 127 minecraft:lever[face=wall,facing=west,powered=false]");
            c.ctx().waitTicks(10); // the floor reaches the client before he is put on it
            TerminalAuraCases.tp(c, 25.5, 124, 127.5, 90f); // facing west (-x); the lever is 1.5 blocks east, behind
            c.ctx().waitTicks(20);
            c.check(c.onClient(mc -> mc.player.onGround() && Math.abs(mc.player.getY() - 124) < 0.01),
                    "not standing on the arena floor: " + c.onClient(mc -> mc.player.position()));
            // The stand only once he is there: an entity added to a chunk the server is not ticking entities in never
            // reaches the client (testkit CLAUDE.md).
            c.server().command("kill @e[type=armor_stand,x=27,y=125,z=127,distance=..3]");
            c.server().command("summon armor_stand 27.5 125 127.5 {NoGravity:1b,Invisible:1b,CustomName:\"Not Activated\",CustomNameVisible:1b}");
            c.hx().sidebar("SKYBLOCK", "The Catac§combs §7(F7)");
            c.waitUntil("DungeonState F7", mc -> Boolean.TRUE.equals(Mod.staticCall("secrets.DungeonState", "isF7OrM7")), 100);
            c.hx().chat(TerminalAuraCases.GOLDOR);
            c.waitUntil("Floor7Tracker phase P3",
                    mc -> "P3".equals(String.valueOf(Mod.staticCall(TerminalAuraCases.TRACKER, "getPhase"))), 100);
            try {
                c.waitUntil("the lever and its stand on the client", mc -> mc.level.getBlockState(LEVER).getBlock()
                        instanceof LeverBlock && !mc.level.getEntitiesOfClass(net.minecraft.world.entity.decoration.ArmorStand.class,
                        new net.minecraft.world.phys.AABB(LEVER.above()).inflate(1.5)).isEmpty(), 100);
            } catch (AssertionError e) {
                c.note("client sees at the lever: " + c.onClient(mc -> mc.level.getBlockState(LEVER)) + ", stands near: "
                        + c.onClient(mc -> mc.level.getEntitiesOfClass(net.minecraft.world.entity.decoration.ArmorStand.class,
                        new net.minecraft.world.phys.AABB(LEVER).inflate(4)).stream().map(st -> st.position().toString()).toList())
                        + ", player " + c.onClient(mc -> mc.player.position()));
                throw e;
            }
            c.note("stand names: " + c.onClient(mc -> mc.level.getEntitiesOfClass(net.minecraft.world.entity.decoration.ArmorStand.class,
                    new net.minecraft.world.phys.AABB(LEVER.above()).inflate(1.5)).stream().map(e -> e.getDisplayName().getString()).toList()));
            c.note("gates: phaseAt=" + c.onClient(mc -> String.valueOf(Mod.staticCall(TerminalAuraCases.TRACKER, "getPhaseAt")))
                    + " inF7Boss=" + c.onClient(mc -> String.valueOf(Mod.staticCall(TerminalAuraCases.TRACKER, "inF7Boss"))));
            // The Lights levers are the Lights Device's own feature since mod lights-aura-1015; it stays off here.
            undo.add(c.onClient(mc -> LightsDeviceCases.raw(LightsDeviceCases.CFG, "AuraEnabled", false)));
            undo.add(c.onClient(mc -> LightsDeviceCases.raw(LightsDeviceCases.CFG, "TriggerEnabled", false)));
            undo.add(c.onClient(mc -> Mod.with(LEVER_CONFIG, "SectionLevers", true)));
            undo.add(c.onClient(mc -> Mod.with(LEVER_CONFIG, "SectionLeversEarly", true)));
            undo.add(c.onClient(mc -> Mod.with(LEVER_CONFIG, "Range", 4.5)));
            // Lever Aura cooldown (2026-11-09; was the CPS slider): 50 ms is one flick a tick.
            undo.add(c.onClient(mc -> Mod.with(LEVER_CONFIG, "CooldownMs", 50)));
            c.note("anticheat lines before Lever Aura is switched on: " + c.scenario().flags().size()
                    + ", on ground " + c.onClient(mc -> mc.player.onGround()) + " at " + c.onClient(mc -> mc.player.position()));
            float[] cam = start(c);
            undo.add(c.onClient(mc -> Mod.with(LEVER_CONFIG, "Enabled", true)));
            c.waitUntil("the lever flipped on the server's say-so", mc -> {
                track(mc, cam);
                return mc.level.getBlockState(LEVER).getValue(LeverBlock.POWERED);
            }, 60);
            c.onClient(mc -> {
                Mod.set(LEVER_CONFIG, "setEnabled", false);
                return null;
            });
            finish(c, cam, "lever aura behind");
        } finally {
            c.onClient(mc -> {
                restore(undo);
                return null;
            });
            c.server().command("kill @e[type=armor_stand,x=27,y=125,z=127,distance=..3]");
            c.server().command("fill 21 123 123 30 129 131 minecraft:air");
            c.server().command("forceload remove 21 123 30 131");
            c.hx().sidebarClear();
            c.hx().call("dungeon.tp", "x", -619.5, "y", 151.0, "z", -619.5, "yaw", 0f, "pitch", 0f);
        }
    }

    /**
     * Lever Cooldown (2026-11-09): with the cooldown at 800 ms, the lever flicked right after the first one waits out the cooldown
     * (+-10%): lever 1 (27,124,127) is flicked, he is put beside lever 2 (23,132,138) at once, and the second flick must come
     * about 0.9 * 800 / 50 = 14.4 ticks (less the ticks the turn to lever 1 took) after the first (and not much later), with GrimAC silent.
     */
    static void leverCooldown(Session c) throws Exception {
        List<AutoCloseable> undo = new ArrayList<>();
        BlockPos lever2 = new BlockPos(23, 132, 138);
        try {
            c.server().command("forceload add 18 123 30 142");
            c.server().sync(20);
            c.server().command("fill 21 123 123 30 123 131 minecraft:stone");
            c.server().command("fill 21 124 123 30 129 131 minecraft:air");
            c.server().command("fill 18 131 134 26 131 142 minecraft:stone");
            c.server().command("fill 18 132 134 26 136 142 minecraft:air");
            c.server().command("setblock 28 124 127 minecraft:stone");
            c.server().command("setblock 27 124 127 minecraft:lever[face=wall,facing=west,powered=false]");
            c.server().command("setblock 24 132 138 minecraft:stone");
            c.server().command("setblock 23 132 138 minecraft:lever[face=wall,facing=west,powered=false]");
            c.ctx().waitTicks(10);
            TerminalAuraCases.tp(c, 25.5, 124, 127.5, 90f);
            c.ctx().waitTicks(20);
            c.server().command("kill @e[type=armor_stand,x=27,y=125,z=127,distance=..3]");
            c.server().command("summon armor_stand 27.5 125 127.5 {NoGravity:1b,Invisible:1b,CustomName:\"Not Activated\",CustomNameVisible:1b}");
            c.server().command("summon armor_stand 23.5 133 138.5 {NoGravity:1b,Invisible:1b,CustomName:\"Not Activated\",CustomNameVisible:1b}");
            c.hx().sidebar("SKYBLOCK", "The Catac\u00a7combs \u00a77(F7)");
            c.waitUntil("DungeonState F7", mc -> Boolean.TRUE.equals(Mod.staticCall("secrets.DungeonState", "isF7OrM7")), 100);
            c.hx().chat(TerminalAuraCases.GOLDOR);
            c.waitUntil("Floor7Tracker phase P3",
                    mc -> "P3".equals(String.valueOf(Mod.staticCall(TerminalAuraCases.TRACKER, "getPhase"))), 100);
            c.waitUntil("both levers and their stands on the client", mc -> mc.level.getBlockState(LEVER).getBlock() instanceof LeverBlock
                    && mc.level.getBlockState(lever2).getBlock() instanceof LeverBlock
                    && mc.level.getEntitiesOfClass(net.minecraft.world.entity.decoration.ArmorStand.class,
                    new net.minecraft.world.phys.AABB(lever2.above()).inflate(1.5)).size() > 0, 100);
            undo.add(c.onClient(mc -> LightsDeviceCases.raw(LightsDeviceCases.CFG, "AuraEnabled", false)));
            undo.add(c.onClient(mc -> LightsDeviceCases.raw(LightsDeviceCases.CFG, "TriggerEnabled", false)));
            undo.add(c.onClient(mc -> Mod.with(LEVER_CONFIG, "SectionLevers", true)));
            undo.add(c.onClient(mc -> Mod.with(LEVER_CONFIG, "SectionLeversEarly", true)));
            undo.add(c.onClient(mc -> Mod.with(LEVER_CONFIG, "Range", 4.5)));
            undo.add(c.onClient(mc -> Mod.with(LEVER_CONFIG, "CooldownMs", 800)));
            int flags0 = c.scenario().flags().size();
            undo.add(c.onClient(mc -> Mod.with(LEVER_CONFIG, "Enabled", true)));
            int t = 0;
            while (t < 120 && !c.onClient(mc -> mc.level.getBlockState(LEVER).getValue(LeverBlock.POWERED))) {
                c.ctx().waitTicks(1);
                t++;
            }
            c.check(t < 120, "lever 1 was never flicked (the feature did not act)");
            TerminalAuraCases.tp(c, 21.5, 132, 138.5, 90f);
            int t1 = 0;
            while (t1 < 200 && !c.onClient(mc -> mc.level.getBlockState(lever2).getValue(LeverBlock.POWERED))) {
                c.ctx().waitTicks(1);
                t1++;
            }
            c.note("lever 2 flicked " + t1 + " ticks after lever 1 was seen flicked (he was beside it within ~2); anticheat lines "
                    + (c.scenario().flags().size() - flags0));
            c.check(t1 < 200, "lever 2 was never flicked");
            // The cooldown counts from the tick the turn to lever 1 began (a few ticks before its flick), so 14.4 - turn ticks; 50 ms would give 3-5.
            c.check(t1 >= 9, "lever 2 went " + t1 + " ticks after lever 1, inside the 800 ms cooldown (14.4 ticks at -10%, less the turn)");
            c.check(t1 <= 45, "lever 2 waited " + t1 + " ticks, far past the 800 ms cooldown");
            c.check(c.scenario().flags().size() == flags0, "GrimAC flagged during the levers");
        } finally {
            c.onClient(mc -> {
                restore(undo);
                return null;
            });
            c.server().command("kill @e[type=armor_stand,x=27,y=125,z=127,distance=..3]");
            c.server().command("kill @e[type=armor_stand,x=23,y=133,z=138,distance=..3]");
            c.server().command("fill 21 123 123 30 129 131 minecraft:air");
            c.server().command("fill 18 131 134 26 136 142 minecraft:air");
            c.server().command("forceload remove 18 123 30 142");
            c.hx().sidebarClear();
            c.hx().call("dungeon.tp", "x", -619.5, "y", 151.0, "z", -619.5, "yaw", 0f, "pitch", 0f);
        }
    }

    /** An old Lever Aura file with a "cps" (no "cooldownMs") loads as a cooldown of 1000 / cps ms, clamped to 50..1000. */
    static void leverMigrate(Session c) throws Exception {
        java.nio.file.Path file = c.onClient(mc -> (java.nio.file.Path) Mod.field(LEVER_CONFIG, "CONFIG_PATH"));
        byte[] old = java.nio.file.Files.exists(file) ? java.nio.file.Files.readAllBytes(file) : null;
        try {
            int[][] cases = {{4, 250}, {10, 100}, {20, 50}, {2, 500}, {1, 1000}, {50, 50}};
            for (int[] cs : cases) {
                java.nio.file.Files.createDirectories(file.getParent());
                java.nio.file.Files.writeString(file, "{\"cps\": " + cs[0] + "}");
                c.onClient(mc -> Mod.staticCall(LEVER_CONFIG, "load"));
                int got = c.onClient(mc -> (Integer) Mod.call(Mod.cfg(LEVER_CONFIG), "getCooldownMs"));
                c.note("old cps " + cs[0] + " -> cooldown " + got + " ms (expected " + cs[1] + ")");
                c.check(got == cs[1], "cps " + cs[0] + " migrated to " + got + " ms, expected " + cs[1]);
            }
            java.nio.file.Files.writeString(file, "{\"cps\": 4, \"cooldownMs\": 300}");
            c.onClient(mc -> Mod.staticCall(LEVER_CONFIG, "load"));
            int kept = c.onClient(mc -> (Integer) Mod.call(Mod.cfg(LEVER_CONFIG), "getCooldownMs"));
            c.check(kept == 300, "a file with cooldownMs 300 loaded as " + kept);
        } finally {
            if (old != null) {
                java.nio.file.Files.write(file, old);
            } else {
                java.nio.file.Files.deleteIfExists(file);
            }
            c.onClient(mc -> Mod.staticCall(LEVER_CONFIG, "load"));
        }
    }

    // ---------------------------------------------------------------------------------------------------- Simon Says

    static void simonAuto(Session c, float yaw, int seed) throws Exception {
        simonAuto(c, yaw, seed, false);
    }

    /** {@code only}: Auto Simon Says alone (2026-11-09): the solver, number overlay, party tracker and the Simon Says switch OFF. */
    static void simonAuto(Session c, float yaw, int seed, boolean only) throws Exception {
        String cfg = SimonSaysCases.CONFIG;
        List<AutoCloseable> undo = new ArrayList<>();
        try {
            c.hx().sidebar("SKYBLOCK", "The Catac§combs §7(F7)");
            c.waitUntil("DungeonState.getFloor()==F7",
                    mc -> "F7".equals(Mod.staticCall("secrets.DungeonState", "getFloor")), 100);
            undo.add(c.onClient(mc -> Mod.with(cfg, "SolverEnabled", !only)));
            undo.add(c.onClient(mc -> Mod.with(cfg, "NumberOverlay", !only)));
            undo.add(c.onClient(mc -> Mod.with(cfg, "PartyProgressTrackerEnabled", !only)));
            undo.add(c.onClient(mc -> Mod.with(cfg, "AutoRestartEnabled", false)));
            undo.add(c.onClient(mc -> Mod.with(cfg, "AutoStartEnabled", false)));
            undo.add(c.onClient(mc -> Mod.with(cfg, "TriggerBotEnabled", false)));
            undo.add(c.onClient(mc -> Mod.with(cfg, "AutoSolveRotate", false)));
            undo.add(c.onClient(mc -> Mod.with(cfg, "AutoSimonSaysEnabled", true))); // the master switch (1a4e48f60)
            undo.add(c.onClient(mc -> Mod.with(cfg, "AutoSolveEnabled", true)));
            c.hx().call("boss.ss.build", "rounds", 4, "line", true, "seed", seed);
            c.hx().call("dungeon.tp", "x", 107.5, "y", 120.0, "z", 93.5, "yaw", -90f, "pitch", 0f);
            c.waitUntil("the client to have the device wall and start button", mc ->
                    mc.level.getBlockState(new BlockPos(111, 120, 92)).is(net.minecraft.world.level.block.Blocks.OBSIDIAN)
                            && mc.level.getBlockState(SimonSaysCases.START).is(net.minecraft.world.level.block.Blocks.STONE_BUTTON), 200);
            c.ctx().waitTicks(5);
            undo.add(c.onClient(mc -> Mod.with(cfg, "Enabled", !only)));
            c.waitUntil("the mod to be at the device", mc -> (Boolean) Mod.field(SimonSaysCases.FEATURE, "wasActive"), 200);
            c.ctx().waitTicks(5);
            SimonSaysCases.click(c, SimonSaysCases.START);
            c.waitUntil("the device to start (Hx)", mc -> !"IDLE".equals(SimonSaysCases.state(c).get("phase").getAsString()), 60);
            // Now face the way the case is about (server tp, so no rotation of the harness's own is ever sent).
            c.hx().call("dungeon.tp", "x", 107.5, "y", 120.0, "z", 93.5, "yaw", yaw, "pitch", 0f);
            c.waitUntil("the client facing " + yaw, mc -> Math.abs(Mth.wrapDegrees(mc.player.getYRot() - yaw)) < 0.01f, 40);
            c.ctx().waitTicks(2);
            float[] cam = start(c);
            boolean[] done = {false};
            try {
                c.waitUntil("ss.completed (Hx) with Auto Solve playing", mc -> {
                    track(mc, cam);
                    return !c.events("ss.completed").isEmpty();
                }, 1200);
                done[0] = true;
            } finally {
                JsonObject st = SimonSaysCases.state(c);
                c.note(String.format(Locale.ROOT, "yaw %.0f: completed %s, state %s, server presses %d, breaks %d", yaw, done[0],
                        st, c.events("ss.press").size(), c.events("ss.broke").size()));
            }
            c.check(c.events("ss.broke").isEmpty(), "the device broke: " + c.events("ss.broke"));
            c.onClient(mc -> {
                Mod.set(cfg, "setAutoSolveEnabled", false);
                return null;
            });
            finish(c, cam, "simon auto solve yaw " + yaw);
        } finally {
            c.hx().call("boss.ss.clear");
            c.onClient(mc -> {
                restore(undo);
                return null;
            });
            c.hx().sidebarClear();
            c.hx().call("dungeon.tp", "x", -619.5, "y", 151.0, "z", -619.5, "yaw", 0f, "pitch", 0f);
        }
    }

    // ---------------------------------------------------------------------------------------------------- helpers

    /** {body yaw, body pitch, camera yaw, camera pitch, worst camera deviation}. */
    static float[] start(Session c) {
        return c.onClient(mc -> new float[]{mc.player.getYRot(), mc.player.getXRot(),
                mc.player.getViewYRot(1f), mc.player.getViewXRot(1f), 0f});
    }

    static void track(net.minecraft.client.Minecraft mc, float[] cam) {
        if (mc.player == null) {
            return;
        }
        float d = Math.max(Math.abs(Mth.wrapDegrees(mc.player.getViewYRot(1f) - cam[2])),
                Math.abs(mc.player.getViewXRot(1f) - cam[3]));
        cam[4] = Math.max(cam[4], d);
    }

    /** A few ticks after the last action: the camera never moved and the body is back where it was. */
    static void finish(Session c, float[] cam, String what) {
        for (int i = 0; i < 6; i++) {
            c.ctx().waitTick();
            c.onClient(mc -> {
                track(mc, cam);
                return null;
            });
        }
        float[] end = c.onClient(mc -> new float[]{mc.player.getYRot(), mc.player.getXRot()});
        c.note(String.format(Locale.ROOT, "%s: camera moved at most %.3f deg; body yaw %.2f pitch %.2f (was %.2f, %.2f)",
                what, cam[4], Mth.wrapDegrees(end[0]), end[1], Mth.wrapDegrees(cam[0]), cam[1]));
        c.check(cam[4] < 0.01f, what + ": the camera moved " + cam[4] + " degrees - only the body may turn");
        c.check(Math.abs(Mth.wrapDegrees(end[0] - cam[0])) < 0.01f && Math.abs(end[1] - cam[1]) < 0.01f,
                what + ": the body was not given back: yaw " + end[0] + " pitch " + end[1]);
    }

    static void restore(List<AutoCloseable> undo) {
        for (int i = undo.size() - 1; i >= 0; i--) {
            try {
                undo.get(i).close();
            } catch (Exception e) {
                System.out.println("[aura-turn] restore failed: " + e);
            }
        }
    }
}
