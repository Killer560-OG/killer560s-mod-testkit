package dev.testkit.gametest.hx;

import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.mod.Mod;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Performance HUD stutters (mod branch stutter-1026, {@code lagdisplay/FrameStats}): every frame timed on the render
 * thread, and a frame over the Stutter Threshold logged with what it was spent on. Each case forces the frame it needs
 * on the render thread and reads back the recorded stutter, the log line (LogTap), the stutter file and the HUD line:
 * <ul>
 *   <li>hooks: every phase hook fired (a wrong mixin target fails silently), and 200 quiet ticks record nothing;</li>
 *   <li>tick: 200 ms slept inside a FeatureGuard-wrapped tick handler is the tick phase, named in the mod's top three,
 *       "mostly the game", on the HUD line, in the log and the file;</li>
 *   <li>gc: a System.gc() plus a 60 ms sleep in a client task shows the collection;</li>
 *   <li>outside: 200 ms slept where the game runs none of its phases (a toast's update) is "outside the game", with the
 *       render thread's CPU far under the wall time: "likely your PC";</li>
 *   <li>verdict: the plain-words reason for six made-up frames (the PC, GC, the game, the swap, a busy PC, mixed).</li>
 * </ul>
 */
final class HxStutterCases {

    private static final String CFG = "lagdisplay.LagDisplayConfig";
    private static final String STATS = "lagdisplay.FrameStats";
    private static final String HUD = "lagdisplay.LagDisplayFeature$LagHudElement";
    private static final String PROBE_NAME = "TestkitStutterProbe";

    /** Milliseconds the probe tick handler sleeps on its next call (0 = none). Render thread. */
    private static volatile int sleepInTickMs;
    private static boolean probeRegistered;

    private HxStutterCases() {
    }

    static void register(Session s) {
        s.test("1026-stutter-hooks", HxStutterCases::hooks);
        s.test("1026-stutter-tick", HxStutterCases::tick);
        s.test("1026-stutter-gc", HxStutterCases::gc);
        s.test("1026-stutter-outside", HxStutterCases::outside);
        s.test("1026-stutter-verdict", HxStutterCases::verdict);
        s.test("1026-stutter-cleanup", HxStutterCases::cleanup);
    }

    private static void settings(Session c, boolean enabled, int thresholdMs) {
        run(c, () -> {
            Object cfg = Mod.cfg(CFG);
            Mod.call(cfg, "setEnabled", enabled);
            Mod.call(cfg, "setShowTps", false);
            Mod.call(cfg, "setShowPing", false);
            Mod.call(cfg, "setShowFps", true);
            Mod.call(cfg, "setShowCps", false);
            Mod.call(cfg, "setShowFrame", true);
            Mod.call(cfg, "setShowLowFps", true);
            Mod.call(cfg, "setLogStutters", true);
            Mod.call(cfg, "setStutterThresholdMs", thresholdMs);
        });
    }

    private static long total(Session c) {
        return c.onClient(mc -> (Long) Mod.staticCall(STATS, "stutterTotal"));
    }

    @SuppressWarnings("unchecked")
    private static String lastStutter(Session c) {
        return c.onClient(mc -> {
            List<Object> r = (List<Object>) Mod.staticCall(STATS, "recent", 1);
            return r.isEmpty() ? "" : (String) Mod.call(r.get(0), "text");
        });
    }

    @SuppressWarnings("unchecked")
    private static List<String> hudLines(Session c) {
        return c.onClient(mc -> List.copyOf((List<String>) Mod.staticCall(HUD, "currentLines")));
    }

    /** "name 12.3 ms" -> 12.3, or -1. */
    private static double msAfter(String text, String label) {
        Matcher m = Pattern.compile(Pattern.quote(label) + " (\\d+\\.\\d) ms").matcher(text);
        return m.find() ? Double.parseDouble(m.group(1)) : -1;
    }

    /** Waits for a stutter after {@code before}, fails if none. */
    private static String awaitStutter(Session c, long before, String why) {
        c.waitUntil(why + ": a stutter recorded", mc -> (Long) Mod.staticCall(STATS, "stutterTotal") > before, 60);
        return lastStutter(c);
    }

    private static void hooks(Session c) {
        settings(c, true, 500);
        c.ctx().waitTicks(40);
        String hits0 = c.onClient(mc -> (String) Mod.staticCall(STATS, "hits"));
        long frames0 = c.onClient(mc -> (Long) Mod.staticCall(STATS, "frames"));
        long stutters0 = total(c);
        c.ctx().waitTicks(200);
        String hits = c.onClient(mc -> (String) Mod.staticCall(STATS, "hits"));
        long frames = c.onClient(mc -> (Long) Mod.staticCall(STATS, "frames")) - frames0;
        long stutters = total(c) - stutters0;
        c.note("hooks after 240 ticks: " + hits + " | frames measured in 200 ticks: " + frames);
        // chunkSync only fires when a section is rebuilt synchronously (a block changed next to the player).
        for (String phase : new String[]{"tick", "tasks", "extract", "extractLevel", "extractGui", "render",
                "renderLevel", "renderGui", "limiter", "chunkSchedule", "chunkUpload", "swap"}) {
            long n = hit(hits, phase) - hit(hits0, phase);
            c.check(n > 0, "the " + phase + " hook never fired in 200 ticks (" + hits + ") - its mixin target is wrong");
        }
        long ticks = hit(hits, "tick") - hit(hits0, "tick");
        c.check(ticks >= 180 && ticks <= 230, "tick phase hits " + ticks + " in 200 ticks (want about 200)");
        c.check(frames >= 100, "only " + frames + " frames measured in 200 ticks");
        c.check(stutters == 0, stutters + " stutter(s) of 500 ms or more on quiet frames: " + lastStutter(c));
        List<String> lines = hudLines(c);
        String frameLine = lines.stream().filter(l -> l.startsWith("Frame: ")).findFirst().orElse("");
        c.check(frameLine.matches("Frame: \\d+\\.\\dms max \\| \\d+ stutters?"), "Frame line: '" + frameLine + "' in " + lines);
        String lowLine = lines.stream().filter(l -> l.startsWith("1% Low: ")).findFirst().orElse("");
        c.check(lowLine.matches("1% Low: \\d+ FPS"), "1% Low line: '" + lowLine + "' in " + lines);
        // Off: nothing is measured.
        settings(c, false, 500);
        c.ctx().waitTicks(5);
        long off0 = c.onClient(mc -> (Long) Mod.staticCall(STATS, "frames"));
        String offHits0 = c.onClient(mc -> (String) Mod.staticCall(STATS, "hits"));
        c.ctx().waitTicks(40);
        long off = c.onClient(mc -> (Long) Mod.staticCall(STATS, "frames")) - off0;
        String offHits = c.onClient(mc -> (String) Mod.staticCall(STATS, "hits"));
        c.check(off == 0 && offHits.equals(offHits0), "Performance HUD off but " + off + " frames measured, hooks "
                + offHits0 + " -> " + offHits);
        c.note("quiet: 0 stutters >= 500 ms; lines " + lines + "; off: 0 frames, hooks unchanged");
    }

    private static long hit(String hits, String phase) {
        Matcher m = Pattern.compile("(?:^| )" + phase + "=(\\d+)").matcher(hits);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    private static void ensureProbe(Session c) {
        if (probeRegistered) {
            return;
        }
        probeRegistered = true;
        run(c, () -> {
            ClientTickEvents.EndTick probe = client -> {
                int ms = sleepInTickMs;
                if (ms > 0) {
                    sleepInTickMs = 0;
                    try {
                        Thread.sleep(ms);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
            };
            ClientTickEvents.END_CLIENT_TICK.register((ClientTickEvents.EndTick) Mod.staticCall("util.FeatureGuard",
                    "end", PROBE_NAME, probe));
        });
    }

    private static void tick(Session c) {
        settings(c, true, 50);
        ensureProbe(c);
        c.ctx().waitTicks(20);
        long mark = LogTap.mark();
        long before = total(c);
        sleepInTickMs = 200;
        String s = awaitStutter(c, before, "200 ms in a tick handler");
        c.note("recorded: " + s);
        double frame = msAfter(s, "frame");
        Matcher tm = Pattern.compile("\\| ticks (\\d+)x (\\d+\\.\\d) ms").matcher(s);
        double ticks = tm.find() ? Double.parseDouble(tm.group(2)) : -1;
        c.check(frame >= 195 && frame < 600, "frame time " + frame + " ms for a 200 ms sleep: " + s);
        c.check(ticks >= 195, "the tick phase " + ticks + " ms does not hold the 200 ms sleep: " + s);
        c.check(s.contains("mostly the game"), "verdict is not 'mostly the game': " + s);
        double mod = msAfter(s, PROBE_NAME);
        c.check(mod >= 195, "the mod's top three do not name " + PROBE_NAME + " with ~200 ms: " + s);
        c.check(s.contains("screen none") && s.contains("world minecraft:"), "screen/world missing: " + s);
        if (Mod.isCheat()) {
            c.check(s.contains("Blink off, Auto Routes off, AP3 off"), "cheat jar without the automation states: " + s);
        } else {
            c.check(!s.contains("Blink"), "legit jar names Blink: " + s);
        }
        // The HUD line within a second.
        c.waitUntil("Frame line shows the 200 ms frame", mc -> {
            for (String l : clientLines()) {
                Matcher m = Pattern.compile("^Frame: (\\d+)\\.\\dms max \\| (\\d+) stutters?$").matcher(l);
                if (m.find() && Integer.parseInt(m.group(1)) >= 195 && Integer.parseInt(m.group(2)) >= 1) {
                    return true;
                }
            }
            return false;
        }, 40);
        // The log line (one INFO) and the file.
        List<String> logged = new ArrayList<>();
        for (String l : LogTap.since(mark)) {
            if (l.contains("[Stutter]") && l.contains(PROBE_NAME)) {
                logged.add(l);
            }
        }
        c.check(logged.size() == 1 && logged.get(0).contains(" INFO "), "log lines for the stutter: " + logged);
        Path file = c.onClient(mc -> (Path) Mod.staticCall(STATS, "file"));
        String marker = s.substring(0, Math.min(60, s.length()));
        c.waitUntil("the stutter file has the line", mc -> fileHas(file, marker), 40);
        c.note("HUD " + hudLines(c) + "; log: " + logged.get(0) + "; file " + file);
    }

    @SuppressWarnings("unchecked")
    private static List<String> clientLines() {
        return (List<String>) Mod.staticCall(HUD, "currentLines");
    }

    private static boolean fileHas(Path file, String text) {
        try {
            return Files.exists(file) && Files.readString(file, StandardCharsets.UTF_8).contains(text);
        } catch (Exception e) {
            return false;
        }
    }

    private static void gc(Session c) {
        settings(c, true, 50);
        c.ctx().waitTicks(20);
        long before = total(c);
        run(c, () -> {
            System.gc();
            try {
                Thread.sleep(60);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        String s = awaitStutter(c, before, "System.gc() + 60 ms in a client task");
        c.note("recorded: " + s);
        Matcher m = Pattern.compile("\\| GC (\\d+)x (\\d+\\.\\d) ms").matcher(s);
        c.check(m.find() && Integer.parseInt(m.group(1)) >= 1, "no collection in the stutter: " + s);
        c.check(msAfter(s, "packets/tasks") >= 55, "the task phase does not hold the gc + sleep: " + s);
    }

    private static void outside(Session c) {
        settings(c, true, 50);
        c.ctx().waitTicks(20);
        long before = total(c);
        run(c, () -> toastManager(Minecraft.getInstance()).addToast(new SleepyToast(200)));
        String s = awaitStutter(c, before, "200 ms slept in a toast's update (outside the game's phases)");
        c.note("recorded: " + s);
        double outside = msAfter(s, "outside the game");
        double game = msAfter(s, ": game");
        c.check(outside >= 190, "outside-the-game time " + outside + " ms for a 200 ms sleep outside every phase: " + s);
        c.check(game >= 0 && game < 100, "game time " + game + " ms should be small: " + s);
        c.check(s.contains("likely your PC"), "a frame where the render thread slept is not 'likely your PC': " + s);
        Matcher cpu = Pattern.compile("render thread CPU (\\d+\\.\\d) ms of (\\d+\\.\\d) ms wall").matcher(s);
        c.check(cpu.find() && Double.parseDouble(cpu.group(1)) < Double.parseDouble(cpu.group(2)) / 2,
                "render thread CPU not far under wall: " + s);
    }

    private static ToastManager toastManager(Minecraft mc) {
        try {
            return (ToastManager) Minecraft.class.getMethod("getToastManager").invoke(mc); // 26.1.2
        } catch (NoSuchMethodException e) {
            try {
                Object gui = Minecraft.class.getField("gui").get(mc); // 26.2: Gui.toastManager()
                return (ToastManager) gui.getClass().getMethod("toastManager").invoke(gui);
            } catch (ReflectiveOperationException e2) {
                throw new AssertionError(e2);
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** Sleeps once in its first update, then hides. */
    private static final class SleepyToast implements Toast {
        private final int ms;
        private boolean slept;

        SleepyToast(int ms) {
            this.ms = ms;
        }

        @Override
        public Visibility getWantedVisibility() {
            return slept ? Visibility.HIDE : Visibility.SHOW;
        }

        @Override
        public void update(ToastManager manager, long time) {
            if (!slept) {
                slept = true;
                try {
                    Thread.sleep(ms);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, Font font, long time) {
        }
    }

    private static void verdict(Session c) {
        long ms = 1_000_000L;
        String[][] cases = {
                // work, game, outside, swap, gc, cpu, stall -> verdict
                {"300", "10", "290", "0", "0", "5", "0", "likely your PC"},
                {"300", "10", "290", "0", "0", "280", "285", "likely your PC"},
                {"300", "10", "290", "0", "0", "280", "0", "outside the game's own work"},
                {"300", "280", "10", "10", "250", "280", "250", "mostly Java garbage collection"},
                {"300", "280", "10", "10", "0", "280", "0", "mostly the game"},
                {"300", "280", "10", "10", "0", "20", "270", "likely your PC: everything stalled"},
                {"300", "20", "20", "260", "0", "30", "0", "mostly the GPU/driver swap"},
                {"300", "120", "100", "80", "0", "200", "0", "mixed"},
        };
        List<String> got = new ArrayList<>();
        for (String[] k : cases) {
            String v = c.onClient(mc -> {
                try {
                    java.lang.reflect.Method m = Mod.cls(STATS).getDeclaredMethod("verdict", long.class, long.class,
                            long.class, long.class, long.class, long.class, long.class);
                    m.setAccessible(true);
                    return (String) m.invoke(null, Long.parseLong(k[0]) * ms, Long.parseLong(k[1]) * ms,
                            Long.parseLong(k[2]) * ms, Long.parseLong(k[3]) * ms, Long.parseLong(k[4]) * ms,
                            Long.parseLong(k[5]) * ms, Long.parseLong(k[6]) * ms);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            got.add(v);
            c.check(v.startsWith(k[7]), "frame " + String.join("/", k) + " gave '" + v + "'");
        }
        c.note("verdicts: " + got);
    }

    private static void cleanup(Session c) {
        sleepInTickMs = 0;
        settings(c, false, 50);
        run(c, () -> Mod.staticCall(STATS, "clear"));
        c.note("Performance HUD off, stutter list cleared");
    }
}
