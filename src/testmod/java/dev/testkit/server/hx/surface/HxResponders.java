package dev.testkit.server.hx.surface;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;

import dev.testkit.server.hx.HxEvents;
import dev.testkit.server.hx.HxPrimitives;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Hypixel command RESPONDERS: when the client sends {@code /<root> ...}, the server answers the way Hypixel would, so a
 * feature that sends a command and then parses the answer can be driven end to end.
 *
 * <p>A rule is {@code {root, match?, replies[], times?}}: {@code match} is a Java regex over the whole command without
 * its slash ({@code "fl( 1)?"}, matched with {@code matches()}), omitted = any command under {@code root}. The NEWEST
 * matching rule answers; {@code times} (default unlimited) counts down and removes the rule at zero. Each reply is a
 * string (legacy section-sign codes) or {@code {text|textJson, overlay?}} - the same text forms as the core
 * {@code chat} op, so a click-event "next page" arrow is a {@code textJson}.
 *
 * <p>Every answer is recorded as a {@code surface.reply} event ({@code root, command, rule, lines}), next to the
 * core {@code cmd} event the Commands mixin already records, so a test can assert both "the mod sent /fl 2" and "the
 * server answered page 2". A command under a registered root with no matching rule is answered by nothing (and
 * recorded as {@code surface.unanswered}).
 *
 * <p>Brigadier cannot unregister, so each root is registered once ({@code root} and {@code root <greedy args>}) and
 * stays; the rules behind it change freely. The command tree is re-sent to every player after a new root, because
 * the client only forwards commands it was told exist.
 */
final class HxResponders {

    private record Rule(int id, String root, Pattern match, JsonArray replies, int[] times) {
    }

    private static final List<Rule> RULES = new ArrayList<>();
    private static final Set<String> ROOTS = new LinkedHashSet<>();
    private static int nextId = 1;

    private HxResponders() {
    }

    /** {@code surface.respond}: add a rule; returns its id. */
    static JsonElement add(MinecraftServer s, JsonObject a) {
        String root = a.get("root").getAsString().toLowerCase(java.util.Locale.ROOT);
        if (root.startsWith("/")) {
            root = root.substring(1);
        }
        Pattern match = a.has("match") ? Pattern.compile(a.get("match").getAsString()) : null;
        JsonArray replies = a.has("replies") ? a.getAsJsonArray("replies") : new JsonArray();
        int times = a.has("times") ? a.get("times").getAsInt() : -1;
        Rule rule = new Rule(nextId++, root, match, replies.deepCopy(), new int[]{times});
        RULES.add(rule);
        ensureRoot(s, root);
        JsonObject out = new JsonObject();
        out.addProperty("rule", rule.id());
        out.addProperty("root", root);
        return out;
    }

    /** {@code surface.respond.clear {root?}}: drop rules (all, or one root's). Roots stay registered. */
    static JsonElement clear(JsonObject a) {
        String root = a.has("root") ? a.get("root").getAsString() : null;
        int before = RULES.size();
        RULES.removeIf(r -> root == null || r.root().equals(root));
        JsonObject out = new JsonObject();
        out.addProperty("removed", before - RULES.size());
        return out;
    }

    static JsonArray describe() {
        JsonArray out = new JsonArray();
        for (Rule r : RULES) {
            JsonObject o = new JsonObject();
            o.addProperty("rule", r.id());
            o.addProperty("root", r.root());
            o.addProperty("match", r.match() == null ? "" : r.match().pattern());
            o.addProperty("replies", r.replies().size());
            o.addProperty("times", r.times()[0]);
            out.add(o);
        }
        return out;
    }

    /** Register {@code root} (and {@code root <args>}) with Brigadier once. */
    static void ensureRoot(MinecraftServer s, String root) {
        if (!ROOTS.add(root)) {
            return;
        }
        var dispatcher = s.getCommands().getDispatcher();
        com.mojang.brigadier.Command<CommandSourceStack> bare = ctx -> answer(s, ctx.getSource(), root);
        com.mojang.brigadier.Command<CommandSourceStack> withArgs = ctx -> answer(s, ctx.getSource(),
                root + " " + StringArgumentType.getString(ctx, "args"));
        dispatcher.register(Commands.literal(root).executes(bare)
                .then(Commands.argument("args", StringArgumentType.greedyString()).executes(withArgs)));
        for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            s.getCommands().sendCommands(p);
        }
    }

    private static int answer(MinecraftServer s, CommandSourceStack source, String command) {
        ServerPlayer player = source.getPlayer();
        String root = command.contains(" ") ? command.substring(0, command.indexOf(' ')) : command;
        for (int i = RULES.size() - 1; i >= 0; i--) {
            Rule r = RULES.get(i);
            if (!r.root().equals(root) || (r.match() != null && !r.match().matcher(command).matches())) {
                continue;
            }
            int sent = 0;
            for (JsonElement reply : r.replies()) {
                JsonObject args;
                if (reply.isJsonObject()) {
                    args = reply.getAsJsonObject();
                } else {
                    args = new JsonObject();
                    args.addProperty("text", reply.getAsString());
                }
                boolean overlay = args.has("overlay") && args.get("overlay").getAsBoolean();
                Component text = HxPrimitives.text(s, args, "text");
                if (player != null) {
                    player.connection.send(new ClientboundSystemChatPacket(text, overlay));
                }
                sent++;
            }
            if (r.times()[0] > 0 && --r.times()[0] == 0) {
                RULES.remove(i);
            }
            JsonObject e = new JsonObject();
            e.addProperty("root", root);
            e.addProperty("command", command);
            e.addProperty("rule", r.id());
            e.addProperty("lines", sent);
            HxEvents.custom("surface.reply", player, e);
            return 1;
        }
        JsonObject e = new JsonObject();
        e.addProperty("root", root);
        e.addProperty("command", command);
        HxEvents.custom("surface.unanswered", player, e);
        return 1;
    }
}
