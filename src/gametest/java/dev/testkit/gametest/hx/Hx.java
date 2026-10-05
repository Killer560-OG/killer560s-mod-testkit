package dev.testkit.gametest.hx;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.testkit.gametest.TestServer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Client for the Hx RPC bridge in the companion server mod ({@code dev.testkit.server.hx.HxBridge}): drives the test
 * server as if it were Hypixel. One JSON object per line over a loopback socket, request and reply.
 *
 * <pre>{@code
 * try (Hx hx = Hx.connect()) {
 *     hx.sidebar("SKYBLOCK", "The Catac§combs §7(F7)");
 *     hx.overlay("§c1234/2345❤     §b890/900✎ Mana");
 *     hx.give(0, "minecraft:bow[custom_data={id:\"TERMINATOR\"}]");
 *     long since = hx.head();
 *     ... the client does something ...
 *     List<JsonObject> cmds = hx.events(since, "cmd");
 * }
 * }</pre>
 *
 * <p>Call it from the TEST thread, never from inside {@code ctx.runOnClient}: every op waits for the server thread,
 * and a client thread blocked on the server is the deadlock AGENTS/CLAUDE.md warn about. A failed op throws
 * {@link AssertionError} with the server's own reason ({@code unknown op ...}, a bad item string, ...).
 *
 * <p>Frozen after WP1. Op reference: the javadoc of {@code HxPrimitives} (core ops) and each WP's module.
 */
public final class Hx implements AutoCloseable {

    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    private final Socket socket;
    private final BufferedReader in;
    private final Writer out;
    private long nextId = 1;

    private Hx(Socket socket) throws IOException {
        this.socket = socket;
        socket.setSoTimeout(READ_TIMEOUT_MS);
        this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
    }

    /** Connect to this checkout's bridge ({@link TestServer#hxPort()}), retrying while the server finishes starting. */
    public static Hx connect() {
        return connect(TestServer.hxPort());
    }

    public static Hx connect(int port) {
        long deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS;
        IOException last = null;
        while (System.currentTimeMillis() < deadline) {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2000);
                Hx hx = new Hx(s);
                hx.call("ping");
                return hx;
            } catch (IOException e) {
                last = e;
                try {
                    s.close();
                } catch (IOException ignored) {
                    // retrying
                }
                try {
                    Thread.sleep(250);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw new AssertionError("[hx] could not reach the Hx bridge on 127.0.0.1:" + port + " within "
                + CONNECT_TIMEOUT_MS / 1000 + " s (" + last + "). Look for '[hx] bridge' in "
                + "build/testserver-console.log: 'could NOT bind' means the port is taken (another checkout on the "
                + "same -Pport?), no line at all means the companion mod did not load.");
    }

    // ---- raw --------------------------------------------------------------------------------------------------

    /** Build an args object from alternating keys and values: {@code args("text", "hi", "overlay", true)}. */
    public static JsonObject args(Object... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("args() takes key/value pairs");
        }
        JsonObject o = new JsonObject();
        for (int i = 0; i < kv.length; i += 2) {
            o.add(String.valueOf(kv[i]), json(kv[i + 1]));
        }
        return o;
    }

    /** Java value to JSON: strings, numbers, booleans, JsonElements, Lists/arrays, Maps. */
    public static JsonElement json(Object v) {
        if (v == null) {
            return com.google.gson.JsonNull.INSTANCE;
        }
        if (v instanceof JsonElement e) {
            return e;
        }
        if (v instanceof String s) {
            return new com.google.gson.JsonPrimitive(s);
        }
        if (v instanceof Number n) {
            return new com.google.gson.JsonPrimitive(n);
        }
        if (v instanceof Boolean b) {
            return new com.google.gson.JsonPrimitive(b);
        }
        if (v instanceof Object[] arr) {
            return json(List.of(arr));
        }
        if (v instanceof Iterable<?> it) {
            JsonArray a = new JsonArray();
            it.forEach(x -> a.add(json(x)));
            return a;
        }
        if (v instanceof Map<?, ?> m) {
            JsonObject o = new JsonObject();
            m.forEach((k, x) -> o.add(String.valueOf(k), json(x)));
            return o;
        }
        throw new IllegalArgumentException("cannot send " + v.getClass().getName() + " to Hx");
    }

    public JsonElement call(String op) {
        return call(op, new JsonObject());
    }

    public JsonElement call(String op, Object... kv) {
        return call(op, args(kv));
    }

