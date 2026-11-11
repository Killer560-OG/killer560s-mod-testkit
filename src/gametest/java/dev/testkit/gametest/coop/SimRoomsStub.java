package dev.testkit.gametest.coop;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * The relay's {@code /simrooms/*} for co-op phase 2 tests (contract: the mod's docs/SIM-COOP.md "Room list", relay
 * src/simrooms.ts). In memory, one room per account; the bearer token IS the account's uuid (dashes optional), and
 * {@link #name} gives it a name. Point the mod at it with {@code killer560.net.simrooms-relay=http://127.0.0.1:PORT}.
 * Every request is recorded as "METHOD path body" ({@link #calls}).
 */
public final class SimRoomsStub {

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final class Room {
        String id, secret, ownerUuid, owner, name, password, mc, modVersion, mode;
        int max, protocol, players = 1;
        JsonElement endpoint;
        final Map<String, JsonObject> tickets = new HashMap<>();
        final List<JsonObject> undelivered = new ArrayList<>();
    }

    private final HttpServer server;
    private final Map<String, Room> byOwner = new HashMap<>();
    private final Map<String, String> names = new HashMap<>();
    private final List<String> calls = new ArrayList<>();
    /** When set, every join answers this endpoint (a test's loopback stand-in for the owner's public address). */
    public volatile JsonObject joinEndpoint;

    public SimRoomsStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/simrooms/", this::handle);
        server.start();
    }

    public int port() { return server.getAddress().getPort(); }

    public String base() { return "http://127.0.0.1:" + port(); }

    public void stop() { server.stop(0); }

    public synchronized void name(String uuid, String name) { names.put(norm(uuid), name); }

    public synchronized List<String> calls() { return new ArrayList<>(calls); }

    /** The calls whose "METHOD path" starts with {@code prefix}. */
    public synchronized List<String> calls(String prefix) {
        List<String> out = new ArrayList<>();
        for (String c : calls) if (c.startsWith(prefix)) out.add(c);
        return out;
    }

    public synchronized int rooms() { return byOwner.size(); }

    /** Queues a ticket for the room owned by {@code ownerUuid}, as a join would; returns its nonce. */
    public synchronized String issue(String ownerUuid, String guestUuid, String guestName) {
        Room r = byOwner.get(norm(ownerUuid));
        if (r == null) return null;
        return ticket(r, norm(guestUuid), guestName).get("nonce").getAsString();
    }

    /** Queues a ticket for the room with id {@code roomId}; returns its nonce. */
    public synchronized String issueById(String roomId, String guestUuid, String guestName) {
        for (Room r : byOwner.values()) if (r.id.equals(roomId)) return ticket(r, norm(guestUuid), guestName).get("nonce").getAsString();
        return null;
    }

    /** The id of the room named {@code name}, or null. */
    public synchronized String idByName(String name) {
        for (Room r : byOwner.values()) if (r.name.equals(name)) return r.id;
        return null;
    }

    /** Lists a room as another owner would (for the Join page); returns its id. */
    public synchronized String addRoom(String ownerUuid, String owner, String name, String password, int max, String mc,
                                       int protocol, JsonObject endpoint) {
        Room r = new Room();
        r.id = hex(12);
        r.secret = hex(16);
        r.ownerUuid = norm(ownerUuid);
        r.owner = owner;
        r.name = name;
        r.password = password;
        r.max = max;
        r.mc = mc;
        r.modVersion = "1.1.0";
        r.protocol = protocol;
        r.mode = "F7 Boss";
        r.endpoint = endpoint;
        byOwner.put(r.ownerUuid, r);
        return r.id;
    }

    private static String norm(String uuid) { return uuid == null ? "" : uuid.replace("-", "").toLowerCase(); }

    private static String hex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    private synchronized void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String query = ex.getRequestURI().getQuery();
        String raw = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        calls.add(ex.getRequestMethod() + " " + path + (query == null ? "" : "?" + query) + " " + raw);
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        String uuid = auth == null ? "" : norm(auth.replaceFirst("(?i)^Bearer\\s+", ""));
        JsonObject body;
        try {
            body = raw.isBlank() ? new JsonObject() : JsonParser.parseString(raw).getAsJsonObject();
        } catch (RuntimeException e) {
            reply(ex, 400, error("bad json"));
            return;
        }
        JsonObject out = new JsonObject();
        int status = 200;
        switch (path) {
            case "/simrooms/create" -> {
                Room r = new Room();
                r.id = hex(12);
                r.secret = hex(16);
                r.ownerUuid = uuid;
                r.owner = names.getOrDefault(uuid, "Owner");
                r.name = body.get("name").getAsString();
                r.password = body.has("password") ? body.get("password").getAsString() : null;
                r.max = body.get("max").getAsInt();
                r.mc = body.get("mc").getAsString();
                r.modVersion = body.get("modVersion").getAsString();
                r.protocol = body.get("protocol").getAsInt();
                r.mode = body.get("mode").getAsString();
                r.endpoint = body.get("endpoint");
                byOwner.put(uuid, r);
                out.addProperty("roomId", r.id);
                out.addProperty("hostSecret", r.secret);
            }
            case "/simrooms/refresh" -> {
                Room r = owned(body);
                if (r == null) {
                    status = 404;
                    out = error("gone");
                } else {
                    r.players = body.get("players").getAsInt();
                    r.mode = body.get("mode").getAsString();
                    if (body.has("endpoint")) r.endpoint = body.get("endpoint");
                    JsonArray t = new JsonArray();
                    r.undelivered.forEach(t::add);
                    r.undelivered.clear();
                    out.add("tickets", t);
                }
            }
            case "/simrooms/delete" -> {
                Room r = owned(body);
                if (r != null) byOwner.remove(r.ownerUuid);
            }
            case "/simrooms/list" -> {
                String mc = "";
                boolean all = false;
                for (String kv : query == null ? new String[0] : query.split("&")) {
                    if (kv.startsWith("mc=")) mc = java.net.URLDecoder.decode(kv.substring(3), StandardCharsets.UTF_8);
                    if (kv.equals("all=1")) all = true;
                }
                JsonArray rooms = new JsonArray();
                for (Room r : byOwner.values()) {
                    if (!all && !r.mc.equals(mc)) continue;
                    JsonObject o = new JsonObject();
                    o.addProperty("id", r.id);
                    o.addProperty("owner", r.owner);
                    o.addProperty("ownerUuid", r.ownerUuid);
                    o.addProperty("name", r.name);
                    o.addProperty("locked", r.password != null);
                    o.addProperty("players", r.players);
                    o.addProperty("max", r.max);
                    o.addProperty("modVersion", r.modVersion);
                    o.addProperty("protocol", r.protocol);
                    o.addProperty("mode", r.mode);
                    o.addProperty("mc", r.mc);
                    rooms.add(o);
                }
                out.add("rooms", rooms);
            }
            case "/simrooms/join" -> {
                Room r = null;
                for (Room x : byOwner.values()) if (x.id.equals(body.get("roomId").getAsString())) r = x;
                long now = System.currentTimeMillis();
                if (r == null) {
                    status = 404;
                    out = error("gone");
                } else if (r.ownerUuid.equals(uuid)) {
                    status = 400;
                    out = error("that is your own room");
                } else if (r.password != null && !(body.has("password") && r.password.equals(body.get("password").getAsString()))) {
                    status = 403;
                    out = error("password");
                } else {
                    r.tickets.values().removeIf(t -> t.get("expires").getAsLong() < now);
                    r.tickets.remove(uuid);
                    if (r.players + r.tickets.size() >= r.max) {
                        status = 409;
                        out = error("full");
                    } else {
                        JsonObject t = ticket(r, uuid, names.getOrDefault(uuid, "Guest"));
                        out.add("endpoint", joinEndpoint != null ? joinEndpoint : r.endpoint == null
                                ? com.google.gson.JsonNull.INSTANCE : r.endpoint);
                        out.addProperty("nonce", t.get("nonce").getAsString());
                        out.addProperty("expires", t.get("expires").getAsLong());
                        out.addProperty("punch", false);
                    }
                }
            }
            default -> {
                status = 404;
                out = error("no such path");
            }
        }
        reply(ex, status, out);
    }

    private JsonObject ticket(Room r, String uuid, String name) {
        JsonObject t = new JsonObject();
        t.addProperty("uuid", uuid);
        t.addProperty("name", name);
        t.addProperty("nonce", hex(16));
        t.addProperty("expires", System.currentTimeMillis() + 120_000L);
        r.tickets.put(uuid, t);
        r.undelivered.removeIf(o -> o.get("uuid").getAsString().equals(uuid));
        r.undelivered.add(t);
        return t;
    }

    private Room owned(JsonObject body) {
        for (Room r : byOwner.values()) {
            if (r.id.equals(body.get("roomId").getAsString()) && r.secret.equals(body.get("hostSecret").getAsString())) return r;
        }
        return null;
    }

    private static JsonObject error(String what) {
        JsonObject o = new JsonObject();
        o.addProperty("error", what);
        return o;
    }

    private static void reply(HttpExchange ex, int status, JsonObject body) throws IOException {
        byte[] b = body.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream o = ex.getResponseBody()) {
            o.write(b);
        }
    }
}
