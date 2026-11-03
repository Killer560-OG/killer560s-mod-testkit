package dev.testkit.compat;

import com.mojang.blaze3d.platform.Window;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWin32;

/** 26.2 (GLFW): the OS side of the test window, for {@code OsInputGuard}. Same public signatures as mc26_3. */
public final class McWindowOs {

    private McWindowOs() {
    }

    /** The Win32 HWND, or 0. */
    public static long hwnd(Window window) {
        try {
            long h = window.handle();
            return h == 0L ? 0L : GLFWNativeWin32.glfwGetWin32Window(h);
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** Whether GLFW thinks the window has input focus. */
    public static boolean osFocused(Window window) {
        return GLFW.glfwGetWindowAttrib(window.handle(), GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE;
    }

    /** Whether the window holds the cursor: GLFW's disabled (clipped, centered) mode. */
    public static boolean cursorCaptured(Window window) {
        return GLFW.glfwGetInputMode(window.handle(), GLFW.GLFW_CURSOR) == GLFW.GLFW_CURSOR_DISABLED;
    }

    /** Called before the window is created: created without focus, and not focused when shown. */
    public static void beforeCreate() {
        GLFW.glfwWindowHint(GLFW.GLFW_FOCUSED, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
    }
}
