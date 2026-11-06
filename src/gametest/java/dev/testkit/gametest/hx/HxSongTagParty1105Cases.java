package dev.testkit.gametest.hx;

import com.google.gson.JsonParser;
import dev.testkit.gametest.TestEnemy;
import dev.testkit.gametest.mod.Mod;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.CameraType;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.UUID;
import static dev.testkit.gametest.hx.HxKit.*;

/**
 * spotshare-1105 (killer560, 2026-11-05: "my friend turned on show others songs in the spotify setting yet still didnt see
 * my lyrics above my head"). His log: autowd joined his party, party-warped, and the two games then asked the relay for
 * different rooms (party:57ea on his side, a lobby room on autowd's) for most of the song, so the lyric packets never
 * reached autowd. The cause: PartyTracker forgot the party on every ClientPlayConnectionEvents.JOIN, and Hypixel fires
 * JOIN on each server switch (warp, party warp, island) over the SAME connection, while the party lives on.
 * <ul>
 *   <li>1105-hx-songtag-party-warp: Mod Chat at its default (on, Party mode), Show Others' Songs on, in a party with the
 *       bot. A same-connection JOIN (what a Hypixel warp fires) keeps the party room (fails on f76d17ccc: the room went to
 *       the lobby), the relay stays in it, and the bot's shared lyric is drawn above its head (render state and pixels);
 *       with Show Others' Songs off it is not.</li>
 * </ul>
 */
final class HxSongTagParty1105Cases {
    private HxSongTagParty1105Cases() { }

    private static final String TAGC = "spotify.SongShareConfig";
    private static final String RS = "spotify.RemoteSongs";
    private static final String SF = "supporters.SupportersFeature";
    private static final String PT = "leapmenu.PartyTracker";

    static void register(Session s) {
        s.test("1105-hx-songtag-party-warp", HxSongTagParty1105Cases::partyWarp);
        s.test("1106-hx-songtag-lobby-party", HxSongTagParty1105Cases::lobbyParty);
    }

