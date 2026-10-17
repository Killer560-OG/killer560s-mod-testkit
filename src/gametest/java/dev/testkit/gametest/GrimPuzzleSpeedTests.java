package dev.testkit.gametest;

import dev.testkit.harness.PacketWatch;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code 1016-grim-puzzle-speed} (mod branch puzzles-speed-1016): the two packet patterns the speed round added, against
 * a real GrimAC.
 * <ul>
 *   <li><b>run out</b> (Auto Boulder's new exit): he stands pressed against iron bars facing them, as after Secret Aura
 *   took the chest, and {@code AutoBoulder.testRunTo} runs him 40 blocks the other way with the room's own code - the
 *   body turned at the human pace under a held camera, forward and sprint held from the tick he faces within 60 degrees of
 *   the way. Played at the sim speed he plays (movement_speed 0.6, his "speed 600") and at a plain 0.1.</li>
 *   <li><b>move ahead</b> (Auto Blaze's warp while the volley flies): sneak held, a shot ({@code useItemRotated} with the
 *   bow), the NEXT tick the AOTV stand-in in hand and a warp's use with another look, then the landing (a server
 *   teleport that keeps his rotation), then the bow back in hand in the landing tick and a shot aimed 5 degrees of yaw off
 *   the target the tick after (a Terminator side-arrow shot).</li>
 * </ul>
 * Not vacuous: the run must cover 35+ blocks, sprinting on 80% of its forward ticks, and end within 1.5 of its point; every
 * use must go out on its planned tick and the item-use count must match; GrimAC must say nothing ({@link Scenario#run},
 * after its positive control).
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class GrimPuzzleSpeedTests implements FabricClientGameTest {

    private static final String NAME = "1016-grim-puzzle-speed";
    private static final String UTIL = "com.killer560.hub.autopuzzles.AutoPuzzleUtil";
    private static final String BOULDER = "com.killer560.hub.autopuzzles.AutoBoulder";
    private static final String DUNGEON_LABEL = "localhost.p3sim.net:25565";
    private static final int SURFACE_Y = 151;
    private static final int BASE_X = -760;
    private static final int BASE_Z = -420;
    private static final int AHEAD_ROUNDS = 3;

    private enum Stage { IDLE, SHOT, WARP, AWAIT_LAND, SIDE_SHOT, DONE }

    private static volatile Stage stage = Stage.IDLE;
    private static Vec3 lastPos;
    private static long tick;
    private static long markTick;
    private static boolean hooked;
    private static volatile Vec3 shotTarget;
    private static volatile Vec3 warpTarget;
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
            case SHOT -> {
                markTick = tick;
                use(mc, p, shotTarget, 0f, "the shot");
                stage = Stage.WARP;
            }
            case WARP -> {
                p.getInventory().setSelectedSlot(0); // the AOTV stand-in, and its use in the same tick
                use(mc, p, warpTarget, 0f, "the warp's use +1 tick after the shot (the volley still flying)");
                stage = Stage.AWAIT_LAND;
            }
            case AWAIT_LAND -> {
                if (!landed) {
                    return;
                }
                markTick = tick;
                try {
                    Class<?> actor = Class.forName("com.killer560.hub.util.ActionGate$Actor");
                    ModUnderTest.staticCall("com.killer560.hub.util.ActionGate", "expectSelfTeleport",
                            new Class<?>[]{actor, int.class}, new Object[]{ModUnderTest.enumValue(
                                    "com.killer560.hub.util.ActionGate$Actor", "PUZZLE_WORLD"), 6});
                } catch (ClassNotFoundException e) {
                    throw new AssertionError(e);
                }
                p.getInventory().setSelectedSlot(1); // the bow back in hand in the landing tick, sneak kept
                step("landed: bow in hand");
                stage = Stage.SIDE_SHOT;
            }
            case SIDE_SHOT -> {
                use(mc, p, shotTarget, 5f, "the side-arrow shot +1 tick after the landing");
                stage = Stage.DONE;
            }
            default -> {
            }
        }
    }

    private static void use(Minecraft mc, LocalPlayer p, Vec3 to, float yawOffset, String what) {
        float[] d = direction(p, to, 1.27);
        boolean ok = (Boolean) ModUnderTest.staticCall(UTIL, "useItemRotated", new Class<?>[]{Minecraft.class,
                LocalPlayer.class, float.class, float.class}, new Object[]{mc, p, d[0] + yawOffset, d[1]});
        step(what + (ok ? " - sent" : " - HELD"));
        if (!ok) {
            synchronized (BAD) {
                BAD.add(what + " was held back");
            }
        }
    }

    private static void step(String s) {
        synchronized (STEPS) {
            STEPS.add("tick +" + (tick - markTick) + ": " + s);
        }
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        try {
            Class.forName(BOULDER).getMethod("testRunTo", Vec3.class);
        } catch (ReflectiveOperationException e) {
            Scenario.skipped(NAME, "this jar has no AutoBoulder.testRunTo (before puzzles-speed-1016)");
            return;
        }
        int barsZ = BASE_Z + 20;
        Scenario.labelServerAs(DUNGEON_LABEL);
        Scenario.run(ctx, NAME,
                (server, scenario) -> TestMap.on(server)
                        .platform(BASE_X, SURFACE_Y - 1, BASE_Z, 24)
                        .walls(5)
                        .catchFloor(120)
                        .survival()
                        .clearInventory()
                        .give("stick", 1)
                        .give("bow", 1)
                        .skyblockSidebar("F7")
                        .command("fill " + (BASE_X - 4) + " " + SURFACE_Y + " " + barsZ + " " + (BASE_X + 4) + " "
                                + (SURFACE_Y + 2) + " " + barsZ + " minecraft:iron_bars")
                        .spawn(BASE_X + 0.5, barsZ - 0.7, 0f)
                        .build(),
                (server, scenario) -> {
                    scenario.assertDetectorWorks();
                    ctx.waitTicks(30);
                    if (!hooked) {
                        hooked = true;
                        ClientTickEvents.START_CLIENT_TICK.register(GrimPuzzleSpeedTests::onStart);
                    }
                    synchronized (STEPS) {
                        STEPS.clear();
                    }
                    synchronized (BAD) {
                        BAD.clear();
                    }
                    PacketWatch.start();
                    List<String> runs = new ArrayList<>();
                    // ---- run out, at his sim speed and at a plain one ----
                    for (double speed : new double[]{0.6, 0.1}) {
                        server.command(String.format(Locale.ROOT, "attribute @p minecraft:movement_speed base set %.2f",
                                speed));
                        // Pressed against the bars, facing them (yaw 0 faces +z), as when Secret Aura took the chest.
                        server.command(String.format(Locale.ROOT, "execute as @p at @s run tp @s %.2f %d %.2f 0 30",
                                BASE_X + 0.5, SURFACE_Y, barsZ - 0.7));
                        ctx.waitTicks(15);
                        Vec3 target = new Vec3(BASE_X + 0.5, SURFACE_Y, BASE_Z - 18.5);
                        double[] start = ctx.computeOnClient(mc -> new double[]{mc.player.getX(), mc.player.getZ()});
                        ctx.runOnClient(mc -> ModUnderTest.staticCall(BOULDER, "testRunTo", new Class<?>[]{Vec3.class},
                                new Object[]{target}));
                        int fwd = 0;
                        int sprinting = 0;
                        int firstFwd = -1;
                        double left = Double.MAX_VALUE;
                        int t = 0;
                        try {
                            for (; t < 160; t++) {
                                ctx.waitTick();
                                Object[] s = ctx.computeOnClient(mc -> new Object[]{mc.options.keyUp.isDown(),
                                        mc.player.isSprinting(), Math.hypot(mc.player.getX() - target.x,
                                        mc.player.getZ() - target.z)});
                                if ((Boolean) s[0]) {
                                    if (firstFwd < 0) {
                                        firstFwd = t;
                                    } else if (t > firstFwd + 2) {
                                        fwd++;
                                        sprinting += (Boolean) s[1] ? 1 : 0;
                                    }
                                }
                                left = (Double) s[2];
                                if (left < 1.0) {
                                    break;
                                }
                            }
                        } finally {
                            ctx.runOnClient(mc -> ModUnderTest.staticCall(BOULDER, "testRunTo",
                                    new Class<?>[]{Vec3.class}, new Object[]{null}));
                        }
                        ctx.waitTicks(25);
                        double[] end = ctx.computeOnClient(mc -> new double[]{mc.player.getX(), mc.player.getZ()});
                        double moved = Math.hypot(end[0] - start[0], end[1] - start[1]);
                        String line = String.format(Locale.ROOT, "run out at speed %.1f: forward from tick %d, %d tick(s) "
                                        + "to within %.2f of the point, moved %.1f blocks, sprinting on %d of %d forward tick(s)",
                                speed, firstFwd, t, left, moved, sprinting, fwd);
                        runs.add(line);
                        scenario.log(line);
                        if (moved < 35.0 || left > 1.5) {
                            throw new AssertionError(line + " - the run did not get there");
                        }
                        if (sprinting < fwd * 0.8) {
                            throw new AssertionError(line + " - not sprinting");
                        }
                    }
                    server.command("attribute @p minecraft:movement_speed base set 0.1");
                    // ---- move ahead: shot, the warp's use the next tick, landing, side-arrow shot ----
                    ctx.runOnClient(mc -> {
                        mc.player.getInventory().setSelectedSlot(1);
                        mc.options.keyShift.setDown(true); // a bow puzzle keeps sneak held
                    });
                    ctx.waitTicks(5);
                    int usesBefore = PacketWatch.itemUses();
                    for (int r = 0; r < AHEAD_ROUNDS; r++) {
                        int x = BASE_X - 6 + 6 * r;
                        shotTarget = new Vec3(x + 0.5, SURFACE_Y + 14, barsZ + 0.5);
                        warpTarget = new Vec3(x + 0.5, SURFACE_Y, BASE_Z - 6.5);
                        synchronized (STEPS) {
                            STEPS.add("round " + (r + 1) + ": shot, warp, landing on " + x + ", " + SURFACE_Y + ", "
                                    + (BASE_Z - 5));
                        }
                        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(1));
                        ctx.waitTick();
                        stage = Stage.SHOT;
                        for (int w = 0; w < 5 && stage != Stage.AWAIT_LAND; w++) {
                            ctx.waitTick();
                        }
                        // The landing: a server teleport that keeps his rotation, as an etherwarp does.
                        server.command(String.format(Locale.ROOT, "execute as @p at @s run tp @s %.2f %d %.2f ~ ~",
                                x + 0.5 + (r % 2 == 0 ? 0.0 : 0.01), SURFACE_Y, BASE_Z - 4.5));
                        for (int w = 0; w < 100 && stage != Stage.DONE; w++) {
                            ctx.waitTick();
                        }
                        if (stage != Stage.DONE) {
                            throw new AssertionError("move-ahead round " + (r + 1) + " never finished (stage " + stage + ")");
                        }
                        stage = Stage.IDLE;
                        ctx.waitTicks(6);
                    }
                    ctx.runOnClient(mc -> mc.options.keyShift.setDown(false));
                    ctx.waitTicks(10);
                    PacketWatch.stop();
                    synchronized (STEPS) {
                        for (String s : STEPS) {
                            scenario.log(s);
                        }
                    }
                    scenario.log(PacketWatch.summary());
                    int uses = PacketWatch.itemUses() - usesBefore;
                    scenario.log(String.format(Locale.ROOT, "RESULT %s; move ahead: %d item use(s) for %d expected; GrimAC "
                            + "%d line(s)", String.join("; ", runs), uses, 3 * AHEAD_ROUNDS, scenario.flags().size()));
                    synchronized (BAD) {
                        if (!BAD.isEmpty()) {
                            throw new AssertionError(String.join("; ", BAD));
                        }
                    }
                    if (uses < 3 * AHEAD_ROUNDS) {
                        throw new AssertionError("only " + uses + " item use(s) went out, " + 3 * AHEAD_ROUNDS + " expected");
                    }
                });
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
