package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.Blocks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Does skipping chunk sections find the same levers as reading every block?
 *
 * <p>Secret Waypoints scanned a room's whole volume across 41 y-levels once a second on the client thread -
 * 44,649 block reads for a 1x1 room and about 173,000 for a 1x4. On 2026-09-29 that was changed to ask each
 * chunk section's palette whether a lever can be inside it at all and skip the section when it cannot, which
 * in a dungeon skips nearly everything.
 *
 * <p>A faster scan that misses a lever is worse than the slow one it replaced, so this runs BOTH algorithms
 * over the same bounds on a real generated floor and requires them to agree exactly. It asserts on his own
 * captured rooms rather than a synthetic arena, because the thing being trusted is that real dungeon
 * geometry puts levers where the palette check still finds them.
 */
public class LeverScanTests implements FabricClientGameTest {

    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String BUILD_QUEUE = "com.killer560.hub.roomsim.SimBuildQueue";

    private static final String SOURCE_ROOMS =
            ModUnderTest.instanceConfig("C:/Users/Hunter/AppData/Roaming/PrismLauncher/instances/26.1.2 (Mod Only Test)/minecraft/config", "killer560smod-rooms");

    /**
     * The y band the rooms sit in, BEFORE the floor's own shift is added.
     *
     * <p>Since 2026-09-29 a built floor is moved bodily so its lowest block sits just above the void - about
     * 123 blocks down normally and 185 UP on a floor with Higher Blaze on it. A band hard-coded to 68..108
     * lands in empty air and this scenario reported "no levers anywhere on the floor", which is the test
     * being wrong rather than the optimisation.
     */
    private static final int BASE_MIN_Y = 68;
    private static final int BASE_MAX_Y = 108;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip("77-lever-scan")) {
            return;
        }
        ModUnderTest.require("killer560smod");
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff(
                "com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (copyRealRooms() < 20) {
            System.out.println("[77-lever-scan] SKIPPED - needs his real rooms");
            return;
        }
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"));
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter",
                new Class<?>[]{String.class}, new Object[]{"gametest"}));
        long simBuildBefore = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> {
            Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
            ModUnderTest.staticCall(FLOOR_GEN, "generate",
                    new Class<?>[]{Minecraft.class, floor.getClass(), int.class, int.class},
                    new Object[]{mc, floor, 3, 4});
        }));
        ctx.waitFor(mc -> mc.level != null);
        Scenario.awaitSimBuild(ctx, simBuildBefore);
        ctx.waitTicks(60);

        // Both scans, on the server, over the whole grid - which is every room at once, a far harsher test
        // than the single room the feature scans.
        int shift = (Integer) ModUnderTest.staticCall(
                "com.killer560.hub.roomsim.SimAltitude", "offset");
        final int minY = BASE_MIN_Y + shift;
        final int maxY = BASE_MAX_Y + shift;
        System.out.println("[77-lever-scan] the floor is shifted " + shift + ", scanning y "
                + minY + ".." + maxY);
        java.util.concurrent.atomic.AtomicReference<long[]> result =
                new java.util.concurrent.atomic.AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            if (server == null) {
                return;
            }
            server.execute(() -> {
                var level = server.overworld();
                int x0 = -201;
                int x1 = -9;
                int z0 = -201;
                int z1 = -9;

                // A: every block, the original.
                List<Long> brute = new ArrayList<>();
                long bruteReads = 0;
                var cursor = new net.minecraft.core.BlockPos.MutableBlockPos();
                for (int x = x0; x <= x1; x++) {
                    for (int z = z0; z <= z1; z++) {
                        for (int y = minY; y <= maxY; y++) {
                            cursor.set(x, y, z);
                            bruteReads++;
                            if (level.getBlockState(cursor).getBlock() == Blocks.LEVER) {
                                brute.add(cursor.asLong());
                            }
                        }
                    }
                }

                // B: skip sections whose palette cannot hold a lever.
                List<Long> fast = new ArrayList<>();
                long fastReads = 0;
                for (int cx = x0 >> 4; cx <= (x1 >> 4); cx++) {
                    for (int cz = z0 >> 4; cz <= (z1 >> 4); cz++) {
                        if (!level.hasChunk(cx, cz)) {
                            continue;
                        }
                        var chunk = level.getChunk(cx, cz);
                        int bx0 = Math.max(x0, cx << 4);
                        int bx1 = Math.min(x1, (cx << 4) + 15);
                        int bz0 = Math.max(z0, cz << 4);
                        int bz1 = Math.min(z1, (cz << 4) + 15);
                        var sections = chunk.getSections();
                        for (int i = 0; i < sections.length; i++) {
                            int secMinY = chunk.getSectionYFromSectionIndex(i) << 4;
                            int secMaxY = secMinY + 15;
                            if (secMaxY < minY || secMinY > maxY) {
                                continue;
                            }
                            var section = sections[i];
                            if (section == null || section.hasOnlyAir()
                                    || !section.maybeHas(st -> st.is(Blocks.LEVER))) {
                                continue;
                            }
                            int y0 = Math.max(minY, secMinY);
                            int y1 = Math.min(maxY, secMaxY);
                            for (int x = bx0; x <= bx1; x++) {
                                for (int z = bz0; z <= bz1; z++) {
                                    for (int y = y0; y <= y1; y++) {
                                        fastReads++;
                                        if (section.getBlockState(x & 15, y & 15, z & 15)
                                                .getBlock() == Blocks.LEVER) {
                                            fast.add(new net.minecraft.core.BlockPos(x, y, z).asLong());
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                java.util.Collections.sort(brute);
                java.util.Collections.sort(fast);
                long same = brute.equals(fast) ? 1 : 0;
                result.set(new long[]{brute.size(), fast.size(), same, bruteReads, fastReads});
            });
        });
        ctx.waitFor(mc -> result.get() != null);
        long[] r = result.get();
        System.out.println(String.format(
                "[77-lever-scan] brute force found %d lever(s) in %d block read(s); "
                        + "section-skipping found %d in %d (%.1f%% of the reads)",
                r[0], r[3], r[1], r[4], 100.0 * r[4] / Math.max(1, r[3])));

        if (r[0] == 0) {
            throw new AssertionError("no levers anywhere on the floor, so nothing was compared - this "
                    + "assertion would pass vacuously and prove nothing about the optimisation");
        }
        if (r[2] != 1) {
            throw new AssertionError("the two scans disagree: brute force found " + r[0]
                    + " lever(s), section-skipping found " + r[1]
                    + " - the faster scan is missing levers, which is worse than being slow");
        }

        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "leave"));
        ctx.runOnClient(mc -> mc.execute(() -> {
            if (mc.level != null) {
                mc.level.disconnect(net.minecraft.network.chat.Component.literal("scenario over"));
                mc.disconnectWithSavingScreen();
            }
        }));
        ctx.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null);
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> mc.execute(() ->
                mc.setScreen(new net.minecraft.client.gui.screens.TitleScreen())));
        ctx.waitFor(mc -> mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen);
        System.out.println("[77-lever-scan] PASS - both scans agree, exactly");
    }

    private static int copyRealRooms() {
        try {
            Path source = Path.of(SOURCE_ROOMS);
            if (!Files.isDirectory(source)) {
                return 0;
            }
            Path target = ModUnderTest.modConfig("killer560smod-rooms");
            Files.createDirectories(target);
            List<Path> files = new ArrayList<>();
            try (var s = Files.list(source)) {
                s.filter(p -> p.toString().endsWith(".json")).forEach(files::add);
            }
            for (Path f : files) {
                Files.copy(f, target.resolve(f.getFileName()),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return files.size();
        } catch (Exception e) {
            return 0;
        }
    }
}
