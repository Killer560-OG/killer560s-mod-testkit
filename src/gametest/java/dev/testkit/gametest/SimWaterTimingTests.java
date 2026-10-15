package dev.testkit.gametest;

import dev.testkit.harness.PacketTrace;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code 93-solve-waterboard-timing} (mod branch water-late-1015). killer560, 2026-10-15, after a real Hypixel run: "it
 * flicks the levers very late from when my solver says they should be flicked" - his log had two timed levers 11 ticks
 * late each, and every warp 7 ticks after the lever click before it.
 *
 * <p>The room is played by {@link SimPuzzleSolveTests.Base} as {@code 93-solve-waterboard} (same load, items, premise
 * checks and sim verdict). On top, every client tick (END_CLIENT_TICK, so a click the auto made at START is in) this
 * reads Water Board Solver's own clock and counted clicks, and a packet tap stamps every item use with the same tick:
 * <ul>
 *   <li>a TIMED click is judged against the solver's countdown: {@code late = solver tick at the click - (opened +
 *   time*20)}. The auto ticks before the solver's counter moves, so a click in the tick right after the countdown turns
 *   "CLICK ME!" reads 1 - which is also exactly {@code time*20} ticks after the water lever's own click. Must be
 *   &lt;= 1.</li>
 *   <li>a lever click followed by a warp (an item use before the next lever click): the use must go on the very next
 *   tick (gap 1).</li>
 * </ul>
 * Not vacuous: needs the room solved (the base verdict), 2+ timed clicks and 2+ click-then-warp pairs measured.
 */
public final class SimWaterTimingTests {

    private SimWaterTimingTests() {
    }

    private static final String SOLVER = "com.killer560.hub.puzzlesolvers.WaterSolverFeature";

    public static final class WaterBoardTiming extends SimPuzzleSolveTests.Base {
        public WaterBoardTiming() {
            super(new SimPuzzleSolveTests.Spec("waterboard-timing", "Water Board", "setAutoWaterEnabled",
                    "isAutoWaterEnabled", "WaterSolverConfig", "SimWaterPuzzle", 0, false, 60,
                    SimPuzzleSolveTests.Approach.NONE, "WaterSolverFeature.nextClick",
                    "WaterSolverFeature.getCountedClicks", "WaterSolverFeature.getOpenedWaterTick",
                    "WaterSolverFeature.getTickCounter"));
        }

        @Override
        SolveExtra extra() {
            return new Timing();
        }
    }

    /** One lever click as the solver counted it. */
    private record Click(int index, long tick, boolean timed, double time, long solverTick, long due, String pos) {
    }

    // ---- recorded on the client thread ----
    private static volatile boolean recording;
    private static boolean hooked;
    private static long ended;
    private static int prevCount;
    private static Object prevNext;
    private static final List<Click> CLICKS = new ArrayList<>();
    private static final List<Long> USES = new ArrayList<>();

    private static void onEnd() {
        if (!recording) {
            return;
        }
        long idx = ended + 1;
        ended++;
        int count;
        long solverTick;
        long opened;
        Object next;
        try {
            count = (Integer) ModUnderTest.staticCall(SOLVER, "getCountedClicks");
            solverTick = (Long) ModUnderTest.staticCall(SOLVER, "getTickCounter");
            opened = (Long) ModUnderTest.staticCall(SOLVER, "getOpenedWaterTick");
            next = ModUnderTest.staticCall(SOLVER, "nextClick");
        } catch (Throwable t) {
            return;
        }
        if (count > prevCount && prevNext != null) {
            double time = (Double) ModUnderTest.call(prevNext, "time", new Class<?>[0], new Object[0]);
            BlockPos pos = (BlockPos) ModUnderTest.call(prevNext, "pos", new Class<?>[0], new Object[0]);
            boolean timed = time > 0.0 && opened != -1;
            long due = timed ? opened + Math.round(time * 20.0) : -1;
            synchronized (CLICKS) {
                CLICKS.add(new Click(CLICKS.size() + 1, idx, timed, time, solverTick, due, pos.toShortString()));
            }
        }
        prevCount = count;
        prevNext = next;
    }

    private static void onPacket(Packet<?> p) {
        if (recording && p instanceof ServerboundUseItemPacket) {
            synchronized (USES) {
                USES.add(ended + 1); // the tick in progress: END_CLIENT_TICK has not counted it yet
            }
        }
    }

    private static final class Timing extends SolveExtra {
        private java.util.function.Consumer<Packet<?>> oldTap;
        private String summary = "not evaluated";
        private boolean installed;

        @Override
        void configure(Object auto, Object cheat) {
            // The base calls configure for the solver alone, again with the auto on, and once more after the verdict.
            if (installed) {
                return;
            }
            installed = true;
            if (!hooked) {
                hooked = true;
                ClientTickEvents.END_CLIENT_TICK.register(c -> onEnd());
            }
            synchronized (CLICKS) {
                CLICKS.clear();
            }
            synchronized (USES) {
                USES.clear();
            }
            ended = 0;
            prevCount = 0;
            prevNext = null;
            oldTap = PacketTrace.tap;
            java.util.function.Consumer<Packet<?>> chained = oldTap;
            PacketTrace.tap = p -> {
                onPacket(p);
                if (chained != null) {
                    chained.accept(p);
                }
            };
            recording = true;
        }

