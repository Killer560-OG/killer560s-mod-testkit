package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 1018-map-unopened-* (mod todo-map-unopened-1018): killer560, 2026-10-18, with a screenshot of an F7 Funny Map whose
 * unopened rooms were dark grey. His rules: on Funny Map an unopened room is greyed in the Funny Map's brown style (its
 * own colour, Normal brown for a plain room, darkened by Darken Unexplored) until he opens it; on the regular map an
 * unopened Blood Room is greyed the same Funny way, but only when its blood door is known - without it, it is an ordinary
 * unopened room. Opened rooms are untouched.
 *
 * <p>The floor of 1015-sim-map-item-only / 1018-map-blood-unopened (Entrance, New Trap, Andesite, Cell, Blood), with a
 * Hypixel-style map item on which Andesite is still UNOPENED - so, as with the Gold room in his screenshot, the room the
 * blood door is reached from is unopened too - plus a map-only unopened room at (2, 2) that the sim never built, so the
 * world scan has not reached it. Read from the fills the Dungeon Map HUD draws.
 *
 * <p>{@link SimMapUnopenedFunnyTests} (cheat jar): Funny Map ON, Andesite, the map-only room and Blood must all be the
 * greyed brown. {@link SimMapUnopenedBloodTests}: Funny Map OFF, Blood with its red door = greyed brown and no "?",
 * Andesite = the ordinary darkened grey; then a second floor without the Blood Room, its cell painted with a plain
 * unopened door: the ordinary darkened grey.
 */
final class SimMapUnopenedTests {

    private static final String MAP = "livemap.LiveMapConfig";
    private static final int ROOM_GRID = 6;
    private static final float DARK = 0.6f; // 1 - Darken Unexplored 0.4

    private SimMapUnopenedTests() {
    }

