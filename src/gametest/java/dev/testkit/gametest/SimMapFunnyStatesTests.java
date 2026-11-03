package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.Report;
import dev.testkit.harness.SuiteVerdict;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * 1995-sim-funny-map-room-states (mod dungeonmap-1103): killer560, 2026-11-03: "the funny map style currently has no way for
 * me to tell if a room has been entered but not cleared or never entered." On the Funny Map every scanned room was
 * promoted to "discovered", so a room never entered and one entered but not cleared drew the same (own color, no mark).
 *
 * <p>A five-room sim floor (Entrance, New Trap, Andesite, Fairy, Cell) with a Hypixel-style dungeon map in slot 9 that shows
 * the Entrance opened, New Trap opened and cleared (white check, color 34), Andesite opened with no check (color 63: entered
 * but not cleared), Cell as an unopened gray "?" room (85 + 119) and Fairy not on it at all (never entered). Funny Map ON
 * (cheat), Room Labels = Checkmarks, Darken Unexplored 0. Read from the Dungeon Map HUD's rendered rectangles: every small
 * rectangle inside a room's square is a piece of its mark.
 * <ul>
 *   <li>entered-not-cleared (Andesite): no mark rectangle at all;</li>
 *   <li>never entered (Fairy, not on the item; Cell, "?" on the item): a mark, dark ("?"), in both rooms;</li>
 *   <li>cleared (New Trap): a mark, white (the check), not the same set of colors as the "?" rooms;</li>
 *   <li>the three states' marks differ pairwise; the fill of a never-entered room is still its own color (the 2026-11-02
 *       rule), so the mark is the difference;</li>
 *   <li>Funny Map OFF on the cheat jar and on the legit jar: only the rooms the item shows are drawn, and Andesite still
 *       has no mark while New Trap's check and Cell's "?" are there (nothing changed for the normal map).</li>
 * </ul>
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimMapFunnyStatesTests implements FabricClientGameTest {

    private static final String NAME = "1995-sim-funny-map-room-states";
    private static final String R_ENTERED = "1995-funny-entered-not-cleared-unmarked";
    private static final String R_NEVER = "1995-funny-never-entered-marked";
    private static final String R_CLEARED = "1995-funny-cleared-check";
    private static final String R_DIFFER = "1995-funny-three-states-differ";
    private static final String R_NORMAL = "1995-funny-off-map-unchanged";
    private static final String MAP = "livemap.LiveMapConfig";
    private static final String PAINTER = "livemap.MapPainter";
    private static final int ROOM_GRID = 6;
    private static final int NORMAL = 0xFF724318;

    private final List<String> all = new ArrayList<>();

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (SimMapTests.copyRoomsForOthers() < 20) {
            Scenario.skipped(NAME, "needs real room captures and room database");
            return;
        }
        ctx.runOnClient(mc -> Scenario.loadRoomDatabaseNow());
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.roomdatabase.RoomDatabase", "isReady"), 1000);
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.RoomLibrary", "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.roomsim.RoomLibrary", "isReady"));

        List<AutoCloseable> restore = new ArrayList<>();
        boolean cheat = Mod.isCheat();
        try {
            ctx.runOnClient(mc -> {
                restore.add(0, Mod.with("hud.HudConfig", "AutoScale", false));
                restore.add(0, Mod.with(MAP, "Enabled", true));
                restore.add(0, Mod.with(MAP, "RoomLabels", 1));
                restore.add(0, Mod.with(MAP, "ColourByType", true));
                restore.add(0, Mod.with(MAP, "DarkenUnopened", 0f));
                // Predictive Rooms paints a room it names from what he has seen of it: timing, and not the map under test.
                SimMapItemTests.tryWith(restore, "livemap.predict.PredictiveRoomsConfig", "Enabled", false);
                SimMapItemTests.tryWith(restore, "livemap.predict.PredictiveRoomsConfig", "SpecialGuess", false);
                SimMapItemTests.tryWith(restore, MAP, "CustomCheckmarks", false);
                SimMapItemTests.tryWith(restore, MAP, "FunnyMap", false);
                try {
                    Mod.set(MAP, "setMapThemeName", "REAL");
                } catch (AssertionError oldJar) {
                    // old jar
                }
                restore.add(0, Mod.with("autopuzzles.AutoPuzzlesConfig", "AutoPuzzlesMasterEnabled", false));
                restore.add(0, Mod.with("mapping.MappingConfig", "ExtraInfoEnabled", false));
            });
            Map<Integer, String> placements = new LinkedHashMap<>();
            placements.put(1 * ROOM_GRID + 1, "Entrance");
            placements.put(1 * ROOM_GRID + 2, "New Trap");
            placements.put(1 * ROOM_GRID + 3, "Andesite");
            placements.put(2 * ROOM_GRID + 2, "Fairy");
            placements.put(2 * ROOM_GRID + 3, "Cell");
            SimMapItemTests.lastOrigin = null;
            SimMapItemTests.build(ctx, placements);
            ctx.waitTicks(20);
            SimMapItemTests.allFills(ctx);

            SimMapItemTests.giveMap(ctx, paintMap());
            Boolean calibrated = false;
            for (int i = 0; i < 60 && !Boolean.TRUE.equals(calibrated); i++) {
                ctx.waitTicks(5);
                calibrated = SimMapItemTests.hook(ctx, PAINTER, "mapItemCalibrated");
            }
            println("dungeon map item calibrated: " + calibrated);
            if (!Boolean.TRUE.equals(calibrated)) {
                row(R_ENTERED, false, "premise: the painted map in slot 9 was never read");
                return;
            }
            ctx.waitTicks(10);

            if (cheat) {
                ctx.runOnClient(mc -> Mod.set(MAP, "setFunnyMap", true));
                ctx.waitTicks(3);
                Object itemOnly = SimMapItemTests.hook(ctx, PAINTER, "mapItemOnly");
                List<int[]> fills = SimMapItemTests.allFills(ctx);
                States s = read(fills);
                List<int[]> rf = SimMapItemTests.roomFills(ctx);
                String all5 = "funny: map-item-only " + itemOnly + ", " + drawnRooms(rf) + " of the 5 rooms drawn ("
                        + rf.size() + " room-sized fills); marks " + s;
                println(all5);
                boolean five = Boolean.FALSE.equals(itemOnly) && drawnRooms(rf) == 5;
                row(R_ENTERED, five && s.andesite.isEmpty(), "Andesite (entered, not cleared) draws no mark: " + s.andesite
                        + " [premise: all 5 rooms drawn " + five + "]");
                row(R_NEVER, five && !s.fairy.isEmpty() && !s.cell.isEmpty() && dark(s.fairy) && dark(s.cell),
                        "Fairy (not on the item) mark " + s.fairy + ", Cell ('?' on the item) mark " + s.cell
                                + " - both must be dark question marks");
                row(R_CLEARED, five && !s.newTrap.isEmpty() && s.newTrap.contains(0xFFFFFFFF),
                        "New Trap (cleared) mark " + s.newTrap + " must hold white");
                boolean differ = s.andesite.isEmpty() && !s.newTrap.equals(s.fairy) && !s.newTrap.isEmpty()
                        && !s.fairy.isEmpty();
                Integer fairyFill = SimMapItemTests.colourAt(SimMapItemTests.roomFills(ctx), 2, 2);
                Integer andFill = SimMapItemTests.colourAt(SimMapItemTests.roomFills(ctx), 3, 1);
                row(R_DIFFER, five && differ && fairyFill != null && andFill != null,
                        "entered " + s.andesite + " / never " + s.fairy + " / cleared " + s.newTrap + "; fills never "
                                + SimMapItemTests.hexOrNone(fairyFill) + ", entered " + SimMapItemTests.hexOrNone(andFill)
                                + " (Darken Unexplored 0: both their own color)");
                ctx.runOnClient(mc -> Mod.set(MAP, "setFunnyMap", false));
                ctx.waitTicks(3);
            }
            // The normal map (legit jar, or cheat with Funny Map off): only what the item shows, same marks as always.
            List<int[]> fills = SimMapItemTests.allFills(ctx);
            States s = read(fills);
            int drawn = drawnRooms(SimMapItemTests.roomFills(ctx));
            println("normal map: " + drawn + " room(s) drawn; marks " + s);
            row(R_NORMAL, drawn == 4 && s.fairy.isEmpty() && s.andesite.isEmpty() && !s.cell.isEmpty()
                            && !s.newTrap.isEmpty(),
                    (cheat ? "cheat, Funny Map off" : "legit") + ": " + drawn + " room(s) drawn (want the 4 the item shows); "
                            + "Fairy (not shown) " + s.fairy + ", Andesite " + s.andesite + ", Cell " + s.cell
                            + ", New Trap " + s.newTrap);
        } catch (Throwable t) {
            all.add("scenario threw: " + t);
            t.printStackTrace(System.out);
        } finally {
            ctx.runOnClient(mc -> {
                McCompat.setScreen(mc, null);
                for (AutoCloseable r : restore) {
                    try {
                        r.close();
                    } catch (Exception e) {
                        println("restore failed: " + e);
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
        if (!all.isEmpty()) {
            throw new AssertionError(String.join(" | ", all));
        }
        println("PASS - every funny-map state case passed");
    }

    /** The colors of the small rectangles inside each room's square: the pieces of its mark. */
    private static final class States {
        TreeSet<Integer> entrance = new TreeSet<>();
        TreeSet<Integer> newTrap = new TreeSet<>();
        TreeSet<Integer> andesite = new TreeSet<>();
        TreeSet<Integer> fairy = new TreeSet<>();
        TreeSet<Integer> cell = new TreeSet<>();

        @Override
        public String toString() {
            return "Andesite " + hex(andesite) + ", Fairy " + hex(fairy) + ", Cell " + hex(cell) + ", New Trap " + hex(newTrap);
        }
    }

    private static String hex(TreeSet<Integer> colors) {
        StringBuilder sb = new StringBuilder("{");
        for (int c : colors) {
            sb.append(sb.length() == 1 ? "" : ",").append(String.format(Locale.ROOT, "%08X", c));
        }
        return sb.append("}").toString();
    }

    private static boolean dark(TreeSet<Integer> colors) {
        for (int c : colors) {
            int lum = (((c >> 16) & 0xFF) + ((c >> 8) & 0xFF) + (c & 0xFF)) / 3;
            if (lum > 90) {
                return false;
            }
        }
        return true;
    }

    private static States read(List<int[]> fills) {
        States s = new States();
        s.entrance = marks(fills, 1, 1);
        s.newTrap = marks(fills, 2, 1);
        s.andesite = marks(fills, 3, 1);
        s.fairy = marks(fills, 2, 2);
        s.cell = marks(fills, 3, 2);
        return s;
    }

    private static TreeSet<Integer> marks(List<int[]> fills, int col, int row) {
        TreeSet<Integer> out = new TreeSet<>();
        int[] r = SimMapItemTests.roomRect(col, row);
        int side = r[2] - r[0] - 4;
        for (int[] f : fills) {
            int cx = (f[0] + f[2]) / 2;
            int cy = (f[1] + f[3]) / 2;
            int w = f[2] - f[0];
            int h = f[3] - f[1];
            // inside the square and clearly smaller than the room's own fill
            if (cx >= r[0] && cx < r[2] && cy >= r[1] && cy < r[3] && w < side - 2 && h < side - 2 && w > 0 && h > 0) {
                out.add(f[4]);
            }
        }
        return out;
    }

    /** How many of the five rooms have a fill drawn on their square (other fills of room size, such as a door, do not count). */
    private static int drawnRooms(List<int[]> rf) {
        int n = 0;
        int[][] rooms = {{1, 1}, {2, 1}, {3, 1}, {2, 2}, {3, 2}};
        for (int[] r : rooms) {
            if (SimMapItemTests.colourAt(rf, r[0], r[1]) != null) {
                n++;
            }
        }
        return n;
    }

    private static byte[] paintMap() {
        byte[] px = SimMapItemTests.paintMap();
        // Reuse the 1015 item, then: Andesite (3,1) opened with no check (63), the "?" moves to Cell (3,2), Fairy (2,2) absent.
        SimMapItemTests.room(px, 3, 1, 63);
        SimMapItemTests.room(px, 3, 2, 85);
        int[] a = SimMapItemTests.origin(3, 2);
        int[][] q = {{6, 4}, {7, 4}, {8, 4}, {9, 4}, {10, 5}, {10, 6}, {9, 7}, {8, 8}, {8, 9}, {8, 11}};
        for (int[] p : q) {
            SimMapItemTests.set(px, a[0] + p[0], a[1] + p[1], 119);
        }
        SimMapItemTests.door(px, 2, 1, 63);
        return px;
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

    private static void println(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}
