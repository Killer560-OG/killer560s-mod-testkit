package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.harness.SuiteVerdict;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * 641-650: killer560's sim-world list (2026-10-09), each proven on the integrated server - what the sim's SERVER did,
 * read back from the server's own level, never from the mod's own counters alone.
 *
 * <ul>
 *   <li><b>641-sim-pickup</b>: the Magnetic Talisman's 3x pickup range. Dirt is dropped beside him at 0.1-block steps
 *       from 1.0 to 4.0 blocks; the farthest one taken and the nearest one left are printed, and must bracket vanilla's
 *       reach x3 (box inflated 3.0 instead of 1.0: taken up to ~3.4, vanilla's ~1.4). The taken ones must be in his
 *       inventory.</li>
 *   <li><b>642-sim-boom-crypts</b>: ONE Superboom aimed at plain stone between two slab crypts opens both, and a cracked
 *       brick sealing a crypt chamber 3 blocks away; a third slab crypt 6 blocks away and a decorative cracked brick with
 *       no chamber stay; three crypt undead appear.</li>
 *   <li><b>643-sim-bat</b>: a secret bat does not exist while he is far from it, appears (Skyblock health 100) when he
 *       comes near, Auto Routes' await counts that appearance, and killing it counts one secret for the run, the bats
 *       and its room.</li>
 *   <li><b>645-sim-prince-brf</b>: Big Red Flag's tomb is a prince; one Superboom opens it as a prince (no crypt), takes
 *       its lid, slab ring and the course under it, and nothing below that.</li>
 *   <li><b>646-sim-prince-head</b>: Leaves' prince takes its player head and the stair layer under it.</li>
 *   <li><b>647-650-sim-lever-*</b>: Bridges, Raccoon, Logs and Arrow Trap - the lever is there (Logs and Arrow Trap's
 *       placed by the sim) and flicking it as the player takes the whole door away.</li>
 *   <li><b>1406-sim-lever-deathmite, 1407-sim-lever-doors</b> (2026-10-13): stood within reach of each lever, it must
 *       flip on the server, its own door must go entirely and every door whose lever is still off must stay whole
 *       (Doors: four levers, each on a log of its door's wood). Against the jar before the rules: 0 levers armed.</li>
 *   <li><b>1412-sim-lever-cobble, 1414-sim-lever-golden-oasis</b> (2026-10-15): the same, for two levers the sim
 *       PLACES (Cobble Wall Pillar: 12 iron bars and the 12 barriers behind them; Golden Oasis: 32 acacia logs).</li>
 *   <li><b>1415-sim-redstone-key</b>: Golden Oasis' Redstone Key - the key head by Hypixel's profile; the node clicked
 *       with no key does nothing; the key taken (no secret); then the node goes, the three redstone blocks by the lever
 *       go, one secret counts for the room, and the client sees the key on an armor stand at the node.</li>
 *   <li><b>1781-sim-prince-supertall, 1782-sim-prince-supertall-floor, 1783-sim-prince-obsidian-census</b>
 *       (2026-10-16): Supertall's two obsidian tombs are princes, by themselves and on a floor; a Superboom opens
 *       each as a prince; /reset brings both back; and the obsidian signature matches exactly the census's rooms.</li>
 * </ul>
 * The mimic route (644) is {@code SimAutoRoutesTests}' 96-ar-644-mimic-warp, where the route machinery lives.
 * Screenshots of 645-650 go to the report's screens at GUI scale 2.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimWorldTests implements FabricClientGameTest {

    private static final String NAME = "64x-sim-world";
    private static final String[] CASES = {"641-sim-pickup", "642-sim-boom-crypts", "643-sim-bat",
            "644-sim-hype-down", "645-sim-prince-brf", "646-sim-prince-head", "647-sim-lever-bridges", "648-sim-lever-raccoon",
            "649-sim-lever-logs", "650-sim-lever-arrowtrap",
            // sim-levers2 (2026-10-12): flicked as the player, every door block must go (no screenshots)
            "856-sim-lever-redstone-warrior", "857-sim-lever-hallway", "858-sim-lever-knight", "859-sim-lever-atlas",
            "860-sim-lever-long-hall",
            // mimic-fix (2026-10-11)
            "652-sim-prince-waypoint-brf",
            // sim-superboom-radius (2026-10-14): a 3-block radius to each block's box, whole groups
            "1401-sim-boom-prince-tomb", "1402-sim-boom-crypt-overhead", "1403-sim-boom-chain",
            "1404-sim-prince-missed", "1405-sim-item-range",
            // deathmite-lever (2026-10-13): stood in reach, flicked as the player, the lever must flip on the server
            "1406-sim-lever-deathmite", "1407-sim-lever-doors",
            "1408-sim-lever-buttons", "1409-sim-lever-dome",
            "1411-sim-newmap-secrets",
            // sim-levers-1015 (2026-10-15): Cobble Wall Pillar's and Golden Oasis' placed levers, the Redstone Key
            "1412-sim-lever-cobble", "1414-sim-lever-golden-oasis", "1415-sim-redstone-key",
            // sim-prince-supertall-1016: the obsidian prince signature
            "1781-sim-prince-supertall", "1782-sim-prince-supertall-floor", "1783-sim-prince-obsidian-census",
            // sim-secrets-1018: Sarcophagus' prince, one Redstone Key per room, chests that appear like bats, Change Room
            "1018-sim-prince-sarcophagus", "1018-sim-keyspot-one", "1018-sim-chest-appear",
            "1018-sim-changeroom-lastpage"};

    private static final String BUILDER = "com.killer560.hub.roomsim.SimBuilder";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String SIM_ITEMS = "com.killer560.hub.roomsim.SimItems";
    private static final String SIM_PRINCE = "com.killer560.hub.roomsim.SimPrince";
    private static final String MAP_CODE = "com.killer560.hub.roomsim.MapCode";
    private static final String SIM_LEVERS = "com.killer560.hub.roomsim.SimRoomLevers";
    private static final String SIM_KEY = "com.killer560.hub.roomsim.SimRedstoneKey";
    private static final String SIM_SECRET_ITEMS = "com.killer560.hub.roomsim.SimSecretItems";
    private static final String SIM_MOBS = "com.killer560.hub.roomsim.SimMobs";
    private static final String SIM_SCORE = "com.killer560.hub.roomsim.SimScore";
    private static final String AWAIT = "com.killer560.hub.autoroutes.AwaitEvents";
    private static final String AR_CONFIG = "com.killer560.hub.autoroutes.AutoRoutesConfig";

    private static UUID playerId;
    private static String builtRoom;
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
        SimMapTests.copyRoomsForOthers();
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);
        ctx.runOnClient(mc -> mc.options.guiScale().set(2));
        try {
            for (String c : selected) {
                long mark = LogTap.mark();
                try {
                    say("---- " + c + " ----");
                    switch (c) {
                        case "641-sim-pickup" -> casePickup(ctx);
                        case "642-sim-boom-crypts" -> caseBoomCrypts(ctx);
                        case "643-sim-bat" -> caseBat(ctx);
                        case "644-sim-hype-down" -> caseHypeDown(ctx);
                        case "645-sim-prince-brf" -> casePrinceBrf(ctx);
                        case "646-sim-prince-head" -> casePrinceHead(ctx);
                        case "647-sim-lever-bridges" -> caseLever(ctx, c, "Bridges", new int[]{55, 59, 29},
                                new int[]{10, 81, 15}, new int[]{10, 86, 17}, false, new int[]{13, 16}, new int[]{52, 29});
                        case "648-sim-lever-raccoon" -> caseLever(ctx, c, "Raccoon", new int[]{25, 70, 29},
                                new int[]{24, 69, 19}, new int[]{26, 72, 19}, false, new int[]{25, 23}, new int[]{25, 26});
                        case "649-sim-lever-logs" -> caseLever(ctx, c, "Logs", new int[]{29, 84, 6},
                                new int[]{20, 69, 23}, new int[]{21, 72, 26}, true, new int[]{25, 24}, new int[]{29, 9});
                        case "650-sim-lever-arrowtrap" -> caseLever(ctx, c, "Arrow Trap", new int[]{16, 70, 24},
                                new int[]{14, 69, 8}, new int[]{18, 73, 8}, true, new int[]{16, 12}, new int[]{16, 21});
                        case "856-sim-lever-redstone-warrior" -> caseLever(ctx, c, "Redstone Warrior",
                                new int[]{16, 62, 5}, new int[]{15, 58, 27}, new int[]{17, 62, 27}, false, null, null);
                        case "857-sim-lever-hallway" -> caseLever(ctx, c, "Hallway",
                                new int[]{11, 76, 93}, new int[]{28, 69, 73}, new int[]{29, 72, 74}, false, null, null);
                        case "858-sim-lever-knight" -> caseLever(ctx, c, "Knight",
                                new int[]{4, 70, 10}, new int[]{24, 69, 21}, new int[]{27, 72, 22}, false, null, null);
                        case "859-sim-lever-atlas" -> caseLever(ctx, c, "Atlas",
                                new int[]{54, 67, 32}, new int[]{7, 80, 25}, new int[]{9, 83, 26}, false, null, null);
                        case "860-sim-lever-long-hall" -> caseLever(ctx, c, "Long Hall",
                                // 2026-10-16: the 3 x 5 wood panel, not the channel floor ("this lever in Long
                                // Hall drops this door").
                                new int[]{21, 70, 28}, new int[]{20, 84, 11}, new int[]{22, 88, 11}, false, null, null);
                        case "652-sim-prince-waypoint-brf" -> casePrinceWaypoint(ctx);
                        case "1401-sim-boom-prince-tomb" -> caseBoomPrinceTomb(ctx);
                        case "1402-sim-boom-crypt-overhead" -> caseBoomCryptOverhead(ctx);
                        case "1403-sim-boom-chain" -> caseBoomChain(ctx);
                        case "1404-sim-prince-missed" -> casePrinceMissed(ctx);
                        case "1405-sim-item-range" -> caseItemRange(ctx);
                        case "1406-sim-lever-deathmite" -> caseLeverDoors(ctx, c, "Deathmite", new int[][][]{
                                {{54, 70, 16}, {28, 65, 15}, {28, 70, 17}, {53, 16}}});
                        // Each lever on a log of one wood opens that wood's door; the two he asked about (dark oak
                        // 44 and jungle 46) are flicked first, each leaving the other's door whole.
                        case "1411-sim-newmap-secrets" -> caseNewMapSecrets(ctx);
                        case "1408-sim-lever-buttons" -> caseLeverDoors(ctx, c, "Buttons", new int[][][]{
                                {{43, 81, 58}, {56, 80, 16}, {58, 88, 16}, {42, 59}}});
                        case "1409-sim-lever-dome" -> caseLeverDoors(ctx, c, "Dome", new int[][][]{
                                {{22, 75, 7}, {21, 77, 4}, {22, 81, 6}, {23, 7}}});
                        case "1407-sim-lever-doors" -> caseLeverDoors(ctx, c, "Doors", new int[][][]{
                                {{44, 59, 16}, {24, 58, 15}, {24, 64, 17}, {44, 15}},
                                {{46, 59, 16}, {20, 58, 15}, {20, 64, 17}, {46, 15}},
                                {{62, 59, 16}, {51, 58, 15}, {51, 63, 17}, {61, 16}},
                                {{51, 59, 2}, {40, 58, 15}, {40, 64, 17}, {51, 3}}});
                        // Placed floor lever in the nook beside the captured redstone torch; 12 bars + 12 barriers.
                        case "1412-sim-lever-cobble" -> caseLeverDoors(ctx, c, "Cobble Wall Pillar", new int[][][]{
                                {{8, 69, 21}, {18, 60, 27}, {20, 63, 28}, {9, 20}}});
                        // Placed floor lever on the acacia wood; the 32-block acacia log wall.
                        case "1414-sim-lever-golden-oasis" -> caseLeverDoors(ctx, c, "Golden Oasis", new int[][][]{
                                {{30, 71, 26}, {5, 69, 11}, {8, 72, 12}, {29, 26}}});
                        case "1415-sim-redstone-key" -> caseRedstoneKey(ctx, c);
                        case "1781-sim-prince-supertall" -> casePrinceSupertall(ctx);
                        case "1782-sim-prince-supertall-floor" -> casePrinceSupertallFloor(ctx);
                        case "1783-sim-prince-obsidian-census" -> casePrinceObsidianCensus(ctx);
                        case "1018-sim-prince-sarcophagus" -> SimSecrets1018.princeSarcophagus(ctx);
                        case "1018-sim-keyspot-one" -> SimSecrets1018.keySpotOne(ctx);
                        case "1018-sim-chest-appear" -> SimSecrets1018.chestAppear(ctx);
                        case "1018-sim-changeroom-lastpage" -> SimSecrets1018.changeRoomLastPage(ctx);
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
            teardown(ctx);
        }
        System.out.println("[" + NAME + "] SUMMARY");
        for (String r : RESULTS) {
            System.out.println("[" + NAME + "]   " + r);
        }
    }

    // ============================================================================================ 641

    private void casePickup(ClientGameTestContext ctx) {
        build(ctx, "Mossy");
        BlockPos feet = arena(ctx);
        double px = feet.getX() + 0.5;
        double py = feet.getY();
        double pz = feet.getZ() + 0.5;
        double lastTaken = -1;
        double firstLeft = -1;
        int taken = 0;
        StringBuilder row = new StringBuilder();
        int dirtBefore = onServer(ctx, (level, sp) -> sp.getInventory().countItem(Items.DIRT));
        for (int i = 10; i <= 40; i++) {
            double d = i / 10.0;
            // Along +x, at his own height: one drop at a time, so each answer is about that distance only.
            AtomicReference<UUID> id = new AtomicReference<>();
            onServer(ctx, (level, sp) -> {
                ItemEntity e = new ItemEntity(level, px + d, py + 0.3, pz, new ItemStack(Items.DIRT));
                e.setNoGravity(true);
                e.setDeltaMovement(Vec3.ZERO);
                e.setPickUpDelay(0);
                level.addFreshEntity(e);
                id.set(e.getUUID());
                return null;
            });
            ctx.waitTicks(4);
            boolean gone = onServer(ctx, (level, sp) -> {
                var e = level.getEntity(id.get());
                boolean removed = e == null || e.isRemoved();
                if (!removed) {
                    e.discard();
                }
                return removed;
            });
            row.append(String.format(Locale.US, " %.1f:%s", d, gone ? "T" : "-"));
            if (gone) {
                taken++;
                lastTaken = d;
            } else if (firstLeft < 0) {
                firstLeft = d;
            }
        }
        int dirtAfter = onServer(ctx, (level, sp) -> sp.getInventory().countItem(Items.DIRT));
        say(String.format(Locale.US, "641 pickup by distance (T taken):%s", row));
        say(String.format(Locale.US, "641 farthest taken %.1f, nearest left %.1f, %d taken, dirt in inventory %d -> %d",
                lastTaken, firstLeft, taken, dirtBefore, dirtAfter));
        check(dirtAfter - dirtBefore == taken, "taken drops did not reach his inventory (" + taken + " taken, "
                + (dirtAfter - dirtBefore) + " in it)");
        // Vanilla: the player box (0.3 half width) inflated 1.0 touches an item box (0.125 half width) up to 1.425 out.
        // Three times the range: inflated 3.0, so up to 3.425.
        check(lastTaken >= 3.3 && lastTaken <= 3.5, "the farthest drop taken was at " + lastTaken + ", expected 3.3-3.4");
        check(firstLeft >= 3.4 && firstLeft <= 3.5, "the nearest drop left was at " + firstLeft + ", expected 3.4-3.5");
        check(taken == (int) Math.round((lastTaken - 1.0) * 10) + 1, "the pickup was not every drop up to "
                + lastTaken + " (" + taken + " taken)");
    }

    // ============================================================================================ 642

    private void caseBoomCrypts(ClientGameTestContext ctx) {
        build(ctx, "Mossy");
        BlockPos feet = arena(ctx);
        int fy = feet.getY() - 1;
        BlockPos t = new BlockPos(feet.getX(), fy, feet.getZ() + 6);
        List<BlockPos> lidA = lid(t.getX() - 3, fy, t.getZ());
        List<BlockPos> lidB = lid(t.getX() + 2, fy, t.getZ());
        List<BlockPos> lidC = lid(t.getX() + 6, fy, t.getZ());
        BlockPos wall = new BlockPos(t.getX(), fy, t.getZ() + 3);
        BlockPos deco = new BlockPos(t.getX(), fy, t.getZ() - 2);
        onServer(ctx, (level, sp) -> {
            BlockState slab = Blocks.SMOOTH_STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);
            for (List<BlockPos> lid : List.of(lidA, lidB, lidC)) {
                for (BlockPos p : lid) {
                    level.setBlockAndUpdate(p, slab);
                    level.setBlockAndUpdate(p.below(), Blocks.AIR.defaultBlockState());   // the chamber
                }
            }
            level.setBlockAndUpdate(wall, Blocks.CRACKED_STONE_BRICKS.defaultBlockState());
            level.setBlockAndUpdate(wall.below(), Blocks.AIR.defaultBlockState());          // a one-block chamber
            level.setBlockAndUpdate(deco, Blocks.CRACKED_STONE_BRICKS.defaultBlockState()); // stone under it
            return null;
        });
        ctx.waitTicks(5);
        int zombiesBefore = zombies(ctx, t, 10);
        long m = LogTap.mark();
        onServer(ctx, (level, sp) -> ModUnderTest.staticCall(SIM_ITEMS, "superboom",
                new Class<?>[]{ServerPlayer.class, BlockPos.class, Direction.class}, new Object[]{sp, t, Direction.UP}));
        ctx.waitTicks(10);
        int a = countIs(ctx, lidA, Blocks.SMOOTH_STONE_SLAB);
        int b = countIs(ctx, lidB, Blocks.SMOOTH_STONE_SLAB);
        int c = countIs(ctx, lidC, Blocks.SMOOTH_STONE_SLAB);
        boolean wallGone = onServer(ctx, (level, sp) -> level.getBlockState(wall).isAir());
        // In reach, so it goes too since sim-superboom-radius (killer560 2026-10-14: cracked stone within 3 blocks
        // breaks), but with stone under it there is no chamber and no undead.
        boolean decoGone = onServer(ctx, (level, sp) -> level.getBlockState(deco).isAir());
        int zombiesAfter = zombies(ctx, t, 10);
        say(String.format(Locale.US, "642 one superboom at %s: slabs left A %d/6, B %d/6 (both 3 away), C %d/6 (6 away); "
                        + "crypt wall 3 away gone %s; decorative cracked brick 2 away gone %s; crypt undead %d -> %d",
                t.toShortString(), a, b, c, wallGone, decoGone, zombiesBefore, zombiesAfter));
        for (String l : LogTap.since(m)) {
            if (l.contains("Sim superboom")) {
                say("642 log: " + l);
            }
        }
        check(a == 0 && b == 0, "one superboom did not open BOTH crypts in reach (A " + a + ", B " + b + " slabs left)");
        check(c == 6, "the crypt 6 blocks away lost " + (6 - c) + " slab(s) - the reach is not bounded");
        check(wallGone, "the cracked brick sealing a crypt chamber 3 blocks away was not broken");
        check(decoGone, "a decorative cracked brick (no chamber) in reach was not broken");
        check(zombiesAfter - zombiesBefore == 3, "expected 3 crypt undead (A, B, the wall), got "
                + (zombiesAfter - zombiesBefore));
    }

    private static List<BlockPos> lid(int x0, int y, int zc) {
        List<BlockPos> out = new ArrayList<>();
        for (int x = x0; x <= x0 + 1; x++) {
            for (int z = zc - 1; z <= zc + 1; z++) {
                out.add(new BlockPos(x, y, z));
            }
        }
        return out;
    }

    private static int zombies(ClientGameTestContext ctx, BlockPos at, int r) {
        return onServer(ctx, (level, sp) -> level.getEntitiesOfClass(Zombie.class, new AABB(at).inflate(r)).size());
    }

    /**
     * killer560 (2026-10-13): "make sure the princes and the crypts spawn on the floor once they explode". Every zombie
     * within {@code r} of {@code at} whose feet are not on the top of the collision box below them, as "x,y,z" lines
     * (empty when all stand on something); the count checked goes into {@code n[0]}.
     */
    private static List<String> floating(ClientGameTestContext ctx, BlockPos at, int r, int[] n) {
        return onServer(ctx, (level, sp) -> {
            List<String> out = new ArrayList<>();
            for (Zombie z : level.getEntitiesOfClass(Zombie.class, new AABB(at).inflate(r))) {
                n[0]++;
                BlockPos under = BlockPos.containing(z.getX(), z.getY() - 0.01, z.getZ());
                var shape = level.getBlockState(under).getCollisionShape(level, under);
                double top = shape.isEmpty() ? Double.NaN : under.getY() + shape.max(Direction.Axis.Y);
                if (!(Math.abs(top - z.getY()) < 0.01)) {
                    out.add(String.format(Locale.US, "%.2f,%.2f,%.2f (under %s)", z.getX(), z.getY(), z.getZ(),
                            net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(under)
                                    .getBlock()).getPath()));
                }
            }
            return out;
        });
    }

    // ============================================================================================ 643

    private void caseBat(ClientGameTestContext ctx) {
        build(ctx, "Mossy");
        BlockPos feet = arena(ctx);
        BlockPos spot = feet.offset(0, 2, 7);
        String room = ctx.computeOnClient(mc -> (String) ModUnderTest.staticCall(SIM_SCORE, "roomAt",
                new Class<?>[]{BlockPos.class}, new Object[]{spot}));
        check(room != null, "no room at the bat's spot " + spot.toShortString());
        // Far away first: 9 blocks back, 16 from the spot.
        tp(ctx, feet.getX() + 0.5, feet.getY(), feet.getZ() - 8.5, 0f, 0f);
        onServer(ctx, (level, sp) -> ModUnderTest.staticCall(SIM_SECRET_ITEMS, "registerBat",
                new Class<?>[]{BlockPos.class}, new Object[]{spot}));
        ctx.waitTicks(20);
        int aliveFar = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(SIM_MOBS, "secretBatsAlive"));
        int batsNear = onServer(ctx, (level, sp) -> level.getEntitiesOfClass(Bat.class, new AABB(spot).inflate(3)).size());
        say("643 far (16 blocks): secret bats alive " + aliveFar + ", bats at the spot " + batsNear);
        check(aliveFar == 0 && batsNear == 0, "the secret bat existed while he was 16 blocks from it");

        // An await is open (as a running route's would be), then he walks in.
        ctx.runOnClient(mc -> {
            ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setEnabled", true);
            ModUnderTest.staticCall(AWAIT, "begin", new Class<?>[]{Minecraft.class}, new Object[]{mc});
            // A fresh window: begin() also counts his own secrets from just before it (here, 641's dirt), which is not
            // what this case measures.
            ModUnderTest.staticCall(AWAIT, "window", new Class<?>[]{Minecraft.class}, new Object[]{mc});
        });
        int found0 = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(SIM_SCORE, "secretsFound"));
        int bats0 = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(SIM_SCORE, "batsKilled"));
        int inRoom0 = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(SIM_SCORE, "foundInRoom",
                new Class<?>[]{String.class}, new Object[]{room}));
        long m = LogTap.mark();
        // killer560 (2026-10-13): a sim bat spawns only after he has been within its range for 5 ticks. Teleported in on
        // the server (game time t0), then polled every tick: the bat may exist only from t0 + 5 on.
        long t0 = onServer(ctx, (level, sp) -> {
            sp.teleportTo(level, feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5,
                    java.util.Set.<net.minecraft.world.entity.Relative>of(), 0f, 0f, false);
            return level.getGameTime();
        });
        long spawnedAt = -1;
        for (int i = 0; i < 40 && spawnedAt < 0; i++) {
            ctx.waitTicks(1);
            spawnedAt = onServer(ctx, (level, sp) -> {
                @SuppressWarnings("unchecked")
                List<BlockPos> pend = (List<BlockPos>) ModUnderTest.staticCall(SIM_SECRET_ITEMS, "pendingBats");
                return pend.contains(spot) ? -1L : level.getGameTime();
            });
        }
        // The poll that saw it gone ran up to a tick after the spawn, so the spawn tick is at least spawnedAt - 1.
        say("643 bat spawned " + (spawnedAt - t0) + " server tick(s) after he came within range (seen on the poll)");
        check(spawnedAt >= 0, "the secret bat never spawned once he was within range");
        check(spawnedAt - t0 >= 5, "the secret bat spawned " + (spawnedAt - t0) + " tick(s) after he came within range - want 5");
        check(spawnedAt - t0 <= 7, "the secret bat took " + (spawnedAt - t0) + " ticks to spawn within range - want 5");
        ctx.runOnClient(mc -> {
            mc.player.setYRot(0f);
            mc.player.setXRot(0f);
        });
        boolean appeared = false;
        for (int i = 0; i < 40 && !appeared; i++) {
            ctx.waitTicks(1);
            appeared = ctx.computeOnClient(mc -> {
                for (var e : mc.level.entitiesForRendering()) {
                    if (e instanceof Bat bat && bat.getMaxHealth() == 100f && bat.blockPosition().closerThan(spot, 2)) {
                        return true;
                    }
                }
                return false;
            });
        }
        ctx.waitTicks(5);
        int awaitSecrets = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(AWAIT, "secrets", new Class<?>[]{}, new Object[]{}));
        boolean awaitLine = false;
        for (String l : LogTap.since(m)) {
            awaitLine |= l.contains("bat spawned at");
        }
        say("643 near (7 blocks): the client sees a bat with Skyblock health 100 at the spot " + appeared
                + "; await secrets counted " + awaitSecrets);
        check(appeared, "no secret bat (max health 100) appeared on the client when he came within 7 blocks");
        // Auto Routes is a cheat-build feature: in a legit jar nothing ticks its await, so only the sim half is asserted.
        boolean cheat;
        try {
            cheat = Class.forName("com.killer560.hub.BuildVariant").getField("CHEAT_FEATURES_ENABLED").getBoolean(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        if (cheat) {
            check(awaitSecrets == 1, "Auto Routes' await counted " + awaitSecrets + " secret(s) for the bat, expected 1");
        } else {
            say("643: legit jar - Auto Routes' await not asserted (cheat-only feature)");
        }

        // Killed by him: one secret, for the run, the bats and the room.
        onServer(ctx, (level, sp) -> {
            for (Bat bat : level.getEntitiesOfClass(Bat.class, new AABB(spot).inflate(3))) {
                bat.hurtServer(level, level.damageSources().playerAttack(sp), 1000f);
            }
            return null;
        });
        ctx.waitTicks(10);
        int found1 = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(SIM_SCORE, "secretsFound"));
        int bats1 = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(SIM_SCORE, "batsKilled"));
        int inRoom1 = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(SIM_SCORE, "foundInRoom",
                new Class<?>[]{String.class}, new Object[]{room}));
        say(String.format(Locale.US, "643 killed: run secrets %d -> %d, bats %d -> %d, %s's secrets %d -> %d", found0,
                found1, bats0, bats1, room, inRoom0, inRoom1));
        ctx.runOnClient(mc -> ModUnderTest.staticCall(AWAIT, "end", new Class<?>[]{}, new Object[]{}));
        check(found1 - found0 == 1, "the bat's death did not count one secret for the run");
        check(bats1 - bats0 == 1, "the bat's death did not count as a bat");
        check(inRoom1 - inRoom0 == 1, "the bat's death did not count for its room (the x/y Secrets line)");
    }

    // ============================================================================================ 644

    /**
     * Kill Mimic's Hyperion straight down, by the real use packet, from an OFF-CENTRE spot: the blast kills a mob two
     * blocks away and he does not move. Before the fix the sim's dash "settled" on his own block and snapped him to its
     * centre (up to 0.7 blocks sideways), which then moved Auto Routes' next etherwarp origin.
     */
    private void caseHypeDown(ClientGameTestContext ctx) {
        build(ctx, "Mossy");
        BlockPos feet = arena(ctx);
        onServer(ctx, (level, sp) -> {
            sp.getInventory().setItem(0, (ItemStack) ModUnderTest.staticCall(SIM_ITEMS, "build",
                    new Class<?>[]{String.class}, new Object[]{"HYPERION"}));
            return null;
        });
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
        tp(ctx, feet.getX() + 0.2, feet.getY(), feet.getZ() + 0.8, 0f, 90f);
        BlockPos mob = feet.offset(2, 0, 0);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_MOBS, "spawn",
                new Class<?>[]{Minecraft.class, BlockPos.class, enumClass(SIM_MOBS + "$Kind")},
                new Object[]{mc, mob, ModUnderTest.enumValue(SIM_MOBS + "$Kind", "ZOMBIE")}));
        ctx.waitTicks(20);
        int before = zombies(ctx, mob, 2);
        Vec3 from = onServer(ctx, (level, sp) -> sp.position());
        ctx.runOnClient(mc -> {
            mc.player.setXRot(90f);
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        });
        ctx.waitTicks(20);
        Vec3 to = onServer(ctx, (level, sp) -> sp.position());
        int alive = onServer(ctx, (level, sp) -> {
            int n = 0;
            for (Zombie z : level.getEntitiesOfClass(Zombie.class, new AABB(mob).inflate(2))) {
                if (z.isAlive()) {
                    n++;
                }
            }
            return n;
        });
        say(String.format(Locale.US, "644 hype straight down from (%.2f, %.2f, %.2f): now (%.2f, %.2f, %.2f), moved %.3f; "
                + "zombies 2 away %d -> %d alive", from.x, from.y, from.z, to.x, to.y, to.z, from.distanceTo(to), before, alive));
        check(before >= 1, "no zombie to blast - the case proves nothing");
        check(alive == 0, "the straight-down Wither Impact did not kill the mob 2 blocks away");
        check(from.distanceTo(to) < 0.05, String.format(Locale.US,
                "a straight-down Hyperion moved him %.3f blocks (to his block's center)", from.distanceTo(to)));
    }

    private static Class<?> enumClass(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    // ============================================================================================ 645 / 646

    private void casePrinceBrf(ClientGameTestContext ctx) {
        String room = "Big Red Flag";
        build(ctx, room);
        BlockPos lid = capW(ctx, room, 14, 72, 24);
        int princes = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(SIM_PRINCE, "count"));
        boolean part = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SIM_PRINCE, "isPart",
                new Class<?>[]{BlockPos.class}, new Object[]{lid}));
        say("645 " + room + ": " + princes + " prince(s); the tomb's lid at " + lid.toShortString() + " is one " + part
                + " (" + blockName(ctx, lid) + ")");
        check(princes == 1 && part, "Big Red Flag's tomb is not the floor's prince");
        stand(ctx, room, 14, 18, 70, new double[]{14.5, 72, 24.5});
        shot(ctx, "645-brf-prince-before");
        List<BlockPos> lidAndRing = new ArrayList<>();
        List<BlockPos> under = new ArrayList<>();
        for (int x = 13; x <= 16; x++) {
            for (int z = 22; z <= 26; z++) {
                // The lid (x 14-15, z 23-25) and its whole slab ring round it, corners included.
                lidAndRing.add(capW(ctx, room, x, 72, z));
                under.add(capW(ctx, room, x, 71, z));
            }
        }
        BlockPos below = capW(ctx, room, 13, 70, 23);
        BlockPos overhang = capW(ctx, room, 12, 71, 24);
        String belowBefore = blockName(ctx, below);
        String overhangBefore = blockName(ctx, overhang);
        long m = LogTap.mark();
        onServer(ctx, (level, sp) -> ModUnderTest.staticCall(SIM_ITEMS, "superboom",
                new Class<?>[]{ServerPlayer.class, BlockPos.class, Direction.class}, new Object[]{sp, lid, Direction.UP}));
        ctx.waitTicks(10);
        int lidLeft = countNotAir(ctx, lidAndRing);
        int underLeft = countNotAir(ctx, under);
        String belowAfter = blockName(ctx, below);
        String overhangAfter = blockName(ctx, overhang);
        int undead = zombies(ctx, lid, 3);
        String boomLine = "";
        for (String l : LogTap.since(m)) {
            if (l.contains("Sim superboom")) {
                boomLine = l;
            }
        }
        shot(ctx, "645-brf-prince-after");
        say(String.format(Locale.US, "645 after one superboom: lid+ring left %d/%d, layer under left %d/%d, below that %s -> %s, "
                        + "overhang outside %s -> %s, undead by it %d; %s", lidLeft, lidAndRing.size(), underLeft, under.size(),
                belowBefore, belowAfter, overhangBefore, overhangAfter, undead, boomLine));
        check(lidLeft == 0, lidLeft + " block(s) of the prince's lid/ring survived");
        check(underLeft == 0, underLeft + " block(s) of the layer under the prince survived");
        // The WHOLE tomb since sim-superboom-radius (killer560 2026-10-14: "that whole structure should be gone"): the
        // smooth stone wall two layers down and the eave slab go with it. 1401 checks every block of it.
        check("air".equals(belowAfter), "the tomb's smooth stone wall under the lid layer survived (" + belowAfter + ")");
        check("air".equals(overhangAfter), "the tomb's eave slab survived (" + overhangAfter + ")");
        check(boomLine.contains("prince=true") && boomLine.contains("crypts opened=0"),
                "the tomb opened as something other than a prince alone: " + boomLine);
        check(undead >= 1, "no undead appeared in the opened prince");
    }

    /**
     * 1404, killer560 (2026-10-13): "this structure in leaves is a prince" - in route practice the floor scan had not
     * registered it, and Superbooms on its polished andesite said prince=false. With the scan's result thrown away
     * (SimPrince.reset after the build), one Superboom at the ring must still find it: prince=true, the lantern gone.
     */
    private void casePrinceMissed(ClientGameTestContext ctx) {
        String room = "Leaves";
        build(ctx, room);
        BlockPos lantern = capW(ctx, room, 9, 82, 15);
        BlockPos aim = capW(ctx, room, 10, 82, 15);
        onServer(ctx, (level, sp) -> ModUnderTest.staticCall(SIM_PRINCE, "reset"));
        boolean knownAfterReset = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SIM_PRINCE, "isPart",
                new Class<?>[]{BlockPos.class}, new Object[]{lantern}));
        stand(ctx, room, 14, 15, 82, new double[]{9.5, 82.5, 15.5});
        String line = boom(ctx, aim, Direction.EAST);
        String lanternAfter = blockName(ctx, lantern);
        say("1404 " + room + ": known after reset " + knownAfterReset + "; lantern after " + lanternAfter + "; " + line);
        check(!knownAfterReset, "the reset did not forget the prince - the case would prove nothing");
        check(line.contains("prince=true"), "the Superboom did not find the prince the scan had missed: " + line);
        check("air".equals(lanternAfter), "the prince's lantern survived");
    }

    /**
     * 1405, killer560 (2026-10-13): "I had a test where I was 3 blocks away and it didnt spawn." An item secret on the
     * floor three blocks ahead of him (2.2 from his side to the block's) appears within 10 ticks; one four blocks ahead
     * (3.2 away) never does in 40.
     */
    private void caseItemRange(ClientGameTestContext ctx) {
        build(ctx, "Mossy");
        BlockPos feet = arena(ctx);
        BlockPos near = feet.offset(3, 0, 0);
        BlockPos far = feet.offset(-4, 0, 0);
        tp(ctx, feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0f, 0f);
        onServer(ctx, (level, sp) -> {
            ModUnderTest.staticCall(SIM_SECRET_ITEMS, "register", new Class<?>[]{BlockPos.class}, new Object[]{near});
            ModUnderTest.staticCall(SIM_SECRET_ITEMS, "register", new Class<?>[]{BlockPos.class}, new Object[]{far});
            return null;
        });
        boolean nearUp = false;
        int t = 0;
        for (; t < 40 && !nearUp; t++) {
            ctx.waitTicks(1);
            nearUp = onServer(ctx, (level, sp) -> {
                @SuppressWarnings("unchecked")
                List<BlockPos> pend = (List<BlockPos>) ModUnderTest.staticCall(SIM_SECRET_ITEMS, "pendingPositions");
                return !pend.contains(near);
            });
        }
        ctx.waitTicks(40);
        boolean farStill = onServer(ctx, (level, sp) -> {
            @SuppressWarnings("unchecked")
            List<BlockPos> pend = (List<BlockPos>) ModUnderTest.staticCall(SIM_SECRET_ITEMS, "pendingPositions");
            return pend.contains(far);
        });
        say("1405 item 3 blocks ahead appeared " + nearUp + " after " + t + " tick(s); item 4 blocks behind still waiting "
                + farStill);
        check(nearUp && t <= 10, "the item 3 blocks from him did not appear within 10 ticks (" + t + ")");
        check(farStill, "the item 4 blocks from him appeared");
    }

    /**
     * 1411, killer560 (2026-10-14): "If i generate a map and in the previous one I cleared secrets in a room, in that new
     * room my map thinks the secrets have already been gotten." A room's found count recorded on one floor (the party
     * interop facts the map reads, keyed by room name) must be gone once a new floor is built.
     */
    private void caseNewMapSecrets(ClientGameTestContext ctx) {
        String interop = "com.killer560.hub.interop.PartyInteropState";
        build(ctx, "Mossy");
        ctx.waitTicks(20);
        Object before = ctx.computeOnClient(mc -> {
            Class<?> src;
            try {
                src = Class.forName("com.killer560.hub.interop.InteropSource");
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
            Object any = src.getEnumConstants()[0];
            ModUnderTest.staticCall(interop, "offerRoomSecrets", new Class<?>[]{String.class, int.class, int.class, src,
                    String.class}, new Object[]{"Mossy", 1, 1, any, "test"});
            return ModUnderTest.staticCall(interop, "roomSecrets", new Class<?>[]{String.class}, new Object[]{"Mossy"});
        });
        build(ctx, "Mossy");
        ctx.waitTicks(20);
        Object after = ctx.computeOnClient(mc -> ModUnderTest.staticCall(interop, "roomSecrets", new Class<?>[]{String.class},
                new Object[]{"Mossy"}));
        say("1411 Mossy found-secrets fact before the new floor " + before + ", after " + after);
        check(before != null, "the fact was never recorded - the case would prove nothing");
        check(after == null || !String.valueOf(after).contains("found=1,"),
                "the previous floor's found secrets survived the new floor: " + after);
    }

    private void casePrinceHead(ClientGameTestContext ctx) {
        String room = "Leaves";
        build(ctx, room);
        BlockPos lantern = capW(ctx, room, 9, 82, 15);
        BlockPos head = capW(ctx, room, 11, 82, 15);
        BlockPos aim = capW(ctx, room, 10, 82, 15);
        BlockPos below = capW(ctx, room, 9, 80, 15);
        say("646 " + room + ": lantern " + blockName(ctx, lantern) + ", head " + blockName(ctx, head) + ", prince "
                + ctx.computeOnClient(mc -> ModUnderTest.staticCall(SIM_PRINCE, "isPart", new Class<?>[]{BlockPos.class},
                new Object[]{lantern})));
        check(blockName(ctx, head).contains("head"), "Leaves has no player head beside its prince at capture (11,82,15)");
        stand(ctx, room, 14, 15, 82, new double[]{9.5, 82.5, 15.5});
        shot(ctx, "646-leaves-prince-before");
        List<BlockPos> under = new ArrayList<>();
        for (int x = 8; x <= 10; x++) {
            for (int z = 14; z <= 16; z++) {
                under.add(capW(ctx, room, x, 81, z));
            }
        }
        String belowBefore = blockName(ctx, below);
        onServer(ctx, (level, sp) -> ModUnderTest.staticCall(SIM_ITEMS, "superboom",
                new Class<?>[]{ServerPlayer.class, BlockPos.class, Direction.class}, new Object[]{sp, aim, Direction.EAST}));
        ctx.waitTicks(10);
        shot(ctx, "646-leaves-prince-after");
        int underLeft = countNotAir(ctx, under);
        String headAfter = blockName(ctx, head);
        String lanternAfter = blockName(ctx, lantern);
        String belowAfter = blockName(ctx, below);
        say("646 after: lantern " + lanternAfter + ", head " + headAfter + ", stair layer left " + underLeft + "/9, below "
                + belowBefore + " -> " + belowAfter);
        check("air".equals(lanternAfter), "the prince itself survived");
        check("air".equals(headAfter), "the prince's player head survived");
        check(underLeft == 0, underLeft + " block(s) of the stair layer under the prince survived");
        check(belowAfter.equals(belowBefore), "the blast went below the stair layer");
    }

    // ============================================================================================ 1401-1403

    /** The blast point the sim uses: the centre of the face the charge hit. */
    private static Vec3 boomAt(BlockPos hit, Direction face) {
        BlockPos out = hit.relative(face);
        return Vec3.atCenterOf(hit).add((out.getX() - hit.getX()) * 0.5, (out.getY() - hit.getY()) * 0.5,
                (out.getZ() - hit.getZ()) * 0.5);
    }

    /** Distance from {@code p} to block {@code b}'s box. */
    private static double boxDist(Vec3 p, BlockPos b) {
        double dx = Math.max(0, Math.max(b.getX() - p.x, p.x - (b.getX() + 1)));
        double dy = Math.max(0, Math.max(b.getY() - p.y, p.y - (b.getY() + 1)));
        double dz = Math.max(0, Math.max(b.getZ() - p.z, p.z - (b.getZ() + 1)));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** The face of {@code hit} that looks at the neighbouring block {@code toward}. */
    private static Direction faceToward(BlockPos hit, BlockPos toward) {
        for (Direction d : Direction.values()) {
            if (hit.relative(d).equals(toward)) {
                return d;
            }
        }
        throw new AssertionError(toward.toShortString() + " is not beside " + hit.toShortString());
    }

    private static String boom(ClientGameTestContext ctx, BlockPos hit, Direction face) {
        long m = LogTap.mark();
        onServer(ctx, (level, sp) -> ModUnderTest.staticCall(SIM_ITEMS, "superboom",
                new Class<?>[]{ServerPlayer.class, BlockPos.class, Direction.class}, new Object[]{sp, hit, face}));
        ctx.waitTicks(10);
        String line = "";
        for (String l : LogTap.since(m)) {
            if (l.contains("Sim superboom at")) {
                line = l;
            }
        }
        return line;
    }

    private static List<String> names(ClientGameTestContext ctx, List<BlockPos> ps) {
        return onServer(ctx, (level, sp) -> {
            List<String> out = new ArrayList<>();
            for (BlockPos p : ps) {
                out.add(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(p).getBlock())
                        .getPath());
            }
            return out;
        });
    }

    /** How many of {@code ps} changed between two {@link #names} reads (into count[0]), naming the first few. */
    private static String changed(List<BlockPos> ps, List<String> before, List<String> after, int[] count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ps.size(); i++) {
            if (!before.get(i).equals(after.get(i))) {
                if (count[0]++ < 6) {
                    sb.append(' ').append(ps.get(i).toShortString()).append(' ').append(before.get(i)).append("->")
                            .append(after.get(i));
                }
            }
        }
        return sb.toString();
    }

    /**
     * killer560 (2026-10-14), Big Red Flag: "this is a prince in big red flag. blowing it up looks like that second
     * picture, but that whole structure should be gone." One Superboom thrown from beside the tomb at its west face must
     * leave every block of the tomb (capture x 12..17, y 69..72, z 22..26) air, take a cracked brick chain laid in the
     * floor beside it (one end in reach, the other 5 blocks out), leave the room wall behind it (z 27, x 18) and the floor
     * under it as they were, and spawn the prince.
     */
    private void caseBoomPrinceTomb(ClientGameTestContext ctx) {
        String room = "Big Red Flag";
        build(ctx, room);
        List<BlockPos> tombAll = new ArrayList<>();
        for (int x = 12; x <= 17; x++) {
            for (int z = 22; z <= 26; z++) {
                for (int y = 69; y <= 72; y++) {
                    tombAll.add(capW(ctx, room, x, y, z));
                }
            }
        }
        List<String> tombNames = names(ctx, tombAll);
        List<BlockPos> tomb = new ArrayList<>();
        for (int i = 0; i < tombAll.size(); i++) {
            if (!"air".equals(tombNames.get(i))) {
                tomb.add(tombAll.get(i));
            }
        }
        List<BlockPos> keep = new ArrayList<>();
        for (int x = 12; x <= 18; x++) {
            for (int y = 68; y <= 73; y++) {
                keep.add(capW(ctx, room, x, y, 27));          // the room wall behind it
            }
        }
        for (int z = 22; z <= 26; z++) {
            for (int y = 68; y <= 73; y++) {
                keep.add(capW(ctx, room, 18, y, z));          // the room wall at its east side
            }
            for (int x = 12; x <= 17; x++) {
                keep.add(capW(ctx, room, x, 68, z));          // the floor under it
            }
        }
        BlockPos hit = capW(ctx, room, 13, 70, 23);
        Direction face = faceToward(hit, capW(ctx, room, 12, 70, 23));
        Vec3 at = boomAt(hit, face);
        // A cracked brick chain in the floor beside it: x 11 (in reach) to x 7 (5 blocks out). Its floor neighbours stay.
        List<BlockPos> chain = new ArrayList<>();
        List<BlockPos> chainSides = new ArrayList<>();
        for (int x = 7; x <= 11; x++) {
            chain.add(capW(ctx, room, x, 68, 23));
            chainSides.add(capW(ctx, room, x, 68, 22));
            chainSides.add(capW(ctx, room, x, 68, 24));
        }
        onServer(ctx, (level, sp) -> {
            for (BlockPos p : chain) {
                level.setBlockAndUpdate(p, Blocks.CRACKED_STONE_BRICKS.defaultBlockState());
            }
            return null;
        });
        keep.addAll(chainSides);
        // Every cracked stone brick within 3 blocks of the blast, as the world has it now.
        List<BlockPos> crackedInReach = onServer(ctx, (level, sp) -> {
            List<BlockPos> out = new ArrayList<>();
            for (int dx = -4; dx <= 4; dx++) {
                for (int dy = -4; dy <= 4; dy++) {
                    for (int dz = -4; dz <= 4; dz++) {
                        BlockPos p = hit.offset(dx, dy, dz);
                        if (boxDist(at, p) <= 3.0 && level.getBlockState(p).is(Blocks.CRACKED_STONE_BRICKS)) {
                            out.add(p);
                        }
                    }
                }
            }
            return out;
        });
        List<String> keepBefore = names(ctx, keep);
        BlockPos lid = capW(ctx, room, 14, 72, 24);
        int undeadBefore = zombies(ctx, lid, 6);
        stand(ctx, room, 9, 25, 69, new double[]{12.9, 70.5, 23.5});
        shot(ctx, "1401-brf-tomb-before");
        String line = boom(ctx, hit, face);
        shot(ctx, "1401-brf-tomb-after");
        int tombLeft = countNotAir(ctx, tomb);
        int chainLeft = countNotAir(ctx, chain);
        int crackedLeft = countNotAir(ctx, crackedInReach);
        int[] moved = {0};
        String which = changed(keep, keepBefore, names(ctx, keep), moved);
        int undeadAfter = zombies(ctx, lid, 6);
        List<String> leftNames = new ArrayList<>();
        List<String> tombAfter = names(ctx, tomb);
        for (int i = 0; i < tomb.size(); i++) {
            if (!"air".equals(tombAfter.get(i)) && leftNames.size() < 8) {
                leftNames.add(tomb.get(i).toShortString() + " " + tombAfter.get(i));
            }
        }
        say(String.format(Locale.US, "1401 %s: superboom at %s face %s (blast %.1f %.1f %.1f): tomb left %d/%d %s; floor chain"
                        + " left %d/%d; cracked in reach left %d/%d; wall/floor kept changed %d/%d%s; undead %d -> %d; %s",
                room, hit.toShortString(), face, at.x, at.y, at.z, tombLeft, tomb.size(), leftNames, chainLeft, chain.size(),
                crackedLeft, crackedInReach.size(), moved[0], keep.size(), which, undeadBefore, undeadAfter, line));
        check(tomb.size() >= 60, "the tomb region holds only " + tomb.size() + " blocks - not Big Red Flag's tomb");
        check(tombLeft == 0, tombLeft + " block(s) of the prince's tomb survived: " + leftNames);
        check(chainLeft == 0, chainLeft + " block(s) of the cracked chain survived (one end in reach)");
        check(crackedLeft == 0, crackedLeft + " cracked stone brick(s) within 3 blocks survived");
        check(moved[0] == 0, moved[0] + " room wall/floor block(s) changed:" + which);
        check(line.contains("prince=true"), "the tomb did not open as the prince: " + line);
        check(undeadAfter > undeadBefore, "no undead appeared in the opened prince");
        int[] seen = {0};
        List<String> up = floating(ctx, lid, 8, seen);
        say("1401 prince undead on the floor: " + (seen[0] - up.size()) + "/" + seen[0] + (up.isEmpty() ? "" : " floating " + up));
        check(seen[0] > 0 && up.isEmpty(), "prince undead not standing on the floor: " + up);
    }

    /**
     * A second crypt in another room: Arrow Trap's slab crypt (capture x 15..17, z 20..23, lid y 70, stairs y 69), the
     * charge hitting the underside of an overhang two blocks above the lid (a stone block put at capture 16,73,21). The
     * lid's top is 2 blocks from the blast, so the whole crypt opens - slabs and the stairs under them - with its undead;
     * the floor and the wall behind it stay. Before sim-superboom-radius the reach was y +-2 from the hit block, so a lid
     * 3 layers under the hit was out of reach and nothing broke.
     */
    private void caseBoomCryptOverhead(ClientGameTestContext ctx) {
        String room = "Arrow Trap";
        build(ctx, room);
        List<BlockPos> region = new ArrayList<>();
        for (int x = 15; x <= 17; x++) {
            for (int z = 20; z <= 23; z++) {
                region.add(capW(ctx, room, x, 70, z));
                region.add(capW(ctx, room, x, 69, z));
            }
        }
        List<String> regionNames = names(ctx, region);
        List<BlockPos> crypt = new ArrayList<>();
        int slabs = 0;
        for (int i = 0; i < region.size(); i++) {
            String n = regionNames.get(i);
            if (n.equals("smooth_stone_slab") || n.equals("stone_brick_stairs")) {
                crypt.add(region.get(i));
                slabs += n.equals("smooth_stone_slab") ? 1 : 0;
            }
        }
        List<BlockPos> keep = new ArrayList<>();
        for (int x = 15; x <= 17; x++) {
            for (int z = 20; z <= 23; z++) {
                keep.add(capW(ctx, room, x, 68, z));         // the floor
            }
            for (int y = 69; y <= 72; y++) {
                keep.add(capW(ctx, room, x, y, 24));         // the wall behind it
            }
        }
        BlockPos hit = capW(ctx, room, 16, 73, 21);
        onServer(ctx, (level, sp) -> {
            level.setBlockAndUpdate(hit, Blocks.STONE.defaultBlockState());
            return null;
        });
        List<String> keepBefore = names(ctx, keep);
        BlockPos lidMid = capW(ctx, room, 16, 70, 21);
        int undeadBefore = zombies(ctx, lidMid, 6);
        stand(ctx, room, 16, 17, 69, new double[]{16.5, 71, 21.5});
        shot(ctx, "1402-arrowtrap-crypt-before");
        String line = boom(ctx, hit, Direction.DOWN);
        shot(ctx, "1402-arrowtrap-crypt-after");
        int left = countNotAir(ctx, crypt);
        int[] moved = {0};
        String which = changed(keep, keepBefore, names(ctx, keep), moved);
        int undeadAfter = zombies(ctx, lidMid, 6);
        say(String.format(Locale.US, "1402 %s: superboom under the overhang at %s: crypt (%d slabs + %d stairs) left %d/%d;"
                        + " floor/wall changed %d/%d%s; undead %d -> %d; %s", room, hit.toShortString(), slabs,
                crypt.size() - slabs, left, crypt.size(), moved[0], keep.size(), which, undeadBefore, undeadAfter, line));
        check(slabs == 12, "Arrow Trap's crypt lid is not 12 slabs here (" + slabs + ")");
        check(left == 0, left + " block(s) of Arrow Trap's crypt survived");
        check(moved[0] == 0, moved[0] + " floor/wall block(s) changed:" + which);
        check(undeadAfter > undeadBefore, "no crypt undead appeared");
        int[] seen = {0};
        List<String> up = floating(ctx, lidMid, 8, seen);
        say("1402 crypt undead on the floor: " + (seen[0] - up.size()) + "/" + seen[0] + (up.isEmpty() ? "" : " floating " + up));
        check(seen[0] > 0 && up.isEmpty(), "crypt undead not standing on the floor: " + up);
    }

    /**
     * killer560 (2026-10-14): "if it is a chain of blocks but any one is within the 3 blocks then it should break them
     * all." In a clear arena: a chain of 8 cracked stone bricks in the floor from 3 to 10 blocks out (only the first in
     * reach), with a two-high stone brick wall along it; a lone cracked brick 6 blocks the other way. The whole chain
     * goes; every stone brick of the wall stays, including those in reach; the lone brick and the stone hit stay.
     */
    private void caseBoomChain(ClientGameTestContext ctx) {
        build(ctx, "Mossy");
        BlockPos feet = arena(ctx);
        int fy = feet.getY() - 1;
        BlockPos t = new BlockPos(feet.getX(), fy, feet.getZ() + 6);
        List<BlockPos> chain = new ArrayList<>();
        List<BlockPos> wall = new ArrayList<>();
        for (int dx = 3; dx <= 10; dx++) {
            chain.add(t.offset(dx, 0, 0));
            wall.add(t.offset(dx, 0, 1));
            wall.add(t.offset(dx, 1, 1));
        }
        BlockPos lone = t.offset(-6, 0, 0);
        onServer(ctx, (level, sp) -> {
            for (BlockPos p : chain) {
                level.setBlockAndUpdate(p, Blocks.CRACKED_STONE_BRICKS.defaultBlockState());
            }
            for (BlockPos p : wall) {
                level.setBlockAndUpdate(p, Blocks.STONE_BRICKS.defaultBlockState());
            }
            level.setBlockAndUpdate(lone, Blocks.CRACKED_STONE_BRICKS.defaultBlockState());
            return null;
        });
        ctx.waitTicks(5);
        Vec3 at = boomAt(t, Direction.UP);
        int inReach = 0;
        for (BlockPos p : chain) {
            inReach += boxDist(at, p) <= 3.0 ? 1 : 0;
        }
        int wallInReach = 0;
        for (BlockPos p : wall) {
            wallInReach += boxDist(at, p) <= 3.0 ? 1 : 0;
        }
        shot(ctx, "1403-chain-before");
        String line = boom(ctx, t, Direction.UP);
        shot(ctx, "1403-chain-after");
        int chainLeft = countNotAir(ctx, chain);
        int wallLeft = countIs(ctx, wall, Blocks.STONE_BRICKS);
        boolean loneStays = onServer(ctx, (level, sp) -> level.getBlockState(lone).is(Blocks.CRACKED_STONE_BRICKS));
        boolean hitStays = onServer(ctx, (level, sp) -> level.getBlockState(t).is(Blocks.STONE));
        say(String.format(Locale.US, "1403 superboom at %s: chain of %d (%d in reach) left %d; stone brick wall %d/%d stays"
                        + " (%d in reach); lone cracked brick 6 away stays %s; the stone hit stays %s; %s", t.toShortString(),
                chain.size(), inReach, chainLeft, wallLeft, wall.size(), wallInReach, loneStays, hitStays, line));
        check(inReach >= 1 && inReach < chain.size(), "the chain is not partly in reach (" + inReach + ")");
        check(chainLeft == 0, chainLeft + " block(s) of the cracked chain survived");
        check(wallInReach > 0 && wallLeft == wall.size(),
                (wall.size() - wallLeft) + " stone brick(s) of the wall beside it were broken");
        check(loneStays, "a cracked brick 6 blocks away, not joined to anything in reach, was broken");
        check(hitStays, "the plain stone the charge hit was broken");
    }

    // ============================================================================================ 652

    /**
     * Secret Waypoints' Show Princes in Big Red Flag: its tomb (the smooth stone lid ringed by slabs, SimPrince's third
     * shape) is ONE prince waypoint whose box holds the lid, and no crypt waypoint - the room database gives Big Red Flag
     * one crypt, which is this prince. One answer for the sim and the waypoints: CryptScanner.lidPrince is the rule both
     * ask. Before mimic-fix CryptScanner knew only the gold and sea lantern shapes, so it showed nothing here.
     */
    private void casePrinceWaypoint(ClientGameTestContext ctx) {
        String room = "Big Red Flag";
        String cfgClass = "com.killer560.hub.secretwaypoints.SecretWaypointsConfig";
        String feature = "com.killer560.hub.secretwaypoints.SecretWaypointsFeature";
        build(ctx, room);
        BlockPos lid = capW(ctx, room, 14, 72, 24);
        Object cfg = ModUnderTest.config(cfgClass);
        boolean was = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.call(cfg, "isEnabled", new Class<?>[]{},
                new Object[]{}));
        try {
            ctx.runOnClient(mc -> {
                ModUnderTest.set(cfg, "setEnabled", true);
                ModUnderTest.set(cfg, "setShowCrypts", true);
                ModUnderTest.set(cfg, "setShowPrinces", true);
                ModUnderTest.staticCall(feature, "invalidateCache");
            });
            ctx.waitTicks(40);
            @SuppressWarnings("unchecked")
            List<double[]> princes = ctx.computeOnClient(mc -> new ArrayList<>((List<double[]>) ModUnderTest.staticCall(
                    feature, "cachedBoxes", new Class<?>[]{String.class}, new Object[]{"PRINCE"})));
            @SuppressWarnings("unchecked")
            List<BlockPos> crypts = ctx.computeOnClient(mc -> new ArrayList<>((List<BlockPos>) ModUnderTest.staticCall(
                    feature, "cachedPositions", new Class<?>[]{String.class}, new Object[]{"CRYPT"})));
            boolean holdsLid = false;
            StringBuilder boxes = new StringBuilder();
            for (double[] b : princes) {
                boxes.append(String.format(Locale.US, " [%.0f,%.0f,%.0f..%.0f,%.0f,%.0f]", b[0], b[1], b[2], b[3], b[4], b[5]));
                holdsLid |= lid.getX() + 0.5 >= b[0] && lid.getX() + 0.5 <= b[3] && lid.getY() + 0.5 >= b[1]
                        && lid.getY() + 0.5 <= b[4] && lid.getZ() + 0.5 >= b[2] && lid.getZ() + 0.5 <= b[5];
            }
            say("652 " + room + ": prince waypoint(s) " + princes.size() + boxes + ", lid " + lid.toShortString()
                    + " inside one " + holdsLid + ", crypt waypoint(s) " + crypts);
            check(princes.size() == 1 && holdsLid, "Big Red Flag's tomb is not one prince waypoint (" + princes.size()
                    + " prince box(es), lid inside one: " + holdsLid + ")");
            check(crypts.isEmpty(), "Big Red Flag shows crypt waypoint(s) " + crypts + " - its one crypt is the prince");
        } finally {
            ctx.runOnClient(mc -> {
                ModUnderTest.set(cfg, "setShowCrypts", false);
                ModUnderTest.set(cfg, "setShowPrinces", false);
                ModUnderTest.set(cfg, "setEnabled", was);
            });
        }
    }

    // ============================================================================================ 647-650

    private void caseLever(ClientGameTestContext ctx, String c, String room, int[] lever, int[] from, int[] to,
                           boolean placed, int[] doorView, int[] leverView) {
        build(ctx, room);
        BlockPos leverPos = capW(ctx, room, lever[0], lever[1], lever[2]);
        String leverBlock = blockName(ctx, leverPos);
        int armed = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(SIM_LEVERS, "armedCount"));
        List<BlockPos> door = new ArrayList<>();
        for (int x = from[0]; x <= to[0]; x++) {
            for (int y = from[1]; y <= to[1]; y++) {
                for (int z = from[2]; z <= to[2]; z++) {
                    door.add(capW(ctx, room, x, y, z));
                }
            }
        }
        int solidBefore = countNotAir(ctx, door);
        say(c + " " + room + ": lever " + leverBlock + " at " + leverPos.toShortString() + (placed ? " (placed by the sim)" : "")
                + ", " + armed + " lever(s) armed, door " + solidBefore + "/" + door.size() + " solid");
        check(leverBlock.equals("lever"), "no lever at capture " + lever[0] + "," + lever[1] + "," + lever[2]);
        check(solidBefore == door.size(), "the door is not whole before the lever (" + solidBefore + "/" + door.size() + ")");
        double[] doorCentre = {(from[0] + to[0]) / 2.0 + 0.5, (from[1] + to[1]) / 2.0 + 0.5, (from[2] + to[2]) / 2.0 + 0.5};
        if (doorView != null) {
            stand(ctx, room, doorView[0], doorView[1], from[1] + 1, doorCentre);
            shot(ctx, c + "-door-before");
            stand(ctx, room, leverView[0], leverView[1], lever[1],
                    new double[]{lever[0] + 0.5, lever[1] + 0.3, lever[2] + 0.5});
            shot(ctx, c + "-lever");
        }
        long m = LogTap.mark();
        ctx.runOnClient(mc -> mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(leverPos), Direction.UP, leverPos, false)));
        ctx.waitTicks(15);
        int solidAfter = countNotAir(ctx, door);
        boolean chat = false;
        for (String l : LogTap.since(m)) {
            chat |= l.contains("the lever takes the way through down");
        }
        if (doorView != null) {
            stand(ctx, room, doorView[0], doorView[1], from[1] + 1, doorCentre);
            shot(ctx, c + "-door-after");
        }
        say(c + " after the flick: door " + solidAfter + "/" + door.size() + " solid, chat line " + chat);
        check(solidAfter == 0, solidAfter + " door block(s) still standing after the lever");
    }

    // ============================================================================================ 1406-1407

    /**
     * One room, several lever doors: each {lever, from, to, standColumn}. He is stood in reach of each lever in turn
     * and uses it as the player; the lever must flip ON THE SERVER (powered), its own door must go entirely, and every
     * door whose lever has not been flicked yet must still be whole.
     */
    private void caseLeverDoors(ClientGameTestContext ctx, String c, String room, int[][][] rules) {
        build(ctx, room);
        int armed = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(SIM_LEVERS, "armedCount"));
        List<List<BlockPos>> doors = new ArrayList<>();
        List<BlockPos> levers = new ArrayList<>();
        for (int[][] r : rules) {
            levers.add(capW(ctx, room, r[0][0], r[0][1], r[0][2]));
            List<BlockPos> door = new ArrayList<>();
            for (int x = r[1][0]; x <= r[2][0]; x++) {
                for (int y = r[1][1]; y <= r[2][1]; y++) {
                    for (int z = r[1][2]; z <= r[2][2]; z++) {
                        door.add(capW(ctx, room, x, y, z));
                    }
                }
            }
            doors.add(door);
        }
        say(c + " " + room + ": " + armed + " lever(s) armed");
        // At least: a room may have more levers than this case flicks (Buttons' lever behind the lava, 2026-10-17, is
        // 1983-sim-gate-buttons'); SimLeverGateTests' sweep holds the full count per room.
        check(armed >= rules.length, room + " armed " + armed + " lever(s), expected at least " + rules.length);
        for (int i = 0; i < rules.length; i++) {
            BlockPos leverPos = levers.get(i);
            String leverBlock = blockName(ctx, leverPos);
            int solidBefore = countNotAir(ctx, doors.get(i));
            say(c + " lever " + (i + 1) + " " + leverBlock + " at capture " + java.util.Arrays.toString(rules[i][0])
                    + ", its door " + solidBefore + "/" + doors.get(i).size() + " solid");
            check(leverBlock.equals("lever"), "no lever at capture " + java.util.Arrays.toString(rules[i][0]));
            check(solidBefore == doors.get(i).size(), "door " + (i + 1) + " is not whole before its lever ("
                    + solidBefore + "/" + doors.get(i).size() + ")");
            check(!powered(ctx, leverPos), "lever " + (i + 1) + " is already on before the flick");
            int[] l = rules[i][0];
            stand(ctx, room, rules[i][3][0], rules[i][3][1], l[1] - 2, new double[]{l[0] + 0.5, l[1] + 0.3, l[2] + 0.5});
            double reach = ctx.computeOnClient(mc -> Math.sqrt(Vec3.atCenterOf(leverPos).distanceToSqr(mc.player.getEyePosition())));
            ctx.runOnClient(mc -> mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(leverPos), Direction.UP, leverPos, false)));
            ctx.waitTicks(15);
            boolean flipped = powered(ctx, leverPos);
            int solidAfter = countNotAir(ctx, doors.get(i));
            StringBuilder others = new StringBuilder();
            for (int j = i + 1; j < rules.length; j++) {
                int s = countNotAir(ctx, doors.get(j));
                others.append(" door ").append(j + 1).append(' ').append(s).append('/').append(doors.get(j).size());
                check(s == doors.get(j).size(), "lever " + (i + 1) + " also took " + (doors.get(j).size() - s)
                        + " block(s) of door " + (j + 1));
            }
            say(c + " after lever " + (i + 1) + " (eye " + String.format(Locale.ROOT, "%.2f", reach)
                    + " from it): powered " + flipped + ", its door " + solidAfter + "/" + doors.get(i).size()
                    + " solid;" + (others.length() == 0 ? " no other doors left" : others.toString()));
            check(flipped, "lever " + (i + 1) + " did not flip on the server");
            check(solidAfter == 0, solidAfter + " block(s) of door " + (i + 1) + " still standing after its lever");
        }
    }

    // ============================================================================================ 1415

    /**
     * Golden Oasis' Redstone Key, as a player does it: the key is a head wearing Hypixel's key profile at capture
     * (13, 71, 9) and the node a redstone block at (11, 71, 4). A click on the node with no key does nothing; a click on
     * the key takes it (not a secret); a click on the node then places it: the node goes, the three redstone blocks by
     * the lever at (27..29, 70, 26) go, one secret counts for the room, and the client sees an armor stand wearing the
     * key where the node was.
     */
    private void caseRedstoneKey(ClientGameTestContext ctx, String c) {
        String room = "Golden Oasis";
        build(ctx, room);
        BlockPos key = capW(ctx, room, 13, 71, 9);
        BlockPos node = capW(ctx, room, 11, 71, 4);
        List<BlockPos> strip = new ArrayList<>();
        for (int x = 27; x <= 29; x++) {
            strip.add(capW(ctx, room, x, 70, 26));
        }
        boolean keyHead = isKeyOnClient(ctx, key);
        String nodeBlock = blockName(ctx, node);
        int stripBefore = countIs(ctx, strip, Blocks.REDSTONE_BLOCK);
        int found0 = foundIn(ctx, room);
        // Before asking SimRedstoneKey anything, so a jar without it fails on what the world holds.
        check(keyHead, "no Redstone Key head (Hypixel's key profile) at capture 13,71,9 - it is " + blockName(ctx, key));
        int armed = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(SIM_KEY, "armedCount"));
        say(c + " before: node(s) armed " + armed + ", key head (client, by profile) " + keyHead + " at "
                + key.toShortString() + ", node " + nodeBlock + " at " + node.toShortString() + ", strip " + stripBefore
                + "/3 redstone, room secrets " + found0);
        check(keyHead, "no Redstone Key head (Hypixel's key profile) at capture 13,71,9 - it is " + blockName(ctx, key));
        check(nodeBlock.equals("redstone_block"), "no redstone node at capture 11,71,4 - it is " + nodeBlock);
        check(stripBefore == 3, "the strip by the lever is " + stripBefore + "/3 redstone before anything");
        check(armed == 1, armed + " node(s) armed, expected 1");
        // aura-skull-place-1016: Auto Routes' await counts the key as Hypixel does - the placing, not the pickup. An
        // await window is opened as a running route's would be (cheat jars: Auto Routes is a cheat-build feature).
        boolean cheat = isCheatJar();
        if (cheat) {
            ctx.runOnClient(mc -> {
                ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setEnabled", true);
                ModUnderTest.staticCall(AWAIT, "begin", new Class<?>[]{Minecraft.class}, new Object[]{mc});
                ModUnderTest.staticCall(AWAIT, "window", new Class<?>[]{Minecraft.class}, new Object[]{mc});
            });
        }

        // Control: the node with no key does nothing.
        stand(ctx, room, 9, 5, 69, new double[]{11.5, 71.5, 4.5});
        use(ctx, node);
        ctx.waitTicks(15);
        int stripNoKey = countIs(ctx, strip, Blocks.REDSTONE_BLOCK);
        String nodeNoKey = blockName(ctx, node);
        int awaitNoKey = cheat ? awaitSecrets(ctx) : 0;
        say(c + " node clicked without the key: node " + nodeNoKey + ", strip " + stripNoKey + "/3, await secrets "
                + awaitNoKey);
        check(nodeNoKey.equals("redstone_block") && stripNoKey == 3, "the node did something without the key");
        check(awaitNoKey == 0, "Auto Routes' await counted a node click with no key");

        // The key.
        stand(ctx, room, 13, 7, 69, new double[]{13.5, 71.25, 9.5});
        double keyReach = ctx.computeOnClient(mc -> Math.sqrt(Vec3.atCenterOf(key).distanceToSqr(mc.player.getEyePosition())));
        use(ctx, key);
        ctx.waitTicks(15);
        String keyAfter = blockName(ctx, key);
        boolean found = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SIM_KEY, "keyFound",
                new Class<?>[]{String.class}, new Object[]{room}));
        int found1 = foundIn(ctx, room);
        say(c + " key clicked (eye " + String.format(Locale.ROOT, "%.2f", keyReach) + " from it): its block is now "
                + keyAfter + ", key found " + found + ", room secrets " + found1);
        check(keyAfter.equals("air"), "the key head is still there after the click (" + keyAfter + ")");
        check(!isKeyOnClient(ctx, key), "the client still sees the key head");
        check(found, "the key was not taken");
        check(found1 == found0, "picking the key up counted a secret (" + found0 + " -> " + found1 + ")");
        int awaitKey = cheat ? awaitSecrets(ctx) : 0;
        say(c + " await secrets after the pickup " + awaitKey);
        check(awaitKey == 0, "Auto Routes' await counted the key's pickup (Hypixel counts only its placing)");

        // The node, with the key.
        stand(ctx, room, 9, 5, 69, new double[]{11.5, 71.5, 4.5});
        double nodeReach = ctx.computeOnClient(mc -> Math.sqrt(Vec3.atCenterOf(node).distanceToSqr(mc.player.getEyePosition())));
        use(ctx, node);
        ctx.waitTicks(20);
        String nodeAfter = blockName(ctx, node);
        int stripAfter = countIs(ctx, strip, Blocks.REDSTONE_BLOCK);
        int stripSolid = countNotAir(ctx, strip);
        int found2 = foundIn(ctx, room);
        int placed = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(SIM_KEY, "placedCount"));
        boolean standSeen = ctx.computeOnClient(mc -> {
            for (var e : mc.level.entitiesForRendering()) {
                if (e instanceof net.minecraft.world.entity.decoration.ArmorStand as
                        && e.blockPosition().getY() + 2 == node.getY()
                        && Math.abs(e.getX() - (node.getX() + 0.5)) < 0.6 && Math.abs(e.getZ() - (node.getZ() + 0.5)) < 0.6) {
                    var prof = as.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD)
                            .get(net.minecraft.core.component.DataComponents.PROFILE);
                    if (prof != null && prof.partialProfile() != null
                            && "fed95410-aba1-39df-9b95-1d4f361eb66e".equals(String.valueOf(prof.partialProfile().id()))) {
                        return true;
                    }
                }
            }
            return false;
        });
        say(c + " node clicked with the key (eye " + String.format(Locale.ROOT, "%.2f", nodeReach) + " from it): node "
                + nodeAfter + ", strip " + stripAfter + "/3 redstone (" + stripSolid + " solid), room secrets " + found1
                + " -> " + found2 + ", keys placed " + placed + ", key stand seen by the client " + standSeen);
        check(nodeAfter.equals("air"), "the node is still " + nodeAfter + " after the key went in");
        check(stripSolid == 0, stripSolid + " of the 3 strip blocks still standing after the key went in");
        check(found2 == found1 + 1, "placing the key counted " + (found2 - found1) + " secret(s) for the room, expected 1");
        check(placed == 1, placed + " key(s) placed");
        check(standSeen, "the client sees no armor stand wearing the key at the node");
        if (cheat) {
            int awaitPlaced = awaitSecrets(ctx);
            ctx.runOnClient(mc -> ModUnderTest.staticCall(AWAIT, "end", new Class<?>[]{}, new Object[]{}));
            say(c + " await secrets after the placing " + awaitPlaced);
            check(awaitPlaced == 1, "Auto Routes' await counted " + awaitPlaced + " secret(s) for the key's placing, expected 1");
        }
    }

    private static int awaitSecrets(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(AWAIT, "secrets", new Class<?>[]{}, new Object[]{}));
    }

    private static boolean isCheatJar() {
        try {
            return Class.forName("com.killer560.hub.BuildVariant").getField("CHEAT_FEATURES_ENABLED").getBoolean(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static int foundIn(ClientGameTestContext ctx, String room) {
        return ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(SIM_SCORE, "foundInRoom",
                new Class<?>[]{String.class}, new Object[]{room}));
    }

    private static boolean isKeyOnClient(ClientGameTestContext ctx, BlockPos p) {
        return ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.secrets.SecretsFeature",
                "isRedstoneKey", new Class<?>[]{net.minecraft.world.level.BlockGetter.class, BlockPos.class},
                new Object[]{mc.level, p}));
    }

    /** A plain right click on a block, as the player sends it. */
    private static void use(ClientGameTestContext ctx, BlockPos p) {
        ctx.runOnClient(mc -> mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(p), Direction.UP, p, false)));
    }

    private static boolean powered(ClientGameTestContext ctx, BlockPos p) {
        return onServer(ctx, (level, sp) -> {
            net.minecraft.world.level.block.state.BlockState s = level.getBlockState(p);
            return s.hasProperty(net.minecraft.world.level.block.LeverBlock.POWERED)
                    && s.getValue(net.minecraft.world.level.block.LeverBlock.POWERED);
        });
    }

    // ============================================================================================ 1781-1783

    /** Supertall's two obsidian tombs, capture coordinates: the upper one's obsidian ring and the lower one's 1x2. */
    private static final int[][] SUPERTALL_UPPER_OBSIDIAN = {{49, 142, 44}, {50, 142, 44}, {51, 142, 44}, {52, 142, 44},
            {49, 142, 45}, {52, 142, 45}, {49, 142, 46}, {52, 142, 46}, {49, 142, 47}, {50, 142, 47}, {51, 142, 47},
            {52, 142, 47}};
    private static final int[][] SUPERTALL_LOWER_OBSIDIAN = {{5, 99, 12}, {5, 99, 13}};

    /**
     * 1781, killer560 (2026-10-16), standing in Supertall: "this is a prince" (the raised obsidian tomb under the two
     * hopper braziers, capture x 48..53 y 142 z 44..48). The wiki gives Supertall 2 princes, and the room's other
     * obsidian tomb (x 4..6 y 99 z 11..14, "behind a Cracked Stone Bricks wall") is the second. Built by itself: both
     * are princes and neither is a crypt; one Superboom on each opens it as a prince (prince=true, no crypt), takes all
     * its obsidian and drops an undead; the run's prince point is taken once. Then {@code /reset}: both come back, whole
     * and unblown.
     */
    private void casePrinceSupertall(ClientGameTestContext ctx) {
        String room = "Supertall";
        build(ctx, room);
        List<BlockPos> upper = capList(ctx, room, SUPERTALL_UPPER_OBSIDIAN);
        List<BlockPos> lower = capList(ctx, room, SUPERTALL_LOWER_OBSIDIAN);
        BlockPos upperRim = capW(ctx, room, 50, 142, 48);
        BlockPos lowerRim = capW(ctx, room, 4, 99, 11);
        BlockPos chestPlatform = capW(ctx, room, 27, 141, 52);
        int princes = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(SIM_PRINCE, "count"));
        boolean upperPart = isPart(ctx, upper.get(0)) && isPart(ctx, upperRim);
        boolean lowerPart = isPart(ctx, lower.get(0)) && isPart(ctx, lowerRim);
        boolean platformPart = isPart(ctx, chestPlatform);
        int upperObs = countIs(ctx, upper, Blocks.OBSIDIAN);
        int lowerObs = countIs(ctx, lower, Blocks.OBSIDIAN);
        say("1781 " + room + ": " + princes + " prince(s); upper tomb obsidian " + upperObs + "/" + upper.size()
                + ", a prince " + upperPart + "; lower tomb obsidian " + lowerObs + "/" + lower.size() + ", a prince "
                + lowerPart + "; chest platform slab a prince " + platformPart);
        check(upperObs == upper.size() && lowerObs == lower.size(), "the capture's obsidian is not where the census put it");
        check(princes == 2, "Supertall has " + princes + " prince(s), the wiki and its two obsidian tombs say 2");
        check(upperPart, "the upper obsidian tomb (his screenshot) is not a prince, or its slab rim is not part of it");
        check(lowerPart, "the lower obsidian tomb is not a prince, or its slab rim is not part of it");
        check(!platformPart, "a chest platform's slabs were taken for a prince");

        // The upper tomb, aimed at its rim from the floor in front of it.
        stand(ctx, room, 50, 51, 142, new double[]{50.5, 142.5, 48.5});
        shot(ctx, "1781-supertall-prince-before");
        int undeadBefore = zombies(ctx, upperRim, 4);
        String line = boom(ctx, upperRim, Direction.UP);
        int upperLeft = countNotAir(ctx, upper);
        int undeadAfter = zombies(ctx, upperRim, 4);
        boolean scored = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SIM_PRINCE, "hasScored"));
        shot(ctx, "1781-supertall-prince-after");
        say("1781 upper: obsidian left " + upperLeft + "/" + upper.size() + ", undead " + undeadBefore + " -> "
                + undeadAfter + ", run scored the prince " + scored + "; " + line);
        check(line.contains("prince=true") && line.contains("crypts opened=0"),
                "the upper tomb did not open as a prince alone: " + line);
        check(upperLeft == 0, upperLeft + " obsidian block(s) of the upper tomb survived");
        check(undeadAfter > undeadBefore, "no undead appeared in the opened upper prince");
        check(scored, "blowing the prince did not take the run's prince point");

        // The lower tomb, from the floor of its alcove.
        stand(ctx, room, 7, 12, 98, new double[]{5.5, 99.5, 12.5});
        int lowBefore = zombies(ctx, lower.get(0), 4);
        String line2 = boom(ctx, lower.get(0), Direction.UP);
        int lowerLeft = countNotAir(ctx, lower);
        int lowAfter = zombies(ctx, lower.get(0), 4);
        say("1781 lower: obsidian left " + lowerLeft + "/" + lower.size() + ", undead " + lowBefore + " -> " + lowAfter
                + "; " + line2);
        check(line2.contains("prince=true") && line2.contains("crypts opened=0"),
                "the lower tomb did not open as a prince alone: " + line2);
        check(lowerLeft == 0, lowerLeft + " obsidian block(s) of the lower tomb survived");
        check(lowAfter > lowBefore, "no undead appeared in the opened lower prince");

        // /reset: the room as loaded, both princes back and unblown.
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.player.connection.sendCommand("reset"));
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitFor(mc -> McCompat.screen(mc) == null, 1200);
        waitStanding(ctx, room);
        ctx.waitTicks(30);
        int princesAfter = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(SIM_PRINCE, "count"));
        int upperBack = countIs(ctx, upper, Blocks.OBSIDIAN);
        int lowerBack = countIs(ctx, lower, Blocks.OBSIDIAN);
        boolean upperLive = isPrince(ctx, upper.get(0));
        boolean lowerLive = isPrince(ctx, lower.get(0));
        boolean scoredAfter = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SIM_PRINCE, "hasScored"));
        say("1781 after /reset: " + princesAfter + " prince(s), upper obsidian " + upperBack + "/" + upper.size()
                + " unblown " + upperLive + ", lower obsidian " + lowerBack + "/" + lower.size() + " unblown " + lowerLive
                + ", prince point taken " + scoredAfter);
        check(princesAfter == 2, "after /reset Supertall has " + princesAfter + " prince(s)");
        check(upperBack == upper.size() && lowerBack == lower.size(), "/reset did not put the obsidian back");
        check(upperLive && lowerLive, "after /reset a prince is still marked blown");
        check(!scoredAfter, "after /reset the run's prince point is still taken");
    }

    /**
     * 1782: Supertall on a generated floor (grid cells x 2..4, z 4..6, unrotated, beside Dueces) - the floor scan
     * registers both obsidian tombs as princes.
     */
    private void casePrinceSupertallFloor(ClientGameTestContext ctx) {
        String room = "Supertall";
        int grid = 11;
        int[] cellRoom = new int[grid * grid];
        java.util.Arrays.fill(cellRoom, -1);
        int[] cellDoor = new int[grid * grid];
        int[] cellRotation = new int[grid * grid];
        for (int gz = 4; gz <= 6; gz++) {
            for (int gx = 2; gx <= 4; gx++) {
                cellRoom[gz * grid + gx] = 0;
            }
        }
        cellRoom[6 * grid + 6] = 1;
        cellDoor[6 * grid + 5] = 1; // DungeonLayout.DOOR_NORMAL
        String code = ctx.computeOnClient(mc -> {
            try {
                Class<?> dec = Class.forName(MAP_CODE + "$Decoded");
                Object d = dec.getConstructor(String[].class, int[].class, int[].class, int[].class)
                        .newInstance(new String[]{room, "Dueces"}, cellRoom, cellDoor, cellRotation);
                return (String) ModUnderTest.staticCall(MAP_CODE, "encodeDecoded", new Class<?>[]{dec}, new Object[]{d});
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        });
        long before = Scenario.simBuildCount(ctx);
        long mark = LogTap.mark();
        ctx.runOnClient(mc -> mc.execute(() -> ModUnderTest.staticCall(BUILDER, "build",
                new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, code})));
        ctx.waitFor(mc -> mc.level != null && mc.player != null, 2400);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitFor(mc -> McCompat.screen(mc) == null, 1200);
        String princeLine = "";
        for (int i = 0; i < 600 && princeLine.isEmpty(); i++) {
            for (String l : LogTap.since(mark)) {
                if (l.contains("Sim prince:") && !l.contains("[STDOUT]")) {
                    princeLine = l;
                }
            }
            ctx.waitTicks(1);
        }
        waitStanding(ctx, "the floor");
        playerId = ctx.computeOnClient(mc -> mc.player.getUUID());
        builtRoom = room;
        List<BlockPos> upper = capList(ctx, room, SUPERTALL_UPPER_OBSIDIAN);
        List<BlockPos> lower = capList(ctx, room, SUPERTALL_LOWER_OBSIDIAN);
        int upperObs = countIs(ctx, upper, Blocks.OBSIDIAN);
        int lowerObs = countIs(ctx, lower, Blocks.OBSIDIAN);
        int princes = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(SIM_PRINCE, "count"));
        boolean upperPart = isPart(ctx, upper.get(0));
        boolean lowerPart = isPart(ctx, lower.get(0));
        say("1782 floor: " + princeLine + "; " + princes + " prince(s); upper obsidian " + upperObs + "/" + upper.size()
                + " a prince " + upperPart + ", lower obsidian " + lowerObs + "/" + lower.size() + " a prince " + lowerPart);
        check(upperObs == upper.size() && lowerObs == lower.size(), "Supertall's obsidian is not on the floor where expected");
        check(upperPart && lowerPart, "the floor scan did not register both of Supertall's obsidian tombs (upper "
                + upperPart + ", lower " + lowerPart + ")");
        check(princes >= 2, "the floor has " + princes + " prince(s), Supertall alone has 2");
    }

    /**
     * 1783: the obsidian signature against the whole library. Only a room whose palette holds obsidian can match it, so
     * every such room is built by itself and its princes that contain obsidian are counted. They must be exactly
     * Supertall 2, Withermancer 1, Grass Ruin 1, Pirate 1, Waterfall 1 (census 2026-10-16) - no other room.
     */
    private void casePrinceObsidianCensus(ClientGameTestContext ctx) {
        java.util.Map<String, Integer> expected = new java.util.TreeMap<>(java.util.Map.of("Supertall", 2,
                "Withermancer", 1, "Grass Ruin", 1, "Pirate", 1, "Waterfall", 1));
        List<String> withObsidian = ctx.computeOnClient(mc -> {
            List<String> out = new ArrayList<>();
            @SuppressWarnings("unchecked")
            List<String> names = (List<String>) ModUnderTest.staticCall(ROOM_LIBRARY, "names");
            for (String n : names) {
                Object r = ModUnderTest.staticCall(ROOM_LIBRARY, "get", new Class<?>[]{String.class}, new Object[]{n});
                try {
                    @SuppressWarnings("unchecked")
                    List<String> pal = (List<String>) r.getClass().getField("palette").get(r);
                    if (pal.stream().anyMatch(p -> p.startsWith("minecraft:obsidian"))) {
                        out.add(n);
                    }
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }
            }
            return out;
        });
        say("1783 rooms with obsidian in their palette (" + withObsidian.size() + "): " + withObsidian);
        java.util.Map<String, Integer> found = new java.util.TreeMap<>();
        List<String> unusable = new ArrayList<>();
        for (String n : withObsidian) {
            boolean usable = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isUsable",
                    new Class<?>[]{String.class}, new Object[]{n}));
            if (!usable) {
                unusable.add(n);
                continue;
            }
            build(ctx, n);
            // The floor scan reads only chunks already loaded (SimPrince.scan's javadoc; a Superboom's scanNear covers
            // the rest), and a second build of a 2x2 left half of Supertall unloaded on the first run (1 of 2 found).
            // This case is about the SIGNATURE, so every chunk of the room is loaded and the scan asked again.
            int[] size = ctx.computeOnClient(mc -> {
                Object r = ModUnderTest.staticCall(ROOM_LIBRARY, "get", new Class<?>[]{String.class}, new Object[]{n});
                try {
                    return new int[]{r.getClass().getField("sizeX").getInt(r), r.getClass().getField("sizeZ").getInt(r)};
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }
            });
            BlockPos ca = capW(ctx, n, 0, 100, 0);
            BlockPos cb = capW(ctx, n, size[0] - 1, 100, size[1] - 1);
            int rescanned = onServer(ctx, (level, sp) -> {
                for (int cx = Math.min(ca.getX(), cb.getX()) >> 4; cx <= Math.max(ca.getX(), cb.getX()) >> 4; cx++) {
                    for (int cz = Math.min(ca.getZ(), cb.getZ()) >> 4; cz <= Math.max(ca.getZ(), cb.getZ()) >> 4; cz++) {
                        level.getChunk(cx, cz);
                    }
                }
                return (Integer) ModUnderTest.staticCall(SIM_PRINCE, "scan", new Class<?>[]{ServerLevel.class},
                        new Object[]{level});
            });
            say("1783 " + n + ": " + rescanned + " prince(s) of any kind with the whole room loaded");
            int obsidianPrinces = onServer(ctx, (level, sp) -> {
                int k = 0;
                @SuppressWarnings("unchecked")
                List<BlockPos> seeds = (List<BlockPos>) ModUnderTest.staticCall(SIM_PRINCE, "positions");
                for (BlockPos seed : seeds) {
                    @SuppressWarnings("unchecked")
                    List<BlockPos> blocks = (List<BlockPos>) ModUnderTest.staticCall(SIM_PRINCE, "blocksOf",
                            new Class<?>[]{BlockPos.class}, new Object[]{seed});
                    for (BlockPos b : blocks) {
                        if (level.getBlockState(b).is(Blocks.OBSIDIAN)) {
                            k++;
                            break;
                        }
                    }
                }
                return k;
            });
            say("1783 " + n + ": " + obsidianPrinces + " obsidian prince(s)");
            if (obsidianPrinces > 0) {
                found.put(n, obsidianPrinces);
            }
        }
        say("1783 obsidian princes by room: " + found + (unusable.isEmpty() ? "" : ", not buildable " + unusable));
        for (String n : expected.keySet()) {
            check(!unusable.contains(n), n + " cannot be built, so the census cannot check it");
        }
        check(withObsidian.containsAll(expected.keySet()), "a room the signature should match has no obsidian: "
                + withObsidian);
        check(found.equals(expected), "the obsidian signature matched " + found + ", the census says " + expected);
    }

    private static List<BlockPos> capList(ClientGameTestContext ctx, String room, int[][] cs) {
        List<BlockPos> out = new ArrayList<>();
        for (int[] c : cs) {
            out.add(capW(ctx, room, c[0], c[1], c[2]));
        }
        return out;
    }

    private static boolean isPart(ClientGameTestContext ctx, BlockPos p) {
        return ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SIM_PRINCE, "isPart",
                new Class<?>[]{BlockPos.class}, new Object[]{p}));
    }

    private static boolean isPrince(ClientGameTestContext ctx, BlockPos p) {
        return ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SIM_PRINCE, "isPrince",
                new Class<?>[]{BlockPos.class}, new Object[]{p}));
    }

    /** Waits until he stands on a block, as {@link #build} does. */
    private static void waitStanding(ClientGameTestContext ctx, String where) {
        for (int t = 0; t < 1800; t++) {
            boolean ok = ctx.computeOnClient(mc -> mc.player != null && mc.player.onGround()
                    && !mc.level.getBlockState(mc.player.blockPosition().below()).isAir());
            if (ok) {
                return;
            }
            ctx.waitTicks(1);
        }
        check(false, "the client never stood in " + where);
    }

    // ============================================================================================ helpers

    /** Builds one room on its own (from the title screen or in the open sim) and waits until he stands in it. */
    private static void build(ClientGameTestContext ctx, String room) {
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> ModUnderTest.staticCall(BUILDER, "buildSingleRoom",
                new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, room})));
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
        check(landed >= 0, "the client never stood in " + room);
        ctx.waitTicks(20);
        playerId = ctx.computeOnClient(mc -> mc.player.getUUID());
        builtRoom = room;
        say("built " + room + " (stood after " + landed + " ticks)");
    }

    /**
     * A clear 21 x 21 stone-floored box round where he stands (floor and 3 below stone, 6 above air), and him on its
     * centre facing +z. Returns his feet block.
     */
    private static BlockPos arena(ClientGameTestContext ctx) {
        // At the room's middle column (capture 16, 16) and his own height, so everything in the box is in the room.
        BlockPos mid = capW(ctx, builtRoom, 16, 0, 16);
        BlockPos feet = onServer(ctx, (level, sp) -> {
            BlockPos f = new BlockPos(mid.getX(), sp.blockPosition().getY(), mid.getZ());
            for (int x = -10; x <= 10; x++) {
                for (int z = -10; z <= 10; z++) {
                    for (int y = -4; y <= 6; y++) {
                        level.setBlock(f.offset(x, y, z), y < 0 ? Blocks.STONE.defaultBlockState()
                                : Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
            return f;
        });
        ctx.waitTicks(10);
        tp(ctx, feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0f, 0f);
        ctx.waitTicks(10);
        return feet;
    }

    /** Server teleport to world feet (x, y, z) with this yaw/pitch, the client turned the same way. */
    private static void tp(ClientGameTestContext ctx, double x, double y, double z, float yaw, float pitch) {
        onServer(ctx, (level, sp) -> {
            sp.teleportTo(level, x, y, z, java.util.Set.<net.minecraft.world.entity.Relative>of(), yaw, pitch, false);
            return null;
        });
        ctx.runOnClient(mc -> {
            mc.player.setYRot(yaw);
            mc.player.setXRot(pitch);
        });
        ctx.waitTicks(5);
    }

    /**
     * Stands him on the first spot near capture column (cx, cz) - scanning down from yHint + 4 - with a solid block
     * under his feet and air for both, trying the column and then its neighbours, and turns him to look at the capture
     * point {@code look}.
     */
    private static void stand(ClientGameTestContext ctx, String room, int cx, int cz, int yHint, double[] look) {
        BlockPos spot = null;
        int[][] tries = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {2, 0}, {-2, 0}, {0, 2}, {0, -2}};
        for (int[] d : tries) {
            for (int y = yHint + 4; y >= yHint - 6 && spot == null; y--) {
                BlockPos feet = capW(ctx, room, cx + d[0], y, cz + d[1]);
                boolean ok = onServer(ctx, (level, sp) -> level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                        && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
                        && !level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty());
                if (ok) {
                    spot = feet;
                }
            }
            if (spot != null) {
                break;
            }
        }
        check(spot != null, "no standing spot near capture " + cx + "," + cz + " in " + room);
        BlockPos at = spot;
        // The look point's block through the same transform, aimed at its centre (the room may be turned, so the
        // fraction of x/z inside the block is not carried over - y is never turned).
        BlockPos lookBlock = capW(ctx, room, (int) Math.floor(look[0]), (int) Math.floor(look[1]), (int) Math.floor(look[2]));
        double wx = lookBlock.getX() + 0.5;
        double wz = lookBlock.getZ() + 0.5;
        double wy = lookBlock.getY() + (look[1] - Math.floor(look[1]));
        double ex = at.getX() + 0.5;
        double ey = at.getY() + 1.62;
        double ez = at.getZ() + 0.5;
        double dx = wx - ex;
        double dy = wy - ey;
        double dz = wz - ez;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        tp(ctx, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, yaw, pitch);
        ctx.waitTicks(15);
    }

    /** World position of a capture-local block of the built room (SimRoomLevers.capturedWorld, the rules' transform). */
    private static BlockPos capW(ClientGameTestContext ctx, String room, int x, int y, int z) {
        BlockPos p = ctx.computeOnClient(mc -> (BlockPos) ModUnderTest.staticCall(SIM_LEVERS, "capturedWorld",
                new Class<?>[]{String.class, int.class, int.class, int.class}, new Object[]{room, x, y, z}));
        check(p != null, room + " is not placed");
        return p;
    }

    private static String blockName(ClientGameTestContext ctx, BlockPos p) {
        return onServer(ctx, (level, sp) -> net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(level.getBlockState(p).getBlock()).getPath());
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

    private static int countIs(ClientGameTestContext ctx, List<BlockPos> ps, net.minecraft.world.level.block.Block b) {
        return onServer(ctx, (level, sp) -> {
            int n = 0;
            for (BlockPos p : ps) {
                if (level.getBlockState(p).is(b)) {
                    n++;
                }
            }
            return n;
        });
    }

    private static void shot(ClientGameTestContext ctx, String name) {
        Path taken = ctx.takeScreenshot(dev.testkit.harness.Report.fileName(name));
        Path kept = dev.testkit.harness.Report.screenshot(name, taken);
        say("screenshot " + name + ": " + kept);
    }

    /** Runs on the integrated server thread (never a join from here - see the testkit's deadlock note) and returns. */
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

    private static void teardown(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            try {
                ModUnderTest.staticCall(AWAIT, "end", new Class<?>[]{}, new Object[]{});
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

    /**
     * killer560's to-do list, 2026-10-18 (mod sim-secrets-1018):
     * <ul>
     *   <li><b>1018-sim-prince-sarcophagus</b>: "this is a prince in sarcophogous". Sarcophagus' one tomb (capture x 14..18,
     *       y 69..71, z 15..17: five bottom slabs and two heads on a polished andesite body on stair legs) is the room's
     *       one prince; one Superboom on its slabs opens it as a prince and takes every block of it, and the room floor
     *       under it (y 68) stays. On main: no prince, and the slab crypt rule left the andesite standing.</li>
     *   <li><b>1018-sim-keyspot-one</b>: Redstone Key's database has two key spots; the sim puts the key on ONE of them
     *       (each forced in turn), as Hypixel does. On main: a key on both.</li>
     *   <li><b>1018-sim-chest-appear</b>: "Make chests on sim have the same spawn in logic as bats for not appearing" - a
     *       secret chest more than 10 blocks from him is not in the world; standing beside it, it appears 5 server ticks
     *       later (the bat rule). On main: every secret chest is there from the build.</li>
     *   <li><b>1018-sim-changeroom-lastpage</b>: "if i press escape then change room after I have generated a map it always
     *       takes me back to load room. It should take me to the setting I selected previously." The pause screen's Change
     *       Room opens the sim menu on the page used last (designer, Previous Runs, Route Practice), and Load a Room when
     *       none was used yet. On main: Load a Room every time.</li>
     * </ul>
     */
    static final class SimSecrets1018 {
        private SimSecrets1018() {
        }

        private static final String SIM_SECRETS = "com.killer560.hub.roomsim.SimSecrets";
        private static final String NAV = "com.killer560.hub.roomsim.SimMenuNav";
        private static final String MENU = "com.killer560.hub.roomsim.SimMenuScreen";
        private static final String DESIGNER = "com.killer560.hub.roomsim.SimMapEditorScreen";

        static void princeSarcophagus(ClientGameTestContext ctx) {
            String room = "Sarcophagus";
            build(ctx, room);
            List<BlockPos> tomb = new ArrayList<>();
            List<BlockPos> floor = new ArrayList<>();
            for (int x = 14; x <= 18; x++) {
                for (int z = 15; z <= 17; z++) {
                    for (int y = 69; y <= 71; y++) {
                        BlockPos p = capW(ctx, room, x, y, z);
                        if (!onServer(ctx, (level, sp) -> level.getBlockState(p).isAir())) {
                            tomb.add(p);
                        }
                    }
                    floor.add(capW(ctx, room, x, 68, z));
                }
            }
            BlockPos slab = capW(ctx, room, 16, 71, 16);
            BlockPos body = capW(ctx, room, 14, 70, 15);
            int princes = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(SIM_PRINCE, "count"));
            boolean slabPart = isPart(ctx, slab);
            boolean bodyPart = isPart(ctx, body);
            int floorBefore = countNotAir(ctx, floor);
            say("1018 sarcophagus: tomb " + tomb.size() + " block(s), slab " + blockName(ctx, slab) + ", body "
                    + blockName(ctx, body) + "; " + princes + " prince(s); slab a prince " + slabPart + ", body a prince "
                    + bodyPart + "; floor under it " + floorBefore + "/" + floor.size());
            check(tomb.size() >= 35 && blockName(ctx, slab).equals("smooth_stone_slab")
                    && blockName(ctx, body).equals("polished_andesite"), "premise: the capture's tomb is not where the census put it");
            check(princes == 1, "Sarcophagus has " + princes + " prince(s); its one tomb is the prince");
            check(slabPart && bodyPart, "the tomb's slabs (" + slabPart + ") or its andesite body (" + bodyPart
                    + ") are not part of the prince");
            stand(ctx, room, 16, 13, 69, new double[]{16.5, 71.5, 16.5});
            int undeadBefore = zombies(ctx, slab, 4);
            String line = boom(ctx, slab, Direction.UP);
            int left = countNotAir(ctx, tomb);
            int floorAfter = countNotAir(ctx, floor);
            int undeadAfter = zombies(ctx, slab, 4);
            say("1018 sarcophagus after one Superboom: tomb blocks left " + left + "/" + tomb.size() + ", floor "
                    + floorAfter + "/" + floor.size() + ", undead " + undeadBefore + " -> " + undeadAfter + "; " + line);
            check(line.contains("prince=true"), "the tomb did not open as a prince: " + line);
            check(left == 0, left + " block(s) of the sarcophagus survived the Superboom");
            check(floorAfter == floorBefore, "the blast took " + (floorBefore - floorAfter) + " block(s) of the room's floor");
            check(undeadAfter > undeadBefore, "no undead appeared in the opened prince");
        }

        private static boolean setKeySpot(ClientGameTestContext ctx, int idx) {
            return ctx.computeOnClient(mc -> {
                try {
                    Class.forName(SIM_SECRETS).getMethod("setKeySpotForTest", int.class).invoke(null, idx);
                    return true;
                } catch (ReflectiveOperationException e) {
                    return false;
                }
            });
        }

        @SuppressWarnings("unchecked")
        private static List<BlockPos> keysLying(ClientGameTestContext ctx) {
            return onServer(ctx, (level, sp) -> {
                try {
                    var f = Class.forName(SIM_KEY).getDeclaredField("KEYS");
                    f.setAccessible(true);
                    return new ArrayList<>(((java.util.Map<BlockPos, String>) f.get(null)).keySet());
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
        }

        static void keySpotOne(ClientGameTestContext ctx) {
            String room = "Redstone Key";
            List<BlockPos> seen = new ArrayList<>();
            try {
                for (int idx = 0; idx <= 1; idx++) {
                    boolean hook = setKeySpot(ctx, idx);
                    build(ctx, room);
                    List<BlockPos> keys = keysLying(ctx);
                    int onClient = 0;
                    for (BlockPos k : keys) {
                        onClient += isKeyOnClient(ctx, k) ? 1 : 0;
                    }
                    say("1018 keyspot: forced spot " + idx + " (hook " + hook + "): " + keys.size() + " key(s) lying at "
                            + keys + ", " + onClient + " a Redstone Key head on the client");
                    check(keys.size() == 1, room + " has " + keys.size() + " Redstone Key(s) lying - Hypixel spawns one, in"
                            + " one of its two spots");
                    check(onClient == 1, "the client does not see the key as Hypixel's Redstone Key head");
                    seen.add(keys.get(0));
                }
                check(!seen.get(0).equals(seen.get(1)), "both forced spots put the key at " + seen.get(0));
            } finally {
                setKeySpot(ctx, -1);
            }
        }

        @SuppressWarnings("unchecked")
        private static List<BlockPos> placedChests(ClientGameTestContext ctx) {
            return onServer(ctx, (level, sp) -> {
                try {
                    return new ArrayList<>((List<BlockPos>) Class.forName(SIM_SECRETS).getField("PLACED_CHESTS").get(null));
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
        }

        static void chestAppear(ClientGameTestContext ctx) {
            SimChestHook.restore(ctx);   // the mod's own rule, whatever a scenario before this one left
            BlockPos far = null;
            String inRoom = null;
            for (String room : new String[]{"Mossy", "Gravel", "Andesite", "Sarcophagus", "Cathedral"}) {
                boolean usable = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isUsable",
                        new Class<?>[]{String.class}, new Object[]{room}));
                if (!usable) {
                    continue;
                }
                build(ctx, room);
                Vec3 at = ctx.computeOnClient(mc -> mc.player.position());
                List<BlockPos> chests = placedChests(ctx);
                for (BlockPos c : chests) {
                    if (Vec3.atCenterOf(c).distanceTo(at) > 13) {
                        far = c;
                        break;
                    }
                }
                say("1018 chest: " + room + " has " + chests.size() + " secret chest(s), " + (far == null ? "none" : "one at "
                        + far.toShortString()) + " more than 13 blocks from him");
                if (far != null) {
                    inRoom = room;
                    break;
                }
            }
            check(far != null, "premise: no room tried has a secret chest more than 13 blocks from the spawn");
            BlockPos c = far;
            String before = blockName(ctx, c);
            boolean clientChest = ctx.computeOnClient(mc -> mc.level.getBlockState(c).is(Blocks.CHEST));
            say("1018 chest at " + c.toShortString() + " in " + inRoom + " while he is far: server " + before
                    + ", client chest " + clientChest);
            check(!before.equals("chest") && !before.equals("trapped_chest") && !clientChest,
                    "the secret chest at " + c.toShortString() + " exists while he is more than 13 blocks from it");
            // A standing spot beside it: feet and head free, solid under, within 3 blocks across and 2 up or down.
            BlockPos spot = onServer(ctx, (level, sp) -> {
                for (int r = 1; r <= 3; r++) {
                    for (int dx = -r; dx <= r; dx++) {
                        for (int dz = -r; dz <= r; dz++) {
                            for (int dy = -2; dy <= 2; dy++) {
                                BlockPos f = c.offset(dx, dy, dz);
                                if (f.equals(c) || f.equals(c.above())) {
                                    continue;
                                }
                                if (level.getBlockState(f).getCollisionShape(level, f).isEmpty()
                                        && level.getBlockState(f.above()).getCollisionShape(level, f.above()).isEmpty()
                                        && !level.getBlockState(f.below()).getCollisionShape(level, f.below()).isEmpty()) {
                                    return f;
                                }
                            }
                        }
                    }
                }
                return null;
            });
            check(spot != null, "premise: no standing spot within 3 blocks of the chest at " + c.toShortString());
            long t0 = onServer(ctx, (level, sp) -> {
                sp.teleportTo(level, spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5,
                        java.util.Set.<net.minecraft.world.entity.Relative>of(), 0f, 0f, false);
                return level.getGameTime();
            });
            long appearedAt = -1;
            for (int i = 0; i < 40 && appearedAt < 0; i++) {
                ctx.waitTicks(1);
                appearedAt = onServer(ctx, (level, sp) -> level.getBlockState(c).is(Blocks.CHEST)
                        || level.getBlockState(c).is(Blocks.TRAPPED_CHEST) ? level.getGameTime() : -1L);
            }
            ctx.waitTicks(5);
            boolean clientAfter = ctx.computeOnClient(mc -> mc.level.getBlockState(c).is(Blocks.CHEST)
                    || mc.level.getBlockState(c).is(Blocks.TRAPPED_CHEST));
            say("1018 chest appeared " + (appearedAt - t0) + " server tick(s) after he came within range (seen on the poll);"
                    + " client sees it " + clientAfter);
            check(appearedAt >= 0, "the secret chest never appeared once he stood beside it");
            check(appearedAt - t0 >= 5 && appearedAt - t0 <= 7, "the chest appeared " + (appearedAt - t0)
                    + " tick(s) after he came within range - want 5, as a secret bat");
            check(clientAfter, "the client never saw the chest");
        }

        private static Object page(String name) {
            return ModUnderTest.enumValue(NAV + "$Page", name);
        }

        /** Shows {@code pageName} as the menu's last page (null: forget the session), then presses Change Room. */
        private static String changeRoom(ClientGameTestContext ctx, String pageName) {
            ctx.runOnClient(mc -> {
                if (pageName == null) {
                    ModUnderTest.staticCall(NAV, "forgetSession", new Class<?>[]{}, new Object[]{});
                } else {
                    ModUnderTest.staticCall(NAV, "shown", new Class<?>[]{page(pageName).getClass()},
                            new Object[]{page(pageName)});
                }
                McCompat.setScreen(mc, new net.minecraft.client.gui.screens.PauseScreen(true));
            });
            ctx.waitFor(mc -> McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.PauseScreen, 200);
            ctx.waitTicks(3);
            String pressed = ctx.computeOnClient(mc -> {
                var s = McCompat.screen(mc);
                for (var child : s.children()) {
                    if (child instanceof net.minecraft.client.gui.components.AbstractWidget w
                            && "Change Room".equals(w.getMessage().getString())) {
                        double x = w.getX() + w.getWidth() / 2.0;
                        double y = w.getY() + w.getHeight() / 2.0;
                        s.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(x, y,
                                new net.minecraft.client.input.MouseButtonInfo(0, 0)), false);
                        s.mouseReleased(new net.minecraft.client.input.MouseButtonEvent(x, y,
                                new net.minecraft.client.input.MouseButtonInfo(0, 0)));
                        return "ok";
                    }
                }
                return "no Change Room button";
            });
            check("ok".equals(pressed), pressed);
            ctx.waitFor(mc -> !(McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.PauseScreen), 200);
            ctx.waitTicks(3);
            String opened = ctx.computeOnClient(mc -> {
                var s = McCompat.screen(mc);
                if (s == null) {
                    return "none";
                }
                String cls = s.getClass().getName();
                if (cls.equals(MENU)) {
                    try {
                        var f = s.getClass().getDeclaredField("mode");
                        f.setAccessible(true);
                        return "menu:" + f.get(s);
                    } catch (ReflectiveOperationException e) {
                        return "menu:?";
                    }
                }
                return cls.equals(DESIGNER) ? "designer" : cls;
            });
            ctx.runOnClient(mc -> McCompat.setScreen(mc, null));
            ctx.waitFor(mc -> McCompat.screen(mc) == null, 200);
            return opened;
        }

        static void changeRoomLastPage(ClientGameTestContext ctx) {
            build(ctx, "Mossy");
            String designer = changeRoom(ctx, "DESIGNER");
            String previous = changeRoom(ctx, "PREVIOUS");
            String cycle = changeRoom(ctx, "CYCLE");
            String room = changeRoom(ctx, "ROOM");
            String fresh = changeRoom(ctx, null);
            say("1018 Change Room opened: last page designer -> " + designer + ", Previous Runs -> " + previous
                    + ", Route Practice -> " + cycle + ", Load a Room -> " + room + ", none yet -> " + fresh);
            check("designer".equals(designer), "with the designer used last (a map generated), Change Room opened "
                    + designer + " - want the designer");
            check("menu:PREVIOUS".equals(previous), "with Previous Runs used last, Change Room opened " + previous);
            check("menu:CYCLE".equals(cycle), "with Route Practice used last, Change Room opened " + cycle);
            check("menu:ROOM".equals(room), "with Load a Room used last, Change Room opened " + room);
            check("menu:ROOM".equals(fresh), "with no page used yet, Change Room opened " + fresh + " - want Load a Room");
        }
    }
}
