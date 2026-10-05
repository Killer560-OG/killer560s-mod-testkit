package dev.testkit.gametest.hx;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Chat commands, party commands, typed-chat features, social and name features: 120-134. The server side of most of
 * these is a COMMAND the client sends ({@code cmd} events, see {@link Session#commands()}), and Hypixel's answers come
 * from WP2's responders ({@code surface.respond}). Sources: killer560s-mod f40ec89.
 */
final class HxSocialCases {

    private HxSocialCases() {
    }

    static void register(Session s) {
        chatCommands(s);
        partyCommands(s);
        partyList(s);
        commandShortcuts(s);
        commandKeybinds(s);
        autoJoinSkyblock(s);
        autoCorrect(s);
        emotes(s);
        cringe(s);
        autoMeow(s);
        leapMessage(s);
        players(s);
        friendsList(s);
        nameChanger(s);
        lagDisplay(s);
    }

    // ---- 120 chatcommands ---------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static void chatCommands(Session s) {
        s.test("120-hx-chatcommands-coords", c -> {
            Object coords = c.onClient(mc -> Mod.enumValue("chatcommands.ChatCommandsConfig$InfoCommand", "COORDS"));
            Object cfg = c.onClient(mc -> Mod.cfg("chatcommands.ChatCommandsConfig"));
            boolean wasOn = c.onClient(mc -> (Boolean) Mod.call(cfg, "isOn", coords));
            try (Settings set = new Settings(c).with("chatcommands.ChatCommandsConfig", "Enabled", true)
                    .with("chatcommands.ChatCommandsConfig", "PartyEnabled", true)
                    .custom(() -> Mod.call(cfg, "setOn", coords, true), () -> Mod.call(cfg, "setOn", coords, wasOn))) {
                dungeon(c);
                run(c, () -> {
                    Mod.setField("chatcommands.ChatCommandsFeature", "lastReplyAtMs", 0L);
                    ((Map<String, Long>) Mod.field("chatcommands.ChatCommandsFeature", "LAST_REPLY_BY_SENDER")).clear();
                    ((java.util.Collection<Long>) Mod.field("chatcommands.ChatCommandsFeature", "REPLY_TIMES")).clear();
                });
                send(c, "hostile.allchat-party-warp");
                c.hx().chat("§7[VIP] HxEvil§f: Party > HxMateA: !coords");
                assertNoCommand(c, 30, "pc ");
                send(c, "chat.party-coords");
                String cmd = awaitCommand(c, "pc ", 60);
                double[] at = c.scenario().playerPosition();
                String want = String.format(java.util.Locale.US, "pc %.0f, %.0f, %.0f", at[0], at[1], at[2]);
                c.check(cmd.equals(want), "/" + cmd + " != /" + want);
                c.note("all-chat quotes ignored; party !coords -> /" + cmd);
            }
        });
    }

    // ---- 121 partycommands -------------------------------------------------------------------------------------

    private static void partyCommands(Session s) {
        s.test("121-hx-partycommands-warp", c -> {
            Object warp = c.onClient(mc -> Mod.enumValue("partycommands.PartyCommandsConfig$Command", "WARP"));
            Object cfg = c.onClient(mc -> Mod.cfg("partycommands.PartyCommandsConfig"));
            boolean wasOn = c.onClient(mc -> (Boolean) Mod.call(cfg, "isOn", warp));
            try (Settings set = new Settings(c).with("partycommands.PartyCommandsConfig", "Enabled", true)
                    .with("partycommands.PartyCommandsConfig", "AllowDestructive", true)
                    .custom(() -> Mod.call(cfg, "setOn", warp, true), () -> Mod.call(cfg, "setOn", warp, wasOn))) {
                dungeon(c);
                c.check(c.onClient(mc -> Mod.staticCall("partycommands.PartyLeaderTracker", "get")) == null,
                        "premise: a party leader is already known");
                send(c, "hostile.allchat-party-warp");
                assertNoCommand(c, 30, "p warp");
                send(c, "chat.party-warp");
                String cmd = awaitCommand(c, "p warp", 60);
                c.note("all-chat '!warp' quote ignored; HxMateA (dungeon tab teammate) '!warp' -> /" + cmd);
            }
        });
    }

    // ---- 122 party list responder -> PartyTracker / PartyLeaderTracker -------------------------------------------

    @SuppressWarnings("unchecked")
    private static void partyList(Session s) {
        s.test("122-hx-party-list-responder", c -> {
            try (Settings set = new Settings(c).with("partycommands.PartyCommandsConfig", "Enabled", true)) {
                hub(c);
                c.hx().call("tab.clear");
                c.hx().chat("§eYou left the party.");
                c.waitUntil("PartyTracker empty after 'You left the party.' (PartyTracker :60)",
                        mc -> ((List<String>) Mod.staticCall("leapmenu.PartyTracker", "teammates")).isEmpty(), 60);
                c.hx().chat("§7[VIP] HxEvil§f: Party Members: HxEvil ●");
                c.ctx().waitTicks(6);
                respond(c, "p", "p list",
                        text(fx(c, "chat.party-list-header")), text(fx(c, "chat.party-list-leader")),
                        text(fx(c, "chat.party-list-members")));
                run(c, () -> Mod.staticCall("util.ServerCommands", "toServer", "p list"));
                c.waitUntil("a surface.reply for /p list", mc -> !c.events("surface.reply").isEmpty(), 60);
                c.waitUntil("PartyTracker.teammates() has HxMateA and HxMateB from the /p list answer", mc -> {
                    List<String> mates = (List<String>) Mod.staticCall("leapmenu.PartyTracker", "teammates");
                    return mates.contains("HxMateA") && mates.contains("HxMateB");
                }, 60);
                List<String> mates = c.onClient(mc -> List.copyOf((List<String>) Mod.staticCall("leapmenu.PartyTracker", "teammates")));
                c.check(!mates.contains("HxEvil"), "an all-chat 'Party Members:' quote added HxEvil: " + mates);
                Object leader = c.onClient(mc -> Mod.staticCall("partycommands.PartyLeaderTracker", "get"));
                c.check("HxLead".equals(leader), "PartyLeaderTracker leader " + leader);
                c.note("/p list answered by the responder -> teammates " + mates + ", leader " + leader
                        + "; all-chat quote ignored");
            } finally {
                c.hx().call("surface.respond.clear", "root", "p");
                c.hx().chat("§eYou left the party.");
                c.ctx().waitTicks(6);
            }
        });
    }

    private static String text(dev.testkit.gametest.Fixtures.Fixture f) {
        return f.payload().getAsString();
    }

    // ---- 123 commandshortcuts (+ SkyblockGate pausing them) -----------------------------------------------------

    private static void commandShortcuts(Session s) {
        s.test("123-hx-commandshortcuts-f7", c -> {
            boolean gateWas = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled"));
            try {
                run(c, () -> Mod.staticCall("util.SkyblockGate", "setEnabled", true));
                // Off Skyblock: paused, nothing sent (CommandShortcutsFeature :275-277).
                sidebar(c, "template", "hub", "title", "§e§lBED WARS");
                c.waitUntil("off Skyblock", mc -> !(Boolean) Mod.staticCall("util.SkyblockGate", "isOnSkyblock"), 40);
                long mark = LogTap.mark();
                type(c, "/f7");
                assertNoCommand(c, 20, "joininstance");
                c.check(!logLines(mark, "paused outside Skyblock").isEmpty(), "no 'paused' message for /f7 off Skyblock");
                sidebar(c, "template", "hub");
                c.waitUntil("on Skyblock", mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isOnSkyblock"), 40);
                type(c, "/f7");
                String cmd = awaitCommand(c, "joininstance", 40);
                c.check(cmd.equals("joininstance catacombs_floor_seven"), "/" + cmd);
                c.check(c.commands().stream().noneMatch(x -> x.equals("f7")), "the literal /f7 reached the server");
                c.note("/f7 off Skyblock -> paused, nothing sent; on Skyblock -> /" + cmd + " (never /f7)");
            } finally {
                run(c, () -> Mod.staticCall("util.SkyblockGate", "setEnabled", gateWas));
            }
        });
    }

    // ---- 124 commandkeybinds -------------------------------------------------------------------------------------

    private static void commandKeybinds(Session s) {
        s.test("124-hx-commandkeybinds-send", c -> {
            // The key poll is GLFW state a test cannot press; this drives the send step the tick calls (:46-66).
            run(c, () -> {
                Mod.staticCall("commandkeybinds.CommandKeybindsFeature", "send", Minecraft.getInstance(), "   ");
                Mod.staticCall("commandkeybinds.CommandKeybindsFeature", "send", Minecraft.getInstance(), "/");
            });
            c.ctx().waitTicks(10);
            c.check(c.commands().isEmpty(), "a blank or bare-slash bind sent " + c.commands());
            c.check(c.events("chat").isEmpty(), "a blank bind sent chat " + c.events("chat"));
            run(c, () -> Mod.staticCall("commandkeybinds.CommandKeybindsFeature", "send", Minecraft.getInstance(), " /warp dh "));
            String cmd = awaitCommand(c, "warp", 40);
            c.check(cmd.equals("warp dh"), "/" + cmd);
            run(c, () -> Mod.staticCall("commandkeybinds.CommandKeybindsFeature", "send", Minecraft.getInstance(), "hx bind chat"));
            c.waitUntil("a player chat event 'hx bind chat'", mc -> c.events("chat").stream()
                    .anyMatch(e -> e.get("text").getAsString().equals("hx bind chat")), 40);
            c.note("blank and '/' binds send nothing; ' /warp dh ' -> /" + cmd + "; 'hx bind chat' -> chat");
        });
    }

    // ---- 125 autojoinskyblock ------------------------------------------------------------------------------------

    private static void autoJoinSkyblock(Session s) {
        s.test("125-hx-autojoinskyblock", c -> {
            try (Settings set = new Settings(c).with("autojoinskyblock.AutoJoinSkyblockConfig", "Enabled", true)) {
                // Negative first: a pending auto-join is cancelled by any command the player sends (:19-23).
                run(c, () -> {
                    Mod.setField("autojoinskyblock.AutoJoinSkyblockFeature", "pending", true);
                    Mod.setField("autojoinskyblock.AutoJoinSkyblockFeature", "fireAtMs", System.currentTimeMillis() + 1500L);
                    Minecraft.getInstance().player.connection.sendCommand("hxcancel");
                });
                assertNoCommand(c, 60, "skyblock");
                // Positive: a real join to a server labelled mc.hypixel.net.
                c.scenario().reconnect();
                String cmd = awaitCommand(c, "skyblock", 100);
                c.note("own command cancels a pending auto-join; re-joining 'mc.hypixel.net' -> /" + cmd);
            }
        });
    }

    // ---- 126 autocorrect -----------------------------------------------------------------------------------------

    private static void autoCorrect(Session s) {
        s.test("126-hx-autocorrect", c -> {
            try (Settings set = new Settings(c).with("autocorrect.AutoCorrectConfig", "CorrectCommands", true)
                    .with("autocorrect.AutoCorrectConfig", "Enabled", true)
                    .with("emotes.ChatEmoteConfig", "Enabled", false)) {
                type(c, "/wardorbe");
                String cmd = awaitCommand(c, "wardrobe", 40);
                // A whisper's recipient is never corrected (TranslateFeature :139-146), the message is.
                type(c, "/msg Teh teh");
                String msg = awaitCommand(c, "msg ", 40);
                c.check(msg.equals("msg Teh the"), "/" + msg + " (want /msg Teh the)");
                c.check(c.commands().stream().noneMatch(x -> x.startsWith("wardorbe")), "the typo reached the server");
                c.note("/wardorbe -> /" + cmd + "; /msg Teh teh -> /" + msg);
            }
        });
    }

    // ---- 127 emotes -----------------------------------------------------------------------------------------------

    private static void emotes(Session s) {
        s.test("127-hx-emotes", c -> {
            try (Settings set = new Settings(c).with("emotes.ChatEmoteConfig", "Enabled", true)
                    .with("autocorrect.AutoCorrectConfig", "Enabled", false)) {
                type(c, "/pc hi o/ there");
                String middle = awaitCommand(c, "pc hi", 40);
                c.check(middle.equals("pc hi o/ there"), "a trigger in the middle was replaced: /" + middle);
                type(c, "/pc o/ hello o/");
                c.waitUntil("an emote-converted /pc", mc -> c.commands().stream().anyMatch(x -> x.contains("◡")), 40);
                String cmd = c.commands().stream().filter(x -> x.contains("◡")).findFirst().orElseThrow();
                c.check(cmd.equals("pc ( ﾟ◡ﾟ)/ hello ( ﾟ◡ﾟ)/"), "/" + cmd);
                c.note("'o/' in the middle kept; leading/trailing 'o/' -> ( ﾟ◡ﾟ)/ (ChatEmoteFeature :90): /" + cmd);
            }
        });
    }

    // ---- 128 cringe -----------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static void cringe(Session s) {
        s.test("128-hx-cringe", c -> {
            try (Settings set = new Settings(c).with("emotes.ChatEmoteConfig", "Enabled", false)) {
                type(c, "/cringe nosuchchannel");
                assertNoCommand(c, 20, "pc ", "gc ");
                c.check(c.events("chat").isEmpty(), "an unknown channel still sent chat");
                type(c, "/cringe pc");
                String cmd = awaitCommand(c, "pc ", 40);
                List<String> lines = c.onClient(mc -> List.copyOf((List<String>) Mod.staticCall("cringe.CringeLines", "all")));
                c.check(lines.contains(cmd.substring(3)), "/" + cmd + " is not one of the " + lines.size() + " cringe lines");
                c.note("unknown channel -> nothing; /cringe pc -> /" + cmd);
            }
        });
    }

    // ---- 129 automeow ---------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static void autoMeow(Session s) {
        s.test("129-hx-automeow", c -> {
            try (Settings set = new Settings(c).with("automeow.AutoMeowConfig", "Enabled", true)
                    .with("automeow.AutoMeowConfig", "PlayCatNoises", false)
                    .with("emotes.ChatEmoteConfig", "Enabled", false)) {
                run(c, () -> Mod.setField("automeow.AutoMeowFeature", "lastReplyAtMs", 0L));
                // Own line: never a trigger (AutoMeowFeature :93-96).
                c.hx().chat("§9Party §8> §b[MVP§c+§b] " + self(c) + "§f: meow");
                assertNoCommand(c, 20, "pc ");
                run(c, () -> Mod.setField("automeow.AutoMeowFeature", "lastReplyAtMs", 0L));
                send(c, "chat.party-meow");
                String cmd = awaitCommand(c, "pc ", 40);
                List<String> all = c.onClient(mc -> List.copyOf((List<String>) Mod.field("automeow.AutoMeowLines", "ALL")));
                c.check(all.contains(cmd.substring(3)), "/" + cmd + " is not an AutoMeow line");
                c.note("own 'meow' ignored; HxMateA's party 'meow' -> /" + cmd);
            }
        });
    }

    // ---- 130 leapmessage ------------------------------------------------------------------------------------------

    private static void leapMessage(Session s) {
        s.test("130-hx-leapmessage", c -> {
            try (Settings set = new Settings(c).with("leapmessage.LeapMessageConfig", "Enabled", true)
                    .with("leapmessage.LeapMessageConfig", "LeapingToEnabled", true)
                    .with("leapmessage.LeapMessageConfig", "CringeEnabled", false)
                    .with("leapmessage.LeapMessageConfig", "CustomMessage", "Hx leaping to {name}")
                    .with("emotes.ChatEmoteConfig", "Enabled", false)) {
                send(c, "hostile.allchat-leap");
                assertNoCommand(c, 20, "pc ");
                send(c, "chat.leap-teleported");
                String cmd = awaitCommand(c, "pc ", 40);
                c.check(cmd.equals("pc Hx leaping to HxMateA"), "/" + cmd);
                c.note("forged all-chat leap ignored; real leap line -> /" + cmd);
            }
        });
    }

    // ---- 131 players ----------------------------------------------------------------------------------------------

    private static void players(Session s) {
        s.test("131-hx-players-names", c -> {
            dungeon(c);
            c.ctx().waitTicks(10);
            UUID self = c.onClient(mc -> mc.player.getUUID());
            UUID found = c.onClient(mc -> (UUID) Mod.staticCall("players.PlayerNames", "uuidFor", mc.player.getGameProfile().name()));
            c.check(self.equals(found), "PlayerNames.uuidFor(self) = " + found + ", not " + self);
            String back = c.onClient(mc -> (String) Mod.staticCall("players.PlayerNames", "nameFor", self));
            c.check(self(c).equals(back), "nameFor(self uuid) = " + back);
            // Tab DISPLAY names ("[42] HxMateA (Mage L)") are not profiles: they must not resolve.
            Object mate = c.onClient(mc -> Mod.staticCall("players.PlayerNames", "uuidFor", "HxMateA"));
            c.check(mate == null, "a tab display name resolved to a UUID: " + mate);
            Object fake = c.onClient(mc -> Mod.staticCall("players.PlayerNames", "uuidFor", "!A-a"));
            c.note("self <-> uuid both ways; display-only HxMateA -> null; the fake column profile '!A-a' -> " + fake
                    + " (Hypixel's fake entries are v2 UUIDs, which noteKnown skips; the Hx tab uses v3 - see docs/requests/hx.md)");
        });
    }

    // ---- 132 social: friends list sync through the /fl responder -----------------------------------------------------

    @SuppressWarnings("unchecked")
    private static void friendsList(Session s) {
        s.test("132-hx-friendslist-paged-fl", c -> {
            String sep = text(fx(c, "chat.fl-separator"));
            // Page 1 as ONE multi-line message (FriendsListSync :213-218 handles a whole block per message):
            // separator, header with the '>>' click event (fixture chat.fl-header-page1), two friends, separator.
            JsonArray header1 = fx(c, "chat.fl-header-page1").payload().getAsJsonObject().getAsJsonArray("json");
            JsonArray page1 = new JsonArray();
            page1.add("");
            page1.add(sep + "\n");
            for (int i = 1; i < header1.size(); i++) {
                page1.add(header1.get(i).deepCopy());
            }
            page1.add("\n" + text(fx(c, "chat.fl-friend-online")) + "\n" + text(fx(c, "chat.fl-friend-offline")) + "\n" + sep);
            JsonArray header2 = fx(c, "chat.fl-header-page2").payload().getAsJsonObject().getAsJsonArray("json");
            JsonArray page2 = new JsonArray();
            page2.add("");
            page2.add(sep + "\n");
            for (int i = 1; i < header2.size(); i++) {
                page2.add(header2.get(i).deepCopy());
            }
            page2.add("\n" + text(fx(c, "chat.fl-friend-page2")) + "\n" + sep);
            // A forged header from another player arrives first; it must not be read as the block.
            JsonObject forged = new JsonObject();
            forged.addProperty("text", text(fx(c, "hostile.allchat-fl-header")));
            JsonObject p1 = new JsonObject();
            p1.add("textJson", page1);
            JsonObject p2 = new JsonObject();
            p2.add("textJson", page2);
            try {
                respond(c, "fl", "fl", forged, p1);
                respond(c, "friend", "friend list 2", p2);
                boolean started = c.onClient(mc -> (Boolean) Mod.staticCall("social.FriendsListSync", "requestSync", true));
                c.check(started, "FriendsListSync.requestSync refused to start");
                c.waitUntil("the sync to walk both pages and finish",
                        mc -> !(Boolean) Mod.staticCall("social.FriendsListSync", "isSyncing"), 300);
                List<String> cmds = c.commands();
                c.check(cmds.contains("fl") && cmds.contains("friend list 2"), "pages requested: " + cmds);
                List<String> names = c.onClient(mc -> {
                    List<String> out = new java.util.ArrayList<>();
                    for (Object f : (List<Object>) Mod.call(Mod.cfg("social.FriendsListConfig"), "friends")) {
                        out.add((String) Mod.field(f, "name"));
                    }
                    return out;
                });
                c.check(names.contains("HxFriendA") && names.contains("HxFriendB") && names.contains("HxFriendC"),
                        "synced friends " + names);
                c.check(!names.contains("HxEvil"), "a forged line added HxEvil: " + names);
                boolean truncated = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg("social.FriendsListConfig"), "isLastSyncTruncated"));
                c.check(!truncated, "sync marked incomplete");
                c.note("/fl -> page 1 (forged header ignored) -> '>>' click /friend list 2 -> page 2; friends " + names);
            } finally {
                c.hx().call("surface.respond.clear", "root", "fl");
                c.hx().call("surface.respond.clear", "root", "friend");
            }
        });
    }

    // ---- 133 namechanger -----------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static void nameChanger(Session s) {
        s.test("133-hx-namechanger", c -> {
            dev.testkit.gametest.TestEnemy bot = null;
            try (Settings set = new Settings(c).with("namechanger.NameChangerConfig", "Enabled", true)
                    .with("namechanger.NameChangerConfig", "OwnNameEnabled", true)
                    .with("namechanger.NameChangerConfig", "OwnDisplayName", "HxNick")
                    .with("namechanger.NameChangerConfig", "RandomizeOthers", true)) {
                dungeon(c);
                double[] at = c.scenario().playerPosition();
                bot = dev.testkit.gametest.TestEnemy.named("Nm", "B").at(at[0] - 2, at[1], at[2]).frozen(true)
                        .spawn(c.ctx(), c.server());
                String self = self(c);
                c.waitUntil("NameReplacer paints the own name as HxNick", mc -> {
                    String out = (String) Mod.staticCall("namechanger.NameReplacer", "replace", "hello " + self + "!");
                    return out != null && out.contains("HxNick") && !out.contains(self);
                }, 60);
                c.waitUntil("randomize-others collected the bot player (premise)", mc -> {
                    Object set2 = Mod.field("namechanger.NameChangerFeature", "SEEN_PLAYERS");
                    synchronized (set2) {
                        return !((java.util.Set<String>) set2).isEmpty();
                    }
                }, 80);
                java.util.Set<String> seen = c.onClient(mc -> {
                    Object set2 = Mod.field("namechanger.NameChangerFeature", "SEEN_PLAYERS");
                    synchronized (set2) {
                        return java.util.Set.copyOf((java.util.Set<String>) set2);
                    }
                });
                c.check(seen.stream().noneMatch(n -> n.startsWith("!")), "fake tab columns were collected as players: " + seen);
                c.note("own name -> HxNick in painted text; randomize-others saw " + seen + " (no '!' columns)");
            } finally {
                if (bot != null) {
                    bot.remove();
                }
            }
        });
    }

    // ---- 134 lagdisplay --------------------------------------------------------------------------------------------

    private static void lagDisplay(Session s) {
        s.test("134-hx-lagdisplay-ping", c -> {
            int own = c.onClient(mc -> {
                PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
                return info == null ? -2 : info.getLatency();
            });
            int ping = c.onClient(mc -> ((Number) Mod.staticCall("lagdisplay.LagDisplayFeature", "ping")).intValue());
            c.check(ping == own && ping >= 0, "LagDisplay ping " + ping + " vs own tab latency " + own);
            // Fake tab entries with a huge latency are someone else's row, not ours.
            JsonArray entries = new JsonArray();
            for (int i = 0; i < 5; i++) {
                JsonObject e = new JsonObject();
                e.addProperty("text", "§r[42] §r§aHxLag" + i + " §r§f(§r§dMage L§r§f)");
                e.addProperty("latency", 9999);
                entries.add(e);
            }
            c.hx().call("tab.set", Hx.args("entries", entries));
            c.ctx().waitTicks(10);
            int after = c.onClient(mc -> ((Number) Mod.staticCall("lagdisplay.LagDisplayFeature", "ping")).intValue());
            c.check(after == own, "fake rows with latency 9999 changed the ping to " + after);
            c.note("ping() == own tab latency (" + own + " ms); 9999-ms fake rows ignored");
        });
    }
}
