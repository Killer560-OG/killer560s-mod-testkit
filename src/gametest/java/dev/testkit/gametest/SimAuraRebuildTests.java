package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.harness.PacketTrace;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 99-sim-aura-rebuild: does Secret Aura still click a room's secrets after he rebuilds the SAME room in the same sim
 * world? Only when named.
 *
 * <p>killer560 (2026-10-06, Map Logger, his sim): "I would regenerate the same room I had a route in and the secret
 * aura wasn't working really after the first run through." Secret Aura keeps a done-set of block positions, and a
 * rebuilt room puts its chests and levers back on the very same blocks; on the mod before aura-rebuild that set was
 * only cleared on a world change or on leaving the dungeon, and a rebuild is neither.
 *
 * <p>The room is loaded his way ({@code SimBuilder.buildSingleRoom}, from the title screen the first time, then twice
 * more from inside the world - what a re-load of the room does). On each build the player is put (server teleport,
 * aura OFF) in reach of one secret chest and then one lever, and Secret Aura goes on with only that kind enabled. The
 * verdict is the integrated SERVER's own record: a use on that exact block from this player. The premise is
 * asserted on every build - the same chest and the same lever back on the same blocks, the client having them - so a
 * build that put nothing there cannot read as "the aura clicked nothing wrong".
 */
public class SimAuraRebuildTests implements FabricClientGameTest {

    private static final String NAME = "99-sim-aura-rebuild";
    private static final String ROOM = System.getenv().getOrDefault("TESTKIT_AURA_ROOM", "Museum");
    private static final int BUILDS = 3;
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String BUILDER = "com.killer560.hub.roomsim.SimBuilder";
    private static final String SIM_SECRETS = "com.killer560.hub.roomsim.SimSecrets";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String LIVE_MAP = "com.killer560.hub.livemap.LiveMapFeature";
    private static final String CHEAT_CFG = "com.killer560.hub.cheatutils.CheatUtilsConfig";

    private static final String SOURCE_ROOMS =
            ModUnderTest.instanceConfig("C:/Users/Hunter/AppData/Roaming/PrismLauncher/instances/"
                    + SimEssenceAuraTests.ROOM_INSTANCE + "/minecraft/config", "killer560smod-rooms");

    /** Every right click the integrated server handled, as "x,y,z" - fed by a server-side UseBlockCallback. */
    private static final Set<String> SERVER_USES = ConcurrentHashMap.newKeySet();
    private static boolean hooked;

    /** One secret this scenario clicks on every build: its block, where to stand, and which aura option it needs. */
    private record Target(String kind, BlockPos pos, double[] stand) {
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank() || Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        auraOff(ctx);
        if (copyDir(SOURCE_ROOMS, "killer560smod-rooms", ".json") < 20
                || copyDir(Path.of(SOURCE_ROOMS).resolveSibling("killer560smod-roomdata").toString(),
                        "killer560smod-roomdata", "") == 0) {
            println("SKIPPED - needs his room captures and room database (" + SimEssenceAuraTests.ROOM_INSTANCE + ")");
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);
        hookServerUses();

        List<String> verdicts = new ArrayList<>();
        try {
            List<Target> targets = null;
            for (int build = 1; build <= BUILDS; build++) {
                String tag = "build " + build + " of " + ROOM + (build == 1 ? " (from the title screen)"
                        : " (rebuilt in the same world)");
                int genBefore = generation(ctx);
                Object levelBefore = ctx.computeOnClient(mc -> (Object) mc.level);
                build(ctx);
                int genAfter = generation(ctx);
                boolean sameWorld = ctx.computeOnClient(mc -> mc.level == levelBefore);
                println(tag + ": live map reset generation " + genBefore + " -> " + genAfter + ", same client world "
                        + sameWorld + ", sim active " + ModUnderTest.staticCall(SIM_STATE, "isActive"));
                if (build > 1 && !sameWorld) {
                    verdicts.add(tag + ": FAIL premise - the rebuild opened a new world, so it is not his case");
                    continue;
                }
                if (targets == null) {
                    targets = pickTargets(ctx);
                    if (targets.isEmpty()) {
                        verdicts.add(tag + ": FAIL premise - no secret chest and no lever with a floor block in reach");
                        break;
                    }
                    for (Target t : targets) {
                        println("target " + t.kind() + " at " + t.pos().toShortString() + ", standing at "
                                + String.format(Locale.ROOT, "%.1f %.1f %.1f", t.stand()[0], t.stand()[1], t.stand()[2]));
                    }
                }
                for (Target t : targets) {
                    verdicts.add(visit(ctx, tag, t));
                }
            }
        } finally {
            auraOff(ctx);
            leave(ctx);
        }
        verdicts.forEach(this::println);
        long passed = verdicts.stream().filter(v -> v.contains(": PASS")).count();
        List<String> failed = verdicts.stream().filter(v -> !v.contains(": PASS")).toList();
        // Every build visited every target, and a chest was among them - otherwise nothing was measured.
        boolean chestMeasured = verdicts.stream().anyMatch(v -> v.contains(", chest at "));
        if (!failed.isEmpty() || passed < 2L * BUILDS - BUILDS || !chestMeasured) {
            throw new AssertionError(failed.isEmpty() ? "only " + passed + " passing visit(s), chest measured "
                    + chestMeasured : String.join(" | ", failed));
        }
        println("PASS - Secret Aura clicked the same chest and lever on all " + BUILDS + " builds of " + ROOM);
    }

