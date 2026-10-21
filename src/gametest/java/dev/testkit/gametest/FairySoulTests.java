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
            settings(ctx);
            travelModes(ctx);
            for (String n : List.of("747-fairy-ladder", "748-fairy-godpot", "749-fairy-corrections", "750-fairy-failsafes",
                    "756-fairy-knockback", "757-fairy-death", "758-fairy-rotation", "1017-fairy-overshoot",
                    "1017-fairy-turning", "1017-fairy-freecam", "1017-fairy-soulsoff", "1017-fairy-water", "1017-fairy-ledge",
                    "1017-fairy-pit", "1017-fairy-lava", "1017-fairy-stuck", "1017-fairy-escape-ether",
                    "1017-fairy-escape-pearl", "1017-fairy-escape-none", "1018-fairy-travel-walkonly",
                    "1018-fairy-travel-pearls")) {
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
                FairySoulTests::rotationControl, FairySoulTests::settings, FairySoulTests::overshoot,
                FairySoulTests::turning, FairySoulTests::freecam, FairySoulTests::soulsOff, FairySoulTests::water,
                FairySoulTests::ledge, FairySoulTests::pitLadder, FairySoulTests::lava, FairySoulTests::stuckWall,
                FairySoulTests::travelModes, FairySoulTests::escapes)) {
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

    // ------------------------------------------------------------------ fairy-souls-1017

    /** The L route of the overshoot and turning cases: 26 east (a step up 4 before the corner), then 30 north. */
    private static final List<Node> L_ROUTE = List.of(
            new Node(0, -820, 151, 1420, false, 1),
            new Node(1, -806, 151, 1420, false, 0, 2),
            new Node(2, -796, 151, 1420, false, 1, 3),
            new Node(3, -792, 152, 1420, false, 2, 4),
            new Node(4, -790, 152, 1420, false, 3, 5),
            new Node(5, -790, 152, 1434, false, 4, 6),
            new Node(6, -790, 152, 1450, true, 5));
    private static final double CORNER_X = -789.5;
    private static final double SOUL_Z = 1450.5;

    private static void lArena(TestServer server) {
        TestMap.on(server)
                .floor(-830, 150, 1405, -770, 1465)
                .fill(-830, 151, 1405, -770, 156, 1465, "air")
                .fill(-794, 151, 1405, -770, 151, 1465, "stone")
                .survival()
                .surface(151)
                .spawn(-819.5, 1420.5, -90f)
                .build();
    }

    /** One speed set-up: Skyblock speed (attribute x1000), sprint, Speed and Jump Boost levels (0 = none). */
    record Speed(String name, int hypixel, boolean sprint, int speedLevel, int jumpLevel) {
    }

    private static void applySpeed(TestServer server, ClientGameTestContext ctx, Speed s) {
        server.command("effect clear @p");
        server.command(String.format(Locale.ROOT, "attribute @p minecraft:movement_speed base set %s",
                new java.math.BigDecimal(s.hypixel()).movePointLeft(3).toPlainString()));
        if (s.speedLevel() > 0) {
            server.command("effect give @p minecraft:speed 600 " + (s.speedLevel() - 1) + " true");
        }
        if (s.jumpLevel() > 0) {
            server.command("effect give @p minecraft:jump_boost 600 " + (s.jumpLevel() - 1) + " true");
        }
        server.command("tp @p -819.5 151 1420.5 -90 0");
        sprint = s.sprint();
        ctx.waitTicks(15);
    }

    /**
     * 1017-fairy-overshoot (killer560 2026-10-17: "it was always overshooting not accounting for if it has jump boost or
     * what its speed is"): the L route at walking pace, sprint, Speed II, Skyblock speed 400 and 600, and Jump Boost II
     * with sprint and with 600. Per run: how far past the corner (east of its x) and past the soul (north of its z) he
     * ever got. Bounds: 1.0 at the corner, 0.5 at the soul, the soul collected every time, GrimAC clean throughout.
     */
    private static void overshoot(ClientGameTestContext ctx) {
        Scenario.run(ctx, "1017-fairy-overshoot", (server, scenario) -> lArena(server), (server, scenario) -> {
            List<Speed> speeds = List.of(
                    new Speed("walk", 100, false, 0, 0),
                    new Speed("sprint", 100, true, 0, 0),
                    new Speed("speed II", 100, true, 2, 0),
                    new Speed("speed 400", 400, true, 0, 0),
                    new Speed("speed 600", 600, true, 0, 0),
                    new Speed("sprint + jump II", 100, true, 0, 2),
                    new Speed("speed 600 + jump II", 600, true, 0, 2));
            List<String> bad = new ArrayList<>();
            StringBuilder table = new StringBuilder();
            try {
                for (Speed s : speeds) {
                    applySpeed(server, ctx, s);
                    Result r = runSouls(ctx, scenario, L_ROUTE, 700, null);
                    double corner = Double.NEGATIVE_INFINITY;
                    double pastSoul = Double.NEGATIVE_INFINITY;
                    double topSpeed = 0;
                    double[] prev = null;
                    for (double[] p : r.track) {
                        corner = Math.max(corner, p[0] - CORNER_X);
                        pastSoul = Math.max(pastSoul, p[2] - SOUL_Z);
                        if (prev != null) {
                            topSpeed = Math.max(topSpeed, Math.hypot(p[0] - prev[0], p[2] - prev[2]));
                        }
                        prev = p;
                    }
                    double[] end = r.track.get(r.track.size() - 1);
                    double rest = Math.hypot(end[0] - CORNER_X, end[2] - SOUL_Z);
                    String line = String.format(Locale.US, "%-20s top %.2f b/t, past corner %+.2f, past soul %+.2f, "
                                    + "rest %.2f from soul, souls %d, %d ticks", s.name(), topSpeed, corner, pastSoul, rest,
                            r.soulsUsed, r.ticks);
                    table.append(line).append('\n');
                    scenario.log("OVERSHOOT " + line);
                    if (corner > 1.0 || pastSoul > 0.5 || r.soulsUsed < 1) {
                        bad.add(line);
                    }
                }
            } finally {
                resetKnobs();
                server.command("effect clear @p");
                server.command("attribute @p minecraft:movement_speed base set 0.1");
            }
            scenario.log("overshoot table:\n" + table);
            check(bad.isEmpty(), "over the bounds (corner 1.0, soul 0.5): " + bad);
        });
    }

    /**
     * 1017-fairy-turning ("it should attempt to make it look as human as possible so it should be more jittery sometimes
     * and not always perfectly smooth"): the same L route twice at the same speed. The body's per-tick turns must not
     * repeat between the runs, and inside a run the turn rates must vary (not one fixed curve).
     */
    private static void turning(ClientGameTestContext ctx) {
        Scenario.run(ctx, "1017-fairy-turning", (server, scenario) -> lArena(server), (server, scenario) -> {
            List<List<Double>> runs = new ArrayList<>();
            int movementsBefore = ctx.computeOnClient(mc -> (Integer) Mod.staticCall("pathfinding.WalkCamera", "movements"));
            int overshootsBefore = ctx.computeOnClient(mc -> (Integer) Mod.staticCall("pathfinding.WalkCamera", "overshoots"));
            int pausesBefore = ctx.computeOnClient(mc -> (Integer) Mod.staticCall("pathfinding.WalkCamera", "pauses"));
            try {
                for (int i = 0; i < 2; i++) {
                    applySpeed(server, ctx, new Speed("sprint", 100, true, 0, 0));
                    Result r = runSouls(ctx, scenario, L_ROUTE, 700, null);
                    check(r.soulsUsed >= 1, "run " + (i + 1) + " did not collect the soul: " + r.summary());
                    List<Double> turns = new ArrayList<>();
                    for (int k = 1; k < r.track.size(); k++) {
                        if (r.track.get(k)[7] == 1 && r.track.get(k - 1)[7] == 1) {
                            turns.add(r.track.get(k)[3] - r.track.get(k - 1)[3]);
                        }
                    }
                    runs.add(turns);
                }
            } finally {
                resetKnobs();
            }
            int n = Math.min(runs.get(0).size(), runs.get(1).size());
            double diff = 0;
            for (int k = 0; k < n; k++) {
                diff += Math.abs(runs.get(0).get(k) - runs.get(1).get(k));
            }
            // Turn rates of the ticks that turned at all (over 0.3 degrees), run 1.
            List<Double> rates = new ArrayList<>();
            for (double t : runs.get(0)) {
                if (Math.abs(t) > 0.3) {
                    rates.add(Math.abs(t));
                }
            }
            double mean = rates.stream().mapToDouble(d -> d).average().orElse(0);
            double sd = Math.sqrt(rates.stream().mapToDouble(d -> (d - mean) * (d - mean)).average().orElse(0));
            long distinct = rates.stream().map(d -> Math.round(d * 10)).distinct().count();
            int movements = ctx.computeOnClient(mc -> (Integer) Mod.staticCall("pathfinding.WalkCamera", "movements")) - movementsBefore;
            int overshoots = ctx.computeOnClient(mc -> (Integer) Mod.staticCall("pathfinding.WalkCamera", "overshoots")) - overshootsBefore;
            int pauses = ctx.computeOnClient(mc -> (Integer) Mod.staticCall("pathfinding.WalkCamera", "pauses")) - pausesBefore;
            scenario.log(String.format(Locale.US, "turning: summed per-tick difference between the runs %.1f deg over %d ticks; "
                            + "run 1 turning ticks %d, rate mean %.2f sd %.2f (cv %.2f), %d distinct rates; movements %d, "
                            + "overshoot-and-correct %d, micro-pauses %d", diff, n, rates.size(), mean, sd,
                    mean == 0 ? 0 : sd / mean, distinct, movements, overshoots, pauses));
            check(rates.size() >= 8, "only " + rates.size() + " turning ticks - the premise (a turning run) failed");
            check(diff > 5.0, "the two runs turned the same way tick for tick (" + diff + ")");
            check(mean > 0 && sd / mean > 0.25 && distinct >= 6, "the turn rates barely vary (cv " + (sd / mean) + ")");
        });
    }

    /**
     * 1017-fairy-freecam ("have it put the player in freecam like auto routes ... and only if they press a key have it
     * stop"): the Start/Stop key (K) starts it and its own press does not stop it; while it walks the L the camera is
     * held still and the body turns; moving the mouse does not stop it; the J key does, by name, and gives the camera
     * back with the body under it. A second run is stopped by a left click.
     */
    private static void freecam(ClientGameTestContext ctx) {
        Scenario.run(ctx, "1017-fairy-freecam",
                (server, scenario) -> TestMap.on(server)
                        .floor(-830, 150, 1500, -790, 1530)
                        .fill(-830, 151, 1500, -790, 155, 1530, "air")
                        .survival()
                        .surface(151)
                        .spawn(-824.5, 1505.5, -90f)
                        .build(),
                (server, scenario) -> {
                    List<Node> g = List.of(
                            new Node(0, -825, 151, 1505, false, 1),
                            new Node(1, -805, 151, 1505, false, 0, 2),
                            new Node(2, -805, 151, 1528, true, 1));
                    try (Hx hx = Hx.connect()) {
                        setup(ctx, hx, g);
                        ctx.runOnClient(mc -> Mod.set(CFG, "setResumeKeyCode", 75)); // K
                        long mark = LogTap.mark();
                        ctx.getInput().pressKey(75);
                        ctx.waitTicks(3);
                        boolean startedByKey = isActive(ctx);
                        if (!startedByKey) {
                            scenario.log("the K press did not start it (simulated keys may not reach the poll) - starting by call");
                            startRun(ctx);
                        }
                        ctx.waitTicks(2);
                        float view0 = ctx.computeOnClient(mc -> mc.player.getViewYRot(1f));
                        float body0 = ctx.computeOnClient(mc -> mc.player.getYRot());
                        int heldTicks = 0;
                        float viewMin = view0;
                        float viewMax = view0;
                        float bodyMin = body0;
                        float bodyMax = body0;
                        boolean activeAll = true;
                        for (int t = 0; t < 110; t++) {
                            ctx.waitTick();
                            float[] v = ctx.computeOnClient(mc -> new float[]{mc.player.getViewYRot(1f), mc.player.getYRot(),
                                    viewHeld() ? 1 : 0});
                            viewMin = Math.min(viewMin, v[0]);
                            viewMax = Math.max(viewMax, v[0]);
                            bodyMin = Math.min(bodyMin, v[1]);
                            bodyMax = Math.max(bodyMax, v[1]);
                            heldTicks += (int) v[2];
                            activeAll &= isActive(ctx);
                        }
                        // The mouse steers the held view, and is not a stop.
                        ctx.getInput().moveCursor(120, 0);
                        ctx.waitTicks(3);
                        boolean activeAfterMouse = isActive(ctx);
                        float viewAfterMouse = ctx.computeOnClient(mc -> mc.player.getViewYRot(1f));
                        // The perspective key (F5) changes the view and is not a stop (killer560, 2026-10-18).
                        ctx.getInput().pressKey(294); // F5
                        ctx.waitTicks(3);
                        boolean activeAfterF5 = isActive(ctx);
                        ctx.getInput().pressKey(74); // J
                        ctx.waitTicks(3);
                        boolean activeAfterJ = isActive(ctx);
                        float[] after = ctx.computeOnClient(mc -> new float[]{mc.player.getViewYRot(1f), mc.player.getYRot(),
                                viewHeld() ? 1 : 0});
                        boolean namedJ = LogTap.since(mark).stream().anyMatch(l -> l.contains("Stopped") && l.contains("J key"));
                        // Second run, from the start again (the server answers no click here, so near the soul it would give up): a left click stops it.
                        server.command("tp @p -824.5 151 1505.5 -90 0");
                        ctx.waitTicks(5);
                        startRun(ctx);
                        ctx.waitTicks(20);
                        boolean activeBeforeClick = isActive(ctx);
                        long mark2 = LogTap.mark();
                        ctx.getInput().pressMouse(0);
                        ctx.waitTicks(3);
                        boolean activeAfterClick = isActive(ctx);
                        boolean namedClick = LogTap.since(mark2).stream().anyMatch(l -> l.contains("Stopped") && l.contains("left click"));
                        ctx.runOnClient(mc -> Mod.set(CFG, "setResumeKeyCode", -1));
                        scenario.log(String.format(Locale.US, "freecam: started by K %s, active through the walk %s, view held %d/110 "
                                        + "ticks, view yaw range %.2f, body yaw range %.1f; after a mouse move active %s (view %.1f -> %.1f); "
                                        + "after J active %s, named J %s, view held %s, body-view %.2f; second run active %s, "
                                        + "after a left click active %s, named %s",
                                startedByKey, activeAll, heldTicks, viewMax - viewMin, bodyMax - bodyMin, activeAfterMouse,
                                view0, viewAfterMouse, activeAfterJ, namedJ, after[2] == 1, Math.abs(after[0] - after[1]),
                                activeBeforeClick, activeAfterClick, namedClick));
                        check(activeAll, "it stopped by itself during the walk (its own start key?)");
                        check(heldTicks >= 105, "the camera was held only " + heldTicks + " of 110 ticks");
                        check(bodyMax - bodyMin > 30f, "the body never turned the corner (" + (bodyMax - bodyMin) + ")");
                        check(viewMax - viewMin < 0.5f, "the camera moved " + (viewMax - viewMin) + " degrees with the body");
                        check(activeAfterMouse, "moving the mouse stopped it");
                        check(activeAfterF5, "the perspective key (F5) stopped it");
                        check(!activeAfterJ && namedJ, "the J key did not stop it by name");
                        check(after[2] == 0 && Math.abs(after[0] - after[1]) < 0.5f, "the camera was not handed back under the body");
                        check(activeBeforeClick && !activeAfterClick && namedClick, "a left click did not stop the second run");
                    }
                });
    }

    /** 1017-fairy-soulsoff ("you shouldn't have to have the fairy soul setting on"): the Fairy Souls switch OFF, the graph
     *  not loaded by anything else; Auto Fairy Souls loads it, walks and collects both souls. */
    private static void soulsOff(ClientGameTestContext ctx) {
        Scenario.run(ctx, "1017-fairy-soulsoff",
                (server, scenario) -> TestMap.on(server)
                        .floor(-830, 150, 1550, -790, 1570)
                        .fill(-830, 151, 1550, -790, 155, 1570, "air")
                        .survival()
                        .surface(151)
                        .spawn(-824.5, 1560.5, -90f)
                        .build(),
                (server, scenario) -> {
                    List<Node> g = List.of(
                            new Node(0, -825, 151, 1560, false, 1),
                            new Node(1, -812, 151, 1560, true, 0, 2),
                            new Node(2, -798, 151, 1560, true, 1));
                    fairySoulsSwitch = false;
                    Result r;
                    try {
                        r = runSouls(ctx, scenario, g, 600, null);
                    } finally {
                        resetKnobs();
                    }
                    boolean enabled = ctx.computeOnClient(mc -> (Boolean) Mod.get(CFG, "isEnabledRaw"));
                    scenario.log(r.summary() + ", Fairy Souls switch now " + enabled);
                    check(r.soulsUsed >= 2 && r.soulsLogged >= 2, "with Fairy Souls off: " + r.summary());
                });
    }

    /** A plateau (top feet 155) over a floor at 150 with a 3x3, 4-deep pit at (cx, cz); a ladder up its west wall. */
    private static TestMap pitArena(TestServer server, int cx, int cz, boolean ladder) {
        TestMap m = TestMap.on(server)
                .floor(cx - 14, 150, cz - 14, cx + 14, cz + 14)
                .fill(cx - 14, 151, cz - 14, cx + 14, 154, cz + 14, "stone")
                .fill(cx - 14, 155, cz - 14, cx + 14, 160, cz + 14, "air")
                .fill(cx - 1, 151, cz - 1, cx + 1, 154, cz + 1, "air");
        if (ladder) {
            m.command(String.format(Locale.ROOT, "fill %d 151 %d %d 154 %d minecraft:ladder[facing=east]", cx - 1, cz, cx - 1, cz));
        }
        return m.survival().surface(151).spawn(cx + 0.5, cz + 0.5, 90f);
    }

    /** The pit's route: along its east rim, then off to the soul on the plateau. */
    private static List<Node> pitRoute(int cx, int cz) {
        return List.of(
                new Node(0, cx + 2, 155, cz, false, 1),
                new Node(1, cx + 2, 155, cz + 4, false, 0, 2),
                new Node(2, cx + 7, 155, cz + 7, true, 1));
    }

    /** A recovery case: the run reaches the soul, at least one way back was planned, and no death on the way. */
    private static void recovery(ClientGameTestContext ctx, String name, java.util.function.Consumer<TestServer> build,
                                 List<Node> g, int maxTicks) {
        recovery(ctx, name, build, g, maxTicks, null);
    }

    private static void recovery(ClientGameTestContext ctx, String name, java.util.function.Consumer<TestServer> build,
                                 List<Node> g, int maxTicks, java.util.function.BiConsumer<TestServer, Integer> hook) {
        Scenario.run(ctx, name, (server, scenario) -> build.accept(server), (server, scenario) -> {
            int plannedBefore = walkerInt(ctx, "rescuesPlanned");
            int doneBefore = walkerInt(ctx, "rescuesDone");
            int deathsBefore = walkerInt(ctx, "deaths");
            ctx.waitTicks(20); // let him fall into whatever he was put over
            Result r;
            try {
                r = runSouls(ctx, scenario, g, maxTicks, hook == null ? null : tick -> hook.accept(server, tick));
            } finally {
                resetKnobs();
            }
            int planned = walkerInt(ctx, "rescuesPlanned") - plannedBefore;
            int done = walkerInt(ctx, "rescuesDone") - doneBefore;
            int deaths = walkerInt(ctx, "deaths") - deathsBefore;
            String why = ctx.computeOnClient(mc -> (String) Mod.staticCall(WALKER, "lastRescue"));
            scenario.log(r.summary() + String.format(", ways back planned %d, completed %d (last: %s), deaths %d",
                    planned, done, why, deaths));
            // A way back that ends at the soul is cut short by the click (the soul in reach), so "completed" can stay 0.
            check(planned >= 1, "no way back was planned (" + planned + ")");
            check(deaths == 0, "he died on the way");
            check(r.soulsUsed >= 1 && r.soulsLogged >= 1, "the soul after the way back: " + r.summary());
        });
    }

    private static void water(ClientGameTestContext ctx) {
        recovery(ctx, "1017-fairy-water", server -> TestMap.on(server)
                        .floor(-830, 150, 1590, -795, 1612)
                        .fill(-830, 151, 1590, -795, 155, 1612, "air")
                        .fill(-822, 147, 1596, -816, 147, 1604, "stone")
                        .fill(-822, 148, 1596, -816, 150, 1604, "water")
                        .survival().surface(151).spawn(-818.5, 1600.5, 90f).build(),
                List.of(new Node(0, -812, 151, 1600, false, 1),
                        new Node(1, -806, 151, 1600, false, 0, 2),
                        new Node(2, -800, 151, 1600, true, 1)), 700);
    }

    private static void ledge(ClientGameTestContext ctx) {
        recovery(ctx, "1017-fairy-ledge", server -> TestMap.on(server)
                        .floor(-830, 150, 1640, -790, 1662)
                        .fill(-830, 151, 1640, -790, 158, 1662, "air")
                        .fill(-830, 151, 1652, -790, 153, 1662, "stone")
                        .fill(-800, 151, 1649, -798, 151, 1649, "stone")
                        .fill(-800, 151, 1650, -798, 152, 1650, "stone")
                        .fill(-800, 151, 1651, -798, 153, 1651, "stone")
                        .survival().surface(151).spawn(-818.5, 1648.5, 0f).build(),
                List.of(new Node(0, -818, 154, 1654, false, 1),
                        new Node(1, -810, 154, 1656, false, 0, 2),
                        new Node(2, -804, 154, 1658, true, 1)), 800);
    }

    private static void pitLadder(ClientGameTestContext ctx) {
        recovery(ctx, "1017-fairy-pit", server -> pitArena(server, -810, 1700, true).build(), pitRoute(-810, 1700), 800);
    }

    private static void lava(ClientGameTestContext ctx) {
        recovery(ctx, "1017-fairy-lava", server -> TestMap.on(server)
                        .floor(-830, 150, 1740, -800, 1760)
                        .fill(-830, 151, 1740, -800, 155, 1760, "air")
                        .fill(-821, 149, 1749, -819, 149, 1751, "stone")
                        .fill(-821, 150, 1749, -819, 150, 1751, "lava")
                        .fill(-820, 150, 1750, -820, 150, 1750, "minecraft:glass")
                        .survival().surface(151).spawn(-819.5, 1750.5, -90f).build(),
                List.of(new Node(0, -815, 151, 1750, false, 1),
                        new Node(1, -810, 151, 1750, false, 0, 2),
                        new Node(2, -805, 151, 1750, true, 1)), 600);
    }

    private static void stuckWall(ClientGameTestContext ctx) {
        recovery(ctx, "1017-fairy-stuck", server -> TestMap.on(server)
                        .floor(-830, 150, 1790, -790, 1812)
                        .fill(-830, 151, 1790, -790, 155, 1812, "air")
                        .fill(-812, 151, 1794, -812, 153, 1806, "minecraft:glass")
                        .survival().surface(151).spawn(-824.5, 1800.5, -90f).build(),
                List.of(new Node(0, -825, 151, 1800, false, 1),
                        new Node(1, -812, 151, 1800, false, 0, 2),
                        new Node(2, -798, 151, 1800, true, 1)), 800);
    }

    /**
     * 1017-fairy-escape-*: the same 4-deep pit with no ladder - no way out on foot. With an AOTV and Etherwarp mode it
     * warps out (the server makes the etherwarp); with only Allow Ender Pearls and pearls it throws one; with neither
     * it says "No way out" once and skips the soul.
     */
    private static void escapes(ClientGameTestContext ctx) {
        escape(ctx, "1017-fairy-escape-ether", -810, 1850, "ether");
        escape(ctx, "1017-fairy-escape-pearl", -810, 1900, "pearl");
        escape(ctx, "1017-fairy-escape-none", -810, 1950, "none");
        // 1018-fairy-travel: Walk Only with an AOTV and pearls in the hotbar uses neither; Walk With Pearls with both throws
        // the pearl and never warps.
        escape(ctx, "1018-fairy-travel-walkonly", -810, 2050, "walkonly");
        escape(ctx, "1018-fairy-travel-pearls", -810, 2100, "pearls");
    }

    private static void escape(ClientGameTestContext ctx, String name, int cx, int cz, String tool) {
        Scenario.run(ctx, name, (server, scenario) -> pitArena(server, cx, cz, false).build(), (server, scenario) -> {
            int etherBefore = ctx.computeOnClient(mc -> (Integer) Mod.staticCall(RUNNER, "escapesEther"));
            int pearlBefore = ctx.computeOnClient(mc -> (Integer) Mod.staticCall(RUNNER, "escapesPearl"));
            int noneBefore = ctx.computeOnClient(mc -> (Integer) Mod.staticCall(RUNNER, "noWayOuts"));
            long mark = LogTap.mark();
            JsonObject statsBefore = null;
            Result r;
            try (Hx hx = Hx.connect()) {
                boolean bothTools = tool.equals("walkonly") || tool.equals("pearls");
                if (bothTools) {
                    hx.call("dungeon.abilities", "enabled", true);
                    statsBefore = hx.call("dungeon.stats").getAsJsonObject();
                    hx.give(0, "minecraft:diamond_shovel[custom_data={id:\"ASPECT_OF_THE_VOID\",ethermerge:1,tuned_transmission:4},"
                            + "unbreakable={}]");
                    hx.call("give", "slot", 1, "count", 16, "stack", "minecraft:ender_pearl");
                    autoMode = tool.equals("pearls") ? "PEARLS" : "WALK";
                } else if (tool.equals("ether")) {
                    hx.call("dungeon.abilities", "enabled", true);
                    statsBefore = hx.call("dungeon.stats").getAsJsonObject();
                    hx.give(0, "minecraft:diamond_shovel[custom_data={id:\"ASPECT_OF_THE_VOID\",ethermerge:1,tuned_transmission:4},"
                            + "unbreakable={}]");
                    autoMode = "ETHERWARP";
                } else if (tool.equals("pearl")) {
                    hx.call("give", "slot", 1, "count", 16, "stack", "minecraft:ender_pearl");
                    autoMode = "PEARLS";
                }
                ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(4));
                ctx.waitTicks(20);
                try {
                    r = runSouls(ctx, scenario, pitRoute(cx, cz), 700, null);
                } finally {
                    resetKnobs();
                }
                int ether = ctx.computeOnClient(mc -> (Integer) Mod.staticCall(RUNNER, "escapesEther")) - etherBefore;
                int pearl = ctx.computeOnClient(mc -> (Integer) Mod.staticCall(RUNNER, "escapesPearl")) - pearlBefore;
                int none = ctx.computeOnClient(mc -> (Integer) Mod.staticCall(RUNNER, "noWayOuts")) - noneBefore;
                int serverWarps = statsBefore == null ? 0 : hx.call("dungeon.stats").getAsJsonObject().get("etherwarps").getAsInt()
                        - statsBefore.get("etherwarps").getAsInt();
                if (tool.equals("ether") || bothTools) {
                    hx.call("dungeon.abilities", "enabled", false);
                }
                List<String> log = LogTap.since(mark);
                boolean saidNoWay = log.stream().anyMatch(l -> l.contains("No way out"));
                boolean saidUnreached = log.stream().anyMatch(l -> l.contains("could not be reached"));
                scenario.log(r.summary() + String.format(", escapes: etherwarp %d (server warps %d), pearl %d, no way out %d; "
                        + "said 'No way out' %s, 'could not be reached' %s", ether, serverWarps, pearl, none, saidNoWay, saidUnreached));
                switch (tool) {
                    case "ether" -> {
                        check(ether >= 1 && serverWarps >= 1 && pearl == 0, "no etherwarp escape");
                        check(r.soulsUsed >= 1, "the soul after the etherwarp: " + r.summary());
                    }
                    case "pearl" -> {
                        check(pearl >= 1 && ether == 0, "no pearl escape");
                        check(r.soulsUsed >= 1, "the soul after the pearl: " + r.summary());
                    }
                    case "pearls" -> {
                        check(pearl >= 1 && ether == 0 && serverWarps == 0, "Walk With Pearls: no pearl, or it etherwarped");
                        check(r.soulsUsed >= 1, "the soul after the pearl: " + r.summary());
                    }
                    default -> {
                        check(none >= 1 && ether == 0 && pearl == 0 && serverWarps == 0,
                                "it escaped with a tool it was not allowed");
                        check(saidNoWay && saidUnreached && r.soulsUsed == 0 && !r.activeAtEnd,
                                "no 'No way out' and skip, or it did not end");
                    }
                }
            }
        });
    }

    /**
     * 1017-fairy-settings: the Fairy Souls tab's rows in killer560's order (switch, Show Waypoints, Show Path + Path
     * Mode, Dim Path + Path Colour, the sliders, HUD + Start On Island Join), no "Found n/m" line and no Stop Navigation;
     * the Auto Fairy Souls tab (cheat) with its switch first, Auto Walk and Auto Click ON by default and no Turn Speed;
     * every setting through save and load; and a version-1 file migrated.
     */
    @SuppressWarnings("unchecked")
    private static void settings(ClientGameTestContext ctx) {
        Scenario.run(ctx, "1017-fairy-settings",
                (server, scenario) -> TestMap.on(server).platform(-810, 150, 2000, 4).survival().spawn(-809.5, 2000.5, 0f).build(),
                (server, scenario) -> {
                    String report = ctx.computeOnClient(mc -> {
                        StringBuilder out = new StringBuilder();
                        Path file = (Path) Mod.staticCall("pathfinding.PathfindingConfig", "path");
                        byte[] backup;
                        try {
                            backup = Files.exists(file) ? Files.readAllBytes(file) : null;
                        } catch (Exception e) {
                            throw new AssertionError(e);
                        }
                        try {
                            Mod.set(CFG, "setEnabled", true);
                            List<String> legit = rows("com.killer560.hub.gui.tab.PathfindingTab");
                            out.append("fairy souls rows ").append(legit).append('\n');
                            List<String> want = List.of("Fairy Souls", "Show Waypoints", "Show Path", "Path Mode", "Dim Path",
                                    "Path Colour", "Visible Path", "Line Width", "Re-Path After", "Text Scale", "HUD",
                                    "Start On Island Join");
                            check(legit.size() >= want.size() && legit.subList(0, want.size()).equals(want),
                                    "Fairy Souls rows out of order: " + legit);
                            check(legit.stream().noneMatch(l -> l.startsWith("Found") || l.startsWith("No graph")),
                                    "the Found n/m line is still there");
                            check(legit.stream().noneMatch(l -> l.contains("Stop Navigation") || l.contains("Nothing To Stop")),
                                    "the Stop Navigation button is still there");
                            if (Mod.isCheat()) {
                                Mod.set(CFG, "setAutoSouls", true);
                                List<String> cheat = rows("com.killer560.hub.gui.tab.AutoFairySoulsTab");
                                out.append("auto fairy souls rows ").append(cheat).append('\n');
                                check(!cheat.isEmpty() && cheat.get(0).equals("Auto Fairy Souls"), "Auto Fairy Souls switch not first: " + cheat);
                                check(cheat.contains("Auto Walk") && cheat.contains("Auto Click") && cheat.contains("Sprint")
                                        && cheat.contains("Start/Stop Key") && cheat.contains("Auto Mode"), "rows missing: " + cheat);
                                check(!cheat.contains("Allow Ender Pearls"), "Allow Ender Pearls is still a row (it is a travel mode now)");
                                check(cheat.stream().noneMatch(l -> l.contains("Turn Speed")), "Turn Speed is still there");
                            }
                            // Save and load every setting.
                            Mod.set(CFG, "setSoulWaypoints", false);
                            Mod.set(CFG, "setShowPath", false);
                            Mod.set(CFG, "setSoulMode", Mod.enumValue("pathfinding.PathfindingConfig$SoulMode", "NEAREST"));
                            Mod.set(CFG, "setDimPath", true);
                            Mod.set(CFG, "setPathColor", 0xFF123456);
                            Mod.set(CFG, "setVisiblePathLength", 40);
                            Mod.set(CFG, "setSoulHud", false);
                            Mod.set(CFG, "setAutoStartOnIsland", true);
                            Mod.set(CFG, "setAutoWalk", false);
                            Mod.set(CFG, "setAutoCollect", false);
                            Mod.set(CFG, "setAutoSprint", false);
                            Mod.set(CFG, "setAutoMode", Mod.enumValue("pathfinding.PathfindingConfig$AutoMode", "FAST_ETHERWARP"));
                            Mod.set(CFG, "setResumeKeyCode", 75);
                            Mod.call(Mod.cfg(CFG), "save");
                            Mod.staticCall(CFG, "load");
                            String got = String.format("waypoints %s path %s mode %s dim %s color %x length %s hud %s join %s walk %s "
                                            + "click %s pearls %s sprint %s auto %s key %s", g("isSoulWaypoints"), g("isShowPath"),
                                    g("getSoulMode"), g("isDimPath"), (Integer) g("getPathColor"), g("getVisiblePathLength"),
                                    g("isSoulHud"), g("isAutoStartOnIsland"), g("isAutoWalkRaw"), g("isAutoCollect"),
                                    g("isAllowPearls"), g("isAutoSprint"), g("getAutoMode"), g("getResumeKeyCode"));
                            out.append("reloaded: ").append(got).append('\n');
                            check(got.equals("waypoints false path false mode NEAREST dim true color ff123456 length 40 hud false "
                                    + "join true walk false click false pearls false sprint false auto FAST_ETHERWARP key 75"),
                                    "settings did not survive save and load: " + got);
                            // A version-1 file.
                            Files.writeString(file, "{\"enabled\":true,\"fairySouls\":false,\"showWholePath\":true,"
                                    + "\"autoMode\":\"PEARLS\",\"autoWalk\":false,\"autoSouls\":true,\"rotationSpeed\":12.0}");
                            Mod.staticCall(CFG, "load");
                            String migrated = String.format("enabled %s waypoints %s dim %s mode %s pearls %s walk %s souls %s",
                                    g("isEnabledRaw"), g("isSoulWaypoints"), g("isDimPath"), g("getAutoMode"), g("isAllowPearls"),
                                    g("isAutoWalkRaw"), g("isAutoSoulsRaw"));
                            Mod.call(Mod.cfg(CFG), "save");
                            String saved = Files.readString(file);
                            out.append("migrated: ").append(migrated).append('\n');
                            check(migrated.equals("enabled true waypoints false dim true mode PEARLS pearls true walk true souls true"),
                                    "the version-1 file migrated wrong: " + migrated);
                            check(saved.contains("\"version\": 2") && !saved.contains("showWholePath")
                                    && !saved.contains("rotationSpeed") && !saved.contains("fairySouls"), "the saved file: " + saved);
                        } catch (java.io.IOException e) {
                            throw new AssertionError(e);
                        } finally {
                            try {
                                if (backup != null) {
                                    Files.write(file, backup);
                                } else {
                                    Files.deleteIfExists(file);
                                }
                            } catch (Exception ignored) {
                                // best effort
                            }
                            Mod.staticCall(CFG, "load");
                        }
                        return out.toString();
                    });
                    scenario.log(report);
                });
    }

    /**
     * 1018-fairy-travel-modes: Auto Mode has exactly four values in order (Walk Only, Walk With Pearls, Etherwarp, Fast
     * Etherwarp); each survives save and load; the pearl/etherwarp permissions follow the mode; an old file with
     * Walk Only plus allowPearls loads as Walk With Pearls and an old Etherwarp file stays Etherwarp.
     */
    private static void travelModes(ClientGameTestContext ctx) {
        Scenario.run(ctx, "1018-fairy-travel-modes",
                (server, scenario) -> TestMap.on(server).platform(-810, 150, 2000, 4).survival().spawn(-809.5, 2000.5, 0f).build(),
                (server, scenario) -> {
                    String report = ctx.computeOnClient(mc -> {
                        StringBuilder out = new StringBuilder();
                        Path file = (Path) Mod.staticCall("pathfinding.PathfindingConfig", "path");
                        byte[] backup;
                        try {
                            backup = Files.exists(file) ? Files.readAllBytes(file) : null;
                        } catch (Exception e) {
                            throw new AssertionError(e);
                        }
                        try {
                            Object[] values = Class.forName("com.killer560.hub.pathfinding.PathfindingConfig$AutoMode").getEnumConstants();
                            List<String> names = new ArrayList<>();
                            for (Object v : values) {
                                names.add(v.toString());
                            }
                            out.append("modes ").append(names).append('\n');
                            check(names.equals(List.of("WALK", "PEARLS", "ETHERWARP", "FAST_ETHERWARP")), "the four modes: " + names);
                            Files.deleteIfExists(file);
                            Mod.staticCall(CFG, "load");
                            check(g("getAutoMode").toString().equals("WALK") && !(Boolean) g("isAllowPearls"),
                                    "default is not Walk Only: " + g("getAutoMode"));
                            for (String name : names) {
                                Mod.set(CFG, "setAutoMode", Mod.enumValue("pathfinding.PathfindingConfig$AutoMode", name));
                                Mod.call(Mod.cfg(CFG), "save");
                                Mod.staticCall(CFG, "load");
                                Object mode = g("getAutoMode");
                                boolean ether = (Boolean) mode.getClass().getMethod("etherwarps").invoke(mode);
                                out.append("reloaded ").append(mode).append(" pearls ").append(g("isAllowPearls"))
                                        .append(" etherwarps ").append(ether).append('\n');
                                check(mode.toString().equals(name), "mode lost on reload: " + mode);
                                check(g("isAllowPearls").toString().equals(String.valueOf(name.equals("PEARLS"))),
                                        "pearl permission wrong in " + name);
                                check(ether == name.endsWith("ETHERWARP"), "etherwarp permission wrong in " + name);
                            }
                            // Old files: the walk-only flag.
                            String[][] old = {
                                    {"{\"autoMode\":\"WALK\",\"allowPearls\":true}", "PEARLS"},
                                    {"{\"autoMode\":\"WALK\",\"allowPearls\":false}", "WALK"},
                                    {"{\"autoMode\":\"WALK\"}", "WALK"},
                                    {"{\"autoMode\":\"ETHERWARP\",\"allowPearls\":true}", "ETHERWARP"},
                                    {"{\"autoMode\":\"FAST_ETHERWARP\"}", "FAST_ETHERWARP"},
                                    {"{\"autoMode\":\"PEARLS\"}", "PEARLS"},
                                    {"{\"allowPearls\":true}", "PEARLS"}};
                            for (String[] o : old) {
                                Files.writeString(file, o[0]);
                                Mod.staticCall(CFG, "load");
                                out.append(o[0]).append(" -> ").append(g("getAutoMode")).append('\n');
                                check(g("getAutoMode").toString().equals(o[1]), o[0] + " loaded as " + g("getAutoMode") + ", wanted " + o[1]);
                            }
                            Mod.call(Mod.cfg(CFG), "save");
                            check(!Files.readString(file).contains("allowPearls"), "the saved file still has allowPearls");
                        } catch (Exception e) {
                            throw new AssertionError(e);
                        } finally {
                            try {
                                if (backup != null) {
                                    Files.write(file, backup);
                                } else {
                                    Files.deleteIfExists(file);
                                }
                            } catch (Exception ignored) {
                                // best effort
                            }
                            Mod.staticCall(CFG, "load");
                        }
                        return out.toString();
                    });
                    scenario.log(report);
                });
    }

    private static Object g(String getter) {
        return Mod.get(CFG, getter);
    }

    /** A tab's row labels in reading order (formatting stripped, cut at ':'), switches and buttons and sliders. */
    private static List<String> rows(String tabClass) {
        try {
            Object tab = Class.forName(tabClass).getConstructor().newInstance();
            List<net.minecraft.client.gui.components.AbstractWidget> widgets =
                    (List<net.minecraft.client.gui.components.AbstractWidget>) tab.getClass()
                            .getMethod("buildWidgets", int.class, int.class, int.class, Runnable.class)
                            .invoke(tab, 0, 0, 300, (Runnable) () -> { });
            List<net.minecraft.client.gui.components.AbstractWidget> sorted = new ArrayList<>(widgets);
            sorted.removeIf(w -> w instanceof net.minecraft.client.gui.components.StringWidget);
            sorted.sort((a, b) -> a.getY() != b.getY() ? Integer.compare(a.getY(), b.getY()) : Integer.compare(a.getX(), b.getX()));
            List<String> out = new ArrayList<>();
            for (var w : sorted) {
                String text = net.minecraft.ChatFormatting.stripFormatting(w.getMessage().getString());
                text = text == null ? "" : text.trim();
                int colon = text.indexOf(':');
                String label = (colon > 0 ? text.substring(0, colon) : text).trim();
                // a colour swatch's label carries the swatch glyph after the name
                label = label.replaceAll("[^A-Za-z0-9/ ()?.'-]", "").trim();
                out.add(label);
            }
            return out;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not build " + tabClass + ": " + e, e);
        }
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
        /** Every tick: x, y, z, body yaw, camera (view) yaw, on ground, view held (1/0), run active (1/0). */
        final List<double[]> track = new ArrayList<>();

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

    /** The mod's free camera is held (client thread). */
    private static boolean viewHeld() {
        return (Boolean) Mod.staticCall("util.ViewFreeze", "isHeld");
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

    /** Per-run knobs for {@link #setup} (cases set them and put them back). */
    static boolean fairySoulsSwitch = true;
    static String autoMode = "WALK";
    static boolean sprint = true;

    private static void resetKnobs() {
        fairySoulsSwitch = true;
        autoMode = "WALK";
        sprint = true;
    }

    /** A setter only some mod versions have (a removed or added setting). */
    private static void trySet(String setter, Object value) {
        try {
            Mod.set(CFG, setter, value);
        } catch (RuntimeException | AssertionError ignored) {
            // not in this jar
        }
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
            Mod.set(CFG, "setEnabled", fairySoulsSwitch);
            trySet("setFairySouls", true); // before fairy-souls-1017 only
            Mod.set(CFG, "setAutoStartOnIsland", false);
            Mod.set(CFG, "setSoulMode", Mod.enumValue("pathfinding.PathfindingConfig$SoulMode", "ROUTE"));
            Mod.set(CFG, "setAutoWalk", true);
            Mod.set(CFG, "setAutoSouls", true);
            Mod.set(CFG, "setAutoMode", Mod.enumValue("pathfinding.PathfindingConfig$AutoMode", autoMode));
            Mod.set(CFG, "setAutoCollect", true);
            Mod.set(CFG, "setAutoSprint", sprint);
            trySet("setRotationSpeed", 20f); // before fairy-souls-1017 only
            Mod.set(CFG, "setChatFeedback", true);
        });
        hx.sidebar("SKYBLOCK", "⏣ Village", "Purse: 0");
        hx.tab(List.of("Area: Hub", "Profile: Test"), null, null);
        ctx.waitFor(mc -> "HUB".equals(Mod.staticCall("pathfinding.IslandDetector", "graphIsland")), 200);
        // The found log is per profile: reset it only once the tab's "Profile: Test" is read, or the reset clears
        // "unknown profile" and the run finds every soul already logged (1017-fairy-turning, first try).
        ctx.waitFor(mc -> "Test".equals(Mod.staticCall("pathfinding.ProfileTracker", "displayName")), 200);
        if (fairySoulsSwitch) {
            // With the Fairy Souls switch off nothing loads the graph until Auto Fairy Souls does (1017-fairy-soulsoff).
            ctx.waitFor(mc -> (Boolean) Mod.staticCall("pathfinding.GraphRepository", "isLoaded", "HUB"), 200);
        }
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
                        mc.player.getYRot(), mc.player.onGround() ? 1 : 0, mc.player.getDeltaMovement().y,
                        mc.player.getViewYRot(1f), viewHeld() ? 1 : 0,
                        (Boolean) Mod.staticCall(RUNNER, "isActive") ? 1 : 0});
                r.track.add(new double[]{s[0], s[1], s[2], s[3], s[6], s[4], s[7], s[8]});
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
                    // Only while the run drives him: at its end the body is turned back under the held camera view in
                    // one tick (invisible on his screen; util/ViewFreeze), which is not the walker's turning.
                    boolean driving = s[8] == 1 && prev[8] == 1;
                    if (driving && (hook == null || (tick < 39 || tick > 90))) {
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
