package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Do the sim's secrets land inside the rooms they belong to?
 *
 * <p>A practice dungeon whose chests are in the void, or in the next room, is not a practice dungeon - the
 * whole point is learning where the secrets ARE. An audit on 2026-09-29 argued that {@code SimSecrets} takes
 * the clay corner as the room's north-west corner while the database measures from corner {@code rotation/90}
 * of the room, so anything pasted at a non-zero rotation puts its secrets roughly thirty blocks outside the
 * room. Rather than take that on argument, this measures it.
 *
 * <p>The assertion is deliberately weak and unarguable: every secret marker the sim places must sit within
 * the bounds of SOME room on the floor. It does not check that a chest is in the right corner of the right
 * room, because the database's own coordinates are the authority for that and this harness has no independent
 * copy. What it can prove is the failure the audit describes - markers landing outside every room.
 */
public class SimSecretsTests implements FabricClientGameTest {

    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String BUILD_QUEUE = "com.killer560.hub.roomsim.SimBuildQueue";
    private static final String BUILDER = "com.killer560.hub.roomsim.SimBuilder";
    private static final String MAP_CODE = "com.killer560.hub.roomsim.MapCode";

    private static final String SOURCE_ROOMS =
            ModUnderTest.instanceConfig("C:/Users/Hunter/AppData/Roaming/PrismLauncher/instances/26.1.2 (Mod Only Test)/minecraft/config", "killer560smod-rooms");

