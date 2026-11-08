package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * spotify1108 (killer560's to-do Doc: "It isnt picking up lyrics for some songs and the timing is still off").
 * <ul>
 *   <li>2872 (offline, fake Kugou and lrclib): Kugou's lyrics keyword search lists only its better-known songs (since about
 *       2026-11-05 every song he played came back "no version within 2.0 s"), so the mod now finds the song by Kugou's song
 *       search and its lyrics by the song's hash; Kugou's "、" artist separator; a YouTube video's channel and title cleaned
 *       for the lookup; lrclib's HTTP 429 asked again; a title-only lrclib search when the artist search misses; and a clock
 *       drift two readings agree on (his log: -478 ms, eased over 4 s) set at once.</li>
 *   <li>2873 (real network, TESTKIT_REAL_NET=1): his 13 songs from the 26.3 log (11-05..11-08) through the mod's own lookups;
 *       reports which have lines and which have Kugou word times.</li>
 * </ul>
 */
final class HxSpotifyLyrics1108Cases {

    private static final String E = "spotify.LyricsEngine";
    private static final String K = "spotify.KugouLyrics";
    private static final String HC = "spotify.SpotifyHudConfig";

    private HxSpotifyLyrics1108Cases() {
    }

    static void register(Session s) {
        s.test("2872-hx-spotify-lyrics-misses-1108", HxSpotifyLyrics1108Cases::misses);
        if ("1".equals(System.getenv("TESTKIT_REAL_NET"))) {
            s.test("2873-hx-spotify-real-lyrics-1108", HxSpotifyLyrics1108Cases::realSongs);
        } else {
            s.shelved("2873-hx-spotify-real-lyrics-1108", "asks the real lrclib and Kugou: set TESTKIT_REAL_NET=1",
                    HxSpotifyLyrics1108Cases::realSongs);
        }
    }

    private static final String SONG_SEARCH = "{\"status\":1,\"data\":{\"info\":["
            + "{\"hash\":\"H0\",\"songname\":\"Testsong (Sped Up)\",\"singername\":\"Testartist\",\"duration\":150},"
            + "{\"hash\":\"H1\",\"songname\":\"Testsong\",\"singername\":\"Testartist、Guest\",\"duration\":201}]}}";
    private static final String HASH_LYRICS = "{\"status\":200,\"candidates\":["
            + "{\"id\":\"7\",\"accesskey\":\"A7\",\"singer\":\"Testartist、Guest\",\"song\":\"Testsong\",\"duration\":201000}]}";
    private static final String NO_CANDIDATES = "{\"status\":200,\"candidates\":[]}";
    private static final String KRC = String.join("\n",
            "[0,1500]<0,500,0>Testsong <500,500,0>- <1000,500,0>Testartist",
            "[5000,3000]<0,300,0>Hold <300,250,0>on <550,200,0>to<750,250,0>night <1000,400,0>we <1400,500,0>dance",
            "[9000,2500]<0,350,0>Under <350,300,0>the <650,450,0>moon <1100,600,0>again",
            "[12500,3000]<0,200,0>Every <200,300,0>single <500,250,0>time <750,700,0>forever");
    private static final String LISTING = "[{\"id\":1,\"trackName\":\"Testsong\",\"artistName\":\"Testartist\",\"albumName\":\"A\","
            + "\"duration\":201.0,\"syncedLyrics\":\"[00:05.00] Hold on tonight we dance\\n[00:09.00] Under the moon again\"}]";

