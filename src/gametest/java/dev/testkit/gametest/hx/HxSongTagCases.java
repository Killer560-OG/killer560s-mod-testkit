package dev.testkit.gametest.hx;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.testkit.gametest.TestEnemy;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import java.awt.image.BufferedImage;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiPredicate;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Song Tag (mod branch song-tag, killer560 2026-10-24): a supporter's Spotify song drawn as its own line above their name
 * for other mod users, carried only by the relay's {@code data} packet {@code np.v1}. Offline there is no relay, so the
 * packet is handed to the mod's own receiver ({@code RemoteSongs.onRelayData}, the call {@code RelayClient.handle} makes
 * with the relay-stamped {@code from}/{@code uuid}) and the sender's packet is caught at {@code SongShare.testSender},
 * the one place it would go to {@code RelayClient.sendData}.
 * <ul>
 *   <li>820: receive. A bot who shows as a mod user but is NOT a supporter: his packet is dropped ("not a supporter"),
 *       nothing is kept, his render state carries no song and nothing new is drawn above his name (screenshot). Put in
 *       the supporter list, the same packet is kept, the render state carries "♪ Testsong - Testartist", the line is
 *       submitted, and the screenshot shows a new text run wholly ABOVE his name tag with a gap. Show Others' Songs OFF
 *       takes it away. Abuse: a non-mod-user, a bad name, no uuid, a non-object, an over-long value, an unknown state are
 *       dropped; § codes, bidi and zero-width characters are stripped; a slur hides the tag; a swear word is starred;
 *       50 packets in one go apply once; a packet stamped with another account's uuid never paints the bot; "off"
 *       clears it.</li>
 *   <li>820 also: send. With Share My Song and Show Me As A Mod User on but the player not a supporter, nothing is
 *       handed over; in the supporter list, {@code {"state":"play","artist":"Artist","title":"Title"}} goes out under key
 *       np.v1 and your own tag carries it; a pause sends {@code {"state":"off"}} once. Both switches default OFF, are
 *       saved, loaded and restored by a profile reload, and have tooltips.</li>
 * </ul>
 */
final class HxSongTagCases {

    private static final String CFG = "spotify.SongShareConfig";
    private static final String RS = "spotify.RemoteSongs";
    private static final String SH = "spotify.SongShare";
    private static final String MU = "modusers.ModUsers";
    private static final String MUC = "modusers.ModUserConfig";
    private static final String SF = "supporters.SupportersFeature";
    private static final String SC = "supporters.SupportersConfig";
    private static final String NC = "namechanger.NameChangerConfig";
    private static final String PLAY = "{\"state\":\"play\",\"artist\":\"Testartist\",\"title\":\"Testsong\"}";
    private static final String TAG = "♪ Testsong - Testartist";

    private HxSongTagCases() {
    }

    static void register(Session s) {
        s.test("820-hx-song-tag", HxSongTagCases::songTag);
    }

    private static JsonElement json(String s) {
        return JsonParser.parseString(s);
    }

    private static String dashless(UUID id) {
        return id.toString().replace("-", "");
    }

    /** Feeds one np.v1 packet as the relay would; returns the receiver's reject reason, or "" when it was kept. */
    private static String feed(Session c, String from, String uuid, String value) {
        return c.onClient(mc -> {
            long before = (Long) Mod.field(RS, "accepted");
            Mod.staticCall(RS, "onRelayData", from, uuid, json(value));
            return (Long) Mod.field(RS, "accepted") > before ? "" : (String) Mod.field(RS, "lastReject");
        });
    }

    private static String tagText(Session c, String name, UUID id) {
        return c.onClient(mc -> {
            Object t = Mod.staticCall(RS, "tagFor", name, id);
            return t == null ? null : ((Component) t).getString();
        });
    }

    /** The song line on the bot's render state this frame, or null. */
    private static String stateTag(Session c, String botName) {
        return c.onClient(mc -> {
            for (Player p : mc.level.players()) {
                if (p.getGameProfile().name().equals(botName)) {
                    EntityRenderState state = mc.getEntityRenderDispatcher().extractEntity(p, 1f);
                    Object t = Mod.call(state, "killer560smod$songTag");
                    return t == null ? null : ((Component) t).getString();
                }
            }
            return "(no bot)";
        });
    }

