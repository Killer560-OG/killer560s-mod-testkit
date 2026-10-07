package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import dev.testkit.gametest.Fixtures;
import dev.testkit.gametest.LogTap;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 406-menu-partyfinder-stats-coverage: a whole Party Finder menu of REAL players gets its stats, through the
 * SkyBlockPV backend's rate limit, with the PB shapes real profiles have.
 *
 * <p>killer560, 2026-10-07 10:10 (jar 799c5c2f): "it is now getting a few people's times in but the vast majority
 * still do not have any times in." Measured the same morning against the real backend with 25 dungeon players from
 * his own logs: 4 profile requests answered, then 19 x 429 "Retry-After: 10" inside two seconds; mod 799c5c2f recorded
 * every 429 as a failure and did not ask again for three minutes. The fixtures in
 * {@code testkit-http/partyfinder-stats/coverage/} are those players' real {@code /profiles/<uuid>} answers (fetched
 * 2026-10-07, trimmed to the dungeon fields), plus two shapes made from real answers: NoSelectedPlr (Xolty's profiles
 * with no profile marked selected) and SideProfilePlr (Hugo001's with "selected" moved to an empty profile).
 * meowinging is real and has no dungeon data on either profile the API returns. Expected numbers were computed from
 * the fixtures by a script independent of the mod (killer560s-mod-logs/pf-stats-2-data/make_fixtures.py).
 *
 * <p>The fake (server port + 6, the same wiring as 400) lets three profile requests through per 3-second window and
 * answers the rest 429 with the window's remaining seconds as Retry-After - the real backend's shape, scaled down - and
 * answers SmhAuto's first request 429 regardless. Checks, all reported before the case fails:
 * <ol>
 *   <li>every player has stats within 90 s (meowinging: a failure naming "no Catacombs data"), and each profile was
 *       answered 200 exactly once;</li>
 *   <li>the 429s happened (the fake counted them; the mod's own THROTTLED counter agrees) and the mod sent no profile
 *       request inside a Retry-After it had been given (300 ms grace for a request already in flight);</li>
 *   <li>four Floor VII parties in Style 1, Both: each member line exactly, including the no-selected-profile and
 *       empty-selected-profile shapes;</li>
 *   <li>an Entrance party (Hypixel records no S on Entrance: every real player's Entrance time is {@code fastest_time})
 *       and a Master Mode Floor III party with any-score-only times, in the None style, which labels the PB;</li>
 *   <li>one INFO line per player outcome ({@code [PartyFinder] stats for X} / {@code no stats for X}) and nothing
 *       per tick.</li>
 * </ol>
 */
final class PartyFinderCoverageCases {

    private PartyFinderCoverageCases() {
    }

    static final String CFG = PartyFinderStyleCases.CFG;
    static final String API = "partyfinder.PartyFinderStatsApi";
    static final String TAG = "[406-menu-partyfinder-stats-coverage] ";

    /** One player: expected Catacombs level (-1 = no stats), secrets short, average, and PB per floor ("S+ 3:34"). */
    record P(String name, String uuid, int cata, String secrets, String avg, String f7, String f0, String m3) {
    }

    static final List<P> PLAYERS = List.of(
            new P("nym_xoxo", "e467295f2b8940958b18dfff347ea285", 42, "35.2K", "12.7", "S+ 3:34", "Any 0:59", "Any 3:38"),
            new P("TheAdmin987", "42699e8135a1492d93a5d9fc55ce4a0f", 38, "29.0K", "42.8", "S+ 3:53", "Any 1:32", "S+ 3:17"),
            new P("SmhAuto", "d5ca5bb9ed6c4f0f91bc34214437752b", 50, "49.4K", "14.9", "S+ 3:29", "Any 1:40", "S+ 6:12"),
            new P("EinfxchDavid", "2c439658cc9c4fe1b3f450f610f6ef3b", 50, "141K", "9.1", "S+ 3:21", "Any 1:59", "S+ 1:56"),
            new P("kittycatlizzy", "dc0bddb23dde4fe9917e2d253fcd941d", 50, "75.5K", "12.7", "S+ 3:37", "Any 0:54", "S+ 2:06"),
            new P("LovePeaceUnity", "4ac1e9dab6c14aca9c7f5319cf54d1a1", 47, "51.0K", "11.9", "S+ 3:15", "Any 1:17", "Any 2:44"),
            new P("kwyt", "c3946f9e0fe843b08953064a6aeeac8c", 48, "30.8K", "6.3", "S+ 3:00", "Any 1:41", "S+ 5:19"),
            new P("corpral", "ff017bc7b9b54732bc4160838e8c8008", 50, "51.6K", "7.3", "S+ 3:43", "Any 0:52", "S+ 2:02"),
            new P("LividLaughs", "9c27dfa3624347598a6b0705944587a9", 50, "97.5K", "11.9", "S+ 3:46", "Any 2:39", "S 2:20"),
            new P("manilike5", "75c0a076d6d44509aac5c2255d0f0777", 44, "32.5K", "11.2", "S+ 3:18", "Any 1:10", "S+ 2:36"),
            new P("Bubbleh", "885a5460f2e0444dae2c255c7e539be0", 50, "47.4K", "13.0", "S+ 3:43", "S 7:09", "S+ 3:48"),
            new P("JulienSpieltz", "3eb6fd767cb84384b9c64bb81019f366", 50, "103K", "11.0", "S+ 3:21", "Any 1:24", "S+ 1:56"),
            new P("SmallBaldingg", "073f4dda72e2471088f54f1bb280a016", 49, "12.9K", "12.5", "S+ 3:40", "Any 3:17", "S+ 2:02"),
            new P("Zwiebler3000", "d2c8570a0571405787a478f079a4df8e", 50, "47.4K", "4.4", "S+ 3:19", "Any 1:10", "S+ 2:44"),
            new P("dmif", "56f54c25766c45f0b4371d70a04ba5ac", 50, "102K", "11.0", "S+ 3:21", "Any 1:04", "S+ 1:53"),
            new P("tvue", "24325a5e18084674ab4a1d41c43315e0", 50, "213K", "25.9", "S+ 3:05", "Any 1:04", "S+ 2:01"),
            new P("meowinging", "df90f38fcc6648829070ac46b232d4ba", -1, "?", "?", null, null, null),
            new P("NoSelectedPlr", "40000000000040000000000000000406", 48, "85.7K", "15.6", "S+ 3:09", "Any 1:10", "S+ 2:11"),
            new P("SideProfilePlr", "40000000000040000000000000000407", 50, "338K", "23.8", "S+ 3:09", "Any 0:56", "S+ 1:54"));

    static P player(String name) {
        for (P p : PLAYERS) {
            if (p.name().equals(name)) {
                return p;
            }
        }
        throw new IllegalArgumentException(name);
    }

    /** A member of a party head: name, role, class level. */
    record M(String name, String role, int level) {
        String letter() {
            return role.substring(0, 1);
        }
    }

    record Party(int slot, String dungeon, String floor, List<M> members) {
    }

    static final List<Party> PARTIES = List.of(
            new Party(10, "The Catacombs", "Floor VII", List.of(new M("nym_xoxo", "Healer", 40), new M("TheAdmin987", "Mage", 38),
                    new M("SmhAuto", "Berserk", 45), new M("EinfxchDavid", "Archer", 50), new M("kittycatlizzy", "Tank", 44))),
            new Party(11, "The Catacombs", "Floor VII", List.of(new M("LovePeaceUnity", "Healer", 41), new M("kwyt", "Mage", 42),
                    new M("corpral", "Berserk", 47), new M("LividLaughs", "Archer", 46), new M("manilike5", "Tank", 39))),
            new Party(12, "The Catacombs", "Floor VII", List.of(new M("Bubbleh", "Healer", 43), new M("JulienSpieltz", "Mage", 48),
                    new M("SmallBaldingg", "Berserk", 40), new M("Zwiebler3000", "Archer", 44), new M("dmif", "Tank", 46))),
            new Party(13, "The Catacombs", "Floor VII", List.of(new M("NoSelectedPlr", "Healer", 35), new M("SideProfilePlr", "Mage", 45),
                    new M("meowinging", "Berserk", 30), new M("tvue", "Archer", 50))),
            new Party(14, "The Catacombs", "Entrance", List.of(new M("kwyt", "Mage", 42), new M("tvue", "Archer", 50),
                    new M("corpral", "Berserk", 47), new M("Bubbleh", "Healer", 43))),
            new Party(15, "Master Mode The Catacombs", "Floor III", List.of(new M("nym_xoxo", "Healer", 40),
                    new M("LovePeaceUnity", "Mage", 41), new M("TheAdmin987", "Tank", 38))));

    // ---- the fake -----------------------------------------------------------------------------------------------

    static final int WINDOW_MS = 3000;
    static final int PER_WINDOW = 3;

    static final class Fake implements AutoCloseable {
        final HttpServer server;
        final Map<String, String> profiles = new ConcurrentHashMap<>();
        final Map<String, AtomicInteger> ok = new ConcurrentHashMap<>();
        final AtomicInteger throttled = new AtomicInteger();
        final AtomicInteger requests = new AtomicInteger();
        /** {received ms, retry-at ms or 0}, per profile request. */
        final List<long[]> log = new CopyOnWriteArrayList<>();
        final Set<String> forcedOnce = ConcurrentHashMap.newKeySet();
        long windowStart;
        int inWindow;

        Fake(int port, Path fixtures) throws IOException {
            for (P p : PLAYERS) {
                profiles.put(p.uuid(), Files.readString(fixtures.resolve(p.name().toLowerCase(Locale.ROOT) + ".json"), StandardCharsets.UTF_8));
            }
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        /** The real backend's limit, scaled: PER_WINDOW requests per fixed WINDOW_MS window, then 429. */
        private synchronized long admit(long now) {
            if (now - windowStart >= WINDOW_MS) {
                windowStart = now;
                inWindow = 0;
            }
            if (inWindow < PER_WINDOW) {
                inWindow++;
                return 0;
            }
            return windowStart + WINDOW_MS;
        }

        private void handle(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath();
            int status = 404;
            String body = "";
            String retryAfter = null;
            if (path.equals("/pv/authenticate")) {
                status = 200;
                body = "testkit-guest-token";
            } else if (path.startsWith("/pv/profiles/")) {
                long now = System.currentTimeMillis();
                requests.incrementAndGet();
                String uuid = path.substring("/pv/profiles/".length()).replace("-", "").toLowerCase(Locale.ROOT);
                long retryAt = admit(now);
                if (retryAt == 0 && uuid.equals(player("SmhAuto").uuid()) && forcedOnce.add(uuid)) {
                    retryAt = now + 1000;
                }
                if (!"testkit-guest-token".equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                    status = 401;
                } else if (retryAt > 0) {
                    status = 429;
                    body = "Rate Limited";
                    long secs = Math.max(1, (retryAt - now + 999) / 1000);
                    retryAfter = Long.toString(secs);
                    retryAt = now + secs * 1000;
                    throttled.incrementAndGet();
                } else if (profiles.containsKey(uuid)) {
                    status = 200;
                    body = profiles.get(uuid);
                    ok.computeIfAbsent(uuid, k -> new AtomicInteger()).incrementAndGet();
                } else {
                    status = 500;
                }
                log.add(new long[]{now, status == 429 ? retryAt : 0});
            } else if (path.startsWith("/mcs/minecraft/profile/lookup/name/") || path.startsWith("/mojang/users/profiles/minecraft/")) {
                String name = path.substring(path.lastIndexOf('/') + 1);
                for (P p : PLAYERS) {
                    if (p.name().equalsIgnoreCase(name)) {
                        status = 200;
                        body = "{\"id\":\"" + p.uuid() + "\",\"name\":\"" + p.name() + "\"}";
                    }
                }
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", status == 200 && body.startsWith("{") ? "application/json" : "text/plain");
            if (retryAfter != null) {
                ex.getResponseHeaders().set("Retry-After", retryAfter);
            }
            ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(bytes);
                }
            }
            ex.close();
        }

        /** Profile requests that arrived inside a Retry-After already given, more than 300 ms after it was given. */
        List<String> early() {
            List<String> out = new ArrayList<>();
            for (long[] given : log) {
                if (given[1] == 0) {
                    continue;
                }
                for (long[] r : log) {
                    if (r[0] > given[0] + 300 && r[0] < given[1]) {
                        out.add("request at +" + (r[0] - given[0]) + " ms of a 429 that said wait " + (given[1] - given[0]) + " ms");
                    }
                }
            }
            return out;
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    // ---- the menu -----------------------------------------------------------------------------------------------

    static JsonObject menu() {
        JsonObject slots = new JsonObject();
        for (Party party : PARTIES) {
            List<String> lore = new ArrayList<>(List.of("§7Dungeon: §b" + party.dungeon(), "§7Floor: §b" + party.floor(), "", "§7Members:"));
            for (M m : party.members()) {
                lore.add(" §a" + m.name() + "§f: §e" + m.role() + " §b(§e" + m.level() + "§b)");
            }
            lore.add("");
            lore.add("§eClick to join!");
            slots.add(String.valueOf(party.slot()), PartyFinderStyleCases.head("§b" + party.members().get(0).name() + "'s Party", lore));
        }
        JsonObject spec = new JsonObject();
        spec.addProperty("title", "Party Finder");
        spec.addProperty("rows", 6);
        spec.addProperty("fill", true);
        spec.add("slots", slots);
        return spec;
    }

    static String pbOf(P p, Party party) {
        return party.floor().equals("Entrance") ? p.f0() : party.dungeon().startsWith("Master") ? p.m3() : p.f7();
    }

    /** The Style 1 line for a member, exactly as the mod should draw it (colours stripped). */
    static String style1(M m, Party party) {
        P p = player(m.name());
        if (p.cata() < 0) {
            return "[" + m.letter() + "] " + m.name() + " [" + m.level() + " | ?] [? | ?] [?]";
        }
        String pb = pbOf(p, party);
        String time = pb == null ? "NO PB" : pb.substring(pb.indexOf(' ') + 1);
        return "[" + m.letter() + "] " + m.name() + " [" + m.level() + " | " + p.cata() + "] [" + p.secrets() + " | " + p.avg() + "] [" + time + "]";
    }

    // ---- the case -----------------------------------------------------------------------------------------------

    static void coverage(Session c) throws Exception {
        MenuKit.reset(c);
        LogTap.install();
        List<String> failures = new ArrayList<>();
        String portProp = System.getProperty("testkit.fakeHttp.port");
        String pvUrl = System.getProperty("killer560.net.pv-backend");
        c.check(portProp != null && pvUrl != null && pvUrl.contains("127.0.0.1:" + portProp),
                "build.gradle must point pv-backend at the fake (testkit.fakeHttp.port " + portProp + ", pv-backend " + pvUrl + ")");
        Path fixtures = Fixtures.root().getParent().resolve("testkit-http").resolve("partyfinder-stats").resolve("coverage");
        List<String> names = new ArrayList<>();
        PLAYERS.forEach(p -> names.add(p.name()));
        forget(c, names);
        int throttledBefore = Math.max(0, PartyFinderStatsCases.hookInt("THROTTLED"));
        long mark = LogTap.mark();
        try (Fake fake = new Fake(Integer.parseInt(portProp), fixtures);
             MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CFG, "Enabled", true).set(CFG, "Tooltip", true).set(CFG, "Highlight", true)
                    .set(CFG, "MemberCount", true).set(CFG, "ShowMissing", false).set(CFG, "RankNameColors", false)
                    .set(CFG, "PbMode", Mod.enumValue(CFG + "$PbMode", "BOTH"))
                    .set(CFG, "CompactMode", Mod.enumValue(CFG + "$CompactMode", "STYLE1"));
            MenuKit.show(c, menu());
            MenuKit.awaitScreen(c, "Party Finder", 100);

            // 1. Every player settles: stats, or (meowinging) a failure.
            long start = System.currentTimeMillis();
            Map<String, String> state = new LinkedHashMap<>();
            boolean settled = false;
            for (int i = 0; i < 180 && !settled; i++) {
                state = c.onClient(mc -> {
                    Map<String, String> s = new LinkedHashMap<>();
                    for (P p : PLAYERS) {
                        boolean stats = Mod.staticCall(API, "get", p.name()) != null;
                        boolean failed = (Boolean) Mod.staticCall(API, "hasFailed", p.name());
                        s.put(p.name(), stats ? "stats" : failed ? "failed: " + Mod.staticCall(API, "failureReason", p.name()) : "pending");
                    }
                    return s;
                });
                settled = true;
                for (P p : PLAYERS) {
                    String st = state.get(p.name());
                    if (p.cata() >= 0 ? !st.equals("stats") : !st.startsWith("failed")) {
                        settled = false;
                    }
                }
                if (!settled) {
                    c.ctx().waitTicks(10);
                }
            }
            long tookMs = System.currentTimeMillis() - start;
            int withStats = 0;
            for (String v : state.values()) {
                withStats += v.equals("stats") ? 1 : 0;
            }
            System.out.println(TAG + "settled " + settled + " after " + tookMs + " ms: " + withStats + " of " + PLAYERS.size()
                    + " players have stats (expected " + (PLAYERS.size() - 1) + "); 429s " + fake.throttled.get()
                    + " of " + fake.requests.get() + " profile requests");
            for (Map.Entry<String, String> e : state.entrySet()) {
                System.out.println(TAG + "  " + e.getKey() + ": " + e.getValue());
            }
            c.note("settled " + settled + " in " + tookMs + " ms, " + withStats + " with stats, 429s " + fake.throttled.get());
            if (!settled) {
                failures.add("not every player settled in 90 s: " + state);
            }
            String meow = state.get("meowinging");
            if (meow == null || !meow.contains("no Catacombs data")) {
                failures.add("meowinging (no dungeon data on any profile) reads '" + meow + "', wanted a failure naming 'no Catacombs data'");
            }
            for (P p : PLAYERS) {
                AtomicInteger n = fake.ok.get(p.uuid());
                int answered = n == null ? 0 : n.get();
                if (answered != 1) {
                    failures.add(p.name() + " answered " + answered + "x (want exactly once)");
                }
            }

            // 2. The 429s happened, were counted by the mod, and every Retry-After was honoured.
            int modThrottled = PartyFinderStatsCases.hookInt("THROTTLED");
            System.out.println(TAG + "429s: fake " + fake.throttled.get() + ", mod THROTTLED +" + (modThrottled < 0 ? "n/a (no hook)" : modThrottled - throttledBefore));
            if (fake.throttled.get() < 2) {
                failures.add("the fake answered only " + fake.throttled.get() + " 429s - the case did not exercise the rate limit");
            }
            if (modThrottled < 0 || modThrottled - throttledBefore < 1) {
                failures.add("the mod counted no 429 (THROTTLED " + modThrottled + ")");
            }
            List<String> early = fake.early();
            System.out.println(TAG + "requests inside a Retry-After: " + early.size() + " " + early);
            if (!early.isEmpty()) {
                failures.add(early.size() + " profile request(s) inside a Retry-After: " + early.subList(0, Math.min(3, early.size())));
            }

            // 3. Floor VII parties, Style 1, Both.
            for (Party party : PARTIES.subList(0, 4)) {
                List<String> want = new ArrayList<>();
                party.members().forEach(m -> want.add(style1(m, party)));
                PartyFinderStatsCases.compare(c, "slot " + party.slot() + " " + party.floor() + " Style 1",
                        members(c, party), want, failures);
            }
            List<String> tip13 = PartyFinderStatsCases.tooltipText(c, 13);
            PartyFinderStatsCases.footer(c, "slot 13 (meowinging)", tip13, "no stats for meowinging - SkyBlockPV backend: no Catacombs data", failures);

            // 4. Entrance and Master Mode Floor III in the None style, which labels the PB it shows.
            try (AutoCloseable m = c.onClient(mc -> Mod.with(CFG, "CompactMode", Mod.enumValue(CFG + "$CompactMode", "NONE")))) {
                for (Party party : PARTIES.subList(4, 6)) {
                    List<String> lines = members(c, party);
                    List<String> want = new ArrayList<>();
                    List<String> wrong = new ArrayList<>();
                    for (int i = 0; i < party.members().size(); i++) {
                        M member = party.members().get(i);
                        String pb = "[" + pbOf(player(member.name()), party) + "]";
                        want.add(member.name() + " ... " + pb);
                        String line = i < lines.size() ? lines.get(i) : "(missing)";
                        if (!line.contains(member.name()) || !line.endsWith(pb)) {
                            wrong.add("'" + line + "' should end with '" + pb + "'");
                        }
                    }
                    boolean ok = wrong.isEmpty();
                    System.out.println(TAG + "slot " + party.slot() + " " + party.dungeon() + " " + party.floor() + " None: " + (ok ? "PASS" : "FAIL"));
                    lines.forEach(l -> System.out.println(TAG + "  '" + l + "'"));
                    c.note("slot " + party.slot() + " None " + (ok ? "PASS" : "FAIL " + wrong));
                    if (!ok) {
                        failures.add("slot " + party.slot() + " " + party.floor() + ": " + wrong);
                    }
                }
            }

            // 5. One log line per outcome, nothing per tick.
            List<String> pf = new ArrayList<>();
            for (String line : LogTap.since(mark)) {
                if (line.contains("[PartyFinder]")) {
                    pf.add(line);
                }
            }
            List<String> logWrong = new ArrayList<>();
            for (P p : PLAYERS) {
                String want = p.cata() >= 0 ? "[PartyFinder] stats for " + p.name() + ":" : "[PartyFinder] no stats for " + p.name() + " ";
                long n = pf.stream().filter(l -> l.contains(want)).count();
                if (n != 1) {
                    logWrong.add(p.name() + " has " + n + " outcome line(s)");
                }
            }
            long pauses = pf.stream().filter(l -> l.contains("rate limited (429)")).count();
            int limit = PLAYERS.size() + fake.throttled.get();
            if (pf.size() > limit) {
                logWrong.add(pf.size() + " [PartyFinder] lines, more than one per player plus one per 429 (" + limit + ")");
            }
            if (pauses < 1) {
                logWrong.add("no rate-limit line");
            }
            System.out.println(TAG + "log: " + pf.size() + " [PartyFinder] line(s), " + pauses + " pause line(s): " + (logWrong.isEmpty() ? "PASS" : "FAIL " + logWrong));
            pf.stream().limit(30).forEach(l -> System.out.println(TAG + "  " + l));
            if (!logWrong.isEmpty()) {
                failures.add("log: " + logWrong);
            }
        } finally {
            forget(c, names);
            PartyFinderStyleCases.parkCursor(c);
            MenuKit.reset(c);
        }
        System.out.println(TAG + (failures.isEmpty() ? "PASS" : "FAIL " + failures));
        c.check(failures.isEmpty(), "Party Finder stats coverage: " + failures);
    }

    /** The tooltip lines of {@code party}'s members, in order. */
    static List<String> members(Session c, Party party) {
        List<String> out = new ArrayList<>();
        for (String s : PartyFinderStatsCases.tooltipText(c, party.slot())) {
            if (s.contains("'s Party") || s.startsWith("? = ")) {
                continue;
            }
            for (M m : party.members()) {
                if (s.contains(m.name())) {
                    out.add(s.trim());
                    break;
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static void forget(Session c, List<String> names) {
        c.ctx().runOnClient(mc -> {
            for (String f : List.of("CACHE", "FAILED", "FAILURE_REASON", "THROTTLES")) {
                try {
                    Map<String, ?> m = (Map<String, ?>) Mod.field(API, f);
                    names.forEach(n -> m.remove(n.toLowerCase(Locale.ROOT)));
                } catch (RuntimeException | AssertionError ignored) {
                    // An old jar has no such field.
                }
            }
        });
    }

    // ---- 407: the same menu shape against the REAL backend (opt-in) --------------------------------------------------

    /**
     * 407-menu-partyfinder-stats-live: a full 25-player menu against the real SkyBlockPV backend and Mojang, to measure
     * coverage before and after. Runs only with {@code TESTKIT_PF_LIVE=1} and {@code -PnetOnline}; it makes about 25
     * real profile requests. Prints how many players have stats and a PB after 30, 60, 120 and 180 s.
     */
    static final List<String> LIVE = List.of("AntsRNG", "celybispuppy", "Elysianz1", "nym_xoxo", "TheAdmin987", "SmhAuto",
            "EinfxchDavid", "kittycatlizzy", "LovePeaceUnity", "kwyt", "meowinging", "corpral", "LividLaughs", "manilike5",
            "Bubbleh", "JulienSpieltz", "SmallBaldingg", "Zwiebler3000", "grlemo", "Hugo001", "Noobalic", "dmif", "tvue",
            "Xolty", "Uqamed");

    static void live(Session c) throws Exception {
        MenuKit.reset(c);
        String pv = System.getProperty("killer560.net.pv-backend");
        if (pv != null && pv.contains("127.0.0.1")) {
            c.check(false, "407 needs -PnetOnline (pv-backend points at " + pv + ")");
            return;
        }
        String[] roles = {"Healer", "Mage", "Berserk", "Archer", "Tank"};
        JsonObject slots = new JsonObject();
        for (int i = 0; i < LIVE.size(); i += 5) {
            List<String> lore = new ArrayList<>(List.of("§7Dungeon: §bThe Catacombs", "§7Floor: §bFloor VII", "", "§7Members:"));
            for (int j = i; j < Math.min(LIVE.size(), i + 5); j++) {
                lore.add(" §a" + LIVE.get(j) + "§f: §e" + roles[j - i] + " §b(§e40§b)");
            }
            slots.add(String.valueOf(10 + i / 5), PartyFinderStyleCases.head("§b" + LIVE.get(i) + "'s Party", lore));
        }
        JsonObject spec = new JsonObject();
        spec.addProperty("title", "Party Finder");
        spec.addProperty("rows", 6);
        spec.addProperty("fill", true);
        spec.add("slots", slots);
        forget(c, LIVE);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CFG, "Enabled", true).set(CFG, "Tooltip", true)
                    .set(CFG, "PbMode", Mod.enumValue(CFG + "$PbMode", "BOTH"))
                    .set(CFG, "CompactMode", Mod.enumValue(CFG + "$CompactMode", "STYLE1"));
            MenuKit.show(c, spec);
            MenuKit.awaitScreen(c, "Party Finder", 100);
            long start = System.currentTimeMillis();
            for (int checkpoint : new int[]{30, 60, 120, 180}) {
                while (System.currentTimeMillis() - start < checkpoint * 1000L) {
                    c.ctx().waitTicks(20);
                }
                int stats = 0;
                int pb = 0;
                int failed = 0;
                Set<String> reasons = new LinkedHashSet<>();
                for (int s = 10; s < 10 + (LIVE.size() + 4) / 5; s++) {
                    for (String line : PartyFinderStatsCases.tooltipText(c, s)) {
                        if (line.startsWith("[") && !line.contains("'s Party")) {
                            boolean hasStats = !line.contains("| ?]");
                            stats += hasStats ? 1 : 0;
                            pb += hasStats && !line.endsWith("[NO PB]") && !line.endsWith("[?]") ? 1 : 0;
                        }
                    }
                }
                for (String n : LIVE) {
                    if (c.onClient(mc -> (Boolean) Mod.staticCall(API, "hasFailed", n))) {
                        failed++;
                        reasons.add(n + ": " + c.onClient(mc -> Mod.staticCall(API, "failureReason", n)));
                    }
                }
                System.out.println(String.format("[407-menu-partyfinder-stats-live] after %d s: %d of %d players with stats, %d with a PB, %d failed %s",
                        checkpoint, stats, LIVE.size(), pb, failed, reasons));
                c.note("after " + checkpoint + " s: stats " + stats + ", PB " + pb + ", failed " + failed);
            }
            for (int s = 10; s < 10 + (LIVE.size() + 4) / 5; s++) {
                for (String line : PartyFinderStatsCases.tooltipText(c, s)) {
                    System.out.println("[407-menu-partyfinder-stats-live]   " + line);
                }
            }
        } finally {
            forget(c, LIVE);
            PartyFinderStyleCases.parkCursor(c);
            MenuKit.reset(c);
        }
    }
}
