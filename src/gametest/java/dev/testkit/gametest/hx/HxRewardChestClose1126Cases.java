package dev.testkit.gametest.hx;

import dev.testkit.gametest.ModUnderTest;
import dev.testkit.gametest.SameTick;
import dev.testkit.gametest.mod.Mod;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;

import java.util.Locale;

/**
 * rewardchest-screen-stall-1126: the reward-chest step closes the chest window it opened itself (Auto Close Chest OFF)
 * instead of waiting on it, and still waits on a screen the player opened. The mod's own {@code RewardChest} runs on a
 * chest two blocks in front of him ({@code AutoWater.chestStartAtForTest}, ticked at START where the autos run), which the
 * server really opens. Cheat jar only (a legit jar notes it and passes).
 * <ul>
 *   <li>1126-same-rewardchest-close-use / -swap / -click / -dig: three claims in a row with his right click / scroll 4-5 /
 *       container click on his last slot / left click on the chest in every tick. Each time the window must open, the step
 *       must close it (the close packet is the feature's packet) and end, with nothing left open, and GrimAC (default and
 *       experimental) silent; use, scroll and container click must share a server tick with the close.</li>
 *   <li>1126-rewardchest-player-screen: a screen he opened first keeps the step waiting (no click, no close of his screen);
 *       once he closes it the step claims the chest and closes its own window.</li>
 * </ul>
 */
final class HxRewardChestClose1126Cases {

    private static final String WATER = "autopuzzles.AutoWater";
    private static final String CLOSE_CONFIG = "com.killer560.hub.autoclosechest.AutoCloseChestConfig";
    private static volatile boolean driving;
    private static boolean hooked;

    private HxRewardChestClose1126Cases() {
    }

    static void register(Session s) {
        s.test("1126-same-rewardchest-close-use", c -> same(c, SameTick.Act.USE));
        s.test("1126-same-rewardchest-close-swap", c -> same(c, SameTick.Act.SWAP));
        s.test("1126-same-rewardchest-close-click", c -> same(c, SameTick.Act.CLICK));
        s.test("1126-same-rewardchest-close-dig", c -> same(c, SameTick.Act.DIG));
        s.test("1126-rewardchest-player-screen", c -> same(c, null));
    }

    private static void hook() {
        if (!hooked) {
            hooked = true;
            ClientTickEvents.START_CLIENT_TICK.register(mc -> {
                if (driving && mc.player != null) {
                    Mod.staticCall(WATER, "chestTickForTest");
                }
            });
        }
    }

    private static boolean active(Session c) {
        return c.onClient(mc -> (Boolean) Mod.staticCall(WATER, "chestActiveForTest"));
    }

    private static boolean windowOpen(Session c) {
        return c.onClient(mc -> mc.player.containerMenu != mc.player.inventoryMenu);
    }

