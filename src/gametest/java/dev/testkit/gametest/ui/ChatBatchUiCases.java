package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import com.mojang.blaze3d.platform.Window;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * killer560's chat batch (mod branch chat-batch, 2026-10-08), the menu half; the chat half is
 * {@code chat/ChatBatchSuite} (560-chat-session).
 * <ul>
 *   <li>565-ui-chat-voice-widget: "make the send-to-party-chat take up the full length of that menu, not half". In the
 *       real menu, Voice To Text's "Send to" row is exactly as wide as the Microphone row and the master switch, in Open
 *       Mic and in Push To Talk; Open Mic shows the Silence Before Send slider (real mouse drags reach 0.3 s and 3.0 s,
 *       a click a quarter along reads ~1.0 s, the value survives save + load), Push To Talk does not; no rows overlap.</li>
 *   <li>569-ui-chat-hider-tab: the Chat folder has one Chat Hider section and no Hide Chat Messages or Chat Tidy section;
 *       its nine rows are the master switch and every old rule minus the two damage-family switches; every row has a
 *       tooltip; a real press on the master switch saves it; Copy Chat's tooltip names Shift and Ctrl.</li>
 * </ul>
 * {@code TESTKIT_CHAT_SHOTS=<dir>} copies the GUI-scale-2 pictures there.
 */
final class ChatBatchUiCases {

    private static final String VOICE = "voicetotext.VoiceToTextFeature";
    private static final String VCFG = "voicetotext.VoiceToTextConfig";
    private static final String HIDER = "chattidy.ChatTidyConfig";

    private ChatBatchUiCases() {
    }

    // ==== 565 ========================================================================================================