    private static void misses(Session c) throws Exception {
        c.check(c.onClient(mc -> Mod.has(E) && Mod.has(K)), "this jar has no Kugou word-timed lyrics");
        // (a) Kugou: keyword search empty, song search + hash finds it
        List<String> asked = new CopyOnWriteArrayList<>();
        String body = "{\"status\":200,\"content\":\"" + encodeKrc(KRC) + "\"}";
        Function<String, String> kugou = url -> {
            asked.add(url);
            if (url.contains("/api/v3/search/song")) return SONG_SEARCH;
            if (url.contains("/download")) return url.contains("id=7") ? body : null;
            if (url.contains("hash=H1")) return HASH_LYRICS;
            return NO_CANDIDATES;
        };
        try (Settings st = new Settings(c).with(HC, "WordTimedLyrics", true)) {
            c.onClient(mc -> {
                Mod.setField(E, "testLrclib", (Function<String, String>) u -> "[]");
                return null;
            });
            Object eng = engine(c, kugou, "Testartist", "Testsong", 201.0, true);
            boolean got = waitFor(c, () -> timedLines(c, eng) > 0, 100);
            String why = c.onClient(mc -> (String) Mod.staticCall(K, "lastResult"));
            c.note("(a) Kugou asked " + asked.size() + ": " + shortUrls(asked) + "; result '" + why + "'; timed lines "
                    + timedLines(c, eng));
            c.check(got, "Kugou's song search + hash route found no word times (keyword search empty): " + why);
            c.check(asked.stream().anyMatch(u -> u.contains("/api/v3/search/song")), "Kugou's song search was never asked");
            c.check(asked.stream().noneMatch(u -> u.contains("hash=H0")), "the Sped Up song (other title, 150 s) was asked");

            // (b) lookup names of a YouTube video
            String names = c.onClient(mc -> {
                StringBuilder b = new StringBuilder();
                String[][] in = {{"ImagineDragonsVEVO", "Imagine Dragons - Believer (Official Music Video)"},
                        {"Imagine Dragons - Topic", "Believer"},
                        {"SomeUploader", "Rosa Walton - I Really Want to Stay at Your House [HD]"},
                        {"The Beatles", "Let It Be - Remastered 2009"},
                        {"Franz Ferdinand", "This fffire - New Version"}};
                for (String[] p : in) {
                    String[] o = (String[]) Mod.staticCall(E, "lookupNames", p[0], p[1]);
                    b.append(o[0]).append(" / ").append(o[1]).append(" | ");
                }
                return b.toString();
            });
            c.note("(b) lookup names: " + names);
            c.check(names.equals("Imagine Dragons / Believer | Imagine Dragons / Believer | Rosa Walton / I Really Want to Stay"
                    + " at Your House | The Beatles / Let It Be - Remastered 2009 | Franz Ferdinand / This fffire - New Version | "),
                    "video names not cleaned, or a music app's title split: " + names);

            // (c) lrclib answers 429 first: asked again
            AtomicInteger calls = new AtomicInteger();
            c.onClient(mc -> {
                Mod.setField(E, "testLrclib", (Function<String, String>) u -> calls.incrementAndGet() == 1 ? null : LISTING);
                return null;
            });
            Object busy = engine(c, u -> null, "Testartist", "Testsong", 201.0, false);
            boolean lines = waitFor(c, () -> lineCount(c, busy) > 0, 160);
            c.note("(c) lrclib 429 then answer: " + calls.get() + " call(s), lines " + lineCount(c, busy));
            c.check(lines && calls.get() >= 2, "after an HTTP 429 lrclib was not asked again: calls " + calls.get());

            // (d) the artist search misses, the title-only search has it
            List<String> lrc = new CopyOnWriteArrayList<>();
            c.onClient(mc -> {
                Mod.setField(E, "testLrclib", (Function<String, String>) u -> {
                    lrc.add(u.replaceAll("^[^?]*", ""));
                    return u.contains("artist_name=") ? "[]" : u.contains("track_name=") ? LISTING : "[]";
                });
                return null;
            });
            Object title = engine(c, u -> null, "Testartist", "Testsong", 201.0, false);
            boolean tl = waitFor(c, () -> lineCount(c, title) > 0, 100);
            c.note("(d) lrclib asked " + lrc + "; lines " + lineCount(c, title));
            c.check(tl, "a title-only lrclib search was not tried after the artist search missed: " + lrc);
        } finally {
            c.onClient(mc -> {
                Mod.setField(K, "testResponder", null);
                Mod.setField(E, "testLrclib", null);
                return null;
            });
        }

        // (f) Kugou's words moved onto lrclib's agreed line clock (Kugou ran 300 ms late here)
        String moved = c.onClient(mc -> {
            StringBuilder on = new StringBuilder();
            StringBuilder late = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                int at = 5000 + i * 3000;
                on.append('[').append(at).append(",2000]<0,400,0>Word").append(i).append(" <400,400,0>two\n");
                late.append('[').append(at + 300).append(",2000]<0,400,0>Word").append(i).append(" <400,400,0>two\n");
            }
            List<?> words = (List<?>) Mod.staticCall(K, "parse", late.toString());
            List<?> lines = (List<?>) Mod.staticCall(K, "parse", on.toString());
            List<?> out = (List<?>) Mod.staticCall(E, "onto", words, lines);
            Object first = out.get(0);
            double[] st = (double[]) Mod.call(Mod.call(first, "parsed"), "wordStarts");
            return String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f", (Double) Mod.call(first, "timeSeconds"), st[0], st[1]);
        });
        c.note("(f) Kugou line 5.3 s onto lrclib's 5.0 s: line,word1,word2 = " + moved);
        c.check(moved.equals("5.00,5.00,5.40"), "Kugou's words were not moved onto lrclib's lines: " + moved);

