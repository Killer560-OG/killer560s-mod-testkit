package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.harness.Report;
import dev.testkit.harness.SuiteVerdict;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>1127-sim-acsceptre-pace</b> (mod autopilot-sceptre-run-1127, killer560 2026-11-27: "I just did a full test with spirit
 * sceptre for clear so you can see how it is looking and if it is being used propperly"). In his 11:05 run 183 sceptre uses
 * drew 102 bats on the wire: every use 4 or more ticks after the one before drew its bat (41 of 41), one 2-3 ticks after
 * drew one only 61 times in 142, and 14 of the 46 one-tap "trusting the bat" calls trusted a cast that never flew. The sim
 * now drops a Guided Bat cast under 4 ticks after the caster's last one, as Hypixel does.
 * <p>Five starred zombies close together in one mob room, Spirit Sceptre as the weapon. Asserted: Auto Clear acted (3+
 * sceptre uses, 3+ zombies dead, the clear finished), no two sceptre uses (BatLog's "[Bat] #N use tick" lines, one per use
 * packet) under 4 ticks apart, and no cast dropped by the sim. The origin jar presses the sceptre every 2 ticks.
 * On a legit jar: Auto Clear does not run.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimAcSceptrePace1127Tests implements FabricClientGameTest {

    private static final String NAME = "1127-sim-acsceptre-pace";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String SIM_MOBS = "com.killer560.hub.roomsim.SimMobs";
    private static final String SIM_ITEMS = "com.killer560.hub.roomsim.SimItems";
    private static final String SIM_ROOM_STATE = "com.killer560.hub.roomsim.SimRoomState";
    private static final String SCEPTRE = "com.killer560.hub.roomsim.SimSpiritSceptre";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String LIVE_MAP_CONFIG = "com.killer560.hub.livemap.LiveMapConfig";
    private static final String AUTO = "com.killer560.hub.autoclear.AutoClearFeature";
    private static final String AUTO_CONFIG = "com.killer560.hub.autoclear.AutoClearConfig";
    private static final Pattern USE = Pattern.compile("\\[Bat\\] #(\\d+) use tick (\\d+) ");
    private static final int MIN_GAP = 4;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank() || Scenario.skip(NAME)) {
            return;
        }
        if (!dev.testkit.gametest.mod.Mod.isCheat()) {
            boolean ran;
            try {
                ModUnderTest.staticCall(AUTO, "start");
                ran = (Boolean) ModUnderTest.staticCall(AUTO, "isBusy");
            } catch (Throwable t) {
                ran = false;   // the class is not in a legit jar
            }
            row(!ran, "legit jar: Auto Clear does not run (" + ran + ")");
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
        String detail = "did not run";
        try {
            detail = body(ctx, failures);
        } catch (Throwable t) {
            failures.add("scenario threw: " + t);
            t.printStackTrace(System.out);
        } finally {
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_MOBS, "setClearRoomsOnEntry", new Class<?>[]{boolean.class},
                    new Object[]{true}));
            ctx.runOnClient(mc -> ModUnderTest.staticCall(AUTO, "cancel"));
            ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AUTO_CONFIG), "setEnabled", false));
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
        row(failures.isEmpty(), failures.isEmpty() ? detail : String.join("; ", failures));
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " problem(s): " + String.join("; ", failures));
        }
    }

    private static String body(ClientGameTestContext ctx, List<String> failures) {
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> {
            Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
            ModUnderTest.staticCall(FLOOR_GEN, "generate", new Class<?>[]{Minecraft.class, floor.getClass(), int.class,
                    int.class}, new Object[]{mc, floor, 3, 4});
        }));
        ctx.waitFor(mc -> mc.level != null, 2400);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitTicks(80);
        SimAutoClearTests.awaitFloorChunks(ctx);
        println("MAP CODE: " + ctx.computeOnClient(mc -> String.valueOf(ModUnderTest.staticCall(SIM_STATE, "mapCode"))));
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
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimRun", "begin",
                new Class<?>[]{Minecraft.class, BlockPos.class},
                new Object[]{mc, ModUnderTest.staticCall("com.killer560.hub.roomsim.SimBuilder", "entranceDoor")}));
        for (int i = 0; i < 400 && !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(
                "com.killer560.hub.roomsim.SimRun", "isRunning")); i++) {
            ctx.waitTicks(1);
        }
        ctx.waitTicks(20);
        Vec3 me = ctx.computeOnClient(mc -> mc.player.position());
        List<SimAutoClearTests.Room> mobRooms = new ArrayList<>();
        for (SimAutoClearTests.Room r : SimAutoClearTests.floor(ctx)) {
            if (r.mob()) {
                mobRooms.add(r);
            }
        }
        mobRooms.sort((a, b) -> Double.compare(SimAutoClearTests.dist(me, a), SimAutoClearTests.dist(me, b)));
        for (SimAutoClearTests.Room r : mobRooms) {
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_ROOM_STATE, "markCleared", new Class<?>[]{String.class},
                    new Object[]{r.name()}));
        }
        ctx.waitTicks(5);
        ctx.runOnClient(mc -> {
            Object cfg = ModUnderTest.config(AUTO_CONFIG);
            ModUnderTest.set(cfg, "setEnabled", true);
            ModUnderTest.set(cfg, "setHyperionHops", true);
        });
        SimAutoClearTests.setMode(ctx, "ANY_MOB_ROOM", "SPIRIT_SCEPTRE");
        String standing = SimAutoClear1020Tests.standingIn(ctx);
        for (SimAutoClearTests.Room room : mobRooms) {
            if (room.name().equals(standing)) {
                continue;
            }
            // Close together, so one stand reaches several and the next target comes at once after a bat.
            List<BlockPos> spots = SimAutoClear1020Tests.spreadSpots(ctx, room, 5, 3.0);
            if (spots.size() < 4) {
                continue;
            }
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_ROOM_STATE, "clearRoom", new Class<?>[]{String.class},
                    new Object[]{room.name()}));
            List<SimAutoClearTests.Placed> placed = SimAutoClear1020Tests.spawnAt(ctx, room.name(), spots);
            ctx.waitTicks(30);
            int droppedBefore = dropped(ctx);
            AtomicReference<String> done = new AtomicReference<>();
            AtomicReference<String> gaveUp = new AtomicReference<>();
            long mark = LogTap.mark();
            SimAutoClearTests.Result r = SimAutoClear1020Tests.runCaseWith(ctx, "sceptre pace", () -> ModUnderTest.staticCall(
                    AUTO, "clearRoom", new Class<?>[]{String.class, Runnable.class, Consumer.class},
                    new Object[]{room.name(), (Runnable) () -> done.set("done"), (Consumer<String>) gaveUp::set}), t -> { });
            List<Long> useTicks = new ArrayList<>();
            for (String l : LogTap.since(mark)) {
                Matcher m = USE.matcher(l);
                if (m.find()) {
                    useTicks.add(Long.parseLong(m.group(2)));
                }
            }
            long minGap = Long.MAX_VALUE;
            int close = 0;
            for (int i = 1; i < useTicks.size(); i++) {
                long g = useTicks.get(i) - useTicks.get(i - 1);
                minGap = Math.min(minGap, g);
                close += g < MIN_GAP ? 1 : 0;
            }
            int droppedNow = dropped(ctx);
            Map<UUID, String> alive = SimAutoClearTests.aliveReport(ctx, placed);
            long dead = alive.values().stream().filter("dead"::equals).count();
            String line = String.format(Locale.US, "%s: %d/%d zombies dead, onDone=%s onGiveUp=%s, %d tick(s), sceptre uses %d,"
                            + " closest two %s tick(s) apart, %d under %d, casts dropped by the sim %s", room.name(), dead,
                    placed.size(), done.get(), gaveUp.get(), r.ticks, useTicks.size(),
                    minGap == Long.MAX_VALUE ? "-" : String.valueOf(minGap), close, MIN_GAP,
                    droppedBefore < 0 ? "n/a (old sim)" : String.valueOf(droppedNow - droppedBefore));
            println(line);
            if (useTicks.size() < 3) {
                failures.add("Auto Clear sent under 3 sceptre uses: " + line);
            }
            if (dead < 3 || done.get() == null) {
                failures.add("the clear did not kill the zombies: " + line);
            }
            if (close > 0) {
                failures.add(close + " sceptre use(s) under " + MIN_GAP + " ticks after the one before (Hypixel drops"
                        + " about half of them): " + line);
            }
            if (droppedBefore >= 0 && droppedNow > droppedBefore) {
                failures.add("the sim dropped " + (droppedNow - droppedBefore) + " cast(s): " + line);
            }
            return line;
        }
        failures.add("setup: no mob room with 4 open floor spots 3 blocks apart");
        return "no room";
    }

    /** The sim's dropped-cast count, -1 on a jar whose sim does not drop casts. */
    private static int dropped(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            try {
                return ((Number) ModUnderTest.staticCall(SCEPTRE, "droppedCasts")).intValue();
            } catch (RuntimeException | Error e) {
                return -1;
            }
        });
    }

    private static void row(boolean pass, String detail) {
        System.out.println("[" + NAME + "] " + (pass ? "PASS" : "FAIL") + " - " + detail);
        Report.caseStarted(NAME);
        Report.caseFinished(NAME, pass ? "PASS" : "FAIL", "n/a (singleplayer)", detail, List.of(), List.of());
        SuiteVerdict.finished(NAME);
    }

    static void println(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}
