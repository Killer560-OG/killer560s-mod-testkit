package dev.testkit.gametest.logic;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import dev.testkit.gametest.mod.Mod;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

/**
 * 1119-*-sharesite-*: Share To Website (mod sharesite-1119, killer560 2026-11-19: ON by default, in the tour; relay
 * {@code /site/stats}). The relay is an in-process stub on {@code killer560.net.sharesite-relay}; the bearer token is a
 * test one ({@code RelayCalls.setTokenForTest}). Every step asserts what the STUB received, so a feature that never sent
 * cannot pass.
 *
 * <pre>
 * 1119-logic-sharesite-default    a fresh config (no file) is ON with no delete waiting; the profile import lock lists it
 * 1119-logic-sharesite-off        OFF: a terminal solve is not kept, DELETE /site/stats goes out with the token and clears
 *                                 pendingDelete; with the relay unreachable the delete waits and a world-join flush sends it
 * 1119-hx-sharesite-run           (HxDungeonCases) a real run through the chat path with Run Summary OFF: one POST with the
 *                                 floor, time, boss beaten, own secrets and the terminal solve; nothing in Run History; OFF
 *                                 then sends DELETE and a second run sends nothing
 * 1119-hx-sharesite-notice       (HxDungeonCases) notice not shown: a finished run uploads nothing; after the notice is answered
 *                                 the next run uploads
 * 1119-ui-intro-sharesite-notice  (IntroSuite) a player past the tour gets ONLY the Share To Website scene (Keep it on / Turn it
 *                                 off, both save noticeShown; OFF sends the delete); the normal tour answers it too
 * 1119-ui-intro-sharesite         (IntroSuite) the tour's step: ON pre-chosen, never blocks, Turn it off / Keep it on
 * </pre>
 */
public final class ShareSiteCases {

    public static final String FEATURE = "sharesite.ShareToWebsite";
    public static final String CONFIG = "sharesite.ShareToWebsiteConfig";
    static final String CALLS = "relay.RelayCalls";
    public static final String TOKEN = "testkit-sharesite-token";
    static volatile ClientGameTestContext ctx;

    private ShareSiteCases() {
    }

    /** The stub relay: records "METHOD path auth" and each POST body; answers 200 {ok:true}. */
    public static final class Stub implements AutoCloseable {
        public final List<String> calls = new CopyOnWriteArrayList<>();
        public final List<JsonObject> bodies = new CopyOnWriteArrayList<>();
        final HttpServer server;
        final String before;

