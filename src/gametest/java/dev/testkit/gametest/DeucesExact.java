package dev.testkit.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Deuces' chest doorway as the mod pastes it ({@code DeucesChestDoorway}, mod deuces-exact-1018), read back from the
 * server's level: every block of the mod's table, at the paste's rotation, against the world.
 */
final class DeucesExact {

    static final String CLASS = "com.killer560.hub.roomsim.DeucesChestDoorway";
    private static final String PLACER = "com.killer560.hub.roomsim.RoomPlacer";

    /**
     * @param compared blocks compared; sealed: expected air, found the solo seal's diamond; notCapture: of the table, how
     *                 many are not the capture's block (so a paste that ignored the table would fail that many);
     *                 samples: first mismatches
     */
    record Result(int compared, int wrong, int sealed, int notCapture, List<String> samples) {
        String summary() {
            return compared + " block(s) of the chest doorway compared (" + notCapture + " of them not the capture's), "
                    + wrong + " differ, " + sealed + " are the solo seal's diamond"
                    + (samples.isEmpty() ? "" : " e.g. " + samples);
        }
    }

    private DeucesExact() {
    }

    /** The mod's table, capture-frame key ({@code RoomDoorPieces.key}) to state, for the room object. */
    @SuppressWarnings("unchecked")
    static Map<Long, BlockState> table(Object room) {
        return (Map<Long, BlockState>) ModUnderTest.staticCall(CLASS, "overrides",
                new Class<?>[]{room.getClass()}, new Object[]{room});
    }

    static int keyX(long k) {
        return (int) ((k >> 16) & 0xFFFF);
    }

    static int keyY(long k) {
        return (int) (k >> 32);
    }

    static int keyZ(long k) {
        return (int) (k & 0xFFFF);
    }

    /** World position of capture (x, y, z) for a room of the given size pasted with its capture x 0 / z 0 corner at (x0, z0). */
    static BlockPos world(int x0, int z0, int dy, int sizeX, int sizeZ, int rotation, int x, int y, int z) {
        int wx = (Integer) ModUnderTest.staticCall(PLACER, "rotatedX",
                new Class<?>[]{int.class, int.class, int.class, int.class, int.class},
                new Object[]{x, z, sizeX, sizeZ, rotation});
        int wz = (Integer) ModUnderTest.staticCall(PLACER, "rotatedZ",
                new Class<?>[]{int.class, int.class, int.class, int.class, int.class},
                new Object[]{x, z, sizeX, sizeZ, rotation});
        return new BlockPos(x0 + wx, y + dy, z0 + wz);
    }

    /** Server thread. Deuces pasted at {@code rotation} with its capture corner at (x0, z0). */
    static Result check(ServerLevel level, Object room, int x0, int z0, int dy, int rotation) throws Exception {
        int sizeX = room.getClass().getField("sizeX").getInt(room);
        int sizeZ = room.getClass().getField("sizeZ").getInt(room);
        Rotation turn = Rotation.values()[Math.floorMod(rotation / 90, 4)];
        @SuppressWarnings("unchecked")
        List<String> palette = (List<String>) room.getClass().getField("palette").get(room);
        var at = room.getClass().getMethod("at", int.class, int.class, int.class);
        int compared = 0;
        int wrong = 0;
        int sealed = 0;
        int notCapture = 0;
        List<String> samples = new ArrayList<>();
        for (Map.Entry<Long, BlockState> e : table(room).entrySet()) {
            long k = e.getKey();
            int x = keyX(k);
            int y = keyY(k);
            int z = keyZ(k);
            short s = (Short) at.invoke(room, x, y, z);
            BlockState captured = s < 0 ? null : (BlockState) ModUnderTest.staticCall(PLACER, "resolve",
                    new Class<?>[]{String.class}, new Object[]{palette.get(s)});
            if (captured == null || !captured.equals(e.getValue())) {
                notCapture++;
            }
            BlockState want = e.getValue().rotate(turn);
            BlockState got = level.getBlockState(world(x0, z0, dy, sizeX, sizeZ, rotation, x, y, z));
            compared++;
            boolean same = want.getBlock() instanceof WallBlock ? got.getBlock() == want.getBlock() : got.equals(want);
            if (want.isAir() && got.isAir()) {
                same = true;
            }
            if (!same && want.isAir() && BuiltInRegistries.BLOCK.getKey(got.getBlock()).getPath().equals("diamond_block")) {
                sealed++;
                continue;
            }
            if (!same) {
                wrong++;
                if (samples.size() < 8) {
                    samples.add("(" + x + "," + y + "," + z + ") want " + want + " got " + got);
                }
            }
        }
        return new Result(compared, wrong, sealed, notCapture, samples);
    }
}
