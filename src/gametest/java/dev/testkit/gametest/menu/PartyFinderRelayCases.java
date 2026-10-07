package dev.testkit.gametest.menu;

import com.google.gson.JsonArray;
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
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 410-menu-partyfinder-relay: the Party Finder Overlay's stats come from killer560's relay ({@code POST /pf/stats},
 * a cache shared by every user of the mod) in one batched request, and the direct SkyBlockPV path is only a fallback.
 *
 * <p>killer560, 2026-10-07: "do whatever you think is best to make this load as quickly as possible" - a full menu
 * took one to two minutes through the SkyBlockPV backend's 3-per-10-seconds limit. The fake here (server port + 6,
 * the same listener as 400/406; build.gradle points the mod's {@code pf-relay} service at {@code /pfrelay}) plays the
 * relay from {@code testkit-http/partyfinder-stats/relay-golden.json} - the RELAY'S OWN extraction of the 22 fixture
 * players, written by the relay repo's {@code scripts/pf-golden.mjs} - and the SkyBlockPV backend and Mojang from the
 * fixtures themselves, as 400/406 do. Checks, every one reported before the case fails:
 * <ol>
 *   <li>cached: a menu of all 22 players has every stat within 1 s of the screen opening, with no direct request,
 *       and the three players case 400 checks show 400's exact Style 1 lines;</li>
 *   <li>pending: players the relay answers "pending" are re-polled and fill in once the fake marks them ok;</li>
 *   <li>stale: a player answered "stale" shows at once and picks up a new PB when the relay's refresh lands;</li>
 *   <li>slow: a player the relay expects to take a minute is fetched directly as well, and shows;</li>
 *   <li>down: with the relay answering 503 every player is fetched directly - and the direct path's numbers equal the
 *       relay's for every player, field by field (level as a double), which is the check that the relay's port of the
 *       mod's stats rules ({@code src/pfextract.ts}) is identical to {@code PartyFinderStatsApi.toStats}.</li>
 * </ol>
 */
final class PartyFinderRelayCases {

    private PartyFinderRelayCases() {
    }

    static final String CFG = PartyFinderStyleCases.CFG;
    static final String API = "partyfinder.PartyFinderStatsApi";
    static final String TAG = "[410-menu-partyfinder-relay] ";

    /** Display names in menu order; the first three are case 400's slot-10 party. */
    static final List<String> NAMES = List.of(
            "AntsRNG", "celybispuppy", "Elysianz1",
            "nym_xoxo", "TheAdmin987", "SmhAuto", "EinfxchDavid", "kittycatlizzy",
            "LovePeaceUnity", "kwyt", "corpral", "LividLaughs", "manilike5",
            "Bubbleh", "JulienSpieltz", "SmallBaldingg", "Zwiebler3000", "dmif",
            "tvue", "meowinging", "NoSelectedPlr", "SideProfilePlr");
    static final List<String> PENDING_NAMES = List.of("AntsRNG", "celybispuppy", "Elysianz1");
    static final String STALE_NAME = "kwyt";
    static final String SLOW_NAME = "tvue";
    static final long NEW_PB_MS = 200_123;

    // ---- the fake -----------------------------------------------------------------------------------------------

    static final class Fake implements AutoCloseable {
        final HttpServer server;
        /** lower-case name -> the relay's golden answer ({status, uuid, stats | reason}). */
        final Map<String, JsonObject> golden = new LinkedHashMap<>();
        final Map<String, String> uuidToProfiles = new ConcurrentHashMap<>();
        final Map<String, String> nameToUuid = new ConcurrentHashMap<>();
        final AtomicInteger relayRequests = new AtomicInteger();
        final Map<String, AtomicInteger> relayAsked = new ConcurrentHashMap<>();
        final Map<String, AtomicInteger> profileFetches = new ConcurrentHashMap<>();
        volatile boolean down;
        /** lower-case names answered "pending", with the etaMs to give. */
        final Map<String, Long> pending = new ConcurrentHashMap<>();
        /** lower-case names answered ok + stale (old numbers); removing one makes it fresh with the new PB. */
        final Set<String> stale = ConcurrentHashMap.newKeySet();
        final Set<String> newPb = ConcurrentHashMap.newKeySet();

