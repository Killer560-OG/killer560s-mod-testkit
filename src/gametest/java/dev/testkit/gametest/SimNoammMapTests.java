package dev.testkit.gametest;

import com.mojang.authlib.GameProfile;
import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 1015-noamm-map (mod noamm-map-compare-1015): the legit Dungeon Map against NoammAddons' legit map, read from what the
 * HUD map draws and what it places, on a sim floor holding a Hypixel-style map item (the same five-room floor and map as
 * {@link SimMapItemTests}).
 * <ul>
 *   <li><b>unopened door</b>: the door the map item paints grey (85), New Trap - Andesite, is drawn in the unopened
 *       colour (darkened by Darken Unexplored like the room), as Hypixel and NoammAddons draw it; main drew New Trap's
 *       orange darkened;</li>
 *   <li><b>marker names</b>: four teammates in the tab list (Hypixel's "[n] Name (Class)" lines, ordered by their tab
 *       profile names) and four player markers on the map item in that order: each name lands on its own marker. Main
 *       paired them in the order of the client's player-info HASH SET;</li>
 *   <li><b>marker glide</b>: a marker that jumps three rooms is followed over several ticks, not in one step;</li>
 *   <li><b>calibration</b>: a map whose first 16-pixel green run is not the Entrance's top edge (a run starting 11
 *       pixels into a slot) still calibrates at the floor's corner (5,5) and draws its three rooms; main measured
 *       (16,5) from the run and drew the map shifted.</li>
 * </ul>
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimNoammMapTests implements FabricClientGameTest {

    static final String CASE = "1015-noamm-map";
    private static final String MAP = "livemap.LiveMapConfig";
    private static final String PAINTER = "livemap.MapPainter";
    private static final int ROOM_GRID = 6;
    private static final int UNOPENED = 0xFF414141;
    private static final String[] MATES = {"Alpha", "Bravo", "Charlie", "Delta"};
    /** Tab profile names: the tab sorts by these, so the party reads Alpha, Bravo, Charlie, Delta. */
    private static final String[] PROFILES = {"!B-a", "!B-b", "!B-c", "!B-d"};

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
        List<UUID> fakes = new ArrayList<>();
        try {
            ctx.runOnClient(mc -> {
                restore.add(0, Mod.with("hud.HudConfig", "AutoScale", false));
                restore.add(0, Mod.with(MAP, "Enabled", true));
                restore.add(0, Mod.with(MAP, "RoomLabels", 1));
                restore.add(0, Mod.with(MAP, "ColourByType", true));
                restore.add(0, Mod.with(MAP, "ShowTeammates", true));
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

            // ================================================================ a clean map first
            SimMapItemTests.lastOrigin = null;
            SimMapItemTests.build(ctx, placements);
            ctx.waitTicks(20);
            SimMapItemTests.giveMap(ctx, SimMapItemTests.paintMap());
            awaitCalibration(ctx);
            ctx.waitTicks(10);
            List<int[]> fills = SimMapItemTests.allFills(ctx);
            if (SimMapItemTests.lastOrigin == null) {
                failures.add("could not find the drawn Entrance - nothing below would mean anything");
                return;
            }

            // ---- unopened door ----
            Integer door = doorColour(fills);
            int want = SimMapItemTests.multiply(UNOPENED, 0.6f);
            println(String.format(Locale.ROOT, "door New Trap - Andesite (map byte 85): drawn %s, want #%08X",
                    SimMapItemTests.hexOrNone(door), want));
            if (door == null || door != want) {
                failures.add("the grey (85) door is drawn " + SimMapItemTests.hexOrNone(door) + ", not the unopened colour #"
                        + String.format(Locale.ROOT, "%08X", want));
            }

            // ---- marker names ----
            ctx.runOnClient(mc -> fakes.addAll(addTabMates(mc)));
            ctx.waitFor(mc -> partySize() >= MATES.length, 200);
            println("tab list read as party " + ctx.computeOnClient(mc ->
                    String.valueOf(Mod.staticCall("leapmenu.PartyTracker", "teammates"))));
            int[] markerMapX = {13, 33, 53, 73};
            ctx.runOnClient(mc -> setMarkers(mc, markerMapX));
            ctx.waitTicks(15);
            Map<String, double[]> at = mates(ctx);
            int right = 0;
            StringBuilder where = new StringBuilder();
            for (int k = 0; k < MATES.length; k++) {
                double wantX = (markerMapX[k] - 5) / 0.625 - 200;
                double[] p = at.get(MATES[k]);
                boolean ok = p != null && Math.abs(p[0] - wantX) < 2;
                right += ok ? 1 : 0;
                where.append(String.format(Locale.ROOT, " %s %s (marker %d at x %.1f)", MATES[k],
                        p == null ? "not placed" : String.format(Locale.ROOT, "at x %.1f", p[0]), k, wantX));
            }
            println(right + " of " + MATES.length + " teammate(s) on their own marker:" + where);
            if (right != MATES.length) {
                failures.add("only " + right + " of " + MATES.length + " teammates were drawn on their own map marker:" + where);
            }

            // ---- marker glide ----
            double fromX = (markerMapX[0] - 5) / 0.625 - 200;
            int[] moved = {73 + 20, 33, 53, 73};
            double toX = (moved[0] - 5) / 0.625 - 200;
            ctx.runOnClient(mc -> setMarkers(mc, moved));
            List<Double> samples = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                ctx.waitTicks(1);
                double[] p = mates(ctx).get(MATES[0]);
                samples.add(p == null ? Double.NaN : p[0]);
            }
            long between = samples.stream().filter(x -> x > fromX + 2 && x < toX - 2).count();
            double last = samples.get(samples.size() - 1);
            println(String.format(Locale.ROOT, "Alpha's marker moved x %.1f -> %.1f; per-tick x: %s (%d in between)",
                    fromX, toX, samples, between));
            if (between < 2 || Math.abs(last - toX) > 1) {
                failures.add("the moved marker did not glide (" + between + " sample(s) in between, ended at " + last + ")");
            }
            // ================================================================ calibration (its own floor build)
            // Slot 9 emptied first: with the clean map still there the floor rebuild would calibrate on it.
            ctx.runOnClient(SimNoammMapTests::clearSlot9);
            ctx.waitTicks(5);
            SimMapItemTests.lastOrigin = null;
            SimMapItemTests.build(ctx, placements);
            ctx.waitTicks(20);
            byte[] stray = SimMapItemTests.paintMap();
            for (int x = 16; x < 32; x++) {
                SimMapItemTests.set(stray, x, 5, 30);   // a green run 11 pixels into slot (0,0)'s top edge
            }
            SimMapItemTests.giveMap(ctx, stray);
            awaitCalibration(ctx);
            ctx.waitTicks(10);
            int[] cal = SimMapItemTests.hook(ctx, PAINTER, "mapCalibration");
            List<int[]> drawn = SimMapItemTests.roomFills(ctx);
            println(String.format(Locale.ROOT, "calibration with a stray green run: corner %s, %d room(s) drawn",
                    cal == null ? "no hook (old jar)" : cal[0] + "," + cal[1] + " size " + cal[2], drawn.size()));
            if (cal != null && (cal[0] != 5 || cal[1] != 5)) {
                failures.add("the map calibrated at corner (" + cal[0] + "," + cal[1] + "), not the floor's (5,5)");
            }
            if (drawn.size() != 3) {
                failures.add("with a stray green run the map drew " + drawn.size() + " room(s), not the 3 the item shows");
            }

        } catch (Throwable t) {
            failures.add("scenario threw: " + t);
            t.printStackTrace(System.out);
        } finally {
            ctx.runOnClient(mc -> {
                removeTabMates(mc, fakes);
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
                    mc.level.disconnect(Component.literal("scenario over"));
                    mc.disconnectWithSavingScreen();
                }
            }));
            ctx.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null);
            ctx.waitTicks(40);
            ctx.runOnClient(mc -> mc.execute(() ->
                    McCompat.setScreen(mc, new net.minecraft.client.gui.screens.TitleScreen())));
            ctx.waitFor(mc -> McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.TitleScreen);
        }
        println(failures.isEmpty() ? "PASS" : "FAIL: " + String.join("; ", failures));
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " problem(s): " + String.join("; ", failures));
        }
    }

    private static void clearSlot9(Minecraft mc) {
        mc.player.getInventory().setItem(8, net.minecraft.world.item.ItemStack.EMPTY);
        var server = mc.getSingleplayerServer();
        var uuid = mc.player.getUUID();
        server.execute(() -> {
            var sp = server.getPlayerList().getPlayer(uuid);
            if (sp != null) {
                sp.getInventory().setItem(8, net.minecraft.world.item.ItemStack.EMPTY);
            }
        });
    }

    private static void awaitCalibration(ClientGameTestContext ctx) {
        for (int i = 0; i < 60; i++) {
            ctx.waitTicks(5);
            Boolean c = SimMapItemTests.hook(ctx, PAINTER, "mapItemCalibrated");
            if (c == null || c) {
                return;
            }
        }
    }

    /** The door fill between New Trap (2,1) and Andesite (3,1): a narrow fill starting at New Trap's right edge. */
    private static Integer doorColour(List<int[]> fills) {
        int[] o = SimMapItemTests.lastOrigin;
        int side = o[3];
        int pitch = o[2];
        int trapRight = o[0] + pitch + side;     // New Trap is one pitch right of the Entrance
        for (int[] f : fills) {
            int w = f[2] - f[0];
            int h = f[3] - f[1];
            if (Math.abs(f[0] - trapRight) <= 1 && w > 0 && w <= side / 3 && h < side && f[1] > o[1] && f[3] < o[1] + side) {
                return f[4];
            }
        }
        return null;
    }

    private static int partySize() {
        Object list = Mod.staticCall("leapmenu.PartyTracker", "teammates");
        return list instanceof List<?> l ? l.size() : 0;
    }

    /** Four listed tab entries in Hypixel's dungeon form, ordered by their profile names. */
    private static List<UUID> addTabMates(Minecraft mc) {
        List<UUID> ids = new ArrayList<>();
        try {
            ClientPacketListener conn = mc.getConnection();
            Map<UUID, PlayerInfo> infos = field(conn, "playerInfoMap");
            Set<PlayerInfo> listed = field(conn, "listedPlayers");
            // Added in reverse so insertion order does not happen to be tab order either.
            for (int k = MATES.length - 1; k >= 0; k--) {
                UUID id = UUID.nameUUIDFromBytes(("noamm-mate-" + k).getBytes());
                PlayerInfo info = new PlayerInfo(new GameProfile(id, PROFILES[k]), false);
                info.setTabListDisplayName(Component.literal("[" + (k + 2) + "] [MVP+] " + MATES[k] + " (Mage 50)"));
                infos.put(id, info);
                listed.add(info);
                ids.add(id);
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot add tab entries: " + e, e);
        }
        return ids;
    }

    private static void removeTabMates(Minecraft mc, List<UUID> ids) {
        if (mc.getConnection() == null || ids.isEmpty()) {
            return;
        }
        try {
            Map<UUID, PlayerInfo> infos = field(mc.getConnection(), "playerInfoMap");
            Set<PlayerInfo> listed = field(mc.getConnection(), "listedPlayers");
            for (UUID id : ids) {
                PlayerInfo info = infos.remove(id);
                if (info != null) {
                    listed.remove(info);
                }
            }
        } catch (ReflectiveOperationException e) {
            println("could not remove tab entries: " + e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object owner, String name) throws ReflectiveOperationException {
        Field f = owner.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(owner);
    }

    /** The map item's decorations: a teammate marker per entry of {@code mapX} (row 1's centre line), then his frame. */
    private static void setMarkers(Minecraft mc, int[] mapX) {
        MapId id = mc.player.getInventory().getItem(8).get(DataComponents.MAP_ID);
        MapItemSavedData data = mc.level.getMapData(id);
        List<MapDecoration> list = new ArrayList<>();
        byte z = (byte) (2 * 33 - 128);
        for (int x : mapX) {
            list.add(new MapDecoration(MapDecorationTypes.PLAYER, (byte) (2 * x - 128), z, (byte) 0, Optional.empty()));
        }
        list.add(new MapDecoration(MapDecorationTypes.FRAME, (byte) (2 * 33 - 128), (byte) (2 * 53 - 128), (byte) 0,
                Optional.empty()));
        data.addClientSideDecorations(list);
    }

    /** What the map places for each teammate: name -> {x, z}. */
    private static Map<String, double[]> mates(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            Map<String, double[]> out = new LinkedHashMap<>();
            Object list = Mod.staticCall("livemap.LiveMapReads", "teammates", mc);
            for (Object m : (List<?>) list) {
                try {
                    Method name = m.getClass().getMethod("name");
                    Method x = m.getClass().getMethod("x");
                    Method z = m.getClass().getMethod("z");
                    name.setAccessible(true);
                    x.setAccessible(true);
                    z.setAccessible(true);
                    out.put((String) name.invoke(m), new double[]{(Double) x.invoke(m), (Double) z.invoke(m)});
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError("cannot read a teammate: " + e, e);
                }
            }
            return out;
        });
    }

    private static void println(String s) {
        System.out.println("[" + CASE + "] " + s);
    }
}