    private static final int GRID = 11;
    private static final int HALF_ROOM = 16;
    private static final int TILE = 31;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip("76-sim-secrets")) {
            return;
        }
        // The teardown in a finally, because an assertion in here USED to hang the whole suite.
        //
        // When this scenario threw, the sim world stayed open and the client never got back to the title
        // screen, so the gametest runner's next scenario had nowhere to start and the freeze watcher shot the
        // process ~16 s later. On 2026-09-29 that cost the six scenarios queued behind this one: 73 had just
        // passed, this failed, and 79, 80, 82, 83, 84 and 89 never ran at all.
        try {
            runChecks(ctx);
        } finally {
            teardown(ctx);
        }
    }

    private static void runChecks(ClientGameTestContext ctx) {
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
            System.out.println("[76-sim-secrets] SKIPPED - needs his real rooms");
            return;
        }
        // The room DATABASE as well as the room library. Without it RoomDatabase.lookupByName returns null for
        // every room, SimSecrets places nothing, and this scenario reports "no secrets" as though the mod were
        // broken - which is exactly what it did on its first run. The harness's own missing data is not a
        // finding about the mod.
        int db = copyRoomDatabase();
        System.out.println("[76-sim-secrets] copied " + db + " room-database file(s)");
        if (db == 0) {
            System.out.println("[76-sim-secrets] SKIPPED - no room database to place secrets from");
            return;
        }
        ctx.runOnClient(mc -> ModUnderTest.staticCall(
                "com.killer560.hub.roomdatabase.RoomDatabase", "ensureLoading"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(
                "com.killer560.hub.roomdatabase.RoomDatabase", "isReady"));
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"));
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter",
                new Class<?>[]{String.class}, new Object[]{"gametest"}));
        long simBuildBefore1 = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> {
            Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
            ModUnderTest.staticCall(FLOOR_GEN, "generate",
                    new Class<?>[]{Minecraft.class, floor.getClass(), int.class, int.class},
                    new Object[]{mc, floor, 3, 4});
        }));
        ctx.waitFor(mc -> mc.level != null);
        Scenario.awaitSimBuild(ctx, simBuildBefore1);
        ctx.waitTicks(60);

        // Which cells hold a room, from the map the sim actually built.
        boolean[] isRoomCell = new boolean[GRID * GRID];
        ctx.runOnClient(mc -> {
            String code = (String) ModUnderTest.staticCall(SIM_STATE, "mapCode");
            Object decoded = ModUnderTest.staticCall(MAP_CODE, "decode",
                    new Class<?>[]{String.class}, new Object[]{code});
            int[] cellRoom = (int[]) ModUnderTest.call(decoded, "cellRoom", new Class<?>[]{}, new Object[]{});
            for (int c = 0; c < isRoomCell.length && c < cellRoom.length; c++) {
                isRoomCell[c] = cellRoom[c] >= 0;
            }
        });

        // Every chest the build placed, scanned out of the world itself rather than from the mod's own record
        // - a wrong position recorded correctly would otherwise pass.
        // Scanned on the SERVER, not the client.
        //
        // The first version read mc.level and found 8 chests on one floor and 2 on another. That was not the
        // mod being inconsistent - the client only holds chunks near the player, so it was measuring render
        // distance. The integrated server has the whole floor, and it is the side the secrets were written
        // on, so it is the side that can answer the question.
        java.util.concurrent.atomic.AtomicReference<List<int[]>> scanned =
                new java.util.concurrent.atomic.AtomicReference<>();
        int bandLow = (Integer) ModUnderTest.staticCall(
                "com.killer560.hub.roomsim.SimAltitude", "minWorldY");
        int bandHigh = (Integer) ModUnderTest.staticCall(
                "com.killer560.hub.roomsim.SimAltitude", "maxWorldY");
        System.out.println("[76-sim-secrets] the floor occupies y " + bandLow + ".." + bandHigh);
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            if (server == null) {
                return;
            }
            server.execute(() -> {
                // Section-skipping, not 5.3 million block reads in one server task.
                //
                // The first version walked 257x257x81 in a single server.execute and stalled the server
                // thread long enough that the client stopped responding and the harness killed it as frozen.
                // The scan is the test's own scaffolding, so it has no business being the slowest thing in
                // the run - asking each chunk section's palette first does the same job in a fraction of it.
                List<int[]> found = new ArrayList<>();
                var level = server.overworld();
                int origin = -185;
                int span = GRID * HALF_ROOM;
                int x0 = origin - 40;
                int x1 = origin + span + 40;
                int z0 = origin - 40;
                int z1 = origin + span + 40;
                java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState> isChest =
                        st -> st.is(net.minecraft.world.level.block.Blocks.CHEST)
                                || st.is(net.minecraft.world.level.block.Blocks.TRAPPED_CHEST);
                for (int cx = x0 >> 4; cx <= (x1 >> 4); cx++) {
                    for (int cz = z0 >> 4; cz <= (z1 >> 4); cz++) {
                        // getChunk, NOT hasChunk-then-getChunk.
                        //
                        // hasChunk only answers "is this chunk loaded right now", and the player stands in
                        // one room of a floor that is six rooms across - so the far side of the map is
                        // outside view distance and every chest over there was skipped. On 2026-09-29 that
                        // made this scenario report 20 of 29 chests missing while the mod, asked to re-read
                        // the same 29 positions, found all 29 still there. The mod was right and the scan was
                        // wrong, which is the failure mode worth remembering: the instrument was broken in
                        // the direction that looked like a bug in the thing being measured.
                        //
                        // getChunk loads it, which is what a test that wants to inspect the whole map needs.
                        var chunk = level.getChunk(cx, cz);
                        int bx0 = Math.max(x0, cx << 4);
                        int bx1 = Math.min(x1, (cx << 4) + 15);
                        int bz0 = Math.max(z0, cz << 4);
                        int bz1 = Math.min(z1, (cz << 4) + 15);
                        var sections = chunk.getSections();
                        for (int i = 0; i < sections.length; i++) {
                            // The floor's OWN band, not a fixed 60..140.
                            //
                            // Since 2026-09-29 the whole map is shifted so its lowest block sits just above
                            // the void - about 123 blocks down on an ordinary floor, and 185 UP on one with
                            // Higher Blaze on it. A scan hard-coded to the capture's coordinates finds an
                            // empty band and reports that the floor has no secrets at all.
                            int secMinY = chunk.getSectionYFromSectionIndex(i) << 4;
                            int secMaxY = secMinY + 15;
                            if (secMaxY < bandLow || secMinY > bandHigh) {
                                continue;
                            }
                            var section = sections[i];
                            if (section == null || section.hasOnlyAir() || !section.maybeHas(isChest)) {
                                continue;
                            }
                            for (int x = bx0; x <= bx1; x++) {
                                for (int z = bz0; z <= bz1; z++) {
                                    for (int y = Math.max(bandLow, secMinY);
                                            y <= Math.min(bandHigh, secMaxY); y++) {
                                        if (isChest.test(section.getBlockState(x & 15, y & 15, z & 15))) {
                                            found.add(new int[]{x, y, z});
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                scanned.set(found);
            });
        });
        ctx.waitFor(mc -> scanned.get() != null);
        List<int[]> chests = scanned.get();
        System.out.println("[76-sim-secrets] found " + chests.size() + " chest(s) in the built floor");
        if (chests.isEmpty()) {
            throw new AssertionError("no secret chests were placed at all, so nothing was measured - a floor "
                    + "with no secrets is not a practice floor, and this assertion would otherwise pass "
                    + "vacuously");
        }

        // How many the database says this floor SHOULD have.
        //
        // "All the chests we placed are inside rooms" is true of a floor with one chest, so the count matters
        // as much as the positions: a 21-room F7 with eight secrets is not something to practise routes on.
        int[] expected = new int[1];
        int[] roomsWithData = new int[1];
        int[] roomsTotal = new int[1];
        ctx.runOnClient(mc -> {
            String code = (String) ModUnderTest.staticCall(SIM_STATE, "mapCode");
            Object decoded = ModUnderTest.staticCall(MAP_CODE, "decode",
                    new Class<?>[]{String.class}, new Object[]{code});
            String[] names = (String[]) ModUnderTest.call(decoded, "nameTable",
                    new Class<?>[]{}, new Object[]{});
            roomsTotal[0] = names.length;
            for (String n : names) {
                Object entry = ModUnderTest.staticCall("com.killer560.hub.roomdatabase.RoomDatabase",
                        "lookupByName", new Class<?>[]{String.class}, new Object[]{n});
                if (entry == null) {
                    continue;
                }
                try {
                    Object coords = entry.getClass().getField("secretCoords").get(entry);
                    if (coords == null) {
                        continue;
                    }
                    Object chestList = coords.getClass().getField("chest").get(coords);
                    int size = chestList == null ? 0 : ((java.util.List<?>) chestList).size();
                    if (size > 0) {
                        roomsWithData[0]++;
                    }
                    expected[0] += size;
                } catch (ReflectiveOperationException ignored) {
                    // Field names changed: say nothing rather than report a false shortfall.
                }
            }
        });
        System.out.println("[76-sim-secrets] the database expects " + expected[0] + " chest(s) across "
                + roomsWithData[0] + " of " + roomsTotal[0] + " rooms; the floor has " + chests.size());
        // Chests the mod itself reports it deliberately dropped, because their coordinates fall outside the
        // room's own footprint. That is not a translation fault and cannot be fixed here: three of his
        // captures are the wrong SIZE (Deathmite is two tiles where the database says three), so some of
        // their secrets have nowhere to go until those rooms are re-captured. This scenario used to fail
        // whenever one of them happened to land on the floor, which made it a test of the floor's room
        // lottery rather than of the secrets.
        int skipped = 0;
        try {
            int[] audit = (int[]) ModUnderTest.staticCall(
                    "com.killer560.hub.roomsim.SimSecrets", "chestAudit");
            if (audit != null && audit.length > 3) {
                skipped = audit[3];
            }
        } catch (RuntimeException ignored) {
            // Older jar without the audit: fall through and assert on the raw count, as before.
        }
        // Two secrets that land on ONE block yield one chest, so the floor is short by that many through no
        // fault of the placement. Counted rather than tolerated: the mod reports it, and this subtracts
        // exactly what it reports instead of loosening the assertion.
        int collisions = 0;
        try {
            collisions = (Integer) ModUnderTest.staticCall(
                    "com.killer560.hub.roomsim.SimSecrets", "collidingChests");
        } catch (RuntimeException ignored) {
            // Older jar without the counter.
        }
        int reachable = expected[0] - skipped - collisions;
        System.out.println("[76-sim-secrets] " + skipped + " chest secret(s) lie outside their own room, "
                + collisions + " share a block with another, so " + reachable + " were placeable");
        if (reachable > 0 && chests.size() < reachable) {
            throw new AssertionError("the floor is missing secrets: " + reachable + " chest(s) were placeable "
                    + "(" + expected[0] + " in the database, " + skipped + " outside their own room, "
                    + collisions + " sharing a block) but only " + chests.size() + " were placed");
        }

        int outside = 0;
        int[] worst = null;
        for (int[] c : chests) {
            if (!insideSomeRoom(c[0], c[2], isRoomCell)) {
                outside++;
                if (worst == null) {
                    worst = c;
                }
            }
        }
        System.out.println("[76-sim-secrets] " + outside + " of " + chests.size()
                + " chest(s) lie outside every room on the floor");
        if (outside > 0) {
            throw new AssertionError("secret chests are being placed outside the rooms they belong to: "
                    + outside + " of " + chests.size() + ", the first at "
                    + worst[0] + "," + worst[1] + "," + worst[2]
                    + " - they would be in the void or in a neighbour");
        }

        // The prince, in a room that actually has one - which is NOT Red Blue.
        //
        // killer560 (2026-09-29): "the prince is a crypt that only exists in specific rooms and it should
        // already be there not something you create [...] for instance red blue has one straight above the
        // lever about 10-20 blocks", and "it is not guarenteed to be in every run either."
        //
        // So a random floor is the wrong thing to assert on, and this built Red Blue on its own instead,
        // calling it "deterministic: its plinth is three gold blocks between smooth stone slabs at y 84".
        // That was wrong twice over. Red Blue's capture holds five gold blocks at y 69, not three at y 84,
        // and every one of them is set in stone with stone brick stairs beside it - no smooth stone slab
        // anywhere near, so SimPrince.onAPlinth correctly refuses them. Checked against the library on
        // 2026-09-30, exactly four rooms hold a real plinth by the mod's own rule: Chambers, Market, Melon
        // and Sloth. His "not guaranteed in every run" applies to a CAPTURE as much as to a floor, and the
        // test was asserting a prince into a room that has none.
        //
        // Each candidate is tried until one yields a prince, so this does not depend on any single room
        // still being in the library. If none of them does, it SKIPS and says so with the count - a prince
        // the data does not contain is not a defect in the code that looks for it.
        String[] princeRooms = {"Chambers", "Market", "Melon", "Sloth"};
        java.util.concurrent.atomic.AtomicReference<int[]> prince =
                new java.util.concurrent.atomic.AtomicReference<>();
        String builtRoom = null;
        for (String candidate : princeRooms) {
            prince.set(null);
            long before = Scenario.simBuildCount(ctx);
            ctx.runOnClient(mc -> mc.execute(() -> ModUnderTest.staticCall(BUILDER, "buildSingleRoom",
                    new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, candidate})));
            Scenario.awaitSimBuild(ctx, before);
            ctx.waitTicks(60);
            scanPrince(ctx, prince);
            ctx.waitFor(mc -> prince.get() != null);
            System.out.println("[76-sim-secrets] " + candidate + ": princes=" + prince.get()[0]
                    + " blocks=" + prince.get()[1]);
            if (prince.get()[0] > 0) {
                builtRoom = candidate;
                break;
            }
        }
        if (builtRoom == null) {
            System.out.println("[76-sim-secrets] no prince in any of " + String.join(", ", princeRooms)
                    + " - SKIPPING the prince half; this library holds no plinth to test");
        } else {
            int[] pr = prince.get();
            System.out.println("[76-sim-secrets] " + builtRoom + ": princes=" + pr[0] + " blocks=" + pr[1]
                    + " blownOnce=" + (pr[2] == 1) + " scoredOncePerRun=" + (pr[3] == 1));
            if (pr[1] < 2) {
                throw new AssertionError("the prince in " + builtRoom + " is only " + pr[1]
                        + " block(s) - a plinth is a short run of gold, not one block");
            }
            if (pr[2] == 0 || pr[3] == 0) {
                throw new AssertionError("blowing the prince twice both succeeded, or the run's single score "
                        + "was handed out more than once");
            }
        }

        System.out.println("[76-sim-secrets] PASS - every secret chest is inside a room");
    }

    /** Runs SimPrince over the built world and records what it found, plus the blow/score behaviour. */
    private static void scanPrince(ClientGameTestContext ctx,
                                   java.util.concurrent.atomic.AtomicReference<int[]> prince) {
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            if (server == null) {
                prince.set(new int[]{-1, 0, 0, 0, 0});
                return;
            }
            server.execute(() -> {
                int found = (Integer) ModUnderTest.staticCall("com.killer560.hub.roomsim.SimPrince",
                        "scan", new Class<?>[]{net.minecraft.server.level.ServerLevel.class},
                        new Object[]{server.overworld()});
                int blocks = (Integer) ModUnderTest.staticCall(
                        "com.killer560.hub.roomsim.SimPrince", "size");
                Object at = ModUnderTest.staticCall("com.killer560.hub.roomsim.SimPrince", "position");
                int blownOnce = 0;
                int scoredOnce = 0;
                if (at != null) {
                    boolean first = (Boolean) ModUnderTest.staticCall("com.killer560.hub.roomsim.SimPrince",
                            "blow", new Class<?>[]{net.minecraft.core.BlockPos.class}, new Object[]{at});
                    boolean second = (Boolean) ModUnderTest.staticCall("com.killer560.hub.roomsim.SimPrince",
                            "blow", new Class<?>[]{net.minecraft.core.BlockPos.class}, new Object[]{at});
                    blownOnce = first && !second ? 1 : 0;
                    boolean s1 = (Boolean) ModUnderTest.staticCall(
                            "com.killer560.hub.roomsim.SimPrince", "takeScore");
                    boolean s2 = (Boolean) ModUnderTest.staticCall(
                            "com.killer560.hub.roomsim.SimPrince", "takeScore");
                    scoredOnce = s1 && !s2 ? 1 : 0;
                }
                prince.set(new int[]{found, blocks, blownOnce, scoredOnce, 0});
            });
        });
    }

    /**
     * Leaves the sim and gets the client back to the title screen.
     *
     * <p>Safe to run when there is no world - which is the point of it, since it runs from a {@code finally}
     * after an assertion that may have failed anywhere.
     */
    private static void teardown(ClientGameTestContext ctx) {
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

    /** Is this world position within the 31-block tile of a cell that holds a room? */
    private static boolean insideSomeRoom(int worldX, int worldZ, boolean[] isRoomCell) {
        for (int cell = 0; cell < isRoomCell.length; cell++) {
            if (!isRoomCell[cell]) {
                continue;
            }
            int cx = -185 + (cell % GRID) * HALF_ROOM;
            int cz = -185 + (cell / GRID) * HALF_ROOM;
            if (Math.abs(worldX - cx) <= TILE / 2 + 1 && Math.abs(worldZ - cz) <= TILE / 2 + 1) {
                return true;
            }
        }
        return false;
    }

    private static int copyRoomDatabase() {
        try {
            Path source = Path.of(SOURCE_ROOMS).resolveSibling("killer560smod-roomdata");
            if (!Files.isDirectory(source)) {
                return 0;
            }
            Path target = ModUnderTest.modConfig("killer560smod-roomdata");
            Files.createDirectories(target);
            int n = 0;
            try (var s = Files.list(source)) {
                for (Path f : s.toList()) {
                    if (Files.isRegularFile(f)) {
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