    static void voiceWidget(UiCase c) throws Exception {
        Object cfg = Mod.cfg(VCFG);
        boolean wasOn = c.onClient(mc -> (Boolean) Mod.call(cfg, "isEnabled"));
        Object oldMode = c.onClient(mc -> Mod.call(cfg, "getMode"));
        int oldSilence = c.onClient(mc -> (Integer) Mod.call(cfg, "getOpenMicSilenceMs"));
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        try {
            c.onClient(mc -> {
                // The arm-once latch first, so switching Open Mic on opens no microphone and fetches no Vosk model.
                Mod.setField(VOICE, "openMicArmed", true);
                Mod.call(cfg, "setMode", Mod.enumValue(VCFG + "$Mode", "OPEN_MIC"));
                Mod.call(cfg, "setOpenMicSilenceMs", 1000);
                Mod.call(cfg, "setEnabled", true);
                mc.options.guiScale().set(2);
                mc.resizeGui();
                return null;
            });
            Screen s = open(c, "Voice To Text");
            Map<String, AbstractWidget> rows = rows(c, s);
            c.note("Open Mic rows: " + describe(rows));
            AbstractWidget master = need(c, rows, "Voice To Text:");
            AbstractWidget mic = need(c, rows, "Microphone:");
            AbstractWidget send = need(c, rows, "Send to:");
            AbstractWidget silence = need(c, rows, "Silence Before Send:");
            c.check(silence instanceof AbstractSliderButton, "Silence Before Send is not a slider");
            c.check(send.getWidth() == mic.getWidth() && send.getWidth() == master.getWidth() && send.getX() == mic.getX(),
                    String.format(Locale.ROOT, "Send to is %d wide at x %d; Microphone %d at x %d; the master switch %d",
                            send.getWidth(), send.getX(), mic.getWidth(), mic.getX(), master.getWidth()));
            c.note(String.format(Locale.ROOT, "Open Mic: Send to %dx%d at %d,%d - full width (Microphone %d, master %d)",
                    send.getWidth(), send.getHeight(), send.getX(), send.getY(), mic.getWidth(), master.getWidth()));
            overlaps(c, rows);
            scrollTop(c, s, "Voice To Text:"); // the section at the top of the picture
            shot(c, "open-mic");

            // the slider by real mouse input
            int min = 300;
            int max = 3000;
            double[] b = box(c, s, "Silence Before Send:");
            SliderDrive.drag(c, s, b[0] + b[2] / 2, b[1] + b[3] / 2, b[0] - 30);
            int left = silence(c, cfg);
            b = box(c, s, "Silence Before Send:");
            SliderDrive.drag(c, s, b[0] + b[2] / 2, b[1] + b[3] / 2, b[0] + b[2] + 30);
            int right = silence(c, cfg);
            b = box(c, s, "Silence Before Send:");
            SliderDrive.click(c, s, b[0] + 4 + (b[2] - 8) * 0.25, b[1] + b[3] / 2);
            int quarter = silence(c, cfg);
            c.note("Silence Before Send: drag past left -> " + left + " ms, past right -> " + right
                    + " ms, click a quarter along -> " + quarter + " ms");
            c.check(left == min, "dragged past its left end the slider reads " + left + " ms, want " + min);
            c.check(right == max, "dragged past its right end the slider reads " + right + " ms, want " + max);
            c.check(Math.abs(quarter - (min + (max - min) * 0.25)) <= 100, "a quarter along reads " + quarter + " ms");
            c.check(quarter % 100 == 0, "the slider is not in 0.1 s steps: " + quarter);
            int saved = c.onClient(mc -> {
                Mod.staticCall(VCFG, "load");
                return (Integer) Mod.call(Mod.cfg(VCFG), "getOpenMicSilenceMs");
            });
            c.check(saved == quarter, "after load the setting reads " + saved + " ms, the slider set " + quarter);
            c.ctx().runOnClient(mc -> Mod.setField(VOICE, "openMicArmed", true)); // load() made a new singleton

            // Push To Talk: no slider, Send to still full width
            Object fresh = Mod.cfg(VCFG);
            c.onClient(mc -> {
                Mod.call(fresh, "setMode", Mod.enumValue(VCFG + "$Mode", "PUSH_TO_TALK"));
                return null;
            });
            rows = rows(c, s);
            c.note("Push To Talk rows: " + describe(rows));
            c.check(find(rows, "Silence Before Send:") == null, "Push To Talk shows the Silence Before Send slider");
            send = need(c, rows, "Send to:");
            mic = need(c, rows, "Microphone:");
            c.check(send.getWidth() == mic.getWidth(), "Push To Talk: Send to " + send.getWidth() + " wide, Microphone "
                    + mic.getWidth());
            overlaps(c, rows);
            scrollTop(c, s, "Voice To Text:");
            shot(c, "push-to-talk");
        } finally {
            c.onClient(mc -> {
                Object now = Mod.cfg(VCFG);
                Mod.call(now, "setEnabled", wasOn);
                Mod.call(now, "setMode", oldMode);
                Mod.call(now, "setOpenMicSilenceMs", oldSilence);
                Mod.call(now, "save");
                McCompat.setScreen(mc, null);
                mc.options.guiScale().set(oldGui);
                mc.resizeGui();
                return null;
            });
        }
    }

    private static int silence(UiCase c, Object cfg) {
        return c.onClient(mc -> (Integer) Mod.call(Mod.cfg(VCFG), "getOpenMicSilenceMs"));
    }

    // ==== 569 ========================================================================================================

    static final List<String> HIDER_ROWS = List.of("Chat Hider", "Stack Duplicate Messages", "Hide Damage Messages",
            "Hide Useless Messages", "Hide Discord Warnings", "Hide Microsoft Warnings", "Hide Empty Chat Messages",
            "Hide Actionbar", "Hide Non-Rank Invites");

