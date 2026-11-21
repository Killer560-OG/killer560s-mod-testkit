package dev.testkit.gametest.coop;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The relay's {@code /simrooms/*} and {@code /simproxy} for co-op tests (contract: the mod's docs/SIM-COOP.md "Room list" and
 * "Private proxy", relay src/simrooms.ts and src/simproxy.ts; coopproxy-1120). In memory, one room per account; the bearer
 * token IS the account's uuid (dashes optional), and {@link #name} gives it a name. Point the mod at it with
 * {@code killer560.net.simrooms-relay=http://127.0.0.1:PORT}.
 *
 * <p>Like the real relay: create and refresh carry {@code proxyPort} + {@code probeKey}, the stub probes the host's gateway
 * ({@code K5PROBE1} -> {@code K5KEY <key>}) and answers {@code proxy: true|false}; only a probed room is listed; a join
 * answers a ticket (nonce, expires, proxy, share) and NEVER an address, except {@code direct: true} in a Share My IP room;
 * {@code net != 3} is 426. {@code /simproxy} is a WebSocket (headers x-sim-room, x-sim-key = the ticket) piped to the host's
 * gateway port on loopback: 426 old net, 503 once {@link #full}, 403 bad ticket, 502 host unreachable. The public port is a
 * raw front (the JDK HttpServer cannot upgrade) that handles /simproxy and splices every other request to the HttpServer.
 * Every HTTP request is recorded as "METHOD path body" ({@link #calls}).
 */
public final class SimRoomsStub {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private static final class Room {
        String id, secret, ownerUuid, owner, name, password, mc, modVersion, mode, probeKey;
        int max, protocol, players = 1, proxyPort;
        boolean probed, shareIp;
        final Map<String, JsonObject> tickets = new HashMap<>();
        final List<JsonObject> undelivered = new ArrayList<>();
    }

    private final HttpServer server;
    private final ServerSocket front;
    private final Map<String, Room> byOwner = new HashMap<>();
    private final Map<String, String> names = new HashMap<>();
    private final List<String> calls = new ArrayList<>();
    private final List<String> answers = new ArrayList<>();
    /** The co-op cap reached: /simproxy answers 503 like the relay's full month. */
    public volatile boolean full;
    private final AtomicInteger proxied = new AtomicInteger();
    private final AtomicLong bytesToHost = new AtomicLong(), bytesToGuest = new AtomicLong();

    public SimRoomsStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/simrooms/", this::handle);
        server.start();
        front = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        daemon(() -> {
            while (!front.isClosed()) {
                try {
                    Socket c = front.accept();
                    daemon(() -> serve(c));
                } catch (IOException e) {
                    break;
                }
            }
        });
    }

    public int port() { return front.getLocalPort(); }

    public String base() { return "http://127.0.0.1:" + port(); }

    public void stop() {
        server.stop(0);
        try {
            front.close();
        } catch (IOException ignored) {
            // closing
        }
    }

    /** Proxy connections opened since the start. */
    public int proxied() { return proxied.get(); }

    /** Bytes the proxy carried guest -> host gateway / host gateway -> guest (co-op traffic really flowed through it). */
    public long bytesToHost() { return bytesToHost.get(); }

    public long bytesToGuest() { return bytesToGuest.get(); }

    public synchronized void name(String uuid, String name) { names.put(norm(uuid), name); }

    public synchronized List<String> calls() { return new ArrayList<>(calls); }

    /** Every answer the stub gave to a /simrooms call, as "path status body" (a test checks none names an address). */
    public synchronized List<String> answers() { return new ArrayList<>(answers); }

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

    /** Lists a room as another owner would (for the Join page); returns its id. Its gateway is not reachable (502 on a proxy join). */
    public synchronized String addRoom(String ownerUuid, String owner, String name, String password, int max, String mc,
                                       int protocol) {
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
        r.probed = true;
        byOwner.put(r.ownerUuid, r);
        return r.id;
    }

    private static String norm(String uuid) { return uuid == null ? "" : uuid.replace("-", "").toLowerCase(Locale.ROOT); }

    private static String hex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    private static void daemon(Runnable r) {
        Thread t = new Thread(r, "tk-simroomsstub");
        t.setDaemon(true);
        t.start();
    }

    // ---- the raw front: /simproxy upgrade, everything else spliced to the HttpServer ----

    private void serve(Socket c) {
        try {
            c.setTcpNoDelay(true);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            for (int b, n = 0; (b = in.read()) >= 0; ) {
                head.write(b);
                n = b == '\n' && head.size() >= 4 && endsHead(head.toByteArray()) ? 1 : 0;
                if (n == 1) break;
                if (head.size() > 16_384) {
                    c.close();
                    return;
                }
            }
            String text = head.toString(StandardCharsets.ISO_8859_1);
            String line = text.substring(0, Math.max(0, text.indexOf("\r\n")));
            String[] p = line.split(" ");
            if (p.length >= 2 && p[1].startsWith("/simproxy")) {
                proxy(c, p[1], text);
                return;
            }
            Socket s = new Socket(InetAddress.getLoopbackAddress(), server.getAddress().getPort());
            s.getOutputStream().write(head.toByteArray());
            daemon(() -> pipe(s, c, null));
            pipe(c, s, null);
        } catch (IOException e) {
            close(c);
        }
    }

    private static boolean endsHead(byte[] b) {
        int n = b.length;
        return b[n - 1] == '\n' && b[n - 2] == '\r' && b[n - 3] == '\n' && b[n - 4] == '\r';
    }

    private static void pipe(Socket from, Socket to, AtomicLong count) {
        byte[] buf = new byte[8192];
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            for (int n; (n = in.read(buf)) >= 0; ) {
                out.write(buf, 0, n);
                out.flush();
                if (count != null) count.addAndGet(n);
            }
        } catch (IOException ignored) {
            // one side closed
        }
        close(from);
        close(to);
    }

    private static void close(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // closed
        }
    }

    private static void refuse(Socket c, int status, String text) throws IOException {
        byte[] body = ("{\"error\":\"" + text + "\"}").getBytes(StandardCharsets.UTF_8);
        OutputStream o = c.getOutputStream();
        o.write(("HTTP/1.1 " + status + " X\r\nContent-Type: application/json\r\nContent-Length: " + body.length
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        o.write(body);
        o.flush();
        close(c);
    }

    private static String header(String head, String name) {
        for (String l : head.split("\r\n")) {
            int i = l.indexOf(':');
            if (i > 0 && l.substring(0, i).trim().equalsIgnoreCase(name)) return l.substring(i + 1).trim();
        }
        return "";
    }

    /** The relay's /simproxy: authorize (ticket -> session key), connect to the host's gateway, pipe WebSocket frames <-> TCP. */
    private void proxy(Socket c, String target, String head) throws IOException {
        String query = target.contains("?") ? target.substring(target.indexOf('?') + 1) : "";
        if (!query.contains("net=3")) {
            refuse(c, 426, "update the mod to play co-op");
            return;
        }
        if (full) {
            refuse(c, 503, "co-op relay is full for this month");
            return;
        }
        String roomId = header(head, "x-sim-room"), key = header(head, "x-sim-key");
        int port;
        synchronized (this) {
            Room r = null;
            for (Room x : byOwner.values()) if (x.id.equals(roomId)) r = x;
            boolean ok = r != null && !key.isEmpty() && r.tickets.values().stream().anyMatch(t -> t.get("nonce").getAsString().equals(key));
            if (!ok) {
                refuse(c, 403, "bad ticket");
                return;
            }
            if (!r.probed || r.proxyPort <= 0) {
                refuse(c, 502, "host unreachable");
                return;
            }
            port = r.proxyPort;
        }
        Socket host = new Socket();
        try {
            host.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 3_000);
            host.setTcpNoDelay(true);
        } catch (IOException e) {
            close(host);
            refuse(c, 502, "host unreachable");
            return;
        }
        String accept;
        try {
            accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((header(head, "Sec-WebSocket-Key") + WS_GUID).getBytes(StandardCharsets.ISO_8859_1)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        OutputStream out = c.getOutputStream();
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "
                + accept + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        proxied.incrementAndGet();
        // host -> guest: one binary frame per read
        daemon(() -> {
            byte[] buf = new byte[16 * 1024];
            try {
                InputStream hin = host.getInputStream();
                for (int n; (n = hin.read(buf)) >= 0; ) {
                    synchronized (out) {
                        writeFrame(out, 2, buf, n);
                    }
                    bytesToGuest.addAndGet(n);
                }
                synchronized (out) {
                    writeFrame(out, 8, new byte[]{(byte) (4012 >> 8), (byte) 4012}, 2);
                }
            } catch (IOException ignored) {
                // closed
            }
            close(host);
            close(c);
        });
        // guest -> host: masked client frames
        try {
            InputStream in = c.getInputStream();
            OutputStream hout = host.getOutputStream();
            for (;;) {
                int b0 = in.read(), b1 = in.read();
                if (b1 < 0) break;
                int op = b0 & 0x0f;
                long len = b1 & 0x7f;
                if (len == 126) len = (in.read() << 8) | in.read();
                else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) len = (len << 8) | in.read();
                }
                byte[] mask = in.readNBytes(4);
                byte[] data = in.readNBytes((int) len);
                for (int i = 0; i < data.length; i++) data[i] ^= mask[i & 3];
                if (op == 8) break;
                if (op == 9) {
                    synchronized (out) {
                        writeFrame(out, 10, data, data.length);
                    }
                } else if (op == 2 || op == 0 || op == 1) {
                    hout.write(data);
                    hout.flush();
                    bytesToHost.addAndGet(data.length);
                }
            }
        } catch (IOException ignored) {
            // closed
        }
        close(host);
        close(c);
    }

    private static void writeFrame(OutputStream out, int op, byte[] b, int n) throws IOException {
        out.write(0x80 | op);
        if (n < 126) out.write(n);
        else {
            out.write(126);
            out.write(n >> 8);
            out.write(n);
        }
        out.write(b, 0, n);
        out.flush();
    }

    /** The relay's probe of the host's gateway: "K5PROBE1\n" must come back as "K5KEY <key>\n". */
    private static boolean probe(int port, String key) {
        if (port <= 0 || key == null) return false;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 3_000);
            s.setSoTimeout(3_000);
            s.getOutputStream().write("K5PROBE1\n".getBytes(StandardCharsets.US_ASCII));
            s.getOutputStream().flush();
            StringBuilder sb = new StringBuilder();
            for (int b; (b = s.getInputStream().read()) >= 0 && b != '\n' && sb.length() < 128; ) sb.append((char) b);
            return sb.toString().trim().equals("K5KEY " + key);
        } catch (IOException e) {
            return false;
        }
    }

    // ---- /simrooms/* ----

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
        boolean oldNet = (path.equals("/simrooms/create") || path.equals("/simrooms/join"))
                && !(body.has("net") && body.get("net").getAsInt() == 3);
        if (oldNet) {
            reply(ex, 426, error("update the mod to play co-op"));
            return;
        }
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
                r.shareIp = body.has("shareIp") && body.get("shareIp").getAsBoolean();
                gateway(r, body);
                byOwner.put(uuid, r);
                out.addProperty("roomId", r.id);
                out.addProperty("hostSecret", r.secret);
                out.addProperty("proxy", r.probed);
            }
            case "/simrooms/refresh" -> {
                Room r = owned(body);
                if (r == null) {
                    status = 404;
                    out = error("gone");
                } else {
                    r.players = body.get("players").getAsInt();
                    r.mode = body.get("mode").getAsString();
                    if (body.has("shareIp")) r.shareIp = body.get("shareIp").getAsBoolean();
                    gateway(r, body);
                    JsonArray t = new JsonArray();
                    r.undelivered.forEach(t::add);
                    r.undelivered.clear();
                    out.add("tickets", t);
                    out.addProperty("proxy", r.probed);
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
                    if (!r.probed || !all && !r.mc.equals(mc)) continue;
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
                    o.addProperty("path", "relay");
                    o.addProperty("share", r.shareIp);
                    rooms.add(o);
                }
                out.add("rooms", rooms);
            }
            case "/simrooms/join" -> {
                Room r = null;
                for (Room x : byOwner.values()) if (x.id.equals(body.get("roomId").getAsString())) r = x;
                long now = System.currentTimeMillis();
                boolean direct = body.has("direct") && body.get("direct").getAsBoolean();
                if (r == null) {
                    status = 404;
                    out = error("gone");
                } else if (r.ownerUuid.equals(uuid)) {
                    status = 400;
                    out = error("that is your own room");
                } else if (r.password != null && !(body.has("password") && r.password.equals(body.get("password").getAsString()))) {
                    status = 403;
                    out = error("password");
                } else if (direct && !r.shareIp) {
                    status = 403;
                    out = error("this room does not share its IP address");
                } else {
                    r.tickets.values().removeIf(t -> t.get("expires").getAsLong() < now);
                    r.tickets.remove(uuid);
                    if (r.players + r.tickets.size() >= r.max) {
                        status = 409;
                        out = error("full");
                    } else {
                        JsonObject t = ticket(r, uuid, names.getOrDefault(uuid, "Guest"));
                        out.addProperty("nonce", t.get("nonce").getAsString());
                        out.addProperty("expires", t.get("expires").getAsLong());
                        out.addProperty("proxy", true);
                        out.addProperty("share", r.shareIp);
                        if (direct) {
                            JsonObject d = new JsonObject();
                            d.addProperty("host", "127.0.0.1");
                            d.addProperty("port", r.proxyPort);
                            out.add("direct", d);
                        }
                    }
                }
            }
            default -> {
                status = 404;
                out = error("no such path");
            }
        }
        answers.add(path + " " + status + " " + out);
        reply(ex, status, out);
    }

    /** Create or refresh with a gateway: probe it now (a refresh without one keeps the last result). */
    private static void gateway(Room r, JsonObject body) {
        if (!body.has("proxyPort") || !body.has("probeKey")) return;
        r.proxyPort = body.get("proxyPort").getAsInt();
        r.probeKey = body.get("probeKey").getAsString();
        r.probed = probe(r.proxyPort, r.probeKey);
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
