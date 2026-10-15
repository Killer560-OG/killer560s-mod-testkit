package dev.testkit.gametest.menu;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import dev.testkit.gametest.Fixtures;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 411-menu-partyfinder-speed and 412-menu-partyfinder-background: how long a 15-player Party Finder menu takes to fill,
 * with both upstreams rate limited the way his own client logs show them (killer560 2026-10-15: "it is still taking 10+
 * minutes to find all the info on people in party finder ... I have been waiting for 4m and it only had 3 peoples info
 * filled in", and "have it continue searching up people even after I close the menu").
 *
 * <p>The fake (server port + 6, the listener 400/406/410 use) plays both upstreams at one third of real time, from the
 * real numbers in his 26.1.2 (Mod Only Test) logs of 2026-10-12..15:
 * <ul>
 *   <li>SkyBlockPV backend: 3 profile requests per 10 s window, then 429 with the window's remaining Retry-After (real:
 *       2-6 per window, then "Retry-After: 30" - 43 pauses, every one 30 s). The first request for 6 of the 15 players
 *       answers HTTP 500 (real: 53 of 126 direct answers were 500s, and the relay fetched the same players fine later).</li>
 *   <li>Relay {@code /pf/stats}: every name "pending" until its upstream gets to it, one player every 9 s in the order
 *       first asked (real: 7 players in 187 s), while promising {@code etaMs} 1.1 s per place in the queue (real: 3.3 s).
 *       Like the real relay's Durable Object queue, it keeps working whether or not anyone polls.</li>
 * </ul>
 * 411 opens the menu and times until all 15 players have stats (a failure does not count as filled). 412 opens it, closes
 * it after 5 s, waits 60 s on no screen, reopens, and requires every player filled within 1.5 s. Both report the
 * numbers and how many answers came from each path before they judge.
 */
final class PartyFinderSpeedCases {

    private PartyFinderSpeedCases() {
    }

    static final String API = PartyFinderRelayCases.API;
    static final String CFG = PartyFinderStyleCases.CFG;

    /** 15 fixture players with dungeon data (410's list without meowinging and the case-400 party). */
    static final List<String> NAMES = List.of(
            "nym_xoxo", "TheAdmin987", "SmhAuto", "EinfxchDavid", "kittycatlizzy",
            "LovePeaceUnity", "kwyt", "corpral", "LividLaughs", "manilike5",
            "Bubbleh", "JulienSpieltz", "SmallBaldingg", "Zwiebler3000", "dmif");
    /** Their first direct request answers HTTP 500. */
    static final Set<String> FIRST_500 = Set.of("theadmin987", "kittycatlizzy", "kwyt", "manilike5", "smallbaldingg", "dmif");

    static final long WINDOW_MS = 10_000;
    static final int PER_WINDOW = 3;
    static final long RELAY_PACE_MS = 9_000;
    static final long RELAY_ETA_STEP_MS = 1_100;

    static final class Fake implements AutoCloseable {
        final HttpServer server;
        final Map<String, JsonObject> golden = new LinkedHashMap<>();
        final Map<String, String> uuidToProfiles = new ConcurrentHashMap<>();
        final Map<String, String> nameToUuid = new ConcurrentHashMap<>();
        final Map<String, String> uuidToName = new ConcurrentHashMap<>();
        /** The relay's queue, first asked first, and what it has fetched. */
        final List<String> relayQueue = new CopyOnWriteArrayList<>();
        final Set<String> relayHas = ConcurrentHashMap.newKeySet();
        final ScheduledExecutorService relayPump = Executors.newSingleThreadScheduledExecutor();
        final Set<String> failedOnce = ConcurrentHashMap.newKeySet();
        final AtomicInteger relayRequests = new AtomicInteger();
        final AtomicInteger profileOk = new AtomicInteger();
        final AtomicInteger profile429 = new AtomicInteger();
        final AtomicInteger profile500 = new AtomicInteger();
        private long windowStart = 0;
        private int windowCount = 0;

