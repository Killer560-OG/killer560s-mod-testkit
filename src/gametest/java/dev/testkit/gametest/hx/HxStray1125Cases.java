package dev.testkit.gametest.hx;

import dev.testkit.gametest.SameTick;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.Latency;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Two Interactive Map / Autopilot warp defects from his 26.3 log of 2026-11-25 (autopilot-warp-stray-1125), on the hx
 * server's etherwarp with the map's own executor ({@code ClearExecutor.clearPath} of {@code ClearNode.EtherNode}s).
 * <ul>
 *   <li>1125-hx-stray-landing (15:39:11): queue A's two warps are in flight (250 ms more round trip, tick gap 3) when
 *       warp 1 lands off its aim (cast standing): one correction, A is dropped and queue B starts from there at once.
 *       A's warp 2 still lands after B started; it is A's own late landing, not a second correction (the old jar
 *       reported one and planned again). Run twice: B's first warp held until the stray landed, and sent at once.
 *       Then a real correction with no landing of a dropped queue due must still count.</li>
 *   <li>1125-hx-airborne-start (15:40:19): a one-warp queue planned in mid-air (falling) from where he is: the use goes a
 *       tick later from lower down. It must still land on the planned block (the old jar sent the look planned from the
 *       higher spot: "warp 1 never landed" there, a block or more short here).</li>
 *   <li>1125-hx-airborne-start-same-&lt;idle|use|swap|tp&gt;: the airborne hop with his own action in the same ticks; GrimAC
 *       (default and experimental) must stay silent and both reach the wire.</li>
 * </ul>
 */
final class HxStray1125Cases {

    private static final String CE = "livemap.autoclear.ClearExecutor";
    private static final String AOTV = "minecraft:diamond_shovel[custom_data={id:\"ASPECT_OF_THE_VOID\",ethermerge:1,"
            + "tuned_transmission:4},unbreakable={}]";
    /** The floor's top (blocks at y 150) and the area: x -470..-430, z -440..-400. */
    private static final double Y = 151;
    private static final double XA = -465.5;
    private static final double ZA = -420.5;

    private HxStray1125Cases() {
    }

    static void register(Session s) {
        s.test("1125-hx-stray-landing", HxStray1125Cases::stray);
        s.test("1125-hx-airborne-start", c -> airborne(c, null));
        s.test("1125-hx-airborne-start-same-idle", c -> airborne(c, "idle"));
        s.test("1125-hx-airborne-start-same-use", c -> airborne(c, "use"));
        s.test("1125-hx-airborne-start-same-swap", c -> airborne(c, "swap"));
        s.test("1125-hx-airborne-start-same-tp", c -> airborne(c, "tp"));
    }

    private static int count(Session c, String getter) {
        return c.onClient(mc -> {
            try {
                return ((Number) Mod.staticCall(CE, getter)).intValue();
            } catch (RuntimeException | AssertionError e) {
                return 0;   // a jar from before the counter (the base jar)
            }
        });
    }

    private static int warps(Session c) {
        return c.hx().call("dungeon.stats").getAsJsonObject().get("etherwarps").getAsInt();
    }

    private static int sends(Session c) {
        return c.onClient(mc -> ((List<?>) Mod.staticCall(CE, "hopSendTicks")).size());
    }