    private static boolean differs(BufferedImage a, BufferedImage b, int x, int y) {
        int p = a.getRGB(x, y), q = b.getRGB(x, y);
        int d = Math.abs(((p >> 16) & 255) - ((q >> 16) & 255)) + Math.abs(((p >> 8) & 255) - ((q >> 8) & 255))
                + Math.abs((p & 255) - (q & 255));
        if (d <= 30) {
            return false;
        }
        boolean cloudP = Math.max((p >> 16) & 255, Math.max((p >> 8) & 255, p & 255))
                - Math.min((p >> 16) & 255, Math.min((p >> 8) & 255, p & 255)) <= 25 && ((p >> 16) & 255) >= 200;
        boolean cloudQ = Math.max((q >> 16) & 255, Math.max((q >> 8) & 255, q & 255))
                - Math.min((q >> 16) & 255, Math.min((q >> 8) & 255, q & 255)) <= 25 && ((q >> 16) & 255) >= 200;
        return !cloudP && !cloudQ;
    }

    /** Rows above {@code limit} in the center band where {@code a} differs from {@code b} in at least 3 pixels. */
    private static int rowsChangedAbove(BufferedImage a, BufferedImage b, int limit) {
        int w = a.getWidth();
        int rows = 0;
        for (int y = 0; y < Math.min(limit, a.getHeight()); y++) {
            int n = 0;
            for (int x = w / 2 - w / 10; x < w / 2 + w / 10; x++) {
                if (differs(a, b, x, y)) {
                    n++;
                }
            }
            if (n >= 3) {
                rows++;
            }
        }
        return rows;
    }