        Fake(int port, Path dir) throws IOException {
            JsonObject g = JsonParser.parseString(Files.readString(dir.resolve("relay-golden.json"), StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : g.entrySet()) {
                JsonObject o = e.getValue().getAsJsonObject();
                golden.put(e.getKey(), o);
                String uuid = o.get("uuid").getAsString();
                nameToUuid.put(e.getKey(), uuid);
                uuidToName.put(uuid, e.getKey());
                Path f = dir.resolve(e.getKey() + ".json");
                if (!Files.exists(f)) {
                    f = dir.resolve("coverage").resolve(e.getKey() + ".json");
                }
                uuidToProfiles.put(uuid, Files.readString(f, StandardCharsets.UTF_8));
            }
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            server.setExecutor(Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "testkit-pfspeed-fake");
                t.setDaemon(true);
                return t;
            }));
            server.createContext("/", this::handle);
            server.start();
            relayPump.scheduleAtFixedRate(() -> {
                for (String n : relayQueue) {
                    if (relayHas.add(n)) {
                        return;
                    }
                }
            }, RELAY_PACE_MS, RELAY_PACE_MS, TimeUnit.MILLISECONDS);
        }

        private JsonObject relayAnswer(String name) {
            JsonObject r = new JsonObject();
            JsonObject g = golden.get(name);
            if (g == null || !"ok".equals(g.get("status").getAsString())) {
                r.addProperty("status", "missing");
                r.addProperty("reason", "no data");
                return r;
            }
            if (!relayHas.contains(name)) {
                if (!relayQueue.contains(name)) {
                    relayQueue.add(name);
                }
                int place = 0;
                for (String n : relayQueue) {
                    if (n.equals(name)) {
                        break;
                    }
                    if (!relayHas.contains(n)) {
                        place++;
                    }
                }
                r.addProperty("status", "pending");
                r.addProperty("etaMs", 800 + place * RELAY_ETA_STEP_MS);
                return r;
            }
            r.addProperty("status", "ok");
            r.addProperty("uuid", g.get("uuid").getAsString());
            r.addProperty("source", "SkyBlockPV backend");
            r.addProperty("fetchedAt", System.currentTimeMillis());
            r.add("stats", g.getAsJsonObject("stats").deepCopy());
            return r;
        }

        /** The backend's window: null to serve, else the Retry-After seconds. */
        private synchronized Long admit() {
            long now = System.currentTimeMillis();
            if (now - windowStart >= WINDOW_MS) {
                windowStart = now;
                windowCount = 0;
            }
            if (windowCount >= PER_WINDOW) {
                return Math.max(1, (windowStart + WINDOW_MS - now + 999) / 1000);
            }
            windowCount++;
            return null;
        }

        private void handle(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath();
            int status = 404;
            String body = "";
            try {
                if (path.equals("/pfrelay/pf/stats") && "POST".equals(ex.getRequestMethod())) {
                    relayRequests.incrementAndGet();
                    JsonObject req = JsonParser.parseString(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                    JsonObject results = new JsonObject();
                    for (JsonElement n : req.getAsJsonArray("names")) {
                        String name = n.getAsString().toLowerCase(Locale.ROOT);
                        results.add(name, relayAnswer(name));
                    }
                    JsonObject out = new JsonObject();
                    out.add("results", results);
                    out.add("uuids", new JsonObject());
                    Thread.sleep(120);
                    status = 200;
                    body = out.toString();
                } else if (path.equals("/pv/authenticate")) {
                    status = 200;
                    body = "testkit-guest-token";
                } else if (path.startsWith("/pv/profiles/")) {
                    String uuid = path.substring("/pv/profiles/".length()).replace("-", "").toLowerCase(Locale.ROOT);
                    Thread.sleep(250);
                    Long retry = admit();
                    if (retry != null) {
                        profile429.incrementAndGet();
                        ex.getResponseHeaders().set("Retry-After", String.valueOf(retry));
                        status = 429;
                    } else {
                        String name = uuidToName.get(uuid);
                        String json = uuidToProfiles.get(uuid);
                        if (json == null || (name != null && FIRST_500.contains(name) && failedOnce.add(name))) {
                            profile500.incrementAndGet();
                            status = 500;
                        } else {
                            profileOk.incrementAndGet();
                            status = 200;
                            body = json;
                        }
                    }
                } else if (path.startsWith("/mcs/minecraft/profile/lookup/name/") || path.startsWith("/mojang/users/profiles/minecraft/")) {
                    String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
                    String uuid = nameToUuid.get(name);
                    Thread.sleep(80);
                    if (uuid != null) {
                        status = 200;
                        body = "{\"id\":\"" + uuid + "\",\"name\":\"" + name + "\"}";
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", body.startsWith("{") ? "application/json" : "text/plain");
            ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(bytes);
                }
            }
            ex.close();
        }

        String counts() {
            return "relay requests " + relayRequests.get() + ", relay fetched " + relayHas.size() + "; backend ok "
                    + profileOk.get() + ", 500 " + profile500.get() + ", 429 " + profile429.get();
        }

        @Override
        public void close() {
            relayPump.shutdownNow();
            server.stop(0);
        }
    }

    static JsonObject menu() {
        JsonObject slots = new JsonObject();
        int slot = 10;
        for (int i = 0; i < NAMES.size(); i += 5) {
            List<String> lore = new ArrayList<>(List.of("§7Dungeon: §bThe Catacombs", "§7Floor: §bFloor VII", "", "§7Members:"));
            for (int j = i; j < Math.min(NAMES.size(), i + 5); j++) {
                lore.add(" §a" + NAMES.get(j) + "§f: §e" + PartyFinderRelayCases.CLASSES[j % 5] + " §b(§e40§b)");
            }
            lore.add("");
            lore.add("§eClick to join!");
            slots.add(String.valueOf(slot++), PartyFinderStyleCases.head("§b" + NAMES.get(i) + "'s Party", lore));
        }
        JsonObject spec = new JsonObject();
        spec.addProperty("title", "Party Finder");
        spec.addProperty("rows", 6);
        spec.addProperty("fill", true);
        spec.add("slots", slots);
        return spec;
    }

    // ---- the cases ----------------------------------------------------------------------------------------------

    /** 411: time until all 15 have stats, menu open throughout. */
    static void speed(Session c) throws Exception {
        String tag = "[411-menu-partyfinder-speed] ";
        long limitMs = 60_000;
        long timeoutMs = 200_000;
        try (Fake fake = setUp(c, tag);
             MenuKit.Cfg cfg = configure(c)) {
            int lookupsBefore = PartyFinderRelayCases.lookups();
            long start = System.currentTimeMillis();
            MenuKit.show(c, menu());
            MenuKit.awaitScreen(c, "Party Finder", 100);
            Map<Integer, Integer> progress = new LinkedHashMap<>();
            long took = -1;
            int next = 10;
            while (System.currentTimeMillis() - start < timeoutMs) {
                int have = filled(c);
                long t = System.currentTimeMillis() - start;
                if (t >= next * 1000L) {
                    progress.put(next, have);
                    next += 10;
                }
                if (have == NAMES.size()) {
                    took = t;
                    break;
                }
                c.ctx().waitTicks(2);
            }
            int direct = PartyFinderRelayCases.lookups() - lookupsBefore;
            System.out.println(tag + "all " + NAMES.size() + " filled " + (took < 0 ? "NEVER (" + timeoutMs / 1000 + " s)" : "after " + took + " ms")
                    + "; filled by second " + progress + "; direct lookups " + direct + "; " + fake.counts()
                    + "; still missing " + missing(c));
            c.note("15 players filled in " + (took < 0 ? "> " + timeoutMs / 1000 + " s" : took + " ms") + " (limit " + limitMs / 1000 + " s); " + fake.counts());
            MenuKit.closeClient(c);
            MenuKit.awaitNoScreen(c, 100);
            c.check(took >= 0 && took <= limitMs, "Party Finder: 15 players should fill within " + limitMs / 1000
                    + " s under the real rate limits (scaled 1/3), took " + (took < 0 ? "> " + timeoutMs / 1000 + " s" : took + " ms"));
        } finally {
            tearDown(c);
        }
    }

    /** 412: close after 5 s, 60 s on no screen, reopen: everyone filled. */
    static void background(Session c) throws Exception {
        String tag = "[412-menu-partyfinder-background] ";
        try (Fake fake = setUp(c, tag);
             MenuKit.Cfg cfg = configure(c)) {
            int lookupsBefore = PartyFinderRelayCases.lookups();
            MenuKit.show(c, menu());
            MenuKit.awaitScreen(c, "Party Finder", 100);
            c.ctx().waitTicks(100);
            int atClose = filled(c);
            MenuKit.closeClient(c);
            MenuKit.awaitNoScreen(c, 100);
            int lookupsAtClose = PartyFinderRelayCases.lookups() - lookupsBefore;
            c.ctx().waitTicks(1200);
            int beforeReopen = filled(c);
            int lookupsClosed = PartyFinderRelayCases.lookups() - lookupsBefore - lookupsAtClose;
            long reopen = System.currentTimeMillis();
            MenuKit.show(c, menu());
            MenuKit.awaitScreen(c, "Party Finder", 100);
            int have = 0;
            while (System.currentTimeMillis() - reopen < 1500) {
                have = filled(c);
                if (have == NAMES.size()) {
                    break;
                }
                c.ctx().waitTicks(1);
            }
            System.out.println(tag + "filled at close (5 s) " + atClose + ", after 60 s closed " + beforeReopen
                    + ", 1.5 s after reopening " + have + " of " + NAMES.size() + "; direct lookups while open " + lookupsAtClose
                    + ", while closed " + lookupsClosed + "; " + fake.counts() + "; still missing " + missing(c));
            c.note("background: " + atClose + " at close, " + beforeReopen + " after 60 s closed, " + have + " on reopen; " + fake.counts());
            MenuKit.closeClient(c);
            MenuKit.awaitNoScreen(c, 100);
            c.check(have == NAMES.size(), "Party Finder: lookups should keep running after the menu closes - "
                    + have + " of " + NAMES.size() + " filled on reopening after 60 s (" + beforeReopen + " before reopening)");
        } finally {
            tearDown(c);
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private static Fake setUp(Session c, String tag) throws IOException {
        MenuKit.reset(c);
        String portProp = System.getProperty("testkit.fakeHttp.port");
        String relayUrl = System.getProperty("killer560.net.pf-relay");
        System.out.println(tag + "fake port " + portProp + ", mod pf-relay -> " + relayUrl);
        c.check(portProp != null && relayUrl != null && relayUrl.contains("127.0.0.1:" + portProp),
                "build.gradle must point pf-relay at the fake (testkit.fakeHttp.port " + portProp + ", pf-relay " + relayUrl + ")");
        forgetAll(c);
        Path dir = Fixtures.root().getParent().resolve("testkit-http").resolve("partyfinder-stats");
        return new Fake(Integer.parseInt(portProp), dir);
    }

    private static MenuKit.Cfg configure(Session c) {
        MenuKit.Cfg cfg = new MenuKit.Cfg(c);
        cfg.set(CFG, "Enabled", true).set(CFG, "Tooltip", true).set(CFG, "Highlight", true)
                .set(CFG, "MemberCount", true).set(CFG, "ShowMissing", false).set(CFG, "RankNameColors", false)
                .set(CFG, "PbMode", Mod.enumValue(CFG + "$PbMode", "S_PLUS"))
                .set(CFG, "CompactMode", Mod.enumValue(CFG + "$CompactMode", "STYLE1"));
        return cfg;
    }

    private static void tearDown(Session c) {
        forgetAll(c);
        PartyFinderStyleCases.parkCursor(c);
        MenuKit.reset(c);
    }

    /** Forget the 15 everywhere: this API (both versions' fields), and the Profile Viewer's profile and name caches,
     *  which would otherwise answer a direct lookup from an earlier case without reaching the fake. */
    @SuppressWarnings("unchecked")
    static void forgetAll(Session c) {
        PartyFinderRelayCases.forget(c, NAMES);
        c.ctx().runOnClient(mc -> {
            for (String f : List.of("CACHE", "NAME_CACHE", "IN_FLIGHT")) {
                try {
                    ((Map<?, ?>) Mod.field("profileviewer.api.ProfileViewerApi", f)).clear();
                } catch (RuntimeException | AssertionError ignored) {
                    // not there in this jar
                }
            }
            try {
                Set<String> pending = (Set<String>) Mod.field(API, "PENDING");
                NAMES.forEach(n -> pending.remove(n.toLowerCase(Locale.ROOT)));
                ((java.util.Collection<String>) Mod.field(API, "QUEUE")).clear();
            } catch (RuntimeException | AssertionError ignored) {
                // the new jar has neither
            }
        });
    }

    static int filled(Session c) {
        return c.onClient(mc -> {
            int n = 0;
            for (String name : NAMES) {
                if (Mod.staticCall(API, "get", name) != null) {
                    n++;
                }
            }
            return n;
        });
    }

    static List<String> missing(Session c) {
        return c.onClient(mc -> {
            List<String> out = new ArrayList<>();
            for (String name : NAMES) {
                if (Mod.staticCall(API, "get", name) == null) {
                    Object why = Mod.staticCall(API, "failureReason", name);
                    out.add(name + (why == null ? "" : " (" + why + ")"));
                }
            }
            return out;
        });
    }
}