        public Stub() throws IOException {
            before = System.getProperty("killer560.net.sharesite-relay");
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", ex -> {
                try {
                    String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    calls.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath() + " "
                            + ex.getRequestHeaders().getFirst("Authorization"));
                    if (!body.isBlank()) {
                        bodies.add(JsonParser.parseString(body).getAsJsonObject());
                    }
                    byte[] b = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().add("Content-Type", "application/json");
                    ex.sendResponseHeaders(200, b.length);
                    ex.getResponseBody().write(b);
                } catch (RuntimeException ignored) {
                    // never throw inside the stub
                } finally {
                    ex.close();
                }
            });
            server.start();
            System.setProperty("killer560.net.sharesite-relay", "http://127.0.0.1:" + server.getAddress().getPort());
            Mod.staticCall(FEATURE, "resetForTest");
            Mod.staticCall(CALLS, "setTokenForTest", TOKEN);
            Mod.staticCall(FEATURE, "setNoticeForTest", true); // the notice is answered unless a case says otherwise
        }

        public long count(String prefix) {
            return calls.stream().filter(s -> s.startsWith(prefix)).count();
        }

        @Override
        public void close() {
            if (before == null) System.clearProperty("killer560.net.sharesite-relay");
            else System.setProperty("killer560.net.sharesite-relay", before);
            server.stop(0);
            Mod.staticCall(FEATURE, "resetForTest");
            Mod.staticCall(CALLS, "setTokenForTest", (Object) null);
        }
    }

    public static boolean enabled() {
        return (Boolean) Mod.call(Mod.staticCall(CONFIG, "getInstance"), "isEnabled");
    }

    public static boolean noticeShown() {
        return (Boolean) Mod.call(Mod.staticCall(CONFIG, "getInstance"), "isNoticeShown");
    }

    public static boolean pendingDelete() {
        return (Boolean) Mod.call(Mod.staticCall(CONFIG, "getInstance"), "isPendingDelete");
    }

    static Path file() {
        return (Path) Mod.staticCall("util.ModPaths", "config", "killer560smod-sharesite.json");
    }

    private static String read() {
        try {
            return Files.readString(file());
        } catch (IOException e) {
            return "";
        }
    }

    private static boolean waitFor(BooleanSupplier ok, long ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (ok.getAsBoolean()) {
                return true;
            }
            ctx.waitTicks(2);
        }
        return ok.getAsBoolean();
    }

    static void defaults(LogicCase c) throws Exception {
        Path f = file();
        byte[] saved = Files.exists(f) ? Files.readAllBytes(f) : null;
        try {
            Files.deleteIfExists(f);
            Mod.staticCall(CONFIG, "load");
            c.check("a fresh config is ON (killer560: on by default)", enabled());
            c.check("with no delete waiting", !pendingDelete());
            c.check("a fresh config has not shown the notice (nothing is sent until it has)", !noticeShown());
            c.check("the notice flag is import-locked", String.valueOf(Mod.field("profiles.ProfileManager", "IMPORT_LOCKED_FIELDS")).contains("noticeShown"));
            c.check("loading defaults wrote no file", !Files.exists(f));
            Object locked = Mod.field("profiles.ProfileManager", "IMPORT_LOCKED_FIELDS");
            c.check("someone else's profile can never change it", String.valueOf(locked).contains("killer560smod-sharesite.json"),
                    String.valueOf(locked));
        } finally {
            if (saved != null) Files.write(f, saved);
            else Files.deleteIfExists(f);
            Mod.staticCall(CONFIG, "load");
        }
    }

    static void off(LogicCase c) throws Exception {
        try (Stub stub = new Stub()) {
            ctx.runOnClient(mc -> Mod.staticCall(FEATURE, "setEnabled", true));
            waitFor(() -> !pendingDelete(), 5_000);
            stub.calls.clear();
            ctx.runOnClient(mc -> Mod.staticCall(FEATURE, "onTerminalSolved", "Panes", 4.25));
            c.eq("ON keeps an own terminal solve for the next run", 1, Mod.staticCall(FEATURE, "termsForTest"));

            ctx.runOnClient(mc -> Mod.staticCall(FEATURE, "setEnabled", false));
            c.check("OFF is off", !enabled());
            c.eq("OFF drops the kept solve", 0, Mod.staticCall(FEATURE, "termsForTest"));
            boolean deleted = waitFor(() -> stub.count("DELETE /site/stats Bearer " + TOKEN) == 1, 10_000);
            c.check("OFF sends DELETE /site/stats with the bearer token", deleted, stub.calls.toString());
            c.check("and the confirmed delete clears pendingDelete", waitFor(() -> !pendingDelete(), 5_000));
            // Read the file only once the delete's own save is done (a read during that write saw a partial file).
            c.check("OFF is saved", waitFor(() -> read().contains("\"enabled\": false") && read().contains("\"pendingDelete\": false"), 5_000), read());
            ctx.runOnClient(mc -> Mod.staticCall(FEATURE, "onTerminalSolved", "Panes", 4.25));
            c.eq("while OFF a solve is not kept", 0, Mod.staticCall(FEATURE, "termsForTest"));
            ctx.runOnClient(mc -> Mod.staticCall(FEATURE, "flush"));
            ctx.waitTicks(20);
            c.check("while OFF nothing is uploaded", stub.count("POST") == 0, stub.calls.toString());

            // The relay out of reach when he turns it off: the delete waits, and the next world-join flush sends it.
            ctx.runOnClient(mc -> Mod.staticCall(FEATURE, "setEnabled", true));
            waitFor(() -> !pendingDelete(), 5_000);
            System.setProperty("killer560.net.sharesite-relay", DiscordLinkCases.deadUrl());
            ctx.runOnClient(mc -> Mod.staticCall(FEATURE, "setEnabled", false));
            boolean tried = waitFor(() -> !(Boolean) Mod.field(FEATURE, "inFlight"), 15_000);
            c.check("an unreachable relay leaves the delete waiting", tried && pendingDelete(), "pending " + pendingDelete());
            c.check("and saved for the next start", waitFor(() -> read().contains("\"pendingDelete\": true"), 5_000), read());
            int before = (int) stub.count("DELETE");
            System.setProperty("killer560.net.sharesite-relay", "http://127.0.0.1:" + stub.server.getAddress().getPort());
            ctx.runOnClient(mc -> Mod.staticCall(FEATURE, "flush"));
            c.check("the next flush (a world join) sends it", waitFor(() -> stub.count("DELETE") == before + 1 && !pendingDelete(), 10_000),
                    stub.calls + " pending " + pendingDelete());
        } finally {
            ctx.runOnClient(mc -> Mod.staticCall(FEATURE, "setEnabled", true));
            ctx.waitTicks(10);
        }
    }
}
