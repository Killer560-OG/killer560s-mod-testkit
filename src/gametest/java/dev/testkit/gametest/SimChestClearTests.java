package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * killer560's chest rule (mod chestclear-1020, 2026-10-20): once Auto Clear or Auto Secret grabs a mob room's secret chest,
 * the room counts as cleared in their eyes - not at once, a few seconds (3 s) later - and if the map still does not show
 * it cleared 30 s after the chest, it may be come back to, once per room per run.
 *
 * <p>A generated sim F3 with clear-on-entry off and no mobs placed, so a mob room never clears on the map and Auto Clear
 * finds nothing to kill in it: without the rule its single-room clear (Auto Secret's {@code clearRoom}) looks, roams and
 * gives up. He is put in a mob room that has a secret chest, the clear starts, and he clicks the chest by hand with an
 * empty hand (as Secret Aura would under it). The case needs: the rule's "grabbed" line; {@code clearRoom}'s onDone between
 * 3 s and 3 s + 20 ticks after the click, never onGiveUp; then, 30 s after the click, the "may come back" line,
 * {@code clearedInItsEyes} false, and {@code drainReturns} giving the room once and then nothing (bounded).
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimChestClearTests implements FabricClientGameTest {

    private static final String NAME = "1020-sim-chestclear";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String SIM_MOBS = "com.killer560.hub.roomsim.SimMobs";
    private static final String SIM_ITEMS = "com.killer560.hub.roomsim.SimItems";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String AUTO = "com.killer560.hub.autoclear.AutoClearFeature";
    private static final String AUTO_CONFIG = "com.killer560.hub.autoclear.AutoClearConfig";
    private static final String RULE = "com.killer560.hub.autoclear.ChestClearRule";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";

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
            Object map = ModUnderTest.config("com.killer560.hub.livemap.LiveMapConfig");
            ModUnderTest.set(map, "setEnabled", true);
            ModUnderTest.set(map, "setInteractiveMapEnabled", true);
            ModUnderTest.set(ModUnderTest.config("com.killer560.hub.cheatutils.CheatUtilsConfig"), "setSecretAuraEnabled", false);
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
        if (!failures.isEmpty()) {
            System.out.println("[" + NAME + "] FAIL - " + String.join("; ", failures));
            throw new AssertionError(failures.size() + " problem(s): " + String.join("; ", failures));
        }
        System.out.println("[" + NAME + "] PASS");
    }

    private static void body(ClientGameTestContext ctx, List<String> failures) {
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> {
            Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F3");
            ModUnderTest.staticCall(FLOOR_GEN, "generate",
                    new Class<?>[]{Minecraft.class, floor.getClass(), int.class, int.class},
                    new Object[]{mc, floor, 3, 4});
        }));
        ctx.waitFor(mc -> mc.level != null);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitTicks(80);
        SimAutoClearTests.awaitFloorChunks(ctx);
        // Loadout: the AOTV for the map's trips, a Hyperion, an empty hand on slot 4 for the chest click.
        AtomicReference<Boolean> given = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            UUID uuid = mc.player.getUUID();
            server.execute(() -> {
                var inv = server.getPlayerList().getPlayer(uuid).getInventory();
                for (int i = 0; i < 36; i++) {
                    inv.setItem(i, ItemStack.EMPTY);
                }
                String[] ids = {"ASPECT_OF_THE_VOID", "HYPERION"};
                for (int i = 0; i < ids.length; i++) {
                    inv.setItem(i, (ItemStack) ModUnderTest.staticCall(SIM_ITEMS, "build", new Class<?>[]{String.class},
                            new Object[]{ids[i]}));
                }
                given.set(true);
            });
        });
        ctx.waitFor(mc -> given.get() != null, 200);
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimRun", "begin",
                new Class<?>[]{Minecraft.class, BlockPos.class},
                new Object[]{mc, ModUnderTest.staticCall("com.killer560.hub.roomsim.SimBuilder", "entranceDoor")}));
        for (int i = 0; i < 400 && !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(
                "com.killer560.hub.roomsim.SimRun", "isRunning")); i++) {
            ctx.waitTicks(1);
        }
        ctx.waitTicks(20);

        // ---- a mob room with a secret chest: the sim's own list of placed chests (most only appear when he comes near) ----
        List<SimAutoClearTests.Room> rooms = SimAutoClearTests.floor(ctx);
        List<String> mobRooms = new ArrayList<>();
        for (SimAutoClearTests.Room r : rooms) {
            if (r.mob() && !r.cleared()) {
                mobRooms.add(r.name());
            }
        }
        @SuppressWarnings("unchecked")
        List<BlockPos> placedChests = ctx.computeOnClient(mc -> {
            try {
                return new ArrayList<>((List<BlockPos>) Class.forName("com.killer560.hub.roomsim.SimSecrets")
                        .getField("PLACED_CHESTS").get(null));
            } catch (ReflectiveOperationException e) {
                return new ArrayList<BlockPos>();
            }
        });
        String room = null;
        BlockPos chest = null;
        for (BlockPos p : placedChests) {
            String name = ctx.computeOnClient(mc -> roomAt(p));
            if (name != null && mobRooms.contains(name)) {
                room = name;
                chest = p;
                break;
            }
        }
        println(placedChests.size() + " placed chest(s); mob rooms " + mobRooms + "; picked " + room + " chest "
                + (chest == null ? "none" : chest.toShortString()));
        if (room == null) {
            failures.add("no uncleared mob room on the floor had a secret chest");
            return;
        }
        final String target = room;
        final BlockPos chestAt = chest;
        // Stand on the chest's spot until it appears (as it does when a player is near), then on its top.
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            UUID uuid = mc.player.getUUID();
            server.execute(() -> server.getPlayerList().getPlayer(uuid).teleportTo(server.overworld(),
                    chestAt.getX() + 0.5, chestAt.getY() + 0.05, chestAt.getZ() + 0.5,
                    java.util.Set.<net.minecraft.world.entity.Relative>of(), 0f, 45f, false));
        });
        for (int t = 0; t < 200 && !ctx.computeOnClient(mc -> mc.level.getBlockState(chestAt).is(Blocks.CHEST)); t++) {
            ctx.waitTicks(1);
        }
        boolean chestThere = ctx.computeOnClient(mc -> mc.level.getBlockState(chestAt).is(Blocks.CHEST));
        if (!chestThere) {
            failures.add("the chest at " + chestAt.toShortString() + " never appeared");
            return;
        }
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            UUID uuid = mc.player.getUUID();
            server.execute(() -> server.getPlayerList().getPlayer(uuid).teleportTo(server.overworld(),
                    chestAt.getX() + 0.5, chestAt.getY() + 1.0, chestAt.getZ() + 0.5,
                    java.util.Set.<net.minecraft.world.entity.Relative>of(), 0f, 60f, false));
        });
        ctx.waitTicks(30);
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(4));
        ctx.waitTicks(3);

        ctx.runOnClient(mc -> {
            Object cfg = ModUnderTest.config(AUTO_CONFIG);
            ModUnderTest.set(cfg, "setEnabled", true);
        });
        long m = LogTap.mark();
        AtomicReference<Integer> doneAt = new AtomicReference<>();
        AtomicReference<String> gaveUp = new AtomicReference<>();
        int[] clock = {0};
        ctx.runOnClient(mc -> ModUnderTest.staticCall(AUTO, "clearRoom",
                new Class<?>[]{String.class, Runnable.class, Consumer.class},
                new Object[]{target, (Runnable) () -> doneAt.compareAndSet(null, clock[0]), (Consumer<String>) gaveUp::set}));
        ctx.waitTicks(2);
        clock[0] = 2;
        // The chest, by hand: an empty-hand click on its top face, as Secret Aura's under the clear would be.
        int clickAt = clock[0];
        ctx.runOnClient(mc -> mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(chestAt).add(0, 0.4, 0), Direction.UP, chestAt, false)));
        for (int t = 0; t < 400 && doneAt.get() == null && gaveUp.get() == null; t++) {
            ctx.waitTicks(1);
            clock[0]++;
            if (t == 3) {
                ctx.runOnClient(mc -> {
                    if (McCompat.screen(mc) != null) {
                        mc.player.closeContainer();
                    }
                });
            }
        }
        boolean grabbed = false;
        boolean clearedLine = false;
        for (String l : LogTap.since(m)) {
            if (l.contains("chest rule") || l.contains("secret click sent") || l.contains("[AutoClear] room cleared")
                    || l.contains("[AutoClear] done") || l.contains("[AutoClear] stopped") || l.contains("gave up")) {
                println("log: " + l);
            }
            grabbed |= l.contains("chest rule:") && l.contains("grabbed in " + target);
            clearedLine |= l.contains("chest rule: " + target + " counts as cleared now");
        }
        Integer done = doneAt.get();
        // clock[0] goes up AFTER each waitTicks(1), so an onDone in the k-th tick after the click reads clickAt + k - 1:
        // add the one back (it read 59 while the mod logged "3.0 s", 60 of its ticks, on main a091e9bd, 2026-10-25).
        int sinceClick = done == null ? -1 : done - clickAt + 1;
        println(String.format(Locale.ROOT, "%s: chest %s clicked at t=%d; rule grabbed %s, counted cleared %s; onDone %s "
                        + "(%d tick(s) after the click), onGiveUp %s", target, chestAt.toShortString(), clickAt, grabbed,
                clearedLine, done == null ? "never" : "t=" + done, sinceClick, gaveUp.get()));
        if (!grabbed) {
            failures.add("the rule never saw the chest grabbed under Auto Clear");
        }
        if (gaveUp.get() != null) {
            failures.add("Auto Clear gave up on " + target + ": " + gaveUp.get());
        }
        if (done == null) {
            failures.add("Auto Clear never handed " + target + " back as cleared after its chest");
        } else if (sinceClick < 60 || sinceClick > 80) {
            failures.add("handed back " + sinceClick + " tick(s) after the chest (expected 60-80: the 3 s rule)");
        }
        boolean mapCleared = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SIM_STATE.replace("SimState",
                "SimRoomState"), "isCleared", new Class<?>[]{String.class}, new Object[]{target}));
        if (mapCleared) {
            failures.add("the premise failed: the map shows " + target + " cleared (nothing for the rule to decide)");
        }
        ctx.runOnClient(mc -> ModUnderTest.staticCall(AUTO, "cancel"));

        // ---- 30 s after the chest: it may be come back to, once ----
        long m2 = LogTap.mark();
        while (clock[0] - clickAt < 30 * 20 + 20) {
            ctx.waitTicks(10);
            clock[0] += 10;
        }
        boolean eyes = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(RULE, "clearedInItsEyes",
                new Class<?>[]{String.class}, new Object[]{target}));
        @SuppressWarnings("unchecked")
        List<String> first = ctx.computeOnClient(mc -> (List<String>) ModUnderTest.staticCall(RULE, "drainReturns"));
        @SuppressWarnings("unchecked")
        List<String> second = ctx.computeOnClient(mc -> (List<String>) ModUnderTest.staticCall(RULE, "drainReturns"));
        boolean backLine = false;
        for (String l : LogTap.since(m2)) {
            if (l.contains("chest rule")) {
                println("log: " + l);
            }
            backLine |= l.contains("chest rule: " + target + " still not cleared");
        }
        println(String.format(Locale.ROOT, "%s after 30 s: counts as cleared %s, come-back line %s, returns %s then %s",
                target, eyes, backLine, first, second));
        if (eyes) {
            failures.add(target + " still counted as cleared 31 s after its chest");
        }
        if (!backLine) {
            failures.add("no come-back line 30 s after the chest");
        }
        if (!first.equals(List.of(target)) || !second.isEmpty()) {
            failures.add("the come-back was not handed out exactly once (" + first + ", then " + second + ")");
        }
    }

    /** The live map's room name under {@code p}, or null. */
    private static String roomAt(BlockPos p) {
        Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
        int r = (Integer) ModUnderTest.call(layout, "roomAtWorld", new Class<?>[]{double.class, double.class},
                new Object[]{p.getX() + 0.5, p.getZ() + 0.5});
        return r < 0 ? null : (String) ModUnderTest.call(layout, "name", new Class<?>[]{int.class}, new Object[]{r});
    }

    static void println(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}