    /** Send one op and wait for its reply. Throws AssertionError with the server's message on failure. */
    public synchronized JsonElement call(String op, JsonObject args) {
        long id = nextId++;
        JsonObject request = new JsonObject();
        request.addProperty("id", id);
        request.addProperty("op", op);
        request.add("args", args);
        String line;
        try {
            out.write(request.toString());
            out.write("\n");
            out.flush();
            line = in.readLine();
        } catch (IOException e) {
            throw new AssertionError("[hx] " + op + ": bridge connection failed: " + e, e);
        }
        if (line == null) {
            throw new AssertionError("[hx] " + op + ": the bridge closed the connection (server stopped?)");
        }
        JsonObject reply = JsonParser.parseString(line).getAsJsonObject();
        if (reply.get("id").getAsLong() != id) {
            throw new AssertionError("[hx] " + op + ": reply out of order: " + line);
        }
        if (!reply.get("ok").getAsBoolean()) {
            throw new AssertionError("[hx] " + reply.get("error").getAsString());
        }
        return reply.get("result");
    }

    // ---- convenience over the core ops ---------------------------------------------------------------------

    /** A chat line, legacy section-sign codes allowed. */
    public void chat(String text) {
        call("chat", "text", text, "overlay", false);
    }

    /** An ACTION BAR line the way Hypixel sends it: system chat with overlay=true. */
    public void overlay(String text) {
        call("chat", "text", text, "overlay", true);
    }

    /** The separate set-action-bar packet. Hypixel's stat parsers do not read this one; see docs/wp/hx.md. */
    public void actionBarPacket(String text) {
        call("actionbar.packet", "text", text);
    }

    public void title(String title, String subtitle) {
        call("title", "title", title, "subtitle", subtitle);
    }

    /** Hypixel-shaped sidebar: one team prefix per line on an invisible owner, top line first. */
    public void sidebar(String title, String... lines) {
        call("sidebar.set", "title", title, "lines", List.of(lines));
    }

    public void sidebarClear() {
        call("sidebar.clear");
    }

    /** Fake tab entries in column-major order (20 rows per column), optional header/footer (null to leave). */
    public void tab(List<String> entries, String header, String footer) {
        JsonObject a = args("entries", entries);
        if (header != null) {
            a.addProperty("header", header);
        }
        if (footer != null) {
            a.addProperty("footer", footer);
        }
        call("tab.set", a);
    }

    public void tabClear() {
        call("tab.clear");
    }

    /** Put a stack (vanilla item syntax) in an inventory slot (0-8 hotbar, 9-35 main, 36-39 armour, 40 offhand). */
    public void give(int slot, String stack) {
        call("give", "slot", slot, "stack", stack);
    }

    /** An armour stand; returns its UUID. Extra keys: small, marker, invisible, nameVisible, helmet. */
    public String stand(double x, double y, double z, String name, Object... extra) {
        JsonObject a = args(extra);
        a.addProperty("x", x);
        a.addProperty("y", y);
        a.addProperty("z", z);
        if (name != null) {
            a.addProperty("name", name);
        }
        return call("stand", a).getAsJsonObject().get("uuid").getAsString();
    }

    public void removeStands() {
        call("entity.remove", "all", true);
    }

    /** Open a chest menu; {@code slots} maps slot index to a stack string. Returns the container id. */
    public int menu(String title, int rows, Map<Integer, String> slots, String script) {
        JsonObject a = args("title", title, "rows", rows);
        JsonObject s = new JsonObject();
        slots.forEach((k, v) -> s.addProperty(String.valueOf(k), v));
        a.add("slots", s);
        if (script != null) {
            a.addProperty("script", script);
        }
        return call("menu.open", a).getAsJsonObject().get("containerId").getAsInt();
    }

    /** Make commands parse on this server (and answer {@code reply} if given). They are logged like any other. */
    public void stub(String reply, String... names) {
        JsonObject a = args("names", List.of(names));
        if (reply != null) {
            a.addProperty("reply", reply);
        }
        call("cmd.stub", a);
    }

    public void kick(String reason) {
        call("kick", "reason", reason);
    }

    public void sound(String id, double volume, double pitch) {
        call("sound", "id", id, "volume", volume, "pitch", pitch);
    }

    // ---- events ----------------------------------------------------------------------------------------------

    /** The newest event seq: take it BEFORE making the client act, then read {@link #events} since it. */
    public long head() {
        return call("events.head").getAsLong();
    }

    /** Events after {@code since}, optionally only those of the given types. */
    public List<JsonObject> events(long since, String... types) {
        JsonObject r = call("events.poll", "since", since).getAsJsonObject();
        if (r.get("dropped").getAsBoolean()) {
            System.out.println("[hx] WARNING: events after " + since + " were dropped from the ring; poll sooner");
        }
        List<JsonObject> out = new ArrayList<>();
        for (JsonElement e : r.getAsJsonArray("events")) {
            JsonObject o = e.getAsJsonObject();
            if (types.length == 0 || List.of(types).contains(o.get("type").getAsString())) {
                out.add(o);
            }
        }
        return out;
    }

    /** The commands the client sent since {@code since}, as typed without the slash. */
    public List<String> commands(long since) {
        List<String> out = new ArrayList<>();
        for (JsonObject e : events(since, "cmd")) {
            out.add(e.get("command").getAsString());
        }
        return out;
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // closing anyway
        }
    }
}
