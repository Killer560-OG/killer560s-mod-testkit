package dev.testkit.gametest;

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

            int centre = GRID / 2;
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
                    + "orientation marker in the right corner, stair kept its east facing, wither door went to barriers and then cleared");
        }
    }

}
