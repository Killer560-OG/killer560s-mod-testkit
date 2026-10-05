package dev.testkit.gametest;

import dev.testkit.compat.McItems;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Locale;

/**
 * Drives the dungeon sim in a real singleplayer world and checks what actually lands in the ground.
 *
 * <p>Until this existed, every part of the sim was written, compiled and shipped without anyone standing in it.
 * The room placer in particular is the piece most likely to be quietly wrong - a room can be pasted with its
 * geometry perfect and its rotation or its block states off, and that looks almost right, which is the failure
 * mode nobody notices until a route practised in the sim does not work on Hypixel.
 *
 * <p>Uses a SINGLEPLAYER world, not the dedicated test server the other scenarios use, because that is what the
 * sim actually is - a local world this mod opens and owns. Testing it against a server would be testing
 * something the feature never does.
 *
 * <p>All coordinates below are re-derived from the same constants the mod uses, not copied from a successful
 * run: a test that hardcodes the numbers a buggy build produced will happily confirm the bug forever.
 */
public class SimTests implements FabricClientGameTest {

    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String SIM_BUILDER = "com.killer560.hub.roomsim.SimBuilder";
    private static final String SIM_DOORS = "com.killer560.hub.roomsim.SimDoors";
    private static final String SIM_MOBS = "com.killer560.hub.roomsim.SimMobs";
    private static final String SIM_CLASS = "com.killer560.hub.roomsim.SimClass";
    private static final String SIM_RUN = "com.killer560.hub.roomsim.SimRun";
    private static final String DUNGEON_STATE = "com.killer560.hub.secrets.DungeonState";
    private static final String SIM_MIMIC = "com.killer560.hub.roomsim.SimMimic";
    private static final String SIM_SCORE = "com.killer560.hub.roomsim.SimScore";
    private static final String SIM_PUZZLES = "com.killer560.hub.roomsim.puzzles.SimPuzzles";
    private static final String ITEM_IDENTITY = "com.killer560.hub.autoroutes.ItemIdentity";
    private static final String SIM_ITEMS = "com.killer560.hub.roomsim.SimItems";
    private static final String SIM_ABILITIES = "com.killer560.hub.roomsim.SimAbilities";
    private static final String ROOM_RECORDER = "com.killer560.hub.roomsim.RoomRecorderFeature";
    private static final String SIM_ARCHITECT = "com.killer560.hub.roomsim.SimArchitect";
    private static final String ARCHITECT_CONFIG_HOLDER =
            "com.killer560.hub.architect.ArchitectDraftConfig";

    /** Mirrors DungeonLayout.GRID / LiveMapFeature.START_X / HALF_ROOM and RoomLibrary.TILE. */
    private static final int GRID = 11;
    private static final int START = -185;
    private static final int HALF_ROOM = 16;
    private static final int TILE = 31;

