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
 * acgreenstart-1122 (killer560 2026-11-22: "if I activated in Green room have it teleport is close to the door as possible
 * so that way it doesn't waste an extra two ether warps just getting to a Start spot"). Dungeons Autopilot switched on in
 * the green room during the countdown (held long by the case) walks to the Entrance door's front; the countdown is then let
 * run out. Cheat jar only.
 * <ul>
 *   <li>1122-sim-ac-greenstart: before GO he has walked (no per-tick jump over 1.2 blocks) at least 6 blocks from the spawn
 *       to within 5.5 blocks of the entrance door; after GO the first trip is out through the door with at most 1 warp
 *       inside 15 ticks, and the autopilot is running. Origin stands at the spawn (fails).</li>
 *   <li>1122-sim-ac-greenstart-keys: his W pressed mid-walk takes over within 3 ticks (walk ended, body back on his view),
 *       W moves him along his own facing, and with W released nothing walks him again before GO; the autopilot still
 *       starts at GO.</li>
 * </ul>
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimGreenStart1122Tests implements FabricClientGameTest {

    private static final String WALK_CASE = "1122-sim-ac-greenstart";
    private static final String KEYS_CASE = "1122-sim-ac-greenstart-keys";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String SIM_RUN = "com.killer560.hub.roomsim.SimRun";
    private static final String SIM_ITEMS = "com.killer560.hub.roomsim.SimItems";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String BUILDER = "com.killer560.hub.roomsim.SimBuilder";
    private static final String PILOT = "com.killer560.hub.autosecret.DungeonsAutopilot";
    private static final String GRS = "com.killer560.hub.autosecret.GreenRoomStart";
    private static final String AS_CONFIG = "com.killer560.hub.autosecret.AutoSecretConfig";
    private static final String AUTO_CLEAR = "com.killer560.hub.autoclear.AutoClearFeature";
    private static final String AUTO_CLEAR_CONFIG = "com.killer560.hub.autoclear.AutoClearConfig";
    private static final String AS = "com.killer560.hub.autosecret.AutoSecretFeature";
    private static final int HOLD = 20 * 120;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        boolean walk = !Scenario.skip(WALK_CASE);
        boolean keys = !Scenario.skip(KEYS_CASE);
        if (!walk && !keys) {
            return;
        }
        ModUnderTest.require("killer560smod");
        if (!dev.testkit.gametest.mod.Mod.isCheat()) {
            if (walk) {
                Scenario.skipped(WALK_CASE, "legit jar: no Dungeons Autopilot");
            }
            if (keys) {
                Scenario.skipped(KEYS_CASE, "legit jar: no Dungeons Autopilot");
            }
            return;
        }
        LogTap.install();
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (SimMapTests.copyRoomsForOthers() < 20) {
            if (walk) {
                Scenario.skipped(WALK_CASE, "needs real room captures and room database");
            }
            if (keys) {
                Scenario.skipped(KEYS_CASE, "needs real room captures and room database");
            }
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
        List<String> failed = new ArrayList<>();
        if (walk) {
            run(ctx, WALK_CASE, false, failed);
        }
        if (keys) {
            run(ctx, KEYS_CASE, true, failed);
        }
        ctx.runOnClient(mc -> {
            Object as = ModUnderTest.config(AS_CONFIG);
            Object none = ModUnderTest.enumValue(AS_CONFIG + "$BloodRush", "NONE");
            ModUnderTest.call(as, "setBloodRush", new Class<?>[]{none.getClass()}, new Object[]{none});
            ModUnderTest.set(as, "setDisableAfterBloodRush", false);
            ModUnderTest.set(ModUnderTest.config(AUTO_CLEAR_CONFIG), "setEnabled", false);
        });
        if (!failed.isEmpty()) {
            throw new AssertionError(String.join("; ", failed));
        }
    }

    private static void run(ClientGameTestContext ctx, String name, boolean keys, List<String> failed) {
        List<String> f = new ArrayList<>();
        String detail;
        try {
            detail = keys ? keysCase(ctx, f) : walkCase(ctx, f);
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            f.add("threw " + t);
            detail = "";
        } finally {
            ctx.runOnClient(mc -> {
                mc.options.keyUp.setDown(false);
                ModUnderTest.staticCall(PILOT, "setOn", new Class<?>[]{boolean.class}, new Object[]{false});
                ModUnderTest.staticCall(AUTO_CLEAR, "cancel");
                if ((Boolean) ModUnderTest.staticCall(AS, "isRunning")) {
                    ModUnderTest.staticCall(AS, "stop", new Class<?>[]{String.class, boolean.class},
                            new Object[]{"test over", true});
                }
            });
            leave(ctx);
        }
        boolean pass = f.isEmpty();
        String text = pass ? detail : String.join("; ", f) + " | " + detail;
        System.out.println("[" + name + "] " + (pass ? "PASS" : "FAIL") + " - " + text);
        Report.caseStarted(name);
        Report.caseFinished(name, pass ? "PASS" : "FAIL", "n/a (singleplayer)", text, List.of(), List.of());
        SuiteVerdict.finished(name);
        if (!pass) {
            failed.add(name + ": " + text);
        }
    }

    /** The floor built, its countdown held; the entrance door; null (with a failure) when it could not be set up. */
    private static BlockPos stage(ClientGameTestContext ctx, List<String> f) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter", new Class<?>[]{String.class},
                    new Object[]{"gametest"}));
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
            System.out.println("[" + WALK_CASE + "] floor " + attempt + ": countdown not caught (held " + held + ", door "
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

    /** +1/-1 along the door's axis toward the inside of the Entrance, as {x, z}. */
    private static int[] inward(Vec3 spawn, BlockPos door) {
        double dx = spawn.x - (door.getX() + 0.5);
        double dz = spawn.z - (door.getZ() + 0.5);
        return Math.abs(dx) >= Math.abs(dz) ? new int[]{(int) Math.signum(dx), 0} : new int[]{0, (int) Math.signum(dz)};
    }

    private static double flat(Vec3 a, BlockPos b) {
        return Math.hypot(a.x - (b.getX() + 0.5), a.z - (b.getZ() + 0.5));
    }

    private static String walkCase(ClientGameTestContext ctx, List<String> f) {
        BlockPos door = stage(ctx, f);
        if (door == null) {
            return "";
        }
        Vec3 spawn = ctx.computeOnClient(mc -> mc.player.position());
        int[] in = inward(spawn, door);
        long mark = LogTap.mark();
        ctx.runOnClient(mc -> ModUnderTest.staticCall(PILOT, "setOn", new Class<?>[]{boolean.class}, new Object[]{true}));
        Vec3 last = spawn;
        double maxStep = 0;
        int still = 0;
        int t = 0;
        String result = "";
        for (; t < 400 && still < 20; t++) {
            ctx.waitTicks(1);
            Vec3 now = ctx.computeOnClient(mc -> mc.player.position());
            double step = Math.hypot(now.x - last.x, now.z - last.z);
            maxStep = Math.max(maxStep, step);
            result = ctx.computeOnClient(SimGreenStart1122Tests::result);
            // Settled: its walk ended (or origin, with no such walk, has stood still for 10 s).
            boolean ended = !"armed".equals(result) && !"walking".equals(result) && !"missing".equals(result);
            still = step < 0.01 && (ended || t > 200) ? still + 1 : 0;
            last = now;
        }
        Vec3 before = last;
        double moved = Math.hypot(before.x - spawn.x, before.z - spawn.z);
        double atDoor = flat(before, door);
        boolean shut = ctx.computeOnClient(mc -> !(Boolean) ModUnderTest.staticCall(SIM_RUN, "isRunning"));
        String pre = String.format(Locale.US, "spawn %.1f from the door, before GO walked %.1f (largest tick step %.2f) to %.1f"
                + " from it in %d ticks, result %s", flat(spawn, door), moved, maxStep, atDoor, t, result);
        // ---- GO
        ctx.runOnClient(mc -> setTicksLeft(1));
        int toGo = 0;
        while (toGo < 40 && !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SIM_RUN, "isRunning"))) {
            ctx.waitTicks(1);
            toGo++;
        }
        int warps = 0;
        int out = -1;
        last = ctx.computeOnClient(mc -> mc.player.position());
        for (int i = 1; i <= 200 && out < 0; i++) {
            ctx.waitTicks(1);
            Vec3 now = ctx.computeOnClient(mc -> mc.player.position());
            if (now.distanceTo(last) > 2.5) {
                warps++;
            }
            last = now;
            double past = -((now.x - (door.getX() + 0.5)) * in[0] + (now.z - (door.getZ() + 0.5)) * in[1]);
            if (past > 1.0) {
                out = i;
            }
        }
        boolean on = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(PILOT, "isOn"));
        boolean logged = LogTap.since(mark).stream().anyMatch(l -> l.contains("[GreenRoomStart] walking"));
        String post = String.format(Locale.US, "after GO: out through the door at tick %d with %d warp(s); autopilot on %s;"
                + " walk logged %s", out, warps, on, logged);
        f.add(shut ? null : "the run started before the case let it");
        f.add(moved >= 6.0 ? null : String.format(Locale.US, "walked only %.1f blocks before GO (want >= 6)", moved));
        f.add(maxStep <= 1.2 ? null : String.format(Locale.US, "a %.2f-block step before GO: it must walk, not warp", maxStep));
        f.add(atDoor <= 5.5 ? null : String.format(Locale.US, "%.1f blocks from the door at GO (want <= 5.5)", atDoor));
        f.add(out > 0 && out <= 15 ? null : "not out through the door within 15 ticks of GO (tick " + out + ")");
        f.add(warps <= 1 ? null : warps + " warps before leaving the Entrance (want <= 1)");
        f.add(logged ? null : "no \"[GreenRoomStart] walking\" log line");
        f.removeIf(java.util.Objects::isNull);
        return pre + "; " + post;
    }

    private static String keysCase(ClientGameTestContext ctx, List<String> f) {
        BlockPos door = stage(ctx, f);
        if (door == null) {
            return "";
        }
        Vec3 spawn = ctx.computeOnClient(mc -> mc.player.position());
        int[] in = inward(spawn, door);
        // Facing across the door's axis: his W walks somewhere the walk to the door never would.
        float yaw0 = in[0] != 0 ? 0f : 90f;
        ctx.runOnClient(mc -> {
            mc.player.setYRot(yaw0);
            mc.player.setYHeadRot(yaw0);
            mc.player.setXRot(0f);
        });
        ctx.waitTicks(2);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(PILOT, "setOn", new Class<?>[]{boolean.class}, new Object[]{true}));
        int walking = 0;
        for (int t = 0; t < 200 && walking < 6; t++) {
            ctx.waitTicks(1);
            walking = "walking".equals(ctx.computeOnClient(SimGreenStart1122Tests::result)) ? walking + 1 : 0;
        }
        if (walking < 6) {
            f.add("the walk to the door never started");
            return "";
        }
        Vec3 atPress = ctx.computeOnClient(mc -> mc.player.position());
        ctx.runOnClient(mc -> mc.options.keyUp.setDown(true));
        ctx.waitTicks(3);
        String result = ctx.computeOnClient(SimGreenStart1122Tests::result);
        boolean walkOn = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(
                "com.killer560.hub.pathfinding.ShortWalk", "isActive"));
        float yawNow = ctx.computeOnClient(mc -> mc.player.getYRot());
        ctx.waitTicks(10);
        Vec3 afterW = ctx.computeOnClient(mc -> mc.player.position());
        ctx.runOnClient(mc -> mc.options.keyUp.setDown(false));
        double fx = -Math.sin(Math.toRadians(yaw0));
        double fz = Math.cos(Math.toRadians(yaw0));
        double along = (afterW.x - atPress.x) * fx + (afterW.z - atPress.z) * fz;
        ctx.waitTicks(15);
        Vec3 rest = ctx.computeOnClient(mc -> mc.player.position());
        ctx.waitTicks(40);
        Vec3 later = ctx.computeOnClient(mc -> mc.player.position());
        double drift = Math.hypot(later.x - rest.x, later.z - rest.z);
        boolean armedOn = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(PILOT, "isOn"));
        ctx.runOnClient(mc -> setTicksLeft(1));
        boolean started = false;
        for (int t = 0; t < 100 && !started; t++) {
            ctx.waitTicks(1);
            started = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(AUTO_CLEAR, "isBusy")
                    || (Boolean) ModUnderTest.staticCall(AS, "isRunning"));
        }
        String d = String.format(Locale.US, "W pressed mid-walk: result \"%s\", walk active %s, body yaw %.1f (his view %.1f);"
                        + " W moved him %.2f along his facing; drift with W up %.2f; autopilot on %s, started at GO %s",
                result, walkOn, yawNow, yaw0, along, drift, armedOn, started);
        f.add("you moved".equals(result) ? null : "his W did not take over (result " + result + ")");
        f.add(!walkOn ? null : "the walk still drives the keys after his W");
        f.add(Math.abs(yawNow - yaw0) < 1.0 ? null : "the body was not given back to his view");
        f.add(along > 0.5 ? null : String.format(Locale.US, "his W moved him only %.2f along his facing", along));
        f.add(drift < 0.3 ? null : String.format(Locale.US, "walked again (%.2f) after he took over", drift));
        f.add(armedOn ? null : "the autopilot switched off");
        f.add(started ? null : "the autopilot did not start at GO");
        f.removeIf(java.util.Objects::isNull);
        return d;
    }

    /** GreenRoomStart.result(), or "missing" on a jar without it (origin). */
    private static String result(Minecraft mc) {
        try {
            return String.valueOf(Class.forName(GRS).getMethod("result").invoke(null));
        } catch (ReflectiveOperationException e) {
            return "missing";
        }
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
