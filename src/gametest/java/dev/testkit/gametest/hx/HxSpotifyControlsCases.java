package dev.testkit.gametest.hx;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.CameraType;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.network.chat.Component;

import java.awt.image.BufferedImage;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Spotify Controls, the lyric clock re-sync, My Lyrics In F5, Smooth Lyric Scroll and Share My Song for supporters only
 * (mod branch spotify-controls-1026, killer560 2026-10-26). Nothing here reaches the real Spotify on the machine: every
 * command goes to {@code SpotifyControls.testSink}, and the lyric state is a made-up {@code LyricsEngine} or snapshot.
 * <ul>
 *   <li>2840 drift: a LyricsEngine fed a simulated Spotify through {@code pollAt} - started 700 ms before the title was
 *       seen, its audio clock 0.5 % slow then 1 % fast, readings refreshed every 4.5 s and read every 500 ms, a seek back
 *       in Spotify, a pause and a resume, and a Skip Ahead from the mod ({@code nudge}). The HUD's clock stays within
 *       100 ms of Spotify's true position everywhere outside the detection window after each event.</li>
 *   <li>2841 controls: every button in the tab and every key sends the right command; a key does nothing with chat
 *       open; key capture binds and Esc unbinds; a control that cannot work is grayed out with its reason as hover.</li>
 *   <li>2842 volume: the volume, the step settings and the keys are saved, loaded back, and survive a profile reload.</li>
 *   <li>2843 F5: with My Lyrics In F5 on, your own render state carries the lyric being sung, it is submitted in third
 *       person (and your name is shown under it with My Name in F5 off), not in first person, and not with it off; the
 *       screenshot shows new text above the player.</li>
 *   <li>2844 scroll: when the line changes in the Spotify HUD the drawn offset eases from a row down to 0 over several
 *       frames (strictly decreasing, within 400 ms); with Smooth Lyric Scroll off it never moves.</li>
 *   <li>2846 F5 word (spotify-f5word-1026): with the clock frozen at six moments through a line, the word the F5 lyric
 *       line lights is the word the Spotify HUD lights at that moment, in the HUD's accent color and underlined; with
 *       Highlight Word In F5 off, or paused, none is lit; with the time held the line is not rebuilt each frame.</li>
 *   <li>2845 share: with Share My Song forced on in the config, a non-supporter hands nothing to the relay and has no own
 *       tag, and the row is grayed out ("Supporters only") when off; in the supporter list the play packet goes out.</li>
 * </ul>
 */
final class HxSpotifyControlsCases {

    private static final String E = "spotify.LyricsEngine";
    private static final String CTL = "spotify.SpotifyControls";
    private static final String CC = "spotify.SpotifyControlsConfig";
    private static final String SLF = "spotify.SpotifyLyricsFeature";
    private static final String HUD = "spotify.SpotifyHud";
    private static final String HC = "spotify.SpotifyHudConfig";
    private static final String TAGC = "spotify.SongShareConfig";
    private static final String SH = "spotify.SongShare";
    private static final String STD = "spotify.SongTagDraw";
    private static final String SF = "supporters.SupportersFeature";
    private static final String SC = "supporters.SupportersConfig";
    private static final String MUC = "modusers.ModUserConfig";

    private HxSpotifyControlsCases() {
    }

    static void register(Session s) {
        s.test("2840-hx-spotify-drift", HxSpotifyControlsCases::drift);
        s.test("2841-hx-spotify-controls", HxSpotifyControlsCases::controls);
        s.test("2842-hx-spotify-volume", HxSpotifyControlsCases::volume);
        s.test("2843-hx-spotify-f5", HxSpotifyControlsCases::f5);
        s.test("2844-hx-spotify-scroll", HxSpotifyControlsCases::scroll);
        s.test("2845-hx-spotify-share-supporter", HxSpotifyControlsCases::share);
        s.test("2846-hx-spotify-f5-word", HxSpotifyControlsCases::f5Word);
    }

