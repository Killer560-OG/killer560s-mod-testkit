package dev.testkit.gametest.hx;

import dev.testkit.gametest.SameTick;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * tickledger-1127: Secret Aura after a teleport. His 11-27 Autopilot session spent 379 ticks with a route's await standing on
 * its landing while ActionGate's 6-tick teleport settle refused the aura's chest / lever / essence click (game log 4652:
 * chosen 2025, refused 2025-2027, clicked 2029 after a landing at 2023). The aura re-picks its secret and face from each
 * tick's eye, so it now waives that settle (still not the quiet tick after a server position).
 * <ul>
 *   <li>1127-tl-secaura-tpjump: four 10-block server teleports, each onto a spot facing away from a chest 3 blocks off; the
 *       aura must click every chest, the median from the client standing there to the use_item_on at most 4 ticks (origin:
 *       7-8, the settle plus its body turn).</li>
 *   <li>1127-tl-secaura-tpjump-use / -swap: the same jumps with his right click / hotbar swap in every tick (SAME-TICK
 *       GRIM CASES); at least three chests clicked, his action went in, and no GrimAC flag.</li>
 * </ul>
 * A legit jar has no Secret Aura: every case passes there only when nothing was clicked.
 */
final class HxTickLedger1127Cases {

    private static final String CU = "cheatutils.CheatUtilsConfig";
    private static final String PLATFORM = "fill -432 150 -425 -394 150 -395 ";
    private static final int Z = -401, N = 4;

    private HxTickLedger1127Cases() {
    }

    static void register(Session s) {
        s.test("1127-tl-secaura-tpjump", c -> jumps(c, SameTick.Act.TP));
        s.test("1127-tl-secaura-tpjump-use", c -> jumps(c, SameTick.Act.USE));
        s.test("1127-tl-secaura-tpjump-swap", c -> jumps(c, SameTick.Act.SWAP));
    }

    private static void jumps(Session c, SameTick.Act act) {
        int[] xs = new int[N];
        java.util.Set<Long> chestSet = new java.util.HashSet<>();
        for (int i = 0; i < N; i++) {
            xs[i] = -430 + 10 * i;
            chestSet.add(new BlockPos(xs[i], 151, Z + 3).asLong());
        }
        SameTick st = null;
        try (HxKit.Settings d = HxTriggerDelayCases.dungeon(c);
             HxKit.Settings on = new HxKit.Settings(c).with(CU, "SecretAuraEnabled", true).with(CU, "AuraCooldownMs", 0)
                     .with(CU, "AuraRange", 4.5).with(CU, "AuraChests", true)) {
            c.server().command(PLATFORM + "minecraft:stone replace minecraft:air");
            for (int x : xs) {
                c.server().command("setblock " + x + " 151 " + (Z + 3) + " chest");
            }
            c.server().command("item replace entity @a hotbar.4 with stick");
            c.server().command("item replace entity @a hotbar.5 with stick");
            c.hx().call("dungeon.tp", "x", -419.5, "y", 151.0, "z", -419.5, "yaw", 180f, "pitch", 0f);
            c.waitUntil("the client to have the last chest", mc -> mc.level.getBlockState(new BlockPos(xs[N - 1], 151, Z + 3))
                    .is(net.minecraft.world.level.block.Blocks.CHEST), 100);
            c.onClient(mc -> {
                mc.player.getInventory().setSelectedSlot(4);
                return null;
            });
            c.ctx().waitTicks(10);
            st = c.onClient(mc -> SameTick.start(act, 1, p -> p instanceof ServerboundUseItemOnPacket u
                    && chestSet.contains(dev.testkit.compat.McPackets.useItemOnHitResult(u).getBlockPos().asLong()), 4, 5));
            SameTick cur0 = st;
            List<Integer> delays = new ArrayList<>();
            int clicked = 0;
            for (int i = 0; i < N; i++) {
                double sx = xs[i] + 0.5, sz = Z + 0.5;
                int before = c.onClient(mc -> cur0.featureWindows());
                c.server().command(String.format(Locale.ROOT, "tp @p %.1f 151 %.1f 180 0", sx, sz));
                int landedAt = -1, delay = -1;
                for (int t = 1; t <= 40; t++) {
                    c.ctx().waitTicks(1);
                    if (landedAt < 0 && c.onClient(mc -> Math.abs(mc.player.getX() - sx) < 0.05 && Math.abs(mc.player.getZ() - sz) < 0.05)) {
                        landedAt = t;
                    }
                    if (c.onClient(mc -> cur0.featureWindows()) > before) {
                        delay = landedAt < 0 ? -1 : t - landedAt;
                        break;
                    }
                }
                if (delay >= 0) {
                    clicked++;
                    delays.add(delay);
                }
                c.ctx().waitTicks(3);
                HxTriggerDelayCases.closeScreen(c);
                c.ctx().waitTicks(8);
            }
            SameTick cur = st;
            st = null;
            c.onClient(mc -> {
                cur.close();
                return null;
            });
            List<Integer> sorted = new ArrayList<>(delays);
            java.util.Collections.sort(sorted);
            int median = sorted.isEmpty() ? -1 : sorted.get(sorted.size() / 2);
            c.note("secret aura after 10-block teleports (" + act + "): " + cur.report() + ", chests clicked " + clicked + " of " + N
                    + ", ticks from standing there to the click " + delays + ", median " + median);
            if (!Mod.isCheat()) {
                c.check(clicked == 0 && cur.featureWindows() == 0, "a legit jar clicked a chest by itself");
                return;
            }
            if (act == SameTick.Act.TP) {
                c.check(clicked == N, "secret aura clicked only " + clicked + " of " + N + " chests after the teleports (the feature did not act)");
                c.check(median >= 0 && median <= 4, "secret aura waited a median " + median + " tick(s) after each teleport landing "
                        + delays + " (ActionGate's teleport settle held it; at most 4)");
            } else {
                c.check(clicked >= 3, "secret aura clicked only " + clicked + " of " + N + " chests (the feature did not act)");
                c.check(cur.done() > 0, "his " + act + " never went in (nothing tested)");
            }
        } finally {
            if (st != null) {
                SameTick s2 = st;
                c.onClient(mc -> {
                    s2.close();
                    return null;
                });
            }
            HxTriggerDelayCases.closeScreen(c);
            for (int x : xs) {
                c.server().command("setblock " + x + " 151 " + (Z + 3) + " air");
            }
            c.server().command(PLATFORM + "minecraft:air replace minecraft:stone");
            c.server().command("clear @a stick");
            c.hx().call("dungeon.tp", "x", -419.5, "y", 151.0, "z", -419.5, "yaw", 0f, "pitch", 0f);
        }
    }
}
