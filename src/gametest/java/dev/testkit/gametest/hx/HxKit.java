package dev.testkit.gametest.hx;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.testkit.gametest.Fixtures;
import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.mod.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * WP2's shared helpers for {@code 1NN-hx-*} cases: replaying fixtures through Hx, reading what the mod printed, and
 * waiting for commands. Everything here goes through the frozen WP1 API ({@link Hx}, {@link Session}, {@link Mod}).
 */
public final class HxKit {

    private HxKit() {
    }

    // ---- fixtures ---------------------------------------------------------------------------------------------

    /** A fixture by id, after checking it against the LOADED mod's patterns (a disagreement fails the case). */
    public static Fixtures.Fixture fx(Session c, String id) {
        Fixtures.Fixture f = Fixtures.byId(id);
        List<String> schema = Fixtures.validate(f);
        c.check(schema.isEmpty(), "fixture " + id + " is invalid: " + schema);
        List<String> agree = Fixtures.checkPatterns(f);
        c.check(agree.isEmpty(), "fixture " + id + " disagrees with the mod: " + agree);
        return f;
    }

    /** Send a fixture the way its kind arrives from Hypixel. */
    public static void send(Session c, Fixtures.Fixture f) {
        Hx hx = c.hx();
        switch (f.kind()) {
            case "chat" -> sendText(hx, f.payload(), false);
            case "overlay" -> sendText(hx, f.payload(), true);
            case "title" -> {
                if (f.payload().isJsonObject()) {
                    hx.call("title", f.payload().getAsJsonObject().deepCopy());
                } else {
                    hx.title(f.payload().getAsString(), "");
                }
            }
            case "sidebar" -> hx.call("sidebar.set", f.payload().getAsJsonObject().deepCopy());
            case "tab" -> hx.call("tab.set", f.payload().getAsJsonObject().deepCopy());
            case "item" -> hx.give(f.json().has("slot") ? f.json().get("slot").getAsInt() : 0,
                    f.payload().getAsJsonObject().get("stack").getAsString());
            default -> throw new AssertionError("[hx] cannot send a fixture of kind " + f.kind());
        }
    }

    /** Fixture id -> send it (after the pattern check). */
    public static Fixtures.Fixture send(Session c, String id) {
        Fixtures.Fixture f = fx(c, id);
        send(c, f);
        return f;
    }

    private static void sendText(Hx hx, JsonElement payload, boolean overlay) {
        if (payload.isJsonPrimitive()) {
            hx.call("chat", "text", payload.getAsString(), "overlay", overlay);
        } else {
            JsonObject p = payload.getAsJsonObject();
            hx.call("chat", "textJson", p.get("json"), "overlay", overlay);
        }
    }

    /** The plain text of a chat/overlay fixture. */
    public static String text(Fixtures.Fixture f) {
        return f.texts().isEmpty() ? "" : f.texts().get(0);
    }

    // ---- what the mod printed --------------------------------------------------------------------------------

    /** Client log lines (mod loggers and every shown chat line, "[System] [CHAT] ...") since {@code mark}. */
    public static List<String> logSince(long mark) {
        return LogTap.since(mark);
    }

    /** Lines since {@code mark} containing every one of {@code parts}. */
    public static List<String> logLines(long mark, String... parts) {
        List<String> out = new ArrayList<>();
        for (String line : LogTap.since(mark)) {
            boolean all = true;
            for (String p : parts) {
                if (!line.contains(p)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                out.add(line);
            }
        }
        return out;
    }

    // ---- commands and events ---------------------------------------------------------------------------------

    /** Wait until the client has sent a command starting with {@code prefix} (no slash) this case; returns it. */
    public static String awaitCommand(Session c, String prefix, int ticks) {
        c.waitUntil("a /" + prefix + "... command at the server",
                mc -> c.commands().stream().anyMatch(s -> s.startsWith(prefix)), ticks);
        return c.commands().stream().filter(s -> s.startsWith(prefix)).findFirst().orElseThrow();
    }

    /** Commands this case starting with {@code prefix}. */
    public static List<String> commandsStarting(Session c, String prefix) {
        return c.commands().stream().filter(s -> s.startsWith(prefix)).toList();
    }

    /** Add a responder rule: when the client sends {@code /root ...} matching {@code match}, answer {@code replies}. */
    public static int respond(Session c, String root, String match, Object... replies) {
        JsonArray arr = new JsonArray();
        for (Object r : replies) {
            arr.add(Hx.json(r));
        }
        JsonObject a = Hx.args("root", root, "replies", arr);
        if (match != null) {
            a.addProperty("match", match);
        }
        return c.hx().call("surface.respond", a).getAsJsonObject().get("rule").getAsInt();
    }

    /** A JSON chat component: plain text with a run_command click event (Hypixel's "next page" arrows). */
    public static JsonObject clickable(String text, String command) {
        JsonObject o = new JsonObject();
        o.addProperty("text", text);
        JsonObject click = new JsonObject();
        click.addProperty("action", "run_command");
        click.addProperty("command", command);
        o.add("click_event", click);
        return o;
    }

    // ---- client state ----------------------------------------------------------------------------------------

    /** Read a value on the client thread. */
    public static <T> T read(Session c, Supplier<T> fn) {
        return c.onClient(mc -> fn.get());
    }

    /** Fail unless {@code mc.level != null} (the client is still connected). */
    public static void assertConnected(Session c, String after) {
        boolean connected = c.onClient(mc -> mc.level != null && mc.getConnection() != null);
        c.check(connected, "the client was DISCONNECTED after " + after);
    }
}
