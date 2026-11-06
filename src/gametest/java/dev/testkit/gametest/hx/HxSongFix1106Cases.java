package dev.testkit.gametest.hx;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.testkit.gametest.TestEnemy;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.CameraType;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import static dev.testkit.gametest.hx.HxKit.*;

/**
 * songfix-1106 (killer560, 2026-11-06: "the sharing still didnt work. also my name didnt show bold for his screen either";
 * "that account doesnt even see my name as orange"; "all non supporters should be able to see supporter feautres"; "it
 * should instantly share"). The cause: 26.3 Default booted on his account (the mod's account switcher then moved it to the
 * alt), its startup supporter check found him linked and pushed Default's EMPTY cosmetics, which was a DELETE of his
 * supporter entry. Off the list, every viewer dropped his song ("not a supporter") and drew his plain name. Bold was never
 * shared (the push sent the name without {@code &l}).
 * <ul>
 *   <li>1106-hx-songfix-viewer: a NON-supporter viewer at default settings (Share My Song off, Show Me As A Mod User off,
 *       Mod Chat at its default) joins the lobby; a supporter there shares a song the moment it hears the join. The song
 *       is drawn over him within 40 ticks (render state and pixels), and his supporter name ({@code &6&l}) is drawn orange
 *       and bold (render state style and orange pixels).</li>
 *   <li>1106-hx-songfix-sender: a supporter with Share My Song on sends the song already playing into the lobby room
 *       within 40 ticks of joining it, with no track change.</li>
 *   <li>1106-hx-songfix-noclear: an instance with nothing to share never clears the shared entry (the 26.3 Default wipe;
 *       fails on d1ca001e7: "DELETE"), Reset Cosmetics still can, an account swap forgets the linked state, and Bold Name
 *       travels as {@code &l}.</li>
 * </ul>
 */
final class HxSongFix1106Cases {
    private HxSongFix1106Cases() { }

    private static final String TAGC = "spotify.SongShareConfig";
    private static final String RS = "spotify.RemoteSongs";
    private static final String SF = "supporters.SupportersFeature";
    private static final String SA = "supporters.SupportersAutoShare";
    private static final String SS = "supporters.SupportersSelfService";
    private static final String SC = "supporters.SupportersConfig";
    private static final String NC = "namechanger.NameChangerConfig";
    private static final String MU = "modusers.ModUserConfig";
    private static final Consumer<String> NO_ERROR = e -> { };

    static void register(Session s) {
        s.test("1106-hx-songfix-viewer", HxSongFix1106Cases::viewer);
        s.test("1106-hx-songfix-sender", HxSongFix1106Cases::sender);
        s.test("1106-hx-songfix-noclear", HxSongFix1106Cases::noClear);
    }

    /** The lobby room, Mod Chat at its default (on, Party mode) with no party. */
    private static Settings lobby(Session c) {
        return HxSpotifyDocCases.noData(new Settings(c)).with("modchat.ModChatConfig", "Enabled", true)
                .with("modchat.ModChatConfig", "RoomMode", Mod.enumValue("relay.RelayRoom$Mode", "PARTY"))
                .with("namechanger.NameChangerConfig", "RandomizeOthers", false).with(TAGC, "CustomF5Colors", false)
                .custom(() -> {
                    Mod.setField("relay.HypixelLocation", "lastLevel", net.minecraft.client.Minecraft.getInstance().level);
                    Mod.setField("relay.HypixelLocation", "serverId", "mini1106SF");
                }, () -> Mod.setField("relay.HypixelLocation", "serverId", null));
    }

