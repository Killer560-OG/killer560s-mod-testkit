package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 1060-1064: killer560's 2026-10-15 click sounds and short descriptions.
 * 1060 measures every feature card's description at several card widths (none may be cut or end in "..."), 1061 the
 * Show Descriptions switch (menu cards and the HUD editor hints), 1062 the HUD editor hint at narrow windows, 1063 the click
 * sound presets and the Vanilla Screens option, 1064 an imported .ogg.
 */
final class SoundDescCases {

    private static final String CARDS = "gui.FeatureCards";
    private static final String DESC_CFG = "gui.DescriptionConfig";
    private static final String CLICK_CFG = "clicksound.ClickSoundConfig";
    private static final String CLICKS = "clicksound.ClickSounds";

    /** Card widths the grid can lay out: 104 is the narrowest (CARD_MIN_W), the rest are what wider windows give. */
    private static final int[] WIDTHS = {104, 108, 115, 124, 131, 150, 180, 240};
    private static final int CARD_H = 66;

    private SoundDescCases() {
    }

    // ---- shared ---------------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static List<Object> leaves() {
        return (List<Object>) Mod.staticCall("gui.ModScreen", "allLeaves");
    }

    private static String name(Object tab) {
        return (String) R.get(tab, "name");
    }

    @SuppressWarnings("unchecked")
    private static List<String> list(Object record, String accessor) {
        return (List<String>) Mod.call(record, accessor);
    }

    private static Object cardText(Object tab, int w, boolean hasSwitch) {
        return Mod.staticCall(CARDS, "cardText", tab, w, CARD_H, hasSwitch);
    }

    // ---- 1060------------------------------------------------------------------------------------------------------

    static void fit(UiCase c) throws Exception {
        try (AutoCloseable all = MenuLayoutCases.allTested(c);
             AutoCloseable on = Mod.with(DESC_CFG, "ShowMenu", true)) {
            MenuLayoutCases.driver(c); // the menu's own tab list, built as the player gets it
            int[] checked = {0};
            List<String> dump = new ArrayList<>();
            c.onClient(mc -> {
                List<Object> tabs = leaves();
                for (Object tab : tabs) {
                    String n = name(tab);
                    boolean hasSwitch = Mod.staticCall(CARDS, "state", tab) != null;
                    String desc = (String) Mod.staticCall(CARDS, "description", tab);
                    if (desc == null || desc.isBlank()) {
                        c.problem(n + ": no description");
                        continue;
                    }
                    if (desc.contains("...") || desc.contains("…")) {
                        c.problem(n + ": the description itself holds an ellipsis: " + desc);
                    }
                    for (int w : WIDTHS) {
                        Object text = cardText(tab, w, hasSwitch);
                        boolean truncated = (Boolean) Mod.call(text, "truncated");
                        List<String> lines = list(text, "desc");
                        String joined = String.join(" ", lines);
                        if (truncated || joined.contains("...") || !joined.equals(desc.trim().replaceAll("\\s+", " "))) {
                            c.problem(n + " at card width " + w + (hasSwitch ? " (switch)" : "") + ": '" + desc
                                    + "' is drawn as " + lines);
                        }
                        checked[0]++;
                    }
                    int nameLines = list(cardText(tab, 104, hasSwitch), "name").size();
                    String raw = (String) Mod.staticCall(CARDS, "rawDescription", tab);
                    dump.add(String.format("%s | %s | switch=%s name=%d | %s | cur=%s", tab.getClass().getSimpleName(), n, hasSwitch, nameLines, raw, desc));
                }
                @SuppressWarnings("unchecked")
                List<String> cut = (List<String>) Mod.staticCall(CARDS, "autoCut");
                if (!cut.isEmpty()) {
                    c.problem("these cards only fit because the menu cut their text (write a shorter one in CardBlurbsData): " + cut);
                }
                c.note(tabs.size() + " cards");
                return null;
            });
            for (String line : dump) {
                System.out.println("[1060] CARD " + line);
            }
            c.check(checked[0] > (Mod.isCheat() ? 1000 : 700), "only " + checked[0] + " card widths measured - the walk did not reach the menu");
            c.note(checked[0] + " card widths measured, none cut");
        }
    }

    // ---- 521 ------------------------------------------------------------------------------------------------------

    static void toggle(UiCase c) throws Exception {
        try (AutoCloseable all = MenuLayoutCases.allTested(c);
             AutoCloseable restore = Mod.with(DESC_CFG, "ShowMenu", true);
             AutoCloseable restoreEditor = Mod.with(DESC_CFG, "ShowEditor", true)) {
            ModScreenDriver d = MenuLayoutCases.driver(c);
            Object tab = null;
            for (Object t : c.onClient(mc -> leaves())) {
                if (name(t).equals("Descriptions")) {
                    tab = t;
                }
            }
            c.check(tab != null, "no Descriptions tab in the menu");
            // The switch exists, is on, and pressing it flips the config (the card layout follows).
            Object probe = null;
            for (Object t : c.onClient(mc -> leaves())) {
                if (!name(t).equals("Descriptions") && Mod.staticCall(CARDS, "state", t) != null) {
                    probe = t;
                    break;
                }
            }
            Object card = probe;
            int shown = c.onClient(mc -> list(cardText(card, 150, true), "desc").size());
            c.check(shown > 0, "with Show Descriptions on, a card draws no description lines");
            Object descTab = tab;
            c.onClient(mc -> {
                var widgets = (List<?>) Mod.call(descTab, "buildWidgets", 0, 0, 300, (Runnable) () -> { });
                int pressed = 0;
                for (Object w : widgets) {
                    if (((net.minecraft.client.gui.components.AbstractWidget) w).getMessage().getString().startsWith("Show Descriptions")) {
                        ((net.minecraft.client.gui.components.AbstractWidget) w).onClick(
                                new net.minecraft.client.input.MouseButtonEvent(0, 0, new net.minecraft.client.input.MouseButtonInfo(0, 0)), false);
                        pressed++;
                    }
                }
                if (pressed != 1) {
                    throw new AssertionError("found " + pressed + " Show Descriptions buttons");
                }
                return null;
            });
            boolean menuOn = (Boolean) Mod.get(DESC_CFG, "isShowMenu");
            c.check(!menuOn, "pressing Show Descriptions did not turn it off");
            int hidden = c.onClient(mc -> list(cardText(card, 150, true), "desc").size());
            c.check(hidden == 0, "Show Descriptions is off but the card still draws " + hidden + " description lines");
            boolean truncated = c.onClient(mc -> (Boolean) Mod.call(cardText(card, 150, true), "truncated"));
            c.check(!truncated, "a hidden description reads as truncated");
            Mod.set(DESC_CFG, "setShowMenu", true);
            int back = c.onClient(mc -> list(cardText(card, 150, true), "desc").size());
            c.check(back == shown, "turning Show Descriptions back on drew " + back + " lines, not " + shown);
            c.note("a card draws " + shown + " description lines on, 0 off, " + back + " back on");

            // HUD editor: the hint chip is drawn on, not drawn off (the empty-editor line stays either way).
            String on = editorHint(c, true);
            String off = editorHint(c, false);
            c.check(!on.isEmpty(), "Show Editor Descriptions is on but the HUD editor drew no hint");
            c.check(off.isEmpty(), "Show Editor Descriptions is off but the HUD editor still drew: " + off);
            c.note("editor hint on: '" + on + "', off: '" + off + "'");
        }
    }

    /** The hint line a HUD editor with some element listed draws with Show Editor Descriptions {@code editorOn}. */
    private static String editorHint(UiCase c, boolean editorOn) throws Exception {
        try (AutoCloseable showAll = Mod.with("hud.HudConfig", "EditorShowAll", true)) {
            Mod.set(DESC_CFG, "setShowEditor", editorOn);
            return openEditor(c, 0, 0);
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
        }
    }

    /** Opens the HUD editor (at GUI width {@code w} when given) and returns the hint it drew. */
    private static String openEditor(UiCase c, int w, int h) {
        c.onClient(mc -> {
            try {
                Screen editor = (Screen) Mod.cls("hud.HudEditorScreen").getConstructor(Screen.class).newInstance((Object) null);
                McCompat.setScreen(mc, editor);
                if (w > 0) {
                    editor.init(w, h);
                }
                return null;
            } catch (Exception e) {
                throw new AssertionError("could not open the HUD editor: " + UiCase.describe(e), e);
            }
        });
        c.ticks(3);
        return c.onClient(mc -> (String) R.get(McCompat.screen(mc), "hintShown"));
    }

    // ---- 522 ------------------------------------------------------------------------------------------------------

    static void editorNarrow(UiCase c) throws Exception {
        try (AutoCloseable on = Mod.with(DESC_CFG, "ShowEditor", true);
             AutoCloseable showAll = Mod.with("hud.HudConfig", "EditorShowAll", true)) {
            int[] widths = {120, 160, 200, 260, 320, 420, 640, 960};
            int measured = 0;
            for (int w : widths) {
                String shown = openEditor(c, w, 240);
                int width = c.onClient(mc -> mc.font.width(shown));
                if (shown.contains("...")) {
                    c.problem("editor hint at GUI width " + w + " ends in an ellipsis: " + shown);
                }
                if (shown.isEmpty()) {
                    c.problem("editor hint at GUI width " + w + " drew nothing");
                }
                if (width > w - 8 && w >= 160) {
                    c.problem("editor hint at GUI width " + w + " is " + width + " px wide, over its " + (w - 8) + " px room");
                }
                c.note("width " + w + ": " + width + " px '" + shown + "'");
                measured++;
            }
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
            c.check(measured == widths.length, "measured " + measured + " widths");
        }
    }

    // ---- 523 ------------------------------------------------------------------------------------------------------

    private static String click(UiCase c) {
        // A real SoundManager.play of vanilla's button click: whatever the mixin hands the engine is recorded.
        c.onClient(mc -> {
            mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                    net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 1.0f, 1.0f));
            return null;
        });
        return (String) Mod.staticCall(CLICKS, "lastPlayedId");
    }

    private static void openScreen(UiCase c, boolean modScreen) {
        c.onClient(mc -> {
            try {
                Screen s = modScreen
                        ? (Screen) Mod.cls("gui.ModScreen").getConstructor(Screen.class).newInstance((Object) null)
                        : new Screen(net.minecraft.network.chat.Component.literal("vanilla stand-in")) { };
                McCompat.setScreen(mc, s);
                return null;
            } catch (Exception e) {
                throw new AssertionError("could not open the screen: " + UiCase.describe(e), e);
            }
        });
        c.ticks(2);
    }

    static void presets(UiCase c) throws Exception {
        try (AutoCloseable a = Mod.with(CLICK_CFG, "Choice", "default");
             AutoCloseable b = Mod.with(CLICK_CFG, "VanillaScreens", false)) {
            List<?> presets = (List<?>) Mod.staticCall(CLICKS, "presets");
            c.check(presets.size() >= 4, "only " + presets.size() + " click presets");
            // Control: Default leaves vanilla's own click alone, on a mod screen.
            openScreen(c, true);
            Mod.set(CLICK_CFG, "setChoice", "default");
            int replacedBefore = (Integer) Mod.staticCall(CLICKS, "clicksReplaced");
            String id = click(c);
            c.check("minecraft:ui.button.click".equals(id), "Default played " + id + ", not vanilla's click");
            c.check((Integer) Mod.staticCall(CLICKS, "clicksReplaced") == replacedBefore, "Default counted a swap");

            for (Object p : presets) {
                String pid = (String) Mod.call(p, "id");
                Mod.set(CLICK_CFG, "setChoice", pid);
                // On the mod's own screen the preset's event plays.
                String got = click(c);
                c.check(("killer560smod:click_" + pid).equals(got), "preset '" + pid + "' on the mod menu played " + got);
            }
            // A REAL button press in the mod's menu (not a hand-made play call): the Vanilla Screens button's own mouseClicked.
            Mod.set(CLICK_CFG, "setChoice", "tick");
            int seenBefore = (Integer) Mod.staticCall(CLICKS, "clicksSeen");
            c.onClient(mc -> {
                for (Object t : leaves()) {
                    if (name(t).equals("Click Sounds")) {
                        var widgets = (List<?>) Mod.call(t, "buildWidgets", 10, 10, 300, (Runnable) () -> { });
                        for (Object w : widgets) {
                            var aw = (net.minecraft.client.gui.components.AbstractWidget) w;
                            if (aw.getMessage().getString().startsWith("Vanilla Screens")) {
                                aw.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(aw.getX() + 3, aw.getY() + 3,
                                        new net.minecraft.client.input.MouseButtonInfo(0, 0)), false);
                                return null;
                            }
                        }
                    }
                }
                throw new AssertionError("no Vanilla Screens button to press");
            });
            c.check((Integer) Mod.staticCall(CLICKS, "clicksSeen") == seenBefore + 1,
                    "pressing a button in the mod menu did not reach the click sound hook");
            c.check("killer560smod:click_tick".equals(Mod.staticCall(CLICKS, "lastPlayedId")),
                    "a real button press played " + Mod.staticCall(CLICKS, "lastPlayedId") + ", not the Tick preset");
            Mod.set(CLICK_CFG, "setVanillaScreens", false);
            // The same click on a vanilla screen stays vanilla while Vanilla Screens is off ...
            openScreen(c, false);
            String vanillaOff = click(c);
            c.check("minecraft:ui.button.click".equals(vanillaOff),
                    "Vanilla Screens is off but a click on a Minecraft (non-mod) screen played " + vanillaOff);
            // ... and takes the chosen sound once it is on.
            Mod.set(CLICK_CFG, "setVanillaScreens", true);
            Object first = presets.get(0);
            Mod.set(CLICK_CFG, "setChoice", Mod.call(first, "id"));
            String vanillaOn = click(c);
            c.check(("killer560smod:click_" + Mod.call(first, "id")).equals(vanillaOn),
                    "Vanilla Screens is on but a click on a Minecraft (non-mod) screen played " + vanillaOn);
            c.note("default -> vanilla click; each of " + presets.size() + " presets plays its own event on the mod menu; "
                    + "non-mod screen: vanilla with Vanilla Screens off, '" + vanillaOn + "' with it on");
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
        }
    }

    // ---- 524 ------------------------------------------------------------------------------------------------------

    static void imported(UiCase c) throws Exception {
        Path dir = (Path) Mod.staticCall(CLICKS, "importDir");
        Path source = Files.createTempFile("k560-click-", ".ogg");
        Path bad = Files.createTempFile("k560-notogg-", ".ogg");
        Path copy = null;
        try (AutoCloseable a = Mod.with(CLICK_CFG, "Choice", "default");
             AutoCloseable b = Mod.with(CLICK_CFG, "VanillaScreens", false)) {
            // A real bundled preset is the one valid .ogg we have: take it out of the running jar.
            try (var in = Mod.cls("clicksound.ClickSounds").getResourceAsStream("/assets/killer560smod/sounds/click_pop.ogg")) {
                c.check(in != null, "the bundled click_pop.ogg is not in the jar");
                Files.write(source, in.readAllBytes());
            }
            Files.writeString(bad, "this is not an ogg file");
            // A bad file is refused and nothing lands in the folder.
            List<String> before = importedNames();
            boolean refused = false;
            try {
                Mod.staticCall(CLICKS, "importFile", bad);
            } catch (Throwable t) {
                refused = true;
            }
            c.check(refused, "a text file renamed .ogg was imported");
            c.check(importedNames().equals(before), "the refused file still reached the folder");
            // A good one is accepted, listed and selectable.
            String saved = (String) Mod.staticCall(CLICKS, "importFile", source);
            copy = dir.resolve(saved);
            c.check(Files.isRegularFile(copy), "the import did not copy the file to " + saved);
            c.check(importedNames().contains(saved), "the imported file is not in the choices: " + importedNames());
            String choice = "import:" + saved;
            Mod.set(CLICK_CFG, "setChoice", choice);
            openScreen(c, true);
            String got = click(c);
            c.check(got.startsWith("killer560smod:imported/"), "an imported click played " + got);
            // The engine can actually read it: ask the buffer path the mixin answers.
            Object buffer = c.onClient(mc -> {
                try {
                    var id = net.minecraft.resources.Identifier.parse("killer560smod:sounds/" + got.substring("killer560smod:".length()) + ".ogg");
                    var lib = new net.minecraft.client.sounds.SoundBufferLibrary(mc.getResourceManager());
                    return lib.getCompleteBuffer(id).get(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new AssertionError("the sound engine could not read the imported file: " + UiCase.describe(e), e);
                }
            });
            c.check(buffer instanceof com.mojang.blaze3d.audio.SoundBuffer sb && sb.size() > 1000,
                    "the imported file decoded to nothing: " + buffer);
            c.note("imported " + saved + " (" + ((com.mojang.blaze3d.audio.SoundBuffer) buffer).size() + " bytes of audio) plays as " + got);
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
        } finally {
            Files.deleteIfExists(source);
            Files.deleteIfExists(bad);
            if (copy != null) {
                Files.deleteIfExists(copy);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> importedNames() {
        return (List<String>) Mod.staticCall(CLICKS, "importedFiles");
    }
}
