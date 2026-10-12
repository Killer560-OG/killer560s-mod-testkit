package dev.testkit.gametest.hx;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.testkit.gametest.mod.Mod;

import java.lang.reflect.Proxy;
import java.net.http.WebSocket;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * 874 (mod branch automeow-channel, 2026-10-12): Auto Meow replies in the channel the meow came from, and the chat
 * features that send into a chosen chat can send into Mod Chat.
 * <ul>
 * <li>party -> /pc, guild -> /gc, officer -> /oc, co-op -> /cc, a private message -> /msg sender, all chat -> /ac, each
 *     reply one of AutoMeowLines;</li>
 * <li>a forged "Party > ..." typed in all chat is answered in /ac, never /pc;</li>
 * <li>his own line (and the echo of his own reply, and his outgoing "To ..." whisper) gets no reply;</li>
 * <li>a Mod Chat meow (relay listener) is answered into Mod Chat - the relay, never Hypixel - and his own relay echo
 *     is not;</li>
 * <li>the Mod Chat fixes from the same audit: {@code /cringe kc}, a typed {@code /kc} through Chat Emotes, Spotify and
 *     Voice To Text set to Mod Chat all reach the relay and never Hypixel.</li>
 * </ul>
 * The relay is offline in tests, so the case puts a recording fake {@link WebSocket} into {@code RelayClient} (state
 * CONNECTED) and reads what the mod sent it.
 */
final class HxAutoMeowChannelCases {

    private static final String AMF = "automeow.AutoMeowFeature";
    private static final String RC = "relay.RelayClient";

    private HxAutoMeowChannelCases() {
    }

    static void register(Session s) {
        s.test("874-hx-automeow-channel", HxAutoMeowChannelCases::channelCase);
    }

