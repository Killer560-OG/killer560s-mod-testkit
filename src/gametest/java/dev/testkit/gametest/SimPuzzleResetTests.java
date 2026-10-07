package dev.testkit.gametest;

import dev.testkit.compat.McCompat;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The sim's player puzzle-reset rules (mod 35a663ba, {@code SimPuzzles.resetForPlayer}).
 *
 * <p>killer560 (2026-10-06): "you shouldnt be able to reset completed puzzles only failed ones"; Water Board can
 * never be reset; Boulder resets whether or not it failed. An Architect's First Draft is used up only when
 * something was reset. Both the item and {@code /simpuzzle reset} go through {@code resetForPlayer}.
 *
 * <p>"Failed" is {@code SimRoomState.failedRooms()}, so a case marks rooms failed through {@code markFailed} (what
 * a real failing puzzle calls) rather than playing a puzzle to its failure. The returned count is what each case
 * asserts: it is one per puzzle reset, so 0 means nothing was reset. Every case first proves its precondition (the
 * failed list reads as set, Boulder is or is not built), so a green run means the rule was exercised.
 */
public class SimPuzzleResetTests implements FabricClientGameTest {

    private static final String NAME = "110-sim-puzzle-reset";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String PUZZLES = "com.killer560.hub.roomsim.puzzles.SimPuzzles";
    private static final String ROOM_STATE = "com.killer560.hub.roomsim.SimRoomState";
    private static final String ITEMS = "com.killer560.hub.roomsim.SimItems";
    private static final String BUILDER = "com.killer560.hub.roomsim.SimBuilder";

