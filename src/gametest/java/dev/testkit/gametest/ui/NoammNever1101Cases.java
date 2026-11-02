package dev.testkit.gametest.ui;

import dev.testkit.gametest.mod.Mod;

import java.lang.reflect.Constructor;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 1101-noamm-never: killer560s-mod never opens or signs into NoammAddons' party socket (its owner asked us on
 * 2026-11-01: "definitely dont manually auth into it"). Mod branch noamm-apikey-1101.
 * <ol>
 * <li>The NoammAddons adapter and codec are gone from the jar.</li>
 * <li>{@code ModNet.forbidden} refuses {@code wss://ws.noamm.org} and {@code https://api.noamm.org/hypixel/auth} as
 * "never contacted" (not the offline refusal every testkit run has), through both the WebSocket and HTTP helpers.</li>
 * <li>The bridge, put in a Hypixel-shaped F7 P3 run with every switch on through {@code BridgeFeature.setContextOverrideForTest},
 * tries Odin's socket (the control: proves the adapters ran and {@code ModNet.attemptsForTest} sees their attempts) and
 * never anything on a noamm.org host.</li>
 * </ol>
 * The run is offline ({@code killer560.net.offline}), so the Odin attempt is refused before the network: nothing is sent.
 */
final class NoammNever1101Cases {

    private NoammNever1101Cases() {
    }

    @SuppressWarnings("unchecked")
    private static List<String> attempts() {
        return (List<String>) Mod.staticCall("util.ModNet", "attemptsForTest");
    }

    static void noammNever(UiCase c) throws Exception {
        // 1. no NoammAddons adapter or codec in the jar
        c.check(!Mod.has("bridge.NoammAdapter"), "bridge.NoammAdapter is still in the jar");
        c.check(!Mod.has("bridge.NoammCodec"), "bridge.NoammCodec is still in the jar");

        // 2. the hard refusal, independent of the offline switch
        int refusedBefore = (Integer) Mod.staticCall("util.ModNet", "forbiddenRefusalsForTest");
        Mod.staticCall("util.ModNet", "clearAttemptsForTest");
        Class<?> net = Mod.cls("util.ModNet");
        HttpClient http = HttpClient.newHttpClient();
        URI socket = URI.create("wss://ws.noamm.org?name=TestPlayer&token=x");
        CompletableFuture<WebSocket> ws = (CompletableFuture<WebSocket>) net
                .getMethod("webSocket", WebSocket.Builder.class, URI.class, WebSocket.Listener.class)
                .invoke(null, http.newWebSocketBuilder(), socket, new WebSocket.Listener() { });
        String wsError = null;
        try {
            ws.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            wsError = String.valueOf(e.getCause() == null ? e : e.getCause());
        }
        c.check(wsError != null && wsError.contains("never contacted"),
                "wss://ws.noamm.org was not refused as never-contacted: " + wsError);
        String authError = null;
        try {
            net.getMethod("send", HttpClient.class, HttpRequest.class, HttpResponse.BodyHandler.class).invoke(null, http,
                    HttpRequest.newBuilder(URI.create("https://api.noamm.org/hypixel/auth"))
                            .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                    HttpResponse.BodyHandlers.discarding());
        } catch (java.lang.reflect.InvocationTargetException e) {
            authError = String.valueOf(e.getCause());
        }
        c.check(authError != null && authError.contains("never contacted"),
                "api.noamm.org/hypixel/auth was not refused as never-contacted: " + authError);
        int refusedAfter = (Integer) Mod.staticCall("util.ModNet", "forbiddenRefusalsForTest");
        c.check(refusedAfter - refusedBefore == 2, "expected 2 never-contacted refusals, counted " + (refusedAfter - refusedBefore));
        List<String> direct = attempts();
        c.check(direct.contains("wss://ws.noamm.org") && direct.contains("https://api.noamm.org/hypixel/auth"),
                "the attempt log did not record the two refused targets: " + direct);
        c.note("refused as never-contacted: " + wsError + " | " + authError);

        // 3. the bridge in a Hypixel-shaped F7 P3 run, every switch on
        Mod.staticCall("util.ModNet", "clearAttemptsForTest");
        try (AutoCloseable a = Mod.with("bridge.BridgeConfig", "Enabled", true);
             AutoCloseable b = Mod.with("bridge.BridgeConfig", "Devonian", true);
             AutoCloseable o = Mod.with("bridge.BridgeConfig", "Odin", true);
             AutoCloseable i = Mod.with("interop.InteropConfig", "Enabled", true)) {
            Class<?> ctxCls = Mod.cls("bridge.BridgeContext");
            Constructor<?> k = ctxCls.getDeclaredConstructors()[0];
            Object ctx = k.newInstance(true, true, true, "F7", "TestPlayer", UUID.randomUUID(),
                    List.of("Mate_A", "Mate_B", "Mate_C"), null, "m123AB", List.of(), true, 1);
            Mod.staticCall("bridge.BridgeFeature", "setContextOverrideForTest", ctx);
            List<String> seen = List.of();
            for (int t = 0; t < 50; t++) { // up to 10 s: the bridge ticks every 5 ticks, its worker pumps every 250 ms
                c.ticks(4);
                seen = attempts();
                if (seen.stream().anyMatch(s -> s.contains("ws.odtheking.com"))) {
                    c.ticks(40); // two more seconds, so a socket opened after Odin's would be seen too
                    seen = attempts();
                    break;
                }
            }
            List<String> fin = seen;
            c.check(fin.stream().anyMatch(s -> s.startsWith("wss://ws.odtheking.com/")),
                    "control failed: the bridge never tried Odin's socket, so this run proves nothing: " + fin);
            c.check(fin.stream().noneMatch(s -> s.contains("noamm")), "the bridge tried a noamm host: " + fin);
            c.note("bridge attempts in a Hypixel-shaped F7 P3 run: " + fin);
        } finally {
            Mod.staticCall("bridge.BridgeFeature", "setContextOverrideForTest", (Object) null);
            Mod.staticCall("bridge.BridgeFeature", "onSettingsChanged");
        }
    }
}
