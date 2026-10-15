package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.harness.PacketTrace;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 683-sim-ether-ticks (mod branch dungeon-b2). killer560, 2026-10-09: "For interactive map can you add an amount of ticks
 * to make the etherwarps as well. I want to be sure it is actually etherwarping every tick." Only when named.
 *
 * <p>A generated sim F7, an Aspect of the Void in hand, the Interactive Map on; a map press (the same call a map click
 * makes, {@code AutoClearUtils.pathToRoom}) to a far room with Ticks Between Etherwarps = 1, then another with 3. What is
 * measured is the WIRE: {@link PacketTrace} buckets every packet by client tick, and the ticks that carry a
 * {@code use_item} are the hops. Each press must have made at least 3 hops and moved him over 20 blocks (so the pace is
 * not vacuous); every gap between two hops must be at least the setting, and most of them exactly it. The executor's
 * own record ({@code ClearExecutor.hopSendTicks}) is printed beside it; where the two disagree the wire wins.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimEtherTicksTests implements FabricClientGameTest {

    private static final String NAME = "683-sim-ether-ticks";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String SIM_ITEMS = "com.killer560.hub.roomsim.SimItems";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String LIVE_MAP_CONFIG = "com.killer560.hub.livemap.LiveMapConfig";
    private static final String CLEAR_UTILS = "com.killer560.hub.livemap.autoclear.AutoClearUtils";
    private static final String EXECUTOR = "com.killer560.hub.livemap.autoclear.ClearExecutor";
    private static final String AUTO_CONFIG = "com.killer560.hub.autopuzzles.AutoPuzzlesConfig";
    private static final int GRID = 11;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank() || Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        boolean cheat = ctx.computeOnClient(mc -> {
            try {
                return Class.forName("com.killer560.hub.BuildVariant").getField("CHEAT_FEATURES_ENABLED").getBoolean(null);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        if (!cheat) {
            Scenario.skipped(NAME, "legit jar: no Interactive Map");
            return;
        }
        if (SimMapTests.copyRoomsForOthers() < 20) {
            Scenario.skipped(NAME, "needs real room captures and room database");
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);
        int oldGap = ctx.computeOnClient(mc -> (Integer) ModUnderTest.call(ModUnderTest.config(LIVE_MAP_CONFIG),
                "getEtherwarpTickGap", new Class<?>[]{}, new Object[]{}));
        int renderBefore = ctx.computeOnClient(mc -> mc.options.renderDistance().get());
        List<String> failures = new ArrayList<>();
        try {
            ctx.runOnClient(mc -> {
                ModUnderTest.set(ModUnderTest.config(AUTO_CONFIG), "setAutoPuzzlesMasterEnabled", false);
                Object map = ModUnderTest.config(LIVE_MAP_CONFIG);
                ModUnderTest.set(map, "setEnabled", true);
                ModUnderTest.set(map, "setInteractiveMapEnabled", true);
                mc.options.renderDistance().set(16);
            });
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter", new Class<?>[]{String.class},
                    new Object[]{"gametest"}));
            long before = Scenario.simBuildCount(ctx);
            ctx.runOnClient(mc -> mc.execute(() -> {
                Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
                ModUnderTest.staticCall(FLOOR_GEN, "generate",
                        new Class<?>[]{Minecraft.class, floor.getClass(), int.class, int.class},
                        new Object[]{mc, floor, 3, 4});
            }));
            ctx.waitFor(mc -> mc.level != null, 2400);
            Scenario.awaitSimBuild(ctx, before);
            ctx.waitFor(mc -> McCompat.screen(mc) == null, 1200);
            ctx.waitFor(mc -> mc.player.onGround() && !mc.level.getBlockState(mc.player.blockPosition().below()).isAir(),
                    2400);
            awaitFloorChunks(ctx);
            giveAotv(ctx, failures);
            ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimRun", "begin",
                    new Class<?>[]{Minecraft.class, BlockPos.class},
                    new Object[]{mc, ModUnderTest.staticCall("com.killer560.hub.roomsim.SimBuilder", "entranceDoor")}));
            for (int i = 0; i < 400 && !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(
                    "com.killer560.hub.roomsim.SimRun", "isRunning")); i++) {
                ctx.waitTicks(1);
            }
            ctx.waitTicks(100);   // the floor graph warms (Interactive Map on, in a dungeon)
            for (int gap : new int[]{1, 3}) {
                measure(ctx, gap, failures);
            }
        } catch (Throwable t) {
            failures.add("scenario threw: " + t);
            t.printStackTrace(System.out);
        } finally {
            ctx.runOnClient(mc -> {
                ModUnderTest.set(ModUnderTest.config(LIVE_MAP_CONFIG), "setEtherwarpTickGap", oldGap);
                mc.options.renderDistance().set(renderBefore);
                ModUnderTest.staticCall(SIM_STATE, "leave");
            });
            ctx.runOnClient(mc -> mc.execute(() -> {
                if (mc.level != null) {
                    mc.level.disconnect(net.minecraft.network.chat.Component.literal("scenario over"));
                    mc.disconnectWithSavingScreen();
                }
            }));
            ctx.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null, 2400);
            ctx.waitTicks(40);
            ctx.runOnClient(mc -> mc.execute(() ->
                    McCompat.setScreen(mc, new net.minecraft.client.gui.screens.TitleScreen())));
            ctx.waitFor(mc -> McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.TitleScreen, 2400);
        }
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " problem(s): " + String.join("; ", failures));
        }
        System.out.println("[" + NAME + "] PASS");
    }

    /** Presses the farthest rooms (up to 5 tries) until one press makes 3+ hops, at Ticks Between Etherwarps = gap. */
    private static void measure(ClientGameTestContext ctx, int gap, List<String> failures) {
        ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(LIVE_MAP_CONFIG), "setEtherwarpTickGap", gap));
        List<int[]> rooms = ctx.computeOnClient(SimEtherTicksTests::farthestRooms);
        for (int attempt = 0; attempt < Math.min(5, rooms.size()); attempt++) {
            int[] t = rooms.get(attempt);
            for (int i = 0; i < 100 && !ctx.computeOnClient(mc -> mc.player.onGround()); i++) {
                ctx.waitTicks(1);
            }
            Vec3 from = ctx.computeOnClient(mc -> mc.player.position());
            int hops0 = ctx.computeOnClient(mc -> hopTicks().size());
            ctx.runOnClient(mc -> PacketTrace.start());
            Boolean ok = ctx.computeOnClient(mc -> {
                Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
                return (Boolean) ModUnderTest.staticCall(CLEAR_UTILS, "pathToRoom",
                        new Class<?>[]{layout.getClass(), int.class, int.class, int.class},
                        new Object[]{layout, t[1], t[0], 0});
            });
            boolean busySeen = false;
            for (int i = 0; i < 600 && Boolean.TRUE.equals(ok); i++) {
                ctx.waitTicks(1);
                boolean busy = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(EXECUTOR, "isBusy"));
                busySeen |= busy;
                if (busySeen && !busy) {
                    break;
                }
            }
            ctx.waitTicks(5);
            List<Integer> wire = ctx.computeOnClient(mc -> {
                PacketTrace.stop();
                List<Integer> out = new ArrayList<>();
                for (int i = 0; i < PacketTrace.tickCount(); i++) {
                    if (PacketTrace.sentOn(i).contains("use_item")) {
                        out.add(i);
                    }
                }
                return out;
            });
            List<Long> own = ctx.computeOnClient(mc -> {
                List<Long> all = hopTicks();
                return new ArrayList<>(all.subList(Math.min(hops0, all.size()), all.size()));
            });
            Vec3 to = ctx.computeOnClient(mc -> mc.player.position());
            double moved = from.distanceTo(to);
            List<Integer> gaps = new ArrayList<>();
            for (int i = 1; i < wire.size(); i++) {
                gaps.add(wire.get(i) - wire.get(i - 1));
            }
            List<Long> ownGaps = new ArrayList<>();
            for (int i = 1; i < own.size(); i++) {
                ownGaps.add(own.get(i) - own.get(i - 1));
            }
            Map<Integer, Integer> histogram = new TreeMap<>();
            for (int g : gaps) {
                histogram.merge(g, 1, Integer::sum);
            }
            println(String.format("gap %d: press to room %d (tile %d) %s; %d hop(s) on the wire at ticks %s, gaps %s"
                            + " (histogram %s); executor's record %d hop(s), gaps %s; moved %.1f blocks", gap, t[1], t[0],
                    Boolean.TRUE.equals(ok) ? "taken" : "REFUSED", wire.size(), wire, gaps, histogram, own.size(),
                    ownGaps, moved));
            if (wire.size() < 3 || moved < 20) {
                continue;   // a room too close or unreachable: try the next farthest
            }
            int exact = histogram.getOrDefault(gap, 0);
            int under = 0;
            for (int g : gaps) {
                under += g < gap ? 1 : 0;
            }
            if (under > 0) {
                failures.add("gap " + gap + ": " + under + " hop(s) went out sooner than " + gap + " tick(s) after the last: " + gaps);
            }
            if (exact * 10 < gaps.size() * 8) {
                failures.add("gap " + gap + ": only " + exact + " of " + gaps.size() + " gaps are exactly " + gap + ": " + gaps);
            }
            return;
        }
        failures.add("gap " + gap + ": no press made 3+ hops over 20+ blocks to measure");
    }

    @SuppressWarnings("unchecked")
    static List<Long> hopTicks() {
        return (List<Long>) ModUnderTest.staticCall(EXECUTOR, "hopSendTicks");
    }

    /** {tile, room} of every room but his own, farthest first (by the first tile's centre). */
    static List<int[]> farthestRooms(Minecraft mc) {
        Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
        int rooms = (Integer) ModUnderTest.call(layout, "roomCount", new Class<?>[]{}, new Object[]{});
        int current = (Integer) ModUnderTest.call(layout, "currentRoom", new Class<?>[]{}, new Object[]{});
        List<double[]> scored = new ArrayList<>();
        for (int r = 0; r < rooms; r++) {
            if (r == current) {
                continue;
            }
            int[] tiles = (int[]) ModUnderTest.call(layout, "tiles", new Class<?>[]{int.class}, new Object[]{r});
            if (tiles.length == 0) {
                continue;
            }
            BlockPos c = (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class},
                    new Object[]{tiles[0]});
            double d = Math.hypot(c.getX() - mc.player.getX(), c.getZ() - mc.player.getZ());
            scored.add(new double[]{tiles[0], r, d});
        }
        scored.sort((a, b) -> Double.compare(b[2], a[2]));
        List<int[]> out = new ArrayList<>();
        for (double[] s : scored) {
            out.add(new int[]{(int) s[0], (int) s[1]});
        }
        return out;
    }

    static void giveAotv(ClientGameTestContext ctx, List<String> failures) {
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
        String held = ctx.computeOnClient(mc -> mc.player.getMainHandItem().getHoverName().getString());
        if (!held.contains("Aspect of the Void")) {
            failures.add("the Aspect of the Void never reached his hand (" + held + ")");
        }
    }

    static void awaitFloorChunks(ClientGameTestContext ctx) {
        for (int waited = 0; waited < 1800; waited += 10) {
            int missing = ctx.computeOnClient(mc -> {
                BlockPos first = (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class},
                        new Object[]{0});
                BlockPos last = (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class},
                        new Object[]{GRID * GRID - 1});
                int n = 0;
                for (int cx = (first.getX() - 16) >> 4; cx <= (last.getX() + 16) >> 4; cx++) {
                    for (int cz = (first.getZ() - 16) >> 4; cz <= (last.getZ() + 16) >> 4; cz++) {
                        if (!mc.level.hasChunk(cx, cz)) {
                            n++;
                        }
                    }
                }
                return n;
            });
            if (missing == 0) {
                return;
            }
            ctx.waitTicks(10);
        }
    }

    private static void println(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}
