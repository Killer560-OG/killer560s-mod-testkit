package dev.testkit.gametest;

import dev.testkit.gametest.hx.Session;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static dev.testkit.gametest.Ap3RuntimeTests.EXEC;
import static dev.testkit.gametest.Ap3RuntimeTests.FEATURE;
import static dev.testkit.gametest.Ap3RuntimeTests.FLOOR;
import static dev.testkit.gametest.Ap3RuntimeTests.LANE_X0;
import static dev.testkit.gametest.Ap3RuntimeTests.contains;
import static dev.testkit.gametest.Ap3RuntimeTests.node;
import static dev.testkit.gametest.Ap3RuntimeTests.onClient;
import static dev.testkit.gametest.Ap3RuntimeTests.set;
import static dev.testkit.gametest.Ap3RuntimeTests.setChain;

/**
 * mod ap3-backwards-1026 - killer560's 2026-10-26 AP3 reports, in the 63-ap3 session's lanes (cheat jar, GrimAC on;
 * Session.test fails any flag):
 * <ul>
 *   <li>1026-ap3-ledge-1 / -3: a Fast Align on a 1- and a 3-block ledge, 0.35 from its edge, entered at sprint speed
 *       (movement_speed 0.4): he never ends more than 0.5 below where he stood, and the align lands within 0.001.</li>
 *   <li>1026-ap3-ledge-past-3: the same with the align's point 0.6 PAST the edge of the 3-block ledge, which no align can
 *       reach without the drop: he must stay on the ledge, the guard must have acted, and AP3 must say it "settled as
 *       close as is safe" - on a jar without the guard he walks off.</li>
 *   <li>1026-ap3-boom-sprint-through: a 1-block Boom box crossed at sprint, its node made 0.7 to the side of his line so
 *       the node's own angle from HIS eye meets nothing (his "boom #31 isn't looking at a block"): the Superboom tap must
 *       reach the wire once, and the movement packet before it must carry a look that meets the node's block.</li>
 *   <li>1026-ap3-boom-node-behind: a LOOK node overlapping the Boom's box right behind it: the boom's look must not be
 *       taken - the tap goes out on the boom's look, and the LOOK turns him only after.</li>
 *   <li>1026-ap3-boom-in-align: a Boom on an Align's point: the align lands first, then the tap, on target.</li>
 *   <li>1026-ap3-leapgate: a Stop with leapcounter:2, an Align and a Walk on the same spot: while the count is short the
 *       align still runs (he lands on its point) and the Walk does not fire; two fake teammates teleport to him; then
 *       the walk goes.</li>
 * </ul>
 */
final class Ap3Fix1026Cases {

    private Ap3Fix1026Cases() {
    }

    /** Ap3Executor.leapGateNode(), on the client thread. */
    private static final class ModUnderTestHolder {
        static Object gate() {
            return dev.testkit.gametest.ModUnderTest.staticCall(EXEC, "leapGateNode");
        }
    }

    static void register(Session s) {
        s.test("1026-ap3-ledge-1", c -> ledge(c, 1, false));
        s.test("1026-ap3-ledge-3", c -> ledge(c, 3, false));
        s.test("1026-ap3-ledge-past-3", c -> ledge(c, 3, true));
        s.test("1026-ap3-boom-sprint-through", Ap3Fix1026Cases::boomSprintThrough);
        s.test("1026-ap3-boom-node-behind", Ap3Fix1026Cases::boomNodeBehind);
        s.test("1026-ap3-boom-in-align", Ap3Fix1026Cases::boomInAlign);
        s.test("1026-ap3-leapgate", Ap3Fix1026Cases::leapGate);
    }

    // ------------------------------------------------------------------------------------------------- shared

    private static void speed(Session c, double v) {
        c.server().command(String.format(Locale.ROOT, "attribute @p minecraft:movement_speed base set %.2f", v));
        c.server().sync(20);
    }