    // ---- reflection helpers -----------------------------------------------------------------------------------

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
        return newRecord("spotify.LyricsEngine$LyricLine", t, text, Mod.staticCall("spotify.LyricTiming", "parse", text, t));
    }

    private static Object snapshot(long playedMs, long sinceMs, List<Object> lines) {
        return newRecord("spotify.LyricsEngine$Snapshot", Mod.enumValue("spotify.NowPlaying$Kind", "TRACK"), "Artist",
                "Song", playedMs, sinceMs, 200.0, lines, true, false, 0.0, true);
    }

    private static boolean has(Session c) {
        return c.onClient(mc -> Mod.has(CTL) && Mod.has(CC));
    }

    // ---- 2840: drift, seek, pause, resume, nudge --------------------------------------------------------------

    /** Spotify's real playback, as the simulation's truth. */
    private static final class Spot {
        boolean playing;
        long basePos;
        long baseWall;
        double rate = 1.0;
        long refreshWall;
        long refreshPos;

        long pos(long w) {
            return playing ? basePos + Math.round((w - baseWall) * rate) : basePos;
        }

        void refresh(long w) {
            refreshPos = pos(w);
            refreshWall = w;
        }

        void play(long w) {
            basePos = pos(w);
            baseWall = w;
            playing = true;
            refresh(w);
        }

        void pause(long w) {
            basePos = pos(w);
            baseWall = w;
            playing = false;
            refresh(w);
        }

        void seek(long w, long p) {
            basePos = p;
            baseWall = w;
            refresh(w);
        }

        void rate(long w, double r) {
            basePos = pos(w);
            baseWall = w;
            rate = r;
        }
    }

    private static void drift(Session c) throws Exception {
        c.check(c.onClient(mc -> Mod.has(E) && Mod.has(CTL)), "this jar has no Spotify Controls (mod before spotify-controls-1026)");
        String result = c.onClient(mc -> {
            try {
                Object eng = Mod.cls(E).getDeclaredConstructor().newInstance();
                Mod.call(eng, "setFetchLyrics", false);
                Object track = Mod.staticCall("spotify.NowPlaying", "track", "Artist", "Song");
                Object paused = Mod.staticCall("spotify.NowPlaying", "paused");
                List<Object> lines = new ArrayList<>();
                for (int i = 0; i < 150; i++) {
                    lines.add(line(i * 2.0, "line number " + i));
                }
                long t0 = 1_000_000_000_000L;
                Spot spot = new Spot();
                spot.rate = 0.995;
                spot.play(t0 - 700);   // Spotify started the song 700 ms before the title change was seen
                Object timeline = null;
                long nextRefresh = t0 - 700 + 4500;
                double maxErr = 0;
                long maxAt = 0;
                int samples = 0;
                long[][] windows = {{0, 1000}, {30_000, 31_000}, {50_000, 51_000}, {60_000, 61_000}, {80_000, 80_150}};
                boolean installed = false;
                for (long t = 0; t <= 120_000; t += 50) {
                    long w = t0 + t;
                    // events in Spotify
                    if (t == 30_000) {
                        spot.seek(w, spot.pos(w) - 20_000);   // rewound in Spotify itself
                    } else if (t == 50_000) {
                        spot.pause(w);
                    } else if (t == 60_000) {
                        spot.play(w);
                    } else if (t == 80_000) {
                        Mod.call(eng, "nudge", 10_000L, w);   // Skip Ahead from the mod...
                    } else if (t == 80_100) {
                        spot.seek(w, spot.pos(w) + 10_000);  // ...which Spotify carries out 100 ms later
                    } else if (t == 100_000) {
                        spot.rate(w, 1.01);
                    }
                    if (w >= nextRefresh) {
                        spot.refresh(w);
                        nextRefresh = w + 4500;
                    }
                    if (spot.refreshWall == w) {
                        nextRefresh = w + 4500;
                    }
                    // the reader samples every 500 ms
                    if (t % 500 == 0) {
                        timeline = newRecord("spotify.SpotifyMediaSession$Timeline", "Artist", "Song",
                                spot.playing ? 4 : 5, spot.refreshPos, 200_000L, spot.refreshWall, w, 0);
                    }
                    // the engine polls every 250 ms
                    if (t % 250 == 0) {
                        Mod.call(eng, "pollAt", spot.playing ? track : paused, timeline, w, false);
                        if (!installed) {
                            setInstanceField(eng, "syncedLyrics", List.copyOf(lines));
                            installed = true;
                        }
                    }
                    boolean inWindow = false;
                    for (long[] win : windows) {
                        if (t >= win[0] && t < win[1]) {
                            inWindow = true;
                        }
                    }
                    if (inWindow) {
                        continue;
                    }
                    Object snap = Mod.call(eng, "snapshot");
                    double shown = (Double) Mod.call(snap, "elapsedSeconds", w) * 1000.0;
                    double err = Math.abs(shown - spot.pos(w));
                    samples++;
                    if (err > maxErr) {
                        maxErr = err;
                        maxAt = t;
                    }
                }
                long corrections = (Long) Mod.field(eng, "corrections");
                return maxErr + "|" + maxAt + "|" + samples + "|" + corrections;
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        String[] f = result.split("\\|");
        double maxErr = Double.parseDouble(f[0]);
        c.note("drift sim: max error " + Math.round(maxErr) + " ms at t=" + f[1] + " ms over " + f[2]
                + " samples, " + f[3] + " corrections");
        c.check(Integer.parseInt(f[2]) > 2000, "too few samples were checked: " + f[2]);
        c.check(Long.parseLong(f[3]) >= 5, "the clock was corrected only " + f[3] + " times");
        c.check(maxErr <= 100, "the lyric clock was " + Math.round(maxErr) + " ms off Spotify at t=" + f[1] + " ms");
    }

    // ---- 2841: buttons and keys -------------------------------------------------------------------------------

    private static List<AbstractWidget> buildTab(Session c) {
        return c.onClient(mc -> {
            try {
                Object tab = Mod.cls("gui.tab.SpotifyTab").getDeclaredConstructor().newInstance();
                Runnable none = () -> { };
                @SuppressWarnings("unchecked")
                List<AbstractWidget> w = (List<AbstractWidget>) Mod.call(tab, "buildWidgets", 0, 0, 320, none);
                lastTab = tab;
                return w;
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
    }

    private static Object lastTab;

    private static AbstractWidget find(List<AbstractWidget> widgets, String startsWith) {
        for (AbstractWidget w : widgets) {
            if (w.getMessage().getString().startsWith(startsWith)) {
                return w;
            }
        }
        throw new AssertionError("no row starting '" + startsWith + "' in the Spotify tab");
    }

    private static String hover(Session c, AbstractWidget w) {
        return c.onClient(mc -> (String) Mod.staticCall("gui.SettingTooltips", "describe", "Spotify Lyrics", w,
                w.getMessage().getString()));
    }

    private static void controls(Session c) throws Exception {
        c.check(has(c), "this jar has no Spotify Controls (mod before spotify-controls-1026)");
        List<String> sent = new CopyOnWriteArrayList<>();
        Consumer<String> sink = sent::add;
        int[] saved = c.onClient(mc -> {
            Object cfg = Mod.cfg(CC);
            int[] k = new int[8];
            for (int i = 0; i < 7; i++) {
                k[i] = (Integer) Mod.call(cfg, "getKey", Mod.cls(CTL + "$Action").getEnumConstants()[i]);
            }
            k[7] = (Integer) Mod.call(cfg, "getVolume");
            return k;
        });
        try {
            c.onClient(mc -> {
                Mod.setField(CTL, "testSink", sink);
                Mod.setField(CTL, "testReason", "");
                Mod.call(Mod.cfg(CC), "setSeekSeconds", 10);
                return null;
            });
            List<AbstractWidget> w = buildTab(c);
            String[][] buttons = {{"Previous", "prev"}, {"Play/Pause", "toggle"}, {"Next", "next"},
                    {"Rewind 10s", "rel -10000"}, {"Skip Ahead 10s", "rel 10000"}};
            for (String[] b : buttons) {
                AbstractWidget btn = find(w, b[0]);
                sent.clear();
                c.onClient(mc -> Mod.call(btn, "press"));
                c.note("button '" + b[0] + "' sent " + sent);
                c.check(sent.equals(List.of(b[1])), "button '" + b[0] + "' sent " + sent + ", not [" + b[1] + "]");
            }

            // Keys: KP 1..7, one per action, through the held-key hook the tick reads.
            Object[] actions = c.onClient(mc -> Mod.cls(CTL + "$Action").getEnumConstants());
            String[] expect = {"toggle", "prev", "rel -10000", "rel 10000", "next", "vol 55", "vol 50"};
            c.onClient(mc -> {
                Object cfg = Mod.cfg(CC);
                for (int i = 0; i < actions.length; i++) {
                    Mod.call(cfg, "setKey", actions[i], 321 + i);
                }
                Mod.call(cfg, "setVolume", 50);
                return null;
            });
            for (int i = 0; i < actions.length; i++) {
                int code = 321 + i;
                sent.clear();
                c.onClient(mc -> {
                    Mod.setField(CTL, "testHeldKey", code);
                    return null;
                });
                c.ctx().waitTicks(2);
                c.onClient(mc -> {
                    Mod.setField(CTL, "testHeldKey", -1);
                    return null;
                });
                c.ctx().waitTicks(2);
                c.note("key for " + actions[i] + " sent " + sent);
                c.check(sent.equals(List.of(expect[i])), "the key for " + actions[i] + " sent " + sent
                        + ", not [" + expect[i] + "]");
            }
            // Not while typing: chat open, the Play/Pause key does nothing.
            sent.clear();
            c.ctx().runOnClient(mc -> McCompat.setScreen(mc, new ChatScreen("", false)));
            c.ctx().waitTicks(2);
            c.onClient(mc -> {
                Mod.setField(CTL, "testHeldKey", 321);
                return null;
            });
            c.ctx().waitTicks(3);
            c.onClient(mc -> {
                Mod.setField(CTL, "testHeldKey", -1);
                return null;
            });
            c.ctx().runOnClient(mc -> McCompat.setScreen(mc, null));
            c.ctx().waitTicks(3);
            c.note("with chat open the key sent " + sent);
            c.check(sent.isEmpty(), "a control key fired while chat was open: " + sent);

            // Key capture: the Play/Pause Key row listens, a key binds, Esc unbinds.
            w = buildTab(c);
            AbstractWidget keyRow = find(w, "Play/Pause Key");
            c.onClient(mc -> Mod.call(keyRow, "press"));
            c.check(c.onClient(mc -> (Boolean) Mod.call(lastTab, "isListeningForKey")), "the key row did not start listening");
            c.onClient(mc -> Mod.call(lastTab, "onKeyCaptured", 329));
            int bound = c.onClient(mc -> (Integer) Mod.call(Mod.cfg(CC), "getKey", actions[0]));
            c.check(bound == 329, "key capture did not bind KP 9: " + bound);
            c.onClient(mc -> Mod.call(keyRow, "press"));
            c.onClient(mc -> Mod.call(lastTab, "onKeyCaptured", 256));
            int unbound = c.onClient(mc -> (Integer) Mod.call(Mod.cfg(CC), "getKey", actions[0]));
            c.check(unbound == -1, "Esc did not unbind: " + unbound);

            // Grayed out with the reason when it cannot work.
            String why = "Spotify is not open on this PC.";
            c.onClient(mc -> {
                Mod.setField(CTL, "testReason", why);
                return null;
            });
            w = buildTab(c);
            for (String[] b : buttons) {
                AbstractWidget btn = find(w, b[0]);
                c.check(!btn.active, "'" + b[0] + "' is not grayed out while Spotify cannot be controlled");
                String h = hover(c, btn);
                c.check(why.equals(h), "'" + b[0] + "' hover is not the reason: " + h);
                sent.clear();
                c.onClient(mc -> Mod.call(btn, "press"));
                c.check(sent.isEmpty(), "a grayed-out '" + b[0] + "' still sent " + sent);
            }
            AbstractWidget slider = find(w, "Spotify Volume");
            c.check(why.equals(hover(c, slider)), "the volume slider's hover is not the reason: " + hover(c, slider));
        } finally {
            c.onClient(mc -> {
                Mod.setField(CTL, "testSink", null);
                Mod.setField(CTL, "testReason", null);
                Mod.setField(CTL, "testHeldKey", -1);
                Object cfg = Mod.cfg(CC);
                Object[] acts = Mod.cls(CTL + "$Action").getEnumConstants();
                for (int i = 0; i < 7; i++) {
                    Mod.call(cfg, "setKey", acts[i], saved[i]);
                }
                Mod.call(cfg, "setVolume", saved[7]);
                Mod.call(cfg, "save");
                return null;
            });
        }
    }

    // ---- 2842: settings persist -------------------------------------------------------------------------------

    private static void volume(Session c) throws Exception {
        c.check(has(c), "this jar has no Spotify Controls (mod before spotify-controls-1026)");
        List<String> sent = new CopyOnWriteArrayList<>();
        Consumer<String> sink = sent::add;
        Path file = c.onClient(mc -> (Path) Mod.staticCall("util.ModPaths", "config", "killer560smod-spotify-controls.json"));
        byte[] before = Files.exists(file) ? Files.readAllBytes(file) : null;
        try {
            c.onClient(mc -> {
                Mod.setField(CTL, "testSink", sink);
                Mod.setField(CTL, "testReason", "");
                return null;
            });
            List<AbstractWidget> w = buildTab(c);
            AbstractWidget slider = find(w, "Spotify Volume");
            c.onClient(mc -> {
                Mod.call(slider, "setValue", 0.37);
                Mod.call(Mod.cfg(CC), "setSeekSeconds", 15);
                Mod.call(Mod.cfg(CC), "setVolumeStep", 10);
                Mod.call(Mod.cfg(CC), "setKey", Mod.enumValue(CTL + "$Action", "NEXT"), 333);
                Mod.call(Mod.cfg(CC), "save");
                return null;
            });
            c.note("slider sent " + sent + ", label now '" + slider.getMessage().getString() + "'");
            c.check(sent.contains("vol 37"), "the slider at 37% did not send 'vol 37': " + sent);
            String json = Files.readString(file);
            c.note("saved: " + json.replaceAll("\\s+", " "));
            c.check(json.contains("\"volume\": 37"), "the volume was not saved: " + json);
            int[] loaded = c.onClient(mc -> {
                Mod.staticCall(CC, "load");
                Object cfg = Mod.cfg(CC);
                return new int[]{(Integer) Mod.call(cfg, "getVolume"), (Integer) Mod.call(cfg, "getSeekSeconds"),
                        (Integer) Mod.call(cfg, "getVolumeStep"),
                        (Integer) Mod.call(cfg, "getKey", Mod.enumValue(CTL + "$Action", "NEXT"))};
            });
            c.check(loaded[0] == 37 && loaded[1] == 15 && loaded[2] == 10 && loaded[3] == 333,
                    "after a reload: volume " + loaded[0] + ", step " + loaded[1] + ", volume step " + loaded[2]
                            + ", Next key " + loaded[3]);
            // A profile apply reloads every config: this one must be in ProfileManager.reloadAllConfigs.
            c.onClient(mc -> {
                Mod.call(Mod.cfg(CC), "setVolume", 80);   // in memory only
                Mod.staticCall("profiles.ProfileManager", "reloadAllConfigs");
                return null;
            });
            int afterProfile = c.onClient(mc -> (Integer) Mod.call(Mod.cfg(CC), "getVolume"));
            c.check(afterProfile == 37, "ProfileManager.reloadAllConfigs did not reload the controls file: " + afterProfile);
            // Defaults: every key unbound, 10 s, 5 %.
            boolean defaults = c.onClient(mc -> {
                try {
                    Constructor<?> k = Mod.cls(CC).getDeclaredConstructor();
                    k.setAccessible(true);
                    Object fresh = k.newInstance();
                    for (Object a : Mod.cls(CTL + "$Action").getEnumConstants()) {
                        if ((Integer) Mod.call(fresh, "getKey", a) != -1) {
                            return false;
                        }
                    }
                    return (Integer) Mod.call(fresh, "getSeekSeconds") == 10 && (Integer) Mod.call(fresh, "getVolumeStep") == 5;
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            c.check(defaults, "the defaults are not: every key unbound, 10 s, 5 %");
        } finally {
            if (before != null) {
                Files.write(file, before);
            } else {
                Files.deleteIfExists(file);
            }
            c.onClient(mc -> {
                Mod.setField(CTL, "testSink", null);
                Mod.setField(CTL, "testReason", null);
                Mod.staticCall(CC, "load");
                return null;
            });
        }
    }

    // ---- 2843: your lyric over your head in F5 ----------------------------------------------------------------

    private static String ownState(Session c, String accessor) {
        return c.onClient(mc -> {
            EntityRenderState state = mc.getEntityRenderDispatcher().extractEntity(mc.player, 1f);
            Object t = Mod.call(state, accessor);
            return t == null ? null : ((Component) t).getString();
        });
    }

    private static boolean ownNameShown(Session c) {
        return c.onClient(mc -> mc.getEntityRenderDispatcher().extractEntity(mc.player, 1f).nameTag != null);
    }

    private static long counter(Session c, String field) {
        return c.onClient(mc -> (Long) Mod.field(STD, field));
    }

    private static void f5(Session c) throws Exception {
        c.check(has(c), "this jar has no My Lyrics In F5 (mod before spotify-controls-1026)");
        long now = System.currentTimeMillis();
        List<Object> lines = c.onClient(mc -> List.of(line(0.0, "the first line"), line(4.0, "Hello from the lyric"),
                line(600.0, "much later")));
        Object snap = c.onClient(mc -> snapshot(5000, now, lines));
        try (Settings s = new Settings(c).with(TAGC, "LyricsOverHead", true).with(TAGC, "ShareSong", false)
                .with(SC, "OwnNameInF5", false)) {
            c.onClient(mc -> {
                Mod.setField(SLF, "testSnapshot", snap);
                return null;
            });
            HxCosmeticNameCases.look(c, 0f, 0f);
            HxCosmeticNameCases.clearChat(c);

            HxCosmeticNameCases.camera(c, CameraType.FIRST_PERSON);
            c.ctx().waitTicks(5);
            long a = counter(c, "ownLyricSubmitted");
            c.ctx().waitTicks(10);
            long firstPerson = counter(c, "ownLyricSubmitted") - a;
            c.check(firstPerson == 0, "your lyric was drawn in first person (" + firstPerson + " submits)");

            HxCosmeticNameCases.camera(c, CameraType.THIRD_PERSON_BACK);
            c.ctx().waitTicks(5);
            long b = counter(c, "ownLyricSubmitted");
            BufferedImage on = HxCosmeticNameCases.shot(c, "lyric-on");
            c.ctx().waitTicks(5);
            long thirdPerson = counter(c, "ownLyricSubmitted") - b;
            String tag = ownState(c, "killer560smod$lyricTag");
            boolean named = ownNameShown(c);
            c.note("F5: " + thirdPerson + " lyric submits, state carries '" + tag + "', own name shown " + named);
            c.check(thirdPerson > 0, "your lyric was not submitted in third person");
            c.check("♫ Hello from the lyric".equals(tag), "your render state carries '" + tag + "'");
            c.check(named, "your name (which the lyric hangs over) is not shown in F5 with My Name in F5 off");

            c.onClient(mc -> {
                Mod.set(TAGC, "setLyricsOverHead", false);
                return null;
            });
            c.ctx().waitTicks(3);
            long d = counter(c, "ownLyricSubmitted");
            BufferedImage off = HxCosmeticNameCases.shot(c, "lyric-off");
            c.ctx().waitTicks(5);
            long offSubmits = counter(c, "ownLyricSubmitted") - d;
            c.check(offSubmits == 0, "your lyric was still drawn with My Lyrics In F5 off");
            c.check(!ownNameShown(c), "your name is still forced on in F5 with nothing over it");
            int rows = changedRows(on, off);
            c.note("screenshot: " + rows + " rows differ in the upper middle between on and off");
            c.check(rows >= 4, "the screenshot shows no text over the player with the lyric on (" + rows + " rows)");
        } finally {
            c.onClient(mc -> {
                Mod.setField(SLF, "testSnapshot", null);
                return null;
            });
            HxCosmeticNameCases.camera(c, CameraType.FIRST_PERSON);
        }
    }

    private static int changedRows(BufferedImage a, BufferedImage b) {
        int w = a.getWidth();
        int rows = 0;
        for (int y = 0; y < a.getHeight() / 2; y++) {
            int n = 0;
            for (int x = w / 2 - w / 8; x < w / 2 + w / 8; x++) {
                int p = a.getRGB(x, y), q = b.getRGB(x, y);
                int d = Math.abs(((p >> 16) & 255) - ((q >> 16) & 255)) + Math.abs(((p >> 8) & 255) - ((q >> 8) & 255))
                        + Math.abs((p & 255) - (q & 255));
                if (d > 60) {
                    n++;
                }
            }
            if (n >= 3) {
                rows++;
            }
        }
        return rows;
    }

    // ---- 2844: Smooth Lyric Scroll ----------------------------------------------------------------------------

    private static void scroll(Session c) throws Exception {
        c.check(has(c), "this jar has no Smooth Lyric Scroll (mod before spotify-controls-1026)");
        try (Settings s = new Settings(c).with(HC, "HudEnabled", true).with(HC, "ShowLyrics", true)
                .with(HC, "SmoothScroll", true)) {
            HxCosmeticNameCases.camera(c, CameraType.FIRST_PERSON);
            List<Float> seen = scrollRun(c);
            c.note("offsets after the line change, one per tick: " + seen);
            int between = 0;
            for (float f : seen) {
                if (f > 0.05f && f < 10.95f) {
                    between++;
                }
            }
            c.check(between >= 2, "the offset did not pass through in-between values: " + seen);
            boolean down = true;
            for (int i = 1; i < seen.size(); i++) {
                if (seen.get(i) > seen.get(i - 1) + 0.001f) {
                    down = false;
                }
            }
            c.check(down, "the offset did not only go down: " + seen);
            c.check(seen.get(seen.size() - 1) == 0f, "the rows did not settle at 0: " + seen);

            c.onClient(mc -> {
                Mod.set(HC, "setSmoothScroll", false);
                return null;
            });
            List<Float> off = scrollRun(c);
            c.note("with Smooth Lyric Scroll off: " + off);
            for (float f : off) {
                c.check(f == 0f, "the rows moved with Smooth Lyric Scroll off: " + off);
            }
        } finally {
            c.onClient(mc -> {
                Mod.setField(SLF, "testSnapshot", null);
                return null;
            });
        }
    }

    /** Puts up a snapshot whose line changes 600 ms from now, waits for the change, then samples the offset each tick. */
    private static List<Float> scrollRun(Session c) {
        long now = System.currentTimeMillis();
        List<Object> lines = c.onClient(mc -> List.of(line(0.0, "the first line of the song"),
                line(4.0, "and the second line of it"), line(600.0, "much later")));
        Object snap = c.onClient(mc -> snapshot(0, now - 3400, lines));
        long before = c.onClient(mc -> {
            Mod.setField(SLF, "testSnapshot", snap);
            return (Long) Mod.field(HUD, "scrollAnimations");
        });
        // wait until 4.0 s of song time (600 ms), plus a little
        List<Float> seen = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 3000;
        boolean started = false;
        while (System.currentTimeMillis() < deadline) {
            c.ctx().waitTicks(1);
            long n = c.onClient(mc -> (Long) Mod.field(HUD, "scrollAnimations"));
            if (System.currentTimeMillis() - now > 700 || n > before) {
                started = true;
                break;
            }
        }
        if (!started) {
            return seen;
        }
        for (int i = 0; i < 8; i++) {
            seen.add(c.onClient(mc -> (Float) Mod.field(HUD, "lastScrollOffset")));
            c.ctx().waitTicks(1);
        }
        return seen;
    }

    // ---- 2846: the F5 line lights the HUD's word ---------------------------------------------------------------

    private static int[] words(Session c) {
        return c.onClient(mc -> new int[]{(Integer) Mod.field(HUD, "lastSungWord"), (Integer) Mod.field(STD, "lastF5Word")});
    }

    private static void freeze(Session c, long at) {
        c.onClient(mc -> {
            Mod.setField(SLF, "testNowMs", at);
            return null;
        });
        c.ctx().waitTicks(3);
    }

    private static void f5Word(Session c) throws Exception {
        c.check(c.onClient(mc -> Mod.has(STD) && Mod.has(TAGC)) && c.onClient(mc -> {
            try {
                Mod.cls(TAGC).getMethod("isHighlightWordInF5");
                return true;
            } catch (NoSuchMethodException e) {
                return false;
            }
        }), "this jar has no Highlight Word In F5 (mod before spotify-f5word-1026)");
        long base = System.currentTimeMillis();
        String text = "one two three four five six seven";
        List<Object> lines = c.onClient(mc -> List.of(line(0.0, "the first line"), line(4.0, text), line(10.0, "next line")));
        Object snap = c.onClient(mc -> snapshot(0, base, lines));
        try (Settings s = new Settings(c).with(TAGC, "LyricsOverHead", true).with(TAGC, "HighlightWordInF5", true)
                .with(HC, "HudEnabled", true).with(HC, "ShowLyrics", true).with(HC, "WordHighlight", true)) {
            c.onClient(mc -> {
                Mod.setField(SLF, "testSnapshot", snap);
                return null;
            });
            HxCosmeticNameCases.look(c, 0f, 0f);
            HxCosmeticNameCases.camera(c, CameraType.THIRD_PERSON_BACK);
            java.util.Set<Integer> distinct = new java.util.TreeSet<>();
            int accent = c.onClient(mc -> (Integer) Mod.call(Mod.staticCall("gui.OverlayThemes", "menu"), "accentHover")) & 0xFFFFFF;
            for (double t : new double[]{4.05, 4.5, 5.0, 5.6, 6.3, 7.4}) {
                freeze(c, base + Math.round(t * 1000));
                int[] w = words(c);
                String styled = c.onClient(mc -> {
                    EntityRenderState state = mc.getEntityRenderDispatcher().extractEntity(mc.player, 1f);
                    Object tag = Mod.call(state, "killer560smod$lyricTag");
                    if (tag == null) {
                        return "null";
                    }
                    StringBuilder b = new StringBuilder();
                    for (Component part : ((Component) tag).getSiblings()) {
                        net.minecraft.network.chat.TextColor col = part.getStyle().getColor();
                        if (part.getStyle().isUnderlined()) {
                            b.append(part.getString().trim()).append('#').append(col == null ? -1 : col.getValue());
                        }
                    }
                    return b.toString();
                });
                c.note("t=" + t + " s: HUD lit word " + w[0] + ", F5 lit word " + w[1] + ", underlined in F5: " + styled);
                c.check(w[0] >= 0, "the HUD lit no word at " + t + " s");
                c.check(w[0] == w[1], "at " + t + " s the HUD lit word " + w[0] + " but the F5 line word " + w[1]);
                String want = text.split(" ")[w[0]] + "#" + accent;
                c.check(want.equals(styled), "at " + t + " s the F5 line's lit word is '" + styled + "', not '" + want + "'");
                distinct.add(w[1]);
            }
            c.check(distinct.size() >= 3, "the lit word hardly moved through the line: " + distinct);

            // Held time: the line is built once, not every frame.
            long builds0 = c.onClient(mc -> (Long) Mod.field(STD, "lyricBuilds"));
            c.ctx().waitTicks(20);
            long builds = c.onClient(mc -> (Long) Mod.field(STD, "lyricBuilds")) - builds0;
            c.note("rebuilds over 20 ticks with the time held: " + builds);
            c.check(builds == 0, "the F5 line was rebuilt " + builds + " times with nothing changing");

            // Off: no word lit in F5 (the HUD keeps its own).
            c.onClient(mc -> {
                Mod.set(TAGC, "setHighlightWordInF5", false);
                return null;
            });
            freeze(c, base + 5000);
            int[] off = words(c);
            c.note("Highlight Word In F5 off: HUD " + off[0] + ", F5 " + off[1]);
            c.check(off[1] == -1 && off[0] >= 0, "with Highlight Word In F5 off: HUD " + off[0] + ", F5 " + off[1]);

            // Paused: held, nothing lit in either.
            c.onClient(mc -> {
                Mod.set(TAGC, "setHighlightWordInF5", true);
                Object paused = newRecord("spotify.LyricsEngine$Snapshot", Mod.enumValue("spotify.NowPlaying$Kind", "PAUSED"),
                        "Artist", "Song", 5000L, 0L, 200.0, lines, true, false, 0.0, true);
                Mod.setField(SLF, "testSnapshot", paused);
                return null;
            });
            freeze(c, base + 5100);
            int[] paused = words(c);
            c.note("paused: HUD " + paused[0] + ", F5 " + paused[1]);
            c.check(paused[0] == -1 && paused[1] == -1, "a word is lit while paused: HUD " + paused[0] + ", F5 " + paused[1]);
        } finally {
            c.onClient(mc -> {
                Mod.setField(SLF, "testSnapshot", null);
                Mod.setField(SLF, "testNowMs", 0L);
                return null;
            });
            HxCosmeticNameCases.camera(c, CameraType.FIRST_PERSON);
        }
    }

    // ---- 2845: Share My Song is supporters only -------------------------------------------------------------

    private static void share(Session c) throws Exception {
        c.check(has(c), "this jar has no supporter-only Share My Song (mod before spotify-controls-1026)");
        List<String> handed = new CopyOnWriteArrayList<>();
        BiPredicate<String, JsonElement> sink = (key, value) -> handed.add(key + " " + value);
        UUID self = c.onClient(mc -> mc.player.getUUID());
        Object savedMap = c.onClient(mc -> Mod.field(SF, "byUuid"));
        try (Settings s = new Settings(c).with(TAGC, "ShareSong", true).with(MUC, "Share", true)) {
            c.onClient(mc -> {
                Mod.setField(CTL, "testSink", (Consumer<String>) x -> { });   // the tab never starts the real reader
                Mod.setField(SF, "byUuid", java.util.Map.of());
                Mod.setField(SH, "testNowPlaying", Mod.staticCall("spotify.NowPlaying", "track", "Artist", "Title"));
                Mod.setField(SH, "testSender", sink);
                return null;
            });
            c.ctx().waitTicks(30);
            Object own = c.onClient(mc -> Mod.staticCall(SH, "ownTag"));
            String block = c.onClient(mc -> (String) Mod.field(SH, "lastBlock"));
            c.note("non-supporter with Share My Song forced on: handed " + handed + ", blocked by '" + block
                    + "', own tag " + own);
            c.check(handed.isEmpty(), "a non-supporter's song was handed to the relay: " + handed);
            c.check(own == null, "a non-supporter has an own song line");
            List<AbstractWidget> w = buildTab(c);
            AbstractWidget row = find(w, "Share My Song");
            String h = hover(c, row);
            c.check(h != null && h.startsWith("Supporters only"), "the Share My Song hover does not say Supporters only: " + h);
            c.onClient(mc -> {
                Mod.set(TAGC, "setShareSong", false);
                return null;
            });
            w = buildTab(c);
            AbstractWidget off = find(w, "Share My Song");
            c.check(!off.active, "a non-supporter's Share My Song row is not grayed out");
            c.onClient(mc -> Mod.call(off, "press"));
            boolean turnedOn = c.onClient(mc -> (Boolean) Mod.get(TAGC, "isShareSong"));
            c.check(!turnedOn, "a non-supporter turned Share My Song on");

            // In the supporter list: the row is live and the song goes out.
            String list = "[{\"uuid\":\"" + self + "\",\"name\":\"\",\"scale\":1}]";
            c.onClient(mc -> {
                Mod.staticCall(SF, "apply", 10,
                        Mod.staticCall("supporters.SupportersFetcher", "parseSupporters", JsonParser.parseString(list)), false);
                Mod.set(TAGC, "setShareSong", true);
                return null;
            });
            c.waitUntil("a play packet handed over", mc -> !handed.isEmpty(), 60);
            c.note("supporter: " + handed);
            c.check(handed.get(0).startsWith("np.v1 {\"state\":\"play\""), "the supporter's packet is not a play packet: " + handed);
            w = buildTab(c);
            c.check(find(w, "Share My Song").active, "a supporter's Share My Song row is grayed out");
        } finally {
            c.onClient(mc -> {
                Mod.setField(SF, "byUuid", savedMap);
                Mod.setField(CTL, "testSink", null);
                Mod.setField(SH, "testSender", null);
                Mod.setField(SH, "testNowPlaying", null);
                return null;
            });
        }
    }
}
