package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 1018-map-blood-unknown (mod todo-map-blood-1018): killer560, 2026-10-18, to-do 20: "just show it as normal no grey". A
 * room the map has no database entry for, and that the world scan has not reached either, was typed "UNKNOWN" and drawn in
 * the unopened grey. It is drawn as an ordinary room now.
 *
 * <p>A sim floor (no map item, so nothing is hidden), then a room group the painter has never seen - a tile no placement
 * covers, no database entry - is handed to {@code MapPainter.roomType} and {@code roomColor}: the type must be NORMAL and
 * the fill the Normal colour, not the unopened grey.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimMapUnknownRoomTests implements FabricClientGameTest {

    static final String CASE = "1018-map-blood-unknown";
    private static final String MAP = "livemap.LiveMapConfig";
    private static final int ROOM_GRID = 6;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip(CASE)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (SimMapTests.copyRoomsForOthers() < 20) {
            Scenario.skipped(CASE, "needs real room captures and room database");
            return;
        }
        ctx.runOnClient(mc -> Scenario.loadRoomDatabaseNow());
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.roomdatabase.RoomDatabase", "isReady"),
                1000);
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.RoomLibrary", "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.roomsim.RoomLibrary", "isReady"));

        List<AutoCloseable> restore = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        try {
            ctx.runOnClient(mc -> {
                restore.add(0, Mod.with(MAP, "Enabled", true));
                restore.add(0, Mod.with(MAP, "ColourByType", true));
                try {
                    Mod.set(MAP, "setMapThemeName", "REAL");
                } catch (AssertionError oldJar) {
                    // a jar from before map-overhaul paints Real Map colours by default
                }
            });
            Map<Integer, String> placements = new LinkedHashMap<>();
            placements.put(1 * ROOM_GRID + 1, "Entrance");
            placements.put(1 * ROOM_GRID + 2, "New Trap");
            SimMapItemTests.build(ctx, placements);
            ctx.waitTicks(20);

            Object[] answer = ctx.computeOnClient(mc -> {
                try {
                    Class<?> groupClass = Class.forName("com.killer560.hub.livemap.LiveMapFeature$RoomGroup");
                    Constructor<?> ctor = groupClass.getDeclaredConstructor(int.class, int[].class, int[].class,
                            Class.forName("com.killer560.hub.roomdatabase.RoomEntry"));
                    ctor.setAccessible(true);
                    int tile = 10 * 11 + 10;                      // the far corner: no placement, never world-scanned
                    Object scanned = Mod.staticCall("livemap.LiveMapFeature", "isWorldScanned", tile);
                    Object group = ctor.newInstance(tile, new int[]{tile}, new int[]{tile}, null);
                    Object cfg = Mod.cfg(MAP);
                    Object type = Mod.staticCall("livemap.MapPainter", "roomType", group);
                    Object color = Mod.staticCall("livemap.MapPainter", "roomColor", group, cfg);
                    Object normal = Mod.get(MAP, "getColorNormal");
                    Object unopened = Mod.get(MAP, "getColorUnopened");
                    return new Object[]{scanned, type, color, normal, unopened};
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError("could not build a room group: " + e, e);
                }
            });
            System.out.println(String.format(Locale.ROOT, "[%s] world-scanned %s, type %s, fill #%08X, Normal #%08X, "
                            + "unopened gray #%08X", CASE, answer[0], answer[1], (Integer) answer[2], (Integer) answer[3],
                    (Integer) answer[4]));
            if (Boolean.TRUE.equals(answer[0])) {
                failures.add("the far-corner tile was world-scanned, the case does not test an unscanned room");
            }
            if (!"NORMAL".equals(answer[1])) {
                failures.add("a room with no database entry is typed '" + answer[1] + "', want NORMAL");
            }
            if (!answer[2].equals(answer[3])) {
                failures.add(String.format(Locale.ROOT, "a room with no database entry is filled #%08X, want the Normal"
                        + " color #%08X", (Integer) answer[2], (Integer) answer[3]));
            }
        } catch (Throwable t) {
            failures.add("scenario threw: " + t);
            t.printStackTrace(System.out);
        } finally {
            ctx.runOnClient(mc -> {
                McCompat.setScreen(mc, null);
                for (AutoCloseable r : restore) {
                    try {
                        r.close();
                    } catch (Exception e) {
                        System.out.println("restore failed: " + e);
                    }
                }
            });
            ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimState", "leave"));
            ctx.runOnClient(mc -> mc.execute(() -> {
                if (mc.level != null) {
                    mc.level.disconnect(net.minecraft.network.chat.Component.literal("scenario over"));
                    mc.disconnectWithSavingScreen();
                }
            }));
            ctx.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null);
            ctx.waitTicks(40);
            ctx.runOnClient(mc -> mc.execute(() ->
                    McCompat.setScreen(mc, new net.minecraft.client.gui.screens.TitleScreen())));
            ctx.waitFor(mc -> McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.TitleScreen);
        }
        System.out.println("[" + CASE + "] " + (failures.isEmpty() ? "PASS" : "FAIL: " + String.join("; ", failures)));
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " problem(s): " + String.join("; ", failures));
        }
    }
}
