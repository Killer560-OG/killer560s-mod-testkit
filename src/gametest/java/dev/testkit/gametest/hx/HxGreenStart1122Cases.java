package dev.testkit.gametest.hx;

import dev.testkit.gametest.SameTick;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.Vec3;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * SAME-TICK GRIM CASES for the green room start walk (acgreenstart-1122, cheat jar only): Dungeons Autopilot switched on
 * before the run walks him (ShortWalk keys, body turned along the way, camera held) to the Entrance door's front. The Hx
 * server has no Entrance, so the walk's goal is set by {@code GreenRoomStart.testGoal}: 10 blocks along a flat floor, back
 * and forth (switched off and on at each end). His actions are put into the walk's ticks; the feature's packets are the
 * movement packets sent while it walks.
 * <ul>
 *   <li>1122-hx-greenstart-same-&lt;idle|use|attack|swap|click|tp&gt;: his right click, a hit on an interaction entity round
 *       him, a hotbar scroll, an inventory click (opened and closed inside one tick) or the server's teleport accept in the
 *       walk's ticks; GrimAC (default and experimental checkouts) silent, both on the wire, and he walked at least 15
 *       blocks.</li>
 *   <li>1122-hx-greenstart-same-rerun: switched off and on again mid-walk (a second run of it): each run walks on.</li>
 * </ul>
 */
final class HxGreenStart1122Cases {

    private static final String GRS = "autosecret.GreenRoomStart";
    private static final String PILOT = "autosecret.DungeonsAutopilot";

    private HxGreenStart1122Cases() {
    }

    static void register(Session s) {
        for (String a : new String[]{"idle", "use", "attack", "swap", "click", "tp"}) {
            s.test("1122-hx-greenstart-same-" + a, c -> same(c, a));
        }
        s.test("1122-hx-greenstart-same-rerun", HxGreenStart1122Cases::rerun);
    }

    /** The floor; returns the two ends of the walk (feet blocks). */
    private static BlockPos[] stage(Session c, boolean target) {
        dungeon(c);
        BlockPos door = (BlockPos) Mod.staticCall("livemap.DungeonLayout", "doorBlock", 1);
        int x = door.getX();
        int z = door.getZ();
        c.server().command("forceload add " + (x - 14) + " " + (z - 14) + " " + (x + 14) + " " + (z + 14));
        c.server().command(String.format("fill %d 67 %d %d 68 %d stone", x - 12, z - 12, x + 12, z + 12));
        c.server().command(String.format("fill %d 69 %d %d 75 %d air", x - 12, z - 12, x + 12, z + 12));
        c.hx().call("dungeon.tp", "x", x + 0.5, "y", 69.0, "z", z - 4.5, "yaw", 90f, "pitch", 0f);
        c.hx().give(4, "minecraft:stick");
        c.hx().give(5, "minecraft:stick");
        c.server().command("kill @e[tag=sametick]");
        if (target) {
            c.server().command(String.format(java.util.Locale.ROOT, "summon minecraft:interaction %.2f 66 %.2f "
                    + "{width:30f,height:8f,response:true,Tags:[\"sametick\"]}", x + 0.5, z + 0.5));
        }
        c.waitUntil("the floor on the client", mc -> !mc.level.getBlockState(new BlockPos(x, 68, z + 5)).isAir(), 200);
        c.onClient(mc -> {
            mc.player.getInventory().setSelectedSlot(4);
            return null;
        });
        c.ctx().waitTicks(10);
        return new BlockPos[]{new BlockPos(x, 69, z + 5), new BlockPos(x, 69, z - 5)};
    }

    private static void clean(Session c) {
        c.onClient(mc -> {
            Mod.staticCall(PILOT, "setOn", false);
            Mod.staticCall(GRS, "testGoal", (Object) null);
            return null;
        });
        c.server().command("kill @e[tag=sametick]");
        c.server().command("forceload remove all");
        for (int s : new int[]{4, 5}) {
            c.hx().give(s, "minecraft:air");
        }
        hub(c);
    }

