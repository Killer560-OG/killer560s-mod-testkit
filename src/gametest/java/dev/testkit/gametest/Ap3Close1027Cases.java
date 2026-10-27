package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Session;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static dev.testkit.gametest.Ap3RuntimeTests.EXEC;
import static dev.testkit.gametest.Ap3RuntimeTests.FLOOR;
import static dev.testkit.gametest.Ap3RuntimeTests.LANE_X0;
import static dev.testkit.gametest.Ap3RuntimeTests.node;
import static dev.testkit.gametest.Ap3RuntimeTests.onClient;
import static dev.testkit.gametest.Ap3RuntimeTests.set;

/**
 * mod ap3-s2ee3-1027, the {@code close} suffix (killer560, 2026-10-27: "ap3 needs a close suffix to only go after a
 * terminal is closed"). In the lanes: a RUN carries him into a Stop with {@code close} and, on the same spot, a Walk with
 * {@code close} heading +z. A chest named "Click in order!" (a terminal's title, terminals/TerminalType) stands beside
 * him.
 * <ul>
 *   <li>1027-ap3-close-terminal: he opens the "terminal" (a real block click, looking at it), it stays open 30 ticks
 *       - the Walk must not go - and the tick after it closes the gate releases and he walks +z.</li>
 *   <li>1027-ap3-close-click: no terminal at all; a left click (the attack key) releases the gate, the Walk goes, and no
 *       swing or dig packet is sent for that click.</li>
 * </ul>
 */
final class Ap3Close1027Cases {

    private Ap3Close1027Cases() {
    }

    static void register(Session s) {
        s.test("1027-ap3-close-terminal", c -> run(c, true));
        s.test("1027-ap3-close-click", c -> run(c, false));
    }

    private static List<String> logSince(long mark, String needle) {
        List<String> out = new ArrayList<>();
        for (String l : LogTap.since(mark)) {
            if (l.contains(needle)) {
                out.add(l);
            }
        }
        return out;
    }

    private static void run(Session c, boolean terminal) {
        ClientGameTestContext ctx = c.ctx();
        double z = Ap3RuntimeTests.lane();
        double ax = LANE_X0 + 10.5;
        BlockPos chest = new BlockPos((int) Math.floor(ax), FLOOR + 1, (int) Math.floor(z) - 2);
        c.server().command(String.format(Locale.ROOT, "setblock %d %d %d minecraft:chest[facing=south]{CustomName:'\"Click in order!\"'}",
                chest.getX(), chest.getY(), chest.getZ()));
        Object run = Ap3RuntimeTests.runNode(z);
        Object stop = node("STOP", ax, z, -90f, 0f, 3.0, 3.0);
        set(stop, "closeGate", true);
        Object walk = node("WALK", ax, z, 0f, 0f, 3.0, 3.0);
        set(walk, "closeGate", true);
        long mark = LogTap.mark();
        java.util.concurrent.atomic.AtomicInteger swings = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean watching = new java.util.concurrent.atomic.AtomicBoolean();
        var old = dev.testkit.harness.PacketTrace.tap;
        dev.testkit.harness.PacketTrace.tap = p -> {
            if (old != null) {
                old.accept(p);
            }
            if (watching.get() && (p instanceof net.minecraft.network.protocol.game.ServerboundSwingPacket
                    || p instanceof net.minecraft.network.protocol.game.ServerboundPlayerActionPacket)) {
                swings.incrementAndGet();
            }
        };
        try {
            Ap3RuntimeTests.walkIntoRun(c, z, run, stop, walk);
            c.waitUntil("the close gate to hold", mc -> ModUnderTest.staticCall(EXEC, "leapGateNode") != null, 60);
            ctx.waitTicks(15);
            double z0 = onClient(ctx, mc -> mc.player.getZ());
            if (terminal) {
                // Look at the chest (server tp facing it - no movement), then open it with a real block click.
                c.server().command(String.format(Locale.ROOT, "tp @p ~ ~ ~ facing %.2f %.2f %.2f", chest.getX() + 0.5,
                        chest.getY() + 0.5, chest.getZ() + 0.5));
                c.server().sync(20);
                ctx.waitTicks(5);
                onClient(ctx, mc -> {
                    Vec3 eye = mc.player.getEyePosition();
                    var shape = mc.level.getBlockState(chest).getShape(mc.level, chest);
                    Vec3 centre = shape.bounds().getCenter().add(chest.getX(), chest.getY(), chest.getZ());
                    BlockHitResult hit = shape.clip(eye, eye.add(centre.subtract(eye).normalize().scale(6)), chest);
                    if (hit != null) {
                        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
                    }
                    return null;
                });
                c.waitUntil("the terminal screen to open", mc -> McCompat.screen(mc) != null, 40);
                String title = onClient(ctx, mc -> McCompat.screen(mc).getTitle().getString());
                c.note("screen open: '" + title + "'");
                ctx.waitTicks(30);
                Object[] mid = onClient(ctx, mc -> new Object[]{mc.player.getZ(), ModUnderTest.staticCall(EXEC, "leapGateNode") != null,
                        ModUnderTest.staticCall(EXEC, "isRunning")});
                c.note("30 ticks with the terminal open: z moved " + ((Double) mid[0] - z0) + ", gate holding " + mid[1]
                        + ", AP3 running " + mid[2]);
                c.check((Boolean) mid[1] && (Boolean) mid[2], "the gate let go (or AP3 stopped) with the terminal still open");
                c.check(Math.abs((Double) mid[0] - z0) < 1e-3, "he moved while the terminal was open");
                onClient(ctx, mc -> {
                    mc.player.closeContainer();
                    return null;
                });
            } else {
                watching.set(true);
                ctx.getInput().pressKey(o -> o.keyAttack);
            }
            int releasedAt = -1;
            double zEnd = z0;
            for (int t = 0; t < 40; t++) {
                ctx.waitTick();
                Object[] r = onClient(ctx, mc -> new Object[]{ModUnderTest.staticCall(EXEC, "leapGateNode") == null, mc.player.getZ()});
                if ((Boolean) r[0] && releasedAt < 0) {
                    releasedAt = t;
                }
                zEnd = (Double) r[1];
            }
            watching.set(false);
            for (String l : logSince(mark, "close gate")) {
                c.note("  " + l);
            }
            c.note(String.format(Locale.ROOT, "%s: gate released %d tick(s) after, walked %+.2f along the Walk, swing/dig packets %d",
                    terminal ? "terminal closed" : "left click", releasedAt, zEnd - z0, swings.get()));
            c.check(releasedAt >= 0 && releasedAt <= 2, "the gate did not release within 2 ticks (" + releasedAt + ")");
            c.check(zEnd - z0 > 2.0, String.format(Locale.ROOT, "the Walk did not go after the gate (%.2f)", zEnd - z0));
            if (!terminal) {
                c.check(swings.get() == 0, "the skip click reached the server: " + swings.get() + " swing/dig packet(s)");
                c.check(!logSince(mark, "close skipped by left click").isEmpty(), "no 'close skipped by left click' line");
            }
        } finally {
            watching.set(false);
            dev.testkit.harness.PacketTrace.tap = old;
            onClient(ctx, mc -> {
                if (McCompat.screen(mc) != null) {
                    mc.player.closeContainer();
                }
                Ap3RuntimeTests.staticCallStop();
                return null;
            });
            c.server().command(String.format(Locale.ROOT, "setblock %d %d %d minecraft:air", chest.getX(), chest.getY(), chest.getZ()));
        }
    }
}
