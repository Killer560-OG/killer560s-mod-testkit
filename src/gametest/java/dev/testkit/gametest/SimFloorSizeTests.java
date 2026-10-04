package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Build every floor size for real and land in it.
 *
 * <p>killer560 (2026-09-29): "make sure stuff like all of the different floor size work and then loading into
 * the game that it all still works".
 *
 * <p>Scenario 73 plans a hundred floors without a world, which is the cheap way to check the LAYOUT rules. It
 * cannot catch anything that only goes wrong once blocks are placed: an altitude that puts the floor through
 * the build limit, a spawn that lands on a roof, a build that never finishes. This one opens a world per floor
 * size and asks the questions that need one.
 *
 * <p>Per floor size it asserts the build finishes, the player ends up STANDING somewhere solid inside the
 * floor's own altitude band, and that he is inside a room rather than on top of one - which is precisely the
 * bug that shipped this morning, where a negative-y sentinel made {@code snapPlayerTo} fall back to the build
 * limit and drop him onto the entrance's roof.
 */
public class SimFloorSizeTests implements FabricClientGameTest {

    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String BUILD_QUEUE = "com.killer560.hub.roomsim.SimBuildQueue";
    private static final String ALTITUDE = "com.killer560.hub.roomsim.SimAltitude";
    private static final String LIVE_MAP = "com.killer560.hub.livemap.LiveMapFeature";

    private static final String SOURCE_ROOMS =
            ModUnderTest.instanceConfig("C:/Users/Hunter/AppData/Roaming/PrismLauncher/instances/26.1.2 (Mod Only Test)"
                    + "/minecraft/config", "killer560smod-rooms");

