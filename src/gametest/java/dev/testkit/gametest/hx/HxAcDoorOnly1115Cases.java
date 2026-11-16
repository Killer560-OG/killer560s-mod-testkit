package dev.testkit.gametest.hx;

import dev.testkit.gametest.SameTick;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * SAME-TICK GRIM CASES for Auto Clear's Door Only (acdooronly-1115, cheat jar only): the door click (use_item_on after the
 * body's turn and the key's slot) on the real GrimAC server, idle and with HIS action in the click's server tick
 * ({@link SameTick}). The Hx server has no dungeon map, so the test forces the door cell
 * ({@code AutoClearFeature.forceDoorForTest}) and builds the door as a coal column he stands 3 blocks from, a Wither Key
 * (any non-placeable item with id WITHER_KEY) in slot 0. The server never opens it, so the feature keeps clicking
 * (DOOR_RETRY_TICKS apart, up to 3, then another round after 5 s).
 * <ul>
 *   <li>1115-ac-dooronly-same-&lt;idle|use|attack|swap|tp&gt;: his right click, left click on a zombie in front of the door,
 *       hotbar scroll or the server's teleport accept in the click's tick; GrimAC (default and experimental checkouts)
 *       must stay silent, the feature's use_item_on and his action must both reach the wire.</li>
 *   <li>1115-ac-dooronly-same-rerun: stopped and started again; both runs click (a second run of the feature itself).</li>
 * </ul>
 */
final class HxAcDoorOnly1115Cases {

    private static final String CFG = "autoclear.AutoClearConfig";
    private static final String AC = "autoclear.AutoClearFeature";
    private static final String KEY = "minecraft:prismarine_shard[custom_data={id:\"WITHER_KEY\"},item_name=\"Wither Key\"]";

    private HxAcDoorOnly1115Cases() {
    }

    static void register(Session s) {
        s.test("1115-ac-dooronly-same-idle", c -> same(c, "idle"));
        s.test("1115-ac-dooronly-same-use", c -> same(c, "use"));
        s.test("1115-ac-dooronly-same-attack", c -> same(c, "attack"));
        s.test("1115-ac-dooronly-same-swap", c -> same(c, "swap"));
        s.test("1115-ac-dooronly-same-tp", c -> same(c, "tp"));
        s.test("1115-ac-dooronly-same-rerun", HxAcDoorOnly1115Cases::rerun);
    }

    /** The door column, the floor, the key and the dungeon sidebar; he stands 3 blocks south of the door. */
    private static BlockPos stage(Session c, boolean zombie) {
        dungeon(c);
        BlockPos door = (BlockPos) Mod.staticCall("livemap.DungeonLayout", "doorBlock", 1);
        int x = door.getX();
        int z = door.getZ();
        c.server().command("forceload add " + (x - 8) + " " + (z - 8) + " " + (x + 8) + " " + (z + 8));
        c.server().command(String.format("fill %d 67 %d %d 68 %d stone", x - 6, z - 6, x + 6, z + 6));
        c.server().command(String.format("fill %d 69 %d %d 75 %d air", x - 6, z - 6, x + 6, z + 6));
        c.server().command(String.format("fill %d 69 %d %d 71 %d coal_block", x, z, x, z));
        c.hx().call("dungeon.tp", "x", x + 0.5, "y", 69.0, "z", z + 3.5, "yaw", 180f, "pitch", 0f);
        c.hx().give(0, KEY);
        c.hx().give(4, "minecraft:stick");
        c.hx().give(5, "minecraft:stick");
        c.server().command("kill @e[tag=sametick]");
        if (zombie) {
            c.server().command(String.format(java.util.Locale.ROOT, "summon zombie %.2f 69 %.2f "
                    + "{NoAI:1b,Invulnerable:1b,Silent:1b,Tags:[\"sametick\"]}", x + 0.5, z + 1.9));
        }
        c.waitUntil("the door column on the client", mc -> mc.level.getBlockState(door).is(net.minecraft.world.level.block.Blocks.COAL_BLOCK), 200);
        c.ctx().waitTicks(10);
        return door;
    }

