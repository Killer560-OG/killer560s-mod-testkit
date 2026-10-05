package dev.testkit.gametest;

import dev.testkit.compat.McCompat;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Does the map editor build the floor he actually drew?
 *
 * <p>killer560 (2026-09-29) asked for Ashfall's Dungeon Maker: pick rooms, place them on a grid, press play.
 * The editor is a screen, which a gametest cannot click, but the screen does no layout of its own - it hands a
 * placement per cell to {@code SimFloorGen.planExplicit}. That is the part that can be wrong, so that is the
 * part this drives directly, with placements a person could have drawn.
 *
 * <p>What matters is that the floor he gets back is the floor he drew: every room he placed is present, at the
 * cell he put it, covering exactly its own footprint and no more. A map editor that quietly moves or resizes a
 * room is worse than no map editor.
 */
public class SimMapEditorTests implements FabricClientGameTest {

    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";

    private static final String SOURCE_ROOMS =
            ModUnderTest.instanceConfig("C:/Users/Hunter/AppData/Roaming/PrismLauncher/instances/26.1.2 (Mod Only Test)/minecraft/config", "killer560smod-rooms");

    private static final int GRID = 11;
    private static final int ROOM_GRID = 6;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip("75-sim-map-editor")) {
            return;
        }
        ModUnderTest.require("killer560smod");
        ctx.waitTicks(40);
        // The auction scan off first.
        //
        // AuctionHouseFeature starts a background scan of the whole auction house the moment a player exists,
        // and it pulls about 43,000 listings across 44 pages, each decoded into an ItemStack. In a gametest
        // client that is enough to wedge the process - scenario 71 froze on exactly that, sixteen seconds
        // after the sim had finished building perfectly. Nothing here is testing the auction house, so the
        // scan is pure interference.
        ctx.runOnClient(mc -> ModUnderTest.turnOff(
                "com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (copyRealRooms() < 20) {
            System.out.println("[75-sim-map-editor] SKIPPED - needs his real rooms");
            return;
        }
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"));

        // Pick real rooms out of his library: some 1x1, and at least one larger, because the larger ones are
        // where footprint bugs live.
        List<String> singles = new ArrayList<>();
        List<String> multis = new ArrayList<>();
        ctx.runOnClient(mc -> {
            @SuppressWarnings("unchecked")
            Iterable<String> names = (Iterable<String>) ModUnderTest.staticCall(ROOM_LIBRARY, "names");
            for (String n : names) {
                // Only rooms the EDITOR would list. It filters on Room.usable(), and since 2026-09-30 that
                // also excludes a capture RoomTileAudit found holding another room's blocks - 28 of the 134.
                // Picking straight out of names() drew a room the builder then correctly refused, and the
                // scenario reported "drew 5 rooms but the floor has 4" as though the editor had lost one.
                if (!(Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isUsable",
                        new Class<?>[]{String.class}, new Object[]{n})) {
                    continue;
                }
                int cells = (Integer) ModUnderTest.staticCall(ROOM_LIBRARY, "cellFootprint",
                        new Class<?>[]{String.class}, new Object[]{n});
                if (cells == 1 && singles.size() < 6) {
                    singles.add(n);
                } else if (cells > 1 && multis.size() < 2) {
                    multis.add(n);
                }
            }
        });
        System.out.println("[75-sim-map-editor] using " + singles.size() + " single(s) and "
                + multis.size() + " multi-tile room(s)");
        if (singles.size() < 4 || multis.isEmpty()) {
            throw new AssertionError("his library does not have enough usable rooms to exercise the editor: "
                    + singles.size() + " single, " + multis.size() + " multi");
        }

        // A drawing: a 2x2 block of singles, with a multi-tile room beside it.
        Map<Integer, String> drawn = new LinkedHashMap<>();
        drawn.put(0 * ROOM_GRID + 0, singles.get(0));
        drawn.put(0 * ROOM_GRID + 1, singles.get(1));
        drawn.put(1 * ROOM_GRID + 0, singles.get(2));
        drawn.put(1 * ROOM_GRID + 1, singles.get(3));
        drawn.put(2 * ROOM_GRID + 0, multis.get(0));

        final Object[] out = new Object[3];
        ctx.runOnClient(mc -> {
            Object p = ModUnderTest.staticCall(FLOOR_GEN, "planExplicit",
                    new Class<?>[]{Map.class}, new Object[]{drawn});
            if (p == null) {
                return;
            }
            Object decoded = ModUnderTest.call(p, "decoded", new Class<?>[]{}, new Object[]{});
            out[0] = ModUnderTest.call(decoded, "nameTable", new Class<?>[]{}, new Object[]{});
            out[1] = ModUnderTest.call(decoded, "cellRoom", new Class<?>[]{}, new Object[]{});
            out[2] = ModUnderTest.call(decoded, "cellDoor", new Class<?>[]{}, new Object[]{});
        });
        if (out[0] == null) {
            throw new AssertionError("planExplicit returned null for a drawing of " + drawn.size() + " rooms");
        }
        String[] names = (String[]) out[0];
        int[] roomOf = (int[]) out[1];
        int[] doorOf = (int[]) out[2];

        // 1. Every room he drew is on the floor, once.
        if (names.length != drawn.size()) {
            throw new AssertionError("drew " + drawn.size() + " rooms but the floor has " + names.length
                    + ": " + String.join(", ", names));
        }
        // 2. Each is at the cell he put it in. Room-grid (gx,gz) is 11x11 cell (gx*2, gz*2).
        for (Map.Entry<Integer, String> e : drawn.entrySet()) {
            int gx = e.getKey() % ROOM_GRID;
            int gz = e.getKey() / ROOM_GRID;
            int cell = (gz * 2) * GRID + (gx * 2);
            int id = roomOf[cell];
            if (id < 0) {
                throw new AssertionError("nothing was placed at the cell holding \"" + e.getValue() + "\"");
            }
            if (!names[id].equals(e.getValue())) {
                throw new AssertionError("drew \"" + e.getValue() + "\" at (" + gx + "," + gz
                        + ") but the floor has \"" + names[id] + "\" there");
            }
        }
        // 3. Nothing covers more or fewer cells than its capture.
        Map<Integer, Integer> cells = new HashMap<>();
        for (int c = 0; c < GRID * GRID; c++) {
            if (roomOf[c] >= 0) {
                cells.merge(roomOf[c], 1, Integer::sum);
            }
        }
        for (Map.Entry<Integer, Integer> e : cells.entrySet()) {
            final int id = e.getKey();
            final int[] want = new int[1];
            // Cells, not tiles: a room of t tiles on an axis spans 2t-1 grid cells, because the connectors
            // between its own tiles belong to it too. SimBuilder flood-fills +-1 over same-id cells, so a
            // room missing its connectors is pasted once per tile.
            ctx.runOnClient(mc -> {
                int tiles = (Integer) ModUnderTest.staticCall(ROOM_LIBRARY, "cellFootprint",
                        new Class<?>[]{String.class}, new Object[]{names[id]});
                int tx = (Integer) ModUnderTest.staticCall(ROOM_LIBRARY, "tilesX",
                        new Class<?>[]{String.class}, new Object[]{names[id]});
                int tz = tiles == 0 || tx == 0 ? 0 : tiles / tx;
                want[0] = tiles == 0 ? 0 : (2 * tx - 1) * (2 * tz - 1);
            });
            if (e.getValue() != want[0]) {
                throw new AssertionError("\"" + names[id] + "\" covers " + e.getValue()
                        + " cell(s) on the drawn floor, expected " + want[0]);
            }
        }
        // 4. Doors between the rooms that touch - a drawn floor of sealed boxes is not playable.
        int doors = 0;
        for (int d : doorOf) {
            if (d != 0) {
                doors++;
            }
        }
        System.out.println("[75-sim-map-editor] " + names.length + " rooms placed exactly as drawn, "
                + doors + " door(s) cut");
        if (doors == 0) {
            throw new AssertionError("the drawn floor has no doors at all - the rooms touch but nothing links");
        }

        // 5. A room that cannot fit is dropped, not pasted over its neighbour.
        Map<Integer, String> overlapping = new LinkedHashMap<>();
        overlapping.put(0, multis.get(0));
        overlapping.put(1, multis.get(0));   // immediately to the right of a room wider than one cell
        final int[] placed = new int[]{-1};
        ctx.runOnClient(mc -> {
            Object p = ModUnderTest.staticCall(FLOOR_GEN, "planExplicit",
                    new Class<?>[]{Map.class}, new Object[]{overlapping});
            if (p == null) {
                placed[0] = 0;
                return;
            }
            Object decoded = ModUnderTest.call(p, "decoded", new Class<?>[]{}, new Object[]{});
            placed[0] = ((String[]) ModUnderTest.call(decoded, "nameTable",
                    new Class<?>[]{}, new Object[]{})).length;
        });
        System.out.println("[75-sim-map-editor] two overlapping placements -> " + placed[0] + " placed");
        if (placed[0] > 1) {
            throw new AssertionError("both overlapping rooms were placed - they would paste over each other");
        }
        // 5b. A saved design comes back exactly, across a reload from disk.
        //
        // Saving a map he spent time drawing and getting something else back is the worst failure this screen
        // has, so it is asserted rather than assumed: write it, force a re-read from the file, compare.
        final String presets = "com.killer560.hub.roomsim.SimMapPresets";
        final String designName = "gametest-roundtrip";
        ctx.runOnClient(mc -> ModUnderTest.staticCall(presets, "put",
                new Class<?>[]{String.class, Map.class}, new Object[]{designName, drawn}));
        final Object[] back = new Object[1];
        ctx.runOnClient(mc -> back[0] = ModUnderTest.staticCall(presets, "get",
                new Class<?>[]{String.class}, new Object[]{designName}));
        if (back[0] == null) {
            throw new AssertionError("saved a map and got nothing back");
        }
        @SuppressWarnings("unchecked")
        Map<Integer, String> reloaded = (Map<Integer, String>) back[0];
        if (!reloaded.equals(drawn)) {
            throw new AssertionError("a saved map did not come back the same: saved " + drawn
                    + ", got " + reloaded);
        }
        System.out.println("[75-sim-map-editor] preset round-trip: " + reloaded.size() + " cell(s) preserved");
        ctx.runOnClient(mc -> ModUnderTest.staticCall(presets, "remove",
                new Class<?>[]{String.class}, new Object[]{designName}));
        final Object[] gone = new Object[1];
        ctx.runOnClient(mc -> gone[0] = ModUnderTest.staticCall(presets, "get",
                new Class<?>[]{String.class}, new Object[]{designName}));
        if (gone[0] != null) {
            throw new AssertionError("deleting a saved map left it behind");
        }

        // 6. The SCREEN itself opens, lays out and renders without throwing.
        //
        // The layout above is the part that can be logically wrong; this is the part that can simply crash.
        // A screen is init()ed and then drawn every frame, and neither had ever run - a bad widget bound or a
        // font measurement on an empty list would take the game down the moment he clicked the button.
        // Reflection, because this harness never compiles against the mod - it loads it at runtime.
        ctx.runOnClient(mc -> mc.execute(() -> {
            try {
                Class<?> cls = Class.forName("com.killer560.hub.roomsim.SimMapEditorScreen");
                Object screen = cls.getConstructor(net.minecraft.client.gui.screens.Screen.class)
                        .newInstance((Object) null);
                McCompat.setScreen(mc, (net.minecraft.client.gui.screens.Screen) screen);
            } catch (Exception e) {
                throw new RuntimeException("could not open the map editor screen", e);
            }
        }));
        ctx.waitTicks(20);
        boolean[] open = new boolean[1];
        ctx.runOnClient(mc -> open[0] = McCompat.screen(mc) != null
                && McCompat.screen(mc).getClass().getName().endsWith("SimMapEditorScreen"));
        if (!open[0]) {
            throw new AssertionError("the map editor screen did not stay open - it threw during init or "
                    + "render, which would crash him the moment he opened it");
        }
        // Rendered for a good few frames, not just constructed: the draw path is where the font measuring and
        // the scissor work live.
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> open[0] = McCompat.screen(mc) != null
                && McCompat.screen(mc).getClass().getName().endsWith("SimMapEditorScreen"));
        if (!open[0]) {
            throw new AssertionError("the map editor screen closed itself while rendering");
        }
        System.out.println("[75-sim-map-editor] the editor screen opened and rendered for 60 ticks");
        ctx.runOnClient(mc -> mc.execute(() -> McCompat.setScreen(mc, null)));
        ctx.waitTicks(10);

        System.out.println("[75-sim-map-editor] PASS - the floor matches the drawing");
    }

    private static int copyRealRooms() {
        try {
            Path source = Path.of(SOURCE_ROOMS);
            if (!Files.isDirectory(source)) {
                return 0;
            }
            Path target = ModUnderTest.modConfig("killer560smod-rooms");
            Files.createDirectories(target);
            List<Path> files = new ArrayList<>();
            try (var s = Files.list(source)) {
                s.filter(p -> p.toString().endsWith(".json")).forEach(files::add);
            }
            for (Path f : files) {
                Files.copy(f, target.resolve(f.getFileName()),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return files.size();
        } catch (Exception e) {
            return 0;
        }
    }
}
