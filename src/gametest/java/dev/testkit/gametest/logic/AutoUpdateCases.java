package dev.testkit.gametest.logic;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;

import dev.testkit.gametest.ModUnderTest;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * 776-785: the mod's Auto Update (killer560, 2026-10-11) against an in-process fake of GitHub's releases API and
 * release downloads, on a port in 32100-32299. The mod's {@code github-api} and {@code github-download} services are
 * pointed at it at run time (AutoUpdater reads both on every check) and restored afterwards. Every case asserts the
 * fake SAW the requests it expects (or saw none), so a check that never ran cannot pass.
 *
 * <pre>
 * 776-logic-autoupdate-asset       each of 26.1.2/26.2 x cheat/legit, on both channels, stages exactly its own jar
 * 777-logic-autoupdate-channel     Beta asks tags/dev-latest, Full asks latest; a prerelease is never a Full Release
 * 778-logic-autoupdate-checksum    a jar whose SHA-256 is not the one in SHA256SUMS.txt is downloaded, rejected, deleted
 * 779-logic-autoupdate-same-hash   the release's commit equals this jar's: no checksum or jar request at all
 * 780-logic-autoupdate-staged      the same release twice downloads once; a corrupted staged jar is fetched again
 * 781-logic-autoupdate-off         channel Off makes zero requests; the real config's Off reaches launchGate()
 * 782-logic-autoupdate-gates       testing builds and the deploy marker make zero requests; the marker in this game dir
 *                                  reaches launchGate()
 * 783-logic-autoupdate-source      a jar from another host or repo, a missing SHA256SUMS.txt or commit.txt, and the old
 *                                  version-less asset names are all refused without downloading a jar
 * 784-logic-autoupdate-helper      the real installer process, against a dummy parent: nothing moves while the parent
 *                                  runs; afterwards mods/ holds exactly one mod jar, the new one
 * 785-logic-autoupdate-helper-refuse a name collision leaves mods/ untouched; this test client itself never updates
 * </pre>
 *
 * Every helper case runs in a temp folder; nothing here touches a Prism instance or this client's own mods.
 */
final class AutoUpdateCases {

    private AutoUpdateCases() {
    }

    static final String UPD = "com.killer560.hub.autoupdate.AutoUpdater";
    static final String FEAT = "com.killer560.hub.autoupdate.AutoUpdateFeature";
    static final String CFG = "com.killer560.hub.autoupdate.AutoUpdateConfig";
    static final String REPO = "Killer560-OG/killer560s-mod";
    static final String CUR = "a".repeat(40);
    static final String REMOTE = "b".repeat(40);
    static final List<String[]> COMBOS = List.of(new String[]{"26.1.2", "cheat"}, new String[]{"26.1.2", "legit"},
            new String[]{"26.2", "cheat"}, new String[]{"26.2", "legit"});

    // ---- the fake --------------------------------------------------------------------------------------------------

    static final class Fake implements AutoCloseable {
        final HttpServer server;
        final int port;
        final Map<String, byte[]> files = new ConcurrentHashMap<>();
        final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
        volatile String latest = "";
        volatile String beta = "";
        private final String oldApi;
        private final String oldDl;

        Fake() throws IOException {
            HttpServer s = null;
            int p = 0;
            for (int candidate = 32100; candidate <= 32299 && s == null; candidate++) {
                try {
                    s = HttpServer.create(new InetSocketAddress("127.0.0.1", candidate), 0);
                    p = candidate;
                } catch (IOException taken) {
                    // try the next port in this branch's range
                }
            }
            if (s == null) {
                throw new IOException("no free port in 32100-32299");
            }
            server = s;
            port = p;
            server.createContext("/", ex -> {
                String path = ex.getRequestURI().getPath();
                hits.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();
                byte[] body;
                int code = 200;
                if (path.equals("/repos/" + REPO + "/releases/latest")) {
                    body = latest.getBytes(StandardCharsets.UTF_8);
                } else if (path.equals("/repos/" + REPO + "/releases/tags/dev-latest")) {
                    body = beta.getBytes(StandardCharsets.UTF_8);
                } else if (files.containsKey(path)) {
                    body = files.get(path);
                } else {
                    body = "not found".getBytes(StandardCharsets.UTF_8);
                    code = 404;
                }
                if (code == 200 && body.length == 0) {
                    body = "not found".getBytes(StandardCharsets.UTF_8);
                    code = 404;
                }
                ex.sendResponseHeaders(code, body.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(body);
                }
            });
            server.start();
            oldApi = System.getProperty("killer560.net.github-api");
            oldDl = System.getProperty("killer560.net.github-download");
            System.setProperty("killer560.net.github-api", base());
            System.setProperty("killer560.net.github-download", base());
        }

