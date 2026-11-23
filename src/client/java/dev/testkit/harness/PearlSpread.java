package dev.testkit.harness;

/**
 * Removes vanilla's random throw spread from ender pearls thrown on the integrated (sim) server while a case asks for
 * it ({@code dev.testkit.mixin.MixinEnderpearlSpread}). Test side only: the mod and his routes are untouched.
 */
public final class PearlSpread {
    /** True while a case wants every pearl thrown dead straight along the thrower's look. */
    public static volatile boolean off;
    /** Pearls thrown with the spread removed since the last {@link #set} - proof it acted. */
    public static volatile int removed;

    private PearlSpread() {
    }

    public static void set(boolean noSpread) {
        off = noSpread;
        removed = 0;
    }
}
