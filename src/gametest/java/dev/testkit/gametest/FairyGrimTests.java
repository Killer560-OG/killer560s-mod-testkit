package dev.testkit.gametest;

import dev.testkit.gametest.mod.Mod;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 1102-fairy-grim-*: where the GrimAC Simulation lines seen on doc-fairy-1101's island runs come from (killer560's rule:
 * any flag is a bug; one that comes only from the test's own teleport is proven with a control and documented).
 * Every case records the anticheat's lines instead of failing on them (runExpectingFlags) and logs each with the tick
 * of the nearest event the case made, so the runs can be compared jar against jar and against a no-automation control.
 * <ul>
 *   <li>tp-walker: Auto Fairy Souls sprints down a 70-block lane; every 50 ticks the SERVER teleports him back to the
 *       start (what the island case does to answer /warp hub), six times, then lets him reach the soul.</li>
 *   <li>tp-keys: the control - no automation at all, the case itself holds W and sprint, same six teleports.</li>
 *   <li>water: Auto Fairy Souls walks a lane crossed by three generator-style pools (2 deep, 3 long, banks at floor
 *       level) to a soul and back to another, so he swims in and climbs out six times.</li>
 * </ul>
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class FairyGrimTests implements FabricClientGameTest {

    private static final int X0 = -930;
    private static final int Z0 = 2600;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Mod.isCheat()) {
            for (String n : List.of("1102-fairy-grim-tp-walker", "1102-fairy-grim-tp-keys", "1102-fairy-grim-water")) {
                if (!Scenario.skip(n)) {
                    Scenario.skipped(n, "Auto Fairy Souls is cheat build only");
                }
            }
            return;
        }
        tpWalker(ctx);
        tpKeys(ctx);
        water(ctx);
    }

    private static TestMap lane(TestServer server, int z) {
        return TestMap.on(server)
                .floor(X0 - 4, 150, z - 3, X0 + 75, z + 3)
                .fill(X0 - 4, 151, z - 3, X0 + 75, 156, z + 3, "air")
                .survival().surface(151).spawn(X0 + 0.5, z + 0.5, -90f);
    }

    private static void report(Scenario scenario, String what, List<Integer> eventTicks, long startMs) {
        List<String> flags = scenario.flags();
        scenario.log(what + ": " + flags.size() + " anticheat line(s); case events at ticks " + eventTicks);
        for (String f : flags) {
            scenario.log("FLAG " + f);
        }
    }

    /** Teleports at 50, 100, ... 300 ticks: back to the lane's start. */
    private static void tpWalker(ClientGameTestContext ctx) {
        int z = Z0;
        Scenario.runExpectingFlags(ctx, "1102-fairy-grim-tp-walker", (server, scenario) -> lane(server, z).build(),
                (server, scenario) -> {
                    List<Integer> tps = new ArrayList<>();
                    List<FairySoulTests.Node> g = List.of(
                            new FairySoulTests.Node(0, X0, 151, z, false, 1),
                            new FairySoulTests.Node(1, X0 + 35, 151, z, false, 0, 2),
                            new FairySoulTests.Node(2, X0 + 70, 151, z, true, 1));
                    long t0 = System.currentTimeMillis();
                    FairySoulTests.Result r = FairySoulTests.runSouls(ctx, scenario, g, 900, tick -> {
                        if (tick > 0 && tick % 50 == 0 && tick <= 300) {
                            tps.add(tick);
                            server.command(String.format(Locale.ROOT, "tp @p %.2f 151 %.2f", X0 + 0.5, z + 0.5));
                        }
                    });
                    scenario.log(r.summary() + ", teleports " + tps.size());
                    report(scenario, "tp-walker", tps, t0);
                    if (r.travelled < 60) {
                        throw new AssertionError("the walker barely moved (" + r.travelled + ") - the case proves nothing");
                    }
                });
    }

    /** No automation: W + sprint held by the case, same teleports. */
    private static void tpKeys(ClientGameTestContext ctx) {
        int z = Z0 + 20;
        Scenario.runExpectingFlags(ctx, "1102-fairy-grim-tp-keys", (server, scenario) -> lane(server, z).build(),
                (server, scenario) -> {
                    List<Integer> tps = new ArrayList<>();
                    double travelled = 0;
                    double[] prev = scenario.playerPosition();
                    ctx.getInput().holdKey(o -> o.keyUp);
                    ctx.getInput().holdKey(o -> o.keySprint);
                    try {
                        for (int tick = 1; tick <= 340; tick++) {
                            ctx.waitTick();
                            if (tick % 50 == 0 && tick <= 300) {
                                tps.add(tick);
                                server.command(String.format(Locale.ROOT, "tp @p %.2f 151 %.2f", X0 + 0.5, z + 0.5));
                            }
                            double[] p = scenario.playerPosition();
                            double step = Math.hypot(p[0] - prev[0], p[2] - prev[2]);
                            if (step < 3) {
                                travelled += step;
                            }
                            prev = p;
                        }
                    } finally {
                        ctx.getInput().releaseKey(o -> o.keyUp);
                        ctx.getInput().releaseKey(o -> o.keySprint);
                    }
                    scenario.log(String.format(Locale.US, "tp-keys: traveled %.1f blocks, teleports %d", travelled, tps.size()));
                    report(scenario, "tp-keys", tps, 0);
                    if (travelled < 60) {
                        throw new AssertionError("the held keys barely moved him (" + travelled + ") - the control proves nothing");
                    }
                });
    }

    /** Three pools across the lane; a soul past them, then one back at the start. */
    private static void water(ClientGameTestContext ctx) {
        int z = Z0 + 40;
        Scenario.runExpectingFlags(ctx, "1102-fairy-grim-water", (server, scenario) -> {
                    TestMap m = lane(server, z);
                    for (int px : new int[]{X0 + 12, X0 + 30, X0 + 48}) {
                        // floor at 150 is the walking surface's block; the pool's bottom at 148, water at 149 and 150
                        m.fill(px, 148, z - 3, px + 2, 148, z + 3, "stone")
                                .fill(px, 149, z - 3, px + 2, 150, z + 3, "water");
                    }
                    m.build();
                },
                (server, scenario) -> {
                    List<FairySoulTests.Node> out = List.of(
                            new FairySoulTests.Node(0, X0, 151, z, false, 1),
                            new FairySoulTests.Node(1, X0 + 35, 151, z, false, 0, 2),
                            new FairySoulTests.Node(2, X0 + 64, 151, z, true, 1));
                    List<FairySoulTests.Node> back = List.of(
                            new FairySoulTests.Node(0, X0 + 64, 151, z, false, 1),
                            new FairySoulTests.Node(1, X0 + 35, 151, z, false, 0, 2),
                            new FairySoulTests.Node(2, X0 + 2, 151, z, true, 1));
                    FairySoulTests.Result r = FairySoulTests.runSouls(ctx, scenario, out, 900, null);
                    scenario.log("out: " + r.summary());
                    FairySoulTests.Result r2 = FairySoulTests.runSouls(ctx, scenario, back, 900, null);
                    scenario.log("back: " + r2.summary());
                    report(scenario, "water", List.of(), 0);
                    if (r.travelled + r2.travelled < 100 || r.soulsUsed + r2.soulsUsed < 2) {
                        throw new AssertionError("he barely moved (" + r.travelled + ") - the case proves nothing");
                    }
                });
    }
}