        Fake(int port, Path dir) throws IOException {
            JsonObject g = JsonParser.parseString(Files.readString(dir.resolve("relay-golden.json"), StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : g.entrySet()) {
                JsonObject o = e.getValue().getAsJsonObject();
                golden.put(e.getKey(), o);
                String uuid = o.get("uuid").getAsString();
                nameToUuid.put(e.getKey(), uuid);
                Path f = dir.resolve(e.getKey() + ".json");
                if (!Files.exists(f)) {
                    f = dir.resolve("coverage").resolve(e.getKey() + ".json");
                }
                uuidToProfiles.put(uuid, Files.readString(f, StandardCharsets.UTF_8));
            }
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        private JsonObject answer(String name) {
            JsonObject r = new JsonObject();
            JsonObject g = golden.get(name);
            if (g == null) {
                r.addProperty("status", "missing");
                r.addProperty("reason", "no Minecraft account with that name");
                return r;
            }
            Long eta = pending.get(name);
            if (eta != null) {
                r.addProperty("status", "pending");
                r.addProperty("etaMs", eta);
                return r;
            }
            if (!"ok".equals(g.get("status").getAsString())) {
                r.addProperty("status", "missing");
                r.add("reason", g.get("reason"));
                return r;
            }
            JsonObject stats = g.getAsJsonObject("stats").deepCopy();
            if (newPb.contains(name) && !stale.contains(name)) {
                stats.getAsJsonObject("pb").getAsJsonObject("normal").getAsJsonObject("floor_7").addProperty("splus", NEW_PB_MS);
            }
            r.addProperty("status", "ok");
            r.addProperty("uuid", g.get("uuid").getAsString());
            r.addProperty("source", "SkyBlockPV backend");
            r.addProperty("fetchedAt", System.currentTimeMillis());
            r.add("stats", stats);
            if (stale.contains(name)) {
                r.addProperty("stale", true);
                r.addProperty("etaMs", 1500);
            }
            return r;
        }

        private void handle(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath();
            int status = 404;
            String body = "";
            if (path.equals("/pfrelay/pf/stats") && "POST".equals(ex.getRequestMethod())) {
                relayRequests.incrementAndGet();
                if (down) {
                    status = 503;
                    body = "{\"error\":\"down\"}";
                } else {
                    JsonObject req = JsonParser.parseString(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                    JsonObject results = new JsonObject();
                    for (JsonElement n : req.getAsJsonArray("names")) {
                        String name = n.getAsString().toLowerCase(Locale.ROOT);
                        relayAsked.computeIfAbsent(name, k -> new AtomicInteger()).incrementAndGet();
                        results.add(name, answer(name));
                    }
                    JsonObject out = new JsonObject();
                    out.add("results", results);
                    out.add("uuids", new JsonObject());
                    status = 200;
                    body = out.toString();
                }
            } else if (path.equals("/pv/authenticate")) {
                status = 200;
                body = "testkit-guest-token";
            } else if (path.startsWith("/pv/profiles/")) {
                String uuid = path.substring("/pv/profiles/".length()).replace("-", "").toLowerCase(Locale.ROOT);
                profileFetches.computeIfAbsent(uuid, k -> new AtomicInteger()).incrementAndGet();
                String json = uuidToProfiles.get(uuid);
                status = json == null ? 500 : 200;
                body = json == null ? "" : json;
            } else if (path.startsWith("/mcs/minecraft/profile/lookup/name/") || path.startsWith("/mojang/users/profiles/minecraft/")) {
                String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
                String uuid = nameToUuid.get(name);
                if (uuid != null) {
                    status = 200;
                    body = "{\"id\":\"" + uuid + "\",\"name\":\"" + name + "\"}";
                }
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

        @Override
        public void close() {
            server.stop(0);
        }
    }

    // ---- the menu -----------------------------------------------------------------------------------------------

    static final String[] CLASSES = {"Berserk", "Healer", "Mage", "Archer", "Tank"};

    static JsonObject menu() {
        JsonObject slots = new JsonObject();
        // Case 400's slot-10 party, line for line, so its expected Style 1 lines apply.
        slots.add("10", PartyFinderStyleCases.head("§bAntsRNG's Party", List.of(
                "§7Dungeon: §bThe Catacombs", "§7Floor: §bFloor VII", "",
                "§7Members:",
                " §6AntsRNG§f: §eBerserk §b(§e41§b)",
                " §bcelybispuppy§f: §eHealer §b(§e41§b)",
                " §aElysianz1§f: §eMage §b(§e40§b)",
                "", "§eClick to join!")));
        int slot = 11;
        for (int i = 3; i < NAMES.size(); i += 5) {
            List<String> lore = new ArrayList<>(List.of("§7Dungeon: §bThe Catacombs", "§7Floor: §bFloor VII", "", "§7Members:"));
            for (int j = i; j < Math.min(NAMES.size(), i + 5); j++) {
                lore.add(" §a" + NAMES.get(j) + "§f: §e" + CLASSES[j % 5] + " §b(§e40§b)");
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

    // ---- the case -----------------------------------------------------------------------------------------------

    static void relay(Session c) throws Exception {
        MenuKit.reset(c);
        List<String> failures = new ArrayList<>();
        String portProp = System.getProperty("testkit.fakeHttp.port");
        String relayUrl = System.getProperty("killer560.net.pf-relay");
        System.out.println(TAG + "fake port " + portProp + ", mod pf-relay -> " + relayUrl);
        c.check(portProp != null && relayUrl != null && relayUrl.contains("127.0.0.1:" + portProp),
                "build.gradle must point pf-relay at the fake (testkit.fakeHttp.port " + portProp + ", pf-relay " + relayUrl + ")");
        try {
            Mod.field(API, "RELAY_REQUESTS");
        } catch (RuntimeException | AssertionError e) {
            c.check(false, "this jar has no relay path (PartyFinderStatsApi.RELAY_REQUESTS missing)");
        }
        Path dir = Fixtures.root().getParent().resolve("testkit-http").resolve("partyfinder-stats");
        forget(c, NAMES);
        try (Fake fake = new Fake(Integer.parseInt(portProp), dir);
             MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CFG, "Enabled", true).set(CFG, "Tooltip", true).set(CFG, "Highlight", true)
                    .set(CFG, "MemberCount", true).set(CFG, "ShowMissing", false).set(CFG, "RankNameColors", false)
                    .set(CFG, "PbMode", Mod.enumValue(CFG + "$PbMode", "S_PLUS"))
                    .set(CFG, "CompactMode", Mod.enumValue(CFG + "$CompactMode", "STYLE1"));

            // 1. Everyone cached at the relay. Direct lookups are counted by the mod (LOOKUPS_STARTED), not the fake:
            // the Profile Viewer keeps fetched profiles for 5 minutes, so an earlier case's fetch never reaches it.
            int lookupsBefore = lookups();
            int relayBefore = fake.relayRequests.get();
            MenuKit.show(c, menu());
            MenuKit.awaitScreen(c, "Party Finder", 100);
            long tookMs = settle(c, NAMES, 5000);
            int relayRequests = fake.relayRequests.get() - relayBefore;
            System.out.println(TAG + "cached: every player settled " + (tookMs < 0 ? "NEVER (5 s)" : "after " + tookMs + " ms")
                    + "; relay requests " + relayRequests + ", direct lookups " + (lookups() - lookupsBefore)
                    + ", relay asked " + new TreeMap<>(fake.relayAsked));
            c.note("cached menu settled in " + tookMs + " ms with " + relayRequests + " relay request(s)");
            if (tookMs < 0 || tookMs > 1000) {
                failures.add("cached menu: every stat should show within 1000 ms of opening, took " + (tookMs < 0 ? "> 5000" : tookMs) + " ms");
            }
            if (relayRequests != 1) {
                failures.add("cached menu: " + relayRequests + " relay requests, wanted ONE batch");
            }
            if (lookups() != lookupsBefore) {
                failures.add("cached menu: " + (lookups() - lookupsBefore) + " direct lookups, wanted none");
            }
            PartyFinderStatsCases.compare(c, "F7 Style 1 via relay", PartyFinderStatsCases.lines(c, 10, 3),
                    PartyFinderStatsCases.F7_STYLE1, failures);
            String meowRelayReason = c.onClient(mc -> (String) Mod.staticCall(API, "failureReason", "meowinging"));
            Map<String, String> viaRelay = snapshot(c);
            MenuKit.closeClient(c);
            MenuKit.awaitNoScreen(c, 100);

            // 2. Pending, then ok.
            forget(c, PENDING_NAMES);
            PENDING_NAMES.forEach(n -> fake.pending.put(n.toLowerCase(Locale.ROOT), 1500L));
            int askedBefore = asked(fake, PENDING_NAMES.get(0));
            MenuKit.show(c, menu());
            MenuKit.awaitScreen(c, "Party Finder", 100);
            c.ctx().waitTicks(60);
            int polls = asked(fake, PENDING_NAMES.get(0)) - askedBefore;
            boolean stillWaiting = c.onClient(mc -> PENDING_NAMES.stream().allMatch(n -> Mod.staticCall(API, "get", n) == null));
            PENDING_NAMES.forEach(n -> fake.pending.remove(n.toLowerCase(Locale.ROOT)));
            long fill = settle(c, PENDING_NAMES, 6000);
            System.out.println(TAG + "pending: polled " + polls + "x in 3 s, still waiting " + stillWaiting
                    + "; filled " + (fill < 0 ? "NEVER" : fill + " ms") + " after the fake marked them ok; direct lookups " + (lookups() - lookupsBefore));
            c.note("pending: " + polls + " polls in 3 s, filled " + fill + " ms after release");
            if (!stillWaiting || polls < 2 || polls > 4) {
                failures.add("pending: polled " + polls + "x in 3 s (wanted 2-4, every 1-2 s), still waiting " + stillWaiting);
            }
            if (fill < 0 || fill > 2500) {
                failures.add("pending: filled " + fill + " ms after the relay had them (wanted within 2.5 s)");
            }
            if (lookups() != lookupsBefore) {
                failures.add("pending: " + (lookups() - lookupsBefore) + " direct lookups for names the relay said were coming soon");
            }

            // 3. Stale: shown at once, new PB once the relay's refresh lands.
            forget(c, List.of(STALE_NAME));
            String staleKey = STALE_NAME.toLowerCase(Locale.ROOT);
            fake.stale.add(staleKey);
            fake.newPb.add(staleKey);
            long shown = settle(c, List.of(STALE_NAME), 3000);
            String oldPb = f7SPlus(c, STALE_NAME);
            c.ctx().waitTicks(40);
            fake.stale.remove(staleKey);
            String wantPb = String.format(Locale.ROOT, "%d:%02d", NEW_PB_MS / 1000 / 60, NEW_PB_MS / 1000 % 60);
            long refreshAt = System.currentTimeMillis();
            String newPb = null;
            for (int i = 0; i < 120 && !wantPb.equals(newPb); i++) {
                c.ctx().waitTicks(1);
                newPb = f7SPlus(c, STALE_NAME);
            }
            long pbMs = System.currentTimeMillis() - refreshAt;
            System.out.println(TAG + "stale: shown after " + shown + " ms with F7 S+ " + oldPb + "; new PB " + newPb + " " + pbMs + " ms after the refresh landed");
            c.note("stale: new PB " + newPb + " after " + pbMs + " ms");
            if (shown < 0 || oldPb == null || oldPb.equals(wantPb)) {
                failures.add("stale: shown " + shown + " ms, old PB " + oldPb);
            }
            if (!wantPb.equals(newPb) || pbMs > 5000) {
                failures.add("stale: F7 S+ " + newPb + " " + pbMs + " ms after the relay refreshed (wanted " + wantPb + " within 5 s)");
            }

            // 4. Slow at the relay: the direct path helps.
            forget(c, List.of(SLOW_NAME));
            fake.pending.put(SLOW_NAME.toLowerCase(Locale.ROOT), 60_000L);
            int slowBefore = lookups();
            long slow = settle(c, List.of(SLOW_NAME), 8000);
            int slowFetches = lookups() - slowBefore;
            fake.pending.clear();
            System.out.println(TAG + "slow: relay said 60 s; shown after " + slow + " ms with " + slowFetches + " direct lookup(s)");
            c.note("slow: shown after " + slow + " ms, " + slowFetches + " direct fetch(es)");
            if (slow < 0 || slowFetches != 1) {
                failures.add("slow: a name the relay expects in 60 s should be fetched directly too (shown " + slow + " ms, " + slowFetches + " fetch(es))");
            }
            MenuKit.closeClient(c);
            MenuKit.awaitNoScreen(c, 100);

            // 5. Relay down: everything direct, and the direct numbers equal the relay's.
            forget(c, NAMES);
            fake.down = true;
            int fetchesBefore = lookups();
            MenuKit.show(c, menu());
            MenuKit.awaitScreen(c, "Party Finder", 100);
            long direct = settle(c, NAMES, 60_000);
            int directFetches = lookups() - fetchesBefore;
            String relayError = (String) Mod.field(API, "lastRelayError");
            long downUntil = (Long) Mod.field(API, "relayDownUntil");
            System.out.println(TAG + "down: settled " + (direct < 0 ? "NEVER" : direct + " ms") + " through " + directFetches
                    + " direct lookups; relay error '" + relayError + "', relay retried in " + (downUntil - System.currentTimeMillis()) / 1000 + " s");
            c.note("down: direct path settled in " + direct + " ms");
            if (direct < 0 || directFetches != NAMES.size() || downUntil <= System.currentTimeMillis()) {
                failures.add("down: settled " + direct + " ms, " + directFetches + " direct lookups for " + NAMES.size() + " names, relay error " + relayError);
            }
            PartyFinderStatsCases.compare(c, "F7 Style 1 via direct path", PartyFinderStatsCases.lines(c, 10, 3),
                    PartyFinderStatsCases.F7_STYLE1, failures);
            Map<String, String> viaDirect = snapshot(c);
            String meowDirectReason = c.onClient(mc -> (String) Mod.staticCall(API, "failureReason", "meowinging"));
            List<String> diff = new ArrayList<>();
            for (String n : NAMES) {
                String a = viaRelay.get(n);
                String b = viaDirect.get(n);
                if (a == null || !a.equals(b)) {
                    diff.add(n + ": relay " + a + " / direct " + b);
                }
            }
            if (meowRelayReason == null || !meowRelayReason.equals(meowDirectReason)) {
                diff.add("meowinging reason: relay '" + meowRelayReason + "' / direct '" + meowDirectReason + "'");
            }
            System.out.println(TAG + "parity relay vs direct (" + (NAMES.size() - 1) + " players + 1 no-data): " + (diff.isEmpty() ? "PASS" : "FAIL"));
            diff.forEach(d -> System.out.println(TAG + "  DIFF " + d));
            c.note("parity: " + (diff.isEmpty() ? "identical for all " + NAMES.size() : diff.size() + " differ"));
            if (!diff.isEmpty()) {
                failures.add("relay and direct numbers differ: " + diff);
            }
        } finally {
            forget(c, NAMES);
            PartyFinderStyleCases.parkCursor(c);
            MenuKit.reset(c);
        }
        System.out.println(TAG + (failures.isEmpty() ? "PASS" : "FAIL " + failures));
        c.check(failures.isEmpty(), "Party Finder relay: " + failures);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    static int lookups() {
        return PartyFinderStatsCases.hookInt("LOOKUPS_STARTED");
    }

    static int asked(Fake fake, String name) {
        AtomicInteger n = fake.relayAsked.get(name.toLowerCase(Locale.ROOT));
        return n == null ? 0 : n.get();
    }

    /** Ms until every name has stats or a failure, polled every tick; -1 past {@code timeoutMs}. */
    static long settle(Session c, List<String> names, long timeoutMs) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            boolean all = c.onClient(mc -> {
                for (String n : names) {
                    if (Mod.staticCall(API, "get", n) == null && !(Boolean) Mod.staticCall(API, "hasFailed", n)) {
                        return false;
                    }
                }
                return true;
            });
            if (all) {
                return System.currentTimeMillis() - start;
            }
            c.ctx().waitTicks(1);
        }
        return -1;
    }

    static String f7SPlus(Session c, String name) {
        return c.onClient(mc -> {
            Object s = Mod.staticCall(API, "get", name);
            if (s == null) {
                return null;
            }
            JsonObject pb = (JsonObject) Mod.call(s, "pbNormal");
            JsonElement e = pb == null ? null : pb.getAsJsonObject("s_plus").get("floor_7");
            return e == null ? null : e.getAsString();
        });
    }

    /** Every name's stats as one comparable string (level exact), or "failed: reason". */
    static Map<String, String> snapshot(Session c) {
        return c.onClient(mc -> {
            Map<String, String> out = new LinkedHashMap<>();
            for (String n : NAMES) {
                Object s = Mod.staticCall(API, "get", n);
                if (s == null) {
                    out.put(n, "failed: " + Mod.staticCall(API, "failureReason", n));
                    continue;
                }
                out.put(n, "level " + Double.toString((Double) Mod.call(s, "level"))
                        + ", secrets " + Mod.call(s, "secrets")
                        + ", avg " + Double.toString((Double) Mod.call(s, "averageSecrets"))
                        + ", normal " + sorted((JsonObject) Mod.call(s, "pbNormal"))
                        + ", master " + sorted((JsonObject) Mod.call(s, "pbMaster")));
            }
            return out;
        });
    }

    /** A JsonObject printed with its keys sorted at every level, so property order cannot make two equal maps differ. */
    static String sorted(JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return "null";
        }
        if (e.isJsonObject()) {
            TreeMap<String, String> m = new TreeMap<>();
            e.getAsJsonObject().entrySet().forEach(en -> m.put(en.getKey(), sorted(en.getValue())));
            return m.toString();
        }
        if (e.isJsonArray()) {
            List<String> l = new ArrayList<>();
            for (JsonElement x : (JsonArray) e) {
                l.add(sorted(x));
            }
            return l.toString();
        }
        return e.toString();
    }

    /** Drop whatever the mod knows about these names (both paths), and clear an earlier case's relay outage. */
    @SuppressWarnings("unchecked")
    static void forget(Session c, List<String> names) {
        c.ctx().runOnClient(mc -> {
            for (String f : List.of("CACHE", "FAILED", "FAILURE_REASON", "EXPIRES", "WANTS")) {
                try {
                    Map<String, ?> m = (Map<String, ?>) Mod.field(API, f);
                    names.forEach(n -> m.remove(n.toLowerCase(Locale.ROOT)));
                } catch (RuntimeException | AssertionError ignored) {
                    // An old jar has no such field.
                }
            }
            try {
                Mod.setField(API, "relayDownUntil", 0L);
            } catch (RuntimeException | AssertionError ignored) {
                // An old jar.
            }
        });
    }
}
