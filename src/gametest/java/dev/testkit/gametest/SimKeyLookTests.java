package dev.testkit.gametest;

import dev.testkit.compat.McCompat;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 143-sim-key-look: what a dropped sim Wither Key and Blood Key look like (mod sim-key-head, 2026-10-06). killer560: "make
 * the armor stand invisible with a black head that looks like the key ... send me a picture of it". Only when named.
 *
 * <p>On a sim F7 3x4, one key at a time is dropped ({@code SimKeys.drop}) on the floor where he stands, and the player
 * (survival, flying - never spectator, which draws invisible entities as ghosts) is put 4 blocks off: one shot front-on
 * at head height, one from 2.5 blocks above. Noon, full gamma, the mod's key ESP off for the shots. Before the shots the key is taken out of the sim's pickup list, because the pickup range is
 * at least the 0.27.2 bonus of 5 blocks and he would otherwise pick it up while the camera is being placed.
 *
 * <p>Asserted, on the CLIENT's copy of the stand: {@code DungeonKeys.dropped} finds it as the right key; it is
 * invisible, has no base plate, is not small or a marker, and wears a player head with a profile carrying textures.
 * The pictures themselves are for a person to look at ({@code build/testkit-report/screens/143-sim-key-look-*}).
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimKeyLookTests implements FabricClientGameTest {

    private static final String NAME = "143-sim-key-look";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String SIM_KEYS = "com.killer560.hub.roomsim.SimKeys";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String DUNGEON_KEYS = "com.killer560.hub.doorkeys.DungeonKeys";
    private static final String DOOR_KEYS_CONFIG = "com.killer560.hub.doorkeys.DoorKeysConfig";
    private static final String FULLBRIGHT_CONFIG = "com.killer560.hub.fullbright.FullbrightConfig";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank() || Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (SimMapTests.copyRoomsForOthers() < 20) {
            Scenario.skipped(NAME, "needs real room captures and room database");
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);
        List<String> failures = new ArrayList<>();
        double gamma = ctx.computeOnClient(mc -> mc.options.gamma().get());
        boolean esp = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.call(ModUnderTest.config(DOOR_KEYS_CONFIG),
                "isEnabled", new Class<?>[]{}, new Object[]{}));
        boolean bright = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.call(ModUnderTest.config(FULLBRIGHT_CONFIG),
                "isEnabled", new Class<?>[]{}, new Object[]{}));
        try {
            body(ctx, failures);
        } catch (Throwable t) {
            failures.add("scenario threw: " + t);
            t.printStackTrace(System.out);
        } finally {
            ctx.runOnClient(mc -> {
                mc.options.gamma().set(gamma);
                ModUnderTest.set(ModUnderTest.config(DOOR_KEYS_CONFIG), "setEnabled", esp);
                ModUnderTest.set(ModUnderTest.config(FULLBRIGHT_CONFIG), "setEnabled", bright);
            });
            leave(ctx);
        }
        if (!failures.isEmpty()) {
            throw new AssertionError(String.join(" | ", failures));
        }
        println("PASS - both keys drawn as invisible stands wearing their heads; pictures in screens/");
    }

    private static void body(ClientGameTestContext ctx, List<String> failures) {
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
        // The first sim world takes a while to reach the client (testkit CLAUDE.md): stand on a real block first.
        ctx.waitFor(mc -> mc.player.onGround() && !mc.level.getBlockState(mc.player.blockPosition().below()).isAir(), 2400);
        ctx.waitTicks(20);
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> server.getCommands().performPrefixedCommand(
                    server.createCommandSourceStack().withSuppressedOutput(), "time set noon"));
        });
        // The entrance is roofed, so noon alone leaves it dim: full gamma for the pictures (restored in runTest), and
        // the mod's own key ESP off so its tracer does not draw over the head (DungeonKeys.dropped is asserted instead).
        // Gamma alone is not enough in a room with no sky light (third run): the mod's Fullbright as well.
        ctx.runOnClient(mc -> {
            mc.options.gamma().set(1.0);
            ModUnderTest.set(ModUnderTest.config(DOOR_KEYS_CONFIG), "setEnabled", false);
            ModUnderTest.set(ModUnderTest.config(FULLBRIGHT_CONFIG), "setEnabled", true);
        });

        // Where the key goes: the block he stands on, and the open horizontal direction the camera goes back along.
        BlockPos feet = ctx.computeOnClient(mc -> mc.player.blockPosition());
        AtomicReference<int[]> dir = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> dir.set(openDirection(server.overworld(), feet)));
        });
        ctx.waitFor(mc -> dir.get() != null, 200);
        int[] d = dir.get();
        println(String.format(Locale.US, "key spot %s, camera back along %d,%d (%d clear blocks)", feet.toShortString(),
                d[0], d[1], d[2]));
        if (d[2] < 4) {
            failures.add("premise: no direction from the spawn with 4 clear blocks for the camera (" + d[2] + ")");
        }
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
                // Survival and flying, NOT spectator: a spectator sees invisible entities as translucent ghosts, so
                // the stand's body showed under the head in the first pictures - nothing a player in a run sees.
                if (sp != null) {
                    sp.setGameMode(GameType.SURVIVAL);
                    sp.getAbilities().mayfly = true;
                    sp.getAbilities().flying = true;
                    sp.onUpdateAbilities();
                }
            });
        });
        ctx.waitFor(mc -> mc.player.getAbilities().flying && !mc.player.isSpectator(), 200);

        for (boolean blood : new boolean[]{false, true}) {
            shoot(ctx, failures, feet, d, blood);
        }
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
                if (sp != null) {
                    sp.getAbilities().flying = false;
                    sp.getAbilities().mayfly = false;
                    sp.onUpdateAbilities();
                }
            });
        });
    }

    private static void shoot(ClientGameTestContext ctx, List<String> failures, BlockPos feet, int[] d, boolean blood) {
        String key = blood ? "blood" : "wither";
        Vec3 at = new Vec3(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
        // The camera first, 4 blocks back: the key is dropped where he stood, and the pickup is switched off below.
        placeCamera(ctx, at, d, 4.0, 0.0);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_KEYS, "drop", new Class<?>[]{Minecraft.class, Vec3.class,
                boolean.class}, new Object[]{mc, at, blood}));
        // Out of the pickup list as soon as the server has it (same server tick queue as the drop).
        AtomicReference<String> held = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> held.set(unlist(server.overworld(), at)));
        });
        ctx.waitFor(mc -> held.get() != null, 200);
        println(key + ": " + held.get());
        try {
            ctx.waitFor(mc -> !dropped(mc).isEmpty(), 400);
        } catch (Throwable t) {
            failures.add(key + ": the client never saw the key (DungeonKeys.dropped is empty)");
            return;
        }
        String seen = ctx.computeOnClient(mc -> describe(mc, blood));
        println(key + ": client sees " + seen);
        if (!seen.startsWith("OK")) {
            failures.add(key + ": " + seen);
        }
        ctx.waitTicks(40);   // the head's skin is fetched asynchronously
        Path front = ctx.takeScreenshot(dev.testkit.harness.Report.fileName(NAME + "-" + key + "-front"));
        println(key + ": front screenshot " + front + " -> " + dev.testkit.harness.Report.screenshot(NAME + "-" + key + "-front", front));
        placeCamera(ctx, at, d, 4.0, 2.5);
        ctx.waitTicks(20);
        double eyeAbove = ctx.computeOnClient(mc -> mc.player.getEyeY() - at.y);
        println(String.format(Locale.US, "%s: camera eye %.2f above the floor for the shot from above (want ~4.1)", key,
                eyeAbove));
        if (eyeAbove < 3.0) {
            failures.add(String.format(Locale.US, "%s: the camera fell before the shot from above (eye %.2f)", key, eyeAbove));
        }
        Path above = ctx.takeScreenshot(dev.testkit.harness.Report.fileName(NAME + "-" + key + "-above"));
        println(key + ": above screenshot " + above + " -> " + dev.testkit.harness.Report.screenshot(NAME + "-" + key + "-above", above));
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> server.overworld().getEntitiesOfClass(ArmorStand.class, new AABB(at, at).inflate(1.0))
                    .forEach(net.minecraft.world.entity.Entity::discard));
        });
        ctx.waitFor(mc -> dropped(mc).isEmpty(), 200);
    }

    /** Puts the spectator {@code back} blocks along {@code d} from the key, {@code up} above eye level, looking at the head. */
    private static void placeCamera(ClientGameTestContext ctx, Vec3 key, int[] d, double back, double up) {
        double x = key.x + d[0] * back;
        double z = key.z + d[1] * back;
        double y = key.y + up;
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
                if (sp != null) {
                    sp.teleportTo(x, y, z);
                }
            });
        });
        ctx.waitFor(mc -> mc.player.position().distanceToSqr(x, y, z) < 0.01, 200);
        ctx.runOnClient(mc -> {
            // Survival flight drops the moment he touches the ground (LocalPlayer), as he did for the front-on shot,
            // so it is put back here or the camera falls before the shot from above (second run, 2026-10-06).
            mc.player.getAbilities().flying = true;
            mc.player.onUpdateAbilities();
            Vec3 eye = mc.player.getEyePosition();
            Vec3 head = key.add(0, 1.65, 0);
            double dx = head.x - eye.x;
            double dy = head.y - eye.y;
            double dz = head.z - eye.z;
            mc.player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
            mc.player.setXRot((float) Math.max(-90, Math.min(90, -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))))));
        });
    }

    /** {dx, dz, clear}: the horizontal direction with the most clear blocks (feet and head) out to 6. */
    private static int[] openDirection(ServerLevel level, BlockPos feet) {
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        int[] best = {1, 0, -1};
        for (int[] d : dirs) {
            int n = 0;
            for (int i = 1; i <= 6; i++) {
                BlockPos p = feet.offset(d[0] * i, 0, d[1] * i);
                if (!level.getBlockState(p).getCollisionShape(level, p).isEmpty()
                        || !level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()
                        || !level.getBlockState(p.above(2)).getCollisionShape(level, p.above(2)).isEmpty()
                        || !level.getBlockState(p.above(3)).getCollisionShape(level, p.above(3)).isEmpty()) {
                    break;
                }
                n++;
            }
            if (n > best[2]) {
                best = new int[]{d[0], d[1], n};
            }
        }
        return best;
    }

    /** Takes the key's stand out of SimKeys' pickup list so the camera can stand near it. Server thread. */
    @SuppressWarnings("unchecked")
    private static String unlist(ServerLevel level, Vec3 at) {
        try {
            List<ArmorStand> stands = level.getEntitiesOfClass(ArmorStand.class, new AABB(at, at).inflate(1.0));
            var f = Class.forName(SIM_KEYS).getDeclaredField("DROPPED");
            f.setAccessible(true);
            Map<java.util.UUID, Boolean> dropped = (Map<java.util.UUID, Boolean>) f.get(null);
            int removed = 0;
            for (ArmorStand s : stands) {
                if (dropped.remove(s.getUUID()) != null) {
                    removed++;
                }
            }
            return stands.size() + " stand(s) at the spot on the server, " + removed + " taken out of the pickup list";
        } catch (Throwable t) {
            return "could not unlist: " + t;
        }
    }

    private static List<?> dropped(Minecraft mc) {
        return (List<?>) ModUnderTest.staticCall(DUNGEON_KEYS, "dropped", new Class<?>[]{Minecraft.class},
                new Object[]{mc});
    }

    /** The client's stand, checked: "OK ..." or what is wrong. */
    private static String describe(Minecraft mc, boolean blood) {
        String want = blood ? "Blood Key" : "Wither Key";
        for (var e : mc.level.entitiesForRendering()) {
            if (!(e instanceof ArmorStand s) || !want.equals(s.getName().getString())) {
                continue;
            }
            var head = s.getItemBySlot(EquipmentSlot.HEAD);
            var profile = head.get(net.minecraft.core.component.DataComponents.PROFILE);
            boolean textured = profile != null && !profile.partialProfile().properties().get("textures").isEmpty();
            String facts = String.format(Locale.US, "stand at y %.2f, invisible %s, base plate hidden %s, small %s,"
                            + " marker %s, head %s, profile textures %s, name visible %s", s.getY(), s.isInvisible(),
                    !s.showBasePlate(), s.isSmall(), s.isMarker(), head.getItem(), textured, s.isCustomNameVisible());
            boolean ok = s.isInvisible() && !s.showBasePlate() && !s.isSmall() && !s.isMarker()
                    && head.is(net.minecraft.world.item.Items.PLAYER_HEAD) && textured && s.isCustomNameVisible();
            return (ok ? "OK " : "WRONG ") + facts;
        }
        return "WRONG no client armour stand named " + want;
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
}
