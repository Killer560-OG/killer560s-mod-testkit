package dev.testkit.server.hx;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.serialization.JsonOps;

import dev.testkit.server.mixin.AccessorPlayerInfoUpdatePacket;
import dev.testkit.server.mixin.InvokerArmorStand;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.item.ItemParser;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The core Hx ops (WP1). Every packet constructor used here was read with javap against the mapped 26.1.2 jar in
 * {@code .gradle/loom-cache} on 2026-10-04; nothing is written from memory.
 *
 * <h2>Text</h2>
 * Any text argument {@code X} may be given as {@code X} (a string, legacy section-sign codes kept inside a literal,
 * which is how most Hypixel lines arrive) or as {@code XJson} (a JSON text component, decoded with vanilla's
 * {@code ComponentSerialization.CODEC}).
 *
 * <h2>Target</h2>
 * {@code player} names one player; omitted, packets go to every player and {@code give}/{@code menu.open}/
 * {@code kick} use the first (there is normally exactly one: the gametest client).
 *
 * <h2>Ops</h2>
 * <pre>
 * ping                       -&gt; "pong"
 * ops                        -&gt; {ops: [...], count}
 * players                    -&gt; [names]
 * events.poll {since}        -&gt; {next, dropped, events: [...]}       (see HxEvents)
 * events.head                -&gt; newest seq
 * chat {text|textJson, overlay?}           ClientboundSystemChatPacket(component, overlay)
 * actionbar.packet {text|textJson}         ClientboundSetActionBarTextPacket - NOT what Hypixel's action bar
 *                                          parsers read; chat {overlay:true} is. Kept for the positive control.
 * title {title?, subtitle?, fadeIn=10, stay=70, fadeOut=20}
 * sidebar.set {title, lines[], split?}     server scoreboard: objective hx_sidebar, one team per line holding the
 *                                          line as PREFIX on an invisible owner (§0§r, §1§r ...), descending
 *                                          scores - Hypixel's shape. split=N puts chars [N..] in the SUFFIX.
 * sidebar.clear
 * tab.set {entries:[string|{text, latency?, listed?}], header?, footer?}
 *                                          fake profiles "!A-a".. (sort ahead of real names), ADD_PLAYER +
 *                                          UPDATE_LISTED + UPDATE_GAME_MODE + UPDATE_LATENCY + UPDATE_DISPLAY_NAME
 * tab.clear
 * sound {id, volume=1, pitch=1, source="master"}   ClientboundSoundPacket at the player
 * particle {type, x?, y?, z?, dx=0, dy=0, dz=0, speed=0, count=1}   vanilla /particle ... force
 * give {slot, stack, count=1}              stack in vanilla item syntax, e.g. bow[custom_data={id:"TERMINATOR"}]
 * stand {x, y, z, name?, nameVisible=true, invisible=true, small=false, marker=true, helmet?}
 *                                          -&gt; {uuid, id}
 * entity.remove {uuid} | {all: true}       stands spawned through Hx
 * menu.open {title, rows=6, slots: {"13": "stack", ...}, script?}   -&gt; {containerId}
 * kick {reason}
 * cmd.stub {names[], reply?}               register commands that just answer (and are logged like any other)
 * </pre>
 */
public final class HxPrimitives implements HxModule {

    private static final String SIDEBAR = "hx_sidebar";
    private static final String TEAM_PREFIX = "hx_sb_";
    private static final int TAB_SLOTS = 80;
    private static final int TAB_ROWS = 20;
    private static final UUID[] TAB_IDS = new UUID[TAB_SLOTS];
    private static final GameProfile[] TAB_PROFILES = new GameProfile[TAB_SLOTS];

    static {
        for (int i = 0; i < TAB_SLOTS; i++) {
            // Vanilla sorts the tab by profile name, so "!A-a" .. "!D-t" is column-major order and '!' sorts ahead
            // of every real player name - Hypixel's own trick, and the mod's sim does the same.
            String name = "!" + (char) ('A' + i / TAB_ROWS) + "-" + (char) ('a' + i % TAB_ROWS);
            TAB_IDS[i] = UUID.nameUUIDFromBytes(("testkit-hx-tab-" + i).getBytes(StandardCharsets.UTF_8));
            TAB_PROFILES[i] = new GameProfile(TAB_IDS[i], name);
        }
    }

