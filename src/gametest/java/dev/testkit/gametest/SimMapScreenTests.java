package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The map designer screen: does its room list clear its buttons, and does Generate draw a floor?
 *
 * <p>killer560 (2026-09-29): "the search rooms section goes too low and overlaps the top layer of things.
 * Before I can press play I should have to press generate and it shows on the left map what map it is going
 * to generate."
 *
 * <p>Both of those were fixed by reasoning about the layout arithmetic and compiling, which is not evidence.
 * Twice today a number worked out by hand turned out to be wrong in a way only running it showed - the crossing
 * distance in scenario 81 and the sentinel in snapPlayerTo - so this opens the real screen and measures it.
 *
 * <p>It asserts two things the screenshot he sent would have caught:
 *
 * <ul>
 *   <li>the bottom of the room list is above the TOP of every button on the screen, so nothing the list draws
 *       can land on a control;</li>
 *   <li>pressing Generate fills the grid and leaves a plan waiting, WITHOUT opening a world - the whole point
 *       of the change is that it previews rather than builds.</li>
 * </ul>
 */
public class SimMapScreenTests implements FabricClientGameTest {

    private static final String EDITOR = "com.killer560.hub.roomsim.SimMapEditorScreen";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";

    private static final String SOURCE_ROOMS =
            "C:/Users/Hunter/AppData/Roaming/PrismLauncher/instances/26.1.2 (Mod Only Test)"
                    + "/minecraft/config/killer560smod-rooms";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip("83-sim-map-screen")) {
            return;
        }
        ModUnderTest.require("killer560smod");
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff(
                "com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));

        if (copyDir(SOURCE_ROOMS, "killer560smod-rooms", ".json") < 20
                || copyDir(Path.of(SOURCE_ROOMS).resolveSibling("killer560smod-roomdata").toString(),
                        "killer560smod-roomdata", "") == 0) {
            System.out.println("[83-sim-map-screen] SKIPPED - needs his rooms and room database");
            return;
        }
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"));

        // Open the real screen, at the real window size.
        ctx.runOnClient(mc -> {
            try {
                Class<?> cls = Class.forName(EDITOR);
                var ctor = cls.getConstructor(net.minecraft.client.gui.screens.Screen.class);
                ctor.setAccessible(true);
                mc.setScreen((net.minecraft.client.gui.screens.Screen) ctor.newInstance((Object) null));
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("could not open the map designer", e);
            }
        });
        ctx.waitTicks(20);

        // ---- 1. nothing may be drawn on top of a control -----------------------------------------------
        // A real rectangle-intersection check, against the list's box AND the grid's box.
        //
        // The first version of this compared the list's bottom against the topmost widget anywhere on the
        // screen, which is the SEARCH BOX sitting above the list by design - so it "failed" at y 64 and named
        // the wrong thing. It also would not have caught what was actually wrong at that window size, which
        // was the GRID running into the settings row, because it never looked at the grid.
        String[] clash = new String[1];
        int[] seen = new int[1];
        ctx.runOnClient(mc -> {
            var screen = mc.screen;
            if (screen == null) {
                throw new AssertionError("the map designer did not open");
            }
            int listBottom = (Integer) callInt(screen, "listBottom");
            int listX = field(screen, "listX");
            int listW = field(screen, "listW");
            int panelY = field(screen, "panelY");
            int gridX = field(screen, "gridX");
            int gridY = field(screen, "gridY");
            int cell = field(screen, "cell");
            int grid = 6;
            int[][] boxes = {
                {listX, panelY + 66, listX + listW, listBottom},
                {gridX, gridY, gridX + cell * grid, gridY + cell * grid},
            };
            String[] names = {"the room list", "the grid"};
            for (var child : screen.children()) {
                if (!(child instanceof net.minecraft.client.gui.components.AbstractWidget w)) {
                    continue;
                }
                seen[0]++;
                for (int i = 0; i < boxes.length && clash[0] == null; i++) {
                    int[] r = boxes[i];
                    boolean overlaps = w.getX() < r[2] && w.getX() + w.getWidth() > r[0]
                            && w.getY() < r[3] && w.getY() + w.getHeight() > r[1];
                    if (overlaps) {
                        clash[0] = names[i] + " (x " + r[0] + ".." + r[2] + ", y " + r[1] + ".." + r[3]
                                + ") is drawn over a control at x " + w.getX() + ".." + (w.getX() + w.getWidth())
                                + ", y " + w.getY() + ".." + (w.getY() + w.getHeight());
                    }
                }
            }
        });
        System.out.println("[83-sim-map-screen] checked " + seen[0] + " control(s) against the list and the grid");
        if (seen[0] == 0) {
            throw new AssertionError("found no controls at all, so this measured nothing");
        }
        if (clash[0] != null) {
            throw new AssertionError(clash[0]);
        }

        // ---- 2. Generate previews, it does not build -----------------------------------------------------
        int[] before = new int[1];
        ctx.runOnClient(mc -> before[0] = placementCount(mc.screen));
        ctx.runOnClient(mc -> {
            try {
                var m = mc.screen.getClass().getDeclaredMethod("preview");
                m.setAccessible(true);
                m.invoke(mc.screen);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("no preview() on the designer", e);
            }
        });
        ctx.waitTicks(20);

        int[] after = new int[2];
        ctx.runOnClient(mc -> {
            after[0] = placementCount(mc.screen);
            try {
                var f = mc.screen.getClass().getDeclaredField("generated");
                f.setAccessible(true);
                after[1] = f.get(mc.screen) == null ? 0 : 1;
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("no generated field on the designer", e);
            }
        });
        boolean[] opened = new boolean[1];
        ctx.runOnClient(mc -> opened[0] = mc.level != null || mc.getSingleplayerServer() != null);

        System.out.println("[83-sim-map-screen] Generate: grid " + before[0] + " -> " + after[0]
                + " room(s), plan held " + (after[1] == 1) + ", world opened " + opened[0]);
        if (after[0] <= before[0]) {
            throw new AssertionError("Generate put nothing on the grid - it is meant to show the floor it "
                    + "would build");
        }
        if (after[1] != 1) {
            throw new AssertionError("Generate drew a floor but kept no plan, so Play would rebuild it from "
                    + "the grid at rotation 0 and lose every room's rotation");
        }
        if (opened[0]) {
            throw new AssertionError("Generate opened a world - it is meant to preview, and Play is what "
                    + "builds");
        }

        ctx.runOnClient(mc -> mc.execute(() ->
                mc.setScreen(new net.minecraft.client.gui.screens.TitleScreen())));
        ctx.waitFor(mc -> mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen);
        System.out.println("[83-sim-map-screen] PASS - the list clears the buttons and Generate previews");
    }

    private static int field(net.minecraft.client.gui.screens.Screen screen, String name) {
        try {
            var f = screen.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return (Integer) f.get(screen);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("no " + name + " on the designer - has it been renamed?", e);
        }
    }

    private static Object callInt(net.minecraft.client.gui.screens.Screen screen, String name) {
        try {
            var m = screen.getClass().getDeclaredMethod(name);
            m.setAccessible(true);
            return m.invoke(screen);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("no " + name + "() on the designer", e);
        }
    }

    private static int placementCount(net.minecraft.client.gui.screens.Screen screen) {
        try {
            var f = screen.getClass().getDeclaredField("placements");
            f.setAccessible(true);
            return ((java.util.Map<?, ?>) f.get(screen)).size();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("no placements field on the designer", e);
        }
    }

    private static int copyDir(String from, String into, String suffix) {
        try {
            Path source = Path.of(from);
            if (!Files.isDirectory(source)) {
                return 0;
            }
            Path target = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve(into);
            Files.createDirectories(target);
            int n = 0;
            try (var s = Files.list(source)) {
                for (Path f : s.toList()) {
                    if (Files.isRegularFile(f) && f.toString().endsWith(suffix)) {
                        Files.copy(f, target.resolve(f.getFileName()),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        n++;
                    }
                }
            }
            return n;
        } catch (Exception e) {
            return 0;
        }
    }
}
