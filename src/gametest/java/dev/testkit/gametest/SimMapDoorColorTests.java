package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.Report;
import dev.testkit.harness.SuiteVerdict;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 1101-map-colors and 1101-map-extra-scale (mod doc-map-1101): killer560's to-do Doc, 2026-11-01, "PREDICTIVE MAP MODE":
 * "For funny map style the doors are too dark on the map, they should be the same color of the rooms. On the map as well
 * the wither doors should be black and the fairy door should be pink no orange highlight. That is the default. Make the
 * extra info overlay have a slider bar for the scale of it."
 *
 * <p>A sim floor drawn by hand - Entrance (1,1), New Trap (2,1), Andesite (3,1), Fairy (2,2), Cell (3,2) - and a
 * Hypixel-style map item showing the Entrance opened and the New Trap - Andesite door as a wither door (color 119), with a
 * coal block in that doorway so it is locked. Read from what the Dungeon Map HUD draws (cheat jar, Funny Map ON, Real Map
 * colors, Darken Unexplored 0):
 * <ul>
 *   <li>the Andesite - Cell door (both rooms unexplored) is the same color as the rooms either side of it;</li>
 *   <li>the New Trap - Fairy door is the Fairy room's pink, exactly as that room is drawn;</li>
 *   <li>the wither door is #000000 with no amber (#FFAA00) outline; Wither Door Outline ON draws the outline (the setting
 *       acts), and it is OFF on a fresh config.</li>
 * </ul>
 * 1101-map-extra-scale: Extra Info Scale 2 makes the HUD taller than 1 (the rows are drawn larger), and the value is
 * written to killer560smod-mapping.json and read back by {@code MappingConfig.load()}.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimMapDoorColorTests implements FabricClientGameTest {

    private static final String NAME = "1101-map-colors";
    private static final String R_COLORS = "1101-map-colors";
    private static final String R_SCALE = "1101-map-extra-scale";
    private static final String MAP = "livemap.LiveMapConfig";
    private static final String MAPPING = "mapping.MappingConfig";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final int ROOM_GRID = 6;
    private static final int AMBER = 0xFFFFAA00;
    private static final int NORMAL = 0xFF794600;

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
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.roomdatabase.RoomDatabase", "isReady"),
                1000);
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.RoomLibrary", "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.roomsim.RoomLibrary", "isReady"));
        List<AutoCloseable> restore = new ArrayList<>();
        SimMapItemTests.lastOrigin = null;
        try {
            boolean outlineDefault = ctx.computeOnClient(mc -> {
                try {
                    Object fresh = construct(Mod.cls(MAP));
                    return (Boolean) Mod.call(fresh, "isWitherDoorOutline");
                } catch (AssertionError | ReflectiveOperationException e) {
                    return Boolean.TRUE; // no such setting: the outline is always drawn
                }
            });
            ctx.runOnClient(mc -> {
                restore.add(0, Mod.with("hud.HudConfig", "AutoScale", false));
                restore.add(0, Mod.with(MAP, "Enabled", true));
                restore.add(0, Mod.with(MAP, "RoomLabels", 0));
                restore.add(0, Mod.with(MAP, "ColourByType", true));
                SimMapItemTests.tryWith(restore, MAP, "CustomCheckmarks", false);
                SimMapItemTests.tryWith(restore, MAP, "FunnyMap", Mod.isCheat());
                SimMapItemTests.tryWith(restore, MAP, "WitherDoorOutline", false);
                Mod.set(MAP, "setMapThemeName", "REAL");
                restore.add(0, Mod.with(MAP, "DarkenUnopened", 0f));
                restore.add(0, Mod.with("autopuzzles.AutoPuzzlesConfig", "AutoPuzzlesMasterEnabled", false));
                restore.add(0, Mod.with(MAPPING, "ExtraInfoEnabled", false));
                restore.add(0, Mod.with("livemap.predict.PredictiveRoomsConfig", "Enabled", false));
            });
            Map<Integer, String> placements = new LinkedHashMap<>();
            placements.put(1 * ROOM_GRID + 1, "Entrance");
            placements.put(1 * ROOM_GRID + 2, "New Trap");
            placements.put(1 * ROOM_GRID + 3, "Andesite");
            placements.put(2 * ROOM_GRID + 2, "Fairy");
            placements.put(2 * ROOM_GRID + 3, "Cell");
            SimMapItemTests.build(ctx, placements);
            ctx.waitTicks(20);

            int witherCell = cell(2, 1, true);   // New Trap | Andesite
            int fairyCell = cell(2, 1, false);   // New Trap over Fairy
            int plainCell = cell(3, 1, false);   // Andesite over Cell
            boolean[] doors = ctx.computeOnClient(mc -> {
                Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
                return new boolean[]{(Boolean) Mod.call(layout, "isDoor", witherCell),
                        (Boolean) Mod.call(layout, "isDoor", fairyCell), (Boolean) Mod.call(layout, "isDoor", plainCell)};
            });
            println("doors on the floor: New Trap|Andesite " + doors[0] + ", New Trap|Fairy " + doors[1]
                    + ", Andesite|Cell " + doors[2]);
            // A coal block in the New Trap | Andesite doorway: a locked wither door, as Hypixel builds one.
            BlockPos coal = ctx.computeOnClient(mc -> (BlockPos) ModUnderTest.staticCall(LAYOUT, "doorBlock",
                    new Class<?>[]{int.class}, new Object[]{witherCell}));
            AtomicReference<String> placed = new AtomicReference<>();
            ctx.runOnClient(mc -> {
                var server = mc.getSingleplayerServer();
                server.execute(() -> {
                    var sp = server.getPlayerList().getPlayers().get(0);
                    ServerLevel level = (ServerLevel) sp.level();
                    level.setBlockAndUpdate(coal, net.minecraft.world.level.block.Blocks.COAL_BLOCK.defaultBlockState());
                    placed.set("ok");
                });
            });
            ctx.waitFor(mc -> placed.get() != null, 200);
            // The coal's chunk first: the floor is built around the Entrance and the client is sent its chunks a batch at a
            // time, so on 26.2 the doorway's chunk (3 chunks off) was still an EmptyLevelChunk, which reads void_air, after
            // the 200 ticks below - it arrived after 494 (2026-11-03; with other clients running). A block read from an
            // unloaded chunk proves nothing, so wait for the chunk itself, then for the block.
            ctx.waitFor(mc -> !(mc.level.getChunkAt(coal) instanceof net.minecraft.world.level.chunk.EmptyLevelChunk), 2400);
            ctx.waitFor(mc -> !mc.level.getBlockState(coal).isAir(), 200);

            byte[] px = new byte[128 * 128];
            SimMapItemTests.room(px, 1, 1, 30);
            SimMapItemTests.door(px, 2, 1, 119);
            SimMapItemTests.giveMap(ctx, px);
            Boolean calibrated = false;
            for (int i = 0; i < 60 && !Boolean.TRUE.equals(calibrated); i++) {
                ctx.waitTicks(5);
                calibrated = SimMapItemTests.hook(ctx, "livemap.MapPainter", "mapItemCalibrated");
            }
            if (!Boolean.TRUE.equals(calibrated)) {
                row(R_COLORS, false, "premise: the painted dungeon map was never read");
                return;
            }
            ctx.waitTicks(10);
            colours(ctx, doors, outlineDefault);
            extraScale(ctx);
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
        println("PASS - every case passed");
    }

    private void colours(ClientGameTestContext ctx, boolean[] doors, boolean outlineDefault) {
        Report.caseStarted(R_COLORS);
        if (!Mod.isCheat()) {
            row(R_COLORS, true, "legit jar: Funny Map is cheat only; nothing to judge here (the outline default "
                    + "is " + outlineDefault + ")");
            return;
        }
        List<int[]> fills = SimMapItemTests.allFills(ctx);
        int[] o = SimMapItemTests.lastOrigin;
        if (o == null) {
            row(R_COLORS, false, "premise: the Entrance square was not found in what the HUD drew");
            return;
        }
        Integer andesite = at(fills, roomCentre(o, 3, 1), true);
        Integer cell = at(fills, roomCentre(o, 3, 2), true);
        Integer fairyRoom = at(fills, roomCentre(o, 2, 2), true);
        Integer plainDoor = at(fills, gapCentre(o, 3, 1, false), false);
        Integer fairyDoor = at(fills, gapCentre(o, 2, 1, false), false);
        Integer witherDoor = at(fills, gapCentre(o, 2, 1, true), false);
        int amber = amberNear(fills, gapCentre(o, 2, 1, true), o[2]);
        boolean hasSetting = ctx.computeOnClient(mc -> {
            try {
                Mod.set(MAP, "setWitherDoorOutline", true);
                return true;
            } catch (AssertionError oldJar) {
                return false;
            }
        });
        ctx.waitTicks(2);
        int amberOn = hasSetting ? amberNear(SimMapItemTests.allFills(ctx), gapCentre(o, 2, 1, true), o[2]) : -1;
        if (hasSetting) {
            ctx.runOnClient(mc -> Mod.set(MAP, "setWitherDoorOutline", false));
        }
        String detail = String.format(Locale.ROOT, "Andesite %s, Cell %s, their door %s; New Trap|Fairy door %s (want "
                        + "the Fairy room's %s); wither door %s (want #FF000000) with %d amber outline part(s), %d with Wither Door Outline "
                        + "ON; outline default %s; doors present %s/%s/%s",
                SimMapItemTests.hexOrNone(andesite), SimMapItemTests.hexOrNone(cell), SimMapItemTests.hexOrNone(plainDoor),
                SimMapItemTests.hexOrNone(fairyDoor), SimMapItemTests.hexOrNone(fairyRoom), SimMapItemTests.hexOrNone(witherDoor), amber, amberOn,
                outlineDefault, doors[0], doors[1], doors[2]);
        boolean plainOk = plainDoor != null && andesite != null && cell != null
                && (plainDoor.equals(andesite) || plainDoor.equals(cell));
        // Since mapcolor-1102 an unexplored Funny Map room keeps its own color (owner 2026-11-02: no darkening when the
        // run starts), so Andesite is plain Normal here; 1102-map-funny-same-color judges that.
        boolean premise = doors[0] && doors[1] && doors[2] && andesite != null;
        boolean pass = premise && plainOk && fairyDoor != null && fairyDoor.equals(fairyRoom) && pinkish(fairyDoor) && witherDoor != null
                && witherDoor == 0xFF000000 && amber == 0 && amberOn > 0 && !outlineDefault;
        row(R_COLORS, pass, (premise ? "" : "PREMISE (doors, Andesite drawn) not met; ") + detail);
    }

    private void extraScale(ClientGameTestContext ctx) {
        Report.caseStarted(R_SCALE);
        float[] got = ctx.computeOnClient(mc -> {
            Object mapping = Mod.cfg(MAPPING);
            Mod.call(mapping, "setExtraInfoEnabled", true);
            Object el = Mod.staticCall("hud.HudElementRegistry", "byId", "live_map");
            Mod.call(mapping, "setExtraInfoScale", 1f);
            int h1 = (Integer) Mod.call(el, "height");
            Mod.call(mapping, "setExtraInfoScale", 2f);
            mc.execute(() -> { });
            return new float[]{h1};
        });
        ctx.waitTicks(2); // the rows are built once a client tick
        int h2 = ctx.computeOnClient(mc -> (Integer) Mod.call(Mod.staticCall("hud.HudElementRegistry", "byId", "live_map"),
                "height"));
        // Persisted: saved, read back from disk.
        float back = ctx.computeOnClient(mc -> {
            Object mapping = Mod.cfg(MAPPING);
            Mod.call(mapping, "setExtraInfoScale", 1.5f);
            Mod.call(mapping, "save");
            Mod.staticCall(MAPPING, "load");
            return (Float) Mod.call(Mod.cfg(MAPPING), "getExtraInfoScale");
        });
        String file = "";
        try {
            java.nio.file.Path p = ModUnderTest.modConfig("killer560smod-mapping.json");
            file = Files.exists(p) ? Files.readString(p).replaceAll("\\s+", "") : "(no file at " + p + ")";
        } catch (Exception e) {
            file = "(unreadable: " + e + ")";
        }
        ctx.runOnClient(mc -> {
            Object mapping = Mod.cfg(MAPPING);
            Mod.call(mapping, "setExtraInfoScale", 1f);
            Mod.call(mapping, "setExtraInfoEnabled", false);
            Mod.call(mapping, "save");
        });
        boolean pass = h2 > got[0] && Math.abs(back - 1.5f) < 1e-4 && file.contains("\"extraInfoScale\":1.5");
        row(R_SCALE, pass, String.format(Locale.ROOT, "HUD height %d at Extra Info Scale 1, %d at 2; saved 1.5 read back "
                + "%.2f; file %s", (int) got[0], h2, back, file));
    }

    // ------------------------------------------------------------------------------------------------- geometry

    /** The 11x11 cell of the door east of ({@code east}) or below room (col, row). */
    private static int cell(int col, int row, boolean east) {
        int gx = col * 2 + (east ? 1 : 0);
        int gz = row * 2 + (east ? 0 : 1);
        return gz * 11 + gx;
    }

    private static int[] roomCentre(int[] o, int col, int row) {
        return new int[]{o[0] + (col - 1) * o[2] + o[3] / 2, o[1] + (row - 1) * o[2] + o[3] / 2};
    }

    /** The middle of the gap east of (or below) room (col, row). */
    private static int[] gapCentre(int[] o, int col, int row, boolean east) {
        int x0 = o[0] + (col - 1) * o[2];
        int y0 = o[1] + (row - 1) * o[2];
        int gap = o[2] - o[3];
        return east ? new int[]{x0 + o[3] + gap / 2, y0 + o[3] / 2} : new int[]{x0 + o[3] / 2, y0 + o[3] + gap / 2};
    }

    /** The color of the last fill covering point p (rooms: room-sized only; doors: any smaller fill). */
    private static Integer at(List<int[]> fills, int[] p, boolean room) {
        Integer c = null;
        for (int[] f : fills) {
            int w = f[2] - f[0];
            int h = f[3] - f[1];
            boolean roomSized = w >= 20 && h >= 20;
            if (room != roomSized || f[0] > p[0] || f[2] <= p[0] || f[1] > p[1] || f[3] <= p[1]) {
                continue;
            }
            if (!room && f[4] == AMBER) {
                continue; // an outline part is judged on its own
            }
            c = f[4];
        }
        return c;
    }

    /** Amber fills (outline parts) within a pitch of p. */
    private static int amberNear(List<int[]> fills, int[] p, int pitch) {
        int n = 0;
        for (int[] f : fills) {
            if (f[4] == AMBER && Math.abs((f[0] + f[2]) / 2 - p[0]) <= pitch / 2 && Math.abs((f[1] + f[3]) / 2 - p[1]) <= pitch / 2) {
                n++;
            }
        }
        return n;
    }

    /** Red and blue both well above green: the Fairy pink, darkened or not. */
    private static boolean pinkish(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return r > g + 20 && b > g + 20;
    }

    private static Object construct(Class<?> c) throws ReflectiveOperationException {
        var k = c.getDeclaredConstructor();
        k.setAccessible(true);
        return k.newInstance();
    }

    private void row(String name, boolean pass, String detail) {
        System.out.println("[" + name + "] " + (pass ? "PASS" : "FAIL") + " - " + detail);
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
