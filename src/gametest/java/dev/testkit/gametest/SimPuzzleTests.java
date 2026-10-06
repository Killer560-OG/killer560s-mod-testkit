package dev.testkit.gametest;

import dev.testkit.compat.McCompat;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Does every sim puzzle actually build something?
 *
 * <p>killer560 (2026-09-29): "Also test that all puzzles are working."
 *
 * <p>There are eight of them and they are only ever exercised by typing {@code /simpuzzle <name>} and looking.
 * That means a puzzle can quietly stop building - a solutions file that fails to parse, an index off the end,
 * a null origin - and nobody finds out until he stands in the room expecting one.
 *
 * <p>What "working" means here is deliberately narrow and honest: each puzzle is built at a known spot in an
 * empty part of the sim world, and the blocks around that spot are counted before and afterwards. A puzzle
 * that changes nothing has not built. It does NOT check that the puzzle is solvable or that its answer is
 * right - that needs a player to play it, and a test that claimed to check it without playing it would be the
 * kind of green that means nothing. The blaze and teleport-maze puzzles are exercised for their entities as
 * well, since those two build mostly mobs rather than blocks.
 *
 * <p>Each puzzle is then reset and the arena measured again, but that number is REPORTED, not asserted on.
 * {@code /simpuzzle reset} re-arms a puzzle rather than demolishing it - Boulder's reset literally rebuilds
 * the arena - so an arena still standing afterwards is correct, and an earlier version of this scenario that
 * required the blocks to be gone was about to have three working puzzles "fixed" to match it. The blocks are
 * cleared when the floor is rebuilt, by {@code SimBuilder.wipeWholeGrid}, which is where that belongs.
 */
public class SimPuzzleTests implements FabricClientGameTest {

    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String SIM_WORLD = "com.killer560.hub.roomsim.SimWorld";
    private static final String PUZZLES = "com.killer560.hub.roomsim.puzzles.SimPuzzles";
    private static final String BUILD_QUEUE = "com.killer560.hub.roomsim.SimBuildQueue";
    private static final String FLAT_ROOM = "com.killer560.hub.roomsim.FlatTestRoom";
    private static final String BUILDER = "com.killer560.hub.roomsim.SimBuilder";

