package dev.testkit.gametest;

import dev.testkit.compat.McCompat;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 1410-sim-water-static: sim water stands still everywhere except the Water Board (mod sim-water-static, 2026-10-13:
 * "make it so if there is water it will not update its flow path outside of water board in sim").
 *
 * <p>Two rooms, each built his way ({@code SimBuilder.buildSingleRoom}). In each, on the SERVER, at a free floor spot
 * beside him: first a lone FLOWING water block (level 4, no source) - vanilla drains it within a few ticks - then a
 * water SOURCE with open floor round it - vanilla spreads it. Both written with update flags, so a fluid tick is
 * scheduled either way. Water in a box round the spot is counted before and after 60 ticks.
 *
 * <ul>
 *   <li>Museum (not the Water Board): the flowing block is still there, the count is unchanged, and the mod's own
 *       {@code SimStaticWater.frozenTicks} went up - proof the hook canceled ticks rather than none being scheduled.</li>
 *   <li>Water Board: the flowing block drained, the source spread (several new water blocks), and
 *       {@code SimStaticWater.allowedTicks} went up - the same placement does flow in there, which is also the
 *       positive control showing the Museum half would have spread without the hook.</li>
 * </ul>
 * Sim only, so no GrimAC.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimStaticWaterTests implements FabricClientGameTest {

    private static final String NAME = "1410-sim-water-static";
    private static final String STILL_ROOM = "Museum";
    private static final String FLOW_ROOM = "Water Board";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String BUILDER = "com.killer560.hub.roomsim.SimBuilder";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String STATIC_WATER = "com.killer560.hub.roomsim.SimStaticWater";
    private static final String LIVE_MAP = "com.killer560.hub.livemap.LiveMapFeature";
    private static final String SOURCE_ROOMS =
            ModUnderTest.instanceConfig(Machine.instanceConfigDir(SimEssenceAuraTests.ROOM_INSTANCE), "killer560smod-rooms");
    /** Half-width of the counting box round the spot; a source spreads 7 blocks on a flat floor. */
    private static final int R = 8;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank() || Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (copyDir(SOURCE_ROOMS, "killer560smod-rooms", ".json") < 20
                || copyDir(Path.of(SOURCE_ROOMS).resolveSibling("killer560smod-roomdata").toString(),
                        "killer560smod-roomdata", "") == 0) {
            Scenario.skipped(NAME, "needs room captures and room database (" + SimEssenceAuraTests.ROOM_INSTANCE + ")");
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);
        String still;
        String flow;
        try {
            still = measure(ctx, STILL_ROOM, false);
            println(still);
            flow = measure(ctx, FLOW_ROOM, true);
            println(flow);
        } finally {
            leave(ctx);
        }
        if (!still.startsWith("PASS") || !flow.startsWith("PASS")) {
            throw new AssertionError(still + " | " + flow);
        }
        println("PASS - water held still in " + STILL_ROOM + " and flowed in " + FLOW_ROOM);
    }

    /** One room: build it, find a spot, run the drain and spread checks. */
    private String measure(ClientGameTestContext ctx, String roomName, boolean shouldFlow) {
        String tag = roomName + ": ";
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> ModUnderTest.staticCall(BUILDER, "buildSingleRoom",
                new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, roomName})));
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
        boolean simActive = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SIM_STATE, "isActive"));
        String boxes = ctx.computeOnClient(mc -> String.valueOf(ModUnderTest.staticCall(STATIC_WATER, "describe")));
        println(tag + "standing in room '" + room + "', sim active " + simActive + ", Water Board box(es) " + boxes);
        if (!roomName.equals(room) || !simActive) {
            return "FAIL premise - " + tag + "the live map says he stands in '" + room + "' (sim active " + simActive + ")";
        }
        if (shouldFlow == "none".equals(boxes)) {
            return "FAIL premise - " + tag + "the mod's Water Board box is '" + boxes + "'";
        }

        // A free spot: air with a solid floor, and the same for its four neighbors, so a source has room to spread.
        BlockPos spot = onServer(ctx, (level, sp) -> {
            BlockPos feet = sp.blockPosition();
            for (int d = 2; d <= 6; d++) {
                int[][] offsets = {{d, 0}, {-d, 0}, {0, d}, {0, -d}, {d, d}, {-d, -d}, {d, -d}, {-d, d}};
                for (int[] o : offsets) {
                    BlockPos p = feet.offset(o[0], 0, o[1]);
                    if (open(level, p)) {
                        boolean ok = true;
                        for (Direction dir : Direction.Plane.HORIZONTAL) {
                            ok &= open(level, p.relative(dir));
                        }
                        if (ok) {
                            return p.immutable();
                        }
                    }
                }
            }
            return BlockPos.ZERO;
        });
        if (spot.equals(BlockPos.ZERO)) {
            return "FAIL premise - " + tag + "no open floor spot near him";
        }
        println(tag + "spot " + spot.toShortString());

        // 1. A lone flowing block: drains in vanilla.
        long frozen0 = counter(ctx, "frozenTicks");
        long allowed0 = counter(ctx, "allowedTicks");
        onServer(ctx, (level, sp) -> {
            level.setBlock(spot, Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 4), 3);
            return spot;
        });
        ctx.waitTicks(40);
        boolean stillThere = onServer(ctx, (level, sp) -> !level.getFluidState(spot).isEmpty());

        // 2. A source on open floor: spreads in vanilla.
        int countBefore = onServer(ctx, (level, sp) -> {
            level.setBlock(spot, Blocks.WATER.defaultBlockState(), 3);
            return countWater(level, spot);
        });
        ctx.waitTicks(60);
        int countAfter = onServer(ctx, (level, sp) -> countWater(level, spot));
        long frozen = counter(ctx, "frozenTicks") - frozen0;
        long allowed = counter(ctx, "allowedTicks") - allowed0;
        // Leave the room as it was, so a rebuild or the next scenario does not inherit a flood.
        onServer(ctx, (level, sp) -> {
            level.setBlock(spot, Blocks.AIR.defaultBlockState(), 2 | 16);
            return spot;
        });

        String facts = "flowing block still there after 40 ticks " + stillThere + "; water in box " + countBefore
                + " -> " + countAfter + " after 60 ticks; hook: frozen +" + frozen + ", allowed +" + allowed;
        if (shouldFlow) {
            if (stillThere) {
                return "FAIL - " + tag + "the lone flowing block did not drain in the Water Board - " + facts;
            }
            if (countAfter < countBefore + 4) {
                return "FAIL - " + tag + "the source did not spread in the Water Board - " + facts;
            }
            if (allowed <= 0) {
                return "FAIL - " + tag + "the hook never saw a Water Board water tick - " + facts;
            }
        } else {
            if (!stillThere) {
                return "FAIL - " + tag + "the lone flowing block drained outside the Water Board - " + facts;
            }
            if (countAfter != countBefore) {
                return "FAIL - " + tag + "water spread outside the Water Board - " + facts;
            }
            if (frozen <= 0) {
                return "FAIL - " + tag + "the hook never canceled a tick (nothing proves it ran) - " + facts;
            }
        }
        return "PASS - " + tag + facts;
    }

    private static boolean open(ServerLevel level, BlockPos p) {
        return level.getBlockState(p).isAir() && level.getBlockState(p.above()).isAir()
                && level.getBlockState(p.below()).isSolidRender();
    }

    private static int countWater(ServerLevel level, BlockPos c) {
        int n = 0;
        for (int x = -R; x <= R; x++) {
            for (int y = -3; y <= 1; y++) {
                for (int z = -R; z <= R; z++) {
                    if (!level.getFluidState(c.offset(x, y, z)).isEmpty()) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    private static long counter(ClientGameTestContext ctx, String method) {
        return ctx.computeOnClient(mc -> ((Number) ModUnderTest.staticCall(STATIC_WATER, method)).longValue());
    }

    private interface ServerTask<T> {
        T run(ServerLevel level, ServerPlayer sp);
    }

    /** Runs on the integrated server thread and waits for the answer without blocking it (see CLAUDE.md). */
    private static <T> T onServer(ClientGameTestContext ctx, ServerTask<T> task) {
        AtomicReference<Object> out = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                try {
                    out.set(task.run(server.overworld(), server.getPlayerList().getPlayer(uuid)));
                } catch (Throwable t) {
                    out.set(t);
                }
            });
        });
        ctx.waitFor(mc -> out.get() != null, 200);
        Object v = out.get();
        if (v instanceof Throwable t) {
            throw new AssertionError("server task threw", t);
        }
        @SuppressWarnings("unchecked")
        T result = (T) v;
        return result;
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

    private static void println(String line) {
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
