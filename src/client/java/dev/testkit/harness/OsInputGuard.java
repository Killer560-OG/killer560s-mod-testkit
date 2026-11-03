package dev.testkit.harness;

import dev.testkit.compat.McWindowOs;

import net.minecraft.client.Minecraft;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Map;
import java.util.TreeMap;

/**
 * A test client never takes OS focus from the person at the PC and never holds, hides or moves his real cursor
 * (killer560, 2026-11-02: "my mouse is bugging out. i think it keeps getting put in the game instances on the left
 * monitor"). Fabric's client gametest already keeps the game's LOGICAL state (window "focused", mouse "grabbed"), and
 * cancels Minecraft's own {@code InputConstants} grab; what reached his cursor was the OS side:
 * <ul>
 *   <li>the window was created/shown ACTIVATED (26.3 SDL shows it with SW_SHOW, 26.2 GLFW focuses on show), so its
 *       thread kept an active window and GLFW/SDL believed they had focus;</li>
 *   <li>then a disabled-cursor/relative-mode request (the mod's FakeCursor, GLFW re-applying its mode on focus) or the
 *       mod's Confine Mouse ({@code user32 ClipCursor}, gated on {@code isWindowActive()}, which the gametest forces
 *       true) clipped his cursor to the test window's rectangle and centered it there. A passive probe on 2026-11-02
 *       saw the global clip rectangle equal to a running test client's window rect while the Claude app was in front.</li>
 * </ul>
 * The fix: windows are created unfocusable/no-activate (MixinNoGrabWindow, per version), the mod's focus and cursor
 * calls and Confine Mouse are cancelled (MixinNoGrabMod*), and every client tick this guard checks the OS truth
 * (foreground window, OS focus, cursor capture, the global clip rectangle) and releases any clip that is inside our
 * window. {@code -Dtestkit.osGrab=allow} turns all of it off (diagnosis only - it can grab his cursor).
 *
 * <p>Counters are read by 1102-nograb.
 */
public final class OsInputGuard {

    private static final Logger LOGGER = LoggerFactory.getLogger("testkit-nograb");
    private static final boolean ACTIVE = !"allow".equalsIgnoreCase(System.getProperty("testkit.osGrab", ""));
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

    private static final Map<String, Integer> BLOCKED = new TreeMap<>();
    private static volatile int samples;
    private static volatile int foregroundTicks;
    private static volatile int osFocusedTicks;
    private static volatile int capturedTicks;
    private static volatile int clipInsideTicks;
    private static volatile int clipsReleased;
    private static volatile int logicalGrabTicks;
    private static volatile boolean noActivateApplied;
    private static long styledHwnd;

    private static MethodHandle getForegroundWindow;
    private static MethodHandle getClipCursor;
    private static MethodHandle clipCursor;
    private static MethodHandle getWindowRect;
    private static MethodHandle getWindowLongPtr;
    private static MethodHandle setWindowLongPtr;
    private static boolean nativeBroken;

    private OsInputGuard() {
    }

    /** Whether the guard is on (default). */
    public static boolean active() {
        return ACTIVE;
    }

    /** Records a call the guard cancelled, by name. */
    public static synchronized void blocked(String what) {
        int n = BLOCKED.merge(what, 1, Integer::sum);
        if (n == 1) {
            LOGGER.info("[nograb] blocked {} (first time)", what);
        }
    }

    public static synchronized Map<String, Integer> blockedCalls() {
        return new TreeMap<>(BLOCKED);
    }

    public static int samples() { return samples; }
    public static int foregroundTicks() { return foregroundTicks; }
    public static int osFocusedTicks() { return osFocusedTicks; }
    public static int capturedTicks() { return capturedTicks; }
    public static int clipInsideTicks() { return clipInsideTicks; }
    public static int clipsReleased() { return clipsReleased; }
    public static int logicalGrabTicks() { return logicalGrabTicks; }
    public static boolean noActivateApplied() { return noActivateApplied; }

    public static synchronized String summary() {
        return "samples=" + samples + " foreground=" + foregroundTicks + " osFocused=" + osFocusedTicks
                + " captured=" + capturedTicks + " clipInside=" + clipInsideTicks + " clipsReleased=" + clipsReleased
                + " logicalGrab=" + logicalGrabTicks + " noActivate=" + noActivateApplied + " blocked=" + BLOCKED;
    }