    /** The map's etherwarp nodes from {@code from}, one per block (each lands on the block's top + 1.05), client thread. */
    private static List<Object> nodes(Vec3 from, BlockPos... blocks) {
        try {
            Class<?> ether = Mod.cls("livemap.autoclear.ClearNode$EtherNode");
            Class<?> tu = Mod.cls("livemap.autoclear.TeleportUtils");
            double eyeH = (Double) tu.getMethod("eyeHeight", boolean.class).invoke(null, true);
            java.lang.reflect.Method dir = tu.getMethod("getEtherwarpDirection", Vec3.class, BlockPos.class, double.class);
            List<Object> out = new ArrayList<>();
            Vec3 at = from;
            for (BlockPos b : blocks) {
                Object rot = dir.invoke(null, new Vec3(at.x, at.y + eyeH, at.z), b, 62.0);
                if (rot == null) {
                    throw new AssertionError("no etherwarp look from " + at + " onto " + b);
                }
                float yaw = (Float) rot.getClass().getMethod("yaw").invoke(rot);
                float pitch = (Float) rot.getClass().getMethod("pitch").invoke(rot);
                out.add(ether.getConstructor(Vec3.class, float.class, float.class, int.class, List.class)
                        .newInstance(at, yaw, pitch, 0xFFA500, List.of()));
                at = new Vec3(b.getX() + 0.5, b.getY() + 1.05, b.getZ() + 0.5);
            }
            return out;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("EtherNode: " + e, e);
        }
    }

    /** Runs {@code nodes} through the map's executor as another feature's list (kept alive with Interactive Map off). */
    private static void run(Minecraft mc, List<Object> nodes) {
        Mod.staticCall(CE, "clearPath", nodes, (Runnable) () -> { });
        Mod.staticCall(CE, "setExternalOwner", true);
    }

    private static BlockPos floor(double x, double z) {
        return BlockPos.containing(x, Y - 1, z);
    }

    private static void area(Session c) {
        c.server().command("fill -470 150 -440 -430 150 -400 smooth_stone");
        c.server().command("fill -470 151 -440 -430 156 -400 air");
        c.server().command("fill -470 157 -440 -430 163 -400 air");
        c.server().sync(10);
        c.hx().give(2, AOTV);
        c.hx().give(4, AOTV);
        c.hx().give(5, AOTV);
        c.hx().call("dungeon.abilities", "enabled", true);
        c.server().command("kill @e[tag=sametick]");
    }

    private static void standAt(Session c, double x, double y, double z) {
        c.server().command(String.format(Locale.ROOT, "tp @p %.2f %.2f %.2f -90 0", x, y, z));
        c.onClient(mc -> {
            mc.player.getInventory().setSelectedSlot(2);
            return null;
        });
        c.ctx().getInput().holdKey(o -> o.keyShift);
        // Wait for the teleport to reach the client (a slow PC under added latency took more than 8 ticks).
        for (int i = 0; i < 80 && c.onClient(mc -> mc.player.position().distanceTo(new Vec3(x, y, z)) > 0.5); i++) {
            c.ctx().waitTick();
        }
        c.ctx().waitTicks(6);
    }

    private static void cleanup(Session c) {
        c.onClient(mc -> {
            try {
                Mod.staticCall(CE, "cancel");
            } catch (RuntimeException | AssertionError ignored) {
                // nothing running
            }
            Latency.remove();
            return null;
        });
        c.ctx().getInput().releaseKey(o -> o.keyShift);
        c.hx().call("dungeon.standingEye", "warps", 0);
        c.hx().call("dungeon.abilities", "enabled", false);
        c.server().command("kill @e[tag=sametick]");
        for (int s : new int[]{2, 4, 5}) {
            c.hx().give(s, "minecraft:air");
        }
        c.server().command("fill -470 150 -440 -430 163 -400 air");
    }

