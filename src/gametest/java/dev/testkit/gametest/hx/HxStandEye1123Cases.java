package dev.testkit.gametest.hx;

import dev.testkit.gametest.SameTick;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;

/**
 * Hypixel casts an etherwarp whose sneak reached the server in the same server tick from the STANDING eye (1.62), not the
 * crouched 1.27 (zpew-ideas-1122; his logs: 13 of 60 warps sent the tick after the sneak landed 1-3 blocks further). Auto
 * Clear, Auto Secret and Pathfinding hops fire that tick (mod standeye-1123): they now aim so both eyes land on the planned
 * block (no cost) or, with no such look, wait one tick for the sneak to age. The hx server models the pose lag
 * (dungeon.standingEye fresh); the hop is queued the way {@code ClearNode.EtherNode.execute} does
 * ({@code ClearExecutor.queueInteract}) the tick after the sneak goes out.
 * <ul>
 *   <li>1123-hx-standeye-senders-aim: a far block (no look holds for both eyes: the hop waits one tick) and a near one (a
 *       look holds for both: re-aimed, goes on the first tick); the server's landing must be the planned block, from the
 *       standing eye in the near case (it did cast from it), and a use must have gone out.</li>
 *   <li>1123-hx-standeye-senders-same-&lt;idle|use|dig|swap|tp&gt;: the same hops with his own action in the same ticks
 *       ({@link SameTick}); GrimAC (default and experimental) must stay silent, hops and his action both reach the wire.</li>
 *   <li>1125-hx-standeye-twotick[-same-&lt;act&gt;] (autopilot-warp-offaim-1125): the server stalled and took the sneak and the
 *       next tick's packets together (dungeon.standingEye ticks 2), so a hop sent TWO ticks after the sneak is still cast
 *       standing. His 26.3 log 11-25 15:32:40: Interactive Map warp 2, sent so with a crouched-only aim, landed exactly one
 *       standing-eye block past its aim; 15:31:09 warp 2 never landed and the next hop went from the wrong spot. The same
 *       far and near hops, queued two ticks after the sneak, must land on the planned block.</li>
 * </ul>
 */
final class HxStandEye1123Cases {

    private static final String CE = "livemap.autoclear.ClearExecutor";
    private static final String AOTV = "minecraft:diamond_shovel[custom_data={id:\"ASPECT_OF_THE_VOID\",ethermerge:1,"
            + "tuned_transmission:4},unbreakable={}]";
    private static final double X0 = -424.5;
    private static final double Y = 151;
    private static final double Z0 = -420.5;

    private HxStandEye1123Cases() {
    }

    static void register(Session s) {
        s.test("1123-hx-standeye-senders-aim", c -> run(c, null));
        s.test("1123-hx-standeye-senders-same-idle", c -> run(c, "idle"));
        s.test("1123-hx-standeye-senders-same-use", c -> run(c, "use"));
        s.test("1123-hx-standeye-senders-same-dig", c -> run(c, "dig"));
        s.test("1123-hx-standeye-senders-same-swap", c -> run(c, "swap"));
        s.test("1123-hx-standeye-senders-same-tp", c -> run(c, "tp"));
        s.test("1125-hx-standeye-twotick", c -> run(c, null, 2));
        s.test("1125-hx-standeye-twotick-same-idle", c -> run(c, "idle", 2));
        s.test("1125-hx-standeye-twotick-same-use", c -> run(c, "use", 2));
        s.test("1125-hx-standeye-twotick-same-dig", c -> run(c, "dig", 2));
        s.test("1125-hx-standeye-twotick-same-swap", c -> run(c, "swap", 2));
        s.test("1125-hx-standeye-twotick-same-tp", c -> run(c, "tp", 2));
    }

    /** The mod's re-aim counter, 0 on a jar from before it (the origin jar). */
    private static int reaims(Session c) {
        return c.onClient(mc -> {
            try {
                return ((Number) Mod.staticCall(CE, "standingReaims")).intValue();
            } catch (RuntimeException | AssertionError e) {
                return 0;
            }
        });
    }

    private static boolean feature(Packet<?> p) {
        return p instanceof ServerboundUseItemPacket || p instanceof ServerboundPlayerInputPacket in && in.input().shift();
    }

    /** One hop (dig: he looks at the floor, so his left click has a block under the crosshair): sneak down, {@code after}
     *  ticks after it goes out queue the hop at {@code d} blocks; returns {ticks to the use, land}. */
    private static double[] hop(Session c, double d, int cycle, boolean dig, int after) {
        c.ctx().getInput().releaseKey(o -> o.keyShift);
        c.server().command(String.format(Locale.ROOT, "tp @p %.2f %.2f %.2f -90 %d", X0, Y, Z0, dig ? 45 : 0));
        c.ctx().waitTicks(8);
        int sizeBefore = c.onClient(mc -> ((List<?>) Mod.staticCall(CE, "hopSendTicks")).size());
        int reaimsBefore = reaims(c);
        int castBefore = c.hx().call("dungeon.stats").getAsJsonObject().get("standingEyeCast").getAsInt();
        int warpsBefore = c.hx().call("dungeon.stats").getAsJsonObject().get("etherwarps").getAsInt();
        c.onClient(mc -> {
            mc.player.getInventory().setSelectedSlot(2);
            return null;
        });
        c.ctx().getInput().holdKey(o -> o.keyShift);
        c.ctx().waitTicks(after); // the sneak goes out at the end of the first tick; the hop is queued for the one after
        float pitch = (float) Math.toDegrees(Math.atan2(1.27, d));
        BlockPos block = BlockPos.containing(X0 + d, Y - 1, Z0);
        c.onClient(mc -> {
            Mod.staticCall(CE, "queueInteract", -90f, pitch, new Vec3(X0, Y, Z0), block, new String[]{"ASPECT_OF_THE_VOID"});
            return null;
        });
        int ticks = -1;
        for (int i = 1; i <= 20; i++) {
            c.ctx().waitTick();
            int now = c.onClient(mc -> ((List<?>) Mod.staticCall(CE, "hopSendTicks")).size());
            if (now > sizeBefore) {
                ticks = i;
                break;
            }
        }
        c.ctx().waitTicks(14);
        Vec3 end = c.onClient(mc -> mc.player.position());
        int cast = c.hx().call("dungeon.stats").getAsJsonObject().get("standingEyeCast").getAsInt() - castBefore;
        int warps = c.hx().call("dungeon.stats").getAsJsonObject().get("etherwarps").getAsInt() - warpsBefore;
        int reaims = reaims(c) - reaimsBefore;
        return new double[]{ticks, end.x, end.z, cast, warps, reaims};
    }