    /** Called at the head of every client tick. Reads the OS state; releases a clip held inside our window. */
    public static void sample(Minecraft mc) {
        if (!ACTIVE || mc.getWindow() == null) {
            return;
        }
        try {
            var window = mc.getWindow();
            long hwnd = McWindowOs.hwnd(window);
            samples++;
            if (mc.mouseHandler != null && mc.mouseHandler.isMouseGrabbed()) {
                logicalGrabTicks++;
            }
            if (McWindowOs.osFocused(window)) {
                osFocusedTicks++;
                note("OS focus on the test window");
            }
            if (McWindowOs.cursorCaptured(window)) {
                capturedTicks++;
                note("cursor capture mode on the test window");
            }
            if (!WINDOWS || hwnd == 0L || !ensureNative()) {
                return;
            }
            if (hwnd != styledHwnd) {
                styledHwnd = hwnd;
                noActivate(hwnd);
            }
            if ((long) getForegroundWindow.invokeExact() == hwnd) {
                foregroundTicks++;
                note("the test window is the foreground window");
            }
            try (Arena a = Arena.ofConfined()) {
                MemorySegment clip = a.allocate(16);
                MemorySegment win = a.allocate(16);
                if ((int) getClipCursor.invokeExact(clip) != 0 && (int) getWindowRect.invokeExact(hwnd, win) != 0) {
                    int cl = clip.get(ValueLayout.JAVA_INT, 0), ct = clip.get(ValueLayout.JAVA_INT, 4);
                    int cr = clip.get(ValueLayout.JAVA_INT, 8), cb = clip.get(ValueLayout.JAVA_INT, 12);
                    int wl = win.get(ValueLayout.JAVA_INT, 0), wt = win.get(ValueLayout.JAVA_INT, 4);
                    int wr = win.get(ValueLayout.JAVA_INT, 8), wb = win.get(ValueLayout.JAVA_INT, 12);
                    boolean inside = wr > wl && cl >= wl && ct >= wt && cr <= wr && cb <= wb;
                    if (inside) {
                        clipInsideTicks++;
                        int ok = (int) clipCursor.invokeExact(MemorySegment.NULL);
                        if (ok != 0) {
                            clipsReleased++;
                        }
                        LOGGER.warn("[nograb] the OS cursor was clipped to {},{} - {},{} inside the test window; released ({})",
                                cl, ct, cr, cb, ok != 0);
                    }
                }
            }
        } catch (Throwable t) {
            if (!nativeBroken) {
                LOGGER.warn("[nograb] sample failed", t);
            }
            nativeBroken = true;
        }
    }

    private static final java.util.Set<String> NOTED = new java.util.HashSet<>();

    private static synchronized void note(String what) {
        if (NOTED.add(what)) {
            LOGGER.warn("[nograb] {} (first time; {})", what, "counted for 1102-nograb");
        }
    }

    /** WS_EX_NOACTIVATE on our window: a click on it never activates it either (26.2 has no SDL NOT_FOCUSABLE). */
    private static void noActivate(long hwnd) throws Throwable {
        final int gwlExStyle = -20;
        final long wsExNoActivate = 0x08000000L;
        long ex = (long) getWindowLongPtr.invokeExact(hwnd, gwlExStyle);
        if ((ex & wsExNoActivate) == 0) {
            long ignored = (long) setWindowLongPtr.invokeExact(hwnd, gwlExStyle, ex | wsExNoActivate);
        }
        noActivateApplied = ((long) getWindowLongPtr.invokeExact(hwnd, gwlExStyle) & wsExNoActivate) != 0;
        LOGGER.info("[nograb] WS_EX_NOACTIVATE on the test window: {}", noActivateApplied);
    }

    private static synchronized boolean ensureNative() {
        if (nativeBroken) {
            return false;
        }
        if (getForegroundWindow != null) {
            return true;
        }
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup user32 = SymbolLookup.libraryLookup("user32", Arena.global());
            getForegroundWindow = linker.downcallHandle(user32.find("GetForegroundWindow").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG));
            getClipCursor = linker.downcallHandle(user32.find("GetClipCursor").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            clipCursor = linker.downcallHandle(user32.find("ClipCursor").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            getWindowRect = linker.downcallHandle(user32.find("GetWindowRect").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
            getWindowLongPtr = linker.downcallHandle(user32.find("GetWindowLongPtrW").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
            setWindowLongPtr = linker.downcallHandle(user32.find("SetWindowLongPtrW").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG));
            return true;
        } catch (Throwable t) {
            nativeBroken = true;
            LOGGER.warn("[nograb] user32 not available", t);
            return false;
        }
    }
}
