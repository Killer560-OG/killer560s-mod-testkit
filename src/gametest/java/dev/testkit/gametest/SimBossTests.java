package dev.testkit.gametest;

import dev.testkit.compat.McCompat;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * The F7/M7 boss in the dungeon sim (mod doc-adm-boss-1101; killer560, 2026-11-01: "We have built the sim clear but not
 * the actual boss ... the map and how the lava functions and all that and all the terms"). Each case starts the boss
 * straight at a phase (the sim menu's F7 Boss entry, {@code BossSim.start}) and drives it the way he would, then judges
 * what the mod's own trackers made of it - and first proves the thing under test happened (the line was said, the block
 * changed, the terminal was solved by the mod's Auto Terminal).
 *
 * <ul>
 * <li>{@code 1101-boss-p1p2}: P1 Maxor (crystals picked up and placed at the pylons by real clicks, two stuns, his death
 * and the floor opening), the fall into P2, Storm crushed twice by real pad presses, then Goldor's line. DungeonState's
 * boss phase and Floor7Tracker's chat phase follow P1 -> P2 -> P3.</li>
 * <li>{@code 1101-boss-p3}: all four sections. Terminals opened with real clicks on their stands and solved by the mod's
 * Auto Terminal (every one of the six types), levers flicked with real clicks, Simon Says pressed button by button,
 * Lights' levers flicked, the Arrows frames turned by real clicks, Target hit by arrows the test summons at the lit
 * block. P3SectionTracker and Floor7Tracker must walk S1 -> S2 -> S3 -> S4 -> core.</li>
 * <li>{@code 1101-boss-p4p5}: the core (Goldor's arrival lines, his death), P4 Necron's lines and death, P5's dragons.</li>
 * <li>{@code 1101-boss-lava}: a step into P3's lava bounces him (motion 2.25 up), as Hypixel's server does.</li>
 * <li>{@code 1101-boss-ap3}: an AP3 RUN node on S1's floor carries him once he walks into it.</li>
 * <li>{@code 1101-boss-portal}: a generated F7, /goto Blood, the portal opens (the sim has no Watcher yet: five seconds in
 * Blood), stepping in starts P1 with the floor's map code unchanged (the floor is not reset).</li>
 * </ul>
 * Only when named. Hits on the bosses (Maxor, Goldor, Necron, the dragons) go through {@code BossFight.testHit}, the same
 * path a weapon's hit takes - the test has no weapon to swing at a boss floating out of reach.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimBossTests implements FabricClientGameTest {

    private static final String P1P2 = "1101-boss-p1p2";
    private static final String P3 = "1101-boss-p3";
    private static final String P4P5 = "1101-boss-p4p5";
    private static final String LAVA = "1101-boss-lava";
    private static final String AP3 = "1101-boss-ap3";
    private static final String PORTAL = "1101-boss-portal";

    private static final String B = "com.killer560.hub.bosssim.";
    private static final String SIM = B + "BossSim";
    private static final String FIGHT = B + "BossFight";
    private static final String START = B + "BossFight$Start";
    private static final String STATIONS = B + "BossStations";
    private static final String DEVICES = B + "BossDevices";
    private static final String TERMS = B + "BossTerminals";
    private static final String LAVA_CLASS = B + "BossLava";
    private static final String ARENA = B + "BossArena";
    private static final String F7 = "com.killer560.hub.fastleap.Floor7Tracker";
    private static final String SECTIONS = "com.killer560.hub.leveraura.P3SectionTracker";
    private static final String DUNGEON = "com.killer560.hub.secrets.DungeonState";
    private static final String TERM_CFG = "com.killer560.hub.terminals.TerminalSolverConfig";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank()) {
            return;
        }
        String[] all = {P1P2, P3, P4P5, LAVA, AP3, PORTAL};
        List<String> chosen = new ArrayList<>();
        for (String n : all) {
            if (!Scenario.skip(n)) {
                chosen.add(n);
            }
        }
        if (chosen.isEmpty()) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(20);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        List<String> failures = new ArrayList<>();
        for (String name : chosen) {
            List<String> f = new ArrayList<>();
            try {
                switch (name) {
                    case P1P2 -> p1p2(ctx, f);
                    case P3 -> p3(ctx, f);
                    case P4P5 -> p4p5(ctx, f);
                    case LAVA -> lava(ctx, f);
                    case PORTAL -> portal(ctx, f);
                    default -> ap3(ctx, f);
                }
            } catch (Throwable t) {
                f.add(name + " threw: " + t);
                t.printStackTrace(System.out);
            } finally {
                cleanup(ctx);
            }
            for (String s : f) {
                println(name, "FAIL: " + s);
            }
            println(name, f.isEmpty() ? "PASS" : "FAIL (" + f.size() + ")");
            for (String s : f) {
                failures.add(name + ": " + s);
            }
        }
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " problem(s): " + String.join("; ", failures));
        }
    }

    // ============================================================================================== cases

    private static void p1p2(ClientGameTestContext ctx, List<String> f) {
        long mark = start(ctx, "P1", true);
        if (!waitChat(ctx, mark, "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!", 40)) {
            f.add("Maxor's first line never came");
            return;
        }
        ctx.waitTicks(5);
        expect(f, "DungeonState boss phase after Maxor's line", call(DUNGEON, "isBossPhaseActive"), true);
        expect(f, "Floor7Tracker phase in P1", phase(ctx), "P1");
        if (!until(ctx, "both crystals on their towers", mc -> (Boolean) fight("crystalOnTower", 0) && (Boolean) fight("crystalOnTower", 1), 120)) {
            f.add("the crystals never appeared");
            return;
        }
        for (int round = 1; round <= 2; round++) {
            if (round == 2 && !until(ctx, "crystals back for round 2", mc -> (Boolean) fight("crystalOnTower", 0), 200)) {
                f.add("the crystals never came back after the first stun");
                return;
            }
            long m = LogTap.mark();
            tp(ctx, 64.5, 238, 50.5, 0, 0);
            ctx.waitTicks(5);
            tp(ctx, 82.5, 238, 50.5, 0, 0);
            ctx.waitTicks(5);
            if (count(m, "picked up an Energy Crystal!") < 2) {
                f.add("round " + round + ": walking into the crystals picked up " + count(m, "picked up an Energy Crystal!"));
                return;
            }
            tp(ctx, 52.5, 221, 44.5, 180, 30);
            ctx.waitTicks(5);
            useBlock(ctx, new BlockPos(52, 223, 42), Direction.SOUTH);
            ctx.waitTicks(5);
            tp(ctx, 94.5, 221, 44.5, 180, 30);
            ctx.waitTicks(5);
            useBlock(ctx, new BlockPos(94, 223, 42), Direction.SOUTH);
            if (!waitChat(ctx, m, "2/2 Energy Crystals are now active!", 20)) {
                f.add("round " + round + ": placing both crystals by clicking the pylons never said 2/2");
                return;
            }
            if (!waitChat(ctx, m, "The Energy Laser is charging up!", 40)
                    || !waitChatAny(ctx, m, new String[]{"[BOSS] Maxor: YOU TRICKED ME!", "[BOSS] Maxor: THAT BEAM! IT HURTS! IT HURTS!!"}, 80)) {
                f.add("round " + round + ": the laser never stunned Maxor");
                return;
            }
            for (int h = 0; h < 5; h++) {
                serverRun(ctx, s -> ModUnderTest.staticCall(FIGHT, "testHit", new Class<?>[]{String.class}, new Object[]{"Maxor"}));
                ctx.waitTicks(2);
            }
        }
        long dead = LogTap.mark();
        if (!waitChat(ctx, LogTap.mark() - 200, "[BOSS] Maxor: I'M TOO YOUNG TO DIE AGAIN!", 120)) {
            f.add("Maxor's death line never came after the second stun's hits");
            return;
        }
        boolean hole = ctx.computeOnClient(mc -> mc.level.getBlockState(new BlockPos(73, 220, 63)).isAir());
        expect(f, "Maxor's floor open over Storm", hole, true);
        tp(ctx, 73.5, 221, 63.5, 0, 0);
        if (!waitChat(ctx, dead, "[BOSS] Storm: Pathetic Maxor, just like expected.", 200)) {
            f.add("Storm's first line never came after the fall");
            return;
        }
        ctx.waitTicks(5);
        expect(f, "Floor7Tracker phase in P2", phase(ctx), "P2");
        // Two crushes: Storm under Purple, then Yellow, and a real stand on that pillar's pad each time.
        String[][] pads = {{"Purple", "114.5", "94.5"}, {"Yellow", "32.5", "94.5"}};
        for (int i = 0; i < 2; i++) {
            String pillar = pads[i][0];
            serverRun(ctx, s -> ModUnderTest.staticCall(FIGHT, "testParkStorm", new Class<?>[]{String.class}, new Object[]{pillar}));
            tp(ctx, Double.parseDouble(pads[i][1]), 170, Double.parseDouble(pads[i][2]), 0, 0);
            int want = i + 1;
            if (!until(ctx, "crush " + want, mc -> (Integer) ModUnderTest.staticCall(FIGHT, "stormCrushes") >= want, 140)) {
                f.add("standing on the " + pillar + " pad with Storm under it never crushed him");
                return;
            }
            tp(ctx, 73.5, 169, 50.5, 0, 0);
            ctx.waitTicks(5);
        }
        long d2 = LogTap.mark() - 50;
        if (!waitChat(ctx, d2, "[BOSS] Storm: I should have known that I stood no chance.", 40)) {
            f.add("Storm's death line never came after two crushes");
            return;
        }
        tp(ctx, 107.5, 169, 33.5, 0, 0);
        if (!waitChat(ctx, d2, "[BOSS] Goldor: Who dares trespass into my domain?", 140)) {
            f.add("Goldor's line never came after Storm's death");
            return;
        }
        ctx.waitTicks(10);
        expect(f, "Floor7Tracker phase in P3", phase(ctx), "P3");
        expect(f, "P3SectionTracker running", call(SECTIONS, "running"), true);
        expect(f, "P3SectionTracker section", call(SECTIONS, "section"), 1);
        double y = ctx.computeOnClient(mc -> mc.player.getY());
        println(P1P2, "after the drop from Storm's floor: y " + y);
        if (y > 120) {
            f.add("the fall from Storm's floor did not reach S1 (y " + y + ")");
        }
    }

    private static void p3(ClientGameTestContext ctx, List<String> f) {
        long mark = start(ctx, "S1", true);
        if (!waitChat(ctx, mark, "[BOSS] Goldor: Who dares trespass into my domain?", 40)) {
            f.add("Goldor's line never came");
            return;
        }
        ctx.runOnClient(mc -> {
            Object cfg = ModUnderTest.config(TERM_CFG);
            ModUnderTest.set(cfg, "setEnabled", true);
            ModUnderTest.set(cfg, "setAutoTerminalsEnabled", true);
            for (String s : new String[]{"Panes", "Rubix", "Numbers", "StartsWith", "Select", "Melody"}) {
                ModUnderTest.set(cfg, "setAuto" + s + "Enabled", true);
                ModUnderTest.set(cfg, "set" + s + "Enabled", true);
            }
        });
        ctx.waitTicks(5);
        String[] types = {"NUMBERS", "PANES", "RUBIX", "STARTS_WITH", "SELECT", "MELODY"};
        int typeIndex = 0;
        int[][] dirs = {{-1, 0}, {0, -1}, {1, 0}, {0, 1}};
        int[] sectionsSeen = new int[6];
        for (int sec = 1; sec <= 4; sec++) {
            int section = sec;
            expect(f, "P3SectionTracker at the start of S" + sec, call(SECTIONS, "section"), sec);
            sectionsSeen[sec] = (Integer) call(SECTIONS, "section");
            // ---- terminals, opened with a real click and solved by the mod's Auto Terminal ----
            for (String label : new String[]{"T1", "T2", "T3", "T4", "T5"}) {
                double[] at = (double[]) ModUnderTest.staticCall(STATIONS, "position", new Class<?>[]{int.class, String.class},
                        new Object[]{sec, label});
                if (at == null) {
                    continue;
                }
                String type = types[typeIndex++ % types.length];
                System.setProperty("killer560.bosssim.terminal", type);
                int[] d = dirs[sec - 1];
                tp(ctx, Math.floor(at[0]) + d[0] * 2 + 0.5, at[1], Math.floor(at[2]) + d[1] * 2 + 0.5, 0, 0);
                ctx.waitTicks(8);
                long m = LogTap.mark();
                int solvedBefore = (Integer) call(TERMS, "solvedCount");
                boolean clicked = ctx.computeOnClient(mc -> {
                    Entity stand = standAt(mc, at, "Inactive Terminal");
                    if (stand == null) {
                        return false;
                    }
                    println(P3, String.format(Locale.ROOT, "click stand #%d at %.2f %.2f %.2f from %.2f %.2f %.2f",
                            stand.getId(), stand.getX(), stand.getY(), stand.getZ(), mc.player.getX(), mc.player.getY(), mc.player.getZ()));
                    mc.gameMode.interact(mc.player, stand, new EntityHitResult(stand, stand.position().add(0, 1, 0)), InteractionHand.MAIN_HAND);
                    return true;
                });
                if (!clicked) {
                    f.add("S" + sec + " " + label + ": no Inactive Terminal stand at its spot");
                    continue;
                }
                int openedBefore = (Integer) call(TERMS, "openedCount");
                if (!until(ctx, "S" + sec + " " + label + " opened", mc -> (Integer) call(TERMS, "openedCount") > openedBefore, 20)) {
                    // One more click, from a block nearer (logged, so a second click is visible in the result).
                    println(P3, "S" + sec + " " + label + ": first click opened nothing, clicking again from 1 block");
                    tp(ctx, Math.floor(at[0]) + d[0] + 0.5, at[1], Math.floor(at[2]) + d[1] + 0.5, 0, 0);
                    ctx.waitTicks(5);
                    ctx.runOnClient(mc -> {
                        Entity stand = standAt(mc, at, "Inactive Terminal");
                        if (stand != null) {
                            mc.gameMode.interact(mc.player, stand, new EntityHitResult(stand, stand.position().add(0, 1, 0)),
                                    InteractionHand.MAIN_HAND);
                        }
                    });
                }
                if (!until(ctx, "S" + sec + " " + label + " solved", mc -> (Integer) call(TERMS, "solvedCount") > solvedBefore, 600)) {
                    f.add("S" + sec + " " + label + " (" + type + "): the mod's Auto Terminal never solved it");
                    ctx.runOnClient(mc -> {
                        if (mc.player.containerMenu != mc.player.inventoryMenu) {
                            mc.player.closeContainer();
                        }
                    });
                    serverRun(ctx, s -> ModUnderTest.staticCall(STATIONS, "testComplete",
                            new Class<?>[]{MinecraftServer.class, int.class, String.class}, new Object[]{s, section, label}));
                    continue;
                }
                if (!waitChat(ctx, m, "activated a terminal!", 20)) {
                    f.add("S" + sec + " " + label + ": solved but no 'activated a terminal!' line");
                }
                println(P3, "S" + sec + " " + label + " " + type + " solved by Auto Terminal");
            }
            System.clearProperty("killer560.bosssim.terminal");
            // ---- levers, real clicks ----
            for (String label : sec == 1 || sec == 3 ? new String[]{"east lever", "west lever"} : new String[]{"low lever", "high lever"}) {
                double[] at = (double[]) ModUnderTest.staticCall(STATIONS, "position", new Class<?>[]{int.class, String.class},
                        new Object[]{sec, label});
                int[] d = dirs[sec - 1];
                BlockPos lever = BlockPos.containing(at[0], Math.round(at[1] - 0.688), at[2]);
                tp(ctx, lever.getX() + d[0] * 2 + 0.5, lever.getY(), lever.getZ() + d[1] * 2 + 0.5, 0, 30);
                ctx.waitTicks(8);
                long m = LogTap.mark();
                useBlock(ctx, lever, Direction.UP);
                if (!waitChat(ctx, m, "activated a lever!", 20)) {
                    f.add("S" + sec + " " + label + ": a click on the lever said nothing");
                }
            }
            // ---- the section's device ----
            long dm = LogTap.mark();
            switch (sec) {
                case 1 -> simonSays(ctx, f);
                case 2 -> lights(ctx, f);
                case 3 -> arrows(ctx, f);
                default -> target(ctx, f);
            }
            if (!waitChat(ctx, dm, "completed a device!", 40)) {
                f.add("S" + sec + ": the device never said 'completed a device!'");
                serverRun(ctx, s -> ModUnderTest.staticCall(STATIONS, "testComplete",
                        new Class<?>[]{MinecraftServer.class, int.class, String.class},
                        new Object[]{s, section, new String[]{"SS", "Lights", "Arrows", "Target"}[section - 1]}));
            }
            // ---- the section ends ----
            long end = LogTap.mark() - 30;
            if (sec < 4) {
                if (!waitChat(ctx, end, "The gate has been destroyed!", 160)) {
                    f.add("S" + sec + ": the gate was never destroyed (" + call(STATIONS, "describe") + ")");
                    return;
                }
                ctx.waitTicks(4);
                int s2 = sec;
                boolean gateGone = ctx.computeOnClient(mc -> {
                    int[] g = s2 == 1 ? new int[]{100, 115, 123} : s2 == 2 ? new int[]{18, 115, 133} : new int[]{6, 115, 50};
                    return mc.level.getBlockState(new BlockPos(g[0], g[1], g[2])).isAir();
                });
                expect(f, "S" + sec + " gate blocks gone", gateGone, true);
            } else if (!waitChat(ctx, end, "The Core entrance is opening!", 40)) {
                f.add("S4: the core never opened");
                return;
            }
            ctx.waitTicks(5);
            expect(f, "P3SectionTracker after S" + sec, call(SECTIONS, "section"), sec + 1);
            expect(f, "P3SectionTracker.sectionDone(" + sec + ")",
                    ModUnderTest.staticCall(SECTIONS, "sectionDone", new Class<?>[]{int.class}, new Object[]{sec}), true);
        }
        expect(f, "Floor7Tracker stage at the end", ctx.computeOnClient(mc ->
                String.valueOf(ModUnderTest.staticCall(F7, "getStage"))), "S5");
        println(P3, "stations: " + call(STATIONS, "describe"));
    }

    private static void simonSays(ClientGameTestContext ctx, List<String> f) {
        tp(ctx, 108.5, 120, 93.5, -90, 0);
        ctx.waitTicks(8);
        useBlock(ctx, new BlockPos(110, 121, 91), Direction.EAST);
        for (int round = 1; round <= 4; round++) {
            int r = round;
            if (!until(ctx, "SS round " + round + " input", mc -> (Boolean) call(DEVICES, "ssInput")
                    && ((List<?>) call(DEVICES, "ssSequence")).size() == r, 200)) {
                f.add("Simon Says never reached round " + round + " (" + call(DEVICES, "describe") + ")");
                return;
            }
            ctx.waitTicks(3);
            @SuppressWarnings("unchecked")
            List<int[]> seq = (List<int[]>) call(DEVICES, "ssSequence");
            for (int[] lantern : seq) {
                useBlock(ctx, new BlockPos(110, lantern[1], lantern[2]), Direction.EAST);
                ctx.waitTicks(3);
            }
        }
    }

    private static void lights(ClientGameTestContext ctx, List<String> f) {
        tp(ctx, 60.5, 131, 140.5, 180, -20);
        ctx.waitTicks(8);
        int[][] levers = {{62, 136, 142}, {58, 136, 142}, {60, 135, 142}, {60, 134, 142}, {62, 133, 142}, {58, 133, 142}};
        int flicked = 0;
        for (int[] l : levers) {
            BlockPos p = new BlockPos(l[0], l[1], l[2]);
            boolean on = ctx.computeOnClient(mc -> mc.level.getBlockState(p).getValue(
                    net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED));
            if (!on) {
                useBlock(ctx, p, Direction.NORTH);
                flicked++;
                ctx.waitTicks(3);
            }
        }
        println(P3, "Lights: flicked " + flicked + " lever(s) on");
        if (flicked == 0) {
            f.add("Lights started with every lever on - nothing was done");
        }
    }

    private static void arrows(ClientGameTestContext ctx, List<String> f) {
        tp(ctx, -0.5, 119, 77.5, 90, 0);
        ctx.waitTicks(10);
        int clicks = 0;
        for (int i = 0; i < 25; i++) {
            int want = (Integer) ModUnderTest.staticCall(DEVICES, "arrowSolution", new Class<?>[]{int.class}, new Object[]{i});
            if (want < 0) {
                continue;
            }
            BlockPos pos = new BlockPos(-2, 120 + i % 5, 75 + i / 5);
            for (int k = 0; k < 8; k++) {
                Integer rot = ctx.computeOnClient(mc -> {
                    ItemFrame fr = frameAt(mc, pos);
                    return fr == null ? null : fr.getRotation() & 7;
                });
                if (rot == null) {
                    f.add("Arrows: no frame at " + pos.toShortString());
                    return;
                }
                if (rot == want) {
                    break;
                }
                ctx.runOnClient(mc -> {
                    ItemFrame fr = frameAt(mc, pos);
                    mc.gameMode.interact(mc.player, fr, new EntityHitResult(fr, fr.position()), InteractionHand.MAIN_HAND);
                });
                clicks++;
                ctx.waitTicks(3);
            }
        }
        println(P3, "Arrows: " + clicks + " real click(s) on frames");
        if (clicks == 0) {
            f.add("Arrows needed no click - the layout started solved");
        }
    }

    private static void target(ClientGameTestContext ctx, List<String> f) {
        tp(ctx, 63.5, 126, 34.5, 0, -20);
        ctx.waitTicks(8);
        for (int hit = 0; hit < 9; hit++) {
            if (!until(ctx, "a lit target", mc -> call(DEVICES, "litTarget") != null, 40)) {
                f.add("Target: nothing lit after " + hit + " hit(s)");
                return;
            }
            int[] lit = (int[]) call(DEVICES, "litTarget");
            boolean emerald = ctx.computeOnClient(mc -> mc.level.getBlockState(new BlockPos(lit[0], lit[1], lit[2]))
                    .is(net.minecraft.world.level.block.Blocks.EMERALD_BLOCK));
            if (!emerald) {
                f.add("Target: the lit block is not emerald on the client");
            }
            command(ctx, String.format(Locale.ROOT, "summon minecraft:arrow %.2f %.2f %.2f {Motion:[0.0,0.0,0.5d]}",
                    lit[0] + 0.5, lit[1] + 0.5, lit[2] - 0.4));
            ctx.waitTicks(6);
        }
    }

    private static void p4p5(ClientGameTestContext ctx, List<String> f) {
        long mark = start(ctx, "CORE", true);
        if (!waitChat(ctx, mark, "[BOSS] Goldor: Who dares trespass into my domain?", 40)) {
            f.add("Goldor's line never came");
            return;
        }
        expect(f, "P3SectionTracker at the core", call(SECTIONS, "section"), 1);
        tp(ctx, 54.5, 107, 90.5, 0, 0);
        if (!waitChat(ctx, mark, "[BOSS] Goldor: You have done it, you destroyed the factory...", 400)) {
            f.add("walking into the core never brought Goldor's arrival lines");
            return;
        }
        for (int h = 0; h < 10; h++) {
            serverRun(ctx, s -> ModUnderTest.staticCall(FIGHT, "testHit", new Class<?>[]{String.class}, new Object[]{"Goldor"}));
            ctx.waitTicks(2);
        }
        if (!waitChat(ctx, mark, "[BOSS] Goldor: ....", 20)) {
            f.add("Goldor never died");
            return;
        }
        tp(ctx, 54.5, 107, 76.5, 0, 0);
        if (!waitChat(ctx, mark, "[BOSS] Necron: You went further than any human before, congratulations.", 120)) {
            f.add("Necron's first line never came");
            return;
        }
        ctx.waitTicks(5);
        expect(f, "Floor7Tracker phase in P4", phase(ctx), "P4");
        double y4 = ctx.computeOnClient(mc -> mc.player.getY());
        if (y4 > 80) {
            f.add("the core floor never let him down into P4 (y " + y4 + ")");
        }
        if (!waitChat(ctx, mark, "[BOSS] Necron: That's a very impressive trick. I guess I'll have to handle this myself.", 200)) {
            f.add("Necron's intro never finished");
            return;
        }
        for (int h = 0; h < 15; h++) {
            serverRun(ctx, s -> ModUnderTest.staticCall(FIGHT, "testHit", new Class<?>[]{String.class}, new Object[]{"Necron"}));
            ctx.waitTicks(2);
        }
        expect(f, "Necron's ARGH line", chatSeen(mark, "[BOSS] Necron: ARGH!"), true);
        if (!waitChat(ctx, mark, "[BOSS] Necron: All this, for nothing...", 20)) {
            f.add("Necron never died");
            return;
        }
        ctx.waitTicks(5);
        expect(f, "Floor7Tracker phase in P5", phase(ctx), "P5");
        ctx.waitTicks(45);
        tp(ctx, 54.5, 63, 76.5, 0, 0);
        if (!waitChat(ctx, mark, "[BOSS] Wither King: You... again?", 160)) {
            f.add("the Wither King's line never came");
            return;
        }
        double y5 = ctx.computeOnClient(mc -> mc.player.getY());
        if (y5 > 20) {
            f.add("Necron's platform never let him down into P5 (y " + y5 + ")");
        }
        int killed = 0;
        for (int i = 0; i < 5; i++) {
            if (!until(ctx, "a dragon up", mc -> !mc.level.getEntitiesOfClass(net.minecraft.world.entity.boss.enderdragon.EnderDragon.class,
                    new AABB(0, 0, 20, 110, 60, 150), d -> d.getHealth() > 0).isEmpty(), 260)) {
                f.add("dragon " + (i + 1) + " never spawned");
                return;
            }
            for (int h = 0; h < 8; h++) {
                serverRun(ctx, s -> ModUnderTest.staticCall(FIGHT, "testHit", new Class<?>[]{String.class}, new Object[]{"Dragon"}));
                ctx.waitTicks(2);
            }
            killed++;
            ctx.waitTicks(25);
        }
        if (!until(ctx, "boss cleared", mc -> "DONE".equals(String.valueOf(call(FIGHT, "phase"))), 100)) {
            f.add("the fight never ended after " + killed + " dragons (" + call(FIGHT, "describe") + ")");
        }
        expect(f, "Wither King counts lines", count(mark, "[BOSS] Wither King: ") >= 6, true);
    }

    private static void lava(ClientGameTestContext ctx, List<String> f) {
        long mark = start(ctx, "S1", true);
        if (!waitChat(ctx, mark, "[BOSS] Goldor: Who dares trespass into my domain?", 40)) {
            f.add("Goldor's line never came");
            return;
        }
        int before = (Integer) call(LAVA_CLASS, "bounces");
        tp(ctx, 30.5, 109, 90.5, 0, 0);
        double[] top = {0};
        boolean bounced = until(ctx, "a lava bounce", mc -> {
            top[0] = Math.max(top[0], mc.player.getY());
            return (Integer) call(LAVA_CLASS, "bounces") > before && mc.player.getY() > 108.5;
        }, 80);
        for (int i = 0; i < 10; i++) {
            ctx.waitTick();
            top[0] = Math.max(top[0], ctx.computeOnClient(mc -> mc.player.getY()));
        }
        println(LAVA, "bounces " + ((Integer) call(LAVA_CLASS, "bounces") - before) + ", highest y " + top[0]);
        if (!bounced) {
            f.add("standing in P3's lava never bounced him");
        }
        if (top[0] < 110.0) {
            f.add("the bounce only reached y " + top[0] + " (Hypixel's from 106: 108.25, 110.03, 111.69 ...)");
        }
    }

    private static void ap3(ClientGameTestContext ctx, List<String> f) {
        long mark = start(ctx, "S1", true);
        if (!waitChat(ctx, mark, "[BOSS] Goldor: Who dares trespass into my domain?", 40)) {
            f.add("Goldor's line never came");
            return;
        }
        ctx.runOnClient(mc -> {
            Object cfg = ModUnderTest.config(Ap3RuntimeTests.CONFIG);
            ModUnderTest.set(cfg, "setEnabled", true);
            ModUnderTest.staticCall(Ap3RuntimeTests.FEATURE, "setForceDungeon", new Class<?>[]{boolean.class}, new Object[]{false});
        });
        // A RUN node on S1's floor, pointing south (+z), box 2x2; he walks into it from the north with a real W.
        Object run = ctx.computeOnClient(mc -> {
            Object n = ap3Node("RUN", 100.5, 107, 50.5, 0f, 0f);
            Ap3RuntimeTests.set(n, "length", 2.0);
            Ap3RuntimeTests.set(n, "width", 2.0);
            Ap3RuntimeTests.setChain(n);
            return n;
        });
        tp(ctx, 100.5, 107, 46.5, 0, 0);
        ctx.waitTicks(10);
        double z0 = ctx.computeOnClient(mc -> mc.player.getZ());
        ctx.getInput().holdKey(options -> options.keyUp);
        boolean inside = false;
        for (int i = 0; i < 60 && !inside; i++) {
            ctx.waitTick();
            inside = ctx.computeOnClient(mc -> Ap3RuntimeTests.contains(run, mc.player.position()));
        }
        ctx.getInput().releaseKey(options -> options.keyUp);
        if (!inside) {
            f.add("walking with W never reached the RUN node's box");
            return;
        }
        boolean ran = until(ctx, "AP3 running", mc -> (Boolean) ModUnderTest.staticCall(Ap3RuntimeTests.EXEC, "isRunning"), 20);
        ctx.waitTicks(20);
        double z1 = ctx.computeOnClient(mc -> mc.player.getZ());
        println(AP3, String.format(Locale.ROOT, "AP3 ran %s, z %.2f -> %.2f", ran, z0, z1));
        if (!ran) {
            f.add("AP3 never ran the RUN node in the sim boss");
        }
        if (z1 - z0 < 6.0) {
            f.add(String.format(Locale.ROOT, "AP3's run carried him only %.2f blocks", z1 - z0));
        }
        ctx.runOnClient(mc -> {
            ModUnderTest.staticCall(Ap3RuntimeTests.EXEC, "stop", new Class<?>[]{String.class}, new Object[]{"test over"});
            Ap3RuntimeTests.setChain();
        });
    }

    private static void portal(ClientGameTestContext ctx, List<String> f) {
        if (!SimPuzzleSolveTests.seedRooms(ctx)) {
            Scenario.skipped(PORTAL, "needs real room captures and room database");
            return;
        }
        String code = null;
        for (int i = 0; i < 6 && code == null; i++) {
            code = planF7(ctx);
        }
        if (code == null) {
            f.add("no F7 could be planned");
            return;
        }
        buildFloorAndEnter(ctx, f, code);
    }

    private static String planF7(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            Object floor = ModUnderTest.enumValue("com.killer560.hub.roomsim.SimFloorGen$Floor", "F7");
            Object planned = ModUnderTest.staticCall("com.killer560.hub.roomsim.SimFloorGen", "plan",
                    new Class<?>[]{floor.getClass(), int.class, int.class}, new Object[]{floor, 4, 5});
            return planned == null ? null : (String) ModUnderTest.call(planned, "code", new Class<?>[0], new Object[0]);
        });
    }

    private static void buildFloorAndEnter(ClientGameTestContext ctx, List<String> f, String code) {
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> {
            java.util.function.Consumer<Minecraft> build = c -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimBuilder",
                    "build", new Class<?>[]{Minecraft.class, String.class}, new Object[]{c, code});
            ModUnderTest.staticCall("com.killer560.hub.roomsim.SimWorld", "open", new Class<?>[]{Minecraft.class, String.class,
                    java.util.function.Consumer.class, String.class}, new Object[]{mc, code, build, "Generating Floor 7"});
        }));
        ctx.waitFor(mc -> mc.level != null, 2400);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitTicks(80);
        String mapBefore = String.valueOf(call("com.killer560.hub.roomsim.SimState", "mapCode"));
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimTeleportCommands", "goTo",
                new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, "Blood"}));
        if (!until(ctx, "standing in Blood", mc -> "Blood".equals(call("com.killer560.hub.roomsim.SimState", "currentRoomName")), 100)) {
            f.add("/goto Blood never put him in the Blood room");
            return;
        }
        if (!until(ctx, "the portal", mc -> call(B + "BossPortal", "portal") != null, 200)) {
            f.add("no portal opened in Blood");
            return;
        }
        int[] p = (int[]) call(B + "BossPortal", "portal");
        ctx.waitTicks(10);
        expect(f, "standing on the spot as it opened did not take him in", call(FIGHT, "running"), false);
        tp(ctx, p[0] + 4.5, p[1] + 1, p[2] + 0.5, 0, 0);
        ctx.waitTicks(10);
        boolean placed = ctx.computeOnClient(mc -> mc.level.getBlockState(new BlockPos(p[0], p[1], p[2]))
                .is(net.minecraft.world.level.block.Blocks.CRYING_OBSIDIAN));
        expect(f, "portal blocks in Blood", placed, true);
        long mark = LogTap.mark();
        tp(ctx, p[0] + 0.5, p[1] + 1, p[2] + 0.5, 0, 0);
        if (!waitChat(ctx, mark, "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!", 600)) {
            f.add("stepping into the portal never started P1");
            return;
        }
        ctx.waitTicks(5);
        expect(f, "the floor's map code after entering the boss",
                String.valueOf(call("com.killer560.hub.roomsim.SimState", "mapCode")), mapBefore);
        expect(f, "Floor7Tracker phase after the portal", phase(ctx), "P1");
    }

    // ============================================================================================== helpers

    private static Object ap3Node(String type, double x, double y, double z, float yaw, float pitch) {
        try {
            Class<?> cls = Class.forName("com.killer560.hub.ap3.Ap3Node");
            return cls.getConstructor(Class.forName("com.killer560.hub.ap3.Ap3Node$Type"), double.class, double.class,
                    double.class, float.class, float.class).newInstance(
                    ModUnderTest.enumValue("com.killer560.hub.ap3.Ap3Node$Type", type), x, y, z, yaw, pitch);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot make an AP3 " + type + " node", e);
        }
    }

    /** Starts the boss at {@code phase} (opening the sim world from the title screen) and waits for it to be running. */
    private static long start(ClientGameTestContext ctx, String phase, boolean master) {
        long mark = LogTap.mark();
        Object start = ModUnderTest.enumValue(START, phase);
        ctx.runOnClient(mc -> mc.execute(() -> ModUnderTest.staticCall(SIM, "start",
                new Class<?>[]{Minecraft.class, start.getClass(), boolean.class}, new Object[]{mc, start, master})));
        ctx.waitFor(mc -> mc.level != null && mc.player != null, 2400);
        if (!until(ctx, "the boss built and running", mc -> !(Boolean) call(SIM, "isBuilding")
                && (Boolean) call(FIGHT, "running"), 2400)) {
            throw new AssertionError("the boss never started at " + phase);
        }
        ctx.waitFor(mc -> McCompat.screen(mc) == null, 600);
        println("boss", "started at " + phase + " from the " + call(ARENA, "source") + ": " + call(FIGHT, "describe"));
        return mark;
    }

    private static void cleanup(ClientGameTestContext ctx) {
        System.clearProperty("killer560.bosssim.terminal");
        try {
            ctx.runOnClient(mc -> {
                ModUnderTest.turnOff(TERM_CFG, "setAutoTerminalsEnabled");
                ModUnderTest.turnOff(Ap3RuntimeTests.CONFIG, "setEnabled");
            });
        } catch (Throwable ignored) {
            // never let cleanup replace the verdict
        }
        if (ctx.computeOnClient(mc -> mc.level != null)) {
            SimPuzzleSolveTests.teardown(ctx);
        }
    }

    private static Object call(String cls, String method) {
        return ModUnderTest.staticCall(cls, method, new Class<?>[]{}, new Object[]{});
    }

    private static Object fight(String method, int arg) {
        return ModUnderTest.staticCall(FIGHT, method, new Class<?>[]{int.class}, new Object[]{arg});
    }

    private static String phase(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> String.valueOf(ModUnderTest.staticCall(F7, "getPhase")));
    }

    private static void serverRun(ClientGameTestContext ctx, java.util.function.Consumer<MinecraftServer> body) {
        ctx.runOnClient(mc -> {
            MinecraftServer s = mc.getSingleplayerServer();
            s.execute(() -> body.accept(s));
        });
        ctx.waitTicks(1);
    }

    private static void command(ClientGameTestContext ctx, String cmd) {
        serverRun(ctx, s -> s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), cmd));
    }

    private static void tp(ClientGameTestContext ctx, double x, double y, double z, float yaw, float pitch) {
        command(ctx, String.format(Locale.ROOT, "tp @p %.2f %.2f %.2f %.1f %.1f", x, y, z, yaw, pitch));
        ctx.waitTicks(3);
    }

    private static void useBlock(ClientGameTestContext ctx, BlockPos pos, Direction face) {
        ctx.runOnClient(mc -> {
            Vec3 hit = Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, pos, false));
        });
    }

    private static Entity standAt(Minecraft mc, double[] at, String name) {
        for (ArmorStand s : mc.level.getEntitiesOfClass(ArmorStand.class, new AABB(at[0] - 1, at[1] - 1, at[2] - 1,
                at[0] + 1, at[1] + 1, at[2] + 1))) {
            if (s.getCustomName() != null && s.getCustomName().getString().contains(name)) {
                return s;
            }
        }
        return null;
    }

    private static ItemFrame frameAt(Minecraft mc, BlockPos pos) {
        for (ItemFrame fr : mc.level.getEntitiesOfClass(ItemFrame.class, new AABB(pos).inflate(0.2))) {
            if (fr.blockPosition().equals(pos)) {
                return fr;
            }
        }
        return null;
    }

    private static boolean until(ClientGameTestContext ctx, String what, Predicate<Minecraft> p, int ticks) {
        for (int i = 0; i < ticks; i++) {
            if (ctx.computeOnClient(p::test)) {
                return true;
            }
            ctx.waitTick();
        }
        println("boss", "timed out waiting for " + what);
        return false;
    }

    private static boolean chatSeen(long mark, String text) {
        for (String l : LogTap.since(mark)) {
            if (l.contains("[CHAT]") && l.contains(text)) {
                return true;
            }
        }
        return false;
    }

    private static int count(long mark, String text) {
        int n = 0;
        for (String l : LogTap.since(mark)) {
            if (l.contains("[CHAT]") && l.contains(text)) {
                n++;
            }
        }
        return n;
    }

    private static boolean waitChat(ClientGameTestContext ctx, long mark, String text, int ticks) {
        return until(ctx, "chat '" + text + "'", mc -> chatSeen(mark, text), ticks);
    }

    private static boolean waitChatAny(ClientGameTestContext ctx, long mark, String[] texts, int ticks) {
        return until(ctx, "one of " + String.join(" | ", texts), mc -> {
            for (String t : texts) {
                if (chatSeen(mark, t)) {
                    return true;
                }
            }
            return false;
        }, ticks);
    }

    private static void expect(List<String> f, String what, Object got, Object want) {
        boolean ok = String.valueOf(want).equals(String.valueOf(got));
        println("boss", what + ": " + got + (ok ? "" : " (want " + want + ")"));
        if (!ok) {
            f.add(what + ": " + got + ", want " + want);
        }
    }

    private static void println(String name, String line) {
        System.out.println("[" + name + "] " + line);
    }
}
