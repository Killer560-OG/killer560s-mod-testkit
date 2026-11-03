package dev.testkit.gametest;

import dev.testkit.gametest.mod.Mod;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * 1103-fairy-*: Auto Fairy Souls on the souls killer560 said it still struggles with (2026-11-03: "some are up really high
 * and it just needs to jump knowing it has jump boost", "it still really struggles to get to fairy souls getting into
 * small 1x2 holes and such"). His own log of that day: Jump Boost 6 ("jumps 6.13 blocks"), "no spot near the soul has it
 * in reach and in sight", ways back called "off the way back" three blocks up in the air.
 * <p>
 * Every case uses {@link FairySoulTests}' machinery (its graph file, Hypixel's soul line, real-size stands) and is proved
 * by the server: the use_entity on the stand. Each stand sits where no spot on the route has it in reach, so the walk
 * into the tight place is what is under test. GrimAC (experimental checks on in this checkout) must stay silent.
 * <ul>
 *   <li>boost2-ledge: the stand on a 2-high ledge, the node on the floor in front of it; Jump Boost II.</li>
 *   <li>boost2-none: the same ledge with no Jump Boost: unreachable on foot, so no click may go and the run must give the
 *       soul up (not hang, not click out of reach).</li>
 *   <li>boost6-pillar: his Jump Boost VI. Three 1-block steps on the route (each a 6-block jump), then the stand on a
 *       2x2 pillar 5 blocks high: he must land on its top, not fly past it.</li>
 *   <li>hole: a 1-wide, 2-tall hole in a hillside, the stand 4 blocks in.</li>
 *   <li>hole-raised6: the same hole one block up the hillside, with his Jump Boost VI and Skyblock speed 300: the jump
 *       into it goes 6 blocks high unless he moves in while his head still fits under the hole's roof.</li>
 *   <li>tunnel: a 1x1 shaft dropping 3 blocks into a 1-wide tunnel with a corner, the stand at its end.</li>
 *   <li>overhang: the stand at the back of a 1.5-high gap under top slabs: only a crouching player fits.</li>
 * </ul>
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class FairyTightTests implements FabricClientGameTest {

    private static final String WALKER = "pathfinding.AutoWalker";
    private static final int X0 = -700;
    private static final int Z0 = 3000;

    private static final List<String> NAMES = List.of("1103-fairy-boost2-ledge", "1103-fairy-boost2-none",
            "1103-fairy-boost6-pillar", "1103-fairy-hole", "1103-fairy-hole-raised6", "1103-fairy-tunnel", "1103-fairy-overhang");

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Mod.isCheat()) {
            for (String n : NAMES) {
                if (!Scenario.skip(n)) {
                    Scenario.skipped(n, "Auto Fairy Souls is cheat build only");
                }
            }
            return;
        }
        AssertionError first = null;
        for (Runnable c : List.<Runnable>of(
                () -> ledge(ctx, "1103-fairy-boost2-ledge", X0, Z0, 2, 2, true),
                () -> ledge(ctx, "1103-fairy-boost2-none", X0, Z0 + 60, 2, 0, false),
                () -> pillar(ctx, X0, Z0 + 120),
                () -> hole(ctx, X0, Z0 + 180),
                () -> raisedHole(ctx, X0, Z0 + 360),
                () -> tunnel(ctx, X0, Z0 + 240),
                () -> overhang(ctx, X0, Z0 + 300))) {
            try {
                c.run();
            } catch (AssertionError e) {
                first = first == null ? e : first;
            }
        }
        if (first != null) {
            throw first;
        }
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }

    private static int walkerInt(ClientGameTestContext ctx, String getter) {
        return ctx.computeOnClient(mc -> {
            try {
                return (Integer) Mod.staticCall(WALKER, getter);
            } catch (AssertionError | RuntimeException missing) {
                return 0;
            }
        });
    }

    private static int runnerInt(ClientGameTestContext ctx, String getter) {
        return ctx.computeOnClient(mc -> {
            try {
                return (Integer) Mod.staticCall("pathfinding.AutoSoulRunner", getter);
            } catch (AssertionError | RuntimeException missing) {
                return 0;
            }
        });
    }

    /** Runs the souls with the stand placed apart from its node; the knobs go back afterwards. */
    private static FairySoulTests.Result souls(ClientGameTestContext ctx, Scenario scenario, List<FairySoulTests.Node> g,
                                               int soulId, double[] stand, int maxTicks) {
        FairySoulTests.standAt = new HashMap<>();
        FairySoulTests.standAt.put(soulId, stand);
        FairySoulTests.warpWhenStuck = false;
        FairySoulTests.autoMode = "WALK";
        try {
            return FairySoulTests.runSouls(ctx, scenario, g, maxTicks, null);
        } finally {
            FairySoulTests.standAt = new HashMap<>();
        }
    }

    private static String boost(int level) {
        return level <= 0 ? "effect clear @p" : "effect give @p minecraft:jump_boost 1000 " + (level - 1) + " true";
    }

    /**
     * A ledge {@code rise} high across the way, the soul node on the floor in front of it and the stand 4 blocks back on
     * its top: from the floor its box is 4.5 blocks from the eye.
     */
    private static void ledge(ClientGameTestContext ctx, String name, int x0, int z0, int rise, int boostLevel, boolean expect) {
        int top = 151 + rise;
        Scenario.run(ctx, name,
                (server, scenario) -> TestMap.on(server)
                        .floor(x0 - 10, 150, z0 - 12, x0 + 10, z0 + 12)
                        .fill(x0 - 10, 151, z0 - 12, x0 + 10, top + 8, z0 + 12, "air")
                        .fill(x0 - 4, 151, z0 + 3, x0 + 4, top - 1, z0 + 10, "minecraft:stone")
                        .survival().surface(151).spawn(x0 + 0.5, z0 - 8.5, 0f)
                        .command(boost(boostLevel))
                        .build(),
                (server, scenario) -> {
                    List<FairySoulTests.Node> g = List.of(
                            new FairySoulTests.Node(0, x0, 151, z0 - 9, false, 1),
                            new FairySoulTests.Node(1, x0, 151, z0 - 3, false, 0, 2),
                            new FairySoulTests.Node(2, x0, 151, z0 + 1, true, 1));
                    int before = walkerInt(ctx, "approachesPlanned");
                    int clicksBefore = runnerInt(ctx, "clicksSent");
                    long mark = LogTap.mark();
                    FairySoulTests.Result r = souls(ctx, scenario, g, 2, new double[]{x0 + 0.5, top, z0 + 7.5}, 900);
                    int approaches = walkerInt(ctx, "approachesPlanned") - before;
                    int clicks = runnerInt(ctx, "clicksSent") - clicksBefore;
                    String startLine = LogTap.since(mark).stream().filter(l -> l.contains("Started in mode")).findFirst().orElse("");
                    scenario.log(r.summary() + String.format(Locale.US, ", approaches %d, clicks %d, start line: %s",
                            approaches, clicks, startLine));
                    if (expect) {
                        check(startLine.contains("Jump Boost " + boostLevel), "the start line did not name Jump Boost " + boostLevel);
                        check(r.maxY >= top - 0.01, "never got onto the ledge (max y " + r.maxY + ", top " + top + ")");
                        check(r.soulsUsed >= 1, "the soul on the ledge was not collected: " + r.summary());
                    } else {
                        check(startLine.contains("no jump boost"), "the start line did not say no jump boost: " + startLine);
                        check(r.soulsUsed == 0 && clicks == 0, "a click went to a soul out of reach (" + clicks + ")");
                        check(!r.activeAtEnd, "the run did not give the unreachable soul up");
                    }
                });
    }

    /**
     * Jump Boost VI (his, 2026-11-03), Skyblock speed 300: three 1-block steps on the route, then a 2x2 pillar 5 blocks
     * above the last step with the stand on it. Every jump is 6 blocks high; he must land on the pillar's 2x2 top.
     */
    private static void pillar(ClientGameTestContext ctx, int x0, int z0) {
        Scenario.run(ctx, "1103-fairy-boost6-pillar",
                (server, scenario) -> TestMap.on(server)
                        .floor(x0 - 12, 150, z0 - 14, x0 + 12, z0 + 14)
                        .fill(x0 - 12, 151, z0 - 14, x0 + 12, 168, z0 + 14, "air")
                        // three steps, 3 deep each: feet 152, 153, 154
                        .fill(x0 - 6, 151, z0 - 6, x0 + 6, 151, z0 + 8, "minecraft:stone")
                        .fill(x0 - 6, 152, z0 - 3, x0 + 6, 152, z0 + 8, "minecraft:stone")
                        .fill(x0 - 6, 153, z0, x0 + 6, 153, z0 + 8, "minecraft:stone")
                        // the pillar on the top step: 2x2, top at 159 (5 above the step's 154)
                        .fill(x0, 154, z0 + 5, x0 + 1, 158, z0 + 6, "minecraft:stone_bricks")
                        .survival().surface(151).spawn(x0 + 0.5, z0 - 10.5, 0f)
                        .speed(300)
                        .command(boost(6))
                        .build(),
                (server, scenario) -> {
                    List<FairySoulTests.Node> g = List.of(
                            new FairySoulTests.Node(0, x0, 151, z0 - 10, false, 1),
                            new FairySoulTests.Node(1, x0, 152, z0 - 5, false, 0, 2),
                            new FairySoulTests.Node(2, x0, 153, z0 - 2, false, 1, 3),
                            new FairySoulTests.Node(3, x0, 154, z0 + 2, true, 2));
                    int before = walkerInt(ctx, "approachesPlanned");
                    long mark = LogTap.mark();
                    FairySoulTests.Result r = souls(ctx, scenario, g, 3, new double[]{x0 + 0.5, 159, z0 + 5.5}, 1000);
                    int approaches = walkerInt(ctx, "approachesPlanned") - before;
                    String startLine = LogTap.since(mark).stream().filter(l -> l.contains("Started in mode")).findFirst().orElse("");
                    double[] end = scenario.playerPosition();
                    scenario.log(r.summary() + String.format(Locale.US, ", approaches %d, ended at %.2f %.2f %.2f, start: %s",
                            approaches, end[0], end[1], end[2], startLine));
                    check(startLine.contains("Jump Boost 6"), "the start line did not name Jump Boost 6");
                    check(r.soulsUsed >= 1, "the soul on the pillar was not collected: " + r.summary());
                });
    }

    /** A 1-wide, 2-tall hole in a hillside, 4 deep; the stand at its end. From the mouth its box is 3.5 from the eye. */
    private static void hole(ClientGameTestContext ctx, int x0, int z0) {
        Scenario.run(ctx, "1103-fairy-hole",
                (server, scenario) -> TestMap.on(server)
                        .floor(x0 - 10, 150, z0 - 12, x0 + 10, z0 + 12)
                        .fill(x0 - 10, 151, z0 - 12, x0 + 10, 160, z0 + 12, "air")
                        .fill(x0 - 6, 151, z0 + 2, x0 + 6, 154, z0 + 10, "minecraft:stone")
                        .fill(x0, 151, z0 + 2, x0, 152, z0 + 5, "air")
                        .survival().surface(151).spawn(x0 + 3.5, z0 - 8.5, 0f)
                        .build(),
                (server, scenario) -> {
                    List<FairySoulTests.Node> g = List.of(
                            new FairySoulTests.Node(0, x0 + 3, 151, z0 - 9, false, 1),
                            new FairySoulTests.Node(1, x0 + 3, 151, z0 - 3, false, 0, 2),
                            new FairySoulTests.Node(2, x0 + 1, 151, z0, true, 1));
                    tight(ctx, scenario, g, new double[]{x0 + 0.5, 151, z0 + 5.5}, z0 + 2.5, "into the hole");
                });
    }

    /** The hole one block up the hillside (a jump into it), Jump Boost VI, Skyblock speed 300. */
    private static void raisedHole(ClientGameTestContext ctx, int x0, int z0) {
        Scenario.run(ctx, "1103-fairy-hole-raised6",
                (server, scenario) -> TestMap.on(server)
                        .floor(x0 - 10, 150, z0 - 12, x0 + 10, z0 + 12)
                        .fill(x0 - 10, 151, z0 - 12, x0 + 10, 166, z0 + 12, "air")
                        .fill(x0 - 6, 151, z0 + 2, x0 + 6, 155, z0 + 10, "minecraft:stone")
                        .fill(x0, 152, z0 + 2, x0, 153, z0 + 5, "air")
                        .survival().surface(151).spawn(x0 + 3.5, z0 - 8.5, 0f)
                        .speed(300)
                        .command(boost(6))
                        .build(),
                (server, scenario) -> {
                    List<FairySoulTests.Node> g = List.of(
                            new FairySoulTests.Node(0, x0 + 3, 151, z0 - 9, false, 1),
                            new FairySoulTests.Node(1, x0 + 3, 151, z0 - 3, false, 0, 2),
                            new FairySoulTests.Node(2, x0 + 1, 151, z0, true, 1));
                    tight(ctx, scenario, g, new double[]{x0 + 0.5, 152, z0 + 5.5}, z0 + 2.5, "into the raised hole");
                });
    }

    /** A 1x1 shaft from the top of a block mass down 3 into a 1-wide tunnel that turns once; the stand at its end. */
    private static void tunnel(ClientGameTestContext ctx, int x0, int z0) {
        Scenario.run(ctx, "1103-fairy-tunnel",
                (server, scenario) -> TestMap.on(server)
                        .floor(x0 - 10, 150, z0 - 12, x0 + 10, z0 + 14)
                        .fill(x0 - 10, 151, z0 - 12, x0 + 10, 162, z0 + 14, "air")
                        .fill(x0 - 8, 151, z0 - 4, x0 + 8, 153, z0 + 12, "minecraft:stone")
                        .fill(x0, 151, z0 + 2, x0, 153, z0 + 2, "air")
                        .fill(x0, 151, z0 + 2, x0, 152, z0 + 7, "air")
                        .fill(x0, 151, z0 + 7, x0 + 5, 152, z0 + 7, "air")
                        .survival().surface(154).spawn(x0 - 4.5, z0 - 2.5, 0f)
                        .build(),
                (server, scenario) -> {
                    List<FairySoulTests.Node> g = List.of(
                            new FairySoulTests.Node(0, x0 - 5, 154, z0 - 3, false, 1),
                            new FairySoulTests.Node(1, x0 - 2, 154, z0 + 1, false, 0, 2),
                            new FairySoulTests.Node(2, x0 + 2, 154, z0 + 4, true, 1));
                    tight(ctx, scenario, g, new double[]{x0 + 5.5, 151, z0 + 7.5}, -1, "down the shaft and along the tunnel");
                });
    }

    /** Top slabs 1.5 above the floor over a 5x6 pocket; the stand at its back. Only crouched does he fit under. */
    private static void overhang(ClientGameTestContext ctx, int x0, int z0) {
        Scenario.run(ctx, "1103-fairy-overhang",
                (server, scenario) -> TestMap.on(server)
                        .floor(x0 - 10, 150, z0 - 12, x0 + 10, z0 + 12)
                        .fill(x0 - 10, 151, z0 - 12, x0 + 10, 160, z0 + 12, "air")
                        .fill(x0 - 3, 151, z0 + 3, x0 + 3, 154, z0 + 9, "minecraft:stone")
                        .fill(x0 - 2, 151, z0 + 3, x0 + 2, 152, z0 + 8, "air")
                        .command(String.format(Locale.ROOT, "fill %d 152 %d %d 152 %d minecraft:stone_slab[type=top]",
                                x0 - 2, z0 + 3, x0 + 2, z0 + 8))
                        .survival().surface(151).spawn(x0 + 0.5, z0 - 8.5, 0f)
                        .build(),
                (server, scenario) -> {
                    List<FairySoulTests.Node> g = List.of(
                            new FairySoulTests.Node(0, x0, 151, z0 - 9, false, 1),
                            new FairySoulTests.Node(1, x0, 151, z0 - 3, false, 0, 2),
                            new FairySoulTests.Node(2, x0, 151, z0 + 1, true, 1));
                    tight(ctx, scenario, g, new double[]{x0 + 0.5, 151, z0 + 7.5}, z0 + 3.5, "under the overhang");
                });
    }

    /** The common check of the tight cases: collected, and (when {@code pastZ} > 0) he got past that z to do it. */
    private static void tight(ClientGameTestContext ctx, Scenario scenario, List<FairySoulTests.Node> g, double[] stand,
                              double pastZ, String where) {
        int before = walkerInt(ctx, "approachesPlanned");
        int crouchBefore = walkerInt(ctx, "crouchTicks");
        FairySoulTests.Result r = souls(ctx, scenario, g, g.get(g.size() - 1).id(), stand, 900);
        int approaches = walkerInt(ctx, "approachesPlanned") - before;
        int crouch = walkerInt(ctx, "crouchTicks") - crouchBefore;
        double maxZ = r.track.stream().mapToDouble(t -> t[2]).max().orElse(0);
        double minY = r.track.stream().mapToDouble(t -> t[1]).min().orElse(0);
        double[] end = scenario.playerPosition();
        scenario.log(r.summary() + String.format(Locale.US, ", approaches %d, crouch ticks %d, furthest z %.2f, lowest y %.2f, "
                + "ended at %.2f %.2f %.2f", approaches, crouch, maxZ, minY, end[0], end[1], end[2]));
        if (pastZ > 0) {
            check(maxZ > pastZ, "he never got " + where + " (furthest z " + maxZ + ")");
        }
        check(r.soulsUsed >= 1, "the soul " + where + " was not collected: " + r.summary());
    }
}
