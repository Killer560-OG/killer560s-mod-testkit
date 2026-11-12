package dev.testkit.gametest.ui;

import dev.testkit.gametest.mod.Mod;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.UUID;

/**
 * 1112-mojang-budget: the mod never spends Mojang's session join budget in the background (the owner's first Hypixel
 * join failed with "Status: 429", 2026-11-12). Idle: no join and no sessionserver call. Control: an explicit Profile
 * Viewer data request does reach the join (the attempt log sees it), so zero elsewhere is not a blind hook. Then a
 * connect just started and a 429 back-off each hold the same request back.
 */
final class Mojang4291112Cases {

    private Mojang4291112Cases() {
    }

    @SuppressWarnings("unchecked")
    private static List<String> attempts() {
        return (List<String>) Mod.staticCall("util.ModNet", "attemptsForTest");
    }

    private static long joins() {
        return attempts().stream().filter(s -> s.startsWith("session-join://")).count();
    }

    private static void quiet() throws Exception {
        Mod.staticCall("profileviewer.api.ProfileViewerApi", "fetchProfiles", UUID.randomUUID(), true, true);
    }

    private static final String RELAY_PROP = "killer560.net.profileviewer-relay";

    private static void request() throws Exception {
        Mod.staticCall("profileviewer.api.ProfileViewerApi", "fetchProfiles", UUID.randomUUID(), true);
    }

    static void budget(UiCase c) throws Exception {
        System.setProperty(RELAY_PROP, "http://127.0.0.1:1"); // the shared-key relay refuses, so the backend join is reachable
        Mod.staticCall("relay.RelayCalls", "setTokenForTest", "test-token");
        try {
            budgetRun(c);
        } finally {
            System.clearProperty(RELAY_PROP);
            Mod.staticCall("relay.RelayCalls", "setTokenForTest", (Object) null);
        }
    }

    private static void budgetRun(UiCase c) throws Exception {
        Mod.staticCall("util.MojangBudget", "resetForTest");
        Mod.staticCall("util.ModNet", "clearAttemptsForTest");
        c.ticks(200);
        c.check(joins() == 0 && attempts().stream().noneMatch(s -> s.contains("sessionserver")),
                "the mod called Mojang's session server with nobody asking: " + attempts());
        quiet(); // a background reader with the relay refusing and no token: no join
        c.ticks(100);
        c.check(joins() == 0, "a background reader spent a join: " + attempts());

        request(); // control: an explicit request does join
        c.ticks(100);
        long control = joins();
        c.check(control >= 1, "control failed: an explicit Profile Viewer request never reached the join, so zero proves nothing: " + attempts());

        Mod.staticCall("util.MojangBudget", "noteConnect");
        request();
        c.ticks(100);
        c.check(joins() == control, "a join was spent right after a server connect started: " + attempts());

        Mod.staticCall("util.MojangBudget", "resetForTest");
        Mod.staticCall("util.MojangBudget", "note429", 0L);
        request();
        c.ticks(100);
        c.check(joins() == control, "a join was spent during the 429 back-off: " + attempts());
        Mod.staticCall("util.MojangBudget", "resetForTest");
        c.note("joins: control " + control + ", none while busy");
    }

    /** What the stub relay saw: "path|authorization". */
    private static final List<String> RELAY_HITS = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static volatile int relayStatus = 200;

    @SuppressWarnings("unchecked")
    private static Object done(UiCase c, Object future) throws Exception {
        CompletableFuture<Object> f = (CompletableFuture<Object>) future;
        for (int i = 0; i < 20 && !f.isDone(); i++) c.ticks(10);
        c.check(f.isDone(), "the fetch never finished");
        return f.isCompletedExceptionally() ? null : f.get();
    }

