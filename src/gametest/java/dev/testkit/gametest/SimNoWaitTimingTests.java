package dev.testkit.gametest;

import dev.testkit.gametest.PuzzleTimingExtra.Pair;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * {@code 93-solve-*-timing} (mod branch puzzles-nowait-1015): Auto Water's no-wait rule (water-late-1015) applied to every
 * other auto that warps or clicks while holding its free camera. Each case plays the room exactly as its plain
 * {@code 93-solve-*} case does ({@link SimPuzzleSolveTests.Base}: same load, items, premise checks and sim verdict) and
 * adds {@link PuzzleTimingExtra}, which stamps every click, use, sneak and landing with its client tick and judges:
 * click -> use, sneak -> warp use, landing -> next action, and ready -> click must each be 1 tick, and the puzzle autos'
 * {@code TurnFirst} must never wait on the auto's own camera. Which of those a room must have measured is listed per
 * case: a room that never clicks a block has no click pairs to judge.
 */
public final class SimNoWaitTimingTests {

    private SimNoWaitTimingTests() {
    }

    private static final String MOD = "com.killer560.hub.";

    // ------------------------------------------------------------------ probes ("ready" moments)

    /** Ice Path: the silverfish stopping is what makes the next shot possible. */
    private static String icePathProbe(Minecraft mc) {
        Object moving = ModUnderTest.staticCall(MOD + "puzzlesolvers.IcePathSolverFeature", "isSilverfishMoving");
        return Boolean.TRUE.equals(moving) ? "sliding" : "still";
    }

    /** Tic Tac Toe: the solver's next move appearing (the computer's reply read) is when the click becomes possible. */
    private static String ticTacToeProbe(Minecraft mc) {
        Object best = ModUnderTest.staticCall(MOD + "puzzlesolvers.TicTacToeSolverFeature", "getBestMove");
        String move = best == null ? "no move" : "move " + ((BlockPos) best).toShortString();
        // The secret chest seen open (the auto's own test, AutoTicTacToe.chestTaken): what the warp back waits for - the
        // server's answer to Secret Aura's click, a round trip after it.
        try {
            Class<?> auto = Class.forName(MOD + "autopuzzles.AutoTicTacToe");
            var m = auto.getDeclaredMethod("chestTaken", Minecraft.class);
            m.setAccessible(true);
            if (Boolean.TRUE.equals(m.invoke(null, mc))) {
                return move + ", secret chest open";
            }
        } catch (ReflectiveOperationException e) {
            return move + ", chestTaken unreadable";
        }
        return move;
    }

    /** Ice Fill: the tile under him turning packed (the server registered it) frees a section's first hop. */
    private static String iceFillProbe(Minecraft mc) {
        BlockPos under = mc.player.blockPosition().below();
        return mc.level.getBlockState(under).is(net.minecraft.world.level.block.Blocks.PACKED_ICE) ? "packed" : "not packed";
    }

    /** Blaze: one fewer blaze listed ends a volley's wait. */
    private static String blazeProbe(Minecraft mc) {
        Object list = ModUnderTest.staticCall(MOD + "puzzlesolvers.BlazeSolverFeature", "getOrderedBlazes");
        String out = "blazes " + (list instanceof List<?> l ? l.size() : -1);
        // The secret chest only exists once the room is done (the sim puts it there on the kill): the auto's click on it
        // can go no earlier, so its appearing is a ready moment - not the auto's wait.
        try {
            var m = Class.forName(MOD + "autopuzzles.AutoBlaze").getDeclaredMethod("testSecretPos");
            m.setAccessible(true);
            BlockPos[] pos = (BlockPos[]) m.invoke(null);
            if (pos != null && pos[0] != null && mc.level.getBlockState(pos[0]).getBlock()
                    instanceof net.minecraft.world.level.block.ChestBlock) {
                out += ", secret chest there";
            }
        } catch (ReflectiveOperationException e) {
            out += ", secret unreadable";
        }
        return out + VolleyArrows.state(mc);
    }

