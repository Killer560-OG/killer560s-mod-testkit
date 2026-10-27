package dev.testkit.gametest.ui;

import dev.testkit.gametest.mod.Mod;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Polls this JVM's descendant processes every 25 ms for the length of the UI run. The mod's clipboard writes
 * (copychat/CopyChatFeature.java:146, screenshotcopy/ScreenshotCopyFeature.java:65) and OS opens shell out, so a
 * process that appears here is a side effect on the real machine. A process that lives less than one poll can be
 * missed; this is a tripwire, not a proof (GLFW clipboard writes make no process at all - see docs/requests/ui.md).
 *
 * <p>Two kinds of child are the mod doing its job, not a side effect, and go to {@link #expected()} instead:
 * <ul>
 *   <li>a process started inside an {@link #expect} scope - a case that makes the mod run ffmpeg on a file the case
 *       wrote (1064's refused import: ClickSounds hands anything it cannot read itself to ffmpeg). 370 used to fail
 *       whenever a poll happened to land in the second that ffmpeg ran, and pass when it did not;</li>
 *   <li>the Spotify media-session reader, a read-only powershell.exe the mod keeps while Send Lyrics To Chat or the
 *       Spotify HUD is on (310/311 flip that switch, 1018-ui-spotify-lyrics turns it on). It is recognized by the pid
 *       the mod itself holds ({@code SpotifyLyricsFeature.MEDIA.process}), so a screenshot copy's powershell.exe still
 *       counts.</li>
 * </ul>
 * Windows gives no command line for these children ({@code ProcessHandle.Info} has only the exe), so matching on
 * arguments is not possible. A child of an expected process (its conhost.exe) is expected too.
 */
final class ProcWatch implements AutoCloseable {

    private static volatile ProcWatch current;

    private final Set<Long> baseline = new HashSet<>();
    private final List<String> seen = new ArrayList<>();
    private final List<String> expectedSeen = new ArrayList<>();
    private final Set<Long> seenPids = new HashSet<>();
    private final Set<Long> expectedPids = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Thread thread;
    private volatile boolean running = true;
    private volatile int polls;
    private volatile String scope;

    ProcWatch() {
        ProcessHandle.current().descendants().forEach(p -> baseline.add(p.pid()));
        thread = new Thread(this::loop, "testkit-ui-procwatch");
        thread.setDaemon(true);
        thread.start();
        current = this;
    }

    private void loop() {
        while (running) {
            try {
                pollOnce();
                polls++;
                Thread.sleep(25);
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException ignored) {
                // a process that exited mid-listing
            }
        }
    }

    /**
     * A new child is judged at once when it is expected; otherwise it waits up to {@link #JUDGE_NANOS} (or until it
     * exits) before it counts, because the mod stores the Spotify reader's Process only after {@code start()} returns
     * and a poll can land in between.
     */
    private static final long JUDGE_NANOS = 2_000_000_000L;
    private final java.util.Map<Long, Pending> pending = new java.util.HashMap<>();

    private record Pending(ProcessHandle handle, String cmd, long since, long parent) {
    }

    private synchronized void pollOnce() {
        long now = System.nanoTime();
        ProcessHandle.current().descendants().forEach(p -> {
            if (!baseline.contains(p.pid()) && seenPids.add(p.pid())) {
                pending.put(p.pid(), new Pending(p, p.info().commandLine().orElse(p.info().command().orElse("?")), now,
                        p.parent().map(ProcessHandle::pid).orElse(-1L)));
            }
        });
        for (var it = pending.values().iterator(); it.hasNext(); ) {
            Pending pd = it.next();
            String why = expectedWhy(pd.handle(), pd.parent());
            boolean expected = why != null;
            if (!expected && pd.handle().isAlive() && now - pd.since() < JUDGE_NANOS) {
                continue;
            }
            // A child whose parent is still waiting to be judged (csc.exe's cvtres.exe, seen in the same poll) waits for
            // it: judged first, it found its parent not yet expected and counted (370, final-1027).
            if (!expected && pending.containsKey(pd.parent()) && pd.parent() != pd.handle().pid()
                    && now - pd.since() < JUDGE_NANOS) {
                continue;
            }
            it.remove();
            synchronized (seen) {
                (expected ? expectedSeen : seen).add(pd.handle().pid() + " " + pd.cmd() + (expected ? " (" + why + ")" : ""));
            }
            if (expected) {
                expectedPids.add(pd.handle().pid());
            }
            System.out.println("[ui-procwatch] child process " + pd.handle().pid() + ": " + pd.cmd()
                    + (expected ? " - expected: " + why : ""));
        }
    }

    /** Judges every child still waiting (the end of the run): what is not expected by now counts. */
    private synchronized void flush() {
        // Parents first: an expected parent still pending makes its children expected too.
        for (int pass = 0; pass < 3; pass++) {
            for (Pending pd : pending.values()) {
                if (expectedWhy(pd.handle(), pd.parent()) != null) {
                    expectedPids.add(pd.handle().pid());
                }
            }
        }
        for (Pending pd : pending.values()) {
            String why = expectedWhy(pd.handle(), pd.parent());
            synchronized (seen) {
                (why != null ? expectedSeen : seen).add(pd.handle().pid() + " " + pd.cmd() + (why != null ? " (" + why + ")" : ""));
            }
        }
        pending.clear();
    }

    /**
     * {@code seenParent} is the parent read when the child was first seen: a short child (the csc.exe the Spotify
     * reader's {@code Add-Type} starts to compile its volume code, mod spotify-controls-1026) is often judged after
     * it exited, when Windows no longer gives its parent (370 on final-1027).
     */
    private String expectedWhy(ProcessHandle p, long seenParent) {
        String s = scope;
        if (s != null) {
            return s;
        }
        Long parent = p.parent().map(ProcessHandle::pid).orElse(seenParent >= 0 ? seenParent : null);
        if (parent != null && expectedPids.contains(parent)) {
            return "child of expected " + parent;
        }
        if (parent != null && parent == spotifyReaderPid()) {
            return "child of the Spotify media-session reader " + parent;
        }
        if (p.pid() == spotifyReaderPid()) {
            return "the Spotify media-session reader (SpotifyLyricsFeature.MEDIA)";
        }
        return null;
    }

    private static long spotifyReaderPid() {
        try {
            Object media = Mod.field("spotify.SpotifyLyricsFeature", "MEDIA");
            Object proc = Mod.field(media, "process");
            return proc instanceof Process pr ? pr.pid() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Every child started from now until the returned scope closes is the case's own doing. Closing waits (at most
     * 5 s, on the real condition) until every child that started inside the scope has exited, so a process that
     * outlives the call by a moment - ffmpeg's conhost.exe - is still counted as expected. Without a watcher running
     * (the case run alone) it does nothing.
     */
    static AutoCloseable expect(String why) {
        ProcWatch w = current;
        if (w == null || !w.running) {
            return () -> { };
        }
        Set<Long> before = new HashSet<>();
        ProcessHandle.current().descendants().forEach(p -> before.add(p.pid()));
        w.scope = why;
        return () -> {
            long deadline = System.nanoTime() + 5_000_000_000L;
            try {
                while (true) {
                    w.pollOnce();   // marks anything not yet seen as expected while the scope is still open
                    Set<Long> live = new HashSet<>();
                    ProcessHandle.current().descendants().forEach(p -> {
                        if (!before.contains(p.pid())) {
                            live.add(p.pid());
                        }
                    });
                    live.removeIf(pid -> !w.expectedPids.contains(pid));
                    if (live.isEmpty() || System.nanoTime() > deadline) {
                        return;
                    }
                    Thread.sleep(10);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                w.scope = null;
            }
        };
    }

    List<String> seen() {
        flush();
        synchronized (seen) {
            return new ArrayList<>(seen);
        }
    }

    /** Children the mod started doing its job (see the class comment), kept out of {@link #seen()}. */
    List<String> expected() {
        synchronized (seen) {
            return new ArrayList<>(expectedSeen);
        }
    }

    int polls() {
        return polls;
    }

    @Override
    public void close() {
        running = false;
        thread.interrupt();
        if (current == this) {
            current = null;
        }
    }
}
