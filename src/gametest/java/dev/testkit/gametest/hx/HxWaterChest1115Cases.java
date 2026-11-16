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
 *       (his use and scroll in the same server tick as a click, his dig apart from it: ActionGate holds the click there; his teleport accept on either side of it) and GrimAC stay silent (default and experimental checks).</li>
 *   <li>1115-same-waterchest-locked: signal forced FALSE for 140 ticks with his right click every tick: 1 to 5 probes in
 *       all (the old rate sent 35), each taken by the server, GrimAC silent.</li>
 * </ul>
 */
final class HxWaterChest1115Cases {

    private static final String WATER = "autopuzzles.AutoWater";
    private static volatile boolean driving;
    private static boolean hooked;
    /** TP case: wall-clock stamps {kind, ms} of his tp commands (0), the chest clicks (1) and his teleport accepts (2). */
    private static final java.util.List<long[]> STAMPS = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

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
            STAMPS.clear();
            st = c.onClient(mc -> {
                forceUnlock(unlocked);
                Mod.staticCall(WATER, "chestStartAtForTest", new BlockPos(x, y, z));
                driving = true;
                return SameTick.start(act, act == SameTick.Act.USE || act == SameTick.Act.SWAP ? 1 : 2,
                        p -> p instanceof ServerboundUseItemOnPacket, // his own act never sends one (use_item, swap, dig, accept)
                        4, 5);
            });
            if (act == SameTick.Act.TP) {
                c.onClient(mc -> {
                    java.util.function.Consumer<net.minecraft.network.protocol.Packet<?>> prev = dev.testkit.harness.PacketTrace.tap;
                    dev.testkit.harness.PacketTrace.tap = p -> {
                        if (prev != null) prev.accept(p);
                        int kind = p instanceof ServerboundUseItemOnPacket ? 1
                                : p instanceof net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket ? 2 : 0;
                        if (kind != 0) STAMPS.add(new long[]{kind, System.currentTimeMillis()});
                    };
                    return null;
                });
            }
            int ticks = unlocked ? 60 : 140;
            for (int t = 0; t < ticks; t += 5) {
                if (act == SameTick.Act.TP) {
                    STAMPS.add(new long[]{0, System.currentTimeMillis()});
                    c.server().command(tp);
                }
                c.ctx().waitTicks(5);
            }
            long halfSince = c.hx().head();
            if (act == SameTick.Act.TP) {
                // Second half: his tp goes the moment after a chest click has left, so the next click (held back through the
                // settle after his accept) goes out with no teleport pending (see the check below).
                STAMPS.add(new long[]{3, System.currentTimeMillis()});
                for (int cycle = 0; cycle < 6; cycle++) {
                    int seen;
                    synchronized (STAMPS) {
                        seen = (int) STAMPS.stream().filter(a -> a[0] == 1).count();
                    }
                    for (int w = 0; w < 30; w++) {
                        c.ctx().waitTicks(1);
                        synchronized (STAMPS) {
                            if (STAMPS.stream().filter(a -> a[0] == 1).count() > seen) {
                                break;
                            }
                        }
                    }
                    STAMPS.add(new long[]{0, System.currentTimeMillis()});
                    c.server().command(tp);
                }
                c.ctx().waitTicks(8);
                // No new click from here; the last one is still on its way to the server, so give it its tick first.
                driving = false;
                c.ctx().waitTicks(3);
            }
            int clicks = c.onClient(mc -> (Integer) Mod.staticCall(WATER, "chestClicksForTest"));
            int served = c.hx().events(since, "use_block").size();
            c.note("chest clicks " + clicks + ", server use_block " + served + " (unlock signal " + unlocked + ")");
            if (act == SameTick.Act.TP) {
                // A click sent after the server's teleport and before his client has answered it reaches a server that is
                // waiting for his position and is discarded; nothing on the client can know a teleport is in flight (no
                // packet for it has arrived yet), so those clicks are not asked to be served. Every click sent with no
                // teleport pending is: it must be served, and enough of them must exist for that to mean something.
                java.util.List<long[]> ev;
                synchronized (STAMPS) {
                    ev = new java.util.ArrayList<>(STAMPS);
                }
                ev.sort(java.util.Comparator.<long[]>comparingLong(a -> a[1]).thenComparingLong(a -> a[0]));
                boolean pending = false, second = false;
                int exposed = 0, clear = 0, exposed2 = 0, clear2 = 0;
                for (long[] e : ev) {
                    if (e[0] == 3) {
                        second = true;
                    } else if (e[0] == 0) {
                        pending = true;
                    } else if (e[0] == 2) {
                        pending = false;
                    } else if (pending) {
                        exposed++;
                        exposed2 += second ? 1 : 0;
                    } else {
                        clear++;
                        clear2 += second ? 1 : 0;
                    }
                }
                c.note("chest clicks " + clicks + ": " + clear + " with no teleport pending, " + exposed
                        + " sent between the server's teleport and his accept; server use_block " + served);
                int served2 = c.hx().events(halfSince, "use_block").size();
                c.note("second half (tp right behind a click): " + clear2 + " clear + " + exposed2 + " exposed clicks, server took " + served2);
                StringBuilder tl = new StringBuilder();
                for (long[] e : ev) {
                    tl.append(" ").append("TCAM".charAt((int) e[0])).append(e[1] % 100000);
                }
                c.check(clear2 >= 3 && served2 >= clear2, "second half: server took " + served2 + " use_block, "
                        + clear2 + " clicks went out with no teleport pending; tp(T)/click(C)/accept(A)/half(M) ms:" + tl);
                c.check(clear >= 5, "only " + clear + " click(s) went out with no teleport pending: nothing to judge");
                c.check(served >= clear, "the server took " + served + " use_block of " + clicks + " clicks, "
                        + clear + " of them sent with no teleport pending");
            } else if (unlocked) {
                c.check(clicks >= 8 && served >= 8, "the chest step clicked " + clicks + " time(s), the server saw " + served);
            } else {
                c.check(clicks >= 1 && clicks <= 5, clicks + " click(s) on the locked chest in " + ticks
                        + " ticks (want 1-5; the old 4-tick rate sent " + ticks / 4 + ")");
                c.check(served >= clicks, "the server saw " + served + " of " + clicks + " probe(s)");
            }
            // His use and scroll share the chest click's server tick (his use_item after the click, his slot inside the
            // click's useItemOn ahead of it). ActionGate holds the click in a tick his left click is queued (a use after a
            // dig in one tick is PacketOrderI), so a dig is apart by design. A teleport is not: ActionGate's settle starts only when a
            // teleport moves him more than 4 blocks and this one lands on the spot he stands on, so nothing holds the click;
            // it met his accept in one server tick whenever a server stall delayed the accept (26.2, pong pong pong accept
            // use_item_on). Both must reach the wire, and GrimAC (default and experimental) judges the tick they share.
            SameTick.judge(c, st, "water board chest", act == SameTick.Act.DIG ? SameTick.Expect.APART
                    : act == SameTick.Act.TP ? SameTick.Expect.ACTED : SameTick.Expect.SHARE);
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
