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

    /** Side Reach the aura scenarios run with: wide enough that the higher rates have a queue to spend. */
    private static final double SIDE_REACH = 1.6;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        speedControl(ctx);
        vanillaControl(ctx);
        // Sprint speed, shipped rate: the clean baseline the packet-ordering fix is proven against.
        breakerAura(ctx, "50-breaker-aura-default", false, SIDE_REACH, false);
        // Speed V and the full rate: the configuration that keeps up with the wall.
        breakerAura(ctx, "51-breaker-aura-multi", true, SIDE_REACH, true);
        // Speed V against the shipped defaults, which cannot keep up. Documented, not asserted clean.
        highSpeedDesync(ctx);
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

    private void breakerAura(ClientGameTestContext ctx, String name, boolean multiBreak, double sideReach,
                             boolean speedPotion) {
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

                    configure(ctx, scenario, multiBreak, sideReach);

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
                });
    }

    /**
     * The shipped defaults at high speed, which DO flag - recorded rather than asserted clean.
     *
     * <p>Every other scenario here comes back clean, and that made this easy to miss. The anticheat's
     * movement prediction objects, with sub-half-block offsets, once the player is fast enough that the wall
     * is not cleared ahead of them. At Blocks Per Cycle 1 with Side Reach off - exactly what ships - most
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
                    configure(ctx, scenario, false, 0.0);
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
                });
    }

    /** Turn the module on exactly the way his own menu does, and nothing else. */
    private void configure(ClientGameTestContext ctx, Scenario scenario, boolean multiBreak,
                           double sideReach) {
        ctx.runOnClient(mc -> {
            Object cfg = ModUnderTest.config(CONFIG);
            ModUnderTest.set(cfg, "setBreakerAuraEnabled", true);
            // His saved default is "selected blocks only", which needs picks placed by hand. Path mode is
            // the one that runs itself, and the one he was complaining about walking into.
            ModUnderTest.set(cfg, "setBreakerAuraSelectedOnly", false);
            ModUnderTest.set(cfg, "setBreakerAuraAutoSwap", false);
            ModUnderTest.set(cfg, "setBreakerAuraMultiBreak", multiBreak);
            // killer560 (2026-09-27): "allow it to break snow to the side of it as well so that way it can
            // break way more than 1 per second if it is working right." Ships at 0.0, so the scenario turns
            // it up: without it the higher Blocks Per Cycle settings have nothing queued to spend and both
            // rates measure the same thing.
            ModUnderTest.set(cfg, "setBreakerAuraSideReach", sideReach);
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
        scenario.log("Breaker Aura on, path mode, Multi Break " + (multiBreak ? "ON" : "off")
                + ", Side Reach " + sideReach + ", dungeon override forced");
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
