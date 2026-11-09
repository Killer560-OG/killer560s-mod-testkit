package dev.testkit.gametest.logic;

import com.sun.net.httpserver.HttpServer;
import dev.testkit.gametest.mod.Mod;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.AbstractWidget;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

/**
 * 1105-discordlink-*: Discord Link (mod discordlink-1105, killer560 2026-11-05: "for them to link their discord account so it
 * lets me know their discord as well"). The relay ({@code /discord-link/start|status|unlink}) is an in-process stub on
 * {@code killer560.net.discordlink-relay}; the bearer token is a test one ({@code RelayCalls.setTokenForTest}, gametest only).
 * Every step asserts what the STUB received and what the card shows, so a flow that never called the relay cannot pass.
 *
 * <pre>
 * 1105-discordlink-card     the Home card before / with a code / linked / unlinked: its rows and words; the code arrives
 *                           from POST /discord-link/start with the token, the mod's own status poll turns it to Linked
 *                           once the code is used in Discord, Unlink posts /discord-link/unlink
 * 1105-discordlink-offline  a relay that cannot be reached: the card says try again later, nothing hangs
 * 1105-discordlink-payload  (CrashReportCases) a crash report carries shareDiscord only with Include My Minecraft Name AND
 *                           Include My Discord on; both default ON
 * </pre>
 */
public final class DiscordLinkCases {

    static final String LINK = "discordlink.DiscordLink";
    static final String CALLS = "relay.RelayCalls";
    static final String TAB = "gui.tab.DiscordLinkTab";
    public static final String TOKEN = "testkit-discordlink-token";
    public static final String CODE = "ABCD2345";
    public static final String DISCORD = "dave_discord";
    static volatile ClientGameTestContext ctx;

    private DiscordLinkCases() {
    }

    /** The stub relay: records "METHOD path auth" per call; status answers linked once {@link #redeemed} is set. */
    public static final class Stub implements AutoCloseable {
        public final List<String> calls = new CopyOnWriteArrayList<>();
        public volatile boolean redeemed;
        final HttpServer server;

