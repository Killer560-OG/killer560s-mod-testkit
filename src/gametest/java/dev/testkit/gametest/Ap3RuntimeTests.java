package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Session;
import dev.testkit.harness.PacketTrace;
import dev.testkit.harness.Report;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * AP3's runtime, tick-exact, against a live GrimAC (killer560, 2026-10-06):
 * <ul>
 *   <li>"look nodes mean that the second I hit the look node I should be looking that direction tick 1" -
 *       {@code 63-ap3-look-*}: the camera (what {@code getViewYRot}/{@code getViewXRot} hand the renderer) is at the
 *       node's angle at the END of the first client tick whose position is inside the box, and stays there.</li>
 *   <li>"Same concept for use nodes they should go off the tick i enter it" - {@code 63-ap3-use-*}: on the wire, the
 *       use is the very next packet group after the movement packet that put him in the box - no movement packet in
 *       between - and it carries the rotation the following movement packet reports (GrimAC BadPacketsJ's rule).</li>
 *   <li>"hitting a node doesnt stop another node unless it is ... a walk ... a stop node or align node" -
 *       {@code 63-ap3-hold-<type>}: a RUN carries him into one node of each type; does the run go on?</li>
 *   <li>The stopwatch HUD hides 10 s after the stopwatch stopped ({@code 63-ap3-stopwatch-hud}).</li>
 *   <li>Two switches shipped without tests: /ap3 and /ar tab-complete when switched on after joining
 *       ({@code 63-ap3-cmdtree}), and server corrections ({@code 63-ap3-corrections}: chat line + alarm, never a stop).</li>
 * </ul>
 * Everything is driven the way he drives it: a real W press walks him into a RUN node's box, he lets go inside it,
 * and AP3 carries him from there. The harness never writes his position or rotation; a lane is changed with a
 * server {@code tp}. Every case first proves the thing under test actually happened (the box was entered, the
 * packet was sent, distance was covered) before it judges anything.
 */
public class Ap3RuntimeTests implements FabricClientGameTest {

    static final String SESSION = "63-ap3-session";
    private static final String P = "com.killer560.hub.ap3.";
    private static final String EXEC = P + "Ap3Executor";
    private static final String FEATURE = P + "Ap3Feature";
    private static final String CONFIG = P + "Ap3Config";
    private static final String STORE = P + "Ap3Store";
    private static final String NODE = P + "Ap3Node";
    private static final String TYPE = P + "Ap3Node$Type";
    private static final String AREA = P + "Ap3Area";

    /** Floor block layer; the player stands at FLOOR + 1. Lanes run +x from LANE_X0, one every LANE_DZ in z. */
    private static final int FLOOR = 120;
    private static final int LANE_X0 = 0;
    private static final int LANE_LEN = 48;
    private static final int LANE_DZ = 8;
    private static final int LANES = 32;
    private static final String WAND = "AP3_TEST_WAND";

    private static int nextLane;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        ModUnderTest.require("killer560smod");
        Session.run(ctx, SESSION, (server, s) -> setup(ctx, s), s -> {
            // First: it reconnects, which is why it cannot follow anything that relies on this world's state.
            s.test("63-ap3-cmdtree", Ap3RuntimeTests::caseCommandTree);
            s.test("63-ap3-look-walk", Ap3RuntimeTests::caseLookWalk);
            s.test("63-ap3-look-queued", Ap3RuntimeTests::caseLookQueued);
            s.test("63-ap3-use-walk", c -> caseUse(c, "63-ap3-use-walk", false, false));
            s.test("63-ap3-use-swap", c -> caseUse(c, "63-ap3-use-swap", true, false));
            s.test("63-ap3-use-on-block", c -> caseUse(c, "63-ap3-use-on-block", false, true));
            s.test("63-ap3-block-walk", c -> caseInteract(c, "BLOCK"));
            s.test("63-ap3-boom-walk", c -> caseInteract(c, "BOOM"));
            // RotationPlace is judged per place, so one clean place is a sample, not a rate: four more.
            for (int i = 2; i <= 5; i++) {
                s.test("63-ap3-block-rep-" + i, c -> caseInteract(c, "BLOCK"));
            }
            for (String type : new String[]{"LOOK", "USE", "STOPWATCH", "JUMP", "EDGE", "BLOCK", "BOOM", "TERMINAL",
                    "LEAP_COUNTER", "TERM_AURA", "STOP", "ALIGN", "AXIS_ALIGN", "FAST_ALIGN", "WALK"}) {
                String name = "63-ap3-hold-" + type.toLowerCase(Locale.ROOT).replace('_', '-');
                s.test(name, c -> caseHold(c, name, type));
            }
            s.test("63-ap3-hold-boom-then-stop", Ap3RuntimeTests::caseBoomThenStop);
            s.test("63-ap3-stopwatch-hud", Ap3RuntimeTests::caseStopwatchHud);
            s.testExpectingFlags("63-ap3-corrections", Ap3RuntimeTests::caseCorrection);
            onClient(ctx, mc -> {
                PacketTrace.tap = null;
                return null;
            });
        });
    }

    // ------------------------------------------------------------------------------------------------- setup

    private static void setup(ClientGameTestContext ctx, Session s) {
        s.scenario().assertDetectorWorks();
        LogTap.install();
        TestMap map = TestMap.on(s.server());
        int zMax = LANES * LANE_DZ;
        // One long floor for every lane, in slices (a fill is capped at 32768 blocks).
        for (int z = -4; z <= zMax + 4; z += 16) {
            map.fill(LANE_X0 - 8, FLOOR, z, LANE_X0 + LANE_LEN + 8, FLOOR, Math.min(z + 15, zMax + 4), "smooth_stone");
            map.fill(LANE_X0 - 8, FLOOR + 1, z, LANE_X0 + LANE_LEN + 8, FLOOR + 4, Math.min(z + 15, zMax + 4), "air");
        }
        map.surface(FLOOR + 1)
                .catchFloor(FLOOR - 20)
                .survival()
                .clearInventory()
                .command("item replace entity @p hotbar.3 with minecraft:stick[minecraft:custom_data={id:\"" + WAND + "\"}]")
                .command("item replace entity @p hotbar.5 with minecraft:smooth_stone_slab 64")
                .command("item replace entity @p hotbar.6 with minecraft:tnt[minecraft:custom_data={id:\"SUPERBOOM_TNT\"}] 64")
                .spawn(LANE_X0 + 0.5, 0.5, -90f)
                .build();
        ctx.runOnClient(mc -> {
            ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled");
            Object cfg = ModUnderTest.config(CONFIG);
            ModUnderTest.set(cfg, "setEnabled", true);
            ModUnderTest.set(cfg, "setChatFeedback", true);
            installProbe();
        });
    }

    // ------------------------------------------------------------------------------------------------- probe

    /** One thing that happened, in the order it happened on the client thread. */
    record Ev(int tick, String kind, double x, double y, double z, float yRot, float xRot, float viewY, float viewX,
              int slot, String extra) {
    }

    private static final List<Ev> EVENTS = new ArrayList<>();
    private static volatile boolean recording;
    private static int tick;
    private static boolean probeInstalled;

    /** END markers come from a listener registered after the mod's, so AP3's own END tick has already run when one
     *  is written; packets the mod sends from START land after the previous END marker and before this START. */
    private static void installProbe() {
        if (probeInstalled) {
            return;
        }
        probeInstalled = true;
        ClientTickEvents.START_CLIENT_TICK.register(mc -> {
            if (recording) {
                add(new Ev(tick, "S", 0, 0, 0, 0, 0, 0, 0, -1, ""));
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (!recording || mc.player == null) {
                return;
            }
            LocalPlayer p = mc.player;
            add(new Ev(tick, "E", p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot(), p.getViewYRot(1f),
                    p.getViewXRot(1f), p.getInventory().getSelectedSlot(), ""));
            tick++;
        });
        PacketTrace.tap = Ap3RuntimeTests::onPacket;
    }

    private static void onPacket(Packet<?> packet) {
        if (!recording) {
            return;
        }
        if (packet instanceof ServerboundMovePlayerPacket m) {
            add(new Ev(tick, "move", m.getX(Double.NaN), m.getY(Double.NaN), m.getZ(Double.NaN),
                    m.getYRot(Float.NaN), m.getXRot(Float.NaN), 0, 0, -1, ""));
        } else if (packet instanceof ServerboundUseItemPacket u) {
            add(new Ev(tick, "use", 0, 0, 0, u.getYRot(), u.getXRot(), 0, 0, -1, ""));
        } else if (packet instanceof ServerboundUseItemOnPacket u) {
            add(new Ev(tick, "useon", 0, 0, 0, Float.NaN, Float.NaN, 0, 0, -1,
                    u.getHitResult().getBlockPos().toShortString() + " " + u.getHitResult().getDirection()));
        } else if (packet instanceof ServerboundSetCarriedItemPacket c) {
            add(new Ev(tick, "carry", 0, 0, 0, 0, 0, 0, 0, c.getSlot(), ""));
        } else if (packet instanceof ServerboundPlayerActionPacket a) {
            add(new Ev(tick, "action", 0, 0, 0, 0, 0, 0, 0, -1, a.getAction().name()));
        }
    }

    private static void add(Ev e) {
        synchronized (EVENTS) {
            EVENTS.add(e);
        }
    }

    private static void startProbe(ClientGameTestContext ctx) {
        onClient(ctx, mc -> {
            synchronized (EVENTS) {
                EVENTS.clear();
            }
            tick = 0;
            recording = true;
            return null;
        });
    }

    private static List<Ev> stopProbe(ClientGameTestContext ctx) {
        return onClient(ctx, mc -> {
            recording = false;
            synchronized (EVENTS) {
                return new ArrayList<>(EVENTS);
            }
        });
    }

    // ------------------------------------------------------------------------------------------------- nodes

    static Object node(String type, double x, double z, float yaw, float pitch, double length, double width) {
        try {
            Class<?> cls = Class.forName(NODE);
            Object n = cls.getConstructor(Class.forName(TYPE), double.class, double.class, double.class, float.class,
                    float.class).newInstance(ModUnderTest.enumValue(TYPE, type), x, (double) FLOOR + 1, z, yaw, pitch);
            set(n, "length", length);
            set(n, "width", width);
            return n;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot make an AP3 " + type + " node", e);
        }
    }

    static void set(Object node, String field, Object value) {
        try {
            Field f = node.getClass().getField(field);
            f.set(node, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Ap3Node." + field, e);
        }
    }

    static boolean contains(Object node, Vec3 p) {
        return (Boolean) ModUnderTest.call(node, "contains", new Class<?>[]{Vec3.class}, new Object[]{p});
    }

    /** Replace the boss chain (the one AP3 runs under Force Dungeon) with {@code nodes}; never saved. */
    @SuppressWarnings("unchecked")
    static void setChain(Object... nodes) {
        Object store = ModUnderTest.staticCall(STORE, "getInstance");
        Object area = ModUnderTest.enumValue(AREA, "BOSS");
        Object chain;
        try {
            chain = store.getClass().getMethod("forAreaOrCreate", Class.forName(AREA),
                    Class.forName("com.killer560.hub.dungeonclass.DungeonClass")).invoke(store, area, null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Ap3Store.forAreaOrCreate", e);
        }
        List<Object> list = (List<Object>) ModUnderTest.call(chain, "nodes", new Class<?>[]{}, new Object[]{});
        list.clear();
        list.addAll(List.of(nodes));
    }

    // ------------------------------------------------------------------------------------------------- driving

    /** A fresh lane: z of its centre line. */
    private static double lane() {
        return (nextLane++ % LANES) * LANE_DZ + 0.5;
    }

    /**
     * Put him at the lane's start (server tp, facing +x), arm the given nodes, then walk him into the RUN box with a
     * real W press and let go inside it - the hand-over AP3 is built for. Returns once the run is carrying him.
     */
    private static void walkIntoRun(Session c, double z, Object run, Object... rest) {
        ClientGameTestContext ctx = c.ctx();
        onClient(ctx, mc -> {
            ModUnderTest.staticCall(EXEC, "stop", new Class<?>[]{String.class}, new Object[]{"test lane"});
            ModUnderTest.staticCall(FEATURE, "setForceDungeon", new Class<?>[]{boolean.class}, new Object[]{true});
            setChain();
            return null;
        });
        c.server().command(String.format(Locale.ROOT, "tp @p %.2f %d %.2f -90 0", LANE_X0 + 0.5, FLOOR + 1, z));
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
            throw new AssertionError("walking with W never reached the RUN node's box - nothing below would mean anything");
        }
        c.waitUntil("AP3 running the RUN node's held walk",
                mc -> (Boolean) ModUnderTest.staticCall(EXEC, "isRunning"), 10);
    }

    /** A RUN node at the start of a lane, pointing +x, box 2x2 so a hand walk lands in it. */
    private static Object runNode(double z) {
        return node("RUN", LANE_X0 + 3.0, z, -90f, 0f, 2.0, 2.0);
    }

    // ------------------------------------------------------------------------------------------------- analysis

    /** The first END marker at which {@code node} contains his position, or null. */
    private static Ev firstInside(ClientGameTestContext ctx, List<Ev> evs, Object node) {
        return onClient(ctx, mc -> {
            for (Ev e : evs) {
                if (e.kind().equals("E") && contains(node, new Vec3(e.x(), e.y(), e.z()))) {
                    return e;
                }
            }
            return null;
        });
    }

    private static Ev endAt(List<Ev> evs, int t) {
        for (Ev e : evs) {
            if (e.kind().equals("E") && e.tick() == t) {
                return e;
            }
        }
        return null;
    }

    private static int indexOf(List<Ev> evs, Ev e) {
        for (int i = 0; i < evs.size(); i++) {
            if (evs.get(i) == e) {
                return i;
            }
        }
        return -1;
    }

    private static boolean angleEq(float a, float b, float tol) {
        return Math.abs(Mth.wrapDegrees(a - b)) <= tol;
    }

    private static void dump(Session c, List<Ev> evs, int fromTick, int toTick) {
        for (Ev e : evs) {
            if (e.tick() < fromTick || e.tick() > toTick) {
                continue;
            }
            String line = switch (e.kind()) {
                case "E" -> String.format(Locale.ROOT, "t%d E pos(%.3f, %.3f) rot(%.2f, %.2f) view(%.2f, %.2f) slot %d",
                        e.tick(), e.x(), e.z(), e.yRot(), e.xRot(), e.viewY(), e.viewX(), e.slot());
                case "S" -> "t" + e.tick() + " S";
                case "move" -> String.format(Locale.ROOT, "t%d   move pos(%.3f, %.3f) rot(%.2f, %.2f)",
                        e.tick(), e.x(), e.z(), e.yRot(), e.xRot());
                case "use" -> String.format(Locale.ROOT, "t%d   use rot(%.2f, %.2f)", e.tick(), e.yRot(), e.xRot());
                case "carry" -> "t" + e.tick() + "   carry slot " + e.slot();
                default -> "t" + e.tick() + "   " + e.kind() + " " + e.extra();
            };
            c.note(line);
        }
    }

    // ------------------------------------------------------------------------------------------------- LOOK

    /** RUN, then a LOOK across the lane: the view must be at the node's angle on the entry tick, and the run goes on. */
    private static void caseLookWalk(Session c) {
        ClientGameTestContext ctx = c.ctx();
        double z = lane();
        Object run = runNode(z);
        Object look = node("LOOK", LANE_X0 + 10.0, z, 37.5f, 12.25f, 1.0, 3.0);
        Object stop = node("STOP", LANE_X0 + 22.0, z, -90f, 0f, 2.0, 3.0);
        startProbe(ctx);
        walkIntoRun(c, z, run, look, stop);
        ctx.waitTicks(80);
        List<Ev> evs = stopProbe(ctx);
        Ev entry = firstInside(ctx, evs, look);
        if (entry == null) {
            throw new AssertionError("never entered the LOOK box - the run did not carry him there");
        }
        dump(c, evs, entry.tick() - 2, entry.tick() + 4);
        String why = lookVerdict(evs, entry, 37.5f, 12.25f);
        Ev later = endAt(evs, entry.tick() + 12);
        double carried = later == null ? 0 : later.x() - entry.x();
        c.note(String.format(Locale.ROOT, "travelled %.2f blocks in the 12 ticks after the LOOK", carried));
        if (why != null) {
            throw new AssertionError(why);
        }
        if (carried < 1.5) {
            throw new AssertionError(String.format(Locale.ROOT,
                    "the LOOK ended the run: only %.2f blocks in the 12 ticks after it (a held run keeps going)", carried));
        }
    }

    /** STOP and LOOK in the same place: the STOP brakes for ticks, and the LOOK must not wait for it. */
    private static void caseLookQueued(Session c) {
        ClientGameTestContext ctx = c.ctx();
        double z = lane();
        Object run = runNode(z);
        Object stop = node("STOP", LANE_X0 + 10.0, z, -90f, 0f, 1.0, 3.0);
        Object look = node("LOOK", LANE_X0 + 10.0, z, -150f, -20f, 1.0, 3.0);
        startProbe(ctx);
        walkIntoRun(c, z, run, stop, look);
        ctx.waitTicks(40);
        List<Ev> evs = stopProbe(ctx);
        Ev entry = firstInside(ctx, evs, look);
        if (entry == null) {
            throw new AssertionError("never entered the STOP + LOOK box");
        }
        dump(c, evs, entry.tick() - 1, entry.tick() + 6);
        String why = lookVerdict(evs, entry, -150f, -20f);
        if (why != null) {
            throw new AssertionError(why);
        }
    }

    /** Null when the view is on (yaw, pitch) at the entry tick's END and still is 3 ticks later; else what is wrong. */
    private static String lookVerdict(List<Ev> evs, Ev entry, float yaw, float pitch) {
        int firstOn = -1;
        for (Ev e : evs) {
            if (e.kind().equals("E") && e.tick() >= entry.tick() && angleEq(e.viewY(), yaw, 0.01f)
                    && Math.abs(e.viewX() - pitch) <= 0.01f) {
                firstOn = e.tick();
                break;
            }
        }
        if (firstOn < 0) {
            return String.format(Locale.ROOT, "the view never reached the LOOK's angle (%.2f, %.2f); at entry it was "
                    + "(%.2f, %.2f)", yaw, pitch, entry.viewY(), entry.viewX());
        }
        if (firstOn != entry.tick()) {
            return "the view reached the LOOK's angle " + (firstOn - entry.tick()) + " tick(s) after he entered the box";
        }
        Ev after = endAt(evs, entry.tick() + 3);
        if (after != null && !(angleEq(after.viewY(), yaw, 0.01f) && Math.abs(after.viewX() - pitch) <= 0.01f)) {
            return String.format(Locale.ROOT, "the view was on the LOOK at entry but 3 ticks later it is (%.2f, %.2f) -"
                    + " something pulled it back", after.viewY(), after.viewX());
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------------- USE

    /**
     * RUN, then a USE across the lane. {@code swap}: the node records the test wand in hotbar slot 4 while slot 1 is
     * held. {@code onBlock}: the node looks down at the floor ahead (use_item_on) instead of at the sky (use_item).
     */
    private static void caseUse(Session c, String name, boolean swap, boolean onBlock) {
        ClientGameTestContext ctx = c.ctx();
        ctx.getInput().pressKey(options -> options.keyHotbarSlots[0]);
        ctx.waitTicks(3);
        double z = lane();
        Object run = runNode(z);
        float yaw = onBlock ? -80f : 20f;
        float pitch = onBlock ? 55f : -35f;
        Object use = node("USE", LANE_X0 + 10.0, z, yaw, pitch, 1.0, 3.0);
        if (swap) {
            set(use, "useItemId", WAND);
        }
        Object stop = node("STOP", LANE_X0 + 22.0, z, -90f, 0f, 2.0, 3.0);
        startProbe(ctx);
        walkIntoRun(c, z, run, use, stop);
        ctx.waitTicks(70);
        List<Ev> evs = stopProbe(ctx);
        ctx.getInput().pressKey(options -> options.keyHotbarSlots[0]);
        Ev entry = firstInside(ctx, evs, use);
        if (entry == null) {
            throw new AssertionError("never entered the USE box");
        }
        dump(c, evs, entry.tick() - 1, entry.tick() + 4);
        String kind = onBlock ? "useon" : "use";
        List<Ev> uses = new ArrayList<>();
        for (Ev e : evs) {
            if (e.kind().equals(kind)) {
                uses.add(e);
            }
        }
        if (uses.size() != 1) {
            throw new AssertionError("expected exactly one " + kind + " packet, saw " + uses.size());
        }
        Ev u = uses.get(0);
        int ui = indexOf(evs, u);
        // The movement packet that put him in the box: the first one whose position is inside it.
        int entryMove = -1;
        for (int i = 0; i < evs.size(); i++) {
            Ev e = evs.get(i);
            if (e.kind().equals("move") && !Double.isNaN(e.x())) {
                final Ev ee = e;
                boolean in = onClient(ctx, mc -> contains(use, new Vec3(ee.x(), ee.y(), ee.z())));
                if (in) {
                    entryMove = i;
                    break;
                }
            }
        }
        if (entryMove < 0) {
            throw new AssertionError("no movement packet ever reported him inside the USE box");
        }
        int movesBetween = 0;
        for (int i = entryMove + 1; i < ui; i++) {
            if (evs.get(i).kind().equals("move")) {
                movesBetween++;
            }
        }
        if (ui < entryMove) {
            throw new AssertionError("the use went out BEFORE the movement packet that put him in the box");
        }
        // The rotation the next movement packet reports must be the use's own (GrimAC BadPacketsJ), and for a plain
        // use it must be the node's angle (the server snaps to the packet's rotation before using).
        Ev nextMove = null;
        for (int i = ui + 1; i < evs.size(); i++) {
            if (evs.get(i).kind().equals("move")) {
                nextMove = evs.get(i);
                break;
            }
        }
        List<String> problems = new ArrayList<>();
        c.note("movement packets between the one that entered the box and the use: " + movesBetween);
        if (movesBetween != 0) {
            problems.add(movesBetween + " movement packet(s) went out between entering the box and the use");
        }
        if (!onBlock) {
            if (!angleEq(u.yRot(), yaw, 0.01f) || Math.abs(u.xRot() - pitch) > 0.01f) {
                problems.add(String.format(Locale.ROOT, "the use packet's rotation is (%.2f, %.2f), not the node's (%.2f, %.2f)",
                        u.yRot(), u.xRot(), yaw, pitch));
            }
            if (nextMove != null && !Float.isNaN(nextMove.yRot())
                    && (nextMove.yRot() != u.yRot() || nextMove.xRot() != u.xRot())) {
                problems.add(String.format(Locale.ROOT, "the next movement packet reports (%.2f, %.2f), the use said (%.2f, %.2f)"
                        + " - BadPacketsJ", nextMove.yRot(), nextMove.xRot(), u.yRot(), u.xRot()));
            }
        }
        if (swap) {
            int carries = 0;
            Ev lastCarry = null;
            for (int i = 0; i < ui; i++) {
                if (evs.get(i).kind().equals("carry")) {
                    carries++;
                    lastCarry = evs.get(i);
                }
            }
            if (lastCarry == null || lastCarry.slot() != 3) {
                problems.add("no hotbar change to slot 4 before the use");
            } else {
                int li = indexOf(evs, lastCarry);
                for (int i = li + 1; i < ui; i++) {
                    if (evs.get(i).kind().equals("move")) {
                        problems.add("a movement packet went out between the hotbar change and the use");
                        break;
                    }
                }
            }
            int after = 0;
            for (int i = ui + 1; i < evs.size(); i++) {
                if (evs.get(i).kind().equals("carry") && evs.get(i).slot() == 3) {
                    after++;
                }
            }
            if (carries > 1 || after > 0) {
                problems.add("the hotbar change to slot 4 was sent " + (carries + after) + " times (BadPacketsA)");
            }
        }
        Ev later = endAt(evs, entry.tick() + 12);
        double carried = later == null ? 0 : later.x() - entry.x();
        c.note(String.format(Locale.ROOT, "travelled %.2f blocks in the 12 ticks after the USE", carried));
        if (carried < 1.5) {
            problems.add(String.format(Locale.ROOT, "the USE ended the run (%.2f blocks in 12 ticks)", carried));
        }
        if (!problems.isEmpty()) {
            throw new AssertionError(String.join("; ", problems));
        }
    }

    // ------------------------------------------------------------------------------------------------- BLOCK / BOOM

    /**
     * RUN, then a BLOCK (a slab placed on the floor beside the lane, swapped to from slot 6 and back to slot 1) or a
     * BOOM (a Superboom tap on the floor ahead, swapped to slot 7). On the wire: one hotbar change right before the
     * place / dig with no movement packet between, the place / dig the next thing after the movement packet that
     * entered the box, a Block's swap back sent once, and the run still going. GrimAC judges the rest (Post,
     * BadPacketsA, RotationPlace) because the case is a plain test.
     */
    private static void caseInteract(Session c, String type) {
        ClientGameTestContext ctx = c.ctx();
        ctx.getInput().pressKey(options -> options.keyHotbarSlots[0]);
        ctx.waitTicks(3);
        double z = lane();
        boolean block = type.equals("BLOCK");
        int itemSlot = block ? 5 : 6;
        Object run = runNode(z);
        Object n = node(type, LANE_X0 + 10.0, z, block ? 0f : -90f, 60f, 1.0, 3.0);
        Object stop = node("STOP", LANE_X0 + 22.0, z, -90f, 0f, 2.0, 3.0);
        startProbe(ctx);
        walkIntoRun(c, z, run, n, stop);
        ctx.waitTicks(60); // the box is ~10 blocks in: entered around trace tick 50, then 12 more to measure
        List<Ev> evs = stopProbe(ctx);
        Ev entry = firstInside(ctx, evs, n);
        if (entry == null) {
            throw new AssertionError("never entered the " + type + " box");
        }
        dump(c, evs, entry.tick() - 1, entry.tick() + 4);
        int act = -1;
        int acts = 0;
        for (int i = 0; i < evs.size(); i++) {
            Ev e = evs.get(i);
            boolean hit = block ? e.kind().equals("useon")
                    : e.kind().equals("action") && e.extra().equals("START_DESTROY_BLOCK");
            if (hit) {
                acts++;
                if (act < 0) {
                    act = i;
                }
            }
        }
        if (acts != 1) {
            throw new AssertionError("expected exactly one " + (block ? "block place" : "Superboom dig") + ", saw " + acts);
        }
        int entryMove = -1;
        for (int i = 0; i < evs.size() && entryMove < 0; i++) {
            Ev e = evs.get(i);
            if (e.kind().equals("move") && !Double.isNaN(e.x())) {
                boolean in = onClient(ctx, mc -> contains(n, new Vec3(e.x(), e.y(), e.z())));
                if (in) {
                    entryMove = i;
                }
            }
        }
        List<String> problems = new ArrayList<>();
        if (entryMove < 0 || act < entryMove) {
            problems.add("the " + type + " went out before any movement packet put him in its box");
        } else {
            int between = 0;
            for (int i = entryMove + 1; i < act; i++) {
                if (evs.get(i).kind().equals("move")) {
                    between++;
                }
            }
            c.note("movement packets between the one that entered the box and the " + type + ": " + between);
            if (between != 0) {
                problems.add(between + " movement packet(s) between entering the box and the " + type);
            }
        }
        int to = 0, back = 0, lastTo = -1;
        for (int i = 0; i < evs.size(); i++) {
            Ev e = evs.get(i);
            if (e.kind().equals("carry")) {
                if (e.slot() == itemSlot) {
                    to++;
                    lastTo = i;
                } else if (e.slot() == 0 && i > act) {
                    back++;
                }
            }
        }
        c.note("hotbar changes: to slot " + (itemSlot + 1) + " x" + to + ", back to slot 1 x" + back);
        if (to != 1) {
            problems.add("the change to slot " + (itemSlot + 1) + " was sent " + to + " times");
        } else {
            if (lastTo > act) {
                problems.add("the hotbar change went out after the " + type);
            }
            for (int i = lastTo + 1; i < act; i++) {
                if (evs.get(i).kind().equals("move")) {
                    problems.add("a movement packet went out between the hotbar change and the " + type);
                    break;
                }
            }
        }
        if (block && back != 1) {
            problems.add("the swap back to slot 1 was sent " + back + " times");
        }
        Ev later = endAt(evs, entry.tick() + 12);
        double carried = later == null ? 0 : later.x() - entry.x();
        c.note(String.format(Locale.ROOT, "travelled %.2f blocks in the 12 ticks after the %s", carried, type));
        if (carried < 1.5) {
            problems.add(String.format(Locale.ROOT, "the %s ended the run (%.2f blocks in 12 ticks)", type, carried));
        }
        if (!problems.isEmpty()) {
            throw new AssertionError(String.join("; ", problems));
        }
    }

    // ------------------------------------------------------------------------------------------------- held walk

    /** Which node types may end a held walk (killer560: a walk/run replaces it, a stop or an align ends it). */
    private static boolean endsHold(String type) {
        return switch (type) {
            case "STOP", "ALIGN", "AXIS_ALIGN", "FAST_ALIGN", "WALK", "RUN", "LEAP", "PATH" -> true;
            default -> false;
        };
    }

    private static void caseHold(Session c, String name, String type) {
        ClientGameTestContext ctx = c.ctx();
        ctx.getInput().pressKey(options -> options.keyHotbarSlots[0]);
        ctx.waitTicks(2);
        double z = lane();
        Object run = runNode(z);
        float yaw = switch (type) {
            case "WALK" -> 0f;           // turns him south (+z): a replaced hold
            case "BLOCK" -> 0f;          // places to the side of the lane, not in front of him
            case "BOOM" -> -90f;
            default -> -90f;
        };
        float pitch = switch (type) {
            case "BLOCK", "BOOM" -> 60f;
            case "USE" -> -40f;
            default -> 0f;
        };
        Object n = node(type, LANE_X0 + 10.0, z, yaw, pitch, 1.0, 3.0);
        if (type.equals("AXIS_ALIGN")) {
            set(n, "wallDir", net.minecraft.core.Direction.NORTH);
        }
        startProbe(ctx);
        walkIntoRun(c, z, run, n);
        ctx.waitTicks(45);
        List<Ev> evs = stopProbe(ctx);
        Ev entry = firstInside(ctx, evs, n);
        if (entry == null) {
            throw new AssertionError("never entered the " + type + " box");
        }
        Ev later = endAt(evs, entry.tick() + 15);
        if (later == null) {
            throw new AssertionError("the trace ended before 15 ticks after the " + type + " node");
        }
        double dx = later.x() - entry.x();
        double dz = later.z() - entry.z();
        boolean kept = dx > 2.0;
        c.note(String.format(Locale.ROOT, "%s: in the 15 ticks after entering it he moved %.2f along the run (+x) and "
                + "%.2f across it (+z) -> %s", type, dx, dz, type.equals("WALK") ? (dz > 2.0 ? "walk replaced the run"
                : "no new walk") : kept ? "run KEPT" : "run ENDED"));
        boolean expectKeep = !endsHold(type);
        if (type.equals("WALK")) {
            if (dz < 2.0 || Math.abs(dx) > 1.0) {
                throw new AssertionError(String.format(Locale.ROOT, "hitting a WALK during a RUN should start that walk: "
                        + "moved %.2f along the run and %.2f along the walk", dx, dz));
            }
            return;
        }
        if (expectKeep != kept) {
            throw new AssertionError(String.format(Locale.ROOT, "%s %s the held run (%.2f blocks in 15 ticks)", type,
                    kept ? "kept" : "ended", dx));
        }
    }

    /** A BOOM keeps the run now; a STOP hit while the boom is still waiting for its confirmation must stop him then. */
    private static void caseBoomThenStop(Session c) {
        ClientGameTestContext ctx = c.ctx();
        ctx.getInput().pressKey(options -> options.keyHotbarSlots[0]);
        ctx.waitTicks(2);
        double z = lane();
        Object run = runNode(z);
        Object boom = node("BOOM", LANE_X0 + 10.0, z, -90f, 60f, 1.0, 3.0);
        Object stop = node("STOP", LANE_X0 + 14.0, z, -90f, 0f, 1.0, 3.0);
        startProbe(ctx);
        walkIntoRun(c, z, run, boom, stop);
        ctx.waitTicks(80); // ~12 blocks to the STOP at sprint speed is ~45 ticks
        List<Ev> evs = stopProbe(ctx);
        Ev atStop = firstInside(ctx, evs, stop);
        if (atStop == null) {
            Ev atBoom = firstInside(ctx, evs, boom);
            throw new AssertionError("never reached the STOP 4 blocks past the BOOM"
                    + (atBoom == null ? " (nor the BOOM)" : " - the BOOM ended the run"));
        }
        Ev later = endAt(evs, atStop.tick() + 12);
        double slid = later == null ? 99 : later.x() - atStop.x();
        c.note(String.format(Locale.ROOT, "after the STOP he moved %.2f blocks in 12 ticks", slid));
        if (slid > 1.0) {
            throw new AssertionError(String.format(Locale.ROOT, "the STOP waited behind the BOOM: he ran on %.2f blocks", slid));
        }
    }

    // ------------------------------------------------------------------------------------------------- stopwatch HUD

    private static long msSinceHud(ClientGameTestContext ctx) {
        return onClient(ctx, mc -> (Long) ModUnderTest.staticCall("com.killer560.hub.hud.HudSeen", "msSince",
                new Class<?>[]{String.class, long.class}, new Object[]{"ap3_stopwatch", System.currentTimeMillis()}));
    }

    private static void caseStopwatchHud(Session c) {
        ClientGameTestContext ctx = c.ctx();
        onClient(ctx, mc -> {
            ModUnderTest.set(ModUnderTest.config(CONFIG), "setStopwatchHud", true);
            resetStopwatch(); // an earlier case (63-ap3-hold-stopwatch) leaves one running
            return null;
        });
        double z = lane();
        Object run = runNode(z);
        Object start = node("STOPWATCH", LANE_X0 + 8.0, z, -90f, 0f, 1.0, 3.0);
        Object end = node("STOPWATCH", LANE_X0 + 14.0, z, -90f, 0f, 1.0, 3.0);
        Object stop = node("STOP", LANE_X0 + 20.0, z, -90f, 0f, 2.0, 3.0);
        try {
            walkIntoRun(c, z, run, start, end, stop);
            c.waitUntil("the stopwatch started", mc -> (Long) ModUnderTest.staticCall(EXEC, "stopwatchRunningMs") >= 0, 60);
            ctx.waitTicks(3);
            long running = msSinceHud(ctx);
            c.waitUntil("the stopwatch stopped", mc -> (Long) ModUnderTest.staticCall(EXEC, "lastStopwatchMs") >= 0, 80);
            ctx.waitTicks(20);
            long justStopped = msSinceHud(ctx);
            ctx.waitTicks(150); // ~8.5 s after the stop
            long at8 = msSinceHud(ctx);
            ctx.waitTicks(60);  // ~11.5 s
            long at11 = msSinceHud(ctx);
            c.note("HUD last drawn: running " + running + " ms ago, 1 s after stop " + justStopped + " ms ago, ~8.5 s "
                    + at8 + " ms ago, ~11.5 s " + at11 + " ms ago");
            if (running > 500 || justStopped > 500) {
                throw new AssertionError("the HUD did not show while the stopwatch ran / right after it stopped - the "
                        + "test proves nothing (running " + running + ", stopped " + justStopped + ")");
            }
            if (at8 > 500) {
                throw new AssertionError("the HUD hid before 10 s (" + at8 + " ms since drawn at ~8.5 s)");
            }
            if (at11 < 1000) {
                throw new AssertionError("the HUD is still showing ~11.5 s after the stopwatch stopped");
            }
            // The HUD editor still previews it.
            onClient(ctx, mc -> {
                try {
                    Screen editor = (Screen) Class.forName("com.killer560.hub.hud.HudEditorScreen")
                            .getConstructor(Screen.class).newInstance((Object) null);
                    McCompat.setScreen(mc, editor);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
                return null;
            });
            ctx.waitTicks(5);
            // HudSeen does not stamp while the editor is open (markDrawn returns early there), so the preview is read
            // off the screen: the stopped time is drawn in GOOD green (0x55FF55), which nothing else here uses.
            String editorState = onClient(ctx, mc -> {
                Screen sc = McCompat.screen(mc);
                List<String> ids = new ArrayList<>();
                try {
                    Field f = sc.getClass().getDeclaredField("shown");
                    f.setAccessible(true);
                    for (Object el : (java.util.Collection<?>) f.get(sc)) {
                        ids.add(String.valueOf(ModUnderTest.call(el, "id", new Class<?>[]{}, new Object[]{})));
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                    ids.add(e.toString());
                }
                return (sc == null ? "no screen" : sc.getClass().getSimpleName()) + " " + ids;
            });
            java.nio.file.Path shot = ctx.takeScreenshot(Report.fileName(c.name() + "-editor"));
            Report.screenshot(c.name(), shot);
            int green = 0;
            try {
                java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(shot.toFile());
                for (int y = 0; y < img.getHeight(); y++) {
                    for (int x = 0; x < img.getWidth(); x++) {
                        int rgb = img.getRGB(x, y);
                        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                        if (g > 230 && r < 110 && b < 110) {
                            green++;
                        }
                    }
                }
            } catch (java.io.IOException e) {
                throw new AssertionError("could not read the editor screenshot", e);
            }
            onClient(ctx, mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
            c.note("HUD editor ~12 s after the stop: " + editorState + ", " + green + " green preview pixel(s)");
            if (!editorState.contains("ap3_stopwatch") || green < 10) {
                throw new AssertionError("the HUD editor no longer previews the stopwatch");
            }
            // And a new start shows it again.
            double z2 = lane();
            walkIntoRun(c, z2, runNode(z2), node("STOPWATCH", LANE_X0 + 8.0, z2, -90f, 0f, 1.0, 3.0),
                    node("STOP", LANE_X0 + 14.0, z2, -90f, 0f, 2.0, 3.0));
            c.waitUntil("the stopwatch started again",
                    mc -> (Long) ModUnderTest.staticCall(EXEC, "stopwatchRunningMs") >= 0, 60);
            ctx.waitTicks(3);
            long again = msSinceHud(ctx);
            c.note("restarted: drawn " + again + " ms ago");
            if (again > 500) {
                throw new AssertionError("the HUD did not come back when the stopwatch started again");
            }
        } finally {
            onClient(ctx, mc -> {
                ModUnderTest.set(ModUnderTest.config(CONFIG), "setStopwatchHud", false);
                return null;
            });
        }
    }

    // ------------------------------------------------------------------------------------------------- corrections

    private static final String SOUNDS = "com.killer560.hub.util.ModSounds";
    private static final String CORRECTIONS = "com.killer560.hub.util.ServerCorrections";

    /**
     * Mod rule (killer560, 2026-10-06): a server correction never stops AP3 - it posts a chat line, plays the correction
     * alarm, and AP3 carries on. Two corrections 10 ticks apart: under the old rule the first stopped AP3 and the pair
     * (two inside 10 s) switched it off, so this is also the circuit breaker's case. The move is a server {@code tp}
     * relative to him, which reaches the client as the same position packet a setback does.
     */
    private static void caseCorrection(Session c) {
        ClientGameTestContext ctx = c.ctx();
        try {
            double z = lane();
            long mark = LogTap.mark();
            int alarmsBefore = onClient(ctx, mc -> (Integer) ModUnderTest.staticCall(SOUNDS, "correctionAlarmsPlayed"));
            int reportsBefore = onClient(ctx, mc -> (Integer) ModUnderTest.staticCall(CORRECTIONS, "reports"));
            walkIntoRun(c, z, runNode(z));
            ctx.waitTicks(6);
            double before = onClient(ctx, mc -> mc.player.getX());
            c.server().command("execute as @p at @s run tp @s ~0.3 ~ ~"); // relative to HIM, not the console
            ctx.waitTicks(10);
            boolean runningBetween = onClient(ctx, mc -> (Boolean) ModUnderTest.staticCall(EXEC, "isRunning"));
            c.server().command("execute as @p at @s run tp @s ~0.3 ~ ~");
            ctx.waitTicks(15);
            double after = onClient(ctx, mc -> mc.player.getX());
            boolean running = onClient(ctx, mc -> (Boolean) ModUnderTest.staticCall(EXEC, "isRunning"));
            boolean enabled = onClient(ctx, mc -> ModUnderTest.getBoolean(ModUnderTest.config(CONFIG), "isEnabledRaw"));
            int alarms = onClient(ctx, mc -> (Integer) ModUnderTest.staticCall(SOUNDS, "correctionAlarmsPlayed"))
                    - alarmsBefore;
            int reports = onClient(ctx, mc -> (Integer) ModUnderTest.staticCall(CORRECTIONS, "reports")) - reportsBefore;
            List<String> log = LogTap.since(mark);
            long seen = log.stream().filter(s -> s.contains("Server correction #")).count();
            long reportLines = log.stream().filter(s -> s.contains("[Correction] AP3:")).count();
            boolean chatLine = log.stream().anyMatch(s -> s.contains("[CHAT]") && s.contains("Server correction:"));
            boolean stopped = log.stream().anyMatch(s -> s.contains("stopped to avoid flags") || s.contains("AP3 is OFF"));
            c.note(String.format(Locale.ROOT, "two corrections: AP3 saw %d, reports %d (%d log line(s)), alarms %d, chat"
                            + " line=%s, moved %.2f in the 25 ticks after the first, running between=%s after=%s, enabled=%s,"
                            + " stop/disable line=%s", seen, reports, reportLines, alarms, chatLine, after - before,
                    runningBetween, running, enabled, stopped));
            if (seen < 2) {
                throw new AssertionError("AP3 saw " + seen + " of the 2 server moves - the case proves nothing");
            }
            if (!runningBetween || !running || !enabled || stopped) {
                throw new AssertionError("a correction stopped or disabled AP3 (running between=" + runningBetween
                        + ", after=" + running + ", enabled=" + enabled + ", stop line=" + stopped + ")");
            }
            if (after - before < 2.0) {
                throw new AssertionError(String.format(Locale.ROOT, "AP3 did not carry on: moved only %.2f",
                        after - before));
            }
            if (reports < 2 || reportLines < 2 || !chatLine) {
                throw new AssertionError("each correction must be reported, with a chat line (reports " + reports
                        + ", log lines " + reportLines + ", chat line " + chatLine + ")");
            }
            if (alarms < 1) {
                throw new AssertionError("the correction alarm never played");
            }
        } finally {
            onClient(ctx, mc -> {
                ModUnderTest.staticCall(EXEC, "stop", new Class<?>[]{String.class}, new Object[]{"test end"});
                return null;
            });
        }
    }

    // ------------------------------------------------------------------------------------------------- command tree

    private static boolean hasRoot(Minecraft mc, String name) {
        return mc.getConnection() != null && mc.getConnection().getCommands().getRoot().getChild(name) != null;
    }

    private static List<String> suggest(Minecraft mc, String input) {
        var dispatcher = mc.getConnection().getCommands();
        var parse = dispatcher.parse(input, mc.getConnection().getSuggestionsProvider());
        try {
            var s = dispatcher.getCompletionSuggestions(parse).get(5, java.util.concurrent.TimeUnit.SECONDS);
            List<String> out = new ArrayList<>();
            s.getList().forEach(x -> out.add(x.getText()));
            return out;
        } catch (Exception e) {
            return List.of("<error " + e + ">");
        }
    }

    private static void caseCommandTree(Session c) {
        ClientGameTestContext ctx = c.ctx();
        String arCfg = "com.killer560.hub.autoroutes.AutoRoutesConfig";
        onClient(ctx, mc -> {
            ModUnderTest.set(ModUnderTest.config(CONFIG), "setEnabled", false);
            ModUnderTest.set(ModUnderTest.config(arCfg), "setEnabled", false);
            return null;
        });
        try {
            c.scenario().reconnect();
            ctx.waitTicks(20);
            boolean ap3Before = onClient(ctx, mc -> hasRoot(mc, "ap3"));
            boolean arBefore = onClient(ctx, mc -> hasRoot(mc, "ar"));
            c.note("joined with both off: /ap3 in tree=" + ap3Before + ", /ar in tree=" + arBefore);
            if (ap3Before || arBefore) {
                throw new AssertionError("precondition: joined with AP3 and Auto Routes off, yet the tree already has "
                        + "them - nothing to test");
            }
            onClient(ctx, mc -> {
                ModUnderTest.set(ModUnderTest.config(CONFIG), "setEnabled", true);
                ModUnderTest.set(ModUnderTest.config(arCfg), "setEnabled", true);
                return null;
            });
            ctx.waitTicks(3);
            List<String> ap3 = onClient(ctx, mc -> hasRoot(mc, "ap3") ? suggest(mc, "ap3 ") : List.of());
            List<String> ar = onClient(ctx, mc -> hasRoot(mc, "ar") ? suggest(mc, "ar ") : List.of());
            c.note("after switching on: /ap3 suggests " + ap3 + "; /ar suggests " + ar);
            // Toggle again: no duplicate children, no error.
            onClient(ctx, mc -> {
                ModUnderTest.set(ModUnderTest.config(CONFIG), "setEnabled", false);
                return null;
            });
            ctx.waitTicks(2);
            onClient(ctx, mc -> {
                ModUnderTest.set(ModUnderTest.config(CONFIG), "setEnabled", true);
                return null;
            });
            ctx.waitTicks(2);
            List<String> ap3Again = onClient(ctx, mc -> suggest(mc, "ap3 "));
            if (!ap3.contains("add") || ar.isEmpty()) {
                throw new AssertionError("switching AP3 / Auto Routes on mid-session did not bring their tab completion back");
            }
            if (ap3Again.size() != ap3.size()) {
                throw new AssertionError("a second off/on changed /ap3's suggestions: " + ap3 + " -> " + ap3Again);
            }
        } finally {
            onClient(ctx, mc -> {
                ModUnderTest.set(ModUnderTest.config(CONFIG), "setEnabled", true);
                ModUnderTest.set(ModUnderTest.config(arCfg), "setEnabled", false);
                return null;
            });
        }
    }

    // ------------------------------------------------------------------------------------------------- util

    /** {@code Ap3Executor.resetStopwatch()} is package-private (the world-change reset). */
    private static void resetStopwatch() {
        try {
            var m = Class.forName(EXEC).getDeclaredMethod("resetStopwatch");
            m.setAccessible(true);
            m.invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Ap3Executor.resetStopwatch", e);
        }
    }

    static <T> T onClient(ClientGameTestContext ctx, java.util.function.Function<Minecraft, T> fn) {
        return ctx.computeOnClient(fn::apply);
    }
}
