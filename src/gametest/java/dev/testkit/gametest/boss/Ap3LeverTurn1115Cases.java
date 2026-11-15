package dev.testkit.gametest.boss;

import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.SameTick;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.WireCapture;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 1115-ap3-leverturn-* (mod ap3leverturn-1115, cheat jar). On Hypixel his EE3 route worked only while the high S2 lever
 * (23,132,138) was still off: Lever Aura then turned the body to click it and AP3 re-picked its keys at that yaw, which is
 * what the route was planned with; on a retry the lever was already on, Lever Aura made no turn, the keys stayed and the
 * walk drifted. Now an activated lever on a running AP3 route gets the same turn at the same tick, only no click.
 * <p>
 * The same short AP3 walk past the high lever runs twice from the same spot, lever off (with its "Not Activated" stand) and
 * lever on (no stand). Judged on the wire: every tick's position and look from the first step on is the same in both runs,
 * the first run sent one click on the lever and the second none, the body really turned toward the lever in both (a look
 * far off the walk's yaw), and the turn-only flick really ran. GrimAC silent (Session.test). The -same-use case puts his
 * own right click (a stick's use_item) into every tick of both runs.
 */
final class Ap3LeverTurn1115Cases {

    private static final String EXEC = "ap3.Ap3Executor";
    private static final String LA = "leveraura.LeverAuraConfig";
    private static final String CU = "cheatutils.CheatUtilsConfig";
    private static final double Y = 132;
    private static final int LX = 23, LY = 132, LZ = 138;
    private static final float WALK_YAW = 90f;

    private Ap3LeverTurn1115Cases() {
    }

    static void register(Session s) {
        if (!Mod.isCheat()) {
            return;
        }
        s.test("1115-ap3-leverturn-states", c -> run(c, false));
        s.test("1115-ap3-leverturn-same-use", c -> run(c, true));
    }

    private static Object node(String type, double x, double z, float yaw, double size) {
        try {
            Class<?> cls = Mod.cls("com.killer560.hub.ap3.Ap3Node");
            Object n = cls.getConstructor(Mod.cls("com.killer560.hub.ap3.Ap3Node$Type"), double.class, double.class, double.class,
                    float.class, float.class).newInstance(Mod.enumValue("com.killer560.hub.ap3.Ap3Node$Type", type), x, Y, z, yaw, 0f);
            cls.getField("length").set(n, size);
            cls.getField("width").set(n, size);
            return n;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot make an AP3 " + type + " node", e);
        }
    }

    private static String describe(Object p) {
        if (p instanceof ServerboundMovePlayerPacket m) {
            return String.format(Locale.ROOT, "move %.6f %.6f %.6f %.4f %.4f", m.getX(Double.NaN), m.getY(Double.NaN),
                    m.getZ(Double.NaN), m.getYRot(Float.NaN), m.getXRot(Float.NaN));
        }
        if (p instanceof ServerboundUseItemOnPacket) {
            return "useon";
        }
        if (p instanceof ServerboundUseItemPacket) {
            return "useitem";
        }
        if (p instanceof ServerboundClientTickEndPacket) {
            return "end";
        }
        return p.getClass().getSimpleName();
    }

    /** One run's wire: per client tick {x, y, z, yaw, pitch, lever clicks, uses}, from the tick he first moved. */
    private record Run(List<double[]> ticks, int leverClicks, int uses, int turnOnly, long turnLines, long repickLines) {
    }

    private static List<double[]> ticksFromWire(int[] counts) {
        List<double[]> out = new ArrayList<>();
        double[] cur = {Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN};
        int clicks = 0;
        int uses = 0;
        for (WireCapture.Sent w : WireCapture.snapshot()) {
            if (!w.phase().equals("play")) {
                continue;
            }
            String t = w.text();
            if (t.startsWith("move ")) {
                String[] f = t.split(" ");
                for (int i = 0; i < 5; i++) {
                    double v = Double.parseDouble(f[i + 1]);
                    if (!Double.isNaN(v)) {
                        cur[i] = v;
                    }
                }
            } else if (t.equals("useon")) {
                clicks++;
            } else if (t.equals("useitem")) {
                uses++;
            } else if (t.equals("end")) {
                out.add(new double[]{cur[0], cur[1], cur[2], cur[3], cur[4], clicks, uses});
                clicks = 0;
                uses = 0;
            }
        }
        // From the first tick he is off the start spot.
        // (vanilla may send no position for up to 20 ticks while he stands, so the start spot is the first one sent).
        int first = 0;
        while (first < out.size() && Double.isNaN(out.get(first)[0])) {
            first++;
        }
        double[] start = first < out.size() ? out.get(first) : null;
        while (start != null && first < out.size() && Math.abs(out.get(first)[0] - start[0]) < 1e-9
                && Math.abs(out.get(first)[2] - start[2]) < 1e-9) {
            first++;
        }
        int c = 0, u = 0;
        for (double[] d : out) {
            c += (int) d[5];
            u += (int) d[6];
        }
        counts[0] = c;
        counts[1] = u;
        return new ArrayList<>(out.subList(Math.max(0, first - 1), out.size()));
    }

    private static Run runOnce(Session c, boolean leverOn, boolean sameUse) {
        c.onClient(mc -> {
            Mod.staticCall(EXEC, "stop", "test lane");
            Ap3TermAuraCases.setChain();
            return null;
        });
        c.server().command("kill @e[tag=leverturn1115]");
        c.server().command(String.format(Locale.ROOT, "setblock %d %d %d minecraft:lever[face=floor,facing=west,powered=%s]",
                LX, LY, LZ, leverOn));
        if (!leverOn) {
            c.server().command(String.format(Locale.ROOT, "summon minecraft:armor_stand %.1f %d %.1f {NoGravity:1b,Invisible:1b,"
                    + "CustomNameVisible:1b,CustomName:\"Not Activated\",Tags:[\"leverturn1115\"]}", LX + 0.5, LY + 1, LZ + 0.5));
        }
        c.server().sync(10);
        TerminalAuraCases.tp(c, 36.2, Y, 141.8, WALK_YAW);
        c.ctx().waitTicks(10);
        // A fresh run each time: Lever Aura forgets the levers it flicked and its cooldown (resetRun).
        LightsDeviceCases.newRun(c);
        LightsDeviceCases.openS2(c);
        c.ctx().waitTicks(20);
        boolean powered = c.onClient(mc -> {
            var st = mc.level.getBlockState(new net.minecraft.core.BlockPos(LX, LY, LZ));
            return st.getBlock() instanceof net.minecraft.world.level.block.LeverBlock
                    && st.getValue(net.minecraft.world.level.block.LeverBlock.POWERED);
        });
        c.check(powered == leverOn, "the lever is " + (powered ? "on" : "off") + " before the run, wanted " + (leverOn ? "on" : "off"));
        Object align = node("FAST_ALIGN", 35.5, 141.5, 0f, 2.0);
        Object run = node("RUN", 35.5, 141.5, WALK_YAW, 1.0);
        Object stop = node("STOP", 14.0, 141.5, WALK_YAW, 4.0);
        int turnOnly0 = ((Number) Mod.staticCall("leveraura.LeverAuraFeature", "turnOnlyCountForTest")).intValue();
        long mark = LogTap.mark();
        SameTick st = sameUse ? SameTickCases.start(c, SameTick.Act.USE, p -> p instanceof ServerboundUseItemOnPacket) : null;
        WireCapture.describe = Ap3LeverTurn1115Cases::describe;
        WireCapture.start();
        c.onClient(mc -> {
            Ap3TermAuraCases.setChain(align, run, stop);
            return null;
        });
        for (int t = 0; t < 220; t++) {
            c.ctx().waitTick();
            double x = c.onClient(mc -> mc.player.getX());
            if (t > 20 && x < 16 && !(Boolean) c.onClient(mc -> Mod.staticCall(EXEC, "isRunning"))) {
                break;
            }
        }
        c.ctx().waitTicks(5);
        WireCapture.stop();
        WireCapture.describe = null;
        if (st != null) {
            if (leverOn) {
                SameTick s2 = st;
                c.onClient(mc -> {
                    s2.close();
                    return null;
                });
            } else {
                SameTickCases.judge(c, st, "lever click with his use");
            }
        }
        int[] counts = new int[2];
        List<double[]> ticks = ticksFromWire(counts);
        int turnOnly = ((Number) Mod.staticCall("leveraura.LeverAuraFeature", "turnOnlyCountForTest")).intValue() - turnOnly0;
        List<String> logs = LogTap.since(mark);
        long turns = logs.stream().filter(l -> l.contains("[LeverAura] turning body")).count();
        long repicks = logs.stream().filter(l -> l.contains("keys re-picked for that yaw")).count();
        c.onClient(mc -> {
            Mod.staticCall(EXEC, "stop", "test lane");
            Ap3TermAuraCases.setChain();
            return null;
        });
        return new Run(ticks, counts[0], counts[1], turnOnly, turns, repicks);
    }

    /** The tick whose look is farthest off the walk's yaw, and that angle. */
    private static double[] widestTurn(List<double[]> ticks) {
        double best = 0;
        int at = -1;
        for (int i = 0; i < ticks.size(); i++) {
            double d = Math.abs(net.minecraft.util.Mth.wrapDegrees(ticks.get(i)[3] - WALK_YAW));
            if (d > best) {
                best = d;
                at = i;
            }
        }
        return new double[]{at, best};
    }

    static void run(Session c, boolean sameUse) throws Exception {
        List<AutoCloseable> undo = new ArrayList<>();
        try {
            LightsDeviceCases.baseline(c, undo);
            c.server().command("forceload add 10 130 46 146");
            c.server().sync(20);
            c.server().command("fill 10 131 132 46 131 146 minecraft:stone");
            c.server().command("fill 10 132 132 46 138 146 minecraft:air");
            c.server().sync(20);
            TerminalAuraCases.tp(c, 36.2, Y, 141.8, WALK_YAW);
            c.ctx().waitTicks(10);
            if (sameUse) {
                SameTickCases.sticks(c);
            }
            c.hx().sidebar("SKYBLOCK", "The Catac§combs §7(F7)");
            c.waitUntil("DungeonState F7", mc -> Boolean.TRUE.equals(Mod.staticCall("secrets.DungeonState", "isF7OrM7")), 100);
            undo.add(c.onClient(mc -> Mod.with(CU, "AuraBossLevers", true)));
            undo.add(c.onClient(mc -> Mod.with(LA, "SectionLevers", true)));
            undo.add(c.onClient(mc -> Mod.with(LA, "SectionLeversEarly", true)));
            undo.add(c.onClient(mc -> Mod.with(LA, "Range", 4.5)));
            undo.add(c.onClient(mc -> Mod.with(LA, "CooldownMs", 50)));
            undo.add(c.onClient(mc -> Mod.with("ap3.Ap3Config", "Enabled", true)));
            c.onClient(mc -> {
                Mod.staticCall("ap3.Ap3Feature", "setForceDungeon", true);
                return null;
            });
            Run off = runOnce(c, false, sameUse);
            Run on = runOnce(c, true, sameUse);
            int n = Math.min(off.ticks().size(), on.ticks().size());
            // Positions must match exactly. Looks are float sums of the same turns from start yaws one 360 apart, so they may
            // differ in the last bits (0.01 degree seen at the lever aim): a look within 0.05 degree is the same look.
            int firstDiff = -1;
            String diff = "";
            double lookDiff = 0;
            int lookDiffAt = -1;
            for (int i = 0; i < n; i++) {
                double[] a = off.ticks().get(i);
                double[] b = on.ticks().get(i);
                for (int k = 0; k < 5; k++) {
                    boolean nan = Double.isNaN(a[k]) != Double.isNaN(b[k]);
                    double d = Math.abs(a[k] - b[k]);
                    if (k >= 3 && !nan && d > lookDiff) {
                        lookDiff = d;
                        lookDiffAt = i;
                    }
                    if (firstDiff < 0 && (nan || d > (k < 3 ? 1e-6 : 0.05))) {
                        firstDiff = i;
                        diff = String.format(Locale.ROOT, "tick %d: off %.5f %.5f %.5f yaw %.3f pitch %.3f / on %.5f %.5f %.5f yaw %.3f"
                                + " pitch %.3f", i, a[0], a[1], a[2], a[3], a[4], b[0], b[1], b[2], b[3], b[4]);
                    }
                }
            }
            diff += String.format(Locale.ROOT, "; largest look difference %.4f deg at tick %d", lookDiff, lookDiffAt);
            int leverTick = -1;
            for (int i = 0; i < off.ticks().size(); i++) {
                if (off.ticks().get(i)[5] > 0) {
                    leverTick = i;
                    break;
                }
            }
            // The lever turn: the look off the walk's yaw in the ticks around the click (the align's own turn is earlier).
            int from = Math.max(0, leverTick - 3), to = Math.max(from, leverTick + 2);
            double[] wOff = widestTurn(off.ticks().subList(Math.min(from, off.ticks().size()), Math.min(to, off.ticks().size())));
            double[] wOn = widestTurn(on.ticks().subList(Math.min(from, on.ticks().size()), Math.min(to, on.ticks().size())));
            wOff[0] += from;
            wOn[0] += from;
            double endX = n == 0 ? Double.NaN : off.ticks().get(n - 1)[0];
            c.note(String.format(Locale.ROOT, "ticks off/on %d/%d, compared %d (walk ended at x %.2f); lever clicks off/on %d/%d"
                            + " (click at tick %d); turn-only flicks off/on %d/%d; widest look off the walk off %.1f deg at tick %d, on"
                            + " %.1f deg at tick %d; Lever Aura turn lines off/on %d/%d, AP3 re-picks off/on %d/%d; uses off/on %d/%d;"
                            + " first difference: %s", off.ticks().size(), on.ticks().size(), n, endX, off.leverClicks(), on.leverClicks(),
                    leverTick, off.turnOnly(), on.turnOnly(), wOff[1], (int) wOff[0], wOn[1], (int) wOn[0], off.turnLines(),
                    on.turnLines(), off.repickLines(), on.repickLines(), off.uses(), on.uses(), (firstDiff < 0 ? "none" : "") + diff));
            c.check(n >= 40 && endX < 20, "the walk did not run past the lever (" + n + " ticks, ended at x " + endX + ")");
            c.check(off.leverClicks() == 1, "lever off: " + off.leverClicks() + " lever clicks on the wire, want 1");
            c.check(on.leverClicks() == 0, "lever on: " + on.leverClicks() + " lever clicks on the wire, want 0");
            c.check(on.turnOnly() == 1 && off.turnOnly() == 0, "turn-only flicks off/on " + off.turnOnly() + "/" + on.turnOnly()
                    + ", want 0/1 (the turn for the activated lever did not act)");
            c.check(wOff[1] > 10 && wOn[1] > 10, String.format(Locale.ROOT, "the body never turned toward the lever on the wire"
                    + " (widest look off the walk: off %.1f, on %.1f degrees)", wOff[1], wOn[1]));
            c.check(off.repickLines() > 0 && on.repickLines() > 0, "AP3 never re-picked its keys for the lever turn (off/on "
                    + off.repickLines() + "/" + on.repickLines() + ")");
            c.check(firstDiff < 0, "the two lever states walked differently: " + diff);
            if (sameUse) {
                c.check(off.uses() > 20 && on.uses() > 20, "his use did not go every tick (uses off/on " + off.uses() + "/" + on.uses() + ")");
                c.check(off.ticks().get(leverTick < 0 ? 0 : leverTick)[6] > 0, "his use did not go in the lever click's tick");
            }
        } finally {
            c.onClient(mc -> {
                try {
                    Mod.staticCall(EXEC, "stop", "test end");
                    Ap3TermAuraCases.setChain();
                    Mod.staticCall("ap3.Ap3Feature", "setForceDungeon", false);
                } catch (Throwable t) {
                    System.out.println("[leverturn1115] AP3 reset failed: " + t);
                }
                return null;
            });
            WireCapture.stop();
            WireCapture.describe = null;
            c.server().command("kill @e[tag=leverturn1115]");
            c.server().command("fill 10 131 132 46 138 146 minecraft:air");
            c.server().command("forceload remove 10 130 46 146");
            LightsDeviceCases.cleanup(c, undo);
        }
    }
}
