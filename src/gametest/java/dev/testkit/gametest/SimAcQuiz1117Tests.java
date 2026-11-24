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
    /** acquiz2-1118: the same floor under Auto Secret (Auto Clear Rooms on, no routes), his real run's setup. */
    private static final String SECRET = "1118-sim-acquiz-secret";
    /** acplan-1121 (audit S3): the same with a long secret route in every secret room, so no route fits a countdown: the gap
     *  must still clear rooms that have routes (it stood at the Quiz instead). */
    private static final String ROUTES = "1121-sim-acquiz-routes";
    /** acplan2-1121 (killer560 11-21: "come back essentially the second that it's ready for me to come back"): the same floor
     *  with short secret routes; it may wait at the Quiz only when every round trip left is marked too long, and
     *  a started gap task is finished before it goes back (no late bound, no cut; acquizlate-1123). */
    private static final String RETURN = "1121-sim-acquiz-return";
    /** The same under the Party Secrets goal. */
    private static final String RETURN_PARTY = "1121-sim-acquiz-return-party";

    /** acquizlate2-1123: the two floors that failed on 11-23 replayed by map code (the sim cannot otherwise repeat a floor):
     *  PIN_PARTY ended in the Dragon room on a block with no warp chain out (Auto Secret stopped, 0 of 3 answers); PIN_RETURN
     *  stood 96 ticks at the Quiz before answer 3 with a route that failed and only too-long candidates left. */
    private static final String PIN_PARTY = "1123-sim-acquiz-party-pinned";
    private static final String PIN_RETURN = "1123-sim-acquiz-return-pinned";
    /** acquizlate3-1123: PIN_PARTY's floor, Auto Clear alone on its Dragon room from the stand its 11-23 hop into the pocket
     *  left from (-70,-24,-181). The pocket's block -63,-23,-175 (barrier blocks and walls, a one-block slit) has no warp, walk
     *  or warp chain out: Auto Clear must weigh it and never land there. The origin jar lands him in it. */
    private static final String POCKET = "1123-sim-ac-pocket";
    private static final java.util.Map<String, String> PINS = java.util.Map.of(
            PIN_PARTY, "MC2:CxcEUXVpeghFbnRyYW5jZQlTdXBlcnRhbGwFRmxhZ3MJTG9uZyBIYWxsDlJlZHN0b25lIENyeXB0BUZhaXJ5C0JsdWUgU2t1bGxzBUNyeXB0BUJsb29kBldpemFyZA1DcmVlcGVyIEJlYW1zBk11c2V1bQlPdmVyZ3Jvd24ITmV3IFRyYXAMR29sZGVuIE9hc2lzB1RvbWlva2EMUHVycGxlIEZsYWdzA0RpcA9TbWFsbCBXYXRlcmZhbGwGS25pZ2h0BkRyYWdvbg5SYXJlIE92ZXJncm93bgASAgAAAAACAAQAAAADAgADAgADAgAAAAAWAAEAAAAUAQASAgAAAAAAAAAAAAADAgADAgADAgAAAAAAAAAAAAEAAAASAgAAAAARAQAAAAADAgADAgADAgEAAAAEAQAEAQAEAQEAAAAAAAEAAAAAAAAAAAAAAAAAAAAAAAAEAQAEAQAEAQAQAgEAAAAOAAAAAAABAwAAAAALAwEAAAAEAQAEAQAEAQAAAAAAAAEAAAAAAAEAAAAAAAALAwAAAAEAAAAAAAAAAAAPAQEAAAANAAANAAANAAEAAAALAwAAAAAFAAEAAAAGAgAAAAAAAAANAAANAAANAAAAAAALAwAAAAAAAAAAAAEAAAAXAQAAAAANAAANAAANAAAAAAALAwEAAAAMAwAAAAAHAAEAAAAAAAEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAEAAAAVAAEAAAATAwAAAAAKAgMAAAAJAAAJAAAJAAEAAAAIAQ",
            PIN_RETURN, "MC2:CxcEUXVpeghFbnRyYW5jZQlXYXRlcmZhbGwHQnV0dG9ucwVGYWlyeQVDYWdlcwVXYXRlcgVNb3NzeQVTbG90aAVCbG9vZAhPbGQgVHJhcANQaXQLV2F0ZXIgQm9hcmQLUHJpc29uIENlbGwETWFnZQNFbmQFQWRtaW4GS25pZ2h0DFNtYWxsIFN0YWlycwZUZW1wbGUKQmxhY2sgRmxhZwRDZWxsBENhZ2UAFgEAAAAADQIAAAAADgABAAAAEAIBAAAAEQABAAAAEgIBAAAAAAABAAAAAAABAAAAAAAAAAAAAAAAAAAAAAABAAAACAMBAAAADAIADAIADAIADAIADAIADAIADAIAAAAAEwEACAMAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABAAAACAMBAAAACwIAAAAAAQIBAAAADwIAAAAAFQIBAAAAFAEACAMAAAAAAAAAAAAAAAAAAAAADwIAAAAAAAAAAAAAAAAACAMBAAAACQADAAAACgAAAAAADwIBAAAABAIABAIABAIACAMAAAAAAAAAAAAAAAAAAAAAAAAAAAAABAIABAIABAIACAMBAAAABwABAAAABgABAAAABQEBAAAABAIABAIABAIBAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABAAAAFwAAAAAAAgAEAAAAAwIAAwIAAwIAAwIAAwIAAwIAAwI");

    private static boolean isReturn(String name) {
        return name.equals(RETURN) || name.equals(RETURN_PARTY) || PINS.containsKey(name);
    }
    private static final Pattern GAP_START = Pattern.compile("\\[AutoSecret\\] Quiz: (-?\\d+) tick\\(s\\) until question \\d opens; clearing");
    private static final Pattern GAP_ROOM = Pattern.compile("clearing (.+?) meanwhile \\(");
    private static final String AS = "com.killer560.hub.autosecret.AutoSecretFeature";
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
    /** Idle ticks at the Quiz over all three countdowns with rooms left (his run, before the fix: 405). */
    private static final int SECRET_IDLE_BOUND = 120;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank()) {
            return;
        }
        boolean gap = !Scenario.skip(GAP);
        boolean wait = !Scenario.skip(WAIT);
        boolean secret = !Scenario.skip(SECRET);
        boolean routes = !Scenario.skip(ROUTES);
        boolean ret = !Scenario.skip(RETURN);
        boolean retParty = !Scenario.skip(RETURN_PARTY);
        boolean pinParty = !Scenario.skip(PIN_PARTY);
        boolean pinRet = !Scenario.skip(PIN_RETURN);
        boolean pocket = !Scenario.skip(POCKET);
        if (!gap && !wait && !secret && !routes && !ret && !retParty && !pinParty && !pinRet && !pocket) {
            return;
        }
        if (!dev.testkit.gametest.mod.Mod.isCheat()) {
            if (gap) Scenario.skipped(GAP, "Auto Clear is cheat build only");
            if (wait) Scenario.skipped(WAIT, "Auto Clear is cheat build only");
            if (secret) Scenario.skipped(SECRET, "Auto Clear is cheat build only");
            if (routes) Scenario.skipped(ROUTES, "Auto Clear is cheat build only");
            if (ret) Scenario.skipped(RETURN, "Auto Clear is cheat build only");
            if (retParty) Scenario.skipped(RETURN_PARTY, "Auto Clear is cheat build only");
            if (pinParty) Scenario.skipped(PIN_PARTY, "Auto Clear is cheat build only");
            if (pinRet) Scenario.skipped(PIN_RETURN, "Auto Clear is cheat build only");
            if (pocket) Scenario.skipped(POCKET, "Auto Clear is cheat build only");
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (SimMapTests.copyRoomsForOthers() < 20) {
            if (gap) Scenario.skipped(GAP, "needs real room captures and room database");
            if (wait) Scenario.skipped(WAIT, "needs real room captures and room database");
            if (secret) Scenario.skipped(SECRET, "needs real room captures and room database");
            if (routes) Scenario.skipped(ROUTES, "needs real room captures and room database");
            if (ret) Scenario.skipped(RETURN, "needs real room captures and room database");
            if (retParty) Scenario.skipped(RETURN_PARTY, "needs real room captures and room database");
            if (pinParty) Scenario.skipped(PIN_PARTY, "needs real room captures and room database");
            if (pinRet) Scenario.skipped(PIN_RETURN, "needs real room captures and room database");
            if (pocket) Scenario.skipped(POCKET, "needs real room captures and room database");
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
            if (secret) {
                one(ctx, SECRET, true, failures);
            }
            if (routes) {
                one(ctx, ROUTES, true, failures);
            }
            if (ret) {
                one(ctx, RETURN, true, failures);
            }
            if (retParty) {
                one(ctx, RETURN_PARTY, true, failures);
            }
            if (pinParty) {
                one(ctx, PIN_PARTY, true, failures);
            }
            if (pinRet) {
                one(ctx, PIN_RETURN, true, failures);
            }
            if (pocket) {
                one(ctx, POCKET, true, failures);
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
        String pin = PINS.get(name.equals(POCKET) ? PIN_PARTY : name);
        boolean pinned = pin != null && before.isBlank();
        if (pinned) {
            System.setProperty("testkit.mobfloorCode", pin);
            before = pin;
        }
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
            if (before.isBlank() || pinned) {
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
            if (name.equals(SECRET) || name.equals(ROUTES) || isReturn(name)) {
                ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autosecret.AutoSecretConfig"), "setAutoClearRooms", true);
            }
            if (isReturn(name)) {
                // The goal: Solo 300 or Party Secrets (the Mode setting since apmenu-1121, the Run Mode before it).
                Object as = ModUnderTest.config("com.killer560.hub.autosecret.AutoSecretConfig");
                boolean menu = java.util.Arrays.stream(as.getClass().getMethods()).anyMatch(m -> m.getName().equals("setMode"));
                boolean party = name.equals(RETURN_PARTY) || name.equals(PIN_PARTY);
                Object v = ModUnderTest.enumValue("com.killer560.hub.autosecret.AutoSecretConfig$" + (menu ? "Mode" : "RunMode"),
                        menu ? (party ? "PARTY_SECRETS" : "SOLO_300") : (party ? "PARTY" : "SOLO"));
                ModUnderTest.call(as, menu ? "setMode" : "setRunMode", new Class<?>[]{v.getClass()}, new Object[]{v});
                ModUnderTest.set(as, "setBloodFirst", false);
            }
        });
        SimAutoClearTests.setMode(ctx, "ANY_MOB_ROOM", "SPIRIT_SCEPTRE");
        if (name.equals(POCKET)) {
            pocket(ctx, failures);
            return;
        }
        if (!withMobs) {
            java.util.concurrent.atomic.AtomicInteger removed = new java.util.concurrent.atomic.AtomicInteger(-1);
            ctx.runOnClient(mc -> removeMobs(mc, removed));
            ctx.waitFor(mc -> removed.get() >= 0, 400);
            int gone = removed.get();
            ctx.waitTicks(20);
            println(name, "removed " + gone + " mob(s) on the server: nothing else to clear");
            // With no mobs no Wither Key drops, so a Quiz behind a wither door is never reached: start inside it (walking in
            // starts Oruo, as on Hypixel) and let Auto Clear take it from there.
            int[] area = ctx.computeOnClient(mc -> (int[]) ModUnderTest.staticCall("com.killer560.hub.roomsim.puzzles.SimQuizPuzzle",
                    "oruoArea"));
            if (area == null) {
                failures.add("the sim has no Quiz area on this floor");
                return;
            }
            // The Quiz's chunks are far from the spawn and not loaded yet (getBlockState reads air): go there first.
            SimMobFloorTests.teleport(ctx, (area[0] + area[2]) / 2 + 0.5, -37, (area[1] + area[3]) / 2 + 0.5);
            ctx.waitTicks(30);
            java.util.concurrent.atomic.AtomicReference<double[]> found = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicBoolean searched = new java.util.concurrent.atomic.AtomicBoolean();
            ctx.runOnClient(mc -> standIn(mc, (area[0] + area[2]) / 2, (area[1] + area[3]) / 2, found, searched));
            ctx.waitFor(mc -> searched.get(), 400);
            double[] spot = found.get();
            if (spot == null) {
                failures.add("no standable block in the Quiz around " + (area[0] + area[2]) / 2 + "," + (area[1] + area[3]) / 2);
                return;
            }
            SimMobFloorTests.teleport(ctx, spot[0], spot[1], spot[2]);
            ctx.waitTicks(20);
            println(name, String.format(Locale.US, "placed in the Quiz at %.1f %.1f %.1f", spot[0], spot[1], spot[2]));
        }
        boolean baseline;
        try {
            ctx.computeOnClient(mc -> ModUnderTest.staticCall(AUTO, "quizGapCount"));
            baseline = false;
        } catch (Throwable t) {
            baseline = true;
            println(name, "BASELINE jar (no Quiz gap hook): times only");
        }
        java.util.Set<String> routed = new java.util.HashSet<>();
        java.nio.file.Path routesFile = null;
        String routesBefore = null;
        if (name.equals(ROUTES) || isReturn(name)) {
            routesFile = ctx.computeOnClient(mc -> (java.nio.file.Path) ModUnderTest.staticCall(
                    "com.killer560.hub.autoroutes.RouteStore", "routesFile"));
            try {
                routesBefore = java.nio.file.Files.exists(routesFile) ? java.nio.file.Files.readString(routesFile) : null;
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
            Map<String, com.google.gson.JsonObject> long30 = new HashMap<>();
            for (SimAutopilotTests.Status s : ctx.computeOnClient(SimAutopilotTests::readStatus)) {
                if (s.unfound() <= 0 || SimAutopilotTests.strands(s.name())) {
                    continue;
                }
                com.google.gson.JsonObject route = SimAutopilotTests.routeFor(ctx, s);
                if (route == null) {
                    continue;
                }
                // ROUTES: 30 nodes, about 15 s of route work, more than any countdown (217 / 140 / 140 ticks). RETURN: 4, a
                // route that fits a countdown with its trips.
                com.google.gson.JsonArray nodes = route.getAsJsonArray("nodes");
                com.google.gson.JsonObject first = nodes.get(0).getAsJsonObject();
                for (int i = 1; i < (isReturn(name) ? 4 : 30); i++) {
                    com.google.gson.JsonObject copy = first.deepCopy();
                    copy.addProperty("start", false);
                    nodes.add(copy);
                }
                long30.put(s.name(), route);
                routed.add(s.name());
            }
            SimAutopilotTests.writeRoutes(ctx, long30);
            println(name, (isReturn(name) ? "short" : "long") + " routes in " + routed.size() + " room(s): " + routed);
        }
        try {
            body2(ctx, name, withMobs, failures, baseline, routed);
        } finally {
            if (routesFile != null) {
                java.nio.file.Path f = routesFile;
                String b = routesBefore;
                ctx.runOnClient(mc -> {
                    try {
                        if (b == null) {
                            java.nio.file.Files.deleteIfExists(f);
                        } else {
                            java.nio.file.Files.writeString(f, b);
                        }
                    } catch (java.io.IOException ignored) {
                        // cleanup must not replace the verdict
                    }
                    ModUnderTest.staticCall("com.killer560.hub.autoroutes.RouteStore", "reload");
                });
            }
        }
    }

    private static void body2(ClientGameTestContext ctx, String name, boolean withMobs, List<String> failures, boolean baseline,
                              java.util.Set<String> routed) {
        long mark = LogTap.mark();
        boolean secret = name.equals(SECRET) || name.equals(ROUTES) || isReturn(name);
        int routedGaps = 0;
        String driver = secret ? AS : AUTO;
        if (secret) {
            if (!Boolean.TRUE.equals(ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(AS, "startAutopilot")))) {
                failures.add("Auto Secret refused to start");
                return;
            }
        } else {
            ctx.runOnClient(mc -> ModUnderTest.staticCall(AUTO, "start"));
        }
        int minutes = Integer.getInteger("testkit.mobfloorMinutes", 12);
        int ticks = 0;
        int quizDoneAt = -1;
        long quizMark = -1;
        List<String> quizLog = null;   // the lines from Oruo's start to the Quiz done (LogTap keeps only the newest lines)
        boolean started = false;
        boolean stranded = false;
        for (; ticks < minutes * 1200; ticks += 5) {
            ctx.waitTicks(5);
            if (ctx.computeOnClient(mc -> mc.level == null || mc.player == null)) {
                failures.add("the world went away");
                return;
            }
            boolean busy = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(driver, secret ? "isRunning" : "isBusy"));
            started |= busy;
            if (name.equals(PIN_PARTY) && !stranded && inPocket(ctx)) {
                stranded = true;   // acquizlate3-1123: the 11-23 run ended a Dragon clear in the pocket no way leaves
                println(name, "in the Dragon pocket -63,-22,-175 at t=" + ticks / 20 + "s");
            }
            if (quizMark < 0 && ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(QUIZ_STATE, "started"))) {
                quizMark = LogTap.mark();
            }
            if (quizDoneAt < 0 && ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(QUIZ_STATE, "done"))) {
                quizDoneAt = ticks;
                quizLog = LogTap.since(quizMark);
                if (!withMobs || secret) {
                    break;   // the wait case (and Auto Secret's, which then goes on to the whole floor) is over once the Quiz is
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
        boolean stillBusy = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(driver, secret ? "isRunning" : "isBusy"));
        int idle = secret ? ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(AS, "quizIdleTicks"))
                : baseline ? -1 : ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(AUTO, "quizIdleTicks"));
        int[] idleBy = null;
        if (secret) {
            try {
                idleBy = ctx.computeOnClient(mc -> (int[]) ModUnderTest.staticCall(AS, "quizIdleByQuestion"));
            } catch (Throwable t) {
                println(name, "this jar has no quizIdleByQuestion: the total idle is judged");
            }
        }
        int gaps = secret ? 0 : baseline ? -1 : ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(AUTO, "quizGapCount"));
        if (secret) {
            ctx.runOnClient(mc -> ModUnderTest.staticCall(AS, "stop", new Class<?>[]{String.class, boolean.class},
                    new Object[]{"test over", true}));
        } else {
            ctx.runOnClient(mc -> ModUnderTest.staticCall(AUTO, "cancel"));
        }
        List<String> log = quizLog != null ? quizLog : LogTap.since(mark);
        int lastAnswer = -1;
        List<Integer> clears = new ArrayList<>();
        List<String> late = new ArrayList<>();
        List<Long> lateTicks = new ArrayList<>();
        for (int i = 0; i < log.size(); i++) {
            String l = log.get(i);
            if (l.contains("[Sim] room cleared:")) {
                clears.add(i);
            }
            if (l.contains("[AutoClear] Quiz countdown")) {
                println(name, "  " + l.replaceAll("^.*\\[AutoClear\\] ", ""));
            }
            if (secret && l.contains("[AutoSecret] Quiz:") && l.contains("meanwhile (")) {
                gaps++;
                println(name, "  " + l.substring(l.indexOf("[AutoSecret]")));
                Matcher g = GAP_ROOM.matcher(l);
                if (g.find() && routed.contains(g.group(1))) {
                    routedGaps++;
                }
            }
            if (isReturn(name) && l.contains("round trips (there + route + back)") && !l.contains("none fits")) {
                gaps++;   // a secret route taken in a countdown
                println(name, "  " + l.substring(l.indexOf("[AutoSecret]")));
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
            between += quizLog != null && (lastAnswer < 0 || c < lastAnswer) ? 1 : 0;
        }
        String code = ctx.computeOnClient(mc -> String.valueOf(ModUnderTest.staticCall("com.killer560.hub.roomsim.SimState",
                "mapCode")));
        println(name, String.format(Locale.US, "RESULT %s: floor %.1f s (%s), Quiz done at %s, answers %s, gap clears %d, rooms"
                        + " cleared during the Quiz %d, idle at the Quiz %d tick(s); map code %s", baseline ? "BASELINE" : "NEW",
                ticks / 20.0, stillBusy ? "still running at the limit" : "Auto Clear ended",
                quizDoneAt < 0 ? "never" : String.format(Locale.US, "%.1f s", quizDoneAt / 20.0), late, gaps, between, idle, code));
        if (idleBy != null) {
            println(name, "idle at the Quiz before answers 1/2/3: " + java.util.Arrays.toString(idleBy) + " tick(s)");
        }
        if (isReturn(name)) {
            try {
                println(name, "Quiz-gap clears cut for the trip back: "
                        + ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(AS, "quizGapCuts")));
            } catch (Throwable t) {
                println(name, "this jar has no quizGapCuts");
            }
        }
        if (lateTicks.size() != 3) {
            failures.add(lateTicks.size() + " of 3 Quiz answers (" + late + ")");
        }
        if (name.equals(PIN_PARTY) && stranded) {
            failures.add("landed in the Dragon pocket -63,-22,-175, which no warp or walk leaves");
        }
        if (isReturn(name)) {
            // acquizlate-1123 (killer560: "a second late rather than a second early ... no time limit"): a Quiz-gap task
            // that was started is finished: no cut (a), no gap task started once the countdown is over (b), and an answer
            // is never early, so the return follows the task's end (c). Judged on every jar (the origin jar cuts).
            for (String l : log) {
                if (l.contains("cutting the clear of")) {
                    failures.add("a Quiz-gap clear was cut for the trip back: " + l.substring(l.indexOf("[AutoSecret]")));
                }
                Matcher g = GAP_START.matcher(l);
                if (g.find() && Long.parseLong(g.group(1)) <= 0) {
                    failures.add("a gap task was started with the question already due: " + l);
                }
            }
            // Standing at the Quiz is judged by what it was weighing (acquizlate2-1123: the 96-tick stand of 11-23 had only
            // "(too long)" round trips left, nothing it could start, and flaked the old tick bound): it fails only when a
            // round trip that was NOT marked too long was passed over for "none fits, waiting at the Quiz".
            int waited = 0;
            for (String l : log) {
                int w = l.indexOf("-> none fits, waiting at the Quiz");
                int r = l.indexOf("round trips (there + route + back):");
                if (w < 0 || r < 0 || r > w) {
                    continue;
                }
                waited++;
                for (String cand : l.substring(r + 35, w).split(";")) {
                    if (!cand.isBlank() && !cand.contains("too long")) {
                        failures.add("it waited at the Quiz with a startable round trip left:" + cand + " (" + l.substring(l.indexOf("[AutoSecret]")) + ")");
                    }
                }
            }
            println(name, "waited at the Quiz " + waited + " time(s) with only too-long work left; idle ticks " + java.util.Arrays.toString(idleBy));
            if (gaps < 2 || between < 2) {
                failures.add("the countdowns were not filled with work (gap picks " + gaps + ", sim room clears " + between + ")");
            }
            return;
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
        if (secret) {
            // His 11-18 run stood 128, 138 and 139 ticks at the Quiz with mob rooms uncleared; the three countdowns are 217 + 140 + 140.
            if (gaps < 2 || between < 2) {
                failures.add("Auto Secret did not fill the countdowns with clears (gap picks " + gaps + ", sim room clears between"
                        + " Oruo's start and the last answer " + between + ")");
            }
            if (name.equals(ROUTES) && routedGaps < 1) {
                failures.add("no countdown was filled with the clear of a room that has a secret route (gap clears " + gaps
                        + ", of rooms with routes " + routedGaps + ")");
            }
            if (idle > SECRET_IDLE_BOUND) {
                failures.add("it stood " + idle + " tick(s) at the Quiz waiting with rooms left (bound " + SECRET_IDLE_BOUND + ")");
            }
        } else if (withMobs) {
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

    /** Server: the lowest standable block (solid, two air above) from y -60 up in the column nearest (x, z), within 3. */
    private static void standIn(Minecraft mc, int x, int z, java.util.concurrent.atomic.AtomicReference<double[]> out,
                                java.util.concurrent.atomic.AtomicBoolean done) {
        var server = mc.getSingleplayerServer();
        server.execute(() -> {
            try {
                var level = server.overworld();
                for (int r = 0; r <= 3 && out.get() == null; r++) {
                    for (int dx = -r; dx <= r && out.get() == null; dx++) {
                        for (int dz = -r; dz <= r && out.get() == null; dz++) {
                            for (int y = -60; y < 120; y++) {
                                net.minecraft.core.BlockPos b = new net.minecraft.core.BlockPos(x + dx, y, z + dz);
                                if (!level.getBlockState(b).getCollisionShape(level, b).isEmpty() && level.getBlockState(b.above()).isAir()
                                        && level.getBlockState(b.above(2)).isAir()) {
                                    out.set(new double[]{b.getX() + 0.5, y + 1, b.getZ() + 0.5});
                                    break;
                                }
                            }
                        }
                    }
                }
            } finally {
                if (out.get() == null) {
                    var level = server.overworld();
                    StringBuilder sb = new StringBuilder();
                    for (int y = 0; y < 130; y++) {
                        var st = level.getBlockState(new net.minecraft.core.BlockPos(x, y, z));
                        if (!st.isAir()) {
                            sb.append(y).append('=').append(st.getBlock().getDescriptionId()).append(' ');
                        }
                    }
                    System.out.println("[1117-sim-acquiz-wait] DIAG column " + x + "," + z + " loaded="
                            + level.hasChunkAt(new net.minecraft.core.BlockPos(x, 70, z)) + ": " + sb);
                }
                done.set(true);
            }
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

    /**
     * Feet in the Dragon pocket system as the sim builds this floor (testkit block dump, 11-23): everything south of the
     * barrier row at z -177 from x -71 to -54 (the zombie's cavity, the one-block slit row z -176, the pocket over
     * -64..-60 x -175..-173 and the corridor z -172), closed by walls; no warp, walk or warp chain leads from it back out.
     */
    private static boolean inPocket(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> mc.player != null && mc.player.getX() > -71.0 && mc.player.getX() < -54.0
                && mc.player.getZ() > -176.99 && mc.player.getZ() < -171.0 && mc.player.getY() > -25.0 && mc.player.getY() < -18.0);
    }

    private static void pocket(ClientGameTestContext ctx, List<String> failures) {
        SimMobFloorTests.teleport(ctx, -69.5, -23, -180.5);
        ctx.waitTicks(40);
        // The Hyperion alone (sim bats blast through walls, and whether one lands near enough is chance) with hops off: the
        // only way to a mob is an etherwarp onto a block within the blast. A second starred zombie in the cavity at
        // -62,-22,-173 (the pocket's north side) is more than a blast from every block outside the pocket system, so only its blocks reach it: the
        // origin jar's plan lands him in there (the 11-23 trip did, for the cavity zombie its bats missed).
        java.util.concurrent.atomic.AtomicBoolean given = new java.util.concurrent.atomic.AtomicBoolean();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            java.util.UUID uuid = mc.player.getUUID();
            server.execute(() -> {
                var inv = server.getPlayerList().getPlayer(uuid).getInventory();
                for (int i = 0; i < 36; i++) {
                    inv.setItem(i, net.minecraft.world.item.ItemStack.EMPTY);
                }
                String[] ids = {"ASPECT_OF_THE_VOID", "HYPERION"};
                for (int i = 0; i < ids.length; i++) {
                    inv.setItem(i, (net.minecraft.world.item.ItemStack) ModUnderTest.staticCall("com.killer560.hub.roomsim.SimItems",
                            "build", new Class<?>[]{String.class}, new Object[]{ids[i]}));
                }
                given.set(true);
            });
        });
        ctx.waitFor(mc -> given.get(), 200);
        ctx.waitTicks(10);
        ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AUTO_CONFIG), "setHyperionHops", false));
        SimAutoClearTests.setMode(ctx, "ANY_MOB_ROOM", "HYPERION");
        ctx.runOnClient(mc -> {
            Object k = ModUnderTest.enumValue("com.killer560.hub.roomsim.SimMobs$Kind", "ZOMBIE");
            ModUnderTest.staticCall("com.killer560.hub.roomsim.SimMobs", "spawnStarred", new Class<?>[]{Minecraft.class,
                    net.minecraft.core.BlockPos.class, k.getClass()}, new Object[]{mc, new net.minecraft.core.BlockPos(-62, -22, -173), k});
        });
        ctx.waitTicks(10);
        long mark = LogTap.mark();
        java.util.concurrent.atomic.AtomicReference<String> end = new java.util.concurrent.atomic.AtomicReference<>();
        ctx.runOnClient(mc -> ModUnderTest.staticCall(AUTO, "clearRoom",
                new Class<?>[]{String.class, Runnable.class, java.util.function.Consumer.class},
                new Object[]{"Dragon", (Runnable) () -> end.set("done"),
                        (java.util.function.Consumer<String>) why -> end.set("gave up: " + why)}));
        boolean in = false;
        int t = 0;
        for (; t < 1200 && end.get() == null && !in; t += 2) {
            ctx.waitTicks(2);
            in = inPocket(ctx);
        }
        net.minecraft.world.phys.Vec3 at = ctx.computeOnClient(mc -> mc.player.position());
        ctx.runOnClient(mc -> ModUnderTest.staticCall(AUTO, "cancel"));
        List<String> log = LogTap.since(mark);
        long acted = log.stream().filter(l -> l.contains("[Bat] decision for") || l.contains("[AutoClear] etherwarp toward")).count();
        boolean weighed = log.stream().anyMatch(l -> l.contains("[AutoClear] no way out of"));
        boolean landed = in;
        for (String l : log) {
            if (l.contains("way out of") || l.contains("no way back out")) {
                println(POCKET, "  " + l.substring(l.indexOf("[AutoClear]")));
            }
        }
        println(POCKET, String.format(Locale.US, "RESULT after %.1f s: %s, at %.2f %.2f %.2f, %d Auto Clear decision(s), pocket"
                + " weighed %s, landed in it %s", t / 20.0, end.get() == null ? (in ? "in the pocket" : "still running") : end.get(),
                at.x, at.y, at.z, acted, weighed, landed));
        if (acted == 0) {
            failures.add("Auto Clear never acted in Dragon (the case proves nothing)");
        }
        // acpocket-perf-1124: the way-out search runs a slice a tick; its log line carries the slice count and longest slice.
        java.util.regex.Matcher sl = java.util.regex.Pattern.compile("(\\d+) slice\\(s\\), longest ([\\d.]+) ms").matcher(String.join("\n", log));
        int judged = 0;
        while (sl.find()) {
            judged++;
        }
        double maxSlice = ctx.computeOnClient(mc -> (Double) ModUnderTest.staticCall(AUTO, "exitMaxSliceMs"));
        println(POCKET, String.format(Locale.US, "way-out searches judged in slices: %d, longest slice %.1f ms", judged, maxSlice));
        if (judged == 0) {
            failures.add("no way-out search ran in slices (the budget check proves nothing)");
        } else if (maxSlice > 8.0) {
            failures.add("a single way-out search slice took " + maxSlice + " ms (budget about 2 ms, allowed 8)");
        }
        if (landed) {
            failures.add("Auto Clear landed him in the Dragon pocket (south of the barrier row, x -71..-54), which no warp or walk leaves");
        } else if (!weighed) {
            failures.add("no block was found with no way out (no 'no way out of' line): the case proves nothing");
        }
    }

    private static void println(String name, String line) {
        System.out.println("[" + name + "] " + line);
    }
}
