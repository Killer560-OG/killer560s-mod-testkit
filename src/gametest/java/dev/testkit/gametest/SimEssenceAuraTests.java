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
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 99-sim-essence-aura: does Secret Aura pick up a wither essence on a sim floor the moment he is next to one -
 * on the first world this client opens, and again after leaving and building a fresh floor? Only when named.
 *
 * <p>killer560 (2026-10-05): "secret aura isn't grabbing skulls when I just booted in." The sim is meant to send
 * the client what Hypixel sends, so the essence must be something Secret Aura recognises by Hypixel's own rule,
 * and its click must be a real {@code use_item_on} that the SERVER answers by taking the skull away.
 *
 * <p>Per visit: a fresh sim F7, the player put (server teleport, Secret Aura OFF) on a floor block within reach of
 * one essence the sim placed, and only once the CLIENT has that skull does Secret Aura go on. The verdict needs
 * all of: the integrated server saw a {@code use_item_on} on that exact block from this player; the server's block
 * there stopped being a skull (collected); and Auto Routes' await counter counted it as our own secret click. The
 * premise is asserted first - a skull at that block on the server, a skull at that block on the client - so a
 * floor with no essence in reach cannot pass as "the aura did nothing wrong".
 */
public class SimEssenceAuraTests implements FabricClientGameTest {

    private static final String NAME = "99-sim-essence-aura";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String SIM_SECRETS = "com.killer560.hub.roomsim.SimSecrets";
    private static final String CHEAT_CFG = "com.killer560.hub.cheatutils.CheatUtilsConfig";
    private static final String AWAIT = "com.killer560.hub.autoroutes.AwaitEvents";

    static final String ROOM_INSTANCE = System.getenv().getOrDefault("TESTKIT_SIM_INSTANCE", "Map Logger");
    private static final String SOURCE_ROOMS =
            ModUnderTest.instanceConfig("C:/Users/Hunter/AppData/Roaming/PrismLauncher/instances/" + ROOM_INSTANCE
                    + "/minecraft/config", "killer560smod-rooms");

    /** Every right click the integrated server handled, as "x,y,z" - fed by a server-side UseBlockCallback. */
    private static final Set<String> SERVER_USES = ConcurrentHashMap.newKeySet();
    private static boolean hooked;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank() || Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (copyDir(SOURCE_ROOMS, "killer560smod-rooms", ".json") < 20
                || copyDir(Path.of(SOURCE_ROOMS).resolveSibling("killer560smod-roomdata").toString(),
                        "killer560smod-roomdata", "") == 0) {
            System.out.println("[" + NAME + "] SKIPPED - needs his room captures and room database ("
                    + ROOM_INSTANCE + ")");
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);
        hookServerUses();