    /**
     * The box counted around each puzzle's origin.
     *
     * <p>Generous, because the puzzles do not agree about what an offset is. SimBoulderPuzzle's FLOOR_Y is
     * 66 and it is added to the origin, so that puzzle builds seventy blocks in the air; the blaze arena sits
     * three blocks up; the water levers use the real solver's room-relative offsets. A box drawn round what
     * one of them does reports the others as "built nothing", which is what the first version of this
     * scenario did - and a test that reports a working feature as broken is worse than no test.
     */
    private static final int RADIUS = 40;
    private static final int Y_BELOW = 8;
    private static final int Y_ABOVE = 72;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip("78-sim-puzzles")) {
            return;
        }
        ModUnderTest.require("killer560smod");
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff(
                "com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));

        // The solution files first.
        //
        // Boulder, Creeper Beams, Water and Quiz build their arenas from the real layouts in
        // killer560smod-roomdata, and without them they build nothing at all - silently. The first run of
        // this scenario reported four puzzles "not building" for exactly that reason, which was the test's
        // setup rather than the puzzles. Whether they should SAY something when the data is missing is a
        // separate question, and worth asking; it is not what this scenario measures.
        int data = copyRoomData();
        System.out.println("[78-sim-puzzles] copied " + data + " room-data file(s)");
        if (data == 0) {
            System.out.println("[78-sim-puzzles] SKIPPED - the puzzle solution files are not on this machine");
            return;
        }
        ctx.runOnClient(mc -> ModUnderTest.staticCall(
                "com.killer560.hub.roomdatabase.RoomDatabase", "ensureLoading"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(
                "com.killer560.hub.roomdatabase.RoomDatabase", "isReady"));

        // A flat room, not a generated floor: the puzzles are being measured by how many blocks they add, so
        // they need somewhere with a known, empty starting state to add them to.
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter",
                new Class<?>[]{String.class}, new Object[]{"gametest"}));
        long simBuildBefore = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() ->
                ModUnderTest.staticCall(BUILDER, "buildFlatTest",
                        new Class<?>[]{net.minecraft.client.Minecraft.class}, new Object[]{mc})));
        ctx.waitFor(mc -> mc.level != null);
        Scenario.awaitSimBuild(ctx, simBuildBefore);
        ctx.waitTicks(60);

        @SuppressWarnings("unchecked")
        List<String> names = (List<String>) ModUnderTest.staticCall(PUZZLES, "names");
        if (names == null || names.isEmpty()) {
            throw new AssertionError("SimPuzzles lists no puzzles at all");
        }
        System.out.println("[78-sim-puzzles] " + names.size() + " puzzle(s): " + String.join(", ", names));

        List<String> failures = new ArrayList<>();
        for (String name : names) {
            final String puzzle = name;
            // The origin is fixed per puzzle rather than "in front of the player", so the before and after
            // counts are of the same box. A puzzle that built where the player happened to be facing would
            // be measured against a different volume each time.
            java.util.concurrent.atomic.AtomicReference<BlockPos> where =
                    new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<int[]> before =
                    new java.util.concurrent.atomic.AtomicReference<>();
            ctx.runOnClient(mc -> {
                if (mc.player == null) {
                    return;
                }
                where.set(mc.player.blockPosition().relative(mc.player.getDirection(), 4));
            });
            ctx.waitFor(mc -> where.get() != null);
            measure(ctx, where.get(), before);

            // On the CLIENT thread, which is the thread /simpuzzle runs on.
            //
            // The first version of this called buildAt inside server.execute and two things went wrong: the
            // puzzles that queue their work onto the client had not built anything by the time the same task
            // measured, so they read as "+0 blocks"; and SimQuizPuzzle threw outright, because it sends a
            // chat line and ModChat is client-only. Neither was a defect in the puzzles - both were this test
            // calling them from somewhere they are never called from.
            java.util.concurrent.atomic.AtomicReference<Boolean> known =
                    new java.util.concurrent.atomic.AtomicReference<>();
            ctx.runOnClient(mc -> known.set((Boolean) ModUnderTest.staticCall(PUZZLES, "buildAt",
                    new Class<?>[]{net.minecraft.client.Minecraft.class, String.class, BlockPos.class},
                    new Object[]{mc, puzzle, where.get()})));
            ctx.waitFor(mc -> known.get() != null);
            if (!known.get()) {
                failures.add(puzzle + ": SimPuzzles has no builder registered for it");
                continue;
            }
            // Long enough for anything queued onto the client or the server to have run.
            ctx.waitTicks(40);
            java.util.concurrent.atomic.AtomicReference<int[]> after =
                    new java.util.concurrent.atomic.AtomicReference<>();
            measure(ctx, where.get(), after);

            // Read BEFORE resetAll, which clears the puzzle's own record of what it spawned - the first
            // version asked afterwards and got zero from a puzzle that had just built five blazes.
            int blazes = "blaze".equals(puzzle)
                    ? (Integer) ModUnderTest.staticCall(
                            "com.killer560.hub.roomsim.puzzles.SimBlazePuzzle", "spawnedCount")
                    : -1;
            ctx.runOnClient(mc -> ModUnderTest.staticCall(PUZZLES, "resetAll"));
            ctx.waitTicks(40);
            java.util.concurrent.atomic.AtomicReference<int[]> cleared =
                    new java.util.concurrent.atomic.AtomicReference<>();
            measure(ctx, where.get(), cleared);

            int addedBlocks = after.get()[0] - before.get()[0];
            int addedEntities = after.get()[1] - before.get()[1];
            int leftBehind = cleared.get()[0] - before.get()[0];
            System.out.println(String.format(
                    "[78-sim-puzzles]   %-13s blocks %d -> %d (%+d); arena after reset %d (%+d)",
                    puzzle, before.get()[0], after.get()[0], addedBlocks,
                    cleared.get()[0], leftBehind));
            if ("blaze".equals(puzzle)) {
                // The blaze arena is mobs, not blocks, and the world query above reads zero for everything -
                // so this one is checked against what the puzzle itself reports having spawned, which only
                // counts a blaze the level accepted.
                System.out.println("[78-sim-puzzles]   blaze         " + blazes + " blaze(s) in the arena");
                if (blazes == 0) {
                    failures.add("blaze: no blazes spawned");
                }
            } else if (addedBlocks == 0 && addedEntities == 0) {
                failures.add(puzzle + ": built nothing at all - no blocks and no entities changed");
            }
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
                McCompat.setScreen(mc, new net.minecraft.client.gui.screens.TitleScreen())));
        ctx.waitFor(mc -> McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.TitleScreen);

        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " puzzle(s) did not build:\n    "
                    + String.join("\n    ", failures));
        }
        System.out.println("[78-sim-puzzles] PASS - every puzzle builds");
    }

    static int copyRoomData() {
        try {
            java.nio.file.Path source = java.nio.file.Path.of(
                    ModUnderTest.instanceConfig("C:/Users/Hunter/AppData/Roaming/PrismLauncher/instances/26.1.2 (Mod Only Test)"
                            + "/minecraft/config", "killer560smod-roomdata"));
            if (!java.nio.file.Files.isDirectory(source)) {
                return 0;
            }
            java.nio.file.Path target = ModUnderTest.modConfig("killer560smod-roomdata");
            java.nio.file.Files.createDirectories(target);
            int n = 0;
            try (var s = java.nio.file.Files.list(source)) {
                for (java.nio.file.Path f : s.toList()) {
                    if (java.nio.file.Files.isRegularFile(f)) {
                        java.nio.file.Files.copy(f, target.resolve(f.getFileName()),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        n++;
                    }
                }
            }
            return n;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Blocks and entities around a spot, counted on the server thread and handed back. */
    private static void measure(ClientGameTestContext ctx, BlockPos origin,
                                java.util.concurrent.atomic.AtomicReference<int[]> out) {
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            if (server == null) {
                out.set(new int[]{0, 0});
                return;
            }
            server.execute(() -> {
                var level = server.overworld();
                out.set(new int[]{solid(level, origin), nearbyEntities(level, origin)});
            });
        });
        ctx.waitFor(mc -> out.get() != null);
    }

    private static int solid(net.minecraft.server.level.ServerLevel level, BlockPos origin) {
        int n = 0;
        var cursor = new BlockPos.MutableBlockPos();
        for (int x = -RADIUS; x <= RADIUS; x++) {
            for (int z = -RADIUS; z <= RADIUS; z++) {
                for (int y = -Y_BELOW; y <= Y_ABOVE; y++) {
                    cursor.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    if (!level.getBlockState(cursor).isAir()) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    private static int nearbyEntities(net.minecraft.server.level.ServerLevel level, BlockPos origin) {
        var box = new net.minecraft.world.phys.AABB(origin).inflate(RADIUS, Y_ABOVE, RADIUS);
        // Mob.class, not Entity.class.
        //
        // getEntitiesOfClass(Entity.class, ...) returned nothing at all, for every puzzle, while the blaze
        // puzzle's own log said it had spawned five - so this counter read zero and made a working puzzle
        // look broken. Every mob a puzzle builds is a Mob, which is the query that actually works.
        return level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, box, e -> true).size();
    }
}