    /**
     * The volley's own arrows, watched by the test and not by the mod: every arrow that appears within 4 blocks of his
     * eye in its first 4 ticks is one of his, and it has ENDED once the client no longer has it (the server removed it -
     * a hit, or BlazeMiss refusing one), it has stopped moving (stuck in a block), or it is 40 blocks out. "volley ended
     * #n" is the moment the volley's result is known on the client; the auto's next shot, or its chest click, is judged
     * from it (no fixed margin, mod blaze-event-1015). Main waited the simulated flight plus 4 ticks.
     */
    static final class VolleyArrows {
        private static final java.util.Map<Integer, net.minecraft.world.phys.Vec3> FLYING = new java.util.HashMap<>();
        private static int volleys;
        private static net.minecraft.client.multiplayer.ClientLevel level;

        static String state(Minecraft mc) {
            if (mc.level != level) {
                level = mc.level;
                FLYING.clear();
                volleys = 0;
            }
            net.minecraft.world.phys.Vec3 eye = mc.player.getEyePosition();
            boolean fresh = false;
            for (var a : mc.level.getEntitiesOfClass(net.minecraft.world.entity.projectile.arrow.AbstractArrow.class,
                    new net.minecraft.world.phys.AABB(eye, eye).inflate(4.0), a -> a.tickCount <= 4)) {
                if (!FLYING.containsKey(a.getId()) && !SEEN.contains(a.getId())) {
                    if (FLYING.isEmpty()) {
                        fresh = true;
                    }
                    FLYING.put(a.getId(), a.position());
                    SEEN.add(a.getId());
                }
            }
            if (fresh) {
                volleys++;
            }
            FLYING.entrySet().removeIf(e -> {
                net.minecraft.world.entity.Entity a = mc.level.getEntity(e.getKey());
                if (a == null || a.isRemoved() || a.position().distanceTo(eye) > 40.0
                        || a.position().distanceToSqr(e.getValue()) < 1.0e-4) {
                    return true;
                }
                e.setValue(a.position());
                return false;
            });
            if (volleys == 0) {
                return "";
            }
            return FLYING.isEmpty() ? ", volley ended #" + volleys : ", volley flying #" + volleys;
        }

        private static final java.util.Set<Integer> SEEN = new java.util.HashSet<>();
    }

    /** Beams: one fewer lit pair is a pair done. */
    private static String beamsProbe(Minecraft mc) {
        Object list = ModUnderTest.staticCall(MOD + "puzzlesolvers.BeamsSolverFeature", "getActivePairs");
        return "pairs " + (list instanceof List<?> l ? l.size() : -1);
    }

    private static void minCooldown(Object auto, Object cheat) {
        ModUnderTest.set(auto, "setShootCooldownMs", 250);
    }

    // ------------------------------------------------------------------ the rooms

    public static final class IcePathTiming extends SimPuzzleSolveTests.Base {
        public IcePathTiming() {
            super(new SimPuzzleSolveTests.Spec("icepath-timing", "Ice Path", "setAutoIcePathEnabled", "isAutoIcePathEnabled",
                    "IcePathSolverConfig", "SimIcePathPuzzle", 1, false, 60, SimPuzzleSolveTests.Approach.NONE,
                    "IcePathSolverFeature.getPath", "IcePathSolverFeature.getSilverfish"));
        }

        @Override
        SolveExtra extra() {
            // Secret Aura off: the auto claims the reward chest itself (RewardChest's warp and click are measured too).
            return new PuzzleTimingExtra(List.of(Pair.SNEAK_USE, Pair.LAND_ACT), SimNoWaitTimingTests::icePathProbe,
                    SimPuzzleRoundTests.claim("SimIcePathPuzzle"), SimNoWaitTimingTests::minCooldown);
        }
    }

