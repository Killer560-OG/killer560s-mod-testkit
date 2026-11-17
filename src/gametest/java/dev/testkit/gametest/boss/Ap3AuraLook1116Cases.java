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
 *       no turn (the look never meets the stand), so no click and the same walk as with the aura off.</li>
 *   <li>1116-nolook-probe (expecting flags): the three click kinds sent raw with NO look change, the target behind him:
 *       an item frame interact, a lever use_item_on and a terminal stand interact. Recorded per kind: did the server act,
 *       which GrimAC checks flagged. The frame must act and draw nothing.</li>
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
        s.test("1116-ap3aura-arrows", c -> arrows(c, null));
        if (!Mod.isCheat()) {
            return;
        }
        for (SameTick.Act a : new SameTick.Act[]{SameTick.Act.USE, SameTick.Act.SWAP, SameTick.Act.ATTACK,
                SameTick.Act.OPEN_CLICK, SameTick.Act.TP}) {
            s.test("1116-ap3aura-arrows-same-" + a.name().toLowerCase(Locale.ROOT), c -> arrows(c, a));
        }
        s.test("1116-ap3aura-term-beside", Ap3AuraLook1116Cases::termBeside);
        s.testExpectingFlags("1116-nolook-probe", Ap3AuraLook1116Cases::probe);
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
        double[] a0 = a.get(0), b0 = b.get(0);
        for (int i = 0; i < n; i++) {
            double[] p = a.get(i), q = b.get(i);
            for (int k = 0; k < 5; k++) {
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
     * South along x -0.5 past the frames (x -2, z 75-79), a bend toward +x at z 76.5 and back at z 79, then a STOP at z 86.
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
            c.server().command("summon armor_stand -0.5 120 83.5 {NoGravity:1b,Invulnerable:1b,Tags:[\"aalook1116\"]}");
        }
        c.ctx().waitTicks(10);
        Object align = node("FAST_ALIGN", START_X, Y, START_Z, 0f, 2.0);
        Object r1 = node("RUN", START_X, Y, START_Z, 0f, 1.0);
        Object r2 = node("RUN", -0.5, Y, 76.5, -20f, 1.0);
        Object r3 = node("RUN", 0.6, Y, 79.5, 15f, 1.0);
        Object stop = node("STOP", 0.0, Y, 86.0, 0f, 3.0);
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
        if (st != null) {
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
                if (r >= 0) {
                    runs[r] = ticksFromWire(px, pz);
                    clicks[r] = c.events("term.interact").size() - before;
                }
            }
            int n = Math.min(runs[0].size(), runs[1].size());
            String diff = firstDiff(runs[0], runs[1], n);
            c.note(String.format(Locale.ROOT, "ticks off/on %d/%d; terminal clicks off/on %d/%d; first difference: %s",
                    runs[0].size(), runs[1].size(), clicks[0], clicks[1], diff.isEmpty() ? "none" : diff));
            c.check(n >= 20, "the walk lasted only " + n + " ticks");
            c.check(diff.isEmpty(), "Terminal Aura changed the AP3 walk: " + diff);
            c.check(clicks[1] == 0, "Terminal Aura clicked a stand the walk's look never met (" + clicks[1] + ")");
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
    private static String flagsSince(Session c, int from) {
        List<String> f = c.scenario().flags();
        return f.size() <= from ? "" : String.join(" | ", f.subList(from, f.size()));
    }

    /**
     * Each click kind sent raw at START with no look change, its target BEHIND him (he faces +x, the targets are west of
     * him), within reach: what the server did and what GrimAC flagged, one kind at a time with a quiet second between.
     */
    static void probe(Session c) throws Exception {
        if (!Mod.isCheat()) {
            return;
        }
        try {
            c.server().command("forceload add -4 64 6 92");
            c.server().sync(20);
            c.server().command("fill -2 119 70 4 119 84 stone");
            c.server().command("fill -2 120 70 4 126 84 air");
            c.server().command("fill -3 119 74 -3 125 80 stone");
            c.server().command("setblock -2 121 72 stone");
            c.server().command("setblock -1 121 72 minecraft:lever[face=wall,facing=east,powered=false]");
            JsonObject f = c.hx().call("frame", "x", -2, "y", 121, "z", 77, "facing", "east", "item", "arrow", "rotation", 0)
                    .getAsJsonObject();
            String frameUuid = f.get("uuid").getAsString();
            c.server().command("summon armor_stand -1.0 120 82.5 {NoGravity:1b,CustomName:\"Inactive Terminal\",CustomNameVisible:1b,"
                    + "Tags:[\"aalook1116\"]}");
            // Facing +x (yaw -90), level: every target is behind him; each within 3.0 of the eye from its own spot.
            List<String> rows = new ArrayList<>();
            // 1. Item frame.
            TerminalAuraCases.tp(c, 0.2, Y, 77.5, -90f);
            c.ctx().waitTicks(20);
            int f0 = c.scenario().flags().size();
            long s0 = c.hx().head();
            c.onClient(mc -> {
                ItemFrame fr = mc.level.getEntitiesOfClass(ItemFrame.class, new AABB(-3, 119, 75, 0, 126, 80)).get(0);
                Vec3 eye = mc.player.getEyePosition();
                Vec3 hit = fr.getBoundingBox().clip(eye, fr.getBoundingBox().getCenter()).orElse(fr.getBoundingBox().getCenter());
                mc.gameMode.interact(mc.player, fr, new EntityHitResult(fr, hit), InteractionHand.MAIN_HAND);
                return null;
            });
            c.ctx().waitTicks(30);
            int rot = AaFastCases.serverRotation(c, frameUuid);
            String fFlags = flagsSince(c, f0);
            rows.add("frame: server use_entity " + c.hx().events(s0, "use_entity").size() + ", rotation now " + rot
                    + ", flags [" + fFlags + "]");
            c.check(rot == 1, "the no-look frame click did not turn the frame on the server (rotation " + rot + ")");
            c.check(fFlags.isEmpty(), "GrimAC flagged a no-look item frame click: " + fFlags);
            // 2. Lever (use_item_on).
            TerminalAuraCases.tp(c, 0.2, Y, 72.5, -90f);
            c.ctx().waitTicks(20);
            int l0 = c.scenario().flags().size();
            c.onClient(mc -> {
                BlockPos lp = new BlockPos(-1, 121, 72);
                Vec3 eye = mc.player.getEyePosition();
                Vec3 center = Vec3.atCenterOf(lp);
                var shape = mc.level.getBlockState(lp).getShape(mc.level, lp);
                BlockHitResult hr = shape.clip(eye, center.add(center.subtract(eye).normalize()), lp);
                if (hr == null) {
                    hr = new BlockHitResult(center, Direction.EAST, lp, false);
                }
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hr);
                return null;
            });
            c.ctx().waitTicks(30);
            boolean powered = c.onClient(mc -> mc.level.getBlockState(new BlockPos(-1, 121, 72))
                    .getValue(net.minecraft.world.level.block.LeverBlock.POWERED));
            String lFlags = flagsSince(c, l0);
            rows.add("lever: powered after " + powered + ", flags [" + lFlags + "]");
            // 3. Terminal stand (interact on a living entity).
            TerminalAuraCases.tp(c, 0.8, Y, 82.5, -90f);
            c.ctx().waitTicks(20);
            int t0 = c.scenario().flags().size();
            long ts = c.hx().head();
            c.onClient(mc -> {
                ArmorStand as = mc.level.getEntitiesOfClass(ArmorStand.class, new AABB(-3, 119, 80, 3, 124, 85)).get(0);
                Vec3 eye = mc.player.getEyePosition();
                Vec3 hit = as.getBoundingBox().clip(eye, as.getBoundingBox().getCenter()).orElse(as.getBoundingBox().getCenter());
                mc.gameMode.interact(mc.player, as, new EntityHitResult(as, hit), InteractionHand.MAIN_HAND);
                return null;
            });
            c.ctx().waitTicks(30);
            String sFlags = flagsSince(c, t0);
            rows.add("terminal stand: server use_entity " + c.hx().events(ts, "use_entity").size() + ", flags [" + sFlags + "]");
            rows.forEach(r -> c.note("no-look " + r));
        } finally {
            c.server().command("kill @e[tag=aalook1116]");
            c.server().command("kill @e[type=item_frame,x=-2,y=122,z=77,distance=..6]");
            c.server().command("fill -3 119 70 4 126 84 air");
            c.server().command("forceload remove -4 64 6 92");
            c.hx().call("dungeon.tp", "x", -619.5, "y", 151.0, "z", -619.5, "yaw", 0f, "pitch", 0f);
        }
    }
}
