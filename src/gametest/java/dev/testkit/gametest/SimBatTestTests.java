package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code /test} (mod battest-1020, cheat build): the Spirit Sceptre bat measurement typed in a sim dungeon run. A starred
 * zombie in a mob room of a generated F7 (its health raised to 1000 so it outlives the five bats - a sim mob has 1 HP),
 * him standing about 10 blocks from it with a clear line; then {@code /test} as typed. The test must start, send at most
 * its five shots (one use each, never a second for the same shot), log each fired bat from its use packet to its hit
 * ({@code [Bat] #N use / seen / fly / hit / result}), end on its own with a {@code [BatTest] summary}, and leave the use
 * key up. The premise is proved first: the client resolves the starred mob, and each fired shot's bat is SEEN and flies.
 * The sim's bat flies {@code SimSpiritSceptre.SPEED} (1.02 b/t); the client sees its sim entity in bursts, so the fitted
 * speed is only required to be 0.6-1.3 (measured 0.78-1.07).
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimBatTestTests implements FabricClientGameTest {

    static final String NAME = "132-sim-bat-test";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String SIM_MOBS = "com.killer560.hub.roomsim.SimMobs";
    private static final String SIM_ITEMS = "com.killer560.hub.roomsim.SimItems";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String LIVE_MAP_CONFIG = "com.killer560.hub.livemap.LiveMapConfig";
    private static final String BAT_TEST = "com.killer560.hub.autoclear.BatTest";
    private static final String RIGHT_CLICK = "com.killer560.hub.util.RightClick";
    private static final String BAT_LOG = "com.killer560.hub.autoclear.BatLog";
    private static final Pattern FLIGHT = Pattern.compile("\\[BatTest\\] summary shot (\\d) \\(([^)]*)\\): bat #(\\d+).*"
            + "flight (-?\\d+) ticks over ([0-9.]+) blocks = ([0-9.-]+) b/t \\(seen \\+(-?\\d+), ([0-9.-]+) b/t while seen\\)");

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank() || Scenario.skip(NAME)) {
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
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"));
        ctx.runOnClient(mc -> {
            ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autopuzzles.AutoPuzzlesConfig"),
                    "setAutoPuzzlesMasterEnabled", false);
            ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autoclear.AutoClearConfig"), "setEnabled", false);
            Object map = ModUnderTest.config(LIVE_MAP_CONFIG);
            ModUnderTest.set(map, "setEnabled", true);
            ModUnderTest.set(map, "setInteractiveMapEnabled", true);
        });
        int renderBefore = ctx.computeOnClient(mc -> mc.options.renderDistance().get());
        ctx.runOnClient(mc -> mc.options.renderDistance().set(16));
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter", new Class<?>[]{String.class},
                new Object[]{"gametest"}));
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_MOBS, "setClearRoomsOnEntry", new Class<?>[]{boolean.class},
                new Object[]{false}));
        List<String> failures = new ArrayList<>();
        try {
            body(ctx, failures);
        } catch (Throwable t) {
            failures.add("scenario threw: " + t);
            t.printStackTrace(System.out);
        } finally {
            if (ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(BAT_TEST, "isRunning"))) {
                ctx.runOnClient(mc -> mc.player.connection.sendCommand("test"));   // a second /test ends it
            }
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_MOBS, "setClearRoomsOnEntry", new Class<?>[]{boolean.class},
                    new Object[]{true}));
            ctx.runOnClient(mc -> mc.options.renderDistance().set(renderBefore));
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

    private static void body(ClientGameTestContext ctx, List<String> failures) {
        // ---- a floor, and a mob room with a mob spot and a standing spot ~10 blocks apart in a clear line ----
        BlockPos[] pair = null;
        String roomName = null;
        for (int attempt = 1; attempt <= 3 && pair == null; attempt++) {
            long before = Scenario.simBuildCount(ctx);
            ctx.runOnClient(mc -> mc.execute(() -> {
                Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
                ModUnderTest.staticCall(FLOOR_GEN, "generate",
                        new Class<?>[]{Minecraft.class, floor.getClass(), int.class, int.class},
                        new Object[]{mc, floor, 3, 4});
            }));
            ctx.waitFor(mc -> mc.level != null);
            Scenario.awaitSimBuild(ctx, before);
            ctx.waitTicks(80);
            SimAutoClearTests.awaitFloorChunks(ctx);
            List<SimAutoClearTests.Room> rooms = SimAutoClearTests.floor(ctx);
            Vec3 me = ctx.computeOnClient(mc -> mc.player.position());
            List<SimAutoClearTests.Room> mobRooms = new ArrayList<>();
            for (SimAutoClearTests.Room r : rooms) {
                if (r.mob() && !r.cleared()) {
                    mobRooms.add(r);
                }
            }
            mobRooms.sort((a, b) -> Double.compare(SimAutoClearTests.dist(me, a), SimAutoClearTests.dist(me, b)));
            for (SimAutoClearTests.Room r : mobRooms) {
                BlockPos[] p = pairIn(ctx, r);
                println("floor " + attempt + ": room " + r.name() + " (" + r.type() + ") at " + r.x() + "," + r.z()
                        + ": " + (p == null ? "no mob/stand pair 9-11 blocks apart in a clear line"
                        : "mob " + p[0].toShortString() + ", stand " + p[1].toShortString()));
                if (p != null) {
                    pair = p;
                    roomName = r.name();
                    break;
                }
            }
        }
        if (pair == null) {
            failures.add("no generated floor in 3 had a mob room with a clear 10-block line to stand on");
            return;
        }

        // ---- loadout: AOTV (the map's etherwarps), Hyperion, Spirit Sceptre; slot 1 held ----
        AtomicReference<Boolean> given = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            UUID uuid = mc.player.getUUID();
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
                String[] ids = {"ASPECT_OF_THE_VOID", "HYPERION", "BAT_WAND"};
                for (int i = 0; i < ids.length; i++) {
                    inv.setItem(i, (ItemStack) ModUnderTest.staticCall(SIM_ITEMS, "build", new Class<?>[]{String.class},
                            new Object[]{ids[i]}));
                }
                given.set(true);
            });
        });
        ctx.waitFor(mc -> given.get() != null, 200);
        ctx.waitTicks(10);
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(1));

        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimRun", "begin",
                new Class<?>[]{Minecraft.class, BlockPos.class},
                new Object[]{mc, ModUnderTest.staticCall("com.killer560.hub.roomsim.SimBuilder", "entranceDoor")}));
        for (int i = 0; i < 400 && !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(
                "com.killer560.hub.roomsim.SimRun", "isRunning")); i++) {
            ctx.waitTicks(1);
        }
        ctx.waitTicks(20);

        // ---- him on the standing spot facing the room, the starred zombie on the mob spot (1000 HP) ----
        BlockPos mobAt = pair[0];
        BlockPos standAt = pair[1];
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            UUID uuid = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(uuid);
                double dx = mobAt.getX() - standAt.getX();
                double dz = mobAt.getZ() - standAt.getZ();
                float yaw = (float) -Math.toDegrees(Math.atan2(dx, dz));
                sp.teleportTo((ServerLevel) sp.level(), standAt.getX() + 0.5, standAt.getY(), standAt.getZ() + 0.5,
                        java.util.Set.of(), yaw, 0f, false);
            });
        });
        ctx.waitTicks(40);
        int pairsBefore = starredPairs(ctx);
        ctx.runOnClient(mc -> {
            Object k = ModUnderTest.enumValue(SIM_MOBS + "$Kind", "ZOMBIE");
            ModUnderTest.staticCall(SIM_MOBS, "spawnStarred", new Class<?>[]{Minecraft.class, BlockPos.class,
                    k.getClass()}, new Object[]{mc, mobAt, k});
        });
        UUID mob = newestStarred(ctx, pairsBefore);
        if (mob == null) {
            failures.add("the starred zombie did not spawn at " + mobAt.toShortString());
            return;
        }
        AtomicReference<String> hp = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                Entity e = server.overworld().getEntity(mob);
                if (e instanceof LivingEntity l) {
                    l.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000.0);
                    l.setHealth(1000f);
                    hp.set(l.getHealth() + "/" + l.getMaxHealth());
                } else {
                    hp.set("not readable");
                }
            });
        });
        ctx.waitFor(mc -> hp.get() != null, 200);
        for (int i = 0; i < 100 && !ctx.computeOnClient(mc -> mc.player.onGround()); i++) {
            ctx.waitTicks(1);
        }
        ctx.waitTicks(20);
        int resolved = ctx.computeOnClient(mc -> ((List<?>) ModUnderTest.staticCall(
                "com.killer560.hub.mobesp.MobEspFeature", "starredMobs", new Class<?>[]{Minecraft.class},
                new Object[]{mc})).size());
        Vec3 eye = ctx.computeOnClient(mc -> mc.player.getEyePosition());
        println(String.format(Locale.ROOT, "room %s: him at %s (eye %.1f blocks from the mob), starred zombie %s at %s,"
                        + " hp %s; starred mobs the client resolves: %d", roomName,
                ctx.computeOnClient(mc -> mc.player.blockPosition().toShortString()),
                eye.distanceTo(Vec3.atCenterOf(mobAt)), mob, mobAt.toShortString(), hp.get(), resolved));
        if (resolved < 1) {
            failures.add("the client resolves no starred mob - /test would have nothing to shoot");
            return;
        }

        // ---- /test as typed ----
        int usesBefore = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(RIGHT_CLICK, "itemUsesSent"));
        int shotsBefore = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(BAT_LOG, "shots"));
        long m = LogTap.mark();
        ctx.runOnClient(mc -> mc.player.connection.sendCommand("test"));
        boolean started = false;
        for (int i = 0; i < 20 && !started; i++) {
            ctx.waitTicks(1);
            started = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(BAT_TEST, "isRunning"));
        }
        boolean ended = false;
        int waited = 0;
        while (started && !ended && waited < 2700) {
            ctx.waitTicks(10);
            waited += 10;
            ended = !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(BAT_TEST, "isRunning"));
        }
        ctx.waitTicks(5);
        int uses = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(RIGHT_CLICK, "itemUsesSent")) - usesBefore;
        int sceptreUses = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(BAT_LOG, "shots")) - shotsBefore;
        boolean keyUp = ctx.computeOnClient(mc -> !mc.options.keyUse.isDown());

        // ---- the log ----
        List<String> lines = LogTap.since(m);
        int useLines = 0;
        int seenLines = 0;
        int flyLines = 0;
        int hitLines = 0;
        int resultLines = 0;
        int fired = 0;
        int skipped = 0;
        boolean summary = false;
        boolean doneAll = false;
        boolean damaged = false;
        List<double[]> speeds = new ArrayList<>();
        for (String l : lines) {
            String t = l.replaceAll("^.*?(\\[Bat(Test)?\\])", "$1");
            if (l.contains("[BatTest]")) {
                println("  " + t);
            } else if (l.contains("[Bat] #") && !l.contains(" fly ")) {
                println("  " + t);
            } else if (l.contains("[Bat] decision")) {
                println("  " + t);
            }
            if (l.contains("[Bat] #") && l.contains(" use tick ")) {
                useLines++;
            }
            if (l.contains("[Bat] #") && l.contains(" seen tick ")) {
                seenLines++;
            }
            if (l.contains("[Bat] #") && l.contains(" fly tick ")) {
                flyLines++;
            }
            if (l.contains("[Bat] #") && l.contains(" hit tick ")) {
                hitLines++;
            }
            if (l.contains("[Bat] #") && l.contains(" result: ")) {
                resultLines++;
                Matcher h = Pattern.compile("hp ([0-9.]+) -> ([0-9.]+)").matcher(l);
                if (h.find() && Double.parseDouble(h.group(2)) < Double.parseDouble(h.group(1))) {
                    damaged = true;
                }
            }
            if (l.contains("[BatTest] shot") && l.contains(" done: bat #")) {
                fired++;
            }
            if (l.contains("[BatTest] shot") && l.contains(" skipped: ")) {
                skipped++;
            }
            if (l.contains("[BatTest] summary:") && l.contains("bat(s) seen")) {
                summary = true;
            }
            if (l.contains("[BatTest] summary:") && l.contains("all 5 shots done")) {
                doneAll = true;
            }
            Matcher f = FLIGHT.matcher(l);
            if (f.find() && !f.group(8).equals("-")) {
                speeds.add(new double[]{Integer.parseInt(f.group(1)), Integer.parseInt(f.group(4)),
                        Double.parseDouble(f.group(5)), Double.parseDouble(f.group(8)), Integer.parseInt(f.group(7))});
            }
        }
        println(String.format(Locale.ROOT, "/test: started %s, ended %s after %d ticks, use_item sent %d (Spirit Sceptre %d,"
                        + " the rest the trips' etherwarps), shots fired %d, skipped %d | [Bat] lines: use %d, seen %d, fly %d,"
                        + " hit %d, result %d | summary %s, all done %s, target damaged %s, use key up %s", started, ended,
                waited, uses, sceptreUses, fired, skipped, useLines,
                seenLines, flyLines, hitLines, resultLines, summary, doneAll, damaged, keyUp));
        for (double[] sp : speeds) {
            println(String.format(Locale.ROOT, "  shot %d: %d ticks use to hit, %.2f blocks, seen +%d, %.2f b/t while seen",
                    (int) sp[0], (int) sp[1], sp[2], (int) sp[4], sp[3]));
        }

        if (!started) {
            failures.add("/test did not start");
            return;
        }
        if (!ended) {
            failures.add("/test was still running after " + waited + " ticks");
        }
        if (!summary) {
            failures.add("no [BatTest] summary line");
        }
        if (fired + skipped != 5 && doneAll) {
            failures.add("the set ended with " + fired + " fired and " + skipped + " skipped, not 5");
        }
        // Every Spirit Sceptre use packet gets its own [Bat] use line (BatLog reads the outgoing packets), so these are the
        // sceptre uses; the rest of use_item are the map trips' etherwarps.
        if (sceptreUses > 5) {
            failures.add(sceptreUses + " Spirit Sceptre uses for a five-shot set");
        }
        if (sceptreUses != fired || useLines != fired) {
            failures.add("sceptre uses " + sceptreUses + ", [Bat] use lines " + useLines + ", shots fired " + fired
                    + " - one use per fired shot expected");
        }
        if (fired < 3) {
            failures.add("only " + fired + " shot(s) fired (the three 10-block shots at least should go from where he"
                    + " stands)");
        }
        if (seenLines < fired || hitLines < fired || resultLines < fired) {
            failures.add("not every fired shot's bat was logged to its end (seen " + seenLines + ", hit " + hitLines
                    + ", result " + resultLines + " for " + fired + ")");
        }
        if (flyLines < fired * 3) {
            failures.add("only " + flyLines + " fly line(s) for " + fired + " bat(s)");
        }
        if (!damaged) {
            failures.add("no bat result shows the target's health going down");
        }
        if (!keyUp) {
            failures.add("the use key was left down");
        }
        if (speeds.size() < fired) {
            failures.add("only " + speeds.size() + " of " + fired + " shot(s) have a while-seen speed in the summary");
        }
        // The sim's server moves its bat entity from queued client-tick tasks and a bat's position goes out every few
        // ticks, so the client sees it in uneven bursts: three runs on 2026-10-20 fitted 0.78-1.07 b/t for the sim's 1.02.
        // This bound catches a fit that is broken (no motion, or the bursts summed), not the sim's jitter.
        for (double[] sp : speeds) {
            if (sp[3] < 0.6 || sp[3] > 1.3) {
                failures.add(String.format(Locale.ROOT, "shot %d measured %.2f b/t while seen; the sim's bat flies 1.02",
                        (int) sp[0], sp[3]));
            }
        }
        List<String> errors = LogTap.modErrorsSince(m);
        if (!errors.isEmpty()) {
            failures.add("mod ERROR lines: " + errors);
        }
    }

    /** In the room: a floor spot for the mob near the middle and a standing spot whose eye is 9-11 from it in a clear line. */
    private static BlockPos[] pairIn(ClientGameTestContext ctx, SimAutoClearTests.Room room) {
        int yHint = ctx.computeOnClient(mc -> ((BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter",
                new Class<?>[]{int.class}, new Object[]{0})).getY());
        AtomicReference<BlockPos[]> out = new AtomicReference<>();
        AtomicReference<Boolean> done = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                try {
                    ServerLevel level = server.overworld();
                    List<BlockPos> floor = new ArrayList<>();
                    for (int dx = -12; dx <= 12; dx++) {
                        for (int dz = -12; dz <= 12; dz++) {
                            for (int y = yHint - 6; y <= yHint + 6; y++) {
                                BlockPos feet = new BlockPos(room.x() + dx, y, room.z() + dz);
                                if (solid(level, feet.below()) && !solid(level, feet) && !solid(level, feet.above())
                                        && !solid(level, feet.above(2))) {
                                    floor.add(feet);
                                    break;
                                }
                            }
                        }
                    }
                    floor.sort((a, b) -> Double.compare(a.distSqr(new BlockPos(room.x(), a.getY(), room.z())),
                            b.distSqr(new BlockPos(room.x(), b.getY(), room.z()))));
                    for (BlockPos m : floor.subList(0, Math.min(40, floor.size()))) {
                        Vec3 c = new Vec3(m.getX() + 0.5, m.getY() + 1.0, m.getZ() + 0.5);
                        for (BlockPos s : floor) {
                            Vec3 eye = new Vec3(s.getX() + 0.5, s.getY() + 1.62, s.getZ() + 0.5);
                            double d = eye.distanceTo(c);
                            if (d < 9.5 || d > 10.5) {
                                continue;
                            }
                            HitResult hit = level.clip(new ClipContext(eye, c, ClipContext.Block.COLLIDER,
                                    ClipContext.Fluid.NONE, net.minecraft.world.phys.shapes.CollisionContext.empty()));
                            if (hit.getType() == HitResult.Type.MISS) {
                                out.set(new BlockPos[]{m, s});
                                return;
                            }
                        }
                    }
                } finally {
                    done.set(true);
                }
            });
        });
        ctx.waitFor(mc -> done.get() != null, 400);
        return out.get();
    }

    private static boolean solid(ServerLevel level, BlockPos p) {
        return !level.getBlockState(p).getCollisionShape(level, p).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static int starredPairs(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> ((List<UUID[]>) ModUnderTest.staticCall(SIM_MOBS, "starredPairs")).size());
    }

    @SuppressWarnings("unchecked")
    private static UUID newestStarred(ClientGameTestContext ctx, int before) {
        for (int i = 0; i < 40; i++) {
            List<UUID[]> pairs = ctx.computeOnClient(mc -> (List<UUID[]>) ModUnderTest.staticCall(SIM_MOBS, "starredPairs"));
            if (pairs.size() > before) {
                return pairs.get(pairs.size() - 1)[0];
            }
            ctx.waitTicks(1);
        }
        return null;
    }

    static void println(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}