    /** The supporter list as the relay serves it: {@code ids[i]} with name {@code names[i]}. */
    private static void supporters(Session c, UUID[] ids, String[] names) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < ids.length; i++) {
            b.append(i == 0 ? "" : ",").append("{\"uuid\":\"").append(ids[i]).append("\",\"name\":\"").append(names[i])
                    .append("\",\"scale\":1}");
        }
        String list = b.append("]").toString();
        c.onClient(mc -> {
            Mod.staticCall(SF, "apply", 12, Mod.staticCall("supporters.SupportersFetcher", "parseSupporters",
                    JsonParser.parseString(list)), false);
            return null;
        });
    }

    /** The bot's drawn name tag: its text, and "bold" / "orange" when every visible letter is. */
    private static String[] nameStyle(Session c, String botName) {
        return c.onClient(mc -> {
            for (Player p : mc.level.players()) {
                if (p.getGameProfile().name().equals(botName)) {
                    EntityRenderState state = mc.getEntityRenderDispatcher().extractEntity(p, 1f);
                    Component tag = state.nameTag;
                    if (tag == null) {
                        return new String[]{"(no tag)", "", ""};
                    }
                    boolean[] bold = {true}, orange = {true};
                    int[] letters = {0};
                    tag.getVisualOrderText().accept((i, st, cp) -> {
                        if (!Character.isWhitespace(cp)) {
                            letters[0]++;
                            bold[0] &= st.isBold();
                            orange[0] &= st.getColor() != null && st.getColor().getValue() == 0xFFAA00;
                        }
                        return true;
                    });
                    return new String[]{tag.getString(), letters[0] > 0 && bold[0] ? "bold" : "not bold",
                            letters[0] > 0 && orange[0] ? "orange" : "not orange"};
                }
            }
            return new String[]{"(no bot)", "", ""};
        });
    }

    private static int orangePixels(BufferedImage img) {
        int n = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int p = img.getRGB(x, y), r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
                if (r >= 200 && g >= 120 && g <= 190 && b <= 60) {
                    n++;
                }
            }
        }
        return n;
    }

    private static void viewer(Session c) throws Exception {
        HxSpotifyDocCases.noParty(c);
        double[] at = c.scenario().playerPosition();
        HxCosmeticNameCases.camera(c, CameraType.FIRST_PERSON);
        HxCosmeticNameCases.look(c, 0f, -12f);
        HxCosmeticNameCases.clearChat(c);
        TestEnemy other = TestEnemy.named("Sf", "N").at(at[0], at[1], at[2] + 5).frozen(true).spawn(c.ctx(), c.server());
        String otherName = other.name();
        Object savedMap = c.onClient(mc -> Mod.field(SF, "byUuid"));
        FakeRelay relay = null;
        // The viewer at the defaults of a first download: not a supporter, sharing nothing, not shown as a mod user.
        try (Settings s = lobby(c).with(TAGC, "ShareSong", false).with(TAGC, "ShowOthers", true).with(MU, "Share", false)
                .with(MU, "SeeOthers", false).with(SC, "ShowSupporterNames", true)) {
            UUID self = c.onClient(mc -> mc.player.getUUID());
            String selfName = c.onClient(mc -> mc.player.getGameProfile().name());
            UUID otherId = HxSpotifyDocCases.botUuid(c, otherName);
            c.check(otherId != null, "the sender is not in the world (premise)");
            supporters(c, new UUID[0], new String[0]);
            c.onClient(mc -> {
                Mod.staticCall(RS, "clear");
                Mod.staticCall("players.PlayerNames", "noteKnown", otherId, otherName);
                return null;
            });
            c.ctx().waitTicks(3);
            BufferedImage plain = HxCosmeticNameCases.shot(c, "songfix-viewer-plain");
            String[] plainStyle = nameStyle(c, otherName);
            supporters(c, new UUID[]{otherId}, new String[]{"&6&l" + otherName});
            c.check(!c.onClient(mc -> (Boolean) Mod.staticCall(SF, "isSupporter", self)), "the viewer is a supporter (premise)");
            c.waitUntil("the lobby room wanted", mc -> FakeRelay.wantedRoom().startsWith("lobby:"), 100);
            relay = FakeRelay.install(List.of(selfName, otherName));
            FakeRelay room = relay;
            // The sender hears the join and sends at once (its SongShare resends on presence join).
            room.presence("mod", otherName, "cheat");
            room.deliver(otherName, otherId, "np.v1", JsonParser.parseString("{\"state\":\"play\",\"artist\":\"Band\",\"title\":\"Tune\"}"));
            String song = null;
            int ticks = 0;
            for (; ticks <= 40; ticks++) {
                song = HxSpotifyDocCases.botState(c, otherName, "killer560smod$songTag");
                if ("♪ Tune - Band".equals(song)) {
                    break;
                }
                c.ctx().waitTicks(1);
            }
            c.note("song over the supporter " + ticks + " ticks after the join: '" + song + "'; last reject '"
                    + c.onClient(mc -> Mod.field(RS, "lastReject")) + "'");
            c.check("♪ Tune - Band".equals(song), "a non-supporter at default settings does not see a supporter's song within"
                    + " 40 ticks of joining: '" + song + "' (last reject " + c.onClient(mc -> Mod.field(RS, "lastReject")) + ")");
            HxCosmeticNameCases.look(c, 0f, -12f);
            c.ctx().waitTicks(3);
            BufferedImage styled = HxCosmeticNameCases.shot(c, "songfix-viewer-supporter");
            int[] rows = HxCosmeticNameCases.tagAndHead(styled, plain);
            String[] style = nameStyle(c, otherName);
            int orangeBefore = orangePixels(plain), orangeAfter = orangePixels(styled);
            c.note("name tag before: '" + plainStyle[0] + "' " + plainStyle[1] + ", " + plainStyle[2] + "; as a supporter: '"
                    + style[0] + "' " + style[1] + ", " + style[2] + "; orange pixels " + orangeBefore + " -> " + orangeAfter
                    + "; rows " + rows[0] + "-" + rows[1] + " added");
            c.check(rows[0] < rows[1], "the screenshot shows no song line over the supporter");
            c.check(style[0].contains(otherName), "the supporter's name tag is not his name: '" + style[0] + "'");
            c.check("bold".equals(style[1]), "a non-supporter does not see the supporter's name bold: " + style[1]);
            c.check("orange".equals(style[2]), "a non-supporter does not see the supporter's name orange: " + style[2]);
            c.check(orangeAfter - orangeBefore >= 20, "the screenshot shows no orange name: " + orangeBefore + " -> " + orangeAfter);
        } finally {
            if (relay != null) {
                relay.close();
            }
            c.onClient(mc -> {
                Mod.staticCall(RS, "clear");
                Mod.setField(SF, "byUuid", savedMap);
                return null;
            });
            other.remove();
        }
        c.ctx().waitTicks(30);
    }

    private static void sender(Session c) throws Exception {
        HxSpotifyDocCases.noParty(c);
        Object savedMap = c.onClient(mc -> Mod.field(SF, "byUuid"));
        FakeRelay relay = null;
        try (Settings s = lobby(c).with(TAGC, "ShareSong", true).with(TAGC, "ShowOthers", true).with(MU, "Share", true)) {
            UUID self = c.onClient(mc -> mc.player.getUUID());
            String selfName = c.onClient(mc -> mc.player.getGameProfile().name());
            supporters(c, new UUID[]{self}, new String[]{"&6&l" + selfName});
            c.onClient(mc -> {
                Mod.setField("spotify.SongShare", "testNowPlaying", Mod.staticCall("spotify.NowPlaying", "track", "Artist", "Title"));
                return null;
            });
            c.ctx().waitTicks(20); // the song was already playing before the join
            c.waitUntil("the lobby room wanted", mc -> FakeRelay.wantedRoom().startsWith("lobby:"), 100);
            relay = FakeRelay.install(List.of(selfName, "Somebody"));
            FakeRelay room = relay;
            int ticks = 0;
            for (; ticks <= 40 && room.sentData("np.v1").isEmpty(); ticks++) {
                c.ctx().waitTicks(1);
            }
            List<JsonObject> out = room.sentData("np.v1");
            c.note("song into the lobby room " + ticks + " ticks after joining: " + out + "; why not: "
                    + c.onClient(mc -> Mod.staticCall("spotify.SongShare", "notSentReason")));
            c.check(!out.isEmpty(), "the playing song was not sent within 40 ticks of joining the lobby room");
            c.check(!out.isEmpty() && "Title".equals(out.get(0).has("title") ? out.get(0).get("title").getAsString() : null),
                    "the first song packet is not the playing song: " + out);
        } finally {
            if (relay != null) {
                relay.close();
            }
            c.onClient(mc -> {
                Mod.setField("spotify.SongShare", "testNowPlaying", null);
                Mod.setField(SF, "byUuid", savedMap);
                return null;
            });
        }
        c.ctx().waitTicks(30);
    }

    /** One shareCurrent with the throttle cleared; the attempt it made, or "(none)". */
    private static String push(Session c) {
        return c.onClient(mc -> {
            Mod.setField(SA, "lastPushAtMs", -1L);
            int before = (Integer) Mod.staticCall(SS, "attempts");
            Mod.staticCall(SA, "shareCurrent", NO_ERROR);
            int after = (Integer) Mod.staticCall(SS, "attempts");
            return after == before ? "(none)" : String.valueOf(Mod.staticCall(SS, "lastAttempt"));
        });
    }

    private static void noClear(Session c) throws Exception {
        boolean wasLinked = c.onClient(mc -> (Boolean) Mod.staticCall(SA, "isLinked"));
        Object savedMap = c.onClient(mc -> Mod.field(SF, "byUuid"));
        try (Settings s = new Settings(c).with(SC, "ShareIfSupporter", true).with(SC, "ShareTrail", false)
                .with(SC, "PlayAsMob", false).with(NC, "OwnDisplayName", "").with(NC, "OwnBold", false)) {
            // A fresh session on a supporter's account with default cosmetics (26.3 Default): linked, nothing to share.
            c.onClient(mc -> {
                try {
                    Mod.staticCall(SA, "accountChanged");
                } catch (AssertionError older) { // a jar before songfix-1106: forget the last push by hand
                    Mod.setField(SA, "lastPushedKey", null);
                }
                Mod.staticCall(SA, "setLinked", true);
                return null;
            });
            String empty = push(c);
            c.note("linked, nothing to share, nothing shared this session: " + empty);
            c.check("(none)".equals(empty), "an instance with nothing to share pushed '" + empty + "': it wipes the supporter"
                    + " entry his main instance shares");

            // Reset Cosmetics takes the entry back on purpose.
            c.onClient(mc -> Mod.staticCall(SA, "clearOnNextShare"));
            String reset = push(c);
            c.note("after Reset Cosmetics: " + reset);
            c.check("DELETE".equals(reset), "Reset Cosmetics no longer clears the shared entry: " + reset);

            // Bold Name travels as &l.
            String bold = c.onClient(mc -> {
                Mod.call(Mod.cfg(NC), "setOwnDisplayName", "HxBold");
                Mod.call(Mod.cfg(NC), "setOwnBold", true);
                return (String) Mod.staticCall(SA, "sharedPlainName");
            });
            String pushed = push(c);
            c.note("Bold Name on: shared name '" + bold + "', push " + pushed);
            c.check("&lHxBold".equals(bold), "Bold Name is not shared as &l: '" + bold + "'");
            c.check(pushed.startsWith("POST ") && pushed.contains("&lHxBold"), "the push does not carry the bold name: " + pushed);

            // A shared name, then the list comes back without this player (another instance cleared it): pushed again.
            c.onClient(mc -> {
                Mod.staticCall(SF, "apply", 13, Mod.staticCall("supporters.SupportersFetcher", "parseSupporters",
                        JsonParser.parseString("[]")), true);
                return null;
            });
            String again = push(c);
            c.note("own entry missing from the fetched list: " + again);
            c.check(again.startsWith("POST ") && again.contains("&lHxBold"), "a missing own entry is not put back: " + again);

            // An account swap forgets the old account's linked state and asks again.
            String[] swapped = c.onClient(mc -> {
                Mod.staticCall(SA, "accountChanged");
                return new String[]{String.valueOf(Mod.staticCall(SA, "isLinked")), String.valueOf(Mod.staticCall(SA, "startupCheckState"))};
            });
            c.note("after an account swap: linked " + swapped[0] + ", startup check " + swapped[1]);
            c.check("false".equals(swapped[0]) && "waiting".equals(swapped[1]), "an account swap kept the old account's state");
        } finally {
            c.onClient(mc -> {
                Mod.staticCall(SA, "setLinked", wasLinked);
                Mod.setField(SA, "lastPushedKey", null);
                Mod.setField(SF, "byUuid", savedMap);
                return null;
            });
        }
    }
}
