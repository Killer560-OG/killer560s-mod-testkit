package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.ModUnderTest;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.gametest.mod.Quiet;
import dev.testkit.harness.Report;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * killer560's 2026-10-21 Theme Extras (mod themes-1021, {@code menuthemes/}): each menu theme's own music, font, and
 * imported video/GIF main menu background and music.
 * <ul>
 *   <li>1021-theme-music: a fresh config has Theme Music ON (killer560, 2026-10-22) and every theme on Theme Track,
 *       Keep Current and Theme Scene, each preset's Theme Track its own loop (Viridian the green one), and the title
 *       screen plays Amber's loop from the defaults alone; a file that saved Theme Music OFF loads OFF, and one that never
 *       saved it loads ON; with the switch off nothing plays on the title screen (control); on, the title screen hands
 *       the sound engine the
 *       menu theme's bundled loop (a live engine channel, still live 40 ticks later, so the stream did not fail), at the
 *       slider's volume, vanilla menu music is not playing; another theme switches the track, Off for that theme stops it,
 *       a loading screen stops it and the title screen starts it again; the rows are in the Themes drawer and a click on
 *       Music steps it; the settings survive a reload.</li>
 *   <li>1021-theme-font: with every theme on Keep Current the built font is the Font setting's (Minecraft's); a theme
 *       font for Amber builds Lato and changes the mod menu's text widths; Dark (Keep Current) goes back to Minecraft's
 *       widths exactly; Dark's own font builds VT323; the Theme Font row steps; it survives a reload.</li>
 *   <li>1021-theme-bg-import: a solid magenta GIF (made by ffmpeg, or ImageIO without it) imported through the clipboard
 *       path is copied to the backgrounds folder, becomes Amber's background, is decoded off the render thread and DRAWN
 *       (draw counter, uploads, and the title screen's own pixels turn magenta); a test-pattern video is turned into
 *       frames by ffmpeg and plays (the shown frame advances); another theme with none keeps its scene (no draws, its
 *       pixels not magenta); a broken file is refused without an ERROR line.</li>
 *   <li>1021-theme-sound-import: an .ogg is imported as it is and an .mp3 converted to .ogg (ffmpeg); as Amber's music
 *       the engine opens the imported file through the mod's stream hook and keeps it playing; a file that is not sound
 *       is refused; other themes keep Theme Track.</li>
 *   <li>1022-theme-preview: Play Preview under the Music row starts the edited theme's track in the click itself (the
 *       engine holds a live, non-looping channel for it before any tick passes), at Music Volume, while the main menu's
 *       own track and vanilla's menu music hold off; the row then reads Stop Preview and a second click ends the channel;
 *       closing the mod menu ends it too; with the theme's Music set to Off the button cannot be pressed.</li>
 * </ul>
 * ffmpeg is needed for the video and mp3 halves; without it on the PATH those parts are noted and skipped, never passed.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class ThemeExtrasSuite implements FabricClientGameTest {

    static final String[] CASES = {"1021-theme-music", "1021-theme-font", "1021-theme-bg-import", "1021-theme-sound-import",
            "1022-theme-preview"};

    static final String CFG = "menuthemes.ThemeExtrasConfig";
    static final String MUSIC = "menuthemes.ThemeMusic";
    static final String MUSIC_FILES = "menuthemes.ThemeMusicFiles";
    static final String BG = "menuthemes.ThemeBackgrounds";
    static final String BG_PLAYER = "menuthemes.ThemeBackgroundPlayer";
    static final String THEMES = "gui.MenuTheme";
    static final String[] ALL_THEMES = {"AMBER", "DARK", "LIGHT", "OCEAN", "GREEN", "CUSTOM"};

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
        UiCase.run(ctx, "1021-theme-music", ThemeExtrasSuite::music);
        UiCase.run(ctx, "1021-theme-font", ThemeExtrasSuite::font);
        UiCase.run(ctx, "1021-theme-bg-import", ThemeExtrasSuite::backgroundImport);
        UiCase.run(ctx, "1021-theme-sound-import", ThemeExtrasSuite::soundImport);
        UiCase.run(ctx, "1022-theme-preview", ThemeExtrasSuite::preview);
    }

    // =================================================================================================================
    // shared
    // =================================================================================================================

    static Object theme(String name) {
        return Mod.enumValue(THEMES, name);
    }

    static Object cfg() {
        return Mod.cfg(CFG);
    }

    /** The saved file and the menu / main menu settings a case changes, put back after it. */
    record Saved(byte[] file, Object menuTheme, Object mmStyle, boolean mmEnabled) {
    }

    static Saved save(UiCase c) throws Exception {
        Path file = c.onClient(mc -> (Path) Mod.staticCall(CFG, "path"));
        byte[] bytes = Files.exists(file) ? Files.readAllBytes(file) : null;
        return c.onClient(mc -> {
            Object mm = Mod.cfg("mainmenu.MainMenuThemeConfig");
            Object s = new Saved(bytes, Mod.call(Mod.cfg("gui.MenuConfig"), "getTheme"), Mod.call(mm, "getStyle"),
                    (Boolean) Mod.call(mm, "isEnabled"));
            // The main menu shows the mod menu's theme (Match), themed.
            Mod.call(mm, "setStyle", Mod.enumValue("mainmenu.MainMenuThemeConfig$Style", "MATCH"));
            Mod.call(mm, "setEnabled", true);
            return (Saved) s;
        });
    }

    static void restore(UiCase c, Saved s) throws Exception {
        Path file = c.onClient(mc -> (Path) Mod.staticCall(CFG, "path"));
        if (s.file() == null) {
            Files.deleteIfExists(file);
        } else {
            Files.write(file, s.file());
        }
        c.onClient(mc -> {
            Mod.staticCall(CFG, "load");
            Mod.call(Mod.cfg("gui.MenuConfig"), "setTheme", s.menuTheme());
            Object mm = Mod.cfg("mainmenu.MainMenuThemeConfig");
            Mod.call(mm, "setStyle", s.mmStyle());
            Mod.call(mm, "setEnabled", s.mmEnabled());
            McCompat.setScreen(mc, new TitleScreen());
            return null;
        });
        c.ticks(3);
    }

    /** Every theme still has its per-theme defaults: its own track, the current font, its own scene. (The Theme Music
     *  switch is not checked here: it is ON by default since 2026-10-22 and a saved OFF is kept, so the test client's
     *  file decides it; 1021-theme-music checks the default on a fresh file.) */
    static String defaultsWhy(Object cfg) {
        for (String t : ALL_THEMES) {
            Object th = theme(t);
            String m = (String) Mod.call(cfg, "getMusic", th);
            String f = (String) Mod.call(cfg, "getFont", th);
            String b = (String) Mod.call(cfg, "getBackground", th);
            if (!"theme".equals(m) || !"keep".equals(f) || !"none".equals(b)) {
                return t + " does not start on the defaults: music " + m + ", font " + f + ", background " + b;
            }
        }
        return null;
    }

    static void setMenuTheme(Minecraft mc, String t) {
        Mod.call(Mod.cfg("gui.MenuConfig"), "setTheme", theme(t));
    }

    static void title(UiCase c) {
        c.onClient(mc -> {
            McCompat.setScreen(mc, new TitleScreen());
            return null;
        });
    }

    /** Whether ffmpeg runs here (the import's video and mp3 halves need it). */
    static boolean ffmpeg() {
        try {
            Process p = new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    static void run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new AssertionError("command failed: " + String.join(" ", cmd) + "\n" + out);
        }
    }

    /** Runs one import (a static {@code importAsync(Path, Consumer)} or {@code importClipboardAsync(String, Consumer)}) and
     *  waits, bounded, for its answer: the new id, or null when refused. */
    static String importAndWait(UiCase c, String cls, String method, Object arg) {
        AtomicReference<String> result = new AtomicReference<>();
        boolean[] done = {false};
        Consumer<String> cb = id -> {
            synchronized (done) {
                result.set(id);
                done[0] = true;
            }
        };
        c.onClient(mc -> Mod.staticCall(cls, method, arg, cb));
        for (int i = 0; i < 1200; i++) {
            synchronized (done) {
                if (done[0]) {
                    return result.get();
                }
            }
            c.ticks(1);
        }
        throw new AssertionError(cls + "." + method + " never answered (60 s)");
    }

    static AbstractWidget row(Screen s, String prefix) {
        return FontsMainMenuSuite.row(s, prefix);
    }

    // =================================================================================================================
    // 1021-theme-music
    // =================================================================================================================

    static void music(UiCase c) throws Exception {
        Saved saved = save(c);
        try {
            // A fresh config (no file): Theme Music ON, every theme on its own track.
            Path file = c.onClient(mc -> (Path) Mod.staticCall(CFG, "path"));
            Files.deleteIfExists(file);
            String fresh = c.onClient(mc -> {
                Mod.staticCall(CFG, "load");
                Object cfg = cfg();
                if (!(Boolean) Mod.call(cfg, "isMusicOn")) {
                    return "a fresh config has Theme Music OFF (wanted ON by default)";
                }
                if ((Integer) Mod.call(cfg, "getMusicVolume") != 50) {
                    return "a fresh config's Music Volume is " + Mod.call(cfg, "getMusicVolume") + ", not 50";
                }
                String[][] own = {{"AMBER", "amber"}, {"DARK", "dark"}, {"LIGHT", "light"}, {"OCEAN", "ocean"}, {"GREEN", "green"}};
                for (String[] t : own) {
                    Object b = Mod.staticCall(MUSIC, "themeTrack", theme(t[0]));
                    String ev = b == null ? null : String.valueOf(Mod.call(b, "event"));
                    if (!("killer560smod:theme_music_" + t[1]).equals(ev)) {
                        return t[0] + "'s own track is " + ev;
                    }
                }
                if (Mod.staticCall(MUSIC, "themeTrack", theme("CUSTOM")) != null) {
                    return "Custom has a track of its own";
                }
                return defaultsWhy(cfg);
            });
            c.check(fresh == null, fresh);
            c.onClient(mc -> {
                setMenuTheme(mc, "AMBER");
                return null;
            });
            title(c);
            c.ticks(5);
            Object[] byDefault = c.onClient(mc -> new Object[]{Mod.staticCall(MUSIC, "currentKey"),
                    Mod.staticCall(MUSIC, "engineActive")});
            c.note("fresh config on the title screen: playing " + byDefault[0] + ", engine active " + byDefault[1]);
            c.check("killer560smod:theme_music_amber".equals(byDefault[0]) && Boolean.TRUE.equals(byDefault[1]),
                    "with the defaults alone the title screen played " + byDefault[0] + " (active " + byDefault[1] + ")");

            // A file that saved OFF keeps OFF; one that never saved the switch gets the new default.
            Files.writeString(file, "{\"musicOn\": false, \"musicVolume\": 30}");
            Object[] savedOff = c.onClient(mc -> {
                Mod.staticCall(CFG, "load");
                return new Object[]{Mod.call(cfg(), "isMusicOn"), Mod.call(cfg(), "getMusicVolume")};
            });
            Files.writeString(file, "{\"musicVolume\": 30}");
            boolean unsaved = c.onClient(mc -> {
                Mod.staticCall(CFG, "load");
                return (Boolean) Mod.call(cfg(), "isMusicOn");
            });
            c.note("saved OFF loads " + savedOff[0] + " (volume " + savedOff[1] + "); no saved switch loads " + unsaved);
            c.check(Boolean.FALSE.equals(savedOff[0]) && Integer.valueOf(30).equals(savedOff[1]),
                    "a file that saved Theme Music OFF loaded " + savedOff[0] + " at " + savedOff[1] + "%");
            c.check(unsaved, "a file with no musicOn loaded Theme Music OFF");
            Files.deleteIfExists(file);

            // Control: the switch off, the title screen up, nothing starts.
            c.onClient(mc -> {
                Mod.staticCall(CFG, "load");
                Mod.call(cfg(), "setMusicOn", false);
                return null;
            });
            c.ticks(3);
            int starts0 = c.onClient(mc -> (Integer) Mod.staticCall(MUSIC, "starts"));
            title(c);
            c.ticks(5);
            Object[] off = c.onClient(mc -> new Object[]{Mod.staticCall(MUSIC, "currentKey"), Mod.staticCall(MUSIC, "starts")});
            c.note("switch off: playing " + off[0] + ", starts " + starts0 + " -> " + off[1]);
            c.check(off[0] == null && (Integer) off[1] == starts0, "Theme Music OFF still started " + off[0]);

            // On: Amber's own loop reaches the engine.
            c.onClient(mc -> {
                Object cfg = cfg();
                Mod.call(cfg, "setMusicOn", true);
                Mod.call(cfg, "setMusicVolume", 40);
                return null;
            });
            c.ticks(4);
            Object[] on = c.onClient(mc -> new Object[]{Mod.staticCall(MUSIC, "currentKey"),
                    Mod.staticCall(MUSIC, "engineActive"), Mod.staticCall(MUSIC, "lastResult"),
                    Mod.staticCall(MUSIC, "currentVolume"),
                    mc.getSoundManager().getSoundEvent(Identifier.fromNamespaceAndPath("killer560smod", "theme_music_amber")) != null,
                    mc.getMusicManager().getCurrentMusicTranslationKey()});
            c.note("Amber on: playing " + on[0] + ", engine active " + on[1] + ", result " + on[2] + ", volume " + on[3]
                    + ", event loaded " + on[4] + ", vanilla music " + on[5]);
            c.check("killer560smod:theme_music_amber".equals(on[0]), "the title screen played " + on[0] + ", not Amber's loop");
            c.check((Boolean) on[4], "the jar's sounds.json has no theme_music_amber event");
            c.check(Boolean.TRUE.equals(on[1]), "the sound engine holds no channel for Amber's loop (result " + on[2] + ")");
            c.check(String.valueOf(on[2]).startsWith("STARTED"), "the engine answered " + on[2]);
            c.check(Math.abs((Float) on[3] - 0.4f) < 0.001f, "the track's volume is " + on[3] + ", the slider says 40%");
            c.check(on[5] == null, "vanilla menu music is playing beside it: " + on[5]);
            c.ticks(40);
            boolean still = c.onClient(mc -> (Boolean) Mod.staticCall(MUSIC, "engineActive"));
            c.check(still, "Amber's loop stopped within 40 ticks (the stream failed?) " + Mod.staticCall(MUSIC, "lastError"));

            // Volume follows the slider live.
            c.onClient(mc -> {
                Mod.call(cfg(), "setMusicVolume", 75);
                return null;
            });
            c.ticks(45); // the menu track ramps in over 40 ticks (ThemeMusicInstance), then holds the slider's volume
            float vol = c.onClient(mc -> (Float) Mod.staticCall(MUSIC, "currentVolume"));
            c.check(Math.abs(vol - 0.75f) < 0.001f, "after the slider moved to 75% the track plays at " + vol);

            // Another theme: its own loop.
            int stops0 = c.onClient(mc -> (Integer) Mod.staticCall(MUSIC, "stops"));
            c.onClient(mc -> {
                setMenuTheme(mc, "OCEAN");
                return null;
            });
            c.ticks(4);
            Object[] ocean = c.onClient(mc -> new Object[]{Mod.staticCall(MUSIC, "currentKey"), Mod.staticCall(MUSIC, "stops"),
                    Mod.staticCall(MUSIC, "engineActive")});
            c.note("Ocean: " + ocean[0] + ", stops " + stops0 + " -> " + ocean[1]);
            c.check("killer560smod:theme_music_ocean".equals(ocean[0]) && Boolean.TRUE.equals(ocean[2]),
                    "switching to Ocean played " + ocean[0] + " (active " + ocean[2] + ")");
            c.check((Integer) ocean[1] > stops0, "Amber's loop was not stopped when the theme changed");

            // Off for Ocean: nothing.
            c.onClient(mc -> {
                Mod.call(cfg(), "setMusic", theme("OCEAN"), "none");
                return null;
            });
            c.ticks(3);
            Object offOcean = c.onClient(mc -> Mod.staticCall(MUSIC, "currentKey"));
            c.check(offOcean == null, "Music: Off for Ocean still plays " + offOcean);
            c.onClient(mc -> {
                Mod.call(cfg(), "setMusic", theme("OCEAN"), "theme");
                return null;
            });
            c.ticks(3);

            // A loading screen ends it; the title screen starts it again.
            c.onClient(mc -> {
                McCompat.setScreen(mc, new GenericMessageScreen(Component.literal("Testkit loading")));
                return null;
            });
            c.ticks(3);
            Object loading = c.onClient(mc -> Mod.staticCall(MUSIC, "currentKey"));
            c.check(loading == null, "a loading screen kept the music playing: " + loading);
            title(c);
            c.ticks(3);
            Object back = c.onClient(mc -> Mod.staticCall(MUSIC, "currentKey"));
            c.check("killer560smod:theme_music_ocean".equals(back), "back on the title screen it played " + back);

            // The drawer's rows, a real click on Music, and a reload.
            String why = c.onClient(mc -> {
                Screen s = FontsMainMenuSuite.themesDrawer(mc);
                Mod.staticCall("menuthemes.ThemeExtrasSettings", "setEditing", theme("AMBER"));
                s = FontsMainMenuSuite.themesDrawer(mc);
                for (String want : new String[]{"Extras For: Amber", "Theme Music: ON", "Music: Theme Track (Amber Loop)",
                        "Music Volume: 75%", "Import Music", "Open Music Folder"}) {
                    if (row(s, want) == null) {
                        return "no '" + want + "' row in the Themes drawer: " + FontsMainMenuSuite.labels(s);
                    }
                }
                ModScreenDriver.press(row(s, "Music: "));
                String now = (String) Mod.call(cfg(), "getMusic", theme("AMBER"));
                if (!"none".equals(now)) {
                    return "a click on Music set Amber's music to " + now + ", expected the next choice, Off";
                }
                Mod.call(cfg(), "save");
                Mod.staticCall(CFG, "load");
                Object re = cfg();
                if (!"none".equals(Mod.call(re, "getMusic", theme("AMBER"))) || !(Boolean) Mod.call(re, "isMusicOn")
                        || (Integer) Mod.call(re, "getMusicVolume") != 75) {
                    return "the music settings did not survive a reload";
                }
                Mod.staticCall("menuthemes.ThemeExtrasSettings", "setEditing", (Object) null);
                return null;
            });
            c.check(why == null, why);
        } finally {
            restore(c, saved);
        }
        // After restoring the client's own file: with its switch OFF nothing plays; ON (the default), its theme's track.
        Object[] after = c.onClient(mc -> new Object[]{Mod.call(cfg(), "isMusicOn"), Mod.staticCall(MUSIC, "currentKey")});
        c.note("after restoring: Theme Music " + after[0] + ", playing " + after[1]);
        if (!(Boolean) after[0]) {
            c.check(after[1] == null, "after restoring (Theme Music OFF) the title screen still plays " + after[1]);
        }
    }

    // =================================================================================================================
    // 1022-theme-preview
    // =================================================================================================================

    /** The sound engine's channel handle for {@code inst} (SoundEngine.instanceToChannel), or null. */
    static Object channelHandle(Minecraft mc, Object inst) {
        Object engine = Mod.field(mc.getSoundManager(), "soundEngine");
        return ((Map<?, ?>) Mod.field(engine, "instanceToChannel")).get(inst);
    }

    /** ChannelAccess.ChannelHandle.isStopped: set when the sound thread finds the OpenAL source stopped and releases it. */
    static boolean channelReleased(Object handle) {
        return (Boolean) Mod.call(handle, "isStopped");
    }

    /** Ticks until {@code handle}'s channel is released, polled one tick at a time; 11 when it is not within 10. */
    static int ticksUntilReleased(UiCase c, Object handle) {
        for (int t = 0; t <= 10; t++) {
            if (c.onClient(mc -> channelReleased(handle))) {
                return t;
            }
            c.ticks(1);
        }
        return 11;
    }

    static void preview(UiCase c) throws Exception {
        Saved saved = save(c);
        try {
            // Amber on the main menu with its own loop playing; Ocean is the theme being set up.
            c.onClient(mc -> {
                setMenuTheme(mc, "AMBER");
                Object cfg = cfg();
                Mod.call(cfg, "setMusicOn", true);
                Mod.call(cfg, "setMusicVolume", 35);
                Mod.call(cfg, "setMusic", theme("AMBER"), "theme");
                Mod.call(cfg, "setMusic", theme("OCEAN"), "theme");
                return null;
            });
            title(c);
            c.ticks(5);
            String menuTrack = c.onClient(mc -> (String) Mod.staticCall(MUSIC, "currentKey"));
            c.check("killer560smod:theme_music_amber".equals(menuTrack), "the title screen plays " + menuTrack
                    + " before the preview");

            int starts0 = c.onClient(mc -> (Integer) Mod.staticCall(MUSIC, "previewStarts"));
            Object[] st = c.onClient(mc -> {
                Mod.staticCall("menuthemes.ThemeExtrasSettings", "setEditing", theme("OCEAN"));
                Screen s = FontsMainMenuSuite.themesDrawer(mc);
                AbstractWidget b = row(s, "Play Preview: Ocean Loop");
                if (b == null) {
                    return new Object[]{"no 'Play Preview: Ocean Loop' row in the Themes drawer: " + FontsMainMenuSuite.labels(s)};
                }
                // It sits right under the Music row.
                AbstractWidget music = row(s, "Music: ");
                if (music == null || b.getY() <= music.getY() || b.getY() - music.getY() > 30) {
                    return new Object[]{"Play Preview is not right under the Music row (Music "
                            + (music == null ? "missing" : music.getY()) + ", preview " + b.getY() + ")"};
                }
                ModScreenDriver.press(b);
                // In the same call, no tick passed: the engine already holds it.
                Object inst = Mod.field(Mod.ROOT + MUSIC, "preview");
                AbstractWidget now = row(s, "Stop Preview");
                return new Object[]{null, Mod.staticCall(MUSIC, "previewKey"), Mod.staticCall(MUSIC, "previewEngineActive"),
                        Mod.staticCall(MUSIC, "lastPreviewResult"), Mod.staticCall(MUSIC, "previewLooping"),
                        inst == null ? -1f : Mod.call(inst, "rawVolume"), Mod.staticCall(MUSIC, "currentKey"),
                        ModScreenDriver.label(now == null ? b : now), inst};
            });
            c.check(st[0] == null, String.valueOf(st[0]));
            if (st[0] != null) {
                return;
            }
            Object inst = st[8];
            c.note("pressed Play Preview: key " + st[1] + ", engine active " + st[2] + ", result " + st[3] + ", looping "
                    + st[4] + ", volume " + st[5] + ", menu track " + st[6] + ", row now '" + st[7] + "'");
            c.check("killer560smod:theme_music_ocean".equals(st[1]), "Play Preview played " + st[1] + ", not Ocean's loop");
            c.check(Boolean.TRUE.equals(st[2]), "the sound engine holds no channel for the preview right after the click ("
                    + st[3] + ")");
            c.check(String.valueOf(st[3]).startsWith("STARTED"), "the engine answered " + st[3]);
            c.check(Boolean.FALSE.equals(st[4]), "the preview loops; it should play once");
            c.check(st[5] instanceof Float f && Math.abs(f - 0.35f) < 0.001f, "the preview's volume is " + st[5]
                    + ", Music Volume says 35%");
            c.check(st[6] == null, "the menu's own track kept playing beside the preview: " + st[6]);
            c.check("Stop Preview".equals(st[7]), "after the click the row reads '" + st[7] + "'");
            int starts1 = c.onClient(mc -> (Integer) Mod.staticCall(MUSIC, "previewStarts"));
            c.check(starts1 == starts0 + 1, "previewStarts went " + starts0 + " -> " + starts1);

            // Still playing 20 ticks on (the stream did not fail, and nothing stopped it on a timer); no vanilla music.
            c.ticks(20);
            Object[] later = c.onClient(mc -> new Object[]{Mod.staticCall(MUSIC, "previewEngineActive"),
                    Mod.staticCall(MUSIC, "currentKey"), mc.getMusicManager().getCurrentMusicTranslationKey()});
            c.check(Boolean.TRUE.equals(later[0]), "the preview stopped by itself within 20 ticks");
            c.check(later[1] == null && later[2] == null, "music beside the preview: theme " + later[1] + ", vanilla "
                    + later[2]);

            // The second click stops it: the OpenAL channel the click started is released. (SoundEngine.isActive is not the
            // test here: the engine keeps a stopped instance in its map until 20 ticks after it started, javap 26.1.2
            // tickNonPaused/soundDeleteTime, so it reads true for a channel that is already silent.)
            Object handle = c.onClient(mc -> channelHandle(mc, inst));
            c.check(handle != null && !channelReleased(handle), "the preview's channel is not live before the second click");
            Object[] stop = c.onClient(mc -> {
                Screen s = McCompat.screen(mc);
                AbstractWidget b = row(s, "Stop Preview");
                if (b == null) {
                    return new Object[]{"no 'Stop Preview' row: " + FontsMainMenuSuite.labels(s)};
                }
                ModScreenDriver.press(b);
                return new Object[]{null, Mod.staticCall(MUSIC, "previewing"), ModScreenDriver.label(b)};
            });
            c.check(stop[0] == null, String.valueOf(stop[0]));
            int stopTicks = ticksUntilReleased(c, handle);
            c.note("second click: previewing " + stop[1] + ", channel released after " + stopTicks + " tick(s), row '"
                    + stop[2] + "'");
            c.check(Boolean.FALSE.equals(stop[1]), "the second click did not stop the preview");
            c.check(stopTicks <= 10, "the preview's channel was still playing 10 ticks after the second click");
            c.check(String.valueOf(stop[2]).startsWith("Play Preview: Ocean Loop"), "after stopping the row reads " + stop[2]);

            // Closing the mod menu stops one too.
            Object inst2 = c.onClient(mc -> {
                AbstractWidget b = row(McCompat.screen(mc), "Play Preview: ");
                if (b != null) {
                    ModScreenDriver.press(b);
                }
                return Mod.field(Mod.ROOT + MUSIC, "preview");
            });
            c.check(inst2 != null, "the third click started nothing");
            if (inst2 == null) {
                return;
            }
            // A human closes the menu some ticks after a click; by then the stream is attached and playing.
            c.ticks(10);
            Object handle2 = c.onClient(mc -> channelHandle(mc, inst2));
            c.check(handle2 != null && !channelReleased(handle2), "the third click's channel is not live 10 ticks on");
            title(c);
            c.ticks(1);
            boolean flagOff = c.onClient(mc -> !(Boolean) Mod.staticCall(MUSIC, "previewing"));
            int closeTicks = 1 + (handle2 == null ? 0 : ticksUntilReleased(c, handle2));
            String menuTrack2 = c.onClient(mc -> (String) Mod.staticCall(MUSIC, "currentKey"));
            c.note("menu closed: preview off after 1 tick " + flagOff + ", channel released after " + closeTicks
                    + " tick(s), menu track " + menuTrack2);
            c.check(flagOff, "the preview was still on a tick after the menu closed");
            c.check(closeTicks <= 11, "the preview's channel was still playing 10 ticks after the menu closed");
            c.check("killer560smod:theme_music_amber".equals(menuTrack2), "after the preview the title screen plays " + menuTrack2);

            // Music: Off for Ocean: nothing to preview, the button cannot be pressed.
            String off = c.onClient(mc -> {
                Mod.call(cfg(), "setMusic", theme("OCEAN"), "none");
                Screen s = FontsMainMenuSuite.themesDrawer(mc);
                AbstractWidget b = row(s, "Play Preview");
                if (b == null) {
                    return "no Play Preview row with Ocean on Off: " + FontsMainMenuSuite.labels(s);
                }
                return b.active ? "Play Preview can be pressed with Ocean's music Off ('" + ModScreenDriver.label(b) + "')" : null;
            });
            c.check(off == null, off);
        } finally {
            c.onClient(mc -> {
                Mod.staticCall(MUSIC, "stopPreview");
                Mod.staticCall("menuthemes.ThemeExtrasSettings", "setEditing", (Object) null);
                return null;
            });
            restore(c, saved);
        }
    }

    // =================================================================================================================
    // 1021-theme-font
    // =================================================================================================================

    static void font(UiCase c) throws Exception {
        Saved saved = save(c);
        Object[] savedFont = FontsMainMenuSuite.saveFont(c);
        try {
            String defaults = c.onClient(mc -> defaultsWhy(cfg()));
            c.check(defaults == null, defaults);
            c.onClient(mc -> {
                setMenuTheme(mc, "AMBER");
                return null;
            });
            FontsMainMenuSuite.useFont(c, "minecraft", "MENU", 100);
            Map<String, Integer> base = c.onClient(mc -> FontsMainMenuSuite.screenTexts(mc, FontsMainMenuSuite.themesDrawer(mc)));
            // Dark's own drawer in Minecraft's font, the control for Dark's Keep Current below (each theme draws its own
            // labels and text shadow, so Amber's widths are not Dark's).
            c.onClient(mc -> {
                setMenuTheme(mc, "DARK");
                Mod.staticCall("fonts.CustomFonts", "refresh", mc);
                return null;
            });
            c.ticks(2);
            Map<String, Integer> baseDark = c.onClient(mc -> FontsMainMenuSuite.screenTexts(mc, FontsMainMenuSuite.themesDrawer(mc)));
            c.onClient(mc -> {
                setMenuTheme(mc, "AMBER");
                Mod.staticCall("fonts.CustomFonts", "refresh", mc);
                return null;
            });
            c.ticks(2);

            int builds0 = c.onClient(mc -> (Integer) Mod.staticCall("fonts.CustomFonts", "builds"));
            String amber = refreshTo(c, "AMBER", "bundled:lato");
            Map<String, Integer> lato = c.onClient(mc -> FontsMainMenuSuite.screenTexts(mc, FontsMainMenuSuite.themesDrawer(mc)));
            int[] d = FontsMainMenuSuite.diff(base, lato);
            int builds1 = c.onClient(mc -> (Integer) Mod.staticCall("fonts.CustomFonts", "builds"));
            c.note("Amber's theme font: built " + amber + " (builds " + builds0 + " -> " + builds1 + "), menu widths changed "
                    + d[0] + "/" + d[1]);
            c.check("bundled:lato".equals(amber), "Amber's theme font Lato was not built: " + amber);
            c.check(builds1 > builds0, "no font set was built for the theme font");
            c.check(d[1] >= 10 && d[0] >= d[1] * 0.8, "the mod menu kept Minecraft's widths under Amber's Lato: "
                    + d[0] + "/" + d[1]);
            c.check("minecraft".equals(c.onClient(mc -> Mod.call(Mod.cfg("fonts.FontConfig"), "getFont"))),
                    "the theme font changed the Font setting itself");

            // Dark keeps the current font: Minecraft's, every width as before.
            String dark = refreshTo(c, "DARK", null);
            Map<String, Integer> darkTexts = c.onClient(mc -> FontsMainMenuSuite.screenTexts(mc, FontsMainMenuSuite.themesDrawer(mc)));
            int[] back = FontsMainMenuSuite.diff(baseDark, darkTexts);
            c.check("minecraft".equals(dark), "Dark (Keep Current) built " + dark);
            c.check(back[0] == 0 && back[1] >= 10, "Dark (Keep Current) did not restore Minecraft's widths: " + back[0]
                    + "/" + back[1] + " changed");

            // Dark's own font.
            String vt = refreshTo(c, "DARK", "bundled:vt323");
            c.check("bundled:vt323".equals(vt), "Dark's theme font VT323 built " + vt);

            // The row steps (Keep Current -> Minecraft) and the setting survives a reload.
            String why = c.onClient(mc -> {
                Mod.call(cfg(), "setFont", theme("LIGHT"), "keep");
                Mod.staticCall("menuthemes.ThemeExtrasSettings", "setEditing", theme("LIGHT"));
                Screen s = FontsMainMenuSuite.themesDrawer(mc);
                AbstractWidget r = row(s, "Theme Font: Keep Current");
                if (r == null) {
                    return "no 'Theme Font: Keep Current' row for Light: " + FontsMainMenuSuite.labels(s);
                }
                ModScreenDriver.press(r);
                String now = (String) Mod.call(cfg(), "getFont", theme("LIGHT"));
                Mod.staticCall("menuthemes.ThemeExtrasSettings", "setEditing", (Object) null);
                if (!"minecraft".equals(now)) {
                    return "a click on Theme Font set " + now + ", expected Minecraft";
                }
                Mod.call(cfg(), "save");
                Mod.staticCall(CFG, "load");
                if (!"bundled:lato".equals(Mod.call(cfg(), "getFont", theme("AMBER")))
                        || !"bundled:vt323".equals(Mod.call(cfg(), "getFont", theme("DARK")))) {
                    return "the theme fonts did not survive a reload";
                }
                return null;
            });
            c.check(why == null, why);
        } finally {
            restore(c, saved);
            FontsMainMenuSuite.restoreFont(c, savedFont);
        }
        String built = c.onClient(mc -> {
            Mod.staticCall("fonts.CustomFonts", "refresh", mc);
            return (String) Mod.staticCall("fonts.CustomFonts", "builtId");
        });
        c.check("minecraft".equals(built) || built.equals(savedFont[0]), "after restoring, the built font is " + built);
    }

    /** Sets {@code theme}'s font (null: leave it) and makes it the menu theme; returns the font then built. */
    static String refreshTo(UiCase c, String theme, String font) {
        c.onClient(mc -> {
            if (font != null) {
                Mod.call(cfg(), "setFont", theme(theme), font);
            }
            setMenuTheme(mc, theme);
            Mod.staticCall("fonts.CustomFonts", "refresh", mc);
            return null;
        });
        c.ticks(2);
        return c.onClient(mc -> (String) Mod.staticCall("fonts.CustomFonts", "builtId"));
    }

    // =================================================================================================================
    // 1021-theme-bg-import
    // =================================================================================================================

    static void backgroundImport(UiCase c) throws Exception {
        Saved saved = save(c);
        boolean ff = ffmpeg();
        Path work = Files.createTempDirectory("testkit-themebg-");
        try {
            String defaults = c.onClient(mc -> defaultsWhy(cfg()));
            c.check(defaults == null, defaults);
            c.onClient(mc -> {
                setMenuTheme(mc, "AMBER");
                return null;
            });

            // Control: no background, the scene draws and the player draws nothing.
            title(c);
            c.ticks(5);
            int draws0 = c.onClient(mc -> (Integer) Mod.staticCall(BG_PLAYER, "draws"));
            c.ticks(10);
            int draws1 = c.onClient(mc -> (Integer) Mod.staticCall(BG_PLAYER, "draws"));
            Pic scene = pic(c, "amber-scene");
            c.note("no background: player draws " + draws0 + " -> " + draws1 + ", scene sample " + scene);
            c.check(draws1 == draws0, "with no background set the player drew " + (draws1 - draws0) + " frames");
            c.check(!scene.magenta(), "the Amber scene already reads magenta, so the import check below proves nothing");

            // A magenta GIF through the clipboard path (quoted, as Explorer's Copy as path gives it).
            Path gif = work.resolve("Testkit Magenta.gif");
            if (ff) {
                run("ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", "color=c=magenta:s=320x180:r=10", "-t", "1",
                        gif.toString());
            } else {
                BufferedImage img = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
                for (int y = 0; y < 180; y++) {
                    for (int x = 0; x < 320; x++) {
                        img.setRGB(x, y, 0xFF00FF);
                    }
                }
                ImageIO.write(img, "gif", gif.toFile());
            }
            String gifId = importAndWait(c, BG, "importClipboardAsync", "\"" + gif + "\"");
            String gifMsg = c.onClient(mc -> (String) Mod.staticCall(BG, "lastImport"));
            c.note("GIF import: " + gifId + " - " + gifMsg);
            c.check("gif:Testkit Magenta.gif".equals(gifId), "the GIF import answered " + gifId + ": " + gifMsg);
            Path folder = c.onClient(mc -> (Path) Mod.staticCall(BG, "folder"));
            c.check(Files.isRegularFile(folder.resolve("Testkit Magenta.gif"))
                            && Files.size(folder.resolve("Testkit Magenta.gif")) == Files.size(gif),
                    "the GIF was not copied into the backgrounds folder as it is");
            c.onClient(mc -> {
                Mod.call(cfg(), "setBackground", theme("AMBER"), gifId);
                return null;
            });
            title(c);
            boolean drawn = false;
            for (int i = 0; i < 200 && !drawn; i++) {
                c.ticks(1);
                drawn = c.onClient(mc -> (Integer) Mod.staticCall(BG_PLAYER, "draws")) > draws1;
            }
            c.ticks(5);
            Object[] st = c.onClient(mc -> new Object[]{Mod.staticCall(BG_PLAYER, "loadedId"),
                    Mod.staticCall(BG_PLAYER, "loadedShape"), Mod.staticCall(BG_PLAYER, "uploads"),
                    Mod.staticCall(BG_PLAYER, "lastError")});
            Pic magenta = pic(c, "amber-gif");
            c.note("GIF background: drawn " + drawn + ", loaded " + st[0] + " " + st[1] + ", uploads " + st[2] + ", error "
                    + st[3] + ", sample " + magenta);
            c.check(drawn, "the imported GIF was never drawn on the title screen (error " + st[3] + ")");
            c.check(gifId.equals(st[0]), "the player loaded " + st[0]);
            c.check((Integer) st[2] >= 1, "no frame was uploaded");
            c.check(magenta.magenta(), "the title screen does not show the magenta GIF: " + magenta);

            // Another theme with none keeps its scene.
            c.onClient(mc -> {
                setMenuTheme(mc, "DARK");
                return null;
            });
            c.ticks(5);
            int d0 = c.onClient(mc -> (Integer) Mod.staticCall(BG_PLAYER, "draws"));
            c.ticks(10);
            int d1 = c.onClient(mc -> (Integer) Mod.staticCall(BG_PLAYER, "draws"));
            Pic dark = pic(c, "dark-scene");
            c.check(d1 == d0, "Dark (no background) drew the imported one " + (d1 - d0) + " times");
            c.check(!dark.magenta(), "Dark's title screen reads magenta: " + dark);
            c.check(c.onClient(mc -> Mod.staticCall(BG_PLAYER, "loadedId")) == null,
                    "Dark kept the GIF loaded (its texture is not released)");

            // A video: ffmpeg frames, played.
            if (ff) {
                Path mp4 = work.resolve("testkit pattern.mp4");
                run("ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", "testsrc=size=640x360:rate=25", "-t", "2",
                        "-pix_fmt", "yuv420p", mp4.toString());
                String vid = importAndWait(c, BG, "importAsync", mp4);
                String vidMsg = c.onClient(mc -> (String) Mod.staticCall(BG, "lastImport"));
                c.note("video import: " + vid + " - " + vidMsg);
                c.check(vid != null && vid.startsWith("video:"), "the video import answered " + vid + ": " + vidMsg);
                if (vid != null) {
                    long frames;
                    try (var s = Files.list(folder.resolve(vid.substring("video:".length())))) {
                        frames = s.filter(p -> p.toString().endsWith(".jpg")).count();
                    }
                    c.check(frames >= 35 && frames <= 45, "a 2 s video at 20 fps made " + frames + " frames");
                    c.onClient(mc -> {
                        Mod.call(cfg(), "setBackground", theme("DARK"), vid);
                        return null;
                    });
                    int up0 = c.onClient(mc -> (Integer) Mod.staticCall(BG_PLAYER, "uploads"));
                    java.util.Set<Integer> shown = new java.util.HashSet<>();
                    for (int i = 0; i < 60; i++) {
                        c.ticks(1);
                        shown.add(c.onClient(mc -> (Integer) Mod.staticCall(BG_PLAYER, "shownFrame")));
                    }
                    Object[] v = c.onClient(mc -> new Object[]{Mod.staticCall(BG_PLAYER, "loadedId"),
                            Mod.staticCall(BG_PLAYER, "loadedShape"), Mod.staticCall(BG_PLAYER, "uploads"),
                            Mod.staticCall(BG_PLAYER, "lastError")});
                    shown.remove(-1);
                    pic(c, "dark-video");
                    c.note("video background: loaded " + v[0] + " " + v[1] + ", uploads " + up0 + " -> " + v[2]
                            + ", distinct frames shown " + shown.size() + ", error " + v[3]);
                    c.check(vid.equals(v[0]), "the player loaded " + v[0] + " for the video");
                    // 640x360 is under the 960x540 cap, so it keeps its own size (never scaled up).
                    c.check(String.valueOf(v[1]).startsWith("640x360"), "the video frames are " + v[1]);
                    c.check(shown.size() >= 10, "the video showed only " + shown.size() + " distinct frames in 3 s");
                }
            } else {
                c.note("SKIPPED the video half: ffmpeg is not on this client's PATH");
            }

            // A broken file is refused, quietly.
            Path broken = work.resolve("not a video.mp4");
            Files.writeString(broken, "this is not a video at all");
            long mark = LogTap.mark();
            String bad = importAndWait(c, BG, "importAsync", broken);
            String badMsg = c.onClient(mc -> (String) Mod.staticCall(BG, "lastImport"));
            c.check(bad == null, "a text file imported as " + bad);
            c.check(badMsg != null && badMsg.startsWith("§c"), "the refusal says " + badMsg);
            c.check(LogTap.modErrorsSince(mark).isEmpty(), "the refusal logged an ERROR: " + LogTap.modErrorsSince(mark));
            c.note("broken file: " + badMsg);
        } finally {
            restore(c, saved);
            c.onClient(mc -> {
                Mod.staticCall(BG_PLAYER, "release");
                return null;
            });
            deleteTree(work);
            Path folder = c.onClient(mc -> (Path) Mod.staticCall(BG, "folder"));
            Files.deleteIfExists(folder.resolve("Testkit Magenta.gif"));
            try (var s = Files.list(folder)) {
                for (Path p : s.toList()) {
                    if (p.getFileName().toString().startsWith("testkit pattern")) {
                        deleteTree(p);
                    }
                }
            }
        }
    }

    record Pic(double r, double g, double b) {
        boolean magenta() {
            return r > 180 && b > 180 && g < 90;
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.ROOT, "rgb(%.0f,%.0f,%.0f)", r, g, b);
        }
    }

    /** A screenshot's average color over the left fifth of the screen (background only: the button panel is centered). */
    static Pic pic(UiCase c, String label) throws Exception {
        Path p = c.ctx().takeScreenshot(Report.fileName(c.name() + "-" + label));
        Report.screenshot(c.name(), p);
        BufferedImage img = ImageIO.read(p.toFile());
        long r = 0;
        long g = 0;
        long b = 0;
        long n = 0;
        int w = img.getWidth() / 5;
        for (int y = img.getHeight() / 4; y < img.getHeight() * 3 / 4; y += 3) {
            for (int x = 2; x < w; x += 3) {
                int px = img.getRGB(x, y);
                r += (px >> 16) & 0xFF;
                g += (px >> 8) & 0xFF;
                b += px & 0xFF;
                n++;
            }
        }
        return new Pic(r / (double) n, g / (double) n, b / (double) n);
    }

    static void deleteTree(Path p) {
        try {
            if (Files.isDirectory(p)) {
                try (var s = Files.list(p)) {
                    for (Path q : s.toList()) {
                        deleteTree(q);
                    }
                }
            }
            Files.deleteIfExists(p);
        } catch (Exception ignored) {
        }
    }

    // =================================================================================================================
    // 1021-theme-sound-import
    // =================================================================================================================

    static void soundImport(UiCase c) throws Exception {
        Saved saved = save(c);
        boolean ff = ffmpeg();
        Path work = Files.createTempDirectory("testkit-thememusic-");
        List<String> made = new ArrayList<>();
        try {
            String defaults = c.onClient(mc -> defaultsWhy(cfg()));
            c.check(defaults == null, defaults);
            if (!ff) {
                c.note("SKIPPED: ffmpeg is not on this client's PATH, so no test sound can be made");
                return;
            }
            Path ogg = work.resolve("Testkit Tone.ogg");
            run("ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", "sine=frequency=440:duration=6", "-c:a", "libvorbis",
                    ogg.toString());
            Path mp3 = work.resolve("Testkit Song.mp3");
            run("ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", "sine=frequency=330:duration=6", mp3.toString());

            String oggId = importAndWait(c, MUSIC_FILES, "importClipboardAsync", "\"" + ogg + "\"");
            String oggMsg = c.onClient(mc -> (String) Mod.staticCall(MUSIC_FILES, "lastImport"));
            Path folder = c.onClient(mc -> (Path) Mod.staticCall(MUSIC_FILES, "folder"));
            c.note(".ogg import: " + oggId + " - " + oggMsg);
            c.check("import:Testkit Tone.ogg".equals(oggId), "the .ogg import answered " + oggId + ": " + oggMsg);
            c.check(Files.isRegularFile(folder.resolve("Testkit Tone.ogg"))
                    && Files.size(folder.resolve("Testkit Tone.ogg")) == Files.size(ogg), "the .ogg was not copied as it is");
            made.add("Testkit Tone.ogg");

            String mp3Id = importAndWait(c, MUSIC_FILES, "importAsync", mp3);
            String mp3Msg = c.onClient(mc -> (String) Mod.staticCall(MUSIC_FILES, "lastImport"));
            c.note(".mp3 import: " + mp3Id + " - " + mp3Msg);
            c.check("import:Testkit Song.ogg".equals(mp3Id), "the .mp3 import answered " + mp3Id + ": " + mp3Msg);
            made.add("Testkit Song.ogg");

            // It is offered on the Music row and plays through the stream hook.
            boolean listed = c.onClient(mc -> ((List<?>) Mod.staticCall(MUSIC, "choices")).contains(mp3Id));
            c.check(listed, "the imported track is not among the Music choices");
            int streams0 = c.onClient(mc -> (Integer) Mod.staticCall(MUSIC_FILES, "streamsOpened"));
            c.onClient(mc -> {
                setMenuTheme(mc, "AMBER");
                Object cfg = cfg();
                Mod.call(cfg, "setMusic", theme("AMBER"), mp3Id);
                Mod.call(cfg, "setMusicOn", true);
                McCompat.setScreen(mc, new TitleScreen());
                return null;
            });
            boolean opened = false;
            for (int i = 0; i < 100 && !opened; i++) {
                c.ticks(1);
                opened = c.onClient(mc -> (Integer) Mod.staticCall(MUSIC_FILES, "streamsOpened")) > streams0;
            }
            c.ticks(40);
            Object[] st = c.onClient(mc -> new Object[]{Mod.staticCall(MUSIC, "currentKey"), Mod.staticCall(MUSIC, "engineActive"),
                    Mod.staticCall(MUSIC, "lastResult"), Mod.staticCall(MUSIC, "lastError"),
                    Mod.staticCall(MUSIC_FILES, "streamsOpened")});
            c.note("imported track: playing " + st[0] + ", engine active " + st[1] + ", result " + st[2] + ", streams "
                    + streams0 + " -> " + st[4] + ", error " + st[3]);
            c.check(String.valueOf(st[0]).startsWith("killer560smod:thememusic/"), "Amber plays " + st[0] + ", not the import");
            c.check(opened, "the engine never asked the mod's stream hook for the imported file");
            c.check(Boolean.TRUE.equals(st[1]), "the imported track is not live in the engine 40 ticks on (" + st[2] + ")");
            String others = c.onClient(mc -> {
                for (String t : ALL_THEMES) {
                    if (!t.equals("AMBER") && !"theme".equals(Mod.call(cfg(), "getMusic", theme(t)))) {
                        return t + " changed to " + Mod.call(cfg(), "getMusic", theme(t));
                    }
                }
                return null;
            });
            c.check(others == null, "importing for Amber changed another theme: " + others);

            // Not a sound: refused, no ERROR.
            Path broken = work.resolve("Not Sound.ogg");
            Files.writeString(broken, "definitely not an ogg vorbis stream");
            long mark = LogTap.mark();
            String bad = importAndWait(c, MUSIC_FILES, "importAsync", broken);
            String badMsg = c.onClient(mc -> (String) Mod.staticCall(MUSIC_FILES, "lastImport"));
            c.note("broken file: " + bad + " - " + badMsg);
            c.check(bad == null, "a text file imported as " + bad);
            c.check(badMsg != null && badMsg.startsWith("§c"), "the refusal says " + badMsg);
            c.check(LogTap.modErrorsSince(mark).isEmpty(), "the refusal logged an ERROR: " + LogTap.modErrorsSince(mark));
        } finally {
            restore(c, saved);
            deleteTree(work);
            Path folder = c.onClient(mc -> (Path) Mod.staticCall(MUSIC_FILES, "folder"));
            for (String f : made) {
                Files.deleteIfExists(folder.resolve(f));
            }
        }
    }
}
