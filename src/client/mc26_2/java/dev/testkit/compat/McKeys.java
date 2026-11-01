package dev.testkit.compat;

import com.mojang.blaze3d.platform.InputConstants;

/**
 * Key codes. The mod under test stores key codes in GLFW numbering on every version (its util/Keys and compat/McInput,
 * mod 26.3 port), and so does the testkit: a scenario writes GLFW numbers into the mod's settings and turns one into the
 * version's NATIVE code with {@link #nat} only where it presses a key through the gametest input API or builds a
 * {@code KeyEvent}. Identical public signatures in both copies.
 */
public final class McKeys {

    private McKeys() {
    }

    /** GLFW key code -> this version's native key code. 26.2 still uses GLFW codes: identity. */
    public static int nat(int glfw) {
        return glfw;
    }

    /** GLFW mouse button (0 left, 1 right, 2 middle) -> this version's native button. 26.2: identity. */
    public static int mouse(int glfwButton) {
        return glfwButton;
    }
}