    /** Fake tab entries currently shown, so tab.set/tab.clear can remove them. */
    private static int tabShown;
    /** Stands spawned through Hx. */
    private static final Map<UUID, Entity> STANDS = new LinkedHashMap<>();
    /** Command names stubbed so far (Brigadier cannot unregister; a repeat just merges). */
    private static final Set<String> STUBBED = new LinkedHashSet<>();

    @Override
    public String name() {
        return "core";
    }

    @Override
    public void register() {
        HxBridge.register("ping", (s, a) -> new JsonPrimitive("pong"));
        HxBridge.register("ops", (s, a) -> HxBridge.describe());
        HxBridge.register("players", (s, a) -> {
            JsonArray out = new JsonArray();
            s.getPlayerList().getPlayers().forEach(p -> out.add(p.getGameProfile().name()));
            return out;
        });
        HxBridge.register("events.poll", (s, a) -> HxEvents.poll(a.has("since") ? a.get("since").getAsLong() : 0));
        HxBridge.register("events.head", (s, a) -> new JsonPrimitive(HxEvents.head()));
        HxBridge.register("chat", (s, a) -> {
            Component c = text(s, a, "text");
            boolean overlay = bool(a, "overlay", false);
            send(s, a, new ClientboundSystemChatPacket(c, overlay));
            return new JsonPrimitive(c.getString());
        });
        HxBridge.register("actionbar.packet", (s, a) -> {
            Component c = text(s, a, "text");
            send(s, a, new ClientboundSetActionBarTextPacket(c));
            return new JsonPrimitive(c.getString());
        });
        HxBridge.register("title", HxPrimitives::title);
        HxBridge.register("sidebar.set", HxPrimitives::sidebarSet);
        HxBridge.register("sidebar.clear", (s, a) -> {
            sidebarClear(s);
            return null;
        });
        HxBridge.register("tab.set", HxPrimitives::tabSet);
        HxBridge.register("tab.clear", (s, a) -> {
            tabClear(s, a);
            send(s, a, new ClientboundTabListPacket(Component.empty(), Component.empty()));
            return null;
        });
        HxBridge.register("sound", HxPrimitives::sound);
        HxBridge.register("particle", HxPrimitives::particle);
        HxBridge.register("give", HxPrimitives::give);
        HxBridge.register("stand", HxPrimitives::stand);
        HxBridge.register("entity.remove", HxPrimitives::entityRemove);
        HxBridge.register("menu.open", HxPrimitives::menuOpen);
        HxBridge.register("kick", (s, a) -> {
            ServerPlayer p = player(s, a);
            p.connection.disconnect(text(s, a, "reason"));
            return null;
        });
        HxBridge.register("cmd.stub", HxPrimitives::cmdStub);
    }

    // ---- text and targets --------------------------------------------------------------------------------

    /** {@code key} as a literal (section signs kept), or {@code keyJson} as a JSON component. Empty if neither. */
    public static Component text(MinecraftServer server, JsonObject args, String key) {
        if (args.has(key + "Json")) {
            JsonElement json = args.get(key + "Json");
            if (json.isJsonPrimitive()) {
                json = JsonParser.parseString(json.getAsString());
            }
            RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
            return ComponentSerialization.CODEC.parse(ops, json).getOrThrow(
                    msg -> new IllegalArgumentException("bad " + key + "Json: " + msg));
        }
        if (args.has(key) && !args.get(key).isJsonNull()) {
            return Component.literal(args.get(key).getAsString());
        }
        return Component.empty();
    }

