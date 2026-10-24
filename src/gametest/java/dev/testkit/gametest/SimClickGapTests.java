package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.harness.ClickPrimer;
import dev.testkit.harness.HypixelUseAfterClick;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 1015-sim-clickgap (rewritten on mod branch ar-nexttick-1015). Hypixel's 1.8 server takes a bare item use as the second
 * half of an unpaired right click on a block when its ray along the look it last HEARD meets a block
 * ({@link HypixelUseAfterClick}); the mod's senders turn to the use's look first when that would happen
 * ({@code util/RightClick}) and otherwise do not wait at all. This proves the two trips that chain etherwarps a tick
 * apart: the Interactive Map's (a map press, {@code AutoClearUtils.pathToRoom} - the same path Auto Secret and the
 * Dungeon Autopilot take to a room) and Auto Clear's (a trip to a room's starred mob, {@code AutoClearFeature.clearRoom}).
 * Only when named; cheat jar, a whole generated sim F7.
 *
 * <p>{@link ClickPrimer} plays Secret Aura after EVERY tick that sent an item use: the body turned straight down and a
 * block clicked with the Aspect of the Void in hand, so each next hop is due while a click is unpaired and the server's
 * look meets the floor. A mod that sends the hop in the tick it turns has it ignored by the integrated server (counted in
 * {@code HypixelUseAfterClick.dropped}); one that turns first has none ignored. Premises asserted first, so a pass cannot
 * be vacuous: a raw use under a heard look that meets a block, a tick after a click, IS ignored in this world (the rule is
 * live), the same press with no clicks lands with nothing ignored, and every primed run must have made 2+ hops and clicked
 * after each, moved 20+ blocks, and (the trip's own proof) the mob died / the room's call-back came.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimClickGapTests implements FabricClientGameTest {

    private static final String NAME = "1015-sim-clickgap";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String SIM_ITEMS = "com.killer560.hub.roomsim.SimItems";
    private static final String SIM_MOBS = "com.killer560.hub.roomsim.SimMobs";
    private static final String SIM_ROOM_STATE = "com.killer560.hub.roomsim.SimRoomState";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String LIVE_MAP_CONFIG = "com.killer560.hub.livemap.LiveMapConfig";
    private static final String CLEAR_UTILS = "com.killer560.hub.livemap.autoclear.AutoClearUtils";
    private static final String EXECUTOR = "com.killer560.hub.livemap.autoclear.ClearExecutor";
    private static final String AUTO = "com.killer560.hub.autoclear.AutoClearFeature";
    private static final String AUTO_CONFIG = "com.killer560.hub.autoclear.AutoClearConfig";
    private static final String PUZZLES_CONFIG = "com.killer560.hub.autopuzzles.AutoPuzzlesConfig";
    private static final int GRID = 11;

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
        int renderBefore = ctx.computeOnClient(mc -> mc.options.renderDistance().get());
        List<String> failures = new ArrayList<>();
        try {
            ctx.runOnClient(mc -> {
                ModUnderTest.set(ModUnderTest.config(PUZZLES_CONFIG), "setAutoPuzzlesMasterEnabled", false);
                Object map = ModUnderTest.config(LIVE_MAP_CONFIG);
                ModUnderTest.set(map, "setEnabled", true);
                ModUnderTest.set(map, "setInteractiveMapEnabled", true);
                mc.options.renderDistance().set(16);
            });
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter", new Class<?>[]{String.class},
                    new Object[]{"gametest"}));
            // The sim counts a mob room cleared the moment he walks in; the Auto Clear case needs its mob to be killed.
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_MOBS, "setClearRoomsOnEntry", new Class<?>[]{boolean.class},
                    new Object[]{false}));
            body(ctx, failures);
        } catch (Throwable t) {
            failures.add("scenario threw: " + t);
            t.printStackTrace(System.out);
        } finally {
            ctx.runOnClient(mc -> {
                ClickPrimer.reset();
                HypixelUseAfterClick.reset();
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
        // ---- a floor with a mob room far from the spawn -----------------------------------------------------------
        SimAutoClearTests.Room target = null;
        List<SimAutoClearTests.Room> rooms = null;
        for (int attempt = 1; attempt <= 4 && target == null; attempt++) {
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
            double best = 0;
            for (SimAutoClearTests.Room r : rooms) {
                if (r.mob() && !r.cleared() && SimAutoClearTests.dist(me, r) > best) {
                    best = SimAutoClearTests.dist(me, r);
                    target = r;
                }
            }
            println("floor " + attempt + ": " + rooms.size() + " rooms; farthest mob room "
                    + (target == null ? "none" : target.name() + " " + String.format(java.util.Locale.US, "%.0f", best)
                    + " blocks away"));
            if (target != null && best < 60) {
                target = null;   // a trip of 2+ hops needs real distance
            }
        }
        if (target == null) {
            failures.add("no generated floor in 4 had a mob room 60+ blocks from the spawn");
            return;
        }
        SimEtherTicksTests.giveAotv(ctx, failures);
        // Slot 2 gets the Spirit Sceptre for the Auto Clear case; slot 0 (the AOTV) stays selected.
        giveSceptre(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimRun", "begin",
                new Class<?>[]{Minecraft.class, BlockPos.class},
                new Object[]{mc, ModUnderTest.staticCall("com.killer560.hub.roomsim.SimBuilder", "entranceDoor")}));
        for (int i = 0; i < 400 && !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(
                "com.killer560.hub.roomsim.SimRun", "isRunning")); i++) {
            ctx.waitTicks(1);
        }
        ctx.waitTicks(100);   // the floor graph warms (Interactive Map on, in a dungeon)
        home = ctx.computeOnClient(mc -> mc.player.position());
        controlRoom = null;

        // ---- the premise: in this world a use sent in the same tick as a same-item block click IS ignored -------
        rawControl(ctx, failures);

        // ---- Auto Clear: a trip to the room's starred mob, with a click after every hop (first: the target is far from the
        // spawn, and the map presses below end wherever the farthest room is) -------------------------------------
        autoClearCase(ctx, failures, rooms, target);

        // ---- Interactive Map: a press, no clicks (control), then a press with a click after every hop ------------
        imapCase(ctx, failures, false);
        imapCase(ctx, failures, true);
    }

    private static void giveSceptre(ClientGameTestContext ctx) {
        AtomicReference<Boolean> given = new AtomicReference<>();
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
        ctx.waitTicks(5);
    }

    /** Looking straight down, a block click and a tick later a raw use: the server must ignore the use, or the hook means
     *  nothing here. */
    private static void rawControl(ClientGameTestContext ctx, List<String> failures) {
        ctx.runOnClient(mc -> {
            ClickPrimer.reset();
            HypixelUseAfterClick.reset();
            HypixelUseAfterClick.enabled = true;
            mc.player.setXRot(90f);
        });
        ctx.waitTicks(5);
        ctx.runOnClient(mc -> {
            BlockPos far = ClickPrimer.storableOutOfReach(mc);
            if (far == null) {
                return;
            }
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(far), Direction.UP, far, false));
        });
        ctx.waitTicks(1);
        ctx.runOnClient(mc -> mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND));
        ctx.waitTicks(10);
        int clicks = HypixelUseAfterClick.clicks;
        int dropped = HypixelUseAfterClick.dropped;
        ctx.runOnClient(mc -> HypixelUseAfterClick.reset());
        println("raw control: looking down, a block click and a use of the same item a tick later - block clicks the hook "
                + "saw " + clicks + ", uses ignored " + dropped + " (" + HypixelUseAfterClick.lastDrop + ")");
        if (clicks < 1 || dropped < 1) {
            failures.add("raw control: the use sent with the click was not ignored (clicks " + clicks + ", ignored " + dropped
                    + ") - the rule is not live in this world, so no case below proves anything");
        }
    }

    /** Where the Interactive Map cases start from (the spawn), and the room the control press went to. */
    private static Vec3 home;
    private static int[] controlRoom;
    private static int controlCorrections;

    /** A server teleport back to {@link #home}, then a settle: both presses start from the same spot. */
    private static void goHome(ClientGameTestContext ctx) {
        AtomicReference<Boolean> done = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            UUID uuid = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(uuid);
                sp.teleportTo(server.overworld(), home.x, home.y, home.z, java.util.Set.<net.minecraft.world.entity.Relative>of(),
                        sp.getYRot(), sp.getXRot(), false);
                done.set(true);
            });
        });
        ctx.waitFor(mc -> done.get() != null, 200);
        ctx.waitTicks(20);
    }

    private static void imapCase(ClientGameTestContext ctx, List<String> failures, boolean primed) {
        String label = primed ? "imap+clicks" : "imap control";
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
        goHome(ctx);
        List<int[]> rooms = ctx.computeOnClient(SimEtherTicksTests::farthestRooms);
        if (primed && controlRoom != null) {
            rooms = List.of(controlRoom);   // the very trip the control flew, now with a click after every hop
        }
        for (int attempt = 0; attempt < Math.min(5, rooms.size()); attempt++) {
            int[] t = rooms.get(attempt);
            for (int i = 0; i < 100 && !ctx.computeOnClient(mc -> mc.player.onGround()); i++) {
                ctx.waitTicks(1);
            }
            Vec3 from = ctx.computeOnClient(mc -> mc.player.position());
            long hops0 = ctx.computeOnClient(mc -> SimEtherTicksTests.hopMark());
            long mark = LogTap.mark();
            ctx.runOnClient(mc -> {
                ClickPrimer.reset();
                HypixelUseAfterClick.reset();
                HypixelUseAfterClick.enabled = true;
                ClickPrimer.enabled = primed;
                ClickPrimer.onlySlot = 0;
            });
            Boolean ok = ctx.computeOnClient(mc -> {
                Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
                return (Boolean) ModUnderTest.staticCall(CLEAR_UTILS, "pathToRoom",
                        new Class<?>[]{layout.getClass(), int.class, int.class, int.class},
                        new Object[]{layout, t[1], t[0], 0});
            });
            boolean busySeen = false;
            for (int i = 0; i < 900 && Boolean.TRUE.equals(ok); i++) {
                ctx.waitTicks(1);
                boolean busy = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(EXECUTOR, "isBusy"));
                busySeen |= busy;
                if (busySeen && !busy) {
                    break;
                }
            }
            ctx.waitTicks(5);
            int hops = ctx.computeOnClient(mc -> SimEtherTicksTests.hopsSince(hops0).size());
            int uses = ClickPrimer.uses;
            int clicks = ClickPrimer.primed;
            int seen = HypixelUseAfterClick.clicks;
            int dropped = HypixelUseAfterClick.dropped;
            boolean held = LogTap.since(mark).stream().anyMatch(l -> l.contains("turning to the use's look first"));
            int corrections = (int) LogTap.since(mark).stream().filter(l -> l.contains("[Path] off the plan:")).count();
            Vec3 to = ctx.computeOnClient(mc -> mc.player.position());
            double moved = from.distanceTo(to);
            ctx.runOnClient(mc -> {
                ClickPrimer.reset();
                HypixelUseAfterClick.reset();
            });
            println(String.format(java.util.Locale.US, "%s: press to room %d %s; %d hop(s), %d use(s) seen, %d click(s) made, "
                            + "%d block click(s) the server saw, %d use(s) ignored, turned-first line %s, moved %.1f blocks, off-the-plan lines %d",
                    label, t[1], Boolean.TRUE.equals(ok) ? "taken" : "REFUSED", hops, uses, clicks, seen, dropped, held, moved,
                    corrections));
            if (hops < 2 || moved < 20) {
                continue;   // too close or unreachable: the next farthest
            }
            if (!primed) {
                controlRoom = t;
                controlCorrections = corrections;
            } else if (corrections > controlCorrections) {
                failures.add(label + ": " + corrections + " off-the-plan line(s) against the control's " + controlCorrections
                        + " on the same trip - a held hop landed somewhere the plan did not expect");
            }
            if (primed) {
                if (clicks < hops - 1 || seen < clicks) {
                    failures.add(label + ": " + hops + " hop(s) but " + clicks + " click(s) made / " + seen
                            + " seen by the server - the clicks did not precede the hops, so this proves nothing");
                }
            } else if (clicks != 0 || seen != 0) {
                failures.add(label + ": clicks were made in the control run");
            }
            if (dropped != 0) {
                failures.add(label + ": " + dropped + " hop(s) were ignored by the server (taken as an unpaired block click's "
                        + "second half)");
            }
            return;
        }
        failures.add(label + ": no press made 2+ hops over 20+ blocks to measure");
        if (!primed) {
            controlRoom = null;
        }
    }

    private static void autoClearCase(ClientGameTestContext ctx, List<String> failures,
                                      List<SimAutoClearTests.Room> rooms, SimAutoClearTests.Room target) {
        // Every other mob room cleared first (what the map would show), so the only trip is to this room.
        for (SimAutoClearTests.Room r : rooms) {
            if (r.mob() && !r.name().equals(target.name())) {
                ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_ROOM_STATE, "markCleared",
                        new Class<?>[]{String.class}, new Object[]{r.name()}));
            }
        }
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_ROOM_STATE, "clearRoom", new Class<?>[]{String.class},
                new Object[]{target.name()}));
        ctx.waitTicks(5);
        // Back to the spawn side, so the trip to the far room is long.
        List<SimAutoClearTests.Placed> placed = SimAutoClearTests.spawnIn(ctx, target, 1, false);
        if (placed.isEmpty()) {
            failures.add("autoclear: could not place a starred mob in " + target.name());
            return;
        }
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> {
            Object cfg = ModUnderTest.config(AUTO_CONFIG);
            ModUnderTest.set(cfg, "setEnabled", true);
            ModUnderTest.set(cfg, "setHyperionHops", false);
        });
        SimAutoClearTests.setMode(ctx, "ANY_MOB_ROOM", "SPIRIT_SCEPTRE");
        ctx.runOnClient(mc -> {
            mc.player.getInventory().setSelectedSlot(0);
            ClickPrimer.reset();
            HypixelUseAfterClick.reset();
            HypixelUseAfterClick.enabled = true;
            ClickPrimer.enabled = true;
            ClickPrimer.onlySlot = 0;   // the AOTV hops; the Spirit Sceptre's uses are left alone
        });
        AtomicReference<String> done = new AtomicReference<>();
        AtomicReference<String> gaveUp = new AtomicReference<>();
        long mark = LogTap.mark();
        SimAutoClearTests.Result r = SimAutoClearTests.runCase(ctx, "autoclear", () -> ModUnderTest.staticCall(AUTO, "clearRoom",
                new Class<?>[]{String.class, Runnable.class, Consumer.class},
                new Object[]{target.name(), (Runnable) () -> done.set("done"), (Consumer<String>) gaveUp::set}));
        Map<UUID, String> alive = SimAutoClearTests.aliveReport(ctx, placed);
        int uses = ClickPrimer.uses;
        int clicks = ClickPrimer.primed;
        int seen = HypixelUseAfterClick.clicks;
        int dropped = HypixelUseAfterClick.dropped;
        boolean held = LogTap.since(mark).stream().anyMatch(l -> l.contains("turning to the use's look first"));
        ctx.runOnClient(mc -> {
            ClickPrimer.reset();
            HypixelUseAfterClick.reset();
        });
        println(String.format("autoclear+clicks: %s; onDone=%s onGiveUp=%s; mob %s; %d AOTV use(s) seen, %d click(s) made, "
                + "%d block click(s) the server saw, %d use(s) ignored, turned-first line %s", r, done.get(), gaveUp.get(), alive,
                uses, clicks, seen, dropped, held));
        if (!r.finished || done.get() == null || gaveUp.get() != null) {
            failures.add("autoclear: the trip did not finish (finished " + r.finished + ", onDone " + done.get() + ", onGiveUp "
                    + gaveUp.get() + ")");
        }
        if (!"dead".equals(alive.get(placed.get(0).id()))) {
            failures.add("autoclear: the starred mob is " + alive.get(placed.get(0).id()));
        }
        if (uses < 2 || clicks < uses - 1 || seen < clicks || r.travelled < 20) {
            failures.add("autoclear: only " + uses + " AOTV use(s), " + clicks + " click(s) made, " + seen + " seen by the "
                    + "server, traveled " + String.format(java.util.Locale.US, "%.0f", r.travelled)
                    + " - the clicks did not precede 2+ hops, so this proves nothing");
        }
        if (dropped != 0) {
            failures.add("autoclear: " + dropped + " use(s) were ignored by the server (taken as an unpaired block click's second half)");
        }
    }

    private static void println(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}
