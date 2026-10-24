package dev.testkit.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.testkit.compat.McCompat;
import dev.testkit.harness.ClickPrimer;
import dev.testkit.harness.HypixelUseAfterClick;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 1015-sim-pathland (mod branch path-wrongblock-1015). killer560, 2026-10-15 (sim): "sometimes the path nodes are still
 * taking me to the wrong block as well. It is far less frequent now but still happens."
 *
 * <p>Auto Routes PATH nodes flown over generated sim floors, every hop judged against its PLAN: the block each saved warp
 * was planned onto ({@code RouteNode.PathHop.target}) against the block the server's landing put him on. The mod's own
 * check ("Etherwarp: landed at") takes any landing within 2 blocks of the planned feet, so a hop that lands on the block
 * NEXT to its plan counts as landed and the path carries on; this scenario reads the plan itself and counts those.
 *
 * <p>Each room gets one route of chained pairs - P0 (start) to P1, P2 stacked on P1's tile to P3, and so on - so every
 * pair after the first fires on the very tick the last pair landed, 0.05 over the block its first warp was planned from,
 * which is what re-aims a path's first warp at run time (killer560's log: "Etherwarp (path hop 1): re-aimed from ...
 * (0.05 off ...)"). A second pass flies the same saved plans with {@link ClickPrimer} clicking a block after every use
 * (Secret Aura's chest click, unpaired), which holds hops a tick and re-aims them when the server's ray and the client's
 * look disagree ({@code RightClick.reaimIfUnpairable}). A pass is not vacuous: it must fly 25+ hops, and the re-aims it
 * exists for must have happened. Pass: no hop on a block other than its plan's. Only when named; cheat jar, room captures.
 * {@code -PpathlandFloors=N} (default 3) floors, {@code -PpathlandSeed=S}.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimPathLandTests implements FabricClientGameTest {

    private static final String NAME = "1015-sim-pathland";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String TELEPORT_UTILS = "com.killer560.hub.livemap.autoclear.TeleportUtils";
    private static final String LIVE_MAP_CONFIG = "com.killer560.hub.livemap.LiveMapConfig";
    private static final String STORE = "com.killer560.hub.autoroutes.RouteStore";
    private static final String COORDS = "com.killer560.hub.autoroutes.RouteCoords";
    private static final String FRAME = "com.killer560.hub.autoroutes.RouteCoords$Frame";
    private static final String AR_CONFIG = "com.killer560.hub.autoroutes.AutoRoutesConfig";
    private static final String EXECUTOR = "com.killer560.hub.autoroutes.RouteExecutor";
    private static final String MEMORY = "com.killer560.hub.autoroutes.RouteRunMemory";
    private static final String OVERLAY = "com.killer560.hub.etherwarpoverlay.EtherwarpOverlayFeature";
    private static final int ROOMS_PER_FLOOR = 10;

    private static final Pattern ACTED = Pattern.compile("\\[AutoRoutes\\] Node #(\\d+) PATH acted .*\\(path warp (\\d+)/(\\d+)\\)");
    private static final Pattern LANDED = Pattern.compile("\\[AutoRoutes\\] Etherwarp: landed at \\((-?[\\d.]+), (-?[\\d.]+), (-?[\\d.]+)\\)");
    private static final Pattern SAVED_FROM = Pattern.compile("firing the saved look from \\(-?[\\d.]+, (-?[\\d.]+), -?[\\d.]+\\)");
    private static final Pattern CORRECTED = Pattern.compile("during path #(\\d+)'s warp (\\d+) \\(([\\d.]+) from its landing\\)");

    /** One room's route: its frame (name, clay, rotation), where each PATH node stands (real feet), and the real look of
     *  each ETHERWARP node between pairs. */
    record RoomRoute(String name, int[] frame, List<Vec3> stands, List<float[]> aims) {
    }

    /** One flown hop: route node (1-based), hop (1-based), where he landed (null: never landed), why. */
    record Hop(String room, int node, int hop, Vec3 landed, String note) {
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank() || Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ClickPrimer.install();
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
            Scenario.skipped(NAME, "legit jar: no Auto Routes");
            return;
        }
        if (SimMapTests.copyRoomsForOthers() < 20) {
            Scenario.skipped(NAME, "needs real room captures and room database");
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);
        int floors = Integer.getInteger("testkit.pathlandFloors", 3);
        long seed = Long.getLong("testkit.pathlandSeed", 1015L);
        int renderBefore = ctx.computeOnClient(mc -> mc.options.renderDistance().get());
        List<String> failures = new ArrayList<>();
        int[] totals = new int[11];   // hops, wrong block, never landed, hop-1 re-aims, unpairable re-aims, rooms,
                                     // aim probes, fragile aims, fragile aims with a firm one to be had,
                                     // saved looks fired from above their spot, probe: no run-time aim at all
        try {
            ctx.runOnClient(mc -> {
                ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autopuzzles.AutoPuzzlesConfig"),
                        "setAutoPuzzlesMasterEnabled", false);
                Object map = ModUnderTest.config(LIVE_MAP_CONFIG);
                ModUnderTest.set(map, "setEnabled", true);
                ModUnderTest.set(map, "setInteractiveMapEnabled", true);
                Object ar = ModUnderTest.config(AR_CONFIG);
                ModUnderTest.set(ar, "setEnabled", true);
                ModUnderTest.set(ar, "setLegitMode", false);
                ModUnderTest.set(ar, "setStartFromStartNodeOnly", true);
                ModUnderTest.set(ar, "setChatFeedback", true);
                mc.options.renderDistance().set(16);
            });
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter", new Class<?>[]{String.class},
                    new Object[]{"gametest"}));
            Random rng = new Random(seed);
            for (int f = 1; f <= floors; f++) {
                floor(ctx, f, rng, failures, totals);
            }
        } catch (Throwable t) {
            failures.add("scenario threw: " + t);
            t.printStackTrace(System.out);
        } finally {
            ctx.runOnClient(mc -> {
                ClickPrimer.reset();
                HypixelUseAfterClick.reset();
                ModUnderTest.staticCall(EXECUTOR, "stop", new Class<?>[]{String.class}, new Object[]{"test over"});
            });
            SimAutoSecretTests.writeRoutes(ctx, Map.of());
            ctx.runOnClient(mc -> {
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
        println(String.format(Locale.US, "TOTAL over %d floor(s), %d room route(s): %d hop(s) flown, %d on a block other "
                        + "than planned, %d never landed; %d first-warp re-aim(s) from a landing, %d unpairable re-aim(s)",
                floors, totals[5], totals[0], totals[1], totals[2], totals[3], totals[4]));
        if (totals[0] < 25 * floors) {
            failures.add("only " + totals[0] + " hop(s) flown over " + floors + " floor(s) - too few to measure");
        }
        if (totals[3] == 0) {
            failures.add("no path's first warp was re-aimed from a landing - the case this exists for never happened");
        }
        if (totals[1] > 0) {
            failures.add(totals[1] + " hop(s) landed on a block other than the one planned");
        }
        println(String.format(Locale.US, "AIM PROBE: %d run-time etherwarp aim(s) from a landing (0.05 over a block center), "
                + "%d fragile (miss the block within 0.01 degrees), %d of them with a firm aim to be had", totals[6], totals[7],
                totals[8]));
        if (totals[6] < 100) {
            failures.add("aim probe: only " + totals[6] + " aim(s) worked out - too few to measure");
        }
        println(String.format(Locale.US, "SAVED LOOK: %d warp(s) fired their saved look from above the spot it was planned "
                + "from; aim probe: %d pair(s) with no run-time aim from 0.05 up (the saved look goes then)", totals[9], totals[10]));
        if (totals[9] > 0) {
            failures.add(totals[9] + " warp(s) fired their saved look from 0.05 over the spot it was planned from");
        }
        if (totals[8] * 100 > totals[6]) {
            failures.add("aim probe: " + totals[8] + " of " + totals[6] + " run-time aims were fragile while a firm aim existed");
        }
        if (!failures.isEmpty()) {
            for (String s : failures) {
                println("FAIL: " + s);
            }
            throw new AssertionError(failures.size() + " problem(s): " + String.join("; ", failures));
        }
        println("PASS");
    }

    private static void floor(ClientGameTestContext ctx, int f, Random rng, List<String> failures, int[] totals) {
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
        ctx.waitFor(mc -> mc.player.onGround() && !mc.level.getBlockState(mc.player.blockPosition().below()).isAir(), 2400);
        SimAutoSecretTests.awaitFloorChunks(ctx);
        SimAutoSecretTests.giveAotv(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimRun", "begin",
                new Class<?>[]{Minecraft.class, BlockPos.class},
                new Object[]{mc, ModUnderTest.staticCall("com.killer560.hub.roomsim.SimBuilder", "entranceDoor")}));
        for (int i = 0; i < 400 && !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(
                "com.killer560.hub.roomsim.SimRun", "isRunning")); i++) {
            ctx.waitTicks(1);
        }
        ctx.waitTicks(100);   // the floor graph warms (Interactive Map on, in a dungeon)

        List<SimAutoSecretTests.Room> rooms = ctx.computeOnClient(SimAutoSecretTests::readRooms);
        List<RoomRoute> routes = new ArrayList<>();
        Map<String, JsonObject> json = new HashMap<>();
        java.util.Collections.shuffle(rooms, rng);
        for (SimAutoSecretTests.Room r : rooms) {
            if (routes.size() >= ROOMS_PER_FLOOR) {
                break;
            }
            if (r.name().equals("Unknown") || r.type().equals("PUZZLE") || r.type().equals("TRAP")
                    || r.type().equals("BLOOD") || r.type().equals("ENTRANCE") || !SimAutoSecretTests.pathsOut(r)) {
                continue;
            }
            long s = rng.nextLong();
            RoomRoute rr = ctx.computeOnClient(mc -> routeIn(mc, r, new Random(s)));
            if (rr == null) {
                continue;
            }
            routes.add(rr);
            json.put(rr.name(), toJson(ctx, rr));
        }
        for (RoomRoute rr : routes) {
            long s = rng.nextLong();
            int[] pr = ctx.computeOnClient(mc -> probe(mc, rr, new Random(s)));
            for (int i = 0; i < 3; i++) {
                totals[6 + i] += pr[i];
            }
            totals[10] += pr[3];
        }
        println("floor " + f + ": " + rooms.size() + " room(s), routes in " + routes.size() + ": "
                + routes.stream().map(RoomRoute::name).toList());
        SimAutoSecretTests.writeRoutes(ctx, json);
        ctx.waitTicks(10);
        // Pass 1 plans each pair as it is reached (he stands still while it plans, so its first warp goes from the spot it
        // was planned from); pass 2 flies the saved plans, each next pair on the landing tick; pass 3 the same with clicks.
        for (int pass = 1; pass <= 3; pass++) {
            for (RoomRoute rr : routes) {
                flyRoom(ctx, f, rr, pass, failures, totals);
            }
        }
        totals[5] += routes.size();
    }

    /**
     * The route's stands, as real feet: S0 (start PATH) -> S1 (arrival PATH), an ETHERWARP node on S1 onto S2, S2 (PATH)
     * -> S3, an ETHERWARP on S3 onto S4, S4 -> S5. The etherwarp lands him on the next pair's source, so that pair's first
     * warp goes on the landing tick, 0.05 over the spot it was planned from (killer560's log: an ETHERWARP node landing
     * on a PATH node, "Etherwarp (path hop 1): re-aimed from ... (0.05 off ...)"). Landable full blocks of the room's main
     * tile, 7+ blocks apart; each etherwarp's look is a firm one (lands for every look 0.02 degrees either way).
     */
    private static RoomRoute routeIn(Minecraft mc, SimAutoSecretTests.Room r, Random rng) {
        Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
        int[] cr = (int[]) ModUnderTest.call(layout, "clayRotation", new Class<?>[]{int.class}, new Object[]{r.id()});
        if (cr == null) {
            return null;
        }
        BlockPos centre = (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class},
                new Object[]{r.mainTile()});
        List<BlockPos> all = new ArrayList<>();
        for (int x = centre.getX() - 13; x <= centre.getX() + 13; x++) {
            for (int z = centre.getZ() - 13; z <= centre.getZ() + 13; z++) {
                for (int y = centre.getY() + 25; y >= centre.getY() - 30; y--) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (mc.level.getBlockState(p).isAir()) {
                        continue;
                    }
                    if ((Boolean) ModUnderTest.staticCall(TELEPORT_UTILS, "landable", new Class<?>[]{BlockPos.class},
                            new Object[]{p}) && mc.level.getBlockState(p).isCollisionShapeFullBlock(mc.level, p)) {
                        all.add(p);
                    }
                }
            }
        }
        java.util.Collections.shuffle(all, rng);
        List<Vec3> stands = new ArrayList<>();
        List<float[]> aims = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Vec3 pick = null;
            float[] aim = null;
            for (BlockPos p : all) {
                Vec3 feet = new Vec3(p.getX() + 0.5, p.getY() + 1.0, p.getZ() + 0.5);
                boolean far = true;
                for (Vec3 s : stands) {
                    far &= s.distanceTo(feet) >= 7.0;
                }
                if (!far) {
                    continue;
                }
                if (i == 2 || i == 4) {
                    Vec3 from = stands.get(i - 1);
                    if (from.distanceTo(feet) > 25.0) {
                        continue;
                    }
                    aim = firmAim(new Vec3(from.x, from.y + 1.27, from.z), p);
                    if (aim == null) {
                        continue;
                    }
                }
                pick = feet;
                break;
            }
            if (pick == null) {
                break;
            }
            stands.add(pick);
            if (aim != null) {
                aims.add(aim);
            }
        }
        // Whole pairs only, each pair after the first reached by its etherwarp.
        while (stands.size() % 2 != 0 || aims.size() < stands.size() / 2 - 1) {
            stands.remove(stands.size() - 1);
        }
        while (aims.size() > Math.max(0, stands.size() / 2 - 1)) {
            aims.remove(aims.size() - 1);
        }
        return stands.size() < 4 ? null : new RoomRoute(r.name(), cr, stands, aims);
    }

    /**
     * The mechanism under the wrong-block hops, measured where it happens: the mod's own run-time etherwarp aim
     * ({@code TeleportUtils.getEtherwarpDirection}, what a path hop or etherwarp node fired 0.05 over its spot re-aims
     * with) from 0.05 over a block centre to another landable block of the room. An aim is FRAGILE when it lands for its
     * exact float values but not for a look 0.01 degrees off (the server reads the running yaw a few float bits off - the
     * planner's own {@code EtherSearch.AIM_MARGIN}); such an aim goes into the neighbouring block a coin toss of the time.
     * @return {aims, fragile, fragile with a firm aim to be had}. Client thread.
     */
    private static int[] probe(Minecraft mc, RoomRoute rr, Random rng) {
        List<BlockPos> all = new ArrayList<>();
        for (Vec3 s : rr.stands()) {
            BlockPos c = BlockPos.containing(s.x, s.y - 1, s.z);
            for (int x = c.getX() - 12; x <= c.getX() + 12; x++) {
                for (int z = c.getZ() - 12; z <= c.getZ() + 12; z++) {
                    for (int y = c.getY() - 8; y <= c.getY() + 8; y++) {
                        BlockPos p = new BlockPos(x, y, z);
                        if (!mc.level.getBlockState(p).isAir() && !all.contains(p) && mc.level.getBlockState(p)
                                .isCollisionShapeFullBlock(mc.level, p) && (Boolean) ModUnderTest.staticCall(TELEPORT_UTILS,
                                "landable", new Class<?>[]{BlockPos.class}, new Object[]{p})) {
                            all.add(p);
                        }
                    }
                }
            }
            if (all.size() > 600) {
                break;
            }
        }
        int aims = 0;
        int fragile = 0;
        int avoidable = 0;
        int noAim = 0;
        for (int k = 0; k < 4000 && aims < 120 && all.size() > 1; k++) {
            BlockPos a = all.get(rng.nextInt(all.size()));
            BlockPos b = all.get(rng.nextInt(all.size()));
            double d = Math.sqrt(a.distSqr(b));
            if (d < 3 || d > 30) {
                continue;
            }
            Vec3 eye = new Vec3(a.getX() + 0.5, a.getY() + 1.05 + 1.27, a.getZ() + 0.5);
            Object rot = ModUnderTest.staticCall(TELEPORT_UTILS, "getEtherwarpDirection",
                    new Class<?>[]{Vec3.class, BlockPos.class, double.class}, new Object[]{eye, b, 57.0});
            if (rot == null) {
                try {
                    rot = ModUnderTest.staticCall(TELEPORT_UTILS, "exactEtherwarpDirection",
                            new Class<?>[]{Vec3.class, BlockPos.class, double.class}, new Object[]{eye, b, 57.0});
                } catch (AssertionError e) {
                    rot = null;   // main: no exact fallback
                }
            }
            if (rot == null) {
                // From the settled eye a firm aim exists (what the planner saves) but none from the landing tick's.
                if (firmAim(new Vec3(eye.x, eye.y - 0.05, eye.z), b, 0.01f) != null) {
                    noAim++;
                }
                continue;
            }
            aims++;
            float yaw = (Float) ModUnderTest.call(rot, "yaw", new Class<?>[]{}, new Object[]{});
            float pitch = (Float) ModUnderTest.call(rot, "pitch", new Class<?>[]{}, new Object[]{});
            if (!holds(eye, b, yaw, pitch, 0.01f)) {
                fragile++;
                if (firmAim(eye, b, 0.01f) != null) {
                    avoidable++;
                }
            }
        }
        return new int[]{aims, fragile, avoidable, noAim};
    }

    /** Whether the etherwarp from {@code eye} lands on {@code to} along the look and along it nudged {@code m} degrees
     *  either way in yaw and in pitch (the mod's own cast, 56 blocks). Client thread. */
    private static boolean holds(Vec3 eye, BlockPos to, float yaw, float pitch, float m) {
        for (float[] d : new float[][]{{0, 0}, {m, 0}, {-m, 0}, {0, m}, {0, -m}}) {
            Object hit = ModUnderTest.staticCall(TELEPORT_UTILS, "getEtherPos",
                    new Class<?>[]{Vec3.class, float.class, float.class, double.class},
                    new Object[]{eye, yaw + d[0], pitch + d[1], 56.0});
            boolean lands = hit != null && (Boolean) ModUnderTest.call(hit, "succeeded", new Class<?>[]{}, new Object[]{})
                    && to.equals(ModUnderTest.call(hit, "pos", new Class<?>[]{}, new Object[]{}));
            if (!lands) {
                return false;
            }
        }
        return true;
    }

    private static float[] firmAim(Vec3 eye, BlockPos to) {
        return firmAim(eye, to, 0.02f);
    }

    /** A look from {@code eye} whose etherwarp lands on {@code to} for it and for it nudged {@code m} degrees each way, or
     *  null. Client thread. */
    private static float[] firmAim(Vec3 eye, BlockPos to, float m) {
        double[] uv = {0.17, 0.29, 0.41, 0.53, 0.66, 0.78};
        for (double u : uv) {
            for (double v : uv) {
                double dx = to.getX() + u - eye.x;
                double dy = to.getY() + 0.97 - eye.y;
                double dz = to.getZ() + v - eye.z;
                float yaw = net.minecraft.util.Mth.wrapDegrees((float) -Math.toDegrees(Math.atan2(dx, dz)));
                float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
                if (holds(eye, to, yaw, pitch, m)) {
                    return new float[]{yaw, pitch};
                }
            }
        }
        return null;
    }

    private static Object frame(RoomRoute rr) {
        try {
            return Class.forName(FRAME).getConstructor(String.class, int.class, int.class, int.class)
                    .newInstance(rr.name(), rr.frame()[0], rr.frame()[1], rr.frame()[2]);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static JsonObject node(Object frame, String type, Vec3 feet) {
        Vec3 rel = (Vec3) ModUnderTest.staticCall(COORDS, "toRelative", new Class<?>[]{frame.getClass(), Vec3.class},
                new Object[]{frame, feet});
        JsonObject n = new JsonObject();
        n.addProperty("type", type);
        n.addProperty("x", rel.x);
        n.addProperty("y", rel.y);
        n.addProperty("z", rel.z);
        n.addProperty("yaw", 0f);
        n.addProperty("pitch", 0f);
        return n;
    }

    /** See {@link #routeIn}: PATH S0 (start), PATH S1, ETHERWARP S1 -> S2, PATH S2, PATH S3, ETHERWARP S3 -> S4, ... */
    private static JsonObject toJson(ClientGameTestContext ctx, RoomRoute rr) {
        return ctx.computeOnClient(mc -> {
            Object frame = frame(rr);
            JsonArray nodes = new JsonArray();
            for (int i = 0; i < rr.stands().size(); i++) {
                JsonObject p = node(frame, "PATH", rr.stands().get(i));
                if (i == 0) {
                    p.addProperty("start", true);
                }
                nodes.add(p);
                if (i % 2 == 1 && i + 1 < rr.stands().size()) {
                    float[] aim = rr.aims().get(i / 2);
                    JsonObject e = node(frame, "ETHERWARP", rr.stands().get(i));
                    e.addProperty("yaw", (Float) ModUnderTest.staticCall(COORDS, "toRelativeYaw",
                            new Class<?>[]{frame.getClass(), float.class}, new Object[]{frame, aim[0]}));
                    e.addProperty("pitch", aim[1]);
                    Vec3 rel = (Vec3) ModUnderTest.staticCall(COORDS, "toRelative",
                            new Class<?>[]{frame.getClass(), Vec3.class},
                            new Object[]{frame, rr.stands().get(i + 1).add(0, 0.05, 0)});
                    e.addProperty("landing", String.format(Locale.US, "%.3f %.3f %.3f", rel.x, rel.y, rel.z));
                    nodes.add(e);
                }
            }
            JsonObject route = new JsonObject();
            route.add("nodes", nodes);
            return route;
        });
    }

    private static void flyRoom(ClientGameTestContext ctx, int f, RoomRoute rr, int pass, List<String> failures,
                                int[] totals) {
        boolean primed = pass == 3;
        String label = "floor " + f + " " + rr.name() + " pass " + pass + (primed ? " (+clicks)" : "");
        ctx.runOnClient(mc -> {
            ModUnderTest.staticCall(EXECUTOR, "stop", new Class<?>[]{String.class}, new Object[]{"next room"});
            ModUnderTest.staticCall(MEMORY, "reset");
            ClickPrimer.reset();
            HypixelUseAfterClick.reset();
        });
        // Off the start node first (on the last stand, which no start node is on), so arriving on it is an entry.
        Vec3 start = rr.stands().get(0);
        teleport(ctx, rr.stands().get(rr.stands().size() - 1));
        ctx.waitTicks(10);
        ctx.runOnClient(mc -> {
            mc.player.getInventory().setSelectedSlot(0);
            if (primed) {
                HypixelUseAfterClick.enabled = true;
                ClickPrimer.enabled = true;
                ClickPrimer.onlySlot = 0;
            }
        });
        long mark = LogTap.mark();
        teleport(ctx, start);
        int idle = 0;
        boolean began = false;
        for (int t = 0; t < 20 * 90 && idle < 60; t++) {
            ctx.waitTicks(1);
            boolean running = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(EXECUTOR, "isSessionActive"));
            began |= running;
            idle = began && !running ? idle + 1 : 0;
            if (running && t % 20 == 0 && LogTap.since(mark).stream().filter(l -> l.contains("Etherwarp: no landing after"))
                    .count() >= 2) {
                break;   // a warp the server keeps dropping: counted below, not waited out again and again
            }
            if (!began && t > 200) {
                break;
            }
        }
        List<String> log = LogTap.since(mark);
        ctx.runOnClient(mc -> {
            ClickPrimer.reset();
            HypixelUseAfterClick.reset();
            ModUnderTest.staticCall(EXECUTOR, "stop", new Class<?>[]{String.class}, new Object[]{"room done"});
        });
        List<Hop> hops = parse(rr.name(), log);
        int reaimed = 0;
        int unpairable = 0;
        String stop = null;
        for (String l : log) {
            reaimed += l.contains("[AutoRoutes] Etherwarp (path hop 1): re-aimed from") ? 1 : 0;
            unpairable += l.contains("[AutoRoutes] Etherwarp: re-aimed ") && l.contains("rays agree") ? 1 : 0;
            Matcher sv = SAVED_FROM.matcher(l);
            if (sv.find() && Math.abs(Double.parseDouble(sv.group(1)) - Math.floor(Double.parseDouble(sv.group(1))) - 0.05) < 0.005) {
                totals[9]++;
                println(label + ": saved look fired from above its spot: " + l.substring(l.indexOf("[AutoRoutes]")));
            }
            if (l.contains("[AutoRoutes] Stopped: ")) {
                stop = l.substring(l.indexOf("Stopped: ") + 9);
            }
        }
        List<String> wrong = new ArrayList<>();
        int never = 0;
        for (Hop h : hops) {
            String why = ctx.computeOnClient(mc -> judge(mc, rr, h));
            if (why == null) {
                continue;
            }
            // A warp the executor saw land more than 2 blocks off its plan reads as "the server moved you": in the sim
            // nothing else teleports him, so that is the warp landing somewhere else - a wrong block too.
            if (h.landed() == null && !h.note().startsWith("the server put him")) {
                never++;
            } else {
                wrong.add(why);
            }
            println(label + ": " + why);
        }
        totals[0] += hops.size();
        totals[1] += wrong.size();
        totals[2] += never;
        totals[3] += reaimed;
        totals[4] += unpairable;
        println(String.format(Locale.US, "%s: %s; %d hop(s) flown, %d on another block, %d never landed; first-warp "
                        + "re-aims %d, unpairable re-aims %d%s", label, began ? "ran" : "NEVER STARTED", hops.size(),
                wrong.size(), never, reaimed, unpairable, stop == null ? "" : "; stopped: " + stop));
    }

    /** Null when the hop landed on its planned block; else what happened. Client thread. */
    private static String judge(Minecraft mc, RoomRoute rr, Hop h) {
        Object store = ModUnderTest.staticCall(STORE, "getInstance");
        Object route = ModUnderTest.call(store, "forRoom", new Class<?>[]{String.class}, new Object[]{rr.name()});
        if (route == null) {
            return "node #" + h.node() + " hop " + h.hop() + ": the route is gone";
        }
        @SuppressWarnings("unchecked")
        List<Object> nodes = (List<Object>) ModUnderTest.call(route, "nodes", new Class<?>[]{}, new Object[]{});
        if (h.node() - 1 >= nodes.size()) {
            return "node #" + h.node() + ": no such node";
        }
        Object node = nodes.get(h.node() - 1);
        List<?> saved;
        try {
            saved = (List<?>) node.getClass().getField("pathHops").get(node);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        if (h.hop() - 1 >= saved.size()) {
            return "node #" + h.node() + " hop " + h.hop() + ": the plan has " + saved.size() + " hop(s)";
        }
        Object ph = saved.get(h.hop() - 1);
        BlockPos relTarget = (BlockPos) ModUnderTest.call(ph, "target", new Class<?>[]{}, new Object[]{});
        Object frame = frame(rr);
        BlockPos target = (BlockPos) ModUnderTest.staticCall(COORDS, "toRealBlock",
                new Class<?>[]{frame.getClass(), BlockPos.class}, new Object[]{frame, relTarget});
        if (h.landed() == null) {
            return String.format(Locale.US, "node #%d hop %d planned onto %s: %s", h.node(), h.hop(), target.toShortString(),
                    h.note());
        }
        double standY = (Double) ModUnderTest.staticCall(OVERLAY, "standYOn",
                new Class<?>[]{net.minecraft.world.level.Level.class, BlockPos.class}, new Object[]{mc.level, target});
        Vec3 l = h.landed();
        boolean same = (int) Math.floor(l.x) == target.getX() && (int) Math.floor(l.z) == target.getZ()
                && Math.abs(l.y - (standY + 0.05)) < 0.3;
        if (same) {
            return null;
        }
        BlockPos on = BlockPos.containing(l.x, l.y - 0.05 - 1e-3, l.z);
        if (Math.abs(l.y - Math.floor(l.y) - 0.05) > 0.02) {
            on = BlockPos.containing(l.x, l.y - 0.5, l.z);
        }
        return String.format(Locale.US, "node #%d hop %d WRONG BLOCK: planned %s (%s), landed at %.2f, %.2f, %.2f - on %s (%s)",
                h.node(), h.hop(), target.toShortString(), mc.level.getBlockState(target).getBlock().getName().getString(),
                l.x, l.y, l.z, on.toShortString(), mc.level.getBlockState(on).getBlock().getName().getString());
    }

    static List<Hop> parse(String room, List<String> log) {
        List<Hop> out = new ArrayList<>();
        int node = -1;
        int hop = -1;
        for (String l : log) {
            Matcher a = ACTED.matcher(l);
            if (a.find()) {
                if (node > 0) {
                    out.add(new Hop(room, node, hop, null, "no landing seen before the next warp"));
                }
                node = Integer.parseInt(a.group(1));
                hop = Integer.parseInt(a.group(2));
                continue;
            }
            if (node < 0) {
                continue;
            }
            Matcher m = LANDED.matcher(l);
            if (m.find()) {
                out.add(new Hop(room, node, hop, new Vec3(Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2)),
                        Double.parseDouble(m.group(3))), "landed"));
                node = -1;
                continue;
            }
            Matcher c = CORRECTED.matcher(l);
            if (c.find()) {
                out.add(new Hop(room, node, hop, null, "the server put him " + c.group(3) + " from its landing (WRONG BLOCK: the warp landed elsewhere)"));
                node = -1;
                continue;
            }
            if (l.contains("Etherwarp: no landing after")) {
                out.add(new Hop(room, node, hop, null, "no landing (" + l.substring(l.indexOf("no landing")) + ")"));
                node = -1;
            }
        }
        return out;
    }

    /** A server teleport (as a /tp would), then two ticks. */
    private static void teleport(ClientGameTestContext ctx, Vec3 to) {
        AtomicReference<Boolean> done = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            UUID uuid = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(uuid);
                sp.teleportTo(server.overworld(), to.x, to.y, to.z, java.util.Set.<net.minecraft.world.entity.Relative>of(),
                        sp.getYRot(), 30f, false);
                done.set(true);
            });
        });
        ctx.waitFor(mc -> done.get() != null, 200);
        ctx.waitTicks(2);
    }

    private static void println(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}