        public Stub() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", ex -> {
                try {
                    ex.getRequestBody().readAllBytes();
                    String path = ex.getRequestURI().getPath();
                    calls.add(ex.getRequestMethod() + " " + path + " " + ex.getRequestHeaders().getFirst("Authorization"));
                    String linked = redeemed ? "{\"discordId\":\"123456789012345678\",\"discordName\":\"" + DISCORD
                            + "\",\"linkedAt\":1}" : "null";
                    String out = switch (path) {
                        case "/discord-link/start" -> "{\"code\":\"" + CODE + "\",\"expiresAt\":"
                                + (System.currentTimeMillis() + 600_000) + ",\"linked\":" + linked + "}";
                        case "/discord-link/status" -> "{\"linked\":" + linked + ",\"codeExpiresAt\":null}";
                        case "/discord-link/unlink" -> {
                            redeemed = false;
                            yield "{\"ok\":true,\"removed\":true}";
                        }
                        default -> "{\"error\":\"not found\"}";
                    };
                    byte[] b = out.getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().add("Content-Type", "application/json");
                    ex.sendResponseHeaders(out.startsWith("{\"error") ? 404 : 200, b.length);
                    ex.getResponseBody().write(b);
                } catch (RuntimeException ignored) {
                    // never throw inside the stub
                } finally {
                    ex.close();
                }
            });
            server.start();
            System.setProperty("killer560.net.discordlink-relay", "http://127.0.0.1:" + server.getAddress().getPort());
            reset();
        }

        @Override
        public void close() {
            System.clearProperty("killer560.net.discordlink-relay");
            server.stop(0);
            Mod.staticCall(LINK, "resetForTest");
            Mod.staticCall(CALLS, "setTokenForTest", (Object) null);
        }
    }

    /** Link state back to "not checked", the test token set. */
    public static void reset() {
        Mod.staticCall(LINK, "resetForTest");
        Mod.staticCall(CALLS, "setTokenForTest", TOKEN);
    }

    /** A loopback URL nothing listens on. */
    public static String deadUrl() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return "http://127.0.0.1:" + s.getLocalPort();
        }
    }

    /** A DiscordLink action, pressed as a button would be: on the client thread. */
    private static void onClient(String cls, String method) {
        ctx.runOnClient(mc -> Mod.staticCall(cls, method));
    }

    public static String state() {
        return String.valueOf(Mod.staticCall(LINK, "state"));
    }

    private static boolean waitFor(BooleanSupplier ok, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (ok.getAsBoolean()) {
                return true;
            }
            ctx.waitTicks(2); // never sleep: the client only runs while the test thread waits ticks
        }
        return ok.getAsBoolean();
    }

    /** The card's widgets' text (buttons and lines), built on the client thread. */
    private static List<String> card() {
        return ctx.computeOnClient(mc -> {
            Object tab;
            try {
                var k = Mod.cls(TAB).getDeclaredConstructor();
                k.setAccessible(true);
                tab = k.newInstance();
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
            List<String> out = new ArrayList<>();
            for (Object w : (List<?>) Mod.call(tab, "buildWidgets", 0, 0, 320, (Runnable) () -> { })) {
                out.add(((AbstractWidget) w).getMessage().getString());
            }
            return out;
        });
    }

    private static boolean has(List<String> card, String text) {
        // The card wraps long lines over several widgets and colors words: match the text as read.
        return String.join(" ", card).replaceAll("§.", "").replaceAll("\\s+", " ").contains(text);
    }

    static void card(LogicCase c) throws Exception {
        try (Stub stub = new Stub()) {
            List<String> before = card();
            c.check("before anything the card offers Link Discord", before.contains("Link Discord"), before.toString());
            c.check("and Check Status, but no Unlink", before.contains("Check Status") && !before.contains("Unlink"),
                    before.toString());
            c.eq("building the card in a test asked the relay nothing", 0, stub.calls.size());

            onClient(LINK, "start");
            boolean got = waitFor(() -> state().equals("CODE"), 10_000);
            if (!c.check("Link Discord gets a one-time code", got, "state " + state() + ", error "
                    + Mod.staticCall(LINK, "error") + ", calls " + stub.calls)) {
                return;
            }
            c.eq("from POST /discord-link/start with the bearer token", "POST /discord-link/start Bearer " + TOKEN,
                    stub.calls.get(0));
            c.eq("the code the relay gave", CODE, Mod.staticCall(LINK, "code"));
            List<String> withCode = card();
            c.check("the card tells him what to type", has(withCode, "/link code:" + CODE) && has(withCode, "killer560's Discord"),
                    withCode.toString());
            c.check("with Copy /link Command and New Code", withCode.contains("Copy /link Command")
                    && withCode.contains("New Code"), withCode.toString());

            // He types /link code:... in Discord; the mod's own poll (every 8 s) sees it.
            stub.redeemed = true;
            boolean linked = waitFor(() -> state().equals("LINKED"), 20_000);
            c.check("the status poll turns the card to Linked by itself", linked, "state " + state() + ", calls " + stub.calls);
            c.check("by asking GET /discord-link/status", stub.calls.stream().anyMatch(s -> s.startsWith("GET /discord-link/status")),
                    stub.calls.toString());
            c.eq("named with his Discord", DISCORD, Mod.staticCall(LINK, "discordName"));
            List<String> linkedCard = card();
            c.check("the card shows the Discord, Unlink and Link a Different Discord", has(linkedCard, "Linked to " + DISCORD)
                    && linkedCard.contains("Unlink") && linkedCard.contains("Link a Different Discord"), linkedCard.toString());
            int pollsAtLink = (int) stub.calls.stream().filter(s -> s.startsWith("GET")).count();
            waitFor(() -> false, 9_000);
            c.eq("once linked the poll stops", pollsAtLink, (int) stub.calls.stream().filter(s -> s.startsWith("GET")).count());

            onClient(LINK, "unlink");
            c.check("Unlink deletes the link", waitFor(() -> state().equals("NOT_LINKED"), 10_000), "state " + state());
            c.check("with POST /discord-link/unlink", stub.calls.contains("POST /discord-link/unlink Bearer " + TOKEN),
                    stub.calls.toString());
            List<String> after = card();
            c.check("and the card is back to Link Discord, no Unlink", after.contains("Link Discord")
                    && !after.contains("Unlink") && has(after, "Not linked"), after.toString());
        }
    }

    static void offline(LogicCase c) throws Exception {
        try (Stub stub = new Stub()) {
            System.setProperty("killer560.net.discordlink-relay", deadUrl());
            long t0 = System.currentTimeMillis();
            onClient(LINK, "start");
            boolean failed = waitFor(() -> state().equals("ERROR"), 20_000);
            c.check("a relay that cannot be reached ends in an error, not a hang", failed, "state " + state());
            c.note("failed after " + (System.currentTimeMillis() - t0) + " ms: " + Mod.staticCall(LINK, "error"));
            c.eq("in plain words, not a socket message", "couldn't reach the relay", Mod.staticCall(LINK, "error"));
            List<String> card = card();
            c.check("the card says try again later", has(card, "Try again later"), card.toString());
            c.check("and still offers Link Discord", card.contains("Link Discord"), card.toString());
            c.eq("the stub (not listening there) was never reached", 0, stub.calls.size());
        }
    }
}
