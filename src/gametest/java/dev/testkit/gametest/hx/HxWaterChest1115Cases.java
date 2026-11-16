package dev.testkit.gametest.hx;

import dev.testkit.gametest.SameTick;
import dev.testkit.gametest.mod.Mod;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;

import java.util.Locale;

/**
 * SAME-TICK GRIM CASES for the Water Board reward chest click (mod acslow3-1115, which changed its timing: a few slow
 * probes while the chest is locked, then one click the moment the puzzle reads solved and every 4 ticks until it opens).
 * The mod's own {@code RewardChest} runs on a chest two blocks in front of him with no dungeon room around it
 * ({@code AutoWater.chestStartAtForTest}, ticked at START where the autos run), a stone on top so the vanilla server never
 * opens it and the clicks keep coming. Cheat jar only.
 * <ul>
 *   <li>1115-same-waterchest-use / -swap / -dig / -tp: unlock signal forced TRUE (the 4-tick clicks), his right click
 *       every tick / scroll 4-5 every tick / left click on the chest every other tick (survival) / a server teleport
 *       every 5 ticks. The server must take at least 8 use_block, both his action and the chest click must reach the wire
 *       (his use and scroll in the same server tick as a click, his dig and teleport apart from it: ActionGate holds the click there) and GrimAC stay silent (default and experimental checks).</li>
 *   <li>1115-same-waterchest-locked: signal forced FALSE for 140 ticks with his right click every tick: 1 to 5 probes in
 *       all (the old rate sent 35), each taken by the server, GrimAC silent.</li>
 * </ul>
 */
final class HxWaterChest1115Cases {

    private static final String WATER = "autopuzzles.AutoWater";
    private static volatile boolean driving;
    private static boolean hooked;

    private HxWaterChest1115Cases() {
    }

    static void register(Session s) {
        s.test("1115-same-waterchest-use", c -> same(c, SameTick.Act.USE, true));
        s.test("1115-same-waterchest-swap", c -> same(c, SameTick.Act.SWAP, true));
        s.test("1115-same-waterchest-dig", c -> same(c, SameTick.Act.DIG, true));
        s.test("1115-same-waterchest-tp", c -> same(c, SameTick.Act.TP, true));
        s.test("1115-same-waterchest-locked", c -> same(c, SameTick.Act.USE, false));
    }

    private static void forceUnlock(Boolean v) {
        dev.testkit.gametest.ModUnderTest.staticCall(Mod.ROOT + WATER, "setUnlockForTest",
                new Class<?>[]{Boolean.class}, new Object[]{v});
    }

    private static void same(Session c, SameTick.Act act, boolean unlocked) {
        if (!Mod.isCheat()) {
            c.note("legit jar: the auto puzzles are cheat-only");
            return;
        }
        if (!hooked) {
            hooked = true;
            ClientTickEvents.START_CLIENT_TICK.register(mc -> {
                if (driving && mc.player != null) {
                    Mod.staticCall(WATER, "chestTickForTest");
                }
            });
        }
        c.waitUntil("standing still", mc -> mc.player.onGround()
                && mc.player.getDeltaMovement().horizontalDistanceSqr() < 1e-4, 200);
        double[] at = c.onClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
        int x = (int) Math.floor(at[0]), y = (int) Math.floor(at[1]), z = (int) Math.floor(at[2]) + 2;
        String tp = String.format(Locale.ROOT, "tp @p %.3f %.3f %.3f ~ ~", at[0], at[1], at[2]);
        SameTick st = null;
        try {
            c.hx().give(4, "minecraft:stick");
            c.hx().give(5, "minecraft:stick");
            if (act == SameTick.Act.DIG) {
                c.server().command("gamemode survival @p"); // creative breaks the chest on his first left click
            }
            setblock(c, x, y + 1, z, "minecraft:stone");
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
            long since = c.hx().head();
            st = c.onClient(mc -> {
                forceUnlock(unlocked);
                Mod.staticCall(WATER, "chestStartAtForTest", new BlockPos(x, y, z));
                driving = true;
                return SameTick.start(act, act == SameTick.Act.USE || act == SameTick.Act.SWAP ? 1 : 2,
                        p -> p instanceof ServerboundUseItemOnPacket, // his own act never sends one (use_item, swap, dig, accept)
                        4, 5);
            });
            int ticks = unlocked ? 60 : 140;
            for (int t = 0; t < ticks; t += 5) {
                if (act == SameTick.Act.TP) {
                    c.server().command(tp);
                }
                c.ctx().waitTicks(5);
            }
            int clicks = c.onClient(mc -> (Integer) Mod.staticCall(WATER, "chestClicksForTest"));
            int served = c.hx().events(since, "use_block").size();
            c.note("chest clicks " + clicks + ", server use_block " + served + " (unlock signal " + unlocked + ")");
            if (unlocked) {
                c.check(clicks >= 8 && served >= 8, "the chest step clicked " + clicks + " time(s), the server saw " + served);
            } else {
                c.check(clicks >= 1 && clicks <= 5, clicks + " click(s) on the locked chest in " + ticks
                        + " ticks (want 1-5; the old 4-tick rate sent " + ticks / 4 + ")");
                c.check(served >= clicks, "the server saw " + served + " of " + clicks + " probe(s)");
            }
            // His use and scroll share the chest click's server tick (his use_item after the click, his slot inside the
            // click's useItemOn ahead of it). ActionGate holds the click in a tick his left click is queued (a use after a
            // dig in one tick is PacketOrderI) and through the 6-tick settle after a teleport: those two are apart by design.
            SameTick.judge(c, st, "water board chest", act == SameTick.Act.DIG || act == SameTick.Act.TP
                    ? SameTick.Expect.APART : SameTick.Expect.SHARE);
            if (act == SameTick.Act.SWAP) {
                c.check(st.playerAfterFeature() == 0, "his slot went out after the chest click in a server tick: " + st.report());
            }
            st = null;
        } finally {
            driving = false;
            SameTick s2 = st;
            c.onClient(mc -> {
                if (s2 != null) {
                    s2.close();
                }
                forceUnlock(null);
                if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) {
                    mc.player.closeContainer();
                }
                return null;
            });
            setblock(c, x, y + 1, z, "minecraft:air");
            setblock(c, x, y, z, "minecraft:air");
            if (act == SameTick.Act.DIG) {
                c.server().command("gamemode creative @p");
            }
            for (int s : new int[]{4, 5}) {
                c.hx().give(s, "minecraft:air");
            }
        }
    }

    private static void setblock(Session c, int x, int y, int z, String block) {
        c.server().command(String.format(Locale.ROOT, "setblock %d %d %d %s", x, y, z, block));
    }
}
