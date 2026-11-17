package dev.testkit.gametest.boss;

import com.google.gson.JsonObject;

import dev.testkit.gametest.SameTick;
import dev.testkit.gametest.hx.HxKit;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.WireCapture;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ap3auralook-1116 (killer560, 11-16: "the look towards stuff for auras keeps messing things up. for instance at the dev itll
 * look to solve it and that breaks the ap3" and "i really need a way for auras to get things without rotating").
 * <ul>
 *   <li>1116-ap3aura-arrows: an AP3 walk past the arrows device (frames as AaFastCases lays them), run with Arrow Align's
 *       Aura off and then on. While AP3 runs the Aura clicks with no turn at all: every tick's position and look on the
 *       wire must be the same in both runs, the Aura must click during the walk and the device must end solved. GrimAC
 *       silent. The -same-* cases put his own action (use, hotbar swap, attack, inventory click, teleport accept) into the
 *       walk's ticks.</li>
 *   <li>1116-ap3aura-term-beside: an AP3 walk past a terminal beside the line with Terminal Aura and Turn To Terminal on:
 *       no turn; the aim may ride only the movement packet of a still tick (PacketLook), so the positions are the same
 *       tick for tick as with the aura off.</li>
 *   <li>1116-nolook-probe (expecting flags): every way of giving a click its look (none, the click tick's own movement
 *       packet, pitch only, an extra Rot, an extra same-position PosRot, the tick before) x the three click kinds (item
 *       frame interact, lever use_item_on, terminal stand interact), standing and moving: what the server did and which
 *       GrimAC checks flagged, as a table (build/testkit-report/nolook-probe-1116.txt).</li>
 * </ul>
 */
final class Ap3AuraLook1116Cases {

    private static final String EXEC = "ap3.Ap3Executor";
    private static final String AA = "arrowalign.ArrowAlignConfig";
    private static final double Y = 120;
    private static final double START_X = -0.5, START_Z = 69.5;

    private Ap3AuraLook1116Cases() {
    }

    static void register(Session s) {
        if (!Mod.isCheat()) {
            return; // AP3 is cheat-only
        }
        s.test("1116-ap3aura-arrows", c -> arrows(c, null));
        // No OPEN_CLICK: an inventory click while AP3 sprints is GrimAC MultiActionsC from his own click with the Aura
        // off too (r2: x31, sprinting=true input=true in both walks), so it cannot judge the Aura. No ATTACK either: his own
        // attack on the stands drew Hitboxes in the Aura-off walk as much as in the Aura-on one (r3, AP3's packet yaw is not
        // the camera's crosshair; WORKQUEUE ap3attack-hitboxes); his attack over an aura's click is in the term and lever cases.
        for (SameTick.Act a : new SameTick.Act[]{SameTick.Act.USE, SameTick.Act.SWAP, SameTick.Act.TP}) {
            s.test("1116-ap3aura-arrows-same-" + a.name().toLowerCase(Locale.ROOT), c -> arrows(c, a));
        }
        s.test("1116-ap3aura-term-beside", Ap3AuraLook1116Cases::termBeside);
        s.test("1116-ap3aura-term-still", Ap3AuraLook1116Cases::termStill);
        s.testExpectingFlags("1116-nolook-probe", Ap3AuraLook1116Cases::probe);
        s.testExpectingFlags("1116-movingclick-probe", Ap3AuraLook1116Cases::movingProbe);
    }

    // ---- shared -----------------------------------------------------------------------------------------------------

    private static Object node(String type, double x, double y, double z, float yaw, double size) {
        try {
            Class<?> cls = Mod.cls(Ap3TermAuraCases.NODE);
            Object n = cls.getConstructor(Mod.cls(Ap3TermAuraCases.TYPE), double.class, double.class, double.class, float.class,
                    float.class).newInstance(Mod.enumValue(Ap3TermAuraCases.TYPE, type), x, y, z, yaw, 0f);
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
        if (p instanceof ServerboundInteractPacket) {
            return "interact";
        }
        if (p instanceof ServerboundClientTickEndPacket) {
            return "end";
        }
        return p.getClass().getSimpleName();
    }

    /** Per client tick {x, y, z, yaw, pitch, interacts, apRunning}, from the first tick he left (sx, sz). */
    private static List<double[]> ticksFromWire(double sx, double sz) {
        List<double[]> out = new ArrayList<>();
        double[] cur = {Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN};
        int interacts = 0;
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
            } else if (t.equals("interact")) {
                interacts++;
            } else if (t.equals("end")) {
                out.add(new double[]{cur[0], cur[1], cur[2], cur[3], cur[4], interacts, 0});
                interacts = 0;
            }
        }
        int first = 0;
        while (first < out.size() && (Double.isNaN(out.get(first)[0])
                || Math.abs(out.get(first)[0] - sx) < 1e-6 && Math.abs(out.get(first)[2] - sz) < 1e-6)) {
            first++;
        }
        return new ArrayList<>(out.subList(Math.max(0, first - 1), out.size()));
    }

    /** The first tick where two walks differ (moved distance since tick 0 over 0.005, look over 0.05 deg), or "". */
    private static String firstDiff(List<double[]> a, List<double[]> b, int n) {
        return firstDiff(a, b, n, 5);
    }

    /** {@code kmax} 3: positions only (a one-packet look on a still tick moves no position); 5: the look too. */
    private static String firstDiff(List<double[]> a, List<double[]> b, int n, int kmax) {
        int s0 = 0;
        while (s0 < n && (Double.isNaN(a.get(s0)[0]) || Double.isNaN(b.get(s0)[0]))) {
            s0++;
        }
        if (s0 >= n) {
            return "no position on the wire";
        }
        double[] a0 = a.get(s0), b0 = b.get(s0);
        for (int i = s0; i < n; i++) {
            double[] p = a.get(i), q = b.get(i);
            for (int k = 0; k < kmax; k++) {
                if (Double.isNaN(p[k]) && Double.isNaN(q[k])) {
                    continue; // nothing sent yet in either walk
                }
                double d = k < 3 ? Math.abs((p[k] - a0[k]) - (q[k] - b0[k])) : Math.abs(Mth.wrapDegrees(p[k] - q[k]));
                if (Double.isNaN(d) || d > (k < 3 ? 0.005 : 0.05)) {
                    return String.format(Locale.ROOT, "tick %d: off %.4f %.4f %.4f yaw %.3f pitch %.3f / on %.4f %.4f %.4f yaw %.3f"
                            + " pitch %.3f", i, p[0], p[1], p[2], p[3], p[4], q[0], q[1], q[2], q[3], q[4]);
                }
            }
        }
        return "";
    }

    private static void stopAp3(Session c) {
        c.onClient(mc -> {
            Mod.staticCall(EXEC, "stop", "test lane");
            Ap3TermAuraCases.setChain();
            return null;
        });
    }

    // ---- 1116-ap3aura-arrows ----------------------------------------------------------------------------------------

    private record Walk(List<double[]> ticks, int interactsWhileRunning, int interacts, int[] rot, int apTicks) {
    }

    /**
     * South along x -0.5 toward the frames (x -2, z 75-79), a bend toward +x at z 73.5 and back at z 75.6, then a STOP
     * beside the device (z 77.8), where the Aura can finish.
     * The frames need clicks in both walks; only the second has the Aura on.
     */
    private static Walk walk(Session c, boolean aura, SameTick.Act act, List<String> sameNotes) {
        stopAp3(c);
        c.onClient(mc -> {
            Mod.set(AA, "setAuraEnabled", false);
            return null;
        });
        TerminalAuraCases.tp(c, START_X, Y, START_Z, 0f);
        Object[] g = AaFastCases.grid(c, AaFastCases.NEEDS);
        String[] uuids = (String[]) g[1];
        if (act == SameTick.Act.ATTACK) {
            // On the walk's line ahead (south), inside attack reach for a stretch of the walk.
            c.server().command("kill @e[tag=aalook1116]");
            for (double sx = -1.45; sx < 0.8; sx += 0.5) {
                c.server().command(String.format(Locale.ROOT,
                        "summon armor_stand %.2f 120 79.8 {NoGravity:1b,Invulnerable:1b,Tags:[\"aalook1116\"]}", sx));
            }
        }
        c.ctx().waitTicks(10);
        Object align = node("FAST_ALIGN", START_X, Y, START_Z, 0f, 2.0);
        Object r1 = node("RUN", START_X, Y, START_Z, 0f, 1.0);
        Object r2 = node("RUN", -0.5, Y, 73.5, -25f, 1.0);
        Object r3 = node("RUN", 0.5, Y, 75.6, 25f, 1.0);
        Object stop = node("STOP", -0.4, Y, 77.8, 25f, 1.2);
        long since = c.hx().head();
        SameTick st = act == null ? null : c.onClient(mc -> SameTick.start(act, act == SameTick.Act.ATTACK ? 3 : 2,
                p -> p instanceof ServerboundInteractPacket, 4, 5));
        WireCapture.describe = Ap3AuraLook1116Cases::describe;
        WireCapture.start();
        c.onClient(mc -> {
            Mod.set(AA, "setAuraEnabled", aura);
            Ap3TermAuraCases.setChain(align, r1, r2, r3, stop);
            return null;
        });
        int apTicks = 0;
        boolean started = false;
        int interactsRunning = 0;
        int wireBefore = 0;
        for (int t = 0; t < 200; t++) {
            if (act == SameTick.Act.TP && t % 2 == 0) {
                c.server().command("execute as @a at @s run tp @s ~ ~ ~");
            }
            c.ctx().waitTick();
            boolean run = c.onClient(mc -> (Boolean) Mod.staticCall(EXEC, "isRunning"));
            started |= run;
            if (run) {
                apTicks++;
                int n = (int) WireCapture.snapshot().stream().filter(w -> "interact".equals(w.text())).count();
                interactsRunning += n - wireBefore;
                wireBefore = n;
            } else {
                wireBefore = (int) WireCapture.snapshot().stream().filter(w -> "interact".equals(w.text())).count();
            }
            if (started && !run && t > 20) {
                break;
            }
        }
        // The Aura may finish what is left after the walk (with its turn: AP3 no longer runs).
        c.ctx().waitTicks(aura ? 60 : 10);
        WireCapture.stop();
        WireCapture.describe = null;
        if (st != null && !aura) {
            c.onClient(mc -> {
                st.close();
                return null;
            });
        } else if (st != null) {
            SameTick.judge(c, st, "arrows on the AP3 walk with his " + act,
                    act == SameTick.Act.SWAP || act == SameTick.Act.TP || act == SameTick.Act.OPEN_CLICK || act == SameTick.Act.ATTACK
                            ? SameTick.Expect.ACTED : SameTick.Expect.SHARE);
            sameNotes.add(act + " judged");
        }
        List<double[]> ticks = ticksFromWire(START_X, START_Z);
        int total = 0;
        for (double[] d : ticks) {
            total += (int) d[5];
        }
        int[] rot = new int[uuids.length];
        for (int i = 0; i < uuids.length; i++) {
            rot[i] = AaFastCases.serverRotation(c, uuids[i]);
        }
        c.onClient(mc -> {
            Mod.set(AA, "setAuraEnabled", false);
            return null;
        });
        stopAp3(c);
        c.note(String.format(Locale.ROOT, "walk aura=%s: %d AP3 ticks, %d interacts (%d while AP3 ran), server use_entity %d,"
                        + " rotations %s", aura, apTicks, total, interactsRunning, c.hx().events(since, "use_entity").size(),
                java.util.Arrays.toString(rot)));
        return new Walk(ticks, interactsRunning, total, rot, apTicks);
    }

    static void arrows(Session c, SameTick.Act act) throws Exception {
        try (HxKit.Settings gate = AaFastCases.gateOff(c);
             HxKit.Settings on = new HxKit.Settings(c).with(AA, "SolverEnabled", true)
                     .with(AA, "PreventMisclicksEnabled", false).with(AA, "TriggerBotEnabled", false)
                     .with(AA, "AuraEnabled", false).with(AA, "AuraRange", 3.0).with(AA, "AuraCps", 20)
                     .with(AA, "AuraClicksPerTick", 7).with(AA, "AuraSameTickTurn", true)
                     .with(AA, "AuraLeaveLastArrow", false).with(AA, "AuraDisableWhileCrouching", true)
                     .with("ap3.Ap3Config", "Enabled", true).with("ap3.Ap3Config", "Strafe45", true)) {
            c.hx().sidebar("SKYBLOCK", "The Catac§combs §7(F7)");
            c.waitUntil("DungeonState F7", mc -> Boolean.TRUE.equals(Mod.staticCall("secrets.DungeonState", "isF7OrM7")), 100);
            c.hx().call("dungeon.tp", "x", -619.5, "y", 151.0, "z", -619.5, "yaw", 0f, "pitch", 0f);
            c.ctx().waitTicks(10);
            c.server().command("forceload add -4 64 6 92");
            c.server().sync(20);
            c.server().command("fill -2 119 64 4 119 92 stone");
            c.server().command("fill -2 120 64 4 126 92 air");
            c.server().command("fill -3 119 74 -3 125 80 stone");
            c.onClient(mc -> {
                Mod.staticCall("ap3.Ap3Feature", "setForceDungeon", true);
                return null;
            });
            if (act != null) {
                SameTickCases.sticks(c);
            }
            // Settle AT the walk's start: the first walk came straight from the 600-block dungeon.tp with only 10 ticks for
            // the chunks and drew Simulation .200416 with the Aura off (r3, r4 on 26.3 and 26.2); a settle at -619 did not help.
            TerminalAuraCases.tp(c, START_X, Y, START_Z, 0f);
            c.server().sync(20);
            c.ctx().waitTicks(40);
            List<String> notes = new ArrayList<>();
            walk(c, false, null, notes); // warm-up, not compared (the session's first walk can start a tick out of step)
            Walk off = walk(c, false, act, notes);
            Walk onW = walk(c, true, act, notes);
            int n = Math.min(off.ticks().size(), onW.ticks().size());
            String diff = n == 0 ? "no ticks" : firstDiff(off.ticks(), onW.ticks(), Math.min(n, Math.min(off.apTicks(), onW.apTicks())));
            c.note(String.format(Locale.ROOT, "ticks off/on %d/%d (AP3 %d/%d); interacts while AP3 ran off/on %d/%d; first"
                    + " difference while AP3 ran: %s", off.ticks().size(), onW.ticks().size(), off.apTicks(), onW.apTicks(),
                    off.interactsWhileRunning(), onW.interactsWhileRunning(), diff.isEmpty() ? "none" : diff));
            c.check(off.apTicks() >= 20, "the walk ran only " + off.apTicks() + " ticks");
            c.check(off.interacts() == 0, "the aura-off walk sent " + off.interacts() + " interacts");
            if (!Mod.isCheat()) {
                c.check(onW.interacts() == 0, "a legit jar clicked frames on the walk: " + onW.interacts());
                return;
            }
            boolean moves = act == null || act == SameTick.Act.USE || act == SameTick.Act.SWAP;
            if (moves) {
                c.check(diff.isEmpty(), "the Aura changed the AP3 walk: " + diff);
            }
            c.check(onW.interactsWhileRunning() > 0, "the Aura sent no click while AP3 ran (nothing tested)");
            if (act == null) {
                c.check(java.util.Arrays.stream(onW.rot()).allMatch(r -> r == 1), "the device was not solved: "
                        + java.util.Arrays.toString(onW.rot()));
            }
        } finally {
            stopAp3(c);
            WireCapture.stop();
            WireCapture.describe = null;
            c.onClient(mc -> {
                Mod.staticCall("ap3.Ap3Feature", "setForceDungeon", false);
                return null;
            });
            c.server().command("kill @e[tag=aalook1116]");
            c.hx().removeStands();
            c.server().command("kill @e[type=item_frame,x=-2,y=122,z=77,distance=..6]");
            c.server().command("fill -3 119 64 4 126 92 air");
            c.server().command("forceload remove -4 64 6 92");
            c.hx().sidebarClear();
            c.hx().call("dungeon.tp", "x", -619.5, "y", 151.0, "z", -619.5, "yaw", 0f, "pitch", 0f);
        }
    }

    // ---- 1116-ap3aura-term-beside -----------------------------------------------------------------------------------

    static void termBeside(Session c) throws Exception {
        int x = Ap3TermAuraCases.X, z = Ap3TermAuraCases.Z, g = Ap3TermAuraCases.G;
        List<AutoCloseable> undo = new ArrayList<>();
        try {
            c.hx().call("boss.term.build", "x", x, "z", z, "groundY", g);
            c.server().command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x - 6, g + 2, z - 2, x + 6, g + 2, z - 2));
            c.server().sync(20);
            double px = x - 5.5, py = g + 3, pz = z - 1.5;
            TerminalAuraCases.tp(c, px, py, pz, -90f);
            JsonObject st = c.hx().call("boss.term.spawn").getAsJsonObject();
            int standId = st.get("standId").getAsInt();
            c.waitUntil("the client to have the terminal stand", mc -> mc.level.getEntity(standId) != null, 200);
            c.hx().sidebar("SKYBLOCK", "The Catac§combs §7(F7)");
            c.waitUntil("DungeonState F7", mc -> Boolean.TRUE.equals(Mod.staticCall("secrets.DungeonState", "isF7OrM7")), 100);
            c.hx().chat(TerminalAuraCases.GOLDOR);
            c.waitUntil("Floor7Tracker phase P3",
                    mc -> "P3".equals(String.valueOf(Mod.staticCall(TerminalAuraCases.TRACKER, "getPhase"))), 100);
            String ta = TerminalAuraCases.CONFIG;
            undo.add(c.onClient(mc -> Mod.with(ta, "Enabled", false)));
            undo.add(c.onClient(mc -> Mod.with(ta, "Range", 3.0)));
            undo.add(c.onClient(mc -> Mod.with(ta, "DelayMs", 50)));
            undo.add(c.onClient(mc -> Mod.with(ta, "GroundOnly", false)));
            undo.add(c.onClient(mc -> Mod.with(ta, "LeapDelayEnabled", false)));
            undo.add(c.onClient(mc -> Mod.with(ta, "PauseOnMovementKeys", false)));
            undo.add(c.onClient(mc -> Mod.with(ta, "FovDegrees", 360)));
            undo.add(c.onClient(mc -> Mod.with(ta, "TurnToTerminal", true)));
            undo.add(c.onClient(mc -> Mod.with(Ap3TermAuraCases.AP3_CONFIG, "Enabled", true)));
            c.onClient(mc -> {
                Mod.staticCall(Ap3TermAuraCases.FEATURE, "setForceDungeon", true);
                return null;
            });
            List<double[]>[] runs = new List[2];
            long held = 0;
            int[] clicks = new int[2];
            for (int r = -1; r < 2; r++) { // r = -1: warm-up
                boolean aura = r == 1;
                stopAp3(c);
                TerminalAuraCases.closeScreen(c);
                TerminalAuraCases.tp(c, px, py, pz, -90f);
                c.ctx().waitTicks(10);
                Object align = node("FAST_ALIGN", px, py, pz, -90f, 2.0);
                Object run = node("RUN", px, py, pz, -90f, 1.0);
                Object stop = node("STOP", x + 5.0, py, pz, -90f, 2.0);
                int before = c.events("term.interact").size();
                long mark = dev.testkit.gametest.LogTap.mark();
                WireCapture.describe = Ap3AuraLook1116Cases::describe;
                WireCapture.start();
                c.onClient(mc -> {
                    Mod.set(ta, "setEnabled", aura);
                    Ap3TermAuraCases.setChain(align, run, stop);
                    return null;
                });
                boolean started = false;
                for (int t = 0; t < 160; t++) {
                    c.ctx().waitTick();
                    boolean on = c.onClient(mc -> (Boolean) Mod.staticCall(EXEC, "isRunning"));
                    started |= on;
                    if (started && !on && t > 20) {
                        break;
                    }
                }
                c.ctx().waitTicks(5);
                WireCapture.stop();
                WireCapture.describe = null;
                c.onClient(mc -> {
                    Mod.set(ta, "setEnabled", false);
                    return null;
                });
                if (aura) {
                    held = dev.testkit.gametest.LogTap.since(mark).stream()
                            .filter(l -> l.contains("[TerminalAura] held: AP3 route runs: no turn")).count();
                }
                if (r >= 0) {
                    runs[r] = ticksFromWire(px, pz);
                    clicks[r] = c.events("term.interact").size() - before;
                }
            }
            int n = Math.min(runs[0].size(), runs[1].size());
            String diff = firstDiff(runs[0], runs[1], n, 3);
            String lookDiff = firstDiff(runs[0], runs[1], n, 5);
            c.note("first look difference (a one-packet aim on a still tick is allowed): " + (lookDiff.isEmpty() ? "none" : lookDiff));
            c.note(String.format(Locale.ROOT, "ticks off/on %d/%d; terminal clicks off/on %d/%d; Terminal Aura held for the route"
                    + " %d time(s); first difference: %s", runs[0].size(), runs[1].size(), clicks[0], clicks[1], held,
                    diff.isEmpty() ? "none" : diff));
            c.check(held > 0, "Terminal Aura never had the stand as a target during the walk (nothing tested)");
            c.check(n >= 20, "the walk lasted only " + n + " ticks");
            c.check(diff.isEmpty(), "Terminal Aura changed the AP3 walk: " + diff);
        } finally {
            stopAp3(c);
            WireCapture.stop();
            WireCapture.describe = null;
            TerminalAuraCases.closeScreen(c);
            c.onClient(mc -> {
                for (int i = undo.size() - 1; i >= 0; i--) {
                    try {
                        undo.get(i).close();
                    } catch (Exception e) {
                        System.out.println("[aalook1116] restore failed: " + e);
                    }
                }
                Mod.staticCall(Ap3TermAuraCases.FEATURE, "setForceDungeon", false);
                return null;
            });
            c.hx().call("boss.term.clear");
            c.hx().sidebarClear();
            c.hx().call("dungeon.tp", "x", -619.5, "y", 151.0, "z", -619.5, "yaw", 0f, "pitch", 0f);
        }
    }

    // ---- 1116-nolook-probe ------------------------------------------------------------------------------------------

    /** Flag lines added since {@code from}, joined. */
    // ---- 1116-ap3aura-term-still ------------------------------------------------------------------------------------

    /**
     * The route stops beside a terminal (STOP, then a TERMINAL node that waits) with the stand 53 degrees off his look:
     * Terminal Aura must open it with the aim in ONE movement packet of a still tick (PacketLook) while his camera stays
     * at -90 every tick and AP3 still runs. Proves the one-packet look acts; GrimAC silent (session verdict).
     */
    static void termStill(Session c) throws Exception {
        int x = Ap3TermAuraCases.X, z = Ap3TermAuraCases.Z, g = Ap3TermAuraCases.G;
        List<AutoCloseable> undo = new ArrayList<>();
        try {
            c.hx().call("boss.term.build", "x", x, "z", z, "groundY", g);
            c.server().command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x - 6, g + 2, z - 2, x + 6, g + 2, z - 2));
            c.server().sync(20);
            double px = x - 5.5, py = g + 3, pz = z - 1.5;
            TerminalAuraCases.tp(c, px, py, pz, -90f);
            JsonObject st = c.hx().call("boss.term.spawn").getAsJsonObject();
            int standId = st.get("standId").getAsInt();
            c.waitUntil("the client to have the terminal stand", mc -> mc.level.getEntity(standId) != null, 200);
            c.hx().sidebar("SKYBLOCK", "The Catac§combs §7(F7)");
            c.waitUntil("DungeonState F7", mc -> Boolean.TRUE.equals(Mod.staticCall("secrets.DungeonState", "isF7OrM7")), 100);
            c.hx().chat(TerminalAuraCases.GOLDOR);
            c.waitUntil("Floor7Tracker phase P3",
                    mc -> "P3".equals(String.valueOf(Mod.staticCall(TerminalAuraCases.TRACKER, "getPhase"))), 100);
            String ta = TerminalAuraCases.CONFIG;
            undo.add(c.onClient(mc -> Mod.with(ta, "Enabled", false)));
            undo.add(c.onClient(mc -> Mod.with(ta, "Range", 3.0)));
            undo.add(c.onClient(mc -> Mod.with(ta, "DelayMs", 50)));
            undo.add(c.onClient(mc -> Mod.with(ta, "GroundOnly", false)));
            undo.add(c.onClient(mc -> Mod.with(ta, "LeapDelayEnabled", false)));
            undo.add(c.onClient(mc -> Mod.with(ta, "PauseOnMovementKeys", false)));
            undo.add(c.onClient(mc -> Mod.with(ta, "FovDegrees", 360)));
            undo.add(c.onClient(mc -> Mod.with(ta, "TurnToTerminal", true)));
            undo.add(c.onClient(mc -> Mod.with(Ap3TermAuraCases.AP3_CONFIG, "Enabled", true)));
            c.onClient(mc -> {
                Mod.staticCall(Ap3TermAuraCases.FEATURE, "setForceDungeon", true);
                return null;
            });
            stopAp3(c);
            TerminalAuraCases.closeScreen(c);
            TerminalAuraCases.tp(c, px, py, pz, -90f);
            c.ctx().waitTicks(10);
            double sx = x - 1.0;
            Object align = node("FAST_ALIGN", px, py, pz, -90f, 2.0);
            Object run = node("RUN", px, py, pz, -90f, 1.0);
            Object stop = node("STOP", sx, py, pz, -90f, 1.0);
            Object terminal = node("TERMINAL", sx, py, pz, -90f, 4.0);
            int before = c.events("term.interact").size();
            long mark = dev.testkit.gametest.LogTap.mark();
            WireCapture.describe = Ap3AuraLook1116Cases::describe;
            WireCapture.start();
            c.onClient(mc -> {
                Mod.set(ta, "setEnabled", true);
                Ap3TermAuraCases.setChain(align, run, stop, terminal);
                return null;
            });
            double worstYaw = 0;
            int runningTicks = 0;
            boolean opened = false;
            for (int t = 0; t < 140 && !opened; t++) {
                c.ctx().waitTick();
                Object[] s = c.onClient(mc -> new Object[]{Mod.staticCall(EXEC, "isRunning"), mc.player.getViewYRot(1f)});
                if (Boolean.TRUE.equals(s[0])) {
                    runningTicks++;
                    worstYaw = Math.max(worstYaw, Math.abs(Mth.wrapDegrees((Float) s[1] + 90f)));
                }
                opened = c.events("term.interact").size() > before;
            }
            boolean runningAtClick = c.onClient(mc -> (Boolean) Mod.staticCall(EXEC, "isRunning"));
            c.ctx().waitTicks(3);
            WireCapture.stop();
            WireCapture.describe = null;
            int aimed = 0;
            for (WireCapture.Sent w : WireCapture.snapshot()) {
                String[] f = w.text().split(" ");
                if (w.phase().equals("play") && f[0].equals("move") && !f[4].equals("NaN")
                        && Math.abs(Mth.wrapDegrees(Float.parseFloat(f[4]) + 90f)) > 5f) {
                    aimed++;
                }
            }
            long oneLook = dev.testkit.gametest.LogTap.since(mark).stream()
                    .filter(l -> l.contains("[TerminalAura]") || l.contains("one-packet")).count();
            c.note(String.format(Locale.ROOT, "opened %b with AP3 running %b; AP3 ran %d ticks; camera off -90 by at most %.3f;"
                    + " movement packets aimed off the walk: %d; Terminal Aura log lines %d", opened, runningAtClick,
                    runningTicks, worstYaw, aimed, oneLook));
            c.check(opened, "Terminal Aura never opened the terminal beside the stopped route (the one-packet look did not act)");
            c.check(runningAtClick, "AP3 was no longer running when the terminal opened (nothing tested)");
            c.check(aimed > 0, "no movement packet carried an aim other than the walk's -90 (the click had no look)");
            c.check(worstYaw < 0.01, "his camera turned while AP3 ran: " + worstYaw + " degrees off -90");
        } finally {
            stopAp3(c);
            WireCapture.stop();
            WireCapture.describe = null;
            TerminalAuraCases.closeScreen(c);
            c.onClient(mc -> {
                for (int i = undo.size() - 1; i >= 0; i--) {
                    try {
                        undo.get(i).close();
                    } catch (Exception e) {
                        System.out.println("[aalook1116] restore failed: " + e);
                    }
                }
                Mod.staticCall(Ap3TermAuraCases.FEATURE, "setForceDungeon", false);
                return null;
            });
            c.hx().call("boss.term.clear");
            c.hx().sidebarClear();
            c.hx().call("dungeon.tp", "x", -619.5, "y", 151.0, "z", -619.5, "yaw", 0f, "pitch", 0f);
        }
    }

    private static String flagsSince(Session c, int from) {
        List<String> f = c.scenario().flags();
        return f.size() <= from ? "" : String.join(" | ", f.subList(from, f.size()));
    }

    /**
     * How the click gets its look. NONE: no look at all. PKT: the click goes at the head of this tick's movement packet
     * and that packet alone carries the aim (the live look is put back at the next tick's start, so the movement of every
     * tick is computed on the live look). PITCH: the same with the live yaw kept, only the pitch aimed. ROT: an extra
     * Rot packet with the aim at the tick's start, then the click. POSROT: an extra PosRot at the same position
     * (ground false) with the aim, then the click (the reference capture's form). BEFORE: the previous tick's movement
     * packet carries the aim, the click goes at this tick's start and this tick's packet carries the live look again.
     */
    enum Way { NONE, PKT, PITCH, ROT, POSROT, BEFORE }

    enum Kind { FRAME, LEVER, STAND, LEVER_AHEAD }

    private static volatile java.util.function.Consumer<net.minecraft.client.Minecraft> each;
    private static float[] restore;
    private static Runnable afterRestore;
    private static boolean hooked;

    private static void hook(Session c) {
        c.onClient(mc -> {
            if (!hooked) {
                net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.START_CLIENT_TICK.register(client -> {
                    if (client.player == null) {
                        return;
                    }
                    if (restore != null) {
                        client.player.setYRot(restore[0]);
                        client.player.setXRot(restore[1]);
                        restore = null;
                    }
                    Runnable a = afterRestore;
                    afterRestore = null;
                    if (a != null) {
                        a.run();
                    }
                    var e = each;
                    if (e != null) {
                        e.accept(client);
                    }
                });
                hooked = true;
            }
            return null;
        });
    }

    private static void beforeMove(Runnable r) {
        try {
            Mod.cls("util.BeforeMove").getMethod("queue", Runnable.class).invoke(null, r);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ItemFrame frame(net.minecraft.client.Minecraft mc) {
        return mc.level.getEntitiesOfClass(ItemFrame.class, new AABB(-3, 119, 75, 0, 126, 80)).get(0);
    }

    private static ArmorStand stand(net.minecraft.client.Minecraft mc) {
        return mc.level.getEntitiesOfClass(ArmorStand.class, new AABB(-3, 119, 80, 3, 124, 85)).get(0);
    }

    private static BlockPos leverPos(Kind k) {
        return k == Kind.LEVER ? new BlockPos(-1, 121, 72) : new BlockPos(0, 121, 90);
    }

    private static AABB box(net.minecraft.client.Minecraft mc, Kind k) {
        if (k == Kind.FRAME) {
            return frame(mc).getBoundingBox();
        }
        if (k == Kind.STAND) {
            return stand(mc).getBoundingBox();
        }
        BlockPos p = leverPos(k);
        return mc.level.getBlockState(p).getShape(mc.level, p).bounds().move(p);
    }

    /** Yaw and pitch from his eye to {@code to}; the yaw is the live yaw's nearest equivalent (a running value). */
    private static float[] aimAt(net.minecraft.client.Minecraft mc, Vec3 to) {
        Vec3 eye = mc.player.getEyePosition();
        double dx = to.x - eye.x, dy = to.y - eye.y, dz = to.z - eye.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        float live = mc.player.getYRot();
        return new float[]{live + Mth.wrapDegrees(yaw - live), Mth.clamp(pitch, -90f, 90f)};
    }

    private static void click(net.minecraft.client.Minecraft mc, Kind k) {
        Vec3 eye = mc.player.getEyePosition();
        if (k == Kind.FRAME || k == Kind.STAND) {
            net.minecraft.world.entity.Entity e = k == Kind.FRAME ? frame(mc) : stand(mc);
            AABB b = e.getBoundingBox();
            Vec3 hit = b.clip(eye, b.getCenter()).orElse(b.getCenter());
            mc.gameMode.interact(mc.player, e, new EntityHitResult(e, hit), InteractionHand.MAIN_HAND);
            return;
        }
        BlockPos lp = leverPos(k);
        Vec3 center = box(mc, k).getCenter();
        BlockHitResult hr = mc.level.getBlockState(lp).getShape(mc.level, lp)
                .clip(eye, center.add(center.subtract(eye).normalize()), lp);
        if (hr == null) {
            hr = new BlockHitResult(center, k == Kind.LEVER ? Direction.EAST : Direction.NORTH, lp, false);
        }
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hr);
    }

    /** One click of {@code k} with look {@code w}, from the START of a client tick. */
    private static void fire(net.minecraft.client.Minecraft mc, Way w, Kind k) {
        net.minecraft.client.player.LocalPlayer p = mc.player;
        switch (w) {
            case NONE -> click(mc, k);
            case ROT -> {
                float[] a = aimAt(mc, box(mc, k).getCenter());
                p.connection.send(new ServerboundMovePlayerPacket.Rot(a[0], a[1], p.onGround(), p.horizontalCollision));
                click(mc, k);
            }
            case POSROT -> {
                float[] a = aimAt(mc, box(mc, k).getCenter());
                p.connection.send(new ServerboundMovePlayerPacket.PosRot(p.getX(), p.getY(), p.getZ(), a[0], a[1], false,
                        p.horizontalCollision));
                click(mc, k);
            }
            case PKT, PITCH -> beforeMove(() -> {
                float[] a = aimAt(mc, box(mc, k).getCenter());
                restore = new float[]{p.getYRot(), p.getXRot()};
                p.setYRot(w == Way.PITCH ? p.getYRot() : a[0]);
                p.setXRot(a[1]);
                click(mc, k);
            });
            case BEFORE -> beforeMove(() -> {
                float[] a = aimAt(mc, box(mc, k).getCenter());
                restore = new float[]{p.getYRot(), p.getXRot()};
                p.setYRot(a[0]);
                p.setXRot(a[1]);
                afterRestore = () -> click(mc, k);
            });
        }
    }

    /** What the server did with the click so far: frame rotation, lever state (client, after the server's answer), stand interacts. */
    private static String outcome(Session c, Kind k, String frameUuid, long hxFrom) {
        return switch (k) {
            case FRAME -> "rot " + AaFastCases.serverRotation(c, frameUuid);
            case STAND -> "use_entity " + c.hx().events(hxFrom, "use_entity").size();
            default -> "powered " + c.onClient(mc -> mc.level.getBlockState(leverPos(k))
                    .getValue(net.minecraft.world.level.block.LeverBlock.POWERED));
        };
    }

    private static final double WALK_X = 0.5;

    /**
     * One step: standing (he faces +x, the target behind him at a spot within 3.0 of it) or moving (W + sprint along +z
     * at x 0.5, the click when he passes the target). Returns its table row.
     */
    private static String step(Session c, Way w, Kind k, boolean moving, String frameUuid) {
        double trigger = switch (k) {
            case LEVER -> 72.2;
            case FRAME -> 77.2;
            case STAND -> 82.2;
            case LEVER_AHEAD -> 88.3;
        };
        float pitch = k == Kind.LEVER_AHEAD ? 30f : 0f;
        if (moving) {
            c.hx().call("dungeon.tp", "x", WALK_X, "y", Y, "z", trigger - 6.5, "yaw", 0f, "pitch", pitch);
        } else {
            double sz = switch (k) {
                case LEVER -> 72.5;
                case FRAME -> 77.5;
                case STAND -> 82.5;
                case LEVER_AHEAD -> 88.5;
            };
            c.hx().call("dungeon.tp", "x", k == Kind.STAND ? 0.8 : k == Kind.LEVER_AHEAD ? WALK_X : 0.2, "y", Y, "z", sz,
                    "yaw", k == Kind.LEVER_AHEAD ? 0f : -90f, "pitch", pitch);
        }
        c.ctx().waitTicks(25);
        String before = k == Kind.STAND ? "" : outcome(c, k, frameUuid, 0);
        int f0 = c.scenario().flags().size();
        long h0 = c.hx().head();
        boolean[] fired = {false};
        double[] firedAt = new double[3];
        hook(c);
        c.onClient(mc -> {
            each = m -> {
                if (!fired[0] && (!moving || m.player.getZ() >= trigger)) {
                    fired[0] = true;
                    firedAt[0] = m.player.getX();
                    firedAt[1] = m.player.getZ();
                    firedAt[2] = m.player.getDeltaMovement().horizontalDistance();
                    if (w != null) {
                        fire(m, w, k);
                    }
                }
            };
            return null;
        });
        if (moving) {
            c.ctx().getInput().holdKey(o -> o.keyUp);
            c.ctx().getInput().holdKey(o -> o.keySprint);
        }
        c.waitUntil("the step to fire", mc -> fired[0], 120);
        c.ctx().waitTicks(8);
        if (moving) {
            c.ctx().getInput().releaseKey(o -> o.keySprint);
            c.ctx().getInput().releaseKey(o -> o.keyUp);
        }
        c.onClient(mc -> {
            each = null;
            return null;
        });
        c.ctx().waitTicks(25);
        String after = outcome(c, k, frameUuid, h0);
        String flags = flagsSince(c, f0);
        return String.format(Locale.ROOT, "%s | %s | %s | at %.2f,%.2f v=%.3f | %s -> %s | flags [%s]",
                moving ? "moving" : "standing", w == null ? "no click" : w, k, firedAt[0], firedAt[1], firedAt[2],
                before, after, flags);
    }

    /**
     * Every look way x click kind, standing and moving, raw (no mod feature acts): what the server did and which GrimAC
     * checks flagged. Rows go to the notes and to a file; the hard checks are only that each step fired and that a
     * no-look frame click turns the frame with no flag.
     */
    static void probe(Session c) throws Exception {
        if (!Mod.isCheat()) {
            return;
        }
        try {
            c.server().command("forceload add -4 62 6 94");
            c.server().sync(20);
            c.server().command("fill -2 119 64 4 119 92 stone");
            c.server().command("fill -2 120 64 4 126 90 air");
            c.server().command("fill -3 119 74 -3 125 80 stone");
            c.server().command("setblock -2 121 72 stone");
            c.server().command("setblock -1 121 72 minecraft:lever[face=wall,facing=east,powered=false]");
            c.server().command("setblock 0 121 91 stone");
            c.server().command("setblock 0 121 90 minecraft:lever[face=wall,facing=north,powered=false]");
            JsonObject f = c.hx().call("frame", "x", -2, "y", 121, "z", 77, "facing", "east", "item", "arrow", "rotation", 0)
                    .getAsJsonObject();
            String frameUuid = f.get("uuid").getAsString();
            c.server().command("summon armor_stand -1.0 120 82.5 {NoGravity:1b,CustomName:\"Inactive Terminal\",CustomNameVisible:1b,"
                    + "Tags:[\"aalook1116\"]}");
            TerminalAuraCases.tp(c, 0.2, Y, 77.5, -90f);
            c.waitUntil("the client to have the frame, the stand and both levers", mc ->
                    !mc.level.getEntitiesOfClass(ItemFrame.class, new AABB(-3, 119, 75, 0, 126, 80)).isEmpty()
                            && !mc.level.getEntitiesOfClass(ArmorStand.class, new AABB(-3, 119, 80, 3, 124, 85)).isEmpty()
                            && mc.level.getBlockState(new BlockPos(-1, 121, 72)).getBlock()
                            instanceof net.minecraft.world.level.block.LeverBlock
                            && mc.level.getBlockState(new BlockPos(0, 121, 90)).getBlock()
                            instanceof net.minecraft.world.level.block.LeverBlock, 200);
            c.ctx().waitTicks(20);
            List<String> rows = new ArrayList<>();
            String first = step(c, Way.NONE, Kind.FRAME, false, frameUuid);
            rows.add(first);
            c.check(first.contains("rot 0 -> rot 1") && first.endsWith("flags []"),
                    "a no-look frame click standing did not turn the frame cleanly: " + first);
            Way[] ways = {Way.NONE, Way.PKT, Way.ROT, Way.POSROT, Way.BEFORE};
            for (boolean moving : new boolean[]{false, true}) {
                if (moving) {
                    rows.add(step(c, null, Kind.STAND, true, frameUuid)); // the walk alone
                }
                for (Kind k : new Kind[]{Kind.FRAME, Kind.LEVER, Kind.STAND}) {
                    for (Way w : ways) {
                        if (!moving && w == Way.NONE && k == Kind.FRAME) {
                            continue; // done first
                        }
                        rows.add(step(c, w, k, moving, frameUuid));
                    }
                }
            }
            rows.add(step(c, Way.NONE, Kind.LEVER_AHEAD, true, frameUuid));
            rows.add(step(c, Way.PITCH, Kind.LEVER_AHEAD, true, frameUuid));
            rows.add(step(c, Way.PITCH, Kind.LEVER_AHEAD, false, frameUuid));
            rows.forEach(r -> c.note("probe " + r));
            try {
                java.nio.file.Path out = java.nio.file.Path.of("build", "testkit-report", "nolook-probe-1116.txt");
                java.nio.file.Files.createDirectories(out.getParent());
                java.nio.file.Files.write(out, rows);
            } catch (java.io.IOException e) {
                c.note("could not write the probe table: " + e);
            }
        } finally {
            c.onClient(mc -> {
                each = null;
                return null;
            });
            c.server().command("kill @e[tag=aalook1116]");
            c.server().command("kill @e[type=item_frame,x=-2,y=122,z=77,distance=..6]");
            c.server().command("fill -3 119 64 4 126 92 air");
            c.server().command("forceload remove -4 62 6 94");
            c.hx().call("dungeon.tp", "x", -619.5, "y", 151.0, "z", -619.5, "yaw", 0f, "pitch", 0f);
        }
    }

    // ---- 1116-movingclick-probe -------------------------------------------------------------------------------------

    /**
     * Ways to click a block or stand on a tick he MOVES (W + sprint along +z), raw, no mod feature acting. COAST_*: the
     * click tick has no movement key (momentum only, on the ground), the aim rides that tick's packet (PKT, click at the
     * head of its sendPosition) or that packet with the click at the next tick's start (BEFORE). AIR_*: the same on an
     * airborne tick after a sprint jump. KEYS_AIM: the body really takes the aim yaw for the tick and the key set whose
     * direction at that yaw is nearest the walk (+z) is pressed, so the motion is what that yaw and those keys give; the
     * click goes at the head of that tick's packet. POSROT_G: an extra same-position PosRot with the aim and the right
     * ground flag, keys held. NONE_STRIKE: no look change, the walk's own look (pitch 30) strikes the lever ahead.
     */
    enum MWay { COAST_PKT, COAST_BEFORE, AIR_PKT, AIR_BEFORE, KEYS_AIM, POSROT_G, NONE_STRIKE }

    private static void keys(net.minecraft.client.Minecraft m, boolean w, boolean a, boolean s, boolean d, boolean sprint,
                             boolean jump) {
        m.options.keyUp.setDown(w);
        m.options.keyLeft.setDown(a);
        m.options.keyDown.setDown(s);
        m.options.keyRight.setDown(d);
        m.options.keySprint.setDown(sprint);
        m.options.keyJump.setDown(jump);
    }

    private static void mfire(net.minecraft.client.Minecraft m, MWay w, Kind k, boolean doClick) {
        net.minecraft.client.player.LocalPlayer p = m.player;
        float[] a = aimAt(m, box(m, k).getCenter());
        switch (w) {
            case COAST_PKT, AIR_PKT -> {
                keys(m, false, false, false, false, false, false);
                beforeMove(() -> {
                    restore = new float[]{p.getYRot(), p.getXRot()};
                    p.setYRot(a[0]);
                    p.setXRot(a[1]);
                    if (doClick) {
                        click(m, k);
                    }
                });
            }
            case COAST_BEFORE, AIR_BEFORE -> {
                keys(m, false, false, false, false, false, false);
                beforeMove(() -> {
                    restore = new float[]{p.getYRot(), p.getXRot()};
                    p.setYRot(a[0]);
                    p.setXRot(a[1]);
                    if (doClick) {
                        afterRestore = () -> click(m, k);
                    }
                });
            }
            case KEYS_AIM -> {
                restore = null;
                float live = p.getYRot(), livePitch = p.getXRot();
                p.setYRot(a[0]);
                p.setXRot(a[1]);
                int k8 = Math.round(Mth.wrapDegrees(live - a[0]) / 45f); // walk yaw = aim + k8 * 45
                keys(m, Math.abs(k8) <= 1, k8 <= -1 && k8 >= -3, Math.abs(k8) >= 3, k8 >= 1 && k8 <= 3, true, false);
                beforeMove(() -> {
                    restore = new float[]{live, livePitch};
                    if (doClick) {
                        click(m, k);
                    }
                });
            }
            case POSROT_G -> {
                p.connection.send(new ServerboundMovePlayerPacket.PosRot(p.getX(), p.getY(), p.getZ(), a[0], a[1],
                        p.onGround(), p.horizontalCollision));
                if (doClick) {
                    click(m, k);
                }
            }
            case NONE_STRIKE -> {
                if (doClick) {
                    click(m, k);
                }
            }
        }
    }

    private static String mstep(Session c, MWay w, Kind k, boolean doClick, String frameUuid) {
        double trigger = switch (k) {
            case LEVER -> 72.2;
            case FRAME -> 77.2;
            case STAND -> 82.2;
            case LEVER_AHEAD -> 88.3;
        };
        boolean air = w == MWay.AIR_PKT || w == MWay.AIR_BEFORE;
        c.hx().call("dungeon.tp", "x", WALK_X, "y", Y, "z", trigger - 7.5, "yaw", 0f,
                "pitch", k == Kind.LEVER_AHEAD ? 30f : 0f);
        c.ctx().waitTicks(25);
        String before = k == Kind.STAND ? "" : outcome(c, k, frameUuid, 0);
        int f0 = c.scenario().flags().size();
        long h0 = c.hx().head();
        int[] st = {0, 0, 0}; // fired, ticks after, jumped
        double[] at = new double[4];
        hook(c);
        c.onClient(mc -> {
            each = m -> {
                net.minecraft.client.player.LocalPlayer p = m.player;
                if (st[0] == 0) {
                    boolean jump = air && st[2] == 0 && p.onGround() && p.getZ() >= trigger - 2.4;
                    if (jump) {
                        st[2] = 1;
                    }
                    if (p.getZ() >= trigger && (!air || !p.onGround())) {
                        st[0] = 1;
                        at[0] = p.getX();
                        at[1] = p.getZ();
                        at[2] = p.getDeltaMovement().horizontalDistance();
                        at[3] = p.onGround() ? 1 : 0;
                        mfire(m, w, k, doClick);
                        return;
                    }
                    keys(m, true, false, false, false, true, jump);
                } else if (st[1]++ < 10) {
                    keys(m, true, false, false, false, true, false);
                } else {
                    keys(m, false, false, false, false, false, false);
                }
            };
            return null;
        });
        c.waitUntil("the moving step to fire", mc -> st[0] == 1 && st[1] > 10, 200);
        c.onClient(mc -> {
            each = null;
            keys(mc, false, false, false, false, false, false);
            return null;
        });
        c.ctx().waitTicks(25);
        String after = outcome(c, k, frameUuid, h0);
        String flags = flagsSince(c, f0);
        return String.format(Locale.ROOT, "moving | %s%s | %s | at %.2f,%.2f v=%.3f ground=%s | %s -> %s | flags [%s]",
                w, doClick ? "" : " (no click)", k, at[0], at[1], at[2], at[3] > 0, before, after, flags);
    }

    /** Every way left for a click on a MOVING tick (step 2 of ap3auralook-1116): what the server did and what flagged. */
    static void movingProbe(Session c) throws Exception {
        if (!Mod.isCheat()) {
            return;
        }
        try {
            c.server().command("forceload add -4 62 6 94");
            c.server().sync(20);
            c.server().command("fill -2 119 60 4 119 96 stone");
            c.server().command("fill -2 120 60 4 126 94 air");
            c.server().command("setblock -2 121 72 stone");
            c.server().command("setblock -1 121 72 minecraft:lever[face=wall,facing=east,powered=false]");
            c.server().command("setblock 0 121 91 stone");
            c.server().command("setblock 0 121 90 minecraft:lever[face=wall,facing=north,powered=false]");
            c.server().command("summon armor_stand -1.0 120 82.5 {NoGravity:1b,CustomName:\"Inactive Terminal\",CustomNameVisible:1b,"
                    + "Tags:[\"aalook1116\"]}");
            TerminalAuraCases.tp(c, WALK_X, Y, 64.5, 0f);
            c.waitUntil("the client to have the stand and both levers", mc ->
                    !mc.level.getEntitiesOfClass(ArmorStand.class, new AABB(-3, 119, 80, 3, 124, 85)).isEmpty()
                            && mc.level.getBlockState(new BlockPos(-1, 121, 72)).getBlock()
                            instanceof net.minecraft.world.level.block.LeverBlock
                            && mc.level.getBlockState(new BlockPos(0, 121, 90)).getBlock()
                            instanceof net.minecraft.world.level.block.LeverBlock, 200);
            c.ctx().waitTicks(20);
            List<String> rows = new ArrayList<>();
            for (MWay w : new MWay[]{MWay.COAST_PKT, MWay.AIR_PKT, MWay.KEYS_AIM}) {
                rows.add(mstep(c, w, Kind.LEVER, false, null)); // the movement change alone
            }
            for (Kind k : new Kind[]{Kind.LEVER, Kind.STAND}) {
                for (MWay w : new MWay[]{MWay.COAST_PKT, MWay.COAST_BEFORE, MWay.AIR_PKT, MWay.AIR_BEFORE, MWay.KEYS_AIM,
                        MWay.POSROT_G}) {
                    rows.add(mstep(c, w, k, true, null));
                }
            }
            rows.add(mstep(c, MWay.NONE_STRIKE, Kind.LEVER_AHEAD, true, null));
            rows.add(mstep(c, MWay.NONE_STRIKE, Kind.LEVER, true, null)); // control: the look does not strike
            rows.forEach(r -> c.note("mprobe " + r));
            c.check(rows.stream().noneMatch(r -> r.contains("(no click)") && r.contains("-> powered true")),
                    "a no-click row flipped the lever");
            try {
                java.nio.file.Path out = java.nio.file.Path.of("build", "testkit-report", "movingclick-probe-1116.txt");
                java.nio.file.Files.createDirectories(out.getParent());
                java.nio.file.Files.write(out, rows);
            } catch (java.io.IOException e) {
                c.note("could not write the probe table: " + e);
            }
        } finally {
            c.onClient(mc -> {
                each = null;
                keys(mc, false, false, false, false, false, false);
                return null;
            });
            c.server().command("kill @e[tag=aalook1116]");
            c.server().command("fill -3 119 60 4 126 96 air");
            c.server().command("forceload remove -4 62 6 94");
            c.hx().call("dungeon.tp", "x", -619.5, "y", 151.0, "z", -619.5, "yaw", 0f, "pitch", 0f);
        }
    }
}