    /** {@link Ap3RuntimeTests#walkIntoRun} at a standing height of {@code y}. */
    private static void walkIntoRunAt(Session c, double z, double y, Object run, Object... rest) {
        ClientGameTestContext ctx = c.ctx();
        onClient(ctx, mc -> {
            Ap3RuntimeTests.staticCallStop();
            return null;
        });
        c.server().command("kill @e[type=item]");
        c.server().command(String.format(Locale.ROOT, "tp @p %.2f %.2f %.2f -90 0", LANE_X0 + 0.5, y, z));
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
        c.waitUntil("AP3 running the RUN node's held walk", mc -> (Boolean) dev.testkit.gametest.ModUnderTest.staticCall(
                EXEC, "isRunning"), 10);
    }

    private static String activeType() {
        Object n = dev.testkit.gametest.mod.Mod.field("ap3.Ap3Executor", "activeNode");
        return n == null ? "none" : String.valueOf(Ap3RuntimeTests.field(n, "type"));
    }

    private static boolean running() {
        return (Boolean) dev.testkit.gametest.ModUnderTest.staticCall(EXEC, "isRunning");
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

    // ------------------------------------------------------------------------------------------------- ledge

    private static void ledge(Session c, int h, boolean past) {
        ClientGameTestContext ctx = c.ctx();
        double z = Ap3RuntimeTests.lane();
        int zc = (int) Math.floor(z);
        int top = FLOOR + h;
        double standY = top + 1;
        int lastX = LANE_X0 + 14;
        double edge = lastX + 1;
        double tx = past ? edge + 0.6 : edge - 0.35;
        c.server().command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", LANE_X0 - 1, FLOOR + 1,
                zc - 3, lastX, top, zc + 3));
        speed(c, 0.4);
        long mark = LogTap.mark();
        try {
            Object run = node("RUN", LANE_X0 + 3.0, z, -90f, 0f, 2.0, 2.0);
            set(run, "y", standY);
            Object align = node("FAST_ALIGN", tx, z, 0f, 0f, 3.0, 3.0);
            set(align, "y", standY);
            walkIntoRunAt(c, z, standY, run, align);
            double minY = Double.MAX_VALUE;
            boolean alignSeen = false;
            int after = -1;
            double[] last = null;
            for (int t = 0; t < 200; t++) {
                ctx.waitTick();
                Object[] r = onClient(ctx, mc -> new Object[]{mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                        activeType(), running()});
                String type = (String) r[3];
                if (type.equals("FAST_ALIGN")) {
                    alignSeen = true;
                }
                if (alignSeen) {
                    minY = Math.min(minY, (Double) r[1]);
                }
                last = new double[]{(Double) r[0], (Double) r[1], (Double) r[2]};
                if (alignSeen && !(Boolean) r[4] && after < 0) {
                    after = t;
                }
                if (after >= 0 && t - after >= 20) {
                    break;
                }
            }
            List<String> guard = logSince(mark, "would take you off a ledge");
            List<String> settled = logSince(mark, "settled as close as is safe");
            String reason = onClient(ctx, mc -> String.valueOf(dev.testkit.gametest.ModUnderTest.staticCall(EXEC, "stopReason")));
            c.note(String.format(Locale.ROOT, "h %d, point x %.2f (edge %.1f): lowest y %.3f against standing %.1f, ended at"
                            + " (%.4f, %.3f, %.4f), guard lines %d, settled lines %d, stop reason '%s'", h, tx, edge, minY,
                    standY, last[0], last[1], last[2], guard.size(), settled.size(), reason));
            for (String l : guard) {
                c.note("  " + l);
            }
            for (String l : settled) {
                c.note("  " + l);
            }
            if (!alignSeen) {
                throw new AssertionError("the Fast Align never ran - nothing was tested");
            }
            if (after < 0) {
                throw new AssertionError("AP3 was still running 200 ticks after the align began");
            }
            if (minY < standY - 0.5) {
                throw new AssertionError(String.format(Locale.ROOT, "the align took him %.3f below where he stood (limit 0.5)",
                        standY - minY));
            }
            if (past) {
                if (guard.isEmpty()) {
                    throw new AssertionError("the point is past the edge but the ledge guard never acted");
                }
                if (settled.isEmpty()) {
                    throw new AssertionError("the align did not say it settled as close as is safe");
                }
                if (last[0] > edge + 0.3) {
                    throw new AssertionError(String.format(Locale.ROOT, "he ended at x %.3f, past the edge's last foothold",
                            last[0]));
                }
            } else if (Math.abs(last[0] - tx) > 1e-3 || Math.abs(last[2] - z) > 1e-3) {
                throw new AssertionError(String.format(Locale.ROOT, "the align ended %.4f / %.4f off its point on the ledge",
                        last[0] - tx, last[2] - z));
            }
        } finally {
            onClient(ctx, mc -> {
                Ap3RuntimeTests.staticCallStop();
                return null;
            });
            speed(c, 0.1);
            c.server().command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", LANE_X0 - 1, FLOOR + 1,
                    zc - 3, lastX, top, zc + 3));
        }
    }

    // ------------------------------------------------------------------------------------------------- boom

    /** The block a look from {@code eye} meets within 4.5, or null. */
    private static BlockPos lookBlock(ClientGameTestContext ctx, Vec3 eye, float yaw, float pitch) {
        return onClient(ctx, mc -> {
            float yr = yaw * Mth.DEG_TO_RAD;
            float pr = pitch * Mth.DEG_TO_RAD;
            Vec3 dir = new Vec3(-Mth.sin(yr) * Mth.cos(pr), -Mth.sin(pr), Mth.cos(yr) * Mth.cos(pr));
            HitResult h = mc.level.clip(new ClipContext(eye, eye.add(dir.scale(4.5)), ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE, mc.player));
            return h instanceof BlockHitResult b && h.getType() == HitResult.Type.BLOCK ? b.getBlockPos().immutable() : null;
        });
    }

    /** What the boom's own angle meets from where it was made (its point at standing eye height). */
    private static BlockPos nodeBlock(ClientGameTestContext ctx, double x, double z, float yaw, float pitch) {
        return lookBlock(ctx, new Vec3(x, FLOOR + 1 + 1.62, z), yaw, pitch);
    }

    /**
     * The Superboom tap(s) in the trace: exactly one is required, and the look it is judged by must meet {@code target}.
     * That look is the movement packet that follows the tap in the same tick: AP3 sends the tap right ahead of it
     * (beforeSendPosition), and GrimAC judges a use / dig by the rotation of the NEXT movement packet (BadPacketsJ;
     * docs/AP3.md in the mod). Returns the tap's index in {@code evs}.
     */
    private static int judgeTap(Session c, List<Ap3RuntimeTests.Ev> evs, BlockPos target) {
        int tap = -1;
        int taps = 0;
        for (int i = 0; i < evs.size(); i++) {
            Ap3RuntimeTests.Ev e = evs.get(i);
            if (e.kind().equals("action") && e.extra().equals("START_DESTROY_BLOCK")) {
                taps++;
                if (tap < 0) {
                    tap = i;
                }
            }
        }
        List<String> log = new ArrayList<>();
        for (String l : LogTap.since(0)) {
            if (l.contains("[AP3] boom #")) {
                log.add(l);
            }
        }
        for (String l : log.subList(Math.max(0, log.size() - 8), log.size())) {
            c.note("  " + l);
        }
        if (taps != 1) {
            throw new AssertionError("expected exactly one Superboom tap on the wire, saw " + taps);
        }
        double px = Double.NaN, py = Double.NaN, pz = Double.NaN;
        float yaw = Float.NaN, pitch = Float.NaN;
        for (int i = 0; i < tap; i++) {
            Ap3RuntimeTests.Ev e = evs.get(i);
            if (e.kind().equals("move") && !Double.isNaN(e.x())) {
                px = e.x();
                py = e.y();
                pz = e.z();
            }
        }
        for (int i = tap + 1; i < evs.size(); i++) {
            Ap3RuntimeTests.Ev e = evs.get(i);
            if (e.kind().equals("E")) {
                break; // the tick ended without a movement packet
            }
            if (e.kind().equals("move")) {
                if (!Double.isNaN(e.x())) {
                    px = e.x();
                    py = e.y();
                    pz = e.z();
                }
                if (!Float.isNaN(e.yRot())) {
                    yaw = e.yRot();
                    pitch = e.xRot();
                }
                break;
            }
        }
        if (Double.isNaN(px) || Float.isNaN(yaw)) {
            throw new AssertionError("no movement packet carrying a rotation followed the tap in its tick");
        }
        BlockPos met = lookBlock(c.ctx(), new Vec3(px, py + 1.62, pz), yaw, pitch);
        c.note(String.format(Locale.ROOT, "the look reported with the tap: %.2f/%.2f from (%.2f, %.2f, %.2f) meets %s; target %s",
                yaw, pitch, px, py, pz, met == null ? "nothing" : met.toShortString(), target.toShortString()));
        if (!target.equals(met)) {
            throw new AssertionError("the look the server had at the tap meets " + (met == null ? "nothing" : met.toShortString())
                    + ", not the boom's block " + target.toShortString());
        }
        return tap;
    }

    private static void boomSprintThrough(Session c) {
        ClientGameTestContext ctx = c.ctx();
        ctx.getInput().pressKey(options -> options.keyHotbarSlots[0]);
        ctx.waitTicks(2);
        double z = Ap3RuntimeTests.lane();
        double nodeZ = z + 0.7;
        BlockPos target = new BlockPos(LANE_X0 + 13, FLOOR + 2, (int) Math.floor(nodeZ));
        c.server().command(String.format(Locale.ROOT, "setblock %d %d %d minecraft:stone", target.getX(), target.getY(),
                target.getZ()));
        speed(c, 0.4);
        try {
            Object run = Ap3RuntimeTests.runNode(z);
            Object boom = node("BOOM", LANE_X0 + 10.0, nodeZ, -90f, 0f, 1.0, 3.0);
            Object stop = node("STOP", LANE_X0 + 26.0, z, -90f, 0f, 2.0, 3.0);
            c.server().sync(20);
            ctx.waitTicks(10); // the block update reaches the client
            BlockPos made = nodeBlock(ctx, LANE_X0 + 10.0, nodeZ, -90f, 0f);
            BlockPos fromLine = lookBlock(ctx, new Vec3(LANE_X0 + 10.0, FLOOR + 1 + 1.62, z), -90f, 0f);
            c.note("the node's angle meets " + made + " from its point and " + fromLine + " from his line");
            if (!target.equals(made) || fromLine != null) {
                throw new AssertionError("the arena is not what the case needs: from the point " + made + ", from the line "
                        + fromLine);
            }
            Ap3RuntimeTests.startProbe(ctx);
            walkIntoRunAt(c, z, FLOOR + 1, run, boom, stop);
            ctx.waitTicks(50);
            List<Ap3RuntimeTests.Ev> evs = Ap3RuntimeTests.stopProbe(ctx);
            int insideTicks = 0;
            for (Ap3RuntimeTests.Ev e : evs) {
                if (e.kind().equals("E") && onClient(ctx, mc -> contains(boom, new Vec3(e.x(), e.y(), e.z())))) {
                    insideTicks++;
                }
            }
            c.note("ticks he spent inside the boom's box: " + insideTicks);
            if (insideTicks == 0) {
                throw new AssertionError("he never stood in the boom's box at a tick end - nothing was tested");
            }
            judgeTap(c, evs, target);
        } finally {
            onClient(ctx, mc -> {
                Ap3RuntimeTests.staticCallStop();
                return null;
            });
            speed(c, 0.1);
            c.server().command(String.format(Locale.ROOT, "setblock %d %d %d minecraft:air", target.getX(), target.getY(),
                    target.getZ()));
        }
    }

    private static void boomNodeBehind(Session c) {
        ClientGameTestContext ctx = c.ctx();
        ctx.getInput().pressKey(options -> options.keyHotbarSlots[0]);
        ctx.waitTicks(2);
        double z = Ap3RuntimeTests.lane();
        Object run = Ap3RuntimeTests.runNode(z);
        Object boom = node("BOOM", LANE_X0 + 10.0, z, -90f, 60f, 1.0, 3.0);
        // Yaw 0 turns the box: length runs along z, width along x - so 1 wide (x 10.1-11.1) and 3 long across the lane.
        Object look = node("LOOK", LANE_X0 + 10.6, z, 0f, 0f, 3.0, 1.0);
        Object stop = node("STOP", LANE_X0 + 24.0, z, -90f, 0f, 2.0, 3.0);
        try {
            BlockPos target = nodeBlock(ctx, LANE_X0 + 10.0, z, -90f, 60f);
            c.note("the boom's block (its angle from its point): " + target);
            if (target == null) {
                throw new AssertionError("the boom's angle meets nothing from its point");
            }
            Ap3RuntimeTests.startProbe(ctx);
            walkIntoRunAt(c, z, FLOOR + 1, run, boom, look, stop);
            ctx.waitTicks(50);
            List<Ap3RuntimeTests.Ev> evs = Ap3RuntimeTests.stopProbe(ctx);
            Ap3RuntimeTests.Ev lookEntry = Ap3RuntimeTests.firstInside(ctx, evs, look);
            if (lookEntry == null) {
                throw new AssertionError("never entered the LOOK's box - nothing was tested");
            }
            int tap = judgeTap(c, evs, target);
            int tapTick = evs.get(tap).tick();
            int turned = -1;
            for (Ap3RuntimeTests.Ev e : evs) {
                if (e.kind().equals("E") && (Math.abs(Mth.wrapDegrees(e.viewY())) < 1f || Math.abs(Mth.wrapDegrees(e.yRot())) < 1f)) {
                    turned = e.tick();
                    break;
                }
            }
            c.note("entered the LOOK's box at tick " + lookEntry.tick() + ", tap at tick " + tapTick + ", first tick facing the"
                    + " LOOK's yaw 0: " + turned);
            if (turned < 0) {
                throw new AssertionError("the LOOK never ran after the boom - it was skipped");
            }
            if (turned < tapTick) {
                throw new AssertionError("the LOOK turned him (tick " + turned + ") before the boom's tap (tick " + tapTick + ")");
            }
        } finally {
            onClient(ctx, mc -> {
                Ap3RuntimeTests.staticCallStop();
                return null;
            });
        }
    }

    private static void boomInAlign(Session c) {
        ClientGameTestContext ctx = c.ctx();
        ctx.getInput().pressKey(options -> options.keyHotbarSlots[0]);
        ctx.waitTicks(2);
        double z = Ap3RuntimeTests.lane();
        double ax = LANE_X0 + 10.5;
        Object run = Ap3RuntimeTests.runNode(z);
        Object align = node("ALIGN", ax, z, 0f, 0f, 2.0, 2.0);
        Object boom = node("BOOM", ax, z, -90f, 60f, 1.0, 3.0);
        try {
            BlockPos target = nodeBlock(ctx, ax, z, -90f, 60f);
            Ap3RuntimeTests.startProbe(ctx);
            walkIntoRunAt(c, z, FLOOR + 1, run, align, boom);
            ctx.waitTicks(80);
            List<Ap3RuntimeTests.Ev> evs = Ap3RuntimeTests.stopProbe(ctx);
            int tap = judgeTap(c, evs, target);
            Ap3RuntimeTests.Ev before = null;
            for (int i = tap; i >= 0 && before == null; i--) {
                if (evs.get(i).kind().equals("E")) {
                    before = evs.get(i);
                }
            }
            if (before == null) {
                throw new AssertionError("no tick end before the tap");
            }
            c.note(String.format(Locale.ROOT, "at the tap he stood at (%.4f, %.4f), the align's point (%.4f, %.4f)", before.x(),
                    before.z(), ax, z));
            if (Math.abs(before.x() - ax) > 0.01 || Math.abs(before.z() - z) > 0.01) {
                throw new AssertionError("the boom went out before the align had landed him");
            }
        } finally {
            onClient(ctx, mc -> {
                Ap3RuntimeTests.staticCallStop();
                return null;
            });
        }
    }

    // ------------------------------------------------------------------------------------------------- leap gate

    private static void leapGate(Session c) {
        ClientGameTestContext ctx = c.ctx();
        double z = Ap3RuntimeTests.lane();
        double ax = LANE_X0 + 10.5;
        Object run = Ap3RuntimeTests.runNode(z);
        Object stop = node("STOP", ax, z, -90f, 0f, 3.0, 3.0);
        set(stop, "leapCounter", 2);
        // The align is further on: the gate ends his run, and he is then put onto it (a 2.5-block server tp, well under
        // AP3's 8-block leap line) the way he would step onto one while waiting.
        double alx = ax + 2.5;
        Object align = node("ALIGN", alx, z, 0f, 0f, 2.0, 2.0);
        Object walk = node("WALK", ax, z, 0f, 0f, 3.0, 3.0);
        long mark = LogTap.mark();
        try {
            onClient(ctx, mc -> {
                dev.testkit.gametest.ModUnderTest.staticCall(EXEC, "setLeapCountAnyPlayerForTest",
                        new Class<?>[]{boolean.class}, new Object[]{true});
                return null;
            });
            walkIntoRunAt(c, z, FLOOR + 1, run, stop, align, walk);
            c.waitUntil("the leapcounter gate to hold", mc -> ModUnderTestHolder.gate() != null, 40);
            ctx.waitTicks(10);
            c.server().command(String.format(Locale.ROOT, "tp @p %.2f %d %.2f", alx - 0.6, FLOOR + 1, z + 0.4));
            c.server().sync(20);
            ctx.waitTicks(60);
            Object[] mid = onClient(ctx, mc -> new Object[]{mc.player.getX(), mc.player.getZ(),
                    dev.testkit.gametest.ModUnderTest.staticCall(EXEC, "leapGateNode") != null, activeType()});
            c.note(String.format(Locale.ROOT, "60 ticks in, before any leap: at (%.4f, %.4f), gate holding %s, active %s",
                    mid[0], mid[1], mid[2], mid[3]));
            if (!(Boolean) mid[2]) {
                throw new AssertionError("the leapcounter:2 gate is not holding before any teammate leapt");
            }
            if (Math.abs((Double) mid[0] - alx) > 1e-3 || Math.abs((Double) mid[1] - z) > 1e-3) {
                throw new AssertionError(String.format(Locale.ROOT, "the align did not run during the wait: %.4f / %.4f off",
                        (Double) mid[0] - alx, (Double) mid[1] - z));
            }
            String[] names = {"LeapA", "LeapB"};
            for (String n : names) {
                c.server().command(String.format(Locale.ROOT, "testkit spawn %s %.2f %d %.2f", n, ax, FLOOR + 1, z + 24));
            }
            c.server().sync(20);
            ctx.waitTicks(20);
            c.server().command(String.format(Locale.ROOT, "tp LeapA %.2f %d %.2f", ax + 2, FLOOR + 1, z + 1.5));
            c.server().sync(20);
            ctx.waitTicks(10);
            Object[] one = onClient(ctx, mc -> new Object[]{dev.testkit.gametest.ModUnderTest.staticCall(EXEC, "leapGateNode") != null,
                    mc.player.getZ()});
            c.note("after one leap: gate holding " + one[0] + ", z " + one[1]);
            if (!(Boolean) one[0]) {
                throw new AssertionError("the gate let go after one leap of two");
            }
            if (Math.abs((Double) one[1] - z) > 1e-3) {
                throw new AssertionError("the Walk fired before the count: z moved to " + one[1]);
            }
            c.server().command(String.format(Locale.ROOT, "tp LeapB %.2f %d %.2f", ax - 2, FLOOR + 1, z - 1.5));
            c.server().sync(20);
            boolean released = false;
            double zEnd = z;
            for (int t = 0; t < 40; t++) {
                ctx.waitTick();
                Object[] r = onClient(ctx, mc -> new Object[]{dev.testkit.gametest.ModUnderTest.staticCall(EXEC, "leapGateNode") == null,
                        mc.player.getZ()});
                released |= (Boolean) r[0];
                zEnd = (Double) r[1];
            }
            for (String l : logSince(mark, "leap gate")) {
                c.note("  " + l);
            }
            c.note(String.format(Locale.ROOT, "after the second leap: released %s, walked to z %+.2f", released, zEnd - z));
            if (!released) {
                throw new AssertionError("the gate never released after two leaps");
            }
            if (zEnd - z < 2.0) {
                throw new AssertionError(String.format(Locale.ROOT, "the Walk did not run after the count (%.2f along it)",
                        zEnd - z));
            }
        } finally {
            onClient(ctx, mc -> {
                Ap3RuntimeTests.staticCallStop();
                dev.testkit.gametest.ModUnderTest.staticCall(EXEC, "setLeapCountAnyPlayerForTest",
                        new Class<?>[]{boolean.class}, new Object[]{false});
                return null;
            });
            c.server().command("testkit remove LeapA");
            c.server().command("testkit remove LeapB");
        }
    }
}