    private void build(ClientGameTestContext ctx) {
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> ModUnderTest.staticCall(BUILDER, "buildSingleRoom",
                new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, ROOM})));
        ctx.waitFor(mc -> mc.level != null && mc.player != null, 2400);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitFor(mc -> McCompat.screen(mc) == null, 1200);
        // The secrets go in from the build's completion callback, the map from a client task after that.
        ctx.waitTicks(40);
        ctx.waitFor(mc -> mc.player != null && mc.player.onGround()
                && !mc.level.getBlockState(mc.player.blockPosition().below()).isAir(), 1800);
        ctx.waitTicks(20);
    }

    /** A secret chest and a lever of the room, each with a standable floor block in reach - picked on the SERVER. */
    private List<Target> pickTargets(ClientGameTestContext ctx) {
        AtomicReference<List<Target>> out = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                List<Target> list = new ArrayList<>();
                try {
                    ServerLevel level = server.overworld();
                    @SuppressWarnings("unchecked")
                    List<BlockPos> chests = new ArrayList<>((java.util.Collection<BlockPos>) Class.forName(SIM_SECRETS)
                            .getField("PLACED_CHESTS").get(null));
                    chests.sort(java.util.Comparator.comparingLong(BlockPos::asLong));
                    for (BlockPos c : chests) {
                        if (level.getBlockState(c).getBlock() != Blocks.CHEST) {
                            continue; // a trapped chest is the mimic's - it moves between builds
                        }
                        double[] stand = standNear(level, c);
                        if (stand != null) {
                            list.add(new Target("chest", c.immutable(), stand));
                            break;
                        }
                    }
                    if (!chests.isEmpty()) {
                        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE;
                        int x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
                        for (BlockPos c : chests) {
                            x0 = Math.min(x0, c.getX()); y0 = Math.min(y0, c.getY()); z0 = Math.min(z0, c.getZ());
                            x1 = Math.max(x1, c.getX()); y1 = Math.max(y1, c.getY()); z1 = Math.max(z1, c.getZ());
                        }
                        List<BlockPos> levers = new ArrayList<>();
                        for (BlockPos p : BlockPos.betweenClosed(x0 - 16, y0 - 12, z0 - 16, x1 + 16, y1 + 12, z1 + 16)) {
                            if (level.getBlockState(p).getBlock() == Blocks.LEVER) {
                                levers.add(p.immutable());
                            }
                        }
                        levers.sort(java.util.Comparator.comparingLong(BlockPos::asLong));
                        for (BlockPos l : levers) {
                            double[] stand = standNear(level, l);
                            if (stand != null) {
                                list.add(new Target("lever", l, stand));
                                break;
                            }
                        }
                    }
                } catch (Throwable t) {
                    System.out.println("[" + NAME + "] target pick threw " + t);
                }
                out.set(list);
            });
        });
        ctx.waitFor(mc -> out.get() != null, 400);
        return out.get();
    }

    private static double[] standNear(ServerLevel level, BlockPos e) {
        double[] best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -3; dy <= 1; dy++) {
                    BlockPos floor = e.offset(dx, dy, dz);
                    BlockPos feet = floor.above();
                    if (feet.equals(e) || feet.above().equals(e)) {
                        continue;
                    }
                    if (level.getBlockState(floor).getCollisionShape(level, floor).isEmpty()
                            || !level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                            || !level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
                            || level.getBlockState(feet).getBlock() == Blocks.LEVER
                            || level.getBlockState(feet.above()).getBlock() == Blocks.LEVER) {
                        continue;
                    }
                    double ex = feet.getX() + 0.5;
                    double ey = feet.getY() + 1.62;
                    double ez = feet.getZ() + 0.5;
                    double cx = Math.max(e.getX(), Math.min(ex, e.getX() + 1));
                    double cy = Math.max(e.getY(), Math.min(ey, e.getY() + 1));
                    double cz = Math.max(e.getZ(), Math.min(ez, e.getZ() + 1));
                    double d = Math.sqrt((ex - cx) * (ex - cx) + (ey - cy) * (ey - cy) + (ez - cz) * (ez - cz));
                    if (d < 1.2 || d > 3.5) {
                        continue;
                    }
                    if (d < bestD) {
                        bestD = d;
                        best = new double[]{ex, feet.getY(), ez};
                    }
                }
            }
        }
        return best;
    }

    private String visit(ClientGameTestContext ctx, String tag, Target t) {
        String who = tag + ", " + t.kind() + " at " + t.pos().toShortString();
        // Premise: the same secret back on the same block, on the server.
        AtomicReference<String> serverBlock = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                serverBlock.set(server.overworld().getBlockState(t.pos()).getBlock().toString());
                ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
                if (sp != null) {
                    sp.teleportTo(t.stand()[0], t.stand()[1], t.stand()[2]);
                }
            });
        });
        ctx.waitFor(mc -> serverBlock.get() != null, 200);
        String want = "chest".equals(t.kind()) ? "chest" : "lever";
        if (!serverBlock.get().contains(want)) {
            return who + ": FAIL premise - the server has " + serverBlock.get() + " there after the build";
        }
        boolean clientHasIt;
        try {
            ctx.waitFor(mc -> mc.player != null && mc.player.onGround()
                    && Math.abs(mc.player.getX() - t.stand()[0]) < 0.6 && Math.abs(mc.player.getZ() - t.stand()[2]) < 0.6
                    && mc.level.getBlockState(t.pos()).getBlock().toString().contains(want), 600);
            clientHasIt = true;
        } catch (Throwable e) {
            clientHasIt = false;
        }
        String clientBlock = ctx.computeOnClient(mc -> describe(mc.level.getBlockState(t.pos())));
        if (!clientHasIt) {
            return who + ": FAIL premise - the client never stood in reach of it (client sees " + clientBlock + ")";
        }
        ctx.waitTicks(10);

        SERVER_USES.clear();
        ctx.runOnClient(mc -> {
            PacketTrace.start();
            Object cfg = ModUnderTest.config(CHEAT_CFG);
            ModUnderTest.set(cfg, "setAuraChests", "chest".equals(t.kind()));
            ModUnderTest.set(cfg, "setAuraLevers", "lever".equals(t.kind()));
            ModUnderTest.set(cfg, "setAuraEssence", false);
            ModUnderTest.set(cfg, "setAuraPauseWhileSneaking", false);
            ModUnderTest.set(cfg, "setSecretAuraEnabled", true);
        });
        String key = t.pos().getX() + "," + t.pos().getY() + "," + t.pos().getZ();
        int waited = 0;
        for (; waited < 100 && !SERVER_USES.contains(key); waited += 5) {
            ctx.waitTicks(5);
        }
        boolean clicked = SERVER_USES.contains(key);
        auraOff(ctx);
        ctx.waitTicks(5);
        int useItemOn = ctx.computeOnClient(mc -> {
            PacketTrace.stop();
            return PacketTrace.sent("use_item_on");
        });
        String after = ctx.computeOnClient(mc -> describe(mc.level.getBlockState(t.pos())));
        // A chest the sim opened leaves a container screen up; close it so the next visit's aura is not gated.
        ctx.runOnClient(mc -> {
            if (McCompat.screen(mc) != null && mc.player != null) {
                mc.player.closeContainer();
            }
        });
        ctx.waitTicks(10);
        String facts = "use_item_on sent " + useItemOn + ", server handled a click on it " + clicked
                + (clicked ? " after ~" + waited + " ticks" : " (waited " + waited + " ticks)")
                + ", client before " + clientBlock + ", after " + after;
        return who + (clicked ? ": PASS - " : ": FAIL - Secret Aura never clicked it - ") + facts;
    }

    private static String describe(BlockState s) {
        String name = s.getBlock().toString();
        if (s.getBlock() == Blocks.LEVER) {
            return name + "[powered=" + s.getValue(net.minecraft.world.level.block.LeverBlock.POWERED) + "]";
        }
        return name;
    }

    private static int generation(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(LIVE_MAP, "resetGeneration",
                new Class<?>[]{}, new Object[]{}));
    }

    private static void hookServerUses() {
        if (hooked) {
            return;
        }
        hooked = true;
        var phase = net.minecraft.resources.Identifier.fromNamespaceAndPath("testkit", "aura_rebuild_watch");
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.addPhaseOrdering(phase,
                net.fabricmc.fabric.api.event.Event.DEFAULT_PHASE);
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register(phase, (player, level, hand, hit) -> {
            if (!level.isClientSide() && player instanceof ServerPlayer && hit != null) {
                BlockPos b = hit.getBlockPos();
                SERVER_USES.add(b.getX() + "," + b.getY() + "," + b.getZ());
            }
            return InteractionResult.PASS;
        });
    }

    private static void auraOff(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            try {
                ModUnderTest.set(ModUnderTest.config(CHEAT_CFG), "setSecretAuraEnabled", false);
            } catch (Throwable ignored) {
                // cleanup never replaces a verdict
            }
        });
    }

    private static void leave(ClientGameTestContext ctx) {
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

    private void println(String line) {
        System.out.println("[" + NAME + "] " + line);
    }

    private static int copyDir(String from, String into, String suffix) {
        try {
            Path source = Path.of(from);
            if (!Files.isDirectory(source)) {
                return 0;
            }
            Path target = ModUnderTest.modConfig(into);
            Files.createDirectories(target);
            int n = 0;
            try (var s = Files.list(source)) {
                for (Path f : s.toList()) {
                    if (Files.isRegularFile(f) && f.toString().endsWith(suffix)) {
                        Files.copy(f, target.resolve(f.getFileName()),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        n++;
                    }
                }
            }
            return n;
        } catch (Exception e) {
            return 0;
        }
    }
}