        List<String> verdicts = new ArrayList<>();
        try {
            verdicts.add(visit(ctx, 1, "first world after boot", "ASPECT_OF_THE_VOID"));
        } finally {
            auraOff(ctx);
            leave(ctx);
        }
        try {
            verdicts.add(visit(ctx, 2, "after leaving and building a new floor", "HYPERION"));
        } finally {
            auraOff(ctx);
            leave(ctx);
        }
        verdicts.forEach(v -> System.out.println("[" + NAME + "] " + v));
        List<String> failed = verdicts.stream().filter(v -> !v.contains(": PASS")).toList();
        if (!failed.isEmpty()) {
            throw new AssertionError(String.join(" | ", failed));
        }
        System.out.println("[" + NAME + "] PASS - Secret Aura collected a wither essence on both visits");
    }

    private static String visit(ClientGameTestContext ctx, int n, String what, String held) {
        String tag = "visit " + n + " (" + what + ", holding " + held + ")";
        auraOff(ctx);
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter",
                new Class<?>[]{String.class}, new Object[]{"gametest"}));
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
        ctx.waitTicks(20);

        // ---- pick an essence and a floor block within reach of it, on the SERVER (it has the whole floor) ---
        AtomicReference<Object[]> pick = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                try {
                    pick.set(pickEssence(server.overworld()));
                } catch (Throwable t) {
                    pick.set(new Object[]{null, null, "pick threw " + t});
                }
            });
        });
        ctx.waitFor(mc -> pick.get() != null, 200);
        Object[] p = pick.get();
        if (p[0] == null) {
            return tag + ": FAIL premise - " + p[2];
        }
        BlockPos skull = (BlockPos) p[0];
        double[] stand = (double[]) p[1];
        println(tag + ": essence at " + skull.toShortString() + " (" + p[2] + "), standing at "
                + String.format(java.util.Locale.ROOT, "%.1f %.1f %.1f", stand[0], stand[1], stand[2]));

        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
                if (sp != null) {
                    sp.teleportTo(stand[0], stand[1], stand[2]);
                }
            });
        });
        // What he holds in a run: an ability item. Given on the server into slot 0, selected on the client.
        AtomicReference<Boolean> given = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
                if (sp == null) {
                    given.set(false);
                    return;
                }
                sp.getInventory().setItem(0, (net.minecraft.world.item.ItemStack) ModUnderTest.staticCall(
                        "com.killer560.hub.roomsim.SimItems", "build", new Class<?>[]{String.class},
                        new Object[]{held}));
                given.set(true);
            });
        });
        ctx.waitFor(mc -> given.get() != null, 200);
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
        // The stack reaches the client a few ticks after the server sets it; on 26.2 the chunk wait below can be
        // zero ticks, so wait for the item itself.
        try {
            ctx.waitFor(mc -> held.equals(ModUnderTest.staticCall("com.killer560.hub.cheatutils.CheatUtils",
                    "skyblockId", new Class<?>[]{net.minecraft.world.item.ItemStack.class},
                    new Object[]{mc.player.getMainHandItem()})), 200);
        } catch (Throwable ignored) {
            // reported as a failed premise below
        }
        // The client must HAVE the skull before the aura can be asked to see it (the first world a fresh client
        // opens takes a while to get its chunks - testkit CLAUDE.md).
        boolean clientHasIt;
        try {
            ctx.waitFor(mc -> mc.player != null && mc.player.onGround()
                    && !mc.level.getBlockState(skull).isAir()
                    && mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(skull)) < 25.0, 1200);
            clientHasIt = true;
        } catch (Throwable t) {
            clientHasIt = false;
        }
        String clientView = ctx.computeOnClient(mc -> describe(mc.level.getBlockState(skull).getBlock().toString(),
                mc.level.getBlockEntity(skull)) + ", eye " + String.format(java.util.Locale.ROOT, "%.2f",
                Math.sqrt(mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(skull)))) + " from its centre");
        String inHand = ctx.computeOnClient(mc -> String.valueOf(ModUnderTest.staticCall(
                "com.killer560.hub.cheatutils.CheatUtils", "skyblockId",
                new Class<?>[]{net.minecraft.world.item.ItemStack.class},
                new Object[]{mc.player.getMainHandItem()})));
        println(tag + ": client sees " + clientView + "; in hand " + inHand);
        if (!held.equals(inHand)) {
            return tag + ": FAIL premise - expected " + held + " in hand, have " + inHand;
        }
        if (!clientHasIt) {
            return tag + ": FAIL premise - the client never had the skull in reach (" + clientView + ")";
        }

        // A picture of the essence as drawn (it should read as a black skull), looking straight at it.
        ctx.runOnClient(mc -> {
            Vec3 eye = mc.player.getEyePosition();
            Vec3 at = Vec3.atCenterOf(skull).subtract(0, 0.25, 0);
            double dx = at.x - eye.x;
            double dy = at.y - eye.y;
            double dz = at.z - eye.z;
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float pitch = (float) Math.max(-90, Math.min(90, -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)))));
            mc.player.setYRot(yaw);
            mc.player.setXRot(pitch);
        });
        ctx.waitTicks(10);
        println(tag + ": screenshot " + ctx.takeScreenshot(dev.testkit.harness.Report.fileName(NAME + "-visit" + n)));

        // ---- Secret Aura on, essence only --------------------------------------------------------------
        SERVER_USES.clear();
        ctx.runOnClient(mc -> {
            PacketTrace.start();
            ModUnderTest.staticCall(AWAIT, "begin", new Class<?>[]{Minecraft.class}, new Object[]{mc});
            Object cfg = ModUnderTest.config(CHEAT_CFG);
            ModUnderTest.set(cfg, "setAuraChests", false);
            ModUnderTest.set(cfg, "setAuraLevers", false);
            ModUnderTest.set(cfg, "setAuraEssence", true);
            ModUnderTest.set(cfg, "setAuraPauseWhileSneaking", false);
            ModUnderTest.set(cfg, "setSecretAuraEnabled", true);
        });
        String key = skull.getX() + "," + skull.getY() + "," + skull.getZ();
        int waited = 0;
        boolean gone = false;
        for (; waited < 100; waited += 5) {
            ctx.waitTicks(5);
            AtomicReference<Boolean> stillSkull = new AtomicReference<>();
            ctx.runOnClient(mc -> {
                var server = mc.getSingleplayerServer();
                server.execute(() -> stillSkull.set(server.overworld().getBlockState(skull).getBlock()
                        instanceof net.minecraft.world.level.block.AbstractSkullBlock));
            });
            ctx.waitFor(mc -> stillSkull.get() != null, 100);
            if (!stillSkull.get()) {
                gone = true;
                break;
            }
        }
        ctx.waitTicks(5);
        boolean serverSawClick = SERVER_USES.contains(key);
        int useItemOn = ctx.computeOnClient(mc -> {
            PacketTrace.stop();
            return PacketTrace.sent("use_item_on");
        });
        int awaitSecrets = ctx.computeOnClient(mc -> {
            int s = (Integer) ModUnderTest.staticCall(AWAIT, "secrets", new Class<?>[]{}, new Object[]{});
            ModUnderTest.staticCall(AWAIT, "end", new Class<?>[]{}, new Object[]{});
            return s;
        });
        String facts = "use_item_on sent " + useItemOn + ", server handled a click on the essence " + serverSawClick
                + ", essence collected on the server " + gone + (gone ? " after ~" + waited + " ticks" : "")
                + ", Auto Routes await counted " + awaitSecrets + " own secret(s)";
        println(tag + ": " + facts);
        List<String> wrong = new ArrayList<>();
        if (!serverSawClick) {
            wrong.add("the server never saw Secret Aura's click on the essence");
        }
        if (!gone) {
            wrong.add("the essence is still there");
        }
        if (awaitSecrets < 1) {
            wrong.add("Auto Routes' await did not count the essence click as ours");
        }
        return tag + (wrong.isEmpty() ? ": PASS - " : ": FAIL - " + String.join("; ", wrong) + " - ") + facts;
    }

    /** {essencePos, standXYZ, note} for the first placed essence with a floor block in reach, or {null,null,why}. */
    private static Object[] pickEssence(ServerLevel level) throws Exception {
        @SuppressWarnings("unchecked")
        Collection<BlockPos> placed = (Collection<BlockPos>) Class.forName(SIM_SECRETS)
                .getField("PLACED_WITHER").get(null);
        List<BlockPos> list = new ArrayList<>(placed);
        list.sort(java.util.Comparator.comparingLong(BlockPos::asLong));
        if (list.isEmpty()) {
            return new Object[]{null, null, "the sim placed no wither essence on this floor"};
        }
        for (BlockPos e : list) {
            String block = level.getBlockState(e).getBlock().toString();
            if (!(level.getBlockState(e).getBlock() instanceof net.minecraft.world.level.block.AbstractSkullBlock)) {
                continue;
            }
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
                                || !level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()) {
                            continue;
                        }
                        double ex = feet.getX() + 0.5;
                        double ey = feet.getY() + 1.62;
                        double ez = feet.getZ() + 0.5;
                        // eye to the skull's box (an 8-pixel cube on the floor of its block): stay well inside 4.5
                        double cx = Math.max(e.getX() + 0.25, Math.min(ex, e.getX() + 0.75));
                        double cy = Math.max(e.getY(), Math.min(ey, e.getY() + 0.5));
                        double cz = Math.max(e.getZ() + 0.25, Math.min(ez, e.getZ() + 0.75));
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
            if (best != null) {
                return new Object[]{e, best, block + ", " + describe(block, level.getBlockEntity(e)) + ", "
                        + list.size() + " essence(s) on the floor, eye-to-box "
                        + String.format(java.util.Locale.ROOT, "%.2f", bestD)};
            }
        }
        return new Object[]{null, null, list.size() + " essence(s) placed, none with a standable floor block in reach"};
    }

    private static String describe(String block, net.minecraft.world.level.block.entity.BlockEntity be) {
        if (be instanceof net.minecraft.world.level.block.entity.SkullBlockEntity s) {
            var prof = s.getOwnerProfile();
            return block + " with profile " + (prof == null ? "none" : prof.partialProfile().id() + " skin patch "
                    + prof.skinPatch().body().map(Object::toString).orElse("none"));
        }
        return block + " (no skull block entity)";
    }

    private static void hookServerUses() {
        if (hooked) {
            return;
        }
        hooked = true;
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.addPhaseOrdering(
                net.minecraft.resources.Identifier.fromNamespaceAndPath("testkit", "essence_watch"),
                net.fabricmc.fabric.api.event.Event.DEFAULT_PHASE);
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register(
                net.minecraft.resources.Identifier.fromNamespaceAndPath("testkit", "essence_watch"),
                (player, level, hand, hit) -> {
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
                Object cfg = ModUnderTest.config(CHEAT_CFG);
                ModUnderTest.set(cfg, "setSecretAuraEnabled", false);
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

    private static void println(String line) {
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
