package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * align12-1110: ground truth for AP3's align physics. A seeded random script of discrete keys (W/A/S/D, shift, sprint)
 * and a free yaw per tick is applied at START_CLIENT_TICK on a flat stone floor at Hypixel speed 550 (and 400, 600), and
 * the player's position, velocity, sprint, crouch and getSpeed() are written at START and END of every tick to
 * {@code build/align12-phys-<speed>.csv}. The offline replica (killer560s-mod-logs/align12-1110/env/Phys.java) is checked
 * against that file tick for tick. GrimAC must stay silent (Scenario.run asserts it): rapid yaw and key changes are what an
 * align sends.
 */
public class Align12PhysTests implements FabricClientGameTest {

    private static final int TICKS = 400;
    private static volatile boolean running;
    private static volatile int idx;
    private static int fw, st, holdLeft;
    private static boolean shift, sprintKey;
    private static float yaw;
    private static Random rnd;
    private static final List<String> ROWS = new ArrayList<>();
    private static boolean installed;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        for (int speed : new int[] {550, 400, 600}) {
            String name = "1110-align12-phys-" + speed;
            if (Scenario.skip(name)) {
                continue;
            }
            Scenario.run(ctx, name,
                    (server, scenario) -> TestMap.on(server)
                            .platform(60)
                            .walls(3)
                            .catchFloor(140)
                            .survival()
                            .speed(speed)
                            .spawn(0.5, 0.5, 0f)
                            .build(),
                    (server, scenario) -> body(ctx, scenario, speed));
        }
    }

    private static void install() {
        if (installed) {
            return;
        }
        installed = true;
        ClientTickEvents.START_CLIENT_TICK.register(mc -> {
            if (!running || mc.player == null) {
                return;
            }
            LocalPlayer p = mc.player;
            if (holdLeft <= 0) {
                fw = rnd.nextInt(3) - 1;
                st = rnd.nextInt(3) - 1;
                if (rnd.nextInt(4) == 0) {
                    fw = 1;
                }
                shift = rnd.nextInt(10) < 3;
                sprintKey = rnd.nextBoolean();
                holdLeft = 1 + rnd.nextInt(4);
            }
            holdLeft--;
            // steer back toward the middle when far out, else a random yaw (unbounded, never wrapped)
            double r = Math.hypot(p.getX(), p.getZ());
            if (r > 25) {
                float toward = (float) Math.toDegrees(Math.atan2(p.getX(), -p.getZ()));
                yaw = toward + (fw >= 0 ? 0 : 180) + (float) (rnd.nextDouble() * 40 - 20);
                fw = 1;
                st = 0;
            } else {
                yaw = yaw + (float) (rnd.nextDouble() * 360 - 180);
            }
            Options o = mc.options;
            o.keyUp.setDown(fw > 0);
            o.keyDown.setDown(fw < 0);
            o.keyLeft.setDown(st > 0);
            o.keyRight.setDown(st < 0);
            o.keyShift.setDown(shift);
            o.keySprint.setDown(sprintKey);
            p.setYRot(yaw);
            p.setYHeadRot(yaw);
            row("S", p);
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (!running || mc.player == null) {
                return;
            }
            row("E", mc.player);
            idx++;
        });
    }

    private static void row(String kind, LocalPlayer p) {
        Vec3 v = p.getDeltaMovement();
        ROWS.add(String.format(Locale.ROOT, "%s,%d,%d,%d,%b,%b,%s,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%b,%b,%b,%s", kind, idx, fw,
                st, shift, sprintKey, Float.toString(yaw), p.getX(), p.getY(), p.getZ(), v.x, v.y, v.z, p.onGround(),
                p.isSprinting(), p.isCrouching(), Float.toString(p.getSpeed())));
    }

    private static void body(ClientGameTestContext ctx, Scenario scenario, int speed) {
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> {
            install();
            rnd = new Random(1110L + speed);
            ROWS.clear();
            idx = 0;
            holdLeft = 0;
            yaw = mc.player.getYRot();
            running = true;
        });
        ctx.waitTicks(TICKS);
        List<String> rows = ctx.computeOnClient(mc -> {
            running = false;
            Options o = mc.options;
            o.keyUp.setDown(false);
            o.keyDown.setDown(false);
            o.keyLeft.setDown(false);
            o.keyRight.setDown(false);
            o.keyShift.setDown(false);
            o.keySprint.setDown(false);
            return new ArrayList<>(ROWS);
        });
        Path out = Path.of("align12-phys-" + speed + ".csv").toAbsolutePath();
        try {
            Files.write(out, rows, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AssertionError("could not write " + out + ": " + e);
        }
        long moved = rows.stream().filter(r -> r.startsWith("E")).count();
        double[] end = scenario.playerPosition();
        scenario.log(String.format(Locale.ROOT, "align12 phys: %d rows (%d ticks) -> %s; end at (%.2f, %.2f)", rows.size(), moved,
                out, end[0], end[2]));
        if (moved < TICKS / 2) {
            throw new AssertionError("only " + moved + " ticks recorded: the script did not run");
        }
    }
}
