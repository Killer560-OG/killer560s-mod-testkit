package dev.testkit.gametest;

import dev.testkit.compat.McCompat;

import com.google.gson.JsonObject;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Quiz's cooldowns (mod quiz-cooldown-1017; killer560, 2026-10-17: "the first time you open it there is a long
 * cooldown till you can answer then a shorter one after that for a total of 3 answers").
 *
 * <p>{@code 1017-sim-quiz-timing}: the sim's Quiz room alone. Walked into (mod quiz-entry-1018), Oruo's lines go out at his pace
 * (question 1 asked 160 server ticks after the intro and answerable at 220; each later lock line 40 ticks after an
 * answer, its question 40 after it, answerable 100 after it - NoammAddons' QuizSolver.kt and his own Hypixel logs); the
 * mod's QuizState reads the same 220 / 100; a correct press before the lock ends is refused; then Auto Quiz answers all
 * three, each on the server tick the lock ends or the one after.
 *
 * <p>{@code 1017-sim-quiz-autosecret}: an F7 with Quiz and Ice Fill pinned and one-node routes seeded (as 102), Auto
 * Secret with Quiz First: the Quiz is its first goal (walking in starts it, and he leaves at once), Ice Fill is done during the first lock,
 * at least one route runs between the later answers, all three answers count, and the time it stood at the Quiz waiting
 * out a lock is under a bound. Both only when named.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimQuizCooldownTests implements FabricClientGameTest {

    private static final String TIMING = "1017-sim-quiz-timing";
    private static final String PLAN = "1017-sim-quiz-autosecret";
    private static final String SIM_QUIZ = "com.killer560.hub.roomsim.puzzles.SimQuizPuzzle";
    private static final String QUIZ_STATE = "com.killer560.hub.puzzlesolvers.QuizState";
    private static final String SOLVER = "com.killer560.hub.puzzlesolvers.QuizSolverFeature";
    private static final String AUTO_CONFIG = "com.killer560.hub.autopuzzles.AutoPuzzlesConfig";
    private static final String SOLVERS = "com.killer560.hub.puzzlesolvers.";
    private static final String LIVE_MAP_CONFIG = "com.killer560.hub.livemap.LiveMapConfig";
    private static final String BUILDER = "com.killer560.hub.roomsim.SimBuilder";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String SIM_WORLD = "com.killer560.hub.roomsim.SimWorld";
    private static final String SIM_RUN = "com.killer560.hub.roomsim.SimRun";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String AS = "com.killer560.hub.autosecret.AutoSecretFeature";
    private static final String AS_CONFIG = "com.killer560.hub.autosecret.AutoSecretConfig";
    private static final String AR_CONFIG = "com.killer560.hub.autoroutes.AutoRoutesConfig";

    private static final Pattern WALKED = Pattern.compile(
            "Sim quiz: \\S+ walked into the Quiz at server tick (\\d+) \\([^)]*\\) - question 1 is asked at (\\d+) and"
                    + " answerable from (\\d+)");
    /** The mod's Quiz logger (2026-10-18): his entry, Oruo's lines, the intro's distance from the entry, questions, presses. */
    private static final Pattern QL_ENTER = Pattern.compile(
            "\\[QuizLog\\] entered the Quiz room \\(entry \\d+ this run\\) at server tick (-?\\d+)");
    private static final Pattern QL_ORUO = Pattern.compile("\\[QuizLog\\] Oruo at server tick (-?\\d+)");
    private static final Pattern QL_INTRO = Pattern.compile("\\[QuizLog\\] intro (-?\\d+) server tick\\(s\\) after he walked in");
    private static final Pattern QL_QUESTION = Pattern.compile("\\[QuizLog\\] question (\\d): \".+\" at server tick (-?\\d+), .*"
            + "QuizState: question \\d, answerable at server tick (-?\\d+)");
    private static final Pattern QL_PRESS = Pattern.compile("\\[QuizLog\\] press \\(([^)]+)\\) on .+? \\(answer (\\S+)\\) at server"
            + " tick (-?\\d+), .*: (EARLY|on time), (\\d+) server tick");
    private static final Pattern QL_RESULT = Pattern.compile("\\[QuizLog\\] press on .+? at server tick (-?\\d+)(?: -> (\\d+)"
            + " server tick\\(s\\) later: (.*)|: no line from Oruo)");
    private static final Pattern ASKED = Pattern.compile("Sim quiz: question (\\d) asked at server tick (\\d+) \\(answerable from (\\d+)\\)");
    private static final Pattern ANNOUNCED = Pattern.compile("Sim quiz: question (\\d) announced at server tick (\\d+) \\(answerable from (\\d+)\\)");
    private static final Pattern ANSWERED = Pattern.compile("Sim quiz: question (\\d) answered at server tick (\\d+) \\(open from (\\d+)");
    /** The most ticks Auto Secret may stand at the Quiz waiting out its three locks, all told. */
    private static final int IDLE_BOUND_TICKS = 200;
    /** Questions 2 and 3: the most ticks an answer may come after its lock ends (rooms are fitted by estimate). */
    private static final int LATE_BOUND_TICKS = 20;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank()) {
            return;
        }
        boolean timing = !Scenario.skip(TIMING);
        boolean plan = !Scenario.skip(PLAN);
        if (!timing && !plan) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(20);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        List<String> failures = new ArrayList<>();
        if (timing) {
            run(ctx, TIMING, failures, () -> timing(ctx, failures));
        }
        if (plan) {
            List<String> f2 = new ArrayList<>();
            run(ctx, PLAN, f2, () -> plan(ctx, f2));
            failures.addAll(f2);
        }
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " problem(s): " + String.join("; ", failures));
        }
    }

    private static void run(ClientGameTestContext ctx, String name, List<String> failures, Runnable body) {
        try {
            body.run();
        } catch (Throwable t) {
            failures.add(name + " threw: " + t);
            t.printStackTrace(System.out);
        } finally {
            ctx.runOnClient(mc -> {
                try {
                    if ((Boolean) ModUnderTest.staticCall(AS, "isRunning")) {
                        ModUnderTest.staticCall(AS, "stop", new Class<?>[]{String.class, boolean.class},
                                new Object[]{"test over", true});
                    }
                    Object auto = ModUnderTest.config(AUTO_CONFIG);
                    ModUnderTest.set(auto, "setAutoQuizEnabled", false);
                    ModUnderTest.set(auto, "setAutoIceFillEnabled", false);
                    ModUnderTest.set(auto, "setAutoPuzzlesMasterEnabled", false);
                } catch (Throwable ignored) {
                    // cleanup must not replace the verdict
                }
            });
            if (ctx.computeOnClient(mc -> mc.level != null)) {
                SimPuzzleSolveTests.teardown(ctx);
            }
        }
        for (String f : failures) {
            println(name, "FAIL: " + f);
        }
        println(name, failures.isEmpty() ? "PASS" : "FAIL (" + failures.size() + ")");
    }

    // ================================================================================================ timing

    private static void timing(ClientGameTestContext ctx, List<String> failures) {
        if (!SimPuzzleSolveTests.seedRooms(ctx)) {
            Scenario.skipped(TIMING, "needs real room captures and room database");
            return;
        }
        ctx.runOnClient(mc -> configure(false, false));
        long mark = LogTap.mark();
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> ModUnderTest.staticCall(BUILDER, "buildSingleRoom",
                new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, "Quiz"})));
        ctx.waitFor(mc -> mc.level != null && mc.player != null, 2400);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitFor(mc -> McCompat.screen(mc) == null, 1200);
        for (int t = 0; t < 1800 && !ctx.computeOnClient(mc -> mc.player.onGround()
                && !mc.level.getBlockState(mc.player.blockPosition().below()).isAir()); t++) {
            ctx.waitTicks(1);
        }
        ctx.waitTicks(40);
        String armed = LogTap.since(mark).stream().filter(l -> l.contains("Sim quiz: Oruo armed")).findFirst().orElse(null);
        println(TIMING, "armed: " + armed);
        if (armed == null) {
            failures.add("the sim's Quiz never armed Oruo");
            return;
        }
        // ---- walking in starts it (killer560, 2026-10-18: "quiz starts once you walk in") -------------------------
        // A lone room's spawn may already be inside it. Either way: out of the footprint first (nothing may start), then
        // back in (the intro must come on that step).
        int[] area = ctx.computeOnClient(mc -> (int[]) ModUnderTest.staticCall(SIM_QUIZ, "oruoArea"));
        if (area == null) {
            failures.add("the sim's Quiz armed no walk-in area");
            return;
        }
        println(TIMING, "walk-in area x " + area[0] + ".." + area[2] + ", z " + area[1] + ".." + area[3]);
        boolean startedAtSpawn = !"IDLE".equals(ctx.computeOnClient(mc -> ModUnderTest.staticCall(SIM_QUIZ, "oruoFlow")));
        boolean spawnInside = ctx.computeOnClient(mc -> inside(area, mc.player.getX(), mc.player.getZ()));
        println(TIMING, "spawned inside the room " + spawnInside + ", Oruo began at spawn " + startedAtSpawn);
        if (startedAtSpawn != spawnInside) {
            failures.add("at spawn: inside the room " + spawnInside + " but Oruo began " + startedAtSpawn);
        }
        if (startedAtSpawn) {
            // Put him back to not started for the controlled step (what a wrong answer does), standing outside.
            ctx.runOnClient(mc -> {
                var server = mc.getSingleplayerServer();
                server.execute(() -> ModUnderTest.staticCall(SIM_QUIZ, "testRearm"));
            });
            ctx.waitTicks(2);
        }
        // Worked out while the map still has him in the room (rel goes through its clay corner).
        double[] in = ctx.computeOnClient(mc -> floorNear(mc, SimPuzzleSolveTests.rel(mc, 15, 69, 3)));
        double[] outside = ctx.computeOnClient(mc -> floorNear(mc, new BlockPos(area[0] - 3, (int) mc.player.getY(),
                (area[1] + area[3]) / 2)));
        if (outside == null) {
            outside = new double[]{area[0] - 2.5, ctx.computeOnClient(mc -> mc.player.getY()) + 3, (area[1] + area[3]) / 2.0};
        }
        teleport(ctx, outside, "outside the room");
        if (!"IDLE".equals(ctx.computeOnClient(mc -> ModUnderTest.staticCall(SIM_QUIZ, "oruoFlow")))) {
            failures.add("Oruo began while he stood outside the room");
        }
        if (!startedAtSpawn && ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(QUIZ_STATE, "started"))) {
            failures.add("QuizState reads started before he walked in");
        }
        long talkMark = LogTap.mark();
        teleport(ctx, in, "inside the room (room-relative 15, 3)");
        List<String> log = LogTap.since(talkMark);
        long t0 = -1;
        for (String l : log) {
            Matcher m = WALKED.matcher(l);
            if (m.find()) {
                t0 = Long.parseLong(m.group(1));
                long ask = Long.parseLong(m.group(2)) - t0;
                long open = Long.parseLong(m.group(3)) - t0;
                println(TIMING, "walked in, intro at server tick " + t0 + ": question 1 asked +" + ask + ", answerable +" + open);
                if (ask != 160 || open != 220) {
                    failures.add("question 1 asked +" + ask + " / answerable +" + open + " after the intro, want +160 / +220");
                }
            }
        }
        if (t0 < 0) {
            failures.add("walking into the room did not start the Quiz");
            return;
        }
        // The [QuizLog]: his entry, then Oruo's intro a few server ticks either side of it (the map names the room on
        // the client, the server sees his feet cross the footprint).
        long entryTick = -1;
        long introTick = -1;
        long introAfter = Long.MIN_VALUE;
        for (String l : log) {
            Matcher m = QL_ENTER.matcher(l);
            if (entryTick < 0 && m.find()) {
                entryTick = Long.parseLong(m.group(1));
            }
            m = QL_ORUO.matcher(l);
            if (introTick < 0 && m.find() && l.contains("I am Oruo the Omniscient")) {
                introTick = Long.parseLong(m.group(1));
            }
            m = QL_INTRO.matcher(l);
            if (m.find()) {
                introAfter = Long.parseLong(m.group(1));
            }
        }
        println(TIMING, "[QuizLog]: entered at server tick " + entryTick + ", intro at " + introTick + ", intro "
                + introAfter + " tick(s) after the entry");
        if (entryTick < 0 || introTick < 0 || introAfter == Long.MIN_VALUE) {
            failures.add("the [QuizLog] entry / intro lines are missing (entry " + entryTick + ", intro " + introTick + ")");
        } else if (Math.abs(introTick - entryTick) > 20 || introAfter != introTick - entryTick) {
            failures.add("[QuizLog] intro " + (introTick - entryTick) + " tick(s) from the entry (want within 20, and the"
                    + " intro line saying the same; it said " + introAfter + ")");
        }
        boolean introSeen = log.stream().anyMatch(l -> l.contains("[STATUE] Oruo the Omniscient: I am Oruo the Omniscient."));
        long[] qs = ctx.computeOnClient(mc -> new long[]{
                ((Number) ModUnderTest.staticCall(QUIZ_STATE, "question")).longValue(),
                ((Number) ModUnderTest.staticCall(QUIZ_STATE, "answerAtTick")).longValue(),
                ((Number) ModUnderTest.staticCall(QUIZ_STATE, "lastLineTick")).longValue()});
        println(TIMING, "intro in chat " + introSeen + "; QuizState question " + qs[0] + ", answerable "
                + (qs[1] - qs[2]) + " ticks after the line");
        if (!introSeen) {
            failures.add("Oruo's intro line never reached chat");
        }
        if (qs[0] != 1 || qs[1] - qs[2] != 220) {
            failures.add("QuizState after the intro: question " + qs[0] + ", lock " + (qs[1] - qs[2]) + " ticks (want 1, 220)");
        }

        // ---- the early press ----------------------------------------------------------------------------------
        double[] spot = ctx.computeOnClient(mc -> floorNear(mc, SimPuzzleSolveTests.rel(mc, 15, 69, 6)));
        teleport(ctx, spot, "between the three answer pillars (room-relative 15, 6)");
        for (int t = 0; t < 400 && ctx.computeOnClient(mc -> ModUnderTest.staticCall(SOLVER, "getCorrectAnswerPos")) == null; t++) {
            ctx.waitTicks(1);
        }
        long[] lock = ctx.computeOnClient(mc -> new long[]{
                ((Number) ModUnderTest.staticCall(SIM_QUIZ, "oruoServerTick")).longValue(),
                ((Number) ModUnderTest.staticCall(SIM_QUIZ, "oruoOpenAt")).longValue()});
        if (ctx.computeOnClient(mc -> ModUnderTest.staticCall(SOLVER, "getCorrectAnswerPos")) == null) {
            failures.add("the solver never had question 1's answer");
            return;
        }
        if (lock[0] >= lock[1]) {
            failures.add("question 1 was already open (" + lock[0] + " >= " + lock[1] + ") - the early press was not tested");
        } else {
            int refusedBefore = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(SIM_QUIZ, "oruoRefused"));
            String press = ctx.computeOnClient(SimQuizCooldownTests::pressCorrect);
            ctx.waitTicks(5);
            int refused = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(SIM_QUIZ, "oruoRefused"));
            int accepted = ctx.computeOnClient(mc -> ((List<?>) ModUnderTest.staticCall(SIM_QUIZ, "oruoAccepted")).size());
            println(TIMING, String.format(Locale.US, "early press (%s) %d tick(s) before the lock: refused %d -> %d, "
                    + "accepted %d", press, lock[1] - lock[0], refusedBefore, refused, accepted));
            if (refused != refusedBefore + 1 || accepted != 0) {
                failures.add("a correct press " + (lock[1] - lock[0]) + " tick(s) before the lock ended was not refused");
            }
        }

        // ---- Auto Quiz answers all three --------------------------------------------------------------------
        ctx.runOnClient(mc -> configure(true, false));
        long autoMark = LogTap.mark();
        for (int t = 0; t < 20 * 60 && !"DONE".equals(ctx.computeOnClient(mc -> ModUnderTest.staticCall(SIM_QUIZ,
                "oruoFlow"))); t += 5) {
            ctx.waitTicks(5);
        }
        @SuppressWarnings("unchecked")
        List<long[]> acc = ctx.computeOnClient(mc -> (List<long[]>) ModUnderTest.staticCall(SIM_QUIZ, "oruoAccepted"));
        StringBuilder sb = new StringBuilder();
        for (long[] a : acc) {
            sb.append(String.format(Locale.US, "Q%d at %d (open %d, +%d) ", a[0], a[1], a[2], a[1] - a[2]));
            if (a[1] - a[2] < 0 || a[1] - a[2] > 1) {
                failures.add("question " + a[0] + " answered " + (a[1] - a[2]) + " tick(s) after its lock ended (want 0 or 1)");
            }
        }
        println(TIMING, "answers: " + sb);
        if (acc.size() != 3) {
            failures.add("Auto Quiz answered " + acc.size() + " of 3 questions");
        }
        // Oruo's pace between questions, from the sim's own log.
        Map<Integer, long[]> announced = new HashMap<>();
        Map<Integer, Long> asked = new HashMap<>();
        Map<Integer, Long> answered = new HashMap<>();
        for (String l : LogTap.since(mark)) {
            Matcher m = ANNOUNCED.matcher(l);
            if (m.find()) {
                announced.put(Integer.parseInt(m.group(1)), new long[]{Long.parseLong(m.group(2)), Long.parseLong(m.group(3))});
            }
            m = ASKED.matcher(l);
            if (m.find()) {
                asked.put(Integer.parseInt(m.group(1)), Long.parseLong(m.group(2)));
            }
            m = ANSWERED.matcher(l);
            if (m.find()) {
                answered.put(Integer.parseInt(m.group(1)), Long.parseLong(m.group(2)));
            }
        }
        for (int q = 2; q <= 3; q++) {
            long[] an = announced.get(q);
            Long ask = asked.get(q);
            Long prev = answered.get(q - 1);
            if (an == null || ask == null || prev == null) {
                failures.add("question " + q + ": no announce / ask / previous answer line in the sim's log");
                continue;
            }
            println(TIMING, String.format(Locale.US, "question %d: lock line +%d after answer %d, asked +%d after it, "
                    + "answerable +%d after it", q, an[0] - prev, q - 1, ask - an[0], an[1] - an[0]));
            if (an[0] - prev != 40 || ask - an[0] != 40 || an[1] - an[0] != 100) {
                failures.add("question " + q + "'s pace is +" + (an[0] - prev) + "/+" + (ask - an[0]) + "/+"
                        + (an[1] - an[0]) + " (want +40/+40/+100)");
            }
        }
        quizLogChecks(LogTap.since(talkMark), failures);
        boolean auto = LogTap.since(autoMark).stream().anyMatch(l -> l.contains("[AutoPuzzles] Quiz: answered question"));
        boolean done = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(QUIZ_STATE, "done"));
        boolean complete = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SIM_QUIZ, "isQuizComplete"));
        println(TIMING, "Auto Quiz answered: " + auto + "; QuizState done " + done + "; sim complete " + complete);
        if (!auto) {
            failures.add("the answers were not Auto Quiz's");
        }
        if (!done || !complete) {
            failures.add("after three answers: QuizState done " + done + ", sim complete " + complete);
        }
        // A talk after the last answer gets Oruo's "already shown" line.
        long againMark = LogTap.mark();
        double[] near = ctx.computeOnClient(SimQuizCooldownTests::spotNearOruo);
        teleport(ctx, near, "back by Oruo");
        ctx.computeOnClient(SimQuizCooldownTests::talk);
        ctx.waitTicks(10);
        boolean already = LogTap.since(againMark).stream().anyMatch(l -> l.contains("You have already shown your vast knowledge"));
        println(TIMING, "talk after the last answer: 'already shown' line " + already);
        if (!already) {
            failures.add("talking to Oruo after the last answer did not get his 'already shown' line");
        }
    }

    // ================================================================================================ the plan

    private static void plan(ClientGameTestContext ctx, List<String> failures) {
        if (SimMapTests.copyRoomsForOthers() < 20) {
            Scenario.skipped(PLAN, "needs real room captures and room database");
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);
        ctx.runOnClient(mc -> {
            configure(true, true);
            // His setting (docs/LESSONS-AUTOMATION.md): a reward chest's window is closed at once. Without it the Ice Fill
            // chest's window stayed up and nothing moved for four minutes (first run, 2026-10-18).
            ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autoclosechest.AutoCloseChestConfig"), "setEnabled",
                    true);
            Object ar = ModUnderTest.config(AR_CONFIG);
            ModUnderTest.set(ar, "setEnabled", true);
            ModUnderTest.set(ar, "setLegitMode", false);
            ModUnderTest.set(ar, "setStartFromStartNodeOnly", true);
            ModUnderTest.call(ar, "setHeight", new Class<?>[]{float.class}, new Object[]{1.0f});
            Object as = ModUnderTest.config(AS_CONFIG);
            ModUnderTest.set(as, "setQuizFirst", true);
            ModUnderTest.set(as, "setIceFillFirst", true);
            ModUnderTest.set(as, "setInstaClear", false);
            ModUnderTest.set(as, "setPuzzleWaitSeconds", 0);
            ModUnderTest.set(as, "setRouteStallSeconds", 10);
        });
        int renderBefore = ctx.computeOnClient(mc -> mc.options.renderDistance().get());
        ctx.runOnClient(mc -> mc.options.renderDistance().set(16));
        try {
            planBody(ctx, failures);
        } finally {
            SimAutoSecretTests.writeRoutes(ctx, Map.of());
            ctx.runOnClient(mc -> mc.options.renderDistance().set(renderBefore));
        }
    }

    private static void planBody(ClientGameTestContext ctx, List<String> failures) {
        String code = null;
        for (int i = 0; i < 6 && code == null; i++) {
            code = ctx.computeOnClient(mc -> planFloor());
        }
        if (code == null) {
            failures.add("no F7 with both Quiz and Ice Fill could be planned");
            return;
        }
        String floorCode = code;
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter", new Class<?>[]{String.class},
                new Object[]{"gametest"}));
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> {
            ModUnderTest.staticCall(SIM_STATE, "setFloorLabel", new Class<?>[]{String.class}, new Object[]{"F7"});
            java.util.function.Consumer<Minecraft> build = c -> ModUnderTest.staticCall(BUILDER, "build",
                    new Class<?>[]{Minecraft.class, String.class}, new Object[]{c, floorCode});
            ModUnderTest.staticCall(SIM_WORLD, "open", new Class<?>[]{Minecraft.class, String.class,
                    java.util.function.Consumer.class, String.class}, new Object[]{mc, floorCode, build, "Generating Floor 7"});
        }));
        ctx.waitFor(mc -> mc.level != null, 2400);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitTicks(80);
        SimAutoSecretTests.awaitFloorChunks(ctx);
        SimAutoSecretTests.giveAotv(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_RUN, "begin", new Class<?>[]{Minecraft.class, BlockPos.class},
                new Object[]{mc, ModUnderTest.staticCall(BUILDER, "entranceDoor")}));
        for (int i = 0; i < 400 && !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SIM_RUN, "isRunning")); i++) {
            ctx.waitTicks(1);
        }
        ctx.waitTicks(40);
        for (int i = 0; i < 100 && !ctx.computeOnClient(mc -> mc.player.onGround()); i++) {
            ctx.waitTicks(1);
        }
        // Routes: one ROTATE start node in up to five reachable secret rooms (102's route), so there is work to fit in.
        List<SimAutoSecretTests.Room> rooms = ctx.computeOnClient(SimAutoSecretTests::readRooms);
        Map<String, JsonObject> routes = new HashMap<>();
        boolean quiz = false;
        boolean ice = false;
        for (SimAutoSecretTests.Room r : rooms) {
            quiz |= r.name().equals("Quiz") && r.reachable();
            ice |= r.name().equals("Ice Fill") && r.reachable();
            boolean ok = !r.name().equals("Unknown") && !r.type().equals("PUZZLE") && !r.type().equals("TRAP")
                    && !r.type().equals("BLOOD") && !r.type().equals("ENTRANCE") && r.unfound() > 0 && r.reachable()
                    && SimAutoSecretTests.pathsOut(r);
            if (ok && routes.size() < 5) {
                JsonObject route = SimAutoSecretTests.routeFor(ctx, r);
                if (route != null) {
                    routes.put(r.name(), route);
                }
            }
        }
        println(PLAN, "floor " + floorCode + ": Quiz reachable " + quiz + ", Ice Fill reachable " + ice + ", routes in "
                + routes.keySet());
        if (!quiz || !ice || routes.isEmpty()) {
            failures.add("the floor lacks a reachable Quiz, Ice Fill or routed room - nothing to test");
            return;
        }
        SimAutoSecretTests.writeRoutes(ctx, routes);

        long mark = LogTap.mark();
        Boolean started = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(AS, "start"));
        if (!Boolean.TRUE.equals(started)) {
            failures.add("Auto Secret refused to start");
            return;
        }
        int ticks = 0;
        for (; ticks < 20 * 240; ticks += 10) {
            ctx.waitTicks(10);
            boolean done = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(QUIZ_STATE, "done"));
            boolean running = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(AS, "isRunning"));
            if (done || !running) {
                break;
            }
        }
        int idle = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(AS, "quizIdleTicks"));
        List<String> log = LogTap.since(mark);
        // The order of events, as each first appears.
        List<String> events = new ArrayList<>();
        int quizFirst = -1;
        int talked = -1;
        int left = -1;
        int qlEntry = -1;
        long qlIntroAfter = Long.MIN_VALUE;
        int iceFirst = -1;
        int iceDone = -1;
        int firstTarget = -1;
        List<Integer> answers = new ArrayList<>();
        List<Integer> routeDone = new ArrayList<>();
        List<String> late = new ArrayList<>();
        for (int i = 0; i < log.size(); i++) {
            String l = log.get(i);
            if (quizFirst < 0 && l.contains("[AutoSecret] Quiz first:")) {
                quizFirst = i;
                events.add("quiz first");
            }
            if (talked < 0 && WALKED.matcher(l).find()) {
                talked = i;
                events.add("walked into the Quiz");
            }
            if (left < 0 && l.contains("[AutoSecret] Quiz: Oruo began")) {
                left = i;
                events.add("left the Quiz");
            }
            if (qlEntry < 0 && QL_ENTER.matcher(l).find()) {
                qlEntry = i;
            }
            Matcher qi = QL_INTRO.matcher(l);
            if (qi.find()) {
                qlIntroAfter = Long.parseLong(qi.group(1));
            }
            if (l.contains("[AutoPuzzles] Quiz: talked to Oruo") || l.contains("Sim quiz: talked to Oruo")) {
                failures.add("something still talks to Oruo: " + l);
            }
            if (iceFirst < 0 && l.contains("[AutoSecret] Ice Fill first:")) {
                iceFirst = i;
                events.add("ice fill");
            }
            if (iceDone < 0 && l.contains("[AutoSecret] Ice Fill done")) {
                iceDone = i;
                events.add("ice fill done");
            }
            if (firstTarget < 0 && l.contains("[AutoSecret] target ")) {
                firstTarget = i;
            }
            Matcher m = ANSWERED.matcher(l);
            if (m.find()) {
                answers.add(i);
                long lateBy = Long.parseLong(m.group(2)) - Long.parseLong(m.group(3));
                late.add("Q" + m.group(1) + " +" + lateBy);
                if (!m.group(1).equals("1") && lateBy > LATE_BOUND_TICKS) {
                    // Question 1 waits on Ice Fill, done whatever it costs (his order); the later ones are planned to fit.
                    failures.add("question " + m.group(1) + " answered " + lateBy + " tick(s) after it opened (bound "
                            + LATE_BOUND_TICKS + ")");
                }
                events.add("answer " + m.group(1) + " (+" + lateBy + " ticks)");
            }
            if (l.contains("[AutoSecret] route ") && l.contains(" finished")) {
                routeDone.add(i);
                events.add(l.replaceAll("^.*\\[AutoSecret\\] ", ""));
            }
            if (l.contains("[AutoSecret] Quiz:") && l.contains("round trips")) {
                println(PLAN, "  " + l.replaceAll("^.*\\[AutoSecret\\] ", ""));
            }
        }
        println(PLAN, String.format(Locale.US, "%.1f s; events: %s; idle at the Quiz %d tick(s); lateness %s", ticks / 20.0,
                events, idle, late));
        if (quizFirst < 0 || talked < 0) {
            failures.add("the Quiz was not started (quiz-first line " + (quizFirst >= 0) + ", walk-in " + (talked >= 0) + ")");
        } else if (firstTarget >= 0 && firstTarget < talked || iceFirst >= 0 && iceFirst < talked) {
            failures.add("something else came before the Quiz was started");
        }
        if (left < 0 || talked >= 0 && left < talked || iceFirst >= 0 && left > iceFirst) {
            failures.add("Auto Secret did not leave the Quiz for Ice Fill once Oruo began (walk-in " + talked + ", left " + left
                    + ", ice fill " + iceFirst + ")");
        }
        println(PLAN, "[QuizLog] entry line " + (qlEntry >= 0) + ", intro " + qlIntroAfter + " server tick(s) after it");
        if (qlEntry < 0 || qlIntroAfter == Long.MIN_VALUE || Math.abs(qlIntroAfter) > 20) {
            failures.add("[QuizLog] entry/intro: entry line " + (qlEntry >= 0) + ", intro " + qlIntroAfter
                    + " tick(s) after it (want within 20)");
        }
        if (iceFirst < 0 || iceDone < 0) {
            failures.add("Ice Fill was not done (started " + (iceFirst >= 0) + ", done " + (iceDone >= 0) + ")");
        } else if (answers.isEmpty() || iceFirst > answers.get(0)) {
            failures.add("Ice Fill was not started during question 1's lock");
        }
        if (answers.size() != 3) {
            failures.add(answers.size() + " of 3 answers counted");
        } else {
            boolean between = false;
            for (int i : routeDone) {
                between |= i > answers.get(0) && i < answers.get(2);
            }
            if (!between) {
                failures.add("no room's route ran between the first and last answers");
            }
        }
        if (idle > IDLE_BOUND_TICKS) {
            failures.add("stood " + idle + " tick(s) at the Quiz waiting out its locks (bound " + IDLE_BOUND_TICKS + ")");
        }
    }

    /**
     * The [QuizLog] lines of one timed Quiz: each question with QuizState's open 60 ticks after it is asked (asked +160 /
     * open +220 from the intro; asked +40 / open +100 from a lock line); the early press logged EARLY with no answer from
     * Oruo; Auto Quiz's three presses on time (0-1 ticks after the open) and each answered by his "answered" line.
     */
    private static void quizLogChecks(List<String> log, List<String> failures) {
        int questions = 0;
        int early = 0;
        int earlyUnanswered = 0;
        int onTime = 0;
        int answeredResults = 0;
        for (String l : log) {
            Matcher m = QL_QUESTION.matcher(l);
            if (m.find()) {
                questions++;
                long gap = Long.parseLong(m.group(3)) - Long.parseLong(m.group(2));
                if (gap < 58 || gap > 62) {
                    failures.add("[QuizLog] question " + m.group(1) + " opens " + gap + " tick(s) after it is asked (want 60)");
                }
            }
            m = QL_PRESS.matcher(l);
            if (m.find()) {
                if (m.group(4).equals("EARLY")) {
                    early++;
                } else if (m.group(1).equals("Auto Quiz")) {
                    onTime++;
                    if (Long.parseLong(m.group(5)) > 1) {
                        failures.add("[QuizLog] an Auto Quiz press " + m.group(5) + " tick(s) after the open");
                    }
                }
                if (m.group(2).equals("?") || m.group(2).equals("none")) {
                    failures.add("[QuizLog] a press named no answer pillar: " + l);
                }
            }
            m = QL_RESULT.matcher(l);
            if (m.find()) {
                if (m.group(3) == null) {
                    earlyUnanswered++;
                } else if (m.group(3).contains("answered")) {
                    answeredResults++;
                }
            }
        }
        println(TIMING, "[QuizLog]: " + questions + " question line(s), " + early + " early press(es), " + earlyUnanswered
                + " press(es) with no line from Oruo, " + onTime + " Auto Quiz press(es) on time, " + answeredResults
                + " answered by his line");
        if (questions != 3) {
            failures.add("[QuizLog] logged " + questions + " of 3 questions");
        }
        if (early != 1 || earlyUnanswered < 1) {
            failures.add("[QuizLog] early press: " + early + " logged EARLY, " + earlyUnanswered + " with no line (want 1, 1)");
        }
        if (onTime != 3 || answeredResults != 3) {
            failures.add("[QuizLog] Auto Quiz presses on time " + onTime + ", answered by Oruo " + answeredResults + " (want 3, 3)");
        }
    }

    // ================================================================================================ helpers

    private static boolean inside(int[] area, double x, double z) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        return bx >= area[0] && bx <= area[2] && bz >= area[1] && bz <= area[3];
    }

    /** Quiz solver on; Auto Puzzles master, pathing and the map on; Auto Quiz {@code quiz}; Ice Fill's auto {@code ice}. */
    private static void configure(boolean quiz, boolean ice) {
        Object auto = ModUnderTest.config(AUTO_CONFIG);
        ModUnderTest.set(auto, "setAutoPuzzlesMasterEnabled", true);
        ModUnderTest.set(auto, "setEtherwarpReposition", true);
        ModUnderTest.set(auto, "setAutoPuzzlePathingEnabled", true);
        ModUnderTest.set(auto, "setAutoQuizEnabled", quiz);
        ModUnderTest.set(auto, "setAutoIceFillEnabled", ice);
        ModUnderTest.set(auto, "setIceFillAdaptive", false);
        ModUnderTest.set(auto, "setIceFillDelayTicks", 2);
        ModUnderTest.set(ModUnderTest.config(SOLVERS + "QuizSolverConfig"), "setEnabled", true);
        Object iceSolver = ModUnderTest.config(SOLVERS + "IceFillSolverConfig");
        ModUnderTest.set(iceSolver, "setEnabled", ice);
        ModUnderTest.set(iceSolver, "setOptimizedPath", false);
        Object map = ModUnderTest.config(LIVE_MAP_CONFIG);
        ModUnderTest.set(map, "setEnabled", true);
        ModUnderTest.set(map, "setInteractiveMapEnabled", true);
        ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autoclosechest.AutoCloseChestConfig"), "setEnabled", false);
    }

    /** An F7 with Quiz and Ice Fill pinned; its code, or null when a pin was dropped. Client thread. */
    private static String planFloor() {
        Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
        Map<Integer, String> pins = new HashMap<>();
        pins.put(14, "Quiz");
        pins.put(21, "Ice Fill");
        Object planned = ModUnderTest.staticCall(FLOOR_GEN, "plan", new Class<?>[]{floor.getClass(), int.class, int.class,
                Map.class}, new Object[]{floor, 4, 5, pins});
        if (planned == null) {
            return null;
        }
        @SuppressWarnings("unchecked")
        List<String> kept = (List<String>) ModUnderTest.call(planned, "keptPins", new Class<?>[0], new Object[0]);
        println(PLAN, "planned an F7, pins kept: " + kept);
        if (kept == null || kept.size() < 2) {
            return null;
        }
        return (String) ModUnderTest.call(planned, "code", new Class<?>[0], new Object[0]);
    }

    /** Oruo's name stand (the sim has no CLICK stand since mod quiz-entry-1018: walking in starts him). */
    private static Entity oruoClick(Minecraft mc) {
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof ArmorStand && e.getName().getString().contains("Oruo the Omniscient") && e.distanceTo(mc.player) < 48) {
                return e;
            }
        }
        return null;
    }

    /** A standable spot whose eye is within 2.5 of Oruo's stand's box. Client thread. */
    private static double[] spotNearOruo(Minecraft mc) {
        Entity e = oruoClick(mc);
        if (e == null) {
            return null;
        }
        AABB box = e.getBoundingBox();
        BlockPos c = e.blockPosition();
        double[] best = null;
        double bestD = 2.5;
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = -4; dy <= 2; dy++) {
                    BlockPos b = c.offset(dx, dy, dz);
                    if (mc.level.getBlockState(b).isAir() || !mc.level.getBlockState(b.above()).isAir()
                            || !mc.level.getBlockState(b.above(2)).isAir()) {
                        continue;
                    }
                    Vec3 eye = new Vec3(b.getX() + 0.5, b.getY() + 1 + 1.62, b.getZ() + 0.5);
                    double d = Math.sqrt(sq(Math.max(0, Math.max(box.minX - eye.x, eye.x - box.maxX)))
                            + sq(Math.max(0, Math.max(box.minY - eye.y, eye.y - box.maxY)))
                            + sq(Math.max(0, Math.max(box.minZ - eye.z, eye.z - box.maxZ))));
                    if (d < bestD) {
                        bestD = d;
                        best = new double[]{b.getX() + 0.5, b.getY() + 1, b.getZ() + 0.5};
                    }
                }
            }
        }
        return best;
    }

    /** Talks to Oruo's CLICK stand as a right click would. Client thread. */
    private static String talk(Minecraft mc) {
        Entity e = oruoClick(mc);
        if (e == null) {
            return "no Oruo CLICK stand";
        }
        Vec3 eye = mc.player.getEyePosition();
        AABB box = e.getBoundingBox();
        Vec3 aim = box.clip(eye, box.getCenter()).orElse(box.getCenter());
        mc.gameMode.interact(mc.player, e, new EntityHitResult(e, aim), InteractionHand.MAIN_HAND);
        mc.player.swing(InteractionHand.MAIN_HAND);
        return String.format(Locale.US, "interacted with the stand at %.1f %.1f %.1f from %.2f", e.getX(), e.getY(), e.getZ(),
                eye.distanceTo(aim));
    }

    /** Presses the correct answer's nearest button. Client thread. */
    private static String pressCorrect(Minecraft mc) {
        BlockPos b = (BlockPos) ModUnderTest.staticCall(SOLVER, "getCorrectAnswerButton",
                new Class<?>[]{net.minecraft.world.level.Level.class, Vec3.class}, new Object[]{mc.level, mc.player.getEyePosition()});
        if (b == null) {
            return "no button";
        }
        Vec3 c = Vec3.atCenterOf(b);
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(c,
                net.minecraft.core.Direction.getApproximateNearest(mc.player.getEyePosition().subtract(c)), b, false));
        mc.player.swing(InteractionHand.MAIN_HAND);
        return "button " + b.toShortString();
    }

    private static double[] floorNear(Minecraft mc, BlockPos column) {
        double[] best = null;
        for (int dy = -12; dy <= 12; dy++) {
            BlockPos b = column.offset(0, dy, 0);
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

    private static void teleport(ClientGameTestContext ctx, double[] spot, String what) {
        if (spot == null) {
            throw new AssertionError("no standable spot " + what);
        }
        AtomicReference<Boolean> done = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(uuid);
                if (sp != null) {
                    sp.teleportTo(spot[0], spot[1], spot[2]);
                }
                done.set(true);
            });
        });
        ctx.waitFor(mc -> done.get() != null, 200);
        ctx.waitTicks(20);
        println(TIMING, String.format(Locale.US, "placed %s: %.1f %.1f %.1f", what, spot[0], spot[1], spot[2]));
    }

    private static double sq(double v) {
        return v * v;
    }

    private static void println(String name, String line) {
        System.out.println("[" + name + "] " + line);
    }
}
