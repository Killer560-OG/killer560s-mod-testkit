package dev.testkit.server;

import com.mojang.authlib.GameProfile;

import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A real {@link ServerPlayer} on the server, with no client behind it.
 *
 * <p>Test scenarios need an opponent that <b>is a player</b>, not a mob wearing a name tag. A mob is a
 * near-miss in the ways that matter: modules pick targets by entity type (an aura that filters for
 * players will not see a zombie at all), player hitboxes and eye heights differ, and knockback,
 * attack cooldown and reach all resolve against player attributes. Testing an aura against a zombie
 * measures the zombie.
 *
 * <p>The only awkward part is the connection. {@code PlayerList.placeNewPlayer} wants one and will write
 * the join sequence down it, so this supplies a {@link Connection} whose {@code send} goes nowhere and
 * which is backed by an {@link EmbeddedChannel} so the netty plumbing it touches is real rather than
 * null. Everything after that is an ordinary server player: it ticks, takes damage, takes knockback,
 * shows up in {@code @a}, and is visible to every other client on the server.
 */
public final class FakePlayer {

    /** Not a real socket, but the server asks for one. Loopback keeps anything that logs it sane. */
    private static final SocketAddress ADDRESS = new InetSocketAddress("127.0.0.1", 0);

    private final MinecraftServer server;
    private final String name;
    private final Vec3 anchor;
    /** Reassigned on respawn: PlayerList.respawn returns a NEW ServerPlayer, not the old one revived. */
    private ServerPlayer player;

    private boolean frozen;
    private float attackReach;
    private int attackDelay;
    private float attackDamage = 1.0f;
    private int sinceAttack;
    private int reports;
    private int deaths;
    private float maxHealth = 20f;

    private double walkYaw;
    private double walkSpeed;
    private boolean walking;
    private boolean chasing;
    private double chaseStop = 2.0;

    private final List<Vec3> route = new ArrayList<>();
    private boolean repeatRoute = true;
    private int routeIndex;

    private FakePlayer(MinecraftServer server, ServerPlayer player, String name, Vec3 anchor) {
        this.server = server;
        this.player = player;
        this.name = name;
        this.anchor = anchor;
    }

    /**
     * Join a fake player to the server at the given position.
     *
     * @param name the player's name — the project convention is {@code Bot<Module><detail>}
     */
    public static FakePlayer join(MinecraftServer server, ServerLevel level, String name, Vec3 position) {
        // A stable UUID per name, so re-running a scenario reuses the same profile rather than
        // accumulating one player file per run.
        UUID id = UUID.nameUUIDFromBytes(("RavenG4TestPlayer:" + name).getBytes());
        GameProfile profile = new GameProfile(id, name);

        ServerPlayer player = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        Connection connection = new SilentConnection();
        new EmbeddedChannel(connection);   // gives the connection a live channel; sets it up as active

        server.getPlayerList().placeNewPlayer(connection, player,
                CommonListenerCookie.createInitial(profile, false));
        // Survival, explicitly. A new ServerPlayer inherits the server's default gamemode, and the test
        // server's default is creative -- which is invulnerable, so an aura would visibly connect and the
        // target would never take a point of damage or flash red.
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        player.teleportTo(position.x, position.y, position.z);

        return new FakePlayer(server, player, name, position);
    }

    // --------------------------------------------------------------- properties

    /** Immovable: full knockback resistance and no gravity. It still takes damage and still dies. */
    public void setFrozen(boolean value) {
        this.frozen = value;
        setAttribute(Attributes.KNOCKBACK_RESISTANCE, value ? 1.0 : 0.0);
        player.setNoGravity(value);
    }

    public void setHealth(float health) {
        this.maxHealth = health;
        setAttribute(Attributes.MAX_HEALTH, Math.max(1.0, health));
        player.setHealth(health);
    }

    /** How many times it has been killed and put back. */
    public int deaths() {
        return deaths;
    }

    /** Body scale, through the vanilla attribute — changes the hitbox, not just the model. */
    public void setScale(double scale) {
        setAttribute(Attributes.SCALE, scale);
    }

