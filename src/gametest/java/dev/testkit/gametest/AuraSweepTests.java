package dev.testkit.gametest;

import dev.testkit.harness.PacketWatch;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.List;
import java.util.Locale;

/**
 * A SWEEP across killer560s-mod's interaction automation, looking for the packet-ordering problem that
 * Breaker Aura turned out to have.
 *
 * <p>Breaker Aura ticked on Fabric's {@code END_CLIENT_TICK}, which runs after the player's own movement
 * packet, and GrimAC flagged every single break as {@code Post - player digging}. About twenty other
 * interaction-sending features in the mod tick in the same place, so the same fault is likely in all of them
 * - but "likely" is the reason this file exists rather than a patch. One measured feature is a finding; twenty
 * inferred ones are a guess.
 *
 * <h2>Why these use runExpectingFlags</h2>
 * A sweep that aborts on its first finding only ever reports one. These scenarios record what the anticheat
 * said instead of asserting silence, so a single pass covers every feature. They still fail hard on the one
 * thing that would make a result meaningless: the feature not acting at all. A module that never ran produces
 * a spotless anticheat log, and that is the most dangerous output this harness can print.
 *
 * <h2>Getting the mod to run at all</h2>
 * These features are gated on being in a dungeon on Hypixel. Two of the mod's own real switches open that up
 * without touching feature code: {@code DungeonState.toggleSimOverride()} (its {@code /killer560 sim} command)
 * and labelling the connection so {@code getCurrentServer().ip} contains {@code p3sim.net}, which is the check
 * the features actually read and the one thing they have no override for. Neither changes a byte of what the
 * server receives.
 */
public class AuraSweepTests implements FabricClientGameTest {

    private static final String MOD_ID = "killer560smod";
    /** The client stores this as the server address; it still dials localhost. p3sim is a real practice
     *  server the mod supports, and its dungeon features refuse to run anywhere else. */
    private static final String DUNGEON_LABEL = "localhost.p3sim.net:25565";

