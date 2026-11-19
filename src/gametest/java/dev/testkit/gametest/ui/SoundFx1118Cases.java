package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 1118-ui-soundfx-options / 1118-ui-soundfx-volume (mod soundfx-1118, killer560 2026-11-18: "a few more custom options ...
 * then also the slider for their volume").
 * <p>options: the six new presets (drop, coin, marimba, thud, zap, sparkle) are in the list, every Sound Effects row's REAL
 * selector reaches them by clicking, each one replaces a game sound (played id = the preset's) and persists through save+load.
 * <p>volume: every row has a Volume slider; 50% plays at half (the played gain), 0% plays nothing, 200% starts a second layer,
 * the slider itself (a real press) sets the value, it is saved and an old file with no volume loads as 100%.
 */
final class SoundFx1118Cases {

    private SoundFx1118Cases() {
    }

    private static final String CFG = "clicksound.SoundEffectConfig";
    private static final String FX = "clicksound.SoundEffects";
    private static final String CLICKS = "clicksound.ClickSounds";
    private static final String[] NEW = {"drop", "coin", "marimba", "thud", "zap", "sparkle"};

    private static Object effect(String key) {
        for (Object e : Mod.cls("clicksound.SoundEffect").getEnumConstants()) {
            if (key.equals(Mod.field(e, "key"))) {
                return e;
            }
        }
        throw new AssertionError("no effect " + key);
    }

    private static void play(UiCase c, String id) {
        c.onClient(mc -> {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(
                    SoundEvent.createVariableRangeEvent(Identifier.withDefaultNamespace(id)), 1.0f));
            return null;
        });
        c.ticks(2);
    }

    private static void openTab(UiCase c) {
        c.onClient(mc -> {
            Screen s;
            try {
                s = MenuACases.open(mc);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            Mod.call(s, "openFeature", MenuACases.leaf("SoundEffectsTab"));
            return null;
        });
        c.ticks(5);
    }

    private static void press(AbstractWidget w, int button, double fx) {
        w.mouseClicked(new MouseButtonEvent(w.getX() + w.getWidth() * fx, w.getY() + w.getHeight() / 2.0,
                new MouseButtonInfo(dev.testkit.compat.McKeys.mouse(button), 0)), false);
    }

    @SuppressWarnings("unchecked")
    static void options(UiCase c) throws Exception {
        Object cfg = c.onClient(mc -> Mod.cfg(CFG));
        Object arrow = effect("arrow_hit");
        Object on0 = Mod.call(cfg, "isOn", arrow);
        Object choice0 = Mod.call(cfg, "getChoice", arrow);
        List<Object> presets = (List<Object>) Mod.staticCall(CLICKS, "presets");
        Set<String> ids = new LinkedHashSet<>();
        for (Object p : presets) {
            ids.add((String) Mod.call(p, "id"));
        }
        List<String> problems = new ArrayList<>();
        try {
            for (String n : NEW) {
                if (!ids.contains(n)) {
                    problems.add("preset " + n + " missing from the list");
                }
            }
            c.check(ids.size() >= 12, "only " + ids.size() + " presets");
            Mod.call(cfg, "setChoice", arrow, "default");
            openTab(c);
            // A real selector: left clicks step forward through the list, every new preset must come up and be chosen.
            Set<String> seen = new LinkedHashSet<>();
            c.onClient(mc -> {
                AbstractWidget sel = null;
                for (AbstractWidget w : MenuACases.paneRows(McCompat.screen(mc))) {
                    if (w.getClass().getSimpleName().equals("SettingsButtonWidget") && (Boolean) Mod.call(w, "isCycling")) {
                        sel = w;
                        break;
                    }
                }
                if (sel == null) {
                    throw new AssertionError("no sound selector in the Sound Effects drawer");
                }
                for (int i = 0; i < 40 && !seen.containsAll(List.of(NEW)); i++) {
                    press(sel, 0, 0.75);
                    seen.add((String) Mod.call(Mod.cfg(CFG), "getChoice", arrow));
                }
                McCompat.setScreen(mc, null);
                return null;
            });
            c.check(seen.containsAll(List.of(NEW)), "the selector reached only " + seen);
            // Each new preset replaces the arrow sound, and survives save + load.
            Mod.call(cfg, "setOn", arrow, true);
            for (String n : NEW) {
                Mod.call(cfg, "setChoice", arrow, n);
                Mod.call(cfg, "save");
                Mod.staticCall(CFG, "load");
                if (!n.equals(Mod.call(Mod.cfg(CFG), "getChoice", arrow))) {
                    problems.add(n + " did not persist");
                }
                cfg = Mod.cfg(CFG);
                int before = (Integer) Mod.staticCall(FX, "replaced", "arrow_hit");
                play(c, "entity.arrow.hit_player");
                String last = (String) Mod.staticCall(FX, "lastPlayedId");
                if ((Integer) Mod.staticCall(FX, "replaced", "arrow_hit") != before + 1
                        || !last.equals("killer560smod:click_" + n)) {
                    problems.add(n + " played " + last);
                }
                c.ticks(3);
            }
            c.note("new presets reached by the selector: " + seen);
        } finally {
            cfg = Mod.cfg(CFG);
            Mod.call(cfg, "setOn", arrow, on0);
            Mod.call(cfg, "setChoice", arrow, choice0);
            Mod.call(cfg, "save");
        }
        c.check(problems.isEmpty(), String.join("; ", problems));
    }

    static void volume(UiCase c) throws Exception {
        Object cfg = c.onClient(mc -> Mod.cfg(CFG));
        Object arrow = effect("arrow_hit");
        Object on0 = Mod.call(cfg, "isOn", arrow);
        Object choice0 = Mod.call(cfg, "getChoice", arrow);
        Object vol0 = Mod.call(cfg, "getVolume", arrow);
        Path file = (Path) Mod.staticCall("util.ModPaths", "config", "killer560smod-sound-effects.json");
        byte[] disk = Files.exists(file) ? Files.readAllBytes(file) : null;
        List<String> problems = new ArrayList<>();
        try {
            Mod.call(cfg, "setOn", arrow, true);
            Mod.call(cfg, "setChoice", arrow, "pop");
            // 100%: the game's volume (1.0) unchanged.
            Mod.call(cfg, "setVolume", arrow, 1.0f);
            play(c, "entity.arrow.hit_player");
            expectGain(problems, "100%", 1.0f);
            Mod.call(cfg, "setVolume", arrow, 0.5f);
            play(c, "entity.arrow.hit_player");
            expectGain(problems, "50%", 0.5f);
            // 0%: counted as acted, but nothing is played.
            Mod.call(cfg, "setVolume", arrow, 0.0f);
            int layers0 = (Integer) Mod.staticCall(CLICKS, "layersPlayed");
            play(c, "entity.arrow.hit_player");
            expectGain(problems, "0%", 0.0f);
            if (!"silent".equals(Mod.staticCall(FX, "lastPlayedId"))
                    || (Integer) Mod.staticCall(CLICKS, "layersPlayed") != layers0) {
                problems.add("0% played " + Mod.staticCall(FX, "lastPlayedId"));
            }
            // 200%: full first copy plus one layer.
            Mod.call(cfg, "setVolume", arrow, 2.0f);
            play(c, "entity.arrow.hit_player");
            expectGain(problems, "200%", 2.0f);
            if ((Integer) Mod.staticCall(CLICKS, "layersPlayed") != layers0 + 1) {
                problems.add("200% started " + ((Integer) Mod.staticCall(CLICKS, "layersPlayed") - layers0) + " layers, not 1");
            }
            // The slider widget: a real press at the quarter point (50%).
            Mod.call(cfg, "setVolume", arrow, 1.0f);
            openTab(c);
            float[] got = {-1};
            c.onClient(mc -> {
                AbstractWidget slider = null;
                for (AbstractWidget w : MenuACases.paneRows(McCompat.screen(mc))) {
                    if (w.getMessage().getString().equals("Arrow Hit Volume: 100%")) {
                        slider = w;
                    }
                }
                if (slider == null) {
                    throw new AssertionError("no 'Arrow Hit Volume: 100%' slider in the drawer");
                }
                press(slider, 0, 0.25);
                got[0] = (Float) Mod.call(Mod.cfg(CFG), "getVolume", arrow);
                McCompat.setScreen(mc, null);
                return null;
            });
            if (got[0] < 0.4f || got[0] > 0.6f) {
                problems.add("a press at 25% of the slider set " + got[0]);
            }
            // Saved and loaded again; an old file with no volume key is 100%.
            Mod.call(Mod.cfg(CFG), "setVolume", arrow, 1.35f);
            Mod.call(Mod.cfg(CFG), "save");
            Mod.staticCall(CFG, "load");
            float back = (Float) Mod.call(Mod.cfg(CFG), "getVolume", arrow);
            if (Math.abs(back - 1.35f) > 0.001f) {
                problems.add("volume did not persist: " + back);
            }
            Files.writeString(file, "{\"arrow_hit\": {\"on\": true, \"choice\": \"pop\"}}", StandardCharsets.UTF_8);
            Mod.staticCall(CFG, "load");
            float old = (Float) Mod.call(Mod.cfg(CFG), "getVolume", arrow);
            if (old != 1.0f) {
                problems.add("an old file loaded at " + old);
            }
        } finally {
            if (disk != null) {
                Files.write(file, disk);
            } else {
                Files.deleteIfExists(file);
            }
            Mod.staticCall(CFG, "load");
            Object fresh = Mod.cfg(CFG);
            Mod.call(fresh, "setOn", arrow, on0);
            Mod.call(fresh, "setChoice", arrow, choice0);
            Mod.call(fresh, "setVolume", arrow, vol0);
        }
        c.check(problems.isEmpty(), String.join("; ", problems));
    }

    private static void expectGain(List<String> problems, String what, float want) {
        float got = (Float) Mod.staticCall(FX, "lastVolume");
        if (Math.abs(got - want) > 0.01f) {
            problems.add(what + " played at gain " + got + ", expected " + want);
        }
    }
}
