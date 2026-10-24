package dev.testkit.gametest;

import dev.testkit.compat.McCompat;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 99-sim-arrowtrap: the Interactive Map works while he stands in Arrow Trap.
 *
 * <p>killer560 (2026-10-13): "for some reason interactive map does not work if i am in arrow trap". Arrow Trap is a
 * NORMAL room in the room database, but {@code AutoClearUtils.canPath} still carried QUOI's by-name rule ("Trap" in
 * the name and his relative z >= 0 refuses), so every map press from that half of the room did nothing at all.
 *
 * <p>A floor with Arrow Trap on tile (2,2), Temple on (3,2) and Entrance on (3,3), the run begun, an AOTV in hand. He
 * is stood in Arrow Trap at a spot whose clay-relative z is >= 0 (the half the old rule refused), and then:
 * <ol>
 *   <li>the live map and the sim's sidebar both say Arrow Trap, so the press really is made from inside it;</li>
 *   <li>{@code AutoClearUtils.canPath} is true there;</li>
 *   <li>a map press on Temple plans ("Found path"), and moves him over 5 blocks into Temple;</li>
 *   <li>control: the sim still refuses an AOTV use in New Trap (a real TRAP room) - the trap rule is not gone.</li>
 * </ol>
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimArrowTrapMapTests implements FabricClientGameTest {

    private static final String NAME = "99-sim-arrowtrap";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String SIM_ITEMS = "com.killer560.hub.roomsim.SimItems";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String DB = "com.killer560.hub.roomdatabase.RoomDatabase";
    private static final String LIVE_MAP_CONFIG = "com.killer560.hub.livemap.LiveMapConfig";
    private static final String IM_FEATURE = "com.killer560.hub.livemap.InteractiveMapFeature";
    private static final String EXECUTOR = "com.killer560.hub.livemap.autoclear.ClearExecutor";
    private static final String CLEAR_UTILS = "com.killer560.hub.livemap.autoclear.AutoClearUtils";
    private static final String AUTO_CONFIG = "com.killer560.hub.autopuzzles.AutoPuzzlesConfig";
    private static final int GRID = 11;
    private static final int ROOM_GRID = 6;
    private static final String ARROW = "Arrow Trap";
    private static final String TRAP_LINE = "No abilities in a trap room";

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
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(DB, "isReady"), 1000);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"));
        Object mapCfg = ctx.computeOnClient(mc -> ModUnderTest.config(LIVE_MAP_CONFIG));
        ctx.runOnClient(mc -> {
            ModUnderTest.set(ModUnderTest.config(AUTO_CONFIG), "setAutoPuzzlesMasterEnabled", false);
            ModUnderTest.set(mapCfg, "setEnabled", true);
            ModUnderTest.set(mapCfg, "setInteractiveMapEnabled", true);
        });
        int renderBefore = ctx.computeOnClient(mc -> mc.options.renderDistance().get());
        ctx.runOnClient(mc -> mc.options.renderDistance().set(16));

        List<String> failures = new ArrayList<>();
        try {
            body(ctx, failures);
        } catch (Throwable t) {
            failures.add("scenario threw: " + t);
            t.printStackTrace(System.out);
        } finally {
            ctx.runOnClient(mc -> {
                McCompat.setScreen(mc, null);
                mc.options.renderDistance().set(renderBefore);
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
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " problem(s): " + String.join("; ", failures));
        }
        System.out.println("[" + NAME + "] PASS");
    }

    private static int key(int gx, int gz) {
        return gz * ROOM_GRID + gx;
    }

    private static void body(ClientGameTestContext ctx, List<String> failures) {
        Map<Integer, String> floor = new LinkedHashMap<>();
        floor.put(key(2, 2), ARROW);
        floor.put(key(3, 2), "Temple");
        floor.put(key(3, 3), "Entrance");
        floor.put(key(1, 2), "New Trap");
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> ModUnderTest.staticCall(FLOOR_GEN, "buildExplicit",
                new Class<?>[]{Minecraft.class, Map.class}, new Object[]{mc, floor})));
        ctx.waitFor(mc -> mc.level != null, 2400);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitTicks(60);

        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimRun", "begin",
                new Class<?>[]{Minecraft.class, BlockPos.class},
                new Object[]{mc, ModUnderTest.staticCall("com.killer560.hub.roomsim.SimBuilder", "entranceDoor")}));
        for (int i = 0; i < 400 && !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(
                "com.killer560.hub.roomsim.SimRun", "isRunning")); i++) {
            ctx.waitTicks(1);
        }
        giveAotv(ctx);

        // ---- stood in Arrow Trap, in the half the old by-name rule refused ---------------------------------
        String placed = standInArrowTrap(ctx);
        println("Arrow Trap spot: " + placed);
        String side = ctx.computeOnClient(mc -> (String) ModUnderTest.staticCall(SIM_STATE, "currentRoomName"));
        String live = liveRoomName(ctx);
        Vec3 at = ctx.computeOnClient(mc -> mc.player.position());
        int relZ = relZ(ctx);
        boolean canPath = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(CLEAR_UTILS, "canPath",
                new Class<?>[]{layoutClass()}, new Object[]{ModUnderTest.staticCall(LAYOUT, "capture")}));
        println(String.format("in Arrow Trap at (%.1f, %.1f, %.1f): sidebar '%s', live map '%s', clay-relative z %d,"
                + " canPath %s", at.x, at.y, at.z, side, live, relZ, canPath));
        if (!ARROW.equals(side) || !ARROW.equals(live)) {
            failures.add("not stood in Arrow Trap (sidebar '" + side + "', live map '" + live + "') - the case under"
                    + " test never ran");
            return;
        }
        if (relZ == Integer.MIN_VALUE || relZ < 0) {
            failures.add("the spot's clay-relative z is " + relZ + ", not the half (z >= 0) the bug refused");
        }
        if (!canPath) {
            failures.add("AutoClearUtils.canPath is false standing in Arrow Trap (a NORMAL room)");
        }

        // ---- a map press on Temple -------------------------------------------------------------------------
        long mark = LogTap.mark();
        int templeCell = 2 * 2 * GRID + 2 * 3;
        ctx.runOnClient(mc -> ModUnderTest.staticCall(IM_FEATURE, "onMapPress", new Class<?>[]{int.class},
                new Object[]{templeCell}));
        boolean busySeen = false;
        for (int t = 0; t < 400; t++) {
            ctx.waitTicks(1);
            boolean busy = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(EXECUTOR, "isBusy"));
            busySeen |= busy;
            if (busySeen && !busy) {
                break;
            }
        }
        ctx.waitTicks(10);
        Vec3 after = ctx.computeOnClient(mc -> mc.player.position());
        boolean found = false;
        boolean trap = false;
        for (String l : LogTap.since(mark)) {
            // A searched plan says "Found path"; a trip planned ahead runs with no search ("Path precomputed" /
            // "planned ahead"). Every one logs "[Path] running N warp(s)" (2026-10-16: a precomputed press failed this).
            found |= l.contains("Found path") || l.contains("[Path] running ");
            trap |= l.contains(TRAP_LINE);
        }
        String end = liveRoomName(ctx);
        double moved = at.distanceTo(after);
        println(String.format("press on Temple from Arrow Trap: executor ran %s, found path %s, trap refusal %s;"
                + " moved %.1f blocks, ended in '%s'", busySeen, found, trap, moved, end));
        if (!busySeen || !found) {
            failures.add("a map press on Temple from Arrow Trap did not plan and start a path (executor ran "
                    + busySeen + ", found path " + found + ")");
        }
        if (trap) {
            failures.add("the sim refused an ability in Arrow Trap with '" + TRAP_LINE + "'");
        }
        if (moved < 5 || !"Temple".equals(end)) {
            failures.add(String.format("the press did not take him to Temple (moved %.1f, ended in '%s')", moved, end));
        }

        // ---- control: a real trap room still refuses ------------------------------------------------------
        String inTrap = standOnTile(ctx, 1, 2, 0, 0);
        println("New Trap spot: " + inTrap);
        String trapSide = ctx.computeOnClient(mc -> (String) ModUnderTest.staticCall(SIM_STATE, "currentRoomName"));
        boolean trapCanPath = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(CLEAR_UTILS, "canPath",
                new Class<?>[]{layoutClass()}, new Object[]{ModUnderTest.staticCall(LAYOUT, "capture")}));
        println("in New Trap (sidebar '" + trapSide + "'): canPath " + trapCanPath);
        if ("New Trap".equals(trapSide) && trapCanPath) {
            failures.add("canPath is true inside New Trap - the trap rule itself was lost");
        } else if (!"New Trap".equals(trapSide)) {
            failures.add("control not run: sidebar says '" + trapSide + "' on New Trap's tile");
        }
    }

    /** The clay-relative z of his block in the room he is in, or MIN_VALUE when the layout has no clay for it. */
    private static int relZ(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            int room = (Integer) ModUnderTest.call(layout, "currentRoom", new Class<?>[]{}, new Object[]{});
            int[] cr = (int[]) ModUnderTest.call(layout, "clayRotation", new Class<?>[]{int.class},
                    new Object[]{room});
            if (cr == null) {
                return Integer.MIN_VALUE;
            }
            Object rel = ModUnderTest.staticCall(DB, "toRelativeCoord",
                    new Class<?>[]{BlockPos.class, int.class, int.class, int.class},
                    new Object[]{mc.player.blockPosition(), cr[0], cr[1], cr[2]});
            try {
                return rel.getClass().getField("z").getInt(rel);
            } catch (ReflectiveOperationException e) {
                return Integer.MIN_VALUE;
            }
        });
    }

    /**
     * Tries spots across Arrow Trap's tile until one is in Arrow Trap (live map), on the ground and at clay-relative
     * z >= 0. Returns a description of the spot used.
     */
    private static String standInArrowTrap(ClientGameTestContext ctx) {
        String last = "no spot tried";
        int[] offs = {0, 6, -6, 11, -11};
        for (int dz : offs) {
            for (int dx : offs) {
                last = standOnTile(ctx, 2, 2, dx, dz);
                if (!last.startsWith("placed")) {
                    continue;
                }
                if (ARROW.equals(liveRoomName(ctx)) && relZ(ctx) >= 0
                        && ctx.computeOnClient(mc -> mc.player.onGround())) {
                    return last + " (offset " + dx + "," + dz + ")";
                }
            }
        }
        return "none of the spots tried was in Arrow Trap's z >= 0 half; last " + last;
    }

    private static String liveRoomName(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            int r = (Integer) ModUnderTest.call(layout, "roomAtWorld", new Class<?>[]{double.class, double.class},
                    new Object[]{mc.player.getX(), mc.player.getZ()});
            return r < 0 ? null : (String) ModUnderTest.call(layout, "name", new Class<?>[]{int.class},
                    new Object[]{r});
        });
    }

    /**
     * Stands him in the column {@code (dx, dz)} from tile (gx, gz)'s center: the first block near the nominal floor
     * height with two of air on it and a ceiling somewhere above, found and teleported to on the SERVER thread (the
     * sim's /goto scans from the render thread and deadlocks the gametest lockstep - see 99-sim-im).
     */
    private static String standOnTile(ClientGameTestContext ctx, int gx, int gz, int dx, int dz) {
        AtomicReference<String> done = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            BlockPos c = (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class},
                    new Object[]{2 * gz * GRID + 2 * gx});
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(uuid);
                if (sp == null) {
                    done.set("no server player");
                    return;
                }
                var level = sp.level();
                int x = c.getX() + dx;
                int z = c.getZ() + dz;
                for (int y = c.getY() - 8; y < c.getY() + 14; y++) {
                    BlockPos b = new BlockPos(x, y, z);
                    var st = level.getBlockState(b);
                    if (!st.getCollisionShape(level, b).isEmpty() && st.getFluidState().isEmpty()
                            && level.getBlockState(b.above()).isAir() && level.getBlockState(b.above(2)).isAir()) {
                        sp.teleportTo(x + 0.5, y + 1, z + 0.5);
                        done.set("placed at " + x + "," + (y + 1) + "," + z);
                        return;
                    }
                }
                done.set("no standable block in the column at " + x + "," + z);
            });
        });
        ctx.waitFor(mc -> done.get() != null, 200);
        ctx.waitTicks(10);
        for (int i = 0; i < 60 && !ctx.computeOnClient(mc -> mc.player.onGround()); i++) {
            ctx.waitTicks(1);
        }
        return done.get();
    }

    private static void giveAotv(ClientGameTestContext ctx) {
        AtomicReference<Boolean> given = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(uuid);
                if (sp == null) {
                    given.set(false);
                    return;
                }
                var inv = sp.getInventory();
                for (int i = 0; i < 36; i++) {
                    inv.setItem(i, ItemStack.EMPTY);
                }
                inv.setItem(0, (ItemStack) ModUnderTest.staticCall(SIM_ITEMS, "build", new Class<?>[]{String.class},
                        new Object[]{"ASPECT_OF_THE_VOID"}));
                given.set(true);
            });
        });
        ctx.waitFor(mc -> given.get() != null, 200);
        ctx.waitTicks(10);
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
        ctx.waitTicks(10);
    }

    private static Class<?> layoutClass() {
        try {
            return Class.forName(LAYOUT);
        } catch (ClassNotFoundException e) {
            throw new AssertionError("no " + LAYOUT, e);
        }
    }

    private static void println(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}