    /** Switched on toward {@code goal} (off first). */
    private static void go(Session c, BlockPos goal) {
        c.onClient(mc -> {
            Mod.staticCall(PILOT, "setOn", false);
            Mod.staticCall(GRS, "testGoal", goal);
            Mod.staticCall(PILOT, "setOn", true);
            return null;
        });
    }

    private static String result(Session c) {
        return c.onClient(mc -> String.valueOf(Mod.staticCall(GRS, "result")));
    }

    private static void same(Session c, String a) {
        if (!Mod.isCheat()) {
            c.note("legit jar: Dungeons Autopilot is cheat-only");
            return;
        }
        SameTick.Act act = switch (a) {
            case "use" -> SameTick.Act.USE;
            case "attack" -> SameTick.Act.ATTACK;
            case "swap" -> SameTick.Act.SWAP;
            case "click" -> SameTick.Act.OPEN_CLICK;
            case "tp" -> SameTick.Act.TP;
            default -> null;
        };
        SameTick st = null;
        try {
            BlockPos[] ends = stage(c, act == SameTick.Act.ATTACK);
            if (act != null) {
                st = c.onClient(mc -> SameTick.start(act, act == SameTick.Act.USE ? 2 : 3,
                        p -> p instanceof ServerboundMovePlayerPacket && "walking".equals(String.valueOf(Mod.staticCall(GRS, "result"))),
                        4, 5));
            }
            int legs = 0;
            double walked = 0;
            Vec3 last = c.onClient(mc -> mc.player.position());
            for (int i = 0; i < 240; i++) {
                if (!"walking".equals(result(c))) {
                    go(c, ends[legs % 2]);
                    legs++;
                }
                if (act == SameTick.Act.TP && i % 4 == 1) {
                    c.server().command("execute as @a at @s run tp @s ~ ~ ~");
                }
                c.ctx().waitTick();
                Vec3 now = c.onClient(mc -> mc.player.position());
                walked += Math.hypot(now.x - last.x, now.z - last.z);
                last = now;
            }
            c.note(String.format(java.util.Locale.US, "green room walk %s: %d leg(s) asked, walked %.1f blocks, last result %s",
                    a, legs, walked, result(c)));
            c.check(walked >= 15, String.format(java.util.Locale.US, "walked only %.1f blocks (nothing tested)", walked));
            if (st != null) {
                SameTick.judge(c, st, "green room walk", act == SameTick.Act.OPEN_CLICK ? SameTick.Expect.ACTED
                        : SameTick.Expect.SHARE);
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
            c.note("legit jar: Dungeons Autopilot is cheat-only");
            return;
        }
        try {
            BlockPos[] ends = stage(c, false);
            double[] walked = new double[2];
            for (int r = 0; r < 2; r++) {
                go(c, ends[r]);
                c.ctx().waitTicks(8);
                go(c, ends[r]);   // switched off and on mid-walk: it walks on to the same goal
                Vec3 from = c.onClient(mc -> mc.player.position());
                for (int i = 0; i < 80 && !"arrived".equals(result(c)); i++) {
                    c.ctx().waitTick();
                }
                Vec3 to = c.onClient(mc -> mc.player.position());
                walked[r] = Math.hypot(to.x - from.x, to.z - from.z);
                BlockPos g = ends[r];
                double off = Math.hypot(to.x - (g.getX() + 0.5), to.z - (g.getZ() + 0.5));
                c.check("arrived".equals(result(c)) && off < 0.8, String.format(java.util.Locale.US,
                        "run %d: result %s, %.2f from its goal", r + 1, result(c), off));
            }
            c.note(String.format(java.util.Locale.US, "walked after the second switch-on: run 1 %.1f, run 2 %.1f", walked[0], walked[1]));
            c.check(walked[0] > 2 && walked[1] > 2, "a run did not walk on after the second switch-on");
        } finally {
            clean(c);
        }
    }
}
