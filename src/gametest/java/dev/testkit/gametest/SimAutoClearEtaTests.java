package dev.testkit.gametest;

import dev.testkit.compat.McCompat;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 1018-autoclear-eta (mod blood-ap3-1018, killer560's doc item 27): "For the auto clear add an estimated time till
 * completion that counts down and estimates how long itll take it to finish the clear based off the auto routes it has
 * left and the pathfinding and puzzles and whatnot."
 *
 * <p>A generated sim F7 with exactly two mob rooms left uncleared (a starred mob in each, every other mob room marked
 * cleared), and Auto Clear run on its own (Any Mob Room, Spirit Sceptre). Every tick the Auto Clear Status HUD's lines are
 * read ({@code AutoStatusHud.shownText}). It must show a "Done in ~m:ss" line while the run is going, the time must
 * count DOWN (a reading one second lower than the one before it, several times), and the run itself must really have
 * cleared (moved 20+ blocks, both mobs dead) - so the line is not judged on a run that never happened. A jar without the
 * estimate never shows the line and fails. Only when named; cheat jar.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimAutoClearEtaTests implements FabricClientGameTest {

    private static final String NAME = "1018-autoclear-eta";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String SIM_ITEMS = "com.killer560.hub.roomsim.SimItems";
    private static final String SIM_MOBS = "com.killer560.hub.roomsim.SimMobs";
    private static final String SIM_ROOM_STATE = "com.killer560.hub.roomsim.SimRoomState";
    private static final String LIVE_MAP_CONFIG = "com.killer560.hub.livemap.LiveMapConfig";
    private static final String AUTO = "com.killer560.hub.autoclear.AutoClearFeature";
    private static final String AUTO_CONFIG = "com.killer560.hub.autoclear.AutoClearConfig";
    private static final String STATUS_HUD = "com.killer560.hub.autosecret.AutoStatusHud";
    private static final String PUZZLES_CONFIG = "com.killer560.hub.autopuzzles.AutoPuzzlesConfig";
    private static final Pattern DONE_IN = Pattern.compile("^Done in ~(\\d+):(\\d\\d)$");
    private static final int RUN_TICKS = 2400;

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
            Scenario.skipped(NAME, "legit jar: no Auto Clear");
            return;
        }
        if (SimMapTests.copyRoomsForOthers() < 20) {
            Scenario.skipped(NAME, "needs real room captures and room database");
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);
        int renderBefore = ctx.computeOnClient(mc -> mc.options.renderDistance().get());
        List<String> failures = new ArrayList<>();
        try {
            ctx.runOnClient(mc -> {
                ModUnderTest.set(ModUnderTest.config(PUZZLES_CONFIG), "setAutoPuzzlesMasterEnabled", false);
                Object map = ModUnderTest.config(LIVE_MAP_CONFIG);
                ModUnderTest.set(map, "setEnabled", true);
                ModUnderTest.set(map, "setInteractiveMapEnabled", true);
                ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autosecret.AutoSecretConfig"), "setStatusHud", true);
                mc.options.renderDistance().set(16);
            });
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter", new Class<?>[]{String.class},
                    new Object[]{"gametest"}));
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_MOBS, "setClearRoomsOnEntry", new Class<?>[]{boolean.class},
                    new Object[]{false}));
            body(ctx, failures);
        } catch (Throwable t) {
            failures.add("scenario threw: " + t);
            t.printStackTrace(System.out);
        } finally {
            ctx.runOnClient(mc -> {
                ModUnderTest.staticCall(SIM_MOBS, "setClearRoomsOnEntry", new Class<?>[]{boolean.class}, new Object[]{true});
                ModUnderTest.staticCall(AUTO, "cancel");
                ModUnderTest.set(ModUnderTest.config(AUTO_CONFIG), "setEnabled", false);
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

    private static void body(ClientGameTestContext ctx, List<String> failures) {
        // ---- a floor with two mob rooms 40+ blocks from the spawn ----------------------------------------------------
        List<SimAutoClearTests.Room> targets = new ArrayList<>();
        List<SimAutoClearTests.Room> rooms = null;
        for (int attempt = 1; attempt <= 4 && targets.size() < 2; attempt++) {
            targets.clear();
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
            SimEtherTicksTests.awaitFloorChunks(ctx);
            ctx.waitTicks(40);
            rooms = SimAutoClearTests.floor(ctx);
            Vec3 me = ctx.computeOnClient(mc -> mc.player.position());
            List<SimAutoClearTests.Room> far = new ArrayList<>();
            for (SimAutoClearTests.Room r : rooms) {
                if (r.mob() && !r.cleared() && SimAutoClearTests.dist(me, r) > 40) {
                    far.add(r);
                }
            }
            far.sort((a, b) -> Double.compare(SimAutoClearTests.dist(me, a), SimAutoClearTests.dist(me, b)));
            for (int i = 0; i < Math.min(2, far.size()); i++) {
                targets.add(far.get(i));
            }
            println("floor " + attempt + ": " + rooms.size() + " rooms; targets " + targets.stream()
                    .map(SimAutoClearTests.Room::name).toList());
        }
        if (targets.size() < 2) {
            failures.add("no generated floor in 4 had two mob rooms 40+ blocks from the spawn");
            return;
        }
        SimEtherTicksTests.giveAotv(ctx, failures);
        java.util.concurrent.atomic.AtomicReference<Boolean> given = new java.util.concurrent.atomic.AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            UUID uuid = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(uuid);
                if (sp != null) {
                    sp.getInventory().setItem(2, (net.minecraft.world.item.ItemStack) ModUnderTest.staticCall(SIM_ITEMS, "build",
                            new Class<?>[]{String.class}, new Object[]{"BAT_WAND"}));
                }
                given.set(sp != null);
            });
        });
        ctx.waitFor(mc -> given.get() != null, 200);
        ctx.waitTicks(10);
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimRun", "begin",
                new Class<?>[]{Minecraft.class, BlockPos.class},
                new Object[]{mc, ModUnderTest.staticCall("com.killer560.hub.roomsim.SimBuilder", "entranceDoor")}));
        for (int i = 0; i < 400 && !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(
                "com.killer560.hub.roomsim.SimRun", "isRunning")); i++) {
            ctx.waitTicks(1);
        }
        ctx.waitTicks(100);

        List<String> keep = targets.stream().map(SimAutoClearTests.Room::name).toList();
        for (SimAutoClearTests.Room r : rooms) {
            if (r.mob() && !keep.contains(r.name())) {
                ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_ROOM_STATE, "markCleared",
                        new Class<?>[]{String.class}, new Object[]{r.name()}));
            }
        }
        List<SimAutoClearTests.Placed> placed = new ArrayList<>();
        for (SimAutoClearTests.Room r : targets) {
            placed.addAll(SimAutoClearTests.spawnIn(ctx, r, 1, false));
        }
        if (placed.size() < 2) {
            failures.add("could not place a starred mob in each target room (" + placed.size() + " placed)");
            return;
        }
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> {
            Object cfg = ModUnderTest.config(AUTO_CONFIG);
            ModUnderTest.set(cfg, "setEnabled", true);
            ModUnderTest.set(cfg, "setHyperionHops", false);
        });
        SimAutoClearTests.setMode(ctx, "ANY_MOB_ROOM", "SPIRIT_SCEPTRE");

        // ---- the run, the HUD read every tick ------------------------------------------------------------------------
        List<Integer> readings = new ArrayList<>();
        String firstLine = null;
        double travelled = 0;
        boolean finished = false;
        int ranTicks = RUN_TICKS;
        ctx.runOnClient(mc -> ModUnderTest.staticCall(AUTO, "start"));
        Vec3 last = ctx.computeOnClient(mc -> mc.player.position());
        for (int t = 0; t < RUN_TICKS; t++) {
            ctx.waitTicks(1);
            Vec3 now = ctx.computeOnClient(mc -> mc.player.position());
            travelled += now.distanceTo(last);
            last = now;
            @SuppressWarnings("unchecked")
            List<String> lines = ctx.computeOnClient(mc -> new ArrayList<>((List<String>) ModUnderTest.staticCall(STATUS_HUD,
                    "shownText")));
            for (String l : lines) {
                Matcher m = DONE_IN.matcher(l);
                if (m.matches()) {
                    int secs = Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2));
                    if (readings.isEmpty() || readings.get(readings.size() - 1) != secs) {
                        readings.add(secs);
                    }
                    if (firstLine == null) {
                        firstLine = l + " at tick " + t;
                    }
                }
            }
            if (!(Boolean) ctx.computeOnClient(mc -> ModUnderTest.staticCall(AUTO, "isBusy"))) {
                finished = true;
                ranTicks = t + 1;
                break;
            }
            if (t % 100 == 99) {
                println("t=" + (t + 1) + ": " + lines);
            }
        }
        if (!finished) {
            ctx.runOnClient(mc -> ModUnderTest.staticCall(AUTO, "cancel"));
        }
        Map<UUID, String> alive = SimAutoClearTests.aliveReport(ctx, placed);
        int downs = 0;
        for (int i = 1; i < readings.size(); i++) {
            if (readings.get(i) == readings.get(i - 1) - 1) {
                downs++;
            }
        }
        println(String.format(java.util.Locale.US, "run finished %s, traveled %.1f blocks, mobs %s; first time line %s;"
                + " readings %s; one-second steps down %d", finished, travelled, alive, firstLine, readings, downs));
        // The run must have happened before the line is judged.
        if (!finished) {
            failures.add("Auto Clear was still running after " + RUN_TICKS / 20 + " s");
        }
        if (travelled < 20) {
            failures.add(String.format(java.util.Locale.US, "traveled only %.1f blocks - nothing ran", travelled));
        }
        for (SimAutoClearTests.Placed p : placed) {
            if (!"dead".equals(alive.get(p.id()))) {
                failures.add("the starred mob in " + p.room() + " is " + alive.get(p.id()) + " - the clear did not happen");
            }
        }
        if (readings.isEmpty()) {
            failures.add("the Auto Clear Status HUD never showed a 'Done in ~m:ss' line while the run went");
            return;
        }
        if (readings.get(0) <= 0) {
            failures.add("the first estimate was " + readings.get(0) + " s for two rooms still to clear");
        }
        // A short run cannot show three one-second steps: on 2026-10-25 both rooms were done in about 3 s and the estimate
        // re-priced from the run's own pace after the first ([27, 26, 1, 0]). Ask for a step each second the run lasted
        // past its first, up to three, and that the time left never goes up.
        int wantDowns = Math.max(1, Math.min(3, ranTicks / 20 - 1));
        boolean rose = false;
        for (int i = 1; i < readings.size(); i++) {
            rose |= readings.get(i) > readings.get(i - 1);
        }
        if (rose) {
            failures.add("the time left went up during the run: " + readings);
        }
        if (downs < wantDowns) {
            failures.add("the time left did not count down (only " + downs + " one-second steps down in " + readings
                    + ", wanted " + wantDowns + " for a " + ranTicks + "-tick run)");
        }
    }

    private static void println(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}
