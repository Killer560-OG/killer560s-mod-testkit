package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.harness.SuiteVerdict;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 1490-sim-mage-capture: {@code /mage} re-captures the room he stands in into the sim's room library (mod
 * mage-capture-1015, 2026-10-15).
 *
 * <p>killer560 (2026-10-15): "Make the command /mage". The shipped Mage capture has ALTAR stitched in as its second
 * tile (that tile's center column hashes to Altar's core in the room database), so he is re-scanning Mage on Hypixel.
 * The command is typed for real ({@code connection.sendCommand}), in a sim world where Mage is loaded on its own:
 * <ol>
 *   <li><b>the corrupt capture is rejected</b>: {@code RoomCapture.validateLibraryRoom("Mage")} on the library's Mage
 *       names Altar;</li>
 *   <li><b>outside Mage it refuses</b>: Altar loaded alone, {@code /mage} says he is in Altar and writes nothing;</li>
 *   <li><b>the stitched tile is caught in the world too</b>: in Mage, with every chunk loaded, {@code /mage} rejects
 *       the room built from that capture, names Altar, and the library file is byte-for-byte unchanged;</li>
 *   <li><b>unloaded chunks refuse</b> (run last): standing in the near end, the client is handed the server's
 *       "forget chunk" packet for the chunks under the far end (premise checked: the client really lacks them and he is
 *       still in Mage); it refuses for unloaded chunks and the file is unchanged;</li>
 *   <li><b>a sound room is captured and round-trips</b>: the stitched tile's center column is replaced, on the server,
 *       by the good tile's (so every tile's column hashes to Mage); {@code /mage} saves, the file decodes to exactly the
 *       server's blocks (full state strings, every x/z of the footprint, every y of the file's band, y moved back by the
 *       sim's altitude offset) and the layers outside the band are empty in the world; the file it replaced was backed
 *       up; after the library reload the library's Mage is that disk capture and usable.</li>
 * </ol>
 * On main the command does not exist (no {@code RoomCapture} class), so this fails there. Sim only, no GrimAC.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimMageCaptureTests implements FabricClientGameTest {

    private static final String NAME = "1490-sim-mage-capture";
    private static final String ROOM = "Mage";
    private static final String OTHER = "Altar";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String CAPTURE = "com.killer560.hub.roomsim.RoomCapture";
    private static final String BUILDER = "com.killer560.hub.roomsim.SimBuilder";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final Pattern TILE_AT = Pattern.compile("capture x (\\d+), z (\\d+)");

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank() || Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> {
            ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled");
            // The mod's Chunk Cache and the Live Map's Keep Chunks Loaded keep chunks the server let go of; off, so
            // the unloaded-chunk step sees what vanilla has.
            ModUnderTest.turnOff("com.killer560.hub.chunkcache.ChunkCacheConfig", "setEnabled");
            ModUnderTest.turnOff("com.killer560.hub.livemap.LiveMapConfig", "setKeepChunksLoaded");
        });
        SimMapTests.copyRoomsForOthers();
        Scenario.ensureRoomDatabase(ctx);
        reload(ctx);
        boolean have = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isUsable",
                new Class<?>[]{String.class}, new Object[]{ROOM})
                && (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isUsable", new Class<?>[]{String.class},
                new Object[]{OTHER}));
        if (!have) {
            Scenario.skipped(NAME, "needs usable " + ROOM + " and " + OTHER + " captures");
            return;
        }
        List<String> failures = new ArrayList<>();
        int renderBefore = ctx.computeOnClient(mc -> mc.options.renderDistance().get());
        long mark = LogTap.mark();
        Path file = ModUnderTest.modConfig("killer560smod-rooms").resolve("Mage.json");
        try {
            // 1. The library's capture, judged as it stands.
            @SuppressWarnings("unchecked")
            List<String> libProblems = ctx.computeOnClient(mc -> (List<String>) ModUnderTest.staticCall(CAPTURE,
                    "validateLibraryRoom", new Class<?>[]{String.class}, new Object[]{ROOM}));
            println("library Mage (" + source(ctx) + ") judged: " + libProblems);
            if (libProblems.stream().noneMatch(p -> p.contains(OTHER))) {
                failures.add("the library's Mage capture was not rejected for Altar's tile: " + libProblems);
            }

            byte[] fileBefore = Files.exists(file) ? Files.readAllBytes(file) : null;
            println("Mage.json in the user library before: " + (fileBefore == null ? "none" : fileBefore.length + " bytes"));

            // 2. Outside Mage: a 1x1 room loaded on its own.
            String outside = ctx.computeOnClient(mc -> {
                @SuppressWarnings("unchecked")
                List<String> names = (List<String>) ModUnderTest.staticCall(ROOM_LIBRARY, "names");
                for (String n : names) {
                    if (!n.equals(ROOM) && (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isUsable",
                            new Class<?>[]{String.class}, new Object[]{n})
                            && (Integer) ModUnderTest.staticCall(ROOM_LIBRARY, "cellFootprint",
                            new Class<?>[]{String.class}, new Object[]{n}) == 1) {
                        return n;
                    }
                }
                return null;
            });
            load(ctx, outside);
            Object r = mage(ctx);
            println("in " + outside + ": " + describe(r));
            String refusal = (String) field(r, "refusal");
            if (Boolean.TRUE.equals(field(r, "saved")) || refusal == null || !refusal.contains("You are in " + outside)) {
                failures.add("/mage in " + outside + " did not refuse as being in it: " + describe(r));
            }

            load(ctx, ROOM);
            // 3. Mage as the library has it, every chunk loaded: the stitched tile rejected, nothing written.
            Object first = awaitLoadedThenMage(ctx);
            println("in Mage as built: " + describe(first));
            @SuppressWarnings("unchecked")
            List<String> problems = (List<String>) field(first, "problems");
            if (Boolean.TRUE.equals(field(first, "saved")) || !"validation".equals(field(first, "refusal"))
                    || problems.stream().noneMatch(p -> p.contains(OTHER))) {
                failures.add("/mage did not reject the room built from the stitched capture for Altar: " + describe(first));
            }
            byte[] fileAfterReject = Files.exists(file) ? Files.readAllBytes(file) : null;
            if (!java.util.Arrays.equals(fileBefore, fileAfterReject)) {
                failures.add("a rejected capture changed Mage.json");
            }
            int originX = (Integer) field(first, "originX");
            int originZ = (Integer) field(first, "originZ");
            int sizeX = (Integer) field(first, "sizeX");
            int sizeZ = (Integer) field(first, "sizeZ");
            int dy = (Integer) field(first, "yOffset");
            println("footprint origin " + originX + "," + originZ + " size " + sizeX + "x" + sizeZ + ", sim y offset " + dy);
            if (sizeX * sizeZ != 65 * 33) {
                failures.add("Mage's footprint is " + sizeX + "x" + sizeZ + ", not 65x33");
                return;
            }

            boolean alongX = sizeX > sizeZ;
            // 5. The stitched tile's column replaced by the good tile's; then a capture that must save and round-trip.
            teleport(ctx, originX + sizeX / 2.0, originZ + sizeZ / 2.0, dy);
            int[] bad = null;
            for (String p : problems) {
                Matcher m = TILE_AT.matcher(p);
                if (p.contains(OTHER) && m.find()) {
                    bad = new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
                }
            }
            if (bad == null) {
                failures.add("no stitched tile named in " + problems);
                return;
            }
            int[] good = alongX ? new int[]{bad[0] == 16 ? 48 : 16, bad[1]} : new int[]{bad[0], bad[1] == 16 ? 48 : 16};
            int copied = copyColumn(ctx, originX + good[0], originZ + good[1], originX + bad[0], originZ + bad[1]);
            println("copied the good tile's center column (capture " + good[0] + "," + good[1] + ") over the stitched one ("
                    + bad[0] + "," + bad[1] + "): " + copied + " block(s) changed");
            final int bx = originX + bad[0];
            final int bz = originZ + bad[1];
            ctx.waitTicks(40);
            Object saved = awaitLoadedThenMage(ctx);
            println("after the column swap: " + describe(saved));
            if (!Boolean.TRUE.equals(field(saved, "saved"))) {
                failures.add("/mage did not save a room whose every tile is Mage: " + describe(saved));
                return;
            }
            Path written = Path.of((String) field(saved, "file"));
            String backup = (String) field(saved, "backup");
            if (!written.toAbsolutePath().normalize().equals(file.toAbsolutePath().normalize())) {
                failures.add("written to " + written + ", not the user library's " + file);
            }
            if (fileBefore != null) {
                if (backup == null || !Files.exists(Path.of(backup))
                        || !java.util.Arrays.equals(fileBefore, Files.readAllBytes(Path.of(backup)))) {
                    failures.add("the Mage.json it replaced was not backed up intact (backup " + backup + ")");
                } else {
                    println("the replaced file is backed up as " + Path.of(backup).getFileName());
                }
            } else if (backup != null) {
                failures.add("a backup was reported with no file to replace: " + backup);
            }
            failures.addAll(roundTrip(ctx, written, originX, originZ, dy, bx, bz));

            // The library reload: the disk capture is the Mage the sim will place.
            ctx.waitTicks(5);
            ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);
            ctx.waitTicks(5);
            String after = source(ctx);
            boolean usable = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isUsable",
                    new Class<?>[]{String.class}, new Object[]{ROOM}));
            @SuppressWarnings("unchecked")
            List<String> nowProblems = ctx.computeOnClient(mc -> (List<String>) ModUnderTest.staticCall(CAPTURE,
                    "validateLibraryRoom", new Class<?>[]{String.class}, new Object[]{ROOM}));
            println("library Mage after the reload: " + after + ", usable " + usable + ", judged " + nowProblems);
            if (!"disk".equals(after) || !usable || !nowProblems.isEmpty()) {
                failures.add("after the reload the library's Mage is " + after + ", usable " + usable + ", problems "
                        + nowProblems);
            }
            // 4. Unloaded chunks (last: the server never resends a chunk it thinks the client still has). Standing in the
            //    near end, the client is handed Hypixel's "forget chunk" packet for every chunk under the far end, through
            //    the real handler, and /mage runs in the same client task. Loaded-ness is asked of the CHUNK SOURCE:
            //    ClientLevel.hasChunk is hard-wired to true (javap 26.1.2), which is what the first version of the
            //    command asked - it then read a forgotten chunk as empty and called its tile "no room".
            teleport(ctx, alongX ? originX + 3.5 : originX + 16.5, alongX ? originZ + 16.5 : originZ + 3.5, dy);
            int farCx = (alongX ? originX + sizeX - 1 : originX + 16) >> 4;
            int farCz = (alongX ? originZ + 16 : originZ + sizeZ - 1) >> 4;
            byte[] beforeUnloaded = Files.readAllBytes(file);
            Object[] unloadRun = ctx.computeOnClient(mc -> {
                var source = mc.level.getChunkSource();
                int n = 0;
                for (int cx = originX >> 4; cx <= (originX + sizeX - 1) >> 4; cx++) {
                    for (int cz = originZ >> 4; cz <= (originZ + sizeZ - 1) >> 4; cz++) {
                        boolean far = alongX ? cx == farCx : cz == farCz;
                        if (far && source.hasChunk(cx, cz)) {
                            mc.getConnection().handleForgetLevelChunk(
                                    new net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket(
                                            new net.minecraft.world.level.ChunkPos(cx, cz)));
                            n++;
                        }
                    }
                }
                boolean isGone = !source.hasChunk(farCx, farCz);
                Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
                int room = (Integer) ModUnderTest.call(layout, "currentRoom", new Class<?>[]{}, new Object[]{});
                String in = (String) ModUnderTest.call(layout, "name", new Class<?>[]{int.class}, new Object[]{room});
                Object before = ModUnderTest.staticCall(CAPTURE, "lastResult");
                mc.player.connection.sendCommand("mage");
                Object res = ModUnderTest.staticCall(CAPTURE, "lastResult");
                return new Object[]{n, isGone, in, res == before ? null : res};
            });
            int dropped = (Integer) unloadRun[0];
            boolean gone = (Boolean) unloadRun[1];
            String standingIn = (String) unloadRun[2];
            Object u = unloadRun[3];
            println("forgot " + dropped + " chunk(s) under the far end; chunk " + farCx + "," + farCz + " gone on the client: "
                    + gone + "; standing in " + standingIn);
            if (dropped < 1 || !gone || !ROOM.equals(standingIn)) {
                failures.add("premise: could not get part of Mage unloaded while standing in it (dropped " + dropped
                        + ", gone " + gone + ", in " + standingIn + ")");
            } else {
                println("with part unloaded: " + describe(u));
                String why = u == null ? null : (String) field(u, "refusal");
                if (u == null || Boolean.TRUE.equals(field(u, "saved")) || why == null || !why.contains("not loaded")) {
                    failures.add("/mage with unloaded chunks did not refuse for them: " + describe(u));
                }
                if (!java.util.Arrays.equals(beforeUnloaded, Files.readAllBytes(file))) {
                    failures.add("a refused capture (unloaded chunks) changed Mage.json");
                }
            }

            List<String> chat = new ArrayList<>();
            for (String l : LogTap.since(mark)) {
                if (l.contains("[Room Capture]")) {
                    chat.add(l.substring(Math.max(0, l.indexOf("[Room Capture]"))));
                }
            }
            for (String l : chat) {
                println("chat: " + l);
            }
            if (chat.stream().noneMatch(l -> l.contains("Saved to")) || chat.stream().noneMatch(l -> l.contains("Library reloaded"))) {
                failures.add("chat never said where it saved and that the library reloaded");
            }
            for (String e : LogTap.modErrorsSince(mark)) {
                failures.add("mod ERROR: " + e);
            }
        } catch (Throwable t) {
            failures.add("threw: " + t);
            t.printStackTrace();
        } finally {
            ctx.runOnClient(mc -> mc.options.renderDistance().set(renderBefore));
            teardown(ctx);
            if (failures.isEmpty()) {
                System.out.println("[" + NAME + "] PASS - /mage rejects the stitched capture, refuses outside Mage and "
                        + "with chunks unloaded, and saves a sound Mage that decodes to the world block for block");
            } else {
                String why = String.join("; ", failures);
                System.out.println("[" + NAME + "] FAIL: " + why);
                SuiteVerdict.failCase(NAME, new AssertionError(why));
            }
        }
    }

    private static void reload(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitTicks(2);
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);
    }

    /** "jar" or "disk" for the library's Mage. */
    private static String source(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            Object room = ModUnderTest.staticCall(ROOM_LIBRARY, "get", new Class<?>[]{String.class}, new Object[]{ROOM});
            if (room == null) {
                return "none";
            }
            return (Boolean) ModUnderTest.call(room, "isBundled", new Class<?>[]{}, new Object[]{}) ? "jar" : "disk";
        });
    }

    private static void load(ClientGameTestContext ctx, String name) {
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> ModUnderTest.staticCall(BUILDER, "buildSingleRoom",
                new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, name})));
        ctx.waitFor(mc -> mc.level != null && mc.player != null, 2400);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitFor(mc -> McCompat.screen(mc) == null, 1200);
        // The client must stand on a block before anything reads its world (scenario lessons: the first sim world).
        ctx.waitFor(mc -> mc.player != null && mc.player.onGround(), 1800);
        ctx.waitTicks(40);
    }

    /** Types /mage and returns RoomCapture's result for it. */
    private static Object mage(ClientGameTestContext ctx) {
        // Fails here, on a jar without the command (no RoomCapture class).
        Object before = ctx.computeOnClient(mc -> ModUnderTest.staticCall(CAPTURE, "lastResult"));
        ctx.runOnClient(mc -> mc.player.connection.sendCommand("mage"));
        ctx.waitTicks(2);
        Object r = ctx.computeOnClient(mc -> ModUnderTest.staticCall(CAPTURE, "lastResult"));
        if (r == null || r == before) {
            throw new AssertionError("/mage ran nothing (no new result)");
        }
        return r;
    }

    /** /mage until it is not refused for unloaded chunks (up to 30 s), so a chunk still arriving is not the verdict. */
    private static Object awaitLoadedThenMage(ClientGameTestContext ctx) {
        Object r = null;
        for (int i = 0; i < 30; i++) {
            r = mage(ctx);
            String why = (String) field(r, "refusal");
            if (why == null || !why.contains("not loaded")) {
                return r;
            }
            ctx.waitTicks(20);
        }
        return r;
    }

    private static void teleport(ClientGameTestContext ctx, double x, double z, int dy) {
        AtomicReference<Boolean> done = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                ServerLevel level = server.overworld();
                var sp = server.getPlayerList().getPlayer(uuid);
                double y = standY(level, (int) Math.floor(x), (int) Math.floor(z), dy);
                sp.teleportTo(level, x, y, z, java.util.Set.<net.minecraft.world.entity.Relative>of(), sp.getYRot(),
                        sp.getXRot(), false);
                done.set(true);
            });
        });
        ctx.waitFor(mc -> done.get() != null, 200);
        ctx.waitTicks(10);
    }

    /** A y to stand at in this column: the lowest floor at or above the doorway floor with two clear blocks over it. */
    private static double standY(ServerLevel level, int x, int z, int dy) {
        for (int y = 69 + dy; y < 120 + dy; y++) {
            BlockPos feet = new BlockPos(x, y, z);
            if (!level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty()
                    && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                    && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()) {
                return y;
            }
        }
        return 75 + dy;
    }

    /** Copies one whole world column onto another, on the server, telling clients. Returns blocks changed. */
    private static int copyColumn(ClientGameTestContext ctx, int fromX, int fromZ, int toX, int toZ) {
        AtomicReference<Integer> out = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                ServerLevel level = server.overworld();
                int n = 0;
                for (int y = level.getMinY(); y <= level.getMaxY(); y++) {
                    BlockState s = level.getBlockState(new BlockPos(fromX, y, fromZ));
                    BlockPos to = new BlockPos(toX, y, toZ);
                    if (!level.getBlockState(to).equals(s)) {
                        level.setBlock(to, s, 2);
                        n++;
                    }
                }
                out.set(n);
            });
        });
        ctx.waitFor(mc -> out.get() != null, 400);
        return out.get();
    }

    /**
     * The written file, decoded by the mod's own reader, against the server's world: every block of the band by full
     * state, and the world empty outside the band. Also checks the swapped column made it in.
     */
    private static List<String> roundTrip(ClientGameTestContext ctx, Path file, int ox, int oz, int dy, int bx, int bz) {
        AtomicReference<Object> out = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            Object room = ModUnderTest.staticCall(ROOM_LIBRARY, "readFile", new Class<?>[]{Path.class}, new Object[]{file});
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                try {
                    out.set(compare(server.overworld(), room, ox, oz, dy, bx, bz));
                } catch (Throwable t) {
                    out.set(t);
                }
            });
        });
        ctx.waitFor(mc -> out.get() != null, 1200);
        if (out.get() instanceof Throwable t) {
            throw new AssertionError("the round trip threw", t);
        }
        @SuppressWarnings("unchecked")
        List<String> fail = (List<String>) out.get();
        return fail;
    }

    private static List<String> compare(ServerLevel level, Object room, int ox, int oz, int dy, int bx, int bz)
            throws Exception {
        List<String> fail = new ArrayList<>();
        if (room == null) {
            fail.add("the written file does not decode");
            return fail;
        }
        @SuppressWarnings("unchecked")
        List<String> palette = (List<String>) room.getClass().getField("palette").get(room);
        var at = room.getClass().getMethod("at", int.class, int.class, int.class);
        int sizeX = room.getClass().getField("sizeX").getInt(room);
        int sizeZ = room.getClass().getField("sizeZ").getInt(room);
        int minY = room.getClass().getField("minY").getInt(room);
        int maxY = room.getClass().getField("maxY").getInt(room);
        long compared = 0;
        long solid = 0;
        int differ = 0;
        List<String> samples = new ArrayList<>();
        for (int x = 0; x < sizeX; x++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    short s = (Short) at.invoke(room, x, y, z);
                    String want = s < 0 || s >= palette.size() ? "<unread>" : palette.get(s);
                    String got = net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(
                            level.getBlockState(new BlockPos(ox + x, y + dy, oz + z)));
                    compared++;
                    if (!want.endsWith("air")) {
                        solid++;
                    }
                    if (!norm(want).equals(norm(got))) {
                        differ++;
                        if (samples.size() < 8) {
                            samples.add("(" + x + "," + y + "," + z + ") file " + want + " world " + got);
                        }
                    }
                }
            }
        }
        // Outside the trimmed band the world must hold nothing (the trim may only drop empty layers).
        int outside = 0;
        for (int x = 0; x < sizeX; x++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int y = level.getMinY(); y <= level.getMaxY(); y++) {
                    int capY = y - dy;
                    if (capY >= minY && capY <= maxY) {
                        continue;
                    }
                    if (!level.getBlockState(new BlockPos(ox + x, y, oz + z)).isAir()) {
                        outside++;
                    }
                }
            }
        }
        // The swapped column is in the file.
        int col = 0;
        for (int y = minY; y <= maxY; y++) {
            short s = (Short) at.invoke(room, bx - ox, y, bz - oz);
            String got = net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(
                    level.getBlockState(new BlockPos(bx, y + dy, bz)));
            if (s >= 0 && norm(palette.get(s)).equals(norm(got))) {
                col++;
            }
        }
        System.out.println("[" + NAME + "] round trip: file " + sizeX + "x" + sizeZ + " y " + minY + ".." + maxY + ", "
                + compared + " block(s) compared (" + solid + " not air), " + differ + " differ"
                + (samples.isEmpty() ? "" : " e.g. " + samples) + "; " + outside
                + " non-air world block(s) outside the band; swapped column " + col + " of " + (maxY - minY + 1)
                + " match");
        if (compared < 65L * 33 * 30 || solid < 20_000) {
            fail.add("round trip compared only " + compared + " block(s), " + solid + " solid - the file is not the room");
        }
        if (differ > 0) {
            fail.add("the file differs from the world at " + differ + " block(s): " + samples);
        }
        if (outside > 0) {
            fail.add(outside + " non-air world block(s) of the footprint lie outside the file's y band");
        }
        if (col != maxY - minY + 1) {
            fail.add("the swapped column matches the world at only " + col + " of " + (maxY - minY + 1) + " y");
        }
        return fail;
    }

    private static String norm(String state) {
        return state.equals("minecraft:void_air") || state.equals("minecraft:cave_air") ? "minecraft:air" : state;
    }

    private static Object field(Object record, String name) {
        try {
            return record.getClass().getMethod(name).invoke(record);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("no " + name + " on " + record, e);
        }
    }

    private static String describe(Object r) {
        return r == null ? "no result" : r.toString();
    }

    private static void teardown(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            try {
                ModUnderTest.staticCall(SIM_STATE, "leave", new Class<?>[]{}, new Object[]{});
            } catch (Throwable ignored) {
                // never let cleanup replace the verdict
            }
        });
        ctx.runOnClient(mc -> mc.execute(() -> {
            if (mc.level != null) {
                mc.level.disconnect(net.minecraft.network.chat.Component.literal("scenario over"));
                mc.disconnectWithSavingScreen();
            }
        }));
        ctx.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null, 1200);
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> mc.execute(() ->
                McCompat.setScreen(mc, new net.minecraft.client.gui.screens.TitleScreen())));
        ctx.waitFor(mc -> McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.TitleScreen, 400);
    }

    private static void println(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}
