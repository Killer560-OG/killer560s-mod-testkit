package dev.testkit.gametest.hx;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.testkit.gametest.mod.Mod;

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A fake killer560s-mod-relay room, put under the mod's own {@code relay.RelayClient} at the socket (tkdebt-1101, for
 * Team Melody's {@code dg.v1.melody}). The real relay cannot be reached from a test: the client is offline, and on 26.3
 * there is no Yggdrasil service for {@code RelayAuth}'s Mojang-signed login (579 is shelved there). So the connect and
 * login are skipped and everything after them is real:
 * <ul>
 *   <li><b>Outbound.</b> {@link #install} puts a {@link WebSocket} of its own into {@code RelayClient.socket} and marks the
 *       client CONNECTED in the room {@code ModChatFeature} already asked for, on the relay's own WORKER thread, the way
 *       {@code connect()} does after a login. Every packet the mod then sends ({@code sendData}, {@code sendChat}, pings) goes
 *       through the mod's own {@code send()} chain into {@link #sent()}.</li>
 *   <li><b>Inbound.</b> {@link #deliver} hands a packet to {@code RelayClient.handle}, the method the real socket's
 *       {@code onText} calls, so the mod's listeners ({@code PartyDataFeature}, Mod Chat, Song Tag) see it exactly as from
 *       the relay. Like the relay, the sender is stamped by the room ({@code from}), never taken from the payload.</li>
 *   <li><b>Another member.</b> {@link #mirrorAs} makes the room behave as if a second member running this mod saw what
 *       this client sent and sent the same back: each data packet this client sends is delivered to it again stamped
 *       {@code from} that name. The relay itself never echoes a packet to its sender; this is the other member's echo.</li>
 * </ul>
 * {@link #close} shuts the client's relay down (state OFF) and clears what it wanted, so the mod's next tick reconnects
 * the normal way (offline: it retries and fails, as before the case).
 */
public final class FakeRelay implements AutoCloseable {

    private static final String RC = "relay.RelayClient";

    private final List<JsonObject> sent = new CopyOnWriteArrayList<>();
    private final ExecutorService wire = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "testkit-fake-relay");
        t.setDaemon(true);
        return t;
    });
    private volatile String mirrorAs;
    private volatile boolean closed;
    private final String room;

    private FakeRelay(String room) {
        this.room = room;
    }

    /** The room {@code ModChatFeature} has asked the relay for ({@code RelayClient.wantRoom}), "" if none yet. */
    public static String wantedRoom() {
        Object r = Mod.field(RC, "wantRoom");
        return r == null ? "" : r.toString();
    }

    /**
     * Connect the mod's relay client to a fake room: the one it wants now ({@link #wantedRoom}), with {@code online}
     * (this client first) in the welcome. Call once {@code wantedRoom()} is the room the case needs.
     */
    public static FakeRelay install(List<String> online) throws Exception {
        String room = wantedRoom();
        if (room.isEmpty()) {
            throw new AssertionError("RelayClient wants no room yet - nothing to fake");
        }
        FakeRelay fake = new FakeRelay(room);
        ScheduledExecutorService worker = (ScheduledExecutorService) Mod.field(RC, "WORKER");
        worker.submit(() -> {
            // What connect() does once the socket is open, minus the login: a new generation (a connect or retry still
            // in flight from before is ignored when it lands), no retry or keep-alive pending, the socket, the room.
            Mod.setField(RC, "generation", ((Long) Mod.field(RC, "generation")) + 1L);
            Mod.staticCall(RC, "cancelRetry");
            Mod.staticCall(RC, "cancelKeepAlive");
            Mod.staticCall(RC, "closeSocket");
            Mod.setField(RC, "connectedUrl", Mod.field(RC, "wantUrl"));
            Mod.setField(RC, "connectedRoom", room);
            Mod.setField(RC, "socket", fake.new Socket());
            Mod.setField(RC, "activeRoom", room);
            Mod.setField(RC, "attempt", 0);
            Mod.setField(RC, "lastError", "");
            Mod.setField(RC, "state", Mod.enumValue(RC + "$State", "CONNECTED"));
            return null;
        }).get(10, TimeUnit.SECONDS);
        JsonObject welcome = new JsonObject();
        welcome.addProperty("type", "welcome");
        JsonArray names = new JsonArray();
        online.forEach(names::add);
        welcome.add("online", names);
        welcome.add("mods", new JsonObject());
        fake.handle(welcome);
        return fake;
    }

    /** The room this fake is. */
    public String room() {
        return room;
    }

    /** Every packet the mod sent into the room, in order (pings included). */
    public List<JsonObject> sent() {
        return List.copyOf(sent);
    }

    /** The {@code data} packets the mod sent with {@code key}: their {@code value}s, in order. */
    public List<JsonObject> sentData(String key) {
        List<JsonObject> out = new ArrayList<>();
        for (JsonObject p : sent) {
            if ("data".equals(str(p, "type")) && key.equals(str(p, "key")) && p.get("value") != null
                    && p.get("value").isJsonObject()) {
                out.add(p.getAsJsonObject("value"));
            }
        }
        return out;
    }

    /** From now on a second member named {@code name} sends back every data packet this client sends (null: stop). */
    public void mirrorAs(String name) {
        mirrorAs = name;
    }

    /** A data packet from {@code from} reaches the client, through the real socket's handler. */
    public void deliver(String from, String key, JsonElement value) {
        JsonObject p = new JsonObject();
        p.addProperty("type", "data");
        p.addProperty("from", from);
        p.addProperty("key", key);
        p.add("value", value);
        handle(p);
    }

    private void handle(JsonObject packet) {
        Mod.staticCall(RC, "handle", packet.toString());
    }

    private void onSent(String text) {
        JsonObject p;
        try {
            p = JsonParser.parseString(text).getAsJsonObject();
        } catch (RuntimeException e) {
            JsonObject bad = new JsonObject();
            bad.addProperty("type", "unparsed");
            bad.addProperty("text", text);
            sent.add(bad);
            return;
        }
        sent.add(p);
        String type = str(p, "type");
        if ("ping".equals(type)) {
            JsonObject pong = new JsonObject();
            pong.addProperty("type", "pong");
            wire.execute(() -> handle(pong));
        } else if ("data".equals(type) && mirrorAs != null) {
            String from = mirrorAs;
            String key = str(p, "key");
            JsonElement value = p.get("value");
            // Off the sending thread, as a packet from the network would be.
            wire.execute(() -> {
                if (!closed) {
                    deliver(from, key, value);
                }
            });
        }
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    @Override
    public void close() throws Exception {
        closed = true;
        mirrorAs = null;
        ScheduledExecutorService worker = (ScheduledExecutorService) Mod.field(RC, "WORKER");
        worker.submit(() -> {
            Mod.staticCall(RC, "shutdown", Mod.enumValue(RC + "$State", "OFF"), "");
            // Forget what was wanted, so ModChatFeature's next update() differs and reconciles the normal way.
            Mod.setField(RC, "wantOn", false);
            Mod.setField(RC, "wantUrl", "");
            Mod.setField(RC, "wantRoom", "");
            return null;
        }).get(10, TimeUnit.SECONDS);
        wire.shutdownNow();
    }

    /** The socket the mod writes to. Every send completes at once; nothing is ever received through it. */
    private final class Socket implements WebSocket {
        private volatile boolean aborted;

        @Override
        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            if (!aborted) {
                onSent(data.toString());
            }
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
            aborted = true;
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public void request(long n) {
        }

        @Override
        public String getSubprotocol() {
            return "";
        }

        @Override
        public boolean isOutputClosed() {
            return aborted;
        }

        @Override
        public boolean isInputClosed() {
            return aborted;
        }

        @Override
        public void abort() {
            aborted = true;
        }
    }
}
