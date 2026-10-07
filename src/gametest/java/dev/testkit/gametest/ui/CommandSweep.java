package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;

import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Case 306: every client command root - parse and suggestions along every path, execute an allowlist, root floor.
 */
final class CommandSweep {

    /** Measured on mod 8c43a6d in a singleplayer world (see docs/wp/ui.md). */
    static final int ROOT_FLOOR = 30;
    private static final int MAX_DEPTH = 6;
    private static final List<String> GATED = new ArrayList<>();

    private CommandSweep() {
    }

    @SuppressWarnings("unchecked")
    static CommandDispatcher<Object> dispatcher() {
        // Fabric API for 26.1 renamed ClientCommandManager to ClientCommands (the mod imports
        // net.fabricmc.fabric.api.client.command.v2.ClientCommands, autokick/AutoKickCommands.java:7); older names kept
        // as a fallback. The dispatcher getter is found by return type, so a renamed method is not a silent miss.
        List<String> tried = new ArrayList<>();
        for (String name : new String[]{"net.fabricmc.fabric.api.client.command.v2.ClientCommands",
                "net.fabricmc.fabric.api.client.command.v2.ClientCommandManager"}) {
            try {
                Class<?> k = Class.forName(name);
                for (java.lang.reflect.Method m : k.getMethods()) {
                    if (java.lang.reflect.Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 0
                            && CommandDispatcher.class.isAssignableFrom(m.getReturnType())) {
                        return (CommandDispatcher<Object>) m.invoke(null);
                    }
                }
                List<String> ms = new ArrayList<>();
                for (java.lang.reflect.Method m : k.getMethods()) {
                    ms.add(m.getName());
                }
                tried.add(name + " has no static dispatcher getter; methods " + ms);
            } catch (ReflectiveOperationException e) {
                tried.add(name + ": " + e);
            }
        }
        throw new AssertionError("[ui] no Fabric client command dispatcher: " + tried);
    }

    static Object source(Minecraft mc) {
        return mc.player.connection.getSuggestionsProvider();
    }

    static void commands(UiCase c, Deny deny) {
        // ---- walk: every root, every literal path, parse + suggestions ----
        GATED.clear();
        List<String> roots = new ArrayList<>();
        List<String> inputs = new ArrayList<>();
        Map<String, CompletableFuture<Suggestions>> pending = new LinkedHashMap<>();
        c.onClient(mc -> {
            CommandDispatcher<Object> d = dispatcher();
            c.check(d != null && mc.player != null, "no active client dispatcher - not in a world?");
            Object src = source(mc);
            for (CommandNode<Object> root : d.getRoot().getChildren()) {
                roots.add(root.getName());
                walk(c, d, src, root, "", 0, inputs, pending);
            }
            // Hypixel commands the mod shadows must still take Hypixel's arguments: "/bz rec" failed on the client
            // with "Incorrect argument for command at position 3" and never reached Hypixel (killer560, 2026-10-06).
            for (String line : List.of("bz rec", "bz Recombobulator 3000", "ah Technoblade", "hypixelbz rec",
                    "hypixelah Technoblade")) {
                if (d.getRoot().getChild(line.substring(0, line.indexOf(' '))) == null) {
                    continue;   // not registered by this jar (legit builds may lack a root)
                }
                com.mojang.brigadier.ParseResults<Object> p = d.parse(line, src);
                c.check(!p.getReader().canRead() && p.getContext().getCommand() != null
                                && p.getExceptions().isEmpty(),
                        "'/" + line + "' does not parse to a command (stops at " + p.getReader().getCursor()
                                + ": the client would reject it instead of forwarding it to Hypixel)");
            }
            return null;
        });
        int suggested = 0;
        int suggestions = 0;
        for (Map.Entry<String, CompletableFuture<Suggestions>> e : pending.entrySet()) {
            try {
                Suggestions s = e.getValue().get(3, TimeUnit.SECONDS);
                suggested++;
                suggestions += s.getList().size();
            } catch (java.util.concurrent.TimeoutException te) {
                c.problem("suggestions for '" + e.getKey() + "' did not complete in 3 s");
            } catch (Exception ex) {
                c.problem("suggestions for '" + e.getKey() + "' failed: " + UiCase.describe(ex));
            }
        }
        c.note(roots.size() + " client command roots: " + roots);
        c.note(GATED.size() + " node(s) gated by .requires for this source (not walked): " + GATED);
        c.note("parsed " + inputs.size() + " inputs, " + suggested + " suggestion requests completed with "
                + suggestions + " suggestions in all");
        if (roots.size() < ROOT_FLOOR) {
            c.problem("only " + roots.size() + " client command roots; floor " + ROOT_FLOOR);
        }

        // ---- coverage of the lists ----
        Set<String> listed = new LinkedHashSet<>();
        for (JsonElement e : deny.commands.getAsJsonArray("execute")) {
            listed.add(e.getAsJsonObject().get("input").getAsString().split(" ")[0]);
        }
        for (JsonElement e : deny.commands.getAsJsonArray("parseOnly")) {
            listed.add(e.getAsJsonObject().get("root").getAsString());
        }
        List<String> unlisted = new ArrayList<>();
        for (String r : roots) {
            if (!listed.contains(r)) {
                unlisted.add(r);
            }
        }
        if (!unlisted.isEmpty()) {
            c.problem("command roots in neither deny-commands.json list (decide execute or parseOnly): " + unlisted);
        }

        // ---- execute the allowlist ----
        int executed = 0;
        for (JsonElement e : deny.commands.getAsJsonArray("execute")) {
            JsonObject o = e.getAsJsonObject();
            String input = o.get("input").getAsString();
            String expect = o.get("expect").getAsString();
            if (!roots.contains(input.split(" ")[0])) {
                c.note("'" + input + "': root not registered in this jar, skipped");
                continue;
            }
            if (expect.startsWith("flip:")) {
                String[] cm = expect.substring(5).split("#");
                boolean before = (Boolean) Mod.staticCall(cm[0], cm[1]);
                String err = execute(c, input);
                c.ticks(1);
                boolean mid = (Boolean) Mod.staticCall(cm[0], cm[1]);
                String err2 = execute(c, input);
                c.ticks(1);
                boolean after = (Boolean) Mod.staticCall(cm[0], cm[1]);
                if (err != null || err2 != null) {
                    c.problem("'/" + input + "' threw: " + (err != null ? err : err2));
                } else if (mid == before || after != before) {
                    c.problem("'/" + input + "' twice: " + cm[1] + " went " + before + " -> " + mid + " -> " + after
                            + " (expected a flip and back)");
                } else {
                    executed += 2;
                }
                continue;
            }
            String err = execute(c, input);
            c.ticks(3);
            if (err != null) {
                c.problem("'/" + input + "' threw: " + err);
                continue;
            }
            executed++;
            if (expect.startsWith("screen:")) {
                String want = Mod.ROOT + expect.substring(7);
                String got = c.onClient(mc -> McCompat.screen(mc) == null ? "none" : McCompat.screen(mc).getClass().getName());
                if (!got.equals(want)) {
                    c.problem("'/" + input + "' should open " + expect.substring(7) + " but the screen is "
                            + got.replace(Mod.ROOT, ""));
                } else {
                    Frames.Drawn drawn = c.onClient(mc -> Frames.extract(mc, McCompat.screen(mc), -1, -1));
                    if (drawn.total() == 0) {
                        c.problem("'/" + input + "' opened " + expect.substring(7) + " but it drew nothing");
                    }
                }
                c.onClient(mc -> {
                    McCompat.setScreen(mc, null);
                    return null;
                });
                c.ticks(1);
            }
        }
        c.note("executed " + executed + " allowlisted command(s)");
        c.check(executed >= 10, "only " + executed + " allowlisted commands executed");
    }