        private boolean probed;

        /** Once: why Interactive Map's room spot for Water Board, relative (15, 58, 9), read "not standable" in his log. */
        @Override
        void tick(ClientGameTestContext ctx, int t, int solvedAt, boolean container) {
            if (probed) {
                return;
            }
            probed = true;
            String line = ctx.computeOnClient(mc -> {
                try {
                    int[] cr = (int[]) ModUnderTest.staticCall("com.killer560.hub.livemap.LiveMapFeature",
                            "currentRoomClayAndRotation");
                    if (cr == null) {
                        return "room spot probe: no room";
                    }
                    BlockPos spot = (BlockPos) ModUnderTest.staticCall("com.killer560.hub.puzzlesolvers.PuzzleCoords", "real",
                            new Class<?>[]{int.class, int.class, int.class, int[].class}, new Object[]{15, 58, 9, cr});
                    String tu = "com.killer560.hub.livemap.autoclear.TeleportUtils";
                    Object ether = ModUnderTest.staticCall(tu, "etherwarpable", new Class<?>[]{BlockPos.class}, new Object[]{spot});
                    Object land = ModUnderTest.staticCall(tu, "landable", new Class<?>[]{BlockPos.class}, new Object[]{spot});
                    Object cover = ModUnderTest.staticCall(tu, "underCover", new Class<?>[]{BlockPos.class}, new Object[]{spot});
                    return "room spot probe: (15,58,9) = " + spot.toShortString() + " block " + mc.level.getBlockState(spot)
                            + ", feet " + mc.level.getBlockState(spot.above()) + ", head " + mc.level.getBlockState(spot.above(2))
                            + " | etherwarpable " + ether + ", landable " + land + ", underCover " + cover;
                } catch (Throwable e) {
                    return "room spot probe failed: " + e;
                }
            });
            SimPuzzleRoundTests.say(name, line);
        }

        @Override
        String verdict(ClientGameTestContext ctx, int solvedAt) {
            recording = false;
            PacketTrace.tap = oldTap;
            List<Click> clicks;
            List<Long> uses;
            synchronized (CLICKS) {
                clicks = new ArrayList<>(CLICKS);
            }
            synchronized (USES) {
                uses = new ArrayList<>(USES);
            }
            List<String> bad = new ArrayList<>();
            int timed = 0;
            int pairs = 0;
            long worstLate = Long.MIN_VALUE;
            long worstGap = Long.MIN_VALUE;
            for (int i = 0; i < clicks.size(); i++) {
                Click c = clicks.get(i);
                String line = String.format(Locale.US, "click %d at %s (time %.1fs) on tick %d", c.index(), c.pos(),
                        c.time(), c.tick());
                if (c.timed()) {
                    timed++;
                    long late = c.solverTick() - c.due();
                    worstLate = Math.max(worstLate, late);
                    line += String.format(Locale.US, ": solver tick %d, due %d -> %d tick(s) after CLICK ME", c.solverTick(),
                            c.due(), late);
                    if (late > 1) {
                        bad.add("click " + c.index() + " at " + c.pos() + " " + late + " ticks late");
                    }
                    if (late < 0) {
                        bad.add("click " + c.index() + " at " + c.pos() + " " + (-late) + " ticks EARLY");
                    }
                }
                long nextClick = i + 1 < clicks.size() ? clicks.get(i + 1).tick() : Long.MAX_VALUE;
                Long use = null;
                for (long u : uses) {
                    if (u >= c.tick() && u < nextClick) {
                        use = u;
                        break;
                    }
                }
                if (use != null) {
                    pairs++;
                    long gap = use - c.tick();
                    worstGap = Math.max(worstGap, gap);
                    line += "; next warp's use " + gap + " tick(s) after the click";
                    if (gap != 1) {
                        bad.add("click " + c.index() + "'s warp went " + gap + " tick(s) after it");
                    }
                }
                SimPuzzleRoundTests.say(name, line);
            }
            if (timed < 2) {
                bad.add("only " + timed + " timed click(s) measured - nothing to judge");
            }
            if (pairs < 2) {
                bad.add("only " + pairs + " click-then-warp pair(s) measured - nothing to judge");
            }
            summary = String.format(Locale.US, "%d click(s), %d timed (worst %s tick(s) after CLICK ME), %d click->warp "
                            + "(worst gap %s)", clicks.size(), timed, worstLate == Long.MIN_VALUE ? "n/a" : worstLate, pairs,
                    worstGap == Long.MIN_VALUE ? "n/a" : worstGap);
            return bad.isEmpty() ? null : String.join("; ", bad);
        }

        @Override
        String summary() {
            return summary;
        }
    }
}
