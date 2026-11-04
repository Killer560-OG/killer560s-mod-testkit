package dev.testkit.gametest;

import dev.testkit.compat.McCompat;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>1104-leak-sim-hops</b> (scanleak-1104): join a full F7 sim world and leave it 30 times, forcing a GC after every
 * leave, and print the heap in use plus the per-class growth of the mod's own classes between hop 4 and hop 30. A
 * leak that survives a world change shows as heap that keeps climbing and as com.killer560 / Minecraft classes whose
 * instance counts only go up. Fails when the heap after GC grew by more than the allowed amount (default 150 MB, or
 * -Dkiller560.test.heapgrowthmb) from hop 4 to hop 30, or when no hop actually built a world.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimHeapHopTests implements FabricClientGameTest {

    private static final String NAME = "1104-leak-sim-hops";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final Pattern HISTO = Pattern.compile("^\\s*\\d+:\\s+(\\d+)\\s+(\\d+)\\s+(\\S+)");

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (SimMapTests.copyRoomsForOthers() < 20) {
            Scenario.skipped(NAME, "needs real room captures and room database");
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.roomsim.RoomLibrary", "isReady"), 1000);
        int hops = Integer.getInteger("killer560.test.hops", 30);
        long allowMb = Long.getLong("killer560.test.heapgrowthmb", 150L);
        int baseHop = 4;
        long[] heapMb = new long[hops + 1];
        Map<String, long[]> base = null;
        Map<String, long[]> last = null;
        Map<String, String> baseSizes = null;
        Map<String, String> lastSizes = null;
        int built = 0;
        String failure = null;
        try {
            for (int i = 1; i <= hops; i++) {
                long before = Scenario.simBuildCount(ctx);
                ctx.runOnClient(mc -> mc.execute(() -> {
                    Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
                    ModUnderTest.staticCall(FLOOR_GEN, "generate",
                            new Class<?>[]{Minecraft.class, floor.getClass(), int.class, int.class},
                            new Object[]{mc, floor, 3, 4});
                }));
                ctx.waitFor(mc -> mc.level != null, 2000);
                Scenario.awaitSimBuild(ctx, before);
                if (Scenario.simBuildCount(ctx) > before
                        && ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SIM_STATE, "isActive"))) {
                    built++;
                }
                ctx.waitTicks(40);
                leave(ctx);
                heapMb[i] = settledHeapMb();
                System.out.println("[" + NAME + "] hop " + i + "/" + hops + ": heap after GC " + heapMb[i] + " MB");
                if (i == baseHop) {
                    base = histogram();
                    baseSizes = suspectSizes(ctx);
                }
                if (i == hops) {
                    last = histogram();
                    lastSizes = suspectSizes(ctx);
                }
            }
        } catch (Throwable t) {
            failure = "scenario threw: " + t;
            t.printStackTrace(System.out);
        } finally {
            try {
                leave(ctx);
            } catch (Throwable ignored) {
                // already out
            }
        }
        if (base != null && last != null) {
            List<String> rows = new ArrayList<>();
            for (Map.Entry<String, long[]> e : last.entrySet()) {
                long[] b = base.getOrDefault(e.getKey(), new long[]{0, 0});
                long dInst = e.getValue()[0] - b[0];
                long dBytes = e.getValue()[1] - b[1];
                if (dBytes > 200_000 || dInst > 2000) {
                    rows.add(String.format("%12d bytes %9d inst  %s", dBytes, dInst, e.getKey()));
                }
            }
            rows.sort((a, b) -> Long.compare(Long.parseLong(b.substring(0, 12).trim()),
                    Long.parseLong(a.substring(0, 12).trim())));
            System.out.println("[" + NAME + "] classes that grew from hop " + baseHop + " to hop " + hops
                    + " (live instances after GC):");
            for (int i = 0; i < Math.min(40, rows.size()); i++) {
                System.out.println("[" + NAME + "]   " + rows.get(i));
            }
        }
        if (baseSizes != null && lastSizes != null) {
            for (Map.Entry<String, String> e : lastSizes.entrySet()) {
                System.out.println("[" + NAME + "] size " + e.getKey() + ": hop " + baseHop + " = "
                        + baseSizes.get(e.getKey()) + ", hop " + hops + " = " + e.getValue());
            }
        }
        long growth = heapMb[hops] - heapMb[baseHop];
        System.out.println("[" + NAME + "] worlds built " + built + "/" + hops + "; heap hop " + baseHop + " "
                + heapMb[baseHop] + " MB -> hop " + hops + " " + heapMb[hops] + " MB (growth " + growth
                + " MB, allowed " + allowMb + ")");
        if (failure == null && built < hops) {
            failure = "only " + built + " of " + hops + " hops built a sim world; the test did not exercise the leave";
        }
        if (failure == null && growth > allowMb) {
            failure = "heap after GC grew " + growth + " MB over " + (hops - baseHop) + " world hops";
        }
        String digFailure = digMapPhase(ctx);
        if (failure == null) {
            failure = digFailure;
        }
        if (failure != null) {
            System.out.println("[" + NAME + "] FAIL " + failure);
            throw new AssertionError("[" + NAME + "] " + failure);
        }
        System.out.println("[" + NAME + "] PASS");
    }

    private static final String H = "com.killer560.hub.";
    private static final String[][] SUSPECTS = {
        {H + "autoroutes.RouteExecutor", "lastDugAt"},
        {H + "social.PlayerHeadRenderer", "RESOLVED"}, {H + "social.PlayerHeadRenderer", "FETCH_AT"},
        {H + "social.PlayerHeadRenderer", "IN_FLIGHT"},
        {H + "players.PlayerNames", "NAME_FAILED_AT"}, {H + "players.PlayerNames", "IN_FLIGHT_NAME"},
        {H + "roomsim.SimSecrets", "PLACED_CHESTS"}, {H + "roomsim.SimDoors", "CARVED"},
        {H + "roomsim.SimMimic", "OPENED"}, {H + "roomsim.SimMimic", "PUZZLE_REWARDS"},
        {H + "roomsim.SimMobs", "KILLED"}, {H + "roomsim.SimMobs", "LIVE"}, {H + "roomsim.SimMobs", "STARRED_DEAD"},
        {H + "roomsim.SimMiniboss", "PLACED_AT"}, {H + "roomsim.DeucesChestDoorway", "WORLD"},
        {H + "roomsim.puzzles.SimBlazePuzzle", "SEEN_DEAD"}, {H + "roomsim.puzzles.SimCreeperPuzzle", "NOT_A_PAIR_SAID"},
        {H + "bosssim.BossLava", "bouncedAt"}, {H + "util.ServerCorrections", "chatState"},
        {H + "simonsays.SimonSaysFeature", "lastSeenGridButtonBoxes"}, {H + "objecthider.ObjectHiderFeature", "deadTags"},
        {H + "bloodcamp.BloodCampFeature", "bloodMobs"}, {H + "util.ChatObserver", "FAILURES_BY_LISTENER"},
        {H + "util.PerTickServerClock", "LISTENERS"}, {H + "witherdragons.ServerTickClock", "LISTENERS"},
        {H + "macrostop.MacroStop", "MACROS"}, {H + "macrostop.MacroStop", "ON_EMERGENCY"},
        {H + "leveraura.P3SectionTracker", "LISTENERS"}, {H + "shared.SharedRecords", "ACCOUNT_LISTENERS"},
        {H + "util.TurnFirst", "BODY_OWNERS"}, {H + "util.PingTracker", "DEMAND"},
        {H + "containercover.ContainerCover", "FULL"}, {H + "util.CommandTreeRefresh", "GATES"},
    };

    /** Size of each suspect static collection, by reflection; "-" when the class or field is not in this jar. */
    private static Map<String, String> suspectSizes(ClientGameTestContext ctx) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (String[] s : SUSPECTS) {
            String key = s[0].substring(s[0].lastIndexOf('.') + 1) + "." + s[1];
            String v = ctx.computeOnClient(mc -> {
                try {
                    Class<?> c = Class.forName(s[0]);
                    java.lang.reflect.Field f = c.getDeclaredField(s[1]);
                    f.setAccessible(true);
                    Object o = f.get(null);
                    if (o instanceof java.util.Collection<?> col) {
                        return String.valueOf(col.size());
                    }
                    if (o instanceof Map<?, ?> m) {
                        return String.valueOf(m.size());
                    }
                    return "?";
                } catch (Throwable t) {
                    return "-";
                }
            });
            out.put(key, v);
        }
        return out;
    }

    /**
     * Auto Routes' breaker records every dig position in RouteExecutor.lastDugAt, which only ever grew. Joins a flat sim
     * room and calls the real private sendBreak 5 times a tick for 300 ticks at distinct high-air positions, then
     * requires the map to hold a few hundred entries at most (fixed jar) instead of all 1500 (old jar). Also requires
     * the map to have been filled at all, so a call that did nothing cannot pass.
     */
    private static String digMapPhase(ClientGameTestContext ctx) {
        try {
            long before = Scenario.simBuildCount(ctx);
            ctx.runOnClient(mc -> mc.execute(() -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimBuilder",
                    "buildFlatTest", new Class<?>[]{Minecraft.class}, new Object[]{mc})));
            ctx.waitFor(mc -> mc.level != null, 2000);
            Scenario.awaitSimBuild(ctx, before);
            ctx.waitTicks(40);
            int sent = 0;
            for (int tick = 0; tick < 300; tick++) {
                final int t = tick;
                sent += ctx.computeOnClient(mc -> {
                    int n = 0;
                    try {
                        Class<?> c = Class.forName("com.killer560.hub.autoroutes.RouteExecutor");
                        java.lang.reflect.Method m = c.getDeclaredMethod("sendBreak", Minecraft.class,
                                net.minecraft.client.player.LocalPlayer.class, BlockPos.class);
                        m.setAccessible(true);
                        for (int k = 0; k < 5; k++) {
                            m.invoke(null, mc, mc.player, new BlockPos(1000 + t, 200 + k, 1000));
                            n++;
                        }
                    } catch (Throwable e) {
                        System.out.println("[" + NAME + "] sendBreak failed: " + e);
                    }
                    return n;
                });
                ctx.waitTicks(1);
            }
            int size = ctx.computeOnClient(mc -> {
                try {
                    java.lang.reflect.Field f = Class.forName("com.killer560.hub.autoroutes.RouteExecutor")
                            .getDeclaredField("lastDugAt");
                    f.setAccessible(true);
                    return ((Map<?, ?>) f.get(null)).size();
                } catch (Throwable e) {
                    return -1;
                }
            });
            System.out.println("[" + NAME + "] dug-map: " + sent + " digs sent over 300 ticks, lastDugAt holds " + size);
            leave(ctx);
            if (sent < 1400 || size < 0) {
                return "the dig phase did not exercise sendBreak (sent " + sent + ", size " + size + ")";
            }
            return size > 400 ? "RouteExecutor.lastDugAt kept " + size + " of " + sent + " dug positions" : null;
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            return "dig phase threw: " + t;
        }
    }

    private static long settledHeapMb() {
        long used = 0;
        for (int i = 0; i < 4; i++) {
            System.gc();
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Runtime rt = Runtime.getRuntime();
            used = (rt.totalMemory() - rt.freeMemory()) >> 20;
        }
        return used;
    }

    /** Live-object class histogram (forces a full GC), only com.killer560 / net.minecraft / java collections kept. */
    private static Map<String, long[]> histogram() {
        Map<String, long[]> out = new HashMap<>();
        try {
            javax.management.ObjectName on = new javax.management.ObjectName("com.sun.management:type=DiagnosticCommand");
            String text = (String) ManagementFactory.getPlatformMBeanServer().invoke(on, "gcClassHistogram",
                    new Object[]{new String[0]}, new String[]{String[].class.getName()});
            for (String line : text.split("\\R")) {
                Matcher m = HISTO.matcher(line);
                if (m.find()) {
                    out.put(m.group(3), new long[]{Long.parseLong(m.group(1)), Long.parseLong(m.group(2))});
                }
            }
        } catch (Throwable t) {
            System.out.println("[" + NAME + "] class histogram unavailable: " + t);
        }
        return out;
    }

    private static void leave(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> mc.execute(() -> {
            if (mc.level != null) {
                mc.level.disconnect(net.minecraft.network.chat.Component.literal("scenario over"));
                mc.disconnectWithSavingScreen();
            }
        }));
        ctx.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null, 2000);
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> mc.execute(() ->
                McCompat.setScreen(mc, new net.minecraft.client.gui.screens.TitleScreen())));
        ctx.waitFor(mc -> McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.TitleScreen);
    }
}
