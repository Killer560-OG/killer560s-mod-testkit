package dev.testkit.gametest;

import dev.testkit.gametest.hx.Session;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import static dev.testkit.gametest.Align12Cases.analyze;
import static dev.testkit.gametest.Align12Cases.lines;
import static dev.testkit.gametest.Align12Cases.release;
import static dev.testkit.gametest.Align12Cases.same;
import static dev.testkit.gametest.Align12Cases.sample;
import static dev.testkit.gametest.Align12Cases.tail;
import static dev.testkit.gametest.Ap3RuntimeTests.FLOOR;
import static dev.testkit.gametest.Ap3RuntimeTests.LANE_X0;
import static dev.testkit.gametest.Ap3RuntimeTests.node;
import static dev.testkit.gametest.Ap3RuntimeTests.onClient;
import static dev.testkit.gametest.Ap3RuntimeTests.set;
import static dev.testkit.gametest.Ap3RuntimeTests.setChain;

/**
 * alignleap-1112 (killer560 2026-11-12: "there should be a leap case for it"). In the 63-ap3 session (cheat jar, GrimAC on).
 * <ul>
 *   <li>1112-align12-leap: a Spirit Leap lands him in the align's 1x1 block: a server position packet the client accepts (tp)
 *       at a random spot of the block with a random yaw and pitch and no velocity, then Hypixel's "You have teleported to"
 *       line. The ticks from the arrival tick to on-the-point-and-stopped are counted (average, worst, histogram).</li>
 *   <li>1112-align12-leap-use / -swap / -attack / -tp: the same, with his own use_item, hotbar swap, attack or a second teleport
 *       accept landing in the align's ticks; SameTick checks on the wire that the align's packet and his shared a server tick.</li>
 *   <li>1112-align12-leap-click: his inventory opens in the align's first tick and he clicks in it every tick: AP3 lets go (a
 *       screen stops it, as always), his clicks go out, and GrimAC stays quiet (a click with a movement key still held is
 *       MultiActionsC); the align's own stop is not asked for. A click with no screen open is not something the game sends.</li>
 *   <li>1112-align12-ap3angle: an AP3 RUN node carries him into the block from headings swept all the way round (every
 *       30 degrees, with a random offset after the first lap); the ticks to stopped are counted per heading bucket.</li>
 * </ul>
 * Every trial: he must end on the point, and his crosshair (the view the screen shows) must not move after the arrival.
 */
final class Align12LeapCases {

    private Align12LeapCases() {
    }

    private static final int ROD_A = 7;
    private static final int ROD_B = 8;

    static void register(Session s) {
        s.test("1112-align12-leap", c -> leap(c, 1, null, Align12Cases.TRIALS + 4));
        for (SameTick.Act a : new SameTick.Act[]{SameTick.Act.USE, SameTick.Act.SWAP, SameTick.Act.CLICK, SameTick.Act.ATTACK, SameTick.Act.TP}) {
            String name = a.name().toLowerCase(Locale.ROOT);
            s.test("1112-align12-leap-" + name, c -> leap(c, 10 + a.ordinal(), a, Math.max(6, Align12Cases.TRIALS - 2)));
        }
        s.test("1112-align12-ap3angle", Align12LeapCases::angle);
    }

