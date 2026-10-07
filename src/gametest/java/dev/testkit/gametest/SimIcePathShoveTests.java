package dev.testkit.gametest;

import dev.testkit.compat.McCompat;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The sim Ice Path silverfish, shoved by hand: a punch and a shortbow shot, each must move it the way he faced and
 * never hurt it.
 *
 * <p>docs/SIM.md (mod) said "Untested in game - no shortbow has been fired at a sim silverfish yet": the punch arrives
 * on {@code AttackEntityCallback} (FAIL, so no damage), the arrow is found by {@code SimIcePathPuzzle.pollForArrow}
 * and shoves along the shot's AIM yaw (a Terminator arrow carries it). Auto Ice Path's 93-solve-icepath plays the
 * room end to end but only through the auto; this drives each input itself and checks the one thing each claims:
 *
 * <ul>
 *   <li>he stands on the silverfish's cell (where Auto Ice Path shoots from), faces one of the four board axes, and
 *       either punches it with an empty hand or fires the sim Terminator straight down into it;</li>
 *   <li>the fish MOVED (horizontal displacement over 0.9 blocks) and the movement points along the facing
 *       (cosine over 0.99) - a facing straight into a wall is logged by the sim and the next axis is tried;</li>
 *   <li>its health is unchanged and it is alive, after the punch and after the arrow.</li>
 * </ul>
 *
 * <p>Fish found on the SERVER ({@code getEntitiesOfClass} around him, once the client stands on the room), so the
 * readings are the sim's own state, and the attack goes to the client's copy by the same entity id.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimIcePathShoveTests implements FabricClientGameTest {

    private static final String NAME = "94-sim-icepath-shove";
    private static final String BUILDER = "com.killer560.hub.roomsim.SimBuilder";
    private static final String SIM_ITEMS = "com.killer560.hub.roomsim.SimItems";
    private static final String TERMINATOR = "com.killer560.hub.roomsim.SimTerminator";
    private static final int TERM_SLOT = 1;
    private static final int FIST_SLOT = 2;
    private static final float[] AXES = {0f, 90f, 180f, -90f};

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(20);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (!SimPuzzleSolveTests.seedRooms(ctx)) {
            Scenario.skipped(NAME, "needs real room captures and room database");
            return;
        }
        // Nothing automatic may shove the fish: Auto Ice Path off.
        ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autopuzzles.AutoPuzzlesConfig"),
                "setAutoPuzzlesMasterEnabled", false));
        List<String> failures = new ArrayList<>();
        try {
            body(ctx, failures);
        } catch (Throwable t) {
            failures.add("scenario threw: " + t);
        } finally {
            SimPuzzleSolveTests.teardown(ctx);
        }
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " problem(s): " + String.join("; ", failures));
        }
        println("PASS");
    }

    private static void body(ClientGameTestContext ctx, List<String> failures) {
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> ModUnderTest.staticCall(BUILDER, "buildSingleRoom",
                new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, "Ice Path"})));
        ctx.waitFor(mc -> mc.level != null && mc.player != null, 2400);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitFor(mc -> McCompat.screen(mc) == null, 1200);
        ctx.waitTicks(40);

        // Terminator in slot 2, an empty slot 3 to punch with.
        AtomicReference<Boolean> given = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(uuid);
                if (sp == null) {
                    given.set(false);
                    return;
                }
                var inv = sp.getInventory();
                for (int i = 0; i < 36; i++) {
                    inv.setItem(i, ItemStack.EMPTY);
                }
                inv.setItem(TERM_SLOT, (ItemStack) ModUnderTest.staticCall(SIM_ITEMS, "build",
                        new Class<?>[]{String.class}, new Object[]{"TERMINATOR"}));
                given.set(true);
            });
        });
        ctx.waitFor(mc -> given.get() != null, 200);
        ctx.waitTicks(10);

        // The client must stand on the room before anything is judged (the ~27 s first-world chunk wait).
        int landed = -1;
        for (int t = 0; t < 1800; t++) {
            boolean ok = ctx.computeOnClient(mc -> mc.player != null && mc.player.onGround()
                    && !mc.level.getBlockState(mc.player.blockPosition().below()).isAir());
            if (ok) {
                landed = t;
                break;
            }
            ctx.waitTicks(1);
        }
        if (landed < 0) {
            failures.add("the room's chunks never reached the client in 90 s");
            return;
        }
        println(String.format("client stands on the room after %.1f s", landed / 20.0));

        double[] fish = null;
        for (int t = 0; t < 200 && fish == null; t++) {
            fish = fish(ctx);
            if (fish == null) {
                ctx.waitTicks(5);
            }
        }
        if (fish == null) {
            failures.add("no sim silverfish found on the server within 48 blocks of him - nothing to shove");
            return;
        }
        println(String.format("silverfish id %d at %.2f, %.2f, %.2f, health %.1f / %.1f", (int) fish[0], fish[1],
                fish[2], fish[3], fish[4], fish[5]));

        // ---- the punch ------------------------------------------------------------------------------------
        int punched = shoveEach(ctx, "punch", FIST_SLOT, 0f, failures);
        if (punched == 0) {
            failures.add("no punch moved the silverfish on any of the four axes");
        }
        // ---- the shortbow: straight down from its cell, as Auto Ice Path shoots ---------------------------
        int shot = shoveEach(ctx, "shortbow", TERM_SLOT, 90f, failures);
        if (shot == 0) {
            failures.add("no shortbow shot moved the silverfish on any of the four axes");
        }
        println(punched + " punch shove(s), " + shot + " shortbow shove(s) moved it the way he faced");
    }

    /**
     * Tries each board axis in turn until one input moves the fish (a facing into a wall moves nothing, and the sim
     * logs that). Returns how many inputs moved it the right way (0 or 1); every wrong or harmful one is a failure.
     */
    private static int shoveEach(ClientGameTestContext ctx, String what, int slot, float pitch, List<String> failures) {
        for (float yaw : AXES) {
            double[] f0 = fish(ctx);
            if (f0 == null) {
                failures.add(what + ": the silverfish is gone before the " + yaw + " try");
                return 0;
            }
            // Onto its cell, facing the axis.
            ctx.runOnClient(mc -> {
                var server = mc.getSingleplayerServer();
                var uuid = mc.player.getUUID();
                server.execute(() -> {
                    var sp = server.getPlayerList().getPlayer(uuid);
                    if (sp != null) {
                        sp.teleportTo(f0[1], f0[2], f0[3]);
                    }
                });
            });
            ctx.waitTicks(15);
            ctx.runOnClient(mc -> {
                mc.player.getInventory().setSelectedSlot(slot);
                mc.player.setYRot(yaw);
                mc.player.setXRot(pitch);
            });
            ctx.waitTicks(10);   // the slot and the rotation reach the server; the Terminator's shot cooldown passes
            double[] f1 = fish(ctx);
            if (f1 == null) {
                failures.add(what + ": the silverfish vanished while he was placed on it");
                return 0;
            }
            int fired0 = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(TERMINATOR, "arrowsFired"));
            long mark = LogTap.mark();
            String done = ctx.computeOnClient(mc -> {
                if (slot == FIST_SLOT) {
                    var e = mc.level.getEntity((int) f1[0]);
                    if (e == null) {
                        return "the client has no entity " + (int) f1[0];
                    }
                    mc.gameMode.attack(mc.player, e);
                } else {
                    mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                }
                return null;
            });
            if (done != null) {
                failures.add(what + " at yaw " + yaw + ": " + done);
                return 0;
            }
            ctx.waitTicks(60);   // a slide is 0.45 blocks a tick; the board is 17 cells
            double[] f2 = fish(ctx);
            int fired1 = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(TERMINATOR, "arrowsFired"));
            String simLine = null;
            for (String l : LogTap.since(mark)) {
                if (l.contains("Sim ice path:")) {
                    simLine = l.substring(l.indexOf("Sim ice path:"));
                }
            }
            String fishAfter = f2 == null ? "GONE" : String.format("%.2f, %.2f, %.2f, health %.1f", f2[1], f2[2],
                    f2[3], f2[4]);
            println(String.format("%s yaw %.0f pitch %.0f from %.2f, %.2f, %.2f (health %.1f) -> %s; arrows fired %d;"
                    + " sim: %s", what, yaw, pitch, f1[1], f1[2], f1[3], f1[4], fishAfter, fired1 - fired0, simLine));
            if (slot == TERM_SLOT && fired1 == fired0) {
                failures.add(what + " at yaw " + yaw + ": the Terminator fired no arrow");
                return 0;
            }
            if (f2 == null) {
                // Out of the exit gap: complete, and the sim discards it. Only right if it slid the way he faced.
                boolean done2 = ctx.computeOnClient(mc -> Boolean.TRUE.equals(ModUnderTest.staticCall(
                        "com.killer560.hub.roomsim.puzzles.SimIcePathPuzzle", "isComplete")));
                if (!done2) {
                    failures.add(what + " at yaw " + yaw + ": the silverfish disappeared and the puzzle is not complete");
                }
                return done2 ? 1 : 0;
            }
            if (f2[4] < f1[4] || f2[4] < f2[5] || f2[6] < 0.5) {
                failures.add(String.format("%s at yaw %.0f HURT the silverfish: health %.1f -> %.1f (max %.1f), alive %s",
                        what, yaw, f1[4], f2[4], f2[5], f2[6] > 0.5));
            }
            double dx = f2[1] - f1[1];
            double dz = f2[3] - f1[3];
            double moved = Math.hypot(dx, dz);
            if (moved < 0.9) {
                if (simLine == null) {
                    failures.add(what + " at yaw " + yaw + ": the sim logged nothing - the input never reached it");
                    return 0;
                }
                continue;   // into a wall: the next axis
            }
            double rad = Math.toRadians(yaw);
            double cos = (dx * -Math.sin(rad) + dz * Math.cos(rad)) / moved;
            if (cos < 0.99) {
                failures.add(String.format("%s at yaw %.0f moved it %.2f blocks along (%.2f, %.2f) - not the way he faced"
                        + " (cosine %.2f)", what, yaw, moved, dx / moved, dz / moved, cos));
                return 0;
            }
            println(String.format("%s: moved %.2f blocks the way he faced (cosine %.3f), health unchanged", what,
                    moved, cos));
            return 1;
        }
        return 0;
    }

    /**
     * The sim silverfish nearest him, read on the server: {id, x, y, z, health, maxHealth, alive}; null if none.
     * Server execute + wait, never a blocking join (CLAUDE.md, the runOnClient deadlock).
     */
    private static double[] fish(ClientGameTestContext ctx) {
        AtomicReference<double[]> out = new AtomicReference<>();
        AtomicReference<Boolean> done = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                try {
                    var sp = server.getPlayerList().getPlayer(uuid);
                    if (sp == null) {
                        return;
                    }
                    var box = sp.getBoundingBox().inflate(48, 16, 48);
                    double best = Double.MAX_VALUE;
                    for (var s : server.overworld().getEntitiesOfClass(
                            net.minecraft.world.entity.monster.Silverfish.class, box, e -> !e.isRemoved())) {
                        double d = s.distanceToSqr(sp);
                        if (d < best) {
                            best = d;
                            out.set(new double[]{s.getId(), s.getX(), s.getY(), s.getZ(), s.getHealth(),
                                    s.getMaxHealth(), s.isAlive() ? 1 : 0});
                        }
                    }
                } finally {
                    done.set(true);
                }
            });
        });
        ctx.waitFor(mc -> done.get() != null, 100);
        return out.get();
    }

    private static void println(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}