    private static final int SURFACE_Y = 151;
    /**
     * The arena is built a long way into NEGATIVE coordinates, and that is load-bearing.
     *
     * <p>The dungeon override forces the floor to F7, and the mod decides whether you are in the BOSS room by
     * real coordinates - for F7, {@code x > -7 && z > -7}. An arena built near the origin therefore reads as
     * the boss room, and Secret Triggerbot's gate is {@code !isInBoss()}: the override that switches the
     * feature's dungeon gate on was simultaneously switching its room gate off, and it sat out the whole run
     * saying "State: in boss". Out here the override gives a dungeon that is not the boss.
     */
    private static final int BASE_X = -300;
    private static final int BASE_Z = -300;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        secretTriggerbot(ctx);
    }

    /**
     * Secret Triggerbot: right-clicks whatever secret your crosshair is on.
     *
     * <p>Chosen as the first feature after Breaker Aura because it is the cheapest one to drive honestly - it
     * needs no boss coordinates, no chat state machine and no Hypixel item, just the dungeon override and
     * something to look at - and because it sends {@code ServerboundUseItemOnPacket} rather than a dig. If the
     * ordering fault is about where the mod ticks rather than about digging specifically, it will show up here
     * too, on a different packet type. That is the question.
     *
     * <p>The target is a lever, not a chest. Clicking a chest opens a container screen, and the feature stands
     * itself down while one is open - so a chest measures one click and then nothing.
     */
    private void secretTriggerbot(ClientGameTestContext ctx) {
        String name = "60-secret-triggerbot";
        if (Scenario.skip(name)) {
            return;
        }
        ModUnderTest.require(MOD_ID);
        Scenario.labelServerAs(DUNGEON_LABEL);
        Scenario.runExpectingFlags(ctx, name,
                (server, scenario) -> TestMap.on(server)
                        .platform(BASE_X, SURFACE_Y - 1, BASE_Z, 12)
                        .walls(4)
                        .catchFloor(120)
                        .survival()
                        .clearInventory()
                        // Floor detected for real, so isInDungeon() is true while isInBoss() stays false.
                        .skyblockSidebar("F7")
                        // A wall to hang the lever on, and the lever at eye height so the crosshair is on it
                        // from the start. The feature raycasts through the crosshair with vanilla reach and
                        // has no range setting of its own, so the player has to be genuinely looking at it.
                        // A WALL OF LEVERS, and the player slides along it.
                        //
                        // One lever measures one click: the feature keeps a done-set and never clicks the same
                        // secret twice, which is correct for a dungeon secret and useless for measuring a rate.
                        // The first version of this scenario had a single lever, got exactly one interaction,
                        // and that one interaction was already enough to draw a Post violation.
                        //
                        // Strafing rather than turning, deliberately: sweeping the crosshair by writing yaw
                        // would put a synthetic rotation of the harness's own into the very packets being
                        // measured. Sideways movement past a row of levers walks each one through the
                        // crosshair using nothing but a held key.
                        .command("fill " + (BASE_X + 1) + " " + SURFACE_Y + " " + (BASE_Z - 10)
                                + " " + (BASE_X + 1) + " " + (SURFACE_Y + 2) + " " + (BASE_Z + 10)
                                + " minecraft:stone")
                        .command("fill " + BASE_X + " " + (SURFACE_Y + 1) + " " + (BASE_Z - 10)
                                + " " + BASE_X + " " + (SURFACE_Y + 1) + " " + (BASE_Z + 10)
                                + " minecraft:lever[face=wall,facing=west]")
                        .spawn(BASE_X - 2.5, BASE_Z - 9.5, -90f)
                        .build(),
                (server, scenario) -> {
                    scenario.assertDetectorWorks();

                    // Prove the crosshair is actually on the lever before turning anything on. Without this a
                    // mis-aimed spawn produces "no packets sent", which reads identically to "the feature is
                    // broken" and to "the gates are shut".
                    String looking = ctx.computeOnClient(mc -> mc.hitResult == null
                            ? "nothing" : mc.hitResult.getType() + " " + mc.hitResult.getLocation());
                    scenario.log("crosshair is on: " + looking);
                    if (!looking.contains("BLOCK")) {
                        throw new AssertionError("the crosshair is not on the lever (" + looking + "), so "
                                + "Secret Triggerbot has nothing to trigger on and this scenario would "
                                + "measure nothing");
                    }

                    PacketWatch.start();
                    ctx.runOnClient(mc -> {
                        Object cfg = ModUnderTest.config(
                                "com.killer560.hub.secrettrigger.SecretTriggerbotConfig");
                        ModUnderTest.set(cfg, "setEnabled", true);
                        // NO sim override here, deliberately. It forces boss phase on as well, and this
                        // feature stands down in the boss - the override would shut the very gate it opens.
                        if (!Boolean.TRUE.equals(ModUnderTest.staticCall(
                                "com.killer560.hub.secrets.DungeonState", "isInDungeon"))) {
                            throw new AssertionError("the scoreboard sidebar was not read as a dungeon floor, "
                                    + "so the feature's dungeon gate is shut and it would sleep through the "
                                    + "whole run");
                        }
                        if (Boolean.TRUE.equals(ModUnderTest.staticCall(
                                "com.killer560.hub.livemap.LiveMapFeature", "isInBoss"))) {
                            throw new AssertionError("the mod thinks this is the boss room, and Secret "
                                    + "Triggerbot stands down there - nothing would be measured");
                        }
                        if (!ModUnderTest.getBoolean(cfg, "isEnabled")) {
                            throw new AssertionError("isEnabled() is still false after setting it - a gate "
                                    + "it AND-s in is shut. Needs the cheat jar, and the server label must "
                                    + "contain p3sim.net or hypixel.net.");
                        }
                    });

                    ctx.waitTicks(5);
                    reportGates(ctx, scenario);
                    // Already counting before the first click can happen. It was started after the feature
                    // last time and missed the only interaction of the run - the anticheat reported a
                    // violation the counter had no record of.
                    ctx.getInput().holdKey(options -> options.keyRight);
                    ctx.waitTicks(200);
                    ctx.getInput().releaseKey(options -> options.keyRight);
                    ctx.waitTicks(10);
                    PacketWatch.stop();

                    scenario.log(PacketWatch.summary());
                    record(scenario, "Secret Triggerbot", PacketWatch.totalUses(),
                            PacketWatch.usesAfterMove());
                    int leversLeft = ctx.computeOnClient(mc -> {
                        int n = 0;
                        for (int dz = -10; dz <= 10; dz++) {
                            if (mc.level.getBlockState(new net.minecraft.core.BlockPos(
                                    BASE_X, SURFACE_Y + 1, BASE_Z + dz)).getValue(
                                    net.minecraft.world.level.block.LeverBlock.POWERED)) {
                                n++;
                            }
                        }
                        return n;
                    });
                    scenario.log(leversLeft + " of 21 levers were flipped, so that many clicks landed");
                    if (PacketWatch.totalUses() == 0) {
                        throw new AssertionError("Secret Triggerbot sent no block-use packets in 200 ticks, "
                                + "so its anticheat result means nothing. Check the client log for its own "
                                + "skip reason.");
                    }
                });
    }

    /**
     * Print the gate values the mod is actually reading, before switching anything on.
     *
     * <p>Every one of these is a way for a scenario to quietly measure nothing, and each has already been the
     * cause of one: the build variant, the server label, the dungeon override, and the map's own idea of
     * which room you are in. Reading them out loud costs a line and turns "no packets" from a mystery into an
     * answer.
     */
    private void reportGates(ClientGameTestContext ctx, Scenario scenario) {
        String report = ctx.computeOnClient(mc -> {
            StringBuilder out = new StringBuilder();
            out.append("server the mod sees: ").append(mc.getCurrentServer() == null
                    ? "none" : mc.getCurrentServer().ip);
            for (String[] probe : new String[][]{
                    {"com.killer560.hub.BuildVariant", "CHEAT_FEATURES_ENABLED", "field"},
                    {"com.killer560.hub.secrets.DungeonState", "isInDungeon", "method"},
                    {"com.killer560.hub.secrets.DungeonState", "isBossPhaseActive", "method"},
                    {"com.killer560.hub.util.SkyblockGate", "allows", "method"},
                    // The one that silently shut this scenario down the first time it ran.
                    {"com.killer560.hub.livemap.LiveMapFeature", "isInBoss", "method"},
            }) {
                out.append(", ").append(probe[1]).append('=');
                try {
                    Class<?> type = Class.forName(probe[0]);
                    out.append("field".equals(probe[2])
                            ? type.getField(probe[1]).get(null)
                            : type.getMethod(probe[1]).invoke(null));
                } catch (ReflectiveOperationException e) {
                    out.append("UNREADABLE(").append(e.getClass().getSimpleName()).append(')');
                }
            }
            return out.toString();
        });
        scenario.log("gates: " + report);
    }

    /** One line per feature, in the shape the Breaker Aura finding was reported in, so they compare. */
    private void record(Scenario scenario, String feature, int actions, int afterMove) {
        List<String> flags = scenario.flags();
        long post = flags.stream().filter(line -> line.contains("failed Post")).count();
        long sim = flags.stream().filter(line -> line.contains("failed Simulation")).count();
        long other = flags.size() - post - sim;
        scenario.log(String.format(Locale.ROOT,
                "RESULT %s: %d interaction(s) sent, %d of them after that tick's movement packet -> "
                        + "anticheat said %d Post, %d Simulation, %d other",
                feature, actions, afterMove, post, sim, other));
        if (post > 0) {
            scenario.log("FINDING: " + feature + " has the same packet-ordering fault Breaker Aura had. It "
                    + "needs to tick on START_CLIENT_TICK, not END_CLIENT_TICK.");
        }
        for (String flag : flags.stream().distinct().limit(4).toList()) {
            scenario.log("    " + flag);
        }
    }
}