    /**
     * 1106-hx-songtag-lobby-party (killer560, 2026-11-06: "it should work for people not in the party too. anyone in the
     * same lobby should be able to see it"). This client shares a song and is partied with a third player (Mod Chat at its
     * default, Party mode), so its main relay socket sits in the party room. A second mod user stands next to it in the
     * same lobby, NOT in the party. Checks: the client also keeps Song Tag's lobby link (RelayTap) in the lobby room, its
     * song goes out there (so the unpartied player receives it), and the unpartied player's lyric reaches this client
     * through it and is drawn over his head (render state and pixels). Fails on 4b10cd322 and f76d17ccc: no lobby link,
     * the song only reached the party room.
     */
    private static void lobbyParty(Session c) throws Exception {
        HxSpotifyDocCases.noParty(c);
        double[] at = c.scenario().playerPosition();
        HxCosmeticNameCases.camera(c, CameraType.FIRST_PERSON);
        HxCosmeticNameCases.look(c, 0f, -12f);
        HxCosmeticNameCases.clearChat(c);
        TestEnemy mate = TestEnemy.named("Pm", "N").at(at[0] + 8, at[1], at[2] - 8).frozen(true).spawn(c.ctx(), c.server());
        TestEnemy other = TestEnemy.named("Lb", "N").at(at[0], at[1], at[2] + 5).frozen(true).spawn(c.ctx(), c.server());
        String mateName = mate.name();
        String otherName = other.name();
        Object savedMap = c.onClient(mc -> Mod.field(SF, "byUuid"));
        FakeRelay party = null;
        FakeRelay lobby = null;
        try (Settings s = HxSpotifyDocCases.noData(new Settings(c)).with("modchat.ModChatConfig", "Enabled", true)
                .with("modchat.ModChatConfig", "RoomMode", Mod.enumValue("relay.RelayRoom$Mode", "PARTY"))
                .with(TAGC, "ShareSong", true).with(TAGC, "ShowOthers", true).with("modusers.ModUserConfig", "Share", true)
                .with("supporters.SupportersConfig", "ShowSupporterNames", false)
                .with("namechanger.NameChangerConfig", "RandomizeOthers", false).with(TAGC, "CustomF5Colors", false)
                .custom(() -> {
                    Mod.setField("relay.HypixelLocation", "lastLevel", net.minecraft.client.Minecraft.getInstance().level);
                    Mod.setField("relay.HypixelLocation", "serverId", "mini1106LB");
                }, () -> Mod.setField("relay.HypixelLocation", "serverId", null))) {
            UUID self = c.onClient(mc -> mc.player.getUUID());
            String selfName = c.onClient(mc -> mc.player.getGameProfile().name());
            UUID otherId = HxSpotifyDocCases.botUuid(c, otherName);
            c.check(otherId != null, "the lobby player is not in the world (premise)");
            HxSpotifyDocCases.supporters(c, self, otherId);
            c.onClient(mc -> {
                Mod.staticCall(RS, "clear");
                Mod.setField("spotify.SongShare", "testNowPlaying", Mod.staticCall("spotify.NowPlaying", "track", "Artist", "Title"));
                return null;
            });
            c.hx().chat("§eYou have joined §b[MVP§c+§b] " + mateName + "'s §eparty!");
            c.waitUntil("PartyTracker lists the mate", mc -> ((List<?>) Mod.staticCall(PT, "teammates")).contains(mateName), 60);
            c.waitUntil("the party room wanted", mc -> FakeRelay.wantedRoom().startsWith("party:"), 100);
            party = FakeRelay.install(List.of(selfName, mateName));
            c.ctx().waitTicks(50); // past the next room check (every 40 ticks)
            String tapRoom = c.onClient(mc -> FakeRelay.wantedTapRoom());
            c.note("partied with " + mateName + ": main room '" + party.room() + "', Song Tag lobby link wants '" + tapRoom + "'");
            c.check(tapRoom.startsWith("lobby:"), "partied (Mod Chat in Party mode), Song Tag keeps no lobby room: '" + tapRoom
                    + "', so no one outside the party sees the song");
            lobby = FakeRelay.installTap(List.of(selfName, otherName));
            FakeRelay tap = lobby;
            FakeRelay main = party;

            // Out: the song reaches the lobby room (the unpartied player) as well as the party room.
            c.waitUntil("the song sent into the lobby room", mc -> !tap.sentData("np.v1").isEmpty(), 200);
            boolean hello = false;
            for (com.google.gson.JsonObject p : tap.sent()) {
                hello |= "hello".equals(p.has("type") ? p.get("type").getAsString() : "");
            }
            com.google.gson.JsonObject out = tap.sentData("np.v1").get(0);
            c.note("into the lobby room: " + out + "; hello " + hello + "; into the party room: " + main.sentData("np.v1").size());
            c.check(hello, "the lobby link sent no hello, so the lobby cannot see this player as a mod user");
            c.check("Title".equals(out.has("title") ? out.get("title").getAsString() : null), "the lobby got no song: " + out);
            c.waitUntil("the song also in the party room", mc -> !main.sentData("np.v1").isEmpty(), 200);
            c.check(tap.sent().stream().noneMatch(p -> p.has("type") && "chat".equals(p.get("type").getAsString())),
                    "the lobby link carried a chat line");

            // In: the unpartied player's lyric through the lobby room, drawn over his head.
            tap.presence("mod", otherName, "legit");
            HxCosmeticNameCases.look(c, 0f, -12f);
            tap.deliver(otherName, otherId, "np.v1", JsonParser.parseString("{\"state\":\"play\",\"artist\":\"Band\",\"title\":\"Tune\"}"));
            c.ctx().waitTicks(3);
            BufferedImage songOnly = HxCosmeticNameCases.shot(c, "lobby-party-song-only");
            String song = HxSpotifyDocCases.botState(c, otherName, "killer560smod$songTag");
            c.note("after the lobby song: last reject '" + c.onClient(mc -> Mod.field(RS, "lastReject")) + "', lobby mod users "
                    + c.onClient(mc -> String.valueOf(Mod.field("relay.RelayTap", "MODS"))) + ", tap connected "
                    + c.onClient(mc -> Mod.staticCall("relay.RelayTap", "isConnected")));
            c.check("♪ Tune - Band".equals(song), "the unpartied player's song drew no line: " + song);
            c.ctx().waitTicks(12);
            tap.deliver(otherName, otherId, "np.v1", JsonParser.parseString("{\"state\":\"play\",\"artist\":\"Band\",\"title\":\"Tune\","
                    + "\"line\":\"lobby lyric line\",\"into\":100,\"words\":[0,1500,3000]}"));
            c.ctx().waitTicks(2);
            String lyric = HxSpotifyDocCases.botState(c, otherName, "killer560smod$lyricTag");
            BufferedImage withLyric = HxCosmeticNameCases.shot(c, "lobby-party-song-and-lyric");
            int[] rows = HxCosmeticNameCases.tagAndHead(songOnly, withLyric);
            String scope = c.onClient(mc -> (String) Mod.staticCall("spotify.SongShare", "receiveScope"));
            c.note("lyric over the unpartied player: '" + lyric + "'; rows " + rows[0] + "-" + rows[1] + " added; hover: " + scope);
            c.check("♫ lobby lyric line".equals(lyric), "the unpartied player's lyric is not drawn: '" + lyric + "'");
            c.check(rows[0] < rows[1], "the screenshot shows no lyric line over the unpartied player");
            c.check(scope.contains("this lobby, partied or not, and in your party"), "the hover does not say lobby and party: " + scope);
        } finally {
            if (lobby != null) {
                lobby.close();
            }
            if (party != null) {
                party.close();
            }
            c.onClient(mc -> {
                Mod.setField("spotify.SongShare", "testNowPlaying", null);
                Mod.staticCall(RS, "clear");
                Mod.setField(SF, "byUuid", savedMap);
                Mod.staticCall(PT, "clearParty");
                return null;
            });
            mate.remove();
            other.remove();
        }
        c.ctx().waitTicks(30);
    }

