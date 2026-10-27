package dev.testkit.gametest;

import dev.testkit.gametest.hx.Session;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static dev.testkit.gametest.Ap3RuntimeTests.EXEC;
import static dev.testkit.gametest.Ap3RuntimeTests.FLOOR;
import static dev.testkit.gametest.Ap3RuntimeTests.LANE_X0;
import static dev.testkit.gametest.Ap3RuntimeTests.contains;
import static dev.testkit.gametest.Ap3RuntimeTests.node;
import static dev.testkit.gametest.Ap3RuntimeTests.onClient;
import static dev.testkit.gametest.Ap3RuntimeTests.set;
import static dev.testkit.gametest.Ap3RuntimeTests.setChain;

/**
 * mod ap3-stairs-1026 - killer560, 2026-10-26: "sometimes fastalign still goes off of stairs" and "if an align walks into
 * lava or something it lags my game to death". In the 63-ap3 session's lanes, cheat jar, GrimAC on. Every case arrives
 * at sprint (a RUN node, movement_speed 0.4 or 0.5) and judges against the align's STARTING feet height.
 * <ul>
 *   <li>1026-ap3-stairs-top-edge: a Fast Align on the high half of the top stair of a staircase (real stair blocks,
 *       each one half a block below the last), its point at the edge: never more than 0.5 below the start, lands.</li>
 *   <li>1026-ap3-stairs-side-drop: the point on a stair's low half beside its open side (3 blocks down): never below the
 *       low half, lands.</li>
 *   <li>1026-ap3-stairs-spiral: half-step slabs turning a corner down to the point, 1.5 below the start: he stays within
 *       0.5 and the align says it settled as close as is safe.</li>
 *   <li>1026-ap3-stairs-sprint: speed 0.5, the point 0.3 from the top of the staircase: lands, never below 0.5.</li>
 *   <li>1026-ap3-lava-beside: the point 0.4 from a lava pool: he never touches lava or burns, and lands near the point.</li>
 *   <li>1026-ap3-lava-inside: an align that starts with him standing in lava (fire resistance on): no client tick of the
 *       align takes 50 ms or more, and the align ends cleanly on its own (it says he is in lava).</li>
 * </ul>
 */
final class Ap3Stairs1026Cases {

    private Ap3Stairs1026Cases() {
    }

    static void register(Session s) {
        s.test("1026-ap3-stairs-top-edge", c -> stairs(c, Shape.TOP_EDGE));
        s.test("1026-ap3-stairs-side-drop", c -> stairs(c, Shape.SIDE_DROP));
        s.test("1026-ap3-stairs-spiral", c -> stairs(c, Shape.SPIRAL));
        s.test("1026-ap3-stairs-sprint", c -> stairs(c, Shape.SPRINT));
        s.test("1026-ap3-stairs-halfstep-off", c -> stairs(c, Shape.HALFSTEP_OFF));
        s.test("1026-ap3-lava-beside", Ap3Stairs1026Cases::lavaBeside);
        s.test("1026-ap3-lava-inside", Ap3Stairs1026Cases::lavaInside);
        s.test("1026-ap3-leapgate-click", Ap3Stairs1026Cases::leapGateClick);
    }

    // ------------------------------------------------------------------------------------------------- leap gate click

