package dev.testkit.gametest;

import dev.testkit.compat.McCompat;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 131-sim-midas-1020: Auto Clear in King Midas (mod, 2026-10-20). killer560: "for king midas yellow room it needs to clear
 * the room; the others just have a mob spawned in them", "it still needs to learn to superboom the crypt". The room's mob
 * only spawns once the gold altar in its middle is superboomed (the sim does that: a Superboom that opens the altar - its
 * 35-slab lid, capture x 14..18, y 69, z 9..15 - spawns a starred King Midas).
 *
 * <p>King Midas is loaded on its own, with a Hyperion, the AOTV and a Superboom, and {@code clearRoom("King Midas")} is
 * called with no mob in the room. Asserted, from the log and the world, not from the mod's own verdict alone:
 * <ol>
 *   <li>the client saw the altar's lid standing before the run (the premise), and no starred mob;</li>
 *   <li>Auto Clear's Superboom on the altar went out, the sim's superboom opened it and spawned King Midas, and the lid is
 *       gone in the client's world;</li>
 *   <li>King Midas died AFTER the boom (log order), the room reads cleared, and clearRoom called onDone, not onGiveUp.</li>
 * </ol>
 * The sim's integrated server has no GrimAC; the same step on GrimAC is 62-argrim-1020-midas.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimAutoClearMidasTests implements FabricClientGameTest {

    private static final String NAME = "131-sim-midas-1020";
    private static final String ROOM = "King Midas";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String SIM_MOBS = "com.killer560.hub.roomsim.SimMobs";
    private static final String SIM_ITEMS = "com.killer560.hub.roomsim.SimItems";
    private static final String SIM_ROOM_STATE = "com.killer560.hub.roomsim.SimRoomState";
    private static final String BUILDER = "com.killer560.hub.roomsim.SimBuilder";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String AUTO = "com.killer560.hub.autoclear.AutoClearFeature";
    private static final String AUTO_CONFIG = "com.killer560.hub.autoclear.AutoClearConfig";

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
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);
        ctx.runOnClient(mc -> {
            ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autopuzzles.AutoPuzzlesConfig"),
                    "setAutoPuzzlesMasterEnabled", false);
            Object map = ModUnderTest.config("com.killer560.hub.livemap.LiveMapConfig");
            ModUnderTest.set(map, "setEnabled", true);
            ModUnderTest.set(map, "setInteractiveMapEnabled", true);
        });
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter", new Class<?>[]{String.class},
                new Object[]{"gametest"}));
        // A room counts cleared the moment he walks in unless this is off (131's rule); this case clears it by its mob.
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_MOBS, "setClearRoomsOnEntry", new Class<?>[]{boolean.class},
                new Object[]{false}));
        List<String> failures = new ArrayList<>();
        try {
            body(ctx, failures);
        } catch (Throwable t) {
            failures.add("scenario threw: " + t);
            t.printStackTrace(System.out);
        } finally {
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_MOBS, "setClearRoomsOnEntry", new Class<?>[]{boolean.class},
                    new Object[]{true}));
            ctx.runOnClient(mc -> ModUnderTest.staticCall(AUTO, "cancel"));
            ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AUTO_CONFIG), "setEnabled", false));
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
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> ModUnderTest.staticCall(BUILDER, "buildSingleRoom",
                new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, ROOM})));
        ctx.waitFor(mc -> mc.level != null && mc.player != null, 2400);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitFor(mc -> McCompat.screen(mc) == null, 1200);
        int landed = -1;
        for (int t = 0; t < 1800; t++) {
            boolean ok = ctx.computeOnClient(mc -> mc.player != null && mc.player.onGround()
                    && !mc.level.getBlockState(mc.player.blockPosition().below()).isAir());
            if (ok) {
                landed = t;
                break;
            }
            ctx.waitTicks(1);
        }
        if (landed < 0) {
            failures.add("the client never stood in " + ROOM);
            return;
        }
        ctx.waitTicks(60);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_ROOM_STATE, "clearRoom", new Class<?>[]{String.class},
                new Object[]{ROOM}));

        // ---- loadout: AOTV for the map's etherwarps, Hyperion, Superboom ----
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
                String[] ids = {"ASPECT_OF_THE_VOID", "HYPERION", "SUPERBOOM_TNT"};
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

        // ---- the premise: the lid stands, no starred mob, the room is a mob room and not cleared ----
        int lidBefore = lidSlabs(ctx);
        int starredBefore = starred(ctx);
        SimAutoClearTests.Room roomBefore = room(ctx);
        println("premise: he stands at " + ctx.computeOnClient(mc -> mc.player.position()) + "; " + lidBefore
                + " lid slab(s) standing in the client's world, " + starredBefore
                + " starred mob(s) visible, room " + roomBefore);
        if (lidBefore < 30) {
            failures.add("the King Midas altar's lid is not standing before the run (" + lidBefore + " slabs)");
            return;
        }
        if (starredBefore != 0) {
            failures.add(starredBefore + " starred mob(s) already in sight - the boom would prove nothing");
            return;
        }
        if (roomBefore == null || !roomBefore.mob() || roomBefore.cleared()) {
            failures.add("King Midas is not an uncleared mob room on the map: " + roomBefore);
            return;
        }

        // ---- Auto Clear: clearRoom("King Midas") ----
        ctx.runOnClient(mc -> {
            Object cfg = ModUnderTest.config(AUTO_CONFIG);
            ModUnderTest.set(cfg, "setEnabled", true);
            ModUnderTest.set(cfg, "setHyperionHops", true);
        });
        SimAutoClearTests.setMode(ctx, "ANY_MOB_ROOM", "HYPERION");
        AtomicReference<String> done = new AtomicReference<>();
        AtomicReference<String> gaveUp = new AtomicReference<>();
        long mark = LogTap.mark();
        SimAutoClearTests.Result r = SimAutoClearTests.runCase(ctx, "midas", () -> ModUnderTest.staticCall(AUTO, "clearRoom",
                new Class<?>[]{String.class, Runnable.class, Consumer.class},
                new Object[]{ROOM, (Runnable) () -> done.set("done"), (Consumer<String>) gaveUp::set}));
        ctx.waitTicks(10);
        List<String> log = LogTap.since(mark);
        int boomAt = -1;
        int openedAt = -1;
        int spawnAt = -1;
        int deadAt = -1;
        for (int i = 0; i < log.size(); i++) {
            String l = log.get(i);
            if (l.contains("[AutoClear]") || l.contains("King Midas spawns") || l.contains("Sim superboom")) {
                println("  " + l.replaceAll("^.*?(\\[AutoClear\\]|Sim superboom)", "$1"));
            }
            if (boomAt < 0 && l.contains("[AutoClear] King Midas: Superboom 1 on the altar")) {
                boomAt = i;
            }
            if (openedAt < 0 && l.contains("[AutoClear] King Midas: altar opened")) {
                openedAt = i;
            }
            if (spawnAt < 0 && l.contains("King Midas spawns")) {
                spawnAt = i;
            }
            if (deadAt < 0 && l.contains("[AutoClear]") && l.contains(" in King Midas is dead")) {
                deadAt = i;
            }
        }
        int lidAfter = lidSlabs(ctx);
        SimAutoClearTests.Room roomAfter = room(ctx);
        println("midas: " + r + "; onDone=" + done.get() + " onGiveUp=" + gaveUp.get() + "; log lines: boom " + boomAt
                + ", sim spawn " + spawnAt + ", opened " + openedAt + ", dead " + deadAt + "; lid slabs " + lidBefore
                + " -> " + lidAfter + "; room after " + roomAfter);
        if (boomAt < 0) {
            failures.add("Auto Clear never superboomed the King Midas altar");
        }
        if (spawnAt < 0) {
            failures.add("the sim's superboom never opened the altar / spawned King Midas");
        }
        if (lidAfter > 0) {
            failures.add(lidAfter + " lid slab(s) still standing after the run");
        }
        if (openedAt < 0) {
            failures.add("Auto Clear never saw the altar open");
        }
        if (deadAt < 0) {
            failures.add("Auto Clear never killed the starred mob in King Midas");
        } else if (boomAt >= 0 && deadAt < boomAt) {
            failures.add("the kill (log line " + deadAt + ") came before the boom (" + boomAt + ")");
        }
        if (roomAfter == null || !roomAfter.cleared()) {
            failures.add("King Midas does not read cleared after the run: " + roomAfter);
        }
        if (done.get() == null || gaveUp.get() != null) {
            failures.add("clearRoom ended onDone=" + done.get() + " onGiveUp=" + gaveUp.get());
        }
    }

    private static SimAutoClearTests.Room room(ClientGameTestContext ctx) {
        for (SimAutoClearTests.Room r : SimAutoClearTests.floor(ctx)) {
            if (ROOM.equals(r.name())) {
                return r;
            }
        }
        return null;
    }

    private static int starred(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> ((List<?>) ModUnderTest.staticCall("com.killer560.hub.mobesp.MobEspFeature",
                "starredMobs", new Class<?>[]{Minecraft.class}, new Object[]{mc})).size());
    }

    /** Smooth stone slabs within 16 blocks of the room's center cell and 30 of his feet's height (the lid is 35). */
    private static int lidSlabs(ClientGameTestContext ctx) {
        SimAutoClearTests.Room r = room(ctx);
        if (r == null) {
            return -1;
        }
        return ctx.computeOnClient(mc -> {
            int n = 0;
            // Around his feet's height: the sim moves a floor's altitude (SimAltitude), so no fixed y band.
            int y0 = mc.player.getBlockY() - 30;
            for (int dx = -16; dx <= 16; dx++) {
                for (int dz = -16; dz <= 16; dz++) {
                    for (int y = y0; y <= y0 + 60; y++) {
                        if (mc.level.getBlockState(new BlockPos(r.x() + dx, y, r.z() + dz)).is(Blocks.SMOOTH_STONE_SLAB)) {
                            n++;
                        }
                    }
                }
            }
            return n;
        });
    }

    static void println(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}