    private static void songTag(Session c) throws Exception {
        c.check(c.onClient(mc -> Mod.has(RS) && Mod.has(SH) && Mod.has(CFG)), "this jar has no Song Tag (mod before song-tag)");
        double[] at = c.scenario().playerPosition();
        HxCosmeticNameCases.camera(c, CameraType.FIRST_PERSON);
        HxCosmeticNameCases.look(c, 0f, -12f);
        HxCosmeticNameCases.clearChat(c);
        BufferedImage base = HxCosmeticNameCases.shot(c, "no-bot");
        TestEnemy bot = TestEnemy.named("Ct", "N").at(at[0], at[1], at[2] + 5).frozen(true).spawn(c.ctx(), c.server());
        Object savedMap = c.onClient(mc -> Mod.field(SF, "byUuid"));
        // settings() reloads the config (a new instance), so the switches are put back by hand at the end too.
        boolean[] orig = c.onClient(mc -> new boolean[]{(Boolean) Mod.get(CFG, "isShareSong"), (Boolean) Mod.get(CFG, "isShowOthers")});
        String botName = bot.name();
        try (Settings s = new Settings(c).with(CFG, "ShowOthers", true).with(SC, "ShowSupporterNames", false)
                .with(NC, "RandomizeOthers", false)
                .custom(() -> Mod.setField(RS, "testConnected", true), () -> Mod.setField(RS, "testConnected", false))) {
            UUID botId = c.onClient(mc -> {
                for (Player p : mc.level.players()) {
                    if (p.getGameProfile().name().equals(botName)) {
                        return p.getUUID();
                    }
                }
                return null;
            });
            c.check(botId != null, "the bot " + botName + " is not in the client's world (premise)");
            String id = dashless(botId);
            UUID otherId = UUID.fromString("2a2b2c2d-3e3f-4a4b-8c8d-9e9fa0a1a2a3");
            c.onClient(mc -> {
                Mod.staticCall(RS, "clear");
                Mod.staticCall(MU, "onPresence", botName, "legit");
                Mod.setField(SF, "byUuid", java.util.Map.of());
                return null;
            });
            HxCosmeticNameCases.look(c, 0f, -12f);
            HxCosmeticNameCases.clearChat(c);
            BufferedImage off = HxCosmeticNameCases.shot(c, "bot-no-song");
            int[] name = HxCosmeticNameCases.tagAndHead(off, base);
            c.note("name tag rows " + name[0] + "-" + name[1]);
            c.check(name[0] < name[1] && name[1] < off.getHeight(), "the bot's name tag is not in the frame (premise)");

            // A mod user who is not a supporter: dropped, nothing kept, nothing drawn.
            String why = feed(c, botName, id, PLAY);
            c.note("non-supporter packet: " + why);
            c.check("not a supporter".equals(why), "a non-supporter's song was not dropped as one: '" + why + "'");
            c.check((Integer) c.onClient(mc -> Mod.staticCall(RS, "tracked")) == 0, "a non-supporter's song was kept");
            c.check(tagText(c, botName, botId) == null, "a non-supporter has a song tag");
            c.check(stateTag(c, botName) == null, "a non-supporter's render state carries a song line");
            BufferedImage nonSupporter = HxCosmeticNameCases.shot(c, "non-supporter");
            int strayRows = rowsChangedAbove(nonSupporter, off, name[0]);
            c.note("rows changed above the name for a non-supporter: " + strayRows);
            c.check(strayRows == 0, "something was drawn above a non-supporter's name (" + strayRows + " rows)");

            // In the supporter list: kept, on the state, submitted, drawn above the name.
            String list = "[{\"uuid\":\"" + botId + "\",\"name\":\"\",\"scale\":1},{\"uuid\":\"" + otherId
                    + "\",\"name\":\"\",\"scale\":1}]";
            c.onClient(mc -> {
                Object parsed = Mod.staticCall("supporters.SupportersFetcher", "parseSupporters", json(list));
                Mod.staticCall(SF, "apply", 9, parsed, false);
                return null;
            });
            c.check((Boolean) c.onClient(mc -> Mod.staticCall(SF, "isSupporter", botId)), "the bot is not a supporter (premise)");
            why = feed(c, botName, id, PLAY);
            c.check(why.isEmpty(), "a supporter's song was dropped: '" + why + "'");
            String tag = tagText(c, botName, botId);
            c.note("tag: " + tag);
            c.check(TAG.equals(tag), "the supporter's tag is not '" + TAG + "': " + tag);
            c.check(TAG.equals(stateTag(c, botName)), "the bot's render state does not carry the song line: " + stateTag(c, botName));
            long submittedBefore = c.onClient(mc -> (Long) Mod.field("spotify.SongTagDraw", "submitted"));
            BufferedImage on = HxCosmeticNameCases.shot(c, "supporter-song");
            long submittedAfter = c.onClient(mc -> (Long) Mod.field("spotify.SongTagDraw", "submitted"));
            c.note("song lines submitted while the shot was taken: " + (submittedAfter - submittedBefore));
            c.check(submittedAfter > submittedBefore, "the song line was never submitted to the renderer");
            int[] song = HxCosmeticNameCases.tagAndHead(on, off);
            int songRows = rowsChangedAbove(on, off, name[0]);
            c.note("song rows " + song[0] + "-" + song[1] + " (" + songRows + " changed rows above the name at " + name[0] + ")");
            c.check(songRows >= 3, "no song line above the supporter's name (" + songRows + " rows)");
            c.check(song[0] < song[1] && song[1] < name[0], "the song line is not wholly above the name: song "
                    + song[0] + "-" + song[1] + ", name " + name[0] + "-" + name[1]);

            // Show Others' Songs OFF takes it away.
            try (Settings o = new Settings(c).with(CFG, "ShowOthers", false)) {
                c.check(tagText(c, botName, botId) == null && stateTag(c, botName) == null, "Show Others' Songs OFF still shows the song");
            }
            c.check(TAG.equals(tagText(c, botName, botId)), "Show Others' Songs ON did not bring it back");

            // Abuse limits.
            c.ctx().waitTicks(12);
            c.check("bad sender name".equals(feed(c, "Bad Name!", id, PLAY)), "a bad sender name was not dropped");
            c.check("no sender uuid".equals(feed(c, botName, "", PLAY)), "a packet with no relay uuid was not dropped");
            c.check("not an object".equals(feed(c, botName, id, "[1,2]")), "a non-object value was not dropped");
            c.check("unknown state".equals(feed(c, botName, id, "{\"state\":\"pause\"}")), "an unknown state was not dropped");
            String longTitle = "x".repeat(600);
            c.check("too long".equals(feed(c, botName, id, "{\"state\":\"play\",\"artist\":\"a\",\"title\":\"" + longTitle + "\"}")),
                    "an over-long value was not dropped");
            String cleaned = c.onClient(mc -> (String) Mod.staticCall(RS, "clean", "§cRed‮evil​zw  \n spaced\u0007", 64));
            c.note("cleaned: '" + cleaned + "'");
            c.check("Redevilzw spaced".equals(cleaned), "clean() left codes or invisible characters: '" + cleaned + "'");
            c.check("".equals(feed(c, botName, id, "{\"state\":\"play\",\"artist\":\"§kA⁦b\",\"title\":\"§4Fuck​ this\"}")),
                    "a packet with § and bidi text was dropped instead of cleaned");
            String dirty = tagText(c, botName, botId);
            c.note("cleaned tag: " + dirty);
            c.check(dirty != null && dirty.indexOf('§') < 0 && dirty.indexOf('⁦') < 0 && dirty.indexOf('​') < 0
                    && dirty.equals("♪ F*** this - Ab"), "the tag was not cleaned and starred: " + dirty);
            c.ctx().waitTicks(12);
            c.check("filtered".equals(feed(c, botName, id, "{\"state\":\"play\",\"artist\":\"x\",\"title\":\"f a g g o t\"}")),
                    "a slur was not filtered");
            c.check(tagText(c, botName, botId) == null, "a slur left the old tag up");
            c.ctx().waitTicks(12);
            long burst = c.onClient(mc -> {
                long before = (Long) Mod.field(RS, "accepted");
                for (int i = 0; i < 50; i++) {
                    Mod.staticCall(RS, "onRelayData", botName, id, json(PLAY));
                }
                return (Long) Mod.field(RS, "accepted") - before;
            });
            c.note("50 packets at once applied " + burst);
            c.check(burst == 1, "the per-sender rate limit applied " + burst + " of 50 packets");
            c.ctx().waitTicks(12);
            c.check("".equals(feed(c, botName, dashless(otherId), PLAY)), "a supporter packet with another uuid was dropped (premise)");
            c.check(tagText(c, botName, botId) == null, "a packet stamped with another account's uuid painted the bot");
            c.ctx().waitTicks(12);
            feed(c, botName, id, PLAY);
            c.check(TAG.equals(tagText(c, botName, botId)), "the tag did not come back (premise)");
            c.check("".equals(feed(c, botName, id, "{\"state\":\"off\"}")) && tagText(c, botName, botId) == null,
                    "\"off\" did not clear the tag");
            c.ctx().waitTicks(12);
            c.onClient(mc -> {
                Mod.staticCall(MU, "onPresence", botName, null);
                return null;
            });
            c.check("not a mod user".equals(feed(c, botName, id, PLAY)), "a sender not showing as a mod user was not dropped");

            send(c);
            settings(c);
        } finally {
            c.onClient(mc -> {
                Mod.staticCall(MU, "onPresence", botName, null);
                Mod.staticCall(RS, "clear");
                Mod.setField(SF, "byUuid", savedMap);
                Mod.setField(SH, "testSender", null);
                Mod.setField(SH, "testNowPlaying", null);
                Mod.set(CFG, "setShareSong", orig[0]);
                Mod.set(CFG, "setShowOthers", orig[1]);
                return null;
            });
            bot.remove();
        }
    }