    public static final class IceFillTiming extends SimPuzzleSolveTests.Base {
        public IceFillTiming() {
            super(new SimPuzzleSolveTests.Spec("icefill-timing", "Ice Fill", "setAutoIceFillEnabled", "isAutoIceFillEnabled",
                    "IceFillSolverConfig", "SimIceFillPuzzle", 0, false, 60, SimPuzzleSolveTests.Approach.NONE,
                    "IceFillSolverFeature.getCurrentPath"));
        }

        @Override
        SolveExtra extra() {
            // His Ice Fill settings (delay 1, adaptive off), so a hop's own delay is the 1 tick the rule allows.
            return new PuzzleTimingExtra(List.of(Pair.SNEAK_USE, Pair.LAND_ACT), SimNoWaitTimingTests::iceFillProbe,
                    SimPuzzleRoundTests.claim(null), (auto, cheat) -> {
                        ModUnderTest.set(auto, "setIceFillDelayTicks", 1);
                        ModUnderTest.set(auto, "setIceFillAdaptive", false);
                    });
        }
    }

    public static final class HigherBlazeTiming extends SimPuzzleSolveTests.Base {
        public HigherBlazeTiming() {
            super(new SimPuzzleSolveTests.Spec("higherblaze-timing", "Higher Blaze", "setAutoBlazeEnabled", "isAutoBlazeEnabled",
                    "BlazeSolverConfig", "SimBlazePuzzle", 1, false, 60, SimPuzzleSolveTests.Approach.NONE,
                    "BlazeSolverFeature.getOrderedBlazes"));
        }

        @Override
        SolveExtra extra() {
            return new PuzzleTimingExtra(List.of(Pair.LAND_ACT, Pair.VOLLEY_NEXT), SimNoWaitTimingTests::blazeProbe, null,
                    SimNoWaitTimingTests::minCooldown);
        }
    }

    public static final class LowerBlazeTiming extends SimPuzzleSolveTests.Base {
        public LowerBlazeTiming() {
            super(new SimPuzzleSolveTests.Spec("lowerblaze-timing", "Lower Blaze", "setAutoBlazeEnabled", "isAutoBlazeEnabled",
                    "BlazeSolverConfig", "SimBlazePuzzle", 1, false, 60, SimPuzzleSolveTests.Approach.NONE,
                    "BlazeSolverFeature.getOrderedBlazes"));
        }

        @Override
        SolveExtra extra() {
            return new PuzzleTimingExtra(List.of(Pair.LAND_ACT, Pair.VOLLEY_NEXT), SimNoWaitTimingTests::blazeProbe, null,
                    SimNoWaitTimingTests::minCooldown);
        }
    }

    /**
     * Higher Blaze with the final volley refused (BlazeMiss, as 93-solve-blazemiss-higher): the re-shot after the miss and
     * the secret chest's click are judged from the moment the volley's arrows are gone.
     */
    public static final class BlazeMissHigherTiming extends SimPuzzleSolveTests.Base {
        public BlazeMissHigherTiming() {
            super(new SimPuzzleSolveTests.Spec("blazemiss-higher-timing", "Higher Blaze", "setAutoBlazeEnabled",
                    "isAutoBlazeEnabled", "BlazeSolverConfig", "SimBlazePuzzle", 1, false, 75,
                    SimPuzzleSolveTests.Approach.NONE, "BlazeSolverFeature.getOrderedBlazes"));
        }

        @Override
        boolean forceMiss() {
            return true;
        }

        @Override
        SolveExtra extra() {
            return new PuzzleTimingExtra(List.of(Pair.LAND_ACT, Pair.VOLLEY_NEXT), SimNoWaitTimingTests::blazeProbe, null,
                    SimNoWaitTimingTests::minCooldown);
        }
    }

    /** {@link BlazeMissHigherTiming} in Lower Blaze. */
    public static final class BlazeMissLowerTiming extends SimPuzzleSolveTests.Base {
        public BlazeMissLowerTiming() {
            super(new SimPuzzleSolveTests.Spec("blazemiss-lower-timing", "Lower Blaze", "setAutoBlazeEnabled",
                    "isAutoBlazeEnabled", "BlazeSolverConfig", "SimBlazePuzzle", 1, false, 75,
                    SimPuzzleSolveTests.Approach.NONE, "BlazeSolverFeature.getOrderedBlazes"));
        }

