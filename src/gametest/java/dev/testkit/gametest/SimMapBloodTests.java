package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 1018-map-blood-unopened (mod todo-map-blood-1018): killer560, 2026-10-18, to-do 20: "but like the light gray for blood
 * room that the funny map style uses for unopened rooms". The Blood Room behind the red door, still unopened, is drawn in
 * the plain unopened gray (undarkened), with no "?" over it. Every other room is left as it was.
 *
 * <p>Corrected the same day: grayed in both styles - Funny Map like every other unopened room (darkened), the regular map
 * in the light undarkened gray when its blood door is known.
 *
 * <p>The sim floor of 1015-sim-map-item-only (Entrance, New Trap, Andesite, Cell, Blood) and its Hypixel-style map item,
 * plus a Blood room painted as an unopened gray square below Andesite with a red (color 18) door between them. Read from
 * what the Dungeon Map HUD draws: the Blood fill must equal the unopened gray exactly (Darken Unexplored 0.4 would make
 * it 0.6 of that), it carries no mark fills of its own, and Andesite - an ordinary unopened room - is still darkened.
 * The cheat build repeats it with Funny Map ON, where Cell (not on the item) must still be its own color darkened.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimMapBloodTests implements FabricClientGameTest {

    static final String CASE = "1018-map-blood-unopened";
    private static final String MAP = "livemap.LiveMapConfig";
    private static final int ROOM_GRID = 6;
    private static final int NORMAL = 0xFF724318;
    private static final int UNOPENED = 0xFF414141;

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
        boolean cheat = Mod.isCheat();
        SimMapItemTests.lastOrigin = null;
        try {
            ctx.runOnClient(mc -> {
                restore.add(0, Mod.with("hud.HudConfig", "AutoScale", false));
                restore.add(0, Mod.with(MAP, "Enabled", true));
                restore.add(0, Mod.with(MAP, "RoomLabels", 1));
                restore.add(0, Mod.with(MAP, "ColourByType", true));
                SimMapItemTests.tryWith(restore, MAP, "CustomCheckmarks", false);
                SimMapItemTests.tryWith(restore, MAP, "FunnyMap", false);
                try {
                    Mod.set(MAP, "setMapThemeName", "REAL");
                } catch (AssertionError oldJar) {
                    // a jar from before map-overhaul paints Real Map colors by default
                }
                restore.add(0, Mod.with(MAP, "DarkenUnopened", 0.4f));
                restore.add(0, Mod.with("autopuzzles.AutoPuzzlesConfig", "AutoPuzzlesMasterEnabled", false));
                restore.add(0, Mod.with("mapping.MappingConfig", "ExtraInfoEnabled", false));
            });
            Map<Integer, String> placements = new LinkedHashMap<>();
            placements.put(1 * ROOM_GRID + 1, "Entrance");
            placements.put(1 * ROOM_GRID + 2, "New Trap");
            placements.put(1 * ROOM_GRID + 3, "Andesite");
            placements.put(2 * ROOM_GRID + 1, "Cell");
            placements.put(2 * ROOM_GRID + 3, "Blood");
            SimMapItemTests.build(ctx, placements);
            ctx.waitTicks(20);

            byte[] px = SimMapItemTests.paintMap();
            SimMapItemTests.room(px, 3, 1, 63);              // Andesite opened: the room the red door is reached from
            SimMapItemTests.room(px, 1, 2, 85);              // Cell: an ordinary unopened room, with a plain door to it
            int[] e = SimMapItemTests.origin(1, 1);
            for (int y = 16; y < 20; y++) {
                for (int x = 5; x <= 10; x++) {
                    SimMapItemTests.set(px, e[0] + x, e[1] + y, 63);
                }
            }
            SimMapItemTests.room(px, 3, 2, 85);              // Blood: unopened gray, as the item shows a room not opened
            int[] o = SimMapItemTests.origin(3, 1);
            for (int y = 16; y < 20; y++) {                  // the red (18) door below Andesite, leading into it
                for (int x = 5; x <= 10; x++) {
                    SimMapItemTests.set(px, o[0] + x, o[1] + y, 18);
                }
            }
            SimMapItemTests.giveMap(ctx, px);
            Boolean calibrated = false;
            for (int i = 0; i < 60 && !Boolean.TRUE.equals(calibrated); i++) {
                ctx.waitTicks(5);
                calibrated = SimMapItemTests.hook(ctx, "livemap.MapPainter", "mapItemCalibrated");
            }
            if (!Boolean.TRUE.equals(calibrated)) {
                failures.add("the painted dungeon map was never read - the case cannot judge the map");
                return;
            }
            ctx.waitTicks(10);
            judge(ctx, failures, cheat ? "cheat, Funny Map OFF" : "legit", false);
            if (cheat) {
                ctx.runOnClient(mc -> Mod.set(MAP, "setFunnyMap", true));
                ctx.waitTicks(4);
                judge(ctx, failures, "cheat, Funny Map ON", true);
                ctx.runOnClient(mc -> Mod.set(MAP, "setFunnyMap", false));
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

    private static void judge(ClientGameTestContext ctx, List<String> failures, String tag, boolean funny) {
        List<int[]> every = SimMapItemTests.allFills(ctx);
        List<int[]> rooms = SimMapItemTests.roomFills(ctx);
        Integer blood = SimMapItemTests.colourAt(rooms, 3, 2);
        Integer andesite = SimMapItemTests.colourAt(rooms, 3, 1);
        Integer cell = SimMapItemTests.colourAt(rooms, 1, 2);
        int[] r = SimMapItemTests.roomRect(3, 2);
        int marks = 0;
        for (int[] f : every) {
            int w = f[2] - f[0];
            if (w < 10 * 2 && f[0] >= r[0] && f[2] <= r[2] && f[1] >= r[1] && f[3] <= r[3]) {
                marks++;
            }
        }
        System.out.println(String.format(Locale.ROOT, "[%s] %s: Blood %s, Andesite %s, Cell %s, %d small fill(s) on Blood",
                CASE, tag, SimMapItemTests.hexOrNone(blood), SimMapItemTests.hexOrNone(andesite),
                SimMapItemTests.hexOrNone(cell), marks));
        int want = funny ? SimMapItemTests.multiply(UNOPENED, 0.6f) : UNOPENED;
        if (blood == null || blood != want) {
            failures.add(tag + ": the unopened Blood Room is " + SimMapItemTests.hexOrNone(blood) + ", want "
                    + SimMapItemTests.hexOrNone(want) + (funny ? " (grayed like every unopened room)"
                    : " (the light unopened gray, undarkened)"));
        }
        if (!funny && marks != 0) {
            failures.add(tag + ": " + marks + " mark fill(s) drawn on the unopened Blood Room (a \"?\")");
        }
        int wantCell = funny ? SimMapItemTests.multiply(NORMAL, 0.6f) : SimMapItemTests.multiply(UNOPENED, 0.6f);
        if (cell == null || cell != wantCell) {
            failures.add(tag + ": an ordinary unopened room (Cell) is " + SimMapItemTests.hexOrNone(cell) + ", want "
                    + SimMapItemTests.hexOrNone(wantCell) + " - only the blood room changes");
        }
    }
}
