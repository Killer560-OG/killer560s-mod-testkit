package dev.testkit.gametest.hx;

import dev.testkit.gametest.SameTick;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Auto Superboom "Also When In Blast Range" (superboom-range-1118), cheat jar only. He clicks a plain stone block B at
 * 17,120,129 (north face centered, the Superboom lands at 17.5,120.5,129.0); a target (cracked brick or a crypt lid) sits
 * at an offset from B. The mod's reach is radius 3 from that point to the target's box (SimItems.inReach): inside the
 * swap goes out (server sees held=2), outside nothing does.
 * <ul>
 *   <li>1118-superboom-range-wall / -crypt: every offset, in and out, the edge included.</li>
 *   <li>1118-superboom-range-off: switch OFF, an in-range click does nothing; a direct cracked click still swaps.</li>
 *   <li>1118-same-superboom-range-dig / -dig-every-tick / -use / -use-key / -swap: his own action in the swap tick.</li>
 * </ul>
 */
final class HxSuperboomRange1118Cases {

    private static final String CFG = "autosuperboom.AutoSuperboomConfig";
    private static final String BOOM = "minecraft:tnt[custom_data={id:\"SUPERBOOM_TNT\"},item_name=\"Superboom TNT\"]";
    private static final BlockPos B = new BlockPos(17, 120, 129);
    /** dx, dy, dz from B, expected in range (box distance from 17.5,120.5,129.0 at most 3). */
    private static final int[][] WALL = {
        {0, 0, 1, 1}, {0, 0, 3, 1}, {0, 0, 4, 0}, {3, 0, 0, 1}, {4, 0, 0, 0}, {-3, 0, 0, 1}, {-4, 0, 0, 0},
        {0, 3, 0, 1}, {0, 4, 0, 0}, {3, 0, 1, 1}, {3, 0, 2, 0}, {2, 0, 2, 1}, {-2, 2, 2, 1}, {-3, 2, 2, 0}};

    private HxSuperboomRange1118Cases() {
    }

    static void register(Session s) {
        s.test("1118-superboom-range-wall", c -> geometry(c, false));
        s.test("1118-superboom-range-crypt", c -> geometry(c, true));
        s.test("1118-superboom-range-off", HxSuperboomRange1118Cases::off);
        s.test("1118-same-superboom-range-dig", c -> same(c, SameTick.Act.DIG, 4));
        s.test("1118-same-superboom-range-dig-every-tick", c -> same(c, SameTick.Act.DIG, 1));
        s.test("1118-same-superboom-range-use", c -> same(c, SameTick.Act.USE, 1));
        s.test("1118-same-superboom-range-use-key", c -> same(c, SameTick.Act.USE_KEY, 1));
        s.test("1118-same-superboom-range-swap", c -> same(c, SameTick.Act.SWAP, 1));
    }

    private static void gateOff(Session c, boolean on) {
        c.onClient(mc -> {
            Mod.staticCall("util.SkyblockGate", "setEnabled", on);
            return null;
        });
    }

    /** Stone floor, clear air, B in front of him, aimed at B's north face center. */
    private static void stage(Session c, Runnable target) {
        c.server().command("fill 10 119 120 24 119 140 minecraft:stone");
        c.server().command("fill 10 120 120 24 127 140 minecraft:air");
        c.server().command("setblock 17 120 129 minecraft:stone");
        target.run();
        c.server().sync(10);
        float pitch = (float) Math.toDegrees(Math.atan2(121.62 - 120.5, 129 - 126.5));
        c.server().command(String.format(java.util.Locale.ROOT, "tp @p 17.5 120 126.5 0 %.2f", pitch));
        c.onClient(mc -> {
            mc.player.setYRot(0f);
            mc.player.setXRot(pitch);
            mc.player.getInventory().setSelectedSlot(4);
            return null;
        });
        c.waitUntil("B under his crosshair", mc -> mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult bh
                && bh.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && bh.getBlockPos().equals(B), 100);
    }

    private static void prep(Session c) {
        c.hx().give(2, BOOM);
        c.hx().give(4, "minecraft:stick");
        c.hx().give(5, "minecraft:stick");
        dungeon(c);
        c.server().command("forceload add 10 120 24 140");
        c.server().sync(20);
    }

    private static void cleanup(Session c, boolean gateWas) {
        gateOff(c, gateWas);
        for (int s : new int[]{2, 4, 5, 6}) {
            c.hx().give(s, "minecraft:air");
        }
        hub(c);
    }

    private static int swaps(Session c, long since) {
        return (int) c.hx().events(since, "held").stream().filter(e -> e.get("slot").getAsInt() == 2).count();
    }

    /** One click on B; true when the server saw the Superboom slot. */
    private static boolean clickSwaps(Session c) {
        c.onClient(mc -> {
            mc.player.getInventory().setSelectedSlot(4);
            return null;
        });
        c.ctx().waitTicks(2);
        long since = c.hx().head();
        c.onClient(mc -> {
            net.minecraft.client.KeyMapping.click(mc.options.keyAttack.getDefaultKey());
            return null;
        });
        c.ctx().waitTicks(8);
        return swaps(c, since) > 0;
    }