    /**
     * 1026-ap3-leapgate-click (killer560, 2026-10-26: "on a leapcounter node if i left click it should fire as well"): a
     * Stop with leapcounter:3 holds a Walk; with 0 leaps counted his real left click (the attack key, pressed through the
     * game's own input) opens the gate, the Walk runs after it, and no swing or dig packet goes out for that click.
     */
    private static void leapGateClick(Session c) {
        ClientGameTestContext ctx = c.ctx();
        double z = Ap3RuntimeTests.lane();
        double ax = LANE_X0 + 10.5;
        Object run = node("RUN", LANE_X0 + 3.0, z, -90f, 0f, 2.0, 2.0);
        Object stop = node("STOP", ax, z, -90f, 0f, 3.0, 3.0);
        set(stop, "leapCounter", 3);
        Object walk = node("WALK", ax, z, 0f, 0f, 3.0, 3.0);
        long mark = LogTap.mark();
        java.util.concurrent.atomic.AtomicInteger swings = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger digs = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean watching = new java.util.concurrent.atomic.AtomicBoolean();
        var old = dev.testkit.harness.PacketTrace.tap;
        dev.testkit.harness.PacketTrace.tap = p -> {
            if (old != null) {
                old.accept(p);
            }
            if (!watching.get()) {
                return;
            }
            if (p instanceof net.minecraft.network.protocol.game.ServerboundSwingPacket) {
                swings.incrementAndGet();
            } else if (p instanceof net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
                    || p instanceof net.minecraft.network.protocol.game.ServerboundInteractPacket) {
                digs.incrementAndGet();
            }
        };
        try {
            walkIn(c, z, FLOOR + 1, run, stop, walk);
            c.waitUntil("the leapcounter gate to hold", mc -> ModUnderTest.staticCall(EXEC, "leapGateNode") != null, 40);
            ctx.waitTicks(10);
            double z0 = onClient(ctx, mc -> mc.player.getZ());
            boolean held = onClient(ctx, mc -> ModUnderTest.staticCall(EXEC, "leapGateNode") != null);
            c.note("gate holding before the click: " + held + ", z " + z0);
            if (!held) {
                throw new AssertionError("the leapcounter:3 gate is not holding - nothing to click");
            }
            watching.set(true);
            ctx.getInput().pressKey(o -> o.keyAttack);
            boolean released = false;
            double zEnd = z0;
            for (int t = 0; t < 40; t++) {
                ctx.waitTick();
                Object[] r = onClient(ctx, mc -> new Object[]{ModUnderTest.staticCall(EXEC, "leapGateNode") == null, mc.player.getZ()});
                released |= (Boolean) r[0];
                zEnd = (Double) r[1];
            }
            watching.set(false);
            List<String> skip = logSince(mark, "leap counter skipped by left click");
            c.note(String.format(Locale.ROOT, "after the click: released %s, walked %+.2f along the Walk, swing packets %d,"
                    + " dig/interact packets %d, skip lines %d", released, zEnd - z0, swings.get(), digs.get(), skip.size()));
            for (String l : skip) {
                c.note("  " + l);
            }
            if (!released || skip.isEmpty()) {
                throw new AssertionError("the left click did not open the leap gate");
            }
            if (zEnd - z0 < 2.0) {
                throw new AssertionError(String.format(Locale.ROOT, "the Walk held behind it did not run (%.2f)", zEnd - z0));
            }
            if (swings.get() != 0 || digs.get() != 0) {
                throw new AssertionError("the click reached the server: " + swings.get() + " swing(s), " + digs.get()
                        + " dig/interact packet(s)");
            }
        } finally {
            watching.set(false);
            dev.testkit.harness.PacketTrace.tap = old;
            onClient(ctx, mc -> {
                Ap3RuntimeTests.staticCallStop();
                return null;
            });
        }
    }

    enum Shape { TOP_EDGE, SIDE_DROP, SPIRAL, SPRINT, HALFSTEP_OFF }

    // ------------------------------------------------------------------------------------------------- shared

    private static void cmd(Session c, String f, Object... a) {
        c.server().command(String.format(Locale.ROOT, f, a));
    }

    private static void speed(Session c, double v) {
        cmd(c, "attribute @p minecraft:movement_speed base set %.2f", v);
        c.server().sync(20);
    }

    private static String activeType() {
        Object n = dev.testkit.gametest.mod.Mod.field("ap3.Ap3Executor", "activeNode");
        return n == null ? "none" : String.valueOf(Ap3RuntimeTests.field(n, "type"));
    }