    /** The sender: nothing from a non-supporter; the exact payload from a supporter; "off" on pause. */
    private static void send(Session c) {
        List<String> handed = new CopyOnWriteArrayList<>();
        BiPredicate<String, JsonElement> sink = (key, value) -> handed.add(key + " " + value);
        UUID self = c.onClient(mc -> mc.player.getUUID());
        try (Settings s = new Settings(c).with(CFG, "ShareSong", true).with(MUC, "Share", true)) {
            c.onClient(mc -> {
                Mod.setField(SF, "byUuid", java.util.Map.of());
                Mod.setField(SH, "testNowPlaying", Mod.staticCall("spotify.NowPlaying", "track", "Artist", "Title"));
                Mod.setField(SH, "testSender", sink);
                return null;
            });
            c.ctx().waitTicks(30);
            String block = c.onClient(mc -> (String) Mod.field(SH, "lastBlock"));
            c.note("non-supporter sender: handed " + handed + ", blocked by '" + block + "'");
            c.check(handed.isEmpty(), "a non-supporter's song was handed to the relay: " + handed);
            c.check("not a supporter".equals(block), "the sender was not blocked as a non-supporter: '" + block + "'");

            String list = "[{\"uuid\":\"" + self + "\",\"name\":\"\",\"scale\":1}]";
            c.onClient(mc -> {
                Mod.staticCall(SF, "apply", 10,
                        Mod.staticCall("supporters.SupportersFetcher", "parseSupporters", json(list)), false);
                return null;
            });
            c.waitUntil("a play packet handed over", mc -> !handed.isEmpty(), 60);
            c.note("supporter sender: " + handed);
            c.check(("np.v1 {\"state\":\"play\",\"artist\":\"Artist\",\"title\":\"Title\"}").equals(handed.get(0)),
                    "the play packet is not the expected np.v1 payload: " + handed.get(0));
            String own = c.onClient(mc -> {
                Object t = Mod.staticCall(SH, "ownTag");
                return t == null ? null : ((Component) t).getString();
            });
            c.check("♪ Title - Artist".equals(own), "your own tag does not carry what was sent: " + own);
            c.ctx().waitTicks(30);
            c.check(handed.size() == 1, "an unchanged song was sent again within 30 s: " + handed);
            c.onClient(mc -> {
                Mod.setField(SH, "testNowPlaying", Mod.staticCall("spotify.NowPlaying", "paused"));
                return null;
            });
            c.waitUntil("an off packet handed over", mc -> handed.size() >= 2, 60);
            c.check("np.v1 {\"state\":\"off\"}".equals(handed.get(1)), "a pause did not send off: " + handed);
            c.ctx().waitTicks(30);
            c.check(handed.size() == 2, "off was sent more than once: " + handed);
        } finally {
            c.onClient(mc -> {
                Mod.setField(SH, "testSender", null);
                Mod.setField(SH, "testNowPlaying", null);
                return null;
            });
        }
    }

