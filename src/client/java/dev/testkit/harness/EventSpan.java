package dev.testkit.harness;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.resources.Identifier;

import java.lang.management.ManagementFactory;
import java.util.Locale;

/**
 * Times every listener of an event from OUTSIDE, without wrapping any of them (2026-10-15, fps-idle-1015).
 *
 * <p>The mod's {@code PerfProbe} wraps each listener in a reflective proxy, so a listener that returns at once still
 * reads 1-2 us there, and nobody can tell how much of that is the listener and how much the proxy. This registers one
 * listener in a phase ordered before every other phase of the event and one in a phase after the default one, and
 * times the gap: the true cost of everything registered in between (all mod listeners, Fabric API's and the testkit's
 * own default-phase ones, which are the same in an A/B pair). Run with {@code -PperfNoProbe} for the real number.
 *
 * <p>Covers START/END_CLIENT_TICK and the two level-render events the mod uses most. Render thread only.
 */
public final class EventSpan {

    private static final Identifier FIRST = Identifier.fromNamespaceAndPath("testkit", "span_first");
    private static final Identifier LAST = Identifier.fromNamespaceAndPath("testkit", "span_last");
    /** Phases the mod orders before the default one: the span must open before them too. */
    private static final Identifier[] MOD_EARLY = {
            Identifier.fromNamespaceAndPath("killer560smod", "client_tick_count"),
    };

    public enum Kind { START_TICK, END_TICK, AFTER_TERRAIN, AFTER_FEATURES }

    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final long[] T0 = new long[Kind.values().length];
    private static final long[] B0 = new long[Kind.values().length];
    private static final long[] NS = new long[Kind.values().length];
    private static final long[] BYTES = new long[Kind.values().length];
    private static final long[] CALLS = new long[Kind.values().length];
    private static boolean installed;
    private static volatile boolean recording;

    private EventSpan() {
    }

    /** Idempotent. Client thread. */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        order(ClientTickEvents.START_CLIENT_TICK);
        ClientTickEvents.START_CLIENT_TICK.register(FIRST, c -> open(Kind.START_TICK));
        ClientTickEvents.START_CLIENT_TICK.register(LAST, c -> close(Kind.START_TICK));
        order(ClientTickEvents.END_CLIENT_TICK);
        ClientTickEvents.END_CLIENT_TICK.register(FIRST, c -> open(Kind.END_TICK));
        ClientTickEvents.END_CLIENT_TICK.register(LAST, c -> close(Kind.END_TICK));
        order(LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN);
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(FIRST, c -> open(Kind.AFTER_TERRAIN));
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(LAST, c -> close(Kind.AFTER_TERRAIN));
        order(LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES);
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(FIRST, c -> open(Kind.AFTER_FEATURES));
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(LAST, c -> close(Kind.AFTER_FEATURES));
    }

    private static void order(Event<?> event) {
        for (Identifier early : MOD_EARLY) {
            event.addPhaseOrdering(FIRST, early);
        }
        event.addPhaseOrdering(FIRST, Event.DEFAULT_PHASE);
        event.addPhaseOrdering(Event.DEFAULT_PHASE, LAST);
    }

    private static void open(Kind k) {
        if (!recording) {
            return;
        }
        int i = k.ordinal();
        B0[i] = THREADS.getCurrentThreadAllocatedBytes();
        T0[i] = System.nanoTime();
    }

    private static void close(Kind k) {
        if (!recording) {
            return;
        }
        long t1 = System.nanoTime();
        long b1 = THREADS.getCurrentThreadAllocatedBytes();
        int i = k.ordinal();
        if (T0[i] == 0L) {
            return; // recording began between this event's two halves
        }
        NS[i] += t1 - T0[i];
        BYTES[i] += b1 - B0[i];
        CALLS[i]++;
        T0[i] = 0L;
    }

    /** Zero and start. Client thread. */
    public static void start() {
        install();
        for (int i = 0; i < NS.length; i++) {
            NS[i] = 0;
            BYTES[i] = 0;
            CALLS[i] = 0;
            T0[i] = 0;
        }
        recording = true;
    }

    public static void stop() {
        recording = false;
    }

    public static long calls(Kind k) {
        return CALLS[k.ordinal()];
    }

    /** Mean microseconds per call of everything between the two halves. */
    public static double usPerCall(Kind k) {
        int i = k.ordinal();
        return CALLS[i] == 0 ? 0 : NS[i] / 1e3 / CALLS[i];
    }

    public static double bytesPerCall(Kind k) {
        int i = k.ordinal();
        return CALLS[i] == 0 ? 0 : BYTES[i] / (double) CALLS[i];
    }

    /** "start 12.3 us 40 B, end ..., terrain ..., features ..." for a phase line. */
    public static String line() {
        StringBuilder sb = new StringBuilder();
        for (Kind k : Kind.values()) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(String.format(Locale.ROOT, "%s %.1f us %.0f B x%d", k.name().toLowerCase(Locale.ROOT),
                    usPerCall(k), bytesPerCall(k), calls(k)));
        }
        return sb.toString();
    }
}
