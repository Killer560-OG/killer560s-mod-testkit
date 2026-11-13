package dev.testkit.gametest.coop;

import com.mojang.authlib.GameProfile;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * A fake co-op guest on the CLIENT's integrated server (the sim's), for server-side co-op rules: closest-player
 * targeting, "every player" checks, per-player credit, a guest's item use, a refused block break. It is the
 * dedicated server's {@code dev.testkit.server.FakePlayer} pattern (a real {@link ServerPlayer} placed through
 * {@code PlayerList.placeNewPlayer} on a {@link Connection} that sends nothing), ported here because the testmod runs
 * on the dedicated server and cannot reach a client's integrated one.
 *
 * <p>Every action goes through the server's REAL packet handler ({@code ServerGamePacketListenerImpl.handleUseItem},
 * {@code handleUseItemOn}, {@code handleAttack}, {@code handleInteract}, {@code handlePlayerAction}), so Fabric's
 * callbacks and the mod's server hooks see it exactly as they would see a networked guest. The guest has no physics
 * (no client sends its moves): it stays where {@link #moveTo} puts it.
 *
 * <p>All methods are called from the test thread; each hops onto the server thread and ticks the client until it ran
 * (a tick or two), so never call them from inside {@code runOnClient}.
 */
public final class GuestPlayer {

    private final ClientGameTestContext ctx;
    private final MinecraftServer server;
    private final ServerPlayer player;
    private int sequence = 1;

    private GuestPlayer(ClientGameTestContext ctx, MinecraftServer server, ServerPlayer player) {
        this.ctx = ctx;
        this.server = server;
        this.player = player;
    }

    /** Joins a guest named {@code name} into {@code level} at {@code pos} (survival, client marked loaded). */
    public static GuestPlayer join(ClientGameTestContext ctx, MinecraftServer server, ServerLevel level, String name, Vec3 pos) {
        hook();
        EVENTS.removeIf(e -> e.startsWith(name + " "));
        GuestPlayer g = place(ctx, server, level, name, pos);
        // The mod lands every joiner beside the host at the end of its join tick (SimHost LANDING); put it back after.
        ctx.waitTicks(3);
        g.moveTo(pos, 0f, 0f);
        return g;
    }

    private static GuestPlayer place(ClientGameTestContext ctx, MinecraftServer server, ServerLevel level, String name, Vec3 pos) {
        return onServer(ctx, server, () -> {
            UUID id = UUID.nameUUIDFromBytes(("TestKitGuest:" + name).getBytes(StandardCharsets.UTF_8));
            GameProfile profile = new GameProfile(id, name);
            GUESTS.add(id);
            ServerPlayer p = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
            SilentConnection c = new SilentConnection();
            c.embedded = new EmbeddedChannel(c);
            server.getPlayerList().placeNewPlayer(c, p, CommonListenerCookie.createInitial(profile, false));
            // Without this, handleUseItem / handleAttack / handlePlayerAction ignore the guest for its first 60 ticks
            // (hasClientLoaded), the same gate a real client clears with this packet.
            p.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            p.setGameMode(GameType.SURVIVAL);
            p.snapTo(pos.x, pos.y, pos.z, 0f, 0f);
            settle(p);
            return new GuestPlayer(ctx, server, p);
        });
    }

    /** Joins next to the host's own server player, in the host's level (the sim's world). */
    public static GuestPlayer joinBesideHost(ClientGameTestContext ctx, MinecraftServer server, UUID host, String name, Vec3 pos) {
        ServerLevel level = onServer(ctx, server, () -> (ServerLevel) server.getPlayerList().getPlayer(host).level());
        return join(ctx, server, level, name, pos);
    }

    public ServerPlayer entity() {
        return player;
    }

    public String name() {
        return player.getGameProfile().name();
    }

    public Vec3 position() {
        return onServer(ctx, server, player::position);
    }

    /** True while the server's PlayerList holds this guest. */
    public boolean listed() {
        return onServer(ctx, server, () -> server.getPlayerList().getPlayer(player.getUUID()) == player);
    }

    /** Puts the guest at {@code pos} looking {@code yaw}/{@code pitch} (a teleport; it has no client to walk). */
    public void moveTo(Vec3 pos, float yaw, float pitch) {
        run(p -> {
            p.snapTo(pos.x, pos.y, pos.z, yaw, pitch);
            settle(p);
        });
    }

    /**
     * Clears the listener's pending teleport (placeNewPlayer sends one, and every {@code teleportTo} another): until a
     * client accepts it, {@code handleUseItemOn} ignores the player. The accept packet's shape differs between 26.2 and
     * 26.3, the field does not.
     */
    private static void settle(ServerPlayer p) {
        try {
            java.lang.reflect.Field f = net.minecraft.server.network.ServerGamePacketListenerImpl.class
                    .getDeclaredField("awaitingPositionFromClient");
            f.setAccessible(true);
            f.set(p.connection, null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("GuestPlayer: cannot clear awaitingPositionFromClient: " + e, e);
        }
        p.connection.resetPosition();
    }

    /** Turns the guest's look only. */
    public void look(float yaw, float pitch) {
        run(p -> {
            p.setYRot(yaw);
            p.setYHeadRot(yaw);
            p.setXRot(pitch);
        });
    }

    /** Puts {@code stack} (a copy) in the guest's main hand. */
    public void hold(ItemStack stack) {
        run(p -> p.setItemInHand(InteractionHand.MAIN_HAND, stack.copy()));
    }

    public ItemStack held() {
        return onServer(ctx, server, () -> player.getMainHandItem().copy());
    }

    /** A right click in the air with the held item, through {@code handleUseItem} (what a real use_item packet runs). */
    public void useItem() {
        run(p -> p.connection.handleUseItem(new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, sequence++,
                p.getYRot(), p.getXRot())));
    }

    /** A right click on a block face, through {@code handleUseItemOn}. */
    public void useOn(BlockPos pos, Direction face) {
        Vec3 hit = Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        run(p -> p.connection.handleUseItemOn(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND,
                new BlockHitResult(hit, face, pos, false), sequence++)));
    }

    /** A melee hit on {@code target}, through {@code handleAttack} (reach and the server's own checks apply). */
    public void attack(Entity target) {
        int id = onServer(ctx, server, target::getId);
        run(p -> p.connection.handleAttack(new ServerboundAttackPacket(id)));
    }

    /** A right click on an entity (armor stand, terminal), through {@code handleInteract}. */
    public void interact(Entity target) {
        int id = onServer(ctx, server, target::getId);
        run(p -> {
            Vec3 at = target.position().add(0, target.getBbHeight() / 2, 0).subtract(target.position());
            p.connection.handleInteract(new ServerboundInteractPacket(id, InteractionHand.MAIN_HAND, at, false));
        });
    }

    /**
     * Starts (and, for {@code finish}, completes) breaking a block through {@code handlePlayerAction}; returns whether
     * the block is gone afterwards. A refused break must leave it standing.
     */
    public boolean breakBlock(BlockPos pos, Direction face, boolean finish) {
        run(p -> {
            p.connection.handlePlayerAction(new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, face, sequence++));
            if (finish) {
                p.connection.handlePlayerAction(new ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, face, sequence++));
            }
        });
        return onServer(ctx, server, () -> player.level().getBlockState(pos).isAir());
    }

    /** Runs {@code body} on the server thread with the guest's ServerPlayer and waits for it. */
    public void run(java.util.function.Consumer<ServerPlayer> body) {
        onServer(ctx, server, () -> {
            body.accept(player);
            return null;
        });
    }

    /** Asks anything of the guest on the server thread. */
    public <T> T ask(Function<ServerPlayer, T> body) {
        return onServer(ctx, server, () -> body.apply(player));
    }

    /**
     * What the server's handlers did with this guest's actions, oldest first, as seen by Fabric's server-side callbacks
     * in a phase ahead of every mod's: {@code use <item>}, {@code useOn x,y,z}, {@code attack <entity type>},
     * {@code interact <entity type>}, {@code dig x,y,z} (a START reached handleBlockBreakAction), {@code break x,y,z} (a break got
     * as far as the break event). A case asserts its action ARRIVED here before judging what
     * the mod made of it.
     */
    public List<String> events() {
        String prefix = name() + " ";
        List<String> out = new ArrayList<>();
        synchronized (EVENTS) {
            for (String e : EVENTS) {
                if (e.startsWith(prefix)) {
                    out.add(e.substring(prefix.length()));
                }
            }
        }
        return out;
    }

    /** Every guest id this helper made (one per name, so it stays as small as the names used). */
    private static final java.util.Set<UUID> GUESTS = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final List<String> EVENTS = java.util.Collections.synchronizedList(new ArrayList<>());
    private static boolean hooked;

    private static void record(net.minecraft.world.entity.player.Player p, String what) {
        if (p instanceof ServerPlayer sp && isGuest(sp)) {
            if (EVENTS.size() > 500) {
                EVENTS.remove(0);
            }
            EVENTS.add(sp.getGameProfile().name() + " " + what);
        }
    }

    private static String type(Entity e) {
        return net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
    }

    private static synchronized void hook() {
        if (hooked) {
            return;
        }
        hooked = true;
        var phase = net.minecraft.resources.Identifier.fromNamespaceAndPath("testkit", "coop_guest_watch");
        var def = net.fabricmc.fabric.api.event.Event.DEFAULT_PHASE;
        var pass = net.minecraft.world.InteractionResult.PASS;
        net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.addPhaseOrdering(phase, def);
        net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register(phase, (p, level, hand) -> {
            record(p, "use " + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(p.getItemInHand(hand).getItem()).getPath());
            return pass;
        });
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.addPhaseOrdering(phase, def);
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register(phase, (p, level, hand, hit) -> {
            BlockPos b = hit.getBlockPos();
            record(p, "useOn " + b.getX() + "," + b.getY() + "," + b.getZ());
            return pass;
        });
        net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.addPhaseOrdering(phase, def);
        net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.register(phase, (p, level, hand, e, hit) -> {
            record(p, "attack " + type(e));
            return pass;
        });
        net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.addPhaseOrdering(phase, def);
        net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register(phase, (p, level, hand, e, hit) -> {
            record(p, "interact " + type(e));
            return pass;
        });
        net.fabricmc.fabric.api.event.player.AttackBlockCallback.EVENT.addPhaseOrdering(phase, def);
        net.fabricmc.fabric.api.event.player.AttackBlockCallback.EVENT.register(phase, (p, level, hand, pos, dir) -> {
            record(p, "dig " + pos.getX() + "," + pos.getY() + "," + pos.getZ());
            return pass;
        });
        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.addPhaseOrdering(phase, def);
        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.register(phase, (level, p, pos, state, be) -> {
            record(p, "break " + pos.getX() + "," + pos.getY() + "," + pos.getZ());
            return true;
        });
    }

    /** Leaves the server (the normal disconnect path: PlayerList.remove, the leave line to everyone). */
    public void remove() {
        run(p -> {
            if (!p.hasDisconnected()) {
                p.connection.disconnect(Component.literal("guest left"));
            }
        });
    }

    /** Removes every guest this helper joined that is still on {@code server} (a case's cleanup). */
    public static int removeAll(ClientGameTestContext ctx, MinecraftServer server) {
        if (server == null) {
            return 0;
        }
        return onServer(ctx, server, () -> {
            List<ServerPlayer> fakes = new ArrayList<>();
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (isGuest(p)) {
                    fakes.add(p);
                }
            }
            for (ServerPlayer p : fakes) {
                p.connection.disconnect(Component.literal("guest left"));
            }
            return fakes.size();
        });
    }

    /** Whether {@code p} is one of these fake guests. */
    public static boolean isGuest(ServerPlayer p) {
        return GUESTS.contains(p.getUUID());
    }

    /**
     * Runs {@code body} on the server thread and waits for it by TICKING the client: a blocking {@code future.get()}
     * from the test thread deadlocks the gametest lockstep (testkit CLAUDE.md, "server.submit(...).join()").
     */
    public static <T> T onServer(ClientGameTestContext ctx, MinecraftServer server, java.util.function.Supplier<T> body) {
        AtomicReference<Object[]> out = new AtomicReference<>();
        server.execute(() -> {
            try {
                out.set(new Object[]{body.get(), null});
            } catch (Throwable t) {
                out.set(new Object[]{null, t});
            }
        });
        ctx.waitFor(mc -> out.get() != null, 200);
        Object[] r = out.get();
        if (r[1] != null) {
            throw new AssertionError("guest action failed on the server: " + r[1], (Throwable) r[1]);
        }
        @SuppressWarnings("unchecked")
        T value = (T) r[0];
        return value;
    }

    /** Sends nothing; completes send listeners so a disconnect still closes it and runs onDisconnect (FakePlayer). */
    private static final class SilentConnection extends Connection {

        private EmbeddedChannel embedded;

        private SilentConnection() {
            super(PacketFlow.SERVERBOUND);
        }

        @Override
        public void send(Packet<?> packet) {
        }

        @Override
        public void send(Packet<?> packet, ChannelFutureListener listener) {
            finished(listener);
        }

        @Override
        public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
            finished(listener);
        }

        private void finished(ChannelFutureListener listener) {
            if (listener == null || embedded == null) {
                return;
            }
            try {
                listener.operationComplete(embedded.newSucceededFuture());
            } catch (Exception e) {
                System.err.println("[coop] a send listener failed: " + e);
            }
        }
    }
}
