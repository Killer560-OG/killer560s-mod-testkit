package dev.testkit.gametest;

import dev.testkit.harness.PacketWatch;
import dev.testkit.harness.Report;
import dev.testkit.harness.SuiteVerdict;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ac-retry-limit-1126: Auto Clear Door Only sent at the Blood door with every door of the floor shut (so no room route exists)
 * must give the trip up after 4 failed tries (it retried every tick: "failed (90 of 4)"), log "No ROOM route" once, say why once
 * and go on running (not stopped as a whole).
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimAcTripRetry1126Tests implements FabricClientGameTest {
    private static final String NAME = "1126-sim-ac-trip-retry-limit-sim";
    private static final String R_NOKEY = "1126-sim-ac-trip-retry-limit";
    private static final String AUTO = "com.killer560.hub.autoclear.AutoClearFeature";
    private static final String AUTO_CONFIG = "com.killer560.hub.autoclear.AutoClearConfig";
    private static final String SIM_DOORS = "com.killer560.hub.roomsim.SimDoors";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";

    private final List<String> all = new ArrayList<>();

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank() || Scenario.skip(NAME)) {
            return;
        }
        if (!dev.testkit.gametest.mod.Mod.isCheat()) {
            boolean ran;
            try {
                ModUnderTest.staticCall(AUTO, "start");
                ran = (Boolean) ModUnderTest.staticCall(AUTO, "isBusy");
            } catch (Throwable t) {
                ran = false;   // the class is not in a legit jar
            }
            row(R_NOKEY, !ran, "legit jar: Auto Clear does not run (" + ran + ")");
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(40);
        renderBefore = ctx.computeOnClient(mc -> mc.options.renderDistance().get());
        if (SimMapTests.copyRoomsForOthers() < 20) {
            Scenario.skipped(NAME, "needs real room captures and room database");
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);
        try {
            body(ctx);
        } catch (Throwable t) {
            all.add("scenario threw: " + t);
            t.printStackTrace(System.out);
        } finally {
            ctx.runOnClient(mc -> mc.options.renderDistance().set(renderBefore));
            ctx.runOnClient(mc -> {
                try {
                    Object c = ModUnderTest.config(AUTO_CONFIG);
                    ModUnderTest.set(c, "setEnabled", false);
                    ModUnderTest.set(c, "setDoorOnly", false);
                } catch (Throwable ignored) {
                    // cleanup never replaces a verdict
                }
            });
            SimKeyDoorTests.leave(ctx);
        }
        if (!all.isEmpty()) {
            throw new AssertionError(String.join(" | ", all));
        }
        System.out.println("[" + NAME + "] PASS - Door Only cases passed");
    }

    private int renderBefore;

    private void body(ClientGameTestContext ctx) {
        // acdoorflake-1121: the Blood door can be 160+ blocks from the first door, and the view distance here is 5 chunks, so its
        // approach was an unloaded chunk (air): "no block to stand on at the wither door" on every floor that put it that far.
        // The sibling Auto Clear sim cases raise it to 16. -PdoorPin=SEED:RD pins the floor and the distance (RD 0 leaves it: the old behavior).
        String[] pin = System.getProperty("testkit.doorPin", "").split(":");
        int rd = pin.length > 1 ? Integer.parseInt(pin[1]) : 16;
        if (rd > 0) {
            ctx.runOnClient(mc -> mc.options.renderDistance().set(rd));
        }
        long seed = pin[0].isBlank() ? System.nanoTime() : Long.parseLong(pin[0]);
        ctx.runOnClient(mc -> {
            try {
                var f = Class.forName(FLOOR_GEN).getDeclaredField("RNG");
                f.setAccessible(true);
                ((java.util.Random) f.get(null)).setSeed(seed);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
        System.out.println("[" + NAME + "] floor seed " + seed + ", render distance " + rd);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter", new Class<?>[]{String.class}, new Object[]{"gametest"}));
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> {
            Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
            ModUnderTest.staticCall(FLOOR_GEN, "generate", new Class<?>[]{Minecraft.class, floor.getClass(), int.class, int.class},
                    new Object[]{mc, floor, 3, 4});
        }));
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitFor(mc -> dev.testkit.compat.McCompat.screen(mc) == null, 1200);
        ctx.waitFor(mc -> mc.player.onGround() && !mc.level.getBlockState(mc.player.blockPosition().below()).isAir(), 2400);
        ctx.waitTicks(40);
        SimKeyDoorTests.Plan p = ctx.computeOnClient(SimKeyDoorTests::plan);
        System.out.println("[" + NAME + "] plan: " + p);
        if (p.problem != null) {
            row(R_NOKEY, false, "premise: " + p.problem);
            return;
        }
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.livemap.HeldMap", "resetSimVisits"));
        double[] at = ctx.computeOnClient(mc -> SimKeyDoorTests.standPoint(p, p.openDoor));
        SimKeyDoorTests.place(ctx, at);
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> {
            ModUnderTest.staticCall(SIM_DOORS, "makeWitherDoor", new Class<?>[]{Minecraft.class, int.class}, new Object[]{mc, p.openDoor});
            ModUnderTest.staticCall(SIM_DOORS, "makeWitherDoor", new Class<?>[]{Minecraft.class, int.class}, new Object[]{mc, p.decoyDoor});
        });
        BlockPos dBlock = ctx.computeOnClient(mc -> SimKeyDoorTests.doorBlock(p.openDoor));
        BlockPos xBlock = ctx.computeOnClient(mc -> SimKeyDoorTests.doorBlock(p.decoyDoor));
        ctx.waitFor(mc -> !mc.level.getBlockState(dBlock).isAir() && !mc.level.getBlockState(xBlock).isAir(), 400);
        ctx.waitTicks(40);
        // Back on the ground (the place helper flew him) so the trips are Auto Clear's own.
        ctx.runOnClient(mc -> {
            mc.player.getAbilities().flying = false;
            mc.player.onUpdateAbilities();
            Object cfg = ModUnderTest.config(AUTO_CONFIG);
            ModUnderTest.set(cfg, "setEnabled", true);
            ModUnderTest.set(cfg, "setDoorOnly", true);
        });

        // AOTV in slot 0 for the map's etherwarps (the trip to a later door), no key.
        AtomicReference<Boolean> aotv = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            UUID me = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(me);
                if (sp != null) {
                    sp.getInventory().setItem(0, (ItemStack) ModUnderTest.staticCall("com.killer560.hub.roomsim.SimItems", "build",
                            new Class<?>[]{String.class}, new Object[]{"ASPECT_OF_THE_VOID"}));
                }
                aotv.set(sp != null);
            });
        });
        ctx.waitFor(mc -> aotv.get() != null, 100);
        ctx.waitTicks(10);
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));

        // Every door of the floor shut (a wither door each), so no route between rooms exists, and the Blood door is the target.
        ctx.runOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            for (int idx = 0; idx < 121; idx++) {
                if ((Boolean) SimKeyDoorTests.inv(layout, "isDoor", idx)) {
                    ModUnderTest.staticCall(SIM_DOORS, "makeWitherDoor", new Class<?>[]{Minecraft.class, int.class}, new Object[]{mc, idx});
                }
            }
        });
        ctx.waitTicks(60);
        // The door D beside his room, the way every stand takes, but no weapon in the hotbar: no trip to it can start or land.
        int bloodDoor = p.openDoor;
        // ... and he stands at the decoy door, the far side of the room from D, so a trip to D is needed.
        double[] away = ctx.computeOnClient(mc -> SimKeyDoorTests.standPoint(p, p.decoyDoor));
        SimKeyDoorTests.place(ctx, away);
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> {
            mc.player.getAbilities().flying = false;
            mc.player.onUpdateAbilities();
        });
        ctx.waitTicks(20);
        // Walled in with stone (feet and head cells free): no warp has anywhere to start, so the trip to D fails every try.
        AtomicReference<Boolean> walled = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            BlockPos wallAt = mc.player.blockPosition();
            server.execute(() -> {
                var level = server.overworld();
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        for (int dy = -1; dy <= 3; dy++) {
                            boolean cell = dx == 0 && dz == 0 && (dy == 0 || dy == 1);
                            if (!cell) {
                                level.setBlock(wallAt.offset(dx, dy, dz), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
                            }
                        }
                    }
                }
                walled.set(true);
            });
        });
        ctx.waitFor(mc -> walled.get() != null, 100);
        ctx.waitTicks(20);
        AtomicReference<Boolean> emptied = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            UUID me = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(me);
                if (sp != null) {
                    for (int i = 0; i < 36; i++) {
                        sp.getInventory().setItem(i, ItemStack.EMPTY);
                    }
                }
                emptied.set(sp != null);
            });
        });
        ctx.waitFor(mc -> emptied.get() != null, 100);
        ctx.waitTicks(10);
        ctx.runOnClient(mc -> {
            Object cfg = ModUnderTest.config(AUTO_CONFIG);
            ModUnderTest.set(cfg, "setEnabled", true);
            ModUnderTest.set(cfg, "setDoorOnly", true);
            ModUnderTest.staticCall(AUTO, "forceDoorForTest", new Class<?>[]{int.class}, new Object[]{bloodDoor});
        });
        long mark = LogTap.mark();
        ctx.runOnClient(mc -> ModUnderTest.staticCall(AUTO, "start"));
        ctx.waitTicks(600);
        List<String> lines = LogTap.since(mark);
        boolean busy = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(AUTO, "isBusy"));
        java.util.regex.Pattern failed = java.util.regex.Pattern.compile("trip .* failed \\((\\d+) of (\\d+)\\)");
        int fails = 0, maxTry = 0, noRoute = 0, gaveUp = 0;
        for (String l : lines) {
            java.util.regex.Matcher m = failed.matcher(l);
            if (m.find()) {
                fails++;
                maxTry = Math.max(maxTry, Integer.parseInt(m.group(1)));
            }
            if (l.contains("No ROOM route")) {
                noRoute++;
            }
            if (l.contains("gave up on wither door")) {
                gaveUp++;
            }
        }
        int shown = 0;
        for (String l : lines) {
            if ((l.contains("[AutoClear]") || l.contains("[Path]")) && shown++ < 14) {
                System.out.println("[" + NAME + "] log: " + l);
            }
        }
        System.out.println("[" + NAME + "] far door " + bloodDoor + ": " + fails + " failed trips, highest try " + maxTry + ", "
                + noRoute + " No ROOM route, " + gaveUp + " give-up lines, running " + busy);
        row(R_NOKEY, fails >= 1 && fails <= 4 && maxTry <= 4 && noRoute <= 1 && gaveUp == 1 && busy,
                fails + " failed trips (1..4 wanted), highest try " + maxTry + " (max 4), No ROOM route " + noRoute
                        + " (max 1), give-up lines " + gaveUp + " (1 wanted), still running " + busy);
    }

    private void row(String name, boolean pass, String detail) {
        System.out.println("[" + name + "] " + (pass ? "PASS" : "FAIL") + " - " + detail);
        Report.caseStarted(name);
        Report.caseFinished(name, pass ? "PASS" : "FAIL", "n/a (singleplayer)", detail, List.of(), List.of());
        SuiteVerdict.finished(name);
        if (!pass) {
            all.add(name + ": " + detail);
        }
    }
}
