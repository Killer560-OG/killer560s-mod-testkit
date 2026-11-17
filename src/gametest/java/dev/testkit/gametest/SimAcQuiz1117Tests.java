package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Auto Clear and the Quiz's countdown (acquiz-1117, killer560 2026-11-17: "if that timer isn't ready then don't go back to
 * click it or wait there have it go clear ... then come back"; "It can return a hair late as well if there are other things
 * to do"). A sim F7 with live mobs and the Quiz, Auto Clear alone (Quiz First, Auto Quiz, the Quiz Solver).
 * <ul>
 *   <li>1117-sim-acquiz-gap: while a question's lock runs it leaves to clear rooms (a gap line, a sim room clear between
 *   Oruo's start and the last answer), and still comes back and answers all three, each within {@link #LATE_BOUND} of its
 *   opening; prints the floor's clear time and map code. On a jar without the gap (no {@code quizGapCount}) it only prints
 *   the times: the baseline for the same floor, replayed with -PmobfloorCode.</li>
 *   <li>1117-sim-acquiz-wait: the same with every mob gone (nothing else to do): no gap, it waits at the Quiz and answers
 *   each on time.</li>
 * </ul>
 */
public class SimAcQuiz1117Tests implements FabricClientGameTest {
    private static final String GAP = "1117-sim-acquiz-gap";
    private static final String WAIT = "1117-sim-acquiz-wait";
    private static final String AUTO = "com.killer560.hub.autoclear.AutoClearFeature";
    private static final String AUTO_CONFIG = "com.killer560.hub.autoclear.AutoClearConfig";
    private static final String QUIZ_STATE = "com.killer560.hub.puzzlesolvers.QuizState";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String SIM_FLOOR = "com.killer560.hub.roomsim.SimMobFloor";
    private static final Pattern ANSWERED = Pattern.compile("Sim quiz: question (\\d) answered at server tick (\\d+) \\(open from (\\d+)");
    /** Generous: a late return is fine while rooms remain, about one more room's clear. */
    private static final int LATE_BOUND = 300;
    /** With nothing else to do, Auto Quiz answers on the opening tick (0-1 ticks); allow load. */
    private static final int WAIT_LATE_BOUND = 10;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank()) {
            return;
        }
        boolean gap = !Scenario.skip(GAP);
        boolean wait = !Scenario.skip(WAIT);
        if (!gap && !wait) {
            return;
        }
        if (!dev.testkit.gametest.mod.Mod.isCheat()) {
            if (gap) Scenario.skipped(GAP, "Auto Clear is cheat build only");
            if (wait) Scenario.skipped(WAIT, "Auto Clear is cheat build only");
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (SimMapTests.copyRoomsForOthers() < 20) {
            if (gap) Scenario.skipped(GAP, "needs real room captures and room database");
            if (wait) Scenario.skipped(WAIT, "needs real room captures and room database");
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.RoomLibrary", "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.roomsim.RoomLibrary", "isReady"), 2400);
        int renderBefore = ctx.computeOnClient(mc -> mc.options.renderDistance().get());
        ctx.runOnClient(mc -> mc.options.renderDistance().set(16));
        List<String> failures = new ArrayList<>();
        try {
            if (wait) {
                one(ctx, WAIT, false, failures);
            }
            if (gap) {
                one(ctx, GAP, true, failures);
            }
        } finally {
            ctx.runOnClient(mc -> mc.options.renderDistance().set(renderBefore));
        }
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " problem(s): " + String.join("; ", failures));
        }
    }

    private static void one(ClientGameTestContext ctx, String name, boolean withMobs, List<String> allFailures) {
        List<String> failures = new ArrayList<>();
        String before = System.getProperty("testkit.mobfloorCode", "");
        try {
            if (before.isBlank()) {
                String code = null;
                for (int i = 0; i < 8 && code == null; i++) {
                    code = ctx.computeOnClient(mc -> planFloor(name));
                }
                if (code == null) {
                    failures.add("no F7 with the Quiz could be planned");
                    return;
                }
                System.setProperty("testkit.mobfloorCode", code);
            }
            List<String> problems = new ArrayList<>();
            List<String> facts = new ArrayList<>();
            SimMobFloorTests.buildMobFloor(ctx, problems, facts);
            for (String f : facts) {
                println(name, "FACT " + f);
            }
            if (ctx.computeOnClient(mc -> mc.level == null)) {
                failures.add("the floor did not load: " + problems);
                return;
            }
            body(ctx, name, withMobs, failures);
        } catch (Throwable t) {
            failures.add("threw: " + t);
            t.printStackTrace(System.out);
        } finally {
            if (before.isBlank()) {
                System.clearProperty("testkit.mobfloorCode");
            }
            ctx.runOnClient(mc -> {
                try {
                    Object auto = ModUnderTest.config("com.killer560.hub.autopuzzles.AutoPuzzlesConfig");
                    ModUnderTest.set(auto, "setAutoQuizEnabled", false);
                    ModUnderTest.set(auto, "setAutoPuzzlesMasterEnabled", false);
                } catch (Throwable ignored) {
                    // cleanup must not replace the verdict
                }
            });
            SimMobFloorTests.leave(ctx);
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_FLOOR, "setEnabled", new Class<?>[]{boolean.class},
                    new Object[]{false}));
            for (String f : failures) {
                println(name, "FAIL: " + f);
            }
            println(name, failures.isEmpty() ? "PASS" : "FAIL (" + failures.size() + ")");
            for (String f : failures) {
                allFailures.add(name + ": " + f);
            }
        }
    }

    private static void body(ClientGameTestContext ctx, String name, boolean withMobs, List<String> failures) {
        ctx.runOnClient(mc -> {
            Object auto = ModUnderTest.config("com.killer560.hub.autopuzzles.AutoPuzzlesConfig");
            ModUnderTest.set(auto, "setAutoPuzzlesMasterEnabled", true);
            ModUnderTest.set(auto, "setEtherwarpReposition", true);
            ModUnderTest.set(auto, "setAutoPuzzlePathingEnabled", true);
            ModUnderTest.set(auto, "setAutoQuizEnabled", true);
            ModUnderTest.set(auto, "setAutoIceFillEnabled", false);
            ModUnderTest.set(ModUnderTest.config("com.killer560.hub.puzzlesolvers.QuizSolverConfig"), "setEnabled", true);
            ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autosecret.AutoSecretConfig"), "setQuizFirst", true);
            ModUnderTest.set(ModUnderTest.config(AUTO_CONFIG), "setEnabled", true);
        });
        SimAutoClearTests.setMode(ctx, "ANY_MOB_ROOM", "SPIRIT_SCEPTRE");
        if (!withMobs) {
            java.util.concurrent.atomic.AtomicInteger removed = new java.util.concurrent.atomic.AtomicInteger(-1);
            ctx.runOnClient(mc -> removeMobs(mc, removed));
            ctx.waitFor(mc -> removed.get() >= 0, 400);
            int gone = removed.get();
            ctx.waitTicks(20);
            println(name, "removed " + gone + " mob(s) on the server: nothing else to clear");
        }
        boolean baseline;
        try {
            ctx.computeOnClient(mc -> ModUnderTest.staticCall(AUTO, "quizGapCount"));
            baseline = false;
        } catch (Throwable t) {
            baseline = true;
            println(name, "BASELINE jar (no Quiz gap hook): times only");
        }
        long mark = LogTap.mark();
        ctx.runOnClient(mc -> ModUnderTest.staticCall(AUTO, "start"));
        int minutes = Integer.getInteger("testkit.mobfloorMinutes", 12);
        int ticks = 0;
        int quizDoneAt = -1;
        boolean started = false;
        for (; ticks < minutes * 1200; ticks += 5) {
            ctx.waitTicks(5);
            if (ctx.computeOnClient(mc -> mc.level == null || mc.player == null)) {
                failures.add("the world went away");
                return;
            }
            boolean busy = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(AUTO, "isBusy"));
            started |= busy;
            if (quizDoneAt < 0 && ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(QUIZ_STATE, "done"))) {
                quizDoneAt = ticks;
                if (!withMobs) {
                    break;   // the wait case is over once the Quiz is
                }
            }
            if (started && !busy) {
                break;
            }
            if (ticks % 1200 == 1195) {
                println(name, String.format(Locale.US, "t=%ds: %s", (ticks + 5) / 20,
                        ctx.computeOnClient(mc -> String.valueOf(ModUnderTest.staticCall(AUTO, "status")))));
            }
        }
        boolean stillBusy = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(AUTO, "isBusy"));
        int gaps = baseline ? -1 : ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(AUTO, "quizGapCount"));
        int idle = baseline ? -1 : ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(AUTO, "quizIdleTicks"));
        ctx.runOnClient(mc -> ModUnderTest.staticCall(AUTO, "cancel"));
        List<String> log = LogTap.since(mark);
        int oruo = -1;
        int lastAnswer = -1;
        List<Integer> clears = new ArrayList<>();
        List<String> late = new ArrayList<>();
        List<Long> lateTicks = new ArrayList<>();
        for (int i = 0; i < log.size(); i++) {
            String l = log.get(i);
            if (oruo < 0 && (l.contains("Oruo began") || l.contains("I am Oruo the Omniscient"))) {
                oruo = i;
            }
            if (l.contains("[Sim] room cleared:") || l.contains("[AutoClear] room cleared:")) {
                clears.add(i);
            }
            if (l.contains("[AutoClear] Quiz countdown")) {
                println(name, "  " + l.replaceAll("^.*\\[AutoClear\\] ", ""));
            }
            Matcher m = ANSWERED.matcher(l);
            if (m.find()) {
                lastAnswer = i;
                long by = Long.parseLong(m.group(2)) - Long.parseLong(m.group(3));
                lateTicks.add(by);
                late.add("Q" + m.group(1) + " +" + by);
            }
        }
        int between = 0;
        for (int c : clears) {
            between += oruo >= 0 && c > oruo && (lastAnswer < 0 || c < lastAnswer) ? 1 : 0;
        }
        String code = ctx.computeOnClient(mc -> String.valueOf(ModUnderTest.staticCall("com.killer560.hub.roomsim.SimState",
                "mapCode")));
        println(name, String.format(Locale.US, "RESULT %s: floor %.1f s (%s), Quiz done at %s, answers %s, gap clears %d, rooms"
                        + " cleared during the Quiz %d, idle at the Quiz %d tick(s); map code %s", baseline ? "BASELINE" : "NEW",
                ticks / 20.0, stillBusy ? "still running at the limit" : "Auto Clear ended",
                quizDoneAt < 0 ? "never" : String.format(Locale.US, "%.1f s", quizDoneAt / 20.0), late, gaps, between, idle, code));
        if (lateTicks.size() != 3) {
            failures.add(lateTicks.size() + " of 3 Quiz answers (" + late + ")");
        }
        if (baseline) {
            return;
        }
        int bound = withMobs ? LATE_BOUND : WAIT_LATE_BOUND;
        for (int q = 0; q < lateTicks.size(); q++) {
            if (lateTicks.get(q) > bound) {
                failures.add("question " + (q + 1) + " answered " + lateTicks.get(q) + " tick(s) after it opened (bound " + bound + ")");
            }
        }
        if (withMobs) {
            if (gaps < 1 || between < 1) {
                failures.add("Auto Clear did not leave the Quiz to clear during a countdown (gap picks " + gaps
                        + ", sim room clears between Oruo's start and the last answer " + between + ")");
            }
            if (stillBusy) {
                failures.add("Auto Clear still running after " + minutes + " min");
            }
        } else {
            if (gaps != 0) {
                failures.add("with nothing to clear it still left the Quiz " + gaps + " time(s)");
            }
            if (idle < 100) {
                failures.add("it waited only " + idle + " tick(s) at the Quiz with nothing else to do (want the locks, 100+)");
            }
        }
    }

    /** Server: every mob (not armor stands, not players) discarded; {@code out} gets the count when done. */
    private static void removeMobs(Minecraft mc, java.util.concurrent.atomic.AtomicInteger out) {
        var server = mc.getSingleplayerServer();
        server.execute(() -> {
            int n = 0;
            for (var level : server.getAllLevels()) {
                List<net.minecraft.world.entity.Entity> list = new ArrayList<>();
                for (net.minecraft.world.entity.Entity e : level.getAllEntities()) {
                    if (e instanceof net.minecraft.world.entity.Mob) {
                        list.add(e);
                    }
                }
                for (net.minecraft.world.entity.Entity e : list) {
                    e.discard();
                    n++;
                }
            }
            out.set(n);
        });
    }

    /** An F7 with the Quiz pinned; its code, or null when the pin was dropped. Client thread. */
    private static String planFloor(String name) {
        Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
        Map<Integer, String> pins = new HashMap<>();
        pins.put(14, "Quiz");
        Object planned = ModUnderTest.staticCall(FLOOR_GEN, "plan", new Class<?>[]{floor.getClass(), int.class, int.class,
                Map.class}, new Object[]{floor, 2, 6, pins});
        if (planned == null) {
            return null;
        }
        @SuppressWarnings("unchecked")
        List<String> kept = (List<String>) ModUnderTest.call(planned, "keptPins", new Class<?>[0], new Object[0]);
        println(name, "planned an F7, pins kept: " + kept);
        if (kept == null || kept.isEmpty()) {
            return null;
        }
        return (String) ModUnderTest.call(planned, "code", new Class<?>[0], new Object[0]);
    }

    private static void println(String name, String line) {
        System.out.println("[" + name + "] " + line);
    }
}
