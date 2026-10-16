package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.ChestBlock;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The reward-chest stall (killer560, 2026-10-15, sim: "it gets stuck on ice fill after grabbing the chest, it doesn't
 * move" - his log was Ice Path): with Auto Close Chest on, as he plays, a chest click's window never shows - the mod
 * cancels the open-screen packet - so the reward-chest step read a claimed chest as unopened, clicked twice more, gave
 * up on it and then waited for a chest to "appear" while Autopilot sat under "Waiting for Auto Ice Path chest".
 *
 * <ul>
 *   <li>{@code 93-solve-icepath-chest-autoclose}, {@code -icefill-chest-autoclose}, {@code -waterboard-chest-autoclose}:
 *   Secret Aura off, Auto Close Chest ON. The SERVER's own record of chest clicks (a server-side UseBlockCallback in this
 *   harness, not the mod's word) is the truth: every chest it took a click on is claimed. The case fails unless each
 *   claimed chest was clicked exactly once, the step ended ("reward chest step over - done") within
 *   {@value #STEP_TICKS} ticks of the last claim, and the mod's AutoPuzzleActivity - what Autopilot, Auto Secret and
 *   Auto Routes hold on - read quiet within {@value #QUIET_TICKS} ticks of that. It fails as untested if no chest window
 *   was ever hidden (a screen showed) or the server took no chest click. Main clicks three times, gives up and waits
 *   about ten seconds.</li>
 *   <li>{@code 93-solve-boulder-chest-unsolved}: Secret Aura on (Auto Boulder runs the roof and never presses a button).
 *   Opening the chest is what completes Boulder on Hypixel, so the sim's {@code SimPuzzles.isComplete("boulder")} must
 *   be true within {@value #COMPLETE_TICKS} ticks of the chest opening and the tab list must read Boulder ✔ within
 *   {@value #TAB_TICKS}, with no button pressed. Main kept Boulder open for the whole run.</li>
 * </ul>
 */
public final class SimChestStallTests {

    static final int STEP_TICKS = 3;
    static final int QUIET_TICKS = 2;
    static final int COMPLETE_TICKS = 2;
    static final int TAB_TICKS = 40;

    private SimChestStallTests() {
    }

    // ------------------------------------------------------------------ the server's own chest-click record

    private static volatile boolean hooked;
    private static final Set<BlockPos> SERVER_CHEST_CLICKS = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.concurrent.atomic.AtomicInteger SERVER_CHEST_CLICK_COUNT =
            new java.util.concurrent.atomic.AtomicInteger();

    private static void hook() {
        if (hooked) {
            return;
        }
        hooked = true;
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!level.isClientSide() && hit != null
                    && level.getBlockState(hit.getBlockPos()).getBlock() instanceof ChestBlock) {
                SERVER_CHEST_CLICKS.add(hit.getBlockPos().immutable());
                SERVER_CHEST_CLICK_COUNT.incrementAndGet();
            }
            return InteractionResult.PASS;
        });
    }

    private static final String ACTIVITY = "com.killer560.hub.autopuzzles.AutoPuzzleActivity";
    private static final String CLOSE_CONFIG = "com.killer560.hub.autoclosechest.AutoCloseChestConfig";

    /** One reward-chest step under Auto Close Chest, judged against the server's chest clicks. */
    static SolveExtra autoCloseClaim(String tag, int chests) {
        return new SolveExtra() {
            int serverClicks0 = -1;
            int lastServerClickT = -1;
            int seenServerClicks = 0;
            int stepAt = -1;
            String stepWhy = null;
            int quietAt = -1;
            boolean screenSeen = false;
            int modClicks = 0;
            boolean gaveUp = false;
            int solved = -1;

            @Override
            void configure(Object auto, Object cheat) {
                hook();
                ModUnderTest.set(cheat, "setSecretAuraEnabled", false);
                ModUnderTest.set(ModUnderTest.config(CLOSE_CONFIG), "setEnabled", true);
            }

            @Override
            void tick(ClientGameTestContext ctx, int t, int solvedAt, boolean container) {
                solved = solvedAt;
                if (serverClicks0 < 0) {
                    serverClicks0 = SERVER_CHEST_CLICK_COUNT.get();
                    SERVER_CHEST_CLICKS.clear();
                }
                if (solvedAt < 0) {
                    return;
                }
                screenSeen |= container;
                int n = SERVER_CHEST_CLICK_COUNT.get() - serverClicks0;
                if (n > seenServerClicks) {
                    seenServerClicks = n;
                    lastServerClickT = t;
                    SimPuzzleRoundTests.say(name, String.format("t=%.1fs the server took chest click %d (%d chest(s))",
                            t / 20.0, n, SERVER_CHEST_CLICKS.size()));
                }
                int clicks = 0;
                for (String l : LogTap.since(mark)) {
                    if (l.contains("] " + tag + ": clicked the reward chest")) {
                        clicks++;
                    }
                    if (l.contains("] " + tag + ": the reward chest at") && l.contains("did not open")) {
                        gaveUp = true;
                    }
                    if (stepAt < 0 && l.contains("] " + tag + ": reward chest step over - ")) {
                        stepAt = t;
                        stepWhy = l.substring(l.indexOf("step over - ") + 12);
                        SimPuzzleRoundTests.say(name, String.format("t=%.1fs step over: %s", t / 20.0, stepWhy));
                    }
                }
                modClicks = clicks;
                if (stepAt >= 0 && quietAt < 0 && !Boolean.TRUE.equals(ctx.computeOnClient(mc ->
                        (Boolean) ModUnderTest.staticCall(ACTIVITY, "isWorking")))) {
                    quietAt = t;
                    SimPuzzleRoundTests.say(name, String.format("t=%.1fs auto puzzle activity quiet", t / 20.0));
                }
            }

            @Override
            boolean satisfied() {
                return stepAt >= 0 && quietAt >= 0;
            }

            @Override
            String verdict(ClientGameTestContext ctx, int solvedAt) {
                ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(CLOSE_CONFIG), "setEnabled", false));
                if (solvedAt < 0) {
                    return null;
                }
                List<String> bad = new java.util.ArrayList<>();
                if (seenServerClicks == 0) {
                    return "UNTESTED - the server took no click on a chest after the solve";
                }
                if (screenSeen) {
                    return "UNTESTED - a chest screen showed, so Auto Close Chest did not hide the window";
                }
                int claimed = SERVER_CHEST_CLICKS.size();
                if (claimed < chests) {
                    bad.add(claimed + " of " + chests + " reward chest(s) claimed");
                }
                if (modClicks != claimed) {
                    bad.add(modClicks + " click(s) on " + claimed + " claimed chest(s) - a claimed chest was clicked again");
                }
                if (gaveUp) {
                    bad.add("the step said a claimed chest did not open");
                }
                if (stepAt < 0) {
                    bad.add("the reward chest step never ended");
                } else {
                    if (!"done".equals(stepWhy)) {
                        bad.add("the step ended \"" + stepWhy + "\", not \"done\"");
                    }
                    if (stepAt - lastServerClickT > STEP_TICKS) {
                        bad.add(String.format("the step ended %d tick(s) after the last claim (allowed %d)",
                                stepAt - lastServerClickT, STEP_TICKS));
                    }
                    if (quietAt < 0) {
                        bad.add("the auto puzzle never read quiet after the step - Autopilot would still wait");
                    } else if (quietAt - stepAt > QUIET_TICKS) {
                        bad.add(String.format("Autopilot's hold lasted %d tick(s) past the step (allowed %d)",
                                quietAt - stepAt, QUIET_TICKS));
                    }
                }
                return bad.isEmpty() ? null : String.join("; ", bad);
            }

            @Override
            String summary() {
                return String.format("%d claimed, %d click(s), step over %s%s, quiet %s", SERVER_CHEST_CLICKS.size(),
                        modClicks, stepAt < 0 ? "never" : (stepAt - lastServerClickT) + " tick(s) after the claim",
                        stepWhy == null ? "" : " (" + stepWhy + ")",
                        quietAt < 0 ? "never" : (quietAt - stepAt) + " tick(s) after");
            }
        };
    }

    /** Boulder: done the moment its chest is opened, whether or not a button was pressed. */
    static SolveExtra boulderChestCompletes() {
        return new SolveExtra() {
            int openAt = -1;
            int completeAt = -1;
            int tabAt = -1;
            boolean pathOpenAtComplete = false;

            @Override
            void tick(ClientGameTestContext ctx, int t, int solvedAt, boolean container) {
                if (openAt < 0 && Boolean.TRUE.equals(ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(
                        "com.killer560.hub.roomsim.puzzles.SimBoulderPuzzle", "isRewardChestOpened")))) {
                    openAt = t;
                    SimPuzzleRoundTests.say(name, String.format("t=%.1fs the sim recorded the chest opened", t / 20.0));
                }
                if (completeAt < 0 && Boolean.TRUE.equals(ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(
                        "com.killer560.hub.roomsim.puzzles.SimPuzzles", "isComplete", new Class<?>[]{String.class},
                        new Object[]{"boulder"})))) {
                    completeAt = t;
                    pathOpenAtComplete = pathOpen(ctx);
                    SimPuzzleRoundTests.say(name, String.format("t=%.1fs the sim reads Boulder complete (boxes open a way: %s)",
                            t / 20.0, pathOpenAtComplete));
                }
                if (tabAt < 0) {
                    char c = ctx.computeOnClient(mc -> (Character) ModUnderTest.staticCall(
                            "com.killer560.hub.scorecalc.ScoreCalculatorFeature", "puzzleState",
                            new Class<?>[]{String.class}, new Object[]{"Boulder"}));
                    if (c == '✔') {
                        tabAt = t;
                        SimPuzzleRoundTests.say(name, String.format("t=%.1fs the tab list reads Boulder ✔", t / 20.0));
                    }
                }
            }

            private boolean pathOpen(ClientGameTestContext ctx) {
                try {
                    return Boolean.TRUE.equals(ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(
                            "com.killer560.hub.roomsim.puzzles.SimBoulderPuzzle", "isPathOpen")));
                } catch (Throwable e) {
                    return false; // a jar from before isPathOpen: there isComplete IS the path test
                }
            }

            @Override
            boolean satisfied() {
                return openAt >= 0 && tabAt >= 0;
            }

            @Override
            String verdict(ClientGameTestContext ctx, int solvedAt) {
                List<String> bad = new java.util.ArrayList<>();
                if (openAt < 0) {
                    return "UNTESTED - the chest was never opened";
                }
                if (completeAt < 0) {
                    bad.add("the sim never read Boulder complete after its chest was opened");
                } else if (completeAt - openAt > COMPLETE_TICKS) {
                    bad.add(String.format("the sim read Boulder complete %d tick(s) after the chest (allowed %d)",
                            completeAt - openAt, COMPLETE_TICKS));
                }
                if (tabAt < 0) {
                    bad.add("the tab list never read Boulder ✔ - Autopilot would wait out its timeout");
                } else if (tabAt - openAt > TAB_TICKS) {
                    bad.add(String.format("the tab list read Boulder ✔ %d tick(s) after the chest (allowed %d)",
                            tabAt - openAt, TAB_TICKS));
                }
                return bad.isEmpty() ? null : String.join("; ", bad);
            }

            @Override
            String summary() {
                return String.format("chest at %s, complete %s, tab ✔ %s", openAt < 0 ? "never" : String.format("%.1fs",
                                openAt / 20.0), completeAt < 0 ? "never" : "+" + (completeAt - openAt) + " tick(s)",
                        tabAt < 0 ? "never" : "+" + (tabAt - openAt) + " tick(s)");
            }
        };
    }

    // ------------------------------------------------------------------ the cases

    public static final class IcePathChestAutoClose extends SimPuzzleSolveTests.Base {
        public IcePathChestAutoClose() {
            super(new SimPuzzleSolveTests.Spec("icepath-chest-autoclose", "Ice Path", "setAutoIcePathEnabled",
                    "isAutoIcePathEnabled", "IcePathSolverConfig", "SimIcePathPuzzle", 1, false, 60,
                    SimPuzzleSolveTests.Approach.NONE, "IcePathSolverFeature.getPath", "IcePathSolverFeature.getSilverfish"));
        }

        @Override
        SolveExtra extra() {
            return autoCloseClaim("Ice Path", 1);
        }
    }

    public static final class IceFillChestAutoClose extends SimPuzzleSolveTests.Base {
        public IceFillChestAutoClose() {
            super(new SimPuzzleSolveTests.Spec("icefill-chest-autoclose", "Ice Fill", "setAutoIceFillEnabled",
                    "isAutoIceFillEnabled", "IceFillSolverConfig", "SimIceFillPuzzle", 0, false, 60,
                    SimPuzzleSolveTests.Approach.NONE, "IceFillSolverFeature.getCurrentPath"));
        }

        @Override
        SolveExtra extra() {
            return autoCloseClaim("Ice Fill", 2);
        }
    }

    public static final class WaterBoardChestAutoClose extends SimPuzzleSolveTests.Base {
        public WaterBoardChestAutoClose() {
            super(new SimPuzzleSolveTests.Spec("waterboard-chest-autoclose", "Water Board", "setAutoWaterEnabled",
                    "isAutoWaterEnabled", "WaterSolverConfig", "SimWaterPuzzle", 0, false, 60,
                    SimPuzzleSolveTests.Approach.NONE, "WaterSolverFeature.nextClick", "WaterSolverFeature.getCountedClicks"));
        }

        @Override
        SolveExtra extra() {
            return autoCloseClaim("Water Board", 1);
        }
    }

    public static final class BoulderChestUnsolved extends SimPuzzleSolveTests.Base {
        public BoulderChestUnsolved() {
            super(new SimPuzzleSolveTests.Spec("boulder-chest-unsolved", "Boulder", "setAutoBoulderEnabled",
                    "isAutoBoulderEnabled", "BoulderSolverConfig", "SimBoulderPuzzle", SimPuzzleSolveTests.AOTV_SLOT, true,
                    60, SimPuzzleSolveTests.Approach.NONE, "BoulderSolverFeature.getNextClick",
                    "BoulderSolverFeature.getRemainingClicks"));
        }

        @Override
        boolean secretAura() {
            return true;
        }

        @Override
        SolveExtra extra() {
            return boulderChestCompletes();
        }
    }
}
