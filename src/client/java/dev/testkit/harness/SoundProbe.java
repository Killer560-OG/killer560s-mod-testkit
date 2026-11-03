package dev.testkit.harness;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts every sound that reaches the sound engine ({@code SoundEngine.play} at its head, fed by
 * {@code MixinSoundEngineProbe}), by sound event id. A sound a mod drops in {@code SoundManager.play} / {@code playDelayed}
 * never gets here; a delayed one gets here when its delay runs out. Always on (one map increment per sound). Added for the
 * Sound Disabler cases (1102-sound-*, mod sounddisable-1102).
 */
public final class SoundProbe {

    private static final Map<String, AtomicInteger> REACHED = new ConcurrentHashMap<>();
    private static final AtomicInteger TOTAL = new AtomicInteger();

    private SoundProbe() {
    }

    public static void reached(String id) {
        TOTAL.incrementAndGet();
        REACHED.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
    }

    /** Sounds with id {@code id} (e.g. {@code minecraft:entity.ghast.shoot}) that reached the engine since the client started. */
    public static int count(String id) {
        AtomicInteger n = REACHED.get(id);
        return n == null ? 0 : n.get();
    }

    /** Every sound that reached the engine since the client started. */
    public static int total() {
        return TOTAL.get();
    }
}
