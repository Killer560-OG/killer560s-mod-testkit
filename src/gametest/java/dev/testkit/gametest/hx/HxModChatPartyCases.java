package dev.testkit.gametest.hx;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.PacketTrace;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Mod Chat and Party Commands (mod branch modchat-party, 2026-10-08), cases 571-580.
 * <ul>
 * <li>571 received Mod Chat prints in chat by default, also for an old config that saved it OFF (once);</li>
 * <li>572 presence notices and their setting are gone;</li>
 * <li>573 {@code /chat k}, {@code /kc} and the Mod Chat channel: Hypixel never receives them, {@code /chat p} still goes;</li>
 * <li>574 {@code /killer560 chat} is gone and {@code /kc} parses;</li>
 * <li>575 the Party Commands tab is exactly three sections;</li>
 * <li>576 an old Party Commands + Chat Commands config migrates to the one list;</li>
 * <li>577 a reply leaves at least the reply delay after the line that asked for it;</li>
 * <li>578 the chats a command may come from (all chat, Mod Chat) and the teammate gate across them;</li>
 * <li>579 an in-game account swap signs the relay login with the NEW account's chat key (the alt-account bug);</li>
 * <li>580 the removed options (destructive switch, confirm invites, the joke command, Chat Commands) are gone.</li>
 * </ul>
 */
final class HxModChatPartyCases {

    private static final String MC = "modchat.ModChatConfig";
    private static final String CHANNEL = "modchat.ModChatChannel";
    private static final String PCC = "partycommands.PartyCommandsConfig";
    private static final String PCF = "partycommands.PartyCommandsFeature";
    /** The fake Mojang services + relay of case 579 (inside this machine's 28900-28999 block). */
    private static final int FAKE_PORT = 28950;

    private HxModChatPartyCases() {
    }

    static void register(Session s) {
        receiveDefault(s);
        noPresence(s);
        chatChannel(s);
        killer560ChatGone(s);
        partyTab(s);
        partyMigration(s);
        replyDelay(s);
        partyChannels(s);
        accountSwapKey(s);
        removedItems(s);
    }

    // ---- 571 ------------------------------------------------------------------------------------------------------

    private static void receiveDefault(Session s) {
        s.test("571-hx-mcp-receive-default", c -> {
            Path file = c.onClient(mc -> (Path) Mod.staticCall("util.ModPaths", "config", "killer560smod-modchat.json"));
            byte[] saved = readOrNull(file);
            try {
                // A fresh install: Log To Chat (received lines in chat) is ON.
                c.onClient(mc -> {
                    delete(file);
                    Mod.staticCall(MC, "load");
                    return null;
                });
                c.check(logToChat(c), "fresh install: Log To Chat is not ON");
                // A file from before the change saved logToChat=false only because that was the old default: ON once.
                write(file, "{\"enabled\":true,\"roomMode\":\"PARTY\",\"logToChat\":false,\"presenceAlerts\":true}");
                c.onClient(mc -> Mod.staticCall(MC, "load"));
                c.check(logToChat(c), "an old config saved with logToChat=false did not switch ON");
                String rewritten = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                c.check(rewritten.contains("receiveDefaultV2") && rewritten.contains("\"logToChat\": true"),
                        "the one-time switch was not written back: " + rewritten);
                c.check(!rewritten.contains("presenceAlerts"), "presenceAlerts was written back: " + rewritten);
                // After that, an explicit OFF is kept.
                c.onClient(mc -> {
                    Object cfg = Mod.cfg(MC);
                    Mod.call(cfg, "setLogToChat", false);
                    Mod.call(cfg, "save");
                    Mod.staticCall(MC, "load");
                    return null;
                });
                c.check(!logToChat(c), "an explicit Log To Chat OFF did not survive a reload");
            } finally {
                restore(file, saved);
                c.onClient(mc -> Mod.staticCall(MC, "load"));
            }
            // Real behaviour: a line from the relay shows in chat.
            try (Settings set = new Settings(c).with(MC, "Enabled", true).with(MC, "LogToChat", true)) {
                hub(c);
                long mark = LogTap.mark();
                run(c, () -> Mod.call(Mod.field("relay.RelayClient", "listener"), "onChat", "HxFriend", "hello from the relay 571"));
                c.waitUntil("the relay line in chat", mc -> LogTap.since(mark).stream()
                        .anyMatch(l -> l.contains("[CHAT]") && l.contains("HxFriend: hello from the relay 571")), 60);
                c.note("fresh/old configs receive into chat; explicit OFF kept; a relay line printed in chat");
            }
        });
    }