        // (e) the clock: two readings agree it is 478 ms ahead -> set at once, not eased 30 ms a poll
        String drift = c.onClient(mc -> {
            try {
                Object eng = Mod.cls(E).getDeclaredConstructor().newInstance();
                Mod.call(eng, "setFetchLyrics", false);
                Object track = Mod.staticCall("spotify.NowPlaying", "track", "Artist", "Song");
                long t0 = 1_000_000_000_000L;
                long[][] readings = {{0, 0}, {250, 250}, {1000, 522}, {1250, 772}, {1500, 1022}};
                StringBuilder b = new StringBuilder();
                long err = 0;
                long before = 0;
                for (long[] r : readings) {
                    long w = t0 + r[0];
                    Object timeline = newRecord("spotify.SpotifyMediaSession$Timeline", "Artist", "Song", 4, r[1], 200_000L, w, w, 0);
                    Object clock = Mod.field(eng, "clock");
                    if (r[0] == 1000) before = (Long) Mod.call(clock, "elapsedMs", w) - r[1];
                    Mod.call(eng, "pollAt", track, timeline, w, false);
                    err = (Long) Mod.call(clock, "elapsedMs", w) - r[1];
                    b.append(r[0]).append("ms:").append(err).append(' ');
                }
                return before + "|" + err + "|" + b;
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        String[] d = drift.split("\\|");
        c.note("(e) clock minus Spotify before the drift " + d[0] + " ms, after two agreeing readings " + d[1] + " ms (" + d[2] + ")");
        c.check(Math.abs(Long.parseLong(d[0])) > 400, "the drift never existed: " + d[0]);
        c.check(Math.abs(Long.parseLong(d[1])) <= 60, "a 478 ms drift two readings agree on was not set at once: still " + d[1] + " ms");
    }

    // ---- 2873: his songs against the real lrclib and Kugou -------------------------------------------------------

    /** artist, title, Spotify length (his log, or the song's listed length). */
    private static final Object[][] HIS_SONGS = {
            {"Rosa Walton", "I Really Want to Stay at Your House", 246.7},
            {"Franz Ferdinand", "This fffire - New Version", 218.1},
            {"King Von", "Crazy Story (feat. Lil Durk) - Remix", 195.0},
            {"Diego Cichy Don", "Nieważne feat. Martyna Baranowska", 157.9},
            {"The Armed", "Night City Aliens", 205.0},
            {"RAT BOY", "Who's Ready for Tomorrow", 116.0},
            {"P.T. Adamczyk", "Outsider No More", 230.0},
            {"Bushwick Princess", "Does Anyone Notice", 240.0},
            {"Johnnivan", "My Spirit Is Reggie", 192.0},
            {"NUDNIK", "A Capricorn Dog", 224.0},
            {"Purr of the Jaguar", "Bad Habits", 159.0},
            {"Sossee", "American Psycho", 180.0},
            {"Ken Carson", "Succubus", 149.9}};

    private static void realSongs(Session c) throws Exception {
        int withLines = 0;
        int withWords = 0;
        StringBuilder rows = new StringBuilder();
        // the testkit client runs with killer560.net.offline; this case asks the real servers (ModNet reads it per call)
        String offline = System.getProperty("killer560.net.offline");
        System.setProperty("killer560.net.offline", "false");
        try (Settings st = new Settings(c).with(HC, "WordTimedLyrics", true)) {
            for (Object[] s : HIS_SONGS) {
                Object eng = engine(c, null, (String) s[0], (String) s[1], (Double) s[2], true);
                waitFor(c, () -> timedLines(c, eng) > 0, 300);   // Kugou answers after lrclib; 15 s at most
                int n = lineCount(c, eng);
                int w = timedLines(c, eng);
                if (n > 0) withLines++;
                if (w > 0) withWords++;
                String why = c.onClient(mc -> (String) Mod.staticCall(K, "lastResult"));
                rows.append(String.format("%n  %s - %s: %d lines, %d word-timed (%s)", s[0], s[1], n, w, why));
            }
        } finally {
            if (offline == null) System.clearProperty("killer560.net.offline");
            else System.setProperty("killer560.net.offline", offline);
        }
        c.note("his " + HIS_SONGS.length + " songs: " + withLines + " with lines, " + withWords + " with Kugou word times" + rows);
        // base 4f46f8ca5: Kugou word times for 1 (Succubus, a keyword-search hit); the hash route finds 7.
        c.check(withWords >= 5, "Kugou word times for only " + withWords + " of his songs");
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private interface Cond {
        boolean ok();
    }

    private static boolean waitFor(Session c, Cond cond, int ticks) {
        for (int i = 0; i < ticks; i += 5) {
            if (cond.ok()) return true;
            c.ctx().waitTicks(5);
        }
        return cond.ok();
    }

    /** A fresh engine playing the track with Spotify's length known; the per-track caches are cleared so it asks. */
    private static Object engine(Session c, Function<String, String> kugou, String artist, String title, double seconds,
                                 boolean words) {
        return c.onClient(mc -> {
            try {
                Mod.setField(K, "testResponder", kugou);
                for (String cache : new String[] {"WORD_CACHE", "FETCH_CACHE"}) {
                    java.util.Map<?, ?> m = (java.util.Map<?, ?>) Mod.field(E, cache);
                    synchronized (m) {
                        m.clear();
                    }
                }
                Mod.set(HC, "setWordTimedLyrics", words);
                Object eng = Mod.cls(E).getDeclaredConstructor().newInstance();
                Mod.call(eng, "setFetchLyrics", true);
                Object track = Mod.staticCall("spotify.NowPlaying", "track", artist, title);
                long now = System.currentTimeMillis();
                Object tl = newRecord("spotify.SpotifyMediaSession$Timeline", artist, title, 4, 1000L,
                        Math.round(seconds * 1000), now, now, 0);
                Mod.call(eng, "pollAt", track, tl, now, false);
                // a second poll once the lookups had time, so the word-time lookup (needs the length) is asked
                return eng;
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
    }

    private static int lineCount(Session c, Object eng) {
        return c.onClient(mc -> ((List<?>) Mod.call(Mod.call(eng, "snapshot"), "lines")).size());
    }

    private static int timedLines(Session c, Object eng) {
        return c.onClient(mc -> {
            int n = 0;
            for (Object l : (List<?>) Mod.call(Mod.call(eng, "snapshot"), "lines")) {
                if ((Boolean) Mod.call(Mod.call(l, "parsed"), "timed")) n++;
            }
            return n;
        });
    }

    private static String shortUrls(List<String> urls) {
        List<String> out = new ArrayList<>();
        for (String u : urls) {
            out.add(u.replaceAll("^https?://[^/]+", "").replaceAll("(keyword|accesskey)=[^&]*", "$1=.."));
        }
        return out.toString();
    }

    private static Object newRecord(String cls, Object... args) {
        Constructor<?> k = Mod.cls(cls).getDeclaredConstructors()[0];
        k.setAccessible(true);
        try {
            return k.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static String encodeKrc(String text) {
        byte[] key = {64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, (byte) 206, (byte) 210, 110, 105};
        java.util.zip.Deflater d = new java.util.zip.Deflater();
        d.setInput(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        d.finish();
        byte[] buf = new byte[65536];
        int n = d.deflate(buf);
        d.end();
        byte[] out = new byte[4 + n];
        out[0] = 'k';
        out[1] = 'r';
        out[2] = 'c';
        out[3] = '1';
        for (int i = 0; i < n; i++) {
            out[4 + i] = (byte) (buf[i] ^ key[i % key.length]);
        }
        return java.util.Base64.getEncoder().encodeToString(out);
    }
}
