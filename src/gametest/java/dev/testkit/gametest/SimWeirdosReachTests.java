package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.harness.PacketWatch;
import dev.testkit.harness.Report;
import dev.testkit.harness.SuiteVerdict;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@code 93-solve-weirdos-reach} (mod branch weirdos-reach-1015). killer560, 2026-10-15, sim: "on sim it cannot reach the
 * right person for auto three weirdos" - his log has Auto Three Weirdos talk to two weirdos from where the Interactive Map
 * put him (relative 15, 68, 22) and then log "no un-talked CLICK stand within entity reach (2 of 3 talked to)" every tick
 * until he walked over himself: 28.9 s for a puzzle the Autopilot estimates at 7.9.
 *
 * <p>The room is built alone at each of its four quarter turns ({@code SimBuilder.setSingleRoomRotationForTest}; a jar
 * without it plays rotation 0 only and says so), and at each the auto is started from four spots: where the build stands
 * him by the doorway (about 21 blocks from the weirdos), the Interactive Map's spot for the room (his case), a spot with
 * exactly ONE weirdo in entity reach, and a spot with NONE in reach. Talk To NPCs,
 * Etherwarp Reposition and pathing are on (his settings), an AOTV is in hand, nothing else moves him. Each case must:
 * have the sim mark Three Weirdos solved (the correct chest opened, the room never failed) within {@link #BUDGET_TICKS} of
 * the auto being switched on (the Autopilot's estimate for the room is 6 s standing in it); have talked to all three (3
 * interact packets, every one within 3.0 blocks eye to the stand's box as the client sent it - the anticheat's limit);
 * have talks made without moving in between go at most 2 ticks apart (main: a 200 ms gap); and log its "waiting" reason
 * once per change, not every tick (main: two alternating lines every tick). Not vacuous: a start spot that already has
 * all three in reach is rejected, and at least one case per run must have moved (a warp or walk line from the auto).
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimWeirdosReachTests implements FabricClientGameTest {

    private static final String NAME = "93-solve-weirdos-reach";
    private static final String BUILDER = "com.killer560.hub.roomsim.SimBuilder";
    private static final String SIM_ITEMS = "com.killer560.hub.roomsim.SimItems";
    private static final String LIVE_MAP = "com.killer560.hub.livemap.LiveMapFeature";
    private static final String LIVE_MAP_CONFIG = "com.killer560.hub.livemap.LiveMapConfig";
    private static final String AUTO_CONFIG = "com.killer560.hub.autopuzzles.AutoPuzzlesConfig";
    private static final String CHEAT_CONFIG = "com.killer560.hub.cheatutils.CheatUtilsConfig";
    private static final String WEIRDOS = "com.killer560.hub.roomsim.puzzles.SimQuizPuzzle";
    private static final String ROOM_STATE = "com.killer560.hub.roomsim.SimRoomState";
    private static final String ROOM = "Three Weirdos";
    private static final String[] ALL_AUTOS = {"setAutoQuizEnabled", "setAutoWeirdosEnabled", "setAutoBlazeEnabled",
            "setAutoBeamsEnabled", "setAutoIcePathEnabled", "setAutoBoulderEnabled", "setAutoWaterEnabled",
            "setAutoTicTacToeEnabled", "setAutoTeleportMazeEnabled", "setAutoIceFillEnabled"};
    /** The Autopilot's own estimate for the room, standing in it (Autopilot.puzzleSeconds: 6 s). */
    private static final int BUDGET_TICKS = 6 * 20;
    /** How long a case is watched: past the budget, so a slow solve is measured rather than cut off. */
    private static final int WATCH_TICKS = 20 * 20;
    private static final double ENTITY_REACH = 3.0;

    private enum Start { DOORWAY, IMAP, ONE_IN_REACH, NONE_IN_REACH }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip(NAME)) {
            return;
        }
        SuiteVerdict.expect(NAME);
        Report.caseStarted(NAME);
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(20);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (ctx.computeOnClient(mc -> mc.level != null)) {
            throw new AssertionError("expected the title screen with no world");
        }
        if (!SimPuzzleSolveTests.seedRooms(ctx)) {
            Scenario.skipped(NAME, "needs room captures and room database");
            SuiteVerdict.finished(NAME);
            return;
        }
        boolean rotations = ctx.computeOnClient(mc -> hasRotationHook());
        int[] turns = rotations ? new int[]{0, 90, 180, 270} : new int[]{0};
        say(rotations ? "rooms built at 0, 90, 180 and 270 (SimBuilder.setSingleRoomRotationForTest)"
                : "this jar has no SimBuilder.setSingleRoomRotationForTest - rotation 0 only");
        long mark0 = LogTap.mark();
        List<String> results = new ArrayList<>();
        List<String> bad = new ArrayList<>();
        int moved = 0;
        int cases = 0;
        try {
            for (int turn : turns) {
                for (Start start : Start.values()) {
                    String c = "rot " + turn + " " + start.name().toLowerCase(Locale.ROOT);
                    String r;
                    try {
                        r = playCase(ctx, c, turn, start, rotations);
                    } catch (AssertionError | RuntimeException e) {
                        r = "FAIL - " + String.valueOf(e.getMessage()).lines().findFirst().orElse("");
                    } finally {
                        allAutosOff(ctx);
                    }
                    if (r.startsWith("SKIP")) {
                        results.add(c + ": " + r);
                        say(c + ": " + r);
                        continue;
                    }
                    cases++;
                    if (r.contains("[moved]")) {
                        moved++;
                    }
                    results.add(c + ": " + r);
                    say(c + ": " + r);
                    if (!r.startsWith("PASS")) {
                        bad.add(c + ": " + r);
                    }
                }
            }
        } finally {
            if (rotations) {
                ctx.runOnClient(mc -> setRotation(0));
            }
            // Once, at the end: leaving the world after every case deadlocked the client against the integrated server
            // parked in the gametest's postRunTasks (jstack 2026-10-15, IntegratedServer.halt under Minecraft.disconnect).
            SimPuzzleSolveTests.teardown(ctx);
        }
        StringBuilder sb = new StringBuilder("[" + NAME + "] SUMMARY - " + cases + " case(s), " + moved + " moved:");
        for (String r : results) {
            sb.append("\n[" + NAME + "]   ").append(r);
        }
        System.out.println(sb);
        if (cases == 0) {
            bad.add("no case ran");
        } else if (moved == 0) {
            bad.add("no case had the auto move itself - nothing here tested the reposition");
        }
        String verdict = bad.isEmpty() ? "PASS - " + cases + " case(s), " + moved + " with a move of the auto's own"
                : "FAIL - " + bad.size() + " problem(s): " + String.join(" | ", bad);
        say(verdict);
        if (!bad.isEmpty()) {
            Report.caseFinished(NAME, "FAIL", "n/a (singleplayer)", verdict, List.of(), LogTap.since(mark0));
            SuiteVerdict.finished(NAME);
            throw new AssertionError(verdict);
        }
        Report.caseFinished(NAME, "PASS", "n/a (singleplayer)", verdict, List.of(), List.of());
        SuiteVerdict.finished(NAME);
    }

    /** One room build, one start spot, one solve. @return the case's verdict line */
    private String playCase(ClientGameTestContext ctx, String c, int turn, Start start, boolean rotations) {
        ctx.runOnClient(mc -> configure(false));
        if (rotations) {
            ctx.runOnClient(mc -> setRotation(turn));
        }
        long before = Scenario.simBuildCount(ctx);
        if (ctx.computeOnClient(mc -> mc.level != null)) {
            // A rebuild in the open world (his menu's room picker does the same): the last case's weirdos and chests go
            // first - the paste wipes blocks, not the stands.
            ctx.runOnClient(mc -> ModUnderTest.staticCall(WEIRDOS, "reset", new Class<?>[]{}, new Object[]{}));
            ctx.waitTicks(5);
        }
        ctx.runOnClient(mc -> mc.execute(() -> ModUnderTest.staticCall(BUILDER, "buildSingleRoom",
                new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, ROOM})));
        ctx.waitFor(mc -> mc.level != null && mc.player != null, 2400);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitFor(mc -> McCompat.screen(mc) == null, 1200);
        ctx.waitTicks(40);
        long mark = LogTap.mark();
        // The published rotation and the weirdos the sim placed, from the build's own log lines.
        String published = ctx.computeOnClient(mc -> {
            int[] cr = (int[]) ModUnderTest.staticCall(LIVE_MAP, "currentRoomClayAndRotation", new Class<?>[]{},
                    new Object[]{});
            return cr == null ? "none" : cr[0] + "," + cr[1] + " rotation " + cr[2];
        });
        giveItems(ctx);
        // The client must have the room under him before anything is judged (93-solve's premise).
        ctx.waitFor(mc -> mc.player.onGround() && !mc.level.getBlockState(mc.player.blockPosition().below()).isAir(), 1800);
        ctx.waitTicks(10);
        if (!ROOM.equals(ctx.computeOnClient(SimWeirdosReachTests::liveRoom))) {
            throw new AssertionError("the live map does not say " + ROOM + " (" + ctx.computeOnClient(SimWeirdosReachTests::liveRoom) + ")");
        }
        int stands = ctx.computeOnClient(mc -> clickStands(mc).size());
        if (stands != 3) {
            throw new AssertionError(stands + " CLICK stand(s) in the room, not 3 - the sim did not place the weirdos");
        }
        if (start != Start.DOORWAY) {
            // DOORWAY: where the build stands him, one block in from the room's doorway - as walking in does.
            double[] spot = ctx.computeOnClient(mc -> startSpot(mc, start));
            if (spot == null) {
                return "SKIP - no standable start spot of this kind in the room";
            }
            teleport(ctx, spot);
        }
        ctx.waitTicks(10);
        int inReach = ctx.computeOnClient(mc -> inReach(mc, mc.player.getEyePosition()));
        String reaches = ctx.computeOnClient(SimWeirdosReachTests::reachLine);
        say(c + ": clay " + published + ", start " + where(ctx) + "; " + reaches);
        if (!ROOM.equals(ctx.computeOnClient(SimWeirdosReachTests::liveRoom))) {
            throw new AssertionError("the start spot is not in " + ROOM + " on the live map");
        }
        if (inReach >= 3) {
            return "SKIP - all three weirdos are in reach from this start (" + reaches + "), nothing to move for";
        }
        if (ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(WEIRDOS, "isWeirdosComplete"))) {
            throw new AssertionError("solved before the auto was switched on");
        }

        // ---- switch it on and watch ------------------------------------------------------------------
        ctx.runOnClient(mc -> {
            PacketWatch.reset();
            PacketWatch.start();
        });
        long watchMark = LogTap.mark();
        ctx.runOnClient(mc -> configure(true));
        int solvedAt = -1;
        int failedAt = -1;
        int lastInteracts = 0;
        List<int[]> talkTicks = new ArrayList<>(); // {tick, moved-since-last-talk ? 1 : 0}
        Vec3 lastTalkPos = null;
        double maxReach = 0;
        for (int t = 1; t <= WATCH_TICKS; t++) {
            ctx.waitTicks(1);
            int n = ctx.computeOnClient(mc -> PacketWatch.entityInteracts());
            Vec3 pos = ctx.computeOnClient(mc -> mc.player.position());
            if (n > lastInteracts) {
                boolean movedSince = lastTalkPos != null && pos.distanceToSqr(lastTalkPos) > 0.01;
                for (int k = lastInteracts; k < n; k++) {
                    talkTicks.add(new int[]{t, movedSince ? 1 : 0});
                }
                lastInteracts = n;
                lastTalkPos = pos;
            }
            if (failedAt < 0 && ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(ROOM_STATE, "isFailed",
                    new Class<?>[]{String.class}, new Object[]{ROOM}))) {
                failedAt = t;
            }
            if (solvedAt < 0 && ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(WEIRDOS, "isWeirdosComplete"))) {
                solvedAt = t;
                ctx.waitTicks(10);
                break;
            }
        }
        maxReach = ctx.computeOnClient(mc -> PacketWatch.maxEntityReach());
        ctx.runOnClient(mc -> PacketWatch.stop());
        ctx.runOnClient(mc -> configure(false));
        List<String> log = LogTap.since(watchMark);
        int waits = 0;
        boolean movedLine = false;
        String lastWait = null;
        int repeats = 0;
        List<String> autoLines = new ArrayList<>();
        for (String l : log) {
            if (l.contains("Weirdos:")) {
                autoLines.add(l.substring(l.indexOf("Weirdos:")));
            }
            if (l.contains("Weirdos: waiting - ")) {
                waits++;
                String why = l.substring(l.indexOf("Weirdos: waiting - "));
                if (why.equals(lastWait)) {
                    repeats++;
                }
                lastWait = why;
            }
            if (l.contains("Weirdos:") && (l.contains("etherwarping onto") || l.contains("walking to") || l.contains("Interactive Map to"))) {
                movedLine = true;
            }
        }
        // ---- verdict --------------------------------------------------------------------------------------
        List<String> problems = new ArrayList<>();
        if (failedAt > 0) {
            problems.add(String.format(Locale.ROOT, "the sim marked the room FAILED at %.2fs", failedAt / 20.0));
        }
        if (solvedAt < 0) {
            problems.add("not solved in " + WATCH_TICKS / 20 + " s");
        } else if (solvedAt > BUDGET_TICKS) {
            problems.add(String.format(Locale.ROOT, "solved at %.2fs, past the %ds estimate", solvedAt / 20.0,
                    BUDGET_TICKS / 20));
        }
        if (talkTicks.size() < 3) {
            problems.add("only " + talkTicks.size() + " interact(s) sent - not all three weirdos were talked to");
        }
        if (maxReach > ENTITY_REACH + 1e-6) {
            problems.add(String.format(Locale.ROOT, "an interact went %.2f blocks eye to box, past %.1f", maxReach,
                    ENTITY_REACH));
        }
        StringBuilder gaps = new StringBuilder();
        for (int i = 1; i < talkTicks.size(); i++) {
            int gap = talkTicks.get(i)[0] - talkTicks.get(i - 1)[0];
            boolean still = talkTicks.get(i)[1] == 0;
            gaps.append(gaps.isEmpty() ? "" : ", ").append(gap).append(still ? "" : "m");
            if (still && gap > 2) {
                problems.add("two talks " + gap + " ticks apart without a move between them");
            }
        }
        // Once per change: a reason may come back after another, but never the same one twice in a row, and never one a
        // tick (main logged two alternating lines every tick: hundreds in a case).
        if (repeats > 0 || waits > 12) {
            problems.add(waits + " 'waiting' line(s), " + repeats + " repeated - not once per change");
        }
        String line = String.format(Locale.ROOT, "solved %s, talks at ticks %s (gaps %s; m = moved between), max reach "
                        + "%.2f, %d waiting line(s)%s",
                solvedAt < 0 ? "never" : String.format(Locale.ROOT, "%.2fs", solvedAt / 20.0),
                talkTicks.stream().map(a -> Integer.toString(a[0])).toList(), gaps, maxReach, waits,
                movedLine ? " [moved]" : "");
        if (!problems.isEmpty()) {
            for (String l : autoLines.subList(Math.max(0, autoLines.size() - 25), autoLines.size())) {
                say("  | " + l);
            }
            say("  end " + where(ctx));
            return "FAIL - " + String.join("; ", problems) + " - " + line;
        }
        return "PASS - " + line;
    }

    // ------------------------------------------------------------------ the room

    private static boolean hasRotationHook() {
        try {
            Class.forName(BUILDER).getMethod("setSingleRoomRotationForTest", int.class);
            return true;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private static void setRotation(int turn) {
        ModUnderTest.staticCall(BUILDER, "setSingleRoomRotationForTest", new Class<?>[]{int.class}, new Object[]{turn});
    }

    /** The room's three "CLICK" stands. */
    private static List<Entity> clickStands(Minecraft mc) {
        List<Entity> out = new ArrayList<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof ArmorStand && e.getName().getString().contains("CLICK") && e.distanceTo(mc.player) < 48) {
                out.add(e);
            }
        }
        return out;
    }

    private static double boxDist(Vec3 eye, AABB box) {
        double dx = Math.max(0, Math.max(box.minX - eye.x, eye.x - box.maxX));
        double dy = Math.max(0, Math.max(box.minY - eye.y, eye.y - box.maxY));
        double dz = Math.max(0, Math.max(box.minZ - eye.z, eye.z - box.maxZ));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static int inReach(Minecraft mc, Vec3 eye) {
        int n = 0;
        for (Entity e : clickStands(mc)) {
            if (boxDist(eye, e.getBoundingBox()) <= ENTITY_REACH) {
                n++;
            }
        }
        return n;
    }

    private static String reachLine(Minecraft mc) {
        StringBuilder sb = new StringBuilder("eye to each CLICK stand's box:");
        for (Entity e : clickStands(mc)) {
            sb.append(String.format(Locale.ROOT, " %.2f", boxDist(mc.player.getEyePosition(), e.getBoundingBox())));
        }
        return sb.toString();
    }

    /**
     * Where the case starts. IMAP: the Interactive Map's spot for the room (relative 15, 68, 22 - AutoClearUtils'), the
     * floor nearest that column's height. ONE_IN_REACH: the standable block in the room with exactly one weirdo within 3.0
     * whose other two are farthest. NONE_IN_REACH: the standable block in the room whose nearest weirdo is closest to 6
     * blocks off. Standable: solid under, two blocks of air, inside the live map's room bounds.
     */
    private static double[] startSpot(Minecraft mc, Start start) {
        List<Entity> stands = clickStands(mc);
        int idx = (Integer) ModUnderTest.staticCall(LIVE_MAP, "currentRoomIndex", new Class<?>[]{}, new Object[]{});
        int[] bounds = (int[]) ModUnderTest.staticCall(LIVE_MAP, "roomWorldBounds", new Class<?>[]{int.class},
                new Object[]{idx});
        int standY = (int) Math.floor(stands.get(0).getY());
        if (start == Start.IMAP) {
            BlockPos col = SimPuzzleSolveTests.rel(mc, 15, 68, 22);
            for (int dy = 0; dy <= 6; dy++) {
                for (int s : new int[]{1, -1}) {
                    BlockPos b = col.offset(0, s * dy, 0);
                    if (standable(mc, b)) {
                        return new double[]{b.getX() + 0.5, b.getY() + 1, b.getZ() + 0.5};
                    }
                }
            }
            return null;
        }
        double[] best = null;
        double bestScore = Double.MAX_VALUE;
        for (int x = bounds[0] + 1; x < bounds[2]; x++) {
            for (int z = bounds[1] + 1; z < bounds[3]; z++) {
                for (int y = standY - 4; y <= standY + 2; y++) {
                    BlockPos b = new BlockPos(x, y, z);
                    if (!standable(mc, b)) {
                        continue;
                    }
                    Vec3 eye = new Vec3(x + 0.5, y + 1 + 1.62, z + 0.5);
                    AABB body = new AABB(x + 0.2, y + 1, z + 0.2, x + 0.8, y + 2.8, z + 0.8);
                    int n = 0;
                    double near = Double.MAX_VALUE;
                    double sumOthers = 0;
                    boolean inside = false;
                    for (Entity e : stands) {
                        double d = boxDist(eye, e.getBoundingBox());
                        inside |= e.getBoundingBox().intersects(body);
                        near = Math.min(near, d);
                        if (d <= ENTITY_REACH) {
                            n++;
                        } else {
                            sumOthers += d;
                        }
                    }
                    if (inside) {
                        continue;
                    }
                    double score;
                    if (start == Start.ONE_IN_REACH) {
                        if (n != 1) {
                            continue;
                        }
                        score = -sumOthers;
                    } else {
                        if (n != 0) {
                            continue;
                        }
                        score = Math.abs(near - 6.0);
                    }
                    if (score < bestScore) {
                        bestScore = score;
                        best = new double[]{x + 0.5, y + 1, z + 0.5};
                    }
                }
            }
        }
        return best;
    }

    private static boolean standable(Minecraft mc, BlockPos b) {
        var s = mc.level.getBlockState(b);
        return !s.isAir() && s.isCollisionShapeFullBlock(mc.level, b) && mc.level.getBlockState(b.above()).isAir()
                && mc.level.getBlockState(b.above(2)).isAir();
    }

    // ------------------------------------------------------------------ settings, items, moves

    /** His settings; with {@code on} false every auto is off and the solver stays on. */
    private static void configure(boolean on) {
        Object auto = ModUnderTest.config(AUTO_CONFIG);
        for (String setter : ALL_AUTOS) {
            ModUnderTest.set(auto, setter, false);
        }
        ModUnderTest.set(auto, "setAutoPuzzlesMasterEnabled", on);
        ModUnderTest.set(auto, "setEtherwarpReposition", true);
        ModUnderTest.set(auto, "setAutoPuzzlePathingEnabled", true);
        ModUnderTest.set(auto, "setWeirdosTalkToNpcs", true);
        Object solver = ModUnderTest.config("com.killer560.hub.puzzlesolvers.WeirdosSolverConfig");
        ModUnderTest.set(solver, "setEnabled", true);
        Object map = ModUnderTest.config(LIVE_MAP_CONFIG);
        ModUnderTest.set(map, "setEnabled", true);
        ModUnderTest.set(map, "setInteractiveMapEnabled", true);
        Object cheat = ModUnderTest.config(CHEAT_CONFIG);
        ModUnderTest.set(cheat, "setSecretAuraEnabled", false);
        if (on) {
            ModUnderTest.set(auto, "setAutoWeirdosEnabled", true);
        }
    }

    private static void allAutosOff(ClientGameTestContext ctx) {
        try {
            ctx.runOnClient(mc -> {
                Object auto = ModUnderTest.config(AUTO_CONFIG);
                for (String setter : ALL_AUTOS) {
                    ModUnderTest.set(auto, setter, false);
                }
                ModUnderTest.set(auto, "setAutoPuzzlesMasterEnabled", false);
            });
        } catch (Throwable ignored) {
            // cleanup must not become the failure
        }
    }

    private static void giveItems(ClientGameTestContext ctx) {
        AtomicReference<Boolean> given = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
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
                inv.setItem(0, (ItemStack) ModUnderTest.staticCall(SIM_ITEMS, "build", new Class<?>[]{String.class},
                        new Object[]{"ASPECT_OF_THE_VOID"}));
                given.set(true);
            });
        });
        ctx.waitFor(mc -> given.get() != null, 200);
        ctx.waitTicks(10);
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
        ctx.waitTicks(5);
    }

    private static void teleport(ClientGameTestContext ctx, double[] spot) {
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(uuid);
                if (sp != null) {
                    sp.teleportTo(spot[0], spot[1], spot[2]);
                }
            });
        });
        ctx.waitTicks(20);
    }

    private static String liveRoom(Minecraft mc) {
        try {
            Object entry = ModUnderTest.staticCall(LIVE_MAP, "currentRoomEntry", new Class<?>[]{}, new Object[]{});
            return entry == null ? null : String.valueOf(entry.getClass().getField("name").get(entry));
        } catch (ReflectiveOperationException e) {
            return "(unreadable: " + e + ")";
        }
    }

    private static String where(ClientGameTestContext ctx) {
        return ctx.computeOnClient(SimPuzzleSolveTests::where);
    }

    private static void say(String line) {
        System.out.println("[" + NAME + "] " + line);
    }
}
