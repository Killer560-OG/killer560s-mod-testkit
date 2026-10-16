package dev.testkit.gametest;

import dev.testkit.harness.PacketWatch;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code 1015-grim-puzzle-nowait} (mod branch puzzles-nowait-1015): the packet orders the no-wait auto puzzles send after a
 * landing, against a real GrimAC. Played at START_CLIENT_TICK - where the autos run - with the mod's own primitives
 * ({@code AutoPuzzleUtil.rotateCamera}, {@code gateWorldClick}, {@code interactBlock}, {@code useItemRotated}), each
 * landing a server teleport that keeps his rotation, as an etherwarp does (a vanilla server has no etherwarp):
 * <ul>
 *   <li><b>click rounds</b> (reward chest, Tic Tac Toe's board, Blaze's secret chest): he lands sneaking; in the landing
 *   tick sneak is let go and the body turns onto a lever; the next tick the lever is clicked and, after it in the same
 *   tick, the body turns to a warp's look with sneak down (Tic Tac Toe's grab-secret warp); the next tick the warp's use.
 *   Before, the landing waited 2 ticks for the sneak, the click took a turn tick, the warp a 2-tick sneak wait.</li>
 *   <li><b>bow rounds</b> (Blaze, Creeper Beams, Ice Path): sneak held throughout; in the landing tick the bow goes back
 *   in hand; the next tick the shot (its look in the use packet); the next tick the AOTV back in hand and the next warp's
 *   use in that same tick (sneak already heard). Before, the gate's 6-tick teleport settle held the shot.</li>
 *   <li><b>talk rounds</b> (Auto Three Weirdos after its own warp into reach, mod branch weirdos-reach-1015): in the landing
 *   tick sneak is let go and the body turns onto the first "CLICK" armour stand ({@code AutoPuzzleUtil.aimBodyAt} on its
 *   box); the next tick the interact (no turn tick: {@code gateWorldClick} must let it straight through) and, after it in
 *   the same tick, the body onto the second stand; the next tick the second interact. GrimAC judges each interact against
 *   the reported look (Hitboxes) and its reach.</li>
 * </ul>
 * Not vacuous: every action must go on its planned tick after the landing (+1 / +2), every lever must end flipped, the
 * uses must have gone out, and GrimAC must say nothing ({@link Scenario#run}, after its positive control).
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class GrimPuzzleNoWaitTests implements FabricClientGameTest {

    private static final String NAME = "1015-grim-puzzle-nowait";
    private static final String UTIL = "com.killer560.hub.autopuzzles.AutoPuzzleUtil";
    private static final String DUNGEON_LABEL = "localhost.p3sim.net:25565";
    private static final int SURFACE_Y = 151;
    private static final int BASE_X = -700;
    private static final int BASE_Z = -360;
    private static final int CLICK_ROUNDS = 3;
    private static final int BOW_ROUNDS = 3;
    private static final int TURN_ROUNDS = 2;
    private static final int TALK_ROUNDS = 2;
    private static final String FREECAM = "com.killer560.hub.autopuzzles.FreeCam";

    private enum Stage { IDLE, AWAIT_LAND, ACT1, ACT2, DONE }

    // ---- driven at START_CLIENT_TICK ----
    private static volatile Stage stage = Stage.IDLE;
    private static volatile boolean bowRound;
    private static volatile boolean turnRound;
    private static volatile boolean talkRound;
    /** The talk round's two stands, in talk order (found on the client by position each round). */
    private static volatile net.minecraft.world.entity.Entity[] talkStands;
    private static Object freeCam;
    private static volatile BlockPos lever;
    private static volatile Vec3 warpTarget;
    private static volatile Vec3 shotTarget;
    private static Vec3 lastPos;
    private static long tick;
    private static long landTick;
    private static boolean hooked;
    private static final List<String> STEPS = new ArrayList<>();
    private static final List<String> BAD = new ArrayList<>();

    private static void onStart(Minecraft mc) {
        tick++;
        LocalPlayer p = mc.player;
        if (p == null) {
            return;
        }
        Vec3 pos = p.position();
        boolean landed = lastPos != null && pos.distanceToSqr(lastPos) > 0.81;
        lastPos = pos;
        switch (stage) {
            case AWAIT_LAND -> {
                if (!landed) {
                    return;
                }
                landTick = tick;
                // What AutoReposition does on its own landing: the gate's teleport settle is waived for the puzzle actor.
                try {
                    Class<?> actor = Class.forName("com.killer560.hub.util.ActionGate$Actor");
                    ModUnderTest.staticCall("com.killer560.hub.util.ActionGate", "expectSelfTeleport",
                            new Class<?>[]{actor, int.class}, new Object[]{ModUnderTest.enumValue(
                                    "com.killer560.hub.util.ActionGate$Actor", "PUZZLE_WORLD"), 6});
                } catch (ClassNotFoundException e) {
                    throw new AssertionError(e);
                }
                if (talkRound) {
                    mc.options.keyShift.setDown(false);
                    freeCam = newFreeCam();
                    boolean turned = aimBodyAtBox(p, talkStands[0]);
                    step("landed: sneak let go, body onto the first stand (turned " + turned + ")");
                } else if (turnRound) {
                    // An auto puzzle's own free camera held, the body a quarter turn off the lever: the click must turn
                    // the body at once (util/TurnFirst, no foreign wait for the auto's own camera) and go the tick after.
                    mc.options.keyShift.setDown(false);
                    freeCam = newFreeCam();
                    callCam("engage", p);
                    aimStanding(p, lever);
                    ModUnderTest.staticCall(UTIL, "rotateCamera", new Class<?>[]{LocalPlayer.class, float.class,
                            float.class}, new Object[]{p, p.getYRot() + 90f, 0f});
                    step("landed: sneak let go, free camera held, body 90 degrees off " + lever.toShortString());
                } else if (bowRound) {
                    p.getInventory().setSelectedSlot(1); // the bow back in hand; sneak stays held
                    step("landed: bow in hand, sneak kept");
                } else {
                    mc.options.keyShift.setDown(false); // let go of the landing's sneak
                    aimStanding(p, lever);
                    step("landed: sneak let go, body on " + lever.toShortString());
                }
                stage = Stage.ACT1;
            }
            case ACT1 -> {
                if (talkRound) {
                    talk(mc, p, talkStands[0], "+1 tick after the landing");
                    aimBodyAtBox(p, talkStands[1]); // after the interact, this same tick
                    stage = Stage.ACT2;
                    return;
                }
                if (turnRound) {
                    float before = p.getYRot();
                    boolean gate = (Boolean) ModUnderTest.staticCall(UTIL, "gateWorldClick",
                            new Class<?>[]{Minecraft.class, BlockPos.class}, new Object[]{mc, lever});
                    step(String.format(Locale.ROOT, "gateWorldClick +1 tick: %s, body turned %.1f deg, TurnFirst foreignWait %d",
                            gate ? "click now (?!)" : "held", p.getYRot() - before, foreignWait()));
                    if (gate) {
                        synchronized (BAD) {
                            BAD.add("gateWorldClick let a click through with the body 90 degrees off the lever");
                        }
                    }
                    stage = Stage.ACT2;
                    return;
                }
                if (bowRound) {
                    float[] d = direction(p, shotTarget, 1.27);
                    boolean shot = (Boolean) ModUnderTest.staticCall(UTIL, "useItemRotated", new Class<?>[]{
                            Minecraft.class, LocalPlayer.class, float.class, float.class}, new Object[]{mc, p, d[0], d[1]});
                    expect(shot, "the shot +1 tick after the landing");
                } else {
                    boolean gate = (Boolean) ModUnderTest.staticCall(UTIL, "gateWorldClick",
                            new Class<?>[]{Minecraft.class, BlockPos.class}, new Object[]{mc, lever});
                    expect(gate, "the click on " + lever.toShortString() + " +1 tick after the landing (body already on it)");
                    if (gate) {
                        ModUnderTest.staticCall(UTIL, "interactBlock", new Class<?>[]{Minecraft.class, BlockPos.class},
                                new Object[]{mc, lever});
                    }
                    // after the click, this same tick: the warp's look and the sneak (grab-secret warp)
                    float[] w = direction(p, warpTarget, 1.27);
                    ModUnderTest.staticCall(UTIL, "rotateCamera", new Class<?>[]{LocalPlayer.class, float.class,
                            float.class}, new Object[]{p, w[0], w[1]});
                    mc.options.keyShift.setDown(true);
                }
                stage = Stage.ACT2;
            }
            case ACT2 -> {
                if (talkRound) {
                    talk(mc, p, talkStands[1], "+2 ticks after the landing (body turned onto it the tick before)");
                    callCam("release", p);
                    stage = Stage.DONE;
                    return;
                }
                if (turnRound) {
                    boolean gate = (Boolean) ModUnderTest.staticCall(UTIL, "gateWorldClick",
                            new Class<?>[]{Minecraft.class, BlockPos.class}, new Object[]{mc, lever});
                    expect(gate, "the click on " + lever.toShortString() + " +2 ticks after the landing (turned the tick "
                            + "before, under the auto's own camera; TurnFirst foreignWait " + foreignWait() + ")");
                    if (gate) {
                        ModUnderTest.staticCall(UTIL, "interactBlock", new Class<?>[]{Minecraft.class, BlockPos.class},
                                new Object[]{mc, lever});
                    }
                    callCam("release", p);
                    stage = Stage.DONE;
                    return;
                }
                if (bowRound) {
                    p.getInventory().setSelectedSlot(0); // the AOTV stand-in back, and its use in the same tick
                }
                float[] w = direction(p, warpTarget, 1.27);
                boolean used = (Boolean) ModUnderTest.staticCall(UTIL, "useItemRotated", new Class<?>[]{
                        Minecraft.class, LocalPlayer.class, float.class, float.class}, new Object[]{mc, p, w[0], w[1]});
                expect(used, (bowRound ? "the next warp's use (sneak held)" : "the warp's use") + " +2 ticks after the landing");
                stage = Stage.DONE;
            }
            default -> {
            }
        }
    }

    /** Auto Three Weirdos' talk: the gate (which must not need a turn - the body is already on the stand), then the
     *  interact at the point of the stand's box on the line to its centre, and the swing. */
    private static void talk(Minecraft mc, LocalPlayer p, net.minecraft.world.entity.Entity stand, String when) {
        boolean gate = (Boolean) ModUnderTest.staticCall(UTIL, "gateWorldClick",
                new Class<?>[]{Minecraft.class, net.minecraft.world.entity.Entity.class}, new Object[]{mc, stand});
        expect(gate, "the talk to the stand at " + stand.blockPosition().toShortString() + " " + when);
        if (gate) {
            Vec3 eye = p.getEyePosition();
            var box = stand.getBoundingBox();
            Vec3 aim = box.clip(eye, box.getCenter()).orElse(box.getCenter());
            mc.gameMode.interact(p, stand, new net.minecraft.world.phys.EntityHitResult(stand, aim),
                    net.minecraft.world.InteractionHand.MAIN_HAND);
            p.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        }
    }

    private static boolean aimBodyAtBox(LocalPlayer p, net.minecraft.world.entity.Entity stand) {
        try {
            var m = Class.forName(UTIL).getDeclaredMethod("aimBodyAt", LocalPlayer.class,
                    net.minecraft.world.phys.AABB.class, Class.forName(FREECAM));
            m.setAccessible(true);
            return (Boolean) m.invoke(null, p, stand.getBoundingBox(), freeCam);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("no AutoPuzzleUtil.aimBodyAt(LocalPlayer, AABB, FreeCam) - this jar predates "
                    + "weirdos-reach-1015", e);
        }
    }

    private static void step(String s) {
        synchronized (STEPS) {
            STEPS.add("tick +" + (tick - landTick) + ": " + s);
        }
    }

    private static void expect(boolean ok, String what) {
        step(what + (ok ? " - sent" : " - HELD"));
        if (!ok) {
            synchronized (BAD) {
                BAD.add(what + " was held back");
            }
        }
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        List<BlockPos> levers = new ArrayList<>();
        for (int i = 0; i < CLICK_ROUNDS + TURN_ROUNDS; i++) {
            levers.add(new BlockPos(BASE_X + 3 * (i + 1), SURFACE_Y + 2, BASE_Z + 3));
        }
        Scenario.labelServerAs(DUNGEON_LABEL);
        Scenario.run(ctx, NAME,
                (server, scenario) -> {
                    TestMap map = TestMap.on(server)
                            .platform(BASE_X + 6, SURFACE_Y - 1, BASE_Z, 12)
                            .walls(5)
                            .catchFloor(120)
                            .survival()
                            .clearInventory()
                            .give("stick", 1)
                            .give("bow", 1)
                            .skyblockSidebar("F7")
                            .command("fill " + (BASE_X - 4) + " " + (SURFACE_Y + 1) + " " + (BASE_Z + 4) + " "
                                    + (BASE_X + 20) + " " + (SURFACE_Y + 3) + " " + (BASE_Z + 4) + " minecraft:stone");
                    for (BlockPos l : levers) {
                        map.command("setblock " + l.getX() + " " + l.getY() + " " + l.getZ()
                                + " minecraft:lever[face=wall,facing=north]");
                    }
                    // Two "CLICK" armour stands per talk round, two blocks either side of its landing and two further from
                    // the lever wall: 2.47 blocks eye to box, inside the 3.0 entity reach.
                    for (int t = 0; t < TALK_ROUNDS; t++) {
                        int tx = talkX(t);
                        for (int side : new int[]{-1, 1}) {
                            map.command(String.format(Locale.ROOT, "summon minecraft:armor_stand %.1f %d %.1f "
                                    + "{CustomName:\"CLICK\",CustomNameVisible:1b,NoGravity:1b,Invulnerable:1b}",
                                    tx + 0.5 + side * 2.0, SURFACE_Y, BASE_Z - 5.5));
                        }
                    }
                    map.spawn(BASE_X + 0.5, BASE_Z + 0.5, 0f).build();
                },
                (server, scenario) -> {
                    scenario.assertDetectorWorks();
                    ctx.waitTicks(30);
                    if (!hooked) {
                        hooked = true;
                        ClientTickEvents.START_CLIENT_TICK.register(GrimPuzzleNoWaitTests::onStart);
                    }
                    synchronized (STEPS) {
                        STEPS.clear();
                    }
                    synchronized (BAD) {
                        BAD.clear();
                    }
                    ctx.runOnClient(mc -> {
                        mc.player.getInventory().setSelectedSlot(0);
                        mc.options.keyShift.setDown(true); // he arrives sneaking, as from an etherwarp
                    });
                    ctx.waitTicks(5);
                    PacketWatch.start();
                    int spot = 0;
                    int talks = 0;
                    for (int r = 0; r < CLICK_ROUNDS + BOW_ROUNDS + TURN_ROUNDS + TALK_ROUNDS; r++) {
                        boolean bow = r >= CLICK_ROUNDS && r < CLICK_ROUNDS + BOW_ROUNDS;
                        boolean talk = r >= CLICK_ROUNDS + BOW_ROUNDS + TURN_ROUNDS;
                        boolean turn = !talk && r >= CLICK_ROUNDS + BOW_ROUNDS;
                        int i = turn ? CLICK_ROUNDS + (r - CLICK_ROUNDS - BOW_ROUNDS) : r % CLICK_ROUNDS;
                        lever = levers.get(i);
                        bowRound = bow;
                        turnRound = turn;
                        talkRound = talk;
                        if (talk) {
                            int tx = talkX(r - CLICK_ROUNDS - BOW_ROUNDS - TURN_ROUNDS);
                            talkStands = ctx.computeOnClient(mc -> {
                                net.minecraft.world.entity.Entity[] two = new net.minecraft.world.entity.Entity[2];
                                for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
                                    if (e instanceof net.minecraft.world.entity.decoration.ArmorStand
                                            && Math.abs(e.getZ() - (BASE_Z - 5.5)) < 0.2) {
                                        if (Math.abs(e.getX() - (tx + 0.5 - 2.0)) < 0.2) {
                                            two[0] = e;
                                        } else if (Math.abs(e.getX() - (tx + 0.5 + 2.0)) < 0.2) {
                                            two[1] = e;
                                        }
                                    }
                                }
                                return two;
                            });
                            if (talkStands[0] == null || talkStands[1] == null) {
                                throw new AssertionError("talk round: the two CLICK stands by x " + tx + " are not on the "
                                        + "client - nothing to talk to");
                            }
                            talks += 2;
                        }
                        // The warp's look: a floor block 7 blocks behind him, like an etherwarp aim; the shot: up the wall.
                        warpTarget = new Vec3(BASE_X + 3 * (i + 1) + 0.5, SURFACE_Y, BASE_Z - 6.5);
                        shotTarget = new Vec3(BASE_X + 3 * (i + 1) + 0.5, SURFACE_Y + 12, BASE_Z + 3.5);
                        if (!bow) {
                            ctx.runOnClient(mc -> mc.options.keyShift.setDown(true));
                            ctx.waitTick();
                        }
                        stage = Stage.AWAIT_LAND;
                        int sx = talk ? talkX(r - CLICK_ROUNDS - BOW_ROUNDS - TURN_ROUNDS) : BASE_X + 3 * (i + 1);
                        synchronized (STEPS) {
                            STEPS.add("round " + (r + 1) + (bow ? " (bow)" : turn ? " (turn under own camera)"
                                    : talk ? " (talk to two stands)" : " (click)")
                                    + ": teleport onto " + sx + ", "
                                    + SURFACE_Y + ", " + BASE_Z);
                        }
                        server.command(String.format(Locale.ROOT, "execute as @p at @s run tp @s %.2f %d %.2f ~ ~",
                                sx + 0.5 + (spot++ % 2 == 0 ? 0.0 : 0.01), SURFACE_Y, talk ? BASE_Z - 3.5 : BASE_Z + 0.5));
                        for (int w = 0; w < 100 && stage != Stage.DONE; w++) {
                            ctx.waitTick();
                        }
                        if (stage != Stage.DONE) {
                            throw new AssertionError("round " + (r + 1) + " never finished (stage " + stage + ")");
                        }
                        stage = Stage.IDLE;
                        ctx.waitTicks(4);
                    }
                    ctx.runOnClient(mc -> mc.options.keyShift.setDown(false));
                    ctx.waitTicks(10);
                    PacketWatch.stop();
                    int flipped = 0;
                    for (BlockPos l : levers) {
                        if (ctx.computeOnClient(mc -> mc.level.getBlockState(l).getValue(LeverBlock.POWERED))) {
                            flipped++;
                        }
                    }
                    synchronized (STEPS) {
                        for (String s : STEPS) {
                            scenario.log(s);
                        }
                    }
                    scenario.log(PacketWatch.summary());
                    scenario.log(String.format(Locale.ROOT, "RESULT %d of %d levers flipped, %d item use(s), %d entity "
                            + "interact(s) (max %.2f eye to box), GrimAC %d line(s)", flipped, levers.size(),
                            PacketWatch.itemUses(), PacketWatch.entityInteracts(), PacketWatch.maxEntityReach(),
                            scenario.flags().size()));
                    synchronized (BAD) {
                        if (!BAD.isEmpty()) {
                            throw new AssertionError(String.join("; ", BAD));
                        }
                    }
                    if (flipped != levers.size()) {
                        throw new AssertionError(flipped + " of " + levers.size() + " levers flipped - the clicks did not land");
                    }
                    if (PacketWatch.entityInteracts() < talks) {
                        throw new AssertionError("only " + PacketWatch.entityInteracts() + " entity interact(s) went out, "
                                + talks + " expected - the talk rounds did not talk");
                    }
                    int uses = CLICK_ROUNDS + 2 * BOW_ROUNDS; // the turn and talk rounds use nothing
                    if (PacketWatch.itemUses() < uses) {
                        throw new AssertionError("only " + PacketWatch.itemUses() + " item use(s) went out, " + uses
                                + " expected");
                    }
                });
    }

    /** The x a talk round lands on (at z BASE_Z - 3.5, on the platform's other half from the lever wall). */
    private static int talkX(int t) {
        return BASE_X + 3 + 6 * t;
    }

    private static Object newFreeCam() {
        try {
            var c = Class.forName(FREECAM).getDeclaredConstructor(String.class);
            c.setAccessible(true);
            return c.newInstance("Grim test");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("no FreeCam(String)", e);
        }
    }

    private static void callCam(String method, LocalPlayer p) {
        try {
            var m = Class.forName(FREECAM).getDeclaredMethod(method, LocalPlayer.class);
            m.setAccessible(true);
            m.invoke(freeCam, p);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("FreeCam." + method, e);
        }
    }

    private static int foreignWait() {
        try {
            var f = Class.forName(UTIL).getDeclaredField("TURN");
            f.setAccessible(true);
            Object turn = f.get(null);
            var w = turn.getClass().getDeclaredField("foreignWait");
            w.setAccessible(true);
            return w.getInt(turn);
        } catch (ReflectiveOperationException e) {
            return -1;
        }
    }

    /** Turns the body (standing eye) onto the lever's surface point, as AutoPuzzleUtil.aimBodyAt does. */
    private static void aimStanding(LocalPlayer p, BlockPos target) {
        Minecraft mc = Minecraft.getInstance();
        Vec3 eye = new Vec3(p.getX(), p.getY() + 1.62, p.getZ());
        BlockHitResult hit = (BlockHitResult) ModUnderTest.staticCall("com.killer560.hub.util.BlockHits", "surface",
                new Class<?>[]{net.minecraft.world.level.BlockGetter.class, BlockPos.class, Vec3.class},
                new Object[]{mc.level, target, eye});
        Vec3 to = hit != null ? hit.getLocation() : Vec3.atCenterOf(target);
        float[] d = direction(p, to, 1.62);
        ModUnderTest.staticCall(UTIL, "rotateCamera", new Class<?>[]{LocalPlayer.class, float.class, float.class},
                new Object[]{p, d[0], d[1]});
    }

    private static float[] direction(LocalPlayer p, Vec3 to, double eyeHeight) {
        double dx = to.x - p.getX();
        double dy = to.y - (p.getY() + eyeHeight);
        double dz = to.z - p.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        return new float[]{yaw, pitch};
    }
}
