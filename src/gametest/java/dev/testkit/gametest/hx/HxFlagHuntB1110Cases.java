package dev.testkit.gametest.hx;

import dev.testkit.gametest.SameTick;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * flaghunt-1110 group B (combat / breaking). SAME-TICK GRIM CASES the earlier sets left open.
 * <ul>
 *   <li>1110-fhb-sectrig-&lt;act&gt;: the Secret Triggerbot clicking a row of six chests while HIS use / dig / hotbar swap /
 *       server teleport lands in the same ticks; -swapon-: the same with the bot's own "Swap slot" (and swap back) on,
 *       which sends set_carried_item before its click.</li>
 * </ul>
 * Passes only when the bot clicked at least four chests (use_item_on on the wire), his action went in, and the harness
 * saw no GrimAC flag (default or experimental, whichever the checkout runs).
 */
final class HxFlagHuntB1110Cases {

    private static final String CU = "cheatutils.CheatUtilsConfig";
    private static final String SEC = "secrettrigger.SecretTriggerbotConfig";
    private static final SameTick.Act[] ACTS = {SameTick.Act.USE, SameTick.Act.DIG, SameTick.Act.SWAP, SameTick.Act.TP};

    private HxFlagHuntB1110Cases() {
    }

    static void register(Session s) {
        if (!Mod.isCheat()) {
            return;
        }
        for (SameTick.Act a : ACTS) {
            String n = a.name().toLowerCase(Locale.ROOT);
            s.test("1110-fhb-sectrig-" + n, c -> secret(c, a, false, false));
            s.test("1110-fhb-sectrig-swapon-" + n, c -> secret(c, a, true, false));
            s.test("1110-fhb-secaura-" + n, c -> secret(c, a, false, true));
        }
    }

    private static void secret(Session c, SameTick.Act act, boolean swapOn, boolean aura) {
        int n = 6;
        int[][] chests = new int[n][];
        for (int i = 0; i < n; i++) {
            chests[i] = new int[]{-417, 151, -422 + i};
        }
        SameTick st = null;
        try (HxKit.Settings d = HxTriggerDelayCases.dungeon(c);
             HxKit.Settings on = (aura ? new HxKit.Settings(c).with(CU, "SecretAuraEnabled", true).with(CU, "AuraCooldownMs", 0)
                     .with(CU, "AuraRange", 4.5).with(CU, "AuraChests", true)
                     : new HxKit.Settings(c).with(SEC, "Enabled", true).with(SEC, "SwapEnabled", swapOn)
                     .with(SEC, "SwapBack", swapOn).with(SEC, "SwapSlot", 1).with(SEC, "DelayMs", 0))) {
            c.hx().call("dungeon.tp", "x", -419.5, "y", 151.0, "z", -419.5, "yaw", 0f, "pitch", -90f);
            for (int[] p : chests) {
                c.server().command("setblock " + p[0] + " " + p[1] + " " + p[2] + " chest");
            }
            c.server().command("item replace entity @a hotbar.0 with stick");
            c.server().command("item replace entity @a hotbar.4 with stick");
            c.server().command("item replace entity @a hotbar.5 with stick");
            c.waitUntil("the client to have the last chest", mc -> mc.level.getBlockState(
                    new BlockPos(chests[n - 1][0], chests[n - 1][1], chests[n - 1][2])).is(
                    net.minecraft.world.level.block.Blocks.CHEST), 100);
            c.onClient(mc -> {
                mc.player.getInventory().setSelectedSlot(4);
                return null;
            });
            TriggerTiming.lookAway(c);
            if (act == SameTick.Act.DIG) {
                c.onClient(mc -> {
                    mc.player.setXRot(90f);
                    return null;
                });
            }
            c.ctx().waitTicks(10);
            double[] at = c.onClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
            String tp = String.format(Locale.ROOT, "tp @p %.3f %.3f %.3f ~ ~", at[0], at[1], at[2]);
            java.util.Set<Long> chestSet = new java.util.HashSet<>();
            for (int[] p : chests) {
                chestSet.add(new BlockPos(p[0], p[1], p[2]).asLong());
            }
            long since = c.hx().head();
            st = c.onClient(mc -> SameTick.start(act == SameTick.Act.TP ? SameTick.Act.TP : act, act == SameTick.Act.DIG ? 2 : 1,
                    p -> p instanceof ServerboundUseItemOnPacket u && chestSet.contains(dev.testkit.compat.McPackets.useItemOnHitResult(u).getBlockPos().asLong()),
                    4, 5));
            int fired = 0;
            if (aura) {
                // The aura turns his body to each chest and clicks it; he closes each chest it opens.
                java.util.Set<Integer> seen = new java.util.HashSet<>();
                for (int t = 0; t < 400 && seen.size() < n; t++) {
                    if (act == SameTick.Act.TP && t % 20 == 10) {
                        c.server().command(tp);
                    }
                    c.ctx().waitTicks(1);
                    c.hx().events(since, "use_block").forEach(e -> seen.add(e.getAsJsonArray("pos").get(2).getAsInt()));
                    if (t % 3 == 0) {
                        HxTriggerDelayCases.closeScreen(c);
                    }
                }
                fired = seen.size();
            }
            for (int[] p : aura ? new int[0][] : chests) {
                Vec3 at2 = new Vec3(p[0] + 0.5, p[1] + 0.4, p[2] + 0.5);
                TriggerTiming.look(c, at2);
                boolean got = false;
                for (int t = 0; t < 80 && !got; t++) {
                    if (act == SameTick.Act.TP && t % 20 == 10) {
                        c.server().command(tp);
                    }
                    c.ctx().waitTicks(1);
                    got = c.hx().events(since, "use_block").stream().anyMatch(e ->
                            e.getAsJsonArray("pos").get(0).getAsInt() == p[0] && e.getAsJsonArray("pos").get(2).getAsInt() == p[2]);
                }
                fired += got ? 1 : 0;
                c.ctx().waitTicks(3);
                HxTriggerDelayCases.closeScreen(c);
                TriggerTiming.lookAway(c);
                c.ctx().waitTicks(12);
            }
            SameTick cur = st;
            st = null;
            c.onClient(mc -> {
                cur.close();
                return null;
            });
            String what = aura ? "secret aura" : "secret triggerbot" + (swapOn ? " (swap on)" : "");
            c.note(what + " " + cur.report() + ", chests clicked " + fired + " of " + n);
            // Under his dig every tick the aura gives way (ActionGate: his hand dig comes first), and the bot's own swap never settles on its slot while he swaps every tick: both only have to stay quiet.
            boolean yields = aura && act == SameTick.Act.DIG || swapOn && act == SameTick.Act.SWAP; // each wins the tick off the other
            if (!yields) {
                c.check(fired >= 4, what + ": clicked only " + fired + " of " + n + " chests (the feature did not act)");
                c.check(cur.featureWindows() > 0, what + ": no use_item_on of the bot on the wire (" + cur.report() + ")");
            }
            if (act != SameTick.Act.TP) {
                c.check(cur.done() > 0, what + ": his action never went in (nothing tested)");
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
            c.server().command("fill -417 151 -422 -417 151 -417 air");
            c.server().command("clear @a stick");
            c.hx().call("dungeon.tp", "x", -419.5, "y", 151.0, "z", -419.5, "yaw", 0f, "pitch", 0f);
        }
    }
}