        @Override
        boolean forceMiss() {
            return true;
        }

        @Override
        SolveExtra extra() {
            return new PuzzleTimingExtra(List.of(Pair.LAND_ACT, Pair.VOLLEY_NEXT), SimNoWaitTimingTests::blazeProbe, null,
                    SimNoWaitTimingTests::minCooldown);
        }
    }

    public static final class CreeperBeamsTiming extends SimPuzzleSolveTests.Base {
        public CreeperBeamsTiming() {
            super(new SimPuzzleSolveTests.Spec("creeperbeams-timing", "Creeper Beams", "setAutoBeamsEnabled",
                    "isAutoBeamsEnabled", "BeamsSolverConfig", "SimCreeperPuzzle", 1, false, 60,
                    SimPuzzleSolveTests.Approach.NONE, "BeamsSolverFeature.getActivePairs"));
        }

        @Override
        SolveExtra extra() {
            return new PuzzleTimingExtra(List.of(Pair.SNEAK_USE, Pair.LAND_ACT), SimNoWaitTimingTests::beamsProbe, null,
                    SimNoWaitTimingTests::minCooldown);
        }
    }

    /** His Tic Tac Toe settings (Grab Secret and Walk Out on, Secret Aura on), as 93-solve-tictactoe-grab plays it. */
    public static final class TicTacToeTiming extends SimPuzzleSolveTests.Base {
        public TicTacToeTiming() {
            super(new SimPuzzleSolveTests.Spec("tictactoe-timing", "Tic Tac Toe", "setAutoTicTacToeEnabled",
                    "isAutoTicTacToeEnabled", "TicTacToeSolverConfig", "SimTicTacToePuzzle", 0, false, 90,
                    SimPuzzleSolveTests.Approach.NONE, "TicTacToeSolverFeature.getBestMove"));
        }

        @Override
        SolveExtra extra() {
            return new PuzzleTimingExtra(List.of(Pair.CLICK_USE, Pair.SNEAK_USE, Pair.LAND_ACT, Pair.READY_CLICK),
                    SimNoWaitTimingTests::ticTacToeProbe, null, (auto, cheat) -> {
                        ModUnderTest.set(auto, "setTicTacToeAuraChestEnabled", true);
                        ModUnderTest.set(auto, "setTicTacToeWalkOutEnabled", true);
                        ModUnderTest.set(cheat, "setSecretAuraEnabled", true);
                        ModUnderTest.set(cheat, "setAuraChests", true);
                        ModUnderTest.call(cheat, "setAuraRange", new Class<?>[]{double.class}, new Object[]{4.5});
                    });
        }
    }

    /**
     * Water Board's reward chest, Secret Aura off: the click on it goes while Auto Water still holds its free camera,
     * which is the TurnFirst wait the water-late lesson named for RewardChest.
     */
    public static final class WaterBoardChestTiming extends SimPuzzleSolveTests.Base {
        public WaterBoardChestTiming() {
            super(new SimPuzzleSolveTests.Spec("waterboard-chest-timing", "Water Board", "setAutoWaterEnabled",
                    "isAutoWaterEnabled", "WaterSolverConfig", "SimWaterPuzzle", 0, false, 60,
                    SimPuzzleSolveTests.Approach.NONE, "WaterSolverFeature.nextClick", "WaterSolverFeature.getCountedClicks"));
        }

        @Override
        SolveExtra extra() {
            return new PuzzleTimingExtra(List.of(Pair.CLICK_USE, Pair.SNEAK_USE, Pair.LAND_ACT, Pair.READY_CLICK), null,
                    SimPuzzleRoundTests.claim("SimWaterPuzzle"), null)
                    .judgeOnly(e -> e.info().endsWith(" chest")); // the levers' own timing is 93-solve-waterboard-timing's
        }
    }
}