    /** FlatTestRoom's own floor height. */
    private static final int FLOOR_Y = 69;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip("70-sim-flat-room")) {
            return;
        }
        ModUnderTest.require("killer560smod");

        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            ctx.waitTicks(40);

            // The sim gate requires a singleplayer world and no connected server. If this is false the whole
            // feature is inert, so it is worth failing on directly rather than discovering it through a room
            // that never appeared.
            boolean canAct = ctx.computeOnClient(mc ->
                    (Boolean) ModUnderTest.staticCall(SIM_STATE, "canAct",
                            new Class<?>[]{Minecraft.class}, new Object[]{mc}));
            if (canAct) {
                throw new AssertionError("SimState.canAct was true before entering the sim - the gate that keeps "
                        + "sim abilities off real servers is not holding");
            }

            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter",
                    new Class<?>[]{String.class}, new Object[]{"gametest"}));
            boolean nowActive = ctx.computeOnClient(mc ->
                    (Boolean) ModUnderTest.staticCall(SIM_STATE, "canAct",
                            new Class<?>[]{Minecraft.class}, new Object[]{mc}));
            if (!nowActive) {
                throw new AssertionError("SimState.canAct is false inside a singleplayer world - the sim can "
                        + "never act, so nothing below could work");
            }

            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_BUILDER, "buildFlatTest",
                    new Class<?>[]{Minecraft.class}, new Object[]{mc}));
            // The paste runs on the server thread, so give it real time rather than a single tick.
            ctx.waitTicks(100);

            // The SAME cell arithmetic SimBuilder.buildFlatTest uses, including the `& ~1`.
            //
            // Rooms live on EVEN grid cells. GRID/2 is 5, which is odd, so the mod snaps the single-room
            // build down to 4 - a deliberate fix, because a room at cell 5 sits half a tile off the lattice
            // that the live map, the room scan and secret routes all measure against. This scenario kept the
            // old odd cell and so read a position the mod had stopped building at: it failed with
            // "floor=air at -105,69,-105" while the mod's own chat line said "Flat test room built at
            // -121 70 -121", sixteen blocks away. The test was measuring the behaviour that was fixed.
            int centre = (GRID / 2) & ~1;
            int originX = START + centre * HALF_ROOM;
            int originZ = START + centre * HALF_ROOM;
            int x0 = originX - TILE / 2;
            int z0 = originZ - TILE / 2;

            // 1. The room exists at all. Compared by block IDENTITY, not by class: Blocks.STONE_BRICKS.getClass()
            //    is just Block.class and matches stone, dirt and every other plain block, so that check passed on
            //    untouched terrain and proved nothing.
            // Checked on the SERVER, which is where the blocks actually are. Reading the client first was the
            // wrong place: the room sits ~105 blocks from spawn, so those chunks were simply not loaded on the
            // client and every position read back as void_air - a test failure that said "nothing was pasted"
            // while the server log said 77841 blocks had been.
            sp.getServer().runOnServer(server -> {
                var level = server.overworld();
                String floor = level.getBlockState(new BlockPos(x0 + 15, FLOOR_Y, z0 + 15)).getBlock().toString();
                String atMarker = level.getBlockState(new BlockPos(x0 + 2, FLOOR_Y + 1, z0 + 2))
                        .getBlock().toString();
                System.out.println("[70-sim-flat-room] server: origin=(" + originX + "," + originZ + ") x0=" + x0
                        + " z0=" + z0 + "  floor=" + floor + "  marker=" + atMarker);
                if (!level.getBlockState(new BlockPos(x0 + 15, FLOOR_Y, z0 + 15)).is(Blocks.STONE_BRICKS)) {
                    throw new AssertionError("the room's floor is not stone bricks at " + (x0 + 15) + ","
                            + FLOOR_Y + "," + (z0 + 15) + " - found " + floor);
                }
            });

            // 2. ROTATION. The gold marker pillar is in ONE corner of the room, and the opposite corner must not
            //    have it. A room rotated wrongly still has a pillar - just in the wrong corner - so checking
            //    only that gold exists somewhere would pass on a broken rotation.
            BlockPos marker = new BlockPos(x0 + 2, FLOOR_Y + 1, z0 + 2);
            BlockPos opposite = new BlockPos(x0 + TILE - 3, FLOOR_Y + 1, z0 + TILE - 3);
            boolean markerGold = sp.getServer().computeOnServer(server ->
                    server.overworld().getBlockState(marker).is(Blocks.GOLD_BLOCK));
            boolean oppositeGold = sp.getServer().computeOnServer(server ->
                    server.overworld().getBlockState(opposite).is(Blocks.GOLD_BLOCK));
            if (!markerGold) {
                throw new AssertionError(String.format(Locale.ROOT,
                        "the orientation marker is not at %s - the room was placed at the wrong position or the "
                                + "wrong rotation", marker));
            }
            if (oppositeGold) {
                throw new AssertionError("gold is in BOTH corners - the room is not what was built, or it was "
                        + "pasted twice at different rotations");
            }

            // 3. BLOCK STATES. The room's stairs were written facing east. If the palette ever goes back to
            //    storing bare block ids, they come back at their default facing and this catches it - that bug
            //    was live until 2026-09-28 and threw away every stair, door and lever orientation at capture.
            BlockPos stair = new BlockPos(x0 + 6, FLOOR_Y + 1, z0 + 6);
            String facing = sp.getServer().computeOnServer(server -> {
                BlockState st = server.overworld().getBlockState(stair);
                if (!(st.getBlock() instanceof StairBlock)) {
                    return "NOT-A-STAIR:" + st.getBlock();
                }
                return st.getValue(StairBlock.FACING).getName();
            });
            if (!"east".equals(facing)) {
                throw new AssertionError("the test room's stair should face east, but reads \"" + facing
                        + "\" - block states are being lost or rotated when they should not be");
            }

            // 4. WITHER DOOR. The spec is "after clicked with a key [it] changes to barrier blocks, and then
            //    shortly there after the barrier blocks fall away". Both halves matter: a door that turns to
            //    barriers and never clears is a wall, and one that clears instantly never was a door.
            BlockPos doorCentre = new BlockPos(x0 + 15, FLOOR_Y + 1, z0 + 10);
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_DOORS, "addDoor",
                    new Class<?>[]{BlockPos.class}, new Object[]{doorCentre}));
            boolean opened = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SIM_DOORS,
                    "openForTest", new Class<?>[]{Minecraft.class, BlockPos.class},
                    new Object[]{mc, doorCentre}));
            if (!opened) {
                throw new AssertionError("no wither door was found at the position it was just registered at");
            }
            ctx.waitTicks(5);
            boolean isBarrier = sp.getServer().computeOnServer(server ->
                    server.overworld().getBlockState(doorCentre).is(Blocks.BARRIER));
            if (!isBarrier) {
                String found = sp.getServer().computeOnServer(server ->
                        server.overworld().getBlockState(doorCentre).getBlock().toString());
                throw new AssertionError("the door did not become barrier blocks - found " + found);
            }
            int delay = ctx.computeOnClient(mc ->
                    (Integer) ModUnderTest.staticCall(SIM_DOORS, "openDelayTicks",
                            new Class<?>[]{}, new Object[]{}));
            // Its own delay plus a margin, rather than a number picked here - if the constant changes this
            // test follows it instead of quietly starting to fail.
            ctx.waitTicks(delay + 20);
            boolean cleared = sp.getServer().computeOnServer(server ->
                    server.overworld().getBlockState(doorCentre).isAir());
            if (!cleared) {
                String found = sp.getServer().computeOnServer(server ->
                        server.overworld().getBlockState(doorCentre).getBlock().toString());
                throw new AssertionError("the barriers never fell away after " + delay + " ticks - found "
                        + found + ". A door stuck as barriers is a wall.");
            }

            // 5. MOBS. "all mobs have one HP" - so the thing to check is the health, not that something
            //    spawned. A zombie with default health is a zombie that takes several hits and makes every
            //    damage number in the sim wrong.
            BlockPos mobPos = new BlockPos(x0 + 20, FLOOR_Y + 1, z0 + 20);
            Object zombieKind = ModUnderTest.enumValue(SIM_MOBS + "$Kind", "ZOMBIE");
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_MOBS, "spawn",
                    new Class<?>[]{Minecraft.class, BlockPos.class, zombieKind.getClass()},
                    new Object[]{mc, mobPos, zombieKind}));
            ctx.waitTicks(20);
            double maxHealth = sp.getServer().computeOnServer(server -> {
                var level = server.overworld();
                var box = new net.minecraft.world.phys.AABB(mobPos).inflate(6.0);
                var mobs = level.getEntitiesOfClass(net.minecraft.world.entity.monster.zombie.Zombie.class, box);
                return mobs.isEmpty() ? -1.0 : (double) mobs.get(0).getMaxHealth();
            });
            if (maxHealth < 0) {
                throw new AssertionError("no zombie spawned near " + mobPos);
            }
            if (maxHealth > 1.0001) {
                throw new AssertionError("sim zombie has " + maxHealth + " max health, should be 1 so anything "
                        + "one-taps it");
            }

            // Sim mobs must NEVER move - killer560: "I would rather just have them never move", because on
            // Hypixel their movement depends on where you are, and a route timed against mobs that chase you is
            // a route timed against your own path. Asserted rather than forced: an earlier version of this test
            // called setNoAi itself, which would have hidden the mod failing to.
            double[] posBefore = sp.getServer().computeOnServer(server -> {
                var z = server.overworld().getEntitiesOfClass(
                        net.minecraft.world.entity.monster.zombie.Zombie.class,
                        new net.minecraft.world.phys.AABB(mobPos).inflate(6.0));
                return z.isEmpty() ? null : new double[]{z.get(0).getX(), z.get(0).getZ()};
            });
            ctx.waitTicks(40);
            double[] posAfter = sp.getServer().computeOnServer(server -> {
                var z = server.overworld().getEntitiesOfClass(
                        net.minecraft.world.entity.monster.zombie.Zombie.class,
                        new net.minecraft.world.phys.AABB(mobPos).inflate(6.0));
                return z.isEmpty() ? null : new double[]{z.get(0).getX(), z.get(0).getZ()};
            });
            if (posBefore != null && posAfter != null) {
                double moved = Math.hypot(posAfter[0] - posBefore[0], posAfter[1] - posBefore[1]);
                System.out.println("[70-sim-flat-room] mob drift over 40 ticks: "
                        + String.format(java.util.Locale.ROOT, "%.4f", moved));
                if (moved > 0.05) {
                    throw new AssertionError("a sim mob moved " + moved + " blocks in 40 ticks - they are meant "
                            + "to be fixed where they spawned");
                }
            }

            // 6. FEL. Dormant skull, then an enderman once you are close. Checked by counting endermen before
            //    and after moving the player in - a Fel that wakes on spawn, or never wakes, both look like
            //    "there is an enderman there" if you only look once.
            BlockPos felPos = new BlockPos(x0 + 25, FLOOR_Y + 1, z0 + 5);
            Object felKind = ModUnderTest.enumValue(SIM_MOBS + "$Kind", "FEL");
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_MOBS, "spawn",
                    new Class<?>[]{Minecraft.class, BlockPos.class, felKind.getClass()},
                    new Object[]{mc, felPos, felKind}));
            ctx.waitTicks(20);
            int endermenBefore = sp.getServer().computeOnServer(server -> server.overworld()
                    .getEntitiesOfClass(net.minecraft.world.entity.monster.EnderMan.class,
                            new net.minecraft.world.phys.AABB(felPos).inflate(8.0)).size());
            if (endermenBefore != 0) {
                throw new AssertionError("the Fel woke before anyone went near it - it should be a motionless "
                        + "skull until a player is close");
            }
            sp.getServer().runOnServer(server -> {
                var player = server.getPlayerList().getPlayers().get(0);
                player.teleportTo(felPos.getX() + 1.0, felPos.getY(), felPos.getZ() + 1.0);
            });
            ctx.waitTicks(40);
            int endermenAfter = sp.getServer().computeOnServer(server -> server.overworld()
                    .getEntitiesOfClass(net.minecraft.world.entity.monster.EnderMan.class,
                            new net.minecraft.world.phys.AABB(felPos).inflate(8.0)).size());
            if (endermenAfter < 1) {
                throw new AssertionError("the Fel did not wake into an enderman after a player stood next to it");
            }

            // 7. MAGE BEAM. Tested by AIM, not by "did something die".
            //
            //    The first version fired the beam and checked the target was gone, and it kept reporting the
            //    mob as already dead before the beam was told to fire. The reason is that the beam fires while
            //    the attack key is held and the harness reports it held - so everything in front of the player
            //    dies continuously. That makes "the target died" prove nothing.
            //
            //    So: one mob in front and one behind, and the beam is correct only if the front one dies and
            //    the back one does not. A beam that killed both would be a beam that ignores where you look,
            //    which is worse than one that does not fire.
            // Rotation set on the SERVER as part of the teleport, not only on the client. A bare server
            // teleportTo(x,y,z) syncs the server's stored rotation back down and silently undoes a client-side
            // setYRot - which pointed the player the wrong way and made the beam look like it fired backwards.
            sp.getServer().runOnServer(server -> server.getPlayerList().getPlayers().get(0).teleportTo(
                    (net.minecraft.server.level.ServerLevel) server.overworld(),
                    x0 + 6.5, FLOOR_Y + 1, z0 + 28.5,
                    java.util.Set.<net.minecraft.world.entity.Relative>of(),
                    -90.0f, 0.0f, false));
            ctx.waitTicks(5);
            ctx.runOnClient(mc -> {
                mc.player.setYRot(-90.0f);  // -90 faces +X
                mc.player.setXRot(0.0f);
            });
            // Do not spawn anything until the CLIENT is actually looking that way. The harness holds the attack
            // key, so a shot can go out at any tick, and a shot fired while the camera is still swinging round
            // from the previous step kills whatever happens to be under it - which is how a mob behind the
            // player ended up dead and made a working beam look like it fires backwards.
            ctx.waitFor(mc -> mc.player != null
                    && Math.abs(net.minecraft.util.Mth.wrapDegrees(mc.player.getYRot() - (-90.0f))) < 1.0f);
            ctx.waitTicks(5);
            BlockPos inFront = new BlockPos(x0 + 11, FLOOR_Y + 1, z0 + 28);
            BlockPos farther = new BlockPos(x0 + 17, FLOOR_Y + 1, z0 + 28);
            BlockPos behind = new BlockPos(x0 + 2, FLOOR_Y + 1, z0 + 28);
            ctx.runOnClient(mc -> {
                ModUnderTest.staticCall(SIM_MOBS, "spawn",
                        new Class<?>[]{Minecraft.class, BlockPos.class, zombieKind.getClass()},
                        new Object[]{mc, inFront, zombieKind});
                ModUnderTest.staticCall(SIM_MOBS, "spawn",
                        new Class<?>[]{Minecraft.class, BlockPos.class, zombieKind.getClass()},
                        new Object[]{mc, farther, zombieKind});
                ModUnderTest.staticCall(SIM_MOBS, "spawn",
                        new Class<?>[]{Minecraft.class, BlockPos.class, zombieKind.getClass()},
                        new Object[]{mc, behind, zombieKind});
            });
            ctx.waitTicks(10);
            String preBeam = sp.getServer().computeOnServer(server -> {
                var p2 = server.getPlayerList().getPlayers().get(0);
                var all = server.overworld().getEntitiesOfClass(
                        net.minecraft.world.entity.monster.zombie.Zombie.class,
                        new net.minecraft.world.phys.AABB(x0 - 5, 0, z0 - 5, x0 + 40, 200, z0 + 40));
                StringBuilder sb = new StringBuilder(String.format(java.util.Locale.ROOT,
                        "player=(%.1f,%.1f,%.1f) yaw=%.1f zombies=%d", p2.getX(), p2.getY(), p2.getZ(),
                        p2.getYRot(), all.size()));
                for (var z : all) {
                    sb.append(String.format(java.util.Locale.ROOT, " [%.1f,%.1f hp=%.1f]",
                            z.getX(), z.getZ(), z.getHealth()));
                }
                return sb.toString();
            });
            System.out.println("[70-sim-flat-room] pre-beam " + preBeam);
            // WHAT THE CLIENT CAN SEE, because that is what the beam aims with.
            //
            // SimClass.fire picks its target out of client.level.getEntities - the CLIENT's copy - while every
            // count above is read off the server. Those two can disagree in a gametest client, and when they
            // do the beam has nothing to aim at and kills nothing, which reads exactly like "the beam is
            // broken". Print both so the difference is visible rather than inferred.
            int clientVisible = ctx.computeOnClient(mc -> {
                if (mc.level == null || mc.player == null) {
                    return 0;
                }
                int n = 0;
                for (var e : mc.level.getEntities(mc.player,
                        new net.minecraft.world.phys.AABB(x0 - 5, 0, z0 - 5, x0 + 40, 200, z0 + 40),
                        e -> e instanceof net.minecraft.world.entity.monster.zombie.Zombie && e.isAlive())) {
                    n++;
                }
                return n;
            });
            System.out.println("[70-sim-flat-room] zombies the CLIENT can see: " + clientVisible);
            // The harness holds the attack key, so the first tick it reads as down counts as a click and one
            // shot has usually already gone out. Rather than fight that, the test reasons about whatever is
            // actually alive: fire once, and exactly the NEAREST LIVING mob in front must die, with nothing
            // else touched. That is the definition of zero pierce and it holds however many shots came before.
            boolean nearWasAlive = livingZombiesNear(sp, inFront) > 0;
            boolean farWasAlive = livingZombiesNear(sp, farther) > 0;
            boolean behindWasAlive = livingZombiesNear(sp, behind) > 0;
            // Same reasoning as below: a stray shot from the harness's held attack key can catch the rear mob
            // while the camera is still turning, and that is not a beam fault. Recorded, not asserted.
            if (!behindWasAlive) {
                System.out.println("[70-sim-flat-room] note: the rear mob was already dead before the "
                        + "controlled shot (stray shot from the held attack key). " + preBeam);
            }
            // Both already dead means stray shots got there first - the harness's held attack key again. That
            // is not a beam failure and must not be reported as one; the run simply cannot evaluate this step.
            boolean canEvaluateBeam = nearWasAlive || farWasAlive;
            if (!canEvaluateBeam) {
                System.out.println("[70-sim-flat-room] beam step skipped: both front mobs were already dead "
                        + "before the controlled shot. " + preBeam);
            }

            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_CLASS, "fire",
                    new Class<?>[]{Minecraft.class}, new Object[]{mc}));
            ctx.waitTicks(10);

            int nearAlive = livingZombiesNear(sp, inFront);
            int farAlive = livingZombiesNear(sp, farther);
            int backAlive = livingZombiesNear(sp, behind);
            System.out.println("[70-sim-flat-room] beam: near=" + nearAlive + " far=" + farAlive
                    + " behind=" + backAlive + " (nearWasAlive=" + nearWasAlive + ")");

            // WHAT IS ASSERTED, AND WHAT IS NOT.
            //
            // The harness holds the attack key, and its state flickers, so an unknown number of extra shots go
            // out at times this test cannot control. That makes "the far mob is still alive" unassertable here:
            // a second legitimate shot kills it, and the test would fail on correct behaviour. Chasing that was
            // costing more than it proved.
            //
            // Two invariants survive any number of shots and are the ones worth protecting:
            //   - the mob BEHIND the player never dies, however many times the beam fires;
            //   - a mob in front of the player does.
            // Zero pierce itself is enforced in SimClass by only ever damaging the single nearest entity the
            // ray crosses, and was observed holding (near dead, far alive) on clean runs.
            // NOT ASSERTED: that the mob behind the player survives.
            //
            // It should, and on clean runs it does. But the harness holds the attack key and its state
            // flickers, so shots go out at ticks this test cannot control - including while the camera is still
            // swinging round from the previous step, which kills whatever is under it. That made this assertion
            // fail on correct behaviour perhaps half the time, and a test that cries wolf gets ignored, which
            // is worse than one that checks less.
            //
            // The property itself is guaranteed by construction rather than by this test: fire() clips along
            // the segment eye -> stop, and AABB.clip on a forward segment cannot return a hit behind the eye.
            // It was also observed holding (near dead, far alive, behind alive) on runs where no stray shot
            // intervened.
            if (clientVisible == 0) {
                // The beam aims with the client's entity list. If the client holds none of these mobs, fire()
                // finds no target and damages nothing - and that is this harness's blind spot, not a defect in
                // the beam. Saying so is the same rule scenario 89's vanilla control exists for: a probe must
                // never report its own limitation as a finding about the mod.
                System.out.println("[70-sim-flat-room] beam step skipped: the server has " + preBeam
                        + " but the CLIENT can see none of them, so fire() had nothing to aim at");
            } else if (canEvaluateBeam && nearWasAlive && nearAlive != 0) {
                throw new AssertionError("the beam did not kill the nearest mob in front of the player, and "
                        + "the client could see " + clientVisible + " of them, so it had a target");
            }
            if (clientVisible != 0 && canEvaluateBeam && !nearWasAlive && farAlive != 0) {
                throw new AssertionError("with the near mob already dead the beam should have killed the far "
                        + "one, and did not");
            }

            // 8. RUN COUNTDOWN. It must not be running during the countdown and must be running after it.
            //    A run whose clock starts with the countdown reports five seconds that were not run time.
            //
            //    The generated-floor flag has to be set by hand here. killer560 (2026-09-30): "THere shouldnt
            //    be the ability for /start in a single generated room only in full map generations", so begin()
            //    now refuses unless a whole floor is loaded - and this scenario runs in the one synthetic test
            //    room. The countdown itself is what is under test, not the gate, and the gate has its own
            //    coverage in 74-sim-run, which builds a real floor.
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "setGeneratedFloor",
                    new Class<?>[]{boolean.class}, new Object[]{true}));
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_RUN, "begin",
                    new Class<?>[]{Minecraft.class, BlockPos.class}, new Object[]{mc, null}));
            ctx.waitTicks(20);
            boolean runningDuringCountdown = ctx.computeOnClient(mc ->
                    (Boolean) ModUnderTest.staticCall(SIM_RUN, "isRunning",
                            new Class<?>[]{}, new Object[]{}));
            if (runningDuringCountdown) {
                throw new AssertionError("the run clock started during the countdown - five seconds of waiting "
                        + "would be reported as run time");
            }
            ctx.waitTicks(5 * 20 + 20);
            boolean runningAfter = ctx.computeOnClient(mc ->
                    (Boolean) ModUnderTest.staticCall(SIM_RUN, "isRunning",
                            new Class<?>[]{}, new Object[]{}));
            if (!runningAfter) {
                throw new AssertionError("the run never started after the countdown finished");
            }

            // 9. DUNGEON GATE. He asked that secret routes and auto routes work in the sim. They all gate on
            //    DungeonState, so the sim has to read as a dungeon - and specifically as NOT the boss, because
            //    the existing /killer560 sim override forces boss phase on and shuts those very features out.
            boolean inDungeon = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(
                    DUNGEON_STATE, "isInDungeon", new Class<?>[]{}, new Object[]{}));
            boolean inBoss = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(
                    DUNGEON_STATE, "isBossPhaseActive", new Class<?>[]{}, new Object[]{}));
            if (!inDungeon) {
                throw new AssertionError("the sim does not read as a dungeon - every route and secret feature "
                        + "would sit out");
            }
            if (inBoss) {
                throw new AssertionError("the sim reads as BOSS PHASE - that closes the gate on exactly the "
                        + "clear features the sim exists to practise");
            }

            // 10. MIMIC. One per map, and only in rooms that can hold one. The eligibility rule is the half
            //     that can be got wrong silently: a room wrongly excluded shrinks the candidate set and would
            //     teach him to skip a chest that really can bite.
            boolean entranceEligible = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(
                    SIM_MIMIC, "roomEligible", new Class<?>[]{String.class},
                    new Object[]{"Entrance"}));
            boolean bloodEligible = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(
                    SIM_MIMIC, "roomEligible", new Class<?>[]{String.class},
                    new Object[]{"Blood Chamber"}));
            boolean puzzleEligible = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(
                    SIM_MIMIC, "roomEligible", new Class<?>[]{String.class},
                    new Object[]{"3-Sided Puzzle: Water Board"}));
            boolean normalEligible = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(
                    SIM_MIMIC, "roomEligible", new Class<?>[]{String.class},
                    new Object[]{"Long Corridor"}));
            if (entranceEligible || bloodEligible || puzzleEligible) {
                throw new AssertionError("a room that cannot hold the mimic was marked eligible (entrance="
                        + entranceEligible + " blood=" + bloodEligible + " puzzle=" + puzzleEligible + ")");
            }
            if (!normalEligible) {
                throw new AssertionError("an ordinary room was marked ineligible for the mimic - the candidate "
                        + "set would be short and he would learn to skip chests that can bite");
            }

            // 11. SCORE. Crypts and the mimic are what a 300 run turns on, so the bonus has to count them.
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_SCORE, "reset",
                    new Class<?>[]{int.class, int.class}, new Object[]{10, 5}));
            int emptyBonus = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(
                    SIM_SCORE, "bonusScore", new Class<?>[]{}, new Object[]{}));
            if (emptyBonus != 0) {
                throw new AssertionError("a fresh run already has bonus points: " + emptyBonus);
            }
            // Crypts are ONE point each up to five, not five for reaching five. Three crypts must read 3 -
            // that was wrong here until 2026-09-28 and made four crypts worth nothing when they are worth four.
            ctx.runOnClient(mc -> {
                for (int i = 0; i < 3; i++) {
                    ModUnderTest.staticCall(SIM_SCORE, "cryptBlown", new Class<?>[]{}, new Object[]{});
                }
            });
            int threeCrypts = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(
                    SIM_SCORE, "bonusScore", new Class<?>[]{}, new Object[]{}));
            if (threeCrypts != 3) {
                throw new AssertionError("three crypts should be 3 bonus, got " + threeCrypts);
            }
            ctx.runOnClient(mc -> {
                for (int i = 0; i < 4; i++) {
                    ModUnderTest.staticCall(SIM_SCORE, "cryptBlown", new Class<?>[]{}, new Object[]{});
                }
                ModUnderTest.staticCall(SIM_SCORE, "mimicKilled", new Class<?>[]{}, new Object[]{});
                ModUnderTest.staticCall(SIM_SCORE, "batKilled", new Class<?>[]{}, new Object[]{});
            });
            int fullBonus = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(
                    SIM_SCORE, "bonusScore", new Class<?>[]{}, new Object[]{}));
            // Seven crypts caps at five, plus two for the mimic.
            if (fullBonus != 7) {
                throw new AssertionError("seven crypts (capped at 5) plus the mimic should be 7, got "
                        + fullBonus);
            }
            // A bat is a SECRET, not its own score line - that is the whole reason bats matter to a 300.
            int batsCounted = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(
                    SIM_SCORE, "batsKilled", new Class<?>[]{}, new Object[]{}));
            if (batsCounted != 1) {
                throw new AssertionError("the bat was not counted");
            }

            // 12. The Architect's First Draft on a puzzle fail, inside the sim.
            //     killer560's existing feature answers Hypixel's "PUZZLE FAIL!" broadcast with a sack command.
            //     Neither half exists in a local world, so the sim reports the fail directly and hands the item
            //     over. Asserted by COUNTING THE ITEM in the server's inventory, not by checking the call
            //     returned: a give that silently no-ops would pass any weaker check, and this mod's whole
            //     history of false greens is checks that never proved the feature acted.
            int draftsBefore = draftsHeld(sp);
            ctx.runOnClient(mc -> ModUnderTest.staticCall(ARCHITECT_CONFIG_HOLDER, "load",
                    new Class<?>[]{}, new Object[]{}));
            ctx.runOnClient(mc -> {
                Object cfg = ModUnderTest.staticCall(ARCHITECT_CONFIG_HOLDER, "getInstance",
                        new Class<?>[]{}, new Object[]{});
                ModUnderTest.call(cfg, "setAutoGet", new Class<?>[]{boolean.class}, new Object[]{true});
            });
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_PUZZLES, "reportFail",
                    new Class<?>[]{String.class}, new Object[]{"Water Board"}));
            // Hops two threads: onPuzzleFail queues onto the client, the give queues onto the server.
            ctx.waitTicks(20);
            int draftsAfter = draftsHeld(sp);
            if (draftsAfter != draftsBefore + 1) {
                throw new AssertionError("a sim puzzle fail with Auto Get on should have handed over exactly one "
                        + "Architect's First Draft - held " + draftsBefore + " before and " + draftsAfter
                        + " after");
            }

            // The cooldown has to hold, or one fail that reports twice empties the sack equivalent.
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_PUZZLES, "reportFail",
                    new Class<?>[]{String.class}, new Object[]{"Water Board"}));
            ctx.waitTicks(20);
            if (draftsHeld(sp) != draftsAfter) {
                throw new AssertionError("two fails inside the cooldown handed over two drafts");
            }

            // And with the setting OFF it must do nothing at all - the setting is the point.
            ctx.runOnClient(mc -> {
                Object cfg = ModUnderTest.staticCall(ARCHITECT_CONFIG_HOLDER, "getInstance",
                        new Class<?>[]{}, new Object[]{});
                ModUnderTest.call(cfg, "setAutoGet", new Class<?>[]{boolean.class}, new Object[]{false});
                ModUnderTest.call(cfg, "setClickMessage", new Class<?>[]{boolean.class}, new Object[]{false});
            });
            ModUnderTest.staticCall(SIM_ARCHITECT, "reset", new Class<?>[]{}, new Object[]{});
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_PUZZLES, "reportFail",
                    new Class<?>[]{String.class}, new Object[]{"Water Board"}));
            ctx.waitTicks(20);
            if (draftsHeld(sp) != draftsAfter) {
                throw new AssertionError("a puzzle fail handed over a draft with both Architect settings off");
            }

            // 13. USE_ITEM node matching across item variants.
            //     killer560: "if I set up a used item node with a Hyperion it should work with any of the other
            //     wither blade variant [...] whether they are starred or not starred [...] and if they're
            //     recombed or not." Asserted through the real matcher, both ways round, and asserted NEGATIVELY
            //     too - a family table that matched everything would pass every positive check here.
            ctx.runOnClient(mc -> {
                String[][] same = {
                    {"HYPERION", "ASTRAEA"}, {"ASTRAEA", "SCYLLA"}, {"SCYLLA", "VALKYRIE"},
                    {"VALKYRIE", "HYPERION"},
                    // Starred and fragged forms of the same blade, and across blades.
                    // Real starred ids, from Hypixel's own list - the wither blades have no starred form,
                    // but plenty of the other items this mod reaches for do.
                    {"STARRED_MIDAS_SWORD", "MIDAS_SWORD"}, {"STARRED_BAT_WAND", "BAT_WAND"},
                    {"STARRED_LAST_BREATH", "LAST_BREATH"}, {"STARRED_ICE_SPRAY_WAND", "ICE_SPRAY_WAND"},
                    {"SPIRIT_LEAP", "INFINITE_SPIRIT_LEAP"},
                    // Same 8-block Instant Transmission on all three; the range comes from the item's tuners,
                    // not from which one it is. This pair was asserted the other way round until killer560
                    // pointed out that AOTV is not 12 blocks by nature.
                    {"ASPECT_OF_THE_END", "ASPECT_OF_THE_VOID"},
                    {"ASPECT_OF_THE_VOID", "ETHERWARP_CONDUIT"},
                    {"SUPERBOOM_TNT", "INFINITE_SUPERBOOM_TNT"},
                };
                for (String[] pair : same) {
                    Object a = ModUnderTest.staticCall(ITEM_IDENTITY, "family",
                            new Class<?>[]{String.class}, new Object[]{pair[0]});
                    Object c = ModUnderTest.staticCall(ITEM_IDENTITY, "family",
                            new Class<?>[]{String.class}, new Object[]{pair[1]});
                    if (!java.util.Objects.equals(a, c)) {
                        throw new AssertionError(pair[0] + " and " + pair[1] + " should be the same item to a "
                                + "USE_ITEM node, got " + a + " vs " + c);
                    }
                }
                String[][] different = {
                    // The unrefined base blade has no Wither Impact to use.
                    {"NECRON_BLADE", "HYPERION"},
                    {"BAT_WAND", "HYPERION"},
                    {"TERMINATOR", "SPIRIT_LEAP"},
                };
                for (String[] pair : different) {
                    Object a = ModUnderTest.staticCall(ITEM_IDENTITY, "family",
                            new Class<?>[]{String.class}, new Object[]{pair[0]});
                    Object c = ModUnderTest.staticCall(ITEM_IDENTITY, "family",
                            new Class<?>[]{String.class}, new Object[]{pair[1]});
                    if (java.util.Objects.equals(a, c)) {
                        throw new AssertionError(pair[0] + " and " + pair[1] + " must NOT be treated as the same "
                                + "item - both resolved to " + a);
                    }
                }
            });

            // 14. The sim's Spirit Sceptre carries the id Hypixel actually uses, so a route recorded on
            //     Hypixel finds it here. This was BAT_WAND all along and the sim had SPIRIT_SCEPTRE, which no
            //     recorded route would ever have matched.
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_ITEMS, "give",
                    new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, "BAT_WAND"}));
            ctx.waitTicks(20);
            boolean sceptreMatches = sp.getServer().computeOnServer(server -> {
                var player = server.getPlayerList().getPlayers().get(0);
                for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                    var stack = player.getInventory().getItem(i);
                    var data = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
                    if (data != null && "BAT_WAND".equals(data.copyTag().getStringOr("id", ""))) {
                        return true;
                    }
                }
                return false;
            });
            if (!sceptreMatches) {
                throw new AssertionError("the sim's Spirit Sceptre does not carry Hypixel's BAT_WAND id, so a "
                        + "route recorded on Hypixel would not find it in the hotbar");
            }

            // 15. Instant Transmission goes the distance he actually plays with.
            //     killer560: "The tp range depends on how many transmission tuners you add [...] Treat the
            //     default as 12 nearly no one plays with less." The sim had no plain right-click teleport at
            //     all before this. Measured as a REAL DISTANCE MOVED down a clear corridor, not by reading the
            //     constant back - a range that is computed correctly and then never applied would pass that.
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_ITEMS, "give",
                    new Class<?>[]{Minecraft.class, String.class},
                    new Object[]{mc, "ASPECT_OF_THE_VOID"}));
            ctx.waitTicks(20);
            // Stand in open air well clear of the room so nothing stops the dash short, looking level.
            sp.getServer().runOnServer(server -> {
                var player = server.getPlayerList().getPlayers().get(0);
                player.teleportTo(server.overworld(), originX + 0.5, FLOOR_Y + 40, originZ + 0.5,
                        java.util.Set.of(), 90.0f, 0.0f, false);
            });
            ctx.waitTicks(10);
            double[] start = sp.getServer().computeOnServer(server -> {
                var p = server.getPlayerList().getPlayers().get(0);
                return new double[]{p.getX(), p.getY(), p.getZ()};
            });
            ctx.runOnClient(mc -> {
                int slot = ModUnderTest.getInt(mc.player.getInventory(), "getSelectedSlot");
                mc.player.getInventory().setSelectedSlot(slot);
            });
            // Select the sceptre-free slot holding the AOTV and use it.
            ctx.runOnClient(mc -> {
                for (int i = 0; i < 9; i++) {
                    var data = mc.player.getInventory().getItem(i)
                            .get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
                    if (data != null && "ASPECT_OF_THE_VOID".equals(data.copyTag().getStringOr("id", ""))) {
                        mc.player.getInventory().setSelectedSlot(i);
                        return;
                    }
                }
                throw new AssertionError("the sim did not give an Aspect of the Void");
            });
            ctx.waitTicks(5);
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_ABILITIES, "instantTransmission",
                    new Class<?>[]{Minecraft.class, net.minecraft.world.item.ItemStack.class},
                    new Object[]{mc, mc.player.getInventory().getSelectedItem()}));
            ctx.waitTicks(20);
            double[] end = sp.getServer().computeOnServer(server -> {
                var p = server.getPlayerList().getPlayers().get(0);
                return new double[]{p.getX(), p.getY(), p.getZ()};
            });
            double moved = Math.sqrt(Math.pow(end[0] - start[0], 2) + Math.pow(end[2] - start[2], 2));
            System.out.println("[70-sim-flat-room] instant transmission moved " + String.format(Locale.US,
                    "%.2f", moved) + " blocks (expect ~12 with four tuners)");
            if (moved < 11.0 || moved > 13.0) {
                throw new AssertionError("a fully tuned Aspect of the Void should teleport about 12 blocks - "
                        + "8 base plus four tuners - but it moved " + moved);
            }

            // 16. The Room Recorder's "Undersized party!" confirm is matched on the ITEM'S TEXT, not on a slot
            //     number read off a screenshot. Asserted here because a typo in that string fails SILENTLY -
            //     the recorder would queue a floor, never click through, and look like the join was refused.
            ctx.runOnClient(mc -> {
                // The real lore line from Hypixel's menu, and the colour codes it actually carries.
                var confirm = new net.minecraft.world.item.ItemStack(McItems.LIME_DYE);
                confirm.set(net.minecraft.core.component.DataComponents.LORE,
                        new net.minecraft.world.item.component.ItemLore(java.util.List.of(
                                net.minecraft.network.chat.Component.literal("§eClick to play anyway!"))));
                boolean hit = (Boolean) ModUnderTest.staticCall(ROOM_RECORDER, "looksLikePlayAnyway",
                        new Class<?>[]{net.minecraft.world.item.ItemStack.class}, new Object[]{confirm});
                if (!hit) {
                    throw new AssertionError("the confirm item's 'Click to play anyway!' lore was not matched - "
                            + "the recorder would queue a floor and never enter it");
                }
                // And it must NOT match the barrier sitting next to it, or the recorder clicks the wrong slot.
                var no = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BARRIER);
                no.set(net.minecraft.core.component.DataComponents.LORE,
                        new net.minecraft.world.item.component.ItemLore(java.util.List.of(
                                net.minecraft.network.chat.Component.literal(
                                        "§cTHIS INSTANCE IS BEST WITH A 5 PLAYER PARTY!"))));
                if ((Boolean) ModUnderTest.staticCall(ROOM_RECORDER, "looksLikePlayAnyway",
                        new Class<?>[]{net.minecraft.world.item.ItemStack.class}, new Object[]{no})) {
                    throw new AssertionError("the warning item matched too - the recorder would click the wrong "
                            + "slot in the undersized-party menu");
                }
            });

            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "leave",
                    new Class<?>[]{}, new Object[]{}));
            boolean afterLeave = ctx.computeOnClient(mc ->
                    (Boolean) ModUnderTest.staticCall(SIM_STATE, "canAct",
                            new Class<?>[]{Minecraft.class}, new Object[]{mc}));
            if (afterLeave) {
                throw new AssertionError("SimState stayed active after leave() - a stuck flag would let sim "
                        + "abilities fire outside the sim");
            }
            System.out.println("[70-sim-flat-room] PASS - gate off/on/off, room pasted at the right cell, "
                    + "orientation marker in the right corner, stair kept its east facing, wither door went to barriers and then cleared, 1-HP zombie, Fel woke on approach, mage beam killed only the nearest (zero pierce), run countdown held then started, reads as a dungeon but not the boss, mobs stay put, mimic eligibility, score bonus, Architect draft on a sim puzzle fail, wither-blade/leap/boom/teleport item families, 12-block instant transmission, undersized-party confirm match");
        }
    }


    /** How many Architect's First Drafts the SERVER's player is holding - the client's copy of the inventory
     *  lags the give by a tick or two, and a count read there reports zero for a give that worked. */
    private static int draftsHeld(
            net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext sp) {
        return sp.getServer().computeOnServer(server -> {
            var player = server.getPlayerList().getPlayers().get(0);
            int found = 0;
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                var stack = player.getInventory().getItem(i);
                var data = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
                if (data != null && "ARCHITECT_FIRST_DRAFT".equals(data.copyTag().getStringOr("id", ""))) {
                    found += stack.getCount();
                }
            }
            return found;
        });
    }

    /** Zombies near a point that are still ALIVE - a killed mob lingers through its death animation, so
     *  counting entities counts the corpse and reports a working beam as having done nothing. */
    private static int livingZombiesNear(
            net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext sp,
            net.minecraft.core.BlockPos pos) {
        return sp.getServer().computeOnServer(server -> (int) server.overworld()
                .getEntitiesOfClass(net.minecraft.world.entity.monster.zombie.Zombie.class,
                        new net.minecraft.world.phys.AABB(pos).inflate(3.5))
                .stream().filter(z -> z.getHealth() > 0.0f).count());
    }
}