    private final List<String> failures = new ArrayList<>();

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff(
                "com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));

        // Boulder builds from the real layouts in killer560smod-roomdata, and builds nothing without them.
        int data = SimPuzzleTests.copyRoomData();
        System.out.println("[" + NAME + "] copied " + data + " room-data file(s)");
        if (data == 0) {
            throw new AssertionError("the puzzle solution files are not on this machine, so Boulder cannot be "
                    + "built and the Boulder rules cannot be tested");
        }
        ctx.runOnClient(mc -> Scenario.loadRoomDatabaseNow());
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(
                "com.killer560.hub.roomdatabase.RoomDatabase", "isReady"));

        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter",
                new Class<?>[]{String.class}, new Object[]{"gametest"}));
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() ->
                ModUnderTest.staticCall(BUILDER, "buildFlatTest",
                        new Class<?>[]{net.minecraft.client.Minecraft.class}, new Object[]{mc})));
        ctx.waitFor(mc -> mc.level != null);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitTicks(60);

        try {
            run(ctx);
        } finally {
            ctx.runOnClient(mc -> ModUnderTest.staticCall(PUZZLES, "resetAll"));
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
        }

        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " puzzle-reset rule(s) broken:\n    "
                    + String.join("\n    ", failures));
        }
        System.out.println("[" + NAME + "] PASS - only failed puzzles reset, Water Board never, Boulder when built, "
                + "and a draft is used up only when something reset");
    }

    private void run(ClientGameTestContext ctx) {
        // 1. Nothing failed, Boulder not built: nothing to reset (a COMPLETED/untouched Quiz is not touched).
        clean(ctx);
        check("nothing failed, no boulder", ctx, resetForPlayer(ctx), 0);

        // 2. Only a failed puzzle resets: Quiz failed -> exactly one reset, and it stops being failed.
        clean(ctx);
        markFailed(ctx, "Quiz");
        expectFailed(ctx, "Quiz failed (precondition)", List.of("Quiz"));
        check("Quiz failed", ctx, resetForPlayer(ctx), 1);
        expectFailed(ctx, "Quiz no longer failed after its reset", List.of());

        // 3. Water Board never resets, and stays failed.
        clean(ctx);
        markFailed(ctx, "Water Board");
        expectFailed(ctx, "Water Board failed (precondition)", List.of("Water Board"));
        check("Water Board failed", ctx, resetForPlayer(ctx), 0);
        expectFailed(ctx, "Water Board still failed", List.of("Water Board"));

        // 4. Ice Fill and Ice Path are never marked failed, so they never reset.
        clean(ctx);
        markFailed(ctx, "Ice Fill");
        markFailed(ctx, "Ice Path");
        expectFailed(ctx, "Ice rooms cannot be failed (precondition)", List.of());
        check("Ice Fill and Ice Path 'failed'", ctx, resetForPlayer(ctx), 0);

        // 5. Boulder resets whenever it is built, failed or not.
        clean(ctx);
        buildBoulder(ctx);
        int wiped = disturbBoulder(ctx);
        ctx.waitTicks(20);
        int idle = restoredCount(ctx);
        check("Boulder built, not failed", ctx, resetForPlayer(ctx), 1);
        ctx.waitTicks(20);
        int back = restoredCount(ctx);
        System.out.println("[" + NAME + "] Boulder: disturbed " + wiped + " block(s), " + idle
                + " back with no reset (control), " + back + " back after the reset");
        if (wiped <= 0) {
            failures.add("Boulder: the test could not disturb any block, so its restore cannot be shown");
        } else if (idle != 0) {
            failures.add("Boulder: " + idle + " block(s) came back with no reset, so the restore proves nothing");
        } else if (back <= 0) {
            failures.add("Boulder: reset reported 1 but none of the " + wiped + " disturbed block(s) came back");
        }

        // 6. A mix: Quiz + Water Board failed, Boulder built -> Quiz and Boulder only.
        clean(ctx);
        buildBoulder(ctx);
        markFailed(ctx, "Quiz");
        markFailed(ctx, "Water Board");
        expectFailed(ctx, "mix failed (precondition)", List.of("Quiz", "Water Board"));
        check("Quiz+Water failed, Boulder built", ctx, resetForPlayer(ctx), 2);
        expectFailed(ctx, "only Water Board left failed", List.of("Water Board"));

        // 7. /simpuzzle reset as typed does the same: it clears a failed Quiz.
        clean(ctx);
        markFailed(ctx, "Quiz");
        expectFailed(ctx, "Quiz failed for the command (precondition)", List.of("Quiz"));
        ctx.runOnClient(mc -> mc.player.connection.sendCommand("simpuzzle reset"));
        ctx.waitTicks(20);
        expectFailed(ctx, "/simpuzzle reset cleared the failed Quiz", List.of());

        // 8. The Architect's First Draft: kept when nothing resets, used up when something does.
        clean(ctx);
        int kept = useDraft(ctx);
        if (kept != 1) {
            failures.add("draft with nothing to reset: stack went 1 -> " + kept + " (must be kept)");
        }
        System.out.println("[" + NAME + "] draft, nothing to reset: count 1 -> " + kept);
        markFailed(ctx, "Quiz");
        expectFailed(ctx, "Quiz failed for the draft (precondition)", List.of("Quiz"));
        int used = useDraft(ctx);
        if (used != 0) {
            failures.add("draft with a failed Quiz: stack went 1 -> " + used + " (must be used up)");
        }
        System.out.println("[" + NAME + "] draft, Quiz failed: count 1 -> " + used);
        expectFailed(ctx, "the draft reset the failed Quiz", List.of());
    }

    private void clean(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            ModUnderTest.staticCall(ROOM_STATE, "clear");
            ModUnderTest.staticCall(PUZZLES, "forget",
                    new Class<?>[]{String.class}, new Object[]{"boulder"});
        });
    }

    private void markFailed(ClientGameTestContext ctx, String room) {
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_STATE, "markFailed",
                new Class<?>[]{String.class}, new Object[]{room}));
    }

    @SuppressWarnings("unchecked")
    private void expectFailed(ClientGameTestContext ctx, String what, List<String> want) {
        AtomicReference<List<String>> got = new AtomicReference<>();
        ctx.runOnClient(mc -> got.set(new ArrayList<>(
                (List<String>) ModUnderTest.staticCall(ROOM_STATE, "failedRooms"))));
        List<String> have = new ArrayList<>(got.get());
        List<String> expected = new ArrayList<>(want);
        have.sort(null);
        expected.sort(null);
        if (!have.equals(expected)) {
            failures.add(what + ": failed rooms are " + have + ", expected " + expected);
        }
        System.out.println("[" + NAME + "] " + what + ": failed rooms " + have);
    }

    private int resetForPlayer(ClientGameTestContext ctx) {
        AtomicReference<Integer> n = new AtomicReference<>();
        ctx.runOnClient(mc -> n.set((Integer) ModUnderTest.staticCall(PUZZLES, "resetForPlayer")));
        return n.get();
    }

    private void check(String what, ClientGameTestContext ctx, int got, int want) {
        System.out.println("[" + NAME + "] " + what + ": reset " + got + " (want " + want + ")");
        if (got != want) {
            failures.add(what + ": reset " + got + " puzzle(s), expected " + want);
        }
    }

    private final java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> wipedBlocks =
            new java.util.HashMap<>();

    /**
     * Wipes (server side) every block near the player that is not the most common block around (the flat floor),
     * remembering each, and returns how many it wiped. {@link #restoredCount} then says how many came back. Only
     * boxes and buttons are laid by a reset, so the case asserts "some, and none without a reset", not "all".
     */
    private int disturbBoulder(ClientGameTestContext ctx) {
        AtomicReference<Integer> n = new AtomicReference<>();
        wipedBlocks.clear();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            BlockPos c = mc.player.blockPosition();
            server.execute(() -> {
                var level = server.overworld();
                java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> all = new java.util.HashMap<>();
                java.util.Map<net.minecraft.world.level.block.state.BlockState, Integer> freq = new java.util.HashMap<>();
                for (int dx = -30; dx <= 30; dx++) {
                    for (int dz = -30; dz <= 30; dz++) {
                        for (int y = c.getY() - 3; y <= c.getY() + 12; y++) {
                            BlockPos p = new BlockPos(c.getX() + dx, y, c.getZ() + dz);
                            var st = level.getBlockState(p);
                            if (!st.isAir()) {
                                all.put(p, st);
                                freq.merge(st, 1, Integer::sum);
                            }
                        }
                    }
                }
                var common = freq.entrySet().stream().max(java.util.Map.Entry.comparingByValue())
                        .map(java.util.Map.Entry::getKey).orElse(null);
                int count = 0;
                for (var e : all.entrySet()) {
                    if (!e.getValue().equals(common)) {
                        wipedBlocks.put(e.getKey(), e.getValue());
                        level.setBlock(e.getKey(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
                        count++;
                    }
                }
                n.set(count);
            });
        });
        ctx.waitFor(mc -> n.get() != null);
        return n.get();
    }

    private int restoredCount(ClientGameTestContext ctx) {
        AtomicReference<Integer> n = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                var level = server.overworld();
                int count = 0;
                for (var e : wipedBlocks.entrySet()) {
                    if (level.getBlockState(e.getKey()).equals(e.getValue())) {
                        count++;
                    }
                }
                n.set(count);
            });
        });
        ctx.waitFor(mc -> n.get() != null);
        return n.get();
    }

    /** Builds Boulder and proves it is up, so the "Boulder resets" cases cannot pass on an arena that never was. */
    private void buildBoulder(ClientGameTestContext ctx) {
        AtomicReference<BlockPos> where = new AtomicReference<>();
        ctx.runOnClient(mc -> where.set(mc.player.blockPosition().relative(mc.player.getDirection(), 4)));
        ctx.runOnClient(mc -> ModUnderTest.staticCall(PUZZLES, "buildAt",
                new Class<?>[]{net.minecraft.client.Minecraft.class, String.class, BlockPos.class},
                new Object[]{mc, "boulder", where.get()}));
        ctx.waitTicks(40);
        AtomicReference<Boolean> built = new AtomicReference<>();
        ctx.runOnClient(mc -> built.set((Boolean) ModUnderTest.staticCall(
                "com.killer560.hub.roomsim.puzzles.SimBoulderPuzzle", "isBuilt")));
        if (!built.get()) {
            throw new AssertionError("Boulder did not build, so its reset rule cannot be tested");
        }
    }

    /**
     * Right-clicks an Architect's First Draft on the server (the server half of the use packet, exactly what
     * {@code SimAbilities} calls) with one in the hand, and returns the stack size once the client has answered.
     */
    private int useDraft(ClientGameTestContext ctx) {
        AtomicReference<ItemStack> stackRef = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayers().get(0);
                ItemStack stack = (ItemStack) ModUnderTest.staticCall(ITEMS, "build",
                        new Class<?>[]{String.class}, new Object[]{"ARCHITECT_FIRST_DRAFT"});
                sp.getInventory().setItem(0, stack);
                sp.getInventory().setSelectedSlot(0);
                stackRef.set(stack);
                ModUnderTest.staticCall(ITEMS, "useOnServer",
                        new Class<?>[]{net.minecraft.server.level.ServerPlayer.class, String.class,
                                net.minecraft.world.phys.BlockHitResult.class},
                        new Object[]{sp, "ARCHITECT_FIRST_DRAFT", null});
            });
        });
        ctx.waitFor(mc -> stackRef.get() != null);
        // client task, then the server task it queues
        ctx.waitTicks(40);
        ItemStack stack = stackRef.get();
        return stack.getCount();
    }
}