    @SuppressWarnings("unchecked")
    private static void channelCase(Session c) {
        List<String> lines = c.onClient(mc -> List.copyOf((List<String>) Mod.field("automeow.AutoMeowLines", "ALL")));
        String self = self(c);
        Object savedState = c.onClient(mc -> Mod.field(RC, "state"));
        Object savedSocket = c.onClient(mc -> Mod.field(RC, "socket"));
        Object savedDest = c.onClient(mc -> Mod.field("spotify.SpotifyLyricsFeature", "chatDestination"));
        Object voiceCfg = c.onClient(mc -> Mod.cfg("voicetotext.VoiceToTextConfig"));
        Object savedVoiceDest = c.onClient(mc -> Mod.call(voiceCfg, "getChatDestination"));
        try (Settings set = new Settings(c).with("automeow.AutoMeowConfig", "Enabled", true)
                .with("automeow.AutoMeowConfig", "PlayCatNoises", false)
                .with("emotes.ChatEmoteConfig", "Enabled", false)
                .with("autocorrect.AutoCorrectConfig", "Enabled", false)
                .with("translate.TranslateConfig", "Enabled", false)
                .with("modchat.ModChatConfig", "Enabled", true)
                .with("modchat.ModChatConfig", "LogToChat", true)) {

            // ---- Hypixel channels: each trigger answered in its own channel ----
            String[][] channels = {
                    {"party", "§9Party §8> §b[MVP§c+§b] HxMateA§f: meow", "pc "},
                    {"guild", "§2Guild > §b[MVP§c+§b] HxMateA §e[Member]§f: purrr", "gc "},
                    {"officer", "§3Officer > §a[VIP] HxMateA §e[Officer]§f: nya", "oc "},
                    {"co-op", "§bCo-op > §a[VIP] HxMateA§f: mew", "cc "},
                    {"private", "§dFrom §b[MVP§c+§b] HxMateB§7: meowww", "msg HxMateB "},
                    {"all", "§7[312] §b[MVP§c+§b] HxMateA§f: meow", "ac "},
            };
            StringBuilder seen = new StringBuilder();
            for (String[] ch : channels) {
                String cmd = trigger(c, ch[1], ch[2]);
                c.check(lines.contains(cmd.substring(ch[2].length())), ch[0] + ": /" + cmd + " is not an AutoMeow line");
                seen.append(ch[0]).append(" -> /").append(cmd).append("; ");
            }

            // ---- forged party line typed in ALL chat: the reply is /ac, never /pc ----
            String forged = trigger(c, "§a[VIP] HxStranger§f: Party > [MVP+] HxMateA: meow", "ac ");
            c.check(lines.contains(forged.substring(3)), "forged: /" + forged + " is not an AutoMeow line");
            seen.append("forged 'Party >' in all chat -> /").append(forged).append("; ");

            // ---- his own lines: nothing (own party line, the echo of his own all-chat reply, his outgoing whisper) ----
            for (String own : List.of("§9Party §8> §b[MVP§c+§b] " + self + "§f: meow",
                    "§7[312] §b[MVP§c+§b] " + self + "§f: purr~",
                    "§dTo §b[MVP§c+§b] HxMateB§7: meow")) {
                long head = resetAndHead(c);
                c.hx().chat(own);
                c.ctx().waitTicks(25);
                List<String> got = chatCommands(c, head);
                c.check(got.isEmpty(), "his own line '" + own + "' got a reply: " + got);
            }
            seen.append("own party / own reply echo / outgoing whisper: no reply; ");

            // ---- Mod Chat: a relay meow is answered in Mod Chat ----
            List<String> relay = new CopyOnWriteArrayList<>();
            Object fake = fakeSocket(relay);
            Object connected = c.onClient(mc -> Mod.enumValue("relay.RelayClient$State", "CONNECTED"));
            long head = resetAndHead(c);
            run(c, () -> {
                Mod.setField(RC, "state", connected);
                Mod.setField(RC, "socket", fake);
                Mod.call(Mod.field(RC, "listener"), "onChat", "HxMateA", "meow");
            });
            String reply = awaitRelay(c, relay, 0, "the Mod Chat meow's reply at the relay");
            c.check(lines.contains(reply), "Mod Chat reply '" + reply + "' is not an AutoMeow line");
            assertNothingToHypixel(c, head, "the Mod Chat meow");
            seen.append("Mod Chat meow -> relay '").append(reply).append("'; ");
            // His own relay echo (the relay sends his lines back to him) is not answered.
            int before = relay.size();
            head = resetAndHead(c);
            run(c, () -> {
                Mod.setField(RC, "state", connected);
                Mod.setField(RC, "socket", fake);
                Mod.call(Mod.field(RC, "listener"), "onChat", self, reply);
                Mod.call(Mod.field(RC, "listener"), "onChat", self, "meow");
            });
            c.ctx().waitTicks(25);
            c.check(relay.size() == before, "his own Mod Chat echo was answered: " + relay.subList(before, relay.size()));
            assertNothingToHypixel(c, head, "his own Mod Chat echo");

            // ---- /cringe kc -> relay ----
            before = relay.size();
            head = c.hx().head();
            run(c, () -> {
                Mod.setField(RC, "state", connected);
                Mod.setField(RC, "socket", fake);
                Mod.staticCall("cringe.CringeFeature", "sendRandom", "kc");
            });
            String cringe = awaitRelay(c, relay, before, "/cringe kc at the relay");
            c.check(!cringe.isBlank(), "/cringe kc sent a blank line");
            assertNothingToHypixel(c, head, "/cringe kc");
            seen.append("/cringe kc -> relay; ");

            // ---- typed /kc goes through Chat Emotes (as /pc does) and to the relay ----
            before = relay.size();
            head = c.hx().head();
            run(c, () -> {
                Mod.set("emotes.ChatEmoteConfig", "setEnabled", true);
                Mod.setField(RC, "state", connected);
                Mod.setField(RC, "socket", fake);
            });
            type(c, "/kc o/ hello o/");
            String typed = awaitRelay(c, relay, before, "the typed /kc at the relay");
            run(c, () -> Mod.set("emotes.ChatEmoteConfig", "setEnabled", false));
            c.check(typed.equals("( ﾟ◡ﾟ)/ hello ( ﾟ◡ﾟ)/"), "typed /kc was not emote-converted: '" + typed + "'");
            assertNothingToHypixel(c, head, "the typed /kc");
            seen.append("typed /kc -> relay '").append(typed).append("'; ");

            // ---- Spotify and Voice To Text set to Mod Chat -> relay ----
            Object modDest = c.onClient(mc -> Mod.enumValue("spotify.ChatDestination", "MOD_CHAT"));
            before = relay.size();
            head = c.hx().head();
            run(c, () -> {
                Mod.setField(RC, "state", connected);
                Mod.setField(RC, "socket", fake);
                Mod.setField("spotify.SpotifyLyricsFeature", "chatDestination", modDest);
                Mod.staticCall("spotify.SpotifyLyricsFeature", "sendToChat", "874 lyric line", false);
            });
            String lyric = awaitRelay(c, relay, before, "the Spotify line at the relay");
            c.check(lyric.equals("874 lyric line"), "Spotify sent '" + lyric + "'");
            before = relay.size();
            run(c, () -> {
                Mod.setField(RC, "state", connected);
                Mod.setField(RC, "socket", fake);
                Mod.call(voiceCfg, "setChatDestination", modDest);
                Mod.staticCall("voicetotext.VoiceToTextFeature", "send", net.minecraft.client.Minecraft.getInstance(),
                        "874 voice line");
            });
            String voice = awaitRelay(c, relay, before, "the Voice To Text line at the relay");
            c.check(voice.equals("874 voice line"), "Voice To Text sent '" + voice + "'");
            assertNothingToHypixel(c, head, "Spotify / Voice To Text to Mod Chat");
            seen.append("Spotify + Voice To Text (Mod Chat) -> relay");

            c.note(seen.toString());
        } finally {
            run(c, () -> {
                Mod.setField(RC, "socket", savedSocket);
                Mod.setField(RC, "state", savedState);
                Mod.setField("spotify.SpotifyLyricsFeature", "chatDestination", savedDest);
                Mod.call(voiceCfg, "setChatDestination", savedVoiceDest);
                Mod.setField(AMF, "lastReplyAtMs", 0L);
            });
        }
    }