    static void run(ClientGameTestContext ctx, String name, boolean funnyCase) {
        if (Scenario.skip(name)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        if (funnyCase && !Mod.isCheat()) {
            Scenario.skipped(name, "Funny Map is a cheat-jar setting");
            return;
        }
        LogTap.install();
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (SimMapTests.copyRoomsForOthers() < 20) {
            Scenario.skipped(name, "needs real room captures and room database");
            return;
        }
        ctx.runOnClient(mc -> Scenario.loadRoomDatabaseNow());
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.roomdatabase.RoomDatabase", "isReady"),
                1000);
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.RoomLibrary", "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.roomsim.RoomLibrary", "isReady"));

        List<AutoCloseable> restore = new ArrayList<>();
        List<String> failures = new ArrayList<>();
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
                    // a jar from before map-overhaul paints Real Map colours by default
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
            if (!giveAndCalibrate(ctx, paint(true), failures)) {
                return;
            }
            if (funnyCase) {
                ctx.runOnClient(mc -> Mod.set(MAP, "setFunnyMap", true));
                ctx.waitTicks(4);
                judgeFunny(ctx, failures);
                ctx.runOnClient(mc -> Mod.set(MAP, "setFunnyMap", false));
            } else {
                judgeRegular(ctx, failures, true);
                // The same cell with no blood door known: a floor without the Blood Room (so the world holds no red
                // door either), its cell painted on the map item as an unopened room behind a plain unopened door.
                placements.remove(2 * ROOM_GRID + 3);
                SimMapItemTests.lastOrigin = null;
                SimMapItemTests.build(ctx, placements);
                ctx.waitTicks(20);
                if (!giveAndCalibrate(ctx, paint(false), failures)) {
                    return;
                }
                judgeRegular(ctx, failures, false);
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
        System.out.println("[" + name + "] " + (failures.isEmpty() ? "PASS" : "FAIL: " + String.join("; ", failures)));
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " problem(s): " + String.join("; ", failures));
        }
    }

    /** The map item: 1015's Entrance / cleared New Trap / unopened Andesite with its "?", an unopened Cell under the
     *  Entrance, an unopened room in the Blood cell under Andesite (behind a red door when {@code bloodDoor}, else a plain
     *  unopened one), and a map-only unopened room at (2, 2) under New Trap that the sim never built. */
    private static byte[] paint(boolean bloodDoor) {
        byte[] px = SimMapItemTests.paintMap();
        SimMapItemTests.room(px, 1, 2, 85);
        below(px, 1, 1, 63);                           // Entrance - Cell
        SimMapItemTests.room(px, 3, 2, 85);
        below(px, 3, 1, bloodDoor ? 18 : 85);          // Andesite - Blood cell
        SimMapItemTests.room(px, 2, 2, 85);
        below(px, 2, 1, 85);                           // New Trap - the map-only room
        return px;
    }

    /** A door south of room (col, row): the 4-pixel gap, 6 pixels across the middle. */
    private static void below(byte[] px, int col, int row, int colour) {
        int[] o = SimMapItemTests.origin(col, row);
        for (int y = 16; y < 20; y++) {
            for (int x = 5; x <= 10; x++) {
                SimMapItemTests.set(px, o[0] + x, o[1] + y, colour);
            }
        }
    }

    private static boolean giveAndCalibrate(ClientGameTestContext ctx, byte[] px, List<String> failures) {
        SimMapItemTests.giveMap(ctx, px);
        Boolean calibrated = false;
        for (int i = 0; i < 60 && !Boolean.TRUE.equals(calibrated); i++) {
            ctx.waitTicks(5);
            calibrated = SimMapItemTests.hook(ctx, "livemap.MapPainter", "mapItemCalibrated");
        }
        if (!Boolean.TRUE.equals(calibrated)) {
            failures.add("the painted dungeon map was never read - the case cannot judge the map");
            return false;
        }
        ctx.waitTicks(10);
        return true;
    }

    private static int colour(ClientGameTestContext ctx, String getter) {
        return ctx.computeOnClient(mc -> (Integer) Mod.get(MAP, getter));
    }

    private static void expect(List<String> failures, String tag, String what, Integer got, int want, String why) {
        if (got == null || got != want) {
            failures.add(tag + ": " + what + " is " + SimMapItemTests.hexOrNone(got) + ", want "
                    + SimMapItemTests.hexOrNone(want) + " (" + why + ")");
        }
    }

    private static void judgeFunny(ClientGameTestContext ctx, List<String> failures) {
        String tag = "Funny Map ON";
        int brownGrey = SimMapItemTests.multiply(colour(ctx, "getColorNormal"), DARK);
        int trap = colour(ctx, "getColorTrap");
        List<int[]> rooms = SimMapItemTests.roomFills(ctx);
        Integer newTrap = SimMapItemTests.colourAt(rooms, 2, 1);
        Integer andesite = SimMapItemTests.colourAt(rooms, 3, 1);
        Integer mapOnly = SimMapItemTests.colourAt(rooms, 2, 2);
        Integer blood = SimMapItemTests.colourAt(rooms, 3, 2);
        Integer cell = SimMapItemTests.colourAt(rooms, 1, 2);
        System.out.println(String.format(Locale.ROOT,
                "[1018-map-unopened-funny] %s: New Trap %s, Andesite %s, map-only %s, Blood %s, Cell %s (brown grey %s)",
                tag, SimMapItemTests.hexOrNone(newTrap), SimMapItemTests.hexOrNone(andesite),
                SimMapItemTests.hexOrNone(mapOnly), SimMapItemTests.hexOrNone(blood), SimMapItemTests.hexOrNone(cell),
                SimMapItemTests.hexOrNone(brownGrey)));
        expect(failures, tag, "unopened Andesite (beside the blood door, like his Gold room)", andesite, brownGrey,
                "Funny Map's greyed brown, not dark grey");
        expect(failures, tag, "the unopened room the world scan has not reached", mapOnly, brownGrey,
                "Funny Map's greyed brown, not dark grey");
        expect(failures, tag, "the unopened Blood Room", blood, brownGrey, "Funny Map's greyed brown");
        expect(failures, tag, "unopened Cell", cell, brownGrey, "unchanged: its own brown darkened");
        expect(failures, tag, "opened New Trap", newTrap, trap, "an opened room keeps its own colour");
    }

    private static void judgeRegular(ClientGameTestContext ctx, List<String> failures, boolean bloodDoor) {
        String tag = (Mod.isCheat() ? "cheat, Funny Map OFF" : "legit") + (bloodDoor ? ", blood door known"
                : ", no blood door");
        int brownGrey = SimMapItemTests.multiply(colour(ctx, "getColorNormal"), DARK);
        int darkGrey = SimMapItemTests.multiply(colour(ctx, "getColorUnopened"), DARK);
        int trap = colour(ctx, "getColorTrap");
        List<int[]> every = SimMapItemTests.allFills(ctx);
        List<int[]> rooms = SimMapItemTests.roomFills(ctx);
        Integer newTrap = SimMapItemTests.colourAt(rooms, 2, 1);
        Integer andesite = SimMapItemTests.colourAt(rooms, 3, 1);
        Integer blood = SimMapItemTests.colourAt(rooms, 3, 2);
        Integer cell = SimMapItemTests.colourAt(rooms, 1, 2);
        int[] r = SimMapItemTests.roomRect(3, 2);
        int marks = 0;
        for (int[] f : every) {
            int w = f[2] - f[0];
            if (w < 10 * 2 && f[0] >= r[0] && f[2] <= r[2] && f[1] >= r[1] && f[3] <= r[3]) {
                marks++;
            }
        }
        System.out.println(String.format(Locale.ROOT,
                "[1018-map-unopened-blood] %s: New Trap %s, Andesite %s, Blood cell %s, Cell %s, %d small fill(s) on the"
                        + " Blood cell (brown grey %s, dark grey %s)",
                tag, SimMapItemTests.hexOrNone(newTrap), SimMapItemTests.hexOrNone(andesite),
                SimMapItemTests.hexOrNone(blood), SimMapItemTests.hexOrNone(cell), marks,
                SimMapItemTests.hexOrNone(brownGrey), SimMapItemTests.hexOrNone(darkGrey)));
        if (bloodDoor) {
            expect(failures, tag, "the unopened Blood Room behind its red door", blood, brownGrey,
                    "Funny Map's greyed brown");
            if (marks != 0) {
                failures.add(tag + ": " + marks + " mark fill(s) drawn on the unopened Blood Room (a \"?\")");
            }
            expect(failures, tag, "unopened Andesite, the room the blood door is reached from", andesite, darkGrey,
                    "an ordinary unopened room - only the dead end behind the red door is the Blood Room");
        } else {
            expect(failures, tag, "the unopened room in the Blood cell with no blood door known", blood, darkGrey,
                    "an ordinary unopened room");
        }
        expect(failures, tag, "unopened Cell", cell, darkGrey, "unchanged: the ordinary darkened grey");
        expect(failures, tag, "opened New Trap", newTrap, trap, "an opened room keeps its own colour");
    }
}
