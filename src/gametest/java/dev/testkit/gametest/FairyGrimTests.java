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
            for (String n : List.of("1102-fairy-grim-tp-walker", "1102-fairy-grim-tp-keys", "1102-fairy-grim-water",
                    "1102-fairy-grim-ladderwater-keys", "1102-fairy-grim-ladderwater-walker",
                    "1102-fairy-grim-flowladder-keys", "1102-fairy-grim-flowladder-walker", "1102-fairy-arrival")) {
                if (!Scenario.skip(n)) {
                    Scenario.skipped(n, "Auto Fairy Souls is cheat build only");
                }
            }
            return;
        }
        AssertionError first = null;
        for (java.util.function.Consumer<ClientGameTestContext> c : List.<java.util.function.Consumer<ClientGameTestContext>>of(
                FairyGrimTests::tpWalker, FairyGrimTests::tpKeys, FairyGrimTests::water, FairyGrimTests::ladderWaterKeys,
                FairyGrimTests::ladderWaterWalker, FairyGrimTests::flowLadderKeys, FairyGrimTests::flowLadderWalker,
                FairyGrimTests::arrival)) {
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

    /** Arrivals per 1102-fairy-arrival scenario (-Pfairy.arrivals, default 6: keep a session to 7 scenarios or fewer). */
    private static int arrivals() {
        return Integer.getInteger("testkit.fairy.arrivals", 6);
    }

    /**
     * 1102-fairy-arrival: the walker from a standstill 5-10 blocks from a soul on flat ground, W+sprint up to it and the stop
     * where the soul comes in reach - the stop BAYOU_SPILL's soul 3 drew Simulation .046 decaying x0.546 plus SprintE* on
     * (twice in ~50). Repeated -Pfairy.arrivals times from varied distances and headings; every arrival must collect the
     * soul, sprint must have been on during the approach, and GrimAC (experimental checks on) must stay silent. The last
     * 40 ticks before any line, with keys, sprint state and velocity, are logged.
     */
    private static void arrival(ClientGameTestContext ctx) {
        int x0 = X0 + 60;
        int z0 = Z0 + 260;
        Scenario.runExpectingFlags(ctx, "1102-fairy-arrival", (server, scenario) -> TestMap.on(server)
                        .floor(x0 - 16, 150, z0 - 16, x0 + 16, z0 + 16)
                        .fill(x0 - 16, 151, z0 - 16, x0 + 16, 156, z0 + 16, "air")
                        .survival().surface(151).spawn(x0 + 0.5, z0 + 0.5, 0f).build(),
                (server, scenario) -> {
                    java.util.Random rnd = new java.util.Random(Long.getLong("testkit.fairy.arrivalSeed", System.nanoTime()));
                    int collected = 0;
                    int sprinted = 0;
                    List<Integer> flagsAfter = new ArrayList<>();
                    for (int run = 0; run < arrivals(); run++) {
                        double dist = 5 + rnd.nextDouble() * 5;
                        double ang = rnd.nextDouble() * Math.PI * 2;
                        int sx = x0 + (int) Math.round(Math.cos(ang) * dist);
                        int sz = z0 + (int) Math.round(Math.sin(ang) * dist);
                        float face = (float) (rnd.nextDouble() * 360 - 180);
                        server.command(String.format(Locale.ROOT, "tp @p %.2f 151 %.2f %.1f 0", sx + 0.5, sz + 0.5, face));
                        ctx.waitTicks(30);
                        FairySoulTests.standAt = new java.util.HashMap<>();
                        FairySoulTests.standAt.put(1, new double[]{x0 + 1.5, 151.0, z0 + 0.5});
                        List<FairySoulTests.Node> g = List.of(
                                new FairySoulTests.Node(0, sx, 151, sz, false, 1),
                                new FairySoulTests.Node(1, x0, 151, z0, true, 0));
                        List<String> trace = new ArrayList<>();
                        boolean[] sprint = {false};
                        int before = scenario.flags().size();
                        int[] flagTick = {-1};
                        FairySoulTests.Result r = FairySoulTests.runSouls(ctx, scenario, g, 400, tick -> {
                            String line = ctx.computeOnClient(mc -> {
                                var p = mc.player;
                                var in = p.input.keyPresses;
                                return String.format(Locale.US, "t%d pos %.4f %.4f v %.4f %.4f yaw %.2f ground %s sprinting %s "
                                                + "speed %.5f hcol %s minor %s keys %s%s%s%s%s%s", tick, p.getX(), p.getZ(),
                                        p.getDeltaMovement().x, p.getDeltaMovement().z, p.getYRot(), p.onGround(), p.isSprinting(),
                                        p.getSpeed(), p.horizontalCollision, p.minorHorizontalCollision,
                                        in.forward() ? "W" : "", in.backward() ? "S" : "", in.left() ? "A" : "", in.right() ? "D" : "",
                                        in.jump() ? "J" : "", in.sprint() ? "+spr" : "");
                            });
                            sprint[0] |= line.contains("sprinting true");
                            trace.add(line);
                            if (flagTick[0] < 0 && scenario.flags().size() > before) {
                                flagTick[0] = tick;
                            }
                        });
                        collected += r.soulsUsed;
                        if (sprint[0]) {
                            sprinted++;
                        }
                        int after = scenario.flags().size();
                        flagsAfter.add(after);
                        scenario.log(String.format(Locale.US, "arrival %d: from %d %d (%.1f blocks), soul %d, sprinted %s, ticks %d, "
                                + "anticheat lines so far %d", run, sx, sz, dist, r.soulsUsed, sprint[0], r.ticks, after));
                        if (after > before) {
                            int end = flagTick[0] < 0 ? trace.size() : Math.min(trace.size(), flagTick[0] + 3);
                            scenario.log("arrival " + run + ": anticheat line first seen at t" + flagTick[0] + "; trace:");
                            for (String l : trace.subList(Math.max(0, end - 40), end)) {
                                scenario.log("  " + l);
                            }
                        } else if (run < 2) {
                            for (String l : trace.subList(Math.max(0, trace.size() - 25), trace.size())) {
                                scenario.log("  " + l);
                            }
                        }
                    }
                    FairySoulTests.standAt = new java.util.HashMap<>();
                    scenario.log(String.format(Locale.US, "arrival: %d runs, souls %d, sprinted %d, lines after each run %s",
                            arrivals(), collected, sprinted, flagsAfter));
                    report(scenario, "arrival", List.of(), 0);
                    if (collected < arrivals() || sprinted < arrivals() || !scenario.flags().isEmpty()) {
                        throw new AssertionError("arrival: souls " + collected + "/" + arrivals() + ", sprinted " + sprinted
                                + ", anticheat lines " + scenario.flags().size());
                    }
                });
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

    /**
     * Backwater Bayou's spot (1101-fairy-island-BACKWATER_BAYOU, 9-17 Simulation lines, .007 decaying x0.8 a tick): a
     * ladder up a 6-high cliff whose top two blocks have water right beside them. The cliff top is a platform at feet 157.
     */
    private static TestMap ladderWater(TestServer server, int x0, int z0) {
        return TestMap.on(server)
                .floor(x0 - 8, 150, z0 - 6, x0 + 12, z0 + 6)
                .fill(x0 - 8, 151, z0 - 6, x0 + 12, 162, z0 + 6, "air")
                .fill(x0 + 1, 151, z0 - 4, x0 + 12, 156, z0 + 4, "stone")
                .command(String.format(Locale.ROOT, "fill %d 151 %d %d 156 %d minecraft:ladder[facing=west]", x0, z0, x0, z0))
                // the water beside the ladder's top two blocks, in a stone tub
                .fill(x0 - 1, 154, z0 - 3, x0 + 1, 156, z0 - 1, "stone")
                .fill(x0, 155, z0 - 2, x0, 156, z0 - 1, "water")
                .survival().surface(151).spawn(x0 - 1.5, z0 + 0.5, -90f);
    }

    /** The control: no automation, the case holds W and jump facing the ladder for 80 ticks. */
    private static void ladderWaterKeys(ClientGameTestContext ctx) {
        int x0 = X0 + 10;
        int z0 = Z0 + 70;
        Scenario.runExpectingFlags(ctx, "1102-fairy-grim-ladderwater-keys", (server, scenario) -> ladderWater(server, x0, z0).build(),
                (server, scenario) -> {
                    double y0 = scenario.playerPosition()[1];
                    double top = y0;
                    ctx.getInput().holdKey(o -> o.keyUp);
                    ctx.getInput().holdKey(o -> o.keyJump);
                    try {
                        for (int tick = 0; tick < 80; tick++) {
                            ctx.waitTick();
                            top = Math.max(top, scenario.playerPosition()[1]);
                        }
                    } finally {
                        ctx.getInput().releaseKey(o -> o.keyUp);
                        ctx.getInput().releaseKey(o -> o.keyJump);
                    }
                    ctx.waitTicks(20);
                    scenario.log(String.format(Locale.US, "ladderwater-keys: climbed from %.2f to %.2f", y0, top));
                    report(scenario, "ladderwater-keys", List.of(), 0);
                    if (top < y0 + 4) {
                        throw new AssertionError("he never climbed the ladder (top " + top + ") - the control proves nothing");
                    }
                });
    }

    /** Repeats of each flowing-water ladder case per run (-Pfairy.flowRuns, default 10). */
    /**
     * On a ladder with water in his box from a column other than the one he stands in - a current from the side, the state
     * GrimAC flags for vanilla keys too (BAYOU_SPILL). Water in the ladder's own column (flowing over its top) is not it:
     * 1102-fairy-grim-flowladder-keys/-walker sit in that every run and are clean.
     */
    static boolean sideWaterOnLadder(net.minecraft.client.Minecraft mc) {
        var p = mc.player;
        if (!p.onClimbable() || !p.isInWater()) {
            return false;
        }
        var box = p.getBoundingBox().deflate(0.001);
        int cx = (int) Math.floor(p.getX());
        int cz = (int) Math.floor(p.getZ());
        for (int x = (int) Math.floor(box.minX); x <= (int) Math.floor(box.maxX); x++) {
            for (int z = (int) Math.floor(box.minZ); z <= (int) Math.floor(box.maxZ); z++) {
                if (x == cx && z == cz) {
                    continue;
                }
                for (int y = (int) Math.floor(box.minY); y <= (int) Math.floor(box.maxY); y++) {
                    if (!mc.level.getFluidState(new net.minecraft.core.BlockPos(x, y, z)).isEmpty()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static int flowRuns() {
        return Integer.getInteger("testkit.fairy.flowRuns", 10);
    }

    /**
     * Backwater Bayou's spot, hand-built (killer560's coordinator, 2026-11-02): a 6-high ladder up a cliff, and a water SOURCE on
     * the cliff top two blocks back from the edge, in a one-wide stone channel that runs to the ladder's top, so FLOWING water
     * covers the cell above the ladder: whoever climbs it comes up into a current pushing back toward the ladder, then walks
     * against it to the platform past the source. The channel's mouth over the ladder is walled so nothing spills to the floor.
     */
    private static TestMap flowLadder(TestServer server, int x0, int z0) {
        return TestMap.on(server)
                .floor(x0 - 8, 150, z0 - 6, x0 + 14, z0 + 6)
                .fill(x0 - 8, 151, z0 - 6, x0 + 14, 164, z0 + 6, "air")
                .fill(x0 + 1, 151, z0 - 4, x0 + 14, 156, z0 + 4, "stone")
                .command(String.format(Locale.ROOT, "fill %d 151 %d %d 156 %d minecraft:ladder[facing=west]", x0, z0, x0, z0))
                // the channel on the cliff top: walls at z0-1 and z0+1 from the ladder's column to x0+3, and over the
                // ladder's top a stop on its west side (the flow sits over the ladder, it does not fall to the floor)
                .fill(x0, 157, z0 - 1, x0 + 3, 158, z0 - 1, "stone")
                .fill(x0, 157, z0 + 1, x0 + 3, 158, z0 + 1, "stone")
                .fill(x0 - 1, 157, z0 - 1, x0 - 1, 158, z0 + 1, "stone")
                .command(String.format(Locale.ROOT, "setblock %d 157 %d minecraft:water", x0 + 2, z0))
                .survival().surface(151).spawn(x0 - 1.5, z0 + 0.5, -90f);
    }

    /** The control: no automation. The case holds W and jump facing the ladder (east) for 140 ticks, a human's climb. */
    private static void flowLadderKeys(ClientGameTestContext ctx) {
        int x0 = X0 + 30;
        int z0 = Z0 + 150;
        Scenario.runExpectingFlags(ctx, "1102-fairy-grim-flowladder-keys", (server, scenario) -> flowLadder(server, x0, z0).build(),
                (server, scenario) -> {
                    ctx.waitTicks(60); // the flow settles
                    int climbed = 0;
                    int wet = 0;
                    List<Integer> flagsAfter = new ArrayList<>();
                    for (int run = 0; run < flowRuns(); run++) {
                        server.command(String.format(Locale.ROOT, "tp @p %.2f 151 %.2f -90 0", x0 - 1.5, z0 + 0.5));
                        ctx.waitTicks(30);
                        double top = 0;
                        boolean inWater = false;
                        ctx.getInput().holdKey(o -> o.keyUp);
                        ctx.getInput().holdKey(o -> o.keyJump);
                        try {
                            for (int tick = 0; tick < 140; tick++) {
                                ctx.waitTick();
                                double[] p = scenario.playerPosition();
                                top = Math.max(top, p[1]);
                                inWater |= ctx.computeOnClient(mc -> mc.player.isInWater());
                            }
                        } finally {
                            ctx.getInput().releaseKey(o -> o.keyUp);
                            ctx.getInput().releaseKey(o -> o.keyJump);
                        }
                        ctx.waitTicks(20);
                        double[] end = scenario.playerPosition();
                        if (top >= 156.9) {
                            climbed++;
                        }
                        if (inWater) {
                            wet++;
                        }
                        flagsAfter.add(scenario.flags().size());
                        scenario.log(String.format(Locale.US, "flowladder-keys run %d: top %.2f, end %.2f %.2f %.2f, in the current %s, "
                                + "anticheat lines so far %d", run, top, end[0], end[1], end[2], inWater, scenario.flags().size()));
                    }
                    scenario.log(String.format(Locale.US, "flowladder-keys: %d runs, climbed %d, in the current %d, lines after each run %s",
                            flowRuns(), climbed, wet, flagsAfter));
                    report(scenario, "flowladder-keys", List.of(), 0);
                    if (climbed < flowRuns() || wet < flowRuns()) {
                        throw new AssertionError("the control did not climb into the current every run (climbed " + climbed
                                + ", wet " + wet + ") - it proves nothing for those runs");
                    }
                });
    }

    /**
     * The walker: a route up the same ladder, through the current, to a soul past the source; repeated. Requires the soul
     * every run and ZERO ticks on the ladder with water from a SIDE column in his box (sideWaterOnLadder, the state GrimAC
     * flags). The water over the ladder's own top is in his box for about 12 ticks of every climb (logged): there is no other
     * way up, and keys and walker are clean in it.
     */
    private static void flowLadderWalker(ClientGameTestContext ctx) {
        int x0 = X0 + 30;
        int z0 = Z0 + 180;
        Scenario.runExpectingFlags(ctx, "1102-fairy-grim-flowladder-walker", (server, scenario) -> flowLadder(server, x0, z0).build(),
                (server, scenario) -> {
                    ctx.waitTicks(60);
                    int collected = 0;
                    int wet = 0;
                    int ladderWetTotal = 0;
                    int sideWetTotal = 0;
                    List<Integer> flagsAfter = new ArrayList<>();
                    for (int run = 0; run < flowRuns(); run++) {
                        server.command(String.format(Locale.ROOT, "tp @p %.2f 151 %.2f -90 0", x0 - 1.5, z0 + 0.5));
                        ctx.waitTicks(30);
                        List<FairySoulTests.Node> g = List.of(
                                new FairySoulTests.Node(0, x0 - 2, 151, z0, false, 1),
                                new FairySoulTests.Node(1, x0 + 1, 157, z0, false, 0, 2),
                                new FairySoulTests.Node(2, x0 + 4, 157, z0, false, 1, 3),
                                new FairySoulTests.Node(3, x0 + 9, 157, z0, true, 2));
                        boolean[] inWater = {false};
                        int[] ladderWet = {0, 0};
                        FairySoulTests.Result r = FairySoulTests.runSouls(ctx, scenario, g, 500, tick -> {
                            boolean[] st = ctx.computeOnClient(mc -> new boolean[]{mc.player.isInWater(), mc.player.onClimbable(),
                                    sideWaterOnLadder(mc)});
                            inWater[0] |= st[0];
                            if (st[0] && st[1]) {
                                ladderWet[0]++;
                            }
                            if (st[2]) {
                                ladderWet[1]++;
                            }
                        });
                        ladderWetTotal += ladderWet[0];
                        sideWetTotal += ladderWet[1];
                        collected += r.soulsUsed;
                        if (inWater[0]) {
                            wet++;
                        }
                        flagsAfter.add(scenario.flags().size());
                        scenario.log(String.format(Locale.US, "flowladder-walker run %d: max y %.2f, soul %d, in the current %s, "
                                + "ticks on the ladder in water %d (from the side %d), anticheat lines so far %d", run, r.maxY,
                                r.soulsUsed, inWater[0], ladderWet[0], ladderWet[1], scenario.flags().size()));
                    }
                    scenario.log(String.format(Locale.US, "flowladder-walker: %d runs, souls %d, in the current %d, ticks on the "
                            + "ladder in water %d (from the side %d), lines after each run %s", flowRuns(), collected, wet,
                            ladderWetTotal, sideWetTotal, flagsAfter));
                    report(scenario, "flowladder-walker", List.of(), 0);
                    if (collected < flowRuns() || sideWetTotal > 0) {
                        throw new AssertionError("the walker did not reach the soul past the current every run, or stood on the "
                                + "ladder with side water in his box (souls " + collected + ", side-water ticks " + sideWetTotal + ")");
                    }
                });
    }

    /** The walker: a route up the same ladder to a soul on the cliff top. */
    private static void ladderWaterWalker(ClientGameTestContext ctx) {
        int x0 = X0 + 10;
        int z0 = Z0 + 90;
        Scenario.runExpectingFlags(ctx, "1102-fairy-grim-ladderwater-walker", (server, scenario) -> ladderWater(server, x0, z0).build(),
                (server, scenario) -> {
                    List<FairySoulTests.Node> g = List.of(
                            new FairySoulTests.Node(0, x0 - 2, 151, z0, false, 1),
                            new FairySoulTests.Node(1, x0 + 2, 157, z0, false, 0, 2),
                            new FairySoulTests.Node(2, x0 + 8, 157, z0, true, 1));
                    FairySoulTests.Result r = FairySoulTests.runSouls(ctx, scenario, g, 600, null);
                    scenario.log("ladderwater-walker: " + r.summary());
                    report(scenario, "ladderwater-walker", List.of(), 0);
                    if (r.maxY < 156) {
                        throw new AssertionError("he never got up the ladder (max y " + r.maxY + ") - the case proves nothing");
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
