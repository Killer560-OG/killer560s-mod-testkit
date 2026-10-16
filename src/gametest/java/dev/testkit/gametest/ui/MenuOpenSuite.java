package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.ModUnderTest;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.gametest.mod.Quiet;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;

import java.lang.reflect.Method;

/**
 * 1920-1921 (menu-open-bugs-1015): the two oddities the YouTube pilot's recording showed (1900-video-general).
 * <ul>
 *   <li>1920 /killer560 typed in chat. Fabric's {@code TestInput.pressKey} calls {@code KeyboardHandler.keyPress} directly
 *       inside {@code runOnClient}; a real key arrives through GLFW's callback, which KeyboardHandler wraps in
 *       {@code Minecraft.execute}, so the whole key press - the chat Enter, the command, its {@code client.execute} - runs
 *       as a task. Inside a task a nested {@code execute} is queued (ReentrantBlockableEventLoop.runningTask), so the menu
 *       opens after ChatScreen's own {@code setScreen(null)}; outside one it runs at once and ChatScreen then closes the
 *       menu it just opened. The case types the command both ways: through TestInput (noted, the 1900 FALLBACK) and
 *       through {@link #realKey} (a key press delivered as GLFW delivers it), which must open the menu.</li>
 *   <li>1921 a video clip recorded right after another one. Each {@link VideoRecorder} used to restart the menu's driven
 *       clock at 1,000,000 ms, BEHIND the moment the previous clip left it; the menu opened before the clip stamps its
 *       open fade at that later moment, and {@code MenuAnim.progress} reads a start up to 1 s in the future as "not begun"
 *       (0), so the whole menu went transparent for a second when the new clip's clock came within 1 s of the old one's
 *       end (14.6 s into Home after the 15.6 s sim-run clip, 24.6 s into Profiles after the 25.6 s HUD editor clip). The
 *       case leaves the clock where a 1.2 s clip would, opens the menu the way the clips do, and records 1.5 s: the open
 *       fade must be 1 on every frame.</li>
 * </ul>
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class MenuOpenSuite implements FabricClientGameTest {

    static final String TYPED = "1920-ui-killer560-typed";
    static final String CLOCK = "1921-ui-video-clock";
    static final String[] CASES = {TYPED, CLOCK};

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (dev.testkit.harness.ScenarioList.active() || dev.testkit.harness.ModGate.missing() != null) {
            for (String n : CASES) {
                dev.testkit.gametest.Scenario.skip(n);
            }
            return;
        }
        boolean any = false;
        for (String n : CASES) {
            any |= UiCase.selected(n);
        }
        if (!any) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.computeOnClient(mc -> {
            if (Quiet.enabled()) {
                Quiet.apply();
            }
            Mod.staticCall("util.SkyblockGate", "setEnabled", false);
            return null;
        });
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            ctx.waitTicks(40);
            SafeWorld.apply(ctx);
            UiCase.run(ctx, TYPED, MenuOpenSuite::typed);
            UiCase.run(ctx, CLOCK, MenuOpenSuite::clock);
        } finally {
            ctx.runOnClient(mc -> Mod.staticCall("gui.MenuAnim", "setClockForTest", -1.0));
        }
    }

    // ---- helpers --------------------------------------------------------------------------------------------------------

    private static Method keyPress;

    /**
     * Presses and releases {@code key} the way a real keyboard does: GLFW's key callback hands KeyboardHandler.keyPress to
     * {@code Minecraft.execute} (javap 26.1.2/26.2, {@code KeyboardHandler.lambda$setup}), so the press runs as a task.
     * TestInput's own pressKey skips that wrapper. Waits a tick for the task to run.
     */
    static void realKey(UiCase c, int key) {
        c.onClient(mc -> {
            try {
                if (keyPress == null) {
                    Method m = KeyboardHandler.class.getDeclaredMethod("keyPress", long.class, int.class, KeyEvent.class);
                    m.setAccessible(true);
                    keyPress = m;
                }
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("no KeyboardHandler.keyPress(long, int, KeyEvent)", e);
            }
            long window = mc.getWindow().handle();
            for (int action : new int[]{InputConstants.PRESS, InputConstants.RELEASE}) {
                mc.execute(() -> {
                    try {
                        keyPress.invoke(mc.keyboardHandler, window, action, new KeyEvent(key, 0, 0));
                    } catch (ReflectiveOperationException e) {
                        throw new AssertionError(e);
                    }
                });
            }
            return null;
        });
        c.ticks(1);
    }

    private static String screenName(UiCase c) {
        return c.onClient(mc -> {
            Screen s = McCompat.screen(mc);
            return s == null ? "null" : s.getClass().getSimpleName();
        });
    }

    private static String chatText(UiCase c) {
        return c.onClient(mc -> {
            Screen s = McCompat.screen(mc);
            Object input = s == null ? null : Mod.field(s, "input");
            return input instanceof EditBox e ? e.getValue() : "?";
        });
    }

    /** T, the command, Enter; {@code real} sends T and Enter as real keys. Returns the screen 1..10 ticks after Enter. */
    private static String typeAndSubmit(UiCase c, String command, boolean real) {
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            return null;
        });
        c.ticks(2);
        if (real) {
            realKey(c, InputConstants.KEY_T);
        } else {
            c.ctx().getInput().pressKey(InputConstants.KEY_T);
        }
        c.ticks(2);
        c.check("ChatScreen".equals(screenName(c)), "T did not open the chat (" + screenName(c) + ")");
        // A chat closed by a screen opened from inside its own submit (the TestInput way) keeps its text as a draft,
        // which the next T restores; start from an empty box either way.
        String draft = chatText(c);
        if (!draft.isEmpty()) {
            c.note("the chat reopened with the draft '" + draft + "'");
            c.onClient(mc -> {
                ((EditBox) Mod.field(McCompat.screen(mc), "input")).setValue("");
                return null;
            });
        }
        c.ctx().getInput().typeChars(command);
        c.ticks(1);
        c.check(command.equals(chatText(c)), "the chat box holds '" + chatText(c) + "', not " + command);
        if (real) {
            realKey(c, InputConstants.KEY_RETURN);
        } else {
            c.ctx().getInput().pressKey(InputConstants.KEY_RETURN);
        }
        String seen = screenName(c);
        for (int i = 0; i < 10 && !"ModScreen".equals(seen); i++) {
            c.ticks(1);
            seen = screenName(c);
        }
        return seen;
    }

    // ---- 1920 -----------------------------------------------------------------------------------------------------------

    static void typed(UiCase c) throws Exception {
        String viaTestInput = typeAndSubmit(c, "/killer560", false);
        c.note("TestInput keys (the 1900 video's way): screen after Enter = " + viaTestInput
                + ("ModScreen".equals(viaTestInput) ? "" : " - the command ran outside a task, its client.execute ran at"
                + " once and ChatScreen's setScreen(null) closed the menu"));
        String viaReal = typeAndSubmit(c, "/killer560", true);
        c.note("real keys (GLFW's Minecraft.execute path): screen after Enter = " + viaReal);
        c.check("ModScreen".equals(viaReal), "/killer560 typed with real keys did not open the mod menu (screen "
                + viaReal + ")");
        // and it stays open (nothing closes it a tick later)
        c.ticks(20);
        c.check("ModScreen".equals(screenName(c)), "the menu /killer560 opened closed again: " + screenName(c));
    }

    // ---- 1921 -----------------------------------------------------------------------------------------------------------

    static void clock(UiCase c) throws Exception {
        String wasMode = MenuAnimSuite.mode();
        MenuAnimSuite.mode("SMOOTH");
        try {
            // where a 1.2 s clip leaves the clock (VideoRecorder starts at 1,000,000 ms, 72 frames)
            double prevEnd = 1_000_000 + 72 * VideoRecorder.FRAME_MS;
            MenuAnimSuite.clock(c, prevEnd);
            // the next clip's menu, opened off camera before its recorder exists (VideoGeneralSuite.openMenuQuiet)
            Screen s = c.onClient(mc -> {
                try {
                    return MenuACases.open(mc);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            c.ticks(3);
            VideoRecorder r = new VideoRecorder(c, "clock-check-1921");
            int bad = 0;
            int firstBad = -1;
            float lowest = 1f;
            try {
                for (int i = 0; i < 90; i++) {
                    r.frame();
                    float fade = c.onClient(mc -> ((float[]) Mod.call(s, "animState"))[0]);
                    boolean open = c.onClient(mc -> McCompat.screen(mc) == s);
                    if (fade < 1f || !open) {
                        bad++;
                        if (firstBad < 0) {
                            firstBad = i;
                        }
                        lowest = Math.min(lowest, fade);
                    }
                }
            } finally {
                r.close();
            }
            c.note(bad + " of 90 frames drew the menu faded (lowest open fade " + lowest + ", first at frame " + firstBad + ")");
            c.check(r.frames() == 90, "the recorder wrote " + r.frames() + " frames, not 90");
            c.check(bad == 0, bad + " of 90 frames drew the menu faded or closed, from frame " + firstBad
                    + " (lowest open fade " + lowest + "): the recorder's clock started behind the menu's open stamp");
        } finally {
            MenuAnimSuite.mode(wasMode);
            MenuAnimSuite.releaseClock(c);
        }
    }
}
