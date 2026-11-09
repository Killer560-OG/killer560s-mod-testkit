package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * ytlyrics-1108 (killer560, 11-06: "youtube videos tend to not have lyrics show"). A video's title is cleaned for the
 * lrclib lookup (brackets, a bare "ft. X" / "Lyric Video" tail, "- Topic", VEVO, "Artist - Song" split), and a search
 * that lrclib answered with nothing is remembered, so a replay does not ask again.
 */
final class HxYtLyrics1108Cases {

    private static final String E = "spotify.LyricsEngine";

    private HxYtLyrics1108Cases() {
    }

    static void register(Session s) {
        s.test("2874-hx-yt-lyrics-names-1108", HxYtLyrics1108Cases::names);
    }

    private static void names(Session c) throws Exception {
        c.check(c.onClient(mc -> Mod.has(E)), "this jar has no LyricsEngine");
        String[][] in = {
                {"ImagineDragonsVEVO", "Imagine Dragons - Believer (Official Music Video)", "Imagine Dragons / Believer"},
                {"SomeChannel", "Rosa Walton - Stay at Your House ft. Guest", "Rosa Walton / Stay at Your House"},
                {"SomeChannel", "Artist - Song Name Lyric Video", "Artist / Song Name"},
                {"SomeChannel", "Artist - Song Name (Official Audio)", "Artist / Song Name"},
                {"Imagine Dragons - Topic", "Believer", "Imagine Dragons / Believer"},
                {"The Beatles", "Let It Be - Remastered 2009", "The Beatles / Let It Be - Remastered 2009"}};
        String bad = c.onClient(mc -> {
            StringBuilder b = new StringBuilder();
            for (String[] p : in) {
                String[] o = (String[]) Mod.staticCall(E, "lookupNames", p[0], p[1]);
                if (!(o[0] + " / " + o[1]).equals(p[2])) b.append('[').append(o[0]).append(" / ").append(o[1]).append("] ");
            }
            return b.toString();
        });
        c.note("(a) uncleaned: " + bad);
        c.check(bad.isEmpty(), "video names not cleaned as expected: " + bad);

        // (b) a song lrclib has nothing for is asked once, not again on a replay
        AtomicInteger calls = new AtomicInteger();
        Object[] eng = new Object[1];
        c.onClient(mc -> {
            Mod.setField(E, "testLrclib", (Function<String, String>) u -> {
                calls.incrementAndGet();
                return "[]";
            });
            for (String cache : new String[] {"WORD_CACHE", "FETCH_CACHE"}) {
                java.util.Map<?, ?> m = (java.util.Map<?, ?>) Mod.field(E, cache);
                synchronized (m) {
                    m.clear();
                }
            }
            return null;
        });
        try {
            for (int round = 1; round <= 2; round++) {
                int before = calls.get();
                c.onClient(mc -> {
                    try {
                        eng[0] = Mod.cls(E).getDeclaredConstructor().newInstance();
                        Mod.call(eng[0], "setFetchLyrics", true);
                        Mod.call(eng[0], "pollAt", Mod.staticCall("spotify.NowPlaying", "track", "Nobody", "No Lyrics Anywhere"),
                                null, System.currentTimeMillis(), false);
                    } catch (ReflectiveOperationException e) {
                        throw new AssertionError(e);
                    }
                    return null;
                });
                for (int i = 0; i < 100 && calls.get() == before; i += 5) c.ctx().waitTicks(5);
                c.ctx().waitTicks(40);
                c.note("(b) round " + round + ": lrclib asked " + (calls.get() - before) + " time(s)");
                if (round == 1) c.check(calls.get() > before, "the lookup never asked lrclib (nothing acted)");
                else c.check(calls.get() == before, "a miss was asked again on a replay: " + (calls.get() - before));
            }
        } finally {
            c.onClient(mc -> {
                Mod.setField(E, "testLrclib", null);
                return null;
            });
        }
    }
}
