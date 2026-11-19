package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Dungeon Autopilot's Blood First (acbloodfirst-1118, killer560 2026-11-18: "for auto clear i have it to blood first yet it
 * keeps doing rooms that arent blood rush stuff once it clears the room it doesnt go to gate instead it does another room
 * then goes back to gate"; and "if it is on blood rush first that should ignore quiz until after blood rush same with things
 * like ice fill").
 * <p>His 11-18 runs: the key-room clear picks up a Wither Key ("has obtained Wither Key!") and the next decision reads the
 * team's count from the sidebar's Keys line, which Hypixel redraws about once a second, 0.05-1.0 s after the chat line. It
 * read 0, so Blood First said "the next door wants a wither key" and the run went to a puzzle (Ice Path 14 s, Tic Tac Toe 11 s)
 * or another room before the door. The sim has no Keys line (his 10-04 wish), so this case draws one the way Hypixel does:
 * the sim server's own key count, redrawn every 20 ticks.
 * <ul>
 *   <li>1118-sim-acbloodfirst: a sim F7 with live mobs, keys and doors, the Quiz and Ice Fill pinned on it, run by Dungeon
 *   Autopilot (Solo, Blood First, puzzles, Quiz First, Ice Fill First). Until the blood door opens: the decision after every
 *   Wither Key pickup goes to a door (or a key, or the next blood rush room), never elsewhere; no puzzle is picked and the
 *   Quiz / Ice Fill are never started. After: both are solved. The run must have picked keys up and opened doors.</li>
 * </ul>
 */
