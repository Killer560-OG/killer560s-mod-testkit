package dev.testkit.gametest;

import dev.testkit.harness.Report;
import dev.testkit.harness.SuiteVerdict;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Auto Clear / Dungeon Autopilot slowdowns (acslow-1121, killer560 2026-11-21: "slow to open doors slow to get the first mobs
 * in a room or if it cant see mobs it is slow to go to the areas to find them", and "if i que any of the auto clear style
 * things while in green make sure it is queued for after the door drops not just that it instantly blacklists rooms").
 * One Dungeon Autopilot run on 1118-sim-acbloodfirst's fixed F7 (same floor for a branch and its base), timed per tick:
 * <ul>
 *   <li>1121-sim-acslow-green-queue: started during a fresh 3 s countdown, the Autopilot only arms (not running, nothing
 *   planned or given up) and starts within a few ticks of GO.</li>
 *   <li>1121-sim-acslow-door: median ticks from reaching a door (DOOR phase) to the next step (neither DOOR nor SETTLE),
 *   over the doors that opened while he stood at them (a door that stays shut is not this gap). Bound 30 from measured
 *   runs: origin 34 (35, 34, 34, 34, 1, 34), the branch 24 (25, 24, 1, 24, 24, 25).</li>
 *   <li>1121-sim-acslow-firstmob: mean ticks from a CLEAR pick to that clear's first starred kill.</li>
 *   <li>1121-sim-acslow-unseen: ticks Auto Clear spent "looking for starred mobs" over the run, and at least one trip to a
 *   Blood Rush spawn spot.</li>
 * </ul>
 * Every row also needs the run to have cleared rooms (starred kills) and opened doors, so a run that did nothing fails.
 */
public class SimAcSlow1121Tests implements FabricClientGameTest {
    private static final String GREEN = "1121-sim-acslow-green-queue";
    private static final String DOOR = "1121-sim-acslow-door";
    private static final String FIRST = "1121-sim-acslow-firstmob";
    private static final String UNSEEN = "1121-sim-acslow-unseen";
    private static final String DOORCLICK = "1121-sim-acwaits-doorclick";
    private static final String AWAIT = "1121-sim-acwaits-awaitroute";
    private static final String WALK = "1121-sim-acwaits-walkstall";
    private static final String PUZZLE = "1121-sim-acwaits-pilotpuzzle";
    private static final String[] ALL = {GREEN, DOOR, FIRST, UNSEEN, DOORCLICK, AWAIT, WALK, PUZZLE};
    private static final String RE = "com.killer560.hub.autoroutes.RouteExecutor";
    private static final String NAME = "1121-sim-acslow";
    private static final String AS = "com.killer560.hub.autosecret.AutoSecretFeature";
    private static final String AC = "com.killer560.hub.autoclear.AutoClearFeature";
    private static final String SIM_RUN = "com.killer560.hub.roomsim.SimRun";
    private static final String SIM_FLOOR = "com.killer560.hub.roomsim.SimMobFloor";
    /** Bounds in ticks (measured 2026-11-21 on 26.3 cheat, branch vs origin; see the state file acslow-1121). */
    private static final double DOOR_MAX = Double.parseDouble(System.getProperty("testkit.acslowDoorMax", "30"));
    private static final double FIRST_MAX = Double.parseDouble(System.getProperty("testkit.acslowFirstMax", "60"));
    private static final int UNSEEN_MAX = Integer.getInteger("testkit.acslowUnseenMax", 150);
    private final List<String> all = new ArrayList<>();

    @Override
    public void runTest(ClientGameTestContext ctx) {
        boolean any = false;
        for (String n : ALL) {
            any |= !System.getProperty("testkit.scenario", "").isBlank() && !Scenario.skip(n);
        }
        if (!any) {
            return;
        }
        if (!dev.testkit.gametest.mod.Mod.isCheat()) {
            for (String n : ALL) {
                Scenario.skipped(n, "Auto Clear is cheat build only");
            }
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (SimMapTests.copyRoomsForOthers() < 20) {
            for (String n : ALL) {
                Scenario.skipped(n, "needs real room captures and room database");
            }
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.RoomLibrary", "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.roomsim.RoomLibrary", "isReady"), 2400);
        int renderBefore = ctx.computeOnClient(mc -> mc.options.renderDistance().get());
        ctx.runOnClient(mc -> mc.options.renderDistance().set(16));
        String before = System.getProperty("testkit.mobfloorCode", "");
        try {
            if (before.isBlank()) {
                System.setProperty("testkit.mobfloorCode", SimAcBloodFirst1118Tests.FIXED_FLOOR);
            }
            List<String> problems = new ArrayList<>();
            List<String> facts = new ArrayList<>();
            SimMobFloorTests.buildMobFloor(ctx, problems, facts);
            if (ctx.computeOnClient(mc -> mc.level == null)) {
                for (String n : ALL) {
                    row(n, false, "the floor did not load: " + problems);
                }
                return;
            }
            body(ctx);
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            for (String n : ALL) {
                row(n, false, "threw: " + t);
            }
        } finally {
            if (before.isBlank()) {
                System.clearProperty("testkit.mobfloorCode");
            }
            ctx.runOnClient(mc -> {
                try {
                    ModUnderTest.staticCall(AS, "stop", new Class<?>[]{String.class, boolean.class}, new Object[]{"test over", true});
                    ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autoclear.AutoClearConfig"), "setEnabled", false);
                } catch (Throwable ignored) {
                    // cleanup must not replace the verdict
                }
            });
            SimMobFloorTests.leave(ctx);
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_FLOOR, "setEnabled", new Class<?>[]{boolean.class},
                    new Object[]{false}));
            ctx.runOnClient(mc -> mc.options.renderDistance().set(renderBefore));
        }
        if (!all.isEmpty()) {
            throw new AssertionError(all.size() + " problem(s): " + String.join("; ", all));
        }
    }

    private void body(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            Object as = ModUnderTest.config("com.killer560.hub.autosecret.AutoSecretConfig");
            Object solo = ModUnderTest.enumValue("com.killer560.hub.autosecret.AutoSecretConfig$RunMode", "SOLO");
            ModUnderTest.call(as, "setRunMode", new Class<?>[]{solo.getClass()}, new Object[]{solo});
            ModUnderTest.set(as, "setChatFeedback", true);
            ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autoclear.AutoClearConfig"), "setEnabled", true);
        });
        SimAutoClearTests.setMode(ctx, "ANY_MOB_ROOM", "SPIRIT_SCEPTRE");
        // A fresh countdown: the run has not started (Hypixel's green room before Mort's line).
        ctx.runOnClient(mc -> {
            ModUnderTest.staticCall(SIM_RUN, "reset");
            ModUnderTest.staticCall(SIM_RUN, "begin", new Class<?>[]{Minecraft.class, BlockPos.class},
                    new Object[]{mc, ModUnderTest.staticCall("com.killer560.hub.roomsim.SimBuilder", "entranceDoor")});
        });
        ctx.waitTicks(2);
        long mark = LogTap.mark();
        boolean preRun = !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SIM_RUN, "hasStarted"));
        ctx.runOnClient(mc -> ModUnderTest.staticCall(AS, "startAutopilot"));
        boolean runningPreGo = false;
        int goTick = -1;
        int startTick = -1;
        int tick = 0;
        int minutes = Integer.getInteger("testkit.acslowMinutes", 6);
        List<int[]> doors = new ArrayList<>();   // {start, end}
        int doorStart = -1;
        boolean doorOpened = false;
        String lastPhase = "";
        int clearPick = -1;
        List<Integer> firstKills = new ArrayList<>();
        int looking = 0;
        int spotTrips = 0;
        int kills = 0;
        int preGoGiveUps = 0;
        boolean bloodOpen = false;
        List<Integer> doorClicks = new ArrayList<>();   // ticks from reaching a door to its click (acwaits-1121)
        int leftClicks = 0;
        boolean doorClicked = false;
        int clickAt = -1;
        for (; tick < minutes * 1200; tick++) {
            ctx.waitTicks(1);
            if (ctx.computeOnClient(mc -> mc.level == null || mc.player == null)) {
                break;
            }
            boolean started = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SIM_RUN, "hasStarted"));
            boolean running = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(AS, "isRunning"));
            if (!started && running) {
                runningPreGo = true;
            }
            if (started && goTick < 0) {
                goTick = tick;
            }
            if (running && started && startTick < 0) {
                startTick = tick;
            }
            long upTo = LogTap.mark();
            List<String> lines = LogTap.between(mark, upTo);
            mark = upTo;
            for (String l : lines) {
                if (goTick < 0 && (l.contains("Giving up on") || l.contains("[Autopilot] done CLEAR"))) {
                    preGoGiveUps++;
                }
                if (l.contains("[Autopilot] decide") && l.contains("pick CLEAR")) {
                    clearPick = tick;
                }
                if (l.contains("[AutoClear]") && l.contains(" is dead (")) {
                    kills++;
                    if (clearPick >= 0) {
                        firstKills.add(tick - clearPick);
                        clearPick = -1;
                    }
                }
                if (l.contains("[AutoClear] roam to a known mob") && l.contains("spawn spot")) {
                    spotTrips++;
                }
                if (l.contains("The BLOOD DOOR has been opened!")) {
                    bloodOpen = true;
                }
                if (l.contains("[AutoSecret] ") && l.contains("-clicked the ") && l.contains(" door at ")) {
                    leftClicks += l.contains("left-clicked") ? 1 : 0;
                    clickAt = tick;   // judged after this tick's phase is read: the click can come in the arrival tick
                }
                if (l.contains("[AutoSecret] the ") && l.contains(" is open (")) {
                    doorOpened = true;
                }
            }
            String phase = ctx.computeOnClient(mc -> (String) ModUnderTest.staticCall(AS, "phaseName"));
            if (phase.equals("DOOR") && !lastPhase.equals("DOOR")) {
                doorStart = tick;
                doorOpened = false;
                doorClicked = false;
            } else if (doorStart >= 0 && !phase.equals("DOOR") && !phase.equals("SETTLE")) {
                if (doorOpened) {
                    doors.add(new int[]{doorStart, tick});
                }
                doorStart = -1;
            }
            if (clickAt >= 0 && doorStart >= 0 && !doorClicked) {
                doorClicks.add(clickAt - doorStart);
                doorClicked = true;
            }
            clickAt = -1;
            lastPhase = phase;
            String status = ctx.computeOnClient(mc -> (String) ModUnderTest.staticCall(AC, "status"));
            if (status.endsWith("looking for starred mobs")) {
                looking++;
            }
            if (bloodOpen || startTick >= 0 && !running) {
                break;
            }
        }
        ctx.runOnClient(mc -> ModUnderTest.staticCall(AS, "stop", new Class<?>[]{String.class, boolean.class}, new Object[]{"test over", true}));
        int[] doorTicks = doors.stream().mapToInt(d -> d[1] - d[0]).sorted().toArray();
        double doorMean = doorTicks.length == 0 ? -1 : doorTicks[doorTicks.length / 2];
        double firstMean = firstKills.stream().mapToInt(Integer::intValue).average().orElse(-1);
        String did = String.format(Locale.US, "run %.1f s, %d starred kill(s), %d door(s), blood open %s", tick / 20.0, kills,
                doors.size(), bloodOpen);
        System.out.println("[" + NAME + "] RESULT: " + did + "; doors " + doors.stream().map(d -> String.valueOf(d[1] - d[0])).toList()
                + "; first kills " + firstKills + "; looking " + looking + "; spawn-spot trips " + spotTrips);
        boolean acted = kills >= 3 && doors.size() >= 2;
        int armWait = startTick < 0 || goTick < 0 ? -1 : startTick - goTick;
        row(GREEN, preRun && !runningPreGo && preGoGiveUps == 0 && armWait >= 0 && armWait <= 5 && acted,
                "pre-run at start " + preRun + ", running before GO " + runningPreGo + ", rooms given up / done before GO "
                        + preGoGiveUps + ", started " + armWait + " tick(s) after GO; " + did);
        row(DOOR, acted && doorMean >= 0 && doorMean <= DOOR_MAX,
                String.format(Locale.US, "median %.1f tick(s) at an opened door until the next step (max %.0f); %s", doorMean, DOOR_MAX, did));
        row(FIRST, acted && firstKills.size() >= 3 && firstMean <= FIRST_MAX,
                String.format(Locale.US, "mean %.1f tick(s) from a CLEAR pick to its first kill over %d clear(s) (max %.0f); %s",
                        firstMean, firstKills.size(), FIRST_MAX, did));
        row(UNSEEN, acted && spotTrips >= 1 && looking <= UNSEEN_MAX,
                looking + " tick(s) looking for starred mobs (max " + UNSEEN_MAX + "), " + spotTrips + " spawn-spot trip(s); " + did);
        int[] clickTicks = doorClicks.stream().mapToInt(Integer::intValue).sorted().toArray();
        int clickMedian = clickTicks.length == 0 ? -1 : clickTicks[clickTicks.length / 2];
        row(DOORCLICK, acted && clickTicks.length >= 2 && clickMedian >= 0 && clickMedian <= 2 && leftClicks >= 1,
                "median " + clickMedian + " tick(s) from reaching a door to its click (max 2) over " + doorClicks
                        + ", " + leftClicks + " left click(s); " + did);
        waitCases(ctx);
    }

    /**
     * acwaits-1121's three AutoSecretFeature waits, driven straight on its state after the run (Auto Secret stopped):
     * a route that starts and stops on its own tick, a path-less route waiting for a walk, a puzzle the map shows finished.
     */
    private void waitCases(ClientGameTestContext ctx) {
        if (Scenario.named(AWAIT)) {
            String r = ctx.computeOnClient(mc -> {
                try {
                    Class<?> store = Class.forName("com.killer560.hub.autoroutes.RouteStore");
                    com.google.gson.JsonObject node = new com.google.gson.JsonObject();
                    node.addProperty("type", "USE_ITEM");
                    node.addProperty("x", 0.5);
                    node.addProperty("y", 70);
                    node.addProperty("z", 0.5);
                    node.addProperty("item", "BAT_WAND");
                    node.addProperty("start", true);
                    com.google.gson.JsonArray nodes = new com.google.gson.JsonArray();
                    nodes.add(node);
                    com.google.gson.JsonObject json = new com.google.gson.JsonObject();
                    json.add("nodes", nodes);
                    Object route = store.getMethod("routeFromJson", String.class, com.google.gson.JsonObject.class)
                            .invoke(null, "AcWaits Test", json);
                    Class<?> frameC = Class.forName("com.killer560.hub.autoroutes.RouteCoords$Frame");
                    Object frame = frameC.getConstructor(String.class, int.class, int.class, int.class)
                            .newInstance("AcWaits Test", 0, 0, 0);
                    Object first = ((java.util.List<?>) route.getClass().getMethod("nodes").invoke(route)).get(0);
                    Class<?> nodeC = Class.forName("com.killer560.hub.autoroutes.RouteNode");
                    Integer starts = (Integer) getStatic(RE, "starts");
                    setStatic(AS, "target", "AcWaits Test");
                    setStatic(AS, "routeCompletionsSeen", ModUnderTest.staticCall(RE, "completions"));
                    if (starts != null) {
                        setStatic(AS, "routeStartsSeen", starts);
                    }
                    java.lang.reflect.Method start = Class.forName(RE).getDeclaredMethod("start", route.getClass(), frameC, nodeC);
                    start.setAccessible(true);
                    return "started " + start.invoke(null, route, frame, first);
                } catch (Throwable t) {
                    return "setup threw " + t;
                }
            });
            ctx.waitTicks(4);
            String why = ctx.computeOnClient(mc -> String.valueOf(ModUnderTest.staticCall(RE, "stopReason")));
            boolean running = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(RE, "isRunning"));
            int ticks = ctx.computeOnClient(mc -> drive(AS, "AWAIT_ROUTE", "tickAwaitRoute", 60));
            String after = ctx.computeOnClient(mc -> String.valueOf(getStatic(AS, "phase")));
            boolean stoppedAsSetUp = !running && why.contains("not in the hotbar");
            row(AWAIT, stoppedAsSetUp && ticks >= 1 && ticks <= 2,
                    "a start node that stopped on its own tick (" + r + ", stop reason " + why + ") left AWAIT_ROUTE after "
                            + ticks + " tick(s) (max 2, was 41) into " + after);
        }
        if (Scenario.named(WALK)) {
            int ticks = ctx.computeOnClient(mc -> {
                try {
                    Class<?> store = Class.forName("com.killer560.hub.autoroutes.RouteStore");
                    com.google.gson.JsonArray nodes = new com.google.gson.JsonArray();
                    for (int i = 0; i < 2; i++) {
                        com.google.gson.JsonObject node = new com.google.gson.JsonObject();
                        node.addProperty("type", "ETHERWARP");
                        node.addProperty("x", 0.5 + 20 * i);
                        node.addProperty("y", 70);
                        node.addProperty("z", 0.5);
                        nodes.add(node);
                    }
                    com.google.gson.JsonObject json = new com.google.gson.JsonObject();
                    json.add("nodes", nodes);
                    Object route = store.getMethod("routeFromJson", String.class, com.google.gson.JsonObject.class)
                            .invoke(null, "AcWaits Walk", json);
                    Object next = ((java.util.List<?>) route.getClass().getMethod("nodes").invoke(route)).get(1);
                    // Between nodes of a path-less route, its next node away from him: what Pipes stood in for 112 ticks.
                    setStatic(RE, "route", route);
                    setStatic(RE, "step", null);
                    setStatic(RE, "plannedNext", next);
                    setStatic(RE, "plannedInPlace", false);
                    setStatic(RE, "running", true);
                    setStatic(AS, "target", "AcWaits Walk");
                    setStatic(AS, "routeActions", ModUnderTest.staticCall(RE, "actionsBegun"));
                    setStatic(AS, "routeIdleTicks", 0);
                    return drive(AS, "ROUTE", "tickRoute", 60);
                } catch (Throwable t) {
                    t.printStackTrace(System.out);
                    return -2;
                } finally {
                    try {
                        ModUnderTest.staticCall(RE, "stop", new Class<?>[]{String.class}, new Object[]{"test over"});
                    } catch (Throwable ignored) {
                        // cleanup
                    }
                }
            });
            boolean still = ctx.computeOnClient(mc -> mc.player.onGround()
                    && mc.player.getDeltaMovement().horizontalDistanceSqr() < 1.0E-4);
            row(WALK, still && ticks >= 1 && ticks <= 12,
                    "a path-less route whose next node needs a walk, him still on the ground (" + still + "), left ROUTE after "
                            + ticks + " tick(s) (max 12, was the 400-tick stall)");
        }
        if (Scenario.named(PUZZLE)) {
            String res = ctx.computeOnClient(mc -> {
                try {
                    String room = null;
                    for (Object r : (Iterable<?>) ModUnderTest.staticCall("com.killer560.hub.livemap.RoomStatus", "rooms")) {
                        java.lang.reflect.Method cleared = r.getClass().getMethod("cleared");
                        cleared.setAccessible(true);
                        if ((Boolean) cleared.invoke(r)) {
                            java.lang.reflect.Method name = r.getClass().getMethod("name");
                            name.setAccessible(true);
                            room = (String) name.invoke(r);
                            break;
                        }
                    }
                    if (room == null) {
                        return "no cleared room on the map";
                    }
                    setStatic(AS, "target", room);
                    setStatic(AS, "pilot", true);
                    setStatic(AS, "puzzleSince", ModUnderTest.staticCall("com.killer560.hub.autopuzzles.AutoPuzzleActivity", "clock"));
                    int t = drive(AS, "PUZZLE_WAIT", "tickPuzzleWait", 3);
                    return room + "|" + t + "|" + getStatic(AS, "phase");
                } catch (Throwable t) {
                    return "threw " + t;
                } finally {
                    try {
                        setStatic(AS, "pilot", false);
                        setStatic(AS, "phase", phaseConst(AS, "IDLE"));
                    } catch (Throwable ignored) {
                        // cleanup
                    }
                }
            });
            row(PUZZLE, res.endsWith("|1|DECIDE"), "a puzzle the map shows finished, under the Autopilot (room|ticks|next phase): "
                    + res + " (want DECIDE on the first tick, was SETTLE)");
        }
    }

    /** Puts Auto Secret in {@code phase} and calls its private {@code tick} until the phase changes: the ticks it took, or max + 1. */
    private static int drive(String cls, String phase, String tick, int max) {
        try {
            setStatic(cls, "phase", phaseConst(cls, phase));
            java.lang.reflect.Method m = Class.forName(cls).getDeclaredMethod(tick);
            m.setAccessible(true);
            for (int i = 1; i <= max; i++) {
                setStatic(cls, "phaseTicks", i);
                m.invoke(null);
                if (!phase.equals(String.valueOf(getStatic(cls, "phase")))) {
                    return i;
                }
            }
            return max + 1;
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            return -1;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object phaseConst(String cls, String name) throws ClassNotFoundException {
        return Enum.valueOf((Class) Class.forName(cls + "$Phase"), name);
    }

    /** A private static field's value, or null when the field does not exist (an older jar). */
    private static Object getStatic(String cls, String field) {
        try {
            java.lang.reflect.Field f = Class.forName(cls).getDeclaredField(field);
            f.setAccessible(true);
            return f.get(null);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static void setStatic(String cls, String field, Object value) throws ReflectiveOperationException {
        java.lang.reflect.Field f = Class.forName(cls).getDeclaredField(field);
        f.setAccessible(true);
        f.set(null, value);
    }

    private void row(String name, boolean pass, String detail) {
        if (!Scenario.named(name)) {
            return;
        }
        if (java.util.List.of(FIRST, UNSEEN).contains(name)) {
            Scenario.skipped(name, "not provable on this sim floor yet (WORKQUEUE acgaps2-unprovable): " + detail);
            return;
        }
        System.out.println("[" + name + "] " + (pass ? "PASS" : "FAIL") + " - " + detail);
        Report.caseStarted(name);
        Report.caseFinished(name, pass ? "PASS" : "FAIL", "n/a (singleplayer)", detail, List.of(), List.of());
        SuiteVerdict.finished(name);
        if (!pass) {
            all.add(name + ": " + detail);
        }
    }
}