    private static void clean(Session c, BlockPos door) {
        c.onClient(mc -> {
            Mod.staticCall(AC, "forceDoorForTest", -1);
            if ((Boolean) Mod.staticCall(AC, "isBusy")) {
                Mod.staticCall(AC, "toggle");
            }
            return null;
        });
        c.server().command("kill @e[tag=sametick]");
        c.server().command("forceload remove all");
        for (int s : new int[]{0, 4, 5}) {
            c.hx().give(s, "minecraft:air");
        }
        hub(c);
    }

    private static void startAc(Session c) {
        c.onClient(mc -> {
            Mod.staticCall(AC, "forceDoorForTest", 1);
            Mod.staticCall(AC, "start");
            return null;
        });
    }

    private static int blockUses(Session c, long since) {
        return c.hx().events(since, "use_block").size();
    }

    private static void same(Session c, String a) {
        if (!Mod.isCheat()) {
            c.note("legit jar: Auto Clear Door Only is cheat-only");
            return;
        }
        SameTick.Act act = switch (a) {
            case "use" -> SameTick.Act.USE;
            case "attack" -> SameTick.Act.ATTACK;
            case "swap" -> SameTick.Act.SWAP;
            case "tp" -> SameTick.Act.TP;
            default -> null;
        };
        BlockPos door = null;
        SameTick st = null;
        try (Settings set = new Settings(c).with(CFG, "Enabled", true).with(CFG, "DoorOnly", true)) {
            door = stage(c, act == SameTick.Act.ATTACK);
            long since = c.hx().head();
            startAc(c);
            c.waitUntil("Door Only running", mc -> (Boolean) Mod.staticCall(AC, "isBusy"), 40);
            if (act != null) {
                // every 3rd tick for his attack and scroll (the feature's turn and swap need the ticks between), every 2nd for use
                st = c.onClient(mc -> SameTick.start(act, act == SameTick.Act.USE ? 2 : 3,
                        p -> p instanceof ServerboundUseItemOnPacket, 4, 5));
            }
            for (int i = 0; i < 260; i++) {
                if (act == SameTick.Act.TP && i % 2 == 0) {
                    c.server().command("execute as @a at @s run tp @s ~ ~ ~");
                }
                c.ctx().waitTick();
            }
            int uses = blockUses(c, since);
            boolean busy = c.onClient(mc -> (Boolean) Mod.staticCall(AC, "isBusy"));
            c.note("Door Only " + a + ": server use_block " + uses + ", still running " + busy);
            c.check(uses > 0, "the server saw no door click (nothing tested)");
            c.check(busy, "Door Only stopped by itself while the door stood shut");
            if (st != null) {
                SameTick.judge(c, st, "Door Only click", SameTick.Expect.ACTED);
                st = null;
            }
        } finally {
            if (st != null) {
                SameTick st2 = st;
                c.onClient(mc -> {
                    st2.close();
                    return null;
                });
            }
            clean(c, door);
        }
    }

    private static void rerun(Session c) {
        if (!Mod.isCheat()) {
            c.note("legit jar: Auto Clear Door Only is cheat-only");
            return;
        }
        BlockPos door = null;
        try (Settings set = new Settings(c).with(CFG, "Enabled", true).with(CFG, "DoorOnly", true)) {
            door = stage(c, false);
            int[] runs = new int[2];
            for (int r = 0; r < 2; r++) {
                long since = c.hx().head();
                startAc(c);
                c.waitUntil("Door Only running (run " + (r + 1) + ")", mc -> (Boolean) Mod.staticCall(AC, "isBusy"), 40);
                c.ctx().waitTicks(100);
                runs[r] = blockUses(c, since);
                c.onClient(mc -> {
                    Mod.staticCall(AC, "toggle");
                    return null;
                });
                c.ctx().waitTicks(5);
            }
            c.note("door clicks the server saw: run 1 " + runs[0] + ", run 2 " + runs[1]);
            c.check(runs[0] > 0 && runs[1] > 0, "a run sent no door click (" + runs[0] + ", " + runs[1] + ")");
        } finally {
            clean(c, door);
        }
    }
}
