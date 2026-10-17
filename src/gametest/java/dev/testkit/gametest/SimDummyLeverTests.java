package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.harness.PacketTrace;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 1017-sim-dummy-lever-*: Red Blue's two dummy levers (mod aura-dummy-levers-1017). killer560 (2026-10-17), inside the
 * room's iron-bar cage: "in red blue dont have secret aura ever grab these two levers they are dummy levers. Only the
 * one outside of the cage". The dummies are the wall levers either side of the nook's chest (capture 57,70,6 and
 * 60,70,6, chest 58,70,6); the real one is the floor lever outside the cage (capture 55,69,6).
 *
 * <p>Red Blue is built on a two-room floor at two rotations (a horizontal 1x3 at 0, a vertical one at 90), so the
 * room-relative list is checked through a real clay corner and turn each time. He stands inside the cage, every one of
 * the three levers within 3.5 blocks eye to box. Per rotation:
 * <ul>
 *   <li>premise: the three levers and the chest are on the server where the capture says; the mod's resolved dummy list
 *       ({@code secrets.DummyLevers.worldPositions}) is exactly the two capture dummies;</li>
 *   <li>Secret Waypoints lists the real lever and neither dummy;</li>
 *   <li>Secret Aura (levers only) sends no use_item_on packet at either dummy, and the server never handles a click
 *       on one, while it DOES click the real lever (the nearest lever is a dummy, so the old jar clicks a dummy
 *       first);</li>
 *   <li>a click on a dummy by hand counts nothing: the sim's room count and Auto Routes' run memory stay put, while
 *       the aura's click on the real lever is in the run memory (the counting path was live).</li>
 * </ul>
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimDummyLeverTests implements FabricClientGameTest {

    private static final String NAME = "1017-sim-dummy-lever";
    private static final String ROOM = "Red Blue";
    private static final String[] CASES = {"1017-sim-dummy-lever-r0", "1017-sim-dummy-lever-r90"};

    private static final String BUILDER = "com.killer560.hub.roomsim.SimBuilder";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String SIM_LEVERS = "com.killer560.hub.roomsim.SimRoomLevers";
    private static final String SIM_SCORE = "com.killer560.hub.roomsim.SimScore";
    private static final String SIM_INDEX = "com.killer560.hub.roomsim.SimRoomIndex";
    private static final String MAP_CODE = "com.killer560.hub.roomsim.MapCode";
    private static final String DUMMY = "com.killer560.hub.secrets.DummyLevers";
    private static final String WAYPOINTS = "com.killer560.hub.secretwaypoints.SecretWaypointsFeature";
    private static final String WAYPOINTS_CFG = "com.killer560.hub.secretwaypoints.SecretWaypointsConfig";
    private static final String CHEAT_CFG = "com.killer560.hub.cheatutils.CheatUtilsConfig";
    private static final String RUN_MEMORY = "com.killer560.hub.autoroutes.RouteRunMemory";

    /** Capture-local blocks (rooms/Red_Blue.json). */
    private static final int[][] DUMMIES = {{57, 70, 6}, {60, 70, 6}};
    private static final int[] REAL = {55, 69, 6};
    private static final int[] CHEST = {58, 70, 6};
    /** Cage floor columns to stand on (feet y 69, cobblestone under). */
    private static final int[][] CAGE = {{58, 8}, {59, 8}, {58, 9}, {59, 9}, {57, 8}, {60, 8}};

    private static UUID playerId;
    /** Every right click the integrated server handled, as BlockPos longs. */
    private static final Set<Long> SERVER_USES = ConcurrentHashMap.newKeySet();
    /** Every use_item_on packet's block, as the client sent it. */
    private static final List<BlockPos> SENT_USES = new CopyOnWriteArrayList<>();
    private static boolean hooked;
    private static final List<String> RESULTS = new ArrayList<>();

    @Override
    public void runTest(ClientGameTestContext ctx) {
        List<String> selected = new ArrayList<>();
        for (String c : CASES) {
            if (!Scenario.skip(c)) {
                selected.add(c);
            }
        }
        if (selected.isEmpty()) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(20);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        auraOff(ctx);
        if (SimMapTests.copyRoomsForOthers() < 20) {
            for (String c : selected) {
                Scenario.skipped(c, "needs room captures and room database");
            }
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);
        hook();
        try {
            for (String c : selected) {
                long mark = LogTap.mark();
                try {
                    say("---- " + c + " ----");
                    runCase(ctx, c, c.endsWith("r90") ? 90 : 0);
                    RESULTS.add(c + " PASS");
                    System.out.println("[" + c + "] PASS");
                } catch (Throwable t) {
                    RESULTS.add(c + " FAIL: " + t.getMessage());
                    System.out.println("[" + c + "] FAIL: " + t);
                    List<String> since = LogTap.since(mark);
                    for (String l : since.subList(Math.max(0, since.size() - 40), since.size())) {
                        System.out.println("[" + c + "]   log: " + l);
                    }
                    dev.testkit.harness.SuiteVerdict.failCase(c, t);
                    if (ctx.computeOnClient(mc -> mc.level == null)) {
                        break;
                    }
                } finally {
                    auraOff(ctx);
                    PacketTrace.tap = null;
                }
            }
        } finally {
            teardown(ctx);
        }
        say("SUMMARY");
        for (String r : RESULTS) {
            say("  " + r);
        }
    }

    private void runCase(ClientGameTestContext ctx, String c, int rotation) {
        buildFloor(ctx, rotation);
        String placed = ctx.computeOnClient(mc -> {
            StringBuilder sb = new StringBuilder();
            for (Object p : (List<?>) ModUnderTest.staticCall(SIM_INDEX, "placed")) {
                sb.append(p).append("; ");
            }
            return sb.toString();
        });
        say(c + ": placed " + placed);

        // Premise: the levers and the chest are where the capture says.
        BlockPos d1 = capW(ctx, DUMMIES[0]);
        BlockPos d2 = capW(ctx, DUMMIES[1]);
        BlockPos real = capW(ctx, REAL);
        BlockPos chest = capW(ctx, CHEST);
        String blocks = onServer(ctx, (level, sp) -> name(level, d1) + " " + name(level, d2) + " " + name(level, real)
                + " " + name(level, chest));
        say(c + ": dummies " + d1.toShortString() + ", " + d2.toShortString() + "; real " + real.toShortString()
                + "; chest " + chest.toShortString() + " -> server has " + blocks);
        // The chest may be the floor's mimic (a trapped chest) - it only places the capture.
        check(blocks.equals("lever lever lever chest") || blocks.equals("lever lever lever trapped_chest"),
                "the capture's levers and chest are not on the server: " + blocks);

        boolean hasList = ctx.computeOnClient(mc -> {
            try {
                Class.forName(DUMMY);
                return true;
            } catch (ClassNotFoundException e) {
                return false;
            }
        });
        if (hasList) {
            @SuppressWarnings("unchecked")
            List<BlockPos> resolved = ctx.computeOnClient(mc -> (List<BlockPos>) ModUnderTest.staticCall(DUMMY, "worldPositions"));
            @SuppressWarnings("unchecked")
            List<BlockPos> live = ctx.computeOnClient(mc -> (List<BlockPos>) ModUnderTest.staticCall(DUMMY, "liveMapPositions"));
            say(c + ": DummyLevers resolves " + resolved + "; through the live map (Hypixel's path) " + live);
            check(new HashSet<>(resolved).equals(Set.of(d1, d2)), "the resolved dummy list " + resolved
                    + " is not the capture's two dummies " + d1.toShortString() + ", " + d2.toShortString());
            check(live.isEmpty() || new HashSet<>(live).equals(Set.of(d1, d2)),
                    "the live map's resolution " + live + " disagrees with the sim's");
        } else {
            // A jar from before the list (the control): the behaviour checks below still decide.
            say(c + ": this jar has no secrets.DummyLevers");
        }

        // Stand inside the cage, every lever in reach.
        BlockPos feet = null;
        for (int[] col : CAGE) {
            BlockPos f = capW(ctx, new int[]{col[0], 69, col[1]});
            boolean ok = onServer(ctx, (level, sp) -> level.getBlockState(f).getCollisionShape(level, f).isEmpty()
                    && level.getBlockState(f.above()).getCollisionShape(level, f.above()).isEmpty()
                    && !level.getBlockState(f.below()).getCollisionShape(level, f.below()).isEmpty());
            Vec3 eye = new Vec3(f.getX() + 0.5, f.getY() + 1.62, f.getZ() + 0.5);
            if (ok && box(eye, d1) <= 3.5 && box(eye, d2) <= 3.5 && box(eye, real) <= 3.5) {
                feet = f;
                break;
            }
        }
        check(feet != null, "no standing spot in the cage with all three levers within 3.5");
        BlockPos at = feet;
        Vec3 eye = new Vec3(at.getX() + 0.5, at.getY() + 1.62, at.getZ() + 0.5);
        say(String.format(Locale.ROOT, "%s: standing at %s, eye to box: dummy %.2f / %.2f, real %.2f", c,
                at.toShortString(), box(eye, d1), box(eye, d2), box(eye, real)));
        tp(ctx, at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        ctx.waitFor(mc -> mc.player != null && mc.player.onGround()
                && Math.abs(mc.player.getX() - (at.getX() + 0.5)) < 0.3
                && Math.abs(mc.player.getZ() - (at.getZ() + 0.5)) < 0.3
                && mc.level.getBlockState(d1).is(Blocks.LEVER) && mc.level.getBlockState(real).is(Blocks.LEVER), 600);
        String room = ctx.computeOnClient(mc -> {
            Object e = ModUnderTest.staticCall("com.killer560.hub.livemap.LiveMapFeature", "currentRoomEntry");
            try {
                return e == null ? "null" : String.valueOf(e.getClass().getField("name").get(e));
            } catch (ReflectiveOperationException ex) {
                return "?";
            }
        });
        say(c + ": the live map puts him in " + room);

        // Secret Waypoints: the real lever is listed, the dummies are not.
        ctx.runOnClient(mc -> {
            Object cfg = ModUnderTest.staticCall(WAYPOINTS_CFG, "getInstance");
            ModUnderTest.set(cfg, "setEnabled", true);
            ModUnderTest.staticCall(WAYPOINTS, "invalidateCache");
        });
        List<BlockPos> levers = List.of();
        for (int t = 0; t < 80; t++) {
            ctx.waitTicks(1);
            levers = waypointLevers(ctx);
            if (levers.contains(real)) {
                break;
            }
        }
        say(c + ": Secret Waypoints' lever waypoints " + levers);
        check(levers.contains(real), "Secret Waypoints never listed the real lever " + real.toShortString()
                + " (so nothing was measured): " + levers);
        // Held to the end, so a failing jar still shows what the aura does.
        List<String> failed = new ArrayList<>();
        if (levers.contains(d1) || levers.contains(d2)) {
            failed.add("Secret Waypoints shows a dummy lever: " + levers);
        }

        // Secret Aura, levers only: no packet at a dummy, the real lever clicked.
        int memClock = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(RUN_MEMORY, "clock"));
        SERVER_USES.clear();
        SENT_USES.clear();
        PacketTrace.tap = p -> {
            if (p instanceof ServerboundUseItemOnPacket u) {
                SENT_USES.add(u.getHitResult().getBlockPos().immutable());
            }
        };
        ctx.runOnClient(mc -> {
            Object cfg = ModUnderTest.config(CHEAT_CFG);
            ModUnderTest.set(cfg, "setAuraChests", false);
            ModUnderTest.set(cfg, "setAuraLevers", true);
            ModUnderTest.set(cfg, "setAuraEssence", false);
            ModUnderTest.set(cfg, "setAuraPauseWhileSneaking", false);
            ModUnderTest.set(cfg, "setAuraRange", 4.5);
            ModUnderTest.set(cfg, "setSecretAuraEnabled", true);
            if (!ModUnderTest.getBoolean(cfg, "isSecretAuraEnabled")) {
                throw new AssertionError("isSecretAuraEnabled() is false - needs the cheat jar");
            }
        });
        int waited = 0;
        while (waited < 200 && !SERVER_USES.contains(real.asLong())) {
            ctx.waitTicks(5);
            waited += 5;
        }
        // Then long enough for every retry and cooldown the aura has, for a dummy click to show if it is coming.
        ctx.waitTicks(80);
        auraOff(ctx);
        ctx.waitTicks(5);
        PacketTrace.tap = null;
        long toD = SENT_USES.stream().filter(p -> p.equals(d1) || p.equals(d2)).count();
        long toReal = SENT_USES.stream().filter(p -> p.equals(real)).count();
        boolean serverDummy = SERVER_USES.contains(d1.asLong()) || SERVER_USES.contains(d2.asLong());
        boolean serverReal = SERVER_USES.contains(real.asLong());
        say(c + ": Secret Aura sent use_item_on " + SENT_USES + " (dummies " + toD + ", real " + toReal
                + "); server handled a dummy click " + serverDummy + ", the real lever " + serverReal
                + " (after ~" + waited + " ticks)");
        if (toD != 0) {
            failed.add(toD + " use_item_on packet(s) went to a dummy lever");
        }
        if (serverDummy) {
            failed.add("the server handled a click on a dummy lever");
        }
        check(failed.isEmpty(), String.join(" | ", failed));
        check(toReal >= 1 && serverReal, "Secret Aura never clicked the real lever, so nothing was measured");
        int realInMemory = memoryNear(ctx, memClock, real);
        say(c + ": run memory near the real lever since the aura went on: " + realInMemory);
        check(realInMemory >= 1, "the aura's real-lever click is not in Auto Routes' run memory (counting path dead)");

        // A dummy clicked by hand counts nothing, on the sim's server or in the run memory.
        int foundBefore = foundIn(ctx);
        int clock2 = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(RUN_MEMORY, "clock"));
        SERVER_USES.clear();
        ctx.runOnClient(mc -> mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(d1), Direction.SOUTH, d1, false)));
        ctx.waitTicks(20);
        int foundAfter = foundIn(ctx);
        int memDummy = memoryNear(ctx, clock2, d1);
        boolean handled = SERVER_USES.contains(d1.asLong());
        say(c + ": a hand click on dummy " + d1.toShortString() + ": server handled it " + handled + ", room count "
                + foundBefore + " -> " + foundAfter + ", run memory near it " + memDummy);
        check(handled, "the hand click on the dummy never reached the server (nothing measured)");
        check(foundAfter == foundBefore, "a dummy lever counted as a secret in the sim's room count");
        check(memDummy == 0, "a dummy lever was counted in Auto Routes' run memory");
    }

    // ============================================================================================ helpers

    private static List<BlockPos> waypointLevers(ClientGameTestContext ctx) {
        @SuppressWarnings("unchecked")
        List<BlockPos> l = ctx.computeOnClient(mc -> (List<BlockPos>) ModUnderTest.staticCall(WAYPOINTS, "cachedPositions",
                new Class<?>[]{String.class}, new Object[]{"LEVER"}));
        return l;
    }

    private static int memoryNear(ClientGameTestContext ctx, int since, BlockPos p) {
        Vec3 c = Vec3.atCenterOf(p);
        java.util.function.Predicate<Vec3> near = v -> v.distanceTo(c) < 0.75;
        return ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(RUN_MEMORY, "gotSince",
                new Class<?>[]{int.class, java.util.function.Predicate.class}, new Object[]{since, near}));
    }

    private static int foundIn(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(SIM_SCORE, "foundInRoom",
                new Class<?>[]{String.class}, new Object[]{ROOM}));
    }

    /** Eye to the block's box. */
    private static double box(Vec3 e, BlockPos b) {
        double cx = Math.max(b.getX(), Math.min(e.x, b.getX() + 1));
        double cy = Math.max(b.getY(), Math.min(e.y, b.getY() + 1));
        double cz = Math.max(b.getZ(), Math.min(e.z, b.getZ() + 1));
        return Math.sqrt((e.x - cx) * (e.x - cx) + (e.y - cy) * (e.y - cy) + (e.z - cz) * (e.z - cz));
    }

    private static String name(ServerLevel level, BlockPos p) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(p).getBlock()).getPath();
    }

    /**
     * Red Blue (1x3) and Dueces on an 11x11 grid: at rotation 0 Red Blue spans cells x 2..6 of row 4, at 90 cells z 2..6
     * of column 4; Dueces two cells past its end with a normal door between.
     */
    private static void buildFloor(ClientGameTestContext ctx, int rotation) {
        int grid = 11;
        int[] cellRoom = new int[grid * grid];
        java.util.Arrays.fill(cellRoom, -1);
        int[] cellDoor = new int[grid * grid];
        int[] cellRotation = new int[grid * grid];
        boolean vertical = rotation == 90 || rotation == 270;
        for (int i = 2; i <= 6; i++) {
            int idx = vertical ? i * grid + 4 : 4 * grid + i;
            cellRoom[idx] = 0;
            cellRotation[idx] = rotation;
        }
        cellRoom[vertical ? 8 * grid + 4 : 4 * grid + 8] = 1;
        cellDoor[vertical ? 7 * grid + 4 : 4 * grid + 7] = 1; // DungeonLayout.DOOR_NORMAL
        String code = ctx.computeOnClient(mc -> {
            try {
                Class<?> dec = Class.forName(MAP_CODE + "$Decoded");
                Object d = dec.getConstructor(String[].class, int[].class, int[].class, int[].class)
                        .newInstance(new String[]{ROOM, "Dueces"}, cellRoom, cellDoor, cellRotation);
                return (String) ModUnderTest.staticCall(MAP_CODE, "encodeDecoded", new Class<?>[]{dec}, new Object[]{d});
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        });
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> ModUnderTest.staticCall(BUILDER, "build",
                new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, code})));
        ctx.waitFor(mc -> mc.level != null && mc.player != null, 2400);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitFor(mc -> McCompat.screen(mc) == null, 1200);
        for (int t = 0; t < 1800; t++) {
            boolean ok = ctx.computeOnClient(mc -> mc.player != null && mc.player.onGround()
                    && !mc.level.getBlockState(mc.player.blockPosition().below()).isAir());
            if (ok) {
                break;
            }
            ctx.waitTicks(1);
        }
        ctx.waitTicks(40);
        playerId = ctx.computeOnClient(mc -> mc.player.getUUID());
        say("built Red Blue at rotation " + rotation + " (" + (vertical ? "vertical" : "horizontal") + ") beside Dueces");
    }

    private static BlockPos capW(ClientGameTestContext ctx, int[] c) {
        BlockPos p = ctx.computeOnClient(mc -> (BlockPos) ModUnderTest.staticCall(SIM_LEVERS, "capturedWorld",
                new Class<?>[]{String.class, int.class, int.class, int.class}, new Object[]{ROOM, c[0], c[1], c[2]}));
        check(p != null, ROOM + " is not placed");
        return p;
    }

    private static void tp(ClientGameTestContext ctx, double x, double y, double z) {
        onServer(ctx, (level, sp) -> {
            sp.teleportTo(level, x, y, z, Set.<net.minecraft.world.entity.Relative>of(), 0f, 0f, false);
            return null;
        });
        ctx.waitTicks(10);
    }

    private static void hook() {
        if (hooked) {
            return;
        }
        hooked = true;
        var phase = net.minecraft.resources.Identifier.fromNamespaceAndPath("testkit", "dummy_lever_watch");
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.addPhaseOrdering(phase,
                net.fabricmc.fabric.api.event.Event.DEFAULT_PHASE);
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register(phase, (player, level, hand, hit) -> {
            if (!level.isClientSide() && player instanceof ServerPlayer && hit != null) {
                SERVER_USES.add(hit.getBlockPos().asLong());
            }
            return InteractionResult.PASS;
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T onServer(ClientGameTestContext ctx, java.util.function.BiFunction<ServerLevel, ServerPlayer, T> f) {
        AtomicReference<Object[]> box = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            UUID id = playerId != null ? playerId : mc.player.getUUID();
            server.execute(() -> {
                try {
                    box.set(new Object[]{f.apply(server.overworld(), server.getPlayerList().getPlayer(id)), null});
                } catch (Throwable t) {
                    box.set(new Object[]{null, t});
                }
            });
        });
        ctx.waitFor(mc -> box.get() != null, 400);
        if (box.get()[1] != null) {
            throw new AssertionError("server task threw", (Throwable) box.get()[1]);
        }
        return (T) box.get()[0];
    }

    private static void auraOff(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            try {
                ModUnderTest.set(ModUnderTest.config(CHEAT_CFG), "setSecretAuraEnabled", false);
            } catch (Throwable ignored) {
                // cleanup never replaces a verdict
            }
        });
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

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }

    private static void say(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}
