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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * entrance-noroute-1126: Dungeons Autopilot (Blood Rush Door, so Auto Clear's Door Only) armed in the green room on four sim
 * floors, the first two built from one seed as 1122-sim-ac-greenstart and the green room warp case do (the second floor
 * of that pair is the one where the first trip stood "-57,253,-128" across Blood's door, 41, from its approach at
 * -57,253,-139: "No ROOM route from room 16 to room 17" ~90 times and he never left the Entrance).
 * <ul>
 *   <li>1126-sim-ac-entrance-exit: on every floor he is out through the Entrance door within 40 ticks of GO, "No ROOM
 *       route" is logged at most once after GO, and every "trip to wither door" stands on the approach's side of that
 *       door (a stand across a shut door is no trip at all). Legit jar: Dungeons Autopilot is absent (its own PASS row).</li>
 * </ul>
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimEntranceExit1126Tests implements FabricClientGameTest {

    private static final String CASE = "1126-sim-ac-entrance-exit";
    private static final String COLD = "1126-sim-ac-coldtrip";
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
    private static final String AS = "com.killer560.hub.autosecret.AutoSecretFeature";
    private static final int HOLD = 20 * 120;
    /** SimGreenStart1122Tests' seed: its first attempt (+1) twice, then two more. */
    static final long SEED = 1122_2026_0950L;
    private static final long[] SEEDS = {SEED + 1, SEED + 1, SEED + 2, SEED + 3};
    private static final Pattern TRIP = Pattern.compile("trip to wither door (\\d+) \\(approach (-?\\d+), -?\\d+, (-?\\d+),"
            + " standing on (-?\\d+), -?\\d+, (-?\\d+)\\)");

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip(CASE)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        if (!dev.testkit.gametest.mod.Mod.isCheat()) {
            // Legit jar: Dungeons Autopilot refuses to switch on (or is not there at all).
            boolean on = ctx.computeOnClient(mc -> {
                try {
                    ModUnderTest.staticCall(PILOT, "setOn", new Class<?>[]{boolean.class}, new Object[]{true});
                    boolean now = (Boolean) ModUnderTest.staticCall(PILOT, "isOn");
                    ModUnderTest.staticCall(PILOT, "setOn", new Class<?>[]{boolean.class}, new Object[]{false});
                    return now;
                } catch (Throwable t) {
                    return false;
                }
            });
            finish(CASE, on ? "Dungeons Autopilot switched on in a legit jar" : null, "legit jar: Dungeons Autopilot stays off");
            finish(COLD, on ? "Dungeons Autopilot switched on in a legit jar" : null, "legit jar: no Auto Clear trips");
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
        coldTrip(ctx);
        List<String> f = new ArrayList<>();
        List<String> floors = new ArrayList<>();
        int trips = 0;
        // acflakes-1126: env TESTKIT_EE_REPEAT=N plays the floors N times, TESTKIT_CPU_BURN=N loads the CPUs meanwhile (floor 1
        // failed only inside a full run: the door trip's planner out of time while its floor graph still warmed).
        String rep = System.getenv("TESTKIT_EE_REPEAT");
        int rounds = rep == null ? 1 : Integer.parseInt(rep.trim());
        dev.testkit.harness.CpuBurn.startFromEnv();
        for (int i = 0; i < SEEDS.length * rounds; i++) {
            try {
                int[] t = new int[1];
                floors.add("floor " + (i + 1) + ": " + floorCase(ctx, SEEDS[i % SEEDS.length], i + 1, f, t));
                trips += t[0];
            } catch (Throwable e) {
                e.printStackTrace(System.out);
                f.add("floor " + (i + 1) + " threw " + e);
            } finally {
                ctx.runOnClient(mc -> {
                    ModUnderTest.staticCall(PILOT, "setOn", new Class<?>[]{boolean.class}, new Object[]{false});
                    ModUnderTest.staticCall(AUTO_CLEAR, "cancel");
                    if ((Boolean) ModUnderTest.staticCall(AS, "isRunning")) {
                        ModUnderTest.staticCall(AS, "stop", new Class<?>[]{String.class, boolean.class},
                                new Object[]{"test over", true});
                    }
                });
                leave(ctx);
            }
        }
        dev.testkit.harness.CpuBurn.stop();
        ctx.runOnClient(mc -> {
            Object as = ModUnderTest.config(AS_CONFIG);
            Object none = ModUnderTest.enumValue(AS_CONFIG + "$BloodRush", "NONE");
            ModUnderTest.call(as, "setBloodRush", new Class<?>[]{none.getClass()}, new Object[]{none});
            ModUnderTest.set(as, "setDisableAfterBloodRush", false);
            ModUnderTest.set(ModUnderTest.config(AUTO_CLEAR_CONFIG), "setEnabled", false);
        });
        f.add(trips > 0 ? null : "no floor's first trip went to a wither door: the stand-side check never acted");
        f.removeIf(java.util.Objects::isNull);
        finish(CASE, f.isEmpty() ? null : String.join("; ", f), trips + " door trip(s) checked | " + String.join(" | ", floors));
        if (!f.isEmpty()) {
            throw new AssertionError(String.join("; ", f));
        }
    }

    /**
     * 1126-sim-ac-coldtrip (acflakes-1126): three trips that fail while the floor graph is not warm (the planner's
     * "nothing" ran out of time, no proof of no way) are not counted toward giving the wither door up. On a loaded PC four
     * such failures in 2 s at GO gave the door up for good (entrance-exit floor 1, 11-26 1 PM).
     */
    private static void coldTrip(ClientGameTestContext ctx) {
        long mark = LogTap.mark();
        String r = ctx.computeOnClient(mc -> {
            try {
                if ((Boolean) ModUnderTest.staticCall("com.killer560.hub.livemap.autoclear.EtherwarpPathfinder", "fullWarm")) {
                    return "the floor graph is warm here: nothing to test";
                }
                Class<?> c = Class.forName(AUTO_CLEAR, true, mc.getClass().getClassLoader());
                java.lang.reflect.Field ts = c.getDeclaredField("travelStarted");
                ts.setAccessible(true);
                java.lang.reflect.Method te = c.getDeclaredMethod("tripEnded", net.minecraft.client.player.LocalPlayer.class,
                        boolean.class);
                te.setAccessible(true);
                for (int i = 0; i < 3; i++) {
                    ts.setBoolean(null, true);
                    te.invoke(null, mc.player, true);
                }
                ts.setBoolean(null, false);
                return null;
            } catch (Throwable t) {
                return "reflection: " + t;
            }
        });
        ctx.runOnClient(mc -> ModUnderTest.staticCall(AUTO_CLEAR, "cancel"));
        List<String> lines = LogTap.since(mark);
        long uncounted = lines.stream().filter(l -> l.contains("still warming up - not counted")).count();
        long counted = lines.stream().filter(l -> l.contains("[AutoClear] trip ") && l.contains(" of 4)")).count();
        String d = "3 failed trips on a cold floor graph: " + uncounted + " not counted, " + counted + " counted";
        finish(COLD, r != null ? r : uncounted == 3 && counted == 0 ? null : "cold-graph failures were counted", d);
    }

    private static void finish(String name, String failure, String detail) {
        boolean pass = failure == null;
        String text = pass ? detail : failure + " | " + detail;
        System.out.println("[" + name + "] " + (pass ? "PASS" : "FAIL") + " - " + text);
        Report.caseStarted(name);
        Report.caseFinished(name, pass ? "PASS" : "FAIL", "n/a (singleplayer)", text, List.of(), List.of());
        SuiteVerdict.finished(name);
    }

    private static String floorCase(ClientGameTestContext ctx, long seed, int n, List<String> f, int[] trips) {
        BlockPos door = stage(ctx, seed, f);
        if (door == null) {
            return "not staged";
        }
        Vec3 spawn = ctx.computeOnClient(mc -> mc.player.position());
        int[] in = inward(spawn, door);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(PILOT, "setOn", new Class<?>[]{boolean.class}, new Object[]{true}));
        // The green room walk (when the jar has one) to the door's front, settled before GO.
        Vec3 last = spawn;
        int still = 0;
        for (int t = 0; t < 400 && still < 20; t++) {
            ctx.waitTicks(1);
            Vec3 now = ctx.computeOnClient(mc -> mc.player.position());
            still = now.distanceTo(last) < 0.01 && t > 20 ? still + 1 : 0;
            last = now;
        }
        long mark = LogTap.mark();
        ctx.runOnClient(mc -> setTicksLeft(1));
        for (int toGo = 0; toGo < 40 && !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SIM_RUN, "isRunning")); toGo++) {
            ctx.waitTicks(1);
        }
        int out = -1;
        for (int i = 1; i <= 60 && out < 0; i++) {
            ctx.waitTicks(1);
            Vec3 now = ctx.computeOnClient(mc -> mc.player.position());
            double past = -((now.x - (door.getX() + 0.5)) * in[0] + (now.z - (door.getZ() + 0.5)) * in[1]);
            if (past > 1.0) {
                out = i;
            }
        }
        List<String> lines = LogTap.since(mark);
        long noRoute = lines.stream().filter(l -> l.contains("No ROOM route")).count();
        int across = 0;
        String trip = "";
        for (String l : lines) {
            Matcher m = TRIP.matcher(l);
            if (!m.find()) {
                continue;
            }
            int d = Integer.parseInt(m.group(1));
            boolean alongX = (d % 11) % 2 == 1;
            int lock = alongX ? -185 + (d % 11) * 16 : -185 + (d / 11) * 16;
            int a = Integer.parseInt(m.group(alongX ? 2 : 3)) - lock;
            int s = Integer.parseInt(m.group(alongX ? 4 : 5)) - lock;
            trips[0]++;
            if (Integer.signum(a) != Integer.signum(s)) {
                across++;
            }
            if (trip.isEmpty()) {
                trip = m.group(0);
            }
        }
        String d = String.format(Locale.US, "seed +%d, spawn %.0f,%.0f,%.0f, door %s: out at tick %d, %d \"No ROOM route\","
                + " %d trip line(s) standing across their door; %s", seed - SEED, spawn.x, spawn.y, spawn.z,
                door.toShortString(), out, noRoute, across, trip.isEmpty() ? "no door trip" : trip);
        System.out.println("[" + CASE + "] floor " + n + ": " + d);
        f.add(out > 0 && out <= 40 ? null : "floor " + n + ": not out through the Entrance door within 40 ticks of GO (tick " + out + ")");
        f.add(noRoute <= 1 ? null : "floor " + n + ": \"No ROOM route\" logged " + noRoute + " times after GO");
        f.add(across == 0 ? null : "floor " + n + ": " + across + " door trip(s) stood across the door from their approach");
        return d;
    }

    /** The floor built from {@code seed}, its countdown held; the entrance door, or null (with a failure). */
    static BlockPos stage(ClientGameTestContext ctx, long seed, List<String> f) {
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter", new Class<?>[]{String.class},
                new Object[]{"gametest"}));
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
        if (!held || door == null) {
            f.add("seed +" + (seed - SEED) + ": countdown not caught (held " + held + ", door " + door + ")");
            return null;
        }
        SimAutopilotTests.awaitFloorChunks(ctx);
        loadout(ctx);
        SimAutopilotTests.ground(ctx);
        ctx.waitTicks(40);
        return door;
    }

    static void setTicksLeft(int ticks) {
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

    static void leave(ClientGameTestContext ctx) {
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
