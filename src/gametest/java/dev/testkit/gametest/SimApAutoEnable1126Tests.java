package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Auto Enable On Dungeon Entry (autopilot-auto-enable-1126, killer560 2026-11-26: "also add a toggle to auto enable auto
 * pilot on entering a dungeon"). Each case builds a sim F7 (entering it is the dungeon entry) and never presses the
 * Dungeons Autopilot switch itself.
 * <ul>
 *   <li>{@code 1126-sim-autopilot-autoenable-on}: switch ON - the mod logs the Auto Enable line, Dungeons Autopilot is on
 *   and the Autopilot really acts (a planner decision line or a sim room cleared) within 60 s of GO.</li>
 *   <li>{@code 1126-sim-autopilot-autoenable-off}: switch OFF - Dungeons Autopilot stays off for 20 s after GO, no Auto
 *   Enable line and no planner decision.</li>
 *   <li>{@code 1126-sim-autopilot-autoenable-midoff}: switch ON, then he switches it off once it has acted - it stays off
 *   for the next 30 s (no second Auto Enable line, Auto Secret not running, no new decision).</li>
 * </ul>
 * ON and MIDOFF share one floor when both are selected.
 */
public class SimApAutoEnable1126Tests implements FabricClientGameTest {
    private static final String ON = "1126-sim-autopilot-autoenable-on";
    private static final String OFF = "1126-sim-autopilot-autoenable-off";
    private static final String MIDOFF = "1126-sim-autopilot-autoenable-midoff";
    private static final String DA = "com.killer560.hub.autosecret.DungeonsAutopilot";
    private static final String AS = "com.killer560.hub.autosecret.AutoSecretFeature";
    private static final String AS_CFG = "com.killer560.hub.autosecret.AutoSecretConfig";
    private static final String SIM_FLOOR = "com.killer560.hub.roomsim.SimMobFloor";
    private static final String ENABLE_LINE = "Auto Enable On Dungeon Entry: entered a dungeon - switching on";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank()) {
            return;
        }
        boolean on = !Scenario.skip(ON);
        boolean off = !Scenario.skip(OFF);
        boolean midoff = !Scenario.skip(MIDOFF);
        if (!on && !off && !midoff) {
            return;
        }
        if (!dev.testkit.gametest.mod.Mod.isCheat()) {
            for (String n : new String[]{ON, OFF, MIDOFF}) {
                if (!Scenario.skip(n)) Scenario.skipped(n, "Dungeons Autopilot is cheat build only");
            }
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (SimMapTests.copyRoomsForOthers() < 20) {
            for (String n : new String[]{ON, OFF, MIDOFF}) {
                if (!Scenario.skip(n)) Scenario.skipped(n, "needs real room captures and room database");
            }
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.RoomLibrary", "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.roomsim.RoomLibrary", "isReady"), 2400);
        List<String> all = new ArrayList<>();
        if (off) {
            floor(ctx, false, false, false, all);
        }
        if (on || midoff) {
            floor(ctx, on, midoff, true, all);
        }
        if (!all.isEmpty()) {
            throw new AssertionError(all.size() + " problem(s): " + String.join("; ", all));
        }
    }

    private static boolean daOn(ClientGameTestContext ctx) {
        return Boolean.TRUE.equals(ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(DA, "isOn")));
    }

    private static boolean acted(List<String> log) {
        return log.stream().anyMatch(l -> l.contains("[Autopilot] decide") || l.contains("[Sim] room cleared:"));
    }

    private static long count(List<String> log, String s) {
        return log.stream().filter(l -> l.contains(s)).count();
    }

    /** One floor with the switch {@code switchOn}; judges OFF (switch off), and ON / MIDOFF (switch on). */
    private static void floor(ClientGameTestContext ctx, boolean judgeOn, boolean judgeMid, boolean switchOn, List<String> all) {
        List<String> fOn = new ArrayList<>();
        List<String> fMid = new ArrayList<>();
        List<String> fOff = new ArrayList<>();
        boolean wasSet = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.call(ModUnderTest.config(AS_CFG), "isAutoEnable",
                new Class<?>[0], new Object[0]));
        try {
            ctx.runOnClient(mc -> {
                Object as = ModUnderTest.config(AS_CFG);
                ModUnderTest.set(as, "setAutoEnable", switchOn);
                ModUnderTest.set(as, "setBloodFirst", false);
                Object mode = ModUnderTest.enumValue(AS_CFG + "$Mode", "SOLO_300");
                ModUnderTest.call(as, "setMode", new Class<?>[]{mode.getClass()}, new Object[]{mode});
                Object rush = ModUnderTest.enumValue(AS_CFG + "$BloodRush", "NONE");
                ModUnderTest.call(as, "setBloodRush", new Class<?>[]{rush.getClass()}, new Object[]{rush});
                ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autoclear.AutoClearConfig"), "setEnabled", true);
            });
            long mark = LogTap.mark();
            List<String> problems = new ArrayList<>();
            SimMobFloorTests.buildMobFloor(ctx, problems, new ArrayList<>());
            if (ctx.computeOnClient(mc -> mc.level == null)) {
                (switchOn ? fOn : fOff).add("the floor did not load: " + problems);
                if (switchOn) fMid.add("the floor did not load: " + problems);
                return;
            }
            ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.roomsim.SimRun", "hasStarted"), 600);
            if (!switchOn) {
                boolean everOn = false;
                for (int t = 0; t < 400; t += 5) {
                    ctx.waitTicks(5);
                    everOn |= daOn(ctx);
                }
                List<String> log = LogTap.since(mark);
                say(OFF, "after GO: Dungeons Autopilot ever on " + everOn + ", Auto Enable lines " + count(log, ENABLE_LINE)
                        + ", acted " + acted(log));
                if (everOn) fOff.add("Dungeons Autopilot came on with the switch OFF");
                if (count(log, ENABLE_LINE) > 0) fOff.add("the Auto Enable line was logged with the switch OFF");
                if (log.stream().anyMatch(l -> l.contains("[Autopilot] decide"))) fOff.add("the planner decided with the switch OFF");
                return;
            }
            int actedAt = -1;
            for (int t = 0; t < 1200 && actedAt < 0; t += 5) {
                ctx.waitTicks(5);
                if (daOn(ctx) && acted(LogTap.since(mark))) {
                    actedAt = t;
                }
            }
            List<String> log = LogTap.since(mark);
            long lines = count(log, ENABLE_LINE);
            say(ON, "Auto Enable lines " + lines + ", Dungeons Autopilot on " + daOn(ctx) + ", acted at "
                    + (actedAt < 0 ? "never" : actedAt / 20.0 + " s after GO"));
            if (lines != 1) fOn.add("want one Auto Enable line on entry, saw " + lines);
            if (actedAt < 0) fOn.add("the Autopilot did not come on and act within 60 s of GO");
            if (actedAt < 0) {
                fMid.add("never acted, so nothing to switch off");
                return;
            }
            ctx.runOnClient(mc -> ModUnderTest.staticCall(DA, "setOn", new Class<?>[]{boolean.class}, new Object[]{false}));
            long offMark = LogTap.mark();
            boolean cameBack = false;
            for (int t = 0; t < 600; t += 5) {
                ctx.waitTicks(5);
                cameBack |= daOn(ctx) || Boolean.TRUE.equals(ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(AS, "isRunning")));
            }
            List<String> after = LogTap.since(offMark);
            say(MIDOFF, "switched off at " + actedAt / 20.0 + " s; in the next 30 s came back " + cameBack
                    + ", Auto Enable lines " + count(after, ENABLE_LINE) + ", decisions " + count(after, "[Autopilot] decide"));
            if (cameBack) fMid.add("Dungeons Autopilot or Auto Secret came back on after he switched it off");
            if (count(after, ENABLE_LINE) > 0) fMid.add("Auto Enable fired again in the same run");
            if (count(after, "[Autopilot] decide") > 0) fMid.add("the planner kept deciding after the switch-off");
        } catch (Throwable t) {
            (switchOn ? fOn : fOff).add("threw: " + t);
            if (switchOn) fMid.add("threw: " + t);
            t.printStackTrace(System.out);
        } finally {
            ctx.runOnClient(mc -> {
                try {
                    ModUnderTest.staticCall(DA, "setOn", new Class<?>[]{boolean.class}, new Object[]{false});
                    ModUnderTest.set(ModUnderTest.config(AS_CFG), "setAutoEnable", wasSet);
                } catch (Throwable ignored) {
                    // cleanup must not replace the verdict
                }
            });
            SimMobFloorTests.leave(ctx);
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_FLOOR, "setEnabled", new Class<?>[]{boolean.class},
                    new Object[]{false}));
            if (!switchOn) {
                verdict(OFF, fOff, all);
            } else {
                if (judgeOn) verdict(ON, fOn, all);
                if (judgeMid) verdict(MIDOFF, fMid, all);
            }
        }
    }

    private static void verdict(String name, List<String> f, List<String> all) {
        for (String s : f) {
            say(name, "FAIL: " + s);
            all.add(name + ": " + s);
        }
        say(name, f.isEmpty() ? "PASS" : "FAIL (" + f.size() + ")");
    }

    private static void say(String name, String line) {
        System.out.println("[" + name + "] " + line);
    }
}
