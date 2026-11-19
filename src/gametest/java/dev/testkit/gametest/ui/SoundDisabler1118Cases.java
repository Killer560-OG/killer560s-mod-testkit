package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.SoundProbe;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.resources.Identifier;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * sounddisabler-menu-1118 (killer560, 2026-11-18: "have that open a custom menu where you can search through every sound in
 * the game and change its volume individually as well as mute it outright ... a slider bar under them ... 0 percent to 200
 * percent"). Two client-only cases, both through the real widgets' own mouse handlers and the saved file:
 * 1118-ui-sounddisabler-tab (Volume Sliders shows and hides the preset sliders and keeps their values; the Custom Sounds
 * button opens the screen) and 1118-ui-sounddisabler-screen (every sound listed, "note block" narrows, a slider and Mute click
 * persist through save and load, Changed only, Reset all after a second click, Preview plays the sound at the row's volume).
 */
final class SoundDisabler1118Cases {

    private static final String CFG = "sounddisabler.SoundDisablerConfig";
    private static final String SCREEN = "sounddisabler.CustomSoundsScreen";
    private static final String HARP = "block.note_block.harp";

    private SoundDisabler1118Cases() {
    }

    private static Object tab() {
        for (Object t : MenuACases.leaves()) {
            if ("Sound Disabler".equals(MenuACases.name(t))) {
                return t;
            }
        }
        throw new AssertionError("no 'Sound Disabler' tab among the menu's leaves");
    }

    @SuppressWarnings("unchecked")
    private static List<AbstractWidget> build(boolean searchScan) {
        try {
            Object t = tab();
            if (searchScan) {
                return (List<AbstractWidget>) t.getClass().getMethod("searchScanWidgets").invoke(t);
            }
            Method m = t.getClass().getMethod("buildWidgets", int.class, int.class, int.class, Runnable.class);
            return (List<AbstractWidget>) m.invoke(t, 10, 10, 330, (Runnable) () -> { });
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not build the Sound Disabler tab: " + UiCase.describe(e), e);
        }
    }

    private static List<String> labels() {
        List<String> out = new ArrayList<>();
        for (AbstractWidget w : build(false)) {
            out.add(ModScreenDriver.label(w));
        }
        return out;
    }

    private static int volumeRows(List<String> labels) {
        int n = 0;
        for (String l : labels) {
            if (l.contains(" Volume: ")) {
                n++;
            }
        }
        return n;
    }

    private static AbstractWidget widget(String startsWith) {
        for (AbstractWidget w : build(false)) {
            if (ModScreenDriver.label(w).startsWith(startsWith)) {
                return w;
            }
        }
        throw new AssertionError("no widget labeled '" + startsWith + "' on the Sound Disabler tab");
    }

    private static void restore(UiCase c) {
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            Object cfg = Mod.cfg(CFG);
            Mod.call(cfg, "setEnabled", false);
            Mod.call(cfg, "setVolumeSliders", false);
            Mod.call(cfg, "clearSounds");
            Mod.call(cfg, "save");
            return null;
        });
    }

    static void tabCase(UiCase c) {
        try {
            c.onClient(mc -> {
                Object cfg = Mod.cfg(CFG);
                Mod.call(cfg, "setVolumeSliders", false);
                Mod.call(cfg, "save");
                return null;
            });
            List<String> off = c.onClient(mc -> labels());
            c.note("rows with Volume Sliders OFF: " + off);
            c.check(off.contains("Custom Sounds"), "no Custom Sounds button: " + off);
            c.check(off.stream().anyMatch(l -> l.startsWith("Volume Sliders")), "no Volume Sliders switch: " + off);
            c.check(volumeRows(off) == 0, "Volume Sliders OFF still shows " + volumeRows(off) + " slider(s)");
            int presets = java.lang.reflect.Array.getLength(Mod.staticCall("sounddisabler.MutedSound", "values"));

            // The real switch click turns the sliders on (the tab rebuilds through its callback).
            c.onClient(mc -> {
                ModScreenDriver.press(widget("Volume Sliders"));
                return null;
            });
            c.check(c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(CFG), "isVolumeSliders")), "the Volume Sliders switch did not turn on");
            List<String> on = c.onClient(mc -> labels());
            c.check(volumeRows(on) == presets, "Volume Sliders ON shows " + volumeRows(on) + " slider(s), wanted " + presets);
            c.check(on.stream().anyMatch(l -> l.equals("Bonzo's Staff Volume: 100%")), "no 'Bonzo's Staff Volume: 100%' row: " + on);

            // Drag the first preset slider to the middle: a step of 5 near 100%, saved, kept when the option goes off.
            int got = c.onClient(mc -> {
                AbstractWidget s = widget("Bonzo's Staff Volume");
                double x = s.getX() + s.getWidth() * 0.25;
                double y = s.getY() + s.getHeight() / 2.0;
                MouseButtonEvent ev = new MouseButtonEvent(x, y, new MouseButtonInfo(dev.testkit.compat.McKeys.mouse(0), 0));
                s.mouseClicked(ev, false);
                s.mouseReleased(ev);
                Mod.staticCall(CFG, "load");
                Object cfg = Mod.cfg(CFG);
                return (Integer) Mod.call(cfg, "getPresetPercent", Mod.staticCall("sounddisabler.MutedSound", "valueOf", "BONZO_STAFF"));
            });
            c.note("Bonzo's Staff slider clicked at 25% of its width: " + got + "%");
            c.check(got != 100 && got % 5 == 0 && got > 0 && got <= 100, "a click at 25% of the slider gave " + got + "%, wanted a step of 5 below 100");

            c.onClient(mc -> {
                ModScreenDriver.press(widget("Volume Sliders"));
                return null;
            });
            List<String> hidden = c.onClient(mc -> labels());
            c.check(volumeRows(hidden) == 0, "Volume Sliders back OFF still shows " + volumeRows(hidden) + " slider(s)");
            int kept = c.onClient(mc -> (Integer) Mod.call(Mod.cfg(CFG), "getPresetPercent",
                    Mod.staticCall("sounddisabler.MutedSound", "valueOf", "BONZO_STAFF")));
            c.check(kept == got, "the slider value " + got + "% was not kept while the option is off (" + kept + "%)");
            // A search finds the sliders even with the option off (the scan builds them).
            List<String> scan = c.onClient(mc -> {
                List<String> o = new ArrayList<>();
                for (AbstractWidget w : build(true)) {
                    o.add(ModScreenDriver.label(w));
                }
                return o;
            });
            c.check(volumeRows(scan) == presets, "the menu search scan shows " + volumeRows(scan) + " slider(s), wanted " + presets);

            // The Custom Sounds button opens the screen.
            c.onClient(mc -> {
                ModScreenDriver.press(widget("Custom Sounds"));
                return null;
            });
            c.ticks(3);
            String open = c.onClient(mc -> {
                Screen s = McCompat.screen(mc);
                return s == null ? "none" : s.getClass().getName();
            });
            c.check(open.endsWith(".CustomSoundsScreen"), "the Custom Sounds button opened " + open);
        } finally {
            restore(c);
        }
    }

    // ---- the screen ----------------------------------------------------------------------------------------------

    private static Object screen(UiCase c) {
        return c.onClient(mc -> McCompat.screen(mc));
    }

    @SuppressWarnings("unchecked")
    private static List<Identifier> listed(UiCase c) {
        return c.onClient(mc -> {
            try {
                return (List<Identifier>) Mod.call(McCompat.screen(mc), "listedIds");
            } catch (RuntimeException e) {
                throw new AssertionError("no listedIds on " + McCompat.screen(mc), e);
            }
        });
    }

    private static int priv(Object screen, String name) {
        try {
            Method m = screen.getClass().getDeclaredMethod(name);
            m.setAccessible(true);
            return (Integer) m.invoke(screen);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("no " + name + " on the screen", e);
        }
    }

    private static boolean click(Screen s, double x, double y) {
        MouseButtonEvent ev = new MouseButtonEvent(x, y, new MouseButtonInfo(dev.testkit.compat.McKeys.mouse(0), 0));
        boolean took = s.mouseClicked(ev, false);
        s.mouseReleased(ev);
        return took;
    }

    private static void search(UiCase c, String text) {
        c.onClient(mc -> {
            Object screen = McCompat.screen(mc);
            ((net.minecraft.client.gui.components.EditBox) R.get(screen, "search")).setValue(text);
            return null;
        });
        c.ticks(2);
    }

    private static void press(UiCase c, String labelStart) {
        c.onClient(mc -> {
            Screen s = McCompat.screen(mc);
            for (var child : s.children()) {
                if (child instanceof AbstractWidget w && ModScreenDriver.label(w).startsWith(labelStart)) {
                    ModScreenDriver.press(w);
                    return null;
                }
            }
            throw new AssertionError("no '" + labelStart + "' button on the Custom Sounds screen");
        });
        c.ticks(2);
    }

    @SuppressWarnings("unchecked")
    private static Object setting(Identifier id) {
        return Mod.call(Mod.cfg(CFG), "sound", id);
    }

    static void screenCase(UiCase c) {
        Identifier harp = Identifier.withDefaultNamespace(HARP);
        try {
            c.onClient(mc -> {
                Object cfg = Mod.cfg(CFG);
                Mod.call(cfg, "setEnabled", true);
                Mod.call(cfg, "clearSounds");
                ModScreenDriver.press(widget("Custom Sounds"));
                return null;
            });
            c.ticks(3);
            c.check(screen(c) != null && screen(c).getClass().getName().endsWith(".CustomSoundsScreen"), "the Custom Sounds screen did not open");

            List<Identifier> all = listed(c);
            c.note("sounds listed: " + all.size());
            c.check(all.size() > 1000, "only " + all.size() + " sounds listed: the screen should list every sound event (~1,700)");
            c.check(all.contains(harp), "the list has no " + harp);

            search(c, "note block");
            List<Identifier> narrowed = listed(c);
            c.note("'note block' lists " + narrowed.size() + " sounds, e.g. " + (narrowed.isEmpty() ? "-" : narrowed.get(0)));
            c.check(narrowed.size() >= 10 && narrowed.size() < all.size() / 10, "'note block' listed " + narrowed.size() + " of " + all.size());
            c.check(narrowed.stream().allMatch(i -> i.getPath().startsWith("block.note_block.")), "'note block' listed a non-note-block sound: " + narrowed);
            search(c, "block.note_block.harp");
            c.check(listed(c).contains(harp) && listed(c).size() <= 3, "'block.note_block.harp' (dots) listed " + listed(c));
            search(c, "xyzzy nosuchsound");
            c.check(listed(c).isEmpty(), "a nonsense search still lists " + listed(c).size());

            // Row 0 of "note block harp": slider at 75% (150%), Mute.
            search(c, "note block harp");
            c.check(listed(c).get(0).equals(harp), "row 0 is " + listed(c).get(0) + ", wanted " + harp);
            int[] p = c.onClient(mc -> {
                Screen s = McCompat.screen(mc);
                int[] b = (int[]) Mod.call(s, "themedPanelBounds");
                return new int[]{b[1] + 70 + 10, priv(s, "sliderX"), priv(s, "muteX"), priv(s, "playX")};
            });
            int sliderW = 96;
            c.onClient(mc -> click(McCompat.screen(mc), p[1] + sliderW * 0.75, p[0]));
            Object s1 = c.onClient(mc -> setting(harp));
            int pct = c.onClient(mc -> s1 == null ? 100 : (Integer) Mod.call(s1, "percent"));
            c.note("slider clicked at 75%: " + pct + "%");
            c.check(pct == 150, "a click at 75% of the slider gave " + pct + "%, wanted 150");
            c.onClient(mc -> click(McCompat.screen(mc), p[2] + 5, p[0]));
            c.onClient(mc -> {
                Mod.staticCall(CFG, "load");
                return null;
            });
            Object s2 = c.onClient(mc -> setting(harp));
            boolean muted = c.onClient(mc -> s2 != null && (Boolean) Mod.call(s2, "muted"));
            int pct2 = c.onClient(mc -> s2 == null ? 100 : (Integer) Mod.call(s2, "percent"));
            c.check(muted && pct2 == 150, "after save and load the harp is muted=" + muted + " at " + pct2 + "%, wanted muted at 150%");

            // Preview plays at the row's volume (two copies at 150%) whatever the mute says.
            int before = SoundProbe.count("minecraft:" + HARP);
            c.onClient(mc -> click(McCompat.screen(mc), p[3] + 5, p[0]));
            c.ticks(6);
            int played = SoundProbe.count("minecraft:" + HARP) - before;
            c.check(played == 2, "Preview at 150% reached the engine " + played + " time(s), wanted 2 (a full copy and a half copy)");

            // Changed only lists just the changed sound; Reset all needs a second click.
            search(c, "");
            press(c, "Changed only");
            List<Identifier> changed = listed(c);
            c.check(changed.size() == 1 && changed.get(0).equals(harp), "Changed only lists " + changed + ", wanted just the harp");
            press(c, "Reset all");
            int still = c.onClient(mc -> (Integer) Mod.call(Mod.cfg(CFG), "soundCount"));
            c.check(still == 1, "the first Reset all click cleared the list (" + still + " left): it needs a confirming second click");
            press(c, "Click again");
            int left = c.onClient(mc -> (Integer) Mod.call(Mod.cfg(CFG), "soundCount"));
            c.check(left == 0, "Reset all after the second click left " + left + " changed sound(s)");
            c.check(listed(c).isEmpty(), "Changed only still lists " + listed(c) + " after Reset all");
        } finally {
            restore(c);
        }
    }
}
