package dev.testkit.gametest;

import dev.testkit.harness.PacketWatch;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code 1018-beams-melee-grim} (mod branch todo-creeper-melee-1018): Auto Creeper Beams' Melee hit against a real GrimAC.
 * A vanilla server has no etherwarp and no Creeper Beams room, so - as {@code 1015-grim-puzzle-nowait} does - each round
 * lands him with a server teleport that keeps his rotation (what an etherwarp does) next to a sea lantern, and drives the
 * mod's own pieces at START_CLIENT_TICK, where the auto runs: in the landing tick sneak is let go and the body turned onto
 * the lantern ({@code AutoPuzzleUtil.aimBodyAt}, as {@code AutoBeams.meleeTick} does); the next tick
 * {@code AutoBeams.meleeClick} - the hit itself, vanilla's start / swing / abort.
 *
 * <p>Rounds: a lantern in a wall at eye height, one high in the wall, one in the floor, one 4.0 blocks off (eye to box),
 * one in mid-air five blocks up beside a floating lantern (where an AOTV teleport straight up leaves him, hit the tick after
 * the landing while he starts to fall), one where the body is left a quarter turn off (the hit must turn first and go the tick after, never on a look that
 * misses), and one from 9.5 blocks away (the reach check where the hit is sent must refuse it: nothing sent).
 *
 * <p>Not vacuous: every hit must go on its planned tick, one START_DESTROY_BLOCK per hit and none for the refused one, each
 * with a swing, at most 4.5 eye to box, every lantern still standing (a tap does not break one), and GrimAC must say nothing
 * ({@link Scenario#run}, after its positive control).
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class GrimBeamsMeleeTests implements FabricClientGameTest {

    private static final String NAME = "1018-beams-melee-grim";
    private static final String UTIL = "com.killer560.hub.autopuzzles.AutoPuzzleUtil";
    private static final String BEAMS = "com.killer560.hub.autopuzzles.AutoBeams";
    private static final String FREECAM = "com.killer560.hub.autopuzzles.FreeCam";
    private static final String DUNGEON_LABEL = "localhost.p3sim.net:25565";
    private static final int Y = 151;
    private static final int BX = -760;
    private static final int BZ = -420;

    private enum Kind { HIT, TURN, FAR, AIR }

    /** One round: where he lands (feet), the lantern, what is expected. */
    private record Round(String what, double x, double z, BlockPos lantern, Kind kind) {
    }

    private enum Stage { IDLE, AWAIT_LAND, ACT1, ACT2, DONE }

    private static volatile Stage stage = Stage.IDLE;
    private static volatile Round round;
    private static Object freeCam;
    private static Vec3 lastPos;
    private static long tick;
    private static long landTick;
    private static boolean hooked;
    private static int sneakWaits;
    private static int breaksBefore;
    private static final List<String> STEPS = new ArrayList<>();
    private static final List<String> BAD = new ArrayList<>();

    private static List<Round> rounds() {
        return List.of(
                new Round("wall, eye height", BX + 3.5, BZ + 1.5, new BlockPos(BX + 3, Y + 1, BZ + 4), Kind.HIT),
                new Round("high in the wall", BX + 6.5, BZ + 1.5, new BlockPos(BX + 6, Y + 3, BZ + 4), Kind.HIT),
                new Round("in the floor", BX + 9.5, BZ + 0.5, new BlockPos(BX + 9, Y - 1, BZ - 2), Kind.HIT),
                new Round("4.0 eye to box", BX + 12.5, BZ + 0.0, new BlockPos(BX + 12, Y + 1, BZ + 4), Kind.HIT),
                new Round("body a quarter turn off", BX + 15.5, BZ + 1.5, new BlockPos(BX + 15, Y + 2, BZ + 4), Kind.TURN),
                new Round("in the air, as after an AOTV teleport up", BX + 16.5, BZ - 0.5, new BlockPos(BX + 16, Y + 7, BZ + 1),
                        Kind.AIR),
                new Round("9.5 blocks away", BX + 0.5, BZ - 5.5, new BlockPos(BX + 1, Y + 2, BZ + 4), Kind.FAR));
    }

    private static void onStart(Minecraft mc) {
        tick++;
        LocalPlayer p = mc.player;
        if (p == null) {
            return;
        }
        Vec3 pos = p.position();
        boolean landed = lastPos != null && pos.distanceToSqr(lastPos) > 0.81;
        lastPos = pos;
        Round r = round;
        switch (stage) {
            case AWAIT_LAND -> {
                if (!landed) {
                    return;
                }
                landTick = tick;
                try {
                    Class<?> actor = Class.forName("com.killer560.hub.util.ActionGate$Actor");
                    ModUnderTest.staticCall("com.killer560.hub.util.ActionGate", "expectSelfTeleport",
                            new Class<?>[]{actor, int.class}, new Object[]{ModUnderTest.enumValue(
                                    "com.killer560.hub.util.ActionGate$Actor", "PUZZLE_WORLD"), 6});
                } catch (ClassNotFoundException e) {
                    throw new AssertionError(e);
                }
                mc.options.keyShift.setDown(false); // AutoReposition lets the sneak go on the landing
                freeCam = newFreeCam();
                callCam("engage", p);
                if (r.kind() == Kind.TURN) {
                    ModUnderTest.staticCall(UTIL, "rotateCamera", new Class<?>[]{LocalPlayer.class, float.class,
                            float.class}, new Object[]{p, p.getYRot() + 90f, 0f});
                    step("landed: sneak let go, body left 90 degrees off " + r.lantern().toShortString());
                } else {
                    boolean turned = aimBodyAt(p, r.lantern());
                    step("landed: sneak let go, body onto " + r.lantern().toShortString() + " (turned " + turned + ")");
                }
                stage = Stage.ACT1;
            }
            case ACT1 -> {
                if (p.isShiftKeyDown()) {
                    sneakWaits++;
                    step("still sneaking - the hit waits a tick, as meleeTick does");
                    return;
                }
                boolean sent = click(mc, p, r.lantern());
                double d = Math.sqrt(boxSq(p.getEyePosition(), r.lantern()));
                switch (r.kind()) {
                    case HIT, AIR -> expect(sent, String.format(Locale.ROOT, "the hit on %s at %.2f eye to box",
                            r.lantern().toShortString(), d), true);
                    case TURN -> expect(sent, "the hit with the body 90 degrees off (must turn, not send)", false);
                    case FAR -> expect(sent, String.format(Locale.ROOT, "the hit from %.2f eye to box (must be refused)", d),
                            false);
                }
                if (r.kind() == Kind.TURN) {
                    stage = Stage.ACT2;
                } else {
                    callCam("release", p);
                    stage = Stage.DONE;
                }
            }
            case ACT2 -> {
                boolean sent = click(mc, p, r.lantern());
                expect(sent, "the hit on " + r.lantern().toShortString() + " the tick after the turn", true);
                callCam("release", p);
                stage = Stage.DONE;
            }
            default -> {
            }
        }
    }

    /** Where a round lands his feet: the platform, or five blocks up in the air for the AOTV-style round. */
    private static int feetY(Round r) {
        return r.kind() == Kind.AIR ? Y + 5 : Y;
    }

    private static boolean click(Minecraft mc, LocalPlayer p, BlockPos lantern) {
        return (Boolean) ModUnderTest.staticCall(BEAMS, "meleeClick", new Class<?>[]{Minecraft.class, LocalPlayer.class,
                BlockPos.class}, new Object[]{mc, p, lantern});
    }

    private static double boxSq(Vec3 eye, BlockPos pos) {
        double dx = Math.max(0, Math.max(pos.getX() - eye.x, eye.x - (pos.getX() + 1)));
        double dy = Math.max(0, Math.max(pos.getY() - eye.y, eye.y - (pos.getY() + 1)));
        double dz = Math.max(0, Math.max(pos.getZ() - eye.z, eye.z - (pos.getZ() + 1)));
        return dx * dx + dy * dy + dz * dz;
    }

    private static void step(String s) {
        synchronized (STEPS) {
            STEPS.add("tick +" + (tick - landTick) + ": " + s);
        }
    }

    private static void expect(boolean sent, String what, boolean want) {
        step(what + (sent ? " - SENT" : " - not sent"));
        if (sent != want) {
            synchronized (BAD) {
                BAD.add(what + (sent ? " was sent" : " was not sent"));
            }
        }
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        List<Round> rounds = rounds();
        Scenario.labelServerAs(DUNGEON_LABEL);
        Scenario.run(ctx, NAME,
                (server, scenario) -> {
                    TestMap map = TestMap.on(server)
                            .platform(BX + 6, Y - 1, BZ, 12)
                            .walls(5)
                            .catchFloor(120)
                            .survival()
                            .clearInventory()
                            .give("diamond_shovel", 1)
                            .skyblockSidebar("F7")
                            .command("fill " + (BX - 4) + " " + (Y + 1) + " " + (BZ + 4) + " " + (BX + 20) + " " + (Y + 3)
                                    + " " + (BZ + 4) + " minecraft:stone");
                    for (Round r : rounds) {
                        BlockPos l = r.lantern();
                        map.command("setblock " + l.getX() + " " + l.getY() + " " + l.getZ() + " minecraft:sea_lantern");
                    }
                    map.spawn(BX + 0.5, BZ + 0.5, 0f).build();
                },
                (server, scenario) -> {
                    scenario.assertDetectorWorks();
                    ctx.waitTicks(30);
                    if (!hooked) {
                        hooked = true;
                        ClientTickEvents.START_CLIENT_TICK.register(GrimBeamsMeleeTests::onStart);
                    }
                    synchronized (STEPS) {
                        STEPS.clear();
                    }
                    synchronized (BAD) {
                        BAD.clear();
                    }
                    for (Round r : rounds) {
                        BlockPos l = r.lantern();
                        boolean there = ctx.computeOnClient(mc -> mc.level.getBlockState(l).is(Blocks.SEA_LANTERN));
                        if (!there) {
                            throw new AssertionError("the sea lantern at " + l.toShortString() + " is not on the client");
                        }
                    }
                    ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
                    ctx.waitTicks(5);
                    PacketWatch.start();
                    int expectedHits = 0;
                    for (int i = 0; i < rounds.size(); i++) {
                        Round r = rounds.get(i);
                        round = r;
                        // He arrives sneaking, as from an etherwarp; standing from an AOTV teleport (no sneak).
                        boolean sneakIn = r.kind() != Kind.AIR;
                        ctx.runOnClient(mc -> mc.options.keyShift.setDown(sneakIn));
                        ctx.waitTicks(2);
                        int before = PacketWatch.totalBreaks();
                        stage = Stage.AWAIT_LAND;
                        synchronized (STEPS) {
                            STEPS.add("round " + (i + 1) + " (" + r.what() + "): teleport onto "
                                    + String.format(Locale.ROOT, "%.1f, %d, %.1f", r.x(), feetY(r), r.z()));
                        }
                        server.command(String.format(Locale.ROOT, "execute as @p at @s run tp @s %.2f %d %.2f ~ ~",
                                r.x(), feetY(r), r.z()));
                        for (int w = 0; w < 100 && stage != Stage.DONE; w++) {
                            ctx.waitTick();
                        }
                        if (stage != Stage.DONE) {
                            throw new AssertionError("round " + (i + 1) + " never finished (stage " + stage + ")");
                        }
                        stage = Stage.IDLE;
                        ctx.waitTicks(6);
                        int sentNow = PacketWatch.totalBreaks() - before;
                        int want = r.kind() == Kind.FAR ? 0 : 1;
                        expectedHits += want;
                        synchronized (STEPS) {
                            STEPS.add("round " + (i + 1) + ": " + sentNow + " START_DESTROY_BLOCK(s) sent, " + want
                                    + " expected");
                        }
                        if (sentNow != want) {
                            synchronized (BAD) {
                                BAD.add("round " + (i + 1) + " (" + r.what() + ") sent " + sentNow + " dig(s), " + want
                                        + " expected");
                            }
                        }
                    }
                    ctx.runOnClient(mc -> mc.options.keyShift.setDown(false));
                    ctx.waitTicks(10);
                    PacketWatch.stop();
                    int standing = 0;
                    for (Round r : rounds) {
                        BlockPos l = r.lantern();
                        if (ctx.computeOnClient(mc -> mc.level.getBlockState(l).is(Blocks.SEA_LANTERN))) {
                            standing++;
                        }
                    }
                    synchronized (STEPS) {
                        for (String s : STEPS) {
                            scenario.log(s);
                        }
                    }
                    scenario.log(PacketWatch.summary());
                    scenario.log(String.format(Locale.ROOT, "RESULT %d dig(s) for %d hit(s), %d without a swing, max %.2f "
                                    + "eye to box, max look angle %.1f, %d occluded, %d of %d lanterns standing, sneak "
                                    + "waits %d, GrimAC %d line(s)", PacketWatch.totalBreaks(), expectedHits,
                            PacketWatch.breaksWithoutSwing(), PacketWatch.maxBoxReach(), PacketWatch.maxDigLookAngle(),
                            PacketWatch.occludedDigs(), standing, rounds.size(), sneakWaits, scenario.flags().size()));
                    synchronized (BAD) {
                        if (!BAD.isEmpty()) {
                            throw new AssertionError(String.join("; ", BAD));
                        }
                    }
                    if (PacketWatch.totalBreaks() != expectedHits) {
                        throw new AssertionError(PacketWatch.totalBreaks() + " dig(s) went out, " + expectedHits
                                + " expected - the hits did not go as planned");
                    }
                    if (PacketWatch.breaksWithoutSwing() > 0) {
                        throw new AssertionError(PacketWatch.breaksWithoutSwing() + " hit(s) without a swing");
                    }
                    if (PacketWatch.maxBoxReach() > 4.5 + 1e-9) {
                        throw new AssertionError(String.format(Locale.ROOT, "a hit at %.2f eye to box, past 4.5",
                                PacketWatch.maxBoxReach()));
                    }
                    if (standing != rounds.size()) {
                        throw new AssertionError((rounds.size() - standing) + " lantern(s) broke - a tap must not break one");
                    }
                });
    }

    private static boolean aimBodyAt(LocalPlayer p, BlockPos pos) {
        try {
            var m = Class.forName(UTIL).getDeclaredMethod("aimBodyAt", LocalPlayer.class, BlockPos.class,
                    Class.forName(FREECAM));
            m.setAccessible(true);
            return (Boolean) m.invoke(null, p, pos, freeCam);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("no AutoPuzzleUtil.aimBodyAt(LocalPlayer, BlockPos, FreeCam)", e);
        }
    }

    private static Object newFreeCam() {
        try {
            var c = Class.forName(FREECAM).getDeclaredConstructor(String.class);
            c.setAccessible(true);
            return c.newInstance("Beams melee test");
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
}
