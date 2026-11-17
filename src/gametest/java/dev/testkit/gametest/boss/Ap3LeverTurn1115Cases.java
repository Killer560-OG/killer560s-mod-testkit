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
 * 1115-ap3-leverturn-* / 1116-leverfreeze-* / 1115-ap3-leveralways-* (cheat jar), rewritten for ap3auralook-1116: while AP3
 * runs no aura turns the body or the camera. Lever Aura's aim rides ONE movement packet of a tick with no movement input
 * (util/PacketLook, here the STOP's slide beside the high S2 lever at 23,132,138) and the click goes at the next tick's
 * start; an activated lever gets nothing (the ap3leverturn-1115 turn-only flick is gone).
 * <p>
 * The same short AP3 walk to a STOP beside the lever runs twice from the same spot, lever off (with its "Not Activated"
 * stand) and lever on (no stand). Judged on the wire: every tick's position is the same in both runs and every look that
 * is not the aim packet too, the first run sent one click on the lever while AP3 still ran and the second none, the click
 * came the tick after a move packet aimed off the walk (the aim really went out), the body and the camera held the walk's
 * yaw at every tick end, and Lever Aura logged no body turn. GrimAC silent (Session.test). The -same-use cases put his own
 * right click (a stick's use_item) into every other tick, so the aim waits for a tick without it and the click shares a
 * tick with his use; -same-swap a hotbar swap into every tick of the retry walk.
 */
final class Ap3LeverTurn1115Cases {

    private static final String EXEC = "ap3.Ap3Executor";
    private static final String LA = "leveraura.LeverAuraConfig";
    private static final String CU = "cheatutils.CheatUtilsConfig";
    private static final double Y = 132;
    private static final int LX = 23, LY = 132, LZ = 138;
    private static final float WALK_YAW = 90f;
    private static final double START_X = 36.2, START_Z = 141.8;

    private Ap3LeverTurn1115Cases() {
    }

    static void register(Session s) {
        if (!Mod.isCheat()) {
            return;
        }
        s.testExpectingFlags("1115-ap3-leverturn-states", c -> run(c, null, false));
        s.testExpectingFlags("1115-ap3-leverturn-same-use", c -> run(c, SameTick.Act.USE, false));
        s.testExpectingFlags("1116-leverfreeze-ap3-states", c -> run(c, null, false));
        s.testExpectingFlags("1116-leverfreeze-ap3-same-use", c -> run(c, SameTick.Act.USE, false));
        // ap3leveralways-1115: the lever already on is the one AP3 flicked itself earlier in the same run (a retry).
        s.testExpectingFlags("1115-ap3-leveralways-retry", c -> run(c, null, true));
        s.testExpectingFlags("1115-ap3-leveralways-retry-same-use", c -> run(c, SameTick.Act.USE, true));
        s.testExpectingFlags("1115-ap3-leveralways-same-swap", c -> run(c, SameTick.Act.SWAP, true));
        // ap3auralook-1116: the same boss lever clicked by Secret Aura (its In Boss half) instead of Lever Aura.
        s.testExpectingFlags("1116-ap3aura-secret-bosslever", c -> runSecret(c, null));
        s.testExpectingFlags("1116-ap3aura-secret-bosslever-same-use", c -> runSecret(c, SameTick.Act.USE));
    }

    /** True while a case runs the lever through Secret Aura (Lever Aura's Section Levers off, so it does not own it). */
    private static boolean secret;
    private static long secretPacketClicks;

    private static void runSecret(Session c, SameTick.Act sameAct) throws Exception {
        secret = true;
        try {
            run(c, sameAct, false);
        } finally {
            secret = false;
        }
    }

    /** A body turn while AP3 ran: Lever Aura's, or TurnFirst's (Secret Aura) unless it clicked by the one-packet look. */
    private static boolean turnLine(String l) {
        return l.contains("[LeverAura] turning body") || l.contains("[TurnFirst] hand-back")
                || l.contains("[TurnFirst] click on") && !l.contains("one-packet look") && !l.contains("body untouched");
    }

    /** {widest camera yaw off the walk, widest body yaw off the walk} at the tick ends of the last runOnce, after the align. */
    private static double[] lastCam = {0, 0};

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
        return Ap3AuraLook1116Cases.describeMovement(p);
    }

    /** One run's wire: per client tick {x, y, z, yaw, pitch, lever clicks, uses}, from the tick he first moved. */
    private record Run(List<double[]> ticks, int leverClicks, int uses, long turnLines, List<String> lines) {
    }

    private static List<double[]> ticksFromWire(int[] counts) {
        List<double[]> out = new ArrayList<>();
        double[] cur = {Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN};
        int clicks = 0;
        int uses = 0;
        double input = 0, cmds = 0;
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
            } else if (t.startsWith("input ")) {
                input = Double.parseDouble(t.substring(6));
            } else if (t.startsWith("cmd ")) {
                cmds = cmds * 16 + Double.parseDouble(t.substring(4));
            } else if (t.equals("end")) {
                out.add(new double[]{cur[0], cur[1], cur[2], cur[3], cur[4], clicks, uses, input, cmds});
                clicks = 0;
                uses = 0;
                cmds = 0;
            }
        }
        // From the first tick he is off the tp spot (a tick with no position sent yet still stands there).
        int first = 0;
        while (first < out.size() && (Double.isNaN(out.get(first)[0]) || Math.abs(out.get(first)[0] - START_X) < 1e-6
                && Math.abs(out.get(first)[2] - START_Z) < 1e-6)) {
            first++;
        }
        int c = 0, u = 0;
        for (double[] d : out) {
            c += (int) d[5];
            u += (int) d[6];
        }
        counts[0] = c;
        counts[1] = u;
        return new ArrayList<>(out.subList(first, out.size()));
    }

    private static Run runOnce(Session c, boolean leverOn, SameTick.Act sameAct, boolean fresh) {
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
        TerminalAuraCases.tp(c, START_X, Y, START_Z, WALK_YAW);
        c.ctx().waitTicks(10);
        // A fresh run each time: Lever Aura forgets the levers it flicked and its cooldown (resetRun). A retry keeps it.
        if (fresh) {
            LightsDeviceCases.newRun(c);
            LightsDeviceCases.openS2(c);
        }
        c.ctx().waitTicks(20);
        boolean powered = c.onClient(mc -> {
            var st = mc.level.getBlockState(new net.minecraft.core.BlockPos(LX, LY, LZ));
            return st.getBlock() instanceof net.minecraft.world.level.block.LeverBlock
                    && st.getValue(net.minecraft.world.level.block.LeverBlock.POWERED);
        });
        c.check(powered == leverOn, "the lever is " + (powered ? "on" : "off") + " before the run, wanted " + (leverOn ? "on" : "off"));
        Object align = node("FAST_ALIGN", 35.5, 141.5, 0f, 2.0);
        Object run = node("RUN", 35.5, 141.5, WALK_YAW, 1.0);
        // The STOP's slide ends about 3.3 blocks from the lever (Range 4.5): its keyless ticks carry the aim.
        Object stop = node("STOP", 25.5, 141.5, WALK_YAW, 1.0);
        long mark = LogTap.mark();
        // His use every other tick (every tick would leave no tick the aim may ride: a use_item carries the live look).
        SameTick st = sameAct == null ? null : sameAct == SameTick.Act.USE
                // Secret Aura sends ONE lever click a walk, so his use goes every tick there: with every other tick the click
                // fell on a tick without his use about one run in two (26.2 round 6: SHARED 0, nothing tested).
                ? c.onClient(mc -> SameTick.start(sameAct, secret ? 1 : 2, p -> p instanceof ServerboundUseItemOnPacket, 4, 5))
                : SameTickCases.start(c, sameAct, p -> p instanceof ServerboundUseItemOnPacket);
        WireCapture.describe = Ap3LeverTurn1115Cases::describe;
        WireCapture.start();
        c.onClient(mc -> {
            Ap3TermAuraCases.setChain(align, run, stop);
            return null;
        });
        double[] cam = {0, 0};
        boolean settled = false;
        for (int t = 0; t < 220; t++) {
            c.ctx().waitTick();
            Object[] now = c.onClient(mc -> new Object[]{mc.player.getYRot(), mc.player.getViewYRot(1f), Mod.staticCall(EXEC, "isRunning")});
            boolean running = (Boolean) now[2];
            if (t > 5 && !running) {
                break;
            }
            double bodyOff = Math.abs(net.minecraft.util.Mth.wrapDegrees((Float) now[0] - WALK_YAW));
            settled |= bodyOff < 0.5;
            if (settled) {
                cam[0] = Math.max(cam[0], Math.abs(net.minecraft.util.Mth.wrapDegrees((Float) now[1] - WALK_YAW)));
                cam[1] = Math.max(cam[1], bodyOff);
            }
        }
        // Lever Aura's turn lines are counted while AP3 ran only: once it stops the aura may turn as it always did.
        List<String> lines = LogTap.since(mark);
        long turns = lines.stream().filter(Ap3LeverTurn1115Cases::turnLine).count();
        if (secret && !leverOn) {
            secretPacketClicks = lines.stream().filter(l -> l.contains("[TurnFirst] click on")
                    && (l.contains("one-packet look") || l.contains("body untouched"))).count();
        }
        WireCapture.stop(); // clicks are counted while AP3 ran (a click after it may turn the body as it always did)
        WireCapture.describe = null;
        c.ctx().waitTicks(5);
        lastCam = cam;
        if (st != null) {
            if (leverOn) {
                SameTick s2 = st;
                c.onClient(mc -> {
                    s2.close();
                    return null;
                });
            } else {
                SameTickCases.judge(c, st, "lever click with his " + sameAct);
            }
        }
        int[] counts = new int[2];
        List<double[]> ticks = ticksFromWire(counts);
        c.onClient(mc -> {
            Mod.staticCall(EXEC, "stop", "test lane");
            Ap3TermAuraCases.setChain();
            return null;
        });
        return new Run(ticks, counts[0], counts[1], turns, lines);
    }

    private static boolean aimed(double[] t) {
        return Math.abs(net.minecraft.util.Mth.wrapDegrees(t[3] - WALK_YAW)) > 10;
    }

    /** The first tick of the RUN (past x 34.5, the align is done by then). */
    private static int runStart(List<double[]> ticks) {
        for (int i = 0; i < ticks.size(); i++) {
            if (ticks.get(i)[0] < 34.5) {
                return i;
            }
        }
        return -1;
    }

    static void run(Session c, SameTick.Act sameAct, boolean retry) throws Exception {
        List<AutoCloseable> undo = new ArrayList<>();
        try {
            LightsDeviceCases.baseline(c, undo);
            c.server().command("forceload add 10 130 46 146");
            c.server().sync(20);
            c.server().command("fill 10 131 132 46 131 146 minecraft:stone");
            c.server().command("fill 10 132 132 46 138 146 minecraft:air");
            c.server().sync(20);
            TerminalAuraCases.tp(c, START_X, Y, START_Z, WALK_YAW);
            c.ctx().waitTicks(10);
            if (sameAct == SameTick.Act.USE) {
                SameTickCases.sticks(c);
            }
            c.hx().sidebar("SKYBLOCK", "The Catac§combs §7(F7)");
            c.waitUntil("DungeonState F7", mc -> Boolean.TRUE.equals(Mod.staticCall("secrets.DungeonState", "isF7OrM7")), 100);
            undo.add(c.onClient(mc -> Mod.with(CU, "AuraBossLevers", true)));
            undo.add(c.onClient(mc -> Mod.with(LA, "SectionLevers", !secret)));
            undo.add(c.onClient(mc -> Mod.with(LA, "SectionLeversEarly", !secret)));
            if (secret) {
                undo.add(c.onClient(mc -> Mod.with(CU, "SecretAuraEnabled", true)));
                undo.add(c.onClient(mc -> Mod.with(CU, "AuraRange", 4.5)));
                undo.add(c.onClient(mc -> Mod.with(CU, "AuraCooldownMs", 100)));
                secretPacketClicks = 0;
            }
            undo.add(c.onClient(mc -> Mod.with(LA, "Range", 4.5)));
            undo.add(c.onClient(mc -> Mod.with(LA, "CooldownMs", 50)));
            undo.add(c.onClient(mc -> Mod.with("ap3.Ap3Config", "Enabled", true)));
            c.onClient(mc -> {
                Mod.staticCall("ap3.Ap3Feature", "setForceDungeon", true);
                return null;
            });
            // A warm-up walk first, not compared: the session's first AP3 walk once started from a different look (the server
            // had heard no rotation yet), one tick out of step with every later walk (experimental run, 11-15).
            runOnce(c, true, null, true);
            int flags0 = c.scenario().flags().size();
            Run off = runOnce(c, false, sameAct, true);
            double[] camOff = lastCam;
            // retry: no new run, so Lever Aura still holds the lever AP3 flicked in the walk above.
            Run on = runOnce(c, true, sameAct, !retry);
            double[] camOn = lastCam;
            int aOff = runStart(off.ticks()), aOn = runStart(on.ticks());
            c.check(aOff >= 0 && aOn >= 0, "the RUN never started (off tick " + aOff + ", on tick " + aOn + ")");
            if (aOff < 0 || aOn < 0) {
                return;
            }
            List<double[]> offT = off.ticks().subList(aOff, off.ticks().size());
            List<double[]> onT = on.ticks().subList(aOn, on.ticks().size());
            int n = Math.min(offT.size(), onT.size());
            // The owner's rule (11-16): positions, keys, sprint commands and the packet yaw on every keys tick match tick for
            // tick; a pitch-only look, or a full aim on a keyless tick, is allowed.
            String diff = Ap3AuraLook1116Cases.movementDiff(offT, onT, n);
            int firstDiff = diff.isEmpty() ? -1 : 0;
            int leverTick = -1;
            for (int i = 0; i < off.ticks().size(); i++) {
                if (off.ticks().get(i)[5] > 0) {
                    leverTick = i;
                    break;
                }
            }
            int aimOff = 0, aimOn = 0;
            for (double[] t : offT) {
                aimOff += aimed(t) ? 1 : 0;
            }
            for (double[] t : onT) {
                aimOn += aimed(t) ? 1 : 0;
            }
            boolean aimBefore = leverTick > 0 && aimed(off.ticks().get(leverTick - 1));
            double endX = n == 0 ? Double.NaN : offT.get(n - 1)[0];
            c.note(String.format(Locale.ROOT, "ticks off/on %d/%d, compared %d (walk ended at x %.2f); lever clicks off/on %d/%d"
                            + " (click at tick %d, aim packet the tick before: %b); aimed move packets off/on %d/%d; camera / body off"
                            + " the walk at most %.3f / %.3f deg (lever off), %.3f / %.3f (on); Lever Aura turn lines off/on %d/%d;"
                            + " uses off/on %d/%d; first difference: %s", off.ticks().size(), on.ticks().size(), n, endX,
                    off.leverClicks(), on.leverClicks(), leverTick, aimBefore, aimOff, aimOn, camOff[0], camOff[1], camOn[0],
                    camOn[1], off.turnLines(), on.turnLines(), off.uses(), on.uses(), firstDiff < 0 ? "none" : diff));
            c.check(n >= 10 && endX < 28, "the walk did not reach the lever (" + n + " ticks, ended at x " + endX + ")");
            c.check(off.leverClicks() == 1, "lever off: " + off.leverClicks() + " lever clicks while AP3 ran, want 1");
            c.check(on.leverClicks() == 0, "lever on: " + on.leverClicks() + " lever clicks while AP3 ran, want 0");
            // The click's look: a full aim the tick before (no keys), a pitch-only look, or none with keys held (step 4).
            long how = off.lines().stream().filter(l -> l.contains("one-packet look") || l.contains("body untouched")).count();
            c.note("lever click lines: " + off.lines().stream().filter(l -> l.contains("[TurnFirst] click on")
                    || l.contains("[LeverAura] click")).toList());
            c.check(aimBefore || how > 0, "the lever click came with no aim and no keys-held click line (nothing tested)");
            c.check(aimOn == 0, "lever on: " + aimOn + " move packet(s) aimed off the walk for an activated lever");
            c.check(camOff[0] < 0.5 && camOn[0] < 0.5, "the camera left the walk's yaw: " + camOff[0] + " / " + camOn[0] + " degrees");
            c.check(camOff[1] < 0.5 && camOn[1] < 0.5, "the body left the walk's yaw at a tick end: " + camOff[1] + " / " + camOn[1] + " degrees");
            c.check(off.turnLines() == 0 && on.turnLines() == 0, "Lever Aura turned the body while AP3 ran (lines off/on "
                    + off.turnLines() + "/" + on.turnLines() + ")");
            c.check(firstDiff < 0, "the lever aura changed the AP3 walk's movement: " + diff);
            c.ctx().waitTicks(20);
            List<String> all = new ArrayList<>(off.lines());
            all.addAll(on.lines());
            Ap3AuraLook1116Cases.stepFour(c, flags0, all, secret ? "Secret Aura on the boss lever" : "Lever Aura on the walk");
            if (secret) {
                c.note("Secret Aura one-packet-look clicks (lever off): " + secretPacketClicks + "; in boss "
                        + c.onClient(mc -> Mod.staticCall("livemap.LiveMapFeature", "isInBoss")));
                c.check(secretPacketClicks == 1, "Secret Aura did not click the boss lever by its one-packet look ("
                        + secretPacketClicks + " lines): the lever click was not Secret Aura's");
            }
            if (sameAct == SameTick.Act.USE) {
                c.check(off.uses() > 10 && on.uses() > 10, "his use did not go (uses off/on " + off.uses() + "/" + on.uses() + ")");
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
