package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import dev.testkit.compat.McCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 1123-sim-trap-secrets (simtrap-secrets-1122): the sim's New Trap and Old Trap carry the REAL room's secrets, from the
 * room database (the only recorded source), at all four rotations.
 *
 * <p>Per trap and rotation, with the sim's trap-cleared option left off (the default): every database chest of the room
 * appears as a chest on the server at the database coordinate turned into the world by the room's own frame (when he
 * stands by it), and a right click on it is counted by the sim as a secret of THAT room ("Sim secret counted: ... in
 * <trap>"), so an Auto Routes await on the trap's nodes can be met by a real secret instead of a test-injected click.
 * The floor build must also have placed as many secrets as the database lists for the room.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimTrapSecrets1123Tests implements FabricClientGameTest {

    private static final String NAME = "1123-sim-trap-secrets";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String DB = "com.killer560.hub.roomdatabase.RoomDatabase";
    private static final String ROUTE_COORDS = "com.killer560.hub.autoroutes.RouteCoords";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        if (Scenario.legitSkip(NAME)) {
            return;
        }
        Integer renderBefore = SimTrapRoutes1121Tests.setUp(ctx, NAME);
        if (renderBefore == null) {
            return;
        }
        List<String> failures = new ArrayList<>();
        try {
            for (String trap : new String[]{"New Trap", "Old Trap"}) {
                for (int rot = 0; rot < 360; rot += 90) {
                    long mark = LogTap.mark();
                    SimTrapRoutes1121Tests.oneFloor(ctx, failures, trap, rot, (c, f, t, r, tag, north) -> body(c, f, t, r, tag, mark));
                }
            }
        } catch (Throwable t) {
            failures.add("scenario threw: " + t);
            t.printStackTrace(System.out);
        } finally {
            SimTrapRoutes1121Tests.tearDown(ctx, renderBefore);
        }
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " problem(s): " + String.join("; ", failures));
        }
        System.out.println("[" + NAME + "] PASS");
    }

    private static void body(ClientGameTestContext ctx, List<String> failures, String trap, int rot, String tag, long buildMark) {
        SimTrapRoutes1121Tests.standOnTile(ctx, SimTrapRoutes1121Tests.NX, SimTrapRoutes1121Tests.NZ, 0, 0);
        for (int i = 0; i < 300 && ctx.computeOnClient(mc -> SimTrapRoutes1121Tests.roomNamed(
                ModUnderTest.staticCall(LAYOUT, "capture"), trap)) < 0; i++) {
            ctx.waitTicks(1);
        }
        // the database's chests of this room, turned into the world by the live map's own frame for the room
        List<BlockPos> chests = ctx.computeOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            int room = SimTrapRoutes1121Tests.roomNamed(layout, trap);
            int[] cr = room < 0 ? null
                    : (int[]) ModUnderTest.call(layout, "clayRotation", new Class<?>[]{int.class}, new Object[]{room});
            Object entry = ModUnderTest.staticCall(DB, "lookupByName", new Class<?>[]{String.class}, new Object[]{trap});
            if (cr == null || entry == null) {
                return null;
            }
            Object frame = SimTrapRoutes1121Tests.newFrame(trap, cr);
            List<BlockPos> out = new ArrayList<>();
            try {
                Object coords = entry.getClass().getField("secretCoords").get(entry);
                for (Object p : (List<?>) coords.getClass().getField("chest").get(coords)) {
                    int x = p.getClass().getField("x").getInt(p);
                    int y = p.getClass().getField("y").getInt(p);
                    int z = p.getClass().getField("z").getInt(p);
                    out.add((BlockPos) ModUnderTest.staticCall(ROUTE_COORDS, "toRealBlock",
                            new Class<?>[]{frame.getClass(), BlockPos.class}, new Object[]{frame, new BlockPos(x, y, z)}));
                }
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("room database entry shape: " + e, e);
            }
            return out;
        });
        if (chests == null) {
            failures.add(tag + ": no live room or no database entry for " + trap);
            return;
        }
        if (chests.isEmpty()) {
            failures.add(tag + ": the database lists no chest for " + trap + " - the case proves nothing");
            return;
        }
        int clicked = 0;
        for (BlockPos at : chests) {
            tp(ctx, at.getX() + 0.5, at.getY() + 0.05, at.getZ() + 0.5);
            for (int t = 0; t < 200 && !isChest(ctx, at); t++) {
                ctx.waitTicks(1);
            }
            if (!isChest(ctx, at)) {
                failures.add(tag + ": the database chest at " + at.toShortString() + " never appeared ("
                        + ctx.computeOnClient(mc -> mc.level.getBlockState(at).getBlock().toString()) + ")");
                continue;
            }
            tp(ctx, at.getX() + 0.5, at.getY() + 1.0, at.getZ() + 0.5);
            ctx.waitTicks(20);
            ctx.runOnClient(mc -> {
                for (int i = 0; i < 9; i++) {
                    if (mc.player.getInventory().getItem(i).isEmpty()) {
                        mc.player.getInventory().setSelectedSlot(i);
                        break;
                    }
                }
            });
            long m = LogTap.mark();
            ctx.runOnClient(mc -> mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(at).add(0, 0.4, 0), Direction.UP, at, false)));
            ctx.waitTicks(10);
            boolean counted = false;
            for (String l : LogTap.since(m)) {
                counted |= l.contains("Sim secret counted: " + at.toShortString() + " in ");
            }
            ctx.runOnClient(mc -> {
                if (McCompat.screen(mc) != null) {
                    mc.player.closeContainer();
                }
            });
            if (counted) {
                clicked++;
            } else {
                failures.add(tag + ": a click on the database chest at " + at.toShortString() + " was not counted as a secret");
            }
        }
        // the build placed at least the database's chest + bat count for the room (levers are not secrets)
        int placed = 0;
        for (String l : LogTap.since(buildMark)) {
            if (l.contains("Sim secrets for " + trap + ": ")) {
                placed = Integer.parseInt(l.substring(l.indexOf(trap + ": ") + trap.length() + 2).split(" ")[0]);
            }
        }
        System.out.println("[" + NAME + "] " + tag + ": " + chests.size() + " database chest(s), " + clicked
                + " collected and counted, build placed " + placed + " secret(s)");
        if (placed < chests.size()) {
            failures.add(tag + ": the floor build placed " + placed + " secret(s), fewer than the database's "
                    + chests.size() + " chests");
        }
        if (clicked == 0) {
            failures.add(tag + ": no secret was collected - the case never acted");
        }
    }

    private static boolean isChest(ClientGameTestContext ctx, BlockPos at) {
        return ctx.computeOnClient(mc -> mc.level.getBlockState(at).is(Blocks.CHEST)
                || mc.level.getBlockState(at).is(Blocks.TRAPPED_CHEST));
    }

    private static void tp(ClientGameTestContext ctx, double x, double y, double z) {
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            UUID uuid = mc.player.getUUID();
            server.execute(() -> server.getPlayerList().getPlayer(uuid).teleportTo(server.overworld(), x, y, z,
                    java.util.Set.<net.minecraft.world.entity.Relative>of(), 0f, 45f, false));
        });
    }
}
