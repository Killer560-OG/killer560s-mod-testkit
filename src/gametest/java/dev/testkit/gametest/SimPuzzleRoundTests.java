package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/**
 * The 2026-10-15 puzzles round (killer560's to-do list, mod branch puzzles-main-1015), as {@code 93-solve-*} cases on top
 * of {@link SimPuzzleSolveTests}: the same room load, items, premise checks and sim verdict, plus each case's own checks
 * through {@link SolveExtra}.
 *
 * <ul>
 *   <li>{@code 93-solve-tictactoe-grab}: his settings (Grab Secret and Walk Out on, Secret Aura on). The room's secret
 *   chest must be opened between the first and the second board click, he must never hold the forward key (every move
 *   is an etherwarp: in front of the board, next to the secret, back), and nothing may walk him out before the game is
 *   over. Main asked the Interactive Map for the chest block itself, which is no landing ("not etherwarpable").</li>
 *   <li>{@code 93-solve-waterboard-claim}, {@code -icepath-claim}, {@code -icefill-claim}: Secret Aura OFF, and the AUTO
 *   must open the reward chest after the solve (Water Board and Ice Path: the sim's own "opened" flag; Ice Fill: a
 *   container opening after the solve). Main never clicked one; Secret Aura skips Ice Path and Ice Fill and waits in Water
 *   Board for a tab-list state.</li>
 *   <li>{@code 93-solve-icefill-firsttile}: his Ice Fill settings (delay 1, adaptive off) with {@code HypixelTileRegister}
 *   holding every packed-ice update 3 client ticks, as Hypixel registers a tile after the landing. Every hop off a
 *   section's first tile must go only after that tile read packed on the client.</li>
 * </ul>
 */
public final class SimPuzzleRoundTests {

    private SimPuzzleRoundTests() {
    }

    static void say(String name, String line) {
        System.out.println("[" + name + "] " + line);
    }

    // ------------------------------------------------------------------ Tic Tac Toe

    public static final class TicTacToeGrab extends SimPuzzleSolveTests.Base {
        public TicTacToeGrab() {
            super(new SimPuzzleSolveTests.Spec("tictactoe-grab", "Tic Tac Toe", "setAutoTicTacToeEnabled",
                    "isAutoTicTacToeEnabled", "TicTacToeSolverConfig", "SimTicTacToePuzzle", 0, false, 90,
                    SimPuzzleSolveTests.Approach.NONE, "TicTacToeSolverFeature.getBestMove"));
        }

