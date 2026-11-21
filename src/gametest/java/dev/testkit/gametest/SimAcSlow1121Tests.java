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
 *   <li>1121-sim-acslow-door: mean ticks from reaching a door (DOOR phase) to the next step (neither DOOR nor SETTLE).</li>
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
    private static final String NAME = "1121-sim-acslow";
    private static final String AS = "com.killer560.hub.autosecret.AutoSecretFeature";
    private static final String AC = "com.killer560.hub.autoclear.AutoClearFeature";
    private static final String SIM_RUN = "com.killer560.hub.roomsim.SimRun";
    private static final String SIM_FLOOR = "com.killer560.hub.roomsim.SimMobFloor";
    /** Bounds in ticks. Door: origin 6dc8207ee 92.3 mean (its first doors 34-35, then 99s stuck), branch 24.0 (main PC,
     *  2026-11-21). First mob and unseen are not proven yet (state file acslow-1121). */
    private static final double DOOR_MAX = Double.parseDouble(System.getProperty("testkit.acslowDoorMax", "30"));
    private static final double FIRST_MAX = Double.parseDouble(System.getProperty("testkit.acslowFirstMax", "60"));
    private static final int UNSEEN_MAX = Integer.getInteger("testkit.acslowUnseenMax", 150);
    private final List<String> all = new ArrayList<>();

    @Override
    public void runTest(ClientGameTestContext ctx) {
        boolean any = false;
        for (String n : new String[]{GREEN, DOOR, FIRST, UNSEEN}) {
            any |= !System.getProperty("testkit.scenario", "").isBlank() && !Scenario.skip(n);
        }
        if (!any) {
            return;
        }
        if (!dev.testkit.gametest.mod.Mod.isCheat()) {
            for (String n : new String[]{GREEN, DOOR, FIRST, UNSEEN}) {
                Scenario.skipped(n, "Auto Clear is cheat build only");
            }
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (SimMapTests.copyRoomsForOthers() < 20) {
            for (String n : new String[]{GREEN, DOOR, FIRST, UNSEEN}) {
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
                for (String n : new String[]{GREEN, DOOR, FIRST, UNSEEN}) {
                    row(n, false, "the floor did not load: " + problems);
                }
                return;
            }
            body(ctx);
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            for (String n : new String[]{GREEN, DOOR, FIRST, UNSEEN}) {
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
        String lastPhase = "";
        int clearPick = -1;
        List<Integer> firstKills = new ArrayList<>();
        int looking = 0;
        int spotTrips = 0;
        int kills = 0;
        int preGoGiveUps = 0;
        boolean bloodOpen = false;
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
            }
            String phase = ctx.computeOnClient(mc -> (String) ModUnderTest.staticCall(AS, "phaseName"));
            if (phase.equals("DOOR") && !lastPhase.equals("DOOR")) {
                doorStart = tick;
            } else if (doorStart >= 0 && !phase.equals("DOOR") && !phase.equals("SETTLE")) {
                doors.add(new int[]{doorStart, tick});
                doorStart = -1;
            }
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
        double doorMean = doors.stream().mapToInt(d -> d[1] - d[0]).average().orElse(-1);
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
                String.format(Locale.US, "mean %.1f tick(s) at a door until the next step (max %.0f); %s", doorMean, DOOR_MAX, did));
        row(FIRST, acted && firstKills.size() >= 3 && firstMean <= FIRST_MAX,
                String.format(Locale.US, "mean %.1f tick(s) from a CLEAR pick to its first kill over %d clear(s) (max %.0f); %s",
                        firstMean, firstKills.size(), FIRST_MAX, did));
        row(UNSEEN, acted && spotTrips >= 1 && looking <= UNSEEN_MAX,
                looking + " tick(s) looking for starred mobs (max " + UNSEEN_MAX + "), " + spotTrips + " spawn-spot trip(s); " + did);
    }

    private void row(String name, boolean pass, String detail) {
        if (!Scenario.named(name) && !System.getProperty("testkit.scenario", "").contains(NAME)) {
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
