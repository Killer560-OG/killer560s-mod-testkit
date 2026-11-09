package dev.testkit.gametest.hx;

import dev.testkit.gametest.SameTick;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Auto Superboom (featD-1108, item 11), cheat jar only. His left click on a cracked wall swaps to the Superboom (slot 2) at
 * START ahead of the dig and back later; every case proves the server saw held=2 then held=4 (or, with his click every
 * tick, the boom kept out until he stops), that his dig reached the wire, and GrimAC (default and experimental
 * checkouts, PacketOrderE) stays silent with his own action in the swap's tick.
 * <ul>
 *   <li>1108-same-superboom-dig: his dig every 4th tick (SHARE: the swap and his dig in one server tick).</li>
 *   <li>1108-same-superboom-dig-every-tick: his dig every tick (the boom stays out, back after he stops).</li>
 *   <li>1108-same-superboom-use / -swap / -tp: his right click / scroll / teleport accept every tick, plus one
 *       left click on the wall a round.</li>
 * </ul>
 */
final class HxSuperboom1108Cases {

    private static final String CFG = "autosuperboom.AutoSuperboomConfig";
    private static final String BOOM = "minecraft:tnt[custom_data={id:\"SUPERBOOM_TNT\"},item_name=\"Superboom TNT\"]";

    private HxSuperboom1108Cases() {
    }

    static void register(Session s) {
        s.test("1108-same-superboom-dig", c -> run(c, SameTick.Act.DIG, 4));
        s.test("1108-same-superboom-dig-every-tick", c -> run(c, SameTick.Act.DIG, 1));
        s.test("1108-same-superboom-use", c -> run(c, SameTick.Act.USE, 1));
        s.test("1108-same-superboom-swap", c -> run(c, SameTick.Act.SWAP, 1));
        s.test("1108-same-superboom-tp", c -> run(c, SameTick.Act.TP, 1));
    }

    private static void run(Session c, SameTick.Act act, int every) {
        if (!Mod.isCheat()) {
            c.note("legit jar: Auto Superboom is cheat-only");
            return;
        }
        boolean gateWas = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled"));
        SameTick st = null;
        try (Settings set = new Settings(c).with(CFG, "Enabled", true)) {
            c.onClient(mc -> {
                Mod.staticCall("util.SkyblockGate", "setEnabled", false);
                return null;
            });
            c.hx().give(2, BOOM);
            c.hx().give(4, "minecraft:stick");
            c.hx().give(5, "minecraft:stick");
            dungeon(c);
            c.onClient(mc -> {
                mc.player.setYRot(0f);
                mc.player.setXRot(0f);
                mc.player.getInventory().setSelectedSlot(4);
                return null;
            });
            c.ctx().waitTicks(3);
            double[] at = c.onClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
            String wall = String.format(java.util.Locale.ROOT, "%d %d %d", (int) Math.floor(at[0]),
                    (int) Math.floor(at[1]) + 1, (int) Math.floor(at[2]) + 2);
            String tp = String.format(java.util.Locale.ROOT, "tp @p %.3f %.3f %.3f 0 0", at[0], at[1], at[2]);
            long since = c.hx().head();
            st = c.onClient(mc -> SameTick.start(act, every, p -> p instanceof ServerboundSetCarriedItemPacket h
                    && (h.getSlot() == 2 || h.getSlot() == 4), 4, 5));
            for (int r = 0; r < 6; r++) {
                c.server().command("setblock " + wall + " minecraft:cracked_stone_bricks");
                c.waitUntil("the cracked wall under his crosshair", mc -> mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult b
                        && b.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                        && mc.level.getBlockState(b.getBlockPos()).is(net.minecraft.world.level.block.Blocks.CRACKED_STONE_BRICKS), 100);
                if (act != SameTick.Act.DIG) {
                    if (act == SameTick.Act.TP) {
                        c.server().command(tp);
                    }
                    c.onClient(mc -> {
                        mc.player.getInventory().setSelectedSlot(4);
                        net.minecraft.client.KeyMapping.click(mc.options.keyAttack.getDefaultKey());
                        return null;
                    });
                }
                c.ctx().waitTicks(10);
            }
            SameTick cur = st;
            if (act == SameTick.Act.DIG) {
                SameTick.judge(c, cur, "auto superboom", SameTick.Expect.SHARE);
            } else {
                c.onClient(mc -> {
                    cur.close();
                    return null;
                });
                c.note("auto superboom " + st.report());
                c.check(st.done() > 0, "his action never went in (nothing tested)");
                c.check(st.featureWindows() > 0, "the feature sent nothing (" + st.report() + ")");
            }
            st = null;
            c.ctx().waitTicks(10);
            var held = c.hx().events(since, "held");
            int boomOut = (int) held.stream().filter(e -> e.get("slot").getAsInt() == 2).count();
            int firstBoom = -1;
            int backAfter = 0;
            for (int i = 0; i < held.size(); i++) {
                int slot = held.get(i).get("slot").getAsInt();
                if (slot == 2 && firstBoom < 0) {
                    firstBoom = i;
                } else if (slot == 4 && firstBoom >= 0) {
                    backAfter++;
                }
            }
            c.note("server held=2 events " + boomOut + ", held=4 after the first " + backAfter);
            c.check(boomOut >= 1, "the server never saw the Superboom slot (the feature did not act)");
            c.check(backAfter >= 1, "the server never saw the slot go back to the held item");
            if (act != SameTick.Act.SWAP) {
                c.check(c.onClient(mc -> mc.player.getInventory().getSelectedSlot()) == 4, "the held slot did not come back to 4");
            }
        } finally {
            if (st != null) {
                SameTick s2 = st;
                c.onClient(mc -> {
                    s2.close();
                    return null;
                });
            }
            c.onClient(mc -> {
                Mod.staticCall("util.SkyblockGate", "setEnabled", gateWas);
                return null;
            });
            for (int s : new int[]{2, 4, 5}) {
                c.hx().give(s, "minecraft:air");
            }
            hub(c);
        }
    }
}