        @Override
        SolveExtra extra() {
            return new SolveExtra() {
                int secrets0 = -1;
                int secretsNow = -1;
                int secretAt = -1;
                int firstClickAt = -1;
                int secondClickAt = -1;
                int boardClicks = 0;
                int forwardTicks = 0;
                String walkLine = null;
                String walkOutLine = null;
                /** Board clicks made when the walk-out was decided: early when another click came after it. */
                int walkOutAt = -1;
                int scanned = 0;

                @Override
                void configure(Object auto, Object cheat) {
                    ModUnderTest.set(auto, "setTicTacToeAuraChestEnabled", true);
                    ModUnderTest.set(auto, "setTicTacToeWalkOutEnabled", true);
                    ModUnderTest.set(cheat, "setSecretAuraEnabled", true);
                    ModUnderTest.set(cheat, "setAuraChests", true);
                    ModUnderTest.call(cheat, "setAuraRange", new Class<?>[]{double.class}, new Object[]{4.5});
                }

                @Override
                void tick(ClientGameTestContext ctx, int t, int solvedAt, boolean container) {
                    int n = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(
                            "com.killer560.hub.roomsim.SimScore", "secretsFound"));
                    if (secrets0 < 0) {
                        secrets0 = n;
                    }
                    // The room's one chest can be the sim's mimic (a single-room build has one candidate), whose
                    // opening counts no secret: a container opening after the first click counts as taking it too.
                    if (secretAt < 0 && container && firstClickAt > 0) {
                        secretAt = t;
                        say(name, String.format("t=%.1fs a container opened - the room's chest was taken", t / 20.0));
                    }
                    if (n > secretsNow && secretsNow >= 0 && secretAt < 0 && n > secrets0) {
                        secretAt = t;
                        say(name, String.format("t=%.1fs the room's secret was taken (secrets %d -> %d)", t / 20.0,
                                secrets0, n));
                    }
                    secretsNow = n;
                    if (ctx.computeOnClient(mc -> mc.options.keyUp.isDown())) {
                        forwardTicks++;
                    }
                    List<String> lines = LogTap.since(mark);
                    for (int i = scanned; i < lines.size(); i++) {
                        String l = lines.get(i);
                        if (l.contains("TicTacToe: clicked BlockPos")) {
                            boardClicks++;
                            if (firstClickAt < 0) {
                                firstClickAt = t;
                            } else if (secondClickAt < 0) {
                                secondClickAt = t;
                            }
                        }
                        if (walkLine == null && (l.contains("TicTacToe: move") && l.contains("walking to"))) {
                            walkLine = l;
                        }
                        if (walkOutAt < 0 && (l.contains("board done but the map has no door")
                                || l.contains("walk out"))) {
                            walkOutAt = boardClicks;
                            walkOutLine = l;
                        }
                    }
                    scanned = lines.size();
                }

                @Override
                boolean satisfied() {
                    return secretAt > 0;
                }

                @Override
                String verdict(ClientGameTestContext ctx, int solvedAt) {
                    List<String> bad = new ArrayList<>();
                    if (boardClicks < 2) {
                        bad.add("only " + boardClicks + " board click(s) - the run was not measured");
                    }
                    if (secretAt < 0) {
                        bad.add("the room's secret chest was never opened (Grab Secret on)");
                    } else if (secondClickAt > 0 && secretAt > secondClickAt) {
                        bad.add(String.format("the secret was taken at %.1fs, after the second board click at %.1fs - "
                                + "it should be right after the first", secretAt / 20.0, secondClickAt / 20.0));
                    }
                    // The walk-in he saw on Hypixel was the board approach ("move ... is out of reach - walking to").
                    // The forward key is printed, not judged: the secret's last blocks may be walked when no warp line
                    // reaches a spot in reach of it.
                    if (walkLine != null) {
                        bad.add("walked to the board instead of etherwarping in front of it: " + walkLine.trim());
                    }
                    if (walkOutAt >= 0 && walkOutAt < boardClicks) {
                        bad.add("walk-out decided after " + walkOutAt + " of " + boardClicks + " board click(s), before "
                                + "the game was over: " + walkOutLine.trim());
                    }
                    return bad.isEmpty() ? null : String.join("; ", bad);
                }

                @Override
                String summary() {
                    return String.format("%d board click(s) (first %.1fs, second %.1fs), secret taken %s, forward key "
                                    + "%d tick(s), walk-out %s", boardClicks, firstClickAt / 20.0,
                            secondClickAt / 20.0, secretAt < 0 ? "never" : String.format("at %.1fs", secretAt / 20.0),
                            forwardTicks, walkOutAt < 0 ? "none" : walkOutAt < boardClicks ? "YES" : "after the last click");
                }
            };
        }
    }

    // ------------------------------------------------------------------ reward chests

    /** Secret Aura off; the auto must open the reward chest after the solve. */
    static SolveExtra claim(String puzzleClass) {
        return new SolveExtra() {
            int claimedAt = -1;
            int solved = -1;

            @Override
            void configure(Object auto, Object cheat) {
                ModUnderTest.set(cheat, "setSecretAuraEnabled", false);
            }

            @Override
            void tick(ClientGameTestContext ctx, int t, int solvedAt, boolean container) {
                solved = solvedAt;
                if (solvedAt < 0 || claimedAt > 0) {
                    return;
                }
                boolean opened = container;
                if (puzzleClass != null) {
                    opened |= ctx.computeOnClient(mc -> {
                        try {
                            return (Boolean) ModUnderTest.staticCall("com.killer560.hub.roomsim.puzzles." + puzzleClass,
                                    "isRewardChestOpened");
                        } catch (Throwable e) {
                            return false; // a jar whose sim has no reward chest here
                        }
                    });
                }
                if (opened) {
                    claimedAt = t;
                    say(name, String.format("t=%.1fs the reward chest was opened, %.1fs after the solve", t / 20.0,
                            (t - solvedAt) / 20.0));
                }
            }

            @Override
            boolean satisfied() {
                return claimedAt > 0;
            }

            @Override
            String verdict(ClientGameTestContext ctx, int solvedAt) {
                if (solvedAt < 0) {
                    return null; // the room's own verdict says it was not solved
                }
                return claimedAt > 0 ? null : "the auto never opened the reward chest in 20 s after the solve (Secret Aura off)";
            }

            @Override
            String summary() {
                return claimedAt > 0 ? String.format("reward chest opened %.1fs after the solve", (claimedAt - solved) / 20.0)
                        : "reward chest NOT opened";
            }
        };
    }

    public static final class WaterBoardClaim extends SimPuzzleSolveTests.Base {
        public WaterBoardClaim() {
            super(new SimPuzzleSolveTests.Spec("waterboard-claim", "Water Board", "setAutoWaterEnabled", "isAutoWaterEnabled",
                    "WaterSolverConfig", "SimWaterPuzzle", 0, false, 60, SimPuzzleSolveTests.Approach.NONE,
                    "WaterSolverFeature.nextClick", "WaterSolverFeature.getCountedClicks"));
        }

        @Override
        SolveExtra extra() {
            return claim("SimWaterPuzzle");
        }
    }

    public static final class IcePathClaim extends SimPuzzleSolveTests.Base {
        public IcePathClaim() {
            super(new SimPuzzleSolveTests.Spec("icepath-claim", "Ice Path", "setAutoIcePathEnabled", "isAutoIcePathEnabled",
                    "IcePathSolverConfig", "SimIcePathPuzzle", 1, false, 60, SimPuzzleSolveTests.Approach.NONE,
                    "IcePathSolverFeature.getPath", "IcePathSolverFeature.getSilverfish"));
        }

        @Override
        SolveExtra extra() {
            return claim("SimIcePathPuzzle");
        }
    }

    public static final class IceFillClaim extends SimPuzzleSolveTests.Base {
        public IceFillClaim() {
            super(new SimPuzzleSolveTests.Spec("icefill-claim", "Ice Fill", "setAutoIceFillEnabled", "isAutoIceFillEnabled",
                    "IceFillSolverConfig", "SimIceFillPuzzle", 0, false, 60, SimPuzzleSolveTests.Approach.NONE,
                    "IceFillSolverFeature.getCurrentPath"));
        }

        @Override
        SolveExtra extra() {
            return claim(null);
        }
    }

    // ------------------------------------------------------------------ Ice Fill: each section's first tile

    public static final class IceFillFirstTile extends SimPuzzleSolveTests.Base {
        public IceFillFirstTile() {
            super(new SimPuzzleSolveTests.Spec("icefill-firsttile", "Ice Fill", "setAutoIceFillEnabled",
                    "isAutoIceFillEnabled", "IceFillSolverConfig", "SimIceFillPuzzle", 0, false, 60,
                    SimPuzzleSolveTests.Approach.NONE, "IceFillSolverFeature.getCurrentPath"));
        }

        @Override
        SolveExtra extra() {
            return new SolveExtra() {
                int prevIndex = -2;
                boolean prevPacked = false;
                int checked = 0;
                final List<String> early = new ArrayList<>();
                int held = 0;

                @Override
                void configure(Object auto, Object cheat) {
                    // His own settings (Mod Only Test, Full Toggle profile): delay 1, adaptive off.
                    ModUnderTest.set(auto, "setIceFillAdaptive", false);
                    ModUnderTest.set(auto, "setIceFillDelayTicks", 1);
                    dev.testkit.harness.HypixelTileRegister.delayTicks = 3;
                }

                @Override
                void tick(ClientGameTestContext ctx, int t, int solvedAt, boolean container) {
                    Object[] r = ctx.computeOnClient(mc -> read(mc));
                    if (r == null) {
                        return;
                    }
                    int idx = (Integer) r[0];
                    int[] starts = (int[]) r[1];
                    if (idx == prevIndex + 1) {
                        for (int s = 0; s < starts.length; s++) {
                            if (starts[s] == prevIndex) {
                                checked++;
                                if (!prevPacked) {
                                    early.add(String.format("section %d: hop off its first tile (point %d) at t=%.1fs "
                                            + "while the tile still read ICE", s + 1, prevIndex, t / 20.0));
                                }
                            }
                        }
                    }
                    prevIndex = idx;
                    prevPacked = (Boolean) r[2];
                    held = dev.testkit.harness.HypixelTileRegister.held;
                }

                /** {lastIndex, sectionStart, whether the tile under path[lastIndex] is packed ice}, or null. */
                private Object[] read(Minecraft mc) {
                    try {
                        Class<?> c = Class.forName("com.killer560.hub.autopuzzles.AutoIceFill");
                        var fi = c.getDeclaredField("lastIndex");
                        var fs = c.getDeclaredField("sectionStart");
                        var fp = c.getDeclaredField("path");
                        fi.setAccessible(true);
                        fs.setAccessible(true);
                        fp.setAccessible(true);
                        int idx = fi.getInt(null);
                        int[] starts = (int[]) fs.get(null);
                        @SuppressWarnings("unchecked")
                        List<net.minecraft.world.phys.Vec3> path = (List<net.minecraft.world.phys.Vec3>) fp.get(null);
                        boolean packed = false;
                        if (idx >= 0 && idx < path.size()) {
                            packed = mc.level.getBlockState(net.minecraft.core.BlockPos.containing(path.get(idx)).below())
                                    .is(net.minecraft.world.level.block.Blocks.PACKED_ICE);
                        }
                        return new Object[]{idx, starts, packed};
                    } catch (ReflectiveOperationException e) {
                        return null;
                    }
                }

                @Override
                String verdict(ClientGameTestContext ctx, int solvedAt) {
                    dev.testkit.harness.HypixelTileRegister.reset();
                    if (held == 0) {
                        return "the tile-register fixture held no update - nothing was measured";
                    }
                    if (checked < 2) {
                        return "only " + checked + " hop(s) off a section's first tile were seen - not measured";
                    }
                    return early.isEmpty() ? null : String.join("; ", early);
                }

                @Override
                String summary() {
                    return checked + " hop(s) off a section's first tile checked, " + early.size()
                            + " before the tile registered; " + held + " packed-ice update(s) held 3 ticks";
                }
            };
        }
    }
}
