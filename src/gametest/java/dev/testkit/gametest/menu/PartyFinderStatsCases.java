package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import dev.testkit.gametest.Fixtures;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 400-menu-partyfinder-stats: the Party Finder Overlay shows REAL player stats - Catacombs level, secrets, average
 * secrets and the party floor's PB - from the SkyBlockPV backend, served here by a loopback fake.
 *
 * <p>killer560, 2026-10-07: every stat in his Floor VII menu read {@code ?} ("celybispuppy [41 | ?] [? | ?] [?]"),
 * because api.docilelm.top/v2/dungeons answers {@code {"result":{}}} for everyone. The mod now asks the Profile
 * Viewer's backend. The fixtures in {@code testkit-http/partyfinder-stats/} are that backend's real
 * {@code /profiles/<uuid>} answers for AntsRNG, celybispuppy and Elysianz1 (fetched 2026-10-07 with a guest token,
 * trimmed to the dungeon fields the mod reads); the expected lines below were computed from them by hand-checked
 * script (Catacombs XP table checked against hypixelskyblock.minecraft.wiki's Dungeoneering page).
 *
 * <p>The fake answers {@code /pv/authenticate} with a token, {@code /pv/profiles/<uuid>} with a fixture (a 500 for
 * BrokenBackend, as the real backend gives for a player it cannot fetch), and the two Mojang name lookups under
 * {@code /mcs} and {@code /mojang} (a 404 for NoSuchPlayerX). build.gradle points the mod's {@code pv-backend},
 * {@code minecraftservices} and {@code mojang} services at it (server port + 6). Checks, every one reported before
 * the case fails:
 * <ol>
 *   <li>a normal Floor VII party in Style 1 and Style 2: each member line, exactly, with the fixture's numbers and
 *       F7 S+ PB;</li>
 *   <li>a Master Mode Floor VII party: the M7 S+ PB, and BrokenBackend shown as {@code ?} with a footer naming the
 *       SkyBlockPV backend;</li>
 *   <li>a party with a name Mojang does not know: {@code ?} and a footer naming the name lookup;</li>
 *   <li>dedupe and cache: with the menu open for several more seconds (the overlay asks again every second) and then
 *       closed and opened again, each name was looked up once and each profile fetched once;</li>
 *   <li>no lookup was started on the render thread (the mod's own record of the threads that started one).</li>
 * </ol>
 */
final class PartyFinderStatsCases {

    private PartyFinderStatsCases() {
    }

    static final String CFG = PartyFinderStyleCases.CFG;
    static final String API = "partyfinder.PartyFinderStatsApi";
    static final String TAG = "[400-menu-partyfinder-stats] ";

    record Player(String name, String uuid, String fixture) {
    }

    static final List<Player> REAL = List.of(
            new Player("AntsRNG", "014fd28253f5474f9d79110e408e5a7c", "antsrng.json"),
            new Player("celybispuppy", "057bfad588de414b871e7e9bf36aa6ef", "celybispuppy.json"),
            new Player("Elysianz1", "2900631614ae42e5a82e7b2eff5a35d4", "elysianz1.json"));
    static final Player BROKEN = new Player("BrokenBackend", "40000000000040000000000000000400", null);
    static final String UNKNOWN = "NoSuchPlayerX";
    static final List<String> ALL_NAMES = List.of("AntsRNG", "celybispuppy", "Elysianz1", "BrokenBackend", UNKNOWN);

    /** Style 1 / Style 2 lines of the normal Floor VII party (slot 10), PB mode S+. */
    static final List<String> F7_STYLE1 = List.of(
            "[B] AntsRNG [41 | 40] [7.50K | 7.5] [3:57]",
            "[H] celybispuppy [41 | 43] [19.6K | 11.5] [3:38]",
            "[M] Elysianz1 [40 | 43] [27.6K | 12.7] [3:35]");
    static final List<String> F7_STYLE2 = List.of(
            "[B 41] AntsRNG [40 | 7.50K | 7.5] 3:57",
            "[H 41] celybispuppy [43 | 19.6K | 11.5] 3:38",
            "[M 40] Elysianz1 [43 | 27.6K | 12.7] 3:35");
    /** Style 1 of the Master Mode Floor VII party (slot 11). */
    static final List<String> M7_STYLE1 = List.of(
            "[B] AntsRNG [41 | 40] [7.50K | 7.5] [6:47]",
            "[H] celybispuppy [41 | 43] [19.6K | 11.5] [5:39]",
            "[M] Elysianz1 [40 | 43] [27.6K | 12.7] [6:01]",
            "[T] BrokenBackend [38 | ?] [? | ?] [?]");

    // ---- the fake -----------------------------------------------------------------------------------------------

    static final class Fake implements AutoCloseable {
        final HttpServer server;
        final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
        final Map<String, String> profiles = new ConcurrentHashMap<>();

        Fake(int port, Path fixtures) throws IOException {
            for (Player p : REAL) {
                profiles.put(p.uuid(), Files.readString(fixtures.resolve(p.fixture()), StandardCharsets.UTF_8));
            }
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        int count(String path) {
            AtomicInteger n = hits.get(path);
            return n == null ? 0 : n.get();
        }

        private void handle(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath();
            hits.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();
            int status = 404;
            String body = "";
            if (path.equals("/pv/authenticate")) {
                status = 200;
                body = "testkit-guest-token";
            } else if (path.startsWith("/pv/profiles/")) {
                String uuid = path.substring("/pv/profiles/".length()).replace("-", "").toLowerCase(Locale.ROOT);
                String auth = ex.getRequestHeaders().getFirst("Authorization");
                if (!"testkit-guest-token".equals(auth)) {
                    status = 401;
                } else if (profiles.containsKey(uuid)) {
                    status = 200;
                    body = profiles.get(uuid);
                } else {
                    status = 500;
                }
            } else if (path.startsWith("/mcs/minecraft/profile/lookup/name/") || path.startsWith("/mojang/users/profiles/minecraft/")) {
                String name = path.substring(path.lastIndexOf('/') + 1);
                for (Player p : REAL) {
                    if (p.name().equalsIgnoreCase(name)) {
                        status = 200;
                        body = "{\"id\":\"" + p.uuid() + "\",\"name\":\"" + p.name() + "\"}";
                    }
                }
                if (BROKEN.name().equalsIgnoreCase(name)) {
                    status = 200;
                    body = "{\"id\":\"" + BROKEN.uuid() + "\",\"name\":\"" + BROKEN.name() + "\"}";
                }
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", status == 200 && !body.startsWith("testkit") ? "application/json" : "text/plain");
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

    static JsonObject menu() {
        JsonObject slots = new JsonObject();
        slots.add("10", PartyFinderStyleCases.head("§bAntsRNG's Party", List.of(
                "§7Dungeon: §bThe Catacombs", "§7Floor: §bFloor VII", "",
                "§7Members:",
                " §6AntsRNG§f: §eBerserk §b(§e41§b)",
                " §bcelybispuppy§f: §eHealer §b(§e41§b)",
                " §aElysianz1§f: §eMage §b(§e40§b)",
                "", "§eClick to join!")));
        slots.add("11", PartyFinderStyleCases.head("§bElysianz1's Party", List.of(
                "§7Dungeon: §bMaster Mode The Catacombs", "§7Floor: §bFloor VII", "",
                "§7Members:",
                " §6AntsRNG§f: §eBerserk §b(§e41§b)",
                " §bcelybispuppy§f: §eHealer §b(§e41§b)",
                " §aElysianz1§f: §eMage §b(§e40§b)",
                " §7BrokenBackend§f: §eTank §b(§e38§b)",
                "", "§eClick to join!")));
        slots.add("12", PartyFinderStyleCases.head("§aNoSuchPlayerX's Party", List.of(
                "§7Dungeon: §bThe Catacombs", "§7Floor: §bFloor V", "",
                " §aNoSuchPlayerX§f: §eArcher §b(§e30§b)",
                "", "§eClick to join!")));
        JsonObject spec = new JsonObject();
        spec.addProperty("title", "Party Finder");
        spec.addProperty("rows", 6);
        spec.addProperty("fill", true);
        spec.add("slots", slots);
        return spec;
    }

    // ---- the case -----------------------------------------------------------------------------------------------

    static void stats(Session c) throws Exception {
        MenuKit.reset(c);
        List<String> failures = new ArrayList<>();
        String portProp = System.getProperty("testkit.fakeHttp.port");
        String pvUrl = System.getProperty("killer560.net.pv-backend");
        System.out.println(TAG + "fake port " + portProp + ", mod pv-backend -> " + pvUrl);
        c.check(portProp != null && pvUrl != null && pvUrl.contains("127.0.0.1:" + portProp),
                "build.gradle must point pv-backend at the fake (testkit.fakeHttp.port " + portProp + ", pv-backend " + pvUrl + ")");
        Path fixtures = Fixtures.root().getParent().resolve("testkit-http").resolve("partyfinder-stats");
        forget(c);
        // The counter covers the whole session (236 before this case starts lookups of its own): measure the delta.
        int startedBefore = Math.max(0, hookInt("LOOKUPS_STARTED"));
        try (Fake fake = new Fake(Integer.parseInt(portProp), fixtures);
             MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CFG, "Enabled", true).set(CFG, "Tooltip", true).set(CFG, "Highlight", true)
                    .set(CFG, "MemberCount", true).set(CFG, "ShowMissing", false).set(CFG, "RankNameColors", false)
                    .set(CFG, "PbMode", Mod.enumValue(CFG + "$PbMode", "S_PLUS"))
                    .set(CFG, "CompactMode", Mod.enumValue(CFG + "$CompactMode", "STYLE1"));
            MenuKit.show(c, menu());
            MenuKit.awaitScreen(c, "Party Finder", 100);
            String renderThread = c.onClient(mc -> Thread.currentThread().getName());

            // Wait for every name to settle: stats for the three real players, a failure for the other two.
            long start = System.currentTimeMillis();
            boolean settled = false;
            for (int i = 0; i < 60 && !settled; i++) {
                settled = c.onClient(mc -> {
                    for (Player p : REAL) {
                        if (Mod.staticCall(API, "get", p.name()) == null) {
                            return false;
                        }
                    }
                    return (Boolean) Mod.staticCall(API, "hasFailed", BROKEN.name())
                            && (Boolean) Mod.staticCall(API, "hasFailed", UNKNOWN);
                });
                if (!settled) {
                    c.ctx().waitTicks(10);
                }
            }
            long tookMs = System.currentTimeMillis() - start;
            System.out.println(TAG + "stats settled: " + settled + " after " + tookMs + " ms; fake hits " + new TreeMap<>(fake.hits));
            c.note("stats settled " + settled + " in " + tookMs + " ms");
            if (!settled) {
                failures.add("stats never arrived for every player within 30 s (fake hits " + new TreeMap<>(fake.hits) + ")");
            }

            // 1. Normal Floor VII, Style 1 and Style 2.
            compare(c, "F7 Style 1", lines(c, 10, REAL.size()), F7_STYLE1, failures);
            try (AutoCloseable m = c.onClient(mc -> Mod.with(CFG, "CompactMode", Mod.enumValue(CFG + "$CompactMode", "STYLE2")))) {
                compare(c, "F7 Style 2", lines(c, 10, REAL.size()), F7_STYLE2, failures);
            }
            // 2. Master Mode Floor VII, with BrokenBackend failing at the backend.
            List<String> mm = tooltipText(c, 11);
            compare(c, "M7 Style 1", memberText(mm, 4), M7_STYLE1, failures);
            footer(c, "M7 (BrokenBackend)", mm, "no stats for brokenbackend - SkyBlockPV backend", failures);
            // 3. A name Mojang does not know.
            List<String> unknown = tooltipText(c, 12);
            compare(c, "F5 unknown name", memberText(unknown, 1), List.of("[A] NoSuchPlayerX [30 | ?] [? | ?] [?]"), failures);
            footer(c, "F5 (NoSuchPlayerX)", unknown, "no stats for nosuchplayerx - Mojang name lookup", failures);

            // 4. Dedupe and cache: keep the menu open (the overlay re-asks every 20 ticks), close it, open it again.
            c.ctx().waitTicks(100);
            MenuKit.closeClient(c);
            MenuKit.awaitNoScreen(c, 100);
            MenuKit.show(c, menu());
            MenuKit.awaitScreen(c, "Party Finder", 100);
            c.ctx().waitTicks(100);
            List<String> requestFailures = new ArrayList<>();
            for (Player p : REAL) {
                int lookups = fake.count("/mcs/minecraft/profile/lookup/name/" + p.name().toLowerCase(Locale.ROOT))
                        + fake.count("/mojang/users/profiles/minecraft/" + p.name().toLowerCase(Locale.ROOT));
                int fetches = profileFetches(fake, p.uuid());
                if (lookups != 1 || fetches != 1) {
                    requestFailures.add(p.name() + " looked up " + lookups + "x, fetched " + fetches + "x");
                }
            }
            int brokenFetches = profileFetches(fake, BROKEN.uuid());
            if (brokenFetches != 1) {
                requestFailures.add("BrokenBackend fetched " + brokenFetches + "x (a failure must not be retried within 3 min)");
            }
            int unknownLookups = fake.count("/mcs/minecraft/profile/lookup/name/" + UNKNOWN.toLowerCase(Locale.ROOT));
            if (unknownLookups != 1) {
                requestFailures.add("NoSuchPlayerX looked up " + unknownLookups + "x");
            }
            int auth = fake.count("/pv/authenticate");
            int started = hookInt("LOOKUPS_STARTED") - startedBefore;
            System.out.println(TAG + "requests: " + new TreeMap<>(fake.hits) + "; lookups started " + started + ", authenticate " + auth);
            if (started != ALL_NAMES.size()) {
                requestFailures.add("mod started " + started + " lookups for " + ALL_NAMES.size() + " names");
            }
            if (auth < 1 || auth > 2) {
                requestFailures.add("/authenticate called " + auth + "x (one token per session, two only if both slots raced)");
            }
            System.out.println(TAG + "dedupe/cache: " + (requestFailures.isEmpty() ? "PASS" : "FAIL " + requestFailures));
            c.note("dedupe/cache: " + (requestFailures.isEmpty() ? "PASS" : requestFailures));
            failures.addAll(requestFailures);

            // 5. Not on the render thread.
            Set<String> threads = hookThreads();
            boolean offRender = threads != null && !threads.isEmpty() && !threads.contains(renderThread);
            System.out.println(TAG + "lookup threads " + threads + " (render thread '" + renderThread + "'): "
                    + (offRender ? "PASS" : "FAIL"));
            c.note("lookup threads " + threads);
            if (!offRender) {
                failures.add("lookups started on " + threads + " (render thread '" + renderThread + "')");
            }
        } finally {
            forget(c);
            PartyFinderStyleCases.parkCursor(c);
            MenuKit.reset(c);
        }
        System.out.println(TAG + (failures.isEmpty() ? "PASS" : "FAIL " + failures));
        c.check(failures.isEmpty(), "Party Finder stats: " + failures);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    static int profileFetches(Fake fake, String uuid) {
        String dashed = uuid.substring(0, 8) + "-" + uuid.substring(8, 12) + "-" + uuid.substring(12, 16) + "-"
                + uuid.substring(16, 20) + "-" + uuid.substring(20);
        return fake.count("/pv/profiles/" + dashed) + fake.count("/pv/profiles/" + uuid);
    }

    /** Drop whatever the mod already knows about these names, so the case measures its own requests. */
    @SuppressWarnings("unchecked")
    static void forget(Session c) {
        c.ctx().runOnClient(mc -> {
            for (String f : List.of("CACHE", "FAILED", "FAILURE_REASON")) {
                try {
                    Map<String, ?> m = (Map<String, ?>) Mod.field("partyfinder.PartyFinderStatsApi", f);
                    ALL_NAMES.forEach(n -> m.remove(n.toLowerCase(Locale.ROOT)));
                } catch (RuntimeException | AssertionError ignored) {
                    // An old jar has no such field.
                }
            }
        });
    }

    static int hookInt(String field) {
        try {
            return ((AtomicInteger) Mod.field("partyfinder.PartyFinderStatsApi", field)).get();
        } catch (RuntimeException | AssertionError e) {
            return -1;
        }
    }

    @SuppressWarnings("unchecked")
    static Set<String> hookThreads() {
        try {
            return Set.copyOf((Set<String>) Mod.field("partyfinder.PartyFinderStatsApi", "LOOKUP_THREADS"));
        } catch (RuntimeException | AssertionError e) {
            return null;
        }
    }

    static List<String> tooltipText(Session c, int slot) {
        List<String> out = new ArrayList<>();
        for (Component l : PartyFinderStyleCases.tooltip(c, slot)) {
            out.add(PartyFinderStyleCases.strip(l.getString()));
        }
        return out;
    }

    static List<String> lines(Session c, int slot, int n) {
        return memberText(tooltipText(c, slot), n);
    }

    /** The tooltip lines naming one of this case's players, in order (at most {@code n}). */
    static List<String> memberText(List<String> tip, int n) {
        List<String> out = new ArrayList<>();
        for (String s : tip) {
            if (s.contains("'s Party") || s.startsWith("?")) {
                continue;
            }
            for (String name : ALL_NAMES) {
                if (s.contains(name)) {
                    out.add(s);
                    break;
                }
            }
        }
        return out.size() > n ? out.subList(0, n) : out;
    }

    static void compare(Session c, String label, List<String> real, List<String> expected, List<String> failures) {
        boolean ok = real.equals(expected);
        System.out.println(TAG + label + ": " + (ok ? "PASS" : "FAIL"));
        for (int i = 0; i < Math.max(real.size(), expected.size()); i++) {
            String a = i < real.size() ? real.get(i) : "(missing)";
            String b = i < expected.size() ? expected.get(i) : "(missing)";
            System.out.println(TAG + "  " + (a.equals(b) ? "ok   " : "DIFF ") + "'" + a + "'" + (a.equals(b) ? "" : " expected '" + b + "'"));
        }
        c.note(label + ": " + (ok ? "PASS" : "FAIL") + " " + real);
        if (!ok) {
            failures.add(label + ": " + real);
        }
    }

    static void footer(Session c, String label, List<String> tip, String expected, List<String> failures) {
        String found = null;
        for (String s : tip) {
            if (s.startsWith("? = ")) {
                found = s;
            }
        }
        boolean ok = found != null && found.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT));
        System.out.println(TAG + label + " footer: " + (ok ? "PASS" : "FAIL") + " '" + found + "'");
        c.note(label + " footer '" + found + "'");
        if (!ok) {
            failures.add(label + " footer '" + found + "', wanted it to contain '" + expected + "'");
        }
    }
}
