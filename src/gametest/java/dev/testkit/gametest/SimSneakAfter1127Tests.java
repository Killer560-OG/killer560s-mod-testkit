package dev.testkit.gametest;

import dev.testkit.harness.Report;
import dev.testkit.harness.SuiteVerdict;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * tl1127-sneak-after-impact (killer560 11-27: "look at how every single tick is spent. there are lots of dead ticks waiting
 * on thing"). A probe at the END of every tick dates each Interactive Map trip from its start ({@code ClearExecutor.lastPressTick})
 * to its first warp ({@code hopSendTicks}) and files it by what Auto Clear / Auto Secret did the tick before.
 * <ul>
 *   <li><b>1127-sim-ac-sneakafter</b>: 1124-sim-ac-presneak's fixed F7 floor and Hyperion loadout. A trip out of a room
 *   whose last mobs one Wither Impact in place killed stood 1-2 ticks before its first warp: the sneak went down only once
 *   the trip existed (primed only while a mob the blast missed was left). Pass: the floor cleared, at least
 *   {@link #MIN_TRIPS} trips, the room's last cast held the sneak ({@code AutoClearFeature.primedLastCastTicks} rose; origin
 *   has no counter) and at most {@link #MAX_UNHEARD} trip after a cast in place started with the sneak not yet heard.</li>
 *   <li><b>1127-sim-ap-decidetick</b>: Dungeons Autopilot on SimEntranceExit1126Tests' seeded floor for {@link #AP_TICKS}
 *   ticks after GO. A step that ended in Auto Secret's END handler set DECIDE and the decision waited for the next END, a dead
 *   tick before the trip (45 in his 11-27 session). Pass: at least {@link #MIN_AP_TRIPS} trips, decisions made in the END
 *   that ended the step ({@code AutoSecretFeature.sameTickDecides}, origin none) and at most {@link #MAX_DEFERRED} END that
 *   left DECIDE for the next.</li>
 * </ul>
 * On a legit jar each row passes by checking Auto Clear refuses to start.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimSneakAfter1127Tests implements FabricClientGameTest {

    private static final String AC_CASE = "1127-sim-ac-sneakafter";
    private static final String AP_CASE = "1127-sim-ap-decidetick";
    private static final String AUTO = "com.killer560.hub.autoclear.AutoClearFeature";
    private static final String AC_CONFIG = "com.killer560.hub.autoclear.AutoClearConfig";
    private static final String AS = "com.killer560.hub.autosecret.AutoSecretFeature";
    private static final String PILOT = "com.killer560.hub.autosecret.DungeonsAutopilot";
    private static final String EXEC = "com.killer560.hub.livemap.autoclear.ClearExecutor";
    private static final int MIN_TRIPS = 25;
    private static final int MAX_UNHEARD = 1;
    private static final int AP_TICKS = 20 * 90;
    private static final int MIN_AP_TRIPS = 4;
    private static final int MAX_DEFERRED = 1;

    private static boolean registered;
    private static boolean on;
    private static Field acPhase;
    private static Field acAction;
    private static Field asPhase;
    private static String prevKey = "";
    private static String startKey = "";
    private static String prevAs = "";
    private static long lastPress = Long.MIN_VALUE;
    private static long openStart = -1;
    private static boolean prevShift;
    private static int trips;
    private static long deadTicks;
    private static int deferred;
    private static int travels;
    private static final Map<String, int[]> BY = new TreeMap<>();
    /** Each waiting tick of a trip before its first warp, by what it came after and the executor's own wait state. */
    private static final Map<String, Integer> WHY = new TreeMap<>();
    private static Field waitReason;
    private static Field swappedOnTick;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        boolean ac = !Scenario.skip(AC_CASE);
        boolean ap = !Scenario.skip(AP_CASE);
        if (System.getProperty("testkit.scenario", "").isBlank() || !ac && !ap) {
            return;
        }
        ModUnderTest.require("killer560smod");
        if (!Ap3Crystal1109Cases.cheatBuild()) {
            boolean busy = ctx.computeOnClient(mc -> {
                ModUnderTest.staticCall(AUTO, "start");
                return (Boolean) ModUnderTest.staticCall(AUTO, "isBusy");
            });
            String why = busy ? "legit jar: Auto Clear started" : null;
            if (ac) {
                finish(AC_CASE, why, "legit jar: Auto Clear is cheat-only and did not start");
            }
            if (ap) {
                finish(AP_CASE, why, "legit jar: Auto Clear is cheat-only and did not start");
            }
            return;
        }
        LogTap.install();
        ctx.runOnClient(mc -> install());
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (SimMapTests.copyRoomsForOthers() < 20) {
            Scenario.skipped(AC_CASE, "needs real room captures and room database");
            Scenario.skipped(AP_CASE, "needs real room captures and room database");
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.RoomLibrary", "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.roomsim.RoomLibrary", "isReady"), 2400);
        ctx.runOnClient(mc -> {
            ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autopuzzles.AutoPuzzlesConfig"),
                    "setAutoPuzzlesMasterEnabled", false);
            Object map = ModUnderTest.config("com.killer560.hub.livemap.LiveMapConfig");
            ModUnderTest.set(map, "setEnabled", true);
            ModUnderTest.set(map, "setInteractiveMapEnabled", true);
        });
        if (ac) {
            try {
                acCase(ctx);
            } catch (Throwable t) {
                t.printStackTrace(System.out);
                finish(AC_CASE, "threw " + t, "");
            }
        }
        if (ap) {
            try {
                apCase(ctx);
            } catch (Throwable t) {
                t.printStackTrace(System.out);
                finish(AP_CASE, "threw " + t, "");
            }
        }
    }

    private static void acCase(ClientGameTestContext ctx) {
        String[][] props = {{"testkit.acsHype", "true"}, {"testkit.acsHypeWeapon", "true"}, {"testkit.acsMobs", "3"},
                {"testkit.acsMinutes", "4"}};
        String[] old = new String[props.length];
        for (int i = 0; i < props.length; i++) {
            old[i] = System.getProperty(props[i][0]);
            System.setProperty(props[i][0], props[i][1]);
        }
        int renderBefore = ctx.computeOnClient(mc -> mc.options.renderDistance().get());
        int primedBefore = counter(ctx, AUTO, "primedLastCastTicks");
        ctx.runOnClient(mc -> {
            mc.options.renderDistance().set(16);
            reset();
        });
        List<SimAutoClearSpeedTests.RoomTime> rooms = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        try {
            ctx.runOnClient(mc -> on = true);
            println(AC_CASE, "SUMMARY " + SimAutoClearSpeedTests.oneFloor(ctx, 1, SimAcPreSneakTests.CODE, rooms, problems));
        } finally {
            ctx.runOnClient(mc -> on = false);
            SimAutoClearSpeedTests.leave(ctx);
            ctx.runOnClient(mc -> mc.options.renderDistance().set(renderBefore));
            for (int i = 0; i < props.length; i++) {
                if (old[i] == null) {
                    System.clearProperty(props[i][0]);
                } else {
                    System.setProperty(props[i][0], old[i]);
                }
            }
        }
        int primedAfter = counter(ctx, AUTO, "primedLastCastTicks");
        String report = ctx.computeOnClient(mc -> report());
        int total = rooms.stream().mapToInt(SimAutoClearSpeedTests.RoomTime::ticks).sum();
        long notCleared = rooms.stream().filter(r -> !r.cleared()).count();
        if (rooms.size() < 10 || notCleared > 0) {
            problems.add(rooms.size() + " room line(s), " + notCleared + " not cleared - the floor was not cleared");
        }
        int t = ctx.computeOnClient(mc -> trips);
        if (t < MIN_TRIPS) {
            problems.add(t + " trip(s) sent a first warp, want at least " + MIN_TRIPS);
        }
        if (primedBefore < 0 || primedAfter - primedBefore < 3) {
            problems.add("the room's last cast in place held the sneak " + primedBefore + " -> " + primedAfter
                    + " tick(s), want at least 3 more");
        }
        int unheard = ctx.computeOnClient(mc -> BY.entrySet().stream()
                .filter(e -> e.getKey().startsWith("in place") && e.getKey().endsWith("heard=false"))
                .mapToInt(e -> e.getValue()[0]).sum());
        if (unheard > MAX_UNHEARD) {
            problems.add(unheard + " trip(s) after a cast in place started with the sneak not yet heard, want at most "
                    + MAX_UNHEARD);
        }
        String detail = report + "; room ticks " + total + " over " + rooms.size() + " rooms; last-cast primes "
                + primedBefore + " -> " + primedAfter;
        finish(AC_CASE, problems.isEmpty() ? null : String.join("; ", problems), detail);
    }

    private static void apCase(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            Object cfg = ModUnderTest.config(AC_CONFIG);
            ModUnderTest.set(cfg, "setEnabled", true);
            ModUnderTest.set(cfg, "setHyperionHops", true);
            Object w = ModUnderTest.enumValue(AC_CONFIG + "$Weapon", "HYPERION");
            ModUnderTest.call(cfg, "setWeapon", new Class<?>[]{w.getClass()}, new Object[]{w});
        });
        List<String> problems = new ArrayList<>();
        int sameBefore = counter(ctx, AS, "sameTickDecides");
        String detail;
        try {
            if (SimEntranceExit1126Tests.stage(ctx, SimEntranceExit1126Tests.SEED + 1, problems) == null) {
                finish(AP_CASE, String.join("; ", problems), "floor not staged");
                return;
            }
            ctx.runOnClient(mc -> ModUnderTest.staticCall(PILOT, "setOn", new Class<?>[]{boolean.class}, new Object[]{true}));
            ctx.waitTicks(40);
            ctx.runOnClient(mc -> {
                reset();
                on = true;
                SimEntranceExit1126Tests.setTicksLeft(1);
            });
            for (int i = 0; i < AP_TICKS / 20; i++) {
                ctx.waitTicks(20);
                if (!ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(AS, "isRunning")) && i > 5) {
                    break;
                }
            }
        } finally {
            ctx.runOnClient(mc -> {
                on = false;
                ModUnderTest.staticCall(PILOT, "setOn", new Class<?>[]{boolean.class}, new Object[]{false});
                ModUnderTest.staticCall(AUTO, "cancel");
                if ((Boolean) ModUnderTest.staticCall(AS, "isRunning")) {
                    ModUnderTest.staticCall(AS, "stop", new Class<?>[]{String.class, boolean.class},
                            new Object[]{"test over", true});
                }
                ModUnderTest.set(ModUnderTest.config(AC_CONFIG), "setEnabled", false);
            });
            SimEntranceExit1126Tests.leave(ctx);
        }
        int sameAfter = counter(ctx, AS, "sameTickDecides");
        detail = ctx.computeOnClient(mc -> report() + "; Auto Secret TRAVEL entries " + travels + ", END ticks that left DECIDE"
                + " for the next END " + deferred);
        detail += "; same-tick decisions " + sameBefore + " -> " + sameAfter;
        int tr = ctx.computeOnClient(mc -> travels);
        int df = ctx.computeOnClient(mc -> deferred);
        if (tr < MIN_AP_TRIPS) {
            problems.add(tr + " Auto Secret trip(s), want at least " + MIN_AP_TRIPS);
        }
        if (sameBefore < 0 || sameAfter - sameBefore < 3) {
            problems.add("decisions made in the END that ended the step " + sameBefore + " -> " + sameAfter + ", want 3 more");
        }
        if (df > MAX_DEFERRED) {
            problems.add(df + " END tick(s) left DECIDE for the next END, want at most " + MAX_DEFERRED);
        }
        finish(AP_CASE, problems.isEmpty() ? null : String.join("; ", problems), detail);
    }

    private static void reset() {
        BY.clear();
        WHY.clear();
        trips = 0;
        deadTicks = 0;
        deferred = 0;
        travels = 0;
        openStart = -1;
        lastPress = Long.MIN_VALUE;
        prevKey = "";
        prevAs = "";
    }

    private static String report() {
        StringBuilder b = new StringBuilder("trips ").append(trips).append(", ticks before first warps ").append(deadTicks)
                .append("; by what was done the tick before [trips, ticks, 2+]:");
        BY.entrySet().stream().sorted((x, y) -> y.getValue()[1] - x.getValue()[1]).limit(25).forEach(e -> b.append(" ")
                .append(e.getKey()).append(" [").append(e.getValue()[0]).append(", ").append(e.getValue()[1]).append(", ")
                .append(e.getValue()[2]).append("];"));
        b.append(" waiting ticks:");
        WHY.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(20)
                .forEach(e -> b.append(" [").append(e.getValue()).append("] ").append(e.getKey()).append(";"));
        return b.toString();
    }

    private static void install() {
        if (registered) {
            return;
        }
        try {
            Class<?> a = Class.forName(AUTO);
            acPhase = a.getDeclaredField("phase");
            acPhase.setAccessible(true);
            acAction = a.getDeclaredField("action");
            acAction.setAccessible(true);
            asPhase = Class.forName(AS).getDeclaredField("phase");
            asPhase.setAccessible(true);
            waitReason = Class.forName(EXEC).getDeclaredField("waitReason");
            waitReason.setAccessible(true);
            swappedOnTick = Class.forName(EXEC).getDeclaredField("swappedOnTick");
            swappedOnTick.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("probe: " + e);
        }
        registered = true;
        ClientTickEvents.END_CLIENT_TICK.register(SimSneakAfter1127Tests::probe);
    }

    /** What the tick's work was, by kind: a cast in place, a hop, a walk, an Auto Secret phase, else Auto Clear's phase. */
    private static String kind(String acp, String act, String asp) {
        if (act.contains("in place")) {
            return "in place";
        }
        if (act.contains("hop") || acp.equals("HOP")) {
            return "hop";
        }
        if (acp.equals("WALK")) {
            return "walk";
        }
        if (!asp.equals("IDLE") && !asp.equals("AUTO_CLEAR") && !asp.equals("TRAVEL")) {
            return "AS " + asp;
        }
        String what = act.replaceAll(" -> .*| \\(.*|[0-9.,-]+", "").trim();
        return acp + (what.isEmpty() ? "" : ":" + what) + (asp.equals("IDLE") ? "" : " in AS " + asp);
    }

    @SuppressWarnings("unchecked")
    private static void probe(Minecraft mc) {
        if (!on || mc.player == null) {
            return;
        }
        long tick;
        long press;
        List<Long> hops;
        boolean busy;
        String acp;
        String act;
        String asp;
        try {
            tick = (Long) ModUnderTest.staticCall(EXEC, "clientTick");
            press = (Long) ModUnderTest.staticCall(EXEC, "lastPressTick");
            hops = (List<Long>) ModUnderTest.staticCall(EXEC, "hopSendTicks");
            busy = (Boolean) ModUnderTest.staticCall(EXEC, "isBusy");
            acp = String.valueOf(acPhase.get(null));
            act = String.valueOf(acAction.get(null));
            asp = String.valueOf(asPhase.get(null));
        } catch (RuntimeException | IllegalAccessException e) {
            return;
        }
        if (asp.equals("DECIDE") && !prevAs.equals("DECIDE") && !prevAs.isEmpty()) {
            deferred++;
            WHY.merge("DECIDE left after " + prevAs + " (Auto Clear " + acp + ")", 1, Integer::sum);
        }
        if (asp.equals("TRAVEL") && !prevAs.equals("TRAVEL")) {
            travels++;
        }
        prevAs = asp;
        boolean heard = prevShift;
        prevShift = mc.player.getLastSentInput().shift();
        String before = prevKey;
        prevKey = kind(acp, act, asp);
        if (lastPress == Long.MIN_VALUE) {
            lastPress = press;   // a press from before the probe went on opens nothing
            return;
        }
        if (press != lastPress) {
            lastPress = press;
            openStart = press;
            startKey = before + " heard=" + heard;
        }
        if (openStart < 0) {
            return;
        }
        long first = -1;
        for (int i = hops.size() - 1; i >= 0 && hops.get(i) >= openStart; i--) {
            first = hops.get(i);
        }
        if (first >= 0) {
            int gap = (int) (first - openStart);
            int[] c = BY.computeIfAbsent(startKey, k -> new int[3]);
            c[0]++;
            c[1] += gap;
            c[2] += gap >= 2 ? 1 : 0;
            trips++;
            deadTicks += gap;
            openStart = -1;
        } else if (!busy || tick - openStart > 40) {
            openStart = -1;
        } else {
            String why;
            try {
                why = waitReason.get(null) + " swapT=" + (tick - (Long) swappedOnTick.get(null));
            } catch (IllegalAccessException | RuntimeException e) {
                why = "?";
            }
            WHY.merge(startKey + " t+" + Math.min(3, tick - openStart) + " heardNow=" + heard + " why=" + why, 1, Integer::sum);
        }
    }

    /** A static int counter of the mod's, or -1 when this jar has none (origin). */
    private static int counter(ClientGameTestContext ctx, String cls, String name) {
        return ctx.computeOnClient(mc -> {
            try {
                return (Integer) ModUnderTest.staticCall(cls, name);
            } catch (RuntimeException | Error e) {
                return -1;
            }
        });
    }

    private static void finish(String name, String failure, String detail) {
        boolean pass = failure == null;
        String text = pass ? detail : failure + " | " + detail;
        println(name, (pass ? "PASS" : "FAIL") + " - " + text);
        Report.caseStarted(name);
        Report.caseFinished(name, pass ? "PASS" : "FAIL", "n/a (singleplayer)", text, List.of(), List.of());
        SuiteVerdict.finished(name);
    }

    private static void println(String name, String s) {
        System.out.println("[" + name + "] " + s);
    }
}
