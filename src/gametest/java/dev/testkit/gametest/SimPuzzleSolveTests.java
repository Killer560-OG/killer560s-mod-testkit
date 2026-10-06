package dev.testkit.gametest;

import dev.testkit.compat.McCompat;

import dev.testkit.harness.Report;
import dev.testkit.harness.SuiteVerdict;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Does each AUTO puzzle actually SOLVE its sim puzzle, with nobody touching the keyboard?
 *
 * <p>killer560 (2026-10-04): "use your test server to test everything and get it all perfect. Really spend a lot
 * of time working on puzzles to make sure it is all working great."
 *
 * <p>Scenario 78 only proves each puzzle BUILDS. This plays them. One scenario per puzzle room, each named
 * {@code 93-solve-<room>}, so {@code -Pscenario=93-solve} runs the lot and {@code -Pscenario=93-solve-icepath}
 * one of them. For each:
 *
 * <ol>
 *   <li>The room is loaded the way he loads one: {@code SimBuilder.buildSingleRoom} from the title screen, which
 *       opens the sim world, pastes the room, arms its puzzle ({@code SimRoomPuzzles.armFloor}) and drops the
 *       player at the room's spawn - the same spot {@code /goto} uses.</li>
 *   <li>Hotbar: Aspect of the Void in slot 1, Terminator in slot 2, nothing else - the two items the autos use
 *       (etherwarp reposition / Interactive Map walks, and the shortbow puzzles). Which one is in hand at the start
 *       is per puzzle and printed; it is the one the auto needs, and choosing it is the only "human input".</li>
 *   <li>Every auto puzzle is switched OFF, then the master switch, this room's auto, its solver, Etherwarp
 *       Reposition, auto-puzzle pathing and the Interactive Map are switched ON. The client is not fresh between
 *       scenarios (AGENTS.md), so nothing is trusted from a default.</li>
 *   <li>Then nothing. The scenario only watches, for up to {@link Spec#seconds} of game time.</li>
 *   <li>The verdict is the SIM's own state, read from the puzzle class by reflection: {@code isComplete()} of the
 *       {@code Sim*Puzzle} that was armed on that room, plus {@code SimRoomState.isFailed} - a puzzle that was
 *       failed on the way counts as a failure even if it later completes, because on Hypixel a fail is final.
 *       Boulder is the one exception and says so: Auto Boulder does not push boxes, it etherwarps to the reward
 *       chest and auras it, so its verdict is "the reward chest was opened" (a container opened on the client),
 *       and the sim's own isComplete is reported beside it.</li>
 * </ol>
 *
 * <p>The premise is checked before anything is blamed on an auto: the room must be armed (the mod's own
 * "Sim puzzles: 1 of 1 puzzle room(s) armed" line), the live map must name the room the player is standing in,
 * the auto's EFFECTIVE getter (the one gated on the cheat build and the Skyblock gate) must read true, and the
 * puzzle must not already be complete before the auto starts. A failure prints the last log lines of the mod and
 * every chat line, the player's position and held item, so the run explains itself.
 */
public final class SimPuzzleSolveTests {

    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String BUILDER = "com.killer560.hub.roomsim.SimBuilder";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String ROOM_STATE = "com.killer560.hub.roomsim.SimRoomState";
    private static final String SIM_ITEMS = "com.killer560.hub.roomsim.SimItems";
    private static final String LIVE_MAP = "com.killer560.hub.livemap.LiveMapFeature";
    private static final String LIVE_MAP_CONFIG = "com.killer560.hub.livemap.LiveMapConfig";
    private static final String AUTO_CONFIG = "com.killer560.hub.autopuzzles.AutoPuzzlesConfig";
    private static final String SOLVERS = "com.killer560.hub.puzzlesolvers.";
    private static final String PUZZLES = "com.killer560.hub.roomsim.puzzles.";

    /**
     * Whose room captures to play. The Prism instances do NOT hold the same ones: on 2026-10-04 Boulder, Ice Fill,
     * Ice Path, Quiz and Water Board differed between "26.1.2 (Mod Only Test)" and "Map Logger", where he plays
     * the sim. Set the environment variable {@code TESTKIT_SIM_INSTANCE} to another instance's folder name to
     * play its captures; the name is printed with every verdict.
     */
    static final String ROOM_INSTANCE = System.getenv().getOrDefault("TESTKIT_SIM_INSTANCE",
            "26.1.2 (Mod Only Test)");

    private static final String SOURCE_ROOMS =
            ModUnderTest.instanceConfig("C:/Users/Hunter/AppData/Roaming/PrismLauncher/instances/" + ROOM_INSTANCE
                    + "/minecraft/config", "killer560smod-rooms");

    private static final String AOTV = "ASPECT_OF_THE_VOID";
    private static final String TERMINATOR = "TERMINATOR";
    private static final int AOTV_SLOT = 0;
    private static final int TERM_SLOT = 1;

    /** Every auto's toggle, so each scenario can switch ALL of them off before turning its own on. */
    private static final String[] ALL_AUTOS = {"setAutoQuizEnabled", "setAutoWeirdosEnabled", "setAutoBlazeEnabled",
            "setAutoBeamsEnabled", "setAutoIcePathEnabled", "setAutoBoulderEnabled", "setAutoWaterEnabled",
            "setAutoTicTacToeEnabled", "setAutoTeleportMazeEnabled", "setAutoIceFillEnabled"};

    /** Verdict per scenario, in run order, for the summary line. */
    private static final Map<String, String> VERDICTS = new LinkedHashMap<>();

    /**
     * The part a human does on Hypixel too, and the only thing the scenario does for him.
     *
     * <ul>
     *   <li>{@code QUIZ_SPOT} - Auto Quiz clicks the answer button within block reach and never moves; he walks
     *       up to the pillars. Placed (server teleport) at room-relative (15, 6), between the three pillars,
     *       from where every answer button is inside 4.5 blocks.</li>
     *   <li>{@code NEAR_WEIRDOS} - Auto Three Weirdos talks to NPCs within entity reach (3.0) and opens a chest
     *       within block reach, and never moves; he walks up to them. Placed on the standable block whose eye
     *       is nearest the farthest of the three "CLICK" stands.</li>
     *   <li>{@code WALK_ONTO_START_PAD} - Auto Teleport Maze reacts to maze teleports; the first one is his
     *       step onto the start pad. Walked there with the forward key, facing it.</li>
     * </ul>
     */
    enum Approach { NONE, QUIZ_SPOT, NEAR_WEIRDOS, WALK_ONTO_START_PAD }

    /**
     * One puzzle room.
     *
     * @param slug      scenario suffix
     * @param room      the room's name in RoomLibrary and on the live map
     * @param autoSetter AutoPuzzlesConfig setter for this room's auto
     * @param autoGetter the EFFECTIVE getter (cheat build + Skyblock gate applied)
     * @param solver    puzzlesolvers config class that auto reads
     * @param puzzle    roomsim.puzzles class whose isComplete() is the verdict
     * @param heldSlot  hotbar slot in hand when the auto is switched on
     * @param chestIsVerdict true for Boulder: the reward chest opening is the verdict
     * @param seconds   game-time budget
     * @param approach  the human input the auto genuinely needs on Hypixel too, driven by the scenario
     * @param probes    no-argument statics of the solver printed in every status line ("Class.method")
     */
    record Spec(String slug, String room, String autoSetter, String autoGetter, String solver, String puzzle,
                int heldSlot, boolean chestIsVerdict, int seconds, Approach approach, String... probes) {
        String name() {
            return "93-solve-" + slug;
        }
    }

    // ------------------------------------------------------------------ the eleven rooms

    public static final class IcePath extends Base {
        public IcePath() {
            super(new Spec("icepath", "Ice Path", "setAutoIcePathEnabled", "isAutoIcePathEnabled",
                    "IcePathSolverConfig", "SimIcePathPuzzle", TERM_SLOT, false, 60, Approach.NONE,
                    "IcePathSolverFeature.getPath", "IcePathSolverFeature.getSilverfish",
                    "IcePathSolverFeature.isSilverfishMoving"));
        }
    }

    public static final class IceFill extends Base {
        public IceFill() {
            super(new Spec("icefill", "Ice Fill", "setAutoIceFillEnabled", "isAutoIceFillEnabled",
                    "IceFillSolverConfig", "SimIceFillPuzzle", AOTV_SLOT, false, 60, Approach.NONE,
                    "IceFillSolverFeature.getCurrentPath"));
        }
    }

    public static final class HigherBlaze extends Base {
        public HigherBlaze() {
            super(new Spec("higherblaze", "Higher Blaze", "setAutoBlazeEnabled", "isAutoBlazeEnabled",
                    "BlazeSolverConfig", "SimBlazePuzzle", TERM_SLOT, false, 60, Approach.NONE,
                    "BlazeSolverFeature.getOrderedBlazes"));
        }
    }

    public static final class LowerBlaze extends Base {
        public LowerBlaze() {
            super(new Spec("lowerblaze", "Lower Blaze", "setAutoBlazeEnabled", "isAutoBlazeEnabled",
                    "BlazeSolverConfig", "SimBlazePuzzle", TERM_SLOT, false, 60, Approach.NONE,
                    "BlazeSolverFeature.getOrderedBlazes"));
        }
    }

    public static final class CreeperBeams extends Base {
        public CreeperBeams() {
            super(new Spec("creeperbeams", "Creeper Beams", "setAutoBeamsEnabled", "isAutoBeamsEnabled",
                    "BeamsSolverConfig", "SimCreeperPuzzle", TERM_SLOT, false, 60, Approach.NONE,
                    "BeamsSolverFeature.getActivePairs", "sim:SimCreeperPuzzle.connected"));
        }
    }

    public static final class Boulder extends Base {
        public Boulder() {
            super(new Spec("boulder", "Boulder", "setAutoBoulderEnabled", "isAutoBoulderEnabled",
                    "BoulderSolverConfig", "SimBoulderPuzzle", AOTV_SLOT, true, 60, Approach.NONE,
                    "BoulderSolverFeature.getNextClick", "BoulderSolverFeature.getRemainingClicks"));
        }
    }

    public static final class ThreeWeirdos extends Base {
        public ThreeWeirdos() {
            super(new Spec("threeweirdos", "Three Weirdos", "setAutoWeirdosEnabled", "isAutoWeirdosEnabled",
                    "WeirdosSolverConfig", "SimQuizPuzzle", AOTV_SLOT, false, 60, Approach.NEAR_WEIRDOS,
                    "WeirdosSolverFeature.getCorrectChestPos", "WeirdosSolverFeature.getWrongChestCount"));
        }
    }

    public static final class TicTacToe extends Base {
        public TicTacToe() {
            super(new Spec("tictactoe", "Tic Tac Toe", "setAutoTicTacToeEnabled", "isAutoTicTacToeEnabled",
                    "TicTacToeSolverConfig", "SimTicTacToePuzzle", AOTV_SLOT, false, 60, Approach.NONE,
                    "TicTacToeSolverFeature.getBestMove"));
        }
    }

    public static final class WaterBoard extends Base {
        public WaterBoard() {
            super(new Spec("waterboard", "Water Board", "setAutoWaterEnabled", "isAutoWaterEnabled",
                    "WaterSolverConfig", "SimWaterPuzzle", AOTV_SLOT, false, 60, Approach.NONE,
                    "WaterSolverFeature.nextClick", "WaterSolverFeature.getCountedClicks",
                    "WaterSolverFeature.getOpenedWaterTick", "WaterSolverFeature.getTickCounter"));
        }
    }

    public static final class TeleportMaze extends Base {
        public TeleportMaze() {
            super(new Spec("teleportmaze", "Teleport Maze", "setAutoTeleportMazeEnabled",
                    "isAutoTeleportMazeEnabled", "TeleportMazeSolverConfig", "SimTeleportMazePuzzle", AOTV_SLOT,
                    false, 60, Approach.WALK_ONTO_START_PAD, "TeleportMazeSolverFeature.getVisited",
                    "TeleportMazeSolverFeature.getTeleportSeq", "TeleportMazeSolverFeature.getBest"));
        }
    }

    public static final class Quiz extends Base {
        public Quiz() {
            super(new Spec("quiz", "Quiz", "setAutoQuizEnabled", "isAutoQuizEnabled",
                    "QuizSolverConfig", "SimQuizPuzzle", AOTV_SLOT, false, 60, Approach.QUIZ_SPOT,
                    "QuizSolverFeature.getCorrectAnswerPos"));
        }
    }

    /** Registered last: prints every verdict this run reached, on one line each. */
    public static final class Summary implements FabricClientGameTest {
        @Override
        public void runTest(ClientGameTestContext ctx) {
            if (VERDICTS.isEmpty()) {
                return;
            }
            StringBuilder out = new StringBuilder("[93-solve] SUITE SUMMARY - " + VERDICTS.size() + " puzzle(s), captures from " + ROOM_INSTANCE + ":");
            long passed = VERDICTS.values().stream().filter(v -> v.startsWith("PASS")).count();
            for (Map.Entry<String, String> e : VERDICTS.entrySet()) {
                out.append("\n[93-solve]   ").append(String.format("%-24s %s", e.getKey(), e.getValue()));
            }
            out.append("\n[93-solve]   ").append(passed).append(" of ").append(VERDICTS.size()).append(" solved");
            System.out.println(out);
        }
    }

    // ------------------------------------------------------------------ the scenario

    public abstract static class Base implements FabricClientGameTest {

        private final Spec spec;

        Base(Spec spec) {
            this.spec = spec;
        }

        /**
         * {@code -PsolveRepeat=N} plays the room N times in one client launch, each run named {@code <name>#k},
         * and carries on past a failed run so one launch measures a flake rate. A summary line counts them.
         */
        @Override
        public void runTest(ClientGameTestContext ctx) {
            String base = spec.name();
            if (Scenario.skip(base)) {
                return;
            }
            int reps = Math.max(1, Integer.getInteger("testkit.solveRepeat", 1));
            if (reps == 1) {
                runOnce(ctx, base);
                return;
            }
            List<String> failed = new ArrayList<>();
            for (int r = 1; r <= reps; r++) {
                String name = base + "#" + r;
                try {
                    runOnce(ctx, name);
                } catch (AssertionError | RuntimeException e) {
                    failed.add(name);
                }
                System.out.println("[" + base + "] REPEAT " + r + "/" + reps + ": " + VERDICTS.get(name));
            }
            System.out.println("[" + base + "] REPEAT SUMMARY " + (reps - failed.size()) + " of " + reps
                    + " passed" + (failed.isEmpty() ? "" : "; failed: " + failed));
            if (!failed.isEmpty()) {
                throw new AssertionError(base + ": " + failed.size() + " of " + reps + " runs failed " + failed);
            }
        }

        private void runOnce(ClientGameTestContext ctx, String name) {
            SuiteVerdict.expect(name);
            // A row in summary.md for every room, not only the failures (it reported "0 passed" for ten passes).
            Report.caseStarted(name);
            ModUnderTest.require("killer560smod");
            LogTap.install();
            ctx.waitTicks(20);
            ctx.runOnClient(mc -> ModUnderTest.turnOff(
                    "com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
            if (ctx.computeOnClient(mc -> mc.level != null)) {
                throw new AssertionError("expected the title screen with no world - the previous scenario left "
                        + "one open, so this would not be loading the room the way he does");
            }
            if (!seedRooms(ctx)) {
                VERDICTS.put(name, "SKIPPED - his room captures / room database are not on this machine");
                System.out.println("[" + name + "] SKIPPED - needs his real rooms and room database");
                Report.caseFinished(name, "SKIP", "n/a (singleplayer)", VERDICTS.get(name), List.of(), List.of());
                SuiteVerdict.finished(name);
                return;
            }
            // The SOLVER on from before the room exists, as he plays with it - Quiz's question is announced the
            // moment he is in the room and is never repeated, so a solver switched on later would miss it. The
            // AUTO stays off until the room is on the client and he is where a player would be.
            ctx.runOnClient(mc -> configure(true, false));

            long mark = LogTap.mark();
            String verdict = null;
            try {
                verdict = play(ctx, name, mark);
            } catch (AssertionError | RuntimeException e) {
                verdict = "FAIL - " + String.valueOf(e.getMessage()).lines().findFirst().orElse("");
                dumpEvidence(ctx, name, mark);
                VERDICTS.put(name, verdict);
                System.out.println("[" + name + "] " + verdict);
                throw e;
            } finally {
                allAutosOff(ctx);
                teardown(ctx);
            }
            VERDICTS.put(name, verdict);
            System.out.println("[" + name + "] " + verdict);
            printSummarySoFar();
            if (!verdict.startsWith("PASS")) {
                throw new AssertionError(verdict);
            }
            Report.caseFinished(name, "PASS", "n/a (singleplayer)", verdict, List.of(), LogTap.since(mark));
            SuiteVerdict.finished(name);
        }

        /** @return the verdict line; throws when the premise fails */
        private String play(ClientGameTestContext ctx, String name, long mark) {
            Class<?>[] mcArg = {Minecraft.class};
            // ---- load the room the way he does: from the title screen ---------------------------------
            long before = Scenario.simBuildCount(ctx);
            ctx.runOnClient(mc -> mc.execute(() -> ModUnderTest.staticCall(BUILDER, "buildSingleRoom",
                    new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, spec.room()})));
            ctx.waitFor(mc -> mc.level != null && mc.player != null, 2400);
            Scenario.awaitSimBuild(ctx, before);
            // The loading screen comes down after the build's completion callback; nothing acts under it.
            ctx.waitFor(mc -> McCompat.screen(mc) == null, 1200);
            ctx.waitTicks(40);

            List<String> log = LogTap.since(mark);
            String armed = log.stream().filter(l -> l.contains("puzzle room(s) armed")).reduce((a, b) -> b)
                    .orElse(null);
            println(name, "built " + spec.room() + " (captures from " + ROOM_INSTANCE + "); " + (armed == null ? "NO 'armed' line in the log"
                    : armed.substring(armed.indexOf("Sim puzzles"))));
            if (armed == null || !armed.contains(" 1 of 1 ")) {
                throw new AssertionError(spec.room() + " was not armed as a puzzle (" + armed + ") - nothing for "
                        + "an auto to solve");
            }

            // ---- items --------------------------------------------------------------------------------
            AtomicReference<Boolean> given = new AtomicReference<>();
            ctx.runOnClient(mc -> {
                var server = mc.getSingleplayerServer();
                var uuid = mc.player.getUUID();
                server.execute(() -> {
                    var sp = server.getPlayerList().getPlayer(uuid);
                    if (sp == null) {
                        given.set(false);
                        return;
                    }
                    var inv = sp.getInventory();
                    for (int i = 0; i < 36; i++) {
                        inv.setItem(i, ItemStack.EMPTY);
                    }
                    inv.setItem(AOTV_SLOT, (ItemStack) ModUnderTest.staticCall(SIM_ITEMS, "build",
                            new Class<?>[]{String.class}, new Object[]{AOTV}));
                    inv.setItem(TERM_SLOT, (ItemStack) ModUnderTest.staticCall(SIM_ITEMS, "build",
                            new Class<?>[]{String.class}, new Object[]{TERMINATOR}));
                    given.set(true);
                });
            });
            ctx.waitFor(mc -> given.get() != null, 200);
            ctx.waitTicks(10);
            ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(spec.heldSlot()));
            ctx.waitTicks(10);
            String held = ctx.computeOnClient(mc -> heldName(mc));
            String slot2 = ctx.computeOnClient(mc -> mc.player.getInventory().getItem(TERM_SLOT).getHoverName()
                    .getString());
            println(name, "hotbar: slot 1 " + ctx.computeOnClient(mc -> mc.player.getInventory().getItem(AOTV_SLOT)
                    .getHoverName().getString()) + ", slot 2 " + slot2 + "; in hand: " + held);
            if (!held.contains(spec.heldSlot() == AOTV_SLOT ? "Aspect of the Void" : "Terminator")) {
                throw new AssertionError("the item the auto needs never reached the hand (holding " + held + ")");
            }

            // ---- premise: the room is on the CLIENT, not just the server -----------------------------------
            //
            // Run 1 (2026-10-04) put the player at the spawn with the client still holding no chunk there: he
            // hung "airborne" for ~25 s, the Ice Path solver read an all-air board and the silverfish could not
            // be seen. A real player walks in with the room loaded, so nothing is switched on until he is
            // standing on a block the client itself can see. How long that took is printed, not hidden.
            int landed = -1;
            for (int t = 0; t < 1800; t++) {
                boolean ok = ctx.computeOnClient(mc -> mc.player != null && mc.player.onGround()
                        && !mc.level.getBlockState(mc.player.blockPosition().below()).isAir());
                if (ok) {
                    landed = t;
                    break;
                }
                ctx.waitTicks(1);
            }
            println(name, landed < 0 ? "the client never had the room's chunk under the player in 90 s"
                    : String.format("client has the room: standing on %s after %.1fs",
                    ctx.computeOnClient(mc -> mc.level.getBlockState(mc.player.blockPosition().below())
                            .getBlock().getName().getString()), landed / 20.0));
            if (landed < 0) {
                throw new AssertionError("the room's chunks never reached the client - nothing an auto does "
                        + "could be seen, so this run cannot judge it");
            }
            ctx.waitTicks(20);

            // ---- premise: where he is, what the map says, the puzzle not already solved ------------------
            String liveRoom = ctx.computeOnClient(SimPuzzleSolveTests::liveRoom);
            String start = ctx.computeOnClient(SimPuzzleSolveTests::where);
            boolean already = complete(ctx);
            println(name, "start: " + start + "; live map room: " + liveRoom + "; puzzle complete already: "
                    + already);
            if (!spec.room().equals(liveRoom)) {
                throw new AssertionError("the live map says the player is in '" + liveRoom + "', not '"
                        + spec.room() + "' - every auto gates on that name, so none could act");
            }
            if (already) {
                throw new AssertionError(spec.puzzle() + ".isComplete() was already true before the auto was "
                        + "switched on - a pass would prove nothing");
            }

            // ---- -PicefillControl: prove the Ice Fill sim still breaks on a real mistake ------------------
            IceBreaks iceBreaks = null;
            if ("Ice Fill".equals(spec.room()) && Boolean.getBoolean("testkit.icefillControl")) {
                iceFillControl(ctx, name);
                iceBreaks = new IceBreaks(name);
            }

            // ---- switch it on ---------------------------------------------------------------------------
            ctx.runOnClient(mc -> configure(true, true));
            ctx.waitTicks(2);
            boolean effective = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.call(
                    ModUnderTest.config(AUTO_CONFIG), spec.autoGetter(), new Class<?>[]{}, new Object[]{}));
            boolean map = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.call(
                    ModUnderTest.config(LIVE_MAP_CONFIG), "isInteractiveMapEnabled", new Class<?>[]{},
                    new Object[]{}));
            println(name, "auto on: " + spec.autoGetter() + "=" + effective + ", interactive map=" + map);
            if (!effective) {
                throw new AssertionError(spec.autoGetter() + "() reads false after being switched on - the cheat "
                        + "build or the Skyblock gate is refusing it, so the auto never ran");
            }

            // The human part, ONLY if the auto does not do it itself. A newer auto may walk to the NPCs or onto
            // the start pad on its own (the mod's HEAD added a maze start walk after this jar was built), so it
            // gets ten seconds to show it does before the scenario steps in - and the line says which it was.
            if (spec.approach() != Approach.NONE) {
                double[] p0 = ctx.computeOnClient(mc -> new double[]{mc.player.getX(), mc.player.getY(),
                        mc.player.getZ()});
                int seq0 = mazeSeq(ctx);
                boolean byItself = false;
                for (int t = 0; t < 200 && !byItself; t++) {
                    ctx.waitTicks(1);
                    double[] p = ctx.computeOnClient(mc -> new double[]{mc.player.getX(), mc.player.getY(),
                            mc.player.getZ()});
                    double moved = Math.sqrt(sq(p[0] - p0[0]) + sq(p[1] - p0[1]) + sq(p[2] - p0[2]));
                    byItself = complete(ctx) || moved > 2.0
                            || (spec.approach() == Approach.WALK_ONTO_START_PAD && mazeSeq(ctx) != seq0);
                }
                if (byItself) {
                    println(name, "the auto approached by itself - no human input driven");
                } else {
                    println(name, "the auto did not approach in 10 s (it never moves the player for this) - "
                            + "driving the human part: " + spec.approach());
                    approach(ctx, name);
                }
            }

            // ---- watch ------------------------------------------------------------------------------------
            // Each forced break costs its 2 s regeneration plus the warp back; give the run room for them.
            int limit = (spec.seconds() + (iceBreaks != null ? 60 : 0)) * 20;
            int failedAt = -1;
            int chestAt = -1;
            String chestWhat = null;
            int solvedAt = -1;
            double[] startPos = ctx.computeOnClient(mc -> new double[]{mc.player.getX(), mc.player.getY(),
                    mc.player.getZ()});
            double travelled = 0;
            double[] last = startPos;
            // -PserverStallMs=N (with -PserverStallEvery=T, default 13 ticks): freeze the integrated server's thread
            // for N ms every T client ticks while the auto plays. A stalled server then handles every packet that
            // queued up in one go, the way a lag spike does - two use packets in one server tick, two position
            // packets reaching the client between two of its ticks. Added 2026-10-05 for the Ice Fill flake.
            int stallMs = Integer.getInteger("testkit.serverStallMs", 0);
            int stallEvery = Math.max(2, Integer.getInteger("testkit.serverStallEvery", 13));
            if (stallMs > 0) {
                println(name, "server stall injection: " + stallMs + " ms every " + stallEvery + " ticks");
            }
            // -PnetStallMs=N: the same, but freezing the CLIENT connection's netty event loop instead. The client
            // gametest runs client and integrated server in lockstep, so a server-thread sleep only slows both and
            // changes nothing (measured: 6/6 identical runs). Packets cross on netty threads outside that lock;
            // holding them back makes several land in one client tick, which is what machine load can do.
            int netStallMs = Integer.getInteger("testkit.netStallMs", 0);
            if (netStallMs > 0) {
                println(name, "client network stall injection: " + netStallMs + " ms every " + stallEvery
                        + " ticks");
            }
            for (int t = 1; t <= limit; t++) {
                ctx.waitTicks(1);
                if (stallMs > 0 && t % stallEvery == 0) {
                    ctx.runOnClient(mc -> mc.getSingleplayerServer().execute(() -> {
                        try {
                            Thread.sleep(stallMs);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }));
                }
                if (netStallMs > 0 && t % stallEvery == 0) {
                    ctx.runOnClient(mc -> {
                        try {
                            java.lang.reflect.Field f = net.minecraft.network.Connection.class
                                    .getDeclaredField("channel");
                            f.setAccessible(true);
                            io.netty.channel.Channel ch = (io.netty.channel.Channel) f.get(
                                    mc.getConnection().getConnection());
                            ch.eventLoop().execute(() -> {
                                try {
                                    Thread.sleep(netStallMs);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            });
                        } catch (ReflectiveOperationException e) {
                            throw new RuntimeException("netStallMs: no Connection.channel", e);
                        }
                    });
                }
                Object[] s = ctx.computeOnClient(mc -> {
                    String open = null;
                    if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) {
                        open = mc.player.containerMenu.getClass().getSimpleName()
                                + (McCompat.screen(mc) == null ? "" : " / " + McCompat.screen(mc).getTitle().getString());
                        // Closed for him, as he would: a container left open stops every auto
                        // (they all refuse to act with a screen up).
                        mc.player.closeContainer();
                    }
                    return new Object[]{open, mc.player == null ? null
                            : new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()}};
                });
                if (s[0] != null && chestAt < 0) {
                    chestAt = t;
                    chestWhat = (String) s[0];
                    println(name, String.format("t=%.1fs a container opened: %s at %s", t / 20.0, chestWhat,
                            ctx.computeOnClient(SimPuzzleSolveTests::where)));
                }
                if (s[1] != null) {
                    double[] p = (double[]) s[1];
                    travelled += Math.sqrt(sq(p[0] - last[0]) + sq(p[1] - last[1]) + sq(p[2] - last[2]));
                    last = p;
                }
                if (iceBreaks != null) {
                    iceBreaks.tick(ctx, t);
                }
                if (failedAt < 0 && failed(ctx)) {
                    failedAt = t;
                    println(name, String.format("t=%.1fs the sim marked %s FAILED", t / 20.0, spec.room()));
                }
                if (solvedAt < 0 && complete(ctx)) {
                    solvedAt = t;
                    println(name, String.format("t=%.1fs %s.isComplete() = true", t / 20.0, spec.puzzle()));
                }
                boolean done = spec.chestIsVerdict() ? chestAt > 0 : solvedAt > 0;
                if (done) {
                    // A few ticks more, so a fail painted right after the last move is still seen.
                    ctx.waitTicks(20);
                    if (failedAt < 0 && failed(ctx)) {
                        failedAt = t;
                    }
                    break;
                }
                if (t % 100 == 0) {
                    println(name, String.format("t=%2ds %s, held %s, travelled %.1f, complete=%s, failed=%s; %s",
                            t / 20, ctx.computeOnClient(SimPuzzleSolveTests::where),
                            ctx.computeOnClient(SimPuzzleSolveTests::heldName), travelled, complete(ctx),
                            failed(ctx), probe(ctx)));
                }
            }
            ctx.runOnClient(mc -> configure(true, false));

            List<String> tail = relevant(LogTap.since(mark));
            println(name, "last auto/sim lines:");
            for (String l : tail.subList(Math.max(0, tail.size() - 12), tail.size())) {
                println(name, "  | " + l);
            }

            String extra = String.format(" (travelled %.1f blocks%s%s)", travelled,
                    chestAt > 0 ? String.format(", container at %.1fs", chestAt / 20.0) : "",
                    spec.chestIsVerdict() ? ", sim isComplete=" + complete(ctx) : "");
            if (failedAt > 0) {
                dumpEvidence(ctx, name, mark);
                return String.format("FAIL - the sim marked the room FAILED at %.1fs%s", failedAt / 20.0, extra);
            }
            if (spec.chestIsVerdict()) {
                if (chestAt > 0) {
                    return String.format("PASS - reward chest opened at %.1fs%s", chestAt / 20.0, extra);
                }
                dumpEvidence(ctx, name, mark);
                return "FAIL - no chest was opened in " + spec.seconds() + "s" + extra;
            }
            if (solvedAt > 0 && "SimBlazePuzzle".equals(spec.puzzle())) {
                // A blaze pass only counts if the player shot: the chain must have died to his arrows.
                int arrows = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(
                        "com.killer560.hub.roomsim.SimTerminator", "arrowsFired"));
                if (arrows == 0) {
                    dumpEvidence(ctx, name, mark);
                    return "FAIL - complete, but the Terminator fired no arrow" + extra;
                }
                return String.format("PASS - solved at %.1fs, %d Terminator arrows%s", solvedAt / 20.0, arrows,
                        extra);
            }
            if (iceBreaks != null) {
                String broke = iceBreaks.verdict();
                if (broke != null) {
                    dumpEvidence(ctx, name, mark);
                    return "FAIL - break recovery: " + broke + (solvedAt > 0 ? " (solved anyway)" : " (not solved)")
                            + extra;
                }
                extra = ", " + iceBreaks.summary() + extra;
            }
            if (solvedAt > 0) {
                return String.format("PASS - solved at %.1fs%s", solvedAt / 20.0, extra);
            }
            dumpEvidence(ctx, name, mark);
            return "FAIL - not solved in " + spec.seconds() + "s" + extra + "; last: "
                    + (tail.isEmpty() ? "(no auto/sim log line at all)" : tail.get(tail.size() - 1));
        }

        private boolean complete(ClientGameTestContext ctx) {
            return ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(PUZZLES + spec.puzzle(),
                    "isComplete", new Class<?>[]{}, new Object[]{}));
        }

        private boolean failed(ClientGameTestContext ctx) {
            return ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(ROOM_STATE, "isFailed",
                    new Class<?>[]{String.class}, new Object[]{spec.room()}));
        }

        /** The solver's own state, for the status line - what the auto is reading. */
        private String probe(ClientGameTestContext ctx) {
            return ctx.computeOnClient(mc -> {
                StringBuilder sb = new StringBuilder();
                for (String p : spec.probes()) {
                    boolean simField = p.startsWith("sim:");
                    if (simField) {
                        p = p.substring(4);
                    }
                    int dot = p.indexOf('.');
                    String v;
                    try {
                        Object o;
                        if (simField) {
                            var f = Class.forName(PUZZLES + p.substring(0, dot)).getDeclaredField(p.substring(dot + 1));
                            f.setAccessible(true);
                            o = f.get(null);
                            if (o instanceof boolean[] flags) {
                                int n = 0;
                                for (boolean b : flags) {
                                    n += b ? 1 : 0;
                                }
                                o = n + " of " + flags.length + " true";
                            }
                        } else {
                            o = ModUnderTest.staticCall(SOLVERS + p.substring(0, dot), p.substring(dot + 1),
                                    new Class<?>[]{}, new Object[]{});
                        }
                        v = o instanceof java.util.Collection<?> c ? c.size() + " item(s)"
                                : o instanceof net.minecraft.world.entity.Entity e ? e.blockPosition().toShortString()
                                : o instanceof net.minecraft.core.BlockPos b ? b.toShortString()
                                : String.valueOf(o);
                    } catch (Throwable t) {
                        v = "(unreadable)";
                    }
                    sb.append(sb.isEmpty() ? "" : ", ").append(p.substring(dot + 1)).append('=').append(v);
                }
                return sb.toString();
            });
        }

        /** Drives the one thing a human does here too - see {@link Approach}. */
        private void approach(ClientGameTestContext ctx, String name) {
            switch (spec.approach()) {
                case QUIZ_SPOT -> {
                    // The standable spot in that column nearest his own height: the first version scanned down
                    // from relative y 75 and put him on a ledge 12 blocks over the floor (2026-10-04, run 2).
                    double[] spot = ctx.computeOnClient(mc -> floorNear(mc, rel(mc, 15, 69, 6)));
                    teleport(ctx, name, spot, "between the three answer pillars (room-relative 15, 6)");
                }
                case NEAR_WEIRDOS -> {
                    double[] spot = ctx.computeOnClient(SimPuzzleSolveTests::weirdosSpot);
                    teleport(ctx, name, spot, "within entity reach of all three weirdos");
                    // What Auto Three Weirdos will measure: eye to each "CLICK" stand's box, against its 3.0.
                    println(name, "CLICK stands from here: " + ctx.computeOnClient(mc -> {
                        StringBuilder sb = new StringBuilder();
                        for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
                            if (e instanceof net.minecraft.world.entity.decoration.ArmorStand
                                    && e.getName().getString().contains("CLICK")) {
                                var box = e.getBoundingBox();
                                var eye = mc.player.getEyePosition();
                                double dx = Math.max(0, Math.max(box.minX - eye.x, eye.x - box.maxX));
                                double dy = Math.max(0, Math.max(box.minY - eye.y, eye.y - box.maxY));
                                double dz = Math.max(0, Math.max(box.minZ - eye.z, eye.z - box.maxZ));
                                sb.append(String.format("[%s at %.1f,%.1f,%.1f box %.2fx%.2f, eye-to-box %.2f] ",
                                        e.getName().getString(), e.getX(), e.getY(), e.getZ(), box.getXsize(),
                                        box.getYsize(), Math.sqrt(dx * dx + dy * dy + dz * dz)));
                            }
                        }
                        return sb.toString();
                    }));
                }
                case WALK_ONTO_START_PAD -> {
                    net.minecraft.core.BlockPos pad = ctx.computeOnClient(mc -> rel(mc, 15, 69, 12));
                    int seq0 = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(
                            SOLVERS + "TeleportMazeSolverFeature", "getTeleportSeq", new Class<?>[]{},
                            new Object[]{}));
                    println(name, "walking onto the start pad " + pad.toShortString() + " with the forward key");
                    ctx.runOnClient(mc -> {
                        double dx = pad.getX() + 0.5 - mc.player.getX();
                        double dz = pad.getZ() + 0.5 - mc.player.getZ();
                        mc.player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
                        mc.player.setXRot(0f);
                    });
                    ctx.getInput().holdKey(o -> o.keyUp);
                    int t = 0;
                    try {
                        for (; t < 200; t++) {
                            ctx.waitTicks(1);
                            int seq = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(
                                    SOLVERS + "TeleportMazeSolverFeature", "getTeleportSeq", new Class<?>[]{},
                                    new Object[]{}));
                            if (seq != seq0) {
                                break;
                            }
                        }
                    } finally {
                        ctx.getInput().releaseKey(o -> o.keyUp);
                    }
                    println(name, t < 200 ? String.format("first maze teleport after %.1fs of walking", t / 20.0)
                            : "no maze teleport after 10 s of walking at the start pad - now at "
                            + ctx.computeOnClient(SimPuzzleSolveTests::where));
                }
                default -> {
                }
            }
        }

        /** This scenario's settings. Every other auto stays off. */
        private void configure(boolean solverOn, boolean on) {
            Object auto = ModUnderTest.config(AUTO_CONFIG);
            for (String setter : ALL_AUTOS) {
                ModUnderTest.set(auto, setter, false);
            }
            ModUnderTest.set(auto, "setAutoPuzzlesMasterEnabled", on);
            ModUnderTest.set(auto, "setEtherwarpReposition", true);
            ModUnderTest.set(auto, "setAutoPuzzlePathingEnabled", true);
            ModUnderTest.set(auto, "setShootCooldownMs", 500);
            ModUnderTest.set(auto, "setAutoBlazeSecretEnabled", false);
            ModUnderTest.set(auto, "setTicTacToeAuraChestEnabled", false);
            ModUnderTest.set(auto, "setTicTacToeWalkOutEnabled", false);
            ModUnderTest.set(auto, "setIceFillAdaptive", false);
            ModUnderTest.set(auto, "setIceFillDelayTicks", 2);
            // Three Weirdos' NPCs only speak when talked to - on Hypixel as in here - and the auto only picks a
            // chest once all three have spoken. Its own "talk to NPCs" option does that; without it this room
            // needs a human. Recorded as the one setting that stands in for him.
            ModUnderTest.set(auto, "setWeirdosTalkToNpcs", true);
            Object solver = ModUnderTest.config(SOLVERS + spec.solver());
            ModUnderTest.set(solver, "setEnabled", solverOn);
            if (spec.solver().equals("IceFillSolverConfig")) {
                // Auto Ice Fill refuses to run on the optimized path and says so.
                ModUnderTest.set(solver, "setOptimizedPath", false);
            }
            Object map = ModUnderTest.config(LIVE_MAP_CONFIG);
            ModUnderTest.set(map, "setEnabled", true);
            ModUnderTest.set(map, "setInteractiveMapEnabled", true);
            if (on) {
                ModUnderTest.set(auto, spec.autoSetter(), true);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void allAutosOff(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            try {
                Object auto = ModUnderTest.config(AUTO_CONFIG);
                for (String setter : ALL_AUTOS) {
                    ModUnderTest.set(auto, setter, false);
                }
                ModUnderTest.set(auto, "setAutoPuzzlesMasterEnabled", false);
            } catch (Throwable ignored) {
                // cleanup must not become the failure
            }
        });
    }

    private static void printSummarySoFar() {
        long passed = VERDICTS.values().stream().filter(v -> v.startsWith("PASS")).count();
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : VERDICTS.entrySet()) {
            sb.append(sb.isEmpty() ? "" : ", ").append(e.getKey().replace("93-solve-", ""))
                    .append(e.getValue().startsWith("PASS") ? " PASS" : e.getValue().startsWith("SKIP") ? " SKIP"
                            : " FAIL");
        }
        System.out.println("[93-solve] so far " + passed + "/" + VERDICTS.size() + ": " + sb);
    }

    /** Lines worth reading when an auto stalls: the autos, the sim, the solvers, and every chat line. */
    private static List<String> relevant(List<String> lines) {
        List<String> out = new ArrayList<>();
        for (String l : lines) {
            if (l.contains("(autopuzzles)") || l.contains("(roomsim)") || l.contains("(puzzles)")
                    || l.contains("(puzzlesolvers)") || l.contains("(interactivemap)") || l.contains("[CHAT]")
                    || l.contains("[Auto") || l.contains("Solver")) {
                out.add(l);
            }
        }
        return out;
    }

    private static void dumpEvidence(ClientGameTestContext ctx, String name, long mark) {
        try {
            List<String> lines = relevant(LogTap.since(mark));
            println(name, "---- evidence: last " + Math.min(60, lines.size()) + " of " + lines.size()
                    + " auto/sim/chat line(s) ----");
            for (String l : lines.subList(Math.max(0, lines.size() - 60), lines.size())) {
                println(name, "  | " + l);
            }
            println(name, "player " + ctx.computeOnClient(SimPuzzleSolveTests::where) + ", holding "
                    + ctx.computeOnClient(SimPuzzleSolveTests::heldName) + ", live map room "
                    + ctx.computeOnClient(SimPuzzleSolveTests::liveRoom) + ", screen "
                    + ctx.computeOnClient(mc -> McCompat.screen(mc) == null ? "none" : McCompat.screen(mc).getClass().getSimpleName()));
            println(name, "---- end evidence ----");
        } catch (Throwable t) {
            println(name, "could not gather evidence: " + t);
        }
    }

    private static String heldName(Minecraft mc) {
        if (mc.player == null) {
            return "(no player)";
        }
        ItemStack s = mc.player.getMainHandItem();
        return s.isEmpty() ? "nothing" : s.getHoverName().getString();
    }

    private static String where(Minecraft mc) {
        if (mc.player == null) {
            return "(no player)";
        }
        return String.format("(%.2f, %.2f, %.2f) yaw %.0f pitch %.0f%s%s", mc.player.getX(), mc.player.getY(),
                mc.player.getZ(), mc.player.getYRot(), mc.player.getXRot(),
                mc.player.onGround() ? " on ground" : " airborne", mc.player.isShiftKeyDown() ? " sneaking" : "");
    }

    private static String liveRoom(Minecraft mc) {
        try {
            Object entry = ModUnderTest.staticCall(LIVE_MAP, "currentRoomEntry", new Class<?>[]{}, new Object[]{});
            if (entry == null) {
                return null;
            }
            return String.valueOf(entry.getClass().getField("name").get(entry));
        } catch (ReflectiveOperationException e) {
            return "(unreadable: " + e + ")";
        }
    }

    /** Teleport Maze solver's teleport counter, or -1 when it cannot be read. */
    private static int mazeSeq(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            try {
                return (Integer) ModUnderTest.staticCall(SOLVERS + "TeleportMazeSolverFeature", "getTeleportSeq",
                        new Class<?>[]{}, new Object[]{});
            } catch (Throwable t) {
                return -1;
            }
        });
    }

    /** A room-relative block of the room the live map says he is in. */
    private static net.minecraft.core.BlockPos rel(Minecraft mc, int x, int y, int z) {
        int[] cr = (int[]) ModUnderTest.staticCall(LIVE_MAP, "currentRoomClayAndRotation", new Class<?>[]{},
                new Object[]{});
        return (net.minecraft.core.BlockPos) ModUnderTest.staticCall(SOLVERS + "PuzzleCoords", "real",
                new Class<?>[]{int.class, int.class, int.class, int[].class}, new Object[]{x, y, z, cr});
    }

    /** Feet position on the first solid block at or below {@code from} with two blocks of air over it. */
    private static double[] standAt(Minecraft mc, net.minecraft.core.BlockPos from) {
        for (int dy = 0; dy < 16; dy++) {
            net.minecraft.core.BlockPos b = from.below(dy);
            if (!mc.level.getBlockState(b).isAir() && mc.level.getBlockState(b.above()).isAir()
                    && mc.level.getBlockState(b.above(2)).isAir()) {
                return new double[]{b.getX() + 0.5, b.getY() + 1, b.getZ() + 0.5};
            }
        }
        return null;
    }

    /** The standable spot in this block's column whose feet are nearest the player's own height. */
    private static double[] floorNear(Minecraft mc, net.minecraft.core.BlockPos column) {
        double[] best = null;
        for (int dy = -12; dy <= 12; dy++) {
            net.minecraft.core.BlockPos b = column.offset(0, dy, 0);
            if (!mc.level.getBlockState(b).isAir() && mc.level.getBlockState(b.above()).isAir()
                    && mc.level.getBlockState(b.above(2)).isAir()) {
                double feet = b.getY() + 1;
                if (best == null || Math.abs(feet - mc.player.getY()) < Math.abs(best[1] - mc.player.getY())) {
                    best = new double[]{b.getX() + 0.5, feet, b.getZ() + 0.5};
                }
            }
        }
        return best;
    }

    /**
     * The standable block near the three "CLICK" stands whose eye is closest to the FARTHEST of them, measured
     * eye to box as Auto Three Weirdos measures it. Run 3 (2026-10-04) placed him by a line through the stands,
     * which the sim does not lay them on - one stand ended up 3.75 away, past the auto's 3.0.
     */
    private static double[] weirdosSpot(Minecraft mc) {
        List<net.minecraft.world.entity.Entity> clicks = new ArrayList<>();
        for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof net.minecraft.world.entity.decoration.ArmorStand
                    && e.getName().getString().contains("CLICK") && e.distanceTo(mc.player) < 40) {
                clicks.add(e);
            }
        }
        if (clicks.size() != 3) {
            return null;
        }
        double cx = 0;
        double cz = 0;
        double y = 0;
        for (var e : clicks) {
            cx += e.getX() / 3;
            cz += e.getZ() / 3;
            y += e.getY() / 3;
        }
        double[] best = null;
        double bestWorst = Double.MAX_VALUE;
        net.minecraft.core.BlockPos c0 = net.minecraft.core.BlockPos.containing(cx, y, cz);
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = -3; dy <= 3; dy++) {
                    net.minecraft.core.BlockPos b = c0.offset(dx, dy, dz);
                    if (mc.level.getBlockState(b).isAir() || !mc.level.getBlockState(b.above()).isAir()
                            || !mc.level.getBlockState(b.above(2)).isAir()) {
                        continue;
                    }
                    var eye = new net.minecraft.world.phys.Vec3(b.getX() + 0.5, b.getY() + 1 + 1.62, b.getZ() + 0.5);
                    double worst = 0;
                    boolean inside = false;
                    for (var e : clicks) {
                        var box = e.getBoundingBox();
                        double ex = Math.max(0, Math.max(box.minX - eye.x, eye.x - box.maxX));
                        double ey = Math.max(0, Math.max(box.minY - eye.y, eye.y - box.maxY));
                        double ez = Math.max(0, Math.max(box.minZ - eye.z, eye.z - box.maxZ));
                        worst = Math.max(worst, Math.sqrt(ex * ex + ey * ey + ez * ez));
                        // Not standing in a weirdo: his feet block must not be one a stand stands in.
                        inside |= net.minecraft.core.BlockPos.containing(e.getX(), e.getY(), e.getZ())
                                .equals(b.above());
                    }
                    if (!inside && worst < bestWorst) {
                        bestWorst = worst;
                        best = new double[]{b.getX() + 0.5, b.getY() + 1, b.getZ() + 0.5};
                    }
                }
            }
        }
        return best;
    }

    /**
     * The negative control for Ice Fill's judge (added 2026-10-05, when the judge moved from the client tick to
     * the server). A pass only shows the judge never broke a section on a legal run; this shows it still breaks
     * one on the two mistakes a test can make without input: arriving two tiles from the last one ("teleported
     * off the ice"), and stepping back onto a tile already used ("stepped on ice you had already used"). Both are
     * plain server teleports, so they exercise the once-a-tick judge; the auto's own run exercises the
     * per-landing one. Waits out each regeneration and leaves him on the first tile.
     */
    @SuppressWarnings("unchecked")
    private static void iceFillControl(ClientGameTestContext ctx, String name) {
        List<net.minecraft.world.phys.Vec3> path = ctx.computeOnClient(mc ->
                (List<net.minecraft.world.phys.Vec3>) ModUnderTest.staticCall(
                        "com.killer560.hub.puzzlesolvers.IceFillSolverFeature", "getCurrentPath",
                        new Class<?>[]{}, new Object[]{}));
        if (path == null || path.size() < 2) {
            throw new AssertionError("ice fill control: the solver has no path to take tiles from (" + path + ")");
        }
        var p0 = path.get(0);
        var p1 = path.get(1);
        double dx = Math.signum(p1.x - p0.x);
        double dz = Math.signum(p1.z - p0.z);
        // Solver points are feet + 0.1.
        double[] first = {p0.x, p0.y - 0.1, p0.z};
        double[] next = {p0.x + dx, p0.y - 0.1, p0.z + dz};
        double[] far = {p0.x + 2 * dx, p0.y - 0.1, p0.z + 2 * dz};

        long a = LogTap.mark();
        teleport(ctx, name, first, "control: first tile");
        teleport(ctx, name, far, "control: two tiles on");
        boolean warpCaught = LogTap.since(a).stream().anyMatch(l -> l.contains("teleported off the ice"));
        ctx.waitTicks(50);   // REGEN_TICKS is 40

        long b = LogTap.mark();
        teleport(ctx, name, first, "control: first tile again");
        teleport(ctx, name, next, "control: next tile");
        teleport(ctx, name, first, "control: back onto the used first tile");
        boolean repeatCaught = LogTap.since(b).stream()
                .anyMatch(l -> l.contains("stepped on ice you had already used"));
        ctx.waitTicks(50);
        teleport(ctx, name, first, "control: first tile for the auto");
        println(name, "ice fill control: two-tile jump broke the section = " + warpCaught
                + ", stepping back on a used tile broke it = " + repeatCaught);
        if (!warpCaught || !repeatCaught) {
            throw new AssertionError("ice fill control: the sim did not break a section on a real mistake "
                    + "(two-tile jump " + warpCaught + ", repeated tile " + repeatCaught + ")");
        }
    }

    /**
     * Mistakes forced WHILE Auto Ice Fill plays (-PicefillControl, added 2026-10-05 for the break recovery).
     *
     * <p>killer560 on Hypixel: a broken section regenerates "after two-ish seconds", only that section, and the auto
     * "needs to pause everything that it is doing until it regenerates then ... teleport back onto the ice fill
     * starting position and continue." So, for each planned section (1-based, {@code -PicefillBreaks}, default
     * "1,2"): once the auto is half way across it, he is put back onto the tile he just left - the classic mistake,
     * judged by the sim's own server-side judge - and then the SIM is read, not the auto's log:
     *
     * <ul>
     *   <li>the sim broke that section (its break counter went up, naming that section);</li>
     *   <li>no sim teleport landed while it was broken, after {@link #GRACE} ticks for a hop already in flight
     *       when the break happened (on Hypixel that is the ping);</li>
     *   <li>it regenerated;</li>
     *   <li>the first landing after that was on that section's entry tile (the auto's warp back);</li>
     * </ul>
     * and the run still has to end with the sim's own isComplete.
     */
    static final class IceBreaks {
        static final int GRACE = 4;
        private static final String P = PUZZLES + "SimIceFillPuzzle";

        private final String name;
        private final List<Integer> plan = new ArrayList<>();   // 0-based sections still to break
        private final List<String> results = new ArrayList<>();
        private final List<String> problems = new ArrayList<>();
        private int target = -1;          // section a forced mistake was just made in
        private int forcedAt = -1;
        private int breaksBefore;
        private int brokenAt = -1;
        private int landingsAtBreak;
        private int landedWhileBroken;
        private int regenAt = -1;
        private int landingsAtRegen;

        IceBreaks(String name) {
            this.name = name;
            String spec = System.getProperty("testkit.icefillBreaks", "1,2");
            for (String s : spec.split(",")) {
                if (!s.isBlank()) {
                    plan.add(Integer.parseInt(s.trim()) - 1);
                }
            }
            println(name, "ice fill breaks planned while the auto plays: sections " + spec);
        }

        private static Object sim(ClientGameTestContext ctx, String method) {
            return ctx.computeOnClient(mc -> ModUnderTest.staticCall(P, method, new Class<?>[]{}, new Object[]{}));
        }

        void tick(ClientGameTestContext ctx, int t) {
            if (target < 0) {
                if (plan.isEmpty()) {
                    return;
                }
                maybeForce(ctx, t);
                return;
            }
            boolean broken = (Boolean) sim(ctx, "isBroken");
            int landings = (Integer) sim(ctx, "landings");
            if (brokenAt < 0) {
                if ((Integer) sim(ctx, "breaks") > breaksBefore) {
                    brokenAt = t;
                    landingsAtBreak = landings;
                    int which = (Integer) sim(ctx, "lastBrokenSection");
                    println(name, String.format("t=%.1fs forced break: the sim broke section %d", t / 20.0, which + 1));
                    if (which != target) {
                        problems.add("forced a mistake in section " + (target + 1) + " but the sim broke " + (which + 1));
                    }
                } else if (t - forcedAt > 20) {
                    problems.add("the forced mistake in section " + (target + 1) + " did not break it in 1 s");
                    target = -1;
                }
                return;
            }
            if (regenAt < 0) {
                if (t - brokenAt == GRACE) {
                    landingsAtBreak = landings;   // whatever was in flight at the break has landed by now
                }
                if (broken) {
                    return;
                }
                regenAt = t;
                landedWhileBroken = landings - landingsAtBreak;
                landingsAtRegen = landings;
                println(name, String.format("t=%.1fs section %d regenerated after %.1fs; landings while broken "
                        + "(after a %d-tick grace): %d", t / 20.0, target + 1, (t - brokenAt) / 20.0, GRACE,
                        landedWhileBroken));
                if (landedWhileBroken != 0) {
                    problems.add(landedWhileBroken + " teleport(s) landed while section " + (target + 1)
                            + " was broken - the auto did not pause");
                }
                return;
            }
            if (landings > landingsAtRegen) {
                var tile = (net.minecraft.core.BlockPos) sim(ctx, "lastLandingTile");
                var entry = (net.minecraft.core.BlockPos) ctx.computeOnClient(mc -> ModUnderTest.staticCall(P,
                        "entryTile", new Class<?>[]{int.class}, new Object[]{target}));
                boolean ok = tile != null && tile.equals(entry);
                String line = String.format("section %d: broke at %.1fs, back %.1fs later, first landing after "
                                + "%.1fs on %s (entry %s) %s", target + 1, brokenAt / 20.0, (regenAt - brokenAt) / 20.0,
                        (t - regenAt) / 20.0, tile == null ? "?" : tile.toShortString(),
                        entry == null ? "?" : entry.toShortString(), ok ? "OK" : "WRONG TILE");
                println(name, "t=" + String.format("%.1fs ", t / 20.0) + line);
                results.add(line);
                if (!ok) {
                    problems.add("after section " + (target + 1) + " regenerated the first landing was "
                            + (tile == null ? "?" : tile.toShortString()) + ", not its entry tile "
                            + (entry == null ? "?" : entry.toShortString()));
                }
                target = -1;
            } else if (t - regenAt > 300) {
                problems.add("nothing landed in 15 s after section " + (target + 1) + " regenerated - no warp back");
                target = -1;
            }
        }

        /** Once the auto is half way across the next planned section, put him back on the tile he just left. */
        private void maybeForce(ClientGameTestContext ctx, int t) {
            int section = plan.get(0);
            if ((Boolean) sim(ctx, "isBroken") || (Integer) sim(ctx, "activeSection") != section) {
                return;
            }
            double[] back = ctx.computeOnClient(mc -> {
                List<net.minecraft.core.BlockPos> tiles = sectionTiles(mc, section);
                if (tiles.isEmpty() || !mc.player.onGround()) {
                    return null;
                }
                int i = tiles.indexOf(mc.player.blockPosition().below());
                if (i < Math.max(2, tiles.size() / 2) || i >= tiles.size() - 1) {
                    return null;
                }
                var prev = tiles.get(i - 1);
                if (!mc.level.getBlockState(prev).is(net.minecraft.world.level.block.Blocks.PACKED_ICE)) {
                    return null;
                }
                return new double[]{prev.getX() + 0.5, prev.getY() + 1, prev.getZ() + 0.5, i, tiles.size()};
            });
            if (back == null) {
                return;
            }
            plan.remove(0);
            target = section;
            forcedAt = t;
            brokenAt = -1;
            regenAt = -1;
            breaksBefore = (Integer) sim(ctx, "breaks");
            println(name, String.format("t=%.1fs forcing a mistake in section %d: on tile %d of %d, stepping back "
                    + "onto the used tile before it", t / 20.0, section + 1, (int) back[3], (int) back[4]));
            ctx.runOnClient(mc -> {
                var server = mc.getSingleplayerServer();
                var uuid = mc.player.getUUID();
                server.execute(() -> {
                    var sp = server.getPlayerList().getPlayer(uuid);
                    if (sp != null) {
                        sp.teleportTo(back[0], back[1], back[2]);
                        // Judged as a landing, as a sim ability teleport is. Without this the once-a-tick judge only
                        // saw where the auto's next hop (handled later in the same server tick) put him, and the
                        // mistake went unjudged (first run, 2026-10-05: section 1 never broke).
                        ModUnderTest.staticCall(P, "onTeleport",
                                new Class<?>[]{net.minecraft.server.level.ServerPlayer.class}, new Object[]{sp});
                    }
                });
            });
        }

        /** Section {@code s}'s tiles in walking order: the solver's corners, filled in one block at a time. */
        @SuppressWarnings("unchecked")
        private static List<net.minecraft.core.BlockPos> sectionTiles(Minecraft mc, int s) {
            List<net.minecraft.world.phys.Vec3> path = (List<net.minecraft.world.phys.Vec3>) ModUnderTest.staticCall(
                    SOLVERS + "IceFillSolverFeature", "getCurrentPath", new Class<?>[]{}, new Object[]{});
            List<Long> heights = new ArrayList<>();
            List<net.minecraft.world.phys.Vec3> mine = new ArrayList<>();
            for (var p : path) {
                long key = Math.round(p.y * 10);
                if (!heights.contains(key)) {
                    heights.add(key);
                }
                if (heights.indexOf(key) == s) {
                    mine.add(p);
                }
            }
            List<net.minecraft.core.BlockPos> out = new ArrayList<>();
            for (int i = 0; i < mine.size(); i++) {
                var a = mine.get(i);
                var b = i + 1 < mine.size() ? mine.get(i + 1) : a;
                int steps = (int) Math.round(Math.max(Math.abs(b.x - a.x), Math.abs(b.z - a.z)));
                for (int k = 0; k < Math.max(1, steps); k++) {
                    double r = steps == 0 ? 0 : (double) k / steps;
                    var tile = net.minecraft.core.BlockPos.containing(a.x + (b.x - a.x) * r, a.y, a.z + (b.z - a.z) * r)
                            .below();
                    if (out.isEmpty() || !out.get(out.size() - 1).equals(tile)) {
                        out.add(tile);
                    }
                }
            }
            return out;
        }

        /** Null when every planned break was forced and recovered from; otherwise what went wrong. */
        String verdict() {
            List<String> all = new ArrayList<>(problems);
            if (target >= 0) {
                all.add("section " + (target + 1) + ": the run ended mid-recovery");
            }
            for (int s : plan) {
                all.add("section " + (s + 1) + ": never reached half way, so no mistake was forced there");
            }
            return all.isEmpty() ? null : String.join("; ", all);
        }

        String summary() {
            return results.size() + " forced break(s) recovered";
        }
    }

    /** A server teleport, which is where the client is told to be - the sim's own /goto does the same. */
    private static void teleport(ClientGameTestContext ctx, String name, double[] spot, String what) {
        if (spot == null) {
            throw new AssertionError("could not find a standable spot " + what + " - the approach the auto "
                    + "needs could not be driven");
        }
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(uuid);
                if (sp != null) {
                    sp.teleportTo(spot[0], spot[1], spot[2]);
                }
            });
        });
        ctx.waitTicks(20);
        println(name, "placed " + what + ": now " + ctx.computeOnClient(SimPuzzleSolveTests::where));
    }

    private static double sq(double v) {
        return v * v;
    }

    private static void println(String name, String line) {
        System.out.println("[" + name + "] " + line);
    }

    static boolean seedRooms(ClientGameTestContext ctx) {
        if (copyDir(SOURCE_ROOMS, "killer560smod-rooms", ".json") < 20
                || copyDir(Path.of(SOURCE_ROOMS).resolveSibling("killer560smod-roomdata").toString(),
                        "killer560smod-roomdata", "") == 0) {
            return false;
        }
        ctx.runOnClient(mc -> ModUnderTest.staticCall(
                "com.killer560.hub.roomdatabase.RoomDatabase", "ensureLoading"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(
                "com.killer560.hub.roomdatabase.RoomDatabase", "isReady"), 2400);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);
        return true;
    }

    static void teardown(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            try {
                ModUnderTest.staticCall(SIM_STATE, "leave", new Class<?>[]{}, new Object[]{});
            } catch (Throwable ignored) {
                // never let cleanup replace the verdict
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
                        Files.copy(f, target.resolve(f.getFileName()),
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

    private SimPuzzleSolveTests() {
    }
}
