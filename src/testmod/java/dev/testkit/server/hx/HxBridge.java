package dev.testkit.server.hx;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import net.minecraft.server.MinecraftServer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * The Hx RPC bridge: lets a gametest client drive this server as if it were Hypixel ("Hx").
 *
 * <p>Why not the console the harness already writes to: stdin is one-way (no reply, no error), and it mangles
 * anything outside the console's codepage - every Hypixel line has a section sign or a private-use icon in it. So
 * this is a loopback {@link ServerSocket} speaking JSON, one object per line, both ways:
 *
 * <pre>
 * -&gt; {"id": 7, "op": "sidebar.set", "args": {"title": "SKYBLOCK", "lines": ["The Catac§combs §7(F7)"]}}
 * &lt;- {"id": 7, "ok": true, "result": {...}}
 * &lt;- {"id": 8, "ok": false, "error": "unknown op 'x' (known: ...)"}
 * </pre>
 *
 * <p>Every handler runs ON THE SERVER THREAD ({@code server.submit(...).get(timeout)}), so handlers may touch the
 * world, the scoreboard and connections freely. The port is {@code -Dtestkit.hx.port} (server port + 5; build.gradle
 * passes it to both JVMs) and the socket binds to loopback only. Handlers are registered by {@link HxModule}s listed
 * in {@link HxModules}; a duplicate op name is a startup error, not a silent override.
 *
 * <p>Frozen after WP1: a WP adds ops through its own module, never by editing this class.
 */
public final class HxBridge {

    /** How long one op may take on the server thread before the client is told it timed out. */
    private static final int OP_TIMEOUT_SECONDS = 20;

    /** One op: arguments in, a JSON result out (null is fine). Throw to report an error to the caller. */
    @FunctionalInterface
    public interface Handler {
        JsonElement handle(MinecraftServer server, JsonObject args) throws Exception;
    }

    private static final Map<String, Handler> OPS = new ConcurrentHashMap<>();
    private static volatile MinecraftServer server;
    private static volatile ServerSocket socket;

    private HxBridge() {
    }

    /** Called once from {@code TestKitServer.onInitializeServer}. */
    public static void init() {
        HxModules.registerAll();
        HxEvents.register();
        ServerLifecycleEvents.SERVER_STARTED.register(HxBridge::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> stop());
    }

    /** Register an op. Names are {@code area.verb}; a module's ops start with its own prefix. */
    public static void register(String op, Handler handler) {
        if (OPS.putIfAbsent(op, handler) != null) {
            throw new IllegalStateException("[hx] op registered twice: " + op);
        }
    }

    /** The running server, or null before SERVER_STARTED. */
    public static MinecraftServer server() {
        return server;
    }

    /** True on the server thread. Packet-handler mixins use it to record a packet once, not once per thread hop. */
    public static boolean onServerThread() {
        MinecraftServer s = server;
        return s != null && s.isSameThread();
    }

    public static java.util.Set<String> ops() {
        return new java.util.TreeSet<>(OPS.keySet());
    }

    private static void start(MinecraftServer started) {
        server = started;
        int port = Integer.getInteger("testkit.hx.port", -1);
        if (port <= 0) {
            System.out.println("[hx] no -Dtestkit.hx.port, bridge not started");
            return;
        }
        try {
            ServerSocket listening = new ServerSocket(port, 16, InetAddress.getLoopbackAddress());
            socket = listening;
            Thread accept = new Thread(() -> acceptLoop(listening), "hx-accept");
            accept.setDaemon(true);
            accept.start();
            // TestServer and Hx.connect key on this exact line.
            System.out.println("[hx] bridge listening on 127.0.0.1:" + port + " with " + OPS.size() + " op(s)");
        } catch (IOException e) {
            // Loud: a run whose bridge is down must fail at Hx.connect with this reason, not time out later.
            System.out.println("[hx] bridge could NOT bind 127.0.0.1:" + port + ": " + e);
        }
    }

    private static void stop() {
        ServerSocket s = socket;
        socket = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // closing anyway
            }
        }
    }

    private static void acceptLoop(ServerSocket listening) {
        while (!listening.isClosed()) {
            try {
                Socket client = listening.accept();
                Thread t = new Thread(() -> serve(client), "hx-conn-" + client.getPort());
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (!listening.isClosed()) {
                    System.out.println("[hx] accept error: " + e);
                }
            }
        }
    }

    private static void serve(Socket client) {
        try (client;
             BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
             Writer out = new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonObject reply = dispatch(line);
                out.write(reply.toString());
                out.write("\n");
                out.flush();
            }
        } catch (IOException ignored) {
            // the client went away; nothing to answer
        }
    }

    private static JsonObject dispatch(String line) {
        JsonObject reply = new JsonObject();
        JsonElement id = JsonNull.INSTANCE;
        String op = "?";
        try {
            JsonObject request = JsonParser.parseString(line).getAsJsonObject();
            id = request.has("id") ? request.get("id") : JsonNull.INSTANCE;
            op = request.get("op").getAsString();
            JsonObject args = request.has("args") && request.get("args").isJsonObject()
                    ? request.getAsJsonObject("args") : new JsonObject();
            Handler handler = OPS.get(op);
            if (handler == null) {
                throw new IllegalArgumentException("unknown op '" + op + "' (known: " + String.join(", ", ops()) + ")");
            }
            MinecraftServer s = server;
            if (s == null) {
                throw new IllegalStateException("server not started yet");
            }
            JsonElement result = s.submit(() -> {
                try {
                    return handler.handle(s, args);
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).get(OP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            reply.add("id", id);
            reply.addProperty("ok", true);
            reply.add("result", result == null ? JsonNull.INSTANCE : result);
        } catch (Throwable t) {
            Throwable cause = t;
            while ((cause instanceof java.util.concurrent.ExecutionException || cause instanceof RuntimeException)
                    && cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            reply.add("id", id);
            reply.addProperty("ok", false);
            reply.addProperty("error", op + ": " + cause);
            System.out.println("[hx] op " + op + " errored: " + cause);
        }
        return reply;
    }

    /** For the {@code ops} op: every registered op name, sorted. */
    static JsonElement describe() {
        JsonObject out = new JsonObject();
        com.google.gson.JsonArray names = new com.google.gson.JsonArray();
        ops().forEach(names::add);
        out.add("ops", names);
        out.addProperty("count", OPS.size());
        return out;
    }
}
