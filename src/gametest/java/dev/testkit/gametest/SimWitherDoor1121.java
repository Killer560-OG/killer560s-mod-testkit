package dev.testkit.gametest;

import dev.testkit.harness.Report;
import dev.testkit.harness.SuiteVerdict;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Shared steps of 1121-sim-witherdoor-anyitem and 1121-sim-witherdoor-nobreak (mod sim-witherdoor-1121, killer560
 * 2026-11-21): a sim in a plain singleplayer world, one fresh wither or blood door per probe (carved by the mod's own
 * {@code SimDoors.carveDoorway}), the team's keys set through SimKeys, and the clicks sent through the real client
 * {@code MultiPlayerGameMode}.
 */
final class SimWitherDoor1121 {
    static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    static final String SIM_DOORS = "com.killer560.hub.roomsim.SimDoors";
    static final String SIM_KEYS = "com.killer560.hub.roomsim.SimKeys";
    static final String SIM_ITEMS = "com.killer560.hub.roomsim.SimItems";
    static final int WITHER = 2;
    static final int BLOOD = 3;
    private static final int FLOOR_Y = 69;
    private static int slot;

    /** One door: its blocks, the click target and where to stand. */
    record Door(List<BlockPos> blocks, BlockPos target, BlockPos stand) {
    }

    private SimWitherDoor1121() {
    }

    static void enter(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        ctx.waitTicks(40);
        // An earlier case in the same JVM may have left a generated floor's altitude offset: doors carve at toWorld(69).
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimAltitude", "reset"));
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter", new Class<?>[]{String.class}, new Object[]{"gametest"}));
        sp.getServer().runOnServer(server -> server.getPlayerList().getPlayers().forEach(p -> p.setGameMode(GameType.SURVIVAL)));
        ctx.waitTicks(10);
    }

    /** A fresh door of {@code type} on its own platform, the player standing 3 blocks west of it. */
    static Door fresh(ClientGameTestContext ctx, TestSingleplayerContext sp, int type) {
        int cx = 300 + 40 * (slot++ % 20);
        int cz = 300 + 40 * (slot / 20);
        BlockPos centre = new BlockPos(cx, FLOOR_Y, cz);
        BlockPos stand = new BlockPos(cx - 4, FLOOR_Y, cz);
        sp.getServer().runOnServer(server -> {
            var level = server.overworld();
            for (int x = -9; x <= 9; x++) {
                for (int z = -9; z <= 9; z++) {
                    level.setBlockAndUpdate(new BlockPos(cx + x, FLOOR_Y - 1, cz + z), Blocks.STONE.defaultBlockState());
                    for (int y = 0; y < 6; y++) {
                        level.setBlockAndUpdate(new BlockPos(cx + x, FLOOR_Y + y, cz + z), Blocks.AIR.defaultBlockState());
                    }
                }
            }
            var p = server.getPlayerList().getPlayers().get(0);
            p.teleportTo(stand.getX() + 0.5, FLOOR_Y, stand.getZ() + 0.5);
        });
        ctx.waitTicks(30);
        sp.getServer().runOnServer(server -> ModUnderTest.staticCall(SIM_DOORS, "carveDoorway",
                new Class<?>[]{net.minecraft.server.level.ServerLevel.class, BlockPos.class, boolean.class, int.class},
                new Object[]{server.overworld(), centre, true, type}));
        ctx.waitTicks(20);
        List<BlockPos> blocks = new ArrayList<>();
        for (int x = -6; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                for (int y = 0; y < 5; y++) {
                    BlockPos p = new BlockPos(cx + x, FLOOR_Y + y, cz + z);
                    if ((Boolean) ModUnderTest.staticCall(SIM_DOORS, "isDoorBlock", new Class<?>[]{BlockPos.class}, new Object[]{p})) {
                        blocks.add(p);
                    }
                }
            }
        }
        BlockPos target = blocks.stream().filter(b -> b.getY() == FLOOR_Y + 1 && b.getZ() == cz).min(java.util.Comparator.comparingInt(BlockPos::getX))
                .orElse(blocks.isEmpty() ? centre : blocks.get(0));
        return new Door(blocks, target, stand);
    }

    static void setKeys(int wither, boolean blood) {
        try {
            Class<?> c = Class.forName(SIM_KEYS);
            Field w = c.getDeclaredField("witherKeys");
            w.setAccessible(true);
            w.setInt(null, wither);
            Field b = c.getDeclaredField("bloodKey");
            b.setAccessible(true);
            b.setBoolean(null, blood);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not set the sim keys", e);
        }
    }

    static int witherKeys() {
        try {
            Field w = Class.forName(SIM_KEYS).getDeclaredField("witherKeys");
            w.setAccessible(true);
            return w.getInt(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    static boolean bloodKey() {
        try {
            Field b = Class.forName(SIM_KEYS).getDeclaredField("bloodKey");
            b.setAccessible(true);
            return b.getBoolean(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** Puts {@code id} (null = empty hand) in slot 0, selected, and waits until the client sees it. */
    static void hold(ClientGameTestContext ctx, TestSingleplayerContext sp, String id) {
        ItemStack stack = id == null ? ItemStack.EMPTY
                : (ItemStack) ModUnderTest.staticCall(SIM_ITEMS, "build", new Class<?>[]{String.class}, new Object[]{id});
        if (id != null && (stack == null || stack.isEmpty())) {
            throw new AssertionError("SimItems.build gave nothing for " + id);
        }
        UUID me = ctx.computeOnClient(mc -> mc.player.getUUID());
        sp.getServer().runOnServer(server -> {
            var p = server.getPlayerList().getPlayer(me);
            p.getInventory().setItem(0, stack.copy());
        });
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
        ctx.waitTicks(8);
    }

    static BlockHitResult hit(BlockPos pos) {
        return new BlockHitResult(new Vec3(pos.getX() - 0.01, pos.getY() + 0.5, pos.getZ() + 0.5), Direction.WEST, pos, false);
    }

    static void rightClick(ClientGameTestContext ctx, BlockPos pos) {
        ctx.runOnClient(mc -> mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit(pos)));
    }

    static void leftClick(ClientGameTestContext ctx, BlockPos pos) {
        ctx.runOnClient(mc -> mc.gameMode.startDestroyBlock(pos, Direction.WEST));
    }

    static int openDelay(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(SIM_DOORS, "openDelayTicks"));
    }

    /** Door blocks still solid, counted on the server and on the client. */
    static int[] solid(ClientGameTestContext ctx, TestSingleplayerContext sp, Door d) {
        int server = sp.getServer().computeOnServer(s -> {
            int n = 0;
            for (BlockPos p : d.blocks()) {
                if (!s.overworld().getBlockState(p).isAir()) n++;
            }
            return n;
        });
        int client = ctx.computeOnClient(mc -> {
            int n = 0;
            for (BlockPos p : d.blocks()) {
                if (!mc.level.getBlockState(p).isAir()) n++;
            }
            return n;
        });
        return new int[]{server, client};
    }

    static void row(List<String> failures, String name, boolean pass, String detail) {
        System.out.println("[" + name + "] " + (pass ? "PASS" : "FAIL") + " - " + detail);
        Report.caseStarted(name);
        Report.caseFinished(name, pass ? "PASS" : "FAIL", "n/a (singleplayer)", detail, List.of(), List.of());
        SuiteVerdict.finished(name);
        if (!pass) failures.add(name + ": " + detail);
    }

    static void leave() {
        try {
            setKeys(0, false);
        } catch (Throwable ignored) {
            // cleanup never replaces a verdict
        }
    }
}
