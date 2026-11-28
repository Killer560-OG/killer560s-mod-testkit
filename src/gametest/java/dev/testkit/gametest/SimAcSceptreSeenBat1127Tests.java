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
 * <b>1127-sim-acsceptre-seenbat</b> (mod sceptre-onetap-seen-bat-1127, killer560 2026-11-27: One-Tap Warp "moving on, trusting
 * the bat" trusted a cast that was SENT, and 14 of 46 such casts drew no bat, so the mob waited 16-34 ticks). The mod now takes
 * a trusted mob back and casts again when no bat from the cast is seen within 7 ticks.
 * <p>Phase A (no drop): a mob room with five starred zombies 8 blocks apart; Auto Clear trusts a bat ("trusting the bat" in
 * the log), the bats show, so no cast is taken back (batsUnseen unchanged) and the clear finishes. Phase B: the sim ignores the
 * first two casts (SimSpiritSceptre.dropNextCasts): the mod must notice (batsUnseen goes up, "no bat was seen" logged), cast
 * again at the 4-tick pace (no two uses under 4 ticks apart) and still kill the zombies. On a legit jar: Auto Clear does not run.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimAcSceptreSeenBat1127Tests implements FabricClientGameTest {

    private static final String NAME = "1127-sim-acsceptre-seenbat";
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
        int phase = 0;
        List<String> lines = new ArrayList<>();
        for (SimAutoClearTests.Room room : mobRooms) {
            if (room.name().equals(standing) || phase >= 2) {
                continue;
            }
            List<BlockPos> spots = SimAutoClear1020Tests.spreadSpots(ctx, room, 5, 8.0);
            if (spots.size() < 4) {
                continue;
            }
            boolean drop = phase == 1;
            phase++;
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_ROOM_STATE, "clearRoom", new Class<?>[]{String.class},
                    new Object[]{room.name()}));
            List<SimAutoClearTests.Placed> placed = SimAutoClear1020Tests.spawnAt(ctx, room.name(), spots);
            ctx.waitTicks(30);
            int unseenBefore = unseen(ctx);
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SCEPTRE, "dropNextCasts", new Class<?>[]{int.class},
                    new Object[]{drop ? 2 : 0}));
            AtomicReference<String> done = new AtomicReference<>();
            AtomicReference<String> gaveUp = new AtomicReference<>();
            long mark = LogTap.mark();
            SimAutoClearTests.Result r = SimAutoClear1020Tests.runCaseWith(ctx, "sceptre seenbat", () -> ModUnderTest.staticCall(
                    AUTO, "clearRoom", new Class<?>[]{String.class, Runnable.class, Consumer.class},
                    new Object[]{room.name(), (Runnable) () -> done.set("done"), (Consumer<String>) gaveUp::set}), t -> { });
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SCEPTRE, "dropNextCasts", new Class<?>[]{int.class},
                    new Object[]{0}));
            List<Long> useTicks = new ArrayList<>();
            int trusted = 0;
            int noBat = 0;
            for (String l : LogTap.since(mark)) {
                Matcher m = USE.matcher(l);
                if (m.find()) {
                    useTicks.add(Long.parseLong(m.group(2)));
                }
                trusted += l.contains("[AutoClear] one-tap:") && l.contains("trusting the bat") ? 1 : 0;
                noBat += l.contains("no bat was seen") ? 1 : 0;
            }
            int close = 0;
            for (int i = 1; i < useTicks.size(); i++) {
                close += useTicks.get(i) - useTicks.get(i - 1) < MIN_GAP ? 1 : 0;
            }
            int unseenNow = unseen(ctx);
            Map<UUID, String> alive = SimAutoClearTests.aliveReport(ctx, placed);
            long dead = alive.values().stream().filter("dead"::equals).count();
            String line = String.format(Locale.US, "%s %s: %d/%d zombies dead, onDone=%s onGiveUp=%s, %d tick(s), sceptre uses"
                            + " %d, trusted %d, taken back %d (log %d), uses under %d ticks apart %d", drop ? "dropped" : "good",
                    room.name(), dead, placed.size(), done.get(), gaveUp.get(), r.ticks, useTicks.size(), trusted,
                    unseenNow - unseenBefore, noBat, MIN_GAP, close);
            println(line);
            lines.add(line);
            if (unseenBefore < 0) {
                failures.add("the jar has no seen-bat check (batsUnseen missing): " + line);
                return line;
            }
            if (dead < 3 || done.get() == null) {
                failures.add("the clear did not kill the zombies: " + line);
            }
            if (close > 0) {
                failures.add("a recast broke the " + MIN_GAP + "-tick pace: " + line);
            }
            if (!drop && (trusted < 1 || unseenNow != unseenBefore)) {
                failures.add("good casts: expected a trusted bat and none taken back: " + line);
            }
            if (drop && (unseenNow == unseenBefore || noBat < 1 || useTicks.size() < 3)) {
                failures.add("dropped casts were not noticed and cast again: " + line);
            }
        }
        if (phase == 2) {
            return String.join(" | ", lines);
        }
        failures.add("setup: fewer than 2 mob rooms with 4 open floor spots 8 blocks apart (" + phase + ")");
        return "no room";
    }

    /** Trusted casts taken back for want of a bat, -1 on a jar without the check. */
    private static int unseen(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            try {
                return ((Number) ModUnderTest.staticCall(AUTO, "batsUnseen")).intValue();
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
