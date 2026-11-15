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
 * Auto Clear "Door Only" (mod acdooronly-1115, cheat build). One generated sim F7; the plan of 1996-sim-key-door gives a
 * room A with one door D on the way to Blood and a decoy wither door X leading away. Both are shut wither doors and he
 * stands in A. With Door Only on:
 * <ul>
 *   <li>1115-ac-dooronly-nokey: no key held, it goes to D and waits (no click, still running, D shut) for 5 s, then a key is
 *       given and the door's click goes out within 40 ticks;</li>
 *   <li>1115-ac-dooronly-full: D opens, the decoy X is never touched, the Blood door opens, the run ends ("Done"), nothing
 *       is attacked (no entity interaction), no room was picked, it stays stopped, and a new start says the door is open.</li>
 * </ul>
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimAcDoorOnly1115Tests implements FabricClientGameTest {
    private static final String NAME = "1115-ac-dooronly-sim";
    private static final String R_NOKEY = "1115-ac-dooronly-nokey";
    private static final String R_FULL = "1115-ac-dooronly-full";
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
            Scenario.skipped(NAME, "Auto Clear is cheat build only");
            return;
        }
        ModUnderTest.require("killer560smod");
        ctx.waitTicks(40);
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

    private void body(ClientGameTestContext ctx) {
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
        double[] at = ctx.computeOnClient(mc -> SimKeyDoorTests.standPoint(p));
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

        // ---- no key: waits at D, nothing clicked, still running ----
        long mark = LogTap.mark();
        PacketWatch.start();
        ctx.runOnClient(mc -> ModUnderTest.staticCall(AUTO, "start"));
        boolean waiting = false;
        for (int t = 0; t < 900 && !waiting; t++) {
            ctx.waitTicks(1);
            for (String l : LogTap.since(mark)) {
                waiting |= l.contains("with no key - waiting");
            }
        }
        ctx.waitTicks(100);
        boolean busy = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(AUTO, "isBusy"));
        boolean dShut = locked(ctx, p.openDoor);
        int usesNoKey = PacketWatch.totalUses();
        List<String> lines = LogTap.since(mark);
        boolean sawRoom = lines.stream().anyMatch(l -> l.contains("next room") || l.contains("room cleared"));
        // Key given: the click must follow within 40 ticks.
        AtomicReference<Boolean> given = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            UUID me = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(me);
                if (sp != null) {
                    sp.getInventory().setItem(3, (ItemStack) ModUnderTest.staticCall(SIM_DOORS, "createWitherKey"));
                }
                given.set(sp != null);
            });
        });
        ctx.waitFor(mc -> given.get() != null, 100);
        long keyMark = LogTap.mark();
        int ticksToClick = -1;
        for (int t = 0; t < 120 && ticksToClick < 0; t++) {
            ctx.waitTicks(1);
            if (LogTap.since(keyMark).stream().anyMatch(l -> l.contains("clicked wither door"))) {
                ticksToClick = t + 1;
            }
        }
        row(R_NOKEY, waiting && busy && dShut && usesNoKey == 0 && !sawRoom && ticksToClick > 0 && ticksToClick <= 40,
                "waited at the door " + waiting + ", running 5 s later " + busy + ", D shut " + dShut + ", block uses without a key "
                        + usesNoKey + ", room picked " + sawRoom + ", ticks from the key to the click " + ticksToClick);

        // ---- the rest: D opens, X untouched, Blood opens, ends, stays stopped ----
        boolean done = false;
        for (int t = 0; t < 2400 && !done; t += 10) {
            ctx.waitTicks(10);
            done = !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(AUTO, "isBusy"));
        }
        PacketWatch.stop();
        int bloodDoor = ctx.computeOnClient(mc -> (Integer) SimKeyDoorTests.inv(ModUnderTest.staticCall(LAYOUT, "current"), "bloodDoor"));
        ctx.waitTicks(40);
        boolean dOpen = !locked(ctx, p.openDoor);
        boolean xShut = locked(ctx, p.decoyDoor);
        boolean bloodOpen = bloodDoor >= 0 && !locked(ctx, bloodDoor);
        int interacts = PacketWatch.entityInteracts();
        List<String> all2 = LogTap.since(mark);
        boolean doneLine = all2.stream().anyMatch(l -> l.contains("done: the Blood door is open"));
        boolean room = all2.stream().anyMatch(l -> l.contains("next room") || l.contains("room cleared"));
        ctx.waitTicks(100);
        boolean stillStopped = !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(AUTO, "isBusy"));
        ctx.runOnClient(mc -> ModUnderTest.staticCall(AUTO, "start"));
        ctx.waitTicks(20);
        boolean noRestart = !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(AUTO, "isBusy"));
        row(R_FULL, done && dOpen && xShut && bloodOpen && interacts == 0 && doneLine && !room && stillStopped && noRestart,
                "ended " + done + ", D open " + dOpen + ", decoy X still shut " + xShut + ", Blood open " + bloodOpen
                        + " (door " + bloodDoor + "), entity interactions " + interacts + ", done line " + doneLine
                        + ", room picked " + room + ", stayed stopped " + stillStopped + ", restart refused " + noRestart
                        + ", block uses " + PacketWatch.totalUses());
    }

    private static boolean locked(ClientGameTestContext ctx, int door) {
        return ctx.computeOnClient(mc -> (Boolean) SimKeyDoorTests.inv(ModUnderTest.staticCall(LAYOUT, "capture"), "isLocked", door));
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
