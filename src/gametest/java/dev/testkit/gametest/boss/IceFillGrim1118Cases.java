package dev.testkit.gametest.boss;

import dev.testkit.gametest.SameTick;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.WireCapture;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * SAME-TICK GRIM CASES for Auto Ice Fill's hops (icefillbugs-1118; killer560's 2026-11-18 Hypixel run). GrimAC's server has
 * no Ice Fill room, so the auto's own hop ({@code AutoIceFill.hopForTest}: the same rotated use under the free camera, the
 * same gate) is driven along six ice tiles the way the fixed auto paces it: the next hop the tick the last one has LANDED
 * (Delay 1), from the client tick's START like the auto. The server answers each hop with a teleport one block on, as
 * Hypixel's Aspect of the Void does there. His own action goes into the same ticks ({@link SameTick}): a right click, a
 * hotbar swap and a teleport accept; act {@code move} holds his forward and jump keys through the hops, and {@code again}
 * runs the lane back to back with no action. GrimAC must say nothing (default and experimental checks), at least 4 hops
 * must have gone, and the wire must carry his action and a hop in one server tick (USE: both went out).
 */
final class IceFillGrim1118Cases {

    static final String PREFIX = "1118-same-icefill-";
    private static final String AUTO = "autopuzzles.AutoIceFill";
    private static final int Y = 150;
    private static final int Z = -620;
    private static final int X0 = -620;
    private static final int TILES = 6;

    private static boolean hooked;
    private static volatile boolean driving;
    private static volatile int at;          // path point he stands on (0..TILES-1)
    private static volatile int sentTo = -1; // point the last hop was sent to, until the test thread answers it
    private static volatile int hops;
    private static volatile boolean landed = true;

    private IceFillGrim1118Cases() {
    }

    static void register(Session s) {
        if (!dev.testkit.gametest.mod.Mod.isCheat()) {
            return; // Auto Ice Fill is cheat build only
        }
        for (String act : new String[]{"use", "swap", "tp", "move", "again"}) {
            s.test(PREFIX + act, c -> run(c, act));
        }
    }

    private static Vec3 point(int i) {
        return new Vec3(X0 + i + 0.5, Y + 1.1, Z + 0.5);
    }

    private static void hook() {
        if (hooked) {
            return;
        }
        hooked = true;
        ClientTickEvents.START_CLIENT_TICK.register(mc -> {
            if (!driving || mc.player == null || !landed || at >= TILES - 1 || sentTo >= 0) {
                return;
            }
            if (mc.player.getInventory().getSelectedSlot() != 0) {
                return; // the auto waits for its Aspect of the Void in hand
            }
            Vec3 here = point(at);
            if (Math.abs(mc.player.getX() - here.x) > 0.01 || Math.abs(mc.player.getZ() - here.z) > 0.01) {
                return;
            }
            if ((Boolean) Mod.staticCall(AUTO, "hopForTest", mc, here, point(at + 1))) {
                hops++;
                landed = false;
                sentTo = at + 1;
            }
        });
    }

    private static void run(Session c, String act) throws Exception {
        SameTick st = null;
        SameTick.Act a = switch (act) {
            case "use" -> SameTick.Act.USE;
            case "swap" -> SameTick.Act.SWAP;
            case "tp" -> SameTick.Act.TP;
            default -> null;
        };
        try (var off = GrimTickCases.gateOff(c); var ip = GrimTickCases.hypixelIp(c)) {
            for (int i = 0; i < TILES; i++) {
                c.server().command("setblock " + (X0 + i) + " " + Y + " " + Z + " minecraft:ice");
            }
            c.server().command("item replace entity @a hotbar.0 with minecraft:diamond_shovel");
            c.server().command("item replace entity @a hotbar.1 with minecraft:stick");
            c.onClient(mc -> {
                hook();
                mc.player.getInventory().setSelectedSlot(0);
                return null;
            });
            int flags0 = c.scenario().flags().size();
            int laps = "again".equals(act) ? 3 : 2;
            WireCapture.start();
            if (a != null) {
                SameTick.Act aa = a;
                st = c.onClient(mc -> SameTick.start(aa, 1, p -> p instanceof ServerboundUseItemPacket && driving, 0, 1));
            }
            hops = 0;
            for (int lap = 0; lap < laps; lap++) {
                c.hx().call("dungeon.tp", "x", X0 + 0.5, "y", (double) Y + 1, "z", Z + 0.5, "yaw", -90f, "pitch", 30f);
                c.waitUntil("on the first tile", mc -> Math.abs(mc.player.getX() - (X0 + 0.5)) < 0.01
                        && Math.abs(mc.player.getZ() - (Z + 0.5)) < 0.01, 60);
                at = 0;
                sentTo = -1;
                landed = true;
                if ("move".equals(act)) {
                    c.onClient(mc -> {
                        mc.options.keyUp.setDown(true);
                        mc.options.keyJump.setDown(true);
                        return null;
                    });
                }
                driving = true;
                for (int k = 0; k < 120 && at < TILES - 1; k++) {
                    if (a == SameTick.Act.TP && k % 2 == 0) {
                        c.server().command("execute as @a at @s run tp @s ~ ~ ~");
                    }
                    int to = sentTo;
                    if (to >= 0) {
                        Vec3 p = point(to);
                        // Hypixel's answer to the hop: a teleport one block on (relative look, the camera untouched).
                        c.server().command(String.format(Locale.ROOT, "tp @a %.2f %.2f %.2f", p.x, (double) Y + 1, p.z));
                        int want = to;
                        c.waitUntil("hop " + want + " landed", mc -> Math.abs(mc.player.getX() - point(want).x) < 0.01, 40);
                        at = want;
                        sentTo = -1;
                        landed = true;
                    }
                    if ("move".equals(act) && k > 30) {
                        break; // walking and jumping he leaves the line; the hops he still got are the test
                    }
                    c.ctx().waitTick();
                }
                driving = false;
                c.onClient(mc -> {
                    mc.options.keyUp.setDown(false);
                    mc.options.keyJump.setDown(false);
                    mc.player.getInventory().setSelectedSlot(0);
                    return null;
                });
                c.ctx().waitTicks(5);
            }
            c.onClient(mc -> {
                Mod.staticCall(AUTO, "endHopsForTest", mc);
                return null;
            });
            c.ctx().waitTicks(5);
            WireCapture.stop();
            c.note(String.format(Locale.ROOT, "%s: %d hop(s) sent over %d lap(s)", act, hops, laps));
            c.check(hops >= ("move".equals(act) ? 1 : 4), "only " + hops + " hop(s) went - the feature did not act");
            if (st != null) {
                SameTick s2 = st;
                st = null;
                SameTick.judge(c, s2, "auto ice fill hop", a == SameTick.Act.USE ? SameTick.Expect.ACTED
                        : SameTick.Expect.SHARE);
            }
            c.check(c.scenario().flags().size() == flags0, "GrimAC flagged Auto Ice Fill's hops (" + act + ")");
        } finally {
            driving = false;
            WireCapture.stop();
            if (st != null) {
                SameTick s2 = st;
                c.onClient(mc -> {
                    s2.close();
                    return null;
                });
            }
            c.onClient(mc -> {
                Minecraft m = mc;
                m.options.keyUp.setDown(false);
                m.options.keyJump.setDown(false);
                return null;
            });
            for (int i = 0; i < TILES; i++) {
                c.server().command("setblock " + (X0 + i) + " " + Y + " " + Z + " minecraft:stone");
            }
            c.server().command("clear @p minecraft:diamond_shovel");
            c.server().command("clear @p minecraft:stick");
        }
    }
}
