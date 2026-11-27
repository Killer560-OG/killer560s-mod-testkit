package dev.testkit.harness;

import java.util.ArrayList;
import java.util.List;

/**
 * Loads the client JVM's CPUs for a case that must show what the mod does on a busy PC (acflakes-1126: two sim cases
 * failed only inside a full run, the planner out of time and the integrated server seconds behind). Env
 * {@code TESTKIT_CPU_BURN=N} starts N busy daemon threads; {@link #stop} ends them. Off unless the env asks.
 */
public final class CpuBurn {
    private static final List<Thread> THREADS = new ArrayList<>();
    private static volatile boolean on;
    public static volatile long sink;

    private CpuBurn() {
    }

    /** Starts the env's thread count (none when unset); returns how many run. */
    public static synchronized int startFromEnv() {
        String v = System.getenv("TESTKIT_CPU_BURN");
        int n = v == null || v.isBlank() ? 0 : Integer.parseInt(v.trim());
        if (n <= 0 || on) {
            return on ? THREADS.size() : 0;
        }
        on = true;
        for (int i = 0; i < n; i++) {
            Thread t = new Thread(() -> {
                long x = 1;
                while (on) {
                    x = x * 6364136223846793005L + 1442695040888963407L;
                    if ((x & 0xFFFFF) == 0) {
                        sink = x;
                    }
                }
            }, "testkit-cpuburn-" + i);
            t.setDaemon(true);
            t.start();
            THREADS.add(t);
        }
        System.out.println("[CpuBurn] " + n + " busy thread(s) started");
        return n;
    }

    public static synchronized void stop() {
        if (on) {
            on = false;
            System.out.println("[CpuBurn] stopped " + THREADS.size() + " thread(s)");
            THREADS.clear();
        }
    }
}