    /**
     * 1112-mojang-relay: the Profile Viewer and the background readers (Collection Tracker, museum) read through the
     * relay's shared Hypixel key. Stub relay on loopback; asserts the stub SAW the bearer call (so the fetch acted),
     * that nothing joined Mojang's session server in the whole relay-served run, that a refusing relay (429, 503)
     * stops a background reader with no join, and that an explicit request then falls back to the join (control).
     */
    static void relay(UiCase c) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hypixel/", ex -> {
            RELAY_HITS.add(ex.getRequestURI().getPath() + "?" + ex.getRequestURI().getRawQuery() + "|" + ex.getRequestHeaders().getFirst("Authorization"));
            String body = relayStatus == 200
                    ? (ex.getRequestURI().getPath().contains("museum") ? "{\"success\":true,\"members\":{}}" : "{\"success\":true,\"profiles\":[]}")
                    : "{\"error\":\"stub " + relayStatus + "\"}";
            byte[] b = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("content-type", "application/json");
            ex.sendResponseHeaders(relayStatus, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        System.setProperty(RELAY_PROP, "http://127.0.0.1:" + server.getAddress().getPort());
        Mod.staticCall("relay.RelayCalls", "setTokenForTest", "test-token");
        try {
            Mod.staticCall("util.MojangBudget", "resetForTest");
            Mod.staticCall("util.ModNet", "clearAttemptsForTest");
            RELAY_HITS.clear();
            relayStatus = 200;
            String id = "0123456789abcdef0123456789abcdef";
            c.check(((String) Mod.get("profileviewer.ProfileViewerConfig", "getApiKey")).isEmpty(), "the test instance has an API key set");
            Object profiles = done(c, Mod.staticCall("profileviewer.api.ProfileViewerApi", "fetchProfiles", UUID.fromString("01234567-89ab-cdef-0123-456789abcdef"), true, true));
            c.check(profiles != null && String.valueOf(profiles).contains("Shared Hypixel key"), "profiles were not served by the shared key: " + profiles);
            c.check(RELAY_HITS.contains("/hypixel/skyblock/profiles?id=" + id + "|Bearer test-token"), "the relay never got the profiles call: " + RELAY_HITS);
            Object kind = Mod.enumValue("profileviewer.api.ProfileViewerApi$AuxKind", "MUSEUM");
            Object museum = done(c, Mod.staticCall("profileviewer.api.ProfileViewerApi", "fetchAux", kind, "01234567-89ab-cdef-0123-456789abcdef", true, true));
            c.check(museum != null && RELAY_HITS.stream().anyMatch(h -> h.startsWith("/hypixel/skyblock/museum?id=01234567-89ab-cdef-0123-456789abcdef")), "the museum read never went through the relay: " + RELAY_HITS);
            c.check(joins() == 0 && attempts().stream().noneMatch(s -> s.contains("sessionserver")), "a relay-served run touched Mojang's session server: " + attempts());

            for (int status : new int[]{429, 503}) {
                relayStatus = status;
                int before = RELAY_HITS.size();
                Object r = done(c, Mod.staticCall("profileviewer.api.ProfileViewerApi", "fetchProfiles", UUID.randomUUID(), true, true));
                c.check(RELAY_HITS.size() == before + 1, "a " + status + " relay was not asked once: " + RELAY_HITS);
                c.check(r == null && joins() == 0, "a background reader fell back to a join when the relay said " + status + ": " + attempts());
            }
            // control: an explicit request does fall back to the backend sign-in, so the zero joins above are not a blind hook
            long cap = joins();
            done(c, Mod.staticCall("profileviewer.api.ProfileViewerApi", "fetchProfiles", UUID.randomUUID(), true));
            c.check(joins() > cap, "control failed: an explicit request never fell back to the backend join: " + attempts());
            c.note("relay hits " + RELAY_HITS.size() + ", joins only in the control: " + joins());
        } finally {
            System.clearProperty(RELAY_PROP);
            Mod.staticCall("relay.RelayCalls", "setTokenForTest", (Object) null);
            Mod.staticCall("util.MojangBudget", "resetForTest");
            server.stop(0);
        }
    }
}
