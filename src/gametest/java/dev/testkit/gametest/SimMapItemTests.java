package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 1015-sim-map-item-only (mod map-overhaul-1015): killer560, 2026-10-15: "For dungeon map by default it should only
 * display what is on the normal handheld map meaning rooms you have already opened. It should not be able to scan to see
 * what rooms there are. Funnymap the toggle should be in cheat version only and that means it scans for rooms." Also
 * "Darken Unexplored does not work" and "by default ... it uses hypixels sprites" for the checkmarks.
 *
 * <p>A five-room sim floor - Entrance (1,1), New Trap (2,1), Andesite (3,1), Cell (1,2), Blood (3,2) - all of it built
 * and loaded in the world, and published to the map. Then the player is given a Hypixel-style dungeon map in hotbar slot
 * 9 (a filled map whose pixels the case paints itself, the way NoammAddons and the mod read Hypixel's: room squares of
 * 16 at a 20-pixel pitch, the entrance's 16-pixel run of colour 30 for calibration) showing the Entrance opened, New Trap
 * opened and cleared (a white check, colour 34, through its centre) and Andesite as an unopened grey room (85) with a
 * black "?" (119). Cell and Blood are not on it.
 *
 * <p>Read from what the Dungeon Map HUD DRAWS (its render extracted into a render state, room-sized fills and their
 * colours):
 * <ul>
 *   <li>legit, and cheat with Funny Map OFF: exactly the three rooms the item shows are drawn; no Blood red and no Cell
 *       brown anywhere, though the world has both loaded;</li>
 *   <li>cheat with Funny Map ON: all five, Cell in its own colour darkened by Darken Unexplored and the unopened Blood in the
 *   greyed brown (Normal darkened, 1018-map-unopened);</li>
 *   <li>Darken Unexplored changes the drawn Andesite (and, with Funny Map, Cell) fill: 0 against 0.6 differ by exactly
 *       the multiply;</li>
 *   <li>Checkmarks: New Trap's mark is Hypixel's own, copied off the item (white pixels inside New Trap's square, the
 *       painter reports "hypixel-item"); with Custom Checkmark Sprite ON it is the mod's sprite ("mod", with its black
 *       outline).</li>
 * </ul>
 * Without the item (the sim's own case) all five are drawn, as before.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimMapItemTests implements FabricClientGameTest {

    static final String CASE = "1015-sim-map-item-only";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String MAP = "livemap.LiveMapConfig";
    private static final String PAINTER = "livemap.MapPainter";
    private static final int ROOM_GRID = 6;
    private static final int GRID = 11;
    private static final float S = 2f;
    private static final int X0 = 20;
    private static final int Y0 = 20;
    private static final int MAP_ID = 31337;

    private static final int BLOOD = 0xFFFF0000;
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
        try {
            ctx.runOnClient(mc -> {
                restore.add(0, Mod.with("hud.HudConfig", "AutoScale", false));
                restore.add(0, Mod.with(MAP, "Enabled", true));
                restore.add(0, Mod.with(MAP, "RoomLabels", 1));
                restore.add(0, Mod.with(MAP, "ColourByType", true));
                tryWith(restore, MAP, "CustomCheckmarks", false);
                tryWith(restore, MAP, "FunnyMap", false);
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
            build(ctx, placements);
            ctx.waitTicks(20);

            // ---- the sim's own case: no map item, every room drawn ----
            List<int[]> noItem = roomFills(ctx);
            int groups = noItem.size();
            println("floor " + ctx.computeOnClient(mc -> String.valueOf(Mod.staticCall("secrets.DungeonState", "getFloor")))
                    + ", " + groups + " room group(s); without a map item the HUD draws " + noItem.size() + " room(s)");
            if (groups != 5) {
                failures.add("the sim published " + groups + " rooms, not 5 - nothing below would mean anything");
                return;
            }

            // ---- the map item ----
            giveMap(ctx, paintMap());
            // A jar from before map-overhaul has no calibration hook and never reads a map in the sim: it gets the same
            // 15 s and is judged on what it draws.
            Boolean calibrated = false;
            for (int i = 0; i < 60 && !Boolean.TRUE.equals(calibrated); i++) {
                ctx.waitTicks(5);
                calibrated = hook(ctx, PAINTER, "mapItemCalibrated");
                if (calibrated == null) {
                    ctx.waitTicks(300);
                    break;
                }
            }
            println("dungeon map item in slot 9, calibrated: " + (calibrated == null ? "no hook (old jar)" : calibrated));
            if (Boolean.FALSE.equals(calibrated)) {
                failures.add("the painted dungeon map in slot 9 was never read (calibrated) - the case cannot judge the map");
                return;
            }
            ctx.waitTicks(10);
            judgeItemOnly(ctx, failures, cheat ? "cheat, Funny Map OFF" : "legit");

            // ---- Darken Unexplored reaches the drawn unopened room ----
            int[] dark = new int[2];
            for (int k = 0; k < 2; k++) {
                float d = k == 0 ? 0f : 0.6f;
                ctx.runOnClient(mc -> Mod.set(MAP, "setDarkenUnopened", d));
                Integer c = colourAt(roomFills(ctx), 3, 1);
                dark[k] = c == null ? 0 : c;
            }
            println(String.format(Locale.ROOT, "Andesite (unopened) drawn #%08X at Darken 0, #%08X at 0.6", dark[0], dark[1]));
            if (dark[0] != UNOPENED || dark[1] != multiply(UNOPENED, 0.4f)) {
                failures.add(String.format(Locale.ROOT, "Darken Unexplored does not darken the unopened room: #%08X at 0,"
                        + " #%08X at 0.6 (want #%08X and #%08X)", dark[0], dark[1], UNOPENED, multiply(UNOPENED, 0.4f)));
            }
            ctx.runOnClient(mc -> Mod.set(MAP, "setDarkenUnopened", 0.4f));

            // ---- Hypixel's own checkmark, then the mod's ----
            String[] marks = new String[2];
            int[] whites = new int[2];
            for (int k = 0; k < 2; k++) {
                boolean custom = k == 1;
                ctx.runOnClient(mc -> Mod.set(MAP, "setCustomCheckmarks", custom));
                List<int[]> fills = allFills(ctx);
                Object src = hook(ctx, PAINTER, "lastMarkSource");
                marks[k] = String.valueOf(src);
                int[] r = roomRect(2, 1);
                for (int[] f : fills) {
                    if (f[4] == 0xFFFFFFFF && f[0] >= r[0] && f[2] <= r[2] && f[1] >= r[1] && f[3] <= r[3]) {
                        whites[k]++;
                    }
                }
            }
            Object runsObj = hook(ctx, "livemap.MapMarks", "hypixelRunCount", 2 * 2 + 2 * 1 * GRID);
            int runs = runsObj == null ? 0 : (Integer) runsObj;
            println("New Trap's mark: '" + marks[0] + "' with " + whites[0] + " white fill(s) (" + runs
                    + " run(s) copied off the item); Custom Checkmark Sprite ON: '" + marks[1] + "'");
            if (!"hypixel-item".equals(marks[0]) || whites[0] == 0 || runs == 0) {
                failures.add("New Trap's checkmark is not Hypixel's own off the map item ('" + marks[0] + "', " + whites[0]
                        + " white fill(s), " + runs + " run(s))");
            }
            if (!"mod".equals(marks[1])) {
                failures.add("Custom Checkmark Sprite ON drew '" + marks[1] + "', not the mod's sprite");
            }
            ctx.runOnClient(mc -> Mod.set(MAP, "setCustomCheckmarks", false));

            // ---- Funny Map ----
            if (cheat) {
                ctx.runOnClient(mc -> Mod.set(MAP, "setFunnyMap", true));
                ctx.waitTicks(2);
                List<int[]> fills = roomFills(ctx);
                Integer cell = colourAt(fills, 1, 2);
                Integer blood = colourAt(fills, 3, 2);
                boolean itemOnly = Boolean.TRUE.equals(hook(ctx, PAINTER, "mapItemOnly"));
                println(String.format(Locale.ROOT, "cheat, Funny Map ON: %d room(s) drawn, map-item-only %s, Cell %s, "
                        + "Blood %s", fills.size(), itemOnly, hexOrNone(cell), hexOrNone(blood)));
                if (fills.size() != 5 || itemOnly) {
                    failures.add("Funny Map ON drew " + fills.size() + " room(s) (map-item-only " + itemOnly + "), want all 5");
                }
                // The unopened Blood Room is Funny Map's greyed brown, not its red darkened (killer560, 2026-10-18,
                // 1018-map-unopened-*): every other unexplored room keeps its own colour darkened.
                if (cell == null || cell != multiply(NORMAL, 0.6f) || blood == null || blood != multiply(NORMAL, 0.6f)) {
                    failures.add("Funny Map's unexplored rooms are not drawn in their own colour darkened by Darken "
                            + "Unexplored 0.4 (the Blood Room in the greyed brown): Cell " + hexOrNone(cell) + ", Blood "
                            + hexOrNone(blood));
                }
                ctx.runOnClient(mc -> Mod.set(MAP, "setDarkenUnopened", 0f));
                Integer cell0 = colourAt(roomFills(ctx), 1, 2);
                if (cell0 == null || cell0 != NORMAL) {
                    failures.add("Funny Map with Darken 0 draws Cell " + hexOrNone(cell0) + ", not its own #"
                            + String.format(Locale.ROOT, "%08X", NORMAL));
                }
                ctx.runOnClient(mc -> {
                    Mod.set(MAP, "setDarkenUnopened", 0.4f);
                    Mod.set(MAP, "setFunnyMap", false);
                });
                ctx.waitTicks(2);
                judgeItemOnly(ctx, failures, "cheat, Funny Map back OFF");
            } else {
                // A saved true from a cheat jar must change nothing on the legit one.
                ctx.runOnClient(mc -> Mod.set(MAP, "setFunnyMap", true));
                judgeItemOnly(ctx, failures, "legit with a saved Funny Map ON");
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
                        println("restore failed: " + e);
                    }
                }
            });
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "leave"));
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
        println(failures.isEmpty() ? "PASS" : "FAIL: " + String.join("; ", failures));
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " problem(s): " + String.join("; ", failures));
        }
    }

    /** Exactly Entrance, New Trap and Andesite drawn; no Blood red, no Cell brown. */
    private static void judgeItemOnly(ClientGameTestContext ctx, List<String> failures, String tag) {
        List<int[]> fills = roomFills(ctx);
        List<int[]> every = allFills(ctx);
        int red = 0;
        int brown = 0;
        for (int[] f : every) {
            red += f[4] == BLOOD || f[4] == multiply(BLOOD, 0.6f) ? 1 : 0;
        }
        Integer cell = colourAt(fills, 1, 2);
        Integer blood = colourAt(fills, 3, 2);
        brown += cell != null ? 1 : 0;
        Object itemOnly = hook(ctx, PAINTER, "mapItemOnly");
        Object revealed = hook(ctx, PAINTER, "revealedRoomCount");
        println(String.format(Locale.ROOT, "%s: %d room(s) drawn (revealed %d, map-item-only %s); Entrance %s, New Trap %s,"
                        + " Andesite %s, Cell %s, Blood %s; %d red fill(s)", tag, fills.size(), revealed, itemOnly,
                hexOrNone(colourAt(fills, 1, 1)), hexOrNone(colourAt(fills, 2, 1)), hexOrNone(colourAt(fills, 3, 1)),
                hexOrNone(cell), hexOrNone(blood), red));
        if (fills.size() != 3 || cell != null || blood != null || red > 0 || brown > 0) {
            failures.add(tag + ": the map drew " + fills.size() + " room(s) (Cell " + hexOrNone(cell) + ", Blood "
                    + hexOrNone(blood) + ", " + red + " red fill(s)) - it must draw only the 3 rooms the map item shows");
        }
        if (colourAt(fills, 1, 1) == null || colourAt(fills, 2, 1) == null || colourAt(fills, 3, 1) == null) {
            failures.add(tag + ": a room the map item shows was not drawn");
        }
    }

    // ---- the painted dungeon map ---------------------------------------------------------------------------------

    /** Hypixel's map bytes: rooms of 16 at a pitch of 20 from corner (5, 5); the colours NoammAddons decodes. */
    static byte[] paintMap() {
        byte[] px = new byte[128 * 128];
        room(px, 1, 1, 30);          // Entrance, opened
        room(px, 2, 1, 62);          // New Trap, opened...
        int[] t = origin(2, 1);
        int[][] check = {{4, 8}, {5, 9}, {6, 10}, {7, 9}, {8, 8}, {9, 7}, {10, 6}, {11, 5}, {5, 8}, {6, 9}, {7, 8}};
        for (int[] p : check) {      // ...and cleared: a white check through the room's centre (8, 8)
            set(px, t[0] + p[0], t[1] + p[1], 34);
        }
        room(px, 3, 1, 85);          // Andesite, unopened grey with a black "?"
        int[] a = origin(3, 1);
        int[][] q = {{6, 4}, {7, 4}, {8, 4}, {9, 4}, {10, 5}, {10, 6}, {9, 7}, {8, 8}, {8, 9}, {8, 11}};
        for (int[] p : q) {
            set(px, a[0] + p[0], a[1] + p[1], 119);
        }
        door(px, 1, 1, 63);          // Entrance - New Trap
        door(px, 2, 1, 85);          // New Trap - Andesite (unopened)
        return px;
    }

    static int[] origin(int col, int row) {
        return new int[]{5 + col * 20, 5 + row * 20};
    }

    static void room(byte[] px, int col, int row, int colour) {
        int[] o = origin(col, row);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                set(px, o[0] + x, o[1] + y, colour);
            }
        }
    }

    /** A door east of room (col, row): the 4-pixel gap, 6 pixels across the middle. */
    static void door(byte[] px, int col, int row, int colour) {
        int[] o = origin(col, row);
        for (int y = 5; y <= 10; y++) {
            for (int x = 16; x < 20; x++) {
                set(px, o[0] + x, o[1] + y, colour);
            }
        }
    }

    static void set(byte[] px, int x, int y, int colour) {
        px[y * 128 + x] = (byte) colour;
    }

    /** Puts a filled map with id {@link #MAP_ID} in hotbar slot 9 on both sides, its pixels {@code colours} client-side. */
    static void giveMap(ClientGameTestContext ctx, byte[] colours) {
        AtomicReference<String> done = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            MapId id = new MapId(MAP_ID);
            MapItemSavedData data = MapItemSavedData.createForClient((byte) 0, true, mc.level.dimension());
            System.arraycopy(colours, 0, data.colors, 0, colours.length);
            mc.level.overrideMapData(id, data);
            ItemStack stack = new ItemStack(Items.FILLED_MAP);
            stack.set(DataComponents.MAP_ID, id);
            mc.player.getInventory().setItem(8, stack.copy());
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(uuid);
                if (sp != null) {
                    sp.getInventory().setItem(8, stack.copy());
                }
                done.set("ok");
            });
        });
        ctx.waitFor(mc -> done.get() != null, 200);
        ctx.waitTicks(5);
    }

    /** {@code Mod.with}, skipped on a jar that has no such setting (one from before map-overhaul). */
    static void tryWith(List<AutoCloseable> restore, String cls, String property, Object value) {
        try {
            restore.add(0, Mod.with(cls, property, value));
        } catch (AssertionError missing) {
            println("no setting " + property + " on this jar");
        }
    }

    /** A mod test hook's answer, or null when the jar has no such hook (one from before map-overhaul). */
    @SuppressWarnings("unchecked")
    static <T> T hook(ClientGameTestContext ctx, String cls, String method, Object... args) {
        return ctx.computeOnClient(mc -> {
            try {
                return (T) Mod.staticCall(cls, method, args);
            } catch (AssertionError | RuntimeException missing) {
                return null;
            }
        });
    }

    // ---- what the HUD draws ---------------------------------------------------------------------------------------

    /** The room-sized fills (10 to 45 map units square: this floor is fitted about 2x): {x0, y0, x1, y1, argb}. */
    static List<int[]> roomFills(ClientGameTestContext ctx) {
        List<int[]> out = new ArrayList<>();
        for (int[] f : allFills(ctx)) {
            int w = f[2] - f[0];
            int h = f[3] - f[1];
            if (w >= 10 * S && h >= 10 * S && w < 45 * S && h < 45 * S) {
                out.add(f);
            }
        }
        return out;
    }

    /** The fill drawn on room (col, row)'s square, by the map's own fit of this floor, or null. */
    static Integer colourAt(List<int[]> rooms, int col, int row) {
        int[] r = roomRect(col, row);
        for (int[] f : rooms) {
            int cx = (f[0] + f[2]) / 2;
            int cy = (f[1] + f[3]) / 2;
            if (cx >= r[0] && cx < r[2] && cy >= r[1] && cy < r[3]) {
                return f[4];
            }
        }
        return null;
    }

    private static int[] lastRoomRects;
    static int[] lastOrigin;

    /** Room (col, row)'s rectangle in the extracted frame, from where the Entrance and New Trap were drawn. */
    static int[] roomRect(int col, int row) {
        // The floor is fitted to its rooms' box (cols 1..3, rows 1..2): the drawn Entrance square gives the origin and
        // New Trap's the pitch. Kept from the first frame that had both.
        int[] o = lastOrigin;
        if (o == null) {
            return new int[]{0, 0, 0, 0};
        }
        int pitch = o[2];
        int x0 = o[0] + (col - 1) * pitch;
        int y0 = o[1] + (row - 1) * pitch;
        return new int[]{x0 - 2, y0 - 2, x0 + o[3] + 2, y0 + o[3] + 2};
    }

    /** Every opaque fill the Dungeon Map HUD draws: {x0, y0, x1, y1, argb}. Also learns the floor's drawn grid. */
    static List<int[]> allFills(ClientGameTestContext ctx) {
        List<int[]> fills = ctx.computeOnClient(SimMapItemTests::extract);
        if (lastOrigin == null) {
            // Entrance is the top-left room: the smallest room-sized square; New Trap the next one to its right.
            int[] entrance = null;
            for (int[] f : fills) {
                int w = f[2] - f[0];
                if (w >= 10 * S && w < 45 * S && Math.abs(w - (f[3] - f[1])) <= 1
                        && (entrance == null || f[0] + f[1] < entrance[0] + entrance[1])) {
                    entrance = f;
                }
            }
            if (entrance != null) {
                int side = entrance[2] - entrance[0];
                int pitch = Math.round(side * 20f / 16f);
                lastOrigin = new int[]{entrance[0], entrance[1], pitch, side};
            }
        }
        return fills;
    }

    static List<int[]> extract(Minecraft mc) {
        Object element = Mod.staticCall("hud.HudElementRegistry", "byId", "live_map");
        GuiRenderState state = new GuiRenderState();
        GuiGraphicsExtractor g = new GuiGraphicsExtractor(mc, state, -1, -1);
        g.pose().pushMatrix();
        try {
            g.pose().translate(X0, Y0);
            g.pose().scale(S, S);
            Method render = Mod.cls("hud.HudElement").getMethod("render", GuiGraphicsExtractor.class, int.class, int.class);
            render.invoke(element, g, 0, 0);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("render of live_map threw: " + e.getCause(), e);
        } finally {
            g.pose().popMatrix();
        }
        List<int[]> out = new ArrayList<>();
        state.forEachElement(el -> {
            List<Object> parts = new ArrayList<>();
            if (el.getClass().getName().equals("com.killer560.hub.hud.GuiRects$Batch")) {
                try {
                    Method m = el.getClass().getDeclaredMethod("parts");
                    m.setAccessible(true);
                    parts.addAll(java.util.Arrays.asList((Object[]) m.invoke(el)));
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError("cannot read the parts of a GuiRects batch", e);
                }
            } else if (el.getClass().getSimpleName().contains("ColoredRectangle")) {
                parts.add(el);
            }
            for (Object p : parts) {
                ScreenRectangle b = ((net.minecraft.client.renderer.state.gui.ScreenArea) p).bounds();
                int col;
                try {
                    col = (Integer) p.getClass().getMethod("col1").invoke(p);
                } catch (ReflectiveOperationException e) {
                    continue;
                }
                if (b != null && (col >>> 24) != 0) {
                    out.add(new int[]{b.left(), b.top(), b.right(), b.bottom(), col});
                }
            }
        }, GuiRenderState.TraverseRange.ALL);
        return out;
    }

    static int multiply(int argb, float f) {
        int a = (argb >>> 24) & 0xFF;
        int r = Math.min(255, Math.round(((argb >> 16) & 0xFF) * f));
        int g = Math.min(255, Math.round(((argb >> 8) & 0xFF) * f));
        int b = Math.min(255, Math.round((argb & 0xFF) * f));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    static String hexOrNone(Integer c) {
        return c == null ? "none" : String.format(Locale.ROOT, "#%08X", c);
    }

    static void build(ClientGameTestContext ctx, Map<Integer, String> placements) {
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> ModUnderTest.staticCall(FLOOR_GEN, "buildExplicit",
                new Class<?>[]{Minecraft.class, Map.class}, new Object[]{mc, placements})));
        ctx.waitFor(mc -> mc.level != null, 2400);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitTicks(60);
    }

    private static void println(String s) {
        System.out.println("[" + CASE + "] " + s);
    }
}
