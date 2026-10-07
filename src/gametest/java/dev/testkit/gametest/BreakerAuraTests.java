package dev.testkit.gametest;

import dev.testkit.harness.PacketWatch;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;
import java.util.Locale;

/**
 * killer560s-mod's <b>Breaker Aura</b>, measured against a live anticheat.
 *
 * <p>The question these answer is the one he actually asked, twice: "Just make sure it is safe to use" and
 * then "If there is 0 other bug then allow it to break multiple at once." Several interaction packets
 * inside one client tick is not a rate a hand can produce, and the module's own source says so in as many
 * words. What was missing was a measurement: how many actually go out on one tick, how far the furthest
 * one reaches, whether a swing goes with each, and whether a real server-side check objects.
 *
 * <p>Two scenarios, identical but for one setting, so the difference between them is attributable:
 * <ol>
 *   <li>{@code 50-breaker-aura-default} - Blocks Per Cycle at its shipped default of 1.</li>
 *   <li>{@code 51-breaker-aura-multi} - Blocks Per Cycle at 5, the maximum the setter allows.</li>
 * </ol>
 *
 * <h2>Two things this cannot tell you</h2>
 * GrimAC is not Hypixel's Watchdog. A clean Grim run is a useful lower bound - it means the traffic is not
 * obviously impossible and survives a well-known movement/interaction model - and it is not a safety
 * certificate. The numbers printed below are the transferable part of the result; Grim's verdict is the
 * cheaper part.
 *
 * <p>And the wall here is snow, not a Hypixel dungeon wall. Snow was picked because a diamond shovel breaks
 * it in a single tick, which is the only way to reproduce the Dungeon Breaker's instant break on a vanilla
 * server: with a block that takes many ticks the module sends one packet, waits, and the per-tick rate this
 * is built to measure never happens.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class BreakerAuraTests implements FabricClientGameTest {

    private static final String MOD_ID = "killer560smod";
    private static final String CONFIG = "com.killer560.hub.dungeonextras.DungeonExtrasConfig";
    private static final String DUNGEON_STATE = "com.killer560.hub.secrets.DungeonState";

    /** The corridor the player tunnels through: x from here, at the two body layers, z either side. */
    private static final int WALL_FROM_X = -2;
    // Long enough that a 200-tick run cannot clear it. The first version stopped at x=24, and both the
    // default and the multi-break setting broke all 54 blocks of it - so the two looked identical when what
    // they had actually done was each run out of wall. A saturated arena cannot measure throughput.
    private static final int WALL_TO_X = 90;
    private static final int WALL_Z1 = -2;
    private static final int WALL_Z2 = 2;
    private static final int SURFACE_Y = 151;

    /**
     * Since mod 2026-10-05 Breaker Aura breaks ONLY picked blocks (killer560: "the aura shouldn't randomly grab
     * blocks, only ones I have selected"); the path sweep, Side Reach and Only Picked Blocks are gone. So the wall
     * scenarios pick the whole corridor first, which is exactly what he does with a wall in a dungeon, and on an
     * older jar they also switch Only Picked Blocks on (and Side Reach off) so both jars run the same behaviour.
     */
    private static final String STORE = "com.killer560.hub.dungeonextras.BreakerAuraStore";

    /** 53/54's arenas: their own coordinates, clear of the corridor (z -6..6) and inside the catch floor. */
    private static final int SIDE_CX = 30;
    private static final int SIDE_CZ = 30;
    private static final int BEHIND_CX = 30;
    private static final int BEHIND_CZ = -30;
    private static final int FLOOR_CX = -30;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        speedControl(ctx);
        vanillaControl(ctx);
        // Sprint speed, one a tick: the clean baseline the packet-ordering fix is proven against.
        breakerAura(ctx, "50-breaker-aura-default", false, false);
        // Speed V and the full rate: the configuration that keeps up with the wall.
        breakerAura(ctx, "51-breaker-aura-multi", true, true);
        // Speed V at one a tick, which cannot keep up. Documented, not asserted clean.
        highSpeedDesync(ctx);
        // Picked blocks beside him break; unpicked ones, even in his way or under his feet, never do.
        pickedSideAndFloor(ctx);
        // The floor cases repeat -PfloorRepeat=N times (default 1): the GroundSpoof they chase was intermittent,
        // so one verdict is not a measurement. Repeats are named "#k" so the filter still selects them.
        int repeat = Integer.getInteger("testkit.floorRepeat", 1);
        for (int k = 1; k <= repeat; k++) {
            String r = k == 1 ? "" : "#" + k;
            // A picked block under his feet: as shipped, and with Zero Ping. Measured (see pickedFloor).
            pickedFloor(ctx, "55-breaker-aura-picked-floor" + r, 30, false, 0f, false);
            pickedFloor(ctx, "56-breaker-aura-picked-floor-zeroping" + r, -30, true, 0f, false);
            // The same two looking straight DOWN at it, as the hand control does: does the look direction matter?
            pickedFloor(ctx, "58-breaker-aura-picked-floor-lookdown" + r, 50, false, 90f, false);
            pickedFloor(ctx, "59-breaker-aura-picked-floor-zeroping-lookdown" + r, -50, true, 90f, false);
            // TURNING while it breaks: every tick then sends a movement packet, so a packet always goes out between
            // the dig and the server's block update. That window is what made 55 intermittent (2026-10-06).
            pickedFloor(ctx, "60-breaker-aura-picked-floor-turning" + r, 70, false, 0f, true);
            pickedFloor(ctx, "61-breaker-aura-picked-floor-zeroping-turning" + r, -70, true, 0f, true);
            // ...and the same moment by HAND, which is what tells a Grim quirk from something the aura does.
            floorByHand(ctx, "57-breaker-floor-by-hand" + r, 0, false);
            floorByHand(ctx, "62-breaker-floor-by-hand-turning" + r, 12, true);
        }
        // Picked blocks BEHIND him and behind another block: measured, not asserted clean.
        pickedBehindAndOccluded(ctx);
    }

    /**
     * THE SECOND CONTROL: the same potion effects, sprinting across open ground, module off.
     *
     * <p>Speed V was added so the wall would enter reach fast enough to queue up, and the moment it went in
     * the anticheat started reporting small movement offsets. Those are only attributable to the module if a
     * player with the same effects and nothing else running is clean - the by-hand control cannot answer it,
     * because a player digging by hand barely moves. This one runs the movement and nothing else.
     */
    private void speedControl(ClientGameTestContext ctx) {
        Scenario.run(ctx, "48-speed-control-open-run",
                (server, scenario) -> TestMap.on(server)
                        .platform(0, SURFACE_Y - 1, 0, 12)
                        .floor(-14, SURFACE_Y - 1, -6, WALL_TO_X + 4, 6)
                        .catchFloor(120)
                        .survival()
                        .command("effect give @p minecraft:haste 99999 4 true")
                        .command("effect give @p minecraft:speed 99999 4 true")
                        .spawn(-3.5, 0.5, -90f)
                        .build(),
                (server, scenario) -> {
                    scenario.assertDetectorWorks();
                    double[] start = scenario.playerPosition();
                    ctx.getInput().holdKey(options -> options.keyUp);
                    ctx.getInput().holdKey(options -> options.keySprint);
                    ctx.waitTicks(200);
                    ctx.getInput().releaseKey(options -> options.keySprint);
                    ctx.getInput().releaseKey(options -> options.keyUp);
                    ctx.waitTicks(10);
                    double[] end = scenario.playerPosition();
                    double travelled = Math.hypot(end[0] - start[0], end[2] - start[2]);
                    scenario.log(String.format(Locale.ROOT,
                            "open ground on Speed V: %.2f blocks in 200 ticks = %.3f blocks/tick",
                            travelled, travelled / 200.0));
                    if (travelled < 60) {
                        throw new AssertionError("only travelled " + travelled + " blocks on Speed V - the "
                                + "run did not happen, so a clean result says nothing");
                    }
                });
    }

    /**
     * THE CONTROL: the same wall, the same shovel, broken by holding the attack key.
     *
     * <p>Without this the two scenarios below cannot be read. If the anticheat objects to the module's
     * breaks, the first question is whether it objects to <i>any</i> digging in this arena - a quirk of the
     * test server, the block, the version - and only a hand-driven run can answer it. A finding that
     * survives this control is about the module; one that does not is about the harness.
     *
     * <p>The mod is loaded here and deliberately left switched OFF, so even the comparison of "mod present"
     * against "mod acting" is held constant.
     */
    private void vanillaControl(ClientGameTestContext ctx) {
        Scenario.run(ctx, "49-breaker-control-by-hand",
                (server, scenario) -> TestMap.on(server)
                        .platform(0, SURFACE_Y - 1, 0, 12)
                        .floor(-14, SURFACE_Y - 1, -6, WALL_TO_X + 4, 6)
                        .catchFloor(120)
                        .survival()
                        .clearInventory()
                        .give("diamond_shovel", 1)
                        // The Dungeon Breaker one-shots a dungeon wall. Nothing on a vanilla server does
                        // that for free, so the tool is stacked until the block cannot survive one packet:
                        // if it takes two, the module's 200 ms retry re-sends it and the per-tick rate this
                        // measures becomes a mining-speed measurement instead. Proven below by the ratio of
                        // break-starts to blocks cleared, not assumed from block hardness.
                        .command("enchant @p minecraft:efficiency 5")
                        .command("effect give @p minecraft:haste 99999 4 true")
                        // killer560 (2026-09-27): "you may want to give the character more speed [...]
                        // because right now it is too slow to have tons of blocks infront of it at once."
                        // He is right, and it was the flaw in the first version of this: at sprint speed the
                        // module clears the wall faster than the player walks into it, so the queue never
                        // held more than two blocks and Blocks Per Cycle 5 could not be reached however it
                        // was set. Speed V roughly doubles how fast new wall enters reach. A potion effect
                        // rather than anything invented: the anticheat models it, and the by-hand control
                        // carries the same effect so nothing here is attributable to the speed itself.
                        .command("effect give @p minecraft:speed 99999 4 true")
                        .fill(WALL_FROM_X, SURFACE_Y, WALL_Z1, WALL_TO_X, SURFACE_Y + 1, WALL_Z2, "snow_block")
                        // Pressed up against the wall, not walking in from range. Arriving with the queue
                        // already full is the only state in which Blocks Per Cycle above 2 can ever engage,
                        // and a setting that goes to 5 deserves to be measured where 5 is possible.
                        .spawn(-3.5, 0.5, -90f)
                        .build(),
                (server, scenario) -> {
                    scenario.assertDetectorWorks();
                    int before = countWall(ctx);

                    PacketWatch.start();
                    ctx.getInput().holdKey(options -> options.keyUp);
                    ctx.getInput().holdKey(options -> options.keyAttack);
                    ctx.waitTicks(200);
                    ctx.getInput().releaseKey(options -> options.keyAttack);
                    ctx.getInput().releaseKey(options -> options.keyUp);
                    ctx.waitTicks(10);
                    PacketWatch.stop();

                    int broken = before - countWall(ctx);
                    scenario.log(PacketWatch.summary());
                    scenario.log("by hand: " + broken + " blocks broken");
                    if (broken <= 0) {
                        throw new AssertionError("holding attack broke nothing, so this control proves "
                                + "nothing about whether the anticheat tolerates digging here");
                    }
                });
    }

    private void breakerAura(ClientGameTestContext ctx, String name, boolean multiBreak, boolean speedPotion) {
        if (Scenario.skip(name)) {
            return;
        }
        ModUnderTest.require(MOD_ID);
        Scenario.run(ctx, name,
                (server, scenario) -> TestMap.on(server)
                        .platform(0, SURFACE_Y - 1, 0, 12)
                        .floor(-14, SURFACE_Y - 1, -6, WALL_TO_X + 4, 6)
                        .catchFloor(120)
                        .survival()
                        .clearInventory()
                        .give("diamond_shovel", 1)
                        // The Dungeon Breaker one-shots a dungeon wall. Nothing on a vanilla server does
                        // that for free, so the tool is stacked until the block cannot survive one packet:
                        // if it takes two, the module's 200 ms retry re-sends it and the per-tick rate this
                        // measures becomes a mining-speed measurement instead. Proven below by the ratio of
                        // break-starts to blocks cleared, not assumed from block hardness.
                        .command("enchant @p minecraft:efficiency 5")
                        .command("effect give @p minecraft:haste 99999 4 true")
                        // killer560 (2026-09-27): "you may want to give the character more speed [...]
                        // because right now it is too slow to have tons of blocks infront of it at once."
                        // He is right, and it was the flaw in the first version of this: at sprint speed the
                        // module clears the wall faster than the player walks into it, so the queue never
                        // held more than two blocks and Blocks Per Cycle 5 could not be reached however it
                        // was set. Speed V roughly doubles how fast new wall enters reach. A potion effect
                        // rather than anything invented: the anticheat models it, and the by-hand control
                        // carries the same effect so nothing here is attributable to the speed itself.
                        .command(speedPotion
                                ? "effect give @p minecraft:speed 99999 4 true"
                                : "effect clear @p minecraft:speed")
                        // A solid corridor of instant-break blocks, at exactly the two layers the module's
                        // swept hitbox looks at, so every tick of the run has something to chew.
                        .fill(WALL_FROM_X, SURFACE_Y, WALL_Z1, WALL_TO_X, SURFACE_Y + 1, WALL_Z2, "snow_block")
                        // Pressed up against the wall, not walking in from range. Arriving with the queue
                        // already full is the only state in which Blocks Per Cycle above 2 can ever engage,
                        // and a setting that goes to 5 deserves to be measured where 5 is possible.
                        .spawn(-3.5, 0.5, -90f)
                        .build(),
                (server, scenario) -> {
                    scenario.assertDetectorWorks();

                    int before = countWall(ctx);
                    scenario.log("wall standing before the run: " + before + " blocks");
                    if (before < 500) {
                        throw new AssertionError("the snow corridor was not built (" + before
                                + " blocks) - this scenario would have measured nothing");
                    }

                    configure(ctx, scenario, multiBreak, corridorPicks());
                    try {

                    PacketWatch.start();
                    double[] start = scenario.playerPosition();
                    ctx.getInput().holdKey(options -> options.keyUp);
                    ctx.getInput().holdKey(options -> options.keySprint);
                    // Re-stamp the breaker EVERY tick. The server owns the inventory, and breaking a
                    // block damages the shovel, so it resends the slot after almost every break and the
                    // client-side components go with it. Stamping every ten ticks left the module
                    // correctly reporting no charges for nine of them - it ran at a tenth of its real
                    // rate, which is the opposite of the mistake a safety test can afford.
                    for (int i = 0; i < 200; i++) {
                        stampBreaker(ctx);
                        ctx.waitTicks(1);
                    }
                    ctx.getInput().releaseKey(options -> options.keySprint);
                    ctx.getInput().releaseKey(options -> options.keyUp);
                    ctx.waitTicks(10);
                    PacketWatch.stop();

                    double[] end = scenario.playerPosition();
                    int after = countWall(ctx);
                    int broken = before - after;
                    double travelled = Math.hypot(end[0] - start[0], end[2] - start[2]);

                    scenario.log(PacketWatch.summary());

                    // RECONCILE THE COUNTER AGAINST THE SERVER. The client-side ordering count depends on
                    // where this harness closes a tick relative to whatever hook the module uses, and it was
                    // silently wrong about exactly that once already. The anticheat's own Post count is
                    // ground truth; if the two disagree, the number to trust is the server's and the one to
                    // stop quoting is ours.
                    long post = scenario.flags().stream()
                            .filter(line -> line.contains("failed Post")).count();
                    scenario.log(String.format(Locale.ROOT,
                            "packet ordering: anticheat reported %d Post violation(s); this harness counted "
                                    + "%d break(s) sent after the movement packet%s",
                            post, PacketWatch.breaksAfterMove(),
                            (post > 0) == (PacketWatch.breaksAfterMove() > 0)
                                    ? " - they agree"
                                    : "  <-- THEY DISAGREE, trust the server's number"));

                    // INSTANT BREAK, PROVEN. One break-start per block cleared means every packet took its
                    // block down on the first try. Above about 1.15 the wall is surviving packets, the
                    // module is retrying, and the throughput figure stops being about the module at all.
                    double perBlock = broken == 0 ? Double.NaN : PacketWatch.totalBreaks() / (double) broken;
                    scenario.log(String.format(Locale.ROOT,
                            "instant-break check: %d break-starts for %d blocks cleared = %.2f packets per "
                                    + "block%s",
                            PacketWatch.totalBreaks(), broken, perBlock,
                            perBlock > 1.15 ? "  <-- NOT an instant break, see below" : "  (instant break)"));
                    if (perBlock > 1.15) {
                        throw new AssertionError(String.format(Locale.ROOT,
                                "the wall is not breaking on the first packet (%.2f packets per block), so "
                                        + "this run measured how fast snow mines, not how fast the module "
                                        + "sends. Stack the tool further or use a softer block before "
                                        + "quoting any rate from it.", perBlock));
                    }
                    scenario.log(String.format(Locale.ROOT,
                            "Multi Break %s: %d blocks cleared and %.2f blocks advanced in 200 ticks "
                                    + "(%.2f blocks/tick through solid wall) - %d of %d wall blocks left "
                                    + "standing, so the arena did not run out",
                            multiBreak ? "ON" : "off", broken, travelled, travelled / 200.0, after,
                            before));

                    // Proving the module ran is the whole point. Grim says nothing about a feature that
                    // was never active, and "no flags" from an inactive feature is the most dangerous
                    // result this harness could print.
                    if (PacketWatch.totalBreaks() == 0) {
                        throw new AssertionError("Breaker Aura sent no break packets at all - it never "
                                + "became active, so its clean anticheat result means nothing. Check the "
                                + "client log for [DungeonExtras] Breaker Aura lines and the skip reason.");
                    }
                    if (broken <= 0) {
                        throw new AssertionError("Breaker Aura sent " + PacketWatch.totalBreaks()
                                + " break packets but the wall is still standing - the server refused "
                                + "every one of them, which is itself the finding.");
                    }
                    if (!multiBreak && PacketWatch.maxBreaksOnOneTick() > 1) {
                        throw new AssertionError("Multi Break is off but "
                                + PacketWatch.maxBreaksOnOneTick() + " break packets went out on a single "
                                + "tick - with it off the rate is supposed to be one a tick.");
                    }

                    // THE ONE THAT MATTERS, in his words: "the only breaker command thing I care about is that
                    // i never am able to run into a wall while it is breaking." With Multi Break on, the wall
                    // must never stop him - so the client must never report a horizontal collision, and his
                    // speed through solid wall must match what the same player manages on open ground. Blocks
                    // per tick is a means; this is the goal, so this is what is asserted.
                    int collisions = PacketWatch.collisionTicks();
                    scenario.log(String.format(Locale.ROOT,
                            "ran into the wall on %d of %d movement packets", collisions,
                            PacketWatch.moveCount()));
                    if (multiBreak && collisions > 0) {
                        throw new AssertionError("Multi Break is on and the player was still stopped by the "
                                + "wall on " + collisions + " tick(s) of " + PacketWatch.moveCount()
                                + ". The whole point of Multi Break is that this number is zero: he asked to "
                                + "never be able to run into a wall while it is breaking.");
                    }
                    if (PacketWatch.breaksWithoutSwing() > 0) {
                        scenario.log("NOTE: " + PacketWatch.breaksWithoutSwing() + " break(s) went out on "
                                + "a tick with no swing packet - some servers check exactly that.");
                    }
                    if (PacketWatch.maxBoxReach() > 4.5001) {
                        scenario.log(String.format(Locale.ROOT, "NOTE: furthest break reached %.2f to the "
                                + "block box; vanilla's own limit is 4.5 and a server may measure it the "
                                + "same way.", PacketWatch.maxBoxReach()));
                    }
                    logDigShape(scenario);
                    } finally {
                        unconfigure(ctx);
                    }
                });
    }

    /**
     * The shipped defaults at high speed, which DO flag - recorded rather than asserted clean.
     *
     * <p>Every other scenario here comes back clean, and that made this easy to miss. The anticheat's
     * movement prediction objects, with sub-half-block offsets, once the player is fast enough that the wall
     * is not cleared ahead of them. At one block a tick (Multi Break off) most
     * ticks are spent walking into a block being removed on that same tick, and client and server briefly
     * disagree about whether it was still there. Turn the rate up so the path is already clear on arrival and
     * the flags vanish: the faster configuration is the cleaner one, which is the opposite of the guess.
     *
     * <p>This matters more in a real dungeon than here, not less. Skyblock speed goes far past Speed V, and
     * the effect only appears once you move faster than the aura clears.
     */
    private void highSpeedDesync(ClientGameTestContext ctx) {
        String name = "52-breaker-aura-highspeed-desync";
        if (Scenario.skip(name)) {
            return;
        }
        ModUnderTest.require(MOD_ID);
        Scenario.runExpectingFlags(ctx, name,
                (server, scenario) -> TestMap.on(server)
                        .platform(0, SURFACE_Y - 1, 0, 12)
                        .floor(-14, SURFACE_Y - 1, -6, WALL_TO_X + 4, 6)
                        .catchFloor(120)
                        .survival()
                        .clearInventory()
                        .give("diamond_shovel", 1)
                        .command("enchant @p minecraft:efficiency 5")
                        .command("effect give @p minecraft:haste 99999 4 true")
                        .command("effect give @p minecraft:speed 99999 4 true")
                        .fill(WALL_FROM_X, SURFACE_Y, WALL_Z1, WALL_TO_X, SURFACE_Y + 1, WALL_Z2, "snow_block")
                        .spawn(-3.5, 0.5, -90f)
                        .build(),
                (server, scenario) -> {
                    scenario.assertDetectorWorks();
                    configure(ctx, scenario, false, corridorPicks());
                    try {
                    PacketWatch.start();
                    ctx.getInput().holdKey(options -> options.keyUp);
                    ctx.getInput().holdKey(options -> options.keySprint);
                    for (int i = 0; i < 200; i++) {
                        stampBreaker(ctx);
                        ctx.waitTicks(1);
                    }
                    ctx.getInput().releaseKey(options -> options.keySprint);
                    ctx.getInput().releaseKey(options -> options.keyUp);
                    ctx.waitTicks(20);
                    PacketWatch.stop();

                    long sim = scenario.flags().stream()
                            .filter(line -> line.contains("failed Simulation")).count();
                    long post = scenario.flags().stream()
                            .filter(line -> line.contains("failed Post")).count();
                    scenario.log(PacketWatch.summary());
                    scenario.log("shipped defaults on Speed V: " + sim + " movement (Simulation) flag(s), "
                            + post + " ordering (Post) flag(s)");
                    if (post > 0) {
                        throw new AssertionError("the packet-ordering fix has regressed: " + post
                                + " Post violation(s). Breaker Aura must tick on START_CLIENT_TICK.");
                    }
                    if (sim == 0) {
                        scenario.log("NOTE: the movement desync did NOT reproduce this run - either it has "
                                + "been fixed or this arena no longer provokes it. Worth re-checking before "
                                + "quoting the finding.");
                    }
                    } finally {
                        unconfigure(ctx);
                    }
                });
    }

    /** Turn the module on exactly the way his own menu does, and nothing else. */
    private void configure(ClientGameTestContext ctx, Scenario scenario, boolean multiBreak,
                           List<BlockPos> picks) {
        ctx.runOnClient(mc -> {
            Object cfg = ModUnderTest.config(CONFIG);
            ModUnderTest.set(cfg, "setBreakerAuraEnabled", true);
            // An older jar still has the path sweep behind "Only Picked Blocks" and a Side Reach slider. Put it
            // in picked-only mode with no side corridor, which is the only behaviour the current jar has, so the
            // two jars run the same thing. On a current jar these setters do not exist and nothing happens.
            boolean legacyPicked = setIfPresent(cfg, "setBreakerAuraSelectedOnly", boolean.class, true);
            boolean legacySide = setIfPresent(cfg, "setBreakerAuraSideReach", double.class, 0.0);
            scenario.log("jar has Only Picked Blocks: " + legacyPicked + ", Side Reach: " + legacySide
                    + (legacyPicked || legacySide ? " (older jar, forced to picked-only)" : " (picked-only build)"));
            ModUnderTest.set(cfg, "setBreakerAuraAutoSwap", false);
            ModUnderTest.set(cfg, "setBreakerAuraMultiBreak", multiBreak);
            setPicks(picks);
            // Everything else is left at whatever ships, so what gets measured is the shipped behaviour.
            if (ModUnderTest.getBoolean(cfg, "isBreakerAuraMultiBreak") != multiBreak) {
                throw new AssertionError("Multi Break did not take the value it was set to, so this scenario "
                        + "is not testing what it says it is");
            }
            // The module is gated on being in a dungeon, which no local server can be. This is the mod's
            // own /killer560 sim toggle - a real switch it ships, not a hook added for testing.
            Object active = ModUnderTest.staticCall(DUNGEON_STATE, "toggleSimOverride");
            if (!Boolean.TRUE.equals(active)) {
                throw new AssertionError("toggleSimOverride() turned the dungeon override OFF - the gate "
                        + "is shut, so Breaker Aura would stay asleep for the whole run");
            }
        });
        stampBreaker(ctx);
        ctx.waitTicks(5);
        boolean live = ctx.computeOnClient(mc ->
                ModUnderTest.getBoolean(ModUnderTest.config(CONFIG), "isBreakerAuraEnabled"));
        if (!live) {
            throw new AssertionError("isBreakerAuraEnabled() is still false after turning it on - one of "
                    + "the gates it AND-s in (cheat build, Skyblock Only) is shut. A legit-build jar "
                    + "cannot run this scenario; build with -PcheatBuild=true.");
        }
        int picked = ctx.computeOnClient(mc -> pickCount());
        if (picked != picks.size()) {
            throw new AssertionError("asked for " + picks.size() + " picks and the store holds " + picked
                    + " - the scenario would not be testing the blocks it names");
        }
        scenario.log("Breaker Aura on, " + picked + " block(s) picked, Multi Break " + (multiBreak ? "ON" : "off")
                + ", dungeon override forced");
    }

    /** Leave the mod as found: no picks (they live in a file that outlives the scenario) and the aura off. */
    private void unconfigure(ClientGameTestContext ctx) {
        try {
            ctx.runOnClient(mc -> {
                setPicks(List.of());
                ModUnderTest.turnOff(CONFIG, "setBreakerAuraEnabled");
            });
        } catch (Throwable ignored) {
            // cleanup must not become the failure
        }
    }

    /** Replaces the active Breaker Aura config's picks with exactly these (client thread). */
    private static void setPicks(List<BlockPos> picks) {
        Object store = ModUnderTest.staticCall(STORE, "getInstance");
        ModUnderTest.call(store, "clear", new Class<?>[0], new Object[0]);
        for (BlockPos p : picks) {
            ModUnderTest.call(store, "addPick", new Class<?>[]{String.class},
                    new Object[]{p.getX() + "," + p.getY() + "," + p.getZ()});
        }
        ModUnderTest.call(store, "save", new Class<?>[0], new Object[0]);
    }

    @SuppressWarnings("unchecked")
    private static int pickCount() {
        Object store = ModUnderTest.staticCall(STORE, "getInstance");
        return ((java.util.Set<String>) ModUnderTest.call(store, "pickedKeys", new Class<?>[0], new Object[0])).size();
    }

    private static boolean setIfPresent(Object cfg, String setter, Class<?> type, Object value) {
        try {
            cfg.getClass().getMethod(setter, type).invoke(cfg, value);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not call " + setter, e);
        }
    }

    /** The whole snow corridor of 49-52, both layers - what he would pick to tunnel through that wall. */
    private static List<BlockPos> corridorPicks() {
        List<BlockPos> out = new java.util.ArrayList<>();
        for (int x = WALL_FROM_X; x <= WALL_TO_X; x++) {
            for (int y = SURFACE_Y; y <= SURFACE_Y + 1; y++) {
                for (int z = WALL_Z1; z <= WALL_Z2; z++) {
                    out.add(new BlockPos(x, y, z));
                }
            }
        }
        return out;
    }

    /** The shape of the digs from his own eyes, which GrimAC may not check and another anticheat could. */
    private static void logDigShape(Scenario scenario) {
        scenario.log(String.format(Locale.ROOT,
                "dig shape: %d dig(s), up to %d on one tick, %d whose eye-to-centre line crossed another block "
                        + "first (dug through something), widest angle off his view %.1f deg, furthest %.2f to box",
                PacketWatch.totalBreaks(), PacketWatch.maxBreaksOnOneTick(), PacketWatch.occludedDigs(),
                PacketWatch.maxDigLookAngle(), PacketWatch.maxBoxReach()));
    }

    private static boolean isAir(ClientGameTestContext ctx, BlockPos pos) {
        return ctx.computeOnClient(mc -> mc.level.getBlockState(pos).isAir());
    }

    private static String xyz(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }

    /**
     * 53: PICKED blocks beside him break; UNPICKED blocks, even the wall he walks into and the floor he stands on,
     * never do. (A picked block under his feet is 55/56.)
     *
     * <p>killer560, 2026-10-05: "the aura shouldn't randomly grab blocks, only ones I have selected". Two phases on
     * one small pad:
     * <ol>
     *   <li>Standing still facing +x: five picked snow blocks to his left and right at body height (one near the edge
     *       of reach) and one picked block OUT of reach. The five must go, the far one must stay, and so must the
     *       unpicked snow right beside them and the unpicked snow floor he stands on.</li>
     *   <li>He walks into a wall of UNPICKED snow for 20 ticks. It must stop him and stay standing.</li>
     * </ol>
     * Every dig the aura sends must name a picked block. Multi Break ON (the shipped default), so the side picks go
     * out together on one tick, at right angles to where he is looking - the shape worth having Grim look at.
     */
    private void pickedSideAndFloor(ClientGameTestContext ctx) {
        String name = "53-breaker-aura-picked-side";
        if (Scenario.skip(name)) {
            return;
        }
        ModUnderTest.require(MOD_ID);
        int fy = SURFACE_Y - 1; // floor layer; he stands at SURFACE_Y
        BlockPos underSpawn = new BlockPos(SIDE_CX, fy, SIDE_CZ);
        List<BlockPos> sidePicks = List.of(
                new BlockPos(SIDE_CX, SURFACE_Y, SIDE_CZ + 2), new BlockPos(SIDE_CX, SURFACE_Y + 1, SIDE_CZ + 2),
                new BlockPos(SIDE_CX + 1, SURFACE_Y, SIDE_CZ - 2), new BlockPos(SIDE_CX + 1, SURFACE_Y + 1, SIDE_CZ - 2),
                new BlockPos(SIDE_CX - 1, SURFACE_Y, SIDE_CZ + 4)); // ~3.6 from his eye: near the edge of 4.5
        BlockPos farPick = new BlockPos(SIDE_CX, SURFACE_Y, SIDE_CZ + 6); // 5.5 away: out of reach
        List<BlockPos> unpickedBeside = List.of(new BlockPos(SIDE_CX - 1, SURFACE_Y, SIDE_CZ + 2),
                new BlockPos(SIDE_CX - 1, SURFACE_Y + 1, SIDE_CZ - 2));
        List<BlockPos> unpickedWall = List.of(new BlockPos(SIDE_CX + 2, SURFACE_Y, SIDE_CZ),
                new BlockPos(SIDE_CX + 2, SURFACE_Y + 1, SIDE_CZ));
        Scenario.run(ctx, name,
                (server, scenario) -> {
                    TestMap map = TestMap.on(server)
                            .platform(SIDE_CX, fy, SIDE_CZ, 6)
                            .catchFloor(120)
                            .survival()
                            .clearInventory()
                            .give("diamond_shovel", 1)
                            .command("enchant @p minecraft:efficiency 5")
                            .command("effect give @p minecraft:haste 99999 4 true")
                            .command("effect clear @p minecraft:speed")
                            // Snow floor from his spawn to the wall (instant-break, so a dig on it would show),
                            // stone a block below so a broken floor drops him one block, not thirty.
                            .fill(SIDE_CX, fy - 1, SIDE_CZ, SIDE_CX + 1, fy - 1, SIDE_CZ, "stone")
                            .fill(SIDE_CX, fy, SIDE_CZ, SIDE_CX + 1, fy, SIDE_CZ, "snow_block");
                    for (BlockPos p : sidePicks) {
                        map.fill(p.getX(), p.getY(), p.getZ(), p.getX(), p.getY(), p.getZ(), "snow_block");
                    }
                    for (BlockPos p : unpickedBeside) {
                        map.fill(p.getX(), p.getY(), p.getZ(), p.getX(), p.getY(), p.getZ(), "snow_block");
                    }
                    for (BlockPos p : unpickedWall) {
                        map.fill(p.getX(), p.getY(), p.getZ(), p.getX(), p.getY(), p.getZ(), "snow_block");
                    }
                    map.fill(farPick.getX(), farPick.getY(), farPick.getZ(), farPick.getX(), farPick.getY(),
                                    farPick.getZ(), "snow_block")
                            .spawn(SIDE_CX + 0.5, SIDE_CZ + 0.5, -90f)
                            .build();
                },
                (server, scenario) -> {
                    scenario.assertDetectorWorks();
                    List<BlockPos> all = new java.util.ArrayList<>(sidePicks);
                    all.add(farPick);
                    all.add(underSpawn);
                    all.addAll(unpickedBeside);
                    all.addAll(unpickedWall);
                    for (BlockPos p : all) {
                        if (isAir(ctx, p)) {
                            throw new AssertionError("block " + xyz(p) + " was never built - the scenario would "
                                    + "measure nothing");
                        }
                    }
                    List<BlockPos> picks = new java.util.ArrayList<>(sidePicks);
                    picks.add(farPick);
                    // Watching BEFORE the aura is switched on: configure() waits a few ticks with it live, and
                    // the first run of this scenario saw all five picks go during that wait, uncounted.
                    PacketWatch.start();
                    configure(ctx, scenario, true, picks);
                    try {
                        // ---- 1: standing still, picks to either side
                        for (int i = 0; i < 40; i++) {
                            stampBreaker(ctx);
                            ctx.waitTicks(1);
                        }
                        PacketWatch.stop();
                        scenario.log("phase 1 (standing): " + PacketWatch.summary());
                        logDigShape(scenario);
                        List<BlockPos> dug1 = PacketWatch.digPositions();
                        int sideGone = 0;
                        StringBuilder standing = new StringBuilder();
                        for (BlockPos p : sidePicks) {
                            if (isAir(ctx, p)) {
                                sideGone++;
                            } else {
                                standing.append(' ').append(xyz(p));
                            }
                        }
                        scenario.log("phase 1: " + sideGone + " of " + sidePicks.size() + " side picks broken"
                                + (standing.length() > 0 ? ", still standing:" + standing : "")
                                + "; digs named " + dug1.size() + " block(s): " + dug1.stream().map(BreakerAuraTests::xyz).toList());
                        if (PacketWatch.totalBreaks() == 0) {
                            throw new AssertionError("no dig went out at all - Breaker Aura never acted, so a clean "
                                    + "anticheat result here means nothing");
                        }
                        if (sideGone != sidePicks.size()) {
                            throw new AssertionError("picked blocks beside him within reach were not all broken:"
                                    + standing);
                        }
                        if (isAir(ctx, farPick) || dug1.contains(farPick)) {
                            throw new AssertionError("the pick at " + xyz(farPick) + " is 5.5 blocks away, past "
                                    + "Reach, and was dug anyway");
                        }
                        // ---- 2: walk into a wall nobody picked
                        PacketWatch.start();
                        ctx.getInput().holdKey(options -> options.keyUp);
                        for (int i = 0; i < 20; i++) {
                            stampBreaker(ctx);
                            ctx.waitTicks(1);
                        }
                        ctx.getInput().releaseKey(options -> options.keyUp);
                        for (int i = 0; i < 5; i++) {
                            stampBreaker(ctx);
                            ctx.waitTicks(1);
                        }
                        PacketWatch.stop();
                        int bumps = PacketWatch.collisionTicks();
                        List<BlockPos> dug2 = PacketWatch.digPositions();
                        double[] pos = scenario.playerPosition();
                        scenario.log(String.format(Locale.ROOT, "phase 2 (walking into unpicked snow): %d dig(s), "
                                + "collided on %d of %d movement packets, stopped at x %.2f", dug2.size(), bumps,
                                PacketWatch.moveCount(), pos[0]));
                        if (bumps == 0) {
                            throw new AssertionError("he never touched the unpicked wall, so phase 2 proves nothing "
                                    + "about it being left alone");
                        }
                        // ---- nothing unpicked, ever
                        java.util.Set<BlockPos> allowed = new java.util.HashSet<>(picks);
                        List<BlockPos> stray = new java.util.ArrayList<>();
                        for (List<BlockPos> dug : List.of(dug1, dug2)) {
                            for (BlockPos p : dug) {
                                if (!allowed.contains(p)) {
                                    stray.add(p);
                                }
                            }
                        }
                        List<BlockPos> lost = new java.util.ArrayList<>();
                        for (List<BlockPos> group : List.of(unpickedBeside, unpickedWall, List.of(underSpawn,
                                underSpawn.east()))) {
                            for (BlockPos p : group) {
                                if (isAir(ctx, p)) {
                                    lost.add(p);
                                }
                            }
                        }
                        if (!stray.isEmpty() || !lost.isEmpty()) {
                            throw new AssertionError("Breaker Aura touched UNPICKED blocks: dug "
                                    + stray.stream().map(BreakerAuraTests::xyz).toList() + ", gone "
                                    + lost.stream().map(BreakerAuraTests::xyz).toList());
                        }
                        scenario.log("53 PASS: " + sidePicks.size() + " side picks broken, the out-of-reach pick and "
                                + "every unpicked block (wall walked into, the snow floor under him, neighbours) left "
                                + "alone, every dig named a picked block");
                    } finally {
                        unconfigure(ctx);
                    }
                });
    }

    /**
     * 55 / 56: a PICKED block under his feet breaks and he drops; the UNPICKED snow beside it stays. Measured, not
     * asserted clean.
     *
     * <p>killer560, 2026-10-05: "If it is selected for breaker aura it should break." Split out of 53 because GrimAC
     * flagged this one moment intermittently (GroundSpoof "claimed true" plus a 0.0784 Simulation offset, which is
     * exactly one tick of gravity: the client still stood on a block the server had already removed) - 1 in 2 runs
     * on the old jar and 1 in 5 on the new one, with the same code path in both. 55 runs it as shipped (Zero Ping
     * off: the client keeps the block until the server says otherwise); 56 runs it with Zero Ping on, where the
     * client drops the block the moment it sends. Both record what Grim said; neither fails on a flag. The
     * assertions are that it happened at all.
     */
    private void pickedFloor(ClientGameTestContext ctx, String name, int cz, boolean zeroPing, float pitch,
                             boolean turn) {
        if (Scenario.skip(name)) {
            return;
        }
        ModUnderTest.require(MOD_ID);
        int fy = SURFACE_Y - 1;
        BlockPos under = new BlockPos(FLOOR_CX, fy, cz);
        BlockPos beside = under.east();
        Scenario.runExpectingFlags(ctx, name,
                (server, scenario) -> TestMap.on(server)
                        .platform(FLOOR_CX, fy, cz, 3)
                        .catchFloor(120)
                        .survival()
                        .clearInventory()
                        .give("diamond_shovel", 1)
                        .command("enchant @p minecraft:efficiency 5")
                        .command("effect give @p minecraft:haste 99999 4 true")
                        .command("effect clear @p minecraft:speed")
                        .fill(FLOOR_CX, fy - 1, cz, FLOOR_CX + 1, fy - 1, cz, "stone")
                        .fill(FLOOR_CX, fy, cz, FLOOR_CX + 1, fy, cz, "snow_block")
                        .spawn(FLOOR_CX + 0.5, cz + 0.5, -90f, pitch)
                        .build(),
                (server, scenario) -> {
                    scenario.assertDetectorWorks();
                    if (isAir(ctx, under) || isAir(ctx, beside)) {
                        throw new AssertionError("the snow floor was never built");
                    }
                    double[] pos = scenario.playerPosition();
                    PacketWatch.start(); // before the aura goes live, see 53
                    // Zero Ping BEFORE the aura goes live, and the pick only once it is running. Until 2026-10-06 Zero
                    // Ping was set after configure(), whose 5-tick wait already broke the block - so 56 and 59 broke it
                    // with Zero Ping OFF and never measured Zero Ping at all.
                    ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(CONFIG), "setBreakerAuraZeroPing",
                            zeroPing));
                    configure(ctx, scenario, true, List.of());
                    try {
                        for (int i = 0; i < 30; i++) {
                            stampBreaker(ctx);
                            final int tick = i;
                            ctx.runOnClient(mc -> {
                                if (turn) {
                                    mc.player.setYRot(mc.player.getYRot() + 3f);
                                }
                                if (tick == 3) {
                                    setPicks(List.of(under));
                                }
                            });
                            ctx.waitTicks(1);
                        }
                        PacketWatch.stop();
                        ctx.waitTicks(20);
                        List<BlockPos> dug = PacketWatch.digPositions();
                        double[] after = scenario.playerPosition();
                        List<String> flags = scenario.flags();
                        scenario.log(String.format(Locale.ROOT, "%s: Zero Ping %s, pitch %.0f, %d dig(s) %s, feet y %.2f -> %.2f; "
                                        + "GrimAC said %d line(s)%s", name, zeroPing ? "ON" : "off", pitch, dug.size(),
                                dug.stream().map(BreakerAuraTests::xyz).toList(), pos[1], after[1], flags.size(),
                                flags.isEmpty() ? "" : ": " + flags));
                        logDigShape(scenario);
                        scenario.log(name + " timeline: " + PacketWatch.digTimeline());
                        if (dug.isEmpty()) {
                            throw new AssertionError("no dig went out - nothing was measured");
                        }
                        if (!isAir(ctx, under) || !dug.contains(under)) {
                            throw new AssertionError("the PICKED block under his feet (" + xyz(under) + ") was not "
                                    + "broken - killer560: \"If it is selected for breaker aura it should break.\"");
                        }
                        if (pos[1] - after[1] < 0.9) {
                            throw new AssertionError("the block under him is gone but he did not drop ("
                                    + pos[1] + " -> " + after[1] + ")");
                        }
                        if (isAir(ctx, beside) || dug.contains(beside)) {
                            throw new AssertionError("the UNPICKED snow beside it (" + xyz(beside) + ") was dug");
                        }
                    } finally {
                        ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(CONFIG), "setBreakerAuraZeroPing",
                                false));
                        unconfigure(ctx);
                    }
                });
    }

    /**
     * 57: THE CONTROL for 55/56 - the block under his feet broken by HAND (look straight down, hold attack), aura off.
     *
     * <p>55 and 56 draw GroundSpoof + a one-tick-of-gravity Simulation offset on some runs and not others. If a vanilla
     * dig of the same block does the same, that is GrimAC's handling of a floor vanishing under a player, not
     * something the aura adds. Measured, not asserted clean; the assertion is that the block went and he dropped.
     */
    private void floorByHand(ClientGameTestContext ctx, String name, int cz, boolean turn) {
        if (Scenario.skip(name)) {
            return;
        }
        int fy = SURFACE_Y - 1;
        int cx = -30;
        BlockPos under = new BlockPos(cx, fy, cz);
        Scenario.runExpectingFlags(ctx, name,
                (server, scenario) -> TestMap.on(server)
                        .platform(cx, fy, cz, 3)
                        .catchFloor(120)
                        .survival()
                        .clearInventory()
                        .give("diamond_shovel", 1)
                        .command("enchant @p minecraft:efficiency 5")
                        .command("effect give @p minecraft:haste 99999 4 true")
                        .command("effect clear @p minecraft:speed")
                        .fill(cx, fy - 1, cz, cx, fy - 1, cz, "stone")
                        .fill(cx, fy, cz, cx, fy, cz, "snow_block")
                        .spawn(cx + 0.5, cz + 0.5, -90f, 90f)
                        .build(),
                (server, scenario) -> {
                    scenario.assertDetectorWorks();
                    if (isAir(ctx, under)) {
                        throw new AssertionError("the snow floor was never built");
                    }
                    double[] pos = scenario.playerPosition();
                    PacketWatch.start();
                    if (turn) {
                        // Turning sideways only (yaw), so he keeps looking straight down at the block.
                        for (int i = 0; i < 4; i++) {
                            ctx.runOnClient(mc -> mc.player.setYRot(mc.player.getYRot() + 3f));
                            ctx.waitTicks(1);
                        }
                    }
                    ctx.getInput().holdKey(options -> options.keyAttack);
                    for (int i = 0; i < 3; i++) {
                        if (turn) {
                            ctx.runOnClient(mc -> mc.player.setYRot(mc.player.getYRot() + 3f));
                        }
                        ctx.waitTicks(1);
                    }
                    ctx.getInput().releaseKey(options -> options.keyAttack);
                    for (int i = 0; i < 30; i++) {
                        if (turn) {
                            ctx.runOnClient(mc -> mc.player.setYRot(mc.player.getYRot() + 3f));
                        }
                        ctx.waitTicks(1);
                    }
                    PacketWatch.stop();
                    scenario.log(name + " timeline: " + PacketWatch.digTimeline());
                    double[] after = scenario.playerPosition();
                    List<String> flags = scenario.flags();
                    scenario.log(String.format(Locale.ROOT, "%s: by hand, %d dig(s) %s, feet y %.2f -> %.2f; GrimAC said "
                                    + "%d line(s)%s", name, PacketWatch.totalBreaks(),
                            PacketWatch.digPositions().stream().map(BreakerAuraTests::xyz).toList(), pos[1], after[1],
                            flags.size(), flags.isEmpty() ? "" : ": " + flags));
                    if (!isAir(ctx, under) || pos[1] - after[1] < 0.9) {
                        throw new AssertionError("the hand did not break the block under him and drop him ("
                                + pos[1] + " -> " + after[1] + ") - the control measured nothing");
                    }
                });
    }

    /**
     * 54: picks BEHIND him and picks hidden BEHIND another block. Measured, not asserted clean.
     *
     * <p>He picks a block by looking at it, but a pick list outlives that moment: walk past a picked wall and it is
     * behind you; pick two layers of a wall and the back layer is dug through the front one. The aura does not turn
     * him or check line of sight (it never has: the face comes from clipping the block's own shape), so both go out
     * as they are. GrimAC's verdict on that shape is recorded here, with the numbers, for the risk write-up.
     */
    private void pickedBehindAndOccluded(ClientGameTestContext ctx) {
        String name = "54-breaker-aura-picked-behind-occluded";
        if (Scenario.skip(name)) {
            return;
        }
        ModUnderTest.require(MOD_ID);
        int fy = SURFACE_Y - 1;
        List<BlockPos> behind = List.of(new BlockPos(BEHIND_CX - 2, SURFACE_Y, BEHIND_CZ),
                new BlockPos(BEHIND_CX - 2, SURFACE_Y + 1, BEHIND_CZ));
        // Two-layer wall ahead: the FRONT layer is unpicked, the BACK layer is picked and in reach through it.
        List<BlockPos> front = List.of(new BlockPos(BEHIND_CX + 2, SURFACE_Y, BEHIND_CZ),
                new BlockPos(BEHIND_CX + 2, SURFACE_Y + 1, BEHIND_CZ));
        List<BlockPos> hidden = List.of(new BlockPos(BEHIND_CX + 3, SURFACE_Y, BEHIND_CZ),
                new BlockPos(BEHIND_CX + 3, SURFACE_Y + 1, BEHIND_CZ));
        Scenario.runExpectingFlags(ctx, name,
                (server, scenario) -> TestMap.on(server)
                        .platform(BEHIND_CX, fy, BEHIND_CZ, 6)
                        .catchFloor(120)
                        .survival()
                        .clearInventory()
                        .give("diamond_shovel", 1)
                        .command("enchant @p minecraft:efficiency 5")
                        .command("effect give @p minecraft:haste 99999 4 true")
                        .command("effect clear @p minecraft:speed")
                        .fill(BEHIND_CX - 2, SURFACE_Y, BEHIND_CZ, BEHIND_CX - 2, SURFACE_Y + 1, BEHIND_CZ, "snow_block")
                        .fill(BEHIND_CX + 2, SURFACE_Y, BEHIND_CZ, BEHIND_CX + 3, SURFACE_Y + 1, BEHIND_CZ, "snow_block")
                        .spawn(BEHIND_CX + 0.5, BEHIND_CZ + 0.5, -90f)
                        .build(),
                (server, scenario) -> {
                    scenario.assertDetectorWorks();
                    List<BlockPos> picks = new java.util.ArrayList<>(behind);
                    picks.addAll(hidden);
                    for (BlockPos p : picks) {
                        if (isAir(ctx, p)) {
                            throw new AssertionError("block " + xyz(p) + " was never built");
                        }
                    }
                    PacketWatch.start(); // before the aura goes live, see 53
                    configure(ctx, scenario, true, picks);
                    try {
                        for (int i = 0; i < 40; i++) {
                            stampBreaker(ctx);
                            ctx.waitTicks(1);
                        }
                        PacketWatch.stop();
                        ctx.waitTicks(20);
                        scenario.log(PacketWatch.summary());
                        logDigShape(scenario);
                        int gone = 0;
                        for (BlockPos p : picks) {
                            gone += isAir(ctx, p) ? 1 : 0;
                        }
                        int frontGone = 0;
                        for (BlockPos p : front) {
                            frontGone += isAir(ctx, p) ? 1 : 0;
                        }
                        List<String> flags = scenario.flags();
                        scenario.log("54: " + gone + " of " + picks.size() + " picks broken (2 behind him, 2 through "
                                + "an unpicked wall), unpicked front layer broken: " + frontGone + "; GrimAC said "
                                + flags.size() + " line(s)" + (flags.isEmpty() ? "" : ": " + flags));
                        if (PacketWatch.totalBreaks() == 0) {
                            throw new AssertionError("no dig went out - nothing was measured");
                        }
                        if (frontGone > 0) {
                            throw new AssertionError("the UNPICKED front layer was broken");
                        }
                    } finally {
                        unconfigure(ctx);
                    }
                });
    }

    /**
     * Make the held shovel look like a Hypixel Dungeon Breaker <b>to the client</b>.
     *
     * <p>The module reads the item off the client's own inventory to decide whether it holds a breaker and
     * how many charges are left, so stamping the components client-side is enough and needs no guesses
     * about command-syntax for components. The server neither needs nor gets them: it only ever sees the
     * break packets, which is exactly the traffic under test.
     */
    private void stampBreaker(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            mc.player.getInventory().setSelectedSlot(0);
            ItemStack stack = mc.player.getInventory().getItem(0);
            if (stack.isEmpty()) {
                return;
            }
            CompoundTag tag = new CompoundTag();
            tag.putString("id", "DUNGEONBREAKER");
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
            stack.set(DataComponents.LORE, new ItemLore(
                    List.of(Component.literal("Charges: 5000/5000"))));
        });
    }

    /** How much of the corridor is still there, counted client-side - so it counts confirmed breaks. */
    private int countWall(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            int standing = 0;
            for (int x = WALL_FROM_X; x <= WALL_TO_X; x++) {
                for (int y = SURFACE_Y; y <= SURFACE_Y + 1; y++) {
                    for (int z = WALL_Z1; z <= WALL_Z2; z++) {
                        if (!mc.level.getBlockState(new BlockPos(x, y, z)).isAir()) {
                            standing++;
                        }
                    }
                }
            }
            return standing;
        });
    }
}
