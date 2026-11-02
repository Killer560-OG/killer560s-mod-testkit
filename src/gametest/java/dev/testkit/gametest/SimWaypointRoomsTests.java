package dev.testkit.gametest;

import dev.testkit.compat.McCompat;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 1101-wp-*: Secret Waypoints' lever, crypt and prince waypoints against his to-do Doc of 2026-11-01 (mod branch
 * doc-secrets-1101). Every room is built on its own in the dungeon sim from his captures; every expected block comes from
 * the capture (capture coordinates, resolved through the sim's own placement with {@code SimRoomLevers.capturedWorld}),
 * never from the mod.
 * <ul>
 *   <li>{@code 1101-wp-pressure-levers}: Pressure Plates shows exactly its two real levers (by the item at capture 6,71,26
 *       and on the oak log at 22,83,16), none of the sixteen cauldron levers at y 97 - which are on the client (premise).</li>
 *   <li>{@code 1101-wp-mines-levers}: Mines' crypt over two levers: the lower floor lever (15,80,4) is shown, the wall
 *       lever on red wool above it (15,81,5) is not; the crypt's box reaches down over its stair legs to y 81.</li>
 *   <li>{@code 1101-wp-waterboard}: Water Board's seven puzzle levers are on the client and no lever waypoint is drawn,
 *       and the mod's log says it skipped the room's levers (the feature acted).</li>
 *   <li>{@code 1101-wp-prison-crypt}: Prison Cell has exactly one crypt, the 4x1 row of slabs (capture x 24..27, y 70,
 *       z 21), boxed from the polished andesite under it (y 69) to the slab tops (y 70.5) and nothing to the side.</li>
 *   <li>{@code 1101-wp-teleport-nocrypt}: Teleport Maze has no crypt: its 3x2 slab run on the floor is on the client and
 *       the scanner logs it rejected as a floor mat.</li>
 *   <li>{@code 1101-wp-flags}: Flags' brazier prince is boxed y 69 .. 70.5 (its stair legs whole, not their top half);
 *       a lid with a raised row of double slabs (capture 44..46, 88, 47..50) reaches y 89; a crypt blown open on the
 *       server leaves the waypoint list within 2 client ticks of its blocks going on the client; 5 crypts in the tab list
 *       hide the crypts (and switching Hide Crypts After 5 off brings them back); "A Prince falls" hides the prince.</li>
 *   <li>{@code 1102-diag-wp-roomchange} (mod diag-1101, owner evidence for "if i leave trap then it still keeps their
 *       waypoints loaded"): walking out of Pressure Plates writes ONE "room change" line naming the room left and 0
 *       waypoints outside the room he is in, and no "still drawing" line; walking back in writes one more; six doorway
 *       flips as fast as the server moves him write fewer lines than flips (at most one a second); the waypoints back in
 *       the room are the ones before (the log changes nothing).</li>
 *   <li>{@code 1102-diag-wp-gold} (owner evidence for Flags' gold prince, p08): a gold block set beside the raised-row
 *       crypt's legs (one out, one down) writes ONE "[CryptScan] gold block" line with its coordinates and the nearest lid's
 *       verdict over three scans, the lid's "PRINCE found (gold beside a lid)" line lists the gold's coordinates, and the
 *       prince waypoint is drawn there (the goldBeside rule, which no capture exercises, acted).</li>
 *   <li>{@code 1101-wp-item-walkaway}: an item secret's entity is removed while he stands outside the room (no waypoints
 *       there), and its waypoint is gone when he comes back while the room's other item waypoint stays.</li>
 * </ul>
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimWaypointRoomsTests implements FabricClientGameTest {

    private static final String NAME = "1101-wp";
    private static final String[] CASES = {"1101-wp-pressure-levers", "1101-wp-mines-levers", "1101-wp-waterboard",
            "1101-wp-prison-crypt", "1101-wp-teleport-nocrypt", "1101-wp-item-walkaway", "1101-wp-flags",
            "1102-diag-wp-roomchange", "1102-diag-wp-gold"};

    private static final String BUILDER = "com.killer560.hub.roomsim.SimBuilder";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String SIM_LEVERS = "com.killer560.hub.roomsim.SimRoomLevers";
    private static final String SIM_SCORE = "com.killer560.hub.roomsim.SimScore";
    private static final String CFG = "com.killer560.hub.secretwaypoints.SecretWaypointsConfig";
    private static final String FEATURE = "com.killer560.hub.secretwaypoints.SecretWaypointsFeature";
    private static final String SCORE = "com.killer560.hub.scorecalc.ScoreCalculatorFeature";
    private static final String LIVE_MAP = "com.killer560.hub.livemap.LiveMapFeature";

    private static UUID playerId;
    private static String currentRoom;
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
        if (SimMapTests.copyRoomsForOthers() < 20) {
            for (String c : selected) {
                Scenario.skipped(c, "needs room captures and room database");
            }
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);
        Object cfg = ModUnderTest.config(CFG);
        try {
            ctx.runOnClient(mc -> {
                ModUnderTest.set(cfg, "setEnabled", true);
                ModUnderTest.set(cfg, "setShowCrypts", true);
                ModUnderTest.set(cfg, "setShowPrinces", true);
                ModUnderTest.call(cfg, "setBoxSize", new Class<?>[]{enumClass(CFG + "$BoxSize")},
                        new Object[]{ModUnderTest.enumValue(CFG + "$BoxSize", "HITBOX")});
            });
            for (String c : selected) {
                long mark = LogTap.mark();
                try {
                    say("---- " + c + " ----");
                    switch (c) {
                        case "1101-wp-pressure-levers" -> pressureLevers(ctx);
                        case "1101-wp-mines-levers" -> minesLevers(ctx);
                        case "1101-wp-waterboard" -> waterBoard(ctx, mark);
                        case "1101-wp-prison-crypt" -> prisonCrypt(ctx);
                        case "1101-wp-teleport-nocrypt" -> teleportMaze(ctx, mark);
                        case "1101-wp-item-walkaway" -> itemWalkAway(ctx);
                        case "1101-wp-flags" -> flags(ctx, cfg);
                        case "1102-diag-wp-roomchange" -> roomChange(ctx);
                        case "1102-diag-wp-gold" -> goldPrince(ctx);
                        default -> throw new AssertionError("no such case " + c);
                    }
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
                    if (ctx.computeOnClient(mc -> mc.level == null && mc.getSingleplayerServer() == null)) {
                        // nothing else depends on a world; the next case builds its own
                        say("no world after " + c);
                    }
                }
            }
        } finally {
            ctx.runOnClient(mc -> {
                try {
                    ModUnderTest.set(cfg, "setShowCrypts", false);
                    ModUnderTest.set(cfg, "setShowPrinces", false);
                    ModUnderTest.set(cfg, "setHideCryptsAfterFive", true);
                    ModUnderTest.set(cfg, "setHidePrincesAfterOne", true);
                    ModUnderTest.set(cfg, "setEnabled", false);
                } catch (Throwable ignored) {
                    // cleanup must not replace the verdict
                }
            });
            teardown(ctx);
        }
        say("SUMMARY");
        for (String r : RESULTS) {
            say("  " + r);
        }
    }

    // ============================================================================================ cases

    private void pressureLevers(ClientGameTestContext ctx) {
        build(ctx, "Pressure Plates");
        BlockPos byItem = capW(ctx, 6, 71, 26);
        BlockPos onLog = capW(ctx, 22, 83, 16);
        List<BlockPos> cauldron = new ArrayList<>();
        for (int x : new int[]{17, 18, 25, 26, 38, 39, 46, 47}) {
            cauldron.add(capW(ctx, x, 97, 15));
            cauldron.add(capW(ctx, x, 97, 17));
        }
        // Premise: all eighteen levers are on the client.
        List<BlockPos> all = new ArrayList<>(cauldron);
        all.add(byItem);
        all.add(onLog);
        standNear(ctx, onLog);
        ctx.waitFor(mc -> all.stream().allMatch(p -> mc.level.getBlockState(p).is(Blocks.LEVER)), 600);
        List<BlockPos> levers = waitFor(ctx, "LEVER", l -> l.contains(onLog) && l.contains(byItem), 100);
        say("Pressure Plates lever waypoints " + levers);
        check(levers.contains(onLog) && levers.contains(byItem), "the two real levers are not both shown: " + levers);
        List<BlockPos> wrong = new ArrayList<>();
        for (BlockPos p : cauldron) {
            if (levers.contains(p)) {
                wrong.add(p);
            }
        }
        check(wrong.isEmpty(), wrong.size() + " cauldron lever(s) shown: " + wrong);
        check(levers.size() == 2, "expected exactly 2 lever waypoints, got " + levers);
    }

    private void minesLevers(ClientGameTestContext ctx) {
        build(ctx, "Mines");
        BlockPos lower = capW(ctx, 15, 80, 4);
        BlockPos upper = capW(ctx, 15, 81, 5);
        standNear(ctx, lower);
        ctx.waitFor(mc -> mc.level.getBlockState(lower).is(Blocks.LEVER) && mc.level.getBlockState(upper).is(Blocks.LEVER),
                600);
        List<BlockPos> levers = waitFor(ctx, "LEVER", l -> l.contains(lower), 100);
        say("Mines lever waypoints " + levers + " (lower " + lower.toShortString() + ", upper " + upper.toShortString() + ")");
        check(levers.contains(lower), "the lower lever under the crypt is not shown");
        check(!levers.contains(upper), "the upper lever on red wool is shown");
        // The crypt over them: lid capture x 14..16, y 82, z 3..6 on stair legs at y 81.
        double[] box = findBox(ctx, "CRYPT", lid(ctx, 14, 16, 82, 3, 6), 100);
        say("Mines crypt over the levers: box " + fmt(box));
        int y = capW(ctx, 14, 82, 3).getY();
        check(box[1] == y - 1, "the crypt box starts at y " + box[1] + ", not over its legs at " + (y - 1));
        // This lid has a raised row of double slabs: its top is the double slabs' top.
        check(box[4] == y + 1.0, "the crypt box top is " + box[4] + ", not the double slabs' top " + (y + 1));
    }

    private void waterBoard(ClientGameTestContext ctx, long mark) {
        build(ctx, "Water Board");
        BlockPos floorLever = capW(ctx, 6, 60, 16);
        List<BlockPos> puzzle = List.of(floorLever, capW(ctx, 11, 61, 11), capW(ctx, 16, 61, 11), capW(ctx, 21, 61, 11),
                capW(ctx, 11, 61, 21), capW(ctx, 16, 61, 21), capW(ctx, 21, 61, 21));
        standNear(ctx, floorLever);
        // The sim lays the puzzle out per run, so not every capture lever need stand; at least some must (premise).
        int onClient = 0;
        for (int t = 0; t < 100 && onClient < puzzle.size(); t++) {
            ctx.waitTicks(1);
            onClient = ctx.computeOnClient(mc -> (int) puzzle.stream().filter(p -> mc.level.getBlockState(p).is(Blocks.LEVER))
                    .count());
        }
        say("Water Board: " + onClient + " of the capture's 7 levers are on the client");
        check(onClient >= 1, "none of Water Board's levers is on the client (nothing to measure)");
        ctx.runOnClient(mc -> ModUnderTest.staticCall(FEATURE, "invalidateCache"));
        boolean skipped = false;
        for (int t = 0; t < 100 && !skipped; t++) {
            ctx.waitTicks(1);
            skipped = LogTap.since(mark).stream().anyMatch(l -> l.contains("lever scan skipped: Water Board"));
        }
        List<BlockPos> levers = positions(ctx, "LEVER");
        say("Water Board: lever waypoints " + levers + ", skip logged " + skipped);
        check(skipped, "the mod never logged skipping Water Board's levers (the feature did not act there)");
        check(levers.isEmpty(), "Water Board shows lever waypoints: " + levers);
    }

    private void prisonCrypt(ClientGameTestContext ctx) {
        build(ctx, "Prison Cell");
        BlockPos a = capW(ctx, 24, 70, 21);
        standNear(ctx, a);
        ctx.waitFor(mc -> mc.level.getBlockState(a).is(Blocks.SMOOTH_STONE_SLAB), 600);
        double[] want = lid(ctx, 24, 27, 70, 21, 21);
        double[] box = findBox(ctx, "CRYPT", want, 100);
        List<double[]> all = boxes(ctx, "CRYPT");
        say("Prison Cell crypts " + all.size() + ": " + fmtAll(all));
        check(all.size() == 1, "Prison Cell should have exactly 1 crypt, has " + all.size());
        check(box[1] == a.getY() - 1 && box[4] == a.getY() + 0.5,
                "the box is not the slabs and the blocks right under them: " + fmt(box));
        check(box[0] == want[0] && box[3] == want[3] && box[2] == want[2] && box[5] == want[5],
                "the box reaches to the side: " + fmt(box) + " vs the row " + fmt(want));
    }

    private void teleportMaze(ClientGameTestContext ctx, long mark) {
        build(ctx, "Teleport Maze");
        BlockPos slab = capW(ctx, 15, 69, 20);
        standNear(ctx, slab);
        ctx.waitFor(mc -> mc.level.getBlockState(slab).is(Blocks.SMOOTH_STONE_SLAB), 600);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(FEATURE, "invalidateCache"));
        boolean rejected = false;
        for (int t = 0; t < 100 && !rejected; t++) {
            ctx.waitTicks(1);
            rejected = LogTap.since(mark).stream().anyMatch(l -> l.contains("crypt rejected (floor mat"));
        }
        List<BlockPos> crypts = positions(ctx, "CRYPT");
        say("Teleport Maze: floor-mat rejection logged " + rejected + ", crypt waypoints " + crypts);
        check(rejected, "the scanner never judged the slab run on the floor (nothing measured)");
        check(crypts.isEmpty(), "Teleport Maze shows crypts: " + crypts);
    }

    private void itemWalkAway(ClientGameTestContext ctx) {
        build(ctx, "Pressure Plates");
        List<BlockPos> items = waitFor(ctx, "ITEM", l -> l.size() >= 2, 200);
        check(items.size() >= 2, "Pressure Plates should show 2 item waypoints, shows " + items);
        int[] bounds = ctx.computeOnClient(mc -> (int[]) ModUnderTest.staticCall(LIVE_MAP, "roomWorldBounds",
                new Class<?>[]{int.class}, new Object[]{(Integer) ModUnderTest.staticCall(LIVE_MAP, "currentRoomIndex")}));
        // The item nearest the room's edge, and a spot just outside the room within 24 blocks of it.
        BlockPos item = null;
        BlockPos spot = null;
        for (BlockPos p : items) {
            int[][] outs = {{bounds[0] - 6, p.getZ()}, {bounds[2] + 6, p.getZ()}, {p.getX(), bounds[1] - 6},
                    {p.getX(), bounds[3] + 6}};
            for (int[] o : outs) {
                BlockPos s = new BlockPos(o[0], p.getY(), o[1]);
                if (s.distSqr(p) <= 20 * 20 && (spot == null || s.distSqr(p) < spot.distSqr(item))) {
                    item = p;
                    spot = s;
                }
            }
        }
        check(item != null, "no item waypoint within 20 blocks of the room's edge: " + items + " bounds "
                + java.util.Arrays.toString(bounds));
        BlockPos it = item;
        BlockPos other = items.stream().filter(p -> !p.equals(it)).findFirst().orElse(null);
        BlockPos out = spot;
        say("item " + it.toShortString() + ", stand-outside spot " + out.toShortString() + ", other item " + other);
        // Stand 4 blocks off the item (no pickup), and lay a never-picked-up item entity at its waypoint.
        // 6+ blocks off: the sim's Magnetic Talisman picks items up from three times vanilla's reach, and its own secret item
        // at this spot must not be taken (and so collect the waypoint) before the walk-away (the base jar did, 2026-11-01).
        standNear(ctx, it, 6);
        int id = onServer(ctx, (level, sp) -> {
            var e = new net.minecraft.world.entity.item.ItemEntity(level, it.getX() + 0.5, it.getY() + 0.1, it.getZ() + 0.5,
                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE));
            e.setPickUpDelay(32767);
            e.setDeltaMovement(0, 0, 0);
            level.addFreshEntity(e);
            return e.getId();
        });
        ctx.waitFor(mc -> mc.level.getEntity(id) != null, 200);
        ctx.waitTicks(10);
        check(positions(ctx, "ITEM").contains(it), "the item waypoint went before the walk-away (taken early - nothing measured)");
        // Out of the room: a platform outside it, where no waypoint stands.
        onServer(ctx, (level, sp) -> {
            level.setBlockAndUpdate(out.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(out, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(out.above(), Blocks.AIR.defaultBlockState());
            return null;
        });
        tp(ctx, out.getX() + 0.5, out.getY(), out.getZ() + 0.5);
        ctx.waitTicks(10);
        int total = 0;
        for (String k : new String[]{"CHEST", "ITEM", "BAT", "WITHER", "LEVER", "CRYPT", "PRINCE"}) {
            total += positions(ctx, k).size();
        }
        say("outside the room: " + total + " waypoint(s) in the snapshot, item entity still on the client "
                + ctx.computeOnClient(mc -> mc.level.getEntity(id) != null));
        check(total == 0, "standing outside the room still has waypoints - the walk-away state was not reached");
        // The pickup: the server removes the item entity while he is out of the room.
        onServer(ctx, (level, sp) -> {
            var e = level.getEntity(id);
            if (e != null) {
                e.discard();
            }
            return null;
        });
        ctx.waitFor(mc -> mc.level.getEntity(id) == null, 200);
        ctx.waitTicks(5);
        standNear(ctx, it, 6);
        List<BlockPos> after = waitFor(ctx, "ITEM", l -> other == null || l.contains(other), 100);
        say("back in the room: item waypoints " + after);
        check(other == null || after.contains(other), "the other item waypoint is missing (snapshot not rebuilt)");
        check(!after.contains(it), "the item taken while he was outside the room kept its waypoint");
    }

    private void flags(ClientGameTestContext ctx, Object cfg) {
        build(ctx, "Flags");
        BlockPos princeLid = capW(ctx, 5, 70, 27);
        standNear(ctx, princeLid);
        ctx.waitFor(mc -> mc.level.getBlockState(princeLid).is(Blocks.SMOOTH_STONE_SLAB), 600);
        // The brazier prince: legs whole, lid's half-slab top.
        double[] pbox = findBox(ctx, "PRINCE", lid(ctx, 5, 7, 70, 27, 30), 100);
        say("Flags prince box " + fmt(pbox));
        check(pbox[1] == princeLid.getY() - 1 && pbox[4] == princeLid.getY() + 0.5,
                "the prince box is not its stair legs and lid (y " + (princeLid.getY() - 1) + " .. "
                        + (princeLid.getY() + 0.5) + "): " + fmt(pbox));
        // A lid with a raised row of double slabs.
        BlockPos raised = capW(ctx, 44, 88, 47);
        standNear(ctx, raised);
        double[] rbox = findBox(ctx, "CRYPT", lid(ctx, 44, 46, 88, 47, 50), 100);
        say("Flags raised-row crypt box " + fmt(rbox));
        check(rbox[4] == raised.getY() + 1.0 && rbox[1] == raised.getY() - 1,
                "the raised-row crypt box is not y " + (raised.getY() - 1) + " .. " + (raised.getY() + 1) + ": " + fmt(rbox));

        // Instant: blow the raised-row crypt open on the server and count client ticks until its waypoint leaves.
        List<BlockPos> lidCells = new ArrayList<>();
        for (int x = 44; x <= 46; x++) {
            for (int z = 47; z <= 50; z++) {
                lidCells.add(capW(ctx, x, 88, z));
            }
        }
        List<BlockPos> before = positions(ctx, "CRYPT");
        BlockPos anchor = before.stream().filter(lidCells::contains).findFirst().orElse(null);
        check(anchor != null, "the raised-row crypt's anchor is not among the crypt waypoints " + before);
        onServer(ctx, (level, sp) -> {
            for (BlockPos p : lidCells) {
                level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
            }
            return null;
        });
        int seenGone = -1;
        int listGone = -1;
        for (int t = 0; t < 40 && listGone < 0; t++) {
            boolean blocksGone = ctx.computeOnClient(mc -> lidCells.stream().allMatch(p -> mc.level.getBlockState(p).isAir()));
            if (blocksGone && seenGone < 0) {
                seenGone = t;
            }
            if (!positions(ctx, "CRYPT").contains(anchor)) {
                listGone = t;
            }
            ctx.waitTicks(1);
        }
        say("blown crypt: blocks gone on the client at tick " + seenGone + ", waypoint gone at tick " + listGone);
        check(seenGone >= 0, "the lid never went on the client (nothing measured)");
        check(listGone >= 0 && listGone - seenGone <= 2, "the waypoint left " + (listGone - seenGone)
                + " tick(s) after the lid went (want at most 2)");

        // Hide after 5 crypts: the sim's tab list counts them.
        List<BlockPos> shown = waitFor(ctx, "CRYPT", l -> !l.isEmpty(), 60);
        check(!shown.isEmpty(), "no crypt waypoints left to hide (nothing measured)");
        ctx.runOnClient(mc -> {
            for (int i = 0; i < 5; i++) {
                ModUnderTest.staticCall(SIM_SCORE, "cryptBlown");
            }
        });
        int tab = -1;
        for (int t = 0; t < 200 && tab < 5; t += 5) {
            ctx.waitTicks(5);
            tab = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(SCORE, "tabCrypts",
                    new Class<?>[]{Minecraft.class}, new Object[]{mc}));
        }
        check(tab >= 5, "the tab list never showed 5 crypts (" + tab + ")");
        List<BlockPos> hidden = waitFor(ctx, "CRYPT", List::isEmpty, 60);
        List<BlockPos> princeStill = positions(ctx, "PRINCE");
        say("tab Crypts " + tab + ": crypt waypoints " + hidden + ", prince waypoints " + princeStill);
        check(hidden.isEmpty(), "crypt waypoints still shown with 5 crypts counted: " + hidden);
        check(!princeStill.isEmpty(), "the prince went with the crypts");
        ctx.runOnClient(mc -> {
            ModUnderTest.set(cfg, "setHideCryptsAfterFive", false);
            ModUnderTest.staticCall(FEATURE, "invalidateCache");
        });
        List<BlockPos> back = waitFor(ctx, "CRYPT", l -> !l.isEmpty(), 60);
        say("Hide Crypts After 5 off: crypt waypoints " + back.size());
        check(!back.isEmpty(), "switching Hide Crypts After 5 off did not bring the crypts back");
        ctx.runOnClient(mc -> ModUnderTest.set(cfg, "setHideCryptsAfterFive", true));

        // Hide after 1 prince: Hypixel's line, sent by the server.
        onServer(ctx, (level, sp) -> {
            sp.sendSystemMessage(net.minecraft.network.chat.Component.literal("A Prince falls. +1 Bonus Score"));
            return null;
        });
        List<BlockPos> princesAfter = waitFor(ctx, "PRINCE", List::isEmpty, 60);
        say("after \"A Prince falls\": prince waypoints " + princesAfter);
        check(princesAfter.isEmpty(), "the prince waypoint stayed after a prince fell");
    }

    private void roomChange(ClientGameTestContext ctx) {
        build(ctx, "Pressure Plates");
        List<BlockPos> items = waitFor(ctx, "ITEM", l -> l.size() >= 2, 200);
        check(!items.isEmpty(), "Pressure Plates shows no item waypoints (nothing to measure)");
        BlockPos inside = ctx.computeOnClient(mc -> mc.player.blockPosition());
        List<String> before = allWaypoints(ctx);
        int[] bounds = ctx.computeOnClient(mc -> (int[]) ModUnderTest.staticCall(LIVE_MAP, "roomWorldBounds",
                new Class<?>[]{int.class}, new Object[]{(Integer) ModUnderTest.staticCall(LIVE_MAP, "currentRoomIndex")}));
        BlockPos out = new BlockPos(bounds[0] - 6, inside.getY(), inside.getZ());
        onServer(ctx, (level, sp) -> {
            level.setBlockAndUpdate(out.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(out, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(out.above(), Blocks.AIR.defaultBlockState());
            return null;
        });
        ctx.waitTicks(30); // past the one-a-second gap from the build's own room change
        long m1 = LogTap.mark();
        tp(ctx, out.getX() + 0.5, out.getY(), out.getZ() + 0.5);
        ctx.waitTicks(10);
        List<String> outLines = lines(m1, "[SecretWaypoints] room change");
        say("walked out: " + outLines);
        check(outLines.size() == 1, "want 1 room change line on walking out, got " + outLines.size());
        check(outLines.get(0).contains("(Pressure Plates) ->") && outLines.get(0).contains(" 0 outside"),
                "the line does not name the room left with 0 waypoints outside: " + outLines.get(0));
        check(lines(m1, "still drawing").isEmpty(), "a stray-waypoint line with no waypoint outside the room");
        check(allWaypoints(ctx).isEmpty(), "waypoints still drawn outside the room (premise of the walk-out)");

        ctx.waitTicks(30);
        long m2 = LogTap.mark();
        tp(ctx, inside.getX() + 0.5, inside.getY(), inside.getZ() + 0.5);
        ctx.waitTicks(10);
        List<String> inLines = lines(m2, "[SecretWaypoints] room change");
        say("walked in: " + inLines);
        check(inLines.size() == 1 && inLines.get(0).contains("(Pressure Plates): snapshot")
                && inLines.get(0).contains(" 0 outside"), "want 1 line into Pressure Plates with 0 outside: " + inLines);

        // Six flips as fast as the server moves him: at most one line a second.
        ctx.waitTicks(30);
        long m3 = LogTap.mark();
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < 6; i++) {
            BlockPos to = i % 2 == 0 ? out : inside;
            fastTp(ctx, to);
            ctx.waitTicks(1);
        }
        long ms = System.currentTimeMillis() - t0;
        ctx.waitTicks(10);
        int flipLines = lines(m3, "[SecretWaypoints] room change").size();
        say("6 flips in " + ms + " ms: " + flipLines + " line(s)");
        check(flipLines >= 1 && flipLines < 6 && flipLines <= ms / 1000 + 1,
                flipLines + " line(s) for 6 flips in " + ms + " ms (want 1 .. one a second)");

        List<String> after = waitForAll(ctx, before, 100);
        check(after.equals(before), "the room's waypoints changed: before " + before + ", after " + after);
    }

    private void goldPrince(ClientGameTestContext ctx) {
        build(ctx, "Flags");
        BlockPos raised = capW(ctx, 44, 88, 47);
        standNear(ctx, raised);
        double[] rbox = findBox(ctx, "CRYPT", lid(ctx, 44, 46, 88, 47, 50), 100);
        say("raised-row crypt box " + fmt(rbox) + " (a crypt before the gold)");
        Set<BlockPos> lidCells = new HashSet<>();
        for (int x = 44; x <= 46; x++) {
            for (int z = 47; z <= 50; z++) {
                lidCells.add(capW(ctx, x, 88, z));
            }
        }
        int y = raised.getY();
        BlockPos gold = onServer(ctx, (level, sp) -> {
            for (BlockPos l : lidCells) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos q = new BlockPos(l.getX() + dx, y - 1, l.getZ() + dz);
                        if (lidCells.contains(new BlockPos(q.getX(), y, q.getZ()))) {
                            continue;
                        }
                        if (level.getBlockState(q).isAir() && !level.getBlockState(q.below()).isAir()) {
                            return q;
                        }
                    }
                }
            }
            return null;
        });
        check(gold != null, "no free spot one out and one down from the raised-row lid");
        long m = LogTap.mark();
        onServer(ctx, (level, sp) -> {
            level.setBlockAndUpdate(gold, Blocks.GOLD_BLOCK.defaultBlockState());
            return null;
        });
        ctx.waitFor(mc -> mc.level.getBlockState(gold).is(Blocks.GOLD_BLOCK), 200);
        String at = "at " + gold.toShortString() + ":";
        for (int t = 0; t < 100 && lines(m, "[CryptScan] gold block", at).isEmpty(); t++) {
            if (t % 20 == 0) {
                ctx.runOnClient(mc -> ModUnderTest.staticCall(FEATURE, "invalidateCache"));
            }
            ctx.waitTicks(1);
        }
        for (int i = 0; i < 3; i++) { // three more scans
            ctx.runOnClient(mc -> ModUnderTest.staticCall(FEATURE, "invalidateCache"));
            ctx.waitTicks(25);
        }
        List<String> goldLines = lines(m, "[CryptScan] gold block", at);
        List<String> princeLines = lines(m, "[CryptScan] PRINCE found (gold beside a lid) at", "at [" + gold.toShortString() + "]");
        List<BlockPos> princes = positions(ctx, "PRINCE");
        say("gold at " + gold.toShortString() + ": " + goldLines + " | " + princeLines + " | prince waypoints " + princes);
        try {
            check(goldLines.size() == 1, "want 1 gold block line over 4 scans, got " + goldLines.size());
            check(goldLines.get(0).contains("nearest slab run") && goldLines.get(0).contains("PRINCE"),
                    "the gold line does not name the lid's verdict: " + goldLines.get(0));
            check(princeLines.size() == 1, "the lid's PRINCE line does not list the gold's coordinates: " + princeLines);
            check(princes.stream().anyMatch(lidCells::contains), "no prince waypoint on the lid: " + princes);
        } finally {
            onServer(ctx, (level, sp) -> {
                level.setBlockAndUpdate(gold, Blocks.AIR.defaultBlockState());
                return null;
            });
        }
    }

    // ============================================================================================ helpers

    private static List<String> lines(long mark, String... parts) {
        List<String> out = new ArrayList<>();
        for (String l : LogTap.since(mark)) {
            boolean all = true;
            for (String p : parts) {
                all &= l.contains(p);
            }
            if (all) {
                out.add(l);
            }
        }
        return out;
    }

    /** Every waypoint in the snapshot as "KIND@x,y,z", sorted. */
    private static List<String> allWaypoints(ClientGameTestContext ctx) {
        List<String> out = new ArrayList<>();
        for (String k : new String[]{"CHEST", "ITEM", "BAT", "WITHER", "LEVER", "CRYPT", "PRINCE"}) {
            for (BlockPos p : positions(ctx, k)) {
                out.add(k + "@" + p.toShortString());
            }
        }
        java.util.Collections.sort(out);
        return out;
    }

    private static List<String> waitForAll(ClientGameTestContext ctx, List<String> want, int ticks) {
        List<String> l = allWaypoints(ctx);
        for (int t = 0; t < ticks && !l.equals(want); t++) {
            ctx.waitTicks(1);
            l = allWaypoints(ctx);
        }
        return l;
    }

    /** A server teleport, waited for only until the client stands there. */
    private static void fastTp(ClientGameTestContext ctx, BlockPos to) {
        onServer(ctx, (level, sp) -> {
            sp.teleportTo(level, to.getX() + 0.5, to.getY(), to.getZ() + 0.5, Set.<net.minecraft.world.entity.Relative>of(),
                    sp.getYRot(), sp.getXRot(), false);
            return null;
        });
        ctx.waitFor(mc -> mc.player != null && Math.abs(mc.player.getX() - (to.getX() + 0.5)) < 0.3
                && Math.abs(mc.player.getZ() - (to.getZ() + 0.5)) < 0.3, 100);
    }

    /** World box {minX, minY, minZ, maxX, maxY, maxZ} of a capture lid's cells at its own layer (y not used). */
    private static double[] lid(ClientGameTestContext ctx, int x0, int x1, int y, int z0, int z1) {
        BlockPos a = capW(ctx, x0, y, z0);
        BlockPos b = capW(ctx, x1, y, z1);
        return new double[]{Math.min(a.getX(), b.getX()), y, Math.min(a.getZ(), b.getZ()),
                Math.max(a.getX(), b.getX()) + 1, y, Math.max(a.getZ(), b.getZ()) + 1};
    }

    /** The waypoint box of {@code kind} whose x/z footprint is {@code want}'s, waited for. */
    private static double[] findBox(ClientGameTestContext ctx, String kind, double[] want, int ticks) {
        for (int t = 0; t <= ticks; t++) {
            for (double[] b : boxes(ctx, kind)) {
                if (b[0] == want[0] && b[2] == want[2] && b[3] == want[3] && b[5] == want[5]) {
                    return b;
                }
            }
            ctx.waitTicks(1);
        }
        throw new AssertionError("no " + kind + " waypoint with the footprint " + fmt(want) + "; have "
                + fmtAll(boxes(ctx, kind)));
    }

    private static List<BlockPos> waitFor(ClientGameTestContext ctx, String kind,
                                          java.util.function.Predicate<List<BlockPos>> ok, int ticks) {
        List<BlockPos> l = positions(ctx, kind);
        for (int t = 0; t < ticks && !ok.test(l); t++) {
            ctx.waitTicks(1);
            l = positions(ctx, kind);
        }
        return l;
    }

    @SuppressWarnings("unchecked")
    private static List<BlockPos> positions(ClientGameTestContext ctx, String kind) {
        return ctx.computeOnClient(mc -> new ArrayList<>((List<BlockPos>) ModUnderTest.staticCall(FEATURE,
                "cachedPositions", new Class<?>[]{String.class}, new Object[]{kind})));
    }

    @SuppressWarnings("unchecked")
    private static List<double[]> boxes(ClientGameTestContext ctx, String kind) {
        return ctx.computeOnClient(mc -> new ArrayList<>((List<double[]>) ModUnderTest.staticCall(FEATURE,
                "cachedBoxes", new Class<?>[]{String.class}, new Object[]{kind})));
    }

    private static String fmt(double[] b) {
        return String.format(Locale.ROOT, "[%.1f,%.1f,%.1f .. %.1f,%.1f,%.1f]", b[0], b[1], b[2], b[3], b[4], b[5]);
    }

    private static String fmtAll(List<double[]> l) {
        List<String> s = new ArrayList<>();
        for (double[] b : l) {
            s.add(fmt(b));
        }
        return s.toString();
    }

    private static void build(ClientGameTestContext ctx, String room) {
        currentRoom = room;
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> ModUnderTest.staticCall(BUILDER, "buildSingleRoom",
                new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, room})));
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
        say("built " + room);
    }

    /** Teleports him onto the nearest standable spot 3..6 blocks from {@code p}, searching the server's blocks. */
    private static void standNear(ClientGameTestContext ctx, BlockPos p) {
        standNear(ctx, p, 3);
    }

    /** As {@link #standNear(ClientGameTestContext, BlockPos)}, at least {@code minD} blocks off. */
    private static void standNear(ClientGameTestContext ctx, BlockPos p, double minD) {
        BlockPos feet = onServer(ctx, (level, sp) -> {
            BlockPos best = null;
            double bestD = Double.MAX_VALUE;
            for (int dx = -6; dx <= 6; dx++) {
                for (int dz = -6; dz <= 6; dz++) {
                    for (int dy = -6; dy <= 4; dy++) {
                        BlockPos f = p.offset(dx, dy, dz);
                        double d = Math.sqrt(f.distSqr(p));
                        if (d < minD || d > minD + 3.5) {
                            continue;
                        }
                        if (level.getBlockState(f).getCollisionShape(level, f).isEmpty()
                                && level.getBlockState(f.above()).getCollisionShape(level, f.above()).isEmpty()
                                && level.getBlockState(f).getFluidState().isEmpty()
                                && level.getBlockState(f.below()).isCollisionShapeFullBlock(level, f.below())
                                && d < bestD) {
                            best = f;
                            bestD = d;
                        }
                    }
                }
            }
            return best;
        });
        check(feet != null, "no standing spot near " + p.toShortString() + " in " + currentRoom);
        tp(ctx, feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
        ctx.waitFor(mc -> mc.player != null && Math.abs(mc.player.getX() - (feet.getX() + 0.5)) < 0.3
                && Math.abs(mc.player.getZ() - (feet.getZ() + 0.5)) < 0.3, 400);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(FEATURE, "invalidateCache"));
        ctx.waitTicks(5);
    }

    private static BlockPos capW(ClientGameTestContext ctx, int x, int y, int z) {
        BlockPos p = ctx.computeOnClient(mc -> (BlockPos) ModUnderTest.staticCall(SIM_LEVERS, "capturedWorld",
                new Class<?>[]{String.class, int.class, int.class, int.class}, new Object[]{currentRoom, x, y, z}));
        check(p != null, currentRoom + " is not placed");
        return p;
    }

    private static void tp(ClientGameTestContext ctx, double x, double y, double z) {
        onServer(ctx, (level, sp) -> {
            sp.teleportTo(level, x, y, z, Set.<net.minecraft.world.entity.Relative>of(), sp.getYRot(), sp.getXRot(), false);
            return null;
        });
        ctx.waitTicks(10);
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

    private static Class<?> enumClass(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new AssertionError("no class " + name, e);
        }
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
