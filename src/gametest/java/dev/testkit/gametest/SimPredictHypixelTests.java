package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.harness.Report;
import dev.testkit.harness.SuiteVerdict;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * 1018-predict-hypixel: Predictive Rooms the way a player on Hypixel meets it (killer560's to-do item 22, 2026-10-18: "I
 * dont see the option for looking into rooms and it knowing what it would be because I have seen enough of the room. If
 * it is on by default then it doesnt work.").
 *
 * <p>1726 (SimDoorsPredictTests) stares through each doorway from fifteen spots for 150 ticks and then calls the
 * predictor's {@code settle()} hook. A player does neither: he passes doorways, looking through each for a moment, and
 * the ticks run at their own pace. Here nothing calls {@code settle()} and every look is short and from one spot, so the
 * predictor gets only what a player sees from a room's entrance - on main that is 50-120 blocks a tile, under the 120 its
 * old threshold wanted, while every room but the right one was already ruled out.
 * <ul>
 * <li>1018-predict-hypixel-fresh: from two blocks short of a doorway, the room behind it must be named within
 *     {@link #GLANCE_TICKS} ticks (half a second), right.</li>
 * <li>1018-predict-hypixel-corridor: from {@link #CORRIDOR_BACK} blocks short of a doorway, where it is a narrow slot
 *     in his view, the room behind it must be named within {@link #CORRIDOR_TICKS} ticks (one second), right.</li>
 * <li>1018-predict-hypixel-walk: every other room of the floor walked as a player clears it (stood in, looked round,
 *     looked out of each doorway for {@link #DOOR_LOOK_TICKS} ticks from 8 and from 3 blocks back): at least
 *     {@link #WALK_MIN_NAMED} of the rooms looked into named right before he reaches them, and none named wrong.</li>
 * <li>1018-predict-hypixel-late: after that walk, the fresh glance into a room not looked into yet, named within
 *     {@link #CORRIDOR_TICKS} ticks.</li>
 * </ul>
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimPredictHypixelTests implements FabricClientGameTest {

    private static final String NAME = "1018-predict-hypixel";
    private static final String R_FRESH = "1018-predict-hypixel-fresh";
    private static final String R_CORRIDOR = "1018-predict-hypixel-corridor";
    private static final String R_WALK = "1018-predict-hypixel-walk";
    /** The corridor glance: this far short of the doorway, named within one second (killer560, 2026-10-18: about 20 s
     *  in game after he stopped in a corridor; "near instant" wanted). */
    private static final double CORRIDOR_BACK = 10;
    private static final int CORRIDOR_TICKS = 20;
    private static final String R_LATE = "1018-predict-hypixel-late";
    /** Every directed doorway of several random floors glanced at from the corridor distance; prints each map code that fails. */
    private static final String SWEEP = "1123-predict-corridor-sweep";
    /** The floors (map codes) a sweep found failing, each glanced at again from the same spot (roompredictor-1123-fix2). */
    private static final String PINNED = "1123-predict-corridor-pinned";
    /** {map code, room he stands in, room he looks into} of each pinned glance. */
    private static final String[][] PINS = {
            // sweep rp2-sweep4 (the third floor of its world): Buttons (4 tiles) ruled out through the doorway from Supertall
            {"MC2:CxUIRW50cmFuY2UMR29sZGVuIE9hc2lzBUZhaXJ5BU1pbmVzBUZsYWdzBk11c2V1bQVCbG9vZARab2RkBU1vc3N5BUF0bGFzCU92ZXJncm93bg1UZWxlcG9ydCBNYXplCEljZSBQYXRoCE9sZCBUcmFwCEFuZGVzaXRlDEhpZ2hlciBCbGF6ZQxCaWcgUmVkIEZsYWcLTG9ja2VkIEF3YXkHUmFjY29vbgxUaHJlZSBGbG9vcnMMUmFyZSBQaWxsYXJzABUDAQAAABMAAAAAAA8BAQAAABAAAAAAAAEABAAAAAIAAAAAAAAAAQAAAAAAAQAAAAAAAAAAAAAAAAAAAAAAAQAAABEBAQAAABICAAAAAAoBAAoBAAoBAAAAABQAAAAAAAMCAQAAAAAAAAAAAAAAAAoBAAoBAAoBAAAAAQAAAAAAAQAAAAsCAAAAAAwCAAAAAAoBAAoBAAoBAAAAAAQBAAQBAAQBAQAAAAAAAQAAAAAAAAAAAAAAAQAAAAAAAAQBAAQBAAQBAAkCAAkCAAkCAAkCAAkCAAkCAAkCAAAAAAQBAAQBAAQBAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAQAAAAAAAQAAAAgCAQAAAAYCAAYCAAYCAAAAAAUBAAUBAAUBAAAAAA4DAAAAAAAAAAYCAAYCAAYCAAAAAAUBAAUBAAUBAAAAAAAAAAcCAwAAAAYCAAYCAAYCAQAAAAUBAAUBAAUBAQAAAA0B,MC2:CxUIRW50cmFuY2UFTWluZXMFRmFpcnkKQmxhY2sgRmxhZwZLbmlnaHQLU2NhZmZvbGRpbmcFQmxvb2QISWNlIFBhdGgGR3JhdmVsCE9sZCBUcmFwCVdhdGVyZmFsbA5Mb3RzIE9mIEZsb29ycw1UZWxlcG9ydCBNYXplDVF1YXJ0eiBLbmlnaHQLTG93ZXIgQmxhemUFQ3J5cHQJT3Zlcmdyb3duA1BpdBBPdmVyZ3Jvd24gQ2hhaW5zB0RlZmF1bHQDRGlwAAEBAAAAAAkBAAkBAAkBAAkBAAkBAQAAAAsBAQAAAAwCBAAAAAAAAQAAAAAAAAAAAAAAAAAAAAAAAAsBAAAAAAAAAAIBAAIBAAIBAQAAAAgBAAAAABIBAAAAAAsBAAAAAA8BAAIBAAIBAAIBAAAAAAAAAAAAABIBAAAAAAsBAAAAAQAAAAIBAAIBAAIBAQAAAAoCAAAAABIBAAAAAAsBAAAAAA4AAQAAAAAAAAAAAAAAAAAAAAAAABIBAAAAAAsBAAAAAA4AAAMCAAAAAAYBAwAAAAcAAAAAABIBAAAAAAsBAQAAAA4AAQAAAAAAAQAAAAAAAAAAAAAAABIBAAAAAQAAAAAAAA4AAAQBAQAAAAUDAAAAABUBAAAAABIBAAAAAA0AAAAAAA4AAAAAAAAAAAAAAAAAAQAAAAAAAQAAAAAAAAAAAAAAAA4AABQCAQAAABMCAQAAABEBAQAAABACABACABACAQAAAA4A,MC2:CxUIRW50cmFuY2UGRHVuY2FuCVN1cGVydGFsbAdCdXR0b25zBUZhaXJ5A0VuZAVCbG9vZAVBdGxhcwhJY2UgRmlsbARRdWl6CE9sZCBUcmFwCU92ZXJncm93bgVTdGVwcwtMb3dlciBCbGF6ZQlXYXRlcmZhbGwGRHVlY2VzBlNwaWtlcw1RdWFydHogS25pZ2h0BVNsb3RoBkxlYXZlcwpSYWlsIFRyYWNrAAIDAQAAAAMBAAMBAAMBAQAAAAQCAAQCAAQCAQAAAAoABAAAAAAAAAMBAAMBAAMBAAAAAAQCAAQCAAQCAAAAAAAAAAEDAAAAAAMBAAMBAAMBAAAAAAQCAAQCAAQCAAAAABMBAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAAAAQAAAAAAAQAAAA4BAAAAAAgBAAgBAAgBAAAAABUDAAAAAAUAAAAAABIAAQAAAAAAAAgBAAgBAAgBAAAAAQAAAAAAAQAAAAAAABIAAAwCAQAAAAgBAAgBAAgBAAAAABQDAAAAAAYBAAAAABIAAQAAAAAAAQAAAAAAAQAAAAAAAQAAAAAAAwAAAAAAABIAAA0BAAAAAAsDAAAAAAkDAAAAABADAAAAAAcBAAAAABIAAQAAAAAAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAABIAAA8AAA8AAA8AAA8AAA8AAA8AAA8AAQAAABEDAQAAABIA", "Supertall", "Buttons"},
            {"MC2:CxUIRW50cmFuY2UMR29sZGVuIE9hc2lzBUZhaXJ5BU1pbmVzBUZsYWdzBk11c2V1bQVCbG9vZARab2RkBU1vc3N5BUF0bGFzCU92ZXJncm93bg1UZWxlcG9ydCBNYXplCEljZSBQYXRoCE9sZCBUcmFwCEFuZGVzaXRlDEhpZ2hlciBCbGF6ZQxCaWcgUmVkIEZsYWcLTG9ja2VkIEF3YXkHUmFjY29vbgxUaHJlZSBGbG9vcnMMUmFyZSBQaWxsYXJzABUDAQAAABMAAAAAAA8BAQAAABAAAAAAAAEABAAAAAIAAAAAAAAAAQAAAAAAAQAAAAAAAAAAAAAAAAAAAAAAAQAAABEBAQAAABICAAAAAAoBAAoBAAoBAAAAABQAAAAAAAMCAQAAAAAAAAAAAAAAAAoBAAoBAAoBAAAAAQAAAAAAAQAAAAsCAAAAAAwCAAAAAAoBAAoBAAoBAAAAAAQBAAQBAAQBAQAAAAAAAQAAAAAAAAAAAAAAAQAAAAAAAAQBAAQBAAQBAAkCAAkCAAkCAAkCAAkCAAkCAAkCAAAAAAQBAAQBAAQBAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAQAAAAAAAQAAAAgCAQAAAAYCAAYCAAYCAAAAAAUBAAUBAAUBAAAAAA4DAAAAAAAAAAYCAAYCAAYCAAAAAAUBAAUBAAUBAAAAAAAAAAcCAwAAAAYCAAYCAAYCAQAAAAUBAAUBAAUBAQAAAA0B,MC2:CxUIRW50cmFuY2UFTWluZXMFRmFpcnkKQmxhY2sgRmxhZwZLbmlnaHQLU2NhZmZvbGRpbmcFQmxvb2QISWNlIFBhdGgGR3JhdmVsCE9sZCBUcmFwCVdhdGVyZmFsbA5Mb3RzIE9mIEZsb29ycw1UZWxlcG9ydCBNYXplDVF1YXJ0eiBLbmlnaHQLTG93ZXIgQmxhemUFQ3J5cHQJT3Zlcmdyb3duA1BpdBBPdmVyZ3Jvd24gQ2hhaW5zB0RlZmF1bHQDRGlwAAEBAAAAAAkBAAkBAAkBAAkBAAkBAQAAAAsBAQAAAAwCBAAAAAAAAQAAAAAAAAAAAAAAAAAAAAAAAAsBAAAAAAAAAAIBAAIBAAIBAQAAAAgBAAAAABIBAAAAAAsBAAAAAA8BAAIBAAIBAAIBAAAAAAAAAAAAABIBAAAAAAsBAAAAAQAAAAIBAAIBAAIBAQAAAAoCAAAAABIBAAAAAAsBAAAAAA4AAQAAAAAAAAAAAAAAAAAAAAAAABIBAAAAAAsBAAAAAA4AAAMCAAAAAAYBAwAAAAcAAAAAABIBAAAAAAsBAQAAAA4AAQAAAAAAAQAAAAAAAAAAAAAAABIBAAAAAQAAAAAAAA4AAAQBAQAAAAUDAAAAABUBAAAAABIBAAAAAA0AAAAAAA4AAAAAAAAAAAAAAAAAAQAAAAAAAQAAAAAAAAAAAAAAAA4AABQCAQAAABMCAQAAABEBAQAAABACABACABACAQAAAA4A,MC2:CxUIRW50cmFuY2UGRHVuY2FuCVN1cGVydGFsbAdCdXR0b25zBUZhaXJ5A0VuZAVCbG9vZAVBdGxhcwhJY2UgRmlsbARRdWl6CE9sZCBUcmFwCU92ZXJncm93bgVTdGVwcwtMb3dlciBCbGF6ZQlXYXRlcmZhbGwGRHVlY2VzBlNwaWtlcw1RdWFydHogS25pZ2h0BVNsb3RoBkxlYXZlcwpSYWlsIFRyYWNrAAIDAQAAAAMBAAMBAAMBAQAAAAQCAAQCAAQCAQAAAAoABAAAAAAAAAMBAAMBAAMBAAAAAAQCAAQCAAQCAAAAAAAAAAEDAAAAAAMBAAMBAAMBAAAAAAQCAAQCAAQCAAAAABMBAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAAAAQAAAAAAAQAAAA4BAAAAAAgBAAgBAAgBAAAAABUDAAAAAAUAAAAAABIAAQAAAAAAAAgBAAgBAAgBAAAAAQAAAAAAAQAAAAAAABIAAAwCAQAAAAgBAAgBAAgBAAAAABQDAAAAAAYBAAAAABIAAQAAAAAAAQAAAAAAAQAAAAAAAQAAAAAAAwAAAAAAABIAAA0BAAAAAAsDAAAAAAkDAAAAABADAAAAAAcBAAAAABIAAQAAAAAAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAABIAAA8AAA8AAA8AAA8AAA8AAA8AAA8AAQAAABEDAQAAABIA", "*", "*"},
    };
    private static final int SWEEP_FLOORS = Integer.getInteger("testkit.sweep.floors", 6);

    /** A glance into a room from its entrance: half a second. */
    private static final int GLANCE_TICKS = 10;
    /** Ticks watched after that, only to report how late a slow answer comes. */
    private static final int WATCH_TICKS = 200;
    /** How long the walk looks out of each doorway, from each of two spots. */
    private static final int DOOR_LOOK_TICKS = 8;
    /** Rooms the walk must see named right. */
    private static final int WALK_MIN_NAMED = 4;

    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String HELD = "com.killer560.hub.livemap.HeldMap";
    private static final String PREDICTOR = "com.killer560.hub.livemap.predict.RoomPredictor";
    private static final String PCFG = "com.killer560.hub.livemap.predict.PredictiveRoomsConfig";
    private static final String VARIANT = "com.killer560.hub.BuildVariant";

    private final List<String> all = new ArrayList<>();
    private String mode = NAME;
    private boolean entered;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        mode = !Scenario.skip(NAME) ? NAME : !Scenario.skip(PINNED) ? PINNED : !Scenario.skip(SWEEP) ? SWEEP : null;
        if (System.getProperty("testkit.scenario", "").isBlank() || mode == null) {
            return;
        }
        ModUnderTest.require("killer560smod");
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (SimMapTests.copyRoomsForOthers() < 20) {
            Scenario.skipped(mode, "needs real room captures and room database");
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);
        try {
            boolean cheat = Class.forName(VARIANT).getField("CHEAT_FEATURES_ENABLED").getBoolean(null);
            println("jar: " + (cheat ? "cheat" : "legit") + "-" + net.fabricmc.loader.api.FabricLoader.getInstance()
                    .getModContainer("minecraft").orElseThrow().getMetadata().getVersion().getFriendlyString());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        try {
            if (mode.equals(NAME)) {
                body(ctx);
            } else if (mode.equals(PINNED)) {
                pinned(ctx);
            } else {
                sweep(ctx);
            }
        } catch (Throwable t) {
            all.add("scenario threw: " + t);
            t.printStackTrace(System.out);
        } finally {
            leave(ctx);
        }
        if (!all.isEmpty()) {
            throw new AssertionError(String.join(" | ", all));
        }
        println("PASS - every case passed");
    }

    private void body(ClientGameTestContext ctx) {
        Floor f = openFloor(ctx, "");

        // Doorways from one room into another (not the Entrance, not Blood) with nothing in the doorway.
        List<int[]> tries = f.directedDoors();
        Collections.shuffle(tries, new Random(1018));
        List<int[]> picks = new ArrayList<>();
        for (int[] t : tries) {
            boolean clash = false;
            for (int[] p : picks) {
                clash |= p[1] == t[1] || p[1] == t[2] || p[2] == t[1] || p[2] == t[2];
            }
            boolean open = ctx.computeOnClient(mc -> mc.level.getBlockState(f.doorBlock(t[0])).isAir()
                    && mc.level.getBlockState(f.doorBlock(t[0]).above()).isAir());
            // The corridor glance (third pick) needs an unobstructed line from where he stands to the doorway: a room's own
            // scenery (Mushroom's) in the way leaves nothing to see, which no predictor can name (roompredictor-1123).
            if (!clash && open && (picks.size() < 2 || clearLine(ctx, f, t, CORRIDOR_BACK))) {
                picks.add(t);
            }
            if (picks.size() == 3) {
                break;
            }
        }
        if (picks.size() < 3) {
            row(R_FRESH, false, "premise: fewer than three free doorways");
            return;
        }

        // ---- fresh: the glance is the first thing he sees.
        Report.caseStarted(R_FRESH);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(HELD, "resetSimVisits"));
        ctx.runOnClient(mc -> ModUnderTest.staticCall(PREDICTOR, "reset"));
        Glance fresh = glance(ctx, f, picks.get(0));
        row(R_FRESH, fresh.pass(), fresh.toString());

        // ---- corridor: from well back, the doorway a narrow slot in his view; few blocks reach the room behind it.
        Report.caseStarted(R_CORRIDOR);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(HELD, "resetSimVisits"));
        ctx.runOnClient(mc -> ModUnderTest.staticCall(PREDICTOR, "reset"));
        Glance corridor = glance(ctx, f, picks.get(2), CORRIDOR_BACK, CORRIDOR_TICKS);
        row(R_CORRIDOR, corridor.pass(), corridor.toString());

        // ---- walk: the rest of the floor as a player clears it; the room of the late glance not looked into.
        Report.caseStarted(R_WALK);
        int[] late = picks.get(1);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(HELD, "resetSimVisits"));
        ctx.runOnClient(mc -> ModUnderTest.staticCall(PREDICTOR, "reset"));
        // Its room counts as opened while the rest is looked at, so not one of its blocks is recorded; afterwards it
        // is unopened again, as a room he has not reached yet.
        ctx.runOnClient(mc -> ModUnderTest.staticCall(HELD, "simVisit", new Class<?>[]{int.class}, new Object[]{late[2]}));
        List<Integer> walked = new ArrayList<>();
        java.util.Set<String> namedRight = new java.util.TreeSet<>();
        java.util.Set<String> namedWrong = new java.util.TreeSet<>();
        int seen = walk(ctx, f, late, walked, namedRight, namedWrong);
        row(R_WALK, namedWrong.isEmpty() && namedRight.size() >= WALK_MIN_NAMED, walked.size() + " room(s) walked, "
                + seen + " block(s) seen; named right before he got there: " + namedRight.size() + " " + namedRight
                + " (need " + WALK_MIN_NAMED + "); named wrong: " + namedWrong);

        Report.caseStarted(R_LATE);
        int intoBefore = 0;
        for (int tile : f.tiles.get(late[2])) {
            intoBefore += ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(PREDICTOR, "evidence",
                    new Class<?>[]{int.class}, new Object[]{tile}));
        }
        // The rooms he walked stay opened, as on Hypixel; the one he glances into is not opened any more.
        ctx.runOnClient(mc -> ModUnderTest.staticCall(HELD, "resetSimVisits"));
        for (int r : walked) {
            ctx.runOnClient(mc -> ModUnderTest.staticCall(HELD, "simVisit", new Class<?>[]{int.class}, new Object[]{r}));
        }
        // One second: a room much like another (Flags beside the other flag rooms) can need a little more of its view.
        Glance g = glance(ctx, f, late, 2, CORRIDOR_TICKS);
        boolean premise = seen > 0 && intoBefore == 0;
        row(R_LATE, premise && g.pass(), "after " + seen + " block(s) seen elsewhere (" + intoBefore + " in "
                + f.names.get(late[2]) + "): " + g);
    }

    /** Generates a floor (the map code {@code code}, or a random one when blank), waits for GO and returns it read. */
    private Floor openFloor(ClientGameTestContext ctx, String code) {
        if (!entered) {
            entered = true;
            ctx.runOnClient(mc -> {
                Object p = ModUnderTest.config(PCFG);
                ModUnderTest.set(p, "setEnabled", true);
                mc.options.guiScale().set(2);
            });
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter", new Class<?>[]{String.class},
                    new Object[]{"gametest"}));
        }
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> {
            if (code.isBlank()) {
                Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
                ModUnderTest.staticCall(FLOOR_GEN, "generate",
                        new Class<?>[]{Minecraft.class, floor.getClass(), int.class, int.class},
                        new Object[]{mc, floor, 3, 4});
            } else {
                ModUnderTest.staticCall(SIM_STATE, "setFloorLabel", new Class<?>[]{String.class}, new Object[]{"F7"});
                java.util.function.Consumer<Minecraft> build = c -> ModUnderTest.staticCall(
                        "com.killer560.hub.roomsim.SimBuilder", "build", new Class<?>[]{Minecraft.class, String.class},
                        new Object[]{c, code});
                ModUnderTest.staticCall("com.killer560.hub.roomsim.SimWorld", "open", new Class<?>[]{Minecraft.class,
                        String.class, java.util.function.Consumer.class, String.class},
                        new Object[]{mc, code, build, "Generating Floor 7"});
            }
        }));
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitFor(mc -> McCompat.screen(mc) == null, 1200);
        ctx.waitFor(mc -> (mc.player.onGround() || mc.player.getAbilities().flying)
                && !mc.level.getBlockState(mc.player.blockPosition().below()).isAir(), 2400);
        ctx.waitTicks(40);
        // Every case is a moment of a run in progress, so wait for GO: during the countdown the sim has no held map
        // (HeldMap.emulated() is false, as on Hypixel before Mort), no room counts as opened, and a walk that starts
        // there records the blocks of the room the late glance must not have seen yet (hsA4f/hsA4g, 2026-11-04).
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.roomsim.SimRun", "hasStarted"), 600);
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(HELD, "emulated"), 200);
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> server.getCommands().performPrefixedCommand(
                    server.createCommandSourceStack().withSuppressedOutput(), "time set noon"));
        });
        Floor f = ctx.computeOnClient(SimPredictHypixelTests::readFloor);
        f.code = ctx.computeOnClient(mc -> String.valueOf(ModUnderTest.staticCall(SIM_STATE, "mapCode")));
        println("floor: " + f.names.size() + " rooms, " + f.doorRooms.size() + " doors, sim offset "
                + ctx.computeOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimAltitude", "offset"))
                + ", MAP CODE: " + f.code);
        creativeFlying(ctx);
        return f;
    }

    /** One corridor glance from a clean predictor, as the corridor case does it. */
    private Glance corridorGlance(ClientGameTestContext ctx, Floor f, int[] t) {
        return glance(ctx, f, t, CORRIDOR_BACK, CORRIDOR_TICKS, true);
    }

    /** True when door {@code t[0]} has nothing in it and a clear line from {@link #CORRIDOR_BACK} short of it. */
    private boolean corridorUsable(ClientGameTestContext ctx, Floor f, int[] t) {
        return ctx.computeOnClient(mc -> mc.level.getBlockState(f.doorBlock(t[0])).isAir()
                && mc.level.getBlockState(f.doorBlock(t[0]).above()).isAir()) && clearLine(ctx, f, t, CORRIDOR_BACK);
    }

    /** Several random floors, every doorway into a room (not the Entrance or Blood) glanced at from the corridor. */
    private void sweep(ClientGameTestContext ctx) {
        int glances = 0;
        List<String> bad = new ArrayList<>();
        for (int n = 0; n < SWEEP_FLOORS; n++) {
            glances += sweepFloor(ctx, openFloor(ctx, ""), bad);
        }
        Report.caseStarted(SWEEP);
        row(SWEEP, bad.isEmpty() && glances > 0, SWEEP_FLOORS + " floor(s), " + glances + " corridor glance(s), "
                + bad.size() + " failing: " + bad);
    }

    /** Every turn and anchor of room {@code into}'s own tiles as a placement, with what it disagrees with (a failed glance's evidence). */
    private void placements(ClientGameTestContext ctx, Floor f, int into) {
        for (int idx : f.tiles.get(into)) {
            for (int rot = 0; rot < 360; rot += 90) {
                String key = f.names.get(into) + "@" + rot + "@" + (idx % 11) / 2 + "," + (idx / 11) / 2;
                List<String> d = ctx.computeOnClient(mc -> (List<String>) ModUnderTest.staticCall(PREDICTOR,
                        "disagreements", new Class<?>[]{String.class, int.class}, new Object[]{key, 30}));
                if (!d.isEmpty()) {
                    println("PLACEMENT " + key + " disagrees on " + d.size() + (d.size() >= 30 ? "+" : "") + ": "
                            + d.subList(0, Math.min(8, d.size())));
                    if (rot == 0) {
                        mismatchBlocks(ctx, f, key, d);
                    }
                }
            }
        }
    }

    /**
     * For each position a placement disagrees on: the client's block now, the sim server's block now, and the grid cell
     * that owns it (room or connector, offset from the cell's middle), then the client's block again 40 ticks later. Tells
     * a sim block the capture lacks (server = client) from a stale client view (server = capture) and from a block that
     * changes over time (roompredictor-1123-fix3).
     */
    private void mismatchBlocks(ClientGameTestContext ctx, Floor f, String key, List<String> d) {
        var server = ctx.computeOnClient(Minecraft::getSingleplayerServer);
        var dim = ctx.computeOnClient(mc -> mc.level.dimension());
        int offset = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall("com.killer560.hub.roomsim.SimAltitude", "offset"));
        List<BlockPos> at = new ArrayList<>();
        for (String s : d) {
            String[] p = s.substring(0, s.indexOf(' ')).split(",");
            at.add(new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])));
        }
        List<String> client = ctx.computeOnClient(mc -> {
            List<String> out = new ArrayList<>();
            for (BlockPos b : at) {
                var chunk = mc.level.getChunk(b.getX() >> 4, b.getZ() >> 4);
                out.add(id(mc.level.getBlockState(b)) + " sect" + (chunk.getSection(chunk.getSectionIndex(b.getY())).hasOnlyAir() ? "Air" : "Blocks"));
            }
            return out;
        });
        List<String> srv;
        try {
            srv = server.submit(() -> {
                var lvl = server.getLevel(dim);
                List<String> out = new ArrayList<>();
                for (BlockPos b : at) {
                    out.add(lvl == null ? "?" : id(lvl.getBlockState(b)));
                }
                return out;
            }).get();
        } catch (Exception e) {
            srv = Collections.nCopies(at.size(), "err " + e);
        }
        ctx.waitTicks(40);
        List<String> later = ctx.computeOnClient(mc -> {
            List<String> out = new ArrayList<>();
            for (BlockPos b : at) {
                out.add(id(mc.level.getBlockState(b)));
            }
            return out;
        });
        for (int i = 0; i < at.size(); i++) {
            BlockPos b = at.get(i);
            int best = -1;
            long bestD = Long.MAX_VALUE;
            for (int c = 0; c < 121; c++) {
                BlockPos m = f.cellCenter(c);
                long dd = (long) (m.getX() - b.getX()) * (m.getX() - b.getX()) + (long) (m.getZ() - b.getZ()) * (m.getZ() - b.getZ());
                if (dd < bestD) {
                    bestD = dd;
                    best = c;
                }
            }
            BlockPos m = f.cellCenter(best);
            int owner = f.roomOf[best];
            println("MISMATCH " + key + " " + d.get(i) + " | client=" + client.get(i) + " server=" + srv.get(i)
                    + " client+40t=" + later.get(i) + " | cell " + best % 11 + "," + best / 11
                    + ((best % 11) % 2 == 1 || (best / 11) % 2 == 1 ? " (connector)" : " (room)")
                    + " owner=" + (owner < 0 ? "none" : f.names.get(owner)) + " d=" + (b.getX() - m.getX()) + "," + (b.getZ() - m.getZ())
                    + " simOffset=" + offset + " capY=" + (b.getY() - offset));
        }
    }

    private static String id(net.minecraft.world.level.block.state.BlockState s) {
        return s.getBlock().getDescriptionId().replace("block.minecraft.", "");
    }

    /** Where nine rays through the doorway end, from where he stands now (a failed glance's geometry). */
    private String rays(ClientGameTestContext ctx, Floor f, int[] t) {
        return ctx.computeOnClient(mc -> {
            StringBuilder sb = new StringBuilder("into room " + t[2] + " opened in sim: "
                    + java.util.Arrays.toString((int[]) ModUnderTest.staticCall(HELD, "simVisitedRooms"))
                    + ", he at " + mc.player.blockPosition().toShortString() + " facing " + mc.player.getYRot()
                    + "/" + mc.player.getXRot() + ", door " + f.doorBlock(t[0]).toShortString() + ", rays");
            var eye = mc.player.getEyePosition();
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    var look = net.minecraft.world.phys.Vec3.directionFromRotation(mc.player.getXRot() + dy * 4,
                            mc.player.getYRot() + dx * 4);
                    var hit = mc.level.clip(new net.minecraft.world.level.ClipContext(eye, eye.add(look.scale(40)),
                            net.minecraft.world.level.ClipContext.Block.VISUAL,
                            net.minecraft.world.level.ClipContext.Fluid.NONE, mc.player));
                    sb.append(' ').append(Math.round(hit.getLocation().distanceTo(eye))).append(':')
                            .append(mc.level.getBlockState(hit.getBlockPos()).getBlock().getDescriptionId()
                                    .replace("block.minecraft.", ""));
                }
            }
            return sb.toString();
        });
    }

    /** Every usable directed doorway of {@code f} glanced at in turn; failures go in {@code bad}. Returns the glances made. */
    private int sweepFloor(ClientGameTestContext ctx, Floor f, List<String> bad) {
        int glances = 0;
        for (int[] t : f.directedDoors()) {
            if (!corridorUsable(ctx, f, t)) {
                continue;
            }
            glances++;
            Glance g = corridorGlance(ctx, f, t);
            if (!g.pass()) {
                println("SWEEP FAIL MAP CODE: " + f.code + " | " + g + " | " + rays(ctx, f, t));
                placements(ctx, f, t[2]);
                bad.add(f.names.get(t[1]) + " into " + f.names.get(t[2]) + " on " + f.code);
            }
        }
        return glances;
    }

    /** Each pinned floor rebuilt from its map code and glanced at from the doorway between the two named rooms. */
    private void pinned(ClientGameTestContext ctx) {
        if (PINS.length == 0) {
            row(PINNED, false, "premise: no pinned floors");
            return;
        }
        for (int i = 0; i < PINS.length; i++) {
            String name = PINNED + "-" + (i + 1);
            Report.caseStarted(name);
            // "a,b,c": the floors built in that order in the one world; the last is the one glanced at. A floor built over
            // an earlier one is what the corridor case always meets (1720 builds the first of the run).
            String[] chain = PINS[i][0].split(",");
            Floor f = null;
            for (String code : chain) {
                f = openFloor(ctx, code);
            }
            if (PINS[i][1].equals("*")) {
                List<String> bad = new ArrayList<>();
                int glances = sweepFloor(ctx, f, bad);
                row(name, glances > 0 && bad.isEmpty(), "every doorway of the floor, in order: " + glances + " glance(s), "
                        + bad.size() + " failing " + bad);
                continue;
            }
            int[] pick = null;
            for (int[] t : f.directedDoors()) {
                if (f.names.get(t[1]).equalsIgnoreCase(PINS[i][1]) && f.names.get(t[2]).equalsIgnoreCase(PINS[i][2])
                        && corridorUsable(ctx, f, t)) {
                    pick = t;
                    break;
                }
            }
            if (pick == null) {
                row(name, false, "premise: no usable doorway from " + PINS[i][1] + " into " + PINS[i][2] + " on " + f.code);
                continue;
            }
            Glance g = corridorGlance(ctx, f, pick);
            if (!g.pass()) {
                placements(ctx, f, pick[2]);
            }
            row(name, g.pass(), "pinned " + PINS[i][1] + " into " + PINS[i][2] + ": " + g);
        }
    }

    // ------------------------------------------------------------------------------------------------- the glance

    private final class Glance {
        String from;
        String into;
        int tiles;
        int firstObsTick = -1;
        int namedTick = -1;
        int oneRoomTick = -1;
        int oneRoomEvidence;
        int evidenceAtEnd;
        int obsAtStart;
        int obsAtEnd;
        String wrong;
        String lastStatus;
        double back;
        int limit;

        boolean pass() {
            return wrong == null && namedTick >= 0 && namedTick <= limit && obsAtEnd > obsAtStart;
        }

        @Override
        public String toString() {
            return "glance from " + back + " block(s) inside " + from + " into " + into + " (" + tiles
                    + " tile(s)): first block seen at tick " + firstObsTick + ", named at tick "
                    + (namedTick < 0 ? "never (watched " + (limit + WATCH_TICKS) + ")" : namedTick)
                    + " (must be by " + limit + "), one room left standing from tick "
                    + oneRoomTick + " (" + oneRoomEvidence + " seen), " + evidenceAtEnd + " of its block(s) seen, "
                    + obsAtStart + " -> " + obsAtEnd + " seen in all, status '" + lastStatus + "'"
                    + (wrong == null ? "" : ", WRONG: " + wrong);
        }
    }

    /** True when nothing solid lies between his eyes {@code back} blocks short of door {@code t[0]} and the doorway. */
    private boolean clearLine(ClientGameTestContext ctx, Floor f, int[] t, double back) {
        double[] dc = f.doorCentre(t[0]);
        double[] u = f.toward(t[1], t[0]);
        return ctx.computeOnClient(mc -> {
            for (double s = 0; s < back; s += 0.25) {
                BlockPos p = BlockPos.containing(dc[0] - u[0] * (back - s), dc[1] + 0.2 + 1.62 - 0.6 * s / back, dc[2] - u[1] * (back - s));
                if (!mc.level.getBlockState(p).isAir()) {
                    return false;
                }
            }
            return true;
        });
    }

    /** Stands two blocks short of door {@code t[0]} in room {@code t[1]}, looks into {@code t[2]}, and lets ticks run. */
    private Glance glance(ClientGameTestContext ctx, Floor f, int[] t) {
        return glance(ctx, f, t, 2, GLANCE_TICKS);
    }

    /** The same from {@code back} blocks short of the doorway; the room must be named within {@code limit} ticks. */
    private Glance glance(ClientGameTestContext ctx, Floor f, int[] t, double back, int limit) {
        return glance(ctx, f, t, back, limit, false);
    }

    /**
     * {@code cleanStart}: the sim's opened rooms and the predictor are reset once he stands in the glance's room, not
     * before he is placed. The sim opens the room he stands in on every tick, so a reset made while he still stands
     * where the last glance left him re-opens that room and, with glances that share rooms (a sweep), the room being
     * looked into: nothing in it is a target and 0 blocks are seen (roompredictor-1123-fix2).
     */
    private Glance glance(ClientGameTestContext ctx, Floor f, int[] t, double back, int limit, boolean cleanStart) {
        int d = t[0];
        int from = t[1];
        int into = t[2];
        Glance g = new Glance();
        g.from = f.names.get(from);
        g.into = f.names.get(into);
        g.tiles = f.tiles.get(into).length;
        g.back = back;
        g.limit = limit;
        double[] dc = f.doorCentre(d);
        double[] u = f.toward(from, d);
        double[] at = new double[]{dc[0] - u[0] * back, dc[1] + 0.2, dc[2] - u[1] * back};
        double[] target = new double[]{dc[0] + u[0] * 12, dc[1] + 1.0, dc[2] + u[1] * 12};
        // Counted before he is placed: the predictor can start seeing on the tick he lands.
        g.obsAtStart = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(PREDICTOR, "observationCount"));
        // The client chunks of the room he looks into are in before the clock starts (on Hypixel they are long since).
        place(ctx, at, target);
        ctx.waitFor(mc -> mc.level.isLoaded(BlockPos.containing(target[0], target[1], target[2])), 400);
        if (cleanStart) {
            ctx.runOnClient(mc -> ModUnderTest.staticCall(HELD, "resetSimVisits"));
            ctx.runOnClient(mc -> ModUnderTest.staticCall(PREDICTOR, "reset"));
            g.obsAtStart = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(PREDICTOR, "observationCount"));
        }
        int[] intoTiles = f.tiles.get(into);
        for (int tick = 1; tick <= limit + WATCH_TICKS; tick++) {
            ctx.waitTick();
            final int now = tick;
            Object[] r = ctx.computeOnClient(mc -> {
                int obs = (Integer) ModUnderTest.staticCall(PREDICTOR, "observationCount");
                int ev = 0;
                for (int tile : intoTiles) {
                    ev += (Integer) ModUnderTest.staticCall(PREDICTOR, "evidence", new Class<?>[]{int.class},
                            new Object[]{tile});
                }
                String named = null;
                String bad = null;
                for (Object p : (List<?>) ModUnderTest.staticCall(PREDICTOR, "predictions")) {
                    String pname = (String) inv(p, "name");
                    for (int tile : (int[]) inv(p, "certainTiles")) {
                        int actual = f.roomAt(tile);
                        if (actual == into && !f.names.get(into).equalsIgnoreCase(pname)) {
                            bad = pname + " on tile " + tile + " (really " + f.names.get(into) + ")";
                        }
                        if (actual == into && f.names.get(into).equalsIgnoreCase(pname)) {
                            named = pname;
                        }
                    }
                }
                java.util.Set<String> rooms = new java.util.HashSet<>();
                for (Object s : (List<?>) ModUnderTest.staticCall(PREDICTOR, "standing", new Class<?>[]{int.class},
                        new Object[]{intoTiles[0]})) {
                    rooms.add(String.valueOf(s).split("@")[0]);
                }
                return new Object[]{obs, ev, named, bad, ModUnderTest.staticCall(PREDICTOR, "status"), rooms.size()};
            });
            int ev = (Integer) r[1];
            if (g.firstObsTick < 0 && ev > 0) {
                g.firstObsTick = now;
            }
            if (g.oneRoomTick < 0 && ev > 0 && (Integer) r[5] == 1) {
                g.oneRoomTick = now;
                g.oneRoomEvidence = ev;
            }
            if (r[3] != null && g.wrong == null) {
                g.wrong = (String) r[3];
            }
            g.evidenceAtEnd = ev;
            g.obsAtEnd = (Integer) r[0];
            g.lastStatus = String.valueOf(r[4]);
            if (r[2] != null && g.namedTick < 0) {
                g.namedTick = now;
                break;
            }
        }
        println(g.toString());
        return g;
    }

    /**
     * A run up to the late glance: every room but the two of the glance (and Blood) is walked as a player clears it -
     * stood in, looked round, and looked out of through each of its doorways - and stays opened. Returns how many
     * blocks the predictor had seen by the end; {@code walked} receives the rooms walked.
     */
    private int walk(ClientGameTestContext ctx, Floor f, int[] glance, List<Integer> walked,
                     java.util.Set<String> right, java.util.Set<String> wrong) {
        int seen = 0;
        for (int r = 0; r < f.names.size(); r++) {
            if (r == glance[1] || r == glance[2] || r == f.blood) {
                continue;
            }
            walked.add(r);
            for (int tile : f.tiles.get(r)) {
                BlockPos c = f.cellCenter(tile);
                double[] at = new double[]{c.getX() + 0.5, f.doorY + 0.2, c.getZ() + 0.5};
                for (int yaw = 0; yaw < 360; yaw += 45) {
                    double yr = Math.toRadians(yaw);
                    place(ctx, at, new double[]{at[0] - Math.sin(yr) * 20, at[1] + 1.62, at[2] + Math.cos(yr) * 20});
                    ctx.waitTicks(6);
                    tally(ctx, f, right, wrong);
                }
            }
            for (Map.Entry<Integer, int[]> e : f.doorRooms.entrySet()) {
                int[] ab = e.getValue();
                if (ab[0] != r && ab[1] != r) {
                    continue;
                }
                double[] dc = f.doorCentre(e.getKey());
                double[] u = f.toward(r, e.getKey());
                for (double back : new double[]{8, 3}) {
                    place(ctx, new double[]{dc[0] - u[0] * back, dc[1] + 0.2, dc[2] - u[1] * back},
                            new double[]{dc[0] + u[0] * 12, dc[1] + 1.0, dc[2] + u[1] * 12});
                    ctx.waitTicks(DOOR_LOOK_TICKS);
                    tally(ctx, f, right, wrong);
                }
            }
            seen = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(PREDICTOR, "observationCount"));
        }
        // Ten seconds for the comparisons of everything seen so far to run at their own pace before the glance.
        ctx.waitTicks(200);
        println("walk: " + walked.size() + " room(s) walked, " + seen + " block(s) seen");
        return seen;
    }

    /** Every prediction standing now, against the room really on each of its certain tiles. */
    private static void tally(ClientGameTestContext ctx, Floor f, java.util.Set<String> right, java.util.Set<String> wrong) {
        ctx.runOnClient(mc -> {
            for (Object p : (List<?>) ModUnderTest.staticCall(PREDICTOR, "predictions")) {
                String pname = (String) inv(p, "name");
                for (int tile : (int[]) inv(p, "certainTiles")) {
                    int actual = f.roomAt(tile);
                    if (actual >= 0 && f.names.get(actual).equalsIgnoreCase(pname)) {
                        right.add(pname);
                    } else {
                        wrong.add(pname + " on tile " + tile + " (really " + (actual < 0 ? "nothing" : f.names.get(actual)) + ")");
                    }
                }
            }
        });
    }

    // ------------------------------------------------------------------------------------------------- floor

    static Floor readFloor(Minecraft mc) {
        Floor f = new Floor();
        Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
        int rooms = (Integer) inv(layout, "roomCount");
        for (int r = 0; r < rooms; r++) {
            f.names.add((String) inv(layout, "name", r));
            f.tiles.add((int[]) inv(layout, "tiles", r));
            Object entry = inv(layout, "entry", r);
            String type = "";
            if (entry != null) {
                try {
                    Object t = entry.getClass().getField("type").get(entry);
                    type = t == null ? "" : t.toString().toUpperCase(Locale.ROOT);
                } catch (ReflectiveOperationException ignored) {
                    // no type
                }
            }
            if ("ENTRANCE".equals(type) || "Entrance".equalsIgnoreCase(f.names.get(r))) f.entrance = r;
            if ("BLOOD".equals(type) || "Blood".equalsIgnoreCase(f.names.get(r))) f.blood = r;
        }
        for (int idx = 0; idx < 121; idx++) {
            f.roomOf[idx] = (Integer) inv(layout, "roomOfCell", idx);
        }
        for (int idx = 0; idx < 121; idx++) {
            int gx = idx % 11;
            int gz = idx / 11;
            if ((gx % 2 == 1) == (gz % 2 == 1) || !(Boolean) inv(layout, "isDoor", idx)) {
                continue;
            }
            int a = gx % 2 == 1 ? f.roomOf[gz * 11 + gx - 1] : f.roomOf[(gz - 1) * 11 + gx];
            int b = gx % 2 == 1 ? f.roomOf[gz * 11 + gx + 1] : f.roomOf[(gz + 1) * 11 + gx];
            if (a >= 0 && b >= 0 && a != b) {
                f.doorRooms.put(idx, new int[]{a, b});
            }
        }
        BlockPos any = (BlockPos) ModUnderTest.staticCall(LAYOUT, "doorBlock", new Class<?>[]{int.class}, new Object[]{1});
        f.doorY = any.getY();
        return f;
    }

    static final class Floor {
        final List<String> names = new ArrayList<>();
        final List<int[]> tiles = new ArrayList<>();
        final int[] roomOf = new int[121];
        final Map<Integer, int[]> doorRooms = new LinkedHashMap<>();
        int entrance = -1;
        int blood = -1;
        int doorY;
        String code = "";

        /** {door, room he stands in, room he looks into} for every doorway into a room that is not the Entrance or Blood. */
        List<int[]> directedDoors() {
            List<int[]> out = new ArrayList<>();
            for (Map.Entry<Integer, int[]> e : doorRooms.entrySet()) {
                int a = e.getValue()[0];
                int b = e.getValue()[1];
                for (int[] ab : new int[][]{{a, b}, {b, a}}) {
                    if (ab[1] != entrance && ab[1] != blood && ab[0] != blood) {
                        out.add(new int[]{e.getKey(), ab[0], ab[1]});
                    }
                }
            }
            return out;
        }

        int roomAt(int tile) {
            return tile >= 0 && tile < 121 ? roomOf[tile] : -1;
        }

        BlockPos doorBlock(int idx) {
            return (BlockPos) ModUnderTest.staticCall(LAYOUT, "doorBlock", new Class<?>[]{int.class}, new Object[]{idx});
        }

        BlockPos cellCenter(int idx) {
            return (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class}, new Object[]{idx});
        }

        double[] doorCentre(int idx) {
            BlockPos b = doorBlock(idx);
            return new double[]{b.getX() + 0.5, b.getY(), b.getZ() + 0.5};
        }

        /** Unit step {x, z} from room {@code from}'s side of door {@code d} toward the other side. */
        double[] toward(int from, int d) {
            int gx = d % 11;
            int gz = d / 11;
            int side = gx % 2 == 1 ? roomOf[gz * 11 + gx - 1] : roomOf[(gz - 1) * 11 + gx];
            int sign = side == from ? 1 : -1;
            return gx % 2 == 1 ? new double[]{sign, 0} : new double[]{0, sign};
        }
    }

    // ------------------------------------------------------------------------------------------------- acting

    static void creativeFlying(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
                if (sp != null) {
                    sp.setGameMode(GameType.CREATIVE);
                    sp.getAbilities().mayfly = true;
                    sp.getAbilities().flying = true;
                    sp.onUpdateAbilities();
                }
            });
        });
        ctx.waitFor(mc -> mc.player.getAbilities().flying, 200);
    }

    /** Server teleport to {@code at}, facing the exact point {@code look}. */
    static void place(ClientGameTestContext ctx, double[] at, double[] look) {
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
                if (sp != null) {
                    sp.teleportTo(at[0], at[1], at[2]);
                }
            });
        });
        ctx.waitFor(mc -> mc.player.position().distanceToSqr(at[0], at[1], at[2]) < 0.01, 200);
        ctx.runOnClient(mc -> {
            mc.player.getAbilities().flying = true;
            mc.player.onUpdateAbilities();
            double dx = look[0] - mc.player.getX();
            double dy = look[1] - mc.player.getEyeY();
            double dz = look[2] - mc.player.getZ();
            mc.player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
            mc.player.setXRot((float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
        });
    }

    // ------------------------------------------------------------------------------------------------- plumbing

    private void row(String name, boolean pass, String detail) {
        System.out.println("[" + name + "] " + (pass ? "PASS" : "FAIL") + " - " + detail);
        Report.caseFinished(name, pass ? "PASS" : "FAIL", "n/a (singleplayer)", detail, List.of(), List.of());
        SuiteVerdict.finished(name);
        if (!pass) {
            all.add(name + ": " + detail);
        }
    }

    static Object inv(Object target, String method, Object... args) {
        try {
            for (Method m : target.getClass().getDeclaredMethods()) {
                if (m.getName().equals(method) && m.getParameterCount() == args.length) {
                    m.setAccessible(true);
                    return m.invoke(target, args);
                }
            }
            throw new AssertionError("no method " + method + "/" + args.length + " on " + target.getClass().getName());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not call " + method, e);
        }
    }

    static void leave(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            try {
                ModUnderTest.staticCall(SIM_STATE, "leave", new Class<?>[]{}, new Object[]{});
            } catch (Throwable ignored) {
                // cleanup never replaces a verdict
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

    private static void println(String line) {
        System.out.println("[" + NAME + "] " + line);
    }
}
