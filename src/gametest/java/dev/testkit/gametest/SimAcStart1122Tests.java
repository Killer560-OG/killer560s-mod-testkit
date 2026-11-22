package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.harness.Report;
import dev.testkit.harness.SuiteVerdict;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * acdooronly-stall-1122: Dungeons Autopilot with Blood Rush "Door" (Auto Clear Door Only) switched on in the green room of
 * a seeded sim F7, started at GO from the Entrance spawn. On origin b43ced79f it logged "only a wither door (N) stands
 * before Blood - going to it" and then did nothing for 10 s. Cheat jar only.
 * <ul>
 *   <li>1122-sim-ac-dooronly-start: the decision line comes within 100 ticks of GO, a trip toward the door (an etherwarp
 *       trip or a walk) starts within 60 ticks of it, and he gets within 6 blocks of the door within 400 ticks of GO.</li>
 * </ul>
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimAcStart1122Tests implements FabricClientGameTest {

    private static final String CASE = "1122-sim-ac-dooronly-start";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String SIM_RUN = "com.killer560.hub.roomsim.SimRun";
    private static final String SIM_ITEMS = "com.killer560.hub.roomsim.SimItems";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String BUILDER = "com.killer560.hub.roomsim.SimBuilder";
    private static final String PILOT = "com.killer560.hub.autosecret.DungeonsAutopilot";
    private static final String AS_CONFIG = "com.killer560.hub.autosecret.AutoSecretConfig";
    private static final String AUTO_CLEAR = "com.killer560.hub.autoclear.AutoClearFeature";
    private static final String AUTO_CLEAR_CONFIG = "com.killer560.hub.autoclear.AutoClearConfig";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String AS = "com.killer560.hub.autosecret.AutoSecretFeature";
    private static final int HOLD = 20 * 120;
    /** The seed of acgreenstart-1122's floor, where origin stalled (origin-sim2.log, door 67). */
    private static final long SEED = 1122_2026_0950L;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip(CASE)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        if (!dev.testkit.gametest.mod.Mod.isCheat()) {
            Scenario.skipped(CASE, "legit jar: no Dungeons Autopilot");
            return;
        }
        LogTap.install();
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (SimMapTests.copyRoomsForOthers() < 20) {
            Scenario.skipped(CASE, "needs real room captures and room database");
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);
        ctx.runOnClient(mc -> {
            ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autopuzzles.AutoPuzzlesConfig"),
                    "setAutoPuzzlesMasterEnabled", false);
            Object cfg = ModUnderTest.config(AUTO_CLEAR_CONFIG);
            ModUnderTest.set(cfg, "setEnabled", true);
            ModUnderTest.set(cfg, "setHyperionHops", true);
            Object w = ModUnderTest.enumValue(AUTO_CLEAR_CONFIG + "$Weapon", "HYPERION");
            ModUnderTest.call(cfg, "setWeapon", new Class<?>[]{w.getClass()}, new Object[]{w});
            Object as = ModUnderTest.config(AS_CONFIG);
            Object door = ModUnderTest.enumValue(AS_CONFIG + "$BloodRush", "DOOR");
            ModUnderTest.call(as, "setBloodRush", new Class<?>[]{door.getClass()}, new Object[]{door});
            ModUnderTest.set(as, "setDisableAfterBloodRush", true);
        });
        List<String> f = new ArrayList<>();
        String detail = "";
        try {
            detail = body(ctx, f);
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            f.add("threw " + t);
        } finally {
            ctx.runOnClient(mc -> {
                ModUnderTest.staticCall(PILOT, "setOn", new Class<?>[]{boolean.class}, new Object[]{false});
                ModUnderTest.staticCall(AUTO_CLEAR, "cancel");
                if ((Boolean) ModUnderTest.staticCall(AS, "isRunning")) {
                    ModUnderTest.staticCall(AS, "stop", new Class<?>[]{String.class, boolean.class},
                            new Object[]{"test over", true});
                }
                Object as = ModUnderTest.config(AS_CONFIG);
                Object none = ModUnderTest.enumValue(AS_CONFIG + "$BloodRush", "NONE");
                ModUnderTest.call(as, "setBloodRush", new Class<?>[]{none.getClass()}, new Object[]{none});
                ModUnderTest.set(as, "setDisableAfterBloodRush", false);
                ModUnderTest.set(ModUnderTest.config(AUTO_CLEAR_CONFIG), "setEnabled", false);
            });
            leave(ctx);
        }
        boolean pass = f.isEmpty();
        String text = pass ? detail : String.join("; ", f) + " | " + detail;
        System.out.println("[" + CASE + "] " + (pass ? "PASS" : "FAIL") + " - " + text);
        Report.caseStarted(CASE);
        Report.caseFinished(CASE, pass ? "PASS" : "FAIL", "n/a (singleplayer)", text, List.of(), List.of());
        SuiteVerdict.finished(CASE);
        if (!pass) {
            throw new AssertionError(CASE + ": " + text);
        }
    }

    private static String body(ClientGameTestContext ctx, List<String> f) {
        BlockPos entrance = stage(ctx, f);
        if (entrance == null) {
            return "";
        }
        ctx.runOnClient(mc -> ModUnderTest.staticCall(PILOT, "setOn", new Class<?>[]{boolean.class}, new Object[]{true}));
        ctx.waitTicks(20);
        long mark = LogTap.mark();
        ctx.runOnClient(mc -> setTicksLeft(1));
        int toGo = 0;
        while (toGo < 40 && !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SIM_RUN, "isRunning"))) {
            ctx.waitTicks(1);
            toGo++;
        }
        int decision = -1;
        int trip = -1;
        int reached = -1;
        int door = -1;
        double closest = Double.MAX_VALUE;
        String lastAction = "";
        for (int t = 1; t <= 400 && reached < 0; t++) {
            ctx.waitTicks(1);
            List<String> lines = LogTap.since(mark);
            if (decision < 0 && lines.stream().anyMatch(l -> l.contains("[AutoClear] only a wither door ("))) {
                decision = t;
            }
            if (decision >= 0 && trip < 0 && lines.stream().anyMatch(l -> l.contains("[AutoClear] trip to wither door")
                    || l.contains("[AutoClear] move to the wither door") && l.contains("- walking"))) {
                trip = t;
            }
            String action = ctx.computeOnClient(mc -> String.valueOf(ModUnderTest.staticCall(AUTO_CLEAR, "hudAction")));
            if (!action.equals(lastAction)) {
                System.out.println("[" + CASE + "] tick " + t + " action: " + action);
                lastAction = action;
            }
            if (decision >= 0) {
                door = ctx.computeOnClient(mc -> (Integer) dev.testkit.gametest.mod.Mod.field("autoclear.AutoClearFeature", "doorTarget"));
                if (door >= 0) {
                    final int d = door;
                    double dist = ctx.computeOnClient(mc -> {
                        BlockPos b = (BlockPos) ModUnderTest.staticCall(LAYOUT, "doorBlock", new Class<?>[]{int.class}, new Object[]{d});
                        return Math.sqrt(mc.player.distanceToSqr(Vec3.atCenterOf(b)));
                    });
                    closest = Math.min(closest, dist);
                    if (dist <= 6.0) {
                        reached = t;
                    }
                }
            }
        }
        String detail = String.format(Locale.US, "GO after %d ticks; decision at tick %d (door %d), trip at %d, within 6 of the"
                + " door at %d (closest %.1f); last action '%s'", toGo, decision, door, trip, reached, closest, lastAction);
        f.add(decision > 0 && decision <= 100 ? null : "no Door Only decision within 100 ticks of GO");
        f.add(trip > 0 && trip - decision <= 60 ? null : "no trip toward the door within 60 ticks of the decision (tick " + trip + ")");
        f.add(reached > 0 ? null : "never within 6 blocks of the door in 400 ticks");
        f.removeIf(java.util.Objects::isNull);
        return detail;
    }

    /** The floor built, its countdown held; the entrance door; null (with a failure) when it could not be set up. */
    private static BlockPos stage(ClientGameTestContext ctx, List<String> f) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter", new Class<?>[]{String.class},
                    new Object[]{"gametest"}));
            long seed = SEED + attempt;
            for (String cls : new String[]{"roomsim.SimFloorGen", "roomsim.SimGenerator", "roomsim.SimMimic"}) {
                try {
                    ((java.util.Random) dev.testkit.gametest.mod.Mod.field(cls, "RNG")).setSeed(seed);
                } catch (Throwable ignored) {
                    // a class without an RNG leaves that part unseeded
                }
            }
            ctx.runOnClient(mc -> mc.execute(() -> {
                Object fl = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
                ModUnderTest.staticCall(FLOOR_GEN, "generate",
                        new Class<?>[]{Minecraft.class, fl.getClass(), int.class, int.class},
                        new Object[]{mc, fl, 3, 3});
            }));
            ctx.waitFor(mc -> mc.level != null, 2400);
            boolean held = false;
            for (int t = 0; t < 2400 && !held; t++) {
                ctx.waitTicks(1);
                held = ctx.computeOnClient(mc -> {
                    if ((Boolean) ModUnderTest.staticCall(SIM_RUN, "isArmed")
                            && !(Boolean) ModUnderTest.staticCall(SIM_RUN, "isRunning")
                            && (Integer) ModUnderTest.staticCall(SIM_RUN, "countdownTicks") > 0) {
                        setTicksLeft(HOLD);
                        return true;
                    }
                    return false;
                });
            }
            BlockPos door = ctx.computeOnClient(mc -> (BlockPos) ModUnderTest.staticCall(BUILDER, "entranceDoor"));
            if (held && door != null) {
                SimAutopilotTests.awaitFloorChunks(ctx);
                loadout(ctx);
                SimAutopilotTests.ground(ctx);
                ctx.waitTicks(40);   // the world scan reads the Entrance and its gate
                return door;
            }
            System.out.println("[" + CASE + "] floor " + attempt + ": countdown not caught (held " + held + ", door "
                    + door + ") - again");
            leave(ctx);
        }
        f.add("could not hold a sim countdown on three floors");
        return null;
    }

    private static void setTicksLeft(int ticks) {
        try {
            java.lang.reflect.Field fl = Class.forName(SIM_RUN).getDeclaredField("ticksLeft");
            fl.setAccessible(true);
            fl.setInt(null, ticks);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("SimRun.ticksLeft: " + e, e);
        }
    }

    private static void loadout(ClientGameTestContext ctx) {
        AtomicReference<Boolean> given = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            UUID uuid = mc.player.getUUID();
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
                String[] ids = {"ASPECT_OF_THE_VOID", "HYPERION"};
                for (int i = 0; i < ids.length; i++) {
                    inv.setItem(i, (ItemStack) ModUnderTest.staticCall(SIM_ITEMS, "build", new Class<?>[]{String.class},
                            new Object[]{ids[i]}));
                }
                given.set(true);
            });
        });
        ctx.waitFor(mc -> given.get() != null, 200);
        ctx.waitTicks(10);
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
    }

    private static void leave(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            try {
                ModUnderTest.staticCall(SIM_RUN, "reset");
                ModUnderTest.staticCall(SIM_STATE, "leave", new Class<?>[]{}, new Object[]{});
            } catch (Throwable ignored) {
                // cleanup never replaces a verdict
            }
        });
        ctx.runOnClient(mc -> mc.execute(() -> {
            if (mc.level != null) {
                mc.level.disconnect(net.minecraft.network.chat.Component.literal("scenario over"));
                mc.disconnectWithSavingScreen();
            }
        }));
        ctx.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null, 1200);
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> mc.execute(() ->
                McCompat.setScreen(mc, new net.minecraft.client.gui.screens.TitleScreen())));
        ctx.waitFor(mc -> McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.TitleScreen, 1200);
    }
}