public class SimAcBloodFirst1118Tests implements FabricClientGameTest {
    private static final String NAME = "1118-sim-acbloodfirst";
    /** Path Mines > Quad Lava > Duncan > Fairy > Flags > Granite > Slabs > Blood; the Quiz and Ice Fill off it. */
    private static final String FIXED_FLOOR = "MC2:CxUEUXVpeghJY2UgRmlsbAhFbnRyYW5jZQVNaW5lcwlRdWFkIExhdmEGRHVuY2FuBUZhaXJ5BUZsYWdzB0dyYW5pdGUFU2xhYnMFQmxvb2"
            + "QER29sZAZHcmF2ZWwIT2xkIFRyYXANVGVsZXBvcnQgTWF6ZQdCdXR0b25zCU92ZXJncm93bgdIYWxsd2F5DE11bHRpY29sb3JlZAxUaHJlZSBG"
            + "bG9vcnMKS2luZyBNaWRhcwAIAAAIAAAIAAEAAAAHAQEAAAAGAAAAAAAMAQAMAQAMAQAIAAAIAAAIAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAEAAA"
            + "AIAAAIAAAIAAEAAAAPAwAAAAAFAgEAAAAEAwAEAwAEAwEAAAAAAAEAAAAAAAAAAAAAAAAAAAAAAAAEAwAEAwAEAwAJAwAAAAANAAAAAAABAwAA"
            + "AAACAAEAAAAEAwAEAwAEAwEAAAAAAAANAAAAAAEAAAAAAAAAAAAAAAEAAAAAAAQAAAAKAwAAAAANAAAAAAAQAAAQAAAQAAAAAAAOAwAAAAADAw"
            + "MAAAAAAAANAAAAAAAQAAAQAAAQAAAAAAAAAAAAAAAAAAALAQAAAAANAAEAAAAQAAAQAAAQAAEAAAARAwEAAAAUAQAAAAAAAAAAAAAAAAAAAAAA"
            + "AAAAAAAAAAEAAAAAAAAAAAAVAwEAAAATAQEAAAASAQASAQASAQASAQASAQASAQASAQ";
    private static final String AS = "com.killer560.hub.autosecret.AutoSecretFeature";
    private static final String AS_CONFIG = "com.killer560.hub.autosecret.AutoSecretConfig";
    private static final String PUZ_CONFIG = "com.killer560.hub.autopuzzles.AutoPuzzlesConfig";
    private static final String QUIZ_STATE = "com.killer560.hub.puzzlesolvers.QuizState";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String SIM_FLOOR = "com.killer560.hub.roomsim.SimMobFloor";
    private static final String KEYS_HOLDER = "§k§r";
    private static final String KEYS_TEAM = "k560keys";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank() || Scenario.skip(NAME)) {
            return;
        }
        if (!dev.testkit.gametest.mod.Mod.isCheat()) {
            Scenario.skipped(NAME, "Auto Clear is cheat build only");
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (SimMapTests.copyRoomsForOthers() < 20) {
            Scenario.skipped(NAME, "needs real room captures and room database");
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.RoomLibrary", "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.roomsim.RoomLibrary", "isReady"), 2400);
        int renderBefore = ctx.computeOnClient(mc -> mc.options.renderDistance().get());
        ctx.runOnClient(mc -> mc.options.renderDistance().set(16));
        List<String> failures = new ArrayList<>();
        String before = System.getProperty("testkit.mobfloorCode", "");
        try {
            if (before.isBlank() && !Boolean.getBoolean("testkit.acbf1118Random")) {
                // One F7 for every jar, so a branch and its base are timed on the same floor.
                System.setProperty("testkit.mobfloorCode", FIXED_FLOOR);
            } else if (before.isBlank()) {
                String code = null;
                for (int i = 0; i < 12 && code == null; i++) {
                    final int n = i;
                    code = ctx.computeOnClient(mc -> planFloor(n));
                }
                if (code == null) {
                    failures.add("no F7 with the Quiz and Ice Fill could be planned");
                    return;
                }
                System.setProperty("testkit.mobfloorCode", code);
            }
            List<String> problems = new ArrayList<>();
            List<String> facts = new ArrayList<>();
            SimMobFloorTests.buildMobFloor(ctx, problems, facts);
            for (String f : facts) {
                println("FACT " + f);
            }
            if (ctx.computeOnClient(mc -> mc.level == null)) {
                failures.add("the floor did not load: " + problems);
                return;
            }
            body(ctx, failures);
        } catch (Throwable t) {
            failures.add("threw: " + t);
            t.printStackTrace(System.out);
        } finally {
            if (before.isBlank()) {
                System.clearProperty("testkit.mobfloorCode");
            }
            ctx.runOnClient(mc -> {
                try {
                    ModUnderTest.staticCall(AS, "stop", new Class<?>[]{String.class, boolean.class}, new Object[]{"test over", true});
                } catch (Throwable ignored) {
                    // cleanup must not replace the verdict
                }
                try {
                    Object auto = ModUnderTest.config(PUZ_CONFIG);
                    ModUnderTest.set(auto, "setAutoQuizEnabled", false);
                    ModUnderTest.set(auto, "setAutoIceFillEnabled", false);
                    ModUnderTest.set(auto, "setAutoPuzzlesMasterEnabled", false);
                    ModUnderTest.set(ModUnderTest.config(AS_CONFIG), "setBloodFirst", false);
                    ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autoclear.AutoClearConfig"), "setEnabled", false);
                } catch (Throwable ignored) {
                    // cleanup must not replace the verdict
                }
            });
            SimMobFloorTests.leave(ctx);
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_FLOOR, "setEnabled", new Class<?>[]{boolean.class},
                    new Object[]{false}));
            ctx.runOnClient(mc -> mc.options.renderDistance().set(renderBefore));
            for (String f : failures) {
                println("FAIL: " + f);
            }
            println(failures.isEmpty() ? "PASS" : "FAIL (" + failures.size() + ")");
        }
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " problem(s): " + String.join("; ", failures));
        }
    }

    private static void body(ClientGameTestContext ctx, List<String> failures) {
        ctx.runOnClient(mc -> {
            Object auto = ModUnderTest.config(PUZ_CONFIG);
            ModUnderTest.set(auto, "setAutoPuzzlesMasterEnabled", true);
            ModUnderTest.set(auto, "setEtherwarpReposition", true);
            ModUnderTest.set(auto, "setAutoPuzzlePathingEnabled", true);
            ModUnderTest.set(auto, "setAutoQuizEnabled", true);
            ModUnderTest.set(auto, "setAutoIceFillEnabled", true);
            ModUnderTest.set(ModUnderTest.config("com.killer560.hub.puzzlesolvers.QuizSolverConfig"), "setEnabled", true);
            Object as = ModUnderTest.config(AS_CONFIG);
            Object solo = ModUnderTest.enumValue(AS_CONFIG + "$RunMode", "SOLO");
            ModUnderTest.call(as, "setRunMode", new Class<?>[]{solo.getClass()}, new Object[]{solo});
            ModUnderTest.set(as, "setBloodFirst", true);
            ModUnderTest.set(as, "setDoPuzzles", true);
            ModUnderTest.set(as, "setQuizFirst", true);
            ModUnderTest.set(as, "setIceFillFirst", true);
            ModUnderTest.set(as, "setChatFeedback", true);
            ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autoclear.AutoClearConfig"), "setEnabled", true);
        });
        SimAutoClearTests.setMode(ctx, "ANY_MOB_ROOM", "SPIRIT_SCEPTRE");
        ctx.runOnClient(mc -> drawKeys(mc.getSingleplayerServer()));
        ctx.waitTicks(25);
        long mark = LogTap.mark();
        Boolean started = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(AS, "startAutopilot"));
        if (!Boolean.TRUE.equals(started)) {
            failures.add("Dungeon Autopilot refused to start");
            return;
        }
        int minutes = Integer.getInteger("testkit.mobfloorMinutes", 12);
        int ticks = 0;
        boolean quizDone = false;
        boolean iceDone = false;
        List<String> all = new ArrayList<>();
        int bloodTick = -1;
        for (; ticks < minutes * 1200; ticks += 5) {
            ctx.waitTicks(5);
            if (ctx.computeOnClient(mc -> mc.level == null || mc.player == null)) {
                failures.add("the world went away");
                return;
            }
            if (ticks % 20 == 0) {
                // Hypixel's sidebar cadence: the Keys line is redrawn about once a second, after the chat line.
                ctx.runOnClient(mc -> drawKeys(mc.getSingleplayerServer()));
            }
            quizDone = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(QUIZ_STATE, "done"));
            long upTo = LogTap.mark();
            all.addAll(LogTap.between(mark, upTo));
            mark = upTo;
            iceDone = all.stream().anyMatch(l -> l.contains("[Autopilot] done PUZZLE Ice Fill")
                    || l.contains("PUZZLE SOLVED!") && l.contains("Ice Fill"));
            if (bloodTick < 0 && all.stream().anyMatch(l -> l.contains("The BLOOD DOOR has been opened!"))) {
                bloodTick = ticks;
            }
            if (quizDone && iceDone && bloodTick >= 0) {
                break;
            }
            if (!(Boolean) ctx.computeOnClient(mc -> ModUnderTest.staticCall(AS, "isRunning"))) {
                break;
            }
        }
        ctx.runOnClient(mc -> ModUnderTest.staticCall(AS, "stop", new Class<?>[]{String.class, boolean.class}, new Object[]{"test over", true}));
        all.addAll(LogTap.since(mark));
        List<String> log = all;
        int bloodOpen = -1;
        int pickups = 0;
        int doorPicks = 0;
        List<String> order = new ArrayList<>();
        for (int i = 0; i < log.size(); i++) {
            String l = log.get(i);
            if (bloodOpen < 0 && (l.contains("[Autopilot] the blood door reads open")
                    || l.contains("The BLOOD DOOR has been opened!"))) {
                bloodOpen = i;
            }
            if (l.contains("[Autopilot] decide")) {
                String p = l.replaceAll("^.*\\| pick ", "");
                order.add((bloodOpen < 0 ? "" : "after-blood ") + (p.length() > 40 ? p.substring(0, 40) : p));
            }
        }
        int end = bloodOpen < 0 ? log.size() : bloodOpen;
        for (int i = 0; i < end; i++) {
            String l = log.get(i);
            if (l.contains("pick DOOR")) {
                doorPicks++;
            }
            if (l.contains("[Autopilot] decide") && l.contains("pick PUZZLE")
                    || l.contains("[AutoSecret] Quiz first") || l.contains("[AutoSecret] Ice Fill first")
                    || l.contains("[AutoSecret] autopilot: puzzle") || l.contains("[AutoSecret] Quiz: question")) {
                failures.add("before the blood door opened: " + strip(l));
            }
            if (l.contains("[CHAT]") && (l.contains("has obtained Wither Key!") || l.contains("has obtained Blood Key!"))) {
                // The key a clear drops comes a moment after the clear: no other room is planned in between.
                for (int j = i - 1; j >= 0; j--) {
                    String m = log.get(j);
                    if (m.contains("[Autopilot] done ")) {
                        break;
                    }
                    if (m.contains("[Autopilot] decide") && m.matches(".*\\| pick (CLEAR|SECRET|PUZZLE|EXPLORE) .*")) {
                        failures.add("planned another room before the cleared room's key came: " + strip(m));
                        break;
                    }
                }
            }
            if (!(l.contains("[CHAT]") && l.contains("has obtained Wither Key!"))) {
                continue;
            }
            pickups++;
            String next = null;
            boolean excused = false;
            for (int j = i + 1; j < end && next == null; j++) {
                String m = log.get(j);
                if (m.contains("next door wants the blood key") || m.contains("is out of reach (its trip failed)")) {
                    excused = true;
                }
                if (m.contains("[Autopilot] decide")) {
                    next = m;
                }
            }
            if (next == null || excused) {
                continue;
            }
            boolean ok = next.contains("pick DOOR") || next.contains("pick KEY")
                    || next.contains("Blood First: clearing the blood rush path");
            println("after a Wither Key (line " + i + "): " + strip(next).replaceAll("^.*\\| pick ", "pick "));
            if (!ok) {
                failures.add("the decision after a Wither Key was not the door: " + strip(next));
            }
        }
        println(String.format(Locale.US, "TIMES: blood door open at %.1f s, blood and both puzzles done at %.1f s",
                bloodTick / 20.0, ticks / 20.0));
        println(String.format(Locale.US, "RESULT: %.1f s, blood open line %d, wither keys picked up before it %d, door picks %d,"
                + " Quiz done %s, Ice Fill done %s; order %s", ticks / 20.0, bloodOpen, pickups, doorPicks, quizDone, iceDone, order));
        if (bloodOpen < 0) {
            failures.add("the blood door never opened");
        }
        if (pickups < 1 || doorPicks < 1) {
            failures.add("nothing to judge: " + pickups + " Wither Key pickup(s), " + doorPicks + " door pick(s) before blood");
        }
        if (!quizDone) {
            failures.add("the Quiz was not solved after the blood door opened");
        }
        if (!iceDone) {
            failures.add("Ice Fill was not solved after the blood door opened");
        }
    }

    /**
     * Server thread: the sidebar's Keys line ("Keys: ■ ✗ ■ 0x") from the sim's own team keys, as Hypixel draws it. Its own
     * holder and team, never one of SimSidebar's lines.
     */
    private static void drawKeys(MinecraftServer server) {
        if (server == null) {
            return;
        }
        server.execute(() -> {
            try {
                Class<?> keys = Class.forName("com.killer560.hub.roomsim.SimKeys");
                java.lang.reflect.Field w = keys.getDeclaredField("witherKeys");
                java.lang.reflect.Field b = keys.getDeclaredField("bloodKey");
                w.setAccessible(true);
                b.setAccessible(true);
                int wither = w.getInt(null);
                boolean blood = b.getBoolean(null);
                Scoreboard board = server.getScoreboard();
                Objective obj = board.getObjective("k560sim");
                if (obj == null) {
                    return;
                }
                PlayerTeam team = board.getPlayerTeam(KEYS_TEAM);
                if (team == null) {
                    team = board.addPlayerTeam(KEYS_TEAM);
                }
                team.setPlayerPrefix(Component.literal("Keys: §c■ " + (blood ? "§a✓" : "§c✗")
                        + " §8■ §a" + wither + "x"));
                if (!team.getPlayers().contains(KEYS_HOLDER)) {
                    board.addPlayerToTeam(KEYS_HOLDER, team);
                }
                board.getOrCreatePlayerScore(ScoreHolder.forNameOnly(KEYS_HOLDER), obj).set(0);
            } catch (ReflectiveOperationException e) {
                System.out.println("[" + NAME + "] could not read SimKeys: " + e);
            }
        });
    }

    /** An F7 with the Quiz and Ice Fill pinned; its code, or null when a pin was dropped. Client thread. */
    private static String planFloor(int attempt) {
        int[][] cells = {{14, 15}, {14, 9}, {8, 14}, {15, 20}, {9, 21}, {20, 14}};
        int[] pair = cells[attempt % cells.length];
        Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
        Map<Integer, String> pins = new HashMap<>();
        pins.put(pair[0], "Quiz");
        pins.put(pair[1], "Ice Fill");
        Object planned = ModUnderTest.staticCall(FLOOR_GEN, "plan", new Class<?>[]{floor.getClass(), int.class, int.class,
                Map.class}, new Object[]{floor, 3, 6, pins});
        if (planned == null) {
            return null;
        }
        @SuppressWarnings("unchecked")
        List<String> kept = (List<String>) ModUnderTest.call(planned, "keptPins", new Class<?>[0], new Object[0]);
        println("planned an F7 (pins at " + pair[0] + "/" + pair[1] + "), pins kept: " + kept);
        if (kept == null || kept.size() < 2) {
            return null;
        }
        return (String) ModUnderTest.call(planned, "code", new Class<?>[0], new Object[0]);
    }

    private static String strip(String l) {
        String s = l.replaceAll("^.*?(\\[Autopilot\\]|\\[AutoSecret\\]|\\[AutoClear\\])", "$1");
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }

    private static void println(String line) {
        System.out.println("[" + NAME + "] " + line);
    }
}