    private static void leap(Session c, long seed, SameTick.Act act, int trials) {
        ClientGameTestContext ctx = c.ctx();
        c.server().command("attribute @p minecraft:movement_speed base set 0.50");
        c.server().sync(20);
        double z = Ap3RuntimeTests.lane();
        double tx = LANE_X0 + 20.5, tz = Math.floor(z) + 0.5, y = FLOOR + 1;
        Random rnd = new Random(1112L * 31 + seed);
        int[] hist = new int[12];
        int planned = 0, fellBack = 0, sum = 0, worst = 0;
        double jumpMax = 0;
        List<String> fails = new ArrayList<>();
        SameTick st = null;
        if (act == SameTick.Act.USE || act == SameTick.Act.SWAP) {
            c.server().command("item replace entity @p hotbar." + ROD_A + " with minecraft:blaze_rod");
            c.server().command("item replace entity @p hotbar." + ROD_B + " with minecraft:blaze_rod");
            ctx.waitTicks(3);
            onClient(ctx, mc -> {
                mc.player.getInventory().setSelectedSlot(ROD_A);
                return null;
            });
        }
        boolean screen = act == SameTick.Act.CLICK;
        if (act == SameTick.Act.ATTACK) {
            // a big interaction entity around him, so his left click has a target whatever look the leap gave him
            c.server().command(String.format(Locale.ROOT, "summon minecraft:interaction %.2f %.2f %.2f {width:6f,height:6f,response:true}", tx, y - 2, tz));
        }
        try {
            if (act != null) {
                st = onClient(ctx, mc -> SameTick.start(act, 1, p -> p instanceof net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
                        && Boolean.TRUE.equals(dev.testkit.gametest.mod.Mod.staticCall("ap3.Ap3Executor", "isRunning")), ROD_A, ROD_B));
            }
            for (int t = 0; t < trials; t++) {
                double lx = tx + (rnd.nextDouble() - 0.5) * 0.96, lz = tz + (rnd.nextDouble() - 0.5) * 0.96;
                double yaw = rnd.nextDouble() * 360 - 180, pitch = rnd.nextDouble() * 180 - 90;
                long mark = LogTap.mark();
                onClient(ctx, mc -> {
                    Ap3RuntimeTests.staticCallStop();
                    return null;
                });
                Object align = node("FAST_ALIGN", tx, tz, 0f, 0f, 1.0, 1.0);
                set(align, "y", y);
                // standing far off, the align armed: the teammate he leaps to is in the block
                c.server().command(String.format(Locale.ROOT, "tp @p %.2f %.2f %.2f -90 0", tx - 10, y, tz));
                c.server().sync(20);
                ctx.waitTicks(10);
                onClient(ctx, mc -> {
                    setChain(align);
                    return null;
                });
                ctx.waitTicks(3);
                c.server().command(String.format(Locale.ROOT, "tp @p %.4f %.2f %.4f %.3f %.3f", lx, y, lz, yaw, pitch));
                c.server().command("tellraw @p {\"text\":\"You have teleported to Rika!\"}");
                List<double[]> tr = new ArrayList<>();
                int entry = -1;
                boolean sawOn = false;
                for (int i = 0; i < 90; i++) {
                    if (act == SameTick.Act.TP && i >= 1 && i < 5) {
                        c.server().command("execute as @a at @s run tp @s ~ ~ ~"); // a teleport accept in the align's first ticks
                    }
                    if (screen && i == 1) {
                        onClient(ctx, mc -> {
                            mc.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player));
                            return null;
                        });
                    }
                    ctx.waitTick();
                    double[] r = sample(ctx, null);
                    tr.add(r);
                    if (screen && i >= 8) {
                        break; // the screen stopped AP3: nothing more to wait for
                    }
                    if (entry < 0 && Math.abs(r[0] - tx) <= 0.5 && Math.abs(r[1] - tz) <= 0.5) {
                        entry = i;
                    }
                    sawOn |= r[4] == 1;
                    if (entry >= 0 && (sawOn && r[4] == 0 && r[6] == 0 && same(tr, 5) || i > entry + 40)) {
                        break;
                    }
                }
                release(ctx);
                if (screen) {
                    onClient(ctx, mc -> {
                        mc.setScreen(null);
                        return null;
                    });
                    Object running = onClient(ctx, mc -> dev.testkit.gametest.mod.Mod.staticCall("ap3.Ap3Executor", "isRunning"));
                    if (Boolean.TRUE.equals(running)) {
                        fails.add("trial " + t + ": AP3 still ran with his inventory open");
                    }
                    planned++;
                    continue;
                }
                double[] an = analyze(tr, tx, tz, 1);
                int done = (int) an[1], n = done >= 0 && entry >= 0 ? done - entry : -1;
                double[] last = tr.get(tr.size() - 1);
                List<String> plan = lines(mark, "tick-exact plan");
                List<String> lost = lines(mark, "tick-exact align handed");
                planned += plan.isEmpty() ? 0 : 1;
                fellBack += lost.isEmpty() ? 0 : 1;
                jumpMax = Math.max(jumpMax, an[2]);
                if (n >= 0) {
                    hist[Math.min(n, hist.length - 1)]++;
                    sum += n;
                    worst = Math.max(worst, n);
                }
                StringBuilder path = new StringBuilder();
                for (int i = 0; i < Math.min(tr.size(), 9); i++) {
                    path.append(String.format(Locale.ROOT, " [%d: %+.3f,%+.3f v %.3f on %.0f]", i, tr.get(i)[0] - tx, tr.get(i)[1] - tz,
                            Math.hypot(tr.get(i)[2], tr.get(i)[3]), tr.get(i)[4]));
                }
                c.note(String.format(Locale.ROOT, "trial %d: landed (%+.3f, %+.3f) yaw %+.0f pitch %+.0f: entered at sample %d, on the point"
                                + " and stopped %s; end off (%+.6f, %+.6f); view jump %.2f deg%s%s", t, lx - tx, lz - tz, yaw, pitch, entry,
                        n >= 0 ? n + " tick(s) after landing" : "NEVER", last[0] - tx, last[1] - tz, an[2],
                        plan.isEmpty() ? "; no tick-exact plan" : "; " + tail(plan.get(plan.size() - 1)),
                        lost.isEmpty() ? "" : "; " + tail(lost.get(0))) + path);
                if (an[2] > 0.5) {
                    fails.add(String.format(Locale.ROOT, "trial %d: his crosshair jumped %.1f degrees in one tick", t, an[2]));
                }
                if (entry < 0) {
                    fails.add("trial " + t + " never landed in the block");
                } else if (n < 0 || Math.abs(last[0] - tx) >= 2e-4 || Math.abs(last[1] - tz) >= 2e-4) {
                    fails.add(String.format(Locale.ROOT, "trial %d ended (%+.6f, %+.6f) off the point", t, last[0] - tx, last[1] - tz));
                }
            }
        } finally {
            if (st != null) {
                SameTick open = st;
                onClient(ctx, mc -> {
                    open.close();
                    return null;
                });
            }
            release(ctx);
            onClient(ctx, mc -> {
                Ap3RuntimeTests.staticCallStop();
                return null;
            });
            c.server().command("attribute @p minecraft:movement_speed base set 0.10");
            c.server().command("clear @p minecraft:blaze_rod");
            c.server().command("kill @e[type=minecraft:interaction]");
            c.server().sync(20);
        }
        c.note(String.format(Locale.ROOT, "align12 leap %s: %d trials, ticks after landing: average %.2f, worst %d, histogram %s; tick-exact plans %d,"
                + " handed to the regular align %d; worst view jump %.2f deg", act == null ? "plain" : act, trials, sum / (double) trials, worst,
                java.util.Arrays.toString(hist), planned, fellBack, jumpMax));
        if (st != null) {
            c.note(st.report());
            String why = screen ? st.playerWindows() > 0 ? null : "his clicks never reached the wire (" + st.report() + ")"
                    : st.verdict("align12 leap " + act, false);
            c.check(why == null, why);
        }
        if (planned == 0) {
            throw new AssertionError("no tick-exact plan in any trial - the feature never acted");
        }
        if (!fails.isEmpty()) {
            throw new AssertionError(String.join("; ", fails));
        }
    }

    /** A RUN node carries him into the block along headings every 30 degrees; per bucket the ticks to stopped. */
    private static void angle(Session c) {
        ClientGameTestContext ctx = c.ctx();
        c.server().command("attribute @p minecraft:movement_speed base set 0.50");
        c.server().sync(20);
        double z = Ap3RuntimeTests.lane();
        double tx = LANE_X0 + 20.5, tz = Math.floor(z) + 0.5, y = FLOOR + 1;
        Random rnd = new Random(1112L * 31 + 40);
        int laps = Math.max(2, Align12Cases.TRIALS / 3);
        int[] cnt = new int[12], sum = new int[12], worst = new int[12];
        double[] sq = new double[12];
        double jumpMax = 0;
        int planned = 0, fellBack = 0, steered = 0;
        List<String> fails = new ArrayList<>();
        try {
            for (int lap = 0; lap < laps; lap++) {
                for (int b = 0; b < 12; b++) {
                    double head = b * 30 + (lap == 0 ? 0 : (rnd.nextDouble() - 0.5) * 28); // degrees off +x, anywhere round
                    double side = (rnd.nextDouble() - 0.5) * 0.8, dist = 7 + rnd.nextDouble() * 2;
                    double hr = Math.toRadians(head), dx = Math.cos(hr), dz = Math.sin(hr);
                    double sx = tx - dx * dist - dz * side, sz = tz - dz * dist + dx * side; // the RUN box's center
                    float yaw = (float) (head - 90);
                    long mark = LogTap.mark();
                    onClient(ctx, mc -> {
                        Ap3RuntimeTests.staticCallStop();
                        return null;
                    });
                    Object run = node("RUN", sx, sz, yaw, 0f, 2.0, 2.0);
                    Object align = node("FAST_ALIGN", tx, tz, 0f, 0f, 1.0, 1.0);
                    set(align, "y", y);
                    c.server().command(String.format(Locale.ROOT, "tp @p %.3f %.2f %.3f %.3f 0", sx - dx * 2.5, y, sz - dz * 2.5, yaw));
                    c.server().sync(20);
                    ctx.waitTicks(10);
                    c.waitUntil("the chunks round the align", mc -> mc.level.hasChunkAt(new net.minecraft.core.BlockPos((int) Math.floor(sx - dx * 2.5), FLOOR,
                            (int) Math.floor(sz - dz * 2.5))) && mc.level.hasChunkAt(new net.minecraft.core.BlockPos((int) Math.floor(tx), FLOOR,
                            (int) Math.floor(tz))), 200);
                    onClient(ctx, mc -> {
                        setChain(run, align);
                        return null;
                    });
                    ctx.waitTicks(2);
                    ctx.getInput().holdKey(o -> o.keyUp);
                    boolean in = false;
                    for (int i = 0; i < 40 && !in; i++) {
                        ctx.waitTick();
                        in = onClient(ctx, mc -> Ap3RuntimeTests.contains(run, mc.player.position()));
                    }
                    release(ctx);
                    if (!in) {
                        throw new AssertionError("walking with W never reached the RUN node's box at heading " + head);
                    }
                    List<double[]> tr = new ArrayList<>();
                    boolean entered = false;
                    for (int i = 0; i < 120; i++) {
                        ctx.waitTick();
                        double[] r = sample(ctx, "RUN");
                        tr.add(r);
                        entered |= Math.abs(r[0] - tx) <= 0.5 && Math.abs(r[1] - tz) <= 0.5;
                        if (entered && r[4] == 0 && r[6] == 0 && tr.size() > 8 && same(tr, 5)) {
                            break;
                        }
                    }
                    double[] an = analyze(tr, tx, tz, 0);
                    int entry = (int) an[0], done = (int) an[1], n = done >= 0 && entry >= 0 ? done - entry : -1;
                    double[] last = tr.get(tr.size() - 1);
                    List<String> plan = lines(mark, "tick-exact plan");
                    List<String> lost = lines(mark, "tick-exact align handed");
                    planned += plan.isEmpty() ? 0 : 1;
                    fellBack += lost.isEmpty() ? 0 : 1;
                    steered += lines(mark, "approach tick steered").isEmpty() ? 0 : 1;
                    jumpMax = Math.max(jumpMax, an[2]);
                    if (n >= 0) {
                        cnt[b]++;
                        sum[b] += n;
                        sq[b] += n * n;
                        worst[b] = Math.max(worst[b], n);
                    }
                    c.note(String.format(Locale.ROOT, "lap %d heading %+.1f side %+.2f from %.1f: entered at sample %d, on the point and stopped %s;"
                                    + " end off (%+.6f, %+.6f); view jump %.2f deg%s%s", lap, head, side, dist, entry,
                            n >= 0 ? n + " tick(s) after entering" : "NEVER", last[0] - tx, last[1] - tz, an[2],
                            plan.isEmpty() ? "; no tick-exact plan" : "; " + tail(plan.get(plan.size() - 1)),
                            lost.isEmpty() ? "" : "; " + tail(lost.get(0))));
                    if (an[2] > 0.5) {
                        fails.add(String.format(Locale.ROOT, "heading %.0f: his crosshair jumped %.1f degrees in one tick", head, an[2]));
                    }
                    if (entry < 0) {
                        fails.add(String.format(Locale.ROOT, "heading %.0f never entered the block", head));
                    } else if (n < 0 || Math.abs(last[0] - tx) >= 2e-4 || Math.abs(last[1] - tz) >= 2e-4) {
                        fails.add(String.format(Locale.ROOT, "heading %.0f ended (%+.6f, %+.6f) off the point", head, last[0] - tx, last[1] - tz));
                    }
                }
            }
        } finally {
            release(ctx);
            onClient(ctx, mc -> {
                Ap3RuntimeTests.staticCallStop();
                return null;
            });
            c.server().command("attribute @p minecraft:movement_speed base set 0.10");
            c.server().sync(20);
        }
        int all = 0, allSum = 0, allWorst = 0;
        double allSq = 0;
        for (int b = 0; b < 12; b++) {
            all += cnt[b];
            allSum += sum[b];
            allSq += sq[b];
            allWorst = Math.max(allWorst, worst[b]);
            double mean = cnt[b] == 0 ? 0 : sum[b] / (double) cnt[b];
            c.note(String.format(Locale.ROOT, "heading bucket %3d: %d stopped of %d, ticks after entering: average %.2f, spread %.2f, worst %d", b * 30,
                    cnt[b], laps, mean, cnt[b] == 0 ? 0 : Math.sqrt(Math.max(0, sq[b] / cnt[b] - mean * mean)), worst[b]));
        }
        double mean = all == 0 ? 0 : allSum / (double) all;
        c.note(String.format(Locale.ROOT, "align12 ap3angle: %d trials, average %.2f ticks, spread %.2f, worst %d; tick-exact plans %d, approach tick"
                        + " steered %d, handed to the regular align %d; worst view jump %.2f deg", laps * 12, mean,
                all == 0 ? 0 : Math.sqrt(Math.max(0, allSq / all - mean * mean)), allWorst, planned, steered, fellBack, jumpMax));
        if (planned == 0) {
            throw new AssertionError("no tick-exact plan in any trial - the feature never acted");
        }
        if (!fails.isEmpty()) {
            throw new AssertionError(String.join("; ", fails));
        }
    }
}