    /**
     * One round from (XA, z): queue A's warp 1 is cast standing (lands ~2 blocks past its aim: a correction) while warp 2
     * is already sent (Ticks Between Etherwarps 3, 250 ms more round trip; A has only two warps, so exactly one lands late). The next queue B (one warp
     * 8 blocks south) starts the moment the correction is seen; warp 2's landing arrives after it. {@code held}: B is held
     * until that landing came (its first warp not sent yet: B must carry on from there and arrive on its block); else B's
     * warp went first (it was cast from the wrong spot: B is dropped quietly). Either way: one correction, one late landing.
     */
    private static void strayRound(Session c, double z, boolean held) {
        standAt(c, XA, Y, z);
        int corr0 = count(c, "serverCorrections");
        int stray0 = count(c, "strayLandings");
        int warps0 = warps(c);
        c.hx().call("dungeon.standingEye", "warps", 1);
        c.onClient(mc -> {
            run(mc, nodes(mc.player.position(), floor(XA + 8, z), floor(XA + 16, z)));
            return null;
        });
        int waited = 0;
        while (waited++ < 80 && count(c, "serverCorrections") == corr0) {
            c.ctx().waitTick();
        }
        c.check(count(c, "serverCorrections") == corr0 + 1, "warp 1 never drew its correction (" + waited + " ticks)");
        Vec3 b0 = c.onClient(mc -> {
            Vec3 at = mc.player.position();
            run(mc, nodes(at, floor(at.x, at.z - 8)));
            if (held) {
                Mod.staticCall(CE, "setHold", true);
            }
            return at;
        });
        BlockPos bBlock = floor(b0.x, b0.z - 8);
        if (held) {
            for (int i = 0; i < 40 && count(c, "strayLandings") == stray0; i++) {
                c.ctx().waitTick();
            }
            c.onClient(mc -> {
                Mod.staticCall(CE, "setHold", false);
                return null;
            });
        }
        c.ctx().waitTicks(40);
        int corr = count(c, "serverCorrections") - corr0;
        int strays = count(c, "strayLandings") - stray0;
        int warps = warps(c) - warps0;
        Vec3 end = c.onClient(mc -> mc.player.position());
        c.note(String.format(Locale.ROOT, "%s: queue B from %.2f,%.2f,%.2f to %s; server warps %d, corrections %d, late landings "
                        + "of the dropped queue %d; ended %.2f,%.2f,%.2f", held ? "B held" : "B at once", b0.x, b0.y, b0.z,
                bBlock.toShortString(), warps, corr, strays, end.x, end.y, end.z));
        c.check(Math.abs(b0.x - (XA + 8)) > 0.6, "warp 1 landed on its aim: no correction to test");
        c.check(warps >= 2, "want A's two warps cast by the server, got " + warps);
        c.check(corr == 1, "want 1 correction (warp 1) - the dropped queue's late landing was read as a correction: " + corr);
        c.check(strays == 1, "want the dropped queue's late landing recognized once, got " + strays);
        if (held) {
            c.check(Math.abs(end.x - (bBlock.getX() + 0.5)) < 0.1 && Math.abs(end.z - (bBlock.getZ() + 0.5)) < 0.1,
                    "queue B did not carry on from the late landing onto its block " + bBlock.toShortString());
        }
    }

    private static void stray(Session c) {
        if (!Mod.isCheat()) {
            c.note("legit jar: the map's warps are cheat-only");
            return;
        }
        try (AutoCloseable gap = Mod.with("livemap.LiveMapConfig", "EtherwarpTickGap", 3)) {
            area(c);
            c.onClient(mc -> {
                Latency.install(250);
                return null;
            });
            c.ctx().waitTicks(20);
            strayRound(c, ZA, true);
            c.ctx().waitTicks(60);
            strayRound(c, ZA + 12, false);
            // a real correction, with no landing of a dropped queue still due, is still one
            c.ctx().waitTicks(60);
            int corr1 = count(c, "serverCorrections");
            c.hx().call("dungeon.standingEye", "warps", 1);
            c.onClient(mc -> {
                Vec3 at = mc.player.position();
                run(mc, nodes(at, floor(at.x + 8, at.z)));
                return null;
            });
            c.ctx().waitTicks(30);
            int real = count(c, "serverCorrections") - corr1;
            c.note("real correction afterwards: " + real);
            c.check(real == 1, "a real correction (no dropped queue's landing due) was not counted: " + real);
        } catch (Exception e) {
            throw new AssertionError("tick gap restore: " + e, e);
        } finally {
            cleanup(c);
        }
    }