    private static void geometry(Session c, boolean crypt) {
        if (!Mod.isCheat()) {
            c.note("legit jar: Auto Superboom is cheat-only");
            return;
        }
        boolean gateWas = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled"));
        try (Settings set = new Settings(c).with(CFG, "Enabled", true)) {
            gateOff(c, false);
            prep(c);
            int[][] cases = crypt ? new int[][]{{0, 0, 2, 1}, {0, 0, 3, 0}, {0, 0, 1, 1}, {0, 0, 4, 0}} : WALL;
            int acted = 0;
            for (int[] k : cases) {
                BlockPos t = B.offset(k[0], k[1], k[2]);
                stage(c, () -> {
                    if (crypt) {
                        // a lid of bottom slabs on a stone base, 3 wide and 2 deep, its near edge k[2] blocks behind B; the slabs sit one block up, so +2 is the edge (2.06) and +3 is out (3.04)
                        c.server().command(String.format("fill 16 120 %d 18 120 %d minecraft:stone_bricks", t.getZ(), t.getZ() + 1));
                        c.server().command(String.format("fill 16 121 %d 18 121 %d minecraft:smooth_stone_slab[type=bottom]", t.getZ(), t.getZ() + 1));
                    } else {
                        c.server().command("setblock " + t.getX() + " " + t.getY() + " " + t.getZ() + " minecraft:cracked_stone_bricks");
                    }
                });
                if (crypt) {
                    c.ctx().waitTicks(5);
                    Object found = c.onClient(mc -> Mod.staticCall("secretwaypoints.CryptScanner", "scan", mc.level, new int[]{1, 113, 33, 145}));
                    c.check(String.valueOf(found).contains("CRYPT"), "the scanner does not see the crypt at +" + k[2] + ": " + found);
                }
                boolean did = clickSwaps(c);
                c.note((crypt ? "crypt" : "wall") + " offset " + k[0] + "," + k[1] + "," + k[2] + " expect " + (k[3] == 1 ? "act" : "nothing")
                        + " got " + (did ? "swap" : "no swap"));
                c.check(did == (k[3] == 1), "offset " + k[0] + "," + k[1] + "," + k[2] + (k[3] == 1 ? " is in blast range but the feature did not act"
                        : " is outside blast range but the feature swapped"));
                acted += did ? 1 : 0;
            }
            c.check(acted >= 2, "the feature acted only " + acted + " times (nothing tested)");
        } finally {
            cleanup(c, gateWas);
        }
    }

    private static void off(Session c) {
        if (!Mod.isCheat()) {
            c.note("legit jar: Auto Superboom is cheat-only");
            return;
        }
        boolean gateWas = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled"));
        try (Settings set = new Settings(c).with(CFG, "Enabled", true).with(CFG, "BlastRange", false)) {
            gateOff(c, false);
            prep(c);
            stage(c, () -> c.server().command("setblock 17 120 130 minecraft:cracked_stone_bricks"));
            c.check(!clickSwaps(c), "switch OFF: an in-range click still swapped");
            // a direct click on the cracked wall keeps working
            c.server().command("setblock 17 120 129 minecraft:cracked_stone_bricks");
            c.ctx().waitTicks(3);
            c.check(clickSwaps(c), "a direct cracked-wall click did not swap with the switch OFF");
            c.check(c.onClient(mc -> !(Boolean) Mod.call(Mod.cfg(CFG), "isBlastRange")), "the switch did not read OFF");
        } finally {
            cleanup(c, gateWas);
        }
    }

    /** His own action in the swap's tick, the click on B alone (an in-range target 2 behind). */
    private static void same(Session c, SameTick.Act act, int every) {
        if (!Mod.isCheat()) {
            c.note("legit jar: Auto Superboom is cheat-only");
            return;
        }
        boolean gateWas = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled"));
        SameTick st = null;
        try (Settings set = new Settings(c).with(CFG, "Enabled", true)) {
            gateOff(c, false);
            prep(c);
            c.hx().give(6, "minecraft:stick");
            Runnable target = () -> c.server().command("setblock 17 120 131 minecraft:cracked_stone_bricks");
            stage(c, target);
            long since = c.hx().head();
            st = c.onClient(mc -> SameTick.start(act, every, p -> p instanceof ServerboundSetCarriedItemPacket h
                    && (h.getSlot() == 2 || act != SameTick.Act.SWAP && h.getSlot() == 4), act == SameTick.Act.SWAP ? 5 : 4,
                    act == SameTick.Act.SWAP ? 6 : 5));
            for (int r = 0; r < 6; r++) {
                stage(c, target);
                if (act == SameTick.Act.DIG) {
                    c.ctx().waitTicks(10);
                } else {
                    for (int i = 0; i < 10; i++) {
                        if (i % 4 == 0) {
                            c.onClient(mc -> {
                                net.minecraft.client.KeyMapping.click(mc.options.keyAttack.getDefaultKey());
                                return null;
                            });
                        }
                        c.ctx().waitTicks(1);
                    }
                }
            }
            SameTick.judge(c, st, "auto superboom in range, his " + act,
                    act == SameTick.Act.SWAP || act == SameTick.Act.USE_KEY ? SameTick.Expect.ACTED : SameTick.Expect.SHARE);
            st = null;
            c.ctx().waitTicks(10);
            int n = swaps(c, since);
            c.note("server held=2 events " + n);
            c.check(n >= 1, "the server never saw the Superboom slot on an in-range click (the feature did not act)");
        } finally {
            if (st != null) {
                SameTick s2 = st;
                c.onClient(mc -> {
                    s2.close();
                    return null;
                });
            }
            cleanup(c, gateWas);
        }
    }
}