    private static String wantRoom(Session c) {
        return c.onClient(mc -> FakeRelay.wantedRoom());
    }

    private static void partyWarp(Session c) throws Exception {
        HxSpotifyDocCases.noParty(c);
        double[] at = c.scenario().playerPosition();
        HxCosmeticNameCases.camera(c, CameraType.FIRST_PERSON);
        HxCosmeticNameCases.look(c, 0f, -12f);
        HxCosmeticNameCases.clearChat(c);
        TestEnemy bot = TestEnemy.named("Pw", "N").at(at[0], at[1], at[2] + 5).frozen(true).spawn(c.ctx(), c.server());
        String botName = bot.name();
        Object savedMap = c.onClient(mc -> Mod.field(SF, "byUuid"));
        FakeRelay relay = null;
        try (Settings s = HxSpotifyDocCases.noData(new Settings(c)).with("modchat.ModChatConfig", "Enabled", true)
                .with("modchat.ModChatConfig", "RoomMode", Mod.enumValue("relay.RelayRoom$Mode", "PARTY"))
                .with(TAGC, "ShareSong", false).with(TAGC, "ShowOthers", true)
                .with("supporters.SupportersConfig", "ShowSupporterNames", false)
                .with("namechanger.NameChangerConfig", "RandomizeOthers", false).with(TAGC, "CustomF5Colors", false)) {
            UUID id = HxSpotifyDocCases.botUuid(c, botName);
            c.check(id != null, "the bot is not in the world (premise)");
            c.hx().chat("§eYou have joined §b[MVP§c+§b] " + botName + "'s §eparty!");
            c.waitUntil("PartyTracker lists the bot", mc -> ((List<?>) Mod.staticCall(PT, "teammates")).contains(botName), 60);
            c.waitUntil("the party room wanted", mc -> FakeRelay.wantedRoom().startsWith("party:"), 100);
            String party = wantRoom(c);
            relay = FakeRelay.install(List.of(c.onClient(mc -> mc.player.getGameProfile().name()), botName));
            c.note("in a party with " + botName + ": room '" + party + "', relay faked there");

            // What Hypixel does on a warp: JOIN again, same Connection.
            c.onClient(mc -> {
                ClientPlayConnectionEvents.JOIN.invoker().onPlayReady(mc.getConnection(), ClientPlayNetworking.getSender(), mc);
                return null;
            });
            c.ctx().waitTicks(50); // past the next room check (every 40 ticks)
            String after = wantRoom(c);
            List<?> mates = c.onClient(mc -> (List<?>) Mod.staticCall(PT, "teammates"));
            Object active = c.onClient(mc -> Mod.field("relay.RelayClient", "activeRoom"));
            boolean connected = c.onClient(mc -> (Boolean) Mod.staticCall("relay.RelayClient", "isConnected"));
            c.note("after a same-connection JOIN: mates " + mates + ", wanted room '" + after + "', active '" + active
                    + "', connected " + connected);
            c.check(mates.contains(botName), "a server switch (same-connection JOIN) forgot the party: " + mates);
            c.check(party.equals(after), "the relay room moved off the party room on a warp: " + party + " -> " + after);
            c.check(connected && party.equals(String.valueOf(active)), "the relay left the party room: active " + active);

            // The bot shares a lyric through the room.
            HxSpotifyDocCases.supporters(c, id);
            c.onClient(mc -> {
                Mod.staticCall(RS, "clear");
                Mod.staticCall("modusers.ModUsers", "onPresence", botName, "legit");
                return null;
            });
            HxCosmeticNameCases.look(c, 0f, -12f);
            relay.deliver(botName, id, "np.v1", JsonParser.parseString("{\"state\":\"play\",\"artist\":\"Band\",\"title\":\"Tune\"}"));
            c.ctx().waitTicks(3);
            BufferedImage songOnly = HxCosmeticNameCases.shot(c, "party-warp-song-only");
            c.check("♪ Tune - Band".equals(HxSpotifyDocCases.botState(c, botName, "killer560smod$songTag")),
                    "the song from the party room drew no song line: " + HxSpotifyDocCases.botState(c, botName, "killer560smod$songTag"));
            c.ctx().waitTicks(12);
            relay.deliver(botName, id, "np.v1", JsonParser.parseString("{\"state\":\"play\",\"artist\":\"Band\",\"title\":\"Tune\","
                    + "\"line\":\"party lyric line\",\"into\":100,\"words\":[0,1500,3000]}"));
            c.ctx().waitTicks(2);
            String lyric = HxSpotifyDocCases.botState(c, botName, "killer560smod$lyricTag");
            BufferedImage withLyric = HxCosmeticNameCases.shot(c, "party-warp-song-and-lyric");
            int[] rows = HxCosmeticNameCases.tagAndHead(songOnly, withLyric);
            c.note("lyric over the bot: '" + lyric + "'; screenshot rows " + rows[0] + "-" + rows[1] + " added");
            c.check("♫ party lyric line".equals(lyric), "the bot's shared lyric is not drawn: '" + lyric + "'");
            c.check(rows[0] < rows[1], "the screenshot shows no lyric line over the bot");

            // Show Others' Songs off: nothing.
            c.onClient(mc -> {
                Mod.set(TAGC, "setShowOthers", false);
                return null;
            });
            c.ctx().waitTicks(2);
            String off = HxSpotifyDocCases.botState(c, botName, "killer560smod$lyricTag");
            c.check(off == null, "Show Others' Songs off still draws the lyric: '" + off + "'");
        } finally {
            if (relay != null) {
                relay.close();
            }
            c.onClient(mc -> {
                Mod.staticCall("modusers.ModUsers", "onPresence", botName, null);
                Mod.staticCall(RS, "clear");
                Mod.setField(SF, "byUuid", savedMap);
                Mod.staticCall(PT, "clearParty");
                return null;
            });
            bot.remove();
        }
        c.ctx().waitTicks(30);
    }
}
