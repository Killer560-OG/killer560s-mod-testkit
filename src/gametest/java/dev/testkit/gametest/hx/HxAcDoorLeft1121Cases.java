package dev.testkit.gametest.hx;

import dev.testkit.gametest.SameTick;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * SAME-TICK GRIM CASES for Auto Secret's left-click door open (acwaits-1121, cheat jar only; killer560 2026-11-21: "it can
 * open the door with any item by left clicking it as well"). A blood door opens on a left click with whatever is in hand,
 * so {@code WitherDoorOpener} sends vanilla's tap (START through the block prediction, ABORT, swing) with no slot change,
 * and in the tick it is asked when the look the server already heard strikes the door. The Hx server never opens it, so
 * the case asks again whenever the opener is idle. The door is a bedrock column he stands 3 blocks from.
 * <ul>
 *   <li>1121-hx-acdoor-left-same-&lt;idle|use|attack|swap|tp&gt;: his right click, a zombie hit in front of the door, a hotbar
 *       scroll or the server's teleport accept in the click's tick; GrimAC (default and experimental checkouts) must stay
 *       silent, the door's START and his action must both reach the wire, and the server must see the door attacked.</li>
 *   <li>1121-hx-acdoor-left-same-rerun: a click asked again while one is pending; both rounds reach the server.</li>
 * </ul>
 */
final class HxAcDoorLeft1121Cases {

    private static final String WDO = "autosecret.WitherDoorOpener";

    private HxAcDoorLeft1121Cases() {
    }

    static void register(Session s) {
        s.test("1121-hx-acdoor-left-same-idle", c -> same(c, "idle"));
        s.test("1121-hx-acdoor-left-same-use", c -> same(c, "use"));
        s.test("1121-hx-acdoor-left-same-attack", c -> same(c, "attack"));
        s.test("1121-hx-acdoor-left-same-swap", c -> same(c, "swap"));
        s.test("1121-hx-acdoor-left-same-tp", c -> same(c, "tp"));
        s.test("1121-hx-acdoor-left-same-rerun", HxAcDoorLeft1121Cases::rerun);
    }

    private static BlockPos stage(Session c, boolean zombie) {
        dungeon(c);
        BlockPos door = (BlockPos) Mod.staticCall("livemap.DungeonLayout", "doorBlock", 1);
        int x = door.getX();
        int z = door.getZ();
        c.server().command("forceload add " + (x - 8) + " " + (z - 8) + " " + (x + 8) + " " + (z + 8));
        c.server().command(String.format("fill %d 67 %d %d 68 %d stone", x - 6, z - 6, x + 6, z + 6));
        c.server().command(String.format("fill %d 69 %d %d 75 %d air", x - 6, z - 6, x + 6, z + 6));
        c.server().command(String.format("fill %d 69 %d %d 71 %d bedrock", x, z, x, z));
        c.hx().call("dungeon.tp", "x", x + 0.5, "y", 69.0, "z", z + 3.5, "yaw", 180f, "pitch", 0f);
        c.hx().give(0, "minecraft:stick");
        c.hx().give(4, "minecraft:stick");
        c.hx().give(5, "minecraft:stick");
        c.server().command("kill @e[tag=sametick]");
        if (zombie) {
            c.server().command(String.format(java.util.Locale.ROOT, "summon zombie %.2f 69 %.2f "
                    + "{NoAI:1b,Invulnerable:1b,Silent:1b,Tags:[\"sametick\"]}", x + 0.5, z + 1.9));
        }
        c.waitUntil("the door column on the client", mc -> mc.level.getBlockState(door).is(net.minecraft.world.level.block.Blocks.BEDROCK), 200);
        c.ctx().waitTicks(10);
        return door;
    }

    private static void clean(Session c) {
        c.onClient(mc -> {
            Mod.staticCall(WDO, "cancel");
            return null;
        });
        c.server().command("kill @e[tag=sametick]");
        c.server().command("forceload remove all");
        for (int s : new int[]{0, 4, 5}) {
            c.hx().give(s, "minecraft:air");
        }
        hub(c);
    }

    /** Asks for a click whenever the opener is idle; the number asked. */
    private static int askIfIdle(Session c, BlockPos door) {
        return c.onClient(mc -> {
            if ((Boolean) Mod.staticCall(WDO, "isBusy")) {
                return 0;
            }
            Mod.staticCall(WDO, "click", door, true);
            return 1;
        });
    }

    private static void same(Session c, String a) {
        if (!Mod.isCheat()) {
            c.note("legit jar: Auto Secret is cheat-only");
            return;
        }
        SameTick.Act act = switch (a) {
            case "use" -> SameTick.Act.USE;
            case "attack" -> SameTick.Act.ATTACK;
            case "swap" -> SameTick.Act.SWAP;
            case "tp" -> SameTick.Act.TP;
            default -> null;
        };
        SameTick st = null;
        try {
            BlockPos door = stage(c, act == SameTick.Act.ATTACK);
            long since = c.hx().head();
            if (act != null) {
                st = c.onClient(mc -> SameTick.start(act, act == SameTick.Act.USE ? 2 : 3,
                        p -> p instanceof ServerboundPlayerActionPacket, 4, 5));
            }
            int asked = 0;
            for (int i = 0; i < 200; i++) {
                if (i % 4 == 0) {
                    asked += askIfIdle(c, door);
                }
                if (act == SameTick.Act.TP && i % 2 == 0) {
                    c.server().command("execute as @a at @s run tp @s ~ ~ ~");
                }
                c.ctx().waitTick();
            }
            int hits = c.hx().events(since, "attack_block").size();
            int uses = c.hx().events(since, "use_block").size();
            c.note("left door click " + a + ": asked " + asked + ", server attack_block " + hits + ", use_block " + uses);
            c.check(hits > 0, "the server saw no door attack (nothing tested)");
            c.check(uses == 0, "the blood door was right-clicked " + uses + " time(s); a left click needs none");
            if (st != null) {
                SameTick.judge(c, st, "left door click", SameTick.Expect.ACTED);
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
            clean(c);
        }
    }

    private static void rerun(Session c) {
        if (!Mod.isCheat()) {
            c.note("legit jar: Auto Secret is cheat-only");
            return;
        }
        try {
            BlockPos door = stage(c, false);
            int[] runs = new int[2];
            for (int r = 0; r < 2; r++) {
                long since = c.hx().head();
                c.onClient(mc -> {
                    Mod.staticCall(WDO, "click", door, true);
                    Mod.staticCall(WDO, "click", door, true);   // asked again while pending: still one click
                    return null;
                });
                c.ctx().waitTicks(20);
                runs[r] = c.hx().events(since, "attack_block").size();
            }
            c.note("door attacks the server saw: round 1 " + runs[0] + ", round 2 " + runs[1]);
            c.check(runs[0] == 1 && runs[1] == 1, "each round should reach the server once (" + runs[0] + ", " + runs[1] + ")");
        } finally {
            clean(c);
        }
    }
}
