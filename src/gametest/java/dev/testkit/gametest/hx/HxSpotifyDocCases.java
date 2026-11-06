package dev.testkit.gametest.hx;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.testkit.compat.McCompat;
import dev.testkit.gametest.TestEnemy;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.CameraType;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.entity.player.Player;

import java.awt.image.BufferedImage;
import java.lang.reflect.Constructor;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * The to-do Doc's SPOTIFY LYRICS item (mod branch doc-spotify-1101, killer560 2026-11-01). Nothing here reaches the real
 * Spotify or another real player on the machine: transport commands go to {@code SpotifyControls.testSink}, the lyric
 * state is a made-up snapshot, and the one case that runs the real Windows helper (2868) sends it volume commands that
 * leave any running Spotify at the volume it already had.
 * <ul>
 *   <li>2860 tab: the transport buttons are gone from the tab; Music Source, Display Censor, Always Show Controls, the
 *       Lyrics In F5 rows, Share What and Share Current Word are there; the F5 rows in Player Cosmetics' Spotify In F5 and
 *       in the Spotify tab are one setting each (a press in one shows in the other); every new setting saves, loads, and
 *       survives a profile reload; every new row has a tooltip.</li>
 *   <li>2861 HUD: Always Show Controls draws the control row with chat closed and puts it inside the element's height;
 *       Play/Pause draws a triangle when paused and two bars when playing, and its cell is filled with the accent
 *       (screenshot: its fill differs from the skip cell's); the volume cell's border is the buttons' border
 *       (screenshot); a click on Play/Pause in chat still sends toggle.</li>
 *   <li>2862 pause: with Hide When Paused OFF, a pause of 59 s still draws the HUD, the F5 lyric and the Song In F5 line;
 *       at 61 s none of them is drawn; playing again brings them back.</li>
 *   <li>2863 censor: All stars every swear word, Minimal only the slur, None nothing, in the chat filter, the F5 lyric line
 *       and another player's song line.</li>
 *   <li>2864 F5 colors: with custom F5 colors the lit word is the highlight color, sung words the text color, words to
 *       come darker; the Song In F5 line is the text color and shows title and artist; the screenshot carries the highlight
 *       color over the player; Menu Theme brings the HUD's accent back.</li>
 *   <li>2865 share lyrics: the packet carries exactly what Share What and Share Current Word allow (song, line, word
 *       times); a new line is sent, a moving "into" alone is not; a receiver lights the word the sender is on, by the
 *       sender's word times on its own clock, draws the line over the sender (render state and screenshot), keeps the
 *       song-only packet of an older client working, ignores bad word times and drops a stale line.</li>
 *   <li>2866 room: with Mod Chat off and no party, Share My Song or Show Others' Songs puts the relay in the LOBBY room;
 *       with both off it does not ask for one. With Mod Chat ON in Party mode (its first-download default) and no party,
 *       Song Tag still gets the lobby room and Mod Chat refuses to send there (songtag-1104).</li>
 *   <li>2867 relay (needs a local relay; SKIP otherwise): with Mod Chat at its default (ON, Party) and no party, through
 *       a real relay (wrangler dev) the client connects to its lobby room on its own, a second relay client in that room receives the song and lyric line the client shares,
 *       and the client draws the song and the lyric that second client shares over the matching player.</li>
 *   <li>2868 volume: 51 volume settings written at once to the real helper run as ONE (50 skipped), so the last value
 *       applies at once.</li>
 *   <li>2869 cover: a cover handed over by the reader is drawn in the HUD's cover tile (screenshot), and Music Source's
 *       other-player reading is taken as the track when the Spotify app has none.</li>
 * </ul>
 */
final class HxSpotifyDocCases {

    private static final String CTL = "spotify.SpotifyControls";
    private static final String SLF = "spotify.SpotifyLyricsFeature";
    private static final String HUD = "spotify.SpotifyHud";
    private static final String HUDC = "spotify.SpotifyHudControls";
    private static final String HC = "spotify.SpotifyHudConfig";
    private static final String TAGC = "spotify.SongShareConfig";
    private static final String SH = "spotify.SongShare";
    private static final String RS = "spotify.RemoteSongs";
    private static final String STD = "spotify.SongTagDraw";
    private static final String SF = "supporters.SupportersFeature";
    private static final String SC = "supporters.SupportersConfig";
    private static final String MU = "modusers.ModUsers";
    private static final String MUC = "modusers.ModUserConfig";
    private static final String NC = "namechanger.NameChangerConfig";
    /** The throwaway TOKEN_SECRET the local relay for 2867 is started with (never a real secret). */
    static final String E2E_SECRET = "song-e2e-secret-not-real";

    private HxSpotifyDocCases() {
    }

    static void register(Session s) {
        s.test("2860-hx-spotify-tab-1101", HxSpotifyDocCases::tab);
        s.test("2861-hx-spotify-hud-row-1101", HxSpotifyDocCases::hudRow);
        s.test("2862-hx-spotify-pause-1101", HxSpotifyDocCases::pause);
        s.test("2863-hx-spotify-censor-1101", HxSpotifyDocCases::censor);
        s.test("2864-hx-spotify-f5-colors-1101", HxSpotifyDocCases::f5Colors);
        s.test("2865-hx-spotify-share-lyrics-1101", HxSpotifyDocCases::shareLyrics);
        s.test("2866-hx-spotify-room-1101", HxSpotifyDocCases::room);
        String override = System.getProperty("killer560.net.relay", "");
        if (override.startsWith("http://127.0.0.1") || override.startsWith("http://localhost")) {
            s.test("2867-hx-spotify-relay-1101", HxSpotifyDocCases::relay);
        } else {
            s.shelved("2867-hx-spotify-relay-1101", "needs a local relay: wrangler dev with TOKEN_SECRET " + E2E_SECRET
                    + " and -PnetOverride=relay=http://127.0.0.1:<port>", HxSpotifyDocCases::relay);
        }
        s.test("2868-hx-spotify-volume-burst-1101", HxSpotifyDocCases::volumeBurst);
        s.test("2869-hx-spotify-cover-1101", HxSpotifyDocCases::cover);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private static boolean has(Session c) {
        return c.onClient(mc -> {
            try {
                Mod.cls(TAGC).getMethod("getShareWhat");
                return true;
            } catch (ReflectiveOperationException | RuntimeException e) {
                return false;
            }
        });
    }

    private static Object newRecord(String cls, Object... args) {
        try {
            Constructor<?> k = Mod.cls(cls).getDeclaredConstructors()[0];
            k.setAccessible(true);
            return k.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Object line(double t, String text) {
        return newRecord("spotify.LyricsEngine$LyricLine", t, text.replaceAll("<[^>]*>", "").replaceAll("\\s+", " ").trim(),
                Mod.staticCall("spotify.LyricTiming", "parse", text, t));
    }

    private static Object snapshot(String kind, long playedMs, long sinceMs, List<Object> lines) {
        return newRecord("spotify.LyricsEngine$Snapshot", Mod.enumValue("spotify.NowPlaying$Kind", kind), "Artist",
                "Song", playedMs, sinceMs, 200.0, lines, true, false, 0.0, true);
    }

    private static void freeze(Session c, long ms) {
        c.onClient(mc -> {
            Mod.setField(SLF, "testNowMs", ms);
            return null;
        });
    }

    private static List<AbstractWidget> build(Session c, Object tab) {
        return c.onClient(mc -> {
            Runnable none = () -> { };
            @SuppressWarnings("unchecked")
            List<AbstractWidget> w = (List<AbstractWidget>) Mod.call(tab, "buildWidgets", 0, 0, 320, none);
            return w;
        });
    }

    private static Object newTab(Session c, String cls) {
        return c.onClient(mc -> {
            try {
                return Mod.cls(cls).getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
    }

    private static AbstractWidget find(List<AbstractWidget> widgets, String startsWith) {
        AbstractWidget w = findOrNull(widgets, startsWith);
        if (w == null) {
            throw new AssertionError("no row starting '" + startsWith + "'");
        }
        return w;
    }

    private static AbstractWidget findOrNull(List<AbstractWidget> widgets, String startsWith) {
        for (AbstractWidget w : widgets) {
            if (w.getMessage().getString().startsWith(startsWith)) {
                return w;
            }
        }
        return null;
    }

    private static String label(List<AbstractWidget> widgets, String startsWith) {
        return find(widgets, startsWith).getMessage().getString();
    }

    private static void press(Session c, AbstractWidget w) {
        c.onClient(mc -> Mod.call(w, "press"));
    }

    private static String ownState(Session c, String accessor) {
        return c.onClient(mc -> {
            EntityRenderState state = mc.getEntityRenderDispatcher().extractEntity(mc.player, 1f);
            Object t = Mod.call(state, accessor);
            return t == null ? null : ((Component) t).getString();
        });
    }

    private static Component ownComponent(Session c, String accessor) {
        return c.onClient(mc -> {
            EntityRenderState state = mc.getEntityRenderDispatcher().extractEntity(mc.player, 1f);
            return (Component) Mod.call(state, accessor);
        });
    }

    static String botState(Session c, String botName, String accessor) {
        return c.onClient(mc -> {
            for (Player p : mc.level.players()) {
                if (p.getGameProfile().name().equals(botName)) {
                    EntityRenderState state = mc.getEntityRenderDispatcher().extractEntity(p, 1f);
                    Object t = Mod.call(state, accessor);
                    return t == null ? null : ((Component) t).getString();
                }
            }
            return "(no bot)";
        });
    }

    /** {@link #botState} for code already on the client thread (a waitUntil condition). */
    private static String botStateHere(net.minecraft.client.Minecraft mc, String botName, String accessor) {
        for (Player p : mc.level.players()) {
            if (p.getGameProfile().name().equals(botName)) {
                EntityRenderState state = mc.getEntityRenderDispatcher().extractEntity(p, 1f);
                Object t = Mod.call(state, accessor);
                return t == null ? null : ((Component) t).getString();
            }
        }
        return "(no bot)";
    }

    /** Writes an instance field (any visibility). */
    private static void setInstance(Object target, String field, Object value) {
        try {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    static UUID botUuid(Session c, String botName) {
        return c.onClient(mc -> {
            for (Player p : mc.level.players()) {
                if (p.getGameProfile().name().equals(botName)) {
                    return p.getUUID();
                }
            }
            return null;
        });
    }

    /** Saves a config file's bytes and puts them back (and reloads) at the end. */
    private static final class FileGuard implements AutoCloseable {
        private final Session c;
        private final String cls;
        private final Path file;
        private final byte[] before;

        FileGuard(Session c, String cls) throws Exception {
            this.c = c;
            this.cls = cls;
            this.file = c.onClient(mc -> (Path) Mod.field(cls, "CONFIG_PATH"));
            this.before = Files.exists(file) ? Files.readAllBytes(file) : null;
        }

        @Override
        public void close() throws Exception {
            if (before == null) {
                Files.deleteIfExists(file);
            } else {
                Files.write(file, before);
            }
            c.onClient(mc -> {
                Mod.staticCall(cls, "load");
                return null;
            });
        }
    }

    /** Supporter list: these uuids. */
    static void supporters(Session c, UUID... ids) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < ids.length; i++) {
            b.append(i == 0 ? "" : ",").append("{\"uuid\":\"").append(ids[i]).append("\",\"name\":\"\",\"scale\":1}");
        }
        String list = b.append("]").toString();
        c.onClient(mc -> {
            Mod.staticCall(SF, "apply", 11, Mod.staticCall("supporters.SupportersFetcher", "parseSupporters",
                    JsonParser.parseString(list)), false);
            return null;
        });
    }

    /** A HUD-local point (x, y) as a pixel of the framebuffer screenshot. */
    private static int[] hudPixel(Session c, double lx, double ly, BufferedImage img) {
        double[] at = c.onClient(mc -> {
            Object hud = Mod.field(HUD, "INSTANCE");
            int[] pos = (int[]) Mod.staticCall("hud.HudElementRegistry", "resolvePosition", hud);
            float scale = (Float) Mod.staticCall("hud.HudElementRegistry", "resolveScale", hud);
            double gs = (double) mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth();
            double fx = (double) img.getWidth() / mc.getWindow().getScreenWidth();
            return new double[]{(pos[0] + lx * scale) * gs * fx, (pos[1] + ly * scale) * gs * fx};
        });
        return new int[]{(int) Math.floor(at[0]), (int) Math.floor(at[1])};
    }

    private static int dist(int p, int q) {
        return Math.abs(((p >> 16) & 255) - ((q >> 16) & 255)) + Math.abs(((p >> 8) & 255) - ((q >> 8) & 255))
                + Math.abs((p & 255) - (q & 255));
    }

    private static String hex(int argb) {
        return String.format("%06X", argb & 0xFFFFFF);
    }

    // ---- 2860: the tab --------------------------------------------------------------------------------------------

    private static void tab(Session c) throws Exception {
        c.check(has(c), "this jar has no 2026-11-01 Spotify settings (mod before doc-spotify-1101)");
        try (FileGuard g1 = new FileGuard(c, TAGC); FileGuard g2 = new FileGuard(c, HC);
             Settings s = new Settings(c).with(SC, "OwnNameInF5", false)) {
            Object spotify = newTab(c, "gui.tab.SpotifyTab");
            List<AbstractWidget> w = build(c, spotify);
            Pattern seek = Pattern.compile("^(Rewind|Skip Ahead) \\d+s$");
            for (AbstractWidget x : w) {
                String m = x.getMessage().getString();
                c.check(!m.equals("Previous") && !m.equals("Play/Pause") && !m.equals("Next") && !seek.matcher(m).matches(),
                        "the tab still has the transport button '" + m + "'");
            }
            for (String want : new String[]{"Spotify Volume:", "Rewind/Skip Step:", "Play/Pause Key:", "Music Source:",
                    "Display Censor:", "Always Show Controls:", "My Lyrics In F5:", "Highlight Word In F5:", "Song In F5:",
                    "My Name in F5:", "F5 Colors:", "Share What:", "Share Current Word:", "Show Others' Songs:"}) {
                c.check(findOrNull(w, want) != null, "the tab has no '" + want + "' row");
            }
            c.note("rows: " + w.size() + "; no transport buttons; Music Source '" + label(w, "Music Source:")
                    + "', Display Censor '" + label(w, "Display Censor:") + "'");

            // Double-faced: Player Cosmetics' Spotify In F5 and the Spotify tab's Lyrics In F5 are the same settings.
            Object cos = newTab(c, "gui.tab.CosmeticsTab");
            List<AbstractWidget> cw = build(c, cos);
            AbstractWidget header = null;
            for (AbstractWidget x : cw) {
                if (x.getMessage().getString().contains("Spotify In F5")) {
                    header = x;
                }
            }
            c.check(header != null, "Player Cosmetics has no Spotify In F5 section");
            press(c, header);
            cw = build(c, cos);
            c.check(findOrNull(cw, "Song In F5:") != null, "Spotify In F5 opened without its rows");
            boolean before = c.onClient(mc -> (Boolean) Mod.get(TAGC, "isSongInF5"));
            press(c, find(cw, "Song In F5:"));
            boolean afterCos = c.onClient(mc -> (Boolean) Mod.get(TAGC, "isSongInF5"));
            String inSpotify = label(build(c, spotify), "Song In F5:");
            c.note("Song In F5 " + before + " -> pressed in Cosmetics -> " + afterCos + "; Spotify tab shows '" + inSpotify + "'");
            c.check(afterCos != before && inSpotify.endsWith(afterCos ? "ON" : "OFF"),
                    "a press in Cosmetics did not show in the Spotify tab: '" + inSpotify + "'");
            press(c, find(build(c, spotify), "Song In F5:"));
            String inCos = label(build(c, cos), "Song In F5:");
            c.check(inCos.endsWith(before ? "ON" : "OFF"), "a press in the Spotify tab did not show in Cosmetics: '" + inCos + "'");
            press(c, find(build(c, spotify), "F5 Colors:"));
            cw = build(c, cos);
            c.check(label(cw, "F5 Colors:").contains("Custom") && findOrNull(cw, "F5 Text Color") != null
                            && findOrNull(cw, "F5 Highlight Color") != null,
                    "F5 Colors set to Custom in the Spotify tab is not Custom (with both colors) in Cosmetics");
            press(c, find(build(c, spotify), "My Name in F5:"));
            boolean name = c.onClient(mc -> (Boolean) Mod.get(SC, "isOwnNameInF5"));
            c.check(name && label(cw = build(c, cos), "My Name in F5:").endsWith("ON"),
                    "My Name in F5 pressed in the Spotify tab is not ON in Player Cosmetics");
            for (String row : new String[]{"My Lyrics In F5:", "Highlight Word In F5:"}) {
                String a = label(build(c, spotify), row);
                press(c, find(build(c, cos), row));
                String b = label(build(c, spotify), row);
                c.check(!a.equals(b), "'" + row + "' pressed in Cosmetics did not change in the Spotify tab");
            }

            // Saved, loaded, and restored by a profile reload.
            c.onClient(mc -> {
                Mod.set(TAGC, "setShareWhat", Mod.enumValue(TAGC + "$ShareWhat", "BOTH"));
                Mod.set(TAGC, "setShareWord", false);
                Mod.set(TAGC, "setSongInF5", true);
                Mod.set(TAGC, "setCustomF5Colors", true);
                Mod.set(TAGC, "setF5TextColor", 0xFF123456);
                Mod.set(TAGC, "setF5HighlightColor", 0xFF654321);
                Mod.set(HC, "setAlwaysShowControls", true);
                Mod.set(HC, "setDisplayCensor", Mod.enumValue("spotify.ProfanityLevel", "NONE"));
                Mod.set(HC, "setMusicSource", Mod.enumValue(HC + "$MusicSource", "ANYTHING"));
                Mod.call(Mod.cfg(TAGC), "save");
                Mod.call(Mod.cfg(HC), "save");
                return null;
            });
            String saved = c.onClient(mc -> {
                Mod.set(TAGC, "setShareWhat", Mod.enumValue(TAGC + "$ShareWhat", "SONG"));
                Mod.set(HC, "setMusicSource", Mod.enumValue(HC + "$MusicSource", "SPOTIFY"));
                Mod.staticCall("profiles.ProfileManager", "reloadAllConfigs");
                return Mod.get(TAGC, "getShareWhat") + " " + Mod.get(TAGC, "isShareWord") + " " + Mod.get(TAGC, "isSongInF5")
                        + " " + Mod.get(TAGC, "isCustomF5Colors") + " " + hex((Integer) Mod.get(TAGC, "getF5TextColor"))
                        + " " + hex((Integer) Mod.get(TAGC, "getF5HighlightColor")) + " " + Mod.get(HC, "isAlwaysShowControls")
                        + " " + Mod.get(HC, "getDisplayCensor") + " " + Mod.get(HC, "getMusicSource");
            });
            c.note("after save + profile reload: " + saved);
            c.check("BOTH false true true 123456 654321 true NONE ANYTHING".equals(saved), "not saved/restored: " + saved);
            String defaults = c.onClient(mc -> {
                try {
                    Constructor<?> k1 = Mod.cls(TAGC).getDeclaredConstructor();
                    k1.setAccessible(true);
                    Object t = k1.newInstance();
                    Constructor<?> k2 = Mod.cls(HC).getDeclaredConstructor();
                    k2.setAccessible(true);
                    Object h = k2.newInstance();
                    return Mod.call(t, "getShareWhat") + " " + Mod.call(t, "isShareWord") + " " + Mod.call(t, "isSongInF5")
                            + " " + Mod.call(t, "isCustomF5Colors") + " " + Mod.call(h, "isAlwaysShowControls") + " "
                            + Mod.call(h, "getDisplayCensor") + " " + Mod.call(h, "getMusicSource");
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            c.note("defaults: " + defaults);
            c.check("SONG true false false false MINIMAL MUSIC_PLAYERS".equals(defaults), "unexpected defaults: " + defaults);

            for (String label : new String[]{"Music Source: x", "Display Censor: x", "Always Show Controls: §cOFF",
                    "Song In F5: §cOFF", "F5 Colors: x", "F5 Text Color", "F5 Highlight Color", "Share What: x",
                    "Share Current Word: §cOFF", "My Name in F5: §cOFF"}) {
                String tip = c.onClient(mc -> (String) Mod.staticCall("gui.SettingTooltips", "describe", "Spotify Lyrics", label));
                c.check(tip != null && !tip.isBlank(), "no tooltip for " + label);
            }
        }
    }

    // ---- 2861: the HUD's control row ---------------------------------------------------------------------------------

    private static void hudRow(Session c) throws Exception {
        c.check(has(c), "this jar has no Always Show Controls");
        List<String> sent = new CopyOnWriteArrayList<>();
        Consumer<String> sink = sent::add;
        long now = System.currentTimeMillis();
        List<Object> lines = c.onClient(mc -> List.of(line(0.0, "a line"), line(600.0, "later")));
        Object playing = c.onClient(mc -> snapshot("TRACK", 5000, now, lines));
        Object paused = c.onClient(mc -> snapshot("PAUSED", 5000, 0, lines));
        try (Settings s = new Settings(c).with(HC, "HudEnabled", true).with(HC, "ShowLyrics", true)
                .with(HC, "AlwaysShowControls", true).with(HC, "HideWhenPaused", false)) {
            c.onClient(mc -> {
                Mod.setField(CTL, "testSink", sink);
                Mod.setField(CTL, "testReason", "");
                Mod.setField(SLF, "testSnapshot", playing);
                Mod.setField(SLF, "testNowMs", 0L);
                return null;
            });
            HxCosmeticNameCases.camera(c, CameraType.FIRST_PERSON);
            c.ctx().runOnClient(mc -> McCompat.setScreen(mc, null));
            c.ctx().waitTicks(5);
            boolean shown = c.onClient(mc -> (Boolean) Mod.field(HUDC, "rowShown"));
            int height = c.onClient(mc -> (Integer) Mod.call(Mod.field(HUD, "INSTANCE"), "height"));
            int panel = c.onClient(mc -> (Integer) Mod.staticCall(HUD, "panelHeight"));
            int rowH = c.onClient(mc -> (Integer) Mod.field(HUDC, "ROW_H"));
            int gap = c.onClient(mc -> (Integer) Mod.field(HUDC, "GAP"));
            String glyph = c.onClient(mc -> (String) Mod.field(HUDC, "lastPlayGlyph"));
            c.note("chat closed: row shown " + shown + ", element height " + height + " = panel " + panel + " + " + gap
                    + " + " + rowH + ", play cell glyph '" + glyph + "' while playing");
            c.check(shown, "Always Show Controls on, chat closed: the control row is not drawn");
            c.check(height == panel + gap + rowH, "the element's height does not hold the row: " + height);
            c.check("pause".equals(glyph), "playing, the Play/Pause cell did not draw the pause bars: " + glyph);

            int[] x0 = c.onClient(mc -> (int[]) Mod.field(HUDC, "X0"));
            int[] x1 = c.onClient(mc -> (int[]) Mod.field(HUDC, "X1"));
            int play = c.onClient(mc -> (Integer) Mod.field(HUDC, "PLAY_CELL"));
            int vol = c.onClient(mc -> (Integer) Mod.field(HUDC, "VOLUME_CELL"));
            BufferedImage img = HxCosmeticNameCases.shot(c, "hud-row-always");
            int y0 = panel + gap;
            int[] pPlay = hudPixel(c, x0[play] + 2.5, y0 + 2.5, img);
            int[] pPrev = hudPixel(c, x0[0] + 2.5, y0 + 2.5, img);
            int[] pVolEdge = hudPixel(c, x0[vol] + 0.5, y0 + 7.5, img);
            int[] pMinusEdge = hudPixel(c, x0[vol - 1] + 0.5, y0 + 7.5, img);
            int cPlay = img.getRGB(pPlay[0], pPlay[1]);
            int cPrev = img.getRGB(pPrev[0], pPrev[1]);
            int cVol = img.getRGB(pVolEdge[0], pVolEdge[1]);
            int cMinus = img.getRGB(pMinusEdge[0], pMinusEdge[1]);
            int accent = c.onClient(mc -> (Integer) Mod.call(Mod.staticCall("gui.OverlayThemes", "menu"), "accent"));
            c.note("screenshot: Play/Pause fill #" + hex(cPlay) + " (accent #" + hex(accent) + "), Previous fill #" + hex(cPrev)
                    + "; volume box edge #" + hex(cVol) + ", Volume Down edge #" + hex(cMinus));
            c.check(dist(cPlay, cPrev) > 90, "the Play/Pause cell looks like the skip cells (#" + hex(cPlay) + " vs #" + hex(cPrev) + ")");
            c.check(dist(cPlay, accent) < 60, "the Play/Pause cell is not filled with the accent (#" + hex(cPlay) + ")");
            c.check(dist(cVol, cMinus) < 30, "the volume reading has no box like the buttons' (#" + hex(cVol) + " vs #" + hex(cMinus) + ")");

            c.onClient(mc -> {
                Mod.setField(SLF, "testSnapshot", paused);
                return null;
            });
            c.ctx().waitTicks(3);
            glyph = c.onClient(mc -> (String) Mod.field(HUDC, "lastPlayGlyph"));
            c.check("play".equals(glyph), "paused, the Play/Pause cell did not draw the play triangle: " + glyph);
            c.check(x1[play] - x0[play] > x1[0] - x0[0], "the Play/Pause cell is not wider than the skip cells");

            // Clicks still work with chat open.
            c.ctx().runOnClient(mc -> McCompat.setScreen(mc, new ChatScreen("", false)));
            c.ctx().waitTicks(3);
            double cx = (x0[play] + x1[play]) / 2.0;
            double[] at = c.onClient(mc -> {
                Object hud = Mod.field(HUD, "INSTANCE");
                int[] pos = (int[]) Mod.staticCall("hud.HudElementRegistry", "resolvePosition", hud);
                float scale = (Float) Mod.staticCall("hud.HudElementRegistry", "resolveScale", hud);
                return new double[]{pos[0] + cx * scale, pos[1] + (y0 + 7) * scale};
            });
            sent.clear();
            boolean taken = c.onClient(mc -> (Boolean) Mod.staticCall(HUDC, "click", at[0], at[1]));
            c.check(taken && sent.equals(List.of("toggle")), "a click on Play/Pause in chat sent " + sent);
            c.ctx().runOnClient(mc -> McCompat.setScreen(mc, null));

            try (Settings off = new Settings(c).with(HC, "AlwaysShowControls", false)) {
                c.ctx().waitTicks(4);
                c.check(!(Boolean) c.onClient(mc -> Mod.field(HUDC, "rowShown")), "Always Show Controls off: the row is still drawn");
            }
        } finally {
            c.ctx().runOnClient(mc -> McCompat.setScreen(mc, null));
            c.onClient(mc -> {
                Mod.setField(CTL, "testSink", null);
                Mod.setField(CTL, "testReason", null);
                Mod.setField(SLF, "testSnapshot", null);
                return null;
            });
        }
    }

    // ---- 2862: a minute of pause -------------------------------------------------------------------------------------

    private static void pause(Session c) throws Exception {
        c.check(has(c), "this jar has no pause timeout");
        long base = 1_900_000_000_000L;
        List<Object> lines = c.onClient(mc -> List.of(line(0.0, "the first line"), line(4.0, "held on the pause"),
                line(600.0, "much later")));
        Object paused = c.onClient(mc -> snapshot("PAUSED", 5123, 0, lines));
        try (Settings s = new Settings(c).with(HC, "HudEnabled", true).with(HC, "HideWhenPaused", false)
                .with(HC, "AlwaysShowControls", false).with(TAGC, "LyricsOverHead", true).with(TAGC, "SongInF5", true)
                .with(TAGC, "ShareSong", false).with(SC, "OwnNameInF5", false)) {
            freeze(c, base);
            c.onClient(mc -> {
                Mod.setField(SLF, "testSnapshot", paused);
                return null;
            });
            HxCosmeticNameCases.look(c, 0f, 0f);
            HxCosmeticNameCases.camera(c, CameraType.THIRD_PERSON_BACK);
            c.ctx().waitTicks(5);
            freeze(c, base + 59_000);
            long d0 = c.onClient(mc -> (Long) Mod.field(HUDC, "drawnAtMs"));
            c.ctx().waitTicks(5);
            long d1 = c.onClient(mc -> (Long) Mod.field(HUDC, "drawnAtMs"));
            String lyric = ownState(c, "killer560smod$lyricTag");
            String song = ownState(c, "killer560smod$songTag");
            c.note("paused 59 s: HUD drawn " + (d1 > d0) + ", F5 lyric '" + lyric + "', F5 song '" + song + "'");
            c.check(d1 > d0, "paused 59 s with Hide When Paused off: the HUD is not drawn");
            c.check("♫ held on the pause".equals(lyric), "paused 59 s: the F5 lyric is '" + lyric + "'");
            c.check("♪ Song - Artist".equals(song), "paused 59 s: the Song In F5 line is '" + song + "'");

            freeze(c, base + 61_000);
            c.ctx().waitTicks(3);
            long h0 = c.onClient(mc -> (Long) Mod.field(HUD, "pauseHides"));
            long e0 = c.onClient(mc -> (Long) Mod.field(HUDC, "drawnAtMs"));
            c.ctx().waitTicks(6);
            long h1 = c.onClient(mc -> (Long) Mod.field(HUD, "pauseHides"));
            long e1 = c.onClient(mc -> (Long) Mod.field(HUDC, "drawnAtMs"));
            lyric = ownState(c, "killer560smod$lyricTag");
            song = ownState(c, "killer560smod$songTag");
            c.note("paused 61 s: HUD frames skipped " + (h1 - h0) + ", HUD drawn " + (e1 > e0) + ", F5 lyric " + lyric
                    + ", F5 song " + song);
            c.check(h1 > h0 && e1 == e0, "paused 61 s: the HUD is still drawn");
            c.check(lyric == null && song == null, "paused 61 s: the F5 lines are still there (" + lyric + " / " + song + ")");

            Object playing = c.onClient(mc -> snapshot("TRACK", 5123, base + 61_000, lines));
            c.onClient(mc -> {
                Mod.setField(SLF, "testSnapshot", playing);
                return null;
            });
            freeze(c, base + 61_500);
            c.ctx().waitTicks(3);
            long p0 = c.onClient(mc -> (Long) Mod.field(HUDC, "drawnAtMs"));
            c.ctx().waitTicks(5);
            long p1 = c.onClient(mc -> (Long) Mod.field(HUDC, "drawnAtMs"));
            lyric = ownState(c, "killer560smod$lyricTag");
            c.note("playing again: HUD drawn " + (p1 > p0) + ", F5 lyric '" + lyric + "'");
            c.check(p1 > p0 && lyric != null, "playing again did not bring the HUD and the F5 lyric back");
        } finally {
            c.onClient(mc -> {
                Mod.setField(SLF, "testSnapshot", null);
                Mod.setField(SLF, "testNowMs", 0L);
                return null;
            });
            HxCosmeticNameCases.camera(c, CameraType.FIRST_PERSON);
        }
    }

    // ---- 2863: censor levels -----------------------------------------------------------------------------------------

    private static void censor(Session c) throws Exception {
        c.check(has(c), "this jar has no Display Censor");
        String text = "fuck this retard";
        String all = c.onClient(mc -> (String) Mod.staticCall(SLF, "filterProfanity", text, Mod.enumValue("spotify.ProfanityLevel", "ALL")));
        String min = c.onClient(mc -> (String) Mod.staticCall(SLF, "filterProfanity", text, Mod.enumValue("spotify.ProfanityLevel", "MINIMAL")));
        String none = c.onClient(mc -> (String) Mod.staticCall(SLF, "filterProfanity", text, Mod.enumValue("spotify.ProfanityLevel", "NONE")));
        c.note("chat filter: All '" + all + "', Minimal '" + min + "', None '" + none + "'");
        c.check("f*** this r*****".equals(all), "All: " + all);
        c.check("fuck this r*****".equals(min), "Minimal must star only the slur: " + min);
        c.check(text.equals(none), "None: " + none);
        String motherf = c.onClient(mc -> (String) Mod.staticCall(SLF, "filterProfanity", "motherfucker", Mod.enumValue("spotify.ProfanityLevel", "MINIMAL")));
        c.check("motherfucker".equals(motherf), "Minimal still stars a swear that is not a slur: " + motherf);

        long now = System.currentTimeMillis();
        List<Object> lines = c.onClient(mc -> List.of(line(0.0, "fuck this retard"), line(600.0, "later")));
        Object snap = c.onClient(mc -> snapshot("TRACK", 5000, now, lines));
        String[][] want = {{"ALL", "♫ f*** this r*****"}, {"MINIMAL", "♫ fuck this r*****"}, {"NONE", "♫ fuck this retard"}};
        try (Settings s = new Settings(c).with(TAGC, "LyricsOverHead", true).with(TAGC, "HighlightWordInF5", false)
                .with(TAGC, "ShareSong", false)) {
            c.onClient(mc -> {
                Mod.setField(SLF, "testSnapshot", snap);
                return null;
            });
            HxCosmeticNameCases.camera(c, CameraType.THIRD_PERSON_BACK);
            for (String[] w : want) {
                try (Settings lv = new Settings(c).with(HC, "DisplayCensor", Mod.enumValue("spotify.ProfanityLevel", w[0]))) {
                    c.ctx().waitTicks(3);
                    String got = ownState(c, "killer560smod$lyricTag");
                    c.note("F5 lyric at " + w[0] + ": '" + got + "'");
                    c.check(w[1].equals(got), "F5 lyric at " + w[0] + " is '" + got + "', not '" + w[1] + "'");
                }
            }
        } finally {
            c.onClient(mc -> {
                Mod.setField(SLF, "testSnapshot", null);
                return null;
            });
            HxCosmeticNameCases.camera(c, CameraType.FIRST_PERSON);
        }

        // Another player's song line follows your level.
        TestEnemy bot = TestEnemy.named("Cz", "N").at(c.scenario().playerPosition()[0], c.scenario().playerPosition()[1],
                c.scenario().playerPosition()[2] + 5).frozen(true).spawn(c.ctx(), c.server());
        Object savedMap = c.onClient(mc -> Mod.field(SF, "byUuid"));
        try (Settings s = new Settings(c).with(TAGC, "ShowOthers", true)
                .custom(() -> Mod.setField(RS, "testConnected", true), () -> Mod.setField(RS, "testConnected", false))) {
            UUID id = botUuid(c, bot.name());
            supporters(c, id);
            c.onClient(mc -> {
                Mod.staticCall(RS, "clear");
                Mod.staticCall(MU, "onPresence", bot.name(), "legit");
                Mod.staticCall(RS, "onRelayData", bot.name(), id.toString().replace("-", ""),
                        JsonParser.parseString("{\"state\":\"play\",\"artist\":\"Band\",\"title\":\"Fuck this\"}"));
                return null;
            });
            for (String[] w : new String[][]{{"ALL", "♪ F*** this - Band"}, {"MINIMAL", "♪ Fuck this - Band"}}) {
                try (Settings lv = new Settings(c).with(HC, "DisplayCensor", Mod.enumValue("spotify.ProfanityLevel", w[0]))) {
                    String got = c.onClient(mc -> {
                        Object t = Mod.staticCall(RS, "tagFor", bot.name(), id);
                        return t == null ? null : ((Component) t).getString();
                    });
                    c.note("another player's song at " + w[0] + ": '" + got + "'");
                    c.check(w[1].equals(got), "another player's song at " + w[0] + " is '" + got + "'");
                }
            }
        } finally {
            c.onClient(mc -> {
                Mod.staticCall(MU, "onPresence", bot.name(), null);
                Mod.staticCall(RS, "clear");
                Mod.setField(SF, "byUuid", savedMap);
                return null;
            });
            bot.remove();
        }
    }

    // ---- 2864: F5 colors and Song In F5 ------------------------------------------------------------------------------

    private static void f5Colors(Session c) throws Exception {
        c.check(has(c), "this jar has no F5 colors");
        long base = 1_900_000_000_000L;
        // Word-timed: "alpha" from 4.0 s, "beta" from 4.5 s, "gamma" from 5.0 s, "delta" from 5.5 s.
        List<Object> lines = c.onClient(mc -> List.of(line(0.0, "intro"),
                line(4.0, "<00:04.00>alpha <00:04.50>beta <00:05.00>gamma <00:05.50>delta"), line(600.0, "later")));
        Object snap = c.onClient(mc -> snapshot("TRACK", 0, base, lines));
        try (Settings s = new Settings(c).with(TAGC, "LyricsOverHead", true).with(TAGC, "HighlightWordInF5", true)
                .with(TAGC, "SongInF5", true).with(TAGC, "ShareSong", false).with(TAGC, "CustomF5Colors", true)
                .with(TAGC, "F5TextColor", 0xFF00FF00).with(TAGC, "F5HighlightColor", 0xFFFF0000)
                .with(HC, "DisplayCensor", Mod.enumValue("spotify.ProfanityLevel", "NONE"))
                .with(SC, "OwnNameInF5", false)) {
            freeze(c, base + 4_700);
            c.onClient(mc -> {
                Mod.setField(SLF, "testSnapshot", snap);
                return null;
            });
            HxCosmeticNameCases.look(c, 0f, 0f);
            HxCosmeticNameCases.clearChat(c);
            HxCosmeticNameCases.camera(c, CameraType.THIRD_PERSON_BACK);
            c.ctx().waitTicks(4);
            Component lyric = ownComponent(c, "killer560smod$lyricTag");
            c.check(lyric != null, "no F5 lyric line (premise)");
            StringBuilder parts = new StringBuilder();
            int lit = -1;
            int[] colours = new int[4];
            List<Component> sib = lyric.getSiblings();
            for (int i = 0; i < sib.size() && i < 4; i++) {
                TextColor col = sib.get(i).getStyle().getColor();
                colours[i] = col == null ? -1 : col.getValue();
                if (sib.get(i).getStyle().isUnderlined()) {
                    lit = i;
                }
                parts.append(sib.get(i).getString().trim()).append('#').append(hex(colours[i])).append(' ');
            }
            c.note("F5 line at 4.7 s with custom colors: " + parts + "(lit word " + lit + ")");
            c.check(lit == 1, "the lit word is " + lit + ", not 'beta' (1)");
            c.check((colours[0] & 0xFFFFFF) == 0x00FF00, "a sung word is not the F5 text color: #" + hex(colours[0]));
            c.check((colours[1] & 0xFFFFFF) == 0xFF0000, "the lit word is not the F5 highlight color: #" + hex(colours[1]));
            c.check((colours[2] & 0xFFFFFF) == 0x009900, "a word to come is not the darker text color: #" + hex(colours[2]));
            Component song = ownComponent(c, "killer560smod$songTag");
            TextColor songCol = song == null ? null : song.getStyle().getColor();
            c.note("Song In F5: '" + (song == null ? null : song.getString()) + "' #" + (songCol == null ? "none" : hex(songCol.getValue())));
            c.check(song != null && "♪ Song - Artist".equals(song.getString()), "Song In F5 is not '♪ Song - Artist'");
            c.check(songCol != null && (songCol.getValue() & 0xFFFFFF) == 0x00FF00, "Song In F5 is not the F5 text color");
            BufferedImage img = HxCosmeticNameCases.shot(c, "f5-custom-colors");
            int red = 0;
            int green = 0;
            for (int y = 0; y < img.getHeight() / 2; y++) {
                for (int x = img.getWidth() / 2 - img.getWidth() / 6; x < img.getWidth() / 2 + img.getWidth() / 6; x++) {
                    int p = img.getRGB(x, y);
                    int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
                    if (r > 180 && g < 70 && b < 70) {
                        red++;
                    }
                    if (g > 180 && r < 70 && b < 70) {
                        green++;
                    }
                }
            }
            c.note("screenshot over the player: " + red + " highlight-red pixels, " + green + " text-green pixels");
            c.check(red >= 6 && green >= 6, "the screenshot does not show the custom F5 colors (" + red + " red, " + green + " green)");

            try (Settings theme = new Settings(c).with(TAGC, "CustomF5Colors", false)) {
                c.ctx().waitTicks(3);
                Component l2 = ownComponent(c, "killer560smod$lyricTag");
                int accent = c.onClient(mc -> (Integer) Mod.call(Mod.staticCall("gui.OverlayThemes", "menu"), "accentHover")) & 0xFFFFFF;
                int litCol = l2.getSiblings().get(1).getStyle().getColor().getValue() & 0xFFFFFF;
                c.check(litCol == accent, "Menu Theme: the lit word is #" + hex(litCol) + ", not the accent #" + hex(accent));
            }
            try (Settings off = new Settings(c).with(TAGC, "SongInF5", false)) {
                c.ctx().waitTicks(3);
                c.check(ownState(c, "killer560smod$songTag") == null, "Song In F5 off still shows the song line");
            }
        } finally {
            c.onClient(mc -> {
                Mod.setField(SLF, "testSnapshot", null);
                Mod.setField(SLF, "testNowMs", 0L);
                return null;
            });
            HxCosmeticNameCases.camera(c, CameraType.FIRST_PERSON);
        }
    }

    // ---- 2865: sharing the lyric line --------------------------------------------------------------------------------

    private static void shareLyrics(Session c) throws Exception {
        c.check(has(c), "this jar has no lyric sharing");
        // No relay room: a party left by an earlier case (578) made the relay retry, and each retry's disconnect resends
        // the payload, which the "into alone" check below reads as a resend (passed alone, failed after 578; spotshare-1105).
        noParty(c);
        long base = 1_900_000_000_000L;
        List<String> handed = new CopyOnWriteArrayList<>();
        BiPredicate<String, JsonElement> sink = (key, value) -> handed.add(key + " " + value);
        UUID self = c.onClient(mc -> mc.player.getUUID());
        Object savedMap = c.onClient(mc -> Mod.field(SF, "byUuid"));
        List<Object> lines = c.onClient(mc -> List.of(line(0.0, "intro"),
                line(4.0, "<00:04.00>alpha <00:04.50>beta <00:05.00>gamma"), line(8.0, "next line here"), line(600.0, "end")));
        Object snap = c.onClient(mc -> snapshot("TRACK", 0, base, lines));
        try (Settings s = noData(new Settings(c)).with("modchat.ModChatConfig", "Enabled", false).with(TAGC, "ShareSong", true).with(MUC, "Share", true)
                .with(TAGC, "ShareWhat", Mod.enumValue(TAGC + "$ShareWhat", "BOTH")).with(TAGC, "ShareWord", true)) {
            supporters(c, self);
            freeze(c, base + 4_600);
            c.onClient(mc -> {
                Mod.setField(SH, "testNowPlaying", Mod.staticCall("spotify.NowPlaying", "track", "Artist", "Title"));
                Mod.setField(SH, "testSnapshot", snap);
                Mod.setField(SH, "testSender", sink);
                return null;
            });
            c.waitUntil("a packet handed over", mc -> !handed.isEmpty(), 80);
            String both = handed.get(0);
            c.note("Song Name + Lyrics, word on: " + both);
            c.check(("np.v1 {\"state\":\"play\",\"artist\":\"Artist\",\"title\":\"Title\",\"line\":\"alpha beta gamma\","
                    + "\"into\":600,\"words\":[0,500,1000]}").equals(both), "unexpected packet: " + both);
            // Only "into" moves: nothing new is sent.
            freeze(c, base + 4_900);
            c.ctx().waitTicks(30);
            c.check(handed.size() == 1, "a moving 'into' alone was sent again: " + handed);
            // A new line goes out.
            freeze(c, base + 8_300);
            c.waitUntil("the next line handed over", mc -> handed.size() >= 2, 80);
            c.note("next line: " + handed.get(1));
            c.check(handed.get(1).contains("\"line\":\"next line here\"") && handed.get(1).contains("\"into\":300"),
                    "the next line was not sent: " + handed.get(1));
            // Lyrics only, no word times.
            handed.clear();
            c.onClient(mc -> {
                Mod.set(TAGC, "setShareWhat", Mod.enumValue(TAGC + "$ShareWhat", "LYRICS"));
                Mod.set(TAGC, "setShareWord", false);
                return null;
            });
            c.waitUntil("a lyrics-only packet", mc -> !handed.isEmpty(), 80);
            c.note("Lyrics, word off: " + handed.get(0));
            c.check(!handed.get(0).contains("title") && !handed.get(0).contains("words") && handed.get(0).contains("\"line\":\"next line here\""),
                    "Lyrics without words carries the wrong fields: " + handed.get(0));
            // Song only: no line.
            handed.clear();
            c.onClient(mc -> {
                Mod.set(TAGC, "setShareWhat", Mod.enumValue(TAGC + "$ShareWhat", "SONG"));
                return null;
            });
            c.waitUntil("a song-only packet", mc -> !handed.isEmpty(), 80);
            c.note("Song Name: " + handed.get(0));
            c.check("np.v1 {\"state\":\"play\",\"artist\":\"Artist\",\"title\":\"Title\"}".equals(handed.get(0)),
                    "Song Name sends more than the song: " + handed.get(0));
        } finally {
            c.onClient(mc -> {
                Mod.setField(SH, "testSender", null);
                Mod.setField(SH, "testNowPlaying", null);
                Mod.setField(SH, "testSnapshot", null);
                Mod.setField(SLF, "testNowMs", 0L);
                return null;
            });
        }

        // Receiving: the line over the sender, the word they are on lit.
        double[] at = c.scenario().playerPosition();
        HxCosmeticNameCases.camera(c, CameraType.FIRST_PERSON);
        HxCosmeticNameCases.look(c, 0f, -12f);
        HxCosmeticNameCases.clearChat(c);
        TestEnemy bot = TestEnemy.named("Cl", "N").at(at[0], at[1], at[2] + 5).frozen(true).spawn(c.ctx(), c.server());
        String botName = bot.name();
        try (Settings s = new Settings(c).with(TAGC, "ShowOthers", true).with(SC, "ShowSupporterNames", false)
                .with(NC, "RandomizeOthers", false).with(TAGC, "CustomF5Colors", false)
                .custom(() -> Mod.setField(RS, "testConnected", true), () -> Mod.setField(RS, "testConnected", false))) {
            UUID id = botUuid(c, botName);
            c.check(id != null, "the bot is not in the world (premise)");
            supporters(c, id);
            String dashless = id.toString().replace("-", "");
            c.onClient(mc -> {
                Mod.staticCall(RS, "clear");
                Mod.staticCall(MU, "onPresence", botName, "legit");
                Mod.staticCall(RS, "onRelayData", botName, dashless,
                        JsonParser.parseString("{\"state\":\"play\",\"artist\":\"Band\",\"title\":\"Tune\"}"));
                return null;
            });
            HxCosmeticNameCases.look(c, 0f, -12f);
            c.ctx().waitTicks(3);
            BufferedImage songOnly = HxCosmeticNameCases.shot(c, "bot-song-only");
            c.check(botState(c, botName, "killer560smod$lyricTag") == null, "an older client's song-only packet drew a lyric line");
            c.check("♪ Tune - Band".equals(botState(c, botName, "killer560smod$songTag")), "the song-only packet drew no song line");
            c.ctx().waitTicks(12);
            long sentAt = System.currentTimeMillis();
            c.onClient(mc -> {
                Mod.staticCall(RS, "onRelayData", botName, dashless, JsonParser.parseString(
                        "{\"state\":\"play\",\"artist\":\"Band\",\"title\":\"Tune\",\"line\":\"one two three four\",\"into\":100,"
                                + "\"words\":[0,1500,3000,4500]}"));
                return null;
            });
            c.ctx().waitTicks(2);
            String l0 = botState(c, botName, "killer560smod$lyricTag");
            int lit0 = c.onClient(mc -> (Integer) Mod.field(RS, "lastRemoteLit"));
            BufferedImage withLyric = HxCosmeticNameCases.shot(c, "bot-song-and-lyric");
            c.waitUntil("1.5 s into the shared line", mc -> System.currentTimeMillis() - sentAt > 1_700, 80);
            c.ctx().waitTicks(2);
            botState(c, botName, "killer560smod$lyricTag");
            int lit1 = c.onClient(mc -> (Integer) Mod.field(RS, "lastRemoteLit"));
            c.note("shared line on the bot: '" + l0 + "', lit word " + lit0 + " at once and " + lit1 + " after 1.7 s");
            c.check("♫ one two three four".equals(l0), "the bot's render state carries '" + l0 + "'");
            c.check(lit0 == 0 && lit1 == 1, "the lit word did not follow the sender's word times: " + lit0 + " then " + lit1);
            int[] name = HxCosmeticNameCases.tagAndHead(songOnly, withLyric);
            c.note("screenshot: new text rows " + name[0] + "-" + name[1] + " with the lyric line added");
            c.check(name[0] < name[1], "the screenshot shows no lyric line over the bot");

            // Bad word times: shown, nothing lit. A stale line: gone.
            c.ctx().waitTicks(12);
            c.onClient(mc -> {
                Mod.staticCall(RS, "onRelayData", botName, dashless, JsonParser.parseString(
                        "{\"state\":\"play\",\"line\":\"x y\",\"into\":5000,\"words\":[900,100]}"));
                return null;
            });
            c.ctx().waitTicks(2);
            String bad = botState(c, botName, "killer560smod$lyricTag");
            int litBad = c.onClient(mc -> (Integer) Mod.field(RS, "lastRemoteLit"));
            c.check("♫ x y".equals(bad) && litBad == -1, "bad word times: '" + bad + "', lit " + litBad);
            c.check(botState(c, botName, "killer560smod$songTag") == null, "a lyrics-only packet left a song line");
            c.ctx().waitTicks(12);
            c.onClient(mc -> {
                Mod.staticCall(RS, "onRelayData", botName, dashless, JsonParser.parseString(
                        "{\"state\":\"play\",\"line\":\"old news\",\"into\":60000,\"words\":[0,100]}"));
                return null;
            });
            c.ctx().waitTicks(2);
            c.check(botState(c, botName, "killer560smod$lyricTag") == null, "a line long past its last word is still drawn");
        } finally {
            c.onClient(mc -> {
                Mod.staticCall(MU, "onPresence", botName, null);
                Mod.staticCall(RS, "clear");
                Mod.setField(SF, "byUuid", savedMap);
                return null;
            });
            bot.remove();
        }
    }

    // ---- 2866: the relay room ----------------------------------------------------------------------------------------

    private static void room(Session c) throws Exception {
        c.check(has(c), "this jar has no Song Tag relay room");
        noParty(c);
        try (Settings s = noData(new Settings(c)).with("modchat.ModChatConfig", "Enabled", false).with(TAGC, "ShareSong", false)
                .with(TAGC, "ShowOthers", false)
                .custom(() -> {
                    Mod.setField("relay.HypixelLocation", "lastLevel", net.minecraft.client.Minecraft.getInstance().level);
                    Mod.setField("relay.HypixelLocation", "serverId", "mini1101T");
                }, () -> Mod.setField("relay.HypixelLocation", "serverId", null))) {
            c.ctx().waitTicks(30);
            String offRoom = c.onClient(mc -> (String) Mod.field("relay.RelayClient", "wantRoom"));
            c.note("both Song Tag switches off, Mod Chat off: wanted room '" + offRoom + "'");
            c.check(!offRoom.startsWith("lobby:"), "a lobby room was chosen with Song Tag off: " + offRoom);
            for (String sw : new String[]{"ShareSong", "ShowOthers"}) {
                try (Settings on = new Settings(c).with(TAGC, sw, true)) {
                    c.waitUntil("the relay wanted for " + sw, mc -> (Boolean) Mod.field("relay.RelayClient", "wantOn")
                            && String.valueOf(Mod.field("relay.RelayClient", "wantRoom")).startsWith("lobby:"), 80);
                    String r = c.onClient(mc -> (String) Mod.field("relay.RelayClient", "wantRoom"));
                    Object mode = c.onClient(mc -> Mod.field("modchat.ModChatFeature", "lastMode"));
                    c.note(sw + " on: relay on, room '" + r + "', mode " + mode);
                    c.check(String.valueOf(mode).equals("LOBBY"), sw + " on: mode " + mode);
                }
            }
            // His case (killer560, 2026-11-04, "26.3 Default"): Mod Chat ON in Party mode, its default, and no party. Party
            // mode had no room, so the relay stayed shut and no song went either way.
            try (Settings on = new Settings(c).with("modchat.ModChatConfig", "Enabled", true)
                    .with("modchat.ModChatConfig", "RoomMode", Mod.enumValue("relay.RelayRoom$Mode", "PARTY"))
                    .with(TAGC, "ShowOthers", true)) {
                noParty(c);
                c.ctx().waitTicks(45); // past the next room check (every 40 ticks), so the wanted room is not the block above's
                c.waitUntil("the lobby room wanted with Mod Chat on in Party mode", mc -> (Boolean) Mod.field("relay.RelayClient", "wantOn")
                        && String.valueOf(Mod.field("relay.RelayClient", "wantRoom")).startsWith("lobby:"), 80);
                String r = c.onClient(mc -> (String) Mod.field("relay.RelayClient", "wantRoom"));
                String said = c.onClient(mc -> (String) Mod.staticCall("modchat.ModChatFeature", "send", "hello lobby"));
                c.note("Mod Chat on, Party, no party: room '" + r + "'; /kc says: " + said);
                c.check(said.contains("not in a party"), "Mod Chat would send into the Song Tag lobby room: " + said);
            }
        }
        c.ctx().waitTicks(30);
    }

    /** Party data in a dungeon takes the party room before Song Tag; an earlier case (or the session's dungeon) can leave
     *  it on, so these cases turn it off: their premise is no dungeon data, no party. */
    static Settings noData(Settings s) {
        return s.with("interop.InteropConfig", "RelayData", false).with("partydata.PartyDataConfig", "ShareEnabled", false)
                .with("melody.MelodyHudConfig", "ShareProgress", false);
    }

    /** His case is "no party": an earlier case may leave the session in its F7 dungeon, whose tab list keeps HxMateA and
     *  HxMateB in PartyTracker (so Party mode HAS a room); go to the hub and forget them. */
    static void noParty(Session c) {
        HxKit.hub(c);
        c.onClient(mc -> Mod.staticCall("leapmenu.PartyTracker", "clearParty"));
        c.check(c.onClient(mc -> ((List<?>) Mod.staticCall("leapmenu.PartyTracker", "teammates")).isEmpty()),
                "PartyTracker still lists mates (premise: no party)");
    }

    // ---- 2867: through a real relay ----------------------------------------------------------------------------------

    private static String b64url(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** A relay token, made as the relay's src/token.ts makes it, with the local relay's throwaway secret. */
    static String token(UUID uuid, String name) throws Exception {
        String body = b64url(("{\"uuid\":\"" + uuid + "\",\"name\":\"" + name + "\",\"exp\":"
                + (System.currentTimeMillis() + 3_600_000L) + "}").getBytes(StandardCharsets.UTF_8));
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(E2E_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return body + "." + b64url(mac.doFinal(body.getBytes(StandardCharsets.US_ASCII)));
    }

    private static String localRelay() {
        String r = System.getProperty("killer560.net.relay", "");
        return r.isEmpty() ? null : r;
    }

    private static void relay(Session c) throws Exception {
        c.check(has(c), "this jar has no 2026-11-01 Song Tag");
        String base = localRelay();
        c.check(base != null, "no relay override");
        double[] at = c.scenario().playerPosition();
        HxCosmeticNameCases.camera(c, CameraType.FIRST_PERSON);
        HxCosmeticNameCases.look(c, 0f, -12f);
        HxCosmeticNameCases.clearChat(c);
        BufferedImage none = HxCosmeticNameCases.shot(c, "relay-before-bot");
        TestEnemy bot = TestEnemy.named("Cr", "N").at(at[0], at[1], at[2] + 5).frozen(true).spawn(c.ctx(), c.server());
        String botName = bot.name();
        Object savedMap = c.onClient(mc -> Mod.field(SF, "byUuid"));
        List<String> peerGot = new CopyOnWriteArrayList<>();
        WebSocket peer = null;
        long base0 = 1_900_000_000_000L;
        List<Object> lines = c.onClient(mc -> List.of(line(0.0, "intro"),
                line(4.0, "<00:04.00>shared <00:04.50>through <00:05.00>relay"), line(600.0, "end")));
        Object snap = c.onClient(mc -> snapshot("TRACK", 0, base0, lines));
        try (Settings s = noData(new Settings(c)).with("modchat.ModChatConfig", "Enabled", true)
                .with("modchat.ModChatConfig", "RoomMode", Mod.enumValue("relay.RelayRoom$Mode", "PARTY"))
                .with(TAGC, "ShareSong", true).with(TAGC, "ShowOthers", true).with(MUC, "Share", true).with(SC, "ShowSupporterNames", false)
                .with(NC, "RandomizeOthers", false)
                .with(TAGC, "ShareWhat", Mod.enumValue(TAGC + "$ShareWhat", "BOTH")).with(TAGC, "ShareWord", true)
                .custom(() -> {
                    Mod.setField("relay.HypixelLocation", "lastLevel", net.minecraft.client.Minecraft.getInstance().level);
                    Mod.setField("relay.HypixelLocation", "serverId", "mini1101E2E");
                }, () -> Mod.setField("relay.HypixelLocation", "serverId", null))) {
            UUID self = c.onClient(mc -> mc.player.getUUID());
            String selfName = c.onClient(mc -> mc.player.getGameProfile().name());
            UUID botId = botUuid(c, botName);
            c.check(botId != null, "the bot is not in the world (premise)");
            supporters(c, self, botId);
            noParty(c);
            // The client's own relay token, as the relay would issue it (an offline account cannot do Mojang's sign-in).
            String mine = token(self, selfName);
            c.onClient(mc -> {
                String url = (String) Mod.staticCall("relay.RelayEndpoint", "normalise",
                        Mod.call(Mod.cfg("modchat.ModChatConfig"), "getRelayUrl"));
                Object tok = newRecord("relay.RelayAuth$Token", mine, System.currentTimeMillis() + 3_600_000L);
                Mod.setField("relay.RelayClient", "token", tok);
                Mod.setField("relay.RelayClient", "tokenUrl", url);
                Mod.setField("relay.RelayClient", "tokenAccount", mc.getUser().getProfileId().toString());
                Mod.setField(SH, "testNowPlaying", Mod.staticCall("spotify.NowPlaying", "track", "Artist", "Title"));
                Mod.setField(SH, "testSnapshot", snap);
                Mod.setField(SLF, "testNowMs", base0 + 4_600);
                return null;
            });
            c.waitUntil("the client connected to its lobby room on the local relay",
                    mc -> (Boolean) Mod.staticCall("relay.RelayClient", "isConnected")
                            && String.valueOf(Mod.staticCall("relay.RelayClient", "room")).startsWith("lobby:"), 400);
            String room = c.onClient(mc -> (String) Mod.staticCall("relay.RelayClient", "room"));
            c.note("client connected on its own, room " + room);

            // A second relay client, the bot's account, in the same room.
            String wsBase = base.replaceFirst("^http", "ws");
            URI uri = URI.create(wsBase + "/ws?room=" + java.net.URLEncoder.encode(room, StandardCharsets.UTF_8)
                    + "&token=" + java.net.URLEncoder.encode(token(botId, botName), StandardCharsets.UTF_8));
            StringBuilder partial = new StringBuilder();
            peer = HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(uri, new WebSocket.Listener() {
                @Override
                public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                    partial.append(data);
                    if (last) {
                        peerGot.add(partial.toString());
                        partial.setLength(0);
                    }
                    ws.request(1);
                    return null;
                }
            }).get(15, java.util.concurrent.TimeUnit.SECONDS);
            peer.sendText("{\"type\":\"hello\",\"variant\":\"legit\",\"share\":true,\"shareAs\":\"legit\"}", true).get();
            c.waitUntil("the client sees the peer as a mod user", mc -> Mod.staticCall(MU, "variantOf", botName) != null, 200);
            // The peer joined after the client's first packet; a join triggers a resend.
            c.waitUntil("the peer received the client's song through the relay", mc -> {
                for (String m : peerGot) {
                    if (m.contains("\"key\":\"np.v1\"") && m.contains("\"from\":\"" + selfName + "\"") && m.contains("\"line\":\"shared through relay\"")) {
                        return true;
                    }
                }
                return false;
            }, 400);
            String got = null;
            for (String m : peerGot) {
                if (m.contains("\"key\":\"np.v1\"")) {
                    got = m;
                }
            }
            c.note("the peer received: " + got);
            JsonObject data = JsonParser.parseString(got).getAsJsonObject();
            JsonObject v = data.getAsJsonObject("value");
            c.check(selfName.equals(data.get("from").getAsString()) && "Title".equals(v.get("title").getAsString())
                    && "[0,500,1000]".equals(v.get("words").toString()), "the relayed packet is not the client's: " + got);

            // The peer shares a song and a lyric line: the client draws both over the bot.
            peer.sendText("{\"type\":\"data\",\"key\":\"np.v1\",\"value\":{\"state\":\"play\",\"artist\":\"PeerBand\","
                    + "\"title\":\"PeerSong\",\"line\":\"hello from the peer\",\"into\":0,\"words\":[0,400,800,1200]}}", true).get();
            c.waitUntil("the client drew the peer's song over the bot",
                    mc -> "♪ PeerSong - PeerBand".equals(botStateHere(mc, botName, "killer560smod$songTag")), 200);
            HxCosmeticNameCases.look(c, 0f, -12f);
            c.ctx().waitTicks(3);
            String lyric = botState(c, botName, "killer560smod$lyricTag");
            BufferedImage withBoth = HxCosmeticNameCases.shot(c, "relay-peer-song-lyric");
            int[] rows = HxCosmeticNameCases.tagAndHead(withBoth, none);
            c.note("over the bot through the relay: song '" + botState(c, botName, "killer560smod$songTag") + "', lyric '"
                    + lyric + "'; new text rows " + rows[0] + "-" + rows[1]);
            c.check("♫ hello from the peer".equals(lyric), "the peer's lyric line is not over the bot: " + lyric);
            c.check(rows[0] < rows[1], "the screenshot shows nothing over the bot");
        } finally {
            if (peer != null) {
                peer.abort();
            }
            c.onClient(mc -> {
                Mod.setField(SH, "testNowPlaying", null);
                Mod.setField(SH, "testSnapshot", null);
                Mod.setField(SLF, "testNowMs", 0L);
                Mod.staticCall(MU, "onPresence", botName, null);
                Mod.staticCall(RS, "clear");
                Mod.setField(SF, "byUuid", savedMap);
                return null;
            });
            bot.remove();
        }
    }

    // ---- 2868: a burst of volume settings ----------------------------------------------------------------------------

    private static void volumeBurst(Session c) throws Exception {
        c.check(has(c), "this jar has no volume coalescing");
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        c.check(windows, "needs Windows (the helper is PowerShell)");
        Object media = c.onClient(mc -> Mod.staticCall(SLF, "media"));
        long before = c.onClient(mc -> (Long) Mod.field(media, "results"));
        c.onClient(mc -> Mod.call(media, "send", "volq"));
        c.waitUntil("the helper answered volq", mc -> (Long) Mod.field(media, "results") > before, 600);
        int current = c.onClient(mc -> (Integer) Mod.call(media, "volume"));
        long skipped0 = c.onClient(mc -> (Long) Mod.field(media, "volumesSkipped"));
        long results0 = c.onClient(mc -> (Long) Mod.field(media, "results"));
        // Spotify running: every value is the one it already has, so nothing is heard. Not running: a drag 100 -> 50.
        c.onClient(mc -> {
            StringBuilder burst = new StringBuilder();
            for (int v = 100; v >= 50; v--) {
                burst.append("vol ").append(current >= 0 ? current : v).append('\n');
            }
            try {
                java.lang.reflect.Field pf = Mod.cls("spotify.SpotifyMediaSession").getDeclaredField("process");
                pf.setAccessible(true);
                Process p = (Process) pf.get(media);
                p.getOutputStream().write(burst.toString().getBytes(StandardCharsets.UTF_8));
                p.getOutputStream().flush();
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            return null;
        });
        long t0 = System.currentTimeMillis();
        c.waitUntil("the burst answered", mc -> String.valueOf(Mod.field(media, "lastResult")).startsWith("vol ")
                && (Long) Mod.field(media, "results") > results0, 400);
        long took = System.currentTimeMillis() - t0;
        c.ctx().waitTicks(40);
        long skipped = c.onClient(mc -> (Long) Mod.field(media, "volumesSkipped")) - skipped0;
        long ran = c.onClient(mc -> (Long) Mod.field(media, "results")) - results0;
        String last = c.onClient(mc -> (String) Mod.field(media, "lastResult"));
        c.note("51 volume settings at once (Spotify volume " + current + "): " + ran + " ran, " + skipped
                + " skipped, answered in " + took + " ms, last '" + last + "', through '" + c.onClient(mc -> Mod.call(media, "volumeHow")) + "'");
        c.check(ran == 1 && skipped == 50, "the burst was not collapsed to its last value: " + ran + " ran, " + skipped + " skipped");
    }

    // ---- 2869: the cover, and another player's reading ---------------------------------------------------------------

    private static void cover(Session c) throws Exception {
        c.check(has(c), "this jar has no 2026-11-01 Spotify fixes");
        long now = System.currentTimeMillis();
        List<Object> lines = c.onClient(mc -> List.of(line(0.0, "a line"), line(600.0, "later")));
        Object snap = c.onClient(mc -> snapshot("TRACK", 5000, now, lines));
        // A 64x64 PNG in pure magenta: easy to find on screen.
        java.awt.image.BufferedImage png = new java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                png.setRGB(x, y, 0xFFFF00FF);
            }
        }
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(png, "png", bytes);
        Object media = c.onClient(mc -> Mod.staticCall(SLF, "media"));
        Object oldCover = c.onClient(mc -> Mod.field(media, "cover"));
        try (Settings s = new Settings(c).with(HC, "HudEnabled", true).with(HC, "AlwaysShowControls", false)) {
            c.onClient(mc -> {
                Mod.setField(SLF, "testSnapshot", snap);
                setInstance(media, "cover", newRecord("spotify.SpotifyMediaSession$Cover", 777, "Artist", "Song", bytes.toByteArray()));
                return null;
            });
            HxCosmeticNameCases.camera(c, CameraType.FIRST_PERSON);
            c.ctx().waitTicks(5);
            BufferedImage img = HxCosmeticNameCases.shot(c, "hud-cover");
            int[] p = hudPixel(c, 5 + 16, 5 + 16, img);
            int col = img.getRGB(p[0], p[1]);
            c.note("cover tile center #" + hex(col));
            c.check(dist(col, 0xFF00FF) < 60, "the HUD did not draw the cover (tile center #" + hex(col) + ")");

            // Another player's reading becomes the track when the Spotify app has none.
            Object timeline = newRecord("spotify.SpotifyMediaSession$Timeline", "Web Artist", "Web Song", 4, 1000L, 200_000L,
                    now, now, 1);
            Object np = c.onClient(mc -> Mod.staticCall("spotify.LyricsEngine", "otherPlayer", timeline));
            c.note("another player's playing reading -> " + np);
            c.check(String.valueOf(np).contains("TRACK") && String.valueOf(np).contains("Web Song"),
                    "another player's reading was not taken as the track: " + np);
        } finally {
            c.onClient(mc -> {
                Mod.setField(SLF, "testSnapshot", null);
                setInstance(media, "cover", oldCover);
                return null;
            });
        }
    }
}
