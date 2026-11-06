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