    static void hiderTab(UiCase c) throws Exception {
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        boolean wasOn = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(HIDER), "getEnabledRaw"));
        try {
            c.onClient(mc -> {
                mc.options.guiScale().set(2);
                mc.resizeGui();
                return null;
            });
            // the Chat folder's sections
            List<String> sections = c.onClient(mc -> {
                try {
                    ModScreenDriver d = new ModScreenDriver(mc);
                    for (Object t : d.topTabs()) {
                        if ("Chat".equals(R.get(t, "name"))) {
                            List<String> out = new ArrayList<>();
                            for (Object sub : d.subTabs(t)) {
                                out.add((String) R.get(sub, "name"));
                            }
                            return out;
                        }
                    }
                    return List.of();
                } catch (Throwable t) {
                    throw new AssertionError(UiCase.describe(t), t);
                }
            });
            c.note("Chat folder: " + sections);
            c.check(sections.contains("Chat Hider"), "no Chat Hider section in the Chat folder");
            c.check(!sections.contains("Chat Tidy") && !sections.contains("Hide Chat Messages"),
                    "an old section is still there: " + sections);

            Screen s = open(c, "Chat Hider");
            Map<String, AbstractWidget> rows = rows(c, s);
            List<String> toggles = new ArrayList<>();
            for (String l : rows.keySet()) {
                int colon = l.indexOf(':');
                if (colon > 0) {
                    toggles.add(l.substring(0, colon));
                }
            }
            c.note("Chat Hider rows: " + toggles);
            c.check(toggles.equals(HIDER_ROWS), "rows are " + toggles + ", want " + HIDER_ROWS);
            Method describe = R.cls("gui.SettingTooltips").getMethod("describe", String.class, AbstractWidget.class,
                    String.class);
            for (Map.Entry<String, AbstractWidget> e : rows.entrySet()) {
                if (e.getKey().indexOf(':') < 0) {
                    continue; // section headers
                }
                String tip = c.onClient(mc -> {
                    try {
                        return (String) describe.invoke(null, "Chat", e.getValue(), e.getKey());
                    } catch (ReflectiveOperationException ex) {
                        throw new AssertionError(ex);
                    }
                });
                if (tip == null || tip.isBlank()) {
                    c.problem("no tooltip on Chat Hider row '" + e.getKey() + "'");
                }
            }
            AbstractWidget damageRow = need(c, rows, "Hide Damage Messages:");
            String damageTip = c.onClient(mc -> {
                try {
                    return (String) describe.invoke(null, "Chat", damageRow, ModScreenDriver.label(damageRow));
                } catch (ReflectiveOperationException ex) {
                    throw new AssertionError(ex);
                }
            });
            c.check(damageTip != null && damageTip.contains("ability") && damageTip.contains("hits you take"),
                    "the Hide Damage Messages tooltip does not cover both families: " + damageTip);
            String copyTip = c.onClient(mc -> {
                try {
                    return (String) describe.invoke(null, "Chat", null, "Copy Chat");
                } catch (ReflectiveOperationException ex) {
                    throw new AssertionError(ex);
                }
            });
            c.check(copyTip != null && copyTip.contains("Shift or Ctrl"), "Copy Chat's tooltip: " + copyTip);

            // a real press on the master switch flips it and saves it
            AbstractWidget master = need(c, rows, "Chat Hider:");
            boolean before = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(HIDER), "getEnabledRaw"));
            c.onClient(mc -> ModScreenDriver.press(master));
            boolean after = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(HIDER), "getEnabledRaw"));
            c.check(after != before, "pressing the Chat Hider switch did not flip it");
            Path file = c.onClient(mc -> (Path) Mod.staticCall("util.ModPaths", "config", "killer560smod-chattidy.json"));
            String json = Files.readString(file);
            c.check(json.contains("\"enabled\": " + after), "the press was not saved: " + json);
            c.note("master switch " + before + " -> " + after + " by a real press, saved");
            overlaps(c, rows(c, s));
            scrollTop(c, s, "Chat Hider:");
            shot(c, "tab");
        } finally {
            c.onClient(mc -> {
                Object cfg = Mod.cfg(HIDER);
                Mod.call(cfg, "setEnabled", wasOn);
                Mod.call(cfg, "save");
                McCompat.setScreen(mc, null);
                mc.options.guiScale().set(oldGui);
                mc.resizeGui();
                return null;
            });
        }
    }

    // ==== shared =====================================================================================================

    /** Opens the real ModScreen with {@code tabName}'s folder sections expanded. */
    static Screen open(UiCase c, String tabName) {
        Screen s = c.onClient(mc -> {
            try {
                ModScreenDriver d = new ModScreenDriver(mc);
                List<Object> tops = d.topTabs();
                for (int i = 0; i < tops.size(); i++) {
                    List<Object[]> chain = new ArrayList<>();
                    if (find(d, tops.get(i), tabName, chain)) {
                        d.select(i);
                        for (Object[] step : chain) {
                            d.expanded(step[0]).add((Integer) step[1]);
                        }
                        McCompat.setScreen(mc, d.screen);
                        return d.screen;
                    }
                }
                return null;
            } catch (Throwable t) {
                throw new AssertionError("could not open gui.ModScreen: " + UiCase.describe(t), t);
            }
        });
        c.check(s != null, "no '" + tabName + "' tab in the mod menu");
        c.ticks(5);
        return s;
    }

    private static boolean find(ModScreenDriver d, Object tab, String name, List<Object[]> chain) {
        if (name.equals(R.get(tab, "name"))) {
            return true;
        }
        if (!d.isFolder(tab)) {
            return false;
        }
        List<Object> subs = d.subTabs(tab);
        for (int j = 0; j < subs.size(); j++) {
            chain.add(new Object[]{tab, j});
            if (find(d, subs.get(j), name, chain)) {
                return true;
            }
            chain.remove(chain.size() - 1);
        }
        return false;
    }

    /** The content pane's rows after a rebuild, by label (in order). */
    @SuppressWarnings("unchecked")
    static Map<String, AbstractWidget> rows(UiCase c, Screen s) {
        return c.onClient(mc -> {
            try {
                R.call0(s, "rebuild");
                Object pane = R.get(s, "contentPane");
                Map<String, AbstractWidget> out = new LinkedHashMap<>();
                if (pane != null) {
                    for (AbstractWidget w : new ArrayList<>((java.util.Collection<AbstractWidget>) R.get(pane, "children"))) {
                        out.putIfAbsent(ModScreenDriver.label(w), w);
                    }
                }
                return out;
            } catch (Throwable t) {
                throw new AssertionError(UiCase.describe(t), t);
            }
        });
    }

    static AbstractWidget find(Map<String, AbstractWidget> rows, String prefix) {
        for (Map.Entry<String, AbstractWidget> e : rows.entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                return e.getValue();
            }
        }
        return null;
    }

    static AbstractWidget need(UiCase c, Map<String, AbstractWidget> rows, String prefix) {
        AbstractWidget w = find(rows, prefix);
        c.check(w != null, "no row starting '" + prefix + "' in " + rows.keySet());
        return w;
    }

    static String describe(Map<String, AbstractWidget> rows) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, AbstractWidget> e : rows.entrySet()) {
            AbstractWidget w = e.getValue();
            out.add(String.format(Locale.ROOT, "'%s' %dx%d@%d,%d", e.getKey(), w.getWidth(), w.getHeight(), w.getX(), w.getY()));
        }
        return String.join(", ", out);
    }

    static void overlaps(UiCase c, Map<String, AbstractWidget> rows) {
        List<AbstractWidget> ws = new ArrayList<>(rows.values());
        for (int a = 0; a < ws.size(); a++) {
            AbstractWidget p = ws.get(a);
            for (int b = a + 1; b < ws.size(); b++) {
                AbstractWidget q = ws.get(b);
                if (p.getX() < q.getX() + q.getWidth() && q.getX() < p.getX() + p.getWidth()
                        && p.getY() < q.getY() + q.getHeight() && q.getY() < p.getY() + p.getHeight()) {
                    c.problem("rows overlap: '" + ModScreenDriver.label(p) + "' / '" + ModScreenDriver.label(q) + "'");
                }
            }
        }
    }

    /** Scrolls the menu so the row starting {@code prefix} sits at the top of the content pane. */
    @SuppressWarnings("unchecked")
    static void scrollTop(UiCase c, Screen s, String prefix) {
        c.onClient(mc -> {
            try {
                R.call0(s, "rebuild");
                AbstractWidget pane = (AbstractWidget) R.get(s, "contentPane");
                for (AbstractWidget w : new ArrayList<>((java.util.Collection<AbstractWidget>) R.get(pane, "children"))) {
                    if (ModScreenDriver.label(w).startsWith(prefix)) {
                        int offset = (Integer) R.getStatic(s.getClass(), "scrollOffset");
                        R.setStatic(s.getClass(), "scrollOffset", Math.max(0, offset + w.getY() - pane.getY() - 30));
                        R.call0(s, "rebuild");
                        break;
                    }
                }
                return null;
            } catch (Throwable t) {
                throw new AssertionError(UiCase.describe(t), t);
            }
        });
    }

    /** {x, y, w, h} of the row starting {@code prefix}, scrolled into the visible band. */
    static double[] box(UiCase c, Screen s, String prefix) {
        double[] b = c.onClient(mc -> {
            try {
                for (int attempt = 0; attempt < 2; attempt++) {
                    R.call0(s, "rebuild");
                    AbstractWidget pane = (AbstractWidget) R.get(s, "contentPane");
                    @SuppressWarnings("unchecked")
                    List<AbstractWidget> kids = new ArrayList<>((java.util.Collection<AbstractWidget>) R.get(pane, "children"));
                    for (AbstractWidget w : kids) {
                        if (ModScreenDriver.label(w).startsWith(prefix)) {
                            int top = pane.getY() + 4;
                            int bottom = pane.getY() + pane.getHeight() - 4;
                            if (w.getY() >= top && w.getY() + w.getHeight() <= bottom) {
                                return new double[]{w.getX(), w.getY(), w.getWidth(), w.getHeight()};
                            }
                            int offset = (Integer) R.getStatic(s.getClass(), "scrollOffset");
                            R.setStatic(s.getClass(), "scrollOffset", offset + (w.getY() - top) - 20);
                            break;
                        }
                    }
                }
                return null;
            } catch (Throwable t) {
                throw new AssertionError(UiCase.describe(t), t);
            }
        });
        c.check(b != null, "no visible row '" + prefix + "'");
        return b;
    }

    static void shot(UiCase c, String label) {
        c.ticks(4);
        String name = c.name() + "-" + label;
        Path taken = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(name));
        Path kept = dev.testkit.harness.Report.screenshot(name, taken);
        c.note("picture " + label + " -> " + (kept != null ? kept : taken));
        String dir = System.getenv("TESTKIT_CHAT_SHOTS");
        if (dir == null || dir.isBlank()) {
            return;
        }
        try {
            String jar = Mod.isCheat() ? "cheat" : "legit";
            String mcv = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("minecraft").orElseThrow()
                    .getMetadata().getVersion().getFriendlyString();
            Path out = Path.of(dir).resolve(name + "-" + mcv + "-" + jar + ".png");
            Files.createDirectories(out.getParent());
            Files.copy(taken, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception | Error e) {
            c.note("could not copy " + name + ": " + e);
        }
    }

    /** Real mouse input on a mod screen, through the factor it was laid out with (as {@code SliderCases}). */
    static final class SliderDrive {
        private SliderDrive() {
        }

        static double[] toWindow(UiCase c, Screen s, double sx, double sy) {
            return c.onClient(mc -> {
                float f = ((Number) Mod.staticCall("hud.AutoScale", "appliedFactor", s)).floatValue();
                Window w = mc.getWindow();
                return new double[]{sx * f * w.getScreenWidth() / (double) w.getGuiScaledWidth(),
                        sy * f * w.getScreenHeight() / (double) w.getGuiScaledHeight()};
            });
        }

        static void drag(UiCase c, Screen s, double sx, double sy, double toX) {
            double[] a = toWindow(c, s, sx, sy);
            c.ctx().getInput().setCursorPos(a[0], a[1]);
            c.ticks(2);
            c.ctx().getInput().holdMouse(0);
            c.ticks(2);
            for (int i = 1; i <= 6; i++) {
                double[] p = toWindow(c, s, sx + (toX - sx) * i / 6.0, sy);
                c.ctx().getInput().setCursorPos(p[0], p[1]);
                c.ticks(1);
            }
            c.ctx().getInput().releaseMouse(0);
            c.ticks(3);
        }

        static void click(UiCase c, Screen s, double sx, double sy) {
            double[] a = toWindow(c, s, sx, sy);
            c.ctx().getInput().setCursorPos(a[0], a[1]);
            c.ticks(2);
            c.ctx().getInput().pressMouse(0);
            c.ticks(3);
        }
    }
}
