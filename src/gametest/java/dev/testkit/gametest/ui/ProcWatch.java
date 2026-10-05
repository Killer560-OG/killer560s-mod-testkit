package dev.testkit.gametest.ui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Polls this JVM's descendant processes every 25 ms for the length of the UI run. The mod's clipboard writes
 * (copychat/CopyChatFeature.java:146, screenshotcopy/ScreenshotCopyFeature.java:65) and OS opens shell out, so a
 * process that appears here is a side effect on the real machine. A process that lives less than one poll can be
 * missed; this is a tripwire, not a proof (GLFW clipboard writes make no process at all - see docs/requests/ui.md).
 */
final class ProcWatch implements AutoCloseable {

    private final Set<Long> baseline = new HashSet<>();
    private final List<String> seen = new ArrayList<>();
    private final Set<Long> seenPids = new HashSet<>();
    private final Thread thread;
    private volatile boolean running = true;
    private volatile int polls;

    ProcWatch() {
        ProcessHandle.current().descendants().forEach(p -> baseline.add(p.pid()));
        thread = new Thread(this::loop, "testkit-ui-procwatch");
        thread.setDaemon(true);
        thread.start();
    }

    private void loop() {
        while (running) {
            try {
                ProcessHandle.current().descendants().forEach(p -> {
                    if (!baseline.contains(p.pid()) && seenPids.add(p.pid())) {
                        String cmd = p.info().commandLine().orElse(p.info().command().orElse("?"));
                        synchronized (seen) {
                            seen.add(p.pid() + " " + cmd);
                        }
                        System.out.println("[ui-procwatch] child process " + p.pid() + ": " + cmd);
                    }
                });
                polls++;
                Thread.sleep(25);
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException ignored) {
                // a process that exited mid-listing
            }
        }
    }

    List<String> seen() {
        synchronized (seen) {
            return new ArrayList<>(seen);
        }
    }

    int polls() {
        return polls;
    }

    @Override
    public void close() {
        running = false;
        thread.interrupt();
    }
}
