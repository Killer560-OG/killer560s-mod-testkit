package dev.testkit.gametest.hx;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.testkit.gametest.mod.Mod;

import java.lang.reflect.Field;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Leaving and rejoining a dungeon run (mod rejoin-1111, killer560 2026-11-11: "look into stuff not working if i leave and
 * rejoin mid dungeon. i want them to still work"). A rejoin is a new client world with the same sidebar server id and a
 * later Time Elapsed; the mod's {@code secrets.DungeonRun} then keeps every feature's per-run state. Here the world change
 * is a trip to the Nether and back (a new ClientLevel each way) with the sidebar cleared while away, as in limbo.
 * Each case proves the state was really there before (split armed, mimic counted, map generation) and after.
 */
final class HxRejoin1111Cases {

    private static final String RUN = "secrets.DungeonRun";
    private static final String DS = "secrets.DungeonState";
    private static final String MAP = "livemap.LiveMapFeature";
    private static final String SPLITS = "splittimers.SplitTimersFeature";
    private static final String SPLIT_CFG = "splittimers.SplitTimersConfig";
    private static final String SCORE = "scorecalc.ScoreCalculatorFeature";
    private static final String SCORE_CFG = "scorecalc.ScoreCalculatorConfig";
    private static final String SERVER_LINE = "§710/04/26 §8m12AB";
    private static final String BC_STATS = "bloodcamp.BloodCampStats";
    private static final String BC_TIMER = "bloodcamp.BloodCampMoveTimer";
    private static final String BR = "bloodrush.BloodRushHelperFeature";
    private static final String BR_ROLES = "bloodrush.BloodRushRoles";
    private static final String LEAP = "leapcounter.LeapTracker";
    private static final String LEAP_CFG = "leapcounter.LeapCounterConfig";
    private static final String GREETING = "[BOSS] The Watcher: Congratulations, you made it through the Entrance.";
    private static final String WATCHER_MOVE = "[BOSS] The Watcher: Let's see how you can handle this.";
    private static final String MAXOR = "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!";

    private HxRejoin1111Cases() {
    }

    static void register(Session s) {
        s.test("1111-hx-rejoin-same", HxRejoin1111Cases::same);
        s.test("1111-hx-rejoin-other", HxRejoin1111Cases::other);
        s.test("1111-hx-rejoin-noserver", HxRejoin1111Cases::noServer);
        s.test("1111-hx-rejoin-camp", HxRejoin1111Cases::camp);
        s.test("1121-hx-rejoin-runstarted", HxRejoin1111Cases::runStarted);
        s.test("1122-hx-runstart-heldmap", HxRejoin1111Cases::heldMap);
    }

    private static final String PILOT = "autosecret.DungeonsAutopilot";

    private static String pilotPhase(Session c) {
        return c.onClient(mc -> String.valueOf(Mod.field(PILOT, "phase")));
    }

    /**
     * runstart-stale-1122 (his 26.3 log 09:04:14): the run he left is held for a rejoin with its map item still read, so in
     * the next dungeon the old map made runStarted() true and Dungeons Autopilot, armed there, started at once - ~7 s before
     * the gate opened. Here: a live run whose map is read, a world change, the autopilot switched on before the new
     * dungeon's sidebar, then ANOTHER run's sidebar. runStarted() must stay false and the autopilot ARMED until Mort's
     * line, then start. Origin e2642d486 read true while the run was held.
     */
    private static void heldMap(Session c) {
        boolean cheat = Mod.isCheat();
        try (Settings set = settings(c)) {
            pad(c);
            armRun(c);
            run(c, () -> Mod.staticCall("livemap.DungeonMapScanner", "testSetCells", (Object) new String[]{"rr"}));
            boolean before = started(c);
            awayAndBack(c, -59.5, 100, -59.5);
            boolean holding = c.onClient(mc -> (Boolean) Mod.staticCall(RUN, "holding"));
            boolean kept = c.onClient(mc -> (Boolean) Mod.staticCall("livemap.DungeonMapScanner", "isCalibrated"));
            boolean whileHeld = started(c);
            if (cheat) {
                run(c, () -> Mod.staticCall(PILOT, "setOn", true));
            }
            board(c, "§710/04/26 §8m99ZZ", "00m 00s");
            int startedTicks = 0;
            int notArmed = 0;
            for (int t = 0; t < 60; t++) {
                c.ctx().waitTicks(1);
                if (started(c)) startedTicks++;
                if (cheat && !"ARMED".equals(pilotPhase(c))) notArmed++;
            }
            String decided = describe(c);
            c.hx().chat("[NPC] Mort: Here, I found this map when I first entered the dungeon.");
            c.waitUntil("Mort's line reads as the run's start",
                    mc -> (Boolean) Mod.staticCall("witherdoors.WitherDoorsFeature", "runStarted"), 40);
            String after = "n/a";
            if (cheat) {
                c.waitUntil("the autopilot leaves ARMED on Mort's line",
                        mc -> !"ARMED".equals(String.valueOf(Mod.field(PILOT, "phase"))), 40);
                after = pilotPhase(c);
            }
            c.check(before, "the first run's read map did not read as started, so the case proves nothing");
            c.check(holding && kept, "the left run was not held with its map (holding " + holding + ", map kept " + kept
                    + "): the case proves nothing");
            c.check(!whileHeld, "the held run's map read as this world's run start");
            c.check(decided.contains("m99ZZ"), "DungeonRun never took the new run: " + decided);
            c.check(startedTicks == 0, "runStarted() was true on " + startedTicks + " of 60 ticks of the new run before Mort");
            c.check(notArmed == 0, "the autopilot left ARMED on " + notArmed + " of 60 ticks before Mort");
            c.note("before=" + before + " holding=" + holding + " mapKept=" + kept + " whileHeld=" + whileHeld
                    + " startedTicks=" + startedTicks + " notArmed=" + notArmed + " phaseAfterMort=" + after + "; " + decided);
        } finally {
            if (cheat) {
                run(c, () -> Mod.staticCall(PILOT, "setOn", false));
            }
            run(c, () -> {
                Mod.staticCall("livemap.DungeonMapScanner", "reset");
                try { // Mort's line was said in this world: later cases in it must not read the run as started
                    Mod.setField("witherdoors.WitherDoorsFeature", "mortLevel", null);
                } catch (Throwable older) {
                    Mod.setField("witherdoors.WitherDoorsFeature", "mortSeen", false);
                }
            });
            cleanup(c);
        }
    }

    private static boolean started(Session c) {
        return c.onClient(mc -> (Boolean) Mod.staticCall("witherdoors.WitherDoorsFeature", "runStarted"));
    }

    /** acgaps2-1121: a rejoin of a live run reads as started (Mort does not speak again), so Auto Clear or Auto Secret
     *  started there starts instead of arming (ArmedStart) until the map is read. Origin 6dc8207ee read false. */
    private static void runStarted(Session c) {
        try (Settings set = settings(c)) {
            pad(c);
            armRun(c);
            boolean before = started(c);
            awayAndBack(c, -59.5, 100, -59.5);
            board(c, SERVER_LINE, "01m 35s");
            c.waitUntil("DungeonRun resumed the run", mc -> (Boolean) Mod.staticCall(RUN, "resumed"), 100);
            boolean after = started(c);
            c.check(!before, "the run read as started before any Mort line or map, so the case proves nothing");
            c.check(after, "a resumed run did not read as started: " + describe(c));
            c.note("before rejoin started=" + before + ", after resume started=" + after + "; " + describe(c));
        } finally {
            cleanup(c);
        }
    }

    /** The F7 dungeon sidebar with {@code serverLine} as its top line ({@code null}: no server line) at {@code time}. */
    private static void board(Session c, String serverLine, String time) {
        JsonObject r = c.hx().call("surface.sidebar", Hx.args("template", "dungeon", "floor", "F7", "time", time)).getAsJsonObject();
        JsonArray lines = r.getAsJsonArray("lines");
        JsonArray out = new JsonArray();
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i).getAsString();
            if (i == 0) {
                if (serverLine == null) continue;
                l = serverLine;
            }
            out.add(l);
        }
        r.add("lines", out);
        c.hx().call("sidebar.set", r);
    }

    private static String dim(Session c) {
        return c.onClient(mc -> mc.level == null ? "" : mc.level.dimension().toString());
    }

    /** Leaves the world for the Nether with the sidebar gone, waits, then comes back to the overworld at x, y, z. */
    static void awayAndBack(Session c, double x, double y, double z) {
        String me = self(c);
        String home = dim(c);
        // A loaded stone floor with air above in the Nether: without it the client fell (or sat in netherrack) where the
        // server did not, and GrimAC flagged Simulation in later cases.
        c.server().command("execute in minecraft:the_nether run forceload add -16 -16 15 15");
        c.server().command("execute in minecraft:the_nether run fill -3 100 -3 3 100 3 minecraft:stone");
        c.server().command("execute in minecraft:the_nether run fill -3 101 -3 3 104 3 minecraft:air");
        c.server().command("execute in minecraft:the_nether run tp " + me + " 0.5 101 0.5");
        c.waitUntil("in the Nether", mc -> mc.level != null && !mc.level.dimension().toString().equals(home), 200);
        c.hx().sidebarClear(); // as on Hypixel, the sidebar changes after the world does
        c.ctx().waitTicks(40);
        c.server().command(String.format(java.util.Locale.ROOT, "execute in minecraft:overworld run tp %s %.1f %.1f %.1f", me, x, y, z));
        c.waitUntil("back in the overworld", mc -> mc.level != null && mc.level.dimension().toString().equals(home), 200);
        try {
            c.waitUntil("standing at the return spot", mc -> mc.player != null
                    && Math.abs(mc.player.getX() - x) < 2 && Math.abs(mc.player.getZ() - z) < 2, 40);
        } catch (AssertionError e) { // the harness client can miss the tp after a dimension change; send it again
            c.server().command(String.format(java.util.Locale.ROOT, "tp %s %.1f %.1f %.1f", me, x, y, z));
            c.waitUntil("standing at the return spot", mc -> mc.player != null
                    && Math.abs(mc.player.getX() - x) < 2 && Math.abs(mc.player.getZ() - z) < 2, 100);
        }
    }

    private static long armedAt(Session c) {
        return c.onClient(mc -> {
            try {
                Object run = Mod.field(SPLITS, "run");
                Field f = run.getClass().getDeclaredField("armedAtMs");
                f.setAccessible(true);
                return f.getLong(run);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static String describe(Session c) {
        return c.onClient(mc -> (String) Mod.staticCall(RUN, "describe"));
    }

    private static boolean flag(Session c, String cls, String name) {
        return c.onClient(mc -> (Boolean) Mod.field(cls, name));
    }

    /** Arms a run on the standard dungeon with a server line: splits armed, mimic counted, map generation read. */
    private static int[] armRun(Session c) {
        run(c, () -> Mod.staticCall(RUN, "resetForTest"));
        dungeon(c);
        board(c, SERVER_LINE, "01m 23s");
        c.waitUntil("DungeonRun has the run's server and time", mc -> {
            String d = (String) Mod.staticCall(RUN, "describe");
            return d.contains("server=m12AB") && d.contains("elapsed=83") && d.contains("live=true");
        }, 100);
        c.hx().chat("Starting in 1 second.");
        c.waitUntil("Split Timers armed", mc -> armedAtLocal() > 0L, 60);
        run(c, () -> Mod.setField(SCORE, "mimicKilled", true));
        c.ctx().waitTicks(5);
        int gen = c.onClient(mc -> (Integer) Mod.staticCall(MAP, "resetGeneration"));
        int runGen = c.onClient(mc -> (Integer) Mod.staticCall(RUN, "generation"));
        return new int[]{gen, runGen};
    }

    private static long armedAtLocal() {
        try {
            Object run = Mod.field(SPLITS, "run");
            Field f = run.getClass().getDeclaredField("armedAtMs");
            f.setAccessible(true);
            return f.getLong(run);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Settings settings(Session c) {
        return new Settings(c).with(SPLIT_CFG, "Enabled", true).with(SCORE_CFG, "Enabled", true);
    }

    /** A stone pad west of the boss arena (x, z < 0) to come back to, so Grim sees a normal landing. */
    private static void pad(Session c) {
        c.server().command("forceload add -70 -70 -50 -50");
        c.ctx().waitTicks(10);
        c.server().command("fill -64 99 -64 -56 99 -56 minecraft:stone");
    }

    private static void cleanup(Session c) {
        pad(c);
        c.server().command("tp @a -59.5 100 -59.5");
        hub(c);
        run(c, () -> {
            Mod.staticCall(RUN, "resetForTest");
            Mod.staticCall(BC_TIMER, "reset");
            Mod.staticCall(BC_STATS, "reset", "test cleanup");
            Mod.staticCall(BR_ROLES, "reset");
            Mod.setField(BR, "bloodOpened", false);
            Mod.staticCall(LEAP, "deactivate");
        });
    }

    /** Same server, later time: the run is resumed, nothing is reset, and the boss phase arms in the arena. */
    private static void same(Session c) {
        c.server().command("forceload add 40 40 70 70");
        c.ctx().waitTicks(10);
        c.server().command("fill 50 114 54 58 114 62 minecraft:stone");
        try (Settings set = settings(c)) {
            int[] before = armRun(c);
            long armed = armedAt(c);
            c.check(flag(c, SCORE, "mimicKilled"), "mimic flag did not take");
            awayAndBack(c, 54.5, 115, 58.5);
            c.ctx().waitTicks(20);
            boolean heldAway = c.onClient(mc -> (Boolean) Mod.staticCall(RUN, "holding"));
            board(c, SERVER_LINE, "01m 35s");
            c.waitUntil("DungeonRun resumed the run", mc -> (Boolean) Mod.staticCall(RUN, "resumed"), 100);
            c.ctx().waitTicks(20);
            int gen = c.onClient(mc -> (Integer) Mod.staticCall(MAP, "resetGeneration"));
            int runGen = c.onClient(mc -> (Integer) Mod.staticCall(RUN, "generation"));
            long armedAfter = armedAt(c);
            boolean mimic = flag(c, SCORE, "mimicKilled");
            boolean boss = c.onClient(mc -> (Boolean) Mod.staticCall(DS, "isBossPhaseActive"));
            c.check(heldAway, "DungeonRun was not holding the run while the sidebar was blank: " + describe(c));
            c.check(armedAfter == armed, "Split Timers lost the run: armed " + armed + " -> " + armedAfter);
            c.check(mimic, "Score Calculator forgot the mimic");
            c.check(gen == before[0], "map reset across the rejoin: generation " + before[0] + " -> " + gen);
            c.check(runGen == before[1], "DungeonRun dropped the run: " + before[1] + " -> " + runGen);
            c.check(boss, "boss phase did not arm after rejoining inside the boss arena: " + describe(c));
            c.note("held away, resumed; splits armed " + armed + " kept, mimic kept, map gen " + gen + ", boss phase on; " + describe(c));
        } finally {
            cleanup(c);
        }
    }

    /** Blood camp state that only a new run may clear: the Watcher's greeting and move line, a camp stat, the rush's blood
     *  door and its party roles. Returns the camp's elapsed ticks since the greeting. */
    private static long bloodState(Session c) {
        c.hx().chat(GREETING);
        c.waitUntil("Blood Camp saw the greeting", mc -> (Boolean) Mod.staticCall(BC_TIMER, "greetingSeen"), 40);
        c.hx().chat(WATCHER_MOVE);
        c.ctx().waitTicks(5);
        run(c, () -> {
            Mod.setField(BC_STATS, "firstKillTicks", 7L);
            Mod.setField(BC_STATS, "camped", true);
            Mod.setField(BR, "bloodOpened", true);
            Mod.setField(BR_ROLES, "seen", true);
        });
        c.ctx().waitTicks(2);
        return elapsed(c);
    }

    private static long elapsed(Session c) {
        return c.onClient(mc -> (Long) Mod.staticCall(BC_TIMER, "elapsedSinceGreeting"));
    }

    private static int leapCount(Session c) {
        return c.onClient(mc -> (Integer) Mod.staticCall(LEAP, "count"));
    }

    /** Blood Camp's timer and stats, Blood Rush Helper and the Leap Counter's count all survive a same-run rejoin. */
    @SuppressWarnings("unchecked")
    private static void camp(Session c) {
        c.server().command("forceload add 40 60 70 80");
        c.ctx().waitTicks(10);
        c.server().command("fill 50 114 66 58 114 74 minecraft:stone");
        try (Settings set = settings(c).with(LEAP_CFG, "Enabled", true)) {
            int[] before = armRun(c);
            long e0 = bloodState(c);
            c.check(e0 >= 0, "no camp time after the greeting: " + e0);
            c.server().command("tp @a 54.5 115 70.5");
            c.hx().chat(MAXOR);
            c.waitUntil("Leap Counter in the core", mc -> "CORE".equals(String.valueOf(Mod.staticCall(LEAP, "section"))), 100);
            run(c, () -> ((java.util.Map<String, Long>) Mod.field(LEAP, "COUNTED")).put("Mate", System.currentTimeMillis()));
            c.ctx().waitTicks(5);
            c.check(leapCount(c) == 1, "Leap Counter did not hold the seeded count before leaving: " + leapCount(c));
            awayAndBack(c, 54.5, 115, 70.5);
            int countAway = leapCount(c);
            boolean greetingAway = c.onClient(mc -> (Boolean) Mod.staticCall(BC_TIMER, "greetingSeen"));
            board(c, SERVER_LINE, "01m 35s");
            c.waitUntil("DungeonRun resumed the run", mc -> (Boolean) Mod.staticCall(RUN, "resumed"), 100);
            // The harness client sometimes ends a dimension round trip at the overworld spawn while the server has it in
            // the arena (no server tp, no move: seen 2 runs of 6); put it back so the case tests the kept state.
            String posAfter = c.onClient(mc -> String.valueOf(mc.player.position()));
            c.server().command("tp @a 54.5 115 70.5");
            try {
                c.waitUntil("Leap Counter back in the core", mc -> "CORE".equals(String.valueOf(Mod.staticCall(LEAP, "section"))), 100);
            } catch (AssertionError e) {
                throw new AssertionError(e.getMessage() + "; " + c.onClient(mc -> "pos " + mc.player.position() + " floor "
                        + Mod.staticCall(DS, "getFloor") + " inDungeon " + Mod.staticCall(DS, "isInDungeon") + " boss "
                        + Mod.staticCall(DS, "isBossPhaseActive") + " f7boss " + Mod.staticCall("fastleap.Floor7Tracker", "inF7Boss")
                        + " phaseAt " + Mod.staticCall("fastleap.Floor7Tracker", "getPhaseAt") + " stageAt "
                        + Mod.staticCall("fastleap.Floor7Tracker", "getStageAt") + " section " + Mod.staticCall(LEAP, "section")
                        + " dim " + mc.level.dimension()) + "; " + describe(c));
            }
            c.ctx().waitTicks(10);
            long e1 = elapsed(c);
            int count = leapCount(c);
            long firstKill = c.onClient(mc -> (Long) Mod.field(BC_STATS, "firstKillTicks"));
            boolean camped = flag(c, BC_STATS, "camped");
            boolean blood = flag(c, BR, "bloodOpened");
            boolean roles = flag(c, BR_ROLES, "seen");
            int runGen = c.onClient(mc -> (Integer) Mod.staticCall(RUN, "generation"));
            c.check(countAway == 1, "Leap Counter dropped its count while away: " + countAway);
            c.check(greetingAway, "Blood Camp forgot the greeting while away");
            c.check(count == 1, "Leap Counter lost the spot's count across the rejoin: " + count);
            c.check(e1 > e0 + 40 && e1 < e0 + 4000, "Blood Camp's time since the greeting is off after the rejoin: " + e0 + " -> " + e1);
            c.check(firstKill == 7L && camped, "Blood Camp Stats reset: firstKill " + firstKill + " camped " + camped);
            c.check(blood, "Blood Rush Helper forgot the blood door");
            c.check(roles, "Blood Rush roles were reset");
            c.check(runGen == before[1], "DungeonRun dropped the run: " + before[1] + " -> " + runGen);
            c.note("pos after rejoin " + posAfter + "; camp kept: greeting " + e0 + " -> " + e1 + " ticks, stats, blood door, roles, leap count " + count + "; " + describe(c));
        } finally {
            cleanup(c);
        }
    }

    /** A different server id after the trip: a new run, so every feature starts over. */
    private static void other(Session c) {
        try (Settings set = settings(c)) {
            pad(c);
            int[] before = armRun(c);
            bloodState(c);
            awayAndBack(c, -59.5, 100, -59.5);
            board(c, "§710/04/26 §8m99ZZ", "00m 05s");
            c.waitUntil("DungeonRun dropped the held run", mc -> (Integer) Mod.staticCall(RUN, "generation") != before[1], 100);
            c.waitUntil("Split Timers reset", mc -> armedAtLocal() == 0L, 60);
            c.waitUntil("Score Calculator reset", mc -> !(Boolean) Mod.field(SCORE, "mimicKilled"), 60);
            c.waitUntil("Blood Camp forgot the greeting", mc -> !(Boolean) Mod.staticCall(BC_TIMER, "greetingSeen"), 60);
            c.waitUntil("Blood Camp Stats reset", mc -> (Long) Mod.field(BC_STATS, "firstKillTicks") == -1L, 60);
            c.waitUntil("Blood Rush Helper reset", mc -> !(Boolean) Mod.field(BR, "bloodOpened") && !(Boolean) Mod.field(BR_ROLES, "seen"), 60);
            int gen = c.onClient(mc -> (Integer) Mod.staticCall(MAP, "resetGeneration"));
            boolean resumed = c.onClient(mc -> (Boolean) Mod.staticCall(RUN, "resumed"));
            c.check(!resumed, "another server's run was taken as a rejoin: " + describe(c));
            c.check(gen != before[0], "map kept across a different run: generation " + gen);
            c.note("other server: dropped, splits/score/map reset (map gen " + before[0] + " -> " + gen + "); " + describe(c));
        } finally {
            cleanup(c);
        }
    }

    /** No server line at all (never placeable): the old behavior, every feature resets on the world change. */
    private static void noServer(Session c) {
        try (Settings set = settings(c)) {
            run(c, () -> Mod.staticCall(RUN, "resetForTest"));
            dungeon(c);
            board(c, null, "01m 23s");
            c.ctx().waitTicks(10);
            // dungeon() showed the template's server line first; forget it so this run never had one
            run(c, () -> Mod.staticCall(RUN, "resetForTest"));
            c.waitUntil("DungeonRun live with no server", mc -> {
                String d = (String) Mod.staticCall(RUN, "describe");
                return d.contains("server=null") && d.contains("live=true");
            }, 40);
            c.hx().chat("Starting in 1 second.");
            c.waitUntil("Split Timers armed", mc -> armedAtLocal() > 0L, 60);
            run(c, () -> Mod.setField(SCORE, "mimicKilled", true));
            pad(c);
            awayAndBack(c, -59.5, 100, -59.5);
            boolean held = c.onClient(mc -> (Boolean) Mod.staticCall(RUN, "holding"));
            board(c, null, "01m 30s");
            c.waitUntil("Split Timers reset", mc -> armedAtLocal() == 0L, 60);
            c.waitUntil("Score Calculator reset", mc -> !(Boolean) Mod.field(SCORE, "mimicKilled"), 60);
            c.check(!held, "a run with no server line was held: " + describe(c));
            c.note("no server line: not held, splits and score reset; " + describe(c));
        } finally {
            cleanup(c);
        }
    }
}
