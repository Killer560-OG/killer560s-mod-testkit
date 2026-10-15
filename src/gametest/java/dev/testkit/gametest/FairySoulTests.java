package dev.testkit.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import dev.testkit.gametest.hx.Hx;
import dev.testkit.gametest.mod.Mod;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 746-750: Auto Fairy Souls (mod {@code pathfinding/AutoSoulRunner} + {@code AutoWalker}) on GrimAC, after killer560's
 * 2026-10-11 list: jumpy rotation, a path "a hair higher than where the player actually runs so they are just jumping
 * the whole time", stuck on walls, no ladders, no God Potion sense, no failsafes.
 * <p>
 * Each case writes its own island graph (SkyHanni's format: feet-block positions, {@code fairy_soul} tags) as the
 * cached HUB graph, labels the island Hub through the tab list, puts real armour stands with heads where the souls are,
 * and answers each stand the server sees used with Hypixel's {@code SOUL! You found a Fairy Soul!} line and removes it.
 * The proof is the server's: the {@code use_entity} events it received, the distance the client travelled, and souls
 * the mod logged found - never only the mod's own state.
 * <ul>
 *   <li>746 walk: a wall the straight line goes through (the path rounds its end), a bottom-slab row and a one-block
 *       step to a plateau. Both souls collected; take-offs counted from the client's own motion must be at most 3 (the
 *       step needs one); no tick turned the camera more than the per-frame cap allows (Turn Speed x the 3-tick
 *       frame-gap clamp: no snap) and the camera wrote frames; Grim clean.</li>
 *   <li>747 ladder: the only way to the soul is a 4-block ladder up a tower. Soul collected from the top; the walker
 *       spent ticks climbing; the player stood at the tower's top height.</li>
 *   <li>748 god potion: a 2-block ledge, Jump Boost II from the server. The start line names Jump Boost 2, the soul on
 *       top is collected, Grim clean.</li>
 *   <li>749 corrections: mid-walk the server moves him a block, then turns his camera 90 degrees without moving him. The
 *       run is still active after each, both are reported, and the soul at the end is still collected.</li>
 *   <li>750 damage (killer560 2026-10-11: "if you take damage with no knockback it should just continue ... shellwise
 *       shard"): plain damage every 12 ticks, ten times, mid-route; the walker sees every hit the server says it
 *       applied ("Applied 0.5 damage" on its console), no failsafe fires, the run never stops, both souls are
 *       collected. (At a 10-tick spacing vanilla's invulnerability frames swallowed some hits: 7 of 10, 2026-10-11.)</li>
 *   <li>756 knockback (killer560 2026-10-11, "have it keep working if it gets knocked back, it just needs to
 *       repathfind"): a mob's hit mid-route knocks him back; the walker counts a knockback, is still active 20 ticks
 *       later, no failsafe fired, both souls are collected.</li>
 *   <li>757 death ("if I die ... it will still work and not think it is a staff check"): killed mid-route, he
 *       respawns at a set spawn point (the test presses Respawn on the death screen, as a player does on vanilla); no
 *       correction or staff-check report and no alarm from the death on, the run stays active, and the soul is
 *       collected from the respawn point.</li>
 *   <li>758 control: a server rotation on its own still reports and plays the alarm, and the run carries on.</li>
 * </ul>
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class FairySoulTests implements FabricClientGameTest {

    private static final String CFG = "pathfinding.PathfindingConfig";
    private static final String RUNNER = "pathfinding.AutoSoulRunner";
    private static final String WALKER = "pathfinding.AutoWalker";
    private static final String SOUL_LINE = "§d§lSOUL! §fYou found a §dFairy Soul§f!";

    /** A graph node: feet block, tags, neighbour ids. */
    record Node(int id, int x, int y, int z, boolean soul, int... next) {
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Mod.isCheat()) {
            legit(ctx);
            for (String n : List.of("747-fairy-ladder", "748-fairy-godpot", "749-fairy-corrections", "750-fairy-failsafes",
                    "756-fairy-knockback", "757-fairy-death", "758-fairy-rotation")) {
                if (!Scenario.skip(n)) {
                    Scenario.skipped(n, "Auto Fairy Souls is cheat build only (746 checks the legit jar does not walk)");
                }
            }
            return;
        }
        // Each case is its own server and arena: one failing must not hide the rest.
        AssertionError first = null;
        for (java.util.function.Consumer<ClientGameTestContext> c : List.<java.util.function.Consumer<ClientGameTestContext>>of(
                FairySoulTests::walk, FairySoulTests::ladder, FairySoulTests::godPotion, FairySoulTests::corrections,
                FairySoulTests::failsafes, FairySoulTests::knockback, FairySoulTests::death,
                FairySoulTests::rotationControl)) {
            try {
                c.accept(ctx);
            } catch (AssertionError e) {
                first = first == null ? e : first;
            }
        }
        if (first != null) {
            throw first;
        }
    }

    // ------------------------------------------------------------------ cases

    private static void walk(ClientGameTestContext ctx) {
        Scenario.run(ctx, "746-fairy-walk",
                (server, scenario) -> TestMap.on(server)
                        .platform(-870, 150, 930, 16)
                        .catchFloor(140)
                        // A wall across the straight line from the start to the far side, rounded at its east end.
                        .fill(-882, 151, 930, -874, 153, 930, "minecraft:stone_bricks")
                        // A bottom-slab row to walk over (a half step: no jump) ...
                        // (vanilla fill: "testkit fill" takes a bare block id and cannot set block states)
                        .command("fill -871 151 934 -871 151 944 minecraft:stone_slab[type=bottom]")
                        // ... and a one-block step up to a plateau (one jump).
                        .fill(-866, 151, 934, -858, 151, 944, "minecraft:stone")
                        .survival()
                        .spawn(-877.5, 920.5, 0f)
                        .build(),
                (server, scenario) -> {
                    List<Node> g = List.of(
                            new Node(0, -878, 151, 921, false, 1),
                            new Node(1, -878, 151, 926, false, 0, 2),
                            new Node(2, -872, 151, 928, false, 1, 3),
                            new Node(3, -872, 151, 932, false, 2, 4, 5),
                            new Node(4, -878, 151, 938, false, 3, 7),
                            new Node(5, -868, 151, 939, false, 3, 6),
                            new Node(6, -862, 152, 939, true, 5),
                            new Node(7, -881, 151, 941, true, 4));
                    Result r = runSouls(ctx, scenario, g, 900, null);
                    scenario.log(r.summary());
                    check(r.soulsUsed >= 2 && r.soulsLogged >= 2, "souls: " + r.summary());
                    check(r.travelled > 25, "traveled only " + r.travelled);
                    check(r.takeoffs >= 1 && r.takeoffs <= 3, "take-offs " + r.takeoffs + " (one step needs one; "
                            + "jumping the whole route is the reported bug)");
                    // The camera eases per FRAME by real elapsed time, capped at Turn Speed (20) per tick of real time and
                    // a frame gap clamped to 3 ticks, so no tick of a slow gametest client can turn more than 60.
                    check(r.maxTurnPerTick <= 60.5f, "a tick turned " + r.maxTurnPerTick + " degrees (a snap)");
                    check(r.framesWritten > 20, "the camera wrote only " + r.framesWritten + " frames");
                });
    }

    /** Legit jar: the same start does nothing - not active, no movement, no soul click reaches the server. */
    private static void legit(ClientGameTestContext ctx) {
        Scenario.run(ctx, "746-fairy-walk",
                (server, scenario) -> TestMap.on(server)
                        .platform(-870, 150, 930, 16)
                        .catchFloor(140)
                        .survival()
                        .spawn(-877.5, 920.5, 0f)
                        .build(),
                (server, scenario) -> {
                    List<Node> g = List.of(new Node(0, -878, 151, 921, false, 1), new Node(1, -878, 151, 931, true, 0));
                    try (Hx hx = Hx.connect()) {
                        setup(ctx, hx, g);
                        long since = hx.head();
                        double[] before = scenario.playerPosition();
                        startRun(ctx);
                        ctx.waitTicks(60);
                        double[] after = scenario.playerPosition();
                        double moved = Math.hypot(after[0] - before[0], after[2] - before[2]);
                        int uses = hx.events(since, "use_entity").size();
                        boolean active = isActive(ctx);
                        scenario.log(String.format(Locale.US, "legit jar: active %s, moved %.2f, soul uses %d", active, moved, uses));
                        check(!active && moved < 0.1 && uses == 0, "the legit jar walked or clicked");
                    }
                });
    }

    private static void ladder(ClientGameTestContext ctx) {
        Scenario.run(ctx, "747-fairy-ladder",
                (server, scenario) -> TestMap.on(server)
                        .platform(-900, 150, 1000, 10)
                        .catchFloor(140)
                        .fill(-906, 151, 997, -901, 154, 1003, "minecraft:stone_bricks")
                        .command("fill -900 151 1000 -900 154 1000 minecraft:ladder[facing=east]")
                        .survival()
                        .spawn(-893.5, 1000.5, 90f)
                        .build(),
                (server, scenario) -> {
                    List<Node> g = List.of(
                            new Node(0, -894, 151, 1000, false, 1),
                            new Node(1, -900, 151, 1000, false, 0, 2),
                            new Node(2, -900, 155, 1000, false, 1, 3),
                            new Node(3, -904, 155, 1000, true, 2));
                    int climbBefore = ctx.computeOnClient(mc -> (Integer) Mod.staticCall(WALKER, "climbTicks"));
                    Result r = runSouls(ctx, scenario, g, 700, null);
                    int climbed = ctx.computeOnClient(mc -> (Integer) Mod.staticCall(WALKER, "climbTicks")) - climbBefore;
                    scenario.log(r.summary() + ", climb ticks " + climbed);
                    check(r.soulsUsed >= 1 && r.soulsLogged >= 1, "soul on the tower: " + r.summary());
                    check(climbed > 5, "never climbed (" + climbed + " climb ticks)");
                    check(r.maxY >= 154.9, "never reached the tower top (max y " + r.maxY + ")");
                });
    }

    private static void godPotion(ClientGameTestContext ctx) {
        Scenario.run(ctx, "748-fairy-godpot",
                (server, scenario) -> TestMap.on(server)
                        .platform(-870, 150, 1060, 10)
                        .catchFloor(140)
                        .fill(-872, 151, 1055, -864, 152, 1065, "minecraft:stone")
                        .survival()
                        .spawn(-878.5, 1060.5, -90f)
                        .command("effect give @p minecraft:jump_boost 600 1 true")
                        .build(),
                (server, scenario) -> {
                    List<Node> g = List.of(
                            new Node(0, -878, 151, 1060, false, 1),
                            new Node(1, -874, 151, 1060, false, 0, 2),
                            new Node(2, -870, 153, 1060, false, 1, 3),
                            new Node(3, -867, 153, 1060, true, 2));
                    long mark = LogTap.mark();
                    Result r = runSouls(ctx, scenario, g, 600, null);
                    boolean named = LogTap.since(mark).stream().anyMatch(l -> l.contains("Jump Boost 2"));
                    scenario.log(r.summary() + ", start line names Jump Boost 2: " + named);
                    check(named, "the start line did not name Jump Boost 2");
                    check(r.soulsUsed >= 1 && r.soulsLogged >= 1, "soul on the 2-block ledge: " + r.summary());
                    check(r.maxY >= 152.9, "never got onto the ledge (max y " + r.maxY + ")");
                });
    }

    private static void corrections(ClientGameTestContext ctx) {
        Scenario.run(ctx, "749-fairy-corrections",
                (server, scenario) -> TestMap.on(server)
                        .platform(-850, 150, 1120, 24)
                        .catchFloor(140)
                        .survival()
                        .spawn(-870.5, 1120.5, -90f)
                        .build(),
                (server, scenario) -> {
                    List<Node> g = List.of(
                            new Node(0, -870, 151, 1120, false, 1),
                            new Node(1, -858, 151, 1120, false, 0, 2),
                            new Node(2, -846, 151, 1120, false, 1, 3),
                            new Node(3, -832, 151, 1120, true, 2));
                    int reportsBefore = ctx.computeOnClient(mc -> (Integer) Mod.staticCall("util.ServerCorrections", "reports"));
                    long mark = LogTap.mark();
                    boolean[] activeAfter = new boolean[2];
                    Result r = runSouls(ctx, scenario, g, 700, tick -> {
                        if (tick == 40) {
                            server.command("execute as @p at @s run tp @s ~ ~ ~1");
                        } else if (tick == 50) {
                            activeAfter[0] = ctx.computeOnClient(mc -> (Boolean) Mod.staticCall(RUNNER, "isActive"));
                        } else if (tick == 70) {
                            server.command("execute as @p at @s run tp @s ~ ~ ~ ~90 ~");
                        } else if (tick == 80) {
                            activeAfter[1] = ctx.computeOnClient(mc -> (Boolean) Mod.staticCall(RUNNER, "isActive"));
                        }
                    });
                    int reports = ctx.computeOnClient(mc -> (Integer) Mod.staticCall("util.ServerCorrections", "reports")) - reportsBefore;
                    List<String> log = LogTap.since(mark);
                    boolean moveSaid = log.stream().anyMatch(l -> l.contains("Auto Fairy Souls") && l.contains("moved you"));
                    boolean turnSaid = log.stream().anyMatch(l -> l.contains("turned your camera"));
                    scenario.log(r.summary() + String.format(", active after move %s / after turn %s, reports %d, "
                            + "move said %s, turn said %s", activeAfter[0], activeAfter[1], reports, moveSaid, turnSaid));
                    check(activeAfter[0] && activeAfter[1], "a server correction stopped the run");
                    check(moveSaid && turnSaid && reports >= 2, "corrections not reported");
                    check(r.soulsUsed >= 1 && r.soulsLogged >= 1, "soul after the corrections: " + r.summary());
                });
    }

    private static void failsafes(ClientGameTestContext ctx) {
        Scenario.run(ctx, "750-fairy-failsafes",
                (server, scenario) -> TestMap.on(server)
                        .platform(-850, 150, 1180, 24)
                        .catchFloor(140)
                        .survival()
                        .spawn(-870.5, 1180.5, -90f)
                        .build(),
                (server, scenario) -> {
                    List<Node> g = List.of(
                            new Node(0, -870, 151, 1180, false, 1),
                            new Node(1, -858, 151, 1180, true, 0, 2),
                            new Node(2, -846, 151, 1180, false, 1, 3),
                            new Node(3, -830, 151, 1180, true, 2));
                    int hitsBefore = walkerInt(ctx, "damageHits");
                    long appliedBefore = applied(server);
                    int fsBefore = walkerInt(ctx, "failsafes");
                    int[] inactiveTicks = new int[1];
                    Result r = runSouls(ctx, scenario, g, 700, tick -> {
                        // Shellwise-style: 0.5 damage, no attacker (so no knockback), ten hits 12 ticks apart - past the
                        // 10 ticks of vanilla invulnerability, so each one lands.
                        if (tick >= 12 && tick <= 120 && tick % 12 == 0) {
                            server.command("damage @p 0.5 minecraft:generic");
                        }
                        if (tick >= 12 && tick < 130 && !isActive(ctx)) {
                            inactiveTicks[0]++;
                        }
                    });
                    int hits = walkerInt(ctx, "damageHits") - hitsBefore;
                    int fs = walkerInt(ctx, "failsafes") - fsBefore;
                    long serverApplied = applied(server) - appliedBefore;
                    scenario.log(r.summary() + String.format(", damage applied by the server %d, hits the walker saw %d, "
                            + "failsafes %d, inactive ticks during the damage %d", serverApplied, hits, fs, inactiveTicks[0]));
                    check(serverApplied >= 5, "the server applied only " + serverApplied + " hit(s) - the premise failed");
                    check(hits == serverApplied, "the walker saw " + hits + " of the " + serverApplied + " hits the server applied");
                    check(fs == 0 && inactiveTicks[0] == 0, "damage stopped the run (failsafes " + fs + ")");
                    check(r.soulsUsed >= 2 && r.soulsLogged >= 2, "souls through the damage: " + r.summary());
                });
    }

    /** "Applied ... damage to" lines on the server console so far (vanilla /damage's success message). */
    private static long applied(TestServer server) {
        return server.tail(5000).lines().filter(l -> l.contains("Applied 0.5 damage to")).count();
    }

    private static int walkerInt(ClientGameTestContext ctx, String getter) {
        return ctx.computeOnClient(mc -> (Integer) Mod.staticCall(WALKER, getter));
    }

    private static int alarms(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> (Integer) Mod.staticCall("util.ModSounds", "correctionAlarmsPlayed")
                + (Integer) Mod.staticCall("util.ModSounds", "correctionAlarmsSuppressed"));
    }

    private static void knockback(ClientGameTestContext ctx) {
        Scenario.run(ctx, "756-fairy-knockback",
                (server, scenario) -> TestMap.on(server)
                        .platform(-850, 150, 1240, 24)
                        .catchFloor(140)
                        .survival()
                        .spawn(-870.5, 1240.5, -90f)
                        .build(),
                (server, scenario) -> {
                    List<Node> g = List.of(
                            new Node(0, -870, 151, 1240, false, 1),
                            new Node(1, -858, 151, 1240, true, 0, 2),
                            new Node(2, -846, 151, 1240, false, 1, 3),
                            new Node(3, -834, 151, 1240, true, 2));
                    int kbBefore = walkerInt(ctx, "knockbacks");
                    int fsBefore = walkerInt(ctx, "failsafes");
                    boolean[] activeAfter = new boolean[1];
                    Result r = runSouls(ctx, scenario, g, 700, tick -> {
                        if (tick == 30) {
                            // A mob's hit: damage WITH knockback, pushing him back along -x.
                            server.command("execute as @p at @s run summon minecraft:zombie ~2 ~ ~ "
                                    + "{NoAI:1b,Silent:1b,Tags:[\"tkkb\"]}");
                        } else if (tick == 32) {
                            server.command("damage @p 1 minecraft:mob_attack by @e[tag=tkkb,limit=1]");
                        } else if (tick == 34) {
                            server.command("kill @e[tag=tkkb]");
                        } else if (tick == 55) {
                            activeAfter[0] = isActive(ctx);
                        }
                    });
                    int kb = walkerInt(ctx, "knockbacks") - kbBefore;
                    int fs = walkerInt(ctx, "failsafes") - fsBefore;
                    scenario.log(r.summary() + String.format(", knockbacks %d, failsafes %d, active after %s", kb, fs,
                            activeAfter[0]));
                    check(kb >= 1, "no knockback was seen (" + kb + ")");
                    check(activeAfter[0] && fs == 0, "the knockback stopped the run (failsafes " + fs + ")");
                    check(r.soulsUsed >= 2 && r.soulsLogged >= 2, "souls after the knockback: " + r.summary());
                });
    }

    private static void death(ClientGameTestContext ctx) {
        Scenario.run(ctx, "757-fairy-death",
                (server, scenario) -> TestMap.on(server)
                        .platform(-850, 150, 1300, 24)
                        .catchFloor(140)
                        .survival()
                        .spawn(-870.5, 1300.5, -90f)
                        .command("spawnpoint @p -866 151 1296")
                        .build(),
                (server, scenario) -> {
                    List<Node> g = List.of(
                            new Node(0, -870, 151, 1300, false, 1),
                            new Node(1, -850, 151, 1300, false, 0, 2),
                            new Node(2, -834, 151, 1300, true, 1));
                    int deathsBefore = walkerInt(ctx, "deaths");
                    int fsBefore = walkerInt(ctx, "failsafes");
                    int[] alarmsAtDeath = new int[1];
                    int[] reportsAtDeath = new int[1];
                    long[] logAtDeath = new long[1];
                    boolean[] activeWhileDead = new boolean[1];
                    Result r = runSouls(ctx, scenario, g, 900, tick -> {
                        if (tick == 40) {
                            alarmsAtDeath[0] = alarms(ctx);
                            reportsAtDeath[0] = ctx.computeOnClient(mc -> (Integer) Mod.staticCall("util.ServerCorrections", "reports"));
                            logAtDeath[0] = LogTap.mark();
                            server.command("kill @p");
                        } else if (tick == 45) {
                            activeWhileDead[0] = isActive(ctx);
                        }
                        if (tick > 40) {
                            ctx.runOnClient(mc -> {
                                if (mc.player != null && mc.player.isDeadOrDying()
                                        && dev.testkit.compat.McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.DeathScreen) {
                                    mc.player.respawn();
                                }
                            });
                        }
                    });
                    int deaths = walkerInt(ctx, "deaths") - deathsBefore;
                    int fs = walkerInt(ctx, "failsafes") - fsBefore;
                    int alarmDelta = alarms(ctx) - alarmsAtDeath[0];
                    int reportDelta = ctx.computeOnClient(mc -> (Integer) Mod.staticCall("util.ServerCorrections", "reports")) - reportsAtDeath[0];
                    List<String> log = LogTap.since(logAtDeath[0]);
                    boolean staffOrCorrection = log.stream().anyMatch(l -> l.contains("Server correction")
                            || l.contains("turned your camera") || l.contains("[Correction] Auto Fairy Souls: server moved"));
                    boolean respawned = log.stream().anyMatch(l -> l.contains("[AutoWalker] respawned"));
                    scenario.log(r.summary() + String.format(", deaths %d, failsafes %d, active while dead %s, respawned "
                                    + "line %s, alarms since death %d, correction reports since death %d, correction/staff "
                                    + "line %s", deaths, fs, activeWhileDead[0], respawned, alarmDelta, reportDelta,
                            staffOrCorrection));
                    check(deaths == 1, deaths + " death(s) seen, expected 1");
                    check(activeWhileDead[0] && fs == 0, "the death stopped the run");
                    check(respawned, "no respawn re-path was logged");
                    check(alarmDelta == 0 && reportDelta == 0 && !staffOrCorrection,
                            "the death or respawn was taken for a correction / staff check");
                    check(r.soulsUsed >= 1 && r.soulsLogged >= 1, "soul after the respawn: " + r.summary());
                });
    }

    private static void rotationControl(ClientGameTestContext ctx) {
        Scenario.run(ctx, "758-fairy-rotation",
                (server, scenario) -> TestMap.on(server)
                        .platform(-850, 150, 1360, 24)
                        .catchFloor(140)
                        .survival()
                        .spawn(-870.5, 1360.5, -90f)
                        .build(),
                (server, scenario) -> {
                    List<Node> g = List.of(
                            new Node(0, -870, 151, 1360, false, 1),
                            new Node(1, -850, 151, 1360, false, 0, 2),
                            new Node(2, -834, 151, 1360, true, 1));
                    int[] alarmsBefore = new int[1];
                    int[] reportsBefore = new int[1];
                    long[] mark = new long[1];
                    boolean[] activeAfter = new boolean[1];
                    Result r = runSouls(ctx, scenario, g, 700, tick -> {
                        if (tick == 40) {
                            alarmsBefore[0] = alarms(ctx);
                            reportsBefore[0] = ctx.computeOnClient(mc -> (Integer) Mod.staticCall("util.ServerCorrections", "reports"));
                            mark[0] = LogTap.mark();
                            server.command("execute as @p at @s run tp @s ~ ~ ~ ~90 ~");
                        } else if (tick == 55) {
                            activeAfter[0] = isActive(ctx);
                        }
                    });
                    int alarmDelta = alarms(ctx) - alarmsBefore[0];
                    int reportDelta = ctx.computeOnClient(mc -> (Integer) Mod.staticCall("util.ServerCorrections", "reports")) - reportsBefore[0];
                    boolean said = LogTap.since(mark[0]).stream().anyMatch(l -> l.contains("turned your camera"));
                    // The report is what plays the alarm (ServerCorrections.say); the alarm counter moves only while
                    // the Correction Alarm is switched on with a volume, so it is logged, the report is asserted.
                    scenario.log(r.summary() + String.format(", reports %d, alarms played/suppressed %d, staff-check line %s, "
                            + "active after %s", reportDelta, alarmDelta, said, activeAfter[0]));
                    check(reportDelta >= 1 && said, "a server rotation alone was not reported");
                    check(activeAfter[0], "the rotation stopped the run");
                    check(r.soulsUsed >= 1, "soul after the rotation: " + r.summary());
                });
    }

    // ------------------------------------------------------------------ machinery

    interface TickHook {
        void at(int tick);
    }

    static final class Result {
        int soulsUsed;
        int soulsLogged;
        double travelled;
        int takeoffs;
        float maxTurnPerTick;
        int framesWritten;
        double maxY;
        int ticks;
        boolean activeAtEnd;
        String last = "";

        String summary() {
            return String.format(Locale.US, "souls used (server) %d, logged %d; traveled %.1f blocks in %d ticks; "
                            + "take-offs %d; max turn %.2f deg/tick; camera frames %d; max y %.2f; active at end %s; %s",
                    soulsUsed, soulsLogged, travelled, ticks, takeoffs, maxTurnPerTick, framesWritten, maxY, activeAtEnd, last);
        }
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }

    private static boolean isActive(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> (Boolean) Mod.staticCall(RUNNER, "isActive"));
    }

    private static String graphJson(List<Node> nodes) {
        Map<Integer, Node> byId = new HashMap<>();
        nodes.forEach(n -> byId.put(n.id(), n));
        JsonObject root = new JsonObject();
        for (Node n : nodes) {
            JsonObject o = new JsonObject();
            o.addProperty("Position", n.x() + ".0:" + n.y() + ".0:" + n.z() + ".0");
            if (n.soul()) {
                JsonArray tags = new JsonArray();
                tags.add("fairy_soul");
                o.add("Tags", tags);
            }
            JsonObject nb = new JsonObject();
            for (int id : n.next()) {
                Node m = byId.get(id);
                double d = Math.sqrt(Math.pow(n.x() - m.x(), 2) + Math.pow(n.y() - m.y(), 2) + Math.pow(n.z() - m.z(), 2));
                nb.addProperty(String.valueOf(id), Math.round(d * 100) / 100.0);
            }
            o.add("Neighbours", nb);
            root.add(String.valueOf(n.id()), o);
        }
        return root.toString();
    }

    /** Graph on disk as the cached HUB graph, island = Hub, Skyblock sidebar, settings, souls placed. */
    @SuppressWarnings("unchecked")
    private static Map<String, Integer> setup(ClientGameTestContext ctx, Hx hx, List<Node> nodes) {
        String json = graphJson(nodes);
        ctx.runOnClient(mc -> {
            try {
                Path dir = (Path) Mod.staticCall("pathfinding.GraphRepository", "dataDir");
                Files.createDirectories(dir);
                Files.writeString(dir.resolve("HUB.json"), json, StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new AssertionError("could not write the test graph: " + e, e);
            }
            ((Map<String, Object>) Mod.field("com.killer560.hub.pathfinding.GraphRepository", "LOADED")).remove("HUB");
            ((Map<String, Object>) Mod.field("com.killer560.hub.pathfinding.GraphRepository", "NEXT_ATTEMPT")).remove("HUB");
            Mod.staticCall("pathfinding.AutoSoulRunner", "stop", "testkit setup", false);
            Mod.staticCall("pathfinding.FairySoulsFeature", "stopGuide", false);
            Mod.set(CFG, "setEnabled", true);
            Mod.set(CFG, "setFairySouls", true);
            Mod.set(CFG, "setAutoStartOnIsland", false);
            Mod.set(CFG, "setSoulMode", Mod.enumValue("pathfinding.PathfindingConfig$SoulMode", "ROUTE"));
            Mod.set(CFG, "setAutoWalk", true);
            Mod.set(CFG, "setAutoSouls", true);
            Mod.set(CFG, "setAutoMode", Mod.enumValue("pathfinding.PathfindingConfig$AutoMode", "WALK"));
            Mod.set(CFG, "setAutoCollect", true);
            Mod.set(CFG, "setAutoSprint", true);
            Mod.set(CFG, "setRotationSpeed", 20f);
            Mod.set(CFG, "setChatFeedback", true);
        });
        hx.sidebar("SKYBLOCK", "⏣ Village", "Purse: 0");
        hx.tab(List.of("Area: Hub", "Profile: Test"), null, null);
        ctx.waitFor(mc -> "HUB".equals(Mod.staticCall("pathfinding.IslandDetector", "graphIsland")), 200);
        ctx.waitFor(mc -> (Boolean) Mod.staticCall("pathfinding.GraphRepository", "isLoaded", "HUB"), 200);
        ctx.runOnClient(mc -> Mod.staticCall("pathfinding.FairySoulsFeature", "resetIsland"));
        hx.removeStands();
        Map<String, Integer> stands = new HashMap<>();
        for (Node n : nodes) {
            if (n.soul()) {
                String uuid = hx.stand(n.x() + 0.5, n.y(), n.z() + 0.5, null, "marker", false, "invisible", true,
                        "helmet", "minecraft:player_head");
                stands.put(uuid, n.id());
            }
        }
        ctx.waitTicks(10);
        return stands;
    }

    private static void startRun(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> Mod.staticCall(RUNNER, "start"));
    }

    /**
     * Set up, start, then tick until every soul is used or {@code maxTicks}: answer each used stand, and sample the
     * client every tick for distance, take-offs and the per-tick camera turn.
     */
    private static Result runSouls(ClientGameTestContext ctx, Scenario scenario, List<Node> nodes, int maxTicks,
                                   TickHook hook) {
        Result r = new Result();
        int souls = (int) nodes.stream().filter(Node::soul).count();
        try (Hx hx = Hx.connect()) {
            Map<String, Integer> stands = setup(ctx, hx, nodes);
            long since = hx.head();
            int framesBefore = ctx.computeOnClient(mc -> (Integer) Mod.staticCall("pathfinding.WalkCamera", "framesWritten"));
            startRun(ctx);
            double[] prev = null;
            boolean prevGround = true;
            List<String> used = new ArrayList<>();
            for (int tick = 0; tick < maxTicks; tick++) {
                ctx.waitTick();
                if (hook != null) {
                    hook.at(tick);
                }
                double[] s = ctx.computeOnClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                        mc.player.getYRot(), mc.player.onGround() ? 1 : 0, mc.player.getDeltaMovement().y});
                r.maxY = Math.max(r.maxY, s[1]);
                if (tick % 20 == 0) {
                    // A trace of where he is and what the walker wants, so a failed case says why.
                    String w = ctx.computeOnClient(mc -> {
                        String k = "com.killer560.hub.pathfinding.AutoWalker";
                        return "climbing=" + Mod.field(k, "climbing") + " fwd=" + Mod.field(k, "wantForward")
                                + " jump=" + Mod.field(k, "wantJump") + " side=" + Mod.field(k, "sidestepTicks")
                                + " back=" + Mod.field(k, "backoffTicks") + " walking=" + Mod.field(k, "walking")
                                + " onLadder=" + mc.player.onClimbable() + " hcol=" + mc.player.horizontalCollision;
                    });
                    scenario.log(String.format(Locale.US, "t%d pos %.2f %.2f %.2f yaw %.1f %s", tick, s[0], s[1], s[2],
                            s[3], w));
                }
                if (prev != null) {
                    double dx = s[0] - prev[0];
                    double dz = s[2] - prev[2];
                    double step = Math.sqrt(dx * dx + dz * dz);
                    if (step < 3.0) { // a server tp is not walking
                        r.travelled += step;
                    }
                    float turn = (float) Math.abs(s[3] - prev[3]);
                    if (hook == null || (tick < 39 || tick > 90)) {
                        r.maxTurnPerTick = Math.max(r.maxTurnPerTick, turn);
                    }
                    if (prevGround && s[4] == 0 && s[5] > 0.3) {
                        r.takeoffs++;
                    }
                }
                prevGround = s[4] == 1;
                prev = s;
                for (JsonObject e : hx.events(since, "use_entity")) {
                    since = Math.max(since, e.get("seq").getAsLong());
                    String uuid = e.get("uuid").getAsString();
                    if (stands.containsKey(uuid) && !used.contains(uuid)) {
                        used.add(uuid);
                        hx.chat(SOUL_LINE);
                        hx.call("entity.remove", "uuid", uuid);
                    }
                }
                r.ticks = tick + 1;
                if (used.size() >= souls) {
                    ctx.waitTicks(10);
                    break;
                }
                if (tick > 20 && tick % 20 == 0 && !isActive(ctx)) {
                    break;
                }
            }
            r.soulsUsed = used.size();
            r.framesWritten = ctx.computeOnClient(mc -> (Integer) Mod.staticCall("pathfinding.WalkCamera", "framesWritten")) - framesBefore;
            r.soulsLogged = ctx.computeOnClient(mc -> {
                int[] counts = (int[]) Mod.staticCall("pathfinding.FairySoulsFeature", "islandCounts");
                return counts == null ? -1 : counts[0];
            });
            r.activeAtEnd = isActive(ctx);
            r.last = ctx.computeOnClient(mc -> "last failsafe '" + Mod.staticCall(WALKER, "lastFailsafe") + "', clicks "
                    + Mod.staticCall(RUNNER, "clicksSent"));
            ctx.runOnClient(mc -> Mod.staticCall(RUNNER, "stop", "testkit end", false));
        }
        return r;
    }
}
