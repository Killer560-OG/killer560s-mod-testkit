package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.harness.PacketTrace;
import dev.testkit.harness.SuiteVerdict;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 96-ar-*: Auto Routes in the dungeon sim, judged on what HAPPENED - where he ended up, the packets that went out on
 * which tick, the sneak each input packet carried, the blocks that broke, and the routes file on disk.
 *
 * <p>One room (the first 1x1 room of his library with secrets that is not a puzzle, trap, blood, entrance or fairy
 * room) is loaded through a real map code at rotation 0, and the scenario carves its own arena into it at fixed
 * ROOM-RELATIVE coordinates: a cleared 23 x 23 floor with walls, so every warp and walk has one known answer. The
 * same arena goes into the same room rebuilt at rotation 90 for the rotation case, through the mod's own
 * room-relative transform. His settings as in his instances: obvious mode, start-node-only, node height 1.0,
 * interact delay 2.
 *
 * <p>Cases (each selectable: -Pscenario=96-ar-play etc.; -Pscenario=96-ar runs all):
 * add, play, interact (empty-hand use nodes), await (what counts as YOUR secret), mimic (Kill Mimic), crypt (crypt
 * nodes and the two await counters), breaker, breakerwait (a node short of charges waits, then one burst), pingpong, charges (a secret gives the sim breaker 2 charges), museum (his
 * exact Museum route: stacked booms into a crypt node), edit, mapopen, mapopen-aim (aim and land with the map open,
 * on the node and after Go + Secret, legit and obvious), 398-offnode / 398-regrow / 398-startawait (his 2026-10-07
 * Museum log: off-centre warps, a rebuilt room's stale regrow, the start await after a map warp), path, screen, rotate.
 * GrimAC does not apply
 * here: the sim is an integrated server.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimAutoRoutesTests implements FabricClientGameTest {

    private static final String NAME = "96-ar";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String BUILDER = "com.killer560.hub.roomsim.SimBuilder";
    private static final String MAP_CODE = "com.killer560.hub.roomsim.MapCode";
    private static final String SIM_ITEMS = "com.killer560.hub.roomsim.SimItems";
    private static final String STORE = "com.killer560.hub.autoroutes.RouteStore";
    private static final String COORDS = "com.killer560.hub.autoroutes.RouteCoords";
    private static final String FRAME = "com.killer560.hub.autoroutes.RouteCoords$Frame";
    private static final String FEATURE = "com.killer560.hub.autoroutes.AutoRoutesFeature";
    private static final String EXECUTOR = "com.killer560.hub.autoroutes.RouteExecutor";
    private static final String PLANNER = "com.killer560.hub.autoroutes.RoutePathPlanner";
    private static final String AR_CONFIG = "com.killer560.hub.autoroutes.AutoRoutesConfig";
    private static final String EDIT_SCREEN = "com.killer560.hub.autoroutes.AutoRoutesEditScreen";
    private static final String CLEAR = "com.killer560.hub.livemap.autoclear.ClearExecutor";
    private static final String DX_CONFIG = "com.killer560.hub.dungeonextras.DungeonExtrasConfig";

    // Order matters once: the editor's Go To is an Interactive Map warp, and after one only a START node may arm until
    // he has been through one (the map-arrival interlock), so the cases that arm non-start nodes run before it.
    private static final String[] CASES = {"96-ar-add", "96-ar-play", "96-ar-interact", "96-ar-await",
            "96-ar-awaitskip", "96-ar-leverwp", "96-ar-complete", "96-ar-mimic",
            "96-ar-crypt", "96-ar-breaker", "96-ar-breakerwait", "96-ar-pingpong", "96-ar-chain", "96-ar-stackorder", "96-ar-crypthold",
            "96-ar-charges", "96-ar-museum", "96-ar-398-offnode", "96-ar-398-regrow",
            // ar-nodes block (mod ar-nodes, 2026-10-11): run before 96-ar-edit's Go To, like the cases above.
            "96-ar-656-dbaddbreak", "96-ar-657-dbeditattack", "96-ar-658-dbreach", "96-ar-659-ewblockwait",
            "96-ar-662-jump",
            // ar-jump-n (2026-10-12)
            "96-ar-870-jumpn",
            // ar-jump-key (2026-10-12)
            "96-ar-872-jumpkey",
            // ar-jump-cooldown (2026-10-14)
            "96-ar-1014-jumpcd",
            // ac-route-memory (2026-10-14): run memory of secrets/bats/routed rooms, and the auto-puzzle hold
            "96-ar-1014-awaitmem", "96-ar-1014-batmem", "96-ar-1014-routedroom", "96-ar-1014-puzzlehold",
            // ar-mainserver (2026-10-15): Hypixel's pickup-with-the-landing order; left click skips in flight / under Kill Mimic
            "96-ar-1015-landpickup", "96-ar-1015-earlyskip", "96-ar-1015-mimicskip",
            // ar-warp-after-click (2026-10-15): Hypixel ignores a use right after a block click with the same item
            "96-ar-1015-warpclick",
            // click-gap-all (2026-10-15): the Auto Puzzles' shared use (AutoPuzzleUtil.useItemRotated) waits like the route's
            "96-ar-1015-puzzleclick",
            // server-lag-1015: a server stall during an etherwarp is not a missed landing (util/ServerLag)
            "96-ar-1015-serverlag",
            // sim-corrections-1015: a path's warps land on their planned blocks after a chest click with the AOTV held
            "96-ar-1015-pathland",
            // rightclick-reaim-1015: a warp whose server ray meets a block the client cannot click is re-aimed, one tick
            "96-ar-1015-reaim",
            // small-dungeon (2026-10-15): a secret bat already alive when the bat node arms is killed and counted
            "96-ar-1015-batpresent", "96-ar-1015-batseek",
            // crypt-aim (2026-10-15): a Spirit Sceptre crypt node aims at each crypt mob, two uses each
            "96-ar-1015-cryptaim",
            "96-ar-edit", "96-ar-dbedit", "96-ar-mapopen", "96-ar-mapopen-aim", "96-ar-398-startawait",
            "96-ar-405-crypt-mapopen", "96-ar-405-startwarp", "96-ar-path", "96-ar-screen", "96-ar-rotate",
            // sim-world (2026-10-11)
            "96-ar-644-mimic-warp",
            // mimic-fix (2026-10-11)
            "96-ar-651-mimic-legit-pitch",
            // path-wait (2026-10-11): legit warps land exactly where obvious ones do
            "96-ar-639-legit-warp-exact",
            // hype-down (2026-10-11)
            "96-ar-653-crypt-straight-down", "96-ar-1013-bat", "96-ar-1013-breakerfall",
            // ar-obvious-only (2026-10-11): legit mode exists only in -PtestingBuild jars
            "96-ar-786-obvious-only",
            // ar-startguard (2026-10-12): a map arrival's start-only hold on a route with no start node
            "96-ar-920-nostart-mapwarp",
            // ar-redo (2026-10-12): /ar redo restores deletes and clears
            "96-ar-960-redo-restore"};

    /** Relative feet height of the arena floor's top (the room's own spawn height). */
    private static int F;
    private static String room;
    private static final List<String> RESULTS = new ArrayList<>();

    // ============================================================================================ per-tick sampler

    /**
     * One client tick, sampled at its END. {@code out} is PacketTrace's bucket for the tick, read after the run: the
     * trace opens a bucket at START_CLIENT_TICK after the mod's own START handlers have run, so a route's use sent at
     * the start of tick n+1 lands in bucket n - right after bucket n's input packet, which is exactly the sneak state
     * the server applies to that use. So {@code shift} here is the sneak the server had for every use in {@code out}.
     */
    private record Sample(int tick, boolean shift, boolean sprint, Vec3 pos, List<String> out) {
    }

    private static final List<Sample> SAMPLES = new ArrayList<>();
    private static volatile boolean sampling;
    private static int sampleTick;
    private static boolean hooked;

    private static void startSampling(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            if (!hooked) {
                hooked = true;
                ClientTickEvents.END_CLIENT_TICK.register(c -> {
                    if (!sampling || c.player == null) {
                        return;
                    }
                    synchronized (SAMPLES) {
                        sampleTick++;
                        SAMPLES.add(new Sample(PacketTrace.tickCount() - 1, c.player.getLastSentInput().shift(),
                                c.player.isSprinting(), c.player.position(), List.of()));
                    }
                });
            }
            synchronized (SAMPLES) {
                SAMPLES.clear();
                sampleTick = 0;
            }
            PacketTrace.start();
            // Bucket 0 is the part-tick before the first sampled tick starts: a route's START_CLIENT_TICK packets on
            // that first tick land in it, judged against the input packet already sent - the state right now.
            synchronized (SAMPLES) {
                SAMPLES.add(new Sample(0, mc.player.getLastSentInput().shift(), mc.player.isSprinting(),
                        mc.player.position(), List.of()));
            }
            sampling = true;
        });
    }

    private static List<Sample> stopSampling(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            sampling = false;
            PacketTrace.stop();
        });
        List<Sample> out = new ArrayList<>();
        synchronized (SAMPLES) {
            for (Sample x : SAMPLES) {
                out.add(new Sample(x.tick(), x.shift(), x.sprint(), x.pos(), PacketTrace.sentOn(x.tick())));
            }
        }
        return out;
    }

    private static List<Sample> useTicks(List<Sample> s) {
        List<Sample> out = new ArrayList<>();
        for (Sample x : s) {
            if (x.out().contains("use_item")) {
                out.add(x);
            }
        }
        return out;
    }

    // ============================================================================================ test

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
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (SimMapTests.copyRoomsForOthers() < 20) {
            Scenario.skipped(NAME, "needs real room captures and room database");
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);
        room = pickRoom(ctx);
        println("room under test: " + room);
        ctx.runOnClient(mc -> {
            Object ar = ModUnderTest.config(AR_CONFIG);
            ModUnderTest.set(ar, "setEnabled", true);
            ModUnderTest.set(ar, "setLegitMode", false);
            ModUnderTest.set(ar, "setStartFromStartNodeOnly", true);
            ModUnderTest.set(ar, "setInteractDelayTicks", 2);
            ModUnderTest.set(ar, "setChatFeedback", true);
            ModUnderTest.call(ar, "setHeight", new Class<?>[]{float.class}, new Object[]{1.0f});
            Object auto = ModUnderTest.config("com.killer560.hub.autopuzzles.AutoPuzzlesConfig");
            ModUnderTest.set(auto, "setAutoPuzzlesMasterEnabled", false);
            Object map = ModUnderTest.config("com.killer560.hub.livemap.LiveMapConfig");
            ModUnderTest.set(map, "setEnabled", true);
            ModUnderTest.set(map, "setInteractiveMapEnabled", true);
            ModUnderTest.set(ModUnderTest.config(DX_CONFIG), "setBreakerAuraEnabled", false);
            ModUnderTest.set(ModUnderTest.config(DX_CONFIG), "setBreakerAuraMultiBreak", true);
        });
        try {
            buildRoom(ctx, 0);
            for (String c : selected) {
                long mark = LogTap.mark();
                try {
                    println("---- " + c + " ----");
                    switch (c) {
                        case "96-ar-add" -> caseAdd(ctx);
                        case "96-ar-play" -> casePlay(ctx);
                        case "96-ar-interact" -> caseInteract(ctx);
                        case "96-ar-await" -> caseAwait(ctx);
                        case "96-ar-awaitskip" -> caseAwaitSkip(ctx);
                        case "96-ar-leverwp" -> caseLeverWaypoint(ctx);
                        case "96-ar-complete" -> caseComplete(ctx);
                        case "96-ar-mimic" -> caseMimic(ctx);
                        case "96-ar-crypt" -> caseCrypt(ctx);
                        case "96-ar-breaker" -> caseBreaker(ctx);
                        case "96-ar-breakerwait" -> caseBreakerWait(ctx);
                        case "96-ar-pingpong" -> casePingPong(ctx);
                        case "96-ar-chain" -> caseChain(ctx);
                        case "96-ar-stackorder" -> caseStackOrder(ctx);
                        case "96-ar-crypthold" -> caseCryptHold(ctx);
                        case "96-ar-charges" -> caseCharges(ctx);
                        case "96-ar-museum" -> caseMuseum(ctx);
                        case "96-ar-398-offnode" -> caseOffNode(ctx);
                        case "96-ar-398-regrow" -> caseRegrow(ctx);
                        case "96-ar-398-startawait" -> caseStartAwait(ctx);
                        case "96-ar-405-crypt-mapopen" -> caseCryptMapOpen(ctx);
                        case "96-ar-405-startwarp" -> caseStartWarp(ctx);
                        case "96-ar-edit" -> caseEdit(ctx);
                        case "96-ar-656-dbaddbreak" -> ArNodesCases.addBreak(ctx);
                        case "96-ar-657-dbeditattack" -> ArNodesCases.editAttack(ctx);
                        case "96-ar-658-dbreach" -> ArNodesCases.reach(ctx);
                        case "96-ar-659-ewblockwait" -> ArNodesCases.ewBlockWait(ctx);
                        case "96-ar-662-jump" -> ArNodesCases.jump(ctx);
                        case "96-ar-870-jumpn" -> ArNodesCases.jumpCount(ctx);
                        case "96-ar-872-jumpkey" -> ArNodesCases.jumpKey(ctx);
                        case "96-ar-1014-jumpcd" -> ArJumpCooldownCases.jumpCooldown(ctx);
                        case "96-ar-1014-awaitmem" -> ArRouteMemoryCases.awaitMemory(ctx);
                        case "96-ar-1014-batmem" -> ArRouteMemoryCases.batMemory(ctx);
                        case "96-ar-1014-routedroom" -> ArRouteMemoryCases.routedRoom(ctx);
                        case "96-ar-1014-puzzlehold" -> ArRouteMemoryCases.puzzleHold(ctx);
                        case "96-ar-1015-landpickup" -> ArMainServerCases.landPickup(ctx);
                        case "96-ar-1015-earlyskip" -> ArMainServerCases.earlySkip(ctx);
                        case "96-ar-1015-mimicskip" -> ArMainServerCases.mimicSkip(ctx);
                        case "96-ar-1015-warpclick" -> ArMainServerCases.warpAfterClick(ctx);
                        case "96-ar-1015-puzzleclick" -> ArMainServerCases.puzzleWarpAfterClick(ctx);
                        case "96-ar-1015-serverlag" -> ArServerLagCases.serverLag(ctx);
                        case "96-ar-1015-pathland" -> ArPathLandCases.pathLand(ctx);
                        case "96-ar-1015-reaim" -> ArReaimCases.reaim(ctx);
                        case "96-ar-dbedit" -> caseDbEdit(ctx);
                        case "96-ar-mapopen" -> caseMapOpen(ctx);
                        case "96-ar-mapopen-aim" -> caseMapOpenAim(ctx);
                        case "96-ar-screen" -> caseScreen(ctx);
                        case "96-ar-path" -> casePath(ctx);
                        case "96-ar-rotate" -> caseRotate(ctx);
                        case "96-ar-644-mimic-warp" -> caseMimicWarp(ctx, MIMIC_MODES);
                        case "96-ar-651-mimic-legit-pitch" -> caseMimicWarp(ctx, LEGIT_AURA_HYPE);
                        case "96-ar-639-legit-warp-exact" -> caseLegitWarpExact(ctx);
                        case "96-ar-653-crypt-straight-down" -> caseCryptStraightDown(ctx);
                        case "96-ar-1013-bat" -> caseBatNode(ctx);
                        case "96-ar-1015-batpresent" -> caseBatPresent(ctx);
                        case "96-ar-1015-batseek" -> caseBatSeek(ctx);
                        case "96-ar-1015-cryptaim" -> caseCryptAim(ctx);
                        case "96-ar-1013-breakerfall" -> caseBreakerFall(ctx);
                        case "96-ar-786-obvious-only" -> caseObviousOnly(ctx);
                        case "96-ar-920-nostart-mapwarp" -> caseNoStartMapWarp(ctx);
                        case "96-ar-960-redo-restore" -> caseRedoRestore(ctx);
                        default -> {
                        }
                    }
                    if (caseSkip != null) {
                        RESULTS.add(c + " SKIP - " + caseSkip);
                        System.out.println("[" + c + "] SKIP - " + caseSkip);
                        caseSkip = null;
                        continue;
                    }
                    RESULTS.add(c + " PASS");
                    System.out.println("[" + c + "] PASS");
                } catch (Throwable t) {
                    RESULTS.add(c + " FAIL: " + t.getMessage());
                    System.out.println("[" + c + "] FAIL: " + t);
                    for (String l : tail(LogTap.since(mark), 60)) {
                        System.out.println("[" + c + "]   log: " + l);
                    }
                    SuiteVerdict.failCase(c, t);
                    // A case that threw can leave a route running or a screen open; the next starts clean.
                    ctx.runOnClient(mc -> {
                        McCompat.setScreen(mc, null);
                        ModUnderTest.staticCall(EXECUTOR, "stop", new Class<?>[]{String.class}, new Object[]{"test"});
                    });
                    if (ctx.computeOnClient(mc -> mc.level == null)) {
                        break;
                    }
                }
            }
        } finally {
            teardown(ctx);
        }
        System.out.println("[" + NAME + "] SUMMARY");
        for (String r : RESULTS) {
            System.out.println("[" + NAME + "]   " + r);
        }
    }

    // ============================================================================================ cases

    /** /ar add of every type through the chat command: snapping, numbering, the file, and each firing on add. */
    private void caseAdd(ClientGameTestContext ctx) {
        resetRoutes(ctx);
        arena(ctx, false);
        giveHotbar(ctx);
        // ---- ew start: aimed from S (6,6) at the floor at T (14,6) ----
        tpRel(ctx, 6.3, 6.7, relYawTo(6, 6, 14, 6), pitchTo(6, 6, 14, 6));
        startSampling(ctx);
        cmd(ctx, "/ar add ew start");
        Vec3 landed = waitLanded(ctx, 14, 6, 60);
        List<Sample> s = stopSampling(ctx);
        check(landed != null, "/ar add ew start did not warp him to (14,6) - fire on add; he is at " + relPos(ctx));
        check(useTicks(s).size() == 1, "fire on add sent " + useTicks(s).size() + " use_item packet(s), expected 1");
        JsonArray nodes = fileNodes(ctx);
        check(nodes.size() == 1, "file has " + nodes.size() + " node(s) after the first add");
        JsonObject n1 = nodes.get(0).getAsJsonObject();
        check("ETHERWARP".equals(n1.get("type").getAsString()) && n1.has("start"), "node 1 is not an ETHERWARP start: " + n1);
        checkSnapped(n1, 6, 6);
        check(n1.has("landing"), "the etherwarp node saved no landing: " + n1);

        // ---- walk at T facing +Z: sprints until the wall at z 27 ----
        tpRel(ctx, 14.5, 6.5, 0f, 0f);
        ctx.waitTicks(5);
        startSampling(ctx);
        cmd(ctx, "/ar add walk");
        ctx.waitTicks(80);
        s = stopSampling(ctx);
        Vec3 after = relPos(ctx);
        boolean sprinted = s.stream().anyMatch(Sample::sprint);
        check(after.z > 20, "/ar add walk did not sprint him toward +z (he is at " + after + ")");
        check(sprinted, "the walk never sprinted");
        check(s.stream().noneMatch(Sample::shift), "the walk sent shift down");
        // The walk may still be held against the wall. Until mod no-stop-corrections the placing tp below stopped it
        // ("you moved the camera"); a server move never stops a route now, so the held walk turned him back to its yaw
        // after the tp and the boom was recorded facing the wall. End it the way he would.
        cmd(ctx, "/ar stop");
        ctx.waitTicks(2);

        // ---- boom at (20,20) facing +X at cracked bricks at (22,F..F+1,20) ----
        setBlocks(ctx, Map.of(new int[]{22, F, 20}, Blocks.CRACKED_STONE_BRICKS.defaultBlockState(),
                new int[]{22, F + 1, 20}, Blocks.CRACKED_STONE_BRICKS.defaultBlockState()));
        tpRel(ctx, 20.5, 20.5, -90f, 0f);
        ctx.waitTicks(5);
        println("before /ar add boom: at " + relPos(ctx) + ", yaw " + ctx.computeOnClient(mc -> mc.player.getYRot())
                + ", bricks " + blockState(ctx, 22, F + 1, 20) + " / " + blockState(ctx, 22, F, 20));
        cmd(ctx, "/ar add boom");
        boolean broke = waitFor(ctx, 60, () -> blockAir(ctx, 22, F + 1, 20) && blockAir(ctx, 22, F, 20));
        check(broke, "/ar add boom did not blow the cracked bricks in front of him");

        // ---- breaker at (20,10): fires with no blocks, then /ar edit db picks two, then it breaks them ----
        setBlocks(ctx, Map.of(new int[]{22, F, 10}, Blocks.STONE.defaultBlockState(),
                new int[]{22, F + 1, 10}, Blocks.STONE.defaultBlockState()));
        tpRel(ctx, 20.5, 10.5, -90f, 0f);
        ctx.waitTicks(5);
        long m = LogTap.mark();
        cmd(ctx, "/ar add breaker start");
        ctx.waitTicks(10);
        check(logHas(m, "DUNGEON_BREAKER acted 0 tick(s) after firing") || logHas(m, "DUNGEON_BREAKER acted"),
                "the breaker node did not fire on add");
        cmd(ctx, "/ar edit db");
        ctx.waitTicks(3);
        // Edit mode picks only with the Dungeon Breaker in hand (mod ar-breaker-wait); any other item clicks normally.
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(2));
        ctx.waitTicks(2);
        long mPick = LogTap.mark();
        startSampling(ctx);
        rightClick(ctx, 22, F, 10, Direction.WEST);
        ctx.waitTicks(3);
        rightClick(ctx, 22, F + 1, 10, Direction.WEST);
        ctx.waitTicks(3);
        cmd(ctx, "/ar edit db");
        // Long enough for the breaker re-fire to have acted: on main it read the two picks as blocks grown back on the
        // node he stands on and broke them the tick edit mode closed (2026-10-15).
        ctx.waitTicks(20);
        s = stopSampling(ctx);
        int pickDigs = 0;
        for (Sample x : s) {
            pickDigs += (int) x.out().stream().filter("player_action"::equals).count();
        }
        boolean refiredOnPick = logHas(mPick, "standing while he is on it");
        println("picks: " + pickDigs + " player_action packet(s) from the picks to 20 ticks after edit mode closed,"
                + " re-fire logged " + refiredOnPick);
        check(pickDigs == 0, "picking (and closing edit mode) sent " + pickDigs + " dig packet(s)");
        check(!refiredOnPick, "closing edit mode re-fired the breaker node on the blocks he had just picked");
        nodes = fileNodes(ctx);
        JsonObject br = nodes.get(3).getAsJsonObject();
        check("DUNGEON_BREAKER".equals(br.get("type").getAsString()) && br.has("blocks")
                && br.getAsJsonArray("blocks").size() == 2, "the breaker did not save its two picked blocks: " + br);
        // Picking breaks nothing (killer560, 2026-10-11; 96-ar-656-dbaddbreak).
        check(!blockAir(ctx, 22, F, 10) && !blockAir(ctx, 22, F + 1, 10), "edit mode broke a block it should only have picked");
        // Step off and back on: the start node arms and breaks both.
        tpRel(ctx, 20.5, 14.5, -90f, 0f);
        ctx.waitTicks(10);
        startSampling(ctx);
        tpRel(ctx, 20.5, 10.5, -90f, 0f);
        boolean broken = waitFor(ctx, 60, () -> blockAir(ctx, 22, F, 10) && blockAir(ctx, 22, F + 1, 10));
        s = stopSampling(ctx);
        check(broken, "the breaker node did not break its two blocks when he stepped back on");
        int digs = 0;
        for (Sample x : s) {
            digs += (int) x.out().stream().filter("player_action"::equals).count();
        }
        check(digs >= 2, "only " + digs + " player_action packet(s) for two breaker blocks");

        // ---- use at (10,20) facing -Z with the AOTV in hand (Instant Transmission) ----
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
        tpRel(ctx, 10.5, 20.5, 180f, 0f);
        ctx.waitTicks(5);
        startSampling(ctx);
        cmd(ctx, "/ar add use");
        ctx.waitTicks(30);
        s = stopSampling(ctx);
        Vec3 u = relPos(ctx);
        check(u.z < 16, "/ar add use did not transmit him toward -z (at " + u + ")");
        List<Sample> uses = useTicks(s);
        StringBuilder trace = new StringBuilder();
        for (Sample x : s) {
            trace.append(String.format(" | t%d shift=%s %s", x.tick(), x.shift(), x.out()));
        }
        check(!uses.isEmpty() && !uses.get(0).shift(), "the use node's use went out while shift was down:" + trace);

        nodes = fileNodes(ctx);
        String[] types = {"ETHERWARP", "WALK", "BOOM", "DUNGEON_BREAKER", "USE_ITEM"};
        check(nodes.size() == 5, "expected 5 nodes in the file, found " + nodes.size());
        for (int i = 0; i < 5; i++) {
            check(types[i].equals(nodes.get(i).getAsJsonObject().get("type").getAsString()),
                    "node " + (i + 1) + " is " + nodes.get(i) + ", expected " + types[i]);
        }
        checkSnapped(nodes.get(2).getAsJsonObject(), 20, 20);
        // Only one start: the breaker took it from the ew.
        int starts = 0;
        for (JsonElement e : nodes) {
            starts += e.getAsJsonObject().has("start") ? 1 : 0;
        }
        check(starts == 1 && nodes.get(3).getAsJsonObject().has("start"), "start was not moved to the breaker");
        check(nodes.get(4).getAsJsonObject().has("item"), "the use node saved no item");
        // Refusals: await:x and an unknown type add nothing.
        cmd(ctx, "/ar add ew await:x");
        // "jump" was the unknown word here until mod ar-nodes made it a node type (96-ar-662-jump).
        cmd(ctx, "/ar add hop");
        ctx.waitTicks(5);
        check(fileNodes(ctx).size() == 5, "a refused /ar add still added a node");
        // A bare "await:" (what tab completion leaves) means await:1 (killer560, 2026-10-06). The node waits for its
        // secret, so adding it does not warp.
        cmd(ctx, "/ar add ew await:");
        ctx.waitTicks(5);
        nodes = fileNodes(ctx);
        check(nodes.size() == 6, "/ar add ew await: added no node (file has " + nodes.size() + ")");
        if (nodes.size() == 6) {
            JsonObject bare = nodes.get(5).getAsJsonObject();
            check(bare.has("awaitEnabled") && bare.get("awaitEnabled").getAsBoolean()
                    && bare.has("amount") && bare.get("amount").getAsInt() == 1,
                    "/ar add ew await: saved " + bare + ", expected await enabled with amount 1");
        }
        cmd(ctx, "/ar stop");
    }

    /**
     * Playback of a hand-written path-less route: S --ew--> T, where a stack of boom / ew / breaker (added in that
     * order) must fire breaker, boom, ew; T --ew--> U; walk at U sprints to (22,26); an await:1 etherwarp there
     * waits for a chest secret (whose screen does not stop it) and then warps to (14,26); a use node there sends its
     * use with shift up.
     */
    private void casePlay(ClientGameTestContext ctx) {
        resetRoutes(ctx);
        arena(ctx, false);
        giveHotbar(ctx);
        setBlocks(ctx, Map.of(new int[]{14, F, 4}, Blocks.CRACKED_STONE_BRICKS.defaultBlockState(),
                new int[]{14, F + 1, 4}, Blocks.CRACKED_STONE_BRICKS.defaultBlockState(),
                new int[]{15, F, 8}, Blocks.STONE.defaultBlockState(),
                new int[]{16, F, 8}, Blocks.STONE.defaultBlockState(),
                new int[]{24, F, 24}, Blocks.CHEST.defaultBlockState()));
        List<JsonObject> n = new ArrayList<>();
        n.add(ew(6, 6, 14, 6, true));                        // #1
        JsonObject boom = node("BOOM", 14, 6, 180f, 0f);      // #2
        n.add(boom);
        n.add(ew(14, 6, 22, 6, false));                      // #3
        JsonObject br = node("DUNGEON_BREAKER", 14, 6, 0f, 0f); // #4
        JsonArray blocks = new JsonArray();
        blocks.add("15 " + (F) + " 8");
        blocks.add("16 " + (F) + " 8");
        br.add("blocks", blocks);
        n.add(br);
        n.add(node("WALK", 22, 6, 0f, 0f));                  // #5
        JsonObject w = ew(22, 26, 14, 26, false);             // #6
        w.addProperty("awaitEnabled", true);
        w.addProperty("await", "SECRET");
        w.addProperty("amount", 1);
        n.add(w);
        JsonObject use = node("USE_ITEM", 14, 26, 180f, 0f);  // #7
        use.addProperty("item", "ASPECT_OF_THE_VOID");
        n.add(use);
        writeRoute(ctx, n);
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(3));
        tpRel(ctx, 4.5, 6.5, 0f, 0f);
        ctx.waitTicks(10);
        long mark = LogTap.mark();
        startSampling(ctx);
        tpRel(ctx, 6.5, 6.5, -90f, 0f);
        // Through T's stack, to U, the walk, and into the await.
        boolean atAwait = waitFor(ctx, 200, () -> logHas(mark, "Node #6 ETHERWARP begins"));
        check(atAwait, "the route never reached the await node #6 (at " + relPos(ctx) + ")");
        ctx.waitTicks(60);
        List<Sample> before = stopSampling(ctx);
        // ---- the stack order at T ----
        int i4 = logIndex(mark, "Node #4 DUNGEON_BREAKER acted");
        int i2 = logIndex(mark, "Node #2 BOOM acted");
        int i3 = logIndex(mark, "Node #3 ETHERWARP acted");
        check(i4 >= 0 && i2 > i4 && i3 > i2, "T's stack did not fire breaker, boom, ew (log order " + i4 + ", " + i2
                + ", " + i3 + ")");
        check(blockAir(ctx, 14, F + 1, 4), "the stacked boom broke nothing");
        check(blockAir(ctx, 15, F, 8) && blockAir(ctx, 16, F, 8), "the stacked breaker did not break both blocks");
        // ---- each action on the tick it fired ----
        for (String t : new String[]{"Node #4 DUNGEON_BREAKER acted 0 tick", "Node #2 BOOM acted 0 tick",
                "Node #3 ETHERWARP acted 0 tick", "Node #5 WALK acted 0 tick"}) {
            check(logHas(mark, t), "missing \"" + t + "\" - an action took longer than its firing tick");
        }
        check(logHas(mark, "Node #1 ETHERWARP acted 0 tick") || logHas(mark, "Node #1 ETHERWARP acted 1 tick"),
                "the first etherwarp took more than one tick");
        check(logHasAll(mark, "Node #3 ETHERWARP acted 0 tick(s)", "sneak already held"),
                "the sneak was not held from #1 through the stack into #3");
        // ---- sneak: down every tick from #1's use to #3's use, up for the walk ----
        List<Sample> uses = useTicks(before);
        check(uses.size() >= 2, "expected the two etherwarp uses before the await, saw " + uses.size());
        if (uses.size() >= 2) {
            for (Sample x : before) {
                if (x.tick() >= uses.get(0).tick() && x.tick() <= uses.get(1).tick()) {
                    check(x.shift(), "shift was up on tick " + x.tick() + " between the #1 and #3 etherwarps");
                }
            }
        }
        check(logHas(mark, "Sneak released after node #3: next is #5 WALK"), "the sneak was not released for the walk");
        check(before.stream().anyMatch(Sample::sprint), "the walk never sprinted");
        // ---- the await: nothing sent while it waits ----
        check(uses.size() == 2, "a use went out while #6 was awaiting a secret (" + uses.size() + " uses)");
        check(relPos(ctx).distanceTo(new Vec3(22.5, F, 26.5)) < 1.5, "he is not on the await node: " + relPos(ctx));
        // ---- click the chest: the click is the secret ----
        // killer560 (2026-10-05): the await is met "once it detects I actually click something". On Hypixel the server
        // credits a chest on the click packet itself and the window is only its view, arriving a round trip later; so
        // the route may warp before the window, and the secret is not lost. The window can arrive either side of the
        // warp - here, before it on 26.1.2 and not at all before it on 26.2 (measured 2026-10-05, 3/3) - and both
        // orders are right, as long as the click was credited and a window that arrives late does not end the route.
        int found0 = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(SIM_SCORE, "secretsFound"));
        startSampling(ctx);
        rightClick(ctx, 24, F, 24, Direction.UP);
        boolean met = waitFor(ctx, 60, () -> awaitMet(mark));
        check(met, "the await never saw the chest click");
        check(logHas(mark, "await secret 1: chest at"), "the await was not met by the chest click");
        check(waitFor(ctx, 20, () -> ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(SIM_SCORE,
                "secretsFound")) > found0), "the sim did not credit the chest secret for the click");
        // Which came first: the chest's window, or #6's warp.
        check(waitFor(ctx, 40, () -> logHas(mark, "Node #6 ETHERWARP acted")
                        || ctx.computeOnClient(mc -> McCompat.screen(mc) != null)),
                "after the click neither the chest window opened nor #6 acted");
        boolean windowFirst = !logHas(mark, "Node #6 ETHERWARP acted");
        println("96-ar-play: " + (windowFirst ? "the chest window came before the warp"
                : "the warp went out before any window"));
        Vec3 l6;
        if (windowFirst) {
            // The window came first: the route waits under it, sends nothing, and warps once it is closed.
            check(ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(EXECUTOR, "isRunning")),
                    "the chest's screen stopped the route");
            check(useTicks(stopSampling(ctx)).isEmpty(), "the etherwarp went out while the chest screen was open");
            startSampling(ctx);
            ctx.runOnClient(mc -> mc.player.closeContainer());
            l6 = waitLanded(ctx, 14, 26, 60);
            check(l6 != null, "#6 did not warp after the screen closed (at " + relPos(ctx) + ")");
        } else {
            // The click met the await before any window: #6 warps at once. Hypixel's window then arrives a round trip
            // late, after the warp - emulated here as a container the server opens once #6 has acted (a chest GUI with
            // no distance check, as Hypixel's are). It must hold the route like the chest's own window, not stop it.
            check(waitFor(ctx, 20, () -> logHas(mark, "Node #6 ETHERWARP acted")), "#6 did not act after the click");
            openLateWindow(ctx);
            check(waitFor(ctx, 20, () -> ctx.computeOnClient(mc -> McCompat.screen(mc) != null)),
                    "the late chest window never opened");
            ctx.waitTicks(10);
            check(!logHas(mark, "Stopped: a screen opened"), "the chest window arriving after the warp stopped the route");
            check(ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(EXECUTOR, "isRunning")),
                    "the route is not running under the late chest window");
            check(!logHas(mark, "Node #7 USE_ITEM acted"), "#7 fired under the late chest window");
            l6 = waitLanded(ctx, 14, 26, 5);
            check(l6 != null, "#6's warp did not land (at " + relPos(ctx) + ")");
            ctx.runOnClient(mc -> mc.player.closeContainer());
        }
        ctx.waitTicks(30);
        List<Sample> afterChest = stopSampling(ctx);
        List<Sample> uses2 = useTicks(afterChest);
        check(uses2.size() == 2, "expected #6's etherwarp and #7's use after the chest, saw " + uses2.size());
        if (uses2.size() == 2) {
            check(uses2.get(0).shift(), "#6's etherwarp went out without shift");
            check(!uses2.get(1).shift(), "#7's use went out with shift down");
        }
        check(relPos(ctx).z < 22, "#7's Instant Transmission did not move him toward -z (" + relPos(ctx) + ")");
        check(logHas(mark, "complete"), "the route did not complete");
    }

    /**
     * A breaker node of six blocks: with Breaker Aura's Multi Break on every START_DESTROY_BLOCK goes on the firing
     * tick; off, one per interact-delay tick; and with fewer charges than blocks (after a real 17-block node) it waits
     * and sends all six in one burst.
     */
    private void caseBreaker(ClientGameTestContext ctx) {
        resetRoutes(ctx);
        arena(ctx, false);
        giveHotbar(ctx);
        int[][] six = {{12, 0}, {13, 0}, {14, 0}, {12, 1}, {13, 1}, {14, 1}};
        try {
            for (boolean multi : new boolean[]{true, false}) {
                ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(DX_CONFIG), "setBreakerAuraMultiBreak", multi));
                Map<int[], BlockState> put = new HashMap<>();
                JsonArray blocks = new JsonArray();
                for (int[] b : six) {
                    put.put(new int[]{b[0], F + b[1], 12}, Blocks.STONE.defaultBlockState());
                    blocks.add(b[0] + " " + (F + b[1]) + " 12");
                }
                setBlocks(ctx, put);
                JsonObject br = node("DUNGEON_BREAKER", 13, 10, 0f, 0f);
                br.add("blocks", blocks);
                br.addProperty("start", true);
                writeRoute(ctx, List.of(br));
                tpRel(ctx, 13.5, 8.5, 0f, 0f);
                ctx.waitTicks(40);   // charges back up
                long m = LogTap.mark();
                startSampling(ctx);
                tpRel(ctx, 13.5, 10.5, 0f, 0f);
                boolean gone = waitFor(ctx, 60, () -> {
                    for (int[] b : six) {
                        if (!blockAir(ctx, b[0], F + b[1], 12)) {
                            return false;
                        }
                    }
                    return true;
                });
                List<Sample> s = stopSampling(ctx);
                int most = 0;
                int total = 0;
                for (Sample x : s) {
                    int c = (int) x.out().stream().filter("player_action"::equals).count();
                    most = Math.max(most, c);
                    total += c;
                }
                println("breaker, multi break " + (multi ? "ON" : "OFF") + ": " + total + " dig(s), at most " + most
                        + " on one tick, all gone " + gone);
                check(gone, "multi break " + multi + ": not all six blocks broke");
                check(total == 6, "multi break " + multi + ": " + total + " dig packets for six blocks");
                check(multi ? most == 6 : most == 1, "multi break " + multi + ": " + most + " digs on one tick");
                check(logHas(m, "DUNGEON_BREAKER acted 0 tick(s)"), "the breaker did not act on its firing tick");
                tpRel(ctx, 13.5, 6.5, 0f, 0f);
                ctx.waitTicks(5);
            }
            // ---- fewer charges than blocks: a 17-block node first, then the six at once ----
            ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(DX_CONFIG), "setBreakerAuraMultiBreak", true));
            Map<int[], BlockState> put = new HashMap<>();
            JsonArray big = new JsonArray();
            for (int i = 0; i < 17; i++) {
                int x = 8 + (i % 9);
                int y = F + (i / 9);
                put.put(new int[]{x, y, 18}, Blocks.STONE.defaultBlockState());
                big.add(x + " " + y + " 18");
            }
            JsonArray blocks = new JsonArray();
            for (int[] b : six) {
                put.put(new int[]{b[0], F + b[1], 12}, Blocks.STONE.defaultBlockState());
                blocks.add(b[0] + " " + (F + b[1]) + " 12");
            }
            setBlocks(ctx, put);
            JsonObject drain = node("DUNGEON_BREAKER", 12, 16, 0f, 0f);
            drain.add("blocks", big);
            drain.addProperty("start", true);
            JsonObject br = node("DUNGEON_BREAKER", 13, 10, 0f, 0f);
            br.add("blocks", blocks);
            writeRoute(ctx, List.of(drain, br));
            ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setStartFromStartNodeOnly", false));
            tpRel(ctx, 12.5, 14.5, 0f, 0f);
            ctx.waitTicks(40);
            long m = LogTap.mark();
            tpRel(ctx, 12.5, 16.5, 0f, 0f);
            ctx.waitTicks(4);
            startSampling(ctx);
            tpRel(ctx, 13.5, 10.5, 0f, 0f);
            // Since mod ar-breaker-wait a node short of charges waits for them (2 a second) and then breaks all six
            // at once - never a partial burst. 96-ar-breakerwait measures the wait itself.
            waitFor(ctx, 160, () -> {
                for (int[] b : six) {
                    if (!blockAir(ctx, b[0], F + b[1], 12)) {
                        return false;
                    }
                }
                return true;
            });
            List<Sample> s = stopSampling(ctx);
            int total = 0;
            int most = 0;
            for (Sample x : s) {
                int c = (int) x.out().stream().filter("player_action"::equals).count();
                total += c;
                most = Math.max(most, c);
            }
            int charges = -1;
            java.util.regex.Pattern cp = java.util.regex.Pattern.compile("Breaker: 6 block\\(s\\) queued, (\\d+) charge");
            for (String l : LogTap.since(m)) {
                java.util.regex.Matcher mm = cp.matcher(l);
                if (mm.find()) {
                    charges = Integer.parseInt(mm.group(1));
                }
            }
            println("drained breaker: " + charges + " charge(s) read, " + total + " dig(s) sent for 6 blocks, at most "
                    + most + " on one tick");
            check(charges >= 0, "the second breaker node never fired");
            check(total == 6 && most == 6, "charges " + charges + ": " + total + " digs, " + most
                    + " on one tick - expected the six in one burst");
            if (charges < 6) {
                check(logHas(m, "breaker charges (have"), "with " + charges + " charges there was no wait line");
            }
        } finally {
            ctx.runOnClient(mc -> {
                ModUnderTest.set(ModUnderTest.config(DX_CONFIG), "setBreakerAuraMultiBreak", true);
                ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setStartFromStartNodeOnly", true);
            });
        }
    }

    // ============================================================================================ ar-startguard

    /**
     * 96-ar-920-nostart-mapwarp (killer560, 2026-10-12: "dungeon breaker nodes are not working in ar. they do not break
     * blocks"). His Mod Only Test log: an Interactive Map warp set Auto Routes' map-arrival hold (start node only), his
     * Museum route has NO start node, and every check after said "start only, no start node" - nothing in the room armed
     * again. Start Node Only is OFF here, so the map's hold is the only thing asking for a start node.
     * <p>
     * The hold is set the way the map sets it: the Go + Secret bind with the cursor off the map
     * ({@code InteractiveMapFeature.pathToCurrentRoomStartNode}, which goes through the map's goal queue and
     * {@code AutoRoutesFeature.cancelForInteractiveMap}), and then whatever pathing the map did to the room. The hold is
     * only READ by reflection, to prove it was on before he stepped on.
     * <p>
     * A: a route of one DUNGEON_BREAKER node, no start node, six stone blocks. After the press he stands on it: it must
     * arm and all six must break. B (control): a start breaker node and a non-start breaker node. After the press the
     * non-start one must NOT arm (its blocks stand); once he has been through the start node, it breaks its blocks.
     */
    private void caseNoStartMapWarp(ClientGameTestContext ctx) {
        resetRoutes(ctx);
        arena(ctx, false);
        giveHotbar(ctx);
        ctx.runOnClient(mc -> {
            ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setStartFromStartNodeOnly", false);
            ModUnderTest.set(ModUnderTest.config(DX_CONFIG), "setBreakerAuraMultiBreak", true);
        });
        // Own rows, clear of every other breaker case's regrowing blocks (z 12, 18, 22).
        int[][] rowA = {{19, 0, 25}, {20, 0, 25}, {21, 0, 25}, {19, 1, 25}, {20, 1, 25}, {21, 1, 25}};
        int[][] rowS = {{7, 0, 25}, {8, 0, 25}, {9, 0, 25}};
        int[][] rowB = {{19, 0, 5}, {20, 0, 5}, {21, 0, 5}, {19, 1, 5}, {20, 1, 5}, {21, 1, 5}};
        try {
            // ---- A: no start node, after each way the map sets the hold: A1 the Go + Secret press, A2 map closed ----
            for (int pass = 1; pass <= 2; pass++) {
                String part = "A" + pass;
                placeStone(ctx, rowA);
                JsonObject a = node("DUNGEON_BREAKER", 20, 23, 0f, 0f);
                a.add("blocks", blockList(rowA));
                writeRoute(ctx, List.of(a));
                if (pass == 1) {
                    mapPressAndSettle(ctx, part);
                } else {
                    mapOpenClose(ctx, part);
                }
                check(arrivalGuard(ctx), part + ": the map did not set the map-arrival hold - the case would prove nothing");
                awaitCharges(ctx, 6);
                long m = LogTap.mark();
                tpRel(ctx, 20.5, 23.5, 0f, 0f);
                boolean gone = waitFor(ctx, 80, () -> allAir(ctx, rowA));
                int standing = standingCount(ctx, rowA);
                println("nostart-mapwarp " + part + ": " + (6 - standing) + " of 6 block(s) broke, armed "
                        + logHas(m, "armed node #1 (DUNGEON_BREAKER)") + ", hold after " + arrivalGuard(ctx));
                if (!gone) {
                    for (String l : LogTap.since(m)) {
                        if (l.contains("[AutoRoutes] Arming")) {
                            println("nostart-mapwarp " + part + ":   " + l);
                        }
                    }
                }
                check(logHas(m, "armed node #1 (DUNGEON_BREAKER)"), part + ": the breaker node (no start node in the "
                        + "route) did not arm after a map arrival");
                check(gone, part + ": " + standing + " of the six blocks still stand after the breaker node armed");
                check(!arrivalGuard(ctx), part + ": the map-arrival hold outlived arming a node of a route with no start node");
                tpRel(ctx, 14.5, 15.5, 0f, 0f);
                ctx.waitTicks(5);
                ctx.runOnClient(mc -> ModUnderTest.staticCall(EXECUTOR, "stop", new Class<?>[]{String.class},
                        new Object[]{"test"}));
            }

            // ---- B (control): a start node exists, so the map arrival still means start node first. The hold comes
            // from closing the map (a Go + Secret press would warp him straight onto the start node). ----
            placeStone(ctx, rowS);
            placeStone(ctx, rowB);
            JsonObject s = node("DUNGEON_BREAKER", 8, 23, 0f, 0f);
            s.add("blocks", blockList(rowS));
            s.addProperty("start", true);
            JsonObject b = node("DUNGEON_BREAKER", 20, 7, 0f, 0f);
            b.add("blocks", blockList(rowB));
            writeRoute(ctx, List.of(s, b));
            mapOpenClose(ctx, "B");
            check(arrivalGuard(ctx), "B: the map did not set the map-arrival hold");
            awaitCharges(ctx, 9);
            long m = LogTap.mark();
            tpRel(ctx, 20.5, 7.5, 0f, 0f);
            ctx.waitTicks(40);
            int bStanding = standingCount(ctx, rowB);
            println("nostart-mapwarp B: on the non-start node after the map: " + bStanding + " of 6 standing, armed "
                    + logHas(m, "armed node") + ", hold " + arrivalGuard(ctx));
            check(!logHas(m, "armed node"), "B: a non-start node armed after a map arrival on a route with a start node");
            check(bStanding == 6, "B: " + (6 - bStanding) + " of the non-start node's blocks broke before the start node");
            check(arrivalGuard(ctx), "B: the hold went away without him passing the start node");
            // Through the start node: it arms and breaks its three, and the hold is over.
            tpRel(ctx, 14.5, 15.5, 0f, 0f);
            ctx.waitTicks(5);
            m = LogTap.mark();
            tpRel(ctx, 8.5, 23.5, 0f, 0f);
            boolean sGone = waitFor(ctx, 80, () -> allAir(ctx, rowS));
            println("nostart-mapwarp B: start node armed " + logHas(m, "armed node #1 (DUNGEON_BREAKER, start)")
                    + ", its blocks gone " + sGone + ", hold " + arrivalGuard(ctx));
            check(logHas(m, "armed node #1 (DUNGEON_BREAKER, start)"), "B: the start node did not arm after the map press");
            check(sGone, "B: the start node's blocks did not break");
            check(!arrivalGuard(ctx), "B: the hold outlived the start node");
            awaitCharges(ctx, 6);
            tpRel(ctx, 20.5, 7.5, 0f, 0f);
            boolean bGone = waitFor(ctx, 80, () -> allAir(ctx, rowB));
            println("nostart-mapwarp B: after the start node, the non-start node's blocks gone " + bGone + " ("
                    + standingCount(ctx, rowB) + " standing)");
            check(bGone, "B: after the start node the non-start breaker node did not break its blocks");
        } finally {
            ctx.runOnClient(mc -> {
                ModUnderTest.staticCall(EXECUTOR, "stop", new Class<?>[]{String.class}, new Object[]{"test"});
                ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setStartFromStartNodeOnly", true);
            });
        }
    }

    private static void placeStone(ClientGameTestContext ctx, int[][] row) {
        Map<int[], BlockState> put = new HashMap<>();
        for (int[] p : row) {
            put.put(new int[]{p[0], F + p[1], p[2]}, Blocks.STONE.defaultBlockState());
        }
        setBlocks(ctx, put);
    }

    private static JsonArray blockList(int[][] row) {
        JsonArray arr = new JsonArray();
        for (int[] p : row) {
            arr.add(p[0] + " " + (F + p[1]) + " " + p[2]);
        }
        return arr;
    }

    private static int standingCount(ClientGameTestContext ctx, int[][] row) {
        int n = 0;
        for (int[] p : row) {
            if (!blockAir(ctx, p[0], F + p[1], p[2])) {
                n++;
            }
        }
        return n;
    }

    private static boolean allAir(ClientGameTestContext ctx, int[][] row) {
        return standingCount(ctx, row) == 0;
    }

    /** Auto Routes' map-arrival hold, READ only (it is set by the map press, never here). */
    private static boolean arrivalGuard(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            try {
                java.lang.reflect.Field f = Class.forName(FEATURE).getDeclaredField("mapArrivalGuard");
                f.setAccessible(true);
                return f.getBoolean(null);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /** The Interactive Map opened (Run While Map Open OFF) and closed from the arena's middle: Auto Routes' interlock 3+4. */
    private static void mapOpenClose(ClientGameTestContext ctx, String part) {
        tpRel(ctx, 14.5, 15.5, 0f, 0f);
        ctx.waitTicks(10);
        ctx.runOnClient(mc -> {
            ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setRunWhileMapOpen", false);
            try {
                McCompat.setScreen(mc, (Screen) Class.forName("com.killer560.hub.livemap.InteractiveMapScreen")
                        .getConstructor(boolean.class).newInstance(true));
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        });
        ctx.waitTicks(10);
        String screen = ctx.computeOnClient(mc -> String.valueOf(McCompat.screen(mc)));
        ctx.runOnClient(mc -> McCompat.setScreen(mc, null));
        ctx.waitTicks(10);
        println("nostart-mapwarp " + part + ": map was open (" + screen + ") and closed");
        check(screen.contains("InteractiveMapScreen"), part + ": the Interactive Map did not open: " + screen);
    }

    /** Waits (up to 15 s) until the sim breaker holds at least {@code n} charges. */
    private static void awaitCharges(ClientGameTestContext ctx, int n) {
        check(waitFor(ctx, 300, () -> simCharges(ctx) >= n), "the sim breaker never got back to " + n + " charges ("
                + simCharges(ctx) + ")");
    }

    /**
     * From the arena's middle, clear of every node: the Go + Secret bind with the cursor off the map, as he presses it
     * ({@code InteractiveMapFeature.pathToCurrentRoomStartNode}), then waits for whatever path the map started to end.
     */
    private static void mapPressAndSettle(ClientGameTestContext ctx, String part) {
        tpRel(ctx, 14.5, 15.5, 0f, 0f);
        ctx.waitTicks(10);
        long m = LogTap.mark();
        ctx.runOnClient(mc -> {
            try {
                java.lang.reflect.Method press = Class.forName("com.killer560.hub.livemap.InteractiveMapFeature")
                        .getDeclaredMethod("pathToCurrentRoomStartNode");
                press.setAccessible(true);
                press.invoke(null);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        });
        ctx.waitTicks(5);
        boolean settled = waitFor(ctx, 200, () -> ctx.computeOnClient(mc ->
                !(Boolean) ModUnderTest.staticCall(CLEAR, "isBusy")
                        && !(Boolean) ModUnderTest.staticCall("com.killer560.hub.livemap.InteractiveMapFeature", "isSteering")));
        ctx.waitTicks(20);
        Vec3 at = relPos(ctx);
        println(String.format(Locale.US, "nostart-mapwarp %s: map press settled %s, he is at relative (%.2f, %.2f, %.2f)",
                part, settled, at.x, at.y, at.z));
        for (String l : LogTap.since(m)) {
            if (l.contains("[AutoRoutes]") || l.contains("[Path]") || l.contains("start node")) {
                println("nostart-mapwarp " + part + ":   " + l);
            }
        }
        check(settled, part + ": the map's path never finished");
    }

    /** Spends sim breaker charges on the server until {@code left} remain, and returns the count it read after. */
    private static int drainCharges(ClientGameTestContext ctx, int left) {
        AtomicReference<Integer> after = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                String st = "com.killer560.hub.roomsim.SimBreakerState";
                while ((Integer) ModUnderTest.staticCall(st, "charges") > left) {
                    ModUnderTest.staticCall(st, "trySpend");
                }
                after.set((Integer) ModUnderTest.staticCall(st, "charges"));
            });
        });
        ctx.waitFor(mc -> after.get() != null, 100);
        return after.get();
    }

    /**
     * killer560 (2026-10-06): "if it steps on an ar breaker node and it doesn't have enough charges then it should wait
     * to break until it does." A start breaker node of eight picked blocks, six standing and two already air (air needs no
     * charge), with an etherwarp stacked on its tile. The sim breaker is drained to 0 just before he steps on; the sim,
     * like Hypixel, gives back 2 a second. While short: no dig packet, one chat line, the route still running. Once the
     * item shows 6: all six digs on ONE tick, every block broken, and the stacked etherwarp goes after it (the route
     * carries on). Second pass: drained again, a fresh strafe key while it waits stops the route with nothing sent.
     */
    private void caseBreakerWait(ClientGameTestContext ctx) {
        resetRoutes(ctx);
        arena(ctx, false);
        giveHotbar(ctx);
        ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(DX_CONFIG), "setBreakerAuraMultiBreak", true));
        int[][] six = {{12, 0}, {13, 0}, {14, 0}, {12, 1}, {13, 1}, {14, 1}};
        Map<int[], BlockState> put = new HashMap<>();
        JsonArray blocks = new JsonArray();
        for (int[] b : six) {
            put.put(new int[]{b[0], F + b[1], 22}, Blocks.STONE.defaultBlockState());
            blocks.add(b[0] + " " + (F + b[1]) + " 22");
        }
        blocks.add("12 " + (F + 2) + " 22");   // picked, but air: needs no charge
        blocks.add("13 " + (F + 2) + " 22");
        setBlocks(ctx, put);
        JsonObject br = node("DUNGEON_BREAKER", 13, 20, 0f, 0f);
        br.add("blocks", blocks);
        br.addProperty("start", true);
        // West along z 20: 96-ar-breaker's broken rows at z 12 and z 18 regrow 10 s later (SimBreakerState), straight
        // across a warp to the south (the first full run's etherwarp hit the z 18 row and landed 6.3 short).
        JsonObject warp = ew(13, 20, 6, 20, false);
        writeRoute(ctx, List.of(br, warp));
        Supplier<Boolean> allGone = () -> {
            for (int[] b : six) {
                if (!blockAir(ctx, b[0], F + b[1], 22)) {
                    return false;
                }
            }
            return true;
        };

        // ---- pass 1: short of charges, waits, then one burst and the route carries on ----
        tpRel(ctx, 13.5, 17.5, 0f, 0f);
        check(waitFor(ctx, 400, () -> simCharges(ctx) == 20), "the sim breaker never refilled (at " + simCharges(ctx) + ")");
        long m = LogTap.mark();
        startSampling(ctx);
        int drained = drainCharges(ctx, 0);
        // The client's lore is what the node reads, and it follows the server a tick later: step on only once the item
        // in his hand says so (the first run stepped on a tick early, read 20 and the server refused all six).
        int loreDrained = waitFor(ctx, 20, () -> loreCharges(ctx) >= 0 && loreCharges(ctx) <= 1) ? loreCharges(ctx) : -1;
        tpRel(ctx, 13.5, 20.5, 0f, 0f);
        boolean gone = waitFor(ctx, 200, allGone);
        Vec3 landed = waitLanded(ctx, 6, 20, 60);
        ctx.waitTicks(3);
        List<Sample> s = stopSampling(ctx);
        int firstDig = -1;
        int digsOnFirst = 0;
        int total = 0;
        int firstUse = -1;
        for (int i = 0; i < s.size(); i++) {
            int c = (int) s.get(i).out().stream().filter("player_action"::equals).count();
            if (c > 0 && firstDig < 0) {
                firstDig = i;
                digsOnFirst = c;
            }
            if (firstUse < 0 && s.get(i).out().contains("use_item")) {
                firstUse = i;
            }
            total += c;
        }
        int fired = logIndex(m, "Breaker: 8 block(s) queued");
        int waitLine = logIndex(m, "breaker charges (have");
        int readyLine = logIndex(m, "after waiting");
        String ready = readyLine >= 0 ? LogTap.since(m).get(readyLine) : "none";
        println("breaker wait: drained to " + drained + ", " + s.size() + " tick(s) sampled, first dig on sample "
                + firstDig + " (" + digsOnFirst + " dig(s) that tick, " + total + " in all), etherwarp use on sample "
                + firstUse + ", all six gone " + gone + ", landed " + landed);
        println("breaker wait: ready line: " + ready);
        println("breaker wait: client lore read " + loreDrained + " when he stepped on");
        check(drained == 0 && loreDrained >= 0, "could not drain the sim breaker (server " + drained + ", lore "
                + loreDrained + ")");
        check(waitLine >= 0, "no 'waiting for N breaker charges (have M)' chat line");
        check(logHas(m, "6 block(s) standing in reach"), "the wait did not count six standing blocks (two picks are air)");
        // Draining to 0 and stepping on, 6 charges need ~60 ticks at 2 a second: a node that waited sent nothing for
        // most of that. Main sent what it had at once (or stopped at 0) - both fail here.
        check(firstDig >= 30, "the first dig went out on sample " + firstDig + " - it did not wait for charges");
        check(digsOnFirst == 6 && total == 6, digsOnFirst + " dig(s) on the burst tick, " + total
                + " in all - expected all six on one tick and nothing else");
        check(gone, "not all six blocks broke after the burst");
        java.util.regex.Matcher rm = java.util.regex.Pattern.compile("Breaker: (\\d+) charge\\(s\\) after waiting").matcher(ready);
        check(rm.find() && Integer.parseInt(rm.group(1)) >= 6, "the burst went before the item showed 6 charges: " + ready);
        check(firstUse > firstDig && landed != null, "the stacked etherwarp did not follow the burst (use on sample "
                + firstUse + ", landed " + landed + ")");
        check(fired >= 0 && waitLine > fired && readyLine > waitLine, "log order: fired " + fired + ", wait line "
                + waitLine + ", ready line " + readyLine);
        check(!logHas(m, "out of charges"), "the two air picks were reported as blocks not sent for want of charges");

        // ---- pass 2: a fresh movement key while it waits stops the route, nothing sent ----
        resetRoutes(ctx);
        setBlocks(ctx, put);
        writeRoute(ctx, List.of(br));
        tpRel(ctx, 13.5, 17.5, 0f, 0f);
        ctx.waitTicks(5);
        long m2 = LogTap.mark();
        startSampling(ctx);
        drainCharges(ctx, 0);
        waitFor(ctx, 20, () -> loreCharges(ctx) >= 0 && loreCharges(ctx) <= 1);
        tpRel(ctx, 13.5, 20.5, 0f, 0f);
        ctx.waitTicks(10);
        boolean waiting = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(EXECUTOR, "isRunning"));
        ctx.getInput().holdKey(options -> options.keyLeft);
        ctx.waitTicks(3);
        ctx.getInput().releaseKey(options -> options.keyLeft);
        ctx.waitTicks(3);
        boolean stillRunning = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(EXECUTOR, "isRunning"));
        List<Sample> s2 = stopSampling(ctx);
        int digs2 = 0;
        for (Sample x : s2) {
            digs2 += (int) x.out().stream().filter("player_action"::equals).count();
        }
        println("breaker wait, movement key: running while short " + waiting + ", after the key " + stillRunning
                + ", digs " + digs2);
        check(waiting && logHas(m2, "breaker charges (have"), "pass 2: the node was not waiting for charges");
        check(!stillRunning && logHas(m2, "you moved"), "a movement key did not end the breaker's wait");
        check(digs2 == 0, digs2 + " dig(s) went out in pass 2");
    }

    /**
     * Run While Map Open: ON, a start node fires under the open Interactive Map screen; OFF, it does not; ON with a
     * chest's screen open instead, it does not.
     */
    private void caseMapOpen(ClientGameTestContext ctx) {
        resetRoutes(ctx);
        arena(ctx, false);
        giveHotbar(ctx);
        writeRoute(ctx, List.of(ew(6, 6, 14, 6, true)));
        setBlocks(ctx, Map.of(new int[]{4, F, 8}, Blocks.CHEST.defaultBlockState()));
        try {
            for (int pass = 0; pass < 3; pass++) {
                boolean on = pass != 1;
                boolean chest = pass == 2;
                ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setRunWhileMapOpen", on));
                tpRel(ctx, 4.5, 6.5, 0f, 0f);
                ctx.waitTicks(10);
                if (chest) {
                    ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(3));
                    rightClick(ctx, 4, F, 8, Direction.UP);
                } else {
                    ctx.runOnClient(mc -> {
                        try {
                            McCompat.setScreen(mc, (Screen) Class.forName("com.killer560.hub.livemap.InteractiveMapScreen")
                                    .getConstructor(boolean.class).newInstance(true));
                        } catch (ReflectiveOperationException e) {
                            throw new RuntimeException(e);
                        }
                    });
                }
                ctx.waitTicks(5);
                String screen = ctx.computeOnClient(mc -> String.valueOf(McCompat.screen(mc)));
                check(!"null".equals(screen), "pass " + pass + ": no screen opened");
                startSampling(ctx);
                long m = LogTap.mark();
                tpRel(ctx, 6.5, 6.5, -90f, 0f);
                Vec3 landed = waitLanded(ctx, 14, 6, 60);
                List<Sample> s = stopSampling(ctx);
                String still = ctx.computeOnClient(mc -> String.valueOf(McCompat.screen(mc)));
                println("map open, setting " + (on ? "ON" : "OFF") + (chest ? " (chest screen)" : "") + ": "
                        + useTicks(s).size() + " warp(s), landed " + (landed != null) + ", screen " + still);
                if (on && !chest) {
                    check(landed != null && useTicks(s).size() == 1, "ON: the start node did not fire under the map");
                    check(still.contains("InteractiveMapScreen"), "ON: the map screen closed: " + still);
                } else {
                    check(landed == null && useTicks(s).isEmpty(), (chest ? "a chest screen" : "the map with the setting OFF")
                            + " did not hold the route back");
                }
                ctx.runOnClient(mc -> {
                    if (mc.player.containerMenu != mc.player.inventoryMenu) {
                        mc.player.closeContainer();
                    }
                    McCompat.setScreen(mc, null);
                });
                ctx.waitTicks(5);
                ctx.runOnClient(mc -> ModUnderTest.staticCall(EXECUTOR, "stop", new Class<?>[]{String.class},
                        new Object[]{"test"}));
            }
        } finally {
            ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setRunWhileMapOpen", false));
        }
    }

    /**
     * killer560, 2026-10-07: "for auto routes if i have interactive map open it doesnt seem to adjust my angle". Run While
     * Map Open ON (his setting), the Interactive Map's screen open exactly as his open key opens it
     * ({@code new InteractiveMapScreen(true)}) for the whole run, and a route of two etherwarps: (6,6) start to (14,6),
     * then (14,6) to (6,22) - each one a turn from where he looks.
     * <p>
     * Flows: "tp" stands him on the start node facing away (looking up), "go" is his Go + Secret press on the open map
     * ({@code InteractiveMapFeature.onMapSecretPress}, the map's own warp to the start node) from across the arena.
     * Legit mode with no screen is the control. Judged on the server's answer (he stands on (6,22): both warps landed,
     * which a warp sent along his own look cannot do) and, in legit mode, on the CAMERA - the view yaw he sees,
     * {@code getViewYRot} - ending on the last warp's direction, which is what "adjust my angle" means to him.
     */
    private void caseMapOpenAim(ClientGameTestContext ctx) {
        resetRoutes(ctx);
        arena(ctx, false);
        giveHotbar(ctx);
        writeRoute(ctx, List.of(ew(6, 6, 14, 6, true), ew(14, 6, 6, 22, false)));
        float lastRel = relYawTo(14, 6, 6, 22);
        float awayRel = relYawTo(6, 6, 14, 6) + 180f;
        // {legit, map, go}
        boolean[][] passes = {
                {true, false, false}, {true, true, false}, {false, false, false}, {false, true, false},
                {true, false, true}, {true, true, true}, {false, false, true}, {false, true, true}};
        List<String> failures = new ArrayList<>();
        try {
            ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setRunWhileMapOpen", true));
            for (boolean[] p : passes) {
                boolean legit = p[0];
                boolean map = p[1];
                boolean go = p[2];
                String label = (go ? "Go + Secret" : "on the node") + ", " + (legit ? "legit" : "obvious")
                        + (map ? ", map open" : ", no screen (control)");
                if (legit && !legitSelectable()) {
                    println("map-open aim, " + label + ": not run - " + LEGIT_ONLY_TESTING);
                    continue;
                }
                ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setLegitMode", legit));
                // Off any node first: from (20,14) looking up and away, the Go + Secret press has a warp to make.
                tpRel(ctx, go ? 20.5 : 4.5, go ? 14.5 : 6.5, awayRel, -20f);
                ctx.waitTicks(10);
                if (map) {
                    ctx.runOnClient(mc -> {
                        try {
                            McCompat.setScreen(mc, (Screen) Class.forName("com.killer560.hub.livemap.InteractiveMapScreen")
                                    .getConstructor(boolean.class).newInstance(true));
                        } catch (ReflectiveOperationException e) {
                            throw new RuntimeException(e);
                        }
                    });
                    ctx.waitTicks(5);
                    String screen = ctx.computeOnClient(mc -> String.valueOf(McCompat.screen(mc)));
                    check(screen.contains("InteractiveMapScreen"), label + ": the map screen did not open: " + screen);
                }
                long m = LogTap.mark();
                float view0 = ctx.computeOnClient(mc -> mc.player.getViewYRot(1f));
                if (go) {
                    ctx.runOnClient(mc -> {
                        try {
                            java.lang.reflect.Method press = Class.forName("com.killer560.hub.livemap.InteractiveMapFeature")
                                    .getDeclaredMethod("onMapSecretPress", int.class);
                            press.setAccessible(true);
                            press.invoke(null, -1);
                        } catch (ReflectiveOperationException e) {
                            throw new RuntimeException(e);
                        }
                    });
                } else {
                    tpRel(ctx, 6.5, 6.5, awayRel, -20f);
                }
                // Every tick: where he stands and the camera he sees, so a turn that happened and was undone shows.
                Vec3 landed = null;
                float maxSwing = 0f;
                for (int i = 0; i < 300 && landed == null; i++) {
                    float v = ctx.computeOnClient(mc -> mc.player.getViewYRot(1f));
                    maxSwing = Math.max(maxSwing, Math.abs(net.minecraft.util.Mth.wrapDegrees(v - view0)));
                    Vec3 at = relPos(ctx);
                    if (Math.abs(at.x - 6.5) < 1.6 && Math.abs(at.z - 22.5) < 1.6 && Math.abs(at.y - F) < 0.2) {
                        landed = at;
                    } else {
                        ctx.waitTicks(1);
                    }
                }
                ctx.waitTicks(10);
                float view1 = ctx.computeOnClient(mc -> mc.player.getViewYRot(1f));
                float body1 = ctx.computeOnClient(mc -> mc.player.getYRot());
                float lastYaw = ctx.computeOnClient(mc -> {
                    Object frame = ModUnderTest.staticCall(FRAME, "current");
                    return (Float) ModUnderTest.staticCall(COORDS, "toRealYaw", new Class<?>[]{frame.getClass(),
                            float.class}, new Object[]{frame, lastRel});
                });
                float viewOff = Math.abs(net.minecraft.util.Mth.wrapDegrees(view1 - lastYaw));
                String still = ctx.computeOnClient(mc -> String.valueOf(McCompat.screen(mc)));
                boolean started = logHas(m, "Started \"");
                int acted = 0;
                for (String l : LogTap.since(m)) {
                    if (l.contains("ETHERWARP acted")) {
                        acted++;
                    }
                }
                println("map-open aim, " + label + ": started " + started + ", " + acted + " etherwarp(s) acted, landed on"
                        + " (6,22) " + (landed != null) + ", view yaw " + view0 + " -> " + view1 + " (body " + body1
                        + "), " + viewOff + " off the last warp's direction, camera swung up to " + maxSwing + ", screen " + still);
                if (landed == null) {
                    failures.add(label + ": the route did not land him within a block of (6,22) - he is at " + relPos(ctx) + ", "
                            + acted + " etherwarp(s) acted");
                }
                if (legit && viewOff > 10f) {
                    failures.add(label + ": the camera did not turn - view yaw " + view1 + " is " + viewOff
                            + " degrees off the last warp's direction");
                }
                if (map && !still.contains("InteractiveMapScreen")) {
                    failures.add(label + ": the map screen closed during the run: " + still);
                }
                ctx.runOnClient(mc -> {
                    McCompat.setScreen(mc, null);
                    ModUnderTest.staticCall(EXECUTOR, "stop", new Class<?>[]{String.class}, new Object[]{"test"});
                });
                ctx.waitTicks(10);
            }
        } finally {
            ctx.runOnClient(mc -> {
                ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setRunWhileMapOpen", false);
                ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setLegitMode", false);
            });
        }
        check(failures.isEmpty(), String.join("; ", failures));
    }
    /** Two etherwarps aimed at each other bounce until /ar stop, sneak held throughout; then the missing-block wait. */
    private void casePingPong(ClientGameTestContext ctx) {
        resetRoutes(ctx);
        arena(ctx, false);
        giveHotbar(ctx);
        writeRoute(ctx, List.of(ew(6, 14, 14, 14, true), ew(14, 14, 6, 14, false)));
        tpRel(ctx, 4.5, 14.5, 0f, 0f);
        ctx.waitTicks(10);
        startSampling(ctx);
        tpRel(ctx, 6.5, 14.5, -90f, 0f);
        ctx.waitTicks(80);
        cmd(ctx, "/ar stop");
        ctx.waitTicks(5);
        List<Sample> s = stopSampling(ctx);
        List<Sample> uses = useTicks(s);
        println("ping-pong: " + uses.size() + " warp(s) in 80 ticks");
        check(uses.size() >= 6, "only " + uses.size() + " warps in 80 ticks of ping-pong");
        for (Sample x : s) {
            if (!uses.isEmpty() && x.tick() >= uses.get(0).tick() && x.tick() <= uses.get(uses.size() - 1).tick()) {
                check(x.shift(), "shift released on tick " + x.tick() + " between two etherwarps");
            }
        }
        check(!ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(EXECUTOR, "isRunning")),
                "/ar stop did not stop the ping-pong");
        int warpsAfter = useTicks(s.subList(Math.max(0, s.size() - 5), s.size())).size();
        check(warpsAfter == 0, "a warp went out after /ar stop");

        // ---- the landing block is gone: no warp until it is back ----
        resetRoutes(ctx);
        writeRoute(ctx, List.of(ew(6, 14, 14, 14, true)));
        tpRel(ctx, 4.5, 14.5, 0f, 0f);
        ctx.waitTicks(10);
        setBlocks(ctx, Map.of(new int[]{14, F - 1, 14}, Blocks.AIR.defaultBlockState()));
        long mark = LogTap.mark();
        startSampling(ctx);
        tpRel(ctx, 6.5, 14.5, -90f, 0f);
        ctx.waitTicks(60);
        List<Sample> waiting = stopSampling(ctx);
        check(useTicks(waiting).isEmpty(), "an etherwarp went out at a missing block");
        check(logHas(mark, "is not there") && logHas(mark, "Waiting for the block"), "no wait message for the missing block");
        check(waiting.size() > 10 && waiting.get(waiting.size() - 1).shift(), "sneak was not held while waiting");
        startSampling(ctx);
        setBlocks(ctx, Map.of(new int[]{14, F - 1, 14}, Blocks.STONE.defaultBlockState()));
        Vec3 l = waitLanded(ctx, 14, 14, 60);
        check(l != null, "the warp did not happen once the block was back (at " + relPos(ctx) + ")");
        check(useTicks(stopSampling(ctx)).size() == 1, "not exactly one warp once the block came back");
    }

    /**
     * A long unconditional etherwarp chain (killer560, 2026-10-06: "in theory I should be able to teleport 20 times a
     * second if there is no waiting"): twelve etherwarps round the arena, each landing in the next node, the last on no
     * node. Measures ticks per warp, and that every use went out only after the previous landing's teleport accept (the
     * server had moved him before the next warp aimed). One warp per tick is the target at the sim's zero ping.
     */
    private void caseChain(ClientGameTestContext ctx) {
        resetRoutes(ctx);
        arena(ctx, false);
        giveHotbar(ctx);
        int[][] pts = ArChainMeasure.CHAIN;
        int[] end = ArChainMeasure.END;
        List<JsonObject> n = new ArrayList<>();
        for (int i = 0; i < pts.length; i++) {
            int[] to = i + 1 < pts.length ? pts[i + 1] : end;
            n.add(ew(pts[i][0], pts[i][1], to[0], to[1], i == 0));
        }
        writeRoute(ctx, n);
        List<String> results = new ArrayList<>();
        try {
            // Etherwarps Per Second (killer560, 2026-10-06): a pace of 20/rate ticks between warps, never ahead of the
            // landing. 20 is the sim's round trip (two ticks: its server answers a tick later than the dedicated one).
            // 3/s is the fractional pace: 6.67 ticks on average, the fraction carried (7, 7, 6).
            for (int rate : new int[]{20, 10, 4, 3}) {
                ctx.runOnClient(mc -> ModUnderTest.call(ModUnderTest.config(AR_CONFIG), "setEtherwarpsPerSecond",
                        new Class<?>[]{int.class}, new Object[]{rate}));
                stopRoute(ctx);
                tpRel(ctx, 4.5, 6.5, 0f, 0f);
                ctx.waitTicks(10);
                long mark = LogTap.mark();
                startSampling(ctx);
                tpRel(ctx, 6.5, 6.5, -90f, 0f);
                Vec3 landed = waitLanded(ctx, end[0], end[1], 400);
                ctx.waitTicks(10);
                stopSampling(ctx);
                ArChainMeasure.Chain chain = ArChainMeasure.chain(0);
                println("chain at " + rate + "/s: " + chain.describe());
                results.add(String.format(Locale.ROOT, "%d/s: %.2f", rate, chain.ticksPerWarp()));
                int acted = 0;
                for (String l : LogTap.since(mark)) {
                    acted += l.contains("ETHERWARP acted") ? 1 : 0;
                }
                check(landed != null, rate + "/s: the chain did not reach its end at " + end[0] + "," + end[1] + " (at "
                        + relPos(ctx) + ")");
                check(acted == pts.length && chain.warps() == pts.length, rate + "/s: expected " + pts.length
                        + " etherwarps, the log says " + acted + " acted and the trace has " + chain.warps() + " use(s)");
                check(chain.usesBeforeLanding() == 0, rate + "/s: " + chain.usesBeforeLanding() + " warp(s) went out "
                        + "before the previous landing was accepted");
                double want = Math.max(2.0, 20.0 / rate);
                if (rate == 20) {
                    check(chain.firesOnLanding(), "20/s: a warp waited after its landing arrived: ticks from each landing "
                            + "to the next use " + chain.landingToUse());
                }
                check(Math.abs(chain.ticksPerWarp() - want) <= 0.3, String.format(Locale.ROOT, "%d/s: %.2f ticks per "
                        + "warp (gaps %s), expected %.2f", rate, chain.ticksPerWarp(), chain.gaps(), want));
            }
        } finally {
            ctx.runOnClient(mc -> ModUnderTest.call(ModUnderTest.config(AR_CONFIG), "setEtherwarpsPerSecond",
                    new Class<?>[]{int.class}, new Object[]{20}));
            println("chain ticks per warp: " + results);
        }
    }

    /**
     * Stacked nodes of the same type keep the order they were added (killer560, 2026-10-06: "if i make a boom a crypt
     * then a boom, it should do the first boom then the second then the crypt"): #1 boom, #2 crypt, #3 boom on one tile
     * fire #1, #3, #2 - with the start flag on #1, and on #3 (which used to put #3 first).
     */
    private void caseStackOrder(ClientGameTestContext ctx) {
        resetRoutes(ctx);
        arena(ctx, false);
        giveHotbar(ctx);
        giveSlot(ctx, 4, "HYPERION");
        setArEnum(ctx, "setCryptWeapon", "CryptWeapon", "HYPERION");
        ctx.runOnClient(mc -> ModUnderTest.call(ModUnderTest.config(AR_CONFIG), "setCryptAttackTicks",
                new Class<?>[]{int.class}, new Object[]{10}));
        try {
            for (int startOn : new int[]{1, 3}) {
                BlockState cracked = Blocks.CRACKED_STONE_BRICKS.defaultBlockState();
                setBlocks(ctx, Map.of(new int[]{12, F, 14}, cracked, new int[]{12, F + 1, 14}, cracked,
                        new int[]{17, F, 14}, cracked, new int[]{17, F + 1, 14}, cracked));
                JsonObject b1 = node("BOOM", 14, 14, 90f, 0f);
                JsonObject cr = cryptNode(14, 14, 0f, 0f, false, 0);
                JsonObject b3 = node("BOOM", 14, 14, -90f, 0f);
                (startOn == 1 ? b1 : b3).addProperty("start", true);
                writeRoute(ctx, List.of(b1, cr, b3));
                ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(3));
                tpRel(ctx, 14.5, 10.5, 0f, 0f);
                ctx.waitTicks(10);
                long m = LogTap.mark();
                tpRel(ctx, 14.5, 14.5, 0f, 0f);
                boolean done = waitFor(ctx, 160, () -> logHas(m, "complete") || logHas(m, "Stopped"));
                int i1 = logIndex(m, "Node #1 BOOM acted");
                int i3 = logIndex(m, "Node #3 BOOM acted");
                int i2 = logIndex(m, "Node #2 CRYPT acted");
                String order = "";
                for (String l : LogTap.since(m)) {
                    if (l.contains("firing in order")) {
                        order = l.substring(l.indexOf("firing in order"));
                    }
                }
                println("stack order, start on #" + startOn + ": " + order + " (acted at log lines #1 boom " + i1
                        + ", #3 boom " + i3 + ", #2 crypt " + i2 + ")");
                check(done && logHas(m, "complete"), "start on #" + startOn + ": the stack did not complete");
                check(i1 >= 0 && i3 > i1 && i2 > i3, "start on #" + startOn + ": fired #1 boom@" + i1 + ", #3 boom@" + i3
                        + ", #2 crypt@" + i2 + " - expected #1, #3, #2");
                check(blockAir(ctx, 12, F + 1, 14) && blockAir(ctx, 17, F + 1, 14), "start on #" + startOn
                        + ": a boom broke nothing");
                stopRoute(ctx);
                tpRel(ctx, 14.5, 10.5, 0f, 0f);
                ctx.waitTicks(5);
            }
        } finally {
            ctx.runOnClient(mc -> ModUnderTest.call(ModUnderTest.config(AR_CONFIG), "setCryptAttackTicks",
                    new Class<?>[]{int.class}, new Object[]{100}));
        }
    }

    // ============================================================================================ breaker charges

    private static int simCharges(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall("com.killer560.hub.roomsim.SimBreakerState",
                "charges"));
    }

    private static int simSecrets(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(SIM_SCORE, "secretsFound"));
    }

    /** The "Charges: N/M" line of the breaker in hotbar slot 2 as the CLIENT has it (what the mod reads), or -1. */
    private static int loreCharges(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            var lore = mc.player.getInventory().getItem(2).get(net.minecraft.core.component.DataComponents.LORE);
            if (lore == null) {
                return -1;
            }
            for (var line : lore.lines()) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("Charges: (\\d+)/(\\d+)").matcher(line.getString());
                if (m.find()) {
                    return Integer.parseInt(m.group(1));
                }
            }
            return -1;
        });
    }

    /**
     * killer560 (2026-10-06): "on gaining any secret you gain 2 breaker charges back", then "the regaining charges should
     * be an optional slider or setting" - the Wither Essence Shop perk Echoes of the Lost, a Sim Settings level 0-5.
     * In the sim: at the cap a secret changes nothing; after eight real breaker digs a chest secret gives exactly the
     * configured number back (default 5), the server says so and the item's lore on the client follows; at setting 0 a
     * secret gives nothing; at setting 3, exactly 3; and the total never passes 20.
     */
    private void caseCharges(ClientGameTestContext ctx) {
        resetRoutes(ctx);
        arena(ctx, false);
        giveHotbar(ctx);
        BlockState chest = Blocks.CHEST.defaultBlockState();
        setBlocks(ctx, Map.of(new int[]{6, F, 12}, chest, new int[]{6, F, 15}, chest, new int[]{4, F, 12}, chest,
                new int[]{4, F, 15}, chest));
        tpRel(ctx, 4.5, 8.5, 90f, 0f);
        // The per-secret restore is the Dungeon Sim setting "Echoes of the Lost" (Wither Essence Shop, levels 1-5 = 1-5
        // charges, 0 = off); the test config is fresh, so what it reads here is the shipped default - the maxed perk.
        int original = secretSetting(ctx);
        check(original == 5, "the default Echoes of the Lost level is " + original + ", expected 5 (the wiki's maximum)");
        try {
            caseChargesBody(ctx);
        } finally {
            setSecretSetting(ctx, original);
        }
    }

    private static int secretSetting(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall("com.killer560.hub.roomsim.SimBreakerState", "secretCharges"));
    }

    private static void setSecretSetting(ClientGameTestContext ctx, int level) {
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimBreakerState", "setSecretCharges", new Class<?>[]{int.class},
                new Object[]{level}));
    }

    private void caseChargesBody(ClientGameTestContext ctx) {
        boolean full = waitFor(ctx, 400, () -> simCharges(ctx) == 20);
        check(full, "the sim breaker never refilled to 20 (at " + simCharges(ctx) + ")");

        // ---- at the cap: the secret counts, the charges stay 20 ----
        int s0 = simSecrets(ctx);
        long mCap = LogTap.mark();
        rightClick(ctx, 6, F, 12, Direction.UP);
        ctx.runOnClient(mc -> mc.player.closeContainer());
        ctx.waitTicks(3);
        int cCap = simCharges(ctx);
        int s1 = simSecrets(ctx);
        println("charges at the cap: secrets " + s0 + " -> " + s1 + ", charges " + cCap);
        check(s1 == s0 + 1, "the first chest was not counted as a secret (" + s0 + " -> " + s1 + ")");
        check(cCap == 20, "a secret at the cap left " + cCap + " charges");
        check(!logHas(mCap, "secret found, charges"), "a secret at the cap reported a charge change");

        // ---- spend eight with real digs ----
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(2));
        ctx.waitTicks(3);
        int[][] wall = new int[8][];
        for (int i = 0; i < 8; i++) {
            wall[i] = new int[]{3, F + (i / 4), 5 + (i % 4)};
        }
        startSampling(ctx);
        for (int[] b : wall) {
            ctx.runOnClient(mc -> {
                BlockPos pos = real(ModUnderTest.staticCall(FRAME, "current"), b[0], b[1], b[2]);
                mc.gameMode.startDestroyBlock(pos, Direction.EAST);
                mc.gameMode.stopDestroyBlock();
            });
            ctx.waitTicks(1);
        }
        ctx.waitTicks(2);
        List<Sample> digs = stopSampling(ctx);
        int digPackets = 0;
        for (Sample x : digs) {
            digPackets += (int) x.out().stream().filter("player_action"::equals).count();
        }
        int broken = 0;
        for (int[] b : wall) {
            if (blockAir(ctx, b[0], b[1], b[2])) {
                broken++;
            }
        }
        int c1 = simCharges(ctx);
        println("spent: " + digPackets + " dig packet(s), " + broken + " of 8 wall blocks broken, charges 20 -> " + c1);
        check(broken == 8, "only " + broken + " of the 8 blocks broke");
        check(c1 <= 14, "eight breaks left " + c1 + " charges - the breaker did not spend");

        // ---- a secret at the default level (5): +5 ----
        // Within reach of the last three chests: the sim counts a chest when the SERVER accepts the click (since
        // sim-world, 2026-10-11), and (6,15) / (4,15) are 7 blocks from (4.5, 8.5) - a click Hypixel refuses too.
        tpRel(ctx, 5.5, 13.5, 90f, 0f);
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(3));
        ctx.waitTicks(1);
        int cBefore = simCharges(ctx);
        long m = LogTap.mark();
        rightClick(ctx, 6, F, 15, Direction.UP);
        ctx.runOnClient(mc -> mc.player.closeContainer());
        ctx.waitTicks(2);
        int c2 = simCharges(ctx);
        int[] ft = restoreFromLog(m);
        int from = ft[0];
        int to = ft[1];
        int target = c2;
        waitFor(ctx, 40, () -> loreCharges(ctx) >= target);
        int lore = loreCharges(ctx);
        println("secret at the default: server " + from + " -> " + to + ", charges read " + cBefore + " -> " + c2
                + ", client lore " + lore + ", secrets " + simSecrets(ctx));
        check(from >= 0, "no charge restore on the second chest");
        check(to == from + 5, "a secret at the default level restored " + (to - from) + " charges, not 5");
        check(c2 >= cBefore + 5, "charges went " + cBefore + " -> " + c2 + " over a secret");
        check(lore >= c2, "the client's breaker lore says " + lore + " after the server had " + c2);

        // ---- setting 0 (perk off): the secret counts, no charge comes back ----
        setSecretSetting(ctx, 0);
        int sOff0 = simSecrets(ctx);
        int cOff0 = simCharges(ctx);
        long mOff = LogTap.mark();
        rightClick(ctx, 4, F, 12, Direction.UP);
        ctx.runOnClient(mc -> mc.player.closeContainer());
        ctx.waitTicks(2);
        int sOff1 = simSecrets(ctx);
        int cOff1 = simCharges(ctx);
        println("setting 0: secrets " + sOff0 + " -> " + sOff1 + ", charges " + cOff0 + " -> " + cOff1);
        check(sOff1 == sOff0 + 1, "the chest at setting 0 was not counted as a secret (" + sOff0 + " -> " + sOff1 + ")");
        check(!logHas(mOff, "secret found, charges"), "a secret at setting 0 restored charges");
        check(cOff1 <= cOff0 + 1, "setting 0: charges went " + cOff0 + " -> " + cOff1 + " over a secret (2 ticks of regen is 1)");

        // ---- setting 3: exactly 3 ----
        // Make room under the cap first (the earlier steps left it at 18), so the restore is not clamped.
        ctx.runOnClient(mc -> {
            for (int i = 0; i < 8; i++) {
                ModUnderTest.staticCall("com.killer560.hub.roomsim.SimBreakerState", "trySpend");
            }
        });
        setSecretSetting(ctx, 3);
        long m3 = LogTap.mark();
        rightClick(ctx, 4, F, 15, Direction.UP);
        ctx.runOnClient(mc -> mc.player.closeContainer());
        ctx.waitTicks(2);
        int[] f3 = restoreFromLog(m3);
        println("setting 3: server " + f3[0] + " -> " + f3[1]);
        check(f3[0] >= 0, "no charge restore at setting 3");
        check(f3[1] == f3[0] + 3, "a secret at setting 3 restored " + (f3[1] - f3[0]) + " charges, not 3");

        // ---- the cap: at level 5 and 18 charges a secret leaves 20, not 23 ----
        setSecretSetting(ctx, 5);
        waitFor(ctx, 400, () -> simCharges(ctx) == 20);
        ctx.runOnClient(mc -> {
            ModUnderTest.staticCall("com.killer560.hub.roomsim.SimBreakerState", "trySpend");
            ModUnderTest.staticCall("com.killer560.hub.roomsim.SimBreakerState", "trySpend");
            ModUnderTest.staticCall("com.killer560.hub.roomsim.SimBreakerState", "secretFound");
        });
        int capped = simCharges(ctx);
        println("cap: 18 charges plus a level-5 secret read " + capped);
        check(capped == 20, "a level-5 secret at 18 charges left " + capped + ", the cap is 20");
    }

    /** The last "secret found, charges A -> B" line since {@code mark}, as {A, B}; {-1, -1} when there is none. */
    private static int[] restoreFromLog(long mark) {
        int from = -1;
        int to = -1;
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("secret found, charges (\\d+) -> (\\d+)");
        for (String l : LogTap.since(mark)) {
            java.util.regex.Matcher mm = p.matcher(l);
            if (mm.find()) {
                from = Integer.parseInt(mm.group(1));
                to = Integer.parseInt(mm.group(2));
            }
        }
        return new int[]{from, to};
    }

    // ============================================================================================ 398: node proc

    /*
     * killer560, 2026-10-07 ~01:17, his own sim on Map Logger, his Museum route: "on occasion it wouldn't proc nodes
     * properly, some just wouldn't proc at all or only partway". Three things in his log, one case each:
     *  - 96-ar-398-offnode: #10 armed with him 0.6 off its centre and 0.27 up (mid-jump), fired the RECORDED look, landed a
     *    block wide of #11 and the route stood still. The look must be worked out from where he really is.
     *  - 96-ar-398-startawait: after the Interactive Map took him to the start node, #1's await (the chest beside it) sat
     *    at 0/1 until he skipped it, three runs of four. Secret Aura and Auto Routes are let go on the same tick after a
     *    map warp, and the aura's click landed one tick before the route's first window opened.
     *  - 96-ar-398-regrow: the room rebuilt (his single-room load) 6 s after a run, and on the next run the wall #5's
     *    breaker had just opened was put back by the PREVIOUS run's 10-second regrow: #7's etherwarp refused four times.
     */

    private static List<JsonObject> museumNodes() {
        List<JsonObject> nodes = new ArrayList<>();
        for (JsonElement e : JsonParser.parseString(MUSEUM_ROUTE).getAsJsonArray()) {
            nodes.add(e.getAsJsonObject());
        }
        return nodes;
    }

    private static final String TELEPORT_UTILS = "com.killer560.hub.livemap.autoclear.TeleportUtils";
    private static final String CHEAT_CFG = "com.killer560.hub.cheatutils.CheatUtilsConfig";

    /** The block an etherwarp from relative feet (x, y, z) along the relative look would stop at (a sneaking eye, a
     *  61-block client clip on colliders), relative; null when the ray hits nothing. */
    private static BlockPos warpHit(ClientGameTestContext ctx, double x, double y, double z, float relYaw, float pitch) {
        return ctx.computeOnClient(mc -> {
            Object frame = ModUnderTest.staticCall(FRAME, "current");
            Vec3 feet = (Vec3) ModUnderTest.staticCall(COORDS, "toReal", new Class<?>[]{frame.getClass(), double.class,
                    double.class, double.class}, new Object[]{frame, x, y, z});
            float yaw = (Float) ModUnderTest.staticCall(COORDS, "toRealYaw", new Class<?>[]{frame.getClass(), float.class},
                    new Object[]{frame, relYaw});
            Vec3 look = (Vec3) ModUnderTest.staticCall(TELEPORT_UTILS, "getLook", new Class<?>[]{float.class, float.class},
                    new Object[]{yaw, pitch});
            Vec3 eye = feet.add(0, 1.27, 0);
            var hit = mc.level.clip(new net.minecraft.world.level.ClipContext(eye, eye.add(look.scale(61.0)),
                    net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, mc.player));
            if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
                return null;
            }
            return (BlockPos) ModUnderTest.staticCall(COORDS, "toRelativeBlock", new Class<?>[]{frame.getClass(),
                    BlockPos.class}, new Object[]{frame, ((BlockHitResult) hit).getBlockPos()});
        });
    }

    /** Whether the mod's own etherwarp aim ({@code TeleportUtils.getEtherwarpDirection}, the Interactive Map's) finds a
     *  ray from a sneaking eye over relative feet (x, y, z) that lands on relative block {@code b}. */
    private static boolean modRay(ClientGameTestContext ctx, double x, double y, double z, BlockPos b) {
        return ctx.computeOnClient(mc -> {
            Object frame = ModUnderTest.staticCall(FRAME, "current");
            Vec3 feet = (Vec3) ModUnderTest.staticCall(COORDS, "toReal", new Class<?>[]{frame.getClass(), double.class,
                    double.class, double.class}, new Object[]{frame, x, y, z});
            double range = (Double) ModUnderTest.staticCall(CLEAR, "hopRange") + 1.0;
            return ModUnderTest.staticCall(TELEPORT_UTILS, "getEtherwarpDirection", new Class<?>[]{Vec3.class,
                    BlockPos.class, double.class}, new Object[]{feet.add(0, 1.27, 0), real(frame, b.getX(), b.getY(), b.getZ()),
                    range}) != null;
        });
    }

    /** Whether a sneaking eye over relative feet (x, y, z) has a clear line to a point on the top of relative block
     *  {@code b} (centre or 0.3 in from a corner), by a client clip on colliders. */
    private static boolean visible(ClientGameTestContext ctx, double x, double y, double z, BlockPos b) {
        return ctx.computeOnClient(mc -> {
            Object frame = ModUnderTest.staticCall(FRAME, "current");
            Vec3 feet = (Vec3) ModUnderTest.staticCall(COORDS, "toReal", new Class<?>[]{frame.getClass(), double.class,
                    double.class, double.class}, new Object[]{frame, x, y, z});
            BlockPos real = real(frame, b.getX(), b.getY(), b.getZ());
            Vec3 eye = feet.add(0, 1.27, 0);
            double[][] pts = {{0.5, 0.5}, {0.2, 0.2}, {0.8, 0.2}, {0.2, 0.8}, {0.8, 0.8}};
            for (double[] p : pts) {
                Vec3 to = new Vec3(real.getX() + p[0], real.getY() + 0.97, real.getZ() + p[1]);
                var hit = mc.level.clip(new net.minecraft.world.level.ClipContext(eye, to,
                        net.minecraft.world.level.ClipContext.Block.COLLIDER,
                        net.minecraft.world.level.ClipContext.Fluid.NONE, mc.player));
                if (hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                        && real.equals(((BlockHitResult) hit).getBlockPos())) {
                    return true;
                }
            }
            return false;
        });
    }

    /** Waits for one of the texts in the log since {@code mark}; the index of the one seen, or -1. */
    private static int waitLog(ClientGameTestContext ctx, long mark, int ticks, String... texts) {
        for (int i = 0; i <= ticks; i++) {
            for (int k = 0; k < texts.length; k++) {
                if (logHas(mark, texts[k])) {
                    return k;
                }
            }
            ctx.waitTicks(1);
        }
        return -1;
    }

    private static long serverTime(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> mc.getSingleplayerServer().overworld().getGameTime());
    }

    /**
     * 96-ar-398-offnode. His Museum #10 (28.5, 69, 10.5) etherwarps onto #11 (31.5, 69.05, 5.5). He is put inside #10's
     * ring but OFF its centre, at a spot from which the recorded look - checked here with a client clip first - does
     * NOT stop on #11's block, so firing it from there misses (the premise, asserted). Twice: standing still there,
     * and dropped in from 0.6 above it, still falling when it fires (his 01:16:43, y 0.27 over the node). Each time the
     * warp must land on #11.
     */
    private void caseOffNode(ClientGameTestContext ctx) {
        String saved = room;
        resetRoutes(ctx);
        room = "Museum";
        try {
            buildRoom(ctx, 0);
            giveHotbar(ctx);
            giveSlot(ctx, 4, "BAT_WAND");
            ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setStartFromStartNodeOnly", false));
            List<JsonObject> nodes = museumNodes();
            writeRoute(ctx, nodes);
            JsonObject n10 = nodes.get(9);
            float yaw = n10.get("yaw").getAsFloat();
            float pitch = n10.get("pitch").getAsFloat();
            BlockPos target = new BlockPos(31, 68, 5);
            BlockPos fromCentre = warpHit(ctx, 28.5, 69.0, 10.5, yaw, pitch);
            println("offnode: the recorded look from #10's center stops at " + fromCentre + " (#11 stands on " + target + ")");
            check(target.equals(fromCentre), "premise: #10's recorded look does not even land on #11 from its own center ("
                    + fromCentre + ")");
            // Spots in #10's ring from which the recorded look MISSES #11's block, nearest his own 01:16:43 offset
            // (0.47, 0.41) first: one from which a ray to the block exists (the mod's etherwarp aim finds one - the
            // re-aim), and one from which none does (a pillar hides it - he must step back onto the node first).
            double[] withRay = null;
            double[] noRay = null;
            List<double[]> spots = new ArrayList<>();
            double[] steps = {0.75, -0.75, 0.6, -0.6, 0.45, -0.45, 0.3, -0.3, 0.15, -0.15, 0.0};
            for (double dx : steps) {
                for (double dz : steps) {
                    if (dx != 0.0 || dz != 0.0) {
                        spots.add(new double[]{dx, dz});
                    }
                }
            }
            spots.sort(java.util.Comparator.comparingDouble(s -> Math.hypot(Math.abs(s[0]) - 0.47, Math.abs(s[1]) - 0.41)));
            for (double[] s : spots) {
                BlockPos hit = warpHit(ctx, 28.5 + s[0], 69.0, 10.5 + s[1], yaw, pitch);
                if (target.equals(hit)) {
                    continue;
                }
                boolean ray = modRay(ctx, 28.5 + s[0], 69.0, 10.5 + s[1], target);
                if (ray && withRay == null) {
                    withRay = s;
                    println(String.format(Locale.US, "offnode: from (%+.2f, %+.2f) the recorded look stops at %s; a ray to #11's"
                            + " block exists", s[0], s[1], hit));
                } else if (!ray && noRay == null) {
                    noRay = s;
                    println(String.format(Locale.US, "offnode: from (%+.2f, %+.2f) the recorded look stops at %s; NO ray reaches"
                            + " #11's block", s[0], s[1], hit));
                }
            }
            check(withRay != null || noRay != null, "premise: no spot in #10's ring where the recorded look misses #11");
            List<double[]> runs = new ArrayList<>();   // {dx, dz, up}
            if (withRay != null) {
                runs.add(new double[]{withRay[0], withRay[1], 0.0});
                runs.add(new double[]{withRay[0], withRay[1], 0.6});
            }
            if (noRay != null) {
                runs.add(new double[]{noRay[0], noRay[1], 0.0});
                runs.add(new double[]{noRay[0], noRay[1], 0.6});
            }
            List<String> failures = new ArrayList<>();
            for (double[] run : runs) {
                double up = run[2];
                String label = String.format(Locale.US, "%s at (%+.2f, %+.2f)%s", up == 0.0 ? "standing" : "falling in",
                        run[0], run[1], (withRay != null && run[0] == withRay[0] && run[1] == withRay[1]) ? " (ray)" : " (no ray)");
                ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setEnabled", false));
                stopRoute(ctx);
                ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(3));
                long m = LogTap.mark();
                double fx = 28.5 + run[0];
                double fz = 10.5 + run[1];
                // Off every node first with Auto Routes on, so the last run's latch on #10 is let go.
                ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setEnabled", true));
                tpRelY(ctx, 7.67, 69.0, 47.46, 0f, 0f);
                ctx.waitTicks(10);
                ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setEnabled", false));
                if (up == 0.0) {
                    tpRelY(ctx, fx, 69.0, fz, 0f, 0f);
                    ctx.waitTicks(5);
                    ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setEnabled", true));
                } else {
                    tpRelY(ctx, fx, 69.0 + up, fz, 0f, 0f);
                    ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setEnabled", true));
                }
                // #10's use, then the landing (a round trip in the sim); booms on #11 do not move him.
                int seen = waitLog(ctx, m, 160, "Node #10 ETHERWARP acted");
                ctx.waitTicks(8);
                Vec3 at = relPos(ctx);
                String begins = null;
                for (String l : LogTap.since(m)) {
                    if (l.contains("Node #10 ETHERWARP") || l.contains("Etherwarp") || l.contains("re-aimed")
                            || l.contains("[Sim] etherwarp")) {
                        println("offnode " + label + " log: " + l);
                        if (begins == null && l.contains("Node #10 ETHERWARP begins")) {
                            begins = l;
                        }
                    }
                }
                boolean onEleven = Math.abs(at.x - 31.5) < 0.35 && Math.abs(at.z - 5.5) < 0.35 && Math.abs(at.y - 69.0) < 0.2;
                println(String.format(Locale.US, "offnode %s: #10 fired %s, he ended at (%.2f, %.2f, %.2f), on #11 %s",
                        label, begins == null ? "NEVER" : "yes", at.x, at.y, at.z, onEleven));
                if (begins == null) {
                    failures.add(label + ": #10 never fired");
                } else if (!onEleven) {
                    failures.add(String.format(Locale.US, "%s: the warp from #10 left him at (%.2f, %.2f, %.2f), not on #11"
                            + " (31.5, 69, 5.5)%s", label, at.x, at.y, at.z, seen < 0 ? " (nothing logged)" : ""));
                }
            }
            check(failures.isEmpty(), String.join("; ", failures));
        } finally {
            try {
                ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setEnabled", false));
                stopRoute(ctx);
            } catch (Throwable ignored) {
                // the rebuild below matters more
            }
            ctx.runOnClient(mc -> {
                ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setEnabled", true);
                ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setStartFromStartNodeOnly", true);
            });
            resetRoutes(ctx);
            room = saved;
            buildRoom(ctx, 0);
        }
    }

    /**
     * 96-ar-398-startawait. His settings: Secret Aura on (chests, range 4.5, pause while sneaking), Auto Close Chest on.
     * Museum's #1 awaits one secret, the chest beside it. He stands where he stood at 01:16:30 and presses Go + Secret
     * (the Interactive Map's warp to the start node). The aura must open that chest (the premise: its done-set says so),
     * and #1's await must count it: "await 1/1 secrets met", not a wait that only his left click ends.
     */
    private void caseStartAwait(ClientGameTestContext ctx) {
        String saved = room;
        resetRoutes(ctx);
        room = "Museum";
        BlockPos chest = null;
        try {
            for (int attempt = 0; attempt < 6 && chest == null; attempt++) {
                buildRoom(ctx, 0);
                // #1's chest: the chest within the aura's reach of #1's eye. A TRAPPED chest is the mimic (its click
                // is not the secret, its death is), so the floor is rebuilt until it is a plain one.
                BlockPos found = ctx.computeOnClient(mc -> {
                    Object frame = ModUnderTest.staticCall(FRAME, "current");
                    Vec3 feet = (Vec3) ModUnderTest.staticCall(COORDS, "toReal", new Class<?>[]{frame.getClass(),
                            double.class, double.class, double.class}, new Object[]{frame, 31.5, 69.0, 56.5});
                    Vec3 eye = feet.add(0, 1.62, 0);
                    BlockPos best = null;
                    for (BlockPos p : BlockPos.betweenClosed(BlockPos.containing(eye).offset(-5, -3, -5),
                            BlockPos.containing(eye).offset(5, 3, 5))) {
                        var st = mc.level.getBlockState(p);
                        if ((st.is(Blocks.CHEST) || st.is(Blocks.TRAPPED_CHEST)) && Vec3.atCenterOf(p).distanceTo(eye) <= 4.6) {
                            best = st.is(Blocks.CHEST) ? p.immutable() : new BlockPos(0, -1000, 0);
                        }
                    }
                    return best;
                });
                println("startawait build " + (attempt + 1) + ": chest beside #1: " + found);
                if (found != null && found.getY() != -1000) {
                    chest = found;
                }
            }
            check(chest != null, "premise: no plain chest within reach of #1 in six builds of Museum");
            final BlockPos chestPos = chest;
            giveHotbar(ctx);
            ctx.runOnClient(mc -> {
                Object ar = ModUnderTest.config(AR_CONFIG);
                ModUnderTest.set(ar, "setEnabled", true);
                ModUnderTest.set(ar, "setStartFromStartNodeOnly", false);   // his setting; the map warp guards anyway
                Object cfg = ModUnderTest.config(CHEAT_CFG);
                ModUnderTest.set(cfg, "setSecretAuraEnabled", true);
                ModUnderTest.set(cfg, "setAuraChests", true);
                ModUnderTest.set(cfg, "setAuraLevers", true);
                ModUnderTest.set(cfg, "setAuraEssence", true);
                ModUnderTest.set(cfg, "setAuraPauseWhileSneaking", true);
                ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autoclosechest.AutoCloseChestConfig"),
                        "setEnabled", true);
            });
            writeRoute(ctx, museumNodes());
            // Where he stood before his 01:16:30 press: real (-120.46, -128.33) in his frame (clay -74,-136, rotation 90).
            Vec3 his = ctx.computeOnClient(mc -> {
                try {
                    Object hisFrame = Class.forName(FRAME).getConstructor(String.class, int.class, int.class, int.class)
                            .newInstance("Museum", -74, -136, 90);
                    return (Vec3) ModUnderTest.staticCall(COORDS, "toRelative", new Class<?>[]{hisFrame.getClass(),
                            Vec3.class}, new Object[]{hisFrame, new Vec3(-120.46, -52.0, -128.33)});
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }
            });
            println(String.format(Locale.US, "startawait: his 01:16:30 spot is relative (%.2f, %.2f)", his.x, his.z));
            tpRelY(ctx, his.x, 69.0, his.z, 0f, 0f);
            ctx.waitTicks(20);
            // The full floor graph, as his was ("floor graph warm" 9 s before his press).
            ctx.waitTicks(100);
            long m = LogTap.mark();
            ctx.runOnClient(mc -> {
                try {
                    java.lang.reflect.Method press = Class.forName("com.killer560.hub.livemap.InteractiveMapFeature")
                            .getDeclaredMethod("onMapSecretPress", int.class);
                    press.setAccessible(true);
                    press.invoke(null, -1);
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }
            });
            // Every tick from the press: what Secret Aura's own gates read, printed when it changes.
            String lastState = "";
            int started = -1;
            int met = -1;
            for (int i = 0; i < 380 && met < 0; i++) {
                String st = auraState(ctx, chestPos);
                if (!st.equals(lastState)) {
                    println("startawait tick " + i + ": " + st);
                    lastState = st;
                }
                if (started < 0 && logHas(m, "Started \"Museum\"")) {
                    started = i;
                }
                if (logHas(m, "Node #1 ETHERWARP: await 1/1 secrets met")) {
                    met = i;
                }
                if (started >= 0 && i - started > 80) {
                    break;
                }
                ctx.waitTicks(1);
            }
            boolean clicked = logHasAll(m, "chest at " + chestPos.toShortString(), "clicked by you")
                    || ctx.computeOnClient(mc -> auraTouched(chestPos));
            for (String l : LogTap.since(m)) {
                if (l.contains("Warping") || l.contains("Found path") || l.contains("Arming") || l.contains("Started")
                        || l.contains("Node #1 ") || l.contains("await") || l.contains("Stopped")) {
                    println("startawait log: " + l);
                }
            }
            println("startawait: route started " + (started >= 0) + ", Secret Aura opened the chest at " + chestPos.toShortString()
                    + " " + clicked + ", #1's await met " + (met >= 0));
            check(started >= 0, "the Go + Secret press never started the route on #1");
            check(clicked, "premise: Secret Aura never opened #1's chest at " + chestPos.toShortString());
            check(met >= 0, "#1's await never counted the chest Secret Aura opened beside it (0/1 - he had to skip it)");
            // Secret Aura's FIRST click on the chest comes the tick the map warp lets go, one tick before the route starts.
            // In his game that click opened the chest and there was nothing left to click: 0/1 for good. In the sim the
            // first click does not always open it, and the aura's retry a second later (RETRY_AFTER_MS) is counted - which
            // is luck, not the fix. So the await must be met by the first click: within half a second of the start.
            check(met - started <= 10, "#1's await was met only " + (met - started) + " ticks after the route started - by "
                    + "Secret Aura's retry, not its first click on the chest (which came before the start and was not counted)");
        } finally {
            ctx.runOnClient(mc -> {
                ModUnderTest.turnOff(CHEAT_CFG, "setSecretAuraEnabled");
                ModUnderTest.turnOff("com.killer560.hub.autoclosechest.AutoCloseChestConfig", "setEnabled");
                ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setStartFromStartNodeOnly", true);
            });
            try {
                stopRoute(ctx);
            } catch (Throwable ignored) {
                // the rebuild below matters more
            }
            resetRoutes(ctx);
            room = saved;
            buildRoom(ctx, 0);
        }
    }

    /**
     * 96-ar-405-crypt-mapopen (killer560, 2026-10-07: "the crypt node is not working while I have the interactive map
     * menu open"). His Museum route from #10, so #11 BOOM, #12 BOOM, #13 CRYPT (await 2, Spirit Sceptre), #14 fire as
     * one stack, twice: with no screen (the control) and with the Interactive Map's screen open for the whole run, Run
     * While Map Open on (his setting). The crypt node holds the use key, and vanilla turns a held key into a use only in
     * {@code handleKeybinds}, which {@code Minecraft.tick} skips while any screen is open (javap 26.1.2 and 26.2). Both
     * passes must get the two kills, the map pass within one held-use period (4 ticks) of the control, with uses every
     * 4 ticks, and the map must still be open at the end.
     */
    private void caseCryptMapOpen(ClientGameTestContext ctx) {
        String saved = room;
        resetRoutes(ctx);
        room = "Museum";
        List<String> failures = new ArrayList<>();
        try {
            ctx.runOnClient(mc -> {
                Object ar = ModUnderTest.config(AR_CONFIG);
                ModUnderTest.set(ar, "setStartFromStartNodeOnly", false);
                ModUnderTest.set(ar, "setRunWhileMapOpen", true);
                ModUnderTest.call(ar, "setCryptAttackTicks", new Class<?>[]{int.class}, new Object[]{100});
            });
            setArEnum(ctx, "setCryptWeapon", "CryptWeapon", "SPIRIT_SCEPTRE");
            List<JsonObject> nodes = museumNodes();
            // #10 is where the run starts; a start node, so the map-arrival guard of the cases before cannot hold it.
            nodes.get(9).addProperty("start", true);
            int[] held = new int[2];
            for (int pass = 0; pass < 2; pass++) {
                boolean map = pass == 1;
                String label = map ? "map open" : "no screen (control)";
                buildRoom(ctx, 0);
                giveHotbar(ctx);
                giveSlot(ctx, 4, "BAT_WAND");
                writeRoute(ctx, nodes);
                ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(3));
                if (map) {
                    ctx.runOnClient(mc -> {
                        try {
                            McCompat.setScreen(mc, (Screen) Class.forName("com.killer560.hub.livemap.InteractiveMapScreen")
                                    .getConstructor(boolean.class).newInstance(true));
                        } catch (ReflectiveOperationException e) {
                            throw new RuntimeException(e);
                        }
                    });
                    ctx.waitTicks(5);
                    String screen = ctx.computeOnClient(mc -> String.valueOf(McCompat.screen(mc)));
                    check(screen.contains("InteractiveMapScreen"), "the map screen did not open: " + screen);
                }
                int c0 = cryptsBlown(ctx);
                long m = LogTap.mark();
                startSampling(ctx);
                tpRelY(ctx, 28.5, 69.0, 10.5, -150.342102f, 12.861895f);
                boolean acted = waitFor(ctx, 200, () -> logHas(m, "Node #13 CRYPT acted") || logHas(m, "Stopped"));
                int cryptTick = ctx.computeOnClient(mc -> PacketTrace.tickCount());
                // Since crypt-aim-1015 a Spirit Sceptre crypt node also ends the tick it sees every crypt mob it was
                // tracking dead (2 seen, 2 dead meets await 2), without the tab list's count a second later.
                boolean ended = waitFor(ctx, 160, () -> logHas(m, "CRYPT: 2 kill(s)") || logHas(m, "up, moving on")
                        || logHas(m, "skipped the kill wait") || logHas(m, "Stopped")
                        || logHas(m, "CRYPT: every crypt mob dead or given its"));
                int endTick = ctx.computeOnClient(mc -> PacketTrace.tickCount());
                ctx.waitTicks(2);
                List<Sample> s = stopSampling(ctx);
                ArChainMeasure.Held uses = ArChainMeasure.held(Math.max(0, cryptTick - 2));
                String still = ctx.computeOnClient(mc -> String.valueOf(McCompat.screen(mc)));
                int c1 = cryptsBlown(ctx);
                String line = null;
                for (String l : LogTap.since(m)) {
                    if (l.contains("CRYPT") || l.contains("BOOM acted") || l.contains("Stopped") || l.contains("Crypt Undead")) {
                        println("crypt-mapopen " + label + " log: " + l);
                    }
                    if (l.contains("CRYPT: ") && l.contains("after holding use")) {
                        line = l;
                    }
                }
                int ticks = -1;
                if (line != null) {
                    java.util.regex.Matcher mt = java.util.regex.Pattern.compile("holding use (\\d+) tick").matcher(line);
                    if (mt.find()) {
                        ticks = Integer.parseInt(mt.group(1));
                    }
                }
                held[pass] = ticks;
                boolean killed = logHas(m, "CRYPT: 2 kill(s) after") || logHas(m, "(2 seen, 2 dead,");
                println(String.format(Locale.US, "crypt-mapopen %s: crypt acted %s, ended %s, 2 kills %s after holding use %d "
                                + "tick(s), crypt undead dead %d -> %d, uses from the crypt node on: %s, screen at the end %s",
                        label, acted, ended, killed, ticks, c0, c1, uses.describe(), still));
                if (!acted || !logHas(m, "holding use")) {
                    failures.add(label + ": the crypt node never held use");
                } else if (!killed) {
                    failures.add(label + ": the crypt node got no 2 kills (" + (line == null ? "no kill line" : line) + ")");
                }
                // The first use tick is #10's etherwarp, landed a few ticks before the stack; the crypt's held uses follow.
                // Only the crypt node's own uses: up to the tick it ended (#14 goes after it).
                List<Integer> cryptTicks = uses.ticks().stream().filter(t -> t <= endTick).toList();
                List<Integer> cryptGaps = new ArrayList<>();
                for (int k = 2; k < cryptTicks.size(); k++) {
                    cryptGaps.add(cryptTicks.get(k) - cryptTicks.get(k - 1));
                }
                if (cryptGaps.stream().anyMatch(g -> g != 4)) {
                    failures.add(label + ": the crypt node's uses are not 4 ticks apart: " + uses.describe());
                }
                if (map && !still.contains("InteractiveMapScreen")) {
                    failures.add(label + ": the map screen closed during the run: " + still);
                }
                ctx.runOnClient(mc -> McCompat.setScreen(mc, null));
                stopRoute(ctx);
                ctx.waitTicks(5);
            }
            println("crypt-mapopen: held use " + held[0] + " tick(s) with no screen, " + held[1] + " with the map open");
            if (held[0] >= 0 && held[1] >= 0 && held[1] > held[0] + 4) {
                failures.add("the map-open crypt node took " + held[1] + " ticks to its kills against " + held[0]
                        + " with no screen");
            }
        } finally {
            try {
                stopRoute(ctx);
            } catch (Throwable ignored) {
                // the rebuild below matters more
            }
            setArEnum(ctx, "setCryptWeapon", "CryptWeapon", "HYPERION");
            ctx.runOnClient(mc -> {
                Object ar = ModUnderTest.config(AR_CONFIG);
                ModUnderTest.set(ar, "setStartFromStartNodeOnly", true);
                ModUnderTest.set(ar, "setRunWhileMapOpen", false);
            });
            resetRoutes(ctx);
            room = saved;
            buildRoom(ctx, 0);
        }
        check(failures.isEmpty(), String.join("; ", failures));
    }

    /**
     * 96-ar-405-startwarp (killer560, 2026-10-07: "I had 1 test out of about 5 where the interactive map was off when it
     * tried to take me to the start node"). His Museum with his route, Kill Mimic on the Spirit Sceptre, Secret Aura and
     * Auto Close Chest on, and the mimic in the trapped chest beside #1 where his was (real -134, -51, -105 in his frame).
     * Ten times from where he stood: Go + Secret on the map (the warp to the start node), and each time he must land on
     * #1's exact block, #1 must arm and act, and he must land in #2 - the route may not stall on #1. Between tries the
     * route is stopped after #2 has begun, as his third run was stopped mid-route, so the next try starts with an old
     * warp's landing still in the executor. Every server-side move of him after the arming is printed with the tick.
     */
    private void caseStartWarp(ClientGameTestContext ctx) {
        String saved = room;
        resetRoutes(ctx);
        room = "Museum";
        List<String> failures = new ArrayList<>();
        int tries = Integer.getInteger("testkit.startwarpTries", 10);
        try {
            buildRoom(ctx, 0);
            giveHotbar(ctx);
            giveSlot(ctx, 2, "BAT_WAND");   // his slot 3 ("Spirit Sceptre from slot 3")
            setArEnum(ctx, "setKillMimic", "KillMimic", "SPIRIT_SCEPTRE");
            // His sim speed (killer560smod-sim-speed.txt: 600; "[Sim] Speed set to 600" in his log).
            simSpeed(ctx, 600);
            ctx.runOnClient(mc -> {
                Object ar = ModUnderTest.config(AR_CONFIG);
                ModUnderTest.set(ar, "setRunWhileMapOpen", true);   // his setting
                ModUnderTest.set(ar, "setStartFromStartNodeOnly", false);   // his setting; the map warp guards anyway
                Object cfg = ModUnderTest.config(CHEAT_CFG);
                ModUnderTest.set(cfg, "setSecretAuraEnabled", true);
                ModUnderTest.set(cfg, "setAuraChests", true);
                ModUnderTest.set(cfg, "setAuraLevers", true);
                ModUnderTest.set(cfg, "setAuraEssence", true);
                ModUnderTest.set(cfg, "setAuraPauseWhileSneaking", true);
                ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autoclosechest.AutoCloseChestConfig"),
                        "setEnabled", true);
            });
            writeRoute(ctx, museumNodes());
            // His frame (clay -74,-136, rotation 90): where he pressed from, and his mimic chest.
            Vec3[] his = ctx.computeOnClient(mc -> {
                try {
                    Object hisFrame = Class.forName(FRAME).getConstructor(String.class, int.class, int.class, int.class)
                            .newInstance("Museum", -74, -136, 90);
                    Class<?>[] sig = {hisFrame.getClass(), Vec3.class};
                    return new Vec3[]{
                            (Vec3) ModUnderTest.staticCall(COORDS, "toRelative", sig, new Object[]{hisFrame,
                                    new Vec3(-120.5, -52.0, -133.5)}),
                            (Vec3) ModUnderTest.staticCall(COORDS, "toRelative", sig, new Object[]{hisFrame,
                                    new Vec3(-133.5, -51.0, -104.5)})};
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }
            });
            int mx = (int) Math.floor(his[1].x);
            int mz = (int) Math.floor(his[1].z);
            println(String.format(Locale.US, "startwarp: his press spot relative (%.2f, %.2f), his mimic chest relative (%d, 70, %d)",
                    his[0].x, his[0].z, mx, mz));
            BlockPos nodeBlock = realNow(ctx, 31, 68, 56);
            tpRelY(ctx, his[0].x, 69.0, his[0].z, 0f, 0f);
            ctx.waitTicks(120);   // the full floor graph, as his was
            int good = 0;
            for (int t = 0; t < 2 * tries; t++) {
                // The first half with no screen, the second with the Interactive Map open from before the press to the
                // end, as he plays (Run While Map Open on; his Go + Secret is pressed on the open map).
                boolean map = t >= tries;
                // Auto Routes off while he is put back: stopped on #2 he would re-arm there at once.
                ctx.runOnClient(mc -> {
                    McCompat.setScreen(mc, null);
                    ModUnderTest.staticCall(EXECUTOR, "stop", new Class<?>[]{String.class}, new Object[]{"test"});
                    ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setEnabled", false);
                });
                ctx.waitTicks(3);
                // The mimic back in its chest, and Secret Aura's memory of the room cleared, as a rebuild would.
                setBlocks(ctx, Map.of(new int[]{mx, 70, mz}, Blocks.TRAPPED_CHEST.defaultBlockState()));
                BlockPos chest = realNow(ctx, mx, 70, mz);
                ctx.runOnClient(mc -> {
                    try {
                        Class<?> c = Class.forName(SIM_MIMIC);
                        var f = c.getDeclaredField("mimic");
                        f.setAccessible(true);
                        f.set(null, chest);
                        var found = c.getDeclaredField("found");
                        found.setAccessible(true);
                        found.set(null, false);
                        Class<?> aura = Class.forName("com.killer560.hub.cheatutils.SecretAuraFeature");
                        for (String fn : new String[]{"firstSeenMs", "attempts", "done"}) {
                            java.lang.reflect.Field af = aura.getDeclaredField(fn);
                            af.setAccessible(true);
                            Object o = af.get(null);
                            if (o instanceof java.util.Map<?, ?> mm) {
                                mm.clear();
                            } else if (o instanceof java.util.Collection<?> cc) {
                                cc.clear();
                            }
                        }
                    } catch (ReflectiveOperationException e) {
                        throw new RuntimeException(e);
                    }
                });
                tpRelY(ctx, his[0].x, 69.0, his[0].z, 0f, 0f);
                ctx.waitTicks(20);
                ctx.runOnClient(mc -> {
                    ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setEnabled", true);
                    mc.player.getInventory().setSelectedSlot(3);
                });
                if (map) {
                    ctx.runOnClient(mc -> {
                        try {
                            McCompat.setScreen(mc, (Screen) Class.forName("com.killer560.hub.livemap.InteractiveMapScreen")
                                    .getConstructor(boolean.class).newInstance(true));
                        } catch (ReflectiveOperationException e) {
                            throw new RuntimeException(e);
                        }
                    });
                }
                ctx.waitTicks(5);
                long m = LogTap.mark();
                ctx.runOnClient(mc -> {
                    try {
                        java.lang.reflect.Method press = Class.forName("com.killer560.hub.livemap.InteractiveMapFeature")
                                .getDeclaredMethod("onMapSecretPress", int.class);
                        press.setAccessible(true);
                        press.invoke(null, -1);
                    } catch (ReflectiveOperationException e) {
                        throw new RuntimeException(e);
                    }
                });
                int armed = -1;
                long mArm = Long.MAX_VALUE;
                Vec3 armedAt = null;
                int acted = -1;
                int landed2 = -1;
                Vec3 lastServer = null;
                List<String> moves = new ArrayList<>();
                for (int i = 0; i < 400; i++) {
                    Vec3[] pos = ctx.computeOnClient(mc -> {
                        var sp = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
                        return new Vec3[]{mc.player.position(), sp == null ? null : sp.position()};
                    });
                    if (armed < 0 && logHas(m, "armed node #1")) {
                        armed = i;
                        armedAt = pos[0];
                        mArm = LogTap.mark();
                    }
                    if (armed >= 0 && pos[1] != null && lastServer != null && pos[1].distanceTo(lastServer) >= 0.3
                            && acted < 0) {
                        moves.add(String.format(Locale.US, "+%d server (%.2f, %.2f, %.2f) -> (%.2f, %.2f, %.2f) client "
                                        + "(%.2f, %.2f, %.2f)", i - armed, lastServer.x, lastServer.y, lastServer.z, pos[1].x,
                                pos[1].y, pos[1].z, pos[0].x, pos[0].y, pos[0].z));
                    }
                    lastServer = pos[1];
                    if (acted < 0 && logHas(m, "Node #1 ETHERWARP acted")) {
                        acted = i;
                    }
                    if (logHas(m, "Landed in node #2")) {
                        landed2 = i;
                        break;
                    }
                    if ((armed >= 0 && logHas(mArm, "Stopped")) || (armed >= 0 && i - armed > 200)
                            || (armed < 0 && i > 300)) {
                        break;
                    }
                    ctx.waitTicks(1);
                }
                String still = ctx.computeOnClient(mc -> String.valueOf(McCompat.screen(mc)));
                boolean exact = armedAt != null && BlockPos.containing(armedAt.x, armedAt.y - 0.5, armedAt.z).equals(nodeBlock);
                boolean letGo = logHas(m, "carrying on from here");
                List<String> lines = new ArrayList<>();
                for (String l : LogTap.since(m)) {
                    if (l.contains("[Path] running") || l.contains("Arming: armed") || l.contains("Node #1 ")
                            || l.contains("Kill Mimic") || l.contains("Correction") || l.contains("Mimic of")
                            || l.contains("no ray from") || l.contains("Landed in node #2") || l.contains("Stopped")
                            || l.contains("await secret")) {
                        lines.add(strip(l));
                    }
                }
                boolean screenOk = !map || still.contains("InteractiveMapScreen");
                boolean ok = armed >= 0 && exact && acted >= 0 && landed2 >= 0 && screenOk;
                if (ok) {
                    good++;
                }
                String label = "try " + (t + 1) + (map ? " (map open)" : " (no screen)");
                println(String.format(Locale.US, "startwarp %s: armed %s at %s (on #1's block %s), #1 acted %s, landed in #2 "
                                + "%s, let go after a correction %s, screen %s -> %s", label, armed >= 0, armedAt == null ? "-"
                                : String.format(Locale.US, "(%.2f, %.2f, %.2f)", armedAt.x, armedAt.y, armedAt.z), exact,
                        acted >= 0 ? "+" + (acted - armed) : "never", landed2 >= 0 ? "+" + (landed2 - armed) : "never", letGo,
                        still, ok ? "OK" : "BAD"));
                for (String mv : moves) {
                    println("startwarp " + label + " move: " + mv);
                }
                if (!ok || !moves.isEmpty()) {
                    for (String l : lines) {
                        println("startwarp " + label + " log: " + l);
                    }
                }
                if (!ok) {
                    failures.add(label + ": " + (armed < 0 ? "#1 never armed" : !exact ? "armed off #1's block at "
                            + armedAt : acted < 0 ? "#1 never acted (the route stalled on the start node)"
                            : landed2 < 0 ? "never landed in #2" : "the map screen closed: " + still));
                }
            }
            println("startwarp: " + good + " of " + 2 * tries + " tries landed on #1, armed it and went on to #2");

            // ---- his shift, made deterministic ----
            // In his log a server packet moved him 1.00 block (+z) while #1 waited on its await, and with a warp of an
            // earlier, stopped run still in the executor (his #6, 47.4 blocks off) the route let #1 go and stood there.
            // The sim does not make that move by itself (the tries above), so the server makes it here: #1's await is
            // a 600 ms DELAY, and 3 ticks after the arming he is moved one block, as his was. #1 must still warp him to
            // #2 (from where he is, or after stepping back onto its spot), and nothing may let it go.
            ctx.runOnClient(mc -> ModUnderTest.turnOff(CHEAT_CFG, "setSecretAuraEnabled"));
            List<JsonObject> delayed = museumNodes();
            delayed.get(0).addProperty("await", "DELAY");
            delayed.get(0).addProperty("amount", 600);
            writeRoute(ctx, delayed);
            int shiftedGood = 0;
            int shifted = Math.max(4, tries / 2);
            for (int t = 0; t < 2 * shifted; t++) {
                boolean map = t >= shifted;
                String label = "shifted try " + (t + 1) + (map ? " (map open)" : " (no screen)");
                ctx.runOnClient(mc -> {
                    McCompat.setScreen(mc, null);
                    ModUnderTest.staticCall(EXECUTOR, "stop", new Class<?>[]{String.class}, new Object[]{"test"});
                    ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setEnabled", false);
                });
                ctx.waitTicks(3);
                tpRelY(ctx, his[0].x, 69.0, his[0].z, 0f, 0f);
                ctx.waitTicks(20);
                ctx.runOnClient(mc -> {
                    ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setEnabled", true);
                    mc.player.getInventory().setSelectedSlot(3);
                });
                if (map) {
                    ctx.runOnClient(mc -> {
                        try {
                            McCompat.setScreen(mc, (Screen) Class.forName("com.killer560.hub.livemap.InteractiveMapScreen")
                                    .getConstructor(boolean.class).newInstance(true));
                        } catch (ReflectiveOperationException e) {
                            throw new RuntimeException(e);
                        }
                    });
                }
                ctx.waitTicks(5);
                long m = LogTap.mark();
                ctx.runOnClient(mc -> {
                    try {
                        java.lang.reflect.Method press = Class.forName("com.killer560.hub.livemap.InteractiveMapFeature")
                                .getDeclaredMethod("onMapSecretPress", int.class);
                        press.setAccessible(true);
                        press.invoke(null, -1);
                    } catch (ReflectiveOperationException e) {
                        throw new RuntimeException(e);
                    }
                });
                boolean armedOk = waitFor(ctx, 300, () -> logHas(m, "armed node #1"));
                ctx.waitTicks(3);
                boolean waiting = logHas(m, "Node #1 ETHERWARP begins") && !logHas(m, "Node #1 ETHERWARP acted");
                Vec3 before = ctx.computeOnClient(mc -> mc.player.position());
                serverRun(ctx, (server, sp) -> sp.teleportTo(server.overworld(), before.x, before.y, before.z + 1.0,
                        java.util.Set.<net.minecraft.world.entity.Relative>of(), sp.getYRot(), sp.getXRot(), false));
                boolean landed = waitFor(ctx, 120, () -> logHas(m, "Landed in node #2") || logHas(m, "carrying on from here")
                        || logHas(m, "Stopped"));
                boolean ok = armedOk && waiting && logHas(m, "Landed in node #2") && !logHas(m, "carrying on from here");
                if (ok) {
                    shiftedGood++;
                }
                println(String.format(Locale.US, "startwarp %s: armed %s, #1 still waiting when moved %s, moved from (%.2f, %.2f, "
                                + "%.2f), landed in #2 %s, let go after a correction %s -> %s", label, armedOk, waiting,
                        before.x, before.y, before.z, logHas(m, "Landed in node #2"), logHas(m, "carrying on from here"),
                        ok ? "OK" : "BAD"));
                if (!ok) {
                    for (String l : LogTap.since(m)) {
                        if (l.contains("Node #1 ") || l.contains("Correction") || l.contains("no ray from")
                                || l.contains("Landed in") || l.contains("Stopped") || l.contains("Arming: armed")) {
                            println("startwarp " + label + " log: " + strip(l));
                        }
                    }
                    failures.add(label + ": " + (!armedOk ? "#1 never armed" : !waiting ? "setup: #1 was not waiting on its "
                            + "await when he was moved" : logHas(m, "carrying on from here") ? "a move during #1's await let #1 "
                            + "go and the route stood on the start node" : "#1 never got him to #2 after the move"));
                }
            }
            println("startwarp: " + shiftedGood + " of " + 2 * shifted + " shifted tries went on to #2");
        } finally {
            ctx.runOnClient(mc -> {
                McCompat.setScreen(mc, null);
                ModUnderTest.turnOff(CHEAT_CFG, "setSecretAuraEnabled");
                ModUnderTest.turnOff("com.killer560.hub.autoclosechest.AutoCloseChestConfig", "setEnabled");
                Object ar = ModUnderTest.config(AR_CONFIG);
                ModUnderTest.set(ar, "setEnabled", true);
                ModUnderTest.set(ar, "setStartFromStartNodeOnly", true);
                ModUnderTest.set(ar, "setRunWhileMapOpen", false);
            });
            setArEnum(ctx, "setKillMimic", "KillMimic", "OFF");
            simSpeed(ctx, 100);
            try {
                stopRoute(ctx);
            } catch (Throwable ignored) {
                // the rebuild below matters more
            }
            resetRoutes(ctx);
            room = saved;
            buildRoom(ctx, 0);
        }
        check(failures.isEmpty(), String.join("; ", failures));
    }

    /** The sim's /speed (SimSpeed.set: the server player's movement speed, Hypixel units; 100 is a walk). */
    private static void simSpeed(ClientGameTestContext ctx, int speed) {
        ctx.runOnClient(mc -> {
            try {
                java.lang.reflect.Method set = Class.forName("com.killer560.hub.roomsim.SimSpeed")
                        .getDeclaredMethod("set", Minecraft.class, int.class);
                set.setAccessible(true);
                set.invoke(null, mc, speed);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        });
        ctx.waitTicks(3);
    }

    /** Secret Aura's view of one secret: its own gate inputs and what it remembers about the block. */
    private static String auraState(ClientGameTestContext ctx, BlockPos pos) {
        return ctx.computeOnClient(mc -> {
            try {
                Class<?> aura = Class.forName("com.killer560.hub.cheatutils.SecretAuraFeature");
                long key = pos.asLong();
                java.lang.reflect.Field fs = aura.getDeclaredField("firstSeenMs");
                java.lang.reflect.Field at = aura.getDeclaredField("attempts");
                java.lang.reflect.Field dn = aura.getDeclaredField("done");
                fs.setAccessible(true);
                at.setAccessible(true);
                dn.setAccessible(true);
                boolean busy = (Boolean) ModUnderTest.staticCall(CLEAR, "isBusy");
                boolean gateScreen = (Boolean) ModUnderTest.staticCall("com.killer560.hub.util.ActionGate",
                        "containerScreenOpen", new Class<?>[]{Minecraft.class}, new Object[]{mc});
                double d = Math.sqrt((Double) ModUnderTest.staticCall("com.killer560.hub.util.BlockHits", "boxDistanceSq",
                        new Class<?>[]{Vec3.class, BlockPos.class}, new Object[]{mc.player.getEyePosition(), pos}));
                return String.format(Locale.US, "mapBusy=%s shift=%s sentShift=%s containerScreen=%s screen=%s box=%.2f "
                                + "firstSeen=%s attempt=%s done=%s block=%s", busy, mc.player.isShiftKeyDown(),
                        mc.player.getLastSentInput().shift(), gateScreen, McCompat.screen(mc) != null, d,
                        ((java.util.Map<?, ?>) fs.get(null)).containsKey(key), ((java.util.Map<?, ?>) at.get(null)).get(key),
                        ((java.util.Set<?>) dn.get(null)).contains(key), mc.level.getBlockState(pos).getBlock().getName().getString());
            } catch (ReflectiveOperationException e) {
                return "unreadable: " + e;
            }
        });
    }

    private static boolean auraTouched(BlockPos pos) {
        try {
            Class<?> aura = Class.forName("com.killer560.hub.cheatutils.SecretAuraFeature");
            java.lang.reflect.Field at = aura.getDeclaredField("attempts");
            at.setAccessible(true);
            return ((java.util.Map<?, ?>) at.get(null)).containsKey(pos.asLong())
                    || (Boolean) ModUnderTest.staticCall("com.killer560.hub.cheatutils.SecretAuraFeature", "isDone",
                    new Class<?>[]{BlockPos.class}, new Object[]{pos});
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    /** {@code SimBuilder.buildSingleRoom} - his way of loading Museum (the menu's room picker, "Built Museum"). */
    private static void buildSingleMuseum(ClientGameTestContext ctx) {
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> ModUnderTest.staticCall(BUILDER, "buildSingleRoom",
                new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, "Museum"})));
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitFor(mc -> McCompat.screen(mc) == null, 1200);
        ctx.waitFor(mc -> {
            Object f = ModUnderTest.staticCall(FRAME, "current");
            return f != null && String.valueOf(f).contains("Museum") && mc.player.onGround();
        }, 1200);
    }

    /**
     * 96-ar-398-regrow. Museum loaded his way (single room). A route of his #5 breaker (only the blocks #6's and #7's
     * warps fly through, so the charges never decide the timing), #6 and #7. Run 1 breaks them and lands on #7's
     * landing (the control). The room is rebuilt. Run 2 breaks the same blocks again some seconds later, and #7 waits
     * (a DELAY await) until well past ten seconds after RUN 1's break - the moment run 1's regrow is due - before it
     * warps through the hole. It must land: run 1's regrow belongs to a room that has since been rebuilt.
     */
    private void caseRegrow(ClientGameTestContext ctx) {
        String saved = room;
        resetRoutes(ctx);
        room = "Museum";
        try {
            buildSingleMuseum(ctx);
            giveHotbar(ctx);
            ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setStartFromStartNodeOnly", false));
            List<JsonObject> all = museumNodes();
            JsonObject n5 = all.get(4);
            JsonObject n6 = all.get(5);
            JsonObject n7 = all.get(6);
            // The breaker's blocks that #6's or #7's warp ray passes through, sampled every 0.05 from a sneaking eye.
            java.util.Set<String> listed = new java.util.LinkedHashSet<>();
            for (JsonElement e : n5.getAsJsonArray("blocks")) {
                listed.add(e.getAsString());
            }
            java.util.Set<String> crossed = new java.util.LinkedHashSet<>();
            for (JsonObject w : List.of(n6, n7)) {
                double x0 = w.get("x").getAsDouble();
                double y0 = w.get("y").getAsDouble() + 1.27;
                double z0 = w.get("z").getAsDouble();
                double yr = Math.toRadians(w.get("yaw").getAsDouble());
                double pr = Math.toRadians(w.get("pitch").getAsDouble());
                double dx = -Math.sin(yr) * Math.cos(pr);
                double dy = -Math.sin(pr);
                double dz = Math.cos(yr) * Math.cos(pr);
                String[] land = w.get("landing").getAsString().split(" ");
                double lx = Double.parseDouble(land[0]) - x0;
                double ly = Double.parseDouble(land[1]) - y0;
                double lz = Double.parseDouble(land[2]) - z0;
                double len = Math.sqrt(lx * lx + ly * ly + lz * lz) + 2.0;
                for (double t = 0; t <= len; t += 0.05) {
                    String b = (int) Math.floor(x0 + dx * t) + " " + (int) Math.floor(y0 + dy * t) + " "
                            + (int) Math.floor(z0 + dz * t);
                    if (listed.contains(b)) {
                        crossed.add(b);
                    }
                }
            }
            println("regrow: #5's blocks on #6's and #7's rays: " + crossed);
            check(!crossed.isEmpty(), "premise: no breaker block lies on #6's or #7's ray");
            JsonObject br = n5.deepCopy();
            JsonArray blocks = new JsonArray();
            crossed.forEach(blocks::add);
            br.add("blocks", blocks);
            br.addProperty("start", true);
            JsonObject w7 = n7.deepCopy();
            w7.remove("awaitEnabled");
            w7.remove("await");
            w7.remove("amount");
            writeRoute(ctx, List.of(br, n6.deepCopy(), w7));

            // ---- run 1: the control ----
            ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(3));
            long m1 = LogTap.mark();
            tpRelY(ctx, 30.5, 82.0, 15.5, 0f, 0f);
            int broke1 = waitLog(ctx, m1, 100, "Breaker: all ");
            long t1 = serverTime(ctx);
            int done1 = waitLog(ctx, m1, 200, "Route \"Museum\" complete", "Stopped");
            Vec3 at1 = relPos(ctx);
            boolean landed1 = Math.abs(at1.x - 34.5) < 0.35 && Math.abs(at1.z - 12.5) < 0.35;
            println(String.format(Locale.US, "regrow run 1: broke %s at server tick %d, route %s, he ended at (%.2f, %.2f, %.2f)"
                    + " - on #7's landing %s", broke1 >= 0, t1, done1 == 0 ? "complete" : done1 == 1 ? "stopped" : "still going",
                    at1.x, at1.y, at1.z, landed1));
            check(broke1 >= 0 && landed1, "control: run 1 did not break #5's blocks and land on #7's landing");
            ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setEnabled", false));
            stopRoute(ctx);

            // ---- the rebuild, his way ----
            buildSingleMuseum(ctx);
            giveHotbar(ctx);
            long rebuilt = serverTime(ctx) - t1;
            println("regrow: rebuilt " + rebuilt + " server tick(s) after run 1's break");
            check(rebuilt < 170, "setup: the rebuild took until " + rebuilt + " ticks after run 1's break - too late to break"
                    + " again before run 1's regrow is due at 200");
            // Run 2 breaks at ~t1+breakAt; #7 then waits until ~45 ticks past t1+200.
            long breakAt = Math.max(rebuilt + 10, 120);
            long waitMs = (200 + 45 - breakAt) * 50;
            w7.addProperty("awaitEnabled", true);
            w7.addProperty("await", "DELAY");
            w7.addProperty("amount", (int) waitMs);
            writeRoute(ctx, List.of(br, n6.deepCopy(), w7));
            while (serverTime(ctx) - t1 < breakAt) {
                ctx.waitTicks(1);
            }

            // ---- run 2 ----
            ctx.runOnClient(mc -> {
                mc.player.getInventory().setSelectedSlot(3);
                ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setEnabled", true);
            });
            long m2 = LogTap.mark();
            tpRelY(ctx, 30.5, 82.0, 15.5, 0f, 0f);
            int broke2 = waitLog(ctx, m2, 100, "Breaker: all ");
            long t2 = serverTime(ctx);
            int fired7 = waitLog(ctx, m2, 300, "Node #3 ETHERWARP acted");
            long t7 = serverTime(ctx);
            int end2 = waitLog(ctx, m2, 80, "Route \"Museum\" complete", "Etherwarp: no landing", "Stopped");
            Vec3 at2 = relPos(ctx);
            boolean landed2 = Math.abs(at2.x - 34.5) < 0.35 && Math.abs(at2.z - 12.5) < 0.35;
            boolean refused = logHas(m2, "[Sim] etherwarp refused");
            for (String l : LogTap.since(m2)) {
                if (l.contains("Breaker") || l.contains("Node #3") || l.contains("[Sim] etherwarp") || l.contains("Etherwarp:")
                        || l.contains("Correction")) {
                    println("regrow run 2 log: " + l);
                }
            }
            println(String.format(Locale.US, "regrow run 2: broke %s at +%d, #7 fired %s at +%d (run 1's regrow due at +200),"
                    + " sim refused it %s, end %d, he ended at (%.2f, %.2f, %.2f) - on #7's landing %s", broke2 >= 0, t2 - t1,
                    fired7 >= 0, t7 - t1, refused, end2, at2.x, at2.y, at2.z, landed2));
            check(broke2 >= 0 && fired7 >= 0, "run 2 did not break and fire #7");
            check(t2 - t1 < 200 && t7 - t1 > 200, "setup: run 2 broke at +" + (t2 - t1) + " and fired #7 at +" + (t7 - t1)
                    + " - not either side of run 1's regrow at +200");
            check(landed2 && !refused, "#7's etherwarp through the hole #5 had just opened " + (refused
                    ? "was refused by the sim" : "did not land") + " - run 1's regrow put the block back after the rebuild");
        } finally {
            try {
                ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setEnabled", false));
                stopRoute(ctx);
            } catch (Throwable ignored) {
                // the rebuild below matters more
            }
            ctx.runOnClient(mc -> {
                ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setEnabled", true);
                ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setStartFromStartNodeOnly", true);
            });
            resetRoutes(ctx);
            room = saved;
            buildRoom(ctx, 0);
        }
    }

    /** His Museum route exactly as his Map Logger instance had it on 2026-10-06 (killer560smod-autoroutes.json). */
    private static final String MUSEUM_ROUTE = """
            [
            {"type":"ETHERWARP","x":31.5,"y":69.0,"z":56.5,"yaw":-122.669197,"pitch":-48.907085,"at":0,"start":true,"awaitEnabled":true,"await":"SECRET","amount":1,"landing":"39.500000 82.050000 51.500000"},
            {"type":"ETHERWARP","x":39.5,"y":82.0,"z":51.5,"yaw":-96.989906,"pitch":-59.229813,"at":0,"landing":"44.500000 93.050000 50.500000"},
            {"type":"ETHERWARP","x":44.5,"y":93.0,"z":50.5,"yaw":159.467621,"pitch":65.926178,"at":0,"awaitEnabled":true,"await":"SECRET","amount":1,"landing":"42.500000 82.050000 45.500000"},
            {"type":"ETHERWARP","x":42.5,"y":82.0,"z":45.5,"yaw":157.720352,"pitch":2.248079,"at":0,"landing":"30.500000 82.050000 15.500000"},
            {"type":"DUNGEON_BREAKER","x":30.5,"y":82.0,"z":15.5,"yaw":157.332077,"pitch":2.118652,"at":0,"blocks":["30 83 14","29 83 14","28 83 14","29 83 13","28 83 12","28 83 13","33 84 12","33 83 12","32 83 12","32 84 12","31 84 12","30 84 12","28 83 11","28 84 11","29 84 11","29 83 11","29 83 12","29 84 12","31 83 12","30 83 12"]},
            {"type":"ETHERWARP","x":30.5,"y":82.0,"z":15.5,"yaw":140.424835,"pitch":3.21848,"at":0,"landing":"27.500000 83.050000 11.500000"},
            {"type":"ETHERWARP","x":27.5,"y":83.0,"z":11.5,"yaw":-78.251343,"pitch":10.466819,"at":0,"awaitEnabled":true,"await":"SECRET","amount":1,"landing":"34.500000 83.050000 12.500000"},
            {"type":"DUNGEON_BREAKER","x":34.5,"y":83.0,"z":12.5,"yaw":78.483276,"pitch":74.015701,"at":0,"blocks":["33 82 12","33 81 12"]},
            {"type":"ETHERWARP","x":34.5,"y":83.0,"z":12.5,"yaw":108.252403,"pitch":66.509209,"at":0,"awaitEnabled":true,"await":"SECRET","amount":1,"landing":"28.500000 69.050000 10.500000"},
            {"type":"ETHERWARP","x":28.5,"y":69.0,"z":10.5,"yaw":-150.342102,"pitch":12.861895,"at":0,"landing":"31.500000 69.050000 5.500000"},
            {"type":"BOOM","x":31.5,"y":69.0,"z":5.5,"yaw":88.836853,"pitch":26.840017,"at":0},
            {"type":"BOOM","x":31.5,"y":69.0,"z":5.5,"yaw":-89.771271,"pitch":28.004892,"at":0},
            {"type":"CRYPT","x":31.5,"y":69.0,"z":5.5,"yaw":-80.972778,"pitch":25.027544,"at":0,"awaitEnabled":true,"await":"SECRET","amount":2},
            {"type":"ETHERWARP","x":31.5,"y":69.0,"z":5.5,"yaw":-82.851318,"pitch":8.849249,"at":0,"landing":"39.500000 69.050000 6.500000"},
            {"type":"DUNGEON_BREAKER","x":39.5,"y":69.0,"z":6.5,"yaw":-96.437561,"pitch":60.490627,"at":0,"blocks":["40 69 6","40 68 6","41 68 6","41 67 6","41 66 6","43 68 8"]},
            {"type":"ETHERWARP","x":39.5,"y":69.0,"z":6.5,"yaw":-88.535522,"pitch":53.340508,"at":0,"landing":"46.500000 61.050000 6.500000"},
            {"type":"ETHERWARP","x":46.5,"y":61.0,"z":6.5,"yaw":54.934692,"pitch":-53.372124,"at":0,"awaitEnabled":true,"await":"SECRET","amount":1}
            ]
            """;

    /** Server teleport to relative feet (x, y, z) - {@link #tpRel} at a height of the caller's choosing. */
    private static void tpRelY(ClientGameTestContext ctx, double x, double y, double z, float relYaw, float pitch) {
        AtomicReference<Boolean> done = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            Object frame = ModUnderTest.staticCall(FRAME, "current");
            Vec3 p = (Vec3) ModUnderTest.staticCall(COORDS, "toReal", new Class<?>[]{frame.getClass(), double.class,
                    double.class, double.class}, new Object[]{frame, x, y, z});
            float yaw = (Float) ModUnderTest.staticCall(COORDS, "toRealYaw", new Class<?>[]{frame.getClass(), float.class},
                    new Object[]{frame, relYaw});
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(uuid);
                sp.teleportTo(server.overworld(), p.x, p.y, p.z, java.util.Set.<net.minecraft.world.entity.Relative>of(),
                        yaw, pitch, false);
                done.set(true);
            });
            mc.player.setYRot(yaw);
            mc.player.setXRot(pitch);
        });
        ctx.waitFor(mc -> done.get() != null, 100);
        ctx.waitTicks(2);
    }

    /**
     * killer560 (2026-10-06): "having those multiple boom nodes on the same spot are not working to then go into the
     * crypt killer". His Museum route, exact, in the sim's Museum: armed on node #10 (the etherwarp onto the stack
     * tile), so the stack #11 BOOM, #12 BOOM, #13 CRYPT (await 2), #14 ETHERWARP fires as it does for him. Each boom
     * must act and complete (blew something, or nothing left to blow), the crypt node must then hold use with his
     * Spirit Sceptre, a crypt undead must die, and the route must carry on to #14. On main before the fix the first
     * boom opened its crypt (the sim broke 30 blocks) but watched only the hit block and its six neighbours, saw no
     * change, and stopped the route with "superboom didn't break anything".
     */
    private void caseMuseum(ClientGameTestContext ctx) {
        String saved = room;
        resetRoutes(ctx);
        room = "Museum";
        try {
            buildRoom(ctx, 0);
            giveHotbar(ctx);
            giveSlot(ctx, 4, "BAT_WAND");   // the Spirit Sceptre's SkyBlock id
            setArEnum(ctx, "setCryptWeapon", "CryptWeapon", "SPIRIT_SCEPTRE");
            ctx.runOnClient(mc -> {
                Object ar = ModUnderTest.config(AR_CONFIG);
                ModUnderTest.set(ar, "setStartFromStartNodeOnly", false);
                ModUnderTest.call(ar, "setCryptAttackTicks", new Class<?>[]{int.class}, new Object[]{100});
            });
            List<JsonObject> nodes = new ArrayList<>();
            for (JsonElement e : JsonParser.parseString(MUSEUM_ROUTE).getAsJsonArray()) {
                nodes.add(e.getAsJsonObject());
            }
            writeRoute(ctx, nodes);
            int c0 = cryptsBlown(ctx);
            ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(3));
            long m = LogTap.mark();
            startSampling(ctx);
            // Onto node #10, his etherwarp that lands on the stack tile.
            tpRelY(ctx, 28.5, 69.0, 10.5, -150.342102f, 12.861895f);
            boolean ended = waitFor(ctx, 400, () -> logHas(m, "Node #14 ETHERWARP acted") || logHas(m, "Stopped"));
            ctx.waitTicks(10);
            List<Sample> s = stopSampling(ctx);
            Map<String, Integer> packets = new java.util.TreeMap<>();
            for (Sample x : s) {
                for (String pk : x.out()) {
                    packets.merge(pk, 1, Integer::sum);
                }
            }
            int c1 = cryptsBlown(ctx);
            List<String> log = LogTap.since(m);
            int booms = 0;
            String cryptLine = null;
            for (String l : log) {
                if (l.contains("Sim superboom at") || l.contains("Boom: ") || l.contains("BOOM acted")
                        || l.contains("CRYPT") || l.contains("Stack of") || l.contains("Stopped")
                        || l.contains("ETHERWARP acted")) {
                    println("museum log: " + l);
                }
                if (l.contains("Boom: blocks changed") || l.contains("nothing left to blow")) {
                    booms++;
                }
                if (l.contains("CRYPT: ") && l.contains("kill(s)")) {
                    cryptLine = l;
                }
            }
            int i11 = logIndex(m, "Node #11 BOOM acted");
            int i12 = logIndex(m, "Node #12 BOOM acted");
            int i13 = logIndex(m, "Node #13 CRYPT acted");
            int i14 = logIndex(m, "Node #14 ETHERWARP acted");
            println("museum: ended " + ended + ", boom #11@" + i11 + " #12@" + i12 + " crypt #13@" + i13 + " ew #14@" + i14
                    + ", booms completed " + booms + ", crypt undead dead " + c0 + " -> " + c1 + ", packets " + packets);
            check(!logHas(m, "superboom didn't break anything"), "a boom stopped the route: superboom didn't break anything");
            check(i11 >= 0, "boom #11 never acted");
            check(i12 > i11, "boom #12 did not act after #11 (" + i11 + ", " + i12 + ")");
            check(booms >= 2, "only " + booms + " boom(s) completed");
            check(i13 > i12, "the crypt node did not fire after the booms (" + i12 + ", " + i13 + ")");
            check(logHas(m, "holding use"), "the crypt node did not hold use");
            check(c1 > c0, "no crypt undead died (" + c0 + " -> " + c1 + ")");
            check(cryptLine != null, "the crypt node never reported its kills");
            check(i14 > i13, "the route did not carry on to #14 after the crypt node (" + i13 + ", " + i14 + ")");

            // ---- again on the same tile: both crypts are already open, so both booms have nothing left to blow ----
            // Auto Routes off while he is moved: stopped where he stands (on #15's tile) it re-arms at once.
            ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setEnabled", false));
            stopRoute(ctx);
            ctx.runOnClient(mc -> ModUnderTest.call(ModUnderTest.config(AR_CONFIG), "setCryptAttackTicks",
                    new Class<?>[]{int.class}, new Object[]{20}));
            ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(3));
            tpRelY(ctx, 31.5, 69.0, 5.5, -82.851318f, 8.849249f);
            ctx.waitTicks(10);
            long m2 = LogTap.mark();
            ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setEnabled", true));
            boolean ended2 = waitFor(ctx, 200, () -> logHas(m2, "Node #14 ETHERWARP acted") || logHas(m2, "Stopped"));
            int nothing = 0;
            for (String l : LogTap.since(m2)) {
                if (l.contains("Sim superboom at") || l.contains("Boom: ") || l.contains("CRYPT") || l.contains("Stopped")
                        || l.contains("Node #14")) {
                    println("museum again log: " + l);
                }
                if (l.contains("nothing left to blow")) {
                    nothing++;
                }
            }
            int j11 = logIndex(m2, "Node #11 BOOM acted");
            int j12 = logIndex(m2, "Node #12 BOOM acted");
            int j13 = logIndex(m2, "Node #13 CRYPT acted");
            int j14 = logIndex(m2, "Node #14 ETHERWARP acted");
            println("museum again: ended " + ended2 + ", boom #11@" + j11 + " #12@" + j12 + " crypt #13@" + j13 + " ew #14@"
                    + j14 + ", nothing-left booms " + nothing);
            check(!logHas(m2, "superboom didn't break anything"), "again: a boom with nothing left to blow stopped the route");
            check(j11 >= 0 && j12 > j11 && nothing >= 2, "again: the booms did not both complete with nothing left to blow ("
                    + j11 + ", " + j12 + ", " + nothing + ")");
            check(j13 > j12 && j14 > j13, "again: the crypt node and #14 did not follow the empty booms (" + j13 + ", " + j14
                    + ")");
        } finally {
            try {
                stopRoute(ctx);
            } catch (Throwable ignored) {
                // the rebuild below matters more
            }
            setArEnum(ctx, "setCryptWeapon", "CryptWeapon", "HYPERION");
            ctx.runOnClient(mc -> {
                ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setStartFromStartNodeOnly", true);
                ModUnderTest.call(ModUnderTest.config(AR_CONFIG), "setCryptAttackTicks", new Class<?>[]{int.class},
                        new Object[]{100});
            });
            resetRoutes(ctx);
            room = saved;
            buildRoom(ctx, 0);
        }
    }

    /**
     * The crypt node HOLDS right click (killer560, 2026-10-06: "only have the crypt node hold right click for its
     * equivalent of attacking do not have it spam click"). Control first: the harness holds the real use key with a
     * Hyperion, looking straight down - vanilla's held cadence. Then a crypt node with nothing to kill holds it for its
     * Crypt Attack Time: the use ticks must be the control's cadence, packet for packet, and stop when it lets go.
     */
    private void caseCryptHold(ClientGameTestContext ctx) {
        resetRoutes(ctx);
        arena(ctx, false);
        giveHotbar(ctx);
        giveSlot(ctx, 4, "HYPERION");
        setArEnum(ctx, "setCryptWeapon", "CryptWeapon", "HYPERION");
        try {
            // ---- control: vanilla, the use key held by the harness ----
            ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(4));
            tpRel(ctx, 20.5, 20.5, 0f, 90f);
            ctx.waitTicks(10);
            startSampling(ctx);
            ctx.getInput().holdKey(o -> o.keyUse);
            ctx.waitTicks(30);
            ctx.getInput().releaseKey(o -> o.keyUse);
            ctx.waitTicks(3);
            stopSampling(ctx);
            ArChainMeasure.Held control = ArChainMeasure.held(0);
            println("crypt hold, vanilla control: " + control.describe());

            // ---- the crypt node, 30 ticks of Crypt Attack Time, nothing to kill ----
            ctx.runOnClient(mc -> ModUnderTest.call(ModUnderTest.config(AR_CONFIG), "setCryptAttackTicks",
                    new Class<?>[]{int.class}, new Object[]{30}));
            writeRoute(ctx, List.of(cryptNode(10, 10, 0f, 0f, true, 0)));
            ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(3));
            tpRel(ctx, 10.5, 6.5, 0f, 0f);
            ctx.waitTicks(10);
            long m = LogTap.mark();
            startSampling(ctx);
            tpRel(ctx, 10.5, 10.5, 0f, 0f);
            boolean movedOn = waitFor(ctx, 100, () -> logHas(m, "up, moving on"));
            ctx.waitTicks(20);
            stopSampling(ctx);
            ArChainMeasure.Held node = ArChainMeasure.held(0);
            boolean keyUp = ctx.computeOnClient(mc -> !mc.options.keyUse.isDown());
            println("crypt hold, crypt node: " + node.describe() + ", use key up after: " + keyUp);
            check(control.every(4), "the vanilla control did not use every 4 ticks: " + control.describe());
            check(movedOn, "the crypt node did not hold for its attack time and move on");
            check(logHas(m, "holding use"), "the crypt node did not say it was holding use");
            check(node.ticks().size() >= 6, "the crypt node used " + node.ticks().size() + " time(s) in 30 ticks");
            check(node.every(4), "the crypt node's uses are not vanilla's held cadence: " + node.describe());
            check(node.perUse().stream().distinct().toList().equals(control.perUse().stream().distinct().toList()),
                    "the crypt node's packets per use " + node.perUse() + " differ from the held key's " + control.perUse());
            check(keyUp, "the use key was left held after the crypt node");
        } finally {
            ctx.runOnClient(mc -> ModUnderTest.call(ModUnderTest.config(AR_CONFIG), "setCryptAttackTicks",
                    new Class<?>[]{int.class}, new Object[]{100}));
        }
    }

    /** delete / remove / undo / redo / clear over many steps, through both /ar and /autoroutes, checked on disk. */
    private void caseEdit(ClientGameTestContext ctx) {
        resetRoutes(ctx);
        arena(ctx, false);
        giveHotbar(ctx);
        int[][] at = {{6, 22}, {10, 22}, {14, 22}, {18, 22}};
        for (int[] p : at) {
            tpRel(ctx, p[0] + 0.5, p[1] + 0.5, 0f, 0f);
            ctx.waitTicks(4);
            cmd(ctx, "/ar add breaker");
            ctx.waitTicks(4);
        }
        tpRel(ctx, 22.5, 14.5, 0f, 0f);
        ctx.waitTicks(4);
        expectXs(ctx, "after four adds", 6, 10, 14, 18);
        cmd(ctx, "/ar delete 2");
        expectXs(ctx, "/ar delete 2", 6, 14, 18);
        cmd(ctx, "/autoroutes remove 1");
        expectXs(ctx, "/autoroutes remove 1", 14, 18);
        cmd(ctx, "/ar undo");
        expectXs(ctx, "undo of the remove", 6, 14, 18);
        cmd(ctx, "/autoroutes undo");
        expectXs(ctx, "undo of the delete", 6, 10, 14, 18);
        cmd(ctx, "/ar redo");
        expectXs(ctx, "redo of the delete", 6, 14, 18);
        cmd(ctx, "/autoroutes redo");
        expectXs(ctx, "redo of the remove", 14, 18);
        cmd(ctx, "/ar undo");
        cmd(ctx, "/ar undo");
        expectXs(ctx, "two undos", 6, 10, 14, 18);
        cmd(ctx, "/ar clear");
        expectXs(ctx, "/ar clear");
        cmd(ctx, "/ar undo");
        expectXs(ctx, "undo of the clear", 6, 10, 14, 18);
        cmd(ctx, "/ar redo");
        expectXs(ctx, "redo of the clear");
        cmd(ctx, "/ar undo");
        expectXs(ctx, "undo of the redone clear", 6, 10, 14, 18);
        // A new change empties redo.
        cmd(ctx, "/ar delete 4");
        cmd(ctx, "/ar undo");
        tpRel(ctx, 22.5, 22.5, 0f, 0f);
        ctx.waitTicks(4);
        cmd(ctx, "/ar add breaker");
        ctx.waitTicks(4);
        long m = LogTap.mark();
        cmd(ctx, "/ar redo");
        expectXs(ctx, "redo after a new add", 6, 10, 14, 18, 22);
        check(logHas(m, "Nothing to redo"), "redo after a new change did not say there was nothing to redo");
        // Delete nearest: standing on #3 (x 14).
        tpRel(ctx, 14.5, 22.5, 0f, 0f);
        ctx.waitTicks(4);
        cmd(ctx, "/autoroutes delete");
        expectXs(ctx, "/autoroutes delete (nearest)", 6, 10, 18, 22);
        // Undo with an empty history deletes the room's last node (AP3), and redo puts it back.
        ctx.runOnClient(mc -> ModUnderTest.staticCall(STORE, "reload"));  // reload clears the history
        cmd(ctx, "/ar undo");
        expectXs(ctx, "undo with no history", 6, 10, 18);
        cmd(ctx, "/ar redo");
        expectXs(ctx, "redo of the fallback delete", 6, 10, 18, 22);
        // Numbers in the labels and /ar list follow the list: "/ar list" says 4 nodes.
        m = LogTap.mark();
        cmd(ctx, "/autoroutes list");
        ctx.waitTicks(3);
        check(logHas(m, ": 4 nodes"), "/autoroutes list did not report 4 nodes");

        // ---- six decimals: a node placed at an awkward look holds, in memory, exactly what /ar reload gives back ----
        tpRel(ctx, 10.5, 14.5, -83.123456789f, 7.987654321f);
        ctx.waitTicks(4);
        cmd(ctx, "/ar add ew");
        ctx.waitTicks(30);
        String before = ctx.computeOnClient(SimAutoRoutesTests::nodeBits);
        cmd(ctx, "/ar reload");
        ctx.waitTicks(3);
        String after = ctx.computeOnClient(SimAutoRoutesTests::nodeBits);
        println("six decimals: " + before);
        check(before.equals(after), "a node is not the same after /ar reload: before " + before + " after " + after);
        JsonObject last = fileNodes(ctx).get(fileNodes(ctx).size() - 1).getAsJsonObject();
        String yawText = last.get("yaw").getAsString();
        check(yawText.contains(".") && yawText.substring(yawText.indexOf('.') + 1).length() <= 6,
                "the saved yaw has more than six decimals: " + yawText);
    }

    // ============================================================================================ ar-redo (960)

    /**
     * killer560 (2026-10-12): "if i do /ar delete or /ar remove and then do /ar redo then it should bring them back. Same
     * if i do /clear /ar redo should bring them all back." Four nodes; /ar delete 2 then /ar remove 2 (two deletes, one
     * at a time); /ar redo twice brings both back at their old numbers, and the routes file is then the original one
     * field for field. A third redo has nothing left. /ar undo after a restore deletes it again and /ar redo puts it back.
     * /ar clear then /ar redo: the whole route back, identical; /ar undo clears it again. Undo/redo of an add is
     * unchanged. Every step through a real ChatScreen, checked on disk.
     */
    private void caseRedoRestore(ClientGameTestContext ctx) {
        resetRoutes(ctx);
        arena(ctx, false);
        giveHotbar(ctx);
        int[][] at = {{6, 22}, {10, 22}, {14, 22}, {18, 22}};
        for (int[] p : at) {
            tpRel(ctx, p[0] + 0.5, p[1] + 0.5, 0f, 0f);
            ctx.waitTicks(4);
            cmd(ctx, "/ar add breaker");
            ctx.waitTicks(4);
        }
        tpRel(ctx, 22.5, 14.5, 0f, 0f);
        ctx.waitTicks(4);
        expectXs(ctx, "redo-restore: after four adds", 6, 10, 14, 18);
        String original = fileNodes(ctx).toString();
        // Reload: the four adds are no longer history, so nothing but the deletes below can be restored.
        ctx.runOnClient(mc -> ModUnderTest.staticCall(STORE, "reload"));

        // ---- two deletes one by one, two redos ----
        cmd(ctx, "/ar delete 2");
        expectXs(ctx, "redo-restore: /ar delete 2", 6, 14, 18);
        cmd(ctx, "/ar remove 2");
        expectXs(ctx, "redo-restore: /ar remove 2", 6, 18);
        long m = LogTap.mark();
        cmd(ctx, "/ar redo");
        expectXs(ctx, "redo-restore: first redo", 6, 14, 18);
        check(logHas(m, "Restored #2"), "the first redo did not say it restored #2");
        m = LogTap.mark();
        cmd(ctx, "/ar redo");
        expectXs(ctx, "redo-restore: second redo", 6, 10, 14, 18);
        check(logHas(m, "Restored #2"), "the second redo did not say it restored #2");
        String afterDeletes = fileNodes(ctx).toString();
        check(afterDeletes.equals(original), "the restored route differs from the original: was " + original
                + " now " + afterDeletes);
        m = LogTap.mark();
        cmd(ctx, "/ar redo");
        expectXs(ctx, "redo-restore: third redo (nothing left)", 6, 10, 14, 18);
        check(logHas(m, "Nothing to redo"), "a third redo did not say there was nothing to redo");

        // ---- /ar undo after a restore deletes it again; /ar redo puts it back ----
        cmd(ctx, "/ar undo");
        expectXs(ctx, "redo-restore: undo of the newest restore", 6, 14, 18);
        cmd(ctx, "/ar redo");
        expectXs(ctx, "redo-restore: redo of that undo", 6, 10, 14, 18);
        cmd(ctx, "/ar undo");
        cmd(ctx, "/ar undo");
        expectXs(ctx, "redo-restore: both restores undone", 6, 18);
        cmd(ctx, "/ar redo");
        cmd(ctx, "/ar redo");
        expectXs(ctx, "redo-restore: both redone", 6, 10, 14, 18);

        // ---- /ar clear, /ar redo: the whole route back ----
        cmd(ctx, "/ar clear");
        expectXs(ctx, "redo-restore: /ar clear");
        m = LogTap.mark();
        cmd(ctx, "/ar redo");
        expectXs(ctx, "redo-restore: redo after the clear", 6, 10, 14, 18);
        check(!logHas(m, "Nothing to redo"), "the redo after /ar clear said there was nothing to redo");
        String afterClear = fileNodes(ctx).toString();
        check(afterClear.equals(original), "the route restored after /ar clear differs from the original: was "
                + original + " now " + afterClear);
        cmd(ctx, "/ar undo");
        expectXs(ctx, "redo-restore: undo of the restored clear");
        cmd(ctx, "/ar redo");
        expectXs(ctx, "redo-restore: redo of that undo", 6, 10, 14, 18);

        // ---- unchanged: undo an add, redo it; a new change leaves nothing to restore ----
        tpRel(ctx, 22.5, 22.5, 0f, 0f);
        ctx.waitTicks(4);
        cmd(ctx, "/ar add breaker");
        ctx.waitTicks(4);
        tpRel(ctx, 22.5, 14.5, 0f, 0f);
        ctx.waitTicks(4);
        expectXs(ctx, "redo-restore: a fifth add", 6, 10, 14, 18, 22);
        cmd(ctx, "/ar undo");
        expectXs(ctx, "redo-restore: undo of the add", 6, 10, 14, 18);
        cmd(ctx, "/ar redo");
        expectXs(ctx, "redo-restore: redo of the add", 6, 10, 14, 18, 22);
        m = LogTap.mark();
        cmd(ctx, "/ar redo");
        expectXs(ctx, "redo-restore: redo with the add newest", 6, 10, 14, 18, 22);
        check(logHas(m, "Nothing to redo"), "a redo with an add as the newest change did not say nothing to redo");
    }

    private static final String EDIT_INPUT = "com.killer560.hub.autoroutes.AutoRoutesEditInput";
    private static final String AR_RENDERER = "com.killer560.hub.autoroutes.AutoRoutesRenderer";
    private static final String DX_ENUMS = "com.killer560.hub.dungeonextras.DungeonExtrasConfig";

    /**
     * Breaker edit mode (killer560, 2026-10-06: "when in edit mode it shouldnt be able to break blocks if i right click a
     * block it adds it if i shift right click it removes it have an option under auto routes for it to be waypoints or
     * highlights"). /ar add dungeonbreaker makes a DUNGEON_BREAKER node and is offered in completion. Control: with the
     * Dungeon Breaker in hand and edit mode off, a real left click on stone sends a swing/dig. In edit mode the same
     * click breaks the stone too (killer560, 2026-10-09 - it was swallowed from 2026-10-06 until mod ar-nodes); right
     * click adds (and breaks the block once), shift-right-click removes, and the saved blocks follow. Every Breaker Block Display x Style combination is drawn by its own path. After edit mode,
     * a left click swings again.
     */
    private void caseDbEdit(ClientGameTestContext ctx) {
        resetRoutes(ctx);
        arena(ctx, false);
        giveHotbar(ctx);
        // ---- /ar add dungeonbreaker: offered in completion, and the node it makes is a DUNGEON_BREAKER ----
        List<String> typeWords = ctx.computeOnClient(mc -> suggest(mc, "ar add "));
        List<String> partial = ctx.computeOnClient(mc -> suggest(mc, "ar add dungeon"));
        println("dbedit complete: 'ar add ' -> " + typeWords + ", 'ar add dungeon' -> " + partial);
        check(typeWords.contains("dungeonbreaker") && typeWords.contains("breaker"),
                "'ar add ' does not offer both breaker and dungeonbreaker: " + typeWords);
        check(partial.contains("dungeonbreaker"), "'ar add dungeon' does not complete dungeonbreaker: " + partial);
        setBlocks(ctx, Map.of(new int[]{22, F, 10}, Blocks.STONE.defaultBlockState(),
                new int[]{22, F + 1, 10}, Blocks.STONE.defaultBlockState(),
                new int[]{22, F + 1, 14}, Blocks.STONE.defaultBlockState()));
        tpRel(ctx, 20.5, 10.5, -90f, 0f);
        ctx.waitTicks(5);
        cmd(ctx, "/ar add dungeonbreaker");
        ctx.waitTicks(10);
        JsonArray nodes = fileNodes(ctx);
        check(nodes.size() == 1 && "DUNGEON_BREAKER".equals(nodes.get(0).getAsJsonObject().get("type").getAsString()),
                "/ar add dungeonbreaker did not make one DUNGEON_BREAKER node: " + nodes);
        cmd(ctx, "/ar add breaker");
        ctx.waitTicks(10);
        nodes = fileNodes(ctx);
        check(nodes.size() == 2 && "DUNGEON_BREAKER".equals(nodes.get(1).getAsJsonObject().get("type").getAsString()),
                "/ar add breaker did not make the same node type as dungeonbreaker: " + nodes);
        cmd(ctx, "/ar delete 2");
        ctx.waitTicks(3);
        check(fileNodes(ctx).size() == 1, "/ar delete 2 left " + fileNodes(ctx).size() + " node(s)");

        // ---- control: edit mode off, Dungeon Breaker in hand, a real left click on stone at (22,F+1,14) ----
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(2));
        tpRel(ctx, 20.5, 14.5, -90f, 0f);
        ctx.waitTicks(5);
        check(aimedAt(ctx, 22, F + 1, 14), "the control is not aimed at its stone");
        startSampling(ctx);
        leftClick(ctx, 3);
        ctx.waitTicks(5);
        List<Sample> s = stopSampling(ctx);
        int ctrlSwing = countOut(s, "swing");
        int ctrlDig = countOut(s, "player_action");
        println("dbedit control (edit mode off): " + ctrlSwing + " swing, " + ctrlDig + " player_action");
        check(ctrlSwing + ctrlDig >= 1, "the control left click sent nothing - the edit-mode check below would prove nothing");

        // ---- edit mode on: since mod ar-nodes (killer560, 2026-10-09: "I cannot break blocks while in the edit breaker
        // mode") his left click with the breaker in hand is a normal left click again - it swings, digs and breaks.
        // Item 1b's own case is 96-ar-657-dbeditattack; this keeps the old window's shape as its control. ----
        long m = LogTap.mark();
        cmd(ctx, "/ar edit db");
        ctx.waitTicks(3);
        check(ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(FEATURE, "isEditMode")),
                "/ar edit db did not turn edit mode on");
        tpRel(ctx, 20.5, 10.5, -90f, 0f);
        ctx.waitTicks(5);
        check(aimedAt(ctx, 22, F + 1, 10), "edit mode's left click is not aimed at its stone");
        startSampling(ctx);
        leftClick(ctx, 2);
        ctx.waitTicks(5);
        s = stopSampling(ctx);
        println("dbedit edit-mode left click: " + countOut(s, "swing") + " swing, " + countOut(s, "player_action")
                + " player_action, stone broken " + blockAir(ctx, 22, F + 1, 10));
        check(countOut(s, "swing") >= 1 && countOut(s, "player_action") >= 1, "a left click in edit mode sent no swing/dig");
        check(blockAir(ctx, 22, F + 1, 10), "a left click in edit mode did not break the stone");
        // The sim regrows it in 10 s; put it back now for the picks below.
        setBlocks(ctx, Map.of(new int[]{22, F + 1, 10}, Blocks.STONE.defaultBlockState()));

        // ---- right click adds, shift-right-click removes ----
        rightClick(ctx, 22, F, 10, Direction.WEST);
        ctx.waitTicks(3);
        rightClick(ctx, 22, F + 1, 10, Direction.WEST);
        ctx.waitTicks(5);
        JsonArray two = fileNodes(ctx).get(0).getAsJsonObject().getAsJsonArray("blocks");
        println("dbedit after two right clicks: " + two);
        check(two != null && two.size() == 2, "two right clicks saved " + two + ", expected 2 blocks");
        ctx.getInput().holdKey(options -> options.keyShift);
        boolean sneaking = waitFor(ctx, 20, () -> ctx.computeOnClient(mc -> mc.player.isShiftKeyDown()));
        rightClick(ctx, 22, F + 1, 10, Direction.WEST);
        ctx.waitTicks(3);
        ctx.getInput().releaseKey(options -> options.keyShift);
        ctx.waitTicks(5);
        check(sneaking, "shift never registered on the player - the remove check would prove nothing");
        JsonArray one = fileNodes(ctx).get(0).getAsJsonObject().getAsJsonArray("blocks");
        println("dbedit after shift-right-click: " + one);
        check(one != null && one.size() == 1 && one.get(0).equals(two.get(0)),
                "shift-right-click left " + one + ", expected only the first pick " + two.get(0));
        // Picking and removing break nothing (killer560, 2026-10-11; 96-ar-656-dbaddbreak).
        check(!blockAir(ctx, 22, F, 10) && !blockAir(ctx, 22, F + 1, 10), "edit mode broke a picked block");

        // ---- edit mode, AOTV in hand (killer560, 2026-10-06): a real sneaking right click on the floor three blocks
        // away is a normal etherwarp - use_item goes out, the server moves him, and nothing is picked ----
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
        tpRel(ctx, 20.5, 14.5, relYawTo(20, 14, 17, 14), pitchTo(20, 14, 17, 14));
        ctx.waitTicks(5);
        long mA = LogTap.mark();
        startSampling(ctx);
        ctx.getInput().holdKey(options -> options.keyShift);
        boolean aotvSneak = waitFor(ctx, 20, () -> ctx.computeOnClient(mc -> mc.player.getLastSentInput().shift()));
        boolean aimsBlock = ctx.computeOnClient(mc -> mc.hitResult instanceof BlockHitResult b
                && b.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK);
        ctx.getInput().holdKey(options -> options.keyUse);
        ctx.waitTicks(2);
        ctx.getInput().releaseKey(options -> options.keyUse);
        Vec3 warped = waitLanded(ctx, 17, 14, 40);
        ctx.getInput().releaseKey(options -> options.keyShift);
        ctx.waitTicks(3);
        s = stopSampling(ctx);
        JsonArray afterAotv = fileNodes(ctx).get(0).getAsJsonObject().getAsJsonArray("blocks");
        println("dbedit AOTV in edit mode: sneak " + aotvSneak + ", crosshair on a block " + aimsBlock + ", "
                + countOut(s, "use_item") + " use_item, " + countOut(s, "use_item_on") + " use_item_on, landed " + warped
                + ", blocks " + afterAotv);
        check(aotvSneak, "shift never reached the server - the etherwarp check would prove nothing");
        check(aimsBlock, "the AOTV click was not aimed at a block in reach - it would not pass edit mode's block hook");
        // Aimed at a block in reach the click goes out as use_item_on (the sim etherwarps on it, as Hypixel does) and/or
        // use_item; before this change edit mode's block hook answered FAIL and neither left the client.
        check(countOut(s, "use_item") + countOut(s, "use_item_on") >= 1,
                "edit mode swallowed the AOTV right click (no use_item / use_item_on)");
        check(warped != null, "the AOTV right click in edit mode did not etherwarp him to (17,14) (at " + relPos(ctx) + ")");
        check(afterAotv != null && afterAotv.size() == 1, "the AOTV right click picked a block: " + afterAotv);

        // ---- edit mode, Hyperion in hand: a left click is a normal left click (swings) ----
        giveSlot(ctx, 4, "HYPERION");
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(4));
        // The control's breaker click above broke this stone (the sim puts it back only after 10 s).
        setBlocks(ctx, Map.of(new int[]{22, F + 1, 14}, Blocks.STONE.defaultBlockState()));
        tpRel(ctx, 20.5, 14.5, -90f, 0f);
        ctx.waitTicks(5);
        check(aimedAt(ctx, 22, F + 1, 14), "the Hyperion left click is not aimed at its stone");
        startSampling(ctx);
        leftClick(ctx, 3);
        ctx.waitTicks(5);
        s = stopSampling(ctx);
        println("dbedit Hyperion in edit mode: " + countOut(s, "swing") + " swing, " + countOut(s, "player_action")
                + " player_action");
        check(countOut(s, "swing") >= 1, "a Hyperion left click in edit mode did not swing");
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(2));
        tpRel(ctx, 20.5, 10.5, -90f, 0f);
        ctx.waitTicks(5);

        // ---- Breaker Block Display x Style: each combination takes its own draw path ----
        try {
            for (String display : new String[]{"HIGHLIGHT", "WAYPOINT"}) {
                for (String style : new String[]{"OUTLINE", "FILL", "FILLED_OUTLINE"}) {
                    setBreakerDisplay(ctx, display, style);
                    String want = display.toLowerCase(java.util.Locale.ROOT) + "/" + style + " 1 drawn";
                    boolean drew = waitFor(ctx, 40, () -> want.equals(ctx.computeOnClient(mc ->
                            (String) ModUnderTest.staticCall(AR_RENDERER, "lastBreakerDraw"))));
                    String got = ctx.computeOnClient(mc -> (String) ModUnderTest.staticCall(AR_RENDERER, "lastBreakerDraw"));
                    println("dbedit draw " + display + "/" + style + ": " + got);
                    check(drew, "Breaker Block Display " + display + " / " + style + " drew '" + got + "', expected '"
                            + want + "'");
                }
            }
        } finally {
            setBreakerDisplay(ctx, "HIGHLIGHT", "FILLED_OUTLINE");
        }

        // ---- edit mode off: left click swings again (the key was let go, not left stuck) ----
        cmd(ctx, "/ar edit db");
        ctx.waitTicks(3);
        check(!ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(FEATURE, "isEditMode")),
                "edit mode did not turn off");
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(3));
        tpRel(ctx, 20.5, 14.5, -90f, 0f);
        ctx.waitTicks(5);
        startSampling(ctx);
        leftClick(ctx, 3);
        ctx.waitTicks(5);
        s = stopSampling(ctx);
        println("dbedit after edit mode: " + countOut(s, "swing") + " swing, " + countOut(s, "player_action")
                + " player_action");
        check(countOut(s, "swing") + countOut(s, "player_action") >= 1, "a left click after edit mode sent nothing");
    }

    private static boolean aimedAt(ClientGameTestContext ctx, int x, int y, int z) {
        return ctx.computeOnClient(mc -> mc.hitResult instanceof BlockHitResult b
                && b.getBlockPos().equals(real(ModUnderTest.staticCall(FRAME, "current"), x, y, z)));
    }

    private static void setBreakerDisplay(ClientGameTestContext ctx, String display, String style) {
        ctx.runOnClient(mc -> {
            Object cfg = ModUnderTest.config(AR_CONFIG);
            ModUnderTest.call(cfg, "setBreakerDisplay", new Class<?>[]{enumClass(DX_ENUMS + "$BreakerDisplay")},
                    new Object[]{ModUnderTest.enumValue(DX_ENUMS + "$BreakerDisplay", display)});
            ModUnderTest.call(cfg, "setBreakerStyle", new Class<?>[]{enumClass(DX_ENUMS + "$BreakerStyle")},
                    new Object[]{ModUnderTest.enumValue(DX_ENUMS + "$BreakerStyle", style)});
        });
    }

    /** Every node of the room, as the exact bits of x, y, z, yaw, pitch and landing. Client thread. */
    @SuppressWarnings("unchecked")
    private static String nodeBits(Minecraft mc) {
        StringBuilder sb = new StringBuilder();
        for (Object n : (List<Object>) ModUnderTest.staticCall(FEATURE, "currentRouteNodes")) {
            try {
                Class<?> c = n.getClass();
                sb.append(String.format("[%s %s %s %s/%08x %s/%08x %s %s %s]", c.getField("x").getDouble(n),
                        c.getField("y").getDouble(n), c.getField("z").getDouble(n), c.getField("yaw").getFloat(n),
                        Float.floatToIntBits(c.getField("yaw").getFloat(n)), c.getField("pitch").getFloat(n),
                        Float.floatToIntBits(c.getField("pitch").getFloat(n)), c.getField("landingX").getDouble(n),
                        c.getField("landingY").getDouble(n), c.getField("landingZ").getDouble(n)));
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }
        return sb.toString();
    }

    /** /ar edit n: every control on screen and clickable where drawn, await + start through the real buttons, Go To. */
    private void caseScreen(ClientGameTestContext ctx) {
        resetRoutes(ctx);
        arena(ctx, false);
        giveHotbar(ctx);
        writeRoute(ctx, List.of(ew(6, 6, 14, 6, true), node("DUNGEON_BREAKER", 24, 24, 0f, 0f)));
        tpRel(ctx, 4.5, 4.5, 0f, 0f);
        ctx.waitTicks(5);
        openEditor(ctx, 1);
        checkLayout(ctx);
        press(ctx, "Await Secrets");
        ctx.waitTicks(2);
        check(labels(ctx).containsAll(List.of("None", "1", "2", "3", "4")), "the await dropdown did not open 0-4: "
                + labels(ctx));
        checkLayout(ctx);
        press(ctx, "2");
        ctx.waitTicks(2);
        check(labels(ctx).stream().anyMatch(l -> l.startsWith("Await Secrets: 2")), "the await label did not change");
        press(ctx, "Start Node");
        ctx.waitTicks(2);
        press(ctx, "Save");
        ctx.waitTicks(3);
        check(ctx.computeOnClient(mc -> McCompat.screen(mc) == null), "Save did not close the editor");
        JsonObject n1 = fileNodes(ctx).get(0).getAsJsonObject();
        check(!n1.has("start") && n1.has("awaitEnabled") && n1.get("amount").getAsInt() == 2,
                "node 1 after Save: " + n1);
        cmd(ctx, "/ar undo");
        n1 = fileNodes(ctx).get(0).getAsJsonObject();
        check(n1.has("start") && !n1.has("awaitEnabled"), "/ar undo did not revert the whole Save: " + n1);
        // Go To node #2 across the arena: the Interactive Map's warp, then edit mode, nothing fired.
        openEditor(ctx, 2);
        press(ctx, "Start Node");
        long m = LogTap.mark();
        Vec3 from = relPos(ctx);
        press(ctx, "Go To");
        boolean edit = waitFor(ctx, 400, () -> ctx.computeOnClient(mc ->
                (Boolean) ModUnderTest.staticCall(FEATURE, "isEditMode")));
        Vec3 to = relPos(ctx);
        println("Go To: " + from + " -> " + to);
        check(edit, "Go To never turned edit mode on (at " + to + ")");
        check(to.distanceTo(new Vec3(24.5, F, 24.5)) < 2.0, "Go To did not bring him to node #2: " + to);
        check(logHas(m, "[Path]"), "Go To did not use the Interactive Map's planner");
        check(!logHas(m, "Node #2 DUNGEON_BREAKER acted"), "node #2 fired under him on arrival");
        check(fileNodes(ctx).get(1).getAsJsonObject().has("start"), "Go To's Save did not save the start toggle");
        cmd(ctx, "/ar edit db");
        ctx.waitTicks(3);
        check(!ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(FEATURE, "isEditMode")),
                "edit mode did not turn off");
        ctx.waitTicks(20);
        check(!logHas(m, "Node #2 DUNGEON_BREAKER acted"), "node #2 fired once edit mode went off while he stood in it");
    }

    /**
     * Path nodes: two added by command, planned ONCE by the floor planner and saved; replays do not plan; the
     * landing is the second node's block; await + start on #1 through the editor; a boom stacked on #1's tile fires
     * before the path; editing #2 re-plans; a missing hop block holds the warp.
     */
    private void casePath(ClientGameTestContext ctx) {
        resetRoutes(ctx);
        arena(ctx, true);   // with the dividing wall, so the path needs more than one warp
        giveHotbar(ctx);
        ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setStartFromStartNodeOnly", false));
        try {
            int plans0 = plans(ctx);
            tpRel(ctx, 6.5, 20.5, 0f, 0f);
            ctx.waitTicks(4);
            long m = LogTap.mark();
            cmd(ctx, "/ar add path");
            ctx.waitTicks(5);
            check(logHas(m, "has no later path node"), "a lone path node did not say it has no partner");
            tpRel(ctx, 24.5, 10.5, 0f, 0f);
            ctx.waitTicks(4);
            cmd(ctx, "/ar add path");
            boolean planned = waitFor(ctx, 400, () -> logHas(m, "planned by the floor planner"));
            check(planned, "adding the second path node did not plan the pair");
            check(logHas(m, "[Path]"), "the pair was not planned by the Interactive Map's floor planner");
            check(plans(ctx) == plans0 + 1, "planned " + (plans(ctx) - plans0) + " times, expected once");
            JsonObject p1 = fileNodes(ctx).get(0).getAsJsonObject();
            check(p1.has("hops") && p1.getAsJsonArray("hops").size() >= 2,
                    "the saved path has no / one warp across the wall: " + p1);
            int hops = p1.has("hops") ? p1.getAsJsonArray("hops").size() : 0;
            println("path: " + hops + " warp(s) saved");
            // ---- two replays, no planning ----
            for (int run = 1; run <= 2; run++) {
                tpRel(ctx, 4.5, 20.5, 0f, 0f);
                ctx.waitTicks(10);
                startSampling(ctx);
                long mr = LogTap.mark();
                tpRel(ctx, 6.5, 20.5, 0f, 0f);
                Vec3 l = waitLanded(ctx, 24, 10, 200);
                List<Sample> s = stopSampling(ctx);
                check(l != null, "replay " + run + " did not land on path #2's block (at " + relPos(ctx) + ")");
                check(useTicks(s).size() == hops, "replay " + run + " sent " + useTicks(s).size() + " warps, saved " + hops);
                check(!logHas(mr, "planning with the Interactive Map"), "replay " + run + " planned again");
                check(logHas(mr, "flying " + hops + " saved warp(s)"), "replay " + run + " did not fly the saved warps");
                for (Sample x : s) {
                    if (!useTicks(s).isEmpty() && x.tick() >= useTicks(s).get(0).tick()
                            && x.tick() <= useTicks(s).get(useTicks(s).size() - 1).tick()) {
                        check(x.shift(), "shift up between two path warps on tick " + x.tick());
                    }
                }
                check(waitFor(ctx, 40, () -> logHas(mr, "(path arrival)")), "the arrival node #2 did not fire");
            }
            check(plans(ctx) == plans0 + 1, "a replay ran the planner");
            // ---- await:1 and start on #1 through the editor ----
            tpRel(ctx, 4.5, 20.5, 0f, 0f);
            ctx.waitTicks(4);
            openEditor(ctx, 1);
            check(labels(ctx).stream().anyMatch(l -> l.startsWith("Re-plan Path")), "no Re-plan Path button");
            press(ctx, "Await Secrets");
            ctx.waitTicks(2);
            press(ctx, "1");
            ctx.waitTicks(2);
            press(ctx, "Start Node");
            ctx.waitTicks(2);
            press(ctx, "Save");
            ctx.waitTicks(5);
            check(plans(ctx) == plans0 + 1, "saving await/start re-planned an unmoved pair");
            setBlocks(ctx, Map.of(new int[]{4, F, 22}, Blocks.CHEST.defaultBlockState()));
            ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setStartFromStartNodeOnly", true));
            startSampling(ctx);
            long ma = LogTap.mark();
            tpRel(ctx, 6.5, 20.5, 0f, 0f);
            ctx.waitTicks(40);
            check(useTicks(stopSampling(ctx)).isEmpty(), "the path flew before its await:1 was met");
            check(logHas(ma, "armed node #1 (PATH, start)"), "the start path node did not arm");
            ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(3));   // an empty hand opens the chest
            ctx.waitTicks(2);
            rightClick(ctx, 4, F, 22, Direction.UP);
            waitFor(ctx, 40, () -> awaitMet(ma));
            ctx.runOnClient(mc -> mc.player.closeContainer());
            check(waitLanded(ctx, 24, 10, 200) != null, "the path did not fly after its await was met");
            ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setStartFromStartNodeOnly", false));
            // ---- a boom on #1's tile fires before the path (stack order: boom 2, path 7) ----
            setBlocks(ctx, Map.of(new int[]{6, F, 18}, Blocks.CRACKED_STONE_BRICKS.defaultBlockState(),
                    new int[]{6, F + 1, 18}, Blocks.CRACKED_STONE_BRICKS.defaultBlockState()));
            tpRel(ctx, 6.5, 20.5, 180f, 0f);
            ctx.waitTicks(4);
            long mb = LogTap.mark();
            cmd(ctx, "/ar add boom");   // fires the tile's stack: boom, then the path (await 1 again: a chest)
            ctx.waitTicks(10);
            // A NEW chest: the sim counts each chest once, as Hypixel does.
            setBlocks(ctx, Map.of(new int[]{4, F, 18}, Blocks.CHEST.defaultBlockState()));
            ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(3));
            ctx.waitTicks(2);
            check(!ctx.computeOnClient(mc -> mc.player.getLastSentInput().shift()), "sneak is held while the await waits");
            rightClick(ctx, 4, F, 18, Direction.UP);
            waitFor(ctx, 40, () -> awaitMet(mb));
            ctx.runOnClient(mc -> mc.player.closeContainer());
            check(waitLanded(ctx, 24, 10, 200) != null, "the boom + path stack did not end on path #2");
            int iBoom = logIndex(mb, "BOOM acted");
            int iPath = logIndex(mb, "PATH: flying");
            check(iBoom >= 0 && iPath > iBoom, "the stack did not fire boom before the path (" + iBoom + ", " + iPath + ")");
            check(blockAir(ctx, 6, F + 1, 18), "the stacked boom broke nothing");
            cmd(ctx, "/ar delete 3");
            ctx.waitTicks(3);
            // Await off again for the rest.
            openEditor(ctx, 1);
            press(ctx, "Await Secrets");
            ctx.waitTicks(2);
            press(ctx, "None");
            ctx.waitTicks(2);
            press(ctx, "Save");
            ctx.waitTicks(3);
            // ---- editing #2 (one block east) re-plans, and the replay lands on the new block ----
            int plansBefore = plans(ctx);
            long me = LogTap.mark();
            openEditor(ctx, 2);
            press(ctx, "X +1");
            ctx.waitTicks(1);
            press(ctx, "Save");
            boolean replanned = waitFor(ctx, 400, () -> logHas(me, "planned by the floor planner"));
            check(replanned && plans(ctx) == plansBefore + 1, "moving path #2 did not re-plan exactly once");
            // Which way is "east" in room terms depends on the rotation; find #2's new relative spot from the file.
            JsonObject p2 = fileNodes(ctx).get(1).getAsJsonObject();
            int nx = (int) Math.floor(p2.get("x").getAsDouble());
            int nz = (int) Math.floor(p2.get("z").getAsDouble());
            tpRel(ctx, 4.5, 20.5, 0f, 0f);
            ctx.waitTicks(10);
            tpRel(ctx, 6.5, 20.5, 0f, 0f);
            check(waitLanded(ctx, nx, nz, 200) != null, "after the edit the path did not land on #2's new block ("
                    + nx + "," + nz + "), at " + relPos(ctx));
            check(plans(ctx) == plansBefore + 1, "the replay after the edit planned again");
            waitFor(ctx, 60, () -> !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(EXECUTOR, "isRunning")));
            // ---- a saved hop's landing block is missing: the path holds ----
            JsonArray hopsArr = fileNodes(ctx).get(0).getAsJsonObject().getAsJsonArray("hops");
            String[] h0 = hopsArr.get(0).getAsString().trim().split("\\s+");
            int bx = Integer.parseInt(h0[5]);
            int by = Integer.parseInt(h0[6]);
            int bz = Integer.parseInt(h0[7]);
            BlockState was = blockState(ctx, bx, by, bz);
            tpRel(ctx, 4.5, 20.5, 0f, 0f);
            ctx.waitTicks(10);
            setBlocks(ctx, Map.of(new int[]{bx, by, bz}, Blocks.AIR.defaultBlockState()));
            long mw = LogTap.mark();
            startSampling(ctx);
            tpRel(ctx, 6.5, 20.5, 0f, 0f);
            ctx.waitTicks(50);
            check(useTicks(stopSampling(ctx)).isEmpty(), "a path warp went out at a missing landing block");
            check(logHas(mw, "Waiting for the block"), "no wait message for the missing hop block");
            setBlocks(ctx, Map.of(new int[]{bx, by, bz}, was));
            check(waitLanded(ctx, nx, nz, 200) != null, "the path did not carry on once the hop block was back");
        } finally {
            ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setStartFromStartNodeOnly", true));
        }
    }

    /** Record at rotation 0 (commands + a path pair), rebuild the room at 90, and replay onto the same relative blocks. */
    private void caseRotate(ClientGameTestContext ctx) {
        resetRoutes(ctx);
        arena(ctx, true);
        giveHotbar(ctx);
        tpRel(ctx, 6.5, 6.5, relYawTo(6, 6, 14, 6), pitchTo(6, 6, 14, 6));
        ctx.waitTicks(4);
        cmd(ctx, "/ar add ew start");
        check(waitLanded(ctx, 14, 6, 60) != null, "recording: the start ew did not land at (14,6)");
        tpRel(ctx, 14.5, 6.5, relYawTo(14, 6, 6, 22), pitchTo(14, 6, 6, 22));
        ctx.waitTicks(4);
        cmd(ctx, "/ar add ew");
        check(waitLanded(ctx, 6, 22, 60) != null, "recording: the second ew did not land at (6,22)");
        tpRel(ctx, 6.5, 20.5, 0f, 0f);
        ctx.waitTicks(4);
        cmd(ctx, "/ar add path");
        tpRel(ctx, 24.5, 10.5, 0f, 0f);
        ctx.waitTicks(4);
        long m = LogTap.mark();
        cmd(ctx, "/ar add path");
        check(waitFor(ctx, 400, () -> logHas(m, "planned by the floor planner")), "recording: the path was not planned");
        int plans = plans(ctx);
        int rot0 = frameRotation(ctx);

        buildRoom(ctx, 90);
        int rot1 = frameRotation(ctx);
        println("frame rotation " + rot0 + " -> " + rot1);
        check(rot0 != rot1, "the rebuilt room has the same rotation - the case tests nothing");
        arena(ctx, true);
        giveHotbar(ctx);
        check(fileNodes(ctx).size() == 4, "the route did not survive the rebuild");
        tpRel(ctx, 4.5, 6.5, 0f, 0f);
        ctx.waitTicks(10);
        tpRel(ctx, 6.5, 6.5, 0f, 0f);
        check(waitLanded(ctx, 14, 6, 80) != null, "rotated: the start ew did not land on relative (14,6), at " + relPos(ctx));
        check(waitLanded(ctx, 6, 22, 80) != null, "rotated: the second ew did not land on relative (6,22), at " + relPos(ctx));
        // The second ew lands on (6,22), one block from path #1 at (6,20) - walk is not ours; place him on it.
        ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setStartFromStartNodeOnly", false));
        try {
            tpRel(ctx, 4.5, 20.5, 0f, 0f);
            ctx.waitTicks(10);
            tpRel(ctx, 6.5, 20.5, 0f, 0f);
            check(waitLanded(ctx, 24, 10, 200) != null, "rotated: the saved path did not land on relative (24,10), at "
                    + relPos(ctx));
            check(plans(ctx) == plans, "rotated: the saved path was planned again");
        } finally {
            ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setStartFromStartNodeOnly", true));
        }
    }

    // ============================================================================================ world

    /** The first 1x1 room in library order with secrets that is not a puzzle/trap/blood/entrance/fairy room. */
    @SuppressWarnings("unchecked")
    // ============================================================================================ 2026-10-05 cases

    private static final String SIM_MOBS = "com.killer560.hub.roomsim.SimMobs";
    private static final String SIM_SCORE = "com.killer560.hub.roomsim.SimScore";
    private static final String SIM_MIMIC = "com.killer560.hub.roomsim.SimMimic";

    /** A floor lever (face FLOOR), off. */
    private static BlockState floorLever() {
        return Blocks.LEVER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.LeverBlock.FACE,
                        net.minecraft.world.level.block.state.properties.AttachFace.FLOOR)
                .setValue(net.minecraft.world.level.block.LeverBlock.POWERED, false);
    }

    private static boolean leverOn(ClientGameTestContext ctx, int x, int y, int z) {
        BlockState s = blockState(ctx, x, y, z);
        return s.is(Blocks.LEVER) && s.getValue(net.minecraft.world.level.block.LeverBlock.POWERED);
    }

    /** Pitch from a STANDING eye over block (fx,fz) to a point {@code h} above the floor top at (tx,tz)'s centre. */
    private static float standPitch(int fx, int fz, int tx, int tz, double h) {
        double horiz = Math.hypot(tx - fx, tz - fz);
        return (float) Math.toDegrees(Math.atan2(1.62 - h, horiz));
    }

    private static JsonObject handUse(int x, int z, float yaw, float pitch, boolean start, int await) {
        JsonObject o = node("USE_ITEM", x, z, yaw, pitch);
        if (start) {
            o.addProperty("start", true);
        }
        if (await > 0) {
            o.addProperty("awaitEnabled", true);
            o.addProperty("await", "SECRET");
            o.addProperty("amount", await);
        }
        return o;
    }

    private static void giveSlot(ClientGameTestContext ctx, int slot, String id) {
        AtomicReference<Boolean> given = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(uuid);
                sp.getInventory().setItem(slot, id == null ? ItemStack.EMPTY : (ItemStack) ModUnderTest.staticCall(SIM_ITEMS,
                        "build", new Class<?>[]{String.class}, new Object[]{id}));
                given.set(true);
            });
        });
        ctx.waitFor(mc -> given.get() != null, 200);
        ctx.waitTicks(5);
    }

    /** The server opens a three-row "Chest" window on him (a plain container: no distance check, like Hypixel's). */
    private static void openLateWindow(ClientGameTestContext ctx) {
        AtomicReference<Boolean> opened = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(uuid);
                opened.set(sp.openMenu(new net.minecraft.world.SimpleMenuProvider((id, inv, p) ->
                        net.minecraft.world.inventory.ChestMenu.threeRows(id, inv),
                        net.minecraft.network.chat.Component.literal("Chest"))).isPresent());
            });
        });
        ctx.waitFor(mc -> opened.get() != null, 200);
        check(Boolean.TRUE.equals(opened.get()), "the server could not open the late chest window");
    }

    /** Steps off (2 blocks -z), then onto the start node at (x,z): true once the route has started. */
    private static boolean arm(ClientGameTestContext ctx, int x, int z, float yaw, float pitch) {
        if (!keepRunMemory) {
            // Each arm is its own run unless a run-memory case says otherwise: the mod remembers a met await for the rest
            // of a run (RouteRunMemory), and the cases re-arm one node with a different secret each time.
            forgetRunMemory(ctx);
        }
        tpRel(ctx, x + 0.5, z - 1.5, yaw, pitch);
        ctx.waitTicks(10);
        long m = LogTap.mark();
        tpRel(ctx, x + 0.5, z + 0.5, yaw, pitch);
        return waitFor(ctx, 40, () -> logHas(m, "Started \""));
    }

    /** No secret bat - pending or alive - from an earlier case: one hit beside his made run 1 read "2 kill(s)" (1015a). */
    private static void clearSecretBats(ClientGameTestContext ctx) {
        serverRun(ctx, (server, sp) -> {
            ModUnderTest.staticCall("com.killer560.hub.roomsim.SimSecretItems", "reset");
            List<net.minecraft.world.entity.Entity> bats = new java.util.ArrayList<>();
            for (var e : server.overworld().getAllEntities()) {
                if (e instanceof net.minecraft.world.entity.ambient.Bat) {
                    bats.add(e);
                }
            }
            bats.forEach(net.minecraft.world.entity.Entity::discard);
        });
        ctx.waitTicks(2);
    }

    private static boolean running(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(EXECUTOR, "isRunning"));
    }

    private static void stopRoute(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            McCompat.setScreen(mc, null);
            ModUnderTest.staticCall(EXECUTOR, "stop", new Class<?>[]{String.class}, new Object[]{"test"});
        });
        ctx.waitTicks(3);
    }

    private static void serverRun(ClientGameTestContext ctx, java.util.function.BiConsumer<net.minecraft.server.MinecraftServer,
            net.minecraft.server.level.ServerPlayer> task) {
        AtomicReference<Boolean> done = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                try {
                    task.accept(server, server.getPlayerList().getPlayer(uuid));
                } finally {
                    done.set(true);
                }
            });
        });
        ctx.waitFor(mc -> done.get() != null, 200);
        ctx.waitTicks(2);
    }

    private static BlockPos realNow(ClientGameTestContext ctx, int x, int y, int z) {
        return ctx.computeOnClient(mc -> real(ModUnderTest.staticCall(FRAME, "current"), x, y, z));
    }

    private static void spawnBatAt(ClientGameTestContext ctx, int x, int y, int z) {
        BlockPos p = realNow(ctx, x, y, z);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_MOBS, "spawn",
                new Class<?>[]{Minecraft.class, BlockPos.class, enumClass(SIM_MOBS + "$Kind")},
                new Object[]{mc, p, ModUnderTest.enumValue(SIM_MOBS + "$Kind", "BAT")}));
    }

    private static Class<?> enumClass(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    private static void setArEnum(ClientGameTestContext ctx, String setter, String enumName, String constant) {
        ctx.runOnClient(mc -> ModUnderTest.call(ModUnderTest.config(AR_CONFIG), setter,
                new Class<?>[]{enumClass(AR_CONFIG + "$" + enumName)},
                new Object[]{ModUnderTest.enumValue(AR_CONFIG + "$" + enumName, constant)}));
    }

    private static int countOut(List<Sample> s, String packet) {
        int n = 0;
        for (Sample x : s) {
            n += (int) x.out().stream().filter(packet::equals).count();
        }
        return n;
    }

    /**
     * Empty-hand use nodes: a start node aimed at a floor lever flips it on its firing tick (an empty slot selected,
     * use_item_on and no use_item); one aimed at a secret chest opens it and the sim counts the secret; one with
     * nothing in reach stops with "nothing to click"; recording an empty-hand lever click makes such a node.
     */
    private void caseInteract(ClientGameTestContext ctx) {
        resetRoutes(ctx);
        arena(ctx, false);
        giveHotbar(ctx);
        // ---- the lever ----
        setBlocks(ctx, Map.of(new int[]{10, F, 12}, floorLever()));
        check(!leverOn(ctx, 10, F, 12), "the test lever did not start off: " + blockState(ctx, 10, F, 12));
        float lp = standPitch(10, 10, 10, 12, 0.1);
        writeRoute(ctx, List.of(handUse(10, 10, 0f, lp, true, 0)));
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));   // the AOTV: the node must pick slot 4 (empty)
        ctx.waitTicks(3);
        tpRel(ctx, 10.5, 8.5, 0f, lp);
        ctx.waitTicks(10);
        long m = LogTap.mark();
        startSampling(ctx);
        tpRel(ctx, 10.5, 10.5, 0f, lp);
        boolean flipped = waitFor(ctx, 30, () -> leverOn(ctx, 10, F, 12));
        List<Sample> s = stopSampling(ctx);
        check(flipped, "the empty-hand start node did not flip the lever (" + blockState(ctx, 10, F, 12) + ")");
        check(logHas(m, "USE_ITEM acted 0 tick(s) after firing") && logHas(m, "empty hand: clicked"),
                "the empty-hand use did not act on its firing tick");
        check(countOut(s, "use_item") == 0, "an empty-hand use sent " + countOut(s, "use_item") + " use_item packet(s)");
        check(countOut(s, "use_item_on") >= 1, "an empty-hand use sent no use_item_on");
        int sel = ctx.computeOnClient(mc -> mc.player.getInventory().getSelectedSlot());
        check(sel == 3, "the empty-hand use clicked from slot " + (sel + 1) + ", not the empty slot 4");
        println("lever flipped by the empty-hand node; slot " + (sel + 1));

        // ---- a secret chest, no await ----
        setBlocks(ctx, Map.of(new int[]{16, F, 12}, Blocks.CHEST.defaultBlockState()));
        float cp = standPitch(16, 10, 16, 12, 0.44);
        writeRoute(ctx, List.of(handUse(16, 10, 0f, cp, true, 0)));
        int before = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(SIM_SCORE, "secretsFound"));
        check(arm(ctx, 16, 10, 0f, cp), "the chest node's route did not start");
        boolean counted = waitFor(ctx, 40, () -> ctx.computeOnClient(mc ->
                (Integer) ModUnderTest.staticCall(SIM_SCORE, "secretsFound")) > before);
        check(counted, "the empty-hand node's chest click was not counted as a secret by the sim");
        ctx.runOnClient(mc -> mc.player.closeContainer());
        ctx.waitTicks(5);

        // ---- nothing in reach ----
        writeRoute(ctx, List.of(handUse(10, 20, 0f, -80f, true, 0)));
        long m3 = LogTap.mark();
        check(arm(ctx, 10, 20, 0f, -80f), "the no-target node's route did not start");
        check(waitFor(ctx, 20, () -> logHas(m3, "nothing to click")), "a use node with no block in sight did not stop "
                + "with \"nothing to click\"");

        // ---- recording an empty-hand lever click ----
        resetRoutes(ctx);
        setBlocks(ctx, Map.of(new int[]{10, F, 12}, floorLever()));
        tpRel(ctx, 10.5, 10.5, 0f, lp);
        ctx.waitTicks(5);
        cmd(ctx, "/ar start record");
        ctx.waitTicks(5);
        ctx.runOnClient(mc -> {
            mc.player.setYRot(lookYaw);
            mc.player.setXRot(lookPitch);
            mc.player.getInventory().setSelectedSlot(3);
        });
        ctx.waitTicks(2);
        ctx.getInput().holdKey(options -> options.keyUse);
        ctx.waitTicks(2);
        ctx.getInput().releaseKey(options -> options.keyUse);
        ctx.waitTicks(5);
        boolean recFlip = leverOn(ctx, 10, F, 12);
        cmd(ctx, "/ar stop record");
        ctx.waitTicks(5);
        JsonArray nodes = fileNodes(ctx);
        JsonObject use = null;
        for (JsonElement e : nodes) {
            if ("USE_ITEM".equals(e.getAsJsonObject().get("type").getAsString())) {
                use = e.getAsJsonObject();
            }
        }
        check(recFlip, "the recorded right click did not flip the lever (" + blockState(ctx, 10, F, 12) + ")");
        check(use != null, "recording an empty-hand lever click made no use node: " + nodes);
        check(!use.has("item") || use.get("item").getAsString().isEmpty(), "the recorded use node has an item: " + use);
        println("recorded: " + use);
    }

    /** One await sub-run: arm the start node at (12,12), fire {@code event}, and say whether its await:1 was met. */
    private static String awaitRun(ClientGameTestContext ctx, String label, boolean expect, Runnable event) {
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(3));
        if (!arm(ctx, 12, 12, 0f, 80f)) {
            return label + ": the route did not start";
        }
        long m = LogTap.mark();
        ctx.waitTicks(10);
        event.run();
        boolean met = waitFor(ctx, 60, () -> logHas(m, "secrets met"));
        ctx.runOnClient(mc -> mc.player.closeContainer());
        println("await " + label + ": met=" + met + " (expected " + expect + ")");
        stopRoute(ctx);
        return met == expect ? null : label + (expect ? " did not satisfy" : " satisfied") + " await:1";
    }

    /**
     * Await events: the sim's bar rising with no click, a lever toggled by the server ("a teammate"), and a button
     * click do NOT satisfy await:1; our lever / chest / skull click, our own item pickup and a secret bat appearing
     * 5 blocks away each do; a bat 10 blocks away, or one already there, does not.
     */
    private void caseAwait(ClientGameTestContext ctx) {
        resetRoutes(ctx);
        arena(ctx, false);
        giveHotbar(ctx);
        BlockState button = Blocks.STONE_BUTTON.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ButtonBlock.FACE,
                        net.minecraft.world.level.block.state.properties.AttachFace.FLOOR);
        setBlocks(ctx, Map.of(new int[]{14, F, 13}, floorLever(), new int[]{10, F, 13}, floorLever(),
                new int[]{14, F, 11}, button, new int[]{10, F, 11}, Blocks.CHEST.defaultBlockState(),
                new int[]{11, F, 14}, Blocks.PLAYER_HEAD.defaultBlockState()));
        // The node's own action: an empty-hand click straight down at the floor - harmless.
        writeRoute(ctx, List.of(handUse(12, 12, 0f, 80f, true, 1)));
        BlockPos lever2 = realNow(ctx, 10, F, 13);
        List<String> wrong = new ArrayList<>();
        java.util.function.Consumer<String> note = r -> {
            if (r != null) {
                wrong.add(r);
            }
        };
        note.accept(awaitRun(ctx, "sim bar rise with no click", false, () -> ctx.runOnClient(mc ->
                ModUnderTest.staticCall(SIM_SCORE, "secretFound", new Class<?>[]{BlockPos.class},
                        new Object[]{mc.player.blockPosition()}))));
        note.accept(awaitRun(ctx, "a lever toggled by the server (a teammate)", false, () -> serverRun(ctx,
                (server, sp) -> server.overworld().setBlockAndUpdate(lever2, server.overworld().getBlockState(lever2)
                        .cycle(net.minecraft.world.level.block.LeverBlock.POWERED)))));
        note.accept(awaitRun(ctx, "our button click", false, () -> rightClick(ctx, 14, F, 11, Direction.UP)));
        note.accept(awaitRun(ctx, "our lever click", true, () -> rightClick(ctx, 14, F, 13, Direction.UP)));
        note.accept(awaitRun(ctx, "our chest click", true, () -> rightClick(ctx, 10, F, 11, Direction.UP)));
        note.accept(awaitRun(ctx, "our skull click", true, () -> rightClick(ctx, 11, F, 14, Direction.UP)));
        note.accept(awaitRun(ctx, "our own item pickup", true, () -> serverRun(ctx, (server, sp) -> {
            var item = new net.minecraft.world.entity.item.ItemEntity(server.overworld(), sp.getX(), sp.getY() + 0.2,
                    sp.getZ(), new ItemStack(net.minecraft.world.item.Items.BONE));
            item.setNoPickUpDelay();
            server.overworld().addFreshEntity(item);
        })));
        note.accept(awaitRun(ctx, "a secret bat 5 blocks away", true, () -> spawnBatAt(ctx, 12, F - 1, 17)));
        note.accept(awaitRun(ctx, "a secret bat 13 blocks away", false, () -> spawnBatAt(ctx, 12, F - 1, 25)));
        spawnBatAt(ctx, 15, F - 1, 12);
        ctx.waitTicks(30);
        note.accept(awaitRun(ctx, "a bat already there", false, () -> { }));
        check(wrong.isEmpty(), String.join("; ", wrong));
    }

    /** A real left click through the game's input (Fabric TestInput -> MouseHandler -> the attack KeyMapping), held
     *  {@code ticks} ticks, as his mouse sends it. */
    private static void leftClick(ClientGameTestContext ctx, int ticks) {
        ctx.getInput().holdKey(options -> options.keyAttack);
        ctx.waitTicks(ticks);
        ctx.getInput().releaseKey(options -> options.keyAttack);
    }

    /**
     * killer560, 2026-10-06: "if I left click while on an await node then it should perform the teleport even if it
     * didn't grab a secret yet". Control first: with no route running the same real left click on the floor sends a
     * swing (so the counter can see one). Then an await:1 etherwarp start node waits with no secret - nothing goes
     * out - and a real left click fires its warp: exactly one use, no swing and no dig anywhere in the window, and the
     * log says the click (not a secret) met the await.
     */
    private void caseAwaitSkip(ClientGameTestContext ctx) {
        resetRoutes(ctx);
        arena(ctx, false);
        giveHotbar(ctx);
        // ---- control: no route, the same click is a vanilla left click ----
        tpRel(ctx, 10.5, 10.5, 0f, 80f);
        ctx.waitTicks(5);
        startSampling(ctx);
        leftClick(ctx, 3);
        ctx.waitTicks(5);
        List<Sample> control = stopSampling(ctx);
        int ctlSwing = countOut(control, "swing");
        int ctlDig = countOut(control, "player_action");
        println("awaitskip control: a left click with no route sent " + ctlSwing + " swing, " + ctlDig + " player_action");
        check(ctlSwing >= 1, "the control left click sent no swing - the click never reached the game, so a clean "
                + "window below would prove nothing");

        // ---- the await node ----
        JsonObject w = ew(6, 6, 14, 6, true);
        w.addProperty("awaitEnabled", true);
        w.addProperty("await", "SECRET");
        w.addProperty("amount", 1);
        writeRoute(ctx, List.of(w));
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(3));
        // He looks down at the floor, so a vanilla left click would hit a block (swing + START_DESTROY_BLOCK).
        check(arm(ctx, 6, 6, relYawTo(6, 6, 14, 6), 80f), "the await route did not start");
        long m = LogTap.mark();
        startSampling(ctx);
        ctx.waitTicks(30);
        check(running(ctx), "the route is not running while it waits on its await");
        check(useTicks(stopSampling(ctx)).isEmpty(), "a use went out while the node waited on its await (no secret yet)");
        check(waitLanded(ctx, 14, 6, 1) == null, "he warped before any secret or click");
        check(!logHas(m, "secrets met"), "the await was met with no secret");

        // ---- attacks and digs the MOD sends are not his clicks: they must not skip it ----
        // A still pig 2 blocks to his side (a vanilla mob: no secret, no bat), then a dig, a swing and an attack on the
        // pig made the way Breaker Aura / Secret Aura / Auto Clear make them - straight through the game mode.
        BlockPos pigAt = realNow(ctx, 6, F, 8);
        AtomicReference<java.util.UUID> pigId = new AtomicReference<>();
        serverRun(ctx, (server, sp) -> {
            var pig = dev.testkit.compat.McEntities.PIG.create(server.overworld(),
                    net.minecraft.world.entity.EntitySpawnReason.COMMAND);
            pig.setPos(pigAt.getX() + 0.5, pigAt.getY(), pigAt.getZ() + 0.5);
            pig.setNoAi(true);
            pig.setInvulnerable(true);
            server.overworld().addFreshEntity(pig);
            pigId.set(pig.getUUID());
        });
        check(waitFor(ctx, 100, () -> ctx.computeOnClient(mc -> pigOnClient(mc, pigId.get()) != null)),
                "the pig never reached the client - the mob cases below would prove nothing");
        BlockPos floorAhead = realNow(ctx, 7, F - 1, 6);
        startSampling(ctx);
        ctx.runOnClient(mc -> {
            mc.gameMode.startDestroyBlock(floorAhead, Direction.UP);
            mc.gameMode.stopDestroyBlock();
            mc.player.swing(InteractionHand.MAIN_HAND);
            mc.gameMode.attack(mc.player, pigOnClient(mc, pigId.get()));
        });
        ctx.waitTicks(20);
        List<Sample> modSent = stopSampling(ctx);
        println("awaitskip mod-sent: " + countOut(modSent, "player_action") + " player_action, "
                + countOut(modSent, "swing") + " swing, " + countOut(modSent, "attack") + " attack");
        check(countOut(modSent, "attack") >= 1 && countOut(modSent, "swing") >= 1,
                "the mod-style attack sent nothing - 'it did not skip' below would prove nothing");
        check(!logHas(m, "left click skipped"), "an attack the mod sent (not his click) skipped the await");
        check(useTicks(modSent).isEmpty() && waitLanded(ctx, 14, 6, 1) == null,
                "the node fired after a mod-sent attack, with no click of his");

        // ---- his click with the pig under the crosshair still skips ----
        ctx.runOnClient(mc -> {
            var pig = pigOnClient(mc, pigId.get());
            Vec3 eye = mc.player.getEyePosition();
            Vec3 d = pig.getBoundingBox().getCenter().subtract(eye);
            mc.player.setYRot((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
            mc.player.setXRot((float) Math.toDegrees(Math.atan2(-d.y, Math.hypot(d.x, d.z))));
        });
        ctx.waitTicks(3);
        boolean onPig = ctx.computeOnClient(mc -> mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult e
                && e.getEntity().getUUID().equals(pigId.get()));
        check(onPig, "the pig is not under the crosshair - the mob case would prove nothing");

        startSampling(ctx);
        leftClick(ctx, 3);
        Vec3 landed = waitLanded(ctx, 14, 6, 60);
        ctx.waitTicks(5);
        List<Sample> s = stopSampling(ctx);
        StringBuilder trace = new StringBuilder();
        for (Sample x : s) {
            if (!x.out().isEmpty()) {
                trace.append(String.format(" | t%d %s", x.tick(), x.out()));
            }
        }
        println("awaitskip window:" + trace);
        check(logHas(m, "left click skipped the await"), "the left click did not skip the await (log)");
        check(landed != null, "the left click did not fire the etherwarp (at " + relPos(ctx) + ")");
        check(useTicks(s).size() == 1, "the skip sent " + useTicks(s).size() + " use_item packet(s), expected 1");
        // The etherwarp's own use swings the hand (a use that succeeds client-side does, here the sim's ability hook), in
        // the use's tick. Any other swing is the left click getting through.
        check(strayClickSwings(s) == 0, "the swallowed left click still sent " + strayClickSwings(s)
                + " swing(s) of its own:" + trace);
        check(countOut(s, "player_action") == 0, "the swallowed left click still sent " + countOut(s, "player_action")
                + " player_action (dig) packet(s):" + trace);
        check(countOut(s, "attack") == 0 && countOut(s, "interact") == 0, "the swallowed left click still hit the pig:"
                + trace);
        check(logHas(m, "(0/1 secrets)"), "the skip line does not say no secret was got");
        stopRoute(ctx);
        serverRun(ctx, (server, sp) -> {
            var e = server.overworld().getEntity(pigId.get());
            if (e != null) {
                e.discard();
            }
        });

        // ---- a crypt node's wait for its kill: his left click ends it too ----
        giveSlot(ctx, 4, "HYPERION");
        setArEnum(ctx, "setCryptWeapon", "CryptWeapon", "HYPERION");
        ctx.runOnClient(mc -> ModUnderTest.call(ModUnderTest.config(AR_CONFIG), "setCryptAttackTicks",
                new Class<?>[]{int.class}, new Object[]{200}));
        try {
            writeRoute(ctx, List.of(cryptNode(10, 20, 0f, 0f, true, 0)));
            ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(3));
            tpRel(ctx, 10.5, 16.5, 0f, 0f);
            ctx.waitTicks(10);
            long mc0 = LogTap.mark();
            tpRel(ctx, 10.5, 20.5, 0f, 0f);
            check(waitFor(ctx, 60, () -> logHas(mc0, "holding use")), "the crypt node never started holding use");
            ctx.waitTicks(10);
            check(!logHas(mc0, "moving on"), "the crypt node moved on before the click");
            leftClick(ctx, 3);
            boolean skipped = waitFor(ctx, 10, () -> logHas(mc0, "left click skipped the kill wait"));
            ctx.waitTicks(3);
            boolean useUp = ctx.computeOnClient(mc -> !mc.options.keyUse.isDown());
            // Vanilla's right click on the floor with the Hyperion is two packets on Hypixel (the block, then the use,
            // which pairs it). The sim's ability hook once ended it after the block half, so util/RightClick held that
            // click unpaired for good and 96-ar-chain, run next, waited a tick at every corner (mod ar-merge-regress-1015).
            boolean pairedUp = ctx.computeOnClient(mc -> !(Boolean) ModUnderTest.staticCall("com.killer560.hub.util.RightClick",
                    "unpaired"));
            println("awaitskip crypt: skipped=" + skipped + ", use key up=" + useUp + ", every click paired=" + pairedUp);
            check(skipped, "his left click did not end the crypt node's wait for a kill");
            check(useUp, "the crypt node left the use key held after the skip");
            check(pairedUp, "the crypt node's Hyperion right clicks on the floor left a block click unpaired - vanilla's "
                    + "right click sent the block half without its use half");
            check(!logHas(mc0, "up, moving on"), "the crypt node ran out its attack time instead of being skipped");
        } finally {
            ctx.runOnClient(mc -> ModUnderTest.call(ModUnderTest.config(AR_CONFIG), "setCryptAttackTicks",
                    new Class<?>[]{int.class}, new Object[]{100}));
            stopRoute(ctx);
        }
    }

    /** Swings beyond one per use_item in the same tick: a use that succeeds client-side swings the hand itself. */
    private static int strayClickSwings(List<Sample> s) {
        int stray = 0;
        for (Sample x : s) {
            long swings = x.out().stream().filter("swing"::equals).count();
            long uses = x.out().stream().filter("use_item"::equals).count();
            stray += (int) Math.max(0, swings - uses);
        }
        return stray;
    }

    private static net.minecraft.world.entity.Entity pigOnClient(Minecraft mc, java.util.UUID id) {
        if (id == null || mc.level == null) {
            return null;
        }
        for (var e : mc.level.entitiesForRendering()) {
            if (id.equals(e.getUUID())) {
                return e;
            }
        }
        return null;
    }

    /**
     * Secret Waypoints, killer560 2026-10-06: "clicking levers doesn't hide them from secret waypoints". Two floor
     * levers in the room are lever waypoints; his click on one hides that one (and keeps it hidden across rebuilds),
     * and leaves the other lever and every other waypoint alone.
     */
    private void caseLeverWaypoint(ClientGameTestContext ctx) {
        String swCfg = "com.killer560.hub.secretwaypoints.SecretWaypointsConfig";
        String sw = "com.killer560.hub.secretwaypoints.SecretWaypointsFeature";
        resetRoutes(ctx);
        arena(ctx, false);
        giveHotbar(ctx);
        Object cfg = ctx.computeOnClient(mc -> ModUnderTest.config(swCfg));
        boolean wasOn = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.call(cfg, "isEnabled", new Class<?>[]{},
                new Object[]{}));
        try {
            ctx.runOnClient(mc -> ModUnderTest.set(cfg, "setEnabled", true));
            setBlocks(ctx, Map.of(new int[]{18, F, 14}, floorLever(), new int[]{18, F, 18}, floorLever()));
            BlockPos a = realNow(ctx, 18, F, 14);
            BlockPos b = realNow(ctx, 18, F, 18);
            tpRel(ctx, 18.5, 12.5, 0f, 40f);
            ctx.runOnClient(mc -> ModUnderTest.staticCall(sw, "invalidateCache"));
            ctx.waitTicks(10);
            List<BlockPos> before = leverWaypoints(ctx, sw);
            int othersBefore = otherWaypoints(ctx, sw);
            println("leverwp before: levers " + before + ", other waypoints " + othersBefore);
            check(before.contains(a) && before.contains(b), "the two levers are not lever waypoints before any click "
                    + "(found " + before + ", wanted " + a + " and " + b + ") - nothing below would mean anything");
            rightClick(ctx, 18, F, 14, Direction.UP);
            check(leverOn(ctx, 18, F, 14), "the click did not flip lever A - it never reached the game");
            // Past the 1 s cache life, so the lever scan has rebuilt at least once since the click.
            ctx.waitTicks(30);
            ctx.runOnClient(mc -> ModUnderTest.staticCall(sw, "invalidateCache"));
            ctx.waitTicks(5);
            List<BlockPos> after = leverWaypoints(ctx, sw);
            int othersAfter = otherWaypoints(ctx, sw);
            println("leverwp after clicking A: levers " + after + ", other waypoints " + othersAfter);
            check(!after.contains(a), "the clicked lever's waypoint is still drawn: " + after);
            check(after.contains(b), "clicking lever A hid lever B's waypoint: " + after);
            check(othersAfter == othersBefore, "clicking a lever changed the other waypoints: " + othersBefore + " -> "
                    + othersAfter);
            // Clicking it back off is still the same lever: it stays hidden.
            rightClick(ctx, 18, F, 14, Direction.UP);
            ctx.waitTicks(30);
            check(!leverWaypoints(ctx, sw).contains(a), "lever A's waypoint came back after a second click");
        } finally {
            ctx.runOnClient(mc -> ModUnderTest.set(cfg, "setEnabled", wasOn));
        }
    }

    @SuppressWarnings("unchecked")
    private static List<BlockPos> leverWaypoints(ClientGameTestContext ctx, String sw) {
        return ctx.computeOnClient(mc -> new ArrayList<>((List<BlockPos>) ModUnderTest.staticCall(sw, "cachedPositions",
                new Class<?>[]{String.class}, new Object[]{"LEVER"})));
    }

    @SuppressWarnings("unchecked")
    private static int otherWaypoints(ClientGameTestContext ctx, String sw) {
        int n = 0;
        for (String k : new String[]{"CHEST", "ITEM", "WITHER", "BAT"}) {
            n += ctx.computeOnClient(mc -> ((List<BlockPos>) ModUnderTest.staticCall(sw, "cachedPositions",
                    new Class<?>[]{String.class}, new Object[]{k})).size());
        }
        return n;
    }

    /** /ar add's modifier completion fills "await:" with nothing after the colon (killer560, 2026-10-06). */
    private void caseComplete(ClientGameTestContext ctx) {
        List<String> afterType = ctx.computeOnClient(mc -> suggest(mc, "ar add ew "));
        List<String> partial = ctx.computeOnClient(mc -> suggest(mc, "ar add ew aw"));
        List<String> alias = ctx.computeOnClient(mc -> suggest(mc, "autoroutes add path start "));
        println("complete: 'ar add ew ' -> " + afterType + ", 'ar add ew aw' -> " + partial
                + ", 'autoroutes add path start ' -> " + alias);
        check(afterType.contains("await:") && afterType.contains("start"), "after the type: " + afterType);
        check(partial.equals(List.of("await:")), "'aw' completes to " + partial + ", expected exactly [await:]");
        check(alias.equals(List.of("await:")), "after start, /autoroutes offers " + alias + ", expected [await:]");
        for (List<String> l : List.of(afterType, partial, alias)) {
            for (String x : l) {
                check(!x.contains("await:x"), "a placeholder is still offered: " + l);
            }
        }
    }

    private static List<String> suggest(net.minecraft.client.Minecraft mc, String input) {
        var dispatcher = mc.getConnection().getCommands();
        var parse = dispatcher.parse(input, mc.getConnection().getSuggestionsProvider());
        try {
            var s = dispatcher.getCompletionSuggestions(parse).get(5, java.util.concurrent.TimeUnit.SECONDS);
            List<String> out = new ArrayList<>();
            s.getList().forEach(x -> out.add(x.getText()));
            return out;
        } catch (Exception e) {
            return List.of("<error " + e + ">");
        }
    }

    /** Places a trapped chest at relative (x,F,z) and makes it the sim's mimic (not yet opened). */
    private static void makeMimic(ClientGameTestContext ctx, int x, int z) {
        setBlocks(ctx, Map.of(new int[]{x, F, z}, Blocks.TRAPPED_CHEST.defaultBlockState()));
        BlockPos p = realNow(ctx, x, F, z);
        try {
            Class<?> c = Class.forName(SIM_MIMIC);
            var f = c.getDeclaredField("mimic");
            f.setAccessible(true);
            f.set(null, p);
            var found = c.getDeclaredField("found");
            found.setAccessible(true);
            found.set(null, false);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static boolean mimicAlive(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> ModUnderTest.staticCall(SIM_MIMIC, "mimicMob")) != null;
    }

    private static void killMimicOnServer(ClientGameTestContext ctx) {
        Object id = ctx.computeOnClient(mc -> ModUnderTest.staticCall(SIM_MIMIC, "mimicMob"));
        if (id == null) {
            return;
        }
        serverRun(ctx, (server, sp) -> {
            var e = server.overworld().getEntity((java.util.UUID) id);
            if (e instanceof net.minecraft.world.entity.LivingEntity le) {
                le.hurtServer(server.overworld(), server.overworld().damageSources().playerAttack(sp), 1000f);
            }
        });
    }

    /**
     * Kill Mimic: Hyperion = exactly one use straight down, the mimic dies; Spirit Sceptre = uses until it dies; Off with
     * an await:1 route = nothing used, the await waits until the mimic is killed (by the test) and then is met; Hyperion
     * with an await:1 route = the kill satisfies it; no wither blade = a chat line and nothing else.
     */
    private void caseMimic(ClientGameTestContext ctx) {
        resetRoutes(ctx);
        arena(ctx, false);
        giveHotbar(ctx);
        giveSlot(ctx, 4, "HYPERION");
        giveSlot(ctx, 5, "BAT_WAND");
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(3));
        try {
            // ---- Hyperion, by hand ----
            setArEnum(ctx, "setKillMimic", "KillMimic", "HYPERION");
            makeMimic(ctx, 14, 14);
            tpRel(ctx, 14.5, 12.5, 0f, 30f);
            ctx.waitTicks(5);
            long m = LogTap.mark();
            startSampling(ctx);
            rightClick(ctx, 14, F, 14, Direction.NORTH);
            boolean killed = waitFor(ctx, 60, () -> logHas(m, "Mimic of the trapped chest"));
            ctx.waitTicks(5);
            List<Sample> s = stopSampling(ctx);
            check(logHas(m, "watching for its mimic"), "the trapped-chest click was not seen");
            check(killed, "Kill Mimic (Hyperion) did not kill the mimic (alive " + mimicAlive(ctx) + ")");
            check(countOut(s, "use_item") == 1, "Kill Mimic (Hyperion) sent " + countOut(s, "use_item")
                    + " use_item packet(s), expected exactly 1");
            check(logHas(m, "Hyperion used straight down (use 1"), "no straight-down Hyperion use logged");
            int sel = ctx.computeOnClient(mc -> mc.player.getInventory().getSelectedSlot());
            check(sel == 3, "Kill Mimic did not put slot 4 back (holding slot " + (sel + 1) + ")");
            println("hyperion: killed, 1 use");

            // ---- Spirit Sceptre, by hand ----
            setArEnum(ctx, "setKillMimic", "KillMimic", "SPIRIT_SCEPTRE");
            makeMimic(ctx, 14, 18);
            tpRel(ctx, 14.5, 16.5, 0f, 30f);
            ctx.waitTicks(5);
            long m2 = LogTap.mark();
            startSampling(ctx);
            rightClick(ctx, 14, F, 18, Direction.NORTH);
            boolean killed2 = waitFor(ctx, 120, () -> logHas(m2, "Mimic of the trapped chest"));
            ctx.waitTicks(5);
            s = stopSampling(ctx);
            check(killed2, "Kill Mimic (Spirit Sceptre) did not kill the mimic");
            check(countOut(s, "use_item") >= 1, "Kill Mimic (Spirit Sceptre) used nothing");
            println("sceptre: killed after " + countOut(s, "use_item") + " use(s)");

            // ---- Off, with an await:1 route: the click is not the secret, the kill is ----
            setArEnum(ctx, "setKillMimic", "KillMimic", "OFF");
            makeMimic(ctx, 14, 22);
            writeRoute(ctx, List.of(handUse(13, 20, 0f, 80f, true, 1)));
            check(arm(ctx, 13, 20, 0f, 80f), "the await route did not start (Off)");
            long m3 = LogTap.mark();
            startSampling(ctx);
            rightClick(ctx, 14, F, 22, Direction.NORTH);
            ctx.waitTicks(40);
            s = stopSampling(ctx);
            check(!logHas(m3, "secrets met"), "the trapped-chest click itself satisfied the await");
            check(countOut(s, "use_item") == 0, "Kill Mimic Off still used an item");
            check(mimicAlive(ctx), "the mimic died with Kill Mimic off");
            killMimicOnServer(ctx);
            check(waitFor(ctx, 40, () -> logHas(m3, "secrets met")), "the mimic's death did not satisfy the await");
            check(logHas(m3, "mimic killed (your trapped chest"), "the await was met by something other than the mimic");
            ctx.waitTicks(10);
            stopRoute(ctx);

            // ---- Hyperion with an await:1 route ----
            setArEnum(ctx, "setKillMimic", "KillMimic", "HYPERION");
            makeMimic(ctx, 20, 22);
            writeRoute(ctx, List.of(handUse(19, 20, 0f, 80f, true, 1)));
            check(arm(ctx, 19, 20, 0f, 80f), "the await route did not start (Hyperion)");
            long m4 = LogTap.mark();
            rightClick(ctx, 20, F, 22, Direction.NORTH);
            check(waitFor(ctx, 60, () -> logHas(m4, "secrets met")), "Kill Mimic's kill did not satisfy the await");
            check(logHas(m4, "Hyperion used straight down"), "Kill Mimic did not act inside the route");
            ctx.waitTicks(10);
            stopRoute(ctx);

            // ---- no wither blade ----
            giveSlot(ctx, 4, null);
            makeMimic(ctx, 20, 16);
            tpRel(ctx, 20.5, 14.5, 0f, 30f);
            ctx.waitTicks(5);
            long m5 = LogTap.mark();
            rightClick(ctx, 20, F, 16, Direction.NORTH);
            ctx.waitTicks(10);
            check(logHas(m5, "no wither blade in the hotbar"), "no chat line for a missing wither blade");
            check(!logHas(m5, "used straight down"), "something was used with no wither blade");
            waitFor(ctx, 20, () -> mimicAlive(ctx));
            killMimicOnServer(ctx);
            ctx.waitTicks(30);
        } finally {
            setArEnum(ctx, "setKillMimic", "KillMimic", "OFF");
        }
    }

    /** A 2x2 smooth-stone-slab crypt lid on the floor at relative (x..x+1, F, z..z+1). */
    private static void slabCrypt(ClientGameTestContext ctx, int x, int z) {
        BlockState slab = Blocks.SMOOTH_STONE_SLAB.defaultBlockState();
        setBlocks(ctx, Map.of(new int[]{x, F, z}, slab, new int[]{x + 1, F, z}, slab, new int[]{x, F, z + 1}, slab,
                new int[]{x + 1, F, z + 1}, slab));
    }

    private static JsonObject cryptNode(int x, int z, float yaw, float pitch, boolean start, int await) {
        JsonObject o = node("CRYPT", x, z, yaw, pitch);
        if (start) {
            o.addProperty("start", true);
        }
        if (await > 0) {
            o.addProperty("awaitEnabled", true);
            o.addProperty("await", "SECRET");
            o.addProperty("amount", await);
        }
        return o;
    }

    private static int cryptsBlown(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(SIM_SCORE, "cryptsBlown"));
    }

    /**
     * Crypt nodes: boom + crypt on one tile blows a slab crypt and kills its undead with each weapon; a crypt await is
     * not met by a secret; a secret await is not met by our crypt kill; boom + crypt await:1 + ew await:1 on one tile
     * runs boom, crypt kill, waits for a secret click, then warps.
     */
    /**
     * killer560 (2026-10-13): "add a bat node. This functions just like crypts ... but is for bats." A secret bat waits
     * three blocks from a lone bat node (start); he walks up (it appears after 5 ticks in range), steps on the node, and
     * the node swaps to the Bat Weapon, uses it straight down and finishes on the bat's death - for Hyperion and Spirit
     * Sceptre both. Then a bat node with no bat near times out ("up, moving on") instead of counting anything else.
     */
    private void caseBatNode(ClientGameTestContext ctx) {
        resetRoutes(ctx);
        clearSecretBats(ctx);
        arena(ctx, false);
        giveHotbar(ctx);
        giveSlot(ctx, 4, "HYPERION");
        giveSlot(ctx, 5, "BAT_WAND");
        try {
            String[] weapons = {"HYPERION", "SPIRIT_SCEPTRE"};
            int[] xs = {19, 8};
            int[] wantSlot = {4, 5};
            for (int i = 0; i < 2; i++) {
                int x = xs[i];
                String weapon = weapons[i];
                setArEnum(ctx, "setBatWeapon", "CryptWeapon", weapon);
                BlockPos spot = realNow(ctx, x, F + 2, 13);
                ctx.runOnClient(mc -> mc.getSingleplayerServer().execute(() -> ModUnderTest.staticCall(
                        "com.killer560.hub.roomsim.SimSecretItems", "registerBat", new Class<?>[]{BlockPos.class},
                        new Object[]{spot})));
                JsonObject bat = node("BAT", x, 10, 0f, 30f);
                bat.addProperty("start", true);
                // The file can load seconds after the write; it then armed before the mark below (1014f run).
                // Off the node first: the previous case can leave him standing on this spot's node.
                ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(3));
                tpRel(ctx, x + 0.5, 8.5, 0f, 30f);
                long w = LogTap.mark();
                writeRoute(ctx, List.of(bat));
                check(waitFor(ctx, 100, () -> logHas(w, "Loaded 1 route")), weapon + ": the bat route never loaded");
                boolean appeared = waitFor(ctx, 60, () -> ctx.computeOnClient(mc -> {
                    for (var e : mc.level.entitiesForRendering()) {
                        if (e instanceof net.minecraft.world.entity.ambient.Bat b && b.getMaxHealth() == 100f
                                && !b.isDeadOrDying() && b.blockPosition().closerThan(spot, 2)) {
                            return true;
                        }
                    }
                    return false;
                }));
                check(appeared, weapon + ": the secret bat never appeared");
                long m = LogTap.mark();
                tpRel(ctx, x + 0.5, 10.5, 0f, 30f);
                boolean done = waitFor(ctx, 160, () -> logHas(m, "BAT: 1 kill(s)"));
                int held = ctx.computeOnClient(mc -> mc.player.getInventory().getSelectedSlot());
                boolean batGone = ctx.computeOnClient(mc -> {
                    for (var e : mc.level.entitiesForRendering()) {
                        if (e instanceof net.minecraft.world.entity.ambient.Bat b && b.getMaxHealth() == 100f
                                && !b.isDeadOrDying() && b.blockPosition().closerThan(spot, 2)) {
                            return false;
                        }
                    }
                    return true;
                });
                println(weapon + ": bat node acted " + logHas(m, "BAT acted") + ", 1 kill " + done + ", bat gone " + batGone
                        + ", slot after " + held);
                check(logHas(m, "BAT acted"), weapon + ": the bat node never acted");
                check(done, weapon + ": the bat node never counted the bat's kill");
                check(batGone, weapon + ": the bat is still alive");
                waitFor(ctx, 40, () -> !running(ctx));
                stopRoute(ctx);
            }

            // ---- no bat near: the node times out, nothing counts ----
            ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setBatAttackTicks", 20));
            setArEnum(ctx, "setBatWeapon", "CryptWeapon", "HYPERION");
            writeRoute(ctx, List.of(node("BAT", 13, 20, 0f, 30f)));
            JsonObject lone = node("BAT", 13, 20, 0f, 30f);
            lone.addProperty("start", true);
            writeRoute(ctx, List.of(lone));
            check(arm(ctx, 13, 20, 0f, 30f), "the lone bat node did not start");
            long m2 = LogTap.mark();
            boolean timedOut = waitFor(ctx, 80, () -> logHas(m2, "up, moving on"));
            check(timedOut, "a bat node with no bat did not time out");
            check(!logHas(m2, "BAT: 1 kill"), "a bat node counted a kill with no bat there");
            stopRoute(ctx);
        } finally {
            ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setBatAttackTicks", 100));
        }
    }

    /**
     * killer560 (2026-10-15): "If I have the spirit scepter set to bat mode have the first 2 bats go straight down after
     * that have it look right at the bat to use the rest of them till it times out. If using hype have it keep looking
     * down." A secret bat stands seven blocks to the side of a lone bat node (outside the straight-down blast). Spirit
     * Sceptre: the server takes at least three uses, the first two at pitch exactly 90.0, the rest aimed at the bat
     * (pitch short of straight down, yaw towards it). Hyperion: every use at 90.0. Obvious mode (legit mode is testing-build only).
     */
    private void caseBatSeek(ClientGameTestContext ctx) {
        resetRoutes(ctx);
        clearSecretBats(ctx);
        arena(ctx, false);
        giveHotbar(ctx);
        giveSlot(ctx, 4, "HYPERION");
        giveSlot(ctx, 5, "BAT_WAND");
        List<String> failures = new ArrayList<>();
        int attack0 = ctx.computeOnClient(mc -> ModUnderTest.getInt(ModUnderTest.config(AR_CONFIG), "getBatAttackTicks"));
        try {
            ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setBatAttackTicks", 60));
            String[] weapons = {"SPIRIT_SCEPTRE", "HYPERION"};
            int[] nodeX = {8, 19};
            int[] batX = {15, 12};
            float[] wantYaw = {-90f, 90f};
            for (int i = 0; i < 2; i++) {
                String weapon = weapons[i];
                int x = nodeX[i];
                setArEnum(ctx, "setBatWeapon", "CryptWeapon", weapon);
                forgetRunMemory(ctx);
                ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(3));
                tpRel(ctx, x + 0.5, 8.5, 0f, 30f);
                spawnBatAt(ctx, batX[i], F + 2, 10);
                boolean standing = waitFor(ctx, 80, () -> ctx.computeOnClient(mc -> {
                    for (var e : mc.level.entitiesForRendering()) {
                        if (e instanceof net.minecraft.world.entity.ambient.Bat b && b.getMaxHealth() == 100f
                                && !b.isDeadOrDying()) {
                            return true;
                        }
                    }
                    return false;
                }));
                if (!standing) {
                    failures.add(weapon + ": the secret bat never stood in the world - nothing measured");
                    continue;
                }
                ctx.waitTicks(20);
                JsonObject bat = node("BAT", x, 10, 0f, 30f);
                bat.addProperty("start", true);
                long w = LogTap.mark();
                writeRoute(ctx, List.of(bat));
                check(waitFor(ctx, 100, () -> logHas(w, "Loaded 1 route")), weapon + ": the bat route never loaded");
                long m = LogTap.mark();
                tpRel(ctx, x + 0.5, 10.5, 0f, 30f);
                waitFor(ctx, 120, () -> logHas(m, "BAT: 1 kill(s)") || logHas(m, "up, moving on"));
                List<float[]> aims = serverUseAims(m);
                StringBuilder sb = new StringBuilder();
                for (float[] a : aims) {
                    sb.append(String.format(Locale.US, " (%.2f, %.2f)", a[0], a[1]));
                }
                println("batseek " + weapon + ": server uses (pitch, yaw)" + sb + ", killed " + logHas(m, "BAT: 1 kill(s)"));
                if (aims.size() < 3) {
                    failures.add(weapon + ": the server took only " + aims.size() + " use(s) - need 3 to measure");
                } else if (weapon.equals("SPIRIT_SCEPTRE")) {
                    for (int k = 0; k < 2; k++) {
                        if (aims.get(k)[0] != 90.0f) {
                            failures.add(weapon + ": use " + (k + 1) + " was at pitch " + aims.get(k)[0] + ", not 90.0");
                        }
                    }
                    float[] third = aims.get(2);
                    if (third[0] >= 60f) {
                        failures.add(weapon + ": use 3 was still pointed down (pitch " + third[0] + ") instead of at the bat");
                    }
                    // The arena frame is turned against the world (the first uses read yaw -180 for a rel yaw 0): the
                    // bearing is taken between the node's and the bat's real positions.
                    BlockPos nodeReal = realNow(ctx, x, F + 2, 10);
                    BlockPos batReal = realNow(ctx, batX[i], F + 2, 10);
                    float wantReal = (float) Math.toDegrees(Math.atan2(-(batReal.getX() - nodeReal.getX()),
                            batReal.getZ() - nodeReal.getZ()));
                    float dyaw = Math.abs(net.minecraft.util.Mth.wrapDegrees(third[1] - wantReal));
                    if (Float.isNaN(third[1]) || dyaw > 25f) {
                        failures.add(weapon + ": use 3 yaw " + third[1] + " is not towards the bat (" + wantReal + ")");
                    }
                } else {
                    for (int k = 0; k < aims.size(); k++) {
                        if (aims.get(k)[0] != 90.0f) {
                            failures.add(weapon + ": use " + (k + 1) + " was at pitch " + aims.get(k)[0] + " - Hyperion must keep looking down");
                        }
                    }
                }
                waitFor(ctx, 40, () -> !running(ctx));
                stopRoute(ctx);
                clearSecretBats(ctx);
            }
        } finally {
            stopRoute(ctx);
            ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setBatAttackTicks", attack0));
        }
        check(failures.isEmpty(), String.join("; ", failures));
    }

    // ============================================================================================ crypt-aim (2026-10-15)

    /** A sim crypt mob's name: Hypixel's tag, "⚔ Crypt Undead 512.6k❤" or "⚔ Prince ..." (mod crypt-aim-2-1016),
     *  or the older bare "Crypt Undead" / "Crypt Prince" of a jar from before it. */
    private static boolean isCryptTag(String name) {
        return name.contains("Crypt Undead") || name.contains("Crypt Prince") || name.contains("Prince ");
    }

    /** Every sim crypt mob (a zombie named "Crypt ...") removed on the server. */
    private static void clearCryptMobs(ClientGameTestContext ctx) {
        serverRun(ctx, (server, sp) -> {
            List<net.minecraft.world.entity.Entity> gone = new ArrayList<>();
            for (var e : server.overworld().getAllEntities()) {
                if (e instanceof net.minecraft.world.entity.monster.zombie.Zombie && e.hasCustomName()
                        && isCryptTag(e.getCustomName().getString())) {
                    gone.add(e);
                }
            }
            gone.forEach(net.minecraft.world.entity.Entity::discard);
        });
    }

    /** The client's living crypt mobs (named "Crypt ..."): id to its real position. */
    private static Map<Integer, Vec3> clientCryptMobs(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            Map<Integer, Vec3> out = new LinkedHashMap<>();
            for (var e : mc.level.entitiesForRendering()) {
                if (e instanceof net.minecraft.world.entity.LivingEntity l && !(e instanceof net.minecraft.world.entity
                        .decoration.ArmorStand) && l.isAlive() && !l.isDeadOrDying() && l.hasCustomName()
                        && isCryptTag(l.getCustomName().getString())) {
                    out.put(l.getId(), l.position());
                }
            }
            return out;
        });
    }

    private static void spawnCryptMob(ClientGameTestContext ctx, int x, int z) {
        spawnCryptMob(ctx, x, z, false);
    }

    private static void spawnCryptMob(ClientGameTestContext ctx, int x, int z, boolean prince) {
        BlockPos p = realNow(ctx, x, F, z);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_MOBS, "spawnCrypt",
                new Class<?>[]{Minecraft.class, BlockPos.class, boolean.class}, new Object[]{mc, p, prince}));
    }

    /**
     * 96-ar-1015-cryptaim (killer560, 2026-10-15): "make the crypt node follow the same logic as bat where it will try to
     * look at a crypt as well. If there are two or more that spawned have it do 2 bats at each before moving on." A lone
     * Spirit Sceptre crypt node in the middle of the arena (15, 15); crypt mobs (SimMobs.spawnCrypt, the sim's real
     * "Crypt Undead") stand 9 blocks out, 15+ blocks from each other, so neither the straight-down blasts nor one mob's
     * blast reaches another. Variants: 1 mob, 2 mobs, 3 mobs with the third spawned only after the node's first aimed use
     * (a late spawn), and 2 mobs that are already standing when the node arms and are too tough to die (1000 health),
     * where the node must give each exactly its 2 uses and move on. Every variant: the server's first two sceptre uses
     * at pitch 90.0, then aimed uses whose yaw points at each mob (within 12 degrees) - exactly 2 for a tough one, 1 or 2
     * for a sim crypt mob, which has one health and so can die to the first bat before the second is due - every mob
     * dead (except the tough ones), nothing aimed elsewhere, and the route complete within a tick of the last mob's death (the last bat's landing for the tough
     * ones). Then a Hyperion control: every use stays at 90.0.
     */
    private void caseCryptAim(ClientGameTestContext ctx) {
        resetRoutes(ctx);
        clearSecretBats(ctx);
        clearCryptMobs(ctx);
        arena(ctx, false);
        giveHotbar(ctx);
        giveSlot(ctx, 4, "HYPERION");
        giveSlot(ctx, 5, "BAT_WAND");
        List<String> failures = new ArrayList<>();
        int attack0 = ctx.computeOnClient(mc -> ModUnderTest.getInt(ModUnderTest.config(AR_CONFIG), "getCryptAttackTicks"));
        int[][] spots = {{24, 15}, {11, 23}, {11, 7}};
        // label, mob count, late (the last one spawns after the first aimed use), tough (pre-spawned, 1000 health)
        Object[][] variants = {{"1 mob", 1, false, false}, {"2 mobs", 2, false, false}, {"3 mobs, one late (a prince)", 3, true, false},
                {"2 tough mobs already there", 2, false, true}};
        try {
            ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setCryptAttackTicks", 200));
            setArEnum(ctx, "setCryptWeapon", "CryptWeapon", "SPIRIT_SCEPTRE");
            for (Object[] v : variants) {
                String label = (String) v[0];
                int n = (Integer) v[1];
                boolean late = (Boolean) v[2];
                boolean tough = (Boolean) v[3];
                stopRoute(ctx);
                clearCryptMobs(ctx);
                ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(3));
                writeRoute(ctx, List.of(cryptNode(15, 15, 0f, 30f, true, 0)));
                waitFor(ctx, 40, () -> !running(ctx));
                if (tough) {
                    for (int k = 0; k < n; k++) {
                        spawnCryptMob(ctx, spots[k][0], spots[k][1]);
                    }
                    ctx.waitTicks(3);
                    serverRun(ctx, (server, sp) -> {
                        for (var e : server.overworld().getAllEntities()) {
                            if (e instanceof net.minecraft.world.entity.monster.zombie.Zombie z && z.hasCustomName()
                                    && isCryptTag(z.getCustomName().getString())) {
                                z.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH)
                                        .setBaseValue(1000.0);
                                z.setHealth(1000f);
                            }
                        }
                    });
                    if (!waitFor(ctx, 60, () -> clientCryptMobs(ctx).size() == n)) {
                        failures.add(label + ": the crypt mobs never stood in the client's world - nothing measured");
                        continue;
                    }
                }
                long m = LogTap.mark();
                if (!arm(ctx, 15, 15, 0f, 30f)) {
                    failures.add(label + ": the crypt node did not start");
                    continue;
                }
                if (!tough) {
                    // Spawned as a Superboom opens a crypt: the node is already running when they appear.
                    for (int k = 0; k < (late ? n - 1 : n); k++) {
                        spawnCryptMob(ctx, spots[k][0], spots[k][1]);
                    }
                }
                Map<Integer, Vec3> seen = new LinkedHashMap<>();
                boolean lateSpawned = !late;
                int allGoneTick = -1;
                int doneTick = -1;
                int completeTick = -1;
                for (int t = 0; t < 260 && completeTick < 0; t++) {
                    ctx.waitTicks(1);
                    Map<Integer, Vec3> alive = clientCryptMobs(ctx);
                    seen.putAll(alive);
                    if (!lateSpawned && logHas(m, "use 1/2 at crypt mob")) {
                        // A prince, so both of Hypixel's crypt-mob tags are read.
                        spawnCryptMob(ctx, spots[n - 1][0], spots[n - 1][1], true);
                        lateSpawned = true;
                    }
                    if (allGoneTick < 0 && seen.size() == n && alive.isEmpty()) {
                        allGoneTick = t;
                    }
                    if (doneTick < 0 && logHas(m, "every crypt mob dead or given its")) {
                        doneTick = t;
                    }
                    if (logHas(m, "\" complete")) {
                        completeTick = t;
                    }
                }
                List<float[]> aims = serverUseAims(m);
                StringBuilder sb = new StringBuilder();
                for (float[] a : aims) {
                    sb.append(String.format(Locale.US, " (%.2f, %.2f)", a[0], a[1]));
                }
                int aliveAfter = clientCryptMobs(ctx).size();
                println(String.format(Locale.US, "cryptaim %s: server uses (pitch, yaw)%s; mobs seen %d, alive after %d,"
                                + " all gone at t%d, node done at t%d, route complete at t%d", label, sb, seen.size(),
                        aliveAfter, allGoneTick, doneTick, completeTick));
                for (String l : LogTap.since(m)) {
                    if (l.contains("CRYPT")) {
                        println("  " + l.replaceAll("^.*?\\[AutoRoutes\\]", "[AutoRoutes]"));
                    }
                }
                if (seen.size() != n) {
                    failures.add(label + ": " + seen.size() + " crypt mob(s) seen on the client, " + n + " spawned");
                    continue;
                }
                if (aims.size() < 2 || aims.get(0)[0] != 90.0f || aims.get(1)[0] != 90.0f) {
                    failures.add(label + ": the first two uses were not straight down:" + sb);
                }
                List<float[]> aimed = aims.size() > 2 ? aims.subList(2, aims.size()) : List.of();
                BlockPos nodeReal = realNow(ctx, 15, F, 15);
                int matched = 0;
                for (Map.Entry<Integer, Vec3> e : seen.entrySet()) {
                    Vec3 p = e.getValue();
                    float want = (float) Math.toDegrees(Math.atan2(-(p.x - (nodeReal.getX() + 0.5)),
                            p.z - (nodeReal.getZ() + 0.5)));
                    int at = 0;
                    for (float[] a : aimed) {
                        if (!Float.isNaN(a[1]) && a[0] < 60f
                                && Math.abs(net.minecraft.util.Mth.wrapDegrees(a[1] - want)) <= 12f) {
                            at++;
                        }
                    }
                    matched += at;
                    // A tough mob takes exactly its 2; a sim crypt mob dies to the first bat's blast (one health), often
                    // before the second use is due, and a dead mob is not shot again.
                    if (tough ? at != 2 : at < 1 || at > 2) {
                        failures.add(String.format(Locale.US, "%s: %d aimed use(s) at the mob at bearing %.1f, not %s",
                                label, at, want, tough ? "2" : "1 or 2"));
                    }
                }
                // "2 bats at each before moving on": a mob's uses come together, never split by another mob's (mod
                // crypt-aim-2-1016 - its own bat in flight once read as a wall and split them).
                List<Integer> order = new ArrayList<>();
                for (float[] a : aimed) {
                    int k = 0;
                    for (Vec3 p : seen.values()) {
                        float want = (float) Math.toDegrees(Math.atan2(-(p.x - (nodeReal.getX() + 0.5)),
                                p.z - (nodeReal.getZ() + 0.5)));
                        if (!Float.isNaN(a[1]) && Math.abs(net.minecraft.util.Mth.wrapDegrees(a[1] - want)) <= 12f) {
                            if (order.isEmpty() || order.get(order.size() - 1) != k) {
                                if (order.contains(k)) {
                                    failures.add(label + ": the uses at one mob were split by another's: " + sb);
                                }
                                order.add(k);
                            }
                        }
                        k++;
                    }
                }
                if (aimed.size() != matched) {
                    failures.add(label + ": " + (aimed.size() - matched) + " use(s) after the first two aimed at no crypt mob");
                }
                if (completeTick < 0) {
                    failures.add(label + ": the route never completed (node done " + (doneTick >= 0) + ")");
                } else if (doneTick < 0) {
                    failures.add(label + ": the route completed without the node's every-crypt-mob line");
                }
                if (tough) {
                    if (aliveAfter != n) {
                        failures.add(label + ": a 1000-health mob died (" + aliveAfter + " left)");
                    }
                } else {
                    if (aliveAfter != 0) {
                        failures.add(label + ": " + aliveAfter + " crypt mob(s) still alive");
                    }
                    if (allGoneTick >= 0 && completeTick >= 0 && completeTick - allGoneTick > 1) {
                        failures.add(label + ": the route went on " + (completeTick - allGoneTick)
                                + " ticks after the last crypt mob died");
                    }
                }
                stopRoute(ctx);
            }

            // ---- Hyperion: keeps looking down, whatever crypt mob stands there ----
            setArEnum(ctx, "setCryptWeapon", "CryptWeapon", "HYPERION");
            ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setCryptAttackTicks", 30));
            clearCryptMobs(ctx);
            ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(3));
            writeRoute(ctx, List.of(cryptNode(15, 15, 0f, 30f, true, 0)));
            waitFor(ctx, 40, () -> !running(ctx));
            spawnCryptMob(ctx, spots[0][0], spots[0][1]);
            ctx.waitTicks(10);
            long mh = LogTap.mark();
            if (!arm(ctx, 15, 15, 0f, 30f)) {
                failures.add("Hyperion: the crypt node did not start");
            } else {
                waitFor(ctx, 120, () -> logHas(mh, "\" complete") || logHas(mh, "up, moving on"));
                List<String> pitches = serverUsePitches(mh);
                println("cryptaim Hyperion: server use pitches " + pitches);
                if (pitches.isEmpty()) {
                    failures.add("Hyperion: the server took no use");
                }
                for (String p : pitches) {
                    if (!"90.0".equals(p)) {
                        failures.add("Hyperion: a use went out at pitch " + p + " - the Hyperion must keep looking down");
                    }
                }
                if (logHas(mh, "at crypt mob")) {
                    failures.add("Hyperion: the node aimed at a crypt mob");
                }
            }
        } finally {
            stopRoute(ctx);
            clearCryptMobs(ctx);
            ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setCryptAttackTicks", attack0));
            setArEnum(ctx, "setCryptWeapon", "CryptWeapon", "HYPERION");
        }
        check(failures.isEmpty(), String.join("; ", failures));
    }

    /**
     * killer560 (2026-10-15): "the bat node should sense whether a bat is there on arrival as well, not just if it
     * spawns. It is fully possible someone else got close to a secret and spawned it in so the bat node gets there with
     * it already spawned and never kills it." The secret bat is spawned and standing (100 max health) BEFORE the route
     * is even written, so no spawn is ever seen inside the node's window; the bat node must still find it, use the
     * weapon and count its death. Hyperion and Spirit Sceptre.
     */
    private void caseBatPresent(ClientGameTestContext ctx) {
        resetRoutes(ctx);
        clearSecretBats(ctx);
        arena(ctx, false);
        giveHotbar(ctx);
        giveSlot(ctx, 4, "HYPERION");
        giveSlot(ctx, 5, "BAT_WAND");
        List<String> failures = new ArrayList<>();
        try {
            String[] weapons = {"HYPERION", "SPIRIT_SCEPTRE"};
            int[] xs = {19, 8};
            for (int i = 0; i < 2; i++) {
                int x = xs[i];
                String weapon = weapons[i];
                setArEnum(ctx, "setBatWeapon", "CryptWeapon", weapon);
                forgetRunMemory(ctx);
                ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(3));
                tpRel(ctx, x + 0.5, 8.5, 0f, 30f);
                // The bat first, a few blocks from the node, spawned by the server as a teammate's approach would.
                spawnBatAt(ctx, x, F + 2, 13);
                boolean standing = waitFor(ctx, 80, () -> ctx.computeOnClient(mc -> {
                    for (var e : mc.level.entitiesForRendering()) {
                        if (e instanceof net.minecraft.world.entity.ambient.Bat b && b.getMaxHealth() == 100f
                                && !b.isDeadOrDying()) {
                            return true;
                        }
                    }
                    return false;
                }));
                if (!standing) {
                    failures.add(weapon + ": the secret bat never stood in the world - nothing measured");
                    continue;
                }
                ctx.waitTicks(20);
                JsonObject bat = node("BAT", x, 10, 0f, 30f);
                bat.addProperty("start", true);
                long w = LogTap.mark();
                writeRoute(ctx, List.of(bat));
                check(waitFor(ctx, 100, () -> logHas(w, "Loaded 1 route")), weapon + ": the bat route never loaded");
                long m = LogTap.mark();
                tpRel(ctx, x + 0.5, 10.5, 0f, 30f);
                boolean done = waitFor(ctx, 160, () -> logHas(m, "BAT: 1 kill(s)"));
                boolean acted = logHas(m, "BAT acted");
                println("batpresent " + weapon + ": acted " + acted + ", 1 kill " + done);
                if (!acted) {
                    failures.add(weapon + ": the bat node never acted");
                }
                if (!done) {
                    failures.add(weapon + ": the bat that was already there was never counted as killed");
                }
                waitFor(ctx, 40, () -> !running(ctx));
                stopRoute(ctx);
                clearSecretBats(ctx);
            }
        } finally {
            stopRoute(ctx);
        }
        check(failures.isEmpty(), String.join("; ", failures));
    }

    /**
     * killer560 (2026-10-13): "if i step on a dungeon breaker node until another node fires it should still be able to
     * mine so for instance if it mines the blocks out from under its feet and starts falling then it should keep mining
     * as it falls." A seven-high stone pillar; he stands on top on the node, whose blocks are the whole pillar. From the top
     * only the upper ones are in reach; the rest must go as he falls past them, after the node itself has finished.
     */
    private void caseBreakerFall(ClientGameTestContext ctx) {
        resetRoutes(ctx);
        arena(ctx, false);
        giveHotbar(ctx);
        int top = 7;
        try {
            ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(DX_CONFIG), "setBreakerAuraMultiBreak", true));
            Map<int[], BlockState> put = new HashMap<>();
            JsonArray blocks = new JsonArray();
            for (int y = 0; y < top; y++) {
                put.put(new int[]{13, F + y, 12}, Blocks.STONE.defaultBlockState());
                put.put(new int[]{13, F + y, 11}, Blocks.STONE.defaultBlockState()); // the approach column, kept
                blocks.add("13 " + (F + y) + " 12");
            }
            setBlocks(ctx, put);
            JsonObject br = node("DUNGEON_BREAKER", 13, 12, 0f, 90f);
            br.addProperty("y", (double) (F + top));
            br.add("blocks", blocks);
            br.addProperty("start", true);
            writeRoute(ctx, List.of(br));
            tpRelY(ctx, 13.5, F + top, 11.5, 0f, 90f);
            ctx.waitTicks(60);   // charges back up
            int broken0 = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(EXECUTOR, "lingerBroken"));
            long m = LogTap.mark();
            tpRelY(ctx, 13.5, F + top, 12.5, 0f, 90f);
            boolean gone = waitFor(ctx, 160, () -> {
                for (int y = 0; y < top; y++) {
                    if (!blockAir(ctx, 13, F + y, 12)) {
                        return false;
                    }
                }
                return true;
            });
            ctx.waitTicks(20);
            int broken = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(EXECUTOR, "lingerBroken")) - broken0;
            Vec3 at = relPos(ctx);
            int standing = 0;
            for (int y = 0; y < top; y++) {
                standing += blockAir(ctx, 13, F + y, 12) ? 0 : 1;
            }
            println("breaker fall: node acted " + logHas(m, "DUNGEON_BREAKER acted") + ", all gone " + gone + " ("
                    + standing + " left), " + broken + " broken after the node finished, now at y " + at.y
                    + " (floor " + F + ")");
            check(logHas(m, "DUNGEON_BREAKER acted"), "the breaker node never fired");
            check(broken >= 2, "only " + broken + " block(s) were broken after the node finished - nothing mined while falling");
            check(gone, standing + " block(s) of the pillar are still standing");
            check(Math.abs(at.y - F) < 0.2, "he did not fall to the floor (y " + at.y + ")");
        } finally {
            stopRoute(ctx);
        }
    }

    private void caseCrypt(ClientGameTestContext ctx) {
        resetRoutes(ctx);
        arena(ctx, false);
        giveHotbar(ctx);
        giveSlot(ctx, 4, "HYPERION");
        giveSlot(ctx, 5, "BAT_WAND");
        try {
            String[] weapons = {"HYPERION", "SPIRIT_SCEPTRE"};
            int[] xs = {19, 6};
            for (int i = 0; i < 2; i++) {
                int x = xs[i];
                String weapon = weapons[i];
                setArEnum(ctx, "setCryptWeapon", "CryptWeapon", weapon);
                slabCrypt(ctx, x, 18);
                float p = standPitch(x, 16, x, 18, 0.25);
                JsonObject boom = node("BOOM", x, 16, 0f, p);
                boom.addProperty("start", true);
                writeRoute(ctx, List.of(boom, cryptNode(x, 16, 0f, p, false, 0)));
                int c0 = cryptsBlown(ctx);
                ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(3));
                tpRel(ctx, x + 0.5, 14.5, 0f, p);
                ctx.waitTicks(10);
                long m = LogTap.mark();
                tpRel(ctx, x + 0.5, 16.5, 0f, p);
                // A Spirit Sceptre crypt node also ends the tick it sees its crypt's undead dead (crypt-aim-1015).
                boolean done = waitFor(ctx, 160, () -> logHas(m, "CRYPT: 1 kill(s)") || logHas(m, "(1 seen, 1 dead,"));
                int iBoom = logIndex(m, "BOOM acted");
                int iCrypt = logIndex(m, "CRYPT acted");
                println(weapon + ": boom@" + iBoom + " crypt@" + iCrypt + " done=" + done + " crypts " + c0 + " -> "
                        + cryptsBlown(ctx));
                check(iBoom >= 0 && iCrypt > iBoom, weapon + ": the crypt node did not fire right after the boom ("
                        + iBoom + ", " + iCrypt + ")");
                check(done, weapon + ": the crypt node never counted a kill (crypts " + c0 + " -> " + cryptsBlown(ctx) + ")");
                check(cryptsBlown(ctx) == c0 + 1, weapon + ": the sim counted " + (cryptsBlown(ctx) - c0) + " crypt(s)");
                waitFor(ctx, 40, () -> !running(ctx));
                stopRoute(ctx);
            }

            // ---- a crypt await is not met by a secret ----
            setArEnum(ctx, "setCryptWeapon", "CryptWeapon", "SPIRIT_SCEPTRE");
            setBlocks(ctx, Map.of(new int[]{12, F, 8}, floorLever()));
            writeRoute(ctx, List.of(cryptNode(10, 6, 0f, 60f, true, 1)));
            check(arm(ctx, 10, 6, 0f, 60f), "the lone crypt node did not start");
            long mc1 = LogTap.mark();
            ctx.waitTicks(5);
            rightClick(ctx, 12, F, 8, Direction.UP);
            boolean stopped = waitFor(ctx, 140, () -> logHas(mc1, "up, moving on"));
            check(!logHas(mc1, "CRYPT: 1 kill"), "a secret (lever click) satisfied a crypt node");
            check(stopped, "the crypt node with nothing to kill did not time out");

            // ---- a secret await is not met by our crypt kill ----
            writeRoute(ctx, List.of(handUse(10, 22, 0f, 80f, true, 1)));
            check(arm(ctx, 10, 22, 0f, 80f), "the secret-await route did not start");
            long mc2 = LogTap.mark();
            BlockPos undead = realNow(ctx, 11, F, 23);
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_MOBS, "spawnCrypt",
                    new Class<?>[]{Minecraft.class, BlockPos.class, boolean.class}, new Object[]{mc, undead, false}));
            ctx.waitTicks(20);
            String seen = ctx.computeOnClient(mc -> {
                for (var e : mc.level.entitiesForRendering()) {
                    if (e instanceof net.minecraft.world.entity.monster.zombie.Zombie z && !z.isDeadOrDying()
                            && z.distanceTo(mc.player) < 3) {
                        return "undead " + z.getId() + " at " + z.blockPosition().toShortString();
                    }
                }
                return null;
            });
            check(seen != null, "the client never saw the crypt undead");
            // Our own Wither Impact, straight down (a melee hit in the sim is the mage's beam, not a punch).
            ctx.runOnClient(mc -> {
                mc.player.getInventory().setSelectedSlot(4);
                mc.player.setXRot(90f);
            });
            ctx.waitTicks(2);
            ctx.runOnClient(mc -> mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND));
            println("secret await vs crypt kill: " + seen);
            boolean cryptSeen = waitFor(ctx, 60, () -> logHas(mc2, "await crypt 1"));
            check(cryptSeen, "our crypt kill was not counted at all (crypts " + cryptsBlown(ctx) + ")");
            check(!logHas(mc2, "secrets met"), "our crypt kill satisfied a SECRET await");
            stopRoute(ctx);

            // ---- boom + crypt await:1 + ew await:1 on one tile ----
            slabCrypt(ctx, 19, 12);
            setBlocks(ctx, Map.of(new int[]{21, F, 10}, floorLever()));
            float p = standPitch(19, 10, 19, 12, 0.25);
            JsonObject boom = node("BOOM", 19, 10, 0f, p);
            boom.addProperty("start", true);
            JsonObject ew = ew(19, 10, 19, 4, false);
            ew.addProperty("awaitEnabled", true);
            ew.addProperty("await", "SECRET");
            ew.addProperty("amount", 1);
            writeRoute(ctx, List.of(boom, cryptNode(19, 10, 0f, p, false, 1), ew));
            ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(3));
            tpRel(ctx, 19.5, 8.5, 0f, p);
            ctx.waitTicks(10);
            long mc3 = LogTap.mark();
            tpRel(ctx, 19.5, 10.5, 0f, p);
            boolean cryptDone = waitFor(ctx, 160, () -> logHas(mc3, "CRYPT: 1 kill(s)") || logHas(mc3, "(1 seen, 1 dead,"));
            check(cryptDone, "combined: the crypt node never killed its crypt");
            ctx.waitTicks(40);
            check(!logHas(mc3, "ETHERWARP acted"), "combined: the etherwarp went before its secret");
            check(relPos(ctx).distanceTo(new Vec3(19.5, F, 10.5)) < 1.5, "combined: he left the tile before the secret ("
                    + relPos(ctx) + ")");
            ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(3));
            rightClick(ctx, 21, F, 10, Direction.UP);
            Vec3 l = waitLanded(ctx, 19, 4, 80);
            int iB = logIndex(mc3, "BOOM acted");
            int iC = Math.max(logIndex(mc3, "CRYPT: 1 kill"), logIndex(mc3, "(1 seen, 1 dead,"));
            int iS = logIndex(mc3, "await secret 1");
            int iE = logIndex(mc3, "ETHERWARP acted");
            println("combined order: boom " + iB + ", crypt kill " + iC + ", secret " + iS + ", ew " + iE);
            check(l != null, "combined: the etherwarp did not land after the secret (at " + relPos(ctx) + ")");
            check(iB >= 0 && iC > iB && iS > iC && iE > iS, "combined: wrong order boom " + iB + ", crypt " + iC
                    + ", secret " + iS + ", ew " + iE);
        } finally {
            setArEnum(ctx, "setCryptWeapon", "CryptWeapon", "HYPERION");
        }
    }

    private static String pickRoom(ClientGameTestContext ctx) {
        Map<String, JsonObject> db = new HashMap<>();
        try {
            Path file = ModUnderTest.modConfig("killer560smod-roomdata").resolve("rooms-modern.json");
            for (JsonElement e : JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonArray()) {
                db.put(e.getAsJsonObject().get("name").getAsString(), e.getAsJsonObject());
            }
        } catch (Exception e) {
            throw new AssertionError("could not read the room database: " + e);
        }
        List<String> names = ctx.computeOnClient(mc -> new ArrayList<>((List<String>) ModUnderTest.staticCall(ROOM_LIBRARY,
                "names")));
        for (String n : names) {
            JsonObject o = db.get(n);
            if (o == null) {
                continue;
            }
            String type = o.has("type") ? o.get("type").getAsString().toUpperCase(Locale.ROOT) : "";
            int secrets = o.has("secrets") ? o.get("secrets").getAsInt() : 0;
            if (secrets < 1 || type.equals("PUZZLE") || type.equals("TRAP") || type.equals("BLOOD")
                    || type.equals("ENTRANCE") || type.equals("FAIRY") || n.contains("Maze") || n.contains("Trap")) {
                continue;
            }
            int[] size = ctx.computeOnClient(mc -> {
                Object r = ModUnderTest.staticCall(ROOM_LIBRARY, "get", new Class<?>[]{String.class}, new Object[]{n});
                try {
                    return new int[]{r.getClass().getField("sizeX").getInt(r), r.getClass().getField("sizeZ").getInt(r)};
                } catch (ReflectiveOperationException e) {
                    return new int[]{999, 999};
                }
            });
            if (size[0] <= 33 && size[1] <= 33) {
                return n;
            }
        }
        throw new AssertionError("no 1x1 room with secrets in the library");
    }

    /** Loads {@link #room} alone through a map code at {@code rotation}, the way a code he pastes is built. */
    private static void buildRoom(ClientGameTestContext ctx, int rotation) {
        int cells = 121;
        int idx = 4 * 11 + 4;
        int[] cellRoom = new int[cells];
        java.util.Arrays.fill(cellRoom, -1);
        int[] cellDoor = new int[cells];
        int[] cellRotation = new int[cells];
        // A room bigger than 1x1 (Museum is 2x2) needs all its cells, the connecting ones included, or half of it is
        // pasted outside its own cells and the live map never identifies it.
        int[] span = ctx.computeOnClient(mc -> {
            Object r = ModUnderTest.staticCall(ROOM_LIBRARY, "get", new Class<?>[]{String.class}, new Object[]{room});
            try {
                return new int[]{Math.max(1, (int) Math.round(r.getClass().getField("sizeX").getInt(r) / 32.0)),
                        Math.max(1, (int) Math.round(r.getClass().getField("sizeZ").getInt(r) / 32.0))};
            } catch (ReflectiveOperationException e) {
                return new int[]{1, 1};
            }
        });
        for (int gz = 4; gz <= 4 + 2 * (span[1] - 1); gz++) {
            for (int gx = 4; gx <= 4 + 2 * (span[0] - 1); gx++) {
                cellRoom[gz * 11 + gx] = 0;
                cellRotation[gz * 11 + gx] = rotation;
            }
        }
        cellRoom[idx] = 0;
        cellRotation[idx] = rotation;
        String code = ctx.computeOnClient(mc -> {
            try {
                Class<?> dec = Class.forName(MAP_CODE + "$Decoded");
                Object d = dec.getConstructor(String[].class, int[].class, int[].class, int[].class)
                        .newInstance(new String[]{room}, cellRoom, cellDoor, cellRotation);
                return (String) ModUnderTest.staticCall(MAP_CODE, "encodeDecoded", new Class<?>[]{dec}, new Object[]{d});
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        });
        if (ctx.computeOnClient(mc -> mc.level == null)) {
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter", new Class<?>[]{String.class},
                    new Object[]{"gametest"}));
        }
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> ModUnderTest.staticCall(BUILDER, "build",
                new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, code})));
        ctx.waitFor(mc -> mc.level != null && mc.player != null, 2400);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitFor(mc -> McCompat.screen(mc) == null, 1200);
        // The client needs the room under its feet and the live map its frame.
        ctx.waitFor(mc -> mc.player.onGround() && ModUnderTest.staticCall(FRAME, "current") != null, 1200);
        // A built floor locks the Dungeon Breaker until the run starts (the sim's rule, as before /start on a floor).
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimRun", "begin",
                new Class<?>[]{Minecraft.class, BlockPos.class},
                new Object[]{mc, ModUnderTest.staticCall(BUILDER, "entranceDoor")}));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.roomsim.SimRun", "hasStarted"), 400);
        ctx.waitTicks(20);
        Vec3 rel = relPos(ctx);
        F = (int) Math.floor(rel.y + 0.01);
        println("built " + room + " at paste rotation " + rotation + "; frame " + frameString(ctx) + "; arena floor y "
                + F + " (relative)");
    }

    /** The arena: relative x,z 4..26 cleared from F to F+8 on a stone floor at F-1, stone walls round it, and with
     *  {@code wall} a 4-high wall across x 15 (z 4..26) the path has to go over. */
    private static void arena(ClientGameTestContext ctx, boolean wall) {
        ctx.runOnClient(mc -> {
            Object frame = ModUnderTest.staticCall(FRAME, "current");
            var server = mc.getSingleplayerServer();
            Map<BlockPos, BlockState> set = new LinkedHashMap<>();
            for (int x = 3; x <= 27; x++) {
                for (int z = 3; z <= 27; z++) {
                    boolean edge = x == 3 || x == 27 || z == 3 || z == 27;
                    set.put(real(frame, x, F - 1, z), Blocks.STONE.defaultBlockState());
                    for (int y = F; y <= F + 8; y++) {
                        boolean solid = (edge && y <= F + 3) || (wall && !edge && x == 15 && y <= F + 3);
                        set.put(real(frame, x, y, z), solid ? Blocks.STONE.defaultBlockState()
                                : Blocks.AIR.defaultBlockState());
                    }
                }
            }
            server.execute(() -> {
                var level = server.overworld();
                for (var e : set.entrySet()) {
                    level.setBlockAndUpdate(e.getKey(), e.getValue());
                }
            });
        });
        ctx.waitTicks(20);
    }

    private static BlockPos real(Object frame, int x, int y, int z) {
        return (BlockPos) ModUnderTest.staticCall(COORDS, "toRealBlock", new Class<?>[]{frame.getClass(), BlockPos.class},
                new Object[]{frame, new BlockPos(x, y, z)});
    }

    private static void setBlocks(ClientGameTestContext ctx, Map<int[], BlockState> blocks) {
        AtomicReference<Boolean> done = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            Object frame = ModUnderTest.staticCall(FRAME, "current");
            Map<BlockPos, BlockState> set = new LinkedHashMap<>();
            for (var e : blocks.entrySet()) {
                set.put(real(frame, e.getKey()[0], e.getKey()[1], e.getKey()[2]), e.getValue());
            }
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                for (var e : set.entrySet()) {
                    server.overworld().setBlockAndUpdate(e.getKey(), e.getValue());
                }
                done.set(true);
            });
        });
        ctx.waitFor(mc -> done.get() != null, 100);
        ctx.waitTicks(3);
    }

    private static BlockState blockState(ClientGameTestContext ctx, int x, int y, int z) {
        return ctx.computeOnClient(mc -> mc.level.getBlockState(real(ModUnderTest.staticCall(FRAME, "current"), x, y, z)));
    }

    private static boolean blockAir(ClientGameTestContext ctx, int x, int y, int z) {
        return blockState(ctx, x, y, z).isAir();
    }

    /** AOTV slot 0, Superboom slot 1, Dungeon Breaker slot 2, empty slot 3 (selected). */
    private static void giveHotbar(ClientGameTestContext ctx) {
        AtomicReference<Boolean> given = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(uuid);
                var inv = sp.getInventory();
                for (int i = 0; i < 36; i++) {
                    inv.setItem(i, ItemStack.EMPTY);
                }
                String[] ids = {"ASPECT_OF_THE_VOID", "SUPERBOOM_TNT", "DUNGEONBREAKER"};
                for (int i = 0; i < ids.length; i++) {
                    inv.setItem(i, (ItemStack) ModUnderTest.staticCall(SIM_ITEMS, "build", new Class<?>[]{String.class},
                            new Object[]{ids[i]}));
                }
                given.set(true);
            });
        });
        ctx.waitFor(mc -> given.get() != null, 200);
        ctx.waitTicks(10);
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(3));
        ctx.waitTicks(3);
    }

    /** Server teleport to relative feet (x, F, z), looking along a RELATIVE yaw. */
    private static void tpRel(ClientGameTestContext ctx, double x, double z, float relYaw, float pitch) {
        AtomicReference<Boolean> done = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            Object frame = ModUnderTest.staticCall(FRAME, "current");
            Vec3 p = (Vec3) ModUnderTest.staticCall(COORDS, "toReal", new Class<?>[]{frame.getClass(), double.class,
                    double.class, double.class}, new Object[]{frame, x, (double) F, z});
            float yaw = (Float) ModUnderTest.staticCall(COORDS, "toRealYaw", new Class<?>[]{frame.getClass(), float.class},
                    new Object[]{frame, relYaw});
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(uuid);
                sp.teleportTo(server.overworld(), p.x, p.y, p.z, java.util.Set.<net.minecraft.world.entity.Relative>of(),
                        yaw, pitch, false);
                done.set(true);
            });
            mc.player.setYRot(yaw);
            mc.player.setXRot(pitch);
            lookYaw = yaw;
            lookPitch = pitch;
            lookSet = true;
        });
        ctx.waitFor(mc -> done.get() != null, 100);
        ctx.waitTicks(2);
    }

    private static float lookYaw;
    private static float lookPitch;
    private static boolean lookSet;

    private static Vec3 relPos(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            Object frame = ModUnderTest.staticCall(FRAME, "current");
            if (frame == null) {
                return new Vec3(Double.NaN, Double.NaN, Double.NaN);
            }
            return (Vec3) ModUnderTest.staticCall(COORDS, "toRelative", new Class<?>[]{frame.getClass(), Vec3.class},
                    new Object[]{frame, mc.player.position()});
        });
    }

    /**
     * Waits until he stands in relative block (x, z) at the arena floor; his position then, or null. Checks now, then after
     * each of {@code ticks} ticks (so {@code ticks == 0} is a single look at where he is now - it used to check nothing and
     * always answered null, which made every "has not landed yet" assertion through it vacuous, 2026-10-15).
     */
    private static Vec3 waitLanded(ClientGameTestContext ctx, int x, int z, int ticks) {
        for (int i = 0; i <= ticks; i++) {
            Vec3 p = relPos(ctx);
            if (Math.floor(p.x) == x && Math.floor(p.z) == z && Math.abs(p.y - F) < 0.2) {
                return p;
            }
            if (i < ticks) {
                ctx.waitTicks(1);
            }
        }
        return null;
    }

    private static int frameRotation(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> (Integer) ModUnderTest.call(ModUnderTest.staticCall(FRAME, "current"),
                "rotation", new Class<?>[]{}, new Object[]{}));
    }

    private static String frameString(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> String.valueOf(ModUnderTest.staticCall(FRAME, "current")));
    }

    // ============================================================================================ routes

    private static float relYawTo(int fx, int fz, int tx, int tz) {
        return (float) Math.toDegrees(Math.atan2(-(tx - fx), tz - fz));
    }

    /** Pitch from a sneaking eye over block (fx,fz) down onto the top centre of the floor block at (tx,tz). */
    private static float pitchTo(int fx, int fz, int tx, int tz) {
        double horiz = Math.hypot(tx - fx, tz - fz);
        return (float) Math.toDegrees(Math.atan2(1.27, horiz));
    }

    private static JsonObject node(String type, int x, int z, float yaw, float pitch) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        o.addProperty("x", x + 0.5);
        o.addProperty("y", (double) F);
        o.addProperty("z", z + 0.5);
        o.addProperty("yaw", yaw);
        o.addProperty("pitch", pitch);
        return o;
    }

    private static JsonObject ew(int fx, int fz, int tx, int tz, boolean start) {
        JsonObject o = node("ETHERWARP", fx, fz, relYawTo(fx, fz, tx, tz), pitchTo(fx, fz, tx, tz));
        o.addProperty("landing", String.format(Locale.US, "%.3f %.3f %.3f", tx + 0.5, F + 0.05, tz + 0.5));
        if (start) {
            o.addProperty("start", true);
        }
        return o;
    }

    private static void writeRoute(ClientGameTestContext ctx, List<JsonObject> nodes) {
        // The reload can land seconds after the write (2026-10-14/15: 1013-bat, 1014-batmem, 1014-awaitmem each armed
        // outside their window once). Every caller gets the route in memory before it moves on.
        long loadMark = LogTap.mark();
        String loaded = "Loaded " + (nodes == null ? 0 : 1) + " route";
        ctx.runOnClient(mc -> {
            try {
                Path file = (Path) ModUnderTest.staticCall(STORE, "routesFile");
                JsonObject routes = new JsonObject();
                if (nodes != null) {
                    JsonArray arr = new JsonArray();
                    nodes.forEach(arr::add);
                    JsonObject r = new JsonObject();
                    r.add("nodes", arr);
                    routes.add(room, r);
                }
                JsonObject root = new JsonObject();
                root.addProperty("version", 1);
                root.add("routes", routes);
                Files.createDirectories(file.getParent());
                Files.writeString(file, root.toString(), StandardCharsets.UTF_8);
                ModUnderTest.staticCall(STORE, "reload");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        ctx.waitTicks(2);
        waitFor(ctx, 100, () -> logHas(loadMark, loaded));
        // The case before may have left him standing on this spot's node, so the load itself can start the route; then
        // arm()'s step off and on sees it already running and never logs a fresh start. Every case begins idle.
        ctx.waitTicks(2);
        if (running(ctx)) {
            stopRoute(ctx);
        }
    }

    private static void resetRoutes(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            McCompat.setScreen(mc, null);
            ModUnderTest.staticCall(EXECUTOR, "stop", new Class<?>[]{String.class}, new Object[]{"test reset"});
            if ((Boolean) ModUnderTest.staticCall(FEATURE, "isEditMode")) {
                ModUnderTest.staticCall(FEATURE, "setEditMode", new Class<?>[]{boolean.class}, new Object[]{false});
            }
        });
        writeRoute(ctx, null);
    }

    private static JsonArray fileNodes(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            try {
                Path file = (Path) ModUnderTest.staticCall(STORE, "routesFile");
                if (!Files.exists(file)) {
                    return new JsonArray();
                }
                JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                JsonObject routes = root.getAsJsonObject("routes");
                if (routes == null || !routes.has(room)) {
                    return new JsonArray();
                }
                return routes.getAsJsonObject(room).getAsJsonArray("nodes");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /** The routes file's nodes, by the floor of their relative x, must be exactly {@code xs} in order. */
    private static void expectXs(ClientGameTestContext ctx, String step, int... xs) {
        ctx.waitTicks(3);
        JsonArray nodes = fileNodes(ctx);
        List<Integer> got = new ArrayList<>();
        for (JsonElement e : nodes) {
            got.add((int) Math.floor(e.getAsJsonObject().get("x").getAsDouble()));
        }
        List<Integer> want = new ArrayList<>();
        for (int x : xs) {
            want.add(x);
        }
        println(step + ": file " + got);
        check(got.equals(want), step + ": the routes file holds " + got + ", expected " + want);
    }

    private static void checkSnapped(JsonObject n, int bx, int bz) {
        double x = n.get("x").getAsDouble();
        double z = n.get("z").getAsDouble();
        check(Math.abs(x - (bx + 0.5)) < 1e-3 && Math.abs(z - (bz + 0.5)) < 1e-3,
                "node not on the center of block (" + bx + "," + bz + "): " + n);
    }

    private static int plans(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(PLANNER, "plansStarted"));
    }

    // ============================================================================================ input

    /** Types a line into a real ChatScreen and submits it - the path his typing takes. An /ar add is typed facing the
     *  way the last tpRel asked for: the gametest window's own mouse can turn the camera in between (a boom node once
     *  saved yaw 128.9 instead of 90), and the look is part of what /ar add records. */
    private static void cmd(ClientGameTestContext ctx, String line) {
        if (line.startsWith("/ar add")) {
            // The last node's route finishes first (a walk sprints until it hits the wall), as he would wait.
            waitFor(ctx, 200, () -> !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(EXECUTOR, "isRunning")));
        }
        ctx.runOnClient(mc -> {
            if (line.startsWith("/ar add") && lookSet) {
                mc.player.setYRot(lookYaw);
                mc.player.setXRot(lookPitch);
            }
            net.minecraft.client.gui.screens.ChatScreen screen = new net.minecraft.client.gui.screens.ChatScreen("", false);
            McCompat.setScreen(mc, screen);
            screen.handleChatInput(line, true);
            if (McCompat.screen(mc) == screen) {
                McCompat.setScreen(mc, null);
            }
        });
        ctx.waitTicks(2);
    }

    /** A real right click on a relative block, through the game mode (his click). */
    private static void rightClick(ClientGameTestContext ctx, int x, int y, int z, Direction face) {
        ctx.runOnClient(mc -> {
            BlockPos pos = real(ModUnderTest.staticCall(FRAME, "current"), x, y, z);
            Vec3 centre = Vec3.atCenterOf(pos);
            BlockHitResult hit = new BlockHitResult(centre, face, pos, false);
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
        });
        ctx.waitTicks(2);
    }

    private static void openEditor(ClientGameTestContext ctx, int n) {
        cmd(ctx, "/ar edit " + n);
        boolean open = waitFor(ctx, 20, () -> ctx.computeOnClient(mc -> McCompat.screen(mc) != null
                && McCompat.screen(mc).getClass().getName().equals(EDIT_SCREEN)));
        check(open, "/ar edit " + n + " did not open the node editor (screen "
                + ctx.computeOnClient(mc -> String.valueOf(McCompat.screen(mc))) + ")");
        ctx.waitTicks(3);
    }

    private static List<String> labels(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            List<String> out = new ArrayList<>();
            Screen s = McCompat.screen(mc);
            if (s != null) {
                for (var child : s.children()) {
                    if (child instanceof AbstractWidget w) {
                        out.add(strip(w.getMessage().getString()));
                    }
                }
            }
            return out;
        });
    }

    /** A click at the widget's DRAWN centre, through the screen's own mouseClicked. Exact label, else prefix. */
    private static void press(ClientGameTestContext ctx, String label) {
        String result = ctx.computeOnClient(mc -> {
            Screen s = McCompat.screen(mc);
            if (s == null) {
                return "no screen";
            }
            AbstractWidget found = null;
            for (var child : s.children()) {
                if (child instanceof AbstractWidget w && !(w instanceof net.minecraft.client.gui.components.EditBox)) {
                    String l = strip(w.getMessage().getString());
                    if (l.equals(label)) {
                        found = w;
                        break;
                    }
                    if (found == null && l.startsWith(label)) {
                        found = w;
                    }
                }
            }
            if (found == null) {
                return "no button \"" + label + "\"";
            }
            double x = found.getX() + found.getWidth() / 2.0;
            double y = found.getY() + found.getHeight() / 2.0;
            boolean took = s.mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0)), false);
            s.mouseReleased(new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0)));
            return took ? "ok" : "click at " + x + "," + y + " on \"" + label + "\" was not taken";
        });
        check("ok".equals(result), result + " - on " + labels(ctx));
        ctx.waitTicks(1);
    }

    /** Every widget on screen and none overlapping another. */
    private static void checkLayout(ClientGameTestContext ctx) {
        String bad = ctx.computeOnClient(mc -> {
            Screen s = McCompat.screen(mc);
            List<AbstractWidget> ws = new ArrayList<>();
            for (var child : s.children()) {
                if (child instanceof AbstractWidget w && w.visible) {
                    ws.add(w);
                }
            }
            for (AbstractWidget a : ws) {
                if (a.getX() < 0 || a.getY() < 0 || a.getX() + a.getWidth() > s.width || a.getY() + a.getHeight() > s.height) {
                    return "\"" + strip(a.getMessage().getString()) + "\" is off the " + s.width + "x" + s.height + " screen";
                }
                for (AbstractWidget b : ws) {
                    if (a != b && a.getX() < b.getX() + b.getWidth() && b.getX() < a.getX() + a.getWidth()
                            && a.getY() < b.getY() + b.getHeight() && b.getY() < a.getY() + a.getHeight()) {
                        return "\"" + strip(a.getMessage().getString()) + "\" overlaps \"" + strip(b.getMessage().getString()) + "\"";
                    }
                }
            }
            return null;
        });
        check(bad == null, "editor layout: " + bad);
    }

    // ============================================================================================ helpers

    private static boolean waitFor(ClientGameTestContext ctx, int ticks, Supplier<Boolean> cond) {
        for (int i = 0; i < ticks; i++) {
            if (cond.get()) {
                return true;
            }
            ctx.waitTicks(1);
        }
        return cond.get();
    }

    private static boolean logHas(long mark, String text) {
        return logIndex(mark, text) >= 0;
    }

    /** The await saw its secret - under the chest's screen, or the tick before the screen arrived. */
    private static boolean awaitMet(long mark) {
        return logHas(mark, "await met under a screen") || logHas(mark, "await held it");
    }

    private static boolean logHasAll(long mark, String a, String b) {
        for (String l : LogTap.since(mark)) {
            if (l.contains(a) && l.contains(b)) {
                return true;
            }
        }
        return false;
    }

    private static int logIndex(long mark, String text) {
        List<String> lines = LogTap.since(mark);
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(text)) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> tail(List<String> l, int n) {
        return l.subList(Math.max(0, l.size() - n), l.size());
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }

    private static String strip(String s) {
        String t = net.minecraft.ChatFormatting.stripFormatting(s);
        return t == null ? s : t;
    }

    private static void println(String s) {
        System.out.println("[" + NAME + "] " + s);
    }

    private static void teardown(ClientGameTestContext ctx) {
        try {
            resetRoutes(ctx);
        } catch (Throwable ignored) {
            // never replaces the verdict
        }
        ctx.runOnClient(mc -> {
            try {
                ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setEnabled", false);
                // NOT SimState.leave() here: leaving through the disconnect, as he does, lets the sim's own unload
                // reset its per-map state. Calling leave() first skipped that, and a Dungeon Breaker block still
                // waiting to regrow then tried to come back in the NEXT world's server, which hung its loading
                // screen (2026-10-05; the mod now drops it on any unload as well).
            } catch (Throwable ignored) {
                // never replaces the verdict
            }
        });
        ctx.runOnClient(mc -> mc.execute(() -> {
            if (mc.level != null) {
                mc.level.disconnect(net.minecraft.network.chat.Component.literal("scenario over"));
                mc.disconnectWithSavingScreen();
            }
        }));
        ctx.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null, 1200);
        // Let the mod's own unload handler (deferred to a client tick) run first: it resets the per-map state only
        // while the sim is still active, so calling leave() before it ran skips that reset. The likely way 96-ar-mimic's
        // five starred mimics reached a later floor (89 read "1 spawned, 5 starred", 2026-10-06 and 2026-10-07; the
        // mod's builds now forget them either way).
        try {
            ctx.waitFor(mc -> !(Boolean) ModUnderTest.staticCall(SIM_STATE, "isActive"), 200);
        } catch (RuntimeException | AssertionError ignored) {
            // the leave() below still turns it off; never replaces the verdict
        }
        ctx.runOnClient(mc -> {
            try {
                ModUnderTest.staticCall(SIM_STATE, "leave");
            } catch (Throwable ignored) {
                // never replaces the verdict
            }
        });
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> mc.execute(() ->
                McCompat.setScreen(mc, new net.minecraft.client.gui.screens.TitleScreen())));
        ctx.waitFor(mc -> McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.TitleScreen, 400);
    }

    // ============================================================================================ ar-nodes (656-662)

    /**
     * killer560's Auto Routes items of 2026-10-09 (mod ar-nodes). Nested so they share this suite's world, sampler and
     * helpers; kept in one block because this file is merged a lot.
     */
    static final class ArNodesCases {
        private ArNodesCases() {
        }

        private static final List<double[]> JUMP_SAMPLES = new ArrayList<>();
        private static volatile boolean jumpSampling;
        private static boolean jumpHooked;

        private static int executorInt(ClientGameTestContext ctx, String method) {
            return ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(EXECUTOR, method));
        }

        /** The block as the integrated SERVER has it (relative coordinates). */
        private static BlockState serverState(ClientGameTestContext ctx, int x, int y, int z) {
            AtomicReference<BlockState> got = new AtomicReference<>();
            ctx.runOnClient(mc -> {
                BlockPos pos = real(ModUnderTest.staticCall(FRAME, "current"), x, y, z);
                var server = mc.getSingleplayerServer();
                server.execute(() -> got.set(server.overworld().getBlockState(pos)));
            });
            ctx.waitFor(mc -> got.get() != null, 100);
            return got.get();
        }

        private static JsonArray breakerBlocks(ClientGameTestContext ctx) {
            for (JsonElement e : fileNodes(ctx)) {
                JsonObject n = e.getAsJsonObject();
                if ("DUNGEON_BREAKER".equals(n.get("type").getAsString())) {
                    JsonArray b = n.getAsJsonArray("blocks");
                    return b == null ? new JsonArray() : b;
                }
            }
            return null;
        }

        private static void editMode(ClientGameTestContext ctx, boolean on) {
            boolean now = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(FEATURE, "isEditMode"));
            if (now != on) {
                cmd(ctx, "/ar edit db");
                ctx.waitTicks(3);
            }
            check(ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(FEATURE, "isEditMode")) == on,
                    "edit mode did not turn " + (on ? "on" : "off"));
        }

        /** A breaker node far from where he picks (the old code measured from it), Dungeon Breaker in hand, charges full. */
        private static void breakerSetup(ClientGameTestContext ctx) {
            resetRoutes(ctx);
            arena(ctx, false);
            giveHotbar(ctx);
            writeRoute(ctx, List.of(node("DUNGEON_BREAKER", 6, 24, 0f, 0f)));
            ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(2));
            ctx.waitTicks(40); // charges back to full
        }

        private static double eyeToBox(ClientGameTestContext ctx, int x, int y, int z) {
            return ctx.computeOnClient(mc -> {
                BlockPos p = real(ModUnderTest.staticCall(FRAME, "current"), x, y, z);
                return Math.sqrt(new net.minecraft.world.phys.AABB(p).distanceToSqr(mc.player.getEyePosition()));
            });
        }

        /**
         * 656 (killer560, 2026-10-11: "in edit mode the node should not break blocks but I should be able to"). A right
         * click in /ar edit db picks the stone and the mod sends NO dig for it - the stone stands on the server. His own
         * left click then breaks it (his dig, no route acting). With the stone back and edit mode still open, stepping
         * onto the start breaker node that holds it fires nothing for 40 ticks. Control: edit mode off, the same step
         * fires the node and it breaks the stone. Re-adding and shift-removing send nothing.
         */
        static void addBreak(ClientGameTestContext ctx) {
            resetRoutes(ctx);
            arena(ctx, false);
            giveHotbar(ctx);
            JsonObject br = node("DUNGEON_BREAKER", 20, 6, 0f, 0f);
            br.addProperty("start", true);
            writeRoute(ctx, List.of(br));
            ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(2));
            setBlocks(ctx, Map.of(new int[]{22, F + 1, 6}, Blocks.STONE.defaultBlockState()));
            tpRel(ctx, 20.5, 9.5, -90f, 0f);
            ctx.waitTicks(40); // charges back to full
            try {
                editMode(ctx, true);
                tpRel(ctx, 20.5, 6.5, -90f, 0f);
                ctx.waitTicks(5);
                check(serverState(ctx, 22, F + 1, 6).is(Blocks.STONE), "the stone is not there on the server to begin with");
                // ---- the pick: no dig from the mod ----
                long m = LogTap.mark();
                startSampling(ctx);
                rightClick(ctx, 22, F + 1, 6, Direction.WEST);
                ctx.waitTicks(20);
                List<Sample> s = stopSampling(ctx);
                JsonArray picked = breakerBlocks(ctx);
                boolean standing = serverState(ctx, 22, F + 1, 6).is(Blocks.STONE);
                println("656 pick: " + countOut(s, "player_action") + " dig(s), " + countOut(s, "swing")
                        + " swing, server stone " + standing + ", picked " + picked);
                check(picked != null && picked.size() == 1, "the right click did not pick the stone: " + picked);
                check(countOut(s, "player_action") == 0, "picking a block made the mod send " + countOut(s, "player_action")
                        + " dig(s)");
                check(standing, "picking a block broke it");

                // ---- his own left click breaks it; the route does nothing ----
                check(aimedAt(ctx, 22, F + 1, 6), "his left click is not aimed at the picked stone");
                startSampling(ctx);
                leftClick(ctx, 2);
                ctx.waitTicks(10);
                s = stopSampling(ctx);
                boolean air = serverState(ctx, 22, F + 1, 6).isAir();
                println("656 his left click: " + countOut(s, "swing") + " swing, " + countOut(s, "player_action")
                        + " dig(s), server air " + air);
                check(countOut(s, "player_action") >= 1 && air, "his own left click did not break the picked stone");
                check(!logHas(m, "DUNGEON_BREAKER acted"), "a breaker node acted while edit mode was open");

                // ---- the stone back; standing on its breaker node with edit mode open fires nothing ----
                setBlocks(ctx, Map.of(new int[]{22, F + 1, 6}, Blocks.STONE.defaultBlockState()));
                tpRel(ctx, 20.5, 9.5, -90f, 0f);
                ctx.waitTicks(10);
                long m2 = LogTap.mark();
                startSampling(ctx);
                tpRel(ctx, 20.5, 6.5, -90f, 0f);
                ctx.waitTicks(40);
                s = stopSampling(ctx);
                boolean running = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(EXECUTOR, "isRunning"));
                standing = serverState(ctx, 22, F + 1, 6).is(Blocks.STONE);
                println("656 node in edit mode: " + countOut(s, "player_action") + " dig(s) in 40 ticks, running "
                        + running + ", stone standing " + standing);
                check(countOut(s, "player_action") == 0, "a breaker node sent digs while edit mode was open");
                check(!running && !logHas(m2, "DUNGEON_BREAKER acted"), "a breaker node fired while edit mode was open");
                check(standing, "the picked stone broke while edit mode was open");

                // Already picked: no second add, no dig.
                startSampling(ctx);
                rightClick(ctx, 22, F + 1, 6, Direction.WEST);
                ctx.waitTicks(10);
                s = stopSampling(ctx);
                check(countOut(s, "player_action") == 0, "re-adding a picked block sent a dig");
                check(breakerBlocks(ctx).size() == 1, "re-adding a picked block changed the list");

                // ---- control: edit mode off, the same step fires the node and it breaks the stone ----
                editMode(ctx, false);
                tpRel(ctx, 20.5, 9.5, -90f, 0f);
                ctx.waitTicks(10);
                long m3 = LogTap.mark();
                tpRel(ctx, 20.5, 6.5, -90f, 0f);
                boolean broke = waitFor(ctx, 40, () -> serverState(ctx, 22, F + 1, 6).isAir());
                println("656 control (edit mode off): node broke the stone " + broke + ", acted "
                        + logHas(m3, "DUNGEON_BREAKER acted"));
                check(broke && logHas(m3, "DUNGEON_BREAKER acted"),
                        "the control breaker node did not break the stone - the edit-mode check proves nothing");
                stopRoute(ctx);

                // Shift-remove in edit mode: nothing broken.
                setBlocks(ctx, Map.of(new int[]{22, F + 1, 6}, Blocks.STONE.defaultBlockState()));
                tpRel(ctx, 20.5, 9.5, -90f, 0f);
                editMode(ctx, true);
                ctx.getInput().holdKey(options -> options.keyShift);
                waitFor(ctx, 20, () -> ctx.computeOnClient(mc -> mc.player.isShiftKeyDown()));
                startSampling(ctx);
                rightClick(ctx, 22, F + 1, 6, Direction.WEST);
                ctx.waitTicks(10);
                s = stopSampling(ctx);
                ctx.getInput().releaseKey(options -> options.keyShift);
                ctx.waitTicks(3);
                println("656 remove: " + countOut(s, "player_action") + " dig(s), picked " + breakerBlocks(ctx));
                check(countOut(s, "player_action") == 0, "a shift-right-click remove sent a dig");
                check(breakerBlocks(ctx).isEmpty(), "shift-right-click did not remove the block");
            } finally {
                ctx.getInput().releaseKey(options -> options.keyShift);
                editMode(ctx, false);
                stopRoute(ctx);
            }
        }

        /** 657, item 1b: in edit mode with the Dungeon Breaker held, his own left click swings, digs and breaks. */
        static void editAttack(ClientGameTestContext ctx) {
            breakerSetup(ctx);
            try {
                setBlocks(ctx, Map.of(new int[]{22, F + 1, 8}, Blocks.STONE.defaultBlockState()));
                tpRel(ctx, 20.5, 8.5, -90f, 0f);
                ctx.waitTicks(5);
                editMode(ctx, true);
                check(aimedAt(ctx, 22, F + 1, 8), "the left click is not aimed at its stone");
                startSampling(ctx);
                leftClick(ctx, 2);
                ctx.waitTicks(10);
                List<Sample> s = stopSampling(ctx);
                boolean serverAir = serverState(ctx, 22, F + 1, 8).isAir();
                println("657 edit-mode left click: " + countOut(s, "swing") + " swing, " + countOut(s, "player_action")
                        + " dig(s), server air " + serverAir + ", picked " + breakerBlocks(ctx));
                check(countOut(s, "swing") >= 1, "the left click in edit mode did not swing");
                check(countOut(s, "player_action") >= 1, "the left click in edit mode sent no dig");
                check(serverAir, "the left click in edit mode did not break the stone on the server");
                check(breakerBlocks(ctx).isEmpty(), "a left click picked a block");
            } finally {
                editMode(ctx, false);
            }
        }

        /**
         * 658, item 1c: "I can manually click on blocks but it says they are too far for my breaker." Reach is from his
         * eye to the block's box, at 4.5 (a plain dig; the wiki gives the Dungeon Breaker no range of its own). The node
         * stands at (6,24), far from both picks - the old measure, from the node, refused both. 4.45 is accepted (the mod
         * sends no dig for a pick), 4.55 is refused.
         */
        static void reach(ClientGameTestContext ctx) {
            breakerSetup(ctx);
            try {
                setBlocks(ctx, Map.of(new int[]{24, F + 1, 12}, Blocks.STONE.defaultBlockState(),
                        new int[]{24, F + 1, 16}, Blocks.STONE.defaultBlockState()));
                editMode(ctx, true);
                // Eye at x 19.55, block face at x 24, eye height inside the block's y span: box distance 4.45.
                tpRel(ctx, 19.55, 12.5, -90f, 0f);
                ctx.waitTicks(5);
                double dIn = eyeToBox(ctx, 24, F + 1, 12);
                long m = LogTap.mark();
                startSampling(ctx);
                rightClick(ctx, 24, F + 1, 12, Direction.WEST);
                ctx.waitTicks(15);
                List<Sample> s = stopSampling(ctx);
                JsonArray after1 = breakerBlocks(ctx);
                println(String.format(Locale.US, "658 at %.3f: picked %s, %d dig(s), server air %s", dIn, after1,
                        countOut(s, "player_action"), serverState(ctx, 24, F + 1, 12).isAir()));
                check(Math.abs(dIn - 4.45) < 0.02, String.format(Locale.US, "the in-reach pick was measured at %.3f, not 4.45", dIn));
                check(after1 != null && after1.size() == 1, "a block 4.45 from his eye was refused: " + after1);
                check(!logHas(m, "out of your reach"), "a block 4.45 from his eye was called out of reach");
                check(countOut(s, "player_action") == 0, "the accepted pick made the mod send " + countOut(s, "player_action")
                        + " digs");

                tpRel(ctx, 19.45, 16.5, -90f, 0f);
                ctx.waitTicks(5);
                double dOut = eyeToBox(ctx, 24, F + 1, 16);
                m = LogTap.mark();
                startSampling(ctx);
                rightClick(ctx, 24, F + 1, 16, Direction.WEST);
                ctx.waitTicks(15);
                s = stopSampling(ctx);
                JsonArray after2 = breakerBlocks(ctx);
                println(String.format(Locale.US, "658 at %.3f: picked %s, %d dig(s)", dOut, after2,
                        countOut(s, "player_action")));
                check(Math.abs(dOut - 4.55) < 0.02, String.format(Locale.US, "the out-of-reach pick was measured at %.3f", dOut));
                check(after2 != null && after2.size() == 1, "a block 4.55 from his eye was accepted: " + after2);
                check(logHas(m, "out of your reach"), "no 'out of your reach' line for the 4.55 pick");
                check(countOut(s, "player_action") == 0, "the refused pick still sent a dig");
            } finally {
                editMode(ctx, false);
            }
        }

        private static JsonObject ewPlaced(int fx, int fz, int tx, int tz) {
            JsonObject o = ew(fx, fz, tx, tz, true);
            o.addProperty("placeEye", String.format(Locale.US, "%.3f %.3f %.3f", fx + 0.5, F + 1.27, fz + 0.5));
            return o;
        }

        private static void setServerBlock(ClientGameTestContext ctx, int x, int y, int z, BlockState state) {
            ctx.runOnClient(mc -> {
                BlockPos pos = real(ModUnderTest.staticCall(FRAME, "current"), x, y, z);
                var server = mc.getSingleplayerServer();
                server.execute(() -> server.overworld().setBlockAndUpdate(pos, state));
            });
        }

        /**
         * 659, item 2: "if it needs to etherwarp somewhere but a block is in the way, have it just wait until that block
         * moves, if it is part of a route where the block wasn't there when the node was placed." A start ew node
         * (8,10) -> (16,10) placed along a clear line; a stone at (12,F,10) put in that line afterwards. Standing on the
         * node: no use for 40 ticks, the route still running, he has not moved. The stone removed on the server: the use
         * goes on the first tick the client sees it gone (at most one tick later) and he lands on (16,10). Controls: a
         * node /ar add-ed with the stone ALREADY there lands on the stone as before (no wait); a node from before
         * placeEye existed warps straight away.
         */
        static void ewBlockWait(ClientGameTestContext ctx) {
            resetRoutes(ctx);
            arena(ctx, false);
            giveHotbar(ctx);
            float yaw = relYawTo(8, 10, 16, 10);
            float pitch = pitchTo(8, 10, 16, 10);
            try {
                // ---- a block that was NOT there at placement: wait, then warp ----
                writeRoute(ctx, List.of(ewPlaced(8, 10, 16, 10)));
                setBlocks(ctx, Map.of(new int[]{12, F, 10}, Blocks.STONE.defaultBlockState()));
                tpRel(ctx, 8.5, 6.5, yaw, pitch);
                ctx.waitTicks(5);
                int waitBefore = executorInt(ctx, "lineWaitTicksTotal");
                long m = LogTap.mark();
                startSampling(ctx);
                tpRel(ctx, 8.5, 10.5, yaw, pitch);
                ctx.waitTicks(40);
                List<Sample> s = stopSampling(ctx);
                boolean running = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(EXECUTOR, "isRunning"));
                Vec3 at = relPos(ctx);
                int waited = executorInt(ctx, "lineWaitTicksTotal") - waitBefore;
                println(String.format(Locale.US, "659 blocked: %d use_item, running %s, at (%.2f, %.2f), waited %d tick(s)",
                        countOut(s, "use_item"), running, at.x, at.z, waited));
                check(logHas(m, "was not there when the node was placed"), "the node never said it was waiting on the block");
                check(countOut(s, "use_item") == 0, "the etherwarp went out with a new block in its line");
                check(running, "the route stopped instead of waiting");
                check(Math.floor(at.x) == 8 && Math.floor(at.z) == 10, "he moved off the node while it waited: " + at);
                check(waited >= 30, "only " + waited + " tick(s) of waiting were counted in 40");

                // Remove it: the warp goes within a tick of the client seeing it gone.
                long m2 = LogTap.mark();
                setServerBlock(ctx, 12, F, 10, Blocks.AIR.defaultBlockState());
                int seenAir = -1;
                int warped = -1;
                for (int t = 0; t < 40 && warped < 0; t++) {
                    if (seenAir < 0 && blockAir(ctx, 12, F, 10)) {
                        seenAir = t;
                    }
                    if (logHas(m2, "is clear again after")) {
                        warped = t;
                    }
                    ctx.waitTicks(1);
                }
                Vec3 landed = waitLanded(ctx, 16, 10, 40);
                println("659 removed: client saw air at t" + seenAir + ", warp at t" + warped + ", landed " + landed);
                check(seenAir >= 0 && warped >= 0, "no warp after the block went (air seen t" + seenAir + ")");
                check(warped - seenAir <= 1, "the warp went " + (warped - seenAir) + " tick(s) after the block was gone");
                check(landed != null, "he did not land on (16,10) after the line cleared (at " + relPos(ctx) + ")");
                stopRoute(ctx);

                // ---- control: the block WAS there when the node was placed - /ar add lands on it, no wait ----
                resetRoutes(ctx);
                setBlocks(ctx, Map.of(new int[]{12, F, 10}, Blocks.STONE.defaultBlockState()));
                ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setStartFromStartNodeOnly", false));
                tpRel(ctx, 8.5, 10.5, yaw, pitch);
                ctx.waitTicks(5);
                waitBefore = executorInt(ctx, "lineWaitTicksTotal");
                cmd(ctx, "/ar add ew");
                boolean onStone = waitFor(ctx, 40, () -> {
                    Vec3 p = relPos(ctx);
                    return Math.floor(p.x) == 12 && Math.floor(p.z) == 10 && Math.abs(p.y - (F + 1)) < 0.2;
                });
                JsonArray nodes = fileNodes(ctx);
                println("659 placed with the block there: on the stone " + onStone + ", node " + nodes + ", waited "
                        + (executorInt(ctx, "lineWaitTicksTotal") - waitBefore));
                check(nodes.size() == 1 && nodes.get(0).getAsJsonObject().has("placeEye"), "/ar add ew saved no placeEye");
                check(onStone, "the node placed with the block there did not warp onto it as before (at " + relPos(ctx) + ")");
                check(executorInt(ctx, "lineWaitTicksTotal") == waitBefore, "a block that was there at placement was waited on");
                stopRoute(ctx);

                // ---- control: a node saved before placeEye existed warps as before, block in its line or not ----
                resetRoutes(ctx);
                ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setStartFromStartNodeOnly", true));
                writeRoute(ctx, List.of(ew(8, 10, 16, 10, true)));
                tpRel(ctx, 8.5, 6.5, yaw, pitch);
                ctx.waitTicks(5);
                waitBefore = executorInt(ctx, "lineWaitTicksTotal");
                startSampling(ctx);
                tpRel(ctx, 8.5, 10.5, yaw, pitch);
                ctx.waitTicks(15);
                s = stopSampling(ctx);
                println("659 legacy node: " + countOut(s, "use_item") + " use_item in 15 ticks, waited "
                        + (executorInt(ctx, "lineWaitTicksTotal") - waitBefore));
                check(countOut(s, "use_item") >= 1, "a node without placeEye did not warp as before");
                check(executorInt(ctx, "lineWaitTicksTotal") == waitBefore, "a node without placeEye waited");
            } finally {
                ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setStartFromStartNodeOnly", true));
                stopRoute(ctx);
                setServerBlock(ctx, 12, F, 10, Blocks.AIR.defaultBlockState());
                ctx.waitTicks(3);
            }
        }

        /** GLFW keypad 8: bound to Add Jump Node for 872 only (no character, nothing else uses it). */
        private static final int JUMP_KEY = 328;

        private static net.minecraft.client.gui.components.EditBox chatInput(net.minecraft.client.gui.screens.ChatScreen cs) {
            try {
                java.lang.reflect.Field f = net.minecraft.client.gui.screens.ChatScreen.class.getDeclaredField("input");
                f.setAccessible(true);
                return (net.minecraft.client.gui.components.EditBox) f.get(cs);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }

        private static int chatPackets(List<Sample> s) {
            int n = 0;
            for (Sample x : s) {
                n += (int) x.out().stream().filter(o -> o.contains("chat")).count();
            }
            return n;
        }

        /**
         * 872 (killer560, 2026-10-12: "Type the command out with the player ready to type in the number of jumps"). The
         * Add Jump Node key, pressed for real (bound to keypad 8, read by the mod's raw poll), opens chat holding exactly
         * "/ar add jump " with the cursor at the end, and for 20 ticks nothing is sent (no chat or command packet, no node).
         * Typing 2 and pressing Enter then sends it and adds a jump node of 2.
         */
        static void jumpKey(ClientGameTestContext ctx) {
            resetRoutes(ctx);
            arena(ctx, false);
            tpRel(ctx, 10.5, 20.5, 0f, 0f);
            ctx.waitTicks(10);
            ctx.runOnClient(mc -> ModUnderTest.call(ModUnderTest.config(AR_CONFIG), "setKeybind",
                    new Class<?>[]{String.class, int.class}, new Object[]{"add_jump", JUMP_KEY}));
            try {
                startSampling(ctx);
                ctx.getInput().holdKeyFor(JUMP_KEY, 3);
                boolean opened = waitFor(ctx, 20, () -> ctx.computeOnClient(mc ->
                        McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.ChatScreen));
                check(opened, "the Add Jump Node key did not open chat (screen "
                        + ctx.computeOnClient(mc -> String.valueOf(McCompat.screen(mc))) + ")");
                String text = ctx.computeOnClient(mc -> chatInput((net.minecraft.client.gui.screens.ChatScreen) McCompat.screen(mc)).getValue());
                int cursor = ctx.computeOnClient(mc -> chatInput((net.minecraft.client.gui.screens.ChatScreen) McCompat.screen(mc)).getCursorPosition());
                ctx.waitTicks(20);
                List<Sample> s = stopSampling(ctx);
                boolean stillOpen = ctx.computeOnClient(mc ->
                        McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.ChatScreen);
                println("872 key: chat '" + text + "' cursor " + cursor + "/" + text.length() + ", still open " + stillOpen
                        + ", chat packets " + chatPackets(s) + ", nodes " + fileNodes(ctx).size());
                check("/ar add jump ".equals(text), "chat holds '" + text + "', expected '/ar add jump '");
                check(cursor == text.length(), "the cursor is at " + cursor + ", not the end (" + text.length() + ")");
                check(stillOpen, "the chat closed by itself");
                check(chatPackets(s) == 0, "something was sent before Enter: " + chatPackets(s) + " chat packet(s)");
                check(fileNodes(ctx).isEmpty(), "a node was added before Enter");

                // He types the number and presses Enter: now it goes.
                ctx.getInput().typeChars("2");
                ctx.waitTicks(2);
                ctx.getInput().pressKey(257); // GLFW_KEY_ENTER
                boolean added = waitFor(ctx, 40, () -> !fileNodes(ctx).isEmpty());
                JsonArray nodes = fileNodes(ctx);
                println("872 after '2' + Enter: nodes " + nodes);
                check(added && nodes.size() == 1 && "JUMP".equals(nodes.get(0).getAsJsonObject().get("type").getAsString())
                        && nodes.get(0).getAsJsonObject().get("jumps").getAsInt() == 2, "Enter did not add a jump 2 node: " + nodes);
            } finally {
                ctx.runOnClient(mc -> {
                    ModUnderTest.call(ModUnderTest.config(AR_CONFIG), "setKeybind",
                            new Class<?>[]{String.class, int.class}, new Object[]{"add_jump", -1});
                    if (McCompat.screen(mc) != null) {
                        McCompat.setScreen(mc, null);
                    }
                });
                stopRoute(ctx);
                ctx.waitTicks(40);
                resetRoutes(ctx);
            }
        }

        /** Server-side (integrated server) on-ground flag of the player, read from the render thread (a plain field). */
        private static boolean serverOnGround(ClientGameTestContext ctx) {
            return ctx.computeOnClient(mc -> {
                var sp = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
                return sp != null && sp.onGround();
            });
        }

        /** Server-side take-offs (on ground -> off) over {@code ticks} ticks, polled each tick; a jump is ~12 ticks up. */
        private static int serverTakeoffs(ClientGameTestContext ctx, int ticks, Runnable first) {
            boolean last = serverOnGround(ctx);
            if (first != null) {
                first.run();
            }
            int n = 0;
            for (int t = 0; t < ticks; t++) {
                boolean g = serverOnGround(ctx);
                if (last && !g) {
                    n++;
                }
                last = g;
                ctx.waitTicks(1);
            }
            return n;
        }

        /**
         * 870 (killer560, 2026-10-12: "you have to type in jump _. That number is how many times it will jump ... If you do
         * /ar add jump just like that then it should send a red message"). Bare, 0 and x add no node and give the red
         * line. /ar add jump 3 saves jumps 3 and, fired on add, takes off 3 times on the SERVER, then the route completes.
         * A written route of jump 2 (start) with a walk stacked on its tile: 2 take-offs, then the walk acts (the route
         * carries on). A node written without a count loads as 1.
         */
        static void jumpCount(ClientGameTestContext ctx) {
            resetRoutes(ctx);
            arena(ctx, false);
            tpRel(ctx, 10.5, 20.5, 0f, 0f);
            ctx.waitTicks(10);
            for (String bad : new String[]{"/ar add jump", "/ar add jump 0", "/ar add jump x", "/ar add jump 21"}) {
                long m = LogTap.mark();
                cmd(ctx, bad);
                ctx.waitTicks(5);
                boolean red = logHasAll(m, "[CHAT]", "Please add a number of jumps: /ar add jump <number>");
                println("870 '" + bad + "': nodes " + fileNodes(ctx).size() + ", red line " + red);
                check(fileNodes(ctx).isEmpty(), "'" + bad + "' added a node");
                check(red, "'" + bad + "' did not say 'Please add a number of jumps'");
            }

            int pressesBefore = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(EXECUTOR, "jumpPresses"));
            long m = LogTap.mark();
            int offs = serverTakeoffs(ctx, 80, () -> cmd(ctx, "/ar add jump 3 await:1"));
            JsonArray nodes = fileNodes(ctx);
            int presses = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(EXECUTOR, "jumpPresses")) - pressesBefore;
            boolean running = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(EXECUTOR, "isRunning"));
            println("870 jump 3 await:1: nodes " + nodes + ", presses " + presses + ", server take-offs " + offs
                    + ", running after " + running);
            check(nodes.size() == 1 && "JUMP".equals(nodes.get(0).getAsJsonObject().get("type").getAsString())
                    && nodes.get(0).getAsJsonObject().get("jumps").getAsInt() == 3
                    && nodes.get(0).getAsJsonObject().has("awaitEnabled"), "/ar add jump 3 await:1 saved " + nodes);
            stopRoute(ctx);

            // Without the await, so it fires on add: exactly 3 presses, 3 server take-offs, then complete.
            resetRoutes(ctx);
            tpRel(ctx, 10.5, 20.5, 0f, 0f);
            ctx.waitTicks(10);
            pressesBefore = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(EXECUTOR, "jumpPresses"));
            m = LogTap.mark();
            offs = serverTakeoffs(ctx, 80, () -> cmd(ctx, "/ar add jump 3"));
            nodes = fileNodes(ctx);
            presses = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(EXECUTOR, "jumpPresses")) - pressesBefore;
            println("870 jump 3: nodes " + nodes + ", presses " + presses + ", server take-offs " + offs + ", complete "
                    + logHas(m, "complete"));
            check(nodes.size() == 1 && nodes.get(0).getAsJsonObject().get("jumps").getAsInt() == 3, "jump 3 saved " + nodes);
            check(presses == 3, "jump 3 pressed " + presses + " time(s)");
            check(offs == 3, "jump 3 took off " + offs + " time(s) on the server");
            check(logHas(m, "JUMP: jump 3 of 3") && logHas(m, "complete"), "jump 3 did not finish its third jump and complete");

            // The route carries on: jump 2 (start) with a walk stacked on its tile.
            resetRoutes(ctx);
            JsonObject j = node("JUMP", 10, 20, 0f, 0f);
            j.addProperty("jumps", 2);
            j.addProperty("start", true);
            // Off the tile first: a route written under his feet arms at once.
            tpRel(ctx, 10.5, 16.5, 0f, 0f);
            ctx.waitTicks(10);
            writeRoute(ctx, List.of(j, node("WALK", 10, 20, 0f, 0f)));
            ctx.waitTicks(5);
            m = LogTap.mark();
            offs = serverTakeoffs(ctx, 60, () -> tpRel(ctx, 10.5, 20.5, 0f, 0f));
            int iJump2 = logIndex(m, "JUMP: jump 2 of 2");
            int iWalk = logIndex(m, "WALK acted");
            println("870 jump 2 + walk: server take-offs " + offs + ", jump 2 at " + iJump2 + ", walk at " + iWalk);
            check(offs >= 2, "the written jump 2 took off " + offs + " time(s)");
            check(iJump2 >= 0 && iWalk > iJump2, "the walk did not act after the second jump (the route did not carry on)");
            stopRoute(ctx);

            // An old node with no count loads as 1.
            resetRoutes(ctx);
            writeRoute(ctx, List.of(node("JUMP", 10, 20, 0f, 0f)));
            int loaded = ctx.computeOnClient(mc -> {
                try {
                    Object n = ((List<?>) ModUnderTest.staticCall(FEATURE, "currentRouteNodes")).get(0);
                    return n.getClass().getField("jumpCount").getInt(n);
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }
            });
            println("870 old node: jumpCount " + loaded);
            check(loaded == 1, "a jump node without a count loaded as " + loaded);
            resetRoutes(ctx);
        }

        /**
         * 662, item 3: "Add a jump node to auto routes where it just jumps once." /ar add jump is offered and makes a JUMP
         * node, which fires on add: exactly one jump in the input packets (one rising edge), he leaves the ground once and
         * rises over half a block.
         */
        static void jump(ClientGameTestContext ctx) {
            resetRoutes(ctx);
            arena(ctx, false);
            List<String> words = ctx.computeOnClient(mc -> suggest(mc, "ar add "));
            check(words.contains("jump"), "'ar add ' does not offer jump: " + words);
            tpRel(ctx, 10.5, 20.5, 0f, 0f);
            ctx.waitTicks(10);
            int pressesBefore = executorInt(ctx, "jumpPresses");
            // Every client tick's END: the input packet's jump and the ground flag, so a press that rises and falls
            // inside cmd()'s own waits is still seen.
            ctx.runOnClient(mc -> {
                if (!jumpHooked) {
                    jumpHooked = true;
                    ClientTickEvents.END_CLIENT_TICK.register(c -> {
                        if (jumpSampling && c.player != null) {
                            synchronized (JUMP_SAMPLES) {
                                JUMP_SAMPLES.add(new double[]{c.player.getLastSentInput().jump() ? 1 : 0,
                                        c.player.onGround() ? 1 : 0, c.player.getY()});
                            }
                        }
                    });
                }
                synchronized (JUMP_SAMPLES) {
                    JUMP_SAMPLES.clear();
                }
                jumpSampling = true;
            });
            ctx.waitTicks(2);
            cmd(ctx, "/ar add jump 1"); // the count is required since mod ar-jump-n
            ctx.waitTicks(40);
            ctx.runOnClient(mc -> jumpSampling = false);
            int rises = 0;
            int takeoffs = 0;
            double y0 = Double.NaN;
            double maxY = Double.NEGATIVE_INFINITY;
            synchronized (JUMP_SAMPLES) {
                for (int i = 1; i < JUMP_SAMPLES.size(); i++) {
                    double[] p = JUMP_SAMPLES.get(i - 1);
                    double[] c = JUMP_SAMPLES.get(i);
                    if (c[0] == 1 && p[0] == 0) {
                        rises++;
                    }
                    if (p[1] == 1 && c[1] == 0) {
                        takeoffs++;
                    }
                    maxY = Math.max(maxY, c[2]);
                }
                y0 = JUMP_SAMPLES.isEmpty() ? Double.NaN : JUMP_SAMPLES.get(0)[2];
            }
            check(JUMP_SAMPLES.size() >= 40, "only " + JUMP_SAMPLES.size() + " tick(s) sampled");
            double rose = maxY - y0;
            JsonArray nodes = fileNodes(ctx);
            int presses = executorInt(ctx, "jumpPresses") - pressesBefore;
            println(String.format(Locale.US, "662 jump: %d jump input(s), %d take-off(s), rose %.2f, presses %d, nodes %s",
                    rises, takeoffs, rose, presses, nodes));
            check(nodes.size() == 1 && "JUMP".equals(nodes.get(0).getAsJsonObject().get("type").getAsString()),
                    "/ar add jump did not make one JUMP node: " + nodes);
            check(presses == 1, "the jump node pressed " + presses + " time(s)");
            check(rises == 1, rises + " jump input(s) went to the server, expected exactly 1");
            check(takeoffs == 1, "he left the ground " + takeoffs + " time(s), expected once");
            check(rose > 0.5, String.format(Locale.US, "he rose only %.2f", rose));
        }
    }

    // ============================================================================================ ar-jump-cooldown (2026-10-14)

    /**
     * 96-ar-1014-jumpcd (killer560, 2026-10-14: "if i make a jump node with 1, then make it so it jumps once then times out
     * for 3s before it will trigger that node again. Right now it stops jumping but then falls right back onto the node").
     * The jump lifts him out of the 0.1-high ring, which clears the latch, so the landing armed the node again.
     */
    static final class ArJumpCooldownCases {
        private ArJumpCooldownCases() {
        }

        /** Per client tick: real ms, server on-ground, client on-ground, relative tile x, z, jump presses so far. */
        private static final List<double[]> CD_SAMPLES = new ArrayList<>();
        private static volatile boolean cdSampling;
        private static boolean cdHooked;

        private static void startCd(ClientGameTestContext ctx) {
            ctx.runOnClient(mc -> {
                if (!cdHooked) {
                    cdHooked = true;
                    ClientTickEvents.END_CLIENT_TICK.register(c -> {
                        if (!cdSampling || c.player == null || c.getSingleplayerServer() == null) {
                            return;
                        }
                        var sp = c.getSingleplayerServer().getPlayerList().getPlayer(c.player.getUUID());
                        Object frame = ModUnderTest.staticCall(FRAME, "current");
                        Vec3 rel = frame == null ? c.player.position() : (Vec3) ModUnderTest.staticCall(COORDS,
                                "toRelative", new Class<?>[]{frame.getClass(), Vec3.class},
                                new Object[]{frame, c.player.position()});
                        synchronized (CD_SAMPLES) {
                            CD_SAMPLES.add(new double[]{System.currentTimeMillis(), sp != null && sp.onGround() ? 1 : 0,
                                    c.player.onGround() ? 1 : 0, Math.floor(rel.x), Math.floor(rel.z),
                                    ((Number) ModUnderTest.staticCall(EXECUTOR, "jumpPresses")).doubleValue()});
                        }
                    });
                }
                synchronized (CD_SAMPLES) {
                    CD_SAMPLES.clear();
                }
                cdSampling = true;
            });
        }

        /** Stops sampling; the real-ms times of every take-off (on ground -> off) in column {@code col} once the jump node
         *  has pressed (the teleport onto the node can drop the ground flag on its own: jump 3's first run counted one). */
        private static List<Long> stopCd(ClientGameTestContext ctx, int col, int[] offTile, int tileX, int tileZ) {
            ctx.runOnClient(mc -> cdSampling = false);
            List<Long> out = new ArrayList<>();
            synchronized (CD_SAMPLES) {
                for (int i = 1; i < CD_SAMPLES.size(); i++) {
                    double[] p = CD_SAMPLES.get(i - 1);
                    double[] c = CD_SAMPLES.get(i);
                    if (p[col] == 1 && c[col] == 0 && c[5] > CD_SAMPLES.get(0)[5]) {
                        out.add((long) c[0]);
                    }
                    if (offTile != null && (c[3] != tileX || c[4] != tileZ)) {
                        offTile[0]++;
                    }
                }
            }
            return out;
        }

        static void jumpCooldown(ClientGameTestContext ctx) {
            if (!dev.testkit.gametest.mod.Mod.isCheat()) {
                // A legit jar registers Auto Routes but never fires a node (first legit run: 0 presses, 0 take-offs).
                caseSkip = "Auto Routes fires nodes only in a cheat build";
                return;
            }
            long cd = ctx.computeOnClient(mc -> ((Number) ModUnderTest.staticCall(EXECUTOR, "jumpCooldownMs")).longValue());
            resetRoutes(ctx);
            arena(ctx, false);
            giveHotbar(ctx);
            // A count-1 and a count-3 node, each standing still on it the whole time: its jumps, then no take-off for the
            // cooldown, then (the jump took him out of the ring, which the latch rules count as stepping off) it fires
            // again - the control that the cooldown, not a latch, held it.
            for (int count : new int[]{1, 3}) {
                resetRoutes(ctx);
                tpRel(ctx, 10.5, 16.5, 0f, 0f); // off the tile first: a route written under his feet arms at once
                ctx.waitTicks(10);
                JsonObject j = node("JUMP", 10, 20, 0f, 0f);
                j.addProperty("jumps", count);
                j.addProperty("start", true);
                writeRoute(ctx, List.of(j));
                ctx.waitTicks(5);
                int pressesBefore = ArNodesCases.executorInt(ctx, "jumpPresses");
                long m = LogTap.mark();
                startCd(ctx);
                tpRel(ctx, 10.5, 20.5, 0f, 0f);
                long t0 = System.currentTimeMillis();
                // Long enough for the jumps, the cooldown and the re-fire's first take-off.
                for (int t = 0; t < 600 && System.currentTimeMillis() - t0 < count * 1000L + cd + 2500; t++) {
                    ctx.waitTicks(1);
                }
                int[] off = {0};
                // Judged on the CLIENT's take-offs: the server's on-ground flag also drops once for the teleport onto
                // the node (first run: 4 server take-offs for 3 presses, the first 545 ms before the second).
                List<Long> client = stopCd(ctx, 2, off, 10, 20);
                List<Long> server = stopCd(ctx, 1, null, 0, 0);
                int presses = ArNodesCases.executorInt(ctx, "jumpPresses") - pressesBefore;
                stopRoute(ctx);
                boolean cdLine = logHas(m, "jump cooldown");
                StringBuilder gaps = new StringBuilder();
                for (int i = 1; i < client.size(); i++) {
                    gaps.append(i > 1 ? ", " : "").append(client.get(i) - client.get(i - 1));
                }
                println(String.format(Locale.US, "1014 jump %d: client take-offs %d (gaps ms %s), server take-offs %d, "
                        + "presses %d, ticks off the tile %d, cooldown line %s", count, client.size(), gaps,
                        server.size(), presses, off[0], cdLine));
                check(off[0] == 0, "jump " + count + ": he left the tile horizontally for " + off[0] + " tick(s)");
                check(client.size() >= count + 1, "jump " + count + ": " + client.size() + " take-off(s), expected "
                        + count + " then one more after the cooldown");
                check(server.size() >= client.size(), "jump " + count + ": the server saw " + server.size()
                        + " take-off(s) for the client's " + client.size());
                check(presses - client.size() >= 0 && presses - client.size() <= 1, "jump " + count + ": " + presses + " press(es) for " + client.size()
                        + " take-off(s)");
                for (int i = 1; i < count; i++) {
                    check(client.get(i) - client.get(i - 1) < 1500, "jump " + count + ": jump " + (i + 1) + " came "
                            + (client.get(i) - client.get(i - 1)) + " ms after the one before (not in a row)");
                }
                long gap = client.get(count) - client.get(count - 1);
                // The cooldown starts the tick after the last press (that jump's take-off), so the next take-off is at
                // least the cooldown later, and soon after it ends.
                check(gap >= cd - 50, "jump " + count + ": the node jumped again " + gap + " ms after its last jump, inside "
                        + "the " + cd + " ms cooldown");
                check(gap <= cd + 1500, "jump " + count + ": nothing fired for " + gap + " ms after the cooldown");
                check(cdLine, "jump " + count + ": no 'jump cooldown' arming line in the log");
            }

            // A route that carries on after the jump goes at once: jump 1 (start) at (10,20), an etherwarp at (10,21) to
            // (10,25). Rings 1.0 wide, and he stands at z 20.95, in both.
            resetRoutes(ctx);
            tpRel(ctx, 10.5, 16.5, 0f, 0f);
            ctx.waitTicks(10);
            JsonObject j = node("JUMP", 10, 20, 0f, 0f);
            j.addProperty("jumps", 1);
            j.addProperty("start", true);
            j.addProperty("radius", 1.0);
            JsonObject e = ew(10, 21, 10, 25, false);
            e.addProperty("radius", 1.0);
            writeRoute(ctx, List.of(j, e));
            ctx.waitTicks(5);
            long m = LogTap.mark();
            startCd(ctx);
            tpRel(ctx, 10.5, 20.95, 0f, 0f);
            Vec3 landed = waitLanded(ctx, 10, 25, 100);
            long landedAt = System.currentTimeMillis();
            List<Long> offs = stopCd(ctx, 2, null, 0, 0);
            stopRoute(ctx);
            int iJump = logIndex(m, "JUMP acted");
            int iWarp = logIndex(m, "ETHERWARP acted");
            long takeoffToLanding = offs.isEmpty() ? -1 : landedAt - offs.get(0);
            println("1014 jump -> etherwarp: landed " + landed + ", take-offs " + offs.size() + ", take-off to landing "
                    + takeoffToLanding + " ms, jump at " + iJump + ", warp at " + iWarp);
            check(iJump >= 0 && iWarp > iJump, "the etherwarp did not act after the jump");
            check(landed != null, "the route did not carry on to the etherwarp's landing at (10,25); he is at " + relPos(ctx));
            check(!offs.isEmpty() && takeoffToLanding < cd - 500, "the etherwarp waited " + takeoffToLanding
                    + " ms after the jump (held by the cooldown?)");
            resetRoutes(ctx);
        }
    }

    // ============================================================================================ path-wait (2026-10-11)

    /**
     * 96-ar-639-legit-warp-exact: 644/651's warp - an etherwarp start node at (14,12) aimed at the top centre of the
     * floor block at (14,5), 0.7 degrees of margin - fired 20 times in LEGIT mode, each from a fresh look turned away and
     * down (50-86 degrees, as after Kill Mimic's Wither Impact), so every run is a real turn up to the warp's pitch. Every one must land on (14,5), as the same node does in obvious
     * mode (run once first as the control). The jar before path-wait clicked once the camera was within 1 degree and
     * landed about one run in three a block short, on (14,6).
     */
    private void caseLegitWarpExact(ClientGameTestContext ctx) {
        if (!legitSelectable()) {
            caseSkip = LEGIT_ONLY_TESTING;
            return;
        }
        resetRoutes(ctx);
        arena(ctx, false);
        giveHotbar(ctx);
        List<String> failures = new ArrayList<>();
        int exact = 0;
        int runs = 0;
        try {
            for (int run = 0; run <= 20; run++) {
                boolean legit = run > 0;
                stopRoute(ctx);
                ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setLegitMode", legit));
                ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(3));
                writeRoute(ctx, List.of(ew(14, 12, 14, 5, true)));
                // Arrive looking well away and DOWN (a different way each run), as 644/651 do after Kill Mimic's
                // straight-down Wither Impact: the turn comes up to the warp's pitch from below it, so a click before it
                // has settled aims short.
                float away = relYawTo(14, 12, 14, 5) + 40f + 13f * run;
                if (!arm(ctx, 14, 12, away, 50f + (run % 5) * 9f)) {
                    failures.add("run " + run + ": #1 did not arm");
                    continue;
                }
                Vec3 p = null;
                boolean landed = false;
                for (int t = 0; t < 200 && !landed; t++) {
                    ctx.waitTicks(1);
                    p = relPos(ctx);
                    landed = Math.abs(p.z - 12.5) > 2 && Math.abs(p.y - F) < 0.2 && ctx.computeOnClient(mc ->
                            mc.player.onGround());
                }
                ctx.waitTicks(5);
                p = relPos(ctx);
                boolean onTarget = Math.floor(p.x) == 14 && Math.floor(p.z) == 5 && Math.abs(p.y - F) < 0.2;
                println(String.format(Locale.US, "639 run %d (%s): landed at (%.2f, %.2f, %.2f) - %s", run,
                        legit ? "legit" : "obvious control", p.x, p.y, p.z, onTarget ? "on (14,5)" : "NOT on (14,5)"));
                if (!onTarget) {
                    failures.add(String.format(Locale.US, "run %d (%s) landed at (%.2f, %.2f), not on (14,5)", run,
                            legit ? "legit" : "obvious", p.x, p.z));
                }
                if (legit) {
                    runs++;
                    exact += onTarget ? 1 : 0;
                }
            }
        } finally {
            stopRoute(ctx);
            ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setLegitMode", false));
        }
        println("639: " + exact + " of " + runs + " legit warps landed exactly on (14,5)");
        check(failures.isEmpty() && runs == 20, String.join("; ", failures));
    }

    // ============================================================================================ ar-obvious-only (2026-10-11)

    /** Set by a case that cannot run on this jar; the session loop records it as a SKIP row instead of a PASS. */
    private static String caseSkip;

    static final String LEGIT_ONLY_TESTING = "Auto Routes legit mode exists only in -PtestingBuild jars (mod ar-obvious-only)";

    /**
     * Whether this jar lets Auto Routes run in legit mode: since mod ar-obvious-only only a {@code -PtestingBuild} jar
     * does ({@code AutoRoutesConfig.legitModeSelectable()}). A jar from before that method always did.
     */
    static boolean legitSelectable() {
        try {
            return (Boolean) Class.forName(AR_CONFIG).getMethod("legitModeSelectable").invoke(null);
        } catch (NoSuchMethodException | ClassNotFoundException e) {
            // An older jar (legit always selectable), or no killer560smod at all (its cases are SKIP rows anyway).
            return true;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not ask " + AR_CONFIG + ".legitModeSelectable", e);
        }
    }

    /**
     * 96-ar-786-obvious-only (killer560, 2026-10-11: "remove the mode as an option and only have obvious"). The config
     * FILE is written with {@code legitMode: true} and reloaded from disk. On a normal jar: {@code isLegitMode()} reads
     * false, the Auto Routes tab builds no "Mode:" row (Start From Start Node Only still there, so the tab did build), and
     * an etherwarp start node, armed while looking 40 degrees away and down, logs "obvious mode", lands on (14,5), and
     * leaves the camera where it was. On a {@code -PtestingBuild} jar the same file reads legit, the Mode row is there,
     * the warp logs "legit mode", lands, and the camera turned to it.
     */
    private void caseObviousOnly(ClientGameTestContext ctx) {
        boolean testing = legitSelectable();
        resetRoutes(ctx);
        arena(ctx, false);
        giveHotbar(ctx);
        stopRoute(ctx);
        Path file = ModUnderTest.modConfig("killer560smod-autoroutes-settings.json");
        try {
            ctx.runOnClient(mc -> {
                Object ar = ModUnderTest.config(AR_CONFIG);
                ModUnderTest.set(ar, "setLegitMode", true);
                ModUnderTest.call(ar, "save", new Class<?>[0], new Object[0]);
                ModUnderTest.staticCall(AR_CONFIG, "load", new Class<?>[0], new Object[0]);
            });
            String json;
            try {
                json = Files.readString(file, StandardCharsets.UTF_8);
            } catch (java.io.IOException e) {
                throw new AssertionError("could not read " + file, e);
            }
            check(json.replaceAll("\\s", "").contains("\"legitMode\":true"),
                    "the reloaded config file does not say legitMode true: " + json);
            boolean legitNow = ctx.computeOnClient(mc ->
                    ModUnderTest.getBoolean(ModUnderTest.config(AR_CONFIG), "isLegitMode"));
            List<String> labels = ctx.computeOnClient(mc -> {
                try {
                    Object tab = Class.forName("com.killer560.hub.gui.tab.AutoRoutesTab").getConstructor().newInstance();
                    java.lang.reflect.Method build = tab.getClass().getMethod("buildWidgets", int.class, int.class,
                            int.class, Runnable.class);
                    List<String> out = new ArrayList<>();
                    for (Object w : (List<?>) build.invoke(tab, 0, 0, 300, (Runnable) () -> {
                    })) {
                        out.add(strip(((AbstractWidget) w).getMessage().getString()));
                    }
                    return out;
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }
            });
            boolean modeRow = labels.stream().anyMatch(l -> l.startsWith("Mode:"));
            boolean startOnlyRow = labels.stream().anyMatch(l -> l.startsWith("Start From Start Node Only"));
            println("786: " + (testing ? "testing" : "normal") + " jar, config file legitMode true, isLegitMode() "
                    + legitNow + ", Mode row " + modeRow + ", tab rows " + labels);
            check(startOnlyRow, "the Auto Routes tab built no Start From Start Node Only row: " + labels);
            check(legitNow == testing, (testing ? "a testing" : "a normal") + " jar reads isLegitMode() " + legitNow
                    + " from a config file saying legitMode true");
            check(modeRow == testing, (testing ? "a testing jar has no" : "a normal jar still shows the")
                    + " Auto Routes Mode row: " + labels);

            // The route itself: arm the warp looking well away and down, as 639 does.
            ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(3));
            writeRoute(ctx, List.of(ew(14, 12, 14, 5, true)));
            float away = relYawTo(14, 12, 14, 5) + 40f;
            long m = LogTap.mark();
            check(arm(ctx, 14, 12, away, 50f), "the etherwarp start node at (14,12) did not arm");
            float view0 = ctx.computeOnClient(mc -> mc.player.getViewYRot(1f));
            Vec3 p = null;
            boolean landed = false;
            for (int i = 0; i < 200 && !landed; i++) {
                ctx.waitTicks(1);
                p = relPos(ctx);
                landed = Math.abs(p.z - 12.5) > 2 && Math.abs(p.y - F) < 0.2 && ctx.computeOnClient(mc ->
                        mc.player.onGround());
            }
            ctx.waitTicks(5);
            p = relPos(ctx);
            float view1 = ctx.computeOnClient(mc -> mc.player.getViewYRot(1f));
            float turned = Math.abs(net.minecraft.util.Mth.wrapDegrees(view1 - view0));
            float warpRel = relYawTo(14, 12, 14, 5);
            float warpYaw = ctx.computeOnClient(mc -> {
                Object frame = ModUnderTest.staticCall(FRAME, "current");
                return (Float) ModUnderTest.staticCall(COORDS, "toRealYaw", new Class<?>[]{frame.getClass(),
                        float.class}, new Object[]{frame, warpRel});
            });
            float offWarp = Math.abs(net.minecraft.util.Mth.wrapDegrees(view1 - warpYaw));
            String begins = null;
            for (String l : LogTap.since(m)) {
                if (l.contains("ETHERWARP begins")) {
                    begins = l;
                    break;
                }
            }
            boolean onTarget = Math.floor(p.x) == 14 && Math.floor(p.z) == 5 && Math.abs(p.y - F) < 0.2;
            println(String.format(Locale.US, "786: landed at (%.2f, %.2f, %.2f) %s, camera moved %.1f degrees after arming and ends %.1f off the warp's yaw, log: %s",
                    p.x, p.y, p.z, onTarget ? "on (14,5)" : "NOT on (14,5)", turned, offWarp, begins));
            String want = testing ? "legit mode" : "obvious mode";
            check(begins != null && begins.contains(want), "the etherwarp did not run in " + want + ": " + begins);
            check(onTarget, String.format(Locale.US, "the warp landed at (%.2f, %.2f), not on (14,5)", p.x, p.z));
            if (testing) {
                check(offWarp < 5f, "legit mode on a testing jar did not turn the camera to the warp (" + offWarp
                        + " degrees off)");
            } else {
                check(turned < 1f && offWarp > 30f, "obvious mode turned the camera: moved " + turned
                        + " degrees, ends " + offWarp + " off the warp's yaw");
            }
        } finally {
            stopRoute(ctx);
            ctx.runOnClient(mc -> {
                Object ar = ModUnderTest.config(AR_CONFIG);
                ModUnderTest.set(ar, "setLegitMode", false);
                ModUnderTest.call(ar, "save", new Class<?>[0], new Object[0]);
            });
        }
    }

    // ============================================================================================ sim-world (2026-10-11)

    /**
     * 96-ar-644-mimic-warp (killer560, 2026-10-09: "if a chest is a mimic then the auto routes kill the mimic then teleport
     * in the direction of the mimic"). Node #1 is an etherwarp start node with await secret 1, aimed AWAY from the mimic's
     * trapped chest (#1 at (14,12), its warp to (14,5), the chest at (14,14)). He turns to the chest and clicks it, Kill
     * Mimic kills the mimic, the kill meets the await and #1 warps. Every tick his relative position is read: he must end
     * on (14,5) and never go more than half a block towards the chest (+z) on the way. Once per Kill Mimic item and mode
     * (Spirit Sceptre / Hyperion, obvious / legit); every use packet is printed with the sneak the server had for it and
     * every server-side move with its tick, so a failure names the packet that moved him.
     */
    // "aura": Secret Aura clicks the chest (his setup) instead of his hand.
    private static final String[][] MIMIC_MODES = {{"SPIRIT_SCEPTRE", "obvious"}, {"HYPERION", "obvious"},
            {"SPIRIT_SCEPTRE", "legit"}, {"HYPERION", "legit"}, {"SPIRIT_SCEPTRE", "obvious", "aura"},
            {"HYPERION", "obvious", "aura"}, {"SPIRIT_SCEPTRE", "legit", "aura"}, {"HYPERION", "legit", "aura"},
            {"HYPERION", "obvious", "aura"}, {"HYPERION", "obvious", "aura"}};

    /**
     * 96-ar-651-mimic-legit-pitch: legit mode with Secret Aura clicking the chest, both Kill Mimic items. On main 2c1065dc
     * the AIM timeout fired the Hyperion at 46.7-62.3 (the aura's turn and hand-back fought the camera approach); since
     * hype-down every use the server takes must be at pitch exactly 90.0 (644 checks the same on every variant), and the
     * mimic must still die.
     */
    private static final String[][] LEGIT_AURA_HYPE = {{"HYPERION", "legit", "aura"}, {"HYPERION", "legit", "aura"},
            {"SPIRIT_SCEPTRE", "legit", "aura"}, {"SPIRIT_SCEPTRE", "legit", "aura"}, {"HYPERION", "legit"},
            {"SPIRIT_SCEPTRE", "legit"}, {"HYPERION", "obvious"}, {"SPIRIT_SCEPTRE", "obvious"}};

    /** The exact pitch (Float.toString) of every Hyperion / Spirit Sceptre use the sim's server logged since {@code mark}
     *  - its own log lines only, not this class's reprints of them. */
    /** Every sceptre / Wither Impact use the sim's server took, as {pitch, yaw} (yaw NaN on a jar whose log has none). */
    private static List<float[]> serverUseAims(long mark) {
        List<float[]> out = new ArrayList<>();
        java.util.regex.Pattern pat = java.util.regex.Pattern.compile(
                "\\[Sim\\] (?:Wither Impact|Spirit Sceptre) at [^(]*\\(pitch ([^)]+)\\)(?: yaw (\\S+))?");
        for (String l : LogTap.since(mark)) {
            if (l.contains("[STDOUT]")) {
                continue;
            }
            java.util.regex.Matcher pm = pat.matcher(l);
            if (pm.find()) {
                out.add(new float[]{Float.parseFloat(pm.group(1)),
                        pm.group(2) == null ? Float.NaN : Float.parseFloat(pm.group(2))});
            }
        }
        return out;
    }

    private static List<String> serverUsePitches(long mark) {
        List<String> out = new ArrayList<>();
        java.util.regex.Pattern pat = java.util.regex.Pattern.compile(
                "\\[Sim\\] (?:Wither Impact|Spirit Sceptre) at [^(]*\\(pitch ([^)]+)\\)");
        for (String l : LogTap.since(mark)) {
            if (l.contains("[STDOUT]")) {
                continue;
            }
            java.util.regex.Matcher pm = pat.matcher(l);
            if (pm.find()) {
                out.add(pm.group(1));
            }
        }
        return out;
    }

    private void caseMimicWarp(ClientGameTestContext ctx, String[][] modes) {
        if (modes == LEGIT_AURA_HYPE && !legitSelectable()) {
            caseSkip = LEGIT_ONLY_TESTING;
            return;
        }
        resetRoutes(ctx);
        arena(ctx, false);
        giveHotbar(ctx);
        giveSlot(ctx, 4, "HYPERION");
        giveSlot(ctx, 5, "BAT_WAND");
        List<String> failures = new ArrayList<>();
        try {
            for (String[] mode : modes) {
                boolean aura = mode.length > 2;
                String label = mode[0] + "/" + mode[1] + (aura ? "/aura" : "");
                boolean legit = "legit".equals(mode[1]);
                if (legit && !legitSelectable()) {
                    println("mimic warp " + label + ": not run - " + LEGIT_ONLY_TESTING);
                    continue;
                }
                stopRoute(ctx);
                ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setLegitMode", legit));
                setArEnum(ctx, "setKillMimic", "KillMimic", mode[0]);
                ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(3));
                JsonObject n1 = ew(14, 12, 14, 5, true);
                n1.addProperty("awaitEnabled", true);
                n1.addProperty("await", "SECRET");
                n1.addProperty("amount", 1);
                writeRoute(ctx, List.of(n1));
                makeMimic(ctx, 14, 14);
                if (!arm(ctx, 14, 12, relYawTo(14, 12, 14, 5), pitchTo(14, 12, 14, 5))) {
                    failures.add(label + ": #1 did not arm");
                    continue;
                }
                ctx.waitTicks(5);
                // He turns to the chest (the camera only - a node waiting on its await lets him) and clicks it.
                if (!aura) ctx.runOnClient(mc -> {
                    Object frame = ModUnderTest.staticCall(FRAME, "current");
                    float yaw = (Float) ModUnderTest.staticCall(COORDS, "toRealYaw",
                            new Class<?>[]{frame.getClass(), float.class}, new Object[]{frame, 0f});
                    mc.player.setYRot(yaw);
                    mc.player.setXRot(35f);
                });
                ctx.waitTicks(3);
                Vec3 before = relPos(ctx);
                long m = LogTap.mark();
                startSampling(ctx);
                if (aura) {
                    ctx.runOnClient(mc -> {
                        try {
                            Class<?> a = Class.forName("com.killer560.hub.cheatutils.SecretAuraFeature");
                            for (String fn : new String[]{"firstSeenMs", "attempts", "done"}) {
                                java.lang.reflect.Field af = a.getDeclaredField(fn);
                                af.setAccessible(true);
                                Object o = af.get(null);
                                if (o instanceof java.util.Map<?, ?> mm) {
                                    mm.clear();
                                } else if (o instanceof java.util.Collection<?> cc) {
                                    cc.clear();
                                }
                            }
                        } catch (ReflectiveOperationException e) {
                            throw new RuntimeException(e);
                        }
                        Object cfg = ModUnderTest.config(CHEAT_CFG);
                        ModUnderTest.set(cfg, "setAuraChests", true);
                        ModUnderTest.set(cfg, "setSecretAuraEnabled", true);
                    });
                } else {
                    rightClick(ctx, 14, F, 14, Direction.NORTH);
                }
                double maxZ = before.z;
                Vec3 last = before;
                List<String> moves = new ArrayList<>();
                boolean landed = false;
                for (int t = 0; t < 240 && !landed; t++) {
                    Vec3 p = relPos(ctx);
                    if (p.distanceTo(last) >= 0.3) {
                        moves.add(String.format(Locale.US, "+%d (%.2f, %.2f, %.2f) -> (%.2f, %.2f, %.2f)", t, last.x,
                                last.y, last.z, p.x, p.y, p.z));
                    }
                    last = p;
                    maxZ = Math.max(maxZ, p.z);
                    // Exactly (14,5) in legit mode too: since mod path-wait a legit warp settles the camera to within
                    // 0.01 degrees before it clicks (it used to go at 1 degree, and this route has 0.7 of margin, so
                    // about one legit warp in three landed a block short; 96-ar-639 repeats it 20 times).
                    landed = Math.floor(p.x) == 14 && Math.floor(p.z) == 5 && Math.abs(p.y - F) < 0.2;
                    ctx.waitTicks(1);
                }
                ctx.waitTicks(10);
                List<Sample> s = stopSampling(ctx);
                boolean killed = logHas(m, "Mimic of the trapped chest");
                println(String.format(Locale.US, "644 %s: mimic killed %s, landed on (14,5) %s, from (%.2f, %.2f), furthest "
                        + "+z %.2f (%.2f towards the chest), now %s", label, killed, landed, before.x, before.z, maxZ,
                        maxZ - before.z, relPos(ctx)));
                for (String mv : moves) {
                    println("644 " + label + " move: " + mv);
                }
                for (Sample x : s) {
                    List<String> shown = new ArrayList<>();
                    for (String o : x.out()) {
                        if (!o.startsWith("move_player") && !o.equals("client_tick_end") && !o.equals("player_input")) {
                            shown.add(o);
                        }
                    }
                    if (!shown.isEmpty()) {
                        println("644 " + label + " tick " + x.tick() + " sneak " + x.shift() + ": " + shown);
                    }
                }
                for (String l : LogTap.since(m)) {
                    if (l.contains("Kill Mimic") || l.contains("Node #1") || l.contains("Mimic of") || l.contains("Stopped")
                            || l.contains("await secret") || l.contains("[Sim]") || l.contains("Landed") || l.contains("Sim mimic")) {
                        println("644 " + label + " log: " + strip(l));
                    }
                }
                // Every Hyperion / Spirit Sceptre use the sim's server took, with the exact pitch it arrived at: Kill
                // Mimic fires straight down only, 90.0 exactly (killer560, 2026-10-11), with either item, in either mode.
                List<String> pitches = serverUsePitches(m);
                println("644 " + label + " server use pitches " + pitches);
                if (pitches.isEmpty()) {
                    failures.add(label + ": the server took no Hyperion / Spirit Sceptre use - nothing was measured");
                }
                for (String pt : pitches) {
                    if (!"90.0".equals(pt)) {
                        failures.add(label + ": a Kill Mimic use went out at pitch " + pt + ", not 90.0");
                    }
                }
                if (!killed) {
                    failures.add(label + ": the mimic was not killed");
                }
                if (maxZ - before.z > 0.5) {
                    failures.add(String.format(Locale.US, "%s: he moved %.2f towards the mimic", label, maxZ - before.z));
                }
                if (!landed) {
                    failures.add(label + ": #1 never warped him to (14,5) - he is at " + relPos(ctx));
                }
                ctx.runOnClient(mc -> {
                    McCompat.setScreen(mc, null);
                    ModUnderTest.turnOff(CHEAT_CFG, "setSecretAuraEnabled");
                });
            }
        } finally {
            ctx.runOnClient(mc -> ModUnderTest.turnOff(CHEAT_CFG, "setSecretAuraEnabled"));
            stopRoute(ctx);
            ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setLegitMode", false));
            setArEnum(ctx, "setKillMimic", "KillMimic", "OFF");
        }
        check(failures.isEmpty(), String.join("; ", failures));
    }

    // ============================================================================================ hype-down (2026-10-11)

    /**
     * 96-ar-653-crypt-straight-down (killer560, 2026-10-11: "those nodes shouldn't matter my facing angle they should
     * always be straight down for crypt"). A lone crypt node RECORDED at a shallow look (yaw 0, pitch 30) armed while he
     * faces yaw 120, nothing to kill, so it holds use for the Crypt Attack Time: every use the sim's server takes must be
     * at pitch exactly 90.0 - Hyperion and Spirit Sceptre, legit and obvious - and in legit mode his camera yaw must not
     * have been turned (the node used to turn him to its recorded yaw).
     */
    private void caseCryptStraightDown(ClientGameTestContext ctx) {
        resetRoutes(ctx);
        arena(ctx, false);
        giveHotbar(ctx);
        giveSlot(ctx, 4, "HYPERION");
        giveSlot(ctx, 5, "BAT_WAND");
        int attack0 = ctx.computeOnClient(mc -> ModUnderTest.getInt(ModUnderTest.config(AR_CONFIG), "getCryptAttackTicks"));
        List<String> failures = new ArrayList<>();
        try {
            ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setCryptAttackTicks", 40));
            String[][] variants = {{"HYPERION", "obvious"}, {"SPIRIT_SCEPTRE", "obvious"}, {"HYPERION", "legit"},
                    {"SPIRIT_SCEPTRE", "legit"}};
            int nx = 4;
            for (String[] v : variants) {
                // Each variant on its own node spot: a route latches on the node it last ran, and re-arming there
                // raced the test's own teleports (which set the server's rotation to the armed 30).
                nx += 4;
                int nodeX = nx;
                String label = v[0] + "/" + v[1];
                boolean legit = "legit".equals(v[1]);
                if (legit && !legitSelectable()) {
                    println("653 " + label + ": not run - " + LEGIT_ONLY_TESTING);
                    continue;
                }
                stopRoute(ctx);
                ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setLegitMode", legit));
                setArEnum(ctx, "setCryptWeapon", "CryptWeapon", v[0]);
                ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(3));
                writeRoute(ctx, List.of(cryptNode(nodeX, 12, 0f, 30f, true, 0)));
                waitFor(ctx, 40, () -> !running(ctx));
                long m = LogTap.mark();
                if (!arm(ctx, nodeX, 12, 120f, 30f)) {
                    failures.add(label + ": the crypt node did not start");
                    continue;
                }
                float yaw0 = ctx.computeOnClient(mc -> mc.player.getYRot());
                startSampling(ctx);
                List<String> bodyPitch = new ArrayList<>();
                boolean over = false;
                for (int t = 0; t < 160 && !over; t++) {
                    bodyPitch.add(ctx.computeOnClient(mc -> Float.toString(mc.player.getXRot())));
                    over = logHas(m, "up, moving on") || logHas(m, "gave up aiming down");
                    ctx.waitTicks(1);
                }
                List<Sample> smp = stopSampling(ctx);
                StringBuilder pk = new StringBuilder();
                for (Sample x : smp) {
                    List<String> shown = new ArrayList<>();
                    for (String o : x.out()) {
                        if (o.startsWith("use") || o.contains("carried") || o.startsWith("player_action")) {
                            shown.add(o);
                        }
                    }
                    if (!shown.isEmpty()) {
                        pk.append(" t").append(x.tick()).append(shown);
                    }
                }
                println("653 " + label + " packets:" + pk);
                println("653 " + label + " client pitch per tick: " + bodyPitch.subList(0, Math.min(40, bodyPitch.size())));
                float yaw1 = ctx.computeOnClient(mc -> mc.player.getYRot());
                List<String> pitches = serverUsePitches(m);
                println(String.format(Locale.US, "653 %s: node done %s, server use pitches %s, camera yaw %.3f -> %.3f",
                        label, over, pitches, yaw0, yaw1));
                if (pitches.isEmpty()) {
                    failures.add(label + ": the server took no use - nothing was measured");
                }
                for (String pt : pitches) {
                    if (!"90.0".equals(pt)) {
                        failures.add(label + ": a crypt-node use went out at pitch " + pt + ", not 90.0");
                    }
                }
                if (legit && Math.abs(yaw1 - yaw0) > 0.5f) {
                    failures.add(String.format(Locale.US, "%s: the crypt node turned his yaw %.3f -> %.3f", label, yaw0, yaw1));
                }
                if (logHas(m, "gave up aiming down")) {
                    failures.add(label + ": the crypt node gave up aiming down");
                }
            }
        } finally {
            stopRoute(ctx);
            ctx.runOnClient(mc -> {
                ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setCryptAttackTicks", attack0);
                ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setLegitMode", false);
            });
        }
        check(failures.isEmpty(), String.join("; ", failures));
    }

    // ============================================================================================ run memory (2026-10-14)

    /** Set by the run-memory cases, so {@link #arm} does not forget the run between their arms. */
    private static boolean keepRunMemory;

    /** What Auto Routes remembers for the run (RouteRunMemory), forgotten as a new run would - a mod jar from before
     *  the class existed has nothing to forget. */
    private static void forgetRunMemory(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            try {
                Class.forName(ArRouteMemoryCases.MEMORY);
            } catch (ClassNotFoundException e) {
                return;
            }
            ModUnderTest.staticCall(ArRouteMemoryCases.MEMORY, "reset");
        });
    }

    /**
     * killer560, 2026-10-14. 96-ar-1014-awaitmem / -batmem: "have it remember my auto routes in a sense so if it
     * remembers I already got that secret or that bat then it will not wait for the await before doing that action".
     * 96-ar-1014-routedroom: a route that ran to its end marks its room for Auto Secret ("treat it as though I got every
     * secret in the room"). 96-ar-1014-puzzlehold: "auto clear related things need to realize when an auto puzzle is
     * working to stop making it etherwarp back to the start" - Auto Routes' side (it must not arm under one).
     */
    static final class ArRouteMemoryCases {
        static final String MEMORY = "com.killer560.hub.autoroutes.RouteRunMemory";

        private ArRouteMemoryCases() {
        }

        private static String memory(ClientGameTestContext ctx) {
            return ctx.computeOnClient(mc -> (String) ModUnderTest.staticCall(MEMORY, "describe"));
        }

        /** Ticks from {@code m} until {@code text} is logged, or -1 within {@code limit}. */
        private static int ticksUntil(ClientGameTestContext ctx, long m, String text, int limit) {
            for (int t = 0; t <= limit; t++) {
                if (logHas(m, text)) {
                    return t;
                }
                ctx.waitTicks(1);
            }
            return -1;
        }

        static void awaitMemory(ClientGameTestContext ctx) {
            resetRoutes(ctx);
            arena(ctx, false);
            giveHotbar(ctx);
            setBlocks(ctx, Map.of(new int[]{10, F, 11}, Blocks.CHEST.defaultBlockState(),
                    new int[]{14, F, 13}, floorLever(), new int[]{12, F, 22}, floorLever()));
            writeRoute(ctx, List.of(handUse(12, 12, 0f, 80f, true, 1)));
            ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(3));
            keepRunMemory = true;
            try {
                forgetRunMemory(ctx);
                // ---- 1: a first run waits for its secret; the chest click meets it ----
                long m1 = LogTap.mark();
                check(arm(ctx, 12, 12, 0f, 80f), "run 1 did not start");
                ctx.waitTicks(30);
                boolean early = logHas(m1, "secrets met");
                rightClick(ctx, 10, F, 11, Direction.UP);
                boolean met1 = waitFor(ctx, 60, () -> logHas(m1, "secrets met"));
                ctx.runOnClient(mc -> mc.player.closeContainer());
                boolean acted1 = waitFor(ctx, 20, () -> logHas(m1, "USE_ITEM acted"));
                stopRoute(ctx);
                println("awaitmem run 1: met before the click " + early + ", met by the chest " + met1 + ", acted " + acted1
                        + "; memory " + memory(ctx));
                check(!early, "run 1's await was met before any secret - nothing below would mean anything");
                check(met1 && acted1, "run 1: the chest click did not meet the await and fire the node");

                // ---- 2: the same node again, nothing new done: met at once from memory, and it acts ----
                long m2 = LogTap.mark();
                check(arm(ctx, 12, 12, 0f, 80f), "run 2 did not start");
                int fromMemory = ticksUntil(ctx, m2, "already met earlier this run", 10);
                boolean acted2 = waitFor(ctx, 10, () -> logHas(m2, "USE_ITEM acted"));
                stopRoute(ctx);
                println("awaitmem run 2 (same node, same run): met from memory after " + fromMemory + " tick(s), acted "
                        + acted2);
                check(fromMemory >= 0, "run 2 waited again for a secret he already got this run");
                check(acted2, "run 2's node did not act after its await was met from memory");

                // ---- 3: control - the run forgotten (a new run), the same node waits again ----
                forgetRunMemory(ctx);
                long m3 = LogTap.mark();
                check(arm(ctx, 12, 12, 0f, 80f), "run 3 did not start");
                ctx.waitTicks(40);
                boolean met3 = logHas(m3, "secrets met");
                stopRoute(ctx);
                println("awaitmem run 3 (new run, control): met " + met3);
                check(!met3, "after a new run the await was met with no secret - run 2's pass proves nothing");

                // ---- 4: first visit, the secret taken by hand beside the node before the route ran ----
                forgetRunMemory(ctx);
                tpRel(ctx, 12.5, 9.5, 0f, 80f);
                ctx.waitTicks(5);
                rightClick(ctx, 14, F, 13, Direction.UP);
                ctx.waitTicks(30); // past AwaitEvents.START_LOOKBACK (10): only the run memory can count it
                long m4 = LogTap.mark();
                check(arm(ctx, 12, 12, 0f, 80f), "run 4 did not start");
                int byHand = ticksUntil(ctx, m4, "remembered from earlier this run", 10);
                stopRoute(ctx);
                println("awaitmem run 4 (lever beside the node clicked by hand 30+ ticks earlier): met after " + byHand
                        + " tick(s)");
                check(byHand >= 0, "a secret he got by hand beside the node earlier this run did not meet its await");

                // ---- 5: a secret 10 blocks from the node does not count ----
                forgetRunMemory(ctx);
                tpRel(ctx, 12.5, 9.5, 0f, 80f);
                ctx.waitTicks(5);
                rightClick(ctx, 12, F, 22, Direction.UP);
                ctx.waitTicks(30);
                long m5 = LogTap.mark();
                check(arm(ctx, 12, 12, 0f, 80f), "run 5 did not start");
                ctx.waitTicks(40);
                boolean met5 = logHas(m5, "secrets met");
                stopRoute(ctx);
                println("awaitmem run 5 (lever 10 blocks away clicked earlier): met " + met5 + "; memory " + memory(ctx));
                check(memory(ctx).contains("block "), "the far lever click was never remembered - run 5 proves nothing");
                check(!met5, "a secret 10 blocks from the node met its await");
            } finally {
                keepRunMemory = false;
                forgetRunMemory(ctx);
            }
        }

        static void batMemory(ClientGameTestContext ctx) {
            resetRoutes(ctx);
            clearSecretBats(ctx);
            arena(ctx, false);
            giveHotbar(ctx);
            giveSlot(ctx, 4, "HYPERION");
            keepRunMemory = true;
            try {
                forgetRunMemory(ctx);
                ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setBatAttackTicks", 20));
                setArEnum(ctx, "setBatWeapon", "CryptWeapon", "HYPERION");
                int x = 19;
                BlockPos spot = realNow(ctx, x, F + 2, 13);
                ctx.runOnClient(mc -> mc.getSingleplayerServer().execute(() -> ModUnderTest.staticCall(
                        "com.killer560.hub.roomsim.SimSecretItems", "registerBat", new Class<?>[]{BlockPos.class},
                        new Object[]{spot})));
                JsonObject bat = node("BAT", x, 10, 0f, 30f);
                bat.addProperty("start", true);
                // As in puzzleHold: the file can load seconds after the write, after run 1's window closed (1014f).
                // Off the node first: the previous case can leave him standing on this spot's node.
                ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(3));
                tpRel(ctx, x + 0.5, 8.5, 0f, 30f);
                long w = LogTap.mark();
                writeRoute(ctx, List.of(bat));
                check(waitFor(ctx, 100, () -> logHas(w, "Loaded 1 route")), "the bat route never loaded");
                boolean appeared = waitFor(ctx, 60, () -> ctx.computeOnClient(mc -> {
                    for (var e : mc.level.entitiesForRendering()) {
                        if (e instanceof net.minecraft.world.entity.ambient.Bat b && b.getMaxHealth() == 100f
                                && !b.isDeadOrDying() && b.blockPosition().closerThan(spot, 2)) {
                            return true;
                        }
                    }
                    return false;
                }));
                check(appeared, "the secret bat never appeared");
                // ---- 1: the bat node kills its bat ----
                long m1 = LogTap.mark();
                tpRel(ctx, x + 0.5, 10.5, 0f, 30f);
                boolean killed = waitFor(ctx, 160, () -> logHas(m1, "BAT: 1 kill(s)"));
                waitFor(ctx, 40, () -> !running(ctx));
                stopRoute(ctx);
                println("batmem run 1: acted " + logHas(m1, "BAT acted") + ", killed " + killed);
                check(logHas(m1, "BAT acted") && killed, "run 1: the bat node did not kill its bat");

                // ---- 2: the same node again this run: no attack, it moves on at once ----
                long m2 = LogTap.mark();
                check(arm(ctx, x, 10, 0f, 30f), "run 2 did not start");
                int skipped = ArRouteMemoryCases.ticksUntil(ctx, m2, "already killed earlier this run", 10);
                ctx.waitTicks(10);
                boolean acted2 = logHas(m2, "BAT acted");
                stopRoute(ctx);
                println("batmem run 2 (same run): skipped from memory after " + skipped + " tick(s), acted " + acted2);
                check(skipped >= 0, "run 2's bat node did not remember its bat was already killed");
                check(!acted2, "run 2's bat node attacked for a bat killed earlier this run");

                // ---- 3: control - a new run: the node attacks (and times out, the bat is gone) ----
                forgetRunMemory(ctx);
                long m3 = LogTap.mark();
                check(arm(ctx, x, 10, 0f, 30f), "run 3 did not start");
                boolean acted3 = waitFor(ctx, 40, () -> logHas(m3, "BAT acted"));
                boolean timedOut = waitFor(ctx, 80, () -> logHas(m3, "up, moving on"));
                stopRoute(ctx);
                println("batmem run 3 (new run, control): acted " + acted3 + ", timed out " + timedOut);
                check(acted3 && timedOut, "after a new run the bat node did not attack - run 2's skip proves nothing");
            } finally {
                keepRunMemory = false;
                forgetRunMemory(ctx);
                ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setBatAttackTicks", 100));
            }
        }

        static void routedRoom(ClientGameTestContext ctx) {
            resetRoutes(ctx);
            arena(ctx, false);
            giveHotbar(ctx);
            ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(3));
            keepRunMemory = true;
            try {
                forgetRunMemory(ctx);
                java.util.function.Supplier<Boolean> routed = () -> ctx.computeOnClient(mc -> (Boolean)
                        ModUnderTest.staticCall(MEMORY, "routeCompletedThisRun", new Class<?>[]{String.class},
                                new Object[]{room}));
                // ---- a route stopped before its end does not count ----
                writeRoute(ctx, List.of(handUse(12, 12, 0f, 80f, true, 1)));
                check(arm(ctx, 12, 12, 0f, 80f), "the await route did not start");
                ctx.waitTicks(10);
                stopRoute(ctx);
                boolean afterStop = routed.get();
                // ---- one that runs to its end does ----
                writeRoute(ctx, List.of(handUse(12, 12, 0f, 80f, true, 0)));
                long m = LogTap.mark();
                check(arm(ctx, 12, 12, 0f, 80f), "the one-node route did not start");
                boolean complete = waitFor(ctx, 40, () -> logHas(m, "complete"));
                boolean afterComplete = routed.get();
                // ---- and a new run forgets it ----
                forgetRunMemory(ctx);
                boolean afterReset = routed.get();
                println("routedroom: stopped route counts " + afterStop + ", completed " + complete + " -> counts "
                        + afterComplete + ", new run -> " + afterReset);
                check(!afterStop, "a route stopped on its await marked the room as routed");
                check(complete, "the one-node route never completed");
                check(afterComplete, "a route that ran to its end did not mark " + room + " as routed this run");
                check(!afterReset, "a new run still remembered the routed room");
            } finally {
                keepRunMemory = false;
                forgetRunMemory(ctx);
            }
        }

        static void puzzleHold(ClientGameTestContext ctx) {
            resetRoutes(ctx);
            arena(ctx, false);
            giveHotbar(ctx);
            ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(3));
            try {
                // ---- control: no puzzle, the start node arms ----
                // The first run of this case read the file 1.8 s after the write and armed at tick 38 of arm()'s 40.
                long w = LogTap.mark();
                writeRoute(ctx, List.of(handUse(8, 16, 0f, 80f, true, 1)));
                waitFor(ctx, 100, () -> logHas(w, "Loaded 1 route"));
                check(arm(ctx, 8, 16, 0f, 80f), "control: the start node did not arm with no puzzle working");
                // ---- a running route stops when an auto puzzle starts working ----
                long m0 = LogTap.mark();
                PuzzleActivityFake.start(ctx);
                boolean stopped = waitFor(ctx, 10, () -> !running(ctx));
                println("puzzlehold: running route stopped by the puzzle " + stopped + " ("
                        + logHas(m0, "Auto testkit is working") + ")");
                check(stopped, "a running route kept going when an auto puzzle started working");
                // ---- under the puzzle, stepping onto the start node arms nothing ----
                tpRel(ctx, 8.5, 14.5, 0f, 80f);
                ctx.waitTicks(10);
                long m1 = LogTap.mark();
                tpRel(ctx, 8.5, 16.5, 0f, 80f);
                ctx.waitTicks(60);
                boolean armedUnder = logHas(m1, "Started \"");
                boolean gateSaid = logHas(m0, "inert: Auto testkit is working");
                println("puzzlehold: armed under the puzzle " + armedUnder + ", gate line " + gateSaid
                        + ", working " + PuzzleActivityFake.working(ctx));
                check(PuzzleActivityFake.working(ctx), "the fake puzzle never read as working - nothing here was tested");
                check(!armedUnder, "the start node armed while an auto puzzle was working");
                check(gateSaid, "Auto Routes never said it was holding for the puzzle");
                // ---- the puzzle done: the node he was left standing in stays latched (it must not fire under him the
                // moment the puzzle lets go), and stepping off and back on arms it as usual ----
                PuzzleActivityFake.stop(ctx);
                ctx.waitTicks(60);
                boolean firedUnder = logHas(m1, "Started \"");
                boolean quiet = !PuzzleActivityFake.working(ctx);
                long m2 = LogTap.mark();
                boolean rearmed = arm(ctx, 8, 16, 0f, 80f);
                println("puzzlehold: after the puzzle - working " + !quiet + ", fired under him " + firedUnder
                        + ", step off/on armed " + rearmed + " (" + logHas(m2, "Started \"") + ")");
                check(quiet, "the fake puzzle still read working 3 s after it stopped marking");
                check(!firedUnder, "the node fired under him the moment the puzzle finished");
                check(rearmed, "the start node never armed after the puzzle finished");
                stopRoute(ctx);
            } finally {
                PuzzleActivityFake.stop(ctx);
                stopRoute(ctx);
            }
        }
    }

    // ============================================================================================ main server (2026-10-15)

    /**
     * killer560's to-do, 2026-10-14. 96-ar-1015-landpickup: "On the main server for auto routes it never detects
     * secrets. I think it picks them up faster than it knows it grabs them so it just sits there waiting forever." His
     * Mod Only Test log of a real Hypixel run (17:22:43, Red Blue) has the item #4 waits for picked up while #3's warp
     * was still the active node - the take packet arrives with the landing - and #4 then sat at 0/1. The integrated
     * server picks up a tick later, so {@link dev.testkit.harness.HypixelOrder} reproduces Hypixel's order whatever the
     * jar's own sim does. 96-ar-1015-earlyskip / -mimicskip: "Left clicking for auto routes does not always skip the
     * node" - a click made while the warp onto the waiting node is in flight, and a click while Kill Mimic works.
     */
    static final class ArMainServerCases {

        private ArMainServerCases() {
        }

        private static void setOrder(ClientGameTestContext ctx, dev.testkit.harness.HypixelOrder.Mode mode) {
            ctx.runOnClient(mc -> {
                dev.testkit.harness.HypixelOrder.reset();
                dev.testkit.harness.HypixelOrder.mode = mode;
            });
        }

        /** A plain item lying on relative block (x, F, z), pickable at once - with him well away from it first (the last
         *  run leaves him standing on that very block, and vanilla would pick it up the tick it appeared). */
        private static void dropItem(ClientGameTestContext ctx, int x, int z) {
            tpRel(ctx, 6.5, 4.5, 0f, 30f);
            ctx.waitTicks(3);
            BlockPos at = realNow(ctx, x, F, z);
            serverRun(ctx, (server, sp) -> {
                var item = new net.minecraft.world.entity.item.ItemEntity(server.overworld(), at.getX() + 0.5, at.getY(),
                        at.getZ() + 0.5, new ItemStack(net.minecraft.world.item.Items.PAPER));
                item.setNoPickUpDelay();
                item.setDeltaMovement(Vec3.ZERO);
                server.overworld().addFreshEntity(item);
            });
            ctx.waitTicks(5);
        }

        /** #1 (start) warps (6,6) -> (14,6), onto #2, which waits for one secret and warps on to (14,14). */
        private static void writeChain(ClientGameTestContext ctx) {
            JsonObject b = ew(14, 6, 14, 14, false);
            b.addProperty("awaitEnabled", true);
            b.addProperty("await", "SECRET");
            b.addProperty("amount", 1);
            long w = LogTap.mark();
            writeRoute(ctx, List.of(ew(6, 6, 14, 6, true), b));
            waitFor(ctx, 100, () -> logHas(w, "Loaded 1 route"));
        }

        private static boolean armChain(ClientGameTestContext ctx) {
            return arm(ctx, 6, 6, relYawTo(6, 6, 14, 6), pitchTo(6, 6, 14, 6));
        }

        static void landPickup(ClientGameTestContext ctx) {
            resetRoutes(ctx);
            arena(ctx, false);
            giveHotbar(ctx);
            ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(3));
            writeChain(ctx);
            try {
                // ---- 1: a teammate takes the item on #2's tile as #1 lands him there: #2 must still wait ----
                dropItem(ctx, 14, 6);
                setOrder(ctx, dev.testkit.harness.HypixelOrder.Mode.OTHER);
                long m1 = LogTap.mark();
                check(armChain(ctx), "run 1 did not start");
                Vec3 landed1 = waitLanded(ctx, 14, 6, 60);
                ctx.waitTicks(40);
                boolean met1 = logHas(m1, "secrets met");
                boolean warped1 = waitLanded(ctx, 14, 14, 1) != null;
                int taken1 = dev.testkit.harness.HypixelOrder.taken;
                stopRoute(ctx);
                println("landpickup run 1 (a teammate's pickup on the landing): landed on #2 " + (landed1 != null)
                        + ", items taken " + taken1 + ", #2's await met " + met1 + ", warped on " + warped1);
                check(landed1 != null, "run 1: #1 never landed him on #2");
                check(taken1 >= 1, "run 1: no item was taken as he landed - the run proves nothing");
                check(!met1 && !warped1, "a teammate's pickup as he landed met #2's await");

                // ---- 2: HE takes it as he lands, in Hypixel's order: #2's await is met and it warps on ----
                dropItem(ctx, 14, 6);
                setOrder(ctx, dev.testkit.harness.HypixelOrder.Mode.SELF);
                long m2 = LogTap.mark();
                check(armChain(ctx), "run 2 did not start");
                boolean landed2 = waitLanded(ctx, 14, 6, 60) != null;
                boolean warped2 = waitLanded(ctx, 14, 14, 60) != null;
                int taken2 = dev.testkit.harness.HypixelOrder.taken;
                int iPick = logIndex(m2, "item picked up by you");
                int iLand = logIndex(m2, "Landed in node #2");
                boolean skipped = logHas(m2, "left click");
                stopRoute(ctx);
                println("landpickup run 2 (his pickup on the landing, Hypixel's order): landed on #2 " + landed2
                        + ", items taken " + taken2 + ", pickup line #" + iPick + " vs landing line #" + iLand
                        + ", #2 warped on " + warped2);
                for (String l : LogTap.since(m2)) {
                    if (l.contains("await") || l.contains("Landed") || l.contains("picked up")) {
                        println("  " + l);
                    }
                }
                check(landed2 && taken2 >= 1 && iPick >= 0, "run 2: the pickup on the landing never reached the mod - "
                        + "the run proves nothing");
                check(iLand >= 0 && iPick < iLand, "run 2: the pickup was handled after the landing, not in Hypixel's "
                        + "order - the run proves nothing");
                check(!skipped, "run 2: something clicked");
                check(warped2, "#2 never warped: the item he picked up as he landed on it (handled before the landing, "
                        + "as on Hypixel) was not counted for its await");
            } finally {
                ctx.runOnClient(mc -> dev.testkit.harness.HypixelOrder.reset());
                stopRoute(ctx);
            }
        }

        static void earlySkip(ClientGameTestContext ctx) {
            resetRoutes(ctx);
            arena(ctx, false);
            giveHotbar(ctx);
            ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(3));
            writeChain(ctx);
            // ---- control: no click, #2 waits on its await ----
            long m0 = LogTap.mark();
            check(armChain(ctx), "control did not start");
            boolean landed0 = waitLanded(ctx, 14, 6, 60) != null;
            ctx.waitTicks(30);
            boolean warped0 = waitLanded(ctx, 14, 14, 1) != null;
            boolean running0 = running(ctx);
            stopRoute(ctx);
            println("earlyskip control: landed on #2 " + landed0 + ", still waiting " + running0 + ", warped on " + warped0);
            check(landed0 && running0 && !warped0 && !logHas(m0, "secrets met"),
                    "control: #2 did not sit waiting on its await with no secret - the click below would prove nothing");

            // ---- his left click while #1's warp is in flight (250 ms more round trip, so the flight is ~6 ticks) ----
            ctx.runOnClient(mc -> dev.testkit.harness.Latency.install(250));
            try {
                long m = LogTap.mark();
                check(armChain(ctx), "the route did not start under latency");
                boolean acted = waitFor(ctx, 40, () -> logHas(m, "Node #1 ETHERWARP acted"));
                check(acted, "#1 never sent its warp");
                boolean before = waitLanded(ctx, 14, 6, 0) == null;
                leftClick(ctx, 2);
                boolean inFlight = before && waitLanded(ctx, 14, 6, 0) == null && !logHas(m, "Landed in node #2");
                boolean warped = waitLanded(ctx, 14, 14, 80) != null;
                println("earlyskip: clicked while #1's warp was in flight " + inFlight + ", #2 warped on " + warped
                        + ", held line " + logHas(m, "held for the next node's await") + ", skip line "
                        + logHas(m, "left click (made while the route warped you here) skipped"));
                check(inFlight, "the warp landed before the click was made - the click was not made in flight, so "
                        + "this run proves nothing");
                check(warped, "a left click made while the warp onto #2 was in flight did not skip #2's await");
            } finally {
                ctx.runOnClient(mc -> dev.testkit.harness.Latency.remove());
                ctx.waitTicks(20);
                stopRoute(ctx);
            }
        }

        static void mimicSkip(ClientGameTestContext ctx) {
            resetRoutes(ctx);
            arena(ctx, false);
            giveHotbar(ctx);
            giveSlot(ctx, 4, "BAT_WAND");
            ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(3));
            // A plain trapped chest (not the sim's mimic): Kill Mimic uses the Spirit Sceptre until its 5 s run out.
            setBlocks(ctx, Map.of(new int[]{8, F, 7}, Blocks.TRAPPED_CHEST.defaultBlockState()));
            JsonObject w = ew(6, 6, 14, 6, true);
            w.addProperty("awaitEnabled", true);
            w.addProperty("await", "SECRET");
            w.addProperty("amount", 1);
            long wl = LogTap.mark();
            writeRoute(ctx, List.of(w));
            waitFor(ctx, 100, () -> logHas(wl, "Loaded 1 route"));
            setArEnum(ctx, "setKillMimic", "KillMimic", "SPIRIT_SCEPTRE");
            try {
                check(arm(ctx, 6, 6, relYawTo(6, 6, 14, 6), 80f), "the await route did not start");
                ctx.waitTicks(10);
                long m = LogTap.mark();
                rightClick(ctx, 8, F, 7, Direction.UP);
                ctx.runOnClient(mc -> mc.player.closeContainer());
                boolean using = waitFor(ctx, 40, () -> logHas(m, "Spirit Sceptre used straight down"));
                check(logHas(m, "watching for its mimic") && using,
                        "Kill Mimic never started using the Spirit Sceptre - the click below would prove nothing");
                check(!logHas(m, "secrets met") && waitLanded(ctx, 14, 6, 0) == null,
                        "the node fired before any click - the trapped chest met its await");
                long m2 = LogTap.mark();
                leftClick(ctx, 2);
                boolean warped = waitLanded(ctx, 14, 6, 30) != null;
                boolean mimicStillOn = !logHas(m2, "Kill Mimic done");
                println("mimicskip: Kill Mimic using " + using + "; after his left click the node warped " + warped
                        + " (skip line " + logHas(m2, "left click skipped the await") + "), Kill Mimic still working "
                        + mimicStillOn);
                check(warped, "a left click while Kill Mimic worked did not skip the waiting node within 30 ticks");
            } finally {
                setArEnum(ctx, "setKillMimic", "KillMimic", "OFF");
                stopRoute(ctx);
                // Kill Mimic outlives the route: let it finish (5 s at most) before the next case.
                long md = LogTap.mark();
                waitFor(ctx, 120, () -> logHas(md, "Kill Mimic done") || !ctx.computeOnClient(mc -> (Boolean)
                        ModUnderTest.staticCall("com.killer560.hub.autoroutes.MimicKiller", "isBusy")));
                giveSlot(ctx, 4, null);
            }
        }

        /** One run of {@link #warpAfterClick}: arm #1, then {@code act}; ticks from the act to his landing on (14,6). */
        private record ClickRun(boolean started, boolean acted, int landTick, double travelled, int dropped,
                                boolean turnedFirst, int actedAfterFiring, String actedLine, String landedLine) {
        }

        private static ClickRun clickRun(ClientGameTestContext ctx, int slot, Runnable act) {
            ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(slot));
            ctx.waitTicks(3);
            long a = LogTap.mark();
            // Pitch 80: the look the server hears while #1 waits meets the floor, as Secret Aura's turn to a chest meets
            // the chest.
            boolean started = arm(ctx, 6, 6, relYawTo(6, 6, 14, 6), 80f);
            ctx.waitTicks(10);
            boolean waiting = !logHas(a, "secrets met") && waitLanded(ctx, 14, 6, 0) == null;
            Vec3 from = relPos(ctx);
            int dropsBefore = dev.testkit.harness.HypixelUseAfterClick.dropped;
            long m = LogTap.mark();
            act.run();
            int landTick = -1;
            Vec3 at = null;
            for (int t = 0; t <= 30; t++) {
                // One look per tick: waitLanded(.., 0) looks now without waiting.
                at = waitLanded(ctx, 14, 6, 0);
                if (at != null) {
                    landTick = t;
                    break;
                }
                ctx.waitTicks(1);
            }
            String acted = null;
            String landed = null;
            for (String l : LogTap.since(m)) {
                if (acted == null && l.contains("Node #1 ETHERWARP acted")) {
                    acted = l;
                }
                if (landed == null && l.contains("Etherwarp: landed")) {
                    landed = l;
                }
            }
            int after = -1;
            if (acted != null) {
                java.util.regex.Matcher am = java.util.regex.Pattern.compile("acted (\\d+) tick\\(s\\) after firing").matcher(acted);
                if (am.find()) {
                    after = Integer.parseInt(am.group(1));
                }
            }
            ClickRun r = new ClickRun(started && waiting, acted != null, landTick,
                    at == null ? relPos(ctx).distanceTo(from) : at.distanceTo(from),
                    dev.testkit.harness.HypixelUseAfterClick.dropped - dropsBefore,
                    logHas(m, "turning to the use's look first"), after, acted, landed);
            stopRoute(ctx);
            return r;
        }

        /** A right click on (8, F, 7) - the lever - through the game mode, as Secret Aura or his own click sends it. */
        private static void clickLever(Minecraft mc) {
            BlockPos pos = real(ModUnderTest.staticCall(FRAME, "current"), 8, F, 7);
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(new Vec3(pos.getX() + 0.5, pos.getY() + 0.1, pos.getZ() + 0.5), Direction.UP,
                            pos, false));
        }

        /** Ticks (0 = the first look) until he stands on relative (x, z), at most {@code max}; -1 when never. */
        private static int ticksToLand(ClientGameTestContext ctx, int x, int z, int max) {
            for (int t = 0; t <= max; t++) {
                if (waitLanded(ctx, x, z, 0) != null) {
                    return t;
                }
                ctx.waitTicks(1);
            }
            return -1;
        }

        /**
         * killer560, 2026-10-15: "I need it etherwarping out as fast as possible. Preferably the next tick ... when I
         * manually do it I can click a chest and warp the next tick." His real run (Mod Only Test, 2026-10-14 17:22-17:29):
         * six warps a tick after a chest or lever click with the AOTV held did nothing. The cause is Hypixel's 1.8 server
         * pairing a bare use with the unpaired block click when its ray along the look it last HEARD meets a block
         * ({@link dev.testkit.harness.HypixelUseAfterClick}); the old jar sent the use in the tick it turned to the warp.
         * <p>
         * Controls: a left-click skip lands; a lever clicked with an empty hand lands; a raw use a tick after a click,
         * the body not turned (the look the server heard meets the floor), is ignored (the rule is live here). Human: the
         * lever clicked by hand looking at it, the next tick he looks at the warp's block and sneaks, the tick after he
         * right-clicks - it must land. The case: the lever clicked with the AOTV held while #1 waits (the heard look
         * meets the floor) - the warp must go the NEXT tick (acted 1 tick after firing) and land with nothing ignored.
         * Item: #1 met by an item he picks up (no click) - the warp goes and lands with nothing ignored and no turn.
         */
        static void warpAfterClick(ClientGameTestContext ctx) {
            resetRoutes(ctx);
            arena(ctx, false);
            giveHotbar(ctx);
            setBlocks(ctx, Map.of(new int[]{8, F, 7}, Blocks.LEVER.defaultBlockState().setValue(
                    net.minecraft.world.level.block.LeverBlock.FACE,
                    net.minecraft.world.level.block.state.properties.AttachFace.FLOOR)));
            JsonObject w = ew(6, 6, 14, 6, true);
            w.addProperty("awaitEnabled", true);
            w.addProperty("await", "SECRET");
            w.addProperty("amount", 1);
            long wl = LogTap.mark();
            writeRoute(ctx, List.of(w));
            waitFor(ctx, 100, () -> logHas(wl, "Loaded 1 route"));
            ctx.runOnClient(mc -> {
                dev.testkit.harness.HypixelUseAfterClick.reset();
                dev.testkit.harness.HypixelUseAfterClick.enabled = true;
            });
            try {
                ClickRun skip = clickRun(ctx, 0, () -> leftClick(ctx, 2));
                ClickRun swap = clickRun(ctx, 3, () -> ctx.runOnClient(ArMainServerCases::clickLever));
                ClickRun same = clickRun(ctx, 0, () -> ctx.runOnClient(ArMainServerCases::clickLever));
                ClickRun item = clickRun(ctx, 0, () -> serverRun(ctx, (server, sp) -> {
                    var drop = new net.minecraft.world.entity.item.ItemEntity(server.overworld(), sp.getX(), sp.getY(),
                            sp.getZ(), new ItemStack(net.minecraft.world.item.Items.PAPER));
                    drop.setNoPickUpDelay();
                    drop.setDeltaMovement(Vec3.ZERO);
                    server.overworld().addFreshEntity(drop);
                }));
                int clicks = dev.testkit.harness.HypixelUseAfterClick.clicks;
                for (var e : List.of(Map.entry("left-click skip, AOTV held", skip),
                        Map.entry("lever clicked with an empty hand", swap), Map.entry("lever clicked with the AOTV held", same),
                        Map.entry("item picked up, no click", item))) {
                    ClickRun r = e.getValue();
                    println(String.format(Locale.US, "warpafterclick %s: started+waiting %s, warp sent %s (%d tick(s) after "
                                    + "firing), landed %s tick(s) after the act, travelled %.2f, uses ignored %d, turned-first "
                                    + "line %s", e.getKey(), r.started(), r.acted(), r.actedAfterFiring(),
                            r.landTick() < 0 ? "never (30)" : String.valueOf(r.landTick()), r.travelled(), r.dropped(),
                            r.turnedFirst()));
                    println("  " + r.actedLine());
                    println("  " + r.landedLine());
                }
                // ---- raw control and the human stream: no route (off its ring), from (6, 4) to (14, 4) ----
                stopRoute(ctx);
                RawHuman rh = rawAndHuman(ctx);
                println("warpafterclick: block clicks the hook saw " + clicks + "; last ignored: "
                        + dev.testkit.harness.HypixelUseAfterClick.lastDrop);
                check(skip.started() && swap.started() && same.started() && item.started(),
                        "a run did not start waiting on #1's await");
                check(clicks >= 2, "the hook saw " + clicks + " block click(s) - the lever runs prove nothing");
                check(rh.rawDropped() >= 1 && rh.rawMoved() < 1.0, "control: a raw use a tick after a block click, under a "
                        + "heard look that meets the floor, was not ignored (ignored " + rh.rawDropped() + ", moved "
                        + String.format(Locale.US, "%.2f", rh.rawMoved()) + ") - the rule is not live, so nothing here "
                        + "proves anything");
                check(skip.landTick() >= 0 && skip.dropped() == 0, "control: a left-click skip did not land (or the hook "
                        + "ignored its use)");
                check(swap.landTick() >= 0 && swap.dropped() == 0, "control: a lever clicked with an empty hand did not land");
                check(rh.humanLand() >= 0 && rh.humanDropped() == 0, "human: click the lever, look at the warp's block and "
                        + "sneak, right-click - did not land (ignored " + rh.humanDropped() + ")");
                check(same.acted(), "#1 never sent its warp after the lever click");
                check(same.dropped() == 0 && same.landTick() >= 0 && same.travelled() > 7.5,
                        "the warp after a lever click made with the AOTV held was ignored or did not land (landed "
                                + same.landTick() + ", travelled " + String.format(Locale.US, "%.2f", same.travelled())
                                + ", uses ignored " + same.dropped() + ")");
                check(same.actedAfterFiring() <= 1, "the warp after the lever click went " + same.actedAfterFiring()
                        + " tick(s) after firing - it must go the next tick, as his own does");
                check(item.acted() && item.dropped() == 0 && item.landTick() >= 0 && !item.turnedFirst(),
                        "the warp after an item pickup did not go and land at once (landed " + item.landTick()
                                + ", ignored " + item.dropped() + ", turned first " + item.turnedFirst() + ")");
            } finally {
                ctx.runOnClient(mc -> dev.testkit.harness.HypixelUseAfterClick.reset());
                ctx.getInput().releaseKey(options -> options.keyShift);
                stopRoute(ctx);
            }
        }

        private record RawHuman(int rawDropped, double rawMoved, int humanDropped, int humanLand) {
        }

        /** The raw control and the human stream, from (6, 4) - outside #1's ring - to (14, 4). */
        private static RawHuman rawAndHuman(ClientGameTestContext ctx) {
            float yawTo = relYawTo(6, 4, 14, 4);
            float pitchTo = pitchTo(6, 4, 14, 4);
            // Raw: sneaking, the heard look on the floor, a block click 7 blocks east (out of the server's reach, so
            // nothing answers it, but stored), and the next tick a use packet aimed at the warp with the body NOT turned.
            ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
            tpRel(ctx, 6.5, 4.5, yawTo, 80f);
            ctx.getInput().holdKey(options -> options.keyShift);
            ctx.waitTicks(5);
            int d0 = dev.testkit.harness.HypixelUseAfterClick.dropped;
            Vec3 from = relPos(ctx);
            ctx.runOnClient(mc -> {
                BlockPos far = mc.player.blockPosition().offset(7, -1, 0);
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(far), Direction.UP, far, false));
            });
            ctx.waitTicks(1);
            ctx.runOnClient(mc -> {
                // The use packet carries the warp's look; the body (what the movement packets report) stays on the floor -
                // the old jar's use, sent in the tick it turned.
                float yaw = (Float) ModUnderTest.staticCall(COORDS, "toRealYaw", new Class<?>[]{
                        ModUnderTest.staticCall(FRAME, "current").getClass(), float.class},
                        new Object[]{ModUnderTest.staticCall(FRAME, "current"), yawTo});
                float keepYaw = mc.player.getYRot();
                float keepPitch = mc.player.getXRot();
                mc.player.setYRot(keepYaw + net.minecraft.util.Mth.wrapDegrees(yaw - keepYaw));
                mc.player.setXRot(pitchTo);
                mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                mc.player.setYRot(keepYaw);
                mc.player.setXRot(keepPitch);
            });
            ctx.waitTicks(20);
            int rawDropped = dev.testkit.harness.HypixelUseAfterClick.dropped - d0;
            double rawMoved = relPos(ctx).distanceTo(from);
            ctx.getInput().releaseKey(options -> options.keyShift);
            // Human: not sneaking, looking at the lever, click it; next tick look at the warp's block and sneak; the tick
            // after, a real right click (vanilla's startUseItem).
            tpRel(ctx, 6.5, 4.5, yawTo, 80f);
            ctx.waitTicks(5);
            int d1 = dev.testkit.harness.HypixelUseAfterClick.dropped;
            ctx.runOnClient(mc -> {
                BlockPos lever = real(ModUnderTest.staticCall(FRAME, "current"), 8, F, 7);
                Vec3 eye = mc.player.getEyePosition();
                Vec3 c = new Vec3(lever.getX() + 0.5, lever.getY() + 0.1, lever.getZ() + 0.5);
                double dx = c.x - eye.x;
                double dy = c.y - eye.y;
                double dz = c.z - eye.z;
                mc.player.setYRot((float) -Math.toDegrees(Math.atan2(dx, dz)));
                mc.player.setXRot((float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))));
            });
            ctx.waitTicks(2);
            ctx.runOnClient(ArMainServerCases::clickLever);
            ctx.waitTicks(1);
            ctx.runOnClient(mc -> {
                float yaw = (Float) ModUnderTest.staticCall(COORDS, "toRealYaw", new Class<?>[]{
                        ModUnderTest.staticCall(FRAME, "current").getClass(), float.class},
                        new Object[]{ModUnderTest.staticCall(FRAME, "current"), yawTo});
                mc.player.setYRot(mc.player.getYRot() + net.minecraft.util.Mth.wrapDegrees(yaw - mc.player.getYRot()));
                mc.player.setXRot(pitchTo);
            });
            ctx.getInput().holdKey(options -> options.keyShift);
            ctx.waitTicks(1);
            ctx.getInput().pressKey(options -> options.keyUse);
            int humanLand = ticksToLand(ctx, 14, 4, 20);
            int humanDropped = dev.testkit.harness.HypixelUseAfterClick.dropped - d1;
            ctx.getInput().releaseKey(options -> options.keyShift);
            println(String.format(Locale.US, "warpafterclick raw use a tick after a click, body not turned: ignored %d, moved "
                    + "%.2f; human (click, look + sneak, right click): landed %s tick(s) after the click, ignored %d", rawDropped,
                    rawMoved, humanLand < 0 ? "never (20)" : String.valueOf(humanLand), humanDropped));
            return new RawHuman(rawDropped, rawMoved, humanDropped, humanLand);
        }

        /** One run of {@link #puzzleWarpAfterClick}. */
        private record PuzzleRun(boolean sneaking, int sentTick, int landTick, double travelled, int dropped, int clicks,
                                 boolean held) {
        }

        /** How the click before the use is made. */
        private enum PuzzleClick { NONE, EMPTY_HAND_THEN_SWAP, SAME_ITEM, SAME_ITEM_RAW_USE }

        private static PuzzleRun puzzleRun(ClientGameTestContext ctx, PuzzleClick kind) {
            ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
            // Looking at the floor (pitch 80): the look the server hears meets a block, as Secret Aura's turn to a chest does.
            tpRel(ctx, 6.5, 6.5, relYawTo(6, 6, 14, 6), 80f);
            boolean sneaking = waitFor(ctx, 30, () -> ctx.computeOnClient(mc -> mc.player.getLastSentInput().shift()));
            ctx.waitTicks(5);
            ctx.runOnClient(mc -> {
                dev.testkit.harness.HypixelUseAfterClick.reset();
                dev.testkit.harness.HypixelUseAfterClick.enabled = true;
            });
            Vec3 from = relPos(ctx);
            long m = LogTap.mark();
            // The floor 7 blocks east: past the server's reach for a block click, so no block or ability answers it (a lever
            // would not do - sneaking, as the warp needs, a click on it is an AOTV use-on-block and warps him itself), but
            // inside the 8 blocks a 1.8 server stores a click from; its handler is entered first, where the hook stores it,
            // and the click goes through the game mode, where the mod's own packet tap takes it (as ClickPrimer's does).
            Runnable click = () -> {
                Minecraft mc = Minecraft.getInstance();
                BlockPos far = mc.player.blockPosition().offset(7, -1, 0);
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(far), Direction.UP, far, false));
            };
            int sent = -1;
            for (int t = 0; t <= 30 && sent < 0; t++) {
                final int tick = t;
                // The use is what an Auto Puzzles auto calls every tick until it answers true; a false is "nothing sent".
                boolean sentNow = ctx.computeOnClient(mc -> {
                    if (tick == 0) {
                        switch (kind) {
                            case SAME_ITEM, SAME_ITEM_RAW_USE -> click.run();
                            case EMPTY_HAND_THEN_SWAP -> {
                                mc.player.getInventory().setSelectedSlot(3);
                                click.run();
                                mc.player.getInventory().setSelectedSlot(0);
                            }
                            default -> {
                            }
                        }
                    }
                    float yaw = (Float) ModUnderTest.staticCall(COORDS, "toRealYaw", new Class<?>[]{
                            ModUnderTest.staticCall(FRAME, "current").getClass(), float.class},
                            new Object[]{ModUnderTest.staticCall(FRAME, "current"), relYawTo(6, 6, 14, 6)});
                    float pitch = pitchTo(6, 6, 14, 6);
                    if (kind == PuzzleClick.SAME_ITEM_RAW_USE) {
                        if (tick == 0) {
                            return false; // the raw use goes a tick after the click, as the old jar's did
                        }
                        // The use packet carries the warp's look; the body stays on the floor (the old jar's use).
                        float keepYaw = mc.player.getYRot();
                        float keepPitch = mc.player.getXRot();
                        mc.player.setYRot(keepYaw + net.minecraft.util.Mth.wrapDegrees(yaw - keepYaw));
                        mc.player.setXRot(pitch);
                        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                        mc.player.setYRot(keepYaw);
                        mc.player.setXRot(keepPitch);
                        return true;
                    }
                    return (Boolean) ModUnderTest.staticCall("com.killer560.hub.autopuzzles.AutoPuzzleUtil", "useItemRotated",
                            new Class<?>[]{Minecraft.class, net.minecraft.client.player.LocalPlayer.class, float.class, float.class},
                            new Object[]{mc, mc.player, yaw, pitch});
                });
                if (sentNow) {
                    sent = t;
                } else {
                    ctx.waitTicks(1);
                }
            }
            int land = -1;
            for (int t = 0; t <= 20; t++) {
                if (waitLanded(ctx, 14, 6, 0) != null) {
                    land = t;
                    break;
                }
                ctx.waitTicks(1);
            }
            double travelled = relPos(ctx).distanceTo(from);
            PuzzleRun r = new PuzzleRun(sneaking, sent, land, travelled, dev.testkit.harness.HypixelUseAfterClick.dropped,
                    dev.testkit.harness.HypixelUseAfterClick.clicks, logHas(m, "turning to the use's look first"));
            ctx.runOnClient(mc -> dev.testkit.harness.HypixelUseAfterClick.reset());
            return r;
        }

        /**
         * Every Auto Puzzles auto that etherwarps or shoots (Ice Fill, Tic Tac Toe, Boulder, Water Board, Teleport Maze, the
         * blaze / beams / ice path shots, Auto Debuff) sends its item use through {@code AutoPuzzleUtil.useItemRotated}, whose
         * callers treat false as "nothing was sent" and ask again next tick. Hypixel's 1.8 server takes a bare use as an
         * unpaired block click's second half when its ray along the look it last HEARD meets a block
         * ({@link dev.testkit.harness.HypixelUseAfterClick}). Called as an auto calls it, sneaking with the AOTV, the heard
         * look on the floor, aimed at relative (14, 6): control 1, no click - sent at once, lands. Control 2, a block clicked
         * with an empty hand and the AOTV swapped in - lands, nothing ignored. The case: a block clicked with the AOTV held -
         * the body turns to the shot's look, the use goes the next tick and lands, nothing ignored. Control 3: the same click
         * and a RAW use a tick later with the body not turned - the server ignores it and he does not move, so the rule bites
         * in this arena and the case's pass is not the hook being off.
         */
        static void puzzleWarpAfterClick(ClientGameTestContext ctx) {
            resetRoutes(ctx);
            arena(ctx, false);
            giveHotbar(ctx);
            ctx.getInput().holdKey(options -> options.keyShift);
            try {
                PuzzleRun none = puzzleRun(ctx, PuzzleClick.NONE);
                PuzzleRun swap = puzzleRun(ctx, PuzzleClick.EMPTY_HAND_THEN_SWAP);
                PuzzleRun same = puzzleRun(ctx, PuzzleClick.SAME_ITEM);
                PuzzleRun raw = puzzleRun(ctx, PuzzleClick.SAME_ITEM_RAW_USE);
                for (var e : List.of(Map.entry("no click", none), Map.entry("block click, empty hand, AOTV swapped in", swap),
                        Map.entry("block click with the AOTV held", same), Map.entry("same click, RAW use (control)", raw))) {
                    PuzzleRun r = e.getValue();
                    println(String.format(Locale.US, "puzzleclick %s: sneaking %s, use sent at tick %d, landed %s tick(s) after, "
                                    + "travelled %.2f, block clicks seen %d, uses ignored %d, turned-first line %s", e.getKey(), r.sneaking(),
                            r.sentTick(), r.landTick() < 0 ? "never (20)" : String.valueOf(r.landTick()), r.travelled(),
                            r.clicks(), r.dropped(), r.held()));
                }
                check(none.sneaking() && swap.sneaking() && same.sneaking() && raw.sneaking(),
                        "shift never reached the server - an etherwarp would not fire and nothing here proves anything");
                check(none.sentTick() == 0 && none.landTick() >= 0 && none.travelled() > 7.5 && none.dropped() == 0,
                        "control: with no click the use was not sent at once and landed (sent " + none.sentTick() + ")");
                check(swap.sentTick() <= 1 && swap.landTick() >= 0 && swap.clicks() >= 1 && swap.dropped() == 0,
                        "control: after a block click with an empty hand the use was late or ignored (sent " + swap.sentTick()
                                + ", ignored " + swap.dropped() + ")");
                check(raw.clicks() >= 1 && raw.dropped() >= 1 && raw.travelled() < 1.0,
                        "control: a raw use right after the same-item click was not ignored (clicks " + raw.clicks() + ", ignored "
                                + raw.dropped() + ", travelled " + String.format(Locale.US, "%.2f", raw.travelled())
                                + ") - the rule is not live, so the case proves nothing");
                check(same.clicks() >= 1, "the block click never reached the server - the case proves nothing");
                check(same.sentTick() == 1 && same.held(),
                        "the use went out at tick " + same.sentTick() + " after a same-item block click (turned-first line "
                                + same.held() + ") - it must turn to its look first and go the next tick");
                check(same.dropped() == 0 && same.landTick() >= 0 && same.travelled() > 7.5,
                        "the use was ignored or did not land (ignored " + same.dropped() + ", landed " + same.landTick()
                                + ", travelled " + String.format(Locale.US, "%.2f", same.travelled()) + ")");
            } finally {
                ctx.getInput().releaseKey(options -> options.keyShift);
                ctx.runOnClient(mc -> dev.testkit.harness.HypixelUseAfterClick.reset());
            }
        }
    }

    /**
     * 96-ar-1015-serverlag (mod server-lag-1015). killer560, 2026-10-15: "... Same with auto puzzles and auto routes and
     * things it needs to always account for server lag." A start etherwarp (6,6) -> (14,6), run twice:
     * <ol>
     *   <li><b>control</b>, no lag: it lands, and the mod's "landed ... N tick(s) after the use" is printed to compare with
     *   a main jar (the normal case's timing must not change);</li>
     *   <li><b>lag</b>: from the moment the route's warp is sent, nothing from the server reaches the client for
     *   {@link #STALL_MS} ({@link dev.testkit.harness.LagSpike}: no landing, no per-tick ping, then all of it at once),
     *   longer than LANDING_TIMEOUT (60 ticks). It must land once the server's packets come through,
     *   with no "no landing after" line and no correction ("did not land where it was recorded"), and the mod's
     *   util.ServerLag must have measured the stall.</li>
     * </ol>
     */
    static final class ArServerLagCases {

        private static final long STALL_MS = 5000L;

        private ArServerLagCases() {
        }

        static void serverLag(ClientGameTestContext ctx) {
            resetRoutes(ctx);
            arena(ctx, false);
            giveHotbar(ctx);
            ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(3));
            long w = LogTap.mark();
            writeRoute(ctx, List.of(ew(6, 6, 14, 6, true)));
            waitFor(ctx, 100, () -> logHas(w, "Loaded 1 route"));
            float yaw = relYawTo(6, 6, 14, 6);
            float pitch = pitchTo(6, 6, 14, 6);
            try {
                // ---- control: no lag ----
                long m0 = LogTap.mark();
                check(arm(ctx, 6, 6, yaw, pitch), "control: the route did not start");
                boolean landed0 = waitLanded(ctx, 14, 6, 80) != null;
                ctx.waitTicks(5);
                String line0 = lineWith(m0, "Etherwarp: landed at");
                stopRoute(ctx);
                println("serverlag control: landed " + landed0 + " - " + line0);
                check(landed0 && line0 != null, "control: the warp did not land with no lag - the lag run would prove nothing");
                check(lineWith(m0, "no landing after") == null, "control: a no-lag warp reported no landing");

                // ---- lag: from the route's warp, nothing from the server arrives for STALL_MS (dev.testkit.harness.LagSpike)
                int stalls0 = SimServerLagTests.lagStalls(ctx);
                ctx.runOnClient(mc -> dev.testkit.harness.LagSpike.armOnNextUse(STALL_MS));
                long m = LogTap.mark();
                check(arm(ctx, 6, 6, yaw, pitch), "lag: the route did not start");
                int ticks = 0;
                Vec3 landed = null;
                for (; ticks < (int) (STALL_MS / 50) + 120 && landed == null; ticks++) {
                    landed = waitLanded(ctx, 14, 6, 0);
                    if (landed == null) {
                        ctx.waitTicks(1);
                    }
                }
                ctx.waitTicks(5);
                ctx.runOnClient(mc -> dev.testkit.harness.LagSpike.disarm());
                long spike = dev.testkit.harness.LagSpike.releasedAt() - dev.testkit.harness.LagSpike.startedAt();
                String acted = lineWith(m, "Node #1 ETHERWARP acted");
                String line = lineWith(m, "Etherwarp: landed at");
                String noLanding = lineWith(m, "no landing after");
                String correction = lineWith(m, "did not land where");
                long longest = SimServerLagTests.lagLongest(ctx);
                stopRoute(ctx);
                println("serverlag lag: spike " + spike + " ms holding " + dev.testkit.harness.LagSpike.held()
                        + " packet(s); acted " + (acted != null) + "; landed " + (landed != null) + " after " + ticks
                        + " tick(s) - " + line + "; ServerLag stalls +" + (SimServerLagTests.lagStalls(ctx) - stalls0)
                        + ", longest " + longest + " ms; no-landing line " + noLanding + "; correction " + correction);
                check(acted != null, "the route never sent its warp - the run proves nothing");
                check(dev.testkit.harness.LagSpike.startedAt() != 0L && spike >= STALL_MS - 100,
                        "no lag spike ran on the warp (" + spike + " ms) - the run proves nothing");
                check(longest >= STALL_MS - 1500, "util.ServerLag did not measure the " + STALL_MS + " ms stall (longest "
                        + longest + " ms)");
                check(noLanding == null, "a server stall was read as a missed landing: " + noLanding);
                check(correction == null, "a server stall was reported as a correction: " + correction);
                check(landed != null, "the warp never landed once the server's packets came through");
                check(ticks > 60, "it landed after only " + ticks + " tick(s), inside LANDING_TIMEOUT - the run proves nothing");
            } finally {
                ctx.runOnClient(mc -> dev.testkit.harness.LagSpike.disarm());
                stopRoute(ctx);
            }
        }

        private static String lineWith(long mark, String text) {
            for (String l : LogTap.since(mark)) {
                if (l.contains(text)) {
                    return l;
                }
            }
            return null;
        }
    }

    /**
     * {@code 96-ar-1015-pathland} (sim-corrections-1015). killer560, 2026-10-15: "In sim something is off with etherwarping
     * and it will end up in a random spot while doing auto routes. It may be from path nodes specifically." His Mod Only
     * Test log (15:54-15:56) had two shapes, both a tick or two after a chest click made with the AOTV in hand:
     * <ol>
     *   <li><b>after a landing</b>: the path's next warp went a tick after the last one landed, and util/RightClick still
     *   traced the server's ray from where he stood BEFORE that warp (vanilla's answer to a teleport goes past its send
     *   tap), so the warp went bare while the click was unpaired; the server, tracing from the landing, met a block and
     *   took it as the click's second half - "no landing after 62 ticks" (Flags #13, #15). Here: warp 1 lands 2.5 blocks
     *   short of the arena's wall, and its look carries on into the wall.</li>
     *   <li><b>a warp within reach</b>: the warp's own look met its block within 4.5, so the block half went first; the
     *   sim's server etherwarped on that click, then traced the use half from the landing, met nothing, and warped AGAIN
     *   past it - 11.2 blocks off (Flags #18). Here: a one-warp pair onto a step three blocks away.</li>
     * </ol>
     * Every sampled position from the await to the arrival must be the start or one of the planned landings, in order,
     * and he must end on the arrival node.
     */
    static final class ArPathLandCases {

        private ArPathLandCases() {
        }

        static void pathLand(ClientGameTestContext ctx) {
            List<String> bad = new ArrayList<>();
            resetRoutes(ctx);
            arena(ctx, true);
            giveHotbar(ctx);
            // Chests of its own: the run memory counts each chest once, and 96-ar-path's are at (4, 22) and (4, 18).
            setBlocks(ctx, Map.of(new int[]{8, F, 23}, Blocks.CHEST.defaultBlockState()));
            String one = flyPair(ctx, "after a landing", 6, 20, new int[][]{{12, F - 1, 20}, {12, F - 1, 12}},
                    new int[]{8, F, 23});
            if (one != null) {
                bad.add(one);
            }
            resetRoutes(ctx);
            arena(ctx, false);
            giveHotbar(ctx);
            // A pad ten blocks past the step, where the warp's look carries on to from the step: a second warp off the
            // use half lands on it (with nothing there the sim refuses it against the arena wall and the bug hides).
            setBlocks(ctx, Map.of(new int[]{9, F, 8}, Blocks.STONE.defaultBlockState(),
                    new int[]{8, F, 6}, Blocks.CHEST.defaultBlockState(),
                    new int[]{19, F + 1, 8}, Blocks.STONE.defaultBlockState()));
            String two = flyPair(ctx, "a warp within reach", 6, 8, new int[][]{{9, F, 8}}, new int[]{8, F, 6});
            if (two != null) {
                bad.add(two);
            }
            resetRoutes(ctx);
            // Both routes ran to their end, which the run memory keeps (their awaits, the room as routed): the cases after
            // this one must start from a fresh run.
            forgetRunMemory(ctx);
            String sync = teleportSync(ctx);
            if (sync != null) {
                bad.add(sync);
            }
            check(bad.isEmpty(), String.join(" | ", bad));
        }

        private static volatile boolean syncArmed;
        private static volatile Vec3 syncLastEnd;
        private static volatile double[] syncSeen;
        private static boolean syncHooked;

        /**
         * The mechanism under shape 1, measured directly (the end-to-end flight above only hits it when the next warp
         * goes out in the very tick the landing is applied, which the gametest's lockstep rarely gives): on the first
         * client tick after a server teleport is applied - before this tick's own movement packet - util/RightClick's idea
         * of where the server traces from must already be the landing, not the spot he was teleported from.
         */
        private static String teleportSync(ClientGameTestContext ctx) {
            ctx.runOnClient(mc -> {
                if (!syncHooked) {
                    syncHooked = true;
                    ClientTickEvents.END_CLIENT_TICK.register(c -> {
                        if (c.player != null) {
                            syncLastEnd = c.player.position();
                        }
                    });
                    ClientTickEvents.START_CLIENT_TICK.register(c -> {
                        Vec3 last = syncLastEnd;
                        if (!syncArmed || c.player == null || last == null || c.player.position().distanceTo(last) < 2.0) {
                            return;
                        }
                        syncArmed = false;
                        try {
                            Class<?> rc = Class.forName("com.killer560.hub.util.RightClick");
                            double[] v = new double[3];
                            String[] names = {"sentX", "sentY", "sentZ"};
                            for (int i = 0; i < 3; i++) {
                                var f = rc.getDeclaredField(names[i]);
                                f.setAccessible(true);
                                v[i] = f.getDouble(null);
                            }
                            syncSeen = v;
                        } catch (ReflectiveOperationException e) {
                            syncSeen = new double[]{Double.NaN, Double.NaN, Double.NaN};
                        }
                    });
                }
            });
            tpRel(ctx, 6.5, 8.5, 90f, 10f);
            ctx.waitTicks(10);
            Vec3 from = ctx.computeOnClient(mc -> mc.player.position());
            Vec3 to = ctx.computeOnClient(mc -> toReal(20.5, F, 8.5));
            syncSeen = null;
            syncArmed = true;
            serverRun(ctx, (server, sp) -> sp.teleportTo(to.x, to.y, to.z));
            waitFor(ctx, 40, () -> syncSeen != null);
            syncArmed = false;
            double[] v = syncSeen;
            if (v == null) {
                return "teleport sync: the teleport was never seen on a tick start - nothing measured";
            }
            Vec3 seen = new Vec3(v[0], v[1], v[2]);
            println(String.format(Locale.US, "pathland teleport sync: teleported from %.2f, %.2f, %.2f to %.2f, %.2f, %.2f; "
                    + "first tick after, RightClick traces from %.2f, %.2f, %.2f", from.x, from.y, from.z, to.x, to.y, to.z,
                    seen.x, seen.y, seen.z));
            return seen.distanceTo(to) < 0.1 ? null : String.format(Locale.US, "teleport sync: on the tick after a server "
                    + "teleport RightClick still traced from %.2f, %.2f, %.2f (%.1f from the landing)", seen.x, seen.y, seen.z,
                    seen.distanceTo(to));
        }

        /**
         * A start PATH node at (ax, az) awaiting one secret, its saved warps onto {@code targets} (relative blocks), and the
         * arrival PATH node on the last one. The chest at {@code chest} is clicked with the AOTV held; the path then flies.
         *
         * @return null when every position was the start or a planned landing, in order, ending on the arrival; else why
         */
        private static String flyPair(ClientGameTestContext ctx, String label, int ax, int az, int[][] targets, int[] chest) {
            forgetRunMemory(ctx);
            double ox = ax + 0.5;
            double oy = F;
            double oz = az + 0.5;
            JsonArray hops = new JsonArray();
            List<double[]> landings = new ArrayList<>();
            for (int[] t : targets) {
                double cx = t[0] + 0.5;
                double cz = t[2] + 0.5;
                double top = t[1] + 1.0;
                double horiz = Math.hypot(cx - ox, cz - oz);
                float yaw = (float) Math.toDegrees(Math.atan2(-(cx - ox), cz - oz));
                float pitch = (float) Math.toDegrees(Math.atan2(oy + 1.27 - top, horiz));
                double lx = cx;
                double ly = top + 0.05;
                double lz = cz;
                hops.add(String.format(Locale.US, "%.6f %.6f %.6f %.6f %.6f %d %d %d %.6f %.6f %.6f", ox, oy, oz, yaw, pitch,
                        t[0], t[1], t[2], lx, ly, lz));
                landings.add(new double[]{lx, top, lz});
                ox = lx;
                oy = top;
                oz = lz;
            }
            JsonObject start = node("PATH", ax, az, 0f, 0f);
            start.addProperty("start", true);
            start.addProperty("awaitEnabled", true);
            start.addProperty("await", "SECRET");
            start.addProperty("amount", 1);
            start.add("hops", hops);
            start.addProperty("planFrom", String.format(Locale.US, "%.6f %.6f %.6f", ax + 0.5, (double) F, az + 0.5));
            start.addProperty("planTo", String.format(Locale.US, "%.6f %.6f %.6f", ox, oy, oz));
            start.addProperty("planBreakers", 0);
            int[] last = targets[targets.length - 1];
            JsonObject arrival = node("PATH", last[0], last[2], 0f, 0f);
            arrival.addProperty("y", oy);
            writeRoute(ctx, List.of(start, arrival));
            // Looking down at the floor: the chest click's look, which the first warp must turn away from.
            ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
            tpRel(ctx, ax + 0.5, az + 0.5, 90f, 80f);
            ctx.waitTicks(10);
            long m = LogTap.mark();
            List<Vec3> expect = new ArrayList<>();
            expect.add(ctx.computeOnClient(mc -> toReal(ax + 0.5, F, az + 0.5)));
            for (double[] l : landings) {
                expect.add(ctx.computeOnClient(mc -> toReal(l[0], l[1], l[2])));
            }
            int dropped0 = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall("com.killer560.hub.roomsim.SimAbilities",
                    "usesTakenAsClickHalf"));
            startSampling(ctx);
            List<Sample> s;
            boolean met;
            try {
                // The chest, clicked with the AOTV in hand (as Secret Aura clicks): its verdict is stored, unpaired.
                rightClick(ctx, chest[0], chest[1], chest[2], Direction.UP);
                met = waitFor(ctx, 40, () -> awaitMet(m));
                ctx.runOnClient(mc -> mc.player.closeContainer());
                Vec3 end = expect.get(expect.size() - 1);
                waitFor(ctx, 100, () -> ctx.computeOnClient(mc -> mc.player.position().distanceTo(end) < 0.4));
                ctx.waitTicks(10);
            } finally {
                s = stopSampling(ctx);
                stopRoute(ctx);
            }
            int dropped = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall("com.killer560.hub.roomsim.SimAbilities",
                    "usesTakenAsClickHalf")) - dropped0;
            // Every sampled spot is the start or a planned landing, and the landings come in order.
            int reached = 0;
            List<String> off = new ArrayList<>();
            for (Sample x : s) {
                int at = -1;
                for (int i = 0; i < expect.size(); i++) {
                    Vec3 e = expect.get(i);
                    if (Math.hypot(x.pos().x - e.x, x.pos().z - e.z) < 0.35 && Math.abs(x.pos().y - e.y) < 0.35) {
                        at = i;
                    }
                }
                if (at < 0) {
                    if (off.size() < 3) {
                        off.add(String.format(Locale.US, "tick %d at %.2f, %.2f, %.2f", x.tick(), x.pos().x, x.pos().y,
                                x.pos().z));
                    }
                } else if (at > reached) {
                    if (at != reached + 1) {
                        off.add("landing " + at + " reached before landing " + (reached + 1));
                    }
                    reached = at;
                }
            }
            int uses = useTicks(s).size();
            boolean noLanding = logHas(m, "no landing after");
            println(String.format(Locale.US, "pathland %s: await met %s, %d warp(s) planned, %d use(s) sent, landings reached "
                            + "%d of %d, off-plan spots %s, sim dropped %d use(s) as a click's second half, 'no landing' %s",
                    label, met, landings.size(), uses, reached, landings.size(), off, dropped, noLanding));
            if (!met) {
                return label + ": the chest click never met the start node's await - nothing flew";
            }
            if (uses == 0) {
                return label + ": no warp was sent";
            }
            if (reached != landings.size() || !off.isEmpty() || noLanding) {
                return label + ": landed " + reached + " of " + landings.size() + " planned landing(s); off the plan: " + off
                        + (noLanding ? "; a warp never landed" : "") + "; sim dropped " + dropped + " use(s)";
            }
            return null;
        }

        static Vec3 toRealPublic(double x, double y, double z) {
            return toReal(x, y, z);
        }

        private static Vec3 toReal(double x, double y, double z) {
            Object frame = ModUnderTest.staticCall(FRAME, "current");
            return (Vec3) ModUnderTest.staticCall(COORDS, "toReal", new Class<?>[]{frame.getClass(), double.class,
                    double.class, double.class}, new Object[]{frame, x, y, z});
        }
    }

    /**
     * {@code 96-ar-1015-reaim} (rightclick-reaim-1015). killer560, 2026-10-15 ("nothing that flags or breaks"): when the
     * server's ray for a warp - traced from the 1.8 eye, 0.27 above the modern sneaking one - meets a block this client's
     * look cannot reach, no click can pair the use and Hypixel drops it; the warp must be held ONE tick and re-aimed to a
     * look whose rays agree. The mismatch is built: an ETHERWARP start node at (6, 20) awaiting a chest clicked with the
     * AOTV held, aimed at the west face of a raised block at (11, F, 20), with a stone block floating at (9, F+1, 20) that
     * only the 1.8 eye's ray meets (geometry found by search, killer560s-mod-logs/reaim-geom.py). He already looks along the
     * warp, so the control (no floating block) sends on its firing tick. The old jar sends the same and the sim drops it;
     * the new one re-aims and lands on the block one tick after the control.
     */
    static final class ArReaimCases {

        private ArReaimCases() {
        }

        private record Run(boolean met, boolean landed, int acted, int dropped, boolean reaimed, boolean noLanding,
                           List<String> off, boolean window) {
        }

        /** Runs per side before giving up on one the chest's window did not land in. */
        private static final int WINDOW_ATTEMPTS = 5;

        static void reaim(ClientGameTestContext ctx) {
            Run control = clean(ctx, false, new int[]{8, F, 23});
            Run mismatch = clean(ctx, true, new int[]{8, F, 24});
            println(String.format(Locale.US, "reaim control: %s | mismatch: %s", control, mismatch));
            check(!control.window() && !mismatch.window(), "the chest's window landed in the warp's ticks on all "
                    + WINDOW_ATTEMPTS + " attempts of a side - no comparable run");
            resetRoutes(ctx);
            forgetRunMemory(ctx);
            check(control.met() && control.landed() && control.dropped() == 0 && control.acted() >= 0,
                    "control (no floating block): the warp did not land cleanly - " + control);
            check(!control.reaimed(), "control: a look whose rays agree was re-aimed - it must cost nothing");
            check(mismatch.met(), "mismatch: the chest click never met the await - nothing flew");
            check(mismatch.dropped() == 0 && !mismatch.noLanding(), "mismatch: the server dropped the warp as the chest "
                    + "click's second half (" + mismatch.dropped() + " use(s)) - the client sent a look it could not pair");
            check(mismatch.landed() && mismatch.off().isEmpty(), "mismatch: did not land on the raised block - off " + mismatch.off());
            check(mismatch.reaimed(), "mismatch: no re-aim line - the case it is about never happened");
            check(mismatch.acted() == control.acted() + 1, "mismatch: the warp went " + mismatch.acted()
                    + " tick(s) after firing, the control " + control.acted() + " - the re-aim must cost exactly one tick");
        }

        /**
         * {@link #run} again while the clicked chest's window arrived after the await was met and the warp started over
         * under it (the mod's "window arrived after the await was met" line, mod ar-merge-regress-1015). In the sim the
         * window comes 1-2 ticks after the click, so whether it lands before the use is the client/server tick phase,
         * which drifts through a session: such a run's count is the window's, not the re-aim's. The control read 2 for 1
         * in session runs after 96-ar-1015-pathland, on the rightclick-reaim tip and on merged main alike (2026-10-15).
         */
        private static Run clean(ClientGameTestContext ctx, boolean floating, int[] chest) {
            Run r = null;
            for (int i = 0; i < WINDOW_ATTEMPTS; i++) {
                r = run(ctx, floating, chest);
                if (!r.window()) {
                    break;
                }
                println(String.format(Locale.US, "reaim %s attempt %d: the chest's window arrived after the await was met"
                        + " - acted %d is the window's, run again", floating ? "mismatch" : "control", i + 1, r.acted()));
            }
            return r;
        }

        private static Run run(ClientGameTestContext ctx, boolean floating, int[] chest) {
            // The chest is the TEST's click: an aura left on by an earlier scenario in this client clicks it first, in the
            // 10-tick settle, and the await is met before the mark (met=false, acted=-1; 2026-10-16 after 147-lastsecret).
            ctx.runOnClient(mc -> ModUnderTest.turnOff(CHEAT_CFG, "setSecretAuraEnabled"));
            forgetRunMemory(ctx);
            resetRoutes(ctx);
            arena(ctx, false);
            giveHotbar(ctx);
            Map<int[], BlockState> put = new LinkedHashMap<>();
            put.put(new int[]{11, F, 20}, Blocks.STONE.defaultBlockState());
            put.put(chest, Blocks.CHEST.defaultBlockState());
            if (floating) {
                put.put(new int[]{9, F + 1, 20}, Blocks.STONE.defaultBlockState());
            }
            setBlocks(ctx, put);
            // The warp's look: the west face's middle of the raised block, from the sneaking eye at (6.5, F, 20.5).
            double dx = 11.03 - 6.5;
            double drop = 1.27 - 0.5;
            float yaw = relYawTo(6, 20, 11, 20);
            float pitch = (float) Math.toDegrees(Math.atan2(drop, dx));
            JsonObject n = node("ETHERWARP", 6, 20, yaw, pitch);
            n.addProperty("landing", String.format(Locale.US, "%.3f %.3f %.3f", 11.5, F + 1.05, 20.5));
            n.addProperty("start", true);
            n.addProperty("awaitEnabled", true);
            n.addProperty("await", "SECRET");
            n.addProperty("amount", 1);
            writeRoute(ctx, List.of(n));
            ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
            // Already looking along the warp: the server has heard its look, so nothing needs turning in the control.
            tpRel(ctx, 6.5, 20.5, yaw, pitch);
            ctx.waitTicks(10);
            int dropped0 = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall("com.killer560.hub.roomsim.SimAbilities",
                    "usesTakenAsClickHalf"));
            Vec3 start = ctx.computeOnClient(mc -> ArPathLandCases.toRealPublic(6.5, F, 20.5));
            Vec3 top = ctx.computeOnClient(mc -> ArPathLandCases.toRealPublic(11.5, F + 1, 20.5));
            long m = LogTap.mark();
            startSampling(ctx);
            List<Sample> s;
            boolean met;
            try {
                rightClick(ctx, chest[0], chest[1], chest[2], Direction.UP);
                met = waitFor(ctx, 40, () -> awaitMet(m));
                ctx.runOnClient(mc -> mc.player.closeContainer());
                waitFor(ctx, 100, () -> ctx.computeOnClient(mc -> mc.player.position().distanceTo(top) < 0.4));
                ctx.waitTicks(10);
            } finally {
                s = stopSampling(ctx);
                stopRoute(ctx);
            }
            int dropped = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall("com.killer560.hub.roomsim.SimAbilities",
                    "usesTakenAsClickHalf")) - dropped0;
            List<String> off = new ArrayList<>();
            boolean landed = false;
            for (Sample x : s) {
                boolean atStart = Math.hypot(x.pos().x - start.x, x.pos().z - start.z) < 0.35 && Math.abs(x.pos().y - start.y) < 0.35;
                boolean atTop = Math.hypot(x.pos().x - top.x, x.pos().z - top.z) < 0.35 && Math.abs(x.pos().y - top.y) < 0.35;
                landed |= atTop;
                if (!atStart && !atTop && off.size() < 3) {
                    off.add(String.format(Locale.US, "tick %d at %.2f, %.2f, %.2f", x.tick(), x.pos().x, x.pos().y, x.pos().z));
                }
            }
            int acted = -1;
            String line = null;
            for (String l : LogTap.since(m)) {
                if (l.contains("Node #1 ETHERWARP acted")) {
                    line = l;
                }
            }
            if (line != null) {
                java.util.regex.Matcher mm = java.util.regex.Pattern.compile("acted (\\d+) tick").matcher(line);
                if (mm.find()) {
                    acted = Integer.parseInt(mm.group(1));
                }
            }
            return new Run(met, landed, acted, dropped, logHas(m, "re-aimed"), logHas(m, "no landing after"), off,
                    logHas(m, "window arrived after the await was met"));
        }
    }
}