    private static boolean logToChat(Session c) {
        return c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(MC), "isLogToChat"));
    }

    // ---- 572 ------------------------------------------------------------------------------------------------------

    private static void noPresence(Session s) {
        s.test("572-hx-mcp-no-presence", c -> {
            List<String> presenceMethods = c.onClient(mc -> Arrays.stream(Mod.cls(MC).getMethods())
                    .map(java.lang.reflect.Method::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).contains("presence")).toList());
            c.check(presenceMethods.isEmpty(), "ModChatConfig still has " + presenceMethods);
            try (Settings set = new Settings(c).with(MC, "Enabled", true).with(MC, "LogToChat", true)) {
                List<String> rows = c.onClient(mc -> labels(buildTab("gui.tab.ModChatTab")));
                c.check(rows.stream().anyMatch(l -> l.startsWith("Log To Chat")), "premise: the Mod Chat tab built no rows " + rows);
                c.check(rows.stream().noneMatch(l -> l.toLowerCase(Locale.ROOT).contains("presence")),
                        "the Mod Chat tab still has a presence row: " + rows);
                hub(c);
                String self = self(c);
                long mark = LogTap.mark();
                run(c, () -> {
                    Object listener = Mod.field("relay.RelayClient", "listener");
                    // Through the RelayListener interface: Mod Chat no longer overrides these, so the call reaches
                    // whatever the listener does with them now (the interface's no-op defaults).
                    relayListener(listener, "onConnected", "party:hx572", List.of(self, "HxFriend"));
                    relayListener(listener, "onPresence", "join", "HxFriend", List.of(self, "HxFriend"));
                    relayListener(listener, "onPresence", "leave", "HxFriend", List.of(self));
                    // Positive control after them: the same listener, same client-thread hop, does print a chat line.
                    Mod.call(listener, "onChat", "HxFriend", "572 control line");
                });
                c.waitUntil("the control line in chat (the listener ran)", mc -> LogTap.since(mark).stream()
                        .anyMatch(l -> l.contains("HxFriend: 572 control line")), 60);
                List<String> notices = LogTap.since(mark).stream().filter(l -> l.contains("joined mod chat")
                        || l.contains("left mod chat") || l.contains("other mod user")).toList();
                c.check(notices.isEmpty(), "presence notices printed: " + notices);
                String overlay = c.onClient(mc -> (String) Mod.staticCall("notify.ModOverlayMessage", "current"));
                c.check(overlay == null || !(overlay.contains("joined") || overlay.contains("left")
                        || overlay.contains("Connected")), "presence overlay shown: " + overlay);
                c.note("no presence methods, no tab row; join/leave/connected printed nothing, control line printed");
            }
        });
    }

    // ---- 573 ------------------------------------------------------------------------------------------------------

    private static void chatChannel(Session s) {
        s.test("573-hx-mcp-chat-channel", c -> {
            try (Settings set = new Settings(c).with(MC, "Enabled", true).with(MC, "LogToChat", true)) {
                hub(c);
                c.hx().stub(null, "chat");
                run(c, () -> Mod.staticCall(CHANNEL, "setActive", false));
                long mark = LogTap.mark();

                type(c, "/chat k");
                c.ctx().waitTicks(10);
                c.check(active(c), "/chat k did not switch to the Mod Chat channel");
                c.check(LogTap.since(mark).stream().anyMatch(l -> l.contains("You are now in the MOD CHAT channel")),
                        "no 'You are now in the MOD CHAT channel' line");

                type(c, "hx573 private line");
                c.ctx().waitTicks(20);
                c.check(chatTexts(c).stream().noneMatch(t -> t.contains("hx573 private line")),
                        "a line typed in the Mod Chat channel reached the server: " + chatTexts(c));
                c.check(LogTap.since(mark).stream().anyMatch(l -> l.contains("nothing was sent")),
                        "premise: the relay is offline here, so the line must be refused locally in red");

                type(c, "/kc hx573 kc line");
                c.ctx().waitTicks(10);

                type(c, "/chat p");
                String toParty = awaitCommand(c, "chat", 60);
                c.check(toParty.equals("chat p"), "/chat p reached the server as /" + toParty);
                c.check(!active(c), "/chat p did not leave the Mod Chat channel");

                type(c, "hx573 public line");
                c.waitUntil("the plain line after /chat p reached the server",
                        mc -> chatTexts(c).stream().anyMatch(t -> t.contains("hx573 public line")), 60);

                type(c, "/kc");
                c.ctx().waitTicks(5);
                c.check(active(c), "bare /kc did not switch to the Mod Chat channel");
                type(c, "/chat a");
                c.waitUntil("/chat a at the server", mc -> c.commands().contains("chat a"), 60);
                c.check(!active(c), "/chat a did not leave the Mod Chat channel");

                List<String> cmds = c.commands();
                c.check(cmds.stream().noneMatch(x -> x.equalsIgnoreCase("chat k") || x.startsWith("kc")),
                        "the server received a Mod Chat command: " + cmds);
                c.check(chatTexts(c).stream().noneMatch(t -> t.contains("hx573 kc line") || t.contains("hx573 private")),
                        "Mod Chat text reached the server: " + chatTexts(c));
                c.note("server commands " + cmds + "; chat " + chatTexts(c));
            } finally {
                run(c, () -> Mod.staticCall(CHANNEL, "setActive", false));
            }
        });
    }

    private static boolean active(Session c) {
        return c.onClient(mc -> (Boolean) Mod.staticCall(CHANNEL, "isActive"));
    }

    private static List<String> chatTexts(Session c) {
        List<String> out = new ArrayList<>();
        for (JsonObject e : c.events("chat")) {
            out.add(e.has("text") ? e.get("text").getAsString() : "");
        }
        return out;
    }

    // ---- 574 ------------------------------------------------------------------------------------------------------

    private static void killer560ChatGone(Session s) {
        s.test("574-hx-mcp-killer560-chat-gone", c -> {
            List<String> problems = c.onClient(mc -> {
                List<String> out = new ArrayList<>();
                try {
                    Class<?> ccm = Class.forName("net.fabricmc.fabric.impl.command.client.ClientCommandInternals");
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    com.mojang.brigadier.CommandDispatcher<Object> d =
                            (com.mojang.brigadier.CommandDispatcher) ccm.getMethod("getActiveDispatcher").invoke(null);
                    Object src = mc.player.connection.getSuggestionsProvider();
                    var root = d.getRoot().getChild("killer560");
                    if (root == null) {
                        out.add("premise: no /killer560 root at all");
                    } else if (root.getChild("chat") != null) {
                        out.add("/killer560 still has a 'chat' child");
                    }
                    var gone = d.parse("killer560 chat hello", src);
                    if (!gone.getReader().canRead() && gone.getContext().getCommand() != null && gone.getExceptions().isEmpty()) {
                        out.add("'/killer560 chat hello' still parses to a command");
                    }
                    var kc = d.parse("kc hello there", src);
                    if (kc.getReader().canRead() || kc.getContext().getCommand() == null || !kc.getExceptions().isEmpty()) {
                        out.add("'/kc hello there' does not parse");
                    }
                    var bare = d.parse("kc", src);
                    if (bare.getReader().canRead() || bare.getContext().getCommand() == null) {
                        out.add("bare '/kc' does not parse");
                    }
                    if (d.getRoot().getChild("chat") != null) {
                        out.add("'chat' is registered client-side - it would swallow Hypixel's /chat");
                    }
                } catch (ReflectiveOperationException e) {
                    out.add("no client dispatcher: " + e);
                }
                return out;
            });
            c.check(problems.isEmpty(), String.join("; ", problems));
            c.note("/killer560 chat gone, /kc <message> and /kc parse, /chat not client-registered");
        });
    }

    // ---- 575 ------------------------------------------------------------------------------------------------------

    private static void partyTab(Session s) {
        s.test("575-hx-mcp-party-tab", c -> {
            try (Settings set = new Settings(c).with(PCC, "Enabled", true).with(PCC, "ReplyDelayMs", 200)) {
                List<String> problems = new ArrayList<>();
                List<String> headers = new ArrayList<>();
                List<String> rows = new ArrayList<>();
                List<String> untipped = new ArrayList<>();
                c.onClient(mc -> {
                    for (AbstractWidget w : buildTab("gui.tab.PartyCommandsTab")) {
                        String text = plain(w.getMessage().getString());
                        if (w instanceof StringWidget) {
                            headers.add(text);
                        } else {
                            rows.add(text);
                            Object tip = Mod.staticCall("gui.SettingTooltips", "describe", "Party Commands", text);
                            if (tip == null || tip.toString().isBlank()) {
                                untipped.add(text);
                            }
                        }
                    }
                    return null;
                });
                Object[] channels = c.onClient(mc -> Mod.cls(PCC + "$Channel").getEnumConstants());
                Object[] commands = c.onClient(mc -> Mod.cls(PCC + "$Command").getEnumConstants());
                if (!headers.equals(List.of("Chats", "Commands"))) {
                    problems.add("section headers " + headers + ", want [Chats, Commands]");
                }
                if (rows.isEmpty() || !rows.get(0).startsWith("Party Commands: ")) {
                    problems.add("first row is not the general toggle: " + (rows.isEmpty() ? "none" : rows.get(0)));
                }
                if (rows.size() < 2 || !rows.get(1).equals("Reply Delay: 200 ms")) {
                    problems.add("second row is not 'Reply Delay: 200 ms': " + (rows.size() < 2 ? "none" : rows.get(1)));
                }
                int want = 2 + channels.length + commands.length;
                if (rows.size() != want) {
                    problems.add(rows.size() + " rows, want " + want + " (toggle, delay, " + channels.length + " chats, "
                            + commands.length + " commands)");
                }
                for (Object ch : channels) {
                    String label = c.onClient(mc -> (String) Mod.call(ch, "label"));
                    if (rows.stream().noneMatch(r -> r.startsWith(label + ": "))) {
                        problems.add("no chat row for " + label);
                    }
                }
                for (String must : List.of("Party (pc)", "Guild (gc)", "All Chat (ac)", "Mod Chat (kc)")) {
                    if (rows.stream().noneMatch(r -> r.startsWith(must + ": "))) {
                        problems.add("no '" + must + "' row");
                    }
                }
                for (Object cmd : commands) {
                    String label = c.onClient(mc -> (String) Mod.call(cmd, "label"));
                    if (rows.stream().noneMatch(r -> r.startsWith(label + ": "))) {
                        problems.add("no command row for " + label);
                    }
                }
                for (String gone : List.of("destructive", "confirm", "racism", "joke", "info commands", "party management")) {
                    for (String r : rows) {
                        if (r.toLowerCase(Locale.ROOT).contains(gone)) {
                            problems.add("removed row still there: " + r);
                        }
                    }
                }
                if (!untipped.isEmpty()) {
                    problems.add("rows without a tooltip: " + untipped);
                }
                // Master off: only the toggle.
                c.onClient(mc -> {
                    Mod.call(Mod.cfg(PCC), "setEnabled", false);
                    return null;
                });
                int offRows = c.onClient(mc -> buildTab("gui.tab.PartyCommandsTab").size());
                if (offRows != 1) {
                    problems.add("with the feature off the tab shows " + offRows + " widgets, want 1");
                }
                c.check(problems.isEmpty(), String.join("; ", problems));
                c.note("headers " + headers + "; " + rows.size() + " rows: " + rows);
            }
        });
    }

    // ---- 576 ------------------------------------------------------------------------------------------------------

    private static void partyMigration(Session s) {
        s.test("576-hx-mcp-party-migration", c -> {
            Path party = c.onClient(mc -> (Path) Mod.staticCall("util.ModPaths", "config", "killer560smod-partycommands.json"));
            Path chat = c.onClient(mc -> (Path) Mod.staticCall("util.ModPaths", "config", "killer560smod-chatcommands.json"));
            byte[] savedParty = readOrNull(party);
            byte[] savedChat = readOrNull(chat);
            List<String> problems = new ArrayList<>();
            try {
                // A: destructive switch OFF - the destructive toggles that never ran stay off; everything else carries.
                write(party, "{\"enabled\":true,\"allowDestructive\":false,\"confirmInvites\":true,\"cmd.warp\":true,"
                        + "\"cmd.kick\":true,\"cmd.boop\":true,\"cmd.invite\":true,\"cmd.racism\":true,\"cmd.help\":false}");
                write(chat, "{\"enabled\":false,\"partyEnabled\":true,\"guildEnabled\":true,\"privateEnabled\":false,"
                        + "\"coopEnabled\":true,\"cmd.coords\":false,\"cmd.tps\":true}");
                Map<String, Object> a = loadState(c);
                expect(problems, "A", a, Map.ofEntries(Map.entry("enabled", true), Map.entry("WARP", false),
                        Map.entry("KICK", false), Map.entry("BOOP", true), Map.entry("INVITE", true), Map.entry("HELP", false),
                        Map.entry("COORDS", false), Map.entry("TPS", true), Map.entry("PING", true),
                        Map.entry("ch.PARTY", true), Map.entry("ch.GUILD", true), Map.entry("ch.PRIVATE", false),
                        Map.entry("ch.COOP", true), Map.entry("ch.ALL", false), Map.entry("ch.MOD_CHAT", true),
                        Map.entry("delay", 200)));
                String rewritten = new String(Files.readAllBytes(party), StandardCharsets.UTF_8);
                for (String gone : List.of("racism", "confirmInvites", "allowDestructive")) {
                    if (rewritten.contains(gone)) {
                        problems.add("A: rewritten file still has " + gone);
                    }
                }
                if (!rewritten.contains("\"version\": 2")) {
                    problems.add("A: rewritten file has no version 2: " + rewritten);
                }
                // A again: a version-2 file is read as it is, never migrated twice.
                c.onClient(mc -> {
                    Object cfg = Mod.cfg(PCC);
                    Mod.call(cfg, "setOn", Mod.enumValue(PCC + "$Command", "WARP"), true);
                    Mod.call(cfg, "save");
                    return null;
                });
                Map<String, Object> a2 = loadState(c);
                expect(problems, "A reloaded", a2, Map.of("WARP", true, "KICK", false, "COORDS", false));

                // B: destructive switch ON - those toggles keep their own value.
                write(party, "{\"enabled\":true,\"allowDestructive\":true,\"cmd.warp\":true,\"cmd.kick\":true,\"cmd.demote\":false}");
                delete(chat);
                Map<String, Object> b = loadState(c);
                expect(problems, "B", b, Map.of("enabled", true, "WARP", true, "KICK", true, "DEMOTE", false,
                        "COORDS", true, "ch.PARTY", true, "ch.PRIVATE", true, "ch.GUILD", false));

                // C: only an old Chat Commands file, which was on: the master follows it.
                delete(party);
                write(chat, "{\"enabled\":true,\"partyEnabled\":false,\"guildEnabled\":false,\"cmd.dice\":false}");
                Map<String, Object> cc = loadState(c);
                expect(problems, "C", cc, Map.of("enabled", true, "DICE", false, "FPS", true, "WARP", false));

                // D: a fresh install - defaults, nothing written.
                delete(party);
                delete(chat);
                Map<String, Object> d = loadState(c);
                expect(problems, "D", d, Map.of("enabled", false, "COORDS", true, "WARP", false, "ch.PARTY", true,
                        "ch.ALL", false, "ch.MOD_CHAT", true, "delay", 200));
                if (Files.exists(party)) {
                    problems.add("D: a fresh install wrote a config file");
                }
            } finally {
                restore(party, savedParty);
                restore(chat, savedChat);
                c.onClient(mc -> Mod.staticCall(PCC, "load"));
            }
            c.check(problems.isEmpty(), String.join("; ", problems));
            c.note("v1 party + chat configs -> one list (destructive gate honoured, racism/confirm dropped); v2 read as is");
        });
    }

    private static Map<String, Object> loadState(Session c) {
        return c.onClient(mc -> {
            Mod.staticCall(PCC, "load");
            Object cfg = Mod.cfg(PCC);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("enabled", Mod.call(cfg, "isEnabledRaw"));
            out.put("delay", Mod.call(cfg, "getReplyDelayMs"));
            for (Object cmd : Mod.cls(PCC + "$Command").getEnumConstants()) {
                out.put(((Enum<?>) cmd).name(), Mod.call(cfg, "isOn", cmd));
            }
            for (Object ch : Mod.cls(PCC + "$Channel").getEnumConstants()) {
                out.put("ch." + ((Enum<?>) ch).name(), Mod.call(cfg, "isChannelOn", ch));
            }
            return out;
        });
    }

    private static void expect(List<String> problems, String label, Map<String, Object> got, Map<String, Object> want) {
        for (Map.Entry<String, Object> e : want.entrySet()) {
            if (!e.getValue().equals(got.get(e.getKey()))) {
                problems.add(label + ": " + e.getKey() + " = " + got.get(e.getKey()) + ", want " + e.getValue());
            }
        }
    }

    // ---- 577 ------------------------------------------------------------------------------------------------------

    /** Wall-clock stamps from this JVM: the "!coords" line arriving at the client, and the reply packet leaving it. */
    private static volatile long coordsSeenAt;
    private static volatile long replySentAt;
    private static volatile boolean timing;
    private static boolean receiveHooked;

    private static void replyDelay(Session s) {
        s.test("577-hx-mcp-reply-delay", c -> {
            Object coords = c.onClient(mc -> Mod.enumValue(PCC + "$Command", "COORDS"));
            Object partyCh = c.onClient(mc -> Mod.enumValue(PCC + "$Channel", "PARTY"));
            Object cfg = c.onClient(mc -> Mod.cfg(PCC));
            boolean wasOn = c.onClient(mc -> (Boolean) Mod.call(cfg, "isOn", coords));
            boolean wasCh = c.onClient(mc -> (Boolean) Mod.call(cfg, "isChannelOn", partyCh));
            java.util.function.Consumer<net.minecraft.network.protocol.Packet<?>> oldTap = PacketTrace.tap;
            try (Settings set = new Settings(c).with(PCC, "Enabled", true).with(PCC, "ReplyDelayMs", 200)
                    .custom(() -> Mod.call(cfg, "setChannelOn", partyCh, true), () -> Mod.call(cfg, "setChannelOn", partyCh, wasCh))
                    .custom(() -> Mod.call(cfg, "setOn", coords, true), () -> Mod.call(cfg, "setOn", coords, wasOn))) {
                dungeon(c);
                c.onClient(mc -> {
                    if (!receiveHooked) {
                        receiveHooked = true;
                        // Registered after the mod's own listener, so it stamps the line slightly LATER than the mod
                        // saw it: the measured gap can only be smaller than the real one.
                        net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.GAME.register((msg, overlay) -> {
                            if (timing && !overlay && msg.getString().contains("HxMateA") && msg.getString().contains("!coords")) {
                                coordsSeenAt = System.nanoTime();
                            }
                        });
                    }
                    return null;
                });
                PacketTrace.tap = packet -> {
                    if (timing && replySentAt == 0 && packet instanceof ServerboundChatCommandPacket p
                            && p.command().startsWith("pc ")) {
                        replySentAt = System.nanoTime();
                    }
                };
                long d200 = measure(c);
                c.check(d200 >= 200, "the reply left " + d200 + " ms after the line, want >= 200 (default delay)");
                c.check(d200 < 600, "the reply took " + d200 + " ms with a 200 ms delay - something else is holding it");
                c.onClient(mc -> {
                    Mod.call(cfg, "setReplyDelayMs", 600);
                    return null;
                });
                long d600 = measure(c);
                c.check(d600 >= 600, "the reply left " + d600 + " ms after the line, want >= 600 (delay set to 600)");
                c.note("reply delay 200 ms -> measured " + d200 + " ms; 600 ms -> " + d600 + " ms (line seen -> packet sent)");
            } finally {
                timing = false;
                PacketTrace.tap = oldTap;
            }
        });
    }

    private static long measure(Session c) {
        run(c, HxSocialCases::resetPartyCommandLimits);
        long head = c.hx().head();
        coordsSeenAt = 0;
        replySentAt = 0;
        timing = true;
        send(c, "chat.party-coords");
        c.waitUntil("the pc reply at the server", mc -> c.hx().commands(head).stream().anyMatch(x -> x.startsWith("pc ")), 80);
        c.waitUntil("the reply packet stamped", mc -> replySentAt != 0, 20);
        timing = false;
        c.check(coordsSeenAt != 0, "premise: the '!coords' line was never seen arriving at the client");
        return (replySentAt - coordsSeenAt) / 1_000_000L;
    }

    // ---- 578 ------------------------------------------------------------------------------------------------------

    private static void partyChannels(Session s) {
        s.test("578-hx-mcp-party-channels", c -> {
            Object cfg = c.onClient(mc -> Mod.cfg(PCC));
            Object coords = c.onClient(mc -> Mod.enumValue(PCC + "$Command", "COORDS"));
            Object warp = c.onClient(mc -> Mod.enumValue(PCC + "$Command", "WARP"));
            Object all = c.onClient(mc -> Mod.enumValue(PCC + "$Channel", "ALL"));
            Object modChat = c.onClient(mc -> Mod.enumValue(PCC + "$Channel", "MOD_CHAT"));
            Map<Object, Boolean> savedCh = new LinkedHashMap<>();
            Map<Object, Boolean> savedCmd = new LinkedHashMap<>();
            for (Object ch : List.of(all, modChat)) {
                savedCh.put(ch, c.onClient(mc -> (Boolean) Mod.call(cfg, "isChannelOn", ch)));
            }
            for (Object cmd : List.of(coords, warp)) {
                savedCmd.put(cmd, c.onClient(mc -> (Boolean) Mod.call(cfg, "isOn", cmd)));
            }
            try (Settings set = new Settings(c).with(PCC, "Enabled", true).with(PCC, "ReplyDelayMs", 200)
                    .with(MC, "Enabled", true).with(MC, "LogToChat", true)) {
                run(c, () -> {
                    Mod.call(cfg, "setOn", coords, true);
                    Mod.call(cfg, "setOn", warp, true);
                    Mod.call(cfg, "setChannelOn", all, false);
                    Mod.call(cfg, "setChannelOn", modChat, true);
                });
                dungeon(c);
                // All chat OFF: a stranger's all-chat !coords gets nothing.
                run(c, HxSocialCases::resetPartyCommandLimits);
                c.hx().chat("§7[312] §b[MVP§c+§b] HxStranger§f: !coords");
                assertNoCommand(c, 30, "ac ", "pc ");
                // All chat ON: the reply goes back to all chat.
                run(c, () -> {
                    HxSocialCases.resetPartyCommandLimits();
                    Mod.call(cfg, "setChannelOn", all, true);
                });
                c.hx().chat("§7[312] §b[MVP§c+§b] HxStranger§f: !coords");
                String ac = awaitCommand(c, "ac ", 60);
                double[] at = c.scenario().playerPosition();
                String want = String.format(Locale.US, "ac %.0f, %.0f, %.0f", at[0], at[1], at[2]);
                c.check(ac.equals(want), "/" + ac + " != /" + want);
                // ...but a stranger's !warp from all chat stops at the teammate gate.
                run(c, HxSocialCases::resetPartyCommandLimits);
                long mark = LogTap.mark();
                c.hx().chat("§7[312] §b[MVP§c+§b] HxStranger§f: !warp");
                assertNoCommand(c, 30, "p warp");
                c.check(LogTap.since(mark).stream().anyMatch(l -> l.contains("HxStranger") && l.contains("not a party/dungeon teammate")),
                        "the stranger's all-chat !warp was not refused at the teammate gate");
                // A teammate (on the dungeon tab) asking in all chat does warp: the gate is who, not where.
                run(c, HxSocialCases::resetPartyCommandLimits);
                c.hx().chat("§7[300] §b[MVP§c+§b] HxMateA§f: !warp");
                awaitCommand(c, "p warp", 60);
                // Mod Chat ("kc"): a relay line runs the command and answers into Mod Chat, never into Hypixel.
                run(c, HxSocialCases::resetPartyCommandLimits);
                long mark2 = LogTap.mark();
                long head = c.hx().head();
                run(c, () -> Mod.call(Mod.field("relay.RelayClient", "listener"), "onChat", "HxMateA", "!coords"));
                c.waitUntil("the Mod Chat !coords answered", mc -> LogTap.since(mark2).stream()
                        .anyMatch(l -> l.contains("\"!coords\" from HxMateA answered")), 60);
                c.check(LogTap.since(mark2).stream().anyMatch(l -> l.contains("Mod Chat reply not sent")),
                        "the Mod Chat reply did not go to the relay (offline here, so it must say it was not sent)");
                c.ctx().waitTicks(10);
                List<String> leaked = c.hx().commands(head);
                c.check(leaked.stream().noneMatch(x -> x.startsWith("pc ") || x.startsWith("ac ") || x.startsWith("kc")),
                        "a Mod Chat command answered into Hypixel: " + leaked);
                // Mod Chat OFF as a command chat: the same line does nothing.
                run(c, () -> {
                    HxSocialCases.resetPartyCommandLimits();
                    Mod.call(cfg, "setChannelOn", modChat, false);
                });
                long mark3 = LogTap.mark();
                run(c, () -> Mod.call(Mod.field("relay.RelayClient", "listener"), "onChat", "HxMateA", "!coords"));
                c.ctx().waitTicks(30);
                c.check(LogTap.since(mark3).stream().noneMatch(l -> l.contains("from HxMateA answered")),
                        "Mod Chat OFF still answered a Mod Chat command");
                c.note("all chat off: nothing; on: /" + ac + "; stranger !warp gated; teammate !warp warped;"
                        + " Mod Chat !coords answered to the relay, never Hypixel; Mod Chat off: nothing");
            } finally {
                run(c, () -> {
                    savedCh.forEach((ch, v) -> Mod.call(cfg, "setChannelOn", ch, v));
                    savedCmd.forEach((cmd, v) -> Mod.call(cfg, "setOn", cmd, v));
                });
            }
        });
    }

    // ---- 579 ------------------------------------------------------------------------------------------------------

    private static final UUID MAIN = UUID.fromString("5a1d0b6e-0000-4000-8000-00000000a560");
    private static final UUID ALT = UUID.fromString("5a1d0b6e-0000-4000-8000-00000000b579");
    private static final String MAIN_TOKEN = "hx-main-token";
    private static final String ALT_TOKEN = "hx-alt-token";
    private static final List<String> HOST_PROPS = List.of("minecraft.api.services.host", "minecraft.api.session.host",
            "minecraft.api.profiles.host", "killer560.net.relay");
    private static final List<String> MC_FIELDS = List.of("user", "profileFuture", "userApiService",
            "userPropertiesFuture", "profileKeyPairManager");

    /**
     * killer560's alts got "relay rejected the login: that public key was not issued by Mojang for that UUID". The
     * launch account here gets a real (fake-backed) Mojang session, the mod's own AccountApplier swaps to an alt, and
     * the mod's own RelayAuth logs in to a fake relay that checks exactly what the real one does: Mojang's signature
     * over (uuid, expiry, key) for the uuid the client claims. On the pre-fix jar the alt is handed the MAIN account's
     * key (the applier reused the launch token) and this fails with the real relay's message.
     */
    private static void accountSwapKey(Session s) {
        s.test("579-hx-mcp-account-swap-key", c -> {
            FakeMojangRelay fake;
            try {
                fake = FakeMojangRelay.start(FAKE_PORT);
            } catch (Exception e) {
                throw new AssertionError("could not start the fake Mojang/relay on " + FAKE_PORT + ": " + e, e);
            }
            Map<String, String> savedProps = new LinkedHashMap<>();
            Map<String, Object> savedFields = new LinkedHashMap<>();
            String outcome;
            try {
                for (String p : HOST_PROPS) {
                    savedProps.put(p, System.getProperty(p));
                }
                CompletableFuture<?> login = c.onClient(mc -> {
                    for (String f : MC_FIELDS) {
                        savedFields.put(f, readField(mc, f));
                    }
                    String base = "http://127.0.0.1:" + FAKE_PORT;
                    for (String p : HOST_PROPS) {
                        System.setProperty(p, base);
                    }
                    // The launch account, signed in for real (as far as the fake Mojang can tell).
                    UserApiService mainService = new YggdrasilAuthenticationService(Proxy.NO_PROXY).createUserApiService(MAIN_TOKEN);
                    User main = new User("HxMainAcct", MAIN, MAIN_TOKEN, Optional.empty(), Optional.empty());
                    writeField(mc, "user", main);
                    writeField(mc, "userApiService", mainService);
                    writeField(mc, "profileKeyPairManager",
                            ProfileKeyPairManager.create(mainService, main, mc.gameDirectory.toPath()));
                    // The mod's Account Switcher swaps to the alt.
                    try {
                        Object auth = Mod.cls("accounts.core.AuthResult").getConstructors()[0]
                                .newInstance(ALT, "HxAltAcct", ALT_TOKEN, null, null, null);
                        Mod.staticCall("accounts.AccountApplier", "apply", auth);
                    } catch (ReflectiveOperationException e) {
                        throw new AssertionError("AuthResult", e);
                    }
                    return (CompletableFuture<?>) Mod.staticCall("relay.RelayAuth", "authenticate",
                            HttpClient.newHttpClient(), base);
                });
                c.waitUntil("the relay login finished", mc -> login.isDone(), 600);
                try {
                    Object token = login.join();
                    outcome = "token " + (token == null ? "null" : "issued");
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    outcome = "FAILED: " + cause.getMessage();
                }
            } finally {
                c.onClient(mc -> {
                    savedFields.forEach((f, v) -> writeField(mc, f, v));
                    return null;
                });
                savedProps.forEach((p, v) -> {
                    if (v == null) {
                        System.clearProperty(p);
                    } else {
                        System.setProperty(p, v);
                    }
                });
                fake.stop();
            }
            c.note("fake Mojang saw certificate requests for " + fake.certificateTokens + "; relay saw logins "
                    + fake.authLog + "; outcome " + outcome);
            c.check(!fake.certificateTokens.isEmpty(), "premise: the client never asked Mojang for a chat key");
            c.check(!fake.authLog.isEmpty(), "premise: the client never posted a relay login (" + outcome + ")");
            c.check(fake.certificateTokens.stream().allMatch(ALT_TOKEN::equals),
                    "after the swap the chat key was fetched with " + fake.certificateTokens + " - want only the alt's token");
            c.check(outcome.startsWith("token"), "the alt's relay login failed: " + outcome);
            c.check(fake.authLog.stream().allMatch(l -> l.startsWith(ALT + " ok")), "relay logins " + fake.authLog);
        });
    }

    private static Object readField(Minecraft mc, String name) {
        try {
            Field f = Minecraft.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(mc);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Minecraft." + name, e);
        }
    }

    private static void writeField(Minecraft mc, String name, Object value) {
        try {
            Field f = Minecraft.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(mc, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Minecraft." + name, e);
        }
    }

    /**
     * Mojang's services ({@code POST /player/certificates}: a fresh player key pair signed by this fake's own
     * "Mojang" key for the account the bearer token belongs to) and the relay's login ({@code /challenge},
     * {@code /auth}: the same two checks src/mojang.ts makes). Loopback only.
     */
    static final class FakeMojangRelay {
        final List<String> certificateTokens = new CopyOnWriteArrayList<>();
        final List<String> authLog = new CopyOnWriteArrayList<>();
        private final Map<String, UUID> accounts = Map.of(MAIN_TOKEN, MAIN, ALT_TOKEN, ALT);
        private final Map<String, byte[]> challenges = new java.util.concurrent.ConcurrentHashMap<>();
        private final AtomicInteger seq = new AtomicInteger();
        private final KeyPair mojang;
        private final HttpServer server;

        private FakeMojangRelay(int port) throws Exception {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            mojang = gen.generateKeyPair();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        static FakeMojangRelay start(int port) throws Exception {
            return new FakeMojangRelay(port);
        }

        void stop() {
            server.stop(0);
        }

        private void handle(HttpExchange ex) throws IOException {
            try {
                String path = ex.getRequestURI().getPath();
                if (path.endsWith("/player/certificates")) {
                    certificates(ex);
                } else if (path.equals("/challenge")) {
                    String challenge = "hx579-" + seq.incrementAndGet() + "-" + System.nanoTime();
                    String q = ex.getRequestURI().getQuery();
                    challenges.put(challenge, (q == null ? "" : q).getBytes(StandardCharsets.UTF_8));
                    JsonObject o = new JsonObject();
                    o.addProperty("challenge", challenge);
                    reply(ex, 200, o);
                } else if (path.equals("/auth")) {
                    auth(ex);
                } else {
                    JsonObject o = new JsonObject();
                    o.addProperty("error", "not found");
                    reply(ex, 404, o);
                }
            } catch (Exception e) {
                JsonObject o = new JsonObject();
                o.addProperty("error", "fake failed: " + e);
                reply(ex, 500, o);
            }
        }

        private void certificates(HttpExchange ex) throws Exception {
            String bearer = String.valueOf(ex.getRequestHeaders().getFirst("Authorization")).replaceFirst("^Bearer ", "");
            certificateTokens.add(bearer);
            UUID uuid = accounts.get(bearer);
            if (uuid == null) {
                JsonObject o = new JsonObject();
                o.addProperty("error", "unknown token");
                reply(ex, 401, o);
                return;
            }
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair player = gen.generateKeyPair();
            Instant expires = Instant.now().plusSeconds(48 * 3600);
            Signature sig = Signature.getInstance("SHA1withRSA");
            sig.initSign(mojang.getPrivate());
            sig.update(payload(uuid, expires.toEpochMilli(), player.getPublic().getEncoded()));
            JsonObject pair = new JsonObject();
            pair.addProperty("privateKey", "-----BEGIN RSA PRIVATE KEY-----\n"
                    + Base64.getEncoder().encodeToString(player.getPrivate().getEncoded()) + "\n-----END RSA PRIVATE KEY-----\n");
            pair.addProperty("publicKey", "-----BEGIN RSA PUBLIC KEY-----\n"
                    + Base64.getEncoder().encodeToString(player.getPublic().getEncoded()) + "\n-----END RSA PUBLIC KEY-----\n");
            JsonObject o = new JsonObject();
            o.add("keyPair", pair);
            o.addProperty("publicKeySignatureV2", Base64.getEncoder().encodeToString(sig.sign()));
            o.addProperty("expiresAt", expires.toString());
            o.addProperty("refreshedAfter", Instant.now().plusSeconds(36 * 3600).toString());
            reply(ex, 200, o);
        }

        private void auth(HttpExchange ex) throws Exception {
            JsonObject body = JsonParser.parseString(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            UUID uuid = UUID.fromString(body.get("uuid").getAsString());
            byte[] der = Base64.getDecoder().decode(body.get("publicKey").getAsString());
            long expiresAt = body.get("expiresAt").getAsLong();
            Signature mojangCheck = Signature.getInstance("SHA1withRSA");
            mojangCheck.initVerify(mojang.getPublic());
            mojangCheck.update(payload(uuid, expiresAt, der));
            boolean issued = mojangCheck.verify(Base64.getDecoder().decode(body.get("publicKeySignature").getAsString()));
            String challenge = body.get("challenge").getAsString();
            boolean known = challenges.containsKey(challenge);
            PublicKey playerKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
            Signature chCheck = Signature.getInstance("SHA256withRSA");
            chCheck.initVerify(playerKey);
            chCheck.update(challenge.getBytes(StandardCharsets.UTF_8));
            boolean holds = chCheck.verify(Base64.getDecoder().decode(body.get("challengeSignature").getAsString()));
            authLog.add(uuid + (issued && known && holds ? " ok" : " REJECTED issued=" + issued + " known=" + known + " holds=" + holds));
            JsonObject o = new JsonObject();
            if (!issued) {
                o.addProperty("error", "that public key was not issued by Mojang for that UUID (fake relay, case 579)");
                reply(ex, 401, o);
            } else if (!known || !holds) {
                o.addProperty("error", "challenge signature did not match that public key");
                reply(ex, 401, o);
            } else {
                o.addProperty("token", "hx579-token-" + seq.incrementAndGet());
                o.addProperty("exp", System.currentTimeMillis() + 3_600_000L);
                reply(ex, 200, o);
            }
        }

        /** Mojang's signed bytes: uuid (two longs), expiry (long millis), the player key's DER. */
        private static byte[] payload(UUID uuid, long expiresAt, byte[] der) {
            ByteBuffer b = ByteBuffer.allocate(24 + der.length);
            b.putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).putLong(expiresAt).put(der);
            return b.array();
        }

        private static void reply(HttpExchange ex, int status, JsonObject body) throws IOException {
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    // ---- 580 ------------------------------------------------------------------------------------------------------

    private static void removedItems(Session s) {
        s.test("580-hx-mcp-removed-items", c -> {
            List<String> problems = c.onClient(mc -> {
                List<String> out = new ArrayList<>();
                for (String gone : List.of("chatcommands.ChatCommandsConfig", "chatcommands.ChatCommandsFeature")) {
                    try {
                        Class.forName(Mod.ROOT + gone);
                        out.add(gone + " still exists");
                    } catch (ClassNotFoundException expected) {
                        // gone
                    }
                }
                List<String> methods = Arrays.stream(Mod.cls(PCC).getMethods()).map(java.lang.reflect.Method::getName)
                        .filter(n -> n.contains("Destructive") || n.contains("ConfirmInvites")).toList();
                if (!methods.isEmpty()) {
                    out.add("PartyCommandsConfig still has " + methods);
                }
                for (Object cmd : Mod.cls(PCC + "$Command").getEnumConstants()) {
                    String n = ((Enum<?>) cmd).name();
                    if (n.contains("RACISM")) {
                        out.add("Command." + n + " still exists");
                    }
                }
                for (String[] gone : new String[][]{{"Party Commands", "Allow Destructive Commands"},
                        {"Party Commands", "Confirm Invites"}, {"Party Commands", "Racism (joke)"},
                        {"Mod Chat", "Presence Alerts"}}) {
                    Object tip = Mod.staticCall("gui.SettingTooltips", "describe", gone[0], gone[1]);
                    if (tip != null && !tip.toString().isBlank()) {
                        out.add("tooltip still there for " + gone[0] + "/" + gone[1] + ": " + tip);
                    }
                }
                return out;
            });
            // A party "!racism" from a teammate, every command on: nothing is sent.
            Object cfg = c.onClient(mc -> Mod.cfg(PCC));
            try (Settings set = new Settings(c).with(PCC, "Enabled", true)) {
                dungeon(c);
                run(c, HxSocialCases::resetPartyCommandLimits);
                c.hx().chat("§9Party §8> §b[MVP§c+§b] HxMateA§f: !racism");
                assertNoCommand(c, 30, "pc ", "gc ", "msg ");
            }
            c.check(problems.isEmpty(), String.join("; ", problems));
            c.note("chatcommands classes, destructive/confirm methods, RACISM, their tooltips gone; party !racism sends nothing ("
                    + (cfg != null ? "config live" : "") + ")");
        });
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    /** Calls a {@code relay.RelayListener} method on {@code listener} by its interface signature. */
    private static void relayListener(Object listener, String method, Object... args) {
        for (java.lang.reflect.Method m : Mod.cls("relay.RelayListener").getMethods()) {
            if (m.getName().equals(method) && m.getParameterCount() == args.length) {
                try {
                    m.invoke(listener, args);
                    return;
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError("RelayListener." + method + " threw", e);
                }
            }
        }
        throw new AssertionError("no RelayListener." + method);
    }

    /** A tab's widgets, built the way the menu builds them (client thread). */
    @SuppressWarnings("unchecked")
    private static List<AbstractWidget> buildTab(String tabClass) {
        try {
            Object tab = Mod.cls(tabClass).getConstructor().newInstance();
            Runnable noop = () -> {
            };
            return (List<AbstractWidget>) Mod.call(tab, "buildWidgets", 0, 0, 320, noop);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not build " + tabClass, e);
        }
    }

    private static List<String> labels(List<AbstractWidget> widgets) {
        List<String> out = new ArrayList<>();
        for (AbstractWidget w : widgets) {
            out.add(plain(w.getMessage().getString()));
        }
        return out;
    }

    private static String plain(String s) {
        String p = ChatFormatting.stripFormatting(s);
        return p == null ? s : p;
    }

    private static byte[] readOrNull(Path file) {
        try {
            return Files.exists(file) ? Files.readAllBytes(file) : null;
        } catch (IOException e) {
            throw new AssertionError("read " + file, e);
        }
    }

    private static void write(Path file, String json) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("write " + file, e);
        }
    }

    private static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new AssertionError("delete " + file, e);
        }
    }

    private static void restore(Path file, byte[] saved) {
        try {
            if (saved == null) {
                Files.deleteIfExists(file);
            } else {
                Files.createDirectories(file.getParent());
                Files.write(file, saved);
            }
        } catch (IOException e) {
            throw new AssertionError("restore " + file, e);
        }
    }
}