    private static boolean running() {
        return (Boolean) ModUnderTest.staticCall(EXEC, "isRunning");
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

    /** Ap3Fix1026Cases' walk-in at a standing height of {@code y}: stop, tp, set the chain, W into the RUN, let go. */
    private static void walkIn(Session c, double z, double y, Object run, Object... rest) {
        ClientGameTestContext ctx = c.ctx();
        onClient(ctx, mc -> {
            Ap3RuntimeTests.staticCallStop();
            return null;
        });
        c.server().command("kill @e[type=item]");
        cmd(c, "tp @p %.2f %.2f %.2f -90 0", LANE_X0 + 0.5, y, z);
        c.server().sync(20);
        ctx.waitTicks(15);
        Object[] all = new Object[rest.length + 1];
        all[0] = run;
        System.arraycopy(rest, 0, all, 1, rest.length);
        onClient(ctx, mc -> {
            setChain(all);
            return null;
        });
        ctx.waitTicks(2);
        ctx.getInput().holdKey(options -> options.keyUp);
        boolean inside = false;
        for (int i = 0; i < 60 && !inside; i++) {
            ctx.waitTick();
            inside = onClient(ctx, mc -> contains(run, mc.player.position()));
        }
        ctx.getInput().releaseKey(options -> options.keyUp);
        if (!inside) {
            throw new AssertionError("walking with W never reached the RUN node's box");
        }
        c.waitUntil("AP3 running the RUN node's held walk", mc -> running(), 10);
    }

    private record Watch(boolean alignSeen, double startY, double minY, double[] end, boolean lavaTouched, boolean burned,
                         int ticks, double maxX, String lastStop) {
    }

    /** Ticks until AP3 has stopped (after the align ran) and 20 more: the lowest feet, whether lava / fire was touched. */
    private static Watch watch(ClientGameTestContext ctx, int max) {
        boolean alignSeen = false;
        double startY = Double.NaN;
        double minY = Double.MAX_VALUE;
        boolean lava = false;
        boolean burn = false;
        int after = -1;
        double[] last = null;
        double maxX = -1e9;
        int t = 0;
        for (; t < max; t++) {
            ctx.waitTick();
            Object[] r = onClient(ctx, mc -> new Object[]{mc.player.getX(), mc.player.getY(), mc.player.getZ(), activeType(),
                    running(), mc.player.isInLava(), mc.player.isOnFire(), mc.player.onGround()});
            boolean inAlign = String.valueOf(r[3]).contains("ALIGN");
            if (inAlign && !alignSeen) {
                alignSeen = true;
            }
            if (alignSeen && Double.isNaN(startY) && (Boolean) r[7]) {
                startY = (Double) r[1];
            }
            if (alignSeen) {
                minY = Math.min(minY, (Double) r[1]);
            }
            lava |= (Boolean) r[5];
            burn |= (Boolean) r[6];
            last = new double[]{(Double) r[0], (Double) r[1], (Double) r[2]};
            maxX = Math.max(maxX, (Double) r[0]);
            if (alignSeen && !(Boolean) r[4] && after < 0) {
                after = t;
            }
            if (after >= 0 && t - after >= 20) {
                break;
            }
        }
        String stop = onClient(ctx, mc -> String.valueOf(ModUnderTest.staticCall(EXEC, "stopReason")));
        return new Watch(alignSeen, startY, minY, last, lava, burn, after < 0 ? -1 : t, maxX, stop);
    }

    // ------------------------------------------------------------------------------------------------- stairs

    private static void stairs(Session c, Shape shape) {
        ClientGameTestContext ctx = c.ctx();
        double z = Ap3RuntimeTests.lane();
        int zc = (int) Math.floor(z);
        int top = FLOOR + 3; // the platform's top block; he stands at FLOOR + 4
        double standY = top + 1;
        int lastX = LANE_X0 + 14;
        // The platform: x LANE_X0-1..14, one column wide at zc for SIDE_DROP and SPIRAL (open sides), else 3 wide.
        int half = shape == Shape.SIDE_DROP || shape == Shape.SPIRAL ? 0 : 1;
        cmd(c, "fill %d %d %d %d %d %d minecraft:stone", LANE_X0 - 1, FLOOR + 1, zc - half, lastX, top, zc + half);
        double tx;
        double tz = z;
        boolean expectSettle = false;
        switch (shape) {
            case TOP_EDGE, SPRINT -> {
                // A staircase going down east: each stair (facing west: its high half on the west, top flush with the step
                // above; its low half on the east, half a block lower) one block below the last.
                for (int i = 0; i < 4; i++) {
                    int x = lastX + 1 + i;
                    int y = top - i;
                    if (y > FLOOR + 1) {
                        cmd(c, "fill %d %d %d %d %d %d minecraft:stone", x, FLOOR + 1, zc - half, x, y - 1, zc + half);
                    }
                    cmd(c, "fill %d %d %d %d %d %d minecraft:stone_brick_stairs[facing=west,half=bottom]", x, y, zc - half,
                            x, y, zc + half);
                }
                // TOP_EDGE: the point on the top stair's high half, at its edge (x 15.0-15.5 is the high half).
                tx = shape == Shape.TOP_EDGE ? lastX + 1 + 0.2 : lastX + 1 - 0.3;
            }
            case HALFSTEP_OFF -> {
                // His 23:15:14 shape: past the top a single stair whose low half (half a block wide, 0.5 down) is the
                // last thing before a 3-block drop. The point on its high half; he comes in at sprint.
                cmd(c, "fill %d %d %d %d %d %d minecraft:stone", lastX + 1, FLOOR + 1, zc - half, lastX + 1, top - 1, zc + half);
                cmd(c, "fill %d %d %d %d %d %d minecraft:stone_brick_stairs[facing=east,half=bottom]", lastX + 1, top,
                        zc - half, lastX + 1, top, zc + half);
                tx = lastX + 1 + 0.2;
            }
            case SIDE_DROP -> {
                // One stair at the end of the one-wide path, its low half 0.5 down; the point beside its open side.
                // A half step (bottom slab, top 0.5 down) at the end of the one-wide path; the point on it, its box at the
                // slab's open side (3 blocks down beyond z zc+1).
                cmd(c, "setblock %d %d %d minecraft:stone_slab[type=bottom]", lastX + 1, top, zc);
                cmd(c, "fill %d %d %d %d %d %d minecraft:stone", lastX + 1, FLOOR + 1, zc, lastX + 1, top - 1, zc);
                tx = lastX + 1 + 0.6;
                tz = zc + 0.7;
            }
            default -> {
                // SPIRAL: half-step slabs turning a corner, each 0.5 below the last: (15,zc) 0.5, (15,zc+1) 1.0,
                // (14,zc+1) 1.5 below the start. The point is on the last one: out of bounds by the 0.5 rule.
                int[][] steps = {{lastX + 1, zc, 1}, {lastX + 1, zc + 1, 2}, {lastX, zc + 1, 3}};
                for (int[] st : steps) {
                    double topY = standY - st[2] * 0.5;
                    int full = (int) Math.floor(topY) - 1; // highest full block
                    if (full >= FLOOR + 1) {
                        cmd(c, "fill %d %d %d %d %d %d minecraft:stone", st[0], FLOOR + 1, st[1], st[0], full, st[1]);
                    }
                    if (topY != Math.floor(topY)) {
                        cmd(c, "setblock %d %d %d minecraft:stone_slab[type=bottom]", st[0], full + 1, st[1]);
                    }
                }
                tx = lastX + 0.5;
                tz = zc + 1.5;
                expectSettle = true;
            }
        }
        speed(c, shape == Shape.SPRINT || shape == Shape.HALFSTEP_OFF ? 0.5 : 0.4);
        long mark = LogTap.mark();
        try {
            Object run = node("RUN", LANE_X0 + 3.0, z, -90f, 0f, 2.0, 2.0);
            set(run, "y", standY);
            Object align = node("FAST_ALIGN", tx, tz, 0f, 0f, 3.0, 3.0);
            // The node sits where its point stands: the slab's top for the side drop, the start height elsewhere.
            set(align, "y", shape == Shape.SIDE_DROP ? standY - 0.5 : standY);
            ctx.waitTicks(5);
            walkIn(c, z, standY, run, align);
            Watch w = watch(ctx, 220);
            List<String> guard = logSince(mark, "[ledge guard]");
            List<String> guardLines = logSince(mark, "more than 0.5 below where the align started");
            List<String> settled = logSince(mark, "settled as close as is safe");
            String reason = onClient(ctx, mc -> String.valueOf(ModUnderTest.staticCall(EXEC, "stopReason")));
            c.note(String.format(Locale.ROOT, "%s: point (%.2f, %.2f), started at y %.3f, lowest %.3f, ended (%.4f, %.3f, %.4f),"
                            + " guard lines %d, settled %d, stop '%s'", shape, tx, tz, w.startY(), w.minY(), w.end()[0], w.end()[1],
                    w.end()[2], guardLines.size(), settled.size(), reason));
            for (String l : guardLines) {
                c.note("  " + l);
            }
            for (String l : settled) {
                c.note("  " + l);
            }
            if (!w.alignSeen()) {
                throw new AssertionError(String.format(Locale.ROOT, "the Fast Align never ran - nothing was tested (got to x"
                        + " %.2f, ended (%.2f, %.2f, %.2f), stop '%s')", w.maxX(), w.end()[0], w.end()[1], w.end()[2], w.lastStop()));
            }
            if (w.ticks() < 0) {
                throw new AssertionError("AP3 was still running at the end of the watch");
            }
            double start = Double.isNaN(w.startY()) ? standY : w.startY();
            if (w.minY() < start - 0.5 - 1e-6) {
                throw new AssertionError(String.format(Locale.ROOT, "the align took him %.3f below where it started (limit 0.5)",
                        start - w.minY()));
            }
            if (expectSettle) {
                if (settled.isEmpty()) {
                    throw new AssertionError("the point is out of bounds but the align did not settle as close as is safe");
                }
            } else if (Math.abs(w.end()[0] - tx) > 1e-3 || Math.abs(w.end()[2] - tz) > 1e-3) {
                throw new AssertionError(String.format(Locale.ROOT, "the align ended %.4f / %.4f off its point",
                        w.end()[0] - tx, w.end()[2] - tz));
            }
        } finally {
            onClient(ctx, mc -> {
                Ap3RuntimeTests.staticCallStop();
                return null;
            });
            speed(c, 0.1);
            cmd(c, "fill %d %d %d %d %d %d minecraft:air", LANE_X0 - 1, FLOOR + 1, zc - 2, lastX + 6, FLOOR + 6, zc + 3);
        }
    }

    // ------------------------------------------------------------------------------------------------- lava

    private static void lavaBeside(Session c) {
        ClientGameTestContext ctx = c.ctx();
        double z = Ap3RuntimeTests.lane();
        int zc = (int) Math.floor(z);
        int px0 = LANE_X0 + 13;
        // The pool sits in the floor layer, x 13-15, three wide.
        cmd(c, "fill %d %d %d %d %d %d minecraft:lava", px0, FLOOR, zc - 1, px0 + 2, FLOOR, zc + 1);
        double tx = px0 - 0.4;
        speed(c, 0.4);
        long mark = LogTap.mark();
        try {
            Object run = node("RUN", LANE_X0 + 3.0, z, -90f, 0f, 2.0, 2.0);
            Object align = node("FAST_ALIGN", tx, z, 0f, 0f, 3.0, 3.0);
            ctx.waitTicks(5);
            walkIn(c, z, FLOOR + 1, run, align);
            Watch w = watch(ctx, 220);
            c.note(String.format(Locale.ROOT, "lava beside: point x %.2f (pool from x %d), ended (%.4f, %.3f, %.4f), touched lava %s,"
                    + " burned %s, guard lines %d", tx, px0, w.end()[0], w.end()[1], w.end()[2], w.lavaTouched(), w.burned(),
                    logSince(mark, "into a hazard").size()));
            if (!w.alignSeen()) {
                throw new AssertionError("the Fast Align never ran - nothing was tested");
            }
            if (w.lavaTouched() || w.burned()) {
                throw new AssertionError("the align took him into the lava (in lava " + w.lavaTouched() + ", on fire " + w.burned() + ")");
            }
            if (Math.abs(w.end()[0] - tx) > 0.05 || Math.abs(w.end()[2] - z) > 0.05) {
                throw new AssertionError(String.format(Locale.ROOT, "he ended %.3f / %.3f from the point", w.end()[0] - tx,
                        w.end()[2] - z));
            }
        } finally {
            onClient(ctx, mc -> {
                Ap3RuntimeTests.staticCallStop();
                return null;
            });
            speed(c, 0.1);
            cmd(c, "fill %d %d %d %d %d %d minecraft:smooth_stone", px0, FLOOR, zc - 1, px0 + 2, FLOOR, zc + 1);
            cmd(c, "fill %d %d %d %d %d %d minecraft:air", px0 - 1, FLOOR + 1, zc - 2, px0 + 3, FLOOR + 3, zc + 2);
        }
    }

    // Per-tick wall time from START_CLIENT_TICK to END_CLIENT_TICK (registered after the mod's own END listeners, so the
    // AP3 tick is inside it).
    private static boolean timerInstalled;
    private static volatile boolean timing;
    private static long tickStart;
    private static final List<Double> TICK_MS = java.util.Collections.synchronizedList(new ArrayList<>());

    private static void installTimer() {
        if (timerInstalled) {
            return;
        }
        timerInstalled = true;
        ClientTickEvents.START_CLIENT_TICK.register(mc -> tickStart = System.nanoTime());
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (timing) {
                TICK_MS.add((System.nanoTime() - tickStart) / 1e6);
            }
        });
    }

    private static void lavaInside(Session c) {
        ClientGameTestContext ctx = c.ctx();
        double z = Ap3RuntimeTests.lane();
        int zc = (int) Math.floor(z);
        int px0 = LANE_X0 + 8;
        // A pool two deep, x 8-12, on a stone bottom: he stands on the bottom, in it.
        cmd(c, "fill %d %d %d %d %d %d minecraft:stone", px0 - 1, FLOOR - 2, zc - 3, px0 + 5, FLOOR - 2, zc + 3);
        cmd(c, "fill %d %d %d %d %d %d minecraft:lava", px0, FLOOR - 1, zc - 2, px0 + 4, FLOOR, zc + 2);
        cmd(c, "effect give @p minecraft:fire_resistance 120 0 true");
        onClient(ctx, mc -> {
            installTimer();
            return null;
        });
        long mark = LogTap.mark();
        try {
            onClient(ctx, mc -> {
                Ap3RuntimeTests.staticCallStop();
                return null;
            });
            cmd(c, "tp @p %.2f %d %.2f -90 0", px0 + 1.5, FLOOR - 1, z);
            c.server().sync(20);
            ctx.waitTicks(20);
            boolean inLava = onClient(ctx, mc -> mc.player.isInLava());
            c.note("standing in lava before the align: " + inLava);
            if (!inLava) {
                throw new AssertionError("he is not in the lava - nothing would be tested");
            }
            Object align = node("FAST_ALIGN", px0 + 3.0, z, 0f, 0f, 6.0, 6.0);
            set(align, "y", (double) (FLOOR - 1));
            TICK_MS.clear();
            timing = true;
            onClient(ctx, mc -> {
                setChain(align);
                return null;
            });
            boolean seen = false;
            int t = 0;
            for (; t < 160; t++) {
                ctx.waitTick();
                Object[] r = onClient(ctx, mc -> new Object[]{activeType(), running()});
                seen |= String.valueOf(r[0]).contains("ALIGN");
                if (seen && !(Boolean) r[1] && t > 5) {
                    break;
                }
            }
            ctx.waitTicks(10);
            timing = false;
            double max = 0;
            double sum = 0;
            int over = 0;
            synchronized (TICK_MS) {
                for (double ms : TICK_MS) {
                    max = Math.max(max, ms);
                    sum += ms;
                    over += ms >= 50 ? 1 : 0;
                }
            }
            List<String> stopped = logSince(mark, "you are in lava - the align stops here");
            seen |= !stopped.isEmpty();
            String reason = onClient(ctx, mc -> String.valueOf(ModUnderTest.staticCall(EXEC, "stopReason")));
            c.note(String.format(Locale.ROOT, "lava inside: align seen %s, %d ticks timed, max %.1f ms, mean %.1f ms, %d at 50 ms or"
                            + " more; 'in lava' lines %d; stop '%s'; AP3 running at the end %s", seen, TICK_MS.size(), max,
                    TICK_MS.isEmpty() ? 0 : sum / TICK_MS.size(), over, stopped.size(), reason,
                    onClient(ctx, mc -> running())));
            for (String l : stopped) {
                c.note("  " + l);
            }
            if (!seen) {
                throw new AssertionError("the align never ran - nothing was tested");
            }
            if (over > 0) {
                throw new AssertionError(String.format(Locale.ROOT, "%d client tick(s) took 50 ms or more during the align (max %.1f)",
                        over, max));
            }
            if (onClient(ctx, mc -> running())) {
                throw new AssertionError("the align was still going after 160 ticks in lava");
            }
            if (stopped.size() != 1) {
                throw new AssertionError("expected the align to say once that he is in lava, saw " + stopped.size());
            }
        } finally {
            timing = false;
            onClient(ctx, mc -> {
                Ap3RuntimeTests.staticCallStop();
                return null;
            });
            cmd(c, "fill %d %d %d %d %d %d minecraft:air", px0 - 1, FLOOR - 2, zc - 3, px0 + 5, FLOOR - 1, zc + 3);
            cmd(c, "fill %d %d %d %d %d %d minecraft:smooth_stone", px0, FLOOR, zc - 2, px0 + 4, FLOOR, zc + 2);
            cmd(c, "effect clear @p");
            cmd(c, "tp @p %.2f %d %.2f -90 0", LANE_X0 + 0.5, FLOOR + 1, z);
        }
    }
}