    /** The named player, or the first one online. */
    public static ServerPlayer player(MinecraftServer server, JsonObject args) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (args.has("player")) {
            ServerPlayer p = server.getPlayerList().getPlayerByName(args.get("player").getAsString());
            if (p == null) {
                throw new IllegalArgumentException("no player named " + args.get("player").getAsString());
            }
            return p;
        }
        for (ServerPlayer p : players) {
            if (!p.getGameProfile().name().startsWith("SicoKaleb")) {
                return p;
            }
        }
        throw new IllegalStateException("no player online");
    }

    /** To the named player, or to everyone. */
    public static void send(MinecraftServer server, JsonObject args, Packet<?> packet) {
        if (args.has("player")) {
            player(server, args).connection.send(packet);
        } else {
            server.getPlayerList().broadcastAll(packet);
        }
    }

    private static boolean bool(JsonObject a, String key, boolean fallback) {
        return a.has(key) ? a.get(key).getAsBoolean() : fallback;
    }

    private static int integer(JsonObject a, String key, int fallback) {
        return a.has(key) ? a.get(key).getAsInt() : fallback;
    }

    private static double number(JsonObject a, String key, double fallback) {
        return a.has(key) ? a.get(key).getAsDouble() : fallback;
    }

    // ---- title -----------------------------------------------------------------------------------------------

    private static JsonElement title(MinecraftServer s, JsonObject a) {
        send(s, a, new ClientboundSetTitlesAnimationPacket(integer(a, "fadeIn", 10), integer(a, "stay", 70),
                integer(a, "fadeOut", 20)));
        if (a.has("subtitle") || a.has("subtitleJson")) {
            send(s, a, new ClientboundSetSubtitleTextPacket(text(s, a, "subtitle")));
        }
        // The title packet is what makes the client show both, so it goes last.
        send(s, a, new ClientboundSetTitleTextPacket(text(s, a, "title")));
        return null;
    }

    // ---- sidebar ---------------------------------------------------------------------------------------------

    private static JsonElement sidebarSet(MinecraftServer s, JsonObject a) {
        sidebarClear(s);
        ServerScoreboard board = s.getScoreboard();
        Objective objective = board.addObjective(SIDEBAR, ObjectiveCriteria.DUMMY, text(s, a, "title"),
                ObjectiveCriteria.RenderType.INTEGER, false, null);
        JsonArray lines = a.has("lines") ? a.getAsJsonArray("lines") : new JsonArray();
        if (lines.size() > 15) {
            throw new IllegalArgumentException("a sidebar shows at most 15 lines, got " + lines.size());
        }
        int split = integer(a, "split", -1);
        JsonArray owners = new JsonArray();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).getAsString();
            // An invisible, unique owner per line, like Hypixel's: a colour code and a reset, which renders as
            // nothing. The visible text lives in the owner's team prefix (and suffix).
            String owner = "§" + Integer.toHexString(i) + "§r";
            PlayerTeam team = board.addPlayerTeam(TEAM_PREFIX + i);
            if (split >= 0 && split < line.length()) {
                team.setPlayerPrefix(Component.literal(line.substring(0, split)));
                team.setPlayerSuffix(Component.literal(line.substring(split)));
            } else {
                team.setPlayerPrefix(Component.literal(line));
            }
            board.addPlayerToTeam(owner, team);
            board.getOrCreatePlayerScore(ScoreHolder.forNameOnly(owner), objective).set(lines.size() - i);
            owners.add(owner);
        }
        board.setDisplayObjective(DisplaySlot.SIDEBAR, objective);
        JsonObject out = new JsonObject();
        out.addProperty("lines", lines.size());
        out.add("owners", owners);
        return out;
    }

    private static void sidebarClear(MinecraftServer s) {
        ServerScoreboard board = s.getScoreboard();
        Objective old = board.getObjective(SIDEBAR);
        if (old != null) {
            board.removeObjective(old);
        }
        for (PlayerTeam team : new ArrayList<>(board.getPlayerTeams())) {
            if (team.getName().startsWith(TEAM_PREFIX)) {
                board.removePlayerTeam(team);
            }
        }
    }

    // ---- tab list --------------------------------------------------------------------------------------------

    private static JsonElement tabSet(MinecraftServer s, JsonObject a) {
        tabClear(s, a);
        JsonArray entries = a.has("entries") ? a.getAsJsonArray("entries") : new JsonArray();
        if (entries.size() > TAB_SLOTS) {
            throw new IllegalArgumentException("at most " + TAB_SLOTS + " tab entries, got " + entries.size());
        }
        List<ClientboundPlayerInfoUpdatePacket.Entry> list = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            JsonElement el = entries.get(i);
            JsonObject e = el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
            if (!el.isJsonObject()) {
                e.addProperty("text", el.getAsString());
            }
            list.add(new ClientboundPlayerInfoUpdatePacket.Entry(TAB_IDS[i], TAB_PROFILES[i], bool(e, "listed", true),
                    integer(e, "latency", 0), GameType.SURVIVAL, text(s, e, "text"), false, 0, null));
        }
        if (!list.isEmpty()) {
            ClientboundPlayerInfoUpdatePacket packet = new ClientboundPlayerInfoUpdatePacket(
                    EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,
                            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,
                            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY,
                            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME),
                    List.<ServerPlayer>of());
            ((AccessorPlayerInfoUpdatePacket) (Object) packet).testkit$setEntries(List.copyOf(list));
            send(s, a, packet);
        }
        tabShown = list.size();
        if (a.has("header") || a.has("headerJson") || a.has("footer") || a.has("footerJson")) {
            send(s, a, new ClientboundTabListPacket(text(s, a, "header"), text(s, a, "footer")));
        }
        JsonObject out = new JsonObject();
        out.addProperty("entries", list.size());
        return out;
    }

    private static void tabClear(MinecraftServer s, JsonObject a) {
        if (tabShown > 0) {
            List<UUID> ids = new ArrayList<>();
            for (int i = 0; i < tabShown; i++) {
                ids.add(TAB_IDS[i]);
            }
            send(s, a, new ClientboundPlayerInfoRemovePacket(ids));
            tabShown = 0;
        }
    }

    // ---- sound, particle -------------------------------------------------------------------------------------

    private static JsonElement sound(MinecraftServer s, JsonObject a) {
        ServerPlayer p = player(s, a);
        Identifier id = Identifier.parse(a.get("id").getAsString());
        Holder<SoundEvent> holder = Holder.direct(SoundEvent.createVariableRangeEvent(id));
        SoundSource source = SoundSource.valueOf(a.has("source")
                ? a.get("source").getAsString().toUpperCase(java.util.Locale.ROOT) : "MASTER");
        p.connection.send(new ClientboundSoundPacket(holder, source, p.getX(), p.getY(), p.getZ(),
                (float) number(a, "volume", 1), (float) number(a, "pitch", 1), p.getRandom().nextLong()));
        return null;
    }

    private static JsonElement particle(MinecraftServer s, JsonObject a) {
        ServerPlayer p = player(s, a);
        String cmd = String.format(java.util.Locale.ROOT, "particle %s %f %f %f %f %f %f %f %d force",
                a.get("type").getAsString(), number(a, "x", p.getX()), number(a, "y", p.getY() + 1),
                number(a, "z", p.getZ()), number(a, "dx", 0), number(a, "dy", 0), number(a, "dz", 0),
                number(a, "speed", 0), integer(a, "count", 1));
        s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), cmd);
        return new JsonPrimitive(cmd);
    }

    // ---- items, stands, menus --------------------------------------------------------------------------------

    /** A stack from vanilla item syntax ({@code minecraft:bow[custom_data={id:"TERMINATOR"}]}). */
    public static ItemStack stack(MinecraftServer s, String spec, int count) throws Exception {
        return new ItemParser(s.registryAccess()).parse(new StringReader(spec)).createItemStack(count);
    }

    private static JsonElement give(MinecraftServer s, JsonObject a) throws Exception {
        ServerPlayer p = player(s, a);
        int slot = integer(a, "slot", 0);
        ItemStack stack = stack(s, a.get("stack").getAsString(), integer(a, "count", 1));
        p.getInventory().setItem(slot, stack);
        p.inventoryMenu.broadcastChanges();
        p.containerMenu.broadcastChanges();
        JsonObject out = new JsonObject();
        out.addProperty("slot", slot);
        out.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        out.addProperty("count", stack.getCount());
        return out;
    }

    private static JsonElement stand(MinecraftServer s, JsonObject a) throws Exception {
        ServerPlayer p = player(s, a);
        ServerLevel level = (ServerLevel) p.level();
        ArmorStand stand = new ArmorStand(level, number(a, "x", p.getX()), number(a, "y", p.getY()),
                number(a, "z", p.getZ()));
        stand.setNoGravity(true);
        stand.setInvisible(bool(a, "invisible", true));
        ((InvokerArmorStand) stand).testkit$setSmall(bool(a, "small", false));
        ((InvokerArmorStand) stand).testkit$setMarker(bool(a, "marker", true));
        if (a.has("name") || a.has("nameJson")) {
            stand.setCustomName(text(s, a, "name"));
            stand.setCustomNameVisible(bool(a, "nameVisible", true));
        }
        if (a.has("helmet")) {
            stand.setItemSlot(EquipmentSlot.HEAD, stack(s, a.get("helmet").getAsString(), 1));
        }
        if (!level.addFreshEntity(stand)) {
            throw new IllegalStateException("the level refused the armour stand");
        }
        STANDS.put(stand.getUUID(), stand);
        JsonObject out = new JsonObject();
        out.addProperty("uuid", stand.getUUID().toString());
        out.addProperty("id", stand.getId());
        return out;
    }

    private static JsonElement entityRemove(MinecraftServer s, JsonObject a) {
        int removed = 0;
        if (bool(a, "all", false)) {
            for (Entity e : STANDS.values()) {
                e.discard();
                removed++;
            }
            STANDS.clear();
        } else {
            Entity e = STANDS.remove(UUID.fromString(a.get("uuid").getAsString()));
            if (e != null) {
                e.discard();
                removed++;
            }
        }
        return new JsonPrimitive(removed);
    }

    private static JsonElement menuOpen(MinecraftServer s, JsonObject a) throws Exception {
        ServerPlayer p = player(s, a);
        int rows = integer(a, "rows", 6);
        String script = a.has("script") ? a.get("script").getAsString() : null;
        if (script != null && !HxChestMenu.hasScript(script)) {
            throw new IllegalArgumentException("no menu script '" + script + "' registered");
        }
        SimpleContainer items = new SimpleContainer(rows * 9);
        if (a.has("slots")) {
            for (var entry : a.getAsJsonObject("slots").entrySet()) {
                items.setItem(Integer.parseInt(entry.getKey()), stack(s, entry.getValue().getAsString(), 1));
            }
        }
        Component title = text(s, a, "title");
        String plainTitle = title.getString();
        var opened = p.openMenu(new SimpleMenuProvider(
                (id, inventory, player) -> new HxChestMenu(id, inventory, rows, items, plainTitle, script), title));
        if (opened.isEmpty()) {
            throw new IllegalStateException("openMenu returned no container id");
        }
        JsonObject out = new JsonObject();
        out.addProperty("containerId", opened.getAsInt());
        return out;
    }

    // ---- command stubs ---------------------------------------------------------------------------------------

    private static JsonElement cmdStub(MinecraftServer s, JsonObject a) {
        String reply = a.has("reply") ? a.get("reply").getAsString() : null;
        var dispatcher = s.getCommands().getDispatcher();
        JsonArray done = new JsonArray();
        for (JsonElement el : a.getAsJsonArray("names")) {
            String name = el.getAsString();
            com.mojang.brigadier.Command<CommandSourceStack> answer = ctx -> {
                if (reply != null) {
                    ctx.getSource().sendSystemMessage(Component.literal(reply));
                }
                return 1;
            };
            dispatcher.register(Commands.literal(name).executes(answer)
                    .then(Commands.argument("args", StringArgumentType.greedyString()).executes(answer)));
            STUBBED.add(name);
            done.add(name);
        }
        // The client only offers (and its own dispatcher only forwards) commands in the tree it was sent.
        for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            s.getCommands().sendCommands(p);
        }
        return done;
    }
}
