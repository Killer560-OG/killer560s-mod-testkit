package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.harness.SuiteVerdict;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

/**
 * 1771-1775: the sim's {@code /reset} (killer560, 2026-10-16): "if someone is doing a solo room in the sim can you add
 * the command /reset that will teleport them back to the spawnpoint of the room with the room fully regenerated", and
 * "make the /reset not show up on main as a command only in sim".
 *
 * <ul>
 *   <li><b>1771-sim-reset-room</b> (Bridges, which holds every secret kind): a chest opened, an essence taken, an item
 *       secret picked up, the secret bat spawned and killed, a zombie killed and a starred one left alive, a 3x3 of
 *       wall knocked out, and him walked off and turned. Each change is asserted to have HAPPENED first. Then
 *       {@code /reset} typed as he types it: every block of the room's box back to what the load built (compared block
 *       by block), the entities back to the load's, the secrets back to pending, the score back to 0, the live map's
 *       reset generation moved (the run memory every client feature keys on), "Room reset" in chat, and him at the
 *       spawn with the spawn's yaw and pitch. Then {@code /reset} a second time, straight after: the same again.</li>
 *   <li><b>1772-sim-reset-puzzle</b> (Creeper Beams): one pair joined (joinedCount 1, its lanterns changed) and the
 *       room's puzzle creeper killed; after {@code /reset} no pair is joined, the lanterns and creeper are back, and the
 *       room is no longer painted failed.</li>
 *   <li><b>1773-sim-reset-floor</b>: on a generated F7, {@code /reset} builds nothing, leaves a removed block removed
 *       and him where he was, and says it is for solo rooms.</li>
 *   <li><b>1774-sim-reset-tree</b>: in the sim the command tree the chat completes from has the mod's {@code reset},
 *       and "res" suggests it.</li>
 *   <li><b>1775-sim-reset-server</b>: on a real server with its own {@code /reset} (the testmod's), the client tree's
 *       {@code reset} is the SERVER's node, the sim commands are absent while the mod's ungated client commands are
 *       there (the copy runs, so the absence is the gate), "res" suggests reset once, and typing {@code /reset}
 *       sends the line unchanged and the server answers it; the mod says nothing. Run after the sim cases in the same
 *       client, so a stale tree from the sim would show here.</li>
 * </ul>
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimResetTests implements FabricClientGameTest {

    private static final String NAME = "177x-sim-reset";
    private static final String[] SIM_CASES = {"1771-sim-reset-room", "1772-sim-reset-puzzle", "1773-sim-reset-floor",
            "1774-sim-reset-tree"};
    private static final String SERVER_CASE = "1775-sim-reset-server";

    private static final String BUILDER = "com.killer560.hub.roomsim.SimBuilder";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String SIM_SECRETS = "com.killer560.hub.roomsim.SimSecrets";
    private static final String SIM_SECRET_ITEMS = "com.killer560.hub.roomsim.SimSecretItems";
    private static final String SIM_SCORE = "com.killer560.hub.roomsim.SimScore";
    private static final String SIM_MOBS = "com.killer560.hub.roomsim.SimMobs";
    private static final String BUILD_QUEUE = "com.killer560.hub.roomsim.SimBuildQueue";
    private static final String ALTITUDE = "com.killer560.hub.roomsim.SimAltitude";
    private static final String ROOM_STATE = "com.killer560.hub.roomsim.SimRoomState";
    private static final String CREEPER = "com.killer560.hub.roomsim.puzzles.SimCreeperPuzzle";
    private static final String LIVE_MAP = "com.killer560.hub.livemap.LiveMapFeature";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String INTERNALS = "net.fabricmc.fabric.impl.command.client.ClientCommandInternals";

    private static UUID playerId;
    private static final List<String> RESULTS = new ArrayList<>();

    @Override
    public void runTest(ClientGameTestContext ctx) {
        List<String> selected = new ArrayList<>();
        for (String c : SIM_CASES) {
            if (!Scenario.skip(c)) {
                selected.add(c);
            }
        }
        boolean server = !Scenario.skip(SERVER_CASE);
        if (selected.isEmpty() && !server) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(20);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (!selected.isEmpty()) {
            if (SimMapTests.copyRoomsForOthers() < 20) {
                for (String c : selected) {
                    Scenario.skipped(c, "needs real room captures and room database");
                }
                selected.clear();
            }
        }
        if (!selected.isEmpty()) {
            Scenario.ensureRoomDatabase(ctx);
            ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
            ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);
            // Compares whole rooms block for block across /reset and opens a chest by its block: secret chests go in at
            // the build here (mod sim-secrets-1018 makes them appear when he comes near).
            SimChestHook.placeAtBuild(ctx);
            try {
                for (String c : selected) {
                    long mark = LogTap.mark();
                    try {
                        say("---- " + c + " ----");
                        switch (c) {
                            case "1771-sim-reset-room" -> caseRoom(ctx);
                            case "1772-sim-reset-puzzle" -> casePuzzle(ctx);
                            case "1773-sim-reset-floor" -> caseFloor(ctx);
                            case "1774-sim-reset-tree" -> caseSimTree(ctx);
                            default -> {
                            }
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
                        SuiteVerdict.failCase(c, t);
                        if (ctx.computeOnClient(mc -> mc.level == null)) {
                            break;
                        }
                    }
                }
            } finally {
                SimChestHook.restore(ctx);
                teardown(ctx);
            }
        }
        if (server) {
            caseServer(ctx);
        }
        System.out.println("[" + NAME + "] SUMMARY");
        for (String r : RESULTS) {
            System.out.println("[" + NAME + "]   " + r);
        }
    }

    // ============================================================================================ 1771

    private void caseRoom(ClientGameTestContext ctx) {
        String room = "Bridges";
        build(ctx, room);
        Snapshot base = snapshot(ctx);
        say("loaded " + room + ": spawn " + base.describePos() + ", " + base.blocks.length + " block(s) in the box, "
                + "entities " + base.entities + ", item secrets pending " + base.itemsPending + ", bats pending "
                + base.batsPending + ", essences " + base.essences + ", chests " + base.chests.size());
        check(!base.chests.isEmpty(), room + " placed no secret chest, so a chest cannot be opened");
        check(!base.essences.isEmpty(), room + " placed no essence");
        check(base.itemsPending > 0, room + " registered no item secret");
        check(base.batsPending > 0, room + " registered no secret bat");
        check(base.secrets == 0, "a fresh load starts with " + base.secrets + " secret(s) found");

        // ---- a chest opened ---------------------------------------------------------------------------------------
        BlockPos chest = base.chests.get(0);
        standNear(ctx, chest);
        click(ctx, chest);
        closeScreen(ctx);
        int afterChest = secrets(ctx);
        say("opened the chest at " + chest.toShortString() + ": secrets " + base.secrets + " -> " + afterChest);
        check(afterChest == base.secrets + 1, "opening the chest at " + chest.toShortString() + " counted no secret");

        // ---- an essence taken -------------------------------------------------------------------------------------
        BlockPos essence = base.essences.get(0);
        standNear(ctx, essence);
        click(ctx, essence);
        ctx.waitTicks(5);
        String essenceAfter = blockName(ctx, essence);
        say("clicked the essence at " + essence.toShortString() + ": now " + essenceAfter);
        check(essenceAfter.equals("air"), "the essence at " + essence.toShortString() + " is still " + essenceAfter);

        // ---- an item secret picked up -----------------------------------------------------------------------------
        @SuppressWarnings("unchecked")
        BlockPos item = ((List<BlockPos>) ModUnderTest.staticCall(SIM_SECRET_ITEMS, "pendingPositions")).get(0);
        int beforeItem = secrets(ctx);
        for (int hold = 0; hold < 30 && secrets(ctx) == beforeItem; hold++) {
            int off = hold < 14 ? 0 : hold % 3 - 1;
            tp(ctx, item.getX() + 0.5, item.getY() + off, item.getZ() + 0.5, 0f, 0f);
            ctx.waitTicks(4);
        }
        int itemsPendingNow = (Integer) ModUnderTest.staticCall(SIM_SECRET_ITEMS, "pendingCount");
        say("stood on the item secret at " + item.toShortString() + ": secrets " + beforeItem + " -> " + secrets(ctx)
                + ", pending " + base.itemsPending + " -> " + itemsPendingNow);
        check(secrets(ctx) == beforeItem + 1, "the item secret at " + item.toShortString() + " was not collected");

        // ---- the secret bat spawned and killed --------------------------------------------------------------------
        @SuppressWarnings("unchecked")
        BlockPos bat = ((List<BlockPos>) ModUnderTest.staticCall(SIM_SECRET_ITEMS, "pendingBats")).get(0);
        standNear(ctx, bat);
        boolean spawned = false;
        for (int i = 0; i < 60 && !spawned; i++) {
            ctx.waitTicks(1);
            spawned = onServer(ctx, (level, sp) -> !level.getEntitiesOfClass(Bat.class,
                    new AABB(bat).inflate(6)).isEmpty());
        }
        check(spawned, "the secret bat at " + bat.toShortString() + " never spawned with him beside it");
        int batsBefore = (Integer) ModUnderTest.staticCall(SIM_SCORE, "batsKilled");
        onServer(ctx, (level, sp) -> {
            for (Bat b : level.getEntitiesOfClass(Bat.class, new AABB(bat).inflate(6))) {
                b.hurtServer(level, level.damageSources().playerAttack(sp), 1000f);
            }
            return null;
        });
        ctx.waitTicks(10);
        int batsAfter = (Integer) ModUnderTest.staticCall(SIM_SCORE, "batsKilled");
        say("killed the secret bat: bats " + batsBefore + " -> " + batsAfter);
        check(batsAfter == batsBefore + 1, "the secret bat's death was not counted");

        // ---- mobs: one killed, one starred left alive ------------------------------------------------------------
        BlockPos spawnFeet = BlockPos.containing(base.x, base.y, base.z);
        ctx.runOnClient(mc -> {
            Object zombie = ModUnderTest.enumValue(SIM_MOBS + "$Kind", "ZOMBIE");
            ModUnderTest.staticCall(SIM_MOBS, "spawn", new Class<?>[]{Minecraft.class, BlockPos.class, zombie.getClass()},
                    new Object[]{mc, spawnFeet, zombie});
            ModUnderTest.staticCall(SIM_MOBS, "spawnStarred",
                    new Class<?>[]{Minecraft.class, BlockPos.class, zombie.getClass()},
                    new Object[]{mc, spawnFeet.offset(1, 0, 0), zombie});
        });
        ctx.waitTicks(20);
        int killed = onServer(ctx, (level, sp) -> {
            for (Entity e : level.getEntities((Entity) null, new AABB(spawnFeet).inflate(0.6))) {
                if (e instanceof LivingEntity le && !(e instanceof ServerPlayer) && le.isAlive()
                        && !(e instanceof net.minecraft.world.entity.decoration.ArmorStand)) {
                    le.hurtServer(level, level.damageSources().playerAttack(sp), 1000f);
                    return 1;
                }
            }
            return 0;
        });
        ctx.waitTicks(30);
        Map<String, Integer> mobsNow = entities(ctx, base.box);
        say("spawned a zombie and a starred zombie, killed " + killed + ": entities now " + mobsNow);
        check(killed == 1, "no zombie to kill appeared at the spawn");
        check(!mobsNow.equals(base.entities), "the mobs did not change the room's entities");

        // ---- a wall knocked out -----------------------------------------------------------------------------------
        List<BlockPos> wall = knockOutWall(ctx, base);
        check(countNotAir(ctx, wall) == 0, "the knocked-out wall is still there");

        // ---- walked away and turned -------------------------------------------------------------------------------
        float awayYaw = base.yaw + 137f;
        float awayPitch = base.pitch > 0 ? -35f : 35f;
        tp(ctx, item.getX() + 0.5, item.getY(), item.getZ() + 0.5, awayYaw, awayPitch);
        ctx.waitTicks(10);
        int changed = diff(base.blocks, snapshotBlocks(ctx, base.box));
        Snapshot moved = snapshot(ctx, base.box);
        say(String.format(Locale.US, "before /reset: %d block(s) changed, at %s yaw %.1f pitch %.1f, secrets %d",
                changed, moved.describePos(), moved.yaw, moved.pitch, moved.secrets));
        check(changed >= wall.size() + 1, "only " + changed + " block(s) differ from the load - the changes did not land");
        check(moved.distanceTo(base) > 3, "he is still at the spawn, so the teleport back cannot be told apart");

        // ---- /reset, then /reload straight after (the same command under both names) ----------------------------
        for (int round = 1; round <= 2; round++) {
            Snapshot after = reset(ctx, "Room reset", base.box, round == 1 ? "reset" : "reload");
            int diffs = diff(base.blocks, after.blocks);
            say(String.format(Locale.US, "after /reset #%d: %d block(s) differ, at %s yaw %.2f pitch %.2f, entities %s,"
                            + " items pending %d, bats pending %d, essences %d, secrets %d, map generation %d -> %d",
                    round, diffs, after.describePos(), after.yaw, after.pitch, after.entities, after.itemsPending,
                    after.batsPending, after.essences.size(), after.secrets, base.generation, after.generation));
            if (diffs != 0) {
                say("first differences: " + firstDiffs(base, after, 10));
            }
            check(diffs == 0, "/reset #" + round + " left " + diffs + " block(s) different from the load");
            check(after.entities.equals(base.entities), "/reset #" + round + " left the entities at " + after.entities
                    + " where the load had " + base.entities);
            check(after.itemsPending == base.itemsPending, "item secrets pending " + after.itemsPending + ", load "
                    + base.itemsPending);
            check(after.batsPending == base.batsPending, "bats pending " + after.batsPending + ", load "
                    + base.batsPending);
            check(after.essences.equals(base.essences), "essences " + after.essences + ", load " + base.essences);
            check(after.chests.equals(base.chests), "chests " + after.chests + ", load " + base.chests);
            check(after.secrets == 0, after.secrets + " secret(s) still counted after /reset #" + round);
            check(after.generation > base.generation, "the live map's reset generation did not move, so the client "
                    + "features' per-run marks were not cleared");
            checkSpawn(after, base, round);
            base = base.withGeneration(after.generation);
        }
    }

    // ============================================================================================ 1772

    private void casePuzzle(ClientGameTestContext ctx) {
        String room = "Creeper Beams";
        build(ctx, room);
        ctx.waitTicks(20);
        Snapshot base = snapshot(ctx);
        int joined0 = (Integer) ModUnderTest.staticCall(CREEPER, "joinedCount");
        BlockPos[] pair = firstPair();
        String a0 = blockName(ctx, pair[0]);
        String b0 = blockName(ctx, pair[1]);
        say("loaded " + room + ": spawn " + base.describePos() + ", joined " + joined0 + ", pair " + pair[0].toShortString()
                + " (" + a0 + ") / " + pair[1].toShortString() + " (" + b0 + "), entities " + base.entities);
        check(joined0 == 0, "a fresh Creeper Beams starts with " + joined0 + " pair(s) joined");

        ctx.runOnClient(mc -> ModUnderTest.staticCall(CREEPER, "tryConnectAt", new Class<?>[]{BlockPos.class},
                new Object[]{pair[0]}));
        ctx.waitTicks(5);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(CREEPER, "tryConnectAt", new Class<?>[]{BlockPos.class},
                new Object[]{pair[1]}));
        ctx.waitTicks(10);
        int joined1 = (Integer) ModUnderTest.staticCall(CREEPER, "joinedCount");
        String a1 = blockName(ctx, pair[0]);
        String b1 = blockName(ctx, pair[1]);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_STATE, "markFailed", new Class<?>[]{String.class},
                new Object[]{room}));
        int killed = onServer(ctx, (level, sp) -> {
            int n = 0;
            for (Entity e : level.getEntities((Entity) null, base.box)) {
                if (e instanceof net.minecraft.world.entity.monster.Creeper c) {
                    c.hurtServer(level, level.damageSources().playerAttack(sp), 1000f);
                    n++;
                }
            }
            return n;
        });
        ctx.waitTicks(30);
        Map<String, Integer> ents = entities(ctx, base.box);
        tp(ctx, base.x + 2, base.y, base.z + 2, base.yaw - 90f, 20f);
        say("joined a pair: joined " + joined1 + ", lanterns " + a1 + " / " + b1 + "; failed rooms "
                + ModUnderTest.staticCall(ROOM_STATE, "failedRooms") + "; creepers killed " + killed + ", entities " + ents);
        check(joined1 == 1, "joining a pair left joinedCount at " + joined1);
        check(!a1.equals(a0) || !b1.equals(b0), "the joined pair's lanterns did not change");
        int changed = diff(base.blocks, snapshotBlocks(ctx, base.box));
        check(changed > 0, "nothing in the room changed, so the reset cannot be shown to put anything back");

        Snapshot after = reset(ctx, "Room reset", base.box);
        int joined2 = (Integer) ModUnderTest.staticCall(CREEPER, "joinedCount");
        String a2 = blockName(ctx, pair[0]);
        String b2 = blockName(ctx, pair[1]);
        @SuppressWarnings("unchecked")
        List<String> failed = (List<String>) ModUnderTest.staticCall(ROOM_STATE, "failedRooms");
        int diffs = diff(base.blocks, after.blocks);
        say("after /reset: joined " + joined2 + ", lanterns " + a2 + " / " + b2 + ", failed " + failed + ", " + diffs
                + " block(s) differ, entities " + after.entities + ", at " + after.describePos());
        if (diffs != 0) {
            say("first differences: " + firstDiffs(base, after, 10));
        }
        check(joined2 == 0, "/reset left " + joined2 + " pair(s) joined");
        check(a2.equals(a0) && b2.equals(b0), "/reset left the lanterns " + a2 + " / " + b2 + ", load " + a0 + " / " + b0);
        check(!failed.contains(room), "/reset left " + room + " painted failed");
        check(diffs == 0, "/reset left " + diffs + " block(s) different from the load");
        check(after.entities.equals(base.entities), "entities " + after.entities + ", load " + base.entities);
        checkSpawn(after, base, 1);
    }

    // ============================================================================================ 1773

    private void caseFloor(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter", new Class<?>[]{String.class},
                new Object[]{"gametest"}));
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> {
            Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
            ModUnderTest.staticCall(FLOOR_GEN, "generate",
                    new Class<?>[]{Minecraft.class, floor.getClass(), int.class, int.class},
                    new Object[]{mc, floor, 3, 4});
        }));
        ctx.waitFor(mc -> mc.level != null && mc.player != null, 2400);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitFor(mc -> McCompat.screen(mc) == null, 1200);
        waitStanding(ctx, "the F7");
        playerId = ctx.computeOnClient(mc -> mc.player.getUUID());
        check(ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SIM_STATE, "isGeneratedFloor")),
                "the F7 is not flagged a generated floor");
        // FOUND, not assumed: a fixed offset under him was a doorway's air on one floor of two.
        BlockPos under = onServer(ctx, (level, sp) -> {
            for (int dy = -1; dy >= -3; dy--) {
                for (int dx = 1; dx <= 4; dx++) {
                    BlockPos p = sp.blockPosition().offset(dx, dy, 0);
                    if (!level.getBlockState(p).isAir()) {
                        return p;
                    }
                }
            }
            return sp.blockPosition().below();
        });
        String was = blockName(ctx, under);
        onServer(ctx, (level, sp) -> level.setBlock(under, Blocks.AIR.defaultBlockState(), 3));
        check(blockName(ctx, under).equals("air"), "could not remove the block at " + under.toShortString());
        Vec3 at = onServer(ctx, (level, sp) -> sp.position());
        long builds = Scenario.simBuildCount(ctx);
        long mark = LogTap.mark();
        ctx.runOnClient(mc -> mc.player.connection.sendCommand("reset"));
        ctx.waitTicks(100);
        long buildsAfter = Scenario.simBuildCount(ctx);
        String now = blockName(ctx, under);
        Vec3 at2 = onServer(ctx, (level, sp) -> sp.position());
        boolean said = chatSince(mark, "/reset is for solo rooms");
        boolean resetSaid = chatSince(mark, "Room reset");
        say("on an F7: removed " + was + " at " + under.toShortString() + "; after /reset builds " + builds + " -> "
                + buildsAfter + ", block " + now + ", moved " + String.format(Locale.US, "%.2f", at.distanceTo(at2))
                + ", said 'for solo rooms' " + said + ", said 'Room reset' " + resetSaid);
        check(!was.equals("air"), "the probe block under him was already air");
        check(said, "/reset on a floor did not say it is for solo rooms");
        check(!resetSaid, "/reset on a floor said Room reset");
        check(buildsAfter == builds, "/reset on a floor built something");
        check(now.equals("air"), "/reset on a floor put the removed block back (" + now + ")");
        check(at.distanceTo(at2) < 0.5, "/reset on a floor moved him");
    }

    // ============================================================================================ 1774

    private void caseSimTree(ClientGameTestContext ctx) {
        build(ctx, "Bridges");
        ctx.waitTicks(20);
        String[] tree = treeReset(ctx);
        List<String> sugg = suggestions(ctx, "res");
        boolean refreshed = false;
        for (String l : LogTap.since(0)) {
            refreshed |= l.contains("[SimReset] command tree refreshed: sim commands shown");
        }
        say("in the sim: tree 'reset' = " + tree[0] + " (" + tree[1] + "), suggestions for 'res' " + sugg
                + ", refresh logged " + refreshed);
        check("present".equals(tree[0]), "the sim's command tree has no 'reset'");
        check(tree[1].contains("ClientCommandInternals"), "the tree's 'reset' is not the mod's (" + tree[1] + ")");
        check(sugg.contains("reset"), "'res' does not suggest reset in the sim: " + sugg);
    }

    // ============================================================================================ 1775

    private void caseServer(ClientGameTestContext ctx) {
        Scenario.run(ctx, SERVER_CASE,
                (server, scenario) -> TestMap.on(server)
                        .platform(-940, 99, -940, 8)
                        .catchFloor(60)
                        .survival()
                        .spawn(-940 + 0.5, -940 + 0.5, 0f)
                        .build(),
                (server, scenario) -> {
                    Scenario.labelServerAs("hypixel.net");
                    ctx.waitTicks(40);
                    boolean simOn = ctx.computeOnClient(mc ->
                            (Boolean) ModUnderTest.staticCall(SIM_STATE, "isActive"));
                    check(!simOn, "the sim is active on a server");
                    String registered = ctx.computeOnClient(mc -> {
                        try {
                            com.mojang.brigadier.CommandDispatcher<?> d = (com.mojang.brigadier.CommandDispatcher<?>)
                                    Class.forName(INTERNALS).getMethod("getActiveDispatcher").invoke(null);
                            return d.getRoot().getChild("reset") == null ? "not registered" : "registered";
                        } catch (ReflectiveOperationException e) {
                            return "lookup failed: " + e;
                        }
                    });
                    check("registered".equals(registered), "the mod's /reset is " + registered
                            + " on the client dispatcher, so this case would prove nothing");
                    String[] tree = treeReset(ctx);
                    String[] summary = ctx.computeOnClient(mc -> {
                        var root = mc.getConnection().getCommands().getRoot();
                        int fromMod = 0;
                        List<String> simCmds = new ArrayList<>();
                        for (var child : root.getChildren()) {
                            if (child.getCommand() != null
                                    && child.getCommand().getClass().getName().contains("ClientCommandInternals")) {
                                fromMod++;
                            }
                            if (List.of("goto", "tpto", "next", "back").contains(child.getName())) {
                                simCmds.add(child.getName());
                            }
                        }
                        return new String[]{String.valueOf(fromMod), simCmds.toString()};
                    });
                    List<String> sugg = suggestions(ctx, "res");
                    long resetsInSugg = sugg.stream().filter("reset"::equals).count();
                    scenario.log("tree 'reset' = " + tree[0] + " (" + tree[1] + "); mod client nodes in the tree "
                            + summary[0] + "; sim commands in the tree " + summary[1] + "; suggestions for 'res' " + sugg);
                    check("present".equals(tree[0]), "the server's own /reset is not in the client's tree");
                    check(!tree[1].contains("ClientCommandInternals"), "the tree's 'reset' is the mod's client node ("
                            + tree[1] + "), not the server's");
                    check(Integer.parseInt(summary[0]) > 0, "no mod client command was copied into the tree at all, so "
                            + "the absence of the sim ones proves nothing");
                    check("[]".equals(summary[1]), "sim commands are in the server's tree: " + summary[1]);
                    check(resetsInSugg == 1, "'res' suggests reset " + resetsInSugg + " time(s): " + sugg);

                    List<String> sent = new CopyOnWriteArrayList<>();
                    dev.testkit.harness.PacketTrace.tap = p -> {
                        if (p instanceof ServerboundChatCommandPacket cmd) {
                            sent.add(cmd.command());
                        }
                    };
                    long mark = LogTap.mark();
                    try {
                        ctx.runOnClient(mc -> mc.player.connection.sendCommand("reset"));
                        ctx.waitTicks(30);
                    } finally {
                        dev.testkit.harness.PacketTrace.tap = null;
                    }
                    boolean answered = chatSince(mark, "server /reset received");
                    boolean modSaid = chatSince(mark, "solo rooms") || chatSince(mark, "Room reset");
                    boolean modLogged = false;
                    for (String l : LogTap.since(mark)) {
                        modLogged |= l.contains("[SimReset] resetting");
                    }
                    scenario.log("typed /reset: sent " + sent + "; the server answered " + answered + "; the mod said "
                            + "something " + modSaid + ", logged a reset " + modLogged);
                    check(sent.contains("reset"), "the line never went to the server: " + sent);
                    check(answered, "the server's /reset never answered");
                    check(!modSaid && !modLogged, "the mod's /reset ran on a server");
                    System.out.println("[" + SERVER_CASE + "] PASS - the server's /reset is the one shown and run");
                });
    }

    // ============================================================================================ helpers

    /** Root child "reset" of the client's command tree (the one chat completes from): present/absent, command class. */
    private static String[] treeReset(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            var node = mc.getConnection().getCommands().getRoot().getChild("reset");
            if (node == null) {
                return new String[]{"absent", "-"};
            }
            return new String[]{"present", node.getCommand() == null ? "no command"
                    : node.getCommand().getClass().getName()};
        });
    }

    /** The command tree's completions for a typed prefix (no slash), as the chat box asks for them. */
    private static List<String> suggestions(ClientGameTestContext ctx, String typed) {
        AtomicReference<List<String>> out = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var conn = mc.getConnection();
            var dispatcher = conn.getCommands();
            var parse = dispatcher.parse(typed, conn.getSuggestionsProvider());
            dispatcher.getCompletionSuggestions(parse).thenAccept(s -> {
                List<String> l = new ArrayList<>();
                for (var one : s.getList()) {
                    l.add(one.getText());
                }
                out.set(l);
            });
        });
        ctx.waitFor(mc -> out.get() != null, 200);
        return out.get();
    }

    /** /reset typed as he types it; waits for the rebuild and his landing, checks the chat line, and reads the room. */
    private Snapshot reset(ClientGameTestContext ctx, String expectChat, AABB box) {
        return reset(ctx, expectChat, box, "reset");
    }

    /** {@code command} is "reset" or "reload" (killer560, 2026-10-16: "have /reset and /reload both work"). */
    private Snapshot reset(ClientGameTestContext ctx, String expectChat, AABB box, String command) {
        long before = Scenario.simBuildCount(ctx);
        long mark = LogTap.mark();
        ctx.runOnClient(mc -> mc.player.connection.sendCommand(command));
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitFor(mc -> McCompat.screen(mc) == null, 1200);
        waitStanding(ctx, "the reset room");
        ctx.waitTicks(30);
        for (int i = 0; i < 40 && !chatSince(mark, expectChat); i++) {
            ctx.waitTicks(1);
        }
        check(chatSince(mark, expectChat), "/reset did not say \"" + expectChat + "\"");
        return snapshot(ctx, box);
    }

    private static void checkSpawn(Snapshot after, Snapshot base, int round) {
        double d = after.distanceTo(base);
        float dyaw = Math.abs(((after.yaw - base.yaw) % 360f + 540f) % 360f - 180f);
        float dpitch = Math.abs(after.pitch - base.pitch);
        check(d < 0.05, String.format(Locale.US, "/reset #%d put him %.3f block(s) from the spawn (%s, spawn %s)",
                round, d, after.describePos(), base.describePos()));
        check(dyaw < 0.5f && dpitch < 0.5f, String.format(Locale.US,
                "/reset #%d faced him yaw %.2f pitch %.2f, the load's spawn faced %.2f / %.2f", round, after.yaw,
                after.pitch, base.yaw, base.pitch));
    }

    private static boolean chatSince(long mark, String text) {
        for (String l : LogTap.since(mark)) {
            if (l.contains("[CHAT]") && l.contains(text)) {
                return true;
            }
        }
        return false;
    }

    /** One room on its own, as the picker loads it, and waits until he stands in it. */
    private static void build(ClientGameTestContext ctx, String room) {
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> ModUnderTest.staticCall(BUILDER, "buildSingleRoom",
                new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, room})));
        ctx.waitFor(mc -> mc.level != null && mc.player != null, 2400);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitFor(mc -> McCompat.screen(mc) == null, 1200);
        waitStanding(ctx, room);
        ctx.waitTicks(40);
        playerId = ctx.computeOnClient(mc -> mc.player.getUUID());
    }

    private static void waitStanding(ClientGameTestContext ctx, String what) {
        for (int t = 0; t < 1800; t++) {
            boolean ok = ctx.computeOnClient(mc -> mc.player != null && mc.player.onGround()
                    && !mc.level.getBlockState(mc.player.blockPosition().below()).isAir());
            if (ok) {
                return;
            }
            ctx.waitTicks(1);
        }
        throw new AssertionError("the client never stood in " + what);
    }

    /** The room as it stands: position, facing, the box's blocks, entities and secret bookkeeping. */
    private static final class Snapshot {
        double x;
        double y;
        double z;
        float yaw;
        float pitch;
        AABB box;
        int[] blocks;
        Map<String, Integer> entities;
        int itemsPending;
        int batsPending;
        List<BlockPos> essences;
        List<BlockPos> chests;
        int secrets;
        int generation;

        String describePos() {
            return String.format(Locale.US, "%.2f %.2f %.2f yaw %.2f pitch %.2f", x, y, z, yaw, pitch);
        }

        double distanceTo(Snapshot o) {
            return Math.sqrt((x - o.x) * (x - o.x) + (y - o.y) * (y - o.y) + (z - o.z) * (z - o.z));
        }

        Snapshot withGeneration(int g) {
            generation = g;
            return this;
        }
    }

    @SuppressWarnings("unchecked")
    private static Snapshot snapshot(ClientGameTestContext ctx) {
        return snapshot(ctx, null);
    }

    /** As {@link #snapshot(ClientGameTestContext)}, over a fixed box (the load's), so two reads compare cell for cell. */
    @SuppressWarnings("unchecked")
    private static Snapshot snapshot(ClientGameTestContext ctx, AABB fixed) {
        Snapshot s = new Snapshot();
        int[] bounds = (int[]) ModUnderTest.staticCall(BUILD_QUEUE, "touchedBounds", new Class<?>[]{}, new Object[]{});
        check(bounds != null, "the sim recorded no build bounds");
        int minY = (Integer) ModUnderTest.staticCall(ALTITUDE, "minWorldY");
        int maxY = (Integer) ModUnderTest.staticCall(ALTITUDE, "maxWorldY");
        s.box = fixed != null ? fixed : new AABB(bounds[0], minY, bounds[1], bounds[2] + 1, maxY + 1, bounds[3] + 1);
        s.blocks = snapshotBlocks(ctx, s.box);
        s.entities = entities(ctx, s.box);
        Object[] p = onServer(ctx, (level, sp) -> new Object[]{sp.getX(), sp.getY(), sp.getZ(), sp.getYRot(), sp.getXRot()});
        s.x = (Double) p[0];
        s.y = (Double) p[1];
        s.z = (Double) p[2];
        s.yaw = (Float) p[3];
        s.pitch = (Float) p[4];
        s.itemsPending = (Integer) ModUnderTest.staticCall(SIM_SECRET_ITEMS, "pendingCount");
        s.batsPending = ((List<BlockPos>) ModUnderTest.staticCall(SIM_SECRET_ITEMS, "pendingBats")).size();
        s.essences = sorted(staticField(SIM_SECRETS, "PLACED_WITHER"));
        s.chests = sorted(staticField(SIM_SECRETS, "PLACED_CHESTS"));
        s.secrets = secrets(ctx);
        s.generation = (Integer) ModUnderTest.staticCall(LIVE_MAP, "resetGeneration");
        return s;
    }

    private static List<BlockPos> sorted(Object collection) {
        List<BlockPos> l = new ArrayList<>((java.util.Collection<BlockPos>) collection);
        l.sort(java.util.Comparator.comparingLong(BlockPos::asLong));
        return l;
    }

    private static Object staticField(String cls, String field) {
        try {
            var f = Class.forName(cls).getDeclaredField(field);
            f.setAccessible(true);
            return f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("no field " + cls + "." + field, e);
        }
    }

    /** Every block state id in the box, read on the server (loading chunks if it must). */
    private static int[] snapshotBlocks(ClientGameTestContext ctx, AABB box) {
        return onServer(ctx, (level, sp) -> {
            int x0 = (int) box.minX;
            int y0 = (int) box.minY;
            int z0 = (int) box.minZ;
            int nx = (int) box.maxX - x0;
            int ny = (int) box.maxY - y0;
            int nz = (int) box.maxZ - z0;
            int[] out = new int[nx * ny * nz];
            var pos = new BlockPos.MutableBlockPos();
            int i = 0;
            for (int x = 0; x < nx; x++) {
                for (int z = 0; z < nz; z++) {
                    for (int y = 0; y < ny; y++) {
                        pos.set(x0 + x, y0 + y, z0 + z);
                        out[i++] = Block.getId(level.getBlockState(pos));
                    }
                }
            }
            return out;
        });
    }

    private static int diff(int[] a, int[] b) {
        if (a.length != b.length) {
            return Math.max(a.length, b.length);
        }
        int n = 0;
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                n++;
            }
        }
        return n;
    }

    private static String firstDiffs(Snapshot base, Snapshot after, int max) {
        List<String> out = new ArrayList<>();
        int x0 = (int) base.box.minX;
        int y0 = (int) base.box.minY;
        int z0 = (int) base.box.minZ;
        int ny = (int) base.box.maxY - y0;
        int nz = (int) base.box.maxZ - z0;
        for (int i = 0; i < base.blocks.length && out.size() < max; i++) {
            if (base.blocks[i] != after.blocks[i]) {
                int y = i % ny;
                int z = (i / ny) % nz;
                int x = i / (ny * nz);
                out.add((x0 + x) + "," + (y0 + y) + "," + (z0 + z) + " " + Block.stateById(base.blocks[i]) + " -> "
                        + Block.stateById(after.blocks[i]));
            }
        }
        return String.join("; ", out);
    }

    /** Non-player entities in the box by type, read on the server. */
    private static Map<String, Integer> entities(ClientGameTestContext ctx, AABB box) {
        return onServer(ctx, (level, sp) -> {
            Map<String, Integer> m = new TreeMap<>();
            for (Entity e : level.getEntities((Entity) null, box)) {
                if (e instanceof net.minecraft.world.entity.player.Player) {
                    continue;
                }
                m.merge(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath(), 1,
                        Integer::sum);
            }
            return m;
        });
    }

    private static int secrets(ClientGameTestContext ctx) {
        return (Integer) ModUnderTest.staticCall(SIM_SCORE, "secretsFound");
    }

    /** The first Creeper Beams pair's two lanterns, off the puzzle's own bound arena. */
    private static BlockPos[] firstPair() {
        Object pairs = staticField(CREEPER, "worldPairs");
        List<?> l = (List<?>) pairs;
        check(!l.isEmpty(), "Creeper Beams has no pairs bound");
        try {
            Object p = l.get(0);
            var a = p.getClass().getDeclaredMethod("a");
            var b = p.getClass().getDeclaredMethod("b");
            a.setAccessible(true);
            b.setAccessible(true);
            return new BlockPos[]{(BlockPos) a.invoke(p), (BlockPos) b.invoke(p)};
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * A 3x3 of wall knocked out on the server: the first solid column walking +x (then the other three ways) from the
     * spawn at his feet height, three wide and three high - what a Superboom or the breaker leaves.
     */
    private static List<BlockPos> knockOutWall(ClientGameTestContext ctx, Snapshot base) {
        BlockPos feet = BlockPos.containing(base.x, base.y, base.z);
        List<BlockPos> wall = onServer(ctx, (level, sp) -> {
            int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] d : dirs) {
                for (int step = 1; step <= 20; step++) {
                    BlockPos p = feet.offset(d[0] * step, 0, d[1] * step);
                    if (!level.getBlockState(p).isAir() && !level.getBlockState(p.above()).isAir()) {
                        List<BlockPos> out = new ArrayList<>();
                        for (int side = -1; side <= 1; side++) {
                            for (int up = 0; up <= 2; up++) {
                                BlockPos q = p.offset(d[1] * side, up, d[0] * side);
                                if (!level.getBlockState(q).isAir()) {
                                    out.add(q.immutable());
                                }
                            }
                        }
                        for (BlockPos q : out) {
                            level.setBlock(q, Blocks.AIR.defaultBlockState(), 3);
                        }
                        return out;
                    }
                }
            }
            return List.of();
        });
        say("knocked out " + wall.size() + " wall block(s) from " + (wall.isEmpty() ? "-" : wall.get(0).toShortString()));
        check(wall.size() >= 4, "found no wall to knock out near the spawn");
        return wall;
    }

    private static int countNotAir(ClientGameTestContext ctx, List<BlockPos> ps) {
        return onServer(ctx, (level, sp) -> {
            int n = 0;
            for (BlockPos p : ps) {
                if (!level.getBlockState(p).isAir()) {
                    n++;
                }
            }
            return n;
        });
    }

    /**
     * Stands him on a free spot within reach of {@code target}: feet and head clear, something solid underfoot,
     * nearest first.
     */
    private static void standNear(ClientGameTestContext ctx, BlockPos target) {
        BlockPos spot = onServer(ctx, (level, sp) -> {
            BlockPos best = null;
            double bestD = Double.MAX_VALUE;
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    for (int dy = -3; dy <= 2; dy++) {
                        BlockPos f = target.offset(dx, dy, dz);
                        if (f.equals(target) || f.above().equals(target)) {
                            continue;
                        }
                        if (!level.getBlockState(f).getCollisionShape(level, f).isEmpty()
                                || !level.getBlockState(f.above()).getCollisionShape(level, f.above()).isEmpty()
                                || level.getBlockState(f.below()).getCollisionShape(level, f.below()).isEmpty()) {
                            continue;
                        }
                        Vec3 eye = new Vec3(f.getX() + 0.5, f.getY() + 1.62, f.getZ() + 0.5);
                        double d = eye.distanceTo(Vec3.atCenterOf(target));
                        if (d < 4.0 && d < bestD) {
                            bestD = d;
                            best = f;
                        }
                    }
                }
            }
            return best;
        });
        check(spot != null, "no standing spot within reach of " + target.toShortString());
        Vec3 eye = new Vec3(spot.getX() + 0.5, spot.getY() + 1.62, spot.getZ() + 0.5);
        Vec3 to = Vec3.atCenterOf(target).subtract(eye);
        float yaw = (float) Math.toDegrees(Math.atan2(-to.x, to.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z)));
        tp(ctx, spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, yaw, pitch);
        ctx.waitTicks(10);
    }

    /** A real right click on the block, through the client's game mode (the use packet the sim answers). */
    private static void click(ClientGameTestContext ctx, BlockPos pos) {
        ctx.runOnClient(mc -> mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false)));
        ctx.waitTicks(10);
    }

    private static void closeScreen(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            if (McCompat.screen(mc) != null && mc.player != null) {
                mc.player.closeContainer();
            }
        });
        ctx.waitTicks(5);
    }

    private static String blockName(ClientGameTestContext ctx, BlockPos p) {
        return onServer(ctx, (level, sp) -> net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(level.getBlockState(p).getBlock()).getPath());
    }

    /** Server teleport with this yaw/pitch, the client turned the same way. */
    private static void tp(ClientGameTestContext ctx, double x, double y, double z, float yaw, float pitch) {
        onServer(ctx, (level, sp) -> {
            sp.teleportTo(level, x, y, z, java.util.Set.<net.minecraft.world.entity.Relative>of(), yaw, pitch, false);
            return null;
        });
        ctx.waitTicks(5);
    }

    /** Runs on the integrated server thread (never a join from here - see the testkit's deadlock note) and returns. */
    @SuppressWarnings("unchecked")
    private static <T> T onServer(ClientGameTestContext ctx, BiFunction<ServerLevel, ServerPlayer, T> f) {
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

    private static void teardown(ClientGameTestContext ctx) {
        playerId = null;
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