    private void setAttribute(net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute,
                              double value) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }

    public void walk(double yaw, boolean sprint) {
        this.walking = true;
        this.chasing = false;
        this.walkYaw = yaw;
        this.walkSpeed = sprint ? 0.28 : 0.21;
    }

    /** Follow the nearest real player, stopping {@code stopAt} blocks short. */
    public void chase(double stopAt) {
        this.chasing = true;
        this.walking = false;
        this.chaseStop = stopAt;
        this.walkSpeed = 0.24;
    }

    public void route(boolean repeat, List<Vec3> positions) {
        this.route.clear();
        this.route.addAll(positions);
        this.repeatRoute = repeat;
        this.routeIndex = 0;
        this.walking = false;
        this.chasing = false;
        this.walkSpeed = 0.21;
    }

    /** Hit any real player within {@code reach}, at most once every {@code delayTicks}. */
    public void attacking(float reach, int delayTicks, float damage) {
        this.attackReach = reach;
        this.attackDelay = Math.max(1, delayTicks);
        this.attackDamage = damage;
    }

    public ServerPlayer entity() {
        return player;
    }

    public String name() {
        return name;
    }

    public void remove() {
        player.connection.disconnect(Component.literal("test over"));
    }

    // ------------------------------------------------------------------- tick

    /** Called once per server tick by {@link FakePlayerManager}. */
    public void tick() {
        // isRemoved() must NOT short-circuit here. A dead player is a REMOVED player, so returning early
        // on it skipped the respawn branch entirely and the corpse stayed wherever it fell -- which is
        // exactly what "it killed the fake player and it never came back" looked like.
        if (player.isRemoved() || !player.isAlive()) {
            // Rejoin rather than PlayerList.respawn. respawn() writes the new-player packets down the
            // player's OWN connection, which for a fake player goes nowhere, so the server had it
            // standing at the anchor while every real client still saw an empty platform. A fresh join
            // goes through the normal broadcast path, which is the one observers actually receive.
            deaths++;
            java.util.EnumMap<EquipmentSlot, ItemStack> carried = new java.util.EnumMap<>(EquipmentSlot.class);
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                carried.put(slot, player.getItemBySlot(slot).copy());
            }
            player.connection.disconnect(Component.literal("respawning"));
            player = join(server, server.overworld(), name, anchor).player;
            for (java.util.Map.Entry<EquipmentSlot, ItemStack> entry : carried.entrySet()) {
                player.setItemSlot(entry.getKey(), entry.getValue());
            }
            setHealth(maxHealth);
            setFrozen(frozen);
            report("died and rejoined at anchor " + anchor + " (death " + deaths + ")");
            return;
        }
        if (frozen) {
            player.setDeltaMovement(Vec3.ZERO);
            player.teleportTo(anchor.x, anchor.y, anchor.z);
        }
        move();
        attack();
    }

    private void move() {
        if (frozen) {
            return;
        }
        if (walking) {
            double radians = Math.toRadians(walkYaw);
            step(-Math.sin(radians) * walkSpeed, 0, Math.cos(radians) * walkSpeed, (float) walkYaw);
            return;
        }
        if (chasing) {
            ServerPlayer target = nearestRealPlayer();
            if (target == null) {
                return;
            }
            double dx = target.getX() - player.getX();
            double dz = target.getZ() - player.getZ();
            double flat = Math.sqrt(dx * dx + dz * dz);
            if (flat > chaseStop) {
                double move = Math.min(walkSpeed, flat - chaseStop);
                step(dx / flat * move, target.getY() - player.getY(), dz / flat * move,
                        (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
            }
            return;
        }
        if (!route.isEmpty()) {
            Vec3 goal = route.get(routeIndex);
            Vec3 delta = goal.subtract(player.position());
            double distance = delta.length();
            if (distance < walkSpeed) {
                player.teleportTo(goal.x, goal.y, goal.z);
                routeIndex++;
                if (routeIndex >= route.size()) {
                    routeIndex = repeatRoute ? 0 : route.size() - 1;
                }
            } else {
                Vec3 stepVector = delta.scale(walkSpeed / distance);
                step(stepVector.x, stepVector.y, stepVector.z,
                        (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0));
            }
        }
    }

    private void step(double dx, double dy, double dz, float yaw) {
        player.teleportTo(player.getX() + dx, player.getY() + dy, player.getZ() + dz);
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
    }

    private void attack() {
        if (attackReach <= 0) {
            return;
        }
        if (++sinceAttack < attackDelay) {
            return;
        }
        ServerPlayer target = nearestRealPlayer();
        if (target == null) {
            report("no real player found to attack");
            sinceAttack = 0;
            return;
        }
        double distance = target.distanceTo(player);
        if (distance > attackReach) {
            report(String.format(java.util.Locale.ROOT,
                    "target %s is %.2f away, reach is %.2f", target.getGameProfile().name(),
                    distance, attackReach));
            sinceAttack = 0;
            return;
        }
        // The player's own attack damage source, so knockback comes from this entity and lands exactly
        // as it would from a real opponent — which is the entire point for a velocity test.
        boolean hurt = target.hurtServer((net.minecraft.server.level.ServerLevel) player.level(),
                player.damageSources().playerAttack(player), attackDamage);
        report(String.format(java.util.Locale.ROOT, "hit %s for %.1f at %.2f blocks -> %s",
                target.getGameProfile().name(), attackDamage, distance,
                hurt ? "damaged" : "REFUSED (invulnerable, pvp off, or already hurt this tick)"));
        sinceAttack = 0;
    }

    /** Server console. Scenarios count hits by matching these lines, so the cap is generous. */
    private void report(String message) {
        if (++reports <= 400) {
            System.out.println("[testkit] " + name + ": " + message);
        }
    }

    /** The closest real (non-fake) player, which is the client under test. */
    private ServerPlayer nearestRealPlayer() {
        ServerPlayer best = null;
        double bestDistance = Double.MAX_VALUE;
        for (ServerPlayer candidate : player.level().getServer().getPlayerList().getPlayers()) {
            if (candidate == player || FakePlayerManager.isFake(candidate)) {
                continue;
            }
            double distance = candidate.distanceTo(player);
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    /**
     * A connection that accepts everything and sends nothing. The server writes the whole join sequence
     * down this before it will treat the player as present; there is simply no client to receive it.
     */
    private static final class SilentConnection extends Connection {

        private SilentConnection() {
            super(PacketFlow.SERVERBOUND);
        }

        @Override
        public void send(Packet<?> packet) {
        }

        @Override
        public void send(Packet<?> packet, ChannelFutureListener listener) {
        }

        @Override
        public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return ADDRESS;
        }
    }
}