    private static void same(Session c, SameTick.Act act) {
        if (!Mod.isCheat()) {
            c.note("legit jar: the auto puzzles are cheat-only");
            return;
        }
        hook();
        c.waitUntil("standing still", mc -> mc.player.onGround()
                && mc.player.getDeltaMovement().horizontalDistanceSqr() < 1e-4, 200);
        double[] at = c.onClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
        int x = (int) Math.floor(at[0]), y = (int) Math.floor(at[1]), z = (int) Math.floor(at[2]) + 2;
        SameTick st = null;
        try {
            c.hx().give(4, "minecraft:stick");
            c.hx().give(5, "minecraft:stick");
            if (act == SameTick.Act.DIG) {
                c.server().command("gamemode survival @p");
            }
            c.onClient(mc -> {
                ModUnderTest.set(ModUnderTest.config(CLOSE_CONFIG), "setEnabled", false); // the stall needs it OFF
                return null;
            });
            setblock(c, x, y, z, "minecraft:chest[facing=north]");
            c.waitUntil("the chest on the client", mc -> mc.level.getBlockState(new BlockPos(x, y, z))
                    .getBlock() instanceof net.minecraft.world.level.block.ChestBlock, 100);
            c.onClient(mc -> {
                mc.player.getInventory().setSelectedSlot(4);
                double dx = x + 0.5 - mc.player.getX(), dy = y + 0.5 - mc.player.getEyeY(), dz = z + 0.5 - mc.player.getZ();
                float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                mc.player.setYRot(mc.player.getYRot() + net.minecraft.util.Mth.wrapDegrees(yaw - mc.player.getYRot()));
                mc.player.setXRot((float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))));
                return null;
            });
            c.ctx().waitTicks(3);
            if (act == null) {
                playerScreen(c, x, y, z);
                return;
            }
            st = c.onClient(mc -> SameTick.start(act, act == SameTick.Act.DIG ? 2 : 1,
                    p -> p instanceof ServerboundContainerClosePacket, // his own act never sends one
                    4, 5));
            for (int trial = 0; trial < 3; trial++) {
                boolean[] seen = {false};
                setblock(c, x, y, z, "minecraft:air");
                c.ctx().waitTicks(2);
                setblock(c, x, y, z, "minecraft:chest[facing=north]"); // a fresh chest each claim (his left clicks may break one)
                c.waitUntil("the chest on the client", mc -> mc.level.getBlockState(new BlockPos(x, y, z))
                        .getBlock() instanceof net.minecraft.world.level.block.ChestBlock, 100);
                c.onClient(mc -> {
                    Mod.staticCall(WATER, "chestStartAtForTest", new BlockPos(x, y, z));
                    driving = true;
                    return null;
                });
                for (int t = 0; t < 80 && active(c); t++) {
                    seen[0] |= windowOpen(c);
                    c.ctx().waitTick();
                }
                c.check(!active(c), "trial " + trial + ": the reward chest step is still waiting after 80 ticks");
                c.check(seen[0], "trial " + trial + ": the chest window never opened (nothing tested)");
                c.ctx().waitTicks(3);
                c.check(!windowOpen(c), "trial " + trial + ": the chest window the step opened is still open");
                driving = false;
                c.ctx().waitTicks(30); // the lid shuts before the next claim
            }
            SameTick.judge(c, st, "reward chest close", act == SameTick.Act.DIG ? SameTick.Expect.ACTED : SameTick.Expect.SHARE);
            st = null;
        } finally {
            driving = false;
            SameTick s2 = st;
            c.onClient(mc -> {
                if (s2 != null) {
                    s2.close();
                }
                if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) {
                    mc.player.closeContainer();
                }
                dev.testkit.compat.McCompat.setScreen(mc, null);
                return null;
            });
            setblock(c, x, y, z, "minecraft:air");
            if (act == SameTick.Act.DIG) {
                c.server().command("gamemode creative @p");
            }
            for (int s : new int[]{4, 5}) {
                c.hx().give(s, "minecraft:air");
            }
        }
    }

    /** A screen the player opened keeps the step waiting; closing it lets the step claim the chest and close its window. */
    private static void playerScreen(Session c, int x, int y, int z) {
        long since = c.hx().head();
        c.onClient(mc -> {
            dev.testkit.compat.McCompat.setScreen(mc, new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player));
            Mod.staticCall(WATER, "chestStartAtForTest", new BlockPos(x, y, z));
            driving = true;
            return null;
        });
        c.ctx().waitTicks(40);
        int clicks = c.onClient(mc -> (Integer) Mod.staticCall(WATER, "chestClicksForTest"));
        boolean still = c.onClient(mc -> dev.testkit.compat.McCompat.screen(mc) != null); // creative swaps the inventory screen class
        c.check(clicks == 0 && c.hx().events(since, "use_block").isEmpty(), "the step clicked the chest (" + clicks
                + ") with a screen of his open");
        c.check(still && active(c), "his screen was closed or the step gave up while it was open");
        c.onClient(mc -> {
            dev.testkit.compat.McCompat.setScreen(mc, null);
            return null;
        });
        boolean seen = false;
        for (int t = 0; t < 80 && active(c); t++) {
            seen |= windowOpen(c);
            c.ctx().waitTick();
        }
        c.check(!active(c), "the step never finished after his screen closed");
        c.check(seen && c.hx().events(since, "use_block").size() >= 1, "the chest was never claimed after his screen closed");
        c.ctx().waitTicks(3);
        c.check(!windowOpen(c), "the chest window the step opened is still open");
    }

    private static void setblock(Session c, int x, int y, int z, String block) {
        c.server().command(String.format(Locale.ROOT, "setblock %d %d %d %s", x, y, z, block));
    }
}
