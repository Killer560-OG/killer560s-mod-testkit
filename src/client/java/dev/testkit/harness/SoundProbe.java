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
    private static final Map<String, java.util.concurrent.atomic.DoubleAdder> VOLUME = new ConcurrentHashMap<>();

    private SoundProbe() {
    }

    private static java.lang.reflect.Field volumeField;

    /** The instance's own volume field: getVolume() reads the resolved Sound, still null when the engine is first handed it. */
    public static float rawVolume(Object instance) {
        try {
            if (volumeField == null) {
                volumeField = net.minecraft.client.resources.sounds.AbstractSoundInstance.class.getDeclaredField("volume");
                volumeField.setAccessible(true);
            }
            return volumeField.getFloat(instance);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Float.NaN;
        }
    }

    public static void reached(String id, float volume) {
        VOLUME.computeIfAbsent(id, k -> new java.util.concurrent.atomic.DoubleAdder()).add(volume);
        reached(id);
    }

    /** The sum of the volumes of every sound with this id that reached the engine (two copies at 1.0 read 2.0). */
    public static double volume(String id) {
        java.util.concurrent.atomic.DoubleAdder v = VOLUME.get(id);
        return v == null ? 0 : v.sum();
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