    /** Defaults OFF, save + load, profile reload, tooltips. */
    private static void settings(Session c) throws Exception {
        boolean[] defaults = c.onClient(mc -> {
            try {
                Constructor<?> k = Mod.cls(CFG).getDeclaredConstructor();
                k.setAccessible(true);
                Object fresh = k.newInstance();
                return new boolean[]{(Boolean) Mod.call(fresh, "isShareSong"), (Boolean) Mod.call(fresh, "isShowOthers")};
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        c.check(!defaults[0] && !defaults[1], "Song Tag switches do not default OFF: share " + defaults[0] + ", show " + defaults[1]);
        Path file = c.onClient(mc -> (Path) Mod.field(CFG, "CONFIG_PATH"));
        byte[] before = Files.exists(file) ? Files.readAllBytes(file) : null;
        try {
            c.onClient(mc -> {
                Mod.set(CFG, "setShareSong", true);
                Mod.set(CFG, "setShowOthers", true);
                Mod.call(Mod.cfg(CFG), "save");
                return null;
            });
            String saved = Files.readString(file);
            c.check(saved.contains("\"shareSong\": true") && saved.contains("\"showOthers\": true"), "not saved: " + saved);
            boolean loaded = c.onClient(mc -> {
                Mod.staticCall(CFG, "load");
                return (Boolean) Mod.get(CFG, "isShareSong") && (Boolean) Mod.get(CFG, "isShowOthers");
            });
            c.check(loaded, "load() did not read the Song Tag switches back");
            boolean profile = c.onClient(mc -> {
                Mod.set(CFG, "setShareSong", false);
                Mod.staticCall("profiles.ProfileManager", "reloadAllConfigs");
                return (Boolean) Mod.get(CFG, "isShareSong");
            });
            c.check(profile, "ProfileManager.reloadAllConfigs did not restore Share My Song");
            for (String label : new String[]{"Share My Song: §cOFF", "Show Others' Songs: §cOFF"}) {
                String tip = c.onClient(mc -> (String) Mod.staticCall("gui.SettingTooltips", "describe", "Spotify Lyrics", label));
                c.note(label + " tooltip: " + tip);
                c.check(tip != null && !tip.isBlank(), "no tooltip for " + label);
            }
        } finally {
            if (before == null) {
                Files.deleteIfExists(file);
            } else {
                Files.write(file, before);
            }
            c.onClient(mc -> {
                Mod.staticCall(CFG, "load");
                return null;
            });
        }
    }
}