    private static boolean feature(Packet<?> p) {
        return p instanceof ServerboundUseItemPacket || p instanceof ServerboundPlayerInputPacket in && in.input().shift();
    }

    /** One hop planned in mid-air: {land x, land z, sent airborne 1/0, the client's y at the plan, y when sent, warps}. */
    private static double[] airHop(Session c, double d) {
        standAt(c, XA, Y, ZA);
        int warps0 = warps(c);
        int sends0 = sends(c);
        c.server().command(String.format(Locale.ROOT, "tp @p %.2f %.2f %.2f -90 0", XA, Y + 12, ZA));
        c.ctx().waitTicks(10);   // falling ~0.7 a tick by now
        BlockPos block = floor(XA + d, ZA);
        double[] plan = c.onClient(mc -> {
            Vec3 at = mc.player.position();
            run(mc, nodes(at, block));
            return new double[]{at.y, mc.player.onGround() ? 1 : 0};
        });
        double airborne = -1;
        double sentY = 0;
        for (int i = 0; i < 20 && airborne < 0; i++) {
            c.ctx().waitTick();
            if (sends(c) > sends0) {
                airborne = c.onClient(mc -> mc.player.onGround() ? 0.0 : 1.0);
                sentY = c.onClient(mc -> mc.player.position().y);
            }
        }
        c.ctx().waitTicks(30);
        Vec3 end = c.onClient(mc -> mc.player.position());
        return new double[]{end.x, end.z, airborne, plan[0], sentY, warps(c) - warps0, plan[1]};
    }

    private static void airborne(Session c, String act) {
        if (!Mod.isCheat()) {
            c.note("legit jar: the map's warps are cheat-only");
            return;
        }
        SameTick.Act a = act == null ? null : switch (act) {
            case "use" -> SameTick.Act.USE;
            case "swap" -> SameTick.Act.SWAP;
            case "tp" -> SameTick.Act.TP;
            default -> null;
        };
        SameTick st = null;
        try {
            area(c);
            c.ctx().waitTicks(10);
            if (a != null) {
                st = c.onClient(mc -> SameTick.start(a, 2, HxStray1125Cases::feature, 4, 5));
            }
            int reaims0 = count(c, "firstHopReaims");
            int cycles = act == null ? 2 : 4;
            for (int i = 0; i < cycles; i++) {
                double d = i % 2 == 0 ? 16.0 : 12.0;
                double[] r = airHop(c, d);
                c.note(String.format(Locale.ROOT, "%s cycle %d: planned at y %.2f (on ground %.0f), use sent airborne %.0f at "
                        + "y %.2f, %.0f server warp(s), landed %.2f,%.2f (block center x %.2f)", act == null ? "aim" : act, i,
                        r[3], r[6], r[2], r[4], r[5], r[0], r[1], floor(XA + d, ZA).getX() + 0.5));
                c.check(r[2] >= 0, "the hop never went out");
                c.check(r[6] == 0, "the plan was not made in mid-air");
                if (act == null) {
                    c.check(r[2] == 1, "the use did not go while he was airborne");
                    c.check(r[5] == 1 && Math.abs(r[0] - (floor(XA + d, ZA).getX() + 0.5)) < 0.1 && Math.abs(r[1] - ZA) < 0.1,
                            "the mid-air hop did not land on its planned block: " + r[5] + " warp(s), at " + r[0] + "," + r[1]);
                }
            }
            int reaims = count(c, "firstHopReaims") - reaims0;
            c.note("first hops aimed again from where the server had him: " + reaims);
            if (act == null) {
                c.check(reaims >= cycles, "the first hop was not aimed again from where the server had him (" + reaims + ")");
            }
            if (st != null) {
                SameTick.judge(c, st, "Mid-air first hops", SameTick.Expect.ACTED);
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
            cleanup(c);
        }
    }
}
