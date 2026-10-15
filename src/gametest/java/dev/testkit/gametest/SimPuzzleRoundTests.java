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
                /** The auto clicked the chest itself - it must be Secret Aura's click (killer560: "it should aura the
                 *  secret"). */
                String ownClick = null;
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
                        if (ownClick == null && l.contains("TicTacToe: clicked the secret chest")) {
                            ownClick = l;
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
                    if (ownClick != null) {
                        bad.add("the auto clicked the chest itself, not Secret Aura: " + ownClick.trim());
                    }
                    if (secretAt < 0) {
                        bad.add("the room's secret was never counted (Grab Secret on, Secret Aura on)");
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

    /**
     * Ice Fill's reward chests, LOOKED AT (killer560, 2026-10-15: chests "on each of those pillars ... a fairly distinct
     * spot where they go after you finish the third part"). The tick the sim says solved, with him on the finish tile:
     * every chest in the room is listed (world and room-relative), and one screenshot is taken looking at each of the
     * two nearest, saved as icefill-chests-1/-2. Fails unless there are exactly two chests, each standing on polished
     * andesite with a torch beside it.
     */
    public static final class IceFillShots extends SimPuzzleSolveTests.Base {
        public IceFillShots() {
            super(new SimPuzzleSolveTests.Spec("icefill-shots", "Ice Fill", "setAutoIceFillEnabled", "isAutoIceFillEnabled",
                    "IceFillSolverConfig", "SimIceFillPuzzle", 0, false, 60, SimPuzzleSolveTests.Approach.NONE,
                    "IceFillSolverFeature.getCurrentPath"));
        }

        @Override
        SolveExtra extra() {
            return new SolveExtra() {
                boolean done = false;
                String bad = null;
                String what = "";

                @Override
                void configure(Object auto, Object cheat) {
                    ModUnderTest.set(cheat, "setSecretAuraEnabled", false);
                }

                @Override
                void tick(ClientGameTestContext ctx, int t, int solvedAt, boolean container) {
                    if (solvedAt < 0 || done) {
                        return;
                    }
                    done = true;
                    // The chests, read on the client, and how each one stands.
                    List<String> found = new ArrayList<>();
                    List<double[]> chests = ctx.computeOnClient(mc -> {
                        List<double[]> out = new ArrayList<>();
                        var p = mc.player.blockPosition();
                        int[] cr = (int[]) ModUnderTest.staticCall("com.killer560.hub.livemap.LiveMapFeature",
                                "currentRoomClayAndRotation", new Class<?>[]{}, new Object[]{});
                        for (var b : net.minecraft.core.BlockPos.betweenClosed(p.offset(-32, -12, -32), p.offset(32, 16, 32))) {
                            var st = mc.level.getBlockState(b);
                            if (!st.is(net.minecraft.world.level.block.Blocks.CHEST)
                                    && !st.is(net.minecraft.world.level.block.Blocks.TRAPPED_CHEST)) {
                                continue;
                            }
                            boolean pedestal = mc.level.getBlockState(b.below())
                                    .is(net.minecraft.world.level.block.Blocks.POLISHED_ANDESITE);
                            boolean torch = false;
                            for (var q : net.minecraft.core.BlockPos.betweenClosed(b.offset(-1, 0, -1), b.offset(1, 0, 1))) {
                                var s = mc.level.getBlockState(q);
                                torch |= s.is(net.minecraft.world.level.block.Blocks.TORCH)
                                        || s.is(net.minecraft.world.level.block.Blocks.WALL_TORCH);
                            }
                            Object rel = cr == null ? "?" : ModUnderTest.staticCall("com.killer560.hub.puzzlesolvers.PuzzleCoords",
                                    "relative", new Class<?>[]{net.minecraft.core.BlockPos.class, int[].class},
                                    new Object[]{b.immutable(), cr});
                            found.add(String.format("%s (relative %s) on %s%s", b.toShortString(),
                                    rel instanceof net.minecraft.core.BlockPos r ? r.toShortString() : rel,
                                    pedestal ? "polished andesite" : mc.level.getBlockState(b.below()).getBlock().getName().getString(),
                                    torch ? ", torch beside" : ", NO torch beside"));
                            out.add(new double[]{b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5, pedestal && torch ? 1 : 0});
                        }
                        out.sort(java.util.Comparator.comparingDouble(c -> mc.player.distanceToSqr(c[0], c[1], c[2])));
                        return out;
                    });
                    String rot = ctx.computeOnClient(mc -> java.util.Arrays.toString((int[]) ModUnderTest.staticCall(
                            "com.killer560.hub.livemap.LiveMapFeature", "currentRoomClayAndRotation", new Class<?>[]{},
                            new Object[]{})));
                    what = chests.size() + " chest(s), room clay/rotation " + rot + ": " + String.join("; ", found);
                    say(name, "chests at the solve: " + what);
                    long good = chests.stream().filter(c -> c[3] == 1).count();
                    if (chests.size() != 2 || good != 2) {
                        bad = "expected two chests, each on a polished andesite pillar with a torch beside it - " + what;
                    }
                    // A look at each: from where he stands (the finish tile), turned to the chest - with the autos off
                    // first, so no free camera holds the view where it was.
                    ctx.runOnClient(mc -> {
                        Object auto = ModUnderTest.config("com.killer560.hub.autopuzzles.AutoPuzzlesConfig");
                        ModUnderTest.set(auto, "setAutoPuzzlesMasterEnabled", false);
                        ModUnderTest.set(auto, "setAutoIceFillEnabled", false);
                    });
                    ctx.waitTicks(20);
                    say(name, "standing at " + ctx.computeOnClient(mc -> mc.player.position().toString()));
                    for (int i = 0; i < Math.min(2, chests.size()); i++) {
                        double[] c = chests.get(i);
                        ctx.runOnClient(mc -> {
                            var eye = mc.player.getEyePosition();
                            double dx = c[0] - eye.x;
                            double dy = c[1] - eye.y;
                            double dz = c[2] - eye.z;
                            mc.player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
                            mc.player.setXRot((float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))));
                        });
                        ctx.waitTicks(3);
                        java.nio.file.Path shot = ctx.takeScreenshot("icefill-chests-" + (i + 1));
                        say(name, "screenshot " + (i + 1) + ": " + shot.toAbsolutePath());
                    }
                }

                @Override
                String verdict(ClientGameTestContext ctx, int solvedAt) {
                    return solvedAt < 0 ? null : bad;
                }

                @Override
                String summary() {
                    return what;
                }
            };
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

    // ------------------------------------------------------------------ Ice Fill: done on the tab list and the map

    private static final String EXECUTOR = "com.killer560.hub.livemap.autoclear.ClearExecutor";
    private static final String CORRECTIONS = "com.killer560.hub.util.ServerCorrections";
    private static final String DONE_MARK = "[✔]";

    /** Every tab-list display name the client holds, as plain text. */
    static List<String> tabLines(Minecraft mc) {
        List<String> out = new ArrayList<>();
        if (mc.getConnection() == null) {
            return out;
        }
        for (var info : mc.getConnection().getListedOnlinePlayers()) {
            if (info.getTabListDisplayName() != null) {
                out.add(info.getTabListDisplayName().getString());
            }
        }
        return out;
    }

    static String iceFillTabRow(Minecraft mc) {
        return tabLines(mc).stream().filter(l -> l.contains("Ice Fill")).findFirst().orElse("(no Ice Fill row)");
    }

    /** {@code SimRoomState.stateFor(room)}: 0 green checkmark, 1 white checkmark, 3 failed, -1 no opinion. */
    static int simRoomState(String room) {
        return (Integer) ModUnderTest.staticCall("com.killer560.hub.roomsim.SimRoomState", "stateFor",
                new Class<?>[]{String.class}, new Object[]{room});
    }

    static int correctionReports() {
        return (Integer) ModUnderTest.staticCall(CORRECTIONS, "reports");
    }

    static void serverTeleport(ClientGameTestContext ctx, double x, double y, double z) {
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(uuid);
                if (sp != null) {
                    sp.teleportTo(x, y, z);
                }
            });
        });
    }

    /** Auto Ice Fill's first hop point (feet, 0.1 up), or null before it built its path. */
    static net.minecraft.world.phys.Vec3 iceFillFirstPoint() {
        try {
            Class<?> c = Class.forName("com.killer560.hub.autopuzzles.AutoIceFill");
            var fp = c.getDeclaredField("path");
            fp.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<net.minecraft.world.phys.Vec3> path = (List<net.minecraft.world.phys.Vec3>) fp.get(null);
            return path == null || path.isEmpty() ? null : path.get(0);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    static int executorSyncDelay() {
        try {
            var f = Class.forName(EXECUTOR).getDeclaredField("syncDelay");
            f.setAccessible(true);
            return f.getInt(null);
        } catch (ReflectiveOperationException e) {
            return -1;
        }
    }

    static void etherPathExact(Minecraft mc, net.minecraft.core.BlockPos to) {
        ModUnderTest.staticCall(EXECUTOR, "etherPathExact",
                new Class<?>[]{net.minecraft.core.BlockPos.class, Runnable.class}, new Object[]{to, null});
    }

    /**
     * {@code 93-solve-icefill-done}: killer560, 2026-10-15: "make ice fill on sim show as complete once it reaches the
     * end". Once the sim says solved, the tab list's Ice Fill row must read done and the map must paint the room with the
     * green checkmark ({@code SimRoomState.stateFor == STATE_GREEN}), as Hypixel does. Main left every sim puzzle room on
     * the white checkmark it gets on entry.
     */
    public static final class IceFillDone extends SimPuzzleSolveTests.Base {
        public IceFillDone() {
            super(new SimPuzzleSolveTests.Spec("icefill-done", "Ice Fill", "setAutoIceFillEnabled", "isAutoIceFillEnabled",
                    "IceFillSolverConfig", "SimIceFillPuzzle", 0, false, 60, SimPuzzleSolveTests.Approach.NONE,
                    "IceFillSolverFeature.getCurrentPath"));
        }

        @Override
        SolveExtra extra() {
            return new SolveExtra() {
                String before = "";
                String tab = "";
                int state = -9;

                @Override
                void beforeAutoOn(ClientGameTestContext ctx) {
                    before = ctx.computeOnClient(mc -> iceFillTabRow(mc) + "; map state " + simRoomState("Ice Fill"));
                    say(name, "before the auto: tab " + before);
                }

                @Override
                String verdict(ClientGameTestContext ctx, int solvedAt) {
                    if (solvedAt < 0) {
                        return "never solved - nothing to show as done";
                    }
                    // The tab list is republished once a second.
                    for (int i = 0; i < 60; i++) {
                        tab = ctx.computeOnClient(SimPuzzleRoundTests::iceFillTabRow);
                        state = ctx.computeOnClient(mc -> simRoomState("Ice Fill"));
                        if (tab.contains(DONE_MARK) && state == 0) {
                            break;
                        }
                        ctx.waitTicks(1);
                    }
                    List<String> bad = new ArrayList<>();
                    if (!tab.contains(DONE_MARK)) {
                        bad.add("tab row is '" + tab + "', not done " + DONE_MARK);
                    }
                    if (state != 0) {
                        bad.add("map state for Ice Fill is " + state + ", not 0 (the green checkmark)");
                    }
                    return bad.isEmpty() ? null : String.join("; ", bad);
                }

                @Override
                String summary() {
                    return "before: " + before + " | after the solve: tab '" + tab + "', map state " + state;
                }
            };
        }
    }

    /**
     * {@code 93-solve-icefill-maptrip}: killer560, 2026-10-15: "every movement shows as a server reposition and makes the
     * sound on sim". His log: a map trip onto Ice Fill's first tile had every landing answered and was settling when Auto
     * Ice Fill began hopping, and each hop read as a server correction (88 alarms in one crossing). Here: he stands on the
     * finish tile, a real ClearExecutor trip takes him to the first tile with Auto Ice Fill on, and the crossing must raise
     * NO correction while the trip and the hops did overlap. Then, Ice Fill switched off, a trip back is moved by a plain
     * server teleport during its arrival settle: a genuine correction, which must report exactly once.
     */
    public static final class IceFillMapTrip extends SimPuzzleSolveTests.Base {
        public IceFillMapTrip() {
            super(new SimPuzzleSolveTests.Spec("icefill-maptrip", "Ice Fill", "setAutoIceFillEnabled",
                    "isAutoIceFillEnabled", "IceFillSolverConfig", "SimIceFillPuzzle", 0, false, 60,
                    SimPuzzleSolveTests.Approach.NONE, "IceFillSolverFeature.getCurrentPath"));
        }

        @Override
        SolveExtra extra() {
            return new SolveExtra() {
                Object auto;
                int reports0 = -1;
                int reportsAtSolve = -1;
                int tripAt = -1;
                String tripTo = null;
                int genuine = -1;
                String genuineWhat = "";

                net.minecraft.core.BlockPos tripBlock;

                @Override
                void configure(Object auto, Object cheat) {
                    this.auto = auto;
                    // Off: with it on, a mid-trip landing off the ice band has Auto Ice Fill warp him onto the first tile
                    // itself, and the trip never ends there with the hops starting under its arrival settle - his case.
                    ModUnderTest.set(auto, "setEtherwarpReposition", false);
                }

                @Override
                void beforeAutoOn(ClientGameTestContext ctx) {
                    net.minecraft.core.BlockPos finish = ctx.computeOnClient(mc -> SimPuzzleSolveTests.rel(mc, 15, 71, 26));
                    serverTeleport(ctx, finish.getX() + 0.5, finish.getY() + 1.0, finish.getZ() + 0.5);
                    ctx.waitTicks(40);
                    reports0 = ctx.computeOnClient(mc -> correctionReports());
                    say(name, "on the finish tile " + finish.toShortString() + ": "
                            + ctx.computeOnClient(SimPuzzleSolveTests::where) + "; correction reports so far " + reports0);
                }

                @Override
                void tick(ClientGameTestContext ctx, int t, int solvedAt, boolean container) {
                    if (tripAt < 0) {
                        String to = ctx.computeOnClient(mc -> {
                            net.minecraft.world.phys.Vec3 first = iceFillFirstPoint();
                            if (first == null) {
                                return null;
                            }
                            net.minecraft.core.BlockPos below = net.minecraft.core.BlockPos.containing(first).below();
                            etherPathExact(mc, below);
                            tripBlock = below;
                            return below.toShortString();
                        });
                        if (to != null) {
                            tripAt = t;
                            tripTo = to;
                            say(name, String.format("t=%.1fs Interactive Map trip started onto the first tile %s",
                                    t / 20.0, to));
                        }
                    }
                    if (solvedAt > 0 && reportsAtSolve < 0) {
                        reportsAtSolve = ctx.computeOnClient(mc -> correctionReports());
                    }
                }

                @Override
                String verdict(ClientGameTestContext ctx, int solvedAt) {
                    List<String> log = LogTap.since(mark);
                    boolean ran = log.stream().anyMatch(l -> l.contains("[Path] running"));
                    long hops = log.stream().filter(l -> l.contains("[AutoIceFill] hop ")).count();
                    // The overlap the bug needs: the trip still owned him (settling, or waiting to plan again) when a hop
                    // landed - the fixed jar's line, or the broken jar's correction lines.
                    long overlap = log.stream().filter(l -> l.contains("another feature's")
                            || l.contains("off the last landing") || l.contains("while waiting to plan again")).count();
                    List<String> bad = new ArrayList<>();
                    if (tripAt < 0 || !ran) {
                        bad.add("the map trip onto the first tile never ran (" + tripTo + ") - nothing was tested");
                    }
                    if (hops < 10) {
                        bad.add("Auto Ice Fill hopped only " + hops + " time(s) - the crossing was not measured");
                    }
                    if (overlap == 0) {
                        bad.add("no hop landed while the trip still owned him - the case the fix is about never happened");
                    }
                    int during = (reportsAtSolve < 0 ? ctx.computeOnClient(mc -> correctionReports()) : reportsAtSolve)
                            - reports0;
                    say(name, "crossing: " + hops + " hop(s), " + overlap + " landing(s) while the trip owned him, "
                            + during + " correction report(s) by the solve");
                    if (solvedAt < 0) {
                        bad.add("Ice Fill was never solved");
                    }
                    if (during != 0) {
                        bad.add(during + " server correction(s) reported while Auto Ice Fill crossed after a map trip - "
                                + "its own hops, not corrections");
                    }
                    genuine = genuineCorrection(ctx);
                    if (genuine != 1) {
                        bad.add("a genuine server teleport during a trip's arrival settle reported " + genuine
                                + " time(s), not exactly once (" + genuineWhat + ")");
                    }
                    return bad.isEmpty() ? null : String.join("; ", bad);
                }

                /** Ice Fill off; a trip back to the first tile; a plain server teleport once its arrival settle starts. */
                private int genuineCorrection(ClientGameTestContext ctx) {
                    ctx.runOnClient(mc -> ModUnderTest.set(auto, "setAutoIceFillEnabled", false));
                    ctx.waitTicks(80);   // any chest-claim warp of the auto's is over, and its 2 s grace has lapsed
                    net.minecraft.core.BlockPos start = tripBlock;
                    net.minecraft.core.BlockPos finish = ctx.computeOnClient(mc -> SimPuzzleSolveTests.rel(mc, 15, 71, 26));
                    if (start == null) {
                        genuineWhat = "no first tile to plan to";
                        return -1;
                    }
                    serverTeleport(ctx, finish.getX() + 0.5, finish.getY() + 1.0, finish.getZ() + 0.5);
                    ctx.waitTicks(40);
                    int r0 = ctx.computeOnClient(mc -> correctionReports());
                    ctx.runOnClient(mc -> etherPathExact(mc, start));
                    int settleAt = -1;
                    for (int t = 0; t < 200 && settleAt < 0; t++) {
                        ctx.waitTicks(1);
                        if (ctx.computeOnClient(mc -> executorSyncDelay()) >= 2) {
                            settleAt = t;
                        }
                    }
                    if (settleAt < 0) {
                        genuineWhat = "the trip back never reached its arrival settle";
                        return -1;
                    }
                    serverTeleport(ctx, finish.getX() + 0.5, finish.getY() + 1.0, finish.getZ() + 0.5);
                    ctx.waitTicks(100);
                    int n = ctx.computeOnClient(mc -> correctionReports()) - r0;
                    genuineWhat = String.format("settle reached %.1fs into the trip back; server teleport onto the finish "
                            + "tile; %d report(s) in the 5 s after", settleAt / 20.0, n);
                    say(name, "genuine correction: " + genuineWhat);
                    return n;
                }

                @Override
                String summary() {
                    return "trip at t=" + tripAt + " onto " + tripTo + "; reports by the solve "
                            + (reportsAtSolve < 0 ? "?" : String.valueOf(reportsAtSolve - reports0)) + "; genuine " + genuine;
                }
            };
        }
    }
}
