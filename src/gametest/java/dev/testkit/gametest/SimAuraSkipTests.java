package dev.testkit.gametest;

import dev.testkit.compat.McCompat;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Blocks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 843-sim-aura-skip-waterboard: Secret Aura's QUOI puzzle skip (mod aura-quoi, 2026-10-12). In Water Board nothing is
 * clicked while the board is being solved - its reward chest spawns on the solve. The sim's Water Board is built as a
 * single room, a chest is put on the SERVER two blocks from him (the old jar clicked any chest there), and Secret Aura
 * goes on with chests only: no click may reach the server for 60 ticks. Then the room is marked cleared, the sim's own
 * "puzzle over" signal the map reads, and the same chest must be clicked - so the aura was alive and the room's state was
 * what held it. The verdict is the integrated server's record of a use on that block. Sim only, so no GrimAC.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimAuraSkipTests implements FabricClientGameTest {

    private static final String NAME = "843-sim-aura-skip-waterboard";
    private static final String ROOM = "Water Board";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String BUILDER = "com.killer560.hub.roomsim.SimBuilder";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String ROOM_STATE = "com.killer560.hub.roomsim.SimRoomState";
    private static final String LIVE_MAP = "com.killer560.hub.livemap.LiveMapFeature";
    private static final String CHEAT_CFG = "com.killer560.hub.cheatutils.CheatUtilsConfig";
    private static final String SOURCE_ROOMS =
            ModUnderTest.instanceConfig(Machine.instanceConfigDir(SimEssenceAuraTests.ROOM_INSTANCE), "killer560smod-rooms");

    private static final Set<String> SERVER_USES = ConcurrentHashMap.newKeySet();
    private static boolean hooked;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank() || Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        auraOff(ctx);
        if (copyDir(SOURCE_ROOMS, "killer560smod-rooms", ".json") < 20
                || copyDir(Path.of(SOURCE_ROOMS).resolveSibling("killer560smod-roomdata").toString(),
                        "killer560smod-roomdata", "") == 0) {
            Scenario.skipped(NAME, "needs room captures and room database (" + SimEssenceAuraTests.ROOM_INSTANCE + ")");
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);
        hookServerUses();
        String verdict;
        try {
            verdict = measure(ctx);
        } finally {
            auraOff(ctx);
            leave(ctx);
        }
        println(verdict);
        if (!verdict.startsWith("PASS")) {
            throw new AssertionError(verdict);
        }
    }

    private String measure(ClientGameTestContext ctx) {
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> ModUnderTest.staticCall(BUILDER, "buildSingleRoom",
                new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, ROOM})));
        ctx.waitFor(mc -> mc.level != null && mc.player != null, 2400);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitFor(mc -> McCompat.screen(mc) == null, 1200);
        ctx.waitTicks(40);
        ctx.waitFor(mc -> mc.player != null && mc.player.onGround()
                && !mc.level.getBlockState(mc.player.blockPosition().below()).isAir(), 1800);
        ctx.waitTicks(20);

        String room = ctx.computeOnClient(mc -> {
            Object entry = ModUnderTest.staticCall(LIVE_MAP, "currentRoomEntry", new Class<?>[]{}, new Object[]{});
            if (entry == null) {
                return "none";
            }
            try {
                return String.valueOf(entry.getClass().getField("name").get(entry));
            } catch (ReflectiveOperationException e) {
                return "?" + e;
            }
        });
        println("standing in room '" + room + "'");
        if (!ROOM.equals(room)) {
            return "FAIL premise - the live map says he stands in '" + room + "', not " + ROOM;
        }

        // A chest on the server, two blocks from his feet, on the first free spot found.
        AtomicReference<BlockPos> chest = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                ServerLevel level = server.overworld();
                ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
                BlockPos feet = sp.blockPosition();
                int[][] offsets = {{2, 0}, {-2, 0}, {0, 2}, {0, -2}, {2, 2}, {-2, -2}, {2, -2}, {-2, 2}};
                for (int[] o : offsets) {
                    BlockPos p = feet.offset(o[0], 0, o[1]);
                    if (level.getBlockState(p).isAir()) {
                        level.setBlock(p, Blocks.CHEST.defaultBlockState(), 3);
                        chest.set(p.immutable());
                        return;
                    }
                }
                chest.set(BlockPos.ZERO);
            });
        });
        ctx.waitFor(mc -> chest.get() != null, 200);
        BlockPos c = chest.get();
        if (c.equals(BlockPos.ZERO)) {
            return "FAIL premise - no free block two from him for a chest";
        }
        ctx.waitFor(mc -> mc.level.getBlockState(c).getBlock() == Blocks.CHEST, 200);
        String key = c.getX() + "," + c.getY() + "," + c.getZ();
        println("chest at " + c.toShortString());

        // Mid-solve: nothing.
        SERVER_USES.clear();
        ctx.runOnClient(mc -> {
            Object cfg = ModUnderTest.config(CHEAT_CFG);
            ModUnderTest.set(cfg, "setAuraChests", true);
            ModUnderTest.set(cfg, "setAuraLevers", false);
            ModUnderTest.set(cfg, "setAuraEssence", false);
            ModUnderTest.set(cfg, "setAuraPauseWhileSneaking", false);
            ModUnderTest.set(cfg, "setAuraRange", 4.5);
            ModUnderTest.set(cfg, "setSecretAuraEnabled", true);
        });
        ctx.waitTicks(60);
        int midSolve = SERVER_USES.size();
        boolean chestMid = SERVER_USES.contains(key);

        // Puzzle over (the room cleared, as the sim marks it on a solve): the same chest.
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_STATE, "markCleared",
                new Class<?>[]{String.class}, new Object[]{ROOM}));
        int waited = 0;
        for (; waited < 100 && !SERVER_USES.contains(key); waited += 5) {
            ctx.waitTicks(5);
        }
        boolean chestAfter = SERVER_USES.contains(key);
        auraOff(ctx);
        String facts = "mid-solve: " + midSolve + " server use(s) in 60 ticks (chest " + chestMid + "); after the room "
                + "was cleared: chest clicked " + chestAfter + (chestAfter ? " after ~" + waited + " ticks" : "");
        if (midSolve != 0) {
            return "FAIL - Secret Aura clicked in Water Board mid-solve - " + facts;
        }
        if (!chestAfter) {
            return "FAIL - the chest was never clicked once the puzzle was over - " + facts;
        }
        return "PASS - " + facts;
    }

    private static void hookServerUses() {
        if (hooked) {
            return;
        }
        hooked = true;
        var phase = net.minecraft.resources.Identifier.fromNamespaceAndPath("testkit", "aura_skip_watch");
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.addPhaseOrdering(phase,
                net.fabricmc.fabric.api.event.Event.DEFAULT_PHASE);
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register(phase, (player, level, hand, hit) -> {
            if (!level.isClientSide() && player instanceof ServerPlayer && hit != null) {
                BlockPos b = hit.getBlockPos();
                SERVER_USES.add(b.getX() + "," + b.getY() + "," + b.getZ());
            }
            return InteractionResult.PASS;
        });
    }

    private static void auraOff(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            try {
                ModUnderTest.set(ModUnderTest.config(CHEAT_CFG), "setSecretAuraEnabled", false);
            } catch (Throwable ignored) {
                // cleanup never replaces a verdict
            }
        });
    }

    private static void leave(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            try {
                ModUnderTest.staticCall(SIM_STATE, "leave", new Class<?>[]{}, new Object[]{});
            } catch (Throwable ignored) {
                // cleanup never replaces a verdict
            }
        });
        ctx.runOnClient(mc -> mc.execute(() -> {
            if (mc.level != null) {
                mc.level.disconnect(net.minecraft.network.chat.Component.literal("scenario over"));
                mc.disconnectWithSavingScreen();
            }
        }));
        ctx.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null, 1200);
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> mc.execute(() ->
                McCompat.setScreen(mc, new net.minecraft.client.gui.screens.TitleScreen())));
        ctx.waitFor(mc -> McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.TitleScreen, 400);
    }

    private void println(String line) {
        System.out.println("[" + NAME + "] " + line);
    }

    private static int copyDir(String from, String into, String suffix) {
        try {
            Path source = Path.of(from);
            if (!Files.isDirectory(source)) {
                return 0;
            }
            Path target = ModUnderTest.modConfig(into);
            Files.createDirectories(target);
            int n = 0;
            try (var s = Files.list(source)) {
                for (Path f : s.toList()) {
                    if (Files.isRegularFile(f) && f.toString().endsWith(suffix)) {
                        Files.copy(f, target.resolve(f.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        n++;
                    }
                }
            }
            return n;
        } catch (Exception e) {
            return 0;
        }
    }
}
