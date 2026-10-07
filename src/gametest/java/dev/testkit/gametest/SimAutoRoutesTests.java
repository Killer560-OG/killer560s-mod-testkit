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
 * nodes and the two await counters), breaker, pingpong, charges (a secret gives the sim breaker 2 charges), museum (his
 * exact Museum route: stacked booms into a crypt node), edit, mapopen, path, screen, rotate. GrimAC does not apply
 * here: the sim is an integrated server.
 */
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
            "96-ar-crypt", "96-ar-breaker", "96-ar-pingpong", "96-ar-chain", "96-ar-stackorder", "96-ar-crypthold",
            "96-ar-charges", "96-ar-museum",
            "96-ar-edit", "96-ar-dbedit", "96-ar-mapopen",
            "96-ar-path", "96-ar-screen", "96-ar-rotate"};

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
            System.out.println("[" + NAME + "] SKIPPED - needs his real rooms and room database");
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
                        case "96-ar-pingpong" -> casePingPong(ctx);
                        case "96-ar-chain" -> caseChain(ctx);
                        case "96-ar-stackorder" -> caseStackOrder(ctx);
                        case "96-ar-crypthold" -> caseCryptHold(ctx);
                        case "96-ar-charges" -> caseCharges(ctx);
                        case "96-ar-museum" -> caseMuseum(ctx);
                        case "96-ar-edit" -> caseEdit(ctx);
                        case "96-ar-dbedit" -> caseDbEdit(ctx);
                        case "96-ar-mapopen" -> caseMapOpen(ctx);
                        case "96-ar-screen" -> caseScreen(ctx);
                        case "96-ar-path" -> casePath(ctx);
                        case "96-ar-rotate" -> caseRotate(ctx);
                        default -> {
                        }
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
        rightClick(ctx, 22, F, 10, Direction.WEST);
        ctx.waitTicks(3);
        rightClick(ctx, 22, F + 1, 10, Direction.WEST);
        ctx.waitTicks(3);
        cmd(ctx, "/ar edit db");
        ctx.waitTicks(3);
        nodes = fileNodes(ctx);
        JsonObject br = nodes.get(3).getAsJsonObject();
        check("DUNGEON_BREAKER".equals(br.get("type").getAsString()) && br.has("blocks")
                && br.getAsJsonArray("blocks").size() == 2, "the breaker did not save its two picked blocks: " + br);
        check(!blockAir(ctx, 22, F, 10), "edit mode broke a block it should only have picked");
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
        cmd(ctx, "/ar add jump");
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
     * tick; off, one per interact-delay tick; and with fewer charges than blocks only that many are sent.
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
            ctx.waitTicks(30);
            List<Sample> s = stopSampling(ctx);
            int total = 0;
            for (Sample x : s) {
                total += (int) x.out().stream().filter("player_action"::equals).count();
            }
            int charges = -1;
            java.util.regex.Pattern cp = java.util.regex.Pattern.compile("Breaker: 6 block\\(s\\) queued, (\\d+) charge");
            for (String l : LogTap.since(m)) {
                java.util.regex.Matcher mm = cp.matcher(l);
                if (mm.find()) {
                    charges = Integer.parseInt(mm.group(1));
                }
            }
            println("drained breaker: " + charges + " charge(s) read, " + total + " dig(s) sent for 6 blocks");
            check(charges >= 0 || logHas(m, "has no charges"), "the second breaker node never fired");
            if (charges > 0 && charges < 6) {
                check(total == charges, "with " + charges + " charges it sent " + total + " digs");
                check(logHas(m, "out of charges"), "no out-of-charges line");
            } else {
                println("drained breaker: charges were " + charges + " - the partial case was not reached this run");
                check(charges <= 0 ? total == 0 : total == 6, "charges " + charges + ": " + total + " digs");
            }
        } finally {
            ctx.runOnClient(mc -> {
                ModUnderTest.set(ModUnderTest.config(DX_CONFIG), "setBreakerAuraMultiBreak", true);
                ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setStartFromStartNodeOnly", true);
            });
        }
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

    private static final String EDIT_INPUT = "com.killer560.hub.autoroutes.AutoRoutesEditInput";
    private static final String AR_RENDERER = "com.killer560.hub.autoroutes.AutoRoutesRenderer";
    private static final String DX_ENUMS = "com.killer560.hub.dungeonextras.DungeonExtrasConfig";

    /**
     * Breaker edit mode (killer560, 2026-10-06: "when in edit mode it shouldnt be able to break blocks if i right click a
     * block it adds it if i shift right click it removes it have an option under auto routes for it to be waypoints or
     * highlights"). /ar add dungeonbreaker makes a DUNGEON_BREAKER node and is offered in completion. Control: with the
     * Dungeon Breaker in hand and edit mode off, a real left click on stone sends a swing/dig. In edit mode the same
     * click, and a held one, sends neither and the block stays; right click adds, shift-right-click removes, and the
     * saved blocks follow. Every Breaker Block Display x Style combination is drawn by its own path. After edit mode,
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

        // ---- edit mode on: the same click, and a held one, on a stone that is NOT picked ----
        long m = LogTap.mark();
        cmd(ctx, "/ar edit db");
        ctx.waitTicks(3);
        check(ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(FEATURE, "isEditMode")),
                "/ar edit db did not turn edit mode on");
        tpRel(ctx, 20.5, 10.5, -90f, 0f);
        ctx.waitTicks(5);
        check(aimedAt(ctx, 22, F + 1, 10), "edit mode's left click is not aimed at its stone");
        int swallowedBefore = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(EDIT_INPUT, "swallowedLeftClicks"));
        startSampling(ctx);
        leftClick(ctx, 2);
        ctx.waitTicks(5);
        leftClick(ctx, 20);
        ctx.waitTicks(5);
        s = stopSampling(ctx);
        int swallowed = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(EDIT_INPUT, "swallowedLeftClicks"))
                - swallowedBefore;
        StringBuilder trace = new StringBuilder();
        for (Sample x : s) {
            if (!x.out().isEmpty()) {
                trace.append(String.format(" | t%d %s", x.tick(), x.out()));
            }
        }
        println("dbedit edit-mode window: " + s.size() + " ticks sampled, " + swallowed + " click(s) swallowed:" + trace);
        check(s.size() >= 30, "only " + s.size() + " tick(s) were sampled in the edit-mode window");
        check(swallowed == 2, "edit mode swallowed " + swallowed + " left click(s), expected 2 (a tap and a hold)");
        check(logHas(m, "edit mode swallowed a left click"), "no 'edit mode swallowed a left click' log line");
        check(countOut(s, "swing") == 0, "a left click in edit mode still swung:" + trace);
        check(countOut(s, "player_action") == 0, "a left click in edit mode still sent a dig:" + trace);
        check(!blockAir(ctx, 22, F + 1, 10), "a left click in edit mode broke the block");

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
        check(!blockAir(ctx, 22, F, 10) && !blockAir(ctx, 22, F + 1, 10), "edit mode broke a picked block");

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
        tpRel(ctx, x + 0.5, z - 1.5, yaw, pitch);
        ctx.waitTicks(10);
        long m = LogTap.mark();
        tpRel(ctx, x + 0.5, z + 0.5, yaw, pitch);
        return waitFor(ctx, 40, () -> logHas(m, "Started \""));
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
            println("awaitskip crypt: skipped=" + skipped + ", use key up=" + useUp);
            check(skipped, "his left click did not end the crypt node's wait for a kill");
            check(useUp, "the crypt node left the use key held after the skip");
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
            check(logHas(m, "Hyperion used straight down (use 1)"), "no straight-down Hyperion use logged");
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
                boolean done = waitFor(ctx, 160, () -> logHas(m, "CRYPT: 1 kill(s)"));
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
            boolean cryptDone = waitFor(ctx, 160, () -> logHas(mc3, "CRYPT: 1 kill(s)"));
            check(cryptDone, "combined: the crypt node never killed its crypt");
            ctx.waitTicks(40);
            check(!logHas(mc3, "ETHERWARP acted"), "combined: the etherwarp went before its secret");
            check(relPos(ctx).distanceTo(new Vec3(19.5, F, 10.5)) < 1.5, "combined: he left the tile before the secret ("
                    + relPos(ctx) + ")");
            ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(3));
            rightClick(ctx, 21, F, 10, Direction.UP);
            Vec3 l = waitLanded(ctx, 19, 4, 80);
            int iB = logIndex(mc3, "BOOM acted");
            int iC = logIndex(mc3, "CRYPT: 1 kill");
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

    /** Waits until he stands in relative block (x, z) at the arena floor; his position then, or null. */
    private static Vec3 waitLanded(ClientGameTestContext ctx, int x, int z, int ticks) {
        for (int i = 0; i < ticks; i++) {
            Vec3 p = relPos(ctx);
            if (Math.floor(p.x) == x && Math.floor(p.z) == z && Math.abs(p.y - F) < 0.2) {
                return p;
            }
            ctx.waitTicks(1);
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
                "node not on the centre of block (" + bx + "," + bz + "): " + n);
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
}