    /** Execute one command as typed (without the slash) on the client thread; returns the error, or null. */
    static String execute(UiCase c, String input) {
        return c.onClient(mc -> {
            try {
                dispatcher().execute(input, source(mc));
                return null;
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
                return "syntax: " + e.getMessage();
            } catch (Throwable t) {
                return UiCase.describe(t);
            }
        });
    }

    private static void walk(UiCase c, CommandDispatcher<Object> d, Object src, CommandNode<Object> node,
                             String prefix, int depth, List<String> inputs,
                             Map<String, CompletableFuture<Suggestions>> pending) {
        if (depth > MAX_DEPTH || inputs.size() > 3000) {
            return;
        }
        String token;
        if (node instanceof LiteralCommandNode<Object> lit) {
            token = lit.getLiteral();
        } else if (node instanceof ArgumentCommandNode<Object, ?> arg) {
            token = sample(arg.getType());
        } else {
            return;
        }
        String input = prefix.isEmpty() ? token : prefix + " " + token;
        // A node behind .requires(...) the source does not meet (the sim's commands outside a sim world) parses to
        // nothing by design: counted as gated, its subtree is not walked.
        if (!node.canUse(src)) {
            GATED.add(input);
            return;
        }
        inputs.add(input);
        try {
            ParseResults<Object> parsed = d.parse(input, src);
            // suggestions for what comes after this node (the menu a player sees after a space)
            ParseResults<Object> next = d.parse(input + " ", src);
            pending.put(input + " ", d.getCompletionSuggestions(next));
            if (parsed.getExceptions().isEmpty() && parsed.getContext().getNodes().isEmpty()) {
                c.problem("'" + input + "' parsed to no nodes");
            }
        } catch (Throwable t) {
            c.problem("parse/suggest '" + input + "' threw " + UiCase.describe(t));
            return;
        }
        // a redirect/fork node has no children of its own; do not follow redirects (loops)
        for (CommandNode<Object> child : node.getChildren()) {
            walk(c, d, src, child, input, depth + 1, inputs, pending);
        }
    }

    private static String sample(ArgumentType<?> type) {
        if (type instanceof IntegerArgumentType || type instanceof LongArgumentType) {
            return "1";
        }
        if (type instanceof DoubleArgumentType || type instanceof FloatArgumentType) {
            return "1.5";
        }
        if (type instanceof BoolArgumentType) {
            return "true";
        }
        return "test";
    }
}