    /**
     * Which sizes to actually build.
     *
     * <p>Not all eight. Each one opens a world and pastes a couple of million blocks, and scenario 73 already
     * covers the layout rules for every size without a world. These four are the ones whose ALTITUDE and
     * extent differ most: the smallest, two in the middle, and the largest.
     */
    private static final String[] FLOORS = {"ENTRANCE", "F3", "F6", "F7"};

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip("84-sim-floor-sizes")) {
            return;
        }
        ModUnderTest.require("killer560smod");
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff(
                "com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));

        // The room library ships in the jar now, so nothing needs copying in. The room DATABASE does not.
        if (copyDir(Path.of(SOURCE_ROOMS).resolveSibling("killer560smod-roomdata").toString(),
                "killer560smod-roomdata", "") == 0) {
            System.out.println("[84-sim-floor-sizes] SKIPPED - needs his room database");
            return;
        }
        ctx.runOnClient(mc -> ModUnderTest.staticCall(
                "com.killer560.hub.roomdatabase.RoomDatabase", "ensureLoading"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(
                "com.killer560.hub.roomdatabase.RoomDatabase", "isReady"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"));

        List<String> failures = new ArrayList<>();
        for (String floorName : FLOORS) {
            try {
                buildAndCheck(ctx, floorName, failures);
            } catch (AssertionError e) {
                failures.add(floorName + ": " + e.getMessage());
            } finally {
                leave(ctx);
            }
        }

        if (!failures.isEmpty()) {
            StringBuilder sb = new StringBuilder(failures.size() + " floor size(s) failed:");
            for (String f : failures) {
                sb.append("\n    ").append(f);
            }
            throw new AssertionError(sb.toString());
        }
        System.out.println("[84-sim-floor-sizes] PASS - every size built and dropped him inside a room");
    }

    private static void buildAndCheck(ClientGameTestContext ctx, String floorName, List<String> failures) {
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter",
                new Class<?>[]{String.class}, new Object[]{"gametest"}));
        long simBuildBefore = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> {
            Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", floorName);
            ModUnderTest.staticCall(FLOOR_GEN, "generate",
                    new Class<?>[]{Minecraft.class, floor.getClass(), int.class, int.class},
                    new Object[]{mc, floor, 3, 4});
        }));
        ctx.waitFor(mc -> mc.level != null);
        Scenario.awaitSimBuild(ctx, simBuildBefore);
        // Long enough for the drop and the landing snap to settle.
        ctx.waitTicks(100);

        double[] pos = new double[3];
        int[] band = new int[2];
        int[] cell = new int[1];
        boolean[] solidUnderfoot = new boolean[1];
        ctx.runOnClient(mc -> {
            if (mc.player != null) {
                pos[0] = mc.player.getX();
                pos[1] = mc.player.getY();
                pos[2] = mc.player.getZ();
                solidUnderfoot[0] = mc.player.onGround();
            }
            band[0] = (Integer) ModUnderTest.staticCall(ALTITUDE, "minWorldY");
            band[1] = (Integer) ModUnderTest.staticCall(ALTITUDE, "maxWorldY");
            cell[0] = (Integer) ModUnderTest.staticCall(LIVE_MAP, "currentRoomIndex");
        });

        // The room's NAME is a nicety, so it must never be able to fail the scenario. It threw for three of
        // the four sizes while the thing actually under test was fine.
        String room = "none";
        if (cell[0] >= 0) {
            Object[] out = new Object[1];
            ctx.runOnClient(mc -> {
                try {
                    Object layout = ModUnderTest.staticCall(
                            "com.killer560.hub.livemap.DungeonLayout", "capture");
                    out[0] = layout == null ? null : ModUnderTest.call(layout, "name",
                            new Class<?>[]{int.class}, new Object[]{cell[0]});
                } catch (RuntimeException | AssertionError e) {
                    out[0] = null;
                }
            });
            room = out[0] == null ? "unnamed" : String.valueOf(out[0]);
            // The sim's own index always knows, even before the live map has identified anything.
            Object[] simName = new Object[1];
            ctx.runOnClient(mc -> {
                try {
                    simName[0] = ModUnderTest.staticCall("com.killer560.hub.roomsim.SimRoomIndex",
                            "nameAtCell", new Class<?>[]{int.class}, new Object[]{cell[0]});
                } catch (RuntimeException | AssertionError e) {
                    simName[0] = null;
                }
            });
            if (simName[0] != null && !String.valueOf(simName[0]).isBlank()) {
                room = String.valueOf(simName[0]);
            }
        }

        // Falling and floating look identical in one sample. Take a second one a second later: if he has not
        // moved and is still not on the ground, he is stuck in the air; if he has dropped, he was mid-fall
        // and the first reading said nothing.
        // What is actually under his feet? "Not on ground" covers standing over a hole, floating above solid
        // floor, and being clipped inside a block, and those are three different bugs.
        // Read the column from the SERVER, not the client. `void_air` on the client only means that chunk
        // has not been sent yet, which in a gametest with a tiny render distance says nothing about whether
        // the floor is really there. The integrated server is authoritative and this is singleplayer.
        java.util.concurrent.atomic.AtomicReference<String> col =
                new java.util.concurrent.atomic.AtomicReference<>();
        ctx.runOnClient(mc -> {
            var sp = mc.getSingleplayerServer();
            if (sp == null || mc.player == null) {
                col.set("no singleplayer server");
                return;
            }
            int px = (int) Math.floor(mc.player.getX());
            int py = (int) Math.floor(mc.player.getY());
            int pz = (int) Math.floor(mc.player.getZ());
            sp.execute(() -> {
                StringBuilder sb = new StringBuilder();
                var level = sp.overworld();
                var p2 = new net.minecraft.core.BlockPos.MutableBlockPos();
                for (int dy = 2; dy >= -4; dy--) {
                    p2.set(px, py + dy, pz);
                    sb.append(dy).append('=').append(net.minecraft.core.registries.BuiltInRegistries.BLOCK
                            .getKey(level.getBlockState(p2).getBlock()).getPath()).append(' ');
                }
                col.set(sb.toString());
            });
        });
        ctx.waitFor(mc -> col.get() != null);
        String[] column = {col.get()};
        System.out.println("[84-sim-floor-sizes]   " + floorName + " column around his feet: " + column[0]);

        ctx.waitTicks(20);
        double[] later = new double[1];
        boolean[] groundLater = new boolean[1];
        ctx.runOnClient(mc -> {
            if (mc.player != null) {
                later[0] = mc.player.getY();
                groundLater[0] = mc.player.onGround();
            }
        });

        System.out.println(String.format(
                "[84-sim-floor-sizes] %-8s built, floor occupies y %d..%d, he is at %.1f, %.1f, %.1f "
                        + "in cell %d (\"%s\"), on ground %s -> y %.1f on ground %s a second later",
                floorName, band[0], band[1], pos[0], pos[1], pos[2], cell[0], room, solidUnderfoot[0],
                later[0], groundLater[0]));

        // Inside the band the floor was actually built into. Landing above it is the roof drop; below it is
        // the void.
        if (pos[1] < band[0] || pos[1] > band[1]) {
            failures.add(floorName + ": landed at y " + (int) pos[1] + ", outside the floor's own band "
                    + band[0] + ".." + band[1] + " - that is the roof or the void, not the green room");
            return;
        }
        if (cell[0] < 0) {
            failures.add(floorName + ": he is not inside any room on the map after the build");
            return;
        }
        // What holds him up is the SERVER's block under his feet, not the client's onGround flag. In a
        // gametest the render distance is tiny, so a client far from spawn reports void_air and onGround
        // false while the server has him standing on cobblestone - which is exactly what happened on two of
        // four sizes and read as a spawn bug.
        boolean solid = column[0] != null && !column[0].contains("-1=air")
                && !column[0].contains("-1=void_air") && !column[0].contains("no singleplayer");
        if (!solid) {
            failures.add(floorName + ": nothing solid under his feet - the column is " + column[0]);
        }
        if (later[0] < band[0]) {
            failures.add(floorName + ": fell out of the floor's band, y " + (int) later[0]);
        }
        // He should start in the green room, which is the whole point of the landing snap.
        if (!"Entrance".equalsIgnoreCase(room)) {
            failures.add(floorName + ": landed in \"" + room + "\" rather than the Entrance");
        }
    }

    private static void leave(ClientGameTestContext ctx) {
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
                mc.setScreen(new net.minecraft.client.gui.screens.TitleScreen())));
        ctx.waitFor(mc -> mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen);
    }

    private static int copyDir(String from, String into, String suffix) {
        try {
            Path source = Path.of(from);
            if (!Files.isDirectory(source)) {
                return 0;
            }
            Path target = ModUnderTest.modConfig(into);
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