        String base() {
            return "http://127.0.0.1:" + port;
        }

        String dlPath(String tag, String name) {
            return "/" + REPO + "/releases/download/" + tag + "/" + name;
        }

        int hits(String path) {
            AtomicInteger n = hits.get(path);
            return n == null ? 0 : n.get();
        }

        int total() {
            return hits.values().stream().mapToInt(AtomicInteger::get).sum();
        }

        int jarHits() {
            int n = 0;
            for (Map.Entry<String, AtomicInteger> e : hits.entrySet()) {
                if (e.getKey().endsWith(".jar")) {
                    n += e.getValue().get();
                }
            }
            return n;
        }

        void reset() {
            hits.clear();
        }

        @Override
        public void close() {
            server.stop(0);
            restore("killer560.net.github-api", oldApi);
            restore("killer560.net.github-download", oldDl);
        }

        private static void restore(String key, String old) {
            if (old == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, old);
            }
        }
    }

    /** One release's worth of assets, published on the fake under {@code tag}. */
    static final class Release {
        final String tag;
        final boolean prerelease;
        final Map<String, byte[]> jars = new LinkedHashMap<>();
        final Map<String, String> urlOverride = new LinkedHashMap<>();
        String commit = REMOTE;
        boolean withSums = true;
        boolean withCommit = true;
        final Map<String, String> sumOverride = new LinkedHashMap<>();

        Release(String tag, boolean prerelease, long seed) {
            this.tag = tag;
            this.prerelease = prerelease;
            Random rnd = new Random(seed);
            for (String[] c : COMBOS) {
                jars.put(jarName(c[0], c[1]), bytes(rnd));
            }
            // A decoy that must never be picked: the private testing jar of the same MC and variant.
            jars.put("killer560smod-1.1.0-26.1.2-cheat-testing.jar", bytes(rnd));
        }

        static byte[] bytes(Random rnd) {
            byte[] b = new byte[64 * 1024 + rnd.nextInt(4096)];
            rnd.nextBytes(b);
            return b;
        }

        /** Publishes this release on the fake: JSON plus every file. */
        void publish(Fake f) {
            JsonObject rel = new JsonObject();
            rel.addProperty("tag_name", tag);
            rel.addProperty("prerelease", prerelease);
            rel.addProperty("draft", false);
            rel.addProperty("body", "Notes for " + tag);
            JsonArray assets = new JsonArray();
            StringBuilder sums = new StringBuilder();
            for (Map.Entry<String, byte[]> e : jars.entrySet()) {
                String path = f.dlPath(tag, e.getKey());
                f.files.put(path, e.getValue());
                assets.add(asset(e.getKey(), urlOverride.getOrDefault(e.getKey(), f.base() + path)));
                sums.append(sumOverride.getOrDefault(e.getKey(), sha(e.getValue()))).append("  ").append(e.getKey())
                        .append('\n');
            }
            if (withSums) {
                String path = f.dlPath(tag, "SHA256SUMS.txt");
                f.files.put(path, sums.toString().getBytes(StandardCharsets.UTF_8));
                assets.add(asset("SHA256SUMS.txt", f.base() + path));
            }
            if (withCommit) {
                String path = f.dlPath(tag, "commit.txt");
                f.files.put(path, (commit + "\n").getBytes(StandardCharsets.UTF_8));
                assets.add(asset("commit.txt", f.base() + path));
            }
            rel.add("assets", assets);
            if (tag.equals("dev-latest")) {
                f.beta = rel.toString();
            } else {
                f.latest = rel.toString();
            }
        }

        static JsonObject asset(String name, String url) {
            JsonObject a = new JsonObject();
            a.addProperty("name", name);
            a.addProperty("browser_download_url", url);
            return a;
        }
    }

    static String jarName(String mc, String variant) {
        return "killer560smod-1.1.0-" + mc + "-" + variant + ".jar";
    }

    static String sha(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String check(String channel, boolean testing, Path gameDir, String mc, String variant, String commit,
                        Path staging) {
        return (String) ModUnderTest.staticCall(UPD, "checkForTest",
                new Class<?>[]{String.class, boolean.class, String.class, String.class, String.class, String.class,
                        String.class},
                new Object[]{channel, testing, gameDir == null ? null : gameDir.toString(), mc, variant, commit,
                        staging.toString()});
    }

    static Path tempDir(String tag) throws IOException {
        return Files.createTempDirectory("k560-autoupdate-" + tag + "-");
    }

    static List<String> list(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    static void deleteTree(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException ignored) {
        }
    }

    // ---- 776 ---------------------------------------------------------------------------------------------------------

    static void asset(LogicCase c) throws Exception {
        try (Fake f = new Fake()) {
            Release beta = new Release("dev-latest", true, 1);
            Release full = new Release("v1.2.0", false, 2);
            beta.publish(f);
            full.publish(f);
            for (String channel : new String[]{"BETA", "FULL_RELEASE"}) {
                Release rel = channel.equals("BETA") ? beta : full;
                for (String[] combo : COMBOS) {
                    String want = jarName(combo[0], combo[1]);
                    Path staging = tempDir("asset");
                    try {
                        f.reset();
                        String out = check(channel, false, null, combo[0], combo[1], CUR, staging);
                        String label = channel + " " + combo[0] + " " + combo[1];
                        c.note(label + ": " + out);
                        c.check(label + " staged", out.startsWith("STAGED"), out);
                        c.check(label + " picked " + want, out.contains("asset=" + want + " "), out);
                        c.eq(label + " downloaded that jar once", 1, f.hits(f.dlPath(rel.tag, want)));
                        c.eq(label + " downloaded no other jar", 1, f.jarHits());
                        Path staged = staging.resolve(want);
                        c.check(label + " staged file is the release's bytes", Files.isRegularFile(staged)
                                && sha(Files.readAllBytes(staged)).equals(sha(rel.jars.get(want))));
                        c.eq(label + " staging holds one jar + staged.json", List.of(want, "staged.json"),
                                list(staging));
                    } finally {
                        deleteTree(staging);
                    }
                }
            }
            // The name rule itself, on the strings that tell the four apart.
            Class<?>[] t = {String.class, String.class, String.class};
            c.eq("26.1.2 jar is not a 26.2 asset", false, ModUnderTest.staticCall(UPD, "assetMatches", t,
                    new Object[]{jarName("26.1.2", "cheat"), "26.2", "cheat"}));
            c.eq("26.2 jar is not a 26.1.2 asset", false, ModUnderTest.staticCall(UPD, "assetMatches", t,
                    new Object[]{jarName("26.2", "cheat"), "26.1.2", "cheat"}));
            c.eq("legit jar is not a cheat asset", false, ModUnderTest.staticCall(UPD, "assetMatches", t,
                    new Object[]{jarName("26.1.2", "legit"), "26.1.2", "cheat"}));
            c.eq("testing jar is never an asset", false, ModUnderTest.staticCall(UPD, "assetMatches", t,
                    new Object[]{"killer560smod-1.1.0-26.1.2-cheat-testing.jar", "26.1.2", "cheat"}));
        }
    }

    // ---- 777 ---------------------------------------------------------------------------------------------------------

    static void channel(LogicCase c) throws Exception {
        try (Fake f = new Fake()) {
            new Release("dev-latest", true, 3).publish(f);
            new Release("v1.2.0", false, 4).publish(f);
            String latest = "/repos/" + REPO + "/releases/latest";
            String tag = "/repos/" + REPO + "/releases/tags/dev-latest";
            Path staging = tempDir("channel");
            try {
                f.reset();
                String out = check("BETA", false, null, "26.1.2", "legit", CUR, staging);
                c.check("Beta staged", out.startsWith("STAGED"), out);
                c.eq("Beta asked tags/dev-latest", 1, f.hits(tag));
                c.eq("Beta never asked latest", 0, f.hits(latest));
                c.eq("Beta downloaded from dev-latest", 1,
                        f.hits(f.dlPath("dev-latest", jarName("26.1.2", "legit"))));
                deleteTree(staging);

                f.reset();
                out = check("FULL_RELEASE", false, null, "26.1.2", "legit", CUR, staging);
                c.check("Full Release staged", out.startsWith("STAGED"), out);
                c.eq("Full Release asked latest", 1, f.hits(latest));
                c.eq("Full Release never asked dev-latest", 0, f.hits(tag));
                c.eq("Full Release downloaded from v1.2.0", 1, f.hits(f.dlPath("v1.2.0", jarName("26.1.2", "legit"))));
                deleteTree(staging);

                // GitHub's /latest never returns a prerelease, but the mod must refuse one if it ever does.
                new Release("v1.3.0-rc", true, 5).publish(f);
                f.reset();
                out = check("FULL_RELEASE", false, null, "26.1.2", "legit", CUR, staging);
                c.check("a prerelease is not a Full Release", out.startsWith("NO_RELEASE"), out);
                c.eq("the prerelease was looked at", 1, f.hits(latest));
                c.eq("and nothing was downloaded", 0, f.jarHits());

                // No release at all: a 404 is NO_RELEASE, not an error.
                f.latest = "";
                f.reset();
                out = check("FULL_RELEASE", false, null, "26.1.2", "legit", CUR, staging);
                c.check("no release reads NO_RELEASE", out.startsWith("NO_RELEASE"), out);
            } finally {
                deleteTree(staging);
            }
        }
    }

    // ---- 778 ---------------------------------------------------------------------------------------------------------

    static void checksum(LogicCase c) throws Exception {
        try (Fake f = new Fake()) {
            Release rel = new Release("dev-latest", true, 6);
            String name = jarName("26.1.2", "cheat");
            rel.sumOverride.put(name, "0".repeat(64));
            rel.publish(f);
            Path staging = tempDir("checksum");
            try {
                f.reset();
                String out = check("BETA", false, null, "26.1.2", "cheat", CUR, staging);
                c.check("mismatch rejected", out.startsWith("CHECKSUM_MISMATCH"), out);
                c.eq("the jar was really downloaded and hashed", 1, f.hits(f.dlPath("dev-latest", name)));
                c.check("the reported hash is the real one", out.contains(sha(rel.jars.get(name))), out);
                c.eq("nothing left in staging (no jar, no .part, no staged.json)", List.of(), list(staging));

                // Control: the other variant's checksum is right in the same file, so it stages.
                f.reset();
                out = check("BETA", false, null, "26.1.2", "legit", CUR, staging);
                c.check("control: correct checksum stages", out.startsWith("STAGED"), out);
            } finally {
                deleteTree(staging);
            }
        }
    }

    // ---- 779 ---------------------------------------------------------------------------------------------------------

    static void sameHash(LogicCase c) throws Exception {
        try (Fake f = new Fake()) {
            Release rel = new Release("dev-latest", true, 7);
            rel.commit = CUR;
            rel.publish(f);
            Path staging = tempDir("samehash");
            try {
                f.reset();
                String out = check("BETA", false, null, "26.2", "legit", CUR, staging);
                c.check("same commit is UP_TO_DATE", out.startsWith("UP_TO_DATE"), out);
                c.eq("commit.txt was read", 1, f.hits(f.dlPath("dev-latest", "commit.txt")));
                c.eq("no checksum request", 0, f.hits(f.dlPath("dev-latest", "SHA256SUMS.txt")));
                c.eq("no jar request", 0, f.jarHits());
                c.eq("nothing staged", List.of(), list(staging));

                // A short hash in commit.txt names the same build.
                rel.commit = CUR.substring(0, 7);
                rel.publish(f);
                f.reset();
                out = check("BETA", false, null, "26.2", "legit", CUR, staging);
                c.check("a 7-character commit.txt also matches", out.startsWith("UP_TO_DATE"), out);
                c.eq("still no jar request", 0, f.jarHits());

                // Control: a different commit does download.
                rel.commit = REMOTE;
                rel.publish(f);
                f.reset();
                out = check("BETA", false, null, "26.2", "legit", CUR, staging);
                c.check("control: a different commit stages", out.startsWith("STAGED"), out);
                c.eq("control: one jar request", 1, f.jarHits());
            } finally {
                deleteTree(staging);
            }
        }
    }

    // ---- 780 ---------------------------------------------------------------------------------------------------------

    static void staged(LogicCase c) throws Exception {
        try (Fake f = new Fake()) {
            new Release("dev-latest", true, 8).publish(f);
            String name = jarName("26.2", "cheat");
            Path staging = tempDir("staged");
            try {
                f.reset();
                String first = check("BETA", false, null, "26.2", "cheat", CUR, staging);
                String second = check("BETA", false, null, "26.2", "cheat", CUR, staging);
                c.check("first check stages", first.startsWith("STAGED"), first);
                c.check("second check reuses it", second.startsWith("ALREADY_STAGED"), second);
                c.eq("one download for two checks", 1, f.jarHits());

                Files.write(staging.resolve(name), new byte[]{1, 2, 3});
                f.reset();
                String third = check("BETA", false, null, "26.2", "cheat", CUR, staging);
                c.check("a corrupted staged jar is fetched again", third.startsWith("STAGED"), third);
                c.eq("one fresh download", 1, f.jarHits());
            } finally {
                deleteTree(staging);
            }
        }
    }

    // ---- 781 ---------------------------------------------------------------------------------------------------------

    static void off(LogicCase c) throws Exception {
        try (Fake f = new Fake()) {
            new Release("dev-latest", true, 9).publish(f);
            new Release("v1.2.0", false, 10).publish(f);
            Path staging = tempDir("off");
            try {
                f.reset();
                String out = check("OFF", false, null, "26.1.2", "cheat", CUR, staging);
                c.check("Off is blocked", out.startsWith("BLOCKED") && out.contains("Off"), out);
                c.eq("Off made zero requests", 0, f.total());
                c.eq("Off staged nothing", List.of(), list(staging));
                // Control: the same fake answers once the channel is on.
                out = check("BETA", false, null, "26.1.2", "cheat", CUR, staging);
                c.check("control: Beta on the same fake stages", out.startsWith("STAGED"), out);
                c.check("control: the fake saw requests", f.total() > 0);
            } finally {
                deleteTree(staging);
            }
        }
        // The real setting: Off reaches the gate the launch check and Check Now both use.
        Object cfg = ModUnderTest.config(CFG);
        Object before = ModUnderTest.call(cfg, "getChannel", new Class<?>[0], new Object[0]);
        c.eq("default channel is Full Release (unless this client's config changed it)", "FULL_RELEASE",
                before.toString());
        Class<?> channelType = Class.forName(CFG + "$Channel");
        try {
            // The channel survives a restart: set Beta, save, reload from disk, read it back.
            ModUnderTest.call(cfg, "setChannel", new Class<?>[]{channelType},
                    new Object[]{ModUnderTest.enumValue(CFG + "$Channel", "BETA")});
            ModUnderTest.call(cfg, "save", new Class<?>[0], new Object[0]);
            ModUnderTest.staticCall(CFG, "load");
            Object reloaded = ModUnderTest.config(CFG);
            c.eq("Beta survives save + load", "BETA",
                    ModUnderTest.call(reloaded, "getChannel", new Class<?>[0], new Object[0]).toString());
            cfg = reloaded;
            ModUnderTest.call(cfg, "setChannel", new Class<?>[]{channelType},
                    new Object[]{ModUnderTest.enumValue(CFG + "$Channel", "OFF")});
            String gate = (String) ModUnderTest.staticCall(FEAT, "launchGate");
            c.check("launchGate says Off", gate != null && gate.contains("Off"), String.valueOf(gate));
        } finally {
            ModUnderTest.call(cfg, "setChannel", new Class<?>[]{channelType}, new Object[]{before});
            ModUnderTest.call(cfg, "save", new Class<?>[0], new Object[0]);
        }
    }

    // ---- 782 ---------------------------------------------------------------------------------------------------------

    static void gates(LogicCase c) throws Exception {
        try (Fake f = new Fake()) {
            new Release("dev-latest", true, 11).publish(f);
            Path staging = tempDir("gates");
            Path game = tempDir("gates-game");
            try {
                f.reset();
                String out = check("BETA", true, game, "26.1.2", "cheat", CUR, staging);
                c.check("a testing build is blocked", out.startsWith("BLOCKED") && out.contains("testing"), out);
                c.eq("testing build made zero requests", 0, f.total());

                Path marker = game.resolve("killer560smod-no-auto-update");
                Files.writeString(marker, "test");
                out = check("BETA", false, game, "26.1.2", "cheat", CUR, staging);
                c.check("the deploy marker blocks", out.startsWith("BLOCKED") && out.contains("no-auto-update"), out);
                c.eq("marker made zero requests", 0, f.total());

                out = check("BETA", false, game, "26.1.2", "cheat", "unknown", staging);
                c.check("a jar without a build commit is blocked", out.startsWith("BLOCKED"), out);
                c.eq("no-commit made zero requests", 0, f.total());

                Files.delete(marker);
                out = check("BETA", false, game, "26.1.2", "cheat", CUR, staging);
                c.check("control: without the marker it stages", out.startsWith("STAGED"), out);
                c.check("control: the fake saw requests", f.total() > 0);
            } finally {
                deleteTree(staging);
                deleteTree(game);
            }
        }
        // The real gate reads the marker from THIS game's folder.
        Path gameDir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir();
        Path marker = gameDir.resolve("killer560smod-no-auto-update");
        boolean existed = Files.exists(marker);
        try {
            if (!existed) {
                Files.writeString(marker, "testkit 782");
            }
            String gate = (String) ModUnderTest.staticCall(FEAT, "launchGate");
            c.check("launchGate names the marker", gate != null && gate.contains("no-auto-update"),
                    String.valueOf(gate));
        } finally {
            if (!existed) {
                Files.deleteIfExists(marker);
            }
        }
    }

    // ---- 783 ---------------------------------------------------------------------------------------------------------

    static void source(LogicCase c) throws Exception {
        String name = jarName("26.1.2", "legit");
        try (Fake f = new Fake()) {
            Path staging = tempDir("source");
            try {
                // (a) another host
                Release rel = new Release("dev-latest", true, 12);
                rel.urlOverride.put(name, "http://127.0.0.2:" + f.port + "/" + REPO + "/releases/download/dev-latest/"
                        + name);
                rel.publish(f);
                f.reset();
                String out = check("BETA", false, null, "26.1.2", "legit", CUR, staging);
                c.check("another host is refused", out.startsWith("BAD_SOURCE"), out);
                c.eq("no jar fetched from it", 0, f.jarHits());

                // (b) the same host, another repository
                rel = new Release("dev-latest", true, 13);
                rel.urlOverride.put(name, f.base() + "/someone/else/releases/download/dev-latest/" + name);
                rel.publish(f);
                f.reset();
                out = check("BETA", false, null, "26.1.2", "legit", CUR, staging);
                c.check("another repo is refused", out.startsWith("BAD_SOURCE"), out);
                c.eq("no jar fetched from it", 0, f.jarHits());

                // (c) a path that climbs out of the releases folder
                rel = new Release("dev-latest", true, 14);
                rel.urlOverride.put(name, f.base() + "/" + REPO + "/releases/download/../../../evil/" + name);
                rel.publish(f);
                f.reset();
                out = check("BETA", false, null, "26.1.2", "legit", CUR, staging);
                c.check("a '..' path is refused", out.startsWith("BAD_SOURCE"), out);

                // (d) no SHA256SUMS.txt
                rel = new Release("dev-latest", true, 15);
                rel.withSums = false;
                rel.publish(f);
                f.reset();
                out = check("BETA", false, null, "26.1.2", "legit", CUR, staging);
                c.check("no checksum file is refused", out.startsWith("NO_CHECKSUM"), out);
                c.eq("and no jar fetched", 0, f.jarHits());

                // (e) no commit.txt
                rel = new Release("dev-latest", true, 16);
                rel.withCommit = false;
                rel.publish(f);
                f.reset();
                out = check("BETA", false, null, "26.1.2", "legit", CUR, staging);
                c.check("no commit.txt is refused", out.startsWith("NO_ASSET"), out);
                c.eq("and no jar fetched", 0, f.jarHits());

                // (f) the pre-2026-10-11 release shape: killer560smod-1.1.0-legit.jar, no MC version in the name
                rel = new Release("v1.1.0", false, 17);
                rel.jars.clear();
                rel.jars.put("killer560smod-1.1.0-legit.jar", new byte[]{1});
                rel.jars.put("killer560smod-1.1.0-cheat.jar", new byte[]{2});
                rel.publish(f);
                f.reset();
                out = check("FULL_RELEASE", false, null, "26.1.2", "legit", CUR, staging);
                c.check("old version-less names are never installed", out.startsWith("NO_ASSET"), out);
                c.eq("and no jar fetched", 0, f.jarHits());
                c.eq("nothing staged by any of them", List.of(), list(staging));
            } finally {
                deleteTree(staging);
            }
        }
    }

    // ---- 784 / 785: the installer process ------------------------------------------------------------------------------

    /** A parent that lives about {@code seconds} and then exits on its own. */
    /** Set by LogicSuite: the helper cases wait through it, so the client keeps ticking (and responding) meanwhile. */
    static volatile net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext ctx;

    /** Polls {@code cond} every 5 client ticks for up to {@code seconds}; true once it holds. */
    static boolean waitUntil(java.util.function.BooleanSupplier cond, int seconds) {
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        while (!cond.getAsBoolean()) {
            if (System.nanoTime() > end) {
                return false;
            }
            if (ctx != null) {
                ctx.waitTicks(5);
            } else {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return true;
    }

    /** A parent that lives about {@code seconds} and then exits on its own: this JVM's own java running a one-line
     *  sleeper (source-file mode), so nothing depends on what PATH holds. */
    static Process dummyParent(Path dir, int seconds) throws IOException {
        Path src = dir.resolve("Sleeper.java");
        if (!Files.exists(src)) {
            Files.writeString(src, "public class Sleeper { public static void main(String[] a) throws Exception {"
                    + " Thread.sleep(Long.parseLong(a[0])); } }\n");
        }
        String java = ProcessHandle.current().info().command().orElse("java");
        ProcessBuilder pb = new ProcessBuilder(java, src.toString(), Long.toString(seconds * 1000L));
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        return pb.start();
    }

    static List<String> modJars(Path mods) throws IOException {
        List<String> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(mods, "killer560smod*.jar")) {
            for (Path p : ds) {
                out.add(p.getFileName().toString());
            }
        }
        out.sort(null);
        return out;
    }

    static long spawn(long parent, Path oldJar, Path newJar, Path staging) {
        return (Long) ModUnderTest.staticCall(FEAT, "spawnHelper",
                new Class<?>[]{long.class, String.class, String.class, String.class},
                new Object[]{parent, oldJar.toString(), newJar.toString(), staging.toString()});
    }

    /** Waits (ticking the client) for the helper to exit, then returns swap.log. On a timeout it reports what the
     *  helper was doing (its command line and stdout log), kills that one PID, and throws. */
    static String waitHelper(long pid, Path staging) throws Exception {
        var h = ProcessHandle.of(pid);
        if (h.isPresent() && !waitUntil(() -> !h.get().isAlive(), 60)) {
            String cmd = h.get().info().commandLine().orElse("?");
            h.get().destroyForcibly();
            throw new AssertionError("helper " + pid + " still running after 60 s; command: " + cmd + "; swap.log: "
                    + read(staging.resolve("swap.log")) + "; stdout: " + read(staging.resolve("swap-helper-out.log")));
        }
        return read(staging.resolve("swap.log"));
    }

    static String read(Path p) {
        try {
            return Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : "(none)";
        } catch (IOException e) {
            return e.toString();
        }
    }

    static void helper(LogicCase c) throws Exception {
        Path root = tempDir("helper");
        Process parent = null;
        try {
            Path mods = Files.createDirectories(root.resolve("mods"));
            Path staging = Files.createDirectories(root.resolve("staging"));
            Random rnd = new Random(20);
            byte[] oldBytes = Release.bytes(rnd);
            byte[] newBytes = Release.bytes(rnd);
            byte[] other = Release.bytes(rnd);
            Path oldJar = mods.resolve("killer560smod-1.1.0-26.1.2-cheat.jar");
            Files.write(oldJar, oldBytes);
            Files.write(mods.resolve("fabric-api-0.155.2.jar"), other);
            Path newJar = staging.resolve("killer560smod-1.1.0-26.1.2-cheat.jar");
            Files.write(newJar, newBytes);
            Files.writeString(staging.resolve("staged.json"), "{\"commit\":\"" + REMOTE + "\"}");

            parent = dummyParent(root, 8);
            long helperPid = spawn(parent.pid(), oldJar, newJar, staging);
            c.check("helper started", helperPid > 0, "pid " + helperPid);

            // While the parent runs, nothing in mods/ may change.
            long t0 = System.nanoTime();
            waitUntil(() -> System.nanoTime() - t0 > 3_000_000_000L, 10);
            c.check("parent still running at +3 s (the wait is real)", parent.isAlive());
            c.check("helper still waiting at +3 s", ProcessHandle.of(helperPid).map(ProcessHandle::isAlive)
                    .orElse(false));
            c.check("old jar untouched while the parent runs", Files.exists(oldJar)
                    && sha(Files.readAllBytes(oldJar)).equals(sha(oldBytes)));
            c.eq("no .part yet", false, Files.exists(mods.resolve(oldJar.getFileName() + ".part")));

            Process p1 = parent;
            c.check("parent exits", waitUntil(() -> !p1.isAlive(), 30));
            String log = waitHelper(helperPid, staging);
            c.note("swap.log:\n" + log);
            int exited = log.indexOf("parent " + parent.pid() + " has exited");
            int swapped = log.indexOf("swapped:");
            c.check("log: the swap came after the parent exited", exited >= 0 && swapped > exited, log);
            c.check("log shows it waited", log.contains("waiting for parent " + parent.pid()), log);
            c.check("log shows exit 0", log.contains("exit 0"), log);
            c.eq("exactly one mod jar in mods/", List.of("killer560smod-1.1.0-26.1.2-cheat.jar"), modJars(mods));
            c.check("it is the NEW jar", sha(Files.readAllBytes(oldJar)).equals(sha(newBytes)));
            c.check("other mods untouched", sha(Files.readAllBytes(mods.resolve("fabric-api-0.155.2.jar")))
                    .equals(sha(other)));
            c.eq("mods/ holds nothing else", List.of("fabric-api-0.155.2.jar", "killer560smod-1.1.0-26.1.2-cheat.jar"),
                    list(mods));
            c.check("staged jar consumed", !Files.exists(newJar));
            c.check("installed.json written for the next launch's popup", Files.exists(staging.resolve(
                    "installed.json")) && !Files.exists(staging.resolve("staged.json")));
            c.check("old jar kept OUTSIDE mods/ as previous.jar.bak", Files.exists(staging.resolve("previous.jar.bak")));

            // A different file name (a new mod version) also ends with exactly one jar.
            Path staging2 = Files.createDirectories(root.resolve("staging2"));
            Path renamed = staging2.resolve("killer560smod-1.2.0-26.1.2-cheat.jar");
            Files.write(renamed, oldBytes);
            Process parent2 = dummyParent(root, 1);
            try {
                String log2 = waitHelper(spawn(parent2.pid(), oldJar, renamed, staging2), staging2);
                c.check("renamed swap exit 0", log2.contains("exit 0"), log2);
                c.eq("renamed swap: exactly one mod jar", List.of("killer560smod-1.2.0-26.1.2-cheat.jar"),
                        modJars(mods));
            } finally {
                parent2.destroyForcibly();
            }
        } finally {
            if (parent != null) {
                parent.destroyForcibly();
            }
            deleteTree(root);
        }
    }

    static void helperRefuse(LogicCase c) throws Exception {
        Path root = tempDir("refuse");
        try {
            Path mods = Files.createDirectories(root.resolve("mods"));
            Path staging = Files.createDirectories(root.resolve("staging"));
            Random rnd = new Random(21);
            byte[] oldBytes = Release.bytes(rnd);
            byte[] collider = Release.bytes(rnd);
            Path oldJar = mods.resolve("killer560smod-1.1.0-26.2-legit.jar");
            Files.write(oldJar, oldBytes);
            // Someone already put a jar with the new name in mods/ - installing would leave two copies.
            Files.write(mods.resolve("killer560smod-1.2.0-26.2-legit.jar"), collider);
            Path newJar = staging.resolve("killer560smod-1.2.0-26.2-legit.jar");
            Files.write(newJar, Release.bytes(rnd));
            Process parent = dummyParent(root, 1);
            String log;
            try {
                log = waitHelper(spawn(parent.pid(), oldJar, newJar, staging), staging);
            } finally {
                parent.destroyForcibly();
            }
            c.note("swap.log:\n" + log);
            c.check("helper refused (exit 3)", log.contains("exit 3"), log);
            c.check("old jar untouched", sha(Files.readAllBytes(oldJar)).equals(sha(oldBytes)));
            c.check("collider untouched", sha(Files.readAllBytes(mods.resolve("killer560smod-1.2.0-26.2-legit.jar")))
                    .equals(sha(collider)));
            c.eq("no .part left", List.of("killer560smod-1.1.0-26.2-legit.jar", "killer560smod-1.2.0-26.2-legit.jar"),
                    list(mods));
            c.check("staged jar kept for a later try", Files.exists(newJar));

            // A missing staged jar changes nothing either.
            Path staging2 = Files.createDirectories(root.resolve("staging2"));
            Process parent2 = dummyParent(root, 1);
            try {
                String log2 = waitHelper(spawn(parent2.pid(), oldJar, staging2.resolve("missing.jar"), staging2),
                        staging2);
                c.check("missing staged jar refused", log2.contains("exit 3"), log2);
            } finally {
                parent2.destroyForcibly();
            }
            c.check("old jar still untouched", sha(Files.readAllBytes(oldJar)).equals(sha(oldBytes)));
        } finally {
            deleteTree(root);
        }
        // This test client loads the mod through fabric.addMods, so the real gate must refuse to update it.
        String gate = (String) ModUnderTest.staticCall(FEAT, "launchGate");
        c.check("this test client never self-updates", gate != null && gate.contains("mods folder"),
                String.valueOf(gate));
        c.note("launchGate in this client: " + gate);
    }
}
