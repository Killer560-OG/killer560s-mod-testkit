package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>1124-sim-ac-presneak</b> (mod acoptimal-next-1124, from killer560's 11-21 "rank every single gap"): after an in-place
 * Wither Impact, the etherwarp trip to the next mob group leaves the tick the death is seen.
 * <p>
 * Origin pressed the sneak only on the tick the death came back, and a warp waits until the server has heard the sneak,
 * so every trip after a kill started a tick later (1020-sim-ac-speed on the 6 floors: 178 of 229 kill-to-warp gaps were
 * one tick, none zero). The fix holds the sneak down while the cast's deaths come back when only an etherwarp can follow.
 * <p>
 * One fixed sim F7 (floor 1 of codes.txt in killer560s-mod-logs/acslow-1121-state.md's 11-24 section), live starred mobs
 * on the rooms' spawn boxes, Auto Clear alone with the Hyperion (1020-sim-ac-speed's Hyperion loadout). A probe reads, each
 * tick, the kills Auto Clear counted and the ticks its warps went out on: a kill-to-first-warp gap of 0 is the fix acting.
 * Asserts the floor cleared, at least {@link #MIN_SAME_TICK} trips left on the kill's own tick, and
 * {@code AutoClearFeature.primedCastTicks} went up. Cheat only.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimAcPreSneakTests implements FabricClientGameTest {

    private static final String NAME = "1124-sim-ac-presneak";
    private static final String AUTO = "com.killer560.hub.autoclear.AutoClearFeature";
    private static final String EXEC = "com.killer560.hub.livemap.autoclear.ClearExecutor";
    private static final String CODE = "MC2:CxcIRW50cmFuY2UFTWluZXMFRmFpcnkFTW9zc3kSQ29iYmxlIFdhbGwgUGlsbGFyCEJhc2VtZW50BUJsb29kCE5ldyBUcmFwBk1pcnJvcglXYXRlcmZhbGwDRGlwC1ByaXNvbiBDZWxsB0J1dHRvbnMJT3Zlcmdyb3duBUNyeXB0B0JvdWxkZXIGRHVuY2FuC1RpYyBUYWMgVG9lDkxvdHMgT2YgRmxvb3JzDUNyZWVwZXIgQmVhbXMQT3Zlcmdyb3duIENoYWlucw5SYXJlIE92ZXJncm93bgdUb21pb2thABACAAAAAA0CAA0CAA0CAQAAABEBAAAAAAcCAwAAAAYBAQAAAAAAAA0CAA0CAA0CAAAAAAAAAAAAAAAAAAAAAQAAAA8DAAAAAA0CAA0CAA0CAAAAAAgCAAAAAAkAAAAAAAUBAA8DAAAAAAAAAAAAAQAAAAAAAQAAAAAAAQAAAAAAAQAAAA8DAAAAABcAAQAAAAQCAAQCAAQCAAQCAAQCAAQCAAQCAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAA4CAQAAABIBAAAAABQBAQAAABMCAAAAAAMCAAAAAAsBAQAAAAAAAAAAAAAAAAAAAAAAAQAAAAAAAQAAAAAAAQAAAAoAAAoAAAoAAAoAAAoAAAoAAAoAAQAAAAIDAAIDAAIDAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAIDAAIDAAIDAAwDAQAAABUAAQAAABYCAAAAAAEABAAAAAIDAAIDAAID";
    /** Trips that left on the kill's own tick, at least (origin: 0). */
    private static final int MIN_SAME_TICK = 8;

    private static boolean registered;
    private static Field killsF;
    private static int lastKills = -1;
    private static long killTick = -1;
    private static int lastUses = -1;
    private static int lastHops = -1;
    private static final int[] GAPS = new int[6];

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank() || Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.runOnClient(mc -> install());
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (SimMapTests.copyRoomsForOthers() < 20) {
            Scenario.skipped(NAME, "needs real room captures and room database");
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.RoomLibrary", "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.roomsim.RoomLibrary", "isReady"), 2400);
        ctx.runOnClient(mc -> {
            ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autopuzzles.AutoPuzzlesConfig"),
                    "setAutoPuzzlesMasterEnabled", false);
            Object map = ModUnderTest.config("com.killer560.hub.livemap.LiveMapConfig");
            ModUnderTest.set(map, "setEnabled", true);
            ModUnderTest.set(map, "setInteractiveMapEnabled", true);
        });
        String[][] props = {{"testkit.acsHype", "true"}, {"testkit.acsHypeWeapon", "true"}, {"testkit.acsMobs", "3"},
                {"testkit.acsMinutes", "4"}};
        String[] old = new String[props.length];
        for (int i = 0; i < props.length; i++) {
            old[i] = System.getProperty(props[i][0]);
            System.setProperty(props[i][0], props[i][1]);
        }
        int renderBefore = ctx.computeOnClient(mc -> mc.options.renderDistance().get());
        ctx.runOnClient(mc -> mc.options.renderDistance().set(16));
        int primedBefore = primed(ctx);
        ctx.runOnClient(mc -> java.util.Arrays.fill(GAPS, 0));
        List<SimAutoClearSpeedTests.RoomTime> rooms = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        try {
            println("SUMMARY " + SimAutoClearSpeedTests.oneFloor(ctx, 1, CODE, rooms, problems));
        } finally {
            SimAutoClearSpeedTests.leave(ctx);
            ctx.runOnClient(mc -> mc.options.renderDistance().set(renderBefore));
            for (int i = 0; i < props.length; i++) {
                if (old[i] == null) {
                    System.clearProperty(props[i][0]);
                } else {
                    System.setProperty(props[i][0], old[i]);
                }
            }
        }
        int primedAfter = primed(ctx);
        int[] gaps = ctx.computeOnClient(mc -> GAPS.clone());
        int total = rooms.stream().mapToInt(SimAutoClearSpeedTests.RoomTime::ticks).sum();
        println("RESULT room ticks " + total + " over " + rooms.size() + " rooms; kill-to-first-warp gaps 0:" + gaps[0]
                + " 1:" + gaps[1] + " 2:" + gaps[2] + " 3:" + gaps[3] + " 4:" + gaps[4] + " 5+:" + gaps[5]
                + "; primed sneak ticks " + primedBefore + " -> " + primedAfter);
        long notCleared = rooms.stream().filter(r -> !r.cleared()).count();
        if (rooms.size() < 10 || notCleared > 0) {
            problems.add(rooms.size() + " room line(s), " + notCleared + " not cleared - the floor was not cleared");
        }
        if (gaps[0] < MIN_SAME_TICK) {
            problems.add(gaps[0] + " trip(s) left on the kill's own tick, want at least " + MIN_SAME_TICK
                    + " (gaps 1:" + gaps[1] + " 2:" + gaps[2] + " 3+:" + (gaps[3] + gaps[4] + gaps[5]) + ")");
        }
        if (primedBefore < 0 || primedAfter <= primedBefore) {
            problems.add("the sneak was never primed after a cast (primed ticks " + primedBefore + " -> " + primedAfter + ")");
        }
        if (!problems.isEmpty()) {
            throw new AssertionError(problems.size() + " problem(s): " + String.join("; ", problems));
        }
        println("PASS");
    }

    /** Registers the kill-to-warp probe once (client thread). */
    private static void install() {
        if (registered) {
            return;
        }
        try {
            killsF = Class.forName(AUTO).getDeclaredField("mobsKilled");
            killsF.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(NAME + ": " + e);
        }
        registered = true;
        ClientTickEvents.END_CLIENT_TICK.register(SimAcPreSneakTests::probe);
    }

    /**
     * END of each tick: a kill Auto Clear counted this tick opens a gap; the first warp after it closes it at its send
     * tick; any other use first (a cast, a bat) closes it as not a trip.
     */
    @SuppressWarnings("unchecked")
    private static void probe(Minecraft mc) {
        if (mc.player == null) {
            return;
        }
        int kills;
        long tick;
        int uses;
        List<Long> hops;
        try {
            kills = killsF.getInt(null);
            tick = (Long) ModUnderTest.staticCall(EXEC, "clientTick");
            uses = (Integer) ModUnderTest.staticCall("com.killer560.hub.util.RightClick", "itemUsesSent");
            hops = (List<Long>) ModUnderTest.staticCall(EXEC, "hopSendTicks");
        } catch (ReflectiveOperationException | RuntimeException e) {
            return;
        }
        int hopCount = hops.size();
        long newHop = -1;
        if (lastHops >= 0 && hopCount > 0 && (hopCount != lastHops || hops.get(hopCount - 1) >= tick)) {
            for (Long t : hops) {
                if (t > killTick && killTick >= 0) {
                    newHop = t;
                    break;
                }
            }
        }
        if (killTick >= 0) {
            if (newHop >= 0) {
                GAPS[(int) Math.min(5, newHop - killTick)]++;
                killTick = -1;
            } else if (lastUses >= 0 && uses > lastUses) {
                killTick = -1;   // a cast or a bat went first: not a trip after the kill
            }
        }
        if (lastKills >= 0 && kills > lastKills) {
            killTick = tick;
            if (!hops.isEmpty() && hops.get(hops.size() - 1) == tick) {
                GAPS[0]++;   // the warp went in the kill's own tick
                killTick = -1;
            }
        }
        lastKills = kills;
        lastUses = uses;
        lastHops = hopCount;
    }

    private static int primed(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            try {
                return (Integer) ModUnderTest.staticCall(AUTO, "primedCastTicks");
            } catch (RuntimeException | Error e) {
                return -1;   // origin: no primed sneak
            }
        });
    }

    private static void println(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}