    private static void run(Session c, String act) {
        run(c, act, 1);
    }

    /** @param after ticks between the sneak going out and the hop being queued; also the server's standing window */
    private static void run(Session c, String act, int after) {
        if (!Mod.isCheat()) {
            c.note("legit jar: Auto Clear hops are cheat-only");
            return;
        }
        SameTick.Act a = act == null ? null : switch (act) {
            case "use" -> SameTick.Act.USE;
            case "dig" -> SameTick.Act.DIG;
            case "swap" -> SameTick.Act.SWAP;
            case "tp" -> SameTick.Act.TP;
            default -> null;
        };
        SameTick st = null;
        try {
            c.server().command("fill -426 150 -424 -412 150 -416 smooth_stone");
            c.server().command("fill -426 151 -424 -412 154 -416 air");
            c.server().sync(10);
            c.hx().give(2, AOTV);
            c.hx().give(4, AOTV);
            c.hx().give(5, AOTV);
            c.hx().call("dungeon.abilities", "enabled", true);
            c.hx().call("dungeon.standingEye", "fresh", true, "ticks", after);
            c.server().command("kill @e[tag=sametick]");
            if (a != null) {
                st = c.onClient(mc -> SameTick.start(a, 2, HxStandEye1123Cases::feature, 4, 5));
            }
            c.ctx().waitTicks(10);
            double[] far = null;
            double[] near = null;
            int cycles = act == null ? 1 : 4;
            for (int i = 0; i < cycles; i++) {
                far = hop(c, 6.0, i, a == SameTick.Act.DIG, after);
                near = hop(c, 2.0, i, a == SameTick.Act.DIG, after);
                c.note(String.format(Locale.ROOT, "%s cycle %d: far use after %.0f tick(s), landed %.2f,%.2f, %.0f standing cast(s), "
                                + "%.0f warp(s), %.0f re-aim(s); near use after %.0f tick(s), landed %.2f,%.2f, %.0f standing cast(s), "
                                + "%.0f warp(s), %.0f re-aim(s)", act == null ? "aim" : act, i, far[0], far[1], far[2], far[3],
                        far[4], far[5], near[0], near[1], near[2], near[3], near[4], near[5]));
                c.check(far[0] > 0 && near[0] > 0, "a hop never went out (far " + far[0] + ", near " + near[0] + ")");
                if (after == 2) {
                    // two ticks after the sneak, cast standing by the stalled server: the far hop waits a tick more (no
                    // look holds for both eyes) and lands crouched on the plan; the near one is re-aimed for both eyes
                    c.check(far[4] == 1 && Math.abs(far[1] - (X0 + 6.0)) < 0.1 && Math.abs(far[2] - Z0) < 0.1,
                            "two ticks, far block: want 1 warp landing at x " + (X0 + 6.0) + ", got " + far[4] + " at "
                                    + far[1] + "," + far[2] + " (" + far[3] + " standing cast(s))");
                    c.check(near[4] == 1 && (act != null || near[3] == 1) && Math.abs(near[1] - (X0 + 2.0)) < 0.1 && Math.abs(near[2] - Z0) < 0.1,
                            "two ticks, near block: want a standing cast landing at x " + (X0 + 2.0) + ", got " + near[4]
                                    + " warp(s) at " + near[1] + "," + near[2] + " (" + near[3] + " standing cast(s))");
                } else if (act == null) {
                    // the far block: no look holds for both eyes, so the hop waited a tick (the sneak aged) and the server
                    // cast from the crouched eye onto the planned block
                    c.check(far[0] >= 2 && far[3] == 0 && far[4] == 1 && Math.abs(far[1] - (X0 + 6.0)) < 0.1
                                    && Math.abs(far[2] - Z0) < 0.1,
                            "far block: want the use a tick late (>= 2), a crouched cast, landing at x " + (X0 + 6.0));
                    // the near block: a look holds for both eyes, so no wait, a re-aim, and the cast (standing) lands on it
                    c.check(near[0] == 1 && near[3] == 1 && near[4] == 1 && near[5] >= 1 && Math.abs(near[1] - (X0 + 2.0)) < 0.1
                                    && Math.abs(near[2] - Z0) < 0.1,
                            "near block: want the use on the first tick, a standing cast that lands on the block, a re-aim, at x "
                                    + (X0 + 2.0));
                }
            }
            if (st != null) {
                SameTick.judge(c, st, "Standing-eye hops", SameTick.Expect.ACTED);
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
            c.ctx().getInput().releaseKey(o -> o.keyShift);
            c.hx().call("dungeon.standingEye", "fresh", false);
            c.hx().call("dungeon.abilities", "enabled", false);
            c.server().command("kill @e[tag=sametick]");
            for (int s : new int[]{2, 4, 5}) {
                c.hx().give(s, "minecraft:air");
            }
        }
    }
}
