package dev.testkit.compat;

import com.mojang.blaze3d.platform.Window;

import org.lwjgl.sdl.SDLHints;
import org.lwjgl.sdl.SDLMouse;
import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDLVideo;

/** 26.3 (SDL3): the OS side of the test window, for {@code OsInputGuard}. Same public signatures as mc26_2. */
public final class McWindowOs {

    private McWindowOs() {
    }

    /** The Win32 HWND, or 0. */
    public static long hwnd(Window window) {
        long h = window.handle();
        if (h == 0L) {
            return 0L;
        }
        int props = SDLVideo.SDL_GetWindowProperties(h);
        return props == 0 ? 0L : SDLProperties.SDL_GetPointerProperty(props, SDLVideo.SDL_PROP_WINDOW_WIN32_HWND_POINTER, 0L);
    }

    /** Whether SDL thinks the window has keyboard (input) focus. */
    public static boolean osFocused(Window window) {
        return (SDLVideo.SDL_GetWindowFlags(window.handle()) & SDLVideo.SDL_WINDOW_INPUT_FOCUS) != 0;
    }

    /** Whether the window holds the cursor: relative mode or a mouse grab. */
    public static boolean cursorCaptured(Window window) {
        long h = window.handle();
        return SDLMouse.SDL_GetWindowRelativeMouseMode(h)
                || (SDLVideo.SDL_GetWindowFlags(h) & SDLVideo.SDL_WINDOW_MOUSE_GRABBED) != 0;
    }

    /** Called before the window is created: shown and raised windows never activate, no click auto-capture. */
    public static void beforeCreate() {
        SDLHints.SDL_SetHint(SDLHints.SDL_HINT_WINDOW_ACTIVATE_WHEN_SHOWN, "0");
        SDLHints.SDL_SetHint(SDLHints.SDL_HINT_WINDOW_ACTIVATE_WHEN_RAISED, "0");
        SDLHints.SDL_SetHint(SDLHints.SDL_HINT_FORCE_RAISEWINDOW, "0");
        SDLHints.SDL_SetHint(SDLHints.SDL_HINT_MOUSE_AUTO_CAPTURE, "0");
    }
}