    /** Clears Auto Meow's cooldown, sends {@code line} as Hypixel chat, and returns the one reply command. */
    private static String trigger(Session c, String line, String prefix) {
        long head = resetAndHead(c);
        c.hx().chat(line);
        c.waitUntil("a /" + prefix + "... reply to '" + line + "'",
                mc -> chatCommands(c, head).stream().anyMatch(x -> x.startsWith(prefix)), 60);
        c.ctx().waitTicks(5);
        List<String> got = chatCommands(c, head);
        c.check(got.size() == 1, "'" + line + "' got " + got.size() + " commands, want exactly one: " + got);
        return got.get(0);
    }

    /** Commands at the server since {@code head} that post into a chat (the only kind Auto Meow may send). */
    private static List<String> chatCommands(Session c, long head) {
        return c.hx().commands(head).stream().filter(x -> x.matches("(?i)^(pc|gc|oc|cc|ac|kc|msg|w|r|tell|chat)( .*)?$")).toList();
    }

    private static long resetAndHead(Session c) {
        run(c, () -> Mod.setField(AMF, "lastReplyAtMs", 0L));
        return c.hx().head();
    }

    private static void assertNothingToHypixel(Session c, long head, String what) {
        c.ctx().waitTicks(10);
        List<String> got = chatCommands(c, head);
        c.check(got.isEmpty(), what + " sent Hypixel commands: " + got);
    }

    /** The chat message of the first relay packet from index {@code from} on. */
    private static String awaitRelay(Session c, List<String> relay, int from, String what) {
        c.waitUntil(what, mc -> relay.size() > from, 60);
        JsonObject packet = JsonParser.parseString(relay.get(from)).getAsJsonObject();
        c.check("chat".equals(packet.get("type").getAsString()), what + ": not a chat packet: " + packet);
        return packet.get("message").getAsString();
    }

    /** A {@link WebSocket} that records every text frame and completes at once. */
    private static Object fakeSocket(List<String> sink) {
        Object[] self = new Object[1];
        self[0] = Proxy.newProxyInstance(WebSocket.class.getClassLoader(), new Class<?>[]{WebSocket.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "sendText" -> {
                        sink.add(args[0].toString());
                        yield CompletableFuture.completedFuture(proxy);
                    }
                    case "sendPing", "sendPong", "sendClose", "sendBinary" -> CompletableFuture.completedFuture(proxy);
                    case "getSubprotocol" -> "";
                    case "isOutputClosed", "isInputClosed" -> false;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "FakeRelaySocket(874)";
                    default -> null;
                });
        return self[0];
    }
}
